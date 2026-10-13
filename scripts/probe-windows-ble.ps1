param([switch]$Enable, [switch]$RestoreOff)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$adapterType = [Windows.Devices.Bluetooth.BluetoothAdapter,Windows.Devices,ContentType=WindowsRuntime]
$operation = $adapterType::GetDefaultAsync()
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1
$task = $asTask.MakeGenericMethod($adapterType).Invoke($null, @($operation))
if (!$task.Wait(15000)) { throw 'Bluetooth adapter query timed out.' }
$adapter = $task.Result
if (!$adapter) { throw 'No default Bluetooth adapter.' }
$radioType = [Windows.Devices.Radios.Radio,Windows.Devices,ContentType=WindowsRuntime]
$radioTask = $asTask.MakeGenericMethod($radioType).Invoke($null, @($adapter.GetRadioAsync()))
if (!$radioTask.Wait(15000)) { throw 'Bluetooth radio query timed out.' }
$radio = $radioTask.Result
if ($radio -and ($Enable -or $RestoreOff)) {
    $requested = if ($Enable) { [Windows.Devices.Radios.RadioState]::On } else { [Windows.Devices.Radios.RadioState]::Off }
    $accessType = [Windows.Devices.Radios.RadioAccessStatus,Windows.Devices,ContentType=WindowsRuntime]
    $changeTask = $asTask.MakeGenericMethod($accessType).Invoke($null, @($radio.SetStateAsync($requested)))
    if (!$changeTask.Wait(15000)) { throw 'Bluetooth radio change timed out.' }
    if ($changeTask.Result.ToString() -ne 'Allowed') { throw ('Bluetooth radio change: ' + $changeTask.Result) }
}
[pscustomobject]@{
    LowEnergy = $adapter.IsLowEnergySupported
    Central = $adapter.IsCentralRoleSupported
    Peripheral = $adapter.IsPeripheralRoleSupported
    RadioState = if ($radio) { $radio.State.ToString() } else { 'Unavailable' }
} | ConvertTo-Json -Compress
