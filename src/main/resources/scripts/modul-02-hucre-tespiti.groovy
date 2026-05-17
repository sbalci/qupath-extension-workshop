/**
 * Modül 2 - Tek Tıkla Hücre Tespiti
 * ----------------------------------
 * Atölye için "hızlı deneme" scripti. Seçilen anotasyon içindeki tüm
 * çekirdekleri H&E için atölye varsayılan parametreleriyle tespit eder.
 *
 * KULLANIM:
 *   1. Bir H&E slaytı açın
 *   2. [R] tuşu → tümör içeren küçük bir dikdörtgen (~1×1 mm) çizin
 *   3. Anotasyon seçili iken → [Automate → Project scripts → bu script]
 *   4. Sonuçları popup pencereden okuyun
 *
 * Bu script atölyenin tek tıkla "wow" anı için yazılmıştır. Aynı parametrelerin
 * her birinin ne işe yaradığını ve nasıl ayarlayacağınızı öğrenmek için
 * web sitesindeki **Modül 2 - Hücre Tespiti** bölümüne dönün.
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
// 1) Ön kontrol — açık görüntü var mı?
// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(
        "Görüntü açık değil",
        "Önce bir slayt açın (sol Proje panelinden çift tıklayın), sonra bu scripti tekrar çalıştırın."
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 2) Karşılama — kullanıcıya ne yapacağımızı anlat
// ──────────────────────────────────────────────────────────────
def devam = waitForConfirm(
    "Modül 2 - Hızlı hücre tespiti",
    "Bu script, seçtiğiniz anotasyon içindeki TÜM çekirdekleri otomatik olarak\n" +
    "tespit edecek. Atölye için ayarlanmış varsayılan parametreleri kullanır.\n\n" +
    "Şunlardan emin olun:\n" +
    "  • Bir H&E slaytı açık\n" +
    "  • Bir dikdörtgen anotasyon (R tuşu) çizdiniz (~1×1 mm tümör alanı)\n" +
    "  • Anotasyon SEÇİLİ (kenarları sarı görünür)\n\n" +
    "Hazırsanız OK, değilse Cancel ile çıkın."
)
if (!devam) {
    println "Kullanıcı iptal etti."
    return
}

// ──────────────────────────────────────────────────────────────
// 3) Anotasyon kontrolü
// ──────────────────────────────────────────────────────────────
def selected = QP.getSelectedObject()
def annotations = QP.getAnnotationObjects()

if (annotations.isEmpty()) {
    Dialogs.showErrorMessage(
        "Anotasyon bulunamadı",
        "Önce bir anotasyon çizmelisiniz.\n\n" +
        "Nasıl:\n" +
        "  1. Toolbar'dan dikdörtgen aracını seçin (veya R tuşu)\n" +
        "  2. Slayttaki tümör alanına ~1×1 mm dikdörtgen sürükleyin\n" +
        "  3. Bu scripti tekrar çalıştırın"
    )
    return
}

if (selected == null || !(selected instanceof PathAnnotationObject)) {
    Dialogs.showErrorMessage(
        "Anotasyon seçili değil",
        "Lütfen yalnızca çalıştırmak istediğiniz dikdörtgen anotasyonu seçin.\n\n" +
        "Nasıl:\n" +
        "  1. Slayttaki anotasyona tıklayın (kenarı sarı görünmeli)\n" +
        "  2. Bu scripti tekrar çalıştırın"
    )
    return
}

def targetAnnotation = selected

// ──────────────────────────────────────────────────────────────
// 4) Hücre tespitini çalıştır (atölye varsayılanları)
// ──────────────────────────────────────────────────────────────
println "Hücre tespiti başlatılıyor — atölye varsayılan parametreleriyle..."
println "  • Requested pixel size: 0.5 µm/px"
println "  • Threshold (Hematoxylin OD): 0.1"
println "  • Sigma: 1.5 µm"
println "  • Min/Max area: 10 / 400 µm²"
println "  • Cell expansion: 5 µm"

def t0 = System.currentTimeMillis()

QP.selectObjects(targetAnnotation)
QP.runPlugin(
    'qupath.imagej.detect.cells.WatershedCellDetection',
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
        '"makeMeasurements":true' +
    '}'
)

def elapsed = (System.currentTimeMillis() - t0) / 1000.0

// ──────────────────────────────────────────────────────────────
// 5) Sonuçları topla
// ──────────────────────────────────────────────────────────────
def cal = imageData.getServer().getPixelCalibration()
def pixelWidth  = cal.getPixelWidthMicrons()
def pixelHeight = cal.getPixelHeightMicrons()

def totalCells = 0
def totalAreaMm2 = 0.0

totalCells = targetAnnotation.getChildObjects().findAll { it.isDetection() }.size()
def roi = targetAnnotation.getROI()
if (roi != null) {
    def areaPx = roi.getArea()
    totalAreaMm2 = (areaPx * pixelWidth * pixelHeight) / 1_000_000.0
}

def density = totalAreaMm2 > 0 ? Math.round(totalCells / totalAreaMm2) : 0

// ──────────────────────────────────────────────────────────────
// 6) Kullanıcıya sonucu sun
// ──────────────────────────────────────────────────────────────
def yorum
if (density < 1000) {
    yorum = "Yoğunluk oldukça düşük. Stromal/yağ ağırlıklı bir alan seçmiş olabilirsiniz, ya da algoritma çekirdekleri kaçırıyor olabilir."
} else if (density > 10000) {
    yorum = "Yoğunluk çok yüksek — lenfoid doku veya muhtemelen fazla-tespit (over-segmentation)."
} else {
    yorum = "Yoğunluk eğitim örnekleri için beklenen başlangıç aralığında (3000–8000 hücre/mm²)."
}

showResultWindow(
    "Tamamlandı 🎉",
    String.format(
        "İlk computational hücre sayımınız bitti!\n\n" +
        "📊 Sonuçlar\n" +
        "──────────\n" +
        "  Toplam hücre        : %,d\n" +
        "  Anotasyon alanı     : %.2f mm²\n" +
        "  Hücre yoğunluğu     : ~%,d hücre/mm²\n" +
        "  Süre                : %.1f sn\n\n" +
        "💡 %s\n\n" +
        "Slayta bakın — anotasyon içindeki renkli daireler her biri tespit edilmiş\n" +
        "bir çekirdek. Yakınlaşın, bazılarını gözle kontrol edin.\n\n" +
        "Sıradaki: Web sitesindeki Modül 2 'Şimdi anlayalım' bölümüne dönün ve\n" +
        "parametreleri değiştirip sonucun nasıl değiştiğini görün.",
        totalCells, totalAreaMm2, density, elapsed, yorum
    )
)

println "─────────────────────────────────────"
println "Tamamlandı: ${totalCells} hücre / ${String.format('%.2f', totalAreaMm2)} mm² / ${density} hücre/mm² (${elapsed} sn)"
println "─────────────────────────────────────"
