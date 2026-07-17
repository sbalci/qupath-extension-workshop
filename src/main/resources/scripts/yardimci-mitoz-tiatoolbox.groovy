/**
 * Modül - Mitoz tespiti (TIA Toolbox · KongNet MIDOG, bölgede canlı)
 * -----------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   QuPath'te ÇİZDİĞİNİZ alan anotasyonu içinde — ve YALNIZ orada — Warwick TIA
 *   Centre'in **KongNet-Det (MIDOG)** H&E mitoz dedektörünü canlı çalıştırır.
 *   Seçtiğiniz bölge ikili bir maskeye rasterlenir → Python köprüsü (region_runner.py)
 *   `engine.run(masks=[...], auto_get_mask=False)` ile yalnız o bölgede KongNet_Det_MIDOG_1
 *   modelini koşturur → mitoz merkezleri GeoJSON olarak geri alınır, bölgeye göre
 *   filtrelenir, "Mitosis" nokta-tespiti olarak eklenir ve SAYIM + YOĞUNLUK üretilir:
 *   mitoz/mm² ve (ROI ~2 mm² ise) gözlenen mitoz / 2 mm².
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Seçili alandaki KongNet-tespitli mitoz noktalarının SAYIM ve YOĞUNLUĞU. Bu bir
 *     SAYIM/YOĞUNLUKtur — mitoz derecesi, grade eşiği veya klinik yorum DEĞİL.
 *   • KongNet doğrudan H&E üzerinde çalışır (PHH3-İHK gerekmez). Çıktı bir derin öğrenme
 *     tahminidir; alan kayması / genelleme sınırları için "Yapay Zekâ Araçlarını
 *     Değerlendirme" ekine bakın. Tahminleri görsel doğrulayın.
 *
 * ÇALIŞMA ZAMANI:
 *   TIA Toolbox ortamını (tiatoolbox + torch) ve region_runner.py köprüsünü, TIA Toolbox
 *   bölge sihirbazıyla AYNI şekilde kullanır (paylaşılan `tiatoolbox-region` venv'i). Ortam
 *   yoksa: Extensions → Atölye → Yardımcılar → Python köprüleri → Atölye Python ortam
 *   yöneticisi → "TIA Toolbox — bölge modelleri".
 *
 * KULLANIM:
 *   1. H&E slaydını (tercihen OpenSlide) açın; piksel boyutu (µm/px) kalibre olsun.
 *   2. İlgi ALANINI anotasyon olarak çizin ve SEÇİN (ör. 10 BBA sıcak-nokta).
 *   3. Bu modülü çalıştırın → "Bölgede çalıştır".
 *
 * ÇIKTI:
 *   • Her mitoz için "Mitosis" sınıflı nokta-tespiti
 *   • Her seçili anotasyona: "Mitoz (KongNet)" + "Mitoz yoğunluğu (mitoz/mm2)"
 *   • Kilitli "KongNet MIDOG mitoz özeti" anotasyonu (Veri dışa aktarma ile dışa aktarılır)
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Lv J ve ark. (2025) KongNet — MIDOG 2025 mitoz tespiti birincisi (KongNet-Det).
 *     TIA Toolbox model kayıtlı adı: KongNet_Det_MIDOG_1 (class "Mitotic_Figure", 0.5 µm/px).
 *   • Alan-tabanlı mitoz birimi (mitoz/2 mm²): bkz. PHH3 Mitoz eki (Dessauvagie 2015).
 *
 * API: ROIs.createPointsROI + PathObjects.createDetectionObject (QuPath 0.6.0+);
 *      GeoJSON ayrıştırma com.google.gson.JsonParser (QuPath 0.7 groovy.json içermez).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.geom.Point2
import qupath.lib.regions.ImagePlane
import com.google.gson.JsonParser
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ───────────────────────────────────────────────────────────────
long PYTHON_TIMEOUT_SECONDS = 3600L
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def SENTINEL_PREFIX = 'Mitoz (KongNet MIDOG) · '
def SUMMARY_NAME    = 'KongNet MIDOG mitoz özeti'
// ── Model (mitoz-özel, sabit) ────────────────────────────────────────────────
// KongNet-Det (MIDOG 2025 birincisi) TIA Toolbox'ta H&E mitoz için TEK uygun
// modeldir; kaydın diğer nucleus_detector modelleri (PanNuke/CoNIC/MONKEY/MapDe)
// ÇEKİRDEK/hücre dedektörleridir — mitoz DEĞİL. O modellerle deney yapmak için
// genel "TIA Toolbox — bölge modelleri" sihirbazı zaten hepsini (doğru sınıf-başına
// içe alımla) çalıştırır. Bu modül bilinçli olarak mitoz-odaklı kalır. Bkz. modül
// sayfası "Mitoz modelleri" bölümü.
def MODEL          = 'KongNet_Det_MIDOG_1'
def ENGINE         = 'nucleus_detector'
def MITOSIS_CLASS  = 'Mitosis'                 // içe alınan nokta-anotasyonu sınıfı
def MITOSIS_PREFIX = 'KongNet MIDOG mitoz'     // nokta-anotasyonu ad öneki
def MODEL_CLASSES  = 'Mitotic figure'          // class_dict (nucleus_detector çıktı adı)
double MODEL_MPP = 0.5          // KongNet_Det_MIDOG_1 çalışma çözünürlüğü (registry ioconfig)
double WHO_AREA_MM2 = 2.0        // WHO alan-tabanlı raporlama birimi (mitoz / 2 mm²)
double AREA_TOL_MM2 = 0.01

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır) ──
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/mitoz-tiatoolbox')
def PREF_PYTHON = 'python'
def PREF_RUNNER = 'runner'
def PREF_WORK   = 'workDir'
def PREF_DEVICE = 'device'
def PREF_MDS    = 'maskDownsample'
def PREF_BATCH  = 'batchSize'
def PREF_THRESH = 'threshold'      // model threshold_abs geçersiz kılma (boş = model varsayılanı)
def PREF_OFFLINE= 'offline'        // 'true' → HF_HUB_OFFLINE (yerel model, ağa çıkma)

// ── Atölye veri kökü (env yöneticisiyle PAYLAŞILAN) + model önbellek yönlendirme ──
def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
// Yerel model dosyasının beklenen yolu (fetch_pretrained_weights → TIATOOLBOX_HOME/models/<ad>.pth)
def modelCacheFile = { -> new File(new File(new File(new File(atolyeDataRoot(), 'cache'), 'tiatoolbox'), 'models'), MODEL + '.pth') }
// Model önbelleklerini de veri köküne yönlendir (TIATOOLBOX_HOME'u tiatoolbox KENDİSİ
// okumaz; asıl uygulama region_runner.py'de rcParam ile yapılır — bu env yalnız değeri taşır).
// offline=true → HF_HUB_OFFLINE: hiç ağa çıkma, yalnız yerel indirilmiş modeli kullan.
def applyCacheEnv = { pb, boolean offline = false ->
    try {
        def cache = new File(atolyeDataRoot(), 'cache'); cache.mkdirs()
        def hf = new File(cache, 'huggingface'); def env = pb.environment()
        env.put('HF_HOME', hf.getAbsolutePath()); env.put('HF_HUB_CACHE', new File(hf, 'hub').getAbsolutePath())
        env.put('TORCH_HOME', new File(cache, 'torch').getAbsolutePath())
        env.put('TIATOOLBOX_HOME', new File(cache, 'tiatoolbox').getAbsolutePath())
        if (offline) { env.put('HF_HUB_OFFLINE', '1'); env.put('TRANSFORMERS_OFFLINE', '1') }
    } catch (Throwable ignore) {}
}

// ── Otomatik tespit: TIA Toolbox bölge sihirbazıyla AYNI ortam/köprü ──────────
def detectPython = { ->
    // (1) Resmî TIAToolbox eklenti ortamı: <kullanıcı>/QuPath/v*/tiatoolbox-runtime/.venv
    def base = new File(System.getProperty('user.home'), 'QuPath')
    if (base.isDirectory()) {
        def vdirs = base.listFiles({ f -> f.isDirectory() && f.getName().startsWith('v') } as java.io.FileFilter)
        if (vdirs != null) {
            def cands = []
            vdirs.sort { it.getName() }.each { vd ->
                def rt = new File(vd, 'tiatoolbox-runtime/.venv')
                def win = new File(rt, 'Scripts/python.exe'); def nix = new File(rt, 'bin/python')
                if (win.isFile()) cands << win else if (nix.isFile()) cands << nix
            }
            if (!cands.isEmpty()) return cands.last().getAbsolutePath()
        }
    }
    // (2) Env yöneticisinin kaydettiği KESİN yol (veri kökü değişse bile doğru).
    try {
        def rec = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('py.tiatoolbox-region', '')
        if (rec?.trim() && new File(rec.trim()).isFile()) return rec.trim()
    } catch (Throwable ignore) {}
    // (3) Yedek: Atölye ortam yöneticisi venv'ini veri kökünden tahmin et.
    def at = new File(new File(atolyeDataRoot(), 'runtimes'), 'tiatoolbox-region/.venv')
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
        def f = new File(r, 'python/tiatoolbox/region_runner.py')
        if (f.isFile()) return f.getAbsolutePath()
    }
    return ''
}

