# ScheduleTool

个人中文日程工具，支持月／周／日视图、时间点与时间段、全天与跨天、每日／每周重复、单次例外，以及 REST API、MCP/OAuth 和操作审计。业务时区固定为 `Asia/Shanghai`。

2026-10-18 已完成上线和测试，用户已确认。采用 Java 21、Spring Boot 3.5.16、MySQL 8.4、Vue 3 和 TypeScript；前端资源打入同一个可运行 JAR。MCP 为可选功能，默认关闭。

## 开始使用

- [部署流程](docs/deployment.md)：数据库初始化、构建、HTTPS 反代、启动与验证。
- [本地开发](docs/development.md)：环境配置、构建、运行和测试。
- [MCP 接入](docs/mcp.md)：OAuth 配置、8 个工具与本地验收。
- [维护与备份](docs/maintenance.md)：升级、重启、恢复和排障。
- [文档索引与目录说明](docs/README.md)：文件用途和验证结果。

## 使用约定

- 时间点不设置结束时间；时间段结束须晚于开始，最长 366 天。
- 全天界面填写最后一天，API `end` 为次日零点，采用排他结束。
- 每周重复取开始日期对应的星期；截止日包含当天，留空持续重复。
- 单次操作使用原规则日期；整系列修改会清除已有单次修改与取消。
- “外观”支持明暗和五种色调，浏览器记住选择。
- 当前为单用户应用，不提供提醒推送和任务复盘。

接口见 [API 文档](docs/api.md)，数据结构见 [数据库设计](docs/database-design.md)，审计能力与边界见 [日志说明](docs/audit.md)。[ScheduleTool skill](skills/scheduletool/SKILL.md) 提供 Python 标准库客户端。

个人配置、账号、密钥、日程数据、运行日志及本机发布包存于忽略目录。公开前的核查结果见 [仓库整理记录](docs/repository.md)。
