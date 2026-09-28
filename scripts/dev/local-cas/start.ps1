# 文件说明：start.ps1：项目自动化脚本；按脚本参数执行对应任务。
[CmdletBinding()]
param([switch]$NoBuild)
. (Join-Path $PSScriptRoot 'common.ps1')

foreach ($port in @(39443,39444,39445)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "Port $port is in use. Stop this demo with stop.ps1, or resolve the conflict. No existing process was stopped."
    }
}
if (Test-Path $DemoProcesses) { Stop-DemoProcesses }
$javaHome = Get-DemoJavaHome
$openssl = (Get-Command openssl -ErrorAction Stop).Source
$node = (Get-Command node -ErrorAction Stop).Source
Invoke-DemoCommand docker @('info','--format','{{.ServerVersion}}')
New-Item -ItemType Directory -Path $DemoRuntime -Force | Out-Null

# Limit generated private keys and credentials to the current account and SYSTEM.
$acl = [System.Security.AccessControl.DirectorySecurity]::new()
$acl.SetAccessRuleProtection($true,$false)
foreach ($sid in @([System.Security.Principal.WindowsIdentity]::GetCurrent().User,
    [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
    $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl',
        'ContainerInherit,ObjectInherit','None','Allow')
    $acl.AddAccessRule($rule)
}
[System.IO.FileSystemAclExtensions]::SetAccessControl([System.IO.DirectoryInfo]::new($DemoRuntime), $acl)
$secretFile = Join-Path $DemoRuntime 'secrets.json'
if (!(Test-Path $secretFile)) {
    $secrets = @{}
    foreach ($name in @('DEMO_DB_PASSWORD','DEMO_TLS_PASSWORD','DEMO_JWT_SECRET','DEMO_TEACHER_SECRET','DEMO_ADMIN_PASSWORD')) {
        $secrets[$name] = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    }
    $secrets | ConvertTo-Json | Set-Content -LiteralPath $secretFile -Encoding utf8
}
$values = Get-Content $secretFile -Raw | ConvertFrom-Json -AsHashtable
$values['JAVA_HOME'] = $javaHome
$values['DEMO_KEYSTORE'] = (Join-Path $DemoRuntime 'server.p12').Replace('\','/')
$values['DEMO_LOG_FILE'] = (Join-Path $DemoRuntime 'gateway.log').Replace('\','/')
$values['DEMO_TLS_KEY'] = Join-Path $DemoRuntime 'server.key'
$values['DEMO_TLS_CERT'] = Join-Path $DemoRuntime 'server.crt'
$values['CONFIG_STORE_PATH'] = Join-Path $DemoRuntime 'config-store'
$values['STATE_STORE_PATH'] = Join-Path $DemoRuntime 'state'
$values['JWT_SECRET'] = $values.DEMO_JWT_SECRET
$values['INITIAL_ADMIN_PASSWORD'] = $values.DEMO_ADMIN_PASSWORD
# Prevent inherited deployment overrides from silently changing this isolated environment.
$saved = @{}
foreach ($entry in Get-ChildItem Env:) {
    if ($entry.Name -match '^(SPRING_|SERVER_|PG_|CAMPUS_|TEACHER_|JAIROUTER_|JWT_|INITIAL_ADMIN_|GATEWAY_|DEMO_|CONFIG_STORE_PATH$|STATE_STORE_PATH$|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$)') {
        $saved[$entry.Name]=$entry.Value
        [Environment]::SetEnvironmentVariable($entry.Name,$null,'Process')
    }
}
foreach ($name in $values.Keys) {
    if (!$saved.ContainsKey($name)) { $saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
    [Environment]::SetEnvironmentVariable($name,[string]$values[$name],'Process')
}
$started = $false
try {
    "DEMO_DB_PASSWORD=$($values.DEMO_DB_PASSWORD)" | Set-Content (Join-Path $DemoRuntime 'compose.env') -Encoding ascii
    if (!(Test-Path (Join-Path $DemoRuntime 'server.p12'))) {
        Push-Location $DemoRuntime
        try {
            Invoke-DemoCommand $openssl @('req','-x509','-newkey','rsa:2048','-nodes','-days','30',
                '-keyout','ca.key','-out','ca.crt','-subj','/CN=AI Gateway Local CAS Demo CA',
                '-addext','basicConstraints=critical,CA:TRUE','-addext','keyUsage=critical,keyCertSign,cRLSign')
            Invoke-DemoCommand $openssl @('req','-new','-newkey','rsa:2048','-nodes','-keyout','server.key',
                '-out','server.csr','-subj','/CN=localhost')
            Invoke-DemoCommand $openssl @('x509','-req','-in','server.csr','-CA','ca.crt','-CAkey','ca.key',
                '-CAcreateserial','-out','server.crt','-days','14','-extfile',(Join-Path $PSScriptRoot 'leaf.cnf'),'-extensions','server')
            Invoke-DemoCommand $openssl @('pkcs12','-export','-in','server.crt','-inkey','server.key','-certfile','ca.crt',
                '-name','localhost','-out','server.p12','-passout','env:DEMO_TLS_PASSWORD')
            Invoke-DemoCommand (Join-Path $javaHome 'bin/keytool.exe') @('-importcert','-noprompt','-alias','local-cas-demo',
                '-file','ca.crt','-keystore','trust.p12','-storetype','PKCS12','-storepass:env','DEMO_TLS_PASSWORD')
            Invoke-DemoCommand $openssl @('x509','-in','ca.crt','-outform','DER','-out','ca.cer')
        } finally { Pop-Location }
    }
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new((Join-Path $DemoRuntime 'ca.cer'))
    $leaf = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new((Join-Path $DemoRuntime 'server.crt'))
    if ($leaf.NotAfter -le (Get-Date).AddHours(1)) { throw 'Demo TLS certificate expired. Run cleanup.ps1 -RemoveCertificate and regenerate demo runtime certificates.' }
    $certificate.Thumbprint | Set-Content (Join-Path $DemoRuntime 'ca-thumbprint.txt') -Encoding ascii
    if (!(Test-Path "Cert:\CurrentUser\Root\$($certificate.Thumbprint)")) {
        Import-Certificate -FilePath (Join-Path $DemoRuntime 'ca.cer') -CertStoreLocation Cert:\CurrentUser\Root | Out-Null
    }
    if (!$NoBuild) {
        Push-Location (Join-Path $DemoRoot 'frontend')
        try {
            if (!(Test-Path node_modules)) { Invoke-DemoCommand npm.cmd @('ci','--no-audit','--no-fund') }
            Invoke-DemoCommand npm.cmd @('run','build')
        } finally { Pop-Location }
        Push-Location $DemoRoot
        try {
            Invoke-DemoCommand (Join-Path $DemoRoot 'mvnw.cmd') @('-B','package','-DskipTests',
                '-Dfrontend.build.skip=true','-Dcheckstyle.skip=true','-Dspotbugs.skip=true','-Djacoco.skip=true')
        } finally { Pop-Location }
        $jar = Get-ChildItem (Join-Path $DemoRoot 'target') -Filter 'ai-gateway-standalone-*.jar' |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (!$jar) { throw 'No built gateway JAR found.' }
        Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $DemoRuntime 'gateway.jar')
    }
    if (!(Test-Path (Join-Path $DemoRuntime 'gateway.jar'))) { throw 'Run start.ps1 without -NoBuild once first.' }
    $started = $true
    Invoke-DemoCompose @('up','-d','--wait','--wait-timeout','90')
    $mockPath = Join-Path $PSScriptRoot 'mock-cas.mjs'
    $mock = Start-DemoProcess $node @($mockPath) 'mock-cas' $mockPath
    $appJar = Join-Path $DemoRuntime 'gateway.jar'
    $settings = (Join-Path $PSScriptRoot 'application-local-cas-demo.yml').Replace('\','/')
    $app = Start-DemoProcess (Join-Path $javaHome 'bin/java.exe') @(
        '-Xms256m','-Xmx1536m',"-Djavax.net.ssl.trustStore=$(Join-Path $DemoRuntime 'trust.p12')",
        "-Djavax.net.ssl.trustStorePassword=$($values.DEMO_TLS_PASSWORD)",'-Djavax.net.ssl.trustStoreType=PKCS12',
        '-jar',$appJar,'--spring.profiles.active=standalone',"--spring.config.additional-location=file:$settings"
    ) 'gateway' $appJar
    $deadline = (Get-Date).AddSeconds(180)
    $ready = $false
    do {
        if ($app.HasExited -or $mock.HasExited) { throw 'Demo process exited. Inspect .local-cas-demo logs.' }
        try {
            $health = Invoke-RestMethod 'https://localhost:39444/actuator/health' -TimeoutSec 3
            $portal = Invoke-RestMethod 'https://localhost:39443/health' -TimeoutSec 3
            $ready = $health.status -eq 'UP' -and $portal.status -eq 'UP'
        } catch { }
        if (!$ready) { Start-Sleep -Seconds 2 }
    } while (!$ready -and (Get-Date) -lt $deadline)
    if (!$ready) { throw 'Demo did not become healthy within 180 seconds. Inspect local demo logs.' }
    $sql = "INSERT INTO sldd_system_users(id,user_id,username,user_type,verify_status) VALUES (990001,'MOCK_T1001','Mock Teacher',2,2) ON CONFLICT (id) DO UPDATE SET user_id=EXCLUDED.user_id, username=EXCLUDED.username,user_type=2,verify_status=2;"
    Invoke-DemoCompose @('exec','-T','postgres','psql','-U','cas_demo','-d','cas_demo','-v','ON_ERROR_STOP=1','-c',$sql)
    Write-Host 'Portal: https://localhost:39443'
    Write-Host 'Gateway: https://localhost:39444/admin/'
    Write-Host 'Select the simulated logged-in teacher scenario, then enter AI Gateway.'
} catch {
    if ($started) {
        Stop-DemoProcesses
        Invoke-DemoCompose @('stop')
    }
    throw
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
}
