# ScheduleTool

个人中文日程工具，支持 TODO · 待办与想法及日历双向联动、月／周／日视图、全天与跨天、重复与单次例外，以及 REST API、MCP/OAuth 和事务审计。业务时区固定为 `Asia/Shanghai`。

采用 Java 21、Spring Boot 3.5.16、MySQL 8.4、Vue 3 和 TypeScript；支持独立前端与后端 JAR，也保留原一体打包。MCP 为可选功能，默认关闭。

## 开始使用

- [部署流程](docs/deployment.md)：数据库初始化、构建、HTTPS 反代、启动与验证。
- [本地开发](docs/development.md)：环境配置、构建、运行和测试。
- [独立前端](docs/independent-web.md)：不可变资源版本、后端健康检查与独立发布。
- [MCP 接入](docs/mcp.md)：OAuth 配置、14 个工具与本地验收。
- MCP Tunnel 模板使用独立 ISP 出站代理；配置与运行变更见 [MCP 部署](deploy/mcp/README.md) 和 [部署记录](docs/deployment.md#7-部署记录)。
- [维护与备份](docs/maintenance.md)：升级、重启、恢复和排障。
- [文档索引与目录说明](docs/README.md)：文件用途和项目目录。

## Server Panel 集成

遵循 [Server Panel 规范](https://192.168.1.71/server-readme/README.md)，统一顶部栏为 `HOMESERVER · ScheduleTool　←返回server panel　外观与排版`。布局偏好仅作用于本工具，明暗与色调使用共享设置；详见 [界面规范](docs/server-panel.md)。

## 使用约定

- 页面与 REST API 均限制为内网访问时，可用 `APP_WEB_AUTO_LOGIN=true` 开启免登录；Cookie/CSRF 和 MCP/OAuth 认证保留。
- 时间点不设置结束时间；时间段结束须晚于开始，最长 366 天。
- 全天界面填写最后一天，API `end` 为次日零点，采用排他结束。
- 每周重复取开始日期对应的星期；截止日包含当天，留空持续重复。
- 单次操作使用原规则日期；整系列修改会清除已有单次修改与取消。
- “外观与排版”支持明暗、五种色调、密度和周／日时间刻度，浏览器记住选择。
- 当前为单用户应用，不提供提醒推送和任务复盘。

接口见 [API 文档](docs/api.md)，数据结构见 [数据库设计](docs/database-design.md)，审计能力与边界见 [日志说明](docs/audit.md)。[ScheduleTool skill](skills/scheduletool/SKILL.md) 提供 Python 标准库客户端。

## 版本与维护状态

- 当前项目版本统一索引见 [VERSION](VERSION)，与 `backend/pom.xml`、`frontend/package.json` 保持一致；沿用现有构建和发布流程。
- 是否仍在维护以 [STATUS.md](STATUS.md) 的 `maintenance_status` 为准；维护结论变化时同步该文件及本节。
- 已上线内网免登录；线上构建提交与验证结果见 [部署记录](docs/deployment.md#7-部署记录)，文档合并不自动更新运行版本。
