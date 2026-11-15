package io.github.sbalci.qupath.workshop

import groovy.test.GroovyTestCase
import qupath.lib.objects.PathObjects
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs

/** Round 2 (16 Nov 2026): the batch annotation export asks "all copies or only the newest" at export time,
 * like veri-aktarma.groovy. Runs the production closures of yardimci-anotasyon-disa-aktar.groovy against
 * QuPath core objects; no GUI, no project, no image server.
 *
 * Needs the script text AFTER the @WT:..@ tokens were replaced by workshopText(...) calls; the shells below
 * therefore bind a stub workshopText that returns the Turkish fallback.
 */
class AnnotationExportCopiesTest extends GroovyTestCase {
    static final String FILE = 'yardimci-anotasyon-disa-aktar.groovy'

    static String code(List names) {
        "import qupath.lib.objects.classes.PathClass\ndef UNCLASSIFIED = '(sınıfsız)'\n" +
            names.collect { SafetyRegressionTest.closure(FILE, it) }.join('\n')
    }

    static final List PREPARE = ['copiesParse', 'copiesGroups', 'copiesOlder', 'copiesAncestorHit', 'copiesReportLines',
                                 'copiesScanHierarchy', 'copiesTargetKey', 'copiesPrepare']

    static def run(Map vars, List names, String tail) {
        def all = [workshopText: { String k, String f -> f }] + vars
        SafetyRegressionTest.shell(all).evaluate(code(names) + '\n' + tail)
    }

    // A project entry stand-in: only getID() is used by copiesTargetKey.
    static def target(String name, PathObjectHierarchy h, String id) {
        [name: name, entry: new Expando(getID: { id }), live: new Expando(getHierarchy: { h })]
    }

    static PathObjectHierarchy hierarchyWithCopies() {
        def h = new PathObjectHierarchy()
        h.addObjects([SafetyRegressionTest.ann('Özet'), SafetyRegressionTest.ann('Özet (2)', 300), SafetyRegressionTest.ann('Kendi notum', 600)])
        h
    }

    void testHeadlessStopsWhenCopiesExistAndNoValueGiven() {
        def h = hierarchyWithCopies()
        def err = null
        try {
            run([ts: [target('S1', h, 'id1')]], PREPARE, "copiesPrepare(ts, null, true, null)")
        } catch (IllegalArgumentException e) { err = e }
        assert err != null                                   // stopped before anything was written
        assert err.getMessage().contains('S1 · Özet: 1, 2')   // the message lists the groups
    }

    void testHeadlessAllAndNewest() {
        def h = hierarchyWithCopies()
        def all = run([ts: [target('S1', h, 'id1')]], PREPARE, "copiesPrepare(ts, 'hepsi', true, null)")
        assert all.choice == 'all' && !all.cancelled && all.excluded.isEmpty()
        def newest = run([ts: [target('S1', h, 'id1')]], PREPARE, "copiesPrepare(ts, 'enyeni', true, null)")
        assert newest.choice == 'newest' && newest.excluded.keySet() as List == ['id1']
        def older = h.getAnnotationObjects().find { it.getName() == 'Özet' }
        assert newest.excluded['id1'] == [older.getID()] as Set       // only the older copy; "Kendi notum" stays
    }

    void testNoCopiesAsksNothingEvenWithValue() {
        def h = new PathObjectHierarchy()
        h.addObjects([SafetyRegressionTest.ann('A'), SafetyRegressionTest.ann('B', 300), SafetyRegressionTest.ann('B (notlarım)', 600)])
        def r = run([ts: [target('S1', h, 'id1')], boom: { a, b, c, d -> throw new IllegalStateException('asked') }],
                    PREPARE, "copiesPrepare(ts, null, false, boom)")
        assert r.choice == null && !r.cancelled
    }

