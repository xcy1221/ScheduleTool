package cn.scheduletool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class Auth implements HandlerInterceptor {
    public static final String COOKIE="SCHEDULE_SESSION";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(12);
    @Value("${app.username}") private String username;
    @Value("${app.password-hash}") private String passwordHash;
    @Value("${app.cookie-secure}") private boolean secure;
    @Value("${app.session-hours}") private int hours;
    private record Attempt(int count, Instant since) {}
    private final Map<String,Attempt> attempts=new ConcurrentHashMap<>();
    private final SecureRandom random=new SecureRandom();
    public Auth(JdbcTemplate db,ObjectMapper json) { this.db=db; this.json=json; }
    @PostConstruct void validate() {
        if(passwordHash==null || !passwordHash.matches("\\$2[aby]\\$\\d{2}\\$.{53}")) throw new IllegalStateException("必须先设置有效的 APP_PASSWORD_HASH（BCrypt）；参见启动说明");
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String token() { byte[] b=new byte[32]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private void cookie(HttpServletRequest req,HttpServletResponse res,String value,Duration age) {
        res.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(COOKIE,value).httpOnly(true).secure(secure).sameSite("Strict")
            .path(req.getContextPath().isEmpty()?"/":req.getContextPath()+"/").maxAge(age).build().toString());
    }
    record Login(@NotBlank @Size(max=100) String username,@NotBlank @Size(max=200) String password) {}
    record Session(String username,String accessToken,String csrfToken,Instant expiresAt) {}
    public synchronized Session login(Login in,HttpServletRequest req,HttpServletResponse res) {
        checkCredentials(in.username(),in.password(),req.getRemoteAddr());
        Instant now=Instant.now(); String access=token(),csrf=token(); Instant expiry=now.plusSeconds(hours*3600L);
        db.update("DELETE FROM auth_tokens WHERE expires_at<?",LocalDateTime.ofInstant(now,ZoneOffset.UTC));
        db.update("INSERT INTO auth_tokens(token_hash,csrf_token,expires_at) VALUES(?,?,?)",hash(access),csrf,LocalDateTime.ofInstant(expiry,ZoneOffset.UTC));
        cookie(req,res,access,Duration.ofHours(hours));
        return new Session(username,access,csrf,expiry);
    }
    synchronized void checkCredentials(String name,String password,String ip) {
        if(name==null || password==null || name.length()>100 || password.length()>200) throw new ApiError.Failure(401,"用户名或密码不正确");
        Instant now=Instant.now();
        attempts.entrySet().removeIf(e->e.getValue().since().plusSeconds(600).isBefore(now));
        var attempt=attempts.get(ip);
        if(attempt!=null && attempt.count()>=5) throw new ApiError.Failure(429,"尝试次数过多，请 10 分钟后重试");
        if(attempts.size()>=10000 && attempt==null) throw new ApiError.Failure(429,"请求过于频繁");
        boolean passwordOk=encoder.matches(password,passwordHash);
        if(!passwordOk || !MessageDigest.isEqual(name.getBytes(StandardCharsets.UTF_8),username.getBytes(StandardCharsets.UTF_8))) {
            attempts.put(ip,new Attempt(attempt==null?1:attempt.count()+1,attempt==null?now:attempt.since()));
            throw new ApiError.Failure(401,"用户名或密码不正确");
        }
        attempts.remove(ip);
    }
    @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) throws Exception {
        res.setHeader("X-Content-Type-Options","nosniff");
        res.setHeader("X-Frame-Options","DENY");
        res.setHeader("Cache-Control","no-store");
        String path=req.getRequestURI().substring(req.getContextPath().length());
        if(path.equals("/api/v1/auth/login") && req.getMethod().equals("POST")) return true;
        String authorization=req.getHeader("Authorization");
        boolean bearer=authorization!=null && authorization.startsWith("Bearer ");
        String access=bearer?authorization.substring(7):null;
        if(!bearer && req.getCookies()!=null) for(var c:req.getCookies()) if(c.getName().equals(COOKIE)) access=c.getValue();
        List<Session> sessions=access==null || access.length()>256 ? List.of() : db.query("SELECT csrf_token,expires_at FROM auth_tokens WHERE token_hash=? AND expires_at>?",
            (rs,n)->new Session(username,null,rs.getString("csrf_token"),rs.getObject("expires_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),hash(access),LocalDateTime.now(ZoneOffset.UTC));
        if(sessions.isEmpty()) return reject(res,401,"请先登录");
        Session session=sessions.getFirst();
        if(!bearer && !Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())) {
            String csrf=req.getHeader("X-CSRF-TOKEN");
            if(csrf==null || !MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8),session.csrfToken().getBytes(StandardCharsets.UTF_8))) return reject(res,403,"安全校验失败，请刷新后重试");
        }
        req.setAttribute("session",session); req.setAttribute("tokenHash",hash(access));
        req.setAttribute("authentication",bearer?"bearer":"cookie"); return true;
    }
    private boolean reject(HttpServletResponse res,int status,String detail) throws Exception {
        res.setStatus(status); res.setContentType("application/problem+json;charset=UTF-8");
        json.writeValue(res.getOutputStream(),Map.of("status",status,"detail",detail)); return false;
    }
    public void logout(HttpServletRequest req,HttpServletResponse res) {
        db.update("DELETE FROM auth_tokens WHERE token_hash=?",req.getAttribute("tokenHash")); cookie(req,res,"",Duration.ZERO);
    }
    @Configuration
    static class WebConfig implements WebMvcConfigurer {
        private final Auth auth;
        WebConfig(Auth auth) { this.auth=auth; }
        @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(auth).addPathPatterns("/api/**","/swagger-ui/**"); }
    }
    @RestController @RequestMapping("/api/v1/auth")
    static class Controller {
        private final Auth auth;
        Controller(Auth auth) { this.auth=auth; }
        @PostMapping("/login") @Operation(summary="单用户登录", description="返回 24 小时有效的 Bearer token；浏览器使用 HttpOnly Cookie，写操作须带 X-CSRF-TOKEN。")
        Session login(@Valid @RequestBody Login in,HttpServletRequest req,HttpServletResponse res) { return auth.login(in,req,res); }
        @GetMapping("/me") @Operation(summary="获取当前登录状态与 CSRF token")
        Session me(HttpServletRequest req) { return (Session)req.getAttribute("session"); }
        @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(summary="退出并撤销当前 token")
        void logout(HttpServletRequest req,HttpServletResponse res) { auth.logout(req,res); }
    }
}
