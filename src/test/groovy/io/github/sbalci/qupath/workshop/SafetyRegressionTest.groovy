package io.github.sbalci.qupath.workshop

import groovy.test.GroovyTestCase
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs

/** Runs production closures, rather than copies, against QuPath 0.6.0 core objects.
 * Does not launch the GUI, Python models or inference processes.
 */
class SafetyRegressionTest extends GroovyTestCase {
    static final String ROOT = 'src/main/resources/scripts/'
    static final String HISTO = 'yardimci-histoplus-sihirbaz.groovy'
    static final String CYTO = 'yardimci-cytoformer-sihirbaz.groovy'
    static final String NUC = 'yardimci-nuclick-sihirbaz.groovy'

    static String source(String file) { new File(ROOT + file).getText('UTF-8') }

    // Top-level closures end at column zero; fail if production structure changes.
    static String closure(String file, String name) {
        String s = source(file)
        int start = s.indexOf('def ' + name + ' = {')
        assert start >= 0 : "Missing production closure: $name"
        int end = s.indexOf('\n}', start)
        assert end > start
        s.substring(start, end + 2) + '\n'
    }

    static def roi(double x = 0) { ROIs.createRectangleROI(x, 0, 100, 100, ImagePlane.getDefaultPlane()) }
    static def ann(String name, double x = 0) {
        def a = PathObjects.createAnnotationObject(roi(x)); a.setName(name); a
    }
    static def det(String cls, double x = 10) {
        PathObjects.createDetectionObject(ROIs.createRectangleROI(x, 10, 2, 2, ImagePlane.getDefaultPlane()),
            cls == null ? null : PathClass.fromString(cls))
    }

    static GroovyShell shell(Map vars = [:]) {
        new GroovyShell(SafetyRegressionTest.classLoader, new Binding(vars))
    }

    static String histoCode() {
        '''import qupath.lib.objects.PathObjects
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs
import com.google.gson.JsonParser
def SUMMARY_NAME = 'HistoPLUS hücre sınıfları özeti'
def CLASS_SUFFIX = ' (HistoPLUS)'
def MEAS_PREFIX = 'HistoPLUS: '
def RUNNER_VOTE = 'vote'
def VOTE_MEAS = 'HistoPLUS: sınıf oy oranı'
def CLASSES = []
def classByKey = [:]
def trOf = { it }
def QP = new Expando(getPathClass: { String n -> qupath.lib.objects.classes.PathClass.fromString(n) })
''' + ['summaryBaseOf', 'isSummaryOf', 'prevSummariesIn', 'prevDetectionsIn',
       'nextSummaryName', 'areaMm2Of', 'importCells', 'writeCounts'].collect { closure(HISTO, it) }.join('\n')
    }

    void testCytoPreservesForeignClassesAndMeasurements() {
        def foreign = det('Tumor: Manual')
        foreign.getMeasurements().put('Manual score', 42d)
        def own = det('Old (CytoFormer)')
        own.getMeasurements().put('Area', 9d)
        def unclassed = det(null)
        def cells = [foreign, own, unclassed]
        def result = shell([cells: cells]).evaluate('''
def CLASS_SUFFIX = ' (CytoFormer)'
def UNCERTAIN_CLASS = 'Belirsiz (CytoFormer)'
def MEAS_PREFIX = 'CytoFormer: '
def classByKey = [:]
def trOf = { it }
def QP = new Expando(getPathClass: { String n -> qupath.lib.objects.classes.PathClass.fromString(n) })
''' + ['isOwnClass', 'foreignClassed', 'keepableCells', 'applyAssignments'].collect { closure(CYTO, it) }.join('\n') + '''
assert foreignClassed(cells) == [cells[0]]
def keep = keepableCells(cells)
assert keep == cells[1..2]
applyAssignments(keep, [0: [key:'New', prob:0.9d], 1: [key:'New', prob:0.1d]], 0.5d)
''')
        assert foreign.getPathClass().toString() == 'Tumor: Manual'
        assert foreign.getMeasurements().get('Manual score') == 42d
        assert own.getPathClass().toString() == 'New (CytoFormer)'
        assert own.getMeasurements().get('Area') == 9d
        assert unclassed.getPathClass().toString() == 'Belirsiz (CytoFormer)'
    }

    void testCytoExplicitOverwriteAndMissingAssignments() {
        def cells = [det('Manual'), det('Keep')]
        shell([cells:cells]).evaluate('''
def CLASS_SUFFIX = ' (CytoFormer)'
def UNCERTAIN_CLASS = 'Belirsiz (CytoFormer)'
def MEAS_PREFIX = 'CytoFormer: '
def classByKey = [:]
def trOf = { it }
def QP = new Expando(getPathClass: { String n -> qupath.lib.objects.classes.PathClass.fromString(n) })
''' + closure(CYTO, 'applyAssignments') + "applyAssignments(cells, [0:[key:'New', prob:0.9d]], 0d)")
        assert cells[0].getPathClass().toString() == 'New (CytoFormer)'
        assert cells[1].getPathClass().toString() == 'Keep'
    }

    void testHistoExactNamesAndRegionScopedNumbering() {
        def h = new PathObjectHierarchy()
        String base = 'HistoPLUS hücre sınıfları özeti — model'
        def original = ann(base), numbered = ann(base + ' (3)'), renamed = ann(base + ' — notes')
        h.addObjects([original, numbered, renamed, ann(base + ' (99)', 200)])
        shell([h:h, region:roi(), base:base, original:original, numbered:numbered]).evaluate(histoCode() + '''
assert prevSummariesIn(h, region, base) as Set == [original, numbered] as Set
assert nextSummaryName(h, region, base) == base + ' (4)'
assert !isSummaryOf(null, base)
''')
    }

