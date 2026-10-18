package cn.scheduletool;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

@RestController @ConditionalOnProperty(name="app.mcp.enabled",havingValue="true")
class McpPages {
    private final McpSettings settings;
    McpPages(McpSettings settings) { this.settings=settings; }
    @GetMapping({"/.well-known/oauth-protected-resource","/.well-known/oauth-protected-resource/mcp"})
    Map<String,Object> metadata() {
        return Map.of("resource",settings.resource(),"resource_name","ScheduleTool 日程工具",
            "authorization_servers",List.of(settings.issuer()),"scopes_supported",List.of(McpOAuth.READ,McpOAuth.WRITE),
            "bearer_methods_supported",List.of("header"));
    }
    static String escape(String value) { return HtmlUtils.htmlEscape(value==null?"":value); }
    static String csrf(HttpServletRequest request) {
        var token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
        return token==null?"":"<input type=\"hidden\" name=\""+escape(token.getParameterName())+"\" value=\""+escape(token.getToken())+"\">";
    }
    @GetMapping(value="/mcp-login",produces=MediaType.TEXT_HTML_VALUE)
    String login(HttpServletRequest request) {
        String error=request.getParameter("error")==null?"":"<p role=\"alert\">登录失败，请检查账号密码；多次失败后请等待 10 分钟。</p>";
        return """
            <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>ScheduleTool · 授权登录</title><style>
            :root{color-scheme:light dark}body{font:16px system-ui;max-width:420px;margin:10vh auto;padding:24px;line-height:1.6}
            label,input,button{display:block;box-sizing:border-box;width:100%%}input,button{font:inherit;padding:10px;margin:8px 0 20px}
            button{cursor:pointer}p[role=alert]{color:#bb3333}</style><h1>连接 ScheduleTool</h1>
            <p>使用日程工具的登录账号。登录后可查看并选择授予 ChatGPT 的日程权限。</p>%s
            <form method="post" action="%s/mcp-login">%s<label>用户名<input name="username" autocomplete="username" maxlength="100" required></label>
            <label>密码<input name="password" type="password" autocomplete="current-password" maxlength="200" required></label><button>登录并继续</button></form></html>
            """.formatted(error,escape(request.getContextPath()),csrf(request));
    }
    @GetMapping(value="/mcp-consent",produces=MediaType.TEXT_HTML_VALUE)
    String consent(HttpServletRequest request,@RequestParam("client_id") String clientId,@RequestParam String state,@RequestParam String scope) {
        if(!settings.clientId().equals(clientId) || state.length()>1000) throw new ApiError.Failure(400,"授权请求不正确");
        var scopes=List.of(scope.split(" "));
        if(!List.of(McpOAuth.READ,McpOAuth.WRITE).containsAll(scopes)) throw new ApiError.Failure(400,"授权范围不正确");
        String hidden="<input type=\"hidden\" name=\"client_id\" value=\""+escape(clientId)+"\"><input type=\"hidden\" name=\"state\" value=\""+escape(state)+"\">"+csrf(request);
        String action=escape(request.getContextPath())+"/oauth2/authorize";
        var html=new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>ScheduleTool · 操作授权</title><style>:root{color-scheme:light dark}body{font:16px system-ui;max-width:480px;margin:10vh auto;padding:24px;line-height:1.8}label{display:block;margin:18px 0}button{font:inherit;padding:8px 20px;margin:8px 0}</style><h1>授权连接日程工具</h1><p>当前账号：")
            .append(escape(request.getUserPrincipal().getName())).append("。请选择允许 ChatGPT 使用的权限。</p><form method=\"post\" action=\"").append(action).append("\">").append(hidden);
        for(String item:scopes) html.append("<label><input type=\"checkbox\" name=\"scope\" value=\"").append(escape(item)).append("\" checked> ")
            .append(item.equals(McpOAuth.READ)?"读取日程和操作审计":"新增、修改及删除日程（同时需要读取权限）").append("</label>");
        return html.append("<button>允许所选权限</button></form><form method=\"post\" action=\"").append(action).append("\">")
            .append(hidden).append("<button>取消授权</button></form></html>").toString();
    }
    @ExceptionHandler(ApiError.Failure.class)
    org.springframework.http.ResponseEntity<Map<String,Object>> failure(ApiError.Failure e) {
        return org.springframework.http.ResponseEntity.status(e.status).body(Map.of("detail",e.getMessage()));
    }
}
