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
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane

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

// ── Paketli kardeş betikleri çalıştırma (eklenti varsa) ────────────
def bundledScript = { String name ->
    try {
        Class.forName('io.github.sbalci.qupath.workshop.WorkshopResources')
            .getMethod('getBundledScript', String.class).invoke(null, name)
    } catch (Throwable t) { null }
}
def launchBundled = { String name ->
    def text = bundledScript(name)
    if (text == null) return false
    def runner = new Thread({
        try { new groovy.lang.GroovyShell(this.class.classLoader).evaluate(text, name) }
        catch (Throwable err) { println "Alt betik hatası (${name}): ${err}" }
    }, "AtolyeLaunch-${name}")
    runner.setDaemon(true)
    runner.start()
    return true
}

// ── Model seçim penceresi (hub) ────────────────────────────────────
// Dönüş: [action:'MEASURE', models:[..], wholeSlide:bool] | [action:'CANCEL']
def chooseModels = { List entries, String preferName, Closure launch, boolean bundledAvailable ->
    def latch = new java.util.concurrent.CountDownLatch(1)
    def result = new java.util.concurrent.atomic.AtomicReference([action: 'CANCEL'])
    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle('Tümör/Stroma — model seçimi')
            stage.setAlwaysOnTop(true)

            def header = new javafx.scene.control.Label(
                'Hangi sınıflandırıcı(lar) ile ölçüm yapılsın? Birden fazla seçerseniz\n' +
                'sonuçlar karşılaştırmalı gösterilir.')
            header.setWrapText(true)
            header.setStyle('-fx-font-size: 12px; -fx-padding: 4px 0;')

            def boxes = []
            def listBox = new javafx.scene.layout.VBox(4)
            if (entries.isEmpty()) {
                listBox.getChildren().add(new javafx.scene.control.Label(
                    '(Projede sınıflandırıcı yok. Aşağıdan örnek modeli kurun ya da sihirbazı açın.)'))
            } else {
                entries.each { e ->
                    def label = e.source == 'BUNDLED' ? "${e.name}  (örnek, paketli)" : e.name
                    def cb = new javafx.scene.control.CheckBox(label)
                    cb.setUserData(e.name)
                    if (e.name == preferName) cb.setSelected(true)
                    boxes << cb
                    listBox.getChildren().add(cb)
                }
            }
            def listScroll = new javafx.scene.control.ScrollPane(listBox)
            listScroll.setFitToWidth(true)
            listScroll.setPrefHeight(180)

            def scopeGroup = new javafx.scene.control.ToggleGroup()
            def rRegion = new javafx.scene.control.RadioButton('Seçili bölge (Specimen anotasyonları)')
            rRegion.setToggleGroup(scopeGroup); rRegion.setSelected(true)
            def rSlide = new javafx.scene.control.RadioButton('Tüm slayt (Specimen gerekmez — kaba tahmin)')
            rSlide.setToggleGroup(scopeGroup)
            def scopeBox = new javafx.scene.layout.VBox(4,
                new javafx.scene.control.Label('Kapsam:'), rRegion, rSlide)
            scopeBox.setStyle('-fx-padding: 8px 0;')

            def installBtn = new javafx.scene.control.Button('Örnek modeli kur')
            installBtn.setOnAction({
                if (!launch('yardimci-ornek-siniflandirici.groovy')) {
                    qupath.lib.gui.dialogs.Dialogs.showMessageDialog('Eklenti gerekli',
                        'Örnek modeli kurmak için: Extensions → Atölye → Yardımcılar →\n' +
                        '"Örnek tümör/stroma sınıflandırıcısını projeye kaydet".')
                }
                result.set([action: 'CANCEL']); stage.close()
            })
            def wizardBtn = new javafx.scene.control.Button('Sihirbazı aç (yeni model)')
            wizardBtn.setOnAction({
                if (!launch('modul-06-sihirbaz.groovy')) {
                    qupath.lib.gui.dialogs.Dialogs.showMessageDialog('Eklenti gerekli',
                        'Sihirbaz için: Extensions → Atölye → Modüller →\n' +
                        '"Modül 6 - Tümör/Stroma sihirbazı (model kur/eğit)".')
                }
                result.set([action: 'CANCEL']); stage.close()
            })
            def measureBtn = new javafx.scene.control.Button('Seçilenlerle ölç')
            measureBtn.setDefaultButton(true)
            measureBtn.setOnAction({
                def picked = boxes.findAll { it.isSelected() }.collect { it.getUserData() as String }
                result.set([action: 'MEASURE', models: picked, wholeSlide: rSlide.isSelected()])
                stage.close()
            })
            def cancelBtn = new javafx.scene.control.Button('İptal')
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({ result.set([action: 'CANCEL']); stage.close() })

            stage.setOnHidden({ latch.countDown() })

            def actionRow = new javafx.scene.layout.HBox(8, installBtn, wizardBtn)
            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def confirmRow = new javafx.scene.layout.HBox(8, spacer, cancelBtn, measureBtn)
            def bottom = new javafx.scene.layout.VBox(8, actionRow, confirmRow)
            bottom.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setTop(header)
            root.setCenter(new javafx.scene.layout.VBox(6, listScroll, scopeBox))
            root.setBottom(bottom)
            javafx.scene.layout.BorderPane.setMargin(header, new javafx.geometry.Insets(8))
            javafx.scene.layout.BorderPane.setMargin(root.getCenter(), new javafx.geometry.Insets(0, 8, 0, 8))

            stage.setScene(new javafx.scene.Scene(root, 560, 460))
            stage.show()
        } catch (Throwable t) {
            result.set([action: 'CANCEL'])
            latch.countDown()
        }
    }
    latch.await()
    return result.get()
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

