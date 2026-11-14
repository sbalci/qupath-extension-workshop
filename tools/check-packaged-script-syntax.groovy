/* Parse every packaged script in the real QuPath host without running it. */
import java.util.jar.JarFile

def root = new File(System.getenv('WORKSHOP_EXAMPLE_ROOT') ?: '.')
def jarFile = new File(System.getenv('WORKSHOP_EXAMPLE_JAR') ?: new File(root, 'build/libs/qupath-extension-workshop-0.2.2-rc8.jar').path)
assert jarFile.isFile(): 'Build the extension JAR first.'
int count = 0
new JarFile(jarFile).withCloseable { archive ->
    archive.entries().each { entry ->
        if (entry.name ==~ /scripts\/[^\/]+\.groovy/) {
            new GroovyClassLoader(this.class.classLoader).withCloseable { loader ->
                loader.parseClass(archive.getInputStream(entry).getText('UTF-8'), entry.name.tokenize('/').last())
            }
            count++
        }
    }
}
assert count == 103
println 'PACKAGED SCRIPT SYNTAX PASSED: ' + count + ' scripts parsed; none executed.'
