package io.github.sbalci.qupath.workshop

import com.google.gson.JsonParser
import groovy.test.GroovyTestCase
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.function.ToDoubleFunction

/**
 * Verifies TR/EN resource bundle parity, bilingual microcopy & validation,
 * classpath presence of all registered menu scripts, and catalog.json integrity
 * without requiring JavaFX or qupath-gui-fx on the test classpath.
 */
class WorkshopI18nAndCatalogTest extends GroovyTestCase {

    @Override
    protected void tearDown() throws Exception {
        WorkshopI18n.setLanguage('tr')
        super.tearDown()
    }

    void testTrAndEnBundlesHaveExactKeyParity() {
        def tr = WorkshopI18n.trKeys()
        def en = WorkshopI18n.enKeys()
        assertFalse('Turkish bundle should not be empty', tr.isEmpty())
        assertEquals('TR and EN bundle key sets must match 1:1', tr, en)
        tr.each { String k ->
            assertFalse("Blank TR value for ${k}", WorkshopI18n.t(k, false).trim().isEmpty())
            assertFalse("Blank EN value for ${k}", WorkshopI18n.t(k, true).trim().isEmpty())
        }
    }

    void testAllRegisteredExtensionMenuKeysAndScriptsResolve() {
        def extSrcPath = Paths.get('src/main/java/io/github/sbalci/qupath/workshop/WorkshopExtension.java')
        assertTrue('WorkshopExtension.java must exist', Files.exists(extSrcPath))
        String src = Files.readString(extSrcPath, StandardCharsets.UTF_8)

        // Every new ScriptGroup("...", "group.key", ...) i18n key must resolve in TR & EN
        def groupMatcher = (src =~ /new\s+ScriptGroup\(\s*"[^"]+"\s*,\s*"([^"]+)"/)
        def groupKeys = groupMatcher.collect { it[1] as String }
        assertEquals(12, groupKeys.size())
        groupKeys.each { String key ->
            assertFalse("Unmapped TR group key: ${key}", WorkshopI18n.t(key, false) == key)
            assertFalse("Unmapped EN group key: ${key}", WorkshopI18n.t(key, true) == key)
        }

        // Every new ScriptEntry("...", "foo.groovy" [, "explicit.key", ...]) must exist on classpath & resolve in TR/EN
        def entryMatcher = (src =~ /new\s+ScriptEntry\(\s*"[^"]+"\s*,\s*"([a-z0-9\-]+\.groovy)"(?:\s*,\s*"([^"]+)")?/)
        def entries = entryMatcher.collect { [resource: it[1] as String, explicitKey: it[2] as String] }
        assertTrue("Expected >= 95 ScriptEntry declarations, found ${entries.size()}", entries.size() >= 95)

        entries.each { Map e ->
            String scriptName = e.resource
            assertNotNull("Missing bundled script resource on classpath: /scripts/${scriptName}",
                    WorkshopResources.class.getResource('/scripts/' + scriptName))
            assertNotNull("WorkshopResources.getBundledScript returned null for ${scriptName}",
                    WorkshopResources.getBundledScript(scriptName))

            String i18nKey = e.explicitKey ?: ('script.' + scriptName)
            assertFalse("Unmapped TR script key: ${i18nKey}", WorkshopI18n.t(i18nKey, false) == i18nKey)
            assertFalse("Unmapped EN script key: ${i18nKey}", WorkshopI18n.t(i18nKey, true) == i18nKey)
        }

        // OB-7 glossary check: ranked hotspot selector uses 'yoğun odak' in TR and 'hotspot' in EN
        assertTrue(WorkshopI18n.t('script.yardimci-hotspot-sirali.groovy', false).contains('yoğun odak'))
        assertTrue(WorkshopI18n.t('script.yardimci-hotspot-sirali.groovy', true).toLowerCase().contains('hotspot'))
    }

    void testEnglishDualAudienceMicrocopyAndValidation() {
        WorkshopMicrocopy.KNOWN_KEYS.each { String key ->
            String enLabel = WorkshopMicrocopy.labelFor(key, true)
            String enTip = WorkshopMicrocopy.tooltipFor(key, true)
            assertFalse("Unmapped EN label for ${key}", enLabel == key)
            assertTrue("Missing Clinical bridge in EN tooltip for ${key}", enTip.contains('Clinical:'))
            assertTrue("Missing Computation bridge in EN tooltip for ${key}", enTip.contains('Computation:'))
        }

        WorkshopI18n.setLanguage('en')
        assertTrue(WorkshopErrors.guidance(new OutOfMemoryError()).toLowerCase().contains('memory'))
        assertTrue(WorkshopErrors.guidance(new FileNotFoundException('missing.svs')).toLowerCase().contains('file not found'))
        assertTrue(WorkshopErrors.guidance(null).contains('Show log'))

        def current = { String k -> (['atolye.nuclear1': 0.2d, 'atolye.nuclear2': 0.4d, 'atolye.nuclear3': 0.6d,
                                      'atolye.minArea': 10d, 'atolye.maxArea': 400d][k] ?: 0d) as double } as ToDoubleFunction<String>
        String orderErr = WorkshopParameterValidation.problem('atolye.nuclear1', 0.5d, current)
        assertNotNull(orderErr)
        assertTrue(orderErr.contains('1+ < 2+ < 3+'))
    }

    void testCatalogJsonMatchesExtensionVersionAndBaseline() {
        def catalogPath = Paths.get('catalog.json')
        assertTrue('catalog.json must exist at repo root', Files.exists(catalogPath))
        def parsed = JsonParser.parseString(Files.readString(catalogPath, StandardCharsets.UTF_8)).getAsJsonObject()
        def extensions = parsed.getAsJsonArray('extensions')
        assertNotNull(extensions)
        assertEquals(1, extensions.size())
        def ext = extensions.get(0).getAsJsonObject()
        assertEquals('QuPath Workshop extension (Atölye)', ext.get('name').getAsString())

        String extSrc = Files.readString(
                Paths.get('src/main/java/io/github/sbalci/qupath/workshop/WorkshopExtension.java'),
                StandardCharsets.UTF_8)
        def verMatch = (extSrc =~ /public Version getVersion\(\)\s*\{\s*return\s+Version\.parse\("([^"]+)"\)/)
        assertTrue('Could not find getVersion() in WorkshopExtension.java', verMatch.find())
        String expectedVer = 'v' + verMatch.group(1)

        def qpMatch = (extSrc =~ /public Version getQuPathVersion\(\)\s*\{\s*return\s+Version\.parse\("([^"]+)"\)/)
        assertTrue('Could not find getQuPathVersion() in WorkshopExtension.java', qpMatch.find())
        String expectedMin = 'v' + qpMatch.group(1)

        def releases = ext.getAsJsonArray('releases')
        assertEquals(expectedVer, releases.get(0).getAsJsonObject().get('name').getAsString())
        assertEquals(expectedMin, releases.get(0).getAsJsonObject().getAsJsonObject('version_range').get('min').getAsString())
        for (int i = 0; i < releases.size(); i++) {
            String rn = releases.get(i).getAsJsonObject().get('name').getAsString()
            assertTrue("Invalid release tag format: ${rn}", rn ==~ /^v\d+\.\d+\.\d+(-rc\d+)?$/)
        }
    }
}
