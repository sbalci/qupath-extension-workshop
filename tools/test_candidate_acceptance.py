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

    def test_fixture_is_deterministic_and_four_decodable_rgb_pages(self):
        a, b = self.root / 'a.svs', self.root / 'b.svs'
        layout = make_slide(a)
        self.assertEqual(layout, make_slide(b))
        self.assertEqual(a.read_bytes(), b.read_bytes())
        pages = ifd_chain(a.read_bytes())
        self.assertEqual([p[256][2] for p in pages], [64, 32, 16, 16])
        self.assertEqual([p[257][2] for p in pages], [48, 24, 16, 16])
        self.assertTrue(all(p[277][2] == 3 and p[259][2] == 1 for p in pages))
        self.assertEqual(pages[0][324][1], 12)
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
            accept.unpack_candidate(archive, self.root)
        self.assertEqual(list(self.root.glob('*.jar')), [])

    def test_archive_checksum_alone_cannot_accept_a_substituted_jar(self):
        archive = self.root / 'bad.zip'
        with zipfile.ZipFile(archive, 'w') as z:
            z.writestr('qupath-extension-workshop-0.2.2-rc7.jar', b'not the candidate')
        with patch.object(accept, 'ARCHIVE_SHA', accept.sha(archive)):
            with self.assertRaises(AssertionError):
                accept.unpack_candidate(archive, self.root)
        self.assertEqual(list(self.root.glob('*.jar')), [])

    def test_archive_path_traversal_is_never_extracted(self):
        archive = self.root / 'bad.zip'
        with zipfile.ZipFile(archive, 'w') as z:
            z.writestr('../escape.jar', b'bad')
        with patch.object(accept, 'ARCHIVE_SHA', accept.sha(archive)):
            with self.assertRaises(AssertionError):
                accept.unpack_candidate(archive, self.root)
        self.assertEqual(list(self.root.glob('*.jar')), [])

    def test_run_directory_cannot_be_reused(self):
        keep = self.root / 'keep'; keep.write_text('existing')
        with patch.object(sys, 'argv', ['accept', '--archive', str(keep), '--work', str(self.root)]):
            with self.assertRaises(FileExistsError):
                accept.main()
        self.assertEqual(keep.read_text(), 'existing')

    def test_corrupt_archive_writes_failed_report_and_nonzero_exit(self):
        archive = self.root / 'bad.zip'; archive.write_bytes(b'bad')
        work = self.root / 'work'
        with patch.object(sys, 'argv', ['accept', '--archive', str(archive), '--work', str(work)]):
            self.assertEqual(accept.main(), 1)
        import json
        report = json.loads((work / 'acceptance.json').read_text())
        self.assertEqual(report['provenance'], 'NOT RUN')
        self.assertEqual(report['host_integration'], 'FAILED / INCOMPLETE')
        self.assertEqual(report['gui_acceptance'], 'NOT RUN')


if __name__ == '__main__':
    unittest.main()
