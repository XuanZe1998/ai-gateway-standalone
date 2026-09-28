# 文件说明：common.ps1：项目自动化脚本；按脚本参数执行对应任务。
$ErrorActionPreference = 'Stop'
$DemoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$DemoRuntime = Join-Path $DemoRoot '.local-cas-demo'
$DemoCompose = Join-Path $PSScriptRoot 'compose.yml'
$DemoProcesses = Join-Path $DemoRuntime 'processes.json'

function Invoke-DemoCommand([string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$File failed (exit $LASTEXITCODE)." }
}
function Invoke-DemoCompose([string[]]$Arguments) {
    Invoke-DemoCommand docker (@('compose','--project-name','ai-gateway-local-cas-demo',
        '--env-file',(Join-Path $DemoRuntime 'compose.env'),'-f',$DemoCompose) + $Arguments)
}
function Get-DemoJavaHome {
    $candidates = @($env:JAVA_HOME, 'D:/Programs/Environment/JDK/jdk17')
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'bin/keytool.exe'))) {
            $version = (& (Join-Path $candidate 'bin/java.exe') -version 2>&1 | Out-String)
            if ($version -match 'version "17[.]') { return $candidate }
        }
    }
    throw 'Set JAVA_HOME to an installed JDK 17 (java and keytool are required).'
}
function Stop-DemoProcesses {
    if (!(Test-Path -LiteralPath $DemoProcesses)) { return }
    $records = @(Get-Content -LiteralPath $DemoProcesses -Raw | ConvertFrom-Json)
    foreach ($record in $records) {
        $process = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
        if (!$process) { continue }
        $info = Get-CimInstance Win32_Process -Filter "ProcessId=$($record.pid)"
        if ($process.StartTime.ToUniversalTime() -ne ([datetime]$record.startTime).ToUniversalTime() -or
            !$info.CommandLine.Contains($record.marker)) {
            throw "PID $($record.pid) no longer matches the demo process; refusing to stop it."
        }
        Stop-Process -Id $record.pid
        $process.WaitForExit(10000) | Out-Null
    }
    Remove-Item -LiteralPath $DemoProcesses
}
function Start-DemoProcess([string]$File, [string[]]$Arguments, [string]$Name, [string]$Marker) {
    # Start-Process joins arguments on Windows, so preserve spaces in absolute paths.
    $quoted = $Arguments | ForEach-Object { '"' + $_.Replace('"','\"') + '"' }
    $process = Start-Process -FilePath $File -ArgumentList $quoted -WorkingDirectory $DemoRuntime `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $DemoRuntime "$Name.out.log") `
        -RedirectStandardError (Join-Path $DemoRuntime "$Name.err.log")
    $records = @()
    if (Test-Path -LiteralPath $DemoProcesses) { $records = @(Get-Content $DemoProcesses -Raw | ConvertFrom-Json) }
    $records += @{pid=$process.Id;startTime=$process.StartTime.ToUniversalTime().ToString('o');marker=$Marker;name=$Name}
    ConvertTo-Json -InputObject @($records) | Set-Content -LiteralPath $DemoProcesses -Encoding utf8
    return $process
}
