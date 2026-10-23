"""Offline checks for accounting boundaries and private snapshot handling."""

import hashlib
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

from audit_snapshot import summarize


class AuditSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.db = self.root / "snapshot.db"
        with closing(sqlite3.connect(self.db)) as connection, connection:
            connection.executescript("""
                CREATE TABLE proof (mint_url TEXT, state TEXT, unit TEXT, amount INTEGER, secret TEXT);
                CREATE TABLE transactions (id BLOB, mint_url TEXT, direction TEXT,
                    amount INTEGER, fee INTEGER, unit TEXT, status TEXT, request TEXT);
                CREATE TABLE mint_quote (id TEXT, state TEXT, secret_key TEXT);
                CREATE TABLE wallet_sagas (id TEXT, kind TEXT, state TEXT, data TEXT);
            """)
            connection.executemany("INSERT INTO proof VALUES (?,?,?,?,?)", [
                ("mint-a", "UNSPENT", "sat", 10, "PROOF_SECRET"),
                ("mint-b", "UNSPENT", "sat", 8, "PROOF_SECRET"),
                ("mint-a", "PENDING", "sat", 144, "PROOF_SECRET"),
                ("mint-a", "PENDING_SPENT", "sat", 124, "PROOF_SECRET"),
                ("mint-a", "SPENT", "sat", 2, "PROOF_SECRET"),
                ("mint-a", "UNSPENT", "usd", 3, "PROOF_SECRET"),
            ])
            connection.execute("INSERT INTO transactions VALUES (?,?,?,?,?,?,?,?)",
                               (b"tx", "mint-a", "Outgoing", 8, 2, "sat", "completed", "PRIVATE_INVOICE"))
            connection.execute("INSERT INTO mint_quote VALUES ('q','ISSUED','PRIVATE_KEY')")
            connection.execute("INSERT INTO wallet_sagas VALUES ('s','receive','swap_requested','SAGA_SECRET')")

    def test_spendable_excludes_pending_and_spent_and_separates_units(self):
        result = summarize(self.db)
        self.assertEqual(result["spendable_by_unit"], {"sat": 18, "usd": 3})
        self.assertEqual(result["transactions"][0]["fee"], 2)
        self.assertEqual(len(result["balances"]), 6)

    def test_no_secret_export_or_main_database_mutation(self):
        before = hashlib.sha256(self.db.read_bytes()).digest()
        encoded = json.dumps(summarize(self.db))
        for secret in ("PROOF_SECRET", "PRIVATE_INVOICE", "PRIVATE_KEY", "SAGA_SECRET"):
            self.assertNotIn(secret, encoded)
        self.assertEqual(before, hashlib.sha256(self.db.read_bytes()).digest())
        self.assertIn("unavailable", encoded)

    def test_unsupported_required_schema_fails(self):
        with closing(sqlite3.connect(self.db)) as connection, connection:
            connection.execute("ALTER TABLE proof DROP COLUMN amount")
        with self.assertRaisesRegex(ValueError, "Unsupported proof schema"):
            summarize(self.db)

    def test_committed_wal_value_is_included(self):
        with closing(sqlite3.connect(self.db)) as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("INSERT INTO proof VALUES ('mint-b','UNSPENT','sat',7,'WAL_SECRET')")
            connection.commit()
            self.assertTrue(Path(str(self.db) + "-wal").exists())
            result = summarize(self.db)
            self.assertEqual(result["spendable_by_unit"]["sat"], 25)
            self.assertNotIn("WAL_SECRET", json.dumps(result))

    def test_cli_private_output_and_no_overwrite(self):
        output = self.root / "audit.json"
        command = [sys.executable, str(Path(__file__).with_name("audit_snapshot.py")),
                   str(self.db), "--output", str(output)]
        first = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual(output.stat().st_mode & 0o777, 0o600)
        before = output.read_bytes()
        self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
        self.assertEqual(output.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
