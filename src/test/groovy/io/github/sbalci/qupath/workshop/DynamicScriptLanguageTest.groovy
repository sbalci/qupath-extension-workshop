package io.github.sbalci.qupath.workshop

import com.google.gson.Gson
import org.junit.Test
import static org.junit.Assert.*

class DynamicScriptLanguageTest {
    private Map catalog() {
        new Gson().fromJson(getClass().getResource('/i18n/script-core.json').getText('UTF-8'),Map)
    }

    private Class support() {
        new GroovyClassLoader(getClass().classLoader).parseClass(new File('tools/script-ui-expression-support.groovy'))
    }

    @Test
    void compilerChecksEveryOriginalValueAndLiteralPosition() {
        def c = catalog(), probe = support()
        def rows = c.strings.collectEntries { [(it.key):it] }
        int count = 0
        c.source_contracts.each { filename,contract ->
            contract.expressions?.each { rewrite -> probe.verify(rewrite as Map,rows); count += rewrite.count as int }
        }
        assertEquals(46,count)
    }

    @Test
    void realLookupExpressionsKeepLazyValuesNumbersPathsAndScopeChoices() {
        def c = catalog(), probe = support()
        def sources = c.scripts.collectEntries { [(it):getClass().getResource('/scripts/'+it).getText('UTF-8')] }
        def field = WorkshopI18n.class.getDeclaredField('activeLanguage'); field.setAccessible(true)
        def previous = field.get(null)
        try {
            def result = probe.probe(c,sources,getClass().classLoader,{ language -> field.set(null,language) },['tr','en'])
            assertEquals(45,result.expressions)
            assertEquals(180,result.renders)
            assertEquals(12,result.scopeChecks)
        } finally { field.set(null,previous) }
    }

    @Test
    void changedDataCodeAndWrongFallbacksAreRejected() {
        def c = catalog(), probe = support(), rows = c.strings.collectEntries { [(it.key):it] }
        def rewrites = c.source_contracts.values().collectMany { it.expressions ?: [] }
        def original = rewrites.find { it.original.contains('activeWholeSlide.get()') }
        assertNotNull(original)
        for (def altered : [
            original.replacement.replace('activeWholeSlide.get()','true'),
            original.replacement.replace('activeModel.get()','"changed model"'),
            original.replacement.replace('çalıştırılıyor','CHANGED')
        ]) {
            assertNotEquals(original.replacement,altered)
            try {
                probe.verify([original:original.original,replacement:altered],rows)
                fail('An altered value or fallback should fail the compiler contract')
            } catch (AssertionError expected) {
                assertFalse(expected.message?.contains('An altered value'))
            }
        }
    }
}
