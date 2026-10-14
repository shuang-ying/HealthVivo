"""Bumble RFCOMM peer for an Android AVD. No GT2 RPC, OrbitV or health data.

Requires the isolated .tools/bluetooth-probe-venv with bumble and its Android
transport dependencies. Run only with a local netsim instance.
"""
import argparse
import asyncio
import json
import logging
from pathlib import Path

from bumble import core, hci, rfcomm
from bumble.device import Device
from bumble.transport import open_transport


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--transport", default="android-netsim:name=HealthVivo-SPP-SIM")
    parser.add_argument("--duration", type=int, default=180)
    options = parser.parse_args()
    if not options.transport.startswith("android-netsim:"):
        raise ValueError("This probe only accepts the virtual netsim transport")
    root = Path(__file__).resolve().parent.parent / "artifacts" / "direct-link-probe"
    root.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(filename=root / "virtual-peer-debug.log", level=logging.DEBUG, format="%(asctime)s %(name)s %(message)s")
    report = {"syntheticOnly": True, "gt2FirmwareVerified": False, "connections": 0,
              "receivedPings": 0, "sentPongs": 0, "forcedDisconnections": 0}

    def save():
        (root / "virtual-peer.json").write_text(json.dumps(report, indent=2), "utf-8")

    async with await open_transport(options.transport) as transport:
        device = Device.with_hci("HealthVivo-SPP-SIM", hci.Address("F0:F1:F2:F3:F4:F5"),
                                 transport.source, transport.sink)
        device.classic_enabled = True
        await device.power_on()
        report["virtualAddress"] = str(device.public_address).split("/")[0]
        connections = []

        def connected(connection):
            connections.append(connection)
            report["connections"] += 1
            save()

        device.on("connection", connected)
        server = rfcomm.Server(device)

        def on_channel(channel):
            report["channelAccepted"] = True
            save()
            buffer = bytearray()

            def received(data):
                buffer.extend(data)
                if len(buffer) > 8192:
                    raise ValueError("Oversized synthetic message")
                while b"\n" in buffer:
                    line, _, rest = buffer.partition(b"\n")
                    buffer[:] = rest
                    ping = json.loads(line)
                    if ping.get("schema") != 1 or ping.get("type") != "ping":
                        raise ValueError("Only synthetic ping frames are accepted")
                    report["receivedPings"] += 1
                    pong = {"schema": 1, "type": "pong", "session": ping["session"],
                            "sequence": ping["sequence"]}
                    channel.write((json.dumps(pong, separators=(",", ":")) + "\n").encode())
                    report["sentPongs"] += 1
                    save()
                    if report["receivedPings"] == 5:
                        async def disconnect_once():
                            await asyncio.sleep(0.5)
                            if connections:
                                await connections[-1].disconnect()
                                report["forcedDisconnections"] += 1
                                save()
                        asyncio.create_task(disconnect_once())

            channel.sink = received

        channel_number = server.listen(on_channel, channel=1)
        device.sdp_service_records = {0x10001: rfcomm.make_service_sdp_records(
            0x10001, channel_number, core.UUID("00001101-0000-1000-8000-00805F9B34FB"))}
        await device.set_connectable(True)
        await device.set_discoverable(True)
        report["ready"] = True
        save()
        print(json.dumps({"ready": True, "virtualAddress": report["virtualAddress"]}), flush=True)
        try:
            await asyncio.sleep(min(max(options.duration, 15), 600))
        finally:
            report["ready"] = False
            save()


if __name__ == "__main__":
    asyncio.run(main())
