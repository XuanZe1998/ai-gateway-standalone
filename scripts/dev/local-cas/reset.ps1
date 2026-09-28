# 文件说明：reset.ps1：项目自动化脚本；按脚本参数执行对应任务。
[CmdletBinding()]
param()
& (Join-Path $PSScriptRoot 'stop.ps1')
& (Join-Path $PSScriptRoot 'start.ps1') -NoBuild
Write-Host 'Both in-memory sessions and tickets reset. Open https://localhost:39443 again.'
