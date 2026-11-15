package io.github.sbalci.qupath.workshop

import groovy.test.GroovyTestCase
import qupath.lib.objects.PathObjects
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs

/** Round 2 (2026-11-16): StarDist filter asks first, mitosis hub orphan-ring clean-up, choice lists with stable ids.
 * Runs production closures / reads production source; no GUI, no Python.
 */
class Round2AskAndChoiceTest extends GroovyTestCase {
    static final String STARDIST = 'yardimci-stardist-sihirbaz.groovy'
    static final String HUB = 'yardimci-mitoz-merkez.groovy'
    static final String PERI = 'yardimci-peritumoral-bant.groovy'
    static final String VALIS = 'yardimci-valis-sihirbaz.groovy'

    static String src(String f) { SafetyRegressionTest.source(f) }
    static String closure(String f, String n) { SafetyRegressionTest.closure(f, n) }

    // ---- StarDist: the false-class removal is behind a choice dialog, keep is the default --------------------
    void testStarDistFilterAsksBeforeRemoving() {
        String s = src(STARDIST)
        // exactly one removal of the false-class detections, and only after the question
        assert s.count('selected.removePathObjects(bad)') == 1
        int ask = s.indexOf('chooseRemoveFalseClass(removeTitleText')
        int rem = s.indexOf('selected.removePathObjects(bad)')
        assert ask > 0 && rem > ask && rem - ask < 400
        // keep branch: nothing removed, count remembered for the result text
        assert s.contains('keptFalse = badN')
        assert s.contains('removedFalse = badN')
        String q = closure(STARDIST, 'chooseRemoveFalseClass')
        // keep is preselected and listed first; only an explicit pick of the removal option returns true
        assert q.contains('[keepOpt, removeOpt], keepOpt)')
        assert q.contains('ask() == removeOpt')
        assert q.contains('picked.get() == removeOpt')   // closing the dialog gives null -> not removeOpt -> keep
        // the density uses all detections left under the annotation, so kept ones are included
        assert s.contains('def dets = selected.getChildObjects().findAll { it.isDetection() }')
        assert s.contains('int n = dets.size()')
        // the headless branch never reaches the filter (wizard is GUI-only)
        assert s.indexOf('if (isHeadless) {') < s.indexOf('def startDetection')
    }

    // ---- Mitosis hub: orphan rings recognised only by the exact link key ----------------------------------------
    static def pt(double x) { PathObjects.createAnnotationObject(ROIs.createPointsROI(x, 50, ImagePlane.getDefaultPlane())) }
    static def ring(String name, String link) {
        def r = PathObjects.createAnnotationObject(ROIs.createEllipseROI(0, 0, 10, 10, ImagePlane.getDefaultPlane()))
        r.setName(name)
        if (link != null) r.getMetadata().put('atolye.mitoz.nokta', link)
        r
    }

    void testHubFindsOnlyOwnRingsWithoutPoint() {
        def h = new PathObjectHierarchy()
        def livePoint = pt(10)
        def liveDet = PathObjects.createDetectionObject(ROIs.createPointsROI(30, 50, ImagePlane.getDefaultPlane()))
        def deadId = UUID.randomUUID().toString()
        def okRing = ring('FCOS mitoz #1', livePoint.getID().toString())
        def okDetRing = ring('KongNet mitoz #1', liveDet.getID().toString())
        def orphanA = ring('KongNet mitoz #2', deadId)
        def orphanB = ring('KongNet mitoz #3', UUID.randomUUID().toString())
        def orphanC = ring('FCOS mitoz #2', UUID.randomUUID().toString())
        def unnamedOrphan = ring(null, UUID.randomUUID().toString())
        def userRing = ring('KongNet mitoz #9', null)          // looks like a ring but carries no link key
        def userCircle = ring('Benim dairem', null)
        def otherKey = ring('FCOS mitoz #7', null); otherKey.getMetadata().put('baska.anahtar', deadId)
        h.addObjects([livePoint, liveDet, okRing, okDetRing, orphanA, orphanB, orphanC, unnamedOrphan, userRing, userCircle, otherKey])
        String code = 'def workshopText = { k, f -> f }\ndef RING_LINK_KEY = \'atolye.mitoz.nokta\'\n' +
            ['findOrphanRings', 'ringModelLabel', 'orphanRingLines'].collect { closure(HUB, it) }.join('\n')
        def res = SafetyRegressionTest.shell([h: h, o: [orphanA, orphanB, orphanC, unnamedOrphan]]).evaluate(code + '''
def found = findOrphanRings(h)
assert found.size() == 4
assert found.containsAll(o)
assert findOrphanRings(null).isEmpty()
assert findOrphanRings(new qupath.lib.objects.hierarchy.PathObjectHierarchy()).isEmpty()
// display grouping only
assert ringModelLabel(o[0]) == 'KongNet mitoz'
assert ringModelLabel(o[3]) == '(adsız)'
def lines = orphanRingLines(found)
assert lines.contains('KongNet mitoz: 2') && lines.contains('FCOS mitoz: 1') && lines.contains('(adsız): 1')
h.removeObjects(found, false)
assert findOrphanRings(h).isEmpty()
return h.getAnnotationObjects().size()
''')
        // livePoint + 2 valid rings + userRing + userCircle + otherKey survive (the detection point is not an annotation)
        assertEquals(6, res)
    }