def loadConfig = { ->
    def py = prefs.get(PREF_PYTHON, ''); if (!py?.trim()) py = detectPython()
    def rn = prefs.get(PREF_RUNNER, ''); if (!rn?.trim()) rn = detectRunner()
    [ python         : py,
      runner         : rn,
      workDir        : prefs.get(PREF_WORK,   ''),
      device         : prefs.get(PREF_DEVICE, 'cuda'),
      maskDownsample : prefs.get(PREF_MDS,    '16.0'),
      batchSize      : prefs.get(PREF_BATCH,  '8'),
      threshold      : prefs.get(PREF_THRESH, ''),     // boş = model varsayılanı (KongNet 0.99)
      offline        : prefs.get(PREF_OFFLINE, 'false') ]  // 'true' → HF_HUB_OFFLINE
}
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile())
        miss << 'Python yürütülebilir (tiatoolbox-runtime/.venv)'
    if (!cfg.runner?.trim() || !(new File(cfg.runner)).isFile())
        miss << 'Köprü betiği (region_runner.py)'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

def parseIntOr = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }
def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().trim().replace(',', '.')) } catch (Throwable t) { return d } }

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
def wsiPathOf = { imageData ->
    try {
        def uris = imageData.getServer().getURIs()
        if (uris != null && !uris.isEmpty()) {
            def uri = uris.iterator().next()
            if ('file'.equals(uri.getScheme())) return new File(uri).getAbsolutePath()
        }
    } catch (Throwable ignore) {}
    return null
}

// Piksel boyutu (µm/px) — yoğunluk için gerekli
def pixelMicrons = { imageData ->
    try {
        def cal = imageData.getServer().getPixelCalibration()
        if (cal != null && cal.hasPixelSizeMicrons())
            return [pw: cal.getPixelWidthMicrons(), ph: cal.getPixelHeightMicrons()]
    } catch (Throwable ignore) {}
    return null
}

