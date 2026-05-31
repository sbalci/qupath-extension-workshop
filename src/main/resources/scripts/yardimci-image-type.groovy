/**
 * Yardımcı - Görüntü Tipi Ayarla
 * --------------------------------
 * QuPath'in **Image type** ayarını tek tıkla değiştirir — açık slayt için ya da
 * projedeki TÜM slaytlar için. Doğru image type, sonraki tüm boya analizlerinin
 * (Modül 2–7) düzgün çalışması için zorunludur:
 *   • H&E slaytları → BRIGHTFIELD_H_E
 *   • İHK / DAB slaytları → BRIGHTFIELD_H_DAB
 *   • Diğer brightfield → BRIGHTFIELD_OTHER
 *   • Floresan → FLUORESCENCE
 *
 * NE YAPAR:
 *   1. Görüntü tipi seçici dialog (radio button)
 *   2. Kapsam seçici (yalnızca aktif slayt / projedeki tüm slaytlar)
 *   3. Toplu uygulama + ImageData kaydetme
 *   4. Sonuç penceresinde hangi slaytların güncellendiği listelenir
 *
 * NEDEN ÖNEMLİ:
 *   "Brightfield (other)" veya "Fluorescence" olarak ayarlı bir İHK slaytında
 *   Positive Cell Detection "Hematoxylin OD" kanalını bulamaz ve
 *   "Unable to set parameter detectionImageBrightfield" hatası verir.
 *   Bu betik o adımdan önce hızlıca toplu düzeltme yapar.
 *
 * NOT: Renk dekonvolüsyon vektörleri varsayılana sıfırlanır. Önceden kalibre
 * edilmiş özel vektörleriniz varsa onları manuel yeniden uygulamanız gerekir.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.images.ImageData

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ──────────────────────────────────────────────────────────────
// Görüntü tipi etiketleri ve enum karşılıkları
// ──────────────────────────────────────────────────────────────
def TYPE_OPTIONS = [
    ["BRIGHTFIELD_H_DAB",  "Brightfield (H-DAB) — İHK / DAB slaytları (Modül 3, 3b, 4, 5, 7)"],
    ["BRIGHTFIELD_H_E",    "Brightfield (H&E) — Hematoksilen-Eozin (Modül 2, 6)"],
    ["BRIGHTFIELD_OTHER",  "Brightfield (other) — diğer brightfield boyamaları"],
    ["FLUORESCENCE",       "Fluorescence — floresan / immünofloresan"],
    ["OTHER",              "Other — bilinmeyen / belirsiz"]
]

def SCOPE_OPTIONS = [
    ["current", "Yalnızca AÇIK slayt"],
    ["project", "Projedeki TÜM slaytlar (toplu)"]
]

// ──────────────────────────────────────────────────────────────
// Seçim diyaloğu — JavaFX radio buttons
// ──────────────────────────────────────────────────────────────
def showSelectionDialog = { ->
    if (isHeadless) {
        println "=== Görüntü tipi ayarla (headless) ==="
        println "Varsayılan: BRIGHTFIELD_H_DAB, yalnızca açık slayt"
        return [type: "BRIGHTFIELD_H_DAB", scope: "current"]
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def result = new java.util.concurrent.atomic.AtomicReference<Map>(null)

    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle("Görüntü Tipi Ayarla")
            stage.setAlwaysOnTop(true)

            // Görüntü tipi grubu
            def typeGroup = new javafx.scene.control.ToggleGroup()
            def typeBox = new javafx.scene.layout.VBox(6)
            typeBox.setPadding(new javafx.geometry.Insets(4, 0, 4, 0))
            def typeLabel = new javafx.scene.control.Label("Hedef görüntü tipi:")
            typeLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;")
            typeBox.getChildren().add(typeLabel)
            TYPE_OPTIONS.eachWithIndex { opt, idx ->
                def rb = new javafx.scene.control.RadioButton(opt[1])
                rb.setUserData(opt[0])
                rb.setToggleGroup(typeGroup)
                if (idx == 0) rb.setSelected(true)   // BRIGHTFIELD_H_DAB varsayılan
                typeBox.getChildren().add(rb)
            }

            // Kapsam grubu
            def scopeGroup = new javafx.scene.control.ToggleGroup()
            def scopeBox = new javafx.scene.layout.VBox(6)
            scopeBox.setPadding(new javafx.geometry.Insets(10, 0, 4, 0))
            def scopeLabel = new javafx.scene.control.Label("Kapsam:")
            scopeLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;")
            scopeBox.getChildren().add(scopeLabel)
            SCOPE_OPTIONS.eachWithIndex { opt, idx ->
                def rb = new javafx.scene.control.RadioButton(opt[1])
                rb.setUserData(opt[0])
                rb.setToggleGroup(scopeGroup)
                if (idx == 0) rb.setSelected(true)
                scopeBox.getChildren().add(rb)
            }

            def info = new javafx.scene.control.Label(
                "Not: Renk dekonvolüsyon vektörleri varsayılan değerlere sıfırlanır.\n" +
                "Özel kalibre vektörler kullanıyorsanız, sonra yeniden uygulayın."
            )
            info.setWrapText(true)
            info.setStyle("-fx-font-size: 11px; -fx-text-fill: #555; -fx-padding: 8 0 0 0;")

            def container = new javafx.scene.layout.VBox(typeBox, scopeBox, info)
            container.setPadding(new javafx.geometry.Insets(12))

            // Düğmeler
            def okBtn = new javafx.scene.control.Button("Uygula")
            okBtn.setDefaultButton(true)
            okBtn.setOnAction({
                def selectedType = typeGroup.getSelectedToggle()?.getUserData() as String
                def selectedScope = scopeGroup.getSelectedToggle()?.getUserData() as String
                if (selectedType && selectedScope) {
                    result.set([type: selectedType, scope: selectedScope])
                }
                stage.close()
            })
            def cancelBtn = new javafx.scene.control.Button("İptal")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({ stage.close() })

            stage.setOnHidden({ latch.countDown() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def buttons = new javafx.scene.layout.HBox(10, spacer, cancelBtn, okBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(container)
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 620, 460))
            stage.show()
        } catch (Throwable t) {
            result.set(null)
            latch.countDown()
        }
    }

    latch.await()
    return result.get()
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
            def buttons = new javafx.scene.layout.HBox(10, spacer, copyBtn, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(textArea)
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 720, 520))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// String → ImageData.ImageType enum çözümü
// ──────────────────────────────────────────────────────────────
def resolveImageType = { String name ->
    try {
        return ImageData.ImageType.valueOf(name)
    } catch (Throwable t) {
        return null
    }
}

// ──────────────────────────────────────────────────────────────
// 1) Kullanıcı seçimi
// ──────────────────────────────────────────────────────────────
def selection = showSelectionDialog()
if (selection == null) {
    println "Kullanıcı iptal etti."
    return
}
def targetTypeName = selection.type
def scope = selection.scope
def targetType = resolveImageType(targetTypeName)
if (targetType == null) {
    Dialogs.showErrorMessage("Geçersiz tip", "Görüntü tipi tanınmadı: ${targetTypeName}")
    return
}

// ──────────────────────────────────────────────────────────────
// 2) Uygulama — açık slayt VEYA tüm proje
// ──────────────────────────────────────────────────────────────
def t0 = System.currentTimeMillis()
def updated = []
def skipped = []

if (scope == "current") {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) {
        Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir slayt açın.")
        return
    }
    def previous = imageData.getImageType()?.toString() ?: "—"
    if (imageData.getImageType() == targetType) {
        skipped << "Açık slayt (zaten ${targetTypeName})"
    } else {
        imageData.setImageType(targetType)
        // Açık görüntüde değişikliği diske de yansıt
        try {
            def entry = QP.getProjectEntry()
            if (entry != null) {
                entry.saveImageData(imageData)
            }
        } catch (Throwable t) {
            println "⚠ ImageData kaydedilemedi (proje açık değil mi?): ${t.message}"
        }
        updated << "Açık slayt: ${previous} → ${targetTypeName}"
    }
} else { // "project"
    def project = QP.getProject()
    if (project == null) {
        Dialogs.showErrorMessage("Proje açık değil",
            "Toplu güncelleme için bir QuPath projesi gerekir.\n" +
            "[File → Project → Create / Open project] ile bir proje açın.")
        return
    }
    def entries = project.getImageList()
    println "Projedeki ${entries.size()} görüntü taranıyor..."
    entries.each { entry ->
        try {
            def imageData = entry.readImageData()
            def previous = imageData.getImageType()?.toString() ?: "—"
            if (imageData.getImageType() == targetType) {
                skipped << "${entry.getImageName()} (zaten ${targetTypeName})"
            } else {
                imageData.setImageType(targetType)
                entry.saveImageData(imageData)
                updated << "${entry.getImageName()}: ${previous} → ${targetTypeName}"
            }
        } catch (Throwable t) {
            skipped << "${entry.getImageName()} (HATA: ${t.getClass().getSimpleName()})"
        }
    }
}

def elapsed = (System.currentTimeMillis() - t0) / 1000.0

// ──────────────────────────────────────────────────────────────
// 3) Sonuç penceresi
// ──────────────────────────────────────────────────────────────
def scopeLabel = scope == "current" ? "Yalnızca açık slayt" : "Projedeki tüm slaytlar"
def summary = new StringBuilder()
summary << String.format(java.util.Locale.US, "Görüntü tipi → %s%n", targetTypeName)
summary << String.format(java.util.Locale.US, "Kapsam        : %s%n", scopeLabel)
summary << String.format(java.util.Locale.US, "Süre          : %.2f sn%n%n", elapsed)
summary << String.format(java.util.Locale.US, "📋 Güncellenen (%d)%n", updated.size())
summary << "──────────────────────────────────\n"
if (updated.isEmpty()) {
    summary << "  (yok)\n"
} else {
    updated.each { summary << "  • ${it}\n" }
}
summary << "\n"
summary << String.format(java.util.Locale.US, "📋 Atlanan (%d)%n", skipped.size())
summary << "──────────────────────────────────\n"
if (skipped.isEmpty()) {
    summary << "  (yok)\n"
} else {
    skipped.each { summary << "  • ${it}\n" }
}
summary << "\n"
summary << "Not: Renk dekonvolüsyon vektörleri varsayılana sıfırlandı.\n"
summary << "Özel kalibre vektörlerinizi yeniden uygulamayı unutmayın."

showResultWindow("Görüntü Tipi Ayarlandı 🎨", summary.toString())

println "─────────────────────────────────────"
println "Görüntü tipi: ${targetTypeName}  |  Kapsam: ${scopeLabel}"
println "  Güncellenen: ${updated.size()}  |  Atlanan: ${skipped.size()}  |  Süre: ${String.format(java.util.Locale.US, '%.2f', elapsed)} sn"
println "─────────────────────────────────────"
