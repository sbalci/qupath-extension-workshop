/**
 * Modül 4 - Tek Tıkla HER2 / Membran İHK Skorlaması
 * ---------------------------------------------------
 * Atölye için "hızlı deneme" betiği. Seçilen anotasyon içinde HER2 (veya
 * E-kadherin, β-katenin gibi başka bir membran İHK boyamasını) skorlar.
 * Her hücre **0 / 1+ / 2+ / 3+** olarak sınıflanır ve membran DAB
 * yoğunluk dağılımı ölçülür.
 *
 * KULLANIM:
 *   1. HER2 İHK slaytını açın
 *   2. Image type → "Brightfield (H-DAB)" olduğundan emin olun
 *      (betik açık değilse otomatik ayarlar — varsayılan boya vektörleri uygulanır)
 *   3. [R] tuşu → tümör içeren ~1×1 mm dikdörtgen anotasyon çizin
 *   4. Anotasyon seçili iken → [Automate → Project scripts → bu betik]
 *
 * MEMBRAN-ODAKLI HÜCRE TESPİTİ:
 *   • İlk tercih: **Cellpose** (`qupath-extension-cellpose`, BIOP) — `cyto3`
 *     modeli DAB + Hematoxylin kanallarından hücre sınırlarını çıkarır.
 *     `cellExpansion(5.0)` ile membran halkası oluşur; "Membrane: DAB OD mean"
 *     ölçümü bu halka boyunca hesaplanır.
 *   • Yedek: `WatershedCellMembraneDetection` (yerleşik, Python gerekmez) —
 *     Cellpose kurulu değilse otomatik kullanılır. Membran konumunu DAB
 *     sinyalinden watershed ile çıkarır; `excludeDAB=true` ile DAB-yoğun
 *     bölgeler çekirdek sanılmaz.
 *   • Detektör seçimi çalışma zamanı'da yapılır; karşılama penceresi hangisinin
 *     kullanılacağını gösterir.
 *
 * CELLPOSE KURULUMU (opsiyonel, daha iyi sonuç):
 *   • qupath-extension-cellpose JAR + Python + `cellpose` paketi
 *   • Detaylar: https://atolye.patoloji.dev/kaynaklar.html#ileri-kurulumlar
 *
 * SKORLAMA — iki paralel metrik:
 *   1. **Hücre bazlı H-score**: tespit edilen hücreler `setCellIntensityClassifications`
 *      ile Negative / 1+ / 2+ / 3+ bin'lerine atanır → H = %1+ + 2×%2+ + 3×%3+.
 *      Eşikler: 0.15 / 0.40 / 0.70 OD (Membrane: DAB OD mean).
 *   2. **Piksel bazlı H-score** (Sara McArdle / PLoS One 2021): annotation içindeki
 *      her piksel DAB OD üzerinden Negative / 1+ / 2+ / 3+'a sınıflanır →
 *      H = (Σ alan_bin × ağırlık) × 100 / toplam alan. Hücre tespitinden bağımsız.
 *      Eşikler: 0.10 / 0.30 / 0.60 DAB OD, 0.05 H OD (atölye varsayılanı).
 *      → Slayt-spesifik kalibrasyon için referans noktaları kullanın (bkz. modül qmd).
 *
 * KAYNAKLAR:
 *   • Cellpose builder JavaDoc: biop.github.io/qupath-extension-cellpose
 *   • Watershed plugin JavaDoc: javadoc/docs/qupath/imagej/detect/cells/WatershedCellMembraneDetection
 *   • Piksel H-score betiği (Sara McArdle): github.com/saramcardle/Image-Analysis-Scripts
 *   • Yöntem makalesi: Ram et al., PLoS One 2021 — doi.org/10.1371/journal.pone.0245638
 *   • Workflow referansı (cancer-informatics.org): docs/ai/qupath_05_her2_expression
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.classes.PathClass
import qupath.lib.regions.ImageRegion
import qupath.opencv.ml.pixel.PixelClassifierTools
import qupath.opencv.ml.pixel.PixelClassifiers
import qupath.opencv.ops.ImageOp
import qupath.opencv.ops.ImageOps
import qupath.opencv.tools.OpenCVTools
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.opencv_core.Mat
import groovy.transform.CompileStatic

// ──────────────────────────────────────────────────────────────
// Modal olmayan pencere yardımcıları
//   - waitForConfirm    : modal hissi veren ama QuPath'i kilitlemeyen onay penceresi
//   - showResultWindow  : sonuç penceresi — açık kalır, QuPath kullanılmaya devam edilebilir
//
// İkisi de always-on-top açık başlar; kullanıcı kapatmadan slaytta dolaşabilir,
// parametre değiştirip betiği tekrar çalıştırabilir, sonuçları kopyalayabilir.
// ──────────────────────────────────────────────────────────────
def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

def waitForConfirm = { String windowTitle, String windowBody ->
    if (isHeadless) {
        println "=== ${windowTitle} ===\n${windowBody}\n=================="
        return true
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def confirmed = new java.util.concurrent.atomic.AtomicBoolean(false)

    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle(windowTitle)
            stage.setAlwaysOnTop(true)

            def label = new javafx.scene.control.Label(windowBody)
            label.setWrapText(true)
            label.setStyle("-fx-font-size: 12px; -fx-padding: 8px;")

            def scrollPane = new javafx.scene.control.ScrollPane(label)
            scrollPane.setFitToWidth(true)

            def okBtn = new javafx.scene.control.Button("Çalıştır")
            okBtn.setDefaultButton(true)
            okBtn.setOnAction({
                confirmed.set(true)
                stage.close()
            })

            def cancelBtn = new javafx.scene.control.Button("İptal")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({
                confirmed.set(false)
                stage.close()
            })

            stage.setOnHidden({ latch.countDown() })

            def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut")
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)

            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, cancelBtn, okBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(scrollPane)
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 620, 460))
            stage.show()
        } catch (Throwable t) {
            // FX kurulumu başarısızsa modal'a geri dön
            confirmed.set(qupath.lib.gui.dialogs.Dialogs.showConfirmDialog(windowTitle, windowBody))
            latch.countDown()
        }
    }

    latch.await()
    return confirmed.get()
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
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 760, 580))
            stage.show()
        } catch (Throwable t) {
            // FX başarısızsa modal'a geri dön — kayıp olmasın
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 1) Ön kontroller
// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(
        "Görüntü açık değil",
        "Önce bir HER2 İHK slaytı açın, sonra bu betiği tekrar çalıştırın."
    )
    return
}

def imageTypeName = imageData.getImageType()?.toString() ?: ""
if (!imageTypeName.toLowerCase().contains("brightfield")) {
    Dialogs.showErrorMessage(
        "Yanlış görüntü tipi",
        "Image type 'Brightfield' olmalı. Şu anki: ${imageTypeName}\n\n" +
        "İHK için: Image type → 'Brightfield (H-DAB)' seçin."
    )
    return
}

// "Hematoxylin OD" kanalı yalnızca H-DAB boya vektörleri ayarlanmışsa görünür.
// Image type 'Brightfield (other)' veya boya vektörleri eksikse parametre reddedilir
// ("Unable to set parameter detectionImageBrightfield with value Hematoxylin OD").
// Hematoxylin boyası tanımlı değilse otomatik olarak BRIGHTFIELD_H_DAB'a geç.
def stains = imageData.getColorDeconvolutionStains()
def hasHematoxylin = false
if (stains != null) {
    for (int i = 1; i <= 3; i++) {
        def name = stains.getStain(i)?.getName()?.toLowerCase()
        if (name != null && name.contains("hematoxylin")) { hasHematoxylin = true; break }
    }
}
if (!hasHematoxylin) {
    println "⚠ H-DAB boya vektörleri tanımlı değil → BRIGHTFIELD_H_DAB varsayılanı uygulanıyor."
    QP.setImageType('BRIGHTFIELD_H_DAB')
}

// ──────────────────────────────────────────────────────────────
// 2) Karşılama
// ──────────────────────────────────────────────────────────────
def cellposeHere = false
try {
    Class.forName('qupath.ext.biop.cellpose.Cellpose2D', false, this.class.classLoader)
    cellposeHere = true
} catch (Throwable ignored) { /* not installed */ }

