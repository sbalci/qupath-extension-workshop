/**
 * Yardımcı - Tespitleri Sil
 * --------------------------
 * Hücre tespitlerini temizlemek için tek-tıkla bir araç. Atölye sırasında
 * "Delete objects → Keep descendant objects → Yes" diyerek anotasyonu silip
 * çocuk tespitleri **slayda öksüz** bırakırsanız, bu betik onları temizler.
 *
 * İKİ MOD:
 *   • "Tümünü sil"           — clearDetections() çağrısı, slayttaki TÜM
 *                              detection nesnelerini siler.
 *   • "Sadece orphan'ları"   — yalnızca üst nesnesi kök (slayt) olan
 *                              tespitleri siler — bir anotasyon içinde
 *                              hâlâ ait olan tespitler korunur.
 *
 * KULLANIM:
 *   1. [Automate → Project scripts → Yardımcı - Tespitleri sil]
 *   2. Sayım önizlemesi görünür → istediğiniz butona tıklayın
 *   3. Sonuç penceresi kaç nesnenin silindiğini gösterir
 *
 * BU BETİK ANOTASYONLARA DOKUNMAZ — yalnızca detection sınıfı nesneleri siler.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ──────────────────────────────────────────────────────────────
// Üç-seçenekli onay penceresi: Tümünü sil / Sadece orphan / İptal
// Mevcut waitForConfirm yalnızca 2 düğmeli olduğundan burada özel
// JavaFX pencere kuruyoruz. Headless modda println'e döner.
// ──────────────────────────────────────────────────────────────
def chooseAction = { String windowTitle, String windowBody ->
    if (isHeadless) {
        println "=== ${windowTitle} ===\n${windowBody}\n=================="
        return "orphan"   // headless varsayılan: güvenli seçim
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def choice = new java.util.concurrent.atomic.AtomicReference<String>("cancel")

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

            def orphanBtn = new javafx.scene.control.Button("Sadece orphan'ları sil")
            orphanBtn.setDefaultButton(true)
            orphanBtn.setOnAction({ choice.set("orphan"); stage.close() })

            def allBtn = new javafx.scene.control.Button("Tümünü sil")
            allBtn.setOnAction({ choice.set("all"); stage.close() })

            def cancelBtn = new javafx.scene.control.Button("İptal")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({ choice.set("cancel"); stage.close() })

            stage.setOnHidden({ latch.countDown() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)

            def buttons = new javafx.scene.layout.HBox(10, cancelBtn, spacer, allBtn, orphanBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(scrollPane)
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 620, 400))
            stage.show()
        } catch (Throwable t) {
            choice.set("cancel")
            latch.countDown()
        }
    }

    latch.await()
    return choice.get()
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

            def closeBtn = new javafx.scene.control.Button("Kapat")
            closeBtn.setDefaultButton(true)
            closeBtn.setOnAction({ stage.close() })

            def buttons = new javafx.scene.layout.HBox(10, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(textArea)
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 560, 320))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 1) Ön kontrol — açık görüntü
// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir slayt açın.")
    return
}

// ──────────────────────────────────────────────────────────────
// 2) Sayım — toplam vs orphan vs anotasyon-altında
//    Orphan: parent kök (slayt) → bir anotasyona değil doğrudan slayta bağlı.
//    Bu durum çoğunlukla "Delete annotation → Keep descendant objects" sonrası
//    oluşur.
// ──────────────────────────────────────────────────────────────
def allDetections = QP.getDetectionObjects()
def orphanDetections = allDetections.findAll { it.getParent()?.isRootObject() }
def nestedDetections = allDetections.size() - orphanDetections.size()

if (allDetections.isEmpty()) {
    Dialogs.showMessageDialog("Silinecek tespit yok", "Bu slaytta hiç detection nesnesi bulunmuyor.")
    return
}

// ──────────────────────────────────────────────────────────────
// 3) Kullanıcıya seç ettir
// ──────────────────────────────────────────────────────────────
def secim = chooseAction(
    "Tespitleri Sil — Hangileri?",
    "Bu slayttaki detection durumu:\n\n" +
    String.format("  Toplam tespit       : %,d\n", allDetections.size()) +
    String.format("  Orphan (anotasyon yok): %,d\n", orphanDetections.size()) +
    String.format("  Anotasyon altında    : %,d\n\n", nestedDetections) +
    "İki seçenek:\n\n" +
    "  • Sadece orphan'ları sil (varsayılan, güvenli):\n" +
    "    Bir anotasyona ait olmayan tespitleri temizler.\n" +
    "    Anotasyon altındaki ölçümler korunur.\n\n" +
    "  • Tümünü sil:\n" +
    "    Slayttaki BÜTÜN tespitleri siler — anotasyonlar dokunulmaz.\n\n" +
    "Anotasyonlar (ROI'ler) HİÇBİR DURUMDA silinmez."
)

if (secim == "cancel") {
    println "Kullanıcı iptal etti."
    return
}

// ──────────────────────────────────────────────────────────────
// 4) Sil + sonuç göster
// ──────────────────────────────────────────────────────────────
def t0 = System.currentTimeMillis()
def silinen = 0
def mod = ""

if (secim == "all") {
    silinen = allDetections.size()
    QP.clearDetections()
    mod = "Tüm tespitler silindi"
} else { // "orphan"
    silinen = orphanDetections.size()
    if (silinen > 0) {
        QP.removeObjects(orphanDetections, true)
    }
    mod = "Sadece orphan tespitler silindi (anotasyon altındakiler korundu)"
}

QP.fireHierarchyUpdate()
def elapsed = (System.currentTimeMillis() - t0) / 1000.0

def kalan = QP.getDetectionObjects().size()

showResultWindow(
    "Temizlik Tamamlandı 🧹",
    String.format(
        "%s.\n\n" +
        "📊 Özet\n" +
        "──────────────\n" +
        "  Silinen tespit : %,d\n" +
        "  Kalan tespit   : %,d\n" +
        "  Süre           : %.2f sn\n\n" +
        "Anotasyonlar değiştirilmedi.",
        mod, silinen, kalan, elapsed
    )
)

println "─────────────────────────────────────"
println "Temizlik: ${mod}"
println "  Silinen: ${silinen}  |  Kalan: ${kalan}  |  Süre: ${String.format('%.2f', elapsed)} sn"
println "─────────────────────────────────────"
