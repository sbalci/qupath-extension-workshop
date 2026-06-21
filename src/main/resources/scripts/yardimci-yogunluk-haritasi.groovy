/**
 * Yardımcı - Yoğunluk Haritası (ızgara)
 * --------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
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
 *   • Analiz sınırını kesen her kare (sıfır hücreli kareler dahil): kilitli "Yoğunluk grid r,c" +
 *     "Hücre yoğunluğu (hücre/mm2)", "Hücre sayısı"
 *   • Kilitli "Yoğunluk Özet": birleşik ROI yoğunluğu ve yalnız tam karolar
 *     arasındaki maksimum yoğunluk
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs
import qupath.lib.roi.RoiTools

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
double tileSizeMicrons = 250.0
int maxTiles = 600

// ── 1) Açık görüntü, kalibrasyon ve açıkça seçilmiş analiz sınırı ──
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage('Görüntü açık değil', 'Önce hücre tespiti yapılmış bir slayt açın.')
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage('Kalibrasyon yok', 'Piksel boyutu tanımlı değil; hücre/mm² hesaplanamaz.')
    return
}

def targets = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI()?.isArea() &&
        !(it.getName() ?: '').startsWith('Yoğunluk grid') && it.getName() != 'Yoğunluk Özet'
}
if (targets.isEmpty()) {
    Dialogs.showErrorMessage('Analiz alanı seçili değil', 'Yoğunluk haritası için ölçülecek anotasyonları açıkça seçin.')
    return
}
def analysisROI = RoiTools.union(targets.collect { it.getROI() })
if (analysisROI == null || analysisROI.isEmpty() || !analysisROI.isArea()) {
    Dialogs.showErrorMessage('Geçersiz analiz alanı', 'Seçili anotasyonlar geçerli bir analiz alanı oluşturmuyor.')
    return
}

def cells = QP.getDetectionObjects().findAll { detection ->
    def roi = detection.getROI()
    detection.isCell() && roi != null && analysisROI.contains(roi.getCentroidX(), roi.getCentroidY())
}
int n = cells.size()

// ── 2) Fiziksel kare boyutuyla, analiz ROI sınırlarından ızgara ─────
double tilePxX = tileSizeMicrons / pw
double tilePxY = tileSizeMicrons / ph
double minX = analysisROI.getBoundsX()
double minY = analysisROI.getBoundsY()
double width = analysisROI.getBoundsWidth()
double height = analysisROI.getBoundsHeight()
int nCols = Math.max(1, (int)Math.ceil(width / tilePxX))
int nRows = Math.max(1, (int)Math.ceil(height / tilePxY))
while ((long)nCols * nRows > maxTiles) {
    tilePxX *= 1.4
    tilePxY *= 1.4
    nCols = Math.max(1, (int)Math.ceil(width / tilePxX))
    nRows = Math.max(1, (int)Math.ceil(height / tilePxY))
}

int[] tileCount = new int[nCols * nRows]
cells.each { cell ->
    def roi = cell.getROI()
    int col = Math.min(nCols - 1, Math.max(0, (int)((roi.getCentroidX() - minX) / tilePxX)))
    int row = Math.min(nRows - 1, Math.max(0, (int)((roi.getCentroidY() - minY) / tilePxY)))
    tileCount[row * nCols + col]++
}

// ── 3) Eski çıktıyı temizle; ROI ile kesişen sıfır/dolu tüm kareleri yaz ──
QP.removeObjects(QP.getAnnotationObjects().findAll {
    it.getName() != null && (it.getName().startsWith('Yoğunluk grid') || it.getName() == 'Yoğunluk Özet')
}, false)

def plane = analysisROI.getImagePlane()
def newTiles = []
double maxFullTileDensity = Double.NaN
double analyzedAreaMm2 = 0.0
int zeroTiles = 0
double fullTileAreaPx = tilePxX * tilePxY
for (int row = 0; row < nRows; row++) {
    for (int col = 0; col < nCols; col++) {
        double x = minX + col * tilePxX
        double y = minY + row * tilePxY
        def rectangle = ROIs.createRectangleROI(x, y, tilePxX, tilePxY, plane)
        def clipped = RoiTools.intersection([rectangle, analysisROI])
        if (clipped == null || clipped.isEmpty() || !clipped.isArea()) continue

        int count = tileCount[row * nCols + col]
        if (count == 0) zeroTiles++
        double areaMm2 = clipped.getArea() * pw * ph / 1_000_000.0
        if (!(areaMm2 > 0)) continue
        double density = count / areaMm2
        double coverage = clipped.getArea() / fullTileAreaPx
        analyzedAreaMm2 += areaMm2
        if (coverage >= 0.99 && (!Double.isFinite(maxFullTileDensity) || density > maxFullTileDensity)) {
            maxFullTileDensity = density
        }

        def tile = PathObjects.createAnnotationObject(clipped)
        tile.setName(String.format(java.util.Locale.US, 'Yoğunluk grid %d,%d', row, col))
        tile.measurements['Kare analiz alanı (mm2)'] = areaMm2
        tile.measurements['Kare kapsamı (%)'] = 100.0 * coverage
        tile.measurements['Hücre sayısı'] = count as double
        tile.measurements['Hücre yoğunluğu (hücre/mm2)'] = density
        tile.setLocked(true)
        newTiles << tile
    }
}

double overallDensity = analyzedAreaMm2 > 0 ? n / analyzedAreaMm2 : Double.NaN

def summary = PathObjects.createAnnotationObject(analysisROI)
summary.setName('Yoğunluk Özet')
summary.measurements['Hücre sayısı (toplam)'] = n as double
summary.measurements['Analiz alanı (mm2)'] = analyzedAreaMm2
summary.measurements['Kare sayısı'] = newTiles.size() as double
summary.measurements['Sıfır hücreli kare'] = zeroTiles as double
summary.measurements['Kare boyutu (µm)'] = tilePxX * pw
summary.measurements['Genel yoğunluk (hücre/mm2)'] = overallDensity
summary.measurements['Maksimum tam-karo yoğunluğu (hücre/mm2)'] = maxFullTileDensity
summary.setLocked(true)

QP.addObjects(newTiles)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

def fmt = { double value, String pattern ->
    Double.isFinite(value) ? String.format(java.util.Locale.US, pattern, value) : 'hesaplanamadı'
}
def body = new StringBuilder()
body << 'HÜCRE YOĞUNLUĞU HARİTASI\n'
body << '══════════════════════════════════\n\n'
body << String.format(java.util.Locale.US, 'Analiz alanı          : %.3f mm²%n', analyzedAreaMm2)
body << String.format(java.util.Locale.US, 'Hücre (toplam)        : %,d%n', n)
body << String.format(java.util.Locale.US, 'Izgara                : %d × %d; kare ≈ %.0f µm%n', nCols, nRows, tilePxX * pw)
body << String.format(java.util.Locale.US, 'ROI ile kesişen kare  : %,d%n', newTiles.size())
body << String.format(java.util.Locale.US, 'Sıfır hücreli kare    : %,d%n', zeroTiles)
body << '──────────────────────────────────\n'
body << "Genel yoğunluk        : ${fmt(overallDensity, '%.1f hücre/mm²')}\n"
body << "Maksimum tam karo     : ${fmt(maxFullTileDensity, '%.1f hücre/mm²')}\n"
body << '\nGenel yoğunluk = toplam hücre / toplam analiz alanı.\n'
body << 'Kısmi kenar karelerinde gerçek ROI-kesişim alanı payda olarak kullanılır.\n'
body << 'Maksimum değer yalnız yaklaşık tam alanlı karolar arasında hesaplanır.\n'
body << 'Sıfır hücreli kareler toplam analiz alanına dahildir.\n\n'
body << 'Bu çıktı betimsel bir ölçümdür; klinik yorum veya kategori üretmez.'

showResultWindow('Yoğunluk haritası', body.toString())
println String.format(java.util.Locale.US, 'Yoğunluk haritası: %d kare, genel yoğunluk %.1f hücre/mm²', newTiles.size(), overallDensity)
