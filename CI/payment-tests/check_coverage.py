#!/usr/bin/env python3
"""Fail if a required payment test is absent, skipped, or unsuccessful."""
import argparse
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def android_results(directory):
    results = {}
    for path in Path(directory).rglob('TEST-*.xml'):
        for case in ET.parse(path).iter('testcase'):
            key = case.get('classname', '').rsplit('.', 1)[-1] + '/' + case.get('name', '').removesuffix('()')
            passed = not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped'))
            results.setdefault(key, []).append(passed)
    return results


def ios_test_nodes(data):
    """Read xcresulttool JSON on macOS or in the Linux aggregation job."""
    results = {}
    def walk(node):
        if node.get('nodeType') == 'Test Case':
            key = node['nodeIdentifier'].removesuffix('()')
            results.setdefault(key, []).append(node.get('result') == 'Passed')
        for child in node.get('children', []):
            walk(child)
    for node in data['testNodes']:
        walk(node)
    return results


def ios_results(bundles, *, exported_json=False):
    results = {}
    for bundle in bundles:
        if exported_json:
            data = json.loads(Path(bundle).read_text())
        else:
            data = read_ios_bundle(bundle)
        for name, attempts in ios_test_nodes(data).items():
            results.setdefault(name, []).extend(attempts)
    return results


def read_ios_bundle(bundle):
    if not (Path(bundle) / 'Info.plist').is_file():
        raise ValueError(
            f'Incomplete iOS test result bundle: {Path(bundle).name}. '
            'The test run may have timed out or been interrupted; inspect the test log.')
    return json.loads(subprocess.check_output([
        'xcrun', 'xcresulttool', 'get', 'test-results', 'tests', '--path', bundle]))


def missing_tests(manifest, platform, tier, results):
    required = manifest[platform]['pr'] + (manifest[platform]['full'] if tier == 'full' else [])
    return [name for name in required if not results.get(name) or not all(results[name])]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', choices=['ios', 'android'], required=True)
    parser.add_argument('--tier', choices=['pr', 'full'], default='pr')
    parser.add_argument('--ios-json', action='store_true', help='Read exported xcresulttool test JSON instead of bundles')
    parser.add_argument('results', nargs='+')
    args = parser.parse_args()
    if args.ios_json and args.platform != 'ios':
        parser.error('--ios-json requires --platform ios')
    manifest = json.loads(Path(__file__).with_name('coverage.json').read_text())
    try:
        results = ios_results(args.results, exported_json=args.ios_json) if args.platform == 'ios' else android_results(args.results[0])
    except (ValueError, OSError, KeyError) as error:
        parser.exit(1, f'{error}\n')
    missing = missing_tests(manifest, args.platform, args.tier, results)
    if missing:
        parser.exit(1, 'Required payment tests missing, skipped, or failed:\n' + '\n'.join(missing) + '\n')
    count = len(manifest[args.platform]['pr']) + (len(manifest[args.platform]['full']) if args.tier == 'full' else 0)
    print(f'Payment coverage: {count} required tests passed ({args.platform}/{args.tier}).')


if __name__ == '__main__':
    main()
