package io.github.sbalci.qupath.workshop;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** File inspection never imports Python, loads a checkpoint, downloads, or chooses a fallback device. */
public final class WorkshopModelReadiness {
    public record Report(boolean ready,List<String> problems,JsonObject evidence){}
    public static Report inspect(WorkshopModelDescriptor model,boolean verifyWeights)throws IOException{
        List<String> problems=new ArrayList<>();JsonObject j=new JsonObject();
        j.addProperty("adapter",model.adapter());j.addProperty("model",model.model());j.addProperty("revision",model.revision());
        j.addProperty("target_mpp",model.targetMpp());j.addProperty("licence",model.licence());
        var classes=new com.google.gson.JsonArray();
        if(model.adapter().equals("histoplus"))WorkshopModelDescriptor.histoplusClasses().forEach(classes::add);else classes.add("NuClick");
        j.add("classes",classes);
        if(model.python()==null||!Files.isRegularFile(model.python()))problems.add("workflow.problem.python");
        if(model.runner()==null||!Files.isRegularFile(model.runner()))problems.add("workflow.problem.runner");
        else j.addProperty("runner_sha256",WorkshopRunStore.sha256(model.runner()));
        if(!Files.isRegularFile(model.weights()))problems.add("workflow.problem.weights");
        else if(verifyWeights){
            String digest=WorkshopRunStore.sha256(model.weights());j.addProperty("weight_sha256",digest);
            if(model.expectedSha256().isBlank())problems.add("workflow.problem.expectedHash");
            else if(!digest.equalsIgnoreCase(model.expectedSha256()))problems.add("workflow.problem.corrupt");
        }else problems.add("workflow.problem.verify");
        if(!model.licenceAccepted())problems.add("workflow.problem.licence");
        j.addProperty("runtime_tested",false);j.addProperty("device","not tested");
        return new Report(problems.isEmpty(),List.copyOf(problems),j);
    }
}
