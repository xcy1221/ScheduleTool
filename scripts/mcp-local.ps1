param([ValidateSet('prepare','start','stop','status')][string]$Action = 'status', [string]$OwnerAccount)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskPrivate = Join-Path $taskRoot '.local/mcp'
$taskJar = Join-Path $taskRoot 'backend/target/scheduletool.jar'
$taskRuntime = Join-Path $taskPrivate 'scheduletool.jar'
$taskPidFile = Join-Path $taskPrivate 'app.pid'
$taskProcessFile = Join-Path $taskPrivate 'process.json'
Set-Location -LiteralPath $taskRoot
function Test-McpLocalJar([string]$taskPath) {
    if (!(Test-Path -LiteralPath $taskPath)) { return $false }
    $taskArchive = [IO.Compression.ZipFile]::OpenRead($taskPath)
    try { return [bool]($taskArchive.Entries | Where-Object FullName -Like 'BOOT-INF/lib/h2-*.jar') }
    finally { $taskArchive.Dispose() }
}

if ($Action -eq 'prepare') {
    if (Test-Path -LiteralPath $taskPrivate) { throw '.local/mcp 已存在，保留原密钥和数据；无需重复 prepare' }
    if (!(Test-Path -LiteralPath $taskJar)) { throw '先执行 mvn -Pmcp-local clean package，参见 docs/mcp.md' }
    if (!(Test-McpLocalJar $taskJar)) { throw 'JAR 缺少本地 H2 驱动，请使用 -Pmcp-local 构建' }
    $taskLogin = @{}
    foreach ($taskLine in Get-Content -LiteralPath (Join-Path $taskRoot '.local/login.txt') -Encoding utf8) {
        $taskParts = $taskLine -split '：',2
        if ($taskParts.Length -eq 2) { $taskLogin[$taskParts[0].Trim()] = $taskParts[1].Trim() }
    }
    if (!$taskLogin['用户名'] -or !$taskLogin['密码']) { throw '.local/login.txt 缺少应用登录信息；不要在聊天中提供密码' }
    & java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar $taskJar --prepare-mcp $taskPrivate
    if ($LASTEXITCODE) { throw 'MCP 私密配置初始化失败' }
    $taskIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    if (!$OwnerAccount) { $OwnerAccount = $taskIdentity }
    & icacls $taskPrivate /inheritance:r /grant:r "${taskIdentity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' *> $null
    if ($LASTEXITCODE) { throw '私密目录权限设置失败' }
    & icacls $taskPrivate /grant:r "${OwnerAccount}:(OI)(CI)F" *> $null
    if ($LASTEXITCODE) { throw '私密目录所有者访问配置失败' }
    $taskHash = ($taskLogin['密码'] | & java -jar $taskJar --hash-password).Trim()
    if ($LASTEXITCODE -or !$taskHash.StartsWith('$2')) { throw '应用密码哈希生成失败' }
    $taskClientHash = (Get-Content -LiteralPath (Join-Path $taskPrivate 'client-secret.hash') -Raw).Trim()
    $taskDbPassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    [IO.File]::WriteAllText((Join-Path $taskPrivate 'api-password.txt'), $taskLogin['密码'], [Text.UTF8Encoding]::new($false))
    $taskNormalized = '.local/mcp'
    $taskConfig = @"
server.address=127.0.0.1
server.port=18081
server.servlet.context-path=/schedule
spring.datasource.url=jdbc:h2:file:./.local/mcp/data/schedule;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.username=sa
spring.datasource.password=$taskDbPassword
spring.flyway.user=sa
spring.flyway.password=$taskDbPassword
app.username=$($taskLogin['用户名'])
app.password-hash=$taskHash
app.cookie-secure=false
app.audit-check-permissions=false
app.mcp.enabled=true
app.mcp.issuer=http://127.0.0.1:18081/schedule
app.mcp.resource=http://127.0.0.1:18081/schedule/mcp
app.mcp.client-id=scheduletool-chatgpt
app.mcp.client-secret-hash=$taskClientHash
app.mcp.redirect-uris=http://127.0.0.1:18082/callback
app.mcp.signing-key-file=$taskNormalized/signing.jwk
app.mcp.api-base=http://127.0.0.1:18081/schedule
app.mcp.api-password-file=$taskNormalized/api-password.txt
logging.file.name=.local/mcp/logs/runtime.log
logging.logback.rollingpolicy.file-name-pattern=.local/mcp/logs/runtime.%d{yyyy-MM-dd}.%i.log.gz
"@
    [IO.File]::WriteAllText((Join-Path $taskPrivate 'application.properties'), $taskConfig, [Text.UTF8Encoding]::new($false))
    Write-Host '本地 MCP 配置已准备，密钥位于受限的 .local/mcp；未连接服务器数据库。'
    exit 0
}

