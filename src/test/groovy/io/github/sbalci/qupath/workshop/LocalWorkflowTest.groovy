package io.github.sbalci.qupath.workshop

import com.google.gson.*
import groovy.test.GroovyTestCase
import java.awt.image.BufferedImage
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean
import qupath.lib.images.ImageData
import qupath.lib.images.servers.WrappedBufferedImageServer
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.io.PathIO
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.projects.Project
import qupath.lib.projects.ProjectImageEntry
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs

/** Real QuPath objects and serialization; synthetic geometry, no models, GPU, GUI or network. */
class LocalWorkflowTest extends GroovyTestCase {
    Path temporary
    def plane = ImagePlane.getDefaultPlane()
    void setUp() { temporary = Files.createTempDirectory('atolye-workflow-test-') }
    void tearDown() { assert temporary.fileName.toString().startsWith('atolye-workflow-test-'); temporary.toFile().deleteDir() }
    def roi(double x=0,double width=100) { ROIs.createRectangleROI(x,0,width,100,plane) }
    def object(double x=10,String cls='Tumor') { PathObjects.createDetectionObject(ROIs.createRectangleROI(x,10,2,4,plane),PathClass.fromString(cls)) }
    def snapshot(List objects=[],double pw=0.5,double ph=1.0) { new WorkshopObjectSnapshot(roi(),objects,pw,ph) }
    def record(WorkshopRunStore store,String image='image-1') {
        WorkshopRunRecord.create(store.projectId(),image,'histoplus').with('scope_json',WorkshopObjectSnapshot.serializeRoi(roi())).with('analysis_unit_id','roi-1')
    }
    def measuredRecord(WorkshopRunStore store,List objects=[],String image='image-1',double x=0) {
        def snap=new WorkshopObjectSnapshot(roi(x),objects,0.5d,1d)
        def metrics=WorkshopMeasurementService.measure(snap,['Tumor','Stroma'],true)
        record(store,image).with('scope_json',WorkshopObjectSnapshot.serializeRoi(roi(x))).with('execution_status','succeeded').with('persistence_status','saved')
            .with('saved_snapshot_id',snap.id()).with('measurement_snapshot_id',snap.id()).withJson('snapshot',snap.json()).withJson('measurements',metrics)
    }
    void testSnapshotChangesForShapeClassCalibrationButNotRenameOrOrdering() {
        def a=object(),b=object(20,'Stroma');def before=snapshot([a,b]).id()
        assert before == snapshot([b,a]).id()
        a.name='renamed';assert before == snapshot([a,b]).id()
        a.setROI(ROIs.createRectangleROI(10,10,4,4,plane));assert before != snapshot([a,b]).id()
        def shape=snapshot([a,b]).id();a.pathClass=PathClass.fromString('Stroma');assert shape != snapshot([a,b]).id()
        assert snapshot([a,b],0.5d,1d).id()!=snapshot([a,b],0.5d,0.5d).id()
        shouldFail(IllegalArgumentException){snapshot([a,a])}
    }
    void testMeasurementsUseCurrentGeometryAnisotropicAreaAndCurrentClasses() {
        def a=object(),b=object(30,'Stroma'),outside=object(150)
        def metrics=WorkshopMeasurementService.measure(snapshot([a,b,outside]),['Tumor','Stroma','Other'],true)
        assert metrics.total_count.asInt==2
        assert metrics.excluded_objects.asInt==1
        assertEquals(0.005d,metrics.scope_area_mm2.asDouble,1e-12)
        assertEquals(4d,metrics.mean_nuclear_area_um2.asDouble,1e-12)
        def tumor=metrics.getAsJsonArray('classes').find{it.getAsJsonObject().get('class').asString=='Tumor'}
        assert tumor.count.asInt==1
        assertEquals(50d,tumor.percentage.asDouble,1e-12)
        assertEquals(200d,tumor.density_per_mm2.asDouble,1e-12)
        a.pathClass=PathClass.fromString('Stroma')
        assert WorkshopMeasurementService.measure(snapshot([a,b]),['Tumor','Stroma'],true).getAsJsonArray('classes').find{it.getAsJsonObject().get('class').asString=='Stroma'}.count.asInt==2
    }
    void testEmptyAndUncalibratedAndPromptScopesDoNotInventValues() {
        def empty=WorkshopMeasurementService.measure(snapshot(),['Tumor'],true)
        assert empty.total_count.asInt==0
        assert empty.classes[0].percentage.isJsonNull()
        assert empty.mean_nuclear_area_um2.isJsonNull()
        def missing=WorkshopMeasurementService.measure(snapshot([object()],Double.NaN,Double.NaN),['Tumor'],true)
        assert missing.scope_area_mm2.isJsonNull()
        assert missing.classes[0].density_per_mm2.isJsonNull()
        def prompted=WorkshopMeasurementService.measure(snapshot([object()]),['Tumor'],false)
        assert prompted.classes[0].density_per_mm2.isJsonNull()
    }
    void testRecordRoundTripFutureSchemaAndIdentityConflict() {
        def store=new WorkshopRunStore(temporary)
        def r=record(store)
        store.write(r)
        assert store.read(r.id()).resultId()==r.resultId()
        shouldFail(IOException){store.write(r.with('image_entry_id','other'))}
        def future=r.json();future.addProperty('schema_version',50)
        def f=new WorkshopRunRecord(future)
        assert !f.writable()
        shouldFail(IllegalStateException){store.write(f)}
        shouldFail(IllegalStateException){f.with('review_status','reviewed')}
        assert store.list().size()==1
    }
    void testConcurrentWriterAndSymlinkEscapeAreRejected() {
        def store=new WorkshopRunStore(temporary)
        def lock=store.lock()
        try { shouldFail(IOException){store.lock()} } finally {lock.close()}
        shouldFail(IllegalArgumentException){store.read('../outside')}
    }
    void testEvidenceKeepsFailuresDistinctFromSuccessfulZeroAndFiltersSecrets() {
        def store=new WorkshopRunStore(temporary)
        def success=measuredRecord(store)
        def runtime=new JsonObject();runtime.addProperty('token','PRIVATE_TOKEN');runtime.addProperty('python_path','C:/patient/private/python.exe');runtime.addProperty('device','cpu')
        success=success.withJson('runtime_identity',runtime)
        def failed=record(store).with('execution_status','failed').with('failure_reason','IOException')
        def destination=WorkshopEvidenceExporter.export(temporary,[success,failed])
        def evidence=Files.readString(destination.resolve('evidence.json'))
        assert !evidence.contains('PRIVATE_TOKEN') && !evidence.contains('patient/private')
        assert JsonParser.parseString(evidence).asJsonObject.complete.asBoolean==false
        def coverage=Files.readString(destination.resolve('coverage.tsv'))
        assert coverage.contains('succeeded\tsaved') && coverage.contains('failed\tunsaved')
        assert Files.readString(destination.resolve('per-region.tsv')).contains('\t0\t')
        assert Files.readString(destination.resolve('observations.tsv')).readLines().size()==1
        assert Files.exists(destination.resolve('summary.tsv'))
    }
    void testDuplicateAndOverlappingExportsFailWithoutTouchingPreviousBundle() {
        def store=new WorkshopRunStore(temporary);def a=measuredRecord(store,[object()]);def b=measuredRecord(store,[object(30)])
        def previous=WorkshopEvidenceExporter.export(temporary,[a]);def before=Files.readString(previous.resolve('evidence.json'))
        shouldFail(IllegalArgumentException){WorkshopEvidenceExporter.export(temporary,[a,a])}
        shouldFail(IllegalArgumentException){WorkshopEvidenceExporter.export(temporary,[a,b])}
        assert before==Files.readString(previous.resolve('evidence.json'))
        assert Files.list(temporary).withCloseable{it.filter{p->p.fileName.toString().startsWith('.atolye-export-')}.count()}==0
        shouldFail(IllegalStateException){WorkshopEvidenceExporter.export(temporary,[a.with('measurement_snapshot_id','old')])}
    }
    void testSummaryUsesPooledCountsInsteadOfAveragingRegionPercentages() {
        def store=new WorkshopRunStore(temporary)
        def first=measuredRecord(store,[object()])
        def second=measuredRecord(store,[object(160,'Stroma'),object(170,'Stroma'),object(180,'Stroma')],'image-1',150)
        def identity=new JsonObject();identity.addProperty('weight_sha256','a'*64)
        first=first.withJson('model_identity',identity);second=second.withJson('model_identity',identity)
        def output=WorkshopEvidenceExporter.export(temporary,[first,second])
        def tumor=Files.readAllLines(output.resolve('summary.tsv')).find{it.split('\t',-1)[3]=='Tumor'}.split('\t',-1)
        assert tumor[4]=='1'
        assert Double.parseDouble(tumor[5])==25d
        assert Double.parseDouble(tumor[6])==100d
        assert tumor[8]=='2'
    }
    def recipeJson() {
        JsonParser.parseString('''{"schema_version":1,"adapter":"histoplus","model":"40x","revision":"cde2eee81af9e39b03802fc33d4f284733b5ee5e","weight_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","runner_sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","device":"cpu","target_mpp":0.25,"parameters":{"batch_size":4,"pp_threads":2},"measurement_version":1,"class_mapping_version":1}''').asJsonObject
    }
    void testRecipeRoundTripAndCanonicalOrdering() {
        def a=new WorkshopRecipe(recipeJson());def reordered=new JsonObject()
        recipeJson().entrySet().toList().reverse().each{reordered.add(it.key,it.value)}
        assert a.signature()==new WorkshopRecipe(reordered).signature()
        def path=temporary.resolve('recipe.json');a.save(path)
        assert a.signature()==WorkshopRecipe.load(path).signature()
        shouldFail(FileAlreadyExistsException){a.save(path)}
    }
    void testRecipeRejectsCodePathsUnknownVersionsAndIncompatibleSettings() {
        ['command','python_path','script','postprocessing'].each{key->def j=recipeJson();j.addProperty(key,'run something');shouldFail(IllegalArgumentException){new WorkshopRecipe(j)}}
        [2,0,1.5].each{v->def j=recipeJson();j.addProperty('schema_version',v);shouldFail(IllegalArgumentException){new WorkshopRecipe(j)}}
        def wrongScale=recipeJson();wrongScale.addProperty('target_mpp',0.5);shouldFail(IllegalArgumentException){new WorkshopRecipe(wrongScale)}
        def auto=recipeJson();auto.addProperty('device','auto');shouldFail(IllegalArgumentException){new WorkshopRecipe(auto)}
        def command=recipeJson();command.parameters.addProperty('command','bad');shouldFail(IllegalArgumentException){new WorkshopRecipe(command)}
        def batch=recipeJson();batch.parameters.addProperty('batch_size',1.5);shouldFail(IllegalArgumentException){new WorkshopRecipe(batch)}
    }
    void testReuseRequiresStrongIdentityAndDurableSuccessIncludingEmptyOutput() {
        def store=new WorkshopRunStore(temporary)
        def r=measuredRecord(store).with('source_identity_strength','strong').with('input_signature','same')
        assert WorkshopExecutionPolicy.reusable(r,'same')
        assert !WorkshopExecutionPolicy.reusable(r,'different')
        assert !WorkshopExecutionPolicy.reusable(r.with('source_identity_strength','unverified'),'same')
        assert !WorkshopExecutionPolicy.reusable(r.with('persistence_status','saving'),'same')
        shouldFail(IllegalStateException){WorkshopExecutionPolicy.requireReplacement(r,false)}
    }
    void testReadinessInspectsBytesWithoutRunningTheExecutable() {
        def executable=temporary.resolve('python.exe');Files.writeString(executable,'not executable')
        def runner=temporary.resolve('runner.py');Files.writeString(runner,'raise Exception("must not execute")')
        def weights=temporary.resolve('weights.pt');Files.writeString(weights,'synthetic weights')
        def descriptor=new WorkshopModelDescriptor('histoplus','40x','revision','weights.pt',WorkshopRunStore.sha256(weights),0.25d,'test',executable,runner,weights,true)
        assert WorkshopModelReadiness.inspect(descriptor,true).ready()
        assert !WorkshopModelReadiness.inspect(descriptor,false).ready()
        Files.writeString(weights,'changed');assert WorkshopModelReadiness.inspect(descriptor,true).problems().contains('workflow.problem.corrupt')
    }
    void testQueuePersistsRequestsAndFailureRecordsAndDoesNotAutostart() {
        def store=new WorkshopRunStore(temporary);def queue=new WorkshopLocalJobQueue(store)
        def job=queue.enqueue('image-1',WorkshopObjectSnapshot.serializeRoi(roi()),new WorkshopRecipe(recipeJson()),WorkshopExecutionPolicy.Action.ADD,'','',false)
        assert queue.list()[0].status.asString=='queued'
        assert store.list()[0].get('execution_status')=='requested'
        shouldFail(IOException){queue.runNext({j,cancel->throw new IOException('simulated')} as WorkshopLocalJobQueue.Executor)}
        assert queue.list()[0].status.asString=='failed'
        assert store.list()[0].get('execution_status')=='failed'
        queue.retry(job.job_id.asString)
        assert queue.list()[0].status.asString=='queued'
    }
    void testQueueSuccessRequiresAnActualSavedRecordAndInterruptedJobsNeedRecovery() {
        def store=new WorkshopRunStore(temporary);def queue=new WorkshopLocalJobQueue(store)
        def job=queue.enqueue('image-1',WorkshopObjectSnapshot.serializeRoi(roi()),new WorkshopRecipe(recipeJson()),WorkshopExecutionPolicy.Action.ADD,'','',false)
        def saved=measuredRecord(store);store.write(saved)
        assert queue.runNext({j,cancel->saved.id()} as WorkshopLocalJobQueue.Executor)
        assert queue.list()[0].status.asString=='saved'
        assert !queue.runNext({j,cancel->throw new AssertionError()} as WorkshopLocalJobQueue.Executor)
        def next=queue.enqueue('image-1',WorkshopObjectSnapshot.serializeRoi(roi()),new WorkshopRecipe(recipeJson()),WorkshopExecutionPolicy.Action.ADD,'','',false)
        next.addProperty('status','saving');WorkshopRunStore.writeJson(store.root().resolve('job-'+next.job_id.asString+'.json'),next)
        queue.reconcileInterrupted()
        assert queue.list().last().status.asString=='recovery-required'
    }
    void testCancellationDoesNotBeginAnotherJob() {
        def store=new WorkshopRunStore(temporary);def queue=new WorkshopLocalJobQueue(store)
        queue.enqueue('image-1',WorkshopObjectSnapshot.serializeRoi(roi()),new WorkshopRecipe(recipeJson()),WorkshopExecutionPolicy.Action.ADD,'','',false)
        queue.cancel();assert !queue.runNext({j,cancel->throw new AssertionError()} as WorkshopLocalJobQueue.Executor)
        assert queue.list()[0].status.asString=='queued'
    }
    void testCrashAfterResultCommitLinksTheJobWithoutRepeatingInference() {
        def store=new WorkshopRunStore(temporary);def queue=new WorkshopLocalJobQueue(store)
        def job=queue.enqueue('image-1',WorkshopObjectSnapshot.serializeRoi(roi()),new WorkshopRecipe(recipeJson()),WorkshopExecutionPolicy.Action.ADD,'','',false)
        def committed=measuredRecord(store).with('job_id',job.job_id.asString);store.write(committed)
        job.addProperty('status','running');WorkshopRunStore.writeJson(store.root().resolve('job-'+job.job_id.asString+'.json'),job)
        queue.reconcileInterrupted()
        assert queue.list()[0].status.asString=='saved'
        assert queue.list()[0].run_id.asString==committed.id()
        assert !queue.runNext({j,cancel->throw new AssertionError('Committed job must not run again')} as WorkshopLocalJobQueue.Executor)
    }
    def fixture(java.net.URI source=null) {
        def server=new WrappedBufferedImageServer('synthetic',new BufferedImage(200,200,BufferedImage.TYPE_INT_RGB)) {
            @Override Collection<java.net.URI> getURIs(){return source == null ? Collections.emptyList() : Collections.singletonList(source)}
        }
        def data=new ImageData<BufferedImage>(server)
        data.updateServerMetadata(new ImageServerMetadata.Builder(server.metadata).pixelSizeMicrons(0.5d,1d).build())
        def imageFile=temporary.resolve('image.qpdata');def failSave=new AtomicBoolean(false)
        def entry=[getID:{'image-1'},getEntryPath:{temporary},saveImageData:{ImageData d->if(failSave.getAndSet(false))throw new IOException('injected save failure');PathIO.writeImageData(imageFile,d)},readImageData:{PathIO.readImageData(imageFile,server)}] as ProjectImageEntry
        def project=[getPath:{temporary.resolve('project.qpproj')},getEntry:{ImageData d->entry}] as Project
        [data:data,entry:entry,project:project,failSave:failSave,server:server]
    }
    void testOwnershipSaveReopenRefreshAndManualInclusion() {
        def f=fixture();def a=object();def unrelated=object(25);def summary=PathObjects.createAnnotationObject(roi())
        f.data.hierarchy.addObjects([a,unrelated,summary])
        def id=WorkshopLocalWorkflow.recordCompleted(f.project,f.data,'histoplus',roi(),[a],summary,[:])
        def store=WorkshopLocalWorkflow.store(f.project);def r=store.read(id)
        assert r.get('persistence_status')=='saved'
        assert WorkshopLocalWorkflow.owned(f.data,r,false)==[a]
        def reopened=f.entry.readImageData();assert WorkshopLocalWorkflow.owned(reopened,r,false)[0].ID==a.ID
        a.setROI(ROIs.createRectangleROI(10,10,4,4,plane))
        def refreshed=WorkshopLocalWorkflow.refresh(f.project,f.data,id,true)
        assert refreshed.json().measurements.total_count.asInt==1
        assert refreshed.get('review_status')=='reviewed'
        assertEquals(8d,refreshed.json().measurements.mean_nuclear_area_um2.asDouble,0d)
        assert refreshed.json().prediction_snapshot.objects[0].roi!=refreshed.json().snapshot.objects[0].roi
        WorkshopLocalWorkflow.includeObjects(f.project,f.data,id,[unrelated])
        assert store.read(id).get('review_status')=='needs-review'
        shouldFail(IllegalStateException){WorkshopLocalWorkflow.export(f.project,f.data,[id],temporary)}
        def updated=WorkshopLocalWorkflow.refresh(f.project,f.data,id,false)
        assert updated.json().measurements.total_count.asInt==2
    }
    void testClassificationReferencesRetainOriginalDetectionOwnershipAndPriorClasses() {
        def f=fixture();def cell=PathObjects.createCellObject(ROIs.createRectangleROI(10,10,5,8,plane),ROIs.createRectangleROI(11,11,2,4,plane),PathClass.fromString('Before'),null)
        cell.metadata.put(WorkshopRunRecord.RESULT_TAG,'original-detection-owner');cell.metadata.put(WorkshopRunRecord.ROLE_TAG,'object')
        f.data.hierarchy.addObject(cell);def previous=[(cell.ID.toString()):'Before'];cell.pathClass=PathClass.fromString('After')
        def id=WorkshopLocalWorkflow.recordClassificationResult(f.project,f.data,roi(),[cell],[previous_classes:previous])
        def record=WorkshopLocalWorkflow.store(f.project).read(id)
        assert cell.metadata.get(WorkshopRunRecord.RESULT_TAG)=='original-detection-owner'
        assert WorkshopLocalWorkflow.owned(f.data,record,false)==[cell]
        assert record.json().previous_classes.get(cell.ID.toString()).asString=='Before'
        def measured=WorkshopLocalWorkflow.refresh(f.project,f.data,id,false)
        assert measured.json().measurements.has('mean_object_area_um2')
        assert !measured.json().measurements.has('mean_nuclear_area_um2')
    }
    void testReplacementFailureRestoresObjectsAndConcurrentEditStopsReplacement() {
        def f=fixture();def a=object();def summary=PathObjects.createAnnotationObject(roi());f.data.hierarchy.addObjects([a,summary])
        def id=WorkshopLocalWorkflow.recordCompleted(f.project,f.data,'histoplus',roi(),[a],summary,[:]);def record=WorkshopLocalWorkflow.store(f.project).read(id)
        def expected=WorkshopLocalWorkflow.snapshot(f.data,record).id()
        def facts=new JsonObject();facts.addProperty('source_sha256','a'*64)
        f.failSave.set(true)
        shouldFail(IOException){WorkshopLocalWorkflow.commitImported(f.project,f.data,'histoplus',roi(),[object(30)],PathObjects.createAnnotationObject(roi()),facts,'sig',id,expected,new AtomicBoolean())}
        assert WorkshopLocalWorkflow.owned(f.data,record,false)==[a]
        assert f.data.hierarchy.getFlattenedObjectList(null).contains(summary)
        a.pathClass=PathClass.fromString('Changed')
        shouldFail(IllegalStateException){WorkshopLocalWorkflow.commitImported(f.project,f.data,'histoplus',roi(),[object(30)],PathObjects.createAnnotationObject(roi()),facts,'sig',id,expected,new AtomicBoolean())}
        assert WorkshopLocalWorkflow.owned(f.data,record,false)==[a]
    }
    void testParseModelPolygonWithHolePreservesGeometryAndClass() {
        def json='''{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[10,10],[20,10],[20,20],[10,20],[10,10]],[[12,12],[12,14],[14,14],[14,12],[12,12]]]},"properties":{"classification":{"name":"Tumor"}}}]}'''
        def path=temporary.resolve('result.json');Files.writeString(path,json)
        def parsed=WorkshopLocalRunner.parseHistoPlus(path,roi())
        assert parsed.size()==1
        assertEquals(96d,parsed[0].ROI.getArea(),0d)
        assert parsed[0].pathClass.toString()=='Cancer cell (HistoPLUS)'
    }
    void testPreparedRunnerWiresAddCompleteRegenerateAndLateCancellationWithoutARealModel() {
        def source=temporary.resolve('synthetic-source.bin');Files.writeString(source,'fixed source pixels')
        def f=fixture(source.toUri())
        f.data.setImageType(ImageData.ImageType.BRIGHTFIELD_H_E)
        def python=temporary.resolve('python.exe');Files.writeString(python,'fixture')
        def runner=temporary.resolve('runner.py');Files.writeString(runner,'fixture runner')
        def weights=temporary.resolve('weights.pt');Files.writeString(weights,'fixture weights')
        def descriptor=new WorkshopModelDescriptor('histoplus','40x',WorkshopModelDescriptor.HISTOPLUS_REVISION,'weights.pt',WorkshopRunStore.sha256(weights),0.25d,'fixture',python,runner,weights,true)
        def ready=WorkshopModelReadiness.inspect(descriptor,true)
        def recipe=WorkshopRecipe.prepared(descriptor,ready,'cpu')
        def unrelated=object(25);f.data.hierarchy.addObject(unrelated)
        int detectCalls=0;boolean cancelAfterOutput=false
        def backend={model,arguments,work,cancelled->
            if(arguments[0]=='--device')return ['{"python_version":"fixture","packages":[],"actual_device":"cpu"}']
            if(arguments[0]=='precheck')return ['PRECHECK ok=1 model=40x bounds_x=0 bounds_y=0 width=200 height=200']
            assert arguments[0]=='detect';detectCalls++
            Files.writeString(work.resolve('histoplus_cells.geojson'),'''{"features":[{"geometry":{"type":"Polygon","coordinates":[[[10,10],[12,10],[12,14],[10,14],[10,10]]]},"properties":{"classification":{"name":"Tumor"}}}]}''')
            Files.writeString(work.resolve('histoplus_run.json'),' {"device":"cpu","model":"40x"}')
            if(cancelAfterOutput)cancelled.set(true)
            return ['DONE']
        } as WorkshopLocalRunner.Backend
        def store=WorkshopLocalWorkflow.store(f.project);def queue=new WorkshopLocalJobQueue(store)
        def request={action,replace='',snapshot='',confirmed=false->queue.enqueue('image-1',WorkshopObjectSnapshot.serializeRoi(roi()),recipe,action,replace,snapshot,confirmed)}
        def firstJob=request(WorkshopExecutionPolicy.Action.ADD)
        def first=WorkshopLocalRunner.executePrepared(f.project,f.data,firstJob,new AtomicBoolean(),descriptor,runner,backend)
        def original=store.read(first);assert detectCalls==1
        def detection=WorkshopLocalWorkflow.owned(f.data,original,false)[0]
        detection.setROI(ROIs.createRectangleROI(10,10,4,4,plane))
        def complete=request(WorkshopExecutionPolicy.Action.COMPLETE_MISSING)
        shouldFail(IllegalStateException){WorkshopLocalRunner.executePrepared(f.project,f.data,complete,new AtomicBoolean(),descriptor,runner,backend)}
        WorkshopLocalWorkflow.refresh(f.project,f.data,first,true)
        assert WorkshopLocalRunner.executePrepared(f.project,f.data,complete,new AtomicBoolean(),descriptor,runner,backend)==first
        assert detectCalls==1
        def expected=WorkshopLocalWorkflow.snapshot(f.data,original).id()
        def replace=request(WorkshopExecutionPolicy.Action.REGENERATE_SELECTED,first,expected,true)
        def second=WorkshopLocalRunner.executePrepared(f.project,f.data,replace,new AtomicBoolean(),descriptor,runner,backend)
        assert second!=first && detectCalls==2
        assert store.read(first).get('persistence_status')=='replaced'
        assert f.data.hierarchy.detectionObjects.contains(unrelated)
        assert !f.data.hierarchy.detectionObjects.contains(detection)
        def before=f.data.hierarchy.detectionObjects.collect{it.ID}.toSet();cancelAfterOutput=true
        shouldFail(InterruptedException){WorkshopLocalRunner.executePrepared(f.project,f.data,request(WorkshopExecutionPolicy.Action.ADD),new AtomicBoolean(),descriptor,runner,backend)}
        assert f.data.hierarchy.detectionObjects.collect{it.ID}.toSet()==before
    }
}
