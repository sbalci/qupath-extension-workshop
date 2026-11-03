"""Offline release-provenance regression tests; no GitHub mutations or JAR execution."""
import copy
import ast
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('promote', TOOLS / 'promote-candidate.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


def archive(files):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w') as z:
        for name, data in files.items():
            z.writestr(name, data)
    return out.getvalue()


class ReleasePromotionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        self.p = m.profile_for('v0.2.2-rc8')
        # Tiny independent fixture, never a real extension or a network download.
        jar = archive({'META-INF/MANIFEST.MF': 'Implementation-Version: 0.2.2-rc8\n'})
        self.p.update(jar_bytes=len(jar), jar_sha256=m.digest(jar))
        name = self.p['jar_name']
        packing = dict(sha256=m.digest(jar), bytes=len(jar), version=self.p['version'], source_resource_identity='passed', source_ref=self.p['source_ref'])
        self.build_files = {name: jar, name+'.sha256': m.digest(jar)+'  '+name+'\n', name+'.verification.json': json.dumps(packing)}
        self.inputs = {}
        for key in ['build', 'acceptance']:
            c = self.p[key]
            repo = dict(id=self.p['repository_id'], full_name=self.p['repository'])
            self.inputs[key+'_run'] = dict(id=c['run_id'], run_attempt=c['attempt'], head_sha=c['head_sha'], path=c['path'], workflow_id=c['workflow_id'], event=c['event'], status='completed', conclusion='success', repository=repo, head_repository=repo.copy())
            self.inputs[key+'_artifact'] = dict(id=c['artifact_id'], name=c['artifact_name'], expired=False, size_in_bytes=0, digest='', workflow_run=dict(id=c['run_id'], head_sha=c['head_sha'], repository_id=self.p['repository_id'], head_repository_id=self.p['repository_id']))
        host = dict(status='passed', qupath_version='0.6.0', fixture='synthetic-only', **{k:True for k in ['label_macro_removed','retained_pixels_equal','key_separation','source_unchanged','injected_mutation_rejected']})
        report = {k:self.p[k] for k in ['candidate','source_ref','checkout_ref','version','jar_sha256']}
        report.update(run_id=self.p['build']['run_id'], artifact_id=self.p['build']['artifact_id'], provenance='passed', host_integration='passed (QuPath CLI, synthetic only)', extension_reported_version=self.p['version'], bridge_inspection='passed (packaged script, CLI with/without synthetic image; GUI menu NOT RUN)', gui_acceptance='NOT RUN', real_slide_acceptance='NOT RUN', packaging=packing, host_results=host)
        self.report = report
        self.evidence_files = {'host-acceptance/host-results.json':json.dumps(host),
            'host-acceptance/bridge-image.log':'Köprü denetimi tamamlandı (salt okunur).',
            'host-acceptance/bridge-no-image.log':'Köprü denetimi tamamlandı (salt okunur).',
            'test-results/test/TEST-example.xml':'<testsuite>'+''.join(f'<testcase classname="C" name="test{i}"/>' for i in range(49))+'</testsuite>'}
        self.refresh()

    def tearDown(self):
        self.tmp.cleanup()

    def refresh(self):
        for key, files in [('build', self.build_files), ('acceptance', self.evidence_files)]:
            if key == 'acceptance':
                self.report['archive_sha256'] = self.p['build']['archive_sha256']
                files['host-acceptance/acceptance.json'] = json.dumps(self.report)
                files['candidate-input/run.json'] = json.dumps(self.inputs['build_run'])
                files['candidate-input/artifact.json'] = json.dumps(self.inputs['build_artifact'])
            data = archive(files); self.inputs[key+'_zip'] = data
            self.p[key].update(archive_sha256=m.digest(data), archive_bytes=len(data))
            self.inputs[key+'_artifact'].update(size_in_bytes=len(data), digest='sha256:'+m.digest(data))

    def stage(self):
        # Source checks have separate real-git tests below; no mocked verification checks.
        with patch.object(m, 'source_gate'), patch.object(m, 'git', return_value=b''):
            m.stage(self.p, self.inputs, self.root/'output', self.root, 'a'*40)

    def rejected(self):
        with self.assertRaises((m.VerificationError, KeyError, ValueError, zipfile.BadZipFile)):
            self.stage()
        self.assertFalse((self.root/'output').exists())

    def test_success_stages_exact_bytes_and_scope(self):
        self.stage(); out = self.root/'output'
        self.assertEqual((out/self.p['jar_name']).read_bytes(), self.build_files[self.p['jar_name']])
        report = json.loads((out/(self.p['jar_name']+'.verification.json')).read_text())
        self.assertEqual(report['clinical_validation'], 'NOT ESTABLISHED')
        self.assertEqual(report['packaged_contracts'], 49)

    def test_wrong_run_status_identity_attempt_workflow_and_source(self):
        original = copy.deepcopy(self.inputs)
        for key in ['build', 'acceptance']:
            for field, bad in [('id',1),('run_attempt',2),('head_sha','0'*40),('workflow_id',1),('path','wrong'),('event','workflow_dispatch'),('status','in_progress'),('conclusion','failure')]:
                with self.subTest(key=key, field=field):
                    self.inputs = copy.deepcopy(original); self.inputs[key+'_run'][field] = bad; self.rejected()

    def test_foreign_or_fork_repository_rejected(self):
        for key in ['build', 'acceptance']:
            for field in ['repository','head_repository']:
                with self.subTest(key=key, field=field):
                    inputs = copy.deepcopy(self.inputs)
                    self.inputs[key+'_run'][field]['id'] = 1; self.rejected(); self.inputs = inputs

    def test_artifact_expired_wrong_id_name_size_digest_or_owner(self):
        original = copy.deepcopy(self.inputs)
        for key in ['build', 'acceptance']:
            for field, bad in [('id',1),('name','other'),('expired',True),('size_in_bytes',1),('digest','sha256:bad')]:
                with self.subTest(key=key, field=field):
                    self.inputs = copy.deepcopy(original); self.inputs[key+'_artifact'][field] = bad; self.rejected()
            self.inputs = copy.deepcopy(original); self.inputs[key+'_artifact']['workflow_run']['head_repository_id'] = 1; self.rejected()

    def test_missing_evidence_and_bad_archive_bytes(self):
        original = copy.deepcopy(self.inputs)
        for key in ['build_zip','acceptance_zip','build_artifact','acceptance_run']:
            with self.subTest(key=key):
                self.inputs = copy.deepcopy(original); del self.inputs[key]; self.rejected()
        for key in ['build_zip','acceptance_zip']:
            self.inputs = copy.deepcopy(original); self.inputs[key] += b'corruption'; self.rejected()

    def test_wrong_jar_sha_or_size_even_with_matching_archive_digest(self):
        for key, value in [('jar_sha256','0'*64),('jar_bytes',1)]:
            old = self.p[key]; self.p[key] = value; self.rejected(); self.p[key] = old

    def test_wrong_checksum_sidecar(self):
        self.build_files[self.p['jar_name']+'.sha256'] = 'wrong'; self.refresh(); self.rejected()

    def test_missing_wrong_version_jar_or_extra_jar(self):
        self.build_files['extra.jar'] = b'not accepted'; self.refresh(); self.rejected()

    def test_wrong_acceptance_candidate_digest_or_scope(self):
        for field, bad in [('jar_sha256','0'*64),('source_ref','0'*40),('artifact_id',1),('provenance','FAILED'),('host_integration','BLOCKED'),('bridge_inspection','NOT RUN'),('gui_acceptance','passed')]:
            old = self.report[field]; self.report[field] = bad; self.refresh(); self.rejected(); self.report[field] = old

    def test_missing_or_failed_host_evidence(self):
        del self.evidence_files['host-acceptance/host-results.json']; self.refresh(); self.rejected()

    def test_failed_skipped_incomplete_or_duplicate_contracts(self):
        key = 'test-results/test/TEST-example.xml'; original = self.evidence_files[key]
        for invalid in [original.replace('name="test0"/>','name="test0"><failure/></testcase>'), original.replace('name="test0"/>','name="test0"><skipped/></testcase>'), original.replace('<testcase classname="C" name="test0"/>',''), original.replace('name="test0"','name="test1"')]:
            self.evidence_files[key] = invalid; self.refresh(); self.rejected()

    def test_unsafe_and_duplicate_zip_members(self):
        for name in ['../escape','/absolute','back\\slash']:
            self.build_files[name] = b'bad'; self.refresh(); self.rejected(); del self.build_files[name]
        raw = io.BytesIO()
        with zipfile.ZipFile(raw,'w') as z:
            z.writestr('duplicate', b'a')
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter('ignore'); z.writestr('duplicate', b'b')
        data=raw.getvalue()
        with self.assertRaises(m.VerificationError):
            m.read_archive(data, dict(archive_bytes=len(data),archive_sha256=m.digest(data)))

    def test_stale_output_refused(self):
        (self.root/'output').mkdir(); (self.root/'output/keep').write_text('keep')
        with self.assertRaises(m.VerificationError): self.stage()
        self.assertEqual((self.root/'output/keep').read_text(),'keep')

    def test_network_or_missing_artifact_error_is_nonzero(self):
        with patch.object(m,'profile_for',return_value=self.p), patch.object(m,'source_gate'), patch.object(m,'retrieve',side_effect=subprocess.CalledProcessError(1,['gh'])), patch('sys.argv',['promote','--tag','v0.2.2-rc8','--release-sha','a'*40,'--output',str(self.root/'output')]):
            self.assertEqual(m.main(),1)
        self.assertFalse((self.root/'output').exists())

    def test_tag_injection_and_unknown_profile_rejected(self):
        for tag in ['../../main', 'v0.2.2-rc8\nnew=value', 'v1;echo']:
            with self.assertRaises(m.VerificationError): m.profile_for(tag)
        with self.assertRaises(FileNotFoundError): m.profile_for('v99.0.0')


class SourceGateTest(unittest.TestCase):
    def test_real_git_source_version_tag_and_dirty_gates(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            def git(*args): return m.git(root,*args).decode().strip()
            git('init','-q'); git('config','user.email','test@example.invalid'); git('config','user.name','Test')
            (root/'build.gradle.kts').write_text('version = "0.2.2-rc8"\n')
            git('add','.'); git('commit','-qm','base'); base=git('rev-parse','HEAD')
            git('checkout','-qb','candidate'); (root/'src/main/resources').mkdir(parents=True)
            (root/'src/main/resources/example').write_text('accepted')
            git('add','.'); git('commit','-qm','source'); source=git('rev-parse','HEAD')
            git('checkout','-q','--detach',base); git('merge','--no-ff','-qm','PR merge',source); checkout=git('rev-parse','HEAD')
            p=dict(source_ref=source,checkout_ref=checkout,version='0.2.2-rc8',acceptance=dict(head_sha=source))
            m.source_gate(root,p,checkout)
            git('tag','v0.2.2-rc8',base)
            with self.assertRaises(m.VerificationError): m.source_gate(root,p,checkout)
            git('tag','-d','v0.2.2-rc8')
            (root/'src/main/resources/example').write_text('changed')
            with self.assertRaises(m.VerificationError): m.source_gate(root,p,checkout)
            git('add','.'); git('commit','-qm','changed source'); changed=git('rev-parse','HEAD')
            with self.assertRaises(m.VerificationError): m.source_gate(root,p,changed)


class ProposedWorkflowTest(unittest.TestCase):
    def workflow(self):
        proposed = TOOLS.parent/'proposals/release.yml.proposed'
        return (proposed if proposed.exists() else TOOLS.parent/'.github/workflows/release.yml').read_text()

    def test_no_build_fallback_and_explicit_publish_gate(self):
        text = self.workflow()
        self.assertNotIn('./gradlew',text)
        self.assertNotIn('build/libs/',text)
        self.assertIn('default: false',text)
        self.assertIn("if: github.event_name == 'workflow_dispatch' && inputs.publish == true",text)
        self.assertIn('environment: release-promotion',text)
        self.assertIn('needs: verify',text)
        self.assertEqual(text.count('python3 tools/promote-candidate.py --tag'),2)
        self.assertIn('persist-credentials: false',text)

    def publish_code(self):
        text=self.workflow().split('      - name: Create new release with exact verified files\n',1)[1]
        code=text.split("          python3 - <<'PY'\n",1)[1].rsplit('          PY',1)[0]
        code='\n'.join(line[10:] for line in code.splitlines())
        ast.parse(code)
        return code

    def simulate(self, mode='success', tag='v0.2.2-rc8', notes=True):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); profile=root/'docs/release-candidates'; profile.mkdir(parents=True)
            out=root/'verified-release'; out.mkdir()
            name='qupath-extension-workshop-'+tag[1:]+'.jar'; jar=b'accepted fixture'
            (out/name).write_bytes(jar)
            for suffix in ['.sha256','.verification.json']: (out/(name+suffix)).write_text('fixture')
            (profile/(tag+'.json')).write_text(json.dumps(dict(jar_name=name,jar_bytes=len(jar),jar_sha256=m.digest(jar))))
            if notes:
                (root/'docs/releases').mkdir(); (root/'docs/releases'/(tag+'.md')).write_text('Release-specific notes')
            assets=[dict(id=i,name=f.name,size=f.stat().st_size) for i,f in enumerate(sorted(out.iterdir()),1)]
            release=dict(id=123,draft=True,assets=assets)
            calls=[]
            def run(args,**kwargs):
                calls.append(args)
                if args[:3]==['gh','api','--include']:
                    status=b'HTTP/2.0 404 Not Found\n' if mode not in ['exists','api-error'] else (b'HTTP/2.0 200 OK\n' if mode=='exists' else b'HTTP/2.0 500 Server Error\n')
                    return subprocess.CompletedProcess(args,0 if mode=='exists' else 1,status,b'')
                if args[:3]==['gh','release','create']: return subprocess.CompletedProcess(args,0)
                if args[:4]==['gh','api','--method','PATCH']: return subprocess.CompletedProcess(args,0)
                raise AssertionError('Unexpected command: '+str(args))
            def check_output(args,**kwargs):
                calls.append(args)
                if args[:2]==['gh','api'] and '/releases/tags/' in args[-1]:
                    if mode=='missing-upload': release['assets']=assets[:-1]
                    return json.dumps(release).encode()
                if args[:2]==['gh','api'] and '/environments/' in args[-1]:
                    return json.dumps(dict(protection_rules=[] if mode=='unprotected' else [dict(type='required_reviewers',reviewers=[dict(type='User')])])).encode()
                if args[:2]==['gh','api'] and '/releases/assets/' in args[-1]:
                    i=int(args[-1].rsplit('/',1)[1]); data=(out/assets[i-1]['name']).read_bytes()
                    return b'corrupt' if mode=='wrong-upload' else data
                raise AssertionError('Unexpected command: '+str(args))
            before=Path.cwd()
            try:
                os.chdir(root)
                with patch.dict(os.environ,dict(RELEASE_TAG=tag,GITHUB_REPOSITORY=m.REPOSITORY,GITHUB_SHA='a'*40,RUNNER_TEMP=str(root))), patch.object(subprocess,'run',side_effect=run), patch.object(subprocess,'check_output',side_effect=check_output):
                    if mode=='success': exec(self.publish_code(),{})
                    else:
                        with self.assertRaises(SystemExit): exec(self.publish_code(),{})
            finally: os.chdir(before)
            published=any(c[:4]==['gh','api','--method','PATCH'] for c in calls)
            self.assertEqual(published,mode=='success')
            return calls

    def test_mocked_success_draft_upload_verify_then_publish(self):
        calls=self.simulate()
        create=next(c for c in calls if c[:3]==['gh','release','create'])
        for flag in ['--draft','--prerelease','--latest=false','--notes-file','--generate-notes']: self.assertIn(flag,create)
        self.assertEqual(calls[-1][-2:],['-F','draft=false'])

    def test_existing_release_api_failure_and_bad_uploads_never_publish(self):
        for mode in ['unprotected','exists','api-error','missing-upload','wrong-upload']:
            with self.subTest(mode=mode): self.simulate(mode)

    def test_final_and_other_prerelease_classification_and_optional_notes(self):
        for tag in ['v0.2.2','v0.2.2-alpha1','v0.2.2-beta1']:
            calls=self.simulate(tag=tag,notes=False)
            create=next(c for c in calls if c[:3]==['gh','release','create'])
            self.assertNotIn('--notes-file',create)
            self.assertEqual('--prerelease' in create,tag!='v0.2.2')


if __name__ == '__main__': unittest.main()
