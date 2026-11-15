/**
 * Tümör/Stroma Sihirbazı (tek pencere: kur/eğit + ölç)
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
import qupath.fx.dialogs.Dialogs
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
def atolyeI = { String k, int d -> (int) __wpCall('intg', [String.class, int.class] as Class[], [k, d] as Object[], d) }

// ── Eğitim sınıfları (zorunlu + isteğe bağlı) ve çıktı yardımcıları ──
def MANDATORY_CLASSES = ['Tumor', 'Stroma', 'Ignore*']
def OPTIONAL_CLASSES  = ['Muscle', 'Fat', 'Immune cells']
def ALL_TRAIN_CLASSES = MANDATORY_CLASSES + OPTIONAL_CLASSES
def SUMMARY_CLASS = 'TSR Özet'              // özet anotasyonun sınıfı → göz simgesiyle gizlenebilir
def OUTPUT_TAG = 'Tümör/Stroma ölçüm'       // üretilen sınıf bölgelerinin açıklama etiketi (idempotent temizlik)
def pathClassFor = { String nm ->
    if (nm == 'Ignore*') return qupath.lib.objects.classes.PathClass.StandardPathClasses.IGNORE
    return qupath.lib.objects.classes.PathClass.fromString(nm)
}
// Eğitim/özet sınıflarını Anotasyonlar paneli listesine ekle (yoksa) — kullanıcı
// 'Muscle'/'Fat' gibi varsayılan-olmayan sınıfları ancak listede varsa atayabilir.
def ensureClassesRegistered = { ->
    def gui = qupath.lib.gui.QuPathGUI.getInstance()
    if (gui == null) return
    try {
        def avail = gui.getAvailablePathClasses()
        def existing = avail.collect { it?.getName() } as Set
        (ALL_TRAIN_CLASSES + [SUMMARY_CLASS]).each { nm ->
            if (!existing.contains(nm)) avail.add(pathClassFor(nm))
        }
    } catch (Throwable ignored) { }
}

// ── Paketli kardeş betikleri çalıştırma (yalnız ön koşul yardımcıları için) ──
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
        catch (Throwable err) {
            println "Alt betik hatası (${name}): ${err}"
            def stepWhere = [
                'yardimci-image-type.groovy': workshopText('script.c2c8bba69355', 'Extensions → Atölye → Yardımcılar → Temel araçlar → Görüntü tipi ayarla'),
                'yardimci-kalibrasyon.groovy': workshopText('script.e3dc2534d6aa', 'Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)')
            ][name] ?: workshopText('script.e251977b9cc3', 'Extensions → Atölye menüsündeki ilgili araç')
            def stepMsg = String.format(java.util.Locale.US, workshopText('script.4682ed652f87', 'Sihirbazın yardımcı adımlarından biri çalışırken hata verdi; sihirbazın kendisi açık kalır.\nAdımı kendi menü girişinden çalıştırın: %s\nArdından sihirbazda "Yenile"ye basın; durum değişmezse sihirbazı kapatıp yeniden açın.\n\nTeknik ayrıntı: %s'), stepWhere, String.valueOf(err))
            try { javafx.application.Platform.runLater { Dialogs.showErrorMessage(workshopText('script.f2ef0ca7ef8f', 'Yardımcı adım çalışmadı'), stepMsg) } }
            catch (Throwable ignored) { }
        }
    }, "AtolyeWizard-${name}")
    runner.setDaemon(true); runner.start()
    return true
}
def menuHint = { String where ->
    Dialogs.showMessageDialog('Eklenti gerekli',
        "${workshopText('script.2da9535b4302', 'Bu adım atölye eklentisini gerektirir. Menüden çalıştırın:\n')}${where}")
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
    def st = [image:false, project:false, he:false, calib:false, tumor:0, stroma:0, counts:[:], classifiers:[]]
    def imageData = QP.getCurrentImageData()
    st.image = imageData != null
    def project = QP.getProject()
    st.project = project != null
    if (imageData != null) {
        def typeName = (imageData.getImageType()?.toString() ?: '').toUpperCase(Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
        st.he = typeName.contains('BRIGHTFIELD_H_E') && imageData.getColorDeconvolutionStains() != null
        def cal = imageData.getServer()?.getPixelCalibration()
        st.calib = cal != null && cal.getAveragedPixelSizeMicrons() > 0
        def counts = [:]
        ALL_TRAIN_CLASSES.each { counts[it] = 0 }
        QP.getAnnotationObjects().each {
            def n = it.getPathClass()?.getName()
            if (it.getROI()?.isArea() && n != null && counts.containsKey(n)) counts[n] = counts[n] + 1
        }
        st.counts = counts
        st.tumor = counts['Tumor'] ?: 0
        st.stroma = counts['Stroma'] ?: 0
    }
    if (project != null) st.classifiers = new ArrayList(project.getPixelClassifiers().getNames())
    return st
}

// ── Eğit & kaydet (eski ayrı Tümör/Stroma eğitici betiği ile aynı atölye ayarları) ──
// selectedNames: eğitime girecek sınıf adları (zorunlu + işaretli isteğe bağlılar).
def trainAndSave = { String targetName, java.util.List selectedNames ->
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

        // Yalnız SEÇİLİ sınıflardan etiket haritası kur (işaretsiz/başıboş sınıflar — örn.
        // 'Region' — eğitime girmez). Kullanıcı anotasyonları DEĞİŞTİRİLMEZ.
        def labelMap = new java.util.LinkedHashMap()
        int idx = 0
        ALL_TRAIN_CLASSES.each { nm ->
            if (!(nm in selectedNames)) return
            def pc = QP.getAnnotationObjects().find {
                it.getROI()?.isArea() && it.getPathClass()?.getName() == nm }?.getPathClass()
            if (pc != null) labelMap.put(pc, idx++)
        }
        if (labelMap.size() < 2)
            return [ok:false, error:'Anotasyonlardan en az iki sınıf çıkarılamadı. Tumor ve Stroma bölgelerinin boş olmadığından emin olun.']

        def trainingData = training.createTrainingDataForLabelMap(
            java.util.Collections.singletonList(imageData), labelMap)

        // QuPath'in yerel "Train pixel classifier" penceresi gibi eğitim örneklerini sınırla
        // (Atölye ayarı 'atolye.maxTrainingSamples', varsayılan 100000; 0 ⇒ sınır yok). RF eğitim
        // süresi örnek sayısıyla artar; sınır olmadan büyük anotasyonlarda eğitim çok yavaşlar.
        def trainData = trainingData.getTrainData()
        int maxTrainingSamples = atolyeI('atolye.maxTrainingSamples', 100000)
        if (maxTrainingSamples > 0 && trainData.getNTrainSamples() > maxTrainingSamples)
            trainData.setTrainTestSplit(maxTrainingSamples, true)
        def model = OpenCVClassifiers.createStatModel(RTrees.class)
        model.train(trainData)

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

// ── Önceki sonuç: SORULMADAN silinmez (ev kuralı: sonuç yazan her sihirbaz önce sorar) ──
// Ekle (varsayılan): hiçbir şey silinmez; yeni özet "Tümör-Stroma Özet (2)", "(3)" … adını alır
// (özet kapalıysa yeni sınıf bölgeleri "Tumor (2)" … adını alır).
// Değiştir: yalnız bu sihirbazın özetleri (TAM ad ya da "ad (N)", önek eşleşmesi YOK; eski adı "TSR Özet" de)
// ve ürettiği etiketli sınıf bölgeleri silinir; içlerindeki başka nesneler KORUNUR (keepChildren = true).
def SUMMARY_BASE = 'Tümör-Stroma Özet'
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
// Bu sihirbazın seti mi? Yalnız TAM ad ya da "ad (N)" — önek eşleşmesi YOK:
// kullanıcının "Tümör-Stroma Özet düzeltmelerim" diye adlandırdığı anotasyon süpürülmez.
def isOwnSetName = { String name, String base ->
    if (name == null) return false
    if (name == base) return true
    return name.startsWith(base + ' (') && name.endsWith(')') &&
        name.substring(base.length() + 2, name.length() - 1).isInteger() &&
        (name.substring(base.length() + 2, name.length() - 1) as int) >= 2
}
def ownSetIndex = { String name, String base ->
    return (name == base) ? 1 : (name.substring(base.length() + 2, name.length() - 1) as int)
}
def prevOwnObjects = { hierarchy, String base ->
    if (hierarchy == null) return []
    return hierarchy.getAnnotationObjects().findAll { isOwnSetName(it.getName(), base) }
}
def nextOwnSetName = { hierarchy, String base ->
    def used = prevOwnObjects(hierarchy, base).collect { ownSetIndex(it.getName(), base) }
    if (used.isEmpty()) return base
    return base + ' (' + ((used.max() as int) + 1) + ')'
}
// Özet adı: bu sihirbazın adı (ya da eski adı "TSR Özet") — tam ad ya da "(N)".
def isOwnSummaryName = { String name ->
    return name != null && (name == 'TSR Özet' || isOwnSetName(name, SUMMARY_BASE))
}
def prevSummaryObjects = { hierarchy ->
    if (hierarchy == null) return []
    return hierarchy.getAnnotationObjects().findAll { isOwnSummaryName(it.getName()) }
}
// Bu sihirbazın ürettiği sınıf bölgesi (anotasyon ya da tespit): ad = sınıf adı ("Tumor") ya da "sınıf (N)" ve
// sınıf boş değil; anotasyonlarda ayrıca açıklama "<etiket> — <sınıf> (<model>)" biçiminde. Tespit olarak üretilen
// bölgelerde açıklama YOKTUR (PathDetectionObject'te getDescription/setDescription yok) → orada ad ölçütü yeter
// (kullanıcının hücre tespitleri adsızdır). Yeniden sınıflandırılmış ya da yeniden adlandırılmış nesne eşleşmez.
// (Eski kural açıklamanın önekiydi ve tespit nesnelerinde getDescription çağırıyordu.)
def isOwnOutputObject = { obj ->
    String cn = obj.getPathClass()?.getName()
    if (cn == null || !isOwnSetName(obj.getName(), cn)) return false
    if (!(obj instanceof qupath.lib.objects.PathAnnotationObject)) return obj.isDetection()
    String d = obj.getDescription() ?: ''
    return d.startsWith(OUTPUT_TAG + ' — ' + cn + ' (') && d.endsWith(')')
}
def prevOutputObjects = { hierarchy ->
    if (hierarchy == null) return []
    return (hierarchy.getAnnotationObjects() + hierarchy.getDetectionObjects()).findAll { isOwnOutputObject(it) }
}
// Özet kaynağı işareti (meta veri `atolye_ozet_kaynak`): 'arac' = bu sihirbazın kendi oluşturduğu özet;
// 'kullanici' = kullanıcının çizdiği tek bölge, özet olarak yeniden kullanıldığında ADI DEĞİŞTİRİLEN anotasyon.
// İşaretsiz (bu değişiklikten önceki) özet kullanıcının bölgesi olabilir → ASLA silinmez, yerinde yeniden ölçülür.
// Silme yalnız açıkça 'arac' işaretli özet için serbesttir.
def SUMMARY_SOURCE_KEY = 'atolye_ozet_kaynak'
def summarySource = { obj ->
    try { return obj.getMetadata()?.get(SUMMARY_SOURCE_KEY) } catch (Throwable ignore) { return null }
}
def markSummary = { obj, String value ->
    try { obj.getMetadata().put(SUMMARY_SOURCE_KEY, value) } catch (Throwable ignore) {}
}
def isToolMadeSummary = { obj ->
    return summarySource(obj) == 'arac'
}
// Değiştir'in silebileceği sınıf bölgesi: üst düzeydeki (özetsiz sonuç), 'arac' işaretli bir özetin altındaki, ya da
// yerinde yeniden ölçülen `target` anotasyonunun altındaki. İşaretsiz/'kullanici' özetin altındakilere dokunulmaz.
def isReplaceableOutput = { obj, target ->
    def p = obj.getParent()
    if (p == null || p.isRootObject()) return true
    if (p == target) return true
    return isOwnSummaryName(p.getName()) && isToolMadeSummary(p)
}

// ── Yerinde ölçüm (çekirdek API; eklenti yansıması gerektirmez) ─────
def classifierAreaToMm2 ={ Number value, String measurementName ->
    if (value == null) return Double.NaN
    double numeric = value.doubleValue()
    String lower = measurementName.toLowerCase(java.util.Locale.ROOT)
    if (lower.contains('µm^2') || lower.contains('μm^2') || lower.contains('um^2')) return numeric / 1_000_000.0
    if (lower.contains('mm^2')) return numeric
    return Double.NaN
}
// Dönüş: [ok:true, text] | [ok:false, error:'NO_REGION'|<mesaj>]
def runMeasure = { String modelName, boolean wholeSlide, boolean asDetections = false, boolean createSummary = true, String mode = 'add' ->
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

        // Önceki sonuç (bu sihirbazın özetleri + etiketli sınıf bölgeleri) SORULMADAN silinmez. Kip çağırandan
        // gelir (startMeasure, FX iş parçacığında, işlemden ÖNCE sordu). Önceki sonuç varken kip belirtilmediyse
        // hiçbir şey yazılmadan durulur (fail closed). Hepsi yeni nesne eklenmeden ÖNCE alınır.
        def hierM = imageData.getHierarchy()
        def prevSummaries = prevSummaryObjects(hierM)
        def prevOutputs = prevOutputObjects(hierM)
        boolean hasPrev = !prevSummaries.isEmpty() || !prevOutputs.isEmpty()
        if (hasPrev && mode != MODE_ADD && mode != MODE_REPLACE)
            return [ok:false, error:workshopText('script.d1e6a0b30433', 'Bu lamda önceki bir Tümör/Stroma sonucu var ve ne yapılacağı belirtilmedi. Hiçbir şey yazılmadı.')]
        boolean addRun = hasPrev && mode == MODE_ADD
        boolean replaceRun = hasPrev && mode == MODE_REPLACE

        // Kapsam: tüm lam VEYA seçili anotasyon(lar). Kapsam dışı tutulanlar:
        // özet anotasyonu, Tumor/Stroma (çıktı/eğitim sınıfları) ve 'Ignore*'
        // (boş cam/artefakt — ölçümden çıkarılacak alanlar).
        def outputClasses = ['Tumor', 'Stroma', 'Ignore*', 'Muscle', 'Fat', 'Immune cells', SUMMARY_CLASS]
        def isOwnOutput = { obj ->
            String nm = obj.getName()
            def cn = obj.getPathClass()?.getName()
            isOwnSummaryName(nm) || cn in outputClasses
        }
        // Kapsam ROI'si salt-okunur belirlenir; özet/geçici nesne ölçümden SONRA kurulur.
        def regions = []
        def reuseCandidate = null   // tek seçili önceki özet (yeniden ölçüm)
        def scopeRoi
        if (wholeSlide) {
            def server = imageData.getServer()
            scopeRoi = qupath.lib.roi.ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(),
                qupath.lib.regions.ImagePlane.getDefaultPlane())
        } else {
            def selectedAreas = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI()?.isArea() }
            if (selectedAreas.size() == 1 && isOwnSummaryName(selectedAreas[0].getName())) {
                reuseCandidate = selectedAreas[0]
                scopeRoi = reuseCandidate.getROI()
            } else {
                regions = selectedAreas.findAll { !isOwnOutput(it) }
                if (regions.isEmpty()) {
                    regions = QP.getAnnotationObjects().findAll {
                        it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Region' && !isOwnOutput(it)
                    }
                }
                if (regions.isEmpty()) return [ok:false, error:'NO_REGION']
                scopeRoi = (regions.size() == 1) ? regions[0].getROI()
                    : RoiTools.union(regions.collect { it.getROI() })
            }
        }

        // İsteğe bağlı: sınıfı 'Ignore*' olan alanları ölçüm KAPSAMINDAN çıkar. Yeniden
        // kullanılan anotasyonun ROI'sini daraltmamak için ayrı bir measureRoi kullanılır.
        def ignoreRegions = QP.getAnnotationObjects().findAll { it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Ignore*' }
        def measureRoi = scopeRoi
        if (measureRoi != null && !ignoreRegions.isEmpty()) {
            try {
                def ig = RoiTools.union(ignoreRegions.collect { it.getROI() })
                def diff = measureRoi.getGeometry().difference(ig.getGeometry())
                if (diff != null && !diff.isEmpty())
                    measureRoi = qupath.lib.roi.GeometryTools.geometryToROI(diff, measureRoi.getImagePlane())
            } catch (Throwable ignored) { }
        }
        if (measureRoi == null || measureRoi.isEmpty() || !measureRoi.isArea())
            return [ok:false, error:'Ölçülecek alan kalmadı (Ignore* tüm bölgeyi kaplıyor olabilir).']

        def manager = PixelClassifierTools.createMeasurementManager(imageData, classifier)
        def names = manager.getMeasurementNames()
        String tumorName = names.find { it.startsWith('Tumor area ') }
        String stromaName = names.find { it.startsWith('Stroma area ') }
        if (tumorName == null || stromaName == null)
            return [ok:false, error:'Model Tumor/Stroma çıktı sınıflarını içermiyor.']

        double tumorMm2 = classifierAreaToMm2(manager.getMeasurementValue(measureRoi, tumorName), tumorName)
        double stromaMm2 = classifierAreaToMm2(manager.getMeasurementValue(measureRoi, stromaName), stromaName)
        double classified = tumorMm2 + stromaMm2
        double roiMm2 = measureRoi.getArea() * pw * ph / 1_000_000.0
        double tumorPct = classified > 0 ? 100.0 * tumorMm2 / classified : Double.NaN
        double stromaPct = classified > 0 ? 100.0 * stromaMm2 / classified : Double.NaN
        double ratio = stromaMm2 > 0 ? tumorMm2 / stromaMm2 : Double.NaN
        double coverage = roiMm2 > 0 ? 100.0 * classified / roiMm2 : Double.NaN

        // ── Çıktı taşıyıcısını belirle: kalıcı özet (createSummary) ya da geçici nesne ──
        // createSummary=false ise kalıcı özet yazılmaz; sınıf bölgeleri üst düzeyde üretilir
        // ve kullanıcı anotasyonu yeniden kullanılmaz/silinmez (yalnız sonuç penceresi).
        // Ekle: kullanıcının bölgesi yeniden adlandırılmaz/ölçümleri silinmez, önceki özet yerinde yeniden ölçülmez;
        // yeni, ayrı bir özet kurulur ("Tümör-Stroma Özet (2)" …).
        def summary
        boolean reuse = false
        // Değiştir + yerinde yeniden ölçüm yok + silinmeyen önceki özet var: ad çakışmasın diye sıradaki ad.
        boolean keptSummaryExists = prevSummaries.any { it != reuseCandidate && !isToolMadeSummary(it) }
        String summaryName = (addRun || (replaceRun && reuseCandidate == null && keptSummaryExists)) ? nextOwnSetName(hierM, SUMMARY_BASE) : SUMMARY_BASE
        if (createSummary) {
            if (reuseCandidate != null && !addRun) { summary = reuseCandidate; reuse = true }   // yerinde yeniden ölçüm: ad/çizgi korunur
            else if (!addRun && !wholeSlide && regions.size() == 1) {
                // Kullanıcının kendi bölgesi özet olarak yeniden kullanılıyor: işaretle ('kullanici') → sonraki Değiştir silmez.
                summary = regions[0]; summary.setName(summaryName); markSummary(summary, 'kullanici'); reuse = true
            }
            else { summary = PathObjects.createAnnotationObject(scopeRoi); summary.setName(summaryName); markSummary(summary, 'arac') }
        } else {
            summary = PathObjects.createAnnotationObject(scopeRoi); summary.setName('__atolye_m6_tmp__')
        }
        int regionCount = (reuse || (addRun && reuseCandidate != null)) ? 1 : regions.size()

        // Değiştir (kullanıcı seçti ve onayladı): bu sihirbazın ÖNCEKİ özetleri ve etiketli sınıf bölgeleri silinir
        // (yeniden ölçülen özet 'summary' HARİÇ). İçlerindeki başka nesneler (hücre tespitleri, kullanıcının
        // anotasyonları) keepChildren = true ile KORUNUR — önceki removeObjectsAndDescendants onları da silerdi.
        int removedSummaries = 0
        int removedOutputs = 0
        if (replaceRun) {
            // Özet (summary) hiçbir koşulda silinmez; kullanıcının bölgesi/işaretsiz özet de silinmez, yerinde yeniden ölçülür.
            def oldOutputs = prevOutputs.findAll { it != summary && isReplaceableOutput(it, summary) }
            def oldSummaries = prevSummaries.findAll { it != summary && isToolMadeSummary(it) }
            if (!oldOutputs.isEmpty()) QP.removeObjects(oldOutputs, true)
            if (!oldSummaries.isEmpty()) QP.removeObjects(oldSummaries, true)
            removedSummaries = oldSummaries.size()
            removedOutputs = oldOutputs.size()
        }

        if (createSummary) {
            // Dışa aktarılabilir, kilitli özet anotasyonu (8 ölçüm + sayaçlar). Gizlenebilir
            // 'TSR Özet' sınıfı → Anotasyonlar sekmesindeki göz simgesiyle tek tıkla kapatılır.
            summary.setPathClass(pathClassFor(SUMMARY_CLASS))
            if (reuse) summary.getMeasurementList().clear()   // yalnız ölçümler sıfırlanır; önceki sınıf bölgeleri "Değiştir" ile (onaylıysa) yukarıda kaldırıldı
            summary.measurements['ROI alanı (mm2)'] = roiMm2
            summary.measurements['Tümör alanı (mm2)'] = tumorMm2
            summary.measurements['Stroma alanı (mm2)'] = stromaMm2
            summary.measurements['Sınıflandırılmış alan (mm2)'] = classified
            summary.measurements['Tümör alanı (%)'] = tumorPct
            summary.measurements['Stroma alanı (%)'] = stromaPct
            summary.measurements['Tümör/Stroma oranı'] = ratio
            summary.measurements['Sınıflandırılmış kapsam (%)'] = coverage
            summary.measurements['Ölçülen bölge sayısı'] = regionCount as double
            summary.measurements['Çıkarılan Ignore* alan sayısı'] = ignoreRegions.size() as double
            summary.setLocked(true)
            if (!reuse) QP.addObjects([summary])
        } else {
            QP.addObjects([summary])   // geçici nesne — yalnız sınıf poligonu üretimi için
        }

        // Görsel kontrol için sınıf bölgeleri: piksel sınıflandırıcının ürettiği çok
        // sayıda küçük poligon, her SINIF için TEK bölgeye birleştirilir ve sınıf adıyla
        // (Tumor / Stroma …) ETİKETLENİR — ad = sınıf olduğundan 'Show names' sınıfı yazar.
        // asDetections=true ise bölgeler Detections paneline (içi boş) yazılır; anotasyon
        // listesi temiz kalır. SELECT_NEW kullanılmaz (seçim sarı çizilir, rengi gizler).
        double minObj = atolyeD('atolye.minObjectArea', 10000.0)
        double minHole = atolyeD('atolye.minHoleArea', 5000.0)
        def before = QP.getAnnotationObjects() as Set
        QP.selectObjects(summary)
        // reuse: özet KULLANICININ KENDİ anotasyonudur → DELETE_EXISTING onun tüm alt nesnelerini
        // (ör. aynı bölgede daha önce çalıştırılmış hücre tespitleri) sessizce silerdi. Bu modülün
        // önceki sınıf bölgeleri yukarıda OUTPUT_TAG ile zaten kaldırıldı; burada silme yapılmaz.
        if (reuse) QP.createAnnotationsFromPixelClassifier(classifier, minObj, minHole)
        else QP.createAnnotationsFromPixelClassifier(classifier, minObj, minHole, 'DELETE_EXISTING')
        def generated = QP.getAnnotationObjects().findAll { !before.contains(it) && it.getPathClass() != null }
        def byClass = generated.groupBy { it.getPathClass().getName() }
        // `generated` = bu çalıştırmanın GEÇİCİ ara nesneleri. Referansla ayrılır: `before` (sınıflandırıcı çağrısından
        // hemen önce hiyerarşideki tüm anotasyonlar, kimlik tabanlı küme) içinde OLMAYAN anotasyonlar — ad ya da
        // sınıfla aranmaz. Çıktı, birleştirilmiş bölgelere aşağıda dönüştürülür; ara nesneler silinir. keepChildren = true:
        // içlerine yerleşmiş başka nesne olsaydı bile silinmez.
        if (!generated.isEmpty()) QP.removeObjects(generated, true)
        double smoothPx = (pw > 0 ? 3.0 / pw : 6.0)   // ~3 µm topolojik sadeleştirme (yalnız görsel)
        def plane = scopeRoi.getImagePlane()
        def outputs = []
        // Ekle + özet kapalı: yeni sınıf bölgeleri üst düzeyde durur → önceki "Tumor"/"Stroma" ile karışmasın diye
        // "Tumor (2)" … adını alır. (Özet açıkken bölgeler yeni özetin altındadır; ad düz sınıf adıdır.)
        int outputSet = 1
        if (addRun && !createSummary)
            outputSet = ((prevOutputs.collect { ownSetIndex(it.getName(), it.getPathClass().getName()) }.max() ?: 0) as int) + 1
        byClass.each { className, objs ->
            try {
                def geom = objs.collect { it.getROI().getGeometry() }.inject { a, b -> a.union(b) }
                if (geom == null || geom.isEmpty()) return
                if (!ignoreRegions.isEmpty()) {   // Ignore* alanlarını görsel bölgelerden de çıkar
                    try { def clip = geom.intersection(measureRoi.getGeometry()); if (clip != null && !clip.isEmpty()) geom = clip }
                    catch (Throwable ignored) { }
                }
                def simp = org.locationtech.jts.simplify.TopologyPreservingSimplifier.simplify(geom, smoothPx)
                if (simp != null && !simp.isEmpty()) geom = simp
                def roi = qupath.lib.roi.GeometryTools.geometryToROI(geom, plane)
                def obj = asDetections
                    ? PathObjects.createDetectionObject(roi, objs[0].getPathClass())
                    : PathObjects.createAnnotationObject(roi, objs[0].getPathClass())
                obj.setName(outputSet > 1 ? (className + ' (' + outputSet + ')') : className)   // 'Show names' sınıf adını yazar (Tumor / Stroma / …)
                try { obj.setDescription("${OUTPUT_TAG} — ${className} (${modelName})") } catch (Throwable ignored) { }
                if (!asDetections) obj.setLocked(true)
                outputs << obj
            } catch (Throwable ignored) { }
        }
        if (createSummary) {
            if (!outputs.isEmpty()) summary.addChildObjects(outputs)
        } else {
            // Özet atlandı: sınıf bölgeleri üst düzeyde dursun; geçici nesneyi kaldır.
            if (!outputs.isEmpty()) QP.addObjects(outputs)
            // Bu çalıştırmanın KENDİ geçici nesnesi (yukarıda referansla oluşturuldu); çocuğu yok, keepChildren = true.
            QP.removeObject(summary, true)
        }
        imageData.getHierarchy().getSelectionModel().clearSelection()
        QP.fireHierarchyUpdate()

        def fmt = { double v, String p -> Double.isFinite(v) ? String.format(java.util.Locale.US, p, v) : 'hesaplanamadı' }
        def text =
            "Model                : ${modelName}\n" +
            (wholeSlide
                ? "Kapsam               : Tüm lam (boş cam/arka plan da sayılabilir — kaba tahmin)\n"
                : "Ölçülen bölge sayısı : ${regionCount}\n") +
            "Görsel çıktı         : ${asDetections ? 'sınıf tespitleri (Detections paneli)' : 'etiketli sınıf anotasyonları'}\n" +
            "Özet anotasyonu      : ${createSummary ? 'yazıldı (TSR Özet sınıfı — göz simgesiyle gizlenebilir)' : 'oluşturulmadı (yalnız bu pencere)'}\n" +
            String.format(java.util.Locale.US, "ROI alanı            : %.3f mm²%n", roiMm2) +
            String.format(java.util.Locale.US, "Tümör alanı          : %.3f mm²%n", tumorMm2) +
            String.format(java.util.Locale.US, "Stroma alanı         : %.3f mm²%n", stromaMm2) +
            "Tümör alanı (%)      : ${fmt(tumorPct, '%.2f%%')}\n" +
            "Stroma alanı (%)     : ${fmt(stromaPct, '%.2f%%')}\n" +
            "Tümör/Stroma oranı   : ${fmt(ratio, '%.4f')}\n" +
            "Sınıflandırılmış kapsam: ${fmt(coverage, '%.2f%%')}"
        if (addRun) {
            // Ekle: önceki sonuç duruyor; yeni sonucun adı ve eskilerin adı açıkça yazılır.
            def prevNames = (prevSummaries.collect { it.getName() } + prevOutputs.collect { it.getName() }).unique().sort()
            String newSet = createSummary ? summaryName : ('… (' + outputSet + ')')
            text = text + '\n\n' + String.format(java.util.Locale.US, workshopText('script.98b0476bc643', 'ÖNCEKİ SONUÇ DURUYOR\nYeni sonuç: %s. Bu lamda önceki Tümör/Stroma sonucu da duruyor: %s. Hiçbir şey silinmedi; eski sonucu kaldırmak için o özet anotasyonunu (ve sınıf bölgelerini) seçip silin ya da bir sonraki çalıştırmada "Değiştir"i seçin. Yukarıdaki sayılar yalnız yeni çalıştırmayı gösterir; önceki özet anotasyonu eski değerleri göstermeye devam eder.'), newSet, prevNames.join(', '))
        } else if (replaceRun) {
            text = text + '\n\n' + String.format(java.util.Locale.US, workshopText('script.04273d6ac80a', 'ÖNCEKİ SONUÇ DEĞİŞTİRİLDİ\n%,d sınıf bölgesi ve aracın kendi oluşturduğu %,d eski özet anotasyonu silindi (içlerindeki diğer nesneler korundu). Sizin çizdiğiniz bölge ya da işaretlenmemiş özet silinmedi; özet bölgesi korundu ve ölçümler onda yeniden hesaplandı.'), removedOutputs, removedSummaries)
        }
        return [ok:true, text:text]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '(mesaj yok)')]
    }
}

if (isHeadless) {
    def s = computeState()
    println "Sihirbaz (headless): image=${s.image} project=${s.project} H&E=${s.he} " +
            "calib=${s.calib} Tumor=${s.tumor} Stroma=${s.stroma} models=${s.classifiers}"
    println "GUI olmadan sihirbaz etkileşimi yok. Ölçüm için tumor-stroma.groovy çalıştırın."
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
def asDetections = new java.util.concurrent.atomic.AtomicBoolean(false)
def createSummary = new java.util.concurrent.atomic.AtomicBoolean(true)
def tickedOptional = new java.util.concurrent.atomic.AtomicReference(null)   // Set<String> | null=ilk-giriş
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
// Sınıf bölgelerini anotasyon yerine tespit (detection) olarak üretme seçeneği.
// render() sahneyi her seferinde yeniden kurduğundan, durum asDetections'ta tutulur.
def detectionCheckBox = { ->
    def cb = new javafx.scene.control.CheckBox(workshopText('script.feadc3dfad73', 'Bölgeleri tespit (detection) olarak oluştur'))
    cb.setSelected(asDetections.get())
    cb.selectedProperty().addListener({ obs, o, n -> asDetections.set(n) } as javafx.beans.value.ChangeListener)
    cb.setTooltip(new javafx.scene.control.Tooltip(
        workshopText('script.480abd8be340', 'İşaretliyse sınıf bölgeleri Detections panelinde (içi boş) oluşturulur;\n' +
        'işaretsizse sınıf adıyla etiketli anotasyon olarak (varsayılan).')))
    return cb
}
// Özet anotasyonu oluşturma seçeneği — kapalıyken kalıcı özet yazılmaz (yalnız sonuç penceresi).
def summaryCheckBox = { ->
    def cb = new javafx.scene.control.CheckBox(workshopText('script.2adf98e8faaf', 'Özet anotasyonu oluştur (Veri dışa aktarma modülü için)'))
    cb.setSelected(createSummary.get())
    cb.selectedProperty().addListener({ obs, o, n -> createSummary.set(n) } as javafx.beans.value.ChangeListener)
    cb.setTooltip(new javafx.scene.control.Tooltip(
        workshopText('script.cf9c4392ce6a', 'İşaretliyse kilitli "Tümör-Stroma Özet" anotasyonu ("TSR Özet" sınıfı) yazılır —\n' +
        'göz simgesiyle gizlenebilir, Veri dışa aktarma modülüyle dışa aktarılır.\n' +
        'İşaretsizse özet oluşturulmaz; sınıf bölgeleri yine görünür, sonuçlar yalnız bu pencerede.')))
    return cb
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
                Dialogs.showErrorMessage(workshopText('script.44f6035a8c0f', 'Örnek model kurulamadı'),
                    r.reason == 'NO_PROJECT' ? workshopText('script.1868bcfd9140', 'Önce bir QuPath projesi açın.') : (r.error ?: workshopText('script.334b848286b9', 'Bilinmeyen hata.')))
                step.set('CHOICE'); render()
            }
        }
    }, 'AtolyeWizardInstall')
    worker.setDaemon(true); worker.start()
}

// Önceki sonuç için kullanıcıya SOR (FX iş parçacığında, ölçümden ÖNCE çağrılır — düğme işleyicileri FX'te).
//   Dönüş: 'add' | 'replace' | null (İptal → hiçbir şey çalışmaz, hiçbir şey yazılmaz).
//   Önceki sonuç yoksa sormadan 'add' döner. Pencere üstte-kal iken diyalog arkada kalmasın diye geçici kapatılır.
def askExisting = { ->
    def hierQ = QP.getCurrentImageData()?.getHierarchy()
    def prevS = prevSummaryObjects(hierQ)
    def prevAll = prevOutputObjects(hierQ)
    if (prevS.isEmpty() && prevAll.isEmpty()) return MODE_ADD
    // Sayı: Değiştir'in silebileceği sınıf bölgeleri (tek seçili anotasyon yerinde yeniden ölçülecekse onun altındakiler dahil).
    def selOne = QP.getSelectedObjects().findAll { it.isAnnotation() }
    def prevO = prevAll.findAll { isReplaceableOutput(it, selOne.size() == 1 ? selOne[0] : null) }
    String optAdd = workshopText('script.c926444eface', 'Ekle — öncekine dokunma, yeni bir sonuç olarak ekle (varsayılan)')
    String optRep = workshopText('script.320920522b08', 'Değiştir — önceki sınıf bölgeleri silinir; özet bölgeniz korunur ve aynı anotasyonda yeniden ölçülür')
    if (stage != null) stage.setAlwaysOnTop(false)
    try {
        def pick = Dialogs.showChoiceDialog(workshopText('script.a43bd962f751', 'Önceki Tümör/Stroma sonucu'),
            String.format(java.util.Locale.US, workshopText('script.a2c2f2d27f11', 'Bu lamda zaten %,d Tümör-Stroma özeti var; Değiştir seçilirse silinebilecek sınıf bölgesi sayısı: %,d.\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni sonuç ayrı bir özet olur ("Tümör-Stroma Özet (2)" gibi) ve kendi sınıf bölgelerini taşır (özet kapalıysa yeni bölgeler "Tumor (2)" gibi adlanır). Önceki özetler değişmeden kalır.\n\n"Değiştir" seçilirse bu sihirbazın ürettiği önceki Tumor/Stroma sınıf bölgeleri silinir — onlarda elle yaptığınız düzeltmeler de gider. Özet olarak kullanılan bölgeniz ASLA silinmez: çizgisi, adı ve sınıfı korunur, yalnızca ölçümleri aynı anotasyonda yeniden hesaplanır. Yalnızca aracın kendi oluşturduğu eski özet anotasyonları silinir; bu araçla işaretlenmemiş (eski sürümden kalma) özetler olduğu gibi kalır. İçlerindeki diğer nesneler (hücre tespitleri, kendi anotasyonlarınız) silinmez.\n\nİptal: hiçbir şey çalıştırılmaz, hiçbir şey yazılmaz.\n\nÖnceki Tümör/Stroma sonucu ne olsun?'), prevS.size(), prevO.size()),
            [optAdd, optRep], optAdd)
        if (pick == null) return null
        if (pick == optRep) {
            boolean ok = Dialogs.showConfirmDialog(workshopText('script.2c478add6c37', 'Önceki sınıf bölgeleri silinecek'),
                String.format(java.util.Locale.US, workshopText('script.f7a62bf61fe4', 'Bu lamda %,d Tümör-Stroma özeti var; %,d sınıf bölgesi silinecek. Sınıf bölgelerinde elle yaptığınız düzeltmeler KAYBOLUR. Özet olarak kullanılan bölgeniz silinmez: yerinde yeniden ölçülür. Yalnızca aracın kendi oluşturduğu eski özet anotasyonları silinir; sizin çizdiğiniz ya da işaretlenmemiş özetler korunur. İçlerindeki diğer nesneler de korunur.\n\nDevam edilsin mi?'), prevS.size(), prevO.size()))
            return ok ? MODE_REPLACE : null
        }
        return MODE_ADD
    } finally {
        if (stage != null) stage.setAlwaysOnTop(true)
    }
}

// Arka planda ölçüm (sınıflandırıcı + poligon üretimi yavaş olabilir).
def startMeasure = { String modelName, boolean wholeSlide = false ->
    // Önceki sonuç varsa İŞLEMDEN ÖNCE sor; İptal → hiçbir şey çalışmaz, hiçbir şey yazılmaz.
    String measureMode = askExisting()
    if (measureMode == null) return
    activeModel.set(modelName); activeWholeSlide.set(wholeSlide)
    step.set('MEASURING'); render()
    def worker = new Thread({
        def res = runMeasure(modelName, wholeSlide, asDetections.get(), createSummary.get(), measureMode)
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
        title.setText(workshopText('script.d1f4f351fb54', 'Tümör/Stroma sihirbazı'))
        bodyLbl.setText(
            workshopText('script.45ac9eed9911', 'Nasıl devam etmek istersiniz?\n\n' +
            '• Örnek sınıflandırıcı: hazır paketli modeli projeye kurar.\n' +
            '• Yeni sınıflandırıcı eğit: kendi Tumor/Stroma bölgelerinizden eğitir.'))
        buttons.getChildren().addAll(
            navButton(workshopText('script.72278748133a', 'İptal'), { stage.close() }),
            navButton(workshopText('script.3625440d0051', 'Örnek sınıflandırıcıyı kullan'), { startInstall() },
                workshopText('script.2561fa236d45', 'Hazır paketli modeli projeye kurar (gerekirse internetten indirir)'), 'CLOUD_DOWNLOAD'),
            navButton(workshopText('script.b9a82b67be11', 'Yeni sınıflandırıcı eğit ▶'), {
                step.set((s.he && s.calib) ? 'CLASSES' : 'PREREQ'); render()
            }, workshopText('script.f7331342a746', 'Kendi Tumor/Stroma bölgelerinizden yeni bir model eğitir'), 'FLASK'))
    } else if (cur == 'INSTALLING') {
        title.setText(workshopText('script.67df0b99ba1e', 'Örnek model kuruluyor…'))
        bodyLbl.setText(workshopText('script.f29fa7e76e5a', 'Paketli model projeye kaydediliyor (gerekirse indiriliyor). Lütfen bekleyin…'))
        content.getChildren().add(busyBar())
    } else if (cur == 'EXAMPLE_DONE') {
        String nm = exampleName.get()
        title.setText(workshopText('script.ac3384fe903e', 'Örnek model hazır — şimdi bölgeyi seçin'))
        bodyLbl.setText(
            (exampleExisted.get()
                ? "'${nm}${workshopText('script.964b8b849025', '\' zaten projenizde mevcut — kullanıma hazır.\n\n')}"
                : "${workshopText('script.09633f283982', 'Örnek sınıflandırıcı \'')}${nm}${workshopText('script.dbb9a7873c77', '\' projenize kuruldu.\n\n')}") +
            workshopText('script.bcb91bdf1bb0', 'Çalıştırmak için:\n') +
            workshopText('script.2ca748f15e3e', '  1. Lamda ölçmek istediğiniz dokuyu bir anotasyonla çizin (Brush/Polygon).\n') +
            workshopText('script.75d94fc464e5', '  2. Bu anotasyonu SEÇİN (üzerine tıklayın).\n') +
            workshopText('script.8fff31a5081f', '  3. Aşağıdan "Seçili bölgede ölç".\n\n') +
            workshopText('script.8a7a26626fd2', 'Seçim yapmazsanız sınıfı "Region" olan anotasyonlar kullanılır.\n') +
            workshopText('script.ea06252ba37d', 'İsteğe bağlı: boş cam/artefakt alanlarını çizip sınıfını "Ignore*" yapın — ölçümden çıkarılır.'))
        content.getChildren().addAll(detectionCheckBox(), summaryCheckBox())
        buttons.getChildren().addAll(
            navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('CHOICE'); render() }),
            navButton(workshopText('script.5f46f7a6e58f', 'Tüm lamda ölç'), { startMeasure(nm, true) },
                workshopText('script.d36539aaf9a8', 'Tüm lamda çalıştırır (seçim gerekmez; boş cam da sayılabilir — kaba tahmin)')),
            navButton(workshopText('script.2da07d33947d', 'Seçili bölgede ölç ▶'), { startMeasure(nm, false) },
                "${workshopText('script.dd2a8e61c518', 'Seçili anotasyon(lar)da \'')}${nm}${workshopText('script.043c398543ed', '\' modelini çalıştırır; seçim yoksa sınıfı \'Region\' olanlar')}"))
    } else if (cur == 'PREREQ') {
        title.setText(workshopText('script.fdde113b8e5d', 'Önce: görüntü tipi ve kalibrasyon'))
        bodyLbl.setText(
            workshopText('script.88a465797c00', "Eğitim için iki ön koşul gerekir:\n\n") +
            "  ${s.he ? '✓' : '✗'}${workshopText('script.31df379d337e', ' Görüntü tipi Brightfield (H&E) + boya vektörleri\n')}" +
            "  ${s.calib ? '✓' : '✗'}${workshopText('script.3422216beab9', ' Piksel kalibrasyonu (µm/px)\n\n')}" +
            workshopText('script.ee6240d9a4a1', "Eksik olanı aşağıdaki düğmelerle düzeltin, sonra 'Yenile'."))
        def fixType = navButton(workshopText('script.bbf6fba1e891', 'Görüntü tipini ayarla'), {
            if (stage != null) stage.setAlwaysOnTop(false)
            if (!launchBundled('yardimci-image-type.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Temel araçlar → Görüntü tipi ayarla')
        })
        def fixCal = navButton('Kalibrasyonu ayarla', {
            if (stage != null) stage.setAlwaysOnTop(false)
            if (!launchBundled('yardimci-kalibrasyon.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)')
        })
        content.getChildren().add(new javafx.scene.layout.HBox(8, fixType, fixCal))
        def cont = navButton(workshopText('script.09839547cef8', 'Devam ▶'), { step.set('CLASSES'); render() })
        cont.setDisable(!(s.he && s.calib))
        buttons.getChildren().addAll(
            navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('CHOICE'); render() }),
            navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }), cont)
    } else if (cur == 'CLASSES') {
        ensureClassesRegistered()
        // İlk girişte zaten çizilmiş isteğe bağlı sınıfları otomatik işaretle.
        if (tickedOptional.get() == null) {
            def init = new java.util.LinkedHashSet()
            OPTIONAL_CLASSES.each { if ((s.counts[it] ?: 0) > 0) init.add(it) }
            tickedOptional.set(init)
        }
        def ticked = tickedOptional.get()
        title.setText(workshopText('script.e0f62859b1fb', '1/2 — Eğitim sınıflarını çizin'))
        bodyLbl.setText(
            workshopText('script.1c91713d87b9', 'Annotations paneli → sınıfı seçin → Brush (B) ile birkaç temsilî bölge çizin.\n' +
            'Zorunlu sınıflar: Tumor, Stroma, Ignore* (boş cam/arka plan — modelin dokuyu\n' +
            'Tümör/Stroma\'ya zorlamasını engeller). İsteğe bağlı sınıfları işaretlerseniz\n' +
            'modele ayrı sınıf olarak katılır; işaretsizler eğitime girmez.\n' +
            'Küçük ve çeşitli örnekler çizin; geniş dolu alan örnek bütçesini boşa harcar.\n' +
            'Not: Model yalnız BU lamdan öğrenir; başka lamlara genellemesi sınırlıdır —\n' +
            'çoklu-slayt eğitim için Anotasyon Stratejisi eki §4b.'))
        def grid = new javafx.scene.layout.VBox(4)
        MANDATORY_CLASSES.each { nm ->
            int c = s.counts[nm] ?: 0
            def lbl = new javafx.scene.control.Label("${c > 0 ? '✓' : '✗'}  ${nm} — ${c} anotasyon (zorunlu)")
            if (c == 0) lbl.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.75;')
            grid.getChildren().add(lbl)
        }
        OPTIONAL_CLASSES.each { nm ->
            int c = s.counts[nm] ?: 0
            def cb = new javafx.scene.control.CheckBox("${nm} — ${c}${workshopText('script.41958301d078', ' anotasyon (isteğe bağlı)')}")
            cb.setSelected(ticked.contains(nm))
            cb.selectedProperty().addListener({ obs, o, n ->
                def set = tickedOptional.get(); if (n) set.add(nm) else set.remove(nm); tickedOptional.set(set)
            } as javafx.beans.value.ChangeListener)
            grid.getChildren().add(cb)
        }
        content.getChildren().add(grid)
        boolean ready = (s.counts['Tumor'] ?: 0) > 0 && (s.counts['Stroma'] ?: 0) > 0 && (s.counts['Ignore*'] ?: 0) > 0
        def cont = navButton(workshopText('script.3f0144008e16', 'İleri ▶'), {
            if (ready) { step.set('NAME'); render() }
            else Dialogs.showWarningNotification(workshopText('script.cf3c722ebe39', 'Eksik zorunlu sınıf'),
                workshopText('script.9d8dd7ea3422', 'Eğitim için Tumor, Stroma ve Ignore* sınıflarının her birinden en az bir alan\n' +
                'anotasyonu gerekir. Eksik olanı çizip "⟳ Yenile".'))
        }, workshopText('script.6a9421399bf3', 'Tumor + Stroma + Ignore* hazırsa adlandırma adımına geçer'))
        cont.setDisable(!ready)
        buttons.getChildren().addAll(
            navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('CHOICE'); render() }),
            navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }),
            cont)
    } else if (cur == 'NAME') {
        def ticked = tickedOptional.get() ?: (new java.util.LinkedHashSet())
        def chosen = MANDATORY_CLASSES + OPTIONAL_CLASSES.findAll { ticked.contains(it) }
        def dataLine = chosen.collect { "${it}=${s.counts[it] ?: 0}" }.join(', ')
        title.setText(workshopText('script.f6991b3a8bf2', '2/2 — Sınıflandırıcıya ad verin'))
        bodyLbl.setText(
            "${workshopText('script.c05fa9fca72d', 'Eğitim verisi (bu lam): ')}${dataLine}.\n" +
            workshopText('script.3234ecb0ac50', 'Modeli kaydetmek için bir ad girin, sonra "Eğit & kaydet".'))
        def nameField = new javafx.scene.control.TextField(atolyeS('atolye.classifierName', 'tumor-stroma-RF'))
        nameField.setPrefColumnCount(28)
        content.getChildren().add(new javafx.scene.layout.HBox(8,
            new javafx.scene.control.Label('Ad:'), nameField))
        buttons.getChildren().addAll(
            navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('CLASSES'); render() }),
            navButton(workshopText('script.ece53f594d06', 'Eğit & kaydet'), {
                String nm = nameField.getText()?.trim()
                if (!nm) { Dialogs.showWarningNotification('Ad gerekli', workshopText('script.2b79d4526eed', 'Bir sınıflandırıcı adı girin.')); return }
                if (s.classifiers.contains(nm)) {
                    if (stage != null) stage.setAlwaysOnTop(false)
                    boolean ow = Dialogs.showConfirmDialog(workshopText('script.d9f96ac310ff', 'Üzerine yaz?'), "'${nm}${workshopText('script.c6423379998e', '\' zaten var. Üzerine yazılsın mı?')}")
                    if (stage != null) stage.setAlwaysOnTop(true)
                    if (!ow) return
                }
                def selectedNames = new ArrayList(chosen)
                trainedName.set(nm)
                step.set('TRAINING'); render()
                def t0 = System.currentTimeMillis()
                def worker = new Thread({
                    def res = trainAndSave(nm, selectedNames)
                    double elapsed = (System.currentTimeMillis() - t0) / 1000.0
                    javafx.application.Platform.runLater {
                        if (res.ok) {
                            println String.format(java.util.Locale.US, 'Sihirbaz: model kaydedildi %s (%.1f sn)', nm, elapsed)
                            step.set('TRAIN_DONE'); render()
                        } else {
                            if (stage != null) stage.setAlwaysOnTop(false)
                            Dialogs.showErrorMessage(workshopText('script.4768938ec5c2', 'Eğitim başarısız'), res.error ?: workshopText('script.8d328b5b7c2a', '(bilinmeyen hata)'))
                            step.set('NAME'); render()
                        }
                    }
                }, 'AtolyeWizardTrain')
                worker.setDaemon(true); worker.start()
            }, workshopText('script.bfe1589730c2', 'Random Forest eğitir ve modeli projeye bu adla kaydeder')))
    } else if (cur == 'TRAINING') {
        title.setText(workshopText('script.426e9ee833e4', 'Eğitiliyor…'))
        bodyLbl.setText("'${trainedName.get()}${workshopText('script.e3c5d18add87', '\' eğitiliyor ve kaydediliyor. Lütfen bekleyin…')}")
        content.getChildren().add(busyBar())
    } else if (cur == 'TRAIN_DONE') {
        String nm = trainedName.get()
        title.setText(workshopText('script.ff56a682c4e1', 'Model kaydedildi ✅ — şimdi bölgeyi seçin'))
        bodyLbl.setText(
            "'${nm}' projeye kaydedildi.\n\n" +
            workshopText('script.bcb91bdf1bb0', 'Çalıştırmak için:\n') +
            workshopText('script.179c881b6fad', '  1. Ölçmek istediğiniz dokuyu bir anotasyonla çizin.\n') +
            workshopText('script.efcb82f4a317', '  2. Bu anotasyonu SEÇİN.\n') +
            workshopText('script.8fff31a5081f', '  3. Aşağıdan "Seçili bölgede ölç".\n\n') +
            workshopText('script.8a7a26626fd2', 'Seçim yapmazsanız sınıfı "Region" olan anotasyonlar kullanılır.\n') +
            workshopText('script.ea06252ba37d', 'İsteğe bağlı: boş cam/artefakt alanlarını çizip sınıfını "Ignore*" yapın — ölçümden çıkarılır.'))
        content.getChildren().addAll(detectionCheckBox(), summaryCheckBox())
        buttons.getChildren().addAll(
            navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }),
            navButton(workshopText('script.5f46f7a6e58f', 'Tüm lamda ölç'), { startMeasure(nm, true) },
                workshopText('script.d36539aaf9a8', 'Tüm lamda çalıştırır (seçim gerekmez; boş cam da sayılabilir — kaba tahmin)')),
            navButton(workshopText('script.2da07d33947d', 'Seçili bölgede ölç ▶'), { startMeasure(nm, false) },
                "${workshopText('script.dd2a8e61c518', 'Seçili anotasyon(lar)da \'')}${nm}${workshopText('script.043c398543ed', '\' modelini çalıştırır; seçim yoksa sınıfı \'Region\' olanlar')}"))
    } else if (cur == 'MEASURING') {
        title.setText(workshopText('script.486f28996d55', 'Ölçülüyor…'))
        bodyLbl.setText("'${activeModel.get()}${workshopText('script.26bf6298ad70', '\' ')}${activeWholeSlide.get() ? workshopText('script.6bab1dcdcff2', 'tüm lamda') : workshopText('script.32543e3db96a', 'seçili bölgede')}${workshopText('script.2ca967d68e8d', ' çalıştırılıyor (alanlar + poligonlar). Lütfen bekleyin…')}")
        content.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        def r = measureResult.get()
        if (r != null && r.ok) {
            title.setText(workshopText('script.138eddc3bf22', 'Ölçüm sonucu ✅'))
            bodyLbl.setText(r.text + workshopText('script.57675d2d057f', '\n\nİpucu: özet bölgeleri kapatıyorsa "TSR Özet" sınıfını Anotasyonlar\n') +
                workshopText('script.ad536fa4c26a', 'sekmesindeki göz simgesiyle gizleyin; sınıf bölgelerini görmek için anotasyon dolgusunu\n') +
                workshopText('script.b3579b55cfb3', 'Shift+F ile, tespitleri ise araç çubuğundaki "Show/hide detection objects" düğmesiyle açıp kapatın.'))
            content.getChildren().addAll(detectionCheckBox(), summaryCheckBox())
        } else if (r != null && r.error == 'NO_REGION') {
            title.setText(workshopText('script.20ce3b7f68dd', 'Önce bir bölge seçin'))
            bodyLbl.setText(workshopText('script.3e7519090ba9', 'Ölçmek için bir anotasyon çizip SEÇİN (ya da sınıfını "Region" yapın), sonra "↻ Tekrar ölç".'))
        } else {
            title.setText(workshopText('script.19182065eeb6', 'Ölçüm yapılamadı'))
            bodyLbl.setText((r?.error ?: workshopText('script.334b848286b9', 'Bilinmeyen hata.')).toString())
        }
        buttons.getChildren().addAll(
            navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }),
            navButton(workshopText('script.c1001d1cc7fd', '↻ Tekrar ölç'), { startMeasure(activeModel.get(), activeWholeSlide.get()) },
                workshopText('script.7e02830a99f6', 'Aynı kapsamda ölçümü tekrarlar (önce yeni bölge seçebilirsiniz)')))
    }

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(content)
    // Kalıcı sorumluluk reddi notu — tema-duyarlı (açık/koyu tema): sabit renk yok,
    // QuPath tema değişkeni + opaklık/italik ile sönük gösterilir.
    def disclaimer = new javafx.scene.control.Label(workshopText('script.04e70fc651a3', 'Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.'))
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
        stage.setTitle(workshopText('script.717932eb089e', 'Tümör/Stroma Sihirbazı'))
        stage.setAlwaysOnTop(true)
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.7fd583bc0c88', 'Sihirbaz açılamadı'), workshopText('script.9aac4912cb86', 'Bu araç başlatılamadı.\n\nYapabilecekleriniz:\n  1. Araç açık bir lam istiyorsa önce lamı açın.\n  2. QuPath\'i tamamen kapatıp yeniden açın ve tekrar deneyin.\n  3. Sorun sürerse View → Show log penceresinin ekran görüntüsünü atölye düzenleyicisine gönderin.\n\nTeknik ayrıntı: ') + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '(ileti yok)'))
    }
}

// Resolve extension UI language; standalone scripts keep their Turkish fallback.
private String workshopText(String key, String fallback) {
    try {
        def bundle = Class.forName('io.github.sbalci.qupath.workshop.WorkshopI18n')
        String translated = bundle.getMethod('t', String.class).invoke(null, key) as String
        return translated == key ? fallback : translated
    } catch (Throwable ignored) {
        return fallback
    }
}
