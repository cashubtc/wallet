"""Exercise readiness deadlines and process failure without a live mint."""
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'start-nutshell.sh'


class NutshellStartupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copy(SCRIPT, self.root / SCRIPT.name)
        mint_bin = self.root / '.nutshell-venv/bin'
        mint_bin.mkdir(parents=True)
        self.write_executable(mint_bin / 'mint', '''import os, time
if os.environ.get('MINT_EXIT') == '1':
    raise SystemExit(1)
time.sleep(60)
''')
        bin_path = self.root / 'bin'
        bin_path.mkdir()
        self.write_executable(bin_path / 'curl', '''import json, os, sys
from pathlib import Path
root = Path(os.environ['PROBE_ROOT'])
count_file = root / 'probe-count'
count = int(count_file.read_text()) + 1 if count_file.exists() else 1
count_file.write_text(str(count))
(root / 'probe-args.json').write_text(json.dumps(sys.argv[1:]))
raise SystemExit(0 if count >= int(os.environ['READY_AFTER']) else 7)
''')
        self.env = dict(os.environ, PATH=f'{bin_path}:{os.environ["PATH"]}',
                        PROBE_ROOT=str(self.root), READY_AFTER='3',
                        NUTSHELL_STARTUP_TIMEOUT_SECONDS='5')
        self.addCleanup(self.stop_mint)

    def write_executable(self, path, code):
        path.write_text(f'#!{sys.executable}\n' + code)
        path.chmod(0o755)

    def stop_mint(self):
        pid_file = self.root / '.nutshell.pid'
        if pid_file.exists():
            try:
                os.kill(int(pid_file.read_text()), signal.SIGTERM)
            except ProcessLookupError:
                pass

    def start(self):
        return subprocess.run(['bash', str(self.root / SCRIPT.name), '18338'],
                              env=self.env, capture_output=True, text=True, timeout=15)

    def test_delayed_readiness_succeeds_with_bounded_probes(self):
        result = self.start()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.root / 'probe-count').read_text(), '3')
        args = json.loads((self.root / 'probe-args.json').read_text())
        self.assertEqual(args[args.index('--connect-timeout') + 1], '1')
        self.assertEqual(args[args.index('--max-time') + 1], '2')

    def test_crashed_mint_fails_before_full_deadline(self):
        self.env.update(MINT_EXIT='1', READY_AFTER='999')
        result = self.start()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Nutshell exited before becoming ready', result.stdout)

    def test_live_but_unready_mint_hits_deadline(self):
        self.env.update(READY_AFTER='999', NUTSHELL_STARTUP_TIMEOUT_SECONDS='1')
        result = self.start()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Mint failed to start within 1 seconds', result.stdout)


if __name__ == '__main__':
    unittest.main()
