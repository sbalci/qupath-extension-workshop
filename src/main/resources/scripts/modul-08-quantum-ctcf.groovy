/**
 * Modül 8 - QuANTUM cTCF (tek pencere sihirbazı)
 * -----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Yayınlanmış (L'Imperio ve ark., Virchows Archiv 2025) QuANTUM iş akışının
 * basitleştirilmiş, tek pencerede yönlendirilen bir uyarlamasıdır. H&E slaytında
 * **cTCF** (Computational Tumor Cellular Fraction) hesaplar:
 *     cTCF = Tumor / (Tumor + Non-neoplastic) × 100   (Ignore paydadan dışlanır)
 *
 * SİHİRBAZ ADIMLARI (Modül 6/7 deseni — tek pencere, adım adım):
 *   PREREQ          → görüntü · H&E · kalibrasyon · proje · StarDist · model
 *   DETECT_REGION   → patolog tarafından çizilmiş TCR anotasyonunu seç
 *   DETECTING       → StarDist TCR içindeki tüm çekirdekleri tespit eder
 *   TUMOR_EXAMPLES  → birkaç tümör hücresini "Tumor" sınıfıyla işaretle
 *   NONNEO_EXAMPLES → birkaç tümör-dışı hücreyi "Non-neoplastic" ile işaretle
 *   TRAIN_GUIDE     → QuPath'in Train Object Classifier penceresinde eğit + kaydet
 *   APPLYING        → sınıflandırıcı tüm tespit edilen hücrelere uygulanır
 *   RESULT          → cTCF + sayımlar; TCR anotasyonuna 5 ölçüm yazılır (Modül 9)
 *
 * ÖNKOŞULLAR:
 *   1. StarDist eklentisi yüklü (sihirbaz yoksa kuruluma yönlendirir)
 *   2. Model dosyası ~/.qupath/stardist/he_heavy_augment.pb
 *      (yoksa ilk tespitte github.com/qupath/models'ten otomatik indirilir)
 *   3. Sınıflandırıcı sihirbazın içinde, kendi olgunuzdan interaktif eğitilir
 *
 * Çıktılar yalnız sayım, alan, oran (cTCF) ve süredir.
 * Klinik kategori, NGS yeterlilik kararı veya yorum üretilmez.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// --- Atölye ayarları: eklenti yüklüyse oku, yoksa atölye varsayılanı kullanılır ---
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeD = { String k, double d -> (double) __wpCall('dbl', [String.class, double.class] as Class[], [k, d] as Object[], d) }

// ── Önkoşul düzeltme için paketli yardımcı betikleri çalıştırma ──
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
    }, "Modul8Wizard-${name}")
    runner.setDaemon(true); runner.start()
    return true
}
def menuHint = { String where ->
    Dialogs.showMessageDialog('Eklenti gerekli',
        "Bu adım atölye eklentisini gerektirir. Menüden çalıştırın:\n${where}")
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

// DETECT — TCR içinde StarDist çekirdek tespiti (+ model çöz, +pürüzsüzleştirme).
//   Dönüş: [ok:true, count:n] | [ok:false, error:…]
def runStarDist = { regionAnno ->
    try {
        def imageData = QP.getCurrentImageData()
        if (imageData == null) return [ok:false, error:'Görüntü açık değil.']

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

        // Önceki tespitleri temizle (yeniden çalıştırılabilirlik).
        def oldDet = regionAnno.getChildObjects().findAll { it.isDetection() }
        if (!oldDet.isEmpty()) regionAnno.removePathObjects(oldDet)

        long t0 = System.currentTimeMillis()
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
        double elapsed = (System.currentTimeMillis() - t0) / 1000.0

        // Pürüzsüzleştirilmiş (komşuluk) özellikler — sınıflandırıcıya ek sinyal. Başarısızsa atlanır.
        try {
            QP.selectObjects(regionAnno)
            QP.runPlugin('qupath.lib.plugins.objects.SmoothFeaturesPlugin',
                '{"fwhmMicrons":25.0,"smoothWithinClasses":false}')
        } catch (Throwable ignored) {
            println 'Modül 8: özellik pürüzsüzleştirme atlandı.'
        }

        def cells = regionAnno.getChildObjects().findAll { it.isDetection() }
        if (cells.isEmpty())
            return [ok:false, error:'TCR içinde çekirdek tespit edilmedi. Eşik veya boya vektörü ayarlarını kontrol edin.']

        regionAnno.setName('TCR')
        def viewer = qupath.lib.gui.QuPathGUI.getInstance()?.getViewer()
        if (viewer != null) javafx.application.Platform.runLater { viewer.repaint() }
        QP.fireHierarchyUpdate()
        println String.format(java.util.Locale.US, 'Modül 8 StarDist: %,d çekirdek (%.1f sn)', cells.size(), elapsed)
        return [ok:true, count:cells.size()]
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
    }
    int tumor = 0, nonNeo = 0, ignore = 0
    cells.each { c ->
        def cls = c.getPathClass()?.getName()?.toLowerCase(java.util.Locale.ROOT) ?: ''
        if (cls.contains('tumor')) tumor++
        else if (cls.contains('non-neoplastic') || cls.contains('non neoplastic') || cls.contains('nonneoplastic')) nonNeo++
        else ignore++
    }
    println String.format(java.util.Locale.US, 'Modül 8 sınıflandırma: Tumor=%,d Non-neoplastic=%,d Ignore=%,d', tumor, nonNeo, ignore)
    return [ok:true, tumorCount:tumor, nonNeoCount:nonNeo, ignoreCount:ignore]
}

// RESULT — cTCF hesapla, TCR ölçüm listesine yaz (Modül 9 dışa aktarır), metin üret.
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

    // Ölçümleri TCR anotasyonuna yaz → Modül 9 TSV'sinde görünür.
    regionAnno.measurements['TCR alanı (mm2)'] = tcrAreaMm2
    if (!Double.isNaN(cTCF)) regionAnno.measurements['cTCF (%)'] = cTCF
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
    body << 'bağlamıdır; sizin slaytınıza/laboratuvarınıza taşınmaz (yerel validasyon gerekir).\n\n'
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
        println 'Modül 8 (headless): bir görüntü ve seçili TCR anotasyonu gerekir.'; return
    }
    if (!stardistAvailable()) { println 'Modül 8 (headless): StarDist eklentisi yüklü değil.'; return }
    def d = runStarDist(sel)
    if (!d.ok) { println 'Modül 8 (headless) hata: ' + d.error; return }
    def clf = 'he-tumor-nonneo'
    def names = QP.getProject()?.getObjectClassifiers()?.getNames() ?: []
    if (names.contains(clf)) {
        def a = applyClassifier(sel, clf)
        if (a.ok) println computeResult(sel, a.tumorCount, a.nonNeoCount, a.ignoreCount).text
    } else {
        println String.format(java.util.Locale.US,
            'Modül 8 (headless): %,d çekirdek tespit edildi; "%s" sınıflandırıcısı yok, cTCF atlandı.', d.count, clf)
    }
    return
}

// ──────────────────────────────────────────────────────────────
// Tek pencere, adım adım render (Modül 6/7 sihirbaz deseni)
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
                Dialogs.showErrorMessage('StarDist başarısız', d.error ?: '')
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
                Dialogs.showErrorMessage('Sınıflandırıcı uygulanamadı', a.error ?: '')
                if (stage != null) stage.setAlwaysOnTop(true)
            }
        }
    }, 'Modul8Apply'); worker.setDaemon(true); worker.start()
}

render = { ->
    if (stage != null) stage.setAlwaysOnTop(true)
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
        title.setText('Önce: görüntü ve eklenti hazırlığı')
        bodyLbl.setText(
            'QuANTUM cTCF iş akışının önkoşulları:\n\n' +
            "  ${s.image ? '✓' : '✗'} Açık görüntü\n" +
            "  ${s.heType ? '✓' : '✗'} Görüntü tipi Brightfield (H&E)\n" +
            "  ${s.calib ? '✓' : '✗'} Piksel kalibrasyonu (µm/px)\n" +
            "  ${s.project ? '✓' : '✗'} Açık proje (sınıflandırıcı kaydı için)\n" +
            "  ${s.stardist ? '✓' : '✗'} StarDist eklentisi\n" +
            "  ${s.model ? '✓' : '⏳'} StarDist modeli " + (s.model ? '(hazır)' : '(ilk tespitte indirilecek, ~5.7 MB)') + '\n' +
            (s.stardist ? '' : "\nStarDist eksik: [Extensions → Manage extensions] ile kurup QuPath'i yeniden başlatın.\n") +
            "\nEksikleri düzeltip 'Yenile'.")
        def fixType = navButton('Görüntü tipini ayarla', {
            if (stage != null) stage.setAlwaysOnTop(false)
            if (!launchBundled('yardimci-image-type.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Görüntü tipi ayarla')
        })
        def fixCal = navButton('Kalibrasyonu ayarla', {
            if (stage != null) stage.setAlwaysOnTop(false)
            if (!launchBundled('yardimci-kalibrasyon.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu)')
        })
        content.getChildren().add(new javafx.scene.layout.HBox(8, fixType, fixCal))
        boolean ready = s.image && s.heType && s.calib && s.project && s.stardist
        def cont = navButton('Devam ▶', { step.set('DETECT_REGION'); render() })
        cont.setDisable(!ready)
        buttons.getChildren().addAll(navButton('İptal', { stage.close() }), navButton('⟳ Yenile', { render() }), cont)
    } else if (cur == 'DETECT_REGION') {
        title.setText('1/6 — TCR anotasyonunu seçin')
        bodyLbl.setText(
            'TCR (Tumor-Containing Region), patoloğun çizdiği araştırma alanıdır — saf tümör konturu\n' +
            'DEĞİL, içinde tümör bulunan geniş bir bölge. [P] (Polygon) ya da Brush ile çizip SEÇİN.\n' +
            'Sınıf atamanıza gerek yok; bu yalnız çekirdek tespitinin sınırıdır.\n\n' +
            "Şu an seçili alan anotasyonu: ${s.selectedArea}")
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('PREREQ'); render() }),
            navButton('⟳ Yenile', { render() }),
            navButton('Tespit et ▶', {
                def sel = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI()?.isArea() }
                if (sel.isEmpty()) { Dialogs.showWarningNotification('Bölge seçili değil', 'Önce bir alan (TCR) anotasyonu çizip SEÇİN.'); return }
                startStarDist(sel[0])
            }, 'Seçili TCR içinde StarDist çekirdek tespiti'))
    } else if (cur == 'DETECTING') {
        title.setText('StarDist tespiti çalışıyor…')
        bodyLbl.setText('TCR içindeki çekirdekler tespit ediliyor ve pürüzsüzleştirilmiş özellikler ekleniyor.\n' +
            'Model ilk kez indiriliyorsa biraz sürebilir. Lütfen bekleyin…')
        content.getChildren().add(busyBar())
    } else if (cur == 'TUMOR_EXAMPLES') {
        def d = detectResult.get()
        title.setText('2/6 — Tümör hücresi örnekleri')
        bodyLbl.setText(
            ((d?.ok) ? "Tespit edilen çekirdek: ${d.count}\n\n" : '') +
            'Birkaç TİPİK TÜMÖR HÜCRESİ bölgesini küçük anotasyonlarla çizin ve sınıfını tam olarak\n' +
            '"Tumor" yapın (Annotations paneli ya da sağ tık → Set class). Tümör epiteline ait\n' +
            'çekirdekleri hedefleyin; lenfosit ve stromadan uzak durun. Bitince İleri.\n\n' +
            "Şu an Tumor anotasyonu: ${s.tumor}")
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('DETECT_REGION'); render() }),
            navButton('⟳ Yenile', { render() }),
            navButton('İleri ▶', {
                if (s.tumor > 0) { step.set('NONNEO_EXAMPLES'); render() }
                else Dialogs.showWarningNotification('Tumor örneği yok', 'Sınıfı "Tumor" olan en az bir alan anotasyonu çizin, sonra İleri.')
            }))
    } else if (cur == 'NONNEO_EXAMPLES') {
        title.setText('3/6 — Non-neoplastic hücre örnekleri')
        bodyLbl.setText(
            'Şimdi tümör DIŞI hücrelerin (lenfosit, fibroblast, endotel, normal epitel) bulunduğu\n' +
            'bölgelerden birkaç örnek çizin ve sınıfını TAM OLARAK "Non-neoplastic" yapın\n' +
            '(büyük N, tireli, küçük n — yanlış yazım sessizce Ignore sayılır).\n' +
            'İsteğe bağlı: artefakt/belirsiz hücreleri "Ignore" sınıfıyla işaretleyebilirsiniz.\n' +
            'Bu ayrım, Non-neoplastic hücreleri cTCF paydasına dahil etmek için kritiktir. Bitince İleri.\n\n' +
            "Şu an Non-neoplastic: ${s.nonNeo}   |   Ignore: ${s.ignore}")
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('TUMOR_EXAMPLES'); render() }),
            navButton('⟳ Yenile', { render() }),
            navButton('İleri ▶', {
                if (s.nonNeo > 0) { step.set('TRAIN_GUIDE'); render() }
                else Dialogs.showWarningNotification('Non-neoplastic örneği yok', 'Sınıfı "Non-neoplastic" olan en az bir alan anotasyonu çizin, sonra İleri.')
            }))
    } else if (cur == 'TRAIN_GUIDE') {
        title.setText("4/6 — Sınıflandırıcıyı QuPath'te eğitin")
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
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('NONNEO_EXAMPLES'); render() }),
            navButton('⟳ Yenile', { render() }),
            navButton('Kaydedildi ▶', {
                String nm = nameField.getText()?.trim()
                if (!nm) { Dialogs.showWarningNotification('Ad gerekli', 'Kaydettiğiniz sınıflandırıcının adını girin.'); return }
                def known = QP.getProject()?.getObjectClassifiers()?.getNames() ?: []
                if (!known.contains(nm)) {
                    if (stage != null) stage.setAlwaysOnTop(false)
                    Dialogs.showWarningNotification('Sınıflandırıcı bulunamadı',
                        "\"${nm}\" adlı nesne sınıflandırıcısı projede yok.\nMevcut: ${known.join(', ') ?: '(yok)'}\n" +
                        "Train Object Classifier penceresinde 'Save' yaptınız mı?")
                    if (stage != null) stage.setAlwaysOnTop(true)
                    return
                }
                startApply(nm)
            }, 'Kaydedilen nesne sınıflandırıcısını tüm hücrelere uygular'))
    } else if (cur == 'APPLYING') {
        title.setText('Sınıflandırıcı uygulanıyor…')
        bodyLbl.setText("'${savedClassifierName.get()}' tüm tespit edilen hücrelere uygulanıyor ve cTCF hesaplanıyor. Lütfen bekleyin…")
        content.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        def ar = applyResultRef.get()
        def a = ar?.apply
        def res = ar?.result
        title.setText('5/6 — cTCF Sonucu')
        if (a?.ok) {
            def summaryLbl = new javafx.scene.control.Label(
                String.format(java.util.Locale.US, 'Sınıflandırma: Tumor=%,d, Non-neoplastic=%,d, Ignore=%,d',
                    a.tumorCount, a.nonNeoCount, a.ignoreCount))
            summaryLbl.setWrapText(true)
            def resultArea = new javafx.scene.control.TextArea(res?.text ?: '')
            resultArea.setEditable(false); resultArea.setWrapText(false); resultArea.setPrefRowCount(14)
            resultArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;")
            content.getChildren().addAll(summaryLbl, resultArea)
            javafx.scene.layout.VBox.setVgrow(resultArea, javafx.scene.layout.Priority.ALWAYS)
            def copyBtn = new javafx.scene.control.Button('Kopyala')
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def cc = new javafx.scene.input.ClipboardContent(); cc.putString(res?.text ?: ''); cb.setContent(cc)
            })
            buttons.getChildren().addAll(navButton('◀ Geri', { step.set('TRAIN_GUIDE'); render() }), copyBtn, navButton('Kapat', { stage.close() }))
        } else {
            bodyLbl.setText('Sınıflandırma tamamlanamadı. Geri dönüp tekrar deneyin.')
            buttons.getChildren().addAll(navButton('◀ Geri', { step.set('TRAIN_GUIDE'); render() }), navButton('Kapat', { stage.close() }))
        }
    }

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(content)
    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.')
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
        stage.setTitle('Modül 8 - QuANTUM cTCF')
        stage.setAlwaysOnTop(true)
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Modül 8 açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}