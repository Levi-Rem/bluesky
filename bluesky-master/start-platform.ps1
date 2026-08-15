[CmdletBinding()]
param(
    [switch]$NoBuild,
    [switch]$NoBrowser,
    [switch]$DemoDatabase
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$platformRoot = Join-Path $repositoryRoot 'training-platform'
$adapterOutputLog = Join-Path $platformRoot 'adapter.out.log'
$adapterErrorLog = Join-Path $platformRoot 'adapter.err.log'
$serverOutputLog = Join-Path $platformRoot 'server.out.log'
$serverErrorLog = Join-Path $platformRoot 'server.err.log'
$pidFile = Join-Path $platformRoot '.platform-processes.json'
$startedProcesses = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()

function Import-LocalEnvironment {
    $environmentFile = Join-Path $repositoryRoot '.env.local'
    if (-not (Test-Path -LiteralPath $environmentFile)) { return }
    foreach ($line in Get-Content -LiteralPath $environmentFile) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $parts = $trimmed.Split('=', 2)
        if ($parts.Count -ne 2) { throw ".env.local 中存在无效配置行: $trimmed" }
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim().Trim('"'), 'Process')
    }
}

function Require-Command([string]$Name, [string]$Message) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) { throw $Message }
}

function Test-PortAvailable([int]$Port) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    try { $listener.Start(); return $true } catch { return $false } finally { $listener.Stop() }
}

function Get-TcpEndpoint([string]$Endpoint, [string]$Name) {
    try { $uri = [Uri]$Endpoint } catch { throw "$Name 不是合法端点: $Endpoint" }
    if ($uri.Scheme -ne 'tcp' -or $uri.Port -le 0) {
        throw "$Name 必须使用 tcp://主机:端口 格式: $Endpoint"
    }
    return $uri
}

function Test-TcpConnection([string]$HostName, [int]$Port, [int]$TimeoutMilliseconds = 2000) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne($TimeoutMilliseconds)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch { return $false } finally { $client.Dispose() }
}

function Wait-Http([string]$Url, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    throw "服务未在 $TimeoutSeconds 秒内就绪: $Url"
}

function Stop-StartedProcesses {
    foreach ($process in $startedProcesses) {
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

try {
    Import-LocalEnvironment
    Require-Command java '未找到 Java；需要 JDK 8 或更高版本运行 Java 8 目标程序。'
    Require-Command mvn '未找到 Maven。'

    $python = Join-Path $repositoryRoot '.venv\Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $python)) { throw "未找到 BlueSky Python 环境: $python" }

    $platformPort = if ($env:BS_PLATFORM_PORT) { [int]$env:BS_PLATFORM_PORT } else { 8080 }
    $controlEndpoint = if ($env:BS_ADAPTER_CONTROL_ENDPOINT) { $env:BS_ADAPTER_CONTROL_ENDPOINT } else { 'tcp://127.0.0.1:5555' }
    $stateEndpoint = if ($env:BS_ADAPTER_STATE_ENDPOINT) { $env:BS_ADAPTER_STATE_ENDPOINT } else { 'tcp://127.0.0.1:5556' }
    $controlUri = Get-TcpEndpoint $controlEndpoint 'BS_ADAPTER_CONTROL_ENDPOINT'
    $stateUri = Get-TcpEndpoint $stateEndpoint 'BS_ADAPTER_STATE_ENDPOINT'
    $controlPort = $controlUri.Port
    $statePort = $stateUri.Port
    if (-not (Test-PortAvailable $platformPort)) { throw "端口 $platformPort 已占用。" }
    if (-not (Test-PortAvailable $controlPort)) { throw "端口 $controlPort 已占用。" }
    if (-not (Test-PortAvailable $statePort)) { throw "端口 $statePort 已占用。" }
    if (Test-Path -LiteralPath $pidFile) {
        throw '检测到本项目平台进程记录；请先执行 .\stop-platform.ps1。'
    }

    if (-not $DemoDatabase) {
        foreach ($name in @('BS_MYSQL_USERNAME', 'BS_MYSQL_PASSWORD')) {
            if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) {
                throw "缺少 $name。请设置环境变量或在不入库的 .env.local 中配置；本机演示可显式使用 -DemoDatabase。"
            }
        }
        $mysqlHost = if ($env:BS_MYSQL_HOST) { $env:BS_MYSQL_HOST } else { '127.0.0.1' }
        $mysqlPort = if ($env:BS_MYSQL_PORT) { [int]$env:BS_MYSQL_PORT } else { 3306 }
        if (-not (Test-TcpConnection $mysqlHost $mysqlPort)) {
            throw "MySQL 不可达: ${mysqlHost}:$mysqlPort。请启动 MySQL 并检查 BS_MYSQL_HOST/BS_MYSQL_PORT。"
        }
    }

    if (-not $NoBuild) {
        & (Join-Path $repositoryRoot 'build-platform.ps1')
        if ($LASTEXITCODE -ne 0) { throw '统一构建失败。' }
    }

    $jar = Join-Path $platformRoot 'target\training-platform.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw "未找到构建产物: $jar" }

    $adapterArguments = @(
        '-m', 'bluesky.plugins.training_adapter.runner',
        '--control-endpoint', $controlEndpoint,
        '--state-endpoint', $stateEndpoint,
        '--workdir', $repositoryRoot
    )
    $adapter = Start-Process -FilePath $python -ArgumentList $adapterArguments -WorkingDirectory $repositoryRoot `
        -RedirectStandardOutput $adapterOutputLog -RedirectStandardError $adapterErrorLog -WindowStyle Hidden -PassThru
    $startedProcesses.Add($adapter)

    $javaArguments = @('-jar', $jar)
    if ($DemoDatabase) {
        $javaArguments += '--spring.profiles.active=demo'
    }
    $server = Start-Process -FilePath 'java' -ArgumentList $javaArguments -WorkingDirectory $platformRoot `
        -RedirectStandardOutput $serverOutputLog -RedirectStandardError $serverErrorLog -WindowStyle Hidden -PassThru
    $startedProcesses.Add($server)

    Wait-Http "http://127.0.0.1:$platformPort/actuator/health" 45
    Wait-Http "http://127.0.0.1:$platformPort/api/v1/workstation/bootstrap" 15

    @{
        adapterPid = $adapter.Id
        serverPid = $server.Id
        startedAt = [DateTime]::UtcNow.ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding UTF8

    $url = "http://127.0.0.1:$platformPort/"
    Write-Host "仿真平台已启动: $url" -ForegroundColor Green
    Write-Host "Adapter PID: $($adapter.Id); Java PID: $($server.Id)"
    if (-not $NoBrowser) { Start-Process $url }
}
catch {
    Stop-StartedProcesses
    Write-Error $_
    exit 1
}
