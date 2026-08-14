/**
 * Yardımcı - Anotasyon Yapısı QC (geometri bütünlüğü denetimi)
 * --------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+
 *
 * NE YAPAR:
 *   Açık görüntünün anotasyon koleksiyonunu SALT OKUNUR biçimde denetler.
 *   Hiyerarşiyi değiştirmez, ölçüm yazmaz. Olası geometri sorunlarını
 *   altı başlık altında sayar ve kısa hatalı-anotasyon listeleri sunar:
 *     (1) ÖRTÜŞME    — bounding-box ön süzgeci, ardından JTS kesişim alanı
 *                      küçük anotasyonun alanının >%5'ini kapsıyorsa çift sayılır.
 *     (2) NEREDEYSE-KOPYA — ağırlık merkezleri < 10 µm VE göreli alan farkı < %1.
 *     (3) BOŞ/TRİVİAL — alanı < 100 µm² ya da boş ROI.
 *     (4) İSİMSİZ+SINIFSIIZ — getName() null/boş VE getPathClass()==null.
 *     (5) SINIR DIŞI   — ROI sınırlayıcı kutusu sunucu genişlik/yüksekliğinin dışında.
 *     (6) GEÇERSİZ GEOMETRİ — getROI().getGeometry().isValid()==false.
 *
 * NE ÖLÇER:
 *   Anotasyon sayıları, örtüşme oranları, alan değerleri (µm² ya da px²),
 *   ağırlık merkezi farkı (µm). Klinik eşik, skor veya yorum ÜRETMEZ.
 *
 * KULLANIM:
 *   1. Denetlemek istediğiniz anotasyonları içeren slaydı açın.
 *   2. [Extensions → Atölye → Yardımcılar → Uzamsal analiz → Anotasyon Yapısı QC]
 *      ya da [Automate → Project scripts → bu betik]
 *
 * ÇIKTI:
 *   Her kategori için başlık + adet + en fazla ~10 hatalı anotasyonun
 *   ad/ID listesi. "Geometri bütünlüğü sorunu — elle inceleme gerekir."
 *   uyarısıyla çerçevelenir. Sonuç yalnızca ekran penceresinde/konsolda gösterilir.
 *
 * YÖNTEM / KAYNAK:
 *   • JTS (Java Topology Suite) — QuPath içinde; isValid(), intersection(), getArea(),
 *     buffer(0) düzeltmesi, centroid().distance().
 *   • Bankhead P et al. (2017) Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı geometri bütünlüğü denetimi üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── showResultWindow — VERBATIM from yardimci-kesisim-alani.groovy ──────────
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

            stage.setScene(new javafx.scene.Scene(root, 720, 560))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}
// ── End showResultWindow ─────────────────────────────────────────────────────

// ── Ön kontrol ───────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    if (isHeadless) { println "Hata: Görüntü açık değil."; return }
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce anotasyon içeren bir slayt açın.")
    return
}

def server = imageData.getServer()
double srvW = server.getWidth()  as double
double srvH = server.getHeight() as double

def cal = server.getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
boolean calibrated = (pw > 0 && ph > 0)
String areaUnit = calibrated ? "µm²" : "px² (kalibrasyon yok)"
// Alanı µm² veya px² döndüren kapanım
def toArea = { double pxArea -> calibrated ? pxArea * pw * ph : pxArea }

// Merkez mesafesi (µm ya da px — calibrated ise µm)
def centDist = { g1, g2 ->
    def c1 = g1.getCentroid(); def c2 = g2.getCentroid()
    // Anizotropik piksellerde X genişlikle, Y yükseklikle ölçeklenir (calibrated ise pw/ph > 0).
    double dx = (c1.getX() - c2.getX()) * (calibrated ? pw : 1.0d)
    double dy = (c1.getY() - c2.getY()) * (calibrated ? ph : 1.0d)
    Math.sqrt(dx * dx + dy * dy)
}

def annots = QP.getAnnotationObjects()
int total = annots.size()
if (total == 0) {
    def msg = "Bu görüntüde hiç anotasyon yok."
    if (isHeadless) { println msg; return }
    Dialogs.showWarningNotification("Anotasyon QC", msg)
    return
}

// ── Yardımcı: anotasyonun görünen adı ───────────────────────────────────────
def label = { ann ->
    def n = ann.getName()
    def c = ann.getPathClass()?.toString() ?: ""
    if (n != null && !n.isBlank()) return n
    if (!c.isBlank()) return "[${c}] (id=${ann.getID().toString().take(8)})"
    return "(id=${ann.getID().toString().take(8)})"
}

def MAX_LIST = 10  // Her kategoride gösterilecek max offender

// ── Kategori listeleri ────────────────────────────────────────────────────────
def overlapPairs     = []   // String açıklamaları
def nearDupPairs     = []
def emptyTrivial     = []
def unnamedUnclassed = []
def outOfBounds      = []
def invalidGeom      = []

// Geometrileri önbelleğe al (null-safe)
def geomCache = [:]
annots.each { ann ->
    try {
        def roi = ann.getROI()
        if (roi != null) {
            def g = roi.getGeometry()
            geomCache[ann] = g
        }
    } catch (Throwable ig) { }
}

// ── (3) BOŞ / TRİVİAL ───────────────────────────────────────────────────────
double TRIVIAL_UM2 = 100.0
annots.each { ann ->
    def roi = ann.getROI()
    if (roi == null || (geomCache[ann] != null && geomCache[ann].isEmpty())) {
        emptyTrivial << label(ann)
        return
    }
    def g = geomCache[ann]
    if (g == null) { emptyTrivial << label(ann); return }
    double areaPx = g.getArea()
    double areaScaled = toArea(areaPx)
    if (areaScaled < TRIVIAL_UM2) emptyTrivial << label(ann)
}

// ── (4) İSİMSİZ + SINIFSIIZ ─────────────────────────────────────────────────
annots.each { ann ->
    def n = ann.getName()
    if ((n == null || n.isBlank()) && ann.getPathClass() == null)
        unnamedUnclassed << label(ann)
}

// ── (5) SINIR DIŞI ──────────────────────────────────────────────────────────
annots.each { ann ->
    def roi = ann.getROI()
    if (roi == null) return
    if (roi.getBoundsX() < 0 || roi.getBoundsY() < 0 ||
        roi.getBoundsX() + roi.getBoundsWidth()  > srvW ||
        roi.getBoundsY() + roi.getBoundsHeight() > srvH)
        outOfBounds << label(ann)
}

// ── (6) GEÇERSİZ GEOMETRİ ───────────────────────────────────────────────────
annots.each { ann ->
    def g = geomCache[ann]
    if (g == null) return
    try { if (!g.isValid()) invalidGeom << label(ann) }
    catch (Throwable ig) { invalidGeom << label(ann) }
}

// ── (1) ÖRTÜŞME + (2) NEREDEYSE-KOPYA (çift döngü) ─────────────────────────
double OVERLAP_FRAC = 0.05   // Küçük anotasyon alanının >%5'i
double NEAR_DUP_DIST_UM = 10.0
double NEAR_DUP_AREA_FRAC = 0.01

def annotList = annots.toList()
for (int i = 0; i < annotList.size() - 1; i++) {
    def a = annotList[i]
    def ga = geomCache[a]
    if (ga == null) continue
    double areaA = toArea(ga.getArea())

    def roiA = a.getROI()
    double axMin = roiA.getBoundsX(), axMax = axMin + roiA.getBoundsWidth()
    double ayMin = roiA.getBoundsY(), ayMax = ayMin + roiA.getBoundsHeight()

    for (int j = i + 1; j < annotList.size(); j++) {
        def b = annotList[j]
        def gb = geomCache[b]
        if (gb == null) continue
        double areaB = toArea(gb.getArea())

        // ─ Bounding-box ön süzgeci (örtüşme için) ─
        def roiB = b.getROI()
        double bxMin = roiB.getBoundsX(), bxMax = bxMin + roiB.getBoundsWidth()
        double byMin = roiB.getBoundsY(), byMax = byMin + roiB.getBoundsHeight()
        boolean bbOverlap = !(axMax <= bxMin || bxMax <= axMin || ayMax <= byMin || byMax <= ayMin)

        // ─ (2) NEREDEYSE-KOPYA ─
        double distUm = centDist(ga, gb)
        double smallerArea = Math.min(areaA, areaB)
        double largerArea  = Math.max(areaA, areaB)
        double relAreaDiff = (largerArea > 0) ? Math.abs(areaA - areaB) / largerArea : 0.0
        if (distUm < NEAR_DUP_DIST_UM && relAreaDiff < NEAR_DUP_AREA_FRAC) {
            nearDupPairs << String.format(java.util.Locale.US,
                "%s ↔ %s (mesafe=%.1f %s, alan farkı=%.2f%%)",
                label(a), label(b), distUm, calibrated ? "µm" : "px", relAreaDiff * 100)
        }

        // ─ (1) ÖRTÜŞME (JTS kesişim) ─
        if (bbOverlap && smallerArea > 0) {
            try {
                def inter = ga.intersection(gb)
                if (inter != null && !inter.isEmpty()) {
                    double interArea = toArea(inter.getArea())
                    double frac = interArea / smallerArea
                    if (frac > OVERLAP_FRAC) {
                        overlapPairs << String.format(java.util.Locale.US,
                            "%s ↔ %s (örtüşme=%.1f%% küçük anot.)",
                            label(a), label(b), frac * 100)
                    }
                }
            } catch (Throwable t) {
                // buffer(0) kurtarma
                try {
                    def inter = ga.buffer(0).intersection(gb.buffer(0))
                    if (inter != null && !inter.isEmpty()) {
                        double interArea = toArea(inter.getArea())
                        double frac = interArea / smallerArea
                        if (frac > OVERLAP_FRAC) {
                            overlapPairs << String.format(java.util.Locale.US,
                                "%s ↔ %s (örtüşme=%.1f%% küçük anot., buffer kurtarma)",
                                label(a), label(b), frac * 100)
                        }
                    }
                } catch (Throwable t2) { /* geometri tamamen geçersiz — zaten (6)'da */ }
            }
        }
    }
}