// Kalibrasyon bilgisi + akla yatkınlık denetimi (ÇALIŞTIRMADAN ÖNCE göster).
// Yanlış kalibrasyon KongNet'i yanlış ölçekte çalıştırır → 0 tespit olası nedeni.
def calibrationInfo = { imageData ->
    def cal = pixelMicrons(imageData)
    double mag = Double.NaN
    try { mag = imageData.getServer().getMetadata().getMagnification() } catch (Throwable ignore) {}
    def warn = null
    if (cal == null) {
        warn = 'Piksel boyutu tanımlı DEĞİL — yoğunluk hesaplanamaz VE KongNet yanlış ölçekte çalışabilir (0 tespit olası). Kalibrasyon yardımcısını çalıştırın.'
    } else {
        double p = (cal.pw + cal.ph) / 2.0
        if (p <= 0.0) warn = 'Piksel boyutu geçersiz (≤ 0).'
        else if (Math.abs(p - 1.0) < 1e-6) warn = 'Piksel boyutu tam 1.000 µm/px — bu genelde QuPath\'in KALİBRESİZ varsayılanıdır. Tarayıcının gerçek değerini girin (Kalibrasyon); yanlışsa KongNet mitoz bulamayabilir.'
        else if (p < 0.10 || p > 1.5) warn = String.format(java.util.Locale.US, 'Piksel boyutu %.3f µm/px H&E WSI için sıra dışı — kalibrasyonu doğrulayın (tipik: 0.25 @40x, 0.50 @20x). Yanlış ölçek → 0 tespit.', p)
    }
    return [cal: cal, mag: mag, warn: warn, mpp: (cal != null ? (cal.pw + cal.ph) / 2.0 : Double.NaN)]
}
// KongNet'in slaydı hangi yönde yeniden örnekleyeceğini açıkla (0.5 µm/px hedef).
def resampleNote = { double mpp ->
    if (!Double.isFinite(mpp) || mpp <= 0) return ''
    double ratio = MODEL_MPP / mpp
    if (Math.abs(ratio - 1.0) < 0.05) return 'slayt zaten ~0.5 µm/px — yeniden örnekleme yok.'
    if (ratio > 1.0) return String.format(java.util.Locale.US, 'slaydınız daha yüksek çözünürlükte; KongNet ~%.1f× AŞAĞI örnekler (normal).', ratio)
    return String.format(java.util.Locale.US, 'slaydınız daha düşük çözünürlükte; KongNet ~%.1f× YUKARI örnekler (mitoz kaçırabilir).', 1.0 / ratio)
}

// Seçili (yoksa tüm) alan anotasyonları — mitozu YALNIZ bunların içinde sayarız.
// Kilitli özet anotasyonunu HER İKİ dalda da hedeflerden dışla (yeniden çalıştırmada
// önceki özetin kendisi bir hedef bölge olmasın).
def notSummary = { ann -> ann.getName() == null || !ann.getName().startsWith(SUMMARY_NAME) }
def regionAnnotationsOf = { imageData ->
    def h = imageData.getHierarchy()
    def sel = h.getSelectionModel().getSelectedObjects().findAll { it.isAnnotation() && it.hasROI() && it.getROI().isArea() && notSummary(it) }
    if (!sel.isEmpty()) return new ArrayList(sel)
    return new ArrayList(h.getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() && notSummary(it) })
}