def detectorLine = cellposeHere
    ? "Detektör: Cellpose (cyto3 — DAB + Hematoxylin)."
    : "Detektör: WatershedCellMembraneDetection (yerleşik yedek).\n" +
      "  ℹ Daha iyi sonuç için Cellpose eklentisini kurun:\n" +
      "    https://atolye.patoloji.dev/kaynaklar.html#ileri-kurulumlar"

def devam = waitForConfirm(
    "Modül 4 - HER2 / Membran İHK skorlaması",
    "Bu betik seçili anotasyon içindeki her hücreye 0 / 1+ / 2+ / 3+\n" +
    "skoru atar ve membran DAB yoğunluk dağılımını özetler.\n\n" +
    "${detectorLine}\n\n" +
    "Atölye varsayılan eşikleri (Membrane: DAB OD mean):\n" +
    "  • 1+ (zayıf):       0.15 OD\n" +
    "  • 2+ (orta):        0.40 OD\n" +
    "  • 3+ (güçlü):       0.70 OD\n\n" +
    "Hücre genişletme (cell expansion): 5 µm (membran sinyalinin örnekleneceği halka)\n\n" +
    "Çıktı: her bin için yüzdeler + H-score + yoğunluk dağılımı.\n\n" +
    "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.\n\n" +
    "Hazırsanız OK düğmesine basın."
)
if (!devam) {
    println "Kullanıcı iptal etti."
    return
}

