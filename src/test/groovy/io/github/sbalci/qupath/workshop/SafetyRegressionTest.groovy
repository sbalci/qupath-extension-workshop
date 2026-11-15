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
