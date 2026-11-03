#!/usr/bin/env python3
"""Verify and exercise the existing unpublished CI candidate; never rebuild it.

--qupath enables real host integration. Without it, exit 2 after provenance
checks: a metadata-only run must never count as integration acceptance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).parent / 'integration'))
from synthetic_slide import make_slide, check_output

ROOT = Path(__file__).resolve().parents[1]
CANDIDATES = {
    'earlier': dict(source_ref='e2806e06ab6e256ff2089fb9d29041449908df58',
        archive_sha256='70a0b36a8c648f27a7b45436acd23b585351460c69589a4eded51359a17c7db0',
        jar_sha256='0eefa0e1953617965ce004885fa7ae7f104b3cfb27bb0ef852abe5f1e8966eec',
        artifact_id=12735752184, run_id=40507617137, version='0.2.2-rc7'),
    'rc8': dict(source_ref='222449d8f6b7ff8c83c73d71e8d90b62def00f07',
        checkout_ref='0c77a5712f405facdf6459f5adaa5828653d1de7',
        archive_sha256='123dc21998e93b527209047d01c024927483358199dc2f9f6f4b84bada6ec3a7',
        jar_sha256='3a40cabe18b7108a47c301680df784ee00816de22233d5cac3484f7999042be1',
        artifact_id=13068674430, run_id=41270697133, version='0.2.2-rc8'),
}
WIZARD_SHA = 'b6f3d19559e00d90b7efc8c6142c6e0c1b3b06778e62b2a774b8a28bcd68b307'


def candidate_name(candidate):
    return f"candidate-{candidate['source_ref'][:7]}-artifact-{candidate['artifact_id']}.jar"


def verify_run_metadata(run, artifact, candidate):
    assert run['id'] == candidate['run_id'] and run['conclusion'] == 'success'
    assert run['head_sha'] == candidate['source_ref']
    assert run['path'] == '.github/workflows/build.yml' and run['event'] in ('push', 'pull_request')
    assert run['repository']['full_name'] == 'sbalci/qupath-extension-workshop'
    assert artifact['id'] == candidate['artifact_id'] and not artifact['expired']
    assert artifact['name'] == 'qupath-extension-workshop-jar'
    assert artifact['workflow_run']['id'] == candidate['run_id']
    assert artifact['workflow_run']['head_sha'] == candidate['source_ref']
    assert artifact['digest'] == 'sha256:' + candidate['archive_sha256']


RELEASE_SHA = '7ed24440452eaa49c101b624d3522312d69828169f89ce0e681fbf1de5a13e70'
SCRIPT = 'scripts/yardimci-anonim-sihirbaz.groovy'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def unpack_candidate(archive, work, candidate):
    assert sha(archive) == candidate['archive_sha256'], 'CI artifact ZIP digest mismatch'
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        assert len(names) == len(set(names)), 'Duplicate archive entries'
        jars = [n for n in names if n.endswith('.jar')]
        assert jars == [f"qupath-extension-workshop-{candidate['version']}.jar"], jars
        data = z.read(jars[0])
        assert hashlib.sha256(data).hexdigest() == candidate['jar_sha256'] != RELEASE_SHA
        assert z.read(jars[0] + '.sha256').decode().split()[0] == candidate['jar_sha256']
    jar = work / candidate_name(candidate)
    jar.write_bytes(data)
    return jar


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--candidate', choices=CANDIDATES, default='earlier')
    p.add_argument('--run-metadata', required=True, type=Path)
    p.add_argument('--artifact-metadata', required=True, type=Path)
    p.add_argument('--archive', required=True, type=Path)
    p.add_argument('--work', required=True, type=Path, help='New, empty private work directory')
    p.add_argument('--qupath', type=Path, help='QuPath 0.6.0 executable in disposable installation')
    a = p.parse_args()
    # No overwrite/deletion of an earlier acceptance run, even on a failed retry.
    work = a.work.resolve()
    work.mkdir(mode=0o700, parents=True, exist_ok=False)
    candidate = CANDIDATES[a.candidate]
    report = dict(candidate=a.candidate, **candidate, published_rc7_sha256=RELEASE_SHA,
                  provenance='NOT RUN', packaged_contracts='NOT RUN (run separately)',
                  host_integration='NOT RUN', gui_acceptance='NOT RUN', real_slide_acceptance='NOT RUN')
    try:
        verify_run_metadata(json.loads(a.run_metadata.read_text()),
                            json.loads(a.artifact_metadata.read_text()), candidate)
        jar = unpack_candidate(a.archive, work, candidate)
        subprocess.run([sys.executable, str(ROOT / 'tools/verify-candidate.py'), '--jar', str(jar),
                        '--source-ref', candidate['source_ref'], '--expect-sha256', candidate['jar_sha256']], cwd=ROOT, check=True)
        metadata = json.loads(jar.with_suffix('.jar.verification.json').read_text())
        assert metadata['version'] == candidate['version'], 'Unexpected version metadata'
        assert metadata['anonymization_wizard_sha256'] == WIZARD_SHA
        report['packaging'] = metadata
        report['provenance'] = 'passed'
        with zipfile.ZipFile(jar) as z:
            wizard = z.read(SCRIPT).decode('utf-8')
        layout = make_slide(work / 'source.svs')
        source_digest = sha(work / 'source.svs')
        (work / 'fixture-layout.json').write_text(json.dumps(layout, indent=2) + '\n')
        if not a.qupath:
            report['host_integration'] = 'BLOCKED: no QuPath executable supplied'
            return 2
        qp = a.qupath.resolve()
        env = dict(os.environ)
        home = work / 'home'; home.mkdir()
        env['HOME'] = str(home)
        # QuPath/Java preferences and caches cannot use a normal workstation profile.
        # Paths with whitespace are quoted for the JVM's option parser.
        def prop(key, value):
            return f'-D{key}="{value}"'
        env['JAVA_TOOL_OPTIONS'] = ' '.join([prop('user.home', home),
            prop('java.util.prefs.userRoot', home / 'prefs'),
            '-Djava.awt.headless=true', '-Xmx2g'])
        env.pop('WORKSHOP_SCRIPT_JAR', None)

        def run(name, args):
            with (work / (name + '.log')).open('w') as log:
                r = subprocess.run([str(qp), *args], cwd=work, env=env,
                                   stdout=log, stderr=subprocess.STDOUT, timeout=240)
            assert r.returncode == 0, f'{name}: exit {r.returncode}; see log'

        # Service discovery loads candidate classes from the actual JAR against
        # the supplied QuPath runtime. It does not claim GUI menu installation.
        service = work / 'service.groovy'
        service.write_text('''
def loader = new URLClassLoader([new File(args[0]).toURI().toURL()] as URL[], this.class.classLoader)
try {
    def matches = ServiceLoader.load(qupath.lib.gui.extensions.QuPathExtension, loader).findAll {
        it.class.name == 'io.github.sbalci.qupath.workshop.WorkshopExtension'
    }
    assert matches.size() == 1
    assert new File(matches[0].class.protectionDomain.codeSource.location.toURI()).canonicalPath == new File(args[0]).canonicalPath
    assert matches[0].getVersion().toString() == args[2] : matches[0].getVersion()
    assert qupath.lib.gui.BuildInfo.getInstance().getVersion().toString() == '0.6.0'
    new File(args[1]).text = matches[0].getVersion().toString()
} finally { loader.close() }
''')
        run('service', ['script', str(service), '--args', str(jar), '--args', str(work / 'service-passed'), '--args', candidate['version']])
        assert (work / 'service-passed').read_text() == candidate['version']
        report['extension_reported_version'] = candidate['version']
        # Whole unaltered script and its normal CLI dispatch, not extracted helpers.
        script = work / 'packaged-wizard.groovy'; script.write_text(wizard)
        out = work / 'cli-out'
        run('whole-wizard', ['script', str(script), '-i', str(work / 'source.svs'),
            '--args', 'kip=anonimlestir', '--args', 'adlandirma=hmac',
            '--args', f'cikti={out}', '--args', f'anahtarklasor={work / "cli-keys"}',
            '--args', 'barkod=hayir', '--args', 'tarama=evet'])
        outputs = list(out.glob('*.svs'))
        assert len(outputs) == 1, 'Whole-wizard CLI produced no unique slide'
        check_output(work / 'source.svs', outputs[0], layout)
        assert (out / 'BENIOKU.md').is_file() and (out / 'SHA256SUMS.txt').is_file()
        assert not any(x.suffix in ('.csv', '.json') or x.name == 'salt.bin' for x in out.rglob('*'))
        # Deterministic injection via the real logging callback; no production
        # safeguards or QuPath image checks are replaced.
        anchor = '\nif (isHeadless) {'
        assert wizard.count(anchor) == 1, 'Candidate dispatch structure changed'
        prefix = wizard.split(anchor)[0]
        checks = (ROOT / 'tools/integration/host-checks.groovy').read_text()
        harness = work / 'host.groovy'
        harness.write_text(prefix + '\ndef acceptanceRoot = new File(args[0])\n' + checks)
        run('host', ['script', str(harness), '--args', str(work)])
        host = json.loads((work / 'host-results.json').read_text())
        assert host['status'] == 'passed' and host['qupath_version'] == '0.6.0'
        check_output(work / 'source.svs', host['output'], layout)
        # Exercise the new helper from the same JAR, with and without an image.
        # Historical candidate profiles do not contain it and retain their scope.
        with zipfile.ZipFile(jar) as z:
            helper_name = 'scripts/yardimci-ajan-kopru-denetimi.groovy'
            if helper_name in z.namelist():
                helper = work / 'packaged-bridge-inspection.groovy'
                helper.write_bytes(z.read(helper_name))
                for label, extra in [('no-image', []), ('image', ['-i', str(work / 'source.svs')])]:
                    run('bridge-' + label, ['script', str(helper), *extra])
                    log = (work / ('bridge-' + label + '.log')).read_text()
                    assert 'Köprü denetimi tamamlandı (salt okunur).' in log
                    assert ('Açık görüntü yok; bu başlık atlandı.' in log) == (label == 'no-image')
                assert sha(work / 'source.svs') == source_digest
                report['bridge_inspection'] = 'passed (packaged script, CLI with/without synthetic image; GUI menu NOT RUN)'
        report['host_integration'] = 'passed (QuPath CLI, synthetic only)'
        report['service_discovery'] = 'passed (GUI install not exercised)'
        report['host_results'] = host
        return 0
    except Exception as e:
        report['failure'] = f'{type(e).__name__}: {e}'
        report['host_integration'] = 'FAILED / INCOMPLETE'
        print(report['failure'], file=sys.stderr)
        return 1
    finally:
        (work / 'acceptance.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))


if __name__ == '__main__':
    sys.exit(main())
