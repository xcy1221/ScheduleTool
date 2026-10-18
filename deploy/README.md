# 部署模板

首选 [根目录源码部署](../docs/deployment.md)。此目录用于把已测试 JAR 打成独立发布包，不要求服务器安装 Maven 或 Node。

| 文件 | 用途 |
| --- | --- |
| `database-init.sql` | 全新库 V1—V3、Flyway 历史及受限应用账号 |
| `database-dev-grants.sql` | 开发迁移完成后的应用表级授权 |
| `Dockerfile.runtime` + `compose.yml` | 发布包，构建上下文须包含 `app.jar` |
| `mcp/` | OAuth 与常驻隧道的完整发布包模板 |

## 预构建 JAR 发布包

仓库根目录运行：

```powershell
./scripts/build.ps1
$releaseDir = '.local/release'
New-Item -ItemType Directory -Force $releaseDir | Out-Null
Copy-Item backend/target/scheduletool.jar "$releaseDir/app.jar"
Copy-Item deploy/Dockerfile.runtime,deploy/compose.yml,.env.example $releaseDir
Get-FileHash "$releaseDir/app.jar" -Algorithm SHA256
```

传到服务器专用目录，例如 `/opt/scheduletool`。复制 `.env.example` 为 `.env` 并填写，完成数据库和 Caddy 配置，再在发布包目录运行：

```sh
chmod 600 .env
docker compose config --quiet
docker compose build
docker compose up -d --wait --wait-timeout 120
```

`JAVA_IMAGE` 默认 Microsoft OpenJDK 21 Ubuntu，正式发布建议固定 digest。包内不放生产迁移账号。有既有发布管理工具时，通过其登记、差异检查和发布流程同步文件。
