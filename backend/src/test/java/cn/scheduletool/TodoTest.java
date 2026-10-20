package cn.scheduletool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class TodoTest {
    @Autowired TodoService todos;
    @Autowired EventService events;
    @Autowired AuditService audit;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("app.password-hash",()->new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("TestPassword123!"));
    }
    @BeforeEach @AfterEach void clean() { for(String table:List.of("todo_events","event_exceptions","schedule_events","todos","audit_log","auth_tokens")) db.update("DELETE FROM "+table); }
    TodoInput input(String title,TodoService.Status status,long version) { return new TodoInput(title,"背景","预期","方案",status,false,version); }
    TodoService.Todo create() { return todos.create(input("实现想法",TodoService.Status.PENDING,0)); }
    EventInput event(String start,String end,EventInput.Repeat repeat,LocalDate until,long version,TodoService.Todo todo) {
        return new EventInput("某次执行","执行备注",LocalDateTime.parse(start),end==null?null:LocalDateTime.parse(end),false,"Asia/Shanghai",repeat,until,"#256f62",version,todo==null?null:todo.id(),todo==null?null:todo.version());
    }
    EventService.Event schedule(TodoService.Todo todo,EventInput.Repeat repeat) { return events.create(event("2098-10-20T09:00:00",null,repeat,null,0,todo)); }
    @Test void multipleSchedulesCompleteReopenAndIndependentTitles() {
        var t=create();var a=schedule(t,EventInput.Repeat.DAILY);var b=schedule(todos.get(t.id()),EventInput.Repeat.NONE);
        assertEquals(2,todos.detail(t.id()).schedules().size());
        t=todos.update(t.id(),input("整体目标改名",TodoService.Status.COMPLETED,todos.get(t.id()).version()));
        assertEquals("某次执行",events.get(a.id()).title());assertEquals("COMPLETED",events.get(a.id()).todoStatus());
        assertEquals(0,todos.list(null,null,false,false).size());assertEquals(1,todos.list("预期",null,true,false).size());
        assertEquals(2,todos.detail(t.id()).schedules().size());
        todos.update(t.id(),input("整体目标改名",TodoService.Status.IN_PROGRESS,t.version()));
        assertEquals("IN_PROGRESS",events.list(LocalDateTime.parse("2098-10-20T00:00:00"),LocalDateTime.parse("2098-10-21T00:00:00")).getFirst().todoStatus());
        events.delete(b.id(),EventService.Scope.SERIES,null,b.version());assertNotNull(todos.get(t.id()));
    }
    @Test void nextSkipsCancelledAndHonorsMovedExceptionsAndSeriesReset() {
        var t=create();var e=schedule(t,EventInput.Repeat.DAILY);var at=LocalDateTime.parse("2098-10-20T08:00:00");
        events.delete(e.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2098-10-20"),0);
        assertEquals("2098-10-21T09:00",todos.detailAt(todos.get(t.id()),at).next().start().toString());
        events.update(e.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2098-10-21"),event("2098-10-25T11:00:00",null,EventInput.Repeat.NONE,null,1,null));
        assertEquals("2098-10-22T09:00",todos.detailAt(todos.get(t.id()),at).next().start().toString());
        events.update(e.id(),EventService.Scope.SERIES,null,event("2098-10-20T10:00:00",null,EventInput.Repeat.NONE,null,2,null));
        assertEquals("2098-10-20T10:00",todos.detailAt(todos.get(t.id()),at).next().start().toString());
        assertNull(todos.detailAt(todos.get(t.id()),at.plusDays(1)).next());
        assertEquals(TodoService.Status.PENDING,todos.get(t.id()).status());
    }
    @Test void movedFromFarFutureAndExpiredRuleStillHasNextException() {
        var t=create();var e=events.create(event("2099-01-01T09:00:00",null,EventInput.Repeat.WEEKLY,LocalDate.parse("2099-01-08"),0,t));
        events.update(e.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2099-01-08"),event("2098-01-01T09:00:00",null,EventInput.Repeat.NONE,null,0,null));
        assertTrue(todos.detailAt(todos.get(t.id()),LocalDateTime.parse("2097-12-31T00:00:00")).next().exception());
        events.update(e.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2099-01-08"),event("2099-05-01T09:00:00",null,EventInput.Repeat.NONE,null,1,null));
        assertEquals("2099-05-01T09:00",todos.detailAt(todos.get(t.id()),LocalDateTime.parse("2099-04-01T00:00:00")).next().start().toString());
    }
    @Test void allDayCrossDayAndPointNextBoundaries() {
        var t=create();var e=events.create(new EventInput("跨天全天","",LocalDateTime.parse("2098-10-20T00:00:00"),LocalDateTime.parse("2098-10-22T00:00:00"),true,"Asia/Shanghai",EventInput.Repeat.WEEKLY,null,"#256f62",0L,t.id(),t.version()));
        assertEquals(e.id(),todos.detailAt(todos.get(t.id()),LocalDateTime.parse("2098-10-21T12:00:00")).next().seriesId());
        assertEquals("2098-10-27T00:00",todos.detailAt(todos.get(t.id()),LocalDateTime.parse("2098-10-22T00:00:00")).next().start().toString());
        events.delete(e.id(),EventService.Scope.SERIES,null,0);var point=schedule(todos.get(t.id()),EventInput.Repeat.NONE);
        assertNull(todos.detailAt(todos.get(t.id()),point.start().plusSeconds(1)).next());
    }
    @Test void linkChangesPreserveExceptionsAndOldClientsPreserveLink() {
        var t=create();var e=events.create(event("2098-10-20T09:00:00",null,EventInput.Repeat.DAILY,null,0,null));
        events.delete(e.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2098-10-21"),0);
        events.link(e.id(),new EventService.LinkInput(t.id(),t.version(),1L));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM event_exceptions",Integer.class));
        events.update(e.id(),EventService.Scope.SERIES,null,event("2098-10-20T10:00:00",null,EventInput.Repeat.DAILY,null,2,null));
        assertEquals(t.id(),events.get(e.id()).todoId());
        events.link(e.id(),new EventService.LinkInput("",null,3L));assertNull(events.get(e.id()).todoId());
    }
    @Test void deletionModesAndConcurrentAssociationGuard() {
        var t=create();var e=schedule(t,EventInput.Repeat.DAILY);String id=t.id();
        assertEquals(409,assertThrows(ApiError.Failure.class,()->todos.delete(id,0,TodoService.DeleteMode.DELETE_EVENTS)).status);
        todos.delete(id,todos.get(id).version(),TodoService.DeleteMode.KEEP_EVENTS);
        assertNull(events.get(e.id()).todoId());assertEquals(1,events.get(e.id()).version());
        var other=create();var series=schedule(other,EventInput.Repeat.DAILY);
        events.delete(series.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2098-10-21"),0);
        todos.delete(other.id(),todos.get(other.id()).version(),TodoService.DeleteMode.DELETE_EVENTS);
        assertThrows(ApiError.Failure.class,()->events.get(series.id()));assertNotNull(events.get(e.id()));assertTrue(audit.verify(null,null).valid());
    }
    @Test void failedAuditAndStaleAssociationRollBackAllBusinessWrites() {
        var t=create();var e=schedule(t,EventInput.Repeat.NONE);long before=audit.verify(null,null).lastSequence();
        assertEquals(409,assertThrows(ApiError.Failure.class,()->schedule(t,EventInput.Repeat.NONE)).status);
        assertEquals(1,todos.detail(t.id()).schedules().size());
        db.execute("ALTER TABLE audit_log ADD CONSTRAINT todo_audit_failure CHECK(sequence_id<="+before+")");
        try {
            assertThrows(RuntimeException.class,()->todos.delete(t.id(),todos.get(t.id()).version(),TodoService.DeleteMode.DELETE_EVENTS));
            assertThrows(RuntimeException.class,()->events.link(e.id(),new EventService.LinkInput("",null,0L)));
            assertEquals(t.id(),events.get(e.id()).todoId());assertEquals(1,todos.get(t.id()).version());
            assertThrows(RuntimeException.class,()->todos.update(t.id(),input("失败",TodoService.Status.COMPLETED,1)));
            assertEquals(TodoService.Status.PENDING,todos.get(t.id()).status());assertEquals(before,audit.verify(null,null).lastSequence());
        } finally { db.execute("ALTER TABLE audit_log DROP CONSTRAINT todo_audit_failure"); }
    }
    @Test void concurrentTodoEditorsCannotOverwrite() throws Exception {
        var t=create();
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> task=()->{try { todos.update(t.id(),input("编辑",TodoService.Status.IN_PROGRESS,0));return 200; } catch(ApiError.Failure e) { return e.status; }};
            var a=pool.submit(task);var b=pool.submit(task);assertEquals(Set.of(200,409),Set.of(a.get(),b.get()));
        }
        assertEquals(1,todos.get(t.id()).version());assertTrue(audit.verify(null,null).valid());
    }
    @Test void oldAuditPayloadAndExternalCheckpointRemainValid() {
        String id=UUID.randomUUID().toString(),requestId=UUID.randomUUID().toString();
        String payload="{\"schemaVersion\":2,\"sequence\":1,\"seriesId\":\""+id+"\",\"requestId\":\""+requestId+"\",\"action\":\"DELETE\"}";
        String hash=Auth.hash(AuditService.GENESIS+"\n"+payload);
        db.update("INSERT INTO audit_log VALUES(?,?,?,?,?,?)",1,id,requestId,payload,AuditService.GENESIS,hash);
        create();assertTrue(audit.verify(1L,hash).valid());
        assertEquals(payload,db.queryForObject("SELECT payload FROM audit_log WHERE sequence_id=1",String.class));
        assertEquals(3,audit.list(1,20,null,null).getFirst().data().path("schemaVersion").asInt());
    }
    @Test void restValidationSourceReceiptAndOpenApi() throws Exception {
        mvc.perform(get("/api/v1/todos")).andExpect(status().isUnauthorized());
        var response=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"TestPassword123!\"}")).andReturn().getResponse();
        String token=json.readTree(response.getContentAsString()).path("accessToken").asText();
        var created=mvc.perform(post("/api/v1/todos").header("Authorization","Bearer "+token).header("X-Schedule-Source","CODEX").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"一句话\"}"))
            .andExpect(status().isCreated()).andExpect(header().exists("X-Audit-Sequence")).andReturn().getResponse();
        String id=json.readTree(created.getContentAsString()).path("id").asText();
        assertEquals("CODEX",audit.list(0,20,id,null).getFirst().data().path("declaredSource").asText());
        mvc.perform(put("/api/v1/todos/"+id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"缺版本\"}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/todos/"+id).param("version","0").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/openapi").header("Authorization","Bearer "+token)).andExpect(jsonPath("$.paths['/api/v1/todos']").exists()).andExpect(jsonPath("$.paths['/api/v1/events/{id}/todo']").exists());
    }
}
