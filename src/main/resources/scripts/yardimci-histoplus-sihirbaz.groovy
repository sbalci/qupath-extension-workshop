/**
 * Yardımcı - HistoPLUS hücre segmentasyonu + sınıflama sihirbazı (H&E; seçili alanda)
 * -----------------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   QuPath'te ÇİZİP SEÇTİĞİNİZ alan(lar)da HistoPLUS'u (Owkin; CellViT mimarisi + H0-mini temel model
 *   kodlayıcısı) çalıştırır: çekirdekleri segmente eder ve 13 hücre tipinden (+ makalede tanımlanmamış
 *   "Minor Stromal Cell" çıkışı) birine atar. Sihirbaz seçili alanları birleştirip GeoJSON olarak yazar →
 *   Python köprüsü (histoplus/histoplus_runner.py) upstream'in KENDİ yapı taşlarını süreç içinde çağırır →
 *   çekirdek konturları sınıflı TESPİT (detection) nesneleri olarak eklenir ve sınıf başına SAYIM + % +
 *   YOĞUNLUK üretilir.
 *
 * NEDEN KÖPRÜ, NEDEN upstream CLI DEĞİL:
 *   Upstream `histoplus` komutu / extract() Windows'ta çalışmaz (issue #29: çok-süreçli kuyruk modeli
 *   pickle etmeye çalışır; ayrıca son-işleme açık .npz dosyasını silmeye çalışıp WinError 32 verir) ve
 *   bölge seçimi yoktur (tüm slayt, Otsu doku tespiti). Köprü aynı upstream fonksiyonlarını aynı sırayla
 *   çağırır, yalnız bu iki tesisat adımını bellek içinde yapar ve karoları SEÇİLİ ALANDAN üretir.
 *
 * KURULUM (sihirbaz adım adım yönlendirir):
 *   ① Python ortamı : Atölye Python ortam yöneticisi → "HistoPLUS — H&E hücre segmentasyonu" (~6 GB)
 *   ② Ağırlıklar    : lisans onayı + iki .pt dosyası (40× ve 20×, ~285 MB) — yerel klasörden (ör. Hugging
 *                     Face deposunun kopyası) ya da KENDİ HF hesabınızla indirerek; SHA-256 bir kez
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Seçili alandaki model-tespitli çekirdeklerin sınıf başına sayımı, yüzdesi ve yoğunluğu (hücre/mm²).
 *   • "Sınıf oy oranı": çekirdek piksellerinden çoğunluk sınıfına oy verenlerin kesri — OLASILIK DEĞİLDİR.
 *   • Sınıf adları modelin eğitim veri setinin kategorileridir; tanı değildir. Klinik yorum ÜRETMEZ.
 *   • µm/px değerini SLAYT DOSYASINDAN okur; dosyada yoksa ÇALIŞTIRMAZ (upstream sessizce 0.25 varsayardı).
 *
 * LİSANS SINIRI:
 *   HistoPLUS kodu ve ağırlıkları CC BY-NC-ND 4.0 (bu eklenti Apache-2.0) → kod ve ağırlıklar
 *   PAKETLENMEZ/KOPYALANMAZ/DEĞİŞTİRİLMEZ; kod sabit commit'ten (576b94e) kullanıcının "histoplus"
 *   ortamına çalışma anında kurulur. Ağırlıklar kapılıdır (Owkin-Bioptimus/histoplus): yalnız ticari
 *   olmayan akademik araştırma, model dağıtılmaz, her kullanıcı KENDİ hesabıyla kaydolur.
 *
 * ÇIKTI:
 *   • Her çekirdek için "<Sınıf> (HistoPLUS)" sınıflı poligon TESPİTİ + "Alan µm²" + "HistoPLUS: sınıf oy oranı"
 *   • Her seçili anotasyona: "HistoPLUS: <sınıf> (n) / (%) / (/mm²)" ölçümleri
 *   • Kilitli "HistoPLUS hücre sınıfları özeti — <model>" anotasyonu
 *   • Çalışma klasöründe upstream biçiminde cell_masks.json + histoplus_cells.geojson + histoplus_run.json
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Adjadj B, Bannier P-A, Horent G ve ark. Toward comprehensive cellular characterization of H&E
 *     slides. J Pathol Inform 2026;22:100696 — doi:10.1016/j.jpi.2026.100696 (ön baskı: arXiv:2508.09926)
 *   • Hörst F ve ark. CellViT, Med Image Anal 2024 — doi:10.1016/j.media.2024.103143
 *   • Kod: https://github.com/owkin/histoplus · Ağırlık: https://huggingface.co/Owkin-Bioptimus/histoplus
 *
 * API: RoiTools.union + ROI.getGeometry (JTS) → GeoJSON (Gson); ROIs.createPolygonROI +
 *      PathObjects.createDetectionObject (QuPath 0.6.0+); GeoJSON ayrıştırma com.google.gson.JsonParser.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane
import com.google.gson.JsonParser
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ───────────────────────────────────────────────────────────────
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def SUMMARY_NAME = 'HistoPLUS hücre sınıfları özeti'
def ENV_ID       = 'histoplus'
def RUNNER_REL   = 'python/histoplus/histoplus_runner.py'
def CLASS_SUFFIX = ' (HistoPLUS)'
def MEAS_PREFIX  = 'HistoPLUS: '
def VOTE_MEAS    = 'HistoPLUS: sınıf oy oranı'
def RUNNER_VOTE  = 'HistoPLUS: sinif oy orani'   // köprünün GeoJSON'da yazdığı ASCII anahtar
def HF_REPO      = 'Owkin-Bioptimus/histoplus'
def LICENSE_TOKEN = 'CC-BY-NC-ND-4.0@cde2eee'    // onay, bu lisans + ağırlık revizyonuna bağlıdır
double MPP_DIFF_WARN     = 0.05   // QuPath ile dosyadaki µm/px farkı bu oranı aşarsa uyar
double LEVEL_DIFF_WARN   = 0.10   // slayt seviyesi ile modelin µm/px'i arası fark (upstream %20'ye kadar kabul eder)
double AREA_WARN_GPU_MM2 = 25.0d  // ölçüm: RTX A4000, batch 4 → ~12 sn/mm² (25 mm² ≈ 5 dk)
double AREA_WARN_CPU_MM2 = 0.25d  // ölçüm: CPU ~7 dk/mm² (0.25 mm² ≈ 2 dk)
def WEIGHTS = [
    '40x': [file:'histoplus_cellvit_segmentor_40x.pt', mpp:0.25, label:'40× (0.25 µm/px)'],
    '20x': [file:'histoplus_cellvit_segmentor_20x.pt', mpp:0.5,  label:'20× (0.5 µm/px)'],
]
// HistoPLUS etiketi (upstream class_mapping, OLDUĞU GİBİ) → Türkçe ad + görüntüleme rengi.
// Renkler Classpose/HoVer-NeXt sihirbazlarındaki aynı hücre tipleriyle eşleşir.
def CLASSES = [
    [key:'Cancer cell',        tr:'Kanser hücresi',         rgb:[200, 0, 120]],
    [key:'Lymphocytes',        tr:'Lenfosit',               rgb:[40, 80, 230]],
    [key:'Fibroblasts',        tr:'Fibroblast',             rgb:[240, 170, 90]],
    [key:'Plasmocytes',        tr:'Plazma hücresi',         rgb:[0, 120, 60]],
    [key:'Eosinophils',        tr:'Eozinofil',              rgb:[0, 190, 200]],
    [key:'Neutrophils',        tr:'Nötrofil',               rgb:[0, 200, 0]],
    [key:'Macrophages',        tr:'Makrofaj',               rgb:[255, 120, 0]],
    [key:'Muscle Cell',        tr:'Düz kas hücresi',        rgb:[170, 90, 40]],
    [key:'Endothelial Cell',   tr:'Endotel hücresi',        rgb:[0, 160, 255]],
    [key:'Red blood cell',     tr:'Eritrosit',              rgb:[150, 0, 0]],
    [key:'Epithelial',         tr:'Doğal epitelyal hücre',  rgb:[255, 150, 180]],
    [key:'Apoptotic Body',     tr:'Apoptotik cisim',        rgb:[60, 60, 0]],
    [key:'Mitotic Figures',    tr:'Mitotik figür',          rgb:[255, 0, 255]],
    // Modelin 15. çıkışı: makalenin 13 sınıfında YOK, tanımı yayımlanmamış → Türkçe ad UYDURULMAZ.
    [key:'Minor Stromal Cell', tr:'Minor Stromal Cell',     rgb:[200, 200, 60], undocumented:true],
]
def classByKey = [:]
CLASSES.each { classByKey[it.key] = it }
def trOf = { String key -> (classByKey[key]?.tr) ?: key }
def UNDOCUMENTED_NOTE = '"Minor Stromal Cell" modelin 15. çıkışıdır; makalenin 13 sınıfında yer almaz ve tanımı yayımlanmamıştır — ayrı raporlayın, yorumlamayın.'

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır) ──
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/histoplus')
def PREF_RUNNER   = 'runner'
def PREF_WORK     = 'workDir'
def PREF_SOURCE   = 'modelSource'   // 'folder' | 'download'
def PREF_FOLDER   = 'modelFolder'
def PREF_DEVICE   = 'device'        // 'auto' | 'cuda' | 'cpu'
def PREF_BATCH    = 'batchSize'
def PREF_THREADS  = 'ppThreads'
def PREF_CUDA     = 'selftestCuda'  // '1' | '0' | '' (henüz denetlenmedi)
def PREF_PIPE     = 'selftestPipeline'  // selftest damgası: upstream yapı taşları içe aktarılabiliyor mu
def PREF_LICENSE  = 'licenseAck'    // LICENSE_TOKEN ya da ''
def putPref = { String k, String v -> prefs.put(k, v ?: ''); try { prefs.flush() } catch (Throwable ignore) {} }

// ── Atölye veri kökü (env yöneticisiyle PAYLAŞILAN) + önbellek yönlendirme ──
def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
// HF_HOME: huggingface_hub önbelleği; HISTOWMICS_HOME: upstream'in kendi önbelleği (köprü kullanmaz ama
// ~/.histoplus'a yazılmasın diye veri köküne yönlendirilir); TORCH_HOME: timm/torch hub.
def applyCacheEnv = { pb, Map extra ->
    try {
        def cache = new File(atolyeDataRoot(), 'cache'); cache.mkdirs()
        def env = pb.environment()
        env.put('HF_HOME', new File(cache, 'huggingface').getAbsolutePath())
        env.put('TORCH_HOME', new File(cache, 'torch').getAbsolutePath())
        env.put('HISTOWMICS_HOME', new File(cache, 'histoplus').getAbsolutePath())
        env.put('PYTHONIOENCODING', 'utf-8')
        (extra ?: [:]).each { k, v -> env.put(k.toString(), v.toString()) }
    } catch (Throwable ignore) {}
}

// ── Otomatik tespit: histoplus ortamı + histoplus_runner.py köprüsü ──────────
def detectPython = { ->
    try {
        def rec = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('py.' + ENV_ID, '')
        if (rec?.trim() && new File(rec.trim()).isFile()) return rec.trim()
    } catch (Throwable ignore) {}
    def at = new File(new File(atolyeDataRoot(), 'runtimes'), ENV_ID + '/.venv')
    def aw = new File(at, 'Scripts/python.exe'); def an = new File(at, 'bin/python')
    if (aw.isFile()) return aw.getAbsolutePath()
    if (an.isFile()) return an.getAbsolutePath()
    return ''
}
def detectRunner = { ->
    def project = QP.getProject()
    def roots = []
    if (project != null && project.getPath() != null) {
        def handson = project.getPath().getParent().toFile()
        roots << handson
        if (handson.getParentFile() != null) roots << new File(handson.getParentFile(), 'handson')
    }
    for (r in roots) {
        def f = new File(r, RUNNER_REL)
        if (f.isFile()) return f.getAbsolutePath()
    }
    return ''
}

// ── Ağırlıklar: yerel klasör ya da veri köküne indirme; SHA-256 sonucu önbellekte ──
def defaultModelFolder = { -> new File(new File(new File(atolyeDataRoot(), 'cache'), 'histoplus'), 'models') }
def modelFolderOf = { cfg ->
    if (cfg.source == 'download') return defaultModelFolder()
    return cfg.folder?.trim() ? new File(cfg.folder.trim()) : null
}
def weightFileOf = { cfg, String key -> def d = modelFolderOf(cfg); return d == null ? null : new File(d, WEIGHTS[key].file) }
// 285 MB dosyanın SHA-256'sı bir kez hesaplanır: sonuç yol + boyut + değişiklik zamanıyla saklanır.
// (Köprü her çalıştırmada yeniden doğrular; bu önbellek yalnız kurulum ekranının durum satırı içindir.)
def verifyKeyOf   = { File f -> 'sha.' + Integer.toHexString(f.getAbsolutePath().hashCode()) }
def verifyStampOf = { File f -> f.getAbsolutePath() + '|' + f.length() + '|' + f.lastModified() }
def isVerified    = { File f -> f != null && f.isFile() && prefs.get(verifyKeyOf(f), '') == verifyStampOf(f) }
def markVerified  = { File f -> if (f != null && f.isFile()) putPref(verifyKeyOf(f), verifyStampOf(f)) }

// ── Yapılandırma ────────────────────────────────────────────────────────────
def loadConfig = { ->
    def rn = prefs.get(PREF_RUNNER, ''); if (!rn?.trim() || !new File(rn.trim()).isFile()) rn = detectRunner()
    def src = prefs.get(PREF_SOURCE, 'download'); if (!['folder', 'download'].contains(src)) src = 'download'
    def dv = prefs.get(PREF_DEVICE, 'auto'); if (!['auto', 'cuda', 'cpu'].contains(dv)) dv = 'auto'
    [ python   : detectPython(),
      runner   : rn,
      workDir  : prefs.get(PREF_WORK, ''),
      source   : src,
      folder   : prefs.get(PREF_FOLDER, ''),
      device   : dv,
      batchSize: prefs.get(PREF_BATCH, '4'),
      ppThreads: prefs.get(PREF_THREADS, '2'),
      cuda     : prefs.get(PREF_CUDA, ''),
      pipeline : prefs.get(PREF_PIPE, ''),
      license  : prefs.get(PREF_LICENSE, '') ]
}
// Selftest damgası: python yolu + kurulu histoplus/__init__.py değişiklik zamanı (ortam yeniden kurulunca geçersiz olur).
def histoplusInitOf = { cfg ->
    if (!cfg.python?.trim()) return null
    def py = new File(cfg.python.trim())
    def venv = py.getParentFile()?.getParentFile()
    if (venv == null) return null
    def site = py.getParentFile().getName().equalsIgnoreCase('Scripts') ? new File(new File(venv, 'Lib'), 'site-packages')
                                                                          : new File(new File(new File(venv, 'lib'), 'python3.12'), 'site-packages')
    return new File(new File(site, 'histoplus'), '__init__.py')
}
def pipelineStampOf = { cfg -> def f = histoplusInitOf(cfg); return (f != null && f.isFile()) ? ('1|' + cfg.python.trim() + '|' + f.lastModified()) : '' }
def setupStatus = { cfg ->
    def verified = WEIGHTS.keySet().findAll { k -> isVerified(weightFileOf(cfg, k)) }
    def present  = WEIGHTS.keySet().findAll { k -> def f = weightFileOf(cfg, k); f != null && f.isFile() }
    [ pythonOk  : cfg.python?.trim() ? new File(cfg.python).isFile() : false,
      runnerOk  : cfg.runner?.trim() ? new File(cfg.runner).isFile() : false,
      envOk     : cfg.pipeline ? (cfg.pipeline == pipelineStampOf(cfg)) : false,
      licenseOk : cfg.license == LICENSE_TOKEN,
      present   : present,
      verified  : verified,
      modelOk   : !verified.isEmpty() ]
}
def setupComplete = { cfg -> def s = setupStatus(cfg); return s.pythonOk && s.runnerOk && s.envOk && s.licenseOk && s.modelOk }
def parseIntOr = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }

def imageNameOf = { imageData -> (imageData.getServer().getMetadata().getName() ?: 'slide').replaceAll(/\.[^.\/\\]+$/, '') }
def safeName = { String s ->
    def original = s ?: 'slide'
    def cleaned = original.replaceAll(/[^A-Za-z0-9._-]+/, '_')
    return cleaned == original ? cleaned : (cleaned + '_' + Integer.toHexString(original.hashCode()))
}
// Çalışma klasörü: ayar → <proje>/histoplus_work/<görüntü> → <slayt klasörü>/histoplus_work/<görüntü> → geçici klasör.
def resolveWorkDir = { cfg, imageData ->
    def name = safeName(imageNameOf(imageData))
    def wd = cfg.workDir?.trim()
    if (wd) return new File(new File(wd), name)
    def project = QP.getProject()
    if (project != null && project.getPath() != null)
        return new File(new File(project.getPath().getParent().toFile(), 'histoplus_work'), name)
    try {
        def uris = imageData.getServer().getURIs()
        if (uris != null && !uris.isEmpty()) {
            def uri = uris.iterator().next()
            if ('file'.equals(uri.getScheme())) {
                def f = java.nio.file.Paths.get(uri).toFile()
                if (f.getParentFile() != null) return new File(new File(f.getParentFile(), 'histoplus_work'), name)
            }
        }
    } catch (Throwable ignore) {}
    return new File(new File(System.getProperty('java.io.tmpdir'), 'histoplus_work'), name)
}
def pixelMicrons = { imageData ->
    try {
        def cal = imageData.getServer().getPixelCalibration()
        if (cal != null && cal.hasPixelSizeMicrons())
            return [pw: cal.getPixelWidthMicrons(), ph: cal.getPixelHeightMicrons()]
    } catch (Throwable ignore) {}
    return null
}

// ── Headless ────────────────────────────────────────────────────────────────
if (isHeadless) {
    def cfg = loadConfig()
    def st = setupStatus(cfg)
    println "HistoPLUS sihirbazı: python=${cfg.python ?: '(yok)'} runner=${cfg.runner ?: '(yok)'} kaynak=${cfg.source}"
    println "① Python ortamı: ${st.pythonOk ? 'VAR' : 'yok'} · köprü: ${st.runnerOk ? 'VAR' : 'yok'} · denetim: ${st.envOk ? 'tamam' : 'denetlenmedi/değişmiş'}"
    println "② Lisans onayı: ${st.licenseOk ? 'VAR' : 'yok'} · ağırlıklar (doğrulanmış): ${st.verified ? st.verified.join(', ') : 'yok'} — ${modelFolderOf(cfg)?.getAbsolutePath() ?: '(klasör seçilmedi)'}"
    println "Bu sihirbaz QuPath arayüzü gerektirir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def jobOkRef      = new java.util.concurrent.atomic.AtomicBoolean(true)
def jobTitleRef   = new java.util.concurrent.atomic.AtomicReference('')
def processRef    = new java.util.concurrent.atomic.AtomicReference(null)
// Ön denetim (runner precheck) durumu — envReturnHook ve refreshPrecheck bunlara başvurur; bir closure
// SONRADAN tanımlanacak bir değişkene bağlanamayacağı için erken bildirilir.
def precheckRef     = new java.util.concurrent.atomic.AtomicReference(null)   // [path: String, pc: Map, text: String]
def precheckBusy    = new java.util.concurrent.atomic.AtomicBoolean(false)
def precheckProcRef = new java.util.concurrent.atomic.AtomicReference(null)
// Nesil sayacı: "⟳ Yenile" ARTIRIR; öldürülmüş/yarışan bir ön denetimin sonucu önbelleğe düşemez.
def precheckGenRef = new java.util.concurrent.atomic.AtomicLong(0L)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def progressRef   = new java.util.concurrent.atomic.AtomicReference(null)   // RUN_RUNNING'deki ilerleme çubuğu
def runPhaseRef   = new java.util.concurrent.atomic.AtomicReference('')
def busyLabelRef  = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def workDirRef    = new java.util.concurrent.atomic.AtomicReference(null)
def tokenFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def render

def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text)
    b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    return b
}
def busyBar = { -> def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb }
def copyToClipboard = { String txt ->
    def cb = javafx.scene.input.Clipboard.getSystemClipboard()
    def content = new javafx.scene.input.ClipboardContent(); content.putString(txt ?: ""); cb.setContent(content)
}
def openFolder = { File f -> try { if (f != null && f.isDirectory() && java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(f) } catch (Throwable ignore) {} }

// ── Çalışma günlüğü ─────────────────────────────────────────────────────────
def runLog      = new StringBuilder()
def resetLog    = { -> synchronized (runLog) { runLog.setLength(0) } }
def appendLog   = { String ln -> synchronized (runLog) { runLog.append(ln == null ? '' : ln).append('\n') } }
def logSnapshot = { -> synchronized (runLog) { return runLog.toString() } }
def saveLogInteractive = {
    def txt = logSnapshot()
    if (!txt?.trim()) { Dialogs.showInfoNotification('Günlük', 'Kaydedilecek günlük yok.'); return }
    try {
        def suggested = new File(workDirRef.get() ?: new File(System.getProperty('user.home')), 'histoplus_wizard.log')
        def f = qupath.fx.dialogs.FileChoosers.promptToSaveFile(stage, 'Günlüğü kaydet', suggested,
            new javafx.stage.FileChooser.ExtensionFilter('Günlük (*.log, *.txt)', '*.log', '*.txt'))
        if (f != null) { f.setText(txt, 'UTF-8'); Dialogs.showInfoNotification('Günlük', 'Kaydedildi: ' + f.getAbsolutePath()) }
    } catch (Throwable t) { Dialogs.showErrorMessage('Günlük', 'Kaydedilemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName())) }
}

// Süreç AĞACINI sonlandır (köprünün olası alt süreçleri GPU'da yetim kalmasın).
def killTree = { proc ->
    if (proc == null) return
    try { proc.descendants().forEach({ h -> try { h.destroyForcibly() } catch (Throwable ignore) {} } as java.util.function.Consumer) } catch (Throwable ignore) {}
    try { proc.destroyForcibly() } catch (Throwable ignore) {}
}

// ── Python süreci → satır akışı (zaman aşımı YOK: büyük alanlar ve indirme uzun sürebilir) ──
def runPython = { List cmd, Map extraEnv, Closure onLine ->
    def pb = new ProcessBuilder(cmd.collect { it.toString() }); pb.redirectErrorStream(true)
    applyCacheEnv(pb, extraEnv)
    def proc
    try { proc = pb.start() }
    catch (Throwable e) { return [ok: false, exitCode: -1, error: 'Python başlatılamadı: ' + (e.getMessage() ?: e.getClass().getSimpleName()), lines: []] }
    processRef.set(proc)
    def lines = new ArrayList()
    try {
        def reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
        String line
        while ((line = reader.readLine()) != null) {
            lines.add(line)
            onLine(line)
            if (cancelledRef.get()) break
        }
        reader.close()
    } catch (Throwable ignore) {}
    if (cancelledRef.get()) { killTree(proc); return [ok: false, exitCode: -3, error: 'İptal edildi', lines: lines] }
    int code
    try { code = proc.waitFor() }
    catch (InterruptedException ie) { killTree(proc); return [ok: false, exitCode: -3, error: 'İptal edildi', lines: lines] }
    return [ok: (code == 0), exitCode: code, lines: lines]
}
def tailOf = { List lines, int n -> lines.size() <= n ? lines.join('\n') : lines.subList(lines.size() - n, lines.size()).join('\n') }

// ── Kurulum işleri (selftest / doğrulama / indirme): tek biçim, canlı günlük ──
def startJob = { String title, String runState, List args, Map extraEnv, Closure onFinish ->
    def cfg = loadConfig()
    def st = setupStatus(cfg)
    if (!st.pythonOk || !st.runnerOk) { errorTextRef.set('Önce ① Python ortamını kurun ve köprü betiğini bulun.'); step.set('ERROR'); render(); return }
    cancelledRef.set(false); resetLog()
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(true); la.setStyle(MONO)
    logAreaRef.set(la); jobTitleRef.set(title)
    step.set(runState); render()
    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython([cfg.python, cfg.runner] + args, extraEnv, appendLine)
        if (r.error) appendLine('HATA: ' + r.error)
        appendLine('# Çıkış kodu: ' + r.exitCode)
        try { onFinish(r, cfg) } catch (Throwable t) { appendLine('HATA (sonuç işlenemedi): ' + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')) }
        javafx.application.Platform.runLater { jobOkRef.set(r.ok); step.set(runState == 'CHECK_RUNNING' ? 'CHECK_DONE' : 'DL_DONE'); render() }
    }, 'AtolyeHistoPLUS-Job')
    worker.setDaemon(true); worker.start()
}
// "VERIFY <anahtar> ok=1 path=<yol>" satırlarındaki dosyaları doğrulanmış işaretle.
def markVerifiedFromLines = { List lines ->
    lines.each { ln ->
        def m = (ln.trim() =~ /^VERIFY (40x|20x) ok=1 path=(.+)$/)
        if (m.find()) markVerified(new File(m.group(2).trim()))
    }
}
def startSelftest = {
    startJob('Bağımlılık denetimi (selftest)', 'CHECK_RUNNING', ['selftest'], [:], { r, cfg ->
        def cudaLine = r.lines.find { it.startsWith('CUDA cuda=') }
        if (cudaLine != null) putPref(PREF_CUDA, cudaLine.substring('CUDA cuda='.length()).trim())
        if (r.exitCode != -1 && r.exitCode != -3)
            putPref(PREF_PIPE, r.lines.contains('PIPELINE ok=1') ? pipelineStampOf(cfg) : '0')
    })
}
def startVerify = {
    def cfg = loadConfig(); def dir = modelFolderOf(cfg)
    if (dir == null) { errorTextRef.set('Önce ağırlık klasörünü seçin.'); step.set('ERROR'); render(); return }
    startJob('Ağırlıklar doğrulanıyor (SHA-256)', 'CHECK_RUNNING', ['verify', '--dir', dir.getAbsolutePath()], [:], { r, c -> markVerifiedFromLines(r.lines) })
}
def startDownload = { String token ->
    if (prefs.get(PREF_LICENSE, '') != LICENSE_TOKEN) { errorTextRef.set('Önce ② lisans onay kutusunu işaretleyin.'); step.set('ERROR'); render(); return }
    def dir = defaultModelFolder(); dir.mkdirs()
    def extra = (token?.trim()) ? [HF_TOKEN: token.trim()] : [:]
    startJob('Ağırlıklar indiriliyor (Hugging Face, kapılı; 2 × ~285 MB)', 'DL_RUNNING', ['download', '--dir', dir.getAbsolutePath()], extra, { r, c -> markVerifiedFromLines(r.lines) })
}

// ── Ortam yöneticisinden bu sihirbaza dönüş ──────────────────────────────────
// "Python ortam yöneticisi…" düğmesi yöneticiyi bu kancayla (`atolyeReturnHook`) açar. Kurulum bitince
// yöneticideki "Sihirbaza dön ▶" (ya da pencereyi kapatmak) bu pencereyi öne getirir ve yapılandırmayı
// yeniden okur. Çalışan bir işlem sürerken ekran değiştirilmez; yalnız pencere öne gelir.
def envReturnHook = [
    envId   : ENV_ID,
    wizard  : 'HistoPLUS hücre segmentasyonu',
    onReturn: { reopen ->
        javafx.application.Platform.runLater {
            if (stage == null || (!stage.isShowing() && !reopen)) return
            try {
                if (['CONFIG_INCOMPLETE', 'READY', 'CHECK_DONE', 'DL_DONE', 'ERROR'].contains(step.get())) {
                    boolean ready = setupComplete(loadConfig())
                    if (ready) precheckRef.set(null)
                    step.set(ready ? 'READY' : 'CONFIG_INCOMPLETE'); render()
                }
                if (stage.isIconified()) stage.setIconified(false)
                if (!stage.isShowing()) stage.show()
                stage.toFront(); stage.requestFocus()
            } catch (Throwable t) {
                Dialogs.showErrorMessage('Sihirbaza dönüş', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
            }
        }
    }
]
def launchEnvManager = { ->
    new Thread({
        try {
            def res = 'yardimci-python-ortam-yoneticisi.groovy'
            def url = null
            try { url = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getResource('/scripts/' + res) } catch (Throwable t) {}
            if (url == null) url = this.getClass().getResource('/scripts/' + res)
            if (url == null) {
                javafx.application.Platform.runLater { Dialogs.showInfoNotification('Betik bulunamadı',
                    'Menüden açın: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi') }
                return
            }
            def cl = this.getClass().getClassLoader()
            try { cl = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getClassLoader() } catch (Throwable t) {}
            def shellBinding = new Binding()
            shellBinding.setVariable('atolyeReturnHook', envReturnHook)
            new GroovyShell(cl, shellBinding).evaluate(url.getText('UTF-8'), res)
        } catch (Throwable t) {
            javafx.application.Platform.runLater { Dialogs.showErrorMessage('Açılamadı', (t.getMessage() ?: t.getClass().getSimpleName())) }
        }
    } as Runnable).start()
}

// ── Alan seçimi: YALNIZ seçili alan anotasyonları (tüm anotasyonlara sessiz geri düşüş YOK) ──
def notSummary = { ann -> ann.getName() == null || !ann.getName().startsWith(SUMMARY_NAME) }
def areaAnnotationsOf = { imageData ->
    def picked = imageData.getHierarchy().getSelectionModel().getSelectedObjects()
    return new ArrayList(picked.findAll { it.isAnnotation() && it.hasROI() && it.getROI().isArea() && notSummary(it) })
}
// Çakışan alanlar birleştirilir: aynı karo iki kez işlenmez, sayımlar birleşik alanda yapılır.
def unionRoiOf = { List targets -> targets.size() == 1 ? targets[0].getROI() : qupath.lib.roi.RoiTools.union(targets.collect { it.getROI() }) }
def areaMm2Of = { roi, double pw, double ph -> roi.getArea() * pw * ph / 1_000_000.0d }

// ── ROI → GeoJSON (köprünün --roi biçimi) ────────────────────────────────────
def ringJson = { coords ->
    def arr = new com.google.gson.JsonArray()
    coords.each { c -> def p = new com.google.gson.JsonArray(); p.add((double) c.x); p.add((double) c.y); arr.add(p) }
    return arr
}
def polygonJson = { poly ->
    def rings = new com.google.gson.JsonArray()
    rings.add(ringJson(poly.getExteriorRing().getCoordinates()))
    for (int i = 0; i < poly.getNumInteriorRing(); i++) rings.add(ringJson(poly.getInteriorRingN(i).getCoordinates()))
    return rings
}
// Tek Feature, (Multi)Polygon, TAM çözünürlük QuPath koordinatları. OpenSlide sınır ofseti (bounds)
// KÖPRÜDE iki yönde uygulanır (ROI'ye eklenir, çıktıdan çıkarılır) — burada ofset EKLEMEYİN.
def writeRoiGeoJson = { roi, File f ->
    def geom = roi.getGeometry()
    def polys = []
    for (int i = 0; i < geom.getNumGeometries(); i++) {
        def g = geom.getGeometryN(i)
        if (g instanceof org.locationtech.jts.geom.Polygon && !g.isEmpty()) polys << g
    }
    if (polys.isEmpty()) return false
    def gj = new com.google.gson.JsonObject()
    if (polys.size() == 1) {
        gj.addProperty('type', 'Polygon'); gj.add('coordinates', polygonJson(polys[0]))
    } else {
        gj.addProperty('type', 'MultiPolygon')
        def mp = new com.google.gson.JsonArray(); polys.each { mp.add(polygonJson(it)) }; gj.add('coordinates', mp)
    }
    def feat = new com.google.gson.JsonObject()
    feat.addProperty('type', 'Feature'); feat.add('geometry', gj); feat.add('properties', new com.google.gson.JsonObject())
    def features = new com.google.gson.JsonArray(); features.add(feat)
    def fc = new com.google.gson.JsonObject(); fc.addProperty('type', 'FeatureCollection'); fc.add('features', features)
    if (f.getParentFile() != null) f.getParentFile().mkdirs()
    f.setText(fc.toString(), 'UTF-8')
    return true
}

// ── Slayt dosyası: köprü dosyayı DOĞRUDAN (OpenSlide) okur → yerel, türetilmemiş tek dosya olmalı ──
def slideFileOf = { imageData ->
    def server = imageData.getServer()
    boolean derived = (server instanceof qupath.lib.images.servers.TransformingImageServer) ||
                      (server instanceof qupath.lib.images.servers.SparseImageServer) ||
                      (server instanceof qupath.lib.images.servers.ConcatChannelsImageServer) ||
                      (server instanceof qupath.lib.images.servers.ZConcatenatedImageServer)
    if (derived) return [ok: false, error: 'Bu görüntü kırpılmış/döndürülmüş ya da birleştirilmiş bir görünüm. HistoPLUS slayt dosyasını doğrudan okur; koordinatlar dosyayla eşleşmez — özgün dosyayı açın.']
    def uris = null
    try { uris = server.getURIs() } catch (Throwable ignore) {}
    if (uris == null || uris.size() != 1) return [ok: false, error: 'Görüntü tek bir yerel slayt dosyasına karşılık gelmiyor (HistoPLUS dosyayı doğrudan okur).']
    def uri = uris.iterator().next()
    if (!'file'.equals(uri.getScheme())) return [ok: false, error: 'Görüntü yerel bir dosya değil (' + uri.getScheme() + '). HistoPLUS yalnız yerel slayt dosyası okur (OMERO/URL/DZI desteklenmez).']
    // Paths.get(URI) file://host/share/... UNC yollarını korur; new File(uri) host bileşenini sessizce atar.
    def f
    try {
        f = java.nio.file.Paths.get(uri).toFile()
    } catch (Throwable t) {
        def host = uri.getHost()
        def rawPath = (uri.getPath() ?: uri.getSchemeSpecificPart() ?: '').replace((char) '/', (char) '\\')
        f = (host != null && !host.trim().isEmpty()) ? new File('\\\\' + host + rawPath) : new File(rawPath)
    }
    if (!f.isFile()) return [ok: false, error: 'Slayt dosyası bulunamadı: ' + f.getAbsolutePath()]
    def kind = ((server.getServerType() ?: '') + ' ' + server.getClass().getName()).toLowerCase(java.util.Locale.ROOT)
    return [ok: true, file: f, openslide: kind.contains('openslide')]
}

// ── Ön denetim (runner precheck): arka planda, dosya başına bir kez; "⟳ Yenile" temizler ──
def parsePrecheck = { List lines ->
    def m = [:]
    lines.each { ln ->
        def t = ln.trim()
        if (t.startsWith('PRECHECK ')) t.substring(9).trim().split(/\s+/).each { kv -> int i = kv.indexOf('='); if (i > 0) m[kv.substring(0, i)] = kv.substring(i + 1) }
    }
    return m
}
def ensurePrecheck = { File slide, cfg ->
    def cached = precheckRef.get()
    if (cached != null && cached.path == slide.getAbsolutePath()) return cached
    if (!setupComplete(cfg)) return null
    if (precheckBusy.getAndSet(true)) return null
    long myGen = precheckGenRef.incrementAndGet()
    def worker = new Thread({
        def res
        File outFile = null
        try {
            outFile = File.createTempFile('histoplus-precheck-', '.txt')
            outFile.deleteOnExit()
            def pb = new ProcessBuilder([cfg.python, cfg.runner, 'precheck', '--slide', slide.getAbsolutePath()].collect { it.toString() })
            pb.redirectErrorStream(true); pb.redirectOutput(outFile); applyCacheEnv(pb, [:])
            def proc = pb.start()
            precheckProcRef.set(proc)
            boolean finished = proc.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)
            def lines = outFile.isFile() ? outFile.getText('UTF-8').readLines() : []
            if (!finished) {
                killTree(proc)
                res = [path: slide.getAbsolutePath(), pc: [ok: '0', reason: 'timeout'], text: tailOf(lines, 5)]
            } else {
                def errLines = lines.findAll { it.startsWith('HATA') }
                def text = errLines.isEmpty() ? (tailOf(lines, 5) + '\n# Çıkış kodu: ' + proc.exitValue()) : errLines.join('\n')
                res = [path: slide.getAbsolutePath(), pc: parsePrecheck(lines), text: text]
            }
        } catch (Throwable t) {
            res = [path: slide.getAbsolutePath(), pc: [ok: '0', reason: 'error'], text: 'Ön denetim başlatılamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName())]
        } finally {
            precheckProcRef.set(null)
            precheckBusy.set(false)
            try { if (outFile != null) outFile.delete() } catch (Throwable ignore) {}
        }
        if (precheckGenRef.get() == myGen) precheckRef.set(res)
        javafx.application.Platform.runLater { if (step.get() == 'READY') render() }
    }, 'AtolyeHistoPLUS-Precheck')
    worker.setDaemon(true); worker.start()
    return null
}
// Alan/yoğunluk için QuPath kalibrasyonu (varsa), yoksa dosyadaki µm/px (köprünün kullandığı).
def calForAreas = { imageData, Map pc ->
    def cal = pixelMicrons(imageData)
    if (cal != null) return [pw: cal.pw, ph: cal.ph, source: 'QuPath kalibrasyonu']
    if (pc != null && pc.mpp_x) return [pw: (pc.mpp_x as double), ph: (pc.mpp_y as double), source: 'slayt dosyası']
    return null
}
def modelLabelOf = { String key -> WEIGHTS[key]?.label ?: (key ?: '—') }

// ── Çalıştırma kapısı — READY ekranı ve startRun AYNI kuralı kullanır ──
def readiness = { imageData, cfg ->
    def blocks = []
    def warns = []
    def st = setupStatus(cfg)
    if (!setupComplete(cfg)) blocks << 'Kurulum tamamlanmadı (① Python ortamı + denetim, ② lisans onayı + doğrulanmış ağırlık) — "◀ Kurulum".'
    def targets = areaAnnotationsOf(imageData)
    if (targets.size() == 0) blocks << 'Bir alan çizip seçin (birden çok alan için Ctrl+tık), sonra "⟳ Yenile".'
    def sf = slideFileOf(imageData)
    if (!sf.ok) blocks << sf.error
    def pcRes = sf.ok ? ensurePrecheck(sf.file, cfg) : null
    def pc = pcRes?.pc
    if (sf.ok && pcRes == null && setupComplete(cfg)) blocks << 'Slayt dosyası denetleniyor… (birkaç saniye)'
    if (pc != null && pc.ok != '1') {
        if (pc.reason == 'no_mpp') blocks << 'Slayt dosyasında µm/px bilgisi yok. Köprü µm/px değerini dosyadan okur (upstream bu durumda sessizce 0.25 µm/px varsayardı); QuPath\'te girilen kalibrasyon aktarılamaz.'
        else if (pc.reason == 'no_level') blocks << 'Slaytta 0.25 ya da 0.5 µm/px\'e %20 yakın bir çözünürlük seviyesi yok (HistoPLUS yalnız 40× ve 20× modelleri sunar).' + (pcRes.text ? ('\n' + pcRes.text) : '')
        else if (pc.reason == 'unreadable') blocks << 'OpenSlide bu slayt dosyasını açamıyor (ör. CZI desteklenmez).' + (pcRes.text ? ('\n' + pcRes.text) : '')
        else if (pc.reason == 'timeout') blocks << 'Slayt ön denetimi 90 saniyede yanıt vermedi (ağ sürücüsü yavaş olabilir) — "⟳ Yenile" ile yeniden deneyin.'
        else blocks << 'Slayt ön denetimi başarısız.' + (pcRes.text ? ('\n' + pcRes.text) : '')
    }
    def modelKey = (pc != null && pc.ok == '1') ? pc.model : null
    if (modelKey != null && WEIGHTS.containsKey(modelKey)) {
        def wf = weightFileOf(cfg, modelKey)
        if (!isVerified(wf)) blocks << 'Bu slayt ' + modelLabelOf(modelKey) + ' modelini gerektirir ama ' + WEIGHTS[modelKey].file + ' yok ya da doğrulanmadı — "◀ Kurulum" → ②.'
        if (pc.level_mpp) {
            double lv = pc.level_mpp as double
            double mm = WEIGHTS[modelKey].mpp as double
            double diff = Math.abs(lv - mm) / mm
            if (diff > LEVEL_DIFF_WARN)
                warns << String.format(java.util.Locale.US, 'Slayt seviyesi %.3f µm/px, model %.2f µm/px için eğitildi (%%%.0f fark). HistoPLUS yeniden örnekleme yapmaz (upstream %%20\'ye kadar kabul eder) — çekirdek boyutları modele farklı görünür.', lv, mm, diff * 100.0d)
        }
    }
    boolean boundsNonZero = pc != null && pc.ok == '1' && ((pc.bounds_x ?: '0') != '0' || (pc.bounds_y ?: '0') != '0')
    if (boundsNonZero && !sf.openslide) {
        blocks << 'Slayt dosyasında OpenSlide sınır ofseti var ama QuPath görüntüyü OpenSlide ile açmamış — hücre konturları kayar. Görüntüyü OpenSlide ile yeniden ekleyin.'
    } else if (boundsNonZero && sf.openslide && pc.width && pc.height) {
        try {
            long serverW = imageData.getServer().getWidth()
            long serverH = imageData.getServer().getHeight()
            long pcW = pc.width as long
            long pcH = pc.height as long
            if (serverW == pcW && serverH == pcH)
                blocks << String.format(java.util.Locale.US,
                    'Slayt dosyasında OpenSlide sınır ofseti var; QuPath görüntü boyutu (%d×%d) OpenSlide\'ın ham boyutuyla aynı — görüntü sınırlara göre kırpılmamış, hücre konturları kayar. Görüntüyü sınırlara göre kırpılmış biçimde yeniden ekleyin.',
                    serverW, serverH)
        } catch (NumberFormatException ignore) {}
    }
    if (cfg.device == 'cuda' && cfg.cuda == '0') blocks << 'GPU seçili ama denetimde CUDA bulunamadı — Otomatik ya da CPU seçin.'
    if (parseIntOr(cfg.batchSize, 0) < 1) blocks << 'Batch size geçersiz (en az 1 olmalı) — "Gelişmiş" bölümünden düzeltin.'
    if (parseIntOr(cfg.ppThreads, 0) < 1) blocks << 'Son-işleme iş parçacığı geçersiz (en az 1 olmalı) — "Gelişmiş" bölümünden düzeltin.'
    def typeName = (imageData.getImageType()?.name() ?: '').toUpperCase(java.util.Locale.ROOT)
    if (!typeName.contains('BRIGHTFIELD_H_E')) warns << 'Görüntü tipi H&E değil (' + typeName + '). HistoPLUS yalnız H&E ile eğitildi.'
    def unionRoi = targets.size() == 0 ? null : unionRoiOf(targets)
    def calA = calForAreas(imageData, pc)
    double areaMm2 = (unionRoi != null && calA != null) ? areaMm2Of(unionRoi, calA.pw, calA.ph) : Double.NaN
    boolean onCpu = cfg.device == 'cpu' || (cfg.device == 'auto' && cfg.cuda != '1')
    if (cfg.device == 'auto' && cfg.cuda == '') warns << 'CUDA henüz denetlenmedi — ① "Denetle" ile GPU\'yu doğrulayın; alan uyarısı CPU\'ya göre hesaplandı.'
    double limit = onCpu ? AREA_WARN_CPU_MM2 : AREA_WARN_GPU_MM2
    if (Double.isFinite(areaMm2) && areaMm2 > limit)
        warns << String.format(java.util.Locale.US, 'Büyük alan: %.2f mm² (%s uyarı eşiği %.2f mm²) — çalıştırma uzun sürebilir (GPU ~12 sn/mm², CPU ~7 dk/mm²).', areaMm2, onCpu ? 'CPU' : 'GPU', limit)
    def qp = pixelMicrons(imageData)
    if (qp != null && pc != null && pc.mpp_x) {
        double fileMpp = ((pc.mpp_x as double) + (pc.mpp_y as double)) / 2.0d
        double qpMpp = (qp.pw + qp.ph) / 2.0d
        if (fileMpp > 0 && Math.abs(qpMpp - fileMpp) / fileMpp > MPP_DIFF_WARN)
            warns << String.format(java.util.Locale.US, 'QuPath µm/px (%.4f) dosyadakinden (%.4f) farklı — HistoPLUS DOSYADAKİ değeri kullanır.', qpMpp, fileMpp)
    }
    return [ok: blocks.isEmpty(), blocks: blocks, warns: warns, targets: targets, unionRoi: unionRoi,
            slide: sf, pc: pc, calA: calA, areaMm2: areaMm2, onCpu: onCpu, modelKey: modelKey]
}
def refreshPrecheck = { -> precheckGenRef.incrementAndGet(); killTree(precheckProcRef.get()); precheckRef.set(null); render() }
def elapsedText = { long ms ->
    long s = Math.max(0L, ms).intdiv(1000L)
    return String.format(java.util.Locale.US, '%d dk %02d sn', s.intdiv(60L), s % 60L)
}

// ── İçe aktarma: HistoPLUS çekirdek konturları → sınıflı TESPİT nesneleri ──
// Önce YALNIZ bu çalıştırmanın alanına düşen eski özet anotasyonları ÇOCUKLARI KORUNARAK silinir, sonra
// YALNIZ bu alandaki eski HistoPLUS tespitleri silinir; yeni tespitler silinecek bir özetin altına düşmez.
def importCells = { File geojson, imageData, unionRoi, calA ->
    def hier = imageData.getHierarchy()
    def oldSummaries = hier.getAnnotationObjects().findAll { a ->
        a.getName() != null && a.getName().startsWith(SUMMARY_NAME) && a.hasROI() &&
        unionRoi.contains(a.getROI().getCentroidX(), a.getROI().getCentroidY())
    }
    if (!oldSummaries.isEmpty()) hier.removeObjects(oldSummaries, true)
    def old = hier.getDetectionObjects().findAll { d ->
        d.getPathClass() != null && (d.getPathClass().getName() ?: '').endsWith(CLASS_SUFFIX) && d.hasROI() &&
        unionRoi.contains(d.getROI().getCentroidX(), d.getROI().getCentroidY())
    }
    if (!old.isEmpty()) hier.removeObjects(old, false)
    def cells = new ArrayList()   // [cx, cy, etiket, oy oranı]
    def unknown = new LinkedHashSet()
    int outside = 0
    if (geojson != null) {
        def root = JsonParser.parseString(geojson.getText('UTF-8')).getAsJsonObject()
        def feats = root.has('features') ? root.getAsJsonArray('features') : new com.google.gson.JsonArray()
        def plane = ImagePlane.getDefaultPlane()
        def pcs = [:]
        def dets = new ArrayList()
        for (el in feats) {
            def ft = el.getAsJsonObject()
            def geom = (ft.has('geometry') && ft.get('geometry').isJsonObject()) ? ft.getAsJsonObject('geometry') : null
            if (geom == null || !geom.has('type') || geom.get('type').getAsString() != 'Polygon') continue
            String key = null
            double vote = Double.NaN
            try { key = ft.getAsJsonObject('properties').getAsJsonObject('classification').get('name').getAsString() } catch (Throwable ignore) {}
            try { vote = ft.getAsJsonObject('properties').getAsJsonObject('measurements').get(RUNNER_VOTE).getAsDouble() } catch (Throwable ignore) {}
            if (key == null) continue
            def ring = geom.getAsJsonArray('coordinates').get(0).getAsJsonArray()
            int n = ring.size()
            if (n >= 2) {
                def p0 = ring.get(0).getAsJsonArray()
                def pl = ring.get(n - 1).getAsJsonArray()
                if (p0.get(0).getAsDouble() == pl.get(0).getAsDouble() && p0.get(1).getAsDouble() == pl.get(1).getAsDouble()) n--
            }
            if (n < 3) continue
            double[] xs = new double[n]
            double[] ys = new double[n]
            for (int i = 0; i < n; i++) { def p = ring.get(i).getAsJsonArray(); xs[i] = p.get(0).getAsDouble(); ys[i] = p.get(1).getAsDouble() }
            def roi = ROIs.createPolygonROI(xs, ys, plane)
            double cx = roi.getCentroidX()
            double cy = roi.getCentroidY()
            if (!unionRoi.contains(cx, cy)) { outside++; continue }
            def pc = pcs[key]
            if (pc == null) {
                def c = classByKey[key]
                if (c == null) unknown << key
                pc = QP.getPathClass(trOf(key) + CLASS_SUFFIX)
                if (c != null) try { pc.setColor(qupath.lib.common.ColorTools.packRGB(c.rgb[0] as int, c.rgb[1] as int, c.rgb[2] as int)) } catch (Throwable ignore) {}
                pcs[key] = pc
            }
            def det = PathObjects.createDetectionObject(roi, pc)
            def ml = det.getMeasurements()
            if (calA != null) ml.put('Alan µm²', roi.getArea() * calA.pw * calA.ph)
            if (!Double.isNaN(vote)) ml.put(VOTE_MEAS, vote)
            dets << det
            cells << [cx, cy, key, vote]
        }
        if (!dets.isEmpty()) hier.addObjects(dets)
    }
    hier.fireHierarchyChangedEvent(hier)
    return [cells: cells, removed: old.size(), unknown: unknown, outside: outside]
}

// ── Sınıf başına sayım + % + yoğunluk: seçili anotasyonlara + kilitli özete ──
def writeCounts = { imageData, List targets, unionRoi, List cells, calA, String modelKey ->
    def hier = imageData.getHierarchy()
    def present = cells.collect { it[2] } as LinkedHashSet
    def keys = CLASSES.collect { it.key }.findAll { present.contains(it) } + present.findAll { !classByKey.containsKey(it) }
    def fill = { obj, roi, List pts ->
        def ml = obj.getMeasurements()
        new ArrayList(ml.keySet()).findAll { it.startsWith(MEAS_PREFIX) }.each { ml.remove(it) }
        int tot = pts.size()
        double areaMm2 = (calA != null) ? areaMm2Of(roi, calA.pw, calA.ph) : Double.NaN
        ml.put(MEAS_PREFIX + 'toplam (n)', tot as double)
        if (calA != null) ml.put(MEAS_PREFIX + 'toplam (/mm²)', areaMm2 > 0 ? tot / areaMm2 : Double.NaN)
        keys.each { k ->
            int n = pts.count { it[2] == k } as int
            def name = trOf(k)
            ml.put(MEAS_PREFIX + name + ' (n)', n as double)
            ml.put(MEAS_PREFIX + name + ' (%)', tot > 0 ? 100.0d * n / tot : Double.NaN)
            if (calA != null) ml.put(MEAS_PREFIX + name + ' (/mm²)', areaMm2 > 0 ? n / areaMm2 : Double.NaN)
        }
        return areaMm2
    }
    targets.each { ann -> def roi = ann.getROI(); fill(ann, roi, cells.findAll { p -> roi.contains(p[0] as double, p[1] as double) }) }
    def summary = PathObjects.createAnnotationObject(unionRoi)
    summary.setName(SUMMARY_NAME + ' — ' + (modelKey ?: '?'))
    double areaMm2 = fill(summary, unionRoi, cells)
    // fill() MEAS_PREFIX ile başlayan tüm ölçümleri temizlediği için bu ölçüm fill()'den SONRA yazılır.
    summary.getMeasurements().put(MEAS_PREFIX + 'seçili alan sayısı', targets.size() as double)
    summary.setLocked(true)
    hier.addObjects([summary])
    hier.fireHierarchyChangedEvent(hier)
    return [areaMm2: areaMm2, keys: keys]
}

// ── Sonuç metni (yalnız sayım/yüzde/yoğunluk/oy oranı) ──────────────────────
def resultText = { imageData, cfg, Map rd, Map imp, Map dens, Map stats, long elapsedMs ->
    def sb = new StringBuilder()
    int tot = imp.cells.size()
    sb << "HISTOPLUS — SEÇİLİ ALANDA HÜCRE SINIFLARI\n"
    sb << "═════════════════════════════════════════\n\n"
    sb << "Slayt   : " << imageNameOf(imageData) << "\n"
    sb << "Model   : " << modelLabelOf(stats.model ?: rd.modelKey) << "  (HistoPLUS, CellViT + H0-mini)\n"
    if (rd.pc?.mpp_x) sb << "µm/px   : " << rd.pc.mpp_x << " (slayt dosyası) · kullanılan seviye " << (rd.pc.level_mpp ?: '?') << "\n"
    double a = dens.areaMm2 as double
    boolean haveArea = rd.calA != null && Double.isFinite(a) && a > 0
    if (haveArea) sb << String.format(java.util.Locale.US, "Alan    : %.3f mm² (alan için: %s)\n", a, rd.calA.source)
    sb << String.format(java.util.Locale.US, "Hücre   : %,d\n", tot)
    sb << "Süre    : " << elapsedText(elapsedMs) << "\n\n"
    sb << String.format(java.util.Locale.US, "%-26s %8s %7s %11s %10s\n", 'Sınıf', 'n', '%', 'hücre/mm²', 'ort. oy')
    dens.keys.each { k ->
        def mine = imp.cells.findAll { it[2] == k }
        int n = mine.size()
        String pct = tot > 0 ? String.format(java.util.Locale.US, '%.1f', 100.0d * n / tot) : '—'
        String dm = haveArea ? String.format(java.util.Locale.US, '%.1f', n / a) : '—'
        def votes = mine.collect { it[3] as double }.findAll { !Double.isNaN(it) }
        String mv = votes.isEmpty() ? '—' : String.format(java.util.Locale.US, '%.2f', votes.sum() / votes.size())
        String name = trOf(k) + ((classByKey[k]?.undocumented) ? ' ⚠' : '')
        sb << String.format(java.util.Locale.US, "%-26s %8d %7s %11s %10s\n", name, n, pct, dm, mv)
    }
    if (tot == 0) sb << "\n0 hücre bulundu — alanın doku içerdiğini denetleyin.\n"
    if (dens.keys.any { classByKey[it]?.undocumented }) sb << "\n⚠ " << UNDOCUMENTED_NOTE << "\n"
    sb << "\n'ort. oy' = çekirdek piksellerinden sınıfa oy verenlerin ortalama kesri (" << VOTE_MEAS << "); OLASILIK DEĞİLDİR.\n"
    if (stats.exported != null) {
        int exported = stats.exported as int
        int outside = imp.outside as int
        int skipped = exported - tot - outside
        if (skipped != 0) sb << String.format(java.util.Locale.US, "\nUYARI: köprü %,d kontur yazdı, %,d içe aktarıldı, %,d alan dışında, %,d atlandı (beklenmeyen geometri ya da sınıf bilgisi eksik).\n", exported, tot, outside, skipped)
    }
    if (!imp.unknown.isEmpty()) sb << "\nTabloda olmayan etiketler (özgün adıyla eklendi): " << imp.unknown.join(', ') << "\n"
    if (imp.removed > 0) sb << "\nBu alandaki önceki " << imp.removed << " HistoPLUS tespiti değiştirildi.\n"
    sb << "\nÇekirdekler '<Sınıf> (HistoPLUS)' sınıflı poligon TESPİTLERİ olarak eklendi; seçili anotasyonlara\n"
    sb << "'HistoPLUS: <sınıf> (n) / (%) / (/mm²)' yazıldı; kilitli özet: '" << SUMMARY_NAME << " — " << (stats.model ?: rd.modelKey) << "'.\n"
    sb << "Sınıflar modelin eğitim veri setinin kategorileridir; görsel olarak doğrulayın.\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

// ── Çalıştırma akışı ─────────────────────────────────────────────────────────
def runStartRef = new java.util.concurrent.atomic.AtomicLong(0L)
def timelineRef = new java.util.concurrent.atomic.AtomicReference(null)
def startRun = {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def rd = readiness(imageData, cfg)
    if (!rd.ok) { errorTextRef.set('Çalıştırılamadı:\n  • ' + rd.blocks.join('\n  • ')); step.set('ERROR'); render(); return }
    def workDir = resolveWorkDir(cfg, imageData); workDir.mkdirs(); workDirRef.set(workDir)
    def roiFile = new File(workDir, 'roi.geojson')
    if (!writeRoiGeoJson(rd.unionRoi, roiFile)) { errorTextRef.set('Seçili alan GeoJSON olarak yazılamadı (boş geometri).'); step.set('ERROR'); render(); return }
    def cmd = [cfg.python, cfg.runner, 'detect',
               '--slide', rd.slide.file.getAbsolutePath(),
               '--roi', roiFile.getAbsolutePath(),
               '--model-dir', modelFolderOf(cfg).getAbsolutePath(),
               '--out', workDir.getAbsolutePath(),
               '--device', cfg.device,
               '--batch-size', String.valueOf(parseIntOr(cfg.batchSize, 4)),
               '--pp-threads', String.valueOf(parseIntOr(cfg.ppThreads, 2))]
    cancelledRef.set(false); resetLog(); runStartRef.set(System.currentTimeMillis())
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(true); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('HistoPLUS çalışıyor — ' + modelLabelOf(rd.modelKey)); step.set('RUN_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln ->
            def t = (ln ?: '').trim()
            if (t.startsWith('PROGRESS ')) {
                // "PROGRESS a/b" → belirli ilerleme çubuğu (günlüğü kalabalıklaştırmaz)
                def m = (t =~ /^PROGRESS (\d+)\/(\d+)$/)
                if (m.find()) {
                    double frac = (m.group(1) as double) / Math.max(1.0d, m.group(2) as double)
                    javafx.application.Platform.runLater { def pb = progressRef.get(); if (pb != null) pb.setProgress(Math.min(1.0d, frac)) }
                }
                return
            }
            appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') }
        }
        try {
            appendLine('Alan: ' + rd.targets.size() + ' anotasyon (birleşik) → ' + roiFile.getAbsolutePath())
            def r = runPython(cmd, [:], appendLine)
            long elapsed = System.currentTimeMillis() - runStartRef.get()
            if (!r.ok) {
                if (r.exitCode == -3) {
                    // İptal hata DEĞİLDİR: Çalıştırma ekranına (READY) dönülür; hiçbir şey içe aktarılmadı.
                    javafx.application.Platform.runLater {
                        Dialogs.showInfoNotification('HistoPLUS', 'Çalıştırma iptal edildi.')
                        precheckRef.set(null); step.set('READY'); render()
                    }
                    return
                }
                javafx.application.Platform.runLater {
                    errorTextRef.set('HistoPLUS başarısız (çıkış: ' + r.exitCode + ')' + (r.error ? ('\n' + r.error) : '') + '\n\n' +
                        tailOf(r.lines.findAll { !it.startsWith('PROGRESS ') }, 30))
                    step.set('ERROR'); render()
                }
                return
            }
            def stats = [:]
            File geo = null
            r.lines.each { ln ->
                def t = ln.trim()
                if (t.startsWith('STAT ')) t.substring(5).trim().split(/\s+/).each { kv -> int i = kv.indexOf('='); if (i > 0) stats[kv.substring(0, i)] = kv.substring(i + 1) }
                if (t.startsWith('COUNT n=')) { try { stats['exported'] = Integer.parseInt(t.substring('COUNT n='.length()).trim()) } catch (Throwable ignore) {} }
                if (t.startsWith('RESULT geojson=')) geo = new File(t.substring('RESULT geojson='.length()).trim())
            }
            if (geo != null && !geo.isFile()) geo = null
            javafx.application.Platform.runLater { busyLabelRef.set('Hücreler içe aktarılıyor…'); step.set('BUSY'); render() }
            // Uzun çalıştırmadan SONRA QP.getCurrentImageData()'yı YENİDEN ALMA: başlatan imageData'ya yaz.
            def imp = importCells(geo, imageData, rd.unionRoi, rd.calA)
            def dens = writeCounts(imageData, rd.targets, rd.unionRoi, imp.cells, rd.calA, (stats.model ?: rd.modelKey) as String)
            if (imp.outside > 0) appendLine('Not: alan dışında kalan ' + imp.outside + ' kontur atlandı (karolar alanı aşar; sayım yalnız alan içidir).')
            def txt = resultText(imageData, cfg, rd, imp, dens, stats, elapsed)
            javafx.application.Platform.runLater {
                try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
                resultTextRef.set(txt); step.set('RESULT'); render()
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Beklenmeyen hata:\n' + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')); step.set('ERROR'); render() }
        }
    }, 'AtolyeHistoPLUS-Run')
    worker.setDaemon(true); worker.start()
}

// ── Render ───────────────────────────────────────────────────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def oldTimeline = timelineRef.get(); if (oldTimeline != null) { oldTimeline.stop(); timelineRef.set(null) }
    def cur = step.get()
    def imageData = QP.getCurrentImageData()
    def cfg = loadConfig()

    def title = new javafx.scene.control.Label(); title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10); center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)
    def actions = new ArrayList()

    def wrapBind = { javafx.scene.control.Label lbl ->
        lbl.setWrapText(true)
        lbl.sceneProperty().addListener({ obs, o, sc ->
            if (sc != null) { try { lbl.maxWidthProperty().unbind() } catch (Throwable ig) {}; lbl.maxWidthProperty().bind(sc.widthProperty().subtract(38)) }
        } as javafx.beans.value.ChangeListener)
    }
    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); wrapBind(lbl); center.getChildren().add(lbl) }
    def addMonoArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: ''); ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(ta)
    }
    def addWarnLabel = { String txt ->
        def lbl = new javafx.scene.control.Label(txt); wrapBind(lbl)
        lbl.setStyle('-fx-text-fill: #b8860b; -fx-font-weight: bold;'); center.getChildren().add(lbl)
    }
    def addLiveLog = { -> def la = logAreaRef.get(); if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) } }
    def killRunning = { -> cancelledRef.set(true); killTree(processRef.get()) }
    // "Kapat" düğmeleri stage.close() çağırır ama bu onCloseRequest'i TETİKLEMEZ — düğmeler aynı öldürme
    // mantığını kendileri uygular, sonra kapatır.
    def closeWizard = { ->
        if ((step.get() ?: '').endsWith('_RUNNING')) { cancelledRef.set(true); killTree(processRef.get()) }
        killTree(precheckProcRef.get())
        timelineRef.get()?.stop()
        stage.close()
    }
    def statusLine = { boolean ok, String msg ->
        def l = new javafx.scene.control.Label((ok ? '✓ ' : '✗ ') + msg); wrapBind(l)
        l.setStyle(ok ? '-fx-text-fill: #2e7d32;' : '-fx-text-fill: #c62828;'); center.getChildren().add(l)
    }
    def section = { String head -> def l = new javafx.scene.control.Label(head); l.setStyle('-fx-font-weight: bold; -fx-padding: 6 0 0 0;'); center.getChildren().add(l) }
    def rowOf = { List nodes -> def h = new javafx.scene.layout.HBox(8); h.setAlignment(javafx.geometry.Pos.CENTER_LEFT); h.getChildren().addAll(nodes); center.getChildren().add(h); return h }
    def later = { Closure c -> javafx.application.Platform.runLater { c() } }

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('HistoPLUS — kurulum')
        def st = setupStatus(cfg)
        addGuidance('HistoPLUS\'u çalıştırmak için iki adım vardır. Her satırın durumu ve düğmesi aşağıdadır; ikisi tamamlanınca "Çalıştırma ekranına geç" etkinleşir.')

        section('① Python ortamı')
        statusLine(st.pythonOk, st.pythonOk ? ('Python: ' + cfg.python) :
            'Python ortamı (histoplus) kurulu değil — ortam yöneticisinde "HistoPLUS — H&E hücre segmentasyonu" satırını kurun (~6 GB, GPU torch).')
        statusLine(st.runnerOk, st.runnerOk ? ('Köprü: ' + cfg.runner) :
            'Köprü betiği bulunamadı (handson/python/histoplus/histoplus_runner.py) — atölye projesini açın ya da "Köprü seç…".')
        statusLine(st.envOk, st.envOk ? 'Denetim tamam (upstream yapı taşları içe aktarıldı).' :
            (cfg.pipeline == '0' ? 'Denetim başarısız — ortamı ortam yöneticisinden yeniden kurun, sonra "Denetle".' :
                (cfg.pipeline?.trim() ? 'Ortam son denetimden sonra değişmiş — "Denetle" ile yeniden denetleyin.' : 'Ortam henüz denetlenmedi — "Denetle".')))
        addGuidance(cfg.cuda == '1' ? 'Denetim: CUDA GPU var.' :
            (cfg.cuda == '0' ? 'Denetim: CUDA yok — HistoPLUS CPU\'da çalışır (çok yavaş, ~7 dk/mm²; küçük alan seçin).' : 'GPU henüz denetlenmedi — "Denetle".'))
        rowOf([navButton('Python ortam yöneticisi…', { launchEnvManager() }, 'Atölye Python ortam yöneticisini açar; "Sihirbaza dön ▶" ile geri gelirsiniz'),
               navButton('Denetle', { startSelftest() }, 'histoplus_runner.py selftest — paketler, upstream yapı taşları, sabit commit + CUDA'),
               navButton('Köprü seç…', {
                   def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'histoplus_runner.py seçin')
                   if (x != null) { putPref(PREF_RUNNER, x.getAbsolutePath()); later { render() } }
               })])

        section('② Lisans ve ağırlıklar')
        def licenseArea = new javafx.scene.control.TextArea(
            'HistoPLUS kodu ve ağırlıkları CC BY-NC-ND 4.0 lisanslıdır (depo LICENSE.md ve Hugging Face kapısı; makaledeki ' +
            '"CC-BY-4.0" ifadesi depoyla çelişir — geçerli olan depo/HF lisansıdır). Atölye kodu ve ağırlıkları PAKETLEMEZ.\n\n' +
            'Hugging Face erişim koşullarından (özgün metin):\n' +
            '"Any commercial use, sale, or other monetization of the HistoPLUS model and its derivatives, which include models ' +
            'trained on outputs from the HistoPLUS model or datasets created from the HistoPLUS model, is prohibited and requires prior approval."\n' +
            '"By downloading this model, you agree not to distribute, publish or reproduce a copy of the model. If another user within ' +
            'your organization wishes to use the HistoPLUS model, they must register as an individual user and agree to comply with the terms of use."\n' +
            '"This model has not been reviewed, certified, or approved by any regulatory body."\n\n' +
            'Yani: yalnız ticari olmayan akademik araştırma/eğitim; HistoPLUS çıktılarıyla eğitilen modeller ve oluşturulan veri setleri de ' +
            'bu kısıta tabidir; ağırlık dosyalarını paylaşmayın; her kullanıcı huggingface.co/' + HF_REPO + ' sayfasındaki formu KENDİ hesabıyla doldurur ' +
            '(DİKKAT: "owkin/histoplus" değil, "Owkin-Bioptimus/histoplus").')
        licenseArea.setEditable(false); licenseArea.setWrapText(true); licenseArea.setPrefRowCount(9)
        center.getChildren().add(licenseArea)
        def licenseChk = new javafx.scene.control.CheckBox('Okudum; bu koşulları kendi adıma kabul ediyorum ve Hugging Face erişim formunu KENDİ hesabımla doldurdum.')
        licenseChk.setWrapText(true); licenseChk.setMaxWidth(Double.MAX_VALUE)
        licenseChk.setSelected(st.licenseOk)
        licenseChk.selectedProperty().addListener({ obs, o, n -> putPref(PREF_LICENSE, n ? LICENSE_TOKEN : ''); later { render() } } as javafx.beans.value.ChangeListener)
        center.getChildren().add(licenseChk)

        def tg = new javafx.scene.control.ToggleGroup()
        def rbFolder = new javafx.scene.control.RadioButton('Klasördeki ağırlıkları kullan'); rbFolder.setToggleGroup(tg)
        def rbDl = new javafx.scene.control.RadioButton('İndir (veri köküne; HF girişi gerekir)'); rbDl.setToggleGroup(tg)
        (cfg.source == 'folder' ? rbFolder : rbDl).setSelected(true)
        tg.selectedToggleProperty().addListener({ obs, o, n ->
            if (n != null) { putPref(PREF_SOURCE, n == rbFolder ? 'folder' : 'download'); later { render() } }
        } as javafx.beans.value.ChangeListener)
        rowOf([rbFolder, rbDl])
        if (cfg.source == 'folder') {
            rowOf([navButton('Klasör seç…', {
                       def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'HistoPLUS ağırlık klasörü (histoplus_cellvit_segmentor_40x.pt …)', null)
                       if (x != null) { putPref(PREF_FOLDER, x.getAbsolutePath()); later { render() } }
                   }), new javafx.scene.control.Label(cfg.folder?.trim() ? cfg.folder : '(klasör seçilmedi)')])
        } else {
            def tokenField = new javafx.scene.control.PasswordField()
            tokenField.setPromptText('Hugging Face jetonu (isteğe bağlı — "hf auth login" yapıldıysa boş bırakın)'); tokenField.setPrefColumnCount(30)
            tokenFieldRef.set(tokenField)
            rowOf([new javafx.scene.control.Label('HF jetonu:'), tokenField])
        }
        WEIGHTS.each { k, w ->
            def f = weightFileOf(cfg, k)
            if (f == null) statusLine(false, w.label + ': klasör seçilmedi.')
            else if (!f.isFile()) statusLine(false, w.label + ': dosya yok — ' + f.getAbsolutePath())
            else if (!isVerified(f)) statusLine(false, w.label + ': var ama doğrulanmadı — "Doğrula" (SHA-256)')
            else statusLine(true, w.label + ': doğrulandı — ' + f.getAbsolutePath())
        }
        addGuidance('Tek bir model yeterlidir: 40× (≈0.25 µm/px) taramalar 40× modelini, 20× (≈0.5 µm/px) taramalar 20× modelini kullanır — seçimi köprü slayttan yapar. GT450/AT2 taramaları 40× modelini kullanır.')
        def modelBtns = []
        if (!st.present.isEmpty() && st.verified.size() < st.present.size())
            modelBtns << navButton('Doğrula', { startVerify() }, 'SHA-256 sabitlenmiş Hugging Face revizyonuyla karşılaştırılır')
        if (cfg.source == 'download' && st.verified.size() < WEIGHTS.size()) {
            def dlBtn = navButton('İndir (2 × ~285 MB)', { startDownload(tokenFieldRef.get()?.getText()) }, 'Hugging Face ' + HF_REPO + ' (sabit revizyon cde2eee) → veri kökü')
            dlBtn.setDisable(!st.licenseOk || !st.pythonOk || !st.runnerOk)
            modelBtns << dlBtn
        }
        if (!modelBtns.isEmpty()) rowOf(modelBtns)
        if (!st.licenseOk) addWarnLabel('Lisans onay kutusu işaretlenmeden indirme ve çalıştırma kapalıdır.')

        actions.add(navButton('Kapat', { closeWizard() }))
        def goBtn = navButton('Çalıştırma ekranına geç ▶', { precheckRef.set(null); step.set('READY'); render() })
        goBtn.setDisable(!setupComplete(cfg))
        actions.add(goBtn)
    } else if (cur == 'CHECK_RUNNING' || cur == 'DL_RUNNING') {
        title.setText(jobTitleRef.get() + '…')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { killRunning() }))
    } else if (cur == 'CHECK_DONE' || cur == 'DL_DONE') {
        title.setText(jobTitleRef.get() + (jobOkRef.get() ? ' — tamam ✅' : (cancelledRef.get() ? ' — iptal edildi' : ' — BAŞARISIZ (günlüğe bakın)')))
        addLiveLog()
        actions.add(navButton('◀ Kuruluma dön', { step.set('CONFIG_INCOMPLETE'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
        if (jobOkRef.get() && setupComplete(loadConfig())) actions.add(navButton('Çalıştırma ekranına dön ▶', { precheckRef.set(null); step.set('READY'); render() }))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText('Görüntü açık değil')
            addGuidance('Önce bir H&E slaytı açın, ilgi alanını çizip seçin, sonra "⟳ Yenile".')
            actions.add(navButton('◀ Kurulum', { step.set('CONFIG_INCOMPLETE'); render() }))
            actions.add(navButton('⟳ Yenile', { refreshPrecheck() }))
        } else {
            def rd = readiness(imageData, cfg)
            title.setText('HistoPLUS — seçili alanda çekirdek segmentasyonu + sınıflama')
            def sb = new StringBuilder()
            sb << "Slayt        : " << imageNameOf(imageData) << "\n"
            sb << "Dosya        : " << (rd.slide.ok ? rd.slide.file.getAbsolutePath() : '—') << "\n"
            sb << "Model        : " << (rd.modelKey ? modelLabelOf(rd.modelKey) : '— (slayt denetlenince seçilir)') << "\n"
            sb << "Ağırlık      : " << (rd.modelKey ? (weightFileOf(cfg, rd.modelKey)?.getAbsolutePath() ?: '—') : '—') << "\n"
            sb << "Seçili alan  : " << rd.targets.size() << " anotasyon"
            if (Double.isFinite(rd.areaMm2 as double)) sb << String.format(java.util.Locale.US, " · %.3f mm²", rd.areaMm2 as double)
            sb << "\n"
            if (rd.pc?.mpp_x) sb << "Dosya µm/px  : " << rd.pc.mpp_x << " × " << rd.pc.mpp_y << "  · kullanılacak seviye " << (rd.pc.level_mpp ?: '?') << "\n"
            def qpc = pixelMicrons(imageData)
            sb << "QuPath µm/px : " << (qpc != null ? String.format(java.util.Locale.US, '%.4f × %.4f', qpc.pw, qpc.ph) : 'kalibre değil') << "\n"
            addMonoArea(sb.toString())

            // Seçenekler — değişiklik anında kaydedilir.
            def devIds = ['auto', 'cuda', 'cpu']
            def devChoice = new javafx.scene.control.ChoiceBox()
            devChoice.getItems().addAll('Otomatik', 'GPU (CUDA)', 'CPU')
            devChoice.getSelectionModel().select(Math.max(0, devIds.indexOf(cfg.device)))
            devChoice.getSelectionModel().selectedIndexProperty().addListener({ obs, o, n ->
                int i = n as int
                if (i >= 0) { putPref(PREF_DEVICE, devIds[i]); later { render() } }
            } as javafx.beans.value.ChangeListener)
            rowOf([new javafx.scene.control.Label('Cihaz:'), devChoice])
            def batchField = new javafx.scene.control.TextField(cfg.batchSize); batchField.setPrefColumnCount(5)
            def thrField = new javafx.scene.control.TextField(cfg.ppThreads); thrField.setPrefColumnCount(5)
            batchField.textProperty().addListener({ obs, o, n -> putPref(PREF_BATCH, n) } as javafx.beans.value.ChangeListener)
            thrField.textProperty().addListener({ obs, o, n -> putPref(PREF_THREADS, n) } as javafx.beans.value.ChangeListener)
            // Odak kaybında yeniden çiz: her tuşta sahne yeniden kurulup odak çalınmasın.
            batchField.focusedProperty().addListener({ obs, o, n -> if (!n) later { render() } } as javafx.beans.value.ChangeListener)
            thrField.focusedProperty().addListener({ obs, o, n -> if (!n) later { render() } } as javafx.beans.value.ChangeListener)
            def adv = new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('Batch size (784 px karo; 4 ≈ 5.6 GB VRAM):'), batchField,
                new javafx.scene.control.Label('Son-işleme iş parçacığı:'), thrField)
            adv.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            def advPane = new javafx.scene.control.TitledPane('Gelişmiş', adv); advPane.setExpanded(false)
            center.getChildren().add(advPane)

            rd.blocks.each { addWarnLabel('⛔ ' + it) }
            rd.warns.each { addWarnLabel('⚠ ' + it) }
            addGuidance('HistoPLUS YALNIZ seçili alan(lar)la kesişen karolarda çalışır; sayım yalnız alan İÇİNDEKİ çekirdeklerdir. Sonuç: her çekirdek için "<Sınıf> (HistoPLUS)" sınıflı poligon tespiti + seçili anotasyonlara sınıf başına sayım, % ve yoğunluk (hücre/mm²).')

            actions.add(navButton('Kapat', { closeWizard() }))
            actions.add(navButton('◀ Kurulum', { step.set('CONFIG_INCOMPLETE'); render() }))
            actions.add(navButton('⟳ Yenile', { refreshPrecheck() }, 'Seçimi ve slayt ön denetimini yeniden okur'))
            def runBtn = navButton('Seçili alanda çalıştır ▶', { startRun() }, 'HistoPLUS\'u seçili alanda çalıştırır')
            runBtn.setDisable(!rd.ok)
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING' || cur == 'BUSY' || cur == 'RESULT') {
        if (cur == 'RUN_RUNNING') {
            title.setText(runPhaseRef.get())
            def clock = new javafx.scene.control.Label(); clock.setStyle('-fx-opacity: 0.8;')
            def tick = { clock.setText('Geçen süre: ' + elapsedText(System.currentTimeMillis() - runStartRef.get())) }
            tick()
            def tl = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), { e -> tick() } as javafx.event.EventHandler))
            tl.setCycleCount(javafx.animation.Animation.INDEFINITE); tl.play(); timelineRef.set(tl)
            addGuidance('HistoPLUS ayrı bir Python süreci olarak çalışıyor (ağırlık doğrulama + model yükleme + karo çıkarımı + son-işleme). Çubuk çıkarılan karo oranını gösterir. "İptal et" süreci durdurur; hiçbir şey içe aktarılmaz.')
            center.getChildren().add(clock)
            def pb = busyBar(); progressRef.set(pb)
            center.getChildren().add(pb); addLiveLog()
            actions.add(navButton('İptal et', { killRunning() }))
            actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
        } else if (cur == 'BUSY') {
            progressRef.set(null)
            title.setText(busyLabelRef.get()); addGuidance('Lütfen bekleyin…'); center.getChildren().add(busyBar())
        } else {
            progressRef.set(null)
            title.setText('Tamamlandı ✅'); addMonoArea(resultTextRef.get())
            addGuidance('Hücre sınıflarını Annotations/Hierarchy panelinden ya da View → Show detections ile inceleyin; sınıf renklerini "Classes" listesinden açıp kapatın.')
            actions.add(navButton('Kapat', { closeWizard() }))
            actions.add(navButton('Klasörü aç', { openFolder(workDirRef.get()) }, 'roi.geojson, histoplus_cells.geojson, cell_masks.json (upstream biçimi), histoplus_run.json'))
            actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
            actions.add(navButton('↻ Yeniden çalıştır', { precheckRef.set(null); step.set('READY'); render() }))
        }
    } else { // ERROR
        title.setText('Hata'); addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Geri', { step.set(setupComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
        actions.add(navButton('Kapat', { closeWizard() }))
    }

    def topChk = new javafx.scene.control.CheckBox('Üstte tut'); topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n -> alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
    def spacer = new javafx.scene.layout.Region(); javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8); bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk); bar.getChildren().add(spacer); bar.getChildren().addAll(actions)
    def disclaimer = new javafx.scene.control.Label('⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def scroll = new javafx.scene.control.ScrollPane(center); scroll.setFitToWidth(true); scroll.setFitToHeight(true)
    center.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE)
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar); bottom.setPadding(new javafx.geometry.Insets(10))
    def root = new javafx.scene.layout.BorderPane(); root.setCenter(scroll); root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 920, 760))
}

// ── Açılış ────────────────────────────────────────────────────────────────────
step.set(setupComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('HistoPLUS — H&E hücre segmentasyonu + sınıflama (seçili alanda)')
        stage.setAlwaysOnTop(alwaysTop.get())
        stage.setOnCloseRequest({ e ->
            if ((step.get() ?: '').endsWith('_RUNNING')) { cancelledRef.set(true); killTree(processRef.get()) }
            killTree(precheckProcRef.get())
            timelineRef.get()?.stop()
        } as javafx.event.EventHandler)
        render(); stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ HistoPLUS hücre segmentasyonu sihirbazı açıldı."
