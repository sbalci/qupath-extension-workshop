/**
 * Yardımcı - AI Tahmin Maskelerini İçe Aktar (GeoJSON)
 * ----------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Harici bir derin öğrenme modelinin (ör. bir U-Net segmentasyon hattının)
 *   ürettiği tahmin poligonlarını GeoJSON FeatureCollection olarak QuPath'e
 *   aktarır; her Polygon / MultiPolygon özelliği, özelliğin "classification"
 *   alanından (yoksa varsayılan "Tumor") sınıflandırılmış, KİLİTLİ bir
 *   anotasyon nesnesi olarak eklenir.
 *
 *   KAVRAM (Bankhead): GeoJSON poligonları nesneleri, raster "etiketli görüntü"ye
 *   (labelled image) alternatif olarak POLİGON/ROI biçiminde temsil eder; QuPath
 *   anotasyonları bu vektör (ROI) gösterimini kullanır.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Model tahminlerini yalnızca QuPath görselleştirme katmanına TAŞIR. Bu bir
 *     VERİ AKTARIM betiğidir — tahmin doğruluğu, patoloji yorumu, grade veya
 *     klinik karar bu betikle üretilmez. Sonuç bir derin öğrenme tahminidir;
 *     genelleme sınırları için bkz. Ek W. Görsel doğrulama gerekir.
 *   • GeoJSON koordinatları WSI taban (level-0) piksel uzayında olmalıdır;
 *     betik koordinat dönüşümü / yeniden ölçekleme yapmaz.
 *   • Önceki içe aktarımdan kalan "ESDIP AI tahmin" adlı anotasyonlar yeniden
 *     içe aktarma öncesinde otomatik silinir.
 *
 * GeoJSON ÜRETİMİ (önkoşul, QuPath dışında):
 *   Bir Python segmentasyon hattı çalıştırın — slaytı karolara bölün, U-Net ile
 *   çıkarım yapın, maskeleri birleştirip poligon GeoJSON'a dönüştürün. Tam akış,
 *   örnek kod ve sınırlar: Ek Z — Derin Öğrenme Eğitimi ve Segmentasyon
 *   (atolye.patoloji.dev/ekler/Z-derin-ogrenme-egitimi.html). Yöntem ilhamı:
 *   ESDIP Academy — Computational Pathology dersi (önerilir).
 *
 * KULLANIM:
 *   1. Tahmin GeoJSON dosyasını hazırlayın (yukarıdaki Python hattı, Ek Z).
 *   2. Karşılık gelen WSI'ı QuPath'te açın.
 *   3. Bu betiği çalıştırın; dosya seçim penceresi açılır, dosyayı seçin.
 *
 * ÇIKTI:
 *   • Sınıflandırılmış (ör. "Tumor"), kilitli Polygon anotasyonları
 *   • İçe aktarılan / atlanan özellik sayısı ve sınıf dökümünü gösteren özet
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Pipeline tasarımı, ESDIP Academy Computational Pathology dersinden ilham
 *     almıştır (esdipath.org/academy — ESDIP üyeliği gerektirir). Buradaki kod
 *     özgün olarak yazılmıştır.
 *   • GeoJSON özellik şeması (içe aktarılan): geometry.type = Polygon |
 *     MultiPolygon; properties.classification = sınıf adı (string ya da
 *     {"name": ...}); properties.isLocked = boolean (varsayılan: kilitli).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane
import qupath.lib.geom.Point2
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
String importSentinelName = 'ESDIP AI tahmin'   // önceki içe aktarımı tanımak için
String defaultClassName   = 'Tumor'             // properties.classification yoksa
boolean lockImported      = true                // tahminleri kilitle (kazara düzenlemeyi önler)
int minVertexCount        = 4                    // dejenere poligonları ele

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce tahminlerin ait olduğu WSI'ı açın.")
    return
}
if (isHeadless) {
    println "GeoJSON dosya seçimi için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    return
}

// ── 2) GeoJSON dosyasını seç ─────────────────────────────────────────
def geojsonFile = Dialogs.promptForFile(
    "AI tahmin GeoJSON dosyasını seçin", null, "AI Tahmin GeoJSON", "geojson", "json")
if (geojsonFile == null) {
    println "İptal edildi — dosya seçilmedi."
    return
}

// Bir poligon halkasını (dış halka) QuPath ROI'sine çevirir
def ringToRoi = { ring, plane ->
    if (ring == null || ring.size() < minVertexCount) return null
    def pts = new ArrayList<Point2>(ring.size())
    boolean valid = true
    for (int i = 0; i < ring.size(); i++) {
        def coord = ring.get(i).getAsJsonArray()
        if (coord.size() < 2) { valid = false; break }
        double x = coord.get(0).getAsDouble()
        double y = coord.get(1).getAsDouble()
        if (!Double.isFinite(x) || !Double.isFinite(y)) { valid = false; break }
        pts.add(new Point2(x, y))
    }
    if (!valid || pts.size() < minVertexCount) return null
    return ROIs.createPolygonROI(pts, plane)
}

// Özelliğin sınıf adını (string ya da {"name": ...}) okur
def classNameOf = { feature ->
    if (!feature.has("properties") || feature.get("properties").isJsonNull()) return defaultClassName
    def props = feature.getAsJsonObject("properties")
    if (!props.has("classification") || props.get("classification").isJsonNull()) return defaultClassName
    def cls = props.get("classification")
    if (cls.isJsonPrimitive()) return cls.getAsString()
    if (cls.isJsonObject() && cls.getAsJsonObject().has("name")) return cls.getAsJsonObject().get("name").getAsString()
    return defaultClassName
}

// Özelliğin kilitli olup olmadığını okur (varsayılan: betik parametresi)
def lockedOf = { feature ->
    if (!feature.has("properties") || feature.get("properties").isJsonNull()) return lockImported
    def props = feature.getAsJsonObject("properties")
    if (props.has("isLocked") && props.get("isLocked").isJsonPrimitive()) {
        try { return props.get("isLocked").getAsBoolean() } catch (Throwable t) { return lockImported }
    }
    return lockImported
}

// ── 3) GeoJSON'u ayrıştır ───────────────────────────────────────────
def plane = ImagePlane.getDefaultPlane()
def newAnnotations = []
def classCounts = new TreeMap<String, Integer>()
int skipped = 0
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
            if (!feature.has("geometry") || feature.get("geometry").isJsonNull()) { skipped++; return }
            def geometry = feature.getAsJsonObject("geometry")
            if (!geometry.has("type") || !geometry.has("coordinates")) { skipped++; return }
            String geomType = geometry.get("type").getAsString()

            // Her geometriden bir veya daha fazla dış-halka ROI'si üret
            def rings = []
            if (geomType == "Polygon") {
                def coords = geometry.getAsJsonArray("coordinates")
                if (coords != null && coords.size() > 0) rings << coords.get(0).getAsJsonArray()
            } else if (geomType == "MultiPolygon") {
                def polys = geometry.getAsJsonArray("coordinates")
                polys?.each { poly ->
                    def ringsOfPoly = poly.getAsJsonArray()
                    if (ringsOfPoly != null && ringsOfPoly.size() > 0) rings << ringsOfPoly.get(0).getAsJsonArray()
                }
            } else {
                skipped++; return   // Point/LineString vb. desteklenmez
            }

            String className = classNameOf(feature)
            boolean locked = lockedOf(feature)
            def pathClass = QP.getPathClass(className)

            boolean addedAny = false
            rings.each { ring ->
                def roi = ringToRoi(ring, plane)
                if (roi == null || roi.isEmpty()) { skipped++; return }
                def annotation = PathObjects.createAnnotationObject(roi, pathClass)
                annotation.setName(importSentinelName)
                if (locked) annotation.setLocked(true)
                newAnnotations << annotation
                classCounts[className] = (classCounts.getOrDefault(className, 0)) + 1
                addedAny = true
            }
            if (!addedAny && rings.isEmpty()) skipped++
        }
    } finally {
        reader.close()
    }
} catch (Throwable t) {
    Dialogs.showErrorMessage("GeoJSON okunamadı", "Dosya ayrıştırılamadı:\n\n" + (t.getMessage() ?: t.getClass().getSimpleName()))
    return
}

if (newAnnotations.isEmpty()) {
    Dialogs.showWarningNotification("Poligon yok",
        "GeoJSON dosyasında geçerli Polygon/MultiPolygon özelliği bulunamadı (atlanan: ${skipped}).")
    return
}

// ── 4) Önceki içe aktarımı temizle ve yenilerini ekle ───────────────
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == importSentinelName }, false)
QP.addObjects(newAnnotations)
QP.fireHierarchyUpdate()

// ── 5) Özet ─────────────────────────────────────────────────────────
def body = new StringBuilder()
body << "AI TAHMİN MASKELERİ — İÇE AKTARMA\n"
body << "═════════════════════════════════\n\n"
body << "Dosya: ${geojsonFile.getName()}\n\n"
body << String.format(java.util.Locale.US, "İçe aktarılan anotasyon : %,d%n", newAnnotations.size())
body << String.format(java.util.Locale.US, "Atlanan özellik         : %,d%n", skipped)
body << "\nSınıf dökümü:\n"
classCounts.each { cls, n ->
    body << String.format(java.util.Locale.US, "  %-20s : %,d%n", cls, n)
}
body << "\nTüm tahminler"
body << (lockImported ? " kilitli" : "")
body << " anotasyon olarak eklendi.\n"
body << "Model tahminleri görsel olarak doğrulanmalıdır; klinik kategori veya yorum üretilmez.\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar."

showResultWindow("AI tahmin maskesi içe aktarma", body.toString())
println "✓ AI tahmin içe aktarımı tamamlandı (${newAnnotations.size()} anotasyon, ${skipped} atlandı)."
