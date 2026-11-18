package io.github.sbalci.qupath.workshop;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.locationtech.jts.geom.Geometry;
import qupath.lib.images.ImageData;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.*;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.regions.*;
import qupath.lib.roi.*;
import qupath.lib.roi.interfaces.ROI;

/** Executes only the two prepared built-in adapters. A recipe never supplies executable commands. */
public final class WorkshopLocalRunner {
    @FunctionalInterface interface Backend {List<String> run(WorkshopModelDescriptor model,List<String> arguments,Path work,AtomicBoolean cancelled)throws Exception;}
    private WorkshopLocalRunner(){}
    public static String execute(Project<?> project,ImageData<BufferedImage> data,JsonObject job,AtomicBoolean cancelled)throws Exception{
        var recipe=new WorkshopRecipe(job.getAsJsonObject("recipe"));
        var model=WorkshopModelDescriptor.configured(recipe.get("adapter"),recipe.get("model"),false);
        Path probe=WorkshopResources.extractBundledPythonBridge("workflow/runtime_probe.py");
        if(probe==null)throw new IOException("Missing runtime probe");
        return executePrepared(project,data,job,cancelled,model,probe,WorkshopLocalRunner::process);
    }
    static String executePrepared(Project<?> project,ImageData<BufferedImage> data,JsonObject job,AtomicBoolean cancelled,WorkshopModelDescriptor model,Path probe,Backend backend)throws Exception{
        var recipe=new WorkshopRecipe(job.getAsJsonObject("recipe"));
        var ready=WorkshopModelReadiness.inspect(model,true);
        if(!ready.ready())throw new IllegalStateException("Model is not prepared: "+ready.problems());
        if(!recipe.get("weight_sha256").equals(ready.evidence().get("weight_sha256").getAsString())||!recipe.get("runner_sha256").equals(ready.evidence().get("runner_sha256").getAsString()))throw new IllegalStateException("Prepared model differs from recipe");
        var entry=WorkshopLocalWorkflow.entry(project,data);
        String entryDataStamp=WorkshopLocalWorkflow.imageDataStamp(entry);
        if(!entry.getID().equals(job.get("image_entry_id").getAsString()))throw new IllegalArgumentException("Open the image named in the queued request");
        ROI roi=WorkshopObjectSnapshot.deserializeRoi(job.get("scope_json").getAsString());
        if(roi.getZ()!=0||roi.getT()!=0||roi.getC()!=-1||!roi.isArea()||roi.getBoundsX()<0||roi.getBoundsY()<0||roi.getBoundsX()+roi.getBoundsWidth()>data.getServer().getWidth()||roi.getBoundsY()+roi.getBoundsHeight()>data.getServer().getHeight())throw new IllegalArgumentException("Choose an in-image area on z=0, t=0");
        var uris=data.getServer().getURIs();if(uris.size()!=1||!uris.iterator().next().getScheme().equals("file"))throw new IllegalArgumentException("One local source file is required");
        Path source=Path.of(uris.iterator().next());String sourceHash=WorkshopRunStore.sha256(source);
        JsonObject signature=new JsonObject();signature.addProperty("source_sha256",sourceHash);signature.addProperty("reader",data.getServer().getClass().getName());
        signature.addProperty("width",data.getServer().getWidth());signature.addProperty("height",data.getServer().getHeight());signature.add("scope",WorkshopObjectSnapshot.roiJson(roi));
        var cal=data.getServer().getPixelCalibration();
        if(!Double.isFinite(cal.getPixelWidthMicrons())||!Double.isFinite(cal.getPixelHeightMicrons())||cal.getPixelWidthMicrons()<=0||cal.getPixelHeightMicrons()<=0)throw new IllegalArgumentException("Positive physical pixel calibration is required");
        signature.addProperty("pixel_width_um",cal.getPixelWidthMicrons());signature.addProperty("pixel_height_um",cal.getPixelHeightMicrons());signature.add("recipe",recipe.json());
        if(model.adapter().equals("nuclick"))signature.add("points",pointSignature(data,roi));
        String inputSignature=WorkshopRunStore.sha256(WorkshopRecipe.canonical(signature).toString());
        var store=WorkshopLocalWorkflow.store(project);
        var probeModel=new WorkshopModelDescriptor(model.adapter(),model.model(),model.revision(),model.weightFile(),model.expectedSha256(),model.targetMpp(),model.licence(),model.python(),probe,model.weights(),model.licenceAccepted());
        Path probeWork=Files.createTempDirectory(store.root(),"runtime-");
        List<String> observed=backend.run(probeModel,List.of("--device",recipe.get("device")),probeWork,cancelled);
        JsonObject observedRuntime=JsonParser.parseString(observed.getLast()).getAsJsonObject();
        signature.add("runtime",observedRuntime);
        inputSignature=WorkshopRunStore.sha256(WorkshopRecipe.canonical(signature).toString());
        String action=job.get("action").getAsString();
        if(action.equals("COMPLETE_MISSING"))for(var r:store.list())if(r.get("image_entry_id").equals(entry.getID())&&WorkshopExecutionPolicy.reusable(r,inputSignature)){
            var current=WorkshopLocalWorkflow.onHost(()->WorkshopLocalWorkflow.snapshot(data,r));
            if(!WorkshopObjectSnapshot.roiJson(current.scope()).equals(WorkshopObjectSnapshot.roiJson(roi)))throw new IllegalStateException("Recorded analysis boundary changed; regenerate explicitly instead of completing missing work");
            boolean unchanged=current.id().equals(r.get("saved_snapshot_id"));
            if(unchanged)return r.id();
            throw new IllegalStateException("Existing compatible result was edited; refresh and save it before completing missing work");
        }
        String replace=action.equals("REGENERATE_SELECTED")?job.get("replace_run_id").getAsString():"";
        if(!replace.isBlank())WorkshopExecutionPolicy.requireReplacement(store.read(replace),job.get("replacement_confirmed").getAsBoolean());
        Path workRoot=store.root().resolve("local-work");if(Files.isSymbolicLink(workRoot))throw new IOException("Invalid work path");Files.createDirectories(workRoot);
        Path work=Files.createTempDirectory(workRoot,"run-");
        List<PathObject> objects;JsonObject outcomes=null;JsonObject runtime=observedRuntime.deepCopy();
        if(model.adapter().equals("histoplus")){
            if(data.getImageType()!=ImageData.ImageType.BRIGHTFIELD_H_E)throw new IllegalArgumentException("Set and verify the H&E image type before queued HistoPLUS inference");
            Map<String,String> pc=parsePrecheck(backend.run(model,List.of("precheck","--slide",source.toString()),work,cancelled));
            if(!"1".equals(pc.get("ok"))||!recipe.get("model").equals(pc.get("model")))throw new IllegalArgumentException("Source is incompatible with the selected model");
            if(pc.containsKey("mpp_x")&&pc.containsKey("mpp_y")){
                double x=Double.parseDouble(pc.get("mpp_x")),y=Double.parseDouble(pc.get("mpp_y"));
                if(!(x>0&&y>0)||Math.abs(x-cal.getPixelWidthMicrons())/x>0.05||Math.abs(y-cal.getPixelHeightMicrons())/y>0.05)throw new IllegalArgumentException("QuPath and source-file calibration disagree; resolve the scale before queued inference");
            }
            boolean bounds=!(pc.getOrDefault("bounds_x","0").equals("0")&&pc.getOrDefault("bounds_y","0").equals("0"));
            if(bounds&&(!data.getServer().getServerType().toLowerCase(Locale.ROOT).contains("openslide")||Long.parseLong(pc.get("width"))==data.getServer().getWidth()&&Long.parseLong(pc.get("height"))==data.getServer().getHeight()))throw new IllegalArgumentException("OpenSlide bounds do not match the QuPath coordinate frame");
            Path roiFile=work.resolve("roi.geojson");JsonObject fc=new JsonObject();fc.addProperty("type","FeatureCollection");JsonArray features=new JsonArray();JsonObject feature=new JsonObject();feature.addProperty("type","Feature");feature.add("geometry",JsonParser.parseString(GsonTools.getInstance().toJson(roi.getGeometry(),Geometry.class)));feature.add("properties",new JsonObject());features.add(feature);fc.add("features",features);Files.writeString(roiFile,fc.toString());
            var p=recipe.json().getAsJsonObject("parameters");
            backend.run(model,List.of("detect","--slide",source.toString(),"--roi",roiFile.toString(),"--model-dir",model.weights().getParent().toString(),"--out",work.toString(),"--device",recipe.get("device"),"--batch-size",parameter(p,"batch_size","4"),"--pp-threads",parameter(p,"pp_threads","2")),work,cancelled);
            objects=parseHistoPlus(work.resolve("histoplus_cells.geojson"),roi);
            JsonObject result=WorkshopRunStore.readJson(work.resolve("histoplus_run.json"));runtime.addProperty("actual_device",result.get("device").getAsString());
            runtime.addProperty("precision","upstream mixed_precision=True");
            if(!result.get("model").getAsString().equals(model.model()))throw new IllegalStateException("Runner selected a different model");
        }else{
            var result=runNuClick(model,recipe,data,roi,work,cancelled,backend);objects=result.objects();outcomes=result.outcomes();
            for(var field:result.runtime().entrySet())runtime.add(field.getKey(),field.getValue());
        }
        if(cancelled.get())throw new InterruptedException("Cancelled before import");
        if(!sourceHash.equals(WorkshopRunStore.sha256(source)))throw new IllegalStateException("Source changed during inference");
        if(!recipe.get("weight_sha256").equals(WorkshopRunStore.sha256(model.weights()))||!recipe.get("runner_sha256").equals(WorkshopRunStore.sha256(model.runner())))throw new IllegalStateException("Model or runner changed during inference");
        // Ensure point edits made during NuClick inference do not silently change the requested task.
        if(model.adapter().equals("nuclick")&&!signature.get("points").equals(pointSignature(data,roi)))throw new IllegalStateException("Point prompts changed during inference");
        runtime.addProperty("qupath_version",qupath.lib.common.GeneralTools.getVersion());runtime.addProperty("extension_version",WorkshopResources.getExtensionVersion());
        runtime.addProperty("runner_sha256",ready.evidence().get("runner_sha256").getAsString());runtime.addProperty("requested_device",recipe.get("device"));runtime.addProperty("python_network_guard",true);
        JsonObject modelId=ready.evidence().deepCopy();modelId.remove("runtime_tested");modelId.remove("device");
        JsonArray classes=new JsonArray();JsonObject classMap=new JsonObject();
        if(model.adapter().equals("histoplus"))for(String key:WorkshopModelDescriptor.histoplusClasses()){classes.add(key);classMap.addProperty(key+" (HistoPLUS)",key);}
        else classes.add("NuClick");
        modelId.add("classes",classes);modelId.add("class_mapping",classMap);
        JsonObject facts=new JsonObject();facts.addProperty("source_sha256",sourceHash);facts.addProperty("entry_data_sha256",entryDataStamp);facts.addProperty("job_id",job.get("job_id").getAsString());facts.add("model_identity",modelId);facts.add("runtime_identity",runtime);facts.add("effective_settings",recipe.json());if(outcomes!=null)facts.add("prompt_outcomes",outcomes);
        PathObject summary=PathObjects.createAnnotationObject(roi);summary.setName("Atolye "+model.adapter()+" "+UUID.randomUUID().toString().substring(0,8));summary.setLocked(true);
        return WorkshopLocalWorkflow.commitImported(project,data,model.adapter(),roi,objects,summary,facts,inputSignature,replace,job.get("expected_snapshot").getAsString(),cancelled);
    }
    static String parameter(JsonObject p,String key,String fallback){return p.has(key)?p.get(key).getAsString():fallback;}
    public static List<PathObject> parseHistoPlus(Path file,ROI scope)throws IOException{
        JsonObject root=WorkshopRunStore.readJson(file);if(!root.has("features")||!root.get("features").isJsonArray())throw new IOException("Missing features array");
        List<PathObject> out=new ArrayList<>();
        for(JsonElement e:root.getAsJsonArray("features")){
            JsonObject f=e.getAsJsonObject();Geometry g=GsonTools.getInstance().fromJson(f.get("geometry"),Geometry.class);
            if(g==null||!g.isValid()||g.isEmpty()||!Double.isFinite(g.getArea())||g.getArea()<=0)throw new IOException("Invalid nucleus geometry");
            ROI roi=GeometryTools.geometryToROI(g,scope.getImagePlane());if(!scope.contains(roi.getCentroidX(),roi.getCentroidY()))continue;
            String label=f.getAsJsonObject("properties").getAsJsonObject("classification").get("name").getAsString();
            if(label.isBlank())throw new IOException("Missing nucleus class");
            label=canonicalHistoPlus(label);
            var object=PathObjects.createDetectionObject(roi,PathClass.fromString(label+" (HistoPLUS)"));
            out.add(object);
        }
        return out;
    }
    static String canonicalHistoPlus(String label){
        String normalized=label.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
        if(Set.of("tumor","tumour","neoplastic","cancer cells").contains(normalized))return "Cancer cell";
        for(String key:WorkshopModelDescriptor.histoplusClasses()){
            String lower=key.toLowerCase(Locale.ROOT);
            if(lower.equals(normalized)||lower.endsWith("s")&&lower.substring(0,lower.length()-1).equals(normalized))return key;
        }
        return label.trim();
    }
    static Map<String,String> parsePrecheck(List<String> lines){Map<String,String> out=new HashMap<>();for(String line:lines)if(line.startsWith("PRECHECK "))for(String word:line.substring(9).split("\\s+")){int i=word.indexOf('=');if(i>0)out.put(word.substring(0,i),word.substring(i+1));}return out;}
    static List<String> process(WorkshopModelDescriptor model,List<String> args,Path work,AtomicBoolean cancelled)throws Exception{
        if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("Cancelled before process start");
        Path launcher=WorkshopResources.extractBundledPythonBridge("workflow/local_only.py");if(launcher==null)throw new IOException("Missing local inference launcher");
        List<String> command=new ArrayList<>(List.of(model.python().toString(),"-I",launcher.toString(),model.runner().toString()));command.addAll(args);
        ProcessBuilder pb=new ProcessBuilder(command).redirectErrorStream(true);pb.environment().put("PYTHONIOENCODING","utf-8");
        pb.environment().put("HF_HOME",WorkshopModelDescriptor.dataRoot().resolve("cache/huggingface").toString());pb.environment().put("TORCH_HOME",WorkshopModelDescriptor.dataRoot().resolve("cache/torch").toString());
        pb.environment().put("ATOLYE_LOCAL_WEIGHTS",model.weights().toString());pb.environment().put("ATOLYE_STRICT_DEVICE","1");
        Path log=Files.createTempFile(work,"process-",".log");pb.redirectOutput(log.toFile());Process process=pb.start();
        try{
            while(!process.waitFor(200,TimeUnit.MILLISECONDS)){
                if(cancelled.get()||Thread.currentThread().isInterrupted()){kill(process);throw new InterruptedException("Cancelled local process");}
                if(Files.size(log)>8*1024*1024){kill(process);throw new IOException("Local process log exceeded the configured limit");}
            }
            if(cancelled.get())throw new InterruptedException("Cancelled local process");
            List<String> lines=Files.readAllLines(log,StandardCharsets.UTF_8);
            if(process.exitValue()!=0)throw new IOException("Local bridge failed (exit "+process.exitValue()+"); inspect the local job log");return lines;
        }finally{if(process.isAlive())kill(process);}
    }
    static void kill(Process process){var descendants=process.descendants().toList();descendants.forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}
    static JsonArray pointSignature(ImageData<?> data,ROI scope)throws Exception{
        return WorkshopLocalWorkflow.onHost(()->{JsonArray points=new JsonArray();
            data.getHierarchy().getAnnotationObjects().stream().filter(o->o.getROI()!=null&&o.getROI().isPoint()&&o.getROI().getImagePlane().equals(scope.getImagePlane()))
                .sorted(Comparator.comparing(o->o.getID().toString())).forEach(o->{int i=0;for(var p:o.getROI().getAllPoints()){
                    if(scope.contains(p.getX(),p.getY())){JsonObject j=new JsonObject();j.addProperty("id",o.getID()+"-"+i);j.addProperty("x",p.getX());j.addProperty("y",p.getY());points.add(j);}i++;}});return points;});
    }
    private record NuClickResult(List<PathObject> objects,JsonObject outcomes,JsonObject runtime){}
    private static NuClickResult runNuClick(WorkshopModelDescriptor model,WorkshopRecipe recipe,ImageData<BufferedImage> data,ROI scope,Path work,AtomicBoolean cancelled,Backend backend)throws Exception{
        var cal=data.getServer().getPixelCalibration();double mpp=cal.getPixelWidthMicrons();
        if(Math.abs(mpp-cal.getPixelHeightMicrons())/mpp>0.01)throw new IllegalArgumentException("NuClick requires nearly square source pixels");
        double ds=0.25/mpp;int side=(int)Math.round(128*ds);int width=data.getServer().getWidth(),height=data.getServer().getHeight();
        if(side<1||side>width||side>height)throw new IllegalArgumentException("Image is too small for a calibrated NuClick patch");
        JsonArray points=pointSignature(data,scope);if(points.isEmpty()||points.size()>2000)throw new IllegalArgumentException("Choose 1–2000 point prompts inside the ROI");
        Path patches=Files.createDirectory(work.resolve("patches"));JsonArray items=new JsonArray();Map<String,double[]> origins=new HashMap<>();
        for(JsonElement e:points){
            if(cancelled.get())throw new InterruptedException();JsonObject p=e.getAsJsonObject();String id=p.get("id").getAsString();double x=p.get("x").getAsDouble(),y=p.get("y").getAsDouble();
            int ox=Math.max(0,Math.min(width-side,(int)Math.round(x-side/2.0))),oy=Math.max(0,Math.min(height-side,(int)Math.round(y-side/2.0)));
            BufferedImage source=data.getServer().readRegion(RegionRequest.createInstance(data.getServer().getPath(),ds,ox,oy,side,side));
            BufferedImage tile=new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB);var graphics=tile.createGraphics();graphics.drawImage(source,0,0,128,128,null);graphics.dispose();ImageIO.write(tile,"PNG",patches.resolve(id+".png").toFile());
            // Actual exported patch extent defines the local-to-slide transform, including rounding.
            double scale=side/128.0;origins.put(id,new double[]{ox,oy,scale});
            JsonObject item=new JsonObject();item.addProperty("id",id);item.addProperty("patch_file",id+".png");JsonArray click=new JsonArray();click.add((x-ox)/scale);click.add((y-oy)/scale);item.add("click",click);JsonArray others=new JsonArray();
            for(JsonElement q:points)if(!q.equals(e)){var other=q.getAsJsonObject();double qx=other.get("x").getAsDouble(),qy=other.get("y").getAsDouble();if(qx>=ox&&qx<ox+side&&qy>=oy&&qy<oy+side){JsonArray v=new JsonArray();v.add((qx-ox)/scale);v.add((qy-oy)/scale);others.add(v);}}
            item.add("others",others);items.add(item);
        }
        JsonObject input=new JsonObject();input.addProperty("tile_size",128);input.add("items",items);Path inputFile=work.resolve("points.json");Files.writeString(inputFile,input.toString());
        var p=recipe.json().getAsJsonObject("parameters");backend.run(model,List.of("predict","--points",inputFile.toString(),"--patches-dir",patches.toString(),"--output-dir",work.toString(),"--device",recipe.get("device"),"--batch-size",parameter(p,"batch_size","16"),"--thresh",parameter(p,"threshold","0.33"),"--min-size",parameter(p,"min_size","10"),"--min-hole-size",parameter(p,"min_hole_size","30")),work,cancelled);
        var predictions=WorkshopRunStore.readJson(work.resolve("predictions.json"));List<PathObject> objects=new ArrayList<>();Set<String> seen=new HashSet<>();JsonArray outcomes=new JsonArray();
        for(JsonElement e:predictions.getAsJsonArray("items")){
            JsonObject result=e.getAsJsonObject();String id=result.get("id").getAsString();if(!origins.containsKey(id)||!seen.add(id))throw new IOException("Unexpected or duplicate point result");
            JsonObject outcome=new JsonObject();outcome.addProperty("id",id);boolean ok=result.get("ok").getAsBoolean();outcome.addProperty("ok",ok);outcomes.add(outcome);if(!ok)continue;
            var contour=result.getAsJsonArray("contour");if(contour.size()<3)throw new IOException("Invalid point contour");double[] xs=new double[contour.size()],ys=new double[contour.size()];var origin=origins.get(id);
            for(int i=0;i<xs.length;i++){xs[i]=origin[0]+contour.get(i).getAsJsonArray().get(0).getAsDouble()*origin[2];ys[i]=origin[1]+contour.get(i).getAsJsonArray().get(1).getAsDouble()*origin[2];}
            var roi=ROIs.createPolygonROI(xs,ys,scope.getImagePlane());if(!roi.getGeometry().isValid()||roi.getArea()<=0)throw new IOException("Invalid NuClick result geometry");
            var object=PathObjects.createDetectionObject(roi,PathClass.fromString("NuClick"));object.getMetadata().put("atolye.workflow.prompt",id);objects.add(object);
        }
        if(seen.size()!=points.size())throw new IOException("Missing point outcomes");
        JsonObject report=new JsonObject();report.add("items",outcomes);JsonObject runtime=new JsonObject();
        runtime.addProperty("actual_device",predictions.has("device")?predictions.get("device").getAsString():"unknown");runtime.addProperty("precision","float32");
        return new NuClickResult(objects,report,runtime);
    }
}
