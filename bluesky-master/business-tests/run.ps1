param(
    [string]$Config = "$PSScriptRoot/config.local.json",
    [string]$Scenarios = "AT-01,AT-02,AT-03,AT-04,AT-05,AT-06,AT-07,AT-08,AT-09,AT-10,AT-11",
    [string]$Output = ""
)

$ErrorActionPreference = "Stop"
$runner = Join-Path $PSScriptRoot "acceptance_runner.py"
$arguments = @($runner, "--config", $Config, "--scenarios", $Scenarios)
if ($Output) {
    $arguments += @("--output", $Output)
}

& python @arguments
exit $LASTEXITCODE
