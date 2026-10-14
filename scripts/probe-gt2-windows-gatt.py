"""Uncached Windows GATT check of the dedicated GT2 test app, never health data.

Uses bleak 0.22.3 in the isolated Bluetooth probe venv. The caller manages and
restores the Windows radio state. No pairing, private API or OrbitV RPC.
"""
import asyncio
import argparse
import hashlib
import json
import re
import secrets
import time
from pathlib import Path

from bleak import BleakClient, BleakScanner

SERVICE = "fa954400-48d6-4a12-b74b-4c5b5170360e"
CHARACTERISTIC = "fa954401-48d6-4a12-b74b-4c5b5170360e"


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog-only", action="store_true")
    parser.add_argument("--report-name", help="Metadata report filename within artifacts/direct-link-probe")
    options = parser.parse_args()
    if options.report_name and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*\.json", options.report_name):
        parser.error("report-name must be a JSON filename without path separators")
    report = {"transport": "Windows native BLE / WinRT", "syntheticOnly": True,
              "healthRecordsRead": 0, "useCachedServices": False, "pongMatches": 0,
              "observedAtMs": int(time.time() * 1000), "passed": False}
    ready = asyncio.Event()
    selected = None

    def advertisement(device, data):
        nonlocal selected
        if selected is not None:
            return
        own_service = SERVICE in [value.lower() for value in data.service_uuids]
        gt2_name = re.search(r"watch\s*gt\s*2", data.local_name or device.name or "", re.I)
        if own_service or gt2_name:
            selected = device
            report["deviceTag"] = hashlib.sha256(device.address.upper().encode("ascii")).hexdigest()[:12]
            report["gt2AdvertisementFound"] = True
            report["testServiceAdvertised"] = own_service
            report["advertisedServiceUuids"] = data.service_uuids
            ready.set()

    try:
        async with BleakScanner(detection_callback=advertisement, scanning_mode="active"):
            await asyncio.wait_for(ready.wait(), 20)
        async with BleakClient(selected, timeout=15, winrt={"use_cached_services": False}) as client:
            report["connected"] = bool(client.is_connected)
            report["serviceUuids"] = [service.uuid for service in client.services]
            service = client.services.get_service(SERVICE)
            characteristic = service.get_characteristic(CHARACTERISTIC) if service else None
            report["testCharacteristicFound"] = characteristic is not None
            if options.catalog_only:
                report["catalogOnly"] = True
                return
            if not characteristic:
                raise RuntimeError("Uncached GATT directory does not contain the test characteristic")
            greeting = await client.read_gatt_char(characteristic, use_cached=False)
            previous_pong = bool(re.fullmatch(rb"PONG:[0-9A-F]{6}:[0-9]{2}", bytes(greeting)))
            report["greetingVerified"] = bytes(greeting) == b"PROBE:1" or previous_pong
            report["previousPongGreeting"] = previous_pong
            if not report["greetingVerified"]:
                raise RuntimeError("Test greeting does not match; no data written")
            session = secrets.token_hex(3).upper()
            for sequence in range(1, 3):
                suffix = f"{session}:{sequence:02d}".encode("ascii")
                await client.write_gatt_char(characteristic, b"PING:" + suffix, response=True)
                value = await client.read_gatt_char(characteristic, use_cached=False)
                if bytes(value) != b"PONG:" + suffix:
                    raise RuntimeError("Synthetic PONG session/sequence does not match")
                report["pongMatches"] = sequence
            report["passed"] = True
    except asyncio.TimeoutError:
        report["error"] = "Native Windows scan or GATT operation timed out"
    except asyncio.CancelledError:
        report["error"] = "Overall 55-second GATT deadline reached"
    except Exception as error:
        report["error"] = re.sub(r"(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}", "[device]", str(error))
    finally:
        report["completed"] = True
        filename = options.report_name or ("gt2-windows-catalog.json" if options.catalog_only else "gt2-windows-uncached-gatt.json")
        destination = Path(__file__).resolve().parent.parent / "artifacts/direct-link-probe" / filename
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(report, indent=2), flush=True)


asyncio.run(asyncio.wait_for(main(), 55))
