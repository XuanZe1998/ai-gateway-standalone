# 文件说明：stop.ps1：项目自动化脚本；按脚本参数执行对应任务。
[CmdletBinding()]
param(
    [switch]$RemoveData
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location -LiteralPath $projectRoot

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw '未检测到 Docker。'
    }

    $composeArgs = @('compose', 'down', '--remove-orphans')
    if ($RemoveData) {
        $composeArgs += '--volumes'
    }

    & docker @composeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose 停止失败，退出码：$LASTEXITCODE"
    }

    $remaining = & docker compose ps -aq
    if ($LASTEXITCODE -ne 0) {
        throw '无法验证容器停止状态。'
    }
    if ($remaining) {
        throw "仍有项目容器未删除：$($remaining -join ', ')"
    }

    if ($RemoveData) {
        Write-Host 'AI 网关已停止，项目数据卷已删除。'
    } else {
        Write-Host 'AI 网关已停止，数据卷已保留。'
    }
} finally {
    Pop-Location
}
