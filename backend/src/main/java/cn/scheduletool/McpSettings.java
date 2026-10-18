package cn.scheduletool;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.mcp")
public record McpSettings(boolean enabled,String issuer,String resource,String clientId,String clientSecretHash,
                          List<String> redirectUris,Path signingKeyFile,String apiBase,Path apiPasswordFile,
                          List<String> allowedOrigins,List<String> allowedHosts) {
    public void validate() {
        address(issuer); address(resource);
        if(issuer.endsWith("/")) throw new IllegalArgumentException("MCP issuer 不应以斜杠结尾");
        if(clientId==null || clientId.isBlank() || clientSecretHash==null || !clientSecretHash.matches("\\$2[aby]\\$\\d{2}\\$.{53}"))
            throw new IllegalArgumentException("请设置 MCP 客户端 ID 和 BCrypt secret hash");
        if(redirectUris==null || redirectUris.isEmpty()) throw new IllegalArgumentException("必须配置精确的 OAuth 回调地址");
        redirectUris.forEach(McpSettings::address);
        if(signingKeyFile==null || apiPasswordFile==null) throw new IllegalArgumentException("缺少 MCP 私密文件配置");
        URI api=URI.create(apiBase);
        if(!"http".equals(api.getScheme()) || !Set.of("localhost","127.0.0.1","::1").contains(api.getHost()) || api.getUserInfo()!=null || api.getQuery()!=null || api.getFragment()!=null)
            throw new IllegalArgumentException("MCP 内部 API 仅允许本进程的 loopback HTTP 地址");
    }
    static void address(String value) {
        if(value==null) throw new IllegalArgumentException("缺少 OAuth URL");
        URI uri=URI.create(value);
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null ||
            !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1","::1").contains(uri.getHost())))
            throw new IllegalArgumentException("OAuth URL 须为 HTTPS，本机开发可用 loopback HTTP");
    }
}