$taskProcess = $null
if (Test-Path -LiteralPath $taskPidFile) {
    $taskProcess = Get-Process -Id ([int](Get-Content -LiteralPath $taskPidFile)) -ErrorAction SilentlyContinue
    if ($taskProcess) {
        if (!(Test-Path -LiteralPath $taskProcessFile)) { throw '缺少进程身份记录，拒绝停止未核对进程' }
        $taskRecord = Get-Content -LiteralPath $taskProcessFile -Raw | ConvertFrom-Json
        if ($taskRecord.runtimeJar -ne $taskRuntime -or $taskProcess.ProcessName -ne 'java' -or $taskProcess.StartTime.ToUniversalTime().Ticks -ne [long]$taskRecord.startTicks -or $taskProcess.Path -ne $taskRecord.executable) { throw 'PID 或启动时间已改变，拒绝操作其他进程' }
    }
}
if ($Action -eq 'status') { if ($taskProcess) { Write-Host '本地 MCP 正在运行：http://127.0.0.1:18081/schedule/mcp' } else { Write-Host '本地 MCP 未运行' }; exit 0 }
if ($Action -eq 'stop') {
    if ($taskProcess) { Stop-Process -InputObject $taskProcess; $taskProcess.WaitForExit(10000) | Out-Null }
    Remove-Item -LiteralPath $taskPidFile,$taskProcessFile -ErrorAction SilentlyContinue
    Write-Host '本地 MCP 已停止'; exit 0
}
if ($taskProcess) { Write-Host '本地 MCP 已在运行'; exit 0 }
if (!(Test-Path -LiteralPath (Join-Path $taskPrivate 'application.properties'))) { throw '先执行 scripts/mcp-local.ps1 prepare' }
if (Test-McpLocalJar $taskJar) { Copy-Item -LiteralPath $taskJar -Destination $taskRuntime }
elseif (!(Test-McpLocalJar $taskRuntime)) { throw '缺少本地验收 JAR，请先使用 -Pmcp-local 构建' }
$taskLogStamp = Get-Date -Format 'yyyyMMdd-HHmmss-ffff'
foreach ($taskName in @('startup.log','startup-error.log')) {
    $taskPreviousLog = Join-Path $taskPrivate $taskName
    if (Test-Path -LiteralPath $taskPreviousLog) { Move-Item -LiteralPath $taskPreviousLog -Destination (Join-Path $taskPrivate "$taskLogStamp-$taskName") }
}
$taskProcess = Start-Process java -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', ('"' + $taskRuntime + '"'), '--spring.config.additional-location=optional:file:./.local/mcp/application.properties') -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskPrivate 'startup.log') -RedirectStandardError (Join-Path $taskPrivate 'startup-error.log') -PassThru
Set-Content -LiteralPath $taskPidFile -Value $taskProcess.Id
$taskStarted = Get-Process -Id $taskProcess.Id
@{ runtimeJar=$taskRuntime; startTicks=$taskStarted.StartTime.ToUniversalTime().Ticks; executable=$taskStarted.Path } | ConvertTo-Json | Set-Content -LiteralPath $taskProcessFile -Encoding utf8
for ($taskAttempt=0; $taskAttempt -lt 45; $taskAttempt++) {
    if ($taskProcess.HasExited) { throw '本地 MCP 启动失败，检查 .local/mcp/startup.log' }
    try {
        $taskResponse = Invoke-WebRequest 'http://127.0.0.1:18081/schedule/.well-known/oauth-protected-resource/mcp' -TimeoutSec 2
        if ($taskResponse.StatusCode -eq 200) { Write-Host '本地 MCP 已启动：http://127.0.0.1:18081/schedule/mcp'; exit 0 }
    } catch { Start-Sleep -Seconds 1 }
}
throw '本地 MCP 启动超时，检查 .local/mcp/startup.log'
