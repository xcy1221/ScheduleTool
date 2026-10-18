package cn.scheduletool;

import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
class McpTest {
    static final String PASSWORD="TestPassword123!",SECRET="mcp-client-test-secret-123456789",VERIFIER="a".repeat(64);
    static final int PORT;
    static final Path PRIVATE;
    static {
        try(var socket=new java.net.ServerSocket(0)) {
            PORT=socket.getLocalPort(); PRIVATE=Files.createTempDirectory("scheduletool-mcp-test-");
            Files.writeString(PRIVATE.resolve("key.jwk"),new RSAKeyGenerator(2048).keyID("test-key").generate().toJSONString());
            Files.writeString(PRIVATE.resolve("password.txt"),PASSWORD);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    static final String BASE="http://127.0.0.1:"+PORT+"/schedule",CALLBACK="http://127.0.0.1:"+PORT+"/callback";
    static final String RESOURCE="https://api.openai.com/v1/mcp/tunnel_22222222222222222222222222222222";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("server.port",()->PORT); p.add("app.password-hash",()->new BCryptPasswordEncoder(4).encode(PASSWORD));
        p.add("spring.datasource.url",()->"jdbc:h2:mem:mcp;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        p.add("app.mcp.enabled",()->true); p.add("app.mcp.issuer",()->BASE); p.add("app.mcp.resource",()->RESOURCE);
        p.add("app.mcp.client-secret-hash",()->new BCryptPasswordEncoder(4).encode(SECRET));
        p.add("app.mcp.redirect-uris",()->CALLBACK); p.add("app.mcp.signing-key-file",()->PRIVATE.resolve("key.jwk").toString());
        p.add("app.mcp.api-base",()->BASE); p.add("app.mcp.api-password-file",()->PRIVATE.resolve("password.txt").toString());
    }
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired RegisteredClientRepository clients;
    private HttpClient browser;
    @BeforeEach void clean() {
        browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();
        for(String table:List.of("oauth2_authorization_consent","oauth2_authorization","audit_log","event_exceptions","schedule_events","auth_tokens")) db.update("DELETE FROM "+table);
    }
    @AfterAll static void cleanup() throws Exception {
        for(String name:List.of("key.jwk","password.txt")) Files.deleteIfExists(PRIVATE.resolve(name));
        Files.deleteIfExists(PRIVATE);
    }
    HttpResponse<String> send(String method,String path,String body,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type","application/json").header("Accept",path.equals("/mcp")?"application/json, text/event-stream":"text/html, application/json");
        if(token!=null) request.header("Authorization","Bearer "+token);
        return browser.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> form(String path,Map<String,String> values,boolean clientAuth) throws Exception {
        String body=values.entrySet().stream().map(e->enc(e.getKey())+"="+enc(e.getValue())).reduce((a,b)->a+"&"+b).orElse("");
        var request=HttpRequest.newBuilder(URI.create(BASE+path)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
        if(clientAuth) request.header("Authorization","Basic "+Base64.getEncoder().encodeToString(("scheduletool-chatgpt:"+SECRET).getBytes(StandardCharsets.UTF_8)));
        return browser.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    static String enc(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    String challenge() throws Exception { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII))); }
    String authorize(String scope,String extra) throws Exception {
        return "/oauth2/authorize?response_type=code&client_id=scheduletool-chatgpt&redirect_uri="+enc(CALLBACK)+"&scope="+enc(scope)
            +"&state=client-state&code_challenge="+challenge()+"&code_challenge_method=S256&resource="+enc(RESOURCE)+extra;
    }
    String csrf(String html) {
        var match=Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(match.find(),"登录页需提供 CSRF token"); return match.group(1);
    }
    void login() throws Exception {
        var page=send("GET","/mcp-login",null,null); assertEquals(200,page.statusCode());
        var result=form("/mcp-login",Map.of("username","admin","password",PASSWORD,"_csrf",csrf(page.body())),false);
        assertEquals(302,result.statusCode());
    }
    String code(String scope) throws Exception {
        login();
        var response=send("GET",authorize(scope,""),null,null);
        if(response.statusCode()==302) {
            String location=response.headers().firstValue("Location").orElseThrow();
            if(location.startsWith(CALLBACK+"?")) {
                var values=query(location); assertEquals("client-state",values.get("state")); assertNotNull(values.get("code")); return values.get("code");
            }
            response=send("GET",URI.create(location).getRawPath().replaceFirst("^/schedule","")+"?"+URI.create(location).getRawQuery(),null,null);
        }
        assertEquals(200,response.statusCode(),response.body());
        var state=Pattern.compile("name=\"state\" value=\"([^\"]+)\"").matcher(response.body()); assertTrue(state.find(),response.body());
        // The standard consent endpoint accepts repeated scope form fields.
        String data="client_id=scheduletool-chatgpt&state="+enc(state.group(1));
        for(String item:scope.split(" ")) data+="&scope="+enc(item);
        var request=HttpRequest.newBuilder(URI.create(BASE+"/oauth2/authorize")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(data));
        response=browser.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(302,response.statusCode(),response.body());
        String location=response.headers().firstValue("Location").orElseThrow(); assertTrue(location.startsWith(CALLBACK+"?"),location);
        Map<String,String> values=query(location); assertEquals("client-state",values.get("state")); return values.get("code");
    }
    static Map<String,String> query(String url) {
        var values=new HashMap<String,String>();
        for(String part:URI.create(url).getRawQuery().split("&")) { var pair=part.split("=",2); values.put(pair[0],URLDecoder.decode(pair[1],StandardCharsets.UTF_8)); }
        return values;
    }
    HttpResponse<String> exchange(String code,String verifier,String resource) throws Exception {
        return form("/oauth2/token",Map.of("grant_type","authorization_code","code",code,"redirect_uri",CALLBACK,"code_verifier",verifier,"resource",resource),true);
    }
    JsonNode tokens(String scope) throws Exception {
        var response=exchange(code(scope),VERIFIER,RESOURCE); assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body());
    }
    JsonNode rpc(String token,String method,Object params) throws Exception {
        var response=send("POST","/mcp",json.writeValueAsString(Map.of("jsonrpc","2.0","id",1,"method",method,"params",params)),token);
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body());
    }
    JsonNode tool(String token,String name,Map<String,Object> args) throws Exception { return rpc(token,"tools/call",Map.of("name",name,"arguments",args)).path("result"); }
    Map<String,Object> event(String start,String recurrence) { return new LinkedHashMap<>(Map.of("title","MCP 测试","start",start,"recurrence",recurrence)); }
    @Test void discoveryRequiresBearerAndLoginRequiresCsrf() throws Exception {
        var response=send("GET","/.well-known/oauth-protected-resource/mcp",null,null);
        assertEquals(RESOURCE,json.readTree(response.body()).path("resource").asText());
        response=send("GET","/.well-known/oauth-authorization-server",null,null);
        assertEquals(200,response.statusCode()); assertTrue(json.readTree(response.body()).path("code_challenge_methods_supported").toString().contains("S256"));
        response=send("POST","/mcp","{}",null); assertEquals(401,response.statusCode());
        assertTrue(response.headers().firstValue("WWW-Authenticate").orElseThrow().contains("oauth-protected-resource/mcp"));
        assertEquals(403,form("/mcp-login",Map.of("username","admin","password",PASSWORD),false).statusCode());
        assertEquals(401,send("POST","/mcp","{}","not-a-token").statusCode());
    }
    @Test void oauthAndMcpCrudRecurringExceptionsAndSourceAudit() throws Exception {
        String token=tokens("schedule:read schedule:write").path("access_token").asText();
        var init=rpc(token,"initialize",Map.of("protocolVersion","2025-06-18","capabilities",Map.of(),"clientInfo",Map.of("name","test","version","1")));
        assertEquals("ScheduleTool",init.path("result").path("serverInfo").path("name").asText());
        assertEquals(8,rpc(token,"tools/list",Map.of()).path("result").path("tools").size());
        assertFalse(tool(token,"schedule_status",Map.of()).toString().contains("csrfToken"));
        var created=tool(token,"schedule_create",Map.of("event",event("2026-11-01T09:00:00","DAILY"),"source","CHATGPT"));
        assertFalse(created.path("isError").asBoolean(),created.toString());
        var payload=created.path("structuredContent"); String id=payload.path("result").path("id").asText();
        assertFalse(payload.path("auditSequence").asText().isEmpty());
        var update=event("2026-11-02T11:00:00","NONE"); update.put("version",0);
        var changed=tool(token,"schedule_update",Map.of("seriesId",id,"scope","OCCURRENCE","occurrenceDate","2026-11-02","event",update,"source","CHATGPT"));
        assertFalse(changed.path("isError").asBoolean(),changed.toString());
        var listed=tool(token,"schedule_list",Map.of("from","2026-11-01T00:00:00","to","2026-11-04T00:00:00")).path("structuredContent").path("result");
        assertEquals(3,listed.size()); assertTrue(listed.toString().contains("11:00:00"));
        var stale=tool(token,"schedule_delete",Map.of("seriesId",id,"scope","SERIES","version",0,"source","CHATGPT"));
        assertTrue(stale.path("isError").asBoolean()); assertEquals(409,stale.path("structuredContent").path("status").asInt());
        assertFalse(tool(token,"schedule_delete",Map.of("seriesId",id,"scope","OCCURRENCE","occurrenceDate","2026-11-03","version",1,"source","CHATGPT")).path("isError").asBoolean());
        assertFalse(tool(token,"schedule_delete",Map.of("seriesId",id,"scope","SERIES","version",2,"source","CHATGPT")).path("isError").asBoolean());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM schedule_events",Integer.class));
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));
        var history=tool(token,"schedule_history",Map.of("seriesId",id)).path("structuredContent").path("result");
        assertEquals("CHATGPT",history.get(0).path("data").path("declaredSource").asText());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM auth_tokens",Integer.class),"每条 MCP 工具调用必须退出内部 API 会话");
        assertTrue(tool(token,"schedule_verify",Map.of()).path("structuredContent").path("result").path("valid").asBoolean());
    }
    @Test void readScopeCannotWriteAndOriginAndOversizeAreRejected() throws Exception {
        String token=tokens("schedule:read").path("access_token").asText();
        assertEquals(403,tool(token,"schedule_create",Map.of("event",event("2026-11-01T09:00:00","NONE"),"source","CHATGPT")).path("structuredContent").path("status").asInt());
        var request=HttpRequest.newBuilder(URI.create(BASE+"/mcp")).header("Origin","https://evil.example").header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofString("{}"));
        assertEquals(403,browser.send(request.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(413,send("POST","/mcp"," ".repeat(65537),token).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM schedule_events",Integer.class));
    }
    @Test void codesAreSingleUsePkceAndResourceAreBoundAndRefreshRotates() throws Exception {
        String code=code("schedule:read schedule:write");
        assertEquals(400,exchange(code,VERIFIER,"https://wrong.example/mcp").statusCode());
        var exchanged=exchange(code,VERIFIER,RESOURCE); assertEquals(200,exchanged.statusCode(),exchanged.body());
        assertEquals(400,exchange(code,VERIFIER,RESOURCE).statusCode());
        // A new grant avoids relying on a replayed grant (the authorization server may invalidate it).
        var grant=tokens("schedule:read"); String refresh=grant.path("refresh_token").asText(); assertFalse(refresh.isEmpty());
        var refreshed=form("/oauth2/token",Map.of("grant_type","refresh_token","refresh_token",refresh,"resource",RESOURCE),true);
        assertEquals(200,refreshed.statusCode(),refreshed.body()); assertNotEquals(refresh,json.readTree(refreshed.body()).path("refresh_token").asText());
        assertEquals(400,form("/oauth2/token",Map.of("grant_type","refresh_token","refresh_token",refresh),true).statusCode());
    }
    @Test void revokedTokensFailAndJdbcGrantsSurviveServiceRecreation() throws Exception {
        String token=tokens("schedule:read").path("access_token").asText();
        var restored=new JdbcOAuth2AuthorizationService(db,clients).findByToken(token,OAuth2TokenType.ACCESS_TOKEN);
        assertNotNull(restored); assertEquals("admin",restored.getPrincipalName());
        assertEquals(200,form("/oauth2/revoke",Map.of("token",token,"token_type_hint","access_token"),true).statusCode());
        assertEquals(401,send("POST","/mcp","{}",token).statusCode());
    }
    @Test void badPkceAndRedirectAndInvalidArgumentsDoNotWrite() throws Exception {
        String code=code("schedule:read"); assertEquals(400,exchange(code,"b".repeat(64),RESOURCE).statusCode());
        var wrong=send("GET",authorize("schedule:read","").replace(enc(CALLBACK),enc("https://evil.example/callback")),null,null);
        assertEquals(400,wrong.statusCode()); assertTrue(wrong.headers().firstValue("Location").isEmpty());
        String token=tokens("schedule:read schedule:write").path("access_token").asText();
        var invalid=tool(token,"schedule_update",Map.of("seriesId",UUID.randomUUID().toString(),"scope","OCCURRENCE","source","CHATGPT","event",Map.of("title","test","start","2026-11-01T09:00:00","version",0)));
        assertTrue(invalid.path("isError").asBoolean());
        var unknown=tool(token,"schedule_list",Map.of("from","2026-11-01T00:00:00","to","2026-11-02T00:00:00","url","https://evil.example"));
        assertTrue(unknown.path("isError").asBoolean()); assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));
    }
    @Test void authorizationRequiresResourceAndS256AndClientSecret() throws Exception {
        login();
        for(String url:List.of(authorize("schedule:read","").replace("&resource="+enc(RESOURCE),""),
                authorize("schedule:read","").replace("code_challenge_method=S256","code_challenge_method=plain"))) {
            var response=send("GET",url,null,null);
            assertTrue(response.statusCode()==400 || response.statusCode()==302 && response.headers().firstValue("Location").orElse("").contains("error="));
        }
        String code=code("schedule:read");
        assertEquals(401,form("/oauth2/token",Map.of("client_id","scheduletool-chatgpt","grant_type","authorization_code","code",code,"redirect_uri",CALLBACK,"code_verifier",VERIFIER),false).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM schedule_events",Integer.class));
    }
    @Test void signatureIssuerAudienceAndExpirationAreChecked() throws Exception {
        String token=tokens("schedule:read").path("access_token").asText();
        assertEquals(401,send("POST","/mcp","{}",token.substring(0,token.length()-8)+"invalid!").statusCode());
        var key=com.nimbusds.jose.jwk.RSAKey.parse(Files.readString(PRIVATE.resolve("key.jwk")));
        var stored=new JdbcOAuth2AuthorizationService(db,clients);
        var original=stored.findByToken(token,OAuth2TokenType.ACCESS_TOKEN);
        for(int kind=0;kind<3;kind++) {
            var claims=new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer(kind==0?"https://wrong.example":BASE)
                .audience(kind==1?"https://wrong.example/mcp":RESOURCE).subject("admin")
                .issueTime(new Date(System.currentTimeMillis()-3600000)).expirationTime(new Date(System.currentTimeMillis()+(kind==2?-600000:600000)))
                .claim("scope",List.of("schedule:read")).build();
            var signed=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256).keyID("test-key").build(),claims);
            signed.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
            var access=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,signed.serialize(),java.time.Instant.now(),java.time.Instant.now().plusSeconds(600),Set.of("schedule:read"));
            stored.save(OAuth2Authorization.from(original).accessToken(access).build());
            assertEquals(401,send("POST","/mcp","{}",signed.serialize()).statusCode());
        }
    }
}
