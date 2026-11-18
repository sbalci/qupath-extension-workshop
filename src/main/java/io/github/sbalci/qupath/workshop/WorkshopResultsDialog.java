package io.github.sbalci.qupath.workshop;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

/** Shared local results, model preparation, recipes and durable ROI jobs. */
public final class WorkshopResultsDialog {
    private static Stage current;
    private static WorkshopResultsDialog active;
    static { WorkshopI18n.addListener(()->{
        var dialog=active;if(dialog==null)return;
        if(Platform.isFxApplicationThread())dialog.relabel();else Platform.runLater(dialog::relabel);
    });}
    private final Map<Labeled,String> labels=new IdentityHashMap<>();
    private final Map<Tab,String> tabLabels=new IdentityHashMap<>();
    private final QuPathGUI qupath;
    private final Project<BufferedImage> project;
    private final WorkshopRunStore store;
    private final WorkshopLocalJobQueue queue;
    private final Stage stage=new Stage();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Atolye-LocalWorkflow");t.setDaemon(true);return t;});
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ListView<WorkshopRunRecord> results=new ListView<>();
    private final ListView<JsonObject> jobs=new ListView<>();
    private final TextArea details=new TextArea();
    private final Label status=new Label();
    private final ComboBox<String> adapter=new ComboBox<>(),model=new ComboBox<>(),device=new ComboBox<>();
    private WorkshopRecipe recipe;
    private HBox resultActions,modelActions,queueActions;
    private interface Work {void run()throws Exception;}
    private WorkshopResultsDialog(QuPathGUI qupath)throws Exception{
        this.qupath=qupath;project=qupath.getProject();store=WorkshopLocalWorkflow.store(project);queue=new WorkshopLocalJobQueue(store);
        stage.initOwner(qupath.getStage());stage.setTitle(t("workflow.title"));
        details.setEditable(false);details.setWrapText(true);details.setPrefRowCount(7);
        results.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        results.setCellFactory(v->new ListCell<>(){protected void updateItem(WorkshopRunRecord r,boolean empty){super.updateItem(r,empty);setText(empty||r==null?null:r.get("image_entry_id")+" · "+r.get("adapter_id")+" · "+shortId(r.resultId())+" · "+(!r.writable()?t("workflow.readOnly"):state(r.get("execution_status"))+" / "+state(r.get("persistence_status"))+" / "+state(r.get("review_status"))+(r.get("live_measurement_status").isBlank()?"":" · "+state(r.get("live_measurement_status")))));}});
        results.getSelectionModel().selectedItemProperty().addListener((o,old,r)->{if(r!=null)details.setText(describe(r));});
        jobs.setCellFactory(v->new ListCell<>(){protected void updateItem(JsonObject j,boolean empty){super.updateItem(j,empty);setText(empty||j==null?null:j.get("job_id").getAsString().substring(0,8)+" · "+state(j.get("status").getAsString())+" · "+j.get("image_entry_id").getAsString());}});
        TabPane tabs=new TabPane();tabs.getTabs().addAll(tab("workflow.results",resultPane()),tab("workflow.models",modelPane()),tab("workflow.queue",queuePane()));
        Button stop=button("workflow.stop",()->{queue.cancel();status.setText(t("workflow.stopping"));});
        VBox root=new VBox(10,label("workflow.intro"),tabs,details,new HBox(10,status,stop));root.setPadding(new Insets(14));VBox.setVgrow(tabs,Priority.ALWAYS);
        stage.setScene(new Scene(root,1000,760));stage.setMinWidth(680);stage.setMinHeight(560);
        stage.setOnCloseRequest(e->{if(busy.get()){queue.cancel();e.consume();status.setText(t("workflow.stopping"));}else worker.shutdown();});
        stage.setOnHidden(e->{if(active==this)active=null;});
    }
    public static void show(QuPathGUI qupath){
        if(current!=null&&current.isShowing()){current.toFront();return;}
        try{var dialog=new WorkshopResultsDialog(qupath);active=dialog;current=dialog.stage;current.show();dialog.work(dialog::reload);}
        catch(Exception e){Dialogs.showErrorMessage(t("workflow.title"),t("workflow.projectRequired")+"\n"+e.getMessage());}
    }
    private static String t(String key){return WorkshopI18n.t(key);}
    private static String shortId(String id){return id.substring(0,Math.min(8,id.length()));}
    private static String describe(WorkshopRunRecord record){
        StringBuilder text=new StringBuilder(t("workflow.detail.result")+": "+record.get("adapter_id")+" · "+record.resultId()+"\n");
        text.append(t("workflow.detail.image")).append(": ").append(record.get("image_entry_id")).append('\n');
        text.append(state(record.get("execution_status"))).append(" / ").append(state(record.get("persistence_status"))).append(" / ").append(state(record.get("review_status"))).append('\n');
        if(!record.get("live_measurement_status").isBlank())text.append(state(record.get("live_measurement_status"))).append('\n');
        if(record.json().has("measurements")){
            var m=record.json().getAsJsonObject("measurements");
            text.append(t("workflow.detail.count")).append(": ").append(m.get("total_count").getAsString()).append('\n');
            text.append(t("workflow.detail.area")).append(": ").append(WorkshopEvidenceExporter.value(m,"scope_area_mm2")).append('\n');
            for(var e:m.getAsJsonArray("classes")){var c=e.getAsJsonObject();text.append(c.get("class").getAsString()).append(": ").append(c.get("count").getAsString()).append('\n');}
        }
        text.append('\n').append(t("workflow.detail.evidence"));return text.toString();
    }
    private static String state(String s){String key="workflow.state."+s;String text=t(key);return key.equals(text)?s:text;}
    private Tab tab(String key,javafx.scene.Node content){Tab tab=new Tab(t(key),content);tab.setClosable(false);tabLabels.put(tab,key);return tab;}
    private Label label(String key){Label label=new Label(t(key));label.setWrapText(true);label.setMaxWidth(Double.MAX_VALUE);labels.put(label,key);return label;}
    private Button button(String key,Runnable action){Button b=new Button(t(key));labels.put(b,key);b.setOnAction(e->{try{action.run();}catch(Exception ex){Dialogs.showErrorMessage(t("workflow.title"),ex.getMessage());}});return b;}
    private void relabel(){
        stage.setTitle(t("workflow.title"));labels.forEach((label,key)->label.setText(t(key)));tabLabels.forEach((tab,key)->tab.setText(t(key)));results.refresh();jobs.refresh();
        for(String key:List.of("workflow.working","workflow.done","workflow.failed","workflow.stopping","workflow.selectResult","workflow.verifyFirst","workflow.selectRoi","workflow.selectObjects"))
            if(status.getText().equals(WorkshopI18n.t(key,true))||status.getText().equals(WorkshopI18n.t(key,false))){status.setText(t(key));break;}
    }
    private void context(){if(qupath.getProject()!=project)throw new IllegalStateException(t("workflow.projectChanged"));}
    private ImageData<BufferedImage> image(){context();var data=qupath.getImageData();if(data==null)throw new IllegalStateException(t("workflow.imageRequired"));return data;}
    private List<String> selected(){return results.getSelectionModel().getSelectedItems().stream().map(WorkshopRunRecord::id).toList();}
    private void work(Work action){
        if(!busy.compareAndSet(false,true))return;setBusy(true);status.setText(t("workflow.working"));
        worker.submit(()->{try{action.run();Platform.runLater(()->status.setText(t("workflow.done")));}
            catch(Exception e){Platform.runLater(()->{status.setText(t("workflow.failed"));details.setText(e.getClass().getSimpleName()+": "+e.getMessage());});}
            finally{busy.set(false);Platform.runLater(()->setBusy(false));}});
    }
    private void setBusy(boolean value){if(resultActions!=null)resultActions.setDisable(value);if(modelActions!=null)modelActions.setDisable(value);if(queueActions!=null)queueActions.setDisable(value);}
    private void reload()throws Exception{
        var rows=new ArrayList<WorkshopRunRecord>();
        var live=WorkshopLocalWorkflow.onHost(()->{context();return qupath.getImageData();});
        String imageId=live==null?"":WorkshopLocalWorkflow.entry(project,live).getID();
        for(var record:store.list()){
            if(record.get("execution_status").equals("delegated"))continue;
            var view=record.json();
            if(record.writable()&&live!=null&&record.get("image_entry_id").equals(imageId)&&record.get("persistence_status").equals("saved")){
                try{
                    String snapshot=WorkshopLocalWorkflow.onHost(()->WorkshopLocalWorkflow.snapshot(live,record).id());
                    if(!snapshot.equals(record.get("measurement_snapshot_id")))view.addProperty("live_measurement_status","stale");
                    else view.addProperty("live_measurement_status","current");
                    if(!snapshot.equals(record.get("reviewed_snapshot_id"))&&record.get("review_status").equals("reviewed"))view.addProperty("review_status","needs-review");
                }catch(Exception ex){view.addProperty("live_measurement_status","recovery-required");}
            }
            rows.add(new WorkshopRunRecord(view));
        }
        var requests=queue.list();
        Platform.runLater(()->{results.getItems().setAll(rows);jobs.getItems().setAll(requests);});
    }
    private VBox resultPane(){
        resultActions=new HBox(8,
            button("workflow.reload",()->work(this::reload)),
            button("workflow.refresh",()->refresh(false)),
            button("workflow.review",()->refresh(true)),
            button("workflow.export",()->export()),
            button("workflow.reconcile",()->{var ids=selected();var data=image();work(()->{for(String id:ids)WorkshopLocalWorkflow.reconcile(project,data,id);reload();});}),
            button("workflow.include",()->include()));
        VBox box=new VBox(10,label("workflow.resultsHelp"),results,new ScrollPane(resultActions));VBox.setVgrow(results,Priority.ALWAYS);return box;
    }
    private void refresh(boolean review){
        var ids=selected();if(ids.isEmpty()){status.setText(t("workflow.selectResult"));return;}var data=image();
        work(()->{for(String id:ids)WorkshopLocalWorkflow.refresh(project,data,id,review);reload();});
    }
    private void export(){
        var ids=selected();if(ids.isEmpty()){status.setText(t("workflow.selectResult"));return;}var data=image();
        long incomplete=results.getSelectionModel().getSelectedItems().stream().filter(r->!r.get("execution_status").equals("succeeded")||!r.get("persistence_status").equals("saved")).count();
        if(incomplete>0&&!Dialogs.showConfirmDialog(t("workflow.export"),WorkshopI18n.tf("workflow.partialConfirm",incomplete)))return;
        DirectoryChooser chooser=new DirectoryChooser();chooser.setTitle(t("workflow.export"));var directory=chooser.showDialog(stage);if(directory==null)return;
        work(()->{Path exported=WorkshopLocalWorkflow.export(project,data,ids,directory.toPath());Platform.runLater(()->details.setText(exported.toString()));});
    }
    private void include(){
        var selected=results.getSelectionModel().getSelectedItem();if(selected==null)return;var data=image();
        List<PathObject> objects=data.getHierarchy().getSelectionModel().getSelectedObjects().stream().filter(PathObject::isDetection).toList();
        if(objects.isEmpty()){status.setText(t("workflow.selectObjects"));return;}
        if(!Dialogs.showConfirmDialog(t("workflow.include"),WorkshopI18n.tf("workflow.includeConfirm",objects.size())))return;
        work(()->{WorkshopLocalWorkflow.includeObjects(project,data,selected.id(),objects);reload();});
    }
    private VBox modelPane(){
        adapter.getItems().setAll("histoplus","nuclick");adapter.setValue("histoplus");
        model.getItems().setAll("40x","20x");model.setValue("40x");device.getItems().setAll("cpu","cuda");device.setValue("cpu");
        adapter.valueProperty().addListener((o,old,value)->{model.getItems().setAll(value.equals("histoplus")?List.of("40x","20x"):List.of("original"));model.getSelectionModel().selectFirst();recipe=null;});
        model.valueProperty().addListener((o,a,b)->recipe=null);device.valueProperty().addListener((o,a,b)->{
            if(recipe!=null){var changed=recipe.json();changed.addProperty("device",b);recipe=new WorkshopRecipe(changed);}
        });
        modelActions=new HBox(8,
            button("workflow.configure",this::configure),
            button("workflow.setup",()->WorkshopExtension.openLocalSetup(qupath,adapter.getValue())),
            button("workflow.verify",()->verify()),
            button("workflow.runtimeTest",()->runtimeTest()),
            button("workflow.importRecipe",this::importRecipe),button("workflow.saveRecipe",this::saveRecipe));
        return new VBox(12,label("workflow.modelsHelp"),new HBox(10,label("workflow.model"),adapter,model,label("workflow.device"),device),new ScrollPane(modelActions));
    }
    private WorkshopModelDescriptor descriptor(boolean prepare){return WorkshopModelDescriptor.configured(adapter.getValue(),model.getValue(),prepare);}
    private void configure(){
        var d=descriptor(true);Dialog<ButtonType> dialog=new Dialog<>();dialog.initOwner(stage);dialog.setTitle(t("workflow.configure"));
        GridPane grid=new GridPane();grid.setHgap(8);grid.setVgap(10);
        TextField python=new TextField(d.python().toString()),runner=new TextField(d.runner()==null?"":d.runner().toString()),weights=new TextField(d.weights().toString()),hash=new TextField(d.expectedSha256());
        TextField[] fields={python,runner,weights,hash};String[] keys={"workflow.python","workflow.runner","workflow.weights","workflow.expectedHash"};
        for(int i=0;i<fields.length;i++){fields[i].setPrefColumnCount(60);grid.add(new Label(t(keys[i])),0,i);grid.add(fields[i],1,i);}
        CheckBox licence=new CheckBox(t("workflow.licenceAck")+" ("+d.licence()+")");licence.setSelected(d.licenceAccepted());licence.setWrapText(true);grid.add(licence,1,4);
        if(d.adapter().equals("histoplus")){licence.setDisable(true);grid.add(new Label(t("workflow.histoLicence")),1,5);}
        dialog.getDialogPane().setContent(grid);dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        if(dialog.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK){
            var p=Preferences.userRoot().node("/qupath/atolye/workflow/"+d.adapter());p.put("python",python.getText().trim());p.put("runner",runner.getText().trim());p.put("weights."+d.model(),weights.getText().trim());
            if(d.adapter().equals("nuclick")){p.put("expectedSha256.original",hash.getText().trim());p.putBoolean("licenceAccepted",licence.isSelected());}
            // Paths belong to this machine; preserve the imported recipe's model identity and parameters.
        }
    }
    private void verify(){var d=descriptor(false);String chosenDevice=device.getValue();WorkshopRecipe imported=recipe;work(()->{
        var report=WorkshopModelReadiness.inspect(d,true);String text=WorkshopRunStore.JSON.toJson(report.evidence())+"\n"+String.join("\n",report.problems().stream().map(WorkshopResultsDialog::t).toList());
        WorkshopRecipe ready=null;
        if(report.ready()){
            if(imported!=null){
                if(!imported.get("weight_sha256").equals(report.evidence().get("weight_sha256").getAsString())||!imported.get("runner_sha256").equals(report.evidence().get("runner_sha256").getAsString()))throw new IllegalStateException("Prepared files differ from the imported recipe; configure the intended files first");
                ready=imported;
            }else ready=WorkshopRecipe.prepared(d,report,chosenDevice);
        }
        final WorkshopRecipe verified=ready;
        Platform.runLater(()->{recipe=verified;details.setText(text);});
    });}
    private void runtimeTest(){var d=descriptor(false);String chosenDevice=device.getValue();work(()->{
        var ready=WorkshopModelReadiness.inspect(d,true);if(!ready.ready())throw new IllegalStateException(String.join("\n",ready.problems().stream().map(WorkshopResultsDialog::t).toList()));
        Path work=Files.createTempDirectory(store.root(),"runtime-test-");
        var args=d.adapter().equals("histoplus")?List.of("selftest"):List.of("selftest","--device",chosenDevice);
        var lines=WorkshopLocalRunner.process(d,args,work,new AtomicBoolean());Platform.runLater(()->details.setText(String.join("\n",lines)));
    });}
    private void importRecipe(){FileChooser chooser=new FileChooser();chooser.setTitle(t("workflow.importRecipe"));var file=chooser.showOpenDialog(stage);if(file==null)return;
        try{var imported=WorkshopRecipe.load(file.toPath());adapter.setValue(imported.get("adapter"));model.setValue(imported.get("model"));device.setValue(imported.get("device"));recipe=imported;details.setText(WorkshopRunStore.JSON.toJson(imported.json()));}
        catch(Exception e){Dialogs.showErrorMessage(t("workflow.importRecipe"),e.getMessage());}
    }
    private void saveRecipe(){if(recipe==null){status.setText(t("workflow.verifyFirst"));return;}var ready=recipe;
        FileChooser chooser=new FileChooser();chooser.setTitle(t("workflow.saveRecipe"));chooser.setInitialFileName("local-workflow.json");var file=chooser.showSaveDialog(stage);if(file!=null)work(()->ready.save(file.toPath()));
    }
    private VBox queuePane(){
        queueActions=new HBox(8,button("workflow.add",()->enqueue(WorkshopExecutionPolicy.Action.ADD)),
            button("workflow.complete",()->enqueue(WorkshopExecutionPolicy.Action.COMPLETE_MISSING)),
            button("workflow.regenerate",()->enqueue(WorkshopExecutionPolicy.Action.REGENERATE_SELECTED)),
            button("workflow.runQueue",this::runQueue),button("workflow.retry",()->{var selected=jobs.getSelectionModel().getSelectedItem();if(selected!=null)work(()->{queue.retry(selected.get("job_id").getAsString());reload();});}),
            button("workflow.recoverQueue",()->work(()->{queue.reconcileInterrupted();reload();})));
        VBox box=new VBox(10,label("workflow.queueHelp"),jobs,new ScrollPane(queueActions));VBox.setVgrow(jobs,Priority.ALWAYS);return box;
    }
    private void enqueue(WorkshopExecutionPolicy.Action action){
        if(recipe==null){status.setText(t("workflow.verifyFirst"));return;}var data=image();var ready=recipe;
        var selected=data.getHierarchy().getSelectionModel().getSelectedObjects().stream().filter(o->o.isAnnotation()&&o.getROI()!=null&&o.getROI().isArea()).toList();
        if(selected.size()!=1){status.setText(t("workflow.selectRoi"));return;}var roi=selected.getFirst().getROI();
        var previous=results.getSelectionModel().getSelectedItem();String replaceId="",snapshot="";
        if(action==WorkshopExecutionPolicy.Action.REGENERATE_SELECTED){
            if(previous==null){status.setText(t("workflow.selectResult"));return;}
            var owned=WorkshopLocalWorkflow.owned(data,previous,false);
            if(!Dialogs.showConfirmDialog(t("workflow.regenerate"),WorkshopI18n.tf("workflow.replaceConfirm",previous.resultId(),owned.size())))return;
            replaceId=previous.id();snapshot=WorkshopLocalWorkflow.snapshot(data,previous).id();
        }
        String target=replaceId,expected=snapshot;var entry=WorkshopLocalWorkflow.entry(project,data);
        work(()->{queue.enqueue(entry.getID(),WorkshopObjectSnapshot.serializeRoi(roi),ready,action,target,expected,action==WorkshopExecutionPolicy.Action.REGENERATE_SELECTED);reload();});
    }
    private void runQueue(){context();queue.start();work(()->{
        while(queue.runNext((job,cancelled)->{
            var target=project.getImageList().stream().filter(e->e.getID().equals(job.get("image_entry_id").getAsString())).findFirst().orElseThrow();
            var live=WorkshopLocalWorkflow.onHost(()->{
                context();var images=qupath.getAllViewers().stream().map(v->v.getImageData()).filter(d->d!=null&&target.equals(project.getEntry(d))).distinct().toList();
                if(images.size()>1)throw new IllegalStateException("The target image is open in multiple viewers; close duplicate views first");
                return images.isEmpty()?null:images.getFirst();
            });
            boolean close=live==null;var data=close?target.readImageData():live;
            try{return WorkshopLocalRunner.execute(project,data,job,cancelled);}finally{if(close)data.close();}
        })){reload();}
        reload();
    });}
}
