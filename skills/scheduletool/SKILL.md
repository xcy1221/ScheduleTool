---
name: scheduletool
description: 通过 REST API 管理 ScheduleTool 日程，查询统一操作审计、校验哈希链并排查本地错误。适用于本项目的日程操作及回溯，不用于其他日历服务或服务器部署。
---

# ScheduleTool 日程助手

## TODO 与关联（V4）

- MCP 新增 `todo_list/get/create/update/delete` 与 `schedule_link_todo`；权限仍用既有 schedule:read/write。先查待办/日程最新版本，写后回查并报告 requestId/auditSequence。
- 客户端：`todo-list [--q 文本] [--status PENDING|IN_PROGRESS|COMPLETED|SHELVED] [--include-completed] [--unscheduled]`、`todo-get ID`、`todo-create --input 文件`、`todo-update ID --input 文件`。
- 待办 JSON 仅标题必填，可含 description、expectedEffect、implementationIdea、status、pinned；编辑须带 version。完成/重开只修改整体状态，不删除安排；日程结束不自动完成待办。
- 日程创建可带 todoId/todoVersion 原子关联。`link 系列ID --todo-id 待办ID --todo-version 版本 --version 日程版本` 关联整个系列，不清除例外；解除时 `--todo-id ''`。旧客户端省略 todoId 会保留关联。
- `todo-delete ID --version 版本 --mode KEEP_EVENTS|DELETE_EVENTS --confirm-delete`：先查询并明确说明全部关联系列及单次例外的影响，按用户所选方式保留解除关联或一并删除。不要猜删除方式。409/超时先核对，不自动重试。
- 未排期指无后续或进行中的有效安排，包含已取消或已结束安排。详情返回全部系列及 next，有效排期随取消、改期和系列变更更新。审计 entityType=TODO 时 seriesId 为待办 ID，旧哈希链继续兼容。

存在已连接的 ScheduleTool MCP 工具时优先使用它们，先调用 `schedule_status` 核对服务和上海时间；否则使用下述本机脚本。MCP 登录由连接授权处理，不向工具传密码或 token。

## 本机入口

- 源码内运行时自动定位仓库；安装到其他目录后设置 `SCHEDULETOOL_HOME` 或 `--home` 为实际项目路径。目标地址和公开 CA 路径读取项目 `.local/skill-config.json`，未配置时使用 `http://127.0.0.1:18080/schedule/`。
- 优先调用本 skill 的 `scripts/scheduletool.py`，不直接写数据库。脚本使用 Python 标准库，自动登录并在结束时撤销会话，不输出密码、Bearer token 或 CSRF token。
- 可用 `SCHEDULETOOL_HOME`、`SCHEDULETOOL_URL`、`SCHEDULETOOL_CA_FILE` 或对应命令参数覆盖配置。非本机地址要求 HTTPS；内网证书使用 `--ca-file` 指向公开 CA，不关闭证书校验或复制 CA 私钥。

## 自动鉴权

- 使用网页/API 的应用登录账号，不使用 MySQL 的应用或迁移账号；agent 不需要数据库凭据。
- 脚本自行读取项目 `.local/login.txt` 的 `用户名：`、`密码：` 两行；`SCHEDULETOOL_USERNAME` / `SCHEDULETOOL_PASSWORD` 环境变量分别优先覆盖。不要用读取文件的工具把凭据打印到上下文，也不要放进 skill、命令参数、日程或回答。
- 已有配置时直接执行 `--source CODEX status` 检查连接，然后执行用户授权的操作，不重复询问密码。每条命令自动登录，Bearer token 仅留在进程内存，结束时调用退出接口；无需手动传入或保存 token。
- 配置缺失时，请用户在本机私密文件或进程环境中补齐，不让用户在聊天里粘贴密码。登录返回 401 时报告凭据无效并停止，不反复尝试；429 时停止重复登录。不要为验证登录而新增测试日程。

下列命令从本 skill 目录运行，其他目录使用脚本绝对路径：

```powershell
python scripts/scheduletool.py status
python scripts/scheduletool.py logs --tail 50
python scripts/scheduletool.py history --limit 10
python scripts/scheduletool.py verify
python scripts/scheduletool.py checkpoint
python scripts/scheduletool.py --help
```

## ChatGPT 与 MCP

