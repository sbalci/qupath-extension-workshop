/**
 * Yardımcı - HoVer-NeXt çekirdek segmentasyonu + sınıflandırma (Lizard, 7 sınıf; bölgede)
 * ----------------------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   QuPath'te ÇİZDİĞİNİZ alan anotasyonu içinde — ve YALNIZ orada — HoVer-NeXt'i (Baumann ve
 *   ark., MIDL 2024) çalıştırır: her çekirdeği SEGMENTE eder ve 7 sınıftan birine atar
 *   (nötrofil, epitel hücresi, lenfosit, plazma hücresi, eozinofil, bağ dokusu hücresi, mitoz).
 *   Sihirbaz bölgeyi 0.5 µm/px'te bir PNG ROI olarak dışa aktarır → Python köprüsü
 *   (hovernext/hovernext_runner.py) HoVer-NeXt'i AYRI bir süreç olarak çalıştırır → çekirdek
 *   konturları TABAN-piksel koordinatlarında geri alınır, bölgeye göre filtrelenir, sınıflı
 *   TESPİT (detection) nesneleri olarak eklenir ve sınıf başına SAYIM + % + YOĞUNLUK üretilir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Seçili alandaki model-tespitli çekirdeklerin sınıf başına sayımı, yüzdesi ve yoğunluğu
 *     (çekirdek/mm²). Klinik kategori, derece veya yorum ÜRETMEZ.
 *   • Lizard modeli KOLOREKTAL H&E ile eğitildi; başka dokulara genelleme doğrulanmamıştır.
 *   • Mitoz sınıfı pHH3 destekli etiketlerle eğitildi; yayımlanmış MİTOZA-ÖZEL başarım metriği
 *     YOKTUR. Mitoz sayımı için Modüller → Mitoz tespiti dedektörleri esastır.
 *
 * LİSANS SINIRI:
 *   HoVer-NeXt kodu GPL-3.0 (bu eklenti Apache-2.0) → kod PAKETLENMEZ/KOPYALANMAZ; sabit commit
 *   çalışma anında indirilir ve yalnız ayrı süreç olarak çalıştırılır. Ağırlıklar Zenodo
 *   10635618, CC BY-NC-SA 4.0 (ticari olmayan) — çalışma anında indirilir, MD5 doğrulanır.
 *
 * ÇALIŞMA ZAMANI:
 *   NVIDIA CUDA GPU ZORUNLU. Python ortamı (env id: hovernext; torch 2.1.1 + CUDA 12.1) +
 *   hovernext/hovernext_runner.py köprüsü. Kurulum: Extensions → Atölye → Yardımcılar →
 *   Python köprüleri → Atölye Python ortam yöneticisi → "HoVer-NeXt — çekirdek segmentasyon + 7 sınıf".
 *
 * KULLANIM:
 *   1. H&E slaytını açın; piksel boyutu (µm/px) KALİBRE olmalı (kalibrasyonsuz çalıştırılmaz).
 *   2. İlgi ALANINI anotasyon olarak çizin ve SEÇİN.
 *   3. Bu sihirbazı çalıştırın → (ilk kez) Yapılandır → "Modeli yerel indir" → "Bölgede çalıştır".
 *
 * ÇIKTI:
 *   • Her çekirdek için "<Sınıf> (HoVer-NeXt)" sınıflı poligon TESPİTİ + "Çekirdek alanı (µm2)"
 *   • Her seçili anotasyona: sınıf başına "(n)", "(%)", "(/mm2)" ölçümleri
 *   • Kilitli "HoVer-NeXt çekirdek sınıfları özeti" anotasyonu (Veri dışa aktarma ile dışa aktarılır)
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Baumann E ve ark., HoVer-NeXt, MIDL 2024 (PMLR 250): https://proceedings.mlr.press/v250/baumann24a.html
 *   • Kod: https://github.com/digitalpathologybern/hover_next_inference (GPL-3.0)
 *   • Ağırlık: https://zenodo.org/records/10635618 (CC BY-NC-SA 4.0)
 *   • Lizard veri seti: Graham S ve ark., ICCV Workshops 2021 (kolorektal çekirdek sınıfları).
 *
 * API: RegionRequest.createInstance + ImageServer.readRegion (bölge dışa aktarma);
 *      ROIs.createPolygonROI + PathObjects.createDetectionObject (QuPath 0.6.0+);
 *      GeoJSON ayrıştırma com.google.gson.JsonParser (QuPath 0.7 groovy.json içermez).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane
import qupath.lib.regions.RegionRequest
import com.google.gson.JsonParser
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ───────────────────────────────────────────────────────────────
long PYTHON_TIMEOUT_SECONDS = 3600L
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def SUMMARY_NAME = 'HoVer-NeXt çekirdek sınıfları özeti'
def ENV_ID       = 'hovernext'
def RUNNER_REL   = 'python/hovernext/hovernext_runner.py'
def CLASS_SUFFIX = ' (HoVer-NeXt)'
double MODEL_MPP = 0.5      // Lizard modelinin eğitim çözünürlüğü (~20x)
double MPP_TOL   = 0.20     // köprü etkin µm/px bu bandın (0.40–0.60) dışındaysa reddeder
int    ROI_WARN_PX = 8000   // hedef çözünürlükte bu boyutu aşan ROI için bellek/süre uyarısı
// Lizard sınıfları: köprünün GeoJSON sınıf adı (olduğu gibi) → Türkçe ad + görüntüleme rengi.
def CLASSES = [
    [key:'neutrophil',             tr:'Nötrofil',           rgb:[0, 200, 0]],
    [key:'epithelial-cell',        tr:'Epitel hücresi',     rgb:[230, 40, 40]],
    [key:'lymphocyte',             tr:'Lenfosit',           rgb:[40, 80, 230]],
    [key:'plasma-cell',            tr:'Plazma hücresi',     rgb:[0, 120, 60]],
    [key:'eosinophil',             tr:'Eozinofil',          rgb:[0, 190, 200]],
    [key:'connective-tissue-cell', tr:'Bağ dokusu hücresi', rgb:[240, 170, 90]],
    [key:'mitosis',                tr:'Mitoz',              rgb:[255, 0, 255]],
]
def MODELS = [
    [id:'lizard_convnextv2_large', label:'Large — ConvNeXtV2-L (~745 MB; en yüksek doğruluk)'],
    [id:'lizard_convnextv2_base',  label:'Base — ConvNeXtV2-B (~346 MB)'],
    [id:'lizard_convnextv2_tiny',  label:'Tiny — ConvNeXtV2-T (~128 MB; en hızlı)'],
]
def CAVEAT = 'Lizard modeli KOLOREKTAL H&E ile eğitildi; diğer dokulara genelleme doğrulanmadı. ' +
             'Mitoz sınıfı pHH3 destekli etiketlerle eğitildi ama yayımlanmış mitoza-özel başarım metriği YOK — ' +
             'mitoz sayımı için Modüller → Mitoz tespiti dedektörlerini esas alın.'
def classByKey = [:]
CLASSES.each { classByKey[it.key] = it }
def ourClassNames = CLASSES.collect { it.tr + CLASS_SUFFIX } as Set
def modelLabelOf = { String id -> (MODELS.find { it.id == id }?.label) ?: id }

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır) ──
// NOT: 'model' anahtarını Mitoz tespiti → Modelleri karşılaştır sihirbazı da okur.
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/hovernext')
def PREF_PYTHON = 'python'
def PREF_RUNNER = 'runner'
def PREF_WORK   = 'workDir'
def PREF_MODEL  = 'model'
def PREF_BATCH  = 'batchSize'
def PREF_TTA    = 'tta'

// ── Atölye veri kökü (env yöneticisiyle PAYLAŞILAN) + önbellek yönlendirme ──
def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
// hovernext_runner download → <dataRoot>/cache/hovernext/hover_next_inference-<commit>/ (kod) + <model>/ (ağırlık)
def codeDirs = { ->
    def base = new File(new File(atolyeDataRoot(), 'cache'), 'hovernext')
    if (!base.isDirectory()) return []
    def ds = base.listFiles({ f -> f.isDirectory() && f.getName().startsWith('hover_next_inference-') && new File(f, 'main.py').isFile() } as java.io.FileFilter)
    return ds == null ? [] : ds.toList()
}
def codeReady = { -> !codeDirs().isEmpty() }
def weightsReady = { String model ->
    codeDirs().any { d -> def md = new File(d, model); new File(md, 'params.toml').isFile() && new File(new File(md, 'train'), 'best_model').isFile() }
}
// Önbellek köklerini veri köküne yönlendir. hovernext_runner cache_dir() TIATOOLBOX_HOME'un ÜST
// dizinini (=<dataRoot>/cache) alıp hovernext ekler; HF_HOME timm'in ImageNet omurgasını tutar.
def applyCacheEnv = { pb ->
    try {
        def cache = new File(atolyeDataRoot(), 'cache'); cache.mkdirs()
        def env = pb.environment()
        env.put('HF_HOME', new File(cache, 'huggingface').getAbsolutePath())
        env.put('TORCH_HOME', new File(cache, 'torch').getAbsolutePath())
        env.put('TIATOOLBOX_HOME', new File(cache, 'tiatoolbox').getAbsolutePath())
    } catch (Throwable ignore) {}
}

// ── Otomatik tespit: hovernext ortamı + hovernext_runner.py köprüsü ──────────
def detectPython = { ->
    // (1) Env yöneticisinin kaydettiği KESİN yol (veri kökü değişse bile doğru).
    try {
        def rec = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('py.' + ENV_ID, '')
        if (rec?.trim() && new File(rec.trim()).isFile()) return rec.trim()
    } catch (Throwable ignore) {}
    // (2) Yedek: Atölye ortam yöneticisi venv'ini veri kökünden tahmin et.
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

def loadConfig = { ->
    def py = prefs.get(PREF_PYTHON, ''); if (!py?.trim()) py = detectPython()
    def rn = prefs.get(PREF_RUNNER, ''); if (!rn?.trim()) rn = detectRunner()
    def md = prefs.get(PREF_MODEL, MODELS[0].id); if (!MODELS.any { it.id == md }) md = MODELS[0].id
    [ python    : py,
      runner    : rn,
      workDir   : prefs.get(PREF_WORK,  ''),
      model     : md,
      batchSize : prefs.get(PREF_BATCH, '16'),
      tta       : prefs.get(PREF_TTA,   '4') ]
}
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile())
        miss << 'Python yürütülebilir (hovernext venv)'
    if (!cfg.runner?.trim() || !(new File(cfg.runner)).isFile())
        miss << 'Köprü betiği (hovernext_runner.py)'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

def parseIntOr = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }

def resolveWorkDir = { cfg, imageData ->
    def wd = cfg.workDir?.trim()
    if (wd) return new File(wd)
    def project = QP.getProject()
    if (project != null && project.getPath() != null)
        return new File(project.getPath().getParent().toFile(), 'tiatoolbox_work')
    try {
        def uris = imageData.getServer().getURIs()
        if (uris != null && !uris.isEmpty()) {
            def uri = uris.iterator().next()
            if ('file'.equals(uri.getScheme())) {
                def f = new File(uri); if (f.getParentFile() != null) return new File(f.getParentFile(), 'tiatoolbox_work')
            }
        }
    } catch (Throwable ignore) {}
    return new File(System.getProperty('java.io.tmpdir'), 'tiatoolbox_work')
}

def imageNameOf = { imageData -> (imageData.getServer().getMetadata().getName() ?: 'slide').replaceAll(/\.[^.\/\\]+$/, '') }

def pixelMicrons = { imageData ->
    try {
        def cal = imageData.getServer().getPixelCalibration()
        if (cal != null && cal.hasPixelSizeMicrons())
            return [pw: cal.getPixelWidthMicrons(), ph: cal.getPixelHeightMicrons()]
    } catch (Throwable ignore) {}
    return null
}

// Kalibrasyon + ÖLÇEK KAPISI. HoVer-NeXt 0.5 µm/px için eğitildi ve son-işlemesi PİKSEL-tabanlı
// boyut eşikleri kullanır → yanlış ölçekte hem segmentasyon hem sınıf bozulur. Bu yüzden
// kalibrasyonsuz ya da modelden kaba çözünürlüklü slaytta ÇALIŞTIRILMAZ (sessiz ölçek hatası).
def calibrationInfo = { imageData ->
    def cal = pixelMicrons(imageData)
    double mag = Double.NaN
    try { mag = imageData.getServer().getMetadata().getMagnification() } catch (Throwable ignore) {}
    String block = null
    String warn = null
    double mpp = (cal != null) ? (cal.pw + cal.ph) / 2.0d : Double.NaN
    if (cal == null) {
        block = 'Piksel boyutu tanımlı DEĞİL. HoVer-NeXt 0.5 µm/px için eğitildi; kalibrasyonsuz görüntüde sessizce yanlış ölçekte çalışır. Yardımcılar → Temel araçlar → Kalibrasyon ile piksel boyutunu girin.'
    } else if (!(mpp > 0.0d)) {
        block = 'Piksel boyutu geçersiz (≤ 0).'
    } else if (Math.abs(mpp - 1.0d) < 1e-6) {
        block = 'Piksel boyutu tam 1.000 µm/px — bu genelde QuPath\'in KALİBRESİZ varsayılanıdır. Tarayıcının gerçek değerini girin (tipik: 0.25 @40x, 0.50 @20x).'
    } else if (Math.max(mpp, MODEL_MPP) > MODEL_MPP * (1.0d + MPP_TOL)) {
        block = String.format(java.util.Locale.US, 'Slayt çözünürlüğü %.3f µm/px — modelin 0.5 µm/px çözünürlüğünden kaba (izin ≤ %.2f µm/px). Daha yüksek büyütmeli tarama gerekir.', mpp, MODEL_MPP * (1.0d + MPP_TOL))
    } else if (mpp < 0.10d) {
        warn = String.format(java.util.Locale.US, 'Piksel boyutu %.3f µm/px H&E WSI için sıra dışı — kalibrasyonu doğrulayın.', mpp)
    }
    return [cal: cal, mag: mag, mpp: mpp, block: block, warn: warn, scaleOk: (block == null)]
}
def resampleNote = { double mpp ->
    if (!Double.isFinite(mpp) || mpp <= 0) return ''
    double ratio = MODEL_MPP / mpp
    if (Math.abs(ratio - 1.0d) < 0.05d) return 'slayt zaten ~0.5 µm/px — yeniden örnekleme yok.'
    if (ratio > 1.0d) return String.format(java.util.Locale.US, 'ROI ~%.1f× AŞAĞI örneklenerek 0.5 µm/px çözünürlüğe getirilir.', ratio)
    return 'slayt 0.5 µm/px çözünürlükten biraz kaba — native beslenir (izin bandı içinde).'
}

// Seçili (yoksa tüm) alan anotasyonları — çekirdekleri YALNIZ bunların içinde sayarız.
def notSummary = { ann -> ann.getName() == null || !ann.getName().startsWith(SUMMARY_NAME) }
def regionAnnotationsOf = { imageData ->
    def h = imageData.getHierarchy()
    def sel = h.getSelectionModel().getSelectedObjects().findAll { it.isAnnotation() && it.hasROI() && it.getROI().isArea() && notSummary(it) }
    if (!sel.isEmpty()) return new ArrayList(sel)
    return new ArrayList(h.getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() && notSummary(it) })
}

// ── Bölgeyi hedef çözünürlükte ROI görüntüsü olarak dışa aktar (birleşik sınır kutusu) ──
def exportRegionImage = { imageData, File workDir, double targetMpp, List regionRois, cal, int warnPx, Closure appendLine ->
    def server = imageData.getServer()
    double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY
    double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY
    regionRois.each { roi ->
        minX = Math.min(minX, roi.getBoundsX()); minY = Math.min(minY, roi.getBoundsY())
        maxX = Math.max(maxX, roi.getBoundsX() + roi.getBoundsWidth()); maxY = Math.max(maxY, roi.getBoundsY() + roi.getBoundsHeight())
    }
    int x = (int) Math.floor(minX); int y = (int) Math.floor(minY)
    if (x < 0) x = 0
    if (y < 0) y = 0
    int w = (int) Math.ceil(maxX - x); int h = (int) Math.ceil(maxY - y)
    if (x + w > server.getWidth())  w = server.getWidth()  - x
    if (y + h > server.getHeight()) h = server.getHeight() - y
    if (w <= 0 || h <= 0) return [ok: false, error: 'Bölge sınır dışı ya da boş.']
    double baseMpp = (cal.pw + cal.ph) / 2.0d
    double downsample = targetMpp / baseMpp
    if (downsample < 1.0d) downsample = 1.0d   // asla üst-örnekleme — native besle
    int outW = (int) Math.max(1, Math.round(w / downsample))
    int outH = (int) Math.max(1, Math.round(h / downsample))
    if (outW > warnPx || outH > warnPx)
        appendLine(String.format(java.util.Locale.US, '⚠ Büyük ROI (%d × %d px @ hedef çözünürlük) — bellek/süre yoğun. Daha küçük bir bölge seçmeyi düşünün.', outW, outH))
    def request = RegionRequest.createInstance(server.getPath(), downsample, x, y, w, h)
    def img = server.readRegion(request)
    if (img == null) return [ok: false, error: 'Bölge okunamadı (readRegion null döndü).']
    def rgb = new java.awt.image.BufferedImage(img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB)
    def g = rgb.createGraphics()
    try { g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight()); g.drawImage(img, 0, 0, null) } finally { g.dispose() }
    def f = new File(workDir, 'hovernext_roi.png')
    if (f.getParentFile() != null) f.getParentFile().mkdirs()
    javax.imageio.ImageIO.write(rgb, 'PNG', f)
    double effMpp = baseMpp * downsample
    appendLine(String.format(java.util.Locale.US, 'ROI görüntüsü: %s (%d × %d px, downsample %.3f, etkin %.3f µm/px, köken %d,%d)', f.getName(), rgb.getWidth(), rgb.getHeight(), downsample, effMpp, x, y))
    return [ok: true, file: f, originX: x, originY: y, downsample: downsample, effMpp: effMpp]
}

// Bölge içi testi: önce sınır kutusu (hızlı ret), sonra gerçek ROI.contains.
def regionBounds = { List regionRois ->
    regionRois.collect { r -> [x0: r.getBoundsX(), y0: r.getBoundsY(), x1: r.getBoundsX() + r.getBoundsWidth(), y1: r.getBoundsY() + r.getBoundsHeight(), roi: r] }
}
def insideAny = { List bounds, double x, double y ->
    bounds.any { b -> x >= (b.x0 as double) && x <= (b.x1 as double) && y >= (b.y0 as double) && y <= (b.y1 as double) && b.roi.contains(x, y) }
}

// ── GeoJSON içe al: bölge içi çekirdekleri sınıflı TESPİT olarak ekle ──
def importNuclei = { File geojson, imageData, List regionRois, cal ->
    if (geojson == null || !geojson.isFile())
        return [ok: false, error: 'GeoJSON çıktısı bulunamadı:\n' + (geojson?.getAbsolutePath() ?: '(yol yok)')]
    def root
    try { root = JsonParser.parseString(geojson.getText('UTF-8')).getAsJsonObject() }
    catch (Throwable t) { return [ok: false, error: 'GeoJSON ayrıştırılamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }
    def feats = root.has('features') ? root.getAsJsonArray('features') : null
    if (feats == null) return [ok: false, error: 'GeoJSON "features" içermiyor.']
    def bounds = regionBounds(regionRois)
    def plane = ImagePlane.getDefaultPlane()
    def pcs = [:]
    CLASSES.each { c ->
        def pc = QP.getPathClass(c.tr + CLASS_SUFFIX)
        try { pc.setColor(qupath.lib.common.ColorTools.packRGB(c.rgb[0] as int, c.rgb[1] as int, c.rgb[2] as int)) } catch (Throwable ignore) {}
        pcs[c.key] = pc
    }
    def dets = new ArrayList()
    def nuclei = new ArrayList()   // [x, y, sınıfAnahtarı]
    int total = 0
    int unknown = 0
    for (el in feats) {
        def ft = el.getAsJsonObject()
        if (!ft.has('geometry') || ft.get('geometry').isJsonNull()) continue
        def props = (ft.has('properties') && ft.get('properties').isJsonObject()) ? ft.getAsJsonObject('properties') : null
        String key = null
        try { key = props.getAsJsonObject('classification').get('name').getAsString() } catch (Throwable ignore) {}
        if (key == null || !classByKey.containsKey(key)) { unknown++; continue }
        def geom = ft.getAsJsonObject('geometry')
        String gtype = geom.has('type') ? geom.get('type').getAsString() : ''
        double cx = Double.NaN
        double cy = Double.NaN
        try { def c = props.getAsJsonArray('centroid'); cx = c.get(0).getAsDouble(); cy = c.get(1).getAsDouble() } catch (Throwable ignore) {}
        def roi = null
        if (gtype == 'Polygon') {
            def ring = geom.getAsJsonArray('coordinates').get(0).getAsJsonArray()
            int n = ring.size()
            if (n >= 4) {   // kapalı halka: son nokta = ilk nokta
                double[] xs = new double[n - 1]
                double[] ys = new double[n - 1]
                for (int i = 0; i < n - 1; i++) { def p = ring.get(i).getAsJsonArray(); xs[i] = p.get(0).getAsDouble(); ys[i] = p.get(1).getAsDouble() }
                roi = ROIs.createPolygonROI(xs, ys, plane)
            }
        } else if (gtype == 'Point') {
            def p = geom.getAsJsonArray('coordinates')
            double px = p.get(0).getAsDouble()
            double py = p.get(1).getAsDouble()
            roi = ROIs.createPointsROI(px, py, plane)
            if (!Double.isFinite(cx)) { cx = px; cy = py }
        }
        if (roi == null) continue
        if (!Double.isFinite(cx) || !Double.isFinite(cy)) { cx = roi.getCentroidX(); cy = roi.getCentroidY() }
        total++
        if (!insideAny(bounds, cx, cy)) continue
        def det = PathObjects.createDetectionObject(roi, pcs[key])
        if (cal != null && roi.isArea()) det.getMeasurements().put('Çekirdek alanı (µm2)', roi.getArea() * cal.pw * cal.ph)
        dets << det
        nuclei << [cx, cy, key]
    }
    def hier = imageData.getHierarchy()
    // YALNIZ bu bölgedeki önceki HoVer-NeXt tespitlerini sil (başka bölgelerdeki çalıştırmalar korunur).
    def old = hier.getDetectionObjects().findAll { d ->
        d.getPathClass() != null && ourClassNames.contains(d.getPathClass().getName()) && d.hasROI() &&
        insideAny(bounds, d.getROI().getCentroidX(), d.getROI().getCentroidY())
    }
    if (!old.isEmpty()) hier.removeObjects(old, false)
    if (!dets.isEmpty()) hier.addObjects(dets)
    hier.fireHierarchyChangedEvent(hier)
    return [ok: true, total: total, inside: nuclei.size(), nuclei: nuclei, unknown: unknown, removed: old.size()]
}

// ── Sınıf başına sayım + % + (kalibreyse) yoğunluk: seçili anotasyonlara + kilitli özete ──
def writeCounts = { imageData, List targets, List nuclei, cal ->
    def hier = imageData.getHierarchy()
    def fill = { obj, roi, List pts ->
        def ml = obj.getMeasurements()
        int tot = pts.size()
        double areaMm2 = (cal != null) ? roi.getArea() * cal.pw * cal.ph / 1_000_000.0d : Double.NaN
        ml.put('HoVer-NeXt: toplam çekirdek (n)', tot as double)
        if (cal != null) ml.put('ROI alanı (mm2)', areaMm2)
        CLASSES.each { c ->
            int n = pts.count { it[2] == c.key } as int
            ml.put('HoVer-NeXt: ' + c.tr + ' (n)', n as double)
            ml.put('HoVer-NeXt: ' + c.tr + ' (%)', tot > 0 ? 100.0d * n / tot : Double.NaN)
            if (cal != null) ml.put('HoVer-NeXt: ' + c.tr + ' (/mm2)', areaMm2 > 0 ? n / areaMm2 : Double.NaN)
        }
        return areaMm2
    }
    targets.each { ann ->
        def roi = ann.getROI()
        fill(ann, roi, nuclei.findAll { p -> roi.contains(p[0] as double, p[1] as double) })
    }
    def unionRoi = (targets.size() == 1) ? targets[0].getROI() : qupath.lib.roi.RoiTools.union(targets.collect { it.getROI() })
    hier.removeObjects(hier.getAnnotationObjects().findAll { it.getName() != null && it.getName().startsWith(SUMMARY_NAME) }, false)
    def summary = PathObjects.createAnnotationObject(unionRoi)
    summary.setName(SUMMARY_NAME)
    summary.getMeasurements().put('Seçili ROI sayısı', targets.size() as double)
    double areaMm2 = fill(summary, unionRoi, nuclei)
    summary.setLocked(true)
    hier.addObjects([summary])
    hier.fireHierarchyChangedEvent(hier)
    return [areaMm2: areaMm2]
}

// ── Sonuç metni ─────────────────────────────────────────────────────────────
def resultText = { imageData, cfg, imp, dens, cal ->
    def sb = new StringBuilder()
    sb << "HoVer-NeXt (Lizard) — BÖLGEDE ÇEKİRDEK SINIFLARI\n"
    sb << "═══════════════════════════════════════════════\n\n"
    sb << "Slayt   : " << imageNameOf(imageData) << "\n"
    sb << "Model   : " << modelLabelOf(cfg.model) << "\n"
    int tot = (imp?.inside ?: 0) as int
    sb << String.format(java.util.Locale.US, "Çekirdek: BÖLGE İÇİ %,d%n", tot)
    double a = (dens != null && dens.areaMm2 != null) ? (dens.areaMm2 as double) : Double.NaN
    boolean haveArea = cal != null && Double.isFinite(a) && a > 0
    if (haveArea) sb << String.format(java.util.Locale.US, "Ölçülen alan : %.3f mm²%n", a)
    sb << "\n" << String.format(java.util.Locale.US, "%-22s %8s %8s %12s%n", 'Sınıf', 'n', '%', 'çekirdek/mm²')
    CLASSES.each { c ->
        int n = imp.nuclei.count { it[2] == c.key } as int
        String pct = tot > 0 ? String.format(java.util.Locale.US, '%.1f', 100.0d * n / tot) : '—'
        String dm = haveArea ? String.format(java.util.Locale.US, '%.1f', n / a) : '—'
        sb << String.format(java.util.Locale.US, "%-22s %8d %8s %12s%n", c.tr, n, pct, dm)
    }
    if (tot == 0) sb << "\n⚠ Bölgede çekirdek bulunamadı — bölgenin doku içerdiğini ve kalibrasyonu doğrulayın.\n"
    sb << "\n⚠ SINIRLAMA: " << CAVEAT << "\n"
    sb << "\nÇekirdekler '<Sınıf> (HoVer-NeXt)' sınıflı poligon TESPİTLERİ olarak eklendi; seçili anotasyonlara\n"
    sb << "sınıf başına sayım + % + yoğunluk yazıldı; kilitli özet: '" << SUMMARY_NAME << "'.\n"
    sb << "Sınıflar modelin tahminidir; görsel doğrulayın. Klinik kategori/yorum üretilmez.\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

// ── Headless ────────────────────────────────────────────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    println "HoVer-NeXt sihirbazı: python=${cfg.python ?: '(ayarsız)'} runner=${cfg.runner ?: '(ayarsız)'} model=${cfg.model}"
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(', ')}"
    println "Yerel kod: ${codeReady() ? 'VAR' : 'yok'} · ağırlık (${cfg.model}): ${weightsReady(cfg.model) ? 'VAR' : 'yok'}"
    if (imageData != null) {
        println "Alan anotasyonu: ${regionAnnotationsOf(imageData).size()}"
        def ci = calibrationInfo(imageData)
        println "Ölçek: ${ci.scaleOk ? 'uygun' : ('UYGUN DEĞİL — ' + ci.block)}"
    } else println "Açık görüntü yok."
    println "Bu sihirbaz QuPath arayüzü gerektirir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def selftestOkRef = new java.util.concurrent.atomic.AtomicBoolean(true)
def dlOkRef       = new java.util.concurrent.atomic.AtomicBoolean(true)
def mitosisCoordsRef = new java.util.concurrent.atomic.AtomicReference(new ArrayList())
def navIdxRef     = new java.util.concurrent.atomic.AtomicInteger(-1)
def processRef    = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef   = new java.util.concurrent.atomic.AtomicReference('')
def busyLabelRef  = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def pyFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def runnerFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def workFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def modelChoiceRef = new java.util.concurrent.atomic.AtomicReference(null)
def batchFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def ttaFieldRef    = new java.util.concurrent.atomic.AtomicReference(null)
def render

// Görüntüleyiciyi i. mitoz-sınıflı çekirdeğe ORTALA + (gerekliyse) yakınlaş.
def goToMitosis = { int i ->
    def coords = mitosisCoordsRef.get()
    if (coords == null || coords.isEmpty()) return
    int n = coords.size(); int idx = ((i % n) + n) % n
    def pt = coords[idx]; navIdxRef.set(idx)
    javafx.application.Platform.runLater {
        try {
            def viewer = gui.getViewer()
            if (viewer != null) {
                if (viewer.getDownsampleFactor() > 4.0d) viewer.setDownsampleFactor(2.0d)
                viewer.setCenterPixelLocation(pt[0] as double, pt[1] as double)
            }
        } catch (Throwable ignore) {}
    }
}

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
// Python ortamı kurulu değilse: Atölye Python ortam yöneticisini kendi penceresinde aç
// (paketli betiği JAR kaynağından ya da sınıf yolundan yükleyip GroovyShell ile çalıştırır).
def launchBundledScript = { String resourceName ->
    new Thread({
        try {
            def url = null
            try { url = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getResource('/scripts/' + resourceName) } catch (Throwable t) {}
            if (url == null) url = this.getClass().getResource('/scripts/' + resourceName)
            if (url == null) {
                javafx.application.Platform.runLater { Dialogs.showInfoNotification('Betik bulunamadı',
                    'Menüden açın: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi') }
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

// ── Çalışma günlüğü: biriktir + otomatik dosyaya yaz + "kaydet" ──
def runLog      = new StringBuilder()
def logFileRef  = new java.util.concurrent.atomic.AtomicReference(null)
def resetLog    = { -> synchronized (runLog) { runLog.setLength(0) } }
def appendLog   = { String ln -> synchronized (runLog) { runLog.append(ln == null ? '' : ln).append('\n') } }
def logSnapshot = { -> synchronized (runLog) { return runLog.toString() } }
def autoSaveLog = { File dir, String base ->
    try {
        if (dir == null) return null
        dir.mkdirs()
        def f = new File(dir, (base ?: 'hovernext') + '_hovernext_run.log')
        f.setText(logSnapshot(), 'UTF-8'); logFileRef.set(f); return f
    } catch (Throwable t) { return null }
}
def saveLogInteractive = {
    def txt = logSnapshot()
    if (!txt?.trim()) { Dialogs.showInfoNotification('Günlük', 'Kaydedilecek günlük yok.'); return }
    try {
        def suggested = logFileRef.get() ?: new File(System.getProperty('user.home'), 'hovernext_run.log')
        def f = qupath.fx.dialogs.FileChoosers.promptToSaveFile(stage, 'Çalışma günlüğünü kaydet', suggested,
            new javafx.stage.FileChooser.ExtensionFilter('Günlük (*.log, *.txt)', '*.log', '*.txt'))
        if (f != null) { f.setText(txt, 'UTF-8'); Dialogs.showInfoNotification('Günlük', 'Kaydedildi: ' + f.getAbsolutePath()) }
    } catch (Throwable t) { Dialogs.showErrorMessage('Günlük', 'Kaydedilemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName())) }
}
def textOf = { ref -> def f = ref.get(); return (f != null ? f.getText() : '').trim() }
def persistFields = {
    prefs.put(PREF_PYTHON, textOf(pyFieldRef))
    prefs.put(PREF_RUNNER, textOf(runnerFieldRef))
    prefs.put(PREF_WORK,   textOf(workFieldRef))
    def mc = modelChoiceRef.get()
    if (mc != null) {
        int i = mc.getSelectionModel().getSelectedIndex()
        if (i >= 0 && i < MODELS.size()) prefs.put(PREF_MODEL, MODELS[i].id)
    }
    def bs = textOf(batchFieldRef); prefs.put(PREF_BATCH, bs ?: '16')
    def tt = textOf(ttaFieldRef);   prefs.put(PREF_TTA,   tt ?: '4')
    try { prefs.flush() } catch (Throwable ignore) {}
}

// Süreç AĞACINI sonlandır: köprü HoVer-NeXt'i ayrı bir alt süreç olarak başlatır (+ son-işleme
// işçisi); yalnız köprüyü öldürmek, GPU'da çalışan torun süreçleri (özellikle Windows'ta) yetim bırakır.
def killTree = { proc ->
    if (proc == null) return
    try { proc.descendants().forEach({ h -> try { h.destroyForcibly() } catch (Throwable ignore) {} } as java.util.function.Consumer) } catch (Throwable ignore) {}
    try { proc.destroyForcibly() } catch (Throwable ignore) {}
}

// ── Python süreci → satır akışı ──────────────────────────────────────────────
def runPython = { List cmd, Closure onLine ->
    def pb = new ProcessBuilder(cmd.collect { it.toString() }); pb.redirectErrorStream(true)
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
            last.addLast(line); while (last.size() > 80) last.pollFirst()
            onLine(line)
            if (cancelledRef.get()) break
        }
        reader.close()
    } catch (Throwable ignore) {}
    boolean finished
    try { finished = proc.waitFor(PYTHON_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) }
    catch (InterruptedException ie) { killTree(proc); return [ok: false, exitCode: -3, error: 'İptal edildi'] }
    if (!finished) { killTree(proc); return [ok: false, exitCode: -2, error: 'Zaman aşımı (' + PYTHON_TIMEOUT_SECONDS + ' sn)'] }
    if (cancelledRef.get()) { killTree(proc); return [ok: false, exitCode: -3, error: 'İptal edildi'] }
    int code = proc.exitValue()
    return [ok: (code == 0), exitCode: code, lastLines: last.join('\n')]
}

// ── Bağımlılık kontrolü (selftest) ──────────────────────────────────────────
def startSelftest = {
    persistFields()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set('Önce yapılandırmayı tamamlayın:\n  • ' + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Bağımlılık kontrolü'); step.set('CHECK_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython([cfg.python, cfg.runner, 'selftest'], appendLine)
        javafx.application.Platform.runLater { selftestOkRef.set(r.ok); step.set('CHECK_DONE'); render() }
    }, 'AtolyeHoverNext-Check')
    worker.setDaemon(true); worker.start()
}

// ── Kodu + seçili modelin ağırlığını yerel indir (bir kez) ────────────────────
def startModelDownload = {
    persistFields()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set('Önce yapılandırmayı tamamlayın:\n  • ' + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Model indiriliyor (yerel)…'); step.set('DL_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython([cfg.python, cfg.runner, 'download', '--model', cfg.model], appendLine)
        javafx.application.Platform.runLater { dlOkRef.set(r.ok); step.set('DL_DONE'); render() }
    }, 'AtolyeHoverNext-Download')
    worker.setDaemon(true); worker.start()
}

// ── Çalıştırma akışı — seçili bölgede HoVer-NeXt ──────────────────────────────
def startRun = {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def targets = regionAnnotationsOf(imageData)
    if (targets.isEmpty()) { errorTextRef.set('Bölge yok.\nÖnce bir alan anotasyonu çizin/seçin.'); step.set('ERROR'); render(); return }
    def ci = calibrationInfo(imageData)
    if (!ci.scaleOk) { errorTextRef.set('Ölçek uygun değil — çalıştırılmadı:\n' + ci.block); step.set('ERROR'); render(); return }
    def cal = ci.cal
    def regionRois = targets.collect { it.getROI() }
    def workDir = resolveWorkDir(cfg, imageData); workDir.mkdirs()
    def base    = imageNameOf(imageData)
    def outGeo  = new File(workDir, base + '_hovernext.geojson')
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Hazırlanıyor…'); step.set('RUN_RUNNING'); render()

    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def setPhase   = { String ph -> javafx.application.Platform.runLater { runPhaseRef.set(ph); render() } }
        try {
            appendLine('Model: HoVer-NeXt ' + cfg.model + '  ·  cihaz: CUDA (zorunlu)  ·  hedef çözünürlük: 0.500 µm/px')
            appendLine('Kapsam: seçili bölge (' + targets.size() + ' anotasyon)')
            appendLine(String.format(java.util.Locale.US, 'Kalibrasyon: %.4f × %.4f µm/px%s  →  %s', cal.pw, cal.ph,
                (Double.isFinite(ci.mag) && ci.mag > 0 ? String.format(java.util.Locale.US, ' (~%.0fx)', ci.mag) : ''), resampleNote(ci.mpp)))
            if (ci.warn != null) appendLine('⚠ Kalibrasyon: ' + ci.warn)
            setPhase('ROI görüntüsü dışa aktarılıyor (1/3)…')
            def exp = exportRegionImage(imageData, workDir, MODEL_MPP, regionRois, cal, ROI_WARN_PX, appendLine)
            if (!exp.ok) { javafx.application.Platform.runLater { errorTextRef.set(exp.error); step.set('ERROR'); render() }; return }
            if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }
            def cmd = [cfg.python, cfg.runner, 'detect',
                       '--roi', exp.file.getAbsolutePath(),
                       '--out', outGeo.getAbsolutePath(),
                       '--origin', (exp.originX + ',' + exp.originY),
                       '--downsample', String.format(java.util.Locale.US, '%.6f', (double) exp.downsample),
                       '--mpp', String.format(java.util.Locale.US, '%.6f', (double) exp.effMpp),
                       '--model', cfg.model, '--mode', 'polygons', '--classes', 'all',
                       '--batch-size', String.valueOf(parseIntOr(cfg.batchSize, 16)),
                       '--tta', String.valueOf(parseIntOr(cfg.tta, 4)),
                       '--device', 'cuda', '--work', workDir.getAbsolutePath()]
            setPhase('HoVer-NeXt çıkarımı + son-işleme çalışıyor (2/3)…')
            def r = runPython(cmd, appendLine)
            appendLine('# Çıkış kodu: ' + r.exitCode)
            def savedLog = autoSaveLog(workDir, base)
            if (savedLog != null) appendLine('# Günlük kaydedildi: ' + savedLog.getAbsolutePath())
            if (!r.ok) { javafx.application.Platform.runLater { errorTextRef.set('HoVer-NeXt başarısız (çıkış: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '') + (savedLog != null ? ('\n\nÇalışma günlüğü: ' + savedLog.getAbsolutePath()) : '')); step.set('ERROR'); render() }; return }
            def geo = outGeo
            try { def m = (r.lastLines ?: '') =~ /RESULT geojson=(.+)/; if (m.find()) { def gp = new File(m.group(1).trim()); if (gp.isFile()) geo = gp } } catch (Throwable ignore) {}

            // Uzun çıkarımdan SONRA QP.getCurrentImageData()'yı YENİDEN ALMA (TOCTOU);
            // çalıştırmayı başlatan captured `imageData`ya yaz.
            javafx.application.Platform.runLater { busyLabelRef.set('Çekirdekler içe aktarılıyor (3/3)…'); step.set('BUSY'); render() }
            def imp = importNuclei(geo, imageData, regionRois, cal)
            if (!imp.ok) { javafx.application.Platform.runLater { errorTextRef.set(imp.error); step.set('ERROR'); render() }; return }
            if (imp.unknown > 0) appendLine('⚠ Tanınmayan sınıflı ' + imp.unknown + ' özellik atlandı.')
            def dens = writeCounts(imageData, targets, imp.nuclei, cal)
            mitosisCoordsRef.set(new ArrayList(imp.nuclei.findAll { it[2] == 'mitosis' }.collect { [it[0], it[1]] }))
            navIdxRef.set(-1)
            javafx.application.Platform.runLater {
                try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
                resultTextRef.set(resultText(imageData, cfg, imp, dens, cal)); step.set('RESULT'); render()
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Beklenmeyen hata:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
        }
    }, 'AtolyeHoverNext-Run')
    worker.setDaemon(true); worker.start()
}

// ── Ortam yöneticisinden bu sihirbaza dönüş ──────────────────────────────────
// "Python ortamı" düğmesi Atölye Python ortam yöneticisini bu kancayla (`atolyeReturnHook`) açar.
// Kurulum bitince yöneticideki "Sihirbaza dön ▶" (ya da yönetici penceresini kapatmak) bu pencereyi
// öne getirir, yapılandırmayı yeniden okur ve çalıştırma ekranına (READY) geçer. Çalışan bir işlem
// sürerken ekran değiştirilmez; yalnız pencere öne gelir.
def envReturnHook = [
    envId   : ENV_ID,
    wizard  : 'HoVer-NeXt çekirdek sınıflandırma',
    onReturn: { reopen ->
        javafx.application.Platform.runLater {
            if (stage == null || (!stage.isShowing() && !reopen)) return
            try {
                if (step.get() == 'CONFIG') persistFields()
                def savedPy = prefs.get(PREF_PYTHON, '')
                if (savedPy?.trim() && !(new File(savedPy.trim())).isFile()) { prefs.remove(PREF_PYTHON); try { prefs.flush() } catch (Throwable ignore) {} }
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

// ── Render ───────────────────────────────────────────────────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
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

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('HoVer-NeXt — çalışma zamanı gerekli')
        def miss = configMissing(cfg)
        addGuidance('Bu sihirbaz HoVer-NeXt Python ortamını (env id: hovernext) ve bir NVIDIA CUDA GPU gerektirir.\nEksik/geçersiz:\n  • ' +
            (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')) +
            '\n\nKurulum: Extensions → Atölye → Yardımcılar → Python köprüleri → Atölye Python ortam yöneticisi → "HoVer-NeXt — çekirdek segmentasyon + 7 sınıf".\n' +
            'Köprü betiği: handson/python/hovernext/hovernext_runner.py')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('⚙ Python ortamını kur/aç', { launchEnvManager() }, 'Atölye Python ortam yöneticisini açar → "HoVer-NeXt"i kurun'))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText('HoVer-NeXt — yapılandırma')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def rnField = new javafx.scene.control.TextField(cfg.runner ?: '')
        def wdField = new javafx.scene.control.TextField(cfg.workDir ?: '')
        def batchField = new javafx.scene.control.TextField(cfg.batchSize ?: '16')
        def ttaField = new javafx.scene.control.TextField(cfg.tta ?: '4')
        [pyField, rnField, wdField].each { it.setPrefColumnCount(36) }
        [batchField, ttaField].each { it.setPrefColumnCount(6) }
        def modelChoice = new javafx.scene.control.ChoiceBox()
        MODELS.each { modelChoice.getItems().add(it.label) }
        int mIdx = MODELS.findIndexOf { it.id == cfg.model }
        modelChoice.getSelectionModel().select(mIdx < 0 ? 0 : mIdx)
        pyFieldRef.set(pyField); runnerFieldRef.set(rnField); workFieldRef.set(wdField)
        modelChoiceRef.set(modelChoice); batchFieldRef.set(batchField); ttaFieldRef.set(ttaField)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Dosya seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir  = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Dizin seç', null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (.venv/python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Köprü (hovernext_runner.py):'), rnField, navButton('…', { browseFile(rnField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Çalışma dizini (ops.):'), wdField, navButton('…', { browseDir(wdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Model (Lizard):'), modelChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Batch size:'), batchField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('TTA görünümü:'), ttaField)
        center.getChildren().add(grid)
        def mcLbl = new javafx.scene.control.Label(
            (codeReady() ? '✓ HoVer-NeXt kodu VAR' : '○ HoVer-NeXt kodu yok') + '   ·   ' +
            (weightsReady(cfg.model) ? ('✓ ağırlık VAR (' + cfg.model + ')') : ('○ ağırlık yok (' + cfg.model + ') — "Modeli yerel indir"')))
        mcLbl.setWrapText(true); mcLbl.setMaxWidth(Double.MAX_VALUE); mcLbl.setStyle('-fx-opacity: 0.85; -fx-font-size: 11px;')
        center.getChildren().add(mcLbl)
        addGuidance('Model: HoVer-NeXt Lizard (7 sınıf). "Modeli yerel indir" seçili modelin ağırlığını Zenodo\'dan (CC BY-NC-SA 4.0, MD5 doğrulanır) ve HoVer-NeXt kodunu (GPL-3.0, sabit commit; paketlenmez, ayrı süreç olarak çalıştırılır) bir kez indirir; ImageNet omurgasını da önbelleğe almaya çalışır — yine de ilk çalıştırmada internet gerekebilir.\n' +
            'Model seçimini değiştirirseniz önce "Kaydet", sonra yeniden "Modeli yerel indir". Cihaz: yalnız CUDA (HoVer-NeXt CPU\'da çalışmaz). Batch size: GPU belleği yetmezse düşürün (ör. 8). TTA: test-zamanı artırma görünümü (HoVer-NeXt varsayılanı 4; 1 = daha hızlı).')
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('⚙ Python ortamı', { launchEnvManager() }, 'Atölye Python ortam yöneticisini aç (hovernext kur)'))
        actions.add(navButton('Modeli yerel indir', { startModelDownload() }, 'HoVer-NeXt kodu + seçili Lizard ağırlığını bir kez yerel indir'))
        actions.add(navButton('Bağımlılık kontrolü', { startSelftest() }, 'hovernext_runner.py selftest (paketler + CUDA + kod/ağırlık)'))
        actions.add(navButton('Kaydet ▶', { persistFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
    } else if (cur == 'CHECK_RUNNING') {
        title.setText('Bağımlılık kontrolü çalışıyor…')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { killRunning() }))
    } else if (cur == 'CHECK_DONE') {
        title.setText(selftestOkRef.get() ? 'Bağımlılık kontrolü tamam ✅'
            : '⚠ Bağımlılık kontrolü BAŞARISIZ — günlüğe bakın (paket / CUDA / kod-ağırlık)'); addLiveLog()
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }, 'Bağımlılık kontrolü günlüğünü kaydet'))
        if (selftestOkRef.get()) actions.add(navButton('Çalıştırma ekranına dön ▶', { step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }, 'Kontrol tamam — bölgede çalıştırma ekranına döner'))
    } else if (cur == 'DL_RUNNING') {
        title.setText('Model indiriliyor (yerel)…')
        addGuidance('HoVer-NeXt kodu + ' + modelLabelOf(cfg.model) + ' ağırlığı veri kökü altına indiriliyor (bir kerelik; MD5 doğrulanır).')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { killRunning() }))
    } else if (cur == 'DL_DONE') {
        title.setText(dlOkRef.get() ? 'Model yerel olarak indirildi ✅' : '⚠ Model indirilemedi — günlüğe bakın'); addLiveLog()
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }, 'İndirme günlüğünü kaydet'))
        if (dlOkRef.get()) actions.add(navButton('Çalıştırma ekranına dön ▶', { step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }, 'İndirme tamam — bölgede çalıştırma ekranına döner'))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText('Görüntü açık değil')
            addGuidance('Önce bir H&E slaytı açın, ilgi ALANINI çizip seçin, sonra "⟳ Yenile".')
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
        } else {
            def targets = regionAnnotationsOf(imageData)
            def ci = calibrationInfo(imageData)
            def typeName = (imageData.getImageType()?.name() ?: '').toUpperCase(java.util.Locale.ROOT)
            boolean isHE = typeName.contains('BRIGHTFIELD_H_E')
            boolean localReady = codeReady() && weightsReady(cfg.model)
            title.setText('HoVer-NeXt — çekirdek segmentasyonu + 7 sınıf (bölgede)')
            def sb = new StringBuilder()
            sb << "Slayt            : " << imageNameOf(imageData) << "\n"
            sb << "Model            : " << modelLabelOf(cfg.model) << "\n"
            sb << "Python           : " << (cfg.python ?: '(ayarsız)') << "\n"
            sb << "Cihaz            : CUDA GPU (zorunlu)\n"
            sb << "Batch / TTA      : " << (cfg.batchSize ?: '16') << " / " << (cfg.tta ?: '4') << "\n"
            sb << "Yerel kod+ağırlık: " << (localReady ? 'VAR' : 'eksik — Yapılandır → "Modeli yerel indir"') << "\n"
            sb << String.format(java.util.Locale.US, "Seçili bölge     : %,d alan anotasyonu%n", targets.size())
            if (ci.cal != null) {
                sb << String.format(java.util.Locale.US, "Piksel boyutu    : %.4f × %.4f µm/px%s%n", ci.cal.pw, ci.cal.ph,
                    (Double.isFinite(ci.mag) && ci.mag > 0 ? String.format(java.util.Locale.US, '   (~%.0f×)', ci.mag) : ''))
                sb << "Yeniden örnek.   : " << resampleNote(ci.mpp) << "\n"
            } else {
                sb << "Piksel boyutu    : KALİBRE DEĞİL — çalıştırılamaz\n"
            }
            addMonoArea(sb.toString())
            addGuidance('HoVer-NeXt YALNIZ seçili/çizili alan anotasyon(lar)ı içinde çalışır. Sonuç: her çekirdek için "<Sınıf> (HoVer-NeXt)" sınıflı renkli poligon TESPİTİ + her anotasyona sınıf başına sayım, % ve yoğunluk (çekirdek/mm²).')
            addWarnLabel('⚠ Sınırlama: ' + CAVEAT)
            if (!isHE) addWarnLabel('⚠ Görüntü tipi H&E değil (' + typeName + '). HoVer-NeXt H&E için eğitildi.')
            if (ci.block != null) addWarnLabel('⛔ Ölçek: ' + ci.block)
            else if (ci.warn != null) addWarnLabel('⚠ Kalibrasyon: ' + ci.warn)
            if (!configComplete(cfg)) addWarnLabel('⚠ Python ortamı (hovernext) kurulu değil — "⚙ Python ortamını kur/aç" ile kurun.')
            else if (!localReady) addWarnLabel('⚠ HoVer-NeXt kodu/ağırlığı yerelde yok — Yapılandır → "Modeli yerel indir".')
            if (targets.size() < 1) addWarnLabel('⚠ Önce en az 1 alan anotasyonu çizin/seçin.')
            boolean canRun = configComplete(cfg) && localReady && targets.size() >= 1 && ci.scaleOk
            actions.add(navButton('Kapat', { stage.close() }))
            if (!configComplete(cfg)) actions.add(navButton('⚙ Python ortamını kur/aç', { launchEnvManager() }, 'Atölye Python ortam yöneticisini açar'))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
            def runBtn = navButton('Bölgede çalıştır ▶', { startRun() }, 'HoVer-NeXt çekirdek segmentasyonu + sınıflandırmasını seçili bölgede çalıştırır')
            runBtn.setDisable(!canRun)
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING') {
        title.setText(runPhaseRef.get())
        addGuidance('HoVer-NeXt köprüsü çalışıyor (ayrı süreç; ilk çalıştırmada ImageNet omurgası da indirilebilir). Zaman aşımı: ' + PYTHON_TIMEOUT_SECONDS + ' sn.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { killRunning() }))
        actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }, 'O ana kadarki çalışma günlüğünü dosyaya kaydet'))
    } else if (cur == 'BUSY') {
        title.setText(busyLabelRef.get()); addGuidance('Lütfen bekleyin…'); center.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅'); addMonoArea(resultTextRef.get())
        int nMit = (mitosisCoordsRef.get()?.size() ?: 0)
        addGuidance('Çekirdek sınıflarını Annotations/Hierarchy panelinden ya da View → Show detections ile inceleyin; sınıf renklerini "Classes" listesinden aç/kapat edin.')
        def lf = logFileRef.get(); if (lf != null) addGuidance('Çalışma günlüğü otomatik kaydedildi: ' + lf.getAbsolutePath())
        actions.add(navButton('Kapat', { stage.close() }))
        if (nMit > 0) actions.add(navButton('◀ Önceki', { goToMitosis(navIdxRef.get() - 1); render() }, 'Önceki mitoz-sınıflı çekirdeğe git'))
        if (nMit > 0) actions.add(navButton('Mitoza git ' + (navIdxRef.get() >= 0 ? ((navIdxRef.get() + 1) + '/' + nMit) : ('1/' + nMit)) + ' ▶', { goToMitosis(navIdxRef.get() + 1); render() }, 'Görüntüleyiciyi mitoz-sınıflı çekirdeğin üstüne ortala'))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }, 'HoVer-NeXt çalışma günlüğünü dosyaya kaydet'))
        actions.add(navButton('↻ Yeniden çalıştır', { step.set('READY'); render() }))
    } else { // ERROR
        title.setText('Hata'); addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Geri', { step.set('READY'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }, 'Çalışma günlüğünü dosyaya kaydet'))
        actions.add(navButton('Kapat', { stage.close() }))
    }

    def topChk = new javafx.scene.control.CheckBox('Üstte tut'); topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n -> alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
    def spacer = new javafx.scene.layout.Region(); javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8); bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk); bar.getChildren().add(spacer); bar.getChildren().addAll(actions)
    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar); bottom.setPadding(new javafx.geometry.Insets(10))
    def root = new javafx.scene.layout.BorderPane(); root.setCenter(center); root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 920, 700))
}

// ── Açılış ────────────────────────────────────────────────────────────────────
step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('HoVer-NeXt — çekirdek segmentasyonu + 7 sınıf (Lizard)')
        stage.setAlwaysOnTop(alwaysTop.get())
        render(); stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ HoVer-NeXt çekirdek segmentasyonu + sınıflandırma sihirbazı açıldı."
