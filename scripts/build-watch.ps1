param(
    [string]$BlueOsStudio = 'D:\DevTools\BlueOSStudio',
    [switch]$Install,
    [switch]$LegacyTests,
    [string]$Serial = '10AC610LZC001DX'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$extensionRoot = Join-Path $BlueOsStudio 'resources\app\extensions\blueos-debugger'
$nodePath = Join-Path $extensionRoot 'media\node\node.exe'
$packPath = Join-Path $extensionRoot 'node_modules\blueos-pack\bin\index.js'
if (!(Test-Path -LiteralPath $nodePath) -or !(Test-Path -LiteralPath $packPath)) { throw 'BlueOS Studio build tools not found.' }
$sdkRoot = [Environment]::GetEnvironmentVariable('ANDROID_HOME', 'User')
if (!$sdkRoot) { $sdkRoot = $env:ANDROID_HOME }
$adbPath = Join-Path $sdkRoot 'platform-tools\adb.exe'
$env:PATH = (Split-Path $nodePath) + ';' + $env:PATH
& (Join-Path $PSScriptRoot 'render-watch-icon.ps1')
# blueos-pack rejects paths containing spaces. Build a temporary source copy.
$stageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('health-vivo-watch-' + [Guid]::NewGuid().ToString('N'))
if ($stageRoot.Contains(' ')) { throw 'Configure TEMP to a directory without spaces for BlueOS builds.' }
New-Item -ItemType Directory -Path $stageRoot | Out-Null
$watchSource = (Resolve-Path -LiteralPath (Join-Path $projectRoot 'watch\src')).Path
$stageSource = Join-Path $stageRoot 'src'
New-Item -ItemType Directory -Path $stageSource | Out-Null
# Exclude generated private identity and machine-local overrides from the standalone package.
Get-ChildItem -LiteralPath $watchSource -Recurse -File | ForEach-Object {
    $relative = $_.FullName.Substring($watchSource.Length + 1)
    if ($relative -match '\.local\.(js|json|properties)$') { return }
    $destination = Join-Path $stageSource $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $destination
}
if ($LegacyTests) { Copy-Item -LiteralPath (Join-Path $projectRoot 'watch\test') -Destination $stageRoot -Recurse }
Copy-Item -LiteralPath (Join-Path $projectRoot 'watch\package.json') -Destination $stageRoot
Push-Location $stageRoot
try {
    if ($LegacyTests) {
        & $nodePath --test 'test/protocol.test.cjs'
        if ($LASTEXITCODE -ne 0) { throw 'Legacy prototype protocol tests failed.' }
    }
    & $nodePath $packPath build --device-type watch-square --debug 2>&1 | Tee-Object -Variable packOutput
    if ($LASTEXITCODE -ne 0 -or ($packOutput -match '\[ERROR\]')) { throw 'Watch compiler reported an error; no artifact copied.' }
    if (!(Test-Path -LiteralPath 'dist')) { throw 'BlueOS did not produce an output directory; inspect compiler errors above.' }
    $rpk = Get-ChildItem -LiteralPath 'dist' -Recurse -Filter '*.rpk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (!$rpk) { throw 'No RPK produced.' }
    $artifactDir = Join-Path $projectRoot 'artifacts'
    New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
    $artifact = Join-Path $artifactDir 'health-vivo-watch-0.7.1.rpk'
    Copy-Item -LiteralPath $rpk.FullName -Destination $artifact -Force
    Write-Output ('RPK: ' + $artifact)
    if ($Install) {
        & $adbPath -s $Serial push $artifact '/sdcard/Download/health-vivo-watch-0.7.1.rpk'
        if ($LASTEXITCODE -ne 0) { throw 'RPK transfer failed.' }
        Write-Output 'RPK copied to Download. Install it on the watch with the third-party OrbitV app (installer only).'
    }
} finally { Pop-Location }
