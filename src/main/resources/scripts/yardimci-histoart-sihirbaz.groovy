/**
 * Yardımcı - HistoART Artefakt Tespiti Sihirbazı (tek pencere köprü)
 * -------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   FDA CDRH/OSEL (DIDSR) **HistoART** artefakt sınıflandırıcılarıyla açık
 *   slayttaki karoları (tile) etiketler — tek pencereden:
 *     1. Varyant (DLA/FMA) + eşik + aygıt seçin; gerekiyorsa ortamı kurun ve
 *        model ağırlıklarını indirin (② ③ düğmeleri).
 *     2. ÇALIŞTIR — seçili anotasyonların (yoksa tüm slaytın) karolarını diske
 *        yazar; Python köprüsü (histoart_bridge.py) iki aşamada sınıflandırır.
 *     3. İçe aktarım — her karo için renkli ALT-TESPİT (KK ısı haritası) +
 *        kopyalanabilir bir "kalite karnesi".
 *   Derin öğrenme QuPath DIŞINDA bir Python venv'inde çalışır; karoları bu betik
 *   QuPath tarafında üretir (openslide gibi yerel kütüphane GEREKMEZ).
 *
 * İKİ AŞAMALI TASARIM (önemli):
 *   Çoklu (multiclass) modelin sınıf kümesinde "temiz" sınıfı YOKTUR — altı
 *   sınıfın hepsi artefakttır. Çoklu model tek başına çalıştırılsaydı temiz bir
 *   karo da mecburen altı etiketten birini alırdı (sessizce, yüksek görünen bir
 *   olasılıkla). Bu yüzden önce İKİLİ model "artefakt var/yok" der; tür
 *   sınıflandırması YALNIZCA artefakt bulunan karolara uygulanır.
 *
 * ÖLÇEK (sessiz hata kaynağı):
 *   HistoART eğitim yamaları 224×224 ve **40× (~0,25 µm/px)**'tir. Bu sihirbaz
 *   slaytın µm/px değerinden hedef 0,25 µm/px'e göre downsample'ı KENDİSİ önerir
 *   ve kalibrasyon yoksa/uzaksa uyarır. Yanlış ölçekte model hata vermez —
 *   yalnızca yanlış cevap verir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Çıktı: karo başına artefakt olasılığı + (artefaktlıysa) tür etiketi ve
 *     tür olasılıkları; slayt düzeyinde temiz/artefaktlı karo sayımı ve oranı.
 *   • Kabul/ret KARARI üretmez — eşiği siz lab protokolünüze göre seçersiniz.
 *   • GrandQC'nin YERİNE GEÇMEZ: GrandQC piksel maskesi üretir (nerede);
 *     HistoART karo düzeyinde sınıflandırır (ne kadar, hangi tür) ve maske
 *     üretmez — "temiz doku" zinciri GrandQC ile kurulur.
 *   • HistoART bir ÖN BASKIDIR (hakem değerlendirmesinden geçmemiştir) ve
 *     FDA'nın RST kataloğunda yer almaz.
 *
 * KULLANIM:
 *   1. [Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller →
 *      Atölye Python ortam yöneticisi] ile 'histoart' ortamını kurun (② düğmesi
 *      bunu doğrudan açar).
 *   2. Bir slayt açın; istersen bölge anotasyonu çizin (çizmezseniz tüm slayt
 *      örneklenir, "maks. karo" sınırıyla).
 *   3. Bu sihirbazı açın → ③ "Model ağırlıklarını indir" → "Çalıştır".
 *
 * VARYANTLAR:
 *   • DLA (varsayılan) — ResNet50 ince ayar, ~94 MB, AÇIK (CC0), AUROC 0,977.
 *   • FMA — ince ayarlı UNI, ~1,21 GB, AUROC 0,995; omurga `MahmoodLab/uni`
 *     **KAPILIDIR** (CC-BY-NC-ND 4.0 + HF erişim onayı). Erişim yoksa model
 *     kurulum anında 401/403 verir.
 *   • KBA köprülenmez (turlanmış SVM + doğrulanmamış öznitelik hattı).
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Kahaki S ve ark. (2025), arXiv:2507.00044 — HistoART. Kod: CC0-1.0.
 *   • Veri: HistoArtifacts (CC BY 4.0) — 224×224 @ 40×; Zenodo 10809442.
 *   • Ağırlıklar: https://huggingface.co/didsr/HistoArt
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
long PYTHON_TIMEOUT_SECONDS = 3600L
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def DEVICE_OPTIONS  = ['auto', 'cpu', 'cuda']
def VARIANT_LABELS  = ['dla': 'DLA — ResNet50 (açık, CC0, ~94 MB)',
                       'fma': 'FMA — ince ayarlı UNI (🔒 KAPILI, ~1,2 GB)']
def variantLabelToKey = { String lbl -> def k = VARIANT_LABELS.find { it.value == lbl }?.key; return (k ?: 'dla') }

// HistoART eğitim yaması: 224×224 @ 40× (~0,25 µm/px). Bu iki sayı modelin
// sözleşmesidir — değiştirmeyin; downsample bunlardan TÜRETİLİR.
int    HISTOART_PATCH = 224
double HISTOART_MPP   = 0.25d

// Çoklu sınıf sırası — köprüdeki ARTIFACT_CLASSES ile AYNI olmalıdır.
// (results/{dla,fma,kba}_multi.csv başlıklarından kurtarıldı.)
def TYPE_TR = ['blood':'Kan', 'blur':'Odak dışı', 'bubble':'Hava kabarcığı',
               'damage':'Doku hasarı', 'fold':'Doku katlanması', 'marker':'Kalem işareti']
def CLEAN_TR = 'Temiz'
def PROB_MEAS = 'HistoART: Artefakt olasılığı'
def HISTOART_SENTINEL = 'HistoART KK'   // yeniden çalıştırmada idempotent temizlik adı

// Sınıf renkleri — GrandQC ekindeki (§7.3) karşılıklarla uyumlu tutulur.
def CLASS_COLOR = [
    (CLEAN_TR)          : ColorTools.makeRGB( 60, 180,  75),
    'Kan'               : ColorTools.makeRGB(230,  25,  75),
    'Odak dışı'         : ColorTools.makeRGB(  0, 130, 200),
    'Hava kabarcığı'    : ColorTools.makeRGB( 70, 240, 240),
    'Doku hasarı'       : ColorTools.makeRGB(245, 130,  48),
    'Doku katlanması'   : ColorTools.makeRGB(145,  30, 180),
    'Kalem işareti'     : ColorTools.makeRGB(240,  50, 230)
]

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır) ──
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/histoart')
def PREF_PYTHON = 'python'
def PREF_BRIDGE = 'bridge'
def PREF_MODEL  = 'modelDir'
def PREF_WORK   = 'workDir'
def PREF_DS     = 'downsample'
def PREF_MAXT   = 'maxTiles'
def PREF_VARIANT= 'variant'
def PREF_THRESH = 'threshold'
def PREF_DEVICE = 'device'

def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
def defaultModelDir = { -> new File(new File(atolyeDataRoot(), 'models'), 'histoart') }
def applyCacheEnv = { pb ->
    try {
        def cache = new File(atolyeDataRoot(), 'cache'); cache.mkdirs()
        def hf = new File(cache, 'huggingface'); def env = pb.environment()
        env.put('HF_HOME', hf.getAbsolutePath()); env.put('HF_HUB_CACHE', new File(hf, 'hub').getAbsolutePath())
        env.put('TORCH_HOME', new File(cache, 'torch').getAbsolutePath())
    } catch (Throwable ignore) {}
}

def loadConfig = { ->
    [ python    : ({ -> def __p = prefs.get(PREF_PYTHON, ''); if (__p?.trim()) return __p; def __r = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('py.histoart', ''); if (__r?.trim() && new File(__r.trim()).isFile()) return __r.trim(); def __v = new File(new File(atolyeDataRoot(), 'runtimes'), 'histoart/.venv'); def __w = new File(__v, 'Scripts/python.exe'); def __n = new File(__v, 'bin/python'); __w.isFile() ? __w.getAbsolutePath() : (__n.isFile() ? __n.getAbsolutePath() : '') }).call(),
      bridge    : prefs.get(PREF_BRIDGE, ''),
      modelDir  : ({ -> def __m = prefs.get(PREF_MODEL, ''); (__m?.trim()) ? __m.trim() : defaultModelDir().getAbsolutePath() }).call(),
      workDir   : prefs.get(PREF_WORK,   ''),
      downsample: prefs.get(PREF_DS,     ''),          // boş = slayttan türet
      maxTiles  : prefs.get(PREF_MAXT,   '2000'),
      variant   : prefs.get(PREF_VARIANT,'dla'),
      threshold : prefs.get(PREF_THRESH, '0.5'),
      device    : prefs.get(PREF_DEVICE, 'auto') ]
}

def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile())
        miss << 'Python yürütülebilir (python.exe) — ② ile ortamı kurun'
    if (!cfg.bridge?.trim() || !(new File(cfg.bridge)).isFile())
        miss << 'HistoART köprüsü (histoart_bridge.py)'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

def parseIntOr = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }
def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().trim()) } catch (Throwable t) { return d } }

// ── Ölçek: slayt µm/px → HistoART'ın beklediği 0,25 µm/px ───────────────────
def slideMpp = { imageData ->
    try {
        def cal = imageData.getServer().getPixelCalibration()
        if (cal != null && cal.hasPixelSizeMicrons()) return cal.getAveragedPixelSizeMicrons()
    } catch (Throwable ignore) {}
    return Double.NaN
}
// Önerilen downsample = hedef µm/px ÷ slayt µm/px. Kalibrasyon yoksa 1.0.
def suggestedDownsample = { imageData ->
    double mpp = slideMpp(imageData)
    if (Double.isNaN(mpp) || mpp <= 0.0d) return 1.0d
    return HISTOART_MPP / mpp
}
def effectiveDownsample = { cfg, imageData ->
    def s = (cfg.downsample ?: '').toString().trim()
    return s ? parseDoubleOr(s, 1.0d) : suggestedDownsample(imageData)
}
// Kalibrasyon uyarısı (WSInfer dersi: yanlış ölçek sessizdir)
def scaleWarning = { cfg, imageData ->
    double mpp = slideMpp(imageData)
    if (Double.isNaN(mpp))
        return '⚠ Slayt KALİBRE DEĞİL (µm/px yok). HistoART 40× (~0,25 µm/px) bekler; ölçek doğrulanamıyor — sonuçlar sessizce yanlış olabilir.'
    double ds = effectiveDownsample(cfg, imageData)
    double eff = mpp * ds
    if (Math.abs(eff - HISTOART_MPP) > (HISTOART_MPP * 0.25d))
        return String.format(java.util.Locale.US,
            '⚠ Etkin çözünürlük %.3f µm/px — HistoART %.2f µm/px (40×) ile eğitildi. Downsample\'ı boş bırakın (otomatik) ya da düzeltin.', eff, HISTOART_MPP)
    return null
}

// ── Çalışma dizinini çöz ────────────────────────────────────────────────────
def resolveWorkDir = { cfg, imageData ->
    def wd = cfg.workDir?.trim()
    if (wd) return new File(wd)
    def project = QP.getProject()
    if (project != null && project.getPath() != null)
        return new File(project.getPath().getParent().toFile(), 'histoart_work')
    try {
        def uris = imageData.getServer().getURIs()
        if (uris != null && !uris.isEmpty()) {
            def uri = uris.iterator().next()
            if ('file'.equals(uri.getScheme())) {
                def f = new File(uri)
                if (f.getParentFile() != null) return new File(f.getParentFile(), 'histoart_work')
            }
        }
    } catch (Throwable ignore) {}
    return new File(System.getProperty('java.io.tmpdir'), 'histoart_work')
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

// ── ROI'yi ızgara karolara böl; her karo için sink() çağrılır ───────────────
def gridTiles = { server, roi, double downsample, int tileSize, int maxTiles, Closure sink ->
    if (roi == null || !roi.isArea()) return 0
    int phys = (int) Math.max(1, Math.round(tileSize * downsample))
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
    return count
}

def writePng = { img, File f -> if (f.getParentFile() != null) f.getParentFile().mkdirs(); javax.imageio.ImageIO.write(img, 'PNG', f) }
def writeJson = { obj, File f ->
    if (f.getParentFile() != null) f.getParentFile().mkdirs()
    f.setText(qupath.lib.io.GsonTools.getInstance(true).toJson(obj), 'UTF-8')
}

// ── Karoları dışa aktar + manifest yaz ──────────────────────────────────────
//   Anotasyon varsa her alan anotasyonu; yoksa TÜM SLAYT (maks. karo sınırıyla).
def exportTiles = { imageData, File workDir, cfg, Closure appendLine ->
    def server = imageData.getServer()
    double downsample = effectiveDownsample(cfg, imageData)
    int maxTiles = parseIntOr(cfg.maxTiles, 2000)
    def tilesRoot = new File(workDir, 'tiles')
    if (tilesRoot.isDirectory()) tilesRoot.deleteDir()
    tilesRoot.mkdirs()

    def anns = imageData.getHierarchy().getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }
    def manifest = []
    int total = 0
    boolean wholeSlide = anns.isEmpty()

    def emit = { String parentId, int x, int y, int phys, img ->
        def id = (parentId ?: 'slide') + '::x' + x + '_y' + y
        writePng(img, new File(tilesRoot, id.replace('::', '__') + '.png'))
        manifest << [id: id, path: new File(tilesRoot, id.replace('::', '__') + '.png').getAbsolutePath(),
                     parent_id: parentId, x: x, y: y, size: phys]
    }

    if (wholeSlide) {
        def plane = qupath.lib.regions.ImagePlane.getDefaultPlane()
        def full = qupath.lib.roi.ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(), plane)
        appendLine('Anotasyon yok — tüm slayt örnekleniyor (maks. ' + maxTiles + ' karo).')
        total = gridTiles(server, full, downsample, HISTOART_PATCH, maxTiles, { x, y, phys, img ->
            emit(null, x, y, phys, img)
        })
        appendLine('  karolar: ' + total)
    } else {
        int i = 0
        anns.each { ann ->
            i++
            def annId = ann.getID().toString()
            int n = gridTiles(server, ann.getROI(), downsample, HISTOART_PATCH, maxTiles, { x, y, phys, img ->
                emit(annId, x, y, phys, img)
            })
            total += n
            appendLine('  karolar: anotasyon ' + i + '/' + anns.size() + ' (' + n + ')')
        }
    }
    writeJson(manifest, new File(workDir, 'tiles.json'))
    return [ok: true, nAnns: anns.size(), nTiles: total, wholeSlide: wholeSlide,
            downsample: downsample, variant: (cfg.variant ?: 'dla')]
}

// ── Python komutları ────────────────────────────────────────────────────────
def predictCmd = { cfg, File workDir ->
    [cfg.python, cfg.bridge, 'predict',
     '--manifest',   new File(workDir, 'tiles.json').getAbsolutePath(),
     '--output-dir', workDir.getAbsolutePath(),
     '--model-dir',  cfg.modelDir,
     '--variant',    (cfg.variant ?: 'dla'),
     '--task',       'both',
     '--threshold',  (cfg.threshold ?: '0.5'),
     '--device',     (cfg.device ?: 'auto')]
}
def selftestCmd = { cfg ->
    [cfg.python, cfg.bridge, 'selftest', '--variant', (cfg.variant ?: 'dla'), '--model-dir', cfg.modelDir]
}
def downloadCmd = { cfg ->
    [cfg.python, cfg.bridge, 'download', '--model-dir', cfg.modelDir,
     '--variant', (cfg.variant ?: 'dla'), '--task', 'both']
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
// CONFIG_INCOMPLETE | CONFIG | CHECK_RUNNING | CHECK_DONE | DL_RUNNING | DL_DONE
//   | READY | RUN_RUNNING | BUSY | RESULT | ERROR
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def selftestOkRef = new java.util.concurrent.atomic.AtomicBoolean(true)
def downloadOkRef = new java.util.concurrent.atomic.AtomicBoolean(true)
def processRef    = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef   = new java.util.concurrent.atomic.AtomicReference('')
def busyLabelRef  = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def pyFieldRef      = new java.util.concurrent.atomic.AtomicReference(null)
def bridgeFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def modelFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def workFieldRef    = new java.util.concurrent.atomic.AtomicReference(null)
def dsFieldRef      = new java.util.concurrent.atomic.AtomicReference(null)
def maxTFieldRef    = new java.util.concurrent.atomic.AtomicReference(null)
def threshFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def variantChoiceRef= new java.util.concurrent.atomic.AtomicReference(null)
def deviceChoiceRef = new java.util.concurrent.atomic.AtomicReference(null)
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

// ── Tahminleri içe aktar (karo başına renkli alt-tespit) ────────────────────
def importPredictions = { File workDir, imageData, exp ->
    def predsFile = new File(workDir, 'predictions.json')
    if (!predsFile.isFile())
        return [ok: false, error: 'predictions.json bulunamadı:\n' + predsFile.getAbsolutePath()]
    def manFile = new File(workDir, 'tiles.json')

    // Karo geometrisi manifestten (tahmin dosyası yalnız id taşır)
    def geom = [:]
    try {
        def r = new java.io.InputStreamReader(new java.io.FileInputStream(manFile), java.nio.charset.StandardCharsets.UTF_8)
        try {
            com.google.gson.JsonParser.parseReader(r).getAsJsonArray().each { el ->
                def o = el.getAsJsonObject()
                geom[o.get('id').getAsString()] = [
                    x: o.get('x').getAsDouble(), y: o.get('y').getAsDouble(),
                    size: o.get('size').getAsDouble(),
                    parentId: (o.has('parent_id') && !o.get('parent_id').isJsonNull()) ? o.get('parent_id').getAsString() : null]
            }
        } finally { r.close() }
    } catch (Throwable t) {
        return [ok: false, error: 'tiles.json okunamadı:\n' + (t.getMessage() ?: t.getClass().getSimpleName())]
    }

    def records = []
    try {
        def reader = new java.io.InputStreamReader(new java.io.FileInputStream(predsFile), java.nio.charset.StandardCharsets.UTF_8)
        try {
            com.google.gson.JsonParser.parseReader(reader).getAsJsonArray().each { el ->
                def o = el.getAsJsonObject()
                if (!o.has('id') || o.get('id').isJsonNull()) return
                def rec = [id: o.get('id').getAsString()]
                rec.artifact = (o.has('artifact') && !o.get('artifact').isJsonNull()) ? o.get('artifact').getAsBoolean() : null
                rec.prob = (o.has('artifact_prob') && !o.get('artifact_prob').isJsonNull()) ? o.get('artifact_prob').getAsDouble() : Double.NaN
                rec.type = (o.has('artifact_type') && !o.get('artifact_type').isJsonNull()) ? o.get('artifact_type').getAsString() : null
                rec.typeProbs = [:]
                if (o.has('type_probabilities') && o.get('type_probabilities').isJsonObject())
                    o.getAsJsonObject('type_probabilities').entrySet().each { e -> rec.typeProbs[e.getKey()] = e.getValue().getAsDouble() }
                records << rec
            }
        } finally { reader.close() }
    } catch (Throwable t) {
        return [ok: false, error: 'predictions.json okunamadı:\n' + (t.getMessage() ?: t.getClass().getSimpleName())]
    }

    def ensureClass = { String name ->
        def pc = QP.getPathClass(name)
        if (pc.getColor() == null && CLASS_COLOR.containsKey(name)) pc.setColor(CLASS_COLOR[name])
        return pc
    }

    def hierarchy = imageData.getHierarchy()
    def plane = qupath.lib.regions.ImagePlane.getDefaultPlane()
    def dist = new TreeMap<String, Integer>()
    int nClean = 0, nArt = 0, nSkipped = 0
    def allDets = []

    // Idempotent yeniden çalıştırma: önceki çalıştırmanın sentinel anotasyonu ALT TESPİTLERİYLE
    // birlikte silinir. Aksi halde her çalıştırma binlerce tespit biriktirir (Spotiflow'un
    // "yeniden çalıştırma eski noktaları silmez" tuzağı). Kullanıcının kendi anotasyonlarına
    // hiç dokunulmaz — tespitler daima bu sentinelin altına yazılır.
    hierarchy.removeObjects(hierarchy.getAnnotationObjects().findAll { it.getName() == HISTOART_SENTINEL }, false)

    records.each { r ->
        def g = geom[r.id]
        if (g == null || r.artifact == null) { nSkipped++; return }
        String label = r.artifact ? (TYPE_TR[r.type] ?: 'Artefakt') : CLEAN_TR
        def pc = ensureClass(label)
        def roi = qupath.lib.roi.ROIs.createRectangleROI((double) g.x, (double) g.y, (double) g.size, (double) g.size, plane)
        def det = qupath.lib.objects.PathObjects.createDetectionObject(roi, pc)
        def ml = det.getMeasurementList()
        if (!Double.isNaN(r.prob)) ml.put(PROB_MEAS, (double) r.prob)
        r.typeProbs.each { kk, vv -> ml.put('HistoART: ' + (TYPE_TR[kk] ?: kk) + ' olasılığı', (double) vv) }
        det.setLocked(true)
        allDets << det
        dist[label] = (dist.getOrDefault(label, 0)) + 1
        if (r.artifact) nArt++ else nClean++
    }

    // Tüm karo tespitleri tek bir kilitli "HistoART KK" anotasyonunun altına yazılır:
    // tek tıkla seçilir, tek adımda silinir, yeniden çalıştırmada temiz biçimde değişir.
    def srv = imageData.getServer()
    def sentinelRoi = qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(), plane)
    def sentinel = qupath.lib.objects.PathObjects.createAnnotationObject(sentinelRoi)
    sentinel.setName(HISTOART_SENTINEL)
    if (!allDets.isEmpty()) sentinel.addChildObjects(allDets)
    sentinel.setLocked(true)
    hierarchy.addObjects([sentinel])
    QP.fireHierarchyUpdate()
    javafx.application.Platform.runLater { try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {} }
    int scored = nClean + nArt
    return [ok: true, nClean: nClean, nArtifact: nArt, nScored: scored, nSkipped: nSkipped, dist: dist]
}

// ── Özet metni (kalite karnesi) ─────────────────────────────────────────────
def buildResultText = { File workDir, exp, imp, cfg, imageData ->
    def sb = new StringBuilder()
    sb << "HistoART — KALİTE KARNESİ\n"
    sb << "═════════════════════════\n\n"
    sb << "Slayt        : " << imageNameOf(imageData) << "\n"
    sb << "Varyant      : " << ((exp?.variant == 'fma') ? 'FMA (UNI)' : 'DLA (ResNet50)') << "\n"
    sb << "Eşik         : " << (cfg.threshold ?: '0.5') << "\n"
    sb << "Kapsam       : " << (exp?.wholeSlide ? 'tüm slayt (örneklem)' : ('anotasyon (' + (exp?.nAnns ?: 0) + ')')) << "\n"
    double mpp = slideMpp(imageData)
    sb << String.format(java.util.Locale.US, "Ölçek        : downsample %.3f", (double) (exp?.downsample ?: 1.0d))
    if (!Double.isNaN(mpp)) sb << String.format(java.util.Locale.US, "  ·  etkin %.3f µm/px (hedef %.2f)", mpp * (double) (exp?.downsample ?: 1.0d), HISTOART_MPP)
    else sb << "  ·  slayt KALİBRE DEĞİL"
    sb << "\n\n"
    if (imp != null && imp.ok) {
        int scored = (int) imp.nScored
        double cleanPct = (scored > 0) ? (100.0d * imp.nClean / scored) : 0.0d
        sb << String.format(java.util.Locale.US, "Değerlendirilen karo : %,d%n", scored)
        sb << String.format(java.util.Locale.US, "Temiz                : %,d  (%.1f%%)%n", (int) imp.nClean, cleanPct)
        sb << String.format(java.util.Locale.US, "Artefaktlı           : %,d  (%.1f%%)%n", (int) imp.nArtifact, 100.0d - cleanPct)
        if (imp.nSkipped > 0) sb << String.format(java.util.Locale.US, "Atlanan              : %,d%n", (int) imp.nSkipped)
        sb << "\nSınıf dağılımı (karo):\n"
        imp.dist.each { cn, n ->
            double pct = (scored > 0) ? (100.0d * n / scored) : 0.0d
            sb << String.format(java.util.Locale.US, "  %-18s : %,7d  (%.1f%%)%n", cn, (int) n, pct)
        }
    } else {
        sb << "  (sonuç yok)\n"
    }
    sb << "\nHer karo, sınıfına göre renkli ve kilitli bir ALT-TESPİT olarak, tek bir\n"
    sb << "'" << HISTOART_SENTINEL << "' anotasyonunun altına yazıldı — yeniden çalıştırırsanız bu\n"
    sb << "anotasyon alt tespitleriyle birlikte silinip yenilenir (birikme olmaz); kendi\n"
    sb << "anotasyonlarınıza dokunulmaz. '" << PROB_MEAS << "' ve tür olasılıkları\n"
    sb << "Measurements panelinde.\n"
    sb << "\nKabul/ret KARARI üretilmez — eşiği lab protokolünüze göre siz seçersiniz\n"
    sb << "(Ek → WSI Kalite Kontrol Metrikleri § 4).\n"
    sb << "HistoART bir ÖN BASKIDIR ve piksel maskesi ÜRETMEZ — 'temiz doku' zinciri için\n"
    sb << "GrandQC'yi kullanın (§ 7.5). Çıktı bir derin öğrenme tahminidir; görsel doğrulama gerekir.\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

// ── Headless: yapılandırmayı raporla, etkileşim yok ─────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println "Önce bir slayt açın."; return }
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    println "HistoART yapılandırması: python=${cfg.python ?: '(ayarsız)'} köprü=${cfg.bridge ?: '(ayarsız)'} model=${cfg.modelDir} varyant=${cfg.variant} eşik=${cfg.threshold} aygıt=${cfg.device}"
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(', ')}"
    def w = scaleWarning(cfg, imageData)
    if (w) println w
    println "HistoART sihirbazı için QuPath arayüzü gerekir (headless çalıştırılamaz)."
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
    prefs.put(PREF_DS,     textOf(dsFieldRef))
    def mt = textOf(maxTFieldRef); prefs.put(PREF_MAXT, mt ?: '2000')
    def th = textOf(threshFieldRef); prefs.put(PREF_THRESH, th ?: '0.5')
    def vr = variantChoiceRef.get(); prefs.put(PREF_VARIANT, (vr != null && vr.getValue() != null) ? variantLabelToKey(vr.getValue()) : 'dla')
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

// ── Ortam yöneticisini aç (② düğmesi) ──────────────────────────────────────
// Sözleşme (CLAUDE.md): [envId | envIds, wizard, onReturn: { reopen -> … }]. Ortam kurulumu
// bitince yöneticideki "Sihirbaza dön ▶" (ya da pencereyi kapatmak) bu pencereyi öne getirir ve
// yapılandırmayı yeniden okur. Çalışan bir işlem sürerken ekran değiştirilmez; yalnız pencere öne gelir.
def envReturnHook = [
    envId   : 'histoart',
    wizard  : 'HistoART artefakt tespiti',
    onReturn: { reopen ->
        javafx.application.Platform.runLater {
            if (stage == null || (!stage.isShowing() && !reopen)) return
            try {
                if (['CONFIG_INCOMPLETE', 'CONFIG', 'READY', 'CHECK_DONE', 'DL_DONE', 'ERROR'].contains(step.get())) {
                    step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render()
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
                    'Menüden açın: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi ("histoart" kaydını kurun).') }
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
    }, 'AtolyeHistoART-Check')
    worker.setDaemon(true); worker.start()
}

// ── Model ağırlıklarını indir (③ düğmesi) ──────────────────────────────────
def startDownload = {
    persistFields()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set('Önce yapılandırmayı tamamlayın:\n  • ' + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Model ağırlıkları indiriliyor'); step.set('DL_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        appendLine('Model dizini: ' + cfg.modelDir)
        def r = runPython(downloadCmd(cfg), appendLine)
        javafx.application.Platform.runLater { downloadOkRef.set(r.ok); step.set('DL_DONE'); render() }
    }, 'AtolyeHistoART-Download')
    worker.setDaemon(true); worker.start()
}

// ── Çalıştırma akışı ────────────────────────────────────────────────────────
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
        if (exp.nTiles == 0) {
            javafx.application.Platform.runLater { errorTextRef.set('Sınıflandırılacak karo üretilemedi.\nBölge çok küçük ya da tamamen arka plan olabilir.'); step.set('ERROR'); render() }; return
        }
        javafx.application.Platform.runLater { runPhaseRef.set('HistoART tahmini (2/2) — ikili → tür…'); render() }
        def r = runPython(predictCmd(cfg, workDir), appendLine)
        if (!r.ok) {
            javafx.application.Platform.runLater { errorTextRef.set('Tahmin başarısız (çıkış: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '')); step.set('ERROR'); render() }; return
        }
        javafx.application.Platform.runLater { busyLabelRef.set('HistoART sonuçları QuPath\'e aktarılıyor…'); step.set('BUSY'); render() }
        def imp = importPredictions(workDir, QP.getCurrentImageData(), exp)
        javafx.application.Platform.runLater {
            if (!imp.ok) { errorTextRef.set(imp.error); step.set('ERROR'); render() }
            else { resultTextRef.set(buildResultText(workDir, exp, imp, cfg, QP.getCurrentImageData())); step.set('RESULT'); render() }
        }
    }, 'AtolyeHistoART-Run')
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
    def makeVariantChoice = { ->
        def cb = new javafx.scene.control.ChoiceBox()
        VARIANT_LABELS.values().each { cb.getItems().add(it) }
        def key = VARIANT_LABELS.containsKey(cfg.variant) ? cfg.variant : 'dla'
        cb.setValue(VARIANT_LABELS[key])
        return cb
    }

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('HistoART yapılandırması gerekli')
        def miss = configMissing(cfg)
        addGuidance('HistoART köprüsü bir Python ortamı gerektirir. Aşağıdakiler eksik/geçersiz:\n  • ' +
            (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')) +
            '\n\n② ile "histoart" ortamını kurun, sonra köprü dosyasını (histoart_bridge.py) gösterin.')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('② Ortamı kur', { launchEnvManager() }, 'Atölye Python ortam yöneticisini açar ve "histoart" kaydını vurgular'))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText('HistoART yapılandırması')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def brField = new javafx.scene.control.TextField(cfg.bridge ?: '')
        def mdField = new javafx.scene.control.TextField(cfg.modelDir ?: '')
        def wdField = new javafx.scene.control.TextField(cfg.workDir ?: '')
        def dsField = new javafx.scene.control.TextField(cfg.downsample ?: '')
        def mtField = new javafx.scene.control.TextField(cfg.maxTiles ?: '2000')
        def thField = new javafx.scene.control.TextField(cfg.threshold ?: '0.5')
        [pyField, brField, mdField, wdField].each { it.setPrefColumnCount(34) }
        [dsField, mtField, thField].each { it.setPrefColumnCount(8) }
        def variantChoice = makeVariantChoice()
        def deviceChoice = new javafx.scene.control.ChoiceBox()
        DEVICE_OPTIONS.each { deviceChoice.getItems().add(it) }
        deviceChoice.setValue(DEVICE_OPTIONS.contains(cfg.device) ? cfg.device : 'auto')
        pyFieldRef.set(pyField); bridgeFieldRef.set(brField); modelFieldRef.set(mdField); workFieldRef.set(wdField)
        dsFieldRef.set(dsField); maxTFieldRef.set(mtField); threshFieldRef.set(thField)
        variantChoiceRef.set(variantChoice); deviceChoiceRef.set(deviceChoice)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Dosya seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir  = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Dizin seç', null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('HistoART köprüsü (histoart_bridge.py):'), brField, navButton('…', { browseFile(brField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Model dizini:'), mdField, navButton('…', { browseDir(mdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Çalışma dizini (ops.):'), wdField, navButton('…', { browseDir(wdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Varyant:'), variantChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Artefakt eşiği (0–1):'), thField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Downsample (boş = otomatik):'), dsField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Maks. karo (0=tümü):'), mtField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Aygıt:'), deviceChoice)
        center.getChildren().add(grid)
        addGuidance('Karo boyutu 224 px olarak SABİTTİR (HistoART eğitim yaması). Downsample boş bırakılırsa slaytın µm/px değerinden ' +
            'hedef 0,25 µm/px (40×) için otomatik hesaplanır — GT450/AT2 gibi 40× tarayıcılarda bu ~1,0 olur. ' +
            'Eşik yalnız ikili aşamayı etkiler; tür sınıflandırması yalnızca eşiği geçen karolara uygulanır.')
        if (cfg.variant == 'fma')
            addWarnLabel('⚠ FMA omurgası MahmoodLab/uni üzerinden kurulur ve bu depo KAPILIDIR (CC-BY-NC-ND 4.0). Erişim onayı + huggingface-cli login gerekir; yoksa 401/403 alırsınız. DLA açıktır ve erişim gerektirmez.')
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('② Ortamı kur', { launchEnvManager() }))
        actions.add(navButton('Bağımlılık kontrolü', { startSelftest() }, 'histoart_bridge.py selftest'))
        actions.add(navButton('③ Modeli indir', { startDownload() }, 'didsr/HistoArt ağırlıklarını model dizinine indirir'))
        actions.add(navButton('Kaydet ▶', { persistFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
    } else if (cur == 'CHECK_RUNNING') {
        title.setText('Bağımlılık kontrolü çalışıyor…')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'CHECK_DONE') {
        title.setText(selftestOkRef.get() ? 'Bağımlılık kontrolü tamam ✅'
            : '⚠ Bağımlılık kontrolü BAŞARISIZ — yukarıdaki günlüğe bakın')
        addLiveLog()
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
        actions.add(navButton('Çalıştırma ekranına dön ▶', { step.set('READY'); render() }))
    } else if (cur == 'DL_RUNNING') {
        title.setText('Model ağırlıkları indiriliyor…')
        addGuidance('DLA ~94 MB × 2; FMA ~1,2 GB × 2. İlk indirmede sabırlı olun.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'DL_DONE') {
        title.setText(downloadOkRef.get() ? 'Model ağırlıkları indirildi ✅' : '⚠ İndirme BAŞARISIZ — günlüğe bakın')
        addLiveLog()
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
        actions.add(navButton('Çalıştırma ekranına dön ▶', { step.set('READY'); render() }))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText('Görüntü açık değil')
            addGuidance('Önce bir slayt açın, sonra "⟳ Yenile". İstersen bölge anotasyonu çizin; çizmezseniz tüm slayt örneklenir.')
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
        } else {
            def workDir = resolveWorkDir(cfg, imageData)
            def anns = imageData.getHierarchy().getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }
            double ds = effectiveDownsample(cfg, imageData)
            double mpp = slideMpp(imageData)
            title.setText('HistoART — hazır')
            def sb = new StringBuilder()
            sb << "Slayt          : " << imageNameOf(imageData) << "\n"
            sb << "Çalışma dizini : " << workDir.getAbsolutePath() << "\n"
            sb << "Model dizini   : " << cfg.modelDir << "\n"
            sb << "Varyant        : " << ((cfg.variant == 'fma') ? 'FMA (UNI, KAPILI)' : 'DLA (ResNet50, açık)') << "\n"
            sb << "Eşik / aygıt   : " << (cfg.threshold ?: '0.5') << "  /  " << cfg.device << "\n"
            sb << String.format(java.util.Locale.US, "Karo           : %d px  ·  downsample %.3f%n", HISTOART_PATCH, ds)
            if (!Double.isNaN(mpp)) sb << String.format(java.util.Locale.US, "Çözünürlük     : slayt %.3f µm/px  →  etkin %.3f µm/px (hedef %.2f)%n", mpp, mpp * ds, HISTOART_MPP)
            else sb << "Çözünürlük     : slayt KALİBRE DEĞİL\n"
            sb << String.format(java.util.Locale.US, "Kapsam         : %s%n", anns.isEmpty() ? ('tüm slayt (maks. ' + (cfg.maxTiles ?: '2000') + ' karo)') : ('alan anotasyonu: ' + anns.size()))
            addMonoArea(sb.toString())

            def sw = scaleWarning(cfg, imageData)
            if (sw) addWarnLabel(sw)
            def mdOk = new File(cfg.modelDir, (cfg.variant == 'fma') ? 'fma_binary.pth' : 'dla_binary.pth').isFile()
            if (!mdOk) addWarnLabel('⚠ Model ağırlığı bulunamadı — ③ "Modeli indir" ile indirin (didsr/HistoArt).')
            addGuidance('İki aşama: önce İKİLİ model artefaktlı karoları bulur, sonra tür sınıflandırması YALNIZCA o karolara uygulanır. ' +
                'Çoklu modelin "temiz" sınıfı olmadığı için tek başına çalıştırılmaz.')

            boolean canRun = configComplete(cfg) && mdOk
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('③ Modeli indir', { startDownload() }))
            actions.add(navButton('⟳ Yenile', { render() }))
            def runBtn = navButton('Çalıştır ▶', { startRun() }, 'Karoları dışa aktarır ve HistoART ile sınıflandırır')
            runBtn.setDisable(!canRun)
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING') {
        title.setText(runPhaseRef.get())
        addGuidance('Python köprüsü çalışıyor. Çıktı aşağıda akıyor. Zaman aşımı: ' + PYTHON_TIMEOUT_SECONDS + ' sn.')
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
        actions.add(navButton('◀ Geri', { step.set('READY'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    }

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
    stage.setScene(new javafx.scene.Scene(root, 900, 680))
}

// ── Açılış durumu ───────────────────────────────────────────────────────────
step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('HistoART artefakt tespiti sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ HistoART artefakt tespiti sihirbazı açıldı."
