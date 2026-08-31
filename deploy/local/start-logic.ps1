[CmdletBinding()]
param(
    [switch]$Build
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$moduleRoot = Join-Path $repoRoot 'game-server\game-node-modules'
$targetRoot = Join-Path $moduleRoot 'target'
$jar = Join-Path $targetRoot 'game-node-modules-0.0.1-SNAPSHOT.jar'
$libRoot = Join-Path $targetRoot 'lib'
$arthasRoot = Join-Path $targetRoot 'arthas'
$arthasAgent = Join-Path $arthasRoot 'arthas-agent.jar'
$logRoot = Join-Path $repoRoot '.log'

if ($Build -or -not (Test-Path $jar) -or -not (Test-Path $libRoot)) {
    New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
    $buildStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $buildLog = Join-Path $logRoot "logic-build-$buildStamp.log"
    Push-Location $repoRoot
    try {
        & (Join-Path $repoRoot 'mvnw.cmd') '-pl' 'game-server/game-node-modules' '-am' 'package' '-DskipTests' *> $buildLog
        if ($LASTEXITCODE -ne 0) {
            throw "Logic build failed with exit code $LASTEXITCODE. See $buildLog"
        }
    }
    finally {
        Pop-Location
    }
}

if (-not (Test-Path $jar)) {
    throw "Logic JAR was not found: $jar"
}
if (-not (Test-Path $libRoot)) {
    throw "Logic dependency directory was not found: $libRoot"
}
if (-not (Test-Path $arthasAgent)) {
    throw "Arthas agent was not found: $arthasAgent"
}

New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stdout = Join-Path $logRoot "logic-$stamp.log"
$stderr = Join-Path $logRoot "logic-$stamp.err.log"
$classpath = "$jar;$libRoot\*"
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
$arthasTelnetPort = if ($env:LOGIC_ARTHAS_TELNET_PORT) { $env:LOGIC_ARTHAS_TELNET_PORT } else { '36581' }
$metricsPort = if ($env:LOGIC_METRICS_PORT) { $env:LOGIC_METRICS_PORT } else { '9090' }

$process = Start-Process `
    -FilePath $java `
    -ArgumentList @(
        '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
        "-Duser.home=$logRoot",
        "-Darthas.home=$arthasRoot",
        '-Darthas.ip=127.0.0.1',
        "-Darthas.telnetPort=$arthasTelnetPort",
        '-Darthas.httpPort=-1',
        "-javaagent:$arthasAgent",
        '-cp', $classpath, 'com.game.GameLogicApplication'
    ) `
    -WorkingDirectory $repoRoot `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -PassThru `
    -WindowStyle Hidden

Write-Output "Logic PID: $($process.Id)"
Write-Output "Logic log: $stdout"
Write-Output "Logic error log: $stderr"
Write-Output "GM trace endpoint: http://127.0.0.1:$metricsPort/admin/gm/trace"