- 具备本机执行环境且已获准使用私密配置的 ChatGPT，可运行同一脚本并填写 `--source CHATGPT`。云端 ChatGPT 不能假定能读取本机文件、已安装的 skill 或访问内网地址。
- MCP 是否可用以当前已连接工具为准。未连接时不能假装能调用，也不能把本机通过测试说成 ChatGPT 已连通。配置与验证方法见项目 `docs/mcp.md`。
- 日程工具为 `schedule_status/list/get/create/update/delete/history/verify`，另有上述 TODO 与关联工具。写操作的 `source` 由执行 AI 填写；401 走连接授权，403 核对读取/写入范围，409 重新查询。写入成功后调用查询工具回查，报告审计回执。
- MCP 适配服务从私密文件读取应用凭据并调用 REST API；OAuth 令牌与后端 API 令牌分开，不作为工具参数或返回值。不在日程操作中自动部署、改授权配置或开放网络。

## 日程操作

- 来源由执行 AI 自行填写：Codex 使用 `--source CODEX`，ChatGPT 使用 `--source CHATGPT`；不能确定时使用 UNKNOWN，不询问用户。参数放在子命令前，例如 `python scripts/scheduletool.py --source CODEX create --input 日程.json`。
- 脚本为日程新增、修改、删除请求附加 `X-Schedule-Source`；新版后端在审计 `data.declaredSource` 中保存。来源是自报标签，不能证明调用方身份。目标仍为旧版本时可能没有该字段，以审计实际返回为准；不能把发送请求头说成已经入库。不要将来源拼进标题或备注。
- 时间按用户当前日期和 `Asia/Shanghai` 理解。仅明确时间点时保持 `end=null`，不要补造时长；全天结束时间是最后一天之后的零点。
- `list --from 时间 --to 时间` 查询左闭右开区间，每次最长 366 天；`get 系列ID` 读取系列定义。
- `create --input JSON文件` 新增；`update 系列ID --scope SERIES或OCCURRENCE --input JSON文件` 修改；`delete 系列ID --scope SERIES或OCCURRENCE --version 版本号 --confirm-delete` 删除。
- 修改和删除前先查询，使用返回的 `seriesId` 与 `version`；展示用的 `id=seriesId@日期` 不能用于写操作。更新 JSON 必须带所查询到的版本号。409 后重新读取并解释差异，不自动套用最新版本重试。
- `OCCURRENCE` 必须同时提供 `--occurrence-date YYYY-MM-DD`，指原规则日期，即使实例已移动也不变。用户未说明修改/删除某一次还是整个系列时，只澄清这一范围；已明确的操作不重复索要确认。
- 整系列修改会清除所有单次例外和取消；执行前简短说明这一影响。不要为了改一个实例而重写整个系列。
- JSON 请求字段与重复规则详见项目 `docs/api.md`，需要时读取。临时正文文件放项目 `.local/`，不提交 Git。
- 成功后按目标时间范围回查，报告事项、时间、操作范围、`auditSequence` 与 `requestId`。写请求超时或断线时先查询日程和审计，不能直接重试。

## 日志与回溯边界

- 统一审计由服务端自动生成，覆盖网页、Swagger UI、本脚本等通过日程服务提交的写操作；包含系列及全部单次例外的前后快照。`history --series-id UUID` 或 `--request-id UUID` 筛选；使用返回的最后序号作为 `--after` 分页。
- 成功审计与日程处于同一事务，失败请求通过 `requestId` 查运行日志，不把失败当作已修改。`logs` 只读本机 `.local/logs/runtime.log`，旧启动输出在 `.local/logs/startup/`；它们不代表服务器日志。线上排错按项目 `docs/maintenance.md` 使用既有管理入口。日志含日程隐私，分享时只截取必要内容。
- `verify` 校验链并对照已有库外校验点；异常时停止写入并报告。`checkpoint` 校验通过后保存新校验点到 `.local/audit-checkpoints/`，同时更新本机索引。可把校验点复制到独立只读备份位置；不要用新记录覆盖异常前的证据。
- 切换服务地址时，先保留旧目标的校验点并使用新目标自己的校验点；URL 不匹配不代表目标数据被篡改，不要直接覆盖旧记录。
- 哈希链可发现内容变更、断链，库外校验点可发现已固定历史的改写/截断；两者不是独立第三方存证。数据库管理员直写、审计启用前的历史和已同时攻陷数据库及本机的情况不在完整保证范围内。详见项目 `docs/audit.md`。
- 恢复操作须符合用户具体授权，先核对当前数据与记录完整性；不要直接导入快照覆盖现有日程。备份恢复流程见项目 `docs/maintenance.md`。
- 401 检查登录；403 检查 Cookie/CSRF 用法；409 核对版本；429 停止重复登录并等待。服务不可达时先报告实际故障；本 skill 不自动重启、部署服务器、修改 Caddy 或执行生产迁移。
