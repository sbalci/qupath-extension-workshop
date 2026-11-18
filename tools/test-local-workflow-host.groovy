/** Run against the candidate JAR first on the classpath plus an installed QuPath app's jars.
 * Usage: groovy.ui.GroovyMain tools/test-local-workflow-host.groovy <new-output-dir> [gui]
 * Uses only a generated image/project; never installs into the user's QuPath profile.
 */
import io.github.sbalci.qupath.workshop.*
import java.awt.image.BufferedImage
import java.nio.file.*
import java.util.concurrent.*
import javax.imageio.ImageIO
import qupath.lib.images.ImageData
import qupath.lib.images.servers.ImageServerProvider
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.projects.Projects
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass

assert args.length>=1
def root=Path.of(args[0]).toAbsolutePath()
assert !Files.exists(root):'Use a new acceptance output directory'
Files.createDirectories(root)
def png=root.resolve('synthetic.png')
def pixels=new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB)
def graphics=pixels.createGraphics();graphics.color=java.awt.Color.WHITE;graphics.fillRect(0,0,256,256);graphics.dispose()
ImageIO.write(pixels,'PNG',png.toFile())
def server=ImageServerProvider.buildServer(png.toString(),BufferedImage.class)
def projectDirectory=Files.createDirectory(root.resolve('project'))
def project=Projects.createProject(projectDirectory.toFile(),BufferedImage.class)
def entry=project.addImage(server.builder)
def data=new ImageData<BufferedImage>(server)
data.setImageType(ImageData.ImageType.BRIGHTFIELD_H_E)
data.updateServerMetadata(new ImageServerMetadata.Builder(server.metadata).pixelSizeMicrons(0.5d,1d).build())
def roi=ROIs.createRectangleROI(0,0,100,100,ImagePlane.defaultPlane)
def cell=PathObjects.createDetectionObject(ROIs.createRectangleROI(10,10,2,4,ImagePlane.defaultPlane),PathClass.fromString('Tumor'))
def unrelated=PathObjects.createDetectionObject(ROIs.createRectangleROI(20,10,2,4,ImagePlane.defaultPlane),PathClass.fromString('Tumor'))
def summary=PathObjects.createAnnotationObject(roi);summary.name='Recorded synthetic result'
data.hierarchy.addObjects([cell,unrelated,summary]);entry.saveImageData(data);project.syncChanges()
def id=WorkshopLocalWorkflow.recordCompleted(project,data,'histoplus',roi,[cell],summary,[:])
def recorded=WorkshopLocalWorkflow.store(project).read(id)
assert recorded.get('persistence_status')=='saved'
def reopened=entry.readImageData()
assert WorkshopLocalWorkflow.owned(reopened,recorded,false).size()==1
def owned=WorkshopLocalWorkflow.owned(reopened,recorded,false)[0]
assert owned.ID==cell.ID
owned.setROI(ROIs.createRectangleROI(10,10,4,4,ImagePlane.defaultPlane))
def refreshed=WorkshopLocalWorkflow.refresh(project,reopened,id,true)
assert refreshed.json().measurements.total_count.asInt==1
assert refreshed.json().measurements.mean_nuclear_area_um2.asDouble==8d
def exported=WorkshopLocalWorkflow.export(project,reopened,[id],root)
assert Files.exists(exported.resolve('evidence.json'))
assert reopened.hierarchy.detectionObjects.size()==2
println 'HOST_ACCEPTANCE_OK '+qupath.lib.common.GeneralTools.version+' '+WorkshopRunStore.sha256(Path.of(WorkshopRunRecord.protectionDomain.codeSource.location.toURI()))

if(args.length>1&&args[1]=='gui'){
    assert java.util.prefs.Preferences.userRoot().class.name.contains('WorkflowTestPreferencesFactory'):'GUI acceptance requires isolated in-memory preferences'
    def failure=new java.util.concurrent.atomic.AtomicReference<Throwable>()
    def finished=new CountDownLatch(1)
    def watchdog=new Thread({if(!finished.await(60,TimeUnit.SECONDS)){System.err.println('GUI acceptance timed out');System.exit(2)}} as Runnable)
    watchdog.daemon=true;watchdog.start()
    javafx.application.Platform.startup({
        try{
            def profile=Files.createDirectory(root.resolve('profile'))
            qupath.lib.gui.prefs.PathPrefs.userPathProperty().set(profile.toString())
            def stage=new javafx.stage.Stage();stage.x=-1800;stage.y=30
            def gui=qupath.lib.gui.QuPathGUI.createInstance(stage)
            gui.setProject(project);gui.getViewer().setImageData(reopened)
            def menu=gui.getMenu('Extensions>Atölye',false)
            if(menu==null||!menu.items.any{it.text==WorkshopI18n.t('workflow.title')}){
                new WorkshopExtension().installExtension(gui);menu=gui.getMenu('Extensions>Atölye',false)
            }
            assert menu.items.any{it.text==WorkshopI18n.t('workflow.title')}
            assert WorkshopI18n.t('workflow.title')!='workflow.title'
            WorkshopResultsDialog.show(gui)
            def field=WorkshopResultsDialog.getDeclaredField('current');field.accessible=true
            def dialog=field.get(null);assert dialog!=null&&dialog.showing
            dialog.x=-1800;dialog.y=30
            def pane=dialog.scene.root.lookup('.tab-pane')
            assert pane.tabs.size()==3
            // Run after the initial record-list reload and layout have completed.
            def capture=new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2))
            capture.onFinished={
                try{
                    def language=WorkshopI18n.getDeclaredField('activeLanguage');language.accessible=true
                    def listeners=WorkshopI18n.getDeclaredField('LISTENERS');listeners.accessible=true
                    for(String code:['tr','en']){
                        language.set(null,code)
                        listeners.get(null).each{it.run()}
                        assert dialog.title==WorkshopI18n.t('workflow.title')
                        for(int i=0;i<3;i++){
                            pane.selectionModel.select(i);dialog.scene.root.applyCss();dialog.scene.root.layout()
                            def image=dialog.scene.root.snapshot(null,null)
                            ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(image,null),'PNG',root.resolve('gui-'+code+'-tab-'+i+'.png').toFile())
                        }
                    }
                    println 'GUI_ACCEPTANCE_OK '+qupath.lib.common.GeneralTools.version
                }catch(Throwable t){failure.set(t);t.printStackTrace()}
                finally{dialog.close();stage.hide();finished.countDown();javafx.application.Platform.exit()}
            } as javafx.event.EventHandler
            capture.play()
        }catch(Throwable t){failure.set(t);t.printStackTrace();finished.countDown();javafx.application.Platform.exit()}
    } as Runnable)
    assert finished.await(65,TimeUnit.SECONDS)
    if(failure.get()!=null)throw failure.get()
    System.exit(0)
}
reopened.close();data.close()
