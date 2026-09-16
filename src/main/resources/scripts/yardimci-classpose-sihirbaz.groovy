/**
 * Yardımcı - Classpose hücre fenotipleme sihirbazı (H&E; seçili alanda)
 * ----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   QuPath'te ÇİZİP SEÇTİĞİNİZ alan(lar)da Classpose'u (Mandal, de Almeida ve ark.; Cellpose-SAM
 *   tabanlı) çalıştırır: hücreleri segmente eder ve modelin sınıflarından birine atar. Sihirbaz
 *   seçili alanları birleştirip GeoJSON olarak yazar → Python köprüsü
 *   (classpose/classpose_runner.py) Classpose'un KENDİ WSI hattını (predict_wsi) AYRI bir süreç
 *   olarak çalıştırır → hücre konturları sınıflı TESPİT (detection) nesneleri olarak eklenir ve
 *   sınıf başına SAYIM + % + YOĞUNLUK üretilir.
 *
 * KURULUM (sihirbaz adım adım yönlendirir):
 *   ① Python ortamı : Atölye Python ortam yöneticisi → "Classpose — H&E hücre fenotipleme" (~6 GB)
 *                     + bir kez "Kurulumu tamamla" (cellpose 4.0.8 + Classpose alt modülleri; ~1 MB)
 *   ② Model dosyası : 6 hazır modelden biri — yerel klasörden ya da indirerek (~1.2 GB); SHA-256 bir kez
 *   ③ GrandQC (isteğe bağlı): doku/artefakt filtresi — GrandQC sihirbazıyla AYNI dosyalar ve klasör
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Seçili alandaki model-tespitli hücrelerin sınıf başına sayımı, yüzdesi ve yoğunluğu (hücre/mm²).
 *   • Sınıf adları modelin eğitim veri setinin kategorileridir; tanı değildir. Klinik yorum ÜRETMEZ.
 *   • µm/px değerini Classpose SLAYT DOSYASINDAN okur; QuPath'te elle girilen kalibrasyon aktarılamaz.
 *
 * LİSANS SINIRI:
 *   Classpose kodu CC BY-NC 4.0 (bu eklenti Apache-2.0) → kod PAKETLENMEZ/KOPYALANMAZ; sabit commit
 *   (f6aeadd) kullanıcının "classpose" ortamına çalışma anında kurulur ve ayrı süreç olarak çalışır.
 *   Hugging Face model kartı MIT der; GrandQC ağırlıkları CC BY-NC 4.0 / CC BY-NC-SA 4.0.
 *   Kullanım: araştırma/eğitim, ticari olmayan.
 *
 * ÇIKTI:
 *   • Her hücre için "<Sınıf> (Classpose)" sınıflı poligon TESPİTİ + "Alan µm²"
 *   • Her seçili anotasyona: "Classpose: <sınıf> (n) / (%) / (/mm²)" ölçümleri
 *   • Kilitli "Classpose hücre sınıfları özeti — <model>" anotasyonu
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Mandal S, de Almeida JG ve ark., bioRxiv — doi:10.64898/2025.12.18.695211
 *   • Pachitariu M, Rariden M, Stringer C. Cellpose-SAM, bioRxiv 2025 — doi:10.1101/2025.04.28.651001
 *   • Weng Z ve ark. GrandQC, Nat Commun 2024 — doi:10.1038/s41467-024-54769-y
 *   • Kod: https://github.com/sohmandal/classpose · Ağırlık: https://huggingface.co/classpose/classpose
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
def SUMMARY_NAME = 'Classpose hücre sınıfları özeti'
def ENV_ID       = 'classpose'
def RUNNER_REL   = 'python/classpose/classpose_runner.py'
def CLASS_SUFFIX = ' (Classpose)'
def MEAS_PREFIX  = 'Classpose: '
double MPP_DIFF_WARN     = 0.05   // QuPath ile dosyadaki µm/px farkı bu oranı aşarsa uyar
double AREA_WARN_GPU_MM2 = 10.0d  // gerçek çalıştırma ölçümüyle ayarlanır (tahmini süre > ~10 dk)
double AREA_WARN_CPU_MM2 = 0.25d
def MODELS = [
    [id:'conic',   label:'conic — 6 sınıf (CoNIC, 0.5 µm/px)'],
    [id:'consep',  label:'consep — 6 sınıf (CoNSeP, 0.25 µm/px)'],
    [id:'glysac',  label:'glysac — 4 sınıf (GlySAC, 0.25 µm/px varsayılan)'],
    [id:'monusac', label:'monusac — 4 sınıf (MoNuSAC, 0.25 µm/px varsayılan)'],
    [id:'nucls',   label:'nucls — 6 sınıf (NuCLS, 0.2 µm/px) ⚠ düşük başarım'],
    [id:'puma',    label:'puma — 9 sınıf (PUMA, 0.22 µm/px)'],
]
def NUCLS_WARNING = 'Yazarların notu: NuCLS etiketleri çoğunlukla sınır kutusudur ve bu modelin başarımı düşüktür.'
// Classpose etiketi (GeoJSON classification.name, OLDUĞU GİBİ) → Türkçe ad + görüntüleme rengi.
// İlk altı renk HoVer-NeXt sihirbazıyla aynıdır (aynı CoNIC/Lizard sınıfları).
def CLASSES = [
    [key:'Neutrophil',           tr:'Nötrofil',                          rgb:[0, 200, 0]],
    [key:'Epithelial',           tr:'Epitel hücresi',                    rgb:[230, 40, 40]],
    [key:'Lymphocyte',           tr:'Lenfosit',                          rgb:[40, 80, 230]],
    [key:'Plasma cell',          tr:'Plazma hücresi',                    rgb:[0, 120, 60]],
    [key:'Eosinophil',           tr:'Eozinofil',                         rgb:[0, 190, 200]],
    [key:'Connective',           tr:'Bağ dokusu hücresi',                rgb:[240, 170, 90]],
    [key:'Other',                tr:'Diğer',                             rgb:[150, 150, 150]],
    [key:'Inflammatory',         tr:'İnflamatuar hücre',                 rgb:[120, 60, 200]],
    [key:'Healthy epithelial',   tr:'Doğal epitelyal hücre',             rgb:[255, 150, 180]],
    [key:'Malignant epithelial', tr:'Displastik/malign epitelyal hücre', rgb:[140, 0, 20]],
    [key:'Stroma',               tr:'Stromal hücre',                     rgb:[200, 200, 60]],
    [key:'Muscle',               tr:'Kas hücresi',                       rgb:[170, 90, 40]],
    [key:'Ambiguous',            tr:'Belirsiz',                          rgb:[90, 90, 90]],
    [key:'Macrophage',           tr:'Makrofaj',                          rgb:[255, 120, 0]],
    [key:'Tumor',                tr:'Tümör hücresi',                     rgb:[200, 0, 120]],
    [key:'Apoptosis',            tr:'Apoptotik hücre',                   rgb:[60, 60, 0]],
    [key:'Endothelial',          tr:'Endotel hücresi',                   rgb:[0, 160, 255]],
    [key:'Histocyte',            tr:'Histiyosit',                        rgb:[255, 200, 0]],
    [key:'Melanophage',          tr:'Melanofaj',                         rgb:[80, 40, 0]],
]
def classByKey = [:]
CLASSES.each { classByKey[it.key] = it }
def trOf = { String key -> (classByKey[key]?.tr) ?: key }
def modelLabelOf = { String id -> (MODELS.find { it.id == id }?.label) ?: id }

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır) ──
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/classpose')
def PREF_RUNNER   = 'runner'
def PREF_WORK     = 'workDir'
def PREF_MODEL    = 'model'
def PREF_SOURCE   = 'modelSource'   // 'folder' | 'download'
def PREF_FOLDER   = 'modelFolder'
def PREF_DEVICE   = 'device'        // 'auto' | 'cuda' | 'cpu'
def PREF_TTA      = 'tta'
def PREF_TISSUE   = 'tissue'
def PREF_ARTEFACT = 'artefact'
def PREF_BATCH    = 'batchSize'
def PREF_TILE     = 'tileSize'
def PREF_CUDA     = 'selftestCuda'  // '1' | '0' | '' (henüz denetlenmedi)
def PREF_ENTRY    = 'selftestEntrypoints'  // '1' | '0' | '' — predict_wsi içe aktarılabiliyor mu (selftest / prepare)
def putPref = { String k, String v -> prefs.put(k, v ?: ''); try { prefs.flush() } catch (Throwable ignore) {} }
def putBool = { String k, boolean b -> prefs.putBoolean(k, b); try { prefs.flush() } catch (Throwable ignore) {} }

// ── Atölye veri kökü (env yöneticisiyle PAYLAŞILAN) + önbellek yönlendirme ──
def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
// HF_HOME: huggingface_hub önbelleği; TORCH_HOME: GrandQC doku modelinin ImageNet kodlayıcısı.
def applyCacheEnv = { pb ->
    try {
        def cache = new File(atolyeDataRoot(), 'cache'); cache.mkdirs()
        def env = pb.environment()
        env.put('HF_HOME', new File(cache, 'huggingface').getAbsolutePath())
        env.put('TORCH_HOME', new File(cache, 'torch').getAbsolutePath())
        env.put('TIATOOLBOX_HOME', new File(cache, 'tiatoolbox').getAbsolutePath())
    } catch (Throwable ignore) {}
}

// ── Otomatik tespit: classpose ortamı + classpose_runner.py köprüsü ──────────
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

// ── Model dosyası: yerel klasör ya da veri köküne indirme; SHA-256 sonucu önbellekte ──
def defaultModelFolder = { -> new File(new File(new File(atolyeDataRoot(), 'cache'), 'classpose'), 'models') }
def modelFolderOf = { cfg ->
    if (cfg.source == 'download') return defaultModelFolder()
    return cfg.folder?.trim() ? new File(cfg.folder.trim()) : null
}
def modelFileOf = { cfg -> def d = modelFolderOf(cfg); return d == null ? null : new File(d, cfg.model + '.pt') }
// 1.2 GB dosyanın SHA-256'sı bir kez hesaplanır: sonuç yol + boyut + değişiklik zamanıyla saklanır.
def verifyKeyOf   = { File f -> 'sha.' + Integer.toHexString(f.getAbsolutePath().hashCode()) }
def verifyStampOf = { File f -> f.getAbsolutePath() + '|' + f.length() + '|' + f.lastModified() }
def isVerified    = { File f -> f != null && f.isFile() && prefs.get(verifyKeyOf(f), '') == verifyStampOf(f) }
def markVerified  = { File f -> if (f != null && f.isFile()) putPref(verifyKeyOf(f), verifyStampOf(f)) }

// ── GrandQC ağırlıkları: GrandQC sihirbazıyla ORTAK klasör (aynı Zenodo dosyaları) ──
// Sıra: GrandQC modelDir → <scriptsDir>/models → <veri kökü>/grandqc/grandqc-main/01_WSI_inference_OPENSLIDE_QC/models.
// Kural GrandQC sihirbazıyla aynı: dosyalar varsa hazırdır (burada yeniden özetlenmez).
def grandqcModelsDir = { ->
    def gq = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/grandqc')
    def md = (gq.get('modelDir', '') ?: '').trim()
    if (md) return new File(md)
    def sd = (gq.get('scriptsDir', '') ?: '').trim()
    if (sd) return new File(sd, 'models')
    return new File(new File(new File(new File(atolyeDataRoot(), 'grandqc'), 'grandqc-main'), '01_WSI_inference_OPENSLIDE_QC'), 'models')
}
def grandqcTissueFile   = { -> new File(new File(grandqcModelsDir(), 'td'), 'Tissue_Detection_MPP10.pth') }
def grandqcArtefactFile = { -> new File(new File(grandqcModelsDir(), 'qc'), 'GrandQC_MPP1.pth') }
def grandqcReady        = { -> grandqcTissueFile().isFile() && grandqcArtefactFile().isFile() }

// ── Yapılandırma ────────────────────────────────────────────────────────────
def loadConfig = { ->
    def rn = prefs.get(PREF_RUNNER, ''); if (!rn?.trim() || !new File(rn.trim()).isFile()) rn = detectRunner()
    def md = prefs.get(PREF_MODEL, 'conic'); if (!MODELS.any { it.id == md }) md = 'conic'
    def src = prefs.get(PREF_SOURCE, 'download'); if (!['folder', 'download'].contains(src)) src = 'download'
    def dv = prefs.get(PREF_DEVICE, 'auto'); if (!['auto', 'cuda', 'cpu'].contains(dv)) dv = 'auto'
    [ python   : detectPython(),
      runner   : rn,
      workDir  : prefs.get(PREF_WORK, ''),
      model    : md,
      source   : src,
      folder   : prefs.get(PREF_FOLDER, ''),
      device   : dv,
      tta      : prefs.getBoolean(PREF_TTA, false),
      tissue   : prefs.getBoolean(PREF_TISSUE, false),
      artefact : prefs.getBoolean(PREF_ARTEFACT, false),
      batchSize: prefs.get(PREF_BATCH, '8'),
      tileSize : prefs.get(PREF_TILE, '1024'),
      cuda     : prefs.get(PREF_CUDA, ''),
      entrypoints: prefs.get(PREF_ENTRY, '') ]
}
// Kurulum damgası: python yolu + predict_wsi.py dosyasının değişiklik zamanına bağlıdır (ortam yeniden kurulunca kendiliğinden geçersiz olur).
def entrypointsFileOf = { cfg ->
    if (!cfg.python?.trim()) return null
    def py = new File(cfg.python.trim())
    def venv = py.getParentFile()?.getParentFile()
    if (venv == null) return null
    def site = py.getParentFile().getName().equalsIgnoreCase('Scripts') ? new File(new File(venv, 'Lib'), 'site-packages')
                                                                          : new File(new File(new File(venv, 'lib'), 'python3.13'), 'site-packages')
    return new File(new File(new File(site, 'classpose'), 'entrypoints'), 'predict_wsi.py')
}
def entrypointsStampOf = { cfg -> def f = entrypointsFileOf(cfg); return (f != null && f.isFile()) ? ('1|' + cfg.python.trim() + '|' + f.lastModified()) : '' }
def setupStatus = { cfg ->
    def mf = modelFileOf(cfg)
    [ pythonOk    : cfg.python?.trim() ? new File(cfg.python).isFile() : false,
      runnerOk    : cfg.runner?.trim() ? new File(cfg.runner).isFile() : false,
      modelPresent: mf != null && mf.isFile(),
      envOk       : cfg.entrypoints ? (cfg.entrypoints == entrypointsStampOf(cfg)) : false,
      modelOk     : isVerified(mf),
      grandqcOk   : grandqcReady() ]
}
def setupComplete = { cfg -> def s = setupStatus(cfg); return s.pythonOk && s.runnerOk && s.envOk && s.modelOk }
def parseIntOr = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }

def imageNameOf = { imageData -> (imageData.getServer().getMetadata().getName() ?: 'slide').replaceAll(/\.[^.\/\\]+$/, '') }
def safeName = { String s ->
    def original = s ?: 'slide'
    def cleaned = original.replaceAll(/[^A-Za-z0-9._-]+/, '_')
    return cleaned == original ? cleaned : (cleaned + '_' + Integer.toHexString(original.hashCode()))
}
// Çalışma klasörü: ayar → <proje>/classpose_work/<görüntü> → <slayt klasörü>/classpose_work/<görüntü> → geçici klasör.
def resolveWorkDir = { cfg, imageData ->
    def name = safeName(imageNameOf(imageData))
    def wd = cfg.workDir?.trim()
    if (wd) return new File(new File(wd), name)
    def project = QP.getProject()
    if (project != null && project.getPath() != null)
        return new File(new File(project.getPath().getParent().toFile(), 'classpose_work'), name)
    try {
        def uris = imageData.getServer().getURIs()
        if (uris != null && !uris.isEmpty()) {
            def uri = uris.iterator().next()
            if ('file'.equals(uri.getScheme())) {
                def f = java.nio.file.Paths.get(uri).toFile()
                if (f.getParentFile() != null) return new File(new File(f.getParentFile(), 'classpose_work'), name)
            }
        }
    } catch (Throwable ignore) {}
    return new File(new File(System.getProperty('java.io.tmpdir'), 'classpose_work'), name)
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
    println "Classpose sihirbazı: python=${cfg.python ?: '(yok)'} runner=${cfg.runner ?: '(yok)'} model=${cfg.model} kaynak=${cfg.source}"
    println "① Python ortamı: ${st.pythonOk ? 'VAR' : 'yok'} · köprü: ${st.runnerOk ? 'VAR' : 'yok'} · kurulum: ${st.envOk ? 'tamam' : (cfg.entrypoints == '0' ? 'eksik (Kurulumu tamamla)' : (cfg.entrypoints?.trim() ? 'değişmiş (Denetle)' : 'denetlenmedi'))}"
    println "② Model dosyası: ${st.modelPresent ? (st.modelOk ? 'VAR (doğrulandı)' : 'VAR (doğrulanmadı)') : 'yok'} — ${modelFileOf(cfg)?.getAbsolutePath() ?: '(klasör seçilmedi)'}"
    println "③ GrandQC ağırlıkları: ${st.grandqcOk ? 'VAR' : 'yok'} — ${grandqcModelsDir().getAbsolutePath()}"
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
// Ön denetim (runner precheck) durumu — erken bildirilir ki envReturnHook (aşağıda) ve refreshPrecheck
// (ileride) bunlara başvurabilsin; bir closure SONRADAN tanımlanacak bir değişkene bağlanamaz.
def precheckRef     = new java.util.concurrent.atomic.AtomicReference(null)   // [path: String, pc: Map, text: String]
def precheckBusy    = new java.util.concurrent.atomic.AtomicBoolean(false)
def precheckProcRef = new java.util.concurrent.atomic.AtomicReference(null)   // çalışan precheck süreci; kapatma/Yenile ile öldürülür
// Nesil sayacı: "⟳ Yenile" ARTIRIR. Bir çalıştırma yalnız kendi yakaladığı nesil HÂLÂ güncelse önbelleğe
// yazar — böylece öldürülmüş (ya da yarışan) bir çalıştırmanın SAHTE sonucu, ince zamanlama sırasına
// bakılmaksızın, tekil bir "iptal edildi mi" bayrağından daha sağlam biçimde asla önbelleğe düşemez.
def precheckGenRef = new java.util.concurrent.atomic.AtomicLong(0L)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef   = new java.util.concurrent.atomic.AtomicReference('')
def busyLabelRef  = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def workDirRef    = new java.util.concurrent.atomic.AtomicReference(null)
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
        def suggested = new File(workDirRef.get() ?: new File(System.getProperty('user.home')), 'classpose_wizard.log')
        def f = qupath.fx.dialogs.FileChoosers.promptToSaveFile(stage, 'Günlüğü kaydet', suggested,
            new javafx.stage.FileChooser.ExtensionFilter('Günlük (*.log, *.txt)', '*.log', '*.txt'))
        if (f != null) { f.setText(txt, 'UTF-8'); Dialogs.showInfoNotification('Günlük', 'Kaydedildi: ' + f.getAbsolutePath()) }
    } catch (Throwable t) { Dialogs.showErrorMessage('Günlük', 'Kaydedilemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName())) }
}

// Süreç AĞACINI sonlandır: köprü Classpose'u alt süreç olarak başlatır, Classpose da işçi süreçleri
// (torch multiprocessing) açar; yalnız köprüyü öldürmek GPU'daki torun süreçleri yetim bırakır.
def killTree = { proc ->
    if (proc == null) return
    try { proc.descendants().forEach({ h -> try { h.destroyForcibly() } catch (Throwable ignore) {} } as java.util.function.Consumer) } catch (Throwable ignore) {}
    try { proc.destroyForcibly() } catch (Throwable ignore) {}
}

// ── Python süreci → satır akışı (zaman aşımı YOK: büyük alanlar ve 1.2 GB indirme uzun sürebilir) ──
def runPython = { List cmd, Closure onLine ->
    def pb = new ProcessBuilder(cmd.collect { it.toString() }); pb.redirectErrorStream(true)
    applyCacheEnv(pb)
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
def startJob = { String title, String runState, List args, Closure onFinish ->
    def cfg = loadConfig()
    def st = setupStatus(cfg)
    if (!st.pythonOk || !st.runnerOk) { errorTextRef.set('Önce ① Python ortamını kurun ve köprü betiğini bulun.'); step.set('ERROR'); render(); return }
    cancelledRef.set(false); resetLog()
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(true); la.setStyle(MONO)
    logAreaRef.set(la); jobTitleRef.set(title)
    step.set(runState); render()
    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython([cfg.python, cfg.runner] + args, appendLine)
        if (r.error) appendLine('HATA: ' + r.error)
        appendLine('# Çıkış kodu: ' + r.exitCode)
        try { onFinish(r, cfg) } catch (Throwable t) { appendLine('HATA (sonuç işlenemedi): ' + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')) }
        javafx.application.Platform.runLater { jobOkRef.set(r.ok); step.set(runState == 'CHECK_RUNNING' ? 'CHECK_DONE' : 'DL_DONE'); render() }
    }, 'AtolyeClasspose-Job')
    worker.setDaemon(true); worker.start()
}
def startSelftest = {
    startJob('Bağımlılık denetimi (selftest)', 'CHECK_RUNNING', ['selftest'], { r, cfg ->
        def cudaLine = r.lines.find { it.startsWith('CUDA cuda=') }
        if (cudaLine != null) putPref(PREF_CUDA, cudaLine.substring('CUDA cuda='.length()).trim())
        if (r.exitCode != -1 && r.exitCode != -3)
            putPref(PREF_ENTRY, r.lines.contains('ENTRYPOINTS ok=1') ? entrypointsStampOf(cfg) : '0')
    })
}
def startVerify = {
    def cfg = loadConfig(); def mf = modelFileOf(cfg)
    if (mf == null) { errorTextRef.set('Önce model klasörünü seçin.'); step.set('ERROR'); render(); return }
    startJob('Model dosyası doğrulanıyor (SHA-256)', 'CHECK_RUNNING', ['verify', '--model', cfg.model, '--dir', mf.getParentFile().getAbsolutePath()], { r, c ->
        if (r.ok && r.lines.any { it.startsWith('VERIFY ok=1') }) markVerified(mf)
    })
}
def startModelDownload = {
    def cfg = loadConfig(); def dir = defaultModelFolder(); dir.mkdirs()
    startJob('Model indiriliyor: ' + cfg.model + ' (~1.2 GB)', 'DL_RUNNING', ['download', '--model', cfg.model, '--dir', dir.getAbsolutePath()], { r, c ->
        if (r.ok) markVerified(new File(dir, cfg.model + '.pt'))
    })
}
def startGrandqcDownload = {
    def dir = grandqcModelsDir(); dir.mkdirs()
    startJob('GrandQC ağırlıkları indiriliyor (~100 MB)', 'DL_RUNNING', ['download', '--grandqc', '--grandqc-dir', dir.getAbsolutePath()], { r, c -> })
}

// ── Kurulumu tamamla: yayımlanan Classpose paketi alt modülleri (entrypoints, grandqc…) içermez ve bu
// commit cellpose 4.0.8 ister. Köprünün `prepare` komutu aynı commit'in kaynağını indirir, paketleme
// satırını düzeltip yeniden kurar ve predict_wsi içe aktarımını denetler (tek sefer).
def uvFileOf = { ->
    def exe = System.getProperty('os.name', '').toLowerCase(java.util.Locale.ROOT).contains('win') ? 'uv.exe' : 'uv'
    def local = new File(new File(atolyeDataRoot(), 'uv'), exe)
    if (local.isFile()) return local
    for (String dir : (System.getenv('PATH') ?: '').split(java.util.regex.Pattern.quote(File.pathSeparator))) {
        def f = new File(dir, exe)
        if (f.isFile()) return f
    }
    return local
}
def startPrepare = {
    def uv = uvFileOf()
    if (!uv.isFile()) { errorTextRef.set('uv bulunamadı (' + uv.getAbsolutePath() + ') — önce ortam yöneticisinden Classpose ortamını kurun.'); step.set('ERROR'); render(); return }
    def prepArgs = ['prepare', '--uv', uv.getAbsolutePath(), '--uv-cache', new File(atolyeDataRoot(), 'uv-cache').getAbsolutePath()]
    startJob('Classpose kurulumu tamamlanıyor (tek sefer, ~1 MB)', 'DL_RUNNING', prepArgs, { r, c ->
        if (r.exitCode != -1 && r.exitCode != -3)
            putPref(PREF_ENTRY, (r.ok && r.lines.any { it.startsWith('DONE prepare ok=1') }) ? entrypointsStampOf(c) : '0')
    })
}

// ── Ortam yöneticisinden bu sihirbaza dönüş ──────────────────────────────────
// "Python ortam yöneticisi…" düğmesi yöneticiyi bu kancayla (`atolyeReturnHook`) açar. Kurulum
// bitince yöneticideki "Sihirbaza dön ▶" (ya da pencereyi kapatmak) bu pencereyi öne getirir ve
// yapılandırmayı yeniden okur. Çalışan bir işlem sürerken ekran değiştirilmez; yalnız pencere öne gelir.
def envReturnHook = [
    envId   : ENV_ID,
    wizard  : 'Classpose hücre fenotipleme',
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
// Çakışan alanlar birleştirilir: Classpose, iki ROI'nin içindeki hücreyi İKİ KEZ döndürür.
def unionRoiOf = { List targets -> targets.size() == 1 ? targets[0].getROI() : qupath.lib.roi.RoiTools.union(targets.collect { it.getROI() }) }
def areaMm2Of = { roi, double pw, double ph -> roi.getArea() * pw * ph / 1_000_000.0d }

// ── ROI → GeoJSON (Classpose --roi_geojson biçimi) ───────────────────────────
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
// Tek Feature, (Multi)Polygon, TAM çözünürlük piksel koordinatları. OpenSlide sınır ofseti (bounds)
// Classpose tarafından İKİ YÖNDE uygulanır (ROI'yi +bounds kaydırır, çıktıyı geri kaydırır) —
// burada ofset EKLEMEYİN, çıktıda ÇIKARMAYIN.
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

// ── Slayt dosyası: Classpose dosyayı DOĞRUDAN okur → yerel, türetilmemiş tek dosya olmalı ──
def slideFileOf = { imageData ->
    def server = imageData.getServer()
    boolean derived = (server instanceof qupath.lib.images.servers.TransformingImageServer) ||
                      (server instanceof qupath.lib.images.servers.SparseImageServer) ||
                      (server instanceof qupath.lib.images.servers.ConcatChannelsImageServer) ||
                      (server instanceof qupath.lib.images.servers.ZConcatenatedImageServer)
    if (derived) return [ok: false, error: 'Bu görüntü kırpılmış/döndürülmüş ya da birleştirilmiş bir görünüm. Classpose slayt dosyasını doğrudan okur; koordinatlar dosyayla eşleşmez — özgün dosyayı açın.']
    def uris = null
    try { uris = server.getURIs() } catch (Throwable ignore) {}
    if (uris == null || uris.size() != 1) return [ok: false, error: 'Görüntü tek bir yerel slayt dosyasına karşılık gelmiyor (Classpose dosyayı doğrudan okur).']
    def uri = uris.iterator().next()
    if (!'file'.equals(uri.getScheme())) return [ok: false, error: 'Görüntü yerel bir dosya değil (' + uri.getScheme() + '). Classpose yalnız yerel slayt dosyası okur (OMERO/URL/DZI desteklenmez).']
    // Paths.get(URI) korur (aynı deyim resolveWorkDir'de) — file://host/share/... UNC'ye (\\host\share\...) çözülür.
    // new File(uri) host bileşenini SESSİZCE atar ve yanlış yerel yol üretir (D:\share\... gibi).
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
            outFile = File.createTempFile('classpose-precheck-', '.txt')
            outFile.deleteOnExit()
            def pb = new ProcessBuilder([cfg.python, cfg.runner, 'precheck', '--slide', slide.getAbsolutePath()].collect { it.toString() })
            pb.redirectErrorStream(true); pb.redirectOutput(outFile); applyCacheEnv(pb)
            def proc = pb.start()
            precheckProcRef.set(proc)
            boolean finished = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            def lines = outFile.isFile() ? outFile.getText('UTF-8').readLines() : []
            if (!finished) {
                killTree(proc)
                res = [path: slide.getAbsolutePath(), pc: [ok: '0', reason: 'timeout'], text: tailOf(lines, 5)]
            } else {
                def errLines = lines.findAll { it.startsWith('HATA') }
                // HATA satırı yoksa da köprü neden başarısız olduğunu göstersin diye çıktının son birkaç satırı + çıkış kodu tutulur.
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
        // Nesil hâlâ güncelse yaz: "⟳ Yenile" arada nesli artırdıysa (öldürme ya da yeni bir çalıştırma
        // yüzünden), bu SAHTE/eskimiş sonuç önbelleğe düşmez — sonraki render() zaten precheckRef'i boş
        // bulup TEMİZ bir denetim başlatır. runLater HER ZAMAN tetiklenir ki o yeni denetim gerçekten başlasın.
        if (precheckGenRef.get() == myGen) precheckRef.set(res)
        javafx.application.Platform.runLater { if (step.get() == 'READY') render() }
    }, 'AtolyeClasspose-Precheck')
    worker.setDaemon(true); worker.start()
    return null
}
// Alan/yoğunluk için QuPath kalibrasyonu (varsa), yoksa dosyadaki µm/px (Classpose'un kullandığı).
def calForAreas = { imageData, Map pc ->
    def cal = pixelMicrons(imageData)
    if (cal != null) return [pw: cal.pw, ph: cal.ph, source: 'QuPath kalibrasyonu']
    if (pc != null && pc.mpp_x) return [pw: (pc.mpp_x as double), ph: (pc.mpp_y as double), source: 'slayt dosyası']
    return null
}

// ── Çalıştırma kapısı — READY ekranı ve startRun AYNI kuralı kullanır ──
def readiness = { imageData, cfg ->
    def blocks = []
    def warns = []
    def st = setupStatus(cfg)
    if (!setupComplete(cfg)) blocks << 'Kurulum tamamlanmadı (① Python ortamı + kurulum denetimi, ② doğrulanmış model) — "◀ Kurulum".'
    def targets = areaAnnotationsOf(imageData)
    if (targets.size() == 0) blocks << 'Bir alan çizip seçin (birden çok alan için Ctrl+tık), sonra "⟳ Yenile".'
    def sf = slideFileOf(imageData)
    if (!sf.ok) blocks << sf.error
    def pcRes = sf.ok ? ensurePrecheck(sf.file, cfg) : null
    def pc = pcRes?.pc
    if (sf.ok && pcRes == null && setupComplete(cfg)) blocks << 'Slayt dosyası denetleniyor… (birkaç saniye)'
    if (pc != null && pc.ok != '1') {
        if (pc.reason == 'no_mpp') blocks << 'Slayt dosyasında µm/px bilgisi yok. Classpose µm/px değerini dosyadan okur; QuPath\'te girilen kalibrasyon aktarılamaz.'
        else if (pc.reason == 'unreadable') blocks << 'OpenSlide bu slayt dosyasını açamıyor (ör. CZI desteklenmez).' + (pcRes.text ? ('\n' + pcRes.text) : '')
        else if (pc.reason == 'timeout') blocks << 'Slayt ön denetimi 60 saniyede yanıt vermedi (ağ sürücüsü yavaş olabilir) — "⟳ Yenile" ile yeniden deneyin.'
        else blocks << 'Slayt ön denetimi başarısız.' + (pcRes.text ? ('\n' + pcRes.text) : '')
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
    if (cfg.tissue && !st.grandqcOk) blocks << 'Doku tespiti açık ama GrandQC ağırlıkları yok — ③ ile indirin ya da doku tespitini kapatın.'
    // Task 6 ölçümü: artefakt tespiti seçili alandan bağımsız olarak TÜM slaytı 1 µm/px'te tarar (111 780 × 87 041 px, RTX A4000: ~8 dk, ~12 GB RAM).
    if (cfg.tissue && cfg.artefact && st.grandqcOk) warns << 'Artefakt filtreleme açık: GrandQC seçili alan küçük olsa da tüm slaytı tarar — büyük bir slaytta ~8 dk ve ~12 GB bellek gerekebilir.'
    if (cfg.device == 'cuda' && cfg.cuda == '0') blocks << 'GPU seçili ama denetimde CUDA bulunamadı — Otomatik ya da CPU seçin.'
    int batchSize = parseIntOr(cfg.batchSize, 0)
    if (batchSize < 1) blocks << 'Batch size geçersiz (en az 1 olmalı) — "Gelişmiş" bölümünden düzeltin.'
    int tileSize = parseIntOr(cfg.tileSize, 0)
    if (tileSize < 256 || tileSize > 2048) blocks << 'Karo boyutu 256–2048 aralığında olmalı — "Gelişmiş" bölümünden düzeltin.'
    def typeName = (imageData.getImageType()?.name() ?: '').toUpperCase(java.util.Locale.ROOT)
    if (!typeName.contains('BRIGHTFIELD_H_E')) warns << 'Görüntü tipi H&E değil (' + typeName + '). Classpose H&E için eğitildi.'
    if (cfg.model == 'nucls') warns << NUCLS_WARNING
    def unionRoi = targets.size() == 0 ? null : unionRoiOf(targets)
    def calA = calForAreas(imageData, pc)
    double areaMm2 = (unionRoi != null && calA != null) ? areaMm2Of(unionRoi, calA.pw, calA.ph) : Double.NaN
    boolean onCpu = cfg.device == 'cpu' || (cfg.device == 'auto' && cfg.cuda != '1')
    if (cfg.device == 'auto' && cfg.cuda == '') warns << 'CUDA henüz denetlenmedi — ① "Denetle" ile GPU\'yu doğrulayın; alan uyarısı CPU\'ya göre hesaplandı.'
    double limit = onCpu ? AREA_WARN_CPU_MM2 : AREA_WARN_GPU_MM2
    if (Double.isFinite(areaMm2) && areaMm2 > limit)
        warns << String.format(java.util.Locale.US, 'Büyük alan: %.2f mm² (%s uyarı eşiği %.2f mm²) — çalıştırma uzun sürebilir.', areaMm2, onCpu ? 'CPU' : 'GPU', limit)
    def qp = pixelMicrons(imageData)
    if (qp != null && pc != null && pc.mpp_x) {
        double fileMpp = ((pc.mpp_x as double) + (pc.mpp_y as double)) / 2.0d
        double qpMpp = (qp.pw + qp.ph) / 2.0d
        if (fileMpp > 0 && Math.abs(qpMpp - fileMpp) / fileMpp > MPP_DIFF_WARN)
            warns << String.format(java.util.Locale.US, 'QuPath µm/px (%.4f) dosyadakinden (%.4f) farklı — Classpose DOSYADAKİ değeri kullanır.', qpMpp, fileMpp)
    }
    return [ok: blocks.isEmpty(), blocks: blocks, warns: warns, targets: targets, unionRoi: unionRoi,
            slide: sf, pc: pc, calA: calA, areaMm2: areaMm2, onCpu: onCpu]
}
// Yeni ön denetim istenir: önce kuşak artırılır (eski işçinin sonucu yayımlanmaz), sonra süreç öldürülür.
def refreshPrecheck = { -> precheckGenRef.incrementAndGet(); killTree(precheckProcRef.get()); precheckRef.set(null); render() }
def elapsedText = { long ms ->
    long s = Math.max(0L, ms).intdiv(1000L)
    return String.format(java.util.Locale.US, '%d dk %02d sn', s.intdiv(60L), s % 60L)
}

// ── İçe aktarma: Classpose hücre konturları → sınıflı TESPİT nesneleri ──
// Önce YALNIZ bu çalıştırmanın alanına düşen (merkezi unionRoi içinde olan) eski özet anotasyonları
// ÇOCUKLARI KORUNARAK (keepChildren=true) silinir — başka bir alandaki özet DOKUNULMADAN kalır —
// sonra YALNIZ bu alandaki eski Classpose tespitleri silinir; böylece yeni tespitler asla silinecek
// bir özetin altına düşmez.
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
    def cells = new ArrayList()   // [cx, cy, etiket]
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
            try { key = ft.getAsJsonObject('properties').getAsJsonObject('classification').get('name').getAsString() } catch (Throwable ignore) {}
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
            if (calA != null) det.getMeasurements().put('Alan µm²', roi.getArea() * calA.pw * calA.ph)
            dets << det
            cells << [cx, cy, key]
        }
        if (!dets.isEmpty()) hier.addObjects(dets)
    }
    hier.fireHierarchyChangedEvent(hier)
    return [cells: cells, removed: old.size(), unknown: unknown, outside: outside]
}

// ── Sınıf başına sayım + % + yoğunluk: seçili anotasyonlara + kilitli özete ──
def writeCounts = { imageData, List targets, unionRoi, List cells, calA, String modelId ->
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
    summary.setName(SUMMARY_NAME + ' — ' + modelId)
    double areaMm2 = fill(summary, unionRoi, cells)
    // fill() MEAS_PREFIX ile başlayan tüm ölçümleri temizleyip yeniden yazar; bu yüzden bu ölçüm de aynı
    // önekle YALNIZ fill() ÇAĞRISINDAN SONRA yazılır — önce yazılsaydı fill() onu da silerdi.
    summary.getMeasurements().put(MEAS_PREFIX + 'seçili alan sayısı', targets.size() as double)
    summary.setLocked(true)
    hier.addObjects([summary])
    hier.fireHierarchyChangedEvent(hier)
    return [areaMm2: areaMm2, keys: keys]
}

// ── Sonuç metni (yalnız sayım/yüzde/yoğunluk) ────────────────────────────────
def resultText = { imageData, cfg, Map rd, Map imp, Map dens, Map stats, long elapsedMs ->
    def sb = new StringBuilder()
    int tot = imp.cells.size()
    sb << "CLASSPOSE — SEÇİLİ ALANDA HÜCRE SINIFLARI\n"
    sb << "═════════════════════════════════════════\n\n"
    sb << "Slayt   : " << imageNameOf(imageData) << "\n"
    sb << "Model   : " << modelLabelOf(cfg.model) << "\n"
    if (rd.pc?.mpp_x) sb << "µm/px   : " << rd.pc.mpp_x << " (slayt dosyası; Classpose bunu kullanır)\n"
    double a = dens.areaMm2 as double
    boolean haveArea = rd.calA != null && Double.isFinite(a) && a > 0
    if (haveArea) sb << String.format(java.util.Locale.US, "Alan    : %.3f mm² (alan için: %s)\n", a, rd.calA.source)
    sb << String.format(java.util.Locale.US, "Hücre   : %,d\n", tot)
    if (stats.after_tissue != null) sb << String.format(java.util.Locale.US, "Doku filtresi sonrası       : %,d\n", stats.after_tissue as int)
    if (stats.artefact_removed != null) sb << String.format(java.util.Locale.US, "Artefakt nedeniyle çıkarılan: %,d\n", stats.artefact_removed as int)
    sb << "Süre    : " << elapsedText(elapsedMs) << "\n\n"
    sb << String.format(java.util.Locale.US, "%-36s %8s %8s %12s\n", 'Sınıf', 'n', '%', 'hücre/mm²')
    dens.keys.each { k ->
        int n = imp.cells.count { it[2] == k } as int
        String pct = tot > 0 ? String.format(java.util.Locale.US, '%.1f', 100.0d * n / tot) : '—'
        String dm = haveArea ? String.format(java.util.Locale.US, '%.1f', n / a) : '—'
        sb << String.format(java.util.Locale.US, "%-36s %8d %8s %12s\n", trOf(k), n, pct, dm)
    }
    if (tot == 0) sb << "\n0 hücre bulundu — alanın doku içerdiğini ve seçilen modeli denetleyin.\n"
    if (stats.exported != null) {
        int exported = stats.exported as int
        int outside = imp.outside as int
        int accounted = tot + outside
        if (accounted != exported) {
            int skipped = exported - accounted
            // Alan dışında kalanlar ayrı bir sayı olarak gösterilir (yalnızca çalışma günlüğünde geçer,
            // sonuç metninde başka yerde yer almaz) — böylece burada verilen tüm sayılar toplanabilir.
            if (outside > 0)
                sb << String.format(java.util.Locale.US,
                    "\nUYARI: Classpose %,d hücre yazdı, %,d içe aktarıldı, %,d alan dışında (%,d atlandı — beklenmeyen geometri ya da sınıf bilgisi eksik).\n",
                    exported, tot, outside, skipped)
            else
                sb << String.format(java.util.Locale.US,
                    "\nUYARI: Classpose %,d hücre yazdı, %,d içe aktarıldı (%,d atlandı — beklenmeyen geometri ya da sınıf bilgisi eksik).\n",
                    exported, tot, skipped)
        }
    }
    if (!imp.unknown.isEmpty()) sb << "\nTabloda olmayan etiketler (özgün adıyla eklendi): " << imp.unknown.join(', ') << "\n"
    if (imp.removed > 0) sb << "\nBu alandaki önceki " << imp.removed << " Classpose tespiti değiştirildi.\n"
    sb << "\nHücreler '<Sınıf> (Classpose)' sınıflı poligon TESPİTLERİ olarak eklendi; seçili anotasyonlara\n"
    sb << "'Classpose: <sınıf> (n) / (%) / (/mm²)' yazıldı; kilitli özet: '" << SUMMARY_NAME << " — " << cfg.model << "'.\n"
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
               '--model', cfg.model,
               '--model-dir', modelFolderOf(cfg).getAbsolutePath(),
               '--roi', roiFile.getAbsolutePath(),
               '--out', workDir.getAbsolutePath(),
               '--device', cfg.device,
               '--batch-size', String.valueOf(parseIntOr(cfg.batchSize, 8)),
               '--tile-size', String.valueOf(parseIntOr(cfg.tileSize, 1024))]
    if (cfg.tta) cmd << '--tta'
    if (cfg.tissue && grandqcReady()) {
        cmd += ['--tissue', grandqcTissueFile().getAbsolutePath()]
        if (cfg.artefact) cmd += ['--artefact', grandqcArtefactFile().getAbsolutePath()]
    }
    cancelledRef.set(false); resetLog(); runStartRef.set(System.currentTimeMillis())
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(true); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Classpose çalışıyor — ' + modelLabelOf(cfg.model)); step.set('RUN_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        try {
            appendLine('Alan: ' + rd.targets.size() + ' anotasyon (birleşik) → ' + roiFile.getAbsolutePath())
            def r = runPython(cmd, appendLine)
            long elapsed = System.currentTimeMillis() - runStartRef.get()
            if (!r.ok) {
                if (r.exitCode == -3) {
                    // İptal, hata DEĞİLDİR: kırmızı hata ekranı yerine Çalıştırma ekranına (READY) dönülür;
                    // hiçbir şey içe aktarılmadı.
                    javafx.application.Platform.runLater {
                        Dialogs.showInfoNotification('Classpose', 'Çalıştırma iptal edildi.')
                        precheckRef.set(null); step.set('READY'); render()
                    }
                    return
                }
                def logFile = new File(workDir, 'classpose_run.log')
                javafx.application.Platform.runLater {
                    errorTextRef.set('Classpose başarısız (çıkış: ' + r.exitCode + ')' + (r.error ? ('\n' + r.error) : '') + '\n\n' +
                        tailOf(r.lines, 30) + '\n\nTam günlük: ' + logFile.getAbsolutePath())
                    step.set('ERROR'); render()
                }
                return
            }
            def stats = [:]
            File geo = null
            r.lines.each { ln ->
                def t = ln.trim()
                if (t.startsWith('STAT ')) { def kv = t.substring(5).split('=', 2); if (kv.length == 2) stats[kv[0]] = parseIntOr(kv[1], 0) }
                if (t.startsWith('COUNT n=')) { try { stats['exported'] = Integer.parseInt(t.substring('COUNT n='.length()).trim()) } catch (Throwable ignore) {} }
                if (t.startsWith('RESULT geojson=')) geo = new File(t.substring('RESULT geojson='.length()).trim())
            }
            if (geo != null && !geo.isFile()) geo = null
            javafx.application.Platform.runLater { busyLabelRef.set('Hücreler içe aktarılıyor…'); step.set('BUSY'); render() }
            // Uzun çalıştırmadan SONRA QP.getCurrentImageData()'yı YENİDEN ALMA: başlatan imageData'ya yaz.
            def imp = importCells(geo, imageData, rd.unionRoi, rd.calA)
            def dens = writeCounts(imageData, rd.targets, rd.unionRoi, imp.cells, rd.calA, cfg.model)
            if (imp.outside > 0) appendLine('Not: alan dışında kalan ' + imp.outside + ' kontur atlandı.')
            def txt = resultText(imageData, cfg, rd, imp, dens, stats, elapsed)
            javafx.application.Platform.runLater {
                try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
                resultTextRef.set(txt); step.set('RESULT'); render()
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Beklenmeyen hata:\n' + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')); step.set('ERROR'); render() }
        }
    }, 'AtolyeClasspose-Run')
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
    // "Kapat" düğmeleri stage.close() çağırır ama bu onCloseRequest'i TETİKLEMEZ (yalnız pencere X'i/OS
    // kapatması tetikler) — bu yüzden düğmeler de aynı öldürme mantığını kendileri uygular, sonra kapatır.
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
        title.setText('Classpose — kurulum')
        def st = setupStatus(cfg)
        addGuidance('Classpose\'u çalıştırmak için üç adım vardır. Her satırın durumu ve düğmesi aşağıdadır; ① ve ② tamamlanınca "Çalıştırma ekranına geç" etkinleşir.')

        section('① Python ortamı')
        statusLine(st.pythonOk, st.pythonOk ? ('Python: ' + cfg.python) :
            'Python ortamı (classpose) kurulu değil — ortam yöneticisinde "Classpose — H&E hücre fenotipleme" satırını kurun (~6 GB, GPU torch).')
        statusLine(st.runnerOk, st.runnerOk ? ('Köprü: ' + cfg.runner) :
            'Köprü betiği bulunamadı (handson/python/classpose/classpose_runner.py) — atölye projesini açın ya da "Köprü seç…".')
        statusLine(st.envOk, st.envOk ? 'Classpose kurulumu tamam (predict_wsi içe aktarıldı).' :
            (cfg.entrypoints == '0' ? 'Classpose kurulumu eksik — "Kurulumu tamamla" (tek sefer: cellpose 4.0.8 + Classpose alt modülleri, ~1 MB).' :
                (cfg.entrypoints?.trim() ? 'Classpose ortamı son denetimden sonra değişmiş — "Denetle" ile yeniden denetleyin.' :
                                           'Classpose kurulumu henüz denetlenmedi — "Denetle".')))
        addGuidance(cfg.cuda == '1' ? 'Denetim: CUDA GPU var.' :
            (cfg.cuda == '0' ? 'Denetim: CUDA yok — Classpose CPU\'da çalışır (çok yavaş; küçük alan seçin).' : 'GPU henüz denetlenmedi — "Denetle".'))
        def prepBtn = navButton('Kurulumu tamamla', { startPrepare() }, 'cellpose 4.0.8\'e sabitler ve Classpose\'u alt modülleriyle yeniden kurar (tek sefer)')
        prepBtn.setDisable(!st.pythonOk || !st.runnerOk || st.envOk)
        rowOf([navButton('Python ortam yöneticisi…', { launchEnvManager() }, 'Atölye Python ortam yöneticisini açar; "Sihirbaza dön ▶" ile geri gelirsiniz'),
               navButton('Denetle', { startSelftest() }, 'classpose_runner.py selftest — paketler, predict_wsi içe aktarımı + CUDA'),
               prepBtn,
               navButton('Köprü seç…', {
                   def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'classpose_runner.py seçin')
                   if (x != null) { putPref(PREF_RUNNER, x.getAbsolutePath()); later { render() } }
               })])

        section('② Model dosyası')
        def modelChoice = new javafx.scene.control.ChoiceBox()
        MODELS.each { modelChoice.getItems().add(it.label) }
        int mIdx = MODELS.findIndexOf { it.id == cfg.model }
        modelChoice.getSelectionModel().select(mIdx < 0 ? 0 : mIdx)
        modelChoice.getSelectionModel().selectedIndexProperty().addListener({ obs, o, n ->
            int i = n as int
            if (i >= 0 && i < MODELS.size()) { putPref(PREF_MODEL, MODELS[i].id); later { render() } }
        } as javafx.beans.value.ChangeListener)
        rowOf([new javafx.scene.control.Label('Model:'), modelChoice])
        if (cfg.model == 'nucls') addWarnLabel('⚠ ' + NUCLS_WARNING)
        def tg = new javafx.scene.control.ToggleGroup()
        def rbFolder = new javafx.scene.control.RadioButton('Klasördeki modeli kullan'); rbFolder.setToggleGroup(tg)
        def rbDl = new javafx.scene.control.RadioButton('İndir (veri köküne)'); rbDl.setToggleGroup(tg)
        (cfg.source == 'folder' ? rbFolder : rbDl).setSelected(true)
        tg.selectedToggleProperty().addListener({ obs, o, n ->
            if (n != null) { putPref(PREF_SOURCE, n == rbFolder ? 'folder' : 'download'); later { render() } }
        } as javafx.beans.value.ChangeListener)
        rowOf([rbFolder, rbDl])
        if (cfg.source == 'folder') {
            rowOf([navButton('Klasör seç…', {
                       def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Model klasörü (conic.pt, puma.pt …)', null)
                       if (x != null) { putPref(PREF_FOLDER, x.getAbsolutePath()); later { render() } }
                   }), new javafx.scene.control.Label(cfg.folder?.trim() ? cfg.folder : '(klasör seçilmedi)')])
        }
        def mf = modelFileOf(cfg)
        if (mf == null) statusLine(false, 'Model klasörü seçilmedi.')
        else if (!st.modelPresent) statusLine(false, 'Dosya yok: ' + mf.getAbsolutePath())
        else if (!st.modelOk) statusLine(false, 'Dosya var ama doğrulanmadı: ' + mf.getAbsolutePath() + ' — "Doğrula" (SHA-256, bir kez)')
        else statusLine(true, 'Doğrulandı: ' + mf.getAbsolutePath())
        def modelBtns = []
        if (st.modelPresent && !st.modelOk) modelBtns << navButton('Doğrula', { startVerify() }, 'SHA-256 sabitlenmiş sürümle karşılaştırılır (1.2 GB — biraz sürer)')
        if (cfg.source == 'download' && !st.modelOk) modelBtns << navButton('İndir (~1.2 GB)', { startModelDownload() }, 'Yalnız seçili model, Hugging Face (sabit revizyon) → veri kökü')
        if (!modelBtns.isEmpty()) rowOf(modelBtns)

        section('③ GrandQC (isteğe bağlı: doku/artefakt filtresi)')
        statusLine(st.grandqcOk, (st.grandqcOk ? 'Ağırlıklar hazır: ' : 'Ağırlıklar yok: ') + grandqcModelsDir().getAbsolutePath())
        addGuidance('GrandQC sihirbazıyla AYNI dosyalar ve klasör kullanılır; ikinci kopya indirilmez. "İndir" eksik dosyaları (4 dosya, ~100 MB) Zenodo MD5 ile doğrulayarak indirir. Not: artefakt tespiti yalnız seçili alanı değil tüm slaytı 1 µm/px\'te tarar.')
        if (!st.grandqcOk) rowOf([navButton('GrandQC ağırlıklarını indir (~100 MB)', { startGrandqcDownload() })])

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
            title.setText('Classpose — seçili alanda hücre sınıfları')
            def sb = new StringBuilder()
            sb << "Slayt        : " << imageNameOf(imageData) << "\n"
            sb << "Dosya        : " << (rd.slide.ok ? rd.slide.file.getAbsolutePath() : '—') << "\n"
            sb << "Model        : " << modelLabelOf(cfg.model) << "\n"
            sb << "Model dosyası: " << (modelFileOf(cfg)?.getAbsolutePath() ?: '—') << "\n"
            sb << "Seçili alan  : " << rd.targets.size() << " anotasyon"
            if (Double.isFinite(rd.areaMm2 as double)) sb << String.format(java.util.Locale.US, " · %.3f mm²", rd.areaMm2 as double)
            sb << "\n"
            if (rd.pc?.mpp_x) sb << "Dosya µm/px  : " << rd.pc.mpp_x << " × " << rd.pc.mpp_y << "  (Classpose bunu kullanır)\n"
            def qpc = pixelMicrons(imageData)
            sb << "QuPath µm/px : " << (qpc != null ? String.format(java.util.Locale.US, '%.4f × %.4f', qpc.pw, qpc.ph) : 'kalibre değil') << "\n"
            sb << "GrandQC      : " << (setupStatus(cfg).grandqcOk ? 'hazır' : 'yok') << "\n"
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
            def ttaChk = new javafx.scene.control.CheckBox('TTA (test zamanı artırma — daha yavaş)'); ttaChk.setSelected(cfg.tta)
            ttaChk.selectedProperty().addListener({ obs, o, n -> putBool(PREF_TTA, n as boolean) } as javafx.beans.value.ChangeListener)
            boolean gqOk = setupStatus(cfg).grandqcOk
            def tisChk = new javafx.scene.control.CheckBox('Doku tespiti (GrandQC)')
            // Kayıtlı durum HER ZAMAN gösterilir (açıkken devre dışı bırakılıp çıkmaz sokağa düşmesin);
            // yalnız KAPALIYKEN VE ön koşul yokken devre dışıdır — zaten açık bir ayar her zaman kapatılabilir.
            tisChk.setSelected(cfg.tissue); tisChk.setDisable(!gqOk && !cfg.tissue)
            tisChk.selectedProperty().addListener({ obs, o, n -> putBool(PREF_TISSUE, n as boolean); later { render() } } as javafx.beans.value.ChangeListener)
            def artChk = new javafx.scene.control.CheckBox('Artefakt filtreleme (GrandQC; tüm slaytı tarar)')
            artChk.setSelected(cfg.artefact); artChk.setDisable(!cfg.artefact && !(gqOk && cfg.tissue))
            artChk.selectedProperty().addListener({ obs, o, n -> putBool(PREF_ARTEFACT, n as boolean); later { render() } } as javafx.beans.value.ChangeListener)
            rowOf([new javafx.scene.control.Label('Cihaz:'), devChoice, ttaChk])
            rowOf([tisChk, artChk])
            def batchField = new javafx.scene.control.TextField(cfg.batchSize); batchField.setPrefColumnCount(5)
            def tileField = new javafx.scene.control.TextField(cfg.tileSize); tileField.setPrefColumnCount(6)
            batchField.textProperty().addListener({ obs, o, n -> putPref(PREF_BATCH, n) } as javafx.beans.value.ChangeListener)
            tileField.textProperty().addListener({ obs, o, n -> putPref(PREF_TILE, n) } as javafx.beans.value.ChangeListener)
            // Metin her tuş vuruşunda DEĞİL, odak kaybedince yeniden çizilir — aksi halde sahne her tuşta
            // yeniden kurulur ve yazılmakta olan alandaki odak çalınır.
            batchField.focusedProperty().addListener({ obs, o, n -> if (!n) later { render() } } as javafx.beans.value.ChangeListener)
            tileField.focusedProperty().addListener({ obs, o, n -> if (!n) later { render() } } as javafx.beans.value.ChangeListener)
            def adv = new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('Batch size:'), batchField,
                new javafx.scene.control.Label('Karo boyutu (256–2048):'), tileField)
            adv.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            def advPane = new javafx.scene.control.TitledPane('Gelişmiş', adv); advPane.setExpanded(false)
            center.getChildren().add(advPane)

            rd.blocks.each { addWarnLabel('⛔ ' + it) }
            rd.warns.each { addWarnLabel('⚠ ' + it) }
            addGuidance('Classpose YALNIZ seçili alan(lar)da çalışır. Sonuç: her hücre için "<Sınıf> (Classpose)" sınıflı poligon tespiti + seçili anotasyonlara sınıf başına sayım, % ve yoğunluk (hücre/mm²).')

            actions.add(navButton('Kapat', { closeWizard() }))
            actions.add(navButton('◀ Kurulum', { step.set('CONFIG_INCOMPLETE'); render() }))
            actions.add(navButton('⟳ Yenile', { refreshPrecheck() }, 'Seçimi ve slayt ön denetimini yeniden okur'))
            def runBtn = navButton('Seçili alanda çalıştır ▶', { startRun() }, 'Classpose\'u seçili alanda çalıştırır')
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
            addGuidance('Classpose ayrı bir süreç olarak çalışıyor (model yükleme + karo çıkarımı). "İptal et" tüm süreç ağacını durdurur; hiçbir şey içe aktarılmaz.')
            center.getChildren().add(clock)
            center.getChildren().add(busyBar()); addLiveLog()
            actions.add(navButton('İptal et', { killRunning() }))
            actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
        } else if (cur == 'BUSY') {
            title.setText(busyLabelRef.get()); addGuidance('Lütfen bekleyin…'); center.getChildren().add(busyBar())
        } else {
            title.setText('Tamamlandı ✅'); addMonoArea(resultTextRef.get())
            addGuidance('Hücre sınıflarını Annotations/Hierarchy panelinden ya da View → Show detections ile inceleyin; sınıf renklerini "Classes" listesinden açıp kapatın.')
            actions.add(navButton('Kapat', { closeWizard() }))
            actions.add(navButton('Klasörü aç', { openFolder(workDirRef.get()) }, 'roi.geojson, Classpose çıktıları ve classpose_run.log'))
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
    stage.setScene(new javafx.scene.Scene(root, 920, 740))
}

// ── Açılış ────────────────────────────────────────────────────────────────────
step.set(setupComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Classpose — H&E hücre fenotipleme (seçili alanda)')
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
println "✓ Classpose hücre fenotipleme sihirbazı açıldı."
