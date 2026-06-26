/**
 * Yardımcı - Ki-67 Heterojenlik Grid
 * -----------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * MEVCUT Ki-67 tespitlerini (Modül 3a veya Modül 7'den) bir kareli ızgaraya
 * böler, her karede Ki-67 işaretleme indeksini (LI) ölçer ve karelerarası
 * **heterojenlik** ölçütleri üretir: varyasyon katsayısı (CV) ve pozitif
 * hücrelerin uzamsal kümelenmesi için Morisita dağılım indeksi (Iδ).
 *
 * NEDEN?
 *   Tek bir "tümör LI %" değeri bölgesel değişkenliği gizler. Bir tümörde
 *   LI bölgeden bölgeye 5%–40% arası değişebilir (Ki-67 "hot-spot"). Bu
 *   betik bu değişkenliği bir ÖLÇÜME çevirir.
 *
 * GİRDİ (ÖNKOŞUL):
 *   • Slaytta zaten Ki-67 hücre tespitleri olmalı (Modül 3a ya da Modül 7'yi
 *     önce çalıştırın). Betik tespit YAPMAZ; var olanı kullanır.
 *   • Patolog tarafından gözden geçirilmiş "Tumor" anotasyonu zorunludur.
 *
 * ÇIKTI:
 *   • Her ızgara karesi: kilitli "Ki-67 grid r,c" anotasyonu +
 *     "Ki-67 LI (%)", "Hücre sayısı", "Pozitif hücre" ölçümleri
 *     → ölçüme göre renklendirilince heterojenlik HARİTASI olur
 *   • Kilitli "Ki-67 Heterojenlik Özet": tüm hücrelerden ağırlıklı LI,
 *     geçerli karoların eşit-ağırlıklı ortalama/SD/CV değerleri ve Iδ
 *
 * YÖNTEM REFERANSLARI:
 *   • Zhang Z et al. (2023), Mod Pathol — bölgesel Ki-67 heterojenliğinin
 *     dijital kantifikasyonu. doi:10.1016/j.modpat.2022.100017
 *   • Lu W et al. (2024), J Pathol Clin Res 10(1):e346 — ızgara temelli
 *     heterojenlik ölçütleri. doi:10.1002/cjp2.346
 *   • Morisita M (1959) — dağılım indeksi Iδ (≈1 rastgele, >1 kümelenmiş).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
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
            def __footer = new javafx.scene.control.Label("QuPath Atölye Scriptleri · araştırma/eğitim amaçlı")
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons)
            __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)

            stage.setScene(new javafx.scene.Scene(root, 720, 560))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
double tileSizeMicrons = 500.0
int minCellsPerTile = 20
int maxTiles = 400
double dabThreshold = 0.20
String dabKey = 'Nucleus: DAB OD mean'

// ── 1) Görüntü, kalibrasyon ve gözden geçirilmiş Tumor sınırı ──────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage('Görüntü açık değil', 'Önce Ki-67 tespiti yapılmış bir slayt açın.')
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage('Kalibrasyon yok', 'Piksel boyutu tanımlı değil.')
    return
}

// Analiz sınırı yalnız açıkça SEÇİLEN, sınıfı 'Tumor' olan anotasyonlardır;
// seçim yoksa betik durur (gözden geçirilmemiş bölgeleri ölçüme katmamak için).
def tumorObjects = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Tumor'
}
if (tumorObjects.isEmpty()) {
    Dialogs.showErrorMessage('Tumor ROI seçili değil', 'Ki-67 heterojenliği için gözden geçirilmiş Tumor anotasyonlarını seçin.')
    return
}
def analysisROI = RoiTools.union(tumorObjects.collect { it.getROI() })
if (analysisROI == null || analysisROI.isEmpty() || !analysisROI.isArea()) {
    Dialogs.showErrorMessage('Geçersiz Tumor ROI', 'Tumor anotasyonları geçerli bir analiz alanı oluşturmuyor.')
    return
}

def cells = QP.getDetectionObjects().findAll { detection ->
    def roi = detection.getROI()
    def dabValue = detection.measurements[dabKey]
    detection.isCell() && roi != null && dabValue != null &&
        Double.isFinite((dabValue as Number).doubleValue()) &&
        analysisROI.contains(roi.getCentroidX(), roi.getCentroidY())
}
if (cells.isEmpty()) {
    Dialogs.showErrorMessage('Tespit yok', 'Tumor ROI içinde mevcut Ki-67 tespiti bulunamadı.')
    return
}

def isPositive = { detection ->
    String className = detection.getPathClass()?.getName() ?: ''
    if (className.equalsIgnoreCase('Positive') || className.endsWith('+')) return true
    def value = detection.measurements[dabKey]
    return value != null && (value as double) >= dabThreshold
}

// ── 2) ROI sınırlarından fiziksel ızgara ───────────────────────────
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

def tileTotal = new int[nCols * nRows]
def tilePositive = new int[nCols * nRows]
cells.each { cell ->
    def roi = cell.getROI()
    int col = Math.min(nCols - 1, Math.max(0, (int)((roi.getCentroidX() - minX) / tilePxX)))
    int row = Math.min(nRows - 1, Math.max(0, (int)((roi.getCentroidY() - minY) / tilePxY)))
    int index = row * nCols + col
    tileTotal[index]++
    if (isPositive(cell)) tilePositive[index]++
}

// ── 3) ROI-kesişimli tüm kareleri oluştur; sıfır quadratları koru ──
QP.removeObjects(QP.getAnnotationObjects().findAll {
    it.getName() != null && (it.getName().startsWith('Ki-67 grid') || it.getName() == 'Ki-67 Heterojenlik Özet')
}, false)

def plane = analysisROI.getImagePlane()
def newTiles = []
def validTileLI = []
def morisitaCounts = []
int partialTiles = 0
int zeroCellTiles = 0
double fullTileAreaPx = tilePxX * tilePxY
for (int row = 0; row < nRows; row++) {
    for (int col = 0; col < nCols; col++) {
        int index = row * nCols + col
        double x = minX + col * tilePxX
        double y = minY + row * tilePxY
        def rectangle = ROIs.createRectangleROI(x, y, tilePxX, tilePxY, plane)
        def clipped = RoiTools.intersection([rectangle, analysisROI])
        if (clipped == null || clipped.isEmpty() || !clipped.isArea()) continue

        int total = tileTotal[index]
        int positive = tilePositive[index]
        if (total == 0) zeroCellTiles++
        double coverage = clipped.getArea() / fullTileAreaPx
        boolean fullQuadrat = coverage >= 0.99
        if (fullQuadrat) morisitaCounts << positive
        else partialTiles++

        double li = total >= minCellsPerTile ? 100.0 * positive / total : Double.NaN
        if (Double.isFinite(li)) validTileLI << li

        def tile = PathObjects.createAnnotationObject(clipped)
        tile.setName(String.format(java.util.Locale.US, 'Ki-67 grid %d,%d', row, col))
        tile.measurements['Kare kapsamı (%)'] = 100.0 * coverage
        tile.measurements['Hücre sayısı'] = total as double
        tile.measurements['Pozitif hücre'] = positive as double
        tile.measurements['Ki-67 LI (%)'] = li
        tile.setLocked(true)
        newTiles << tile
    }
}

// ── 4) Ağırlıklı genel LI ve betimsel karo dağılımı ────────────────
int totalCells = cells.size()
int totalPositive = cells.count { isPositive(it) } as int
double overallLI = totalCells > 0 ? 100.0 * totalPositive / totalCells : Double.NaN
double meanTileLI = validTileLI.isEmpty() ? Double.NaN : validTileLI.sum() / validTileLI.size()
double sdTileLI = Double.NaN
double cvTileLI = Double.NaN
if (validTileLI.size() > 1) {
    double variance = validTileLI.collect { value -> (value - meanTileLI) * (value - meanTileLI) }.sum() / validTileLI.size()
    sdTileLI = Math.sqrt(variance)
    cvTileLI = meanTileLI > 0 ? 100.0 * sdTileLI / meanTileLI : Double.NaN
}

double sumXX = 0.0
long totalMorisitaPositive = 0
morisitaCounts.each { count ->
    sumXX += (double)count * (count - 1)
    totalMorisitaPositive += count
}
int quadrats = morisitaCounts.size()
double morisita = totalMorisitaPositive > 1 && quadrats > 1
    ? quadrats * sumXX / (totalMorisitaPositive * (totalMorisitaPositive - 1.0))
    : Double.NaN

def summary = PathObjects.createAnnotationObject(analysisROI)
summary.setName('Ki-67 Heterojenlik Özet')
summary.measurements['Ağırlıklı genel LI (%)'] = overallLI
summary.measurements['Karo LI ortalaması (%)'] = meanTileLI
summary.measurements['Karo LI SD (%)'] = sdTileLI
summary.measurements['Karo LI CV (%)'] = cvTileLI
summary.measurements['LI için geçerli karo'] = validTileLI.size() as double
summary.measurements['Morisita quadrat sayısı'] = quadrats as double
summary.measurements['Morisita Iδ'] = morisita
summary.measurements['Sıfır hücreli karo'] = zeroCellTiles as double
summary.measurements['Kısmi kenar karosu'] = partialTiles as double
summary.measurements['Karo boyutu (µm)'] = tilePxX * pw
summary.setLocked(true)

QP.addObjects(newTiles)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

def fmt = { double value, String pattern -> Double.isFinite(value) ? String.format(java.util.Locale.US, pattern, value) : 'hesaplanamadı' }
def body = new StringBuilder()
body << 'Kİ-67 HETEROJENLİK IZGARASI\n'
body << '════════════════════════════════\n\n'
body << String.format(java.util.Locale.US, 'Tumor ROI sayısı       : %,d%n', tumorObjects.size())
body << String.format(java.util.Locale.US, 'Toplam hücre           : %,d%n', totalCells)
body << String.format(java.util.Locale.US, 'Pozitif hücre          : %,d%n', totalPositive)
body << "Ağırlıklı genel LI    : ${fmt(overallLI, '%.2f%%')}\n"
body << String.format(java.util.Locale.US, 'ROI-kesişimli karo     : %,d%n', newTiles.size())
body << String.format(java.util.Locale.US, 'Sıfır hücreli karo     : %,d%n', zeroCellTiles)
body << String.format(java.util.Locale.US, 'LI için geçerli karo   : %,d (karo başına ≥%d hücre)%n', validTileLI.size(), minCellsPerTile)
body << '────────────────────────────────\n'
body << "Karo LI ortalaması    : ${fmt(meanTileLI, '%.2f%%')}\n"
body << "Karo LI SD            : ${fmt(sdTileLI, '%.2f%%')}\n"
body << "Karo LI CV            : ${fmt(cvTileLI, '%.2f%%')}\n"
body << "Morisita Iδ           : ${fmt(morisita, '%.4f')}\n"
body << String.format(java.util.Locale.US, 'Morisita quadratları   : %,d tam alanlı karo%n', quadrats)
body << String.format(java.util.Locale.US, 'Dışlanan kenar karosu  : %,d kısmi karo%n', partialTiles)
body << '\nAğırlıklı genel LI, tüm hücrelerin birleşik oranıdır.\n'
body << 'Karo ortalaması her geçerli karoya eşit ağırlık verir ve ayrı raporlanır.\n'
body << 'Morisita hesabı eşit alan varsayımı için yalnız tam karoları kullanır; sıfır quadratlar dahildir.\n\n'
body << 'Bu çıktı betimsel bir ölçümdür; klinik yorum veya kategori üretmez.'

showResultWindow('Ki-67 heterojenlik', body.toString())
println String.format(java.util.Locale.US, 'Ki-67 heterojenlik: %d karo, genel LI %s', newTiles.size(), fmt(overallLI, '%.2f%%'))
