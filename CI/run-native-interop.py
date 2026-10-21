#!/usr/bin/env python3
"""Pair released Swift/Kotlin wallet bindings against the two local fake mints.

Build iOS test products and start mints/payment fixtures first. Android's Gradle
task builds/installs its current sources. Each mint gets its own shared mailbox;
only actual native wallet code constructs and redeems tokens.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import time
import urllib.request


def request(url, method="GET"):
    with urllib.request.urlopen(urllib.request.Request(url, method=method), timeout=15) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--xctestrun", type=Path, required=True)
    parser.add_argument("--ios-device", required=True)
    parser.add_argument("--android-sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.android_sdk is None:
        parser.error("Set ANDROID_HOME or pass --android-sdk")
    root = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    adb = args.android_sdk / "platform-tools/adb"
    fixture = "http://127.0.0.1:3341"
    request(fixture + "/health")
    versions = {name: request(f"http://127.0.0.1:{port}/v1/info")["version"]
                for name, port in [("nutshell", 3338), ("cdk", 3339)]}
    previous_reverse = subprocess.check_output([str(adb), "reverse", "--list"], text=True)
    old = {parts[1]: parts[2] for line in previous_reverse.splitlines() if len(parts := line.split()) == 3}
    ports = [3338, 3339, 3341]
    summaries = []
    try:
        for port in ports:
            subprocess.run([str(adb), "reverse", f"tcp:{port}", f"tcp:{port}"], check=True)
        for mint in ["cdk", "nutshell"]:
            key = request(fixture + "/sessions", "POST")["id"]
            ios_method = "test" + mint.capitalize() + "NativePlatformInteroperability"
            android_method = mint + "NativePlatformInteroperability"
            commands = {
                "ios": ["xcodebuild", "test-without-building", "-xctestrun", str(args.xctestrun.resolve()),
                        "-destination", "platform=iOS Simulator,id=" + args.ios_device,
                        "-only-testing:CashuWalletTests/CrossPlatformIntegrationTests/" + ios_method,
                        "-parallel-testing-enabled", "NO", "-resultBundlePath", str(output / (mint + "-ios.xcresult"))],
                "android": [str(root / "android/gradlew"), "--no-daemon", ":app:connectedDebugAndroidTest",
                            "-Pandroid.testInstrumentationRunnerArguments.class=com.cashu.me.liveintegration.CrossPlatformInteropTest#" + android_method,
                            "-Pandroid.testInstrumentationRunnerArguments.cashu.paymentFixtures=true",
                            "-Pandroid.testInstrumentationRunnerArguments.cashu.paymentFixtureUrl=" + fixture,
                            "-Pandroid.testInstrumentationRunnerArguments.cashu.interopSessionId=" + key],
            }
            env = dict(os.environ, TEST_RUNNER_PAYMENT_FIXTURE_URL=fixture,
                       TEST_RUNNER_INTEROP_SESSION_ID=key, ANDROID_HOME=str(args.android_sdk),
                       ANDROID_SDK_ROOT=str(args.android_sdk))
            processes, logs = {}, []
            try:
                for platform, command in commands.items():
                    log = (output / f"{mint}-{platform}.log").open("w")
                    logs.append(log)
                    processes[platform] = subprocess.Popen(command, cwd=root / "android" if platform == "android" else root,
                                                           env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                deadline = time.monotonic() + 600
                while any(p.poll() is None for p in processes.values()):
                    if time.monotonic() > deadline or any(p.poll() not in (None, 0) for p in processes.values()):
                        break
                    time.sleep(1)
            finally:
                for process in processes.values():
                    if process.poll() is None:
                        os.killpg(process.pid, signal.SIGTERM)
                        try:
                            process.wait(timeout=15)
                        except subprocess.TimeoutExpired:
                            os.killpg(process.pid, signal.SIGKILL)
                            process.wait(timeout=15)
                for log in logs:
                    log.close()
                request(fixture + "/sessions/" + key, "DELETE")
            result = {"mint": mint, "exit_codes": {name: p.returncode for name, p in processes.items()}}
            android_results = root / "android/app/build/outputs/androidTest-results/connected"
            if android_results.exists():
                shutil.copytree(android_results, output / (mint + "-android-results"))
            summaries.append(result)
            print(json.dumps(result), flush=True)
    finally:
        for port in ports:
            local = f"tcp:{port}"
            command = [str(adb), "reverse", local, old[local]] if local in old else [str(adb), "reverse", "--remove", local]
            subprocess.run(command, check=False, stdout=subprocess.DEVNULL)
        (output / "summary.json").write_text(json.dumps({"mint_versions": versions, "results": summaries}, indent=2) + "\n")
    return 0 if len(summaries) == 2 and all(len(s["exit_codes"]) == 2 and all(c == 0 for c in s["exit_codes"].values()) for s in summaries) else 1


if __name__ == "__main__":
    raise SystemExit(main())
