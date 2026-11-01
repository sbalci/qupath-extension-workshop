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
SOURCE_REF = 'e2806e06ab6e256ff2089fb9d29041449908df58'
ARCHIVE_SHA = '70a0b36a8c648f27a7b45436acd23b585351460c69589a4eded51359a17c7db0'
JAR_SHA = '0eefa0e1953617965ce004885fa7ae7f104b3cfb27bb0ef852abe5f1e8966eec'
RELEASE_SHA = '7ed24440452eaa49c101b624d3522312d69828169f89ce0e681fbf1de5a13e70'
SCRIPT = 'scripts/yardimci-anonim-sihirbaz.groovy'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def unpack_candidate(archive, work):
    assert sha(archive) == ARCHIVE_SHA, 'CI artifact ZIP digest mismatch'
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        assert len(names) == len(set(names)), 'Duplicate archive entries'
        jars = [n for n in names if n.endswith('.jar')]
        assert jars == ['qupath-extension-workshop-0.2.2-rc7.jar'], jars
        data = z.read(jars[0])
        assert hashlib.sha256(data).hexdigest() == JAR_SHA != RELEASE_SHA
        assert z.read(jars[0] + '.sha256').decode().split()[0] == JAR_SHA
    # External name identifies provenance even though internal version is rc7.
    jar = work / 'candidate-e2806e0-artifact-12735752184.jar'
    jar.write_bytes(data)
    return jar


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--archive', required=True, type=Path)
    p.add_argument('--work', required=True, type=Path, help='New, empty private work directory')
    p.add_argument('--qupath', type=Path, help='QuPath 0.6.0 executable in disposable installation')
    a = p.parse_args()
    # No overwrite/deletion of an earlier acceptance run, even on a failed retry.
    work = a.work.resolve()
    work.mkdir(mode=0o700, parents=True, exist_ok=False)
    report = dict(candidate='unpublished-ci-e2806e0', artifact_id=12735752184,
                  run_id=40507617137, source_ref=SOURCE_REF, archive_sha256=ARCHIVE_SHA,
                  jar_sha256=JAR_SHA, published_rc7_sha256=RELEASE_SHA,
                  provenance='NOT RUN', packaged_contracts='NOT RUN (run separately)',
                  host_integration='NOT RUN', gui_acceptance='NOT RUN', real_slide_acceptance='NOT RUN')
    try:
        jar = unpack_candidate(a.archive, work)
        subprocess.run([sys.executable, str(ROOT / 'tools/verify-candidate.py'), '--jar', str(jar),
                        '--source-ref', SOURCE_REF, '--expect-sha256', JAR_SHA], cwd=ROOT, check=True)
        report['provenance'] = 'passed'
        with zipfile.ZipFile(jar) as z:
            wizard = z.read(SCRIPT).decode('utf-8')
        layout = make_slide(work / 'source.svs')
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
    new File(args[1]).text = 'passed'
} finally { loader.close() }
''')
        run('service', ['script', str(service), '--args', str(jar), '--args', str(work / 'service-passed')])
        assert (work / 'service-passed').read_text() == 'passed'
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
        harness.write_text('def acceptanceRoot = new File(args[0])\n' + prefix + '\n' + checks)
        run('host', ['script', str(harness), '--args', str(work)])
        host = json.loads((work / 'host-results.json').read_text())
        assert host['status'] == 'passed'
        check_output(work / 'source.svs', host['output'], layout)
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
