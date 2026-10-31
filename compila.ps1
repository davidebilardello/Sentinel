param([switch]$Release, [string]$SigningProperties)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

if ($Release) {
    if (-not $SigningProperties) { $SigningProperties = $env:SENTINEL_SIGNING_PROPERTIES }
    if (-not $SigningProperties) { $SigningProperties = Join-Path $env:USERPROFILE 'SentinelSigning\signing.properties' }
    if (-not (Test-Path -LiteralPath $SigningProperties -PathType Leaf)) { throw 'Release signing properties not found. Specify -SigningProperties.' }
    $env:SENTINEL_SIGNING_PROPERTIES = (Resolve-Path -LiteralPath $SigningProperties).Path
}

$jdkCandidates = @($env:JAVA_HOME, "$env:ProgramFiles\Android\Android Studio\jbr")
$jdkCandidates += Get-ChildItem -LiteralPath "$env:ProgramFiles\JetBrains" -Directory -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName 'jbr' }
$jdkCandidates += Get-ChildItem -LiteralPath "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
$jdkPath = $jdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'release')) } | Where-Object {
    (Get-Content -LiteralPath (Join-Path $_ 'release')) -match '^JAVA_VERSION="21\.'
} | Select-Object -First 1
if (-not $jdkPath) { throw 'Installa un JDK 21 oppure indica il suo percorso in JAVA_HOME.' }
$env:JAVA_HOME = $jdkPath

if (-not (Test-Path -LiteralPath 'local.properties')) {
    $sdkPath = @($env:ANDROID_HOME, "$env:USERPROFILE\Android\Sdk", "$env:LOCALAPPDATA\Android\Sdk") |
        Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platforms\android-36')) } | Select-Object -First 1
    if (-not $sdkPath) { throw 'Installa Android SDK 36 e configura il percorso SDK in Android Studio.' }
    $sdkProperty = $sdkPath.Replace('\', '/').Replace(':', '\:')
    [System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'local.properties'), "sdk.dir=$sdkProperty`n", [System.Text.Encoding]::ASCII)
}

$variant = if ($Release) { 'Release' } else { 'Debug' }
& .\gradlew.bat ":app:assemble$variant" ":app:test${variant}UnitTest" ":app:lint$variant" --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Compilazione o verifiche non riuscite.' }
$variantFolder = $variant.ToLowerInvariant()
Write-Output "APK pronto: $PSScriptRoot\app\build\outputs\apk\$variantFolder\app-$variantFolder.apk"
