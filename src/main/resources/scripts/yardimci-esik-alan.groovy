/**
 * Yardımcı - Eşik ile Alan Ölçümü (% bölge)
 * ------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Seçili anotasyon(lar) içinde, kaydedilmiş HERHANGİ bir piksel
 * sınıflandırıcının (en basit hâliyle bir EŞİKLEYİCİ / thresholder)
 * her sınıfının kapladığı alanı ölçer ve bölgenin **geometrik alanına**
 * normalize ederek "% bölge" üretir. Hücre tespiti YAPMAZ.
 *
 * NEDEN: Modül 6'nın "Önce en basit sınıflandırıcı: eşikleyici" bölümünün
 * ölçüm adımıdır. Bir alan tek başına anlam taşımaz; bir paydaya (burada
 * çizdiğiniz bölgenin alanına) bölündüğünde okunur. Bu, QuPath'in
 * "Measuring areas" yaklaşımının betimsel karşılığıdır.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Her sınıfın ALANINI (µm² / mm²) ve bölgeye oranını (% bölge) ölçer.
 *     Bu bir ORAN/ALANdır — klinik skor, eşik veya yorum DEĞİL.
 *   • Eşik DEĞERLERİ sınıflandırıcının içinde tanımlıdır; kullanıcı GUI'de
 *     kalibre eder (aşağıdaki ön koşul).
 *   • DAB-İHK "% pozitiflik" (pozitif ÷ sınıflı) için kardeş betik:
 *     "Alan-bazlı pozitiflik" (yardimci-alan-pozitiflik.groovy, Ek R).
 *
 * ÖN KOŞUL — eşikleyici (bir kez, GUI'de):
 *   [Classify → Pixel classification → Create thresholder]
 *     • Resolution: orta (örn. ~1-2 µm/px — "yeterince doğru" en düşük değer)
 *     • Channel: dokunuza uygun kanal (örn. Hematoxylin OD ile doku alanı)
 *     • Threshold: değerleri sağ-alttan izleyerek ayarlayın
 *     • Eşik-üstü / eşik-altı sınıflarına ad verin
 *     • Canlı önizleme: C tuşu (aç/kapat), opaklık = Ctrl/Cmd + kaydır
 *     • [Save] → bir ad verin (örn. "doku")
 *
 * KULLANIM:
 *   1. Slaytı açın; görüntü kalibre (µm/px) olmalı (yoksa "Kalibrasyon" yardımcısı)
 *   2. Ölçülecek bölgeyi anotasyon olarak çizin ve SEÇİN
 *   3. [Extensions → Atölye → Yardımcılar → Eşik ile alan ölçümü]
 *      (ya da [Automate → Project scripts → bu betik])
 *
 * ÇIKTI:
 *   • Her anotasyona: her sınıf için "<ad>: <Sınıf> (% bölge)" ölçümü
 *     + sınıflandırıcının eklediği alan ölçümleri
 *   • Sonuç penceresinde sınıf bazlı alan tablosu
 *
 * YÖNTEM REFERANSLARI:
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *   • Alan ölçüm metodolojisi: Ek L — Tümör/Stroma alan ölçümlerinin metodolojisi.
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
String preferredName = null   // headless/varsayılan seçim için (null → ilk/seçim)

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir slayt açın.")
    return
}

def project = QP.getProject()
if (project == null) {
    def msg = "Bu betik bir proje gerektirir (kayıtlı sınıflandırıcılar projede tutulur).\n" +
              "Slaytı bir QuPath projesine ekleyip tekrar deneyin."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Proje yok", msg)
    return
}

def server = imageData.getServer()
def cal = server.getPixelCalibration()
if (cal == null || !cal.hasPixelSizeMicrons()) {
    def msg = "Görüntü kalibrasyonu (µm/px) yok; alanlar µm²/mm² olarak ölçülemez.\n\n" +
              "Önce piksel boyutunu ayarlayın:\n" +
              "  [Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu)]"
    if (isHeadless) println msg else Dialogs.showErrorMessage("Kalibrasyon yok", msg)
    return
}
double pxW = cal.getPixelWidthMicrons()
double pxH = cal.getPixelHeightMicrons()

def targets = QP.getSelectedObjects().findAll { it.isAnnotation() }
if (targets.isEmpty()) {
    def msg = "Ölçülecek anotasyon yok.\n\nÖnce bölgeyi anotasyon olarak çizin ve SEÇİN."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Anotasyon yok", msg)
    return
}

// ── 2) Eşikleyiciyi seç ─────────────────────────────────────────────
def names
try {
    names = new ArrayList<String>(project.getPixelClassifiers().getNames())
} catch (Throwable t) {
    names = new ArrayList<String>()
}
names.sort()
if (names.isEmpty()) {
    def msg = "Projede kayıtlı piksel sınıflandırıcı (eşikleyici) yok.\n\n" +
              "Bir kez oluşturmanız gerekir:\n" +
              "  [Classify → Pixel classification → Create thresholder]\n" +
              "  • Channel + Threshold ayarlayın, sınıfları adlandırın, [Save].\n\n" +
              "Ayrıntı: Modül 6 — \"Önce en basit sınıflandırıcı: eşikleyici\"."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Sınıflandırıcı yok", msg)
    return
}

String chosen
if (isHeadless) {
    chosen = (preferredName != null && names.contains(preferredName)) ? preferredName : names[0]
    println "Headless mod: \"${chosen}\" sınıflandırıcısı kullanılıyor."
} else if (names.size() == 1) {
    chosen = names[0]
} else {
    def defaultChoice = (preferredName != null && names.contains(preferredName)) ? preferredName : names[0]
    chosen = Dialogs.showChoiceDialog("Eşikleyici seç",
        "Ölçülecek kayıtlı piksel sınıflandırıcı (eşikleyici):", names, defaultChoice)
    if (chosen == null) {
        println "İptal edildi."
        return
    }
}

// ── 3) Sınıflandırıcıyı yükle ───────────────────────────────────────
def classifier
try {
    classifier = QP.loadPixelClassifier(chosen)
} catch (Throwable t) {
    classifier = null
}
if (classifier == null) {
    def msg = "Sınıflandırıcı yüklenemedi: \"${chosen}\"\n\n" +
              "Adın birebir eşleştiğinden ve sınıflandırıcının bozulmadığından emin olun."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Yüklenemedi", msg)
    return
}

// ── 4) Alan ölçümlerini ekle ────────────────────────────────────────
println "Eşikleyici ile alan ölçümü: \"${chosen}\""
QP.selectObjects(targets)
QP.addPixelClassifierMeasurements(classifier, chosen)

// addPixelClassifierMeasurements her sınıf için "<ad>: <Sınıf> area µm^2"
// benzeri bir ölçüm ekler. Sınıf adını anahtardan türetir, değeri okuruz.
def deriveClass = { String key ->
    String s = key
    if (s.startsWith(chosen)) s = s.substring(chosen.length())
    s = s.replaceFirst(/^[:\s]+/, '')           // baştaki ": " ayıracını at
    s = s.replaceFirst(/(?i)\s*area.*$/, '')      // sondaki "area µm^2" kısmını at
    return s.trim()
}

// ── 5) Sınıf bazlı alanları topla + % bölge yaz ─────────────────────
Map<String, Double> classAreaUm2 = new LinkedHashMap<String, Double>()
double totalRegionUm2 = 0.0
int measured = 0

targets.each { ann ->
    def roi = ann.getROI()
    double rUm2 = (roi != null) ? (roi.getScaledArea(pxW, pxH) as double) : Double.NaN
    if (!Double.isNaN(rUm2) && rUm2 > 0) {
        totalRegionUm2 += rUm2
        measured++
    }
    // findAll yeni bir liste döndürür → ölçüm haritasını güncellerken CME olmaz
    def areaKeys = ann.measurements.keySet().findAll {
        it.startsWith(chosen) && it.toLowerCase(java.util.Locale.ROOT).contains("area")
    }
    areaKeys.each { k ->
        String cn = deriveClass(k)
        double v = (ann.measurements[k] ?: 0.0) as double
        classAreaUm2[cn] = (classAreaUm2.containsKey(cn) ? classAreaUm2[cn] : 0.0) + v
        if (!Double.isNaN(rUm2) && rUm2 > 0) {
            ann.measurements["${chosen}: ${cn} (% bölge)"] = 100.0 * v / rUm2
        }
    }
}
QP.fireHierarchyUpdate()

if (classAreaUm2.isEmpty() || totalRegionUm2 <= 0) {
    def msg = "Sınıflandırıcı çalıştı ama beklenen alan ölçümleri bulunamadı.\n\n" +
              "Seçili bölgenin sınıflandırıcının çalıştığı alanı içerdiğinden ve\n" +
              "sınıflandırıcının alan ölçümü ürettiğinden emin olun."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Ölçüm yok", msg)
    return
}

// ── 6) Sonucu sun ───────────────────────────────────────────────────
double classifiedTotal = 0.0
classAreaUm2.values().each { classifiedTotal += (it as double) }
double covPct = totalRegionUm2 > 0 ? (100.0 * classifiedTotal / totalRegionUm2) : Double.NaN

def body = new StringBuilder()
body << "EŞİK İLE ALAN ÖLÇÜMÜ (% bölge)\n"
body << "═════════════════════════════════════\n\n"
body << "Sınıflandırıcı     : \"" << chosen << "\"\n"
body << String.format(java.util.Locale.US, "Ölçülen anotasyon  : %d%n", measured)
body << String.format(java.util.Locale.US, "Bölge alanı        : %.4f mm²%n", totalRegionUm2 / 1.0e6)
body << "\n"
body << "Sınıf                  Alan (mm²)       Alan (µm²)      % bölge\n"
body << "──────────────────────────────────────────────────────────────\n"
classAreaUm2.each { cn, a ->
    double av = a as double
    double pct = totalRegionUm2 > 0 ? (100.0 * av / totalRegionUm2) : Double.NaN
    body << String.format(java.util.Locale.US, "%-20s %12.5f %15.2f %10.2f %%%n", cn, av / 1.0e6, av, pct)
}
body << "──────────────────────────────────────────────────────────────\n"
body << String.format(java.util.Locale.US, "Sınıflandırılmış kapsam : %.2f %% (bölgenin)%n", covPct)
body << "\n"
body << "Not: sınıf alanları piksel sınıflandırıcının çözünürlüğünde sayılır;\n"
body << "bölge alanı geometrik hesaplanır. Bu yüzden kapsam %100 olmayabilir\n"
body << "(bkz. Ek L — alan ölçüm metodolojisi).\n\n"
body << "Bu bir ALANdır — klinik skor, eşik veya yorum DEĞİL.\n"
body << "(Bankhead 2017)\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Eşik ile alan ölçümü", body.toString())
println "✓ % bölge ölçümleri yazıldı (her anotasyon, her sınıf)."
