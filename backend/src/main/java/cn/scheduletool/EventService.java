package cn.scheduletool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class EventService {
    private final JdbcTemplate db;
    private final AuditService audit;
    public EventService(JdbcTemplate db, AuditService audit) { this.db=db; this.audit=audit; }
    public enum Scope { SERIES, OCCURRENCE }
    public record Event(String id, String title, String description, LocalDateTime start, LocalDateTime end,
                        boolean allDay, String timeZone, EventInput.Repeat recurrence, LocalDate repeatUntil,
                        String color, long version, String todoId, String todoStatus) {}
    public record Occurrence(String id, String seriesId, LocalDate occurrenceDate, String title, String description,
                             LocalDateTime start, LocalDateTime end, boolean allDay, String timeZone,
                             EventInput.Repeat recurrence, LocalDate repeatUntil, String color, long version, boolean exception,String todoId,String todoStatus) {}
    private record ExceptionRow(LocalDate date, boolean cancelled, String title, String description,
                                LocalDateTime start, LocalDateTime end, boolean allDay, String color) {}
    private static LocalDateTime time(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        return rs.getObject(name, LocalDateTime.class);
    }
    private static final RowMapper<Event> MAPPER = (rs, n) -> new Event(rs.getString("id"), rs.getString("title"),
        rs.getString("description"), time(rs,"start_at"), time(rs,"end_at"), rs.getBoolean("all_day"), rs.getString("time_zone"),
        EventInput.Repeat.valueOf(rs.getString("recurrence")), rs.getObject("repeat_until", LocalDate.class), rs.getString("color"), rs.getLong("version"),rs.getString("todo_id"),rs.getString("todo_status"));
    private static final String SELECT="SELECT e.*,l.todo_id,t.status AS todo_status FROM schedule_events e LEFT JOIN todo_events l ON l.series_id=e.id LEFT JOIN todos t ON t.id=l.todo_id ";
    public Event get(String id) {
        return db.query(SELECT+"WHERE e.id=?", MAPPER, id).stream().findFirst().orElseThrow(() -> new ApiError.Failure(404,"日程不存在"));
    }
    @Transactional
    public Event create(EventInput raw) {
        var in = raw.normalized(); String id = UUID.randomUUID().toString(); var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        return audit.change("CREATE",id,Scope.SERIES,null,()->{
        db.update("INSERT INTO schedule_events(id,title,description,start_at,end_at,all_day,time_zone,recurrence,repeat_until,color,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,0,?,?)",
            id,in.title(),in.description(),in.start(),in.end(),in.allDay(),in.timeZone(),in.recurrence().name(),in.repeatUntil(),in.color(),now,now);
        associate(id,null,in.todoId(),in.todoVersion());
        return get(id);
        });
    }
    private void bump(Event e, long version) {
        if (e.version()!=version || db.update("UPDATE schedule_events SET version=version+1,updated_at=? WHERE id=? AND version=?",
            LocalDateTime.now(ZoneId.of("Asia/Shanghai")),e.id(),version)!=1) throw new ApiError.Failure(409,"日程已被修改，请刷新后重试");
    }
    private void touchTodo(String id) {
        if(id!=null) db.update("UPDATE todos SET version=version+1,updated_at=? WHERE id=?",LocalDateTime.now(ZoneId.of("Asia/Shanghai")),id);
    }
    // null preserves an existing link for old clients; empty string explicitly unlinks the whole series.
    private void associate(String series,String previous,String requested,Long version) {
        String target=requested==null?previous:requested.isBlank()?null:requested;
        if(!Objects.equals(previous,target)) {
            if(target!=null) {
                var versions=db.query("SELECT version FROM todos WHERE id=?",(rs,n)->rs.getLong(1),target);
                if(versions.isEmpty()) throw new ApiError.Failure(404,"待办不存在");
                if(version==null) throw new ApiError.Failure(400,"关联待办须提供 todoVersion");
                if(versions.getFirst().longValue()!=version) throw new ApiError.Failure(409,"待办或关联安排已被修改，请刷新后核对");
            }
            db.update("DELETE FROM todo_events WHERE series_id=?",series);
            if(target!=null) db.update("INSERT INTO todo_events(series_id,todo_id) VALUES(?,?)",series,target);
            touchTodo(previous);
        }
        touchTodo(target);
    }
    public record LinkInput(String todoId,Long todoVersion,@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long version) {}
    @Transactional public Event link(String id,LinkInput in) {
        if(in.todoId()==null) throw new ApiError.Failure(400,"todoId 填待办 ID，解除关联填空字符串");
        return audit.change("LINK_TODO",id,Scope.SERIES,null,()->{
            var e=get(id);bump(e,in.version());associate(id,e.todoId(),in.todoId(),in.todoVersion());return get(id);
        });
    }
    private void requireOccurrence(Event e, LocalDate date) {
        if (e.recurrence()==EventInput.Repeat.NONE || date==null || !occurs(e,date)) throw new ApiError.Failure(400,"请选择有效的重复日程日期");
    }
    private boolean occurs(Event e, LocalDate date) {
        long days = ChronoUnit.DAYS.between(e.start().toLocalDate(),date);
        return days>=0 && (e.repeatUntil()==null || !date.isAfter(e.repeatUntil())) &&
            (e.recurrence()==EventInput.Repeat.DAILY || (e.recurrence()==EventInput.Repeat.WEEKLY && days%7==0));
    }
    @Transactional
    public Event update(String id, Scope scope, LocalDate date, EventInput raw) {
        var in=raw.normalized(scope==Scope.OCCURRENCE);
        return audit.change("UPDATE",id,scope,date,()->{
        var e=get(id); bump(e,in.version());
        if(scope==Scope.OCCURRENCE && in.todoId()!=null && !Objects.equals(e.todoId(),in.todoId().isBlank()?null:in.todoId()))
            throw new ApiError.Failure(400,"待办关联属于整个系列；请单独修改系列关联");
        if (scope==Scope.OCCURRENCE) {
            requireOccurrence(e,date);
            db.update("DELETE FROM event_exceptions WHERE series_id=? AND occurrence_date=?",id,date);
            db.update("INSERT INTO event_exceptions(series_id,occurrence_date,cancelled,title,description,start_at,end_at,all_day,color) VALUES(?,?,false,?,?,?,?,?,?)",
                id,date,in.title(),in.description(),in.start(),in.end(),in.allDay(),in.color());
        } else {
            db.update("UPDATE schedule_events SET title=?,description=?,start_at=?,end_at=?,all_day=?,time_zone=?,recurrence=?,repeat_until=?,color=? WHERE id=?",
                in.title(),in.description(),in.start(),in.end(),in.allDay(),in.timeZone(),in.recurrence().name(),in.repeatUntil(),in.color(),id);
            // Whole-series edits deliberately reset prior single-occurrence changes and cancellations.
            db.update("DELETE FROM event_exceptions WHERE series_id=?",id);
        }
        associate(id,e.todoId(),in.todoId(),in.todoVersion());
        return get(id);
        });
    }
    @Transactional
    public void delete(String id, Scope scope, LocalDate date, long version) {
        audit.change("DELETE",id,scope,date,()->{
        var e=get(id); bump(e,version);
        touchTodo(e.todoId());
        if(scope==Scope.OCCURRENCE) {
            requireOccurrence(e,date);
            db.update("DELETE FROM event_exceptions WHERE series_id=? AND occurrence_date=?",id,date);
            db.update("INSERT INTO event_exceptions(series_id,occurrence_date,cancelled,title,description,start_at,end_at,all_day,color) VALUES(?,?,true,?,?,?,?,?,?)",
                id,date,e.title(),e.description(),e.start(),e.end(),e.allDay(),e.color());
        } else db.update("DELETE FROM schedule_events WHERE id=?",id);
        return null;
        });
    }
    static boolean overlaps(LocalDateTime start, LocalDateTime end, LocalDateTime from, LocalDateTime to) {
        return start.isBefore(to) && (end==null ? !start.isBefore(from) : end.isAfter(from));
    }
    @Transactional(readOnly=true)
    public List<Occurrence> list(LocalDateTime from, LocalDateTime to) {
        if (from.getYear()<1900 || to.getYear()>2200 || !to.isAfter(from) || Duration.between(from,to).compareTo(Duration.ofDays(366))>0)
            throw new ApiError.Failure(400,"查询范围须大于 0 且不超过 366 天");
        var series=db.query(SELECT+"WHERE (recurrence='NONE' AND start_at<? AND (end_at>? OR (end_at IS NULL AND start_at>=?))) OR (recurrence<>'NONE' AND start_at<? AND (repeat_until IS NULL OR repeat_until>=?)) OR e.id IN (SELECT series_id FROM event_exceptions WHERE start_at<? AND (end_at>? OR (end_at IS NULL AND start_at>=?))) ORDER BY start_at LIMIT 5001", MAPPER,to,from,from,to,from.toLocalDate().minusDays(367),to,from,from);
        if(series.size()>5000) throw new ApiError.Failure(422,"候选日程过多，请归档旧日程或缩小查询范围");
        List<Occurrence> result=new ArrayList<>();
        for(var e:series) {
            if(e.recurrence()==EventInput.Repeat.NONE) {
                add(result,e,e.start().toLocalDate(),e.title(),e.description(),e.start(),e.end(),e.allDay(),e.color(),false,from,to); continue;
            }
            var exceptions=new HashMap<LocalDate,ExceptionRow>();
            db.query("SELECT * FROM event_exceptions WHERE series_id=?",(rs,n)->new ExceptionRow(rs.getObject("occurrence_date",LocalDate.class),rs.getBoolean("cancelled"),rs.getString("title"),rs.getString("description"),time(rs,"start_at"),time(rs,"end_at"),rs.getBoolean("all_day"),rs.getString("color")),e.id())
                .forEach(x->exceptions.put(x.date(),x));
            long lookback=e.end()==null ? 0 : Duration.between(e.start(),e.end()).toDays()+1;
            LocalDate day=from.toLocalDate().minusDays(lookback);
            if(day.isBefore(e.start().toLocalDate())) day=e.start().toLocalDate();
            long step=e.recurrence()==EventInput.Repeat.WEEKLY ? 7 : 1;
            if(step==7) day=day.plusDays((7-ChronoUnit.DAYS.between(e.start().toLocalDate(),day)%7)%7);
            for(;!day.isAfter(to.toLocalDate()) && (e.repeatUntil()==null || !day.isAfter(e.repeatUntil()));day=day.plusDays(step)) {
                if(exceptions.containsKey(day)) continue;
                var start=day.atTime(e.start().toLocalTime());
                var end=e.end()==null ? null : start.plus(Duration.between(e.start(),e.end()));
                add(result,e,day,e.title(),e.description(),start,end,e.allDay(),e.color(),false,from,to);
            }
            for(var x:exceptions.values()) if(!x.cancelled())
                add(result,e,x.date(),x.title(),x.description(),x.start(),x.end(),x.allDay(),x.color(),true,from,to);
        }
        result.sort(Comparator.comparing(Occurrence::start).thenComparing(Occurrence::id));
        return result;
    }
    private void add(List<Occurrence> out, Event e, LocalDate date, String title, String description, LocalDateTime start,
                     LocalDateTime end, boolean allDay, String color, boolean exception, LocalDateTime from, LocalDateTime to) {
        if(!overlaps(start,end,from,to)) return;
        if(out.size()>=10000) throw new ApiError.Failure(422,"日程超过 10000 条，请缩小查询范围");
        out.add(occurrence(e,date,title,description,start,end,allDay,color,exception));
    }
    private Occurrence occurrence(Event e,LocalDate date,String title,String description,LocalDateTime start,LocalDateTime end,boolean allDay,String color,boolean exception) {
        return new Occurrence(e.id()+"@"+date,e.id(),date,title,description,start,end,allDay,e.timeZone(),e.recurrence(),e.repeatUntil(),color,e.version(),exception,e.todoId(),e.todoStatus());
    }
    private boolean upcoming(LocalDateTime start,LocalDateTime end,LocalDateTime at) { return end==null?!start.isBefore(at):end.isAfter(at); }
    Occurrence next(Event e,LocalDateTime at) {
        if(e.recurrence()==EventInput.Repeat.NONE) return upcoming(e.start(),e.end(),at)?occurrence(e,e.start().toLocalDate(),e.title(),e.description(),e.start(),e.end(),e.allDay(),e.color(),false):null;
        var exceptions=new HashMap<LocalDate,ExceptionRow>();
        db.query("SELECT * FROM event_exceptions WHERE series_id=?",(rs,n)->new ExceptionRow(rs.getObject("occurrence_date",LocalDate.class),rs.getBoolean("cancelled"),rs.getString("title"),rs.getString("description"),time(rs,"start_at"),time(rs,"end_at"),rs.getBoolean("all_day"),rs.getString("color")),e.id()).forEach(x->exceptions.put(x.date(),x));
        Occurrence best=null;
        for(var x:exceptions.values()) if(!x.cancelled() && upcoming(x.start(),x.end(),at) && (best==null||x.start().isBefore(best.start())))
            best=occurrence(e,x.date(),x.title(),x.description(),x.start(),x.end(),x.allDay(),x.color(),true);
        long duration=e.end()==null?0:Duration.between(e.start(),e.end()).toDays()+1;
        LocalDate day=at.toLocalDate().minusDays(duration);
        if(day.isBefore(e.start().toLocalDate())) day=e.start().toLocalDate();
        long step=e.recurrence()==EventInput.Repeat.WEEKLY?7:1;
        if(step==7) day=day.plusDays((7-ChronoUnit.DAYS.between(e.start().toLocalDate(),day)%7)%7);
        LocalDate limit=e.repeatUntil()==null?LocalDate.of(2199,12,31):e.repeatUntil();
        for(;!day.isAfter(limit);day=day.plusDays(step)) {
            var start=day.atTime(e.start().toLocalTime());var end=e.end()==null?null:start.plus(Duration.between(e.start(),e.end()));
            if(exceptions.containsKey(day)||!upcoming(start,end,at)) continue;
            if(best==null||start.isBefore(best.start())) best=occurrence(e,day,e.title(),e.description(),start,end,e.allDay(),e.color(),false);
            break;
        }
        return best;
    }
}
