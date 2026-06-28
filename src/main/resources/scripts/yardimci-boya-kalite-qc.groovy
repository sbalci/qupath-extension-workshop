/**
 * Yardımcı - Boya kalitesi QC ölçümü (H:E oranı + yoğunluk + CIELAB L*)
 * --------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Seçili bölgede (yoksa tüm slaytta) H&E/İHK boya KALİTESİNİ niceler. QuPath'in
 *   renk dekonvolüsyonuyla doku piksellerinde:
 *     • Boya-1 (Hematoksilin) ve Boya-2 (Eozin/DAB) ortalama optik yoğunluğu (OD)
 *     • Boya-1 : Boya-2 OD oranı (yoğunluk dengesi)
 *     • Dekonvolve tek-boya yeniden-yapımının CIELAB **L*** değeri ve **H:E L* oranı**
 *       — Dunn ve ark. (2025) yöntemine yakın; H&E için optimal aralık **0,94–0,99**.
 *     • Doku oranı (arka-plan dışı %).
 *   Tarayıcıdan-tarayıcıya (ör. GT450 ↔ AT2) ve zaman içinde boya sürüklenmesini
 *   karşılaştırmak için bir KK aracıdır.
 *
 * NE ÖLÇMEZ (sınır):
 *   Yönlü ΔE (dΔE) **çok-slaytlı bir referans havuzu** gerektirir (Dunn'da tüm
 *   laboratuvarların ortalaması); tek bölge/slayttan hesaplanmaz — bu yardımcının
 *   kapsamı dışındadır. Yalnız ölçüm üretir; hiçbir klinik eşik/yorum üretmez.
 *
 * KULLANIM:
 *   1. Parlak-alan bir H&E (ya da İHK) slaydı açın; boya vektörleri tanımlı olmalı
 *      (Image type = Brightfield H&E/H-DAB; gerekirse Boya vektörleri sihirbazı).
 *   2. (Önerilir) temsilî bir doku bölgesini anote edip SEÇİN; yoksa tüm slayt kullanılır.
 *   3. [Extensions → Atölye → Yardımcılar → Boya kalitesi QC ölçümü]
 *
 * YÖNTEM / KAYNAK:
 *   Dunn C, Brettle D, Hodgson C, Hughes R, Treanor D (2025) "An international study of
 *   stain variability in histopathology using qualitative and quantitative analysis."
 *   J Pathol Inform 17:100423. doi:10.1016/j.jpi.2025.100423.  (Ek A — Boya normalizasyonu)
 *   API: ColorTransformer.getTransformedPixels (Stain_1/Stain_2/Optical_density_sum), QuPath 0.6.0+.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.regions.RegionRequest
import qupath.lib.color.ColorTransformer
import qupath.lib.color.ColorTransformer.ColorTransformMethod

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// Atölye varsayılanları
double BG_OD = 0.15d        // Optical density sum < bu → arka plan (doku değil)
int    MAX_DIM = 2048       // bölgeyi bu en-büyük-kenara indirger (hız + bellek)

// ── Sonuç penceresi (atölye standart deseni) ───────────────────────────────
def showResultWindow = { String windowTitle, String windowBody ->
    if (isHeadless) { println "=== ${windowTitle} ===\n${windowBody}\n=================="; return }
    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle(windowTitle); stage.setAlwaysOnTop(true)
            def textArea = new javafx.scene.control.TextArea(windowBody)
            textArea.setEditable(false); textArea.setWrapText(false)
            textArea.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;")
            def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut"); alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener({ obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
            def copyBtn = new javafx.scene.control.Button("Kopyala")
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent(); content.putString(windowBody); cb.setContent(content)
            })
            def closeBtn = new javafx.scene.control.Button("Kapat"); closeBtn.setDefaultButton(true); closeBtn.setOnAction({ stage.close() })
            def spacer = new javafx.scene.layout.Region(); javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, copyBtn, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT); buttons.setPadding(new javafx.geometry.Insets(8))
            def root = new javafx.scene.layout.BorderPane(); root.setCenter(textArea)
            def __footer = new javafx.scene.control.Label("QuPath Atölye Scriptleri · araştırma/eğitim amaçlı")
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons); __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)
            stage.setScene(new javafx.scene.Scene(root, 720, 560)); stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── sRGB (0–255) → CIELAB L* (D65; yalnız L* gerekir → luminans yeterli) ────
def srgbLin = { double c -> c /= 255.0d; return (c <= 0.04045d) ? (c / 12.92d) : Math.pow((c + 0.055d) / 1.055d, 2.4d) }
def labL = { double r, double g, double b ->
    double y = 0.2126d * srgbLin(r) + 0.7152d * srgbLin(g) + 0.0722d * srgbLin(b)   // Yn = 1 (D65)
    double fy = (y > 0.008856d) ? Math.cbrt(y) : (7.787d * y + 16.0d / 116.0d)
    return 116.0d * fy - 16.0d
}

// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    if (isHeadless) { println "Görüntü açık değil."; return }
    Dialogs.showErrorMessage("Görüntü yok", "Önce bir slayt açın."); return
}
def server = imageData.getServer()
def stains = imageData.getColorDeconvolutionStains()
if (stains == null) {
    def msg = "Bu yardımcı renk dekonvolüsyonu (parlak-alan H&E / H-DAB) gerektirir; boya vektörleri tanımlı değil.\n" +
              "[Image → Set image type] ile Brightfield seçin ya da Boya vektörleri sihirbazını çalıştırın."
    if (isHeadless) { println msg; return }
    Dialogs.showErrorMessage("Boya vektörü yok", msg); return
}
def s1name = stains.getStain(1)?.getName() ?: "Boya 1"
def s2name = stains.getStain(2)?.getName() ?: "Boya 2"
boolean isHE = (s2name.toLowerCase(java.util.Locale.ROOT).contains("eosin") || s2name.toLowerCase(java.util.Locale.ROOT).contains("eozin"))
double[] svH = stains.getStain(1).getArray()
double maxR = stains.getMaxRed(), maxG = stains.getMaxGreen(), maxB = stains.getMaxBlue()
if (!(maxR > 0)) maxR = 255.0d; if (!(maxG > 0)) maxG = 255.0d; if (!(maxB > 0)) maxB = 255.0d

// ── Bölgeyi seç: seçili alan anotasyonu, yoksa tüm slayt ────────────────────
def sel = imageData.getHierarchy().getSelectionModel().getSelectedObjects().findAll { it.isAnnotation() && it.getROI()?.isArea() }
def roi = sel.isEmpty() ? null : sel.iterator().next().getROI()
int rx, ry, rw, rh
if (roi != null) { rx = (int) Math.max(0, roi.getBoundsX()); ry = (int) Math.max(0, roi.getBoundsY()); rw = (int) Math.min(server.getWidth()-rx, Math.ceil(roi.getBoundsWidth())); rh = (int) Math.min(server.getHeight()-ry, Math.ceil(roi.getBoundsHeight())) }
else { rx = 0; ry = 0; rw = server.getWidth(); rh = server.getHeight() }
if (rw <= 0 || rh <= 0) { if (!isHeadless) Dialogs.showErrorMessage("Geçersiz bölge", "Bölge boyutu okunamadı."); else println "Geçersiz bölge."; return }
double downsample = Math.max(1.0d, Math.max(rw, rh) / (double) MAX_DIM)

def img
try { img = server.readRegion(RegionRequest.createInstance(server.getPath(), downsample, rx, ry, rw, rh)) }
catch (Throwable t) { def m = "Bölge okunamadı: " + (t.getMessage() ?: t.getClass().getSimpleName()); if (!isHeadless) Dialogs.showErrorMessage("Okuma hatası", m); else println m; return }
if (img == null) { if (!isHeadless) Dialogs.showErrorMessage("Okuma hatası", "Bölge boş okundu."); else println "Bölge boş."; return }

int iw = img.getWidth(), ih = img.getHeight()
int[] rgb = img.getRGB(0, 0, iw, ih, null, 0, iw)

// ── Toplu dekonvolüsyon: OD (Stain_1, Stain_2, OD-sum) ──────────────────────
float[] od1 = ColorTransformer.getTransformedPixels(rgb, ColorTransformMethod.Stain_1, null, stains)
float[] od2 = ColorTransformer.getTransformedPixels(rgb, ColorTransformMethod.Stain_2, null, stains)
float[] ods = ColorTransformer.getTransformedPixels(rgb, ColorTransformMethod.Optical_density_sum, null, stains)

double sumOD1 = 0, sumOD2 = 0, sumLH = 0, sumLE = 0
long nTissue = 0
double[] svE = stains.getStain(2).getArray()
for (int i = 0; i < rgb.length; i++) {
    double osum = (double) ods[i]
    if (Double.isNaN(osum) || osum < BG_OD) continue            // arka plan → atla
    double o1 = (double) od1[i]; double o2 = (double) od2[i]
    if (o1 < 0) o1 = 0; if (o2 < 0) o2 = 0
    sumOD1 += o1; sumOD2 += o2
    // Tek-boya yeniden-yapım (Beer-Lambert, taban 10) → CIELAB L*
    double rH = maxR * Math.pow(10.0d, -o1 * svH[0]); double gH = maxG * Math.pow(10.0d, -o1 * svH[1]); double bH = maxB * Math.pow(10.0d, -o1 * svH[2])
    double rE = maxR * Math.pow(10.0d, -o2 * svE[0]); double gE = maxG * Math.pow(10.0d, -o2 * svE[1]); double bE = maxB * Math.pow(10.0d, -o2 * svE[2])
    sumLH += labL(rH, gH, bH); sumLE += labL(rE, gE, bE)
    nTissue++
}

if (nTissue == 0) {
    def m = "Doku pikseli bulunamadı (arka plan eşiği OD < " + BG_OD + ").\nDaha dokulu bir bölge seçin ya da eşiği gözden geçirin."
    if (!isHeadless) Dialogs.showErrorMessage("Doku yok", m); else println m; return
}

double meanOD1 = sumOD1 / nTissue, meanOD2 = sumOD2 / nTissue
double odRatio = (meanOD2 > 1e-6) ? (meanOD1 / meanOD2) : Double.NaN
double meanLH = sumLH / nTissue, meanLE = sumLE / nTissue
double lRatio = (meanLE != 0) ? (meanLH / meanLE) : Double.NaN
double tissueFrac = (double) nTissue / (double) rgb.length

String heVerdict
if (isHE && !Double.isNaN(lRatio)) {
    if (lRatio >= 0.94d && lRatio <= 0.99d) heVerdict = "0,94–0,99 aralığında (Dunn 2025 optimal)"
    else if (lRatio < 0.94d) heVerdict = "< 0,94 — hematoksilin baskın (koyu çekirdek eğilimi)"
    else heVerdict = "> 0,99 — hematoksilin zayıf / eozin baskın"
} else {
    heVerdict = isHE ? "(hesaplanamadı)" : "(yalnız H&E için Dunn referansı; bu görüntü " + s1name + "/" + s2name + ")"
}

def sb = new StringBuilder()
sb << "BOYA KALİTESİ QC ÖLÇÜMÜ\n"
sb << "═══════════════════════\n\n"
sb << "Görüntü : " << (server.getMetadata().getName() ?: "(adsız)") << "\n"
sb << "Bölge   : " << (roi != null ? "seçili anotasyon" : "tüm slayt") << String.format(java.util.Locale.US, "  (%,d × %,d px, downsample %.2f)%n", rw, rh, downsample)
sb << String.format(java.util.Locale.US, "Doku    : %,d piksel  (%.1f%% örneklenen alan; OD-sum ≥ %.2f)%n", nTissue, 100.0d*tissueFrac, BG_OD)
sb << "\n📊  Yoğunluk (optik yoğunluk, OD)\n"
sb << "──────────────────────────────\n"
sb << String.format(java.util.Locale.US, "  %-14s : %.3f OD%n", s1name, meanOD1)
sb << String.format(java.util.Locale.US, "  %-14s : %.3f OD%n", s2name, meanOD2)
sb << String.format(java.util.Locale.US, "  %s:%s OD oranı : %.3f%n", s1name, s2name, odRatio)
sb << "\n🎨  CIELAB L* (dekonvolve tek-boya yeniden-yapım)\n"
sb << "────────────────────────────────────────────\n"
sb << String.format(java.util.Locale.US, "  L* %-12s : %.1f%n", s1name, meanLH)
sb << String.format(java.util.Locale.US, "  L* %-12s : %.1f%n", s2name, meanLE)
sb << String.format(java.util.Locale.US, "  H:E L* oranı   : %.3f  →  %s%n", lRatio, heVerdict)
sb << "\n📝 Yorum (ölçüm, yorum değil)\n"
sb << "──────────────────────────\n"
sb << "  • OD ↑ = boya daha koyu/yoğun; OD ↓ = soluk/zayıf boyama.\n"
sb << "  • Aynı ölçümü TARAYICI başına (GT450 ↔ AT2) ve ZAMAN içinde izleyin;\n"
sb << "    eşik dışı slaytları işaretleyip (gerekirse) boya normalizasyonu uygulayın (Ek A §8).\n"
sb << "  • Yönlü ΔE (dΔE) burada YOK: çok-slaytlı bir referans havuzu gerektirir (Dunn 2025).\n"
sb << "\nYöntem: Dunn ve ark. 2025, J Pathol Inform 17:100423 (doi:10.1016/j.jpi.2025.100423).\n"
sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik yorum içermez."

showResultWindow("Boya kalitesi QC — Tamamlandı 🎨", sb.toString())
println String.format(java.util.Locale.US, "Boya kalitesi QC: %s OD %.3f, %s OD %.3f, H:E L* oranı %.3f (%,d doku piksel)", s1name, meanOD1, s2name, meanOD2, lRatio, nTissue)
