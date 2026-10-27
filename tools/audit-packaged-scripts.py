#!/usr/bin/env python3
"""Run safety contracts on pinned actual JAR scripts; known unsafe binaries must fail."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
out = root / 'audit-artifacts'
artifacts = [
    ('published-rc6', out / 'published-rc6.jar',
     '9509d3245c6e3df388066c1f213b6afdf6826a80296f30323fce822e42c6649d',
     {'testLegacyTemporaryFileIsNeverDeleted', 'testLateCancellationCannotPublishOutput',
      'testHistoInvalidOrMissingResultsCannotDeleteExistingWork',
      'testIncompleteAuditIsUncertainAndCancellationDoesNotLoadDependencies'}),
    ('validated-pr3', out / 'candidate/qupath-extension-workshop-0.2.2-rc6.jar',
     'aa618da3f757e4cdd193eceaf64c6e2e7d15eba21d734ae87a265245bf3d3c0e',
     {'testHistoSkippedInvalidFeaturesCannotBecomeEmptyReplacement'}),
]
def read_results(directory):
    """Use class + method identities; counts alone cannot detect missing tests."""
    cases = [c for p in directory.glob('TEST-*.xml')
             for c in ET.parse(p).getroot().findall('testcase')]
    identities = [(c.attrib['classname'], c.attrib['name']) for c in cases]
    assert identities and len(set(identities)) == len(identities), identities
    assert not any(c.find('skipped') is not None for c in cases), 'Skipped safety contracts'
    return (set(identities),
            {c.attrib['name'].removesuffix('()') for c in cases if c.find('failure') is not None},
            [c.attrib['name'] for c in cases if c.find('error') is not None])


def run_suite(name, jar=None):
    # Never allow XML from an earlier run to satisfy a failed/incomplete invocation.
    results = root / 'build/test-results/test'
    shutil.rmtree(results, ignore_errors=True)
    env = dict(os.environ)
    env.pop('WORKSHOP_SCRIPT_JAR', None)
    if jar is not None:
        env['WORKSHOP_SCRIPT_JAR'] = str(jar)
    run = subprocess.run(['./gradlew', '--no-daemon', 'test', '--rerun-tasks'], cwd=root,
                         env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (out / f'{name}.log').write_text(run.stdout)
    dest = out / f'{name}-test-results'
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(results, dest)
    identities, failures, errors = read_results(dest)
    return run.returncode, identities, failures, errors


def main():
    out.mkdir(parents=True, exist_ok=True)
    # A passing corrected implementation defines the suite for THIS checkout.
    # Keep the original 22-contract floor; new tests need no harness count edit.
    rc, expected_cases, failures, errors = run_suite('corrected-baseline')
    required = set().union(*(a[3] for a in artifacts))
    assert rc == 0 and not failures and not errors, (rc, failures, errors)
    assert len(expected_cases) >= 22, expected_cases
    assert required <= {name.removesuffix('()') for _, name in expected_cases}, expected_cases
    summary = []
    for name, jar, expected_digest, required_failures in artifacts:
        digest = hashlib.sha256(jar.read_bytes()).hexdigest()
        assert digest == expected_digest, (name, digest)
        rc, cases, failures, errors = run_suite(name, jar)
        record = dict(artifact=name, sha256=digest, tests=len(cases),
                      failures=sorted(failures), errors=errors)
        summary.append(record)
        (out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        print(json.dumps(record, indent=2), flush=True)
        assert rc != 0 and required_failures <= failures and not errors, record
        assert cases == expected_cases, dict(artifact=name,
            missing=sorted(expected_cases - cases), unexpected=sorted(cases - expected_cases))
    # Restore ordinary regression results and require corrected source to pass again.
    rc, cases, failures, errors = run_suite('corrected')
    assert rc == 0 and cases == expected_cases and not failures and not errors, (rc, failures, errors)
    summary.append(dict(artifact='corrected-source', tests=len(cases), failures=[], errors=[]))
    (out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')


if __name__ == '__main__':
    main()