    File resultFile(String json) {
        def f = File.createTempFile('histoplus-test-', '.geojson'); f.deleteOnExit(); f.setText(json, 'UTF-8'); f
    }

    static String validResult() { '''{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[20,20],[24,20],[24,24],[20,24],[20,20]]]},"properties":{"classification":{"name":"new"}}}]}''' }

    void testHistoAddPreservesObjectsAndTargetMeasurements() {
        def h = new PathObjectHierarchy()
        def target = ann('User ROI'), old = det('Old (HistoPLUS)')
        target.getMeasurements().put('HistoPLUS: previous', 7d)
        target.getMeasurements().put('User score', 42d)
        h.addObjects([target, old])
        def image = new Expando(getHierarchy:{ h })
        shell([image:image, region:roi(), target:target, f:resultFile(validResult())]).evaluate(histoCode() + '''
def imp = importCells(f, image, region, null, false, 'summary')
assert imp.removed == 0
writeCounts(image, [target], region, imp.cells, null, 'model', false, 'summary')
''')
        assert h.getDetectionObjects().contains(old)
        assert h.getDetectionObjects().size() == 2
        assert target.getMeasurements().get('HistoPLUS: previous') == 7d
        assert target.getMeasurements().get('User score') == 42d
        def summary = h.getAnnotationObjects().find { it.getName() == 'summary' }
        assert summary.isLocked()
        assert summary.getMeasurements().get('HistoPLUS: toplam (n)') == 1d
    }

    void testHistoReplacementScopeAndChildPreservation() {
        def h = new PathObjectHierarchy()
        def summary = ann('summary'), renamed = ann('summary — notes')
        def old = det('Manual (HistoPLUS)'), other = det('Tumor'), outside = det('Old (HistoPLUS)', 210)
        def child = ann('User child')
        summary.addChildObject(child)
        h.addObjects([summary, renamed, old, other, outside])
        shell([image:new Expando(getHierarchy:{h}), region:roi(), f:resultFile(validResult())]).evaluate(histoCode() + '''
def imp = importCells(f, image, region, null, true, 'summary')
assert imp.removed == 1
assert imp.removedSummaries == 1
''')
        assert !h.getDetectionObjects().contains(old)
        assert h.getDetectionObjects().containsAll([other, outside])
        assert h.getAnnotationObjects().containsAll([renamed, child])
        assert !h.getAnnotationObjects().contains(summary)
    }

    void testHistoInvalidOrMissingResultsCannotDeleteExistingWork() {
        [null, resultFile('{broken'), resultFile('{}'), resultFile('{"features":[{"geometry":{"type":"Polygon","coordinates":[]},"properties":{"classification":{"name":"x"}}}]}')].each { f ->
            def h = new PathObjectHierarchy()
            def old = det('Old (HistoPLUS)'), summary = ann('summary')
            h.addObjects([old, summary])
            shouldFail(Exception) {
                shell([image:new Expando(getHierarchy:{h}), region:roi(), f:f]).evaluate(histoCode() + "importCells(f, image, region, null, true, 'summary')")
            }
            assert h.getDetectionObjects().contains(old)
            assert h.getAnnotationObjects().contains(summary)
        }
    }

    void testHistoExplicitEmptyResultIsValid() {
        def h = new PathObjectHierarchy(); def old = det('Old (HistoPLUS)'); h.addObject(old)
        shell([image:new Expando(getHierarchy:{h}), region:roi(), f:resultFile('{"features":[]}')]).evaluate(histoCode() + "importCells(f, image, region, null, true, 'summary')")
        assert h.getDetectionObjects().isEmpty()
    }

    void testNuClickExactSentinelMatching() {
        def h = new PathObjectHierarchy()
        def base = 'NuClick çekirdekleri'
        h.addObjects([ann(base), ann(base + ' (2)'), ann(base + ' (my corrections)')])
        shell([h:h, base:base]).evaluate(['isNuclickSentinel','findSentinels','nextSentinelName'].collect { closure(NUC,it) }.join('\n') + '''
assert findSentinels(h, base).size() == 2
assert nextSentinelName(h, base) == base + ' (3)'
''')
    }

    // Source-order contract only: interactive dialog/cancellation acceptance remains manual.
    void testConfirmationAndLateCancellationContracts() {
        [HISTO, CYTO, NUC].each { file ->
            def s = source(file)
            def start = s.indexOf('def startRun =')
            assert start >= 0
            def run = s.substring(start)
            assert run.indexOf('showChoiceDialog') < run.indexOf('workDir.mkdirs()')
            assert run.contains('if (pick == null) return')
            assert run.contains('if (!ok) return')
        }
        assert source(HISTO).contains("if (cancelledRef.get()) { javafx.application.Platform.runLater { step.set('READY'); render() }; return }\n            def imp = importCells")
        assert source(CYTO).contains("if (cancelledRef.get()) { step.set('READY'); render(); return }\n                    def applyStats = applyAssignments")
        assert source(NUC).contains("if (cancelledRef.get()) { step.set('READY'); render(); return }\n                def imp = importResults")
    }
}
