/**
 * Yardımcı - Boya Vektörlerini Tahmin Et
 * ------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * SEÇİLİ temsilî bir bölgeden renk-dekonvolüsyon (boya) vektörlerini TAHMİN eder,
 * eski → yeni karşılaştırmasını gösterir ve ONAYINIZLA görüntüye uygular.
 * "Boya vektörlerini kontrol et" yalnız okur/raporlar; bu betik hesaplar/uygular.
 *
 * NEDEN ÖNEMLİ:
 *   • Boya vektörleri tarayıcı/laboratuvar/protokole özgüdür. Kantitatif H-DAB
 *     ölçümünde (Modül 3a, 3b, 4, 5, 7) eşikler MUTLAK DAB OD üzerindendir;
 *     yanlış vektör her skoru kaydırır.
 *   • QuPath'in yerleşik [Image → Estimate stain vectors] komutunun tek-tıkla,
 *     sadeleştirilmiş (önizle → uygula) karşılığıdır.
 *
 * BOYA AYRIMI ≠ BOYA NORMALİZASYONU:
 *   • Ayrım (bu betik): bu slaydı doğru OKUMAK için kanallara kalibre eder.
 *   • Normalizasyon: slaytları birbirine BENZETMEK için yeniden renklendirir
 *     (Macenko/Reinhard/Vahadane — bkz. Ek A).
 *
 * KULLANIM:
 *   1. İHK/H&E slaytını açın; Image type'ı brightfield yapın
 *      (gerekirse: Yardımcılar → Görüntü tipi ayarla)
 *   2. Temsilî bir bölge ÇİZİN ve SEÇİN (iki boya + biraz arka plan)
 *   3. [Extensions → Atölye → Yardımcılar → Boya vektörlerini tahmin et]
 *   4. Eski → yeni vektörleri inceleyin; uygunsa [Uygula]
 *
 * YÖNTEM REFERANSLARI:
 *   • Ruifrok AC, Johnston DA (2001), Anal Quant Cytol Histol 23(4):291–299.
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.regions.RegionRequest
import qupath.lib.analysis.algorithms.EstimateStainVectors

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// QuPath EstimateStainVectorsCommand referans sabitleri (0.6.0)
double MIN_STAIN_OD = 0.05d
double MAX_STAIN_OD = 1.0d
double IGNORE_PCT   = 1.0d
boolean CHECK_COLORS = true
double MAX_PIXELS   = 16_000_000d

def vecOf = { stain ->
    [stain.getRed() as double, stain.getGreen() as double, stain.getBlue() as double] as double[]
}
def fmtVec = { double[] v ->
    String.format(java.util.Locale.US, "r=%.4f  g=%.4f  b=%.4f", v[0], v[1], v[2])
}
def angleDeg = { double[] a, double[] b ->
    double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    dot = Math.max(-1.0d, Math.min(1.0d, dot))
    Math.toDegrees(Math.acos(dot))
}

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    def msg = "Önce bir slayt açın."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Görüntü açık değil", msg)
    return
}

def oldStains = imageData.getColorDeconvolutionStains()
if (oldStains == null) {
    def msg = "Bu görüntüde renk-dekonvolüsyon (boya) vektörleri tanımlı değil.\n\n" +
              "Önce görüntü tipini brightfield yapın:\n" +
              "  [Extensions → Atölye → Yardımcılar → Görüntü tipi ayarla]"
    if (isHeadless) println msg else Dialogs.showErrorMessage("Boya vektörü yok", msg)
    return
}

def selected = QP.getSelectedObjects().findAll { it.hasROI() && it.getROI().isArea() }
if (selected.isEmpty()) {
    def msg = "Tahmin için temsilî bir BÖLGE seçili olmalı.\n\n" +
              "Yapın:\n" +
              "  1. Dikdörtgen/çokgen anotasyon aracı ile iki boyayı +\n" +
              "     biraz arka planı içeren bir alan çizin\n" +
              "  2. O anotasyonu seçili bırakın (üzerine tıklayın)\n" +
              "  3. Bu yardımcıyı tekrar çalıştırın\n\n" +
              "QuPath tahmini bu bölgenin pikselleri üzerinden yapar."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Bölge seçili değil", msg)
    return
}
def roi = selected[0].getROI()
def multiNote = selected.size() > 1 ?
    ("\n(Not: " + selected.size() + " nesne seçiliydi; ilk alan bölgesi kullanıldı.)") : ""

// ── 2) Bölgeyi oku (MAX_PIXELS altına indir) ───────────────────────
def server = imageData.getServer()
double regionPixels = (roi.getBoundsWidth() as double) * (roi.getBoundsHeight() as double)
double downsample = Math.max(1.0d, Math.sqrt(regionPixels / MAX_PIXELS))
def request = RegionRequest.createInstance(server.getPath(), downsample, roi)
def img
try {
    img = server.readRegion(request)
} catch (Throwable t) {
    def msg = "Seçili bölge okunamadı: " + (t.getMessage() ?: t.getClass().getSimpleName())
    if (isHeadless) println msg else Dialogs.showErrorMessage("Bölge okunamadı", msg)
    return
}
if (img == null) {
    def msg = "Seçili bölgeden görüntü alınamadı (boş bölge?)."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Bölge boş", msg)
    return
}

// ── 3) Tahmin ───────────────────────────────────────────────────────
def newStains
try {
    newStains = EstimateStainVectors.estimateStains(
        img, oldStains, MIN_STAIN_OD, MAX_STAIN_OD, IGNORE_PCT, CHECK_COLORS)
} catch (Throwable t) {
    def msg = "Boya vektörleri tahmin edilemedi.\n\n" +
              "Olası neden: seçili bölge iki boyayı + biraz arka planı birlikte\n" +
              "içermiyor olabilir (QuPath renk denetimini geçemedi).\n\n" +
              "Daha temsilî bir alan seçip tekrar deneyin.\n\n" +
              "Ayrıntı: " + (t.getMessage() ?: t.getClass().getSimpleName())
    if (isHeadless) println msg else Dialogs.showErrorMessage("Tahmin başarısız", msg)
    return
}

// ── 4) Eski → yeni rapor metni ──────────────────────────────────────
double[] os1 = vecOf(oldStains.getStain(1)); double[] ns1 = vecOf(newStains.getStain(1))
double[] os2 = vecOf(oldStains.getStain(2)); double[] ns2 = vecOf(newStains.getStain(2))
double d1 = angleDeg(os1, ns1)
double d2 = angleDeg(os2, ns2)

def report = new StringBuilder()
report << "BOYA VEKTÖRÜ TAHMİNİ — ESKİ → YENİ\n"
report << "═══════════════════════════════════════\n\n"
report << "Set adı : " << (oldStains.getName() ?: "(adsız)") << multiNote << "\n"
report << String.format(java.util.Locale.US, "Örnekleme downsample : %.2f%n%n", downsample)
report << (oldStains.getStain(1).getName() ?: "Stain 1") << "\n"
report << "  eski : " << fmtVec(os1) << "\n"
report << "  yeni : " << fmtVec(ns1) << "\n"
report << String.format(java.util.Locale.US, "  açısal değişim : %.2f derece%n%n", d1)
report << (oldStains.getStain(2).getName() ?: "Stain 2") << "\n"
report << "  eski : " << fmtVec(os2) << "\n"
report << "  yeni : " << fmtVec(ns2) << "\n"
report << String.format(java.util.Locale.US, "  açısal değişim : %.2f derece%n%n", d2)
report << String.format(java.util.Locale.US,
        "Arka plan (maks RGB) eski : %.1f, %.1f, %.1f%n",
        oldStains.getMaxRed() as double, oldStains.getMaxGreen() as double, oldStains.getMaxBlue() as double)
report << String.format(java.util.Locale.US,
        "Arka plan (maks RGB) yeni : %.1f, %.1f, %.1f%n",
        newStains.getMaxRed() as double, newStains.getMaxGreen() as double, newStains.getMaxBlue() as double)

// ── 5a) Headless: yalnız raporla, UYGULAMA ─────────────────────────
if (isHeadless) {
    println "=== Boya vektörü tahmini ===\n" + report.toString()
    println "\n(Headless mod: vektörler UYGULANMADI. Uygulamak için QuPath arayüzünde çalıştırın.)"
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── 5b) GUI: önizle → onayla ───────────────────────────────────────
def applyName = "Bölgeden tahmin (Atölye)"
javafx.application.Platform.runLater {
    try {
        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle("Boya vektörleri — tahmin")
        stage.setAlwaysOnTop(true)

        def textArea = new javafx.scene.control.TextArea(report.toString())
        textArea.setEditable(false)
        textArea.setWrapText(false)
        textArea.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;")

        def status = new javafx.scene.control.Label(
            "Yeni vektörleri inceleyin. Uygunsa [Uygula]; değilse [İptal] (görüntü değişmez).")
        status.setWrapText(true)
        status.setStyle("-fx-padding: 4 0 0 0;")

        def applyBtn = new javafx.scene.control.Button("Uygula")
        applyBtn.setDefaultButton(true)
        def cancelBtn = new javafx.scene.control.Button("İptal")
        def revertBtn = new javafx.scene.control.Button("Geri al")
        revertBtn.setDisable(true)
        revertBtn.setVisible(false)
        revertBtn.setManaged(false)

        def repaint = {
            javafx.application.Platform.runLater {
                try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
            }
        }

        applyBtn.setOnAction({
            imageData.setColorDeconvolutionStains(newStains.changeName(applyName))
            repaint()
            status.setText("✓ Yeni boya vektörleri uygulandı. Geri almak için [Geri al].")
            applyBtn.setDisable(true)
            revertBtn.setVisible(true); revertBtn.setManaged(true); revertBtn.setDisable(false)
            cancelBtn.setText("Kapat")
        })
        revertBtn.setOnAction({
            imageData.setColorDeconvolutionStains(oldStains)
            repaint()
            status.setText("↩ Eski boya vektörleri geri yüklendi.")
            applyBtn.setDisable(false)
            revertBtn.setDisable(true); revertBtn.setVisible(false); revertBtn.setManaged(false)
            cancelBtn.setText("Kapat")
        })
        cancelBtn.setOnAction({ stage.close() })

        def spacer = new javafx.scene.layout.Region()
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
        def buttons = new javafx.scene.layout.HBox(10, spacer, revertBtn, cancelBtn, applyBtn)
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
        buttons.setPadding(new javafx.geometry.Insets(8))

        def root = new javafx.scene.layout.BorderPane()
        root.setCenter(textArea)
        def footer = new javafx.scene.control.Label("QuPath Atölye Scriptleri · araştırma/eğitim amaçlı")
        footer.setMaxWidth(Double.MAX_VALUE)
        footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
        def bottom = new javafx.scene.layout.VBox(8.0, status, footer, buttons)
        bottom.setPadding(new javafx.geometry.Insets(8))
        root.setBottom(bottom)

        stage.setScene(new javafx.scene.Scene(root, 760, 560))
        stage.show()
    } catch (Throwable t) {
        Dialogs.showMessageDialog("Boya vektörleri — tahmin", report.toString())
    }
}
println "✓ Boya vektörü tahmini hazır (önizleme penceresi açıldı; onaya kadar uygulanmadı)."
