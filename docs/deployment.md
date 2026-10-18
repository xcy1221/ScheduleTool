# 部署流程

适用于新环境部署当前 V3 结构。示例域名、网段须替换为实际值；已有环境升级见 [维护说明](maintenance.md)。

## 1. 准备环境

服务器需要 Docker Engine、支持 `--wait` 的 Compose v2、已有 MySQL 8.4 和 HTTPS 反向代理。应用不创建 MySQL 或 Caddy 容器，不发布宿主机端口。

确认 MySQL 与反代的外部网络已存在，MySQL 网络配置了 IPAM 子网；选择空闲固定地址作为 `MYSQL_IPV4`。MySQL 用户 host 必须与数据库观察到的应用来源匹配。外部数据库需按实际拓扑调整 Compose 网络。

```sh
git clone https://github.com/xcy1221/ScheduleTool.git
cd ScheduleTool
cp .env.example .env
chmod 600 .env
docker network inspect mysql_default caddy-proxy
```

填写 `.env` 的数据库连接、密码、登录哈希、网络名和固定 IP。`DB_URL` 使用可解析的 MySQL 服务名，要求 TLS；需要校验证书身份时另配 JVM 信任库并使用 `VERIFY_IDENTITY`。业务时区为 `Asia/Shanghai`。

## 2. 首次初始化数据库

把 `deploy/database-init.sql` 复制到受保护位置，替换 `REPLACE_WITH_APP_HOST` 和 `REPLACE_WITH_RANDOM_PASSWORD`；host 使用应用实际来源，密码与 `.env` 的 `DB_PASSWORD` 一致。修改库名和账号时同步替换所有引用。

用 MySQL 管理员在同一会话完整执行私有副本：

```sh
mysql --host=DB_HOST --user=DB_ADMIN --password --ssl-mode=REQUIRED --execute='source production-init.sql'
```

密码使用交互提示。客户端遇错停止，不使用 `--force`。脚本仅用于全新库：创建 V1—V3、匹配的 Flyway 历史、应用账号和表级权限，最后输出 `SCHEDULETOOL_DATABASE_READY`。DDL 自动提交，出错后核对已执行部分，不直接重跑。

生产设置 `SPRING_FLYWAY_ENABLED=false`，不需要迁移凭据。不要授予应用库级 DML；具备审计 UPDATE/DELETE 权限时应用会拒绝启动。V1/V2 旧库只按 [V3 增量说明](../deploy/mcp/README.md) 升级。

## 3. 构建与生成登录哈希

从源码构建，无需宿主机 Java/Node：

```sh
docker compose build
docker compose run --rm --no-deps scheduletool --hash-password
```

从标准输入输入至少 12 位网页登录密码，Linux 用 Ctrl+D 结束输入；哈希命令随后退出，不连接数据库。把 BCrypt 写入 `.env`，用单引号包围以保留 `$`。填写哈希前可先用占位值构建。

构建会运行前端检查和 Maven 测试。先在工作站测试再传输 JAR 时，使用 [预构建发布包](../deploy/README.md)。离线部署需在匹配架构环境构建，传输后核对镜像和 JAR 校验和。

## 4. 配置 HTTPS 入口

将以下片段加入现有 Caddy 站点，保留 `/schedule` 前缀：

```caddyfile
redir /schedule /schedule/ 308
handle /schedule/* {
    reverse_proxy scheduletool:8080
}
```

Caddy 与应用必须同在 `PROXY_NETWORK`。内部 CA 需在浏览器和客户端安装信任；Compose 固定 Secure Cookie，生产须使用 HTTPS。

若配置由生成器或管理工具维护，应修改生成源、验证后 reload，避免修改生成结果后被覆盖。代理应覆盖外部传入的转发头，并限制入口到预期网络。

## 5. 启动与验证

```sh
# 只校验，不打印展开后的密码
docker compose config --quiet
docker compose up -d --wait --wait-timeout 120
docker compose ps
docker compose logs --tail 80 scheduletool
```

访问 `https://schedule.example.com/schedule/`，验证登录、CRUD、重复例外和 `/schedule/api/docs`。写入应有 `X-Request-ID`、`X-Audit-Sequence`，审计校验有效；重启后数据保留。完整项目见 [回归清单](acceptance.md)。

日志在专用 volume；日程、审计和 OAuth 授权在外部 MySQL。记录 Git 提交、镜像 ID/digest、JAR SHA-256 和迁移版本，并备份私有 `.env`。

## 6. 可选 MCP/OAuth

基础部署完成后，按 [MCP 接入](mcp.md) 和 [MCP 容器部署](../deploy/mcp/README.md) 准备 OAuth 密钥与隧道发布包。无需 AI 接入时保持默认关闭。
