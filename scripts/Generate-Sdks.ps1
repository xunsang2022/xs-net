param(
    [ValidateSet('All', 'Unity', 'TypeScript', 'Cocos', 'Cpp')]
    [string]$Only = 'All',
    [string]$Protoc = $env:PROTOC,
    [string]$TsProtoPlugin = $env:TS_PROTO_PLUGIN,
    [string]$OutputRoot = 'F:\project\xs-net\xs-net\xs-net-sdk'
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$mavenProject = Join-Path $repoRoot 'xs-net\pom.xml'
$sdkModule = Join-Path $repoRoot 'xs-net\xs-net-sdk'
$canonicalProto = Join-Path $sdkModule 'target\generated-proto'
$frameworkProto = Join-Path $repoRoot 'xs-net\xs-net-message\src\main\proto'
$templateRoot = Join-Path $sdkModule 'src\main\sdk'
$codeRoot = if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    Join-Path $sdkModule 'target\code'
} else {
    [System.IO.Path]::GetFullPath($OutputRoot)
}
$unityTemplate = Join-Path $templateRoot 'unity'
$typescriptTemplate = Join-Path $templateRoot 'typescript'
$cocosTemplate = Join-Path $templateRoot 'cocos'
$cppTemplate = Join-Path $templateRoot 'cpp'
$unityRoot = Join-Path $codeRoot 'unity'
$typescriptRoot = Join-Path $codeRoot 'typescript'
$cocosRoot = Join-Path $codeRoot 'cocos'
$cppRoot = Join-Path $codeRoot 'cpp'

$selected = switch ($Only) {
    'All' { @('Unity', 'TypeScript', 'Cocos', 'Cpp') }
    default { @($Only) }
}

function Resolve-Protoc {
    if (-not [string]::IsNullOrWhiteSpace($Protoc)) {
        if (-not (Test-Path -LiteralPath $Protoc -PathType Leaf)) {
            throw "protoc executable was not found: $Protoc"
        }
        return (Resolve-Path -LiteralPath $Protoc).Path
    }

    $defaultProtoc = Join-Path $env:USERPROFILE '.m2\repository\com\google\protobuf\protoc\3.25.5\protoc-3.25.5-windows-x86_64.exe'
    if (Test-Path -LiteralPath $defaultProtoc -PathType Leaf) {
        return (Resolve-Path -LiteralPath $defaultProtoc).Path
    }

    $command = Get-Command protoc -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw 'protoc was not found. Set PROTOC or pass -Protoc <path>.'
}

function Resolve-TsProtoPlugin {
    if (-not [string]::IsNullOrWhiteSpace($TsProtoPlugin)) {
        if (-not (Test-Path -LiteralPath $TsProtoPlugin -PathType Leaf)) {
            throw "ts-proto plugin was not found: $TsProtoPlugin"
        }
        return (Resolve-Path -LiteralPath $TsProtoPlugin).Path
    }

    $pluginCandidates = @(
        (Join-Path $typescriptTemplate 'node_modules\.bin\protoc-gen-ts_proto.cmd'),
        (Join-Path $typescriptRoot 'node_modules\.bin\protoc-gen-ts_proto.cmd')
    )
    foreach ($candidate in $pluginCandidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw 'protoc-gen-ts_proto was not found. Run npm install in xs-net/xs-net-sdk/src/main/sdk/typescript or pass -TsProtoPlugin <path>.'
}

function Invoke-LoggedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$Name,
        [string]$WorkingDirectory = $repoRoot
    )

    $logRoot = Join-Path $repoRoot '.log'
    New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $stdout = Join-Path $logRoot "sdk-$Name-$stamp.log"
    $stderr = Join-Path $logRoot "sdk-$Name-$stamp.err.log"

    Write-Host "[sdk] $Name"
    $process = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -Wait -WindowStyle Hidden
    if ($process.ExitCode -ne 0) {
        throw "$Name failed with exit code $($process.ExitCode). See $stdout and $stderr"
    }
}