// ── Sonuç metnini oluştur ────────────────────────────────────────────────────
def fmt = { List<String> lst ->
    if (lst.isEmpty()) return "  ✓ Sorun bulunamadı.\n"
    def sb = new StringBuilder()
    lst.take(MAX_LIST).each { sb << "  • ${it}\n" }
    if (lst.size() > MAX_LIST)
        sb << String.format(java.util.Locale.US, "  … ve %d sorunlu anotasyon daha (pencere sınırı)\n", lst.size() - MAX_LIST)
    return sb.toString()
}

def body = new StringBuilder()
body << "ANOTASYON YAPISI QC — geometri bütünlüğü denetimi\n"
body << "════════════════════════════════════════════════════\n"
body << String.format(java.util.Locale.US, "Toplam anotasyon : %d\n", total)
body << String.format(java.util.Locale.US, "Alan birimi      : %s\n", areaUnit)
body << "Not: Sorunlu anotasyonlar elle incelenmelidir.\n\n"

body << String.format(java.util.Locale.US,
    "(1) ÖRTÜŞME çiftleri (>%%5 küçük alan)   : %d çift\n", overlapPairs.size())
body << fmt(overlapPairs)

body << String.format(java.util.Locale.US,
    "(2) NEREDEYSE-KOPYA çiftleri (<10 %s, alan farkı <%%1) : %d çift\n",
    calibrated ? "µm" : "px", nearDupPairs.size())
