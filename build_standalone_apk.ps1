$ErrorActionPreference = "Stop"

# Override JAVA_HOME with the correct path to the Microsoft OpenJDK 17 installation
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
Write-Host "Set JAVA_HOME to: $env:JAVA_HOME"

Write-Host "1. Downloading Gradle 8.2..."
$zipPath = "$env:TEMP\gradle-8.2-bin.zip"
if (!(Test-Path $zipPath)) {
    Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-8.2-bin.zip" -OutFile $zipPath
}

Write-Host "2. Extracting Gradle 8.2..."
$extractPath = "$env:TEMP\gradle-temp"
if (!(Test-Path "$extractPath\gradle-8.2")) {
    Expand-Archive -Path $zipPath -DestinationPath $extractPath -Force
}

Write-Host "3. Generating Gradle Wrapper..."
$gradleBin = "$extractPath\gradle-8.2\bin\gradle.bat"
$projectDir = "c:\Users\Varshan\Downloads\projects\tieen\fast-file-share\android-app"
& $gradleBin wrapper --gradle-version 8.2 --project-dir $projectDir

Write-Host "4. Compiling APK (assembleDebug)..."
& "$projectDir\gradlew.bat" -p "$projectDir" assembleDebug

Write-Host "Build complete! Checking generated APK path..."
$apkPath = "$projectDir\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apkPath) {
    Write-Host "SUCCESS: APK generated at $apkPath"
    # Copy APK to the root of the project as fast-file-share.apk
    Copy-Item -Path $apkPath -Destination "c:\Users\Varshan\Downloads\projects\tieen\fast-file-share\fast-file-share.apk" -Force
    Write-Host "SUCCESS: Copied APK to root fast-file-share.apk"
} else {
    Write-Error "ERROR: APK was not found at the expected location."
}
