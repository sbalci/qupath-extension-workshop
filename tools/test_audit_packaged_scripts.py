"""Synthetic harness tests; no JAR downloads or Gradle invocation."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('audit-packaged-scripts.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class AuditHarnessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        required = set().union(*(a[3] for a in audit.artifacts))
        self.cases = {('Safety', name + '()') for name in required}
        self.cases.update(('Added', f'test{i}()') for i in range(49 - len(required)))

    def result(self, name, jar=None):
        failures = next((a[3] for a in audit.artifacts if a[0] == name), set())
        return (1 if jar else 0, self.cases, failures, [])

    def run_audit(self, result=None, digest=None):
        with patch.object(audit, 'out', self.directory), \
             patch.object(audit, 'script_evidence', return_value={'script_sha256':'fixture', 'closures':[]}), \
             patch.object(audit, 'detailed_results', return_value=[]), \
             patch.object(audit, 'run_suite', side_effect=result or self.result), \
             patch.object(Path, 'read_bytes', return_value=b'fixture'), \
             patch.object(audit.hashlib, 'sha256') as sha:
            sha.return_value.hexdigest.side_effect = digest or [a[2] for a in audit.artifacts]
            audit.main()

    def test_added_tests_need_no_count_update(self):
        self.run_audit()

    def test_missing_or_substituted_historical_case_is_rejected(self):
        def result(name, jar=None):
            rc, cases, failures, errors = self.result(name, jar)
            if jar:
                cases = (cases - {('Added', 'test0()')}) | {('Other', 'test0()')}
            return rc, cases, failures, errors
        with self.assertRaises(AssertionError):
            self.run_audit(result)

    def test_missing_required_failure_is_rejected(self):
        def result(name, jar=None):
            rc, cases, failures, errors = self.result(name, jar)
            return rc, cases, set(), errors
        with self.assertRaises(AssertionError):
            self.run_audit(result)

    def test_corrected_failure_is_rejected_at_start_and_finish(self):
        for target in ['corrected-baseline', 'corrected']:
            with self.subTest(target=target):
                def result(name, jar=None):
                    if name == target:
                        return 1, self.cases, {'testBroken'}, []
                    return self.result(name, jar)
                with self.assertRaises(AssertionError):
                    self.run_audit(result)

    def test_artifact_hash_mismatch_is_rejected(self):
        for index, artifact in enumerate(audit.artifacts):
            with self.subTest(artifact=artifact[0]):
                digests = [a[2] for a in audit.artifacts]
                digests[index] = 'wrong'
                with self.assertRaises(AssertionError):
                    self.run_audit(digest=digests)

    def test_rc7_cannot_pass_or_omit_a_required_contract(self):
        for defect in ['passing', 'missing', 'error']:
            with self.subTest(defect=defect):
                def result(name, jar=None):
                    rc, cases, failures, errors = self.result(name, jar)
                    if name == 'published-rc7':
                        if defect == 'passing':
                            return 0, cases, set(), []
                        if defect == 'missing':
                            return rc, cases - {next(iter(cases))}, failures, errors
                        return rc, cases, failures, ['fixture error']
                    return rc, cases, failures, errors
                with self.assertRaises(AssertionError):
                    self.run_audit(result)


    def test_empty_duplicate_and_skipped_reports_are_rejected(self):
        for body in ['', '<testcase classname="A" name="x"/>' * 2,
                     '<testcase classname="A" name="x"><skipped/></testcase>']:
            with self.subTest(body=body):
                (self.directory / 'TEST-fixture.xml').write_text('<testsuite>' + body + '</testsuite>')
                with self.assertRaises(AssertionError):
                    audit.read_results(self.directory)


if __name__ == '__main__':
    unittest.main()
