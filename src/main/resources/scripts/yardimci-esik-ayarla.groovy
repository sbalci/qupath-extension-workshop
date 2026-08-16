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
    String scope = SCOPE_ALL
    if (!discoverColumns(cellsForScope(SCOPE_SELECTED)).isEmpty()) scope = SCOPE_SELECTED
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

// ── GUI: görsel eşik paleti (tek pencere) ───────────────────────────
def uiPrefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/esik-ayarla')
boolean defLive = Boolean.parseBoolean(uiPrefs.get('liveApply', 'true'))

// Açılış kapsamı: geçerli sütunlu seçili anotasyon varsa Seçili, yoksa Tüm slayt
String initialScope = SCOPE_ALL
if (!discoverColumns(cellsForScope(SCOPE_SELECTED)).isEmpty()) initialScope = SCOPE_SELECTED
if (discoverColumns(cellsForScope(initialScope)).isEmpty()) initialScope = SCOPE_ALL
if (discoverColumns(cellsForScope(initialScope)).isEmpty()) {
    Dialogs.showErrorMessage('Uyumlu ölçüm bulunamadı',
        'Görüntüde "Membrane/Nucleus/Cytoplasm: <boya> OD mean" deseninde ölçüm taşıyan tespit yok.\n' +
        'Önce Nükleer boya / ER-PR H-score / Membran boya / Sitoplazmik boya / Tümör içi Ki-67 ' +
        'modüllerinden birini çalıştırın, sonra bu yardımcıyı yeniden açın.')
    return
}

