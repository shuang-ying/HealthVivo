# Report or set the Windows Bluetooth radio state.
#
# scripts/probe-gt2-windows-gatt.py documents that "the caller manages and restores the Windows
# radio state", but nothing in the repository did that, so the uncached GATT check could only fail
# with ERROR_DEVICE_NOT_READY whenever the radio happened to be off. This is that caller.
#
# Uses the documented WinRT Radio API. That projection needs Windows PowerShell 5.1, so when this
# runs under PowerShell 7 it re-invokes itself with powershell.exe. It toggles the radio only; it
# never pairs, unpairs, or writes to any device.

param(
    [ValidateSet('status', 'on', 'off')]
    [string]$Action = 'status'
)
$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -ge 6) {
    if (!$PSCommandPath) { throw 'Cannot re-invoke without a script path.' }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath -Action $Action
    exit $LASTEXITCODE
}

Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
$null = [Windows.Devices.Radios.Radio, Windows.System.Devices, ContentType = WindowsRuntime]
$null = [Windows.Devices.Radios.RadioState, Windows.System.Devices, ContentType = WindowsRuntime]
$null = [Windows.Devices.Radios.RadioKind, Windows.System.Devices, ContentType = WindowsRuntime]

# WinRT returns IAsyncOperation; wait for it through the projection helper.
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]
function Await-Operation($operation, $type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($operation))
    $task.Wait(-1) | Out-Null
    return $task.Result
}

$radios = Await-Operation ([Windows.Devices.Radios.Radio]::GetRadiosAsync()) ([System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]])
$bluetooth = $radios | Where-Object { $_.Kind -eq [Windows.Devices.Radios.RadioKind]::Bluetooth }
if (!$bluetooth) { throw 'No Bluetooth radio reported by the Windows radio API.' }

if ($Action -ne 'status') {
    $want = if ($Action -eq 'on') { [Windows.Devices.Radios.RadioState]::On } else { [Windows.Devices.Radios.RadioState]::Off }
    Write-Output ("before`t$($bluetooth.State)")
    if ($bluetooth.State -ne $want) {
        $result = Await-Operation ($bluetooth.SetStateAsync($want)) ([Windows.Devices.Radios.RadioAccessStatus])
        if ($result -ne [Windows.Devices.Radios.RadioAccessStatus]::Allowed) { throw "The system refused the radio change: $result" }
        for ($attempt = 0; $attempt -lt 40 -and $bluetooth.State -ne $want; $attempt++) { Start-Sleep -Milliseconds 250 }
    }
    if ($bluetooth.State -ne $want) { throw "Radio did not reach $want (state is $($bluetooth.State))." }
}
Write-Output ("bluetooth`t$($bluetooth.State)")
