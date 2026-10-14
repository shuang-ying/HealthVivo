"""Run the native SPP diagnostic on an AVD, including background and reconnection.

This does not start on a physical phone, read fitness data, or test GT2 RPC.
Start probe-virtual-spp-peer.py and install the diagnostic debug APK first.
"""
import argparse
import json
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="emulator-5556")
parser.add_argument("--adb", default="D:/Android/Sdk/platform-tools/adb.exe")
args = parser.parse_args()
if not re.fullmatch(r"emulator-\d+", args.serial):
    raise ValueError("Only an Android emulator may run this diagnostic")
root = Path(__file__).resolve().parent.parent / "artifacts" / "direct-link-probe"
peer = json.loads((root / "virtual-peer.json").read_text("utf-8-sig"))
if peer.get("ready") is not True:
    raise ValueError("The virtual Bluetooth peer is not ready")


def adb(*commands):
    result = subprocess.run([args.adb, "-s", args.serial, *commands], capture_output=True,
                            text=True, encoding="utf-8", timeout=15)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return result.stdout


def state():
    return json.loads(adb("shell", "run-as", "com.lrh.healthvivo", "cat", "cache/spp-emulator-probe.json"))


def accept_virtual_pairing():
    adb("shell", "uiautomator", "dump", "/sdcard/spp-probe-window.xml")
    nodes = list(ET.fromstring(adb("shell", "cat", "/sdcard/spp-probe-window.xml")).iter("node"))
    if not any("HealthVivo-SPP-SIM" in node.get("text", "") for node in nodes):
        adb("shell", "cmd", "statusbar", "expand-notifications")
        return
    # Only confirm pairing for the deliberately named virtual test device.
    buttons = [node for node in nodes if node.get("text", "").lower() in ("pair", "pair & connect")]
    if buttons:
        bounds = list(map(int, re.findall(r"\d+", buttons[0].get("bounds"))))
        adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))


adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard")
run_id = str(uuid.uuid4())
adb("shell", "am", "start", "-S", "-n", "com.lrh.healthvivo/.debug.SppEmulatorProbeActivity",
    "--es", "peer_address", peer["virtualAddress"], "--es", "run_id", run_id, "--ei", "duration_seconds", "120")
deadline = time.monotonic() + 45
while time.monotonic() < deadline:
    before = state()
    if before.get("runId") == run_id and before["responsesReceived"] >= 2:
        break
    accept_virtual_pairing()
    time.sleep(0.5)
else:
    raise RuntimeError("Virtual SPP handshake did not finish")
adb("shell", "cmd", "statusbar", "collapse")
adb("shell", "input", "keyevent", "KEYCODE_HOME")
adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
power = adb("shell", "dumpsys", "power")
services = adb("shell", "dumpsys", "activity", "services", "com.lrh.healthvivo")
(root / "android-before-background.json").write_text(json.dumps(before, indent=2), "utf-8")
(root / "android-background-power.txt").write_text("\n".join(line for line in power.splitlines() if "mWakefulness=" in line), "utf-8")
(root / "android-background-service.txt").write_text("\n".join(line for line in services.splitlines() if "SppEmulator" in line or "isForeground=true" in line), "utf-8")
print(json.dumps({"phase": "background_and_screen_off", "responsesBefore": before["responsesReceived"]}), flush=True)
deadline = time.monotonic() + 45
while time.monotonic() < deadline:
    time.sleep(2)
    after = state()
    if after.get("runId") == run_id and after["connections"] >= 2 and after["responsesReceived"] >= before["responsesReceived"] + 6:
        break
else:
    raise RuntimeError("Background reception and reconnection did not pass")
(root / "android-after-background.json").write_text(json.dumps(after, indent=2), "utf-8")
peer_after = json.loads((root / "virtual-peer.json").read_text("utf-8-sig"))
result = {"androidEmulator": True, "virtualPeer": "Bumble", "nativeRfcomm": True,
          "foregroundServiceType": "connectedDevice", "responsesBeforeBackground": before["responsesReceived"],
          "responsesAfterBackground": after["responsesReceived"], "connections": after["connections"],
          "forcedPeerDisconnects": peer_after["forcedDisconnections"],
          "screenOff": "Dozing" in power or "Asleep" in power,
          "vivoHealthInstalled": after["vivoHealthInstalled"],
          "healthRecordsRead": after["healthRecordsRead"], "gt2FirmwareVerified": False}
assert result["forcedPeerDisconnects"] == 1
assert result["screenOff"]
(root / "android-spp-result.json").write_text(json.dumps(result, indent=2), "utf-8")
print(json.dumps(result, indent=2), flush=True)
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "am", "start", "-f", "0x18000000", "-n", "com.lrh.healthvivo/.debug.SppEmulatorProbeActivity", "--ez", "stop", "true")
