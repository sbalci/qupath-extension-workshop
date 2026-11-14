package io.github.sbalci.qupath.workshop

import org.junit.Test
import com.google.gson.Gson
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import static org.junit.Assert.*

class CoreScriptLanguageTest {
    @Test
    void coreDisplayCatalogMatchesBothBundlesAndScriptFallbacks() {
        def keys = WorkshopI18n.trKeys().findAll { it ==~ /script\.[0-9a-f]{12}/ }
        def catalog = new Gson().fromJson(getClass().getResource('/i18n/script-core.json').getText('UTF-8'), Map)
        assertEquals(565, keys.size())
        assertEquals(catalog.strings.collect { it.key } as Set, keys as Set)
        catalog.strings.each { row ->
            assertEquals(row.key, row.tr, WorkshopI18n.t(row.key as String, false))
            assertEquals(row.key, row.en, WorkshopI18n.t(row.key as String, true))
        }
        keys.each { key ->
            assertNotEquals(key, WorkshopI18n.t(key, false))
            assertNotEquals(key, WorkshopI18n.t(key, true))
        }
        assertEquals(15, catalog.scripts.size())
        catalog.scripts.each { filename ->
            def script = getClass().getResource('/scripts/' + filename).getText('UTF-8')
            assertTrue(filename, script.contains('private String workshopText(String key, String fallback)'))
            def used = (script =~ /workshopText\('(script\.[0-9a-f]+)'/).collect { it[1] }
            assertFalse(filename, used.isEmpty())
            assertTrue(filename, used.every { key -> keys.contains(key) })
            def rows = catalog.strings.collectEntries { row -> [(row.key): row] }
            def constantValue
            constantValue = { expression ->
                if (expression instanceof ConstantExpression && expression.value instanceof String) return expression.value
                if (expression instanceof BinaryExpression && expression.operation.text == '+') {
                    def left = constantValue(expression.leftExpression), right = constantValue(expression.rightExpression)
                    if (left != null && right != null) return left + right
                }
                return null
            }
            def unit = SourceUnit.create(filename as String, script)
            unit.parse(); unit.completePhase(); unit.convert()
            int checked = 0
            def visitor = new CodeVisitorSupport() {
                void visitMethodCallExpression(MethodCallExpression call) {
                    if (call.methodAsString == 'workshopText') {
                        def args = call.arguments.expressions
                        def key = constantValue(args[0]), fallback = constantValue(args[1])
                        assertNotNull(filename as String, rows[key])
                        assertEquals(filename as String, rows[key].tr, fallback)
                        checked++
                    }
                    super.visitMethodCallExpression(call)
                }
            }
            unit.AST.statementBlock.visit(visitor)
            unit.AST.methods.each { method -> method.code?.visit(visitor) }
            assertTrue(filename as String, checked > 0)
        }
        assertTrue(keys.any { WorkshopI18n.t(it, true) == 'Step ' })
    }

    @Test
    void translatedNumericAndPathTemplatesRetainTheirFormatArguments() {
        def catalog = new Gson().fromJson(getClass().getResource('/i18n/script-core.json').getText('UTF-8'), Map)
        def signature = { String text ->
            // Java Formatter conversions only: prose such as "% ve" is not a format token.
            (text =~ /((?<!%)%(?!%)(?:\d+\$)?[-#+0, (<]*\d*(?:\.\d+)?(?:[tT][a-zA-Z]|[bBhHsScCdoxXeEfgGaAn]))/).collect { it[0] }
        }
        catalog.strings.each { row ->
            assertEquals(row.key, signature(row.tr as String), signature(row.en as String))
        }
        def warning = catalog.strings.find { it.tr.startsWith('⚠ %.4f µm/px') }
        def project = catalog.strings.find { it.tr.startsWith('Açık görüntü %.4f') }
        def folder = catalog.strings.find { it.tr.startsWith('Veri kökü: %s%s') }
        assertNotNull(warning); assertNotNull(project); assertNotNull(folder)
        [false, true].each { english ->
            [warning, project].each { row ->
                def rendered = String.format(Locale.US, WorkshopI18n.t(row.key as String, english), 0.26d)
                assertTrue(rendered, rendered.contains('0.2600 µm/px'))
            }
            def rendered = String.format(Locale.US, WorkshopI18n.t(folder.key as String, english),
                    'D:/synthetic workshop', '', 12.5d)
            assertTrue(rendered, rendered.contains('D:/synthetic workshop'))
            assertTrue(rendered, rendered.contains('12.5 GB'))
            def classpose = catalog.strings.find { it.tr.startsWith('Bu alandaki %,d özet') }
            def cytoformer = catalog.strings.find { it.tr.startsWith('%,d hücrenin mevcut sınıfı') }
            assertNotNull(classpose); assertNotNull(cytoformer)
            def replace = String.format(Locale.US, WorkshopI18n.t(classpose.key as String, english), 17, 31)
            assertTrue(replace, replace.indexOf('17') >= 0 && replace.indexOf('31') > replace.indexOf('17'))
            assertTrue(String.format(Locale.US, WorkshopI18n.t(cytoformer.key as String, english), 126).contains('126'))
        }
    }
}
