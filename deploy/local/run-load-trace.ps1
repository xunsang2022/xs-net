[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$GmToken,
    [string]$ManagementUrl = 'http://127.0.0.1:9091/admin/gm/trace',
    [ValidateSet('tcp', 'udp')]
    [string]$Protocol = 'tcp',
    [int]$Port = 0,
    [int]$Count = 10000,
    [int]$Concurrency = 10000,
    [int]$Qps = 10000,
    [int]$PlayerStart = 200000,
    [int]$ProtocolNumber = 1000,
    [int]$TraceTimeoutMs = 20000
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$project = Join-Path $repoRoot 'xs-net\xs-net-sdk\target\code\unity\LoadTest\XsNet.LoadTest.csproj'
$targetPort = if ($Port -gt 0) { $Port } elseif ($Protocol -eq 'udp') { 2002 } else { 8082 }
$logRoot = Join-Path $repoRoot '.log'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$traceStdout = Join-Path $logRoot "gm-trace-load-$stamp.log"
$traceStderr = Join-Path $logRoot "gm-trace-load-$stamp.err.log"
$loadStdout = Join-Path $logRoot "$Protocol-qps-load-$Qps-$stamp.log"
$loadStderr = Join-Path $logRoot "$Protocol-qps-load-$Qps-$stamp.err.log"
New-Item -ItemType File -Path $traceStdout, $traceStderr -Force | Out-Null

$traceBody = '{"protocolNumber":' + $ProtocolNumber + ',"timeoutMs":' + $TraceTimeoutMs + '}'
$traceClient = [System.Net.Http.HttpClient]::new()
$traceClient.Timeout = [TimeSpan]::FromSeconds(35)
$traceRequest = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::Post,
    $ManagementUrl)
$traceRequest.Headers.Add('X-GM-Token', $GmToken)
$traceRequest.Content = [System.Net.Http.StringContent]::new(
    $traceBody,
    [Text.Encoding]::UTF8,
    'application/json')
$traceTask = $traceClient.SendAsync($traceRequest)

Start-Sleep -Seconds 3

$loadArguments = @(
    'run', '--project', $project, '--no-build', '--',
    '--host', '127.0.0.1', '--protocol', $Protocol, '--port', $targetPort,
    '--count', $Count, '--concurrency', $Concurrency,
    '--qps', $Qps,
    '--player-start', $PlayerStart, '--timeout-ms', '15000'
)
$loadProcess = Start-Process -FilePath 'dotnet.exe' -ArgumentList $loadArguments `
    -WorkingDirectory $repoRoot -RedirectStandardOutput $loadStdout `
    -RedirectStandardError $loadStderr -PassThru -WindowStyle Hidden

Write-Output 'Trace request: asynchronous HTTP GM call'
Write-Output "Load PID: $($loadProcess.Id)"
Write-Output "Trace log: $traceStdout"
Write-Output "Load log: $loadStdout"

if (-not $loadProcess.WaitForExit(120000)) {
    Stop-Process -Id $loadProcess.Id -Force
    throw "Load test timed out; process $($loadProcess.Id) was stopped."
}

$traceExitCode = 1
try {
    $traceResponse = $traceTask.GetAwaiter().GetResult()
    $traceOutput = $traceResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    [IO.File]::WriteAllText($traceStdout, $traceOutput, [Text.UTF8Encoding]::new($false))
    $traceExitCode = if ([int]$traceResponse.StatusCode -ge 200 -and [int]$traceResponse.StatusCode -lt 300) { 0 } else { 1 }
}
catch {
    [IO.File]::WriteAllText($traceStderr, $_.Exception.ToString(), [Text.UTF8Encoding]::new($false))
}
finally {
    $traceRequest.Dispose()
    $traceClient.Dispose()
}

Write-Output "Load exit code: $($loadProcess.ExitCode)"
Write-Output "Trace exit code: $traceExitCode"
Write-Output '--- Load result ---'
Get-Content $loadStdout -Tail 30
Write-Output '--- Trace result ---'
Get-Content $traceStdout -Tail 80
if ((Get-Item $traceStderr).Length -gt 0) {
    Write-Output '--- Trace errors ---'
    Get-Content $traceStderr -Tail 30
}

exit ([Math]::Max($loadProcess.ExitCode, $traceExitCode))
