$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$privateDir = Join-Path $projectRoot '.local'
$configFile = Join-Path $privateDir 'application-local.properties'
$jarFile = Join-Path $projectRoot 'backend/target/scheduletool.jar'
$runtimeDir = Join-Path $privateDir 'runtime'
$runtimeJar = Join-Path $runtimeDir 'scheduletool.jar'
$pidFile = Join-Path $privateDir 'app.pid'
if (!(Test-Path -LiteralPath $configFile)) { throw '缺少 .local/application-local.properties，参见 README.md' }
if (!(Test-Path -LiteralPath $jarFile)) { throw '请先运行 scripts/build.ps1' }
if (Test-Path -LiteralPath $pidFile) {
    $existingPid = [int](Get-Content -LiteralPath $pidFile)
    $existing = Get-CimInstance Win32_Process -Filter "ProcessId=$existingPid"
    if ($existing -and $existing.CommandLine.Contains($runtimeJar)) { Write-Host 'ScheduleTool 已在运行：http://127.0.0.1:18080/schedule/'; exit 0 }
}
$pendingPassword = Join-Path $privateDir 'initial-password.txt'
if (Test-Path -LiteralPath $pendingPassword) {
    $hash = Get-Content -LiteralPath $pendingPassword -Raw | java -jar $jarFile --hash-password
    if ($LASTEXITCODE -or !$hash.StartsWith('$2')) { throw '密码哈希生成失败' }
    $configText = (Get-Content -LiteralPath $configFile -Raw).Replace('app.password-hash=PENDING', "app.password-hash=$hash")
    [IO.File]::WriteAllText($configFile, $configText, [Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $pendingPassword
}
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
Copy-Item -LiteralPath $jarFile -Destination $runtimeJar
$archiveDir = Join-Path $privateDir 'logs/startup'
New-Item -ItemType Directory -Force -Path $archiveDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-ffff'
foreach ($name in @('app.log', 'app-error.log')) {
    $previousLog = Join-Path $privateDir $name
    if (Test-Path -LiteralPath $previousLog) { Move-Item -LiteralPath $previousLog -Destination (Join-Path $archiveDir "$stamp-$name") }
}
$process = Start-Process -FilePath 'java' -ArgumentList @('-jar', ('"' + $runtimeJar + '"'), '--spring.config.additional-location=optional:file:./.local/application-local.properties') -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $privateDir 'app.log') -RedirectStandardError (Join-Path $privateDir 'app-error.log') -PassThru
Set-Content -LiteralPath $pidFile -Value $process.Id
for ($attempt = 0; $attempt -lt 45; $attempt++) {
    if ($process.HasExited) { throw '应用启动失败，检查 .local/app.log 与 app-error.log' }
    try {
        $response = Invoke-WebRequest -Uri 'http://127.0.0.1:18080/schedule/' -UseBasicParsing -TimeoutSec 2
        if ($response.StatusCode -eq 200) { Write-Host '已启动：http://127.0.0.1:18080/schedule/'; exit 0 }
    } catch { Start-Sleep -Seconds 1 }
}
throw '启动超时，请检查 .local/app.log'
