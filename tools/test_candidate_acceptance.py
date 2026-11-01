"""Independent checks for fixture layout and the acceptance harness's fail-closed gate."""
import importlib.util
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('accept_candidate', TOOLS / 'accept-candidate.py')
accept = importlib.util.module_from_spec(spec)
spec.loader.exec_module(accept)
from synthetic_slide import make_slide, ifd_chain, check_output


class CandidateAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def metadata(self, name='earlier'):
        import json
        c = accept.CANDIDATES[name]
        run = dict(id=c['run_id'], conclusion='success', head_sha=c['source_ref'],
                   path='.github/workflows/build.yml', event='pull_request',
                   repository=dict(full_name='sbalci/qupath-extension-workshop'))
        artifact = dict(id=c['artifact_id'], expired=False, name='qupath-extension-workshop-jar',
                        workflow_run=dict(id=c['run_id'], head_sha=c['source_ref']),
                        digest='sha256:' + c['archive_sha256'])
        paths = self.root / 'run.json', self.root / 'artifact.json'
        for path, value in zip(paths, (run, artifact)):
            path.write_text(json.dumps(value))
        return paths

    def test_profiles_keep_distinct_candidate_and_run_identities(self):
        self.assertNotEqual(accept.candidate_name(accept.CANDIDATES['earlier']),
                            accept.candidate_name(accept.CANDIDATES['rc8']))
        import json
        for name, c in accept.CANDIDATES.items():
            run, artifact = [json.loads(p.read_text()) for p in self.metadata(name)]
            accept.verify_run_metadata(run, artifact, c)
            for key, value in [('head_sha', 'wrong'), ('conclusion', 'failure'), ('id', 1)]:
                with self.subTest(name=name, key=key), self.assertRaises(AssertionError):
                    accept.verify_run_metadata(dict(run, **{key:value}), artifact, c)
            for key, value in [('id', 1), ('expired', True), ('digest', 'sha256:wrong')]:
                with self.subTest(name=name, key=key), self.assertRaises(AssertionError):
                    accept.verify_run_metadata(run, dict(artifact, **{key:value}), c)

    def test_earlier_archive_cannot_be_used_as_rc8(self):
        archive = self.root / 'wrong.zip'
        with zipfile.ZipFile(archive, 'w') as z:
            z.writestr('qupath-extension-workshop-0.2.2-rc7.jar', b'old')
        with patch.dict(accept.CANDIDATES['rc8'], archive_sha256=accept.sha(archive)):
            with self.assertRaises(AssertionError):
                accept.unpack_candidate(archive, self.root, accept.CANDIDATES['rc8'])
        self.assertEqual(list(self.root.glob('*.jar')), [])

    def test_fixture_is_deterministic_and_four_decodable_rgb_pages(self):
        a, b = self.root / 'a.svs', self.root / 'b.svs'
        layout = make_slide(a)
        self.assertEqual(layout, make_slide(b))
        self.assertEqual(a.read_bytes(), b.read_bytes())
        pages = ifd_chain(a.read_bytes())
        self.assertEqual([p[256][2] for p in pages], [64, 32, 16, 16])
        self.assertEqual([p[257][2] for p in pages], [48, 24, 16, 16])
        self.assertTrue(all(p[277][2] == 3 and p[259][2] == 8 for p in pages))
        self.assertEqual(pages[0][324][1], 12)
        # OpenSlide derives associated-image names from description line two.
        for page, kind in zip(pages[2:], [b'label', b'macro']):
            typ, count, pos = page[270]
            desc = a.read_bytes()[pos:pos + count]
            self.assertTrue(desc.split(b'\n')[1].startswith(kind + b' '))
        self.assertTrue(all(len(a.read_bytes()[pos:pos+n]) == n
                            for p in layout['pages'] for pos, n in p['owned']))

    def test_unsanitized_copy_cannot_pass_independent_verification(self):
        source = self.root / 'source.svs'
        layout = make_slide(source)
        with self.assertRaises(AssertionError):
            check_output(source, source, layout)

    def test_wrong_archive_digest_fails_before_extracting_any_jar(self):
        archive = self.root / 'bad.zip'; archive.write_bytes(b'wrong artifact')
        with self.assertRaisesRegex(AssertionError, 'ZIP digest'):
            accept.unpack_candidate(archive, self.root, accept.CANDIDATES['earlier'])
        self.assertEqual(list(self.root.glob('*.jar')), [])

    def test_archive_checksum_alone_cannot_accept_a_substituted_jar(self):
        archive = self.root / 'bad.zip'
        with zipfile.ZipFile(archive, 'w') as z:
            z.writestr('qupath-extension-workshop-0.2.2-rc7.jar', b'not the candidate')
        with patch.dict(accept.CANDIDATES['earlier'], archive_sha256=accept.sha(archive)):
            with self.assertRaises(AssertionError):
                accept.unpack_candidate(archive, self.root, accept.CANDIDATES['earlier'])
        self.assertEqual(list(self.root.glob('*.jar')), [])

    def test_archive_path_traversal_is_never_extracted(self):
        archive = self.root / 'bad.zip'
        with zipfile.ZipFile(archive, 'w') as z:
            z.writestr('../escape.jar', b'bad')
        with patch.dict(accept.CANDIDATES['earlier'], archive_sha256=accept.sha(archive)):
            with self.assertRaises(AssertionError):
                accept.unpack_candidate(archive, self.root, accept.CANDIDATES['earlier'])
        self.assertEqual(list(self.root.glob('*.jar')), [])

    def test_run_directory_cannot_be_reused(self):
        keep = self.root / 'keep'; keep.write_text('existing')
        with patch.object(sys, 'argv', ['accept', '--run-metadata', 'unused', '--artifact-metadata', 'unused', '--archive', str(keep), '--work', str(self.root)]):
            with self.assertRaises(FileExistsError):
                accept.main()
        self.assertEqual(keep.read_text(), 'existing')

    def test_corrupt_archive_writes_failed_report_and_nonzero_exit(self):
        archive = self.root / 'bad.zip'; archive.write_bytes(b'bad')
        work = self.root / 'work'
        with patch.object(sys, 'argv', ['accept', '--run-metadata', str(self.metadata()[0]), '--artifact-metadata', str(self.metadata()[1]), '--archive', str(archive), '--work', str(work)]):
            self.assertEqual(accept.main(), 1)
        import json
        report = json.loads((work / 'acceptance.json').read_text())
        self.assertEqual(report['provenance'], 'NOT RUN')
        self.assertEqual(report['host_integration'], 'FAILED / INCOMPLETE')
        self.assertEqual(report['gui_acceptance'], 'NOT RUN')


if __name__ == '__main__':
    unittest.main()
