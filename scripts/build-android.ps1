param([switch]$Install, [switch]$UnitTests, [switch]$DeviceTests, [string]$Serial = '10AC610LZC001DX')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME','User')
$env:ANDROID_HOME = [Environment]::GetEnvironmentVariable('ANDROID_HOME','User')
if (!$env:JAVA_HOME -or !$env:ANDROID_HOME) { throw 'Configure user JAVA_HOME (JDK 17) and ANDROID_HOME.' }
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user'
$androidRoot = Join-Path $projectRoot 'android'
$localProperties = Join-Path $androidRoot 'local.properties'
if (!(Test-Path -LiteralPath $localProperties)) {
    Set-Content -LiteralPath $localProperties -Value ('sdk.dir=' + $env:ANDROID_HOME.Replace('\','/')) -Encoding Ascii
}
$gradlePath = Join-Path $projectRoot '.tools\gradle-8.13\bin\gradle.bat'
if (!(Test-Path -LiteralPath $gradlePath)) { $gradlePath = Join-Path $androidRoot 'gradlew.bat' }
$tasks = @('assembleDebug')
if ($UnitTests) { $tasks += 'testDebugUnitTest' }
if ($DeviceTests) { $tasks += 'assembleDebugAndroidTest' }
& $gradlePath -p $androidRoot @tasks --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Android build/tests failed.' }
$artifactDir = Join-Path $projectRoot 'artifacts'
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
# Derive the artifact name from the version the build actually declares, so bumping the version
# cannot leave a stale file name behind.
$phoneVersion = [regex]::Match((Get-Content -LiteralPath (Join-Path $androidRoot 'app\build.gradle.kts') -Raw), 'versionName\s*=\s*"(\d+\.\d+\.\d+)"').Groups[1].Value
if (!$phoneVersion) { throw 'Android versionName not found.' }
$apkPath = Join-Path $artifactDir "vitality-phone-$phoneVersion.apk"
Copy-Item -LiteralPath (Join-Path $androidRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $apkPath -Force
$signerPath = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\apksigner.bat'
$signature = & $signerPath verify --print-certs $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$signature | Set-Content -LiteralPath (Join-Path $artifactDir 'phone-signature.txt') -Encoding UTF8
Write-Output ('APK: ' + $apkPath)
if ($Install -or $DeviceTests) {
    $adbPath = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    & $adbPath -s $Serial install -r $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
    if ($DeviceTests) {
        & $adbPath -s $Serial install -r (Join-Path $androidRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
        if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }
        # Direct instrumentation avoids a test orchestrator uninstalling the user's app/data.
        $testOutput = & $adbPath -s $Serial shell am instrument --user 0 -w -r 'com.lrh.healthvivo.test/androidx.test.runner.AndroidJUnitRunner'
        $testExitCode = $LASTEXITCODE
        $testText = $testOutput -join "`n"
        $testOutput | Set-Content -LiteralPath (Join-Path $artifactDir 'independent-device-tests.txt') -Encoding UTF8
        Write-Output $testText
        if ($testExitCode -ne 0 -or $testText -notmatch 'OK \(\d+ tests\)' -or $testText -match 'FAILURES!!!|INSTRUMENTATION_FAILED') { throw 'Device tests did not pass.' }
    }
    if ($Install) { & $adbPath -s $Serial shell am start --user 0 -n 'com.lrh.healthvivo/.MainActivity' }
}
