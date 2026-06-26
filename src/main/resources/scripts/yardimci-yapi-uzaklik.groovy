/**
 * Yardımcı - Yapıya Uzaklık (sınıra mesafe)
 * ------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Her hücre tespiti için, SEÇİLİ anotasyon(lar)ın (bir "yapı": tümör sınırı,
 * damar, bez, invazyon kenarı vb.) SINIRINA olan işaretli mesafeyi (µm)
 * hesaplar ve hücre ölçümü olarak yazar. İşaretli mesafe: NEGATİF değer
 * hücrenin yapının İÇİNDE olduğunu, pozitif değer DIŞINDA olduğunu gösterir.
 *
 * NEDEN: Atölyenin uzamsal yardımcıları (Delaunay, en yakın komşu, yoğunluk)
 * hücre-hücre düzenini ölçer. Ama çoğu patoloji sorusu bir YAPIYA göredir:
 * "immün hücreler tümör sınırına ne kadar yakın?", "Ki-67+ hücreler invazyon
 * kenarında mı yoğun?". Bu, From Samples to Knowledge (FS2K, Session 12)
 * eğitimindeki "signed distance to annotations → yakın/uzak sınıflama"
 * iş akışının atölye karşılığıdır ve doğrudan Ek O (TIL) / Ek L (TSR)
 * bölümlerini besler.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • VAR OLAN tespitlerin merkezinden seçili yapının sınırına mesafe ölçer.
 *     Bu bir MESAFE ölçümüdür — klinik skor, eşik veya yorum DEĞİL.
 *   • Hücre TESPİTİ YAPMAZ. Önce bir tespit modülü çalıştırın (Modül 2/3/5/7).
 *   • Yapıyı (anotasyonu) sizin çizmeniz gerekir; betik bunu üretmez.
 *
 * QuPath KARŞILIĞI (GUI):
 *   [Analyze → Spatial analysis → Signed distance to annotations 2D]
 *   yerleşik komutu aynı ölçümü üretir, ama yalnız SINIFLANDIRILMIŞ
 *   anotasyonlar için çalışır. Bu betik seçili herhangi bir anotasyonu
 *   (sınıfı olsun olmasın) hedef alır ve tek, temiz bir ölçüm sütunu yazar.
 *
 * KULLANIM:
 *   1. Hücre tespiti yapılmış, kalibre (µm/px) bir slayt açın
 *   2. Yapıyı anotasyon olarak çizin ve SEÇİN (birden çok da olabilir)
 *   3. [Extensions → Atölye → Yardımcılar → Yapıya uzaklık (sınıra mesafe)]
 *      (ya da [Automate → Project scripts → bu betik])
 *
 * ÇIKTI:
 *   • Her hücre: "Yapıya uzaklık (µm)" ölçümü (− = içeride; Modül 9 ile aktarılır)
 *   • Kilitli "Yapıya Uzaklık Özet": ortalama/medyan/min/maks, yapı içi adet,
 *     sınır bandı (|mesafe| ≤ N µm) adet ve %
 *   • Sonuç penceresinde özet tablo
 *
 * YÖNTEM REFERANSLARI:
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *   • From Samples to Knowledge 2025 (FS2K), Session 12 — uzamsal analiz
 *     (yapıya işaretli mesafe → yakın/uzak): saramcardle.github.io/FS2K
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.analysis.DistanceTools

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

// ── Parametreler ────────────────────────────────────────────────────
double bandMicrons = 50.0          // "sınır bandı" eşiği (µm): |mesafe| ≤ N → yakın
String distMeas    = "Yapıya uzaklık (µm)"
String summaryName = "Yapıya Uzaklık Özet"

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce hücre tespiti yapılmış bir slayt açın.")
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok", "Slaytta piksel boyutu (µm) tanımlı değil; mesafe µm olarak hesaplanamaz." +
        "\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu).")
    return
}

def detections = QP.getDetectionObjects().findAll { it.getROI() != null }
if (detections.isEmpty()) {
    def msg = "Slaytta hücre tespiti yok.\nÖnce Modül 2/3/5/7 ile tespit yapın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Tespit yok", msg)
    return
}

def targets = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI() != null && it.getName() != summaryName
}
if (targets.isEmpty()) {
    def msg = "Hedef yapı seçili değil.\n\n" +
              "Mesafe ölçülecek yapıyı (tümör sınırı, damar, invazyon kenarı vb.)\n" +
              "anotasyon olarak çizin ve SEÇİN, sonra betiği tekrar çalıştırın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Yapı seçili değil", msg)
    return
}

// ── 2) İşaretli mesafe (merkez → yapı sınırı), µm ───────────────────
// QuPath çekirdek API'si: her kaynak hücrenin merkezinden hedef yapıların
// BİRLEŞİK sınırına mesafe; signed=true → yapı içindeki hücrelerde negatif.
println String.format(java.util.Locale.US, "Yapıya uzaklık hesaplanıyor (%,d hücre, %d hedef yapı)...",
    detections.size(), targets.size())
DistanceTools.centroidToBoundsDistance2D(detections, targets, pw, ph, distMeas, true)

// ── 3) Ölçümleri oku + istatistik ───────────────────────────────────
def vals = []
detections.each { d ->
    def v = d.measurements[distMeas]
    if (v != null && !Double.isNaN(v as double)) vals << (v as double)
}
if (vals.isEmpty()) {
    showResultWindow("Yapıya uzaklık — ölçüm üretilemedi",
        "Mesafe ölçümü hiçbir hücreye yazılamadı.\n\n" +
        "Seçili yapı(lar)ın geçerli bir alan/çizgi ROI'si olduğundan ve hücrelerle\n" +
        "aynı z/t düzleminde bulunduğundan emin olun.\n\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.")
    return
}

int total = vals.size()
double meanD = vals.sum() / total
def sorted = vals.sort(false)
int m = sorted.size()
double medianD = (m % 2 == 1) ? sorted[(int) (m / 2)] : (sorted[(int) (m / 2) - 1] + sorted[(int) (m / 2)]) / 2.0
double minD = sorted[0]
double maxD = sorted[m - 1]
int insideCount = vals.count { it < 0 } as int
int bandCount   = vals.count { Math.abs(it) <= bandMicrons } as int
double insidePct = 100.0 * insideCount / total
double bandPct   = 100.0 * bandCount / total

// ── 4) Kilitli özet anotasyonu ──────────────────────────────────────
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == summaryName }, false)
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName(summaryName)
summary.measurements['Hücre sayısı']                  = total as double
summary.measurements['Hedef yapı sayısı']             = targets.size() as double
summary.measurements['Sınır bandı eşiği (µm)']        = bandMicrons
summary.measurements['Ortalama uzaklık (µm)']         = meanD
summary.measurements['Medyan uzaklık (µm)']           = medianD
summary.measurements['Minimum uzaklık (µm)']          = minD
summary.measurements['Maksimum uzaklık (µm)']         = maxD
summary.measurements['Yapı içinde (adet)']            = insideCount as double
summary.measurements['Yapı içinde (%)']               = insidePct
summary.measurements["Sınır bandı ≤ ${bandMicrons as int} µm (adet)"] = bandCount as double
summary.measurements["Sınır bandı ≤ ${bandMicrons as int} µm (%)"]    = bandPct
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

// ── 5) Sonucu sun ───────────────────────────────────────────────────
def structNames = targets.collect {
    def nm = it.getName()
    def pc = it.getPathClass()
    nm ?: (pc != null ? pc.toString() : "(adsız)")
}.unique()

def body = new StringBuilder()
body << "YAPIYA UZAKLIK (sınıra işaretli mesafe)\n"
body << "════════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Hücre sayısı       : %,d%n", total)
body << "Hedef yapı(lar)    : ${structNames.join(', ')}\n"
body << String.format(java.util.Locale.US, "Sınır bandı eşiği  : %.0f µm%n", bandMicrons)
body << "\n"
body << String.format(java.util.Locale.US, "Ortalama uzaklık   : %.1f µm%n", meanD)
body << String.format(java.util.Locale.US, "Medyan uzaklık     : %.1f µm%n", medianD)
body << String.format(java.util.Locale.US, "Min / Maks         : %.1f / %.1f µm%n", minD, maxD)
body << "──────────────────────────────────────────────\n"
body << String.format(java.util.Locale.US, "Yapı içinde (− )   : %,d  (%.1f %%)%n", insideCount, insidePct)
body << String.format(java.util.Locale.US, "Sınır bandı ≤%.0fµm : %,d  (%.1f %%)%n", bandMicrons, bandCount, bandPct)
body << "\n"
body << "İşaret kuralı: NEGATİF mesafe → hücre yapının İÇİNDE; pozitif → dışında.\n"
body << "Her hücreye '" << distMeas << "' ölçümü yazıldı; ölçüme göre renklendirince\n"
body << "yapıya yakınlık haritası görünür ([Measure → Show measurement maps]).\n"
body << "Modül 9 ile dışa aktarılır.\n\n"
body << "Bu bir MESAFE ölçümüdür — klinik skor, eşik veya yorum DEĞİL.\n"
body << "(FS2K Session 12; Bankhead 2017)\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Yapıya uzaklık", body.toString())
println String.format(java.util.Locale.US,
    "✓ Yapıya uzaklık yazıldı (ortalama %.1f µm, medyan %.1f µm; sınır bandı %%%.1f).",
    meanD, medianD, bandPct)
