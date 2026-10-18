# MCP/OAuth 与隧道部署

这是独立发布包模板，适用于已有 MySQL、Caddy 和出站代理网络的 Linux x86_64 环境；基础建库和 HTTPS 见 [部署流程](../../docs/deployment.md)。不会自动创建外部网络或下载私有客户端包。

## 1. 准备发布包

在 [预构建 JAR 包](../README.md) 基础上，以本目录 `compose.yml` 替换普通版本，加入：

```text
app.jar                     已测试的普通生产 JAR
Dockerfile.runtime          来自 deploy/
Dockerfile.tunnel            来自本目录
tunnel-entrypoint.sh         来自本目录，保留 LF
tunnel-client               官方 Linux x86_64 发布包，已校验
cloudflared                 同一官方发布包的伴随程序
tunnel-licenses/             该发布包附带的许可证
tunnel.yaml                 按本目录模板填写
caddy-root.crt              内部公开 CA；公共 CA 环境见下文
.env                        按根目录 .env.example 填写
private/
  application-mcp.properties
  signing.jwk
  api-password.txt
  tunnel-runtime-key
```

客户端下载入口以 [OpenAI 官方指南](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels) 为准；本模板已验证 v0.0.16。更换版本先检查 CLI 帮助、配置与健康命令。`Dockerfile.tunnel` 从 Ubuntu Java 镜像提取 x86_64 Bash 依赖，不适用于 ARM。

## 2. 数据库与 OAuth 密钥

新库执行 `deploy/database-init.sql`，已包含 V3。仅旧 V1/V2 库使用 `upgrade-v3.sql`：复制为私有文件，替换 `REPLACE_WITH_APP_HOST`，确认库名、账号与成功的 V1/V2 校验和后，由管理员完整执行一次；先备份，遇错停止，不使用 `--force`。脚本检查现有状态，不创建新库或账号。失败后核对已提交 DDL，不盲目重跑。

在受保护的维护环境运行，目标目录必须尚不存在：

```sh
umask 077
java -jar app.jar --prepare-mcp private
```

会生成 `signing.jwk`、`client-secret.txt`、`client-secret.hash`。客户端 secret 安全填入连接管理页后移出运行包另存，不提交 Git。创建 `api-password.txt` 保存与应用哈希一致的网页登录密码；`tunnel-runtime-key` 保存具备 Tunnels Read + Use 的运行密钥，两者均使用文件安全输入。

`private/application-mcp.properties` 示例：

```properties
app.mcp.issuer=https://schedule.example.com/schedule
app.mcp.resource=填写实际授权请求的精确resource
app.mcp.client-id=scheduletool-chatgpt
app.mcp.client-secret-hash=填写client-secret.hash的内容
app.mcp.redirect-uris=填写连接管理页的精确回调URL
app.mcp.signing-key-file=/run/secrets/signing.jwk
app.mcp.api-password-file=/run/secrets/api-password.txt
```

私密目录及运行文件设置 UID/GID 10001、目录 0700、文件 0400；保留 SELinux 标签。签名密钥须持久化，不能每次发布重新生成。常驻容器保持 Flyway 禁用，无迁移凭据。

## 3. 网络与配置

- `.env` 填写镜像版本、`JAVA_IMAGE`、三个外部网络及空闲固定 IP；SQL host 对应应用在 MySQL 网络的来源。
- `tunnel.yaml` 替换 tunnel ID、MCP HTTPS 地址及 Harpoon 精确 host 正则。出站代理示例为 `mihomo:7890`，按实际环境修改；直连时去掉 `http_proxy` 并移除不需要的出站网络。
- `caddy-root.crt` 为内部公开 CA；如果使用公共 CA，移除 Compose 的 `--ca-bundle` 参数与对应证书挂载，使用镜像的系统 CA。
- 只有确实需要 Docker 回流访问时，才在现有内网拒绝规则前加入 `caddy-route.caddy`，替换网关占位值。它按来源 IP、路径和方法放行 MCP POST、元数据 GET、token/revoke POST；同网关其他容器也能访问这些端点，身份由 OAuth 校验。
- 反代配置由生成器维护时修改生成源，验证后 reload。浏览器仍须能访问授权页；登录、REST、Swagger 不通过此白名单向隧道放行。

## 4. 发布与验证

```sh
docker compose config --quiet
docker compose build
docker compose stop
docker compose up -d --wait --wait-timeout 120
docker compose ps
```

隧道按应用健康依赖启动，入口另等应用返回 200；原生健康检查要求成功控制平面轮询。保持运行后，按 [MCP 接入](../../docs/mcp.md) 完成 OAuth 关联并测试 8 个工具、重复例外、审计、重启持久化和令牌刷新。

已有服务器管理工具时，通过其预置挂载、登记文件来源、检查差异并发布；不要把整个私密目录登记成公开配置源。回退恢复旧配置及镜像，保留 V3 新表，数据库恢复先在隔离库演练。
