$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $projectRoot 'frontend')
try {
    npm ci --no-fund --no-audit
    if ($LASTEXITCODE) { throw '前端依赖安装失败' }
    npm run build
    if ($LASTEXITCODE) { throw '前端构建失败' }
} finally { Pop-Location }
Push-Location (Join-Path $projectRoot 'backend')
try {
    mvn -B clean verify
    if ($LASTEXITCODE) { throw '后端构建或测试失败' }
} finally { Pop-Location }
