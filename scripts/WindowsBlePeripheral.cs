// Desktop simulator radio only. Watch protocol, crypto and Home remain in production JS.
using System;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

public sealed class WindowsBlePeripheral
{
    private GattServiceProvider provider;
    private GattLocalCharacteristic rx, status;
    private readonly int port;
    public WindowsBlePeripheral(int port) { this.port = port; }
    public async Task Start()
    {
        var adapter = await System.WindowsRuntimeSystemExtensions.AsTask(BluetoothAdapter.GetDefaultAsync());
        if (adapter == null || !adapter.IsPeripheralRoleSupported) throw new Exception("BLE peripheral role unavailable");
        var result = await System.WindowsRuntimeSystemExtensions.AsTask(GattServiceProvider.CreateAsync(new Guid("9f7c7200-1771-42b9-bc36-d88bfb0952a0")));
        if (result.Error != BluetoothError.Success) throw new Exception("Create service: " + result.Error);
        provider = result.ServiceProvider;
        var written = await System.WindowsRuntimeSystemExtensions.AsTask(provider.Service.CreateCharacteristicAsync(new Guid("9f7c7201-1771-42b9-bc36-d88bfb0952a0"),
            new GattLocalCharacteristicParameters { CharacteristicProperties = GattCharacteristicProperties.Write, WriteProtectionLevel = GattProtectionLevel.Plain }));
        var readable = await System.WindowsRuntimeSystemExtensions.AsTask(provider.Service.CreateCharacteristicAsync(new Guid("9f7c7202-1771-42b9-bc36-d88bfb0952a0"),
            new GattLocalCharacteristicParameters { CharacteristicProperties = GattCharacteristicProperties.Read, ReadProtectionLevel = GattProtectionLevel.Plain }));
        if (written.Error != BluetoothError.Success || readable.Error != BluetoothError.Success) throw new Exception("Create characteristics failed");
        rx = written.Characteristic; status = readable.Characteristic;
        rx.WriteRequested += Write; status.ReadRequested += Read;
        provider.AdvertisementStatusChanged += Advertising;
        provider.StartAdvertising(new GattServiceProviderAdvertisingParameters { IsConnectable = true, IsDiscoverable = true });
        Console.WriteLine("BLE service registered; awaiting advertising state.");
    }
    private void Advertising(GattServiceProvider sender, GattServiceProviderAdvertisementStatusChangedEventArgs args)
    { Console.WriteLine("Advertising: " + args.Status + "; error=" + args.Error); }
    private async Task<string> Exchange(string line)
    {
        using (var socket = new TcpClient()) {
            socket.ReceiveTimeout = 5000; socket.SendTimeout = 5000;
            await socket.ConnectAsync("127.0.0.1", port);
            using (var stream = socket.GetStream())
            using (var writer = new StreamWriter(stream, new UTF8Encoding(false), 1024, true))
            using (var reader = new StreamReader(stream, Encoding.UTF8, false, 1024, true)) {
                await writer.WriteLineAsync(line); await writer.FlushAsync();
                var reading = reader.ReadLineAsync();
                if (await Task.WhenAny(reading, Task.Delay(5000)) != reading) throw new IOException("Simulator response timeout");
                return await reading;
            }
        }
    }
    private static string Peer(GattSession session)
    { return Convert.ToBase64String(Encoding.UTF8.GetBytes(session.DeviceId.Id)); }
    private async void Write(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        using (args.GetDeferral()) {
            GattWriteRequest request = null;
            try {
                request = await System.WindowsRuntimeSystemExtensions.AsTask(args.GetRequestAsync());
                if (request == null) { Console.WriteLine("Write denied by Windows"); return; }
                var data = new byte[request.Value.Length];
                using (var reader = DataReader.FromBuffer(request.Value)) reader.ReadBytes(data);
                var response = await Exchange("W\t" + Peer(args.Session) + "\t" + request.Offset + "\t" + Convert.ToBase64String(data));
                if (request.Option == GattWriteOption.WriteWithResponse) {
                    if (response == "OK") request.Respond(); else request.RespondWithProtocolError(0x0E);
                }
            } catch (Exception error) {
                Console.WriteLine("Write failure: " + error.GetType().Name + ": " + error.Message);
                if (request != null && request.Option == GattWriteOption.WriteWithResponse) try { request.RespondWithProtocolError(0x0E); } catch {}
            }
        }
    }
    private async void Read(GattLocalCharacteristic sender, GattReadRequestedEventArgs args)
    {
        using (args.GetDeferral()) {
            GattReadRequest request = null;
            try {
                request = await System.WindowsRuntimeSystemExtensions.AsTask(args.GetRequestAsync());
                if (request == null) { Console.WriteLine("Read denied by Windows"); return; }
                var response = await Exchange("R\t" + Peer(args.Session) + "\t" + request.Offset);
                if (!response.StartsWith("OK\t")) { request.RespondWithProtocolError(0x0E); return; }
                using (var writer = new DataWriter()) {
                    writer.WriteBytes(Convert.FromBase64String(response.Substring(3)));
                    request.RespondWithValue(writer.DetachBuffer());
                }
            } catch (Exception error) {
                Console.WriteLine("Read failure: " + error.GetType().Name + ": " + error.Message);
                if (request != null) try { request.RespondWithProtocolError(0x0E); } catch {}
            }
        }
    }
    public void Stop()
    {
        if (rx != null) rx.WriteRequested -= Write;
        if (status != null) status.ReadRequested -= Read;
        if (provider != null) { provider.StopAdvertising(); provider.AdvertisementStatusChanged -= Advertising; }
        provider = null; rx = null; status = null;
    }
}
