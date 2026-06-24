/**
 * Yardımcı - Boya Vektörlerini Kontrol Et
 * ------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Aktif görüntünün renk-dekonvolüsyon (boya) vektörlerini OKUR ve raporlar;
 * bu vektörlerin QuPath VARSAYILANI mı yoksa bu slayttan tahmin/kalibre mi
 * edilmiş olduğunu işaretler. Görüntüyü DEĞİŞTİRMEZ — yalnız raporlar ve yön
 * gösterir (tahmini kendisi yapmaz).
 *
 * NEDEN ÖNEMLİ:
 *   • Boya vektörleri tarayıcı/laboratuvar/protokole özgüdür. Kantitatif H-DAB
 *     ölçümünde (Modül 3, 3b, 4, 5, 7) eşikler MUTLAK DAB OD üzerindendir;
 *     yanlış vektör her skoru kaydırır.
 *   • QuPath'in yerleşik varsayılanları bir BAŞLANGIÇ noktasıdır, sizin
 *     tarayıcınız için bir kalibrasyon değildir. Bu betik vektörlerin hâlâ
 *     varsayılan olup olmadığını söyler.
 *
 * BOYA AYRIMI ≠ BOYA NORMALİZASYONU:
 *   • Ayrım (bu betik): bu slaydı doğru OKUMAK için kanallara kalibre eder.
 *   • Normalizasyon: slaytları birbirine BENZETMEK için yeniden renklendirir
 *     (Macenko/Reinhard/Vahadane — bkz. Ek A).
 *
 * KULLANIM:
 *   1. İHK/H&E slaytını açın; Image type'ı brightfield yapın
 *      (gerekirse: Yardımcılar → Görüntü tipi ayarla)
 *   2. [Extensions → Atölye → Yardımcılar → Boya vektörlerini kontrol et]
 *      (ya da [Automate → Project scripts → bu betik])
 *   3. Tahmin için: temsilî bir bölge (iki boya + biraz arka plan) seçip
 *      [Image → Estimate stain vectors] çalıştırın, sonra bu betiği tekrar açın.
 *
 * YÖNTEM REFERANSLARI:
 *   • Ruifrok AC, Johnston DA (2001), Anal Quant Cytol Histol 23(4):291–299 —
 *     renk dekonvolüsyonu.
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP

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

            stage.setScene(new javafx.scene.Scene(root, 760, 560))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Referans varsayılan vektörler (QuPath; birim/normalize OD) ───────
// Çalışma anında fabrika ile üretmeyi dener; olmazsa bu tabloya düşer.
double[] DEF_HEMA  = [0.651d, 0.701d, 0.290d] as double[]
double[] DEF_EOSIN = [0.216d, 0.801d, 0.558d] as double[]
double[] DEF_DAB   = [0.269d, 0.568d, 0.776d] as double[]
double TOL = 0.02d

def vecOf = { stain ->
    [stain.getRed() as double, stain.getGreen() as double, stain.getBlue() as double] as double[]
}
def vecClose = { double[] a, double[] b ->
    (Math.abs(a[0] - b[0]) <= TOL) && (Math.abs(a[1] - b[1]) <= TOL) && (Math.abs(a[2] - b[2]) <= TOL)
}
def fmtVec = { double[] v ->
    String.format(java.util.Locale.US, "r=%.4f  g=%.4f  b=%.4f", v[0], v[1], v[2])
}

// Fabrika varsayılanlarını dene (sürüm sürmez); başarısız olursa tablo kalır.
try {
    def cds = Class.forName("qupath.lib.color.ColorDeconvolutionStains")
    def enumCls = Class.forName("qupath.lib.color.ColorDeconvolutionStains\$DefaultColorDeconvolutionStains")
    def makeDefault = { String enumName ->
        def enumVal = Enum.valueOf(enumCls, enumName)
        cds.getMethod("makeDefaultColorDeconvolutionStains", enumCls).invoke(null, enumVal)
    }
    def heDef = makeDefault("H_E")
    def dabDef = makeDefault("H_DAB")
    DEF_HEMA  = vecOf(heDef.getStain(1))
    DEF_EOSIN = vecOf(heDef.getStain(2))
    DEF_DAB   = vecOf(dabDef.getStain(2))
} catch (Throwable ignore) {
    // tablo değerleri kullanılır
}

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir slayt açın.")
    return
}

def stains = imageData.getColorDeconvolutionStains()
if (stains == null) {
    def msg = "Bu görüntüde renk-dekonvolüsyon (boya) vektörleri tanımlı değil.\n\n" +
              "Olası neden: görüntü tipi brightfield değil (örn. floresan/diğer).\n\n" +
              "Çözüm:\n" +
              "  • [Extensions → Atölye → Yardımcılar → Görüntü tipi ayarla]\n" +
              "    ile tipi Brightfield (H&E) ya da Brightfield (H-DAB) yapın.\n" +
              "  • Sonra temsilî bir bölge seçip [Image → Estimate stain vectors]."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Boya vektörü yok", msg)
    return
}

// ── 2) Vektörleri oku ───────────────────────────────────────────────
def s1 = stains.getStain(1)
def s2 = stains.getStain(2)
def s3 = stains.getStain(3)

def n2 = (s2?.getName() ?: "").toLowerCase(java.util.Locale.ROOT)
boolean isHE   = n2.contains("eosin")
boolean isHDAB = n2.contains("dab")

double[] v1 = vecOf(s1)
double[] v2 = vecOf(s2)

// ── 3) Varsayılan mı, kalibre mi? ───────────────────────────────────
String verdict
boolean unknownType = false
if (isHE) {
    boolean def1 = vecClose(v1, DEF_HEMA)
    boolean def2 = vecClose(v2, DEF_EOSIN)
    verdict = (def1 && def2)
        ? "⚠ Vektörler QuPath H&E VARSAYILANI ile aynı — bu slayttan tahmin EDİLMEMİŞ olabilir."
        : "✓ Vektörler varsayılandan farklı — muhtemelen kalibre/tahmin edilmiş."
} else if (isHDAB) {
    boolean def1 = vecClose(v1, DEF_HEMA)
    boolean def2 = vecClose(v2, DEF_DAB)
    verdict = (def1 && def2)
        ? "⚠ Vektörler QuPath H-DAB VARSAYILANI ile aynı — bu slayttan tahmin EDİLMEMİŞ olabilir."
        : "✓ Vektörler varsayılandan farklı — muhtemelen kalibre/tahmin edilmiş."
} else {
    unknownType = true
    verdict = "ℹ Boya tipi otomatik tanınamadı (H&E/H-DAB değil). Vektörleri elle doğrulayın."
}

// ── 4) Raporu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "BOYA (RENK-DEKONVOLÜSYON) VEKTÖRLERİ\n"
body << "═════════════════════════════════════\n\n"
body << "Set adı       : " << (stains.getName() ?: "(adsız)") << "\n"
body << "Boya tipi     : " << (isHE ? "H&E" : (isHDAB ? "H-DAB" : "özel/bilinmiyor")) << "\n\n"
body << String.format(java.util.Locale.US, "Stain 1  %-14s %s%n", (s1?.getName() ?: "?"), fmtVec(v1))
body << String.format(java.util.Locale.US, "Stain 2  %-14s %s%n", (s2?.getName() ?: "?"), fmtVec(v2))
if (s3 != null) {
    String s3label = s3.isResidual() ? (s3.getName() + " (residual)") : s3.getName()
    body << String.format(java.util.Locale.US, "Stain 3  %-14s %s%n", s3label, fmtVec(vecOf(s3)))
}
body << String.format(java.util.Locale.US, "%nArka plan (maks RGB) : %.1f, %.1f, %.1f%n",
        stains.getMaxRed() as double, stains.getMaxGreen() as double, stains.getMaxBlue() as double)
body << "\n"
body << verdict << "\n\n"
if (!unknownType) {
    body << "Karşılaştırma (varsayılan, ±" << String.format(java.util.Locale.US, "%.2f", TOL) << "):\n"
    body << String.format(java.util.Locale.US, "  Hematoxylin varsayılan : %s%n", fmtVec(DEF_HEMA))
    body << String.format(java.util.Locale.US, "  %-22s : %s%n",
            (isHE ? "Eosin varsayılan" : "DAB varsayılan"), fmtVec(isHE ? DEF_EOSIN : DEF_DAB))
    body << "\n"
}
body << "Öneri: kantitatif H-DAB ölçümünde boya vektörlerini HER SLAYTTAN\n"
body << "(ya da aynı boyama/tarayıcı ile doğrulanmış bir grup için bir kez) tahmin edin.\n"
body << "Tahmin için temsilî bir bölge (iki boya + biraz arka plan) seçip:\n"
body << "  [Image → Estimate stain vectors]\n"
body << "Boya normalizasyonu (slaytları birbirine benzetme) ayrı bir adımdır — bkz. Ek A.\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Boya vektörleri", body.toString())
println "✓ Boya vektörleri raporlandı (görüntü değiştirilmedi)."
