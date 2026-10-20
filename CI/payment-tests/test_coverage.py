import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
from check_coverage import android_results, ios_results, missing_tests


class CoverageGateTests(unittest.TestCase):
    def test_incomplete_ios_bundle_reports_interrupted_run(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory, 'UITests.xcresult')
            for exists in [False, True]:
                if exists:
                    bundle.mkdir()
                with self.subTest(directory_exists=exists):
                    with patch('check_coverage.subprocess.check_output') as reader:
                        with self.assertRaisesRegex(ValueError, 'Incomplete.*UITests.xcresult.*interrupted'):
                            ios_results([str(bundle)])
                        reader.assert_not_called()

    def test_ios_results_retain_failures_and_skips(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'Info.plist').touch()
            payload = b'''{"testNodes":[{"children":[
              {"nodeType":"Test Case","nodeIdentifier":"Payments/paid()","result":"Passed"},
              {"nodeType":"Test Case","nodeIdentifier":"Payments/retry()","result":"Failed"},
              {"nodeType":"Test Case","nodeIdentifier":"Payments/retry()","result":"Passed"},
              {"nodeType":"Test Case","nodeIdentifier":"Payments/skipped()","result":"Skipped"}
            ]}]}'''
            with patch('check_coverage.subprocess.check_output', return_value=payload):
                self.assertEqual(ios_results([directory]), {
                    'Payments/paid': [True], 'Payments/retry': [False, True], 'Payments/skipped': [False]})

    def test_missing_skipped_and_failed_are_not_coverage(self):
        manifest = {'android': {'pr': ['Payments/a', 'Payments/b'], 'full': ['Payments/c']}}
        self.assertEqual(missing_tests(manifest, 'android', 'pr', {}), ['Payments/a', 'Payments/b'])
        self.assertEqual(missing_tests(manifest, 'android', 'pr', {'Payments/a': [True], 'Payments/b': [False]}), ['Payments/b'])
        self.assertEqual(missing_tests(manifest, 'android', 'full', {'Payments/a': [True], 'Payments/b': [True]}), ['Payments/c'])
        self.assertEqual(missing_tests(manifest, 'android', 'pr', {'Payments/a': [True], 'Payments/b': [True]}), [])

    def test_junit_skip_failure_and_mixed_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'TEST-payments.xml').write_text('''<testsuite>
              <testcase classname="app.Payments" name="paid"/>
              <testcase classname="app.Payments" name="skipped"><skipped/></testcase>
              <testcase classname="app.Payments" name="retry"><failure/></testcase>
              <testcase classname="app.Payments" name="retry"/>
            </testsuite>''')
            self.assertEqual(android_results(directory), {
                'Payments/paid': [True], 'Payments/skipped': [False], 'Payments/retry': [False, True]})

    def test_exported_ios_shards_merge_without_masking_failures(self):
        import json
        with tempfile.TemporaryDirectory() as directory:
            shards = []
            for index, cases in enumerate([
                [('Payments/paid()', 'Passed'), ('Payments/retry()', 'Failed')],
                [('UI/journey()', 'Passed'), ('Payments/retry()', 'Passed')],
                [('UI/skipped()', 'Skipped')],
            ]):
                path = Path(directory, f'{index}.json')
                path.write_text(json.dumps({'testNodes': [{'children': [
                    {'nodeType': 'Test Case', 'nodeIdentifier': name, 'result': result}
                    for name, result in cases
                ]}]}))
                shards.append(path)
            with patch('check_coverage.subprocess.check_output') as reader:
                results = ios_results(shards, exported_json=True)
                reader.assert_not_called()
            self.assertEqual(results, {
                'Payments/paid': [True], 'Payments/retry': [False, True],
                'UI/journey': [True], 'UI/skipped': [False]})
            manifest = {'ios': {'pr': ['Payments/paid', 'Payments/retry', 'UI/journey'], 'full': []}}
            self.assertEqual(missing_tests(manifest, 'ios', 'pr', results), ['Payments/retry'])

    def test_missing_or_truncated_ios_shard_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, 'missing.json')
            with self.assertRaises(FileNotFoundError):
                ios_results([path], exported_json=True)
            path.write_text('{')
            with self.assertRaises(ValueError):
                ios_results([path], exported_json=True)
            path.write_text('{}')
            with self.assertRaises(KeyError):
                ios_results([path], exported_json=True)