// ── Model keşfi ────────────────────────────────────────────────────
def projectClassifierNames = new ArrayList(project.getPixelClassifiers().getNames())
Collections.sort(projectClassifierNames)
def bundledJson = bundledClassifierJson()
boolean bundledAvailable = bundledJson != null && !projectClassifierNames.contains(classifierName)

def selectable = []
projectClassifierNames.each { selectable << [name: it, source: 'PROJECT'] }
if (bundledAvailable) selectable << [name: classifierName, source: 'BUNDLED']

def choice
if (isHeadless) {
    def headlessModels = []
    if (projectClassifierNames.contains(classifierName)) headlessModels << classifierName
    else if (bundledAvailable) headlessModels << classifierName
    if (headlessModels.isEmpty()) {
        println "Sınıflandırıcı yok ('${classifierName}'). Önce model eğitin/kurun."
        return
    }
    choice = [action: 'MEASURE', models: headlessModels, wholeSlide: false]
} else {
    choice = chooseModels(selectable, classifierName, launchBundled, bundledAvailable)
}
if (choice.action != 'MEASURE') { println 'İptal / alt-betik başlatıldı.'; return }

def selectedModelNames = choice.models
if (selectedModelNames.isEmpty()) {
    Dialogs.showErrorMessage('Model seçilmedi', 'En az bir sınıflandırıcı seçin.')
    return
}
boolean wholeSlide = choice.wholeSlide

def resolveClassifier = { String name ->
    if (projectClassifierNames.contains(name)) return project.getPixelClassifiers().get(name)
    if (bundledAvailable && name == classifierName && bundledJson != null)
        return qupath.lib.io.GsonTools.getInstance().fromJson(bundledJson, qupath.lib.classifiers.pixel.PixelClassifier.class)
    return null
}

// ── Kapsam ROI'leri ────────────────────────────────────────────────
def annotations = QP.getAnnotationObjects()
def server = imageData.getServer()
def scopeRoi
def analysisObjects = []
def specimenObjects = []
if (wholeSlide) {
    scopeRoi = ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(),
        ImagePlane.getDefaultPlane())
} else {
    specimenObjects = annotations.findAll {
        it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Specimen'
    }
    if (specimenObjects.isEmpty()) {
        Dialogs.showErrorMessage(
            'Specimen anotasyonu gerekli',
            'Seçili bölge modunda değerlendirilecek dokuyu sınıfı "Specimen" olan bir\n' +
            'anotasyonla çevreleyin — ya da kapsam olarak "Tüm slayt" seçin.')
        return
    }
    scopeRoi = RoiTools.union(specimenObjects.collect { it.getROI() })
    if (scopeRoi == null || scopeRoi.isEmpty() || !scopeRoi.isArea()) {
        Dialogs.showErrorMessage('Geçersiz Specimen sınırı', 'Specimen anotasyonları geçerli bir alan oluşturmuyor.')
        return
    }
    analysisObjects = annotations.findAll {
        it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Analysis ROI'
    }
}

