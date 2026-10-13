param([int]$Port = 18764, [int]$DurationSeconds = 900)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$references = @(
    'System.dll', 'System.Core.dll',
    'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.Runtime.WindowsRuntime.dll',
    'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.Runtime.InteropServices.WindowsRuntime.dll',
    'C:\Windows\Microsoft.NET\assembly\GAC_MSIL\System.Runtime\v4.0_4.0.0.0__b03f5f7f11d50a3a\System.Runtime.dll',
    'C:\Windows\Microsoft.NET\assembly\GAC_MSIL\System.Threading.Tasks\v4.0_4.0.0.0__b03f5f7f11d50a3a\System.Threading.Tasks.dll',
    (Join-Path (Split-Path $PSScriptRoot -Parent) '.tools\windows-contracts\ref\netstandard2.0\Windows.WinMD')
)
$references += Get-ChildItem (Join-Path (Split-Path $PSScriptRoot -Parent) '.tools\windows-contracts\ref\netstandard2.0') -Filter '*.winmd' | Where-Object { $_.Name -ne 'Windows.WinMD' } | ForEach-Object FullName
$localDir = Join-Path (Split-Path $PSScriptRoot -Parent) '.local'
New-Item -ItemType Directory -Force -Path $localDir | Out-Null
$assemblyPath = Join-Path $localDir 'WindowsBlePeripheral.dll'
$arguments = @('/nologo', '/target:library', ('/out:' + $assemblyPath))
$arguments += $references | ForEach-Object { '/reference:' + $_ }
$arguments += Join-Path $PSScriptRoot 'WindowsBlePeripheral.cs'
& 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe' @arguments
if ($LASTEXITCODE -ne 0) { throw 'Windows BLE host compilation failed.' }
Add-Type -Path $assemblyPath
$peripheral = New-Object WindowsBlePeripheral($Port)
try {
    $peripheral.Start().GetAwaiter().GetResult()
    $deadline = (Get-Date).AddSeconds($DurationSeconds)
    while ((Get-Date) -lt $deadline) { Start-Sleep -Seconds 1 }
} finally { $peripheral.Stop() }
