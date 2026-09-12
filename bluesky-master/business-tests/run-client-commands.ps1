param(
    [string]$Output = "$PSScriptRoot/artifacts/client-command-evidence.json"
)

$ErrorActionPreference = "Stop"
$frontend = Join-Path $PSScriptRoot "../training-platform/frontend"
$env:BS_CLIENT_EVIDENCE_PATH = [System.IO.Path]::GetFullPath($Output)
Push-Location $frontend
try {
    & npm run test:business
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
