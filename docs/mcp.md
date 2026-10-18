# MCP 接入

当前 MCP/OAuth 和常驻隧道已上线并完成测试。基础部署见 [部署流程](deployment.md)，容器发布包见 [MCP 部署](../deploy/mcp/README.md)。新环境默认关闭 MCP。

## 协议与工具

使用官方 Java MCP SDK 1.1.5 和 Spring Authorization Server，入口为 `/schedule/mcp`，无会话 Streamable HTTP。每次调用校验签名、issuer、audience、有效期和撤销状态。

工具为 `schedule_status`、`schedule_list`、`schedule_get`、`schedule_create`、`schedule_update`、`schedule_delete`、`schedule_history`、`schedule_verify`。读取需 `schedule:read`；写入同时需 `schedule:read schedule:write`。写操作复用 REST 业务规则与事务审计。

OAuth 使用授权码 + PKCE S256、精确回调地址、预注册客户端和 `client_secret_basic`。访问令牌 15 分钟、刷新令牌 7 天且轮换、授权码 2 分钟且只能使用一次。`source` 为 AI 自报标签，不代表可信身份。

## 服务配置

| 配置 | 含义 |
| --- | --- |
| `MCP_ENABLED` | 默认 false，启用时为 true |
| `MCP_ISSUER` | 浏览器可达的 HTTPS 授权服务地址，无尾斜杠，如 `https://schedule.example.com/schedule` |
| `MCP_RESOURCE` | 客户端实际授权请求中的精确资源标识；直连默认 issuer + `/mcp` |
| `MCP_CLIENT_ID` | 默认 `scheduletool-chatgpt` |
| `MCP_CLIENT_SECRET_HASH` | OAuth 客户端 secret 的 BCrypt，区别于网页密码和隧道 key |
| `MCP_REDIRECT_URIS` | 连接管理页显示的精确回调 URL，多个以逗号分隔 |
| `MCP_SIGNING_KEY_FILE` | 持久化 RSA 私钥 JWK 文件 |
| `MCP_API_PASSWORD_FILE` | 应用登录密码文件，供进程内 REST 调用 |
| `MCP_ALLOWED_HOSTS` / `MCP_ALLOWED_ORIGINS` | 按实际请求配置精确白名单 |

内部 API 默认访问本进程 loopback；不把 OAuth token 透传给 REST。隧道的资源标识可能与内网 issuer 不同，必须以实际授权请求为准，不能根据展示地址猜测或放宽 audience 校验。

浏览器登录页为 issuer + `/mcp-login`，使用日程工具账号。ChatGPT 连接选择 Tunnel、OAuth、自定义客户端，填写客户端 ID、secret、`client_secret_basic` 和 scope；回调复制管理页的当前完整值。

Secure MCP Tunnel 由客户端出站轮询 OpenAI。运行需 Tunnels Read + Use，创建／管理需 Read + Manage，并关联目标工作区；授权服务器的可达性须单独验证。以 [OpenAI 官方隧道指南](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels) 的当前流程为准。

## 本机隔离验收

先按 [本地开发](development.md) 准备 `.local/login.txt`，构建前端，再从仓库根目录执行：

```powershell
npm --prefix frontend ci
npm --prefix frontend run build
mvn -B -f backend/pom.xml -Pmcp-local clean package
./scripts/mcp-local.ps1 prepare
./scripts/mcp-local.ps1 start
# 检查与停止
./scripts/mcp-local.ps1 status
./scripts/mcp-local.ps1 stop
```

`prepare` 仅首次执行，已有目录时拒绝覆盖。独立 H2 库、密钥和运行副本在 `.local/mcp/`，入口 `http://127.0.0.1:18081/schedule/`；生产 JAR 不含 H2。需要指定本机实际用户时使用 `prepare -OwnerAccount '计算机名\用户'`。

实际读写与持久化验收：

```powershell
python scripts/mcp-smoke.py before
./scripts/mcp-local.ps1 stop
./scripts/mcp-local.ps1 start
python scripts/mcp-smoke.py after
```

脚本仅清理自己记录的 5 个系列，保留审计；临时令牌在完成后撤销。重跑前归档已完成的 `smoke-state.json`，不覆盖未完成证据。

## 本机隧道调试

按官方指南下载客户端到 `.local/tunnel-client/`，核对版本及校验和；仓库脚本已验证 v0.0.16，换版本先核对 CLI 帮助。配置自己的 tunnel ID：

```powershell
./scripts/mcp-tunnel.ps1 init -TunnelId tunnel_你的隧道ID
./scripts/mcp-tunnel.ps1 doctor -PromptKey
./scripts/mcp-tunnel.ps1 run -PromptKey
```

密钥在终端安全输入，不放聊天或参数；前台运行需保持终端打开。健康页仅监听 `127.0.0.1:18083`，日志在 `.local/mcp/logs/`。本机 loopback HTTP 仅用于隔离测试，生产验证 HTTPS。隧道脚本保留 UTF-8 BOM，兼容 Windows PowerShell 5.1。

验收必须包含真实 OAuth 关联、工具读写和重启后授权／数据保留，单独健康检查不能替代。当前验证范围见 [验证结果](verification.md)。
