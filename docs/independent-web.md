# 独立前端发布

前端业务地址仍为 `/schedule/`，API 固定为 `/schedule/api/*`。设置、日历、TODO、免登录续会话和 CSRF 行为保持；OAuth/MCP 及其登录、同意页面继续由后端提供。

## 构建

```powershell
./scripts/build.ps1 -ExternalWeb -WebVersion <不可变版本>
```

产物分别为 `frontend/dist/` 和 `backend/target/scheduletool.jar`。独立前端资源带 `/_web/schedule/<版本>/` 前缀，入口仍部署在 `/schedule/`。`mvn -Pexternal-web clean verify` 不将前端加入 JAR；不选此 profile 时保留原有一体打包方式。不要用旧 target 目录增量打包替代 clean。

公共主题和 Vue 排版控件使用 `@homeserver/theme`、`@homeserver/ui` 1.0.0，本地固定制品存于 `frontend/vendor/` 并由锁文件校验。更新公共包时替换制品、刷新锁文件并重新构建本应用；日历刻度与业务专有样式继续在本仓库维护。旧主题键、`scheduletool-layout-v1` 及 `serverpanel-page-layout-v1:schedule` 保留。

前端产物由 Server Panel 的统一 Nginx 按应用发布、校验、切换和回滚，保留旧版本资源；纯前端发布无需重启本后端或 Tunnel。基础设施的实际管理入口是 home_server 的 `python scripts/serverctl.py`，发布前先 plan。

## 后端与边界

- `/schedule/health` 独立检查数据库连接：就绪为 200/UP，否则 503/DOWN；不依赖首页、Cookie 或静态资源，不返回数据库细节。
- Dockerfile、预构建 Compose 和 Tunnel 启动探针均使用此健康接口；Tunnel 控制平面健康检查保持原样。
- `/schedule/api/*`、Swagger、OAuth、MCP、`.well-known` 先于页面路由转发；接口错误不能回退成 HTML。
- 内网免登录仍依赖入口限制，Cookie/CSRF 与身份认证不变；生产密码、密钥、授权名单和数据库结构不属于前端迁移。
- 前端回滚选择上一不可变版本，后端回滚使用原 JAR/镜像及原健康探针；配置回退不恢复业务数据。

发布版本和验证结果见 [部署记录](deployment.md#7-部署记录)。
