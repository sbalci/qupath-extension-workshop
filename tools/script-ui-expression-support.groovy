/* Test-only compiler/probe support. No image, project, file or GUI code is run. */
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.ast.expr.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ScriptUiExpressionSupport {
    static Map parse(String expression) {
        String source = 'return ' + expression + '\n'
        def unit = SourceUnit.create('UiExpression.groovy', source)
        unit.parse(); unit.completePhase(); unit.convert()
        def node = unit.AST.statementBlock.statements[0].expression
        assert node instanceof GStringExpression: 'Expected a complete GString expression'
        def offsets = [0]
        for (int i = 0; i < source.length(); i++) if (source.charAt(i) == '\n' as char) offsets << i + 1
        return [source:source, node:node, offsets:offsets]
    }

    static List range(Map context, node) {
        assert node.lineNumber > 0 && node.lastLineNumber > 0
        [context.offsets[node.lineNumber-1] + node.columnNumber-1,
         context.offsets[node.lastLineNumber-1] + node.lastColumnNumber-1]
    }

    static String sourceOf(Map context, node) {
        def bounds = range(context, node)
        context.source.substring(bounds[0] as int, bounds[1] as int)
    }

    static boolean lookup(node) {
        node instanceof MethodCallExpression && node.methodAsString == 'workshopText'
    }

    static Map lookupRow(node, Map rows) {
        def args = node.arguments.expressions
        assert args.size() == 2
        assert args[0] instanceof ConstantExpression && args[1] instanceof ConstantExpression
        def row = rows[args[0].value]
        assert row != null: 'Unknown translated literal'
        assert args[1].value == row.tr: 'Translation key and Turkish fallback differ'
        row
    }

    // A data slot is opaque. Only result arms of a ternary/Elvis expression may
    // contain approved UI-label lookups; condition code and method arguments stay exact.
    static void branchEdits(Map context, node, Map rows, List edits) {
        if (lookup(node)) {
            lookupRow(node, rows)
            def bounds = range(context, node)
            edits << [bounds[0], bounds[1], sourceOf(context, node.arguments.expressions[1])]
        } else if (node instanceof TernaryExpression) {
            branchEdits(context, node.trueExpression, rows, edits)
            branchEdits(context, node.falseExpression, rows, edits)
        }
    }

    static String normalisedValue(Map context, node, Map rows) {
        def edits = []
        if (node instanceof TernaryExpression) {
            branchEdits(context, node.trueExpression, rows, edits)
            branchEdits(context, node.falseExpression, rows, edits)
        }
        def bounds = range(context, node)
        String source = sourceOf(context, node)
        edits.sort { a,b -> b[0] <=> a[0] }.each { edit ->
            int first = (int)edit[0] - (int)bounds[0], last = (int)edit[1] - (int)bounds[0]
            source = source.substring(0,first) + edit[2] + source.substring(last)
        }
        source
    }

    static Map pattern(Map context, Map rows, String language) {
        def parts = [context.node.strings[0].value.toString()]
        def values = [], nodes = []
        context.node.values.eachWithIndex { node,index ->
            String next = context.node.strings[index+1].value.toString()
            if (lookup(node)) {
                parts[-1] = parts[-1] + lookupRow(node, rows)[language] + next
            } else {
                values << [node.class.name, normalisedValue(context,node,rows)]
                nodes << node
                parts << next
            }
        }
        [parts:parts, values:values, nodes:nodes]
    }

    static void verify(Map rewrite, Map rows) {
        def before = pattern(parse(rewrite.original as String),rows,'tr')
        def after = pattern(parse(rewrite.replacement as String),rows,'tr')
        assert before.parts == after.parts: 'Literal fallback or its position changed'
        assert before.values == after.values: 'Original data/value expression changed'
    }

    static String injected(Map context, Map rows) {
        def edits = []
        pattern(context,rows,'tr').nodes.eachWithIndex { node,index ->
            def bounds = range(context,node)
            edits << [bounds[0],bounds[1],'__uiSlots[' + index + ']']
        }
        String source = context.source
        edits.sort { a,b -> b[0] <=> a[0] }.each { edit ->
            source = source.substring(0,(int)edit[0]) + edit[2] + source.substring((int)edit[1])
        }
        source
    }

    static String expected(List parts, List labels) {
        def out = new StringBuilder(parts[0] as String)
        labels.eachWithIndex { label,index -> out.append(label).append(parts[index+1]) }
        out.toString()
    }

    static Map probe(Map catalog, Map sources, ClassLoader parent, Closure selectLanguage, List languages) {
        Map rows = catalog.strings.collectEntries { row -> [(row.key):row] }
        int expressions = 0, renders = 0, scopeChecks = 0
        new GroovyClassLoader(parent).withCloseable { loader ->
            catalog.source_contracts.each { filename,contract ->
                if (!contract.expressions) return
                String source = sources[filename]
                String helper = source.substring(source.lastIndexOf('private String workshopText('))
                contract.expressions.eachWithIndex { rewrite,index ->
                    verify(rewrite as Map,rows)
                    def context = parse(rewrite.replacement as String)
                    def scriptClass = loader.parseClass(helper + '\n' + injected(context,rows), 'UiProbe' + expressions + '.groovy')
                    int size = pattern(context,rows,'tr').values.size()
                    expressions++
                    languages.each { language ->
                        selectLanguage(language)
                        def parts = pattern(context,rows,language as String).parts
                        // Keep data inert: no source value expressions or clinical data run.
                        def labels = (0..<size).collect { slot -> '<synthetic-' + slot + ': 0.2600 µm/px / ç $ { } \' " %>' }
                        def calls = (0..<size).collect { new AtomicInteger() }
                        def lazy = (0..<size).collect { slot -> { -> calls[slot].incrementAndGet(); labels[slot] } }
                        def script = scriptClass.getConstructor().newInstance()
                        script.binding = new Binding([__uiSlots:lazy])
                        def result = script.run()
                        assert result instanceof GString
                        assert calls.every { it.get() == 0 }: 'Data was evaluated eagerly'
                        assert result.toString() == expected(parts,labels)
                        assert result.toString() == expected(parts,labels)
                        assert calls.every { it.get() == 2 }: 'Lazy coercion/evaluation count changed'
                        def data = (0..<size).collect { slot -> slot % 2 == 0 ? new BigDecimal('0.2600') : 'D:/synthetic/ç $sample (1).tif' }
                        script = scriptClass.getConstructor().newInstance()
                        script.binding = new Binding([__uiSlots:data])
                        result = script.run()
                        assert result instanceof GString
                        assert result.toString() == expected(parts,data.collect { it.toString() })
                        renders += 2
                    }
                    // Exercise the only localised data-slot labels with safe JDK/list bindings.
                    if (rewrite.original.contains('activeWholeSlide.get()') || rewrite.original.contains("known.join(', ')")) {
                        def actualClass = loader.parseClass(helper + '\nreturn ' + rewrite.replacement + '\n', 'UiScope' + expressions + '.groovy')
                        languages.each { language ->
                            selectLanguage(language)
                            [false,true].each { flag ->
                                def bindings
                                if (rewrite.original.contains('activeWholeSlide.get()')) {
                                    bindings = [activeModel:new AtomicReference('SYNTHETIC model α'),activeWholeSlide:new AtomicReference(flag)]
                                } else {
                                    bindings = [nm:'SYNTHETIC missing α',known:flag ? ['SYNTHETIC-A','synthetic-b'] : []]
                                }
                                def script = actualClass.getConstructor().newInstance(); script.binding = new Binding(bindings)
                                String result = script.run().toString()
                                if (bindings.activeModel) {
                                    assert result.contains('SYNTHETIC model α')
                                    assert result.contains(language == 'en' ? (flag ? 'across the whole slide' : 'in the selected region') : (flag ? 'tüm lamda' : 'seçili bölgede'))
                                } else {
                                    assert result.contains('SYNTHETIC missing α')
                                    assert result.contains(flag ? 'SYNTHETIC-A, synthetic-b' : (language == 'en' ? '(none)' : '(yok)'))
                                }
                                scopeChecks++
                            }
                        }
                    }
                }
            }
        }
        [expressions:expressions,renders:renders,scopeChecks:scopeChecks]
    }
}
