package cn.scheduletool;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.http.MediaType;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ScheduleTest {
    @Autowired EventService service;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("app.password-hash",()->new BCryptPasswordEncoder(4).encode("TestPassword123!"));
    }
    @BeforeEach void clean() { db.update("DELETE FROM audit_log");db.update("DELETE FROM event_exceptions");db.update("DELETE FROM schedule_events");db.update("DELETE FROM auth_tokens"); }
    EventInput input(String start,String end,boolean allDay,EventInput.Repeat repeat,LocalDate until,long version) {
        return new EventInput("测试日程","备注",LocalDateTime.parse(start),end==null?null:LocalDateTime.parse(end),allDay,"Asia/Shanghai",repeat,until,"#256f62",version);
    }
    EventInput point(String date,EventInput.Repeat repeat,long version) { return input(date+"T09:00:00",null,false,repeat,null,version); }
    List<EventService.Occurrence> list(String from,String to) { return service.list(LocalDate.parse(from).atStartOfDay(),LocalDate.parse(to).atStartOfDay()); }
    @Test void pointRangeBoundariesAndCrud() {
        var e=service.create(point("2026-10-17",EventInput.Repeat.NONE,0));
        assertEquals(1,list("2026-10-17","2026-10-18").size());
        assertEquals(0,service.list(LocalDateTime.parse("2026-10-17T09:00:01"),LocalDateTime.parse("2026-10-18T00:00:00")).size());
        service.update(e.id(),EventService.Scope.SERIES,null,point("2026-10-18",EventInput.Repeat.NONE,0));
        assertEquals(0,list("2026-10-17","2026-10-18").size());
        assertEquals(1,service.get(e.id()).version());
        service.delete(e.id(),EventService.Scope.SERIES,null,1);assertEquals(0,list("2026-10-18","2026-10-19").size());
    }
    @Test void crossDayAndAllDayExclusiveEnd() {
        service.create(input("2026-10-17T23:00:00","2026-10-18T02:00:00",false,EventInput.Repeat.NONE,null,0));
        service.create(input("2026-10-17T00:00:00","2026-10-19T00:00:00",true,EventInput.Repeat.NONE,null,0));
        assertEquals(2,list("2026-10-18","2026-10-19").size());
        assertEquals(0,list("2026-10-19","2026-10-20").size());
    }
    @Test void dailyUntilInclusiveAndSingleDelete() {
        var e=service.create(input("2026-10-17T09:00:00",null,false,EventInput.Repeat.DAILY,LocalDate.parse("2026-10-20"),0));
        assertEquals(4,list("2026-10-17","2026-10-22").size());
        service.delete(e.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2026-10-18"),0);
        var all=list("2026-10-17","2026-10-22");assertEquals(3,all.size());assertTrue(all.stream().noneMatch(x->x.occurrenceDate().equals(LocalDate.parse("2026-10-18"))));
    }
    @Test void weeklyKeepsOriginalWeekday() {
        service.create(point("2026-10-17",EventInput.Repeat.WEEKLY,0));
        var all=list("2026-10-18","2026-11-02");assertEquals(2,all.size());
        assertTrue(all.stream().allMatch(x->x.start().getDayOfWeek()==DayOfWeek.SATURDAY));
    }
    @Test void recurringCrossMidnightOverlapsNextDay() {
        service.create(input("2026-10-17T23:00:00","2026-10-18T02:00:00",false,EventInput.Repeat.DAILY,LocalDate.parse("2026-10-18"),0));
        var next=service.list(LocalDateTime.parse("2026-10-18T00:00:00"),LocalDateTime.parse("2026-10-18T01:00:00"));
        assertEquals(1,next.size());assertEquals(LocalDate.parse("2026-10-17"),next.getFirst().occurrenceDate());
    }
    @Test void movedExceptionOutsideOriginalRangeIsVisibleExactlyOnce() {
        var e=service.create(input("2026-11-17T09:00:00",null,false,EventInput.Repeat.DAILY,LocalDate.parse("2026-11-18"),0));
        service.update(e.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2026-11-18"),point("2026-10-01",EventInput.Repeat.DAILY,0));
        var october=list("2026-10-01","2026-10-02");assertEquals(1,october.size());assertTrue(october.getFirst().exception());
        assertEquals(LocalDate.parse("2026-11-18"),october.getFirst().occurrenceDate());
        assertEquals(1,list("2026-11-17","2026-11-19").size());
    }
    @Test void singleOccurrenceCanMovePastSeriesUntil() {
        var until=LocalDate.parse("2026-10-20");
        var e=service.create(input("2026-10-17T09:00:00",null,false,EventInput.Repeat.DAILY,until,0));
        service.update(e.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2026-10-18"),
            input("2026-11-01T11:00:00",null,false,EventInput.Repeat.DAILY,until,0));
        assertEquals(1,list("2026-11-01","2026-11-02").size());
        assertEquals(until,service.get(e.id()).repeatUntil());
    }
    @Test void seriesEditResetsExceptionsAndCanStopRecurrence() {
        var e=service.create(point("2026-10-17",EventInput.Repeat.DAILY,0));
        service.delete(e.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2026-10-18"),0);
        service.update(e.id(),EventService.Scope.SERIES,null,point("2026-10-17",EventInput.Repeat.NONE,1));
        assertEquals(1,list("2026-10-17","2026-10-22").size());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM event_exceptions",Integer.class));
    }
    @Test void invalidOccurrenceRollsBackVersionAndStaleWritesFail() {
        var e=service.create(point("2026-10-17",EventInput.Repeat.WEEKLY,0));
        assertThrows(ApiError.Failure.class,()->service.delete(e.id(),EventService.Scope.OCCURRENCE,LocalDate.parse("2026-10-18"),0));
        assertEquals(0,service.get(e.id()).version());
        service.update(e.id(),EventService.Scope.SERIES,null,point("2026-10-17",EventInput.Repeat.WEEKLY,0));
        assertEquals(409,assertThrows(ApiError.Failure.class,()->service.delete(e.id(),EventService.Scope.SERIES,null,0)).status);
    }
    @Test void validationAndRangeLimit() {
        assertThrows(ApiError.Failure.class,()->service.create(input("2026-10-17T10:00:00","2026-10-17T09:00:00",false,EventInput.Repeat.NONE,null,0)));
        assertThrows(ApiError.Failure.class,()->service.create(input("2026-10-17T09:00:00",null,true,EventInput.Repeat.NONE,null,0)));
        assertThrows(ApiError.Failure.class,()->list("2026-01-01","2028-01-01"));
    }
    JsonNode signIn() throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"TestPassword123!\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test void authenticationCsrfRevocationAndOpenApi() throws Exception {
        mvc.perform(get("/api/v1/events").param("from","2026-10-01T00:00:00").param("to","2026-11-01T00:00:00")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/openapi")).andExpect(status().isUnauthorized());
        var s=signIn();String token=s.get("accessToken").asText(),csrf=s.get("csrfToken").asText();
        Cookie cookie=new Cookie(Auth.COOKIE,token);
        String body=json.writeValueAsString(point("2026-10-17",EventInput.Repeat.NONE,0));
        mvc.perform(post("/api/v1/events").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        var created=mvc.perform(post("/api/v1/events").cookie(cookie).header("X-CSRF-TOKEN",csrf).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        String id=json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/v1/events/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/openapi").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/events']").exists());
        mvc.perform(post("/api/v1/auth/logout").cookie(cookie).header("X-CSRF-TOKEN",csrf)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void expiredSessionAndInvalidPayload() throws Exception {
        var s=signIn();String token=s.get("accessToken").asText();
        mvc.perform(post("/api/v1/events").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}")).andExpect(status().isBadRequest());
        db.update("UPDATE auth_tokens SET expires_at=?",LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void wrongPasswordIsRejectedAndRateLimited() throws Exception {
        for(int i=0;i<6;i++) mvc.perform(post("/api/v1/auth/login").with(req->{req.setRemoteAddr("192.0.2.5");return req;})
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
            .andExpect(status().is(i<5?401:429));
    }
}
