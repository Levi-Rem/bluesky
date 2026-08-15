[CmdletBinding()]
param(
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$frontendRoot = Join-Path $repositoryRoot 'training-platform\frontend'
$platformRoot = Join-Path $repositoryRoot 'training-platform'
$python = Join-Path $repositoryRoot '.venv\Scripts\python.exe'

function Invoke-Checked {
    param([string]$Name, [scriptblock]$Action)
    Write-Host "[$Name]" -ForegroundColor Cyan
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "$Name 失败，退出码 $LASTEXITCODE"
    }
}

if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw '未找到 Node.js。' }
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { throw '未找到 npm。' }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw '未找到 Maven。' }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw '未找到 Java。' }
if (-not (Test-Path -LiteralPath $python)) { throw "未找到 BlueSky Python 环境: $python" }

if (-not $SkipTests) {
    Push-Location $repositoryRoot
    try {
        Invoke-Checked 'Python Adapter 测试' {
            & $python -m unittest discover -s tests/training_adapter -p 'test_*.py'
        }
    }
    finally {
        Pop-Location
    }
}

Push-Location $frontendRoot
try {
    Invoke-Checked '安装前端依赖' { npm ci --ignore-scripts }
    if (-not $SkipTests) {
        Invoke-Checked '前端测试' { npm test }
    }
    Invoke-Checked '前端类型检查' { npm run typecheck }
    Invoke-Checked '前端生产构建' { npm run build }
}
finally {
    Pop-Location
}

Push-Location $platformRoot
try {
    $runningPidFile = Join-Path $platformRoot '.platform-processes.json'
    if (Test-Path -LiteralPath $runningPidFile) {
        throw '检测到本项目平台仍在运行；请先执行 .\stop-platform.ps1，再重新构建。'
    }
    $mavenArguments = @('clean', 'package')
    if ($SkipTests) { $mavenArguments += '-DskipTests' }
    Invoke-Checked 'Java 构建' { mvn @mavenArguments }
}
finally {
    Pop-Location
}

Write-Host "构建完成: $platformRoot\target\training-platform.jar" -ForegroundColor Green
