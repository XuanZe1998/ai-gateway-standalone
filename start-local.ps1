# 文件说明：start-local.ps1：项目自动化脚本；按脚本参数执行对应任务。
param()
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $root
Get-Content -LiteralPath '.env' | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
    }
}
$env:SPRING_PROFILES_ACTIVE = 'standalone'
$env:SERVER_PORT = $env:GATEWAY_PORT
$env:PG_HOST = 'localhost'
$env:PG_PORT = $env:PG_EXPOSE_PORT
$env:PG_SCHEMA = 'public'
$env:LOG_FILE = Join-Path $root 'logs\ai-gateway.log'
$env:CONFIG_STORE_PATH = Join-Path $root 'config-store'
$env:STATE_STORE_PATH = Join-Path $root 'data\state'
New-Item -ItemType Directory -Force -Path 'logs','config-store','data\state' | Out-Null
$java = 'D:\Programs\Environment\JDK\jdk17\bin\java.exe'
$jar = Join-Path $root 'target\ai-gateway-standalone-2.6.11.jar'
$out = Join-Path $root 'logs\gateway-console.out.log'
$err = Join-Path $root 'logs\gateway-console.err.log'
$p = Start-Process -FilePath $java -ArgumentList @('-jar', $jar) -WorkingDirectory $root -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
Set-Content -LiteralPath '.gateway.pid' -Value $p.Id -Encoding ascii
Write-Output "gatewayPid=$($p.Id)"
