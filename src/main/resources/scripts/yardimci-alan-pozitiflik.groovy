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
 *     kalibre eder (bkz. Alan-bazlı Pozitiflik eki).
 *
 * ÖN KOŞUL — eşik sınıflandırıcısı (bir kez, GUI'de):
 *   [Classify → Pixel classification → Create thresholder]
 *     • Resolution: orta (örn. ~1-2 µm/px)
 *     • Channel: DAB
 *     • Threshold: dokunuza göre ayarlayın (başlangıç ~0.20 DAB OD)
 *     • Above threshold sınıfı adını "Pozitif" yapın
 *     • [Save] → adı "DAB-pozitif" verin
 *   Ayrıntı: Alan-bazlı pozitiflik eki.
 *
 * KULLANIM:
 *   1. DAB-İHK lamını açın, Image type → "Brightfield (H-DAB)"
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
 *   • Finney CA et al. (2021), J Neurosci Methods 348:108994 — ölçeklenebilir
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

            def alwaysTop = new javafx.scene.control.CheckBox(workshopText('script.70ca64c0c43a', "Üstte tut"))
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )

            def copyBtn = new javafx.scene.control.Button(workshopText('script.a8bcca42d9ce', "Kopyala"))
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent()
                content.putString(windowBody)
                cb.setContent(content)
            })

            def closeBtn = new javafx.scene.control.Button(workshopText('script.7b31a9fc4816', "Kapat"))
            closeBtn.setDefaultButton(true)
            closeBtn.setOnAction({ stage.close() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)

            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, copyBtn, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(textArea)
            def __footer = new javafx.scene.control.Label(workshopText('script.be46103cef99', "QuPath Atölye Betikleri · araştırma/eğitim amaçlı"))
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
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.284651a2e0c4', "Önce bir DAB-İHK lamı açın."))
    return
}

def selected = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getName() != summaryName }
def targets = selected
if (targets.isEmpty()) {
    def msg = workshopText('script.b01d7ce7f65a', 'Ölçülecek anotasyon yok.\n\nÖnce bölgeyi anotasyon olarak çizin (ve seçin).')
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.469472ee53f3', "Anotasyon yok"), msg)
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
    def msg = String.format(java.util.Locale.US, workshopText('script.eb522099263d', 'Eşik sınıflandırıcısı bulunamadı: "%s"\n\n'), classifierName) +
              workshopText('script.7e76e0e94a55', 'Bir kez oluşturmanız gerekir:\n') +
              "  [Classify → Pixel classification → Create thresholder]\n" +
              String.format(java.util.Locale.US, workshopText('script.96e6e78742cf', '  • Channel: DAB, eşik-üstü sınıf adı: "%s"\n'), positiveClass) +
              "  • [Save] → ad: \"${classifierName}\"\n\n" +
              workshopText('script.4f15dfc6222b', 'Ayrıntı: Alan-bazlı pozitiflik eki.')
    if (isHeadless) println msg else Dialogs.showErrorMessage(workshopText('script.9b963a692abd', "Sınıflandırıcı yok"), msg)
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
        // Sınıf adı YALNIZ sınıflandırıcı önekinden SONRAKİ kısımda aranır: öntanımlı ad
        // "DAB-pozitif" kendisi "pozitif" içerir → tüm anahtar üzerinde aramak "…: Negatif area"
        // anahtarını da eşleştirir ve pay sessizce yanlış sınıfın alanı olabilirdi.
        it.startsWith(classifierName) &&
            it.substring(classifierName.length()).toLowerCase(java.util.Locale.ROOT).contains(cls.toLowerCase(java.util.Locale.ROOT)) &&
            it.toLowerCase(java.util.Locale.ROOT).contains("area")
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
    def msg = workshopText('script.270de2c387c6', 'Sınıflandırıcı çalıştı ama beklenen alan ölçümleri bulunamadı.\n\n') +
              String.format(java.util.Locale.US, workshopText('script.4f725be33621', 'Eşik sınıflandırıcısının eşik-üstü sınıf adının "%s" olduğundan\n'), positiveClass) +
              workshopText('script.a2ab6619a0b5', 'emin olun (Alan-bazlı Pozitiflik eki). Sınıflandırıcıyı yeniden kaydedip tekrar deneyin.')
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.e3dc9a444a25', "Ölçüm yok"), msg)
    return
}

double overallPct = totalAllArea > 0 ? (100.0 * totalPosArea / totalAllArea) : 0.0

// ── 5) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << workshopText('script.8161fc9039bd', 'ALAN-BAZLI POZİTİFLİK (% positivity)\n')
body << "═════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, workshopText('script.32b388a2a684', 'Anotasyon (ölçülen)  : %d%n'), withData)
body << String.format(java.util.Locale.US, workshopText('script.2d90c28ea9e3', 'Pozitif alan         : %.4f µm²%n'), totalPosArea)
body << String.format(java.util.Locale.US, workshopText('script.35ee81bc2252', 'Toplam sınıflı alan  : %.4f µm²%n'), totalAllArea)
body << String.format(java.util.Locale.US, workshopText('script.592c1314e95a', '%% pozitif alan       : %.2f %%%n'), overallPct)
body << "\n"
body << workshopText('script.d191f2d163c9', 'Sınıflandırıcı: "') << classifierName << workshopText('script.c68433f46e82', '" (eşikleri Alan temelli pozitiflik ekine göre kalibre edin)\n')
body << workshopText('script.683e74e5677e', 'Bu bir ORAN/ALANdır — klinik skor, H-score veya yorum DEĞİL.\n')
body << "(Gonzalez 2025; Finney 2021; Bankhead 2017)\n\n"
body << workshopText('script.cf6099f0a005', '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')

showResultWindow(workshopText('script.19c0e0429cf2', 'Alan temelli pozitiflik'), body.toString())
println "✓ % pozitif alan yazıldı (her anotasyon)."

// Resolve extension UI language; standalone scripts keep their Turkish fallback.
private String workshopText(String key, String fallback) {
    try {
        def bundle = Class.forName('io.github.sbalci.qupath.workshop.WorkshopI18n')
        String translated = bundle.getMethod('t', String.class).invoke(null, key) as String
        return translated == key ? fallback : translated
    } catch (Throwable ignored) {
        return fallback
    }
}
