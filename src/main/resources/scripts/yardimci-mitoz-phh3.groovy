/**
 * Yardımcı - PHH3 Mitoz Kantifikasyonu
 * --------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Seçili anotasyon(lar) içinde **PHH3 (fosfo-histon H3)** İHK boyasının
 * DAB-pozitif çekirdeklerini sayar ve **mitoz yoğunluğu** üretir:
 * mitoz/mm² ve yalnız tam 2 mm² ROI için gözlenen mitoz sayısı.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Tanımlı bir alandaki PHH3-DAB-pozitif çekirdeklerin SAYIM ve
 *     YOĞUNLUĞUNU ölçer. Bu bir SAYIM/YOĞUNLUKtur — mitoz derecesi,
 *     grade eşiği veya klinik yorum DEĞİL.
 *   • PHH3 pozitifliği mitoz ile birebir eş DEĞİLDİR: PHH3 geç G2/profaz
 *     çekirdeklerini de boyar (Modül 2'deki "freckle" örneği). Boyut filtresi
 *     bunları azaltır ama tümüyle elemez — sonuç görsel doğrulama gerektirir.
 *   • Nükleer Ki-67 betiğinden (modul-03) FARKI: orada amaç pozitif FRAKSİYON
 *     (LI %); burada amaç alana göre MUTLAK SAYIM (mitoz/mm²).
 *
 * KULLANIM:
 *   1. PHH3-İHK slaytını açın, Image type → "Brightfield (H-DAB)"
 *   2. Mitozca yoğun bölgeyi (ör. tümörün en hücreli/sıcak alanı) anotasyon
 *      olarak çizin ve SEÇİN (ölçülecek anotasyonlar açıkça seçilmelidir)
 *   3. [Automate → Project scripts → bu betik]
 *
 * ÇIKTI:
 *   • Her anotasyona: "PHH3-pozitif çekirdek (mitoz adayı)" +
 *     "Mitoz yoğunluğu (mitoz/mm2)" + "PHH3+ nükleer %"
 *   • Kilitli "Mitoz Özet" anotasyonu (Modül 9 ile dışa aktarılır)
 *
 * YÖNTEM NOTU — EMPAIA "Mitosis Detection" not defteri ile bağlantı:
 *   EMPAIA ECDP2022 not defteri PHH3 mitozlarını klasik görüntü işlemeyle bulur:
 *   RGB->HSV -> HSV eşik (inRange) -> Otsu eşik -> morfolojik açma (MORPH_OPEN;
 *   küçük yapay sinyalleri/"freckle" eler) -> kontur sayımı (findContours). Bu betik
 *   aynı kavramsal zinciri QuPath ile uygular: boya vektörü ayrıştırma -> nükleer DAB
 *   OD eşiği (dabThreshold) -> watershed/morfoloji -> sayım. Not defterindeki
 *   "opening" adımının QuPath karşılığı, küçük profaz/non-mitotik PHH3+ sinyallerini
 *   azaltan minimum çekirdek alanı (minNucleusAreaUm2) filtresidir.
 *
 * YÖNTEM REFERANSLARI:
 *   • Hendzel MJ et al. (1997), Chromosoma 106(6):348–360 — H3 Ser10 fosforilasyonu
 *     geç G2/profazda başlar; PHH3'ün profaz çekirdeklerini de boyamasının (boyut
 *     filtresi gerekçesi) moleküler temeli. doi:10.1007/s004120050256
 *   • Ribalta T et al. (2004), Am J Surg Pathol 28(11):1532–1536 — PHH3 mitoz
 *     figürlerini H&E'ye göre daha hızlı/güvenilir ayırır (meningiom). doi:10.1097/01.pas.0000141389.06925.d5
 *   • Casper DJ et al. (2010), Am J Dermatopathol 32(7):650–654 — ince melanomda
 *     PHH3 H&E'ye göre %243 daha çok mitoz saptar. doi:10.1097/DAD.0b013e3181cf7cc1
 *   • Dessauvagie BF et al. (2015), Pathology 47(4):329–334 — PHH3 dijital görüntü
 *     analizi, mitoz/2 mm² (alan-tabanlı). doi:10.1097/PAT.0000000000000248
 *   • Ibrahim A et al. (2022), Histopathology 82(3):393–406 — PHH3-H&E, görüntü
 *     analizi ↔ patolog uyumunu artırır. doi:10.1111/his.14837
 *   • Kalsnes J et al. (2026), FEBS Open Bio — derin öğrenme ile mitoz/mm²,
 *     pan-kanser doğrulama. doi:10.1002/2211-5463.70210
 *   • Atölye eklentisi ve bu betik için bağlam: EMPAIA Academy, ECDP2022 uygulamalı
 *     atölyesi "Mitosis Detection" (https://www.empaia.org/hands-on-workshop-2022;
 *     Colab: https://colab.research.google.com/drive/1aPunyrBZ1rSHY3xMmL7hSpOWvpv5AKLq).
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

            stage.setScene(new javafx.scene.Scene(root, 760, 580))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler (sabit; kalibre edilebilir başlangıç değerleri) ────
double pixelSizeMicrons          = 0.5
double dabThreshold              = 0.25    // PHH3 nükleer DAB OD eşiği — slayt/tarayıcıya göre KALİBRE EDİN
double nucleusDetectionThreshold = 0.10    // Hematoxylin OD nükleus tespiti eşiği
double minNucleusAreaUm2         = 12.0    // küçük profaz/freckle sinyallerini azaltır (Modül 2 "opening" karşılığı)
double maxNucleusAreaUm2         = 400.0
double cellExpansionUm           = 0.0     // nükleer belirteç — hücre genişletmeye gerek yok
double whoAreaMm2                = 2.0     // WHO alan-tabanlı raporlama birimi (mitoz / 2 mm²)
String detectionChannel          = "Hematoxylin OD"
String summaryName               = "Mitoz Özet"

// ── 1) Ön kontroller ve açık analiz sınırı ─────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir PHH3-İHK slaytı açın.")
    return
}

def imageTypeName = imageData.getImageType()?.toString() ?: ""
def normalizedImageType = imageTypeName.toUpperCase(java.util.Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
if (!normalizedImageType.contains('H_DAB')) {
    Dialogs.showErrorMessage(
        "Yanlış görüntü tipi",
        "Image type Brightfield (H-DAB) olmalı. Şu anki: ${imageTypeName}"
    )
    return
}

def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok",
        "Slaytta piksel boyutu (µm) tanımlı değil; mitoz yoğunluğu (mitoz/mm²) hesaplanamaz.")
    return
}

def targets = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI()?.isArea() && it.getName() != summaryName
}
if (targets.isEmpty()) {
    Dialogs.showErrorMessage("Anotasyon seçili değil", "PHH3 ölçümü için analiz anotasyonlarını açıkça seçin.")
    return
}

def analysisROI = qupath.lib.roi.RoiTools.union(targets.collect { it.getROI() })
if (analysisROI == null || analysisROI.isEmpty() || !analysisROI.isArea()) {
    Dialogs.showErrorMessage("Geçersiz analiz alanı", "Seçili anotasyonlar geçerli bir birleşik ROI oluşturmuyor.")
    return
}

def stains = imageData.getColorDeconvolutionStains()
def hasHematoxylin = false
if (stains != null) {
    for (int i = 1; i <= 3; i++) {
        def name = stains.getStain(i)?.getName()?.toLowerCase(java.util.Locale.ROOT)
        if (name != null && name.contains("hematoxylin")) { hasHematoxylin = true; break }
    }
}
if (!hasHematoxylin) {
    Dialogs.showErrorMessage("Boya vektörü eksik", "H-DAB boya vektörlerinde Hematoxylin kanalı bulunamadı.")
    return
}

// Eski özet kaldırılır; yalnız onun altındaki önceki PHH3 tespitleri silinir.
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == summaryName }, false)
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(analysisROI)
summary.setName(summaryName)
QP.addObjects([summary])

// ── 2) Birleşik ROI içinde pozitif çekirdek tespiti ─────────────────
println "PHH3 mitoz için pozitif çekirdek tespiti..."
QP.selectObjects(summary)
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
        '"thresholdCompartment":"Nucleus: DAB OD mean",' +
        '"thresholdPositive1":' + dabThreshold + ',' +
        '"singleThreshold":true' +
    '}'
)

def cells = summary.getChildObjects().findAll { it.isDetection() }.findAll { it.isCell() }
def isPositive = { cell ->
    def className = cell.getPathClass()?.getName()
    className != null && (className.equalsIgnoreCase("Positive") || className.equalsIgnoreCase("Pozitif") || className.endsWith("+"))
}
def areaMm2 = { roi -> roi.getArea() * pw * ph / 1_000_000.0 }
def writeMetrics = { object, roi, localCells ->
    int total = localCells.size()
    int positive = localCells.count { isPositive(it) } as int
    double area = areaMm2(roi)
    object.measurements['ROI alanı (mm2)'] = area
    object.measurements['Toplam çekirdek'] = total as double
    object.measurements['PHH3-pozitif çekirdek (mitoz adayı)'] = positive as double
    object.measurements['Mitoz yoğunluğu (mitoz/mm2)'] = area > 0 ? positive / area : Double.NaN
    object.measurements['PHH3+ nükleer %'] = total > 0 ? 100.0 * positive / total : Double.NaN
}

// Kaynak ROI sonuçları bağımsızdır; birleşik özet örtüşmeleri yalnız bir kez sayar.
targets.each { target ->
    def roi = target.getROI()
    def localCells = cells.findAll { cell ->
        def cellROI = cell.getROI()
        cellROI != null && roi.contains(cellROI.getCentroidX(), cellROI.getCentroidY())
    }
    writeMetrics(target, roi, localCells)
}

int totalNuclei = cells.size()
int totalPos = cells.count { isPositive(it) } as int
double totalAreaMm2 = areaMm2(analysisROI)
double overallDensity = totalAreaMm2 > 0 ? totalPos / totalAreaMm2 : Double.NaN
double overallPct = totalNuclei > 0 ? 100.0 * totalPos / totalNuclei : Double.NaN
double areaToleranceMm2 = 0.01
double observedPerWhoArea = Math.abs(totalAreaMm2 - whoAreaMm2) <= areaToleranceMm2 ? totalPos as double : Double.NaN

writeMetrics(summary, analysisROI, cells)
summary.measurements['Kaynak ROI sayısı'] = targets.size() as double
summary.measurements['Mitoz / 2 mm2 (yalnız 2.00 ± 0.01 mm2 ROI)'] = observedPerWhoArea
summary.setLocked(true)
QP.fireHierarchyUpdate()

def fmt = { double value, String pattern ->
    Double.isFinite(value) ? String.format(java.util.Locale.US, pattern, value) : 'hesaplanamadı'
}
def body = new StringBuilder()
body << "PHH3 MİTOZ KANTİFİKASYONU\n"
body << "═════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Seçili ROI                : %,d%n", targets.size())
body << String.format(java.util.Locale.US, "Birleşik ölçülen alan     : %.3f mm²%n", totalAreaMm2)
body << String.format(java.util.Locale.US, "Toplam çekirdek           : %,d%n", totalNuclei)
body << "PHH3-pozitif (mitoz adayı): ${totalPos}  (${fmt(overallPct, '%.2f%%')})\n"
body << "Mitoz yoğunluğu           : ${fmt(overallDensity, '%.1f mitoz/mm²')}\n"
if (Double.isFinite(observedPerWhoArea))
    body << String.format(java.util.Locale.US, "Mitoz / %.0f mm² (gözlenen): %.0f%n", whoAreaMm2, observedPerWhoArea)
else
    body << String.format(java.util.Locale.US, "Mitoz / %.0f mm²: hesaplanmadı (alan %.3f mm²; tolerans ±%.2f)%n", whoAreaMm2, totalAreaMm2, areaToleranceMm2)
body << "\nPHH3 DAB eşiği: ${String.format(java.util.Locale.US, '%.2f', dabThreshold)}; min çekirdek alanı: ${String.format(java.util.Locale.US, '%.0f', minNucleusAreaUm2)} µm²\n\n"
body << "Birleşik özet örtüşen ROI alanlarını ve hücreleri yalnız bir kez sayar.\n"
body << "Bu bir SAYIM/YOĞUNLUKtur; mitoz derecesi, grade eşiği veya klinik yorum değildir.\n"
body << "PHH3 pozitifliği mitozla birebir eş değildir; adayları görsel olarak doğrulayın.\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("PHH3 mitoz kantifikasyonu", body.toString())
println "✓ PHH3 mitoz ölçümü yazıldı (her seçili ROI + '${summaryName}')."