/**
 * Yardımcı - KongNet H&E Mitoz Tespiti (içe aktarma)
 * --------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   KongNet (KongNet_Inference_Main, MIDOG) ile H&E slaytında bulunan mitoz
 *   merkezlerini içeren bir GeoJSON dosyasını QuPath'e aktarır; her mitozu
 *   "Mitosis" sınıflı bir nokta-tespiti (point detection) olarak oluşturur ve
 *   seçili anotasyon(lar) için mitoz sayısı + yoğunluğu üretir:
 *   mitoz/mm² ve mitoz/2 mm² (WHO alan-tabanlı birim).
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
 *   FeatureCollection (her özellik bir Point) olarak dışa aktarın. Bağlam ve
 *   kurulum: kaynaklar.qmd § 5.4 (KongNet satırı).
 *
 * KULLANIM:
 *   1. H&E slaytını açın; piksel boyutu (µm/px) kalibre olsun
 *      (Extensions → Atölye → Yardımcılar → Kalibrasyon).
 *   2. İlgili bölge(ler)i anotasyon olarak çizin ve SEÇİN (seçim yoksa
 *      slayttaki tüm anotasyonlar kullanılır).
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

import qupath.lib.gui.dialogs.Dialogs
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
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 760, 580))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
double whoAreaMm2       = 2.0        // WHO alan-tabanlı raporlama birimi (mitoz / 2 mm²)
String mitosisClassName = "Mitosis"  // KongNet GeoJSON sınıf adı
String summaryName      = "KongNet Mitoz Özet"

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir H&E slaytı açın.")
    return
}

def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok",
        "Slaytta piksel boyutu (µm) tanımlı değil; mitoz yoğunluğu (mitoz/mm²) hesaplanamaz." +
        "\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu).")
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

// ── 3) GeoJSON'u ayrıştır → mitoz noktaları (WSI taban piksel koordinatı) ──
def points = []   // her eleman: [x, y]
try {
    def reader = new java.io.InputStreamReader(
        new java.io.FileInputStream(geojsonFile), java.nio.charset.StandardCharsets.UTF_8)
    try {
        def root = JsonParser.parseReader(reader).getAsJsonObject()
        if (!root.has("features")) {
            Dialogs.showErrorMessage("Geçersiz GeoJSON",
                "Dosyada 'features' dizisi yok (FeatureCollection bekleniyor).")
            return
        }
        root.getAsJsonArray("features").each { fe ->
            def feat = fe.getAsJsonObject()
            if (!feat.has("geometry") || feat.get("geometry").isJsonNull()) return
            def geom = feat.getAsJsonObject("geometry")
            def gtype = geom.has("type") ? geom.get("type").getAsString() : ""
            if (gtype != "Point") return
            def coords = geom.getAsJsonArray("coordinates")
            if (coords == null || coords.size() < 2) return
            points << [coords.get(0).getAsDouble(), coords.get(1).getAsDouble()]
        }
    } finally {
        reader.close()
    }
} catch (Throwable t) {
    Dialogs.showErrorMessage("GeoJSON okunamadı",
        "Dosya ayrıştırılamadı:\n\n" + (t.getMessage() ?: t.getClass().getSimpleName()))
    return
}

if (points.isEmpty()) {
    Dialogs.showWarningNotification("Mitoz yok",
        "GeoJSON dosyasında 'Point' tipinde özellik bulunamadı.")
    return
}

// ── 4) Nokta-tespitleri oluştur (önceki Mitosis noktalarını temizle) ──
def plane = ImagePlane.getDefaultPlane()
def mitosisClass = QP.getPathClass(mitosisClassName)
QP.removeObjects(QP.getDetectionObjects().findAll { it.getPathClass() == mitosisClass }, false)
def detections = points.collect { p ->
    PathObjects.createDetectionObject(
        ROIs.createPointsROI(p[0] as double, p[1] as double, plane), mitosisClass)
}
QP.addObjects(detections)

// ── 5) Hedef anotasyonlar + sayım/yoğunluk ──────────────────────────
def selected = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getName() != summaryName }
def targets = selected.isEmpty() ? QP.getAnnotationObjects().findAll { it.getName() != summaryName } : selected

def areaMm2 = { ann ->
    def roi = ann.getROI()
    roi != null ? (roi.getArea() * pw * ph) / 1_000_000.0 : 0.0
}

int totalMitosis = 0
double totalAreaMm2 = 0.0

if (targets.isEmpty()) {
    totalMitosis = points.size()   // alan tanımlı değil → yalnızca toplam sayı
} else {
    targets.each { ann ->
        def roi = ann.getROI()
        int n = points.count { p -> roi != null && roi.contains(p[0] as double, p[1] as double) } as int
        double aMm2 = areaMm2(ann)
        totalMitosis += n
        totalAreaMm2 += aMm2
        double dens = aMm2 > 0 ? n / aMm2 : 0.0
        ann.measurements['Mitoz (KongNet)'] = n as double
        ann.measurements['Mitoz yoğunluğu (mitoz/mm2)'] = dens
    }
}

double overallDensity = totalAreaMm2 > 0 ? totalMitosis / totalAreaMm2 : 0.0
double mitosesPerWhoArea = overallDensity * whoAreaMm2

// ── 6) Kilitli özet anotasyonu ──────────────────────────────────────
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == summaryName }, false)
def srv = imageData.getServer()
def summary = PathObjects.createAnnotationObject(
    ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(), ImagePlane.getDefaultPlane()))
summary.setName(summaryName)
summary.measurements['Ölçülen alan (mm2)']              = totalAreaMm2
summary.measurements['Mitoz (KongNet)']                 = totalMitosis as double
summary.measurements['Mitoz yoğunluğu (mitoz/mm2)']     = overallDensity
summary.measurements['Mitoz / 2 mm2 (WHO alan birimi)'] = mitosesPerWhoArea
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

// ── 7) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "KONGNET H&E MİTOZ TESPİTİ\n"
body << "═════════════════════════\n\n"
if (totalAreaMm2 > 0)
    body << String.format(java.util.Locale.US, "Ölçülen alan        : %.3f mm²%n", totalAreaMm2)
else
    body << "Ölçülen alan        : (anotasyon yok — yalnızca toplam sayı)\n"
body << String.format(java.util.Locale.US, "Mitoz (KongNet)     : %,d%n", totalMitosis)
if (totalAreaMm2 > 0) {
    body << String.format(java.util.Locale.US, "Mitoz yoğunluğu     : %.1f mitoz/mm²%n", overallDensity)
    body << String.format(java.util.Locale.US, "Mitoz / %.0f mm² (WHO): %.1f%n", whoAreaMm2, mitosesPerWhoArea)
}
body << "\n"
body << "Bu bir SAYIM/YOĞUNLUKtur — mitoz derecesi, grade eşiği veya yorum DEĞİL.\n"
body << "KongNet bir derin öğrenme tahminidir; mitoz adaylarını görsel olarak doğrulayın.\n"
body << "Alan kayması / genelleme sınırları için bkz. Ek W.\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("KongNet H&E mitoz tespiti", body.toString())
println "✓ KongNet mitoz içe aktarımı tamamlandı (${totalMitosis} mitoz)."
