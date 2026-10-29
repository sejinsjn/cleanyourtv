# Build the release APK and install/update it without changing the Home role or stock launcher state.
# Use this on TVs where set-home-activity reports success but the physical Home button still opens stock Home.
#
# Usage: launcher\build-and-install-no-home.ps1 -TvHost <tv ip> [-RestoreKeyServices] [-LaunchHomeAfterBoot]
#        -RestoreKeyServices grants WRITE_SECURE_SETTINGS for restore_key_services.
#        -LaunchHomeAfterBoot also grants WRITE_SECURE_SETTINGS and persists TCL's AUTO_START app-op.
param(
    [string]$TvHost = $env:TV_HOST,
    [switch]$RestoreKeyServices,
    [switch]$LaunchHomeAfterBoot
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost

$repo = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $repo 'tools'
$py = Join-Path $tools 'venv\Scripts\python.exe'
if (-not (Test-Path $py)) {
    throw 'Python venv not found; run: python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt'
}

if ($env:JAVA_HOME) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path $java)) { throw "JAVA_HOME does not contain bin\java.exe: $env:JAVA_HOME" }
} else {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if (-not $javaCommand) { throw 'Set JAVA_HOME to JDK 17+ or put a compatible java.exe on PATH' }
    $java = $javaCommand.Source
}
$versionText = (& $env:ComSpec /c "`"$java`" -version 2>&1" | Out-String)
$version = [regex]::Match($versionText, 'version "(\d+)')
if (-not $version.Success -or [int]$version.Groups[1].Value -lt 17 -or [int]$version.Groups[1].Value -gt 22) {
    throw "Gradle 8.9 needs a compatible JDK (17-22); found: $versionText"
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $sdk)) { throw "Android SDK not found at '$sdk'; set ANDROID_HOME" }
if (-not (Test-Path (Join-Path $PSScriptRoot 'local.properties'))) {
    "sdk.dir=$($sdk -replace '\\', '/')" | Out-File -Encoding ascii (Join-Path $PSScriptRoot 'local.properties')
}

Push-Location $PSScriptRoot
try {
    & .\gradlew.bat assembleRelease --no-daemon -q --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
} finally {
    Pop-Location
}

$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "Release APK not found: $apk" }
$remoteApk = '/data/local/tmp/tvhome-no-home.apk'
& $py -I (Join-Path $tools 'b64push.py') $apk $remoteApk
if ($LASTEXITCODE -ne 0) { throw 'APK upload failed; Home and the stock launcher were not changed' }

$out = & $py -I (Join-Path $tools 'tv.py') "pm install -r $remoteApk; rm -f $remoteApk"
Write-Host $out
if ($LASTEXITCODE -ne 0 -or "$out" -notmatch 'Success') {
    throw 'APK installation failed; Home and the stock launcher were not changed'
}
if ($RestoreKeyServices -or $LaunchHomeAfterBoot) {
    $grant = & $py -I (Join-Path $tools 'tv.py') 'pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS && echo restore-granted'
    Write-Host $grant
    if ($LASTEXITCODE -ne 0 -or "$grant" -notmatch 'restore-granted') {
        throw 'Installed the launcher, but WRITE_SECURE_SETTINGS was not granted'
    }
}
if ($LaunchHomeAfterBoot) {
    $appOp = & $py -I (Join-Path $tools 'tv.py') 'cmd appops set com.gathin.tvhome AUTO_START allow && cmd appops write-settings && cmd appops get com.gathin.tvhome AUTO_START'
    Write-Host $appOp
    if ($LASTEXITCODE -ne 0 -or "$appOp" -notmatch 'AUTO_START:\s*allow') {
        throw 'Installed the launcher, but TCL AUTO_START was not granted'
    }
}
Write-Host 'Installed/updated com.gathin.tvhome. This script does not select a Home app, enable/disable the stock launcher, or enable accessibility services.'
if ($RestoreKeyServices) { Write-Host 'Granted WRITE_SECURE_SETTINGS for the explicitly requested restore_key_services option.' }
if ($LaunchHomeAfterBoot) { Write-Host 'Granted WRITE_SECURE_SETTINGS and saved TCL AUTO_START for the explicitly requested launch_home_after_boot option.' }
