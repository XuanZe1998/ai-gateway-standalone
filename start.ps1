# 文件说明：start.ps1：项目自动化脚本；按脚本参数执行对应任务。
[CmdletBinding()]
param(
    [switch]$NoBuild,
    [ValidateRange(30, 900)]
    [int]$TimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location -LiteralPath $projectRoot

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw '未检测到 Docker。请先安装并启动 Docker Desktop。'
    }

    & docker compose version | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw '当前 Docker 不支持 docker compose 命令。'
    }

    & docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Engine 未运行。请先启动 Docker Desktop。'
    }

    if (-not (Test-Path -LiteralPath '.env')) {
        Copy-Item -LiteralPath '.env.example' -Destination '.env'
        Write-Warning '已从 .env.example 创建 .env。对外部署前请修改其中的密码和密钥。'
    }

    if (-not $NoBuild) {
        $jar = Get-ChildItem -LiteralPath '.\target' -Filter 'ai-gateway-standalone-*.jar' -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1

        $latestInput = @(
            Get-Item -LiteralPath '.\pom.xml'
            Get-ChildItem -LiteralPath '.\src\main' -Recurse -File -ErrorAction SilentlyContinue
        ) | Sort-Object LastWriteTime -Descending | Select-Object -First 1

        if (-not $jar -or ($latestInput -and $latestInput.LastWriteTime -gt $jar.LastWriteTime)) {
            if (-not $env:JAVA_HOME) {
                $javaCommand = Get-Command java -ErrorAction SilentlyContinue
                if (-not $javaCommand) {
                    throw '需要重新构建 JAR，但未检测到 Java 17。请先安装 JDK 17。'
                }

                $javaSettings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
                if ($javaSettings -match '(?m)^\s*java\.home\s*=\s*(.+?)\s*$') {
                    $env:JAVA_HOME = $Matches[1].Trim()
                    Write-Host "已自动设置 JAVA_HOME=$env:JAVA_HOME"
                } else {
                    throw '检测到 java 命令，但无法确定 JAVA_HOME。请手工设置 JAVA_HOME。'
                }
            }

            Write-Host '正在构建网关 JAR...'
            $mavenArgs = @(
                '-B',
                'package',
                '-DskipTests',
                '-Dcheckstyle.skip=true',
                '-Dspotbugs.skip=true',
                '-Djacoco.skip=true',
                '-Dfrontend.build.skip=true'
            )
            & '.\mvnw.cmd' @mavenArgs
            if ($LASTEXITCODE -ne 0) {
                throw "Maven 构建失败，退出码：$LASTEXITCODE"
            }
        } else {
            Write-Host "使用已构建的 JAR：$($jar.Name)"
        }
    }

    $composeArgs = @('compose', 'up', '-d')
    if (-not $NoBuild) {
        $composeArgs += '--build'
    }

    Write-Host '正在启动 PostgreSQL 和 AI 网关...'
    & docker @composeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose 启动失败，退出码：$LASTEXITCODE"
    }

    $healthUrl = 'http://localhost:38008/actuator/health'
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = $null

    Write-Host "等待健康检查（最长 $TimeoutSeconds 秒）..."
    do {
        try {
            $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 5
            if ($health.status -eq 'UP') {
                Write-Host ''
                & docker compose ps
                Write-Host ''
                Write-Host 'AI 网关启动成功：'
                Write-Host '  管理后台: http://localhost:38008/admin/'
                Write-Host '  Swagger : http://localhost:38008/swagger-ui/index.html'
                Write-Host "  健康检查: $healthUrl"
                return
            }
            $lastError = "健康状态为 $($health.status)"
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)

    Write-Host ''
    Write-Host '启动超时，容器状态：' -ForegroundColor Red
    & docker compose ps -a
    Write-Host ''
    Write-Host '最近日志：' -ForegroundColor Red
    & docker compose logs --tail 200 gateway postgres
    throw "网关未在 $TimeoutSeconds 秒内通过健康检查。最后错误：$lastError"
} finally {
    Pop-Location
}


