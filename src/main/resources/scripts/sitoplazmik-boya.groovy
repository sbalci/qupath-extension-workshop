/**
 * Sitoplazmik Boya - Tek Tıkla CD68 / Sitoplazmik İHK Kantifikasyonu
 * ----------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Atölye için "hızlı deneme" betiği. Sitoplazmik DAB boyamasını (CD68,
 * CD163 ve sitokeratinler gibi belirteçleri) ölçer. Her hücre sitoplazmik DAB yoğunluğuna göre
 * 0 (negatif) / 1+ (zayıf) / 2+ (orta) / 3+ (güçlü) sınıflarına atanır.
 *
 * KULLANIM:
 *   1. CD68 (veya benzeri sitoplazmik DAB) İHK slaytını açın
 *   2. Image type → "Brightfield (H-DAB)"
 *   3. [R] tuşu → ~1×1 mm dikdörtgen anotasyon çizin ve SEÇİN
 *   4. Bu betiği çalıştırın → açılan TEK pencerede "Çalıştır"
 *   5. Pencere açık kalır: eşikleri değiştirip "Yeniden say (hızlı)" ile
 *      hücre tespitini TEKRAR ÇALIŞTIRMADAN saniyeler içinde yeniden sınıflandırın
 *
 * NEDEN SİTOPLAZMA FARKLI?
 *   • Sitoplazma çekirdekten **çok daha büyük hacme** sahip
 *   • Aynı miktarda DAB → daha düşük ortalama OD
 *   • Bu yüzden EŞİKLER DAHA DÜŞÜK olmalı:
 *       - Nükleer Ki-67: 0.20 / 0.40 / 0.60
 *       - Sitoplazmik CD68: 0.10 / 0.20 / 0.35  ← bu betik
 *   • Hücre genişletme (cell expansion) DAHA BÜYÜK (7 µm) — sitoplazma hacmini örneklemek için
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
        "Önce bir CD68 (veya başka sitoplazmik İHK) slaytı açın."
    )
    return
}

// Image type kontrolü (Brightfield (H-DAB) zorunlu — DAB ayrımı için).
// Modal pencere yerine: pencere her zaman açılır; tip yanlışsa kullanıcı
// pencere içinde tek tıkla "Brightfield (H-DAB)" olarak ayarlayabilir.
def imageTypeOk = { ->
    def n = (imageData.getImageType()?.toString() ?: "")
        .toUpperCase(java.util.Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
    n.contains('H_DAB')
}
def imageTypeGuidance = { ->
    "Bu slayt 'Brightfield (H-DAB)' olarak ayarlı değil.\n" +
    "Şu anki tip: ${imageData.getImageType()?.toString() ?: ''}\n\n" +
    "Çözüm:\n" +
    "  • Penceredeki \"Görüntü tipini 'Brightfield (H-DAB)' olarak ayarla\" düğmesine tıklayın, VEYA\n" +
    "  1. Image panelini açın (sol-üst)\n" +
    "  2. 'Image type' → 'Brightfield (H-DAB)' seçin (DAB ayrımı için gerekli)\n" +
    "  3. Yukarıdaki 'Çalıştır' düğmesine tekrar basın"
}

// H-DAB ise ve boya vektörleri eksikse atölye varsayılanını uygula.
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
// Boya vektörü önerisi + tek tıklamalı tahmin (tüm DAB-OD modüllerinde aynı blok).
//   • Aktif vektörler QuPath VARSAYILANI ise uyarır: kantitatif ölçümden önce bu
//     slayttan tahmin etmeyi önerir (eşikler mutlak DAB OD; yanlış vektör skoru kaydırır).
//   • "Tamam" → SEÇİLİ alan anotasyonundan tahmin edip uygular (Boya vektörleri
//     sihirbazı ile aynı yöntem). "İptal" → yine de devam. Kalibre görünüyorsa sessiz geçer.
// ──────────────────────────────────────────────────────────────
double SV_TOL = 0.02d
double[] SV_DEF_HEMA = [0.651d, 0.701d, 0.290d] as double[]
double[] SV_DEF_DAB  = [0.269d, 0.568d, 0.776d] as double[]
try {
    def svEnum = Class.forName('qupath.lib.color.ColorDeconvolutionStains$DefaultColorDeconvolutionStains')
    def svDab = Class.forName('qupath.lib.color.ColorDeconvolutionStains')
                     .getMethod('makeDefaultColorDeconvolutionStains', svEnum)
                     .invoke(null, Enum.valueOf(svEnum, 'H_DAB'))
    SV_DEF_HEMA = [svDab.getStain(1).getRed(), svDab.getStain(1).getGreen(), svDab.getStain(1).getBlue()] as double[]
    SV_DEF_DAB  = [svDab.getStain(2).getRed(), svDab.getStain(2).getGreen(), svDab.getStain(2).getBlue()] as double[]
} catch (Throwable svIgnore) { /* tablo değerleri kullanılır */ }
def svVecOf = { s -> [s.getRed() as double, s.getGreen() as double, s.getBlue() as double] as double[] }
def svClose = { double[] a, double[] b ->
    Math.abs(a[0] - b[0]) <= SV_TOL && Math.abs(a[1] - b[1]) <= SV_TOL && Math.abs(a[2] - b[2]) <= SV_TOL
}
def suggestStainVectors = { ->
    def svId = QP.getCurrentImageData()
    if (svId == null) return
    def svStains = svId.getColorDeconvolutionStains()
    if (svStains == null) return
    def svS2 = (svStains.getStain(2)?.getName() ?: '').toLowerCase(java.util.Locale.ROOT)
    if (!svS2.contains('dab')) return
    boolean svDefault = svClose(svVecOf(svStains.getStain(1)), SV_DEF_HEMA) &&
                        svClose(svVecOf(svStains.getStain(2)), SV_DEF_DAB)
    if (!svDefault) { println '✓ Boya vektörleri bu slayttan tahmin/kalibre edilmiş görünüyor.'; return }
    def svMsg =
        '⚠ Boya vektörleri QuPath VARSAYILANI görünüyor — bu slayttan tahmin EDİLMEMİŞ.\n\n' +
        'Eşikler mutlak DAB OD üzerinden çalışır; doğru vektör olmadan skorlar kayar.\n' +
        'Tarayıcı/boyama partisi başına bir kez tahmin yeterlidir.\n\n' +
        'Şimdi SEÇİLİ alandan tahmin edilsin mi?\n' +
        '(İki boya + biraz arka plan içeren temsilî bir alan anotasyonu seçili olmalı.)\n\n' +
        '[Tamam] = tahmin et ve uygula      [İptal] = yine de devam et'
    if (isHeadless) { println svMsg; return }
    if (!qupath.fx.dialogs.Dialogs.showConfirmDialog('Boya vektörleri — öneri', svMsg)) return
    def svSel = QP.getSelectedObjects().findAll { it.hasROI() && it.getROI().isArea() }
    if (svSel.isEmpty()) {
        qupath.fx.dialogs.Dialogs.showWarningNotification('Boya vektörleri',
            'Önce iki boya + biraz arka plan içeren temsilî bir alan anotasyonu çizip SEÇİN, sonra tekrar deneyin.')
        return
    }
    try {
        def svRoi = svSel[0].getROI()
        def svServer = svId.getServer()
        double svPix = (svRoi.getBoundsWidth() as double) * (svRoi.getBoundsHeight() as double)
        double svDown = Math.max(1.0d, Math.sqrt(svPix / 16_000_000d))
        def svImg = svServer.readRegion(
            qupath.lib.regions.RegionRequest.createInstance(svServer.getPath(), svDown, svRoi))
        def svOld = svId.getColorDeconvolutionStains()
        def svNew = qupath.lib.analysis.algorithms.EstimateStainVectors.estimateStains(
            svImg, svOld, 0.05d, 1.0d, 1.0d, true)
        svId.setColorDeconvolutionStains(svNew.changeName('Bölgeden tahmin (Atölye)'))
        javafx.application.Platform.runLater {
            try { qupath.lib.gui.QuPathGUI.getInstance()?.getViewer()?.repaintEntireImage() } catch (Throwable svR) { }
        }
        qupath.fx.dialogs.Dialogs.showMessageDialog('Boya vektörleri',
            'Vektörler seçili bölgeden tahmin edilip uygulandı. Modülü şimdi çalıştırabilirsiniz.')
    } catch (Throwable svErr) {
        qupath.fx.dialogs.Dialogs.showMessageDialog('Boya vektörleri',
            'Tahmin yapılamadı: ' + (svErr.getMessage() ?: svErr.getClass().getSimpleName()))
    }
}
if (QP.getSelectedObject() instanceof PathAnnotationObject && QP.getSelectedObject().getROI()?.isArea()) {
    suggestStainVectors()
}

