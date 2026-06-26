/**
 * Yardımcı - Alan-Bazlı Pozitiflik (% positivity)
 * ------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Seçili anotasyon(lar) içinde, kaydedilmiş bir EŞİK piksel-sınıflandırıcısı
 * kullanarak **DAB-pozitif alan oranını** (% pozitif alan) hesaplar. Hücre
 * tespiti YAPMAZ — bu, "percent positivity / area% load" yaklaşımıdır
 * (yaygın stainler, nöropatoloji p-tau/amiloid, diffüz membranöz boyalar).
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Pozitif boya ALANININ doku alanına oranını ölçer (% pozitif alan).
 *     Bu bir ORAN/ALANdır — klinik skor, H-score, eşik veya yorum DEĞİL.
 *   • Eşik DEĞERLERİ sınıflandırıcının içinde tanımlıdır; kullanıcı
 *     kalibre eder (bkz. Ek R).
 *
 * ÖN KOŞUL — eşik sınıflandırıcısı (bir kez, GUI'de):
 *   [Classify → Pixel classification → Create thresholder]
 *     • Resolution: orta (örn. ~1-2 µm/px)
 *     • Channel: DAB
 *     • Threshold: dokunuza göre ayarlayın (başlangıç ~0.20 DAB OD)
 *     • Above threshold sınıfı adını "Pozitif" yapın
 *     • [Save] → adı "DAB-pozitif" verin
 *   Ayrıntı: Ek R — Alan-bazlı pozitiflik.
 *
 * KULLANIM:
 *   1. DAB-İHK slaytını açın, Image type → "Brightfield (H-DAB)"
 *   2. Bölgeyi anotasyon olarak çizin ve SEÇİN (ölçülecek anotasyonlar açıkça seçilmelidir)
 *   3. [Automate → Project scripts → bu betik]
 *
 * ÇIKTI:
 *   • Her anotasyona: "% pozitif alan" + sınıflandırıcının eklediği alan ölçümleri
 *   • Sonuç penceresinde özet
 *
 * YÖNTEM REFERANSLARI:
 *   • Gonzalez AD et al. (2025), J Neuropathol Exp Neurol 84(8):692–706 — %
 *     pozitiflik en güvenilir tau metriği; QuPath Braak korelasyonu. doi:10.1093/jnen/nlaf026
 *   • Finney CA et al. (2020), J Neurosci Methods 348:108994 — ölçeklenebilir
 *     bölgesel İHK % kantifikasyonu (astrosit). doi:10.1016/j.jneumeth.2020.108994
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
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
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
String classifierName = "DAB-pozitif"   // GUI'de oluşturulan eşik sınıflandırıcısı
String positiveClass  = "Pozitif"        // sınıflandırıcının eşik-üstü sınıf adı
String summaryName    = "Alan-pozitiflik Özet"

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir DAB-İHK slaytı açın.")
    return
}

def selected = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getName() != summaryName }
def targets = selected
if (targets.isEmpty()) {
    def msg = "Ölçülecek anotasyon yok.\n\nÖnce bölgeyi anotasyon olarak çizin (ve seçin)."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Anotasyon yok", msg)
    return
}

// ── 2) Eşik sınıflandırıcısını yükle ────────────────────────────────
def classifier
try {
    classifier = QP.loadPixelClassifier(classifierName)
} catch (Throwable t) {
    classifier = null
}
if (classifier == null) {
    def msg = "Eşik sınıflandırıcısı bulunamadı: \"${classifierName}\"\n\n" +
              "Bir kez oluşturmanız gerekir:\n" +
              "  [Classify → Pixel classification → Create thresholder]\n" +
              "  • Channel: DAB, eşik-üstü sınıf adı: \"${positiveClass}\"\n" +
              "  • [Save] → ad: \"${classifierName}\"\n\n" +
              "Ayrıntı: Ek R — Alan-bazlı pozitiflik."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Sınıflandırıcı yok", msg)
    return
}

// ── 3) Alan ölçümlerini ekle ────────────────────────────────────────
println "Eşik sınıflandırıcısı ile alan ölçümü..."
QP.selectObjects(targets)
QP.addPixelClassifierMeasurements(classifier, classifierName)

// ── 4) % pozitif alanı hesapla ──────────────────────────────────────
// addPixelClassifierMeasurements her sınıf için "<ad>: <Sınıf> area µm^2"
// benzeri ölçümler ekler. Pozitif sınıfın alanını / tüm sınıfların alanına böleriz.
def areaKeyFor = { ann, String cls ->
    // startsWith(classifierName) guard: a second thresholder whose positive class is
    // also named "Pozitif" (QuPath's default) would otherwise let an unrelated key win
    // the unordered find() — silently picking the wrong classifier's area. Mirrors
    // allAreaKeysFor below so numerator and denominator come from the same classifier.
    def k = ann.measurements.keySet().find {
        it.startsWith(classifierName) && it.toLowerCase(java.util.Locale.ROOT).contains(cls.toLowerCase(java.util.Locale.ROOT)) && it.toLowerCase(java.util.Locale.ROOT).contains("area")
    }
    k != null ? (ann.measurements[k] ?: Double.NaN) as double : Double.NaN
}
def allAreaKeysFor = { ann ->
    ann.measurements.keySet().findAll {
        it.startsWith(classifierName) && it.toLowerCase(java.util.Locale.ROOT).contains("area")
    }
}

int withData = 0
double totalPosArea = 0.0
double totalAllArea = 0.0

targets.each { ann ->
    double posArea = areaKeyFor(ann, positiveClass)
    double allArea = 0.0
    allAreaKeysFor(ann).each { k -> allArea += ((ann.measurements[k] ?: 0.0) as double) }

    if (!Double.isNaN(posArea) && allArea > 0) {
        withData++
        double pct = 100.0 * posArea / allArea
        ann.measurements['% pozitif alan'] = pct
        totalPosArea += posArea
        totalAllArea += allArea
    }
}
QP.fireHierarchyUpdate()

if (withData == 0) {
    def msg = "Sınıflandırıcı çalıştı ama beklenen alan ölçümleri bulunamadı.\n\n" +
              "Eşik sınıflandırıcısının eşik-üstü sınıf adının \"${positiveClass}\" olduğundan\n" +
              "emin olun (Ek R). Sınıflandırıcıyı yeniden kaydedip tekrar deneyin."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Ölçüm yok", msg)
    return
}

double overallPct = totalAllArea > 0 ? (100.0 * totalPosArea / totalAllArea) : 0.0

// ── 5) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "ALAN-BAZLI POZİTİFLİK (% positivity)\n"
body << "═════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Anotasyon (ölçülen)  : %d%n", withData)
body << String.format(java.util.Locale.US, "Pozitif alan         : %.4f µm²%n", totalPosArea)
body << String.format(java.util.Locale.US, "Toplam sınıflı alan  : %.4f µm²%n", totalAllArea)
body << String.format(java.util.Locale.US, "%% pozitif alan       : %.2f %%%n", overallPct)
body << "\n"
body << "Sınıflandırıcı: \"" << classifierName << "\" (eşikleri Ek R'ye göre kalibre edin)\n"
body << "Bu bir ORAN/ALANdır — klinik skor, H-score veya yorum DEĞİL.\n"
body << "(Gonzalez 2025; Finney 2020; Bankhead 2017)\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Alan-bazlı pozitiflik", body.toString())
println "✓ % pozitif alan yazıldı (her anotasyon)."
