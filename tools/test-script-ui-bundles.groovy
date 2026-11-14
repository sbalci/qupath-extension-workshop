/* Exercise the actual script lookup method without running any image/UI code. */
import java.net.URLClassLoader
import java.util.jar.JarFile
import qupath.lib.io.GsonTools

def root = new File(System.getenv('WORKSHOP_EXAMPLE_ROOT') ?: '.')
def jarFile = new File(System.getenv('WORKSHOP_EXAMPLE_JAR') ?: new File(root, 'build/libs/qupath-extension-workshop-0.2.2-rc8.jar').path)
assert jarFile.isFile(): 'Build the extension first.'
def loader = new URLClassLoader([jarFile.toURI().toURL()] as URL[], this.class.classLoader)
try {
    def bundle = loader.loadClass('io.github.sbalci.qupath.workshop.WorkshopI18n')
    assert bundle.protectionDomain.codeSource.location.toURI() == jarFile.toURI()
    // Test process memory only: never call the preference-persisting setter.
    def languageField = bundle.getDeclaredField('activeLanguage')
    languageField.setAccessible(true)
    def original = languageField.get(null)
    int count = 0
    try {
        new JarFile(jarFile).withCloseable { archive ->
            def catalog = GsonTools.getInstance().fromJson(archive.getInputStream(archive.getJarEntry('i18n/script-core.json')).getText('UTF-8'), Map)
            catalog.scripts.each { filename ->
                String source = archive.getInputStream(archive.getJarEntry('scripts/' + filename)).getText('UTF-8')
                String helper = source.substring(source.lastIndexOf('private String workshopText('))
                new GroovyClassLoader(loader).withCloseable { scriptLoader ->
                    def probe = scriptLoader.parseClass(helper, 'LookupProbe.groovy').getConstructor().newInstance()
                    ['tr', 'en'].each { language ->
                        languageField.set(null, language)
                        catalog.strings.each { row ->
                            assert probe.invokeMethod('workshopText', [row.key, row.tr] as Object[]) == row[language]: filename + ' / ' + language + ' / ' + row.key
                            count++
                        }
                        assert probe.invokeMethod('workshopText', ['missing.ui.key', 'Turkish fallback'] as Object[]) == 'Turkish fallback'
                    }
                }
                new GroovyClassLoader(this.class.classLoader).withCloseable { standaloneLoader ->
                    def probe = standaloneLoader.parseClass(helper, 'StandaloneProbe.groovy').getConstructor().newInstance()
                    assert probe.invokeMethod('workshopText', [catalog.strings[0].key, 'Turkish fallback'] as Object[]) == 'Turkish fallback'
                }
            }
            String cellSource = archive.getInputStream(archive.getJarEntry('scripts/hucre-tespiti.groovy')).getText('UTF-8')
            String cellHelper = cellSource.substring(cellSource.indexOf('def atolyeText ='), cellSource.indexOf('// --- Atölye ayarları:'))
            def cellLookup = new GroovyShell(loader).evaluate(cellHelper + '\nreturn atolyeText')
            def keys = bundle.getMethod('trKeys').invoke(null).findAll { it.startsWith('cell.') }
            ['tr', 'en'].each { language ->
                languageField.set(null, language)
                keys.each { key ->
                    assert cellLookup.call(key, 'fallback') == bundle.getMethod('t', String.class, Boolean.TYPE).invoke(null, key, language == 'en')
                    count++
                }
            }
            def standaloneCell = new GroovyShell(this.class.classLoader).evaluate(cellHelper + '\nreturn atolyeText')
            assert standaloneCell.call(keys.first(), 'Turkish fallback') == 'Turkish fallback'
        }
    } finally { languageField.set(null, original) }
    println 'SCRIPT UI LOOKUP PASSED: ' + count + ' TR/EN lookups and standalone fallback checks; no GUI or preference write.'
} finally { loader.close() }
