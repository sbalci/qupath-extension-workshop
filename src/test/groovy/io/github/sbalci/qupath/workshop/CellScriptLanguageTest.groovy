package io.github.sbalci.qupath.workshop

import org.junit.Test
import static org.junit.Assert.*

class CellScriptLanguageTest {
    @Test
    void cellWindowHasBothLanguagesAndStableNumericResults() {
        def keys = WorkshopI18n.trKeys().findAll { it.startsWith('cell.') }
        assertEquals(40, keys.size())   // window strings + image-type question + re-detect question + visible warnings
        keys.each { key ->
            assertNotEquals(key, WorkshopI18n.t(key, false))
            assertNotEquals(key, WorkshopI18n.t(key, true))
        }
        assertEquals('Çalıştır', WorkshopI18n.t('cell.run', false))
        assertEquals('Run', WorkshopI18n.t('cell.run', true))
        def values = [0.1d, 1.5d, 5.0d, 100, 0.065536d, 1526L, 1.2d] as Object[]
        def tr = String.format(Locale.US, WorkshopI18n.t('cell.results', false), values)
        def en = String.format(Locale.US, WorkshopI18n.t('cell.results', true), values)
        assertTrue(tr.contains('Toplam hücre        : 100'))
        assertTrue(en.contains('Total cells         : 100'))
        assertEquals(tr.findAll(/\d+(?:[.,]\d+)*/), en.findAll(/\d+(?:[.,]\d+)*/))
    }
}
