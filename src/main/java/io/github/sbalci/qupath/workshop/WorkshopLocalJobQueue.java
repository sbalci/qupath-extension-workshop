package io.github.sbalci.qupath.workshop;

import com.google.gson.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable single-worker queue. Loading never executes a job or assumes an interrupted job succeeded. */
public final class WorkshopLocalJobQueue {
    public interface Executor { String execute(JsonObject job,AtomicBoolean cancelled)throws Exception; }
    private final WorkshopRunStore store;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    public WorkshopLocalJobQueue(WorkshopRunStore store){this.store=store;}
    private Path path(String id){return store.root().resolve("job-"+UUID.fromString(id)+".json");}
    public JsonObject enqueue(String imageId,String scopeJson,WorkshopRecipe recipe,WorkshopExecutionPolicy.Action action,String replaceRunId,String expectedSnapshot,boolean confirmed)throws IOException{
        if(action==WorkshopExecutionPolicy.Action.MEASURE_ONLY)throw new IllegalArgumentException("Use refresh measurements directly");
        if(action==WorkshopExecutionPolicy.Action.REGENERATE_SELECTED&&(!confirmed||replaceRunId.isBlank()||expectedSnapshot.isBlank()))throw new IllegalArgumentException("Confirmed exact replacement target required");
        JsonObject j=new JsonObject();j.addProperty("schema_version",1);j.addProperty("job_id",UUID.randomUUID().toString());
        j.addProperty("created_at",java.time.Instant.now().toString());j.addProperty("image_entry_id",imageId);j.addProperty("scope_json",scopeJson);
        j.addProperty("action",action.name());j.addProperty("replace_run_id",replaceRunId);j.addProperty("expected_snapshot",expectedSnapshot);
        j.addProperty("replacement_confirmed",confirmed);j.addProperty("status","queued");j.add("recipe",recipe.json());
        var requested=WorkshopRunRecord.create(store.projectId(),imageId,recipe.get("adapter"))
                .with("scope_json",scopeJson).with("analysis_unit_id",WorkshopRunStore.sha256(scopeJson))
                .withJson("effective_settings",recipe.json());j.addProperty("request_run_id",requested.id());
        try(var ignored=store.lock()){store.write(requested);WorkshopRunStore.writeJson(path(j.get("job_id").getAsString()),j);}return j;
    }
    public List<JsonObject> list()throws IOException{
        List<JsonObject> jobs=new ArrayList<>();try(var files=Files.newDirectoryStream(store.root(),"job-*.json")){for(Path p:files)jobs.add(WorkshopRunStore.readJson(p));}
        jobs.sort(Comparator.comparing(j->j.get("created_at").getAsString()));return jobs;
    }
    public void cancel(){cancelled.set(true);}
    public void start(){cancelled.set(false);}
    public void retry(String id)throws IOException{
        try(var ignored=store.lock()){
            JsonObject j=WorkshopRunStore.readJson(path(id));requireSchema(j);
            if(!Set.of("failed","cancelled","recovery-required").contains(j.get("status").getAsString()))throw new IllegalStateException("Only interrupted or failed jobs can be retried");
            for(var r:store.list())if(r.get("job_id").equals(id)&&Set.of("saving","saved").contains(r.get("persistence_status")))throw new IllegalStateException("A result may already be committed; reconcile the saved image and queue before retrying");
            // Replacement is never silently reconfirmed after a restart.
            if(j.get("action").getAsString().equals("REGENERATE_SELECTED"))throw new IllegalStateException("Create and confirm a new replacement request");
            j.addProperty("status","queued");WorkshopRunStore.writeJson(path(id),j);
        }
    }
    private static void requireSchema(JsonObject j){if(!j.has("schema_version")||j.get("schema_version").getAsInt()!=1)throw new IllegalStateException("Unknown job schema; read only");}
    public void reconcileInterrupted()throws IOException{
        try(var ignored=workerLock();var writing=store.lock()){
            for(JsonObject job:list()){
                if(!job.has("schema_version")||job.get("schema_version").getAsInt()!=1)continue;
                if(Set.of("running","saving","recovery-required","failed","cancelled").contains(job.get("status").getAsString())){
                    var saved=store.list().stream().filter(r->r.get("job_id").equals(job.get("job_id").getAsString())&&r.get("persistence_status").equals("saved")&&r.get("execution_status").equals("succeeded")).toList();
                    if(saved.size()==1){
                        job.addProperty("status","saved");job.addProperty("run_id",saved.getFirst().id());
                        if(job.has("request_run_id"))store.write(store.read(job.get("request_run_id").getAsString()).with("execution_status","delegated").with("completed_run_id",saved.getFirst().id()));
                    }else if(Set.of("running","saving","recovery-required").contains(job.get("status").getAsString()))job.addProperty("status","recovery-required");
                    WorkshopRunStore.writeJson(path(job.get("job_id").getAsString()),job);
                }
            }
        }
    }
    private WorkerLock workerLock()throws IOException{
        Path p=store.root().resolve("queue.worker.lock");if(Files.isSymbolicLink(p))throw new IOException("Invalid queue lock");
        FileChannel channel=FileChannel.open(p,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try{FileLock lock=channel.tryLock();if(lock==null)throw new IOException("A local queue is already running");return new WorkerLock(channel,lock);}
        catch(IOException|OverlappingFileLockException e){channel.close();throw new IOException("A local queue is already running",e);}
    }
    private record WorkerLock(FileChannel channel,FileLock lock) implements AutoCloseable{public void close()throws IOException{try{lock.release();}finally{channel.close();}}}
    public boolean runNext(Executor executor)throws Exception{
        if(cancelled.get())return false;
        try(var worker=workerLock()){
            JsonObject job;
            try(var writing=store.lock()){
                job=list().stream().filter(j->j.get("status").getAsString().equals("queued")).findFirst().orElse(null);
                if(job==null)return false;requireSchema(job);job.addProperty("status","running");WorkshopRunStore.writeJson(path(job.get("job_id").getAsString()),job);
                if(job.has("request_run_id"))store.write(store.read(job.get("request_run_id").getAsString()).with("execution_status","running"));
            }
            try{
                String runId=executor.execute(job.deepCopy(),cancelled);
                WorkshopRunRecord result=store.read(runId);
                if(!result.get("persistence_status").equals("saved")||!result.get("execution_status").equals("succeeded"))throw new IllegalStateException("Executor did not produce a durable result");
                job.addProperty("run_id",runId);job.addProperty("status","saved");
            }catch(Exception ex){
                job.addProperty("status",cancelled.get()||ex instanceof InterruptedException?"cancelled":"failed");
                // Store a category rather than a potentially identifying file path from a traceback.
                job.addProperty("error_category",ex.getClass().getSimpleName());throw ex;
            }finally{
                try(var writing=store.lock()){
                    if(job.has("request_run_id")){
                        var request=store.read(job.get("request_run_id").getAsString());String status=job.get("status").getAsString();
                        request=request.with("execution_status",status.equals("saved")?"delegated":status);
                        if(job.has("run_id"))request=request.with("completed_run_id",job.get("run_id").getAsString());
                        if(job.has("error_category"))request=request.with("failure_reason",job.get("error_category").getAsString());
                        store.write(request);
                    }
                    WorkshopRunStore.writeJson(path(job.get("job_id").getAsString()),job);
                }
            }
            return true;
        }
    }
}
