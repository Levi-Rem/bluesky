param(
    [string]$Config = "$PSScriptRoot/instruction-config.local.json",
    [string]$Commands = "all",
    [string]$Output = ""
)

$ErrorActionPreference = "Stop"
$runner = Join-Path $PSScriptRoot "instruction_acceptance_runner.py"
$arguments = @($runner, "--config", $Config, "--commands", $Commands)
if ($Output) {
    $arguments += @("--output", $Output)
}

& python @arguments
exit $LASTEXITCODE
