/**
 * Yardımcı - Doku Tespiti Sihirbazı (native)
 * -------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Python veya ImageJ gerektirmeden, tamamen QuPath içinde (native) bir slaytın
 *   doku bölgesini düşük çözünürlüklü piksele eşikleyerek tespit eder ve kilitli
 *   "Doku" sınıflı bir anotasyon üretir.
 *   Yöntem:
 *     1. Kullanıcı seçilen eşik tabanı (OD toplamı / Hematoksilen / Arka plan parlaklığı)
 *        ve downsample ile düşük çözünürlüklü bir bölge okunur.
 *     2. Her piksel için skaler hesaplanır (renk ayırma veya parlaklık).
 *     3. Histogram üzerinde Otsu eşikleme uygulanır.
 *     4. İkili maske kontür izlemesiyle (ContourTracing) ROI oluşturulur.
 *     5. Minimum alandan küçük parçalar atılır.
 *     6. Kilitli "Doku" sınıflı anotasyon eklenir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Tespit edilen doku alanını (mm²) ve tüm görüntüye oranını (%) ölçer.
 *   • Klinik yorum, eşik, grade veya patoloji tanısı üretmez.
 *   • DAB OD eşiği veya klinik kesim noktası eklemez.
 *
 * KULLANIM:
 *   1. Kalibre (µm/px) bir slayt açın.
 *   2. [Extensions → Atölye → Yardımcılar → Doku Tespiti (native)]
 *   3. Eşik tabanını, downsample'ı ve minimum alan (mm²) değerini girin.
 *
 * ÇIKTI:
 *   • Kilitli, "Doku" sınıflı kütlesel anotasyon (idempotent — yeniden çalıştırmada temizlenir).
 *   • Tespit edilen doku alanı (mm²) ve doku fraksiyonu (%) — sonuç penceresinde.
 *
 * YÖNTEM / KAYNAK:
 *   • Renk ayırma: imageData.getColorDeconvolutionStains() — QuPath 0.6.0+.
 *   • ContourTracing.createTracedROI(Raster, min, max, band, RegionRequest) — QuPath 0.6.0+.
 *   • Otsu eşikleme: Otsu N (1979), IEEE Trans SMC — histogram-tabanlı varyans minimizasyonu.
 *   • Bankhead P et al. (2017), Sci Rep. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.regions.RegionRequest
import qupath.lib.analysis.images.ContourTracing

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

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

            stage.setScene(new javafx.scene.Scene(root, 720, 520))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Sabitler ────────────────────────────────────────────────────────
String SENTINEL_NAME = "Doku"
String SENTINEL_CLASS = "Doku"
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/doku-tespiti-sihirbaz')

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    if (isHeadless) { println "Görüntü açık değil."; return }
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce analiz edilecek slaydı açın.")
    return
}
def server = imageData.getServer()
def cal = server.getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
boolean calibrated = (pw > 0 && ph > 0 && !Double.isNaN(pw) && !Double.isNaN(ph))

// ── 2) Kullanıcı seçimleri ───────────────────────────────────────────
List<String> choices = ["OD toplamı (OD sum)", "Hematoksilen", "Arka plan (parlaklık)"]
String defChoice = prefs.get('choice', choices[0])
if (!choices.contains(defChoice)) defChoice = choices[0]

String choice
if (isHeadless) {
    choice = prefs.get('choice', choices[0])
    if (!choices.contains(choice)) choice = choices[0]
} else {
    choice = Dialogs.showChoiceDialog("Doku tespiti — eşik tabanı",
        "Pikseli doku/arka plan olarak ayırt etmek için hangi sinyal kullanılsın?",
        choices, defChoice)
    if (choice == null) { println "İptal edildi."; return }
}

String defDs = prefs.get('downsample', '32')
String defMin = prefs.get('minAreaMm2', '1.0')

double downsample = 32.0d
double minAreaMm2 = 1.0d

if (!isHeadless) {
    def dsStr = Dialogs.showInputDialog("Doku tespiti — downsample",
        "Düşük çözünürlük için downsample (önerilen: 16–64):", defDs)
    if (dsStr == null) { println "İptal edildi."; return }
    try { downsample = Double.parseDouble(dsStr.trim().replace(',', '.')) }
    catch (NumberFormatException ignored) {
        Dialogs.showErrorMessage("Sayı formatı", "Downsample ondalık bir sayı olmalı (ör. 32).")
        return
    }
    if (!(downsample > 0)) downsample = 32.0d

    def minStr = Dialogs.showInputDialog("Doku tespiti — minimum alan",
        "Atılacak minimum doku parçası (mm²):", defMin)
    if (minStr == null) { println "İptal edildi."; return }
    try { minAreaMm2 = Double.parseDouble(minStr.trim().replace(',', '.')) }
    catch (NumberFormatException ignored) { minAreaMm2 = 1.0d }
    if (!(minAreaMm2 >= 0)) minAreaMm2 = 1.0d
} else {
    try { downsample = Double.parseDouble(defDs.replace(',', '.')) } catch (Throwable ig) { downsample = 32.0d }
    try { minAreaMm2 = Double.parseDouble(defMin.replace(',', '.')) } catch (Throwable ig) { minAreaMm2 = 1.0d }
}

prefs.put('choice', choice)
prefs.put('downsample', String.format(java.util.Locale.US, "%.1f", downsample))
prefs.put('minAreaMm2', String.format(java.util.Locale.US, "%.3f", minAreaMm2))
try { prefs.flush() } catch (Throwable ig) {}

// ── 3) Düşük çözünürlüklü bölgeyi oku ─────────────────────────────
println String.format(java.util.Locale.US,
    "Doku tespiti başlıyor: eşik='%s', downsample=%.0f, minAlan=%.2f mm²", choice, downsample, minAreaMm2)

def request = RegionRequest.createInstance(server.getPath(), downsample,
    0, 0, server.getWidth(), server.getHeight())
java.awt.image.BufferedImage img = server.readRegion(request)
def raster = img.getRaster()
int W = raster.getWidth()
int H = raster.getHeight()

// ── 4) Skaler harita hesapla (float[][]) ────────────────────────────
// Renk ayırma için stain bilgisini al (varsa)
def stains = imageData.getColorDeconvolutionStains()
float[] scalar = new float[W * H]

if (choice == "OD toplamı (OD sum)") {
    // OD toplamı: her pikselin R/G/B'sini OD'ye çevir ve topla
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            int r = raster.getSample(x, y, 0) & 0xFF
            int g = raster.getSample(x, y, 1) & 0xFF
            int b = raster.getSample(x, y, 2) & 0xFF
            double odR = -Math.log((r + 1.0) / 256.0) / Math.log(10.0)
            double odG = -Math.log((g + 1.0) / 256.0) / Math.log(10.0)
            double odB = -Math.log((b + 1.0) / 256.0) / Math.log(10.0)
            scalar[y * W + x] = (float) (odR + odG + odB)
        }
    }
} else if (choice == "Hematoksilen") {
    // Hematoksilen kanalı renk ayırma ile (varsa); yoksa parlaklık proxy
    if (stains != null) {
        def s1 = stains.getStain(1)
        double[] v1 = [s1.getRed(), s1.getGreen(), s1.getBlue()]
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int r = raster.getSample(x, y, 0) & 0xFF
                int g = raster.getSample(x, y, 1) & 0xFF
                int b = raster.getSample(x, y, 2) & 0xFF
                double odR = -Math.log((r + 1.0) / 256.0) / Math.log(10.0)
                double odG = -Math.log((g + 1.0) / 256.0) / Math.log(10.0)
                double odB = -Math.log((b + 1.0) / 256.0) / Math.log(10.0)
                double hem = odR * v1[0] + odG * v1[1] + odB * v1[2]
                scalar[y * W + x] = (float) Math.max(0.0, hem)
            }
        }
    } else {
        // Renk ayırma yok: OD sum ile aynı yol
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int r = raster.getSample(x, y, 0) & 0xFF
                int g = raster.getSample(x, y, 1) & 0xFF
                int b = raster.getSample(x, y, 2) & 0xFF
                double odR = -Math.log((r + 1.0) / 256.0) / Math.log(10.0)
                double odG = -Math.log((g + 1.0) / 256.0) / Math.log(10.0)
                double odB = -Math.log((b + 1.0) / 256.0) / Math.log(10.0)
                scalar[y * W + x] = (float) (odR + odG + odB)
            }
        }
        println "Uyarı: Renk ayırma bilgisi yok — OD toplamı kullanıldı."
    }
} else {
    // Arka plan parlaklığı: (R+G+B)/3; doku = düşük parlaklık → negatif sinyal al
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            int r = raster.getSample(x, y, 0) & 0xFF
            int g = raster.getSample(x, y, 1) & 0xFF
            int b = raster.getSample(x, y, 2) & 0xFF
            scalar[y * W + x] = (float) (255.0 - (r + g + b) / 3.0)
        }
    }
}

// ── 5) Otsu eşikleme (histogram üzerinden) ──────────────────────────
int BINS = 512
float minVal = scalar[0], maxVal = scalar[0]
for (float v : scalar) { if (v < minVal) minVal = v; if (v > maxVal) maxVal = v }
float range = maxVal - minVal
if (range < 1e-6f) {
    def msg = "Tüm pikseller aynı değerde — görüntü boş veya tek renk olabilir."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Eşikleme başarısız", msg)
    return
}

long[] hist = new long[BINS]
for (float v : scalar) {
    int bin = (int) ((v - minVal) / range * (BINS - 1))
    if (bin < 0) bin = 0; else if (bin >= BINS) bin = BINS - 1
    hist[bin]++
}
long total = (long) scalar.length
long cumSum = 0L
double sum = 0.0
for (int i = 0; i < BINS; i++) sum += i * hist[i]
double sumB = 0.0, maxVar = 0.0
long wB = 0L
int otsuBin = 0
for (int t = 0; t < BINS; t++) {
    wB += hist[t]
    if (wB == 0L) continue
    long wF = total - wB
    if (wF == 0L) break
    sumB += t * hist[t]
    double mB = sumB / wB
    double mF = (sum - sumB) / wF
    double var = wB * wF * (mB - mF) * (mB - mF)
    if (var > maxVar) { maxVar = var; otsuBin = t }
}
double threshold = minVal + (otsuBin.toDouble() / (BINS - 1)) * range
println String.format(java.util.Locale.US,
    "Otsu eşiği: %.4f  (bin=%d / %d, sinyal aralığı [%.4f, %.4f])", threshold, otsuBin, BINS, minVal, maxVal)

// ── 6) İkili maske oluştur (DataBuffer üzerinden WritableRaster) ─────
def maskImg = new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
def maskRaster = maskImg.getRaster()
for (int y = 0; y < H; y++) {
    for (int x = 0; x < W; x++) {
        maskRaster.setSample(x, y, 0, scalar[y * W + x] >= threshold ? 255 : 0)
    }
}

// ── 7) ContourTracing → ROI ─────────────────────────────────────────
// Signature (from maske-iceaktar): createTracedROI(Raster, min, max, band, RegionRequest)
def tracedRoi = ContourTracing.createTracedROI(maskRaster, 1.0d, 255.0d, 0, request)
if (tracedRoi == null || tracedRoi.isEmpty()) {
    def msg = "Eşikleme sonrası ön plan bölgesi bulunamadı. Eşik tabanı veya downsample değerini değiştirin."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Doku bulunamadı", msg)
    return
}

// ── 8) Küçük parçaları at ───────────────────────────────────────────
double minAreaPx2 = (calibrated && minAreaMm2 > 0)
    ? (minAreaMm2 * 1_000_000.0d / (pw * ph))   // mm² → µm² → px²
    : 0.0d
// Parçalara ayır ve filtrele
def pieces
try { pieces = qupath.lib.roi.RoiTools.splitROI(tracedRoi) }
catch (Throwable t) { pieces = [tracedRoi] }
def kept = pieces.findAll { it != null && !it.isEmpty() && it.getArea() >= minAreaPx2 }
if (kept.isEmpty()) {
    def msg = "Tüm bölgeler minimum alan (${String.format(java.util.Locale.US, '%.2f', minAreaMm2)} mm²) altında kaldı."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Doku bulunamadı", msg)
    return
}
// Birden fazla parça varsa geometrilerini birleştir (tek kütlesel anotasyon)
def finalRoi
if (kept.size() == 1) {
    finalRoi = kept[0]
} else {
    def union = kept[0].getGeometry()
    for (int i = 1; i < kept.size(); i++) union = union.union(kept[i].getGeometry())
    finalRoi = qupath.lib.roi.GeometryTools.geometryToROI(union,
        qupath.lib.regions.ImagePlane.getDefaultPlane())
}

// ── 9) Önceki tespiti temizle ve yeni anotasyonu ekle (idempotent) ──
QP.removeObjects(QP.getAnnotationObjects().findAll {
    it.getPathClass()?.toString() == SENTINEL_CLASS && it.getName() == SENTINEL_NAME
}, false)
def dokAnnotation = PathObjects.createAnnotationObject(finalRoi, QP.getPathClass(SENTINEL_CLASS))
dokAnnotation.setName(SENTINEL_NAME)
dokAnnotation.setLocked(true)
QP.addObjects([dokAnnotation])
QP.fireHierarchyUpdate()

// ── 10) Özet hesapla ────────────────────────────────────────────────
double tissueAreaPx2  = finalRoi.getArea()
double slideAreaPx2   = (double) server.getWidth() * server.getHeight()
double tissueFraction = (slideAreaPx2 > 0) ? 100.0 * tissueAreaPx2 / slideAreaPx2 : 0.0
double tissueAreaMm2  = calibrated ? (tissueAreaPx2 * pw * ph / 1_000_000.0d) : Double.NaN
double slideAreaMm2   = calibrated ? (slideAreaPx2   * pw * ph / 1_000_000.0d) : Double.NaN

def body = new StringBuilder()
body << "DOKU TESPİTİ (native)\n"
body << "═══════════════════════════════════════════\n\n"
body << "Eşik tabanı       : ${choice}\n"
body << String.format(java.util.Locale.US, "Downsample        : %.0f%n", downsample)
body << String.format(java.util.Locale.US, "Otsu eşiği        : %.4f%n", threshold)
body << String.format(java.util.Locale.US, "Minimum alan      : %.2f mm²%n", minAreaMm2)
body << String.format(java.util.Locale.US, "Parça sayısı      : %d → birleştirildi%n", kept.size())
body << "\n"
if (calibrated) {
    body << String.format(java.util.Locale.US, "Doku alanı        : %.2f mm²%n", tissueAreaMm2)
    body << String.format(java.util.Locale.US, "Slayt alanı       : %.2f mm²%n", slideAreaMm2)
} else {
    body << String.format(java.util.Locale.US, "Doku alanı        : %,.0f px²  (görüntü kalibre değil)%n", tissueAreaPx2)
}
body << String.format(java.util.Locale.US, "Doku fraksiyonu   : %.1f %%%n", tissueFraction)
body << "\n"
body << "Kilitli 'Doku' sınıflı anotasyon eklendi.\n"
body << "Doku maskesini ayarlamak için downsample veya eşik tabanını değiştirerek yeniden çalıştırın.\n"
body << "Modül 6 piksel sınıflandırıcısı ile daha hassas segmentasyon yapılabilir.\n\n"
body << "Bu bir ALAN ölçümüdür — klinik yorum, grade veya patoloji tanısı üretilmez.\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Doku Tespiti", body.toString())
println String.format(java.util.Locale.US,
    "✓ Doku tespiti tamamlandı: doku fraksiyonu %%%.1f%s.",
    tissueFraction,
    calibrated ? String.format(java.util.Locale.US, " (%.2f mm²)", tissueAreaMm2) : "")
