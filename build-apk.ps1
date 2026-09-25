# Builds the signed release APK and copies it to .\release and your Downloads folder.
# Usage (from the tg-finder folder):  powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$tools = Join-Path $env:USERPROFILE "android-dev"
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $env:JAVA_HOME = Join-Path $tools "jdk17"
}
if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    throw "JDK 17 not found. Install it or set JAVA_HOME (see README: Building the APK yourself)."
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $tools "sdk" }
if (-not (Test-Path "keystore.properties")) {
    throw "keystore.properties is missing. The APK cannot be signed without the release keystore."
}

Write-Host "Running unit tests..." -ForegroundColor Yellow
& .\gradlew.bat testDebugUnitTest --console=plain
if ($LASTEXITCODE -ne 0) { throw "Unit tests failed." }

Write-Host "Building signed release APK..." -ForegroundColor Yellow
& .\gradlew.bat assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "Build failed." }

$version = (Select-String -Path "app\build.gradle.kts" -Pattern 'versionName\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
$name = "TGFinder-$version.apk"
New-Item -ItemType Directory -Force "release" | Out-Null
Copy-Item "app\build\outputs\apk\release\app-release.apk" "release\$name" -Force
Copy-Item "release\$name" (Join-Path $env:USERPROFILE "Downloads\$name") -Force

Write-Host ""
Write-Host "Done. Install this file on your phone:" -ForegroundColor Green
Write-Host "  $(Resolve-Path "release\$name")"
Write-Host "  (also copied to $(Join-Path $env:USERPROFILE "Downloads\$name"))"
