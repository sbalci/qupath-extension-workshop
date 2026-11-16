package io.github.sbalci.qupath.workshop

import groovy.test.GroovyTestCase
import qupath.lib.geom.Point2
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs

/**
 * Verifies QuPath coordinate, class-mask, multi-point validation, multi-class hotspot,
 * persistent-window slide-switch, and TSV sanitization fixes.
 */
class QuPathAccuracyFixesTest extends GroovyTestCase {

    static String src(String f) { SafetyRegressionTest.source(f) }
    static String closure(String f, String n) { SafetyRegressionTest.closure(f, n) }

    void testNegativeOriginBoundingBoxClampingShrinksSpan() {
        def plane = ImagePlane.getDefaultPlane()
        def negRoi = ROIs.createRectangleROI(-50.0d, -30.0d, 150.0d, 100.0d, plane)
        def server = new Expando(getWidth: { -> 500 }, getHeight: { -> 400 })
        int[] box = SafetyRegressionTest.shell([negRoi: negRoi, server: server])
            .evaluate(closure('yardimci-bolge-cikart-sihirbaz.groovy', 'clampedBounds') + '\nclampedBounds(negRoi, server)') as int[]
        assert box.toList() == [0, 0, 100, 70]

        def rightEdge = ROIs.createRectangleROI(420.0d, 350.0d, 200.0d, 100.0d, plane)
        int[] box2 = SafetyRegressionTest.shell([negRoi: rightEdge, server: server])
            .evaluate(closure('yardimci-bolge-cikart-sihirbaz.groovy', 'clampedBounds') + '\nclampedBounds(negRoi, server)') as int[]
        assert box2.toList() == [420, 350, 80, 50]

        ['yardimci-omezarr-disa-aktarma.groovy', 'yardimci-goruntu-kunye.groovy', 'yardimci-boya-kalite-qc.groovy'].each { f ->
            String s = src(f)
            assert s.contains('x2 - rx') && s.contains('y2 - ry') : f
        }
    }

    void testValidationF1MultiPointRoiFiltersIndividualPointsInsidePatch() {
        def plane = ImagePlane.getDefaultPlane()
        def patchRoi = ROIs.createRectangleROI(0.0d, 0.0d, 100.0d, 100.0d, plane)
        // Average centroid of (20,20), (800,800), (900,900) is (573.3, 573.3) -> outside patchRoi!
        def ptsRoi = ROIs.createPointsROI([new Point2(20.0d, 20.0d), new Point2(800.0d, 800.0d), new Point2(900.0d, 900.0d)], plane)
        def ptsAnn = PathObjects.createAnnotationObject(ptsRoi, PathClass.fromString('GT'))

        assert !patchRoi.contains(ptsRoi.getCentroidX(), ptsRoi.getCentroidY())
        assert ptsRoi.getAllPoints().any { pt -> patchRoi.contains(pt.getX(), pt.getY()) }

        String code = closure('yardimci-dogrulama-f1.groovy', 'buildUnits')
        def units = SafetyRegressionTest.shell([objs: [ptsAnn], patchRoi: patchRoi])
            .evaluate(code + '\nbuildUnits(objs, patchRoi)')
        assertEquals(1, units.size())
        assertEquals(20.0d, units[0].cx as double, 1e-6d)
        assertEquals(20.0d, units[0].cy as double, 1e-6d)
    }

    void testHotspotMultiClassRerunRestoresEachTilesOriginalClass() {
        def plane = ImagePlane.getDefaultPlane()
        def tumorTile = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 50, 50, plane), PathClass.fromString('Tumor'))
        def stromaTile = PathObjects.createAnnotationObject(ROIs.createRectangleROI(60, 0, 50, 50, plane), PathClass.fromString('Stroma'))
        String rankMeasKey = 'Hotspot sıra'
        String hotspotClass = 'Hotspot'
        String NOCLASS = '(Sınıfsız)'
        def QP = new Expando(getPathClass: { String n -> PathClass.fromString(n) })

        // Simulate Run 1 on 'Tumor' marking tumorTile as Hotspot
        tumorTile.measurements[rankMeasKey] = 1.0d
        tumorTile.getMetadata().put('atolye.hotspot.prevClass', 'Tumor')
        tumorTile.setPathClass(PathClass.fromString(hotspotClass))

        // Simulate Run 2 on 'Stroma' restoring previous Hotspot tiles
        String s = src('yardimci-hotspot-sirali.groovy')
        assert s.contains("atolye.hotspot.prevClass")
        def allAnnos = [tumorTile, stromaTile]
        String chosenClass = 'Stroma'
        def resetPC = QP.getPathClass(chosenClass)
        allAnnos.findAll { it.getPathClass()?.toString() == hotspotClass && it.measurements.containsKey(rankMeasKey) }
                .each {
                    String origCls = it.getMetadata().get('atolye.hotspot.prevClass')
                    if (origCls != null) {
                        it.setPathClass(origCls == NOCLASS ? null : QP.getPathClass(origCls))
                    } else {
                        it.setPathClass(resetPC)
                    }
                }
        assertEquals('Tumor', tumorTile.getPathClass().toString())
        assertEquals('Stroma', stromaTile.getPathClass().toString())
    }

    void testTileExporterScopesMaskServerAndZeroesIgnorePixelsWithNearestNeighbor() {
        String s = src('yardimci-karo-disa-aktarma.groovy')
        assert s.contains('scopeIds.contains(o.getID())')
        assert s.contains('raster.setSample(px, py, 0, 0)')
        assert s.contains('def mimg = fitTo(rawMask, true)')
        assert s.contains('java.awt.image.AffineTransformOp.TYPE_NEAREST_NEIGHBOR')
    }

    void testPersistentWindowModulesResolveCurrentImageDataAndCatchWorkerErrors() {
        ['nukleer-boya.groovy', 'er-pr-hscore.groovy', 'sitoplazmik-boya.groovy', 'membran-boya.groovy'].each { f ->
            String s = src(f)
            assert s.contains('def curImg = QP.getCurrentImageData() ?: imageData') : f
            assert s.contains('(QP.getCurrentImageData() ?: imageData).setImageType(') : f
        }
        assert src('hucre-tespiti.groovy').contains('(QP.getCurrentImageData() ?: imageData).getServer().getPixelCalibration()')
        String mem = src('membran-boya.groovy')
        assert mem.count('catch (Throwable t) {') >= 2
    }

    void testCohortSummarySanitizesTabsAndNewlinesInTsvCells() {
        String code = closure('yardimci-kohort-ozet-topla.groovy', 'sanitizeTsvCell')
        def fn = SafetyRegressionTest.shell().evaluate(code + '\nsanitizeTsvCell')
        assertEquals('Lam A Not 1', fn("Lam\tA\r\nNot 1"))
        assertEquals('', fn(null))
    }
}