function Copy-ProtoSet {
    param(
        [Parameter(Mandatory = $true)][string]$Target,
        [string[]]$Exclude = @()
    )

    New-Item -ItemType Directory -Force -Path $Target | Out-Null
    Get-ChildItem -LiteralPath $Target -Filter '*.proto' -File -ErrorAction SilentlyContinue | Remove-Item -Force
    Get-ChildItem -LiteralPath $canonicalProto -Filter '*.proto' -File |
        Where-Object { $Exclude -notcontains $_.Name } |
        Sort-Object Name | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $Target $_.Name) -Force
    }
}

function Reset-SdkOutput {
    param(
        [Parameter(Mandatory = $true)][string]$Template,
        [Parameter(Mandatory = $true)][string]$Output
    )

    if (-not (Test-Path -LiteralPath $Template -PathType Container)) {
        throw "SDK template directory was not found: $Template"
    }
    if (Test-Path -LiteralPath $Output -PathType Container) {
        [System.IO.Directory]::Delete((Resolve-Path -LiteralPath $Output).Path, $true)
    }
    New-Item -ItemType Directory -Force -Path $Output | Out-Null
    Get-ChildItem -LiteralPath $Template -Force |
        Where-Object { $_.Name -ne 'node_modules' } |
        ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $Output $_.Name) -Recurse -Force
    }
}

function Get-ProtoFiles {
    $files = @(Get-ChildItem -LiteralPath $canonicalProto -Filter '*.proto' -File | Sort-Object Name)
    if ($files.Count -eq 0) {
        throw "No canonical proto files were generated: $canonicalProto"
    }
    return $files
}

function Merge-FrameworkProto {
    if (-not (Test-Path -LiteralPath $frameworkProto -PathType Container)) {
        throw "Framework proto directory was not found: $frameworkProto"
    }

    Get-ChildItem -LiteralPath $frameworkProto -Filter '*.proto' -File | Sort-Object Name | ForEach-Object {
        $target = Join-Path $canonicalProto $_.Name
        if (Test-Path -LiteralPath $target -PathType Leaf) {
            $sourceHash = (Get-FileHash -Algorithm SHA256 $_.FullName).Hash
            $targetHash = (Get-FileHash -Algorithm SHA256 $target).Hash
            if ($sourceHash -ne $targetHash) {
                throw "Framework proto conflicts with generated proto: $($_.Name)"
            }
            return
        }
        Copy-Item -LiteralPath $_.FullName -Destination $target
    }
}

function Clear-GeneratedFiles {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string[]]$Extensions
    )

    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    Get-ChildItem -LiteralPath $Directory -File -ErrorAction SilentlyContinue |
        Where-Object { $Extensions -contains $_.Extension } |
        Remove-Item -Force
}

function Copy-CocosTypeScriptRuntime {
    param(
        [Parameter(Mandatory = $true)][string]$Output
    )

    $source = Join-Path $typescriptTemplate 'src'
    $target = Join-Path $Output 'src'
    Get-ChildItem -LiteralPath $source -Filter '*.ts' -File |
        Where-Object { $_.Name -notin @('index.ts', 'node-transport.ts', 'kcp-udp-transport.ts') } |
        ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $target $_.Name) -Force
    }
}

function Convert-CocosLocalImports {
    param(
        [Parameter(Mandatory = $true)][string]$Output
    )

    Get-ChildItem -LiteralPath $Output -Filter '*.ts' -File -Recurse | ForEach-Object {
        $content = [System.IO.File]::ReadAllText($_.FullName)
        $converted = $content.Replace('.js"', '"').Replace(".js'", "'")
        if ($converted -ne $content) {
            [System.IO.File]::WriteAllText($_.FullName, $converted, [System.Text.UTF8Encoding]::new($false))
        }
    }
}

