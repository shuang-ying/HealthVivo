param([string]$ProbeWorkspace = 'D:\AgentProject\HealthVivoDirectProbe')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$probeRoot = [System.IO.Path]::GetFullPath($ProbeWorkspace)
if ($probeRoot.Contains(' ')) { throw 'BlueOS pack requires a path without spaces.' }
$marker = Join-Path $probeRoot 'health-vivo-direct-probe.txt'
if (Test-Path -LiteralPath $probeRoot) {
    if (!(Test-Path -LiteralPath $marker) -or (Get-Content -LiteralPath $marker -Raw) -ne $projectRoot) {
        throw 'Refusing to overwrite an unmarked or unrelated probe workspace.'
    }
}
New-Item -ItemType Directory -Path $probeRoot -Force | Out-Null
[System.IO.File]::WriteAllText($marker,$projectRoot)
Copy-Item -LiteralPath (Join-Path $projectRoot 'probes\blueos-direct\src') -Destination $probeRoot -Recurse -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'probes\blueos-direct\package.json') -Destination $probeRoot -Force
$common = Join-Path $probeRoot 'src\common'
New-Item -ItemType Directory -Path $common -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'watch\src\common\icon.png') -Destination (Join-Path $common 'icon.png') -Force
Push-Location $probeRoot
try {
    & 'D:\DevTools\BlueOSStudio\resources\app\extensions\blueos-debugger\media\node\node.exe' 'D:\DevTools\BlueOSStudio\resources\app\extensions\blueos-debugger\node_modules\blueos-pack\bin\index.js' build --device-type watch-square --debug
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath 'build\watch-square\Home\index.js')) { throw 'BlueOS probe compilation failed.' }
} finally { Pop-Location }
Write-Output ('Isolated BlueOS probe: ' + $probeRoot)
