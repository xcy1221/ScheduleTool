package cn.scheduletool;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fixed loopback REST bridge. Never accepts arbitrary URLs or forwards MCP access tokens. */
@Component @ConditionalOnProperty(name="app.mcp.enabled",havingValue="true")
class McpApi {
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base,username,password;
    McpApi(ObjectMapper json,McpSettings settings,@Value("${app.username}") String username) throws IOException {
        this.json=json; settings.validate(); this.base=settings.apiBase(); this.username=username;
        this.password=Files.readString(settings.apiPasswordFile(),StandardCharsets.UTF_8).stripTrailing();
        if(password.isEmpty()) throw new IllegalStateException("MCP API 密码文件为空");
    }
    record Reply(Object result,String requestId,String auditSequence) {}
    <T> T session(Function<Session,T> work) {
        var session=new Session();
        try {
            Object result=session.call("POST","/api/v1/auth/login",Map.of("username",username,"password",password),"UNKNOWN").result();
            session.token=((JsonNode)result).path("accessToken").asText();
            if(session.token.isEmpty()) throw new ApiError.Failure(502,"内部 API 登录未确认");
            return work.apply(session);
        } finally {
            if(!session.token.isEmpty()) try { session.call("POST","/api/v1/auth/logout",null,"UNKNOWN"); }
            catch(RuntimeException ignored) { org.slf4j.LoggerFactory.getLogger(McpApi.class).warn("MCP 内部 API 会话撤销未确认"); }
        }
    }
    class Session {
        private String token="";
        Reply call(String method,String path,Object body,String source) {
            if(!path.startsWith("/api/v1/") || path.contains("..")) throw new IllegalArgumentException("非法内部 API 路径");
            try {
                var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
                if(!token.isEmpty()) builder.header("Authorization","Bearer "+token);
                if(!"GET".equals(method) && (path.startsWith("/api/v1/events") || path.startsWith("/api/v1/todos"))) builder.header("X-Schedule-Source",source);
                builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
                var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
                byte[] bytes;
                try(var stream=response.body()) { bytes=stream.readNBytes(16*1024*1024+1); }
                if(bytes.length>16*1024*1024) throw new ApiError.Failure(502,"响应过大，请缩小查询范围");
                String requestId=response.headers().firstValue("X-Request-ID").orElse("");
                JsonNode data=bytes.length==0?null:json.readTree(bytes);
                if(response.statusCode()>=300) {
                    String detail=data==null?"请求被拒绝":data.path("detail").asText("请求被拒绝");
                    detail=detail.replace(password,"[REDACTED]");
                    if(!token.isEmpty()) detail=detail.replace(token,"[REDACTED]");
                    throw new ApiError.Failure(response.statusCode(),detail+"；requestId="+requestId+"。写操作不自动重试。");
                }
                return new Reply(data,requestId,response.headers().firstValue("X-Audit-Sequence").orElse(""));
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt(); throw new ApiError.Failure(502,"请求中断；先核对日程和审计，不直接重试写入");
            } catch(IOException e) { throw new ApiError.Failure(502,"内部 API 结果未确认；先核对日程和审计，不直接重试写入"); }
        }
    }
}
