# 文档与项目目录

| 文档 | 用途 |
| --- | --- |
| [部署流程](deployment.md) | 新环境从空库部署当前版本 |
| [本地开发](development.md) | 本机配置、构建和测试 |
| [维护与备份](maintenance.md) | 升级、重启、备份恢复与排障 |
| [MCP 接入](mcp.md) | OAuth、工具和本地验收 |
| [API](api.md) | REST 请求、重复规则与错误码 |
| [数据库设计](database-design.md) | 表结构、迁移与凭据边界 |
| [日志与回溯](audit.md) | 审计、权限和库外校验点 |
| [验收清单](acceptance.md) | 后续版本的回归检查 |

## 目录职责

| 路径 | 内容 |
| --- | --- |
| `backend/` | Java 服务、Flyway V1—V4 和后端测试 |
| `frontend/` | Vue 页面、主题、日历与依赖锁文件 |
| `deploy/` | 首装 SQL、开发授权、预构建 JAR 部署模板 |
| `deploy/mcp/` | 可选 MCP/OAuth 与隧道的容器模板 |
| `scripts/` | 本机构建、启停与 API/MCP 验证脚本 |
| `skills/scheduletool/` | 日程助手及 Python 客户端 |
| `tests/` | Python 客户端测试 |
| `docs/` | 功能、部署、维护和测试文档 |
| `.local/` | 私密配置、运行数据和归档；不提交 Git |

根目录 `Dockerfile` + `compose.yml` 用于源码构建；`deploy/compose.yml` 用于独立发布包中的已测试 JAR；MCP 发布包采用 `deploy/mcp/compose.yml`。三份 Compose 是不同入口，不叠加使用。
