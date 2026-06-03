/**
 * Yardımcı - Yoğunluk Haritası (ızgara)
 * --------------------------------------
 * Hücre tespitlerini kareli bir ızgaraya böler ve her karede hücre
 * **yoğunluğunu (hücre/mm²)** ölçer. Karelere yoğunluğa göre renk verince
 * doku boyunca hücresellik HARİTASI ortaya çıkar.
 *
 * QuPath KARŞILIĞI (GUI):
 *   [Analyze → Density maps] yerleşik komutu yumuşak (Gaussian) bir ısı
 *   haritası üretir ve görsel olarak daha şıktır. Bu betik aynı fikrin
 *   eklenti gerektirmeyen, SAYISAL (kare başına hücre/mm²) ve Modül 9 ile
 *   dışa aktarılabilen karşılığıdır.
 *
 * GİRDİ (ÖNKOŞUL):
 *   • Slaytta hücre tespitleri olmalı (Modül 2/3/5/7). Betik tespit YAPMAZ.
 *
 * ÇIKTI:
 *   • Her dolu kare: kilitli "Yoğunluk grid r,c" anotasyonu +
 *     "Hücre yoğunluğu (hücre/mm2)", "Hücre sayısı"
 *   • Kilitli "Yoğunluk Özet": ortalama / maksimum yoğunluk (hot-spot)
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.lib.gui.dialogs.Dialogs
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

            stage.setScene(new javafx.scene.Scene(root, 700, 500))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
double tileSizeMicrons = 250.0   // ızgara karesi kenarı (µm)
int    maxTiles        = 600     // hiyerarşiyi boğmamak için üst sınır

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce hücre tespiti yapılmış bir slayt açın.")
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok", "Slaytta piksel boyutu (µm) tanımlı değil; yoğunluk hesaplanamaz.")
    return
}

def cells = QP.getDetectionObjects().findAll { it.getROI() != null }
int n = cells.size()
if (n == 0) {
    def msg = "Bu slaytta hücre tespiti yok.\nÖnce Modül 2/3/5/7 ile tespit yapın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Tespit yok", msg)
    return
}

// ── 2) Izgara sınırları + boyutu ────────────────────────────────────
double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY
double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY
cells.each { c ->
    def r = c.getROI()
    double cx = r.getCentroidX(), cy = r.getCentroidY()
    if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
    if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
}
double tilePx = tileSizeMicrons / pw
int nCols = Math.max(1, (int) Math.ceil((maxX - minX) / tilePx))
int nRows = Math.max(1, (int) Math.ceil((maxY - minY) / tilePx))
while ((long) nCols * nRows > maxTiles) {
    tilePx *= 1.4
    nCols = Math.max(1, (int) Math.ceil((maxX - minX) / tilePx))
    nRows = Math.max(1, (int) Math.ceil((maxY - minY) / tilePx))
}
double tileAreaMm2 = (tilePx * pw) * (tilePx * ph) / 1_000_000.0

// ── 3) Hücreleri karelere ata ───────────────────────────────────────
int[] tileCount = new int[nCols * nRows]
cells.each { c ->
    def r = c.getROI()
    int col = (int) ((r.getCentroidX() - minX) / tilePx); if (col >= nCols) col = nCols - 1
    int row = (int) ((r.getCentroidY() - minY) / tilePx); if (row >= nRows) row = nRows - 1
    tileCount[row * nCols + col]++
}

// ── 4) Eski grid/özet temizle, dolu kareleri oluştur ────────────────
QP.removeObjects(QP.getAnnotationObjects().findAll {
    it.getName() != null && (it.getName().startsWith("Yoğunluk grid") || it.getName() == "Yoğunluk Özet")
}, false)

def plane = qupath.lib.regions.ImagePlane.getDefaultPlane()
def newTiles = []
def densities = []
double maxDensity = 0.0
int filledTiles = 0
for (int row = 0; row < nRows; row++) {
    for (int col = 0; col < nCols; col++) {
        int cnt = tileCount[row * nCols + col]
        if (cnt == 0) continue
        filledTiles++
        double density = tileAreaMm2 > 0 ? cnt / tileAreaMm2 : 0.0
        densities << density
        if (density > maxDensity) maxDensity = density

        double x = minX + col * tilePx
        double y = minY + row * tilePx
        def tile = qupath.lib.objects.PathObjects.createAnnotationObject(
            qupath.lib.roi.ROIs.createRectangleROI(x, y, tilePx, tilePx, plane))
        tile.setName(String.format(java.util.Locale.US, "Yoğunluk grid %d,%d", row, col))
        tile.measurements['Hücre yoğunluğu (hücre/mm2)'] = density
        tile.measurements['Hücre sayısı'] = cnt as double
        tile.setLocked(true)
        newTiles << tile
    }
}

double meanDensity = densities.isEmpty() ? 0.0 : densities.sum() / densities.size()

// ── 5) Kilitli özet anotasyonu ──────────────────────────────────────
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(), plane))
summary.setName("Yoğunluk Özet")
summary.measurements['Hücre sayısı (toplam)']             = n as double
summary.measurements['Dolu kare sayısı']                  = filledTiles as double
summary.measurements['Kare boyutu (µm)']                  = tilePx * pw
summary.measurements['Ortalama yoğunluk (hücre/mm2)']     = meanDensity
summary.measurements['Maksimum yoğunluk (hücre/mm2)']     = maxDensity
summary.setLocked(true)

QP.addObjects(newTiles)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

// ── 6) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "HÜCRE YOĞUNLUĞU HARİTASI (ızgara)\n"
body << "══════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Hücre (toplam)        : %,d%n", n)
body << String.format(java.util.Locale.US, "Izgara                : %d sütun × %d satır, kare ≈ %.0f µm%n", nCols, nRows, tilePx * pw)
body << String.format(java.util.Locale.US, "Dolu kare             : %d%n", filledTiles)
body << "──────────────────────────────────\n"
body << String.format(java.util.Locale.US, "Ortalama yoğunluk     : %.0f hücre/mm²%n", meanDensity)
body << String.format(java.util.Locale.US, "Maksimum (hot-spot)   : %.0f hücre/mm²%n", maxDensity)
body << "\n"
body << "Kareleri 'Hücre yoğunluğu (hücre/mm2)' ölçümüne göre renklendirin\n"
body << "(Measure → Show measurement maps) → yoğunluk haritası.\n"
body << "Yumuşak ısı haritası için: [Analyze → Density maps].\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Yoğunluk haritası", body.toString())
println String.format(java.util.Locale.US, "✓ %d dolu kare + özet yazıldı (maks %.0f hücre/mm²).", filledTiles, maxDensity)
