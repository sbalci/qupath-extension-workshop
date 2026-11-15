package io.github.sbalci.qupath.workshop

import groovy.test.GroovyTestCase
import qupath.lib.objects.PathObjects
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs

/** Round 2 (2026-11-16): the tile exporter asks "all copies or only the newest" and its choice lists use stable ids.
 * Runs production closures / reads production source; no GUI, no image, no Python.
 */
class TileExportCopiesAndChoicesTest extends GroovyTestCase {
    static final String TILE = 'yardimci-karo-disa-aktarma.groovy'

    static String src(String f) { SafetyRegressionTest.source(f) }
    static String closure(String f, String n) { SafetyRegressionTest.closure(f, n) }

    static String copiesCode() {
        'import qupath.lib.objects.PathObjects\n' +
        ['headlessArg', 'copiesParse', 'copiesGroups', 'copiesOlder', 'copiesAncestorHit', 'copiesReportLines', 'copiesScanHierarchy']
            .collect { closure(TILE, it) }.join('\n')
    }

    // ---- copies: only annotations are scanned; older copy and what is inside it is excluded ----------------------
    void testScanSeesOnlyAnnotationsAndExcludesOlderCopyWithChildren() {
        def h = new PathObjectHierarchy()
        def old = SafetyRegressionTest.ann('Tümör'), newest = SafetyRegressionTest.ann('Tümör (2)', 300)
        def mine = SafetyRegressionTest.ann('Tümör (notlarım)', 600)           // a user's own name: never a copy
        def nested = PathObjects.createAnnotationObject(ROIs.createRectangleROI(20, 20, 10, 10, ImagePlane.getDefaultPlane()))
        nested.setName('İç')
        def d = SafetyRegressionTest.det('c', 50)
        h.insertPathObjects([old, newest, mine, nested, d])
        def sc = SafetyRegressionTest.shell([h: h]).evaluate(copiesCode() + '\ncopiesScanHierarchy(h)')
        assert sc.ids == [old.getID()] as Set
        assert sc.leftAnn == 2                      // the older copy and the annotation inside it
        assert !sc.containsKey('leftDet')           // detections never reach the tile output
        def hit = SafetyRegressionTest.shell([ids: sc.ids, objs: [old, nested, newest, mine]]).evaluate(
            copiesCode() + '\nobjs.collect { copiesAncestorHit(it, ids) }')
        assert hit == [true, true, false, false]    // the user's own "Tümör (notlarım)" stays
    }

    void testNoCopyGroupAsksNothing() {
        def h = new PathObjectHierarchy()
        h.addObjects([SafetyRegressionTest.ann('A'), SafetyRegressionTest.ann('B', 300), SafetyRegressionTest.ann('B (notlarım)', 600), SafetyRegressionTest.ann('C (2)', 900)])
        assert SafetyRegressionTest.shell([h: h]).evaluate(copiesCode() + '\ncopiesScanHierarchy(h)') == null
    }

    void testHeadlessArgumentAndSidecarLines() {
        def arg = { List a -> SafetyRegressionTest.shell([args: a]).evaluate(copiesCode() + "\nheadlessArg('kopyalar')") }
        assert arg(['kopyalar=ENYENI']) == 'enyeni'
        assert arg(['mod=maske', ' kopyalar = hepsi ']) == 'hepsi'
        assert arg([]) == null
        def sc = [name: 'S1', scan: [groups: [X: new TreeMap([1: [], 2: [], 3: []])]]]
        def lines = SafetyRegressionTest.shell([res: [image: sc]]).evaluate(copiesCode() + '\n[copiesReportLines(res, false), copiesReportLines(res, true)]')
        assert lines[0] == ['S1 · X: 1, 2, 3']
        assert lines[1] == ['S1 · X: 1, 2, 3 → 3']
    }

