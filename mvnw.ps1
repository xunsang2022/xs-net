# Maven wrapper for xs-net using the Maven bundled with IntelliJ IDEA.
# Usage: .\mvnw.ps1 [Maven arguments]

$ErrorActionPreference = 'Stop'

$mavenHome = 'F:\Program Files\JetBrains\IntelliJ IDEA 2025.1.1\plugins\maven\lib\maven3'
$mvn = Join-Path $mavenHome 'bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $mvn)) {
    throw "IDEA bundled Maven not found: $mvn"
}

$jdk = 'C:\Users\XOS\.jdks\ms-21.0.10'
if (-not (Test-Path -LiteralPath (Join-Path $jdk 'bin\java.exe'))) {
    $jdk = 'F:\Program Files\JetBrains\IntelliJ IDEA 2025.1.1\jbr'
}
$env:JAVA_HOME = $jdk

$homeDir = if ($env:USERPROFILE) { $env:USERPROFILE } else { 'C:\Users\XOS' }
$localRepo = Join-Path $homeDir '.m2\repository'

$mavenArgs = @('-Dmaven.repo.local=' + $localRepo)
$mavenArgs += $args

& $mvn @mavenArgs
exit $LASTEXITCODE
