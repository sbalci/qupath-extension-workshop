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
 *   5. Pencere açık kalır: eşikleri değiştirip (Gelişmiş ayarlar) tekrar
 *      çalıştırabilir, sonuçları aynı pencerede güncel görebilirsiniz
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

import qupath.lib.gui.dialogs.Dialogs
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
// 2) Tespit + sonuç toplama tek yerde — pencere kapanmadan tekrar
//    (eşikleri değiştirip) çağrılabilir. Dönüş: [ok, text] | [ok:false, error].
// ──────────────────────────────────────────────────────────────
def runDetection = { double nuclear1, double nuclear2, double nuclear3 ->
    // Görüntü tipini her çalıştırmada yeniden denetle (kullanıcı bu arada düzeltmiş olabilir).
    if (!imageTypeOk())
        return [ok:false, error: imageTypeGuidance(), imageTypeIssue:true]
    def selected = QP.getSelectedObject()
    if (selected == null || !(selected instanceof PathAnnotationObject))
        return [ok:false, error:'Önce ölçmek istediğiniz dikdörtgen anotasyonu çizip SEÇİN (kenarı sarı görünür).']
    def targetAnnotation = selected
    def cal = imageData.getServer().getPixelCalibration()
    def pixelWidth  = cal.getPixelWidthMicrons()
    def pixelHeight = cal.getPixelHeightMicrons()
    if (!(pixelWidth > 0) || !(pixelHeight > 0))
        return [ok:false, error:'Piksel kalibrasyonu yok; alan/yoğunluk (mm²) hesaplanamaz (Yardımcılar → Kalibrasyon).']

    double pixelSizeMicrons = atolyeD('atolye.pixelSize', 0.5)
    double backgroundRadius = atolyeD('atolye.backgroundRadius', 8.0)
    double medianRadius     = atolyeD('atolye.medianRadius', 0.0)
    double sigma            = atolyeD('atolye.sigma', 1.5)
    double minArea          = atolyeD('atolye.minArea', 10.0)
    double maxArea          = atolyeD('atolye.maxArea', 400.0)
    double detectionThreshold = atolyeD('atolye.detectionThreshold', 0.1)
    double cellExpansion    = atolyeD('atolye.cellExpansionNuclear', 5.0)
    int warnGenericCount    = atolyeI('atolye.warnGenericCount', 200)

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
    double elapsed = (System.currentTimeMillis() - t0) / 1000.0

    def cells = targetAnnotation.getChildObjects().findAll { it.isDetection() }
    def totalCells = cells.size()

    def nNeg = 0, n1 = 0, n2 = 0, n3 = 0
    cells.each { c ->
        def cls = c.getPathClass()?.getName() ?: ""
        if (cls.contains("3+"))      n3++
        else if (cls.contains("2+")) n2++
        else if (cls.contains("1+")) n1++
        else                          nNeg++
    }

    def positives = n1 + n2 + n3
    def positivePct = totalCells > 0 ? 100.0 * positives / totalCells : 0.0

    // H-score — ER/PR için standart yoğunluk-ağırlıklı skor (0–300)
    def pct1   = totalCells > 0 ? 100.0 * n1   / totalCells : 0.0
    def pct2   = totalCells > 0 ? 100.0 * n2   / totalCells : 0.0
    def pct3   = totalCells > 0 ? 100.0 * n3   / totalCells : 0.0
    def pctNeg = totalCells > 0 ? 100.0 * nNeg / totalCells : 0.0
    def hScore = (1.0 * pct1) + (2.0 * pct2) + (3.0 * pct3)

    def roi = targetAnnotation.getROI()
    def totalAreaMm2 = roi != null ? (roi.getArea() * pixelWidth * pixelHeight) / 1_000_000.0 : 0.0
    def density = totalAreaMm2 > 0 ? Math.round(totalCells / totalAreaMm2) : 0L

    def uyari = ""
    if (totalCells < warnGenericCount) {
        uyari = String.format(java.util.Locale.US,
            "\n❗ Not: %,d hücre <%d — küçük örneklem; sonuçlar istatistiksel olarak güvenilir olmayabilir.",
            totalCells, warnGenericCount)
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
        "  Süre                   : %.1f sn%s\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
        nuclear1, nuclear2, nuclear3,
        (nuclear1 != 0.20 || nuclear2 != 0.40 || nuclear3 != 0.60) ? ' (değiştirildi)' : '',
        totalCells,
        nNeg, pctNeg, n1, pct1, n2, pct2, n3, pct3,
        hScore, positivePct,
        pctNeg, pct1, pct2, pct3,
        density, totalAreaMm2, elapsed, uyari
    )
    return [ok:true, text:text]
}