function Generate-TypeScriptSdk {
    param(
        [Parameter(Mandatory = $true)][string]$Template,
        [Parameter(Mandatory = $true)][string]$Output,
        [Parameter(Mandatory = $true)][string]$Name,
        [switch]$Cocos
    )

    Reset-SdkOutput -Template $Template -Output $Output
    if ($Cocos) {
        Copy-CocosTypeScriptRuntime -Output $Output
    }

    $typescriptProto = Join-Path $Output 'proto'
    $typescriptGenerated = Join-Path $Output 'generated'
    Copy-ProtoSet -Target $typescriptProto
    Clear-GeneratedFiles -Directory $typescriptGenerated -Extensions @('.ts')
    $plugin = Resolve-TsProtoPlugin
    $arguments = @(
        "--proto_path=$canonicalProto",
        "--plugin=protoc-gen-ts_proto=$plugin",
        "--ts_proto_out=$typescriptGenerated",
        '--ts_proto_opt=esModuleInterop=true,forceLong=number,useOptionals=messages,outputServices=generic-definitions'
    ) + @($protoFiles | ForEach-Object { $_.FullName })
    Invoke-LoggedProcess -FilePath $protoc -ArgumentList $arguments -Name $Name -WorkingDirectory $canonicalProto
    if ($Cocos) {
        Convert-CocosLocalImports -Output $Output
    }
}

$protoc = Resolve-Protoc

$mavenArgs = @('-f', $mavenProject, '-pl', 'xs-net-sdk', '-am', 'generate-sources', '-DskipTests')
Invoke-LoggedProcess -FilePath (Join-Path $repoRoot 'mvnw.cmd') -ArgumentList $mavenArgs -Name 'proto' -WorkingDirectory $repoRoot

Merge-FrameworkProto
$protoFiles = Get-ProtoFiles

if ($selected -contains 'Unity') {
    Reset-SdkOutput -Template $unityTemplate -Output $unityRoot
    $unityRuntime = Join-Path $unityRoot 'Runtime'
    $unityProtoFiles = @($protoFiles | Where-Object { $_.Name -ne 'net_message.proto' })
    Copy-ProtoSet -Target (Join-Path $unityRoot 'proto') -Exclude @('net_message.proto')
    Clear-GeneratedFiles -Directory $unityRuntime -Extensions @('.cs')
    $arguments = @(
        "--proto_path=$canonicalProto",
        "--csharp_out=$unityRuntime",
        '--csharp_opt=base_namespace=Game.Message'
    ) + @($unityProtoFiles | ForEach-Object { $_.FullName })
    Invoke-LoggedProcess -FilePath $protoc -ArgumentList $arguments -Name 'unity' -WorkingDirectory $canonicalProto
}

if ($selected -contains 'TypeScript') {
    Generate-TypeScriptSdk -Template $typescriptTemplate -Output $typescriptRoot -Name 'typescript'
}

if ($selected -contains 'Cocos') {
    Generate-TypeScriptSdk -Template $cocosTemplate -Output $cocosRoot -Name 'cocos' -Cocos
}

if ($selected -contains 'Cpp') {
    Reset-SdkOutput -Template $cppTemplate -Output $cppRoot
    $cppProto = Join-Path $cppRoot 'proto'
    $cppGenerated = Join-Path $cppRoot 'generated'
    Copy-ProtoSet -Target $cppProto
    Clear-GeneratedFiles -Directory $cppGenerated -Extensions @('.h', '.cc')
    $arguments = @(
        "--proto_path=$canonicalProto",
        "--cpp_out=$cppGenerated"
    ) + @($protoFiles | ForEach-Object { $_.FullName })
    Invoke-LoggedProcess -FilePath $protoc -ArgumentList $arguments -Name 'cpp' -WorkingDirectory $canonicalProto
}

Write-Host "[sdk] completed: $($selected -join ', ')"
