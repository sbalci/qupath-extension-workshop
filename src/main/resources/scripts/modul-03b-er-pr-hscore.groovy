/**
 * Modül 3b - Tek Tıkla ER / PR Nükleer H-score
 * ----------------------------------------------
 * Atölye için "hızlı deneme" betiği. Seçilen anotasyon içinde
 * ER / PR (östrojen / progesteron reseptörü) gibi nükleer hormon
 * reseptörlerini skorlar ve **H-score**'u (0–300) kaydeder.
 *
 * KULLANIM:
 *   1. ER veya PR İHK slaytını açın
 *   2. Image type → "Brightfield (other)"
 *   3. [R] tuşu → tümör içeren ~1×1 mm dikdörtgen anotasyon çizin
 *   4. Anotasyon seçili iken → [Automate → Project scripts → bu betik]
 *
 * NE YAPAR:
 *   • Çekirdek tespiti + DAB OD'ye göre Negative / 1+ / 2+ / 3+ sınıflama
 *   • H-score = (1 × %1+) + (2 × %2+) + (3 × %3+)        [0–300]
 *   • Pozitif yüzdesi (≥1+ olan her çekirdek)
 *   • Hücre yoğunluğu (hücre/mm²)
 *
 * NE YAPMAZ:
 *   • Allred skoru hesaplamaz (yoğunluk × dağılım puanı — manuel)
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathAnnotationObject

// ──────────────────────────────────────────────────────────────
// Modal olmayan pencere yardımcıları
//   - waitForConfirm    : modal hissi veren ama QuPath'i kilitlemeyen onay penceresi
//   - showResultWindow  : sonuç penceresi — açık kalır, QuPath kullanılmaya devam edilebilir
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
        "Önce bir ER veya PR İHK slaytı açın."
    )
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

// "Hematoxylin OD" kanalı yalnızca H-DAB boya vektörleri ayarlanmışsa görünür.
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

// ──────────────────────────────────────────────────────────────
// 2) Karşılama
// ──────────────────────────────────────────────────────────────
def devam = waitForConfirm(
    "Modül 3b - ER / PR Nükleer H-score",
    "Bu betik, seçili anotasyon içindeki tüm çekirdekleri tespit edip\n" +
    "her birini DAB yoğunluğuna göre Negative / 1+ / 2+ / 3+ olarak sınıflar.\n\n" +
    "Atölye varsayılan eşikleri (Nucleus: DAB OD mean):\n" +
    "  • 1+ (zayıf):  0.20 OD\n" +
    "  • 2+ (orta):   0.40 OD\n" +
    "  • 3+ (güçlü):  0.60 OD\n\n" +
    "Birincil çıktı:\n" +
    "  • H-score (0–300) — ER/PR için standart yoğunluk-ağırlıklı skor\n" +
    "  • Pozitif yüzdesi (≥1+)\n" +
    "  • Grup dağılımı (% 0 / % 1+ / % 2+ / % 3+)\n\n" +
    "Not: Allred skoru bu betik tarafından hesaplanmaz (proporsiyon\n" +
    "puanlaması manuel görsel adımdır).\n\n" +
    "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.\n\n" +
    "Hazırsanız OK düğmesine basın."
)
if (!devam) { println "İptal."; return }

// ──────────────────────────────────────────────────────────────
// 3) Anotasyon kontrolü
// ──────────────────────────────────────────────────────────────
def selected = QP.getSelectedObject()
if (selected == null || !(selected instanceof PathAnnotationObject)) {
    Dialogs.showErrorMessage(
        "Anotasyon seçili değil",
        "Tümör içeren bir dikdörtgen anotasyon çizip seçili tutun."
    )
    return
}
def targetAnnotation = selected

// ──────────────────────────────────────────────────────────────
// 4) Positive cell detection (nükleer DAB)
// ──────────────────────────────────────────────────────────────
println "─────────────────────────────────────"
println "Modül 3b - ER / PR Nükleer H-score"
println "─────────────────────────────────────"
println "  • Score compartment: Nucleus: DAB OD mean"
println "  • Eşik 1+ / 2+ / 3+: 0.20 / 0.40 / 0.60 OD"

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

// H-score — ER/PR için standart yoğunluk-ağırlıklı skor (0–300)
def pct1 = totalCells > 0 ? 100.0 * n1 / totalCells : 0.0
def pct2 = totalCells > 0 ? 100.0 * n2 / totalCells : 0.0
def pct3 = totalCells > 0 ? 100.0 * n3 / totalCells : 0.0
def pctNeg = totalCells > 0 ? 100.0 * nNeg / totalCells : 0.0
def hScore = (1.0 * pct1) + (2.0 * pct2) + (3.0 * pct3)

// Alan ve yoğunluk
def cal = imageData.getServer().getPixelCalibration()
def pixelWidth  = cal.getPixelWidthMicrons()
def pixelHeight = cal.getPixelHeightMicrons()
def roi = targetAnnotation.getROI()
def totalAreaMm2 = roi != null
    ? (roi.getArea() * pixelWidth * pixelHeight) / 1_000_000.0
    : 0.0
def density = totalAreaMm2 > 0 ? Math.round(totalCells / totalAreaMm2) : 0

def uyari = ""
if (totalCells < 200) {
    uyari = String.format("\n📝 Not: %,d hücre <200 — küçük örneklem; sonuçlar istatistiksel olarak istatistiksel olarak güvenilir olmayabilir.",
        totalCells)
}

// ──────────────────────────────────────────────────────────────
// 6) Sonucu sun
// ──────────────────────────────────────────────────────────────
showResultWindow(
    "Tamamlandı 🧬",
    String.format(
        "ER / PR Nükleer H-score kantifikasyonu bitti.\n\n" +
        "📊 Sayım (n = %,d toplam çekirdek)\n" +
        "────────────────────────────────────\n" +
        "  Negatif (0)         : %,d  (%%%.1f)\n" +
        "  1+ (zayıf)          : %,d  (%%%.1f)\n" +
        "  2+ (orta)           : %,d  (%%%.1f)\n" +
        "  3+ (güçlü)          : %,d  (%%%.1f)\n\n" +
        "🎯 H-score (özet)\n" +
        "─────────────────\n" +
        "  H-score (0–300)        : %.0f\n" +
        "  Pozitif yüzdesi (≥1+)  : %%%.1f\n" +
        "  %% 0 / 1+ / 2+ / 3+    : %%%.1f / %%%.1f / %%%.1f / %%%.1f\n\n" +
        "  Hücre yoğunluğu        : ~%,d hücre/mm²\n" +
        "  Anotasyon alanı        : %.2f mm²\n" +
        "  Süre                   : %.1f sn\n" +
        "%s\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
        totalCells,
        nNeg, pctNeg, n1, pct1, n2, pct2, n3, pct3,
        hScore, positivePct,
        pctNeg, pct1, pct2, pct3,
        density, totalAreaMm2, elapsed, uyari
    )
)

println "─────────────────────────────────────"
println "Tamamlandı: ${totalCells} çekirdek"
println "  0: ${nNeg} (${String.format('%.1f', pctNeg)}%)  |  1+: ${n1} (${String.format('%.1f', pct1)}%)"
println "  2+: ${n2} (${String.format('%.1f', pct2)}%)  |  3+: ${n3} (${String.format('%.1f', pct3)}%)"
println "  H-score: ${String.format('%.0f', hScore)}  |  Pozitif: ${String.format('%.1f', positivePct)}%"
println "─────────────────────────────────────"
