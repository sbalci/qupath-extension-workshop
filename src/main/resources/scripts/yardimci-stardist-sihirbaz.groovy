/**
 * Yardımcı - StarDist Çekirdek Tespiti Sihirbazı (tek pencere)
 * -------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Yerel QuPath **StarDist eklentisi** ile, seçili anotasyon (ROI) içinde
 *   H&E çekirdeklerini derin öğrenme ile tespit eder — tek pencereden, kod
 *   yazmadan ve (ertelenen) Modül 8'i etkinleştirmeden:
 *     1. StarDist eklentisi + he_heavy_augment.pb modeli kontrol edilir
 *        (model eksikse otomatik iner, SHA-256 doğrulanır; gerekirse elle seçim).
 *     2. ÇEKİRDEK TESPİTİ — seçili ROI içinde StarDist çalışır.
 *     3. Özet: çekirdek sayısı, ROI alanı (mm²), yoğunluk (çekirdek/mm²), süre.
 *   StarDist QuPath İÇİNDE OpenCV ile koşar — bu .pb modelleri için ayrıca DJL/
 *   TensorFlow kurmak GEREKMEZ.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Yalnız sayım / alan / yoğunluk üretir; sınıflandırma, grade, alt-tip veya
 *     klinik yorum YAPMAZ. (Tümör/non-neoplastik ayrımı + cTCF için Modül 8 — QuANTUM.)
 *   • StarDist çıktısı bir DERİN ÖĞRENME TAHMİNİDİR; görsel doğrulama gerekir (Ek W).
 *   • Eşik / piksel boyutu / hücre genişletme değerleri Modül 8 ile ORTAK kalıcı
 *     atölye ayarlarıdır (Atölye Ayarları → StarDist).
 *
 * KULLANIM:
 *   1. StarDist eklentisini kurun: [Extensions → Manage extensions] → StarDist.
 *      Ayrıntılı kurulum: Ekler → Ek G (StarDist Eklentisi).
 *   2. Bir H&E slaytı açın; ölçülecek bölge için bir ALAN anotasyonu çizin ve SEÇİN.
 *   3. [Extensions → Atölye → Yardımcılar → StarDist çekirdek tespiti sihirbazı]
 *   4. (Ops.) eşik / piksel boyutu / hücre genişletmeyi ayarlayın → "Çekirdek tespiti".
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Schmidt U ve ark. (2018), MICCAI — StarDist. arXiv:1806.03535
 *   • Weigert M ve ark. (2020), WACV — 3D StarDist. arXiv:1908.03636
 *   • Model: github.com/qupath/models/tree/main/stardist (he_heavy_augment.pb)
 *   • Resmî doküman: qupath.readthedocs.io/en/stable/docs/deep/stardist.html
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathAnnotationObject
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"

// --- StarDist modeli: kamuya açık qupath/models'ten sabitlenmiş indirme ---
// Pin: qupath/models @ 60cc9dd (public, auth gerektirmez). İndirme sonrası
// SHA-256 + boyut doğrulanır; uyuşmazsa elle seçim yedeğine düşülür.
// (Modül 8 ile aynı model + aynı pin.)
final String MODEL_URL    = "https://raw.githubusercontent.com/qupath/models/60cc9dd3406871fdd4bbbe8ad76a94e759bab7dd/stardist/he_heavy_augment.pb"
final String MODEL_SHA256 = "00033ae84b03ef82faec2ffad4dc7cd12666738ab70b339f35d3a6e5a5379bab"
final long   MODEL_BYTES  = 5722237L

// ── Kalıcı yapılandırma ──────────────────────────────────────────────────────
//   Parametreler (eşik/px/genişletme) eklenti yüklüyse WorkshopPrefs'ten okunur
//   (Modül 8 ile ortak); eklenti yoksa bu yerel düğümden okunur/yazılır.
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/stardist')
def PREF_MODEL = 'modelPath'
def PREF_THR   = 'threshold'
def PREF_PX    = 'pixelSize'
def PREF_EXP   = 'cellExpansion'

def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().trim()) } catch (Throwable t) { return d } }

// ── Atölye ayarları köprüsü (reflective; eklenti JAR'ı olmadan da çalışır) ────
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeD = { String k, double d -> (double) __wpCall('dbl', [String.class, double.class] as Class[], [k, d] as Object[], d) }
def wpPutD  = { String k, double v -> __wpCall('setDbl', [String.class, double.class] as Class[], [k, (Double) v] as Object[], null) }
def wpPresent = (__wpClass() != null)

// Parametre yükleme: WorkshopPrefs (kanonik, Modül 8 ile ortak) → yoksa yerel düğüm.
def loadThreshold = { -> wpPresent ? atolyeD('atolye.stardistThreshold', 0.5)
                                   : parseDoubleOr(prefs.get(PREF_THR, '0.5'), 0.5) }
def loadPixelSize = { -> wpPresent ? atolyeD('atolye.stardistPixelSize', 0.5)
                                   : parseDoubleOr(prefs.get(PREF_PX, '0.5'), 0.5) }
def loadCellExp   = { -> wpPresent ? atolyeD('atolye.stardistCellExpansion', 5.0)
                                   : parseDoubleOr(prefs.get(PREF_EXP, '5.0'), 5.0) }
def saveParams = { double thr, double px, double exp ->
    if (wpPresent) {
        wpPutD('atolye.stardistThreshold', thr)
        wpPutD('atolye.stardistPixelSize', px)
        wpPutD('atolye.stardistCellExpansion', exp)
    }
    prefs.put(PREF_THR, Double.toString(thr))
    prefs.put(PREF_PX,  Double.toString(px))
    prefs.put(PREF_EXP, Double.toString(exp))
    try { prefs.flush() } catch (Throwable ignore) {}
}

// ── Model yolu ───────────────────────────────────────────────────────────────
def modelPathDefault = { -> new File(System.getProperty('user.home'), '.qupath/stardist/he_heavy_augment.pb').getAbsolutePath() }
def loadModelPath = { -> def p = prefs.get(PREF_MODEL, ''); return (p?.trim()) ? p.trim() : modelPathDefault() }

// İndirme sırasında gösterilen basit (belirsiz) ilerleme penceresi.
def makeProgressStage = { String msg ->
    if (isHeadless) return null
    def ref = new java.util.concurrent.atomic.AtomicReference()
    def latch = new java.util.concurrent.CountDownLatch(1)
    javafx.application.Platform.runLater {
        try {
            def st = new javafx.stage.Stage()
            st.initModality(javafx.stage.Modality.NONE)
            st.setAlwaysOnTop(true)
            st.setTitle("StarDist modeli")
            def bar = new javafx.scene.control.ProgressBar()
            bar.setPrefWidth(300)
            bar.setProgress(javafx.scene.control.ProgressIndicator.INDETERMINATE_PROGRESS)
            def label = new javafx.scene.control.Label(msg)
            label.setWrapText(true)
            def box = new javafx.scene.layout.VBox(12, label, bar)
            box.setPadding(new javafx.geometry.Insets(16))
            st.setScene(new javafx.scene.Scene(box, 380, 130))
            st.show()
            ref.set(st)
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

def stardistInstalled = { -> try { Class.forName('qupath.ext.stardist.StarDist2D'); return true } catch (Throwable t) { return false } }

def imageNameOf = { imageData -> def nm = imageData.getServer().getMetadata().getName() ?: 'slide'; return nm }

// ── Özet metni ───────────────────────────────────────────────────────────────
def buildResultText = { String slideName, int n, double areaMm2, double density, double secs,
                        double thr, double px, double exp, boolean calOk ->
    def sb = new StringBuilder()
    sb << "STARDIST — ÇEKİRDEK TESPİTİ\n"
    sb << "═══════════════════════════════\n\n"
    sb << "Slayt          : " << slideName << "\n"
    sb << String.format(java.util.Locale.US, "Eşik           : %.2f%n", thr)
    sb << String.format(java.util.Locale.US, "Piksel boyutu  : %.2f µm/px%n", px)
    sb << String.format(java.util.Locale.US, "Hücre genişletme: %s%n", (exp > 0 ? String.format(java.util.Locale.US, "%.1f µm", exp) : "yok (yalnız çekirdek)"))
    sb << "\n"
    sb << String.format(java.util.Locale.US, "Çekirdek sayısı : %,d%n", n)
    if (calOk) {
        sb << String.format(java.util.Locale.US, "ROI alanı       : %.4f mm²%n", areaMm2)
        sb << String.format(java.util.Locale.US, "Yoğunluk        : %,.1f çekirdek/mm²%n", density)
    } else {
        sb << "ROI alanı       : (piksel kalibrasyonu yok — alan/yoğunluk hesaplanamadı)\n"
    }
    sb << String.format(java.util.Locale.US, "Süre            : %.1f sn%n", secs)
    sb << "\nŞekil + yoğunluk ölçümleri her hücreye yazıldı (Measurements paneli).\n"
    sb << "StarDist çıktısı bir derin öğrenme tahminidir; görsel olarak doğrulayın (Ek W).\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

// ── Headless: yapılandırmayı raporla, etkileşim yok ─────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println "Önce bir slayt açın."; return }
    println String.format(java.util.Locale.US, "StarDist sihirbazı: eşik=%.2f px=%.2f genişletme=%.1f",
        loadThreshold(), loadPixelSize(), loadCellExp())
    println "StarDist eklentisi: ${stardistInstalled() ? 'bulundu' : 'BULUNAMADI — Extensions → Manage extensions → StarDist'}"
    println "Model: ${loadModelPath()} (${new File(loadModelPath()).exists() ? 'mevcut' : 'eksik — ilk çalıştırmada iner'})"
    println "Bu sihirbaz QuPath arayüzü gerektirir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
// NEED_INSTALL | READY | RUNNING | RESULT | ERROR
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef   = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def thrFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def pxFieldRef    = new java.util.concurrent.atomic.AtomicReference(null)
def expFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def modelFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def render  // forward declaration

def textOf = { ref -> def f = ref.get(); return (f != null ? f.getText() : '').trim() }
def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text)
    b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    return b
}
def busyBar = { ->
    def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb
}
def copyToClipboard = { String txt ->
    def cb = javafx.scene.input.Clipboard.getSystemClipboard()
    def content = new javafx.scene.input.ClipboardContent()
    content.putString(txt ?: "")
    cb.setContent(content)
}

// ── Tespit akışı (arka plan iş parçacığı) ───────────────────────────────────
def startDetection = {
    if (!stardistInstalled()) { errorTextRef.set('StarDist eklentisi yüklü değil.\nExtensions → Manage extensions → StarDist (bkz. Ek G).'); step.set('ERROR'); render(); return }
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def selected = QP.getSelectedObject()
    if (selected == null || !(selected instanceof PathAnnotationObject) || !selected.hasROI() || !selected.getROI().isArea()) {
        errorTextRef.set('Önce bir ALAN anotasyonu çizip seçin.\n  1. [P] → Polygon/Brush aracı\n  2. Ölçülecek bölgenin çevresini çizin\n  3. Anotasyon seçiliyken "Çekirdek tespiti".'); step.set('ERROR'); render(); return
    }
    // Parametreleri alanlardan oku, doğrula, kalıcılaştır
    double thr = parseDoubleOr(textOf(thrFieldRef), loadThreshold())
    double px  = parseDoubleOr(textOf(pxFieldRef),  loadPixelSize())
    double exp = parseDoubleOr(textOf(expFieldRef), loadCellExp())
    if (!(thr > 0.0d && thr <= 1.0d)) { errorTextRef.set('Eşik 0 ile 1 arasında olmalı (örn. 0.5).'); step.set('ERROR'); render(); return }
    if (!(px > 0.0d))                 { errorTextRef.set('Piksel boyutu pozitif olmalı (örn. 0.5).'); step.set('ERROR'); render(); return }
    if (exp < 0.0d)                   { errorTextRef.set('Hücre genişletme negatif olamaz (0 = yalnız çekirdek).'); step.set('ERROR'); render(); return }
    def mp = textOf(modelFieldRef)
    prefs.put(PREF_MODEL, mp); try { prefs.flush() } catch (Throwable ig) {}
    saveParams(thr, px, exp)
    String modelPathToUse = (mp?.trim()) ? mp.trim() : modelPathDefault()

    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('StarDist çalışıyor…'); step.set('RUNNING'); render()

    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        appendLine('Slayt: ' + imageNameOf(imageData))
        appendLine(String.format(java.util.Locale.US, 'Parametreler: eşik %.2f · px %.2f µm · genişletme %.1f µm', thr, px, exp))

        // 1) Model dosyası (otomatik indir → SHA-256 → gerekirse elle seç)
        def modelFile = new File(modelPathToUse)
        if (!modelFile.exists()) {
            appendLine('Model indiriliyor: he_heavy_augment.pb (~5.7 MB)…')
            def progress = makeProgressStage("StarDist modeli indiriliyor…\n(he_heavy_augment.pb, ~5.7 MB)")
            boolean ok = false
            try { downloadModel(modelFile); ok = true; appendLine('  ✓ indirildi ve SHA-256 doğrulandı: ' + modelFile.getAbsolutePath()) }
            catch (Throwable t) { appendLine('  ✗ otomatik indirme başarısız: ' + (t.message ?: t.getClass().getSimpleName())) }
            finally { if (progress != null) javafx.application.Platform.runLater { progress.close() } }
            if (!ok) {
                def picked = Dialogs.promptForFile("StarDist modelini seç (.pb)", null, "StarDist Model (.pb)", "pb")
                if (picked == null) { javafx.application.Platform.runLater { errorTextRef.set('Model yok ve elle seçim iptal edildi.\nİnternet bağlıyken tekrar deneyin ya da modeli ~/.qupath/stardist/ içine koyun (bkz. Ek G).'); step.set('ERROR'); render() }; return }
                modelFile = picked
            }
        } else {
            appendLine('Model: ' + modelFile.getAbsolutePath())
        }
        if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }

        // 2) StarDist tespiti
        appendLine('StarDist çekirdek tespiti çalışıyor (büyük ROI\'de birkaç dakika sürebilir)…')
        def t0 = System.currentTimeMillis()
        try {
            def oldDetections = selected.getChildObjects().findAll { it.isDetection() }
            if (!oldDetections.isEmpty()) { selected.removePathObjects(oldDetections); appendLine('  Önceki ' + oldDetections.size() + ' tespit temizlendi.') }

            def stardist = qupath.ext.stardist.StarDist2D
                    .builder(modelFile.getAbsolutePath())
                    .threshold(thr)
                    // Global (tüm-görüntü) normalizasyon — arka planda "hayalet çekirdek"i
                    // önler (resmî StarDist dokümanının önerdiği yol; QuPath 0.4+).
                    .preprocess(
                        qupath.ext.stardist.StarDist2D.imageNormalizationBuilder()
                            .maxDimension(4096)
                            .percentiles(0.2, 99.8)
                            .build()
                    )
                    .pixelSize(px)
                    .cellExpansion(exp)
                    .constrainToParent(true)
                    .measureShape()
                    .measureIntensity()
                    .build()
            try {
                QP.selectObjects(selected)
                stardist.detectObjects(imageData, [selected])
            } finally {
                try { stardist.close() } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('StarDist tespiti başarısız:\n' + (t.getMessage() ?: t.getClass().getSimpleName()) + '\n\nMODEL_URL/SHA uyuşmazlığı, eksik DJL motoru (SavedModel modelleri için) ya da bellek olabilir. Konsolu kontrol edin.'); step.set('ERROR'); render() }
            return
        }
        def secs = (System.currentTimeMillis() - t0) / 1000.0d

        def dets = selected.getChildObjects().findAll { it.isDetection() }
        int n = dets.size()

        // 3) Alan + yoğunluk
        def cal = imageData.getServer().getPixelCalibration()
        double pw = cal.getPixelWidthMicrons(); double ph = cal.getPixelHeightMicrons()
        boolean calOk = (pw > 0.0d && ph > 0.0d)
        double areaMm2 = Double.NaN; double density = Double.NaN
        if (calOk) {
            double areaPx = selected.getROI().getArea()
            areaMm2 = areaPx * pw * ph / 1_000_000.0d
            density = (areaMm2 > 0.0d) ? (n / areaMm2) : Double.NaN
        }

        QP.fireHierarchyUpdate()
        javafx.application.Platform.runLater { try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {} }

        if (n == 0) {
            javafx.application.Platform.runLater {
                errorTextRef.set(String.format(java.util.Locale.US,
                    'StarDist 0 çekirdek buldu.\nOlası nedenler:\n  • Eşik çok yüksek (%.2f) — düşürmeyi deneyin (%.2f)\n  • ROI çok küçük\n  • Slayt H&E için tipik boyama aralığında değil',
                    thr, thr * 0.6d))
                step.set('ERROR'); render()
            }
            return
        }

        def slideName = imageNameOf(imageData)
        javafx.application.Platform.runLater {
            resultTextRef.set(buildResultText(slideName, n, areaMm2, density, secs, thr, px, exp, calOk))
            step.set('RESULT'); render()
        }
    }, 'AtolyeStarDist-Run')
    worker.setDaemon(true); worker.start()
}

// ── Render: her durum değişiminde sahneyi sıfırdan kurar ────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def imageData = QP.getCurrentImageData()

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10)
    center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)
    def actions = new ArrayList()

    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); center.getChildren().add(lbl) }
    def addMonoArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: '')
        ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(ta)
    }
    def addLiveLog = { ->
        def la = logAreaRef.get()
        if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) }
    }
    // READY ekranında parametre düzenleme satırları (her açılışta kalıcı değerlerle dolar)
    def addParamGrid = {
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def thrField = new javafx.scene.control.TextField(String.format(java.util.Locale.US, '%.2f', loadThreshold()))
        def pxField  = new javafx.scene.control.TextField(String.format(java.util.Locale.US, '%.2f', loadPixelSize()))
        def expField = new javafx.scene.control.TextField(String.format(java.util.Locale.US, '%.1f', loadCellExp()))
        def mdField  = new javafx.scene.control.TextField(loadModelPath())
        [thrField, pxField, expField].each { it.setPrefColumnCount(8) }
        mdField.setPrefColumnCount(34)
        thrFieldRef.set(thrField); pxFieldRef.set(pxField); expFieldRef.set(expField); modelFieldRef.set(mdField)
        def browse = navButton('…', {
            def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'StarDist modeli (.pb) seç')
            if (x != null) mdField.setText(x.getAbsolutePath())
        })
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Eşik (0-1):'), thrField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Piksel boyutu (µm/px):'), pxField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Hücre genişletme (µm, 0=yok):'), expField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Model (.pb):'), mdField, browse)
        center.getChildren().add(grid)
    }

    if (cur == 'NEED_INSTALL') {
        title.setText('StarDist eklentisi gerekli')
        addGuidance('Bu sihirbaz yerel QuPath **StarDist eklentisini** kullanır; şu anda yüklü değil.\n\n' +
            'Kurulum:\n  1. [Extensions → Manage extensions] → StarDist → Install\n  2. QuPath\'i yeniden başlatın\n  3. Bu sihirbazı tekrar açın\n\n' +
            'Ayrıntılı kurulum + model dosyaları: Ekler → Ek G (StarDist Eklentisi).\n' +
            'Not: .pb modelleri QuPath içinde OpenCV ile koşar — ayrıca DJL/TensorFlow GEREKMEZ.')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('⟳ Yeniden denetle', { step.set(stardistInstalled() ? 'READY' : 'NEED_INSTALL'); render() }))
    } else if (cur == 'READY') {
        title.setText('StarDist — çekirdek tespiti')
        if (imageData == null) {
            addGuidance('Önce bir H&E slaytı açın ve ölçülecek bölge için bir alan anotasyonu çizip SEÇİN, sonra "⟳ Yenile".')
        } else {
            def selected = QP.getSelectedObject()
            boolean selOk = (selected != null && selected instanceof PathAnnotationObject && selected.hasROI() && selected.getROI().isArea())
            def modelFile = new File(loadModelPath())
            def sb = new StringBuilder()
            sb << "Slayt           : " << imageNameOf(imageData) << "\n"
            sb << "Seçili anotasyon: " << (selOk ? 'var (alan ROI)' : 'YOK — bir alan anotasyonu seçin') << "\n"
            sb << "StarDist        : yüklü ✓\n"
            sb << "Model           : " << (modelFile.exists() ? (modelFile.getName() + ' (mevcut)') : 'eksik — tespitte otomatik iner') << "\n"
            addMonoArea(sb.toString())
            addParamGrid()
            addGuidance('Çıktı yalnız çekirdek SAYISI + alan + yoğunluktur; sınıflandırma/yorum üretmez. ' +
                'Tümör/non-neoplastik ayrımı + cTCF için Modül 8 (QuANTUM).')
        }
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('⟳ Yenile', { render() }))
        def selectedNow = (imageData != null) ? QP.getSelectedObject() : null
        boolean canRun = (imageData != null && selectedNow != null && selectedNow instanceof PathAnnotationObject && selectedNow.hasROI() && selectedNow.getROI().isArea())
        def runBtn = navButton('Çekirdek tespiti ▶', { startDetection() }, 'Seçili ROI içinde StarDist H&E çekirdek tespiti çalıştırır')
        runBtn.setDisable(!canRun)
        actions.add(runBtn)
    } else if (cur == 'RUNNING') {
        title.setText(runPhaseRef.get())
        addGuidance('StarDist çalışıyor. İlerleme aşağıda. Büyük ROI\'lerde CPU\'da birkaç dakika sürebilir.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true) }, 'Devam eden StarDist çağrısı tamamlanana dek beklenebilir; iptal bir sonraki kontrol noktasında etkilidir'))
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅')
        addMonoArea(resultTextRef.get())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('↻ Yeniden çalıştır', { step.set('READY'); render() }))
    } else { // ERROR
        title.setText('Hata')
        addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Geri', { step.set(stardistInstalled() ? 'READY' : 'NEED_INSTALL'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    }

    // Alt çubuk: "Üstte tut" (sol) + disclaimer + eylem düğmeleri (sağ)
    def topChk = new javafx.scene.control.CheckBox('Üstte tut')
    topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n ->
        alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n)
    } as javafx.beans.value.ChangeListener)
    def spacer = new javafx.scene.layout.Region()
    javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8)
    bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk)
    bar.getChildren().add(spacer)
    bar.getChildren().addAll(actions)

    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(center)
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 760, 600))
}

// ── Açılış durumu ───────────────────────────────────────────────────────────
step.set(stardistInstalled() ? 'READY' : 'NEED_INSTALL')

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('StarDist çekirdek tespiti sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ StarDist çekirdek tespiti sihirbazı açıldı."
