/**
 * Modül 4 - Tek Tıkla HER2 / Membran İHK Skorlaması
 * ---------------------------------------------------
 * Atölye için "hızlı deneme" betiği. Seçilen anotasyon içinde HER2 (veya
 * E-kadherin, β-katenin gibi başka bir membran İHK boyamasını) skorlar.
 *
 * BİRİNCİL METRİK — PİKSEL BAZLI H-SCORE (Cellpose gerektirmez):
 *   Seçili (tümör) anotasyon içindeki her piksel DAB OD üzerinden
 *   Negative / 1+ / 2+ / 3+ olarak sınıflanır → alan-ağırlıklı H-score.
 *   Hücre tespitinden bağımsız, her zaman çalışır.
 *   Eşikler: 0.10 / 0.30 / 0.60 OD (atölye varsayılanı), 0.05 H OD maskesi.
 *   → Slayt-spesifik kalibrasyon için referans noktaları kullanın.
 *
 * İKİNCİL METRİK — HÜCRE BAZLI (karşılaştırma, opsiyonel):
 *   Cellpose ya da WatershedCellMembraneDetection ile hücreler tespit edilir,
 *   "Membrane: DAB OD mean" ölçümüyle 0/1+/2+/3+ olarak sınıflanır.
 *   Cellpose / Python yoksa veya hata oluşursa bu adım atlanır — birincil
 *   piksel H-score zaten hesaplanmış olacaktır.
 *   Eşikler: 0.15 / 0.40 / 0.70 OD (Membrane: DAB OD mean).
 *
 * KULLANIM:
 *   1. HER2 İHK slaytını açın
 *   2. Image type → "Brightfield (H-DAB)" olduğundan emin olun
 *   3. [R] tuşu → TÜMÖR (epitel) içeren ~1×1 mm dikdörtgen anotasyon çizin
 *      (stroma dahil büyük ROI seçmek piksel H-score'u seyreltir)
 *   4. Anotasyon seçili iken → [Automate → Project scripts → bu betik]
 *
 * CELLPOSE KURULUMU (opsiyonel — ikincil hücre bazlı metrik için):
 *   • qupath-extension-cellpose JAR + Python + `cellpose` paketi
 *   • Detaylar: https://atolye.patoloji.dev/kaynaklar.html#ileri-kurulumlar
 *
 * KAYNAKLAR:
 *   • Piksel H-score betiği (Sara McArdle): github.com/saramcardle/Image-Analysis-Scripts
 *   • Yöntem makalesi: Ram et al., PLoS One 2021 — doi.org/10.1371/journal.pone.0245638
 *   • Membran bütünlüğü sınırı: Sode et al., Histopathology 2023 — doi.org/10.1111/his.14877
 *   • Cellpose builder JavaDoc: biop.github.io/qupath-extension-cellpose
 *   • Watershed plugin: javadoc/docs/qupath/imagej/detect/cells/WatershedCellMembraneDetection
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
// 2) Atölye parametreleri (WorkshopPrefs'ten veya varsayılan)
// ──────────────────────────────────────────────────────────────
// — Piksel bazlı (birincil) —
def pixDab1       = atolyeD('atolye.pixDab1',            0.10)
def pixDab2       = atolyeD('atolye.pixDab2',            0.30)
def pixDab3       = atolyeD('atolye.pixDab3',            0.60)
def pixHthreshold = atolyeD('atolye.pixHmask',           0.05)
double pixScale   = atolyeD('atolye.pixScale',            1.0)

// — Hücre bazlı (ikincil) —
def cellposeModel      = atolyeS('atolye.cellposeModel',        'cyto3')
def cellposeDiameter   = atolyeI('atolye.cellposeDiameter',     25)
def pixelSize          = atolyeD('atolye.pixelSize',            0.5)
def cellExpansion      = atolyeD('atolye.cellExpansionNuclear', 5.0)
def backgroundRadius   = atolyeD('atolye.backgroundRadius',     8.0)
def sigma              = atolyeD('atolye.sigma',                1.5)
def detectionThreshold = atolyeD('atolye.detectionThreshold',   0.1)
def minArea            = atolyeD('atolye.minArea',              10.0)
def membrane1          = atolyeD('atolye.membrane1',            0.15)
def membrane2          = atolyeD('atolye.membrane2',            0.40)
def membrane3          = atolyeD('atolye.membrane3',            0.70)

// — Genel —
def warnCount = atolyeI('atolye.warnGenericCount', 200)

// ──────────────────────────────────────────────────────────────
// Karşılama — Cellpose algıla (karşılama penceresi için)
// ──────────────────────────────────────────────────────────────
def cellposeHere = false
try {
    Class.forName('qupath.ext.biop.cellpose.Cellpose2D', false, this.class.classLoader)
    cellposeHere = true
} catch (Throwable ignored) { /* not installed */ }

