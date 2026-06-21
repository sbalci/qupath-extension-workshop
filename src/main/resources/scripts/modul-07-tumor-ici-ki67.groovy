/**
 * Modül 7 - İncelenmiş Tümör ROI'sinde Ki-67 Ölçümü
 * --------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+
 *
 * Ki-67 H-DAB slaydında patolog tarafından çizilmiş, sınıfı `Tumor` olan
 * anotasyonların birleşimi içinde pozitif çekirdek tespiti yapar.
 * H&E için eğitilmiş bir piksel sınıflandırıcıyı İHK slaydına uygulamaz.
 *
 * Çıktılar yalnız sayım, alan, yoğunluk ve pozitif yüzdesidir.
 * Klinik kategori, eşik veya yorum üretilmez.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.objects.PathObjects
import qupath.lib.roi.RoiTools
import qupath.lib.scripting.QP

import java.util.Locale

// ── Sonuç penceresi ─────────────────────────────────────────────────
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

            def alwaysTop = new javafx.scene.control.CheckBox('Üstte tut')
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, oldValue, newValue -> stage.setAlwaysOnTop(newValue) } as javafx.beans.value.ChangeListener
            )

            def copyBtn = new javafx.scene.control.Button('Kopyala')
            copyBtn.setOnAction({
                def clipboard = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent()
                content.putString(windowBody)
                clipboard.setContent(content)
            })

            def closeBtn = new javafx.scene.control.Button('Kapat')
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
            stage.setScene(new javafx.scene.Scene(root, 800, 600))
            stage.show()
        } catch (Throwable t) {
            Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Eklenti tercihleri ──────────────────────────────────────────────
def __wpClass = { ->
    try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') }
    catch (Throwable ignored) { null }
}
def __wpCall = { String method, Class[] signature, Object[] args, Object fallback ->
    def cls = __wpClass()
    if (cls == null) return fallback
    try { cls.getMethod(method, signature).invoke(null, args) }
    catch (Throwable ignored) { fallback }
}
def atolyeD = { String key, double fallback ->
    (double)__wpCall('dbl', [String.class, double.class] as Class[], [key, fallback] as Object[], fallback)
}
def atolyeS = { String key, String fallback ->
    (String)__wpCall('str', [String.class, String.class] as Class[], [key, fallback] as Object[], fallback)
}
def atolyeB = { String key, boolean fallback ->
    (boolean)__wpCall('bool', [String.class, boolean.class] as Class[], [key, fallback] as Object[], fallback)
}

// ── Ön kontroller ──────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage('Görüntü açık değil', 'Önce bir Ki-67 H-DAB slaytı açın.')
    return
}
String imageTypeName = imageData.getImageType()?.toString() ?: ''
String normalizedImageType = imageTypeName.toUpperCase(Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
if (!normalizedImageType.contains('H_DAB')) {
    Dialogs.showErrorMessage('Yanlış görüntü tipi', "Image type Brightfield (H-DAB) olmalı. Şu anki: ${imageTypeName}")
    return
}

def calibration = imageData.getServer().getPixelCalibration()
double pixelWidth = calibration.getPixelWidthMicrons()
double pixelHeight = calibration.getPixelHeightMicrons()
if (!(pixelWidth > 0) || !(pixelHeight > 0)) {
    Dialogs.showErrorMessage('Kalibrasyon yok', 'Piksel boyutu tanımlı değil; alan ve yoğunluk hesaplanamaz.')
    return
}

// Analiz sınırı yalnız patolog tarafından açıkça SEÇİLEN, sınıfı 'Tumor' olan
// anotasyonlardır. Seçim yoksa betik durur — gözden geçirilmemiş bölgeleri
// (ör. başka bir modülün ürettiği poligonları) sessizce ölçüme katmamak için.
def tumorObjects = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Tumor'
}
if (tumorObjects.isEmpty()) {
    Dialogs.showErrorMessage(
        'Tumor ROI seçili değil',
        'Ki-67 slaydında ölçülecek tümör epitelini anotasyonla çevreleyin, sınıfını tam\n' +
        'olarak "Tumor" yapın ve bu anotasyon(lar)ı SEÇİN. Betik yalnız seçili Tumor\n' +
        'anotasyonlarını ölçer; H&E sınıflandırıcısı bu İHK slaydına otomatik uygulanmaz.'
    )
    return
}

def tumorUnion = RoiTools.union(tumorObjects.collect { it.getROI() })
if (tumorUnion == null || tumorUnion.isEmpty()) {
    Dialogs.showErrorMessage('Geçersiz Tumor ROI', 'Tumor anotasyonları geçerli bir alan oluşturmuyor.')
    return
}

// Eski özet ve alt tespitlerini temizle.
def oldSummaries = QP.getAnnotationObjects().findAll { it.getName() == 'Ki-67 Tümör Özet' }
if (!oldSummaries.isEmpty()) QP.removeObjects(oldSummaries, false)

def summary = PathObjects.createAnnotationObject(tumorUnion)
summary.setName('Ki-67 Tümör Özet')
QP.addObjects([summary])

// ── Pozitif çekirdek tespiti ───────────────────────────────────────
double nuclear1 = atolyeD('atolye.nuclear1', 0.20)
double nuclear2 = atolyeD('atolye.nuclear2', 0.40)
double nuclear3 = atolyeD('atolye.nuclear3', 0.60)
String detectionChannel = atolyeS('atolye.detectionChannel', 'Hematoxylin OD')

def started = System.currentTimeMillis()
QP.selectObjects(summary)
QP.runPlugin(
    'qupath.imagej.detect.cells.PositiveCellDetection',
    '{' +
        '"detectionImageBrightfield":"' + detectionChannel + '",' +
        '"requestedPixelSizeMicrons":' + atolyeD('atolye.pixelSize', 0.5) + ',' +
        '"backgroundRadiusMicrons":' + atolyeD('atolye.backgroundRadius', 8.0) + ',' +
        '"medianRadiusMicrons":' + atolyeD('atolye.medianRadius', 0.0) + ',' +
        '"sigmaMicrons":' + atolyeD('atolye.sigma', 1.5) + ',' +
        '"minAreaMicrons":' + atolyeD('atolye.minArea', 10.0) + ',' +
        '"maxAreaMicrons":' + atolyeD('atolye.maxArea', 400.0) + ',' +
        '"threshold":' + atolyeD('atolye.detectionThreshold', 0.1) + ',' +
        '"watershedPostProcess":' + atolyeB('atolye.watershed', true) + ',' +
        '"cellExpansionMicrons":' + atolyeD('atolye.cellExpansionNuclear', 5.0) + ',' +
        '"includeNuclei":true,' +
        '"smoothBoundaries":true,' +
        '"makeMeasurements":true,' +
        '"thresholdCompartment":"Nucleus: DAB OD mean",' +
        '"thresholdPositive1":' + nuclear1 + ',' +
        '"thresholdPositive2":' + nuclear2 + ',' +
        '"thresholdPositive3":' + nuclear3 + ',' +
        '"singleThreshold":false' +
    '}'
)
double elapsed = (System.currentTimeMillis() - started) / 1000.0

def detections = summary.getChildObjects().findAll { it.isDetection() }
if (detections.isEmpty()) {
    QP.removeObjects([summary], false)
    QP.fireHierarchyUpdate()
    Dialogs.showErrorMessage('Tespit yok', 'Tumor ROI içinde çekirdek tespit edilmedi. Tespit ve boya vektörü ayarlarını kontrol edin.')
    return
}
summary.setLocked(true)

def summarize = { collection, roi ->
    int n0 = 0, n1 = 0, n2 = 0, n3 = 0
    collection.each { detection ->
        String className = detection.getPathClass()?.getName() ?: ''
        if (className.contains('3+')) n3++
        else if (className.contains('2+')) n2++
        else if (className.contains('1+')) n1++
        else n0++
    }
    int total = collection.size()
    int positive = n1 + n2 + n3
    double positivePct = total > 0 ? 100.0 * positive / total : Double.NaN
    double areaMm2 = roi == null ? 0.0 : roi.getArea() * pixelWidth * pixelHeight / 1_000_000.0
    double density = areaMm2 > 0 ? total / areaMm2 : Double.NaN
    return [total: total, positive: positive, n0: n0, n1: n1, n2: n2, n3: n3,
            positivePct: positivePct, area: areaMm2, density: density]
}

def writeMetrics = { object, values ->
    object.measurements['ROI alanı (mm2)'] = values.area
    object.measurements['Toplam çekirdek'] = values.total as double
    object.measurements['Pozitif çekirdek'] = values.positive as double
    object.measurements['Negatif çekirdek'] = values.n0 as double
    object.measurements['1+ çekirdek'] = values.n1 as double
    object.measurements['2+ çekirdek'] = values.n2 as double
    object.measurements['3+ çekirdek'] = values.n3 as double
    object.measurements['Ki-67 pozitif (%)'] = values.positivePct
    object.measurements['Çekirdek yoğunluğu (hücre/mm2)'] = values.density
}

// Birleşik Tumor ROI sonucu: örtüşen anotasyonlar iki kez sayılmaz.
def aggregate = summarize(detections, tumorUnion)
writeMetrics(summary, aggregate)
summary.measurements['Tumor ROI sayısı'] = tumorObjects.size() as double
summary.measurements['DAB eşiği 1+'] = nuclear1
summary.measurements['DAB eşiği 2+'] = nuclear2
summary.measurements['DAB eşiği 3+'] = nuclear3

// Her kaynak Tumor ROI için aynı tespitlerden centroid-temelli alt sonuç üret.
tumorObjects.each { tumor ->
    def roi = tumor.getROI()
    def local = detections.findAll { detection ->
        def detectionROI = detection.getROI()
        detectionROI != null && roi.contains(detectionROI.getCentroidX(), detectionROI.getCentroidY())
    }
    writeMetrics(tumor, summarize(local, roi))
}
QP.fireHierarchyUpdate()

def percent = { int count, int total -> total > 0 ? 100.0 * count / total : Double.NaN }
def fmt = { double value, String pattern -> Double.isFinite(value) ? String.format(Locale.US, pattern, value) : 'hesaplanamadı' }

def body = new StringBuilder()
body << 'TÜMÖR ROI İÇİNDE Kİ-67 ÖLÇÜMÜ\n'
body << '════════════════════════════════════════\n\n'
body << String.format(Locale.US, 'Tumor ROI sayısı       : %,d%n', tumorObjects.size())
body << String.format(Locale.US, 'Birleşik alan          : %.3f mm²%n', aggregate.area)
body << String.format(Locale.US, 'Toplam çekirdek        : %,d%n', aggregate.total)
body << String.format(Locale.US, 'Pozitif çekirdek       : %,d%n', aggregate.positive)
body << "Ki-67 pozitif (%)     : ${fmt(aggregate.positivePct, '%.2f%%')}\n"
body << "Çekirdek yoğunluğu    : ${fmt(aggregate.density, '%.1f hücre/mm²')}\n\n"
body << 'YOĞUNLUK GRUPLARI\n'
body << '────────────────────────────────────────\n'
body << String.format(Locale.US, 'Negatif : %,d  (%s)%n', aggregate.n0, fmt(percent(aggregate.n0, aggregate.total), '%.2f%%'))
body << String.format(Locale.US, '1+      : %,d  (%s)%n', aggregate.n1, fmt(percent(aggregate.n1, aggregate.total), '%.2f%%'))
body << String.format(Locale.US, '2+      : %,d  (%s)%n', aggregate.n2, fmt(percent(aggregate.n2, aggregate.total), '%.2f%%'))
body << String.format(Locale.US, '3+      : %,d  (%s)%n', aggregate.n3, fmt(percent(aggregate.n3, aggregate.total), '%.2f%%'))
body << '\nTEKNİK KAYIT\n'
body << '────────────────────────────────────────\n'
body << "Tespit kanalı          : ${detectionChannel}\n"
body << String.format(Locale.US, 'DAB eşikleri           : %.2f / %.2f / %.2f%n', nuclear1, nuclear2, nuclear3)
body << String.format(Locale.US, 'İşlem süresi           : %.1f sn%n', elapsed)
body << 'Analiz sınırı           : patolog tarafından seçilen Tumor ROI birleşimi\n\n'
body << 'Bu çıktı betimsel bir ölçümdür; klinik yorum veya kategori üretmez.\n'
body << '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'

showResultWindow('Modül 7 - Ki-67 ölçümü', body.toString())
println String.format(Locale.US, 'Modül 7 tamamlandı: n=%d, Ki-67 pozitif=%s',
    aggregate.total, fmt(aggregate.positivePct, '%.2f%%'))