// ──────────────────────────────────────────────────────────────
// 3) Anotasyon kontrolü
// ──────────────────────────────────────────────────────────────
def selected = QP.getSelectedObject()
if (selected == null || !(selected instanceof PathAnnotationObject)) {
    Dialogs.showErrorMessage(
        "Anotasyon seçili değil",
        "Tümör içeren ~1×1 mm bir dikdörtgen anotasyon çizip seçili tutun."
    )
    return
}
def targetAnnotation = selected

// ──────────────────────────────────────────────────────────────
// 4) Membran-aware hücre tespiti — Cellpose öncelikli, Watershed yedek
//    Cellpose (qupath-extension-cellpose, BIOP): cyto3 modeli DAB+Hematoxylin
//      kanallarından hücre sınırlarını öğrenmiş şekilde çıkarır, kalabalık
//      ve membran-bright kesitlerde daha sağlam.
//    Watershed (yerleşik): Cellpose kurulu değilse otomatik geri-düşer.
//    Her iki yoldan sonra `setCellIntensityClassifications("Membrane: DAB OD mean")`
//    ile bin'ler (1+/2+/3+) atanır → metrik tarafı aynı kalır.
// ──────────────────────────────────────────────────────────────
def cellposeAvailable = cellposeHere
if (!cellposeAvailable) {
    println "Cellpose eklentisi bulunamadı → WatershedCellMembraneDetection'a düşülüyor."
}

def detector = cellposeAvailable ? "Cellpose (cyto3, DAB + Hematoxylin)" : "WatershedCellMembraneDetection (DAB-temelli)"
println "─────────────────────────────────────"
println "Modül 4 - HER2 / Membran İHK"
println "─────────────────────────────────────"
println "Membran skorlaması başlatılıyor..."
println "  • Detektör: ${detector}"
println "  • Grup eşikleri (Membrane: DAB OD mean): 0.15 / 0.40 / 0.70"
println "  • Hücre genişletme (cell expansion): 5 µm"

def t0 = System.currentTimeMillis()

QP.selectObjects(targetAnnotation)

