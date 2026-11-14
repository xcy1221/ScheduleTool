param([switch]$ExternalWeb, [string]$WebVersion)
$ErrorActionPreference = 'Stop'
if ($WebVersion -and $WebVersion -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$') { throw 'WebVersion 格式无效' }
if ($ExternalWeb -and !$WebVersion) { throw '独立前端构建必须指定 -WebVersion' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$previousWebVersion = $env:SERVER_WEB_VERSION
if ($WebVersion) { $env:SERVER_WEB_VERSION = $WebVersion }
Push-Location (Join-Path $projectRoot 'frontend')
try {
    npm ci --no-fund --no-audit
    if ($LASTEXITCODE) { throw '前端依赖安装失败' }
    npm run build
    if ($LASTEXITCODE) { throw '前端构建失败' }
} finally { Pop-Location; $env:SERVER_WEB_VERSION = $previousWebVersion }
Push-Location (Join-Path $projectRoot 'backend')
try {
    if ($ExternalWeb) { mvn -B -Pexternal-web clean verify }
    else { mvn -B clean verify }
    if ($LASTEXITCODE) { throw '后端构建或测试失败' }
} finally { Pop-Location }