// Headless: tek sefer atölye varsayılanlarıyla çalıştır + yazdır.
if (isHeadless) {
    def r = runDetection(
        atolyeD('atolye.nuclear1', 0.20),
        atolyeD('atolye.nuclear2', 0.40),
        atolyeD('atolye.nuclear3', 0.60)
    )
    println r.ok ? r.text : ("Hata: " + r.error)
    return
}

// ──────────────────────────────────────────────────────────────
// 3) Tek pencere: ayarla → Çalıştır → sonuç → (gerekirse) tekrar
// ──────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Modül 3b - ER / PR Nükleer H-score')
        stage.setAlwaysOnTop(true)

        def title = new javafx.scene.control.Label('ER / PR Nükleer H-score')
        title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
        def info = new javafx.scene.control.Label(
            'Bir dikdörtgen anotasyon (R) çizip SEÇİN (kenarı sarı), sonra "Çalıştır".\n' +
            'Eşikleri değiştirip yeniden çalıştırabilirsiniz; sonuç aşağıda güncellenir.')
        info.setWrapText(true)

        def spT1 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.nuclear1', 0.20), 0.01)
        def spT2 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.nuclear2', 0.40), 0.01)
        def spT3 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.nuclear3', 0.60), 0.01)
        [spT1, spT2, spT3].each { it.setEditable(true); it.setPrefWidth(110) }
        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(6); grid.setPadding(new javafx.geometry.Insets(6))
        grid.addRow(0, new javafx.scene.control.Label('1+ eşiği (zayıf, DAB OD)'), spT1)
        grid.addRow(1, new javafx.scene.control.Label('2+ eşiği (orta, DAB OD)'),  spT2)
        grid.addRow(2, new javafx.scene.control.Label('3+ eşiği (güçlü, DAB OD)'), spT3)
        def adv = new javafx.scene.control.TitledPane('⚙ Gelişmiş ayarlar — eşikler', grid)
        adv.setExpanded(false); adv.setAnimated(false)

        def status = new javafx.scene.control.Label('Hazır.')
        def progress = new javafx.scene.control.ProgressBar()
        progress.setMaxWidth(Double.MAX_VALUE); progress.setVisible(false); progress.setManaged(false)
        def resultArea = new javafx.scene.control.TextArea()
        resultArea.setEditable(false); resultArea.setWrapText(false); resultArea.setPrefRowCount(10)
        resultArea.setPromptText('Sonuçlar burada görünecek…')
        resultArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;")

        // İlk açılışta görüntü tipi yanlışsa kullanıcıyı pencere içinde yönlendir (modal yok).
        if (!imageTypeOk()) {
            status.setStyle('-fx-text-fill: -qp-script-error-color;')
            status.setText("⚠ Görüntü tipi 'Brightfield (H-DAB)' değil — adımlar aşağıda.")
            resultArea.setText(imageTypeGuidance())
        }

        def runBtn = new javafx.scene.control.Button('Çalıştır'); runBtn.setDefaultButton(true)
        runBtn.setOnAction({
            runBtn.setDisable(true)
            status.setStyle(''); status.setText('Çalışıyor…')
            progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
            double t1 = spT1.getValue() as double
            double t2 = spT2.getValue() as double
            double t3 = spT3.getValue() as double
            def worker = new Thread({
                def res = runDetection(t1, t2, t3)
                javafx.application.Platform.runLater {
                    progress.setVisible(false); progress.setManaged(false); runBtn.setDisable(false)
                    if (res.ok) {
                        status.setStyle(''); status.setText('Tamamlandı ✅ — eşikleri değiştirip tekrar çalıştırabilirsiniz.')
                        resultArea.setText(res.text)
                    } else if (res.imageTypeIssue) {
                        status.setStyle('-fx-text-fill: -qp-script-error-color;')
                        status.setText("⚠ Görüntü tipi 'Brightfield (H-DAB)' değil — adımlar aşağıda.")
                        resultArea.setText(res.error)
                    } else {
                        status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + res.error)
                    }
                }
            }, 'Modul3bHScore')
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
        def btnRow = new javafx.scene.layout.HBox(8, alwaysTop, spacer, copyBtn, runBtn, closeBtn)
        btnRow.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

        def content = new javafx.scene.layout.VBox(10, title, info, adv, status, progress, resultArea)
        content.setPadding(new javafx.geometry.Insets(14))
        javafx.scene.layout.VBox.setVgrow(resultArea, javafx.scene.layout.Priority.ALWAYS)
        def bottom = new javafx.scene.layout.VBox(8, footer, btnRow)
        bottom.setPadding(new javafx.geometry.Insets(10))
        def root = new javafx.scene.layout.BorderPane()
        root.setCenter(content); root.setBottom(bottom)
        stage.setScene(new javafx.scene.Scene(root, 560, 540))
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Modül 3b açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
