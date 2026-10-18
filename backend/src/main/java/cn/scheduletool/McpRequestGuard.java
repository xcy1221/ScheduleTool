package cn.scheduletool;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(-150)
@ConditionalOnProperty(name="app.mcp.enabled",havingValue="true")
class McpRequestGuard extends OncePerRequestFilter {
    private final Set<String> origins=new HashSet<>(),hosts=new HashSet<>();
    private final java.util.concurrent.Semaphore capacity=new java.util.concurrent.Semaphore(8);
    McpRequestGuard(McpSettings p) {
        for(String value:List.of(p.issuer(),p.apiBase())) {
            URI uri=URI.create(value); hosts.add(uri.getRawAuthority().toLowerCase(Locale.ROOT));
            origins.add(uri.getScheme()+"://"+uri.getRawAuthority());
        }
        if(p.allowedOrigins()!=null) p.allowedOrigins().stream().filter(s->!s.isBlank()).forEach(origins::add);
        if(p.allowedHosts()!=null) p.allowedHosts().stream().filter(s->!s.isBlank()).map(s->s.toLowerCase(Locale.ROOT)).forEach(hosts::add);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals(request.getContextPath()+"/mcp");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String host=request.getHeader("Host"),origin=request.getHeader("Origin");
        if(host==null || !hosts.contains(host.toLowerCase(Locale.ROOT)) || origin!=null && !origins.contains(origin)) {
            response.sendError(403); return;
        }
        if(!capacity.tryAcquire()) { response.sendError(429); return; }
        try { chain.doFilter(request,response); } finally { capacity.release(); }
    }
}
