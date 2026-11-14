# 部署流程

新环境先执行首装 SQL 创建 V1—V3，再以维护身份执行 [V4 增量 SQL](../deploy/upgrade-v4.sql)（替换应用 host），最后启动当前应用。不要改写已执行迁移。已有环境按 [维护说明](maintenance.md) 备份与演练后仅执行 V4。

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

若页面与 `/schedule/api/*` 均仅允许内网访问，可设置 `APP_WEB_AUTO_LOGIN=true`，访问页面自动进入日历，无需输入密码；会话过期后自动恢复。默认 `false`。此开关不检查来源 IP，内网限制由反代/防火墙保证；不要向公网开放 REST API 或 `/auth/me`。Cookie、CSRF 和审计保留，MCP/OAuth 仍需原有认证，`APP_PASSWORD_HASH` 继续配置。改回 `false` 后清理 `auth_tokens` 可立即撤销已有免登录会话。

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

## 7. 部署记录

### 2026-11-14 独立前端（待发布）

- 新增不可变版本资源前缀、`external-web` 后端打包 profile 及独立 `/schedule/health`；部署和 Tunnel 启动探针不再检查首页。
- `/schedule/` 及 API、Cookie/CSRF、内网免登录和 OAuth/MCP 路径保持，未修改业务数据、数据库结构、密钥或授权名单。
- 本批构建、验证和实际版本由发布完成后回填；当前不能据此认定已上线。构建和回滚方式见 [独立前端](independent-web.md)。
- 本地验收：TypeScript/生产构建、6 项前端和 48 项后端测试通过；隔离浏览器验证桌面及 390 px 下明暗五色共 20 组布局/色彩一致，首页与外观浮层各 4 组截图逐像素一致，旧偏好、恢复本页默认、日历刻度和返回入口通过。尚待生产入口及真实 MCP 回查。

### 2026-11-14 内网页面免登录

- 已部署 [PR #8](https://github.com/xcy1221/ScheduleTool/pull/8)，前后端均构建自 `43921791d3eda7188452712731940fa479f1a933`；镜像 `scheduletool:lan-auto-60f8f65df23d`，JAR SHA-256 为 `60f8f65df23dce56ff7627c2a8683673f7d37b3acc6cc760bcb258850358905b`。
- 生产开启 `APP_WEB_AUTO_LOGIN=true`，页面与 REST API 仍限制为内网访问。两个全新客户端免登录、Cookie/CSRF、审计链和实际 MCP 调用验证通过；应用与隧道 healthy，发布计划无差异或漂移，原有业务与审计数据指纹不变。
- 前端构建、6 项前端测试和 46 项后端测试通过。已备份数据库和配置并校验备份；未创建业务测试数据，验证会话已撤销。详细证据保留在私有维护目录。
- [PR #7](https://github.com/xcy1221/ScheduleTool/pull/7) 随后合并为 `81e277e`，仅同步下述 ISP 出站模板与说明，未替换线上 JAR。

### 2026-11-13 Tunnel ISP 出站

2026-11-13 23:09:49（Asia/Shanghai）：按用户授权将 ScheduleTool Tunnel 控制平面切换至 `http://isp-gateway:17890`。仅重建 `scheduletool-tunnel`，出站移入既有 `isp-gateway_default` 网络；Caddy 固定地址、tunnel ID、运行密钥及 `0.0.16-startup1` 镜像不变，镜像 ID 为 `sha256:d97a1dce7177e246877d908511c424dc6376eaa91d1ff3ac4ba6c830302ad3fc`。

切换前 `serverctl plan` 无漂移；配置变更后 `pull schedule --replace` 刷新基线，仅两个配置文件变化。容器官方健康检查要求的控制平面轮询已通过；应用 JAR、MySQL 和 OAuth 配置未改动，ScheduleTool 后端及既有 OpenClaw 容器的 ID、启动时间均保持。模板基线为 `7decf757`。

当日 23:15:43（Asia/Shanghai），通过现有 ChatGPT ScheduleTool 插件的真实 OAuth 通路只读调用 `schedule_status` 成功，返回 `Asia/Shanghai` 且服务器时间正常。

回退时恢复原 Tunnel 代理地址和出站网络，再单独重建 Tunnel worker；运行配置及备份保存在私有目录，不写入本文。模板和说明已通过 PR #7 合并；上述运行切换当时未部署应用代码。
