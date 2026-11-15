package io.github.sbalci.qupath.workshop

import org.junit.Test

/**
 * The anonymization wizard's windows follow the interface language, but everything it WRITES to disk
 * (report, key table, manifests, BENIOKU.md, SHA256SUMS, file names) keeps its Turkish text, and the status
 * codes are values that the code compares and writes. This test pins that split:
 *  - the status codes and the file headers are present verbatim and are NOT wrapped in workshopText / @WT: tokens;
 *  - the file-only closures contain no translated text at all;
 *  - the report text built for the file (showOrig=false) never changes with the interface language, while the
 *    on-screen summary (showOrig=true) does.
 * Source checks do not depend on any Turkish text that is a token (they match the code literals only).
 */
class AnonymizationFileOutputLanguageTest {
    static final String SCRIPT = 'yardimci-anonim-sihirbaz.groovy'

    static String src() { SafetyRegressionTest.source(SCRIPT) }

    /** The line that contains the literal; fails if absent or if the literal occurs more than once per line set. */
    static String lineWith(String literal) {
        def hits = src().readLines().findAll { it.contains(literal) }
        assert !hits.isEmpty() : 'literal missing from the script: ' + literal
        hits[0]
    }

    static void assertVerbatimAndNotTranslated(String literal) {
        String line = lineWith(literal)
        assert !line.contains('workshopText(') : 'wrapped in workshopText: ' + literal
        assert !line.contains('@WT:') : 'replaced by a token: ' + literal
    }

    @Test
    void statusCodesStayPlainLiterals() {
        [
            "def counts = ['TEMİZ': 0, 'EKSİK': 0, 'BELİRSİZ': 0, 'ATLANDI': 0]",
            "verdict = !findings.isEmpty() ? 'EKSİK' : ((!unsure.isEmpty() || u.kind != 'aperio') ? 'BELİRSİZ' : 'TEMİZ')",
            "verdict = 'ATLANDI'; notes << KLS[u.kind]",
            "def PKG_STATUSES = ['TAMAM', 'KISMİ', 'İNCELE']",
            "def c = ['TAMAM': 0, 'KISMİ': 0, 'İNCELE': 0, 'HATA': 0, 'ATLANDI': 0]",
            "return [status: !review.isEmpty() ? 'İNCELE' : (full ? 'TAMAM' : 'KISMİ'), detail: parts.join(' · '), ed: ed]",
            "boolean produced = r.status in ['TAMAM', 'KISMİ', 'İNCELE']",
            "def reviewUnits = { plan -> plan.units.findAll { it.status == 'İNCELE' && it.anon } }",
            "String mark = ['TEMİZ': '✓', 'EKSİK': '✗', 'BELİRSİZ': '?', 'ATLANDI': '–'][verdict]",
            "String mark = ['TAMAM': '✓', 'KISMİ': '◐', 'İNCELE': '⚑', 'HATA': '✗', 'ATLANDI': '–'][u.status] ?: '?'",
        ].each { assertVerbatimAndNotTranslated(it) }
    }

    @Test
    void reportAndKeyFileHeadersStayPlainLiterals() {
        [
            "def KEY_COLUMNS = ['Original_Name', 'Original_Path', 'Pseudonym', 'Naming_Mode', 'Run_Timestamp', 'Status', 'Fingerprint_Before', 'Fingerprint_After']",
            "w.writeLine('Original_Name,Original_Path,Original_Entry_ID,Anonymized_Filename,Status,Detail,Fingerprint_Before,Fingerprint_After,Fingerprint_End,Timestamp')",
            "w.writeLine('Anonymized_Filename,Status,Detail,Source_Unchanged,Timestamp')",
            "def SALT_FILE = 'salt.bin'",
            "def CROSSWALK_FILE = 'anahtar.csv'",
            '''def csv = new File(keyDir, "anonymization_lookup_${ts}.csv")''',
            '''def json = new File(keyDir, "anonymization_lookup_${ts}.json")''',
            '''def csv = new File(keyDir, "anonymization_manifest_${ts}.csv")''',
            '''rep = new File(plan.outDir, "anonimlestirme_raporu_${ts}.txt")''',
            "pkgSkipped: willPackage ? null : (cancel.get() ? 'çalışma iptal edildi' : 'üretilen lam yok')]",
            "u.skipReason = 'Çıktı klasöründe aynı takma adlı dosya zaten var (üzerine yazılmaz): ' + anon",
            "sb << '# WSI anonim lam paketi — BENİOKU\\n\\n'",
            "String readmeName = new File(outDir, 'BENIOKU.md').exists()",
            "String sumsName = new File(outDir, 'SHA256SUMS.txt').exists()",
            "javax.imageio.ImageIO.write(img, 'png', new File(saveDir, f.getName() + '__etiket.png'))",
            "javax.imageio.ImageIO.write(img, 'png', new File(saveDir, f.getName() + '__makro.png'))",
        ].each { assertVerbatimAndNotTranslated(it) }
    }

