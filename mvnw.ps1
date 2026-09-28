# Compatibility shim for callers that still invoke mvnw.ps1 directly.
$wrapper = Join-Path $PSScriptRoot 'mvnw.cmd'
& $wrapper @args
exit $LASTEXITCODE