body << fmt(nearDupPairs)

body << String.format(java.util.Locale.US,
    "(3) BOŞ / TRİVİAL (<100 %s ya da boş ROI) : %d\n", areaUnit, emptyTrivial.size())
body << fmt(emptyTrivial)

body << String.format(java.util.Locale.US,
    "(4) İSİMSİZ + SINIFSIIZ                  : %d\n", unnamedUnclassed.size())
body << fmt(unnamedUnclassed)

body << String.format(java.util.Locale.US,
    "(5) SINIR DIŞI                            : %d\n", outOfBounds.size())
body << fmt(outOfBounds)

body << String.format(java.util.Locale.US,
    "(6) GEÇERSİZ GEOMETRİ (JTS isValid=false) : %d\n", invalidGeom.size())
body << fmt(invalidGeom)

int totalIssues = overlapPairs.size() + nearDupPairs.size() + emptyTrivial.size() +
                  unnamedUnclassed.size() + outOfBounds.size() + invalidGeom.size()
body << "────────────────────────────────────────────────────\n"
body << String.format(java.util.Locale.US,
    "Toplam sorunlu giriş (çiftler ayrı sayılır) : %d\n\n", totalIssues)
body << "Bu betik hiyerarşiyi değiştirmez ve ölçüm yazmaz.\n"
body << "Geometri bütünlüğü sorunu — elle inceleme gerekir.\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı geometri bütünlüğü denetimi üretir."

showResultWindow("Anotasyon Yapısı QC", body.toString())
println String.format(java.util.Locale.US,
    "✓ Anotasyon QC tamamlandı: %d anotasyon incelendi, %d sorunlu giriş.",
    total, totalIssues)