if (cellposeAvailable) {
    // İç betiği ayrı bir GroovyShell'de çalıştır: `import qupath.ext.biop.cellpose.Cellpose2D`
    // ifadesi yalnızca eklenti classpath'te ise parse olabildiği için, dış betik Cellpose
    // yüklü olmayan kurulumlarda da çalışsın diye iç bloğa kapatıyoruz.
    def innerScript = '''
        import qupath.ext.biop.cellpose.Cellpose2D
        import qupath.lib.scripting.QP

        def cellpose = Cellpose2D.builder("cyto3")
            .pixelSize(0.5)
            .channels("DAB", "Hematoxylin")
            .diameter(15)
            .cellExpansion(5.0)
            .measureShape()
            .measureIntensity()
            .build()

        cellpose.detectObjects(QP.getCurrentImageData(), QP.getSelectedObjects())
    '''
    new groovy.lang.GroovyShell(this.class.classLoader).evaluate(innerScript)
} else {
    QP.runPlugin(
        'qupath.imagej.detect.cells.WatershedCellMembraneDetection',
        '{' +
            '"detectionImageBrightfield":"Hematoxylin OD",' +
            '"requestedPixelSizeMicrons":0.5,' +
            '"backgroundRadiusMicrons":8.0,' +
            '"medianRadiusMicrons":0.0,' +
            '"sigmaMicrons":1.5,' +
            '"minAreaMicrons":10.0,' +
            '"maxAreaMicrons":1000.0,' +
            '"threshold":0.1,' +
            '"maxBackground":2.0,' +
            '"watershedPostProcess":true,' +
            '"excludeDAB":true,' +
            '"cellExpansionMicrons":5.0,' +
            '"limitExpansionByNucleusSize":false,' +
            '"includeNuclei":true,' +
            '"smoothBoundaries":false,' +
            '"makeMeasurements":true' +
        '}'
    )
}

// Cell-by-cell intensity binning by membrane DAB OD
// Creates classes: "Negative", "1+", "2+", "3+"
QP.setCellIntensityClassifications("Membrane: DAB OD mean", 0.15, 0.40, 0.70)

def cellElapsed = (System.currentTimeMillis() - t0) / 1000.0

// ──────────────────────────────────────────────────────────────
// 4b) Piksel bazlı H-score (Sara McArdle / Ram et al. PLoS One 2021)
//     Hücre tespitinden tamamen bağımsız. Annotation içindeki her pikseli
//     DAB OD'ye göre 0/1+/2+/3+ olarak sınıflar; bir Hematoxylin maskesi ile
//     boyasız boş alanlar dışlanır. Sonuç: alan-ağırlıklı H-score (0–300).
// ──────────────────────────────────────────────────────────────
def pixT0 = System.currentTimeMillis()
def pixDABthresholds = [0.10, 0.30, 0.60] as double[]   // 1+, 2+, 3+ DAB OD
def pixHthreshold = 0.05                                 // Hematoxylin OD — boş alanı maskelemek için
double pixScale = 1.0                                    // downsample (büyük = hızlı, daha az hassas)

def imgData = QP.getCurrentImageData()
def cal = imgData.getServer().getPixelCalibration()
def stains = imgData.getColorDeconvolutionStains()

def op = ImageOps.buildImageDataOp().appendOps(
    ImageOps.Channels.deconvolve(stains),
    ImageOps.Channels.extract(0, 1),   // H=0, DAB=1
    new HScoreThresholdOp()
        .lowThreshold((double) pixDABthresholds[0])
        .mediumThreshold((double) pixDABthresholds[1])
        .highThreshold((double) pixDABthresholds[2])
        .minStainThreshold(pixHthreshold)
)

def pixClassMap = [
    255: PathClass.getInstance("Ignore*"),
      0: PathClass.getInstance("Pixel-Negative"),
      1: PathClass.getInstance("Pixel-1+"),
      2: PathClass.getInstance("Pixel-2+"),
      3: PathClass.getInstance("Pixel-3+")
]

def pixClassifier = PixelClassifiers.createClassifier(op, cal.createScaledInstance(pixScale, pixScale), pixClassMap)
def pixServer = PixelClassifierTools.createPixelClassificationServer(imgData, pixClassifier)
def pixManager = PixelClassifierTools.createMeasurementManager(pixServer)
def pixPrefix = "H-score-px"

