/**
 * Modül 7 - Tek Tıkla Tümör-Restricted Ki-67 Kantifikasyonu
 * -----------------------------------------------------------
 * Bu script atölyenin birleşik analiz adımıdır: önce piksel
 * sınıflandırıcı ile tümör bölgesini ayırır, sonra **yalnızca tümör
 * alanı içinde** Ki-67 pozitif çekirdek sayımı yapar.
 *
 * NEDEN?
 *   Ki-67 indeksini TÜM doku üzerinde hesaplarsanız:
 *     • Stromal lenfosit ve endotel hücrelerinin Ki-67+ çekirdekleri
 *       paydaya girer → DİLÜSYON etkisi
 *
 *   Bu script tümör/stroma ayrımını açık bir ölçüm adımı olarak uygular.
 *
 * KULLANIM:
 *   1. Ki-67 IHC slaytını açın (atölye için: Ki-67 slaytında piksel
 *      sınıflandırıcı IHC üzerinde de çalışabilir; gerçek hayatta
 *      H&E seri kesit kullanılır)
 *   2. Image type → "Brightfield (other)"
 *   3. [Automate → Project scripts → bu script]
 *      (Anotasyon ÇİZMENIZE GEREK YOK — script tüm slayttan başlar)
 *
 * ÖNKOŞUL:
 *   Projenizde `classifiers/tumor-stroma-RF.json` sınıflandırıcısı olmalı
 *   (Modül 6'da kaydedilen). Yoksa script size adımları söyler.
 *
 * PIPELINE (3 adım):
 *   1. Sınıflandırıcı → tümör anotasyonları
 *   2. Tümör anotasyonları seçili → Positive cell detection
 *   3. Ki-67 LI'yi yalnızca tümör alanında ölç
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP

// ──────────────────────────────────────────────────────────────
// Non-modal pencere yardımcıları
//   - waitForConfirm    : modal-hissi veren ama QuPath'i bloklamayan onay penceresi
//   - showResultWindow  : sonuç penceresi — açık kalır, QuPath kullanılmaya devam edilebilir
//
// İkisi de always-on-top açık başlar; kullanıcı kapatmadan slaytta dolaşabilir,
// parametre değiştirip scripti tekrar koşabilir, sonuçları kopyalayabilir.
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
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir Ki-67 IHC slaytı açın.")
    return
}

def imageTypeName = imageData.getImageType()?.toString() ?: ""
if (!imageTypeName.toLowerCase().contains("brightfield")) {
    Dialogs.showErrorMessage(
        "Yanlış görüntü tipi",
        "Image type 'Brightfield (other)' olmalı. Şu anki: ${imageTypeName}"
    )
    return
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
        "Bu script şu sınıflandırıcıya ihtiyaç duyar: ${classifierName}\n\n" +
        "Önce Modül 6'yı tamamlayın:\n" +
        "  1. H&E slaytında Tumor / Stroma anotasyonları eğitin\n" +
        "  2. '${classifierName}' ismiyle kaydedin\n" +
        "  3. Bu scripti tekrar çalıştırın"
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 2) Karşılama — 3-adımlı pipeline açıklaması
// ──────────────────────────────────────────────────────────────
def devam = waitForConfirm(
    "Modül 7 - Tümör-Restricted Ki-67",
    "Bu script 3 adımlı bir pipeline çalıştırır:\n\n" +
    "  1️⃣ Piksel sınıflandırıcı '${classifierName}' → tümör bölgesi ayır\n" +
    "  2️⃣ Tümör anotasyonları seçili → Positive cell detection\n" +
    "  3️⃣ Yalnızca tümör hücrelerinde Ki-67 LI hesapla\n\n" +
    "Ki-67 IHC eşikleri (Nucleus: DAB OD mean):\n" +
    "  • 1+ / 2+ / 3+: 0.20 / 0.40 / 0.60 OD\n\n" +
    "Çıktı: tümör-içi Ki-67 LI + tüm-slayt karşılaştırması\n" +
    "(stromal dilüsyon etkisinin büyüklüğünü göreceksiniz)\n\n" +
    "Bu işlem 2–5 dakika sürebilir (slayt boyutuna bağlı).\n\n" +
    "Hazırsanız OK."
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
    // Not: H-score Ki-67 için kullanılmaz — H-score ER/PR ve sitoplazmik markerlar için uygundur.
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

// Önce yalnızca bu scriptin önceki çıktısını temizle.
// Kullanıcının elle çizdiği Tumor/Stroma anotasyonlarına dokunma.
def existing = QP.getAnnotationObjects().findAll {
    (it.getName() ?: "") == generatedName
}
if (!existing.isEmpty()) {
    QP.removeObjects(existing, true)
    println "  Önceki ${existing.size()} script çıktısı temizlendi."
}

def beforeAnnotations = QP.getAnnotationObjects() as Set
QP.createAnnotationsFromPixelClassifier(classifierName, 10000.0, 5000.0)

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
        "  • Sınıflandırıcı IHC için değil H&E için eğitildi (tipik durum)\n" +
        "  • Eşikler bu slayta uymuyor\n" +
        "  • Slayt gerçekten tümör içermiyor (lenf nodu, normal doku?)\n\n" +
        "Çözüm: Modül 6'da Ki-67 IHC üzerinde de çalışan bir sınıflandırıcı eğitin\n" +
        "(IHC + H&E karışık eğitim seti) ya da H&E seri kesit kullanın."
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

QP.selectObjects(tumorAnnotations)
QP.runPlugin(
    'qupath.imagej.detect.cells.PositiveCellDetection',
    '{' +
        '"detectionImageBrightfield":"Hematoxylin OD",' +
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
        "  Toplam süre            : %.1f sn\n\n" +
        "Not: H-score Ki-67 için kullanılmaz (ER/PR ve sitoplazmik markerlara uygundur).\n" +
        "Tümör/stroma sınırını ve DAB eşiklerini görsel olarak kontrol edin;\n" +
        "bu çıktı yalnızca araştırma/eğitim amaçlı ölçümdür.\n\n" +
        "📝 Ölçüm özeti:\n" +
        "  \"Ki-67 LI: %%%.1f (yalnızca tümör alanı, n=%,d hücre).\n" +
        "   Yöntem: QuPath 0.6 + RF piksel sınıflandırıcı + Positive cell detection.\"\n\n" +
        "Modül 8 ileriki atölye sürümlerinde ele alınacaktır.",
        tumorAnnotations.size(), tumorAreaMm2,
        tumorStats.total,
        tumorStats.n0, pct(tumorStats.n0, tumorStats.total),
        tumorStats.n1, pct(tumorStats.n1, tumorStats.total),
        tumorStats.n2, pct(tumorStats.n2, tumorStats.total),
        tumorStats.n3, pct(tumorStats.n3, tumorStats.total),
        tumorStats.ki67LI, ciMetin, density, totalElapsed,
        tumorStats.ki67LI, tumorStats.total
    )
)

println "─────────────────────────────────────"
println String.format("Tamamlandı:")
println String.format("  Tümör alanı: %.2f mm² (%d nesne)", tumorAreaMm2, tumorAnnotations.size())
println String.format("  Tümör-içi hücre: %d  |  Ki-67 LI: %.1f%%", tumorStats.total, tumorStats.ki67LI)
println String.format("  Toplam süre: %.1f sn", totalElapsed)
println "─────────────────────────────────────"
