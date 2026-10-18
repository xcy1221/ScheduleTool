# ScheduleTool 数据库设计

2026-10-18；代码包含 V1（日程、会话）、V2（审计）、V3（OAuth）。V3 与来源自报审计已完成上线和测试。

## 1. 当前结构

正式业务库 `scheduletool`，旧开发库 `scheduletool_dev`；MCP 本地验收使用 `.local/mcp/data/` 的独立 H2 文件库，不接触服务器数据。业务时间采用 `Asia/Shanghai` 本地时间；会话过期与审计时间使用 UTC。UUID 由应用生成，迁移由 Flyway 管理。

| 表 | 用途 | 关联 |
| --- | --- | --- |
| `schedule_events` | 单次日程或重复系列的定义 | 系列主表 |
| `event_exceptions` | 某次重复安排的修改或取消 | 外键关联系列，整系列删除时级联删除 |
| `auth_tokens` | 登录会话 | 当前为配置式单用户，没有用户表 |
| `audit_log` | 成功提交的操作与完整前后快照 | series_id 为逻辑关联，无外键，日程删除后日志保留 |
| `audit_lock` | 串行化审计写入，避免哈希链分叉 | 固定一行 id=1 |
| `oauth2_authorization` | V3：OAuth 授权码、访问/刷新令牌及元数据 | 关联配置中的预注册客户端和单用户 |
| `oauth2_authorization_consent` | V3：用户已同意的 OAuth 权限 | 客户端、用户名联合主键 |
| `flyway_schema_history` | 迁移版本、校验和及执行状态 | Flyway 自行维护 |

## 2. 当前字段

### schedule_events

| 字段 | 类型 | 含义／约束 |
| --- | --- | --- |
| id | VARCHAR(36) | 主键，系列 UUID |
| title | VARCHAR(200) | 标题，必填 |
| description | VARCHAR(4000) | 备注，非 NULL，可为空字符串 |
| start_at | DATETIME | 开始时间，必填 |
| end_at | DATETIME NULL | 结束时间；NULL 表示时间点 |
| all_day | BOOLEAN | 是否全天；全天结束时间为次日零点，排他 |
| time_zone | VARCHAR(64) | 当前只接受 Asia/Shanghai |
| recurrence | VARCHAR(8) | NONE、DAILY、WEEKLY；应用校验 |
| repeat_until | DATE NULL | 重复截止日，包含当天；NULL 表示无截止 |
| color | VARCHAR(7) | #RRGGBB；应用校验 |
| version | BIGINT | 乐观锁，初始 0；系列或单次变更均递增 |
| created_at | DATETIME | 创建时间，上海时间 |
| updated_at | DATETIME | 最后修改时间，上海时间 |

索引：`idx_events_start(start_at)`。重复实例按查询范围展开，不逐条存储。

### event_exceptions

| 字段 | 类型 | 含义／约束 |
| --- | --- | --- |
| series_id | VARCHAR(36) | 外键 → schedule_events.id |
| occurrence_date | DATE | 原规则日期；实例移动后此键保持不变 |
| cancelled | BOOLEAN | true 表示取消该次 |
| title | VARCHAR(200) | 该次标题 |
| description | VARCHAR(4000) | 该次备注 |
| start_at | DATETIME | 该次实际开始时间 |
| end_at | DATETIME NULL | 该次实际结束时间 |
| all_day | BOOLEAN | 该次是否全天 |
| color | VARCHAR(7) | 该次颜色 |

联合主键：`(series_id, occurrence_date)`；索引：`idx_exceptions_start(start_at)`。整系列修改会清除既有例外，操作前快照由审计保留。当前该表没有独立版本号、来源或修改时间字段。

### auth_tokens

| 字段 | 类型 | 含义／约束 |
| --- | --- | --- |
| token_hash | VARCHAR(64) | 主键，访问 token 的 SHA-256；不存原 token |
| csrf_token | VARCHAR(64) | Cookie 写操作校验值，私密数据 |
| expires_at | DATETIME | UTC 过期时间 |

