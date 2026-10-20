package cn.scheduletool;

import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class TodoService {
    public enum Status { PENDING, IN_PROGRESS, COMPLETED, SHELVED }
    public enum DeleteMode { KEEP_EVENTS, DELETE_EVENTS }
    public record Todo(String id,String title,String description,String expectedEffect,String implementationIdea,
                       Status status,boolean pinned,long version,LocalDateTime createdAt,LocalDateTime updatedAt) {}
    public record Arrangement(EventService.Event event,EventService.Occurrence next) {}
    public record Detail(Todo todo,List<Arrangement> schedules,EventService.Occurrence next) {}
    private final JdbcTemplate db;
    private final AuditService audit;
    private final EventService events;
    public TodoService(JdbcTemplate db,AuditService audit,EventService events) { this.db=db;this.audit=audit;this.events=events; }
    private static final RowMapper<Todo> MAPPER=(rs,n)->new Todo(rs.getString("id"),rs.getString("title"),rs.getString("description"),
        rs.getString("expected_effect"),rs.getString("implementation_idea"),Status.valueOf(rs.getString("status")),rs.getBoolean("pinned"),
        rs.getLong("version"),rs.getObject("created_at",LocalDateTime.class),rs.getObject("updated_at",LocalDateTime.class));
    public Todo get(String id) {
        return db.query("SELECT * FROM todos WHERE id=?",MAPPER,id).stream().findFirst().orElseThrow(()->new ApiError.Failure(404,"待办不存在"));
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Detail detail(String id) { return detailAt(get(id),LocalDateTime.now(ZoneId.of("Asia/Shanghai"))); }
    Detail detailAt(Todo todo,LocalDateTime at) {
        var schedules=db.query("SELECT series_id FROM todo_events WHERE todo_id=? ORDER BY series_id",(rs,n)->rs.getString(1),todo.id()).stream()
            .map(events::get).map(e->new Arrangement(e,events.next(e,at))).toList();
        var next=schedules.stream().map(Arrangement::next).filter(Objects::nonNull).min(Comparator.comparing(EventService.Occurrence::start)).orElse(null);
        return new Detail(todo,schedules,next);
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Detail> list(String q,Status status,boolean includeCompleted,boolean unscheduled) {
        if(q!=null && q.length()>200) throw new ApiError.Failure(400,"搜索不能超过 200 字");
        String search=q==null?"":q.strip().toLowerCase(Locale.ROOT);
        var rows=db.query("SELECT * FROM todos ORDER BY pinned DESC, updated_at DESC, id",MAPPER);
        if(rows.size()>5000) throw new ApiError.Failure(422,"待办过多，请清理或归档");
        var at=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        return rows.stream().filter(t->status!=null?t.status()==status:includeCompleted||t.status()!=Status.COMPLETED)
            .filter(t->(t.title()+"\n"+t.description()+"\n"+t.expectedEffect()+"\n"+t.implementationIdea()).toLowerCase(Locale.ROOT).contains(search))
            .map(t->detailAt(t,at)).filter(d->!unscheduled||d.next()==null).toList();
    }
    @Transactional public Todo create(TodoInput raw) {
        var in=raw.normalized(false);String id=UUID.randomUUID().toString();
        return audit.todoChange("TODO_CREATE",id,()->{
            var now=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
            db.update("INSERT INTO todos(id,title,description,expected_effect,implementation_idea,status,pinned,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,0,?,?)",
                id,in.title(),in.description(),in.expectedEffect(),in.implementationIdea(),in.status().name(),in.pinned(),now,now);
            return get(id);
        });
    }
    @Transactional public Todo update(String id,TodoInput raw) {
        var in=raw.normalized(true);
        return audit.todoChange("TODO_UPDATE",id,()->{
            var todo=get(id);check(todo,in.version());
            db.update("UPDATE todos SET title=?,description=?,expected_effect=?,implementation_idea=?,status=?,pinned=?,version=version+1,updated_at=? WHERE id=?",
                in.title(),in.description(),in.expectedEffect(),in.implementationIdea(),in.status().name(),in.pinned(),LocalDateTime.now(ZoneId.of("Asia/Shanghai")),id);
            return get(id);
        });
    }
    static void check(Todo todo,long version) { if(todo.version()!=version) throw new ApiError.Failure(409,"待办或关联安排已被修改，请刷新后核对"); }
    @Transactional public void delete(String id,long version,DeleteMode mode) {
        if(mode==null) throw new ApiError.Failure(400,"请选择保留日程或删除关联日程");
        audit.todoChange(mode==DeleteMode.KEEP_EVENTS?"TODO_DELETE_KEEP_EVENTS":"TODO_DELETE_WITH_EVENTS",id,()->{
            check(get(id),version);
            var ids=db.query("SELECT series_id FROM todo_events WHERE todo_id=?",(rs,n)->rs.getString(1),id);
            // Both choices are one audited transaction. Bump surviving series so stale editors cannot relink silently.
            for(String series:ids) {
                if(mode==DeleteMode.DELETE_EVENTS) db.update("DELETE FROM schedule_events WHERE id=?",series);
                else db.update("UPDATE schedule_events SET version=version+1,updated_at=? WHERE id=?",LocalDateTime.now(ZoneId.of("Asia/Shanghai")),series);
            }
            db.update("DELETE FROM todo_events WHERE todo_id=?",id);db.update("DELETE FROM todos WHERE id=?",id);return null;
        });
    }
}
