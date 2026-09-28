# 文件说明：cleanup.ps1：项目自动化脚本；按脚本参数执行对应任务。
[CmdletBinding()]
param([switch]$RemoveData, [switch]$RemoveCertificate)
. (Join-Path $PSScriptRoot 'common.ps1')
& (Join-Path $PSScriptRoot 'stop.ps1')
if ($RemoveData -and (Test-Path (Join-Path $DemoRuntime 'compose.env'))) {
    Invoke-DemoCompose @('down','--volumes')
    Write-Host 'Removed only the ai-gateway-local-cas-demo containers/network/database volume. Demo data is not recoverable without a backup.'
}
if ($RemoveCertificate -and (Test-Path (Join-Path $DemoRuntime 'ca-thumbprint.txt'))) {
    $thumbprint = (Get-Content (Join-Path $DemoRuntime 'ca-thumbprint.txt') -Raw).Trim()
    $cert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new((Join-Path $DemoRuntime 'ca.cer'))
    if ($thumbprint -notmatch '^[A-F0-9]{40}$' -or $thumbprint -ne $cert.Thumbprint -or
        $cert.Subject -ne 'CN=AI Gateway Local CAS Demo CA') { throw 'Certificate identity mismatch; refusing removal.' }
    $certPath = "Cert:\CurrentUser\Root\$thumbprint"
    if (Test-Path $certPath) { Remove-Item -LiteralPath $certPath }
    Write-Host "Removed only the current-user demo CA: $thumbprint. Restarting the demo imports it again."
}