    void testGuiAsksOnceKeepsAllPreselectedAndCancelDoesNothing() {
        def h = hierarchyWithCopies()
        def calls = []
        def pickSecond = { String t, String b, List o, String d -> calls << [t, b, o, d]; o[1] }
        def r = run([ts: [target('S1', h, 'id1')], ask: pickSecond], PREPARE, "copiesPrepare(ts, null, false, ask)")
        assert calls.size() == 1
        assert calls[0][2].size() == 2 && calls[0][3] == calls[0][2][0]      // default = "all copies" (first option)
        assert calls[0][1].contains('S1 · Özet: 1, 2')                       // examples are shown in the question
        assert r.choice == 'newest' && r.excluded.size() == 1
        def pickFirst = { String t, String b, List o, String d -> o[0] }
        assert run([ts: [target('S1', h, 'id1')], ask: pickFirst], PREPARE, "copiesPrepare(ts, null, false, ask)").choice == 'all'
        def cancel = run([ts: [target('S1', h, 'id1')], ask: { t, b, o, d -> null }], PREPARE, "copiesPrepare(ts, null, false, ask)")
        assert cancel.cancelled && cancel.choice == null && cancel.excluded.isEmpty()
    }

    void testSelectObjectsLeavesOutOlderCopyAndWhatIsInsideItOnly() {
        def h = new PathObjectHierarchy()
        def old = SafetyRegressionTest.ann('Özet'), newest = SafetyRegressionTest.ann('Özet (2)', 300), mine = SafetyRegressionTest.ann('Kendi notum', 700)
        def nested = PathObjects.createAnnotationObject(ROIs.createRectangleROI(20, 20, 10, 10, ImagePlane.getDefaultPlane()))
        nested.setName('İç')
        def dOld = SafetyRegressionTest.det('c', 50), dNew = SafetyRegressionTest.det('c', 310), dFree = SafetyRegressionTest.det('c', 1000)
        h.insertPathObjects([old, newest, mine, nested, dOld, dNew, dFree])
        def names = ['copiesParse', 'copiesGroups', 'copiesOlder', 'copiesAncestorHit', 'copiesScanHierarchy', 'classNameOf', 'classMatches', 'selectObjects']
        def res = run([h: h], names, '''
def sc = copiesScanHierarchy(h)
[ids: sc.ids,
 withDet: selectObjects(h, true, null, sc.ids).size(),
 withoutDet: selectObjects(h, false, null, sc.ids).collect { it.getName() }.sort(),
 unfiltered: selectObjects(h, true, null).size(),
 unfilteredNull: selectObjects(h, true, null, null).size()]
''')
        assert res.ids == [old.getID()] as Set
        assert res.unfiltered == 7 && res.unfilteredNull == 7     // no exclusion: everything is exported as before
        assert res.withDet == 4                                    // newest, mine, dNew, dFree (old, nested, dOld left out)
        assert res.withoutDet == ['Kendi notum', 'Özet (2)']       // the user's own annotation is never hidden
    }

    void testSourceContractQuestionBeforeAnyWriteAndFailClosed() {
        String s = SafetyRegressionTest.source(FILE)
        assert !s.contains('.getChildObjects()')
        assert s.contains("'ayrac', 'kopyalar']")
        // headless: unknown value stops first, then the question/stop happens before runExport writes anything
        int bad = s.indexOf("copiesArg != 'hepsi' && copiesArg != 'enyeni'")
        int prepH = s.indexOf('copiesPrepare(targets, copiesArg, true, null)')
        int runH = s.indexOf('def res = runExport(cfg, targets, { int i, int n, String nm -> println')
        assert bad > 0 && prepH > bad && runH > prepH
        assert s.substring(bad, bad + 400).contains('throw new IllegalArgumentException')
        // GUI: ask in the worker before runExport; Cancel returns before runExport
        int prepG = s.indexOf('copiesPrepare(targets, null, false, askCopiesGui)')
        int cancel = s.indexOf('if (copiesPrep.cancelled)', prepG)
        int runG = s.lastIndexOf('def res = runExport(cfg, targets, { int i, int n, String nm ->')
        assert prepG > 0 && cancel > prepG && runG > cancel
        assert s.substring(cancel, runG).contains('return')
        // the default/preselected option is the first one (all copies), and the closure names are the reference ones
        assert s.contains('[copiesOptAll, copiesOptNewest], copiesOptAll)')
        assert s.contains("out.choice = (copiesArg == 'enyeni') ? 'newest' : 'all'")
    }
}