// Pre-fetch tiles (parallel) to speed up large annotations
def roi = targetAnnotation.getROI()
def region = ImageRegion.createInstance(roi)
def tiles = pixServer.getTileRequestManager().getAllTileRequests()
    .findAll { t -> t.getRegionRequest().intersects(region) }
tiles.parallelStream().forEach { t -> pixServer.readRegion(t.getRegionRequest()) }

PixelClassifierTools.addMeasurements([targetAnnotation], pixManager, pixPrefix)

def pxArea1 = (targetAnnotation.measurements["${pixPrefix}: Pixel-1+ area µm^2"] ?: 0.0) as double
def pxArea2 = (targetAnnotation.measurements["${pixPrefix}: Pixel-2+ area µm^2"] ?: 0.0) as double
def pxArea3 = (targetAnnotation.measurements["${pixPrefix}: Pixel-3+ area µm^2"] ?: 0.0) as double
def pxAreaNeg = (targetAnnotation.measurements["${pixPrefix}: Pixel-Negative area µm^2"] ?: 0.0) as double
def pxAreaDenom = pxArea1 + pxArea2 + pxArea3 + pxAreaNeg
def pxPct1 = pxAreaDenom > 0 ? 100.0 * pxArea1 / pxAreaDenom : 0.0
def pxPct2 = pxAreaDenom > 0 ? 100.0 * pxArea2 / pxAreaDenom : 0.0
def pxPct3 = pxAreaDenom > 0 ? 100.0 * pxArea3 / pxAreaDenom : 0.0
def pxPctNeg = pxAreaDenom > 0 ? 100.0 * pxAreaNeg / pxAreaDenom : 0.0
def pixelHScore = pxPct1 * 1 + pxPct2 * 2 + pxPct3 * 3
targetAnnotation.measurements['Pixelwise H-score'] = pixelHScore

def pixElapsed = (System.currentTimeMillis() - pixT0) / 1000.0
def elapsed = cellElapsed + pixElapsed

// ──────────────────────────────────────────────────────────────
// 5) Sonuçları topla
// ──────────────────────────────────────────────────────────────
def cells = targetAnnotation.getChildObjects().findAll { it.isDetection() }
def totalCells = cells.size()

def n0 = 0, n1 = 0, n2 = 0, n3 = 0
cells.each { c ->
    def cls = c.getPathClass()?.getName() ?: ""
    if (cls.contains("3+"))      n3++
    else if (cls.contains("2+")) n2++
    else if (cls.contains("1+")) n1++
    else                          n0++
}

def pct = { count -> totalCells > 0 ? 100.0 * count / totalCells : 0.0 }
def pct0 = pct(n0), pct1 = pct(n1), pct2 = pct(n2), pct3 = pct(n3)
def hScore = pct1 + 2.0 * pct2 + 3.0 * pct3

def uyari = ""
if (totalCells < 200) {
    uyari = String.format("\n📝 Not: %,d hücre <200 — küçük örneklem; sonuç istatistiksel olarak güvenilir olmayabilir.", totalCells)
}

