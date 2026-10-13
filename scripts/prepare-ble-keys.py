"""Private paired build identity. Never print key material; reuse it across updates."""
import base64
import json
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

root = Path(__file__).resolve().parent.parent
local = root / ".local"
local.mkdir(exist_ok=True)
key_file = local / "watch-ble-identity.json"
if key_file.exists():
    identity = json.loads(key_file.read_text(encoding="utf-8"))
    key = serialization.load_der_private_key(base64.b64decode(identity["privateKey"]), password=None)
else:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    identity = {
        "privateKey": base64.b64encode(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())).decode(),
        "publicKey": base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
    }
    key_file.write_text(json.dumps(identity), encoding="utf-8")
public = base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
if public != identity["publicKey"]:
    raise ValueError("Paired identity mismatch")
(root / "android" / "ble-watch.local.properties").write_text("publicKey=" + public + "\n", encoding="ascii")
(root / "watch" / "src" / "common" / "ble.local.js").write_text("export default " + json.dumps({"privateKey": identity["privateKey"]}) + "\n", encoding="utf-8")
print("Private BLE build identity ready; reused across companion updates.")