    void testHubCleanUpAsksAndDefaultsToLeave() {
        String c = closure(HUB, 'cleanOrphanRings')
        assert c.contains('[leaveOpt, removeOpt], leaveOpt)')
        assert c.contains('if (pick != removeOpt)')
        assert c.count('hier.removeObjects(orphans, false)') == 1
        assert c.indexOf('if (pick != removeOpt)') < c.indexOf('hier.removeObjects(orphans, false)')
        // nothing found -> returns before the dialog
        assert c.indexOf('orphans.isEmpty()') < c.indexOf('showChoiceDialog')
        // the button exists exactly once and the headless branch does not call the clean-up
        String s = src(HUB)
        assert s.count('{ cleanOrphanRings() }') == 1
        assert s.indexOf('if (isHeadless) {') < s.indexOf('def cleanOrphanRings')
        assert !s.substring(s.indexOf('if (isHeadless) {'), s.indexOf('// ── Durum makinesi')).contains('cleanOrphanRings')
    }

    // ---- Peritumoral band: internal ids, legacy Turkish stored values still read --------------------------------
    void testPeritumoralDirectionIdsAndLegacyStoredValues() {
        String s = src(PERI)
        int a = s.indexOf('String DIR_OUT')
        int b = s.indexOf('def dirIdOf')
        int e = s.indexOf('\n', b)
        assert a > 0 && b > a
        String code = s.substring(a, e).replaceAll(/@WT:(\w+)@/) { all, n -> "'L_" + n + "'" }
        def r = SafetyRegressionTest.shell().evaluate('workshopText = { k, f -> f }; ' + code + '''
assert dirIds == ['outer', 'inner', 'both']
assert dirIdOf('Dışa (peritümöral halka)') == 'outer'
assert dirIdOf('İçe (iç kenar bandı)') == 'inner'
assert dirIdOf('Her iki yön (invazif kenar bandı)') == 'both'
assert dirIdOf('inner') == 'inner'
assert dirIdOf('bilinmeyen') == null
assert dirIdOf(null) == null
assert (dirIdOf('bilinmeyen') ?: DIR_OUT) == 'outer'
// stored value stays the old Turkish text
assert dirStored[DIR_IN] == 'İçe (iç kenar bandı)'
return dirLabels.size()
''')
        assertEquals(3, r)
        // logic compares ids; object name and stored value unchanged
        assert s.contains('(direction == DIR_IN) ? "iç bant"')
        assert s.contains("prefs.put('direction', dirStored[direction])")
        assert !s.contains('dirOpts')
    }

    // ---- VALIS reference choice: id is read from the selected index ----------------------------------------------
    void testValisReferenceChoiceUsesIds() {
        String s = src(VALIS)
        assert s.contains("def refIds = ['open', 'auto']")
        assert s.contains('selectedIndexProperty()')
        assert !s.contains("startsWith('Otomatik')")
        assert s.contains("referenceModeRef.get() == 'auto'")   // consumer unchanged (-> --auto-reference)
    }
}
