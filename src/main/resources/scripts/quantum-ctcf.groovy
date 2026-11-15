/**
 * QuANTUM cTCF (tek pencere sihirbazı)
 * -----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Yayınlanmış (L'Imperio ve ark., Virchows Archiv 2025) QuANTUM iş akışının
 * basitleştirilmiş, tek pencerede yönlendirilen bir uyarlamasıdır. H&E lamında
 * **cTCF** (Computational Tumor Cellular Fraction) hesaplar:
 *     cTCF = Tumor / (Tumor + Non-neoplastic) × 100   (Ignore paydadan dışlanır)
 *
 * SİHİRBAZ ADIMLARI (Tümör/Stroma ve Tümör içi Ki-67 modüllerindeki desen — tek pencere, adım adım):
 *   PREREQ          → görüntü · H&E · kalibrasyon · proje · StarDist · model
 *   DETECT_REGION   → patolog tarafından çizilmiş TCR anotasyonunu seç
 *   DETECTING       → StarDist TCR içindeki tüm çekirdekleri tespit eder
 *   TUMOR_EXAMPLES  → birkaç tümör hücresini "Tumor" sınıfıyla işaretle
 *   NONNEO_EXAMPLES → birkaç tümör-dışı hücreyi "Non-neoplastic" ile işaretle
 *   TRAIN_GUIDE     → QuPath'in Train Object Classifier penceresinde eğit + kaydet
 *   APPLYING        → sınıflandırıcı tüm tespit edilen hücrelere uygulanır
 *   RESULT          → cTCF + sayımlar; TCR anotasyonuna 5 ölçüm yazılır (Veri dışa aktarma modülü)
 *
 * ÖN KOŞULLAR:
 *   1. StarDist eklentisi yüklü (sihirbaz yoksa kuruluma yönlendirir)
 *   2. Model dosyası ~/.qupath/stardist/he_heavy_augment.pb
 *      (yoksa ilk tespitte github.com/qupath/models'ten otomatik indirilir)
 *   3. Sınıflandırıcı sihirbazın içinde, kendi olgunuzdan etkileşimli eğitilir
 *
 * Çıktılar yalnız sayım, alan, oran (cTCF) ve süredir.
 * Klinik kategori, NGS yeterlilik kararı veya yorum üretilmez.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
// Hücreleri değiştirme sorusu: varsayılan seçim "dokunma"dır — Enter/Tamam hiçbir şeyi değiştirmez.
// (Evet/Hayır penceresinde Enter "Evet" anlamına gelir; bu yüzden seçim penceresi kullanılır.)
def confirmReplaceCells = { String title, String body ->
    String keepOpt = workshopText('script.665d4074c4fe', 'Mevcut hücrelere dokunma — tespiti çalıştırma (varsayılan)')
    String replaceOpt = workshopText('script.6b4af35c53fe', 'Mevcut hücreleri değiştir — tespiti çalıştır')
    def ask = { -> qupath.fx.dialogs.Dialogs.showChoiceDialog(title, body, [keepOpt, replaceOpt], keepOpt) }
    if (javafx.application.Platform.isFxApplicationThread()) return ask() == replaceOpt
    def picked = new java.util.concurrent.atomic.AtomicReference(null)
    def latch = new java.util.concurrent.CountDownLatch(1)
    javafx.application.Platform.runLater {
        try { picked.set(ask()) } finally { latch.countDown() }
    }
    latch.await()
    return picked.get() == replaceOpt
}

// --- Atölye ayarları: eklenti yüklüyse oku, yoksa atölye varsayılanı kullanılır ---
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeD = { String k, double d -> (double) __wpCall('dbl', [String.class, double.class] as Class[], [k, d] as Object[], d) }

// ── Ön koşul düzeltme için paketli yardımcı betikleri çalıştırma ──
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
    }, "Modul8Wizard-${name}")
    runner.setDaemon(true); runner.start()
    return true
}
def menuHint = { String where ->
    Dialogs.showMessageDialog('Eklenti gerekli',
        "${workshopText('script.2da9535b4302', 'Bu adım atölye eklentisini gerektirir. Menüden çalıştırın:\n')}${where}")
}

// --- StarDist modeli: kamuya açık qupath/models'ten sabitlenmiş indirme ---
// Pin: qupath/models @ 60cc9dd (public, auth gerektirmez). İndirme sonrası
// SHA-256 + boyut doğrulanır; uyuşmazsa indirme reddedilir.
final String MODEL_URL    = "https://raw.githubusercontent.com/qupath/models/60cc9dd3406871fdd4bbbe8ad76a94e759bab7dd/stardist/he_heavy_augment.pb"
final String MODEL_SHA256 = "00033ae84b03ef82faec2ffad4dc7cd12666738ab70b339f35d3a6e5a5379bab"
final long   MODEL_BYTES  = 5722237L
def userHome  = System.getProperty('user.home')
def modelPath = userHome + '/.qupath/stardist/he_heavy_augment.pb'

// İndirme sırasında gösterilen basit (belirsiz) ilerleme penceresi.
def makeProgressStage = { String msg ->
    if (isHeadless) return null
    def ref = new java.util.concurrent.atomic.AtomicReference()
    def latch = new java.util.concurrent.CountDownLatch(1)
    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setAlwaysOnTop(true)
            stage.setTitle("StarDist modeli")
            def bar = new javafx.scene.control.ProgressBar()
            bar.setPrefWidth(300)
            bar.setProgress(javafx.scene.control.ProgressIndicator.INDETERMINATE_PROGRESS)
            def label = new javafx.scene.control.Label(msg)
            label.setWrapText(true)
            def box = new javafx.scene.layout.VBox(12, label, bar)
            box.setPadding(new javafx.geometry.Insets(16))
            stage.setScene(new javafx.scene.Scene(box, 380, 130))
            stage.show()
            ref.set(stage)
        } catch (Throwable t) {
            // FX kurulamadıysa pencere yok — indirme yine de devam eder
        } finally {
            latch.countDown()
        }
    }
    latch.await()
    return ref.get()
}

// Modeli temp dosyaya indir, boyut + SHA-256 doğrula, atomik taşı.
def downloadModel = { File target ->
    target.getParentFile().mkdirs()
    def tmp = new File(target.getParentFile(), target.getName() + ".part")
    if (tmp.exists()) tmp.delete()
    def client = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build()
    def req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(MODEL_URL))
            .timeout(java.time.Duration.ofMinutes(5))
            .GET().build()
    def resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
    if (resp.statusCode() != 200) {
        tmp.delete(); throw new IOException("HTTP ${resp.statusCode()}")
    }
    if (tmp.length() != MODEL_BYTES) {
        tmp.delete(); throw new IOException("Boyut uyuşmuyor: ${tmp.length()} != ${MODEL_BYTES}")
    }
    def md = java.security.MessageDigest.getInstance("SHA-256")
    tmp.withInputStream { is ->
        byte[] buf = new byte[65536]; int n
        while ((n = is.read(buf)) > 0) md.update(buf, 0, n)
    }
    def hex = md.digest().collect { String.format(java.util.Locale.US, "%02x", it & 0xFF) }.join()
    if (hex != MODEL_SHA256) {
        tmp.delete(); throw new IOException("SHA-256 uyuşmuyor")
    }
    java.nio.file.Files.move(tmp.toPath(), target.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
}

// StarDist eklentisi sınıf yolunda mı?
def stardistAvailable = { ->
    try { Class.forName('qupath.ext.stardist.StarDist2D'); return true }
    catch (Throwable t) { return false }
}

// ──────────────────────────────────────────────────────────────
// Durum + sihirbaz yardımcı kapanışları
// ──────────────────────────────────────────────────────────────
def computeState = { ->
    def st = [image:false, project:false, heType:false, calib:false, stardist:false, model:false,
              selectedArea:0, tumor:0, nonNeo:0, ignore:0, classifiers:[]]
    def imageData = QP.getCurrentImageData()
    st.image = imageData != null
    def project = QP.getProject()
    st.project = project != null
    st.stardist = stardistAvailable()
    st.model = new File(modelPath).exists()
    if (imageData != null) {
        def typeName = (imageData.getImageType()?.toString() ?: '').toUpperCase(java.util.Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
        st.heType = typeName.contains('BRIGHTFIELD_H_E')
        def cal = imageData.getServer()?.getPixelCalibration()
        st.calib = cal != null && cal.getAveragedPixelSizeMicrons() > 0
        QP.getAnnotationObjects().each {
            if (it.getROI()?.isArea()) {
                def n = it.getPathClass()?.getName()
                if (n == 'Tumor') st.tumor++
                else if (n == 'Non-neoplastic') st.nonNeo++
                else if (n == 'Ignore') st.ignore++
            }
        }
        st.selectedArea = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI()?.isArea() }.size()
    }
    if (project != null) st.classifiers = new ArrayList(project.getObjectClassifiers().getNames())
    return st
}

// Başsız kipte soru sorulamaz: açık bağımsız değişken gerekir (ör. -a mevcut=degistir).
def headlessArg = { String key ->
    def list = []
    try { if (binding.hasVariable('args')) { def a = binding.getVariable('args'); if (a != null) list = (a as List) } } catch (Throwable ignore) {}
    def found = null
    list.each { a -> def s = a?.toString() ?: ''; int i = s.indexOf('='); if (i > 0 && s.substring(0, i).trim() == key) found = s.substring(i + 1).trim().toLowerCase(java.util.Locale.ROOT) }
    return found
}
// Tespit, hedef TCR'nin içindeki mevcut hücre tespitlerinin YERİNİ alır: yalnız bunlar sayılır.
def redetectCount = { regionAnno ->
    regionAnno == null ? 0 : regionAnno.getChildObjects().findAll { it.isDetection() }.size()
}
String redetectReplacedFmt = workshopText('script.784fe373ea77', 'Önceki %,d hücre değiştirildi.')

// DETECT — TCR içinde StarDist çekirdek tespiti (StarDist yoksa Watershed yedeği).
//   Dönüş: [ok:true, count:n] | [ok:false, error:…]
def runStarDist = { regionAnno ->
    try {
        def imageData = QP.getCurrentImageData()
        if (imageData == null) return [ok:false, error:'Görüntü açık değil.']

        // Önceki tespitleri temizle (yeniden çalıştırılabilirlik).
        // Çağıranlar bu noktadan ÖNCE sordu (GUI: redetectConfirm, başsız: mevcut=degistir); buradaki silme onaylı yerine koymadır.
        def oldDet = regionAnno.getChildObjects().findAll { it.isDetection() }
        int replacedCount = oldDet.size()
        if (!oldDet.isEmpty()) regionAnno.removePathObjects(oldDet)

        long t0 = System.currentTimeMillis()
        if (stardistAvailable()) {
            // Model çöz — yoksa otomatik indir.
            def mf = new File(modelPath)
            if (!mf.exists()) {
                def progress = makeProgressStage('StarDist modeli indiriliyor…\n(he_heavy_augment.pb, ~5.7 MB)')
                boolean dok = false
                try { downloadModel(mf); dok = true; println '  ✓ Model indirildi ve doğrulandı.' }
                catch (Throwable t) { println "  ✗ Otomatik indirme başarısız: ${t.message}" }
                finally { if (progress != null) javafx.application.Platform.runLater { progress.close() } }
                if (!dok) return [ok:false, error:
                    'StarDist modeli bulunamadı ve indirilemedi.\n' +
                    "Beklenen yol:\n  ${modelPath}\n\n" +
                    'İnternet bağlıyken tekrar deneyin ya da he_heavy_augment.pb dosyasını\n' +
                    'bu konuma elle kopyalayın (kaynaklar.qmd § ileri kurulumlar).']
            }
            def stardist = qupath.ext.stardist.StarDist2D
                    .builder(modelPath)
                    .threshold(atolyeD('atolye.stardistThreshold', 0.5))
                    // Global (tüm-görüntü) normalizasyon — karo-karo normalizasyonun arka planda
                    // ürettiği "hayalet çekirdek"leri önler (resmî StarDist dokümanının uyardığı pitfall).
                    .preprocess(
                        qupath.ext.stardist.StarDist2D.imageNormalizationBuilder()
                            .maxDimension(4096)
                            .percentiles(0.2, 99.8)
                            .build()
                    )
                    .pixelSize(atolyeD('atolye.stardistPixelSize', 0.5))
                    .cellExpansion(atolyeD('atolye.stardistCellExpansion', 5.0))
                    .constrainToParent(true)
                    .measureShape()
                    .measureIntensity()
                    .build()
            try {
                QP.selectObjects(regionAnno)
                stardist.detectObjects(imageData, [regionAnno])
            } finally {
                stardist.close()
            }
        } else {
            println '⚠ StarDist eklentisi yüklü değil → klasik WatershedCellDetection yedeği kullanılıyor.'
            if (!isHeadless) javafx.application.Platform.runLater { Dialogs.showWarningNotification(workshopText('script.186dd2b709e8', 'StarDist yüklü değil'), workshopText('script.cc3d27e2d676', 'StarDist eklentisi yüklü olmadığı için klasik Watershed hücre tespiti kullanıldı; sonuçlar StarDist ile aynı olmaz. StarDist\'i kurmak için Extensions → Atölye → Ortam kontrolü… penceresine ve kaynaklar sayfasındaki ileri kurulumlar bölümüne bakın, sonra aracı yeniden çalıştırın.')) }
            QP.selectObjects(regionAnno)
            QP.runPlugin(
                'qupath.imagej.detect.cells.WatershedCellDetection',
                '{' +
                    '"detectionImageBrightfield":"Hematoxylin OD",' +
                    '"requestedPixelSizeMicrons":' + atolyeD('atolye.pixelSize', 0.5) + ',' +
                    '"backgroundRadiusMicrons":' + atolyeD('atolye.backgroundRadius', 8.0) + ',' +
                    '"medianRadiusMicrons":' + atolyeD('atolye.medianRadius', 0.0) + ',' +
                    '"sigmaMicrons":' + atolyeD('atolye.sigma', 1.5) + ',' +
                    '"minAreaMicrons":' + atolyeD('atolye.minArea', 10.0) + ',' +
                    '"maxAreaMicrons":' + atolyeD('atolye.maxArea', 400.0) + ',' +
                    '"threshold":' + atolyeD('atolye.detectionThreshold', 0.1) + ',' +
                    '"watershedPostProcess":true,' +
                    '"cellExpansionMicrons":' + atolyeD('atolye.stardistCellExpansion', 5.0) + ',' +
                    '"includeNuclei":true,' +
                    '"smoothBoundaries":true,' +
                    '"makeMeasurements":true' +
                '}'
            )
        }
        double elapsed = (System.currentTimeMillis() - t0) / 1000.0

        // Pürüzsüzleştirilmiş (komşuluk) özellikler — sınıflandırıcıya ek sinyal. Başarısızsa atlanır.
        try {
            QP.selectObjects(regionAnno)
            QP.runPlugin('qupath.lib.plugins.objects.SmoothFeaturesPlugin',
                '{"fwhmMicrons":25.0,"smoothWithinClasses":false}')
        } catch (Throwable ignored) {
            println 'QuANTUM modülü: özellik pürüzsüzleştirme atlandı.'
        }

        def cells = regionAnno.getChildObjects().findAll { it.isDetection() }
        if (cells.isEmpty())
            return [ok:false, error:'TCR içinde çekirdek tespit edilmedi. Eşik veya boya vektörü ayarlarını kontrol edin.']

        regionAnno.setName('TCR')
        def viewer = qupath.lib.gui.QuPathGUI.getInstance()?.getViewer()
        if (viewer != null) javafx.application.Platform.runLater { viewer.repaintEntireImage() }
        QP.fireHierarchyUpdate()
        println String.format(java.util.Locale.US, 'QuANTUM modülü StarDist: %,d çekirdek (%.1f sn)', cells.size(), elapsed)
        return [ok:true, count:cells.size(), replaced:replacedCount]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
    }
}

// APPLY — kayıtlı nesne sınıflandırıcıyı TCR'nin hücrelerine uygula + say.
//   Katı eşleme: yalnız "Tumor" paya, yalnız "Non-neoplastic" (varyantları) paydaya;
//   diğer her şey (Stroma/Other/sınıfsız) → Ignore (cTCF paydasından dışlanır). Geri çevirmeyin.
def applyClassifier = { regionAnno, String name ->
    def cells = regionAnno.getChildObjects().findAll { it.isDetection() }
    if (cells.isEmpty()) return [ok:false, error:'Uygulanacak tespit yok.']
    try {
        QP.selectObjects(cells)
        QP.runObjectClassifier(name)
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
    } finally {
        QP.fireHierarchyUpdate()
        QP.resetSelection()   // sihirbaz sonrası hücreler seçili kalmasın
    }
    int tumor = 0, nonNeo = 0, ignore = 0
    cells.each { c ->
        def pc = c.getPathClass()
        def baseCls = pc?.getBaseClass()?.getName()?.toLowerCase(java.util.Locale.ROOT) ?: ''
        def cls = pc?.getName()?.toLowerCase(java.util.Locale.ROOT) ?: ''
        if ((baseCls == 'tumor' || cls == 'tumor') && !cls.contains('non')) tumor++
        else if (cls.contains('non-neoplastic') || cls.contains('non neoplastic') || cls.contains('nonneoplastic')) nonNeo++
        else ignore++
    }
    println String.format(java.util.Locale.US, 'QuANTUM modülü sınıflandırma: Tumor=%,d Non-neoplastic=%,d Ignore=%,d', tumor, nonNeo, ignore)
    return [ok:true, tumorCount:tumor, nonNeoCount:nonNeo, ignoreCount:ignore]
}

// RESULT — cTCF hesapla, TCR ölçüm listesine yaz (Veri dışa aktarma modülü dışa aktarır), metin üret.
def computeResult = { regionAnno, int tumorCount, int nonNeoCount, int ignoreCount ->
    double cTCF = Double.NaN
    String cTCFtext
    int validTotal = tumorCount + nonNeoCount   // Ignore'u dışla
    if (validTotal > 0) {
        cTCF = 100.0 * tumorCount / validTotal
        cTCFtext = String.format(java.util.Locale.US, '%.1f%%', cTCF)
    } else {
        cTCFtext = 'Tumor/Non-neoplastic hücre yok — cTCF hesaplanamadı'
    }

    double tcrAreaMm2 = 0.0
    try {
        def cal = QP.getCurrentImageData().getServer().getPixelCalibration()
        double pw = cal.getPixelWidthMicrons(), ph = cal.getPixelHeightMicrons()
        def roi = regionAnno?.getROI()
        if (roi != null && pw > 0 && ph > 0) tcrAreaMm2 = roi.getArea() * pw * ph / 1_000_000.0
    } catch (Throwable ignored) { }

    int totalNuclei = tumorCount + nonNeoCount + ignoreCount

    // Ölçümleri TCR anotasyonuna yaz → Veri dışa aktarma modülü TSV'sinde görünür.
    regionAnno.measurements['TCR alanı (mm2)'] = tcrAreaMm2
    if (!Double.isNaN(cTCF)) regionAnno.measurements['cTCF (%)'] = cTCF
    else regionAnno.measurements.remove('cTCF (%)')   // hesaplanamadıysa önceki çalıştırmanın değeri kalmasın
    regionAnno.measurements['Tümör hücre sayısı']  = tumorCount as double
    regionAnno.measurements['Non-neoplastik sayı'] = nonNeoCount as double
    regionAnno.measurements['Ignore / sınıflandırılmamış sayı'] = ignoreCount as double
    QP.fireHierarchyUpdate()

    def body = new StringBuilder()
    body << 'QuANTUM cTCF — Sonuç\n'
    body << '════════════════════════════\n\n'
    body << String.format(java.util.Locale.US, 'TCR alanı              : %.2f mm²%n', tcrAreaMm2)
    body << String.format(java.util.Locale.US, 'Toplam nükleus          : %,d%n', totalNuclei)
    body << String.format(java.util.Locale.US, 'Tumor sınıfı            : %,d%n', tumorCount)
    body << String.format(java.util.Locale.US, 'Non-neoplastic sınıfı   : %,d%n', nonNeoCount)
    body << String.format(java.util.Locale.US, 'Ignore                  : %,d%n', ignoreCount)
    body << '\n'
    body << "🧬 ANA SONUÇ: cTCF = ${cTCFtext}\n"
    body << '════════════════════════════\n\n'
    body << 'cTCF = Tumor ÷ (Tumor + Non-neoplastic) × 100; Ignore paydadan dışlanır.\n\n'
    body << "Yöntem bağlamı (L'Imperio ve ark., Virchows Archiv 2025): özgün çalışmada 121\n"
    body << 'NSCLC olgusunda manuel TCF %52±19 iken cTCF %30±10 ölçülmüş. Bu sayılar yöntemin\n'
    body << 'bağlamıdır; sizin lamınıza/laboratuvarınıza taşınmaz (yerel validasyon gerekir).\n\n'
    body << 'Bu çıktı betimsel bir ölçümdür; yeterlilik, tedavi ya da başka bir klinik karar üretmez.\n'
    body << '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'
    return [cTCF:cTCF, text:body.toString()]
}

// ──────────────────────────────────────────────────────────────
// Headless: seçili TCR üzerinde tespit (+ varsa sınıflandırıcı) → yazdır.
// ──────────────────────────────────────────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    def sel = QP.getSelectedObject()
    if (imageData == null || !(sel instanceof PathAnnotationObject)) {
        println 'QuANTUM modülü (headless): bir görüntü ve seçili TCR anotasyonu gerekir.'; return
    }
    String mevcut = headlessArg('mevcut')
    if (mevcut != null && mevcut != 'degistir') {
        println String.format(java.util.Locale.US, workshopText('script.5383f68a2a7d', 'HATA: mevcut yalnız \'degistir\' olabilir (verilen: %s). Hiçbir şey yazılmadı.'), mevcut)
        return
    }
    int redetectPrev = redetectCount(sel)
    if (redetectPrev > 0 && mevcut != 'degistir') {
        println String.format(java.util.Locale.US, workshopText('script.2c044d4d8901', 'Seçili TCR anotasyonunda zaten %,d hücre tespiti var. Hiçbir şey yazılmadı. Bu hücreleri değiştirmek için -a mevcut=degistir ile yeniden çalıştırın.'), redetectPrev)
        return
    }
    def d = runStarDist(sel)
    if (!d.ok) { println 'QuANTUM modülü (headless) hata: ' + d.error; return }
    if ((d.replaced ?: 0) > 0) println String.format(java.util.Locale.US, redetectReplacedFmt, d.replaced as int)
    def clf = 'he-tumor-nonneo'
    def names = QP.getProject()?.getObjectClassifiers()?.getNames() ?: []
    if (names.contains(clf)) {
        def a = applyClassifier(sel, clf)
        if (a.ok) println computeResult(sel, a.tumorCount, a.nonNeoCount, a.ignoreCount).text
    } else {
        println String.format(java.util.Locale.US,
            'QuANTUM modülü (headless): %,d çekirdek tespit edildi; "%s" sınıflandırıcısı yok, cTCF atlandı.', d.count, clf)
    }
    return
}

// ──────────────────────────────────────────────────────────────
// Tek pencere, adım adım render (Tümör/Stroma ve Tümör içi Ki-67 modüllerindeki sihirbaz deseni)
// ──────────────────────────────────────────────────────────────
def stage = null   // YALNIZ FX iş parçacığında oluşturulur (aşağıda Platform.runLater)

// PREREQ | DETECT_REGION | DETECTING | TUMOR_EXAMPLES | NONNEO_EXAMPLES | TRAIN_GUIDE | APPLYING | RESULT
def step = new java.util.concurrent.atomic.AtomicReference('PREREQ')
def summaryAnnotation = new java.util.concurrent.atomic.AtomicReference(null)
def savedClassifierName = new java.util.concurrent.atomic.AtomicReference(null)
def detectResult = new java.util.concurrent.atomic.AtomicReference(null)
def applyResultRef = new java.util.concurrent.atomic.AtomicReference(null)
def render  // forward declaration

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
def busyBar = { ->
    def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb
}

// Mevcut hücre varsa tespitten ÖNCE sor (FX iş parçacığında; Hayır → hiçbir şey çalışmaz, hiçbir şey yazılmaz).
def redetectConfirm = { regionAnno ->
    int n = redetectCount(regionAnno)
    if (n == 0) return true
    String ttl = workshopText('script.c8cded1ce05d', 'Önceki hücre tespitleri değiştirilecek')
    if (stage != null) stage.setAlwaysOnTop(false)
    boolean ok = false
    try { ok = confirmReplaceCells(ttl, String.format(java.util.Locale.US, workshopText('script.e2b40df41cb4', 'Seçili TCR anotasyonunda zaten %,d hücre var. Tespit bu hücrelerin yerini alacak; elle verdiğiniz sınıflar ve düzeltmeler de gider.\n\nDevam edilsin mi?\n\n"Hayır" derseniz hiçbir şey çalıştırılmaz ve hiçbir şey değişmez.'), n)) }
    finally { if (stage != null) stage.setAlwaysOnTop(true) }
    if (!ok) Dialogs.showInfoNotification(ttl, workshopText('script.0187c58bc6df', 'İptal edildi — hiçbir şey çalıştırılmadı, hiçbir şey değişmedi.'))
    return ok
}

// Arka plan: StarDist tespiti (DETECTING).
def startStarDist = { regionAnno ->
    summaryAnnotation.set(regionAnno)
    step.set('DETECTING'); render()
    def worker = new Thread({
        def d = runStarDist(regionAnno)
        javafx.application.Platform.runLater {
            detectResult.set(d)
            if (d.ok) { step.set('TUMOR_EXAMPLES'); render() }
            else {
                step.set('DETECT_REGION'); render()
                if (stage != null) stage.setAlwaysOnTop(false)
                Dialogs.showErrorMessage(workshopText('script.8ed52a76322b', 'StarDist başarısız'), d.error ?: '')
                if (stage != null) stage.setAlwaysOnTop(true)
            }
        }
    }, 'Modul8StarDist'); worker.setDaemon(true); worker.start()
}

// Arka plan: nesne sınıflandırıcı uygula + cTCF hesapla (APPLYING → RESULT).
def startApply = { String name ->
    savedClassifierName.set(name)
    step.set('APPLYING'); render()
    def worker = new Thread({
        def tcr = summaryAnnotation.get()
        def a = applyClassifier(tcr, name)
        def res = a.ok ? computeResult(tcr, a.tumorCount, a.nonNeoCount, a.ignoreCount) : null
        javafx.application.Platform.runLater {
            applyResultRef.set([apply:a, result:res])
            if (a.ok) { step.set('RESULT'); render() }
            else {
                step.set('TRAIN_GUIDE'); render()
                if (stage != null) stage.setAlwaysOnTop(false)
                Dialogs.showErrorMessage(workshopText('script.face316a825e', 'Sınıflandırıcı uygulanamadı'), a.error ?: '')
                if (stage != null) stage.setAlwaysOnTop(true)
            }
        }
    }, 'Modul8Apply'); worker.setDaemon(true); worker.start()
}

// ── Menü vurgusu (Atölye #3) — üst menü çubuğunda bir üst-menü başlığını turuncu iç
// parıltıyla işaretler (başlık bulunamazsa tüm menü çubuğunu). SALT-GÖRSEL: menüyü
// AÇMAZ, hiçbir şeyi tıklamaz/değiştirmez (sihirbazların salt okunur sözleşmesi). Tüm
// setEffect çağrıları FX iş parçacığında; render başında ve pencere kapanınca temizlenir.
def menuHiRef = new java.util.concurrent.atomic.AtomicReference(null)   // [node, origEffect] | null
def clearMenuHighlight = { ->
    javafx.application.Platform.runLater {
        def cur = menuHiRef.getAndSet(null)
        if (cur != null) { try { ((javafx.scene.Node) cur[0]).setEffect((javafx.scene.effect.Effect) cur[1]) } catch (Throwable t) {} }
    }
}
def applyMenuHighlight = { String menuName ->
    def g = qupath.lib.gui.QuPathGUI.getInstance()
    if (g == null) return
    javafx.application.Platform.runLater {
        try {
            // önce önceki vurguyu ATOMİK geri yükle (hızlı toggle'da takılı parıltı kalmasın)
            def prev = menuHiRef.getAndSet(null)
            if (prev != null) { try { ((javafx.scene.Node) prev[0]).setEffect((javafx.scene.effect.Effect) prev[1]) } catch (Throwable t) {} }
            def mb = g.getMenuBar()
            if (mb == null) return
            javafx.scene.Node target = null
            def want = menuName?.toLowerCase(java.util.Locale.ROOT)
            if (want != null) {
                // Yalnız üst-menü başlıkları (.menu-button); '.menu' alt menülere de inip yanlış
                // düğümü işaretleyebildiğinden kullanılmaz. Bulunamazsa tüm menü çubuğuna düşülür.
                for (n in mb.lookupAll('.menu-button')) {
                    try {
                        def txt = (n instanceof javafx.scene.control.Labeled) ? ((javafx.scene.control.Labeled) n).getText() : null
                        if (txt != null && txt.toLowerCase(java.util.Locale.ROOT).contains(want)) { target = n; break }
                    } catch (Throwable t) {}
                }
            }
            boolean spot = (target != null)
            if (target == null) target = mb
            def orig = target.getEffect()
            menuHiRef.set([target, orig])
            def glow = new javafx.scene.effect.InnerShadow()
            glow.setColor(javafx.scene.paint.Color.web('#FF7A00'))   // turuncu (tur sihirbazıyla aynı)
            glow.setRadius(spot ? 8.0d : 12.0d)
            glow.setChoke(spot ? 0.85d : 0.5d)
            target.setEffect(glow)
        } catch (Throwable t) { menuHiRef.set(null) }
    }
}
def menuHighlightToggle = { String menuName ->
    def tb = new javafx.scene.control.ToggleButton(menuName + workshopText('script.ef07407189c3', ' menüsünü göster'))
    tb.setTooltip(new javafx.scene.control.Tooltip(
        workshopText('script.242839f75398', 'Üst menü çubuğunda "') + menuName + workshopText('script.f4d336f42a58', '" menüsünü turuncu çerçeveyle işaretler (menüyü açmaz, hiçbir şeyi değiştirmez).')))
    tb.setOnAction({ if (tb.isSelected()) applyMenuHighlight(menuName) else clearMenuHighlight() })
    return tb
}

render = { ->
    if (stage != null) stage.setAlwaysOnTop(true)
    clearMenuHighlight()   // #3: adım değişiminde/yenilemede takılı menü vurgusunu kaldır
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
    if (cur == 'PREREQ') {
        title.setText(workshopText('script.8bfbd036c199', 'Önce: görüntü ve eklenti hazırlığı'))
        bodyLbl.setText(
            workshopText('script.b6e5799b14e1', 'QuANTUM cTCF iş akışının ön koşulları:\n\n') +
            "  ${s.image ? '✓' : '✗'}${workshopText('script.c00010ebce02', ' Açık görüntü\n')}" +
            "  ${s.heType ? '✓' : '✗'}${workshopText('script.5b9dcb71c2a7', ' Görüntü tipi Brightfield (H&E)\n')}" +
            "  ${s.calib ? '✓' : '✗'}${workshopText('script.d93396f804f2', ' Piksel kalibrasyonu (µm/px)\n')}" +
            "  ${s.project ? '✓' : '✗'}${workshopText('script.023b402ca7ac', ' Açık proje (sınıflandırıcı kaydı için)\n')}" +
            "  ${s.stardist ? '✓' : '⚠'} StarDist eklentisi " + (s.stardist ? workshopText('script.5543213e9c58', '(hazır)') : workshopText('script.bc86f45f1ab0', '(kurulu değil — klasik Watershed yedeği kullanılacak)')) + '\n' +
            "  ${s.model ? '✓' : '⏳'} StarDist modeli " + (s.model ? workshopText('script.5543213e9c58', '(hazır)') : '(StarDist varsa ilk tespitte indirilecek, ~5.7 MB)') + '\n' +
            workshopText('script.ff94ab283b7b', "\nEksikleri düzeltip 'Yenile'."))
        def fixType = navButton(workshopText('script.bbf6fba1e891', 'Görüntü tipini ayarla'), {
            if (stage != null) stage.setAlwaysOnTop(false)
            if (!launchBundled('yardimci-image-type.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Temel araçlar → Görüntü tipi ayarla')
        })
        def fixCal = navButton('Kalibrasyonu ayarla', {
            if (stage != null) stage.setAlwaysOnTop(false)
            if (!launchBundled('yardimci-kalibrasyon.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)')
        })
        content.getChildren().add(new javafx.scene.layout.HBox(8, fixType, fixCal))
        boolean ready = s.image && s.heType && s.calib && s.project
        def cont = navButton(workshopText('script.09839547cef8', 'Devam ▶'), { step.set('DETECT_REGION'); render() })
        cont.setDisable(!ready)
        buttons.getChildren().addAll(navButton(workshopText('script.72278748133a', 'İptal'), { stage.close() }), navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }), cont)
    } else if (cur == 'DETECT_REGION') {
        title.setText(workshopText('script.dd5de281527b', '1/5 — TCR anotasyonunu seçin'))
        bodyLbl.setText(
            workshopText('script.7c5d577ae1ea', 'TCR (Tumor-Containing Region), patoloğun çizdiği araştırma alanıdır — saf tümör konturu\n' +
            'DEĞİL, içinde tümör bulunan geniş bir bölge. [P] (Polygon) ya da Brush ile çizip SEÇİN.\n' +
            'Sınıf atamanıza gerek yok; bu yalnız çekirdek tespitinin sınırıdır.\n\n') +
            "${workshopText('script.fdefe60273a1', 'Şu an seçili alan anotasyonu: ')}${s.selectedArea}")
        buttons.getChildren().addAll(
            navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('PREREQ'); render() }),
            navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }),
            navButton(workshopText('script.a6ba34fc5c67', 'Tespit et ▶'), {
                def sel = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI()?.isArea() }
                if (sel.isEmpty()) { Dialogs.showWarningNotification(workshopText('script.e894b6d0ac6e', 'Bölge seçili değil'), workshopText('script.bacd63d7fb6d', 'Önce bir alan (TCR) anotasyonu çizip SEÇİN.')); return }
                if (!redetectConfirm(sel[0])) return
                startStarDist(sel[0])
            }, workshopText('script.c08db097c1e1', 'Seçili TCR içinde StarDist çekirdek tespiti')))
    } else if (cur == 'DETECTING') {
        title.setText(workshopText('script.0d1fe9b7ba82', 'StarDist tespiti çalışıyor…'))
        bodyLbl.setText(workshopText('script.bce358ad25ae', 'TCR içindeki çekirdekler tespit ediliyor ve pürüzsüzleştirilmiş özellikler ekleniyor.\n' +
            'Model ilk kez indiriliyorsa biraz sürebilir. Lütfen bekleyin…'))
        content.getChildren().add(busyBar())
    } else if (cur == 'TUMOR_EXAMPLES') {
        def d = detectResult.get()
        title.setText(workshopText('script.c7d32438535f', '2/5 — Tümör hücresi örnekleri'))
        bodyLbl.setText(
            ((d?.ok) ? "${workshopText('script.f089332eb95f', 'Tespit edilen çekirdek: ')}${d.count}\n\n" : '') +
            ((d?.ok && (d.replaced ?: 0) > 0) ? String.format(java.util.Locale.US, redetectReplacedFmt, d.replaced as int) + '\n\n' : '') +
            workshopText('script.9b12e56cc20f', 'Birkaç TİPİK TÜMÖR HÜCRESİ bölgesini küçük anotasyonlarla çizin ve sınıfını tam olarak\n') +
            workshopText('script.58c36a1e98d7', '"Tumor" yapın (Annotations sekmesinde sınıfı seçip "Set selected" ya da sağ tık → Set classification).\n') +
            workshopText('script.7bec4bc81f61', 'Tümör epiteline ait çekirdekleri hedefleyin; lenfosit ve stromadan uzak durun. Bitince İleri.\n\n') +
            "${workshopText('script.8dbd07801613', 'Şu an Tumor anotasyonu: ')}${s.tumor}")
        buttons.getChildren().addAll(
            navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('DETECT_REGION'); render() }),
            navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }),
            navButton(workshopText('script.3f0144008e16', 'İleri ▶'), {
                if (s.tumor > 0) { step.set('NONNEO_EXAMPLES'); render() }
                else Dialogs.showWarningNotification(workshopText('script.1bc051e5f818', 'Tumor örneği yok'), workshopText('script.1cd820930a4a', 'Sınıfı "Tumor" olan en az bir alan anotasyonu çizin, sonra İleri.'))
            }))
    } else if (cur == 'NONNEO_EXAMPLES') {
        title.setText(workshopText('script.5530fd449f6b', '3/5 — Non-neoplastic hücre örnekleri'))
        bodyLbl.setText(
            workshopText('script.bff07ecb51f0', 'Şimdi tümör DIŞI hücrelerin (lenfosit, fibroblast, endotel, sıkışmış benign parankimal epitel) bulunduğu\n' +
            'bölgelerden birkaç örnek çizin ve sınıfını TAM OLARAK "Non-neoplastic" yapın\n' +
            '(büyük N, tireli, küçük n — yanlış yazım sessizce Ignore sayılır).\n' +
            'İsteğe bağlı: artefakt/belirsiz hücreleri "Ignore" sınıfıyla işaretleyebilirsiniz.\n' +
            'Bu ayrım, Non-neoplastic hücreleri cTCF paydasına dahil etmek için kritiktir. Bitince İleri.\n\n') +
            "${workshopText('script.4ec640f9054e', 'Şu an Non-neoplastic: ')}${s.nonNeo}   |   Ignore: ${s.ignore}")
        buttons.getChildren().addAll(
            navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('TUMOR_EXAMPLES'); render() }),
            navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }),
            navButton(workshopText('script.3f0144008e16', 'İleri ▶'), {
                if (s.nonNeo > 0) { step.set('TRAIN_GUIDE'); render() }
                else Dialogs.showWarningNotification(workshopText('script.79a8750a224d', 'Non-neoplastic örneği yok'), workshopText('script.58d3f62597cb', 'Sınıfı "Non-neoplastic" olan en az bir alan anotasyonu çizin, sonra İleri.'))
            }))
    } else if (cur == 'TRAIN_GUIDE') {
        title.setText(workshopText('script.292c4d20e93e', "4/5 — Sınıflandırıcıyı QuPath'te eğitin"))
        def instr = new javafx.scene.control.TextArea(
            '1. Üst menü:  Classify → Object classification → Train object classifier\n' +
            '2. Sınıflandırıcı türü: Random Trees (varsayılan)\n' +
            '3. "Live update" kutusunu işaretleyin — hücreler Tumor / Non-neoplastic / Ignore\n' +
            '   renklerine boyanmalı\n' +
            '4. Sınıflandırma kararlı görünene dek her sınıftan örnek eklemeye devam edin\n' +
            '5. Sınıf adları tam şöyle olmalı: Tumor · Non-neoplastic · Ignore\n' +
            '6. Bir AD girip "Save" ile kaydedin (ör. he-tumor-nonneo)\n' +
            '7. Kaydettiğiniz adı aşağıya yazıp "Kaydedildi ▶"')
        instr.setEditable(false); instr.setWrapText(true); instr.setPrefRowCount(8)
        def nameField = new javafx.scene.control.TextField('he-tumor-nonneo'); nameField.setPrefColumnCount(24)
        content.getChildren().addAll(instr, new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('Kaydedilen ad:'), nameField))
        content.getChildren().add(menuHighlightToggle('Classify'))   // #3: Classify menüsünü göster
        buttons.getChildren().addAll(
            navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('NONNEO_EXAMPLES'); render() }),
            navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }),
            navButton('Kaydedildi ▶', {
                String nm = nameField.getText()?.trim()
                if (!nm) { Dialogs.showWarningNotification('Ad gerekli', workshopText('script.6c7b6f3bc12d', 'Kaydettiğiniz sınıflandırıcının adını girin.')); return }
                def known = QP.getProject()?.getObjectClassifiers()?.getNames() ?: []
                if (!known.contains(nm)) {
                    if (stage != null) stage.setAlwaysOnTop(false)
                    Dialogs.showWarningNotification(workshopText('script.57b501859ef7', 'Sınıflandırıcı bulunamadı'),
                        "\"${nm}${workshopText('script.6c177effcc99', '" adlı nesne sınıflandırıcısı projede yok.\nMevcut: ')}${known.join(', ') ?: workshopText('script.715392eca0f8', '(yok)')}\n" +
                        workshopText('script.d52c4dfa8ba7', "Train Object Classifier penceresinde 'Save' yaptınız mı?"))
                    if (stage != null) stage.setAlwaysOnTop(true)
                    return
                }
                startApply(nm)
            }, workshopText('script.a67848fb85ec', 'Kaydedilen nesne sınıflandırıcısını tüm hücrelere uygular')))
    } else if (cur == 'APPLYING') {
        title.setText(workshopText('script.c7f9b0ba20c4', 'Sınıflandırıcı uygulanıyor…'))
        bodyLbl.setText("'${savedClassifierName.get()}${workshopText('script.c664953a8916', '\' tüm tespit edilen hücrelere uygulanıyor ve cTCF hesaplanıyor. Lütfen bekleyin…')}")
        content.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        def ar = applyResultRef.get()
        def a = ar?.apply
        def res = ar?.result
        title.setText('5/5 — cTCF Sonucu')
        if (a?.ok) {
            def summaryLbl = new javafx.scene.control.Label(
                String.format(java.util.Locale.US, workshopText('script.c525977d89b0', 'Sınıflandırma: Tumor=%,d, Non-neoplastic=%,d, Ignore=%,d'),
                    a.tumorCount, a.nonNeoCount, a.ignoreCount))
            summaryLbl.setWrapText(true)
            def resultArea = new javafx.scene.control.TextArea(res?.text ?: '')
            resultArea.setEditable(false); resultArea.setWrapText(false); resultArea.setPrefRowCount(14)
            resultArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;")
            content.getChildren().addAll(summaryLbl, resultArea)
            javafx.scene.layout.VBox.setVgrow(resultArea, javafx.scene.layout.Priority.ALWAYS)
            def copyBtn = new javafx.scene.control.Button(workshopText('script.a8bcca42d9ce', 'Kopyala'))
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def cc = new javafx.scene.input.ClipboardContent(); cc.putString(res?.text ?: ''); cb.setContent(cc)
            })
            buttons.getChildren().addAll(navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('TRAIN_GUIDE'); render() }), copyBtn, navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
        } else {
            bodyLbl.setText(workshopText('script.b40481006ffe', 'Sınıflandırma tamamlanamadı. Geri dönüp tekrar deneyin.'))
            buttons.getChildren().addAll(navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('TRAIN_GUIDE'); render() }), navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
        }
    }

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(content)
    def disclaimer = new javafx.scene.control.Label(workshopText('script.04e70fc651a3', 'Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.'))
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, buttons)
    bottom.setPadding(new javafx.geometry.Insets(10))
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 660, 600))
}

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('QuANTUM cTCF')
        stage.setAlwaysOnTop(true)
        stage.setOnHidden({ clearMenuHighlight() })   // #3: pencere kapanınca menü vurgusunu kaldır
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.7305ffe40a4c', 'QuANTUM modülü açılamadı'), t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
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
