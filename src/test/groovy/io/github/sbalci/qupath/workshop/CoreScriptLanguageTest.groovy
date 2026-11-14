package io.github.sbalci.qupath.workshop

import org.junit.Test
import static org.junit.Assert.*

class CoreScriptLanguageTest {
    @Test
    void coreDisplayCatalogMatchesBothBundlesAndScriptFallbacks() {
        def keys = WorkshopI18n.trKeys().findAll { it ==~ /script\.[0-9a-f]{12}/ }
        assertEquals(143, keys.size())
        keys.each { key ->
            assertNotEquals(key, WorkshopI18n.t(key, false))
            assertNotEquals(key, WorkshopI18n.t(key, true))
        }
        ['arayuz-turu.groovy', 'sihirbaz.groovy', 'tumor-ici-ki67.groovy',
         'tumor-stroma.groovy', 'veri-aktarma.groovy', 'quantum-ctcf.groovy',
         'membran-boya.groovy', 'sitoplazmik-boya.groovy'].each { filename ->
            def script = getClass().getResource('/scripts/' + filename).getText('UTF-8')
            assertTrue(filename, script.contains('private String workshopText(String key, String fallback)'))
            def used = (script =~ /workshopText\('(script\.[0-9a-f]+)'/).collect { it[1] }
            assertFalse(filename, used.isEmpty())
            assertTrue(filename, used.every { key -> keys.contains(key) })
        }
        assertTrue(keys.any { WorkshopI18n.t(it, true) == 'Step ' })
    }
}
