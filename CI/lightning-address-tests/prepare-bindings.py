"""Build CDK 0.18.3 with additive test CA loading; preserve released language bindings."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tomllib
from zipfile import ZipFile, ZIP_DEFLATED

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--source", type=Path, required=True, help="Clean CDK v0.18.3 source checkout/tarball")
p.add_argument("--workdir", type=Path, required=True)
p.add_argument("--platform", choices=["ios", "android"], required=True)
p.add_argument("--swift-packages", type=Path, help="Existing resolved SwiftPM package directory")
p.add_argument("--android-aar", type=Path, help="Released cdk-android-0.18.3.aar")
p.add_argument("--ndk", type=Path)
a = p.parse_args()
a.source = a.source.resolve()
a.workdir = a.workdir.resolve()
a.workdir.mkdir(parents=True, exist_ok=True)
assert tomllib.loads((a.source / "Cargo.toml").read_text())["workspace"]["package"]["version"] == "0.18.3"
manifest = a.source / "crates/cdk-ffi/Cargo.toml"
original = manifest.read_text()
addition = '\n# Test trust roots only; no CDK logic changes.\nbitreq = { version = "=0.3.7", default-features = false, features = ["async-https-rustls-probe"] }'
if addition not in original:
    assert "\nbitreq = " not in original, "Use clean CDK v0.18.3 sources"
    manifest.write_text(original.replace("[dependencies]", "[dependencies]" + addition, 1))

target = "aarch64-apple-ios-sim" if a.platform == "ios" else "aarch64-linux-android"
env = dict(os.environ)
if a.platform == "android":
    assert a.ndk and a.android_aar
    tools = a.ndk.resolve() / "toolchains/llvm/prebuilt/darwin-x86_64/bin"
    env.update(CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=str(tools / "aarch64-linux-android26-clang"),
               CC_aarch64_linux_android=str(tools / "aarch64-linux-android26-clang"),
               AR_aarch64_linux_android=str(tools / "llvm-ar"))
subprocess.run(["cargo", "build", "--manifest-path", str(a.source / "Cargo.toml"), "-p", "cdk-ffi", "--release", "--target", target], env=env, check=True)
native = a.source / "target" / target / "release"
if a.platform == "ios":
    assert a.swift_packages
    packages = a.workdir / "packages"
    assert not packages.exists(), "Choose a fresh workdir; never overwrite a shared package cache"
    # APFS clone keeps the large released XCFrameworks isolated without duplicating blocks.
    subprocess.run(["cp", "-cR", str(a.swift_packages.resolve()) + "/.", str(packages)], check=True)
    state = packages / "workspace-state.json"
    data = json.loads(state.read_text())
    for artifact in data["object"]["artifacts"]:
        old = Path(artifact["path"])
        artifact["path"] = str(packages / "artifacts" / Path(*old.parts[old.parts.index("artifacts") + 1:]))
    state.write_text(json.dumps(data, indent=2))
    framework = packages / "artifacts/cdk-swift/CashuDevKitFFI/CashuDevKitFFI.xcframework/ios-arm64_x86_64-simulator/CashuDevKitFFI.framework/CashuDevKitFFI"
    shutil.copyfile(native / "libcdk_ffi.a", framework)
else:
    with ZipFile(a.android_aar) as source, ZipFile(a.workdir / "cdk-fixture.aar", "w", ZIP_DEFLATED) as destination:
        for info in source.infolist():
            destination.writestr(info, (native / "libcdk_ffi.so").read_bytes()
                if info.filename == "jni/arm64-v8a/libcdk_ffi_kotlin.so" else source.read(info.filename))
print("Prepared isolated", a.platform, "fixture binding")