def classifierAreaToMm2 = { Number value, String measurementName ->
    if (value == null) return Double.NaN
    double numeric = value.doubleValue()
    String lower = measurementName.toLowerCase(java.util.Locale.ROOT)
    if (lower.contains('µm^2') || lower.contains('μm^2') || lower.contains('um^2')) return numeric / 1_000_000.0
    if (lower.contains('mm^2')) return numeric
    return Double.NaN
}
def measureWith = { manager, tumorAreaName, stromaAreaName, roi ->
    if (roi == null || roi.isEmpty() || !roi.isArea()) {
        return [roiArea: 0.0, tumorArea: 0.0, stromaArea: 0.0, classifiedArea: 0.0,
                tumorPct: Double.NaN, stromaPct: Double.NaN, ratio: Double.NaN, coveragePct: Double.NaN]
    }
    double roiAreaMm2 = roi.getArea() * pixelWidth * pixelHeight / 1_000_000.0
    double tumorAreaMm2 = classifierAreaToMm2(manager.getMeasurementValue(roi, tumorAreaName), tumorAreaName)
    double stromaAreaMm2 = classifierAreaToMm2(manager.getMeasurementValue(roi, stromaAreaName), stromaAreaName)
    double classifiedAreaMm2 = tumorAreaMm2 + stromaAreaMm2
    double tumorPct = classifiedAreaMm2 > 0 ? 100.0 * tumorAreaMm2 / classifiedAreaMm2 : Double.NaN
    double stromaPct = classifiedAreaMm2 > 0 ? 100.0 * stromaAreaMm2 / classifiedAreaMm2 : Double.NaN
    double ratio = stromaAreaMm2 > 0 ? tumorAreaMm2 / stromaAreaMm2 : Double.NaN
    double coveragePct = roiAreaMm2 > 0 ? 100.0 * classifiedAreaMm2 / roiAreaMm2 : Double.NaN
    return [roiArea: roiAreaMm2, tumorArea: tumorAreaMm2, stromaArea: stromaAreaMm2,
            classifiedArea: classifiedAreaMm2, tumorPct: tumorPct, stromaPct: stromaPct,
            ratio: ratio, coveragePct: coveragePct]
}
def formatValue = { double value, String pattern ->
    Double.isFinite(value) ? String.format(java.util.Locale.US, pattern, value) : 'hesaplanamadı'
}

boolean multi = selectedModelNames.size() > 1
def keyPrefix = { String model -> multi ? "[${model}] " : '' }

def oldSummaries = QP.getAnnotationObjects().findAll { it.getName() in ['Tümör-Stroma Özet', 'TSR Özet'] }
if (!oldSummaries.isEmpty()) QP.removeObjectsAndDescendants(oldSummaries)

def summary = PathObjects.createAnnotationObject(scopeRoi)
summary.setName('Tümör-Stroma Özet')

def perModel = []
selectedModelNames.each { modelName ->
    def classifier = resolveClassifier(modelName)
    if (classifier == null) { println "Model çözülemedi: ${modelName}"; return }
    def manager = PixelClassifierTools.createMeasurementManager(imageData, classifier)
    def names = manager.getMeasurementNames()
    String tumorAreaName = names.find { it.startsWith('Tumor area ') }
    String stromaAreaName = names.find { it.startsWith('Stroma area ') }
    if (tumorAreaName == null || stromaAreaName == null) {
        println "Model '${modelName}' Tumor/Stroma çıktı sınıflarını içermiyor; atlandı."
        return
    }
    def aggregate = measureWith(manager, tumorAreaName, stromaAreaName, scopeRoi)

    def p = keyPrefix(modelName)
    summary.measurements["${p}ROI alanı (mm2)"] = aggregate.roiArea
    summary.measurements["${p}Tümör alanı (mm2)"] = aggregate.tumorArea
    summary.measurements["${p}Stroma alanı (mm2)"] = aggregate.stromaArea
    summary.measurements["${p}Sınıflandırılmış alan (mm2)"] = aggregate.classifiedArea
    summary.measurements["${p}Tümör alanı (%)"] = aggregate.tumorPct
    summary.measurements["${p}Stroma alanı (%)"] = aggregate.stromaPct
    summary.measurements["${p}Tümör/Stroma oranı"] = aggregate.ratio
    summary.measurements["${p}Sınıflandırılmış kapsam (%)"] = aggregate.coveragePct

    def analysisRows = []
    if (!wholeSlide) {
        specimenObjects.each { sp ->
            def m = measureWith(manager, tumorAreaName, stromaAreaName, sp.getROI())
            sp.measurements["${p}Tümör alanı (%)"] = m.tumorPct
            sp.measurements["${p}Stroma alanı (%)"] = m.stromaPct
            sp.measurements["${p}Tümör/Stroma oranı"] = m.ratio
        }
        analysisObjects.eachWithIndex { analysis, index ->
            def clipped = RoiTools.intersection([analysis.getROI(), scopeRoi])
            def m = measureWith(manager, tumorAreaName, stromaAreaName, clipped)
            analysis.measurements["${p}Tümör alanı (%)"] = m.tumorPct
            analysis.measurements["${p}Stroma alanı (%)"] = m.stromaPct
            analysis.measurements["${p}Tümör/Stroma oranı"] = m.ratio
            analysisRows << [name: analysis.getName() ?: "Analysis ROI ${index + 1}", metrics: m]
        }
    }
    perModel << [model: modelName, aggregate: aggregate, analysisRows: analysisRows, classifier: classifier]
}

