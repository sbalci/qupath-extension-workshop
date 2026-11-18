package io.github.sbalci.qupath.workshop;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Validated data-only recipe. There is no command/code/path field to execute on import. */
public final class WorkshopRecipe {
    private final JsonObject json;
    private static final Set<String> KEYS=Set.of("schema_version","adapter","model","revision","weight_sha256","runner_sha256","device","target_mpp","parameters","measurement_version","class_mapping_version");
    public WorkshopRecipe(JsonObject json){
        this.json=json.deepCopy();
        for(String key:json.keySet())if(!KEYS.contains(key))throw new IllegalArgumentException("Unknown recipe field: "+key);
        if(integer("schema_version")!=1||integer("measurement_version")!=1||integer("class_mapping_version")!=1)throw new IllegalArgumentException("Unsupported recipe version");
        String adapter=get("adapter");
        if(!WorkshopModelDescriptor.adapters().contains(adapter))throw new IllegalArgumentException("Unsupported adapter");
        if(!Set.of("cpu","cuda").contains(get("device")))throw new IllegalArgumentException("Choose CPU or CUDA explicitly");
        String model=get("model");
        if(adapter.equals("histoplus")&&!Set.of("40x","20x").contains(model)||adapter.equals("nuclick")&&!model.equals("original"))throw new IllegalArgumentException("Unsupported model");
        double expected=adapter.equals("histoplus")&&model.equals("20x")?0.5:0.25;
        if(!json.has("target_mpp")||json.get("target_mpp").getAsDouble()!=expected)throw new IllegalArgumentException("Incorrect model resolution");
        for(String key:List.of("weight_sha256","runner_sha256"))if(!get(key).matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Verified model and runner digests are required");
        if(get("revision").isBlank())throw new IllegalArgumentException("Model revision is required");
        if(adapter.equals("histoplus")&&!get("revision").equals(WorkshopModelDescriptor.HISTOPLUS_REVISION))throw new IllegalArgumentException("Unsupported HistoPLUS revision");
        if(adapter.equals("nuclick")&&!get("revision").equals("tiatoolbox-original-pannuke"))throw new IllegalArgumentException("Unsupported NuClick model identity");
        if(!json.has("parameters")||!json.get("parameters").isJsonObject())throw new IllegalArgumentException("Recipe parameters must be an object");
        Set<String> allowed=adapter.equals("histoplus")?Set.of("batch_size","pp_threads"):Set.of("batch_size","threshold","min_size","min_hole_size");
        for(var entry:json.getAsJsonObject("parameters").entrySet()){
            if(!allowed.contains(entry.getKey())||!entry.getValue().isJsonPrimitive()||!entry.getValue().getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Unsupported parameter");
            double value=entry.getValue().getAsDouble();
            if(!Double.isFinite(value)||value<0||value>65536)throw new IllegalArgumentException("Invalid parameter range");
            if(entry.getKey().equals("threshold")){if(value<=0||value>=1)throw new IllegalArgumentException("Threshold must be between 0 and 1");}
            else if(value!=Math.rint(value)||((entry.getKey().equals("batch_size")||entry.getKey().equals("pp_threads"))&&value<1))throw new IllegalArgumentException("Positive integer parameter required");
        }
    }
    private int integer(String key){if(!json.has(key)||!json.get(key).isJsonPrimitive()||!json.get(key).getAsJsonPrimitive().isNumber()||json.get(key).getAsDouble()!=1d)throw new IllegalArgumentException("Unsupported recipe version");return 1;}
    public String get(String key){return json.has(key)?json.get(key).getAsString():"";}
    public JsonObject json(){return json.deepCopy();}
    public String signature(){return WorkshopRunStore.sha256(canonical(json).toString());}
    static JsonElement canonical(JsonElement value){
        if(value.isJsonObject()){JsonObject out=new JsonObject();new TreeSet<>(value.getAsJsonObject().keySet()).forEach(k->out.add(k,canonical(value.getAsJsonObject().get(k))));return out;}
        if(value.isJsonArray()){JsonArray a=new JsonArray();value.getAsJsonArray().forEach(v->a.add(canonical(v)));return a;}return value.deepCopy();
    }
    public void save(Path path)throws IOException{
        if(Files.exists(path))throw new FileAlreadyExistsException("Choose a new recipe filename");
        Files.writeString(path,WorkshopRunStore.JSON.toJson(json),java.nio.charset.StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    public static WorkshopRecipe load(Path path)throws IOException{
        if(Files.size(path)>65536)throw new IOException("Recipe is too large");return new WorkshopRecipe(WorkshopRunStore.readJson(path));
    }
    public static WorkshopRecipe prepared(WorkshopModelDescriptor model,WorkshopModelReadiness.Report readiness,String device){
        if(!readiness.ready())throw new IllegalStateException("Verify local model first");
        JsonObject j=new JsonObject();j.addProperty("schema_version",1);j.addProperty("adapter",model.adapter());j.addProperty("model",model.model());
        j.addProperty("revision",model.revision());j.addProperty("weight_sha256",readiness.evidence().get("weight_sha256").getAsString());
        j.addProperty("runner_sha256",readiness.evidence().get("runner_sha256").getAsString());j.addProperty("device",device);
        j.addProperty("target_mpp",model.targetMpp());j.addProperty("measurement_version",1);j.addProperty("class_mapping_version",1);
        JsonObject p=new JsonObject();p.addProperty("batch_size",model.adapter().equals("histoplus")?4:16);
        if(model.adapter().equals("histoplus"))p.addProperty("pp_threads",2);else{p.addProperty("threshold",0.33);p.addProperty("min_size",10);p.addProperty("min_hole_size",30);}
        j.add("parameters",p);return new WorkshopRecipe(j);
    }
}
