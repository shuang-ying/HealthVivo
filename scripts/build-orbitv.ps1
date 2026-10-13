param([switch]$Push, [string]$Serial = '10AC610LZC001DX')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $projectRoot 'orbitv')
try {
    if (!(Test-Path -LiteralPath 'node_modules')) { & npm.cmd ci --ignore-scripts --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'OrbitV dependency installation failed.' } }
    & npm.cmd run package
    if ($LASTEXITCODE -ne 0) { throw 'OrbitV build failed.' }
    $artifact = Join-Path $projectRoot 'artifacts\vitality-orbitv-1.2.0.ovpkg'
    Copy-Item -LiteralPath 'release\com.lrh.healthvivo.watch-1.2.0.ovpkg' -Destination $artifact -Force
    Write-Output ('OrbitV extension: ' + $artifact)
    if ($Push) {
        & 'D:\Android\Sdk\platform-tools\adb.exe' -s $Serial push $artifact '/sdcard/Download/vitality-orbitv-1.2.0.ovpkg'
        if ($LASTEXITCODE -ne 0) { throw 'Extension copy failed.' }
    }
} finally { Pop-Location }