    // ---- copies: order of events in the script -------------------------------------------------------------------
    void testQuestionBeforeFormAndAnyWriteAndFailClosed() {
        String s = src(TILE)
        assert !s.contains('.getChildObjects()')
        int bad = s.indexOf("copiesArg != 'hepsi' && copiesArg != 'enyeni'")
        int scan = s.indexOf('copiesScan = copiesScanHierarchy(imageData.getHierarchy())')
        int form = s.indexOf('def opt = showOptionsForm(')
        int headlessStop = s.indexOf('if (copiesArg == null) {')
        int cancel = s.indexOf('if (copiesPick == null)')
        int filterAll = s.indexOf('allAnns = allAnns.findAll { !copiesAncestorHit(it, copiesExcludedIds) }')
        int mk = s.indexOf('outDir.mkdirs()')
        int labels = s.indexOf('labelsFile.write(')
        int wipe = s.indexOf('it.delete()')
        assert bad > 0 && bad < scan && scan < form                     // unknown value stops even when no copies exist
        assert s.substring(bad, bad + 300).contains('return')
        assert s.substring(headlessStop, headlessStop + 700).contains('return')   // headless without the argument stops
        assert s.substring(cancel, cancel + 160).contains('return')                // Cancel writes nothing
        assert cancel < filterAll && filterAll < form                              // form lists only what will be exported
        assert cancel < mk && cancel < labels && cancel < wipe
        // option order and default: all copies first and preselected
        assert s.contains('copiesLabels, copiesLabels[0])')
        assert s.contains("def copiesIds = ['all', 'newest']")
        assert !s.contains('copiesPick == copiesOpt')                              // never compares displayed text
        // the headless form accepts the argument (it was validated above)
        assert s.contains("k == 'kopyalar' && ['hepsi', 'enyeni'].contains(v)")
    }

    void testNewestFilterReachesTilesAndMaskAndSidecar() {
        String s = src(TILE)
        assert s.contains('selectedAnns = selectedAnns.findAll { !copiesAncestorHit(it, copiesExcludedIds) }')
        // the label mask would otherwise paint the older copies: the builder gets an annotation-only filter that also skips them
        assert s.contains('builder.useFilter({ o -> o.isAnnotation() && !copiesAncestorHit(o, keepOut) } as java.util.function.Predicate)')
        // choice is recorded in a sidecar next to the tile list, never as a new tile-list column
        assert s.contains('slug + ".kopyalar.txt"')
        assert s.contains("w.writeLine(['lam', 'hasta', 'bolme', 'bolme_kaynagi', 'etiket', 'sinif', 'anotasyon_adlari', 'x', 'y', 'kenar_taban_px', 'karo_px', 'downsample', 'um_px', 'kapsama', 'doku_orani', 'dosya', 'maske'].join('\\t'))")
        assert s.contains('noteBlock, copiesBlock')
    }

    // ---- choice lists: stable ids, displayed text never compared -------------------------------------------------
    void testChoiceListsUseIdsOnly() {
        String s = src(TILE)
        for (String bad : ['startsWith("Seçili")', 'startsWith("Görüntü +")', 'startsWith("JPG")', 'startsWith("PNG")',
                           'startsWith("Anotasyon adına")', 'startsWith("Sınıf +")', 'startsWith("<lam>")', 'contains("lam klasörü yok")',
                           'getValue().startsWith', 'choice == WIPE', 'choice == null'])
            assert !s.contains(bad) : bad
        assert s.contains("def modeIds   = ['image', 'mask']")
        assert s.contains("def scopeIds  = ['all', 'selected']")
        assert s.contains("def formatIds = ['png', 'jpg', 'tiff']")
        assert s.contains("def formatExt = [png: '.png', jpg: '.jpg', tiff: '.tif']")
        assert s.contains("def groupIds  = ['class', 'name', 'both']")
        assert s.contains("def layoutIds = ['label-slide', 'slide-label', 'label']")
        // old-tile dialog: only an explicit pick of the second option wipes; null cancels; unknown pick keeps
        assert s.contains("def oldTileIds = ['keep', 'wipe']")
        assert s.contains("choiceId = (oldTileIdx >= 0) ? oldTileIds[oldTileIdx] : 'keep'")
        assert s.contains("if (choiceId == 'wipe') {")
        // the preferences are not used for any choice, so there is no stored Turkish text to migrate
        assert s.count("atolyeS('") == 1 && s.contains("atolyeS('atolye.exportFolder'")
    }

    void testIdOfMapsSelectionIndexToId() {
        String s = src(TILE)
        int a = s.indexOf('def idOf = {')
        int e = s.indexOf('\n', a)
        String code = s.substring(a, e).replace('{ javafx.scene.control.ChoiceBox b, List ids ->', '{ b, List ids ->')
        def r = SafetyRegressionTest.shell().evaluate(code + '''
def ids = ['all', 'selected']
def box = { int i -> new Expando(getSelectionModel: { -> new Expando(getSelectedIndex: { -> i }) }) }
return [idOf(box(0), ids), idOf(box(1), ids), idOf(box(-1), ids)]
''')
        assert r == ['all', 'selected', 'all']
    }
}
