"""Exercise the actual shard runner without launching Xcode or simulators."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

RUNNER = Path(__file__).resolve().parents[1] / 'run-ios-test-shard.sh'


class IOSShardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        products = self.root / 'Products'
        products.mkdir()
        (products / 'CashuWallet.xctestrun').touch()
        bin_path = self.root / 'bin'
        bin_path.mkdir()
        (bin_path / 'xcrun').write_text('#!/bin/bash\nexit 0\n')
        (bin_path / 'xcrun').chmod(0o755)
        xcode = bin_path / 'xcodebuild'
        xcode.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
Path(os.environ['COMMAND_RECORD']).write_text(json.dumps(sys.argv[1:]))
sys.exit(int(os.environ.get('TEST_EXIT_CODE', '0')))
''')
        xcode.chmod(0o755)
        self.record = self.root / 'command.json'
        self.env = dict(os.environ, PATH=f'{bin_path}:{os.environ["PATH"]}',
                        RUNNER_TEMP=str(self.root), SIMULATOR_UDID='test-simulator',
                        COMMAND_RECORD=str(self.record))

    def run_shard(self, shard):
        return subprocess.run(['bash', str(RUNNER), shard], cwd=self.root,
                              env=self.env, capture_output=True, text=True)

    def test_every_ui_class_is_selected_exactly_once_including_future_classes(self):
        selections = []
        for shard in ['unit', 'ui-lifecycle', 'ui-settings', 'ui-other']:
            with self.subTest(shard=shard):
                self.assertEqual(self.run_shard(shard).returncode, 0)
                args = json.loads(self.record.read_text())
                self.assertEqual(args[0], 'test-without-building')
                self.assertIn(str(self.root / 'Products/CashuWallet.xctestrun'), args)
                self.assertEqual(args[args.index('-parallel-testing-enabled') + 1], 'NO')
                self.assertEqual('-test-timeouts-enabled' in args, shard != 'unit')
                selections.append(([a.split(':', 1)[1] for a in args if a.startswith('-only-testing:')],
                                   [a.split(':', 1)[1] for a in args if a.startswith('-skip-testing:')]))
        for case in ['CashuWalletTests/Payments/testPayment',
                     'CashuWalletUITests/WalletLifecycleUITests/testRestore',
                     'CashuWalletUITests/SettingsUITests/testSetting',
                     'CashuWalletUITests/LiveCdkPaymentUITests/testPayment',
                     'CashuWalletUITests/FutureUITests/testNewJourney']:
            matches = lambda selector: case == selector or case.startswith(selector + '/')
            self.assertEqual(sum(any(map(matches, only)) and not any(map(matches, skip))
                                 for only, skip in selections), 1, case)

    def test_test_failure_is_not_hidden_by_log_pipeline(self):
        self.env['TEST_EXIT_CODE'] = '65'
        self.assertEqual(self.run_shard('ui-other').returncode, 65)

    def test_invalid_shard_or_missing_products_fail_before_xcode(self):
        self.assertNotEqual(self.run_shard('typo').returncode, 0)
        (self.root / 'Products/CashuWallet.xctestrun').unlink()
        self.assertNotEqual(self.run_shard('unit').returncode, 0)
        self.assertFalse(self.record.exists())


if __name__ == '__main__':
    unittest.main()
