package cn.scheduletool;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class WebAutoLoginTest {
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("app.web-auto-login",()->true);
        p.add("app.password-hash",()->new BCryptPasswordEncoder(4).encode("TestPassword123!"));
        p.add("spring.datasource.url",()->"jdbc:h2:mem:web-auto-login;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
    }
    @BeforeEach @AfterEach void clean() {
        for(String table:List.of("todo_events","event_exceptions","schedule_events","todos","audit_log","auth_tokens"))
            db.update("DELETE FROM "+table);
    }
    int tokenCount() { return db.queryForObject("SELECT COUNT(*) FROM auth_tokens",Integer.class); }
    MvcResult bootstrap() throws Exception {
        return mvc.perform(get("/api/v1/auth/me")).andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(jsonPath("$.autoLogin").value(true))
            .andExpect(jsonPath("$.csrfToken").isNotEmpty())
            .andExpect(jsonPath("$.accessToken").doesNotExist()).andReturn();
    }
    Cookie cookie(MvcResult result) {
        var cookie=result.getResponse().getCookie(Auth.COOKIE);
        assertNotNull(cookie); return cookie;
    }
    String csrf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("csrfToken").asText();
    }
    String event() {
        return "{\"title\":\"内网日程\",\"start\":\"2026-11-14T09:00:00\",\"allDay\":false,\"timeZone\":\"Asia/Shanghai\",\"recurrence\":\"NONE\",\"color\":\"#256f62\",\"version\":0}";
    }
    @Test void bootstrapsPersistentCookieAndReusesSession() throws Exception {
        var first=bootstrap(); var cookie=cookie(first);
        assertTrue(cookie.isHttpOnly()); assertEquals(86400,cookie.getMaxAge());
        assertTrue(first.getResponse().getHeader("Set-Cookie").contains("SameSite=Strict"));
        var expires=Instant.parse(json.readTree(first.getResponse().getContentAsString()).path("expiresAt").asText());
        assertTrue(expires.isAfter(Instant.now().plus(Duration.ofHours(23))));
        assertTrue(expires.isBefore(Instant.now().plus(Duration.ofHours(25))));
        assertEquals(1,tokenCount());
        mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isOk())
            .andExpect(jsonPath("$.autoLogin").value(true)).andExpect(jsonPath("$.csrfToken").value(csrf(first)))
            .andExpect(jsonPath("$.accessToken").doesNotExist());
        assertEquals(1,tokenCount());
    }
    @Test void expiredOrUnknownCookieCanBootstrapAgain() throws Exception {
        var first=bootstrap(); var old=cookie(first);
        db.update("UPDATE auth_tokens SET expires_at=?",LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        var renewed=mvc.perform(get("/api/v1/auth/me").cookie(old)).andExpect(status().isOk())
            .andExpect(jsonPath("$.autoLogin").value(true)).andExpect(jsonPath("$.accessToken").doesNotExist()).andReturn();
        assertNotEquals(old.getValue(),cookie(renewed).getValue());
        assertNotEquals(csrf(first),csrf(renewed)); assertEquals(1,tokenCount());
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie(Auth.COOKIE,"unknown-session")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.autoLogin").value(true));
    }
    @Test void anonymousApiRequestsDoNotBootstrap() throws Exception {
        mvc.perform(get("/api/v1/events").param("from","2026-11-14T00:00:00").param("to","2026-11-15T00:00:00"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(event())).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/events/unknown").contentType(MediaType.APPLICATION_JSON).content(event())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/events/unknown").param("version","0")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/todos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/openapi")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
        assertEquals(0,tokenCount());
    }
    @Test void autoLoginWritesRequireCsrfAndAuditAsWeb() throws Exception {
        var session=bootstrap(); var cookie=cookie(session);
        mvc.perform(post("/api/v1/events").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(event()))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/events").cookie(cookie).header("X-CSRF-TOKEN","wrong").contentType(MediaType.APPLICATION_JSON).content(event()))
            .andExpect(status().isForbidden());
        var created=mvc.perform(post("/api/v1/events").cookie(cookie).header("X-CSRF-TOKEN",csrf(session))
            .contentType(MediaType.APPLICATION_JSON).content(event())).andExpect(status().isCreated()).andReturn();
        mvc.perform(get("/api/v1/audit-logs").cookie(cookie).param("requestId",created.getResponse().getHeader("X-Request-ID")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].data.actor").value("admin"))
            .andExpect(jsonPath("$[0].data.authentication").value("cookie"))
            .andExpect(jsonPath("$[0].data.declaredSource").value("WEB"));
    }
    @Test void explicitInvalidAuthorizationDoesNotBootstrap() throws Exception {
        for(String authorization:List.of("Bearer invalid-token","Basic invalid-credentials",""))
            mvc.perform(get("/api/v1/auth/me").header("Authorization",authorization)).andExpect(status().isUnauthorized());
        assertEquals(0,tokenCount());
    }
    @Test void logoutStillRevokesSessionAndNextVisitCanBootstrap() throws Exception {
        var session=bootstrap(); var cookie=cookie(session);
        mvc.perform(post("/api/v1/auth/logout").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").cookie(cookie).header("X-CSRF-TOKEN",csrf(session))).andExpect(status().isNoContent());
        assertEquals(0,tokenCount());
        mvc.perform(get("/api/v1/todos").cookie(cookie)).andExpect(status().isUnauthorized());
        assertNotEquals(cookie.getValue(),cookie(bootstrap()).getValue());
    }
    @Test void credentialLoginStillChecksPasswordAndSupportsBearer() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}")).andExpect(status().isUnauthorized());
        assertEquals(0,tokenCount());
        var login=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"TestPassword123!\"}")).andExpect(status().isOk()).andReturn();
        String token=json.readTree(login.getResponse().getContentAsString()).path("accessToken").asText();
        assertFalse(token.isEmpty());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        assertEquals(0,tokenCount());
    }
}
