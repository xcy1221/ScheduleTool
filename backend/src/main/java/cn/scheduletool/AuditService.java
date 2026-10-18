package cn.scheduletool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.context.request.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.*;

@Service
public class AuditService {
    private static final Logger LOG=LoggerFactory.getLogger(AuditService.class);
    static final String GENESIS = "0".repeat(64);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public AuditService(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }
    public record Entry(long sequence, String seriesId, String requestId, JsonNode data,
                        String previousHash, String hash) {}
    public record Verification(boolean valid, long checked, long lastSequence, String lastHash,
                               Long brokenSequence, String detail) {}
    private record Stored(long sequence, String seriesId, String requestId, String payload,
                          String previousHash, String hash) {}
    private Stored row(ResultSet rs, int n) throws SQLException {
        return new Stored(rs.getLong("sequence_id"),rs.getString("series_id"),rs.getString("request_id"),
            rs.getString("payload"),rs.getString("previous_hash"),rs.getString("record_hash"));
    }
    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch(JsonProcessingException e) { throw new IllegalStateException("审计数据损坏",e); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JsonProcessingException e) { throw new IllegalStateException("无法生成审计快照",e); }
    }
    private Map<String,Object> snapshot(String id) {
        var rows=db.query("SELECT * FROM schedule_events WHERE id=?",this::snapshotRow,id);
        if(rows.isEmpty()) return null;
        var result=new LinkedHashMap<String,Object>();
        result.put("series",rows.getFirst());
        result.put("exceptions",db.query("SELECT * FROM event_exceptions WHERE series_id=? ORDER BY occurrence_date",this::snapshotRow,id));
        return result;
    }
    private Map<String,Object> snapshotRow(ResultSet rs,int n) throws SQLException {
        var result=new LinkedHashMap<String,Object>();
        var meta=rs.getMetaData();
        for(int i=1;i<=meta.getColumnCount();i++) {
            String key=meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
            Object value=switch(meta.getColumnType(i)) {
                case java.sql.Types.TIMESTAMP -> rs.getObject(i,LocalDateTime.class);
                case java.sql.Types.DATE -> rs.getObject(i,LocalDate.class);
                default -> rs.getObject(i);
            };
            result.put(key,value instanceof java.time.temporal.TemporalAccessor ? value.toString() : value);
        }
        return result;
    }
    // Runs inside the business transaction. A failed audit INSERT rolls back the business write.
    // Serialize before reading snapshots, so concurrent changes cannot fork the chain or mix snapshots.
    @Transactional(propagation=Propagation.MANDATORY)
    public <T> T change(String action,String id,EventService.Scope scope,LocalDate date,Supplier<T> work) {
        if(db.queryForObject("SELECT id FROM audit_lock WHERE id=1 FOR UPDATE",Integer.class)==null)
            throw new IllegalStateException("审计锁缺失");
        var tail=db.query("SELECT * FROM audit_log ORDER BY sequence_id DESC LIMIT 1",this::row);
        long sequence=tail.isEmpty()?1:tail.getFirst().sequence()+1;
        String previous=tail.isEmpty()?GENESIS:tail.getFirst().hash();
        if(!tail.isEmpty() && !validRow(tail.getFirst())) throw new IllegalStateException("审计末条校验失败，停止写入");
        var before=snapshot(id);
        T result=work.get();
        var attributes=RequestContextHolder.getRequestAttributes();
        var req=attributes instanceof ServletRequestAttributes s?s.getRequest():null;
        var session=req==null?null:(Auth.Session)req.getAttribute("session");
        String requestId=req==null?UUID.randomUUID().toString():(String)req.getAttribute("requestId");
        if(requestId==null) requestId=UUID.randomUUID().toString();
        var data=new LinkedHashMap<String,Object>();
        data.put("schemaVersion",2); data.put("sequence",sequence); data.put("seriesId",id);
        data.put("requestId",requestId); data.put("action",action); data.put("scope",scope.name());
        data.put("occurrenceDate",date==null?null:date.toString()); data.put("recordedAt",Instant.now().toString());
        data.put("actor",session==null?"internal":session.username());
        data.put("authentication",req==null?"internal":req.getAttribute("authentication"));
        String source=req==null?null:req.getHeader("X-Schedule-Source");
        data.put("declaredSource",source!=null && Set.of("CODEX","CHATGPT","WEB","OTHER","UNKNOWN").contains(source)?source:
            req!=null && "cookie".equals(req.getAttribute("authentication"))?"WEB":"UNKNOWN");
        data.put("remoteAddress",req==null?null:req.getRemoteAddr());
        data.put("before",before); data.put("after",snapshot(id));
        String payload=encode(data),hash=Auth.hash(previous+"\n"+payload);
        db.update("INSERT INTO audit_log(sequence_id,series_id,request_id,payload,previous_hash,record_hash) VALUES(?,?,?,?,?,?)",
            sequence,id,requestId,payload,previous,hash);
        String committedRequestId=requestId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                // A second, database-external receipt. Database audit remains authoritative on a crash.
                LOG.info("audit committed sequence={} hash={} requestId={}",sequence,hash,committedRequestId);
                if(attributes instanceof ServletRequestAttributes s) {
                    s.getRequest().setAttribute("auditSequence",sequence);
                    if(s.getResponse()!=null) s.getResponse().setHeader("X-Audit-Sequence",Long.toString(sequence));
                }
            }
        });
        return result;
    }
    private boolean validRow(Stored entry) {
        try {
            JsonNode data=parse(entry.payload());
            return data.path("sequence").asLong()==entry.sequence()
                && data.path("seriesId").asText().equals(entry.seriesId())
                && data.path("requestId").asText().equals(entry.requestId())
                && Auth.hash(entry.previousHash()+"\n"+entry.payload()).equals(entry.hash());
        } catch(RuntimeException e) { return false; }
    }
    public List<Entry> list(long after,int limit,String seriesId,String requestId) {
        if(after<0 || limit<1 || limit>200) throw new ApiError.Failure(400,"after 须非负，limit 须为 1—200");
        return db.query("SELECT * FROM audit_log WHERE sequence_id>? AND (? IS NULL OR series_id=?) AND (? IS NULL OR request_id=?) ORDER BY sequence_id LIMIT ?",
            this::row,after,seriesId,seriesId,requestId,requestId,limit).stream()
            .map(e->new Entry(e.sequence(),e.seriesId(),e.requestId(),parse(e.payload()),e.previousHash(),e.hash())).toList();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Verification verify(Long expectedSequence,String expectedHash) {
        if((expectedSequence==null)!=(expectedHash==null) || (expectedSequence!=null && (expectedSequence<0 || !expectedHash.matches("[0-9a-f]{64}"))))
            throw new ApiError.Failure(400,"校验点须同时包含非负序号与 SHA-256");
        var invalid=db.query("SELECT sequence_id FROM audit_log WHERE sequence_id<=0 ORDER BY sequence_id LIMIT 1",(rs,n)->rs.getLong(1));
        if(!invalid.isEmpty()) return new Verification(false,0,0,GENESIS,invalid.getFirst(),"存在非法审计序号");
        long last=0; String hash=GENESIS; boolean anchor=expectedSequence==null || (expectedSequence==0 && GENESIS.equals(expectedHash));
        while(true) {
            var batch=db.query("SELECT * FROM audit_log WHERE sequence_id>? ORDER BY sequence_id LIMIT 200",this::row,last);
            if(batch.isEmpty()) break;
            for(var e:batch) {
                if(e.sequence()!=last+1 || !e.previousHash().equals(hash) || !validRow(e))
                    return new Verification(false,last,last,hash,e.sequence(),"序号、内容或哈希链不一致");
                last=e.sequence(); hash=e.hash();
                if(expectedSequence!=null && last==expectedSequence) anchor=hash.equals(expectedHash);
            }
        }
        return new Verification(anchor,last,last,hash,anchor?null:expectedSequence,anchor?"链内一致；不代表独立第三方存证":"与外部校验点不一致或记录已被截断");
    }
}
