/**
 * Modül 5 - Tek Tıkla CD68 / Sitoplazmik IHC Kantifikasyonu
 * ----------------------------------------------------------
 * Atölye için "hızlı deneme" scripti. Sitoplazmik DAB boyamasını (CD68,
 * CD163, EBER vb.) skorlar. Her hücre sitoplazmik DAB yoğunluğuna göre
 * Negative / Weak / Moderate / Strong bin'lerine atanır.
 *
 * KULLANIM:
 *   1. CD68 (veya benzeri sitoplazmik DAB) IHC slaytını açın
 *   2. Image type → "Brightfield (other)"
 *   3. [R] tuşu → ~1×1 mm dikdörtgen anotasyon çizin
 *   4. Anotasyon seçili iken → [Automate → Project scripts → bu script]
 *
 * NEDEN SİTOPLAZMA FARKLI?
 *   • Sitoplazma çekirdekten **çok daha büyük hacme** sahip
 *   • Aynı miktarda DAB → daha düşük ortalama OD
 *   • Bu yüzden EŞİKLER DAHA DÜŞÜK olmalı:
 *       - Nükleer Ki-67: 0.20 / 0.40 / 0.60
 *       - Sitoplazmik CD68: 0.10 / 0.20 / 0.35  ← bu script
 *   • Cell expansion DAHA BÜYÜK (7 µm) — sitoplazma hacmini örneklemek için
 *
 * KLİNİK KULLANIMLAR:
 *   • CD68 → makrofaj yoğunluğu (TAM = Tumor-Associated Macrophage)
 *   • CD163 → M2-polarize makrofajlar
 *   • EBER (ISH) → EBV+ DLBCL, nazofarengeal CA
 *   • CK7/CK20 → epitel sitokeratin profilleri (primer odak)
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathAnnotationObject

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
    Dialogs.showErrorMessage(
        "Görüntü açık değil",
        "Önce bir CD68 (veya başka sitoplazmik IHC) slaytı açın."
    )
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

// ──────────────────────────────────────────────────────────────
// 2) Karşılama
// ──────────────────────────────────────────────────────────────
def devam = waitForConfirm(
    "Modül 5 - CD68 / Sitoplazmik IHC kantifikasyonu",
    "Bu script, seçili anotasyon içinde sitoplazmik DAB sinyaline göre\n" +
    "her hücreyi Negative / Weak (1+) / Moderate (2+) / Strong (3+) olarak\n" +
    "sınıflar.\n\n" +
    "Atölye varsayılan eşikleri (Cytoplasm: DAB OD mean):\n" +
    "  • 1+ (zayıf):       0.10 OD  ← nükleerden düşük (sitoplazma büyük hacim)\n" +
    "  • 2+ (orta):        0.20 OD\n" +
    "  • 3+ (güçlü):       0.35 OD\n\n" +
    "Cell expansion: **7 µm** (sitoplazma için daha büyük örnekleme halkası)\n\n" +
    "Çıktı:\n" +
    "  • Bin dağılımı + yüzdeler\n" +
    "  • H-score (yoğunluk-ağırlıklı)\n" +
    "  • Hücre yoğunluğu (mm² başına) — CD68 için TAM yoğunluğu metriği\n\n" +
    "Hazırsanız OK."
)
if (!devam) { println "İptal."; return }

// ──────────────────────────────────────────────────────────────
// 3) Anotasyon kontrolü
// ──────────────────────────────────────────────────────────────
def selected = QP.getSelectedObject()
if (selected == null || !(selected instanceof PathAnnotationObject)) {
    Dialogs.showErrorMessage(
        "Anotasyon seçili değil",
        "Tümör adası + çevresini içeren bir dikdörtgen anotasyon çizip seçili tutun."
    )
    return
}
def targetAnnotation = selected

// ──────────────────────────────────────────────────────────────
// 4) Positive cell detection (sitoplazma)
// ──────────────────────────────────────────────────────────────
println "─────────────────────────────────────"
println "Modül 5 - CD68 / Sitoplazmik IHC"
println "─────────────────────────────────────"
println "  • Score compartment: Cytoplasm: DAB OD mean"
println "  • Threshold 1+ / 2+ / 3+: 0.10 / 0.20 / 0.35 OD"
println "  • Cell expansion: 7 µm"

def t0 = System.currentTimeMillis()

QP.selectObjects(targetAnnotation)
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
        '"cellExpansionMicrons":7.0,' +
        '"includeNuclei":true,' +
        '"smoothBoundaries":true,' +
        '"makeMeasurements":true,' +
        '"thresholdCompartment":"Cytoplasm: DAB OD mean",' +
        '"thresholdPositive1":0.10,' +
        '"thresholdPositive2":0.20,' +
        '"thresholdPositive3":0.35,' +
        '"singleThreshold":false' +
    '}'
)

def elapsed = (System.currentTimeMillis() - t0) / 1000.0

// ──────────────────────────────────────────────────────────────
// 5) Sonuçları topla
// ──────────────────────────────────────────────────────────────
def cells = targetAnnotation.getChildObjects().findAll { it.isDetection() }
def totalCells = cells.size()

def nNeg = 0, n1 = 0, n2 = 0, n3 = 0
cells.each { c ->
    def cls = c.getPathClass()?.getName() ?: ""
    if (cls.contains("3+"))      n3++
    else if (cls.contains("2+")) n2++
    else if (cls.contains("1+")) n1++
    else                          nNeg++
}

def positives = n1 + n2 + n3
def positivePct = totalCells > 0 ? 100.0 * positives / totalCells : 0.0
def hScore = totalCells > 0
    ? (1.0 * n1 + 2.0 * n2 + 3.0 * n3) * 100.0 / totalCells
    : 0.0

def cal = imageData.getServer().getPixelCalibration()
def pixelWidth  = cal.getPixelWidthMicrons()
def pixelHeight = cal.getPixelHeightMicrons()
def roi = targetAnnotation.getROI()
def totalAreaMm2 = roi != null
    ? (roi.getArea() * pixelWidth * pixelHeight) / 1_000_000.0
    : 0.0
def totalDensity = totalAreaMm2 > 0 ? Math.round(totalCells / totalAreaMm2) : 0
def positiveDensity = totalAreaMm2 > 0 ? Math.round(positives / totalAreaMm2) : 0

// ──────────────────────────────────────────────────────────────
// 6) Hücre sayısı uyarısı
// ──────────────────────────────────────────────────────────────
def uyari = ""
if (totalCells < 200) {
    uyari = String.format("\n⚠️ Sadece %%,d hücre — istatistiksel olarak güvenilmez (≥500 önerilir).", totalCells).replace("%%,d", String.format("%,d", totalCells))
}

// ──────────────────────────────────────────────────────────────
// 7) Sonucu sun
// ──────────────────────────────────────────────────────────────
def pctNeg = totalCells > 0 ? 100.0 * nNeg / totalCells : 0.0
def pct1   = totalCells > 0 ? 100.0 * n1 / totalCells : 0.0
def pct2   = totalCells > 0 ? 100.0 * n2 / totalCells : 0.0
def pct3   = totalCells > 0 ? 100.0 * n3 / totalCells : 0.0

showResultWindow(
    "Tamamlandı 🦠",
    String.format(
        "CD68 / Sitoplazmik IHC bitti.\n\n" +
        "📊 Hücre dağılımı (n = %,d toplam)\n" +
        "────────────────────────────────────\n" +
        "  Negative            : %,d  (%%%.1f)\n" +
        "  Weak (1+)           : %,d  (%%%.1f)\n" +
        "  Moderate (2+)       : %,d  (%%%.1f)\n" +
        "  Strong (3+)         : %,d  (%%%.1f)\n\n" +
        "🎯 Bütünleşik metrikler\n" +
        "────────────────────────\n" +
        "  Pozitif yüzdesi       : %%%.1f\n" +
        "  H-score (0–300)       : %.0f\n" +
        "  Toplam yoğunluk       : ~%,d hücre/mm²\n" +
        "  CD68+ yoğunluk        : ~%,d hücre/mm²  ← TAM yoğunluğu metriği\n" +
        "  Anotasyon alanı       : %.2f mm²\n" +
        "  Süre                  : %.1f sn\n" +
        "%s\n" +
        "Not: H-score (sitoplazmik) araştırma/eğitim amaçlı nicel metriktir.\n\n" +
        "Modül 6'da tümör/stroma ayrımı yaparsak CD68+ hücrelerin\n" +
        "intratumoral vs peritümöral dağılımı çıkarılabilir.",
        totalCells,
        nNeg, pctNeg, n1, pct1, n2, pct2, n3, pct3,
        positivePct, hScore, totalDensity, positiveDensity, totalAreaMm2, elapsed,
        uyari
    )
)

println "─────────────────────────────────────"
println "Tamamlandı: ${totalCells} hücre"
println "  Pozitif: ${positives} (${String.format('%.1f', positivePct)}%)"
println "  H-score: ${String.format('%.0f', hScore)}  |  CD68+ yoğunluk: ${positiveDensity}/mm²"
println "─────────────────────────────────────"
