/**
 * Yardımcı - Hepatosit Segmentasyonu Sihirbazı (tek pencere köprü)
 * ----------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   sbalci/hepatocyte-app'in **kendi eğittiği** derin öğrenme (TorchScript JIT)
 *   modelini QuPath'e TEK pencereden bağlar — "kendi modelinizi QuPath'te
 *   çalıştırma" deseninin gerçek bir örneği:
 *     1. Seçili / çizili alan anotasyonlarının taban (level-0) koordinatlarını +
 *        WKT geometrisini bir istek JSON'una yazar.
 *     2. hepatocyte-app'in `hepatocyte_qupath_inference.py` betiğini bir Python
 *        venv'inde (QuPath DIŞINDA) çalıştırır; betik slaytı openslide ile kendi
 *        açar, modeli çalıştırır ve poligonları GeoJSON olarak döndürür.
 *     3. GeoJSON'u QuPath'e sınıflandırılmış anotasyon olarak geri aktarır; her ana
 *        ROI'ye sınıf başı hücre sayımı ölçümü yazar. Proje açıksa Excel raporu üretilir.
 *
 *   NON-CODER kurulum (①②③, terminal gerekmez):
 *     ① Python ortamı  → Atölye Python ortam yöneticisini açar; "Hepatocyte" venv'ini kurar.
 *     ② Depoyu indir   → sbalci/hepatocyte-app'i indirir/açar; köprü betiği yolu otomatik.
 *     ③ Model ağırlığı → bakımcıdan edinilen .pth'yi seçtirir (🔒 talep üzerine).
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Sınıflar MODELİN morfolojik sınıflarıdır (Balloon dystrophy, Mesenchymal,
 *     Non-nuclear, Normal, Steatosis); bu betik bunların üzerine hiçbir klinik
 *     eşik / derece (NAS/SAF/fibrozis) mantığı eklemez.
 *   • Çıktı yalnız sınıflandırılmış poligon + sınıf başı sayım ölçümüdür; patoloji
 *     yorumu, grade veya klinik karar üretmez. Bir DERİN ÖĞRENME TAHMİNİDİR;
 *     görsel doğrulama gerekir (Yapay Zekâ Araçlarını Değerlendirme eki).
 *   • Koordinatlar taban (level-0) piksel uzayındadır; betik yeniden ölçekleme yapmaz.
 *   • Model kısıtlıdır (🔒 talep üzerine); depo herkese açıktır.
 *
 * KULLANIM:
 *   1. Hepatocyte Python ortamını kurun (①), depoyu indirin (②), modeli seçin (③).
 *   2. Yerel diskteki bir karaciğer H&E slaydı açın; segmentlemek istediğiniz bölge(ler)
 *      için alan anotasyonu çizin.
 *   3. [Extensions → Atölye → Yardımcılar → Hücre/çekirdek tespiti → Hepatosit segmentasyonu sihirbazı]
 *   4. "Bağımlılık kontrolü" ile ortamı doğrulayın; "Segmentle" ile çalıştırın.
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Depo: https://github.com/sbalci/hepatocyte-app  (herkese açık; model 🔒 talep üzerine)
 *   • Ek → Hepatosit Segmentasyonu; Kaynaklar → İleri kurulumlar § K.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.fx.dialogs.FileChoosers
import qupath.lib.scripting.QP
import qupath.lib.io.GsonTools
import qupath.lib.io.PathIO
import org.locationtech.jts.io.WKTWriter
import com.google.gson.JsonParser
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ───────────────────────────────────────────────────────────────
long PYTHON_TIMEOUT_SECONDS = 1800L          // WSI bölge çıkarımı — cömert üst sınır
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def DEVICE_OPTIONS = ['auto', 'cpu', 'cuda']

// hepatocyte-app deposu (herkese açık) — köprü betiği ② ile buradan iner.
def HEPATO_REPO_ZIP   = 'https://github.com/sbalci/hepatocyte-app/archive/refs/heads/main.zip'
def HEPATO_ZIP_TOP    = 'hepatocyte-app-main'
def HEPATO_BRIDGE_SUB = 'qupath/hepatocyte_qupath_inference.py'   // depo içi köprü yolu

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır) ──
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/hepatocyte')
def PREF_PYTHON   = 'python'
def PREF_BRIDGE   = 'bridge'
def PREF_MODEL    = 'model'
def PREF_DEVICE   = 'device'
def PREF_PATCH    = 'patch'
def PREF_MINAREA  = 'minArea'
def PREF_MINROI   = 'minRoi'
def PREF_MPP      = 'targetMpp'
def PREF_MODELURL = 'modelUrl'    // boş; ileride yayımlanırsa otomatik indirme açılır

// Atölye veri kökü (env yöneticisiyle PAYLAŞILAN; öntanımlı ~/.atolye — C:).
def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
// torch/HF önbelleklerini de veri köküne yönlendir (C: dolmasın).
def applyCacheEnv = { pb ->
    try {
        def cache = new File(atolyeDataRoot(), 'cache'); cache.mkdirs()
        def hf = new File(cache, 'huggingface'); def env = pb.environment()
        env.put('HF_HOME', hf.getAbsolutePath()); env.put('HF_HUB_CACHE', new File(hf, 'hub').getAbsolutePath())
        env.put('TORCH_HOME', new File(cache, 'torch').getAbsolutePath())
    } catch (Throwable ignore) {}
}
def hepatoBase = { -> new File(atolyeDataRoot(), 'hepatocyte') }
def hepatoBridgeFile = { -> new File(new File(hepatoBase(), HEPATO_ZIP_TOP), HEPATO_BRIDGE_SUB) }

def loadConfig = { ->
    [ python : ({ -> def __p = prefs.get(PREF_PYTHON, ''); if (__p?.trim()) return __p; def __r = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('py.hepatocyte', ''); if (__r?.trim() && new File(__r.trim()).isFile()) return __r.trim(); def __v = new File(new File(atolyeDataRoot(), 'runtimes'), 'hepatocyte/.venv'); def __w = new File(__v, 'Scripts/python.exe'); def __n = new File(__v, 'bin/python'); __w.isFile() ? __w.getAbsolutePath() : (__n.isFile() ? __n.getAbsolutePath() : '') }).call(),
      bridge    : prefs.get(PREF_BRIDGE,   ''),
      model     : prefs.get(PREF_MODEL,    ''),
      device    : prefs.get(PREF_DEVICE,   'auto'),
      patch     : prefs.get(PREF_PATCH,    '512'),
      minArea   : prefs.get(PREF_MINAREA,  '15'),
      minRoi    : prefs.get(PREF_MINROI,   '512'),
      targetMpp : prefs.get(PREF_MPP,      '0.25'),
      modelUrl  : prefs.get(PREF_MODELURL, '') ]
}

// Zorunlu: python.exe + köprü betiği + model ağırlığı
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile())
        miss << 'Python yürütülebilir (python.exe)'
    if (!cfg.bridge?.trim() || !(new File(cfg.bridge)).isFile())
        miss << 'Köprü betiği (hepatocyte_qupath_inference.py)'
    if (!cfg.model?.trim() || !(new File(cfg.model)).isFile())
        miss << 'Model ağırlığı (.pth — 🔒 talep üzerine)'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

def parseIntOr = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }
def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().trim()) } catch (Throwable t) { return d } }

// ── Slayt yolunu çöz (yalnız yerel dosya; Python openslide ile açar) ────────
def resolveSlidePath = { imageData ->
    def server = imageData.getServer()
    def uris = []
    try { uris = server.getURIs() ?: [] } catch (Throwable ignore) {}
    for (def uri : uris) {
        try { def f = new File(uri); if (f.exists()) return f.getAbsolutePath() } catch (Throwable ignore) {}
    }
    // Yedek: server.getPath() içinden file: URI'sini ayıkla
    def sp = null
    try { sp = server.getPath() } catch (Throwable ignore) {}
    if (sp != null) {
        def m = (~"file:/+(.+?\\.svs|.+?\\.tiff?|.+?\\.ndpi|.+?\\.png)\$").matcher(sp)
        if (m.find()) {
            try { return java.net.URLDecoder.decode(m.group(1), 'UTF-8').replace('/', File.separator) } catch (Throwable ignore) {}
        }
    }
    return null
}

def imageNameOf = { imageData ->
    def nm = imageData.getServer().getMetadata().getName() ?: 'slide'
    return nm.replaceAll(/\.[^.\/\\]+$/, '')
}

// Hedef alan anotasyonları: seçili varsa onlar, yoksa tüm alan anotasyonları.
def targetAnnotations = { imageData ->
    def sel = QP.getSelectedObjects().findAll { it.isAnnotation() && it.hasROI() && it.getROI().isArea() }
    if (!sel.isEmpty()) return sel
    return imageData.getHierarchy().getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }
}

// ── İstek JSON'u kur (level-0 koordinat + WKT) ──────────────────────────────
def buildRequest = { imageData, slidePath, cfg, File reportFile ->
    def wkt = new WKTWriter()
    int minRoi = parseIntOr(cfg.minRoi, 512)
    def anns = targetAnnotations(imageData)
    def rois = []; int skippedSmall = 0
    anns.each { ann ->
        def roi = ann.getROI()
        int w = (int) Math.ceil(roi.getBoundsWidth())
        int h = (int) Math.ceil(roi.getBoundsHeight())
        if (w < minRoi || h < minRoi) { skippedSmall++; return }
        def shapeWkt = null
        try { def g = roi.getGeometry(); if (g != null) shapeWkt = wkt.write(g).toString() } catch (Throwable ignore) {}
        rois << [ id: ann.getID().toString(),
                  x: (int) Math.floor(roi.getBoundsX()),
                  y: (int) Math.floor(roi.getBoundsY()),
                  w: w, h: h, shape_wkt: shapeWkt ]
    }
    def request = [ slide_path : slidePath,
                    model_path : cfg.model,
                    rois       : rois,
                    patch_size : parseIntOr(cfg.patch, 512),
                    min_area   : parseIntOr(cfg.minArea, 15),
                    min_roi_size : minRoi,
                    device     : (cfg.device ?: 'auto'),
                    target_mpp : parseDoubleOr(cfg.targetMpp, 0.25d) ]
    if (reportFile != null) request.output_xlsx_path = reportFile.getAbsolutePath()
    return [request: request, nAnns: anns.size(), nRois: rois.size(), skippedSmall: skippedSmall]
}

def writeJson = { obj, File f ->
    if (f.getParentFile() != null) f.getParentFile().mkdirs()
    f.setText(GsonTools.getInstance(true).toJson(obj), 'UTF-8')
}

// ── GeoJSON içe aktar (PathIO) + sınıf başı sayım ölçümleri ─────────────────
def importResult = { File outputFile, imageData, List targetAnns ->
    if (outputFile == null || !outputFile.isFile())
        return [ok: false, error: 'Çıktı GeoJSON bulunamadı:\n' + (outputFile?.getAbsolutePath() ?: '(yol yok)')]
    def imported = []
    try { outputFile.withInputStream { is -> imported = PathIO.readObjectsFromGeoJSON(is) } }
    catch (Throwable t) { return [ok: false, error: 'GeoJSON okunamadı:\n' + (t.getMessage() ?: t.getClass().getSimpleName())] }
    if (imported != null && !imported.isEmpty()) {
        imageData.getHierarchy().addObjects(imported)
        QP.fireHierarchyUpdate()
    }
    // metadata.counts_per_roi → ana ROI ölçümleri + toplam döküm
    def countsPerRoi = [:]
    def classTotals = new TreeMap<String, Integer>()
    try {
        def reader = new java.io.InputStreamReader(
            new java.io.FileInputStream(outputFile), java.nio.charset.StandardCharsets.UTF_8)
        try {
            def root = JsonParser.parseReader(reader).getAsJsonObject()
            if (root.has('metadata') && root.get('metadata').isJsonObject()) {
                def md = root.getAsJsonObject('metadata')
                if (md.has('counts_per_roi') && md.get('counts_per_roi').isJsonObject()) {
                    md.getAsJsonObject('counts_per_roi').entrySet().each { e ->
                        def per = [:]
                        e.getValue().getAsJsonObject().entrySet().each { sub ->
                            int n = sub.getValue().getAsInt()
                            per[sub.getKey()] = n
                            classTotals[sub.getKey()] = (classTotals.getOrDefault(sub.getKey(), 0)) + n
                        }
                        countsPerRoi[e.getKey()] = per
                    }
                }
            }
        } finally { reader.close() }
    } catch (Throwable ignore) {}
    int annUpdated = 0
    targetAnns.each { ann ->
        def rid = ann.getID().toString()
        def per = countsPerRoi[rid]
        if (per == null) return
        int total = 0
        def ml = ann.getMeasurementList()
        per.each { cls, n ->
            ml.put(('Hepatocyte: ' + cls).toString(), (double) ((int) n))
            total += (int) n
        }
        ml.put('Hepatocyte: total'.toString(), (double) total)
        annUpdated++
    }
    return [ok: true, nImported: (imported ? imported.size() : 0), classTotals: classTotals, annUpdated: annUpdated]
}

// ── Özet metni ──────────────────────────────────────────────────────────────
def buildResultText = { slideName, exp, imp, File reportFile ->
    def sb = new StringBuilder()
    sb << "HEPATOSİT SEGMENTASYONU — ÖZET\n"
    sb << "════════════════════════════════\n\n"
    sb << "Slayt      : " << (slideName ?: '?') << "\n"
    sb << String.format(java.util.Locale.US, "Anotasyon  : %,d   ·   işlenen ROI: %,d%n", (int) (exp?.nAnns ?: 0), (int) (exp?.nRois ?: 0))
    if ((exp?.skippedSmall ?: 0) > 0)
        sb << String.format(java.util.Locale.US, "Atlanan    : %,d (bbox < min ROI, tek yamadan küçük)%n", (int) exp.skippedSmall)
    sb << String.format(java.util.Locale.US, "Poligon    : %,d%n", (int) (imp?.nImported ?: 0))
    sb << "\nSınıf dağılımı (hücre sayısı):\n"
    if (imp != null && imp.ok && !imp.classTotals.isEmpty()) {
        int total = 0
        imp.classTotals.each { cn, n -> sb << String.format(java.util.Locale.US, "  %-26s : %,d%n", cn, (int) n); total += (int) n }
        sb << String.format(java.util.Locale.US, "  %-26s : %,d%n", '(toplam)', total)
    } else {
        sb << "  (sınıf sayımı yok — model bu bölgede yalnız arka plan tahmin etmiş olabilir)\n"
    }
    sb << "\n'Hepatocyte: <sınıf>' ve 'Hepatocyte: total' ölçümleri her ana anotasyona yazıldı (Measurements paneli).\n"
    if (reportFile != null && reportFile.isFile())
        sb << "Excel raporu: " << reportFile.getAbsolutePath() << "\n"
    sb << "\nÇıktı bir derin öğrenme tahminidir; görsel olarak doğrulayın (Yapay Zekâ Araçlarını Değerlendirme eki).\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

// ── Headless: yapılandırmayı raporla, etkileşim yok ─────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println "Önce bir slayt açın."; return }
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    println "Hepatosit yapılandırması: python=${cfg.python ?: '(ayarsız)'} köprü=${cfg.bridge ?: '(ayarsız)'} model=${cfg.model ?: '(ayarsız — 🔒 talep üzerine)'} device=${cfg.device}"
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(', ')}"
    def sp = resolveSlidePath(imageData)
    println "Slayt yolu: ${sp ?: '(yerel dosya değil — openslide açamaz)'}"
    def anns = targetAnnotations(imageData)
    println "Alan anotasyonu: ${anns.size()}"
    println "Hepatosit sihirbazı için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
// CONFIG_INCOMPLETE | CONFIG | CHECK_RUNNING | CHECK_DONE | READY
//   | RUN_RUNNING | BUSY | RESULT | ERROR
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def selftestOkRef = new java.util.concurrent.atomic.AtomicBoolean(true)
def processRef    = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef   = new java.util.concurrent.atomic.AtomicReference('')
def busyLabelRef  = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
// CONFIG düzenleme alanları
def pyFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def bridgeFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def modelFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def deviceChoiceRef= new java.util.concurrent.atomic.AtomicReference(null)
def patchFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def minAreaFieldRef= new java.util.concurrent.atomic.AtomicReference(null)
def minRoiFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def mppFieldRef    = new java.util.concurrent.atomic.AtomicReference(null)
// ①②③ kurulum
def installLog        = new StringBuilder()
def installLogAreaRef = new java.util.concurrent.atomic.AtomicReference(null)
def installBusyRef    = new java.util.concurrent.atomic.AtomicBoolean(false)
def render  // forward declaration

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

// ── Python süreci (ProcessBuilder) → satır akışı ────────────────────────────
def runPython = { List cmd, Closure onLine ->
    def pb = new ProcessBuilder(cmd)
    pb.redirectErrorStream(true)
    applyCacheEnv(pb)
    def proc
    try { proc = pb.start() }
    catch (Throwable e) { return [ok: false, exitCode: -1, error: 'Python başlatılamadı: ' + (e.getMessage() ?: e.getClass().getSimpleName())] }
    processRef.set(proc)
    def last = new java.util.ArrayDeque()
    try {
        def reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
        String line
        while ((line = reader.readLine()) != null) {
            last.addLast(line); while (last.size() > 60) last.pollFirst()
            onLine(line)
            if (cancelledRef.get()) break
        }
        reader.close()
    } catch (Throwable ignore) {}
    boolean finished
    try { finished = proc.waitFor(PYTHON_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) }
    catch (InterruptedException ie) { proc.destroyForcibly(); return [ok: false, exitCode: -3, error: 'İptal edildi'] }
    if (!finished) { proc.destroyForcibly(); return [ok: false, exitCode: -2, error: 'Zaman aşımı (' + PYTHON_TIMEOUT_SECONDS + ' sn)'] }
    if (cancelledRef.get()) { proc.destroyForcibly(); return [ok: false, exitCode: -3, error: 'İptal edildi'] }
    int code = proc.exitValue()
    return [ok: (code == 0), exitCode: code, lastLines: last.join('\n')]
}

// Bağımlılık + model kontrolü (betiğin selftest alt-komutu YOK → satır-içi -c).
// Model yolu AYRI argv (tırnak/backslash sorunu yok; ProcessBuilder cmd.exe'ye girmez).
def selftestCmd = { cfg ->
    def code = 'import sys, os; import numpy, pandas, torch, cv2, openslide, albumentations, openpyxl, shapely; from PIL import Image; ' +
        'print("deps OK"); ' +
        'm = sys.argv[1] if len(sys.argv) > 1 else ""; ' +
        'torch.jit.load(m, map_location="cpu") if (m and os.path.isfile(m)) else print("model AYARSIZ (yalniz bagimliliklar dogrulandi)"); ' +
        'print("SELFTEST OK")'
    return [cfg.python, '-c', code, (cfg.model ?: '')]*.toString()
}

// ── ①②③ kurulum yardımcıları (GrandQC deseninden) ──────────────────────────
def launchBundledScript = { String resourceName ->
    new Thread({
        try {
            def url = null
            try { url = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getResource('/scripts/' + resourceName) } catch (Throwable t) {}
            if (url == null) url = this.getClass().getResource('/scripts/' + resourceName)
            if (url == null) {
                javafx.application.Platform.runLater { Dialogs.showInfoNotification('Betik bulunamadı',
                    'Menüden açın: Extensions → Atölye → Yardımcılar → Python köprüleri & temel modeller → Atölye Python ortam yöneticisi') }
                return
            }
            def cl = this.getClass().getClassLoader()
            try { cl = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getClassLoader() } catch (Throwable t) {}
            new GroovyShell(cl).evaluate(url.getText('UTF-8'), resourceName)
        } catch (Throwable t) {
            javafx.application.Platform.runLater { Dialogs.showErrorMessage('Açılamadı', (t.getMessage() ?: t.getClass().getSimpleName())) }
        }
    } as Runnable).start()
}
def installLogSnapshot = { -> synchronized (installLog) { return installLog.toString() } }
def appendInstallLog = { String line ->
    String snap
    synchronized (installLog) { installLog.append(line).append('\n'); snap = installLog.toString() }
    javafx.application.Platform.runLater {
        def a = installLogAreaRef.get()
        if (a != null) { a.setText(snap); a.setScrollTop(Double.MAX_VALUE) }
    }
}
// Yönlendirmeli HTTPS indirici (yalnız https; Content-Length ile eksik indirmeyi yakalar; yarım dosyayı siler).
def httpDownload = { String urlStr, File dest ->
    if (dest.getParentFile() != null) dest.getParentFile().mkdirs()
    String cur = urlStr; int hops = 0
    while (true) {
        if (hops++ > 8) throw new RuntimeException('Çok fazla yönlendirme')
        def base = new java.net.URL(cur)
        if (!'https'.equalsIgnoreCase(base.getProtocol()))
            throw new RuntimeException('Güvensiz (https değil) bağlantı reddedildi: ' + cur)
        def conn = (java.net.HttpURLConnection) base.openConnection()
        conn.setInstanceFollowRedirects(false)
        conn.setConnectTimeout(30000); conn.setReadTimeout(120000)
        conn.setRequestProperty('User-Agent', 'atolye-hepatocyte-installer')
        int code
        try { code = conn.getResponseCode() } catch (Throwable t) { conn.disconnect(); throw t }
        if (code >= 300 && code < 400) {
            def loc = conn.getHeaderField('Location'); conn.disconnect()
            if (!loc) throw new RuntimeException('Yönlendirme konumu yok')
            cur = new java.net.URL(base, loc).toString()
            continue
        }
        if (code != 200) { conn.disconnect(); throw new RuntimeException('HTTP ' + code + ' — ' + cur) }
        long total = conn.getContentLengthLong()
        boolean ok = false
        try {
            def ins = conn.getInputStream()
            try {
                def os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(dest))
                long done = 0
                try {
                    byte[] buf = new byte[65536]; long last = 0; int r
                    while ((r = ins.read(buf)) > 0) {
                        os.write(buf, 0, r); done += r
                        if (done - last >= 4_000_000L) { last = done
                            appendInstallLog(String.format(java.util.Locale.US, '    %.1f MB%s', done / 1048576.0d, total > 0 ? String.format(java.util.Locale.US, ' / %.1f MB', total / 1048576.0d) : '')) }
                    }
                } finally { os.close() }
                if (total > 0 && done != total)
                    throw new RuntimeException('Eksik indirme (' + done + '/' + total + ' bayt): ' + dest.getName())
                ok = true
            } finally { ins.close() }
        } finally {
            conn.disconnect()
            if (!ok) { try { dest.delete() } catch (Throwable t) {} }
        }
        return
    }
}
// Zip-slip korumalı çıkarıcı
def unzipTo = { File zipFile, File destDir ->
    destDir.mkdirs()
    def destCanon = destDir.getCanonicalPath()
    def zis = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(zipFile)))
    try {
        def e; int cnt = 0
        while ((e = zis.getNextEntry()) != null) {
            def out = new File(destDir, e.getName())
            def oc = out.getCanonicalPath()
            if (oc != destCanon && !oc.startsWith(destCanon + File.separator)) throw new RuntimeException('Güvensiz zip girdisi: ' + e.getName())
            if (e.isDirectory()) out.mkdirs()
            else {
                if (out.getParentFile() != null) out.getParentFile().mkdirs()
                def os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(out))
                try { byte[] b = new byte[65536]; int r; while ((r = zis.read(b)) > 0) os.write(b, 0, r) } finally { os.close() }
                cnt++
            }
            zis.closeEntry()
        }
        appendInstallLog('    ' + cnt + ' dosya açıldı')
    } finally { zis.close() }
}
// ② hepatocyte-app deposunu indir + çıkar + köprü yolunu otomatik ayarla
def installRepo = {
    if (installBusyRef.getAndSet(true)) { appendInstallLog('(Kurulum sürüyor — bekleyin.)'); return }
    new Thread({
        try {
            appendInstallLog(''); appendInstallLog('② hepatocyte-app deposu indiriliyor…'); appendInstallLog('    ' + HEPATO_REPO_ZIP)
            def base = hepatoBase(); base.mkdirs()
            def zipF = new File(base, 'hepatocyte-app-main.zip')
            httpDownload(HEPATO_REPO_ZIP, zipF)
            appendInstallLog('    açılıyor → ' + base.getAbsolutePath())
            unzipTo(zipF, base)
            try { zipF.delete() } catch (Throwable t) {}
            def bf = hepatoBridgeFile()
            if (bf.isFile()) {
                prefs.put(PREF_BRIDGE, bf.getAbsolutePath()); try { prefs.flush() } catch (Throwable t) {}
                appendInstallLog('✓ Köprü betiği ayarlandı: ' + bf.getAbsolutePath())
            } else appendInstallLog('⚠ Köprü betiği beklenen yerde yok: ' + bf.getAbsolutePath())
            javafx.application.Platform.runLater { if (step.get() == 'CONFIG_INCOMPLETE') { step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() } }
        } catch (Throwable t) {
            appendInstallLog('✗ Depo indirilemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName()))
            appendInstallLog('   Elle indirin: ' + HEPATO_REPO_ZIP)
        } finally { installBusyRef.set(false) }
    } as Runnable).start()
}
// ③ Model ağırlığı — kısıtlı (🔒 talep üzerine): dosya seçici. modelUrl ayarlıysa indirir.
def selectModel = {
    def cfg = loadConfig()
    if (cfg.modelUrl?.trim()) {
        if (installBusyRef.getAndSet(true)) { appendInstallLog('(Kurulum sürüyor — bekleyin.)'); return }
        new Thread({
            try {
                appendInstallLog(''); appendInstallLog('③ Model indiriliyor…'); appendInstallLog('    ' + cfg.modelUrl.trim())
                def dest = new File(new File(hepatoBase(), 'weights'), 'baseline-1-jit.pth')
                httpDownload(cfg.modelUrl.trim(), dest)
                prefs.put(PREF_MODEL, dest.getAbsolutePath()); try { prefs.flush() } catch (Throwable t) {}
                appendInstallLog('✓ Model ayarlandı: ' + dest.getAbsolutePath())
                javafx.application.Platform.runLater { if (step.get() == 'CONFIG_INCOMPLETE') { step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() } }
            } catch (Throwable t) {
                appendInstallLog('✗ Model indirilemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName()))
            } finally { installBusyRef.set(false) }
        } as Runnable).start()
        return
    }
    // Dosya seçici (kısıtlı model bakımcıdan edinilir)
    def x = FileChoosers.promptForFile(stage, 'Model ağırlığı (.pth) seç')
    if (x != null) {
        prefs.put(PREF_MODEL, x.getAbsolutePath()); try { prefs.flush() } catch (Throwable t) {}
        appendInstallLog('✓ Model seçildi: ' + x.getAbsolutePath())
        if (step.get() == 'CONFIG_INCOMPLETE') { step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }
    }
}

// ── Alanları prefs'e yaz (CONFIG) ────────────────────────────────────────────
def textOf = { ref -> def f = ref.get(); return (f != null ? f.getText() : '').trim() }
def persistFields = {
    prefs.put(PREF_PYTHON, textOf(pyFieldRef))
    prefs.put(PREF_BRIDGE, textOf(bridgeFieldRef))
    prefs.put(PREF_MODEL,  textOf(modelFieldRef))
    def dv = deviceChoiceRef.get(); prefs.put(PREF_DEVICE, (dv != null && dv.getValue() != null) ? dv.getValue().toString() : 'auto')
    def ps = textOf(patchFieldRef);   prefs.put(PREF_PATCH,   ps ?: '512')
    def ma = textOf(minAreaFieldRef); prefs.put(PREF_MINAREA, ma ?: '15')
    def mr = textOf(minRoiFieldRef);  prefs.put(PREF_MINROI,  mr ?: '512')
    def mp = textOf(mppFieldRef);     prefs.put(PREF_MPP,     mp ?: '0.25')
    try { prefs.flush() } catch (Throwable ignore) {}
}

// ── Bağımlılık kontrolü ──────────────────────────────────────────────────────
def startSelftest = {
    persistFields()
    def cfg = loadConfig()
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile()) {
        errorTextRef.set('Önce Python ortamını ayarlayın (①).'); step.set('ERROR'); render(); return
    }
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Bağımlılık kontrolü'); step.set('CHECK_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython(selftestCmd(cfg), appendLine)
        javafx.application.Platform.runLater { selftestOkRef.set(r.ok); step.set('CHECK_DONE'); render() }
    }, 'AtolyeHepatocyte-Check')
    worker.setDaemon(true); worker.start()
}

// ── Segmentasyon akışı ────────────────────────────────────────────────────────
def startRun = {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def slidePath = resolveSlidePath(imageData)
    if (slidePath == null) {
        errorTextRef.set('Slayt yerel bir dosya değil — Python (openslide) açamaz.\nYerel diskteki bir SVS/TIFF slaytı açın.'); step.set('ERROR'); render(); return
    }
    // Çalışma dizini + Excel rapor yolu
    def project = QP.getProject()
    File workDir; File reportFile = null
    if (project != null && project.getPath() != null) {
        def projDir = project.getPath().getParent().toFile()
        workDir = new File(projDir, 'hepatocyte_tmp')
        def stamp = new java.text.SimpleDateFormat('yyyyMMdd_HHmmss').format(new Date())
        def stem = imageNameOf(imageData).replaceAll('[\\\\/:*?"<>|]', '_')
        def repDir = new File(new File(projDir, 'reports'), stem); repDir.mkdirs()
        reportFile = new File(repDir, stamp + '.xlsx')
    } else {
        workDir = new File(System.getProperty('java.io.tmpdir'), 'hepatocyte_tmp')
    }
    workDir.mkdirs()
    def runId = UUID.randomUUID().toString().take(8)
    def requestFile = new File(workDir, 'hepatocyte_request_' + runId + '.json')
    def outputFile  = new File(workDir, 'hepatocyte_result_' + runId + '.geojson')

    def built = buildRequest(imageData, slidePath, cfg, reportFile)
    if (built.nRois == 0) {
        errorTextRef.set('Segmentlenecek alan anotasyonu yok ya da hepsi çok küçük (bbox < ' + cfg.minRoi + ' px = bir yama).\nEn az bir yama boyu (≥' + cfg.minRoi + '×' + cfg.minRoi + ') alan anotasyonu çizin.'); step.set('ERROR'); render(); return
    }
    def targetAnns = targetAnnotations(imageData)
    try { writeJson(built.request, requestFile) }
    catch (Throwable t) { errorTextRef.set('İstek yazılamadı:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render(); return }

    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Hepatosit segmentasyonu çalışıyor…'); step.set('RUN_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        appendLine('Slayt: ' + slidePath)
        appendLine('İşlenen ROI: ' + built.nRois + (built.skippedSmall > 0 ? ('  (atlanan küçük: ' + built.skippedSmall + ')') : ''))
        def cmd = [cfg.python, cfg.bridge, requestFile.getAbsolutePath(), outputFile.getAbsolutePath()]*.toString()
        def r = runPython(cmd, appendLine)
        if (!r.ok) {
            javafx.application.Platform.runLater { errorTextRef.set('Segmentasyon başarısız (çıkış: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '')); step.set('ERROR'); render() }; return
        }
        javafx.application.Platform.runLater { busyLabelRef.set('Sonuçlar QuPath\'e aktarılıyor…'); step.set('BUSY'); render() }
        def imp = importResult(outputFile, QP.getCurrentImageData(), targetAnns)
        javafx.application.Platform.runLater {
            try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
            if (!imp.ok) { errorTextRef.set(imp.error); step.set('ERROR'); render() }
            else { resultTextRef.set(buildResultText(imageNameOf(imageData), built, imp, reportFile)); step.set('RESULT'); render() }
        }
    }, 'AtolyeHepatocyte-Run')
    worker.setDaemon(true); worker.start()
}

// ── Render: her durum değişiminde sahneyi sıfırdan kurar ────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def imageData = QP.getCurrentImageData()
    def cfg = loadConfig()

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10)
    center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)
    def actions = new ArrayList()

    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); lbl.setMaxWidth(Double.MAX_VALUE); center.getChildren().add(lbl) }
    def addMonoArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: '')
        ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(ta)
    }
    def addWarnLabel = { String txt ->
        def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); lbl.setMaxWidth(Double.MAX_VALUE)
        lbl.setStyle('-fx-text-fill: #b8860b; -fx-font-weight: bold;')
        center.getChildren().add(lbl)
    }
    def addLiveLog = { ->
        def la = logAreaRef.get()
        if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) }
    }

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('Hepatosit kurulumu gerekli')
        def miss = configMissing(cfg)
        addGuidance('Bu köprü ÜÇ ayrı kurulum adımı gerektirir; aşağıdaki ①②③ butonları bu adımları ÇALIŞTIRIR ' +
            '(terminal gerekmez). Eksik/geçersiz:\n  • ' + (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')))
        def resArea = new javafx.scene.control.TextArea(
            'KAYNAKLAR — ne · nereden · nereye:\n' +
            '① Python ortamı : ' + (cfg.python?.trim() ? (cfg.python + '  (kurulu)') :
                'uv venv + paketler → ' + new File(new File(atolyeDataRoot(), 'runtimes'), 'hepatocyte/.venv').getAbsolutePath()) + '\n' +
            '   (buton Atölye Python ortam yöneticisini açar; "Hepatocyte"yi kurun — python otomatik algılanır)\n' +
            '② hepatocyte-app deposu: ' + HEPATO_REPO_ZIP + '\n' +
            '   → ' + hepatoBridgeFile().getAbsolutePath() + '\n' +
            '③ Model (🔒 talep üzerine): baseline-1-jit.pth (~180 MB)\n' +
            '   → bakımcıdan edinin, sonra "③ Model seç" ile gösterin (genel indirme yok).')
        resArea.setEditable(false); resArea.setWrapText(false); resArea.setStyle(MONO); resArea.setPrefRowCount(8); resArea.setMaxHeight(180)
        center.getChildren().add(resArea)
        actions.add(navButton('① Python ortamı', { launchBundledScript('yardimci-python-ortam-yoneticisi.groovy') },
            'Atölye Python ortam yöneticisini açar → "Hepatocyte"yi kurun; python otomatik algılanır'))
        actions.add(navButton('② Depoyu indir', { installRepo() },
            'sbalci/hepatocyte-app ZIP indirir + açar, köprü betiğini otomatik ayarlar'))
        actions.add(navButton('③ Model seç', { selectModel() },
            'Bakımcıdan edindiğiniz .pth model ağırlığını gösterin (🔒 talep üzerine)'))
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
        def ilog = new javafx.scene.control.TextArea(installLogSnapshot())
        ilog.setEditable(false); ilog.setWrapText(false); ilog.setStyle(MONO); ilog.setPrefRowCount(7)
        javafx.scene.layout.VBox.setVgrow(ilog, javafx.scene.layout.Priority.ALWAYS)
        installLogAreaRef.set(ilog); center.getChildren().add(ilog)
    } else if (cur == 'CONFIG') {
        title.setText('Hepatosit yapılandırması')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def brField = new javafx.scene.control.TextField(cfg.bridge ?: '')
        def mdField = new javafx.scene.control.TextField(cfg.model ?: '')
        [pyField, brField, mdField].each { it.setPrefColumnCount(34) }
        def deviceChoice = new javafx.scene.control.ChoiceBox()
        DEVICE_OPTIONS.each { deviceChoice.getItems().add(it) }
        deviceChoice.setValue(DEVICE_OPTIONS.contains(cfg.device) ? cfg.device : 'auto')
        def psField = new javafx.scene.control.TextField(cfg.patch ?: '512')
        def maField = new javafx.scene.control.TextField(cfg.minArea ?: '15')
        def mrField = new javafx.scene.control.TextField(cfg.minRoi ?: '512')
        def mpField = new javafx.scene.control.TextField(cfg.targetMpp ?: '0.25')
        [psField, maField, mrField, mpField].each { it.setPrefColumnCount(8) }
        pyFieldRef.set(pyField); bridgeFieldRef.set(brField); modelFieldRef.set(mdField); deviceChoiceRef.set(deviceChoice)
        patchFieldRef.set(psField); minAreaFieldRef.set(maField); minRoiFieldRef.set(mrField); mppFieldRef.set(mpField)
        def browseFile = { f, cap -> def x = FileChoosers.promptForFile(stage, cap); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (python.exe):'), pyField, navButton('…', { browseFile(pyField, 'python.exe seç') }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Köprü (hepatocyte_qupath_inference.py):'), brField, navButton('…', { browseFile(brField, 'Köprü betiği seç') }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Model (.pth — 🔒):'), mdField, navButton('…', { browseFile(mdField, 'Model ağırlığı seç') }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Aygıt:'), deviceChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Yama boyu (px):'), psField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Min. poligon alanı (px²):'), maField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Min. ROI boyu (px):'), mrField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Hedef MPP (µm/px):'), mpField)
        center.getChildren().add(grid)
        addGuidance('Yama boyu 512, min. ROI 512 (bir yama), hedef MPP 0,25 — model eğitim ölçeği. ' +
            'Aygıt "auto": GPU varsa CUDA, yoksa CPU (CPU yavaştır). Model kısıtlıdır (🔒 talep üzerine).')
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('Bağımlılık kontrolü', { startSelftest() }, 'python -c ile import + model yüklenebilirlik kontrolü'))
        actions.add(navButton('Kaydet ▶', { persistFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
    } else if (cur == 'CHECK_RUNNING') {
        title.setText('Bağımlılık kontrolü çalışıyor…')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'CHECK_DONE') {
        title.setText(selftestOkRef.get() ? 'Bağımlılık kontrolü tamam ✅'
            : '⚠ Bağımlılık kontrolü BAŞARISIZ — yukarıdaki günlüğe bakın (Python / paket / model)')
        addLiveLog()
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText('Görüntü açık değil')
            addGuidance('Önce yerel diskteki bir karaciğer H&E slaydı açın ve segmentlemek istediğiniz bölge(ler) için alan anotasyonu çizin, sonra "⟳ Yenile".')
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
        } else {
            def slidePath = resolveSlidePath(imageData)
            def anns = targetAnnotations(imageData)
            int minRoi = parseIntOr(cfg.minRoi, 512)
            int usable = anns.count { def r = it.getROI(); (int) Math.ceil(r.getBoundsWidth()) >= minRoi && (int) Math.ceil(r.getBoundsHeight()) >= minRoi }
            title.setText('Hepatosit segmentasyonu — hazır')
            def sb = new StringBuilder()
            sb << "Slayt          : " << imageNameOf(imageData) << "\n"
            sb << "Slayt yolu     : " << (slidePath ?: '(yerel dosya değil — openslide açamaz)') << "\n"
            sb << "Python         : " << (cfg.python ?: '(ayarsız)') << "\n"
            sb << "Model          : " << (cfg.model ?: '(ayarsız — 🔒 talep üzerine)') << "\n"
            sb << "Aygıt          : " << cfg.device << "\n"
            sb << String.format(java.util.Locale.US, "Alan anotasyonu: %,d   ·   uygun (≥%d px): %,d%n", anns.size(), minRoi, usable)
            addMonoArea(sb.toString())
            addGuidance('Seçili alan anotasyonları işlenir (hiç seçili yoksa tüm alan anotasyonları). Model KENDİ eğitilmiş TorchScript modelidir; yalnız tahmin yapar.')
            if (slidePath == null) addWarnLabel('⚠ Slayt yerel bir dosya değil — Python (openslide) açamaz. Yerel SVS/TIFF açın.')
            boolean canRun = configComplete(cfg) && (slidePath != null) && usable >= 1
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
            def runBtn = navButton('Segmentle ▶', { startRun() }, 'Seçili/çizili alan anotasyonlarını hepatosit modeliyle segmentler')
            runBtn.setDisable(!canRun)
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING') {
        title.setText(runPhaseRef.get())
        addGuidance('Python köprüsü koşuyor (slaytı openslide ile açar, modeli çalıştırır). Çıktı aşağıda akıyor. Zaman aşımı: ' + PYTHON_TIMEOUT_SECONDS + ' sn.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'BUSY') {
        title.setText(busyLabelRef.get()); addGuidance('Lütfen bekleyin…'); center.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅')
        addMonoArea(resultTextRef.get())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('↻ Yeniden çalıştır', { step.set('READY'); render() }))
    } else { // ERROR
        title.setText('Hata')
        addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Geri', { step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
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
    stage.setScene(new javafx.scene.Scene(root, 880, 660))
}

// ── Açılış durumu ───────────────────────────────────────────────────────────
step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Hepatosit segmentasyonu sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ Hepatosit segmentasyonu sihirbazı açıldı."
