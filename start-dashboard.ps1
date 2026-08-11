# Start the understand dashboard with GRAPH_DIR set via PowerShell env
$env:GRAPH_DIR = 'E:\workspace\BlueSky\bluesky-master'
Set-Location 'C:\Users\LUO Lin\.opencode\understand-anything\understand-anything-plugin\packages\dashboard'
Write-Host "GRAPH_DIR=$env:GRAPH_DIR"
npx vite --host 127.0.0.1
