#!/usr/bin/env python3
"""Summarize a consistent archived CDK SQLite snapshot; never connect to a mint.

Only explicitly selected non-secret columns are exported. Inspect the output
before sharing: mint URLs and quote/transaction identifiers may still be private.
This does not create a consistent snapshot or establish payment settlement.
"""

import argparse
import json
from pathlib import Path
import sqlite3
import sys


REQUIRED = {
    "proof": ("mint_url", "state", "unit", "amount"),
    "transactions": ("id", "mint_url", "direction", "amount", "fee", "unit", "status"),
}
OPTIONAL = {
    "mint_quote": ("id", "mint_url", "amount", "unit", "state", "amount_paid", "amount_issued", "payment_method"),
    "melt_quote": ("id", "mint_url", "amount", "unit", "state", "fee_reserve", "payment_method"),
    "wallet_sagas": ("id", "kind", "state", "amount", "mint_url", "unit", "quote_id"),
}


def columns(db, table):
    # Table names are internal constants, never command-line or database values.
    return {row[1] for row in db.execute(f'PRAGMA table_info("{table}")')}


def value(item):
    if isinstance(item, bytes):
        return item.hex()
    return item


def records(db, table, selected):
    fields = ", ".join(f'"{field}"' for field in selected)
    return [{field: value(item) for field, item in zip(selected, row)}
            for row in db.execute(f'SELECT {fields} FROM "{table}"')]


def summarize(path):
    path = Path(path).resolve(strict=True)
    # mode=ro prevents creation or mutation of the main database. Use an archived
    # copy with its matching sidecars; immutable=1 would incorrectly ignore WAL.
    db = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)
    try:
        db.execute("PRAGMA query_only=ON")
        db.execute("BEGIN")
        integrity = [row[0] for row in db.execute("PRAGMA integrity_check")]
        if integrity != ["ok"]:
            raise ValueError("Snapshot failed SQLite integrity check")
        for table, fields in REQUIRED.items():
            missing = set(fields) - columns(db, table)
            if missing:
                raise ValueError(f"Unsupported {table} schema; missing columns: {', '.join(sorted(missing))}")
        balances = [dict(zip(("mint_url", "state", "unit", "amount", "proofs"), row))
                    for row in db.execute(
                        "SELECT mint_url, state, unit, SUM(amount), COUNT(*) FROM proof "
                        "GROUP BY mint_url, state, unit ORDER BY mint_url, state, unit")]
        totals = {}
        for row in balances:
            if row["state"] == "UNSPENT":
                totals[row["unit"]] = totals.get(row["unit"], 0) + row["amount"]
        output = {"integrity": "ok", "balances": balances,
                  "spendable_by_unit": totals,
                  "transactions": records(db, "transactions", REQUIRED["transactions"]),
                  "schema_notes": [],
                  "limitations": ["Only UNSPENT proofs counted as spendable.",
                                  "Pending value needs operation-level reconciliation; never sum blindly.",
                                  "No mint settlement or fee correctness inferred from this snapshot."]}
        for table, fields in OPTIONAL.items():
            available = columns(db, table)
            selected = [field for field in fields if field in available]
            if not selected:
                output["schema_notes"].append(f"{table}: unavailable")
                continue
            output[table] = records(db, table, selected)
            missing = set(fields) - available
            if missing:
                output["schema_notes"].append(f"{table}: omitted unavailable columns {', '.join(sorted(missing))}")
        return output
    finally:
        db.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("--output", type=Path, required=True,
                        help="New private JSON file; existing output is never overwritten")
    args = parser.parse_args()
    try:
        result = summarize(args.snapshot)
        # Exclusive creation avoids replacing evidence, including via symlinks.
        import os
        descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            json.dump(result, stream, indent=2, sort_keys=True)
            stream.write("\n")
    except (OSError, ValueError, sqlite3.Error) as error:
        print(f"Audit failed: {error}", file=sys.stderr)
        return 1
    print("Snapshot audit saved privately; review schema notes and limitations.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
