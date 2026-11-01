#!/usr/bin/env python3
"""Reuse the pinned PR #5 harness against the exact verified candidate JAR."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess

AUDIT_REF = '95bc3d468793d30fe797b7dd4710ff6a42d2913f'
root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--audit-root', type=Path, required=True)
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
rc, cases, failures, errors = audit.run_suite('candidate-binary', jar)
details = audit.detailed_results(out / 'candidate-binary-test-results')
(out / 'candidate-binary-contracts.json').write_text(json.dumps(details, indent=2) + '\n')
report = dict(
    jar=jar.name, sha256=digest, version=verification['version'],
    candidate_checkout=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
    audit_harness_ref=AUDIT_REF, source_tests=len(source_cases),
    binary_script_tests=len(cases), binary_exit_code=rc,
    failures=sorted(failures), errors=errors,
    missing_tests=sorted(source_cases - cases), unexpected_tests=sorted(cases - source_cases),
    **audit.script_evidence(jar),
    scope='Packaged Groovy scripts; Java menu assertions read harness source; QuPath reader checks injected',
    gui_acceptance='NOT RUN', real_slide_acceptance='NOT RUN')
(out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2), flush=True)
assert rc == 0 and cases == source_cases and not failures and not errors, report
assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest, 'JAR changed during testing'
