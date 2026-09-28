# 文件说明：stop-local.ps1：项目自动化脚本；按脚本参数执行对应任务。
param(
    [switch]$StopDatabase
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $root
if (Test-Path -LiteralPath '.gateway.pid') {
    $gatewayPid = [int](Get-Content -LiteralPath '.gateway.pid' -Raw)
    $process = Get-Process -Id $gatewayPid -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $gatewayPid
        $process.WaitForExit(15000)
        Write-Host "AI 网关进程已停止：$gatewayPid"
    }
    Remove-Item -LiteralPath '.gateway.pid' -Force
}
if ($StopDatabase) {
    docker compose stop postgres
}
