#!/usr/bin/env python3
"""Read-only GitHub retrieval and fail-closed staging of an accepted JAR. Never publishes."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'sbalci/qupath-extension-workshop'
PRODUCTION = ['src', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
              'gradle', 'gradlew', 'gradlew.bat', '.github/workflows/build.yml']


class VerificationError(ValueError):
    pass


def require(condition, message):
    # Deliberately not assert: python -O must not disable release checks.
    if not condition:
        raise VerificationError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.PIPE)


def profile_for(tag, root=ROOT):
    require(re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', tag), 'Invalid release tag')
    p = json.loads((root / 'docs/release-candidates' / (tag + '.json')).read_text())
    require(p['repository'] == REPOSITORY and p['repository_id'] == 1240697127, 'Wrong repository')
    require(tag == 'v' + p['version'], 'Profile version/tag mismatch')
    require(p['jar_name'] == f"qupath-extension-workshop-{p['version']}.jar", 'Unexpected JAR name')
    for ref in [p['source_ref'], p['checkout_ref'], p['acceptance']['head_sha']]:
        require(re.fullmatch('[0-9a-f]{40}', ref), 'Unpinned source commit')
    for value in [p['jar_sha256'], p['build']['archive_sha256'], p['acceptance']['archive_sha256']]:
        require(re.fullmatch('[0-9a-f]{64}', value), 'Unpinned SHA-256')
    return p


def source_gate(root, p, release_sha):
    require(re.fullmatch('[0-9a-f]{40}', release_sha), 'Unpinned release commit')
    require(git(root, 'rev-parse', 'HEAD').decode().strip() == release_sha, 'Checkout/release commit mismatch')
    require(not git(root, 'status', '--porcelain', '--untracked-files=no'), 'Tracked checkout changes')
    version = re.search(r'^version = "([^"]+)"', (root / 'build.gradle.kts').read_text(), re.M)
    require(version and version[1] == p['version'], 'Release tag/build version mismatch')
    source = p['source_ref']
    for ref in [source, p['checkout_ref'], p['acceptance']['head_sha']]:
        require(git(root, 'rev-parse', ref + '^{commit}').decode().strip() == ref, 'Missing exact source commit')
    parents = git(root, 'show', '-s', '--format=%P', p['checkout_ref']).decode().split()
    require(source in parents, 'Build checkout is not the pinned PR merge')
    for ref in [release_sha, p['checkout_ref'], p['acceptance']['head_sha']]:
        require(not git(root, 'diff', '--name-only', source, ref, '--', *PRODUCTION),
                'Production/build/test source differs from accepted source')
    subprocess.run(['git', 'merge-base', '--is-ancestor', source, release_sha], cwd=root, check=True)
    # If a tag exists, dispatch must not publish different code under that name.
    tag = 'refs/tags/v' + p['version']
    exists = subprocess.run(['git', 'show-ref', '--verify', '--quiet', tag], cwd=root)
    require(exists.returncode in (0, 1), 'Tag lookup failed')
    if exists.returncode == 0:
        require(git(root, 'rev-parse', tag + '^{commit}').decode().strip() == release_sha, 'Existing tag targets different source')


def verify_metadata(run, artifact, p, key):
    c = p[key]
    expected = dict(id=c['run_id'], run_attempt=c['attempt'], head_sha=c['head_sha'],
                    workflow_id=c['workflow_id'], path=c['path'], event=c['event'],
                    status='completed', conclusion='success')
    require(all(run.get(k) == v for k, v in expected.items()), key + ': run identity/status mismatch')
    for field in ['repository', 'head_repository']:
        require(run[field]['id'] == p['repository_id'] and run[field]['full_name'] == p['repository'],
                key + ': foreign/fork repository')
    expected = dict(id=c['artifact_id'], name=c['artifact_name'], expired=False,
                    size_in_bytes=c['archive_bytes'], digest='sha256:' + c['archive_sha256'])
    require(all(artifact.get(k) == v for k, v in expected.items()), key + ': artifact identity/expiry/size/digest mismatch')
    owner = artifact['workflow_run']
    require(owner['id'] == c['run_id'] and owner['head_sha'] == c['head_sha'] and
            owner['repository_id'] == owner['head_repository_id'] == p['repository_id'],
            key + ': artifact source/run mismatch')


def read_archive(data, c):
    require(len(data) == c['archive_bytes'] and digest(data) == c['archive_sha256'], 'Archive size/SHA-256 mismatch')
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        entries = z.infolist()
        names = [e.filename for e in entries]
        require(len(names) == len(set(names)), 'Duplicate ZIP entries')
        require(sum(e.file_size for e in entries) <= 64 * 1024 * 1024, 'Oversized ZIP contents')
        for e in entries:
            path = PurePosixPath(e.filename)
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in e.filename and
                    (e.external_attr >> 16) & 0o170000 != 0o120000, 'Unsafe ZIP member')
        # No extractall and no execution of artifact contents.
        return {n: z.read(n) for n in names}


def verify_evidence(files, p):
    r = json.loads(files['host-acceptance/acceptance.json'])
    for key in ['candidate', 'source_ref', 'checkout_ref', 'version', 'jar_sha256']:
        require(r[key] == p[key], 'Acceptance candidate mismatch: ' + key)
    for key, value in [('run_id', p['build']['run_id']), ('artifact_id', p['build']['artifact_id']),
                       ('archive_sha256', p['build']['archive_sha256'])]:
        require(r[key] == value, 'Acceptance artifact mismatch: ' + key)
    require('failure' not in r and r['provenance'] == 'passed' and
            r['host_integration'] == 'passed (QuPath CLI, synthetic only)' and
            r['extension_reported_version'] == p['version'] and
            r['bridge_inspection'] == 'passed (packaged script, CLI with/without synthetic image; GUI menu NOT RUN)',
            'Synthetic host/bridge acceptance incomplete')
    require(r['gui_acceptance'] == r['real_slide_acceptance'] == 'NOT RUN', 'Unexpected acceptance scope')
    packing = r['packaging']
    require(packing['sha256'] == p['jar_sha256'] and packing['bytes'] == p['jar_bytes'] and
            packing['source_ref'] == p['source_ref'] and packing['version'] == p['version'] and
            packing['source_resource_identity'] == 'passed', 'Acceptance packaging mismatch')
    host = json.loads(files['host-acceptance/host-results.json'])
    require(host == r['host_results'] and host['status'] == 'passed' and
            host['qupath_version'] == '0.6.0' and host['fixture'] == 'synthetic-only', 'Host results mismatch')
    for key in ['label_macro_removed', 'retained_pixels_equal', 'key_separation',
                'source_unchanged', 'injected_mutation_rejected']:
        require(host[key] is True, 'Host safeguard failed: ' + key)
    for label in ['image', 'no-image']:
        require('Köprü denetimi tamamlandı (salt okunur).'.encode() in files['host-acceptance/bridge-' + label + '.log'],
                'Missing bridge execution evidence')
    verify_metadata(json.loads(files['candidate-input/run.json']),
                    json.loads(files['candidate-input/artifact.json']), p, 'build')
    cases = []
    for name, data in files.items():
        if name.startswith('test-results/test/TEST-') and name.endswith('.xml'):
            suite = ET.fromstring(data)
            require(suite.tag == 'testsuite' and all(next(suite.iter(t), None) is None
                    for t in ['failure', 'error', 'skipped']), 'Failed/skipped JUnit evidence')
            cases.extend(suite.findall('testcase'))
    require(len(cases) == len({(c.attrib['classname'], c.attrib['name']) for c in cases}) == 49,
            'Require 49 distinct passing packaged contracts')


def verify_bundle(p, inputs, root, release_sha):
    source_gate(root, p, release_sha)
    for key in ['build', 'acceptance']:
        verify_metadata(inputs[key + '_run'], inputs[key + '_artifact'], p, key)
    evidence = read_archive(inputs['acceptance_zip'], p['acceptance'])
    verify_evidence(evidence, p)
    files = read_archive(inputs['build_zip'], p['build'])
    name = p['jar_name']
    require(set(files) == {name, name + '.sha256', name + '.verification.json'}, 'Unexpected build artifact files')
    jar = files[name]
    require(len(jar) == p['jar_bytes'] and digest(jar) == p['jar_sha256'], 'JAR size/SHA-256 mismatch')
    require(files[name + '.sha256'].decode().split() == [p['jar_sha256'], name], 'Packaged checksum mismatch')
    packing = json.loads(files[name + '.verification.json'])
    require(packing['sha256'] == p['jar_sha256'] and packing['bytes'] == p['jar_bytes'] and
            packing['version'] == p['version'] and packing['source_resource_identity'] == 'passed', 'Build verification mismatch')
    with zipfile.ZipFile(io.BytesIO(jar)) as z:
        require(len(z.namelist()) == len(set(z.namelist())), 'Duplicate JAR entries')
        require(('Implementation-Version: ' + p['version']) in z.read('META-INF/MANIFEST.MF').decode(), 'JAR version mismatch')
        resource_root = 'src/main/resources/'
        for path in git(root, 'ls-tree', '-r', '--name-only', p['source_ref'], '--', resource_root).decode().splitlines():
            require(z.read(path[len(resource_root):]) == git(root, 'show', p['source_ref'] + ':' + path),
                    'Packaged source resource mismatch: ' + path)
    return jar


def api(repo, endpoint):
    return subprocess.check_output(['gh', 'api', 'repos/' + repo + '/' + endpoint], stderr=subprocess.PIPE)


def retrieve(p, root=ROOT):
    inputs = {}
    for key in ['build', 'acceptance']:
        c = p[key]
        # Current run state must match pinned attempt; a later rerun requires review.
        inputs[key + '_run'] = json.loads(api(p['repository'], f"actions/runs/{c['run_id']}"))
        inputs[key + '_artifact'] = json.loads(api(p['repository'], f"actions/artifacts/{c['artifact_id']}"))
        verify_metadata(inputs[key + '_run'], inputs[key + '_artifact'], p, key)
        inputs[key + '_zip'] = api(p['repository'], f"actions/artifacts/{c['artifact_id']}/zip")
    # Historical PR merge commits need not be reachable from normal branches.
    for ref in [p['source_ref'], p['checkout_ref'], p['acceptance']['head_sha']]:
        subprocess.run(['git', 'fetch', '--no-tags', 'origin', ref], cwd=root, check=True)
    return inputs


def stage(p, inputs, output, root, release_sha):
    require(not output.exists(), 'Output directory already exists; refusing stale files')
    jar = verify_bundle(p, inputs, root, release_sha)
    # No publishable output exists until every check has passed.
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        work = Path(tmp) / 'verified'; work.mkdir()
        name = p['jar_name']
        (work / name).write_bytes(jar)
        (work / (name + '.sha256')).write_text(p['jar_sha256'] + '  ' + name + '\n')
        report = dict(verification='passed', release_commit=release_sha, profile=p,
                      packaged_contracts=49, synthetic_host='QuPath 0.6.0 CLI; bridge with/without image',
                      gui_acceptance='NOT RUN', real_slide_acceptance='NOT RUN',
                      barcode_and_intended_os_acceptance='NOT RUN', clinical_validation='NOT ESTABLISHED')
        (work / (name + '.verification.json')).write_text(json.dumps(report, indent=2) + '\n')
        work.rename(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--release-sha', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        p = profile_for(args.tag)
        require(os.environ.get('GITHUB_REPOSITORY', REPOSITORY) == REPOSITORY, 'Foreign workflow repository')
        stage(p, retrieve(p), args.output.resolve(), ROOT, args.release_sha)
        print('Verified exact accepted JAR; staged only, no publication performed.')
        return 0
    except Exception as e:
        # Do not dump gh stderr or credentials/signed download URLs.
        print('Promotion verification failed: ' + (str(e) if isinstance(e, VerificationError) else type(e).__name__), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
