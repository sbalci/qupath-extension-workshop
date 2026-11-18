package io.github.sbalci.qupath.workshop;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;

/** Headless-callable workflow facade, also used reflectively by standalone wizard integrations. */
public final class WorkshopLocalWorkflow {
    private WorkshopLocalWorkflow() {}
    public static <T> T onHost(java.util.concurrent.Callable<T> call) throws Exception {
        Class<?> platform;
        try { platform=Class.forName("javafx.application.Platform"); } catch(ClassNotFoundException e){return call.call();}
        if((boolean)platform.getMethod("isFxApplicationThread").invoke(null))return call.call();
        FutureTask<T> task=new FutureTask<>(call);
        try { platform.getMethod("runLater",Runnable.class).invoke(null,task); }
        catch(java.lang.reflect.InvocationTargetException e){
            if(e.getCause() instanceof IllegalStateException)return call.call(); throw e;
        }
        try{return task.get();}catch(ExecutionException e){if(e.getCause() instanceof Exception ex)throw ex;throw e;}
    }
    public static WorkshopRunStore store(Project<?> project) throws IOException {
        if(project==null||project.getPath()==null)throw new IllegalArgumentException("Save a QuPath project first");
        return new WorkshopRunStore(project.getPath().getParent());
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    static ProjectImageEntry<BufferedImage> entry(Project<?> project,ImageData<?> data) {
        ProjectImageEntry<BufferedImage> entry=((Project<BufferedImage>)project).getEntry((ImageData)data);
        if(entry==null)throw new IllegalArgumentException("Image is not in the open project");return entry;
    }
    private static void requireContext(WorkshopRunRecord record,String projectId,String imageId){
        if(!record.get("project_id").equals(projectId)||!record.get("image_entry_id").equals(imageId))throw new IllegalArgumentException("Record belongs to another project or image");
    }
    public static List<PathObject> owned(ImageData<?> data,WorkshopRunRecord record,boolean summary) {
        if(!summary&&record.get("adapter_id").equals("cytoformer")&&record.json().has("referenced_object_ids")){
            Set<String> ids=new HashSet<>();record.json().getAsJsonArray("referenced_object_ids").forEach(e->ids.add(e.getAsString()));
            return data.getHierarchy().getFlattenedObjectList(null).stream().filter(o->ids.contains(o.getID().toString())).toList();
        }
        return data.getHierarchy().getFlattenedObjectList(null).stream().filter(o->o.hasMetadata()
                &&record.resultId().equals(o.getMetadata().get(WorkshopRunRecord.RESULT_TAG))
                &&(summary?"summary":"object").equals(o.getMetadata().get(WorkshopRunRecord.ROLE_TAG))).toList();
    }
    static ROI scope(ImageData<?> data,WorkshopRunRecord record) {
        var summaries=owned(data,record,true);
        if(summaries.size()!=1)throw new IllegalStateException("Missing or duplicate owned result summary");
        return summaries.getFirst().getROI();
    }
    public static WorkshopObjectSnapshot snapshot(ImageData<?> data,WorkshopRunRecord record) {
        var cal=data.getServer().getPixelCalibration();
        return new WorkshopObjectSnapshot(scope(data,record),owned(data,record,false),cal.getPixelWidthMicrons(),cal.getPixelHeightMicrons());
    }
    /** Evidence map contains observed facts, not inferred preference values. No file reads happen on the host thread. */
    public static String recordCompleted(Project<?> project,ImageData<BufferedImage> data,String adapter,ROI roi,
            Collection<? extends PathObject> objects,PathObject summary,Map<String,?> evidence) throws Exception {
        if(project==null)return "";
        WorkshopRunStore store=store(project); String projectId=store.projectId();
        var entry=entry(project,data);
        WorkshopRunRecord record=WorkshopRunRecord.create(projectId,entry.getID(),adapter)
                .with("scope_json",WorkshopObjectSnapshot.serializeRoi(roi))
                .with("analysis_unit_id",WorkshopRunStore.sha256(WorkshopObjectSnapshot.roiJson(roi).toString()))
                .with("execution_status","succeeded").with("persistence_status","saving");
        JsonObject facts=WorkshopRunStore.JSON.toJsonTree(evidence).getAsJsonObject();
        JsonArray expectedObjects=new JsonArray();objects.forEach(o->expectedObjects.add(o.getID().toString()));
        record=record.withJson("expected_object_ids",expectedObjects).with("expected_summary_id",summary.getID().toString());
        if(adapter.equals("cytoformer")){
            JsonArray references=new JsonArray();objects.forEach(o->references.add(o.getID().toString()));record=record.withJson("referenced_object_ids",references);
        }
        for(String key:List.of("model_identity","runtime_identity","effective_settings","component_rois","prompt_outcomes","previous_classes"))
            if(facts.has(key))record=record.withJson(key,facts.get(key));
        record=record.with("source_identity_strength",facts.has("source_sha256")?"strong":"unverified");
        if(facts.has("source_sha256"))record=record.with("source_sha256",facts.get("source_sha256").getAsString());
        JsonObject signature=new JsonObject();signature.add("scope",WorkshopObjectSnapshot.roiJson(roi));
        signature.add("facts",facts); signature.addProperty("adapter",adapter);
        record=record.with("input_signature",WorkshopRunStore.sha256(signature.toString()));
        final WorkshopRunRecord initial=record;
        try(var ignored=store.lock()){
            store.write(initial);
            WorkshopObjectSnapshot snapshot=onHost(()->{
                for(PathObject o:objects) {
                    if(!adapter.equals("cytoformer")&&o.hasMetadata()&&o.getMetadata().containsKey(WorkshopRunRecord.RESULT_TAG))throw new IllegalArgumentException("Object is already owned by a result set");
                }
                if(!adapter.equals("cytoformer"))for(PathObject o:objects){o.getMetadata().put(WorkshopRunRecord.RESULT_TAG,initial.resultId());o.getMetadata().put(WorkshopRunRecord.ROLE_TAG,"object");
                    String original=o.getMetadata().containsKey(WorkshopRunRecord.ORIGINAL_CLASS_TAG)?o.getMetadata().get(WorkshopRunRecord.ORIGINAL_CLASS_TAG):facts.has("previous_classes")&&facts.getAsJsonObject("previous_classes").has(o.getID().toString())
                            ?facts.getAsJsonObject("previous_classes").get(o.getID().toString()).getAsString():o.getPathClass()==null?"":o.getPathClass().toString();
                    o.getMetadata().put(WorkshopRunRecord.ORIGINAL_CLASS_TAG,original);}
                summary.getMetadata().put(WorkshopRunRecord.RESULT_TAG,initial.resultId());summary.getMetadata().put(WorkshopRunRecord.ROLE_TAG,"summary");
                var snap=snapshot(data,initial);entry.saveImageData(data);return snap;
            });
            String initialKind=facts.has("capture_origin")&&facts.get("capture_origin").getAsString().equals("legacy_wizard")?"initial_recorded_snapshot":"prediction_snapshot";
            store.write(initial.with("persistence_status","saved").with("saved_snapshot_id",snapshot.id()).withJson("snapshot",snapshot.json()).withJson(initialKind,snapshot.json()));
        }
        return initial.id();
    }
    /** Wizard bridge: observations are recorded after inference; automatic input reuse remains disabled. */
    public static String recordWizardResult(Project<?> project,ImageData<BufferedImage> data,String adapter,ROI roi,
            Collection<? extends PathObject> objects,PathObject summary,Map<String,?> configuration)throws Exception{
        if(project==null)return "";
        Map<String,Object> evidence=new LinkedHashMap<>();Map<String,Object> runtime=new LinkedHashMap<>();Map<String,Object> model=new LinkedHashMap<>();
        evidence.put("capture_origin","legacy_wizard");
        runtime.put("qupath_version",qupath.lib.common.GeneralTools.getVersion());runtime.put("extension_version",WorkshopResources.getExtensionVersion());
        for(String field:List.of("runner","weights")){
            Object path=configuration.get(field);if(path!=null&&!path.toString().isBlank()&&Files.isRegularFile(Path.of(path.toString())))
                (field.equals("runner")?runtime:model).put(field.equals("runner")?"runner_sha256":"weight_sha256",WorkshopRunStore.sha256(Path.of(path.toString())));
        }
        runtime.put("actual_device",configuration.getOrDefault("actual_device",null));runtime.put("precision",configuration.getOrDefault("precision",null));
        model.put("revision",configuration.get("revision"));model.put("classes",configuration.getOrDefault("classes",null));
        if(configuration.containsKey("class_mapping"))model.put("class_mapping",configuration.get("class_mapping"));
        evidence.put("runtime_identity",runtime);evidence.put("model_identity",model);
        if(configuration.containsKey("settings"))evidence.put("effective_settings",configuration.get("settings"));
        if(configuration.containsKey("previous_classes"))evidence.put("previous_classes",configuration.get("previous_classes"));
        if(configuration.containsKey("prompt_outcomes"))evidence.put("prompt_outcomes",configuration.get("prompt_outcomes"));
        if(configuration.containsKey("component_rois"))evidence.put("component_rois",configuration.get("component_rois"));
        return recordCompleted(project,data,adapter,roi,objects,summary,evidence);
    }
    public static String recordClassificationResult(Project<?> project,ImageData<BufferedImage> data,ROI roi,
            Collection<? extends PathObject> objects,Map<String,?> configuration)throws Exception{
        if(project==null)return "";
        var summary=onHost(()->{var o=qupath.lib.objects.PathObjects.createAnnotationObject(roi);o.setName("Atolye CytoFormer "+UUID.randomUUID().toString().substring(0,8));o.setLocked(true);data.getHierarchy().addObject(o);return o;});
        return recordWizardResult(project,data,"cytoformer",roi,objects,summary,configuration);
    }
    public static String commitImported(Project<?> project,ImageData<BufferedImage> data,String adapter,ROI roi,
            List<PathObject> objects,PathObject summary,JsonObject facts,String inputSignature,
            String replaceRunId,String expectedSnapshot,java.util.concurrent.atomic.AtomicBoolean cancelled)throws Exception{
        var store=store(project);String projectId=store.projectId();var entry=entry(project,data);
        var record=WorkshopRunRecord.create(projectId,entry.getID(),adapter).with("scope_json",WorkshopObjectSnapshot.serializeRoi(roi))
                .with("analysis_unit_id",WorkshopRunStore.sha256(WorkshopObjectSnapshot.roiJson(roi).toString()))
                .with("input_signature",inputSignature).with("source_identity_strength","strong").with("source_sha256",facts.get("source_sha256").getAsString())
                .with("execution_status","succeeded").with("persistence_status","saving");
        if(facts.has("job_id"))record=record.with("job_id",facts.get("job_id").getAsString());
        JsonArray expectedIds=new JsonArray();objects.forEach(o->expectedIds.add(o.getID().toString()));
        record=record.withJson("expected_object_ids",expectedIds).with("expected_summary_id",summary.getID().toString());
        for(String key:List.of("model_identity","runtime_identity","effective_settings","prompt_outcomes"))if(facts.has(key))record=record.withJson(key,facts.get(key));
        final var initial=record;
        try(var ignored=store.lock()){
            WorkshopRunRecord previous=replaceRunId.isBlank()?null:store.read(replaceRunId);
            if(previous!=null){WorkshopExecutionPolicy.requireReplacement(previous,true);requireContext(previous,projectId,entry.getID());
                if(!previous.get("adapter_id").equals(adapter))throw new IllegalArgumentException("Regeneration must use the same adapter; add a separate result for another model family");}
            if(facts.has("entry_data_sha256")&&!imageDataStamp(entry).equals(facts.get("entry_data_sha256").getAsString()))throw new IllegalStateException("Saved image data changed in another session; reopen the image before retrying");
            store.write(initial);
            var snap=onHost(()->{
                if(cancelled.get())throw new InterruptedException("Cancelled before commit");
                if(previous!=null&&!snapshot(data,previous).id().equals(expectedSnapshot))throw new IllegalStateException("Replacement target changed while inference ran");
                List<PathObject> old=new ArrayList<>();Map<PathObject,PathObject> parents=new IdentityHashMap<>();
                if(previous!=null){old.addAll(owned(data,previous,false));old.addAll(owned(data,previous,true));
                    for(var o:data.getHierarchy().getFlattenedObjectList(null))if(o.getParent()!=null)parents.put(o,o.getParent());}
                var h=data.getHierarchy();
                try{
                    for(var o:objects){o.getMetadata().put(WorkshopRunRecord.RESULT_TAG,initial.resultId());o.getMetadata().put(WorkshopRunRecord.ROLE_TAG,"object");
                        o.getMetadata().put(WorkshopRunRecord.ORIGINAL_CLASS_TAG,o.getPathClass()==null?"":o.getPathClass().toString());}
                    summary.getMetadata().put(WorkshopRunRecord.RESULT_TAG,initial.resultId());summary.getMetadata().put(WorkshopRunRecord.ROLE_TAG,"summary");
                    if(!old.isEmpty())h.removeObjects(old,true);
                    h.addObjects(objects);h.addObject(summary);h.fireHierarchyChangedEvent(WorkshopLocalWorkflow.class);
                    var current=snapshot(data,initial);entry.saveImageData(data);return current;
                }catch(Exception ex){
                    h.removeObjects(objects,true);h.removeObject(summary,true);h.addObjects(old);
                    parents.forEach((child,parent)->{if(child.getParent()!=parent)parent.addChildObject(child);});
                    h.fireHierarchyChangedEvent(WorkshopLocalWorkflow.class);throw ex;
                }
            });
            store.write(initial.with("persistence_status","saved").with("saved_snapshot_id",snap.id()).withJson("snapshot",snap.json()).withJson("prediction_snapshot",snap.json()));
            if(previous!=null)store.write(previous.with("persistence_status","replaced").with("superseded_by",initial.resultId()));
            return initial.id();
        }
    }
    public static WorkshopRunRecord refresh(Project<?> project,ImageData<BufferedImage> data,String runId,boolean review) throws Exception {
        WorkshopRunStore store=store(project);var entry=entry(project,data);String projectId=store.projectId();
        try(var ignored=store.lock()){
            var record=store.read(runId);record.checkWritable();
            requireContext(record,projectId,entry.getID());
            if(!record.get("execution_status").equals("succeeded"))throw new IllegalStateException("This run has no successful object result");
            var snap=onHost(()->snapshot(data,record));
            List<String> classes=new ArrayList<>();
            if(record.json().has("model_identity")&&record.json().getAsJsonObject("model_identity").has("classes"))
                record.json().getAsJsonObject("model_identity").getAsJsonArray("classes").forEach(e->classes.add(e.getAsString()));
            Map<String,String> classMap=new HashMap<>();
            if(record.json().has("model_identity")&&record.json().getAsJsonObject("model_identity").has("class_mapping"))
                record.json().getAsJsonObject("model_identity").getAsJsonObject("class_mapping").entrySet().forEach(e->classMap.put(e.getKey(),e.getValue().getAsString()));
            var canonicalClasses=classes.stream().map(c->classMap.getOrDefault(c,c)).distinct().toList();
            var measured=WorkshopMeasurementService.measure(snap,canonicalClasses,!record.get("adapter_id").equals("nuclick"),classMap,!record.get("adapter_id").equals("cytoformer"));
            var updated=record.with("persistence_status","saving");store.write(updated);
            onHost(()->{
                if(!snapshot(data,record).id().equals(snap.id()))throw new IllegalStateException("Objects changed during measurement; refresh again");
                var ml=owned(data,record,true).getFirst().getMeasurementList();
                for(String key:new ArrayList<>(ml.keySet()))if(key.startsWith("Atolye workflow: "))ml.remove(key);
                ml.put("Atolye workflow: count",measured.get("total_count").getAsDouble());
                ml.put("Atolye workflow: excluded",measured.get("excluded_objects").getAsDouble());
                for(JsonElement e:measured.getAsJsonArray("classes")){var row=e.getAsJsonObject();String c=row.get("class").getAsString();
                    ml.put("Atolye workflow: "+c+" count",row.get("count").getAsDouble());}
                data.getHierarchy().fireHierarchyChangedEvent(WorkshopLocalWorkflow.class);entry.saveImageData(data);return null;
            });
            String reviewStatus=review?"reviewed":record.get("reviewed_snapshot_id").equals(snap.id())?"reviewed":record.get("review_status").equals("unreviewed")?"unreviewed":"needs-review";
            updated=updated.with("persistence_status","saved").with("saved_snapshot_id",snap.id()).withJson("snapshot",snap.json())
                    .with("measurement_snapshot_id",snap.id()).with("measurement_scope_json",WorkshopObjectSnapshot.serializeRoi(snap.scope())).withJson("measurements",measured).with("review_status",reviewStatus);
            if(review)updated=updated.with("reviewed_snapshot_id",snap.id());
            store.write(updated);return updated;
        }
    }
    public static WorkshopRunRecord reconcile(Project<?> project,ImageData<BufferedImage> data,String id)throws Exception{
        WorkshopRunStore store=store(project);var entry=entry(project,data);String projectId=store.projectId();
        try(var ignored=store.lock()){
            var record=store.read(id);record.checkWritable();
            requireContext(record,projectId,entry.getID());
            if(record.get("persistence_status").equals("replaced"))throw new IllegalStateException("This is a historical replaced result, not an active commit");
            // Reopen durable image data; the live hierarchy is not evidence of persistence.
            var disk=entry.readImageData();
            try{
                var summaries=owned(disk,record,true);var objects=owned(disk,record,false);
                if(summaries.isEmpty()&&objects.isEmpty()){
                    var absent=record.with("persistence_status","unsaved").with("execution_status","failed").with("failure_reason","commit_not_present_on_disk");store.write(absent);return absent;
                }
                if(record.get("persistence_status").equals("saving")&&record.json().has("expected_object_ids")){
                    Set<String> expected=new HashSet<>();record.json().getAsJsonArray("expected_object_ids").forEach(e->expected.add(e.getAsString()));
                    var actual=objects.stream().map(o->o.getID().toString()).collect(java.util.stream.Collectors.toSet());
                    if(!expected.equals(actual)||summaries.size()!=1||!summaries.getFirst().getID().toString().equals(record.get("expected_summary_id")))throw new IllegalStateException("Partial or conflicting commit; inspect saved objects before retrying");
                }
                var snap=snapshot(disk,record);
                var fixed=record.with("persistence_status","saved").with("saved_snapshot_id",snap.id()).withJson("snapshot",snap.json());
                if(!record.get("reviewed_snapshot_id").equals(snap.id()))fixed=fixed.with("review_status","needs-review");
                store.write(fixed);return fixed;
            }finally{disk.getServer().close();}
        }
    }
    public static void includeObjects(Project<?> project,ImageData<BufferedImage> data,String id,List<PathObject> objects)throws Exception{
        var store=store(project);var entry=entry(project,data);String projectId=store.projectId();
        try(var ignored=store.lock()){
            var record=store.read(id);record.checkWritable();
            requireContext(record,projectId,entry.getID());
            if(record.get("adapter_id").equals("cytoformer"))throw new IllegalArgumentException("Classification references cannot adopt detections; classify the intended cells explicitly");
            if(!record.get("image_entry_id").equals(entry.getID())||!record.get("persistence_status").equals("saved"))throw new IllegalArgumentException("Choose a saved result in this image");
            for(var object:objects)if(!object.isDetection()||object.hasMetadata()&&object.getMetadata().containsKey(WorkshopRunRecord.RESULT_TAG))throw new IllegalArgumentException("Choose unowned detection objects");
            store.write(record.with("persistence_status","saving"));
            var snap=onHost(()->{
                var present=new HashSet<>(data.getHierarchy().getFlattenedObjectList(null));
                for(var object:objects)if(!present.contains(object))throw new IllegalStateException("Selected object is no longer in this image");
                try{
                    for(var object:objects){object.getMetadata().put(WorkshopRunRecord.RESULT_TAG,record.resultId());object.getMetadata().put(WorkshopRunRecord.ROLE_TAG,"object");object.getMetadata().put("atolye.workflow.origin","manual");}
                    var current=snapshot(data,record);entry.saveImageData(data);return current;
                }catch(Exception ex){for(var object:objects){object.getMetadata().remove(WorkshopRunRecord.RESULT_TAG);object.getMetadata().remove(WorkshopRunRecord.ROLE_TAG);object.getMetadata().remove("atolye.workflow.origin");}throw ex;}
            });
            store.write(record.with("persistence_status","saved").with("saved_snapshot_id",snap.id()).withJson("snapshot",snap.json()).with("review_status","needs-review"));
        }
    }
    public static Path export(Project<?> project,ImageData<BufferedImage> data,List<String> ids,Path destination)throws Exception{
        WorkshopRunStore store=store(project);List<WorkshopRunRecord> rows=new ArrayList<>();String projectId=store.projectId();
        try(var ignored=store.lock()){
            var image=entry(project,data);
            for(String id:ids){var r=store.read(id);
                requireContext(r,projectId,image.getID());
                if(r.get("execution_status").equals("succeeded")){
                    if(!onHost(()->snapshot(data,r)).id().equals(r.get("measurement_snapshot_id")))throw new IllegalStateException("Objects or calibration changed; refresh measurements");
                    var durable=image.readImageData();
                    try{if(!snapshot(durable,r).id().equals(r.get("measurement_snapshot_id")))throw new IllegalStateException("Saved objects differ from the measured snapshot; reconcile or refresh before export");}
                    finally{durable.close();}
                }
                rows.add(r);
            }
            return WorkshopEvidenceExporter.export(destination,rows);
        }
    }
    static String imageDataStamp(ProjectImageEntry<?> entry)throws IOException{
        Path file=entry.getEntryPath().resolve("data.qpdata");return Files.isRegularFile(file)?WorkshopRunStore.sha256(file):"missing";
    }
}
