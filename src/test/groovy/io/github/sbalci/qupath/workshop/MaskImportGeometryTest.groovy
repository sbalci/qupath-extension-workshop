package io.github.sbalci.qupath.workshop

import groovy.test.GroovyTestCase
import java.awt.image.BufferedImage
import qupath.lib.analysis.images.ContourTracing
import qupath.lib.regions.RegionRequest

/** Runs the production scale/extent gates and QuPath's raster tracing; no GUI or patient image. */
class MaskImportGeometryTest extends GroovyTestCase {
    static final String FILE = 'yardimci-maske-iceaktar.groovy'

    void testDownsampleRejectsInvalidValuesInsteadOfChangingThemToOne() {
        def parse = SafetyRegressionTest.shell().evaluate(
            SafetyRegressionTest.closure(FILE, 'maskDownsample') + '\nmaskDownsample')
        [null, '', 'wrong', 0, -1, 'NaN', 'Infinity', '-Infinity',
         Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY].each { value ->
            shouldFail(IllegalArgumentException) { parse(value) }
        }
        assertEquals(4.0d, parse(4), 0.0d)
        assertEquals(0.25d, parse('0,25'), 0.0d)
        assertEquals(2.5d, parse(' 2.5 '), 0.0d)
    }

    void testBothDimensionsAreCheckedAndRoundingIsTolerated() {
        def mismatch = SafetyRegressionTest.shell().evaluate(
            SafetyRegressionTest.closure(FILE, 'maskDimensionMismatch') + '\nmaskDimensionMismatch')
        assertFalse(mismatch(250, 200, 4d, 1000, 800))
        assertFalse(mismatch(251, 201, 4d, 1000, 800))
        assertTrue(mismatch(250, 100, 4d, 1000, 800)) // same width, wrong height
        assertTrue(mismatch(125, 200, 4d, 1000, 800)) // wrong width, same height
    }

    void testRgbAndRgbaAreStoppedBeforeTracingWhileIndexedMasksPass() {
        String source = SafetyRegressionTest.source(FILE)
        int start = source.indexOf('if (numBands != 1) {')
        int end = source.indexOf('// ── 5) Downsample', start)
        assert start > 0 && end > start
        String gate = source.substring(start, end)
        [BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_BYTE_INDEXED,
         BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_USHORT_GRAY].each { type ->
            def img = new BufferedImage(2, 2, type)
            def errors = []
            def dialogs = new Expando(showErrorMessage: { title, body -> errors << body })
            def result = SafetyRegressionTest.shell([numBands: img.raster.numBands, Dialogs: dialogs])
                .evaluate('def workshopText = { key, fallback -> fallback }\n' + gate + '\nreturn "continued"')
            if (img.raster.numBands == 1) {
                assertEquals('continued', result)
                assert errors.isEmpty()
            } else {
                assertNull(result)
                assertEquals(1, errors.size())
                assertTrue(errors[0].contains('PNG'))
            }
        }
        assert end < source.indexOf('ContourTracing.createTracedROI(raster')
        assert source.contains("if (sidecar == null) throw new IllegalArgumentException('empty sidecar')")
        assert !source.contains('catch (Throwable t) { sidecar = null }')
    }

    void testLabelValuesTraceAtTheDeclaredPhysicalPixelScale() {
        def img = new BufferedImage(6, 4, BufferedImage.TYPE_BYTE_GRAY)
        // A 2x2 region labelled 7, separate from a 1x1 region labelled 255.
        (1..2).each { y -> (2..3).each { x -> img.raster.setSample(x, y, 0, 7) } }
        img.raster.setSample(5, 0, 0, 255)
        def request = RegionRequest.createInstance('synthetic', 4d, 0, 0, 24, 16)
        def seven = ContourTracing.createTracedROI(img.raster, 7d, 7d, 0, request)
        def last = ContourTracing.createTracedROI(img.raster, 255d, 255d, 0, request)
        assertEquals(8d, seven.boundsX, 0.0d)
        assertEquals(4d, seven.boundsY, 0.0d)
        assertEquals(64d, seven.getArea(), 0.0d)
        assertEquals(16d, last.getArea(), 0.0d)
    }
}
