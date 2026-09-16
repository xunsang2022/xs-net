# Maven wrapper for xs-net using the Maven bundled with IntelliJ IDEA.
# Usage: .\mvnw.ps1 [Maven arguments]
# 自动探测 IDEA 自带 Maven 与 JDK 21；可通过环境变量 IDEA_MAVEN_HOME / JAVA_HOME 显式指定。

$ErrorActionPreference = 'Stop'

# ---- 1. 定位 Maven（优先环境变量，其次扫描 JetBrains 安装目录） ----
$mvn = $null
if ($env:IDEA_MAVEN_HOME) {
    $candidate = Join-Path $env:IDEA_MAVEN_HOME 'bin\mvn.cmd'
    if (Test-Path -LiteralPath $candidate) {
        $mvn = $candidate
    }
}
if (-not $mvn) {
    $jetbrainsRoots = @('C:\Program Files\JetBrains', 'F:\Program Files\JetBrains')
    foreach ($jetbrainsRoot in $jetbrainsRoots) {
        if (Test-Path -LiteralPath $jetbrainsRoot) {
            $ideaDirs = Get-ChildItem -LiteralPath $jetbrainsRoot -Directory -Filter 'IntelliJ IDEA*' |
                Sort-Object Name -Descending
            foreach ($dir in $ideaDirs) {
                $candidate = Join-Path $dir.FullName 'plugins\maven\lib\maven3\bin\mvn.cmd'
                if (Test-Path -LiteralPath $candidate) {
                    $mvn = $candidate
                    break
                }
            }
            if ($mvn) {
                break
            }
        }
    }
}
if (-not $mvn) {
    throw "IDEA bundled Maven not found. Set IDEA_MAVEN_HOME to the Maven home (for example IntelliJ IDEA plugins\maven\lib\maven3)."
}

# ---- 2. 定位 JDK 21（优先扫描用户 .jdks 目录，其次回退到 JAVA_HOME） ----
# 说明：本仓库为 Java 21 代码。用户 .jdks 目录中按名称可明确识别出 JDK21，
# 因此优先扫描它，避免全局 JAVA_HOME 指向其它版本(如 Java 8)导致编译失败。
$jdk = $null
$jdksRoot = Join-Path $env:USERPROFILE '.jdks'
if (Test-Path -LiteralPath $jdksRoot) {
    # 优先匹配名称以 21 开头的目录（如 ms-21.0.10 / zulu-21.0.5 ...）
    $jdkDirs = Get-ChildItem -LiteralPath $jdksRoot -Directory |
        Where-Object { $_.Name -match '^(?:jdk-?|openjdk-?|ms-?|zulu-?|corretto-?|temurin-?)?21(?:\.|_|$)' } |
        Sort-Object Name -Descending
    foreach ($dir in $jdkDirs) {
        if (Test-Path -LiteralPath (Join-Path $dir.FullName 'bin\java.exe')) {
            $jdk = $dir.FullName
            break
        }
    }
}
if (-not $jdk) {
    # 未在 .jdks 中找到明确命名的 JDK21，回退到 JAVA_HOME（存在且含 java.exe）
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $jdk = $env:JAVA_HOME
    }
}
if (-not $jdk) {
    throw "JDK 21 not found. Set JAVA_HOME to a JDK 21 installation."
}
$env:JAVA_HOME = $jdk

# ---- 3. 本地仓库 ----
$homeDir = if ($env:USERPROFILE) { $env:USERPROFILE } else { $env:USERPROFILE }
$localRepo = Join-Path $homeDir '.m2\repository'

$mavenArgs = @('-Dmaven.repo.local=' + $localRepo)
$mavenArgs += $args

& $mvn @mavenArgs
exit $LASTEXITCODE
