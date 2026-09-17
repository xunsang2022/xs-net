param(
    [string]$Protoc = $env:PROTOC
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$unifiedScript = Join-Path $repoRoot 'scripts\Generate-Sdks.ps1'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $unifiedScript -Only Cpp -Protoc $Protoc
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
