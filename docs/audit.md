# 日志与操作回溯

## 自动记录

网页、Swagger UI、skill 和其他 REST 客户端共用日程服务的事务钩子。每次成功的新增、修改、删除自动追加审计：UTC 时间、登录用户、认证方式、请求 ID、系列 ID、单次/整系列范围、原规则日期，以及完整系列和全部例外的前后快照。删除后仍保留记录，无日程外键级联。

新增 MCP 通过同一 REST API 写入。新版 payload.schemaVersion=2，`declaredSource` 保存 X-Schedule-Source 自报标签；Cookie 缺省为 WEB，其他缺省或无效标签为 UNKNOWN。该字段不证明真实客户端身份，不改写旧正文或哈希；当前版本已发布，以查询实际返回为准。

日程和审计在同一数据库事务中提交；审计写入失败则日程回滚。并发写入先取得专用行锁，保证序号与哈希链连续。409、校验失败、未登录等失败请求不记为成功变更，按请求 ID 查看运行日志。响应头 `X-Request-ID` 用于排错；成功写入另有 `X-Audit-Sequence`。

## 可信度与权限

- `audit_log` 对日常应用账号仅开放 SELECT、INSERT；`audit_lock` 开放 SELECT 和锁列 UPDATE，满足 MySQL `FOR UPDATE` 要求。业务表另行授权，应用无审计记录 UPDATE、DELETE、DROP、ALTER 权限。启动时用零行语句实测审计 UPDATE/DELETE 必须被 MySQL 拒绝，并校验已有哈希链，否则拒绝启动。
- 每条记录保存 `SHA-256(前条哈希 + 换行 + 原始 payload UTF-8)`，首条前哈希为 64 个零。校验还核对连续序号及索引列与正文的一致性。原始 payload 是存储的 JSON 文本，不应重排后再计算。
- 提交后另向轮转文件写入序号、哈希和请求 ID，形成库外回执。skill 的 `checkpoint` 会先对照旧校验点再保存新校验点；旧版本留在 `.local/audit-checkpoints/`。建议把校验点和备份复制到独立、限制修改的位置。
- 链内校验无法独自发现尾部删除或管理员重算整条链；库外校验点能保护此前固定的历史，不能证明其后的所有记录。数据库和本机同时被控制时也无独立保证。文件回执在提交后的进程崩溃中可能缺失，以数据库已提交记录为准。

这是应用事务钩子，**不是 MySQL trigger 或 binlog 审计**。数据库管理员直接写业务表会绕过它；日常应用账号仍须通过服务使用。无法补出启用前的操作历史，也不能凭同一个 admin 登录名确定具体自然人。`remoteAddress` 是直连来源，未来反代后可能是代理地址。审计不替代完整备份，也不自动撤销操作。

新库使用 [首装 SQL](../deploy/database-init.sql)，开发迁移后使用 [表级授权模板](../deploy/database-dev-grants.sql)，先替换账号和 host。权限会累加，必须撤销原库级 DML。迁移身份属于可信管理员边界，生产日常进程不携带迁移凭据。

## 查询与校验

全部接口须登录，只提供读取和校验，不提供删除/修改审计 API：

- `GET /schedule/api/v1/audit-logs?after=0&limit=50`：按序号升序，limit 1—200；可加 `seriesId`、`requestId`，下页 after 使用最后序号。
- `GET /schedule/api/v1/audit-logs/verify`：全链校验，返回 valid、checked、lastSequence、lastHash、brokenSequence。
- 校验时同时传 `expectedSequence`、`expectedHash`：对照已在库外保存的校验点，可发现已固定历史的改写或截断。

skill 示例（在 `skills/scheduletool` 目录）：

```powershell
python scripts/scheduletool.py history --limit 20
python scripts/scheduletool.py history --series-id 系列UUID
python scripts/scheduletool.py verify
python scripts/scheduletool.py checkpoint
python scripts/scheduletool.py logs --tail 50
```

校验异常时保留数据库、日志、旧校验点和备份，停止写入并调查；不要清空审计或用新校验点覆盖异常。恢复误删前先核对完整快照和当前状态，经用户明确恢复授权后实施；暂未提供一键撤销。

## 文件与保留

本地原实例运行日志为 `.local/logs/runtime.log`，MCP 独立验收实例为 `.local/mcp/logs/runtime.log`；按日或 10MB 轮转，最多 30 天、合计 300MB。正式服务将 `/app/logs` 挂到专用日志卷，已部署验证；线上日志使用既有管理入口查看。

请求日志不记录正文、查询参数、密码、Cookie、Bearer 或 CSRF token；仅记录路径、状态、耗时、请求 ID 与审计序号。业务审计快照包含日程内容，按私密数据管理，不提交 Git。审计表无自动删除策略；备份须覆盖 audit_log 与 audit_lock，未来归档需同时设计链与校验点的连续性。
# V4 兼容扩展

新审计载荷使用 schemaVersion=3，保留原 `seriesId/requestId/sequence` 索引和哈希算法。旧载荷字节不变，V1/V2 审计及库外校验点继续有效。`entityType` 区分 TODO/EVENT；待办审计中的 `seriesId` 为待办 ID，可沿用历史查询筛选。

待办删除记录所有关联系列及例外的前快照、受影响 ID 和操作后的系列快照。待办、关联、日程与审计共用事务和审计锁；审计写入失败时全部回滚。来源仍为调用者自报。
