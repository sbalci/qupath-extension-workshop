/**
 * Yardımcı - GrandQC Kohort KK Özeti
 * -----------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Açık projedeki HER görüntü için hiyerarşiyi diskten okur; GrandQC sihirbazının
 *   ürettiği "GrandQC KK" adlı (sentinel) anotasyonları toplar ve slayt başına
 *   kalite-kontrol özetini GENİŞ TABLO (bir satır = bir görüntü) TSV'ye yazar:
 *     • doku alanı (mm²), temiz doku alanı (mm²), artefakt alanı (mm²) ve doku içi %
 *     • doku parça (bağlı bileşen) sayısı
 *     • her artefakt sınıfı için alan (mm²) + anotasyon (bölge) sayısı
 *   GrandQC anotasyonu olmayan slaytlar atlanır (hata değil).
 *
 *   GrandQC sihirbazının per-slayt sonuç penceresini KOHORT düzeyine taşır:
 *   "tüm slayt setimde kalite nasıldı?" sorusunu tek tabloda yanıtlar. Yerleşik
 *   "Kohort özet toplayıcı" ile aynı proje-döngüsü kalıbını izler.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Yalnızca var olan GrandQC anotasyonlarının GEOMETRİSİNDEN alan/sayı/% türetir.
 *   • Kalite notu, derece, eşik, kabul/ret ya da klinik yorum ÜRETMEZ.
 *   • Hiçbir imageData'yı KAYDETMEZ (saveImageData çağrısı yoktur) — salt okunur.
 *   • Alanlar yalnız kalibre görüntülerde mm² verilir; kalibre değilse hücre boş kalır.
 *   • Artefakt alanı doğrudan (artefakt sınıfları ∩ doku) olarak ölçülür — ayrı saklanan
 *     "Temiz doku" anotasyonuna bağlı DEĞİLDİR; böylece %100 artefaktlı slaytta da doludur.
 *     "temiz_doku_mm2" ise "Temiz doku" anotasyonundan gelir (sihirbaz üretmediyse boş kalır).
 *
 * KULLANIM:
 *   1. Önce GrandQC sihirbazıyla slaytları işleyip GeoJSON'u içe aktarın
 *      (tek slayt ya da "Proje geneli içe aktar"). Anotasyonlar "GrandQC KK" adını taşır.
 *   2. [Extensions → Atölye → Yardımcılar → Klinik & kohort → GrandQC kohort KK özeti]
 *      (ya da [Automate → Project scripts → bu betik])
 *   3. TSV <proje>/grandqc-kohort/ altında oluşur; sonuç penceresinde yol + özet görünür.
 *
 * ÇIKTI:
 *   • <proje>/grandqc-kohort/grandqc_kohort_ozet_<millis>.tsv
 *     Eksik hücreler boş string (Excel/R doğrudan okur).
 *   • Sonuç penceresi: özetlenen/atlanan görüntü sayısı, TSV yolu.
 *   • Headless: aynı TSV + konsol çıktısı.
 *
 * YÖNTEM / KAYNAK:
 *   • Weng Z ve ark. (2024), Nat Commun 15:10685 — GrandQC. doi:10.1038/s41467-024-54769-y
 *   • sbalci/metadata-qupath — proje döngüsü kalıbı (entry.readImageData() + close + gc).
 *   • Bankhead P et al. (2017), Sci Rep. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import java.io.File

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── showResultWindow (lint Check 8 — VERBATIM from yardimci-kesisim-alani) ───
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
            def __footer = new javafx.scene.control.Label("QuPath Atölye Betikleri · araştırma/eğitim amaçlı")
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons)
            __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)

            stage.setScene(new javafx.scene.Scene(root, 760, 540))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── GrandQC sabitleri (yardimci-grandqc-sihirbaz ile aynı) ───────────────────
def GRANDQC_SENTINEL = 'GrandQC KK'
def TISSUE_CLASS     = 'Tissue'
def CLEAN_CLASS      = 'Temiz doku'
def ARTIFACT_CLASSES = ['Tissue Fold', 'Dark Spot & Foreign Object',
                        'Pen Marking', 'Air Bubble & Slide Edge', 'Out of Focus']

// ── Birleştirme (RoiTools; başarısızsa JTS) + alan/parça sayımı ──────────────
def unionRoi = { rois ->
    if (rois == null || rois.isEmpty()) return null
    if (rois.size() == 1) return rois[0]
    try {
        return qupath.lib.roi.RoiTools.union(rois)
    } catch (Throwable t) {
        org.locationtech.jts.geom.Geometry g = null
        for (r in rois) { def gg = r.getGeometry(); g = (g == null) ? gg : g.union(gg) }
        return qupath.lib.roi.GeometryTools.geometryToROI(g, qupath.lib.regions.ImagePlane.getDefaultPlane())
    }
}
// Bir ROI için: kalibre ise mm² alan + parça (bağlı bileşen, alan>1px²) sayısı.
def areaAndFrags = { roi, double pw, double ph, boolean cal ->
    if (roi == null) return [mm2: Double.NaN, frags: 0]
    org.locationtech.jts.geom.Geometry g = null
    try { g = roi.getGeometry() } catch (Throwable t) { return [mm2: Double.NaN, frags: 0] }
    if (g == null) return [mm2: Double.NaN, frags: 0]
    double aPx = 0.0d; int frags = 0
    int n = g.getNumGeometries()
    for (int i = 0; i < n; i++) {
        double ai
        try { ai = g.getGeometryN(i).getArea() } catch (Throwable t) { ai = 0.0d }
        if (ai > 1.0d) { aPx += ai; frags++ }
    }
    return [mm2: (cal ? (aPx * pw * ph / 1_000_000.0d) : Double.NaN), frags: frags]
}
// İki ROI'nin KESİŞİM alanı (mm²). Artefakt alanını doğrudan (artefakt∩doku) ölç →
// ayrı saklanan "Temiz doku" anotasyonuna bağımlılığı kaldırır; %100 artefaktlı slaytta
// (Temiz doku üretilememiş olsa bile) artefakt alanı yine doğru çıkar.
def intersectArea = { roiA, roiB, double pw, double ph, boolean cal ->
    if (roiA == null || roiB == null || !cal) return Double.NaN
    try {
        def g = roiA.getGeometry().intersection(roiB.getGeometry())
        if (g == null || g.isEmpty()) return 0.0d
        return g.getArea() * pw * ph / 1_000_000.0d
    } catch (Throwable t) { return Double.NaN }
}
def fmtArea = { double v -> Double.isNaN(v) ? '' : String.format(java.util.Locale.US, '%.4f', v) }
def fmtPct  = { double v -> Double.isNaN(v) ? '' : String.format(java.util.Locale.US, '%.1f', v) }

// ── 1) Ön kontrol: açık proje zorunlu ────────────────────────────────────────
def project = QP.getProject()
if (project == null) {
    def msg = "Önce bir QuPath projesi açın. (Dosya → Proje aç…)\nBu betik tek slayt modunda çalışmaz."
    if (isHeadless) { println msg; return }
    Dialogs.showErrorMessage("Proje açık değil", msg)
    return
}
def entries = project.getImageList()
if (entries == null || entries.isEmpty()) {
    def msg = "Projede görüntü bulunamadı."
    if (isHeadless) { println msg; return }
    Dialogs.showErrorMessage("Boş proje", msg)
    return
}

// ── 2) Çıktı dizini + sütun düzeni ───────────────────────────────────────────
def projectDir = null
try { projectDir = project.getPath()?.getParent()?.toFile() } catch (Throwable ignore) {}
def outDir = (projectDir != null) ? new File(projectDir, "grandqc-kohort") : new File(System.getProperty("java.io.tmpdir"), "grandqc-kohort")
outDir.mkdirs()
long millis = System.currentTimeMillis()
def tsvFile = new File(outDir, String.format(java.util.Locale.US, "grandqc_kohort_ozet_%d.tsv", millis))

def columns = ["image_name", "doku_mm2", "temiz_doku_mm2", "artefakt_mm2", "artefakt_yuzde", "doku_parca_sayisi"]
ARTIFACT_CLASSES.each { c -> columns << (c + " · alan_mm2"); columns << (c + " · n") }

// ── 3) Tüm görüntüleri gez: GrandQC sentinel anotasyonlarını topla ───────────
def rows        = []
int totalImages = entries.size()
int skipped     = 0   // GrandQC anotasyonu olmayan
int failed      = 0   // okunamayan
println String.format(java.util.Locale.US, "GrandQC kohort KK özeti başladı: %d görüntü", totalImages)

entries.eachWithIndex { entry, idx ->
    def imgName = entry.getImageName()
    def imageData = null
    try {
        imageData = entry.readImageData()
        def hierarchy = imageData.getHierarchy()
        def sentinels = hierarchy.getAnnotationObjects().findAll { it.getName() == GRANDQC_SENTINEL }
        if (sentinels.isEmpty()) {
            skipped++
            println String.format(java.util.Locale.US, "  [%d/%d] %s — GrandQC anotasyonu yok, atlandı", idx + 1, totalImages, imgName)
            return
        }
        def cal = imageData.getServer().getPixelCalibration()
        double pw = cal.getPixelWidthMicrons(); double ph = cal.getPixelHeightMicrons()
        boolean isCal = (pw > 0 && ph > 0 && !Double.isNaN(pw) && !Double.isNaN(ph))

        def byClass = sentinels.groupBy { it.getPathClass()?.getName() ?: 'Background' }
        def roisOf  = { String cn -> (byClass[cn] ?: []).collect { it.getROI() } }

        def tissueUnionRoi = unionRoi(roisOf(TISSUE_CLASS))
        def tissue = areaAndFrags(tissueUnionRoi, pw, ph, isCal)
        def clean  = areaAndFrags(unionRoi(roisOf(CLEAN_CLASS)), pw, ph, isCal)   // bilgi amaçlı (temiz_doku_mm2)
        // Artefakt alanı = (tüm artefakt sınıflarının birleşimi) ∩ doku — "Temiz doku"dan bağımsız.
        def artUnionRoi = unionRoi(ARTIFACT_CLASSES.collectMany { roisOf(it) })
        double artMm2
        if (tissueUnionRoi == null || !isCal)      artMm2 = Double.NaN         // doku yok / kalibre değil → boş
        else if (artUnionRoi == null)              artMm2 = 0.0d               // artefakt anotasyonu yok → 0
        else                                       artMm2 = intersectArea(artUnionRoi, tissueUnionRoi, pw, ph, isCal)
        double artPct = (!Double.isNaN(tissue.mm2) && tissue.mm2 > 0 && !Double.isNaN(artMm2)) ? (artMm2 / tissue.mm2 * 100.0d) : Double.NaN

        def row = new java.util.LinkedHashMap<String, String>()
        row["image_name"]        = imgName
        row["doku_mm2"]          = fmtArea(tissue.mm2)
        row["temiz_doku_mm2"]    = fmtArea(clean.mm2)
        row["artefakt_mm2"]      = fmtArea(artMm2)
        row["artefakt_yuzde"]    = fmtPct(artPct)
        row["doku_parca_sayisi"] = String.format(java.util.Locale.US, "%d", tissue.frags)
        ARTIFACT_CLASSES.each { c ->
            def rr = roisOf(c)
            def ar = areaAndFrags(unionRoi(rr), pw, ph, isCal)
            row[c + " · alan_mm2"] = fmtArea(ar.mm2)
            row[c + " · n"]        = String.format(java.util.Locale.US, "%d", rr.size())
        }
        rows << row
        println String.format(java.util.Locale.US, "  [%d/%d] %s — %d GrandQC anotasyonu", idx + 1, totalImages, imgName, sentinels.size())
    } catch (Throwable t) {
        failed++
        println String.format(java.util.Locale.US, "  [%d/%d] %s — HATA: %s", idx + 1, totalImages, imgName, (t.getMessage() ?: t.getClass().getSimpleName()))
    } finally {
        try { imageData?.getServer()?.close() } catch (Throwable ignore) {}
        imageData = null
        System.gc()
    }
}

if (rows.isEmpty()) {
    def msg = "Hiçbir görüntüde GrandQC anotasyonu (\"" + GRANDQC_SENTINEL + "\") bulunamadı.\n" +
              "Önce GrandQC sihirbazıyla slaytları işleyip GeoJSON'u içe aktarın."
    if (isHeadless) { println msg; return }
    Dialogs.showWarningNotification("GrandQC kohort KK özeti", msg)
    return
}

// ── 4) TSV yaz (sekme ayırıcı; eksik hücre = boş string) ────────────────────
try {
    tsvFile.withWriter("UTF-8") { w ->
        w.writeLine(columns.join("\t"))
        rows.each { r ->
            w.writeLine(columns.collect { col -> (r[col] ?: "") }.join("\t"))
        }
    }
} catch (Throwable t) {
    def msg = "TSV yazılamadı: ${t.getMessage() ?: t.getClass().getSimpleName()}\nHedef: ${tsvFile.getAbsolutePath()}"
    if (isHeadless) println "HATA: ${msg}" else Dialogs.showErrorMessage("Yazma hatası", msg)
    return
}

// ── 5) Sonucu sun ─────────────────────────────────────────────────────────────
def body = new StringBuilder()
body << "GrandQC KOHORT KALİTE KONTROL ÖZETİ\n"
body << "═══════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Görüntü sayısı        : %,d%n", totalImages)
body << String.format(java.util.Locale.US, "Özetlenen (GrandQC'li): %,d%n", rows.size())
body << String.format(java.util.Locale.US, "Atlanan (anotasyon yok): %,d%n", skipped)
body << String.format(java.util.Locale.US, "Okunamayan            : %,d%n", failed)
body << "\nTSV dosyası:\n  " + tsvFile.getAbsolutePath() + "\n"
body << "\nSütunlar: doku/temiz doku/artefakt alanı (mm²) + doku içi artefakt %,\n"
body << "doku parça sayısı ve her artefakt sınıfı için alan (mm²) + bölge sayısı.\n"
body << "Alanlar yalnız kalibre görüntülerde doldurulur; kalibre değilse hücre boş.\n"
body << "Anotasyon üretmez, hiçbir slaydı kaydetmez — salt okunur.\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("GrandQC Kohort KK Özeti", body.toString())
println String.format(java.util.Locale.US,
    "✓ TSV yazıldı: %d görüntü özetlendi (%d atlandı, %d hata) → %s",
    rows.size(), skipped, failed, tsvFile.getAbsolutePath())
