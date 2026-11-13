package io.github.sbalci.qupath.workshop

import groovy.test.GroovyTestCase
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.function.ToDoubleFunction
import qupath.lib.objects.classes.PathClass

/** Gerçek doğrulayıcıyı, renk paletlerini, Türkçe mikro metinleri ve hata sözleşmesini JavaFX oturumu açmadan sınar. */
class ParameterValidationTest extends GroovyTestCase {
    static final Map VALUES = ['atolye.nuclear1': .2d, 'atolye.nuclear2': .4d,
        'atolye.nuclear3': .6d, 'atolye.minArea': 10d, 'atolye.maxArea': 400d]
    static String check(String key, double value) {
        WorkshopParameterValidation.problem(key, value, { String k -> (VALUES[k] ?: 0d) as double } as ToDoubleFunction<String>)
    }
    void testRejectsNonFiniteValues() {
        [Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY].each {
            assertNotNull(check('atolye.pixelSize', it))
        }
    }
    void testPhysicalScaleAndProbabilityBounds() {
        assertNotNull(check('atolye.pixelSize', 0))
        assertNotNull(check('atolye.stardistPixelSize', -1))
        assertNotNull(check('atolye.pixScale', .5))
        assertNotNull(check('atolye.stardistThreshold', 1.01))
        assertNull(check('atolye.stardistThreshold', 1))
        assertNull(check('atolye.cellposeCellprob', -3))
        assertNull(check('atolye.backgroundRadius', 0))
        assertNull(check('atolye.pixelSize', .25))
    }
    void testCrossParameterValidation() {
        assertNotNull(check('atolye.nuclear1', .4))
        assertNotNull(check('atolye.nuclear2', .7))
        assertNotNull(check('atolye.nuclear3', .3))
        assertNotNull(check('atolye.minArea', 401))
        assertNotNull(check('atolye.maxArea', 9))
        assertNull(check('atolye.nuclear2', .45))
        assertNull(check('atolye.minArea', 400))
    }
    void testActionableErrorsUseRootCause() {
        assertTrue(WorkshopErrors.guidance(new RuntimeException(new FileNotFoundException('model'))).contains('dosya'))
        assertTrue(WorkshopErrors.guidance(new OutOfMemoryError()).contains('küçük'))
        assertTrue(WorkshopErrors.guidance(new IllegalArgumentException('NaN')).contains('ölçek'))
        assertTrue(WorkshopErrors.guidance(new IllegalArgumentException('missing column')).contains('sütun'))
        assertTrue(WorkshopErrors.guidance(new IllegalArgumentException('unsupported bio-formats image server')).contains('piramit'))
        assertTrue(WorkshopErrors.guidance(new IllegalStateException('H-DAB stain deconvolution vector missing')).contains('H-DAB'))
        assertTrue(WorkshopErrors.guidance(new IllegalStateException('empty ROI selected')).contains('ROI'))
        assertTrue(WorkshopErrors.guidance(new RuntimeException('cellpose python environment missing')).contains('Ortam kontrolü'))
        assertTrue(WorkshopErrors.guidance(null).contains('Show log'))
    }
    void testColorblindSafeAndClassicPalettes() {
        def cb = WorkshopColorPalettes.colorblindSafePalette()
        def classic = WorkshopColorPalettes.classicPathologyPalette()
        assertEquals(cb.keySet(), classic.keySet())
        assertTrue(cb.get('Negative') != cb.get('Positive'))
        assertEquals(cb.size(), WorkshopColorPalettes.applyPalette(true))
        assertEquals(cb.get('Positive'), PathClass.fromString('Positive').getColor())
        assertEquals(classic.size(), WorkshopColorPalettes.applyPalette(false))
        assertEquals(classic.get('Positive'), PathClass.fromString('Positive').getColor())
    }
    void testEveryPreferenceHasDualAudienceTooltip() {
        def prefsPath = Paths.get('src/main/java/io/github/sbalci/qupath/workshop/WorkshopPrefs.java')
        if (Files.exists(prefsPath)) {
            def src = Files.readString(prefsPath, StandardCharsets.UTF_8)
            def m = (src =~ /reg[DISB]\s*\([^,]+,\s*"(atolye\.[A-Za-z0-9_]+)"/)
            def declaredKeys = m.collect { it[1] }
            assertEquals(declaredKeys, WorkshopMicrocopy.KNOWN_KEYS)
        }
        WorkshopMicrocopy.KNOWN_KEYS.each { String key ->
            String label = WorkshopMicrocopy.labelFor(key)
            String tip = WorkshopMicrocopy.tooltipFor(key)
            assertFalse("Unmapped label for ${key}", label == key)
            assertTrue("Missing clinical bridge in tooltip for ${key}", tip.contains('Klinik:'))
            assertTrue("Missing mathematical bridge in tooltip for ${key}", tip.contains('Matematik:'))
        }
    }
}
