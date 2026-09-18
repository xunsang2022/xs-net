param(
    [ValidateSet('All', 'Unity', 'TypeScript', 'Cocos', 'Cpp')]
    [string]$Only = 'All',
    [string]$Protoc = $env:PROTOC,
    [string]$TsProtoPlugin = $env:TS_PROTO_PLUGIN,
    [string]$OutputRoot = 'F:\project\sdk-generated',
    [string]$CocosProjectRoot = 'F:\project\sdkTest'
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

function Convert-ProtoFieldName {
    param(
        [Parameter(Mandatory = $true)][string]$Name
    )

    $parts = @($Name -split '_' | Where-Object { $_ })
    if ($parts.Count -le 1) {
        return $Name
    }

    $result = $parts[0]
    foreach ($part in $parts[1..($parts.Count - 1)]) {
        $result += $part.Substring(0, 1).ToUpperInvariant() + $part.Substring(1).ToLowerInvariant()
    }
    return $result
}

function Convert-ProtoFieldType {
    param(
        [Parameter(Mandatory = $true)][string]$Type
    )

    switch ($Type.ToLowerInvariant()) {
        'string' { return 'string' }
        'bool' { return 'bool' }
        'bytes' { return 'bytes' }
        'double' { return 'number' }
        'float' { return 'number' }
        'int32' { return 'number' }
        'int64' { return 'number' }
        'sint32' { return 'number' }
        'sint64' { return 'number' }
        'uint32' { return 'number' }
        'uint64' { return 'number' }
        'fixed32' { return 'number' }
        'fixed64' { return 'number' }
        'sfixed32' { return 'number' }
        'sfixed64' { return 'number' }
        default { throw "Unsupported Cocos test panel proto field type: $Type" }
    }
}

function Normalize-ProtoIdentifier {
    param(
        [Parameter(Mandatory = $true)][string]$Value
    )

    return [regex]::Replace($Value.ToLowerInvariant(), '[^a-z0-9]', '')
}

function Get-CocosRpcMetadata {
    param(
        [Parameter(Mandatory = $true)][string]$ProtoDirectory,
        [Parameter(Mandatory = $true)][string]$GeneratedDirectory
    )

    $messages = @{}
    foreach ($protoFile in Get-ChildItem -LiteralPath $ProtoDirectory -Filter '*.proto' -File | Sort-Object Name) {
        $content = [System.IO.File]::ReadAllText($protoFile.FullName)
        $messageMatches = [regex]::Matches($content, '(?ms)^\s*message\s+(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*\{(?<body>.*?)^\s*\}')
        foreach ($messageMatch in $messageMatches) {
            $fields = @()
            $fieldMatches = [regex]::Matches($messageMatch.Groups['body'].Value, '(?m)^\s*(?:(?<modifier>optional|repeated)\s+)?(?<type>[A-Za-z_][A-Za-z0-9_.]*)\s+(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?<index>\d+)\s*(?:\[[^\]]*\])?\s*;')
            foreach ($fieldMatch in $fieldMatches) {
                $fields += [pscustomobject]@{
                    Index = [int]$fieldMatch.Groups['index'].Value
                    Name = Convert-ProtoFieldName -Name $fieldMatch.Groups['name'].Value
                    Type = Convert-ProtoFieldType -Type $fieldMatch.Groups['type'].Value
                    Repeated = $fieldMatch.Groups['modifier'].Value -eq 'repeated'
                }
            }

            $sourceName = $messageMatch.Groups['name'].Value
            $fileName = [System.IO.Path]::GetFileNameWithoutExtension($protoFile.Name)
            $generatedFile = Join-Path $GeneratedDirectory "$fileName.ts"
            $generatedContent = [System.IO.File]::ReadAllText($generatedFile)
            $generatedSymbols = [regex]::Matches($generatedContent, '(?m)^export const (?<name>[A-Za-z_][A-Za-z0-9_]*)\s*:\s*MessageFns<')
            $generatedSymbol = @($generatedSymbols | Where-Object {
                (Normalize-ProtoIdentifier -Value $_.Groups['name'].Value) -eq (Normalize-ProtoIdentifier -Value $sourceName)
            }) | Select-Object -First 1
            if (-not $generatedSymbol) {
                throw "No generated TypeScript codec matches proto message: $sourceName"
            }
            $messages[$sourceName] = [pscustomobject]@{
                SourceName = $sourceName
                TypeScriptName = $generatedSymbol.Groups['name'].Value
                FileName = $fileName
                Fields = @($fields | Sort-Object Index)
            }
        }
    }

    $routesFile = Join-Path $ProtoDirectory 'rpc_routes.proto'
    $routesContent = [System.IO.File]::ReadAllText($routesFile)
    $routeMatches = [regex]::Matches($routesContent, '(?m)^\s*(?<route>RPC_[A-Z0-9_]+)\s*=\s*(?<number>\d+)\s*;')
    $definitions = @()
    foreach ($routeMatch in $routeMatches) {
        $route = $routeMatch.Groups['route'].Value
        if ($route -eq 'RPC_ROUTE_UNSPECIFIED') {
            continue
        }
        $routeName = $route.Substring(4)
        $normalizedRoute = Normalize-ProtoIdentifier -Value $routeName
        $message = @($messages.Values | Where-Object {
            (Normalize-ProtoIdentifier -Value $_.SourceName) -eq $normalizedRoute
        }) | Select-Object -First 1
        if (-not $message) {
            throw "No proto message matches RPC route: $route"
        }

        $definitions += [pscustomobject]@{
            Route = $route
            RpcNum = [int]$routeMatch.Groups['number'].Value
            Message = $message
        }
    }
    return @($definitions | Sort-Object RpcNum)
}

function Write-CocosTestPanelProtocol {
    param(
        [Parameter(Mandatory = $true)][string]$ProtoDirectory,
        [Parameter(Mandatory = $true)][string]$GeneratedDirectory,
        [Parameter(Mandatory = $true)][string]$Output
    )

    $definitions = Get-CocosRpcMetadata -ProtoDirectory $ProtoDirectory -GeneratedDirectory $GeneratedDirectory
    $target = Join-Path $Output 'src\sdk-test-panel-protocol.ts'
    $lines = [System.Collections.Generic.List[string]]::new()
    $imports = @{}
    foreach ($definition in $definitions) {
        $fileName = $definition.Message.FileName
        if (-not $imports.ContainsKey($fileName)) {
            $imports[$fileName] = @()
        }
        $imports[$fileName] += $definition.Message.TypeScriptName
    }
    foreach ($fileName in ($imports.Keys | Sort-Object)) {
        $names = @($imports[$fileName] | Sort-Object -Unique) -join ', '
        $lines.Add("import { $names } from `"../generated/$fileName`";")
    }
    $lines.Add('import { RpcRoute } from "../generated/rpc_routes";')
    $lines.Add('')
    $lines.Add('export type RpcFieldType = "string" | "number" | "bool" | "bytes";')
    $lines.Add('')
    $lines.Add('export interface RpcFieldDefinition {')
    $lines.Add('  index: number;')
    $lines.Add('  name: string;')
    $lines.Add('  type: RpcFieldType;')
    $lines.Add('  repeated?: boolean;')
    $lines.Add('}')
    $lines.Add('')
    $lines.Add('interface MessageCodec {')
    $lines.Add('  create(base?: Record<string, unknown>): unknown;')
    $lines.Add('  encode(message: unknown): { finish(): Uint8Array };')
    $lines.Add('}')
    $lines.Add('')
    $lines.Add('export interface RpcDefinition {')
    $lines.Add('  rpcNum: number;')
    $lines.Add('  protoName: string;')
    $lines.Add('  fields: RpcFieldDefinition[];')
    $lines.Add('  encode(values: Record<string, string>): Uint8Array;')
    $lines.Add('}')
    $lines.Add('')
    $lines.Add('function parseScalarValue(field: RpcFieldDefinition, value: string): unknown {')
    $lines.Add('  if (field.type === "string") return value;')
    $lines.Add('  if (field.type === "bytes") {')
    $lines.Add('    if (!globalThis.atob) throw new Error("Base64 decoding is unavailable");')
    $lines.Add('    const binary = globalThis.atob(value.trim());')
    $lines.Add('    return Uint8Array.from(binary, (character) => character.charCodeAt(0));')
    $lines.Add('  }')
    $lines.Add('  if (field.type === "bool") {')
    $lines.Add('    if (value.trim() === "true" || value.trim() === "1") return true;')
    $lines.Add('    if (value.trim() === "false" || value.trim() === "0") return false;')
    $lines.Add('    throw new Error(`${field.name} must be true or false`);')
    $lines.Add('  }')
    $lines.Add('  const numberValue = Number(value.trim());')
    $lines.Add('  if (!Number.isFinite(numberValue) || !Number.isInteger(numberValue)) {')
    $lines.Add('    throw new Error(`${field.name} must be an integer`);')
    $lines.Add('  }')
    $lines.Add('  return numberValue;')
    $lines.Add('}')
    $lines.Add('')
    $lines.Add('function parseValue(field: RpcFieldDefinition, value: string): unknown {')
    $lines.Add('  if (!value.trim()) return undefined;')
    $lines.Add('  if (!field.repeated) return parseScalarValue(field, value);')
    $lines.Add('  return value.split(",").map((item) => item.trim()).filter(Boolean).map((item) => parseScalarValue(field, item));')
    $lines.Add('}')
    $lines.Add('')
    $lines.Add('function createRpcDefinition(rpcNum: number, protoName: string, codecValue: unknown, fields: RpcFieldDefinition[]): RpcDefinition {')
    $lines.Add('  const codec = codecValue as MessageCodec;')
    $lines.Add('  return {')
    $lines.Add('    rpcNum,')
    $lines.Add('    protoName,')
    $lines.Add('    fields,')
    $lines.Add('    encode(values) {')
    $lines.Add('      const message: Record<string, unknown> = {};')
    $lines.Add('      for (const field of fields) {')
    $lines.Add('        const parsed = parseValue(field, values[field.name] ?? "");')
    $lines.Add('        if (parsed !== undefined) message[field.name] = parsed;')
    $lines.Add('      }')
    $lines.Add('      return codec.encode(codec.create(message)).finish();')
    $lines.Add('    },')
    $lines.Add('  };')
    $lines.Add('}')
    $lines.Add('')
    $lines.Add('const field = (index: number, name: string, type: RpcFieldType, repeated = false): RpcFieldDefinition => ({ index, name, type, repeated });')
    $lines.Add('')
    $lines.Add('export const RPC_DEFINITIONS: RpcDefinition[] = [')
    foreach ($definition in $definitions) {
        $message = $definition.Message
        $lines.Add("  createRpcDefinition(RpcRoute.$($definition.Route), `"$($message.TypeScriptName)`", $($message.TypeScriptName), [")
        foreach ($field in $message.Fields) {
            $repeated = if ($field.Repeated) { ', true' } else { '' }
            $lines.Add("    field($($field.Index), `"$($field.Name)`", `"$($field.Type)`"$repeated),")
        }
        $lines.Add('  ]),')
    }
    $lines.Add('];')
    [System.IO.File]::WriteAllText($target, ($lines -join [Environment]::NewLine), [System.Text.UTF8Encoding]::new($false))
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
        Write-CocosTestPanelProtocol -ProtoDirectory $typescriptProto -GeneratedDirectory $typescriptGenerated -Output $Output
        Convert-CocosLocalImports -Output $Output
    }
}

function Sync-CocosSdkToProject {
    param(
        [Parameter(Mandatory = $true)][string]$SdkOutput,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )

    if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
        throw "Cocos project directory was not found: $ProjectRoot"
    }

    $target = Join-Path $ProjectRoot 'assets\xs-net-sdk'
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    Get-ChildItem -LiteralPath $SdkOutput -Force |
        Where-Object { $_.Name -notin @('node_modules', 'dist', 'test') } |
        Copy-Item -Destination $target -Recurse -Force
    Write-Host "[sdk] cocos synced: $target"
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
    Sync-CocosSdkToProject -SdkOutput $cocosRoot -ProjectRoot $CocosProjectRoot
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