if (perModel.isEmpty()) {
    Dialogs.showErrorMessage('Ölçüm yapılamadı', 'Seçilen modellerin hiçbiri Tumor/Stroma çıktısı üretmedi.')
    return
}

summary.measurements['Seçilen model sayısı'] = perModel.size() as double
summary.measurements['Analysis ROI sayısı'] = (wholeSlide ? 0 : analysisObjects.size()) as double
summary.setLocked(true)
QP.addObjects([summary])

def primary = perModel[0]
def before = QP.getAnnotationObjects() as Set
QP.selectObjects(summary)
QP.createAnnotationsFromPixelClassifier(primary.classifier, minObjectArea, minHoleArea,
    'SPLIT', 'DELETE_EXISTING', 'SELECT_NEW')
def generated = QP.getAnnotationObjects().findAll {
    !before.contains(it) && it.getPathClass()?.getName() in ['Tumor', 'Stroma']
}
generated.each { it.setName("Generated by Modül 6 - ${primary.model}") }
QP.fireHierarchyUpdate()

def body = new StringBuilder()
body << 'TÜMÖR/STROMA ALAN ÖLÇÜMÜ\n'
body << '════════════════════════════════════════\n\n'
body << "Kapsam              : ${wholeSlide ? 'Tüm slayt' : 'Seçili bölge (Specimen)'}\n"
if (wholeSlide) body << 'Not: Tüm slayt modunda boş cam/arka plan da Tumor/Stroma sayılabilir (kaba tahmin).\n'
body << "Slayttaki bindirme  : ${primary.model}\n\n"

if (perModel.size() == 1) {
    def a = primary.aggregate
    body << "Sınıflandırıcı      : ${primary.model}\n"
    body << String.format(java.util.Locale.US, 'ROI alanı             : %.3f mm²%n', a.roiArea)
    body << String.format(java.util.Locale.US, 'Tümör alanı           : %.3f mm²%n', a.tumorArea)
    body << String.format(java.util.Locale.US, 'Stroma alanı          : %.3f mm²%n', a.stromaArea)
    body << "Tümör alanı (%)      : ${formatValue(a.tumorPct, '%.2f%%')}\n"
    body << "Stroma alanı (%)     : ${formatValue(a.stromaPct, '%.2f%%')}\n"
    body << "Tümör/Stroma oranı   : ${formatValue(a.ratio, '%.4f')}\n"
    body << "Sınıflandırılmış kapsam: ${formatValue(a.coveragePct, '%.2f%%')}\n"
    primary.analysisRows.each { row ->
        def m = row.metrics
        body << "\n${row.name}: Tümör ${formatValue(m.tumorPct, '%.2f%%')} | " +
                "Stroma ${formatValue(m.stromaPct, '%.2f%%')} | T/S ${formatValue(m.ratio, '%.4f')}\n"
    }
} else {
    body << 'KARŞILAŞTIRMA (kapsam birleşimi)\n'
    body << '────────────────────────────────────────\n'
    body << String.format(java.util.Locale.US, '%-22s %9s %9s %8s%n', 'Model', 'Tümör%', 'Stroma%', 'T/S')
    perModel.each { pm ->
        def a = pm.aggregate
        body << String.format(java.util.Locale.US, '%-22s %9s %9s %8s%n',
            (pm.model.length() > 22 ? pm.model.substring(0, 22) : pm.model),
            formatValue(a.tumorPct, '%.2f'), formatValue(a.stromaPct, '%.2f'),
            formatValue(a.ratio, '%.3f'))
    }
}

body << '\nQC NOTLARI\n'
body << '────────────────────────────────────────\n'
body << '• Payda yalnız Tumor + Stroma olarak sınıflandırılan alandır.\n'
body << '• Çoklu modelde nesne ölçümleri model adıyla ön-eklenir ([model] ...).\n'
body << '• Slayttaki Tumor/Stroma poligonları yalnız birincil (ilk seçilen) modele aittir.\n\n'
body << 'Bu çıktı betimsel bir ölçümdür; klinik yorum veya kategori üretmez.\n'
body << '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'

showResultWindow('Modül 6 - Tümör/Stroma ölçümü', body.toString())
println String.format(java.util.Locale.US, 'Modül 6 tamamlandı: %d model, kapsam=%s',
    perModel.size(), (wholeSlide ? 'slide' : 'region'))