// ──────────────────────────────────────────────────────────────
// 7) Sonucu sun — yalnızca sayısal özet
// ──────────────────────────────────────────────────────────────
showResultWindow(
    "Tamamlandı 🧬",
    String.format(
        "HER2 / Membran İHK skorlaması bitti.\n\n" +
        "═══════════════════════════════════════════════════\n" +
        "  A. HÜCRE BAZLI — %s\n" +
        "═══════════════════════════════════════════════════\n" +
        "📊 Hücre dağılımı (n = %,d toplam)\n" +
        "  0  (negatif)        : %,d  (%%%.1f)\n" +
        "  1+ (zayıf)          : %,d  (%%%.1f)\n" +
        "  2+ (orta)           : %,d  (%%%.1f)\n" +
        "  3+ (güçlü)          : %,d  (%%%.1f)\n" +
        "  Toplam pozitif (≥1+): %%%.1f\n\n" +
        "  H-score (hücre)     : %.0f / 300\n" +
        "  Süre                : %.1f sn\n\n" +
        "═══════════════════════════════════════════════════\n" +
        "  B. PİKSEL BAZLI — Ram et al. 2021 / S. McArdle\n" +
        "═══════════════════════════════════════════════════\n" +
        "📊 Alan dağılımı (toplam %,.0f µm², boyasız alanlar maskelendi)\n" +
        "  0  (negatif)        : %%%.1f\n" +
        "  1+ (zayıf)          : %%%.1f\n" +
        "  2+ (orta)           : %%%.1f\n" +
        "  3+ (güçlü)          : %%%.1f\n\n" +
        "  Pixelwise H-score   : %.0f / 300\n" +
        "  Süre (piksel geçişi)  : %.1f sn\n\n" +
        "  Eşikler: DAB [%.2f / %.2f / %.2f] OD,  H mask %.2f OD\n" +
        "%s\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
        detector,
        totalCells,
        n0, pct0, n1, pct1, n2, pct2, n3, pct3, (pct1 + pct2 + pct3),
        hScore, cellElapsed,
        pxAreaDenom,
        pxPctNeg, pxPct1, pxPct2, pxPct3,
        pixelHScore, pixElapsed,
        pixDABthresholds[0], pixDABthresholds[1], pixDABthresholds[2], pixHthreshold,
        uyari
    )
)

println "─────────────────────────────────────"
println "Tamamlandı:"
println "  Hücre (n=${totalCells}) | H-score: ${String.format('%.0f', hScore)}"
println "  0:${n0} (${String.format('%.1f', pct0)}%) | 1+:${n1} (${String.format('%.1f', pct1)}%) | 2+:${n2} (${String.format('%.1f', pct2)}%) | 3+:${n3} (${String.format('%.1f', pct3)}%)"
println "  Piksel H-score: ${String.format('%.0f', pixelHScore)}"
println "    Neg:${String.format('%.1f', pxPctNeg)}% | 1+:${String.format('%.1f', pxPct1)}% | 2+:${String.format('%.1f', pxPct2)}% | 3+:${String.format('%.1f', pxPct3)}%"
println "─────────────────────────────────────"

// ──────────────────────────────────────────────────────────────
// Piksel H-score için custom ImageOp (Sara McArdle uyarlaması)
// ──────────────────────────────────────────────────────────────
class HScoreThresholdOp implements ImageOp {

    double minThreshold = Double.NEGATIVE_INFINITY
    double t1 = 0.1
    double t2 = 0.2
    double t3 = 0.3

    HScoreThresholdOp minStainThreshold(double minThreshold) {
        this.minThreshold = minThreshold
        return this
    }

    HScoreThresholdOp lowThreshold(double t1) {
        this.t1 = t1
        return this
    }

    HScoreThresholdOp mediumThreshold(double t2) {
        this.t2 = t2
        return this
    }

    HScoreThresholdOp highThreshold(double t3) {
        this.t3 = t3
        return this
    }

    Mat apply(Mat input) {
        def split = OpenCVTools.splitChannels(input)
        def matDAB = split[1]
        OpenCVTools.apply(matDAB, this.&applyDABThresholds)
        def matH = split[0]
        OpenCVTools.apply(matH, this.&applyHematoxylinThreshold)
        // Hem H hem DAB sıfırsa pikseli "Ignore" (255) olarak işaretle
        def matMask = opencv_core.equals(
            opencv_core.max(matH, matDAB).asMat(),
            0.0
        ).asMat()
        matDAB.setTo(OpenCVTools.scalarMat(255.0, matDAB.type()), matMask)
        return matDAB
    }

    @CompileStatic
    double applyHematoxylinThreshold(double value) {
        return value < minThreshold ? 0 : 1
    }

    @CompileStatic
    double applyDABThresholds(double value) {
        if (value < t1) return 0
        if (value < t2) return 1
        if (value < t3) return 2
        return 3
    }
}
