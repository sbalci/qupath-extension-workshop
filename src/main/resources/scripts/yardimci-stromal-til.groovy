/**
 * Yardımcı - Stromal TIL Yoğunluğu (morfolojik ölçüm)
 * ----------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Modül 6 ile ayrılan **Stroma** alanı içinde çekirdek tespiti yapar ve
 * küçük-çekirdekli hücreleri (lenfosit adayı) sayarak **stromal hücre
 * yoğunluğu (hücre/mm²)** üretir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • H&E üzerinde "stromal TIL" görsel skoru (Salgado/IIOBWG) bir
 *     PATOLOG tahminidir. Bu betik onun yerini ALMAZ; yalnızca stromada
 *     küçük yuvarlak çekirdek **yoğunluğunu** ölçer — lenfosit/plazma
 *     hücresi morfolojik vekili. CD3/CD8 İHK doğrulaması gerekir.
 *   • Çıktı bir SAYIM/YOĞUNLUKtur — yüzde TIL, klinik eşik veya yorum DEĞİL.
 *
 * KULLANIM:
 *   1. H&E slaytını açın, Image type → "Brightfield (H&E)" veya "(H-DAB)"
 *   2. Önce Modül 6'yı çalıştırın → "Stroma" anotasyonları oluşsun
 *   3. [Automate → Project scripts → bu betik]
 *
 * ÇIKTI:
 *   • Her "Stroma" anotasyonuna: "Stromal hücre yoğunluğu (hücre/mm2)" +
 *     "Lenfosit adayı yoğunluğu (hücre/mm2)"
 *   • Kilitli "Stromal TIL Özet" anotasyonu (Modül 9 ile dışa aktarılır)
 *
 * YÖNTEM REFERANSLARI:
 *   • Salgado R et al. (2015), Ann Oncol — IIOBWG stromal TIL skorlama
 *     standardı (görsel, H&E). doi:10.1093/annonc/mdu450
 *   • Thagaard J et al. (2021), Cancers — otomatik TIL kantifikasyonu
 *     ve patolog uyumu. doi:10.3390/cancers13123050
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

            stage.setScene(new javafx.scene.Scene(root, 720, 540))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler (sabit; eklenti tercihlerine bağlı değil) ──────────
double pixelSizeMicrons    = 0.5     // tespit çözünürlüğü
double detectionThreshold  = 0.10    // Hematoxylin OD eşiği
double minNucleusAreaUm2   = 5.0     // çok küçük gürültüyü ele
double maxNucleusAreaUm2   = 80.0    // büyük tümör/stroma çekirdeklerini ele
double lymphocyteMaxAreaUm2 = 40.0   // lenfosit adayı: küçük yuvarlak çekirdek
String detectionChannel    = "Hematoxylin OD"

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir H&E slaytı açın.")
    return
}

def stromaAnnotations = QP.getAnnotationObjects().findAll { it.getPathClass()?.getName() == "Stroma" }
if (stromaAnnotations.isEmpty()) {
    def msg = "Bu slaytta 'Stroma' anotasyonu yok.\n\n" +
              "Önce Modül 6 (Tümör vs stroma sınıflandırıcı) çalıştırın; o betik\n" +
              "tümör/stroma alanlarını ayırır. Sonra bu betiği tekrar çalıştırın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Stroma bulunamadı", msg)
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

// ── 2) Stroma içinde çekirdek tespiti ──────────────────────────────
println "Stroma alanında çekirdek tespiti..."
QP.selectObjects(stromaAnnotations)
QP.runPlugin(
    'qupath.imagej.detect.cells.PositiveCellDetection',
    '{' +
        '"detectionImageBrightfield":"' + detectionChannel + '",' +
        '"requestedPixelSizeMicrons":' + pixelSizeMicrons + ',' +
        '"backgroundRadiusMicrons":8.0,' +
        '"medianRadiusMicrons":0.0,' +
        '"sigmaMicrons":1.0,' +
        '"minAreaMicrons":' + minNucleusAreaUm2 + ',' +
        '"maxAreaMicrons":' + maxNucleusAreaUm2 + ',' +
        '"threshold":' + detectionThreshold + ',' +
        '"watershedPostProcess":true,' +
        '"cellExpansionMicrons":2.0,' +
        '"includeNuclei":true,' +
        '"smoothBoundaries":true,' +
        '"makeMeasurements":true,' +
        '"thresholdCompartment":"Nucleus: DAB OD mean",' +
        '"thresholdPositive1":0.20,' +
        '"thresholdPositive2":0.40,' +
        '"thresholdPositive3":0.60,' +
        '"singleThreshold":true' +
    '}'
)

// ── 3) Hücreleri topla, lenfosit adaylarını ayır ────────────────────
def nucAreaKeys = ["Nucleus: Area µm^2", "Nucleus: Area", "Nucleus: Area um^2"]
def nucArea = { cell ->
    for (k in nucAreaKeys) { def v = cell.measurements[k]; if (v != null) return v as double }
    return Double.NaN
}

def areaMm2 = { ann ->
    def roi = ann.getROI()
    roi != null ? (roi.getArea() * pw * ph) / 1_000_000.0 : 0.0
}

int totalCells = 0
int totalLympho = 0
int areaKnown = 0
double totalStromaMm2 = 0.0

stromaAnnotations.each { ann ->
    def cells = ann.getChildObjects().findAll { it.isDetection() }
    double aMm2 = areaMm2(ann)
    totalStromaMm2 += aMm2

    int n = cells.size()
    int nLympho = cells.count { def a = nucArea(it); !Double.isNaN(a) && a > 0 && a <= lymphocyteMaxAreaUm2 } as int
    if (cells.any { !Double.isNaN(nucArea(it)) }) areaKnown++

    totalCells += n
    totalLympho += nLympho

    double dCell = aMm2 > 0 ? n / aMm2 : 0.0
    double dLympho = aMm2 > 0 ? nLympho / aMm2 : 0.0
    ann.measurements['Stromal hücre yoğunluğu (hücre/mm2)']    = dCell
    ann.measurements['Lenfosit adayı yoğunluğu (hücre/mm2)']   = dLympho
}

double overallCellDensity = totalStromaMm2 > 0 ? totalCells / totalStromaMm2 : 0.0
double overallLymphoDensity = totalStromaMm2 > 0 ? totalLympho / totalStromaMm2 : 0.0

// ── 4) Kilitli özet anotasyonu (Modül 9 dışa aktarımı için) ─────────
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == "Stromal TIL Özet" }, false)
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName("Stromal TIL Özet")
summary.measurements['Stroma alanı (mm2)']                    = totalStromaMm2
summary.measurements['Stromal hücre sayısı']                  = totalCells as double
summary.measurements['Lenfosit adayı sayısı']                 = totalLympho as double
summary.measurements['Stromal hücre yoğunluğu (hücre/mm2)']   = overallCellDensity
summary.measurements['Lenfosit adayı yoğunluğu (hücre/mm2)']  = overallLymphoDensity
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

def areaNote = areaKnown == 0
    ? "\n⚠️ Çekirdek alan ölçümü bulunamadı → lenfosit adayı boyut filtresi uygulanamadı;\n   tüm stromal çekirdekler sayıldı. (Tespit ayarlarını kontrol edin.)"
    : ""

// ── 5) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "STROMAL TIL YOĞUNLUĞU (morfolojik vekil)\n"
body << "════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Stroma alanı            : %.3f mm²%n", totalStromaMm2)
body << String.format(java.util.Locale.US, "Stromal hücre (toplam)  : %,d  →  %.0f hücre/mm²%n", totalCells, overallCellDensity)
body << String.format(java.util.Locale.US, "Lenfosit adayı (≤%.0f µm²): %,d  →  %.0f hücre/mm²%n",
        lymphocyteMaxAreaUm2, totalLympho, overallLymphoDensity)
body << areaNote << "\n\n"
body << "Yöntem: stromada çekirdek tespiti; küçük yuvarlak çekirdek = lenfosit vekili.\n"
body << "Bu bir SAYIM/YOĞUNLUKtur — IIOBWG görsel TIL%'i, klinik eşik veya yorum DEĞİL.\n"
body << "Lenfosit kimliği için CD3/CD8 İHK doğrulaması gerekir (Salgado 2015; Thagaard 2021).\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Stromal TIL yoğunluğu", body.toString())
println "✓ Stromal TIL yoğunluğu yazıldı (her Stroma anotasyonu + 'Stromal TIL Özet')."