// ──────────────────────────────────────────────────────────────
// 2) Tespit + sonuç toplama tek yerde — pencere kapanmadan tekrar
//    (eşikleri değiştirip) çağrılabilir. Dönüş: [ok, text] | [ok:false, error].
// ──────────────────────────────────────────────────────────────
def finishCytoMetrics = { targetAnnotation, double cyto1, double cyto2, double cyto3, double cellExpansionCyto, double detectElapsed ->
    long t0 = System.currentTimeMillis()
    def cells = targetAnnotation.getChildObjects().findAll { it.isDetection() }
    QP.setIntensityClassifications(cells, "Cytoplasm: DAB OD mean", cyto1, cyto2, cyto3)
    QP.fireHierarchyUpdate()
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
    int warnGenericCount = atolyeI('atolye.warnGenericCount', 200)
    def uyari = ""
    if (totalCells < warnGenericCount) {
        uyari = String.format(java.util.Locale.US, "\n⚠️ Sadece %,d hücre — istatistiksel olarak güvenilmez (≥%d önerilir).", totalCells, warnGenericCount)
    }
    double elapsed = detectElapsed + (System.currentTimeMillis() - t0) / 1000.0
    def text = String.format(java.util.Locale.US,
        "CD68 / Sitoplazmik İHK bitti.\n\n" +
        "Parametreler : 1+=%.2f  2+=%.2f  3+=%.2f  genişletme=%.1f µm\n" +
        "────────────────────────────────────\n" +
        "📊 Hücre dağılımı (n = %,d toplam)\n" +
        "────────────────────────────────────\n" +
        "  0  (negatif)        : %,d  (%%%.1f)\n" +
        "  1+ (zayıf)          : %,d  (%%%.1f)\n" +
        "  2+ (orta)           : %,d  (%%%.1f)\n" +
        "  3+ (güçlü)          : %,d  (%%%.1f)\n\n" +
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

def runDetection = { double cyto1, double cyto2, double cyto3, double cellExpansionCyto ->
    if (!(cyto1 < cyto2 && cyto2 < cyto3))
        return [ok:false, error:'Eşik sırası hatalı: 1+ < 2+ < 3+ olmalıdır.']
    if (!imageTypeOk())
        return [ok:false, error: imageTypeGuidance(), imageTypeIssue:true]
    def selected = QP.getSelectedObject()
    if (selected == null || !(selected instanceof PathAnnotationObject))
        return [ok:false, error:'Önce ölçmek istediğiniz alan anotasyonunu seçin (üst araç çubuğundan Dikdörtgen [R], Çokgen [P] veya Fırça [B] ile çizip üzerine tıklayın; seçili anotasyonun kenarı SARI görünür).']
    def targetAnnotation = selected
    double pixelSize       = atolyeD('atolye.pixelSize', 0.5)
    double backgroundRadius = atolyeD('atolye.backgroundRadius', 8.0)
    double medianRadius    = atolyeD('atolye.medianRadius', 0.0)
    double sigma           = atolyeD('atolye.sigma', 1.5)
    double minArea         = atolyeD('atolye.minArea', 10.0)
    double maxArea         = atolyeD('atolye.maxArea', 400.0)
    double detectionThreshold = atolyeD('atolye.detectionThreshold', 0.1)
    boolean watershed      = atolyeB('atolye.watershed', true)
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
    double detectElapsed = (System.currentTimeMillis() - t0) / 1000.0
    return finishCytoMetrics(targetAnnotation, cyto1, cyto2, cyto3, cellExpansionCyto, detectElapsed)
}

// Hızlı yeniden say (tespit YOK): yalnızca mevcut hücreleri yeni eşiklerle sınıflandırır.
def recount = { double cyto1, double cyto2, double cyto3, double cellExpansionCyto ->
    if (!(cyto1 < cyto2 && cyto2 < cyto3))
        return [ok:false, error:'Eşik sırası hatalı: 1+ < 2+ < 3+ olmalıdır.']
    if (!imageTypeOk())
        return [ok:false, error: imageTypeGuidance(), imageTypeIssue:true]
    def selected = QP.getSelectedObject()
    if (selected == null || !(selected instanceof PathAnnotationObject))
        return [ok:false, error:'Önce ölçmek istediğiniz alan anotasyonunu seçin (üst araç çubuğundan Dikdörtgen [R], Çokgen [P] veya Fırça [B] ile çizip üzerine tıklayın; seçili anotasyonun kenarı SARI görünür).']
    def existing = selected.getChildObjects().findAll { it.isDetection() }
    if (existing.isEmpty())
        return [ok:false, error:'Bu anotasyonda hücre tespiti yok. Önce "Çalıştır" ile tespit yapın, sonra "Yeniden say (hızlı)" kullanın.']
    return finishCytoMetrics(selected, cyto1, cyto2, cyto3, cellExpansionCyto, 0.0)
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
        def hasDetections = new java.util.concurrent.atomic.AtomicBoolean(false)
        def initSel = QP.getSelectedObject()
        if (initSel instanceof PathAnnotationObject && !initSel.getChildObjects().findAll { it.isDetection() }.isEmpty()) {
            hasDetections.set(true)
        }

        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Sitoplazmik Boya - CD68 / Sitoplazmik İHK kantifikasyonu')
        stage.setAlwaysOnTop(true)

        def title = new javafx.scene.control.Label('CD68 / Sitoplazmik İHK kantifikasyonu')
        title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
        def info = new javafx.scene.control.Label(
            'Bir dikdörtgen anotasyon (R) çizip SEÇİN (kenarı sarı), sonra "Çalıştır".\n' +
            'Eşik değiştirince "Yeniden say (hızlı)" tespiti tekrarlamadan saniyeler içinde günceller.')
        info.setWrapText(true)

        def commitSpinner = { javafx.scene.control.Spinner sp ->
            try {
                def vf = sp.getValueFactory()
                def conv = vf?.getConverter()
                def txt = sp.getEditor()?.getText()?.trim()?.replace(',', '.')
                if (vf != null && conv != null && txt != null && !txt.isEmpty()) {
                    vf.setValue(conv.fromString(txt))
                }
            } catch (Throwable ignore) {
                if (sp.getValueFactory() != null && sp.getEditor() != null)
                    sp.getEditor().setText(String.valueOf(sp.getValueFactory().getValue()))
            }
        }
        def spCyto1 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.cyto1', 0.10), 0.01)
        def spCyto2 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.cyto2', 0.20), 0.01)
        def spCyto3 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.cyto3', 0.35), 0.01)
        def spExp   = new javafx.scene.control.Spinner(0.0, 20.0, atolyeD('atolye.cellExpansionCyto', 7.0), 0.5)
        [spCyto1, spCyto2, spCyto3, spExp].each { sp ->
            sp.setEditable(true); sp.setPrefWidth(110)
            sp.focusedProperty().addListener({ obs, was, now -> if (!now) commitSpinner(sp) } as javafx.beans.value.ChangeListener)
        }
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
        def recountBtn = new javafx.scene.control.Button('Yeniden say (hızlı)'); recountBtn.setDisable(!hasDetections.get())
        def fixTypeBtn = new javafx.scene.control.Button("Görüntü tipini 'Brightfield (H-DAB)' olarak ayarla")
        fixTypeBtn.setVisible(false); fixTypeBtn.setManaged(false)
        fixTypeBtn.setOnAction({
            imageData.setImageType(qupath.lib.images.ImageData.ImageType.BRIGHTFIELD_H_DAB)
            QP.setImageType('BRIGHTFIELD_H_DAB')
            fixTypeBtn.setVisible(false); fixTypeBtn.setManaged(false)
            status.setStyle(''); status.setText("✓ Görüntü tipi 'Brightfield (H-DAB)' olarak ayarlandı. Şimdi bir alan anotasyonu seçip 'Çalıştır'a basın.")
            resultArea.setText('')
        })

        if (!imageTypeOk()) {
            status.setStyle('-fx-text-fill: -qp-script-error-color;')
            status.setText("⚠ Görüntü tipi 'Brightfield (H-DAB)' değil — tek tıkla düzeltebilirsiniz.")
            resultArea.setText(imageTypeGuidance())
            fixTypeBtn.setVisible(true); fixTypeBtn.setManaged(true)
        }

        def handleResult = { res ->
            progress.setVisible(false); progress.setManaged(false)
            runBtn.setDisable(false)
            recountBtn.setDisable(!hasDetections.get())
            if (res.ok) {
                fixTypeBtn.setVisible(false); fixTypeBtn.setManaged(false)
                hasDetections.set(true); recountBtn.setDisable(false)
                status.setStyle(''); status.setText('Tamamlandı ✅ — eşikleri değiştirip "Yeniden say (hızlı)" deneyin.')
                resultArea.setText(res.text)
            } else if (res.imageTypeIssue) {
                status.setStyle('-fx-text-fill: -qp-script-error-color;')
                status.setText("⚠ Görüntü tipi 'Brightfield (H-DAB)' değil — tek tıkla düzeltebilirsiniz.")
                resultArea.setText(res.error)
                fixTypeBtn.setVisible(true); fixTypeBtn.setManaged(true)
            } else {
                status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + res.error)
            }
        }

        runBtn.setOnAction({
            [spCyto1, spCyto2, spCyto3, spExp].each { commitSpinner(it) }
            runBtn.setDisable(true); recountBtn.setDisable(true)
            status.setStyle(''); status.setText('Hücreler tespit ediliyor…')
            progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
            double c1  = spCyto1.getValue() as double
            double c2  = spCyto2.getValue() as double
            double c3  = spCyto3.getValue() as double
            double exp = spExp.getValue() as double
            def worker = new Thread({
                def res = runDetection(c1, c2, c3, exp)
                javafx.application.Platform.runLater { handleResult(res) }
            }, 'Modul5Detect')
            worker.setDaemon(true); worker.start()
        })

        recountBtn.setOnAction({
            [spCyto1, spCyto2, spCyto3, spExp].each { commitSpinner(it) }
            runBtn.setDisable(true); recountBtn.setDisable(true)
            status.setStyle(''); status.setText('Yeniden sayılıyor… (tespit yok, yalnızca sınıflandırma)')
            progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
            double c1  = spCyto1.getValue() as double
            double c2  = spCyto2.getValue() as double
            double c3  = spCyto3.getValue() as double
            double exp = spExp.getValue() as double
            def worker = new Thread({
                def res = recount(c1, c2, c3, exp)
                javafx.application.Platform.runLater { handleResult(res) }
            }, 'Modul5Recount')
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

        def footer = new javafx.scene.control.Label('QuPath Atölye Betikleri · araştırma/eğitim amaçlı')
        footer.setMaxWidth(Double.MAX_VALUE)
        footer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;')

        def spacer = new javafx.scene.layout.Region()
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
        def btnRow = new javafx.scene.layout.HBox(8, alwaysTop, spacer, copyBtn, recountBtn, runBtn, closeBtn)
        btnRow.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

        def content = new javafx.scene.layout.VBox(10, title, info, adv, status, fixTypeBtn, progress, resultArea)
        content.setPadding(new javafx.geometry.Insets(14))
        javafx.scene.layout.VBox.setVgrow(resultArea, javafx.scene.layout.Priority.ALWAYS)
        def bottom = new javafx.scene.layout.VBox(8, footer, btnRow)
        bottom.setPadding(new javafx.geometry.Insets(10))
        def root = new javafx.scene.layout.BorderPane()
        root.setCenter(content); root.setBottom(bottom)
        stage.setScene(new javafx.scene.Scene(root, 560, 540))
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sitoplazmik boya modülü açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
