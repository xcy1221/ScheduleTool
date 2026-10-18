package cn.scheduletool;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
@org.springframework.core.annotation.Order(-200)
public class RequestLog extends OncePerRequestFilter {
    private static final Logger LOG=LoggerFactory.getLogger(RequestLog.class);
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String id=UUID.randomUUID().toString(); long start=System.nanoTime();
        req.setAttribute("requestId",id); res.setHeader("X-Request-ID",id); MDC.put("requestId",id);
        boolean failed=false;
        try { chain.doFilter(req,res); }
        catch(ServletException|IOException|RuntimeException e) { failed=true; throw e; }
        finally {
            String path=req.getRequestURI().substring(req.getContextPath().length());
            if(path.startsWith("/api/") || path.equals("/mcp") || path.startsWith("/oauth2/") || path.equals("/mcp-login")) {
                path=path.replaceAll("[^a-zA-Z0-9/_-]","?");
                if(path.length()>160) path=path.substring(0,160);
                int status=failed?500:res.getStatus();
                var level=status>=400?LOG.atWarn():LOG.atInfo();
                level.log("request method={} path={} status={} audit={} elapsedMs={}",req.getMethod(),path,status,
                    req.getAttribute("auditSequence"),(System.nanoTime()-start)/1_000_000);
            }
            MDC.remove("requestId");
        }
    }
}
