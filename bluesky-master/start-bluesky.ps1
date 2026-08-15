[CmdletBinding()]
param(
    [ValidateSet("gui", "headless", "client", "console", "sim", "detached")]
    [string]$Mode = "gui",

    [string]$Scenario,
    [string]$HostName,

    # Force dependency installation even when the current import check passes.
    [switch]$Setup,

    # Validate the environment without starting BlueSky or changing anything.
    [switch]$CheckOnly
)

$ErrorActionPreference = "Stop"
$scriptExitCode = 0
$locationPushed = $false

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE."
    }
}

function Find-SystemPython {
    $pyLauncher = Get-Command "py.exe" -ErrorAction SilentlyContinue
    if ($null -ne $pyLauncher) {
        & $pyLauncher.Source -3 -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)" 2>$null
        if ($LASTEXITCODE -eq 0) {
            return @{
                FilePath = $pyLauncher.Source
                Prefix   = @("-3")
            }
        }
    }

    $python = Get-Command "python.exe" -ErrorAction SilentlyContinue
    if ($null -ne $python) {
        & $python.Source -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)" 2>$null
        if ($LASTEXITCODE -eq 0) {
            return @{
                FilePath = $python.Source
                Prefix   = @()
            }
        }
    }

    return $null
}

function Test-PythonImports {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PythonPath,

        [Parameter(Mandatory = $true)]
        [string]$ImportCode
    )

    & $PythonPath -c $ImportCode *> $null
    return ($LASTEXITCODE -eq 0)
}

try {
    $projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
    $venvRoot = Join-Path $projectRoot ".venv"
    $venvPython = Join-Path $venvRoot "Scripts\python.exe"
    $entryPoint = Join-Path $projectRoot "BlueSky.py"

    Push-Location -LiteralPath $projectRoot
    $locationPushed = $true

    if (-not (Test-Path -LiteralPath $entryPoint -PathType Leaf)) {
        throw "BlueSky.py was not found in $projectRoot."
    }

    if (-not (Test-Path -LiteralPath $venvPython -PathType Leaf)) {
        if ($CheckOnly) {
            throw "The virtual environment does not exist: $venvRoot"
        }

        $systemPython = Find-SystemPython
        if ($null -eq $systemPython) {
            throw "Python 3.10 or newer was not found. Install Python 3, then run this launcher again."
        }

        Write-Host "[SETUP] Creating virtual environment: $venvRoot" -ForegroundColor Cyan
        $createArguments = @($systemPython.Prefix) + @("-m", "venv", $venvRoot)
        Invoke-NativeCommand -FilePath $systemPython.FilePath -Arguments $createArguments -Description "Virtual environment creation"
        $Setup = $true
    }

    & $venvPython -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "The project virtual environment must use Python 3.10 or newer. Recreate $venvRoot with a supported Python version."
    }

    $coreImports = "import bluesky, numpy, scipy, pandas, msgpack, zmq, openap"
    $installExtra = "headless"
    $requiredImports = $coreImports

    switch ($Mode) {
        { $_ -in @("gui", "client") } {
            $requiredImports += "; import PyQt6, OpenGL; from PyQt6 import QtWebEngineWidgets"
            $installExtra = "qt6"
            break
        }
        "console" {
            $requiredImports += "; import textual"
            $installExtra = "console"
            break
        }
    }

    $dependenciesReady = Test-PythonImports -PythonPath $venvPython -ImportCode $requiredImports
    if ($Setup -or -not $dependenciesReady) {
        if ($CheckOnly -and -not $Setup) {
            throw "Required dependencies for mode '$Mode' are missing. Run again without -CheckOnly to install them."
        }

        $installTarget = ".[${installExtra}]"
        Write-Host "[SETUP] Installing BlueSky dependencies: $installTarget" -ForegroundColor Cyan
        Invoke-NativeCommand -FilePath $venvPython -Arguments @("-m", "pip", "install", "--disable-pip-version-check", "-e", $installTarget) -Description "Dependency installation"

        if (-not (Test-PythonImports -PythonPath $venvPython -ImportCode $requiredImports)) {
            throw "Dependency verification still fails after installation. Review the pip output above."
        }
    }

    $pythonVersion = & $venvPython -c "import sys; print('.'.join(map(str, sys.version_info[:3])))"
    Write-Host "[OK] Environment ready (Python $pythonVersion, mode: $Mode)." -ForegroundColor Green

    if ($CheckOnly) {
        return
    }

    $blueSkyArguments = @($entryPoint)
    switch ($Mode) {
        "headless" { $blueSkyArguments += "--headless" }
        "client" {
            $blueSkyArguments += "--client"
            if (-not [string]::IsNullOrWhiteSpace($HostName)) {
                $blueSkyArguments += $HostName
            }
        }
        "console" {
            $blueSkyArguments += "--console"
            if (-not [string]::IsNullOrWhiteSpace($HostName)) {
                $blueSkyArguments += $HostName
            }
        }
        "sim" { $blueSkyArguments += "--sim" }
        "detached" { $blueSkyArguments += "--detached" }
    }

    if (-not [string]::IsNullOrWhiteSpace($Scenario)) {
        $blueSkyArguments += @("--scenfile", $Scenario)
    }

    Write-Host "[START] Launching BlueSky..." -ForegroundColor Cyan
    & $venvPython @blueSkyArguments
    $blueSkyExitCode = $LASTEXITCODE

    if ($blueSkyExitCode -ne 0) {
        throw "BlueSky exited with code $blueSkyExitCode."
    }

}
catch {
    Write-Host "[ERROR] $($_.Exception.Message)" -ForegroundColor Red
    $scriptExitCode = 1
}
finally {
    if ($locationPushed) {
        Pop-Location
    }
}

if ($scriptExitCode -ne 0) {
    exit $scriptExitCode
}
