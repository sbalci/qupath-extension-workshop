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

    static String source(String file) {
        String jar = System.getenv('WORKSHOP_SCRIPT_JAR')
        if (!jar) return new File(ROOT + file).getText('UTF-8').replace('\r\n', '\n')
        new java.util.zip.ZipFile(jar).withCloseable { zip ->
            zip.getInputStream(zip.getEntry('scripts/' + file)).withCloseable { it.getText('UTF-8').replace('\r\n', '\n') }
        }
    }

    // Top-level closures end at column zero; fail if production structure changes.
    static String closure(String file, String name) {
        String s = source(file)
        int start = s.indexOf('def ' + name + ' = {')
        assert start >= 0 : "Missing production closure: $name"
        int lineEnd = s.indexOf('\n', start)
        if (s.substring(start, lineEnd).trim().endsWith('}'))
            return s.substring(start, lineEnd) + '\n'
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
            Exception rejected = null
            try {
                shell([image:new Expando(getHierarchy:{h}), region:roi(), f:f]).evaluate(histoCode() + "importCells(f, image, region, null, true, 'summary')")
            } catch (Exception e) { rejected = e }
            assert h.getDetectionObjects().contains(old) : 'Invalid output deleted previous detections'
            assert h.getAnnotationObjects().contains(summary) : 'Invalid output deleted previous summary'
            assert rejected != null : 'Invalid output was accepted'
        }
    }

    void testHistoSkippedInvalidFeaturesCannotBecomeEmptyReplacement() {
        ['{}', '{"geometry":{"type":"Point"}}',
         '{"geometry":{"type":"Polygon","coordinates":[[[1,1],[2,2],[1,1]]]},"properties":{"classification":{"name":"x"}}}',
         '{"geometry":{"type":"Polygon","coordinates":[[[1,1],[2,2],[3,3],[1,1]]]},"properties":{"classification":{"name":"x"}}}',
         '{"geometry":{"type":"Polygon","coordinates":[[[20,20],[24,20],[24,24],[20,20]]]}}'].each { feature ->
            def h = new PathObjectHierarchy()
            def old = det('Old (HistoPLUS)'), summary = ann('summary')
            h.addObjects([old, summary])
            def f = resultFile('{"features":[' + feature + ']}')
            Exception rejected = null
            try {
                shell([image:new Expando(getHierarchy:{h}), region:roi(), f:f]).evaluate(histoCode() + "importCells(f, image, region, null, true, 'summary')")
            } catch (Exception e) { rejected = e }
            assert h.getDetectionObjects().contains(old) : 'Invalid output deleted previous detections'
            assert h.getAnnotationObjects().contains(summary) : 'Invalid output deleted previous summary'
            assert rejected != null : 'Invalid output was accepted'
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

    static final String GRANDQC = 'yardimci-grandqc-sihirbaz.groovy'

    static String ownSetCode(String file) {
        ['isOwnSetName', 'ownSetIndex', 'prevOwnObjects', 'nextOwnSetName'].collect { closure(file, it) }.join('\n')
    }

    // Add -> add -> add numbering; a user-renamed annotation is never part of "our" sets.
    void testGrandQcOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def base = 'GrandQC KK'
        shell([h: h, base: base]).evaluate(ownSetCode(GRANDQC) + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
''')
        def mine = ann(base + ' düzeltmelerim')
        h.addObjects([ann(base), ann(base, 200), mine, ann(base + ' (1)'), ann(base + ' (x)'), ann('Başka')])
        shell([h: h, base: base]).evaluate(ownSetCode(GRANDQC) + '''
assert prevOwnObjects(h, base).size() == 2
assert nextOwnSetName(h, base) == base + ' (2)'
assert !isOwnSetName(base + ' düzeltmelerim', base)
assert !isOwnSetName(base + ' (1)', base)
assert !isOwnSetName(base + ' (x)', base)
assert !isOwnSetName(null, base)
''')
        h.addObjects([ann(base + ' (2)'), ann(base + ' (5)')])
        shell([h: h, base: base]).evaluate(ownSetCode(GRANDQC) + '''
assert prevOwnObjects(h, base).size() == 4
assert nextOwnSetName(h, base) == base + ' (6)'
''')
        assert h.getAnnotationObjects().contains(mine)
    }

    // Source contract: nothing is removed outside the explicit replace branch, the
    // question precedes the Python run, and Cancel returns before any work.
    void testGrandQcAsksBeforeRemoving() {
        String s = source(GRANDQC)
        assert s.count('removeObjects(') == 1
        int rep = s.indexOf("if (mode == 'replace') {")
        int rem = s.indexOf('hierarchy.removeObjects(prev, true)')
        assert rep >= 0 && rem > rep && rem - rep < 200
        assert s.contains("def importGeoJSON = { File geojsonFile, hierarchy, String mode = 'add' ->")
        int direct = s.indexOf('def startDirectRun = {')
        int ask = s.indexOf('askExisting(', direct)
        assert ask > direct && ask < s.indexOf('def cfg = loadConfig()', direct)
        assert s.contains('if (importMode == null) return')
        String askSrc = closure(GRANDQC, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        assert !s.contains('runImportClean(slide, effectiveFolder(slide)) }')
        assert !s.contains('runBatchImport(manualFolderRef.get())')
    }

    // ---- yardimci-warpy-sihirbaz.groovy: ask before replacing an earlier transfer ----

    // Add -> add -> add numbering; a user-renamed annotation is never part of "our" sets.
    void testWarpyOwnSetNamingNeverMatchesByPrefix() {
        final String WARPY = 'yardimci-warpy-sihirbaz.groovy'
        def h = new PathObjectHierarchy()
        def base = '↪ Warpy aktarımı'
        shell([h: h, base: base]).evaluate(ownSetCode(WARPY) + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
''')
        def mine = ann(base + ' düzeltmelerim')
        def users = ann('Tümör')
        h.addObjects([ann(base), ann(base, 200), mine, users, ann(base + ' (1)'), ann(base + ' (x)'), ann('Başka')])
        shell([h: h, base: base]).evaluate(ownSetCode(WARPY) + '''
assert prevOwnObjects(h, base).size() == 2
assert nextOwnSetName(h, base) == base + ' (2)'
assert !isOwnSetName(base + ' düzeltmelerim', base)
assert !isOwnSetName('Tümör', base)
assert !isOwnSetName(base + ' (1)', base)
assert !isOwnSetName(base + ' (x)', base)
assert !isOwnSetName(null, base)
''')
        h.addObjects([ann(base + ' (2)'), ann(base + ' (5)')])
        shell([h: h, base: base]).evaluate(ownSetCode(WARPY) + '''
assert prevOwnObjects(h, base).size() == 4
assert nextOwnSetName(h, base) == base + ' (6)'
''')
        assert h.getAnnotationObjects().contains(mine)
        assert h.getAnnotationObjects().contains(users)
    }

    // Source contract: removal only inside the explicit replace branch (children kept),
    // the question precedes the transfer worker, Cancel returns before any work,
    // and the wizard still refuses to run without a GUI (writes nothing).
    void testWarpyAsksBeforeRemoving() {
        final String WARPY = 'yardimci-warpy-sihirbaz.groovy'
        String s = source(WARPY)
        assert s.count('removeObjects(') == 1
        int rep = s.indexOf("if (mode == 'replace') {")
        int rem = s.indexOf('QP.removeObjects(ownTransferTree(prev), true)')
        assert rep >= 0 && rem > rep && rem - rep < 200
        assert !s.contains('findAll { it.getName() == SENTINEL }')
        assert s.contains("boolean lock, String mode = 'add' ->")
        // Question is asked on the button handler, before the worker thread and before doTransfer.
        int handler = s.indexOf('aktarBtn.setOnAction({')
        int ask = s.indexOf('askExisting()', handler)
        int worker = s.indexOf('def worker = new Thread({', handler)
        int disable = s.indexOf('aktarBtn.setDisable(true)', handler)
        assert ask > handler && ask < disable && ask < worker
        assert s.indexOf('if (transferMode == null) return', ask) in (ask..disable)
        assert s.contains('lockChk.isSelected(), transferMode)')
        String askSrc = closure(WARPY, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        assert askSrc.contains('showChoiceDialog')
        assert askSrc.contains('showConfirmDialog')
        assert askSrc.contains('[optAdd, optRep], optAdd)')
        // Headless: the wizard needs the GUI and returns before any transfer code is defined.
        int headless = s.indexOf('if (isHeadless) {')
        assert headless >= 0 && headless < s.indexOf('def doTransfer = {')
        assert s.substring(headless, s.indexOf('def doTransfer = {')).contains('return')
        // Add is the default and the result text names the earlier set.
        assert s.contains('r.prevNames')
        assert s.contains('(mode == \'replace\' ? [] : prevNames)')
    }

    // ---- yardimci-hizalama-aktarim.groovy: ask before replacing an earlier transfer ----

    // Add -> add -> add numbering; a user-renamed annotation is never part of "our" sets.
    void testAffineTransferOwnSetNamingNeverMatchesByPrefix() {
        final String AFFINE = 'yardimci-hizalama-aktarim.groovy'
        def h = new PathObjectHierarchy()
        def base = '↪ Hizalama aktarımı'
        shell([h: h, base: base]).evaluate(ownSetCode(AFFINE) + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
''')
        def mine = ann(base + ' düzeltmelerim')
        def users = ann('Tümör')
        h.addObjects([ann(base), ann(base, 200), mine, users, ann(base + ' (1)'), ann(base + ' (x)'), ann('Başka')])
        shell([h: h, base: base]).evaluate(ownSetCode(AFFINE) + '''
assert prevOwnObjects(h, base).size() == 2
assert nextOwnSetName(h, base) == base + ' (2)'
assert !isOwnSetName(base + ' düzeltmelerim', base)
assert !isOwnSetName('Tümör', base)
assert !isOwnSetName(base + ' (1)', base)
assert !isOwnSetName(base + ' (x)', base)
assert !isOwnSetName(null, base)
''')
        h.addObjects([ann(base + ' (2)'), ann(base + ' (5)')])
        shell([h: h, base: base]).evaluate(ownSetCode(AFFINE) + '''
assert prevOwnObjects(h, base).size() == 4
assert nextOwnSetName(h, base) == base + ' (6)'
''')
        assert h.getAnnotationObjects().contains(mine)
        assert h.getAnnotationObjects().contains(users)
    }

    // Source contract: removal only inside the explicit replace branch (children kept), the
    // question precedes BOTH transfer paths (the automatic one opens the source image and
    // runs the aligner), Cancel returns before any work, and headless refuses to run.
    void testAffineTransferAsksBeforeRemoving() {
        final String AFFINE = 'yardimci-hizalama-aktarim.groovy'
        String s = source(AFFINE)
        assert s.count('removeObjects(') == 1
        int rep = s.indexOf("if (mode == 'replace') {")
        int rem = s.indexOf('QP.removeObjects(ownTransferTree(prev), true)')
        assert rep >= 0 && rem > rep && rem - rep < 200
        assert !s.contains('findAll { it.getName() == SENTINEL }')
        assert s.contains("boolean copyMeas, boolean lock, String mode = 'add' ->")
        assert s.count('applyTransferred(srcAnns,') == 2
        assert s.count(', mode)') >= 2
        // Auto path: ask before the worker that opens the source image and aligns.
        int auto = s.indexOf('autoBtn.setOnAction({')
        int autoAsk = s.indexOf('askExisting()', auto)
        int autoWorker = s.indexOf('def worker = new Thread({', auto)
        int autoDisable = s.indexOf('autoBtn.setDisable(true)', auto)
        assert autoAsk > auto && autoAsk < autoDisable && autoAsk < autoWorker
        assert s.indexOf('if (transferMode == null) return', autoAsk) < autoDisable
        assert s.contains('lockChk.isSelected(), transferMode)')
        // Manual path: same order.
        int man = s.indexOf('manualBtn.setOnAction({')
        int manAsk = s.indexOf('askExisting()', man)
        int manWorker = s.indexOf('def worker = new Thread({', man)
        int manDisable = s.indexOf('autoBtn.setDisable(true)', man)
        assert manAsk > man && manAsk < manDisable && manAsk < manWorker
        assert s.indexOf('if (transferMode == null) return', manAsk) < manDisable
        assert s.count('askExisting()') == 2
        String askSrc = closure(AFFINE, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        assert askSrc.contains('showChoiceDialog')
        assert askSrc.contains('showConfirmDialog')
        assert askSrc.contains('[optAdd, optRep], optAdd)')
        // Headless: returns before any transfer code is defined, so nothing is written.
        int headless = s.indexOf('if (isHeadless) {')
        assert headless >= 0 && headless < s.indexOf('def applyTransferred = {')
        assert s.substring(headless, s.indexOf('def applyTransferred = {')).contains('return')
        assert s.contains('r.prevNames')
        assert s.contains('(mode == \'replace\' ? [] : prevNames)')
    }

    static final String VALIS = 'yardimci-valis-sihirbaz.groovy'

    // Add -> add -> add numbering; a user-renamed annotation is never part of "our" sets.
    void testValisOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def base = 'VALIS'
        shell([h: h, base: base]).evaluate(ownSetCode(VALIS) + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
''')
        def mine = ann(base + ' notlarım')
        h.addObjects([ann(base), ann(base, 200), mine, ann(base + ' (1)'), ann(base + ' (x)'), ann('Başka')])
        shell([h: h, base: base]).evaluate(ownSetCode(VALIS) + '''
assert prevOwnObjects(h, base).size() == 2
assert nextOwnSetName(h, base) == base + ' (2)'
assert !isOwnSetName(base + ' notlarım', base)
assert !isOwnSetName(base + ' (1)', base)
assert !isOwnSetName(base + ' (x)', base)
assert !isOwnSetName(null, base)
''')
        h.addObjects([ann(base + ' (2)'), ann(base + ' (5)')])
        shell([h: h, base: base]).evaluate(ownSetCode(VALIS) + '''
assert prevOwnObjects(h, base).size() == 4
assert nextOwnSetName(h, base) == base + ' (6)'
''')
        assert h.getAnnotationObjects().contains(mine)
    }

    // Source contract: both import sites remove only inside the explicit replace branch (children kept),
    // the question precedes the warp run, Cancel returns before any work, headless fails closed.
    void testValisAsksBeforeRemoving() {
        String s = source(VALIS)
        assert s.count('removeObjects(') == 2
        int from = 0
        2.times {
            int rep = s.indexOf("if (mode == 'replace') {", from)
            int rem = s.indexOf('hier.removeObjects(ownTransferTree(prevOwn), true)', rep)
            assert rep >= 0 && rem > rep && rem - rep < 200
            from = rem
        }
        assert !s.contains('removeObjects(hier.getAnnotationObjects()')
        assert s.contains("def importWarpedToTarget = { project, targetEntry, File warpedFile, double srcAreaUm2 = 0.0d, String mode = 'add' ->")
        assert s.contains("def importGeojsonToCurrent = { File gjFile, String mode = 'add' ->")
        assert s.contains('importWarpedToTarget(project, p.entry, gj, srcAreaUm2, importMode)')
        // Quick warp: the question comes before prepareRun's command is started (Python run).
        int quick = s.indexOf('def doWarpOnlyTransfer = {')
        int ask = s.indexOf('askExisting(', quick)
        assert ask > quick && ask < s.indexOf('startRun(cmd,', quick)
        assert s.indexOf('if (warpMode == null) return', quick) > ask
        assert s.contains('doImportWarped(warpMode)')
        // Manual file import asks after the file is chosen and before anything is written.
        int file = s.indexOf('def doImportGeojsonFromFile = {')
        int fileAsk = s.indexOf('askExisting(', file)
        assert fileAsk > file && fileAsk < s.indexOf('importGeojsonToCurrent(gj, fileMode)', file)
        assert s.contains('if (fileMode == null) return')
        // Import button: Cancel stops the whole chain.
        assert s.contains("if (doImportWarped() != 'cancel') { doAddOme(); doAddMergeOut() }")
        assert s.contains("if (importMode == null) return 'cancel'")
        String askSrc = closure(VALIS, 'askExisting')
        assert askSrc.contains('if (prevCount <= 0) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        assert askSrc.contains('Dialogs.showChoiceDialog(')
        assert askSrc.contains('Dialogs.showConfirmDialog(')
    }

    // askExisting itself: no earlier result -> no dialog; cancel, add and replace-with-confirm paths.
    void testValisAskExistingDecisions() {
        def run = { Map dlg, int n ->
            def calls = []
            def Dialogs = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            def r = shell([Dialogs: Dialogs, workshopText: { Object... a -> a[0] }]).evaluate('''
def MODE_ADD = 'add'
def MODE_REPLACE = 'replace'
''' + closure(VALIS, 'askExisting').replaceAll(/@WT:\w+@/, "'x'") + '''
askExisting(''' + n + ''', 1)''')
            [r, calls]
        }
        assert run([pick: 'add'], 0) == ['add', []]
        assert run([pick: null], 3) == [null, ['choice']]
        assert run([pick: 'add'], 3) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], 3) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], 3) == [null, ['choice', 'confirm']]
    }

    // Headless (no window): the wizard stops before anything is written; unknown `mevcut` fails closed.
    void testValisHeadlessFailsClosed() {
        String s = source(VALIS)
        int head = s.indexOf('if (isHeadless) {')
        assert head >= 0
        int end = s.indexOf('// ── Durum makinesi', head)
        String block = s.substring(head, end)
        assert block.contains("['ekle', 'degistir'].contains(")
        assert block.indexOf('headlessMap.mevcut') < block.lastIndexOf('return')
        assert !block.contains('removeObjects') && !block.contains('addObjects') && !block.contains('importWarpedToTarget')
        // Nothing that writes to a hierarchy is reachable before the headless return.
        String before = s.substring(0, head)
        assert before.count('hier.addObjects(') == 1   // only inside a closure defined (not invoked) above the guard
    }

    static final String KONGNET = 'yardimci-mitoz-kongnet.groovy'

    // Closure set used by the KongNet import (naming closures copied verbatim from the GrandQC wizard).
    static String kongNetCode() {
        '''String summaryName = 'KongNet Mitoz Özet'
String generatedName = 'Generated by KongNet import'
String metaSource = 'atolye.mitoz.kaynak'
String metaPoint = 'atolye.mitoz.nokta'
''' + ownSetCode(KONGNET) + '\n' +
            ['prevOwnPoints', 'prevOwnSummaries', 'ringsOfPoints'].collect { closure(KONGNET, it) }.join('\n')
    }

    // "This script's result" = exact set name or "name (N)", N >= 2, never a prefix; own points are matched
    // by the KongNet source tag inside the selected area. Replace (as the script performs it) removes only
    // those points, their linked rings and own summaries, and keeps everything else.
    void testKongNetOwnResultNeverMatchesByPrefixOrForeign() {
        String sumBase = 'KongNet Mitoz Özet'
        String gen = 'Generated by KongNet import'
        def h = new PathObjectHierarchy()
        def area = roi(0)
        def ownPt = det('Mitosis', 10)
        ownPt.getMetadata().put('atolye.mitoz.kaynak', gen)
        def legacyPt = det('Mitosis', 20)
        legacyPt.setName(gen)
        def outsidePt = det('Mitosis', 500)
        outsidePt.getMetadata().put('atolye.mitoz.kaynak', gen)
        def otherModelPt = det('Mitosis', 30)
        otherModelPt.getMetadata().put('atolye.mitoz.kaynak', 'KongNet MIDOG mitoz')
        def userPt = det('Mitosis', 40)
        def ownRing = ann('KongNet mitoz #1', 0)
        ownRing.getMetadata().put('atolye.mitoz.nokta', ownPt.getID().toString())
        def otherRing = ann('KongNet MIDOG mitoz #1', 0)
        otherRing.getMetadata().put('atolye.mitoz.nokta', otherModelPt.getID().toString())
        def orphanRing = ann('Yetim halka', 0)
        orphanRing.getMetadata().put('atolye.mitoz.nokta', UUID.randomUUID().toString())
        def sum1 = ann(sumBase, 0)
        def sum2 = ann(sumBase + ' (2)', 0)
        def farSum = ann(sumBase + ' (3)', 5000)
        def renamed = ann(sumBase + ' notlarım', 0)
        def lookalike = ann(sumBase + ' (1)', 0)
        def userAnn = ann('Benim alanım', 0)
        h.addObjects([sum1, sum2, farSum, renamed, lookalike, userAnn, ownRing, otherRing, orphanRing,
                      ownPt, legacyPt, outsidePt, otherModelPt, userPt])
        def vars = [h: h, area: area, ownPt: ownPt, legacyPt: legacyPt, outsidePt: outsidePt, otherModelPt: otherModelPt,
                    userPt: userPt, ownRing: ownRing, otherRing: otherRing, orphanRing: orphanRing, sum1: sum1, sum2: sum2,
                    farSum: farSum, renamed: renamed, lookalike: lookalike, userAnn: userAnn]
        shell(vars).evaluate(kongNetCode() + '''
assert isOwnSetName(summaryName, summaryName)
assert isOwnSetName(summaryName + ' (2)', summaryName)
assert !isOwnSetName(summaryName + ' notlarım', summaryName)
assert !isOwnSetName(summaryName + ' (1)', summaryName)
assert !isOwnSetName(summaryName + ' (x)', summaryName)
assert !isOwnSetName(null, summaryName)
assert nextOwnSetName(h, summaryName) == summaryName + ' (4)'
assert nextOwnSetName(new qupath.lib.objects.hierarchy.PathObjectHierarchy(), summaryName) == summaryName

def pts = prevOwnPoints(h, area)
assert pts.size() == 2 && pts.contains(ownPt) && pts.contains(legacyPt)
assert !pts.contains(outsidePt) && !pts.contains(otherModelPt) && !pts.contains(userPt)
assert prevOwnPoints(null, area).isEmpty()
def sums = prevOwnSummaries(h, area)
assert sums.size() == 2 && sums.contains(sum1) && sums.contains(sum2)
assert !sums.contains(farSum) && !sums.contains(renamed) && !sums.contains(lookalike)
def rings = ringsOfPoints(h, pts)
assert rings == [ownRing]
assert !rings.contains(otherRing) && !rings.contains(orphanRing)

// Replace exactly as the script does it: own rings + own points + own summaries, children kept.
h.removeObjects(rings + pts + sums, true)
assert prevOwnPoints(h, area).isEmpty()
assert prevOwnSummaries(h, area).isEmpty()
assert nextOwnSetName(h, summaryName) == summaryName + ' (4)'
''')
        def left = h.getAnnotationObjects() + h.getDetectionObjects()
        [farSum, renamed, lookalike, userAnn, otherRing, orphanRing, outsidePt, otherModelPt, userPt].each {
            assert left.contains(it) : "must survive replace: ${it.getName()}"
        }
        assert !left.contains(ownPt) && !left.contains(legacyPt) && !left.contains(ownRing)
        assert !left.contains(sum1) && !left.contains(sum2)
    }

    // Source contract: one removal, only in the replace branch with keepChildren; the question comes after the
    // headless exit and before the file chooser; Cancel returns before anything is written; the old silent
    // sweeps (all points on the slide, summary by exact name, orphan sweep) are gone.
    void testKongNetAsksBeforeRemoving() {
        String s = source(KONGNET)
        assert s.count('removeObjects(') == 1
        int rep = s.indexOf('if (mode == MODE_REPLACE && hadPrev) {')
        int rem = s.indexOf('QP.removeObjects(prevRings + prevPts + prevSums, true)')
        assert rep >= 0 && rem > rep && rem - rep < 300
        int headless = s.indexOf('if (isHeadless) {\n    println "GeoJSON')
        int ask = s.indexOf('String mode = askExisting(')
        int chooser = s.indexOf('FileChoosers.promptForFile')
        assert headless >= 0 && ask > headless && ask < chooser
        assert s.contains('if (mode == null) {')
        assert ask < s.indexOf('if (mode == null) {') && s.indexOf('if (mode == null) {') < chooser
        assert !s.contains('liveIds')
        assert !s.contains('it.getName() == summaryName }')
        assert !s.contains('link != null && !liveIds')
        assert s.contains('!isOwnSetName(it.getName(), summaryName)')
        assert s.contains('summary.setName(setName)')
        String askSrc = closure(KONGNET, 'askExisting')
        assert askSrc.contains('if (nPts == 0 && nSum == 0) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        assert askSrc.contains('[optAdd, optRep], optAdd)')
        assert s.contains('boolean writeTargetMeasurements = !(hadPrev && mode == MODE_ADD)')
    }

    static final String MITOSIS_COMPARE = 'yardimci-mitoz-karsilastir.groovy'

    static String mitosisCompareCode() {
        '''String SUMMARY_NAME = 'Mitoz — model karşılaştırması'
String META_SOURCE = 'atolye.mitoz.kaynak'
String META_POINT = 'atolye.mitoz.nokta'
''' + ownSetCode(MITOSIS_COMPARE) + '\n' +
            ['prevOwnModelPoints', 'prevOwnSummaries', 'ringsOfPoints'].collect { closure(MITOSIS_COMPARE, it) }.join('\n')
    }

    static def cmpPoint(String cls, double x, String source) {
        def a = PathObjects.createAnnotationObject(
            ROIs.createPointsROI(x, 50, ImagePlane.getDefaultPlane()), cls == null ? null : PathClass.fromString(cls))
        if (source != null) a.getMetadata().put('atolye.mitoz.kaynak', source)
        a
    }

    // Own result = this wizard's class AND source tag inside the selected region (per model), or a summary named
    // exactly "name" / "name (N)". Never a prefix; never another wizard's "Mitosis" points or a user's hand-made
    // point that merely uses the same class. Replace (as the script performs it) removes only those + linked rings.
    void testMitosisCompareOwnResultNeverMatchesByPrefixOrForeign() {
        String kong = 'Mitoz — KongNet'
        String fcos = 'Mitoz — FCOS'
        String cons = 'Mitoz (konsensüs)'
        String sumBase = 'Mitoz — model karşılaştırması'
        def h = new PathObjectHierarchy()
        def region = roi(0)
        def kongPt = cmpPoint(kong, 10, kong)
        def kongHand = cmpPoint(kong, 20, null)             // same class, no source tag: user-made or reclassified
        def kongOutside = cmpPoint(kong, 500, kong)
        def fcosPt = cmpPoint(fcos, 30, fcos)
        def consPt = cmpPoint(cons, 40, cons)
        def siblingPt = cmpPoint('Mitosis', 50, 'KongNet MIDOG mitoz')
        def kongRing = ann('Mitoz — KongNet #1', 0)
        kongRing.getMetadata().put('atolye.mitoz.nokta', kongPt.getID().toString())
        def fcosRing = ann('Mitoz — FCOS #1', 0)
        fcosRing.getMetadata().put('atolye.mitoz.nokta', fcosPt.getID().toString())
        def orphanRing = ann('Yetim halka', 0)
        orphanRing.getMetadata().put('atolye.mitoz.nokta', UUID.randomUUID().toString())
        def sum1 = ann(sumBase, 0)
        def sum2 = ann(sumBase + ' (2)', 0)
        def farSum = ann(sumBase + ' (3)', 5000)
        def renamed = ann(sumBase + ' kendi notlarım', 0)
        def lookalike = ann(sumBase + ' (1)', 0)
        def userAnn = ann('Benim alanım', 0)
        h.addObjects([sum1, sum2, farSum, renamed, lookalike, userAnn, kongRing, fcosRing, orphanRing,
                      kongPt, kongHand, kongOutside, fcosPt, consPt, siblingPt])
        def vars = [h: h, region: [region], kong: kong, fcos: fcos, cons: cons, kongPt: kongPt, kongHand: kongHand,
                    kongOutside: kongOutside, fcosPt: fcosPt, consPt: consPt, siblingPt: siblingPt, kongRing: kongRing,
                    fcosRing: fcosRing, orphanRing: orphanRing, sum1: sum1, sum2: sum2, farSum: farSum,
                    renamed: renamed, lookalike: lookalike, userAnn: userAnn]
        shell(vars).evaluate(mitosisCompareCode() + '''
assert isOwnSetName(SUMMARY_NAME, SUMMARY_NAME)
assert isOwnSetName(SUMMARY_NAME + ' (2)', SUMMARY_NAME)
assert !isOwnSetName(SUMMARY_NAME + ' kendi notlarım', SUMMARY_NAME)
assert !isOwnSetName(SUMMARY_NAME + ' (1)', SUMMARY_NAME)
assert !isOwnSetName(null, SUMMARY_NAME)
assert nextOwnSetName(h, SUMMARY_NAME) == SUMMARY_NAME + ' (4)'

assert prevOwnModelPoints(h, kong, region) == [kongPt]
assert prevOwnModelPoints(h, fcos, region) == [fcosPt]
assert prevOwnModelPoints(h, cons, region) == [consPt]
assert prevOwnModelPoints(h, 'Mitosis', region).isEmpty()
assert prevOwnModelPoints(null, kong, region).isEmpty()
def sums = prevOwnSummaries(h, region)
assert sums.size() == 2 && sums.contains(sum1) && sums.contains(sum2)
assert !sums.contains(farSum) && !sums.contains(renamed) && !sums.contains(lookalike)
def kRings = ringsOfPoints(h, [kongPt])
assert kRings == [kongRing]

// Replace for the KongNet model only (as importModelPoints does): its own point + linked ring.
h.removeObjects(kRings + [kongPt], true)
assert prevOwnModelPoints(h, kong, region).isEmpty()
assert prevOwnModelPoints(h, fcos, region) == [fcosPt]
// Replace of consensus + summaries (as the result block does).
def consRings = ringsOfPoints(h, prevOwnModelPoints(h, cons, region))
h.removeObjects(consRings + prevOwnModelPoints(h, cons, region) + prevOwnSummaries(h, region), true)
assert prevOwnSummaries(h, region).isEmpty() && prevOwnModelPoints(h, cons, region).isEmpty()
''')
        def left = h.getAnnotationObjects() + h.getDetectionObjects()
        [kongHand, kongOutside, fcosPt, fcosRing, orphanRing, siblingPt, farSum, renamed, lookalike, userAnn].each {
            assert left.contains(it) : "must survive replace: ${it.getName()}"
        }
        assert !left.contains(kongPt) && !left.contains(kongRing) && !left.contains(consPt)
        assert !left.contains(sum1) && !left.contains(sum2)
    }

    // Source contract: both removals sit in explicit replace branches (keepChildren = true); one single question
    // covers the whole run and precedes the export/Python work; Cancel returns; no prefix sweep or orphan sweep.
    void testMitosisCompareAsksOnceBeforeRunning() {
        String s = source(MITOSIS_COMPARE)
        assert s.count('removeObjects(') == 2
        int rep1 = s.indexOf("if (mode == 'replace' && !prevPts.isEmpty()) {")
        int rem1 = s.indexOf('hier.removeObjects(prevRings + prevPts, true)')
        assert rep1 >= 0 && rem1 > rep1 && rem1 - rep1 < 300
        int rep2 = s.indexOf('if (importMode == MODE_REPLACE) {')
        int rem2 = s.indexOf('hier.removeObjects(prevConsRings + prevCons + prevSums, true)')
        assert rep2 >= 0 && rem2 > rep2 && rem2 - rep2 < 400
        assert s.count('askExisting(') == 1
        int start = s.indexOf('def startRun = {')
        int ask = s.indexOf('askExisting(', start)
        assert ask > start
        assert ask < s.indexOf('workDir.mkdirs()', start)
        assert ask < s.indexOf('exportRegionMask(', start)
        assert s.indexOf('if (importMode == null) return', ask) > ask
        assert s.indexOf('if (importMode == null) return', ask) < s.indexOf('workDir.mkdirs()', start)
        assert !s.contains('startsWith(SUMMARY_NAME) }, false')
        assert !s.contains('removeOrphanRings')
        assert s.contains('summary.setName(setName)')
        String askSrc = closure(MITOSIS_COMPARE, 'askExisting')
        assert askSrc.contains('if (modelLines.isEmpty() && nCons == 0 && nSum == 0) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        assert askSrc.contains('[optAdd, optRep], optAdd)')
        // Headless branch of this wizard stops before any write and never reaches startRun.
        int headless = s.indexOf('if (isHeadless) {\n    def imageData')
        assert headless >= 0 && headless > s.indexOf('def importModelPoints') && s.indexOf('return', headless) < s.indexOf('def stage = null')
    }

    static final String TIA_REGION = 'yardimci-tiatoolbox-bolge-sihirbaz.groovy'

    // Own-result matching of the TIA region wizard: exact name pattern + point annotation + class
    // and point count agree with the name. Never a prefix match; user objects are never "ours".
    void testTiaRegionOwnSetNamingNeverMatchesByPrefix() {
        def prefix = 'TIA bölge tespiti · '
        def plane = ImagePlane.getDefaultPlane()
        def pts = { String cls, int n, String name ->
            def a = PathObjects.createAnnotationObject(
                ROIs.createPointsROI((1..n).collect { new qupath.lib.geom.Point2(it, it) }, plane),
                PathClass.fromString(cls))
            a.setName(name)
            a
        }
        String code = ['ownPointSetIndex', 'prevOwnPointSets', 'nextPointSetIndex', 'pointSetName']
            .collect { closure(TIA_REGION, it) }.join('\n')
        def h = new PathObjectHierarchy()
        shell([h: h, SENTINEL_PREFIX: prefix]).evaluate(code + '''
assert nextPointSetIndex(h) == 1
assert prevOwnPointSets(null).isEmpty()
assert pointSetName('Mitotic figure', 3, 1) == SENTINEL_PREFIX + 'Mitotic figure (3)'
assert pointSetName('Mitotic figure', 3, 2) == SENTINEL_PREFIX + 'Mitotic figure (3) (2)'
''')
        def mine1 = pts('Mitotic figure', 3, prefix + 'Mitotic figure (3)')
        def mine2 = pts('Mitotic figure', 3, prefix + 'Mitotic figure (3) (2)')
        def renamed = pts('Mitotic figure', 3, prefix + 'Mitotic figure (3) notlarım')
        def noCount = pts('Mitotic figure', 3, prefix + 'notlarım')
        def wrongCount = pts('Mitotic figure', 3, prefix + 'Mitotic figure (5)')
        def wrongClass = pts('Lymphocyte', 3, prefix + 'Mitotic figure (3)')
        def idxOne = pts('Mitotic figure', 3, prefix + 'Mitotic figure (3) (1)')
        def idxText = pts('Mitotic figure', 3, prefix + 'Mitotic figure (3) (x)')
        def noPrefix = pts('Mitotic figure', 3, 'Mitotic figure (3)')
        def rectangle = ann(prefix + 'Mitotic figure (3)')
        h.addObjects([mine1, mine2, renamed, noCount, wrongCount, wrongClass, idxOne, idxText, noPrefix, rectangle, ann('Başka')])
        shell([h: h, SENTINEL_PREFIX: prefix, mine1: mine1, mine2: mine2]).evaluate(code + '''
def prev = prevOwnPointSets(h)
assert prev.size() == 2
assert prev.contains(mine1) && prev.contains(mine2)
assert ownPointSetIndex(mine1) == 1
assert ownPointSetIndex(mine2) == 2
assert nextPointSetIndex(h) == 3
assert !h.getAnnotationObjects().findAll { it != mine1 && it != mine2 }.any { ownPointSetIndex(it) > 0 }
assert ownPointSetIndex(null) == 0
''')
        h.addObjects([pts('Lymphocyte', 2, prefix + 'Lymphocyte (2) (7)')])
        shell([h: h, SENTINEL_PREFIX: prefix]).evaluate(code + '''
assert prevOwnPointSets(h).size() == 3
assert nextPointSetIndex(h) == 8
''')
        assert h.getAnnotationObjects().containsAll([renamed, noCount, wrongCount, wrongClass, idxOne, idxText, noPrefix, rectangle])
    }

    // Source contract: the only removal sits in the explicit replace branch; the question precedes
    // the Python run in the single-slide and project paths; Cancel returns before any work;
    // headless fails closed on an unknown `mevcut`.
    void testTiaRegionAsksBeforeRemoving() {
        String s = source(TIA_REGION)
        assert s.count('removeObjects(') == 1
        int rep = s.indexOf("if (mode == 'replace') {")
        int rem = s.indexOf('hier.removeObjects(prev, true)')
        assert rep >= 0 && rem > rep && rem - rep < 200
        assert !s.contains('startsWith(SENTINEL_PREFIX) }, false)')
        assert s.contains("def importAndFilter = { File geojson, imageData, List regionRois, String mode = 'add' ->")
        assert s.contains("Closure setPhase, String mode = 'add' ->")
        assert s.contains('importAndFilter(geo, imageData, regionRois, mode)')
        int single = s.indexOf('def startRun = {')
        int singleAsk = s.indexOf('askExisting(imageData)', single)
        assert singleAsk > single && singleAsk < s.indexOf('def worker = new Thread', single)
        assert s.indexOf('if (importMode == null) return', single) > singleAsk
        assert s.contains('appendLine, setPhase, importMode)')
        int proj = s.indexOf('def startProjectRun = {')
        int projAsk = s.indexOf('askProjectMode(', proj)
        assert projAsk > proj && projAsk < s.indexOf('def worker = new Thread', proj)
        assert s.indexOf('if (importMode == null) return', proj) > projAsk
        assert s.contains("appendLine('   ' + ph) }, importMode)")
        String askSrc = closure(TIA_REGION, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        String projSrc = closure(TIA_REGION, 'askProjectMode')
        assert projSrc.contains('if (pick == null) return null')
        assert projSrc.contains('return ok ? MODE_REPLACE : null')
        assert s.contains("argMap.containsKey('mevcut')")
        assert s.contains("['ekle', 'degistir'].contains(mv)")
        int hl = s.indexOf('if (isHeadless) {')
        assert s.indexOf('prevOwnPointSets(imageData.getHierarchy())', hl) > hl
        assert s.indexOf('\n    return\n}', hl) > s.indexOf('hlPrevExists', hl)
    }

    static final String TIA_MASK = 'yardimci-tiatoolbox-sihirbaz.groovy'

    // Own-result matching of the TIA tissue-mask importer: exact name or name + " (N)" with N >= 2,
    // never a prefix; an annotation the user renamed is never swept.
    void testTiaMaskOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def base = 'Doku maskesi (TIA Toolbox)'
        shell([h: h, base: base]).evaluate(ownSetCode(TIA_MASK) + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
''')
        def mine = ann(base + ' düzeltmelerim')
        h.addObjects([ann(base), ann(base, 200), mine, ann(base + ' (1)'), ann(base + ' (x)'), ann('Doku'), ann('Başka')])
        shell([h: h, base: base]).evaluate(ownSetCode(TIA_MASK) + '''
assert prevOwnObjects(h, base).size() == 2
assert nextOwnSetName(h, base) == base + ' (2)'
assert !isOwnSetName(base + ' düzeltmelerim', base)
assert !isOwnSetName(base + ' (1)', base)
assert !isOwnSetName(base + ' (x)', base)
assert !isOwnSetName(null, base)
''')
        h.addObjects([ann(base + ' (2)'), ann(base + ' (5)')])
        shell([h: h, base: base]).evaluate(ownSetCode(TIA_MASK) + '''
assert prevOwnObjects(h, base).size() == 4
assert nextOwnSetName(h, base) == base + ' (6)'
''')
        assert h.getAnnotationObjects().contains(mine)
    }

    // Source contract: the only removal sits in the explicit replace branch and acts on the captured
    // image's own hierarchy; the question precedes the thumbnail export and the Python run, only in
    // tissue-mask mode; Cancel returns before any work; headless fails closed on an unknown `mevcut`.
    void testTiaMaskAsksBeforeRemoving() {
        String s = source(TIA_MASK)
        assert s.count('removeObjects(') == 1
        assert !s.contains('QP.removeObjects')
        assert !s.contains('QP.addObjects')
        int rep = s.indexOf("if (mode == 'replace') {")
        int rem = s.indexOf('hierarchy.removeObjects(prev, true)')
        assert rep >= 0 && rem > rep && rem - rep < 200
        assert s.contains("def importTissueMask = { File workDir, imageData, double downsample, String mode = 'add' ->")
        assert s.contains('importTissueMask(workDir, imageData, mds, tissueImportMode)')
        int run = s.indexOf('def startRun = {')
        int ask = s.indexOf('askExisting(imageData.getHierarchy())', run)
        assert ask > run && ask < s.indexOf('def worker = new Thread', run)
        assert ask < s.indexOf('workDir.mkdirs()', run)
        assert s.lastIndexOf("if (mode == 'tissue-mask') {", ask) > run
        assert s.indexOf('if (importMode == null) return', run) > ask
        String askSrc = closure(TIA_MASK, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        assert s.contains("argMap.containsKey('mevcut')")
        assert s.contains("['ekle', 'degistir'].contains(mv)")
        int hl = s.indexOf('if (isHeadless) {')
        assert s.indexOf('prevOwnObjects(imageData.getHierarchy(), TISSUE_SENTINEL)', hl) > hl
        assert s.indexOf('\n    return\n}', hl) > s.indexOf('hlPrevExists', hl)
    }

    static final String TISSUE = 'yardimci-doku-tespiti-sihirbaz.groovy'

    static String tissueClosures(List<String> names) {
        names.collect { closure(TISSUE, it) }.join('\n')
    }

    // "Own result" = class Doku AND exact name "Doku" / "Doku (N>=2)". Never a prefix, never another
    // class, never the TIA Toolbox tissue annotation, never a user-renamed annotation.
    void testTissueDetectOwnSetNeverMatchesByPrefixOrOtherClass() {
        def h = new PathObjectHierarchy()
        def cls = PathClass.fromString('Doku')
        def mk = { String n, double x, PathClass c -> def a = ann(n, x); a.setPathClass(c); a }
        String names = tissueClosures(['isOwnSetName', 'ownSetIndex', 'prevTissueObjects', 'nextTissueSetName'])
        shell([h: h]).evaluate(names + '''
assert nextTissueSetName(h, 'Doku', 'Doku') == 'Doku'
assert prevTissueObjects(null, 'Doku', 'Doku').isEmpty()
''')
        h.addObjects([mk('Doku', 0, cls), mk('Doku (2)', 200, cls), mk('Doku', 400, null),
                      mk('Doku düzeltmelerim', 600, cls), mk('Doku maskesi (TIA Toolbox)', 800, cls),
                      mk('Doku (1)', 1000, cls), mk('Doku (x)', 1200, cls), mk('Doku', 1400, PathClass.fromString('Başka')),
                      mk(null, 1600, cls)])
        shell([h: h]).evaluate(names + '''
assert prevTissueObjects(h, 'Doku', 'Doku').size() == 2
assert nextTissueSetName(h, 'Doku', 'Doku') == 'Doku (3)'
assert !isOwnSetName('Doku düzeltmelerim', 'Doku')
assert !isOwnSetName('Doku (1)', 'Doku')
assert !isOwnSetName('Doku (x)', 'Doku')
assert !isOwnSetName(null, 'Doku')
''')
    }

    // Behaviour: Add deletes nothing and numbers the new set; Replace removes only own sets and keeps children.
    void testTissueDetectApplyAddKeepsEarlierAndReplaceKeepsChildren() {
        def h = new PathObjectHierarchy()
        def cls = PathClass.fromString('Doku')
        def old = ann('Doku', 0); old.setPathClass(cls)
        def tia = ann('Doku maskesi (TIA Toolbox)', 200); tia.setPathClass(cls)
        def mine = ann('Doku düzeltmelerim', 400); mine.setPathClass(cls)
        h.addObjects([old, tia, mine])
        def child = det('Tümör', 10); old.addChildObject(child)
        assert child.getParent() == old
        String code = 'import qupath.lib.objects.PathObjects\n' +
            tissueClosures(['isOwnSetName', 'ownSetIndex', 'prevTissueObjects', 'nextTissueSetName', 'applyAnno'])
        // QP lives in the GUI module, which is not on the unit-test classpath: stub the two calls applyAnno makes.
        def qp = new Expando(getPathClass: { String n -> PathClass.fromString(n) }, fireHierarchyUpdate: { hh -> })
        def vars = [h: h, QP: qp, SENTINEL_NAME: 'Doku', SENTINEL_CLASS: 'Doku', MODE_ADD: 'add', MODE_REPLACE: 'replace']
        def r1 = shell(vars + [r: roi(600)]).evaluate(code + "\napplyAnno(r, h, MODE_ADD)")
        assert r1.setName == 'Doku (2)' && r1.removed == 0 && r1.prevNames == ['Doku']
        assert h.getAnnotationObjects().size() == 4
        assert h.getAnnotationObjects().containsAll([old, tia, mine])
        assert h.getDetectionObjects().contains(child)
        def r2 = shell(vars + [r: roi(800)]).evaluate(code + "\napplyAnno(r, h, MODE_REPLACE)")
        assert r2.setName == 'Doku' && r2.removed == 2 && r2.prevNames.isEmpty()
        assert !h.getAnnotationObjects().contains(old)
        assert h.getAnnotationObjects().containsAll([tia, mine])
        assert h.getAnnotationObjects().size() == 3
        assert h.getDetectionObjects().contains(child)       // keepChildren = true
        assert child.getParent() != old
        assert h.getAnnotationObjects().count { it.getName() == 'Doku' && it.isLocked() } == 1
    }

    // Source contract: only the Replace branch removes anything, the question precedes any work, Cancel
    // returns first, the preview never writes to the hierarchy, and headless mode fails closed.
    void testTissueDetectAsksBeforeRemoving() {
        String s = source(TISSUE)
        assert s.count('removeObjects(') == 1
        int rep = s.indexOf('if (mode == MODE_REPLACE) {')
        int rem = s.indexOf('hierarchy.removeObjects(prev, true)')
        assert rep >= 0 && rem > rep && rem - rep < 200
        assert !s.contains('QP.removeObjects') && !s.contains('clearAllObjects') && !s.contains('clearDetections')
        assert s.count('addObjects(') == 1                    // only applyAnno writes; previewing never does
        String askSrc = closure(TISSUE, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        int click = s.indexOf('applyBtn.setOnAction({')
        int ask = s.indexOf('askExisting(targetHierarchy)', click)
        assert ask > click
        assert ask < s.indexOf('applyBtn.setDisable(true); applyBtn.setText(', click)
        assert ask < s.indexOf('new Thread({', ask)
        assert s.contains('if (applyMode == null) return')
        assert s.contains('applyAnno(d.roi, targetHierarchy, applyMode)')
        int hl = s.indexOf('if (isHeadless) {\n    // Önceki sonuç')
        assert hl >= 0
        assert s.indexOf("['ekle', 'degistir'].contains(mevcut)", hl) > hl
        assert s.indexOf('!prevHeadless.isEmpty() && mevcut == null', hl) > hl
        assert s.indexOf('!prevHeadless.isEmpty() && mevcut == null', hl) < s.indexOf('def c = buildCache(defDs, defChoice)', hl)
        assert s.contains("String headlessMode = (mevcut == 'degistir') ? MODE_REPLACE : MODE_ADD")
        assert s.contains('applyAnno(d.roi, imageData.getHierarchy(), headlessMode)')
    }

    static final String BUDDING = 'yardimci-tumor-tomurcuklanma.groovy'

    static String budClosures(List<String> names) {
        names.collect { closure(BUDDING, it) }.join('\n')
    }

    static Map budVars(Map extra = [:]) {
        [BUD_CLASS_NAME: 'Tumor bud', BAND_NAME: 'Tomurcuk bandı (invazif cephe)', TMA_BASE: 'TMA çekirdek bölgesi'] + extra
    }

    // Exact name or "name (N>=2)"; TMA names match only against the grid's own core names.
    void testBuddingOwnSetNamingNeverMatchesByPrefix() {
        shell(budVars()).evaluate(budClosures(['isOwnSetName', 'ownSetIndex', 'tmaSetIndex']) + '''
def b = BAND_NAME
assert isOwnSetName(b, b) && isOwnSetName(b + ' (2)', b) && isOwnSetName(b + ' (12)', b)
assert !isOwnSetName(b + ' notlarım', b) && !isOwnSetName(b + ' (1)', b) && !isOwnSetName(b + ' (x)', b)
assert !isOwnSetName(null, b)
assert ownSetIndex(b, b) == 1 && ownSetIndex(b + ' (3)', b) == 3
def cores = ['A-1', 'B-2']
assert tmaSetIndex(TMA_BASE + ': A-1', cores, TMA_BASE) == 1
assert tmaSetIndex(TMA_BASE + ' (2): B-2', cores, TMA_BASE) == 2
assert tmaSetIndex(TMA_BASE + ' (1): A-1', cores, TMA_BASE) == 0
assert tmaSetIndex(TMA_BASE + ' (x): A-1', cores, TMA_BASE) == 0
assert tmaSetIndex(TMA_BASE + ' (): A-1', cores, TMA_BASE) == 0
assert tmaSetIndex(TMA_BASE + ': A-1 notlarım', cores, TMA_BASE) == 0
assert tmaSetIndex(TMA_BASE + ': Z-9', cores, TMA_BASE) == 0
assert tmaSetIndex(null, cores, TMA_BASE) == 0
''')
    }

    // The earlier result is ONLY "Tumor bud" detections (and the wizard's own bands); the user's cell
    // detections, other classes and renamed annotations are never part of it.
    void testBuddingEarlierResultIsOnlyOwnObjects() {
        def h = new PathObjectHierarchy()
        def region = ann('Bölge', 0)
        h.addObject(region)
        def bud1 = det('Tumor bud', 10); def bud2 = det('Tumor bud', 20); bud2.setName('Tumor bud (2)')
        def renamed = det('Tumor bud', 25); renamed.setName('Tumor bud notlarım')
        def cell = det('Lenfosit', 30); def tumor = det('Tumor', 40); def plain = det(null, 50)
        [bud1, bud2, renamed, cell, tumor, plain].each { region.addChildObject(it) }
        assert bud1.getParent() == region
        String code = budClosures(['isOwnSetName', 'ownSetIndex', 'tmaSetIndex', 'isBudDetection', 'budIndexOf', 'earlierOf'])
        def e = shell(budVars([h: h, region: region])).evaluate(code + "\nearlierOf(h, 'area', [region])")
        assert e.buds.size() == 3 && e.buds.containsAll([bud1, bud2, renamed])
        assert !e.buds.contains(cell) && !e.buds.contains(tumor) && !e.buds.contains(plain)
        assert e.parents.isEmpty() && e.count == 3 && e.maxIdx == 2
        def none = shell(budVars([h: h])).evaluate(code + "\nearlierOf(h, 'area', [])")
        assert none.count == 0 && none.maxIdx == 0
        def hot = shell(budVars([h: h, region: region])).evaluate(code + "\nearlierOf(h, 'hotspot', [region])")
        assert hot.count == 3

        def h2 = new PathObjectHierarchy()
        def band = ann('Tomurcuk bandı (invazif cephe)', 1000)
        def band2 = ann('Tomurcuk bandı (invazif cephe) (2)', 1200)
        def userBand = ann('Tomurcuk bandı (invazif cephe) notlarım', 1400)
        h2.addObjects([band, band2, userBand])
        def bBud = det('Tumor bud', 1010); def bCell = det('Lenfosit', 1020)
        band.addChildObject(bBud); band.addChildObject(bCell)
        assert bBud.getParent() == band
        def f = shell(budVars([h: h2])).evaluate(code + "\nearlierOf(h, 'front', [])")
        assert f.parents.size() == 2 && f.parents.containsAll([band, band2]) && !f.parents.contains(userBand)
        assert f.buds == [bBud] && f.count == 3 && f.maxIdx == 2
        def t = shell(budVars([h: h2])).evaluate(code + "\nearlierOf(h, 'tma', [])")   // no grid -> nothing
        assert t.count == 0
    }

    // Source contract: DELETE_EXISTING is gone, only the new detections of this run are filtered away,
    // every earlier-result removal sits behind the Replace flag, and the question precedes the run.
    void testBuddingAsksBeforeRemoving() {
        String s = source(BUDDING)
        assert !s.contains("'DELETE_EXISTING'")
        assert s.contains("QP.createDetectionsFromPixelClassifier(classifier, budMin, holeArea, 'SPLIT')")
        assert s.contains('findAll { it.isDetection() }.findAll { !before.contains(it) }')
        assert s.count('removeObjects(') == 3                  // this run's own toRemove + the two in removeEarlier
        String rem = closure(BUDDING, 'removeEarlier')
        assert rem.contains('hierarchy.removeObjects(e.buds, true)') && rem.contains('hierarchy.removeObjects(e.parents, true)')
        assert s.count('removeEarlier(hierarchy, earlier)') == 3
        assert s.count('if (doReplace) removedN = removeEarlier(hierarchy, earlier)') == 3
        assert !s.contains('QP.removeObjects(oldBands') && !s.contains("it.getName() == BAND_NAME }")
        String askSrc = closure(BUDDING, 'askExisting')
        assert askSrc.contains('if (e.count == 0) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        int start = s.indexOf('def startRun = {')
        int ask = s.indexOf('askExisting(', start)
        assert ask > start && ask < s.indexOf("step.set('RUNNING')", start)
        assert s.indexOf('if (existMode == null) return', start) > ask
        assert s.contains('fillRef.get(), existMode)')
        assert s.contains("String existMode = 'add' ->")
    }

    // Owner decision: Replace removes the old transfer whole (outlines plus the objects that were
    // transferred inside them) but never an object the user added inside afterwards.
    void testTransferReplaceRemovesTaggedTreeAndKeepsUserObjects() {
        ['yardimci-warpy-sihirbaz.groovy', 'yardimci-hizalama-aktarim.groovy', 'yardimci-valis-sihirbaz.groovy'].each { file ->
            def h = new PathObjectHierarchy()
            def outline = ann('set', 0)
            def movedCell = det('Tumor', 10)
            def userCell = det('Mine', 20)
            def userRegion = ann('benim', 400)
            outline.addChildObject(movedCell)
            h.addObjects([outline, userRegion])
            String code = ['tagTransferTree', 'ownTransferTree'].collect { closure(file, it) }.join('\n')
            shell([roots: [outline]]).evaluate(code + "\ntagTransferTree(roots, 'set')")
            assert outline.getMetadata().get('atolye_aktarim') == 'set'
            assert movedCell.getMetadata().get('atolye_aktarim') == 'set'
            outline.addChildObject(userCell)          // added by the user after the transfer: no tag
            def victims = shell([prev: [outline]]).evaluate(code + '\nownTransferTree(prev)')
            assert victims.contains(outline) && victims.contains(movedCell)
            assert !victims.contains(userCell) && !victims.contains(userRegion)
            h.removeObjects(victims, true)
            assert !h.getAnnotationObjects().contains(outline)
            assert !h.getDetectionObjects().contains(movedCell)
            assert h.getDetectionObjects().contains(userCell)
            assert h.getAnnotationObjects().contains(userRegion)
            assert source(file).contains('removeObjects(ownTransferTree(')
            assert !source(file).contains('removeObjects(prev, true)') && !source(file).contains('removeObjects(prevOwn, true)')
        }
    }

    // The image type and stain vectors are never changed with only a log line: every
    // setImageType call sits behind a question (GUI), a named argument (headless) or a button.
    void testModulesAskBeforeChangingImageTypeOrStains() {
        ['nukleer-boya.groovy', 'er-pr-hscore.groovy', 'membran-boya.groovy', 'sitoplazmik-boya.groovy'].each { file ->
            String s = source(file)
            int ask = s.indexOf("if (headlessArg('boya') != 'varsayilan') {")
            int gui = s.indexOf('} else if (!askYesNo(', ask)
            int set = s.indexOf("QP.setImageType('BRIGHTFIELD_H_DAB')")
            assert ask >= 0 && gui > ask && set > gui : file
            String between = s.substring(ask, set)
            assert between.count('return') == 2 : file              // headless stop and GUI "No"
            assert !s.contains('BRIGHTFIELD_H_DAB varsayılanı uygulanıyor."\n        QP.setImageType') || s.contains('kullanıcı onayıyla') : file
            // The only other call is the explicit "set image type" button handler.
            assert s.count("QP.setImageType('BRIGHTFIELD_H_DAB')") == 2 : file
            assert s.indexOf('fixTypeBtn.setOnAction({') < s.lastIndexOf("QP.setImageType('BRIGHTFIELD_H_DAB')") : file
        }
        String cell = source('hucre-tespiti.groovy')
        int ask = cell.indexOf("if (headlessArg('tip') != 'he') {")
        int gui = cell.indexOf('} else if (!askYesNo(', ask)
        int set = cell.indexOf("QP.setImageType('BRIGHTFIELD_H_E')")
        assert ask >= 0 && gui > ask && set > gui
        assert cell.count("QP.setImageType(") == 1
        assert cell.substring(ask, set).count('return') == 2
        // askYesNo and headlessArg behave: no args -> null; a named value is read lower-cased.
        def r = shell([binding: new Binding([args: ['boya=VARSAYILAN', 'x=1']])]).evaluate(
            closure('nukleer-boya.groovy', 'headlessArg') + "\n[headlessArg('boya'), headlessArg('yok')]")
        assert r == ['varsayilan', null]
    }


    static final String HOTSPOT_FILE = 'yardimci-hotspot-sirali.groovy'

    static String hotspotClosures() {
        ['isOwnSetName', 'ownSetIndex', 'prevOwnObjects', 'nextOwnSetName', 'prevOwnSummaries',
         'askExisting', 'headlessDecision', 'commitSummary', 'modeNote'].collect { closure(HOTSPOT_FILE, it) }.join('\n')
    }

    // A summary as the script writes it: carries the signature measurement. unsigned = a user's own annotation.
    static def hotspotSummary(String name, double x, boolean signed = true) {
        def a = ann(name, x)
        if (signed) a.getMeasurements().put('Seçilen top-N', 1d)
        a
    }

    // Add -> add -> add numbering; a user-renamed annotation is never part of "our" sets.
    void testHotspotOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def base = 'Hotspot Özet'
        shell([h: h, base: base]).evaluate(hotspotClosures() + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
assert isOwnSetName(base, base) && isOwnSetName(base + ' (2)', base) && isOwnSetName(base + ' (17)', base)
assert !isOwnSetName(base + ' (1)', base) && !isOwnSetName(base + ' (x)', base) && !isOwnSetName(base + ' (2) x', base)
assert !isOwnSetName(base + ' düzeltmelerim', base) && !isOwnSetName('x ' + base, base) && !isOwnSetName(null, base)
''')
        def mine = ann(base + ' düzeltmelerim', 900)
        h.addObjects([mine])
        def names = []
        3.times { i ->
            def s = ann(shell([h: h, base: base]).evaluate(hotspotClosures() + '\nnextOwnSetName(h, base)'), i * 150)
            names << s.getName()
            h.addObjects([s])
        }
        assert names == [base, base + ' (2)', base + ' (3)']
        assert h.getAnnotationObjects().contains(mine)
        assert mine.getName() == base + ' düzeltmelerim'
    }

    // Behaviour: Add deletes nothing and numbers the new summary; Replace removes only signed own summaries,
    // keeps the objects inside them, and never touches a user's same-name annotation that lacks the signature.
    void testHotspotCommitAddKeepsEarlierAndReplaceKeepsUserObjects() {
        def h = new PathObjectHierarchy()
        def base = 'Hotspot Özet'
        def s1 = hotspotSummary(base, 0)
        def s2 = hotspotSummary(base + ' (2)', 150)
        def mineSame = hotspotSummary(base, 300, false)
        def mineRenamed = hotspotSummary(base + ' notlarım', 450, false)
        h.addObjects([s1, s2, mineSame, mineRenamed])
        def child = det('Tümör', 10); s1.addChildObject(child)
        assert child.getParent() == s1
        String code = hotspotClosures()
        def vars = [h: h, base: base, SIG: 'Seçilen top-N', MODE_ADD: 'add', MODE_REPLACE: 'replace',
                    workshopText: { Object... a -> a[0] }]
        def sum3 = hotspotSummary('x', 600)
        def r1 = shell(vars + [sum: sum3]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_ADD, sum)")
        assert r1.setName == base + ' (3)' && r1.removed == 0
        assert r1.prevNames == [base, base + ' (2)']
        assert sum3.getName() == base + ' (3)'
        assert shell(vars + [res: r1]).evaluate(code + "\nmodeNote(res)")      // result-window note after Add
        assert h.getAnnotationObjects().size() == 5
        assert h.getAnnotationObjects().containsAll([s1, s2, mineSame, mineRenamed, sum3])
        def sum4 = hotspotSummary('y', 750)
        def r2 = shell(vars + [sum: sum4]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_REPLACE, sum)")
        assert r2.removed == 3 && r2.prevNames.isEmpty()
        assert r2.setName == base + ' (2)'
        assert shell(vars + [res: r2]).evaluate(code + "\nmodeNote(res)")      // result-window note after Replace
        assert !h.getAnnotationObjects().contains(s1) && !h.getAnnotationObjects().contains(s2) && !h.getAnnotationObjects().contains(sum3)
        assert h.getAnnotationObjects().containsAll([mineSame, mineRenamed, sum4])
        assert h.getAnnotationObjects().size() == 3
        assert h.getDetectionObjects().contains(child)
        assert child.getParent() != s1
        def h2 = new PathObjectHierarchy()
        def sum5 = hotspotSummary('z', 0)
        def r3 = shell(vars + [h: h2, sum: sum5]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_ADD, sum)")
        assert r3.setName == base && r3.removed == 0 && r3.prevNames.isEmpty()
        assert h2.getAnnotationObjects() == [sum5]
    }

    // Decisions: no question without an earlier own summary; Add is the default; Replace needs a second
    // confirmation; Cancel anywhere returns null; a user's unsigned same-name annotation never triggers a question.
    void testHotspotAskDecisions() {
        def base = 'Hotspot Özet'
        String code = hotspotClosures()
        def h = new PathObjectHierarchy()
        def calls = []
        def mkVars = { choice, confirm ->
            [h: h, base: base, SIG: 'Seçilen top-N', MODE_ADD: 'add', MODE_REPLACE: 'replace', workshopText: { Object... a -> a[0] },
             Dialogs: new Expando(
                showChoiceDialog: { t, b, opts, dflt -> calls << 'choice'; choice == 'add' ? opts[0] : (choice == 'replace' ? opts[1] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; confirm })]
        }
        def ask = { choice, confirm -> shell(mkVars(choice, confirm)).evaluate(code + "\naskExisting(h, base, SIG)") }
        assert ask('replace', true) == 'add' && calls.isEmpty()
        h.addObjects([hotspotSummary(base, 0, false)])
        assert ask('replace', true) == 'add' && calls.isEmpty()
        h.addObjects([hotspotSummary(base, 150)])
        assert ask('add', true) == 'add' && calls == ['choice']
        calls.clear()
        assert ask('replace', true) == 'replace' && calls == ['choice', 'confirm']
        calls.clear()
        assert ask('replace', false) == null && calls == ['choice', 'confirm']
        calls.clear()
        assert ask(null, true) == null && calls == ['choice']
        assert h.getAnnotationObjects().size() == 2
    }

    // Headless: no dialog. An earlier own summary stops the run unless mevcut=ekle|degistir is given;
    // an unknown value stops it whether or not an earlier result exists; deciding never writes.
    void testHotspotHeadlessFailsClosed() {
        def base = 'Hotspot Özet'
        String code = hotspotClosures()
        def h = new PathObjectHierarchy()
        def dec = { String m -> shell([h: h, base: base, SIG: 'Seçilen top-N', MODE_ADD: 'add', MODE_REPLACE: 'replace',
                                       workshopText: { Object... a -> a[0] }, mev: m]).evaluate(code + "\nheadlessDecision(h, base, SIG, mev)") }
        assert dec(null).mode == 'add' && dec(null).error == null
        assert dec('ekle').mode == 'add' && dec('degistir').mode == 'replace'
        assert dec('sil').mode == null && dec('sil').error
        h.addObjects([hotspotSummary(base, 0)])
        assert dec('sil').mode == null && dec('sil').error
        assert dec(null).mode == null && dec(null).error
        assert dec('ekle').mode == 'add' && dec('degistir').mode == 'replace'
        assert h.getAnnotationObjects().size() == 1
    }

    // Source contract: removal sits only in the Replace branch, the question precedes any work, Cancel returns
    // first, headless stops first, and the summary name comes only from commitSummary.
    void testHotspotAsksBeforeWriting() {
        String s = source(HOTSPOT_FILE)
        assert s.count('removeObjects(') == 1
        assert !s.contains('QP.removeObjects') && !s.contains('clearAllObjects') && !s.contains('clearDetections') && !s.contains('removeObject(')
        assert s.count('.setName(') == 1
        int rep = s.indexOf('if (mode == MODE_REPLACE) {')
        int rem = s.indexOf('hierarchy.removeObjects(prev, true)')
        assert rep >= 0 && rem > rep && rem - rep < 250
        String askSrc = closure(HOTSPOT_FILE, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        assert askSrc.contains('prevOwnSummaries(hierarchy, base, sigKey)')
        assert !s.contains('startsWith(base)') && !s.contains('startsWith(summaryName)')
        int work = s.indexOf('allAnnos.findAll { it.getPathClass()?.toString() == hotspotClass')
        int ask = s.indexOf('writeMode = askExisting(imageData.getHierarchy()')
        int cancel = s.indexOf('if (writeMode == null) { println "İptal edildi."; return }')
        int head = s.indexOf('def hd = headlessDecision(imageData.getHierarchy()')
        int stop = s.indexOf('if (hd.mode == null) { println hd.error; return }')
        assert work > 0 && ask > 0 && ask < cancel && cancel < work
        assert head > 0 && head < stop && stop < work
        assert s.contains("['ekle', 'degistir'].contains(mevcut)")
        assert s.contains('mevcutArg = argMap.mevcut == null')
        assert s.contains('commitSummary(imageData.getHierarchy(), ') && s.contains(', writeMode, ')
    }


    static final String NNDIST_FILE = 'yardimci-nn-mesafe.groovy'

    static String nnDistanceClosures() {
        ['isOwnSetName', 'ownSetIndex', 'prevOwnObjects', 'nextOwnSetName', 'prevOwnSummaries',
         'askExisting', 'headlessDecision', 'commitSummary', 'modeNote'].collect { closure(NNDIST_FILE, it) }.join('\n')
    }

    // A summary as the script writes it: carries the signature measurement. unsigned = a user's own annotation.
    static def nnDistanceSummary(String name, double x, boolean signed = true) {
        def a = ann(name, x)
        if (signed) a.getMeasurements().put('Ortalama NN mesafesi (µm)', 1d)
        a
    }

    // Add -> add -> add numbering; a user-renamed annotation is never part of "our" sets.
    void testNnDistanceOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def base = 'En Yakın Komşu Özet'
        shell([h: h, base: base]).evaluate(nnDistanceClosures() + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
assert isOwnSetName(base, base) && isOwnSetName(base + ' (2)', base) && isOwnSetName(base + ' (17)', base)
assert !isOwnSetName(base + ' (1)', base) && !isOwnSetName(base + ' (x)', base) && !isOwnSetName(base + ' (2) x', base)
assert !isOwnSetName(base + ' düzeltmelerim', base) && !isOwnSetName('x ' + base, base) && !isOwnSetName(null, base)
''')
        def mine = ann(base + ' düzeltmelerim', 900)
        h.addObjects([mine])
        def names = []
        3.times { i ->
            def s = ann(shell([h: h, base: base]).evaluate(nnDistanceClosures() + '\nnextOwnSetName(h, base)'), i * 150)
            names << s.getName()
            h.addObjects([s])
        }
        assert names == [base, base + ' (2)', base + ' (3)']
        assert h.getAnnotationObjects().contains(mine)
        assert mine.getName() == base + ' düzeltmelerim'
    }

    // Behaviour: Add deletes nothing and numbers the new summary; Replace removes only signed own summaries,
    // keeps the objects inside them, and never touches a user's same-name annotation that lacks the signature.
    void testNnDistanceCommitAddKeepsEarlierAndReplaceKeepsUserObjects() {
        def h = new PathObjectHierarchy()
        def base = 'En Yakın Komşu Özet'
        def s1 = nnDistanceSummary(base, 0)
        def s2 = nnDistanceSummary(base + ' (2)', 150)
        def mineSame = nnDistanceSummary(base, 300, false)
        def mineRenamed = nnDistanceSummary(base + ' notlarım', 450, false)
        h.addObjects([s1, s2, mineSame, mineRenamed])
        def child = det('Tümör', 10); s1.addChildObject(child)
        assert child.getParent() == s1
        String code = nnDistanceClosures()
        def vars = [h: h, base: base, SIG: 'Ortalama NN mesafesi (µm)', MODE_ADD: 'add', MODE_REPLACE: 'replace',
                    workshopText: { Object... a -> a[0] }]
        def sum3 = nnDistanceSummary('x', 600)
        def r1 = shell(vars + [sum: sum3]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_ADD, sum)")
        assert r1.setName == base + ' (3)' && r1.removed == 0
        assert r1.prevNames == [base, base + ' (2)']
        assert sum3.getName() == base + ' (3)'
        assert shell(vars + [res: r1]).evaluate(code + "\nmodeNote(res)")      // result-window note after Add
        assert h.getAnnotationObjects().size() == 5
        assert h.getAnnotationObjects().containsAll([s1, s2, mineSame, mineRenamed, sum3])
        def sum4 = nnDistanceSummary('y', 750)
        def r2 = shell(vars + [sum: sum4]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_REPLACE, sum)")
        assert r2.removed == 3 && r2.prevNames.isEmpty()
        assert r2.setName == base + ' (2)'
        assert shell(vars + [res: r2]).evaluate(code + "\nmodeNote(res)")      // result-window note after Replace
        assert !h.getAnnotationObjects().contains(s1) && !h.getAnnotationObjects().contains(s2) && !h.getAnnotationObjects().contains(sum3)
        assert h.getAnnotationObjects().containsAll([mineSame, mineRenamed, sum4])
        assert h.getAnnotationObjects().size() == 3
        assert h.getDetectionObjects().contains(child)
        assert child.getParent() != s1
        def h2 = new PathObjectHierarchy()
        def sum5 = nnDistanceSummary('z', 0)
        def r3 = shell(vars + [h: h2, sum: sum5]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_ADD, sum)")
        assert r3.setName == base && r3.removed == 0 && r3.prevNames.isEmpty()
        assert h2.getAnnotationObjects() == [sum5]
    }

    // Decisions: no question without an earlier own summary; Add is the default; Replace needs a second
    // confirmation; Cancel anywhere returns null; a user's unsigned same-name annotation never triggers a question.
    void testNnDistanceAskDecisions() {
        def base = 'En Yakın Komşu Özet'
        String code = nnDistanceClosures()
        def h = new PathObjectHierarchy()
        def calls = []
        def mkVars = { choice, confirm ->
            [h: h, base: base, SIG: 'Ortalama NN mesafesi (µm)', MODE_ADD: 'add', MODE_REPLACE: 'replace', workshopText: { Object... a -> a[0] },
             Dialogs: new Expando(
                showChoiceDialog: { t, b, opts, dflt -> calls << 'choice'; choice == 'add' ? opts[0] : (choice == 'replace' ? opts[1] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; confirm })]
        }
        def ask = { choice, confirm -> shell(mkVars(choice, confirm)).evaluate(code + "\naskExisting(h, base, SIG)") }
        assert ask('replace', true) == 'add' && calls.isEmpty()
        h.addObjects([nnDistanceSummary(base, 0, false)])
        assert ask('replace', true) == 'add' && calls.isEmpty()
        h.addObjects([nnDistanceSummary(base, 150)])
        assert ask('add', true) == 'add' && calls == ['choice']
        calls.clear()
        assert ask('replace', true) == 'replace' && calls == ['choice', 'confirm']
        calls.clear()
        assert ask('replace', false) == null && calls == ['choice', 'confirm']
        calls.clear()
        assert ask(null, true) == null && calls == ['choice']
        assert h.getAnnotationObjects().size() == 2
    }

    // Headless: no dialog. An earlier own summary stops the run unless mevcut=ekle|degistir is given;
    // an unknown value stops it whether or not an earlier result exists; deciding never writes.
    void testNnDistanceHeadlessFailsClosed() {
        def base = 'En Yakın Komşu Özet'
        String code = nnDistanceClosures()
        def h = new PathObjectHierarchy()
        def dec = { String m -> shell([h: h, base: base, SIG: 'Ortalama NN mesafesi (µm)', MODE_ADD: 'add', MODE_REPLACE: 'replace',
                                       workshopText: { Object... a -> a[0] }, mev: m]).evaluate(code + "\nheadlessDecision(h, base, SIG, mev)") }
        assert dec(null).mode == 'add' && dec(null).error == null
        assert dec('ekle').mode == 'add' && dec('degistir').mode == 'replace'
        assert dec('sil').mode == null && dec('sil').error
        h.addObjects([nnDistanceSummary(base, 0)])
        assert dec('sil').mode == null && dec('sil').error
        assert dec(null).mode == null && dec(null).error
        assert dec('ekle').mode == 'add' && dec('degistir').mode == 'replace'
        assert h.getAnnotationObjects().size() == 1
    }

    // Source contract: removal sits only in the Replace branch, the question precedes any work, Cancel returns
    // first, headless stops first, and the summary name comes only from commitSummary.
    void testNnDistanceAsksBeforeWriting() {
        String s = source(NNDIST_FILE)
        assert s.count('removeObjects(') == 1
        assert !s.contains('QP.removeObjects') && !s.contains('clearAllObjects') && !s.contains('clearDetections') && !s.contains('removeObject(')
        assert s.count('.setName(') == 1
        int rep = s.indexOf('if (mode == MODE_REPLACE) {')
        int rem = s.indexOf('hierarchy.removeObjects(prev, true)')
        assert rep >= 0 && rem > rep && rem - rep < 250
        String askSrc = closure(NNDIST_FILE, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        assert askSrc.contains('prevOwnSummaries(hierarchy, base, sigKey)')
        assert !s.contains('startsWith(base)') && !s.contains('startsWith(summaryName)')
        int work = s.indexOf('double[] xs = new double[n]')
        int ask = s.indexOf('writeMode = askExisting(imageData.getHierarchy()')
        int cancel = s.indexOf('if (writeMode == null) { println "İptal edildi."; return }')
        int head = s.indexOf('def hd = headlessDecision(imageData.getHierarchy()')
        int stop = s.indexOf('if (hd.mode == null) { println hd.error; return }')
        assert work > 0 && ask > 0 && ask < cancel && cancel < work
        assert head > 0 && head < stop && stop < work
        assert s.contains("['ekle', 'degistir'].contains(mevcut)")
        assert s.contains('mevcutArg = argMap.mevcut == null')
        assert s.contains('commitSummary(imageData.getHierarchy(), ') && s.contains(', writeMode, ')
    }


    static final String CROSSNN_FILE = 'yardimci-capraz-nn-mesafe.groovy'

    static String crossNnClosures() {
        ['isOwnSetName', 'ownSetIndex', 'prevOwnObjects', 'nextOwnSetName', 'prevOwnSummaries',
         'askExisting', 'headlessDecision', 'commitSummary', 'modeNote'].collect { closure(CROSSNN_FILE, it) }.join('\n')
    }

    // A summary as the script writes it: carries the signature measurement. unsigned = a user's own annotation.
    static def crossNnSummary(String name, double x, boolean signed = true) {
        def a = ann(name, x)
        if (signed) a.getMeasurements().put('Ortalama çapraz NN (µm)', 1d)
        a
    }

    // Add -> add -> add numbering; a user-renamed annotation is never part of "our" sets.
    void testCrossNnOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def base = 'Çapraz NN Özet (Tümör→Lenfosit)'
        shell([h: h, base: base]).evaluate(crossNnClosures() + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
assert isOwnSetName(base, base) && isOwnSetName(base + ' (2)', base) && isOwnSetName(base + ' (17)', base)
assert !isOwnSetName(base + ' (1)', base) && !isOwnSetName(base + ' (x)', base) && !isOwnSetName(base + ' (2) x', base)
assert !isOwnSetName(base + ' düzeltmelerim', base) && !isOwnSetName('x ' + base, base) && !isOwnSetName(null, base)
''')
        def mine = ann(base + ' düzeltmelerim', 900)
        h.addObjects([mine])
        def names = []
        3.times { i ->
            def s = ann(shell([h: h, base: base]).evaluate(crossNnClosures() + '\nnextOwnSetName(h, base)'), i * 150)
            names << s.getName()
            h.addObjects([s])
        }
        assert names == [base, base + ' (2)', base + ' (3)']
        assert h.getAnnotationObjects().contains(mine)
        assert mine.getName() == base + ' düzeltmelerim'
    }

    // Behaviour: Add deletes nothing and numbers the new summary; Replace removes only signed own summaries,
    // keeps the objects inside them, and never touches a user's same-name annotation that lacks the signature.
    void testCrossNnCommitAddKeepsEarlierAndReplaceKeepsUserObjects() {
        def h = new PathObjectHierarchy()
        def base = 'Çapraz NN Özet (Tümör→Lenfosit)'
        def s1 = crossNnSummary(base, 0)
        def s2 = crossNnSummary(base + ' (2)', 150)
        def mineSame = crossNnSummary(base, 300, false)
        def mineRenamed = crossNnSummary(base + ' notlarım', 450, false)
        h.addObjects([s1, s2, mineSame, mineRenamed])
        def child = det('Tümör', 10); s1.addChildObject(child)
        assert child.getParent() == s1
        String code = crossNnClosures()
        def vars = [h: h, base: base, SIG: 'Ortalama çapraz NN (µm)', MODE_ADD: 'add', MODE_REPLACE: 'replace',
                    workshopText: { Object... a -> a[0] }]
        def sum3 = crossNnSummary('x', 600)
        def r1 = shell(vars + [sum: sum3]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_ADD, sum)")
        assert r1.setName == base + ' (3)' && r1.removed == 0
        assert r1.prevNames == [base, base + ' (2)']
        assert sum3.getName() == base + ' (3)'
        assert shell(vars + [res: r1]).evaluate(code + "\nmodeNote(res, 'Tümör→Lenfosit en yakın komşu (µm)')")      // result-window note after Add
        assert h.getAnnotationObjects().size() == 5
        assert h.getAnnotationObjects().containsAll([s1, s2, mineSame, mineRenamed, sum3])
        def sum4 = crossNnSummary('y', 750)
        def r2 = shell(vars + [sum: sum4]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_REPLACE, sum)")
        assert r2.removed == 3 && r2.prevNames.isEmpty()
        assert r2.setName == base + ' (2)'
        assert shell(vars + [res: r2]).evaluate(code + "\nmodeNote(res, 'Tümör→Lenfosit en yakın komşu (µm)')")      // result-window note after Replace
        assert !h.getAnnotationObjects().contains(s1) && !h.getAnnotationObjects().contains(s2) && !h.getAnnotationObjects().contains(sum3)
        assert h.getAnnotationObjects().containsAll([mineSame, mineRenamed, sum4])
        assert h.getAnnotationObjects().size() == 3
        assert h.getDetectionObjects().contains(child)
        assert child.getParent() != s1
        def h2 = new PathObjectHierarchy()
        def sum5 = crossNnSummary('z', 0)
        def r3 = shell(vars + [h: h2, sum: sum5]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_ADD, sum)")
        assert r3.setName == base && r3.removed == 0 && r3.prevNames.isEmpty()
        assert h2.getAnnotationObjects() == [sum5]
    }

    // Decisions: no question without an earlier own summary; Add is the default; Replace needs a second
    // confirmation; Cancel anywhere returns null; a user's unsigned same-name annotation never triggers a question.
    void testCrossNnAskDecisions() {
        def base = 'Çapraz NN Özet (Tümör→Lenfosit)'
        String code = crossNnClosures()
        def h = new PathObjectHierarchy()
        def calls = []
        def mkVars = { choice, confirm ->
            [h: h, base: base, SIG: 'Ortalama çapraz NN (µm)', MODE_ADD: 'add', MODE_REPLACE: 'replace', workshopText: { Object... a -> a[0] },
             Dialogs: new Expando(
                showChoiceDialog: { t, b, opts, dflt -> calls << 'choice'; choice == 'add' ? opts[0] : (choice == 'replace' ? opts[1] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; confirm })]
        }
        def ask = { choice, confirm -> shell(mkVars(choice, confirm)).evaluate(code + "\naskExisting(h, base, SIG, 'Tümör→Lenfosit en yakın komşu (µm)')") }
        assert ask('replace', true) == 'add' && calls.isEmpty()
        h.addObjects([crossNnSummary(base, 0, false)])
        assert ask('replace', true) == 'add' && calls.isEmpty()
        h.addObjects([crossNnSummary(base, 150)])
        assert ask('add', true) == 'add' && calls == ['choice']
        calls.clear()
        assert ask('replace', true) == 'replace' && calls == ['choice', 'confirm']
        calls.clear()
        assert ask('replace', false) == null && calls == ['choice', 'confirm']
        calls.clear()
        assert ask(null, true) == null && calls == ['choice']
        assert h.getAnnotationObjects().size() == 2
    }

    // Headless: no dialog. An earlier own summary stops the run unless mevcut=ekle|degistir is given;
    // an unknown value stops it whether or not an earlier result exists; deciding never writes.
    void testCrossNnHeadlessFailsClosed() {
        def base = 'Çapraz NN Özet (Tümör→Lenfosit)'
        String code = crossNnClosures()
        def h = new PathObjectHierarchy()
        def dec = { String m -> shell([h: h, base: base, SIG: 'Ortalama çapraz NN (µm)', MODE_ADD: 'add', MODE_REPLACE: 'replace',
                                       workshopText: { Object... a -> a[0] }, mev: m]).evaluate(code + "\nheadlessDecision(h, base, SIG, mev)") }
        assert dec(null).mode == 'add' && dec(null).error == null
        assert dec('ekle').mode == 'add' && dec('degistir').mode == 'replace'
        assert dec('sil').mode == null && dec('sil').error
        h.addObjects([crossNnSummary(base, 0)])
        assert dec('sil').mode == null && dec('sil').error
        assert dec(null).mode == null && dec(null).error
        assert dec('ekle').mode == 'add' && dec('degistir').mode == 'replace'
        assert h.getAnnotationObjects().size() == 1
    }

    // Source contract: removal sits only in the Replace branch, the question precedes any work, Cancel returns
    // first, headless stops first, and the summary name comes only from commitSummary.
    void testCrossNnAsksBeforeWriting() {
        String s = source(CROSSNN_FILE)
        assert s.count('removeObjects(') == 1
        assert !s.contains('QP.removeObjects') && !s.contains('clearAllObjects') && !s.contains('clearDetections') && !s.contains('removeObject(')
        assert s.count('.setName(') == 1
        int rep = s.indexOf('if (mode == MODE_REPLACE) {')
        int rem = s.indexOf('hierarchy.removeObjects(prev, true)')
        assert rep >= 0 && rem > rep && rem - rep < 250
        String askSrc = closure(CROSSNN_FILE, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        assert askSrc.contains('prevOwnSummaries(hierarchy, base, sigKey)')
        assert !s.contains('startsWith(base)') && !s.contains('startsWith(summaryName)')
        int work = s.indexOf('def bucketsB = new HashMap')
        int ask = s.indexOf('writeMode = askExisting(imageData.getHierarchy()')
        int cancel = s.indexOf('if (writeMode == null) { println "İptal edildi."; return }')
        int head = s.indexOf('def hd = headlessDecision(imageData.getHierarchy()')
        int stop = s.indexOf('if (hd.mode == null) { println hd.error; return }')
        assert work > 0 && ask > 0 && ask < cancel && cancel < work
        assert head > 0 && head < stop && stop < work
        assert s.contains("['ekle', 'degistir'].contains(mevcut)")
        assert s.contains('mevcutArg = argMap.mevcut == null')
        assert s.contains('commitSummary(imageData.getHierarchy(), ') && s.contains(', writeMode, ')
    }


    static final String CLASSCOUNT_FILE = 'yardimci-sinif-sayim.groovy'

    static String classCountClosures() {
        ['isOwnSetName', 'ownSetIndex', 'prevOwnObjects', 'nextOwnSetName', 'prevOwnSummaries',
         'askExisting', 'headlessDecision', 'commitSummary', 'modeNote'].collect { closure(CLASSCOUNT_FILE, it) }.join('\n')
    }

    // A summary as the script writes it: carries the signature measurement. unsigned = a user's own annotation.
    static def classCountSummary(String name, double x, boolean signed = true) {
        def a = ann(name, x)
        if (signed) a.getMeasurements().put('Toplam hücre', 1d)
        a
    }

    // Add -> add -> add numbering; a user-renamed annotation is never part of "our" sets.
    void testClassCountOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def base = 'Sınıf Sayım Özet'
        shell([h: h, base: base]).evaluate(classCountClosures() + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
assert isOwnSetName(base, base) && isOwnSetName(base + ' (2)', base) && isOwnSetName(base + ' (17)', base)
assert !isOwnSetName(base + ' (1)', base) && !isOwnSetName(base + ' (x)', base) && !isOwnSetName(base + ' (2) x', base)
assert !isOwnSetName(base + ' düzeltmelerim', base) && !isOwnSetName('x ' + base, base) && !isOwnSetName(null, base)
''')
        def mine = ann(base + ' düzeltmelerim', 900)
        h.addObjects([mine])
        def names = []
        3.times { i ->
            def s = ann(shell([h: h, base: base]).evaluate(classCountClosures() + '\nnextOwnSetName(h, base)'), i * 150)
            names << s.getName()
            h.addObjects([s])
        }
        assert names == [base, base + ' (2)', base + ' (3)']
        assert h.getAnnotationObjects().contains(mine)
        assert mine.getName() == base + ' düzeltmelerim'
    }

    // Behaviour: Add deletes nothing and numbers the new summary; Replace removes only signed own summaries,
    // keeps the objects inside them, and never touches a user's same-name annotation that lacks the signature.
    void testClassCountCommitAddKeepsEarlierAndReplaceKeepsUserObjects() {
        def h = new PathObjectHierarchy()
        def base = 'Sınıf Sayım Özet'
        def s1 = classCountSummary(base, 0)
        def s2 = classCountSummary(base + ' (2)', 150)
        def mineSame = classCountSummary(base, 300, false)
        def mineRenamed = classCountSummary(base + ' notlarım', 450, false)
        h.addObjects([s1, s2, mineSame, mineRenamed])
        def child = det('Tümör', 10); s1.addChildObject(child)
        assert child.getParent() == s1
        String code = classCountClosures()
        def vars = [h: h, base: base, SIG: 'Toplam hücre', MODE_ADD: 'add', MODE_REPLACE: 'replace',
                    workshopText: { Object... a -> a[0] }]
        def sum3 = classCountSummary('x', 600)
        def r1 = shell(vars + [sum: sum3]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_ADD, sum)")
        assert r1.setName == base + ' (3)' && r1.removed == 0
        assert r1.prevNames == [base, base + ' (2)']
        assert sum3.getName() == base + ' (3)'
        assert shell(vars + [res: r1]).evaluate(code + "\nmodeNote(res)")      // result-window note after Add
        assert h.getAnnotationObjects().size() == 5
        assert h.getAnnotationObjects().containsAll([s1, s2, mineSame, mineRenamed, sum3])
        def sum4 = classCountSummary('y', 750)
        def r2 = shell(vars + [sum: sum4]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_REPLACE, sum)")
        assert r2.removed == 3 && r2.prevNames.isEmpty()
        assert r2.setName == base + ' (2)'
        assert shell(vars + [res: r2]).evaluate(code + "\nmodeNote(res)")      // result-window note after Replace
        assert !h.getAnnotationObjects().contains(s1) && !h.getAnnotationObjects().contains(s2) && !h.getAnnotationObjects().contains(sum3)
        assert h.getAnnotationObjects().containsAll([mineSame, mineRenamed, sum4])
        assert h.getAnnotationObjects().size() == 3
        assert h.getDetectionObjects().contains(child)
        assert child.getParent() != s1
        def h2 = new PathObjectHierarchy()
        def sum5 = classCountSummary('z', 0)
        def r3 = shell(vars + [h: h2, sum: sum5]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_ADD, sum)")
        assert r3.setName == base && r3.removed == 0 && r3.prevNames.isEmpty()
        assert h2.getAnnotationObjects() == [sum5]
    }

    // Decisions: no question without an earlier own summary; Add is the default; Replace needs a second
    // confirmation; Cancel anywhere returns null; a user's unsigned same-name annotation never triggers a question.
    void testClassCountAskDecisions() {
        def base = 'Sınıf Sayım Özet'
        String code = classCountClosures()
        def h = new PathObjectHierarchy()
        def calls = []
        def mkVars = { choice, confirm ->
            [h: h, base: base, SIG: 'Toplam hücre', MODE_ADD: 'add', MODE_REPLACE: 'replace', workshopText: { Object... a -> a[0] },
             Dialogs: new Expando(
                showChoiceDialog: { t, b, opts, dflt -> calls << 'choice'; choice == 'add' ? opts[0] : (choice == 'replace' ? opts[1] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; confirm })]
        }
        def ask = { choice, confirm -> shell(mkVars(choice, confirm)).evaluate(code + "\naskExisting(h, base, SIG)") }
        assert ask('replace', true) == 'add' && calls.isEmpty()
        h.addObjects([classCountSummary(base, 0, false)])
        assert ask('replace', true) == 'add' && calls.isEmpty()
        h.addObjects([classCountSummary(base, 150)])
        assert ask('add', true) == 'add' && calls == ['choice']
        calls.clear()
        assert ask('replace', true) == 'replace' && calls == ['choice', 'confirm']
        calls.clear()
        assert ask('replace', false) == null && calls == ['choice', 'confirm']
        calls.clear()
        assert ask(null, true) == null && calls == ['choice']
        assert h.getAnnotationObjects().size() == 2
    }

    // Headless: no dialog. An earlier own summary stops the run unless mevcut=ekle|degistir is given;
    // an unknown value stops it whether or not an earlier result exists; deciding never writes.
    void testClassCountHeadlessFailsClosed() {
        def base = 'Sınıf Sayım Özet'
        String code = classCountClosures()
        def h = new PathObjectHierarchy()
        def dec = { String m -> shell([h: h, base: base, SIG: 'Toplam hücre', MODE_ADD: 'add', MODE_REPLACE: 'replace',
                                       workshopText: { Object... a -> a[0] }, mev: m]).evaluate(code + "\nheadlessDecision(h, base, SIG, mev)") }
        assert dec(null).mode == 'add' && dec(null).error == null
        assert dec('ekle').mode == 'add' && dec('degistir').mode == 'replace'
        assert dec('sil').mode == null && dec('sil').error
        h.addObjects([classCountSummary(base, 0)])
        assert dec('sil').mode == null && dec('sil').error
        assert dec(null).mode == null && dec(null).error
        assert dec('ekle').mode == 'add' && dec('degistir').mode == 'replace'
        assert h.getAnnotationObjects().size() == 1
    }

    // Source contract: removal sits only in the Replace branch, the question precedes any work, Cancel returns
    // first, headless stops first, and the summary name comes only from commitSummary.
    void testClassCountAsksBeforeWriting() {
        String s = source(CLASSCOUNT_FILE)
        assert s.count('removeObjects(') == 1
        assert !s.contains('QP.removeObjects') && !s.contains('clearAllObjects') && !s.contains('clearDetections') && !s.contains('removeObject(')
        assert s.count('.setName(') == 1
        int rep = s.indexOf('if (mode == MODE_REPLACE) {')
        int rem = s.indexOf('hierarchy.removeObjects(prev, true)')
        assert rep >= 0 && rem > rep && rem - rep < 250
        String askSrc = closure(CLASSCOUNT_FILE, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        assert askSrc.contains('prevOwnSummaries(hierarchy, base, sigKey)')
        assert !s.contains('startsWith(base)') && !s.contains('startsWith(summaryName)')
        int work = s.indexOf('targets.each { ann ->')
        int ask = s.indexOf('writeMode = askExisting(imageData.getHierarchy()')
        int cancel = s.indexOf('if (writeMode == null) { println "İptal edildi."; return }')
        int head = s.indexOf('def hd = headlessDecision(imageData.getHierarchy()')
        int stop = s.indexOf('if (hd.mode == null) { println hd.error; return }')
        assert work > 0 && ask > 0 && ask < cancel && cancel < work
        assert head > 0 && head < stop && stop < work
        assert s.contains("['ekle', 'degistir'].contains(mevcut)")
        assert s.contains('mevcutArg = argMap.mevcut == null')
        assert s.contains('commitSummary(imageData.getHierarchy(), ') && s.contains(', writeMode, ')
    }


    static final String DELAUNAY_FILE = 'yardimci-delaunay-komsuluk.groovy'

    static String delaunayClosures() {
        ['isOwnSetName', 'ownSetIndex', 'prevOwnObjects', 'nextOwnSetName', 'prevOwnSummaries',
         'askExisting', 'headlessDecision', 'commitSummary', 'modeNote'].collect { closure(DELAUNAY_FILE, it) }.join('\n')
    }

    // A summary as the script writes it: carries the signature measurement. unsigned = a user's own annotation.
    static def delaunaySummary(String name, double x, boolean signed = true) {
        def a = ann(name, x)
        if (signed) a.getMeasurements().put('Ortalama komşu sayısı', 1d)
        a
    }

    // Add -> add -> add numbering; a user-renamed annotation is never part of "our" sets.
    void testDelaunayOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def base = 'Delaunay Özet'
        shell([h: h, base: base]).evaluate(delaunayClosures() + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
assert isOwnSetName(base, base) && isOwnSetName(base + ' (2)', base) && isOwnSetName(base + ' (17)', base)
assert !isOwnSetName(base + ' (1)', base) && !isOwnSetName(base + ' (x)', base) && !isOwnSetName(base + ' (2) x', base)
assert !isOwnSetName(base + ' düzeltmelerim', base) && !isOwnSetName('x ' + base, base) && !isOwnSetName(null, base)
''')
        def mine = ann(base + ' düzeltmelerim', 900)
        h.addObjects([mine])
        def names = []
        3.times { i ->
            def s = ann(shell([h: h, base: base]).evaluate(delaunayClosures() + '\nnextOwnSetName(h, base)'), i * 150)
            names << s.getName()
            h.addObjects([s])
        }
        assert names == [base, base + ' (2)', base + ' (3)']
        assert h.getAnnotationObjects().contains(mine)
        assert mine.getName() == base + ' düzeltmelerim'
    }

    // Behaviour: Add deletes nothing and numbers the new summary; Replace removes only signed own summaries,
    // keeps the objects inside them, and never touches a user's same-name annotation that lacks the signature.
    void testDelaunayCommitAddKeepsEarlierAndReplaceKeepsUserObjects() {
        def h = new PathObjectHierarchy()
        def base = 'Delaunay Özet'
        def s1 = delaunaySummary(base, 0)
        def s2 = delaunaySummary(base + ' (2)', 150)
        def mineSame = delaunaySummary(base, 300, false)
        def mineRenamed = delaunaySummary(base + ' notlarım', 450, false)
        h.addObjects([s1, s2, mineSame, mineRenamed])
        def child = det('Tümör', 10); s1.addChildObject(child)
        assert child.getParent() == s1
        String code = delaunayClosures()
        def vars = [h: h, base: base, SIG: 'Ortalama komşu sayısı', MODE_ADD: 'add', MODE_REPLACE: 'replace',
                    workshopText: { Object... a -> a[0] }]
        def sum3 = delaunaySummary('x', 600)
        def r1 = shell(vars + [sum: sum3]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_ADD, sum)")
        assert r1.setName == base + ' (3)' && r1.removed == 0
        assert r1.prevNames == [base, base + ' (2)']
        assert sum3.getName() == base + ' (3)'
        assert shell(vars + [res: r1]).evaluate(code + "\nmodeNote(res)")      // result-window note after Add
        assert h.getAnnotationObjects().size() == 5
        assert h.getAnnotationObjects().containsAll([s1, s2, mineSame, mineRenamed, sum3])
        def sum4 = delaunaySummary('y', 750)
        def r2 = shell(vars + [sum: sum4]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_REPLACE, sum)")
        assert r2.removed == 3 && r2.prevNames.isEmpty()
        assert r2.setName == base + ' (2)'
        assert shell(vars + [res: r2]).evaluate(code + "\nmodeNote(res)")      // result-window note after Replace
        assert !h.getAnnotationObjects().contains(s1) && !h.getAnnotationObjects().contains(s2) && !h.getAnnotationObjects().contains(sum3)
        assert h.getAnnotationObjects().containsAll([mineSame, mineRenamed, sum4])
        assert h.getAnnotationObjects().size() == 3
        assert h.getDetectionObjects().contains(child)
        assert child.getParent() != s1
        def h2 = new PathObjectHierarchy()
        def sum5 = delaunaySummary('z', 0)
        def r3 = shell(vars + [h: h2, sum: sum5]).evaluate(code + "\ncommitSummary(h, base, SIG, MODE_ADD, sum)")
        assert r3.setName == base && r3.removed == 0 && r3.prevNames.isEmpty()
        assert h2.getAnnotationObjects() == [sum5]
    }

    // Decisions: no question without an earlier own summary; Add is the default; Replace needs a second
    // confirmation; Cancel anywhere returns null; a user's unsigned same-name annotation never triggers a question.
    void testDelaunayAskDecisions() {
        def base = 'Delaunay Özet'
        String code = delaunayClosures()
        def h = new PathObjectHierarchy()
        def calls = []
        def mkVars = { choice, confirm ->
            [h: h, base: base, SIG: 'Ortalama komşu sayısı', MODE_ADD: 'add', MODE_REPLACE: 'replace', workshopText: { Object... a -> a[0] },
             Dialogs: new Expando(
                showChoiceDialog: { t, b, opts, dflt -> calls << 'choice'; choice == 'add' ? opts[0] : (choice == 'replace' ? opts[1] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; confirm })]
        }
        def ask = { choice, confirm -> shell(mkVars(choice, confirm)).evaluate(code + "\naskExisting(h, base, SIG)") }
        assert ask('replace', true) == 'add' && calls.isEmpty()
        h.addObjects([delaunaySummary(base, 0, false)])
        assert ask('replace', true) == 'add' && calls.isEmpty()
        h.addObjects([delaunaySummary(base, 150)])
        assert ask('add', true) == 'add' && calls == ['choice']
        calls.clear()
        assert ask('replace', true) == 'replace' && calls == ['choice', 'confirm']
        calls.clear()
        assert ask('replace', false) == null && calls == ['choice', 'confirm']
        calls.clear()
        assert ask(null, true) == null && calls == ['choice']
        assert h.getAnnotationObjects().size() == 2
    }

    // Headless: no dialog. An earlier own summary stops the run unless mevcut=ekle|degistir is given;
    // an unknown value stops it whether or not an earlier result exists; deciding never writes.
    void testDelaunayHeadlessFailsClosed() {
        def base = 'Delaunay Özet'
        String code = delaunayClosures()
        def h = new PathObjectHierarchy()
        def dec = { String m -> shell([h: h, base: base, SIG: 'Ortalama komşu sayısı', MODE_ADD: 'add', MODE_REPLACE: 'replace',
                                       workshopText: { Object... a -> a[0] }, mev: m]).evaluate(code + "\nheadlessDecision(h, base, SIG, mev)") }
        assert dec(null).mode == 'add' && dec(null).error == null
        assert dec('ekle').mode == 'add' && dec('degistir').mode == 'replace'
        assert dec('sil').mode == null && dec('sil').error
        h.addObjects([delaunaySummary(base, 0)])
        assert dec('sil').mode == null && dec('sil').error
        assert dec(null).mode == null && dec(null).error
        assert dec('ekle').mode == 'add' && dec('degistir').mode == 'replace'
        assert h.getAnnotationObjects().size() == 1
    }

    // Source contract: removal sits only in the Replace branch, the question precedes any work, Cancel returns
    // first, headless stops first, and the summary name comes only from commitSummary.
    void testDelaunayAsksBeforeWriting() {
        String s = source(DELAUNAY_FILE)
        assert s.count('removeObjects(') == 1
        assert !s.contains('QP.removeObjects') && !s.contains('clearAllObjects') && !s.contains('clearDetections') && !s.contains('removeObject(')
        assert s.count('.setName(') == 1
        int rep = s.indexOf('if (mode == MODE_REPLACE) {')
        int rem = s.indexOf('hierarchy.removeObjects(prev, true)')
        assert rep >= 0 && rem > rep && rem - rep < 250
        String askSrc = closure(DELAUNAY_FILE, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        assert askSrc.contains('prevOwnSummaries(hierarchy, base, sigKey)')
        assert !s.contains('startsWith(base)') && !s.contains('startsWith(summaryName)')
        int work = s.indexOf('def subdivision = DelaunayTools')
        int ask = s.indexOf('writeMode = askExisting(imageData.getHierarchy()')
        int cancel = s.indexOf('if (writeMode == null) { println "İptal edildi."; return }')
        int head = s.indexOf('def hd = headlessDecision(imageData.getHierarchy()')
        int stop = s.indexOf('if (hd.mode == null) { println hd.error; return }')
        assert work > 0 && ask > 0 && ask < cancel && cancel < work
        assert head > 0 && head < stop && stop < work
        assert s.contains("['ekle', 'degistir'].contains(mevcut)")
        assert s.contains('mevcutArg = argMap.mevcut == null')
        assert s.contains('commitSummary(imageData.getHierarchy(), ') && s.contains(', writeMode, ')
    }


    // Add -> add -> add numbering; a user-renamed annotation is never swept; Replace keeps what is inside.
    void testValidationF1OwnSetNamingAndApplyMode() {
        String f = 'yardimci-dogrulama-f1.groovy'
        String base = 'Doğrulama Özet'
        String pre = "def summaryName = '" + base + "'\ndef MODE_ADD = 'add'\ndef MODE_REPLACE = 'replace'\n" + ownSetCode(f) + '\n' + closure(f, 'applyMode') + '\n'
        def h = new PathObjectHierarchy()
        shell([h: h]).evaluate(pre + '''
assert prevOwnObjects(null, summaryName).isEmpty()
assert nextOwnSetName(h, summaryName) == summaryName
''')
        def mine = ann(base + ' notlarım', 900)
        h.addObject(mine)
        def made = []
        def seen = []
        3.times { i ->
            def r = shell([h: h]).evaluate(pre + "applyMode(h, MODE_ADD)")
            seen << r
            def a = ann(r.setName, 200 * (i + 1))
            made << a
            h.addObject(a)
        }
        assert made*.getName() == [base, base + ' (2)', base + ' (3)']
        assert seen[0].prevNames == [] && seen[0].removed == 0
        assert seen[1].prevNames == [base]
        assert seen[2].prevNames == [base, base + ' (2)']
        assert h.getAnnotationObjects().contains(mine)
        shell([h: h]).evaluate(pre + '''
assert !isOwnSetName(summaryName + ' notlarım', summaryName)
assert !isOwnSetName(summaryName + ' (1)', summaryName)
assert !isOwnSetName(summaryName + ' (x)', summaryName)
assert !isOwnSetName(null, summaryName)
assert prevOwnObjects(h, summaryName).size() == 3
''')
        // Replace: only the three summaries go; the user's annotation stays; objects inside are handled as documented.
        def inside = ann('Kullanıcı bölgesi', 5)
        made[1].addChildObject(inside)
        def cell = det('Pozitif', 210)
        made[1].addChildObject(cell)
        def r = shell([h: h]).evaluate(pre + "applyMode(h, MODE_REPLACE)")
        assert r.removed == 3 && r.prevNames == [] && r.setName == base
        assert h.getAnnotationObjects().contains(mine)
        assert h.getAnnotationObjects().contains(inside)
        assert h.getAnnotationObjects().findAll { it.getName() == base || it.getName().startsWith(base + ' (') }.isEmpty()
        assert h.getDetectionObjects().contains(cell)   // keepChildren = true
    }

    // Source contract: removal only in the replace branch, the question precedes the computation,
    // Cancel returns first, headless fails closed.
    void testValidationF1AsksBeforeRemoving() {
        String f = 'yardimci-dogrulama-f1.groovy'
        String s = source(f)
        assert s.count('removeObjects(') == 1
        assert !s.contains('QP.removeObjects')
        int rep = s.indexOf('if (mode == MODE_REPLACE) {')
        assert rep > 0 && s.indexOf('removeObjects(') > rep
        // GUI: the question is asked in startCompute before the worker thread and the COMPUTING step.
        int start = s.indexOf('def startCompute = {')
        assert start > 0
        int ask = s.indexOf('askExisting(ctx.imageData.getHierarchy())', start)
        int cancel = s.indexOf('if (existingMode == null) return', start)
        int busy = s.indexOf("step.set('COMPUTING'); render()", start)
        int run = s.indexOf('runValidation(gtL', start)
        assert ask > start && cancel > ask && busy > cancel && run > busy
        // Headless: unknown mevcut fails closed before anything else; an earlier result without mevcut stops.
        int guard = s.indexOf("['ekle', 'degistir'].contains(mevcut)")
        int head = s.indexOf('if (isHeadless) {', s.indexOf('// ── HEADLESS'))
        assert guard > 0 && guard < head
        String block = s.substring(head, s.indexOf('// ── Tek pencere', head))
        int stop = block.indexOf('mevcut == null')
        assert stop > 0 && stop < block.indexOf('runValidation(')
        assert block.substring(stop, block.indexOf('String headlessMode')).contains('return')
        assert !block.contains('removeObjects') && !block.contains('addObjects')
        // runValidation applies the mode after every validation return and before it writes the summary.
        int apply = s.indexOf('applyMode(ctx.imageData.getHierarchy(), existingMode)')
        assert apply > s.indexOf("return [ok: false, reason: 'IOU_NEEDS_AREA']") && apply < s.indexOf('summary.setName(')
    }

    // The question: no earlier result runs silently; Add is the default; Replace needs a second confirmation; Cancel gives null.
    void testValidationF1AskExistingDecisions() {
        String f = 'yardimci-dogrulama-f1.groovy'
        String base = 'Doğrulama Özet'
        def run = { Map dlg, int n ->
            def calls = []
            def h = new PathObjectHierarchy()
            (0..<n).each { h.addObject(ann(base, 100 * it)) }
            h.addObject(ann(base + ' notlarım', 900))
            def Dialogs = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            String code = "def summaryName = '" + base + "'\ndef MODE_ADD = 'add'\ndef MODE_REPLACE = 'replace'\n" + ownSetCode(f) + '\n' +
                closure(f, 'askExisting').replaceAll(/@WT:(\w+)@/) { all, nm -> "'" + nm + "'" }
            def r = shell([Dialogs: Dialogs, h: h, workshopText: { Object... t -> t[0] }]).evaluate(code + '\naskExisting(h)')
            [r, calls]
        }
        assert run([pick: 'add'], 0) == ['add', []]
        assert run([pick: null], 3) == [null, ['choice']]
        assert run([pick: 'add'], 3) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], 3) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], 3) == [null, ['choice', 'confirm']]
    }


    // Add -> add -> add numbering; a user-renamed annotation is never swept; Replace keeps what is inside.
    void testImmuneDensityOwnSetNamingAndApplyMode() {
        String f = 'yardimci-immun-yogunluk.groovy'
        String base = 'İmmün Yoğunluk Özet'
        String pre = "def summaryName = '" + base + "'\ndef MODE_ADD = 'add'\ndef MODE_REPLACE = 'replace'\n" + ownSetCode(f) + '\n' + closure(f, 'applyMode') + '\n'
        def h = new PathObjectHierarchy()
        shell([h: h]).evaluate(pre + '''
assert prevOwnObjects(null, summaryName).isEmpty()
assert nextOwnSetName(h, summaryName) == summaryName
''')
        def mine = ann(base + ' notlarım', 900)
        h.addObject(mine)
        def made = []
        def seen = []
        3.times { i ->
            def r = shell([h: h]).evaluate(pre + "applyMode(h, MODE_ADD)")
            seen << r
            def a = ann(r.setName, 200 * (i + 1))
            made << a
            h.addObject(a)
        }
        assert made*.getName() == [base, base + ' (2)', base + ' (3)']
        assert seen[0].prevNames == [] && seen[0].removed == 0
        assert seen[1].prevNames == [base]
        assert seen[2].prevNames == [base, base + ' (2)']
        assert h.getAnnotationObjects().contains(mine)
        shell([h: h]).evaluate(pre + '''
assert !isOwnSetName(summaryName + ' notlarım', summaryName)
assert !isOwnSetName(summaryName + ' (1)', summaryName)
assert !isOwnSetName(summaryName + ' (x)', summaryName)
assert !isOwnSetName(null, summaryName)
assert prevOwnObjects(h, summaryName).size() == 3
''')
        // Replace: only the three summaries go; the user's annotation stays; objects inside are handled as documented.
        def inside = ann('Kullanıcı bölgesi', 5)
        made[1].addChildObject(inside)
        def cell = det('Pozitif', 210)
        made[1].addChildObject(cell)
        def r = shell([h: h]).evaluate(pre + "applyMode(h, MODE_REPLACE)")
        assert r.removed == 3 && r.prevNames == [] && r.setName == base
        assert h.getAnnotationObjects().contains(mine)
        assert h.getAnnotationObjects().contains(inside)
        assert h.getAnnotationObjects().findAll { it.getName() == base || it.getName().startsWith(base + ' (') }.isEmpty()
        assert h.getDetectionObjects().contains(cell)   // keepChildren = true
    }

    // Source contract: removal only in the replace branch, the question precedes the computation,
    // Cancel returns first, headless fails closed.
    void testImmuneDensityAsksBeforeRemoving() {
        String f = 'yardimci-immun-yogunluk.groovy'
        String s = source(f)
        assert s.count('removeObjects(') == 1
        assert !s.contains('QP.removeObjects')
        int rep = s.indexOf('if (mode == MODE_REPLACE) {')
        assert rep > 0 && s.indexOf('removeObjects(') > rep
        int ask = s.indexOf('runMode = askExisting(imageData.getHierarchy())')
        int cancel = s.indexOf('if (runMode == null) return', ask)
        int compute = s.indexOf("QP.runPlugin(", ask)
        int write = s.indexOf('applyMode(imageData.getHierarchy(), runMode)')
        assert ask > 0 && cancel > ask && compute > cancel
        assert write > compute
        // Headless: unknown mevcut fails closed before anything else; an earlier result without mevcut stops.
        int guard = s.indexOf("['ekle', 'degistir'].contains(mevcut)")
        assert guard > 0 && guard < s.indexOf('def imageData = QP.getCurrentImageData()')
        String block = s.substring(s.lastIndexOf('if (isHeadless) {', ask), ask)
        int stop = block.indexOf('mevcut == null')
        assert stop > 0 && block.substring(stop).contains('return')
        assert !block.contains('removeObjects') && !block.contains('addObjects')
    }

    // The question: no earlier result runs silently; Add is the default; Replace needs a second confirmation; Cancel gives null.
    void testImmuneDensityAskExistingDecisions() {
        String f = 'yardimci-immun-yogunluk.groovy'
        String base = 'İmmün Yoğunluk Özet'
        def run = { Map dlg, int n ->
            def calls = []
            def h = new PathObjectHierarchy()
            (0..<n).each { h.addObject(ann(base, 100 * it)) }
            h.addObject(ann(base + ' notlarım', 900))
            def Dialogs = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            String code = "def summaryName = '" + base + "'\ndef MODE_ADD = 'add'\ndef MODE_REPLACE = 'replace'\n" + ownSetCode(f) + '\n' +
                closure(f, 'askExisting').replaceAll(/@WT:(\w+)@/) { all, nm -> "'" + nm + "'" }
            def r = shell([Dialogs: Dialogs, h: h, workshopText: { Object... t -> t[0] }]).evaluate(code + '\naskExisting(h)')
            [r, calls]
        }
        assert run([pick: 'add'], 0) == ['add', []]
        assert run([pick: null], 3) == [null, ['choice']]
        assert run([pick: 'add'], 3) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], 3) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], 3) == [null, ['choice', 'confirm']]
    }


    // Add -> add -> add numbering; a user-renamed annotation is never swept; Replace keeps what is inside.
    void testStromalTilOwnSetNamingAndApplyMode() {
        String f = 'yardimci-stromal-til.groovy'
        String base = 'Stromal TIL Özet'
        String pre = "def summaryName = '" + base + "'\ndef MODE_ADD = 'add'\ndef MODE_REPLACE = 'replace'\n" + ownSetCode(f) + '\n' + closure(f, 'applyMode') + '\n'
        def h = new PathObjectHierarchy()
        shell([h: h]).evaluate(pre + '''
assert prevOwnObjects(null, summaryName).isEmpty()
assert nextOwnSetName(h, summaryName) == summaryName
''')
        def mine = ann(base + ' notlarım', 900)
        h.addObject(mine)
        def made = []
        def seen = []
        3.times { i ->
            def r = shell([h: h]).evaluate(pre + "applyMode(h, MODE_ADD)")
            seen << r
            def a = ann(r.setName, 200 * (i + 1))
            made << a
            h.addObject(a)
        }
        assert made*.getName() == [base, base + ' (2)', base + ' (3)']
        assert seen[0].prevNames == [] && seen[0].removed == 0
        assert seen[1].prevNames == [base]
        assert seen[2].prevNames == [base, base + ' (2)']
        assert h.getAnnotationObjects().contains(mine)
        shell([h: h]).evaluate(pre + '''
assert !isOwnSetName(summaryName + ' notlarım', summaryName)
assert !isOwnSetName(summaryName + ' (1)', summaryName)
assert !isOwnSetName(summaryName + ' (x)', summaryName)
assert !isOwnSetName(null, summaryName)
assert prevOwnObjects(h, summaryName).size() == 3
''')
        // Replace: only the three summaries go; the user's annotation stays; objects inside are handled as documented.
        def inside = ann('Kullanıcı bölgesi', 5)
        made[1].addChildObject(inside)
        def cell = det('Pozitif', 210)
        made[1].addChildObject(cell)
        def r = shell([h: h]).evaluate(pre + "applyMode(h, MODE_REPLACE)")
        assert r.removed == 3 && r.prevNames == [] && r.setName == base
        assert h.getAnnotationObjects().contains(mine)
        assert h.getAnnotationObjects().contains(inside)
        assert h.getAnnotationObjects().findAll { it.getName() == base || it.getName().startsWith(base + ' (') }.isEmpty()
        assert h.getDetectionObjects().contains(cell)   // keepChildren = true
    }

    // Source contract: removal only in the replace branch, the question precedes the computation,
    // Cancel returns first, headless fails closed.
    void testStromalTilAsksBeforeRemoving() {
        String f = 'yardimci-stromal-til.groovy'
        String s = source(f)
        assert s.count('removeObjects(') == 1
        assert !s.contains('QP.removeObjects')
        int rep = s.indexOf('if (mode == MODE_REPLACE) {')
        assert rep > 0 && s.indexOf('removeObjects(') > rep
        int ask = s.indexOf('runMode = askExisting(imageData.getHierarchy())')
        int cancel = s.indexOf('if (runMode == null) return', ask)
        int compute = s.indexOf("QP.runPlugin(", ask)
        int write = s.indexOf('applyMode(imageData.getHierarchy(), runMode)')
        assert ask > 0 && cancel > ask && compute > cancel
        assert write > compute
        // Headless: unknown mevcut fails closed before anything else; an earlier result without mevcut stops.
        int guard = s.indexOf("['ekle', 'degistir'].contains(mevcut)")
        assert guard > 0 && guard < s.indexOf('def imageData = QP.getCurrentImageData()')
        String block = s.substring(s.lastIndexOf('if (isHeadless) {', ask), ask)
        int stop = block.indexOf('mevcut == null')
        assert stop > 0 && block.substring(stop).contains('return')
        assert !block.contains('removeObjects') && !block.contains('addObjects')
    }

    // The question: no earlier result runs silently; Add is the default; Replace needs a second confirmation; Cancel gives null.
    void testStromalTilAskExistingDecisions() {
        String f = 'yardimci-stromal-til.groovy'
        String base = 'Stromal TIL Özet'
        def run = { Map dlg, int n ->
            def calls = []
            def h = new PathObjectHierarchy()
            (0..<n).each { h.addObject(ann(base, 100 * it)) }
            h.addObject(ann(base + ' notlarım', 900))
            def Dialogs = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            String code = "def summaryName = '" + base + "'\ndef MODE_ADD = 'add'\ndef MODE_REPLACE = 'replace'\n" + ownSetCode(f) + '\n' +
                closure(f, 'askExisting').replaceAll(/@WT:(\w+)@/) { all, nm -> "'" + nm + "'" }
            def r = shell([Dialogs: Dialogs, h: h, workshopText: { Object... t -> t[0] }]).evaluate(code + '\naskExisting(h)')
            [r, calls]
        }
        assert run([pick: 'add'], 0) == ['add', []]
        assert run([pick: null], 3) == [null, ['choice']]
        assert run([pick: 'add'], 3) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], 3) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], 3) == [null, ['choice', 'confirm']]
    }


    // Add -> add -> add numbering; a user-renamed annotation is never swept; Replace keeps what is inside.
    void testWsinferSummaryOwnSetNamingAndApplyMode() {
        String f = 'yardimci-wsinfer-ozet.groovy'
        String base = 'WSInfer Alan Özet'
        String pre = "def summaryName = '" + base + "'\ndef MODE_ADD = 'add'\ndef MODE_REPLACE = 'replace'\n" + ownSetCode(f) + '\n' + closure(f, 'applyMode') + '\n'
        def h = new PathObjectHierarchy()
        shell([h: h]).evaluate(pre + '''
assert prevOwnObjects(null, summaryName).isEmpty()
assert nextOwnSetName(h, summaryName) == summaryName
''')
        def mine = ann(base + ' notlarım', 900)
        h.addObject(mine)
        def made = []
        def seen = []
        3.times { i ->
            def r = shell([h: h]).evaluate(pre + "applyMode(h, MODE_ADD)")
            seen << r
            def a = ann(r.setName, 200 * (i + 1))
            made << a
            h.addObject(a)
        }
        assert made*.getName() == [base, base + ' (2)', base + ' (3)']
        assert seen[0].prevNames == [] && seen[0].removed == 0
        assert seen[1].prevNames == [base]
        assert seen[2].prevNames == [base, base + ' (2)']
        assert h.getAnnotationObjects().contains(mine)
        shell([h: h]).evaluate(pre + '''
assert !isOwnSetName(summaryName + ' notlarım', summaryName)
assert !isOwnSetName(summaryName + ' (1)', summaryName)
assert !isOwnSetName(summaryName + ' (x)', summaryName)
assert !isOwnSetName(null, summaryName)
assert prevOwnObjects(h, summaryName).size() == 3
''')
        // Replace: only the three summaries go; the user's annotation stays; objects inside are handled as documented.
        def inside = ann('Kullanıcı bölgesi', 5)
        made[1].addChildObject(inside)
        def cell = det('Pozitif', 210)
        made[1].addChildObject(cell)
        def r = shell([h: h]).evaluate(pre + "applyMode(h, MODE_REPLACE)")
        assert r.removed == 3 && r.prevNames == [] && r.setName == base
        assert h.getAnnotationObjects().contains(mine)
        assert h.getAnnotationObjects().contains(inside)
        assert h.getAnnotationObjects().findAll { it.getName() == base || it.getName().startsWith(base + ' (') }.isEmpty()
        assert h.getDetectionObjects().contains(cell)   // keepChildren = true
    }

    // Source contract: removal only in the replace branch, the question precedes the computation,
    // Cancel returns first, headless fails closed.
    void testWsinferSummaryAsksBeforeRemoving() {
        String f = 'yardimci-wsinfer-ozet.groovy'
        String s = source(f)
        assert s.count('removeObjects(') == 1
        assert !s.contains('QP.removeObjects')
        int rep = s.indexOf('if (mode == MODE_REPLACE) {')
        assert rep > 0 && s.indexOf('removeObjects(') > rep
        int ask = s.indexOf('runMode = askExisting(imageData.getHierarchy())')
        int cancel = s.indexOf('if (runMode == null) return', ask)
        int compute = s.indexOf("def classOf = { d ->", ask)
        int write = s.indexOf('applyMode(imageData.getHierarchy(), runMode)')
        assert ask > 0 && cancel > ask && compute > cancel
        assert write > compute
        // Headless: unknown mevcut fails closed before anything else; an earlier result without mevcut stops.
        int guard = s.indexOf("['ekle', 'degistir'].contains(mevcut)")
        assert guard > 0 && guard < s.indexOf('def imageData = QP.getCurrentImageData()')
        String block = s.substring(s.lastIndexOf('if (isHeadless) {', ask), ask)
        int stop = block.indexOf('mevcut == null')
        assert stop > 0 && block.substring(stop).contains('return')
        assert !block.contains('removeObjects') && !block.contains('addObjects')
    }

    // The question: no earlier result runs silently; Add is the default; Replace needs a second confirmation; Cancel gives null.
    void testWsinferSummaryAskExistingDecisions() {
        String f = 'yardimci-wsinfer-ozet.groovy'
        String base = 'WSInfer Alan Özet'
        def run = { Map dlg, int n ->
            def calls = []
            def h = new PathObjectHierarchy()
            (0..<n).each { h.addObject(ann(base, 100 * it)) }
            h.addObject(ann(base + ' notlarım', 900))
            def Dialogs = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            String code = "def summaryName = '" + base + "'\ndef MODE_ADD = 'add'\ndef MODE_REPLACE = 'replace'\n" + ownSetCode(f) + '\n' +
                closure(f, 'askExisting').replaceAll(/@WT:(\w+)@/) { all, nm -> "'" + nm + "'" }
            def r = shell([Dialogs: Dialogs, h: h, workshopText: { Object... t -> t[0] }]).evaluate(code + '\naskExisting(h)')
            [r, calls]
        }
        assert run([pick: 'add'], 0) == ['add', []]
        assert run([pick: null], 3) == [null, ['choice']]
        assert run([pick: 'add'], 3) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], 3) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], 3) == [null, ['choice', 'confirm']]
    }


    // Add -> add -> add numbering; a user-renamed annotation is never swept; Replace keeps what is inside.
    void testPhh3OwnSetNamingAndApplyMode() {
        String f = 'yardimci-mitoz-phh3.groovy'
        String base = 'Mitoz Özet'
        String pre = "def summaryName = '" + base + "'\ndef MODE_ADD = 'add'\ndef MODE_REPLACE = 'replace'\n" + ownSetCode(f) + '\n' + closure(f, 'applyMode') + '\n'
        def h = new PathObjectHierarchy()
        shell([h: h]).evaluate(pre + '''
assert prevOwnObjects(null, summaryName).isEmpty()
assert nextOwnSetName(h, summaryName) == summaryName
''')
        def mine = ann(base + ' notlarım', 900)
        h.addObject(mine)
        def made = []
        def seen = []
        3.times { i ->
            def r = shell([h: h]).evaluate(pre + "applyMode(h, MODE_ADD)")
            seen << r
            def a = ann(r.setName, 200 * (i + 1))
            made << a
            h.addObject(a)
        }
        assert made*.getName() == [base, base + ' (2)', base + ' (3)']
        assert seen[0].prevNames == [] && seen[0].removed == 0
        assert seen[1].prevNames == [base]
        assert seen[2].prevNames == [base, base + ' (2)']
        assert h.getAnnotationObjects().contains(mine)
        shell([h: h]).evaluate(pre + '''
assert !isOwnSetName(summaryName + ' notlarım', summaryName)
assert !isOwnSetName(summaryName + ' (1)', summaryName)
assert !isOwnSetName(summaryName + ' (x)', summaryName)
assert !isOwnSetName(null, summaryName)
assert prevOwnObjects(h, summaryName).size() == 3
''')
        // Replace: only the three summaries go; the user's annotation stays; objects inside are handled as documented.
        def inside = ann('Kullanıcı bölgesi', 5)
        made[1].addChildObject(inside)
        def cell = det('Pozitif', 210)
        made[1].addChildObject(cell)
        def r = shell([h: h]).evaluate(pre + "applyMode(h, MODE_REPLACE)")
        assert r.removed == 3 && r.prevNames == [] && r.setName == base
        assert h.getAnnotationObjects().contains(mine)
        assert h.getAnnotationObjects().contains(inside)
        assert h.getAnnotationObjects().findAll { it.getName() == base || it.getName().startsWith(base + ' (') }.isEmpty()
        assert r.removedDet == 1
        assert !h.getDetectionObjects().contains(cell)   // this helper's own PHH3 detections go with their summary
    }

    // Source contract: removal only in the replace branch, the question precedes the computation,
    // Cancel returns first, headless fails closed.
    void testPhh3AsksBeforeRemoving() {
        String f = 'yardimci-mitoz-phh3.groovy'
        String s = source(f)
        assert s.count('removeObjects(') == 2
        assert !s.contains('QP.removeObjects')
        int rep = s.indexOf('if (mode == MODE_REPLACE) {')
        assert rep > 0 && s.indexOf('removeObjects(') > rep
        int ask = s.indexOf('runMode = askExisting(imageData.getHierarchy())')
        int cancel = s.indexOf('if (runMode == null) return', ask)
        int compute = s.indexOf("QP.runPlugin(", ask)
        int write = s.indexOf('applyMode(imageData.getHierarchy(), runMode)')
        assert ask > 0 && cancel > ask && compute > cancel
        assert write > cancel   // the summary is the detection parent: written before the plugin runs
        // Headless: unknown mevcut fails closed before anything else; an earlier result without mevcut stops.
        int guard = s.indexOf("['ekle', 'degistir'].contains(mevcut)")
        assert guard > 0 && guard < s.indexOf('def imageData = QP.getCurrentImageData()')
        String block = s.substring(s.lastIndexOf('if (isHeadless) {', ask), ask)
        int stop = block.indexOf('mevcut == null')
        assert stop > 0 && block.substring(stop).contains('return')
        assert !block.contains('removeObjects') && !block.contains('addObjects')
    }

    // The question: no earlier result runs silently; Add is the default; Replace needs a second confirmation; Cancel gives null.
    void testPhh3AskExistingDecisions() {
        String f = 'yardimci-mitoz-phh3.groovy'
        String base = 'Mitoz Özet'
        def run = { Map dlg, int n ->
            def calls = []
            def h = new PathObjectHierarchy()
            (0..<n).each { h.addObject(ann(base, 100 * it)) }
            h.addObject(ann(base + ' notlarım', 900))
            def Dialogs = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            String code = "def summaryName = '" + base + "'\ndef MODE_ADD = 'add'\ndef MODE_REPLACE = 'replace'\n" + ownSetCode(f) + '\n' +
                closure(f, 'askExisting').replaceAll(/@WT:(\w+)@/) { all, nm -> "'" + nm + "'" }
            def r = shell([Dialogs: Dialogs, h: h, workshopText: { Object... t -> t[0] }]).evaluate(code + '\naskExisting(h)')
            [r, calls]
        }
        assert run([pick: 'add'], 0) == ['add', []]
        assert run([pick: null], 3) == [null, ['choice']]
        assert run([pick: 'add'], 3) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], 3) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], 3) == [null, ['choice', 'confirm']]
    }

    // Structure distance: "this helper's summary" = exact name or "name (N)", N >= 2, never a prefix.
    // add -> add -> add numbering; a user-renamed annotation survives; Replace keeps child objects.
    void testStructureDistanceOwnSetNamingNeverMatchesByPrefix() {
        String f = 'yardimci-yapi-uzaklik.groovy'
        def h = new PathObjectHierarchy()
        def base = 'Yapıya Uzaklık Özet'
        shell([h: h, base: base]).evaluate(ownSetCode(f) + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
''')
        def mine = ann(base + ' notlarım')
        h.addObjects([ann(base), mine, ann(base + ' (1)', 200), ann(base + ' (x)', 400), ann('Başka', 600)])
        shell([h: h, base: base]).evaluate(ownSetCode(f) + '''
assert prevOwnObjects(h, base).size() == 1
assert nextOwnSetName(h, base) == base + ' (2)'
assert !isOwnSetName(base + ' notlarım', base)
assert !isOwnSetName(base + ' (1)', base)
assert !isOwnSetName(base + ' (x)', base)
assert !isOwnSetName(null, base)
''')
        h.addObjects([ann(base + ' (2)', 800)])
        shell([h: h, base: base]).evaluate(ownSetCode(f) + "assert nextOwnSetName(h, base) == base + ' (3)'")
        h.addObjects([ann(base + ' (5)', 1000)])
        def prev = shell([h: h, base: base]).evaluate(ownSetCode(f) + '''
assert nextOwnSetName(h, base) == base + ' (6)'
prevOwnObjects(h, base)
''')
        assert prev.size() == 3
        // Replace as the script performs it: own summaries only, children kept.
        def own = h.getAnnotationObjects().find { it.getName() == base }
        def child = det('Tümör', 10)
        own.addChildObject(child)
        h.removeObjects(prev, true)
        assert h.getAnnotationObjects().contains(mine)
        assert h.getAnnotationObjects().size() == 4
        assert h.getDetectionObjects().contains(child)
        assert child.getParent() != own
    }

    // The question: none without an earlier result; Add is the default and deletes nothing; Replace
    // needs a second confirmation; Cancel (null, or "no" at the confirmation) writes nothing.
    void testStructureDistanceAskExistingDecisions() {
        String f = 'yardimci-yapi-uzaklik.groovy'
        def withPrev = new PathObjectHierarchy()
        withPrev.addObjects([ann('Yapıya Uzaklık Özet'), ann('Yapıya Uzaklık Özet (2)', 200)])
        def run = { Map dlg, hier ->
            def calls = []
            def stub = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            def r = shell([Dialogs: stub, workshopText: { Object... a -> a[0] }, h: hier]).evaluate('''
def MODE_ADD = 'add'
def MODE_REPLACE = 'replace'
String summaryName = 'Yapıya Uzaklık Özet'
String distMeas = 'Yapıya uzaklık'
''' + ownSetCode(f) + '\n' + closure(f, 'askExisting').replaceAll(/@WT:(\w+)@/) { all, n -> "'" + n + "'" } + '''
askExisting(h)''')
            [r, calls]
        }
        assert run([pick: 'add'], new PathObjectHierarchy()) == ['add', []]
        assert run([pick: null], withPrev) == [null, ['choice']]
        assert run([pick: 'add'], withPrev) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], withPrev) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], withPrev) == [null, ['choice', 'confirm']]
    }

    // Source contract: removal only inside the Replace branch, the question precedes the computation,
    // Cancel returns first, the unknown `mevcut` value and an earlier result both stop a headless run.
    void testStructureDistanceAsksBeforeRemoving() {
        String f = 'yardimci-yapi-uzaklik.groovy'
        String s = source(f)
        assert s.count('removeObjects(') == 1
        assert !s.contains('clearAllObjects') && !s.contains('clearDetections') && !s.contains('removeObjectsAndDescendants')
        int rep = s.indexOf('if (writeMode == MODE_REPLACE) {')
        int rem = s.indexOf('QP.removeObjects(prevNow, true)')
        assert rep >= 0 && rem > rep && rem - rep < 250
        assert rem < s.indexOf('QP.addObjects([summary])')
        int calc = s.indexOf('DistanceTools.centroidToBoundsDistance2D(')
        int ask = s.indexOf('writeMode = askExisting(imgHierarchy)')
        assert ask > 0 && calc > ask
        int cancel = s.indexOf('if (writeMode == null) { println "İptal edildi."; return }', ask)
        assert cancel > ask && cancel < calc
        assert s.contains('!isOwnSetName(it.getName(), summaryName)')   // an earlier summary is never a target
        String askSrc = closure(f, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        int hl = s.indexOf('String mevcutArg = null')
        assert hl >= 0
        int bad = s.indexOf("['ekle', 'degistir'].contains(mevcutArg)", hl)
        assert bad > hl && bad < s.indexOf('def imageData = QP.getCurrentImageData()')   // unknown value fails closed first
        int stop = s.indexOf('if (mevcutArg == null) {', hl)
        assert stop > hl && stop < calc                                                   // earlier result + no argument: stop
        assert s.indexOf("writeMode = (mevcutArg == 'degistir') ? MODE_REPLACE : MODE_ADD", stop) > stop
    }

    // Intersection area: "this helper's summary" = exact name or "name (N)", N >= 2, never a prefix.
    void testIntersectionAreaOwnSetNamingNeverMatchesByPrefix() {
        String f = 'yardimci-kesisim-alani.groovy'
        def h = new PathObjectHierarchy()
        def base = 'Kesişim Alanı Özet'
        shell([h: h, base: base]).evaluate(ownSetCode(f) + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
''')
        def mine = ann(base + ' notlarım')
        h.addObjects([ann(base), mine, ann(base + ' (1)', 200), ann(base + ' (x)', 400), ann('Başka', 600)])
        shell([h: h, base: base]).evaluate(ownSetCode(f) + '''
assert prevOwnObjects(h, base).size() == 1
assert nextOwnSetName(h, base) == base + ' (2)'
assert !isOwnSetName(base + ' notlarım', base) && !isOwnSetName(base + ' (1)', base) && !isOwnSetName(base + ' (x)', base)
assert !isOwnSetName(null, base)
''')
        h.addObjects([ann(base + ' (2)', 800)])
        shell([h: h, base: base]).evaluate(ownSetCode(f) + "assert nextOwnSetName(h, base) == base + ' (3)'")
        h.addObjects([ann(base + ' (5)', 1000)])
        def prev = shell([h: h, base: base]).evaluate(ownSetCode(f) + '''
assert nextOwnSetName(h, base) == base + ' (6)'
prevOwnObjects(h, base)
''')
        assert prev.size() == 3
        def own = h.getAnnotationObjects().find { it.getName() == base }
        def child = det('Tümör', 10)
        own.addChildObject(child)
        h.removeObjects(prev, true)
        assert h.getAnnotationObjects().contains(mine)
        assert h.getAnnotationObjects().size() == 4
        assert h.getDetectionObjects().contains(child)
    }

    void testIntersectionAreaAskExistingDecisions() {
        String f = 'yardimci-kesisim-alani.groovy'
        def withPrev = new PathObjectHierarchy()
        withPrev.addObjects([ann('Kesişim Alanı Özet'), ann('Kesişim Alanı Özet (3)', 200)])
        def run = { Map dlg, hier ->
            def calls = []
            def stub = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            def r = shell([Dialogs: stub, workshopText: { Object... a -> a[0] }, h: hier]).evaluate('''
def MODE_ADD = 'add'
def MODE_REPLACE = 'replace'
String summaryName = 'Kesişim Alanı Özet'
''' + ownSetCode(f) + '\n' + closure(f, 'askExisting').replaceAll(/@WT:(\w+)@/) { all, n -> "'" + n + "'" } + '''
askExisting(h, 'Kesişen B alanı', 'Kesişen B oranı')''')
            [r, calls]
        }
        assert run([pick: 'add'], new PathObjectHierarchy()) == ['add', []]
        assert run([pick: null], withPrev) == [null, ['choice']]
        assert run([pick: 'add'], withPrev) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], withPrev) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], withPrev) == [null, ['choice', 'confirm']]
    }

    void testIntersectionAreaAsksBeforeRemoving() {
        String f = 'yardimci-kesisim-alani.groovy'
        String s = source(f)
        assert s.count('removeObjects(') == 1
        assert !s.contains('clearAllObjects') && !s.contains('clearDetections') && !s.contains('removeObjectsAndDescendants')
        int rep = s.indexOf('if (writeMode == MODE_REPLACE) {')
        int rem = s.indexOf('QP.removeObjects(prevNow, true)')
        assert rep >= 0 && rem > rep && rem - rep < 250
        assert rem < s.indexOf('QP.addObjects([summary])')
        int calc = s.indexOf('a.measurements[measArea] = interUm2')          // first write to the user's A annotations
        int ask = s.indexOf('writeMode = askExisting(imgHierarchy, measArea, measPct)')
        assert ask > 0 && calc > ask
        int cancel = s.indexOf('if (writeMode == null) { println "İptal edildi."; return }', ask)
        assert cancel > ask && cancel < calc
        String askSrc = closure(f, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        int hl = s.indexOf('String mevcutArg = null')
        assert hl >= 0
        int bad = s.indexOf("['ekle', 'degistir'].contains(mevcutArg)", hl)
        assert bad > hl && bad < s.indexOf('def imageData = QP.getCurrentImageData()')
        int stop = s.indexOf('if (mevcutArg == null) {', hl)
        assert stop > hl && stop < calc
        assert s.indexOf("writeMode = (mevcutArg == 'degistir') ? MODE_REPLACE : MODE_ADD", stop) > stop
    }

    static String peritumoralBandCode(String f) {
        'String BAND_CLASS = "Peritümöral bant"\n' +
            ['bandSetIndex', 'prevOwnBands', 'nextBandRunIndex'].collect { closure(f, it) }.join('\n') + '\n'
    }

    // Peritumoral band: this helper's band = class + its own name pattern ("<source> · dış bant 50µm" with an
    // optional " (N)", N >= 2) + the two measurements it writes. A same-class band the user drew, renamed,
    // or edited down to no measurements is never ours. add -> add -> add numbering; Replace keeps children.
    void testPeritumoralBandOwnSetNeverMatchesByPrefixOrClassOnly() {
        String f = 'yardimci-peritumoral-bant.groovy'
        String u = 'µ'
        String code = peritumoralBandCode(f)
        def mk = { String nm, double x, boolean classed = true, boolean meas = true ->
            def a = ann(nm, x)
            if (classed) a.setPathClass(PathClass.fromString('Peritümöral bant'))
            if (meas) {
                a.getMeasurements().put('Bant alanı (' + u + 'm²)', 1d)
                a.getMeasurements().put('Bant genişliği (' + u + 'm)', 50d)
            }
            a
        }
        String n1 = 'Tümör · dış bant 50' + u + 'm'
        shell([:]).evaluate(code + '''
String n1 = 'Tümör · dış bant 50µm'
assert bandSetIndex(n1) == 1
assert bandSetIndex(n1 + ' (2)') == 2 && bandSetIndex(n1 + ' (12)') == 12
assert bandSetIndex(n1 + ' (1)') == 0 && bandSetIndex(n1 + ' (x)') == 0 && bandSetIndex(n1 + ' (0)') == 0
assert bandSetIndex(n1 + ' notlarım') == 0 && bandSetIndex('Bant') == 0 && bandSetIndex('Tümör · dış bant') == 0
assert bandSetIndex('Kenar · iç bant 20µm') == 1 && bandSetIndex('Kenar · kenar bandı 100µm (3)') == 3
assert bandSetIndex(null) == 0
assert prevOwnBands(null).isEmpty()
''')
        def h = new PathObjectHierarchy()
        assert shell([h: h]).evaluate(code + 'nextBandRunIndex(h)') == 1
        def own1 = mk(n1, 0)
        def own1b = mk('Bez · dış bant 50' + u + 'm', 200)
        def renamed = mk(n1 + ' notlarım', 400)
        def noClass = mk(n1, 600, false)
        def noMeas = mk(n1, 800, true, false)
        def other = ann('Başka', 1000)
        h.addObjects([own1, own1b, renamed, noClass, noMeas, other])
        def prev = shell([h: h]).evaluate(code + 'prevOwnBands(h)')
        assert prev as Set == ([own1, own1b] as Set)
        assert shell([h: h]).evaluate(code + 'nextBandRunIndex(h)') == 2
        def set2 = mk(n1 + ' (2)', 1200)
        h.addObjects([set2])
        assert shell([h: h]).evaluate(code + 'nextBandRunIndex(h)') == 3
        def set5 = mk(n1 + ' (5)', 1400)
        h.addObjects([set5])
        assert shell([h: h]).evaluate(code + 'nextBandRunIndex(h)') == 6
        // Replace as the script performs it: own bands only (all sources), children kept.
        def all = shell([h: h]).evaluate(code + 'prevOwnBands(h)')
        assert all as Set == ([own1, own1b, set2, set5] as Set)
        def child = det('Tümör', 10)
        own1.addChildObject(child)
        h.removeObjects(all, true)
        assert h.getAnnotationObjects() as Set == ([renamed, noClass, noMeas, other] as Set)
        assert h.getDetectionObjects().contains(child)
    }

    void testPeritumoralBandAskExistingDecisions() {
        String f = 'yardimci-peritumoral-bant.groovy'
        String u = 'µ'
        def withPrev = new PathObjectHierarchy()
        def b = ann('Tümör · dış bant 50' + u + 'm')
        b.setPathClass(PathClass.fromString('Peritümöral bant'))
        b.getMeasurements().put('Bant alanı (' + u + 'm²)', 1d)
        b.getMeasurements().put('Bant genişliği (' + u + 'm)', 50d)
        withPrev.addObjects([b])
        def run = { Map dlg, hier ->
            def calls = []
            def stub = new Expando(
                showChoiceDialog: { t, bd, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, bd -> calls << 'confirm'; dlg.confirm })
            def r = shell([Dialogs: stub, workshopText: { Object... a -> a[0] }, h: hier]).evaluate('''
def MODE_ADD = 'add'
def MODE_REPLACE = 'replace'
''' + peritumoralBandCode(f) + closure(f, 'askExisting').replaceAll(/@WT:(\w+)@/) { all, n -> "'" + n + "'" } + '''
askExisting(h)''')
            [r, calls]
        }
        assert run([pick: 'add'], new PathObjectHierarchy()) == ['add', []]
        assert run([pick: null], withPrev) == [null, ['choice']]
        assert run([pick: 'add'], withPrev) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], withPrev) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], withPrev) == [null, ['choice', 'confirm']]
    }

    void testPeritumoralBandAsksBeforeRemoving() {
        String f = 'yardimci-peritumoral-bant.groovy'
        String s = source(f)
        assert s.count('removeObjects(') == 1
        assert !s.contains('clearAllObjects') && !s.contains('clearDetections') && !s.contains('removeObjectsAndDescendants')
        assert !s.contains('def stale = ')                       // no class-only sweep any more
        int rep = s.indexOf('if (writeMode == MODE_REPLACE) {')
        int rem = s.indexOf('QP.removeObjects(prevNow, true)')
        assert rep >= 0 && rem > rep && rem - rep < 250
        int add = s.indexOf('QP.addObjects(created)')
        assert rem < add
        int calc = s.indexOf('targets.eachWithIndex { t, i ->')
        int ask = s.indexOf('writeMode = askExisting(imgHierarchy)')
        assert ask > 0 && calc > ask
        int cancel = s.indexOf('if (writeMode == null) { println "İptal edildi."; return }', ask)
        assert cancel > ask && cancel < calc
        assert s.indexOf('+ runSuffix)') > calc                 // new bands carry the set number
        String askSrc = closure(f, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        int hl = s.indexOf('String mevcutArg = null')
        assert hl >= 0
        int bad = s.indexOf("['ekle', 'degistir'].contains(mevcutArg)", hl)
        assert bad > hl && bad < s.indexOf('def imageData = QP.getCurrentImageData()')
        int stop = s.indexOf('if (mevcutArg == null) {', hl)
        assert stop > hl && stop < calc
        assert s.indexOf("writeMode = (mevcutArg == 'degistir') ? MODE_REPLACE : MODE_ADD", stop) > stop
    }

    // Convex hull: this helper's hull = exact name (or "name (N)", N >= 2) AND the hull class. A user's
    // unclassed annotation with the same name, a renamed hull, or another source's hull is never ours.
    void testConvexHullOwnSetNeverMatchesByPrefixOrOtherClass() {
        String f = 'yardimci-konveks-zarf.groovy'
        String hullClass = 'Konveks Zarf'
        String base = 'Konveks Zarf · seçili'
        def mk = { String nm, double x, boolean classed ->
            def a = ann(nm, x)
            if (classed) a.setPathClass(PathClass.fromString(hullClass))
            a
        }
        def h = new PathObjectHierarchy()
        String code = 'String HULL_CLASS = "' + hullClass + '"\n' + ownSetCode(f) + '\n' + closure(f, 'prevHullObjects') + '\n'
        shell([h: h, base: base]).evaluate(code + '''
assert prevHullObjects(h, base).isEmpty()
assert prevHullObjects(null, base).isEmpty()
assert nextOwnSetName(h, base) == base
''')
        def own1 = mk(base, 0, true)
        def renamed = mk(base + ' notlarım', 200, true)
        def unclassed = mk(base, 400, false)
        def otherSource = mk('Konveks Zarf · Tümör', 600, true)
        def bad1 = mk(base + ' (1)', 800, true)
        h.addObjects([own1, renamed, unclassed, otherSource, bad1])
        def prev1 = shell([h: h, base: base]).evaluate(code + 'prevHullObjects(h, base)')
        assert prev1 == [own1]
        // Add -> add: next names are numbered; the unclassed same-name object only ever raises the number.
        def own2 = mk(base + ' (2)', 1000, true)
        h.addObjects([own2])
        assert shell([h: h, base: base]).evaluate(code + 'nextOwnSetName(h, base)') == base + ' (3)'
        def own5 = mk(base + ' (5)', 1200, true)
        h.addObjects([own5])
        assert shell([h: h, base: base]).evaluate(code + 'nextOwnSetName(h, base)') == base + ' (6)'
        def prev = shell([h: h, base: base]).evaluate(code + 'prevHullObjects(h, base)')
        assert prev as Set == [own1, own2, own5] as Set
        // Replace as the script performs it: only those, children kept.
        def child = det('Tümör', 10)
        own1.addChildObject(child)
        h.removeObjects(prev, true)
        assert h.getAnnotationObjects() as Set == [renamed, unclassed, otherSource, bad1] as Set
        assert h.getDetectionObjects().contains(child)
    }

    void testConvexHullAskExistingDecisions() {
        String f = 'yardimci-konveks-zarf.groovy'
        String base = 'Konveks Zarf · seçili'
        def withPrev = new PathObjectHierarchy()
        def a1 = ann(base); a1.setPathClass(PathClass.fromString('Konveks Zarf'))
        withPrev.addObjects([a1])
        def onlyUnclassed = new PathObjectHierarchy()
        onlyUnclassed.addObjects([ann(base)])
        def run = { Map dlg, hier ->
            def calls = []
            def stub = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            def r = shell([Dialogs: stub, workshopText: { Object... a -> a[0] }, h: hier, base: base]).evaluate('''
def MODE_ADD = 'add'
def MODE_REPLACE = 'replace'
String HULL_CLASS = 'Konveks Zarf'
''' + ownSetCode(f) + '\n' + closure(f, 'prevHullObjects') + '\n' + closure(f, 'askExisting').replaceAll(/@WT:(\w+)@/) { all, n -> "'" + n + "'" } + '''
askExisting(h, base)''')
            [r, calls]
        }
        assert run([pick: 'add'], new PathObjectHierarchy()) == ['add', []]
        assert run([pick: 'add'], onlyUnclassed) == ['add', []]            // a user's unclassed look-alike raises no question
        assert run([pick: null], withPrev) == [null, ['choice']]
        assert run([pick: 'add'], withPrev) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], withPrev) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], withPrev) == [null, ['choice', 'confirm']]
    }

    void testConvexHullAsksBeforeRemoving() {
        String f = 'yardimci-konveks-zarf.groovy'
        String s = source(f)
        assert s.count('removeObjects(') == 1
        assert !s.contains('clearAllObjects') && !s.contains('clearDetections') && !s.contains('removeObjectsAndDescendants')
        int rep = s.indexOf('if (writeMode == MODE_REPLACE) {')
        int rem = s.indexOf('QP.removeObjects(prevNow, true)')
        assert rep >= 0 && rem > rep && rem - rep < 250
        assert rem < s.indexOf('QP.addObjects([hullAnno])')
        int calc = s.indexOf('def union = (geoms.size() == 1)')
        int ask = s.indexOf('writeMode = askExisting(imgHierarchy, hullName)')
        assert ask > 0 && calc > ask
        int cancel = s.indexOf('if (writeMode == null) { println "İptal edildi."; return }', ask)
        assert cancel > ask && cancel < calc
        String askSrc = closure(f, 'askExisting')
        assert askSrc.contains('prevHullObjects(hierarchy, base)')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        assert !s.contains('def stale = ')
        int hl = s.indexOf('String mevcutArg = null')
        assert hl >= 0
        int bad = s.indexOf("['ekle', 'degistir'].contains(mevcutArg)", hl)
        assert bad > hl && bad < s.indexOf('def imageData = QP.getCurrentImageData()')
        int stop = s.indexOf('if (mevcutArg == null) {', hl)
        assert stop > hl && stop < calc
        assert s.indexOf("writeMode = (mevcutArg == 'degistir') ? MODE_REPLACE : MODE_ADD", stop) > stop
    }

    static String centrePeripheryCode(String f) {
        'def REGION_CLASSES = ["Merkez (eşit alanlı)", "Çevre (eşit alanlı)", "Ara bölge (eşit alanlı)"]\n' +
            ['regionSetIndex', 'prevOwnRegions', 'nextRegionRunIndex'].collect { closure(f, it) }.join('\n') + '\n'
    }

    // Centre/periphery: this helper's region = one of its three classes + its own name pattern
    // ("<source> · Merkez (2/2)" with an optional " (N)", N >= 2) + the two measurements it writes.
    // The "(i/n)" ordinal is never mistaken for a set number. add -> add -> add numbering; Replace keeps children.
    void testCentrePeripheryOwnSetNeverMatchesByPrefixOrClassOnly() {
        String f = 'yardimci-merkez-cevre.groovy'
        String u = 'µ'
        String code = centrePeripheryCode(f)
        def mk = { String nm, double x, String cls = 'Merkez (eşit alanlı)', boolean meas = true ->
            def a = ann(nm, x)
            if (cls != null) a.setPathClass(PathClass.fromString(cls))
            if (meas) {
                a.getMeasurements().put('Bölge alanı (' + u + 'm²)', 1d)
                a.getMeasurements().put('Bölge sırası (dıştan)', 2d)
            }
            a
        }
        shell([:]).evaluate(code + '''
assert regionSetIndex('Tümör · Merkez (2/2)') == 1
assert regionSetIndex('Tümör · Çevre (1/2)') == 1 && regionSetIndex('Tümör · Ara 1 (2/3)') == 1
assert regionSetIndex('Tümör · Merkez (2/2) (2)') == 2 && regionSetIndex('Tümör · Merkez (2/2) (12)') == 12
assert regionSetIndex('Tümör · Merkez (2/2) (1)') == 0 && regionSetIndex('Tümör · Merkez (2/2) (x)') == 0
assert regionSetIndex('Tümör · Merkez') == 0 && regionSetIndex('Tümör · Merkez (2/2) notlarım') == 0
assert regionSetIndex('Merkez (2/2)') == 0
assert regionSetIndex(null) == 0
assert prevOwnRegions(null).isEmpty()
''')
        def h = new PathObjectHierarchy()
        assert shell([h: h]).evaluate(code + 'nextRegionRunIndex(h)') == 1
        def own1 = mk('Tümör · Merkez (2/2)', 0)
        def own1b = mk('Tümör · Çevre (1/2)', 200, 'Çevre (eşit alanlı)')
        def renamed = mk('Tümör · Merkez (2/2) notlarım', 400)
        def noClass = mk('Tümör · Merkez (2/2)', 600, null)
        def wrongClass = mk('Tümör · Merkez (2/2)', 700, 'Tümör')
        def noMeas = mk('Tümör · Merkez (2/2)', 800, 'Merkez (eşit alanlı)', false)
        def other = ann('Başka', 1000)
        h.addObjects([own1, own1b, renamed, noClass, wrongClass, noMeas, other])
        def prev = shell([h: h]).evaluate(code + 'prevOwnRegions(h)')
        assert prev as Set == ([own1, own1b] as Set)
        assert shell([h: h]).evaluate(code + 'nextRegionRunIndex(h)') == 2
        def set2 = mk('Tümör · Merkez (2/2) (2)', 1200)
        h.addObjects([set2])
        assert shell([h: h]).evaluate(code + 'nextRegionRunIndex(h)') == 3
        def set5 = mk('Tümör · Çevre (1/2) (5)', 1400, 'Çevre (eşit alanlı)')
        h.addObjects([set5])
        assert shell([h: h]).evaluate(code + 'nextRegionRunIndex(h)') == 6
        def all = shell([h: h]).evaluate(code + 'prevOwnRegions(h)')
        assert all as Set == ([own1, own1b, set2, set5] as Set)
        def child = det('Tümör', 10)
        own1.addChildObject(child)
        h.removeObjects(all, true)
        assert h.getAnnotationObjects() as Set == ([renamed, noClass, wrongClass, noMeas, other] as Set)
        assert h.getDetectionObjects().contains(child)
    }

    void testCentrePeripheryAskExistingDecisions() {
        String f = 'yardimci-merkez-cevre.groovy'
        String u = 'µ'
        def withPrev = new PathObjectHierarchy()
        def r1 = ann('Tümör · Merkez (2/2)')
        r1.setPathClass(PathClass.fromString('Merkez (eşit alanlı)'))
        r1.getMeasurements().put('Bölge alanı (' + u + 'm²)', 1d)
        r1.getMeasurements().put('Bölge sırası (dıştan)', 2d)
        withPrev.addObjects([r1])
        def run = { Map dlg, hier ->
            def calls = []
            def stub = new Expando(
                showChoiceDialog: { t, bd, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, bd -> calls << 'confirm'; dlg.confirm })
            def r = shell([Dialogs: stub, workshopText: { Object... a -> a[0] }, h: hier]).evaluate('''
def MODE_ADD = 'add'
def MODE_REPLACE = 'replace'
''' + centrePeripheryCode(f) + closure(f, 'askExisting').replaceAll(/@WT:(\w+)@/) { all, n -> "'" + n + "'" } + '''
askExisting(h)''')
            [r, calls]
        }
        assert run([pick: 'add'], new PathObjectHierarchy()) == ['add', []]
        assert run([pick: null], withPrev) == [null, ['choice']]
        assert run([pick: 'add'], withPrev) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], withPrev) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], withPrev) == [null, ['choice', 'confirm']]
    }

    void testCentrePeripheryAsksBeforeRemoving() {
        String f = 'yardimci-merkez-cevre.groovy'
        String s = source(f)
        assert s.count('removeObjects(') == 1
        assert !s.contains('clearAllObjects') && !s.contains('clearDetections') && !s.contains('removeObjectsAndDescendants')
        assert !s.contains('def stale = ')                       // no class-only sweep any more
        int rep = s.indexOf('if (writeMode == MODE_REPLACE) {')
        int rem = s.indexOf('QP.removeObjects(prevNow, true)')
        assert rep >= 0 && rem > rep && rem - rep < 250
        int add = s.indexOf('QP.addObjects(created)')
        assert rem < add
        int calc = s.indexOf('targets.eachWithIndex { t, si ->')
        int ask = s.indexOf('writeMode = askExisting(imgHierarchy)')
        assert ask > 0 && calc > ask
        int cancel = s.indexOf('if (writeMode == null) { println "İptal edildi."; return }', ask)
        assert cancel > ask && cancel < calc
        assert s.indexOf('+ runSuffix)') > calc
        String askSrc = closure(f, 'askExisting')
        assert askSrc.contains('if (prev.isEmpty()) return MODE_ADD')
        assert askSrc.contains('if (pick == null) return null')
        assert askSrc.contains('return ok ? MODE_REPLACE : null')
        int hl = s.indexOf('String mevcutArg = null')
        assert hl >= 0
        int bad = s.indexOf("['ekle', 'degistir'].contains(mevcutArg)", hl)
        assert bad > hl && bad < s.indexOf('def imageData = QP.getCurrentImageData()')
        int stop = s.indexOf('if (mevcutArg == null) {', hl)
        assert stop > hl && stop < calc
        assert s.indexOf("writeMode = (mevcutArg == 'degistir') ? MODE_REPLACE : MODE_ADD", stop) > stop
    }


    static final String KI67H = 'yardimci-ki67-heterojenlik.groovy'

    static String ki67hClosures() {
        ['isOwnSetName', 'ownSetIndex', 'prevOwnObjects', 'nextOwnSetName', 'isOwnTileName', 'tileSetIndex',
         'prevOwnGridObjects', 'gridSetIndexes', 'nextGridSetIndex', 'setSuffix'].collect { closure(KI67H, it) }.join('\n')
    }

    // Own result = "Ki-67 grid r,c" / "Ki-67 grid (N) r,c" tiles and "Ki-67 Heterojenlik Özet" / "(N)" summaries,
    // N >= 2, exact shape only. Add -> add -> add numbering; user-renamed annotations never match.
    void testKi67HeterogeneityOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def vars = [h: h, G: 'Ki-67 grid', S: 'Ki-67 Heterojenlik Özet']
        shell(vars).evaluate(ki67hClosures() + '''
assert prevOwnGridObjects(null, G, S).isEmpty()
assert nextGridSetIndex(h, G, S) == 1
assert setSuffix(1) == '' && setSuffix(2) == ' (2)'
''')
        def mineTile = ann('Ki-67 grid notlarım', 900)
        def mineSum = ann('Ki-67 Heterojenlik Özet düzeltmelerim', 1000)
        def mineNum = ann('Ki-67 grid 0,0 kopya', 1100)
        h.addObjects([ann('Ki-67 grid 0,0'), ann('Ki-67 grid 1,2', 100), ann('Ki-67 Heterojenlik Özet', 200),
                      mineTile, mineSum, mineNum, ann('Ki-67 grid (1) 0,0'), ann('Ki-67 grid (x) 0,0'),
                      ann('Ki-67 Heterojenlik Özet (1)'), ann('Başka'), ann(null)])
        shell(vars).evaluate(ki67hClosures() + '''
assert prevOwnGridObjects(h, G, S).size() == 3
assert gridSetIndexes(h, G, S) == [1]
assert nextGridSetIndex(h, G, S) == 2
assert isOwnTileName('Ki-67 grid 12,3', G) && isOwnTileName('Ki-67 grid (2) 0,0', G) && isOwnTileName('Ki-67 grid (13) 4,5', G)
assert !isOwnTileName('Ki-67 grid (1) 0,0', G) && !isOwnTileName('Ki-67 grid (x) 0,0', G) && !isOwnTileName('Ki-67 grid', G)
assert !isOwnTileName('Ki-67 grid 0,0 kopya', G) && !isOwnTileName('Ki-67 grid notlarım', G) && !isOwnTileName(null, G)
assert !isOwnSetName('Ki-67 Heterojenlik Özet düzeltmelerim', S) && !isOwnSetName(null, S)
assert tileSetIndex('Ki-67 grid 0,0', G) == 1 && tileSetIndex('Ki-67 grid (4) 0,0', G) == 4
''')
        h.addObjects([ann('Ki-67 grid (2) 0,0', 300), ann('Ki-67 grid (2) 0,1', 400), ann('Ki-67 Heterojenlik Özet (2)', 500)])
        shell(vars).evaluate(ki67hClosures() + '''
assert prevOwnGridObjects(h, G, S).size() == 6
assert gridSetIndexes(h, G, S) == [1, 2]
assert nextGridSetIndex(h, G, S) == 3
assert nextOwnSetName(h, S) == S + ' (3)'
''')
        // tiles alone (summary deleted by the user) still count as an earlier result
        def h2 = new PathObjectHierarchy()
        h2.addObjects([ann('Ki-67 grid (3) 0,0')])
        shell([h: h2, G: 'Ki-67 grid', S: 'Ki-67 Heterojenlik Özet']).evaluate(ki67hClosures() + '''
assert prevOwnGridObjects(h, G, S).size() == 1
assert nextGridSetIndex(h, G, S) == 4
''')
        assert h.getAnnotationObjects().containsAll([mineTile, mineSum, mineNum])
    }

    // Replace as the script performs it: removeObjects(own, true) keeps the objects inside the tiles and never
    // touches user-named annotations; Add removes nothing.
    void testKi67HeterogeneityReplaceKeepsChildrenAndUserObjects() {
        def h = new PathObjectHierarchy()
        def tile = ann('Ki-67 grid 0,0'), sum = ann('Ki-67 Heterojenlik Özet', 200), mine = ann('Ki-67 grid notlarım', 400)
        h.addObjects([tile, sum, mine])
        def inner = det('Tumor', 10); tile.addChildObject(inner)
        def removed = shell([h: h, G: 'Ki-67 grid', S: 'Ki-67 Heterojenlik Özet']).evaluate(ki67hClosures() + '''
def prevNow = prevOwnGridObjects(h, G, S)
h.removeObjects(prevNow, true)
prevNow.size()
''')
        assert removed == 2
        assert h.getAnnotationObjects().toSet() == [mine] as Set
        assert h.getDetectionObjects().contains(inner)
        assert inner.getParent() != tile
    }

    // Decisions of the question: no earlier result -> add without asking; Cancel -> null; Replace needs a confirm.
    void testKi67HeterogeneityAskExistingDecisions() {
        def run = { Map dlg, int n ->
            def calls = []
            def h = new PathObjectHierarchy()
            (0..<n).each { h.addObjects([ann('Ki-67 grid 0,' + it, 100 * it)]) }
            def Dialogs = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            String code = ki67hClosures() + '\n' + closure(KI67H, 'askExisting')
            code = code.replaceAll(/@WT:(\w+)@/) { all, name -> "'" + name + "'" }
            def r = shell([workshopText: { Object... a -> a[0] }, Dialogs: Dialogs, h: h, GRID_BASE: 'Ki-67 grid', SUMMARY_BASE: 'Ki-67 Heterojenlik Özet',
                           MODE_ADD: 'add', MODE_REPLACE: 'replace']).evaluate(code + '\naskExisting(h)')
            [r, calls]
        }
        assert run([pick: 'add'], 0) == ['add', []]
        assert run([pick: null], 3) == [null, ['choice']]
        assert run([pick: 'add'], 3) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], 3) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], 3) == [null, ['choice', 'confirm']]
    }

    // Source contract: the only removal sits in the Replace branch after the new objects exist, the question
    // precedes the computation, Cancel returns first, and headless mode fails closed.
    void testKi67HeterogeneityAsksBeforeRemoving() {
        String s = source(KI67H)
        assert s.count('removeObjects(') == 1
        assert !s.contains('QP.removeObjects') && !s.contains('clearAllObjects') && !s.contains('clearDetections')
        int rep = s.indexOf('if (writeMode == MODE_REPLACE) {\n    def prevNow')
        int rem = s.indexOf('hierarchy.removeObjects(prevNow, true)')
        assert rep >= 0 && rem > rep && rem - rep < 250
        assert rem > s.indexOf('summary.setLocked(true)')          // removal only after the new objects exist
        assert rem < s.indexOf('hierarchy.addObjects(newTiles)')
        int ask = s.indexOf('writeMode = askExisting(hierarchy)')
        assert ask > 0
        assert s.indexOf('if (writeMode == null) return', ask) in (ask + 1)..(ask + 80)
        assert ask < s.indexOf('def tileTotal = new int[')          // asked before the computation
        assert ask < s.indexOf('hierarchy.addObjects(newTiles)')
        String ac = closure(KI67H, 'askExisting')
        assert ac.contains('if (prev.isEmpty()) return MODE_ADD')
        assert ac.contains('if (pick == null) return null')
        assert ac.contains('return ok ? MODE_REPLACE : null')
        // headless: unknown value fails closed first; an earlier result without mevcut stops before computing
        int bad = s.indexOf("!['ekle', 'degistir'].contains(mevcutArg)")
        assert bad > 0 && bad < s.indexOf('def imageData = QP.getCurrentImageData()')
        int stop = s.indexOf('if (mevcutArg == null) {')
        assert stop > 0 && stop < ask
        assert s.indexOf('return', stop) < s.indexOf('writeMode = (mevcutArg', stop)
        assert s.contains("writeMode = (mevcutArg == 'degistir') ? MODE_REPLACE : MODE_ADD")
    }


    static final String DENSITY_MAP = 'yardimci-yogunluk-haritasi.groovy'

    static String densityMapClosures() {
        ['isOwnSetName', 'ownSetIndex', 'prevOwnObjects', 'nextOwnSetName', 'isOwnTileName', 'tileSetIndex',
         'prevOwnGridObjects', 'gridSetIndexes', 'nextGridSetIndex', 'setSuffix'].collect { closure(DENSITY_MAP, it) }.join('\n')
    }

    // Own result = "Yoğunluk grid r,c" / "Yoğunluk grid (N) r,c" tiles and "Yoğunluk Özet" / "(N)" summaries,
    // N >= 2, exact shape only. Add -> add -> add numbering; user-renamed annotations never match.
    void testDensityMapOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def vars = [h: h, G: 'Yoğunluk grid', S: 'Yoğunluk Özet']
        shell(vars).evaluate(densityMapClosures() + '''
assert prevOwnGridObjects(null, G, S).isEmpty()
assert nextGridSetIndex(h, G, S) == 1
assert setSuffix(1) == '' && setSuffix(2) == ' (2)'
''')
        def mineTile = ann('Yoğunluk grid notlarım', 900)
        def mineSum = ann('Yoğunluk Özet düzeltmelerim', 1000)
        def mineNum = ann('Yoğunluk grid 0,0 kopya', 1100)
        h.addObjects([ann('Yoğunluk grid 0,0'), ann('Yoğunluk grid 1,2', 100), ann('Yoğunluk Özet', 200),
                      mineTile, mineSum, mineNum, ann('Yoğunluk grid (1) 0,0'), ann('Yoğunluk grid (x) 0,0'),
                      ann('Yoğunluk Özet (1)'), ann('Başka'), ann(null)])
        shell(vars).evaluate(densityMapClosures() + '''
assert prevOwnGridObjects(h, G, S).size() == 3
assert gridSetIndexes(h, G, S) == [1]
assert nextGridSetIndex(h, G, S) == 2
assert isOwnTileName('Yoğunluk grid 12,3', G) && isOwnTileName('Yoğunluk grid (2) 0,0', G) && isOwnTileName('Yoğunluk grid (13) 4,5', G)
assert !isOwnTileName('Yoğunluk grid (1) 0,0', G) && !isOwnTileName('Yoğunluk grid (x) 0,0', G) && !isOwnTileName('Yoğunluk grid', G)
assert !isOwnTileName('Yoğunluk grid 0,0 kopya', G) && !isOwnTileName('Yoğunluk grid notlarım', G) && !isOwnTileName(null, G)
assert !isOwnSetName('Yoğunluk Özet düzeltmelerim', S) && !isOwnSetName(null, S)
assert tileSetIndex('Yoğunluk grid 0,0', G) == 1 && tileSetIndex('Yoğunluk grid (4) 0,0', G) == 4
''')
        h.addObjects([ann('Yoğunluk grid (2) 0,0', 300), ann('Yoğunluk grid (2) 0,1', 400), ann('Yoğunluk Özet (2)', 500)])
        shell(vars).evaluate(densityMapClosures() + '''
assert prevOwnGridObjects(h, G, S).size() == 6
assert gridSetIndexes(h, G, S) == [1, 2]
assert nextGridSetIndex(h, G, S) == 3
assert nextOwnSetName(h, S) == S + ' (3)'
''')
        // tiles alone (summary deleted by the user) still count as an earlier result
        def h2 = new PathObjectHierarchy()
        h2.addObjects([ann('Yoğunluk grid (3) 0,0')])
        shell([h: h2, G: 'Yoğunluk grid', S: 'Yoğunluk Özet']).evaluate(densityMapClosures() + '''
assert prevOwnGridObjects(h, G, S).size() == 1
assert nextGridSetIndex(h, G, S) == 4
''')
        assert h.getAnnotationObjects().containsAll([mineTile, mineSum, mineNum])
    }

    // Replace as the script performs it: removeObjects(own, true) keeps the objects inside the tiles and never
    // touches user-named annotations; Add removes nothing.
    void testDensityMapReplaceKeepsChildrenAndUserObjects() {
        def h = new PathObjectHierarchy()
        def tile = ann('Yoğunluk grid 0,0'), sum = ann('Yoğunluk Özet', 200), mine = ann('Yoğunluk grid notlarım', 400)
        h.addObjects([tile, sum, mine])
        def inner = det('Tumor', 10); tile.addChildObject(inner)
        def removed = shell([h: h, G: 'Yoğunluk grid', S: 'Yoğunluk Özet']).evaluate(densityMapClosures() + '''
def prevNow = prevOwnGridObjects(h, G, S)
h.removeObjects(prevNow, true)
prevNow.size()
''')
        assert removed == 2
        assert h.getAnnotationObjects().toSet() == [mine] as Set
        assert h.getDetectionObjects().contains(inner)
        assert inner.getParent() != tile
    }

    // Decisions of the question: no earlier result -> add without asking; Cancel -> null; Replace needs a confirm.
    void testDensityMapAskExistingDecisions() {
        def run = { Map dlg, int n ->
            def calls = []
            def h = new PathObjectHierarchy()
            (0..<n).each { h.addObjects([ann('Yoğunluk grid 0,' + it, 100 * it)]) }
            def Dialogs = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            String code = densityMapClosures() + '\n' + closure(DENSITY_MAP, 'askExisting')
            code = code.replaceAll(/@WT:(\w+)@/) { all, name -> "'" + name + "'" }
            def r = shell([workshopText: { Object... a -> a[0] }, Dialogs: Dialogs, h: h, GRID_BASE: 'Yoğunluk grid', SUMMARY_BASE: 'Yoğunluk Özet',
                           MODE_ADD: 'add', MODE_REPLACE: 'replace']).evaluate(code + '\naskExisting(h)')
            [r, calls]
        }
        assert run([pick: 'add'], 0) == ['add', []]
        assert run([pick: null], 3) == [null, ['choice']]
        assert run([pick: 'add'], 3) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], 3) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], 3) == [null, ['choice', 'confirm']]
    }

    // Source contract: the only removal sits in the Replace branch after the new objects exist, the question
    // precedes the computation, Cancel returns first, and headless mode fails closed.
    void testDensityMapAsksBeforeRemoving() {
        String s = source(DENSITY_MAP)
        assert s.count('removeObjects(') == 1
        assert !s.contains('QP.removeObjects') && !s.contains('clearAllObjects') && !s.contains('clearDetections')
        int rep = s.indexOf('if (writeMode == MODE_REPLACE) {\n    def prevNow')
        int rem = s.indexOf('hierarchy.removeObjects(prevNow, true)')
        assert rep >= 0 && rem > rep && rem - rep < 250
        assert rem > s.indexOf('summary.setLocked(true)')          // removal only after the new objects exist
        assert rem < s.indexOf('hierarchy.addObjects(newTiles)')
        int ask = s.indexOf('writeMode = askExisting(hierarchy)')
        assert ask > 0
        assert s.indexOf('if (writeMode == null) return', ask) in (ask + 1)..(ask + 80)
        assert ask < s.indexOf('int[] tileCount = new int[')          // asked before the computation
        assert ask < s.indexOf('hierarchy.addObjects(newTiles)')
        String ac = closure(DENSITY_MAP, 'askExisting')
        assert ac.contains('if (prev.isEmpty()) return MODE_ADD')
        assert ac.contains('if (pick == null) return null')
        assert ac.contains('return ok ? MODE_REPLACE : null')
        // headless: unknown value fails closed first; an earlier result without mevcut stops before computing
        int bad = s.indexOf("!['ekle', 'degistir'].contains(mevcutArg)")
        assert bad > 0 && bad < s.indexOf('def imageData = QP.getCurrentImageData()')
        int stop = s.indexOf('if (mevcutArg == null) {')
        assert stop > 0 && stop < ask
        assert s.indexOf('return', stop) < s.indexOf('writeMode = (mevcutArg', stop)
        assert s.contains("writeMode = (mevcutArg == 'degistir') ? MODE_REPLACE : MODE_ADD")
    }


    static final String EYEBALL = 'yardimci-goz-karari-kalibrasyon.groovy'
    static final String EYEBALL_GRID = '__GozKarariKalibrasyonIzgarasi__'

    static String eyeballClosures(List<String> extra = []) {
        (['isOwnSetName', 'ownSetIndex', 'prevOwnObjects', 'nextOwnSetName', 'foreignGridObjects'] + extra).collect { closure(EYEBALL, it) }.join('\n')
    }

    // Foreign grid = exact name or "name (N>=2)" that this window did not place; a user-renamed annotation never
    // matches; the window's own tiles are tracked by reference.
    void testEyeballCalibrationOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def own = [ann(EYEBALL_GRID, 0), ann(EYEBALL_GRID, 100)]
        h.addObjects(own)
        shell([h: h, G: EYEBALL_GRID, own: own]).evaluate(eyeballClosures() + '''
assert foreignGridObjects(h, G, own).isEmpty()
assert nextOwnSetName(h, G) == G + ' (2)'
assert prevOwnObjects(null, G).isEmpty()
''')
        def stale = ann(EYEBALL_GRID, 200)
        def stale2 = ann(EYEBALL_GRID + ' (2)', 300)
        def mine = ann(EYEBALL_GRID + ' notlarım', 400)
        h.addObjects([stale, stale2, mine, ann(EYEBALL_GRID + ' (1)', 500), ann(EYEBALL_GRID + ' (x)', 600), ann('Başka', 700)])
        shell([h: h, G: EYEBALL_GRID, own: own, stale: stale, stale2: stale2]).evaluate(eyeballClosures() + '''
assert foreignGridObjects(h, G, own) as Set == [stale, stale2] as Set
assert nextOwnSetName(h, G) == G + ' (3)'
assert !isOwnSetName(G + ' notlarım', G) && !isOwnSetName(G + ' (1)', G) && !isOwnSetName(G + ' (x)', G) && !isOwnSetName(null, G)
''')
        assert h.getAnnotationObjects().contains(mine)
    }

    // removeGrid takes away only the window's OWN tiles by reference, never anything that merely carries the name.
    void testEyeballCalibrationRemoveGridOnlyOwnObjects() {
        def h = new PathObjectHierarchy()
        def own = [ann(EYEBALL_GRID, 0), ann(EYEBALL_GRID, 100)]
        def sameName = ann(EYEBALL_GRID, 200)          // e.g. the user's, or another window's: NOT ours
        def mine = ann('Benim bölgem', 300)
        h.addObjects(own + [sameName, mine])
        def inner = det('Tumor', 10); own[0].addChildObject(inner)
        def qp = new Expando(fireHierarchyUpdate: { hh -> })
        shell([ownGridObjects: new ArrayList(own), gridHierarchy: h, QP: qp]).evaluate(closure(EYEBALL, 'removeGrid') + '\nremoveGrid()')
        assert h.getAnnotationObjects().toSet() == [sameName, mine] as Set
        assert h.getDetectionObjects().contains(inner)        // keepChildren = true
        // calling it again with nothing tracked removes nothing
        shell([ownGridObjects: [], gridHierarchy: h, QP: qp]).evaluate(closure(EYEBALL, 'removeGrid') + '\nremoveGrid()')
        assert h.getAnnotationObjects().size() == 2
    }

    // The question and the grid placement, with the production closures: Add keeps the earlier grid and names the new
    // set "(2)"; Replace removes only the foreign tiles; Cancel places nothing.
    void testEyeballCalibrationAskAndPlaceGrid() {
        def run = { String pick, Boolean confirm ->
            def h = new PathObjectHierarchy()
            def stale = (0..<3).collect { ann(EYEBALL_GRID, 100 * it) }
            def keep = ann('Benim bölgem', 900)
            h.addObjects(stale + [keep])
            def calls = []
            def Dialogs = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; pick == 'rep' ? opts[1] : (pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; confirm })
            def noteRef = new java.util.concurrent.atomic.AtomicReference('')
            def own = []
            String code = '''import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs
import java.util.Locale
''' + eyeballClosures(['removeGrid', 'askExisting', 'addGrid'])
            code = code.replaceAll(/@WT:(\w+)@/) { all, name -> "'" + name + "'" }
            def qp = new Expando(fireHierarchyUpdate: { hh -> })
            def ok = shell([Dialogs: Dialogs, QP: qp, gridHierarchy: h, ownGridObjects: own, acknowledgedForeign: [], gridNoteRef: noteRef,
                            GRID_NAME: EYEBALL_GRID, MODE_ADD: 'add', MODE_REPLACE: 'replace', targetRoi: roi(0), workshopText: { Object... a -> a[0] }]).evaluate(code + '\naddGrid()')
            [ok, calls, h, stale, keep, own, noteRef.get()]
        }
        def (ok, calls, h, stale, keep, own, note) = run('add', null)
        assert ok && calls == ['choice'] && own.size() == 16
        assert h.getAnnotationObjects().containsAll(stale + [keep]) && h.getAnnotationObjects().size() == 3 + 1 + 16
        assert own.every { it.getName() == EYEBALL_GRID + ' (2)' && it.isLocked() }
        assert note
        (ok, calls, h, stale, keep, own, note) = run('rep', true)
        assert ok && calls == ['choice', 'confirm'] && own.size() == 16
        assert h.getAnnotationObjects().size() == 1 + 16 && h.getAnnotationObjects().contains(keep)
        assert note
        assert own.every { it.getName() == EYEBALL_GRID } && stale.every { !h.getAnnotationObjects().contains(it) }
        (ok, calls, h, stale, keep, own, note) = run('rep', false)
        assert !ok && calls == ['choice', 'confirm'] && own.isEmpty() && h.getAnnotationObjects().size() == 4
        (ok, calls, h, stale, keep, own, note) = run(null, null)
        assert !ok && calls == ['choice'] && own.isEmpty() && h.getAnnotationObjects().size() == 4
    }

    // Source contract: nothing is removed by name sweep; the two removals are the own-tiles removal and the
    // Replace branch; the question precedes any placement; Cancel un-ticks the box; the window close removes
    // only own tiles; headless fails closed on an unknown value and never writes.
    void testEyeballCalibrationAsksBeforeRemoving() {
        String s = source(EYEBALL)
        assert s.count('removeObjects(') == 2
        assert !s.contains('QP.removeObjects') && !s.contains('clearAllObjects') && !s.contains('clearDetections')
        assert !s.contains('getName() == GRID_NAME')
        String rg = closure(EYEBALL, 'removeGrid')
        assert rg.contains('ownGridObjects') && rg.contains('gridHierarchy.removeObjects(mine, true)') && !rg.contains('getName()')
        String ag = closure(EYEBALL, 'addGrid')
        int ask = ag.indexOf('gridMode = askExisting(gridHierarchy, foreign)')
        assert ask > 0
        assert ag.indexOf('if (gridMode == null) return false', ask) in (ask + 1)..(ask + 90)
        int rep = ag.indexOf('if (gridMode == MODE_REPLACE) {\n        removedForeign')
        int rem = ag.indexOf('gridHierarchy.removeObjects(foreign, true)')
        assert rep > ask && rem > rep && rem - rep < 200
        assert ask < ag.indexOf('gridHierarchy.addObjects(cells)')
        assert s.contains('if (!addGrid()) { gridCheck.setSelected(false); return }')
        assert s.contains('stage.setOnHidden({\n        removeGrid()')
        int bad = s.indexOf("!['ekle', 'degistir'].contains(mevcutArg)")
        assert bad > 0 && bad < s.indexOf('def imageData = QP.getCurrentImageData()')
        int hl = s.indexOf('if (isHeadless) {\n    println String.format(Locale.US,')
        assert hl > 0 && s.indexOf('return', hl) < s.indexOf('def removeGrid = {')
    }


    static final String HISTOART = 'yardimci-histoart-sihirbaz.groovy'
    static final String HISTOART_SET = 'HistoART KK'

    static String histoArtOwnTileCode() {
        '''def PROB_MEAS = 'HistoART: Artefakt olasılığı'
def CLASS_COLOR = ['Temiz': 1, 'Kan': 2]
''' + closure(HISTOART, 'ownTileDetections')
    }

    // Own result = the sentinel annotation named exactly "HistoART KK" or "HistoART KK (N>=2)". Add -> add -> add
    // numbering; a user-renamed annotation is never part of "our" sets.
    void testHistoArtOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        shell([h: h, base: HISTOART_SET]).evaluate(ownSetCode(HISTOART) + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
''')
        def mine = ann(HISTOART_SET + ' düzeltmelerim', 900)
        h.addObjects([ann(HISTOART_SET), mine, ann(HISTOART_SET + ' (1)', 200), ann(HISTOART_SET + ' (x)', 300), ann('Başka', 400), ann(null, 500)])
        shell([h: h, base: HISTOART_SET]).evaluate(ownSetCode(HISTOART) + '''
assert prevOwnObjects(h, base).size() == 1
assert nextOwnSetName(h, base) == base + ' (2)'
assert !isOwnSetName(base + ' düzeltmelerim', base) && !isOwnSetName(base + ' (1)', base) && !isOwnSetName(base + ' (x)', base)
assert !isOwnSetName(null, base)
''')
        h.addObjects([ann(HISTOART_SET + ' (2)', 600), ann(HISTOART_SET + ' (5)', 700)])
        shell([h: h, base: HISTOART_SET]).evaluate(ownSetCode(HISTOART) + '''
assert prevOwnObjects(h, base).size() == 3
assert nextOwnSetName(h, base) == base + ' (6)'
''')
        assert h.getAnnotationObjects().contains(mine)
    }

    // The sentinel's own tile detections are those carrying the probability measurement or a wizard class;
    // anything else under it (a user's annotation or detection) is not ours.
    void testHistoArtOwnTileDetectionsExcludeUserObjects() {
        def sentinel = ann(HISTOART_SET)
        def t1 = det('Kan', 10); t1.getMeasurements().put('HistoART: Artefakt olasılığı', 0.9d)
        def t2 = det('Temiz', 20)                                  // class only (probability was NaN)
        def t3 = det('Tumor', 30); t3.getMeasurements().put('HistoART: Artefakt olasılığı', 0.1d)  // hand-reclassified, measurement kept
        def userDet = det('Tumor', 40)
        def userAnn = ann('Benim notum', 50)
        [t1, t2, t3, userDet, userAnn].each { sentinel.addChildObject(it) }
        def own = shell([sentinel: sentinel]).evaluate(histoArtOwnTileCode() + '\nownTileDetections(sentinel)')
        assert own as Set == [t1, t2, t3] as Set
    }

    // Replace as the wizard performs it: own tile detections first, then the sets with keepChildren = true;
    // user objects (also those under a set) and user-named annotations survive. Add removes nothing.
    void testHistoArtReplaceKeepsUserObjects() {
        def h = new PathObjectHierarchy()
        def s1 = ann(HISTOART_SET), s2 = ann(HISTOART_SET + ' (2)', 200), mine = ann(HISTOART_SET + ' notlarım', 400)
        h.addObjects([s1, s2, mine])
        def t1 = det('Kan', 10); t1.getMeasurements().put('HistoART: Artefakt olasılığı', 0.9d)
        def t2 = det('Temiz', 210)
        def userDet = det('Tumor', 20)
        s1.addChildObject(t1); s1.addChildObject(userDet); s2.addChildObject(t2)
        def counts = shell([h: h, base: HISTOART_SET]).evaluate(ownSetCode(HISTOART) + '\n' + histoArtOwnTileCode() + '''
def prevSets = prevOwnObjects(h, base)
def prevTiles = prevSets.collectMany { ownTileDetections(it) }
h.removeObjects(prevTiles, true)
h.removeObjects(prevSets, true)
[prevSets.size(), prevTiles.size()]
''')
        assert counts == [2, 2]
        assert h.getAnnotationObjects().toSet() == [mine] as Set
        assert h.getDetectionObjects().toSet() == [userDet] as Set
        assert userDet.getParent() != s1
    }

    // Decisions of the question: no earlier result -> add without asking; Cancel -> null; Replace needs a confirm.
    void testHistoArtAskExistingDecisions() {
        def run = { Map dlg, int n ->
            def calls = []
            def h = new PathObjectHierarchy()
            def sets = (0..<n).collect { i -> ann(i == 0 ? HISTOART_SET : HISTOART_SET + ' (' + (i + 1) + ')', 100 * (i + 1)) }
            if (!sets.isEmpty()) h.addObjects(sets)
            def Dialogs = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            String code = histoArtOwnTileCode() + '\n' + ownSetCode(HISTOART) + '\n' + closure(HISTOART, 'askExisting')
            code = code.replaceAll(/@WT:(\w+)@/) { all, name -> "'" + name + "'" }
            def r = shell([workshopText: { Object... a -> a[0] }, Dialogs: Dialogs, h: h, HISTOART_SENTINEL: HISTOART_SET, MODE_ADD: 'add', MODE_REPLACE: 'replace']).evaluate(code + '\naskExisting(h)')
            [r, calls]
        }
        assert run([pick: 'add'], 0) == ['add', []]
        assert run([pick: null], 2) == [null, ['choice']]
        assert run([pick: 'add'], 2) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], 2) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], 2) == [null, ['choice', 'confirm']]
    }

    // Source contract: removal only in the Replace branch (own tiles, then own sets, keepChildren = true); the
    // question precedes the tile export and Python run; Cancel returns first; the old yes/no confirm is gone;
    // other sets are not treated as scan regions; the headless path never writes and fails closed.
    void testHistoArtAsksBeforeRemoving() {
        String s = source(HISTOART)
        assert s.count('removeObjects(') == 2
        assert !s.contains('QP.removeObjects') && !s.contains('clearAllObjects') && !s.contains('clearDetections')
        assert !s.contains("it.getName() == HISTOART_SENTINEL") && !s.contains('!= HISTOART_SENTINEL')
        assert s.count('!isOwnSetName(it.getName(), HISTOART_SENTINEL)') == 2   // exportTiles and the READY screen
        int rep = s.indexOf('if (mode == MODE_REPLACE) {\n        def prevTiles')
        int r1 = s.indexOf('hierarchy.removeObjects(prevTiles, true)')
        int r2 = s.indexOf('hierarchy.removeObjects(prevSets, true)')
        assert rep >= 0 && r1 > rep && r2 > r1 && r2 - rep < 400
        assert r2 < s.indexOf('hierarchy.addObjects([sentinel])')
        assert s.contains("def importPredictions = { File workDir, imageData, exp, String mode = 'add' ->")
        assert s.contains('importPredictions(workDir, imageData, exp, runMode)')
        int start = s.indexOf('def startRun = {')
        int ask = s.indexOf('final String runMode = askExisting(imageData.getHierarchy())', start)
        assert ask > start
        assert s.indexOf('if (runMode == null) return', ask) in (ask + 1)..(ask + 120)
        assert ask < s.indexOf('exportTiles(imageData, workDir, cfg, appendLine)', start)
        assert ask < s.indexOf('def cfg = loadConfig()', start)
        assert s.indexOf('if (isHeadless) return', start) in start..ask
        assert !s.contains('showConfirmDialog(workshopText(\'script.e66fc10d5440\'')
        String ac = closure(HISTOART, 'askExisting')
        assert ac.contains('if (prev.isEmpty()) return MODE_ADD')
        assert ac.contains('if (pick == null) return null')
        assert ac.contains('return ok ? MODE_REPLACE : null')
        // headless: unknown value fails closed first; nothing is written anywhere in the headless block
        int hl = s.indexOf('if (isHeadless) {\n    // Başsız kip hiçbir şey yazmaz')
        assert hl > 0
        int hend = s.indexOf('// ── Alanları prefs', hl)
        String block = s.substring(hl, hend)
        assert block.contains("!['ekle', 'degistir'].contains(mevcutArg)")
        assert block.indexOf("!['ekle', 'degistir'].contains(mevcutArg)") < block.indexOf('QP.getCurrentImageData()')
        assert !block.contains('removeObjects') && !block.contains('addObjects') && !block.contains('importPredictions(')
    }

    // ---- tumor-ici-ki67.groovy: ask before replacing an earlier Ki-67 summary / region detections ----

    static final String TUMOR_KI67_FILE = 'tumor-ici-ki67.groovy'

    // Production source with translation tokens neutralised: a token becomes a distinct string (its own name).
    static String tumorKi67Closure(String name) {
        closure(TUMOR_KI67_FILE, name).replaceAll(/@WT:(\w+)@/, "'\$1'")
    }

    static String tumorKi67Prelude() {
        '''def MODE_ADD = 'add'
def MODE_REPLACE = 'replace'
def KI67_BASE = 'Ki-67 Tümör Özet'
def KI67_REGION_BASE = 'Ki-67 tespit alanı'
''' + ownSetCode(TUMOR_KI67_FILE) + '\n' + tumorKi67Closure('ki67OwnDetections') + '\n'
    }

    // Add -> add -> add numbering; a user-renamed annotation is never part of "our" sets (summary and region copy).
    void testTumorKi67OwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def base = 'Ki-67 Tümör Özet'
        shell([h: h, base: base]).evaluate(ownSetCode(TUMOR_KI67_FILE) + '''
assert nextOwnSetName(h, base) == base
assert prevOwnObjects(null, base).isEmpty()
''')
        def mine = ann(base + ' düzeltmelerim')
        h.addObjects([ann(base), mine, ann(base + ' (1)'), ann(base + ' (x)'), ann('Başka')])
        shell([h: h, base: base]).evaluate(ownSetCode(TUMOR_KI67_FILE) + '''
assert prevOwnObjects(h, base).size() == 1
assert nextOwnSetName(h, base) == base + ' (2)'
assert !isOwnSetName(base + ' düzeltmelerim', base)
assert !isOwnSetName(base + ' (1)', base)
assert !isOwnSetName(null, base)
''')
        h.addObjects([ann(base + ' (2)', 200)])
        shell([h: h, base: base]).evaluate(ownSetCode(TUMOR_KI67_FILE) + '''
assert nextOwnSetName(h, base) == base + ' (3)'
assert nextOwnSetName(h, 'Ki-67 tespit alanı') == 'Ki-67 tespit alanı'
''')
        assert h.getAnnotationObjects().contains(mine)
    }

    // The question: no earlier result -> no dialog; Cancel -> null; Add -> keep; Replace needs a second confirmation.
    void testTumorKi67AskExistingDecisions() {
        def run = { Map dlg, boolean withPrev ->
            def h = new PathObjectHierarchy()
            if (withPrev) {
                def s1 = ann('Ki-67 Tümör Özet'); s1.addChildObjects([det(null, 10), det(null, 20)])
                h.addObjects([s1, ann('Ki-67 Tümör Özet düzeltmelerim', 300)])
            }
            def calls = []
            def Dialogs = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            def r = shell([Dialogs: Dialogs, stage: null, h: h, workshopText: { Object... a -> a[0] }]).evaluate(
                tumorKi67Prelude() + tumorKi67Closure('withStageBelow') + '\n' + tumorKi67Closure('askExistingA') + '\naskExistingA(h)')
            [r, calls]
        }
        assert run([pick: 'rep', confirm: true], false) == ['add', []]
        assert run([pick: null], true) == [null, ['choice']]
        assert run([pick: 'add'], true) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], true) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], true) == [null, ['choice', 'confirm']]
    }

    // Path B detection: nothing is removed unless the user chose Replace; a failed Replace puts the old cells back.
    void testTumorKi67DetectOnlyReplacesWhenAskedAndRestoresOnFailure() {
        def build = { ->
            def region = ann('Alan')
            def d1 = det(null, 10); def d2 = det(null, 20)
            region.addChildObjects([d1, d2])
            [region, d1, d2]
        }
        def exec = { Map vars, String mode ->
            def log = []
            def QP = new Expando(
                removeObjects: { list, keep -> log << [list.size(), keep]; vars.region.removeChildObjects(list as List) },
                selectObjects: { x -> },
                runPlugin: { a, b -> if (vars.plugin) vars.plugin() },
                fireHierarchyUpdate: { -> })
            def res = shell(vars + [QP: QP, log: log, workshopText: { Object... a -> a[0] },
                atolyeS: { String k, String d -> d }, atolyeD: { String k, double d -> d }, atolyeB: { String k, boolean d -> d }]).evaluate(
                tumorKi67Prelude() + tumorKi67Closure('runCellDetection') + "\nrunCellDetection(region, '" + mode + "')")
            [res, log]
        }
        // Add with existing detections (should not happen: the caller makes a copy) -> fail closed, nothing removed.
        def (region, d1, d2) = build()
        def (res, log) = exec([region: region], 'add')
        assert !res.ok && log.isEmpty()
        assert region.getChildObjects().findAll { it.isDetection() }.size() == 2
        // Replace, new run finds nothing -> earlier detections restored.
        (region, d1, d2) = build()
        (res, log) = exec([region: region], 'replace')
        assert !res.ok && log == [[2, true]]
        def kids = region.getChildObjects().findAll { it.isDetection() }
        assert kids.size() == 2 && kids.contains(d1) && kids.contains(d2)
        // Replace, new run succeeds -> old ones are gone, new one stays, count reported.
        (region, d1, d2) = build()
        (res, log) = exec([region: region, plugin: { -> region.addChildObjects([det(null, 40)]) }], 'replace')
        assert res.ok && res.count == 1 && res.removed == 2
        assert !region.getChildObjects().findAll { it.isDetection() }.contains(d1)
    }

    // Source contract: removal only in the replace branch AFTER a successful run, the question precedes the work,
    // Cancel returns first, no earlier result without a choice, headless fails closed.
    void testTumorKi67AsksBeforeRemoving() {
        String s = source(TUMOR_KI67_FILE)
        assert s.count('removeObjects(') == 5 && s.count('removeObject(') == 0
        assert !s.contains('QP.removeObjectsAndDescendants(') && !(s =~ /removeObjects?\([^\n]*, false\)/).find()
        // Path A: removal of earlier summaries only in `if (replaceRun)`, after detection succeeded.
        int run = s.indexOf('def runDetection = {')
        int detected = s.indexOf('if (detections.isEmpty()) {', run)
        int locked = s.indexOf('summary.setLocked(true)', detected)
        int rep = s.indexOf('if (replaceRun) {', locked)
        int rem = s.indexOf('QP.removeObjects(prevSummaries, true)', rep)
        assert run > 0 && detected > run && locked > detected && rep > locked && rem > rep && rem - rep < 500
        assert s.indexOf('QP.removeObjects(oldDets, true)', rep) in (rep + 1)..rem
        assert s.substring(run, detected).contains('mode != MODE_ADD && mode != MODE_REPLACE')   // earlier result without a choice
        assert s.substring(run, detected).contains('nextOwnSetName(hierA, KI67_BASE)')
        // The failed run only removes ITS OWN summary (captured by reference above).
        assert s.substring(detected, locked).contains('QP.removeObjects([summary], true)')
        // The question is asked on the FX thread before the worker thread starts.
        int panel = s.indexOf('def buildScorePanel = {')
        int ask = s.indexOf('preAsk()', panel)
        int thread = s.indexOf('def worker = new Thread({', ask)
        assert ask > panel && thread > ask
        assert s.substring(ask, thread).contains('if (runMode == null) return')
        assert s.contains('{ askExistingA(QP.getCurrentImageData()?.getHierarchy()) })')
        for (String n in ['askExistingA', 'askExistingB']) {
            String a = closure(TUMOR_KI67_FILE, n)
            assert a.contains('return MODE_ADD') && a.contains('if (pick == null) return null') && a.contains('return ok ? MODE_REPLACE : null')
        }
        // Path B: the question precedes startDetect; Add makes a copy BEFORE the step changes; a failed run removes only the copy.
        int btn = s.indexOf('def detMode = askExistingB(sel[0])')
        assert btn > 0 && s.indexOf('if (detMode == null) return', btn) > btn && s.indexOf('startDetect(sel[0], detMode)', btn) > btn
        int sd = s.indexOf('def startDetect = {')
        int copy = s.indexOf('createAnnotationObject(regionAnno.getROI())', sd)
        int stepDet = s.indexOf("step.set('DETECTING')", sd)
        assert copy > sd && copy < stepDet
        assert s.substring(sd, stepDet).contains('mode == MODE_ADD && !regionAnno.getChildObjects().findAll { it.isDetection() }.isEmpty()')
        assert s.substring(stepDet).contains('else if (madeCopy)')
        // Replace in path B: only when the user chose it, and only under the selected region.
        String det = closure(TUMOR_KI67_FILE, 'runCellDetection')
        assert det.contains('if (!existing.isEmpty() && mode != MODE_REPLACE)')
        assert det.indexOf('QP.removeObjects(existing, true)') > det.indexOf('mode != MODE_REPLACE')
        // Headless: bad `mevcut` value fails closed; an earlier result without a choice stops before runDetection.
        int head = s.indexOf('if (isHeadless) {', s.indexOf('suggestStainVectors()'))
        String block = s.substring(head, s.indexOf('def stage = null'))
        assert block.contains("['ekle', 'degistir'].contains(mevcut)")
        assert block.indexOf('argMap.mevcut') < block.indexOf('def r = runDetection(')
        assert block.indexOf('mevcut == null') < block.indexOf('def r = runDetection(')
        assert block.count('return') >= 3
    }

    // ---- sihirbaz.groovy (Tümör/Stroma wizard): ask before replacing an earlier tumor/stroma result ----

    static final String TS_WIZARD_FILE = 'sihirbaz.groovy'

    // Production source with translation tokens neutralised: a token becomes a distinct string (its own name).
    static String tsWizardClosure(String name) {
        closure(TS_WIZARD_FILE, name).replaceAll(/@WT:(\w+)@/, "'\$1'")
    }

    static String tsWizardPrelude() {
        '''def MODE_ADD = 'add'
def MODE_REPLACE = 'replace'
def SUMMARY_BASE = 'Tümör-Stroma Özet'
def OUTPUT_TAG = 'Tümör/Stroma ölçüm'
def SUMMARY_SOURCE_KEY = 'atolye_ozet_kaynak'
''' + ownSetCode(TS_WIZARD_FILE) + '\n' +
            ['isOwnSummaryName', 'prevSummaryObjects', 'isOwnOutputObject', 'prevOutputObjects',
             'summarySource', 'markSummary', 'isToolMadeSummary', 'isReplaceableOutput'].collect { tsWizardClosure(it) }.join('\n') + '\n'
    }

    static def tsWizardOutput(String cls, String name, String desc) {
        def o = PathObjects.createAnnotationObject(roi(), PathClass.fromString(cls))
        o.setName(name)
        if (desc != null) o.setDescription(desc)
        o
    }

    // Add -> add -> add numbering; user-renamed summaries are never "ours"; the legacy name 'TSR Özet' still is.
    void testTumorStromaWizardOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def base = 'Tümör-Stroma Özet'
        shell([h: h, base: base]).evaluate(tsWizardPrelude() + '''
assert nextOwnSetName(h, base) == base
assert prevSummaryObjects(null).isEmpty() && prevOutputObjects(null).isEmpty()
''')
        def mine = ann(base + ' düzeltmelerim')
        h.addObjects([ann(base), ann('TSR Özet', 100), mine, ann(base + ' (1)'), ann(base + ' (x)'), ann('TSR Özet (2)'), ann('Başka')])
        shell([h: h, base: base]).evaluate(tsWizardPrelude() + '''
assert prevSummaryObjects(h).size() == 2
assert nextOwnSetName(h, base) == base + ' (2)'
assert isOwnSummaryName('TSR Özet') && isOwnSummaryName(base) && isOwnSummaryName(base + ' (3)')
assert !isOwnSummaryName(base + ' düzeltmelerim') && !isOwnSummaryName('TSR Özet (2)') && !isOwnSummaryName(null)
''')
        h.addObjects([ann(base + ' (2)', 200)])
        shell([h: h, base: base]).evaluate(tsWizardPrelude() + '''
assert nextOwnSetName(h, base) == base + ' (3)'
''')
        assert h.getAnnotationObjects().contains(mine)
    }

    // The wizard's class regions: exact name == class (or "class (N)") plus the exact description format for
    // annotations. The old rule matched any description that merely STARTED with the tag, and called getDescription()
    // on detections (which have none) — both are covered here.
    void testTumorStromaWizardOutputMatchIsExactAndSafeOnDetections() {
        def tag = 'Tümör/Stroma ölçüm — '
        def own = tsWizardOutput('Tumor', 'Tumor', tag + 'Tumor (m)')
        def own2 = tsWizardOutput('Stroma', 'Stroma (2)', tag + 'Stroma (m)')
        def renamed = tsWizardOutput('Tumor', 'Benim tümörüm', tag + 'Tumor (m)')
        def reclassified = tsWizardOutput('Stroma', 'Tumor', tag + 'Tumor (m)')
        def prefixOnly = tsWizardOutput('Tumor', 'Tumor', 'Tümör/Stroma ölçüm notlarım')
        def plain = tsWizardOutput('Tumor', 'Tumor', null)
        def ownDet = det('Tumor'); ownDet.setName('Tumor')
        def userDet = det('Tumor', 30)
        def userDet2 = det(null, 50)
        def h = new PathObjectHierarchy()
        h.addObjects([own, own2, renamed, reclassified, prefixOnly, plain, ownDet, userDet, userDet2])
        def got = shell([h: h]).evaluate(tsWizardPrelude() + 'prevOutputObjects(h)')
        assert got.size() == 3 && got.contains(own) && got.contains(own2) && got.contains(ownDet)
        // a user cell detection on the slide no longer makes the sweep throw
        assert !got.contains(userDet) && !got.contains(userDet2)
    }

    // The question: no earlier result -> no dialog; Cancel -> null; Add -> keep; Replace needs a second confirmation.
    void testTumorStromaWizardAskExistingDecisions() {
        def run = { Map dlg, boolean withPrev ->
            def h = new PathObjectHierarchy()
            if (withPrev) h.addObjects([ann('Tümör-Stroma Özet'), tsWizardOutput('Tumor', 'Tumor', 'Tümör/Stroma ölçüm — Tumor (m)'), ann('Tümör-Stroma Özet düzeltmelerim', 300)])
            def calls = []
            def Dialogs = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            def QP = new Expando(getCurrentImageData: { -> new Expando(getHierarchy: { -> h }) }, getSelectedObjects: { -> [] })
            def r = shell([Dialogs: Dialogs, QP: QP, stage: null, workshopText: { Object... a -> a[0] }]).evaluate(
                tsWizardPrelude() + tsWizardClosure('askExisting') + '\naskExisting()')
            [r, calls]
        }
        assert run([pick: 'rep', confirm: true], false) == ['add', []]
        assert run([pick: null], true) == [null, ['choice']]
        assert run([pick: 'add'], true) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true], true) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false], true) == [null, ['choice', 'confirm']]
    }

    // Temporary objects of this run are found by REFERENCE (identity set captured before the classifier call), not by
    // name or class: a user's annotation with the same class and name stays; objects nested inside a generated one survive.
    void testTumorStromaWizardGeneratedAreTemporaryByReference() {
        String s = source(TS_WIZARD_FILE)
        String beforeLine = s.readLines().find { it.trim().startsWith('def before = QP.getAnnotationObjects() as Set') }
        String genLine = s.readLines().find { it.trim().startsWith('def generated = QP.getAnnotationObjects().findAll') }
        String remLine = s.readLines().find { it.contains('QP.removeObjects(generated, true)') }
        assert beforeLine && genLine && remLine
        def h = new PathObjectHierarchy()
        def userTumor = tsWizardOutput('Tumor', 'Tumor', null)
        def userNoClass = ann('Alan')
        h.addObjects([userTumor, userNoClass])
        def QP = new Expando(getAnnotationObjects: { -> h.getAnnotationObjects() },
            removeObjects: { list, keep -> h.removeObjects(list, keep) })
        def sh = shell([QP: QP])
        sh.evaluate(beforeLine.trim().replace('def before =', 'before ='))
        def newA = tsWizardOutput('Tumor', 'Tumor', null)
        def newB = tsWizardOutput('Stroma', 'Stroma', null)
        def newNoClass = ann('Yeni sınıfsız', 500)
        h.addObjects([newA, newB, newNoClass])
        def inner = det('Tumor')
        newA.addChildObjects([inner])
        sh.evaluate(genLine.trim().replace('def generated =', 'generated =') + '\n' + remLine.trim())
        def left = h.getAnnotationObjects()
        assert left.contains(userTumor) && left.contains(userNoClass) && left.contains(newNoClass)
        assert !left.contains(newA) && !left.contains(newB)
        assert h.getDetectionObjects().contains(inner)
    }

    // Source contract: nothing is removed outside the explicit replace branch, the question precedes the measurement,
    // Cancel returns first, an earlier result without a choice fails closed, headless never writes.
    void testTumorStromaWizardAsksBeforeRemoving() {
        String s = source(TS_WIZARD_FILE)
        assert s.count('removeObjects(') == 3 && s.count('removeObject(') == 1
        assert !s.contains('QP.removeObjectsAndDescendants(') && !(s =~ /removeObjects?\([^\n]*, false\)/).find()
        int run = s.indexOf('def runMeasure = {')
        assert s.substring(run).contains("String mode = 'add' ->")
        int rep = s.indexOf('if (replaceRun) {', run)
        int rem1 = s.indexOf('QP.removeObjects(oldOutputs, true)', rep)
        int rem2 = s.indexOf('QP.removeObjects(oldSummaries, true)', rep)
        assert rep > run && rem1 > rep && rem2 > rem1 && rem2 - rep < 500
        // Earlier result without a choice -> error, before any object is created or removed.
        int guard = s.indexOf('mode != MODE_ADD && mode != MODE_REPLACE', run)
        assert guard > run && guard < rep && guard < s.indexOf('createMeasurementManager', run)
        // Add -> a separate summary with the next name; the user's region is neither renamed nor reused.
        assert s.contains('nextOwnSetName(hierM, SUMMARY_BASE)')
        assert s.contains('if (reuseCandidate != null && !addRun)') && s.contains('else if (!addRun && !wholeSlide && regions.size() == 1)')
        // The question is asked on the FX thread (button handlers) before the step changes and the worker starts.
        int sm = s.indexOf('def startMeasure = {')
        int ask = s.indexOf('String measureMode = askExisting()', sm)
        int stepM = s.indexOf("step.set('MEASURING')", sm)
        int worker = s.indexOf('def worker = new Thread({', sm)
        assert ask > sm && ask < stepM && stepM < worker
        assert s.indexOf('if (measureMode == null) return', ask) in ask..stepM
        assert s.contains('runMeasure(modelName, wholeSlide, asDetections.get(), createSummary.get(), measureMode)')
        String a = closure(TS_WIZARD_FILE, 'askExisting')
        assert a.contains('return MODE_ADD') && a.contains('if (pick == null) return null') && a.contains('return ok ? MODE_REPLACE : null')
        // The earlier tag rule (description prefix) and name-only summary lists are gone.
        assert !s.contains('startsWith(OUTPUT_TAG)') && !s.contains('in summaryNames')
        // The temporary summary of a no-summary run is this run's own object.
        assert s.contains('QP.removeObject(summary, true)')
        // Headless prints the state and returns before anything is written or measured.
        int head = s.indexOf('if (isHeadless) {', run)
        String block = s.substring(head, s.indexOf('def stage = null', head))
        assert block.contains('return') && !block.contains('removeObjects') && !block.contains('addObjects') && !block.contains('runMeasure(')
    }

    // ---- tumor-stroma.groovy: ask before replacing an earlier tumor/stroma result ----

    static final String TS_AREA_FILE = 'tumor-stroma.groovy'

    // Production source with translation tokens neutralised: a token becomes a distinct string (its own name).
    static String tsAreaClosure(String name) {
        closure(TS_AREA_FILE, name).replaceAll(/@WT:(\w+)@/, "'\$1'")
    }

    static String tsAreaPrelude() {
        '''def MODE_ADD = 'add'
def MODE_REPLACE = 'replace'
def SUMMARY_BASE = 'Tümör-Stroma Özet'
''' + ownSetCode(TS_AREA_FILE) + '\n' +
            ['isOwnSummaryName', 'prevSummaryObjects', 'isOwnClassRegion', 'ownClassRegionsUnder'].collect { tsAreaClosure(it) }.join('\n') + '\n'
    }

    static def tsAreaClassRegion(String cls, String name, String desc) {
        def o = PathObjects.createAnnotationObject(roi(), PathClass.fromString(cls))
        o.setName(name)
        if (desc != null) o.setDescription(desc)
        o
    }

    // Add -> add -> add numbering; user-renamed summaries are never "ours"; the legacy name 'TSR Özet' still is.
    void testTumorStromaAreaOwnSetNamingNeverMatchesByPrefix() {
        def h = new PathObjectHierarchy()
        def base = 'Tümör-Stroma Özet'
        shell([h: h, base: base]).evaluate(tsAreaPrelude() + '''
assert nextOwnSetName(h, base) == base
assert prevSummaryObjects(null).isEmpty()
''')
        def mine = ann(base + ' düzeltmelerim')
        h.addObjects([ann(base), ann('TSR Özet', 100), mine, ann(base + ' (1)'), ann(base + ' (x)'), ann('TSR Özet (2)'), ann('Başka')])
        shell([h: h, base: base]).evaluate(tsAreaPrelude() + '''
assert prevSummaryObjects(h).size() == 2
assert nextOwnSetName(h, base) == base + ' (2)'
assert isOwnSummaryName('TSR Özet') && isOwnSummaryName(base) && isOwnSummaryName(base + ' (3)')
assert !isOwnSummaryName(base + ' düzeltmelerim') && !isOwnSummaryName('TSR Özet (2)') && !isOwnSummaryName(null)
''')
        h.addObjects([ann(base + ' (2)', 200)])
        shell([h: h, base: base]).evaluate(tsAreaPrelude() + '''
assert prevSummaryObjects(h).size() == 3
assert nextOwnSetName(h, base) == base + ' (3)'
''')
        assert h.getAnnotationObjects().contains(mine)
    }

    // Only the module's own, untouched class regions count; renamed, reclassified or foreign objects never do,
    // and objects inside them are not reported as ours.
    void testTumorStromaAreaClassRegionTagIsExact() {
        def tag = ' — Tümör/Stroma modülü (tumor-stroma-RF)'
        def own = tsAreaClassRegion('Tumor', 'Tumor', 'Tumor' + tag)
        def ownDet = PathObjects.createDetectionObject(roi(), PathClass.fromString('Stroma'))
        ownDet.setName('Stroma')                    // detections carry no description; name == class is the marker
        def renamed = tsAreaClassRegion('Tumor', 'Benim tümörüm', 'Tumor' + tag)
        def reclassified = tsAreaClassRegion('Stroma', 'Tumor', 'Tumor' + tag)
        def foreign = tsAreaClassRegion('Tumor', 'Tumor', 'Tümör/Stroma ölçüm — Tumor (m)')
        def plain = tsAreaClassRegion('Tumor', 'Tumor', null)
        def userDet = det('Tumor')
        def parent = ann('Tümör-Stroma Özet')
        parent.addChildObjects([own, ownDet, renamed, reclassified, foreign, plain, userDet])
        def got = shell([parent: parent]).evaluate(tsAreaPrelude() + 'ownClassRegionsUnder(parent)')
        assert got.size() == 2 && got.contains(own) && got.contains(ownDet)
    }

    // The question: no dialog without an earlier result is handled by the caller; Cancel -> null; Replace asks twice.
    void testTumorStromaAreaAskExistingDecisions() {
        def run = { Map dlg ->
            def calls = []
            def Dialogs = new Expando(
                showChoiceDialog: { t, b, opts, def0 -> calls << 'choice'; dlg.pick == 'rep' ? opts[1] : (dlg.pick == 'add' ? opts[0] : null) },
                showConfirmDialog: { t, b -> calls << 'confirm'; dlg.confirm })
            def r = shell([Dialogs: Dialogs, workshopText: { Object... a -> a[0] }]).evaluate('''
def MODE_ADD = 'add'
def MODE_REPLACE = 'replace'
''' + tsAreaClosure('askExisting') + '\naskExisting(2, 4)')
            [r, calls]
        }
        assert run([pick: null]) == [null, ['choice']]
        assert run([pick: 'add']) == ['add', ['choice']]
        assert run([pick: 'rep', confirm: true]) == ['replace', ['choice', 'confirm']]
        assert run([pick: 'rep', confirm: false]) == [null, ['choice', 'confirm']]
    }

    // Temporary objects of this run are found by REFERENCE (identity set captured before the classifier call), not by
    // name or class: a user's annotation with the same class and name stays; objects nested inside a generated one survive.
    void testTumorStromaAreaGeneratedAreTemporaryByReference() {
        String s = source(TS_AREA_FILE)
        String beforeLine = s.readLines().find { it.startsWith('def before = QP.getAnnotationObjects() as Set') }
        String genLine = s.readLines().find { it.startsWith('def generated = QP.getAnnotationObjects().findAll') }
        String remLine = s.readLines().find { it.contains('QP.removeObjects(generated, true)') }
        assert beforeLine && genLine && remLine
        def h = new PathObjectHierarchy()
        def userTumor = tsAreaClassRegion('Tumor', 'Tumor', null)       // user's own, same class and name as an output
        def userNoClass = ann('Alan')
        h.addObjects([userTumor, userNoClass])
        def QP = new Expando(getAnnotationObjects: { -> h.getAnnotationObjects() },
            removeObjects: { list, keep -> h.removeObjects(list, keep) })
        def sh = shell([QP: QP])
        sh.evaluate(beforeLine.replace('def before =', 'before ='))
        def newA = tsAreaClassRegion('Tumor', 'Tumor', null)
        def newB = tsAreaClassRegion('Stroma', 'Stroma', null)
        def newNoClass = ann('Yeni sınıfsız', 500)
        h.addObjects([newA, newB, newNoClass])
        def inner = det('Tumor')
        newA.addChildObjects([inner])                                   // foreign object nested inside a generated one
        sh.evaluate(genLine.replace('def generated =', 'generated =') + '\n' + remLine.trim())
        def left = h.getAnnotationObjects()
        assert left.contains(userTumor) && left.contains(userNoClass) && left.contains(newNoClass)
        assert !left.contains(newA) && !left.contains(newB)
        assert h.getDetectionObjects().contains(inner)                  // keepChildren = true
    }

    // Source contract: nothing is removed outside the explicit replace branch, the question precedes the measurement
    // and every change to the user's region, Cancel returns first, headless fails closed.
    void testTumorStromaAreaAsksBeforeRemoving() {
        String s = source(TS_AREA_FILE)
        assert s.count('removeObjects(') == 3 && s.count('removeObject(') == 0
        assert !s.contains('QP.removeObjectsAndDescendants(') && !(s =~ /removeObjects?\([^\n]*, false\)/).find()
        int rep = s.indexOf('if (replaceRun) {')
        int rem1 = s.indexOf('QP.removeObjects(oldRegions, true)', rep)
        int rem2 = s.indexOf('QP.removeObjects(oldSummaries, true)', rep)
        assert rep > 0 && rem1 > rep && rem2 > rem1 && rem2 - rep < 500
        // The question comes before the user's region is renamed/cleared and before any measurement or classifier use.
        int ask = s.indexOf('existingMode = askExisting(')
        int mutate = s.indexOf('summary.setName(summaryName); summary.setPathClass(null)')
        int measure = s.indexOf('PixelClassifierTools.createMeasurementManager(imageData, classifier)', s.indexOf('def perModel'))
        assert ask > 0 && mutate > ask && measure > ask && rep > measure
        assert s.indexOf('if (existingMode == null) return', ask) > ask && s.indexOf('if (existingMode == null) return', ask) < mutate
        assert s.contains('if (addRun) reuse = false')
        assert s.contains('nextOwnSetName(hier, SUMMARY_BASE)')
        // Replace removes only this module's own regions (tagged) and summaries (exact name or "(N)").
        assert s.substring(s.indexOf('def prevRegions ='), ask).contains('ownClassRegionsUnder')
        assert !s.contains('startsWith(SUMMARY') && !s.contains("in ['Tümör-Stroma Özet', 'TSR Özet']")
        // Own/temporary objects of the run are captured by reference before the classifier call.
        int before = s.indexOf('def before = QP.getAnnotationObjects() as Set')
        int create = s.indexOf('QP.createAnnotationsFromPixelClassifier', before)
        assert before > 0 && create > before && s.indexOf('!before.contains(it)', create) > create
        // Headless: bad `mevcut` fails closed; an earlier result without a choice stops before any write.
        int head = s.indexOf('String headlessMevcut = null')
        String hb = s.substring(head, s.indexOf('def headlessModels = []', head))
        assert hb.contains("['ekle', 'degistir'].contains(headlessMevcut)") && hb.contains('return')
        String block = s.substring(ask - 700, ask)
        assert block.contains('headlessMevcut == null') && block.contains('return')
    }

    // ---- Re-detection safety: detection tools ask before replacing the cells already in the annotation ----
    static final String REDETECT_CELL = 'hucre-tespiti.groovy'
    static final String REDETECT_NUC = 'nukleer-boya.groovy'
    static final String REDETECT_ERPR = 'er-pr-hscore.groovy'
    static final String REDETECT_CYTO = 'sitoplazmik-boya.groovy'

    // The Run-button handler: from runBtn.setOnAction up to the next button/section.
    static String redetectRunHandler(String s) {
        int a = s.indexOf('runBtn.setOnAction({')
        assert a >= 0 && s.indexOf('runBtn.setOnAction({', a + 1) < 0
        int b = s.indexOf('recountBtn.setOnAction({', a)
        if (b < 0) b = s.indexOf('def alwaysTop', a)
        assert b > a
        s.substring(a, b)
    }

    // The question precedes every side effect of the Run button, and Cancel returns before them.
    static void redetectAssertAsksFirst(String file) {
        String s = source(file)
        String h = redetectRunHandler(s)
        assert h.count('confirmReplaceCells(') == 1
        int count = h.indexOf('countReplaceable(selNow)')
        int ask = h.indexOf('confirmReplaceCells(')
        assert count >= 0 && count < ask
        int disable = h.indexOf('runBtn.setDisable(true)')
        int worker = h.indexOf('new Thread({')
        int detect = h.indexOf('runDetection(')
        assert disable > ask && worker > ask && detect > ask
        int cancel = h.indexOf('if (!okReplace) {', ask)
        assert cancel > ask
        int ret = h.indexOf('return', cancel)
        assert ret > cancel && ret < disable && ret < worker && ret < detect
        // the replaced-cell count reaches the detection call
        assert h.substring(detect).startsWith('runDetection(') && h.substring(detect, h.indexOf('\n', detect)).contains('nPrev)')
        // detection plugin is invoked once, inside runDetection, and nowhere before the question
        assert s.count('QP.runPlugin(') == 1
        int defAt = s.indexOf('def runDetection = {')
        assert defAt >= 0 && s.indexOf('QP.runPlugin(') > defAt
        // no direct deletion API anywhere in the script
        ['removeObject', 'clearAllObjects', 'clearDetections', 'clearChildObjects', 'deleteObject', 'removeObjects'].each {
            assert !s.contains(it) : file + ' contains ' + it
        }
        // the only way to count replaceable cells counts detections under the target
        assert s.count('.getChildObjects()') == s.count('.getChildObjects().findAll { it.isDetection() }')
    }

    // The quick recount only reclassifies existing detections: nothing it calls creates or deletes objects.
    static void redetectAssertRecountDeletesNothing(String file, List<String> closureNames) {
        closureNames.each { n ->
            String c = closure(file, n)
            ['runPlugin', 'removeObject', 'clearAllObjects', 'clearDetections', 'clearChildObjects', 'addObject', 'deleteObject'].each {
                assert !c.contains(it) : n + ' contains ' + it
            }
        }
    }

    static void redetectAssertCountsOnlyDetectionsUnderTarget(String file) {
        def target = ann('target')
        def other = ann('other', 500)
        def nested = ann('nested')
        target.addChildObject(det('A'))
        target.addChildObject(det(null, 20))
        target.addChildObject(PathObjects.createCellObject(
            ROIs.createRectangleROI(30, 30, 6, 6, ImagePlane.getDefaultPlane()),
            ROIs.createRectangleROI(31, 31, 2, 2, ImagePlane.getDefaultPlane()), null, null))
        target.addChildObject(nested)            // a child annotation is not a detection
        nested.addChildObject(det('B', 40))      // its detections are replaced by a run on `nested`, not on `target`
        other.addChildObject(det('C', 510))
        def empty = ann('empty')
        empty.addChildObject(ann('only-annotation'))
        def count = shell().evaluate('import qupath.lib.objects.PathAnnotationObject\n' + closure(file, 'countReplaceable') + '\ncountReplaceable')
        assert count(target) == 3
        assert count(nested) == 1
        assert count(other) == 1
        assert count(empty) == 0
        assert count(null) == 0
    }

    // Headless: stops (writes nothing) when detections exist unless -a mevcut=degistir; any other value fails closed.
    static Map redetectRunHeadless(String file, Object annotation, String mevcut) {
        String s = source(file)
        int mark = s.indexOf('// Tespit mevcut hücreleri DEĞİŞTİRİR: soru sorulamaz')
        assert mark >= 0
        int start = s.lastIndexOf('if (isHeadless) {', mark)
        int end = s.indexOf('\n}\n', mark) + 3
        assert start >= 0 && end > mark
        String block = s.substring(start, end)
        def calls = []
        def sw = new StringWriter()
        def vars = [
            isHeadless: true, out: new PrintWriter(sw, true),
            QP: new Expando(getSelectedObject: { annotation }),
            imageTypeOk: { -> true },
            resolveTarget: { -> [ok: annotation != null, ann: annotation] },
            headlessArg: { String k -> mevcut },
            workshopText: { Object... a -> a[-1] },
            atolyeText: { String k, String fb -> fb },
            atolyeD: { String k, double d -> d },
            DEF1: 0.2d, DEF2: 0.4d, DEF3: 0.6d,
            runDetection: { Object... a -> calls << a.toList(); [ok: true, text: 'RESULT'] }
        ]
        shell(vars).evaluate('import qupath.lib.objects.PathAnnotationObject\n' + closure(file, 'countReplaceable') + '\n' + block)
        [calls: calls, out: sw.toString()]
    }

    static void redetectAssertHeadlessFailsClosed(String file) {
        def withCells = ann('t'); withCells.addChildObject(det('A')); withCells.addChildObject(det(null, 20))
        def noCells = ann('t2')
        def r = redetectRunHeadless(file, withCells, null)
        assert r.calls.isEmpty() && r.out.contains('2') && r.out.contains('mevcut=degistir')
        r = redetectRunHeadless(file, withCells, 'ekle')
        assert r.calls.isEmpty() && r.out.contains('ekle')
        r = redetectRunHeadless(file, withCells, '')
        assert r.calls.isEmpty()
        r = redetectRunHeadless(file, withCells, 'degistir')
        assert r.calls.size() == 1 && r.calls[0].last() == 2 && r.out.contains('RESULT')
        r = redetectRunHeadless(file, noCells, null)
        assert r.calls.size() == 1 && r.calls[0].last() == 0
        r = redetectRunHeadless(file, noCells, 'degistir')
        assert r.calls.size() == 1 && r.calls[0].last() == 0
        r = redetectRunHeadless(file, noCells, 'bilinmeyen')
        assert r.calls.isEmpty()
        // no target at all: behaves as before (the detection call reports its own error)
        r = redetectRunHeadless(file, null, null)
        assert r.calls.size() == 1 && r.calls[0].last() == 0
    }

    void testRedetectCellAsksBeforeDetection() { redetectAssertAsksFirst(REDETECT_CELL) }
    void testRedetectCellCountsOnlyDetectionsUnderTarget() { redetectAssertCountsOnlyDetectionsUnderTarget(REDETECT_CELL) }
    void testRedetectCellHeadlessFailsClosed() { redetectAssertHeadlessFailsClosed(REDETECT_CELL) }

    // Needs the shared redetect* helpers and REDETECT_* constants from test-redetect-hucre-tespiti.groovy
    void testRedetectNuclearAsksBeforeDetection() { redetectAssertAsksFirst(REDETECT_NUC) }
    void testRedetectNuclearCountsOnlyDetectionsUnderTarget() { redetectAssertCountsOnlyDetectionsUnderTarget(REDETECT_NUC) }
    void testRedetectNuclearHeadlessFailsClosed() { redetectAssertHeadlessFailsClosed(REDETECT_NUC) }
    void testRedetectNuclearRecountDeletesNothing() { redetectAssertRecountDeletesNothing(REDETECT_NUC, ['recount', 'finishWithMetrics', 'metricsAt']) }

    // Needs the shared redetect* helpers and REDETECT_* constants from test-redetect-hucre-tespiti.groovy
    void testRedetectErPrAsksBeforeDetection() { redetectAssertAsksFirst(REDETECT_ERPR) }
    void testRedetectErPrCountsOnlyDetectionsUnderTarget() { redetectAssertCountsOnlyDetectionsUnderTarget(REDETECT_ERPR) }
    void testRedetectErPrHeadlessFailsClosed() { redetectAssertHeadlessFailsClosed(REDETECT_ERPR) }
    void testRedetectErPrRecountDeletesNothing() { redetectAssertRecountDeletesNothing(REDETECT_ERPR, ['recount', 'finishWithMetrics', 'metricsAt']) }

    // Needs the shared redetect* helpers and REDETECT_* constants from test-redetect-hucre-tespiti.groovy
    void testRedetectCytoAsksBeforeDetection() { redetectAssertAsksFirst(REDETECT_CYTO) }
    void testRedetectCytoCountsOnlyDetectionsUnderTarget() { redetectAssertCountsOnlyDetectionsUnderTarget(REDETECT_CYTO) }
    void testRedetectCytoHeadlessFailsClosed() { redetectAssertHeadlessFailsClosed(REDETECT_CYTO) }
    void testRedetectCytoRecountDeletesNothing() { redetectAssertRecountDeletesNothing(REDETECT_CYTO, ['recount', 'finishCytoMetrics']) }


    static final String REDETECT_QUANTUM_FILE = 'quantum-ctcf.groovy'

    // The cell count is exactly the detections that are children of the target; other annotations' cells,
    // child annotations and a missing target never count.
    void testRedetectQuantumCountsOnlyDetectionsUnderTarget() {
        def h = new PathObjectHierarchy()
        def target = ann('TCR', 0)
        def other = ann('Other', 500)
        h.addObjects([target, other])
        [det('Tumor', 10), det(null, 20), det('Stroma', 30)].each { target.addChildObject(it) }
        [det('X', 510), det('Y', 520)].each { other.addChildObject(it) }
        target.addChildObject(ann('Child annotation', 40))
        def count = shell([:]).evaluate(closure(REDETECT_QUANTUM_FILE, 'redetectCount') + '\nredetectCount')
        assert count(target) == 3
        assert count(other) == 2
        assert count(ann('Empty', 1000)) == 0
        assert count(null) == 0
    }

    void testRedetectQuantumConfirmDecisions() {
        def h = new PathObjectHierarchy()
        def target = ann('TCR', 0)
        def empty = ann('Empty', 500)
        h.addObjects([target, empty])
        [det('Tumor', 10), det(null, 20)].each { target.addChildObject(it) }
        def asked = []; def notes = []
        def answer = [value: false]
        def dialogs = new Expando(
            showConfirmDialog: { String t, String b -> asked << b; answer.value },
            showInfoNotification: { String t, String b -> notes << b })
        String code = closure(REDETECT_QUANTUM_FILE, 'redetectCount') + closure(REDETECT_QUANTUM_FILE, 'redetectConfirm') + '\nredetectConfirm'
        def confirm = shell([Dialogs: dialogs, stage: null, workshopText: { Object... a -> a[0] },
            confirmReplaceCells: { String t, String b -> asked << b; answer.value }]).evaluate(code)
        // No detections under the target: no question, run proceeds.
        assert confirm(empty) == true && asked.isEmpty()
        // Cancel: false, one question carrying the count, a notice, nothing removed.
        assert confirm(target) == false
        assert asked.size() == 1 && asked[0].contains('2') && notes.size() == 1
        assert target.getChildObjects().findAll { it.isDetection() }.size() == 2
        answer.value = true
        assert confirm(target) == true && asked.size() == 2
    }

    void testRedetectQuantumHeadlessArgParsing() {
        def parse = { List a -> shell([args: a]).evaluate(closure(REDETECT_QUANTUM_FILE, 'headlessArg') + "\nheadlessArg('mevcut')") }
        assert parse(['mevcut=Degistir']) == 'degistir'
        assert parse(['mevcut = ekle']) == 'ekle'
        assert parse([]) == null
        assert parse(['x=y']) == null
    }

    // Source contract: the question precedes the detection; No returns before it; headless stops first;
    // the only calls of the detection runner are the confirmed GUI start and the checked headless run.
    void testRedetectQuantumAsksBeforeDetecting() {
        String s = source(REDETECT_QUANTUM_FILE)
        assert s.count('runStarDist(') == 2
        assert s.count('startStarDist(') == 1
        int ask = s.indexOf('if (!redetectConfirm(sel[0])) return')
        int start = s.indexOf('startStarDist(sel[0])')
        assert ask > 0 && ask < start
        String confirmSrc = closure(REDETECT_QUANTUM_FILE, 'redetectConfirm')
        assert confirmSrc.indexOf('if (n == 0) return true') > 0
        assert confirmSrc.indexOf('confirmReplaceCells(') > confirmSrc.indexOf('if (n == 0) return true')
        assert confirmSrc.contains('return ok')
        int hl = s.indexOf("redetectPrev > 0 && mevcut != 'degistir'")
        int hlReturn = s.indexOf('return', hl)
        int run = s.indexOf('def d = runStarDist(sel)')
        assert hl > 0 && hl < hlReturn && hlReturn < run
        int bad = s.indexOf("mevcut != null && mevcut != 'degistir'")
        assert bad > 0 && bad < hl
        // The pre-detection removal is only reachable through the two guarded callers.
        assert s.count('regionAnno.removePathObjects(oldDet)') == 1
    }


    static final String REDETECT_IMMUNE_FILE = 'yardimci-immun-yogunluk.groovy'

    void testRedetectImmuneCountsOnlyDetectionsUnderTargets() {
        def h = new PathObjectHierarchy()
        def a = ann('A', 0); def b = ann('B', 500); def c = ann('C', 1000)
        h.addObjects([a, b, c])
        [det('Positive', 10), det(null, 20)].each { a.addChildObject(it) }
        b.addChildObject(det('Negative', 510)); c.addChildObject(det('Z', 1010))
        def count = shell([:]).evaluate(closure(REDETECT_IMMUNE_FILE, 'redetectCount') + '\nredetectCount')
        assert count([a]) == 2
        assert count([a, b]) == 3
        assert count([c]) == 1
        assert count([ann('Empty', 2000)]) == 0
        assert count([]) == 0
    }

    // Source contract: summary question first (once), then the detection question (once); Cancel returns
    // before anything is selected or run; headless stops unless mevcut=degistir; the summary is only
    // changed after both answers.
    void testRedetectImmuneAsksBeforeDetecting() {
        String s = source(REDETECT_IMMUNE_FILE)
        assert s.count('askExisting(imageData') == 1
        assert s.count('confirmReplaceCells(redetectTitleText') == 1
        int sum = s.indexOf('runMode = askExisting(imageData.getHierarchy())')
        int cnt = s.indexOf('int redetectPrev = redetectCount(targets)')
        int hl = s.indexOf("if (mevcut != 'degistir')", cnt)
        int hlRet = s.indexOf('return', hl)
        int ask = s.indexOf('if (!confirmReplaceCells(', cnt)
        int cancelRet = s.indexOf('return', ask)
        int select = s.indexOf('QP.selectObjects(targets)')
        int plugin = s.indexOf('QP.runPlugin(')
        int apply = s.indexOf('def appliedMode = applyMode(')
        assert sum > 0 && sum < cnt && cnt < hl && hl < hlRet && hlRet < ask && ask < cancelRet
        assert cancelRet < select && select < plugin && plugin < apply
        assert s.count('QP.runPlugin(') == 1
    }

    void testRedetectImmuneHeadlessFailsClosed() {
        String s = source(REDETECT_IMMUNE_FILE)
        int bad = s.indexOf("!['ekle', 'degistir'].contains(mevcut)")
        int cnt = s.indexOf('int redetectPrev = redetectCount(targets)')
        assert bad > 0 && bad < cnt
        String block = s.substring(cnt, s.indexOf('QP.selectObjects(targets)'))
        assert block.contains("if (mevcut != 'degistir') {")
        assert block.count('return') == 2   // headless stop + cancel
    }


    static final String REDETECT_STROMALTIL_FILE = 'yardimci-stromal-til.groovy'

    void testRedetectStromalTilCountsOnlyDetectionsUnderStroma() {
        def h = new PathObjectHierarchy()
        def s1 = ann('S1', 0); def s2 = ann('S2', 500); def tumor = ann('T', 1000)
        h.addObjects([s1, s2, tumor])
        [det('Lenf', 10), det(null, 20)].each { s1.addChildObject(it) }
        s2.addChildObject(det('X', 510)); tumor.addChildObject(det('Y', 1010))
        def count = shell([:]).evaluate(closure(REDETECT_STROMALTIL_FILE, 'redetectCount') + '\nredetectCount')
        assert count([s1]) == 2
        assert count([s1, s2]) == 3
        assert count([ann('Empty', 2000)]) == 0
        assert count([]) == 0
    }

    void testRedetectStromalTilAsksBeforeDetecting() {
        String s = source(REDETECT_STROMALTIL_FILE)
        assert s.count('askExisting(imageData') == 1
        assert s.count('confirmReplaceCells(redetectTitleText') == 1
        int sum = s.indexOf('runMode = askExisting(imageData.getHierarchy())')
        int cnt = s.indexOf('int redetectPrev = redetectCount(stromaAnnotations)')
        int hl = s.indexOf("if (mevcut != 'degistir')", cnt)
        int hlRet = s.indexOf('return', hl)
        int ask = s.indexOf('if (!confirmReplaceCells(', cnt)
        int cancelRet = s.indexOf('return', ask)
        int select = s.indexOf('QP.selectObjects(stromaAnnotations)')
        int plugin = s.indexOf('QP.runPlugin(')
        int apply = s.indexOf('def appliedMode = applyMode(')
        assert sum > 0 && sum < cnt && cnt < hl && hl < hlRet && hlRet < ask && ask < cancelRet
        assert cancelRet < select && select < plugin && plugin < apply
        assert s.count('QP.runPlugin(') == 1
    }

    void testRedetectStromalTilHeadlessFailsClosed() {
        String s = source(REDETECT_STROMALTIL_FILE)
        int bad = s.indexOf("!['ekle', 'degistir'].contains(mevcut)")
        int cnt = s.indexOf('int redetectPrev = redetectCount(stromaAnnotations)')
        assert bad > 0 && bad < cnt
        String block = s.substring(cnt, s.indexOf('QP.selectObjects(stromaAnnotations)'))
        assert block.contains("if (mevcut != 'degistir') {")
        assert block.count('return') == 2
    }


    static final String REDETECT_STARDIST_FILE = 'yardimci-stardist-sihirbaz.groovy'

    void testRedetectStarDistCountsOnlyDetectionsUnderSelected() {
        def h = new PathObjectHierarchy()
        def a = ann('A', 0); def b = ann('B', 500)
        h.addObjects([a, b])
        [det('Tumor', 10), det(null, 20)].each { a.addChildObject(it) }
        b.addChildObject(det('Q', 510))
        def count = shell([:]).evaluate(closure(REDETECT_STARDIST_FILE, 'redetectCount') + '\nredetectCount')
        assert count(a) == 2
        assert count(b) == 1
        assert count(ann('Empty', 1000)) == 0
        assert count(null) == 0
    }

    // Source contract: the question is asked on the button handler (before preferences are saved, before the
    // worker thread starts); No returns before either; the removal of earlier cells exists only in the worker.
    void testRedetectStarDistAsksBeforeDetecting() {
        String s = source(REDETECT_STARDIST_FILE)
        int count = s.indexOf('int redetectPrev = redetectCount(selected)')
        int ask = s.indexOf('confirmReplaceCells(redetectTitleText', count)
        int cancel = s.indexOf('if (!redetectOk) {', ask)
        int cancelRet = s.indexOf('return', cancel)
        int save = s.indexOf('prefs.put(PREF_MODEL, mp)')
        int worker = s.indexOf('def worker = new Thread(')
        int removal = s.indexOf('selected.removePathObjects(oldDetections)')
        int detect = s.indexOf('.detectObjects(imageData, [selected])')
        assert count > 0 && count < ask && ask < cancel && cancel < cancelRet
        assert cancelRet < save && save < worker && worker < removal && removal < detect
        assert s.count('redetectCount(selected)') == 1
        assert s.count('.detectObjects(') == 1
        assert s.count('selected.removePathObjects(oldDetections)') == 1
    }


    static final String REDETECT_INSTANSEG_FILE = 'yardimci-instanseg-sihirbaz.groovy'

    void testRedetectInstanSegCountsOnlyDetectionsUnderSelected() {
        def h = new PathObjectHierarchy()
        def a = ann('A', 0); def b = ann('B', 500)
        h.addObjects([a, b])
        [det('Tumor', 10), det(null, 20)].each { a.addChildObject(it) }
        b.addChildObject(det('Q', 510))
        def count = shell([:]).evaluate(closure(REDETECT_INSTANSEG_FILE, 'redetectCount') + '\nredetectCount')
        assert count(a) == 2
        assert count(b) == 1
        assert count(ann('Empty', 1000)) == 0
        assert count(null) == 0
    }

    // Source contract: the question is asked on the button handler (before preferences are saved, before the
    // worker thread starts); No returns before either; the removal of earlier cells exists only in the worker.
    void testRedetectInstanSegAsksBeforeDetecting() {
        String s = source(REDETECT_INSTANSEG_FILE)
        int count = s.indexOf('int redetectPrev = redetectCount(selected)')
        int ask = s.indexOf('confirmReplaceCells(redetectTitleText', count)
        int cancel = s.indexOf('if (!redetectOk) {', ask)
        int cancelRet = s.indexOf('return', cancel)
        int save = s.indexOf('saveParams(modelDir, device)')
        int worker = s.indexOf('def worker = new Thread(')
        int removal = s.indexOf('selected.removePathObjects(oldDetections)')
        int detect = s.indexOf('.detectObjects(imageData, [selected])')
        assert count > 0 && count < ask && ask < cancel && cancel < cancelRet
        assert cancelRet < save && save < worker && worker < removal && removal < detect
        assert s.count('redetectCount(selected)') == 1
        assert s.count('.detectObjects(') == 1
        assert s.count('selected.removePathObjects(oldDetections)') == 1
    }


    static final String REDETECT_MEMBRANE_FILE = 'membran-boya.groovy'

    void testRedetectMembraneHeadlessArgParsing() {
        def parse = { List a -> shell([args: a]).evaluate(closure(REDETECT_MEMBRANE_FILE, 'headlessArg') + "\nheadlessArg('mevcut')") }
        assert parse(['mevcut=DEGISTIR']) == 'degistir'
        assert parse(['boya=varsayilan']) == null
        assert parse(['boya=varsayilan', 'mevcut=sil']) == 'sil'
    }

    // Headless: the cell count reads only the detections under the selected annotation, and the run is
    // stopped unless -a mevcut=degistir was given; any other mevcut value fails closed first.
    void testRedetectMembraneHeadlessFailsClosed() {
        String s = source(REDETECT_MEMBRANE_FILE)
        int hl = s.indexOf('if (isHeadless) {\n    // Tespit hedef')
        assert hl > 0
        int bad = s.indexOf("if (mevcut != null && mevcut != 'degistir') {", hl)
        int badRet = s.indexOf('return', bad)
        int cnt = s.indexOf('int redetectPrev = ', badRet)
        int stop = s.indexOf("if (redetectPrev > 0 && mevcut != 'degistir') {", cnt)
        int stopRet = s.indexOf('return', stop)
        int run = s.indexOf('def r = runDetection(', stopRet)
        assert bad > hl && badRet > bad && cnt > badRet && stop > cnt && stopRet > stop && run > stopRet
        int eol = s.indexOf('\n', cnt)
        String expr = s.substring(s.indexOf('= ', cnt) + 2, eol)
        def h = new PathObjectHierarchy()
        def a = ann('A', 0); def other = ann('B', 500)
        h.addObjects([a, other])
        [det('Tumor', 10), det(null, 20)].each { a.addChildObject(it) }
        other.addChildObject(det('Z', 510))
        def n = { sel -> shell([QP: new Expando(getSelectedObject: { sel })]).evaluate('import qupath.lib.objects.PathAnnotationObject\n' + expr) }
        assert n(a) == 2 && n(other) == 1 && n(null) == 0 && n(det('x')) == 0
        // The GUI path already asks (existing behaviour); the headless path is the only one without a dialog.
        assert s.count('runDetection(m1, m2, m3, pd1, pd2, pd3, ph, false)') == 1
    }


    static final String REDETECT_CELLPOSE_FILE = 'yardimci-cellpose-sihirbaz.groovy'

    void testRedetectCellposeHeadlessArgParsing() {
        def parse = { List a -> shell([args: a]).evaluate(closure(REDETECT_CELLPOSE_FILE, 'headlessArg') + "\nheadlessArg('mevcut')") }
        assert parse(['mevcut=Degistir']) == 'degistir'
        assert parse([]) == null
        assert parse(['mevcut=ekle']) == 'ekle'
    }

    void testRedetectCellposeHeadlessFailsClosed() {
        String s = source(REDETECT_CELLPOSE_FILE)
        int hl = s.indexOf('if (isHeadless) {\n    // Tespit hedef')
        assert hl > 0
        int bad = s.indexOf("if (mevcut != null && mevcut != 'degistir') {", hl)
        int badRet = s.indexOf('return', bad)
        int cnt = s.indexOf('int redetectPrev = ', badRet)
        int stop = s.indexOf("if (redetectPrev > 0 && mevcut != 'degistir') {", cnt)
        int stopRet = s.indexOf('return', stop)
        int run = s.indexOf('def r = runDetection(', stopRet)
        assert bad > hl && badRet > bad && cnt > badRet && stop > cnt && stopRet > stop && run > stopRet
        int eol = s.indexOf('\n', cnt)
        String expr = s.substring(s.indexOf('= ', cnt) + 2, eol)
        def h = new PathObjectHierarchy()
        def a = ann('A', 0); def b = ann('B', 500); def c = ann('C', 1000)
        h.addObjects([a, b, c])
        [det('Tumor', 10), det(null, 20)].each { a.addChildObject(it) }
        b.addChildObject(det('Z', 510))
        def n = { List sel -> shell([QP: new Expando(getSelectedObjects: { sel })]).evaluate(expr) }
        assert n([a]) == 2
        assert n([a, b]) == 3
        assert n([c]) == 0
        assert n([]) == 0
        // The GUI start still asks first (existing behaviour).
        int gui = s.indexOf('boolean ok = confirmReplaceCells(')
        int guiRun = s.indexOf('def res = runDetection(fam')
        assert gui > 0 && gui < guiRun
    }

    // ===== (b) EXISTING tests of tumor-stroma.groovy that fail after this change =====
    // None. (Checked by running all five testTumorStromaArea* methods against the new script: all pass.)
    // New tests below.

    // ---- tumor-stroma.groovy: a user's region is never deleted by Replace ----

    // Evaluates the real Replace-bookkeeping source lines of the script against a hierarchy.
    //   candidate: previously reused summary selected for in-place re-measure (or null)
    //   summaryObj: the annotation that carries the new measurement
    static Map tsAreaRunReplace(PathObjectHierarchy h, def candidate, def summaryObj) {
        String s = source(TS_AREA_FILE)
        int a = s.indexOf('def removableSummaries =')
        int b = s.indexOf("String existingMode = MODE_ADD", a)
        String sel = s.substring(a, b)
        int r0 = s.indexOf('int removedSummaries = 0')
        int r1 = s.indexOf('\n}\n', s.indexOf('if (replaceRun) {', r0)) + 3
        String rem = s.substring(r0, r1)
        def QP = new Expando(removeObjects: { list, keep -> h.removeObjects(list, keep) })
        def sh = shell([QP: QP, h: h, candidate: candidate, summary: summaryObj, replaceRun: true,
            prevSummaries: h.getAnnotationObjects().findAll { it.getName() != null && (it.getName() == 'TSR Özet' || it.getName().startsWith('Tümör-Stroma Özet')) && !it.getName().contains('düzeltmelerim') },
            ])
        String pre = tsAreaPrelude() + ['summarySource', 'markSummary', 'isToolMadeSummary'].collect { tsAreaClosure(it) }.join('\n') + '\n'
        sh.evaluate("def SUMMARY_SOURCE_KEY = 'atolye_ozet_kaynak'\n" + pre + 'def prevInRegion = ownClassRegionsUnder(summary)\n' + sel + '\n' + rem +
            '\n[removedSummaries: removedSummaries, removedRegions: removedRegions]')
    }

    static def tsAreaScene() {
        def tag = ' — Tümör/Stroma modülü (m)'
        def user = ann('Tümör-Stroma Özet'); user.getMetadata().put('atolye_ozet_kaynak', 'kullanici')
        def userRegion = tsAreaClassRegion('Tumor', 'Tumor', 'Tumor' + tag)
        def userInner = det('Tumor')
        user.addChildObjects([userRegion, userInner])
        def tool = ann('Tümör-Stroma Özet (2)', 300); tool.getMetadata().put('atolye_ozet_kaynak', 'arac')
        def toolRegion = tsAreaClassRegion('Stroma', 'Stroma', 'Stroma' + tag)
        def toolInner = det('Stroma', 320)
        tool.addChildObjects([toolRegion, toolInner])
        def legacy = ann('TSR Özet', 600)                                // no marker: possibly a user's region
        def legacyRegion = tsAreaClassRegion('Tumor', 'Tumor', 'Tumor' + tag)
        legacy.addChildObjects([legacyRegion])
        def h = new PathObjectHierarchy()
        h.addObjects([user, tool, legacy])
        [h: h, user: user, userRegion: userRegion, userInner: userInner, tool: tool, toolRegion: toolRegion,
         toolInner: toolInner, legacy: legacy, legacyRegion: legacyRegion]
    }

    void testTumorStromaAreaKeepsUserRegionMarkersDistinguishUserFromTool() {
        def sc = tsAreaScene()
        def got = shell([a: sc.user, b: sc.tool, c: sc.legacy]).evaluate("def SUMMARY_SOURCE_KEY = 'atolye_ozet_kaynak'\n" +
            ['summarySource', 'markSummary', 'isToolMadeSummary'].collect { tsAreaClosure(it) }.join('\n') +
            "\n[isToolMadeSummary(a), isToolMadeSummary(b), isToolMadeSummary(c), summarySource(c)]")
        assert got == [false, true, false, null]
        String s = source(TS_AREA_FILE)
        assert s.contains("markSummary(summary, 'kullanici')") && s.contains("markSummary(summary, 'arac')")
        assert s.indexOf("markSummary(summary, 'kullanici')") > s.indexOf('if (candidate == null) {')
    }

    // Replace with a previously reused user region selected: it is re-measured in place (stays), its own earlier class
    // regions go, the tool-made summary and its regions go, objects inside are kept, the unmarked legacy summary stays.
    void testTumorStromaAreaKeepsUserRegionReusedAndReMeasuredInPlace() {
        def sc = tsAreaScene()
        def r = tsAreaRunReplace(sc.h, sc.user, sc.user)
        def left = sc.h.getAnnotationObjects()
        assert left.contains(sc.user)                                   // user's region/summary is never deleted
        assert left.contains(sc.legacy)                                 // unmarked summary kept
        assert !left.contains(sc.tool)                                  // positively tool-made: removed
        assert !left.contains(sc.userRegion) && !left.contains(sc.toolRegion)   // exact-tag own class regions removed
        assert left.contains(sc.legacyRegion)                           // belongs to a kept summary
        assert sc.h.getDetectionObjects().containsAll([sc.userInner, sc.toolInner])   // objects inside are kept
        assert r.removedSummaries == 1 && r.removedRegions == 2
        assert !left.isEmpty() && left.count { it.getName() == 'Tümör-Stroma Özet' } == 1
    }

    // Replace on a different region (the new summary is another annotation): earlier user/unmarked summaries stay.
    void testTumorStromaAreaKeepsUserRegionWhenAnotherRegionIsMeasured() {
        def sc = tsAreaScene()
        def fresh = ann('Yeni bölge', 900)
        sc.h.addObjects([fresh])
        def r = tsAreaRunReplace(sc.h, null, fresh)
        def left = sc.h.getAnnotationObjects()
        assert left.containsAll([sc.user, sc.legacy, fresh, sc.userRegion, sc.legacyRegion])
        assert !left.contains(sc.tool) && !left.contains(sc.toolRegion)
        assert r.removedSummaries == 1 && r.removedRegions == 1
    }

    // Only unmarked summaries on the slide (all made before the marker existed): Replace deletes none of them.
    void testTumorStromaAreaKeepsUserRegionUnmarkedLegacySummary() {
        def legacy = ann('Tümör-Stroma Özet')
        def lr = tsAreaClassRegion('Tumor', 'Tumor', 'Tumor — Tümör/Stroma modülü (m)')
        legacy.addChildObjects([lr])
        def h = new PathObjectHierarchy()
        h.addObjects([legacy])
        def r = tsAreaRunReplace(h, legacy, legacy)                      // in-place: its own class regions are replaced
        assert h.getAnnotationObjects().contains(legacy) && r.removedSummaries == 0 && r.removedRegions == 1
        def legacy2 = ann('TSR Özet', 400)
        h.addObjects([legacy2])
        def fresh = ann('Yeni', 700); h.addObjects([fresh])
        def r2 = tsAreaRunReplace(h, null, fresh)
        assert h.getAnnotationObjects().containsAll([legacy, legacy2, fresh]) && r2.removedSummaries == 0
    }

    // Source contract: Replace deletes only marked tool-made summaries; the question counts only what can be deleted.
    void testTumorStromaAreaKeepsUserRegionSourceContract() {
        String s = source(TS_AREA_FILE)
        assert s.contains('def oldSummaries = removableSummaries.findAll { it != summary }')
        assert s.contains('def removableSummaries = prevSummaries.findAll { it != candidate && isToolMadeSummary(it) }')
        assert s.contains('existingMode = askExisting(prevSummaries.size(), replaceRegions.size())')
        assert !s.contains('prevSummaries.findAll { it != summary }')
        assert s.contains('if (!reuse && candidate == null) scopeRegions.each { sp ->')   // no overwrite of an earlier summary on Add
        assert s.contains('summary.getMeasurementList().clear()')
    }

    // ===== (b) EXISTING tests of sihirbaz.groovy that fail after this change =====
    // 1) testTumorStromaWizardAskExistingDecisions fails with MissingMethodException (QP.getSelectedObjects stub and the new
    //    closures are missing). Patch two places:
    //    (i) in tsWizardPrelude(): replace
    //            ['isOwnSummaryName', 'prevSummaryObjects', 'isOwnOutputObject', 'prevOutputObjects'].collect { tsWizardClosure(it) }.join('\n') + '\n'
    //        with
    //            ['isOwnSummaryName', 'prevSummaryObjects', 'isOwnOutputObject', 'prevOutputObjects',
    //             'summarySource', 'markSummary', 'isToolMadeSummary', 'isReplaceableOutput'].collect { tsWizardClosure(it) }.join('\n') + '\n'
    //        and add the line   def SUMMARY_SOURCE_KEY = 'atolye_ozet_kaynak'   right after   def OUTPUT_TAG = 'Tümör/Stroma ölçüm'   in the same prelude string.
    //    (ii) in that test replace
    //            def QP = new Expando(getCurrentImageData: { -> new Expando(getHierarchy: { -> h }) })
    //        with
    //            def QP = new Expando(getCurrentImageData: { -> new Expando(getHierarchy: { -> h }) }, getSelectedObjects: { -> [] })
    // 2) All other testTumorStromaWizard* methods pass unchanged (verified by running them).
    // New tests below.

    // ---- sihirbaz.groovy: a user's region is never deleted by Replace ----

    // Evaluates the real Replace-bookkeeping source of runMeasure against a hierarchy.
    static Map tsWizardRunReplace(PathObjectHierarchy h, def summaryObj) {
        String s = source(TS_WIZARD_FILE)
        int a = s.indexOf('int removedSummaries = 0')
        int b = s.indexOf('\n        }\n', s.indexOf('if (replaceRun) {', a)) + '\n        }\n'.length()
        String rem = s.substring(a, b)
        def QP = new Expando(removeObjects: { list, keep -> h.removeObjects(list, keep) })
        def sh = shell([QP: QP, summary: summaryObj, replaceRun: true,
            prevSummaries: h.getAnnotationObjects().findAll { it.getName() == 'TSR Özet' || it.getName()?.startsWith('Tümör-Stroma Özet') },
            prevOutputs: (h.getAnnotationObjects() + h.getDetectionObjects()).findAll { it.getName() != null && it.getPathClass() != null && it.getName() == it.getPathClass().getName() && it.isAnnotation() }])
        sh.evaluate(tsWizardPrelude() + '\n' + rem + '\n[removedSummaries: removedSummaries, removedOutputs: removedOutputs]')
    }

    static def tsWizardScene() {
        def tag = 'Tümör/Stroma ölçüm — '
        def user = ann('Tümör-Stroma Özet'); user.getMetadata().put('atolye_ozet_kaynak', 'kullanici')
        def userOut = tsWizardOutput('Tumor', 'Tumor', tag + 'Tumor (m)')
        def userInner = det('Tumor')
        user.addChildObjects([userOut, userInner])
        def tool = ann('Tümör-Stroma Özet (2)', 300); tool.getMetadata().put('atolye_ozet_kaynak', 'arac')
        def toolOut = tsWizardOutput('Stroma', 'Stroma', tag + 'Stroma (m)')
        def toolInner = det('Stroma', 320)
        tool.addChildObjects([toolOut, toolInner])
        def legacy = ann('TSR Özet', 600)                                // unmarked: possibly a user's region
        def legacyOut = tsWizardOutput('Tumor', 'Tumor', tag + 'Tumor (m)')
        legacy.addChildObjects([legacyOut])
        def top = tsWizardOutput('Stroma', 'Stroma', tag + 'Stroma (m)') // summary-less earlier result at top level
        def h = new PathObjectHierarchy()
        h.addObjects([user, tool, legacy, top])
        [h: h, user: user, userOut: userOut, userInner: userInner, tool: tool, toolOut: toolOut, toolInner: toolInner,
         legacy: legacy, legacyOut: legacyOut, top: top]
    }

    void testTumorStromaWizardKeepsUserRegionMarkersDistinguishUserFromTool() {
        def sc = tsWizardScene()
        def got = shell([a: sc.user, b: sc.tool, c: sc.legacy]).evaluate(tsWizardPrelude() +
            "\n[isToolMadeSummary(a), isToolMadeSummary(b), isToolMadeSummary(c), summarySource(c)]")
        assert got == [false, true, false, null]
        // replaceable outputs: top-level, under a tool-made summary, or under the annotation being re-measured
        def r = shell([o1: sc.userOut, o2: sc.toolOut, o3: sc.legacyOut, o4: sc.top, t: sc.user]).evaluate(tsWizardPrelude() +
            "\n[isReplaceableOutput(o1, t), isReplaceableOutput(o2, t), isReplaceableOutput(o3, t), isReplaceableOutput(o4, t), isReplaceableOutput(o1, null)]")
        assert r == [true, true, false, true, false]
        String s = source(TS_WIZARD_FILE)
        assert s.contains("markSummary(summary, 'kullanici')") && s.contains("markSummary(summary, 'arac')")
    }

    // Replace with a previously reused user region selected: re-measured in place, never deleted; the tool-made summary and
    // the replaceable earlier class regions go; objects inside are kept; the unmarked legacy summary and its regions stay.
    void testTumorStromaWizardKeepsUserRegionReusedAndReMeasuredInPlace() {
        def sc = tsWizardScene()
        def r = tsWizardRunReplace(sc.h, sc.user)
        def left = sc.h.getAnnotationObjects()
        assert left.containsAll([sc.user, sc.legacy, sc.legacyOut])
        assert !left.contains(sc.tool)
        assert !left.contains(sc.userOut) && !left.contains(sc.toolOut) && !left.contains(sc.top)
        assert sc.h.getDetectionObjects().containsAll([sc.userInner, sc.toolInner])
        assert r.removedSummaries == 1 && r.removedOutputs == 3
        assert left.count { it.getName() == 'Tümör-Stroma Özet' } == 1
    }

    // Replace when the new summary is a different annotation: earlier user/unmarked summaries and their regions stay.
    void testTumorStromaWizardKeepsUserRegionWhenAnotherSummaryIsWritten() {
        def sc = tsWizardScene()
        def fresh = ann('Yeni özet', 900)
        sc.h.addObjects([fresh])
        def r = tsWizardRunReplace(sc.h, fresh)
        def left = sc.h.getAnnotationObjects()
        assert left.containsAll([sc.user, sc.userOut, sc.legacy, sc.legacyOut, fresh])
        assert !left.contains(sc.tool) && !left.contains(sc.toolOut) && !left.contains(sc.top)
        assert r.removedSummaries == 1 && r.removedOutputs == 2
    }

    // Only unmarked summaries (all made before the marker existed): Replace deletes none of them.
    void testTumorStromaWizardKeepsUserRegionUnmarkedLegacySummary() {
        def legacy = ann('Tümör-Stroma Özet')
        def lo = tsWizardOutput('Tumor', 'Tumor', 'Tümör/Stroma ölçüm — Tumor (m)')
        legacy.addChildObjects([lo])
        def other = ann('TSR Özet', 400)
        def fresh = ann('Yeni', 700)
        def h = new PathObjectHierarchy()
        h.addObjects([legacy, other, fresh])
        def r = tsWizardRunReplace(h, fresh)
        assert h.getAnnotationObjects().containsAll([legacy, other, fresh, lo]) && r.removedSummaries == 0 && r.removedOutputs == 0
        def r2 = tsWizardRunReplace(h, legacy)                           // in place: only its own regions are replaced
        assert h.getAnnotationObjects().contains(legacy) && !h.getAnnotationObjects().contains(lo) && r2.removedSummaries == 0 && r2.removedOutputs == 1
    }

    // Source contract.
    void testTumorStromaWizardKeepsUserRegionSourceContract() {
        String s = source(TS_WIZARD_FILE)
        assert s.contains('def oldSummaries = prevSummaries.findAll { it != summary && isToolMadeSummary(it) }')
        assert s.contains('def oldOutputs = prevOutputs.findAll { it != summary && isReplaceableOutput(it, summary) }')
        assert !s.contains('prevSummaries.findAll { it != summary }')
    }

    static final String COPIES_EXPORT_FILE = 'veri-aktarma.groovy'

    static String copiesExportCode() {
        'import qupath.lib.objects.PathObjects\n' +
        ['headlessArg', 'copiesParse', 'copiesGroups', 'copiesOlder', 'copiesAncestorHit', 'copiesReportLines', 'copiesScanHierarchy']
            .collect { closure(COPIES_EXPORT_FILE, it) }.join('\n')
    }

    static def copiesExportRun(List names, String tail) {
        shell([anns: names.collect { ann(it) }]).evaluate(copiesExportCode() + '\n' + tail)
    }

    void testCopiesExportGroupRule() {
        def r = copiesExportRun(['X', 'X (2)', 'X (3)', 'XY (2)', 'Y (2)', 'Z', 'Z (notlarım)', 'Z (1)',
                'TIA bölge tespiti · Mitotic figure (12)', 'Merkez 1 (1/3)'], '''
def g = copiesGroups(anns, { it.getName() })
def r = [groups: g.keySet() as List, idx: g['X'].keySet() as List, older: copiesOlder(g).collect { it.getName() }.sort()]
r
''')
        assert r.groups == ['X']          // exact match: XY (2), Y (2), Z (1), Z (notlarım) are not copies of anything
        assert r.idx == [1, 2, 3]
        assert r.older == ['X', 'X (2)']   // newest = highest number
    }

    void testCopiesExportPointCountNamesAreNotCopiesWithoutSibling() {
        def r = copiesExportRun(['TIA bölge tespiti · Mitotic figure (12)', 'B (4)'], '''
def g = copiesGroups(anns, { it.getName() })
g.keySet() as List
''')
        assert r == []                     // a point count alone, or a base-less single "B (4)", asks nothing
        def two = copiesExportRun(['TIA bölge tespiti · Mitotic figure (12)', 'TIA bölge tespiti · Mitotic figure (12) (2)'], '''
def g = copiesGroups(anns, { it.getName() })
def r = [keys: g.keySet() as List, idx: g.values().first().keySet() as List, older: copiesOlder(g).collect { it.getName() }]
r
''')
        assert two.keys == ['TIA bölge tespiti · Mitotic figure (12)']
        assert two.idx == [1, 2]
        assert two.older == ['TIA bölge tespiti · Mitotic figure (12)']
        def center = copiesExportRun(['Merkez 1 (1/3)', 'Merkez 1 (1/3) (2)'], '''
copiesGroups(anns, { it.getName() }).keySet() as List
''')
        assert center == ['Merkez 1 (1/3)']
    }

    void testCopiesExportDocumentedLimitTwoNumberedNamesWithoutBase() {
        def r = copiesExportRun(['A (12)', 'A (3)'], '''
def g = copiesGroups(anns, { it.getName() })
def r = [keys: g.keySet() as List, idx: g['A'].keySet() as List, older: copiesOlder(g).collect { it.getName() }]
r
''')
        assert r.keys == ['A']             // limit: a name alone cannot tell a point count from a copy number
        assert r.idx == [3, 12]
        assert r.older == ['A (3)']
    }

    void testCopiesExportNewestExcludesOlderCopyAndObjectsInsideIt() {
        def h = new PathObjectHierarchy()
        def old = ann('Özet'), newest = ann('Özet (2)', 300)
        def nested = PathObjects.createAnnotationObject(ROIs.createRectangleROI(20, 20, 10, 10, ImagePlane.getDefaultPlane()))
        nested.setName('İç')
        def dOld = det('c', 50), dNew = det('c', 310), dFree = det('c', 1000)
        h.insertPathObjects([old, newest, nested, dOld, dNew, dFree])   // inserted by ROI containment: nested + detections get parents
        assert nested.getParent().is(old) && dOld.getParent().is(old) && dNew.getParent().is(newest)
        def sc = shell([h: h]).evaluate(copiesExportCode() + '\ncopiesScanHierarchy(h)')
        assert sc.ids == [old.getID()] as Set
        assert sc.leftAnn == 2             // the older copy and the annotation inside it
        assert sc.leftDet == 1             // only the detection inside the older copy
        def hit = shell([ids: sc.ids, objs: [old, nested, dOld, newest, dNew, dFree]]).evaluate(
            copiesExportCode() + '\nobjs.collect { copiesAncestorHit(it, ids) }')
        assert hit == [true, true, true, false, false, false]
    }

    void testCopiesExportWithoutCopyGroupAsksNothing() {
        def h = new PathObjectHierarchy()
        h.addObjects([ann('A'), ann('B', 300), ann('B (notlarım)', 600)])
        assert shell([h: h]).evaluate(copiesExportCode() + '\ncopiesScanHierarchy(h)') == null
    }

    void testCopiesExportReportLinesAndHeadlessArgument() {
        def sc = [name: 'S1', scan: [groups: [X: new TreeMap([1: [], 2: [], 3: []])]]]
        def lines = shell([res: [k: sc]]).evaluate(copiesExportCode() + '\n[copiesReportLines(res, false), copiesReportLines(res, true)]')
        assert lines[0] == ['S1 · X: 1, 2, 3']
        assert lines[1] == ['S1 · X: 1, 2, 3 → 3']
        def arg = { List a -> shell([args: a]).evaluate(copiesExportCode() + "\nheadlessArg('kopyalar')") }
        assert arg(['kopyalar=ENYENI']) == 'enyeni'
        assert arg(['x=1', ' kopyalar = hepsi ']) == 'hepsi'
        assert arg([]) == null
        assert arg(['kopyalar']) == null
    }

    void testCopiesExportQuestionBeforeAnyWriteAndFailClosed() {
        String s = source(COPIES_EXPORT_FILE)
        assert !s.contains('.getChildObjects()')
        int bad = s.indexOf("copiesArg != 'hepsi' && copiesArg != 'enyeni'")
        int opts = s.indexOf('def opt = showExportOptions(defaults)')
        assert bad > 0 && bad < opts       // unknown value stops even when no copies exist
        assert s.substring(bad, bad + 260).contains('return')
        int ask = s.indexOf("copiesAskChoice(workshopText('script.2a83adf213c8'")
        int cancel = s.indexOf('if (copiesPick == null)')
        int stop = s.indexOf("workshopText('script.49a1c50ae61c'")
        int save = s.indexOf('currentEntry.saveImageData(')
        int mk = s.indexOf('outDir.mkdirs()')
        int exp = s.indexOf('exportMeasurements(')
        assert ask > 0 && stop > 0 && ask < cancel
        assert s.substring(cancel, cancel + 260).contains('return')   // Cancel returns before anything is written
        assert s.substring(stop, s.indexOf('\n', stop) + 120).contains('return')       // headless without the argument stops
        assert cancel < save && cancel < mk && mk < exp && stop < mk
        assert s.indexOf('Dialogs.showChoiceDialog(title, body, options, dflt)') > 0
        assert s.indexOf('[copiesOptAll, copiesOptNewest], copiesOptAll)') > ask
    }

    void testCopiesExportNewestFilterWiringAndSidecar() {
        String s = source(COPIES_EXPORT_FILE)
        assert s.count('.filter(copiesPredicate(') >= 4          // detections + annotations, per image and combined
        assert s.contains('(opt.det as boolean) && !copiesCombinedSkip')
        assert s.contains('(opt.ann as boolean) && !copiesCombinedSkip')
        assert s.contains('".kopyalar.txt"')
        // data columns and file format untouched: no copy information is written into measurement files
        assert !s.contains('includeOnlyColumns(copies')
        assert s.indexOf('if (exIds != null) annos = annos.findAll') > 0   // GeoJSON follows the same rule
    }

    static final String COPIES_COHORT_FILE = 'yardimci-kohort-ozet-topla.groovy'

    static String copiesCohortCode() {
        ['headlessArg', 'copiesParse', 'copiesGroups', 'copiesOlder', 'copiesBuildRows']
            .collect { closure(COPIES_COHORT_FILE, it) }.join('\n')
    }

    static def copiesCohortRecs() {
        // S1: older locked "Özet" and newest locked "Özet (2)"; S2: a single "Özet" with no copy
        [[name: 'S1', affected: [Özet: new TreeMap([1: [], 2: []])], items: [
            [base: 'Özet', older: true, cells: [a: '1', b: '5']],
            [base: 'Özet (2)', older: false, cells: [a: '2']]]],
         [name: 'S2', affected: [:], items: [[base: 'Özet', older: false, cells: [a: '7']]]]]
    }

    void testCopiesCohortAllKeepsEveryCopyLabelled() {
        def b = shell([recs: copiesCohortRecs()]).evaluate(copiesCohortCode() + "\ncopiesBuildRows(recs, 'all')")
        assert b.columnOrder as List == ['Özet · a', 'Özet · b', 'Özet (2) · a']
        assert b.rows[0]['Özet · a'] == '1' && b.rows[0]['Özet (2) · a'] == '2'
        assert b.rows[0]['kopya_bilgisi'] == 'Özet: 1,2'
        assert b.rows[1]['kopya_bilgisi'] == ''
        assert b.leftAnnotations == 0 && b.leftColumns.isEmpty()
    }

    void testCopiesCohortNewestDropsOlderAndCountsColumns() {
        def b = shell([recs: copiesCohortRecs()]).evaluate(copiesCohortCode() + "\ncopiesBuildRows(recs, 'newest')")
        assert b.columnOrder as List == ['Özet (2) · a', 'Özet · a']   // 'Özet · a' still comes from S2
        assert b.rows[0]['Özet · a'] == null
        assert b.rows[0]['Özet (2) · a'] == '2'
        assert b.rows[0]['kopya_bilgisi'] == 'Özet: 1,2 -> 2'
        assert b.leftAnnotations == 1
        assert b.leftColumns as List == ['Özet · b']                    // only columns that vanish from the table
    }

    void testCopiesCohortNoCopyBehaviourUnchanged() {
        def recs = [[name: 'S1', affected: [:], items: [
            [base: 'Özet', older: false, cells: [a: '1']], [base: 'Özet', older: false, cells: [a: '2']]]]]
        def b = shell([recs: recs]).evaluate(copiesCohortCode() + "\ncopiesBuildRows(recs, null)")
        assert b.columnOrder as List == ['Özet · a', 'Özet (2) · a']    // same-name ordinal columns, as before
        assert !b.rows[0].containsKey('kopya_bilgisi')
        assert b.duplicateImages as List == ['S1']
    }

    void testCopiesCohortOrdinalColumnNeverCollidesWithARealCopy() {
        def recs = [[name: 'S1', affected: [Özet: new TreeMap([1: [], 2: []])], items: [
            [base: 'Özet', older: false, cells: [a: '1']], [base: 'Özet', older: false, cells: [a: '2']],
            [base: 'Özet (2)', older: false, cells: [a: '3']]]]]
        def b = shell([recs: recs]).evaluate(copiesCohortCode() + "\ncopiesBuildRows(recs, 'all')")
        assert b.rows[0]['Özet (2) · a'] == '3'
        assert b.rows[0]['Özet [2] · a'] == '2'
    }

    void testCopiesCohortGroupDetectionAndHeadlessArgument() {
        def anns = ['Özet', 'Özet (2)', 'Özet (notlarım)', 'Özet (1)', 'Başka (2)'].collect { ann(it) }
        def r = shell([anns: anns]).evaluate(copiesCohortCode() + '''
def g = copiesGroups(anns, { it.getName() })
[g.keySet() as List, copiesOlder(g).collect { it.getName() }]
''')
        assert r[0] == ['Özet'] && r[1] == ['Özet']
        def arg = { List a -> shell([args: a]).evaluate(copiesCohortCode() + "\nheadlessArg('kopyalar')") }
        assert arg(['kopyalar=Enyeni']) == 'enyeni'
        assert arg([]) == null
    }

    void testCopiesCohortQuestionBeforeWriteAndFailClosed() {
        String s = source(COPIES_COHORT_FILE)
        assert !s.contains('.getChildObjects()')
        int bad = s.indexOf("copiesArg != 'hepsi' && copiesArg != 'enyeni'")
        assert bad > 0 && bad < s.indexOf('def project = QP.getProject()')
        assert s.substring(bad, bad + 260).contains('return')
        int ask = s.indexOf("copiesAskChoice(workshopText('script.08096a5c93b4'")
        int cancel = s.indexOf('if (copiesPick == null)')
        int stop = s.indexOf("workshopText('script.81b5fc1eeb8b'")
        int mk = s.indexOf('outDir.mkdirs()')
        int write = s.indexOf('tsvFile.withWriter(')
        assert ask > 0 && ask < cancel && cancel < mk && mk < write && stop < mk
        assert s.substring(cancel, cancel + 260).contains('return')
        assert s.substring(stop, s.indexOf('\n', stop) + 120).contains('return')
        assert s.contains('ix.values().flatten().any { it.isLocked() }')   // only groups with a locked member are asked about
        assert s.contains('if (copiesChoice != null) colList << "kopya_bilgisi"')
    }

    static final String COPIES_GQC_FILE = 'yardimci-grandqc-kohort-ozet.groovy'

    static String copiesGrandQcCode() {
        ['headlessArg', 'copiesParse', 'copiesSetsOf', 'copiesPickSets']
            .collect { closure(COPIES_GQC_FILE, it) }.join('\n')
    }

    void testCopiesGrandQcSetsSeeNumberedSetsOnly() {
        def anns = ['GrandQC KK', 'GrandQC KK', 'GrandQC KK (2)', 'GrandQC KK (3)', 'GrandQC KK (notlarım)',
                    'GrandQC KK (1)', 'GrandQC KK extra', 'Başka (2)', 'GrandQC KK (2) (2)'].collect { ann(it) }
        def r = shell([anns: anns]).evaluate(copiesGrandQcCode() + '''
def s = copiesSetsOf(anns, 'GrandQC KK')
[s.keySet() as List, s.values().collect { it.size() }]
''')
        assert r[0] == [1, 2, 3]
        assert r[1] == [2, 1, 1]            // two same-named annotations are ONE set (index 1)
        def lone = shell([anns: [ann('GrandQC KK (4)')]]).evaluate(copiesGrandQcCode() + "\ncopiesSetsOf(anns, 'GrandQC KK').keySet() as List")
        assert lone == [4]                   // base deleted by hand: the only set, summarised, no question
        assert shell([anns: [ann('Başka')]]).evaluate(copiesGrandQcCode() + "\ncopiesSetsOf(anns, 'GrandQC KK').isEmpty()")
    }

    void testCopiesGrandQcPickNewestPerImage() {
        def rows = [[img: 0, index: 1, setName: 'GrandQC KK'], [img: 0, index: 3, setName: 'GrandQC KK (3)'],
                    [img: 0, index: 2, setName: 'GrandQC KK (2)'], [img: 1, index: 1, setName: 'GrandQC KK'],
                    [img: 2, index: 2, setName: 'GrandQC KK (2)']]
        def all = shell([rows: rows]).evaluate(copiesGrandQcCode() + '\ncopiesPickSets(rows, false)')
        assert all == rows                   // all copies: one row per set, nothing dropped
        def newest = shell([rows: rows]).evaluate(copiesGrandQcCode() + '\ncopiesPickSets(rows, true)')
        assert newest.collect { it.setName } == ['GrandQC KK (3)', 'GrandQC KK', 'GrandQC KK (2)']
        assert newest.collect { it.img } == [0, 1, 2]    // one row per image, image order kept
    }

    void testCopiesGrandQcHeadlessArgument() {
        def arg = { List a -> shell([args: a]).evaluate(copiesGrandQcCode() + "\nheadlessArg('kopyalar')") }
        assert arg(['kopyalar=HEPSI']) == 'hepsi'
        assert arg(['kopyalar=bilinmeyen']) == 'bilinmeyen'   // the script rejects it, see source contract
        assert arg([]) == null
    }

    void testCopiesGrandQcQuestionBeforeWriteAndColumnsUnchanged() {
        String s = source(COPIES_GQC_FILE)
        assert !s.contains('.getChildObjects()')
        int bad = s.indexOf("copiesArg != 'hepsi' && copiesArg != 'enyeni'")
        assert bad > 0 && bad < s.indexOf('def project = QP.getProject()')
        assert s.substring(bad, bad + 260).contains('return')
        int ask = s.indexOf("copiesAskChoice(workshopText('script.63ce111d915c'")
        int cancel = s.indexOf('if (copiesPick == null)')
        int stop = s.indexOf("workshopText('script.14de14c01439'")
        int mk = s.indexOf('outDir.mkdirs()')
        int write = s.indexOf('tsvFile.withWriter(')
        assert ask > 0 && ask < cancel && cancel < mk && mk < write && stop < mk
        assert s.substring(cancel, cancel + 260).contains('return')
        assert s.substring(stop, s.indexOf('\n', stop) + 120).contains('return')
        // existing columns/order untouched; the set column exists only when copies were found
        assert s.contains('def columns = ["image_name", "doku_mm2", "temiz_doku_mm2", "artefakt_mm2", "artefakt_yuzde", "doku_parca_sayisi"]')
        int setCol = s.indexOf('columns << "set_adi"')
        assert setCol > 0 && s.lastIndexOf('if (copiesChoice != null) {', setCol) > s.indexOf('def chosenRows')
        assert !s.contains('it.getName() == GRANDQC_SENTINEL')   // no more exact-name-only read
    }

    // Source-order contract only: interactive dialog/cancellation acceptance remains manual.
    void testConfirmationAndLateCancellationContracts() {
        [HISTO, CYTO, NUC,
         'yardimci-classpose-sihirbaz.groovy',
         'yardimci-hovernext-sihirbaz.groovy',
         'yardimci-mitoz-fcos.groovy',
         'yardimci-mitoz-retinanet.groovy',
         'yardimci-mitoz-tiatoolbox.groovy'].each { file ->
            def s = source(file)
            def start = s.indexOf('def startRun =')
            assert start >= 0 : "Missing startRun in $file"
            def run = s.substring(start)
            assert run.indexOf('showChoiceDialog') >= 0 : "Missing showChoiceDialog in $file"
            assert run.indexOf('showChoiceDialog') < run.indexOf('workDir.mkdirs()') : "Choice dialog must precede workDir.mkdirs() in $file"
            assert run.contains('if (pick == null) return') : "Missing null-cancel check in $file"
            assert run.contains('if (!ok) return') : "Missing replace-confirm cancel check in $file"
        }
        assert source(HISTO).contains("if (cancelledRef.get()) { javafx.application.Platform.runLater { step.set('READY'); render() }; return }\n            def imp = importCells")
        assert source(CYTO).contains("if (cancelledRef.get()) { step.set('READY'); render(); return }\n                    def applyStats = applyAssignments")
        assert source(NUC).contains("if (cancelledRef.get()) { step.set('READY'); render(); return }\n                def imp = importResults")
    }

    void testHistoPlusCanonicalCellTypeAliases() {
        String s = source(HISTO)
        int cStart = s.indexOf('def CLASSES = [')
        int cEnd = s.indexOf('def UNDOCUMENTED_NOTE =', cStart)
        assert cStart >= 0 && cEnd > cStart
        def block = s.substring(cStart, cEnd)
        shell().evaluate(block + '''
assert canonicalKeyOf('Cancer cell') == 'Cancer cell'
assert canonicalKeyOf('cancer cells') == 'Cancer cell'
assert canonicalKeyOf('Tumor') == 'Cancer cell'
assert canonicalKeyOf('lymphocyte') == 'Lymphocytes'
assert canonicalKeyOf('Plasma Cell') == 'Plasmocytes'
assert canonicalKeyOf('mitosis') == 'Mitotic Figures'
assert trOf('lymphocyte') == 'Lenfosit'
assert trOf('Unknown Custom') == 'Unknown Custom'
''')
    }

    void testErrorReportRedactsHomeAndSlideFilenames() {
        def ex = new java.io.FileNotFoundException('Cannot open C:\\Users\\dr_ayse\\Slides\\patient_12345_biopsy.svs or /Users/ali/cohort_case.ndpi')
        String report = WorkshopErrors.formatCopyableReport('Hücre tespiti', 'hucre-tespiti.groovy', ex)
        assert !report.contains('dr_ayse')
        assert !report.contains('/Users/ali')
        assert !report.contains('patient_12345_biopsy.svs')
        assert !report.contains('cohort_case.ndpi')
        assert report.contains('<HOME>')
        assert report.contains('<SLIDE_FILE>')
    }
}
