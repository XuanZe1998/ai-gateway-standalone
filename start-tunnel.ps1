# 文件说明：start-tunnel.ps1：项目自动化脚本；按脚本参数执行对应任务。
param([switch]$ConnectorOnly)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $projectRoot

$envPath = Join-Path $projectRoot '.env'
$tokenPath = Join-Path $projectRoot '.cloudflare-tunnel-token'
if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    throw 'Missing .env; copy .env.example and configure credentials first.'
}
if (-not (Test-Path -LiteralPath $tokenPath -PathType Leaf) -or
    (Get-Item -LiteralPath $tokenPath).Length -eq 0) {
    throw 'Missing Cloudflare Tunnel token file: .cloudflare-tunnel-token'
}

$settings = @{}
foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -match '^\s*([^#\s][^=]*)=(.*)$') {
        $settings[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$publicBaseUrl = [string]$settings['CAMPUS_PUBLIC_BASE_URL']
$parsedPublicBaseUrl = $null
if (-not [System.Uri]::TryCreate($publicBaseUrl, [System.UriKind]::Absolute, [ref]$parsedPublicBaseUrl) -or
    $parsedPublicBaseUrl.Scheme -ne 'https' -or
    $parsedPublicBaseUrl.AbsolutePath -ne '/' -or
    $parsedPublicBaseUrl.Query -ne '' -or
    $parsedPublicBaseUrl.Fragment -ne '') {
    throw 'Set CAMPUS_PUBLIC_BASE_URL in .env to the public HTTPS origin, for example https://gordanshop.com.'
}
if ([string]$settings['CAMPUS_SESSION_COOKIE_SECURE'] -ne 'true') {
    throw 'Set CAMPUS_SESSION_COOKIE_SECURE=true in .env for the public HTTPS Tunnel.'
}

foreach ($rule in @(
    @{ Name = 'PG_PASSWORD'; MinLength = 16 },
    @{ Name = 'JWT_SECRET'; MinLength = 32 },
    @{ Name = 'INITIAL_ADMIN_PASSWORD'; MinLength = 16 },
    @{ Name = 'GATEWAY_API_KEY'; MinLength = 24 }
)) {
    $value = [string]$settings[$rule.Name]
    if ($value.Length -lt $rule.MinLength -or
        $value -match '(?i)change.this|local.development|^123456$|^admin123$') {
        throw "Set a strong $($rule.Name) in .env before exposing the gateway."
    }
}

if ($ConnectorOnly) {
    docker compose -f docker-compose.yml -f docker-compose.tunnel.yml up -d --no-deps tunnel-proxy cloudflared
} else {
    docker compose -f docker-compose.yml -f docker-compose.tunnel.yml up -d --build
}
if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose failed with exit code $LASTEXITCODE."
}
