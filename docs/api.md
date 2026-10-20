# API v1

前缀 `/schedule/api/v1`。所有业务时间为 `Asia/Shanghai` 的 ISO 本地时间，如 `2026-10-17T09:00:00`，不带偏移；日期为 `YYYY-MM-DD`。JSON 接口错误使用 Problem Detail，含 `status`、`detail`。

## 鉴权

`POST /auth/login`，JSON：`{"username":"admin","password":"你的密码"}`。

返回 `accessToken`、`csrfToken`、`expiresAt`。Token 默认有效 24 小时；后续 API 使用 `Authorization: Bearer <accessToken>`。数据库只保存 token 的 SHA-256，不保存明文。登录连续失败 5 次暂限该来源 10 分钟。

浏览器使用同源 HttpOnly、SameSite=Strict Cookie；写请求需带 `X-CSRF-TOKEN`。`GET /auth/me` 获取身份和 CSRF；`POST /auth/logout` 撤销当前会话。API/OpenAPI 文档均需鉴权。登录端点本身不需要既有 token。

## 日程

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/events?from=2026-10-01T00:00:00&to=2026-11-01T00:00:00` | 展开重复，按起始时间排序，范围左闭右开，最长 366 天 |
| POST | `/events` | 新增，返回 201 与系列定义 |
| GET | `/events/{seriesId}` | 获取原始系列定义 |
| PUT | `/events/{seriesId}?scope=SERIES` | 修改整个系列，同时重置单次例外 |
| PUT | `/events/{seriesId}?scope=OCCURRENCE&occurrenceDate=2026-10-18` | 修改原规则的该次安排，可移动至其他日期；忽略正文中的重复规则 |
| DELETE | `/events/{seriesId}?scope=SERIES&version=0` | 删除整个系列，返回 204 |
| DELETE | `/events/{seriesId}?scope=OCCURRENCE&occurrenceDate=2026-10-18&version=0` | 取消该次安排，返回 204 |

POST / PUT 正文：

```json
{
  "title": "起床",
  "description": "可选备注",
  "start": "2026-10-17T07:30:00",
  "end": null,
  "allDay": false,
  "timeZone": "Asia/Shanghai",
  "recurrence": "DAILY",
  "repeatUntil": null,
  "color": "#256f62",
  "version": 0
}
```

`end=null` 表示时间点；重复为 `NONE` / `DAILY` / `WEEKLY`。全天 start 和 end 均为零点，end 为最后一天之后的零点。标题最长 200 字，备注 4000 字，日期范围 1900—2199 年。

列表返回展开实例，`id` 为展示标识 `seriesId@occurrenceDate`；写请求使用独立的 `seriesId`，不能把展示 id 当系列 id。`occurrenceDate` 始终指原规则日期，即使单次被移动也保持不变。`exception` 表示单次修改。系列修改、单次修改与取消都会递增整个系列的 `version`，后续写操作使用最新版本；过期版本返回 409。

常见错误：400 输入无效、401 未登录/已过期、403 缺少 CSRF、404 系列不存在、409 版本冲突、422 查询结果过多（超过 10000 条或 5000 个候选系列）、429 登录限流。

OpenAPI JSON：`GET /schedule/api/openapi`，Swagger UI：`/schedule/api/docs`。网页登录后可直接打开，也可给 JSON 请求加 Bearer 头。登录 API 返回的 token 供程序调用，不要存入前端 localStorage。

审计接口 `GET /audit-logs` 与 `GET /audit-logs/verify` 同样需要鉴权。成功日程写入自动保存完整前后快照，响应头含 `X-Audit-Sequence`，所有 API 响应含 `X-Request-ID`。分页、库外校验点及限制见 [日志与回溯](audit.md)。

新版写请求可带 `X-Schedule-Source: CODEX|CHATGPT|WEB|OTHER|UNKNOWN`，保存在审计 `data.declaredSource`，只作调用方自报标签。Cookie 缺省记 WEB，其他缺省或无效值记 UNKNOWN；不改写既有审计。MCP 工具和 OAuth 接入见 [MCP 说明](mcp.md)；当前版本已发布。
# TODO 与日历联动（V4）

- `GET /api/v1/todos?q=&status=&includeCompleted=false&unscheduled=false`：默认未完成，搜索四个文本字段，置顶优先。状态为 `PENDING/IN_PROGRESS/COMPLETED/SHELVED`；未排期指无后续或进行中的有效安排。
- `POST /api/v1/todos`：仅 `title` 必填（1—200 字）。可选 `description/expectedEffect/implementationIdea`（各 4000 字）、`status/pinned`。
- `GET /api/v1/todos/{id}`：`{todo,schedules:[{event,next}],next}`，包含全部关联系列与下一次有效安排；取消、改期、截止与跨天均参与计算。
- `PUT /api/v1/todos/{id}`：完整编辑对象与最新 `version`；完成/重开只改变整体状态，保留安排。
- `DELETE /api/v1/todos/{id}?version=&mode=KEEP_EVENTS|DELETE_EVENTS`：明确选择保留并解除所有关联，或删除关联系列及全部单次例外。先查询并确认影响范围。关联集合变化也递增待办版本，过期返回 409。
- 日程创建/系列编辑可携带 `todoId/todoVersion`，在同一事务内保存关联。新关联需要最新待办版本。`todoId` 缺省或 null 保留既有关联，空字符串解除；旧客户端不受影响。
- `PUT /api/v1/events/{id}/todo`：`{version,todoId,todoVersion}` 单独更新整个系列关联，不重置单次例外；空字符串解除，新增关联须提供 `todoVersion`。单次编辑不改变关联。

日程返回 `todoId/todoStatus`，一个系列最多属于一个待办。创建页面默认带入待办标题，随后各自独立。日程删除保留待办；执行结束不自动完成目标。写操作均支持 `X-Schedule-Source`、审计回执与事务回滚，409/超时后先重新查询，不盲重试。
