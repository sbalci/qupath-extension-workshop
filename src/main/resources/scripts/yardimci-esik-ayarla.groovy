/**
 * Yardımcı - Eşikleri ayarla (görsel eşik paleti)
 * ------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Nükleer boya, ER/PR H-score, Membran boya, Sitoplazmik boya veya Tümör içi Ki-67
 * modüllerinden biriyle hücre tespiti yaptıktan sonra: bu sihirbaz **hücre tespitini
 * yeniden çalıştırmadan** üç bin eşiğini (1+ / 2+ / 3+) GÖRSEL olarak ayarlar.
 *
 * NE İŞE YARAR?
 *   • Slaydınızın KENDİ kromojen renklerinden üretilen renk şeridi + gerçek hücre
 *     kesitlerinden oluşan örnek şeridi üzerinde, histogramdaki üç eşik çizgisini
 *     SÜRÜKLEYEREK ayarlarsınız; sayım / yüzde / H-score anında güncellenir.
 *   • "Canlı uygula" açıkken her sürükleme bırakışında hücreler yeni bin'lerine
 *     yeniden sınıflandırılır — değişikliği ana görüntüde görürsünüz.
 *   • Kapsam seçilebilir: Seçili anotasyon(lar) ya da Tüm slayt.
 *   • Ölçüm sütunu otomatik keşfedilir: "Membrane/Nucleus/Cytoplasm: <boya> OD mean"
 *     desenindeki her sütun tanınır (DAB varsayılan; AEC gibi diğer kromojenler de
 *     çalışır — birden çok aday varsa sütun seçici görünür).
 *
 * EŞİK NOTU (Bankhead): Eşik değerleri 1+/2+/3+ dağılımını ve H-score'u DOĞRUDAN
 *   kaydırır; "doğru" tek bir eşik yoktur. Yöntem referansınızla tutarlı, sabit bir
 *   eşik seçip belgeleyin — aynı çalışmada slayttan slayta değiştirmeyin.
 *
 * NE YAPMAZ:
 *   • Hücre tespit adımını yeniden çalıştırmaz; tespit parametrelerini değiştiremez.
 *   • Piksel-bazlı (pixel-wise) H-score'u yeniden HESAPLAMAZ — eşik değişince
 *     bayatlayan piksel H-score ölçümlerini anotasyonlardan temizler; yeniden
 *     hesaplamak için Membran boya modülünü yeni eşiklerle yeniden çalıştırın.
 *   • Klinik eşik önermez; renk şeridi bir rubrik değildir (ölçüm görselleştirmesi).
 *   • Geri al (Ctrl+Z) yığını sınırlıdır (10 adım) — canlı uygulama her bırakışta bir
 *     adım tüketir; sihirbazın kendisi kayıpsız geri dönüş yoludur (Sıfırla + sürükle).
 *
 * KULLANIM:
 *   1. Bir skorlama modülünü çalıştırın (hücre tespiti + OD ölçümleri oluşur).
 *   2. [Extensions → Atölye → Yardımcılar → Eşikleri ayarla]
 *   3. Kapsamı seçin, çizgileri sürükleyin ya da alanlara değer yazın; "Uygula" ile
 *      özet penceresini alın. Pencere açık kalır — istediğiniz kadar deneyin.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.regions.RegionRequest

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// --- Atölye ayarları: eklenti yüklüyse oku, yoksa atölye varsayılanı kullanılır ---
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeD = { String k, double d -> (double) __wpCall('dbl', [String.class, double.class] as Class[], [k, d] as Object[], d) }

// ── Sabitler ────────────────────────────────────────────────────────
String SCOPE_SELECTED = 'Seçili anotasyon(lar)'
String SCOPE_ALL      = 'Tüm slayt'
String WINDOW_TITLE   = 'Eşik paleti — Eşikleri ayarla'

// ── Ön kontrol ──────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    if (isHeadless) { println 'Görüntü açık değil.'; return }
    Dialogs.showErrorMessage('Görüntü açık değil', 'Önce bir slayt açın.')
    return
}
def server = imageData.getServer()
def cal = server.getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
boolean calibrated = (pw > 0 && ph > 0 && !Double.isNaN(pw) && !Double.isNaN(ph))

// ── Kapsam yardımcıları ─────────────────────────────────────────────
def selectedAnnos = { -> QP.getSelectedObjects().findAll { it.isAnnotation() }.toList() }

def cellsForScope = { String scope ->
    if (scope == SCOPE_ALL) return QP.getDetectionObjects().toList()
    def out = new LinkedHashSet()
    selectedAnnos().each { a -> a.getChildObjects().findAll { it.isDetection() }.each { out.add(it) } }
    return out.toList()
}

def annosForScope = { String scope ->
    scope == SCOPE_ALL ? QP.getAnnotationObjects().toList() : selectedAnnos()
}

// ── Sütun keşfi: "Membrane/Nucleus/Cytoplasm: <boya> OD mean" ──────
def COLUMN_PATTERN = /^(Membrane|Nucleus|Cytoplasm): .+ OD mean$/
def DAB_TRIO = ['Membrane: DAB OD mean', 'Nucleus: DAB OD mean', 'Cytoplasm: DAB OD mean']
def COMPARTMENTS = ['Membrane', 'Nucleus', 'Cytoplasm']

def discoverColumns = { List cells ->
    def names = new LinkedHashSet()
    cells.take(100).each { c -> names.addAll(c.getMeasurementList().getNames()) }
    def matches = names.findAll { it ==~ COLUMN_PATTERN }
    def dab = DAB_TRIO.findAll { matches.contains(it) }
    def rest = matches.findAll { !dab.contains(it) }.sort { a, b ->
        int ca = COMPARTMENTS.indexOf(a.split(':')[0])
        int cb = COMPARTMENTS.indexOf(b.split(':')[0])
        ca <=> cb ?: (a <=> b)
    }
    return dab + rest
}

// Bölme (compartment) başına atölye varsayılanları — DAB için kalibre değerler
def defaultsForColumn = { String column ->
    if (column.startsWith('Membrane:'))
        return [vals: [atolyeD('atolye.membrane1', 0.15), atolyeD('atolye.membrane2', 0.40), atolyeD('atolye.membrane3', 0.70)],
                keys: ['atolye.membrane1', 'atolye.membrane2', 'atolye.membrane3'],
                hint: 'Membran boya modülü (HER2)']
    if (column.startsWith('Cytoplasm:'))
        return [vals: [atolyeD('atolye.cyto1', 0.10), atolyeD('atolye.cyto2', 0.20), atolyeD('atolye.cyto3', 0.35)],
                keys: ['atolye.cyto1', 'atolye.cyto2', 'atolye.cyto3'],
                hint: 'Sitoplazmik boya modülü (CD68)']
    return [vals: [atolyeD('atolye.nuclear1', 0.20), atolyeD('atolye.nuclear2', 0.40), atolyeD('atolye.nuclear3', 0.60)],
            keys: ['atolye.nuclear1', 'atolye.nuclear2', 'atolye.nuclear3'],
            hint: 'Nükleer boya / ER-PR H-score / Tümör içi Ki-67 modülleri']
}

// ── Saf hesap çekirdeği ─────────────────────────────────────────────
// Sütunda SONLU değeri olan hücreler; sorted[i] değerinin hücresi = cells[order[i]].
// (Sonlu filtre şart: değeri olmayan tespitleri Negatif'e boyamamak için.)
def buildScored = { List allCells, String column ->
    def cells = new ArrayList(allCells.size())
    def tmp = new double[allCells.size()]
    int n = 0
    for (c in allCells) {
        double v = c.getMeasurementList().getOrDefault(column, Double.NaN)
        if (Double.isFinite(v)) { cells.add(c); tmp[n++] = v }
    }
    double[] vals = java.util.Arrays.copyOf(tmp, n)
    def order = (0..<n).sort { int i -> vals[i] }
    double[] sorted = new double[n]
    for (int i = 0; i < n; i++) sorted[i] = vals[order[i]]
    return [cells: cells, order: order, sorted: sorted]
}

// arr[i] >= t olan ilk indeks (çift-değer güvenli alt sınır; >= semantiği
// PathObjectTools.setIntensityClassification ile birebir — bkz. spec §⑤)
def lowerBound = { double[] arr, double t ->
    int lo = 0, hi = arr.length
    while (lo < hi) { int mid = (lo + hi) >>> 1; if (arr[mid] < t) lo = mid + 1; else hi = mid }
    return lo
}

def countsFor = { double[] sorted, double t1, double t2, double t3 ->
    int n = sorted.length
    int i1 = lowerBound(sorted, t1), i2 = lowerBound(sorted, t2), i3 = lowerBound(sorted, t3)
    return [n: n, n0: i1, n1: i2 - i1, n2: i3 - i2, n3: n - i3]
}

def hScoreOf = { Map c ->
    if (c.n == 0) return 0.0d
    return 100.0d * c.n1 / c.n + 2.0d * 100.0d * c.n2 / c.n + 3.0d * 100.0d * c.n3 / c.n
}

// ── Tek uygulama hattı: sınıflandır + bayat piksel H-score temizliği + olay ──
def applyThresholds = { List scoredCells, String column, double t1, double t2, double t3, List scopeAnnos ->
    QP.setIntensityClassifications(scoredCells, column, t1, t2, t3)
    boolean staleRemoved = false
    scopeAnnos.each { anno ->
        def names = anno.getMeasurementList().getNames().toList()
        def stale = names.findAll { it == 'Pixelwise H-score' || it.startsWith('H-score-px:') }
        if (!stale.isEmpty()) {
            def ml = anno.getMeasurementList()
            stale.each { ml.remove(it) }
            ml.close()
            staleRemoved = true
        }
    }
    QP.fireHierarchyUpdate()
    return staleRemoved
}

// Uygulama SONRASI nesnelerin gerçek sınıflarından özet (yerel değişkenden asla)
def summarizeFromObjects = { List scoredCells ->
    int n0 = 0, n1 = 0, n2 = 0, n3 = 0
    scoredCells.each { c ->
        def cls = c.getPathClass()?.getName() ?: ''
        if (cls.contains('3+')) n3++
        else if (cls.contains('2+')) n2++
        else if (cls.contains('1+')) n1++
        else n0++
    }
    int total = scoredCells.size()
    def pct = { int x -> total > 0 ? 100.0d * x / total : 0.0d }
    return [n: total, n0: n0, n1: n1, n2: n2, n3: n3,
            p0: pct(n0), p1: pct(n1), p2: pct(n2), p3: pct(n3),
            hscore: pct(n1) + 2.0d * pct(n2) + 3.0d * pct(n3)]
}

def buildResultText = { String moduleHint, String column, String scopeLabel, Map s,
                        double t1, double t2, double t3, double[] baseline,
                        double elapsedSec, boolean staleRemoved ->
    def pixelNote = staleRemoved
        ? '\nℹ Kapsamdaki anotasyonlarda bayatlamış piksel-bazlı H-score ölçümleri temizlendi.\n  Güncel eşiklerle yeniden hesaplamak için Membran boya modülünü yeniden çalıştırın.\n'
        : ''
    def mark = { double v, double b -> v != b ? ' (değiştirildi)' : '' }
    return String.format(java.util.Locale.US,
        'Modül: %s\nÖlçüm: %s\nKapsam: %s\n\n' +
        'Eşikler:\n  1+ ≥ %.3f OD%s\n  2+ ≥ %.3f OD%s\n  3+ ≥ %.3f OD%s\n\n' +
        '📊 Grup dağılımı (n = %,d)\n──────────────────────────────────\n' +
        '  0  (negatif)  : %,d  (%%%.1f)\n' +
        '  1+ (zayıf)    : %,d  (%%%.1f)\n' +
        '  2+ (orta)     : %,d  (%%%.1f)\n' +
        '  3+ (güçlü)    : %,d  (%%%.1f)\n' +
        '  Toplam ≥1+    : %,d  (%%%.1f)\n\n' +
        '🎯 Metrikler\n──────────────\n' +
        '  H-score (0–300)   : %.0f\n' +
        '  Süre              : %.2f sn\n%s\n' +
        '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.',
        moduleHint, column, scopeLabel,
        t1, mark(t1, baseline[0]), t2, mark(t2, baseline[1]), t3, mark(t3, baseline[2]),
        s.n, s.n0, s.p0, s.n1, s.p1, s.n2, s.p2, s.n3, s.p3,
        s.n1 + s.n2 + s.n3, s.p1 + s.p2 + s.p3,
        s.hscore, elapsedSec, pixelNote)
}

def showResultWindow = { String windowTitle, String windowBody ->
    if (isHeadless) {
        println "=== ${windowTitle} ===\n${windowBody}\n=================="
        return
    }
    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle(windowTitle)
            stage.setAlwaysOnTop(true)

            def textArea = new javafx.scene.control.TextArea(windowBody)
            textArea.setEditable(false)
            textArea.setWrapText(false)
            textArea.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;")

            def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut")
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )

            def copyBtn = new javafx.scene.control.Button("Kopyala")
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent()
                content.putString(windowBody)
                cb.setContent(content)
            })
            def closeBtn = new javafx.scene.control.Button("Kapat")
            closeBtn.setDefaultButton(true)
            closeBtn.setOnAction({ stage.close() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, copyBtn, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(textArea)
            def __footer = new javafx.scene.control.Label("QuPath Atölye Scriptleri · araştırma/eğitim amaçlı")
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons)
            __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)

            stage.setScene(new javafx.scene.Scene(root, 660, 420))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Headless: tek atış (kapsamı çöz, varsayılanları uygula, özeti yaz) ──
if (isHeadless) {
    def annos = selectedAnnos()
    String scope = annos.any { a -> !a.getChildObjects().findAll { it.isDetection() }.isEmpty() } ? SCOPE_SELECTED : SCOPE_ALL
    def cells = cellsForScope(scope)
    def columns = discoverColumns(cells)
    if (columns.isEmpty()) {
        println 'Tanınan "... OD mean" ölçüm sütunu yok — önce bir skorlama modülü çalıştırın.'
        return
    }
    String column = columns[0]
    def dflt = defaultsForColumn(column)
    def sc = buildScored(cells, column)
    if (((List) sc.cells).isEmpty()) { println "Sütunda (${column}) sonlu değer taşıyan tespit yok."; return }
    long t0 = System.currentTimeMillis()
    boolean stale = applyThresholds((List) sc.cells, column,
        (double) dflt.vals[0], (double) dflt.vals[1], (double) dflt.vals[2], annosForScope(scope))
    def s = summarizeFromObjects((List) sc.cells)
    println buildResultText((String) dflt.hint, column, scope, s,
        (double) dflt.vals[0], (double) dflt.vals[1], (double) dflt.vals[2],
        [dflt.vals[0], dflt.vals[1], dflt.vals[2]] as double[],
        (System.currentTimeMillis() - t0) / 1000.0d, stale)
    return
}

// [GUI-BLOCK] — görsel palet (Task 2'de bu blok tamamen değiştirilir)
javafx.application.Platform.runLater {
    Dialogs.showMessageDialog(WINDOW_TITLE, 'Görsel palet kurulumu devam ediyor (ara sürüm).')
}
