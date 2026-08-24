# Serve the backend showcase (Windows).
# jwebserver ships with the JDK, so there is no build step and no Node.
param([int]$Port = 8090)
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
Write-Host "Backend showcase:  http://localhost:$Port"
Write-Host "Expects the payment orchestrator at http://localhost:8083"
jwebserver -p $Port -d $dir -b 127.0.0.1
