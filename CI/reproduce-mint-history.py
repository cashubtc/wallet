#!/usr/bin/env python3
"""Reproduce BOLT11 retry history using an isolated CDK v0.18.0 checkout.

Usage: python3 CI/reproduce-mint-history.py /path/to/cdk-repository
Requires Rust and CDK's build prerequisites/dependencies. Honors CARGO_TARGET_DIR.
The supplied repository and the wallet's CDK dependencies are never modified.
"""

import pathlib
import subprocess
import sys
import tempfile


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    repository = pathlib.Path(sys.argv[1]).resolve()
    test = pathlib.Path(__file__).with_name("mint-history-retry-test.rs").read_text()
    with tempfile.TemporaryDirectory(prefix="mint-history-repro-") as directory:
        root = pathlib.Path(directory)
        archive = subprocess.run(
            ["git", "-C", str(repository), "archive", "v0.18.0"],
            check=True, capture_output=True,
        ).stdout
        subprocess.run(["tar", "-x", "-C", directory], input=archive, check=True)
        module = root / "crates/cdk/src/wallet/issue/saga/mod.rs"
        source = module.read_text()
        marker = "    fn legacy_mint_quote_msg_to_sign("
        if source.count(marker) != 1:
            raise RuntimeError("Unexpected CDK test module layout")
        module.write_text(source.replace(marker, test + "\n" + marker, 1))
        subprocess.run([
            "cargo", "test", "--manifest-path", str(root / "Cargo.toml"),
            "-p", "cdk", "--lib",
            "wallet_report_repeated_bolt11_attempts_create_distinct_history_rows",
            "--", "--nocapture",
        ], check=True)


if __name__ == "__main__":
    main()
