#!/usr/bin/env python3
"""Inspect the actual built JAR; write evidence and SHA-256 beside it."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import zipfile
import subprocess
import tempfile
import tarfile
import io

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jar', type=Path, help='Verify an existing JAR against source')
parser.add_argument('--source-ref', help='Compare with this exact git commit rather than the checkout')
parser.add_argument('--expect-sha256', help='Require the digest of the exact downloaded artifact')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
# git archive contains repository-controlled paths; extract only regular files.
snapshot = tempfile.TemporaryDirectory() if args.source_ref else None
if snapshot:
    archive = subprocess.check_output(['git', 'archive', args.source_ref], cwd=root)
    root = Path(snapshot.name)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for entry in tar:
            if entry.isfile():
                target = root / entry.name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(tar.extractfile(entry).read())
version = re.search(r'^version = "([^"]+)"', (root / 'build.gradle.kts').read_text(), re.M)[1]
jar = args.jar.resolve() if args.jar else root / f'build/libs/qupath-extension-workshop-{version}.jar'
digest = hashlib.sha256(jar.read_bytes()).hexdigest()
if args.expect_sha256:
    assert digest == args.expect_sha256, 'Artifact digest mismatch'
with zipfile.ZipFile(jar) as z:
    names = set(z.namelist())
    manifest = z.read('META-INF/MANIFEST.MF').decode()
    assert f'Implementation-Version: {version}' in manifest
    info = z.read('build-info.properties').decode()
    assert f'build.version={version}\n' in info
    service = z.read('META-INF/services/qupath.lib.gui.extensions.QuPathExtension').decode().strip()
    assert service == 'io.github.sbalci.qupath.workshop.WorkshopExtension'
    cls = z.read(service.replace('.', '/') + '.class')
    assert int.from_bytes(cls[6:8], 'big') == 65, 'Must target Java 21'
    assert version.encode() in cls, 'Extension-reported version missing'
    assert b'0.2.2-rc5' not in cls
    # All source resources must survive packaging byte for byte, including the
    # classifier, icon, service descriptor, Groovy scripts and Python bridges.
    resources = root / 'src/main/resources'
    for p in resources.rglob('*'):
        if p.is_file():
            assert z.read(p.relative_to(resources).as_posix()) == p.read_bytes(), str(p)
    java_source = (root / 'src/main/java/io/github/sbalci/qupath/workshop/WorkshopExtension.java').read_text()
    scripts = sorted(set(re.findall(r'"([^"/]+\.groovy)"', java_source)))
    for script in scripts:
        assert 'scripts/' + script in names, script
    wizard = z.read('scripts/yardimci-anonim-sihirbaz.groovy')
    assert b'def runAudit =' in wizard and b'def processUnit =' in wizard
    assert b'core/3.5.3/core-3.5.3.jar' in wizard
    assert b'8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82' in wizard
    assert b'def PY_STRIP' not in wizard and b'ProcessBuilder' not in wizard
    assert not any(n.startswith('com/google/zxing/') or n.endswith('core-3.5.3.jar') for n in names)
    assert z.read('images/workshop-icon.png').startswith(b'\x89PNG\r\n\x1a\n')
    classifier = json.loads(gzip.decompress(z.read('classifiers/tumor-stroma-RF.json.gz')))
    assert classifier, 'Empty example classifier'
    # QuPath, JavaFX, Groovy and test dependencies must remain host-provided.
    assert not any(n.startswith(('qupath/', 'javafx/', 'groovy/', 'org/junit/')) for n in names)
digest = hashlib.sha256(jar.read_bytes()).hexdigest()
jar.with_suffix('.jar.sha256').write_text(f'{digest}  {jar.name}\n')
report = {'jar': jar.name, 'sha256': digest, 'bytes': jar.stat().st_size,
          'version': version, 'class_major': 65, 'menu_script_count': len(scripts),
          'anonymization_wizard_sha256': hashlib.sha256(wizard).hexdigest(),
          'zxing': '3.5.3 external, SHA-256 pinned; not bundled',
          'source_resource_identity': 'passed', 'classifier_json': 'passed',
          'qupath_compile_and_core_test_baseline': '0.6.0', 'gui_acceptance': 'NOT RUN', 'source_ref': args.source_ref or 'checkout'}
jar.with_suffix('.jar.verification.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
