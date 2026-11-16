/* Run with QuPath CLI; no GUI, network, patient data or saved project is used. */
import java.awt.image.BufferedImage
import java.util.jar.JarFile
import qupath.lib.images.ImageData
import qupath.lib.images.servers.ImageServerProvider
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane

def root = new File(System.getenv('WORKSHOP_EXAMPLE_ROOT') ?: '.')
def jar = new File(System.getenv('WORKSHOP_EXAMPLE_JAR') ?: new File(root, 'build/libs/qupath-extension-workshop-0.2.2-rc9.jar').path)
def image = new File(root, 'assets/examples/synthetic-nuclei.png')
assert image.isFile() && jar.isFile(): 'Build the JAR and synthetic example first.'
def server = ImageServerProvider.buildServer(image.toURI().toString(), BufferedImage.class)
try {
    def data = new ImageData(server, ImageData.ImageType.BRIGHTFIELD_H_E)
    data.updateServerMetadata(new ImageServerMetadata.Builder(server.getMetadata()).pixelSizeMicrons(0.5, 0.5).build())
    QP.setBatchImageData(data)
    def region = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 512, 512, ImagePlane.getDefaultPlane()))
    data.getHierarchy().addObject(region)
    QP.selectObjects(region)
    new JarFile(jar).withCloseable { archive ->
        def script = archive.getInputStream(archive.getJarEntry('scripts/hucre-tespiti.groovy')).getText('UTF-8')
        new GroovyShell(this.class.classLoader, new Binding()).evaluate(script)
    }
    def detections = data.getHierarchy().getDetectionObjects()
    assert detections.size() == 100: 'Expected 100 synthetic objects; found ' + detections.size()
    assert detections.every { it.getROI() != null && !it.getMeasurementList().isEmpty() }
    println 'FIRST RUN PASSED: 100 synthetic objects, separate contours and non-empty measurement rows.'
} finally {
    QP.setBatchImageData(null)
    server.close()
}
