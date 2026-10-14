param([string]$StudioProject = 'D:\AgentProject\HealthVivoWatchDev', [switch]$Open, [switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($StudioProject.Contains(' ')) { throw 'Studio project path must not contain spaces.' }
$studioRoot = [System.IO.Path]::GetFullPath($StudioProject)
$marker = Join-Path $studioRoot 'health-vivo-workspace.txt'
if ((Test-Path -LiteralPath $studioRoot) -and !(Test-Path -LiteralPath $marker)) { throw 'Existing directory is not a Health Vivo preview copy. Choose a new directory.' }
New-Item -ItemType Directory -Force -Path $studioRoot | Out-Null
[System.IO.File]::WriteAllText($marker,$projectRoot,[System.Text.UTF8Encoding]::new($false))
foreach ($sourceDirectory in @('src','test')) { Copy-Item -LiteralPath (Join-Path $projectRoot ('watch\'+$sourceDirectory)) -Destination $studioRoot -Recurse -Force }
foreach ($sourceFile in @('package.json','jsconfig.json')) { Copy-Item -LiteralPath (Join-Path $projectRoot ('watch\'+$sourceFile)) -Destination $studioRoot -Force }
$moduleRoot=Join-Path $studioRoot 'node_modules'
New-Item -ItemType Directory -Force -Path $moduleRoot | Out-Null
$packLink=Join-Path $moduleRoot 'blueos-pack'
if (!(Test-Path -LiteralPath $packLink)) { New-Item -ItemType Junction -Path $packLink -Target 'D:\DevTools\BlueOSStudio\resources\app\extensions\blueos-debugger\node_modules\blueos-pack' | Out-Null }
if (!$NoBuild) {
Push-Location $studioRoot
try {
    & 'D:\DevTools\BlueOSStudio\resources\app\extensions\blueos-debugger\media\node\node.exe' 'node_modules\blueos-pack\bin\index.js' build --device-type watch-square --debug
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath 'build\watch-square\Home\index.js')) { throw 'Preview compilation did not produce the Home page.' }
} finally { Pop-Location }
}
Write-Output ('Studio preview copy: ' + $studioRoot)
if ($Open) { & 'D:\DevTools\BlueOSStudio\bin\blue-os-studio.cmd' --reuse-window $studioRoot }
