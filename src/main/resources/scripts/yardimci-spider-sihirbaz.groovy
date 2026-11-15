/**
 * Yardımcı - SPIDER Doku Sınıflandırıcı Sihirbazı (tek pencere köprü)
 * -------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   HistAI'nin **SPIDER** organa özgüleşmiş, ÖN-EĞİTİMLİ doku sınıflandırıcı
 *   modellerini (histai/SPIDER-{colorectal,skin,thorax,breast}-model; Hibou-L
 *   omurgası) kullanarak açık lamdaki anotasyonları sınıflandırır — tek
 *   pencereden:
 *     1. Organ modelini + granülariteyi seçin.
 *     2. SINIFLANDIR — anotasyonların 1120×1120 karolarını diske yazar; Python
 *        köprüsü (spider_bridge.py) her karoyu SPIDER ile sınıflandırır.
 *     3. İçe aktarım — sonuçlar QuPath'e geri yazılır:
 *          • Anotasyon düzeyi  → her anotasyona sınıf + güven + olasılık ölçümü.
 *          • Karo ızgarası     → her karo için renkli ALT-TESPİT (ısı haritası).
 *   Derin öğrenme QuPath DIŞINDA bir Python venv'inde çalışır; bu betik karoları
 *   QuPath tarafında üretir (openslide gibi yerel kütüphane GEREKMEZ).
 *
 *   Kaiko sihirbazından FARKI: SPIDER modelleri ZATEN eğitilmiştir (organ başına
 *   sabit sınıf kümesi) — bu yüzden EĞİTİM ADIMI YOKTUR; yalnız tahmin yapılır.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Sınıflar MODELİN sabit organ sınıflarıdır; bu betik bunların üzerine hiçbir
 *     klinik eşik/alt-tip/grade mantığı eklemez.
 *   • Çıktı yalnız sınıf etiketi + güven + olasılık ölçümüdür; patoloji yorumu,
 *     grade veya klinik karar üretmez. SPIDER çıktısı bir DERİN ÖĞRENME
 *     TAHMİNİDİR; görsel doğrulama gerekir (Yapay Zekâ Araçlarını Değerlendirme eki).
 *   • Lisans: SPIDER **CC BY-NC 4.0** ve **KAPILI** (HuggingFace hesabı + erişim
 *     onayı + token). Midnight'ın (MIT/kapısız) aksine kayıt gerektirir.
 *
 * KULLANIM:
 *   1. SPIDER Python ortamını kurun (venv) + KAPILI modele erişim. Bkz. Ekler →
 *      SPIDER Doku Sınıflandırıcı § kurulum veya Kaynaklar → § İleri kurulumlar G.
 *   2. Bir lam açın; sınıflandırmak istediğiniz bölge(ler) için alan anotasyonu
 *      çizin (PathClass atamaya gerek yok — model sınıfı kendisi verir).
 *   3. [Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → SPIDER doku sınıflandırıcı sihirbazı]
 *   4. İlk açılışta yapılandırın: python.exe, spider_bridge.py, (ops.) model dizini.
 *   5. Organ + granülarite seçip "Sınıflandır" → çıktı otomatik içe aktarılır.
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Nechaev D ve ark. (2025), arXiv:2503.02876 — SPIDER. doi:10.48550/arXiv.2503.02876
 *   • Hibou (omurga): Nechaev D ve ark. (2024), arXiv:2406.05074.
 *   • Modeller: https://huggingface.co/histai  (CC BY-NC 4.0, gated)
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.regions.RegionRequest
import qupath.lib.common.ColorTools
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ───────────────────────────────────────────────────────────────
long PYTHON_TIMEOUT_SECONDS = 3600L          // Hibou-L (ViT-L) CPU'da yavaş — cömert üst sınır
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def DEVICE_OPTIONS = ['auto', 'cpu', 'cuda']
def ORGAN_OPTIONS  = ['colorectal', 'skin', 'thorax', 'breast']
def GRAN_LABELS    = ['annotation': 'Anotasyon düzeyi', 'tile-grid': 'Karo ızgarası (ısı haritası)']
def granLabelToKey = { String lbl -> def k = GRAN_LABELS.find { it.value == lbl }?.key; return (k ?: 'annotation') }
def CONF_MEAS = 'SPIDER: Güven'

// Niteliksel renk paleti (≤24 sınıf) — yalnız RENKSİZ (yeni) PathClass'lara atanır
def PALETTE = [
    ColorTools.makeRGB(230,  25,  75), ColorTools.makeRGB( 60, 180,  75),
    ColorTools.makeRGB(  0, 130, 200), ColorTools.makeRGB(245, 130,  48),
    ColorTools.makeRGB(145,  30, 180), ColorTools.makeRGB( 70, 240, 240),
    ColorTools.makeRGB(240,  50, 230), ColorTools.makeRGB(210, 245,  60),
    ColorTools.makeRGB(250, 190, 212), ColorTools.makeRGB(  0, 128, 128),
    ColorTools.makeRGB(220, 190, 255), ColorTools.makeRGB(170, 110,  40),
    ColorTools.makeRGB(255, 250, 200), ColorTools.makeRGB(128,   0,   0),
    ColorTools.makeRGB(170, 255, 195), ColorTools.makeRGB(128, 128,   0),
    ColorTools.makeRGB(255, 215, 180), ColorTools.makeRGB(  0,   0, 128),
    ColorTools.makeRGB(128, 128, 128), ColorTools.makeRGB(255, 225,  25),
    ColorTools.makeRGB(190, 100, 168), ColorTools.makeRGB( 60,  60,  60),
    ColorTools.makeRGB( 50, 110, 110), ColorTools.makeRGB(200,  30,  60)
]

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır) ──
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/spider')
def PREF_PYTHON = 'python'
def PREF_BRIDGE = 'bridge'
def PREF_MODEL  = 'modelDir'
def PREF_WORK   = 'workDir'
def PREF_TILE   = 'tileSize'
def PREF_DS     = 'downsample'
def PREF_MAXT   = 'maxTiles'
def PREF_ORGAN  = 'organ'
def PREF_GRAN   = 'granularity'
def PREF_DEVICE = 'device'

// Atölye veri kökü (env yöneticisiyle PAYLAŞILAN; öntanımlı ~/.atolye — C:).
def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
// Model önbelleğini (HF hub) de veri köküne yönlendir — SPIDER ağırlıkları HF'den iner.
def applyCacheEnv = { pb ->
    try {
        def cache = new File(atolyeDataRoot(), 'cache'); cache.mkdirs()
        def hf = new File(cache, 'huggingface'); def env = pb.environment()
        env.put('HF_HOME', hf.getAbsolutePath()); env.put('HF_HUB_CACHE', new File(hf, 'hub').getAbsolutePath())
        env.put('TORCH_HOME', new File(cache, 'torch').getAbsolutePath())
        env.put('PYTHONIOENCODING', 'utf-8')   // boru stdin/stdout ANSI kod sayfasına düşmesin (Türkçe sınıf adları/yollar)
        // HF_HOME yönlendirilince huggingface_hub oturum belirtecini $HF_HOME/token'da arar; oysa
        // `huggingface-cli login` onu ~/.cache/huggingface/token'a yazar → KAPILI SPIDER modelleri
        // 401 verirdi. Belirteç dosyasının yerini açıkça göster (HF_TOKEN zaten ayarlıysa dokunma).
        if (!env.get('HF_TOKEN') && !env.get('HF_TOKEN_PATH') && !new File(hf, 'token').isFile()) {
            def defTok = new File(new File(new File(System.getProperty('user.home'), '.cache'), 'huggingface'), 'token')
            if (defTok.isFile()) env.put('HF_TOKEN_PATH', defTok.getAbsolutePath())
        }
    } catch (Throwable ignore) {}
}

def extractBundledBridge = { String rel ->
    try {
        def cl = null
        try { cl = this.getClass().getClassLoader() } catch (Throwable ignore) {}
        def cls = null
        try { cls = Class.forName('io.github.sbalci.qupath.workshop.WorkshopResources', true, cl) } catch (Throwable ignore) {}
        if (cls == null) {
            try {
                def extCl = qupath.lib.gui.QuPathGUI.getExtensionCatalogManager()?.getExtensionClassLoader()
                if (extCl != null) cls = Class.forName('io.github.sbalci.qupath.workshop.WorkshopResources', true, extCl)
            } catch (Throwable ignore) {}
        }
        if (cls == null) cls = Class.forName('io.github.sbalci.qupath.workshop.WorkshopResources')
        def p = cls.getMethod('extractBundledPythonBridge', String, java.nio.file.Path).invoke(null, rel, atolyeDataRoot().toPath())
        if (p != null) {
            def f = p.toFile()
            if (f.isFile()) return f.getAbsolutePath()
        }
    } catch (Throwable ignore) {}
    return ''
}
def detectBridge = { ->
    try {
        def project = QP.getProject()
        if (project != null && project.getPath() != null) {
            def handson = project.getPath().getParent().toFile()
            def roots = [handson]
            if (handson.getParentFile() != null) roots << new File(handson.getParentFile(), 'handson')
            for (r in roots) { def f = new File(r, 'python/spider/spider_bridge.py'); if (f.isFile()) return f.getAbsolutePath() }
        }
    } catch (Throwable ignore) {}
    return extractBundledBridge('python/spider/spider_bridge.py')
}

def loadConfig = { ->
    [ python      : ({ -> def __p = prefs.get(PREF_PYTHON, ''); if (__p?.trim()) return __p; def __r = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('py.spider', ''); if (__r?.trim() && new File(__r.trim()).isFile()) return __r.trim(); def __v = new File(new File(atolyeDataRoot(), 'runtimes'), 'spider/.venv'); def __w = new File(__v, 'Scripts/python.exe'); def __n = new File(__v, 'bin/python'); __w.isFile() ? __w.getAbsolutePath() : (__n.isFile() ? __n.getAbsolutePath() : '') }).call(),
      bridge      : (prefs.get(PREF_BRIDGE, '')?.trim() ?: detectBridge()),
      modelDir    : prefs.get(PREF_MODEL,  ''),
      workDir     : prefs.get(PREF_WORK,   ''),
      tileSize    : prefs.get(PREF_TILE,   '1120'),
      downsample  : prefs.get(PREF_DS,     ''),
      maxTiles    : prefs.get(PREF_MAXT,   '0'),
      organ       : prefs.get(PREF_ORGAN,  'colorectal'),
      granularity : prefs.get(PREF_GRAN,   'annotation'),
      device      : prefs.get(PREF_DEVICE, 'auto') ]
}

// Zorunlu: python.exe + spider_bridge.py
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile())
        miss << 'Python yürütülebilir (python.exe)'
    if (!cfg.bridge?.trim() || !(new File(cfg.bridge)).isFile())
        miss << 'SPIDER köprüsü (spider_bridge.py)'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

def parseIntOr = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }
def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().trim().replace(',', '.')) } catch (Throwable t) { return d } }

// ── Çalışma dizinini çöz (proje dizini → lam klasörü → geçici) ────────────
def resolveWorkDir = { cfg, imageData ->
    def wd = cfg.workDir?.trim()
    if (wd) return new File(wd)
    def project = QP.getProject()
    if (project != null && project.getPath() != null)
        return new File(project.getPath().getParent().toFile(), 'spider_work')
    try {
        def uris = imageData.getServer().getURIs()
        if (uris != null && !uris.isEmpty()) {
            def uri = uris.iterator().next()
            if ('file'.equals(uri.getScheme())) {
                def f = new File(uri)
                if (f.getParentFile() != null) return new File(f.getParentFile(), 'spider_work')
            }
        }
    } catch (Throwable ignore) {}
    return new File(System.getProperty('java.io.tmpdir'), 'spider_work')
}

def imageNameOf = { imageData ->
    def nm = imageData.getServer().getMetadata().getName() ?: 'slide'
    return nm.replaceAll(/\.[^.\/\\]+$/, '')
}

// ── Arka plan tespiti (ortalama parlaklık > 220 → arka plan) ────────────────
def isBackground = { java.awt.image.BufferedImage img ->
    int w = img.getWidth(); int h = img.getHeight()
    if (w <= 0 || h <= 0) return true
    int[] px = img.getRGB(0, 0, w, h, null, 0, w)
    if (px.length == 0) return true
    int stepP = Math.max(1, (int) (px.length / 4096))
    long sum = 0L; int n = 0
    for (int i = 0; i < px.length; i += stepP) {
        int p = px[i]
        int r = (p >> 16) & 0xFF; int g = (p >> 8) & 0xFF; int b = p & 0xFF
        sum += (r + g + b); n++
    }
    double mean = (n > 0) ? (sum / (3.0d * n)) : 255.0d
    return mean > 220.0d
}

// ── Bir anotasyon ROI'sini ızgara karolara böl; her karo için sink() çağrılır ─
//   sink(int x, int y, int phys, BufferedImage img) — yazma yerini çağıran seçer.
def gridTiles = { server, roi, double downsample, int tileSize, int maxTiles, Closure sink ->
    if (roi == null || !roi.isArea()) return 0
    int phys = (int) Math.max(1, Math.round(tileSize * downsample))   // level-0 piksel boyutu
    int bx = (int) Math.floor(roi.getBoundsX())
    int by = (int) Math.floor(roi.getBoundsY())
    int bw = (int) Math.ceil(roi.getBoundsWidth())
    int bh = (int) Math.ceil(roi.getBoundsHeight())
    int sw = server.getWidth(); int sh = server.getHeight()
    int count = 0
    for (int yy = by; yy + phys <= by + bh; yy += phys) {
        for (int xx = bx; xx + phys <= bx + bw; xx += phys) {
            if (xx < 0 || yy < 0 || xx + phys > sw || yy + phys > sh) continue
            double cx = xx + phys / 2.0d, cy = yy + phys / 2.0d
            if (!roi.contains(cx, cy)) continue
            def img
            try { img = server.readRegion(RegionRequest.createInstance(server.getPath(), downsample, xx, yy, phys, phys)) }
            catch (Throwable t) { continue }
            if (img == null || isBackground(img)) continue
            sink(xx, yy, phys, img)
            count++
            if (maxTiles > 0 && count >= maxTiles) return count
        }
    }
    // Karo boyutundan küçük anotasyon: merkeze (sunucu sınırına kırpılmış) tek karo
    if (count == 0) {
        int cx = (int) Math.round(roi.getCentroidX() - phys / 2.0d)
        int cy = (int) Math.round(roi.getCentroidY() - phys / 2.0d)
        cx = Math.max(0, Math.min(cx, sw - phys))
        cy = Math.max(0, Math.min(cy, sh - phys))
        if (cx >= 0 && cy >= 0 && cx + phys <= sw && cy + phys <= sh) {
            try {
                def img = server.readRegion(RegionRequest.createInstance(server.getPath(), downsample, cx, cy, phys, phys))
                if (img != null) { sink(cx, cy, phys, img); count = 1 }
            } catch (Throwable ignore) {}
        }
    }
    return count
}

def writePng = { img, File f -> if (f.getParentFile() != null) f.getParentFile().mkdirs(); javax.imageio.ImageIO.write(img, 'PNG', f) }
def writeJson = { obj, File f ->
    if (f.getParentFile() != null) f.getParentFile().mkdirs()
    f.setText(qupath.lib.io.GsonTools.getInstance(true).toJson(obj), 'UTF-8')
}

// ── Karoları dışa aktar + JSON yaz ──────────────────────────────────────────
//   annotation modu : her anotasyon → tiles/<annId>/ (çok karo) → tek giriş
//   tile-grid modu  : her karo → tiles/<annId>/x<x>_y<y>/tile.png → giriş/karo
def exportTiles = { imageData, File workDir, cfg, Closure appendLine ->
    def server = imageData.getServer()
    def imageName = imageNameOf(imageData)
    int tileSize = parseIntOr(cfg.tileSize, 1120)
    // Downsample boşsa otomatik: lamın µm/px değerinden 0.5 µm/px (~20x) hedefine. Kalibrasyon yoksa 1.0.
    def calDs = server.getPixelCalibration()
    double mppDs = calDs.hasPixelSizeMicrons() ? calDs.getAveragedPixelSizeMicrons().doubleValue() : Double.NaN
    double downsample
    if ((cfg.downsample ?: '').toString().trim().isEmpty()) {
        downsample = (Double.isFinite(mppDs) && mppDs > 0) ? Math.max(1.0d, 0.5d / mppDs) : 1.0d
        appendLine(String.format(java.util.Locale.US, 'Downsample otomatik: %.3f%s', downsample,
            Double.isFinite(mppDs) ? String.format(java.util.Locale.US, ' (lam %.3f µm/px → karo %.3f µm/px)', mppDs, mppDs * downsample) : ' (lam kalibre değil; ölçek bilinmiyor)'))
    } else {
        downsample = parseDoubleOr(cfg.downsample, 1.0d)
        if (!(downsample > 0)) downsample = 1.0d
        if (Double.isFinite(mppDs)) appendLine(String.format(java.util.Locale.US, 'Downsample %.3f (lam %.3f µm/px → karo %.3f µm/px)', downsample, mppDs, mppDs * downsample))
    }
    if (Double.isFinite(mppDs) && Math.abs(mppDs * downsample - 0.5d) > 0.1d)
        appendLine(String.format(java.util.Locale.US, '⚠ Karo çözünürlüğü %.3f µm/px. SPIDER 20x (~0.5 µm/px) karolarla eğitildi; bu ölçekte tahminler güvenilmez olabilir.', mppDs * downsample))
    else if (!Double.isFinite(mppDs))
        appendLine('⚠ Lam kalibre değil: karo ölçeği denetlenemedi. SPIDER 20x (~0.5 µm/px) karolarla eğitildi.')
    int maxTiles = parseIntOr(cfg.maxTiles, 0)
    String mode = (cfg.granularity == 'tile-grid') ? 'tile-grid' : 'annotation'
    def tilesRoot = new File(workDir, 'tiles')
    // Önceki çalıştırmanın karoları birikmesin — bu çalıştırmadan önce temizle.
    if (tilesRoot.isDirectory()) tilesRoot.deleteDir()
    def anns = imageData.getHierarchy().getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }
    def predictList = []
    def roiMap = { double x, double y, double w, double h -> [x: x, y: y, width: w, height: h] }
    int totalTiles = 0
    int i = 0
    anns.each { ann ->
        i++
        def annId = ann.getID().toString()
        if (mode == 'annotation') {
            def outDir = new File(tilesRoot, annId); outDir.mkdirs()
            int n = gridTiles(server, ann.getROI(), downsample, tileSize, maxTiles, { x, y, phys, img ->
                writePng(img, new File(outDir, 'tile_x' + x + '_y' + y + '.png'))
            })
            if (n > 0) {
                def r = ann.getROI()
                predictList << [id: annId, image_name: imageName, tile_folder: outDir.getAbsolutePath(),
                                roi: roiMap(r.getBoundsX(), r.getBoundsY(), r.getBoundsWidth(), r.getBoundsHeight()),
                                parent_id: null, mode: 'annotation']
            }
            totalTiles += n
            appendLine('  karolar: anotasyon ' + i + '/' + anns.size() + ' (' + n + ')')
        } else {
            int n = gridTiles(server, ann.getROI(), downsample, tileSize, 0, { x, y, phys, img ->
                def outDir = new File(tilesRoot, annId + '/x' + x + '_y' + y); outDir.mkdirs()
                writePng(img, new File(outDir, 'tile.png'))
                predictList << [id: annId + '::x' + x + '_y' + y, image_name: imageName,
                                tile_folder: outDir.getAbsolutePath(),
                                roi: roiMap((double) x, (double) y, (double) phys, (double) phys),
                                parent_id: annId, mode: 'tile-grid']
            })
            totalTiles += n
            appendLine('  ızgara karoları: anotasyon ' + i + '/' + anns.size() + ' (' + n + ')')
        }
    }
    writeJson(predictList, new File(workDir, 'predict_annotations.json'))
    return [ok: true, nAnns: anns.size(), nEntries: predictList.size(), nTiles: totalTiles,
            mode: mode, organ: (cfg.organ ?: 'colorectal')]
}

// ── Python komutları ────────────────────────────────────────────────────────
def predictCmd = { cfg, File workDir ->
    def cmd = [cfg.python, cfg.bridge, 'predict',
               '--annotations', new File(workDir, 'predict_annotations.json').getAbsolutePath(),
               '--tiles-dir',   new File(workDir, 'tiles').getAbsolutePath(),
               '--output-dir',  workDir.getAbsolutePath(),
               '--organ', (cfg.organ ?: 'colorectal'),
               '--mode', ((cfg.granularity == 'tile-grid') ? 'tile-grid' : 'annotation'),
               '--max-tiles', (cfg.maxTiles ?: '0'),
               '--device', (cfg.device ?: 'auto')]
    if (cfg.modelDir?.trim()) { cmd.add('--model-dir'); cmd.add(cfg.modelDir.trim()) }
    return cmd
}
def selftestCmd = { cfg ->
    def cmd = [cfg.python, cfg.bridge, 'selftest', '--organ', (cfg.organ ?: 'colorectal')]
    if (cfg.modelDir?.trim()) { cmd.add('--model-dir'); cmd.add(cfg.modelDir.trim()) }
    return cmd
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
def workFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def tileFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def dsFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def maxTFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def organChoiceRef = new java.util.concurrent.atomic.AtomicReference(null)
def granChoiceRef  = new java.util.concurrent.atomic.AtomicReference(null)
def deviceChoiceRef= new java.util.concurrent.atomic.AtomicReference(null)
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

// ── Tahminleri içe aktar (anotasyon sınıfı VEYA renkli alt-tespit) ──────────
def importPredictions = { File workDir, imageData, String granularity ->
    def predsFile = new File(workDir, 'predictions.json')
    if (!predsFile.isFile())
        return [ok: false, error: 'predictions.json bulunamadı:\n' + predsFile.getAbsolutePath()]
    def records = []
    try {
        def reader = new java.io.InputStreamReader(
            new java.io.FileInputStream(predsFile), java.nio.charset.StandardCharsets.UTF_8)
        try {
            def arr = com.google.gson.JsonParser.parseReader(reader).getAsJsonArray()
            arr.each { el ->
                def o = el.getAsJsonObject()
                if (!o.has('id') || o.get('id').isJsonNull()) return
                def rec = [id: o.get('id').getAsString()]
                rec.parentId = (o.has('parent_id') && !o.get('parent_id').isJsonNull()) ? o.get('parent_id').getAsString() : null
                rec.mode = (o.has('mode') && !o.get('mode').isJsonNull()) ? o.get('mode').getAsString() : granularity
                rec.confidence = Double.NaN
                rec.probabilities = [:]
                if (!o.has('prediction') || o.get('prediction').isJsonNull()) {
                    rec.prediction = null
                } else {
                    rec.prediction = o.get('prediction').getAsString()
                    if (o.has('confidence') && !o.get('confidence').isJsonNull()) rec.confidence = o.get('confidence').getAsDouble()
                    if (o.has('probabilities') && o.get('probabilities').isJsonObject())
                        o.getAsJsonObject('probabilities').entrySet().each { e -> rec.probabilities[e.getKey()] = e.getValue().getAsDouble() }
                }
                if (o.has('roi') && o.get('roi').isJsonObject()) {
                    def ro = o.getAsJsonObject('roi')
                    rec.roi = [x: ro.get('x').getAsDouble(), y: ro.get('y').getAsDouble(),
                               width: ro.get('width').getAsDouble(), height: ro.get('height').getAsDouble()]
                }
                records << rec
            }
        } finally { reader.close() }
    } catch (Throwable t) {
        return [ok: false, error: 'predictions.json okunamadı:\n' + (t.getMessage() ?: t.getClass().getSimpleName())]
    }

    // Tahmin edilen sınıflar için kararlı renk indeksi (yalnız renksiz sınıflara uygulanır)
    def distinct = records.findAll { it.prediction != null }.collect { it.prediction }.unique().sort()
    def colorIdx = [:]; distinct.eachWithIndex { c, k -> colorIdx[c] = k }
    def ensureClass = { String name ->
        def pc = QP.getPathClass(name)
        if (pc.getColor() == null) {
            int idx = colorIdx.containsKey(name) ? (int) colorIdx[name] : Math.abs(name.hashCode())
            pc.setColor(PALETTE[idx % PALETTE.size()])
        }
        return pc
    }

    def hierarchy = imageData.getHierarchy()
    def predDist = new TreeMap<String, Integer>()
    double confSum = 0.0d; int confN = 0
    int updated = 0; int skipped = 0

    if (granularity == 'tile-grid') {
        def annById = [:]; hierarchy.getAnnotationObjects().each { annById[it.getID().toString()] = it }
        def byParent = records.findAll { it.prediction != null && it.parentId != null && it.roi != null }.groupBy { it.parentId }
        byParent.each { parentId, recs ->
            def parent = annById[parentId]
            if (parent == null) return
            def plane = parent.getROI().getImagePlane()
            def newDets = []
            recs.each { r ->
                def pc = ensureClass(r.prediction)
                def roi = qupath.lib.roi.ROIs.createRectangleROI(
                    (double) r.roi.x, (double) r.roi.y, (double) r.roi.width, (double) r.roi.height, plane)
                def det = qupath.lib.objects.PathObjects.createDetectionObject(roi, pc)
                if (!Double.isNaN(r.confidence)) det.setName(String.format(java.util.Locale.US, '%s (%.2f)', r.prediction, r.confidence))
                else det.setName(r.prediction)
                def ml = det.getMeasurementList()
                if (!Double.isNaN(r.confidence)) ml.put(CONF_MEAS, (double) r.confidence)
                r.probabilities.each { kk, vv -> ml.put('SPIDER: ' + kk + ' olasılığı', (double) vv) }
                det.setLocked(true)
                newDets << det
                predDist[r.prediction] = (predDist.getOrDefault(r.prediction, 0)) + 1
                if (!Double.isNaN(r.confidence)) { confSum += r.confidence; confN++ }
                updated++
            }
            if (!newDets.isEmpty()) parent.addChildObjects(newDets)
        }
    } else {
        def recById = [:]; records.each { recById[it.id] = it }
        hierarchy.getAnnotationObjects().each { ann ->
            def id = ann.getID().toString()
            if (!recById.containsKey(id)) return
            def r = recById[id]
            if (r.prediction == null) { skipped++; return }
            ann.setPathClass(ensureClass(r.prediction))
            if (!Double.isNaN(r.confidence)) ann.setName(String.format(java.util.Locale.US, '%s (%.2f)', r.prediction, r.confidence))
            else ann.setName(r.prediction)
            def ml = ann.getMeasurementList()
            if (!Double.isNaN(r.confidence)) ml.put(CONF_MEAS, (double) r.confidence)
            r.probabilities.each { kk, vv -> ml.put('SPIDER: ' + kk + ' olasılığı', (double) vv) }
            predDist[r.prediction] = (predDist.getOrDefault(r.prediction, 0)) + 1
            if (!Double.isNaN(r.confidence)) { confSum += r.confidence; confN++ }
            updated++
        }
    }
    QP.fireHierarchyUpdate()
    javafx.application.Platform.runLater { try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {} }
    double meanConf = (confN > 0) ? (confSum / confN) : Double.NaN
    return [ok: true, updated: updated, skipped: skipped, predDist: predDist, meanConf: meanConf, mode: granularity]
}

// ── Özet metni ──────────────────────────────────────────────────────────────
def buildResultText = { File workDir, exp, imp ->
    def sb = new StringBuilder()
    sb << "SPIDER — ÖZET\n"
    sb << "═════════════════\n\n"
    sb << "Organ modeli : " << (exp?.organ ?: '?') << "\n"
    sb << "Granülarite  : " << ((imp?.mode == 'tile-grid') ? 'Karo ızgarası (alt-tespit)' : 'Anotasyon düzeyi') << "\n"
    sb << String.format(java.util.Locale.US, "Anotasyon    : %,d   ·   karo: %,d%n", (int) (exp?.nAnns ?: 0), (int) (exp?.nTiles ?: 0))
    sb << "\nSınıf dağılımı:\n"
    if (imp != null && imp.ok && !imp.predDist.isEmpty()) {
        int total = 0
        imp.predDist.each { cn, n -> sb << String.format(java.util.Locale.US, "  %-26s : %,d%n", cn, n); total += n }
        sb << String.format(java.util.Locale.US, "  %-26s : %,d%n", ((imp.mode == 'tile-grid') ? '(toplam karo)' : '(sınıflandırılan)'), total)
        if (imp.skipped > 0) sb << String.format(java.util.Locale.US, "  %-26s : %,d%n", '(karo yok, atlanan)', imp.skipped)
        if (!Double.isNaN(imp.meanConf)) sb << String.format(java.util.Locale.US, "  %-26s : %.3f%n", 'ortalama güven', imp.meanConf)
    } else {
        sb << "  (sonuç yok)\n"
    }
    sb << "\n'" << CONF_MEAS << "' ve sınıf olasılığı ölçümleri " <<
        ((imp?.mode == 'tile-grid') ? 'her alt-tespite' : 'her anotasyona') << " yazıldı (Measurements paneli).\n"
    sb << "\nSPIDER çıktısı bir derin öğrenme tahminidir; görsel olarak doğrulayın (Yapay Zekâ Araçlarını Değerlendirme eki).\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

// ── Headless: yapılandırmayı raporla, etkileşim yok ─────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println "Önce bir lam açın."; return }
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    println "SPIDER yapılandırması: python=${cfg.python ?: '(ayarsız)'} köprü=${cfg.bridge ?: '(ayarsız)'} model=${cfg.modelDir ?: '(HF hub — KAPILI)'} organ=${cfg.organ} granülarite=${cfg.granularity} aygıt=${cfg.device}"
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(', ')}"
    def anns = imageData.getHierarchy().getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }
    println "Alan anotasyonu: ${anns.size()}"
    println "SPIDER sihirbazı için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Alanları prefs'e yaz ─────────────────────────────────────────────────────
def textOf = { ref -> def f = ref.get(); return (f != null ? f.getText() : '').trim() }
def persistFields = {
    prefs.put(PREF_PYTHON, textOf(pyFieldRef))
    prefs.put(PREF_BRIDGE, textOf(bridgeFieldRef))
    prefs.put(PREF_MODEL,  textOf(modelFieldRef))
    prefs.put(PREF_WORK,   textOf(workFieldRef))
    def ts = textOf(tileFieldRef); prefs.put(PREF_TILE, ts ?: '1120')
    def ds = textOf(dsFieldRef);   prefs.put(PREF_DS,   ds ?: '')
    def mt = textOf(maxTFieldRef); prefs.put(PREF_MAXT, mt ?: '0')
    def og = organChoiceRef.get();  prefs.put(PREF_ORGAN, (og != null && og.getValue() != null) ? og.getValue() : 'colorectal')
    def gr = granChoiceRef.get();   prefs.put(PREF_GRAN, (gr != null && gr.getValue() != null) ? granLabelToKey(gr.getValue()) : 'annotation')
    def dv = deviceChoiceRef.get(); prefs.put(PREF_DEVICE, (dv != null && dv.getValue() != null) ? dv.getValue() : 'auto')
    try { prefs.flush() } catch (Throwable ignore) {}
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

// ── Bağımlılık kontrolü (selftest) ──────────────────────────────────────────
def startSelftest = {
    persistFields()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set('Önce yapılandırmayı tamamlayın:\n  • ' + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Bağımlılık kontrolü'); step.set('CHECK_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython(selftestCmd(cfg), appendLine)
        javafx.application.Platform.runLater { selftestOkRef.set(r.ok); step.set('CHECK_DONE'); render() }
    }, 'AtolyeSPIDER-Check')
    worker.setDaemon(true); worker.start()
}

// ── Sınıflandırma akışı (tek eylem; eğitim yok) ─────────────────────────────
def startRun = {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def workDir = resolveWorkDir(cfg, imageData)
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Karo dışa aktarılıyor (1/2)…'); step.set('RUN_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        appendLine('Çalışma dizini: ' + workDir.getAbsolutePath())
        def exp
        try { exp = exportTiles(imageData, workDir, cfg, appendLine) }
        catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Karo dışa aktarma hatası:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }; return
        }
        if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }
        if (exp.nEntries == 0) {
            javafx.application.Platform.runLater { errorTextRef.set('Sınıflandırılacak karo üretilemedi.\nEn az 1 alan anotasyonu çizin (çok küçük ya da tamamen arka plan olabilir).'); step.set('ERROR'); render() }; return
        }
        javafx.application.Platform.runLater { runPhaseRef.set('SPIDER tahmini (2/2)…'); render() }
        def r = runPython(predictCmd(cfg, workDir), appendLine)
        if (!r.ok) {
            javafx.application.Platform.runLater { errorTextRef.set('Tahmin başarısız (çıkış: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '')); step.set('ERROR'); render() }; return
        }
        javafx.application.Platform.runLater { busyLabelRef.set('SPIDER sonuçları QuPath\'e aktarılıyor…'); step.set('BUSY'); render() }
        def imp = importPredictions(workDir, imageData, exp.mode)   /* çalıştırma başlarken yakalanan görüntü — sonuç başka lama yazılmasın */
        javafx.application.Platform.runLater {
            if (!imp.ok) { errorTextRef.set(imp.error); step.set('ERROR'); render() }
            else { resultTextRef.set(buildResultText(workDir, exp, imp)); step.set('RESULT'); render() }
        }
    }, 'AtolyeSPIDER-Run')
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
    // READY + CONFIG ekranlarında ortak organ + granülarite seçicileri (anında kaydeder)
    def makeOrganChoice = { ->
        def cb = new javafx.scene.control.ChoiceBox()
        ORGAN_OPTIONS.each { cb.getItems().add(it) }
        cb.setValue(ORGAN_OPTIONS.contains(cfg.organ) ? cfg.organ : 'colorectal')
        return cb
    }
    def makeGranChoice = { ->
        def cb = new javafx.scene.control.ChoiceBox()
        GRAN_LABELS.values().each { cb.getItems().add(it) }
        def key = GRAN_LABELS.containsKey(cfg.granularity) ? cfg.granularity : 'annotation'
        cb.setValue(GRAN_LABELS[key])
        return cb
    }

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText(workshopText('script.ae607b21867d', 'SPIDER yapılandırması gerekli'))
        def miss = configMissing(cfg)
        addGuidance(workshopText('script.5afae0791a82', 'SPIDER köprüsü bir Python ortamı gerektirir. Aşağıdakiler eksik/geçersiz:\n  • ') +
            (miss.isEmpty() ? workshopText('script.715392eca0f8', '(yok)') : miss.join('\n  • ')) +
            workshopText('script.1cde1245b84d', '\n\nKurulum: Ekler → SPIDER Doku Sınıflandırıcı § kurulum (veya Kaynaklar → İleri kurulumlar G).'))
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
        actions.add(navButton(workshopText('script.3efe57ecbec5', 'Yapılandır ▶'), { step.set('CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText(workshopText('script.12747301bd53', 'SPIDER yapılandırması'))
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def brField = new javafx.scene.control.TextField(cfg.bridge ?: '')
        def mdField = new javafx.scene.control.TextField(cfg.modelDir ?: '')
        def wdField = new javafx.scene.control.TextField(cfg.workDir ?: '')
        def tsField = new javafx.scene.control.TextField(cfg.tileSize ?: '1120')
        def dsField = new javafx.scene.control.TextField(cfg.downsample ?: ''); dsField.setPromptText(workshopText('script.a9a111622b2f', 'boş = otomatik'))
        def mtField = new javafx.scene.control.TextField(cfg.maxTiles ?: '0')
        [pyField, brField, mdField, wdField].each { it.setPrefColumnCount(34) }
        [tsField, dsField, mtField].each { it.setPrefColumnCount(8) }
        def organChoice = makeOrganChoice()
        def granChoice = makeGranChoice()
        def deviceChoice = new javafx.scene.control.ChoiceBox()
        DEVICE_OPTIONS.each { deviceChoice.getItems().add(it) }
        deviceChoice.setValue(DEVICE_OPTIONS.contains(cfg.device) ? cfg.device : 'auto')
        pyFieldRef.set(pyField); bridgeFieldRef.set(brField); modelFieldRef.set(mdField); workFieldRef.set(wdField)
        tileFieldRef.set(tsField); dsFieldRef.set(dsField); maxTFieldRef.set(mtField)
        organChoiceRef.set(organChoice); granChoiceRef.set(granChoice); deviceChoiceRef.set(deviceChoice)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, workshopText('script.8622b674942a', 'Dosya seç')); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir  = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, workshopText('script.15f8cfe83dc9', 'Dizin seç'), null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(workshopText('script.2262e6480a2c', 'SPIDER köprüsü (spider_bridge.py):')), brField, navButton('…', { browseFile(brField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(workshopText('script.52855724c856', 'Model dizini (ops., çevrimdışı):')), mdField, navButton('…', { browseDir(mdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(workshopText('script.2719ca64ba94', 'Çalışma dizini (ops.):')), wdField, navButton('…', { browseDir(wdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(workshopText('script.3c2e26050a7a', 'Organ modeli:')), organChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(workshopText('script.77c7142f1bb2', 'Granülarite:')), granChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(workshopText('script.91c09ece8069', 'Karo boyutu (px):')), tsField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Downsample:'), dsField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(workshopText('script.385cd7498856', 'Anotasyon başı maks. karo (0=tümü):')), mtField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(workshopText('script.72e0fecf25c7', 'Aygıt:')), deviceChoice)
        center.getChildren().add(grid)
        if (!cfg.modelDir?.trim())
            addWarnLabel(workshopText('script.0e6ff2e20ded', '⚠ SPIDER modelleri KAPILIDIR (CC BY-NC 4.0). Model dizini boşsa indirme için HuggingFace token gerekir (huggingface-cli login). Çevrimdışı için indirilmiş klasörü "Model dizini"ne yazın — bkz. Kaynaklar § G.'))
        addGuidance(workshopText('script.056d16d7c6b0', 'Karo boyutu SPIDER için 1120 (sabit bağlam penceresi). Downsample: boş bırakın, lamın µm/px değerinden 0.5 µm/px (20x) hedefine otomatik hesaplanır (0,25 µm/px tarayıcıda 2.0). ' +
            'Granülarite: "Anotasyon düzeyi" her anotasyona tek sınıf; "Karo ızgarası" her 1120 karo için renkli alt-tespit (ısı haritası).'))
        actions.add(navButton(workshopText('script.72278748133a', 'İptal'), { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton(workshopText('script.8369fe631575', 'Bağımlılık kontrolü'), { startSelftest() }, workshopText('script.cc4c82c9ba2f', 'spider_bridge.py selftest — bağımlılık + HF erişimi doğrular')))
        actions.add(navButton(workshopText('script.96f6bd4b06cc', 'Kaydet ▶'), { persistFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
    } else if (cur == 'CHECK_RUNNING') {
        title.setText(workshopText('script.8e84f306c3c6', 'Bağımlılık kontrolü çalışıyor…'))
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton(workshopText('script.3f6920a9ead3', 'İptal et'), { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'CHECK_DONE') {
        title.setText(selftestOkRef.get() ? workshopText('script.93f447fe25d4', 'Bağımlılık kontrolü tamam ✅')
            : workshopText('script.202de3d08ba4', '⚠ Bağımlılık kontrolü BAŞARISIZ — yukarıdaki günlüğe bakın (Python / bağımlılık / HF erişimi)'))
        addLiveLog()
        actions.add(navButton(workshopText('script.c8b5f18cded5', '◀ Yapılandırmaya dön'), { step.set('CONFIG'); render() }))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText(workshopText('script.b4c901dc184b', 'Görüntü açık değil'))
            addGuidance(workshopText('script.5dc531b09dd4', 'Önce bir lam açın ve sınıflandırmak istediğiniz bölge(ler) için alan anotasyonu çizin, sonra "⟳ Yenile".'))
            actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
            actions.add(navButton(workshopText('script.b528a2cc114e', 'Yapılandır'), { step.set('CONFIG'); render() }))
            actions.add(navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }))
        } else {
            def workDir = resolveWorkDir(cfg, imageData)
            def anns = imageData.getHierarchy().getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }
            title.setText(workshopText('script.79892e25adf7', 'SPIDER — hazır'))
            def sb = new StringBuilder()
            sb << "Lam          : " << imageNameOf(imageData) << "\n"
            sb << "Çalışma dizini : " << workDir.getAbsolutePath() << "\n"
            sb << "Python         : " << (cfg.python ?: '(ayarsız)') << "\n"
            sb << "Model          : " << (cfg.modelDir?.trim() ? cfg.modelDir : ('HuggingFace (histai/SPIDER-' + (cfg.organ ?: 'colorectal') + '-model, KAPILI)')) << "\n"
            sb << "Aygıt          : " << cfg.device << "\n"
            sb << String.format(java.util.Locale.US, "Alan anotasyonu: %,d%n", anns.size())
            addMonoArea(sb.toString())
            addGuidance(workshopText('script.6a2ae0e96800', 'SPIDER ÖN-EĞİTİMLİDİR — kendi etiketlerinize gerek yok; sadece organ + granülarite seçip "Sınıflandır" deyin.'))

            // Organ + granülarite hızlı seçiciler (anında kaydeder, READY'yi tazeler)
            def organChoice = makeOrganChoice()
            organChoice.valueProperty().addListener({ obs, o, n ->
                if (n != null) { prefs.put(PREF_ORGAN, n.toString()); try { prefs.flush() } catch (Throwable ig) {}; render() }
            } as javafx.beans.value.ChangeListener)
            def granChoice = makeGranChoice()
            granChoice.valueProperty().addListener({ obs, o, n ->
                if (n != null) { prefs.put(PREF_GRAN, granLabelToKey(n.toString())); try { prefs.flush() } catch (Throwable ig) {}; render() }
            } as javafx.beans.value.ChangeListener)
            def selRow = new javafx.scene.layout.HBox(8)
            selRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            selRow.getChildren().addAll(new javafx.scene.control.Label('Organ:'), organChoice,
                new javafx.scene.control.Label(workshopText('script.78c5e74fe6b1', '   Granülarite:')), granChoice)
            center.getChildren().add(selRow)

            if (!cfg.modelDir?.trim())
                addWarnLabel(workshopText('script.2611f35e062f', '⚠ KAPILI model — HuggingFace token gerekir (huggingface-cli login) ya da "Model dizini" ayarlayın. CPU yavaştır; GPU önerilir.'))

            boolean canRun = configComplete(cfg) && anns.size() >= 1
            actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
            actions.add(navButton(workshopText('script.b528a2cc114e', 'Yapılandır'), { step.set('CONFIG'); render() }))
            actions.add(navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }))
            def runBtn = navButton(workshopText('script.fbdec7f3d90f', 'Sınıflandır ▶'), { startRun() }, workshopText('script.d5b0fc54585d', 'Seçili organ modeliyle alan anotasyonlarını sınıflandırır'))
            runBtn.setDisable(!canRun)
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING') {
        title.setText(runPhaseRef.get())
        addGuidance(workshopText('script.74f34d4d16a3', 'Python köprüsü çalışıyor. Çıktı aşağıda akıyor. Zaman aşımı: ') + PYTHON_TIMEOUT_SECONDS + workshopText('script.9d84a2f3efc0', ' sn.'))
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton(workshopText('script.3f6920a9ead3', 'İptal et'), { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'BUSY') {
        title.setText(busyLabelRef.get()); addGuidance(workshopText('script.245c572ec2ea', 'Lütfen bekleyin…')); center.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        title.setText(workshopText('script.92c17020894f', 'Tamamlandı ✅'))
        addMonoArea(resultTextRef.get())
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
        actions.add(navButton(workshopText('script.a8bcca42d9ce', 'Kopyala'), { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton(workshopText('script.c94499b243e4', '↻ Yeniden çalıştır'), { step.set('READY'); render() }))
    } else { // ERROR
        title.setText(workshopText('script.307b9dc7912d', 'Hata'))
        addMonoArea(errorTextRef.get())
        actions.add(navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('READY'); render() }))
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
    }

    // Alt çubuk: "Üstte tut" (sol) + disclaimer + eylem düğmeleri (sağ)
    def topChk = new javafx.scene.control.CheckBox(workshopText('script.70ca64c0c43a', 'Üstte tut'))
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

    def disclaimer = new javafx.scene.control.Label(workshopText('script.04e70fc651a3', 'Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.'))
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
        stage.setTitle(workshopText('script.ec575cbc114f', 'SPIDER doku sınıflandırıcı sihirbazı'))
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.7fd583bc0c88', 'Sihirbaz açılamadı'), workshopText('script.9aac4912cb86', 'Bu araç başlatılamadı.\n\nYapabilecekleriniz:\n  1. Araç açık bir lam istiyorsa önce lamı açın.\n  2. QuPath\'i tamamen kapatıp yeniden açın ve tekrar deneyin.\n  3. Sorun sürerse View → Show log penceresinin ekran görüntüsünü atölye düzenleyicisine gönderin.\n\nTeknik ayrıntı: ') + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: workshopText('script.e136f4a3bb45', '(ileti yok)')))
    }
}
println "✓ SPIDER doku sınıflandırıcı sihirbazı açıldı."

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
