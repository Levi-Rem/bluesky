# Download the complete BlueSky wiki as raw markdown files
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# 1. Extract all page names from the HTML page list
$c = Get-Content 'E:\workspace\BlueSky\pages.json' -Raw
$m = [regex]::Matches($c, 'href="/TUDelft-CNS-ATM/bluesky/wiki/([^"/]+)"')
$names = $m | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique | Where-Object { $_ -ne '_new' -and $_ -ne '' }
Write-Host ("Pages to download: " + $names.Count)

# Save the page list
$names | Out-File 'E:\workspace\BlueSky\wiki-page-names.txt' -Encoding utf8

# 2. Download each page as raw markdown
New-Item -ItemType Directory -Force -Path 'E:\workspace\BlueSky\wiki' | Out-Null
$ok = 0; $fail = @()
foreach ($n in $names) {
    $url = 'https://raw.githubusercontent.com/wiki/TUDelft-CNS-ATM/BlueSky/' + $n + '.md'
    $out = 'E:\workspace\BlueSky\wiki\' + $n + '.md'
    $success = $false
    for ($try = 1; $try -le 4 -and -not $success; $try++) {
        try {
            Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing -TimeoutSec 90
            $success = $true
        } catch {
            Start-Sleep -Milliseconds (800 * $try)
        }
    }
    if ($success) { $ok++ } else { $fail += $n; Write-Host ("FAIL: " + $n) }
}
Write-Host ("Downloaded OK: " + $ok)
Write-Host ("Failed: " + $fail.Count)
if ($fail.Count -gt 0) { $fail | Out-File 'E:\workspace\BlueSky\wiki-failed.txt' -Encoding utf8 }
