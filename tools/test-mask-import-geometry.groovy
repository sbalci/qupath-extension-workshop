/* Compile the packaged importer and exercise its numeric gates on a real QuPath host; never run its UI. */
import java.util.jar.JarFile

def root = new File(System.getenv('WORKSHOP_EXAMPLE_ROOT') ?: '.')
def jar = new File(System.getenv('WORKSHOP_EXAMPLE_JAR') ?: new File(root, 'build/libs/qupath-extension-workshop-0.2.2-rc9.jar').path)
assert jar.isFile(): 'Build the local development JAR first.'
new JarFile(jar).withCloseable { archive ->
    String source = archive.getInputStream(archive.getJarEntry('scripts/yardimci-maske-iceaktar.groovy')).getText('UTF-8')
    new GroovyClassLoader(this.class.classLoader).withCloseable { loader ->
        loader.parseClass(source, 'PackagedMaskImporter.groovy')
    }
    def closure = { String name ->
        int start = source.indexOf('def ' + name + ' = {')
        assert start >= 0: 'Missing packaged helper: ' + name
        int end = source.indexOf('\n}', start)
        assert end > start
        new GroovyShell(this.class.classLoader).evaluate(source.substring(start, end + 2) + '\n' + name)
    }
    def scale = closure('maskDownsample')
    [Double.NaN, Double.POSITIVE_INFINITY, 0, -1, 'bad'].each { value ->
        boolean rejected = false
        try { scale(value) } catch (IllegalArgumentException expected) { rejected = true }
        assert rejected: 'Invalid scale accepted: ' + value
    }
    assert scale('0,25') == 0.25d
    def mismatch = closure('maskDimensionMismatch')
    assert !mismatch(250, 200, 4d, 1000, 800)
    assert mismatch(250, 100, 4d, 1000, 800): 'Height mismatch was ignored'
}
println 'PACKAGED MASK IMPORT PASSED: full script compiled; invalid scales rejected; both axes checked; no UI or image mutation.'
