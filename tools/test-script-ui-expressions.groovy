/* Execute only language helpers and compiler-injected synthetic GStrings. */
import java.net.URLClassLoader
import java.util.jar.JarFile
import qupath.lib.io.GsonTools

def root = new File(System.getenv('WORKSHOP_EXAMPLE_ROOT') ?: '.')
def jarFile = new File(System.getenv('WORKSHOP_EXAMPLE_JAR') ?: new File(root,'build/libs/qupath-extension-workshop-0.2.2-rc8.jar').path)
assert jarFile.isFile(): 'Build the extension first.'

def loader = new URLClassLoader([jarFile.toURI().toURL()] as URL[],this.class.classLoader)
try {
    def bundle = loader.loadClass('io.github.sbalci.qupath.workshop.WorkshopI18n')
    assert bundle.protectionDomain.codeSource.location.toURI() == jarFile.toURI()
    def field = bundle.getDeclaredField('activeLanguage'); field.setAccessible(true)
    def previous = field.get(null)
    try {
        new JarFile(jarFile).withCloseable { archive ->
            def catalog = GsonTools.getInstance().fromJson(archive.getInputStream(archive.getJarEntry('i18n/script-core.json')).getText('UTF-8'),Map)
            def sources = catalog.scripts.collectEntries { filename ->
                [(filename):archive.getInputStream(archive.getJarEntry('scripts/'+filename)).getText('UTF-8')]
            }
            new GroovyClassLoader(this.class.classLoader).withCloseable { supportLoader ->
                def support = supportLoader.parseClass(new File(root,'tools/script-ui-expression-support.groovy'))
                def bilingual = support.probe(catalog,sources,loader,{ language -> field.set(null,language) },['tr','en'])
                // The parent alone cannot load this extension: true standalone fallback.
                boolean found = false
                try { this.class.classLoader.loadClass('io.github.sbalci.qupath.workshop.WorkshopI18n'); found = true }
                catch (ClassNotFoundException ignored) { }
                assert !found: 'Standalone probe needs a parent without the test extension.'
                def standalone = support.probe(catalog,sources,this.class.classLoader,{ language -> },['tr'])
                println 'SCRIPT UI EXPRESSIONS PASSED: ' + bilingual + '; standalone=' + standalone + '; no GUI, image processing or preference write.'
            }
        }
    } finally { field.set(null,previous) }
} finally { loader.close() }
