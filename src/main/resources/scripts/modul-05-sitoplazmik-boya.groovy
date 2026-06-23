/**
 * Modül 5 - Tek Tıkla CD68 / Sitoplazmik İHK Kantifikasyonu
 * ----------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Atölye için "hızlı deneme" betiği. Sitoplazmik DAB boyamasını (CD68,
 * CD163 ve sitokeratinler gibi belirteçleri ölçer. Her hücre sitoplazmik DAB yoğunluğuna göre
 * Negative / Weak / Moderate / Strong bin'lerine atanır.
 *
 * KULLANIM:
 *   1. CD68 (veya benzeri sitoplazmik DAB) İHK slaytını açın
 *   2. Image type → "Brightfield (H-DAB)"
 *   3. [R] tuşu → ~1×1 mm dikdörtgen anotasyon çizin ve SEÇİN
 *   4. Bu betiği çalıştırın → açılan TEK pencerede "Çalıştır"
 *   5. Pencere açık kalır: eşikleri değiştirip (Gelişmiş ayarlar) tekrar
 *      çalıştırabilir, sonuçları aynı pencerede güncel görebilirsiniz
 *
 * NEDEN SİTOPLAZMA FARKLI?
 *   • Sitoplazma çekirdekten **çok daha büyük hacme** sahip
 *   • Aynı miktarda DAB → daha düşük ortalama OD
 *   • Bu yüzden EŞİKLER DAHA DÜŞÜK olmalı:
 *       - Nükleer Ki-67: 0.20 / 0.40 / 0.60
 *       - Sitoplazmik CD68: 0.10 / 0.20 / 0.35  ← bu betik
 *   • Hücre genişletme (cell expansion) DAHA BÜYÜK (7 µm) — sitoplazma hacmini örneklemek için
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
        "Önce bir CD68 (veya başka sitoplazmik İHK) slaytı açın."
    )
    return
}

def imageTypeName = imageData.getImageType()?.toString() ?: ""
def normalizedImageType = imageTypeName.toUpperCase(java.util.Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
if (!normalizedImageType.contains('H_DAB')) {
    Dialogs.showErrorMessage(
        "Yanlış görüntü tipi",
        "Image type 'Brightfield (H-DAB)' olmalı. Şu anki: ${imageTypeName}"
    )
    return
}

// "Hematoxylin OD" kanalı yalnızca H-DAB boya vektörleri ayarlanmışsa görünür.
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

// ──────────────────────────────────────────────────────────────
// 2) Tespit + sonuç toplama tek yerde — pencere kapanmadan tekrar
//    (eşikleri değiştirip) çağrılabilir. Dönüş: [ok, text] | [ok:false, error].
// ──────────────────────────────────────────────────────────────
def runDetection = { double cyto1, double cyto2, double cyto3, double cellExpansionCyto ->
    def selected = QP.getSelectedObject()
    if (selected == null || !(selected instanceof PathAnnotationObject))
        return [ok:false, error:'Önce ölçmek istediğiniz dikdörtgen anotasyonu çizip SEÇİN (kenarı sarı görünür).']
    def targetAnnotation = selected
    double pixelSize       = atolyeD('atolye.pixelSize', 0.5)
    double backgroundRadius = atolyeD('atolye.backgroundRadius', 8.0)
    double medianRadius    = atolyeD('atolye.medianRadius', 0.0)
    double sigma           = atolyeD('atolye.sigma', 1.5)
    double minArea         = atolyeD('atolye.minArea', 10.0)
    double maxArea         = atolyeD('atolye.maxArea', 400.0)
    double detectionThreshold = atolyeD('atolye.detectionThreshold', 0.1)
    boolean watershed      = atolyeB('atolye.watershed', true)
    int warnGenericCount   = atolyeI('atolye.warnGenericCount', 200)
    long t0 = System.currentTimeMillis()
    QP.selectObjects(targetAnnotation)
    QP.runPlugin(
        'qupath.imagej.detect.cells.PositiveCellDetection',
        '{' +
            '"detectionImageBrightfield":"Hematoxylin OD",' +
            '"requestedPixelSizeMicrons":' + pixelSize + ',' +
            '"backgroundRadiusMicrons":' + backgroundRadius + ',' +
            '"medianRadiusMicrons":' + medianRadius + ',' +
            '"sigmaMicrons":' + sigma + ',' +
            '"minAreaMicrons":' + minArea + ',' +
            '"maxAreaMicrons":' + maxArea + ',' +
            '"threshold":' + detectionThreshold + ',' +
            '"watershedPostProcess":' + watershed + ',' +
            '"cellExpansionMicrons":' + cellExpansionCyto + ',' +
            '"includeNuclei":true,' +
            '"smoothBoundaries":true,' +
            '"makeMeasurements":true,' +
            '"thresholdCompartment":"Cytoplasm: DAB OD mean",' +
            '"thresholdPositive1":' + cyto1 + ',' +
            '"thresholdPositive2":' + cyto2 + ',' +
            '"thresholdPositive3":' + cyto3 + ',' +
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
    def hScore = totalCells > 0
        ? (1.0 * n1 + 2.0 * n2 + 3.0 * n3) * 100.0 / totalCells
        : 0.0
    def cal = imageData.getServer().getPixelCalibration()
    def pixelWidth  = cal.getPixelWidthMicrons()
    def pixelHeight = cal.getPixelHeightMicrons()
    def totalAreaMm2 = 0.0
    def roi = targetAnnotation.getROI()
    if ((pixelWidth > 0) && (pixelHeight > 0) && roi != null)
        totalAreaMm2 = (roi.getArea() * pixelWidth * pixelHeight) / 1_000_000.0
    def totalDensity   = totalAreaMm2 > 0 ? Math.round(totalCells / totalAreaMm2)  : 0L
    def positiveDensity = totalAreaMm2 > 0 ? Math.round(positives / totalAreaMm2) : 0L
    def pctNeg = totalCells > 0 ? 100.0 * nNeg / totalCells : 0.0
    def pct1   = totalCells > 0 ? 100.0 * n1 / totalCells : 0.0
    def pct2   = totalCells > 0 ? 100.0 * n2 / totalCells : 0.0
    def pct3   = totalCells > 0 ? 100.0 * n3 / totalCells : 0.0
    def uyari = ""
    if (totalCells < warnGenericCount) {
        uyari = String.format(java.util.Locale.US, "\n⚠️ Sadece %,d hücre — istatistiksel olarak güvenilmez (≥500 önerilir).", totalCells)
    }
    def text = String.format(java.util.Locale.US,
        "CD68 / Sitoplazmik İHK bitti.\n\n" +
        "Parametreler : 1+=%.2f  2+=%.2f  3+=%.2f  genişletme=%.1f µm\n" +
        "────────────────────────────────────\n" +
        "📊 Hücre dağılımı (n = %,d toplam)\n" +
        "────────────────────────────────────\n" +
        "  Negative            : %,d  (%%%.1f)\n" +
        "  Weak (1+)           : %,d  (%%%.1f)\n" +
        "  Moderate (2+)       : %,d  (%%%.1f)\n" +
        "  Strong (3+)         : %,d  (%%%.1f)\n\n" +
        "🎯 Bütünleşik metrikler\n" +
        "────────────────────────\n" +
        "  Pozitif yüzdesi       : %%%.1f\n" +
        "  H-score (0–300)       : %.0f\n" +
        "  Toplam yoğunluk       : ~%,d hücre/mm²\n" +
        "  CD68+ yoğunluk        : ~%,d hücre/mm²\n" +
        "  Anotasyon alanı       : %.2f mm²\n" +
        "  Süre                  : %.1f sn\n" +
        "%s\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
        cyto1, cyto2, cyto3, cellExpansionCyto,
        totalCells,
        nNeg, pctNeg, n1, pct1, n2, pct2, n3, pct3,
        positivePct, hScore, totalDensity, positiveDensity, totalAreaMm2, elapsed,
        uyari
    )
    return [ok:true, text:text]
}

// Headless: tek sefer atölye varsayılanlarıyla çalıştır + yazdır.
if (isHeadless) {
    def r = runDetection(
        atolyeD('atolye.cyto1', 0.10),
        atolyeD('atolye.cyto2', 0.20),
        atolyeD('atolye.cyto3', 0.35),
        atolyeD('atolye.cellExpansionCyto', 7.0)
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
        stage.setTitle('Modül 5 - CD68 / Sitoplazmik İHK kantifikasyonu')
        stage.setAlwaysOnTop(true)

        def title = new javafx.scene.control.Label('CD68 / Sitoplazmik İHK kantifikasyonu')
        title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
        def info = new javafx.scene.control.Label(
            'Bir dikdörtgen anotasyon (R) çizip SEÇİN (kenarı sarı), sonra "Çalıştır".\n' +
            'Eşikleri değiştirip yeniden çalıştırabilirsiniz; sonuç aşağıda güncellenir.')
        info.setWrapText(true)

        def spCyto1 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.cyto1', 0.10), 0.01)
        def spCyto2 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.cyto2', 0.20), 0.01)
        def spCyto3 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.cyto3', 0.35), 0.01)
        def spExp   = new javafx.scene.control.Spinner(0.0, 20.0, atolyeD('atolye.cellExpansionCyto', 7.0), 0.5)
        [spCyto1, spCyto2, spCyto3, spExp].each { it.setEditable(true); it.setPrefWidth(110) }
        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(6); grid.setPadding(new javafx.geometry.Insets(6))
        grid.addRow(0, new javafx.scene.control.Label('1+ eşiği (zayıf, DAB OD)'), spCyto1)
        grid.addRow(1, new javafx.scene.control.Label('2+ eşiği (orta, DAB OD)'), spCyto2)
        grid.addRow(2, new javafx.scene.control.Label('3+ eşiği (güçlü, DAB OD)'), spCyto3)
        grid.addRow(3, new javafx.scene.control.Label('Hücre genişletme (µm)'), spExp)
        def adv = new javafx.scene.control.TitledPane('⚙ Gelişmiş ayarlar — eşikler', grid)
        adv.setExpanded(false); adv.setAnimated(false)

        def status = new javafx.scene.control.Label('Hazır.')
        def progress = new javafx.scene.control.ProgressBar()
        progress.setMaxWidth(Double.MAX_VALUE); progress.setVisible(false); progress.setManaged(false)
        def resultArea = new javafx.scene.control.TextArea()
        resultArea.setEditable(false); resultArea.setWrapText(false); resultArea.setPrefRowCount(12)
        resultArea.setPromptText('Sonuçlar burada görünecek…')
        resultArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;")

        def runBtn = new javafx.scene.control.Button('Çalıştır'); runBtn.setDefaultButton(true)
        runBtn.setOnAction({
            runBtn.setDisable(true)
            status.setStyle(''); status.setText('Hücreler tespit ediliyor…')
            progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
            double c1  = spCyto1.getValue() as double
            double c2  = spCyto2.getValue() as double
            double c3  = spCyto3.getValue() as double
            double exp = spExp.getValue() as double
            def worker = new Thread({
                def res = runDetection(c1, c2, c3, exp)
                javafx.application.Platform.runLater {
                    progress.setVisible(false); progress.setManaged(false); runBtn.setDisable(false)
                    if (res.ok) {
                        status.setStyle(''); status.setText('Tamamlandı ✅ — eşikleri değiştirip tekrar çalıştırabilirsiniz.')
                        resultArea.setText(res.text)
                    } else {
                        status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + res.error)
                    }
                }
            }, 'Modul5Detect')
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
        Dialogs.showErrorMessage('Modül 5 açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
