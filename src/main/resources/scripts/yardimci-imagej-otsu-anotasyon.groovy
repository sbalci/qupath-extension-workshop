/**
 * Yardımcı - ImageJ ile Otomatik Eşik → Anotasyon (Otsu)
 * -------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Açık görüntüyü (ya da seçili ALAN anotasyonunun sınırlayıcı kutusunu)
 *   düşük çözünürlükte ister, hafifçe yumuşatır, ImageJ'nin Otsu otomatik
 *   eşiğini uygular ve eşiklenen pikselleri tam çözünürlükte bir QuPath
 *   anotasyonuna çevirir. Kaba bir "doku / arka plan" maskesi ya da bir
 *   bölge taslağı çıkarmak için hızlı bir yoldur.
 *
 *   QuPath ⇄ ImageJ köprüsünün küçük bir gösterimidir: eşik DÜŞÜK
 *   çözünürlükte hesaplanır, ama ROI tam çözünürlükte yeniden ölçeklenip
 *   görüntülenir — yani tüm-slayt görüntülerinde de çalışır.
 *
 * NE ÜRETİR (ve ne ÜRETMEZ):
 *   • Yalnız bir alan anotasyonu + (kalibre ise) alanı (mm²) üretir.
 *     Hücre tespiti, sınıflandırma, skor veya yorum YAPMAZ.
 *   • Otsu basit/küresel bir eşiktir; sonucu görsel olarak doğrulayın ve
 *     gerekiyorsa elle düzeltin (Ekler → Ek D: Manuel Düzeltme).
 *
 * KULLANIM:
 *   1. Bir görüntü açın. (İsteğe bağlı) yalnız bir bölgeyi eşiklemek için bir
 *      ALAN anotasyonu çizip SEÇİN; seçmezseniz tüm görüntü kullanılır.
 *   2. [Extensions → Atölye → İleri analiz → ImageJ ile otomatik eşik → anotasyon]
 *      (ya da [Automate → Project scripts → bu betik]).
 *
 * KAYNAK / İLHAM:
 *   • Bankhead P, I2K 2024 "QuPath for Fiji Fans" — "Apply an automated threshold"
 *     (github.com/qupath/i2k2024, workshops/fiji-fans). Bu betik onun uyarlamasıdır.
 *   • ImageJ AutoThresholder yöntemleri (Otsu, Default, Triangle…):
 *     imagej.net/ij/developer/api/ij/ij/process/AutoThresholder.Method.html
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import ij.process.AutoThresholder
import ij.process.ColorProcessor
import qupath.imagej.processing.SimpleThresholding
import qupath.imagej.tools.IJTools
import qupath.fx.dialogs.Dialogs
import qupath.lib.images.ImageData
import qupath.lib.objects.PathObjects
import qupath.lib.regions.RegionRequest
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── Sonuç penceresi (atölye ortak yardımcısı; ekstansiyon/Project scripts'ten çalışır) ──
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

            stage.setScene(new javafx.scene.Scene(root, 740, 520))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── 1) Ön kontroller ───────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    def msg = "Önce bir görüntü açın."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Görüntü açık değil", msg)
    return
}
def server = imageData.getServer()

// ── 2) Bölge: seçili alan anotasyonunun sınırlayıcı kutusu, yoksa tüm görüntü ──
def sel = QP.getSelectedObject()
boolean useROI = (sel != null && sel.hasROI() && sel.getROI().isArea())
int rx, ry, rw, rh
if (useROI) {
    def b = sel.getROI()
    rx = (int) Math.floor(b.getBoundsX())
    ry = (int) Math.floor(b.getBoundsY())
    rw = (int) Math.ceil(b.getBoundsWidth())
    rh = (int) Math.ceil(b.getBoundsHeight())
} else {
    rx = 0; ry = 0; rw = server.getWidth(); rh = server.getHeight()
}

// Downsample: bölgenin eni/boyu 1024 px'i geçmeyecek şekilde küçült.
double downsample = Math.max(1.0, Math.max(rw, rh) / 1024.0)
def request = RegionRequest.createInstance(server.getPath(), downsample, rx, ry, rw, rh)

// ── 3) ImageJ'ye geç: gri tona indir → yumuşat → Otsu otomatik eşik ─────────
def method = AutoThresholder.Method.Otsu
def imp = IJTools.convertToImagePlus(server, request).getImage()
def ip = imp.getProcessor()
if (ip instanceof ColorProcessor)
    ip = ip.convertToByteProcessor()
ip.blurGaussian(2.0)
// Floresan = koyu arka plan; parlak alan (H&E/DAB) = açık arka plan.
boolean darkBackground = imageData.getImageType() == ImageData.ImageType.FLUORESCENCE
ip.setAutoThreshold(method, darkBackground)

// ── 4) Eşiklenen pikselleri tam çözünürlüklü QuPath ROI'sine çevir ──────────
def roi = SimpleThresholding.thresholdToROI(ip, request)
if (roi == null) {
    def msg = "Otsu eşiği bu bölgede hiç piksel seçmedi.\n" +
              "Farklı bir bölge deneyin ya da görüntü tipini doğrulayın\n" +
              "(Atölye → Yardımcılar → Görüntü tipi ayarla)."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Eşik boş", msg)
    return
}

def annotation = PathObjects.createAnnotationObject(roi)
annotation.setPathClass(QP.getPathClass("ImageJ Otsu"))
imageData.getHierarchy().addObject(annotation)
QP.fireHierarchyUpdate()

// ── 5) Özet ─────────────────────────────────────────────────────────────────
def cal = server.getPixelCalibration()
double pw = cal != null ? cal.getPixelWidthMicrons() : Double.NaN
double ph = cal != null ? cal.getPixelHeightMicrons() : Double.NaN
boolean hasMicrons = (cal != null && cal.hasPixelSizeMicrons() && pw > 0 && ph > 0)

def body = new StringBuilder()
body << "IMAGEJ — OTOMATİK EŞİK → ANOTASYON\n"
body << "═══════════════════════════════════════\n\n"
body << "Yöntem         : Otsu (ImageJ AutoThresholder)\n"
body << "Bölge          : " << (useROI ? "seçili anotasyonun sınırlayıcı kutusu" : "tüm görüntü") << "\n"
body << String.format(java.util.Locale.US, "Downsample     : %.2f×%n", downsample)
body << String.format(java.util.Locale.US, "Arka plan      : %s%n", (darkBackground ? "koyu (floresan)" : "açık (parlak alan)"))
body << "\n"
if (hasMicrons) {
    double areaMm2 = roi.getArea() * pw * ph / 1_000_000.0
    body << String.format(java.util.Locale.US, "Anotasyon alanı: %.4f mm²%n", areaMm2)
} else {
    body << "Anotasyon alanı: (piksel kalibrasyonu yok — mm² hesaplanamadı)\n"
}
body << "Sınıf          : ImageJ Otsu\n"
body << "\n"
body << "Eşik DÜŞÜK çözünürlükte hesaplandı, ROI tam çözünürlükte oluşturuldu.\n"
body << "Sonucu görsel olarak doğrulayın; gerekiyorsa elle düzeltin (Ek D).\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("ImageJ otomatik eşik", body.toString())
println "✓ ImageJ Otsu anotasyonu oluşturuldu (sınıf: ImageJ Otsu)."
