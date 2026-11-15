#!/usr/bin/env python3
"""Reuse the pinned PR #5 harness against the exact verified candidate JAR."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

AUDIT_REF = '95bc3d468793d30fe797b7dd4710ff6a42d2913f'
root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--audit-root', type=Path, required=True)
parser.add_argument('--offline', action='store_true', help='Use only cached Gradle dependencies')
args = parser.parse_args()
audit_root = args.audit_root.resolve()
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=audit_root, text=True).strip() == AUDIT_REF
assert not subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'], cwd=audit_root, text=True).strip()
spec = importlib.util.spec_from_file_location('pr5_audit', audit_root / 'tools/audit-packaged-scripts.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
out = root / 'build/candidate-binary-evidence'
out.mkdir(parents=True, exist_ok=True)
audit.out = out
reports = list((root / 'build/libs').glob('*.jar.verification.json'))
assert len(reports) == 1, reports
verification = json.loads(reports[0].read_text())
jar = root / 'build/libs' / verification['jar']
digest = hashlib.sha256(jar.read_bytes()).hexdigest()
assert digest == verification['sha256']
assert jar.with_suffix('.jar.sha256').read_text().strip() == f'{digest}  {jar.name}'

# Source results come from clean build; never overwrite or substitute them.
source_cases, source_failures, source_errors = audit.read_results(root / 'build/test-results/test')
assert len(source_cases) >= 49 and not source_failures and not source_errors
def run_pinned_suite(name, packaged_jar=None):
    """Keep the pinned suite independent from tests added in the current tree."""
    env = dict(os.environ)
    env.pop('WORKSHOP_SCRIPT_JAR', None)
    if packaged_jar is not None:
        env['WORKSHOP_SCRIPT_JAR'] = str(packaged_jar)
    wrapper = audit_root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    command = (['cmd', '/c', str(wrapper)] if os.name == 'nt' else [str(wrapper)])
    command += ['--no-daemon', '--console=plain', 'cleanTest', 'test', '--rerun-tasks']
    if args.offline:
        command.append('--offline')
    run = subprocess.run(command, cwd=audit_root, env=env, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, text=True)
    (out / (name + '.log')).write_text(run.stdout, encoding='utf-8')
    results = audit_root / 'build/test-results/test'
    dest = out / (name + '-test-results')
    assert not dest.exists(), 'Preserve previous evidence before rerunning: ' + str(dest)
    shutil.copytree(results, dest)
    identities, failures, errors = audit.read_results(dest)
    return run.returncode, identities, failures, errors


# Comparing the pinned suite with every current test would reject any later
# language/UI test additions. Establish its own exact identity set first;
# retain the current source suite as independent evidence, with explicit scope.
baseline_rc, expected_cases, baseline_failures, baseline_errors = run_pinned_suite('pinned-source')
required = set().union(*(entry[3] for entry in audit.artifacts))
assert baseline_rc == 0 and len(expected_cases) >= 49 and not baseline_failures and not baseline_errors
assert required <= {name.removesuffix('()') for _, name in expected_cases}
assert expected_cases <= source_cases, 'Current source suite dropped pinned safety contracts'
rc, cases, failures, errors = run_pinned_suite('candidate-binary', jar)
details = audit.detailed_results(out / 'candidate-binary-test-results')
(out / 'candidate-binary-contracts.json').write_text(json.dumps(details, indent=2) + '\n')
report = dict(
    jar=jar.name, sha256=digest, version=verification['version'],
    candidate_checkout=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
    audit_harness_ref=AUDIT_REF, source_tests=len(source_cases),
    binary_script_tests=len(cases), binary_exit_code=rc,
    failures=sorted(failures), errors=errors,
    pinned_source_tests=len(expected_cases),
    missing_tests=sorted(expected_cases - cases), unexpected_tests=sorted(cases - expected_cases),
    additional_current_source_tests=sorted(source_cases - expected_cases),
    **audit.script_evidence(jar),
    scope='Packaged Groovy scripts; Java menu assertions read harness source; QuPath reader checks injected',
    gui_acceptance='NOT RUN', real_slide_acceptance='NOT RUN')
(out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2), flush=True)
assert rc == 0 and cases == expected_cases and not failures and not errors, report
assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest, 'JAR changed during testing'
