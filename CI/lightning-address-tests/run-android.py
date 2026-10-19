"""Run the opt-in network suite on a disposable rooted arm64 emulator."""
import argparse
import re
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--adb", default="adb")
p.add_argument("--apk", type=Path, required=True)
p.add_argument("--test-apk", type=Path, required=True)
a = p.parse_args()

def adb(*args, check=True):
    result = subprocess.run([a.adb, *args], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=check)
    return result.stdout

def rule(*args, check=True):
    return adb("shell", "su", "0", "iptables", "-t", "nat", *args, check=check)

for apk in [a.apk, a.test_apk]:
    assert "Success" in adb("install", "-r", str(apk))
package = "com.cashu.me.debug"
uid = re.search(r"package:" + re.escape(package) + r" uid:(\d+)\b", adb("shell", "cmd", "package", "list", "packages", "-U", package)).group(1)
chain = "WALLET_LNURL_FIXTURE"
ports = [18444, 18445, 15353]
# A pre-existing chain may belong to another run. Fail without altering it.
rule("-N", chain)
try:
    for port in ports:
        adb("reverse", f"tcp:{port}", f"tcp:{port}")
    for host in ["8.8.8.8", "8.8.4.4"]:
        rule("-A", chain, "-p", "tcp", "--dport", "53", "-d", host, "-j", "DNAT", "--to-destination", "127.0.0.1:15353")
    rule("-A", chain, "-p", "tcp", "--dport", "443", "-j", "DNAT", "--to-destination", "127.0.0.1:18445")
    rule("-A", "OUTPUT", "-m", "owner", "--uid-owner", uid, "-j", chain)
    output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", "com.cashu.me.liveintegration.LightningAddressBindingTest", "-e", "lightningFixtures", "true", "-e", "lightningFixtureUrl", "http://127.0.0.1:18444", package + ".test/com.cashu.me.test.CashuUiTestRunner")
    print(output)
    assert "OK (7 tests)" in output, "Native integration tests did not all pass"
finally:
    rule("-D", "OUTPUT", "-m", "owner", "--uid-owner", uid, "-j", chain, check=False)
    rule("-F", chain, check=False)
    rule("-X", chain, check=False)
    for port in ports:
        adb("reverse", "--remove", f"tcp:{port}", check=False)
