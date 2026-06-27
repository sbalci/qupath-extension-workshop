/**
 * Modül 3b - Tek Tıkla ER / PR Nükleer H-score
 * ----------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Atölye için "hızlı deneme" betiği. Seçilen anotasyon içinde
 * ER / PR (östrojen / progesteron reseptörü) gibi nükleer hormon
 * reseptörlerini skorlar ve **H-score**'u (0–300) kaydeder.
 *
 * KULLANIM:
 *   1. ER veya PR İHK slaytını açın
 *   2. Image type → "Brightfield (H-DAB)"
 *   3. [R] tuşu → tümör içeren ~1×1 mm dikdörtgen anotasyon çizin ve SEÇİN
 *   4. Bu betiği çalıştırın → açılan TEK pencerede "Çalıştır"
 *   5. Pencere açık kalır: eşikleri değiştirip "Yeniden say (hızlı)" ile
 *      hücre tespitini TEKRAR ÇALIŞTIRMADAN saniyeler içinde yeniden bin'leyin.
 *      "Öğretim modu" açıkken varsayılan ↔ sizin eşik karşılaştırması da gösterilir.
 *
 * NE YAPAR:
 *   • Çekirdek tespiti + DAB OD'ye göre Negative / 1+ / 2+ / 3+ sınıflama
 *   • H-score = (1 × %1+) + (2 × %2+) + (3 × %3+)        [0–300]
 *   • Pozitif yüzdesi (≥1+ olan her çekirdek)
 *   • Hücre yoğunluğu (hücre/mm²)
 *
 * NE YAPMAZ:
 *   • Allred skoru hesaplamaz (yoğunluk × dağılım puanı — manuel)
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathAnnotationObject

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
// --- Atölye ayarları: eklenti yüklüyse oku, yoksa atölye varsayılanı kullanılır ---
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeD = { String k, double  d -> (double)  __wpCall('dbl',  [String.class, double.class]  as Class[], [k, d] as Object[], d) }
def atolyeS = { String k, String  d -> (String)  __wpCall('str',  [String.class, String.class]  as Class[], [k, d] as Object[], d) }
def atolyeI = { String k, int     d -> (int)     __wpCall('intg', [String.class, int.class]     as Class[], [k, d] as Object[], d) }
def atolyeB = { String k, boolean d -> (boolean) __wpCall('bool', [String.class, boolean.class] as Class[], [k, d] as Object[], d) }

// ──────────────────────────────────────────────────────────────
// 1) Ön kontroller
// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(
        "Görüntü açık değil",
        "Önce bir ER veya PR İHK slaytı açın."
    )
    return
}

// Image type kontrolü (Brightfield (H-DAB) zorunlu — DAB ayrımı için).
// Modal pencere yerine: aşağıdaki pencere her zaman açılır; tip yanlışsa kullanıcı
// pencere içinde yönlendirilir, Image panelinden tipi düzeltip "Çalıştır"a tekrar
// basabilir (her çalıştırmada yeniden denetlenir — pencere kapanmaz).
def imageTypeOk = { ->
    def n = (imageData.getImageType()?.toString() ?: "")
        .toUpperCase(java.util.Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
    n.contains('H_DAB')
}
def imageTypeGuidance = { ->
    "Bu slayt 'Brightfield (H-DAB)' olarak ayarlı değil.\n" +
    "Şu anki tip: ${imageData.getImageType()?.toString() ?: ''}\n\n" +
    "Çözüm:\n" +
    "  1. Image panelini açın (sol-üst)\n" +
    "  2. 'Image type' → 'Brightfield (H-DAB)' seçin (DAB ayrımı için gerekli)\n" +
    "  3. Yukarıdaki 'Çalıştır' düğmesine tekrar basın"
}

// H-DAB ise ve boya vektörleri eksikse atölye varsayılanını uygula.
// (Yanlış tipteki slaytları otomatik dönüştürmeyiz — yönlendirme pencerede gösterilir.)
if (imageTypeOk()) {
    def stains = imageData.getColorDeconvolutionStains()
    def hasHematoxylin = false
    if (stains != null) {
        for (int i = 1; i <= 3; i++) {
            def name = stains.getStain(i)?.getName()?.toLowerCase(java.util.Locale.ROOT)
            if (name != null && name.contains("hematoxylin")) { hasHematoxylin = true; break }
        }
    }
    if (!hasHematoxylin) {
        println "⚠ H-DAB boya vektörleri tanımlı değil → BRIGHTFIELD_H_DAB varsayılanı uygulanıyor."
        QP.setImageType('BRIGHTFIELD_H_DAB')
    }
}

// ──────────────────────────────────────────────────────────────
// 2) Ölçüm + sonuç oluşturma — paylaşılan yardımcılar.
//    Hücreler "Nucleus: DAB OD mean" sütununda bin'lenir. Eşik değişince hücre
//    tespitini yeniden çalıştırmak yerine sadece bu sütun üzerinden yeniden
//    sınıflandırırız (setIntensityClassifications). "recount" bunu kullanır.
// ──────────────────────────────────────────────────────────────
def COMPARTMENT = 'Nucleus: DAB OD mean'
def DEF1 = atolyeD('atolye.nuclear1', 0.20)
def DEF2 = atolyeD('atolye.nuclear2', 0.40)
def DEF3 = atolyeD('atolye.nuclear3', 0.60)
def warnGenericCount = atolyeI('atolye.warnGenericCount', 200)

def resolveTarget = { ->
    if (!imageTypeOk())
        return [ok:false, error: imageTypeGuidance(), imageTypeIssue:true]
    def selected = QP.getSelectedObject()
    if (selected == null || !(selected instanceof PathAnnotationObject))
        return [ok:false, error:'Önce ölçmek istediğiniz dikdörtgen anotasyonu çizip SEÇİN (kenarı sarı görünür).']
    def cal = imageData.getServer().getPixelCalibration()
    def pw = cal.getPixelWidthMicrons()
    def ph = cal.getPixelHeightMicrons()
    if (!(pw > 0) || !(ph > 0))
        return [ok:false, error:'Piksel kalibrasyonu yok; alan/yoğunluk (mm²) hesaplanamaz (Yardımcılar → Kalibrasyon).']
    return [ok:true, ann:selected, pw:pw, ph:ph]
}

// Verili eşiklerle (yeniden tespit YOK) hücreleri bin'le + H-score say.
def metricsAt = { targetAnnotation, double pw, double ph, double thr1, double thr2, double thr3 ->
    def cells = targetAnnotation.getChildObjects().findAll { it.isDetection() }
    QP.setIntensityClassifications(cells, COMPARTMENT, thr1, thr2, thr3)
    int total = cells.size(), n0 = 0, n1 = 0, n2 = 0, n3 = 0
    cells.each { c ->
        def cls = c.getPathClass()?.getName() ?: ""
        if (cls.contains("3+"))      n3++
        else if (cls.contains("2+")) n2++
        else if (cls.contains("1+")) n1++
        else                          n0++
    }
    int pos = n1 + n2 + n3
    double pctNeg = total > 0 ? 100.0 * n0 / total : 0.0
    double pct1   = total > 0 ? 100.0 * n1 / total : 0.0
    double pct2   = total > 0 ? 100.0 * n2 / total : 0.0
    double pct3   = total > 0 ? 100.0 * n3 / total : 0.0
    double posPct = total > 0 ? 100.0 * pos / total : 0.0
    double hScore = (1.0 * pct1) + (2.0 * pct2) + (3.0 * pct3)
    def roi = targetAnnotation.getROI()
    double areaMm2 = roi != null ? (roi.getArea() * pw * ph) / 1_000_000.0 : 0.0
    long density = areaMm2 > 0 ? Math.round(total / areaMm2) : 0L
    return [total:total, n0:n0, n1:n1, n2:n2, n3:n3, pos:pos,
            pctNeg:pctNeg, pct1:pct1, pct2:pct2, pct3:pct3, posPct:posPct,
            hScore:hScore, area:areaMm2, density:density]
}

def buildResultText = { m, double thr1, double thr2, double thr3, double elapsed, baseline ->
    def uyari = ""
    if (m.total < warnGenericCount) {
        uyari = String.format(java.util.Locale.US,
            "\n❗ Not: %,d hücre <%d — küçük örneklem; sonuçlar istatistiksel olarak güvenilir olmayabilir.",
            m.total, warnGenericCount)
    }
    def text = String.format(java.util.Locale.US,
        "ER / PR Nükleer H-score kantifikasyonu bitti.\n\n" +
        "Eşikler  : 1+=%.2f  2+=%.2f  3+=%.2f (DAB OD)%s\n\n" +
        "Sayım (n = %,d toplam çekirdek)\n" +
        "────────────────────────────────────\n" +
        "  Negatif (0)         : %,d  (%%%.1f)\n" +
        "  1+ (zayıf)          : %,d  (%%%.1f)\n" +
        "  2+ (orta)           : %,d  (%%%.1f)\n" +
        "  3+ (güçlü)          : %,d  (%%%.1f)\n\n" +
        "H-score (özet)\n" +
        "─────────────────\n" +
        "  H-score (0–300)        : %.0f\n" +
        "  Pozitif yüzdesi (≥1+)  : %%%.1f\n" +
        "  %% 0 / 1+ / 2+ / 3+    : %%%.1f / %%%.1f / %%%.1f / %%%.1f\n\n" +
        "  Hücre yoğunluğu        : ~%,d hücre/mm²\n" +
        "  Anotasyon alanı        : %.2f mm²\n" +
        "  Süre                   : %.1f sn\n",
        thr1, thr2, thr3,
        (thr1 != DEF1 || thr2 != DEF2 || thr3 != DEF3) ? ' (değiştirildi)' : '',
        m.total,
        m.n0, m.pctNeg, m.n1, m.pct1, m.n2, m.pct2, m.n3, m.pct3,
        m.hScore, m.posPct,
        m.pctNeg, m.pct1, m.pct2, m.pct3,
        m.density, m.area, elapsed
    )
    if (baseline != null) {
        text += String.format(java.util.Locale.US,
            "\n🎓 Öğretim — varsayılan ↔ sizin (aynı hücreler, yeniden bin'lendi)\n" +
            "──────────────────────────────────────────────\n" +
            "  Varsayılan %.2f/%.2f/%.2f → H-score %.0f  (≥1+ %%%.1f)\n" +
            "  Sizin      %.2f/%.2f/%.2f → H-score %.0f  (≥1+ %%%.1f)\n" +
            "  Fark: %+.0f puan  (eşik yükseldikçe H-score düşer)\n",
            DEF1, DEF2, DEF3, baseline.hScore, baseline.posPct,
            thr1, thr2, thr3, m.hScore, m.posPct,
            (m.hScore - baseline.hScore)
        )
    }
    text += uyari + "\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return text
}

def finishWithMetrics = { t, double thr1, double thr2, double thr3, boolean teaching, double extraElapsed ->
    long t0 = System.currentTimeMillis()
    def m = metricsAt(t.ann, t.pw, t.ph, thr1, thr2, thr3)
    def baseline = null
    if (teaching && (thr1 != DEF1 || thr2 != DEF2 || thr3 != DEF3)) {
        baseline = metricsAt(t.ann, t.pw, t.ph, DEF1, DEF2, DEF3)
        m = metricsAt(t.ann, t.pw, t.ph, thr1, thr2, thr3)
    }
    QP.fireHierarchyUpdate()
    double elapsed = extraElapsed + (System.currentTimeMillis() - t0) / 1000.0
    def text = buildResultText(m, thr1, thr2, thr3, elapsed, baseline)

    println "─────────────────────────────────────"
    println "Modül 3b - ER / PR Nükleer H-score"
    println "  n=${m.total} | 0:${m.n0} 1+:${m.n1} 2+:${m.n2} 3+:${m.n3} | H-score: ${String.format(java.util.Locale.US, '%.0f', m.hScore)}"
    println "─────────────────────────────────────"

    return [ok:true, text:text]
}

def runDetection = { double nuclear1, double nuclear2, double nuclear3, boolean teaching ->
    def t = resolveTarget()
    if (!t.ok) return t
    def targetAnnotation = t.ann

    double pixelSizeMicrons = atolyeD('atolye.pixelSize', 0.5)
    double backgroundRadius = atolyeD('atolye.backgroundRadius', 8.0)
    double medianRadius     = atolyeD('atolye.medianRadius', 0.0)
    double sigma            = atolyeD('atolye.sigma', 1.5)
    double minArea          = atolyeD('atolye.minArea', 10.0)
    double maxArea          = atolyeD('atolye.maxArea', 400.0)
    double detectionThreshold = atolyeD('atolye.detectionThreshold', 0.1)
    double cellExpansion    = atolyeD('atolye.cellExpansionNuclear', 5.0)

    long t0 = System.currentTimeMillis()
    QP.selectObjects(targetAnnotation)
    QP.runPlugin(
        'qupath.imagej.detect.cells.PositiveCellDetection',
        '{' +
            '"detectionImageBrightfield":"Hematoxylin OD",' +
            '"requestedPixelSizeMicrons":' + pixelSizeMicrons + ',' +
            '"backgroundRadiusMicrons":' + backgroundRadius + ',' +
            '"medianRadiusMicrons":' + medianRadius + ',' +
            '"sigmaMicrons":' + sigma + ',' +
            '"minAreaMicrons":' + minArea + ',' +
            '"maxAreaMicrons":' + maxArea + ',' +
            '"threshold":' + detectionThreshold + ',' +
            '"watershedPostProcess":true,' +
            '"cellExpansionMicrons":' + cellExpansion + ',' +
            '"includeNuclei":true,' +
            '"smoothBoundaries":true,' +
            '"makeMeasurements":true,' +
            '"thresholdCompartment":"Nucleus: DAB OD mean",' +
            '"thresholdPositive1":' + nuclear1 + ',' +
            '"thresholdPositive2":' + nuclear2 + ',' +
            '"thresholdPositive3":' + nuclear3 + ',' +
            '"singleThreshold":false' +
        '}'
    )
    double detectElapsed = (System.currentTimeMillis() - t0) / 1000.0
    return finishWithMetrics(t, nuclear1, nuclear2, nuclear3, teaching, detectElapsed)
}

def recount = { double thr1, double thr2, double thr3, boolean teaching ->
    def t = resolveTarget()
    if (!t.ok) return t
    def existing = t.ann.getChildObjects().findAll { it.isDetection() }
    if (existing.isEmpty())
        return [ok:false, error:'Bu anotasyonda hücre tespiti yok. Önce "Çalıştır" ile tespit yapın, sonra "Yeniden say (hızlı)" kullanın.']
    return finishWithMetrics(t, thr1, thr2, thr3, teaching, 0.0)
}

// Headless: tek sefer atölye varsayılanlarıyla çalıştır + yazdır.
if (isHeadless) {
    def r = runDetection(DEF1, DEF2, DEF3, false)
    println r.ok ? r.text : ("Hata: " + r.error)
    return
}

// ──────────────────────────────────────────────────────────────
// 3) Tek pencere: ayarla → Çalıştır → sonuç → (gerekirse) hızlı yeniden say
// ──────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        def hasDetections = new java.util.concurrent.atomic.AtomicBoolean(false)

        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Modül 3b - ER / PR Nükleer H-score')
        stage.setAlwaysOnTop(true)

        def title = new javafx.scene.control.Label('ER / PR Nükleer H-score')
        title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
        def info = new javafx.scene.control.Label(
            'Bir dikdörtgen anotasyon (R) çizip SEÇİN (kenarı sarı), sonra "Çalıştır".\n' +
            'Eşik değiştirince "Yeniden say (hızlı)" tespiti tekrarlamadan saniyeler içinde günceller.')
        info.setWrapText(true)

        def spT1 = new javafx.scene.control.Spinner(0.0, 2.0, DEF1, 0.01)
        def spT2 = new javafx.scene.control.Spinner(0.0, 2.0, DEF2, 0.01)
        def spT3 = new javafx.scene.control.Spinner(0.0, 2.0, DEF3, 0.01)
        [spT1, spT2, spT3].each { it.setEditable(true); it.setPrefWidth(110) }
        def thrTip = new javafx.scene.control.Tooltip(
            'DAB optik yoğunluğu (OD) eşiği: bir çekirdeğin "Nucleus: DAB OD mean" değeri\n' +
            'bu eşiğin üzerindeyse o sınıfa (1+/2+/3+) atanır. H-score = 1×%1+ + 2×%2+ + 3×%3+.\n' +
            'Eşiği YÜKSELTMEK H-score\'u düşürür. Sıra: 1+ < 2+ < 3+ olmalı.')
        [spT1, spT2, spT3].each { it.setTooltip(thrTip) }
        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(6); grid.setPadding(new javafx.geometry.Insets(6))
        grid.addRow(0, new javafx.scene.control.Label('1+ eşiği (zayıf, DAB OD)'), spT1)
        grid.addRow(1, new javafx.scene.control.Label('2+ eşiği (orta, DAB OD)'),  spT2)
        grid.addRow(2, new javafx.scene.control.Label('3+ eşiği (güçlü, DAB OD)'), spT3)
        def advHint = new javafx.scene.control.Label(
            'DAB OD eşiği: çekirdeğin DAB sinyali bu değerin üzerindeyse pozitif sayılır. ' +
            'Yükseltmek H-score\'u düşürür.')
        advHint.setWrapText(true)
        advHint.setStyle('-fx-font-style: italic; -fx-opacity: 0.7; -fx-font-size: 11px;')
        def advBox = new javafx.scene.layout.VBox(6, grid, advHint)
        def adv = new javafx.scene.control.TitledPane('⚙ Gelişmiş ayarlar — eşikler', advBox)
        adv.setExpanded(false); adv.setAnimated(false)

        def teachChk = new javafx.scene.control.CheckBox('Öğretim modu (varsayılan ↔ sizin karşılaştırması)')
        teachChk.setSelected(true)

        def status = new javafx.scene.control.Label('Hazır.')
        def progress = new javafx.scene.control.ProgressBar()
        progress.setMaxWidth(Double.MAX_VALUE); progress.setVisible(false); progress.setManaged(false)
        def resultArea = new javafx.scene.control.TextArea()
        resultArea.setEditable(false); resultArea.setWrapText(false); resultArea.setPrefRowCount(10)
        resultArea.setPromptText('Sonuçlar burada görünecek…')
        resultArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;")

        def runBtn = new javafx.scene.control.Button('Çalıştır'); runBtn.setDefaultButton(true)
        def recountBtn = new javafx.scene.control.Button('Yeniden say (hızlı)'); recountBtn.setDisable(true)

        // İlk açılışta görüntü tipi yanlışsa kullanıcıyı pencere içinde yönlendir (modal yok).
        if (!imageTypeOk()) {
            status.setStyle('-fx-text-fill: -qp-script-error-color;')
            status.setText("⚠ Görüntü tipi 'Brightfield (H-DAB)' değil — adımlar aşağıda.")
            resultArea.setText(imageTypeGuidance())
        }

        def handleResult = { res ->
            progress.setVisible(false); progress.setManaged(false)
            runBtn.setDisable(false)
            recountBtn.setDisable(!hasDetections.get())
            if (res.ok) {
                hasDetections.set(true); recountBtn.setDisable(false)
                status.setStyle(''); status.setText('Tamamlandı ✅ — eşikleri değiştirip "Yeniden say (hızlı)" deneyin.')
                resultArea.setText(res.text)
            } else if (res.imageTypeIssue) {
                status.setStyle('-fx-text-fill: -qp-script-error-color;')
                status.setText("⚠ Görüntü tipi 'Brightfield (H-DAB)' değil — adımlar aşağıda.")
                resultArea.setText(res.error)
            } else {
                status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + res.error)
            }
        }

        runBtn.setOnAction({
            runBtn.setDisable(true); recountBtn.setDisable(true)
            status.setStyle(''); status.setText('Çalışıyor… (hücre tespiti)')
            progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
            double t1 = spT1.getValue() as double
            double t2 = spT2.getValue() as double
            double t3 = spT3.getValue() as double
            boolean teaching = teachChk.isSelected()
            def worker = new Thread({
                def res = runDetection(t1, t2, t3, teaching)
                javafx.application.Platform.runLater { handleResult(res) }
            }, 'Modul3bHScore')
            worker.setDaemon(true); worker.start()
        })

        recountBtn.setOnAction({
            runBtn.setDisable(true); recountBtn.setDisable(true)
            status.setStyle(''); status.setText('Yeniden sayılıyor… (tespit yok, sadece bin)')
            progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
            double t1 = spT1.getValue() as double
            double t2 = spT2.getValue() as double
            double t3 = spT3.getValue() as double
            boolean teaching = teachChk.isSelected()
            def worker = new Thread({
                def res = recount(t1, t2, t3, teaching)
                javafx.application.Platform.runLater { handleResult(res) }
            }, 'Modul3bRecount')
            worker.setDaemon(true); worker.start()
        })

        def alwaysTop = new javafx.scene.control.CheckBox('Üstte tut'); alwaysTop.setSelected(true)
        alwaysTop.selectedProperty().addListener(
            { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
        def copyBtn = new javafx.scene.control.Button('Kopyala')
        copyBtn.setOnAction({
            def cb = javafx.scene.input.Clipboard.getSystemClipboard()
            def c = new javafx.scene.input.ClipboardContent(); c.putString(resultArea.getText()); cb.setContent(c)
        })
        def closeBtn = new javafx.scene.control.Button('Kapat'); closeBtn.setOnAction({ stage.close() })

        def footer = new javafx.scene.control.Label('QuPath Atölye Scriptleri · araştırma/eğitim amaçlı')
        footer.setMaxWidth(Double.MAX_VALUE)
        footer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;')

        def spacer = new javafx.scene.layout.Region()
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
        def btnRow = new javafx.scene.layout.HBox(8, alwaysTop, spacer, copyBtn, recountBtn, runBtn, closeBtn)
        btnRow.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

        def content = new javafx.scene.layout.VBox(10, title, info, adv, teachChk, status, progress, resultArea)
        content.setPadding(new javafx.geometry.Insets(14))
        javafx.scene.layout.VBox.setVgrow(resultArea, javafx.scene.layout.Priority.ALWAYS)
        def bottom = new javafx.scene.layout.VBox(8, footer, btnRow)
        bottom.setPadding(new javafx.geometry.Insets(10))
        def root = new javafx.scene.layout.BorderPane()
        root.setCenter(content); root.setBottom(bottom)
        stage.setScene(new javafx.scene.Scene(root, 580, 580))
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Modül 3b açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
