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
summary = []
for name, jar, expected_digest, required_failures in artifacts:
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    assert digest == expected_digest, (name, digest)
    env = dict(os.environ, WORKSHOP_SCRIPT_JAR=str(jar))
    run = subprocess.run(['./gradlew', '--no-daemon', 'test', '--rerun-tasks'], cwd=root,
                         env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (out / f'{name}.log').write_text(run.stdout)
    dest = out / f'{name}-test-results'
    shutil.copytree(root / 'build/test-results/test', dest, dirs_exist_ok=True)
    cases = [c for p in dest.glob('TEST-*.xml') for c in ET.parse(p).getroot().findall('testcase')]
    failures = {c.attrib['name'].removesuffix('()') for c in cases if c.find('failure') is not None}
    errors = [c.attrib['name'] for c in cases if c.find('error') is not None]
    record = dict(artifact=name, sha256=digest, tests=len(cases), failures=sorted(failures), errors=errors)
    summary.append(record)
    (out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(record, indent=2), flush=True)
    assert run.returncode != 0 and required_failures <= failures and not errors, record
    assert len(cases) == 22, record
# Restore ordinary regression results and require the corrected source to pass.
env = dict(os.environ)
env.pop('WORKSHOP_SCRIPT_JAR', None)
subprocess.run(['./gradlew', '--no-daemon', 'test', '--rerun-tasks'], cwd=root, env=env, check=True)
shutil.copytree(root / 'build/test-results/test', out / 'corrected-test-results', dirs_exist_ok=True)
