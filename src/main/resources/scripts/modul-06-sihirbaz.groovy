/**
 * Modül 6 - Tümör/Stroma Sihirbazı (tek pencere: kur/eğit + ölç)
 * --------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * Tüm akış TEK pencerede yürür; ayrı betik/pencere açmaz:
 *   • Örnek sınıflandırıcıyı kullan → paketli modeli (yoksa indirip) projeye
 *     kurar → bölge seç → ölç.
 *   • Yeni sınıflandırıcı eğit → Tumor → Stroma bölgelerini adım adım doğrular,
 *     ad sorar, eğitip kaydeder → bölge seç → ölç.
 *
 * Ölçüm, çekirdek QuPath API'leriyle yerinde yapılır (eklenti sınıflarına
 * yansıma/alt-betik gerektirmez): seçili anotasyon(lar) — seçim yoksa sınıfı
 * 'Region' olanlar — birleşiminde Tümör/Stroma alanları piksel sınıflandırıcıdan
 * ölçülür; kilitli bir 'Tümör-Stroma Özet' anotasyonu + görsel poligonlar üretilir.
 *   ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import java.util.Locale

import qupath.lib.images.servers.ColorTransforms
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.classifiers.pixel.PixelClassifier
import qupath.lib.classifiers.pixel.PixelClassifierMetadata
import qupath.lib.objects.PathObjects
import qupath.lib.roi.RoiTools
import qupath.opencv.ops.ImageOps
import qupath.opencv.ml.OpenCVClassifiers
import qupath.opencv.ml.pixel.PixelClassifiers
import qupath.opencv.ml.pixel.PixelClassifierTools
import qupath.process.gui.commands.ml.PixelClassifierTraining
import org.bytedeco.opencv.opencv_ml.RTrees

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── Atölye tercihleri (eklenti yansıması başarısızsa sabit varsayılan) ──
def __wpCall = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = null
    try { c = Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { c = null }
    if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeS = { String k, String d -> (String) __wpCall('str', [String.class, String.class] as Class[], [k, d] as Object[], d) }
def atolyeD = { String k, double d -> (double) __wpCall('dbl', [String.class, double.class] as Class[], [k, d] as Object[], d) }

// ── Paketli kardeş betikleri çalıştırma (yalnız önkoşul yardımcıları için) ──
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
    }, "AtolyeWizard-${name}")
    runner.setDaemon(true); runner.start()
    return true
}
def menuHint = { String where ->
    Dialogs.showMessageDialog('Eklenti gerekli',
        "Bu adım atölye eklentisini gerektirir. Menüden çalıştırın:\n${where}")
}

// ── Örnek modeli yerinde kur: paketli JSON (yansıma), yoksa GitHub'dan indir ──
def bundledClassifierJson = { ->
    try {
        Class.forName('io.github.sbalci.qupath.workshop.WorkshopResources')
            .getMethod('getTumorStromaClassifierJson').invoke(null)
    } catch (Throwable ignored) { null }
}
def EXAMPLE_MODEL_GZ_URL =
    'https://raw.githubusercontent.com/sbalci/qupath-extension-workshop/main/src/main/resources/classifiers/tumor-stroma-RF.json.gz'
def downloadGunzip = { String url ->
    def conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection()
    conn.setConnectTimeout(20000); conn.setReadTimeout(60000)
    conn.setRequestProperty('User-Agent', 'qupath-extension-workshop')
    try {
        int code = conn.getResponseCode()
        if (code != 200) throw new java.io.IOException("HTTP ${code}")
        def out = new java.io.ByteArrayOutputStream()
        conn.getInputStream().withCloseable { rawIn ->
            new java.util.zip.GZIPInputStream(rawIn).withCloseable { gz ->
                byte[] buf = new byte[1 << 16]; int n
                while ((n = gz.read(buf)) != -1) out.write(buf, 0, n)
            }
        }
        return new String(out.toByteArray(), 'UTF-8')
    } finally { conn.disconnect() }
}
// Dönüş: [ok, name, existed] | [ok:false, reason:'NO_PROJECT'|'ERROR', error]
def installExample = { ->
    def project = QP.getProject()
    if (project == null) return [ok:false, reason:'NO_PROJECT']
    String name = atolyeS('atolye.classifierName', 'tumor-stroma-RF')
    if (project.getPixelClassifiers().getNames().contains(name)) return [ok:true, name:name, existed:true]
    def json = bundledClassifierJson()
    if (json == null) {
        try { json = downloadGunzip(EXAMPLE_MODEL_GZ_URL) }
        catch (Throwable t) { return [ok:false, reason:'ERROR', error:"Paketli model yok ve indirilemedi: ${t.getClass().getSimpleName()}"] }
    }
    try {
        def classifier = qupath.lib.io.GsonTools.getInstance()
            .fromJson(json, qupath.lib.classifiers.pixel.PixelClassifier.class)
        if (classifier == null) return [ok:false, reason:'ERROR', error:'JSON ayrıştırılamadı.']
        project.getPixelClassifiers().put(name, classifier)
        return [ok:true, name:name, existed:false]
    } catch (Throwable t) {
        return [ok:false, reason:'ERROR', error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
    }
}

// ── Durum hesaplama ────────────────────────────────────────────────
def computeState = { ->
    def st = [image:false, project:false, he:false, calib:false, tumor:0, stroma:0, classifiers:[]]
    def imageData = QP.getCurrentImageData()
    st.image = imageData != null
    def project = QP.getProject()
    st.project = project != null
    if (imageData != null) {
        def typeName = (imageData.getImageType()?.toString() ?: '').toUpperCase(Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
        st.he = typeName.contains('BRIGHTFIELD_H_E') && imageData.getColorDeconvolutionStains() != null
        def cal = imageData.getServer()?.getPixelCalibration()
        st.calib = cal != null && cal.getAveragedPixelSizeMicrons() > 0
        QP.getAnnotationObjects().each {
            def n = it.getPathClass()?.getName()
            if (it.getROI()?.isArea() && n == 'Tumor') st.tumor++
            if (it.getROI()?.isArea() && n == 'Stroma') st.stroma++
        }
    }
    if (project != null) st.classifiers = new ArrayList(project.getPixelClassifiers().getNames())
    return st
}

// ── Eğit & kaydet (eski Modül 6a trainer ile aynı atölye ayarları) ──
def trainAndSave = { String targetName ->
    try {
        def imageData = QP.getCurrentImageData()
        def project = QP.getProject()
        def stains = imageData.getColorDeconvolutionStains()
        def cal = imageData.getServer().getPixelCalibration()
        double basePixelMicrons = cal.getAveragedPixelSizeMicrons()
        double trainResolutionMicrons = 2.0

        def chH = ColorTransforms.createColorDeconvolvedChannel(stains, 1)
        def chE = ColorTransforms.createColorDeconvolvedChannel(stains, 2)
        def featureOp = ImageOps.buildImageDataOp([chH, chE])
            .appendOps(ImageOps.Core.splitMerge(
                ImageOps.Core.identity(),
                ImageOps.Filters.gaussianBlur(1.0),
                ImageOps.Filters.gaussianBlur(2.0),
                ImageOps.Filters.gaussianBlur(4.0)
            ))

        def downsample = trainResolutionMicrons / basePixelMicrons
        def resolution = cal.createScaledInstance(downsample, downsample)

        def training = new PixelClassifierTraining(featureOp)
        training.setResolution(resolution)
        def trainingData = training.createTrainingData(imageData)

        def labelMap = trainingData.getLabelMap()
        if (labelMap == null || labelMap.size() < 2)
            return [ok:false, error:'Anotasyonlardan en az iki sınıf çıkarılamadı. Tumor ve Stroma bölgelerinin boş olmadığından emin olun.']

        def model = OpenCVClassifiers.createStatModel(RTrees.class)
        model.train(trainingData.getTrainData())

        def classifications = [:]
        labelMap.each { pathClass, label -> classifications[label] = pathClass }

        def metadata = new PixelClassifierMetadata.Builder()
            .inputResolution(resolution)
            .inputShape(512, 512)
            .setChannelType(ImageServerMetadata.ChannelType.CLASSIFICATION)
            .classificationLabels(classifications)
            .build()

        def classifier = PixelClassifiers.createClassifier(model, featureOp, metadata, true)
        project.getPixelClassifiers().put(targetName, classifier)
        return [ok:true]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '(mesaj yok)')]
    }
}

// ── Yerinde ölçüm (çekirdek API; eklenti yansıması gerektirmez) ─────
def classifierAreaToMm2 = { Number value, String measurementName ->
    if (value == null) return Double.NaN
    double numeric = value.doubleValue()
    String lower = measurementName.toLowerCase(java.util.Locale.ROOT)
    if (lower.contains('µm^2') || lower.contains('μm^2') || lower.contains('um^2')) return numeric / 1_000_000.0
    if (lower.contains('mm^2')) return numeric
    return Double.NaN
}
// Dönüş: [ok:true, text] | [ok:false, error:'NO_REGION'|<mesaj>]
def runMeasure = { String modelName, boolean wholeSlide ->
    try {
        def imageData = QP.getCurrentImageData()
        if (imageData == null) return [ok:false, error:'Görüntü açık değil.']
        def project = QP.getProject()
        if (project == null) return [ok:false, error:'Proje açık değil.']
        def classifier = project.getPixelClassifiers().get(modelName)
        if (classifier == null) return [ok:false, error:"Sınıflandırıcı bulunamadı: ${modelName}"]
        def cal = imageData.getServer().getPixelCalibration()
        double pw = cal.getPixelWidthMicrons(), ph = cal.getPixelHeightMicrons()
        if (!(pw > 0) || !(ph > 0)) return [ok:false, error:'Piksel kalibrasyonu yok (mm² hesaplanamaz).']

        // Kapsam: tüm slayt VEYA seçili anotasyon(lar). Kapsam dışı tutulanlar:
        // özet anotasyonu, Tumor/Stroma (çıktı/eğitim sınıfları) ve 'Ignore*'
        // (boş cam/artefakt — ölçümden çıkarılacak alanlar).
        def isOwnOutput = { obj ->
            String nm = obj.getName()
            def cn = obj.getPathClass()?.getName()
            (nm != null && nm in ['Tümör-Stroma Özet', 'TSR Özet']) || cn in ['Tumor', 'Stroma', 'Ignore*']
        }
        def regions = []
        def scopeRoi
        if (wholeSlide) {
            def server = imageData.getServer()
            scopeRoi = qupath.lib.roi.ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(),
                qupath.lib.regions.ImagePlane.getDefaultPlane())
        } else {
            regions = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI()?.isArea() && !isOwnOutput(it) }
            if (regions.isEmpty()) {
                regions = QP.getAnnotationObjects().findAll {
                    it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Region' && !isOwnOutput(it)
                }
            }
            if (regions.isEmpty()) return [ok:false, error:'NO_REGION']
            scopeRoi = RoiTools.union(regions.collect { it.getROI() })
        }
        // İsteğe bağlı: sınıfı 'Ignore*' olan alanları (boş cam/artefakt) ölçümden çıkar.
        def ignoreRegions = QP.getAnnotationObjects().findAll { it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Ignore*' }
        if (scopeRoi != null && !ignoreRegions.isEmpty()) {
            try {
                def ig = RoiTools.union(ignoreRegions.collect { it.getROI() })
                def diff = scopeRoi.getGeometry().difference(ig.getGeometry())
                if (diff != null && !diff.isEmpty())
                    scopeRoi = qupath.lib.roi.GeometryTools.geometryToROI(diff, scopeRoi.getImagePlane())
            } catch (Throwable ignored) { }
        }
        if (scopeRoi == null || scopeRoi.isEmpty() || !scopeRoi.isArea())
            return [ok:false, error:'Ölçülecek alan kalmadı (Ignore* tüm bölgeyi kaplıyor olabilir).']

        def manager = PixelClassifierTools.createMeasurementManager(imageData, classifier)
        def names = manager.getMeasurementNames()
        String tumorName = names.find { it.startsWith('Tumor area ') }
        String stromaName = names.find { it.startsWith('Stroma area ') }
        if (tumorName == null || stromaName == null)
            return [ok:false, error:'Model Tumor/Stroma çıktı sınıflarını içermiyor.']

        double tumorMm2 = classifierAreaToMm2(manager.getMeasurementValue(scopeRoi, tumorName), tumorName)
        double stromaMm2 = classifierAreaToMm2(manager.getMeasurementValue(scopeRoi, stromaName), stromaName)
        double classified = tumorMm2 + stromaMm2
        double roiMm2 = scopeRoi.getArea() * pw * ph / 1_000_000.0
        double tumorPct = classified > 0 ? 100.0 * tumorMm2 / classified : Double.NaN
        double stromaPct = classified > 0 ? 100.0 * stromaMm2 / classified : Double.NaN
        double ratio = stromaMm2 > 0 ? tumorMm2 / stromaMm2 : Double.NaN
        double coverage = roiMm2 > 0 ? 100.0 * classified / roiMm2 : Double.NaN

        // Dışa aktarılabilir, kilitli özet anotasyonu.
        def summary = PathObjects.createAnnotationObject(scopeRoi)
        summary.setName('Tümör-Stroma Özet')
        summary.measurements['ROI alanı (mm2)'] = roiMm2
        summary.measurements['Tümör alanı (mm2)'] = tumorMm2
        summary.measurements['Stroma alanı (mm2)'] = stromaMm2
        summary.measurements['Sınıflandırılmış alan (mm2)'] = classified
        summary.measurements['Tümör alanı (%)'] = tumorPct
        summary.measurements['Stroma alanı (%)'] = stromaPct
        summary.measurements['Tümör/Stroma oranı'] = ratio
        summary.measurements['Sınıflandırılmış kapsam (%)'] = coverage
        summary.measurements['Ölçülen bölge sayısı'] = regions.size() as double
        summary.measurements['Çıkarılan Ignore* alan sayısı'] = ignoreRegions.size() as double
        summary.setLocked(true)
        def oldSummaries = QP.getAnnotationObjects().findAll { it.getName() in ['Tümör-Stroma Özet', 'TSR Özet'] }
        if (!oldSummaries.isEmpty()) QP.removeObjectsAndDescendants(oldSummaries)
        QP.addObjects([summary])

        // Görsel kontrol için Tumor/Stroma poligonları. SELECT_NEW kullanılmaz:
        // seçili poligonlar sarı çizilir ve sınıf rengini (Tumor kırmızı / Stroma
        // yeşil) gizler. Seçim sonda temizlenir; poligonlar sınıf renginde görünür.
        double minObj = atolyeD('atolye.minObjectArea', 10000.0)
        double minHole = atolyeD('atolye.minHoleArea', 5000.0)
        def before = QP.getAnnotationObjects() as Set
        QP.selectObjects(summary)
        QP.createAnnotationsFromPixelClassifier(classifier, minObj, minHole, 'DELETE_EXISTING')
        double smoothPx = (pw > 0 ? 3.0 / pw : 6.0)   // ~3 µm topolojik sadeleştirme (yalnız görsel)
        QP.getAnnotationObjects().findAll { !before.contains(it) && it.getPathClass()?.getName() in ['Tumor', 'Stroma'] }
            .each {
                try {
                    def simp = org.locationtech.jts.simplify.TopologyPreservingSimplifier.simplify(it.getROI().getGeometry(), smoothPx)
                    if (simp != null && !simp.isEmpty())
                        it.setROI(qupath.lib.roi.GeometryTools.geometryToROI(simp, it.getROI().getImagePlane()))
                } catch (Throwable ignored) { }
                it.setName(null)   // görüntüde etiket yazma; sınıf rengi ayrımı gösterir
                try { it.setDescription("${it.getPathClass().getName()} — Modül 6 (${modelName})") } catch (Throwable ignored) { }
            }
        imageData.getHierarchy().getSelectionModel().clearSelection()
        QP.fireHierarchyUpdate()

        def fmt = { double v, String p -> Double.isFinite(v) ? String.format(java.util.Locale.US, p, v) : 'hesaplanamadı' }
        def text =
            "Model                : ${modelName}\n" +
            (wholeSlide
                ? "Kapsam               : Tüm slayt (boş cam/arka plan da sayılabilir — kaba tahmin)\n"
                : "Ölçülen bölge sayısı : ${regions.size()}\n") +
            String.format(java.util.Locale.US, "ROI alanı            : %.3f mm²%n", roiMm2) +
            String.format(java.util.Locale.US, "Tümör alanı          : %.3f mm²%n", tumorMm2) +
            String.format(java.util.Locale.US, "Stroma alanı         : %.3f mm²%n", stromaMm2) +
            "Tümör alanı (%)      : ${fmt(tumorPct, '%.2f%%')}\n" +
            "Stroma alanı (%)     : ${fmt(stromaPct, '%.2f%%')}\n" +
            "Tümör/Stroma oranı   : ${fmt(ratio, '%.4f')}\n" +
            "Sınıflandırılmış kapsam: ${fmt(coverage, '%.2f%%')}"
        return [ok:true, text:text]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '(mesaj yok)')]
    }
}

if (isHeadless) {
    def s = computeState()
    println "Sihirbaz (headless): image=${s.image} project=${s.project} H&E=${s.he} " +
            "calib=${s.calib} Tumor=${s.tumor} Stroma=${s.stroma} models=${s.classifiers}"
    println "GUI olmadan sihirbaz etkileşimi yok. Ölçüm için modul-06-tumor-stroma.groovy çalıştırın."
    return
}

// ── Tek pencere, adım adım render ──────────────────────────────────
// Stage/Scene YALNIZ FX uygulama iş parçacığında oluşturulabilir; betik arka
// planda çalıştığından stage aşağıdaki Platform.runLater içinde oluşturulur.
def stage = null

// CHOICE | INSTALLING | EXAMPLE_DONE | PREREQ | TUMOR | STROMA | NAME | TRAINING | TRAIN_DONE | MEASURING | RESULT
def step = new java.util.concurrent.atomic.AtomicReference('CHOICE')
def trainedName = new java.util.concurrent.atomic.AtomicReference(null)
def exampleName = new java.util.concurrent.atomic.AtomicReference('tumor-stroma-RF')
def exampleExisted = new java.util.concurrent.atomic.AtomicBoolean(false)
def activeModel = new java.util.concurrent.atomic.AtomicReference(null)
def activeWholeSlide = new java.util.concurrent.atomic.AtomicBoolean(false)
def measureResult = new java.util.concurrent.atomic.AtomicReference(null)
def render  // forward declaration

// FontAwesome glifi (ControlsFX, QuPath ile paketli). Glif ADIYLA çağrılır
// (ör. 'CLOUD_DOWNLOAD'); yazı tipi/ad bulunamazsa null döner → buton yalnız
// metinle çalışır (tema-duyarlı; -fx-text-base-color izler).
def faIcon = { String glyphName ->
    try {
        def node = org.controlsfx.glyphfont.Glyph.create('FontAwesome|' + glyphName)
        node.setStyle('-fx-text-fill: -fx-text-base-color;')
        return node
    } catch (Throwable ignored) { return null }
}
def navButton = { String text, Closure action, String tooltip = null, String icon = null ->
    def b = new javafx.scene.control.Button(text); b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    if (icon) { def g = faIcon(icon); if (g != null) b.setGraphic(g) }
    return b
}
// Belirsiz (indeterminate) ilerleme çubuğu — uzun süren adımlarda "çalışıyor" geri bildirimi.
def busyBar = { ->
    def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb
}

// Arka planda örnek kurulum (indirme FX'i dondurmasın).
def startInstall = {
    step.set('INSTALLING'); render()
    def worker = new Thread({
        def r = installExample()
        javafx.application.Platform.runLater {
            if (r.ok) {
                exampleName.set(r.name); exampleExisted.set(r.existed); step.set('EXAMPLE_DONE'); render()
            } else {
                if (stage != null) stage.setAlwaysOnTop(false)
                Dialogs.showErrorMessage('Örnek model kurulamadı',
                    r.reason == 'NO_PROJECT' ? 'Önce bir QuPath projesi açın.' : (r.error ?: 'Bilinmeyen hata.'))
                step.set('CHOICE'); render()
            }
        }
    }, 'AtolyeWizardInstall')
    worker.setDaemon(true); worker.start()
}

// Arka planda ölçüm (sınıflandırıcı + poligon üretimi yavaş olabilir).
def startMeasure = { String modelName, boolean wholeSlide = false ->
    activeModel.set(modelName); activeWholeSlide.set(wholeSlide)
    step.set('MEASURING'); render()
    def worker = new Thread({
        def res = runMeasure(modelName, wholeSlide)
        javafx.application.Platform.runLater { measureResult.set(res); step.set('RESULT'); render() }
    }, 'AtolyeWizardMeasure')
    worker.setDaemon(true); worker.start()
}

render = { ->
    if (stage != null) stage.setAlwaysOnTop(true)   // her render'da üstte-kal yeniden uygulanır
    def s = computeState()
    def content = new javafx.scene.layout.VBox(10)
    content.setPadding(new javafx.geometry.Insets(14))
    def buttons = new javafx.scene.layout.HBox(8)
    buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def bodyLbl = new javafx.scene.control.Label()
    bodyLbl.setWrapText(true)
    content.getChildren().addAll(title, bodyLbl)

    def cur = step.get()
    if (cur == 'CHOICE') {
        title.setText('Tümör/Stroma sihirbazı')
        bodyLbl.setText(
            'Nasıl devam etmek istersiniz?\n\n' +
            '• Örnek sınıflandırıcı: hazır paketli modeli projeye kurar.\n' +
            '• Yeni sınıflandırıcı eğit: kendi Tumor/Stroma bölgelerinizden eğitir.')
        buttons.getChildren().addAll(
            navButton('İptal', { stage.close() }),
            navButton('Örnek sınıflandırıcıyı kullan', { startInstall() },
                'Hazır paketli modeli projeye kurar (gerekirse internetten indirir)', 'CLOUD_DOWNLOAD'),
            navButton('Yeni sınıflandırıcı eğit ▶', {
                step.set((s.he && s.calib) ? 'TUMOR' : 'PREREQ'); render()
            }, 'Kendi Tumor/Stroma bölgelerinizden yeni bir model eğitir', 'FLASK'))
    } else if (cur == 'INSTALLING') {
        title.setText('Örnek model kuruluyor…')
        bodyLbl.setText('Paketli model projeye kaydediliyor (gerekirse indiriliyor). Lütfen bekleyin…')
        content.getChildren().add(busyBar())
    } else if (cur == 'EXAMPLE_DONE') {
        String nm = exampleName.get()
        title.setText('Örnek model hazır — şimdi bölgeyi seçin')
        bodyLbl.setText(
            (exampleExisted.get()
                ? "'${nm}' zaten projenizde mevcut — kullanıma hazır.\n\n"
                : "Örnek sınıflandırıcı '${nm}' projenize kuruldu.\n\n") +
            'Çalıştırmak için:\n' +
            '  1. Slaytta ölçmek istediğiniz dokuyu bir anotasyonla çizin (Brush/Polygon).\n' +
            '  2. Bu anotasyonu SEÇİN (üzerine tıklayın).\n' +
            '  3. Aşağıdan "Seçili bölgede ölç".\n\n' +
            'Seçim yapmazsanız sınıfı "Region" olan anotasyonlar kullanılır.\n' +
            'İsteğe bağlı: boş cam/artefakt alanlarını çizip sınıfını "Ignore*" yapın — ölçümden çıkarılır.')
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('CHOICE'); render() }),
            navButton('Tüm slaytta ölç', { startMeasure(nm, true) },
                'Tüm slaytta çalıştırır (seçim gerekmez; boş cam da sayılabilir — kaba tahmin)'),
            navButton('Seçili bölgede ölç ▶', { startMeasure(nm, false) },
                "Seçili anotasyon(lar)da '${nm}' modelini çalıştırır; seçim yoksa sınıfı 'Region' olanlar"))
    } else if (cur == 'PREREQ') {
        title.setText('Önce: görüntü tipi ve kalibrasyon')
        bodyLbl.setText(
            "Eğitim için iki önkoşul gerekir:\n\n" +
            "  ${s.he ? '✓' : '✗'} Görüntü tipi Brightfield (H&E) + boya vektörleri\n" +
            "  ${s.calib ? '✓' : '✗'} Piksel kalibrasyonu (µm/px)\n\n" +
            "Eksik olanı aşağıdaki düğmelerle düzeltin, sonra 'Yenile'.")
        def fixType = navButton('Görüntü tipini ayarla', {
            if (stage != null) stage.setAlwaysOnTop(false)
            if (!launchBundled('yardimci-image-type.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Görüntü tipi ayarla')
        })
        def fixCal = navButton('Kalibrasyonu ayarla', {
            if (stage != null) stage.setAlwaysOnTop(false)
            if (!launchBundled('yardimci-kalibrasyon.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu)')
        })
        content.getChildren().add(new javafx.scene.layout.HBox(8, fixType, fixCal))
        def cont = navButton('Devam ▶', { step.set('TUMOR'); render() })
        cont.setDisable(!(s.he && s.calib))
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('CHOICE'); render() }),
            navButton('⟳ Yenile', { render() }), cont)
    } else if (cur == 'TUMOR') {
        title.setText('1/3 — Tümör bölgesi çizin')
        bodyLbl.setText(
            'Slaytta birkaç temsilî tümör adasını anotasyonla çizin ve sınıfını\n' +
            '**Tumor** yapın (Annotations panelinden sınıf atayın). Bitince İleri.\n\n' +
            "Şu an bulunan Tumor anotasyonu: ${s.tumor}")
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('CHOICE'); render() }),
            navButton('⟳ Yenile', { render() }),
            navButton('İleri ▶', {
                if (s.tumor > 0) { step.set('STROMA'); render() }
                else Dialogs.showWarningNotification('Tumor anotasyonu yok',
                    'Henüz sınıfı "Tumor" olan bir alan anotasyonu görünmüyor. Bir bölge çizip\n' +
                    'sınıfını Tumor yaptığınızı kontrol edin, sonra tekrar İleri.')
            }))
    } else if (cur == 'STROMA') {
        title.setText('2/3 — Stroma bölgesi çizin')
        bodyLbl.setText(
            'Şimdi birkaç stromal bölgeyi anotasyonla çizin ve sınıfını **Stroma**\n' +
            'yapın. Bitince İleri.\n\n' +
            "Şu an bulunan Stroma anotasyonu: ${s.stroma}")
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('TUMOR'); render() }),
            navButton('⟳ Yenile', { render() }),
            navButton('İleri ▶', {
                if (s.stroma > 0) { step.set('NAME'); render() }
                else Dialogs.showWarningNotification('Stroma anotasyonu yok',
                    'Henüz sınıfı "Stroma" olan bir alan anotasyonu görünmüyor. Bir bölge çizip\n' +
                    'sınıfını Stroma yaptığınızı kontrol edin, sonra tekrar İleri.')
            }))
    } else if (cur == 'NAME') {
        title.setText('3/3 — Sınıflandırıcıya ad verin')
        bodyLbl.setText(
            "Eğitim verisi: Tumor=${s.tumor}, Stroma=${s.stroma} (bu slayt).\n" +
            'Modeli kaydetmek için bir ad girin, sonra "Eğit & kaydet".')
        def nameField = new javafx.scene.control.TextField(atolyeS('atolye.classifierName', 'tumor-stroma-RF'))
        nameField.setPrefColumnCount(28)
        content.getChildren().add(new javafx.scene.layout.HBox(8,
            new javafx.scene.control.Label('Ad:'), nameField))
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('STROMA'); render() }),
            navButton('Eğit & kaydet', {
                String nm = nameField.getText()?.trim()
                if (!nm) { Dialogs.showWarningNotification('Ad gerekli', 'Bir sınıflandırıcı adı girin.'); return }
                if (s.classifiers.contains(nm)) {
                    if (stage != null) stage.setAlwaysOnTop(false)
                    boolean ow = Dialogs.showConfirmDialog('Üzerine yaz?', "'${nm}' zaten var. Üzerine yazılsın mı?")
                    if (stage != null) stage.setAlwaysOnTop(true)
                    if (!ow) return
                }
                trainedName.set(nm)
                step.set('TRAINING'); render()
                def t0 = System.currentTimeMillis()
                def worker = new Thread({
                    def res = trainAndSave(nm)
                    double elapsed = (System.currentTimeMillis() - t0) / 1000.0
                    javafx.application.Platform.runLater {
                        if (res.ok) {
                            println String.format(java.util.Locale.US, 'Sihirbaz: model kaydedildi %s (%.1f sn)', nm, elapsed)
                            step.set('TRAIN_DONE'); render()
                        } else {
                            if (stage != null) stage.setAlwaysOnTop(false)
                            Dialogs.showErrorMessage('Eğitim başarısız', res.error ?: '(bilinmeyen hata)')
                            step.set('NAME'); render()
                        }
                    }
                }, 'AtolyeWizardTrain')
                worker.setDaemon(true); worker.start()
            }, 'Random Forest eğitir ve modeli projeye bu adla kaydeder'))
    } else if (cur == 'TRAINING') {
        title.setText('Eğitiliyor…')
        bodyLbl.setText("'${trainedName.get()}' eğitiliyor ve kaydediliyor. Lütfen bekleyin…")
        content.getChildren().add(busyBar())
    } else if (cur == 'TRAIN_DONE') {
        String nm = trainedName.get()
        title.setText('Model kaydedildi ✅ — şimdi bölgeyi seçin')
        bodyLbl.setText(
            "'${nm}' projeye kaydedildi.\n\n" +
            'Çalıştırmak için:\n' +
            '  1. Ölçmek istediğiniz dokuyu bir anotasyonla çizin.\n' +
            '  2. Bu anotasyonu SEÇİN.\n' +
            '  3. Aşağıdan "Seçili bölgede ölç".\n\n' +
            'Seçim yapmazsanız sınıfı "Region" olan anotasyonlar kullanılır.\n' +
            'İsteğe bağlı: boş cam/artefakt alanlarını çizip sınıfını "Ignore*" yapın — ölçümden çıkarılır.')
        buttons.getChildren().addAll(
            navButton('Kapat', { stage.close() }),
            navButton('Tüm slaytta ölç', { startMeasure(nm, true) },
                'Tüm slaytta çalıştırır (seçim gerekmez; boş cam da sayılabilir — kaba tahmin)'),
            navButton('Seçili bölgede ölç ▶', { startMeasure(nm, false) },
                "Seçili anotasyon(lar)da '${nm}' modelini çalıştırır; seçim yoksa sınıfı 'Region' olanlar"))
    } else if (cur == 'MEASURING') {
        title.setText('Ölçülüyor…')
        bodyLbl.setText("'${activeModel.get()}' ${activeWholeSlide.get() ? 'tüm slaytta' : 'seçili bölgede'} çalıştırılıyor (alanlar + poligonlar). Lütfen bekleyin…")
        content.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        def r = measureResult.get()
        if (r != null && r.ok) {
            title.setText('Ölçüm sonucu ✅')
            bodyLbl.setText(r.text + '\n\nKilitli "Tümör-Stroma Özet" anotasyonu yazıldı (Modül 9 ile dışa aktarılabilir).')
        } else if (r != null && r.error == 'NO_REGION') {
            title.setText('Önce bir bölge seçin')
            bodyLbl.setText('Ölçmek için bir anotasyon çizip SEÇİN (ya da sınıfını "Region" yapın), sonra "↻ Tekrar ölç".')
        } else {
            title.setText('Ölçüm yapılamadı')
            bodyLbl.setText((r?.error ?: 'Bilinmeyen hata.').toString())
        }
        buttons.getChildren().addAll(
            navButton('Kapat', { stage.close() }),
            navButton('↻ Tekrar ölç', { startMeasure(activeModel.get(), activeWholeSlide.get()) },
                'Aynı kapsamda ölçümü tekrarlar (önce yeni bölge seçebilirsiniz)'))
    }

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(content)
    // Kalıcı sorumluluk reddi notu — tema-duyarlı (açık/koyu tema): sabit renk yok,
    // QuPath tema değişkeni + opaklık/italik ile sönük gösterilir.
    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, buttons)
    bottom.setPadding(new javafx.geometry.Insets(10))
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 620, 500))
}

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Modül 6 - Tümör/Stroma Sihirbazı')
        stage.setAlwaysOnTop(true)
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
