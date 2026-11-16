package io.github.sbalci.qupath.workshop

import groovy.test.GroovyTestCase

/** Round 2 (16 Nov 2026): the run manifest records copy groups (annotation_copy_groups) without changing
 * any existing key. Runs the production closures copiesParse / copiesGroups of yardimci-kosu-manifesti.groovy
 * and checks the source text; works on the script AFTER @WT:..@ tokens became workshopText(...) calls.
 */
class RunManifestCopyGroupsTest extends GroovyTestCase {
    static final String FILE = 'yardimci-kosu-manifesti.groovy'

    static final List OLD_KEYS = [
        'rec.identity', 'identity.image_name', 'identity.file_uri', 'identity.file_path', 'identity.pixel_width_um',
        'identity.pixel_height_um', 'identity.magnification', 'identity.image_type', 'identity.width_pixels',
        'identity.height_pixels', 'rec.stain_vectors', 'stainVectors.stain_name', 'stainVectors.is_default',
        'stainVectors.stains', 'stainVectors.note', 'stainVectors.error', 'sv.index', 'sv.name', 'sv.r', 'sv.g', 'sv.b',
        'analysisState.annotation_count_total', 'analysisState.annotation_count_by_class', 'analysisState.annotation_error',
        'analysisState.detection_count_total', 'analysisState.detection_count_by_class', 'analysisState.detection_error',
        'rec.analysis_state', 'env.qupath_version', 'env.os_name', 'env.os_version', 'env.timestamp_ms',
        'env.timestamp_iso', 'rec.environment', 'rec.user_note']

    static def groupsOf(List names) {
        def code = SafetyRegressionTest.closure(FILE, 'copiesParse') + SafetyRegressionTest.closure(FILE, 'copiesGroups')
        def objs = names.collect { n -> new Expando(getName: { n }) }
        SafetyRegressionTest.shell([objs: objs]).evaluate(code + "\ncopiesGroups(objs, { it.getName() })")
    }

    void testExactlyOneGroupFromMixedNames() {
        def g = groupsOf(['GrandQC KK', 'GrandQC KK (2)', 'Tümör (notlarım)', 'Bölge (2)', null])
        assert g.keySet() as List == ['GrandQC KK']
        assert g['GrandQC KK'].values().collect { it.collect { o -> o.getName() } }.flatten() == ['GrandQC KK', 'GrandQC KK (2)']
    }

    void testNoGroupWithoutTwoMembersAndNoPrefixMatch() {
        assert groupsOf(['Bölge (2)', 'Tümör', 'Tümör (notlarım)', 'Tümör alt', null]).isEmpty()
        assert groupsOf(['A (1)', 'A']).isEmpty()      // N must be >= 2
    }

    void testMembersOrderedByIndex() {
        def g = groupsOf(['X (3)', 'X', 'X (2)'])
        assert g['X'].values().collect { it.collect { o -> o.getName() } }.flatten() == ['X', 'X (2)', 'X (3)']
    }

    void testClosuresAreVerbatimCopiesOfExport() {
        ['copiesParse', 'copiesGroups'].each { n ->
            assert SafetyRegressionTest.closure(FILE, n) == SafetyRegressionTest.closure('veri-aktarma.groovy', n)
        }
    }

    void testEveryExistingKeyStillWrittenAndNewKeyWritten() {
        String s = SafetyRegressionTest.source(FILE)
        OLD_KEYS.each { k -> assert s.contains(k) : "existing manifest key no longer written: $k" }
        // existing order inside analysis_state is untouched; the new key comes after the detection keys
        assert s.indexOf('analysisState.detection_error') < s.indexOf('analysisState.annotation_copy_groups')
        assert s.indexOf('analysisState.annotation_copy_groups') < s.indexOf('rec.analysis_state =')
        // success path writes the list once; the only other write is the empty-list fallback in the catch
        assert s.count('analysisState.annotation_copy_groups = copyList') == 1
        assert s.count('analysisState.annotation_copy_groups = []') == 1
        assert s.count('g.base ') == 1 && s.count('g.members ') == 1 && s.count('g.count ') == 1
        assert s.contains('copyGroupsRecorded')
    }

    void testReadOnlyAndCountsUnfiltered() {
        String s = SafetyRegressionTest.source(FILE)
        assert !s.contains('saveImageData') || s.indexOf('saveImageData') < s.indexOf('import qupath')  // docstring only
        assert !s.contains('removeObject') && !s.contains('clearAllObjects') && !s.contains('showConfirmDialog')
        assert s.contains('def annotations = imgData.getHierarchy().getAnnotationObjects()')
        assert s.contains('analysisState.annotation_count_total = annotations.size()')
    }
}
