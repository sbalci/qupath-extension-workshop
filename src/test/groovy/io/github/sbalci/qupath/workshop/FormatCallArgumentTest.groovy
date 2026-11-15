package io.github.sbalci.qupath.workshop

import com.google.gson.Gson
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.control.SourceUnit
import org.junit.Test

import static org.junit.Assert.*

/**
 * A translated message that carries a value is a format pattern:
 * String.format(Locale.US, workshopText(key, tr), args...). The pattern runs in Turkish and
 * in English, so a wrong argument shows an error window instead of the result in both.
 * This test reads every such call in every packaged script and checks what can be known
 * without running QuPath: there are enough arguments, a whole-number conversion is not fed
 * an expression that is a decimal by construction, and a decimal conversion is not fed one
 * that is a whole number by construction.
 */
class FormatCallArgumentTest {
    private static final def CONVERSION = /(?<!%)%(?!%)(?:(\d+)\$)?[-#+0, (<]*\d*(?:\.\d+)?([tT][a-zA-Z]|[bBhHsScCdoxXeEfgGaAn])/
    private static final Set WHOLE_METHODS = ['size', 'length', 'getWidth', 'getHeight', 'nChannels', 'intValue',
                                              'longValue', 'getAsInt', 'nObjects', 'incrementAndGet'] as Set
    private static final Set DECIMAL_METHODS = ['doubleValue', 'floatValue', 'getAsDouble', 'getPixelWidthMicrons',
                                                'getPixelHeightMicrons', 'getAveragedPixelSizeMicrons', 'sqrt'] as Set

    private static String kind(expression) {
        if (expression instanceof ConstantExpression) {
            def value = expression.value
            if (value instanceof Integer || value instanceof Long || value instanceof BigInteger) return 'whole'
            if (value instanceof BigDecimal || value instanceof Double || value instanceof Float) return 'decimal'
            if (value instanceof String) return 'text'
            return null
        }
        if (expression instanceof CastExpression) {
            def type = ClassHelper.getUnwrapper(expression.type).name
            if (type in ['int', 'long', 'short', 'byte']) return 'whole'
            if (type in ['double', 'float']) return 'decimal'
            return null
        }
        if (expression instanceof MethodCallExpression && expression.arguments.expressions.isEmpty()) {
            if (expression.methodAsString in WHOLE_METHODS) return 'whole'
            if (expression.methodAsString in DECIMAL_METHODS) return 'decimal'
        }
        return null
    }

    @Test
    void everyTranslatedFormatCallHasEnoughArgumentsOfAPlausibleType() {
        def catalog = new Gson().fromJson(getClass().getResource('/i18n/script-core.json').getText('UTF-8'), Map)
        def rows = catalog.strings.collectEntries { row -> [(row.key): row] }
        int calls = 0, typed = 0
        def problems = []
        catalog.scripts.each { filename ->
            def script = getClass().getResource('/scripts/' + filename).getText('UTF-8')
            def unit = SourceUnit.create(filename as String, script)
            unit.parse(); unit.completePhase(); unit.convert()
            def visitor = new CodeVisitorSupport() {
                void visitMethodCallExpression(MethodCallExpression call) {
                    def args = call.arguments.expressions
                    if (call.methodAsString == 'format' && call.objectExpression.text == 'String' && args.size() >= 2
                            && args[1] instanceof MethodCallExpression && args[1].methodAsString == 'workshopText') {
                        def keyExpression = args[1].arguments.expressions[0]
                        def row = keyExpression instanceof ConstantExpression ? rows[keyExpression.value] : null
                        def values = args.drop(2)
                        if (row != null && !values.any { it instanceof SpreadExpression }) {
                            calls++
                            ['tr', 'en'].each { language ->
                                int next = 0
                                (row[language] =~ CONVERSION).each { match ->
                                    String conversion = match[2]
                                    if (conversion == 'n') return
                                    int index = match[1] ? (match[1] as int) - 1 : next++
                                    String where = filename + ':' + call.lineNumber + ' ' + row.key + ' ' + language + ' %' + conversion
                                    if (index >= values.size()) {
                                        problems << where + ' has no argument'
                                        return
                                    }
                                    String found = kind(values[index])
                                    if (found != null) typed++
                                    if (conversion in ['d', 'o', 'x', 'X'] && found in ['decimal', 'text'])
                                        problems << where + ' gets a ' + found + ' value: ' + values[index].text
                                    if (conversion in ['e', 'E', 'f', 'g', 'G'] && found in ['whole', 'text'])
                                        problems << where + ' gets a ' + found + ' value: ' + values[index].text
                                }
                            }
                        }
                    }
                    super.visitMethodCallExpression(call)
                }
            }
            unit.AST.statementBlock.visit(visitor)
            unit.AST.methods.each { it.code?.visit(visitor) }
            unit.AST.classes.each { cls -> cls.methods.each { it.code?.visit(visitor) } }
        }
        assertTrue(problems.join('\n'), problems.isEmpty())
        // Guard against the visitor silently finding nothing.
        assertTrue('format calls seen: ' + calls, calls >= 1000)
        assertTrue('arguments with a known type: ' + typed, typed >= 300)
    }
}