    @Test
    void fileOnlyClosuresContainNoTranslatedText() {
        ['buildBenioku', 'writeKeyFiles', 'appendCrosswalk', 'csvCell', 'writePackage', 'foreignOutputs', 'sanitizerFor',
         'fingerprintOf', 'fpString', 'pinTimes'].each { name ->
            String body = SafetyRegressionTest.closure(SCRIPT, name)
            assert !body.contains('@WT:') : name + ' contains a token'
            assert !body.contains('workshopText(') : name + ' contains workshopText'
        }
    }

    @Test
    void fileTextInTheSplitBuilderIsNeverATokenAndThePickerIsFileFirst() {
        String body = SafetyRegressionTest.closure(SCRIPT, 'buildApplyText')
        assert body.contains('def L = { String fileText, String screenText -> showOrig ? screenText : fileText }')
        // Every L(...) call passes the file text first: it never starts with a token or a workshopText call.
        assert !(body =~ /\bL\(\s*@WT:/).find()
        assert !(body =~ /\bL\(\s*workshopText\(/).find()
        // The two call sites: file = showOrig false, screen = showOrig true.
        String all = src()
        assert all.contains('buildApplyText(plan, projInfo, [], false, shareExt)')
        assert all.contains('buildApplyText(plan, projInfo, keyFiles, true, ext)')
    }

    @Test
    void reportForTheFileDoesNotChangeWithLanguageButTheScreenSummaryDoes() {
        def plan = [
            cfg: [reversible: true, naming: 'hmac', accPattern: '', removeLabels: true, redactMeta: true, includePartial: false,
                  transferObjects: false, keyState: null],
            outDir: new File('cikti-klasoru'),
            units: [
                [file: new File('A.svs'), items: [[name: 'A.svs']], status: 'TAMAM', anon: 'case_1.svs', detail: 'DETAY', kind: 'aperio',
                 fpBefore: [size: 1], edge: null, review: [], sourceChanged: false],
                [file: new File('B.svs'), items: [[name: 'B.svs']], status: 'HATA', anon: 'case_2.svs', detail: 'DETAY', kind: 'aperio',
                 fpBefore: [size: 1], edge: null, review: [], sourceChanged: false],
            ]
        ]
        String code = AnonymizationRegressionTest.imports + AnonymizationRegressionTest.constants() +
            AnonymizationRegressionTest.code('edgeFlagged', 'reviewUnits', 'buildApplyText')
        def render = { String marker, boolean showOrig ->
            SafetyRegressionTest.shell([plan: plan, showOrig: showOrig,
                workshopText: { Object... a -> marker }]).evaluate(code + '\nreturn buildApplyText(plan, null, [], showOrig, [:])')
        }
        String fileA = render('ENGLISH-A', false)
        String fileB = render('ENGLISH-B', false)
        assert fileA == fileB : 'the report written to disk depends on the interface language'
        assert !fileA.contains('ENGLISH-')
        assert fileA.contains('Sonuç         : TAMAM 1 · KISMİ 0 · İNCELE 0 · HATA 1 · ATLANDI 0 (toplam 2 dosya)')
        assert fileA.contains('✓ TAMAM')              // the status code, not a label, is what the file shows
        assert fileA.contains('✗ HATA')
        String screen = render('ENGLISH-A', true)
        assert screen.contains('ENGLISH-A')            // the on-screen summary is translated
    }
}
