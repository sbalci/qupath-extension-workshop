/**
 * Yardımcı - Delaunay Komşuluk Özellikleri
 * -----------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Hücre merkezlerinden bir Delaunay komşuluk ağı kurar ve her hücreye komşu
 * SAYISI (+ kalibre ise ortalama komşu MESAFESİ, µm) ölçümü ekler. Doku
 * mimarisinin (hücre-hücre bağlantısallığı) sayısal bir betimidir.
 *
 * YÖNTEM: QuPath'in YERLEŞİK çekirdek API'si `qupath.lib.analysis.DelaunayTools`
 * kullanılır (0.6+). Eski `DelaunayClusteringPlugin` sınıfı 0.6'da kaldırıldığı
 * için artık ona BAĞLI DEĞİLDİR — komşuluk ağı doğrudan koordinatlardan, ek
 * eklenti gerektirmeden hesaplanır.
 *
 * QuPath KARŞILIĞI (GUI):
 *   [Analyze → Spatial analysis → Delaunay cluster features 2D]
 *   Aynı komşuluk grafiğini menüden de üretebilirsiniz; bu betik onu tek
 *   tıkla otomatikleştirir ve sade bir özet yazar.
 *
 * GİRDİ (ÖNKOŞUL):
 *   • Slaytta hücre tespitleri olmalı (Modül 2/3/5/7). Betik tespit YAPMAZ.
 *
 * ÇIKTI:
 *   • Her hücre: "Delaunay: Num neighbors" (+ kalibre ise
 *     "Delaunay: Mean neighbor distance (µm)") ölçümleri (Modül 9 ile aktarılır)
 *   • Kilitli "Delaunay Özet": ortalama komşu sayısı (+ ortalama komşu mesafesi)
 *
 * PARAMETRE:
 *   • maxEdgeMicrons > 0 ise yalnız bu mesafeden YAKIN komşular sayılır
 *     (uzun kenarları budar; yalnız kalibre slaytlarda etkilidir). 0 = sınırsız.
 *
 * YÖNTEM REFERANSI:
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *   • From Samples to Knowledge 2025 (FS2K), Session 12 — uzamsal analiz:
 *     saramcardle.github.io/FS2K
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.analysis.DelaunayTools

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
double maxEdgeMicrons = 0.0   // 0 = sınırsız (saf Delaunay); >0 = uzun kenarları kes (yalnız kalibre)

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce hücre tespiti yapılmış bir slayt açın.")
    return
}
def cells = QP.getDetectionObjects().findAll { it.getROI() != null }
if (cells.size() < 3) {
    def msg = "Delaunay üçgenlemesi için en az 3 hücre gerekir.\nÖnce Modül 2/3/5/7 ile tespit yapın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Yetersiz hücre", msg)
    return
}

def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
boolean hasMicrons = (cal.hasPixelSizeMicrons() && pw > 0 && ph > 0)
boolean limitByDist = hasMicrons && maxEdgeMicrons > 0

// ── 2) Delaunay komşuluk ağı (yerleşik DelaunayTools, 0.6+) ─────────
println String.format(java.util.Locale.US, "Delaunay komşuluk hesaplanıyor (%,d hücre)...", cells.size())
def subdivision = DelaunayTools.newBuilder(cells).centroids().calibration(cal).build()
def allNeighbors = subdivision.getAllNeighbors()   // Map<PathObject, List<PathObject>>

// ── 3) Her hücreye komşu sayısı (+ ortalama komşu mesafesi) yaz ─────
def neighborStats = { cell ->
    def nbs = allNeighbors.getOrDefault(cell, Collections.emptyList())
    double sx = cell.getROI().getCentroidX(), sy = cell.getROI().getCentroidY()
    int kept = 0
    double distSum = 0.0
    nbs.each { nb ->
        double dx = (sx - nb.getROI().getCentroidX()) * pw
        double dy = (sy - nb.getROI().getCentroidY()) * ph
        double d = Math.sqrt(dx * dx + dy * dy)
        if (limitByDist && d > maxEdgeMicrons) return
        kept++
        distSum += d
    }
    [count: kept, meanDist: kept > 0 ? distSum / kept : Double.NaN]
}

def counts = []
def dists = []
cells.each { c ->
    def st = neighborStats(c)
    c.measurements['Delaunay: Num neighbors'] = st.count as double
    counts << (st.count as double)
    if (hasMicrons && st.count > 0) {
        c.measurements['Delaunay: Mean neighbor distance (µm)'] = st.meanDist
        dists << st.meanDist
    }
}

double meanNeighbors = counts.isEmpty() ? 0.0 : counts.sum() / counts.size()
double meanDistOverall = dists.isEmpty() ? Double.NaN : dists.sum() / dists.size()

// ── 4) Kilitli özet anotasyonu ──────────────────────────────────────
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == "Delaunay Özet" }, false)
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName("Delaunay Özet")
summary.measurements['Hücre sayısı']            = cells.size() as double
summary.measurements['Ortalama komşu sayısı']   = meanNeighbors
if (!Double.isNaN(meanDistOverall)) summary.measurements['Ortalama komşu mesafesi (µm)'] = meanDistOverall
if (limitByDist) summary.measurements['Maks kenar eşiği (µm)'] = maxEdgeMicrons
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

def body = new StringBuilder()
body << "DELAUNAY KOMŞULUK\n"
body << "════════════════════\n\n"
body << String.format(java.util.Locale.US, "Hücre sayısı          : %,d%n", cells.size())
body << String.format(java.util.Locale.US, "Ortalama komşu sayısı : %.2f%n", meanNeighbors)
if (!Double.isNaN(meanDistOverall)) {
    body << String.format(java.util.Locale.US, "Ortalama komşu mesafe : %.1f µm%n", meanDistOverall)
}
if (limitByDist) {
    body << String.format(java.util.Locale.US, "Maks kenar eşiği      : %.0f µm (uzun kenarlar budandı)%n", maxEdgeMicrons)
}
body << "\n"
body << "Her hücreye 'Delaunay: Num neighbors'"
body << (hasMicrons ? " + 'Delaunay: Mean neighbor distance (µm)'" : "")
body << " yazıldı.\n"
body << "Yüksek komşu sayısı / küçük komşu mesafesi → sıkı doku (kümelenme).\n"
body << "Modül 9 ile dışa aktarılır.\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Delaunay komşuluk", body.toString())
println String.format(java.util.Locale.US, "✓ Delaunay özeti yazıldı (ortalama %.2f komşu).", meanNeighbors)
