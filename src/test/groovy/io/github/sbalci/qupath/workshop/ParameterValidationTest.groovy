package io.github.sbalci.qupath.workshop

import groovy.test.GroovyTestCase
import java.util.function.ToDoubleFunction

/** Gerçek doğrulayıcıyı, kalıcı tercihlere ve JavaFX'e dokunmadan sınar. */
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
    }
}
