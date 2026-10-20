# 维护与备份

V4 升级：备份库、配置与密钥，先在独立库恢复备份并执行 `deploy/upgrade-v4.sql`；正式库核对 V1—V3 校验和，停止本项目，执行 V4 并仅新增两表的应用 DML 授权，再升级应用。V4 不改动旧表和旧审计，回退旧 JAR/镜像时保留新增表；不要回滚 SQL 数据。恢复旧配置不会撤销用户升级后的业务写入。

新环境见 [部署流程](deployment.md)。命令在实际服务目录执行；使用既有服务器管理工具的环境，应通过该工具检查实时状态、差异和发布基线。

## 升级与重启

1. 备份本项目库、私有配置、OAuth 签名密钥、库外审计校验点和版本信息。
2. 构建并测试新版本，记录提交、JAR SHA-256 和镜像 ID/digest。
3. 核对 `flyway_schema_history`。只追加迁移，不修改已执行的 V1—V3；生产迁移由维护身份执行，常驻应用无 DDL 凭据。
4. 检查 Compose 差异后发布，验证页面、API、审计链和可选 MCP。

仅重启本项目，MCP 版本先完整停止再按依赖启动，避免隧道探测到仍在退出的旧应用：

```sh
docker compose stop
docker compose up -d --wait --wait-timeout 120
docker compose ps
```

更新镜像时先执行 `docker compose build`，再使用上述流程。回退时恢复旧配置和镜像；V3 为新增表，回退应用不删除 OAuth 表。不要通过删除 volume 恢复单个项目。

更换登录密码后清理 `auth_tokens`，并撤销需失效的 OAuth 授权。更换签名密钥会影响现有令牌，应安排重新授权。

## 备份与恢复

覆盖项目全部表，含日程、例外、审计、Flyway 历史、会话和 OAuth 授权。OAuth 表含令牌，备份按凭据保护，不放 Git 或公开目录。导出时避免并发 DDL。

```sh
# 在有 MySQL 客户端的维护环境执行；交互输入密码。
mysqldump --host=DB_HOST --user=DB_BACKUP --password --ssl-mode=REQUIRED \
  --single-transaction --no-tablespaces --set-gtid-purged=OFF \
  scheduletool > scheduletool.sql
# 仅在导出成功且文件非空后生成校验和。
sha256sum scheduletool.sql > scheduletool.sql.sha256
```

正式流程应先写临时文件，检查命令退出码和文件非空，再原子改名。另保存 `.env`、MCP 私有目录、公开 CA、审计校验点及发布版本。

恢复顺序：

1. 校验 SHA-256，保留当前快照，停止本项目写入。
2. 新建隔离演练库，确认备份不含切换到原库的 `USE` 或 `CREATE DATABASE`；恢复到演练库，不能先覆盖正式库。
3. 在演练库核对表数、日程、跨天、重复例外、时区、迁移历史和审计链。
4. 清理演练库中的旧会话和 OAuth 授权，使用独立配置验证登录和重新授权。
5. 演练成功且获得正式恢复授权后，切换正式连接或恢复目标库，复核权限及业务行为。

恢复演练尚无完成记录；上述流程不能作为已验证恢复成功的证明。

## 排障

- 本机日志：`.local/logs/runtime.log`、`.local/app.log`、`.local/app-error.log`；服务器用 `docker compose logs --tail 80`。
- 启动失败：先核对数据库连接、TLS、账号表级权限和审计链；不要关闭校验绕过。
- 401 重新登录／授权，403 检查 CSRF 或 OAuth 权限，409 重新查询版本，429 停止重复登录。
- 隧道 ready 不代表授权和工具调用已通过；核对真实轮询、OAuth 发现和工具调用。无 Bearer 的 401 可能只是鉴权挑战。
- 本机更新使用 `stop.ps1` → `build.ps1` → `start.ps1`；保留 `.local` 和独立开发库。