javafx.application.Platform.runLater {
    try {
        // Tek örnek koruması: açık pencere varsa öne getir, ikinci pencere açma
        def existing = javafx.stage.Window.getWindows().find {
            it instanceof javafx.stage.Stage && it.isShowing() && WINDOW_TITLE.equals(((javafx.stage.Stage) it).getTitle())
        }
        if (existing != null) { ((javafx.stage.Stage) existing).toFront(); return }

        // ── Durum ──
        def DATA = new java.util.concurrent.atomic.AtomicReference(null)
        def lastApplied = new java.util.concurrent.atomic.AtomicReference(null)
        def invalidated = new java.util.concurrent.atomic.AtomicBoolean(false)
        def thresholdMoved = new java.util.concurrent.atomic.AtomicBoolean(false)
        def rebuildSeq = new java.util.concurrent.atomic.AtomicLong(0L)
        boolean[] guard = new boolean[3]
        // İleri bildirimler (Task 3-4 atar; çağrılar ?.call ile korunur)
        def submitApply = null
        def rebuildRamp = null
        def rebuildThumbs = null
        def relayoutAligned = null

        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle(WINDOW_TITLE)
        stage.setAlwaysOnTop(true)

        // ── ⓪ Kapsam + sütun ──
        def scopeBox = new javafx.scene.control.ComboBox(
            javafx.collections.FXCollections.observableArrayList([SCOPE_SELECTED, SCOPE_ALL]))
        scopeBox.getSelectionModel().select(initialScope)
        def columnLabel = new javafx.scene.control.Label('Sütun:')
        def columnBox = new javafx.scene.control.ComboBox()
        columnLabel.setVisible(false); columnLabel.setManaged(false)
        columnBox.setVisible(false); columnBox.setManaged(false)
        def moduleLabel = new javafx.scene.control.Label('')
        moduleLabel.setStyle('-fx-font-size: 11px; -fx-opacity: 0.75;')

        // ── ①② yer tutucu bölmeler (Task 4 doldurur) ──
        def stripPane = new javafx.scene.layout.Pane()
        stripPane.setMinHeight(52); stripPane.setPrefHeight(52)
        def rampHolder = new javafx.scene.layout.Pane()
        rampHolder.setMinHeight(20); rampHolder.setPrefHeight(20)

        // ── ③ Histogram + sürüklenebilir eşikler ──
        def chart = new qupath.lib.gui.charts.HistogramChart()
        chart.setAnimated(false)
        chart.setShowTickLabels(true)
        chart.setLegendVisible(false)
        chart.setPrefHeight(210)
        def xAxis = (javafx.scene.chart.NumberAxis) chart.getXAxis()
        def yAxis = (javafx.scene.chart.NumberAxis) chart.getYAxis()
        yAxis.setTickLabelsVisible(false)
        xAxis.setAutoRanging(false)   // eksen SABİTLENİR — hizalama matematiğinin temeli (spec)
        def thrPane = new qupath.lib.gui.charts.ChartThresholdPane(chart)
        thrPane.setIsInteractive(true)
        def LINE_COLORS = [javafx.scene.paint.Color.web('#E6A817'),
                           javafx.scene.paint.Color.web('#D97706'),
                           javafx.scene.paint.Color.web('#7C2D12')]
        def thresholds = []
        [0.2d, 0.4d, 0.6d].eachWithIndex { double v, int i ->
            thresholds << thrPane.addThreshold(v, LINE_COLORS[i])
        }

        // ── ④ Sayısal alanlar ──
        def fields = (0..2).collect { new javafx.scene.control.TextField() }
        fields.each { it.setPrefColumnCount(7) }

        // ── ⑤ Canlı istatistik + durum ──
        def statsLabel = new javafx.scene.control.Label('')
        statsLabel.setWrapText(true); statsLabel.setMaxWidth(Double.MAX_VALUE)
        def dirtyLabel = new javafx.scene.control.Label('değişiklikler henüz uygulanmadı')
        dirtyLabel.setStyle('-fx-text-fill: #B45309; -fx-font-size: 11px;')
        dirtyLabel.setVisible(false); dirtyLabel.setManaged(true)
        def statusLabel = new javafx.scene.control.Label('Hazır')
        statusLabel.setWrapText(true); statusLabel.setMaxWidth(Double.MAX_VALUE)

        // ── ⑥ Düğmeler ──
        def liveChk = new javafx.scene.control.CheckBox('Canlı uygula')
        liveChk.setSelected(defLive)
        def applyBtn = new javafx.scene.control.Button('Uygula')
        applyBtn.setDefaultButton(true)
        def resetBtn = new javafx.scene.control.Button('Sıfırla')
        def saveBtn = new javafx.scene.control.Button('Bu eşikleri varsayılan yap')
        def closeBtn = new javafx.scene.control.Button('Kapat')
        closeBtn.setCancelButton(true)
        closeBtn.setOnAction({ stage.close() })
        def alwaysTop = new javafx.scene.control.CheckBox('Üstte tut')
        alwaysTop.setSelected(true)
        alwaysTop.selectedProperty().addListener(
            { obs, o, n -> stage.setAlwaysOnTop((boolean) n) } as javafx.beans.value.ChangeListener)

        // ── Yardımcılar ──
        def curThr = { -> [thresholds[0].doubleValue(), thresholds[1].doubleValue(), thresholds[2].doubleValue()] }

        def updateStats = { ->
            def d = DATA.get(); if (d == null) return
            def t = curThr()
            def c = countsFor((double[]) d.sorted, (double) t[0], (double) t[1], (double) t[2])
            double hs = hScoreOf(c)
            def pc = { int x -> c.n > 0 ? 100.0d * x / c.n : 0.0d }
            statsLabel.setText(String.format(java.util.Locale.US,
                'Negatif %,d (%%%.1f) · 1+ %,d (%%%.1f) · 2+ %,d (%%%.1f) · 3+ %,d (%%%.1f) · ≥1+ %,d (%%%.1f) · H-score %.0f',
                c.n0, pc((int) c.n0), c.n1, pc((int) c.n1), c.n2, pc((int) c.n2), c.n3, pc((int) c.n3),
                (int) (c.n1 + c.n2 + c.n3), pc((int) (c.n1 + c.n2 + c.n3)), hs))
            def la = (double[]) lastApplied.get()
            dirtyLabel.setVisible(la == null || la[0] != t[0] || la[1] != t[1] || la[2] != t[2])
        }

        // Programatik toplu eşik ataması (a<b<c çağıran garanti eder; kelepçe atlanır)
        def setThresholds = { double a, double b, double c2 ->
            guard[0] = true; guard[1] = true; guard[2] = true
            try {
                ((javafx.beans.value.WritableNumberValue) thresholds[0]).setValue(a)
                ((javafx.beans.value.WritableNumberValue) thresholds[1]).setValue(b)
                ((javafx.beans.value.WritableNumberValue) thresholds[2]).setValue(c2)
                (0..2).each { int i ->
                    fields[i].setText(String.format(java.util.Locale.US, '%.3f', thresholds[i].doubleValue()))
                }
            } finally { guard[0] = false; guard[1] = false; guard[2] = false }
            updateStats()
        }

        // Eşik dinleyicileri: sıralama kelepçesi (yalnız hareket edeni sınırlar,
        // komşuyu asla itmez; idempotent öz-atama — sürükleme olayı ham değeri
        // yeniden bassa da her adımda yeniden kelepçelenir) + alan eşitleme + sayım
        (0..2).each { int i ->
            thresholds[i].addListener({ obs, ov, nv ->
                if (guard[i]) return
                guard[i] = true
                try {
                    def d = DATA.get()
                    double eps = d != null ? ((double) d.axisMax) / 128.0d : 0.001d
                    double v = nv.doubleValue()
                    double lo = i > 0 ? thresholds[i - 1].doubleValue() + eps : 0.0d
                    double hi = i < 2 ? thresholds[i + 1].doubleValue() - eps : xAxis.getUpperBound()
                    double clamped = Math.min(Math.max(v, lo), Math.max(lo, hi))
                    if (clamped != v) ((javafx.beans.value.WritableNumberValue) thresholds[i]).setValue(clamped)
                    fields[i].setText(String.format(java.util.Locale.US, '%.3f', clamped))
                    thresholdMoved.set(true)
                    updateStats()
                } finally { guard[i] = false }
            } as javafx.beans.value.ChangeListener)
        }

        // Gerçek sürükleme-bırakma sinyali: çizgiler pane'in çocuğu, olay kabarcıklanır
        thrPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, { evt ->
            if (thresholdMoved.getAndSet(false) && liveChk.isSelected() && !invalidated.get())
                submitApply?.call(false)
        } as javafx.event.EventHandler)

        def commitField = { int i ->
            try {
                double v = Double.parseDouble(fields[i].getText().trim().replace(',', '.'))
                ((javafx.beans.value.WritableNumberValue) thresholds[i]).setValue(v)
                if (liveChk.isSelected() && !invalidated.get()) submitApply?.call(false)
            } catch (NumberFormatException ignored) {
                fields[i].setText(String.format(java.util.Locale.US, '%.3f', thresholds[i].doubleValue()))
            }
        }
        (0..2).each { int i ->
            fields[i].setOnAction({ commitField(i) } as javafx.event.EventHandler)
            fields[i].focusedProperty().addListener(
                { obs, was, is -> if (was && !is) commitField(i) } as javafx.beans.value.ChangeListener)
        }

        // ── ①② Renk şeridi + örnek hücre şeridi ──
        def thumbCount = new java.util.concurrent.atomic.AtomicInteger(0)   // N: ilk yerleşimde bir kez
        def thumbData = new java.util.concurrent.atomic.AtomicReference(new ArrayList())

        def chromogenTokenOf = { String column ->
            column.replaceFirst(/^(Membrane|Nucleus|Cytoplasm): /, '').replaceFirst(/ OD mean$/, '')
        }
        // Sütun belirtecine AD ile eşleşen boya vektörü (POZİSYONEL getStain(2) ASLA —
        // H&E'de stain2 = Eozin olurdu; spec §②). Türkçe-I tehlikesi: Locale.ROOT.
        def stainForColumn = { String column ->
            def stains = imageData.getColorDeconvolutionStains()
            if (stains == null) return null
            String tok = chromogenTokenOf(column).toLowerCase(java.util.Locale.ROOT)
            for (int i = 1; i <= 3; i++) {
                def s = stains.getStain(i)
                if (s != null && (s.getName() ?: '').toLowerCase(java.util.Locale.ROOT) == tok) return s
            }
            return null
        }
        // odOf ile aynı konvansiyonun tersi: v = 256·10^(−OD·bileşen) − 1 (spec §②)
        def rampArgbAt = { double od ->
            def d = DATA.get()
            def stain = d != null ? stainForColumn((String) d.column) : null
            if (stain == null) return (int) 0xFFDDDDDD
            def comp = { double sComp ->
                Math.max(0, Math.min(255, (int) Math.round(256.0d * Math.pow(10.0d, -od * sComp) - 1.0d)))
            }
            return (int) (0xFF000000 | (comp(stain.getRed()) << 16) | (comp(stain.getGreen()) << 8) | comp(stain.getBlue()))
        }
        // Paylaşılan hizalama kuralı: eksen-değer → sahne → hedef bölme (spec; ChartThresholdPane deseni)
        def xInPane = { javafx.scene.Node pane, double value ->
            double ax = xAxis.getDisplayPosition(value)
            def scenePt = xAxis.localToScene(ax, 0.0d)
            return pane.sceneToLocal(scenePt.getX(), 0.0d).getX()
        }

        relayoutAligned = { ->
            def d = DATA.get()
            if (d == null || chart.getScene() == null) return
            double am = (double) d.axisMax
            double x0 = xInPane(rampHolder, 0.0d)
            double x1 = xInPane(rampHolder, am)
            def kids = rampHolder.getChildren()
            if (!kids.isEmpty() && kids[0] instanceof qupath.lib.gui.ColorMapCanvas && x1 - x0 > 10.0d) {
                def canvas = (qupath.lib.gui.ColorMapCanvas) kids[0]
                canvas.setLayoutX(x0)
                canvas.resize(x1 - x0, 18.0d)   // resize() ŞART — repaint yalnız burada (spec §②)
            }
            ((List) thumbData.get()).each { e ->
                double x = xInPane(stripPane, (double) e.od)
                def iv = (javafx.scene.image.ImageView) e.view
                iv.setLayoutX(x - iv.getFitWidth() / 2.0d)
                iv.setLayoutY(4.0d)
            }
        }

        rebuildRamp = { ->
            def d = DATA.get(); if (d == null) return
            rampHolder.getChildren().clear()
            def stain = stainForColumn((String) d.column)
            if (stain == null) {
                def lbl = new javafx.scene.control.Label(
                    'Renk şeridi yok: boya vektörleri arasında "' + chromogenTokenOf((String) d.column) +
                    '" adlı boya bulunamadı. (Histogram ve kesitler çalışmaya devam eder.)')
                lbl.setWrapText(true); lbl.setMaxWidth(Double.MAX_VALUE)
                lbl.setStyle('-fx-font-size: 11px; -fx-opacity: 0.75;')
                rampHolder.getChildren().add(lbl)
                return
            }
            double am = (double) d.axisMax
            int[] rr = new int[256]; int[] gg = new int[256]; int[] bb = new int[256]
            for (int i = 0; i < 256; i++) {
                int argb = rampArgbAt(am * i / 255.0d)
                rr[i] = (argb >> 16) & 0xFF; gg[i] = (argb >> 8) & 0xFF; bb[i] = argb & 0xFF
            }
            def cmap = qupath.lib.color.ColorMaps.createColorMap('Atölye kromojen', rr, gg, bb)
            def canvas = new qupath.lib.gui.ColorMapCanvas(18.0d, cmap)
            canvas.setManaged(false)
            // DİKKAT: geri çağrının parametresi 0-255 İNDEKSTİR, OD değil (spec §②)
            canvas.setTooltipFunction({ Double idx ->
                String.format(java.util.Locale.US, 'OD ≈ %.3f', am * idx / 255.0d)
            } as java.util.function.Function)
            rampHolder.getChildren().add(canvas)
        }

        rebuildThumbs = { ->
            def d = DATA.get()
            if (d == null || chart.getScene() == null) return
            double am = (double) d.axisMax
            double x0 = xInPane(stripPane, 0.0d)
            double x1 = xInPane(stripPane, am)
            double stripW = x1 - x0
            if (stripW < 60.0d) return   // eksen henüz yerleşmedi; boundsInParent tetikleyicisi tekrar çağırır
            int thumbPx = 30, gap = 4
            if (thumbCount.get() == 0)
                thumbCount.set(Math.max(12, Math.min(40, (int) (stripW / (thumbPx + gap)))))
            int N = thumbCount.get()
            double[] sorted = (double[]) d.sorted
            def order = (List) d.order
            def cells = (List) d.cells
            stripPane.getChildren().clear()
            def entries = []
            def used = new HashSet()
            double halfSlot = am / N / 2.0d
            for (int k = 0; k < N; k++) {
                double target = am * (k + 0.5d) / N
                int pos = lowerBound(sorted, target)
                int chosen = -1
                for (int step = 0; step < 64 && chosen == -1; step++) {
                    for (int cand in [pos + step, pos - 1 - step]) {
                        if (cand < 0 || cand >= sorted.length || used.contains(cand)) continue
                        if (Math.abs(sorted[cand] - target) <= halfSlot) { chosen = cand; break }
                    }
                }
                if (chosen < 0) continue   // yakın hücre yok → yuva boş kalır (yanıltıcı uzak hücre yok)
                used.add(chosen)
                def iv = new javafx.scene.image.ImageView()
                iv.setFitWidth(thumbPx); iv.setFitHeight(thumbPx)
                iv.setPreserveRatio(true); iv.setSmooth(true); iv.setManaged(false)
                def phImg = new javafx.scene.image.WritableImage(1, 1)
                phImg.getPixelWriter().setArgb(0, 0, rampArgbAt(sorted[chosen]))
                iv.setImage(phImg)
                def tt = new javafx.scene.control.Tooltip(
                    String.format(java.util.Locale.US, 'OD = %.3f', sorted[chosen]))
                javafx.scene.control.Tooltip.install(iv, tt)
                stripPane.getChildren().add(iv)
                entries << [od: sorted[chosen], view: iv, tip: tt, cell: cells[(int) order[chosen]]]
            }
            thumbData.set(entries)
            relayoutAligned()
            // Kesitler arka planda okunur — FX asla server.readRegion beklemez (spec §①)
            new Thread({
                entries.each { e ->
                    try {
                        def roi = e.cell.getROI()
                        double cx = roi.getCentroidX(), cy = roi.getCentroidY()
                        double sidePx = calibrated ? (20.0d / pw) : 48.0d
                        double ds = Math.max(1.0d, sidePx / 40.0d)
                        int rx = (int) Math.max(0.0d, Math.min(cx - sidePx / 2.0d, server.getWidth() - sidePx))
                        int ry = (int) Math.max(0.0d, Math.min(cy - sidePx / 2.0d, server.getHeight() - sidePx))
                        def req = RegionRequest.createInstance(server.getPath(), ds, rx, ry, (int) sidePx, (int) sidePx)
                        def img = server.readRegion(req)
                        if (img == null) throw new RuntimeException('boş bölge')
                        int W = img.getWidth(), H = img.getHeight()
                        int[] argb = new int[W * H]
                        img.getRGB(0, 0, W, H, argb, 0, W)
                        def wimg = new javafx.scene.image.WritableImage(W, H)
                        wimg.getPixelWriter().setPixels(0, 0, W, H,
                            javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, W)
                        javafx.application.Platform.runLater { ((javafx.scene.image.ImageView) e.view).setImage(wimg) }
                    } catch (Throwable ignored) {
                        javafx.application.Platform.runLater {
                            ((javafx.scene.control.Tooltip) e.tip).setText(
                                ((javafx.scene.control.Tooltip) e.tip).getText() + ' — kesit okunamadı')
                        }
                    }
                }
            } as Runnable).start()
        }

        // İlk yerleşim + her boyut değişiminde hizala. boundsInParent İLK yerleşimde de
        // tetiklenir — eksen scale'i yerleşimden önce 0'dır, erken konumlama üst üste
        // yığardı (spec: ChartThresholdPane'in kendi tetikleyici seti)
        chart.boundsInParentProperty().addListener({ obs, ov, nv ->
            javafx.application.Platform.runLater {
                if (((List) thumbData.get()).isEmpty()) rebuildThumbs?.call()
                relayoutAligned?.call()
            }
        } as javafx.beans.value.ChangeListener)

        // ── ⑦ Sınırlamalar: her madde NEDENİYLE birlikte (spec §⑦) ──
        def limitLabel = { String txt ->
            def l = new javafx.scene.control.Label(txt)
            l.setWrapText(true); l.setMaxWidth(Double.MAX_VALUE)
            l.setStyle('-fx-font-size: 11px;')
            return l
        }
        def stripNote = limitLabel(
            'Şerit ≠ hücre görünümü: renk şeridi ORTALAMA OD sayısının lejantıdır; granüler-güçlü ve ' +
            'yaygın-zayıf boyanma aynı ortalamayı paylaşabilir. Gerçek referans üstteki hücre kesitleridir.')
        def liveNote = limitLabel(
            'Çok büyük tespit kümelerinde her bırakışta yeniden sınıflandırma gecikme yaratabilir — ' +
            'bu yüzden "Canlı uygula" kapatılabilir.')
        def dabWarnLabel = limitLabel(
            'Dikkat: varsayılan eşikler DAB için kalibre edilmiştir; bu kromojen için başlangıç noktası ' +
            'olarak kullanın, doğrulanmış varsayılan değildir.')
        dabWarnLabel.setStyle('-fx-font-size: 11px; -fx-text-fill: #B45309;')
        dabWarnLabel.setVisible(false); dabWarnLabel.setManaged(false)

        def limitsBox = new javafx.scene.layout.VBox(6,
            limitLabel('1) Şerit ≠ hücre görünümü — OD 0.4\'teki düz renk örneği "2+ hücre böyle görünür" demek ' +
                'DEĞİLDİR; ortalama farklı boyanma desenlerinde aynı çıkabilir. Kesitler bu yüzden var.'),
            limitLabel('2) Renkler boya vektörüne bağlı — şerit, görüntünün güncel vektör tahmininden üretilir; ' +
                'yanlış tahmin yanlış renk gösterir. Önce Boya vektörleri sihirbazını çalıştırın (Yardımcılar → Boya ve renk).'),
            limitLabel('3) Kesitler tek örnektir — her yuva hedef OD\'ye EN YAKIN tek hücreyi gösterir; o değerin ' +
                'çevresindeki çeşitliliği gösteremez. Tüm dağılım histogramdadır.'),
            limitLabel('4) Eşik sonucu doğrudan kaydırır — bir eşiği sürüklemek 1+/2+/3+ dağılımını ve H-score\'u ' +
                'tanım gereği değiştirir; "doğru" tek eşik yoktur. Yöntem referansınızla tutarlı bir eşik seçip ' +
                'belgeleyin; aynı çalışmada slayttan slayta değiştirmeyin.'),
            limitLabel('5) Yalnızca yeniden bin\'ler — tespit/genişletme parametrelerine dokunmaz (onlar için modülü ' +
                'yeniden çalıştırın). Piksel-bazlı H-score yeniden HESAPLANMAZ; bayat ölçümler her uygulamada temizlenir.'),
            limitLabel('6) Canlı uygulamanın boyut maliyeti — büyük kümelerde her bırakışta yeniden sınıflandırma ' +
                'gecikebilir; bu yüzden açma/kapama seçeneğidir.'),
            limitLabel('7) Ölçüm üretir, yorum üretmez — klinik eşik/kategori önermez.'),
            limitLabel('8) Canlı uygulama geri-al yığınını tüketir — her uygulama bir Ctrl+Z adımı kaydeder ve QuPath ' +
                '10 adım tutar; uzun ayar oturumundan sonra sihirbaz-öncesi duruma Ctrl+Z ile dönmeye güvenmeyin. ' +
                'Sıfırla + sürükleme, ölçümlerden kayıpsız geri dönüş yoludur.'),
            limitLabel('9) Piksel-düzeyi alternatif — piksel renk eşikleme QuPath\'te zaten var: ' +
                'Classify → Pixel classification → Create thresholder. Bu sihirbaz bir üst katmanda, hücre başına ' +
                'ortalama kromojen OD üzerinde çalışır; ikisi birbirinin yedeği değil tamamlayıcısıdır.'),
            limitLabel('10) Varsayılan eşikler DAB için kalibredir — AEC gibi diğer kromojenlerde başlangıç ' +
                'noktasıdır, doğrulanmış varsayılan değildir.'))
        limitsBox.setPadding(new javafx.geometry.Insets(4, 8, 4, 8))
        def limitsPane = new javafx.scene.control.TitledPane('Sınırlamalar — neden böyle?', limitsBox)
        limitsPane.setExpanded(false)

        // ── Veri (yeniden) kurulumu: kapsam/sütun değişimi + açılış ──
        def rebuildData = { String scope, String forcedColumn, boolean resetThresholds ->
            long myRebuild = rebuildSeq.incrementAndGet()
            statusLabel.setText('Hesaplanıyor…')
            new Thread({
                def result = null
                def err = null
                try {
                    def cells = cellsForScope(scope)
                    def cols = discoverColumns(cells)
                    if (cols.isEmpty()) {
                        err = 'Bu kapsamda tanınan "... OD mean" sütunu taşıyan tespit yok.'
                    } else {
                        String column = (forcedColumn != null && cols.contains(forcedColumn)) ? forcedColumn : cols[0]
                        def sc = buildScored(cells, column)
                        if (((List) sc.cells).isEmpty()) {
                            err = "Sütunda (${column}) sonlu değer taşıyan tespit yok."
                        } else {
                            def dflt = defaultsForColumn(column)
                            double[] sorted = (double[]) sc.sorted
                            double dataMax = sorted[sorted.length - 1]
                            double axisMax = Math.max(dataMax, ((double) dflt.vals[2]) * 1.25d)
                            result = [cells: sc.cells, order: sc.order, sorted: sorted,
                                      column: column, columns: cols,
                                      scopeLabel: scope, scopeAnnos: annosForScope(scope),
                                      axisMax: axisMax, hint: dflt.hint,
                                      defVals: dflt.vals, defKeys: dflt.keys,
                                      baseline: [dflt.vals[0], dflt.vals[1], dflt.vals[2]] as double[]]
                        }
                    }
                } catch (Throwable t) {
                    err = t.getMessage() ?: t.getClass().getSimpleName()
                }
                def resultF = result
                def errF = err
                javafx.application.Platform.runLater {
                    if (myRebuild != rebuildSeq.get()) return   // eski (terk edilmiş) kurulum — at
                    if (errF != null) { statusLabel.setText('Hata: ' + errF); return }
                    DATA.set(resultF)
                    double am = (double) resultF.axisMax
                    xAxis.setLowerBound(0.0d)
                    xAxis.setUpperBound(am)
                    xAxis.setTickUnit(am / 5.0d)
                    def hist = new qupath.lib.analysis.stats.Histogram((double[]) resultF.sorted, 128, 0.0d, am)
                    chart.getHistogramData().setAll(
                        [qupath.lib.gui.charts.HistogramChart.createHistogramData(hist, javafx.scene.paint.Color.web('#2A7F8F'))])
                    def cols2 = (List) resultF.columns
                    columnBox.setItems(javafx.collections.FXCollections.observableArrayList(cols2))
                    columnBox.getSelectionModel().select(resultF.column)
                    boolean many = cols2.size() > 1
                    columnLabel.setVisible(many); columnLabel.setManaged(many)
                    columnBox.setVisible(many); columnBox.setManaged(many)
                    moduleLabel.setText(((String) resultF.hint) + ' · ' + ((String) resultF.column))
                    boolean isDabCol = ((String) resultF.column).endsWith(': DAB OD mean')
                    dabWarnLabel.setVisible(!isDabCol); dabWarnLabel.setManaged(!isDabCol)
                    if (resetThresholds || thresholds[2].doubleValue() > am)
                        setThresholds((double) resultF.defVals[0], (double) resultF.defVals[1], (double) resultF.defVals[2])
                    else
                        updateStats()
                    // [T4-REBUILD-HOOK]
                    rebuildRamp?.call()
                    rebuildThumbs?.call()
                    relayoutAligned?.call()
                    statusLabel.setText('Hazır')
                    if (liveChk.isSelected() && !invalidated.get()) submitApply?.call(false)
                }
            } as Runnable).start()
        }

        scopeBox.valueProperty().addListener({ obs, ov, nv ->
            if (nv != null && ov != null && ov != nv) rebuildData((String) nv, (String) columnBox.getValue(), false)
        } as javafx.beans.value.ChangeListener)
        columnBox.setOnAction({
            def v = columnBox.getValue()
            def d = DATA.get()
            if (v != null && d != null && v != d.column) rebuildData((String) scopeBox.getValue(), (String) v, true)
        } as javafx.event.EventHandler)

        // ── Uygulama hattı: TÜM hiyerarşi yazıları tek iş parçacıklı yürütücüde ──
        def applyExec = java.util.concurrent.Executors.newSingleThreadExecutor({ r ->
            def t = new Thread(r, 'esik-paleti-apply'); t.setDaemon(true); return t
        } as java.util.concurrent.ThreadFactory)
        def applySeq = new java.util.concurrent.atomic.AtomicLong(0L)

        submitApply = { boolean showResult ->
            def d = DATA.get()
            if (d == null || invalidated.get()) return
            def t = curThr()
            long mySeq = applySeq.incrementAndGet()
            statusLabel.setText('Uygulanıyor…')
            applyExec.submit({
                try {
                    // Birleştirme: kuyruktaki eski CANLI görevler atlanır (Uygula asla atlanmaz)
                    if (!showResult && mySeq != applySeq.get()) return
                    // Çalışma-zamanı geçerlilik: görüntü değişti mi, kapsam anotasyonları duruyor mu?
                    boolean stillValid = QP.getCurrentImageData() == imageData &&
                        (d.scopeLabel != SCOPE_SELECTED || QP.getAnnotationObjects().containsAll((List) d.scopeAnnos))
                    if (!stillValid) {
                        invalidated.set(true)
                        javafx.application.Platform.runLater {
                            statusLabel.setText('Kapsam artık geçerli değil — pencereyi kapatın')
                            liveChk.setSelected(false); liveChk.setDisable(true); applyBtn.setDisable(true)
                        }
                        return
                    }
                    long t0 = System.currentTimeMillis()
                    boolean stale = applyThresholds((List) d.cells, (String) d.column,
                        (double) t[0], (double) t[1], (double) t[2], (List) d.scopeAnnos)
                    lastApplied.set([t[0], t[1], t[2]] as double[])
                    double elapsed = (System.currentTimeMillis() - t0) / 1000.0d
                    def s = showResult ? summarizeFromObjects((List) d.cells) : null
                    javafx.application.Platform.runLater {
                        statusLabel.setText('Hazır')
                        updateStats()
                        if (showResult) {
                            showResultWindow('Eşikler Uygulandı 🎛️',
                                buildResultText((String) d.hint, (String) d.column, (String) d.scopeLabel, s,
                                    (double) t[0], (double) t[1], (double) t[2],
                                    (double[]) d.baseline, elapsed, stale))
                        }
                    }
                } catch (Throwable err) {
                    javafx.application.Platform.runLater {
                        statusLabel.setText('Hata: ' + (err.getMessage() ?: err.getClass().getSimpleName()))
                    }
                }
            } as Runnable)
        }

        applyBtn.setOnAction({ submitApply(true) } as javafx.event.EventHandler)

        resetBtn.setOnAction({
            def d = DATA.get(); if (d == null) return
            setThresholds((double) d.defVals[0], (double) d.defVals[1], (double) d.defVals[2])
            if (liveChk.isSelected() && !invalidated.get()) submitApply(false)
        } as javafx.event.EventHandler)

        saveBtn.setOnAction({
            def d = DATA.get(); if (d == null) return
            def t = curThr()
            def wpCls = __wpClass()
            if (wpCls != null) {
                [[d.defKeys[0], t[0]], [d.defKeys[1], t[1]], [d.defKeys[2], t[2]]].each { pair ->
                    try {
                        wpCls.getMethod('setDbl', String.class, double.class)
                            .invoke(null, (String) pair[0], ((Number) pair[1]).doubleValue())
                    } catch (Throwable ignored) { }
                }
                Dialogs.showMessageDialog('Varsayılan kaydedildi', String.format(java.util.Locale.US,
                    'Yeni varsayılan eşikler kaydedildi:\n  1+: %.3f\n  2+: %.3f\n  3+: %.3f', t[0], t[1], t[2]))
            } else {
                Dialogs.showWarningNotification('Kaydedilemedi',
                    'Atölye eklentisi yüklü değil — varsayılan kaydedilemedi.')
            }
        } as javafx.event.EventHandler)

        liveChk.selectedProperty().addListener({ obs, ov, nv ->
            uiPrefs.put('liveApply', String.valueOf(nv))
            if (nv && !invalidated.get()) submitApply(false)
        } as javafx.beans.value.ChangeListener)

        // Kapat: bekleyen fark varsa (canlı açıkken) SON bir uygulama gönder,
        // sonra yürütücüyü DRENE ederek kapat (shutdownNow asla — yazı yarıda kesilmez)
        stage.setOnHidden({
            def la = (double[]) lastApplied.get()
            def t = curThr()
            if (liveChk.isSelected() && !invalidated.get() &&
                (la == null || la[0] != t[0] || la[1] != t[1] || la[2] != t[2]))
                submitApply(false)
            applyExec.shutdown()
        } as javafx.event.EventHandler)

        // ── Yerleşim ──
        def scopeRow = new javafx.scene.layout.HBox(8,
            new javafx.scene.control.Label('Kapsam:'), scopeBox, columnLabel, columnBox, moduleLabel)
        scopeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        scopeRow.setPadding(new javafx.geometry.Insets(10, 10, 4, 10))

        def fieldsRow = new javafx.scene.layout.HBox(10,
            new javafx.scene.control.Label('1+ ≥'), fields[0],
            new javafx.scene.control.Label('2+ ≥'), fields[1],
            new javafx.scene.control.Label('3+ ≥'), fields[2],
            dirtyLabel)
        fieldsRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)

        def centerBox = new javafx.scene.layout.VBox(8, stripPane, rampHolder, thrPane, fieldsRow, statsLabel)
        centerBox.setPadding(new javafx.geometry.Insets(4, 10, 4, 10))

        centerBox.getChildren().add(2, stripNote)          // şeridin hemen altına
        centerBox.getChildren().add(0, dabWarnLabel)
        centerBox.getChildren().add(limitsPane)

        def spacer = new javafx.scene.layout.Region()
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
        def liveBox = new javafx.scene.layout.VBox(2, liveChk, liveNote)
        liveNote.setMaxWidth(260)
        def buttons = new javafx.scene.layout.HBox(10, liveBox, alwaysTop, spacer, resetBtn, saveBtn, closeBtn, applyBtn)
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

        def disclaimer = new javafx.scene.control.Label('⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')
        disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
        disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-font-size: 11px;')

        def bottomBox = new javafx.scene.layout.VBox(8, statusLabel, buttons, disclaimer)
        bottomBox.setPadding(new javafx.geometry.Insets(6, 10, 10, 10))

        def root = new javafx.scene.layout.BorderPane()
        root.setTop(scopeRow)
        root.setCenter(centerBox)
        root.setBottom(bottomBox)

        stage.setScene(new javafx.scene.Scene(root, 800, 700))
        stage.show()
        rebuildData(initialScope, null, true)
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Eşik paleti',
            'Pencere açılamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName()))
    }
}
