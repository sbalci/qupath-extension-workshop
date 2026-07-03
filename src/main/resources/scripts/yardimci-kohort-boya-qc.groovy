/**
 * Yardımcı - Kohort Boya OD Drift (proje geneli boya QC)
 * -------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Açık QuPath projesindeki her parlak-alan (H&E / H-DAB) görüntüyü sırayla
 *   açar; her birinden küçük bir temsili bölge okur ve boya yoğunluğunu ölçer:
 *     • Boya-1 (Hematoksilin) ortalama OD
 *     • Boya-2 (Eozin/DAB) ortalama OD
 *     • Boya-1 : Boya-2 OD oranı
 *     • H:DAB CIELAB L* oranı
 *     • Doku oranı (arka plan dışı %)
 *     • Boya vektörlerinin QuPath varsayılanında olup olmadığı
 *   Renk dekonvolüsyonu (H&E / H-DAB) tanımlı olmayan görüntüler atlanır.
 *   Sonuçlar <proje>/boya-qc/kohort_boya_qc_<zaman damgası>.csv dosyasına
 *   yazılır. Döngü tamamlandıktan sonra her metriğin kohort medyanı, IQR,
 *   min ve maksimumu hesaplanır; medyan ± 1,5·IQR aralığı dışındaki slaytlar
 *   "metriklerde kohort medyanından uzak" notu ile işaretlenir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   Yalnızca sayısal boya yoğunluk ölçümleri (OD, L*, oran, %) üretir.
 *   Hiçbir klinik eşik, kalite kategorisi ("kötü", "yetersiz") veya yorum
 *   üretmez. Outlier notu tarafsızdır: "metriklerde kohort medyanından uzak".
 *
 * KULLANIM:
 *   1. Bir QuPath projesi açın (her görüntüde boya vektörleri tanımlı olsun).
 *   2. [Extensions → Atölye → Yardımcılar → Kohort Boya OD Drift]
 *      (ya da [Automate → Project scripts → bu betik])
 *   3. Sonuç penceresini inceleyin; CSV'yi R/Python'da açın.
 *
 * ÇIKTI:
 *   • Konsol + sonuç penceresi: sıralı metin tablo + kohort özet satırı + uyarılar
 *   • <proje>/boya-qc/kohort_boya_qc_<millis>.csv: her görüntü bir satır
 *
 * YÖNTEM / KAYNAK:
 *   Dunn C, Brettle D, Hodgson C, Hughes R, Treanor D (2025) "An international
 *   study of stain variability in histopathology using qualitative and
 *   quantitative analysis." J Pathol Inform 17:100423.
 *   doi:10.1016/j.jpi.2025.100423
 *   OD hesabı: ColorTransformer (Stain_1/Stain_2/Optical_density_sum), QuPath 0.6.0+.
 *   CIELAB L*: Beer-Lambert tek-boya yeniden-yapım → sRGB → D65 luminans (IEC 61966-2-1).
 *   Outlier: Tukey 1.5·IQR kuralı.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.regions.RegionRequest
import qupath.lib.color.ColorTransformer
import qupath.lib.color.ColorTransformer.ColorTransformMethod
import java.io.File

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── Sabitler ────────────────────────────────────────────────────────────────
double BG_OD = 0.15d
int    MAX_DIM = 2048

// ── Sonuç penceresi (yardimci-kesisim-alani.groovy ile AYNI desen) ─────────
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

            stage.setScene(new javafx.scene.Scene(root, 820, 600))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── sRGB → CIELAB L* (Beer-Lambert yeniden-yapım → D65 luminans) ────────────
def srgbLin = { double c -> c /= 255.0d; (c <= 0.04045d) ? (c / 12.92d) : Math.pow((c + 0.055d) / 1.055d, 2.4d) }
def labL = { double r, double g, double b ->
    double y = 0.2126d * srgbLin(r) + 0.7152d * srgbLin(g) + 0.0722d * srgbLin(b)
    double fy = (y > 0.008856d) ? Math.cbrt(y) : (7.787d * y + 16.0d / 116.0d)
    return 116.0d * fy - 16.0d
}

// ── Tek görüntüden OD/CIELAB çekirdek metrikleri (yardimci-boya-kalite-qc çekirdeği) ─
def measureStainQC = { imageData ->
    def server = imageData.getServer()
    def stains = imageData.getColorDeconvolutionStains()
    if (stains == null) return null   // renk dekonvolüsyonu yok → atla

    def s1name = stains.getStain(1)?.getName() ?: "Boya1"
    def s2name = stains.getStain(2)?.getName() ?: "Boya2"
    if (stains.getStain(1) == null || stains.getStain(2) == null) return null   // eksik boya vektörü → atla
    double[] svH = stains.getStain(1).getArray()
    double[] svE = stains.getStain(2).getArray()
    double maxR = stains.getMaxRed(), maxG = stains.getMaxGreen(), maxB = stains.getMaxBlue()
    if (!(maxR > 0)) maxR = 255.0d; if (!(maxG > 0)) maxG = 255.0d; if (!(maxB > 0)) maxB = 255.0d

    // Varsayılan vektör tespiti (isimde H/Hematoxylin + Eosin/DAB beklenir)
    def s1l = s1name.toLowerCase(java.util.Locale.ROOT)
    def s2l = s2name.toLowerCase(java.util.Locale.ROOT)
    boolean isDefault = (s1l.contains("hematox") || s1l.startsWith("h")) &&
                        (s2l.contains("eosin") || s2l.contains("dab") || s2l.startsWith("e") || s2l.startsWith("d"))

    int rw = server.getWidth(), rh = server.getHeight()
    double downsample = Math.max(1.0d, Math.max(rw, rh) / (double) MAX_DIM)

    def img
    try { img = server.readRegion(RegionRequest.createInstance(server.getPath(), downsample, 0, 0, rw, rh)) }
    catch (Throwable t) { return null }
    if (img == null) return null

    int iw = img.getWidth(), ih = img.getHeight()
    int[] rgb = img.getRGB(0, 0, iw, ih, null, 0, iw)
    float[] od1 = ColorTransformer.getTransformedPixels(rgb, ColorTransformMethod.Stain_1, null, stains)
    float[] od2 = ColorTransformer.getTransformedPixels(rgb, ColorTransformMethod.Stain_2, null, stains)
    float[] ods = ColorTransformer.getTransformedPixels(rgb, ColorTransformMethod.Optical_density_sum, null, stains)

    double sumOD1 = 0, sumOD2 = 0, sumLH = 0, sumLE = 0
    long nTissue = 0
    for (int i = 0; i < rgb.length; i++) {
        double osum = (double) ods[i]
        if (Double.isNaN(osum) || osum < BG_OD) continue
        double o1 = (double) od1[i]; double o2 = (double) od2[i]
        if (o1 < 0) o1 = 0; if (o2 < 0) o2 = 0
        sumOD1 += o1; sumOD2 += o2
        double rH = maxR * Math.pow(10.0d, -o1 * svH[0]); double gH = maxG * Math.pow(10.0d, -o1 * svH[1]); double bH = maxB * Math.pow(10.0d, -o1 * svH[2])
        double rE = maxR * Math.pow(10.0d, -o2 * svE[0]); double gE = maxG * Math.pow(10.0d, -o2 * svE[1]); double bE = maxB * Math.pow(10.0d, -o2 * svE[2])
        sumLH += labL(rH, gH, bH); sumLE += labL(rE, gE, bE)
        nTissue++
    }
    if (nTissue == 0) return null

    double meanOD1 = sumOD1 / nTissue
    double meanOD2 = sumOD2 / nTissue
    double odRatio = (meanOD2 > 1e-6d) ? (meanOD1 / meanOD2) : Double.NaN
    double meanLH  = sumLH / nTissue
    double meanLE  = sumLE / nTissue
    double lRatio  = (Math.abs(meanLE) > 1e-6d) ? (meanLH / meanLE) : Double.NaN
    double tissueFrac = (double) nTissue / (double) rgb.length

    return [stain1: s1name, stain2: s2name,
            od1: meanOD1, od2: meanOD2, odRatio: odRatio,
            lRatio: lRatio, tissueFrac: tissueFrac, isDefault: isDefault]
}

// ── CSV yardımcıları ─────────────────────────────────────────────────────────
def csvEsc = { Object v ->
    if (v == null) return ''
    def s = v.toString()
    (s.contains(',') || s.contains('"') || s.contains('\n')) ? ('"' + s.replace('"', '""') + '"') : s
}

// ── Medyan + IQR (sıralı liste) ──────────────────────────────────────────────
def median = { List<Double> vals ->
    def s = vals.findAll { !Double.isNaN(it) }.sort()
    if (s.isEmpty()) return Double.NaN
    int n = s.size(); (n % 2 == 0) ? ((s[n/2-1] + s[n/2]) / 2.0d) : (double) s[n/2]
}
def iqr = { List<Double> vals ->
    def s = vals.findAll { !Double.isNaN(it) }.sort()
    if (s.size() < 4) return Double.NaN
    int n = s.size()
    double q1 = (n % 4 == 0) ? ((s[n/4-1] + s[n/4]) / 2.0d) : (double) s[(int)(n/4)]
    double q3 = (n % 4 == 0) ? ((s[3*n/4-1] + s[3*n/4]) / 2.0d) : (double) s[(int)(3*n/4)]
    return q3 - q1
}

// ── Ön kontrol: proje gerekli ─────────────────────────────────────────────────
def project = QP.getProject()
if (project == null) {
    def msg = "Bu betik açık bir QuPath projesi gerektirir.\nÖnce bir proje açın (File → Open Project)."
    if (isHeadless) { println msg; return }
    Dialogs.showErrorMessage("Proje yok", msg); return
}
def entries = project.getImageList()
if (entries == null || entries.isEmpty()) {
    def msg = "Projede hiç görüntü yok."
    if (isHeadless) { println msg; return }
    Dialogs.showErrorMessage("Görüntü yok", msg); return
}

// ── Çıktı dizini: <proje>/boya-qc/ ─────────────────────────────────────────
File outDir
try {
    def parent = project.getPath()?.getParent()
    outDir = (parent != null) ? new File(parent.toFile(), 'boya-qc') : new File(System.getProperty('java.io.tmpdir'), 'boya-qc')
    outDir.mkdirs()
} catch (Throwable t) {
    outDir = new File(System.getProperty('java.io.tmpdir'), 'boya-qc')
    outDir.mkdirs()
}
long millis = System.currentTimeMillis()
File csvFile = new File(outDir, String.format(java.util.Locale.US, "kohort_boya_qc_%d.csv", millis))

// ── CSV başlık sütunları ─────────────────────────────────────────────────────
def COLS = ['image_name','stain_type','stain1','stain2','od1_mean','od2_mean','od_ratio','lab_l_ratio','tissue_frac','default_vectors']

// ── Proje döngüsü ─────────────────────────────────────────────────────────────
def rows = []
int total = entries.size(), skipped = 0, errCount = 0
println String.format(java.util.Locale.US, "Kohort boya QC başlıyor: %d görüntü taranacak...", total)

entries.eachWithIndex { entry, idx ->
    def nm = entry.getImageName()
    println String.format(java.util.Locale.US, "[%d/%d] %s", idx + 1, total, nm)
    try {
        entry.readImageData().withCloseable { ed ->
            def stains = ed.getColorDeconvolutionStains()
            if (stains == null) { println "  → atlandı (renk dekonvolüsyonu tanımlı değil)"; skipped++; return }
            def stainType = ed.getImageType()?.toString() ?: "Bilinmiyor"
            def m = measureStainQC(ed)
            if (m == null) { println "  → atlandı (ölçüm başarısız / doku pikseli yok)"; skipped++; return }
            rows << [image_name: nm, stain_type: stainType,
                     stain1: m.stain1, stain2: m.stain2,
                     od1_mean: m.od1, od2_mean: m.od2, od_ratio: m.odRatio,
                     lab_l_ratio: m.lRatio, tissue_frac: m.tissueFrac,
                     default_vectors: m.isDefault ? "evet" : "hayir"]
        }
    } catch (Throwable t) {
        println String.format(java.util.Locale.US, "  → hata: %s", t.getMessage() ?: t.getClass().getSimpleName())
        errCount++
    }
    System.gc()
}

if (rows.isEmpty()) {
    def msg = String.format(java.util.Locale.US,
        "Hiçbir görüntüden ölçüm alınamadı (atlandı: %d, hata: %d).\nTüm görüntülerin H&E/H-DAB boya vektörleri tanımlı mı?", skipped, errCount)
    if (isHeadless) { println msg; return }
    Dialogs.showErrorMessage("Sonuç yok", msg); return
}

// ── CSV yaz ──────────────────────────────────────────────────────────────────
try {
    csvFile.withWriter('UTF-8') { w ->
        w.writeLine(COLS.collect { csvEsc(it) }.join(','))
        rows.each { r -> w.writeLine(COLS.collect { csvEsc(r[it]) }.join(',')) }
    }
    println "CSV yazıldı: " + csvFile.getAbsolutePath()
} catch (Throwable t) {
    println "CSV yazılamadı: " + (t.getMessage() ?: t.getClass().getSimpleName())
}

// ── Kohort istatistikleri (medyan, IQR, outlier) ─────────────────────────────
def numCols = ['od1_mean','od2_mean','od_ratio','lab_l_ratio','tissue_frac']
def stats = [:]
numCols.each { col ->
    List<Double> vals = rows.collect { r -> def v = r[col]; (v instanceof Number && !Double.isNaN((double)v)) ? (double)v : Double.NaN }
    double med = median(vals); double iq = iqr(vals)
    // Not: Elvis (?:) kullanma — Groovy'de 0.0 "falsy"dir, gerçek 0.0 min/max NaN'a düşerdi.
    def finiteVals = vals.findAll { !Double.isNaN(it) }
    double mn = finiteVals.isEmpty() ? Double.NaN : (double) finiteVals.min()
    double mx = finiteVals.isEmpty() ? Double.NaN : (double) finiteVals.max()
    stats[col] = [med: med, iqr: iq, min: mn, max: mx,
                  lo: (med - 1.5d * iq), hi: (med + 1.5d * iq)]
}

def outlierRows = rows.findAll { r ->
    numCols.any { col ->
        def v = r[col]; if (!(v instanceof Number)) return false
        double d = (double)v; if (Double.isNaN(d)) return false
        def s = stats[col]
        (!Double.isNaN((double)s.lo) && !Double.isNaN((double)s.hi)) && (d < (double)s.lo || d > (double)s.hi)
    }
}

// ── Sonuç metni ──────────────────────────────────────────────────────────────
def sb = new StringBuilder()
sb << "KOHORT BOYA OD DRIFT — SONUÇLAR\n"
sb << "════════════════════════════════════════════════════════════════════\n\n"
sb << String.format(java.util.Locale.US, "Taranan      : %,d görüntü  (atlandı: %d, hata: %d)%n", rows.size(), skipped, errCount)
sb << "\n"
sb << String.format(java.util.Locale.US, "%-42s  %7s  %7s  %7s  %7s%n", "Görüntü", "OD1", "OD2", "ODoran", "L*oran")
sb << "─" * 72 + "\n"
rows.sort { it.image_name }.each { r ->
    def f3 = { v -> (v instanceof Number && !Double.isNaN((double)v)) ? String.format(java.util.Locale.US, "%7.3f", (double)v) : "      —" }
    sb << String.format(java.util.Locale.US, "%-42s  %s  %s  %s  %s%n",
        (r.image_name?.size() > 42 ? r.image_name[0..39] + "…" : r.image_name),
        f3(r.od1_mean), f3(r.od2_mean), f3(r.od_ratio), f3(r.lab_l_ratio))
}
sb << "─" * 72 + "\n"
sb << String.format(java.util.Locale.US, "%-42s  %7s  %7s  %7s  %7s%n", "MEDYAN",
    String.format(java.util.Locale.US, "%7.3f", (double)stats.od1_mean.med),
    String.format(java.util.Locale.US, "%7.3f", (double)stats.od2_mean.med),
    String.format(java.util.Locale.US, "%7.3f", (double)stats.od_ratio.med),
    String.format(java.util.Locale.US, "%7.3f", (double)stats.lab_l_ratio.med))
sb << String.format(java.util.Locale.US, "%-42s  %7s  %7s  %7s  %7s%n", "IQR",
    String.format(java.util.Locale.US, "%7.3f", (double)stats.od1_mean.iqr),
    String.format(java.util.Locale.US, "%7.3f", (double)stats.od2_mean.iqr),
    String.format(java.util.Locale.US, "%7.3f", (double)stats.od_ratio.iqr),
    String.format(java.util.Locale.US, "%7.3f", (double)stats.lab_l_ratio.iqr))
sb << String.format(java.util.Locale.US, "%-42s  %7s  %7s  %7s  %7s%n", "MIN",
    String.format(java.util.Locale.US, "%7.3f", (double)stats.od1_mean.min),
    String.format(java.util.Locale.US, "%7.3f", (double)stats.od2_mean.min),
    String.format(java.util.Locale.US, "%7.3f", (double)stats.od_ratio.min),
    String.format(java.util.Locale.US, "%7.3f", (double)stats.lab_l_ratio.min))
sb << String.format(java.util.Locale.US, "%-42s  %7s  %7s  %7s  %7s%n", "MAKS",
    String.format(java.util.Locale.US, "%7.3f", (double)stats.od1_mean.max),
    String.format(java.util.Locale.US, "%7.3f", (double)stats.od2_mean.max),
    String.format(java.util.Locale.US, "%7.3f", (double)stats.od_ratio.max),
    String.format(java.util.Locale.US, "%7.3f", (double)stats.lab_l_ratio.max))
sb << "\n"
if (outlierRows.isEmpty()) {
    sb << "Tüm görüntüler kohort medyanının 1,5·IQR aralığı içinde.\n"
} else {
    sb << String.format(java.util.Locale.US, "Kohort medyanından uzak slaytlar (%d adet) — 1,5·IQR Tukey kuralı:%n", outlierRows.size())
    outlierRows.each { r ->
        def flags = numCols.findAll { col ->
            def v = r[col]; if (!(v instanceof Number)) return false
            double d = (double)v; if (Double.isNaN(d)) return false
            def s = stats[col]; (!Double.isNaN((double)s.lo) && !Double.isNaN((double)s.hi)) && (d < (double)s.lo || d > (double)s.hi)
        }
        sb << String.format(java.util.Locale.US, "  %-44s → metriklerde kohort medyanından uzak (%s)%n",
            (r.image_name?.size() > 44 ? r.image_name[0..41] + "…" : r.image_name),
            flags.join(", "))
    }
}
sb << "\nCSV: " + csvFile.getAbsolutePath() + "\n"
sb << "\nYöntem: Dunn ve ark. 2025, J Pathol Inform 17:100423; Tukey 1.5·IQR.\n"
sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik yorum içermez."

showResultWindow("Kohort Boya OD Drift", sb.toString())
println String.format(java.util.Locale.US,
    "✓ Kohort boya QC tamamlandı: %d görüntü, %d outlier. CSV: %s",
    rows.size(), outlierRows.size(), csvFile.getAbsolutePath())
