"""Compile the simulator-only socket redirect and enable the opt-in XCTest suite."""
import argparse
from pathlib import Path
import plistlib
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--xctestrun", type=Path, required=True)
p.add_argument("--workdir", type=Path, required=True)
a = p.parse_args()
a.workdir = a.workdir.resolve()
library = a.workdir / "redirect-dns.dylib"
subprocess.run(["xcrun", "--sdk", "iphonesimulator", "clang", "-arch", "arm64", "-mios-simulator-version-min=18.0", "-dynamiclib",
    str(Path(__file__).with_name("redirect-dns.c")), "-o", str(library)], check=True)
data = plistlib.loads(a.xctestrun.read_bytes())
if "TestConfigurations" in data:
    targets = [t for c in data["TestConfigurations"] for t in c["TestTargets"] if t["BlueprintName"] == "CashuWalletTests"]
else:
    targets = [data["CashuWalletTests"]]
for target in targets:
    env = target.setdefault("EnvironmentVariables", {})
    env.update(LIGHTNING_FIXTURE_URL="http://127.0.0.1:18444", LIGHTNING_DNS_STUB="1", SSL_CERT_FILE=str(a.workdir / "fixtures/ca.pem"))
    prior = env.get("DYLD_INSERT_LIBRARIES", "").split(":")
    env["DYLD_INSERT_LIBRARIES"] = ":".join(dict.fromkeys([str(library)] + [item for item in prior if item]))
a.xctestrun.write_bytes(plistlib.dumps(data))
print("Enabled LightningAddressBindingTests fixture environment")
