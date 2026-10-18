$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$pidFile = Join-Path $projectRoot '.local/app.pid'
$jarFile = Join-Path $projectRoot '.local/runtime/scheduletool.jar'
if (!(Test-Path -LiteralPath $pidFile)) { Write-Host '没有本项目的进程记录'; exit 0 }
$appPid = [int](Get-Content -LiteralPath $pidFile)
$process = Get-CimInstance Win32_Process -Filter "ProcessId=$appPid"
if ($process) {
    if ($process.Name -ne 'java.exe' -or !$process.CommandLine.Contains($jarFile)) { throw 'PID 已被其他程序使用，拒绝停止' }
    Stop-Process -Id $appPid
}
Remove-Item -LiteralPath $pidFile
Write-Host 'ScheduleTool 已停止，数据库数据保留。'
