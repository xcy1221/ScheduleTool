# 本地开发

需要 Java 21、Maven 3.9+、Node 20.19+ 或 22.12+；Python 客户端使用标准库。普通运行使用 MySQL 8.4，独立 MCP 验收可使用 H2。

## 配置与运行

按 [部署流程](deployment.md) 准备独立开发库和受限应用账号。可把 SQL 模板中的 `scheduletool` / `scheduletool_prod` 分别改为 `scheduletool_dev` / `scheduletool_app`，host 改为本机实际来源；不要复用生产库。

创建 `.local/application-local.properties`：

```properties
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/scheduletool_dev?sslMode=REQUIRED&connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true
spring.datasource.username=scheduletool_app
spring.datasource.password=填写数据库密码
spring.flyway.enabled=false
app.username=admin
app.password-hash=填写BCrypt哈希
```

```powershell
./scripts/build.ps1
# 从标准输入输入至少 12 位登录密码；Windows 输入结束用 Ctrl+Z、回车。
java -jar backend/target/scheduletool.jar --hash-password
./scripts/start.ps1
# 停止
./scripts/stop.ps1
```

地址 `http://127.0.0.1:18080/schedule/`。运行使用 `.local/runtime/` 的 JAR 副本，更新后停止再启动。日志位于 `.local/logs/runtime.log` 和 `.local/app*.log`。

前端独立开发时，在 `frontend/` 运行 `npm ci`、`npm run dev`，访问 `http://127.0.0.1:15173/schedule/`。

开发者可另配迁移账号和 `spring.flyway.enabled=true`。迁移完成后，按 [开发授权模板](../deploy/database-dev-grants.sql) 配置应用账号。生产常驻进程不携带迁移账号。

## 客户端配置

在 `.local/login.txt` 保存应用账号，供 API 验证、MCP 本地准备和 skill 使用：

```text
用户名：admin
密码：填写与应用哈希对应的密码
```

skill 默认从源码所在仓库读取 `.local/`；安装到其他目录后设置 `SCHEDULETOOL_HOME` 或 `--home` 为项目路径。目标服务和公开 CA 可写入 `.local/skill-config.json`：

```json
{"url":"https://schedule.example.com/schedule/","caFile":"/path/to/public-ca.crt"}
```

也可用 `SCHEDULETOOL_URL`、`SCHEDULETOOL_CA_FILE`、`SCHEDULETOOL_USERNAME`、`SCHEDULETOOL_PASSWORD`。CA 使用公开证书，不关闭 HTTPS 校验。

## 验证

```powershell
npm --prefix frontend run build
mvn -B -f backend/pom.xml verify
python -m unittest discover -s tests -v
```

后端测试使用隔离 H2，不连接生产库。`scripts/api-smoke.py before` / `after` 会创建测试日程并检查重启持久化，只在测试环境执行。MCP 独立验收见 [MCP 文档](mcp.md)。
