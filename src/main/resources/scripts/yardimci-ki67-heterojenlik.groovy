/**
 * Yardımcı - Ki-67 Heterojenlik Grid
 * -----------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * MEVCUT Ki-67 tespitlerini (Modül 3 veya Modül 7'den) bir kareli ızgaraya
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
 *   • Slaytta zaten Ki-67 hücre tespitleri olmalı (Modül 3 ya da Modül 7'yi
 *     önce çalıştırın). Betik tespit YAPMAZ; var olanı kullanır.
 *   • "Tumor" anotasyonu varsa yalnız onun içindeki hücreler; yoksa tüm slayt.
 *
 * ÇIKTI:
 *   • Her ızgara karesi: kilitli "Ki-67 grid r,c" anotasyonu +
 *     "Ki-67 LI (%)", "Hücre sayısı", "Pozitif hücre" ölçümleri
 *     → ölçüme göre renklendirilince heterojenlik HARİTASI olur
 *   • Kilitli "Ki-67 Heterojenlik Özet": ortalama LI, SD, CV (%), Iδ
 *
 * YÖNTEM REFERANSLARI:
 *   • Zhang Z et al. (2023), Mod Pathol — bölgesel Ki-67 heterojenliğinin
 *     dijital kantifikasyonu. doi:10.1016/j.modpat.2022.100017
 *   • Lu Z et al. (2024), J Pathol Clin Res — ızgara temelli heterojenlik
 *     ölçütleri. doi:10.1002/2056-4538.346
 *   • Morisita M (1959) — dağılım indeksi Iδ (≈1 rastgele, >1 kümelenmiş).
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

            stage.setScene(new javafx.scene.Scene(root, 720, 560))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
double tileSizeMicrons   = 500.0   // ızgara karesi kenarı (µm)
int    minCellsPerTile   = 20      // LI için bir karede en az bu kadar hücre
int    maxTiles          = 400     // çok küçük karelerle hiyerarşiyi boğmamak için
double dabThreshold      = 0.20    // sınıf yoksa pozitiflik için Nucleus DAB OD eşiği
String dabKey            = "Nucleus: DAB OD mean"

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce Ki-67 tespiti yapılmış bir slayt açın.")
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok", "Slaytta piksel boyutu (µm) tanımlı değil.")
    return
}

def tumorAnnotations = QP.getAnnotationObjects().findAll { it.getPathClass()?.getName() == "Tumor" }
def cells = tumorAnnotations.isEmpty()
    ? QP.getDetectionObjects()
    : tumorAnnotations.collectMany { it.getChildObjects().findAll { it.isDetection() } }

if (cells.isEmpty()) {
    def msg = "Bu slaytta hücre tespiti yok.\n\n" +
              "Önce Modül 3 (Nükleer Ki-67) veya Modül 7 (tümör-içi Ki-67) çalıştırın;\n" +
              "bu betik VAR OLAN tespitleri ızgaraya böler, kendisi tespit yapmaz."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Tespit yok", msg)
    return
}

// ── 2) Pozitiflik testi ─────────────────────────────────────────────
def positiveClassNames = ["Positive", "Pozitif"] as Set
def isPositive = { cell ->
    def cn = cell.getPathClass()?.getName()
    if (cn != null && (positiveClassNames.contains(cn) || cn.endsWith("+"))) return true
    def v = cell.measurements[dabKey]
    return (v != null) && ((v as double) >= dabThreshold)
}

// ── 3) Izgara sınırları + boyutu (kareyi maxTiles'a sığacak şekilde büyüt) ──
double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY
double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY
cells.each { c ->
    def roi = c.getROI(); if (roi == null) return
    double cx = roi.getCentroidX(), cy = roi.getCentroidY()
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
double tileUmActual = tilePx * pw

// ── 4) Hücreleri karelere ata ───────────────────────────────────────
int nTilesTotal = nCols * nRows
int[] tileTotal = new int[nTilesTotal]
int[] tilePos   = new int[nTilesTotal]
cells.each { c ->
    def roi = c.getROI(); if (roi == null) return
    int col = (int) ((roi.getCentroidX() - minX) / tilePx); if (col >= nCols) col = nCols - 1
    int row = (int) ((roi.getCentroidY() - minY) / tilePx); if (row >= nRows) row = nRows - 1
    int idx = row * nCols + col
    tileTotal[idx]++
    if (isPositive(c)) tilePos[idx]++
}

// ── 5) Eski grid/özet anotasyonlarını temizle, yenilerini oluştur ───
QP.removeObjects(QP.getAnnotationObjects().findAll {
    it.getName() != null && (it.getName().startsWith("Ki-67 grid") || it.getName() == "Ki-67 Heterojenlik Özet")
}, false)

def plane = qupath.lib.regions.ImagePlane.getDefaultPlane()
def newTiles = []
def liList = []        // yalnız minCellsPerTile karşılayan kareler
def posCounts = []     // hücre içeren tüm kareler (Morisita için)
for (int r = 0; r < nRows; r++) {
    for (int col = 0; col < nCols; col++) {
        int idx = r * nCols + col
        int tot = tileTotal[idx]
        if (tot == 0) continue
        posCounts << tilePos[idx]
        if (tot < minCellsPerTile) continue

        double li = 100.0 * tilePos[idx] / tot
        liList << li
        double x = minX + col * tilePx
        double y = minY + r * tilePx
        def tile = qupath.lib.objects.PathObjects.createAnnotationObject(
            qupath.lib.roi.ROIs.createRectangleROI(x, y, tilePx, tilePx, plane))
        tile.setName(String.format(java.util.Locale.US, "Ki-67 grid %d,%d", r, col))
        tile.measurements['Ki-67 LI (%)']  = li
        tile.measurements['Hücre sayısı']  = tot as double
        tile.measurements['Pozitif hücre'] = tilePos[idx] as double
        tile.setLocked(true)
        newTiles << tile
    }
}

// ── 6) Heterojenlik ölçütleri ───────────────────────────────────────
double meanLI = 0.0, sdLI = 0.0, cvLI = 0.0
if (!liList.isEmpty()) {
    meanLI = liList.sum() / liList.size()
    double varLI = liList.collect { (it - meanLI) * (it - meanLI) }.sum() / liList.size()
    sdLI = Math.sqrt(varLI)
    cvLI = meanLI > 0 ? 100.0 * sdLI / meanLI : 0.0
}

// Morisita dağılım indeksi Iδ — pozitif hücrelerin kümelenmesi
double sumXX = 0.0
long Ntot = 0
posCounts.each { x -> sumXX += (double) x * (x - 1); Ntot += x }
int quadrats = posCounts.size()
double morisita = (Ntot > 1 && quadrats > 0) ? quadrats * sumXX / (Ntot * (Ntot - 1.0)) : Double.NaN

// ── 7) Kilitli özet anotasyonu ──────────────────────────────────────
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(), plane))
summary.setName("Ki-67 Heterojenlik Özet")
summary.measurements['Ortalama LI (%)']           = meanLI
summary.measurements['LI SD (%)']                 = sdLI
summary.measurements['LI CV (%)']                 = cvLI
summary.measurements['Değerlendirilen kare']      = liList.size() as double
summary.measurements['Kare boyutu (µm)']          = tileUmActual
summary.measurements['Morisita Iδ']               = Double.isNaN(morisita) ? 0.0 : morisita
summary.setLocked(true)

QP.addObjects(newTiles)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

// ── 8) Sonucu sun ───────────────────────────────────────────────────
def scopeNote = tumorAnnotations.isEmpty()
    ? "Kapsam: tüm slayt (Tumor anotasyonu yok)."
    : String.format(java.util.Locale.US, "Kapsam: %d Tumor anotasyonu içindeki hücreler.", tumorAnnotations.size())
def morisitaStr = Double.isNaN(morisita)
    ? "—"
    : String.format(java.util.Locale.US, "%.2f  (≈1 rastgele, >1 kümelenmiş)", morisita)

def body = new StringBuilder()
body << "Kİ-67 HETEROJENLİK (ızgara)\n"
body << "════════════════════════════════\n\n"
body << scopeNote << "\n"
body << String.format(java.util.Locale.US, "Hücre (toplam)      : %,d%n", cells.size())
body << String.format(java.util.Locale.US, "Izgara              : %d sütun × %d satır, kare ≈ %.0f µm%n", nCols, nRows, tileUmActual)
body << String.format(java.util.Locale.US, "Değerlendirilen kare: %d  (≥%d hücre)%n", liList.size(), minCellsPerTile)
body << "────────────────────────────────\n"
body << String.format(java.util.Locale.US, "Ortalama LI         : %.1f %%%n", meanLI)
body << String.format(java.util.Locale.US, "LI standart sapma   : %.1f %%%n", sdLI)
body << String.format(java.util.Locale.US, "LI CV (heterojenlik): %.1f %%%n", cvLI)
body << "Morisita Iδ         : " << morisitaStr << "\n\n"
body << "CV ne kadar yüksekse bölgesel LI değişkenliği o kadar büyük.\n"
body << "Kareleri 'Ki-67 LI (%)' ölçümüne göre renklendirip haritayı görün\n"
body << "(Measure → Show measurement maps).\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Ki-67 heterojenlik", body.toString())
println String.format(java.util.Locale.US, "✓ %d ızgara karesi + özet yazıldı (CV = %.1f%%).", liList.size(), cvLI)
