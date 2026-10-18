package cn.scheduletool;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class AuditTest {
    @Autowired EventService events;
    @Autowired AuditService audit;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("app.password-hash",()->new BCryptPasswordEncoder(4).encode("TestPassword123!"));
    }
    @BeforeEach @AfterEach void clean() {
        db.update("DELETE FROM audit_log");db.update("DELETE FROM event_exceptions");
        db.update("DELETE FROM schedule_events");db.update("DELETE FROM auth_tokens");
    }
    EventInput input(long version) {
        return new EventInput("审计测试","完整快照",LocalDateTime.parse("2026-10-17T09:00:00"),null,false,
            "Asia/Shanghai",EventInput.Repeat.DAILY,null,"#256f62",version);
    }
    @Test void capturesFullExceptionsAndSurvivesSeriesDeletion() {
        var e=events.create(input(0));
        events.update(e.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2026-10-18"),input(0));
        events.delete(e.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2026-10-19"),1);
        events.delete(e.id(),EventService.Scope.SERIES,null,2);
        var logs=audit.list(0,50,e.id(),null);assertEquals(4,logs.size());
        assertTrue(logs.getFirst().data().path("before").isMissingNode());
        var deleted=logs.getLast().data();
        assertEquals("DELETE",deleted.path("action").asText());
        assertEquals(2,deleted.path("before").path("exceptions").size());
        assertFalse(deleted.path("before").path("exceptions").get(0).path("cancelled").asBoolean());
        assertFalse(deleted.has("after"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM schedule_events",Integer.class));
        assertTrue(audit.verify(null,null).valid());
    }
    @Test void invalidOrStaleWritesLeaveNoSuccessAudit() {
        var e=events.create(input(0));
        assertThrows(ApiError.Failure.class,()->events.delete(e.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2026-10-16"),0));
        assertEquals(0,events.get(e.id()).version());
        events.update(e.id(),EventService.Scope.SERIES,null,input(0));
        assertThrows(ApiError.Failure.class,()->events.delete(e.id(),EventService.Scope.SERIES,null,0));
        assertEquals(2,audit.verify(null,null).checked());
    }
    @Test void auditStorageFailureRollsBackBusinessAndVersion() {
        var e=events.create(input(0));
        db.execute("ALTER TABLE audit_log ADD CONSTRAINT test_audit_failure CHECK(sequence_id<2)");
        try {
            assertThrows(RuntimeException.class,()->events.update(e.id(),EventService.Scope.SERIES,null,input(0)));
            assertThrows(RuntimeException.class,()->events.delete(e.id(),EventService.Scope.SERIES,null,0));
            assertThrows(RuntimeException.class,()->events.create(input(0)));
            assertEquals(0,events.get(e.id()).version());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM schedule_events",Integer.class));
            assertEquals(1,audit.verify(null,null).checked());
        } finally { db.execute("ALTER TABLE audit_log DROP CONSTRAINT test_audit_failure"); }
    }
    @Test void detectsContentIndexTamperingGapsAndTruncationAgainstCheckpoint() {
        var e=events.create(input(0));events.update(e.id(),EventService.Scope.SERIES,null,input(0));
        var checkpoint=audit.verify(null,null);
        db.update("UPDATE audit_log SET series_id=? WHERE sequence_id=1",UUID.randomUUID().toString());
        assertFalse(audit.verify(null,null).valid());
        db.update("UPDATE audit_log SET series_id=? WHERE sequence_id=1",e.id());
        db.update("UPDATE audit_log SET payload=REPLACE(payload,?,?) WHERE sequence_id=1","完整快照","被篡改");
        assertFalse(audit.verify(null,null).valid());
        db.update("UPDATE audit_log SET payload=REPLACE(payload,?,?) WHERE sequence_id=1","被篡改","完整快照");
        db.update("DELETE FROM audit_log WHERE sequence_id=2");
        assertTrue(audit.verify(null,null).valid()); // A chain alone cannot detect tail truncation.
        assertFalse(audit.verify(checkpoint.lastSequence(),checkpoint.lastHash()).valid());
        events.update(e.id(),EventService.Scope.SERIES,null,input(1));
        db.update("DELETE FROM audit_log WHERE sequence_id=1");
        assertFalse(audit.verify(null,null).valid());
    }
    @Test void concurrentTransactionsProduceOneContiguousChain() throws Exception {
        try(var pool=Executors.newFixedThreadPool(4)) {
            var jobs=new ArrayList<Future<?>>();
            for(int i=0;i<12;i++) jobs.add(pool.submit(()->events.create(input(0))));
            for(var job:jobs) job.get(15,TimeUnit.SECONDS);
        }
        assertTrue(audit.verify(null,null).valid());assertEquals(12,audit.verify(null,null).checked());
    }
    @Test void rejectsZeroSequenceAndUnexpectedCheckpointParameters() {
        events.create(input(0));
        db.update("UPDATE audit_log SET sequence_id=0 WHERE sequence_id=1");
        assertFalse(audit.verify(null,null).valid());
        assertThrows(ApiError.Failure.class,()->audit.verify(1L,null));
        assertThrows(ApiError.Failure.class,()->audit.verify(1L,"invalid"));
    }
    @Test void apiAuditIsAuthenticatedAndCorrelatedWithoutSecrets() throws Exception {
        mvc.perform(get("/api/v1/audit-logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/audit-logs/verify")).andExpect(status().isUnauthorized());
        var login=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"TestPassword123!\"}")).andReturn();
        var session=json.readTree(login.getResponse().getContentAsString());
        String token=session.path("accessToken").asText();
        var created=mvc.perform(post("/api/v1/events").header("Authorization","Bearer "+token)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input(0))))
            .andExpect(status().isCreated()).andExpect(header().string("X-Audit-Sequence","1")).andReturn();
        String requestId=created.getResponse().getHeader("X-Request-ID");
        var response=mvc.perform(get("/api/v1/audit-logs").header("Authorization","Bearer "+token).param("requestId",requestId))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].data.actor").value("admin"))
            .andExpect(jsonPath("$[0].data.authentication").value("bearer")).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(token));assertFalse(response.contains("TestPassword123!"));
        mvc.perform(get("/api/v1/audit-logs").header("Authorization","Bearer "+token).param("limit","201")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/events").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/audit-logs/verify").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
    }
}
