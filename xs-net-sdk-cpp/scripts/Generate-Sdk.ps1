param(
    [string]$Protoc = $env:PROTOC
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Protoc)) {
    $Protoc = Join-Path $env:USERPROFILE '.m2\repository\com\google\protobuf\protoc\3.25.5\protoc-3.25.5-windows-x86_64.exe'
}

$sdkRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $sdkRoot
$protoDirectory = Join-Path $sdkRoot 'proto'
$generatedDirectory = Join-Path $sdkRoot 'generated'
$sources = @(
    (Join-Path $repoRoot 'game-server\game-message-action\src\main\proto'),
    (Join-Path $repoRoot 'xs-net\xs-net-message\src\main\proto')
)

New-Item -ItemType Directory -Force -Path $protoDirectory, $generatedDirectory | Out-Null
Get-ChildItem -Path $protoDirectory -Filter '*.proto' -File | Remove-Item
Get-ChildItem -Path $generatedDirectory -Include '*.h', '*.cc' -File -Recurse | Remove-Item
foreach ($source in $sources) {
    Get-ChildItem -Path $source -Filter '*.proto' -File | Copy-Item -Destination $protoDirectory
}

$protoFiles = Get-ChildItem -Path $protoDirectory -Filter '*.proto' -File | Sort-Object Name
if ($protoFiles.Count -eq 0) {
    throw 'No protocol files were copied.'
}
& $Protoc "--proto_path=$protoDirectory" "--cpp_out=$generatedDirectory" $protoFiles.FullName
if ($LASTEXITCODE -ne 0) {
    throw "protoc exited with code $LASTEXITCODE"
}
