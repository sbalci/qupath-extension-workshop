/**
 * Modül 6 - Tümör/Stroma Alan Ölçümü
 * -----------------------------------
 * Hedef QuPath sürümü: 0.6.0+
 *
 * Patolog tarafından gözden geçirilmiş `Specimen` anotasyonları içinde
 * Tumor ve Stroma piksel sınıflarının alanlarını ölçer. İsteğe bağlı
 * `Analysis ROI` anotasyonları için aynı ölçümleri ayrı ayrı üretir.
 *
 * ÜRETİLEN BETİMSEL ÖLÇÜMLER:
 *   • Tümör alanı (%) = Tumor / (Tumor + Stroma) × 100
 *   • Stroma alanı (%) = Stroma / (Tumor + Stroma) × 100
 *   • Tümör/Stroma oranı = Tumor / Stroma
 *
 * Betik klinik kategori, eşik, prognoz veya tedavi yorumu üretmez.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.objects.PathObjects
import qupath.lib.roi.RoiTools
import qupath.lib.scripting.QP
import qupath.opencv.ml.pixel.PixelClassifierTools

import java.util.Locale

// ── Kendi başına çalışabilen, modal olmayan sonuç penceresi ─────────
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
                { obs, oldValue, newValue -> stage.setAlwaysOnTop(newValue) } as javafx.beans.value.ChangeListener
            )

            def copyBtn = new javafx.scene.control.Button("Kopyala")
            copyBtn.setOnAction({
                def clipboard = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent()
                content.putString(windowBody)
                clipboard.setContent(content)
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
            stage.setScene(new javafx.scene.Scene(root, 820, 620))
            stage.show()
        } catch (Throwable t) {
            Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Eklenti tercihleri; eklenti yoksa aynı sabit varsayılanlar ─────
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

def bundledClassifierJson = { ->
    try {
        Class.forName('io.github.sbalci.qupath.workshop.WorkshopResources')
            .getMethod('getTumorStromaClassifierJson')
            .invoke(null)
    } catch (Throwable ignored) { null }
}

// ── Ön kontroller ──────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage('Görüntü açık değil', 'Önce bir H&E slaytı açın.')
    return
}
def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage('Proje açık değil', 'Bu betik bir QuPath projesi içinde çalışır.')
    return
}

String imageTypeName = imageData.getImageType()?.toString() ?: ''
String normalizedImageType = imageTypeName.toUpperCase(Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
if (!normalizedImageType.contains('BRIGHTFIELD_H_E')) {
    Dialogs.showErrorMessage(
        'Yanlış görüntü tipi',
        "Tümör/stroma sınıflandırıcısı Brightfield (H&E) görüntü bekler. Şu anki: ${imageTypeName}"
    )
    return
}

def calibration = imageData.getServer().getPixelCalibration()
double pixelWidth = calibration.getPixelWidthMicrons()
double pixelHeight = calibration.getPixelHeightMicrons()
if (!(pixelWidth > 0) || !(pixelHeight > 0)) {
    Dialogs.showErrorMessage(
        'Kalibrasyon yok',
        'Piksel boyutu tanımlı değil; mm² alan ölçümleri hesaplanamaz.\n' +
        'Önce kalibrasyonu metadata veya bir kalibrasyon slaytıyla doğrulayın.'
    )
    return
}

String classifierName = atolyeS('atolye.classifierName', 'tumor-stroma-RF')
double minObjectArea = atolyeD('atolye.minObjectArea', 10000.0)
double minHoleArea = atolyeD('atolye.minHoleArea', 5000.0)

def availableClassifiers = project.getPixelClassifiers().getNames()
boolean hasProjectClassifier = availableClassifiers.contains(classifierName)
def json = hasProjectClassifier ? null : bundledClassifierJson()
if (!hasProjectClassifier && json == null) {
    Dialogs.showErrorMessage(
        'Sınıflandırıcı bulunamadı',
        "'${classifierName}' adlı piksel sınıflandırıcı bulunamadı.\n" +
        'Modül 6 eğitim adımıyla bir model oluşturun veya atölye eklentisini yükleyin.'
    )
    return
}

def classifier = hasProjectClassifier
    ? project.getPixelClassifiers().get(classifierName)
    : qupath.lib.io.GsonTools.getInstance().fromJson(json, qupath.lib.classifiers.pixel.PixelClassifier.class)
if (classifier == null) {
    Dialogs.showErrorMessage('Sınıflandırıcı yüklenemedi', "'${classifierName}' okunamadı.")
    return
}

// Analiz sınırı yalnızca patolog tarafından gözden geçirilmiş Specimen nesneleridir.
def annotations = QP.getAnnotationObjects()
def specimenObjects = annotations.findAll {
    it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Specimen'
}
if (specimenObjects.isEmpty()) {
    Dialogs.showErrorMessage(
        'Specimen anotasyonu gerekli',
        'Slayttaki değerlendirilecek dokuyu bir veya daha fazla anotasyonla çevreleyin\n' +
        've anotasyon sınıfını tam olarak "Specimen" yapın. Boş camı, nekrozu veya\n' +
        'ölçüme girmemesi gereken dokuyu bu sınırın dışında bırakın.'
    )
    return
}

def specimenUnion = RoiTools.union(specimenObjects.collect { it.getROI() })
if (specimenUnion == null || specimenUnion.isEmpty() || !specimenUnion.isArea()) {
    Dialogs.showErrorMessage('Geçersiz Specimen sınırı', 'Specimen anotasyonları geçerli bir alan oluşturmuyor.')
    return
}

def analysisObjects = annotations.findAll {
    it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Analysis ROI'
}

// ── Sınıflandırıcı alan ölçüm yöneticisi ──────────────────────────
def manager = PixelClassifierTools.createMeasurementManager(imageData, classifier)
def measurementNames = manager.getMeasurementNames()
String tumorAreaName = measurementNames.find { it.startsWith('Tumor area ') }
String stromaAreaName = measurementNames.find { it.startsWith('Stroma area ') }
if (tumorAreaName == null || stromaAreaName == null) {
    Dialogs.showErrorMessage(
        'Sınıf adları uyuşmuyor',
        "Sınıflandırıcı tam olarak 'Tumor' ve 'Stroma' çıktı sınıflarını içermeli.\n" +
        "Bulunan ölçümler: ${measurementNames.join(', ')}"
    )
    return
}

def classifierAreaToMm2 = { Number value, String measurementName ->
    if (value == null) return Double.NaN
    double numeric = value.doubleValue()
    String lower = measurementName.toLowerCase(Locale.ROOT)
    if (lower.contains('µm^2') || lower.contains('μm^2') || lower.contains('um^2')) return numeric / 1_000_000.0
    if (lower.contains('mm^2')) return numeric
    return Double.NaN
}

def measureROI = { roi ->
    if (roi == null || roi.isEmpty() || !roi.isArea()) {
        return [
            roiArea: 0.0,
            tumorArea: 0.0,
            stromaArea: 0.0,
            classifiedArea: 0.0,
            tumorPct: Double.NaN,
            stromaPct: Double.NaN,
            ratio: Double.NaN,
            coveragePct: Double.NaN
        ]
    }
    double roiAreaMm2 = roi.getArea() * pixelWidth * pixelHeight / 1_000_000.0
    double tumorAreaMm2 = classifierAreaToMm2(manager.getMeasurementValue(roi, tumorAreaName), tumorAreaName)
    double stromaAreaMm2 = classifierAreaToMm2(manager.getMeasurementValue(roi, stromaAreaName), stromaAreaName)
    double classifiedAreaMm2 = tumorAreaMm2 + stromaAreaMm2
    double tumorPct = classifiedAreaMm2 > 0 ? 100.0 * tumorAreaMm2 / classifiedAreaMm2 : Double.NaN
    double stromaPct = classifiedAreaMm2 > 0 ? 100.0 * stromaAreaMm2 / classifiedAreaMm2 : Double.NaN
    double tumorStromaRatio = stromaAreaMm2 > 0 ? tumorAreaMm2 / stromaAreaMm2 : Double.NaN
    double coveragePct = roiAreaMm2 > 0 ? 100.0 * classifiedAreaMm2 / roiAreaMm2 : Double.NaN
    return [
        roiArea: roiAreaMm2,
        tumorArea: tumorAreaMm2,
        stromaArea: stromaAreaMm2,
        classifiedArea: classifiedAreaMm2,
        tumorPct: tumorPct,
        stromaPct: stromaPct,
        ratio: tumorStromaRatio,
        coveragePct: coveragePct
    ]
}

def writeMetrics = { object, metrics ->
    object.measurements['ROI alanı (mm2)'] = metrics.roiArea
    object.measurements['Tümör alanı (mm2)'] = metrics.tumorArea
    object.measurements['Stroma alanı (mm2)'] = metrics.stromaArea
    object.measurements['Sınıflandırılmış alan (mm2)'] = metrics.classifiedArea
    object.measurements['Tümör alanı (%)'] = metrics.tumorPct
    object.measurements['Stroma alanı (%)'] = metrics.stromaPct
    object.measurements['Tümör/Stroma oranı'] = metrics.ratio
    object.measurements['Sınıflandırılmış kapsam (%)'] = metrics.coveragePct
}

def formatValue = { double value, String pattern ->
    Double.isFinite(value) ? String.format(Locale.US, pattern, value) : 'hesaplanamadı'
}

// ── Önceki betik özetini ve yalnızca onun altındaki görselleştirmeyi temizle ──
def oldSummaries = QP.getAnnotationObjects().findAll {
    it.getName() in ['Tümör-Stroma Özet', 'TSR Özet']
}
if (!oldSummaries.isEmpty()) QP.removeObjectsAndDescendants(oldSummaries)

// Her Specimen parçasına kendi ölçümü yaz. Örtüşmeler agregada ayrıca sayılmaz.
specimenObjects.each { specimen -> writeMetrics(specimen, measureROI(specimen.getROI())) }

// Analysis ROI ölçümü yalnız Specimen birleşimiyle kesişen bölümde yapılır.
def roiRows = []
analysisObjects.eachWithIndex { analysis, index ->
    def clipped = RoiTools.intersection([analysis.getROI(), specimenUnion])
    def metrics = measureROI(clipped)
    writeMetrics(analysis, metrics)
    roiRows << [name: analysis.getName() ?: "Analysis ROI ${index + 1}", metrics: metrics]
}

// Birleşik specimen özeti: üst üste binen Specimen anotasyonları bir kez sayılır.
def summary = PathObjects.createAnnotationObject(specimenUnion)
summary.setName('Tümör-Stroma Özet')
def aggregate = measureROI(specimenUnion)
writeMetrics(summary, aggregate)
summary.measurements['Specimen parça sayısı'] = specimenObjects.size() as double
summary.measurements['Analysis ROI sayısı'] = analysisObjects.size() as double
summary.setLocked(true)
QP.addObjects([summary])

// Görsel Tumor/Stroma poligonları yalnız birleşik specimen sınırı içinde oluşturulur.
def before = QP.getAnnotationObjects() as Set
QP.selectObjects(summary)
QP.createAnnotationsFromPixelClassifier(
    classifier,
    minObjectArea,
    minHoleArea,
    'SPLIT',
    'DELETE_EXISTING',
    'SELECT_NEW'
)
def generated = QP.getAnnotationObjects().findAll {
    !before.contains(it) && it.getPathClass()?.getName() in ['Tumor', 'Stroma']
}
generated.each { it.setName("Generated by Modül 6 - ${classifierName}") }
QP.fireHierarchyUpdate()

// ── Sonuç: yalnız ölçüm ve teknik kapsam bilgisi ───────────────────
def body = new StringBuilder()
body << 'TÜMÖR/STROMA ALAN ÖLÇÜMÜ\n'
body << '════════════════════════════════════════\n\n'
body << "Sınıflandırıcı      : ${classifierName}\n"
body << String.format(Locale.US, 'Specimen parçaları    : %,d%n', specimenObjects.size())
body << String.format(Locale.US, 'Analysis ROI sayısı   : %,d%n%n', analysisObjects.size())
body << 'SPECIMEN BİRLEŞİMİ\n'
body << '────────────────────────────────────────\n'
body << String.format(Locale.US, 'ROI alanı             : %.3f mm²%n', aggregate.roiArea)
body << String.format(Locale.US, 'Tümör alanı           : %.3f mm²%n', aggregate.tumorArea)
body << String.format(Locale.US, 'Stroma alanı          : %.3f mm²%n', aggregate.stromaArea)
body << String.format(Locale.US, 'Sınıflandırılmış alan : %.3f mm²%n', aggregate.classifiedArea)
body << "Tümör alanı (%)      : ${formatValue(aggregate.tumorPct, '%.2f%%')}\n"
body << "Stroma alanı (%)     : ${formatValue(aggregate.stromaPct, '%.2f%%')}\n"
body << "Tümör/Stroma oranı   : ${formatValue(aggregate.ratio, '%.4f')}\n"
body << "Sınıflandırılmış kapsam: ${formatValue(aggregate.coveragePct, '%.2f%%')}\n"

if (!roiRows.isEmpty()) {
    body << '\nANALYSIS ROI SONUÇLARI\n'
    body << '────────────────────────────────────────\n'
    roiRows.each { row ->
        def m = row.metrics
        body << "${row.name}\n"
        body << String.format(Locale.US, '  Alan: %.3f mm² | Tumor: %.3f mm² | Stroma: %.3f mm²%n',
            m.roiArea, m.tumorArea, m.stromaArea)
        body << "  Tümör: ${formatValue(m.tumorPct, '%.2f%%')} | "
        body << "Stroma: ${formatValue(m.stromaPct, '%.2f%%')} | "
        body << "T/S: ${formatValue(m.ratio, '%.4f')}\n"
    }
}

body << '\nQC NOTLARI\n'
body << '────────────────────────────────────────\n'
body << '• Payda yalnız Tumor + Stroma olarak sınıflandırılan alandır.\n'
body << '• Sınıflandırılmış kapsam, ROI içinde bu iki sınıfa giren alanı gösterir.\n'
body << '• Oran için stroma alanı sıfırsa sonuç hesaplanamaz; sıfır yazılmaz.\n'
body << '• Analysis ROI sonuçları birbirinden bağımsızdır; örtüşen ROI alanları birleştirilmez.\n\n'
body << 'Bu çıktı betimsel bir ölçümdür; klinik yorum veya kategori üretmez.\n'
body << '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'

showResultWindow('Modül 6 - Tümör/Stroma ölçümü', body.toString())
println String.format(Locale.US,
    'Modül 6 tamamlandı: Tumor %.3f mm², Stroma %.3f mm², Tumor %% %s, T/S %s',
    aggregate.tumorArea,
    aggregate.stromaArea,
    formatValue(aggregate.tumorPct, '%.2f'),
    formatValue(aggregate.ratio, '%.4f'))