def detectorLine = cellposeHere
    ? "Detektör (ikincil karşılaştırma): Cellpose (cyto3 — DAB + H OD).\n" +
      "  Görüntü H-DAB renk dekonvolüsyonundan geçirilip\n" +
      "  DAB (membran) ve Hematoksilen (çekirdek) kanalları\n" +
      "  Cellpose'a verilir. Çap (diameter) 25 px (~12.5 µm).\n" +
      "  ℹ Cellpose çalıştırma anında başarısız olursa\n" +
      "    WatershedCellMembraneDetection yedeğine otomatik düşülür."
    : "Detektör (ikincil karşılaştırma): WatershedCellMembraneDetection (yerleşik).\n" +
      "  ℹ Daha iyi hücre bazlı sonuç için Cellpose eklentisini kurun:\n" +
      "    https://atolye.patoloji.dev/kaynaklar.html#ileri-kurulumlar"

def devam = waitForConfirm(
    "Modül 4 - HER2 / Membran İHK skorlaması",
    "BİRİNCİL METRİK — Piksel bazlı H-score (Cellpose gerekmez):\n" +
    "  Seçili anotasyon içindeki her piksel DAB OD'ye göre\n" +
    "  Negatif / 1+ / 2+ / 3+ olarak sınıflanır → alan-ağırlıklı H-score.\n" +
    "  Bu metrik her zaman çalışır; hücre tespitinden bağımsızdır.\n\n" +
    "  ➤ LÜTFEN bir TÜMÖR (epitel) bölgesi seçin/çizin.\n" +
    "    Stroma içeren büyük ROI seçmek piksel H-score'u seyreltir.\n\n" +
    "  Piksel eşikleri (DAB OD): " +
    "${pixDab1}${pixDab1 != 0.10 ? ' (değiştirildi)' : ''} / " +
    "${pixDab2}${pixDab2 != 0.30 ? ' (değiştirildi)' : ''} / " +
    "${pixDab3}${pixDab3 != 0.60 ? ' (değiştirildi)' : ''}\n" +
    "  H maskesi (OD): ${pixHthreshold}${pixHthreshold != 0.05 ? ' (değiştirildi)' : ''}\n\n" +
    "İKİNCİL METRİK — Hücre bazlı ring H-score (opsiyonel karşılaştırma):\n" +
    "  Cellpose / Watershed ile hücreler tespit edilir, membran DAB OD mean\n" +
    "  ile 0/1+/2+/3+ olarak sınıflanır. Cellpose yoksa veya hata oluşursa\n" +
    "  bu adım atlanır — birincil piksel skoru zaten hesaplanmıştır.\n\n" +
    "${detectorLine}\n\n" +
    "  Hücre bazlı eşikler (Membrane: DAB OD mean):\n" +
    "  • 1+ (zayıf):  ${membrane1}${membrane1 != 0.15 ? ' (değiştirildi)' : ''} OD\n" +
    "  • 2+ (orta):   ${membrane2}${membrane2 != 0.40 ? ' (değiştirildi)' : ''} OD\n" +
    "  • 3+ (güçlü):  ${membrane3}${membrane3 != 0.70 ? ' (değiştirildi)' : ''} OD\n\n" +
    "Not: Bu betik membran boyamasının GÜCÜNÜ ölçer — membranın kaç hücrede\n" +
    "TAM çevrelendiğini (bütünlük / completeness) ÖLÇMEZ.\n\n" +
    "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.\n\n" +
    "Hazırsanız Çalıştır düğmesine basın."
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
// 4a) Piksel bazlı H-score — BİRİNCİL, her zaman çalışır
//     (Ram et al. PLoS One 2021 / Sara McArdle)
//     Hücre tespitinden tamamen bağımsız. Annotation içindeki her pikseli
//     DAB OD'ye göre 0/1+/2+/3+ olarak sınıflar; bir Hematoxylin maskesi ile
//     boyasız boş alanlar dışlanır. Sonuç: alan-ağırlıklı H-score (0–300).
// ──────────────────────────────────────────────────────────────
println "─────────────────────────────────────"
println "Modül 4 - HER2 / Membran İHK"
println "─────────────────────────────────────"
println "Piksel bazlı H-score hesaplanıyor (birincil)..."

def pixT0 = System.currentTimeMillis()
def pixDABthresholds = [pixDab1, pixDab2, pixDab3] as double[]   // 1+, 2+, 3+ DAB OD

def imgData  = QP.getCurrentImageData()
def cal      = imgData.getServer().getPixelCalibration()
def pixStains = imgData.getColorDeconvolutionStains()

def op = ImageOps.buildImageDataOp().appendOps(
    ImageOps.Channels.deconvolve(pixStains),
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
def pixServer     = PixelClassifierTools.createPixelClassificationServer(imgData, pixClassifier)
def pixManager    = PixelClassifierTools.createMeasurementManager(pixServer)
def pixPrefix     = "H-score-px"

// Pre-fetch tiles (parallel) to speed up large annotations
def roi    = targetAnnotation.getROI()
def region = ImageRegion.createInstance(roi)
def tiles  = pixServer.getTileRequestManager().getAllTileRequests()
    .findAll { t -> t.getRegionRequest().intersects(region) }
tiles.parallelStream().forEach { t -> pixServer.readRegion(t.getRegionRequest()) }

PixelClassifierTools.addMeasurements([targetAnnotation], pixManager, pixPrefix)

def pxArea1   = (targetAnnotation.measurements["${pixPrefix}: Pixel-1+ area µm^2"]       ?: 0.0) as double
def pxArea2   = (targetAnnotation.measurements["${pixPrefix}: Pixel-2+ area µm^2"]       ?: 0.0) as double
def pxArea3   = (targetAnnotation.measurements["${pixPrefix}: Pixel-3+ area µm^2"]       ?: 0.0) as double
def pxAreaNeg = (targetAnnotation.measurements["${pixPrefix}: Pixel-Negative area µm^2"] ?: 0.0) as double
def pxAreaDenom = pxArea1 + pxArea2 + pxArea3 + pxAreaNeg
def pxPct1    = pxAreaDenom > 0 ? 100.0 * pxArea1   / pxAreaDenom : 0.0
def pxPct2    = pxAreaDenom > 0 ? 100.0 * pxArea2   / pxAreaDenom : 0.0
def pxPct3    = pxAreaDenom > 0 ? 100.0 * pxArea3   / pxAreaDenom : 0.0
def pxPctNeg  = pxAreaDenom > 0 ? 100.0 * pxAreaNeg / pxAreaDenom : 0.0
def pixelHScore = pxPct1 * 1 + pxPct2 * 2 + pxPct3 * 3
targetAnnotation.measurements['Pixelwise H-score'] = pixelHScore

def pixElapsed = (System.currentTimeMillis() - pixT0) / 1000.0

// ROI alanı ve büyük-ROI uyarısı
double areaMm2 = targetAnnotation.getROI().getScaledArea(
    cal.getPixelWidthMicrons(), cal.getPixelHeightMicrons()
) / 1e6
double LARGE_ROI_MM2 = 25.0  // sezgisel; kalibre değil
def roiWarnStr = areaMm2 > LARGE_ROI_MM2
    ? "\n⚠ Büyük ROI uyarısı: seçili alan ${String.format('%.1f', areaMm2)} mm²" +
      " (eşik ${String.format('%.0f', LARGE_ROI_MM2)} mm²).\n" +
      "  Stroma içeren büyük alanlar piksel H-score'u seyreltebilir.\n" +
      "  Daha güvenilir sonuç için yalnızca tümör (epitel) bölgesini seçin.\n"
    : ""

println "  Piksel H-score: ${String.format('%.0f', pixelHScore)} / 300  (süre: ${String.format('%.1f', pixElapsed)} sn)"

// ──────────────────────────────────────────────────────────────
// 4b) Hücre bazlı H-score — İKİNCİL, non-blocking
//     Membran-aware hücre tespiti: Cellpose öncelikli, Watershed yedek.
//     Herhangi bir hata → cellBasedOk = false, atlanır. Birincil piksel
//     sonucu etkilenmez.
// ──────────────────────────────────────────────────────────────

// Tüm hücre bazlı değişkenleri try bloğunun DIŞINDA tanımla
// (result window her iki durumda da bunlara erişir)
boolean cellBasedOk = true
String detector = "—"
int totalCells = 0, n0 = 0, n1 = 0, n2 = 0, n3 = 0
double hScore = 0.0
double cellElapsed = 0.0

try {
    def cellposeAvailable = cellposeHere
    if (!cellposeAvailable) {
        println "Cellpose eklentisi bulunamadı → WatershedCellMembraneDetection'a düşülüyor."
    }

    detector = cellposeAvailable ? "Cellpose (cyto3, DAB + H OD)" : "WatershedCellMembraneDetection (DAB-temelli)"
    println "Hücre bazlı tespit başlatılıyor (ikincil)..."
    println "  • Detektör: ${detector}"
    println "  • Grup eşikleri (Membrane: DAB OD mean): ${membrane1} / ${membrane2} / ${membrane3}"
    println "  • Hücre genişletme (cell expansion): ${cellExpansion} µm"

    def t0 = System.currentTimeMillis()

    QP.selectObjects(targetAnnotation)

    // Yardımcı kapatma: Watershed-tabanlı yedek detektör.
    def runWatershedFallback = {
        QP.runPlugin(
            'qupath.imagej.detect.cells.WatershedCellMembraneDetection',
            '{' +
                '"detectionImageBrightfield":"Hematoxylin OD",' +
                "\"requestedPixelSizeMicrons\":${pixelSize}," +
                "\"backgroundRadiusMicrons\":${backgroundRadius}," +
                '"medianRadiusMicrons":0.0,' +
                "\"sigmaMicrons\":${sigma}," +
                "\"minAreaMicrons\":${minArea}," +
                '"maxAreaMicrons":1000.0,' +
                "\"threshold\":${detectionThreshold}," +
                '"maxBackground":2.0,' +
                '"watershedPostProcess":true,' +
                '"excludeDAB":true,' +
                "\"cellExpansionMicrons\":${cellExpansion}," +
                '"limitExpansionByNucleusSize":false,' +
                '"includeNuclei":true,' +
                '"smoothBoundaries":false,' +
                '"makeMeasurements":true' +
            '}'
        )
    }

    if (cellposeAvailable) {
        // İç betiği ayrı bir GroovyShell'de çalıştır: `import qupath.ext.biop.cellpose.Cellpose2D`
        // ifadesi yalnızca eklenti classpath'te ise parse olabildiği için, dış betik Cellpose
        // yüklü olmayan kurulumlarda da çalışsın diye iç bloğa kapatıyoruz.
        //
        // KANAL HAZIRLIĞI (HER2 için kritik):
        //   • Doğrudan RGB → Cellpose modeli "bright nuclei on dark background"
        //     beklediği için brightfield görüntüde zayıf çalışır.
        //   • Çözüm: önce **renk dekonvolüsyonu** uygulayıp DAB ve Hematoxylin
        //     OD kanallarını çıkarıyoruz (boya bölgelerinde yüksek değer alan,
        //     arka planda sıfıra yakın olan kanallar — Cellpose'un sevdiği biçim).
        //   • ImageOps.Channels.extract(1, 0) → sırasıyla DAB (cyto/membran sinyali)
        //     ve Hematoxylin (çekirdek sinyali) verir; Cellpose chan=DAB, chan2=H
        //     olarak işler.
        //
        // PARAMETRELER:
        //   • normalizePercentilesGlobal(0.1, 99.8, 10) — Cellpose normalize edilmiş
        //     input bekler; bu adım atlanırsa hücreler büyük olasılıkla bulunmaz.
        //   • diameter(25) — 0.5 µm/px'de yaklaşık 12.5 µm çaplı epitel hücreleri.
        //     Çok küçük tümör hücreleri için 18-20, büyük apokrin hücreler için 30-35
        //     deneyin. diameter(0) otomatik tahmin yapar (daha yavaş, daha tutarsız).
        //   • cellExpansion(5.0) — çekirdek + sitoplazma sınırından dışarıya 5 µm
        //     halka oluşturur; "Membrane: DAB OD mean" ölçümü bu halkadan alınır.
        //
        // ÇALIŞMA ZAMANI HATA YAKALAMA: Cellpose JAR'ı yüklü olabilir ama Python
        // ortamı ayarlanmamış, model indirilmemiş veya bir tile başarısız olabilir.
        // Bu durumda Watershed yedeğine geçiyoruz, betik kesintisiz tamamlanır.
        def innerScript = """
            import qupath.ext.biop.cellpose.Cellpose2D
            import qupath.lib.scripting.QP
            import qupath.opencv.ops.ImageOps

            def stainVectors = QP.getCurrentImageData().getColorDeconvolutionStains()

            def cellpose = Cellpose2D.builder("${cellposeModel}")
                .pixelSize(${pixelSize})
                .preprocess(
                    ImageOps.Channels.deconvolve(stainVectors),
                    ImageOps.Channels.extract(1, 0)   // 1=DAB (cyto), 0=Hematoxylin (nuclei)
                )
                .cellposeChannels(1, 2)               // Cellpose CLI: --chan 1 (DAB) --chan2 2 (H)
                                                      // .preprocess() çıktısı 2 kanallı TIFF olarak yazılır;
                                                      // bu çağrı olmadan CLI grayscale'e düşer -> 0 hücre.
                .normalizePercentilesGlobal(0.1, 99.8, 10)
                .diameter(${cellposeDiameter})
                .cellExpansion(${cellExpansion})
                .measureShape()
                .measureIntensity()
                .build()

            cellpose.detectObjects(QP.getCurrentImageData(), QP.getSelectedObjects())
        """
        try {
            new groovy.lang.GroovyShell(this.class.classLoader).evaluate(innerScript)
        } catch (Throwable cellposeError) {
            def reason = cellposeError.getMessage() ?: cellposeError.getClass().getSimpleName()
            println "⚠ Cellpose çalıştırılamadı: ${reason}"
            println "→ WatershedCellMembraneDetection yedeğine düşülüyor."
            cellposeAvailable = false
            detector = "WatershedCellMembraneDetection (Cellpose başarısız oldu)"
            // Cellpose kısmi nesneler bırakmış olabilir — temizle.
            def partial = targetAnnotation.getChildObjects().findAll { it.isDetection() }
            if (!partial.isEmpty()) {
                QP.removeObjects(partial, true)
            }
            QP.selectObjects(targetAnnotation)
            runWatershedFallback()
        }
    } else {
        runWatershedFallback()
    }

    // Cell-by-cell intensity binning by membrane DAB OD
    // Creates classes: "Negative", "1+", "2+", "3+"
    QP.setCellIntensityClassifications("Membrane: DAB OD mean", membrane1, membrane2, membrane3)

    cellElapsed = (System.currentTimeMillis() - t0) / 1000.0

    // Hücre sayımı
    def cells = targetAnnotation.getChildObjects().findAll { it.isDetection() }
    totalCells = cells.size()

    cells.each { c ->
        def cls = c.getPathClass()?.getName() ?: ""
        if (cls.contains("3+"))      n3++
        else if (cls.contains("2+")) n2++
        else if (cls.contains("1+")) n1++
        else                          n0++
    }

    def pct = { count -> totalCells > 0 ? 100.0 * count / totalCells : 0.0 }
    hScore = pct(n1) + 2.0 * pct(n2) + 3.0 * pct(n3)

    println "  Hücre bazlı H-score: ${String.format('%.0f', hScore)} / 300  (${totalCells} hücre, ${String.format('%.1f', cellElapsed)} sn)"

} catch (Throwable cellErr) {
    cellBasedOk = false
    def reason = cellErr.getMessage() ?: cellErr.getClass().getSimpleName()
    println "⚠ Hücre bazlı tespit atlandı: ${reason}"
    println "  Birincil piksel H-score sonucu etkilenmedi."
}

// ──────────────────────────────────────────────────────────────
// 5) Sonucu sun — piksel bazlı birincil, hücre bazlı ikincil
// ──────────────────────────────────────────────────────────────

// Per-cell percentages (safe: totalCells=0 when cellBasedOk=false → all 0.0)
def pct0Cell = totalCells > 0 ? 100.0 * n0 / totalCells : 0.0
def pct1Cell = totalCells > 0 ? 100.0 * n1 / totalCells : 0.0
def pct2Cell = totalCells > 0 ? 100.0 * n2 / totalCells : 0.0
def pct3Cell = totalCells > 0 ? 100.0 * n3 / totalCells : 0.0

// Piksel bazlı blok (her zaman) — pure GString, no String.format wrapping the block
def pixBlock =
    "HER2 / Membran İHK skorlaması bitti.\n\n" +
    "═══════════════════════════════════════════════════════════\n" +
    "  PİKSEL-BAZLI H-SCORE  (birincil · segmentasyon-bağımsız)\n" +
    "═══════════════════════════════════════════════════════════\n" +
    "  H-score          : ${String.format('%.0f', pixelHScore)} / 300\n" +
    "  Negatif          : %${String.format('%.1f', pxPctNeg)}\n" +
    "  1+ (zayıf)       : %${String.format('%.1f', pxPct1)}\n" +
    "  2+ (orta)        : %${String.format('%.1f', pxPct2)}\n" +
    "  3+ (güçlü)       : %${String.format('%.1f', pxPct3)}\n" +
    "  Toplam pozitif   : %${String.format('%.1f', (pxPct1 + pxPct2 + pxPct3))}\n\n" +
    "  Eşikler: DAB [${String.format('%.2f', pixDABthresholds[0])} / " +
                    "${String.format('%.2f', pixDABthresholds[1])} / " +
                    "${String.format('%.2f', pixDABthresholds[2])}] OD," +
              "  H mask ${String.format('%.2f', pixHthreshold)} OD\n" +
    "  Alan (boyalı)    : ${String.format('%,.0f', pxAreaDenom)} µm²\n" +
    "  Bölge            : seçili ROI · ${String.format('%.2f', areaMm2)} mm²" +
                        "  (süre: ${String.format('%.1f', pixElapsed)} sn)\n\n"

// Hücre bazlı blok (koşullu) — pure GString
def cellBlock
if (cellBasedOk) {
    def uyari = totalCells < warnCount
        ? "\n  📝 Not: ${String.format('%,d', totalCells)} hücre < ${String.format('%,d', warnCount)}" +
          " — küçük örneklem; istatistiksel güvenilirlik sınırlı."
        : ""
    cellBlock =
        "═══════════════════════════════════════════════════════════\n" +
        "  Hücre bazlı  (karşılaştırma · ring) — ${detector}\n" +
        "═══════════════════════════════════════════════════════════\n" +
        "  H-score          : ${String.format('%.0f', hScore)} / 300\n" +
        "  Toplam hücre     : ${String.format('%,d', totalCells)}\n" +
        "  0  (negatif)     : ${String.format('%,d', n0)}  (%${String.format('%.1f', pct0Cell)})\n" +
        "  1+ (zayıf)       : ${String.format('%,d', n1)}  (%${String.format('%.1f', pct1Cell)})\n" +
        "  2+ (orta)        : ${String.format('%,d', n2)}  (%${String.format('%.1f', pct2Cell)})\n" +
        "  3+ (güçlü)       : ${String.format('%,d', n3)}  (%${String.format('%.1f', pct3Cell)})\n" +
        "  Süre             : ${String.format('%.1f', cellElapsed)} sn${uyari}\n" +
        "  ⓘ İki yöntem farklıysa: ring sitoplazma/eksik membranı yanlış örnekleyebilir.\n\n"
} else {
    cellBlock = "Hücre bazlı karşılaştırma atlandı (Cellpose/Watershed yok ya da hata).\n\n"
}

// Bütünlük notu (her zaman)
def completenessNote =
    "───────────────────────────────────────────────────────────\n" +
    "Not: Bu betik membran boyamasının GÜCÜNÜ ölçer; membranın\n" +
    "kaç hücrede TAM çevrelendiğini (bütünlük) ÖLÇMEZ. (Sode 2023)\n\n"

// Büyük ROI uyarısı (koşullu — boş string ise eklenmez)
def roiWarn = roiWarnStr.isEmpty() ? "" : roiWarnStr + "\n"

// Son uyarı satırı
def finalDisclaimer = "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow(
    "Tamamlandı 🧬",
    pixBlock + cellBlock + completenessNote + roiWarn + finalDisclaimer
)

println "─────────────────────────────────────"
println "Tamamlandı:"
println "  Piksel H-score: ${String.format('%.0f', pixelHScore)} / 300"
println "    Neg:${String.format('%.1f', pxPctNeg)}% | 1+:${String.format('%.1f', pxPct1)}% | 2+:${String.format('%.1f', pxPct2)}% | 3+:${String.format('%.1f', pxPct3)}%"
if (cellBasedOk) {
    println "  Hücre (n=${totalCells}) | H-score: ${String.format('%.0f', hScore)}"
    println "  0:${n0} (${String.format('%.1f', pct0Cell)}%) | 1+:${n1} (${String.format('%.1f', pct1Cell)}%) | 2+:${n2} (${String.format('%.1f', pct2Cell)}%) | 3+:${n3} (${String.format('%.1f', pct3Cell)}%)"
} else {
    println "  Hücre bazlı: atlandı"
}
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
