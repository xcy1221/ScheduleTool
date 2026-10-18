param(
    [ValidateSet('init','doctor','run')][string]$Action = 'doctor',
    [string]$TunnelId,
    [switch]$PromptKey
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $taskRoot
$taskExecutable = Join-Path $taskRoot '.local/tunnel-client/tunnel-client.exe'
$taskProfiles = Join-Path $taskRoot '.local/mcp/tunnel'
if (!(Test-Path -LiteralPath $taskExecutable)) { throw '从 OpenAI 官方 tunnel-client Releases 下载 Windows amd64 版本并校验 SHA-256，放到 .local/tunnel-client' }
if ($Action -eq 'init') {
    if ($TunnelId -notmatch '^tunnel_[a-z0-9]{32}$') { throw '请填写 Platform 已创建的 tunnel_... ID' }
    if (!(Test-Path -LiteralPath (Join-Path $taskRoot '.local/mcp/application.properties'))) { throw '先准备本地 MCP 私密目录' }
    & $taskExecutable init --sample sample_mcp_with_dcr --profile scheduletool --profile-dir $taskProfiles --tunnel-id $TunnelId --mcp-server-url http://127.0.0.1:18081/schedule/mcp --health-listen-addr 127.0.0.1:18083 --control-plane-api-key-ref env:CONTROL_PLANE_API_KEY
    if ($LASTEXITCODE) { throw '隧道配置创建失败；保留已有配置，不自动覆盖' }
    exit 0
}
if (!(Test-Path -LiteralPath (Join-Path $taskProfiles 'scheduletool.yaml'))) { throw '先运行 init 并提供自己的 tunnel ID' }
$taskAddedKey = $false
try {
    if (!$env:CONTROL_PLANE_API_KEY) {
        if (!$PromptKey) { throw '尚未配置 CONTROL_PLANE_API_KEY；可使用 -PromptKey 在当前终端安全输入运行密钥，勿在聊天里提供' }
        $taskSecure = Read-Host '输入具有 Tunnels Read + Use 的运行密钥' -AsSecureString
        $taskPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskSecure)
        try { $env:CONTROL_PLANE_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPointer) }
        $taskAddedKey = $true
    }
    # 此脚本只连接固定的本机 HTTP 服务；允许其 OAuth 目标注册，不自动注册其他内网主机。
    if ($Action -eq 'doctor') { & $taskExecutable doctor --profile scheduletool --profile-dir $taskProfiles --explain --harpoon.allow-plaintext-http --harpoon.hosts-include-private=false }
    else {
        $taskLogDirectory = Join-Path $taskRoot '.local/mcp/logs'
        New-Item -ItemType Directory -Path $taskLogDirectory -Force | Out-Null
        $taskLogFile = Join-Path $taskLogDirectory ('tunnel-' + (Get-Date -Format 'yyyyMMdd-HHmmss-ffff') + '.jsonl')
        Write-Host '隧道正在前台运行，请保持本窗口开启；Ctrl+C 或关闭窗口会断开连接。'
        Write-Host ('健康页面：http://127.0.0.1:18083/ui；日志：' + $taskLogFile)
        & $taskExecutable run --profile scheduletool --profile-dir $taskProfiles --harpoon.allow-plaintext-http --harpoon.hosts-include-private=false --log.file $taskLogFile
    }
    if ($LASTEXITCODE) { throw '隧道客户端未通过检查或已退出，请查看脱敏诊断；不能报告 ChatGPT 已接通' }
} finally {
    if ($taskAddedKey) { Remove-Item Env:CONTROL_PLANE_API_KEY -ErrorAction SilentlyContinue }
}