// ── Bölgeyi TÜM-slayt ikili maskesine rasterle ──────────────────────────────
def exportRegionMask = { imageData, File workDir, double downsample, List regionRois, Closure appendLine ->
    def server = imageData.getServer()
    int W = server.getWidth(), H = server.getHeight()
    int mw = (int) Math.max(1, Math.ceil(W / downsample))
    int mh = (int) Math.max(1, Math.ceil(H / downsample))
    def img = new java.awt.image.BufferedImage(mw, mh, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
    def g = img.createGraphics()
    try {
        g.setColor(java.awt.Color.BLACK); g.fillRect(0, 0, mw, mh)
        g.setColor(java.awt.Color.WHITE)
        def at = java.awt.geom.AffineTransform.getScaleInstance(1.0d / downsample, 1.0d / downsample)
        regionRois.each { roi -> def s = roi.getShape(); if (s != null) g.fill(at.createTransformedShape(s)) }
    } finally { g.dispose() }
    def f = new File(workDir, 'mitoz_region_mask.png')
    if (f.getParentFile() != null) f.getParentFile().mkdirs()
    javax.imageio.ImageIO.write(img, 'PNG', f)
    appendLine('Bölge maskesi: ' + f.getName() + ' (' + mw + ' × ' + mh + ' px, downsample ' + downsample + ')')
    return [ok: true, file: f, w: mw, h: mh]
}

// ── GeoJSON içe al: bölge içi noktaları çıkar + nokta-anotasyonu olarak ekle ──
def importMitoses = { File geojson, imageData, List regionRois ->
    if (geojson == null || !geojson.isFile())
        return [ok: false, error: 'GeoJSON çıktısı bulunamadı:\n' + (geojson?.getAbsolutePath() ?: '(yol yok)')]
    def root
    try { root = JsonParser.parseString(geojson.getText('UTF-8')).getAsJsonObject() }
    catch (Throwable t) { return [ok: false, error: 'GeoJSON ayrıştırılamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }
    def feats = root.has('features') ? root.getAsJsonArray('features') : null
    if (feats == null) return [ok: false, error: 'GeoJSON "features" içermiyor.']
    def plane = ImagePlane.getDefaultPlane()
    def coords = new ArrayList()
    int total = 0
    for (el in feats) {
        def ft = el.getAsJsonObject()
        if (!ft.has('geometry') || ft.get('geometry').isJsonNull()) continue
        def geom = ft.getAsJsonObject('geometry')
        if (!geom.has('type') || geom.get('type').getAsString() != 'Point') continue
        def c = geom.getAsJsonArray('coordinates')
        double x = c.get(0).getAsDouble(), y = c.get(1).getAsDouble()
        total++
        if (!regionRois.any { it.contains(x, y) }) continue
        coords.add([x, y])
    }
    def hier = imageData.getHierarchy()
    def detClass = QP.getPathClass(MITOSIS_CLASS)
    // Sınıfa belirgin bir renk ver (görünür işaret) — kırmızı.
    try { detClass.setColor(qupath.lib.common.ColorTools.packRGB(255, 40, 40)) } catch (Throwable ignore) {}
    // Her tespiti AYRI bir nokta-ANOTASYONU olarak ekle → Annotations panelinde tek tek
    // listelenir ve seçilebilir. YALNIZ bu bölgedeki önceki mitoz anotasyonlarını sil
    // (alt-küme yeniden çalıştırma diğer bölgeleri korur, #4).
    hier.removeObjects(hier.getAnnotationObjects().findAll { a ->
        a.getName() != null && a.getName().startsWith(MITOSIS_PREFIX) && a.hasROI() &&
        regionRois.any { r -> r.contains(a.getROI().getCentroidX(), a.getROI().getCentroidY()) }
    }, false)
    def newAnns = []
    coords.eachWithIndex { pt, i ->
        def ann = PathObjects.createAnnotationObject(ROIs.createPointsROI(pt[0] as double, pt[1] as double, plane), detClass)
        ann.setName(MITOSIS_PREFIX + ' #' + (i + 1))
        newAnns << ann
    }
    if (!newAnns.isEmpty()) hier.addObjects(newAnns)
    hier.fireHierarchyChangedEvent(hier)
    return [ok: true, total: total, inside: coords.size(), coords: coords]
}

// ── Sayım + (kalibreyse) yoğunluk: seçili anotasyonlara ölçüm ekle + kilitli özet ──
// cal null olsa bile SAYIM her zaman yazılır; alan/yoğunluk/WHO yalnız kalibreyse.
def writeCounts = { imageData, List targets, List coords, cal ->
    def hier = imageData.getHierarchy()
    targets.each { ann ->
        def roi = ann.getROI()
        int cnt = coords.count { pt -> roi.contains(pt[0] as double, pt[1] as double) } as int
        ann.measurements['Mitoz (KongNet)'] = cnt as double
        if (cal != null) {
            double a = roi.getArea() * cal.pw * cal.ph / 1_000_000.0
            ann.measurements['ROI alanı (mm2)'] = a
            ann.measurements['Mitoz yoğunluğu (mitoz/mm2)'] = a > 0 ? cnt / a : Double.NaN
        }
    }
    // Birleşik ROI (örtüşme tek sayılır — coords zaten bölge-içi tekildir)
    def unionRoi = (targets.size() == 1) ? targets[0].getROI() : qupath.lib.roi.RoiTools.union(targets.collect { it.getROI() })
    int insideUnion = coords.size()

    hier.removeObjects(hier.getAnnotationObjects().findAll { it.getName() != null && it.getName().startsWith(SUMMARY_NAME) }, false)
    def summary = PathObjects.createAnnotationObject(unionRoi)
    summary.setName(SUMMARY_NAME)
    summary.measurements['Seçili ROI sayısı'] = targets.size() as double
    summary.measurements['Mitoz (KongNet, bölge içi)'] = insideUnion as double
    def out = [inside: insideUnion, unionAreaMm2: Double.NaN, density: Double.NaN]
    if (cal != null) {
        double unionAreaMm2 = unionRoi.getArea() * cal.pw * cal.ph / 1_000_000.0
        double density = unionAreaMm2 > 0 ? insideUnion / unionAreaMm2 : Double.NaN
        summary.measurements['Ölçülen alan (mm2)'] = unionAreaMm2
        summary.measurements['Mitoz yoğunluğu (mitoz/mm2)'] = density
        // WHO 2 mm² birimi: yalnız alan ~2 mm² ise GÖZLENEN sayıyı yaz (asla ölçekleme)
        if (Math.abs(unionAreaMm2 - WHO_AREA_MM2) <= AREA_TOL_MM2)
            summary.measurements['Mitoz / 2 mm2 (gozlenen)'] = insideUnion as double
        out = [inside: insideUnion, unionAreaMm2: unionAreaMm2, density: density]
    }
    summary.setLocked(true)
    hier.addObjects([summary])
    hier.fireHierarchyChangedEvent(hier)
    return out
}

// ── Sonuç metni ─────────────────────────────────────────────────────────────
def resultText = { imageData, cfg, imp, dens, cal ->
    def fmt = { double v, String p -> Double.isFinite(v) ? String.format(java.util.Locale.US, p, v) : 'hesaplanamadı' }
    def sb = new StringBuilder()
    sb << "KONGNET MIDOG — BÖLGEDE MİTOZ\n"
    sb << "═════════════════════════════\n\n"
    sb << "Slayt   : " << imageNameOf(imageData) << "\n"
    sb << "Model   : " << MODEL << "  (H&E mitoz, 0.5 µm/px)\n"
    sb << "Cihaz   : " << (cfg.device ?: 'cuda') << "\n"
    sb << String.format(java.util.Locale.US, "Tespit  : toplam %,d   ·   BÖLGE İÇİ %,d%n", (int)(imp?.total ?: 0), (int)(imp?.inside ?: 0))
    if (cal != null && dens != null) {
        sb << String.format(java.util.Locale.US, "Ölçülen alan : %.3f mm²%n", (double)(dens.unionAreaMm2 ?: 0.0))
        sb << "Yoğunluk     : " << fmt((double)(dens.density ?: Double.NaN), '%.1f mitoz/mm²') << "\n"
        double a = (double)(dens.unionAreaMm2 ?: 0.0)
        if (Math.abs(a - WHO_AREA_MM2) <= AREA_TOL_MM2)
            sb << String.format(java.util.Locale.US, "Mitoz / %.0f mm² (gözlenen): %,d%n", WHO_AREA_MM2, (int)(dens.inside ?: 0))
        else
            sb << String.format(java.util.Locale.US, "Mitoz / %.0f mm²: hesaplanmadı (alan %.3f mm²; ROI'yi ~2 mm² yapın)%n", WHO_AREA_MM2, a)
    } else {
        sb << "Yoğunluk     : hesaplanmadı — piksel boyutu (µm/px) kalibre değil (Yardımcılar → Kalibrasyon).\n"
    }
    if ((int)(imp?.inside ?: 0) == 0) {
        sb << "\n⚠ Bölgede 0 mitoz — model çalıştı ama eşiği geçen tespit yok.\n"
        if (!cfg.threshold?.trim())
            sb << "  KongNet varsayılan eşiği 0.99 ÇOK TUTUCUdur. Yapılandır → 'Duyarlılık eşiği'ni\n  düşürüp (ör. 0.5) yeniden çalıştırın; kalibrasyonu da doğrulayın.\n"
        else
            sb << "  Eşiği (" << cfg.threshold.trim() << ") daha da düşürmeyi ve/veya kalibrasyonu doğrulamayı deneyin.\n"
    }
    sb << "\nTespitler '" << MITOSIS_CLASS << "' sınıflı KIRMIZI nokta-anotasyonları olarak eklendi; seçili anotasyonlara\n"
    sb << "sayım + yoğunluk ölçümü yazıldı; kilitli özet: '" << SUMMARY_NAME << "'.\n"
    sb << "Tahminleri görsel doğrulayın; klinik kategori/yorum üretilmez.\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

// ── Headless ────────────────────────────────────────────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    println "Mitoz (KongNet MIDOG) sihirbazı: python=${cfg.python ?: '(ayarsız)'} runner=${cfg.runner ?: '(ayarsız)'} model=${MODEL} cihaz=${cfg.device}"
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(', ')}"
    if (imageData != null) {
        println "Alan anotasyonu: ${regionAnnotationsOf(imageData).size()}"
        println "Piksel boyutu: ${pixelMicrons(imageData) ? 'kalibre' : 'KALİBRE DEĞİL (yoğunluk hesaplanamaz)'}"
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
def mitosisCoordsRef = new java.util.concurrent.atomic.AtomicReference(new ArrayList())  // bölge içi mitoz noktaları (gezinme)
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
def deviceChoiceRef= new java.util.concurrent.atomic.AtomicReference(null)
def mdsFieldRef    = new java.util.concurrent.atomic.AtomicReference(null)
def batchFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def threshFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def offlineChkRef  = new java.util.concurrent.atomic.AtomicReference(null)
def render

// Görüntüleyiciyi i. mitoza ORTALA + (gerekliyse) yakınlaş — "nerede?" sorusunu çöz.
def goToMitosis = { int i ->
    def coords = mitosisCoordsRef.get()
    if (coords == null || coords.isEmpty()) return
    int n = coords.size(); int idx = ((i % n) + n) % n
    def pt = coords[idx]; navIdxRef.set(idx)
    javafx.application.Platform.runLater {
        try {
            def viewer = gui.getViewer()
            if (viewer != null) {
                if (viewer.getDownsampleFactor() > 4.0d) viewer.setDownsampleFactor(2.0d)  // çok uzaksa yakınlaş
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

// ── Çalıştırma günlüğü: tüm satırları biriktir + otomatik dosyaya yaz + "kaydet" ──
// (Canlı günlük TextArea'sı sonuç ekranında kaybolur; kullanıcı "kaydedemedim"
//  dememeli — bu yüzden hem çalışma dizinine otomatik yazarız hem de "kaydet"
//  düğmesi sunarız.)
def runLog      = new StringBuilder()
def logFileRef  = new java.util.concurrent.atomic.AtomicReference(null)
def resetLog    = { -> synchronized (runLog) { runLog.setLength(0) } }
def appendLog   = { String ln -> synchronized (runLog) { runLog.append(ln == null ? '' : ln).append('\n') } }
def logSnapshot = { -> synchronized (runLog) { return runLog.toString() } }
def autoSaveLog = { File dir, String base ->
    try {
        if (dir == null) return null
        dir.mkdirs()
        def f = new File(dir, (base ?: 'mitoz') + '_run.log')
        f.setText(logSnapshot(), 'UTF-8'); logFileRef.set(f); return f
    } catch (Throwable t) { return null }
}
def saveLogInteractive = {
    def txt = logSnapshot()
    if (!txt?.trim()) { Dialogs.showInfoNotification('Günlük', 'Kaydedilecek günlük yok.'); return }
    try {
        def suggested = logFileRef.get() ?: new File(System.getProperty('user.home'), 'mitoz_run.log')
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
    def dv = deviceChoiceRef.get(); prefs.put(PREF_DEVICE, (dv != null && dv.getValue() != null) ? dv.getValue() : 'cuda')
    def md = textOf(mdsFieldRef);  prefs.put(PREF_MDS,   md ?: '16.0')
    def bs = textOf(batchFieldRef); prefs.put(PREF_BATCH, bs ?: '8')
    prefs.put(PREF_THRESH, textOf(threshFieldRef))
    def ofc = offlineChkRef.get(); prefs.put(PREF_OFFLINE, (ofc != null && ofc.isSelected()) ? 'true' : 'false')
    try { prefs.flush() } catch (Throwable ignore) {}
}

// ── Python süreci → satır akışı ──────────────────────────────────────────────
def runPython = { List cmd, Closure onLine, boolean offline = false ->
    def pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true)
    applyCacheEnv(pb, offline)
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
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Bağımlılık kontrolü'); step.set('CHECK_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython([cfg.python, cfg.runner, 'selftest'], appendLine)
        javafx.application.Platform.runLater { selftestOkRef.set(r.ok); step.set('CHECK_DONE'); render() }
    }, 'AtolyeMitoz-Check')
    worker.setDaemon(true); worker.start()
}

// ── Modeli yerel indir (bir kez) → sonra "Çevrimdışı çalış" ile ağa çıkmadan koş ──
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
        // İndirme HER ZAMAN çevrimiçi (offline=false); cmd_download offline env'i zaten kaldırır.
        def r = runPython([cfg.python, cfg.runner, 'download', '--model', MODEL], appendLine, false)
        javafx.application.Platform.runLater { dlOkRef.set(r.ok); step.set('DL_DONE'); render() }
    }, 'AtolyeMitoz-Download')
    worker.setDaemon(true); worker.start()
}

// ── Çalıştırma akışı — seçili bölgede canlı KongNet MIDOG ────────────────────
def startRun = {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def wsi = wsiPathOf(imageData)
    if (wsi == null) { errorTextRef.set('Slaytın yerel dosya yolu yok (WSI tiatoolbox tarafından açılamaz).\nSlaytı OpenSlide ile açın.'); step.set('ERROR'); render(); return }
    def targets = regionAnnotationsOf(imageData)
    if (targets.isEmpty()) { errorTextRef.set('Bölge yok.\nÖnce bir alan anotasyonu çizin/seçin.'); step.set('ERROR'); render(); return }
    def regionRois = targets.collect { it.getROI() }
    def cal = pixelMicrons(imageData)
    def workDir = resolveWorkDir(cfg, imageData); workDir.mkdirs()
    def base    = imageNameOf(imageData)
    def saveDir = new File(workDir, 'mitoz_out_' + base)
    def outGeo  = new File(workDir, base + '_mitoz.geojson')
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Hazırlanıyor…'); step.set('RUN_RUNNING'); render()

    def worker = new Thread({
        // appendLine: hem canlı TextArea'ya hem de kalıcı günlük tamponuna yazar.
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def setPhase   = { String ph -> javafx.application.Platform.runLater { runPhaseRef.set(ph); render() } }
        try {
            appendLine('Model: ' + MODEL + '  ·  cihaz: ' + (cfg.device ?: 'cuda') + '  ·  maske downsample: ' + cfg.maskDownsample)
            appendLine('Kapsam: seçili bölge (' + targets.size() + ' anotasyon)')
            def ci0 = calibrationInfo(imageData)
            if (ci0.cal != null) appendLine(String.format(java.util.Locale.US, 'Kalibrasyon: %.4f × %.4f µm/px%s  →  KongNet %.2f µm/px (%s)',
                ci0.cal.pw, ci0.cal.ph, (Double.isFinite(ci0.mag) && ci0.mag > 0 ? String.format(java.util.Locale.US, ' (~%.0fx)', ci0.mag) : ''), MODEL_MPP, resampleNote(ci0.mpp)))
            if (ci0.warn != null) appendLine('⚠ Kalibrasyon: ' + ci0.warn)
            if (cal == null) appendLine('⚠ Piksel boyutu kalibre değil — yoğunluk (mitoz/mm²) hesaplanmayacak.')
            double mds = parseDoubleOr(cfg.maskDownsample, 16.0d)
            setPhase('Bölge maskesi yazılıyor (1/2)…')
            def exp = exportRegionMask(imageData, workDir, mds, regionRois, appendLine)
            if (!exp.ok) { javafx.application.Platform.runLater { errorTextRef.set(exp.error); step.set('ERROR'); render() }; return }
            if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }
            def cmd = [cfg.python, cfg.runner, 'detect',
                       '--wsi', wsi,
                       '--mask', exp.file.getAbsolutePath(),
                       '--model', MODEL,
                       '--engine', ENGINE,
                       '--out', outGeo.getAbsolutePath(),
                       '--save-dir', saveDir.getAbsolutePath(),
                       '--device', (cfg.device ?: 'cuda'),
                       '--batch-size', String.valueOf(parseIntOr(cfg.batchSize, 8)),
                       '--classes', MODEL_CLASSES]
            // Duyarlılık: kullanıcı bir eşik girdiyse modelin tutucu threshold_abs'ini geçersiz kıl.
            if (cfg.threshold?.trim()) {
                double thr = parseDoubleOr(cfg.threshold, -1.0d)
                if (thr >= 0.0d && thr <= 1.0d) { cmd.add('--threshold'); cmd.add(String.format(java.util.Locale.US, '%.4f', thr)); appendLine('Duyarlılık eşiği: ' + String.format(java.util.Locale.US, '%.3f', thr) + ' (model varsayılanı 0.99)') }
            }
            boolean offlineOn = (cfg.offline == 'true')
            if (offlineOn) appendLine('Çevrimdışı mod: HF_HUB_OFFLINE=1 (yalnız yerel model; ağa çıkılmaz)')
            setPhase('KongNet MIDOG çıkarımı koşuyor (2/2)…')
            def r = runPython(cmd, appendLine, offlineOn)
            // Çıktı ne olursa olsun günlüğü çalışma dizinine YAZ — kullanıcı hep bulabilsin.
            appendLine('# Çıkış kodu: ' + r.exitCode)
            def savedLog = autoSaveLog(workDir, base)
            if (savedLog != null) appendLine('# Günlük kaydedildi: ' + savedLog.getAbsolutePath())
            if (!r.ok) { javafx.application.Platform.runLater { errorTextRef.set('Çıkarım başarısız (çıkış: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '') + (savedLog != null ? ('\n\nÇalışma günlüğü: ' + savedLog.getAbsolutePath()) : '')); step.set('ERROR'); render() }; return }
            def geo = outGeo
            try { def m = (r.lastLines ?: '') =~ /RESULT geojson=(.+)/; if (m.find()) { def gp = new File(m.group(1).trim()); if (gp.isFile()) geo = gp } } catch (Throwable ignore) {}

            // Uzun (saatlerce olabilen) çıkarımdan SONRA QP.getCurrentImageData()'yı
            // YENİDEN ALMA — pencere kipsiz; kullanıcı arada görüntü değiştirebilir/kapatabilir.
            // Koşuyu başlatan `imageData` bu yolda hiç kapatılmaz; hep onu kullan (yabancı
            // görüntüye yazma / null NPE önlenir).
            javafx.application.Platform.runLater { busyLabelRef.set('Sonuçlar içe aktarılıyor…'); step.set('BUSY'); render() }
            def imp = importMitoses(geo, imageData, regionRois)
            if (!imp.ok) { javafx.application.Platform.runLater { errorTextRef.set(imp.error); step.set('ERROR'); render() }; return }
            def dens = writeCounts(imageData, targets, imp.coords, cal)
            mitosisCoordsRef.set(new ArrayList(imp.coords)); navIdxRef.set(-1)
            javafx.application.Platform.runLater {
                try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
                resultTextRef.set(resultText(imageData, cfg, imp, dens, cal)); step.set('RESULT'); render()
                if (!imp.coords.isEmpty()) goToMitosis(0)   // ilk mitoza ortala (görünür olsun)
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Beklenmeyen hata:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
        }
    }, 'AtolyeMitoz-Run')
    worker.setDaemon(true); worker.start()
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

    // Sarma: setMaxWidth(MAX_VALUE) yetmeyebilir (kapsayıcı pencereden geniş olabilir);
    // etiket maxWidth'ini SAHNE genişliğine bağla — her koşulda pencerede sarılır.
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

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('Mitoz tespiti — çalışma zamanı gerekli')
        def miss = configMissing(cfg)
        addGuidance('Bu modül TIA Toolbox çalışma zamanını (tiatoolbox + torch) gerektirir — TIA Toolbox bölge sihirbazıyla AYNI ortam.\nEksik/geçersiz:\n  • ' +
            (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')) +
            '\n\nKurulum: Extensions → Atölye → Yardımcılar → Python köprüleri → Atölye Python ortam yöneticisi → "TIA Toolbox — bölge modelleri".\n' +
            'Köprü betiği: handson/python/tiatoolbox/region_runner.py')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText('Mitoz tespiti — yapılandırma')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def rnField = new javafx.scene.control.TextField(cfg.runner ?: '')
        def wdField = new javafx.scene.control.TextField(cfg.workDir ?: '')
        def mdsField = new javafx.scene.control.TextField(cfg.maskDownsample ?: '16.0')
        def batchField = new javafx.scene.control.TextField(cfg.batchSize ?: '8')
        def threshField = new javafx.scene.control.TextField(cfg.threshold ?: '')
        threshField.setPromptText('boş = 0.99')
        [pyField, rnField, wdField].each { it.setPrefColumnCount(36) }
        [mdsField, batchField, threshField].each { it.setPrefColumnCount(8) }
        def deviceChoice = new javafx.scene.control.ChoiceBox(); ['cuda', 'cpu'].each { deviceChoice.getItems().add(it) }
        deviceChoice.setValue((cfg.device == 'cpu') ? 'cpu' : 'cuda')
        pyFieldRef.set(pyField); runnerFieldRef.set(rnField); workFieldRef.set(wdField)
        deviceChoiceRef.set(deviceChoice); mdsFieldRef.set(mdsField); batchFieldRef.set(batchField); threshFieldRef.set(threshField)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Dosya seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir  = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Dizin seç', null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Model:'), new javafx.scene.control.Label(MODEL + '  (KongNet MIDOG — H&E mitoz, sabit)'))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (.venv/python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Köprü (region_runner.py):'), rnField, navButton('…', { browseFile(rnField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Çalışma dizini (ops.):'), wdField, navButton('…', { browseDir(wdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Cihaz:'), deviceChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Maske downsample:'), mdsField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Batch size:'), batchField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Duyarlılık eşiği (0–1):'), threshField)
        def offlineChk = new javafx.scene.control.CheckBox('Çevrimdışı çalış (yalnız yerel model — ağa çıkma)')
        offlineChk.setSelected(cfg.offline == 'true'); offlineChkRef.set(offlineChk)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Ağ:'), offlineChk)
        center.getChildren().add(grid)
        def mcf = modelCacheFile()
        def mcLbl = new javafx.scene.control.Label(mcf.isFile()
            ? ('✓ Yerel model VAR: ' + mcf.getAbsolutePath())
            : '○ Yerel model yok — "Modeli yerel indir" ile bir kez indirin (sonra çevrimdışı çalışabilirsiniz).')
        mcLbl.setWrapText(true); mcLbl.setMaxWidth(Double.MAX_VALUE); mcLbl.setStyle('-fx-opacity: 0.85; -fx-font-size: 11px;')
        center.getChildren().add(mcLbl)
        addGuidance('Model: Bu modül H&E mitoz için sabit "KongNet MIDOG" (MIDOG 2025 birincisi) kullanır — TIA Toolbox\'ta mitoza uygun TEK model. Çekirdek/hücre dedektörleriyle (PanNuke/CoNIC/MONKEY/MapDe) deney için → Yardımcılar → Python köprüleri → "TIA Toolbox — bölge modelleri" (hepsini sınıf-başına içe alımla çalıştırır). Model listesi ve lisanslar: modül sayfası "Mitoz modelleri".\n' +
            'Python + köprü otomatik bulunur (tiatoolbox-runtime/.venv ve handson/python/tiatoolbox/). Cihaz: GPU için cuda.\n' +
            'Duyarlılık eşiği: KongNet varsayılanı 0.99 (çok tutucu — az/hiç tespit). Daha çok mitoz için düşürün (ör. 0.5); boş = model varsayılanı. ' +
            'Düşük eşik daha çok tespit ama daha çok yanlış-pozitif demektir — tahminleri görsel doğrulayın.\n' +
            'Yerel model: "Modeli yerel indir" ağırlıkları BİR KEZ veri kökü altına indirir; ardından "Çevrimdışı çalış"ı işaretleyin → koşularda ağa hiç çıkılmaz (GrandQC gibi).')
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('Modeli yerel indir', { startModelDownload() }, 'KongNet ağırlıklarını bir kez yerel indir (sonra çevrimdışı çalışılabilir)'))
        actions.add(navButton('Bağımlılık kontrolü', { startSelftest() }, 'region_runner.py selftest'))
        actions.add(navButton('Kaydet ▶', { persistFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
    } else if (cur == 'CHECK_RUNNING') {
        title.setText('Bağımlılık kontrolü çalışıyor…')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'CHECK_DONE') {
        title.setText(selftestOkRef.get() ? 'Bağımlılık kontrolü tamam ✅'
            : '⚠ Bağımlılık kontrolü BAŞARISIZ — yukarıdaki günlüğe bakın (Python / bağımlılık)'); addLiveLog()
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }, 'Bağımlılık kontrolü günlüğünü kaydet'))
    } else if (cur == 'DL_RUNNING') {
        title.setText('Model indiriliyor (yerel)…')
        addGuidance('KongNet ağırlıkları veri kökü altına indiriliyor (bir kerelik; ~yüzlerce MB olabilir).')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'DL_DONE') {
        title.setText(dlOkRef.get() ? 'Model yerel olarak indirildi ✅' : '⚠ Model indirilemedi — yukarıdaki günlüğe bakın'); addLiveLog()
        if (dlOkRef.get()) addGuidance('Artık "Çevrimdışı çalış"ı işaretleyip ağa çıkmadan koşabilirsiniz (Yapılandır ekranı).')
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }, 'İndirme günlüğünü kaydet'))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText('Görüntü açık değil')
            addGuidance('Önce bir H&E slaydı açın (tercihen OpenSlide), ilgi ALANINI çizip seçin, sonra "⟳ Yenile".')
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
        } else {
            def targets = regionAnnotationsOf(imageData)
            def ci = calibrationInfo(imageData)
            def cal = ci.cal
            def typeName = (imageData.getImageType()?.name() ?: '').toUpperCase(java.util.Locale.ROOT)
            boolean isHE = typeName.contains('BRIGHTFIELD_H_E')
            title.setText('Mitoz tespiti — KongNet MIDOG (bölgede)')
            def sb = new StringBuilder()
            sb << "Slayt          : " << imageNameOf(imageData) << "\n"
            sb << "Model          : " << MODEL << "  (H&E mitoz)\n"
            sb << "Python         : " << (cfg.python ?: '(ayarsız)') << "\n"
            sb << "Cihaz          : " << (cfg.device ?: 'cuda') << "\n"
            sb << "Duyarlılık eşiği: " << (cfg.threshold?.trim() ? cfg.threshold.trim() : '0.99 (model varsayılanı — tutucu)') << "\n"
            sb << "Ağ / model     : " << ((cfg.offline == 'true') ? 'ÇEVRİMDIŞI (yalnız yerel)' : 'çevrimiçi') << (modelCacheFile().isFile() ? '  ·  yerel model: VAR' : '  ·  yerel model: yok') << "\n"
            sb << String.format(java.util.Locale.US, "Seçili bölge   : %,d alan anotasyonu%n", targets.size())
            // ── Kalibrasyon bilgisi (ÇALIŞTIRMADAN ÖNCE) ──
            if (cal != null) {
                sb << String.format(java.util.Locale.US, "Piksel boyutu  : %.4f × %.4f µm/px%s%n", cal.pw, cal.ph,
                    (Double.isFinite(ci.mag) && ci.mag > 0 ? String.format(java.util.Locale.US, '   (~%.0f×)', ci.mag) : ''))
                sb << String.format(java.util.Locale.US, "KongNet çözün. : %.2f µm/px — %s%n", MODEL_MPP, resampleNote(ci.mpp))
            } else {
                sb << "Piksel boyutu  : KALİBRE DEĞİL — yoğunluk hesaplanmaz, KongNet yanlış ölçekte çalışabilir\n"
            }
            addMonoArea(sb.toString())
            addGuidance('KongNet MIDOG YALNIZ seçili/çizili alan anotasyon(lar)ı içinde çalışır. Sonuç: "Mitosis" nokta-tespitleri + her anotasyona mitoz sayısı ve yoğunluğu (mitoz/mm²); ROI ~2 mm² ise WHO birimi (mitoz/2 mm²).')
            if (!isHE) addWarnLabel('⚠ Görüntü tipi H&E değil (' + typeName + '). KongNet H&E için tasarlanmıştır.')
            if (ci.warn != null) addWarnLabel('⚠ Kalibrasyon: ' + ci.warn)
            boolean canRun = configComplete(cfg) && targets.size() >= 1
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
            def runBtn = navButton('Bölgede çalıştır ▶', { startRun() }, 'KongNet MIDOG mitoz dedektörünü seçili bölgede çalıştırır')
            runBtn.setDisable(!canRun)
            if (!canRun && targets.size() < 1) addWarnLabel('⚠ Önce en az 1 alan anotasyonu çizin/seçin.')
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING') {
        title.setText(runPhaseRef.get())
        addGuidance('KongNet MIDOG köprüsü koşuyor (ilk çalıştırmada model ağırlıkları indirilebilir). Zaman aşımı: ' + PYTHON_TIMEOUT_SECONDS + ' sn.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
        actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }, 'O ana kadarki çalışma günlüğünü dosyaya kaydet'))
    } else if (cur == 'BUSY') {
        title.setText(busyLabelRef.get()); addGuidance('Lütfen bekleyin…'); center.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅'); addMonoArea(resultTextRef.get())
        int nMit = (mitosisCoordsRef.get()?.size() ?: 0)
        if (nMit > 0) addGuidance('Tespitler "' + MITOSIS_CLASS + '" sınıflı KIRMIZI nokta-anotasyonları olarak eklendi (Annotations panelinde "' + MITOSIS_PREFIX + ' #…"). Düşük yakınlaştırmada nokta küçük görünür — "Mitoza git" ile üstüne gidin.')
        def lf = logFileRef.get(); if (lf != null) addGuidance('Çalışma günlüğü otomatik kaydedildi: ' + lf.getAbsolutePath())
        actions.add(navButton('Kapat', { stage.close() }))
        if (nMit > 0) actions.add(navButton('◀ Önceki', { goToMitosis(navIdxRef.get() - 1); render() }, 'Önceki mitoza git'))
        if (nMit > 0) actions.add(navButton('Mitoza git ' + (navIdxRef.get() >= 0 ? ((navIdxRef.get() + 1) + '/' + nMit) : ('1/' + nMit)) + ' ▶', { goToMitosis(navIdxRef.get() + 1); render() }, 'Görüntüleyiciyi mitozun üstüne ortala + yakınlaştır'))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }, 'KongNet çalışma günlüğünü (mitoz neden 0 vb.) dosyaya kaydet'))
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
    stage.setScene(new javafx.scene.Scene(root, 900, 640))
}

// ── Açılış ────────────────────────────────────────────────────────────────────
step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Mitoz tespiti — TIA Toolbox (KongNet MIDOG)')
        stage.setAlwaysOnTop(alwaysTop.get())
        render(); stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ Mitoz tespiti (TIA Toolbox · KongNet MIDOG) sihirbazı açıldı."
