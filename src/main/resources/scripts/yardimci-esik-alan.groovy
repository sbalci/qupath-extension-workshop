/**
 * Yardımcı - Eşik ile Alan Ölçümü (% bölge)
 * ------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Seçili anotasyon(lar) içinde, kaydedilmiş HERHANGİ bir piksel
 * sınıflandırıcının (en basit hâliyle bir EŞİKLEYİCİ / thresholder)
 * her sınıfının kapladığı alanı ölçer ve bölgenin **geometrik alanına**
 * normalize ederek "% bölge" üretir. Hücre tespiti YAPMAZ.
 *
 * NEDEN: Tümör/Stroma modülünün "Önce en basit sınıflandırıcı: eşikleyici" bölümünün
 * ölçüm adımıdır. Bir alan tek başına anlam taşımaz; bir paydaya (burada
 * çizdiğiniz bölgenin alanına) bölündüğünde okunur. Bu, QuPath'in
 * "Measuring areas" yaklaşımının betimsel karşılığıdır.
 *
 * EŞİK NOTU (Bankhead): Eşik seçimi, eşik-üstü alanın büyüklüğünü ve şeklini
 * doğrudan değiştirir; eşik tek başına "doku/pozitif" demek değildir. Sınıflandırıcıyı
 * GUI'de canlı önizleme (C tuşu) ile kalibre edin ve aynı çalışmada sabit tutun.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Her sınıfın ALANINI (µm² / mm²) ve bölgeye oranını (% bölge) ölçer.
 *     Bu bir ORAN/ALANdır — klinik skor, eşik veya yorum DEĞİL.
 *   • Eşik DEĞERLERİ sınıflandırıcının içinde tanımlıdır; kullanıcı GUI'de
 *     kalibre eder (aşağıdaki ön koşul).
 *   • DAB-İHK "% pozitiflik" (pozitif ÷ sınıflı) için kardeş betik:
 *     "Alan-bazlı pozitiflik" (yardimci-alan-pozitiflik.groovy, Alan-bazlı Pozitiflik eki).
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
 *   1. Lamı açın; görüntü kalibre (µm/px) olmalı (yoksa "Kalibrasyon" yardımcısı)
 *   2. Ölçülecek bölgeyi anotasyon olarak çizin ve SEÇİN
 *   3. [Extensions → Atölye → Yardımcılar → Skorlama ve ölçüm → Eşik ile alan ölçümü]
 *      (ya da [Automate → Project scripts → bu betik])
 *
 * ÇIKTI:
 *   • Her anotasyona: her sınıf için "<ad>: <Sınıf> (% bölge)" ölçümü
 *     + sınıflandırıcının eklediği alan ölçümleri
 *   • Sonuç penceresinde sınıf bazlı alan tablosu
 *
 * YÖNTEM REFERANSLARI:
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *   • Alan ölçüm metodolojisi: TSR Metodolojisi eki — Tümör/Stroma alan ölçümlerinin metodolojisi.
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
String preferredName = null   // headless/varsayılan seçim için (null → ilk/seçim)

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.12204d6dbab7', "Önce bir lam açın."))
    return
}

def project = QP.getProject()
if (project == null) {
    def msg = workshopText('script.6cfdc02de0f9', 'Bu betik bir proje gerektirir (kayıtlı sınıflandırıcılar projede tutulur).\n') +
              workshopText('script.4d79dd3aa73f', 'Lamı bir QuPath projesine ekleyip tekrar deneyin.')
    if (isHeadless) println msg else Dialogs.showErrorMessage(workshopText('script.830fe38f7e53', "Proje yok"), msg)
    return
}

def server = imageData.getServer()
def cal = server.getPixelCalibration()
if (cal == null || !cal.hasPixelSizeMicrons()) {
    def msg = workshopText('script.b8a89f9ef6e8', 'Görüntü kalibrasyonu (µm/px) yok; alanlar µm²/mm² olarak ölçülemez.\n\n') +
              workshopText('script.921642fd64de', 'Önce piksel boyutunu ayarlayın:\n') +
              workshopText('script.85e3ebc30f18', '  [Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)]')
    if (isHeadless) println msg else Dialogs.showErrorMessage(workshopText('script.a5a01874aff2', "Kalibrasyon yok"), msg)
    return
}
double pxW = cal.getPixelWidthMicrons()
double pxH = cal.getPixelHeightMicrons()

def targets = QP.getSelectedObjects().findAll { it.isAnnotation() }
if (targets.isEmpty()) {
    def msg = workshopText('script.0bc95044d482', 'Ölçülecek anotasyon yok.\n\nÖnce bölgeyi anotasyon olarak çizin ve SEÇİN.')
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.469472ee53f3', "Anotasyon yok"), msg)
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
    def msg = workshopText('script.f6d4f7da15ec', 'Projede kayıtlı piksel sınıflandırıcı (eşikleyici) yok.\n\n') +
              workshopText('script.7e76e0e94a55', 'Bir kez oluşturmanız gerekir:\n') +
              "  [Classify → Pixel classification → Create thresholder]\n" +
              workshopText('script.a0a08fe7eb88', '  • Channel + Threshold ayarlayın, sınıfları adlandırın, [Save].\n\n') +
              workshopText('script.71bab3b465f9', 'Ayrıntı: Tümör/Stroma modülü — "Önce en basit sınıflandırıcı: eşikleyici".')
    if (isHeadless) println msg else Dialogs.showErrorMessage(workshopText('script.9b963a692abd', "Sınıflandırıcı yok"), msg)
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
    chosen = Dialogs.showChoiceDialog(workshopText('script.d160a36f227f', "Eşikleyici seç"),
        workshopText('script.4ec849de2690', "Ölçülecek kayıtlı piksel sınıflandırıcı (eşikleyici):"), names, defaultChoice)
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
    def msg = String.format(java.util.Locale.US, workshopText('script.1c1d6fae7952', 'Sınıflandırıcı yüklenemedi: "%s"\n\nAdın birebir eşleştiğinden ve sınıflandırıcının bozulmadığından emin olun.'), chosen)
    if (isHeadless) println msg else Dialogs.showErrorMessage(workshopText('script.ea8a4476846b', "Yüklenemedi"), msg)
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
        // "<ad>: " tam öneki: "doku" seçiliyken "doku2: …" anahtarlarını da toplamasın.
        it.startsWith(chosen + ":") && it.toLowerCase(java.util.Locale.ROOT).contains("area")
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
    def msg = workshopText('script.270de2c387c6', 'Sınıflandırıcı çalıştı ama beklenen alan ölçümleri bulunamadı.\n\n') +
              workshopText('script.34eb5d84a198', 'Seçili bölgenin sınıflandırıcının çalıştığı alanı içerdiğinden ve\n') +
              workshopText('script.5f202e1c100b', 'sınıflandırıcının alan ölçümü ürettiğinden emin olun.')
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.e3dc9a444a25', "Ölçüm yok"), msg)
    return
}

// ── 6) Sonucu sun ───────────────────────────────────────────────────
double classifiedTotal = 0.0
classAreaUm2.values().each { classifiedTotal += (it as double) }
double covPct = totalRegionUm2 > 0 ? (100.0 * classifiedTotal / totalRegionUm2) : Double.NaN

def body = new StringBuilder()
body << workshopText('script.9f5990aeab58', 'EŞİK İLE ALAN ÖLÇÜMÜ (% bölge)\n')
body << "═════════════════════════════════════\n\n"
body << workshopText('script.f35a66c9ef99', 'Sınıflandırıcı     : "') << chosen << "\"\n"
body << String.format(java.util.Locale.US, workshopText('script.4a85f4f47f27', 'Ölçülen anotasyon  : %d%n'), measured)
body << String.format(java.util.Locale.US, workshopText('script.2ac06b049f94', 'Bölge alanı        : %.4f mm²%n'), totalRegionUm2 / 1.0e6)
body << "\n"
body << workshopText('script.48a8b0e57405', 'Sınıf                  Alan (mm²)       Alan (µm²)      % bölge\n')
body << "──────────────────────────────────────────────────────────────\n"
classAreaUm2.each { cn, a ->
    double av = a as double
    double pct = totalRegionUm2 > 0 ? (100.0 * av / totalRegionUm2) : Double.NaN
    body << String.format(java.util.Locale.US, "%-20s %12.5f %15.2f %10.2f %%%n", cn, av / 1.0e6, av, pct)
}
body << "──────────────────────────────────────────────────────────────\n"
body << String.format(java.util.Locale.US, workshopText('script.4cc62338a3be', 'Sınıflandırılmış kapsam : %.2f %% (bölgenin)%n'), covPct)
body << "\n"
body << workshopText('script.bad0b19c62e7', 'Not: sınıf alanları piksel sınıflandırıcının çözünürlüğünde sayılır;\n')
body << workshopText('script.cab13b3785ac', 'bölge alanı geometrik hesaplanır. Bu yüzden kapsam %100 olmayabilir\n')
body << workshopText('script.d9d5ece167da', '(bkz. TSR Metodolojisi eki — alan ölçüm metodolojisi).\n\n')
body << workshopText('script.b2c032c6bc7b', 'Bu bir ALANdır — klinik skor, eşik veya yorum DEĞİL.\n')
body << "(Bankhead 2017)\n\n"
body << workshopText('script.cf6099f0a005', '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')

showResultWindow(workshopText('script.8ec17bab9e88', 'Eşik ile alan ölçümü'), body.toString())
println "✓ % bölge ölçümleri yazıldı (her anotasyon, her sınıf)."

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