用户名与密码哈希来自私密配置。当前会话表没有用户名、client_id 或来源字段；Codex skill 也使用同一单用户登录接口。

### audit_log 与 audit_lock

| audit_log 字段 | 类型 | 含义／约束 |
| --- | --- | --- |
| sequence_id | BIGINT | 主键，应用按提交顺序分配连续正整数 |
| series_id | VARCHAR(36) | 被操作系列，无业务外键 |
| request_id | VARCHAR(36) | 服务端生成的请求 UUID |
| payload | LONGTEXT | 原始 JSON 正文，字段见下表 |
| previous_hash | VARCHAR(64) | 前一条哈希；首条为 64 个零 |
| record_hash | VARCHAR(64) | SHA-256(前条哈希 + 换行 + 原始正文 UTF-8) |

索引：`idx_audit_series(series_id, sequence_id)`、`idx_audit_request(request_id)`。`audit_lock` 只有 `id INTEGER PRIMARY KEY`，固定一行 `1`。

| payload 字段 | 当前含义 |
| --- | --- |
| schemaVersion | 新记录为 2，旧记录保持 1 |
| sequence、seriesId、requestId | 对应序号和关联标识 |
| action | CREATE、UPDATE、DELETE |
| scope、occurrenceDate | SERIES／OCCURRENCE；单次操作的原规则日期 |
| recordedAt | UTC ISO 时间 |
| actor | 登录用户名；无 HTTP 请求上下文时为 internal |
| authentication | cookie、bearer 或 internal，仅代表认证方式 |
| declaredSource | CODEX、CHATGPT、WEB、OTHER、UNKNOWN；自报标签，不是客户端身份证明 |
| remoteAddress | 直接连接地址；反向代理后可能是代理地址 |
| before、after | 完整系列行及全部例外行；不存在的一侧可省略 |

日程变更与审计在同一事务提交，失败一起回滚。日常应用账号对审计记录仅有 SELECT、INSERT 权限；校验及边界见 [日志说明](audit.md)。

## 3. 来源自报

按用户选择沿用应用账号，不为 CODEX、CHATGPT 分别建账号。skill 通过 X-Schedule-Source 填 CODEX，MCP 工具 source 由 AI 填 CHATGPT 等值；后端写入审计 `declaredSource`。Cookie 未提供标签时记 WEB，其他缺省或非法标签记 UNKNOWN。

来源放在每次操作的审计正文，未增加主表 created_source / updated_source 列。可按系列的 CREATE 与最近审计回查标签；旧审计没有该字段，不补造来源、不重写哈希。同一 admin 账号、IP、认证方式或自报标签均不能证明真实客户端身份。

## 4. OAuth 表与凭据

V3 使用 Spring Authorization Server JDBC 标准结构：`oauth2_authorization` 以 id 为主键，记录 registered_client_id、principal_name、授权类型、权限集合，以及授权码、访问/刷新令牌、各自签发/过期时间和元数据；BLOB 保存令牌及序列化数据。`oauth2_authorization_consent` 以客户端和用户名为联合主键，保存已同意权限。

应用账号密码仍由私密配置提供；OAuth 预注册客户端 ID、secret 哈希与精确回调地址来自配置，RSA 私钥从私密文件读取。OAuth 客户端用于连接授权，不是新增的 MySQL 账号，也不是来源标签。正式部署复用现有业务库与应用账号，仅新增两张表及必要 DML 权限。

OAuth 表包含敏感令牌，不具备审计表的仅追加限制；备份须按凭据保护。业务 API 的 auth_tokens 仍只保存 token 哈希，与 OAuth 令牌分开。MCP 不把 OAuth token 透传给业务 API，而是内部登录后调用 REST、结束即退出。

实现依据：[V1 SQL](../backend/src/main/resources/db/migration/V1__initial.sql)、[V2 SQL](../backend/src/main/resources/db/migration/V2__audit.sql)、[V3 SQL](../backend/src/main/resources/db/migration/V3__mcp_oauth.sql)、[审计服务](../backend/src/main/java/cn/scheduletool/AuditService.java)、[MCP 配置](mcp.md)。
