package io.github.sbalci.qupath.workshop;

import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;

/** Model identity is independent of the local machine's runtime/weight locations. */
public record WorkshopModelDescriptor(String adapter, String model, String revision, String weightFile,
        String expectedSha256, double targetMpp, String licence, Path python, Path runner, Path weights, boolean licenceAccepted) {
    public static final String HISTOPLUS_REVISION="cde2eee81af9e39b03802fc33d4f284733b5ee5e";
    public static Set<String> adapters(){return Set.of("histoplus","nuclick");}
    public static List<String> histoplusClasses(){return List.of("Cancer cell","Lymphocytes","Fibroblasts","Plasmocytes","Eosinophils","Neutrophils","Macrophages","Muscle Cell","Endothelial Cell","Red blood cell","Epithelial","Apoptotic Body","Mitotic Figures","Minor Stromal Cell");}
    public static Path dataRoot(){
        String root=Preferences.userRoot().node("/qupath/atolye/common").get("dataRoot","").trim();
        return root.isEmpty()?Path.of(System.getProperty("user.home"),".atolye"):Path.of(root);
    }
    public static WorkshopModelDescriptor configured(String adapter,String model,boolean prepareBundledBridge){
        if(!adapters().contains(adapter))throw new IllegalArgumentException("Unsupported local adapter");
        if(adapter.equals("histoplus")&&!Set.of("40x","20x").contains(model))throw new IllegalArgumentException("Choose 40x or 20x");
        if(adapter.equals("nuclick")&&!model.equals("original"))throw new IllegalArgumentException("Only original NuClick is supported");
        var prefs=Preferences.userRoot().node("/qupath/atolye/"+adapter);
        var local=Preferences.userRoot().node("/qupath/atolye/workflow/"+adapter);
        String py=local.get("python",Preferences.userRoot().node("/qupath/atolye/common").get("py."+adapter,""));
        if(py.isBlank())py=prefs.get("python","");
        if(py.isBlank()){
            Path env=dataRoot().resolve("runtimes").resolve(adapter).resolve(".venv");
            py=(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?env.resolve("Scripts/python.exe"):env.resolve("bin/python")).toString();
        }
        String file=adapter.equals("histoplus")?"histoplus_runner.py":"nuclick_bridge.py";
        String runner=local.get("runner",prefs.get(adapter.equals("histoplus")?"runner":"bridge",""));
        if(runner.isBlank()&&prepareBundledBridge){
            Path extracted=WorkshopResources.extractBundledPythonBridge(adapter+"/"+file);
            if(extracted!=null)runner=extracted.toString();
        }
        String weightName=adapter.equals("histoplus")?"histoplus_cellvit_segmentor_"+model+".pt":"nuclick_original-pannuke.pth";
        String weights=local.get("weights."+model,"");
        if(weights.isBlank()){
            if(adapter.equals("histoplus")){
                Path folder=prefs.get("modelSource","download").equals("folder")?Path.of(prefs.get("modelFolder","")):dataRoot().resolve("cache/histoplus/models");
                weights=folder.resolve(weightName).toString();
            }else weights=dataRoot().resolve("cache/tiatoolbox/models").resolve(weightName).toString();
        }
        String sha=adapter.equals("histoplus")?(model.equals("40x")?"9cccb30d3d73535535bcec0e6d7f322a8ee1be1584bde13a558cf0a5d352ea5c":"aa19dc31c109c0a4fcf7bd773559c45480cee792a331ae0a9ffe42eb08267bfc"):local.get("expectedSha256.original","");
        boolean accepted=adapter.equals("histoplus")?prefs.get("licenseAck","").equals("CC-BY-NC-ND-4.0@cde2eee"):local.getBoolean("licenceAccepted",false);
        return new WorkshopModelDescriptor(adapter,model,adapter.equals("histoplus")?HISTOPLUS_REVISION:"tiatoolbox-original-pannuke",
                weightName,sha,adapter.equals("histoplus")&&model.equals("20x")?0.5:0.25,
                adapter.equals("histoplus")?"CC-BY-NC-ND-4.0":"CC-BY-NC-SA-4.0",Path.of(py),runner.isBlank()?null:Path.of(runner),Path.of(weights),accepted);
    }
}
