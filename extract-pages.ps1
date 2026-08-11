# Find all wiki-related links in the pages HTML
$c = Get-Content 'E:\workspace\BlueSky\pages.json' -Raw
# Try several link patterns
$patterns = @(
    'href="([^"]*wiki[^"]*)"',
    'href="([^"]*_pages[^"]*)"',
    '"name":"([^"]+)"'
)
foreach ($p in $patterns) {
    $m = [regex]::Matches($c, $p)
    Write-Host ("Pattern: " + $p + " => " + $m.Count + " matches")
    $uniq = $m | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    $uniq | Select-Object -First 30 | ForEach-Object { Write-Host "   " + $_ }
    Write-Host ""
}
# Also look for the 'Pages 152' section context
$idx = $c.IndexOf('Pages')
if ($idx -ge 0) { Write-Host "Context around 'Pages':" ; Write-Host $c.Substring($idx, 1500) }
