[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidFile = Join-Path $repositoryRoot 'training-platform\.platform-processes.json'
$targetProcessIds = [System.Collections.Generic.HashSet[int]]::new()
if (Test-Path -LiteralPath $pidFile) {
    $processes = Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
    foreach ($processId in @($processes.adapterPid, $processes.serverPid)) {
        if ($processId) { [void]$targetProcessIds.Add([int]$processId) }
    }
}

$jarPath = Join-Path $repositoryRoot 'training-platform\target\training-platform.jar'
$escapedRoot = [Regex]::Escape($repositoryRoot)
$escapedJar = [Regex]::Escape($jarPath)
foreach ($process in Get-CimInstance Win32_Process -ErrorAction SilentlyContinue) {
    $commandLine = [string]$process.CommandLine
    $isPlatformServer = $process.Name -eq 'java.exe' -and $commandLine -match $escapedJar
    $isPlatformAdapter = $process.Name -eq 'python.exe' `
        -and $commandLine -match 'bluesky\.plugins\.training_adapter\.runner' `
        -and $commandLine -match $escapedRoot
    if ($isPlatformServer -or $isPlatformAdapter) {
        [void]$targetProcessIds.Add([int]$process.ProcessId)
    }
}

foreach ($processId in $targetProcessIds) {
    Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
}
if (Test-Path -LiteralPath $pidFile) { Remove-Item -LiteralPath $pidFile }
if ($targetProcessIds.Count -eq 0) {
    Write-Host '没有找到本项目的仿真平台进程。'
} else {
    Write-Host "已停止仿真平台进程: $($targetProcessIds -join ', ')" -ForegroundColor Green
}
