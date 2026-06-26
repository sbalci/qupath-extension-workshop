/**
 * Yardımcı - İmmün Hücre Yoğunluğu (DAB)
 * ----------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Seçili anotasyon(lar) içinde bir DAB-İHK immün belirteci (CD8, CD57, PD1…)
 * için pozitif hücre tespiti yapar ve **pozitif hücre yoğunluğu (hücre/mm²)**
 * ile **pozitif hücre %** üretir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • DAB-pozitif hücrelerin alana göre YOĞUNLUĞUNU ölçer. Bu bir
 *     SAYIM/YOĞUNLUKtur — klinik immün skor, eşik veya yorum DEĞİL.
 *   • Stromal TIL helper'ından FARKLIDIR: o, H&E'de küçük çekirdek
 *     morfolojisini sayar; bu betik açık bir İHK belirtecinin DAB
 *     pozitifliğini sayar.
 *
 * KULLANIM:
 *   1. DAB-İHK slaytını açın, Image type → "Brightfield (H-DAB)"
 *   2. Ölçmek istediğiniz alanı anotasyon olarak çizin ve SEÇİN
 *      (ölçülecek anotasyonlar açıkça seçilmelidir)
 *   3. [Automate → Project scripts → bu betik]
 *
 * ÇIKTI:
 *   • Her anotasyona: "İmmün pozitif hücre yoğunluğu (hücre/mm2)" +
 *     "Pozitif hücre %"
 *   • Kilitli "İmmün Yoğunluk Özet" anotasyonu (Modül 9 ile dışa aktarılır)
 *
 * YÖNTEM REFERANSLARI:
 *   • de Ruiter EJ et al. (2022), Virchows Arch 481(2):223–231 — CD57+
 *     hücre/mm², QuPath vs iki gözlemci ICC. doi:10.1007/s00428-022-03323-6
 *   • Russell GG et al. (2023), Immunol Invest 52(6):661–680 — PD1/PDL1
 *     yoğunluğu, yarı-otomatik QuPath. doi:10.1080/08820139.2023.2217845
 *   • Reichling C et al. (2019), Gut — CD3/CD8 yoğunluğu (tümör/invaziv kenar).
 *     doi:10.1136/gutjnl-2019-319292
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

            stage.setScene(new javafx.scene.Scene(root, 720, 540))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler (sabit; kalibre edilebilir başlangıç değerleri) ────
double pixelSizeMicrons        = 0.5
double dabThreshold            = 0.20    // DAB OD eşiği — belirtece göre KALİBRE EDİN
double nucleusDetectionThreshold = 0.10  // Hematoxylin OD nükleus eşiği
double minNucleusAreaUm2       = 10.0
double maxNucleusAreaUm2       = 400.0
double cellExpansionUm         = 2.0
String detectionChannel        = "Hematoxylin OD"
String summaryName             = "İmmün Yoğunluk Özet"

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir DAB-İHK slaytı açın.")
    return
}

def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok", "Slaytta piksel boyutu (µm) tanımlı değil; yoğunluk hesaplanamaz." +
        "\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu).")
    return
}

def selected = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getName() != summaryName }
def targets = selected
if (targets.isEmpty()) {
    def msg = "Ölçülecek anotasyon yok.\n\nÖnce ilgilendiğiniz bölgeyi anotasyon olarak çizin (ve seçin)."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Anotasyon yok", msg)
    return
}

// ── 2) Pozitif hücre tespiti (DAB tek eşik) ─────────────────────────
println "İmmün belirteç için pozitif hücre tespiti..."
QP.selectObjects(targets)
QP.runPlugin(
    'qupath.imagej.detect.cells.PositiveCellDetection',
    '{' +
        '"detectionImageBrightfield":"' + detectionChannel + '",' +
        '"requestedPixelSizeMicrons":' + pixelSizeMicrons + ',' +
        '"backgroundRadiusMicrons":8.0,' +
        '"medianRadiusMicrons":0.0,' +
        '"sigmaMicrons":1.5,' +
        '"minAreaMicrons":' + minNucleusAreaUm2 + ',' +
        '"maxAreaMicrons":' + maxNucleusAreaUm2 + ',' +
        '"threshold":' + nucleusDetectionThreshold + ',' +
        '"watershedPostProcess":true,' +
        '"cellExpansionMicrons":' + cellExpansionUm + ',' +
        '"includeNuclei":true,' +
        '"smoothBoundaries":true,' +
        '"makeMeasurements":true,' +
        // Membranöz immün belirteçler (CD8/PD-L1/PD1) için hücre gövdesi (membran dahil) compartmenti
        '"thresholdCompartment":"Cell: DAB OD mean",' +
        '"thresholdPositive1":' + dabThreshold + ',' +
        '"singleThreshold":true' +
    '}'
)

// ── 3) Pozitif hücreleri say, yoğunluk hesapla ──────────────────────
def isPositive = { cell -> def cn = cell.getPathClass()?.getName(); cn != null && (cn.equalsIgnoreCase("Positive") || cn.equalsIgnoreCase("Pozitif") || cn.endsWith("+")) }
def areaMm2 = { ann ->
    def roi = ann.getROI()
    roi != null ? (roi.getArea() * pw * ph) / 1_000_000.0 : 0.0
}

int totalCells = 0
int totalPos = 0
double totalAreaMm2 = 0.0

targets.each { ann ->
    def cells = ann.getChildObjects().findAll { it.isDetection() }
    int n = cells.size()
    int nPos = cells.count { isPositive(it) } as int
    double aMm2 = areaMm2(ann)

    totalCells += n
    totalPos += nPos
    totalAreaMm2 += aMm2

    double dPos = aMm2 > 0 ? nPos / aMm2 : 0.0
    double pctPos = n > 0 ? (100.0 * nPos / n) : 0.0
    ann.measurements['İmmün pozitif hücre yoğunluğu (hücre/mm2)'] = dPos
    ann.measurements['Pozitif hücre %'] = pctPos
}

double overallDensity = totalAreaMm2 > 0 ? totalPos / totalAreaMm2 : 0.0
double overallPct = totalCells > 0 ? (100.0 * totalPos / totalCells) : 0.0

// ── 4) Kilitli özet anotasyonu ──────────────────────────────────────
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == summaryName }, false)
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName(summaryName)
summary.measurements['Ölçülen alan (mm2)']                      = totalAreaMm2
summary.measurements['Toplam hücre']                            = totalCells as double
summary.measurements['Pozitif hücre']                          = totalPos as double
summary.measurements['İmmün pozitif hücre yoğunluğu (hücre/mm2)'] = overallDensity
summary.measurements['Pozitif hücre %']                        = overallPct
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

// ── 5) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "İMMÜN HÜCRE YOĞUNLUĞU (DAB)\n"
body << "════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Ölçülen alan          : %.3f mm²%n", totalAreaMm2)
body << String.format(java.util.Locale.US, "Toplam hücre          : %,d%n", totalCells)
body << String.format(java.util.Locale.US, "Pozitif hücre         : %,d  (%.1f %%)%n", totalPos, overallPct)
body << String.format(java.util.Locale.US, "Pozitif yoğunluk      : %.0f hücre/mm²%n", overallDensity)
body << "\n"
body << String.format(java.util.Locale.US, "DAB eşiği: %.2f (kalibre edilebilir başlangıç değeri)%n", dabThreshold)
body << "\n"
body << "Bu bir SAYIM/YOĞUNLUKtur — klinik immün skor, eşik veya yorum DEĞİL.\n"
body << "Belirteç kimliği ve eşik kalibrasyonu kullanıcıya aittir (de Ruiter 2022; Russell 2023).\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("İmmün hücre yoğunluğu (DAB)", body.toString())
println "✓ İmmün hücre yoğunluğu yazıldı (her anotasyon + '${summaryName}')."
