# 文件说明：stop.ps1：项目自动化脚本；按脚本参数执行对应任务。
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')
Stop-DemoProcesses
if (Test-Path (Join-Path $DemoRuntime 'compose.env')) { Invoke-DemoCompose @('stop') }
Write-Host 'Local CAS demo stopped. Database and demo CA retained.'
