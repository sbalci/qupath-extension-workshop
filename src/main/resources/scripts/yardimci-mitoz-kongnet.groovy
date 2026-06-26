/**
 * Yardımcı - KongNet H&E Mitoz Tespiti (içe aktarma)
 * --------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   KongNet (KongNet_Inference_Main, MIDOG) ile H&E slaytında bulunan mitoz
 *   merkezlerini içeren bir GeoJSON dosyasını QuPath'e aktarır; her mitozu
 *   "Mitosis" sınıflı bir nokta-tespiti (point detection) olarak oluşturur ve
 *   açıkça seçilmiş anotasyon(lar) için mitoz sayısı + yoğunluğu üretir:
 *   mitoz/mm² ve yalnız tam 2 mm² ROI için gözlenen mitoz sayısı.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Tanımlı bir alandaki KongNet-tespitli mitoz NOKTALARININ sayım ve
 *     YOĞUNLUĞUNU ölçer. Bu bir SAYIM/YOĞUNLUKtur — mitoz derecesi, grade
 *     eşiği veya klinik yorum DEĞİL.
 *   • KongNet doğrudan H&E üzerinde çalışır (Ek V'deki PHH3-İHK'nın aksine
 *     immün boya gerekmez). Çıktı bir derin öğrenme tahminidir; alan kayması /
 *     genelleme sınırları için bkz. Ek W. Görsel doğrulama gerekir.
 *
 * GeoJSON ÜRETİMİ (önkoşul, QuPath dışında):
 *   KongNet'i resmi depo (Jiaqi-Lv/KongNet_Inference_Main) ile çalıştırın:
 *   inference_MIDOG.py → SQLite çıktı; ardından output_to_qupath.py ile mitoz
 *   merkezlerini WSI taban (level-0) piksel koordinatlarında GeoJSON
 *   FeatureCollection (her özellik bir Point) olarak dışa aktarın. Bağlam,
 *   akış ve sınırlar: Ek V — PHH3 mitoz § KongNet
 *   (atolye.patoloji.dev/ekler/V-mitoz-phh3.html#kongnet).
 *
 * KULLANIM:
 *   1. H&E slaytını açın; piksel boyutu (µm/px) kalibre olsun
 *      (Extensions → Atölye → Yardımcılar → Kalibrasyon).
 *   2. İlgili bölge(ler)i anotasyon olarak çizin ve açıkça SEÇİN.
 *   3. Bu betiği çalıştırın; KongNet GeoJSON dosyasını seçin.
 *
 * ÇIKTI:
 *   • Her mitoz için "Mitosis" sınıflı nokta-tespiti
 *   • Her anotasyona: "Mitoz (KongNet)" + "Mitoz yoğunluğu (mitoz/mm2)"
 *   • Kilitli "KongNet Mitoz Özet" anotasyonu (Modül 9 ile dışa aktarılır)
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Lv J ve ark. (2025) KongNet — arXiv:2510.23559; MIDOG 2025 mitoz tespiti
 *     birincisi (KongNet-Det). Resmi çıkarım deposu + output_to_qupath.py:
 *     github.com/Jiaqi-Lv/KongNet_Inference_Main. Kod BSD-3; ağırlıklar
 *     CC BY-NC-SA (ticari-dışı).
 *   • Alan-tabanlı mitoz birimi (mitoz/2 mm²): bkz. Ek V (PHH3) ve oradaki
 *     Dessauvagie 2015 referansı.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane
import com.google.gson.JsonParser

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

            stage.setScene(new javafx.scene.Scene(root, 760, 580))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
double whoAreaMm2       = 2.0        // WHO alan-tabanlı raporlama birimi (mitoz / 2 mm²)
String mitosisClassName = "Mitosis"  // KongNet GeoJSON sınıf adı
String summaryName      = "KongNet Mitoz Özet"

// ── 1) Ön kontroller ve açık analiz sınırı ─────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir H&E slaytı açın.")
    return
}

def imageTypeName = imageData.getImageType()?.toString() ?: ''
def normalizedImageType = imageTypeName.toUpperCase(java.util.Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
if (!normalizedImageType.contains('BRIGHTFIELD_H_E')) {
    Dialogs.showErrorMessage("Yanlış görüntü tipi", "KongNet GeoJSON'u Brightfield (H&E) görüntüyle eşleşmelidir. Şu anki: ${imageTypeName}")
    return
}

def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok", "Piksel boyutu tanımlı değil; mitoz/mm² hesaplanamaz.")
    return
}

def targets = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI()?.isArea() && it.getName() != summaryName
}
if (targets.isEmpty()) {
    Dialogs.showErrorMessage("Anotasyon seçili değil", "KongNet ölçümü için analiz anotasyonlarını açıkça seçin.")
    return
}
def analysisROI = qupath.lib.roi.RoiTools.union(targets.collect { it.getROI() })
if (analysisROI == null || analysisROI.isEmpty() || !analysisROI.isArea()) {
    Dialogs.showErrorMessage("Geçersiz analiz alanı", "Seçili anotasyonlar geçerli bir birleşik ROI oluşturmuyor.")
    return
}

// ── 2) KongNet GeoJSON dosyasını seç ────────────────────────────────
if (isHeadless) {
    println "GeoJSON dosya seçimi için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    return
}
def geojsonFile = Dialogs.promptForFile("KongNet mitoz GeoJSON dosyasını seçin", null, "KongNet GeoJSON", "geojson", "json")
if (geojsonFile == null) {
    println "İptal edildi — dosya seçilmedi."
    return
}

// ── 3) GeoJSON'u ayrıştır ──────────────────────────────────────────
def points = []
try {
    def reader = new java.io.InputStreamReader(
        new java.io.FileInputStream(geojsonFile), java.nio.charset.StandardCharsets.UTF_8)
    try {
        def root = JsonParser.parseReader(reader).getAsJsonObject()
        if (!root.has("features")) {
            Dialogs.showErrorMessage("Geçersiz GeoJSON", "Dosyada 'features' dizisi yok (FeatureCollection bekleniyor).")
            return
        }
        root.getAsJsonArray("features").each { featureElement ->
            def feature = featureElement.getAsJsonObject()
            if (!feature.has("geometry") || feature.get("geometry").isJsonNull()) return
            def geometry = feature.getAsJsonObject("geometry")
            if (!geometry.has("type") || geometry.get("type").getAsString() != "Point") return
            def coordinates = geometry.getAsJsonArray("coordinates")
            if (coordinates == null || coordinates.size() < 2) return
            double x = coordinates.get(0).getAsDouble()
            double y = coordinates.get(1).getAsDouble()
            if (Double.isFinite(x) && Double.isFinite(y)) points << [x, y]
        }
    } finally {
        reader.close()
    }
} catch (Throwable t) {
    Dialogs.showErrorMessage("GeoJSON okunamadı", "Dosya ayrıştırılamadı:\n\n" + (t.getMessage() ?: t.getClass().getSimpleName()))
    return
}
if (points.isEmpty()) {
    Dialogs.showWarningNotification("Mitoz yok", "GeoJSON dosyasında geçerli Point özelliği bulunamadı.")
    return
}

def pointsInROI = points.findAll { point -> analysisROI.contains(point[0] as double, point[1] as double) }

// ── 4) Yalnız seçili birleşik ROI içindeki KongNet noktalarını ekle ─
String generatedName = 'Generated by KongNet import'
QP.removeObjects(QP.getDetectionObjects().findAll { it.getName() == generatedName }, false)
def plane = analysisROI.getImagePlane()
def mitosisClass = QP.getPathClass(mitosisClassName)
def detections = pointsInROI.collect { point ->
    def detection = PathObjects.createDetectionObject(
        ROIs.createPointsROI(point[0] as double, point[1] as double, plane), mitosisClass)
    detection.setName(generatedName)
    detection
}
if (!detections.isEmpty()) QP.addObjects(detections)

// ── 5) Kaynak ROI ve birleşik sonuç ────────────────────────────────
def areaMm2 = { roi -> roi.getArea() * pw * ph / 1_000_000.0 }
targets.each { target ->
    def roi = target.getROI()
    int count = pointsInROI.count { point -> roi.contains(point[0] as double, point[1] as double) } as int
    double area = areaMm2(roi)
    target.measurements['ROI alanı (mm2)'] = area
    target.measurements['Mitoz (KongNet)'] = count as double
    target.measurements['Mitoz yoğunluğu (mitoz/mm2)'] = area > 0 ? count / area : Double.NaN
}

int totalMitosis = pointsInROI.size()
double totalAreaMm2 = areaMm2(analysisROI)
double overallDensity = totalAreaMm2 > 0 ? totalMitosis / totalAreaMm2 : Double.NaN
double areaToleranceMm2 = 0.01
double observedPerWhoArea = Math.abs(totalAreaMm2 - whoAreaMm2) <= areaToleranceMm2 ? totalMitosis as double : Double.NaN

QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == summaryName }, false)
def summary = PathObjects.createAnnotationObject(analysisROI)
summary.setName(summaryName)
summary.measurements['Kaynak ROI sayısı'] = targets.size() as double
summary.measurements['GeoJSON nokta sayısı'] = points.size() as double
summary.measurements['ROI içi mitoz (KongNet)'] = totalMitosis as double
summary.measurements['Ölçülen alan (mm2)'] = totalAreaMm2
summary.measurements['Mitoz yoğunluğu (mitoz/mm2)'] = overallDensity
summary.measurements['Mitoz / 2 mm2 (yalnız 2.00 ± 0.01 mm2 ROI)'] = observedPerWhoArea
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

def fmt = { double value, String pattern ->
    Double.isFinite(value) ? String.format(java.util.Locale.US, pattern, value) : 'hesaplanamadı'
}
def body = new StringBuilder()
body << "KONGNET H&E MİTOZ TESPİTİ\n"
body << "═════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Seçili ROI           : %,d%n", targets.size())
body << String.format(java.util.Locale.US, "GeoJSON noktası      : %,d%n", points.size())
body << String.format(java.util.Locale.US, "ROI içi mitoz        : %,d%n", totalMitosis)
body << String.format(java.util.Locale.US, "Birleşik alan        : %.3f mm²%n", totalAreaMm2)
body << "Mitoz yoğunluğu      : ${fmt(overallDensity, '%.1f mitoz/mm²')}\n"
if (Double.isFinite(observedPerWhoArea))
    body << String.format(java.util.Locale.US, "Mitoz / %.0f mm² (gözlenen): %.0f%n", whoAreaMm2, observedPerWhoArea)
else
    body << String.format(java.util.Locale.US, "Mitoz / %.0f mm²: hesaplanmadı (alan %.3f mm²; tolerans ±%.2f)%n", whoAreaMm2, totalAreaMm2, areaToleranceMm2)
body << "\nBirleşik özet örtüşen ROI alanlarını ve noktaları yalnız bir kez sayar.\n"
body << "Yalnız seçili birleşik ROI içindeki GeoJSON noktaları QuPath'e eklenir.\n"
body << "KongNet çıktısı görsel olarak doğrulanmalıdır; klinik kategori veya yorum üretilmez.\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("KongNet H&E mitoz tespiti", body.toString())
println "✓ KongNet mitoz içe aktarımı tamamlandı (${totalMitosis} ROI-içi mitoz)."