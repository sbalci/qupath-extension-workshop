/**
 * Yardımcı - PHH3 Mitoz Kantifikasyonu
 * --------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Seçili anotasyon(lar) içinde **PHH3 (fosfo-histon H3)** İHK boyasının
 * DAB-pozitif çekirdeklerini sayar ve **mitoz yoğunluğu** üretir:
 * mitoz/mm² ve mitoz/2 mm² (WHO alan-tabanlı birim).
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
 *      olarak çizin ve SEÇİN (seçim yoksa slayttaki tüm anotasyonlar kullanılır)
 *   3. [Automate → Project scripts → bu betik]
 *
 * ÇIKTI:
 *   • Her anotasyona: "PHH3-pozitif çekirdek (mitoz adayı)" +
 *     "Mitoz yoğunluğu (mitoz/mm2)" + "PHH3+ nükleer %"
 *   • Kilitli "Mitoz Özet" anotasyonu (Modül 9 ile dışa aktarılır)
 *
 * YÖNTEM NOTU — neden boyut filtresi (Modül 2 ile bağlantı):
 *   Modül 2'de gördüğümüz renk→eşik→morfoloji→sayım zincirinde morfolojik
 *   "opening" küçük yapay sinyalleri ("freckle") temizler. Burada bunun QuPath
 *   karşılığı minimum çekirdek alanı (minNucleusAreaUm2) filtresidir; küçük
 *   profaz/non-mitotik PHH3+ sinyallerini azaltır.
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
 *     atölyesi "Mitosis Detection" (https://www.empaia.org/hands-on-workshop-2022).
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

            stage.setScene(new javafx.scene.Scene(root, 760, 580))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
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

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir PHH3-İHK slaytı açın.")
    return
}

def imageTypeName = imageData.getImageType()?.toString() ?: ""
if (!imageTypeName.toLowerCase(java.util.Locale.ROOT).contains("brightfield")) {
    Dialogs.showErrorMessage(
        "Yanlış görüntü tipi",
        "Bu slayt 'Brightfield' olarak ayarlı değil (şu an: ${imageTypeName}).\n\n" +
        "Image panelinden 'Image type' → 'Brightfield (H-DAB)' seçin (DAB ayrımı için gerekli)," +
        " sonra betiği tekrar çalıştırın."
    )
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
    println "⚠ H-DAB boya vektörleri tanımlı değil → BRIGHTFIELD_H_DAB varsayılanı uygulanıyor."
    QP.setImageType('BRIGHTFIELD_H_DAB')
}

def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok",
        "Slaytta piksel boyutu (µm) tanımlı değil; mitoz yoğunluğu (mitoz/mm²) hesaplanamaz." +
        "\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu).")
    return
}

def selected = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getName() != summaryName }
def targets = selected.isEmpty() ? QP.getAnnotationObjects().findAll { it.getName() != summaryName } : selected
if (targets.isEmpty()) {
    def msg = "Ölçülecek anotasyon yok.\n\nÖnce mitozca yoğun bölgeyi anotasyon olarak çizin (ve seçin)."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Anotasyon yok", msg)
    return
}

// ── 2) Pozitif çekirdek tespiti (nükleer DAB tek eşik) ──────────────
println "PHH3 mitoz için pozitif çekirdek tespiti..."
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
        // Nükleer belirteç (PHH3) → çekirdek DAB compartmenti, tek eşik
        '"thresholdCompartment":"Nucleus: DAB OD mean",' +
        '"thresholdPositive1":' + dabThreshold + ',' +
        '"singleThreshold":true' +
    '}'
)

// ── 3) Pozitif çekirdekleri say, yoğunluk hesapla ───────────────────
def isPositive = { cell -> def cn = cell.getPathClass()?.getName(); cn != null && (cn.equalsIgnoreCase("Positive") || cn.equalsIgnoreCase("Pozitif") || cn.endsWith("+")) }
def areaMm2 = { ann ->
    def roi = ann.getROI()
    roi != null ? (roi.getArea() * pw * ph) / 1_000_000.0 : 0.0
}

int totalNuclei = 0
int totalPos = 0
double totalAreaMm2 = 0.0

targets.each { ann ->
    def cells = ann.getChildObjects().findAll { it.isDetection() }
    int n = cells.size()
    int nPos = cells.count { isPositive(it) } as int
    double aMm2 = areaMm2(ann)

    totalNuclei += n
    totalPos += nPos
    totalAreaMm2 += aMm2

    double dPos = aMm2 > 0 ? nPos / aMm2 : 0.0
    double pctPos = n > 0 ? (100.0 * nPos / n) : 0.0
    ann.measurements['PHH3-pozitif çekirdek (mitoz adayı)'] = nPos as double
    ann.measurements['Mitoz yoğunluğu (mitoz/mm2)'] = dPos
    ann.measurements['PHH3+ nükleer %'] = pctPos
}

double overallDensity = totalAreaMm2 > 0 ? totalPos / totalAreaMm2 : 0.0
double mitosesPerWhoArea = overallDensity * whoAreaMm2
double overallPct = totalNuclei > 0 ? (100.0 * totalPos / totalNuclei) : 0.0

// ── 4) Kilitli özet anotasyonu ──────────────────────────────────────
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == summaryName }, false)
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName(summaryName)
summary.measurements['Ölçülen alan (mm2)']                  = totalAreaMm2
summary.measurements['Toplam çekirdek']                     = totalNuclei as double
summary.measurements['PHH3-pozitif çekirdek (mitoz adayı)'] = totalPos as double
summary.measurements['Mitoz yoğunluğu (mitoz/mm2)']         = overallDensity
summary.measurements['Mitoz / 2 mm2 (WHO alan birimi)']     = mitosesPerWhoArea
summary.measurements['PHH3+ nükleer %']                     = overallPct
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

// ── 5) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "PHH3 MİTOZ KANTİFİKASYONU\n"
body << "═════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Ölçülen alan              : %.3f mm²%n", totalAreaMm2)
body << String.format(java.util.Locale.US, "Toplam çekirdek           : %,d%n", totalNuclei)
body << String.format(java.util.Locale.US, "PHH3-pozitif (mitoz adayı): %,d  (%.2f %%)%n", totalPos, overallPct)
body << String.format(java.util.Locale.US, "Mitoz yoğunluğu           : %.1f mitoz/mm²%n", overallDensity)
body << String.format(java.util.Locale.US, "Mitoz / %.0f mm² (WHO birimi) : %.1f%n", whoAreaMm2, mitosesPerWhoArea)
body << "\n"
body << String.format(java.util.Locale.US, "PHH3 DAB eşiği: %.2f · min çekirdek alanı: %.0f µm² (kalibre edilebilir)%n", dabThreshold, minNucleusAreaUm2)
body << "\n"
body << "Bu bir SAYIM/YOĞUNLUKtur — mitoz derecesi, grade eşiği veya yorum DEĞİL.\n"
body << "PHH3+ ≠ mitoz birebir: küçük profaz sinyalleri sayıma karışabilir; mitoz adaylarını\n"
body << "görsel olarak doğrulayın. WHO alan-tabanlı sayım (mitoz/2 mm²) optik-bağımsızdır.\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("PHH3 mitoz kantifikasyonu", body.toString())
println "✓ PHH3 mitoz ölçümü yazıldı (her anotasyon + '${summaryName}')."
