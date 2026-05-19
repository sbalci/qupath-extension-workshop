/**
 * Modül 7 - Tek Tıkla Tümör-Restricted Ki-67 Kantifikasyonu
 * -----------------------------------------------------------
 * Bu betik atölyenin birleşik analiz adımıdır: önce piksel
 * sınıflandırıcı ile tümör bölgesini ayırır, sonra **yalnızca tümör
 * alanı içinde** Ki-67 pozitif çekirdek sayımı yapar.
 *
 * NEDEN?
 *   Ki-67 indeksini TÜM doku üzerinde hesaplarsanız:
 *     • Stromal lenfosit ve endotel hücrelerinin Ki-67+ çekirdekleri
 *       paydaya girer → DİLÜSYON etkisi
 *
 *   Bu betik tümör/stroma ayrımını açık bir ölçüm adımı olarak uygular.
 *
 * KULLANIM:
 *   1. Ki-67 İHK slaytını açın (atölye için: Ki-67 slaytında piksel
 *      sınıflandırıcı İHK üzerinde de çalışabilir; gerçek hayatta
 *      H&E seri kesit kullanılır)
 *   2. Image type → "Brightfield (other)"
 *   3. [Automate → Project scripts → bu betik]
 *      (Anotasyon ÇİZMENIZE GEREK YOK — betik tüm slayttan başlar)
 *
 * ÖNKOŞUL:
 *   Projenizde `classifiers/tumor-stroma-RF.json` sınıflandırıcısı olmalı
 *   (Modül 6'da kaydedilen). Yoksa betik size adımları söyler.
 *
 * İŞ AKIŞI (3 adım):
 *   1. Sınıflandırıcı → tümör anotasyonları
 *   2. Tümör anotasyonları seçili → Positive cell detection
 *   3. Ki-67 LI'yi yalnızca tümör alanında ölç
 *
 * YÖNTEM REFERANSLARI:
 *   • Nielsen TO et al. (2021), J Natl Cancer Inst — Ki-67 Working Group
 *     sayma standardı (≥500-1.000 tümör hücresi). doi:10.1093/jnci/djaa201
 *   • Bankhead P et al. (2018), Lab Invest — QuPath ile entegre tümör tanıma
 *     + İHK skorlama orijinal yayını. doi:10.1038/labinvest.2017.131
 *   • Skjervold AH et al. (2022), Diagn Pathol — manuel vs dijital uyum.
 *     doi:10.1186/s13000-022-01225-4
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP

// ──────────────────────────────────────────────────────────────
// Modal olmayan pencere yardımcıları
//   - waitForConfirm    : modal hissi veren ama QuPath'i kilitlemeyen onay penceresi
//   - showResultWindow  : sonuç penceresi — açık kalır, QuPath kullanılmaya devam edilebilir
//
// İkisi de always-on-top açık başlar; kullanıcı kapatmadan slaytta dolaşabilir,
// parametre değiştirip betiği tekrar çalıştırabilir, sonuçları kopyalayabilir.
// ──────────────────────────────────────────────────────────────
def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

def waitForConfirm = { String windowTitle, String windowBody ->
    if (isHeadless) {
        println "=== ${windowTitle} ===\n${windowBody}\n=================="
        return true
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def confirmed = new java.util.concurrent.atomic.AtomicBoolean(false)

    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle(windowTitle)
            stage.setAlwaysOnTop(true)

            def label = new javafx.scene.control.Label(windowBody)
            label.setWrapText(true)
            label.setStyle("-fx-font-size: 12px; -fx-padding: 8px;")

            def scrollPane = new javafx.scene.control.ScrollPane(label)
            scrollPane.setFitToWidth(true)

            def okBtn = new javafx.scene.control.Button("Çalıştır")
            okBtn.setDefaultButton(true)
            okBtn.setOnAction({
                confirmed.set(true)
                stage.close()
            })

            def cancelBtn = new javafx.scene.control.Button("İptal")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({
                confirmed.set(false)
                stage.close()
            })

            stage.setOnHidden({ latch.countDown() })

            def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut")
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)

            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, cancelBtn, okBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(scrollPane)
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 620, 460))
            stage.show()
        } catch (Throwable t) {
            // FX kurulumu başarısızsa modal'a geri dön
            confirmed.set(qupath.lib.gui.dialogs.Dialogs.showConfirmDialog(windowTitle, windowBody))
            latch.countDown()
        }
    }

    latch.await()
    return confirmed.get()
}

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
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 760, 580))
            stage.show()
        } catch (Throwable t) {
            // FX başarısızsa modal'a geri dön — kayıp olmasın
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 1) Ön kontroller
// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir Ki-67 İHK slaytı açın.")
    return
}

def imageTypeName = imageData.getImageType()?.toString() ?: ""
if (!imageTypeName.toLowerCase().contains("brightfield")) {
    Dialogs.showErrorMessage(
        "Yanlış görüntü tipi",
        "Image type 'Brightfield (H-DAB)' olmalı. Şu anki: ${imageTypeName}"
    )
    return
}

// "Hematoxylin OD" kanalı yalnızca H-DAB boya vektörleri ayarlanmışsa var olur.
def stains = imageData.getColorDeconvolutionStains()
def hasHematoxylin = false
if (stains != null) {
    for (int i = 1; i <= 3; i++) {
        def name = stains.getStain(i)?.getName()?.toLowerCase()
        if (name != null && name.contains("hematoxylin")) { hasHematoxylin = true; break }
    }
}
if (!hasHematoxylin) {
    println "⚠ H-DAB boya vektörleri tanımlı değil → BRIGHTFIELD_H_DAB varsayılanı uygulanıyor."
    QP.setImageType('BRIGHTFIELD_H_DAB')
}

def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage("Proje açık değil", "Önce bir proje açın.")
    return
}

def classifierName = 'tumor-stroma-RF'
if (!project.getPixelClassifiers().getNames().contains(classifierName)) {
    Dialogs.showErrorMessage(
        "Sınıflandırıcı bulunamadı",
        "Bu betik şu sınıflandırıcıya ihtiyaç duyar: ${classifierName}\n\n" +
        "Önce Modül 6'yı tamamlayın:\n" +
        "  1. H&E slaytında Tumor / Stroma anotasyonları eğitin\n" +
        "  2. '${classifierName}' ismiyle kaydedin\n" +
        "  3. Bu betiği tekrar çalıştırın"
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 2) Karşılama — 3-adımlı iş akışı açıklaması
// ──────────────────────────────────────────────────────────────
def devam = waitForConfirm(
    "Modül 7 - Tümör-Restricted Ki-67",
    "Bu betik 3 adımlı bir iş akışı çalıştırır:\n\n" +
    "  1️⃣ Piksel sınıflandırıcı '${classifierName}' → tümör bölgesi ayır\n" +
    "  2️⃣ Tümör anotasyonları seçili → Positive cell detection\n" +
    "  3️⃣ Yalnızca tümör hücrelerinde Ki-67 LI hesapla\n\n" +
    "Ki-67 İHK eşikleri (Nucleus: DAB OD mean):\n" +
    "  • 1+ / 2+ / 3+: 0.20 / 0.40 / 0.60 OD\n\n" +
    "Çıktı: tümör-içi Ki-67 LI + grup dağılımı + yoğunluk\n\n" +
    "Bu işlem 2–5 dakika sürebilir (slayt boyutuna bağlı).\n\n" +
    "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.\n\n" +
    "Hazırsanız OK düğmesine basın."
)
if (!devam) { println "İptal."; return }

// ──────────────────────────────────────────────────────────────
// Yardımcı: pozitif/negatif/bin sayımı
// ──────────────────────────────────────────────────────────────
def sayHucreler = { collection ->
    def total = collection.size()
    def n0 = 0, n1 = 0, n2 = 0, n3 = 0
    collection.each { c ->
        def cls = c.getPathClass()?.getName() ?: ""
        if (cls.contains("3+"))      n3++
        else if (cls.contains("2+")) n2++
        else if (cls.contains("1+")) n1++
        else                          n0++
    }
    def pozitif = n1 + n2 + n3
    def ki67LI = total > 0 ? 100.0 * pozitif / total : 0.0
    return [total: total, n0: n0, n1: n1, n2: n2, n3: n3,
            pozitif: pozitif, ki67LI: ki67LI]
}

// ──────────────────────────────────────────────────────────────
// 3) Adım 1 — Tümör segmentasyonu
// ──────────────────────────────────────────────────────────────
println "─────────────────────────────────────"
println "Modül 7 - Tümör-Restricted Ki-67"
println "─────────────────────────────────────"
println "Adım 1/3: Tümör segmentasyonu..."

def t0 = System.currentTimeMillis()

def generatedName = "Generated by Modül 7 - ${classifierName}"

// Önce yalnızca bu betiğin önceki çıktısını temizle.
// Kullanıcının elle çizdiği Tumor/Stroma anotasyonlarına dokunma.
def existing = QP.getAnnotationObjects().findAll {
    (it.getName() ?: "") == generatedName
}
if (!existing.isEmpty()) {
    QP.removeObjects(existing, true)
    println "  Önceki ${existing.size()} betik çıktısı temizlendi."
}

def beforeAnnotations = QP.getAnnotationObjects() as Set
QP.createAnnotationsFromPixelClassifier(
    classifierName,
    10000.0,            // minimum object area (µm²)
    5000.0,             // minimum hole area (µm²)
    "SPLIT",            // split into multiple annotations
    "DELETE_EXISTING",  // delete previous annotations of the same class
    "SELECT_NEW"        // select newly created objects
)

def generatedAnnotations = QP.getAnnotationObjects().findAll {
    !beforeAnnotations.contains(it) && (it.getPathClass()?.getName() in ["Tumor", "Stroma"])
}
generatedAnnotations.each { it.setName(generatedName) }

def tumorAnnotations = generatedAnnotations.findAll {
    it.getPathClass()?.getName() == "Tumor"
}

if (tumorAnnotations.isEmpty()) {
    Dialogs.showErrorMessage(
        "Tümör bulunamadı",
        "Sınıflandırıcı bu slaytta hiç tümör bölgesi tespit edemedi.\n\n" +
        "Olası nedenler:\n" +
        "  • Sınıflandırıcı İHK için değil H&E için eğitildi (tipik durum)\n" +
        "  • Eşikler bu slayta uymuyor\n" +
        "  • Slayt gerçekten tümör içermiyor (lenf nodu, normal doku?)\n\n" +
        "Çözüm: Modül 6'da Ki-67 İHK üzerinde de çalışan bir sınıflandırıcı eğitin\n" +
        "(İHK + H&E karışık eğitim seti) ya da H&E seri kesit kullanın."
    )
    return
}

def t1 = System.currentTimeMillis()
def step1Time = (t1 - t0) / 1000.0
println String.format("  ✓ %d tümör nesnesi (%.1f sn)", tumorAnnotations.size(), step1Time)

// ──────────────────────────────────────────────────────────────
// 4) Adım 2 — Tümör seçili, pozitif hücre tespiti
// ──────────────────────────────────────────────────────────────
println "Adım 2/3: Tümör alanında Ki-67 pozitif hücre tespiti..."

// Tespit kanalı — Modül 3 ile aynı yöntem notu geçerli.
// Varsayılan: Hematoxylin OD. Yüksek-LI vakada 'Optical density sum'a geçin.
def detectionImageBrightfield = 'Hematoxylin OD'   // veya 'Optical density sum'

QP.selectObjects(tumorAnnotations)
QP.runPlugin(
    'qupath.imagej.detect.cells.PositiveCellDetection',
    '{' +
        '"detectionImageBrightfield":"' + detectionImageBrightfield + '",' +
        '"requestedPixelSizeMicrons":0.5,' +
        '"backgroundRadiusMicrons":8.0,' +
        '"medianRadiusMicrons":0.0,' +
        '"sigmaMicrons":1.5,' +
        '"minAreaMicrons":10.0,' +
        '"maxAreaMicrons":400.0,' +
        '"threshold":0.1,' +
        '"watershedPostProcess":true,' +
        '"cellExpansionMicrons":5.0,' +
        '"includeNuclei":true,' +
        '"smoothBoundaries":true,' +
        '"makeMeasurements":true,' +
        '"thresholdCompartment":"Nucleus: DAB OD mean",' +
        '"thresholdPositive1":0.2,' +
        '"thresholdPositive2":0.4,' +
        '"thresholdPositive3":0.6,' +
        '"singleThreshold":false' +
    '}'
)

def t2 = System.currentTimeMillis()
def step2Time = (t2 - t1) / 1000.0
println String.format("  ✓ Pozitif hücre tespiti bitti (%.1f sn)", step2Time)

// ──────────────────────────────────────────────────────────────
// 5) Adım 3 — Tümör hücrelerini topla
// ──────────────────────────────────────────────────────────────
println "Adım 3/3: Sonuçları toplama..."

def tumorCells = []
tumorAnnotations.each { ann -> tumorCells.addAll(ann.getChildObjects().findAll { it.isDetection() }) }
def tumorStats = sayHucreler(tumorCells)

// Alan hesabı
def cal = imageData.getServer().getPixelCalibration()
def pixelWidth  = cal.getPixelWidthMicrons()
def pixelHeight = cal.getPixelHeightMicrons()
def tumorAreaMm2 = tumorAnnotations.sum(0.0) { ann ->
    def roi = ann.getROI()
    roi != null ? (roi.getArea() * pixelWidth * pixelHeight) / 1_000_000.0 : 0.0
}
def density = tumorAreaMm2 > 0 ? Math.round(tumorStats.total / tumorAreaMm2) : 0

// İstatistiksel güven aralığı (95% CI - Basitleştirilmiş binomial proportion)
def nCells = tumorStats.total
def pVal = tumorStats.ki67LI / 100.0
def zVal = 1.96 // 95% güven
def errorMargin = nCells > 30 ? zVal * Math.sqrt((pVal * (1 - pVal)) / nCells) * 100.0 : 0.0
def ciMetin = nCells > 30 ? String.format("±%.1f%%", errorMargin) : "(n<30)"

def totalElapsed = (System.currentTimeMillis() - t0) / 1000.0

// Sayma standardı uyarısı — Nielsen 2021 (J Natl Cancer Inst, doi:10.1093/jnci/djaa201)
// International Ki-67 in Breast Cancer Working Group: en az 500-1.000 tümör hücresi.
def sayimUyari = ""
if (tumorStats.total < 500) {
    sayimUyari = String.format(
        "\n📝 Not: %,d tümör hücresi <500 — Ki-67 Working Group (Nielsen 2021) sayma\n" +
        "  standardının altında. Tümör annotation alanını büyütmeyi değerlendirin.",
        tumorStats.total)
}

// ──────────────────────────────────────────────────────────────
// 6) Sonucu sun — tümör-içi LI'yi vurgulayalım
// ──────────────────────────────────────────────────────────────
def pct = { c, t -> t > 0 ? 100.0 * c / t : 0.0 }

showResultWindow(
    "Tamamlandı 🔗",
    String.format(
        "Tümör-Restricted Ki-67 kantifikasyonu bitti.\n\n" +
        "🧠 Adım 1 — Tümör segmentasyonu\n" +
        "──────────────────────────────────\n" +
        "  Tümör nesneleri  : %,d\n" +
        "  Tümör alanı      : %.2f mm²\n\n" +
        "🔬 Adım 2 — Ki-67 sayımı (yalnızca tümör içinde)\n" +
        "─────────────────────────────────────────────────\n" +
        "  Toplam tümör hücresi  : %,d\n" +
        "  Negatif               : %,d  (%%%.1f)\n" +
        "  1+ (zayıf)            : %,d  (%%%.1f)\n" +
        "  2+ (orta)             : %,d  (%%%.1f)\n" +
        "  3+ (güçlü)            : %,d  (%%%.1f)\n\n" +
        "🎯 Metrikler (tümör-içi)\n" +
        "─────────────────────────\n" +
        "  Ki-67 LI               : %%%.1f %s (95%% Güven Aralığı)\n" +
        "  Tümör içi yoğunluk     : ~%,d hücre/mm²\n" +
        "  Toplam süre            : %.1f sn\n" +
        "%s\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
        tumorAnnotations.size(), tumorAreaMm2,
        tumorStats.total,
        tumorStats.n0, pct(tumorStats.n0, tumorStats.total),
        tumorStats.n1, pct(tumorStats.n1, tumorStats.total),
        tumorStats.n2, pct(tumorStats.n2, tumorStats.total),
        tumorStats.n3, pct(tumorStats.n3, tumorStats.total),
        tumorStats.ki67LI, ciMetin, density, totalElapsed, sayimUyari
    )
)

println "─────────────────────────────────────"
println String.format("Tamamlandı:")
println String.format("  Tümör alanı: %.2f mm² (%d nesne)", tumorAreaMm2, tumorAnnotations.size())
println String.format("  Tümör-içi hücre: %d  |  Ki-67 LI: %.1f%%", tumorStats.total, tumorStats.ki67LI)
println String.format("  Toplam süre: %.1f sn", totalElapsed)
println "─────────────────────────────────────"
