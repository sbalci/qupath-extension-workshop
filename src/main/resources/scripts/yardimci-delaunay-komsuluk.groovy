/**
 * Yardımcı - Delaunay Komşuluk Özellikleri
 * -----------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Hücreler arasında Delaunay komşuluk ağı kurar ve her hücreye komşu
 * sayısı + komşu mesafesi ölçümleri ekler. Doku mimarisinin (hücre-hücre
 * bağlantısallığı) sayısal bir betimidir.
 *
 * ÖNERİLEN YOL — GUI komutu (en güvenilir):
 *   [Analyze → Spatial analysis → Delaunay cluster features 2D]
 *   Bu menü komutu her QuPath sürümünde çalışır ve her hücreye
 *   "Delaunay: ..." ölçümlerini ekler. Atölyede bu yolu kullanın.
 *
 * BU BETİK:
 *   Aynı komutu betikten otomatikleştirmeyi dener. Eklenti/sürüm farkı
 *   nedeniyle başarısız olursa GUI yolunu söyler (yıkıcı bir şey yapmaz).
 *
 *   ⚠️ DOĞRULA: aşağıdaki plugin sınıf adı QuPath sürümleri arasında
 *   değişebilir. GUI komutunu bir kez çalıştırıp [Automate → Show
 *   workflow] ile kaydı açın; "runPlugin('...', '{...}')" satırındaki
 *   gerçek sınıf adını ve parametreleri buraya kopyalayın.
 *
 * GİRDİ (ÖNKOŞUL):
 *   • Slaytta hücre tespitleri olmalı (Modül 2/3/5/7). Betik tespit YAPMAZ.
 *
 * ÇIKTI (başarılıysa):
 *   • Her hücre: "Delaunay: Num neighbors" vb. ölçümler
 *   • Kilitli "Delaunay Özet": ortalama komşu sayısı
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
            textArea.setWrapText(true)
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

            stage.setScene(new javafx.scene.Scene(root, 720, 520))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
double distanceThresholdMicrons = 0.0   // 0 = sınırsız (saf Delaunay); >0 = uzun kenarları kes

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce hücre tespiti yapılmış bir slayt açın.")
    return
}
def cells = QP.getDetectionObjects()
if (cells.size() < 3) {
    def msg = "Delaunay üçgenlemesi için en az 3 hücre gerekir.\nÖnce Modül 2/3/5/7 ile tespit yapın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Yetersiz hücre", msg)
    return
}

// ── 2) GUI komutunu betikten dene (sürüm farkına dayanıklı) ─────────
def guiHelp =
    "Otomatik Delaunay başarısız oldu (sürüm/eklenti farkı).\n\n" +
    "Lütfen menü komutunu kullanın:\n" +
    "  [Analyze → Spatial analysis → Delaunay cluster features 2D]\n\n" +
    "Sonra (isteğe bağlı) bu betiği tekrar çalıştırın; eklenen\n" +
    "'Delaunay: Num neighbors' ölçümlerinden özet üretir.\n\n" +
    "Geliştirici notu: gerçek sınıf adını öğrenmek için komutu bir kez\n" +
    "GUI'den çalıştırın, [Automate → Show workflow] kaydındaki\n" +
    "runPlugin(...) satırını bu betiğe kopyalayın."

QP.selectObjects(cells)
boolean ran = false
try {
    // DOĞRULA: sınıf adı QuPath sürümüne göre değişebilir.
    QP.runPlugin(
        'qupath.lib.plugins.objects.DelaunayClusteringPlugin',
        '{' +
            '"distanceThresholdMicrons":' + distanceThresholdMicrons + ',' +
            '"limitByClass":false,' +
            '"addClusterMeasurements":true' +
        '}'
    )
    ran = true
} catch (Throwable t) {
    println "Delaunay plugin çağrısı başarısız: ${t.getClass().getSimpleName()} — ${t.getMessage()}"
}

if (!ran) {
    showResultWindow("Delaunay — GUI gerekli", guiHelp)
    return
}

// ── 3) Komşu sayısı ölçümünü oku (anahtar adı sürüme göre değişebilir) ──
def neighborKeys = ["Delaunay: Num neighbors", "Delaunay: num neighbors", "Cluster: Num neighbors"]
def numNeighbors = { cell ->
    for (k in neighborKeys) { def v = cell.measurements[k]; if (v != null) return v as double }
    return Double.NaN
}
def vals = []
cells.each { c -> def v = numNeighbors(c); if (!Double.isNaN(v)) vals << v }

if (vals.isEmpty()) {
    showResultWindow("Delaunay çalıştı — ölçüm okunamadı",
        "Delaunay komutu çalıştı ama beklenen 'Delaunay: Num neighbors' ölçümü\n" +
        "bulunamadı (anahtar adı sürümde farklı olabilir).\n\n" +
        "Measurement tablosunda 'Delaunay:' ile başlayan sütunlara bakın;\n" +
        "ölçümler hücrelere eklendi ve Modül 9 ile dışa aktarılabilir.\n\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.")
    return
}

double meanNeighbors = vals.sum() / vals.size()

// ── 4) Kilitli özet anotasyonu ──────────────────────────────────────
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == "Delaunay Özet" }, false)
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName("Delaunay Özet")
summary.measurements['Hücre sayısı']            = cells.size() as double
summary.measurements['Ortalama komşu sayısı']   = meanNeighbors
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

def body = new StringBuilder()
body << "DELAUNAY KOMŞULUK\n"
body << "════════════════════\n\n"
body << String.format(java.util.Locale.US, "Hücre sayısı          : %,d%n", cells.size())
body << String.format(java.util.Locale.US, "Ortalama komşu sayısı : %.2f%n", meanNeighbors)
body << "\n"
body << "Her hücreye 'Delaunay: ...' ölçümleri eklendi (komşu sayısı/mesafesi).\n"
body << "Modül 9 ile dışa aktarılır.\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Delaunay komşuluk", body.toString())
println String.format(java.util.Locale.US, "✓ Delaunay özeti yazıldı (ortalama %.2f komşu).", meanNeighbors)
