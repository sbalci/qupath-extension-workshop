/**
 * Yardımcı - Kalibrasyon (piksel boyutu)
 * --------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Açık slaytın piksel boyutunu (µm/px) ayarlar. QuPath fiziksel ölçeği bu
 * sayıdan bilir; eksikse alan/yoğunluk/çap ölçümleri yanlış olur — "Kalibrasyon
 * yok" uyarısının çözümü budur.
 *
 * İKİ MOD:
 *   A) Doğrudan değer gir — µm/px değerini elle girin.
 *      Atölye tarayıcıları: Leica Aperio GT450 (40×) ≈ 0.26 µm/px;
 *                           Aperio AT2 (40×) ≈ 0.25 µm/px (20× ≈ 0.50).
 *      Not: Yerel Aperio .svs dosyaları piksel boyutunu zaten gömer (QuPath
 *      otomatik kalibre eder); bu araç asıl olarak meta verisi silinmiş
 *      dönüştürülmüş/dışa aktarılmış dosyalar (ör. TIFF) içindir.
 *   B) Cetvelden ölç — bilinen uzunlukta bir çizgi anotasyonu çizin; gerçek
 *      uzunluğu (µm) girin → µm/px = gerçek_µm ÷ çizgi_uzunluğu_px.
 *
 * KAPSAM: Açık görüntü (her zaman). Mod A'da isteğe bağlı: projedeki tüm
 *         KALİBRE EDİLMEMİŞ görüntülere de uygula (kalibre olanlara dokunmaz).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.images.servers.ImageServerMetadata

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── showResultWindow — diğer atölye betikleriyle aynı modal-olmayan pencere ──
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
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
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
            stage.setScene(new javafx.scene.Scene(root, 640, 460))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Mod seçimi — 3 düğmeli pencere (yardimci-tespitleri-sil kalıbı) ──
def chooseMode = { ->
    if (isHeadless) return "direct"
    def latch = new java.util.concurrent.CountDownLatch(1)
    def choice = new java.util.concurrent.atomic.AtomicReference<String>(null)
    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle("Kalibrasyon — yöntem seçin")
            stage.setAlwaysOnTop(true)
            def label = new javafx.scene.control.Label(
                "Piksel boyutunu (µm/px) nasıl ayarlamak istersiniz?\n\n" +
                "• Doğrudan değer: tarayıcı değerini elle girin\n" +
                "    (GT450 40× ≈ 0.26 · AT2 40× ≈ 0.25 / 20× ≈ 0.50)\n" +
                "• Cetvelden ölç: bilinen uzunlukta bir çizgi çizip ölçün")
            label.setWrapText(true)
            label.setStyle("-fx-font-size: 12px; -fx-padding: 10px;")
            def directBtn = new javafx.scene.control.Button("Doğrudan değer gir")
            directBtn.setDefaultButton(true)
            directBtn.setOnAction({ choice.set("direct"); stage.close() })
            def rulerBtn = new javafx.scene.control.Button("Cetvelden ölç")
            rulerBtn.setOnAction({ choice.set("ruler"); stage.close() })
            def cancelBtn = new javafx.scene.control.Button("İptal")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({ choice.set(null); stage.close() })
            stage.setOnHidden({ latch.countDown() })
            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def buttons = new javafx.scene.layout.HBox(10, cancelBtn, spacer, rulerBtn, directBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))
            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(label)
            root.setBottom(buttons)
            stage.setScene(new javafx.scene.Scene(root, 500, 240))
            stage.show()
        } catch (Throwable t) {
            choice.set(null); latch.countDown()
        }
    }
    latch.await()
    return choice.get()
}

// ── 1) Ön kontrol ──
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir slayt açın, sonra bu betiği tekrar çalıştırın.")
    return
}
def server = imageData.getServer()
def cal = server.getPixelCalibration()
double curPx = cal.getPixelWidthMicrons()
def curStr = (curPx > 0) ? String.format(java.util.Locale.US, "%.4f µm/px", curPx) : "tanımsız (NaN)"

// ── 2) Mod seçimi ──
def mode = chooseMode()
if (mode == null) { println "Kalibrasyon iptal edildi."; return }

double newPx = 0.0
String methodNote = ""

if (mode == "direct") {
    def input = Dialogs.showInputDialog(
        "Kalibrasyon — doğrudan değer",
        "Şu anki piksel boyutu: ${curStr}\n\n" +
        "Yeni piksel boyutu (µm/px):\n" +
        "  • Leica Aperio GT450 (40×) ≈ 0.26\n" +
        "  • Leica Aperio AT2 (40×) ≈ 0.25  (20× ≈ 0.50)\n\n" +
        "Not: Yerel .svs dosyaları bu değeri zaten içerir; bu araç meta verisi\n" +
        "eksik (ör. dışa aktarılmış TIFF) dosyalar içindir.",
        "0.26")
    if (input == null) { println "İptal."; return }
    try {
        newPx = Double.parseDouble(input.toString().trim().replace(',' as char, '.' as char))
    } catch (Exception e) {
        Dialogs.showErrorMessage("Geçersiz değer", "Sayısal bir µm/px değeri girin (ör. 0.26).")
        return
    }
    if (!(newPx > 0)) {
        Dialogs.showErrorMessage("Geçersiz değer", "Piksel boyutu pozitif olmalı.")
        return
    }
    if (newPx < 0.05 || newPx > 2.0) {
        def ok = Dialogs.showConfirmDialog("Sıra dışı değer",
            String.format(java.util.Locale.US,
                "%.4f µm/px tipik patoloji aralığının (0.05–2.0) dışında. Yine de uygula?", newPx))
        if (!ok) return
    }
    methodNote = "Doğrudan giriş"
} else { // ruler
    def sel = QP.getSelectedObject()
    def roi = sel?.getROI()
    if (sel == null || roi == null || !roi.isLine()) {
        Dialogs.showErrorMessage("Çizgi anotasyonu yok",
            "Cetvelden ölçmek için:\n" +
            "  1. Araç çubuğundan Çizgi (Line) aracını seçin\n" +
            "  2. Bilinen uzunlukta bir yapının üzerine bir çizgi çizin\n" +
            "  3. Çizgi seçili iken bu betiği tekrar çalıştırın")
        return
    }
    double lenPx = roi.getLength()   // çizgi uzunluğu — piksel cinsinden
    if (!(lenPx > 0)) {
        Dialogs.showErrorMessage("Sıfır uzunluk", "Çizginin uzunluğu sıfır görünüyor; daha uzun bir çizgi çizin.")
        return
    }
    def lenInput = Dialogs.showInputDialog("Kalibrasyon — cetvel",
        String.format(java.util.Locale.US,
            "Çizgi uzunluğu: %.1f piksel.\n\nBu çizginin gerçek uzunluğu kaç µm?\n" +
            "(ör. 100 µm'lik bir ölçek çubuğu için 100)", lenPx),
        "100")
    if (lenInput == null) { println "İptal."; return }
    double realUm
    try {
        realUm = Double.parseDouble(lenInput.toString().trim().replace(',' as char, '.' as char))
    } catch (Exception e) {
        Dialogs.showErrorMessage("Geçersiz değer", "Sayısal bir uzunluk (µm) girin.")
        return
    }
    if (!(realUm > 0)) {
        Dialogs.showErrorMessage("Geçersiz değer", "Uzunluk pozitif olmalı.")
        return
    }
    newPx = realUm / lenPx
    methodNote = String.format(java.util.Locale.US, "Cetvel: %.1f µm ÷ %.1f px", realUm, lenPx)
}

// ── 3) Açık görüntüye uygula (bellekte) ──
def applyMeta = { imgData, double px ->
    def srv = imgData.getServer()
    def newMeta = new ImageServerMetadata.Builder(srv.getMetadata())
        .pixelSizeMicrons(px, px)
        .build()
    imgData.updateServerMetadata(newMeta)
}
applyMeta(imageData, newPx)

// Projedeyse açık görüntüyü diske de yaz (kalıcı olsun) — best-effort
boolean persisted = false
def project = QP.getProject()
def currentEntry = (project != null) ? QP.getProjectEntry() : null
if (currentEntry != null) {
    try {
        currentEntry.saveImageData(imageData)
        persisted = true
    } catch (Throwable t) {
        println "Uyarı: açık görüntü diske yazılamadı (${t.getClass().getSimpleName()})."
    }
}

// Görüntüleyiciyi tazele (ölçek çubuğu/ruler güncellensin)
javafx.application.Platform.runLater {
    try { qupath.lib.gui.QuPathGUI.getInstance()?.getViewer()?.repaint() } catch (Throwable ignored) {}
}

// ── 4) İsteğe bağlı: proje genelinde kalibre edilmemişlere uygula (yalnız Mod A) ──
int batchUpdated = 0
int batchSkipped = 0
boolean batchRan = false
if (mode == "direct" && project != null && !isHeadless) {
    def doBatch = Dialogs.showConfirmDialog("Proje geneli",
        String.format(java.util.Locale.US,
            "Projedeki TÜM kalibre edilmemiş görüntülere de %.4f µm/px uygulansın mı?\n" +
            "(Zaten kalibre olan görüntülere dokunulmaz.)", newPx))
    if (doBatch) {
        batchRan = true
        for (entry in project.getImageList()) {
            try {
                if (currentEntry != null && entry == currentEntry) continue
                entry.readImageData().withCloseable { ed ->
                    if (!ed.getServer().getPixelCalibration().hasPixelSizeMicrons()) {
                        applyMeta(ed, newPx)
                        entry.saveImageData(ed)
                        batchUpdated++
                    } else {
                        batchSkipped++
                    }
                }
            } catch (Throwable t) {
                println "Uyarı: ${entry.getImageName()} güncellenemedi (${t.getClass().getSimpleName()})."
            }
        }
    }
}

// ── 5) Sonuç ──
def body = new StringBuilder()
body << "Kalibrasyon güncellendi.\n\n"
body << "  Yöntem        : ${methodNote}\n"
body << "  Önceki        : ${curStr}\n"
body << String.format(java.util.Locale.US, "  Yeni          : %.4f µm/px\n", newPx)
body << "  Diske yazıldı : " + (persisted ? "evet (proje)" : (project != null ? "hayır" : "proje yok — yalnız bellek")) + "\n"
if (batchRan) {
    body << String.format(java.util.Locale.US, "  Proje geneli  : %d güncellendi, %d zaten kalibre\n", batchUpdated, batchSkipped)
}
body << "\nDoğrulama: Ruler aracıyla tipik bir tümör çekirdeği çapı ~8–12 µm görünmeli.\n"
body << "\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
showResultWindow("Kalibrasyon tamam", body.toString())

println String.format(java.util.Locale.US, "Kalibrasyon: %s → %.4f µm/px (%s)", curStr, newPx, methodNote)
