/**
 * Modül - Mitoz tespiti (2021/22 MIDOG DA-RetinaNet, bölgede canlı)
 * ----------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   QuPath'te ÇİZDİĞİNİZ alan anotasyonu içinde — ve YALNIZ orada — DeepMicroscopy'nin
 *   2021/22 MIDOG referans dedektörünü (Domain-Adversarial RetinaNet, ResNet18, fastai v1)
 *   canlı çalıştırır. Sihirbaz seçili bölgeyi hedef çözünürlükte (varsayılan 0.5 µm/px) bir
 *   ROI görüntüsü olarak dışa aktarır → Python köprüsü (midog/retinanet_runner.py) referans
 *   deposunun KODUNU + AĞIRLIĞINI çalışma anında indirir (GrandQC gibi; paketlenmez), o kodla
 *   512-döşemeli çıkarım yapar → mitotik figür merkezleri TABAN-piksel koordinatlarında GeoJSON
 *   olarak geri alınır, bölgeye göre filtrelenir, "Mitosis" nokta-anotasyonu olarak eklenir ve
 *   SAYIM + YOĞUNLUK üretilir.
 *
 * ⚠️ ESKİ / DOĞRULANMAMIŞ ORTAM UYARISI:
 *   Bu model **fastai==1.0.61** gerektirir (referans: torch>=1.6 / torchvision>=0.10, GPU'lu
 *   olabilir). Ancak fastai 1.x + modern torch bilinen bir kırılgan kombinasyondur; ortam
 *   çözülmeyebilir ya da import sırasında hata verebilir. Bu ATÖLYEDE DOĞRULANMAMIŞTIR. Çalışmazsa
 *   bilinen bir sınırlamadır — bunun yerine **MIDOG25 FCOS** (modern, sağlam, aşılmış referans)
 *   kullanın. Bkz. plan abort kriteri.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Seçili alandaki RetinaNet-tespitli mitoz noktalarının SAYIM ve YOĞUNLUĞU. Klinik
 *     yorum/derece DEĞİL. Tahminleri görsel doğrulayın.
 *
 * AĞIRLIK/KOD LİSANSI:
 *   Referans deponun LİSANS dosyası yok; kod+ağırlık çalışma anında indirilir (paketlenmez) →
 *   araştırma/eğitim, kullanıcı sorumluluğunda. Atıf: MIDOG, DeepMicroscopy.
 *
 * ÇALIŞMA ZAMANI:
 *   fastai 1.0.61 (+ eski torch, CPU) ortamı (env id: midog-retinanet-legacy) +
 *   midog/retinanet_runner.py köprüsü. Kurulum: Extensions → Atölye → Yardımcılar → Python
 *   köprüleri → Atölye Python ortam yöneticisi → "MIDOG DA-RetinaNet (eski, CPU)".
 *
 * ÇIKTI:
 *   • Her mitoz için "Mitosis" sınıflı nokta-anotasyonu ("RetinaNet mitoz #i")
 *   • Her seçili anotasyona: "Mitoz (RetinaNet)" + "Mitoz yoğunluğu (mitoz/mm2)"
 *   • Kilitli "RetinaNet MIDOG mitoz özeti" anotasyonu
 *
 * API: RegionRequest.createInstance + ImageServer.readRegion; ROIs.createPointsROI +
 *      PathObjects.createAnnotationObject; GeoJSON com.google.gson.JsonParser.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.geom.Point2
import qupath.lib.regions.ImagePlane
import qupath.lib.regions.RegionRequest
import com.google.gson.JsonParser
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ───────────────────────────────────────────────────────────────
long PYTHON_TIMEOUT_SECONDS = 3600L
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def SUMMARY_NAME    = 'RetinaNet MIDOG mitoz özeti'
def MODEL          = 'DA-RetinaNet (MIDOG 2021/22)'
def ENV_ID         = 'midog-retinanet-legacy'
def MITOSIS_CLASS  = 'Mitosis'                 // paylaşılan sözleşme
def MITOSIS_PREFIX = 'RetinaNet mitoz'
double TARGET_MPP  = 0.5        // MIDOG kanonik; KongNet/FCOS ile tutarlı
double DET_THRESH  = 0.64       // referans process.py çıktı eşiği
double WHO_AREA_MM2 = 2.0
double AREA_TOL_MM2 = 0.01
int    ROI_WARN_PX = 12000

def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/mitoz-retinanet')
def PREF_PYTHON = 'python'
def PREF_RUNNER = 'runner'
def PREF_WORK   = 'workDir'
def PREF_DEVICE = 'device'
def PREF_TMPP   = 'targetMpp'
def PREF_BATCH  = 'batchSize'
def PREF_THRESH = 'threshold'

def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
// RetinaNet ağırlığı indirilen deponun içinde: <cache>/midog-retinanet/MIDOG_reference_docker-master/model_weights/RetinaNetDA.pth
def modelCacheFile = { -> new File(new File(new File(new File(new File(new File(atolyeDataRoot(), 'cache'), 'midog-retinanet'), 'MIDOG_reference_docker-master'), 'model_weights'), ''), 'RetinaNetDA.pth') }
def applyCacheEnv = { pb ->
    try {
        def cache = new File(atolyeDataRoot(), 'cache'); cache.mkdirs()
        def env = pb.environment()
        env.put('HF_HOME', new File(cache, 'huggingface').getAbsolutePath())
        env.put('TORCH_HOME', new File(cache, 'torch').getAbsolutePath())
        env.put('TIATOOLBOX_HOME', new File(cache, 'tiatoolbox').getAbsolutePath())
    } catch (Throwable ignore) {}
}

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
    for (r in roots) { def f = new File(r, 'python/midog/retinanet_runner.py'); if (f.isFile()) return f.getAbsolutePath() }
    return ''
}

def loadConfig = { ->
    def py = prefs.get(PREF_PYTHON, ''); if (!py?.trim()) py = detectPython()
    def rn = prefs.get(PREF_RUNNER, ''); if (!rn?.trim()) rn = detectRunner()
    [ python    : py,
      runner    : rn,
      workDir   : prefs.get(PREF_WORK,   ''),
      device    : prefs.get(PREF_DEVICE, 'cuda'),
      targetMpp : prefs.get(PREF_TMPP,   ''),
      batchSize : prefs.get(PREF_BATCH,  '8'),
      threshold : prefs.get(PREF_THRESH, '') ]
}
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile()) miss << 'Python yürütülebilir (midog-retinanet-legacy venv)'
    if (!cfg.runner?.trim() || !(new File(cfg.runner)).isFile()) miss << 'Köprü betiği (retinanet_runner.py)'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

def parseIntOr = { s, int dfl -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return dfl } }
def parseDoubleOr = { s, double dfl -> try { return Double.parseDouble((s ?: '').toString().trim().replace(',', '.')) } catch (Throwable t) { return dfl } }

def resolveWorkDir = { cfg, imageData ->
    def wd = cfg.workDir?.trim()
    if (wd) return new File(wd)
    def project = QP.getProject()
    if (project != null && project.getPath() != null) return new File(project.getPath().getParent().toFile(), 'tiatoolbox_work')
    try {
        def uris = imageData.getServer().getURIs()
        if (uris != null && !uris.isEmpty()) { def uri = uris.iterator().next(); if ('file'.equals(uri.getScheme())) { def f = new File(uri); if (f.getParentFile() != null) return new File(f.getParentFile(), 'tiatoolbox_work') } }
    } catch (Throwable ignore) {}
    return new File(System.getProperty('java.io.tmpdir'), 'tiatoolbox_work')
}

def imageNameOf = { imageData -> (imageData.getServer().getMetadata().getName() ?: 'slide').replaceAll(/\.[^.\/\\]+$/, '') }
def pixelMicrons = { imageData ->
    try { def cal = imageData.getServer().getPixelCalibration(); if (cal != null && cal.hasPixelSizeMicrons()) return [pw: cal.getPixelWidthMicrons(), ph: cal.getPixelHeightMicrons()] } catch (Throwable ignore) {}
    return null
}
def calibrationInfo = { imageData ->
    def cal = pixelMicrons(imageData)
    double mag = Double.NaN
    try { mag = imageData.getServer().getMetadata().getMagnification() } catch (Throwable ignore) {}
    def warn = null
    if (cal == null) warn = 'Piksel boyutu tanımlı DEĞİL — yoğunluk hesaplanamaz VE ROI hedef çözünürlüğe örneklenemez. Kalibrasyon yardımcısını çalıştırın.'
    else {
        double p = (cal.pw + cal.ph) / 2.0
        if (p <= 0.0) warn = 'Piksel boyutu geçersiz (≤ 0).'
        else if (Math.abs(p - 1.0) < 1e-6) warn = 'Piksel boyutu tam 1.000 µm/px — genelde KALİBRESİZ varsayılan. Tarayıcının gerçek değerini girin.'
        else if (p < 0.10 || p > 1.5) warn = String.format(java.util.Locale.US, 'Piksel boyutu %.3f µm/px sıra dışı — kalibrasyonu doğrulayın (tipik: 0.25 @40x, 0.50 @20x).', p)
    }
    return [cal: cal, mag: mag, warn: warn, mpp: (cal != null ? (cal.pw + cal.ph) / 2.0 : Double.NaN)]
}
def resampleNote = { double mpp, double targetMpp ->
    if (!Double.isFinite(mpp) || mpp <= 0) return ''
    double ratio = targetMpp / mpp
    if (Math.abs(ratio - 1.0) < 0.05) return 'slayt zaten ~hedef çözünürlükte — yeniden örnekleme yok.'
    if (ratio > 1.0) return String.format(java.util.Locale.US, 'slaydınız daha yüksek çözünürlükte; ROI ~%.1f× AŞAĞI örneklenir (normal).', ratio)
    return 'slaydınız daha düşük çözünürlükte; hedef çözünürlüğe çıkılamaz (native beslenir).'
}

def notSummary = { ann -> ann.getName() == null || !ann.getName().startsWith(SUMMARY_NAME) }
def regionAnnotationsOf = { imageData ->
    def h = imageData.getHierarchy()
    def sel = h.getSelectionModel().getSelectedObjects().findAll { it.isAnnotation() && it.hasROI() && it.getROI().isArea() && notSummary(it) }
    if (!sel.isEmpty()) return new ArrayList(sel)
    return new ArrayList(h.getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() && notSummary(it) })
}

def exportRegionImage = { imageData, File workDir, double targetMpp, List regionRois, cal, int warnPx, Closure appendLine ->
    def server = imageData.getServer()
    double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY
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
    double baseMpp = (cal != null) ? (cal.pw + cal.ph) / 2.0 : Double.NaN
    double downsample = 1.0
    if (cal != null && targetMpp > 0 && Double.isFinite(baseMpp) && baseMpp > 0) { downsample = targetMpp / baseMpp; if (downsample < 1.0) downsample = 1.0 }
    int outW = (int) Math.max(1, Math.round(w / downsample)); int outH = (int) Math.max(1, Math.round(h / downsample))
    if (outW > warnPx || outH > warnPx)
        appendLine(String.format(java.util.Locale.US, '⚠ Büyük ROI (%d × %d px) — CPU\'da çok yavaş olabilir. Daha küçük bir sıcak-nokta seçin.', outW, outH))
    def request = qupath.lib.regions.RegionRequest.createInstance(server.getPath(), downsample, x, y, w, h)
    def img = server.readRegion(request)
    if (img == null) return [ok: false, error: 'Bölge okunamadı (readRegion null döndü).']
    def rgb = new java.awt.image.BufferedImage(img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB)
    def g = rgb.createGraphics()
    try { g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight()); g.drawImage(img, 0, 0, null) } finally { g.dispose() }
    def f = new File(workDir, 'retinanet_roi.png')
    if (f.getParentFile() != null) f.getParentFile().mkdirs()
    javax.imageio.ImageIO.write(rgb, 'PNG', f)
    appendLine(String.format(java.util.Locale.US, 'ROI görüntüsü: %s (%d × %d px, downsample %.3f, köken %d,%d)', f.getName(), rgb.getWidth(), rgb.getHeight(), downsample, x, y))
    return [ok: true, file: f, originX: x, originY: y, downsample: downsample]
}

def importMitoses = { File geojson, imageData, List regionRois ->
    if (geojson == null || !geojson.isFile()) return [ok: false, error: 'GeoJSON çıktısı bulunamadı:\n' + (geojson?.getAbsolutePath() ?: '(yol yok)')]
    def root
    try { root = JsonParser.parseString(geojson.getText('UTF-8')).getAsJsonObject() } catch (Throwable t) { return [ok: false, error: 'GeoJSON ayrıştırılamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }
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
    try { detClass.setColor(qupath.lib.common.ColorTools.packRGB(170, 70, 200)) } catch (Throwable ignore) {}  // RetinaNet = mor
    hier.removeObjects(hier.getAnnotationObjects().findAll { a ->
        a.getName() != null && a.getName().startsWith(MITOSIS_PREFIX) && a.hasROI() &&
        regionRois.any { r -> r.contains(a.getROI().getCentroidX(), a.getROI().getCentroidY()) }
    }, false)
    def newAnns = []
    coords.eachWithIndex { pt, i ->
        def ann = PathObjects.createAnnotationObject(ROIs.createPointsROI(pt[0] as double, pt[1] as double, plane), detClass)
        ann.setName(MITOSIS_PREFIX + ' #' + (i + 1)); newAnns << ann
    }
    if (!newAnns.isEmpty()) hier.addObjects(newAnns)
    hier.fireHierarchyChangedEvent(hier)
    return [ok: true, total: total, inside: coords.size(), coords: coords]
}

def writeCounts = { imageData, List targets, List coords, cal ->
    def hier = imageData.getHierarchy()
    targets.each { ann ->
        def roi = ann.getROI()
        int cnt = coords.count { pt -> roi.contains(pt[0] as double, pt[1] as double) } as int
        ann.measurements['Mitoz (RetinaNet)'] = cnt as double
        if (cal != null) {
            double a = roi.getArea() * cal.pw * cal.ph / 1_000_000.0
            ann.measurements['ROI alanı (mm2)'] = a
            ann.measurements['Mitoz yoğunluğu (mitoz/mm2)'] = a > 0 ? cnt / a : Double.NaN
        }
    }
    def unionRoi = (targets.size() == 1) ? targets[0].getROI() : qupath.lib.roi.RoiTools.union(targets.collect { it.getROI() })
    int insideUnion = coords.size()
    hier.removeObjects(hier.getAnnotationObjects().findAll { it.getName() != null && it.getName().startsWith(SUMMARY_NAME) }, false)
    def summary = PathObjects.createAnnotationObject(unionRoi)
    summary.setName(SUMMARY_NAME)
    summary.measurements['Seçili ROI sayısı'] = targets.size() as double
    summary.measurements['Mitoz (RetinaNet, bölge içi)'] = insideUnion as double
    def out = [inside: insideUnion, unionAreaMm2: Double.NaN, density: Double.NaN]
    if (cal != null) {
        double unionAreaMm2 = unionRoi.getArea() * cal.pw * cal.ph / 1_000_000.0
        double density = unionAreaMm2 > 0 ? insideUnion / unionAreaMm2 : Double.NaN
        summary.measurements['Ölçülen alan (mm2)'] = unionAreaMm2
        summary.measurements['Mitoz yoğunluğu (mitoz/mm2)'] = density
        if (Math.abs(unionAreaMm2 - WHO_AREA_MM2) <= AREA_TOL_MM2) summary.measurements['Mitoz / 2 mm2 (gozlenen)'] = insideUnion as double
        out = [inside: insideUnion, unionAreaMm2: unionAreaMm2, density: density]
    }
    summary.setLocked(true); hier.addObjects([summary]); hier.fireHierarchyChangedEvent(hier)
    return out
}

def resultText = { imageData, cfg, imp, dens, cal ->
    def fmt = { double v, String p -> Double.isFinite(v) ? String.format(java.util.Locale.US, p, v) : 'hesaplanamadı' }
    def sb = new StringBuilder()
    sb << "DA-RetinaNet (MIDOG 2021/22) — BÖLGEDE MİTOZ\n"
    sb << "════════════════════════════════════════════\n\n"
    sb << "Slayt   : " << imageNameOf(imageData) << "\n"
    sb << "Model   : " << MODEL << "\n"
    sb << "Cihaz   : " << (cfg.device ?: 'cuda') << " (eski/doğrulanmamış ortam)\n"
    sb << String.format(java.util.Locale.US, "Tespit  : BÖLGE İÇİ %,d%n", (int)(imp?.inside ?: 0))
    if (cal != null && dens != null) {
        sb << String.format(java.util.Locale.US, "Ölçülen alan : %.3f mm²%n", (double)(dens.unionAreaMm2 ?: 0.0))
        sb << "Yoğunluk     : " << fmt((double)(dens.density ?: Double.NaN), '%.1f mitoz/mm²') << "\n"
        double a = (double)(dens.unionAreaMm2 ?: 0.0)
        if (Math.abs(a - WHO_AREA_MM2) <= AREA_TOL_MM2) sb << String.format(java.util.Locale.US, "Mitoz / %.0f mm² (gözlenen): %,d%n", WHO_AREA_MM2, (int)(dens.inside ?: 0))
        else sb << String.format(java.util.Locale.US, "Mitoz / %.0f mm²: hesaplanmadı (alan %.3f mm²)%n", WHO_AREA_MM2, a)
    } else sb << "Yoğunluk     : hesaplanmadı — piksel boyutu kalibre değil.\n"
    if ((int)(imp?.inside ?: 0) == 0) {
        sb << "\n⚠ Bölgede 0 mitoz — model çalıştı ama eşiği geçen tespit yok.\n"
        sb << "  Duyarlılık eşiğini düşürüp yeniden çalıştırın; kalibrasyonu doğrulayın.\n"
    }
    sb << "\nTespitler '" << MITOSIS_CLASS << "' sınıflı MOR nokta-anotasyonları olarak eklendi; kilitli özet: '" << SUMMARY_NAME << "'.\n"
    sb << "Tahminleri görsel doğrulayın; klinik kategori/yorum üretilmez.\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    println "Mitoz (RetinaNet) sihirbazı: python=${cfg.python ?: '(ayarsız)'} runner=${cfg.runner ?: '(ayarsız)'} model=${MODEL}"
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(', ')}"
    if (imageData != null) println "Alan anotasyonu: ${regionAnnotationsOf(imageData).size()}"
    else println "Açık görüntü yok."
    println "⚠️ Eski fastai ortamı, CPU. Bu sihirbaz QuPath arayüzü gerektirir."
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Durum makinesi alanları ──
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
def deviceChoiceRef= new java.util.concurrent.atomic.AtomicReference(null)
def tmppFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def batchFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def threshFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def render

def goToMitosis = { int i ->
    def coords = mitosisCoordsRef.get()
    if (coords == null || coords.isEmpty()) return
    int n = coords.size(); int idx = ((i % n) + n) % n
    def pt = coords[idx]; navIdxRef.set(idx)
    javafx.application.Platform.runLater {
        try { def viewer = gui.getViewer(); if (viewer != null) { if (viewer.getDownsampleFactor() > 4.0d) viewer.setDownsampleFactor(2.0d); viewer.setCenterPixelLocation(pt[0] as double, pt[1] as double) } } catch (Throwable ignore) {}
    }
}
def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text); b.setOnAction({ action() }); if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip)); return b
}
def busyBar = { -> def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb }
def copyToClipboard = { String txt -> def cb = javafx.scene.input.Clipboard.getSystemClipboard(); def content = new javafx.scene.input.ClipboardContent(); content.putString(txt ?: ""); cb.setContent(content) }
// Python ortamı kurulu değilse: Atölye Python ortam yöneticisini kendi penceresinde aç.
def launchBundledScript = { String resourceName ->
    new Thread({
        try {
            def url = null
            try { url = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getResource('/scripts/' + resourceName) } catch (Throwable t) {}
            if (url == null) url = this.getClass().getResource('/scripts/' + resourceName)
            if (url == null) { javafx.application.Platform.runLater { Dialogs.showInfoNotification('Betik bulunamadı', 'Menüden açın: Extensions → Atölye → Yardımcılar → Python köprüleri & temel modeller → Atölye Python ortam yöneticisi') }; return }
            def cl = this.getClass().getClassLoader()
            try { cl = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getClassLoader() } catch (Throwable t) {}
            new GroovyShell(cl).evaluate(url.getText('UTF-8'), resourceName)
        } catch (Throwable t) { javafx.application.Platform.runLater { Dialogs.showErrorMessage('Açılamadı', (t.getMessage() ?: t.getClass().getSimpleName())) } }
    } as Runnable).start()
}

def runLog = new StringBuilder()
def logFileRef = new java.util.concurrent.atomic.AtomicReference(null)
def resetLog = { -> synchronized (runLog) { runLog.setLength(0) } }
def appendLog = { String ln -> synchronized (runLog) { runLog.append(ln == null ? '' : ln).append('\n') } }
def logSnapshot = { -> synchronized (runLog) { return runLog.toString() } }
def autoSaveLog = { File dir, String base -> try { if (dir == null) return null; dir.mkdirs(); def f = new File(dir, (base ?: 'mitoz-retinanet') + '_run.log'); f.setText(logSnapshot(), 'UTF-8'); logFileRef.set(f); return f } catch (Throwable t) { return null } }
def saveLogInteractive = {
    def txt = logSnapshot(); if (!txt?.trim()) { Dialogs.showInfoNotification('Günlük', 'Kaydedilecek günlük yok.'); return }
    try {
        def suggested = logFileRef.get() ?: new File(System.getProperty('user.home'), 'mitoz-retinanet_run.log')
        def f = qupath.fx.dialogs.FileChoosers.promptToSaveFile(stage, 'Çalışma günlüğünü kaydet', suggested, new javafx.stage.FileChooser.ExtensionFilter('Günlük (*.log, *.txt)', '*.log', '*.txt'))
        if (f != null) { f.setText(txt, 'UTF-8'); Dialogs.showInfoNotification('Günlük', 'Kaydedildi: ' + f.getAbsolutePath()) }
    } catch (Throwable t) { Dialogs.showErrorMessage('Günlük', 'Kaydedilemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName())) }
}
def textOf = { ref -> def f = ref.get(); return (f != null ? f.getText() : '').trim() }
def persistFields = {
    prefs.put(PREF_PYTHON, textOf(pyFieldRef)); prefs.put(PREF_RUNNER, textOf(runnerFieldRef)); prefs.put(PREF_WORK, textOf(workFieldRef))
    def dv = deviceChoiceRef.get(); prefs.put(PREF_DEVICE, (dv != null && dv.getValue() != null) ? dv.getValue() : 'cuda')
    def tm = textOf(tmppFieldRef); prefs.put(PREF_TMPP, tm ?: '')
    def bs = textOf(batchFieldRef); prefs.put(PREF_BATCH, bs ?: '8')
    prefs.put(PREF_THRESH, textOf(threshFieldRef))
    try { prefs.flush() } catch (Throwable ignore) {}
}

def runPython = { List cmd, Closure onLine ->
    def pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true); applyCacheEnv(pb)
    def proc
    try { proc = pb.start() } catch (Throwable e) { return [ok: false, exitCode: -1, error: 'Python başlatılamadı: ' + (e.getMessage() ?: e.getClass().getSimpleName())] }
    processRef.set(proc)
    def last = new java.util.ArrayDeque()
    try {
        def reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
        String line
        while ((line = reader.readLine()) != null) { last.addLast(line); while (last.size() > 80) last.pollFirst(); onLine(line); if (cancelledRef.get()) break }
        reader.close()
    } catch (Throwable ignore) {}
    boolean finished
    try { finished = proc.waitFor(PYTHON_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) } catch (InterruptedException ie) { proc.destroyForcibly(); return [ok: false, exitCode: -3, error: 'İptal edildi'] }
    if (!finished) { proc.destroyForcibly(); return [ok: false, exitCode: -2, error: 'Zaman aşımı'] }
    if (cancelledRef.get()) { proc.destroyForcibly(); return [ok: false, exitCode: -3, error: 'İptal edildi'] }
    int code = proc.exitValue()
    return [ok: (code == 0), exitCode: code, lastLines: last.join('\n')]
}

def startSelftest = {
    persistFields(); def cfg = loadConfig(); def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set('Önce yapılandırmayı tamamlayın:\n  • ' + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO); logAreaRef.set(la)
    runPhaseRef.set('Bağımlılık kontrolü'); step.set('CHECK_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython([cfg.python, cfg.runner, 'selftest'], appendLine)
        javafx.application.Platform.runLater { selftestOkRef.set(r.ok); step.set('CHECK_DONE'); render() }
    }, 'AtolyeRetina-Check')
    worker.setDaemon(true); worker.start()
}
def startModelDownload = {
    persistFields(); def cfg = loadConfig(); def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set('Önce yapılandırmayı tamamlayın:\n  • ' + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO); logAreaRef.set(la)
    runPhaseRef.set('Model + kod indiriliyor…'); step.set('DL_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython([cfg.python, cfg.runner, 'download'], appendLine)
        javafx.application.Platform.runLater { dlOkRef.set(r.ok); step.set('DL_DONE'); render() }
    }, 'AtolyeRetina-Download')
    worker.setDaemon(true); worker.start()
}

def startRun = {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def targets = regionAnnotationsOf(imageData)
    if (targets.isEmpty()) { errorTextRef.set('Bölge yok.\nÖnce bir alan anotasyonu çizin/seçin.'); step.set('ERROR'); render(); return }
    def regionRois = targets.collect { it.getROI() }
    def cal = pixelMicrons(imageData)
    double targetMpp = cfg.targetMpp?.trim() ? parseDoubleOr(cfg.targetMpp, TARGET_MPP) : TARGET_MPP
    def workDir = resolveWorkDir(cfg, imageData); workDir.mkdirs()
    def base = imageNameOf(imageData)
    def outGeo = new File(workDir, base + '_retinanet_mitoz.geojson')
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO); logAreaRef.set(la)
    runPhaseRef.set('Hazırlanıyor…'); step.set('RUN_RUNNING'); render()

    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def setPhase = { String ph -> javafx.application.Platform.runLater { runPhaseRef.set(ph); render() } }
        try {
            appendLine('Model: ' + MODEL + '  ·  (eski fastai ortamı, CPU)  ·  hedef çözünürlük: ' + String.format(java.util.Locale.US, '%.3f µm/px', targetMpp))
            appendLine('Kapsam: seçili bölge (' + targets.size() + ' anotasyon)')
            def ci0 = calibrationInfo(imageData)
            if (ci0.cal != null) appendLine(String.format(java.util.Locale.US, 'Kalibrasyon: %.4f × %.4f µm/px%s  →  hedef %.2f µm/px (%s)', ci0.cal.pw, ci0.cal.ph, (Double.isFinite(ci0.mag) && ci0.mag > 0 ? String.format(java.util.Locale.US, ' (~%.0fx)', ci0.mag) : ''), targetMpp, resampleNote(ci0.mpp, targetMpp)))
            if (ci0.warn != null) appendLine('⚠ Kalibrasyon: ' + ci0.warn)
            if (cal == null) appendLine('⚠ Piksel boyutu kalibre değil — yoğunluk hesaplanmayacak.')
            setPhase('ROI görüntüsü dışa aktarılıyor (1/2)…')
            def exp = exportRegionImage(imageData, workDir, targetMpp, regionRois, cal, ROI_WARN_PX, appendLine)
            if (!exp.ok) { javafx.application.Platform.runLater { errorTextRef.set(exp.error); step.set('ERROR'); render() }; return }
            if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }
            def cmd = [cfg.python, cfg.runner, 'detect', '--roi', exp.file.getAbsolutePath(), '--out', outGeo.getAbsolutePath(),
                       '--origin', (exp.originX + ',' + exp.originY), '--downsample', String.format(java.util.Locale.US, '%.6f', (double) exp.downsample),
                       '--device', (cfg.device ?: 'cpu'), '--batch-size', String.valueOf(parseIntOr(cfg.batchSize, 8))]
            if (cfg.threshold?.trim()) { double thr = parseDoubleOr(cfg.threshold, -1.0d); if (thr >= 0.0d && thr <= 1.0d) { cmd.add('--det-thresh'); cmd.add(String.format(java.util.Locale.US, '%.4f', thr)); appendLine('Duyarlılık eşiği: ' + String.format(java.util.Locale.US, '%.3f', thr) + ' (referans 0.64)') } }
            setPhase('RetinaNet çıkarımı koşuyor (2/2, yavaş olabilir)…')
            def r = runPython(cmd, appendLine)
            appendLine('# Çıkış kodu: ' + r.exitCode)
            def savedLog = autoSaveLog(workDir, base)
            if (savedLog != null) appendLine('# Günlük kaydedildi: ' + savedLog.getAbsolutePath())
            if (!r.ok) { javafx.application.Platform.runLater { errorTextRef.set('Çıkarım başarısız (çıkış: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '') + '\n\n⚠ Eski fastai ortamı modern torch/GPU ile kırılabilir — FCOS önerilir.' + (savedLog != null ? ('\n\nÇalışma günlüğü: ' + savedLog.getAbsolutePath()) : '')); step.set('ERROR'); render() }; return }
            def geo = outGeo
            try { def m = (r.lastLines ?: '') =~ /RESULT geojson=(.+)/; if (m.find()) { def gp = new File(m.group(1).trim()); if (gp.isFile()) geo = gp } } catch (Throwable ignore) {}
            javafx.application.Platform.runLater { busyLabelRef.set('Sonuçlar içe aktarılıyor…'); step.set('BUSY'); render() }
            def imp = importMitoses(geo, imageData, regionRois)
            if (!imp.ok) { javafx.application.Platform.runLater { errorTextRef.set(imp.error); step.set('ERROR'); render() }; return }
            def dens = writeCounts(imageData, targets, imp.coords, cal)
            mitosisCoordsRef.set(new ArrayList(imp.coords)); navIdxRef.set(-1)
            javafx.application.Platform.runLater {
                try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
                resultTextRef.set(resultText(imageData, cfg, imp, dens, cal)); step.set('RESULT'); render()
                if (!imp.coords.isEmpty()) goToMitosis(0)
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Beklenmeyen hata:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
        }
    }, 'AtolyeRetina-Run')
    worker.setDaemon(true); worker.start()
}

render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def imageData = QP.getCurrentImageData()
    def cfg = loadConfig()

    def title = new javafx.scene.control.Label(); title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10); center.setPadding(new javafx.geometry.Insets(14)); center.getChildren().add(title)
    def actions = new ArrayList()

    def wrapBind = { javafx.scene.control.Label lbl -> lbl.setWrapText(true); lbl.sceneProperty().addListener({ obs, o, sc -> if (sc != null) { try { lbl.maxWidthProperty().unbind() } catch (Throwable ig) {}; lbl.maxWidthProperty().bind(sc.widthProperty().subtract(38)) } } as javafx.beans.value.ChangeListener) }
    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); wrapBind(lbl); center.getChildren().add(lbl) }
    def addMonoArea = { String txt -> def ta = new javafx.scene.control.TextArea(txt ?: ''); ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO); javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(ta) }
    def addWarnLabel = { String txt -> def lbl = new javafx.scene.control.Label(txt); wrapBind(lbl); lbl.setStyle('-fx-text-fill: #b8860b; -fx-font-weight: bold;'); center.getChildren().add(lbl) }
    def addLiveLog = { -> def la = logAreaRef.get(); if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) } }

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('Mitoz tespiti (RetinaNet) — çalışma zamanı gerekli')
        def miss = configMissing(cfg)
        addGuidance('Bu modül ESKİ fastai 1.0.61 ortamını (env id: midog-retinanet-legacy, CPU) gerektirir.\nEksik/geçersiz:\n  • ' + (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')) +
            '\n\nKurulum: Extensions → Atölye → Yardımcılar → Python köprüleri → Atölye Python ortam yöneticisi → "MIDOG DA-RetinaNet (eski, CPU)".\n' +
            'Köprü betiği: handson/python/midog/retinanet_runner.py\n\n⚠ Bu eski model FCOS ile AŞILMIŞTIR; modern GPU\'lu tespit için MIDOG25 FCOS sihirbazını kullanın.')
        actions.add(navButton('Kapat', { stage.close() })); actions.add(navButton('⚙ Python ortamını kur/aç', { launchBundledScript('yardimci-python-ortam-yoneticisi.groovy') }, 'Atölye Python ortam yöneticisini açar → "MIDOG DA-RetinaNet"i kurun')); actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText('Mitoz tespiti (RetinaNet) — yapılandırma')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def rnField = new javafx.scene.control.TextField(cfg.runner ?: '')
        def wdField = new javafx.scene.control.TextField(cfg.workDir ?: '')
        def tmppField = new javafx.scene.control.TextField(cfg.targetMpp ?: ''); tmppField.setPromptText('boş = 0.5')
        def batchField = new javafx.scene.control.TextField(cfg.batchSize ?: '8')
        def threshField = new javafx.scene.control.TextField(cfg.threshold ?: ''); threshField.setPromptText('boş = 0.64')
        [pyField, rnField, wdField].each { it.setPrefColumnCount(36) }
        [tmppField, batchField, threshField].each { it.setPrefColumnCount(8) }
        def deviceChoice = new javafx.scene.control.ChoiceBox(); ['cuda', 'cpu'].each { deviceChoice.getItems().add(it) }; deviceChoice.setValue((cfg.device == 'cpu') ? 'cpu' : 'cuda')
        pyFieldRef.set(pyField); runnerFieldRef.set(rnField); workFieldRef.set(wdField); deviceChoiceRef.set(deviceChoice); tmppFieldRef.set(tmppField); batchFieldRef.set(batchField); threshFieldRef.set(threshField)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Dosya seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir  = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Dizin seç', null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Model:'), new javafx.scene.control.Label(MODEL + ' (sabit, eski)'))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (.venv/python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Köprü (retinanet_runner.py):'), rnField, navButton('…', { browseFile(rnField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Çalışma dizini (ops.):'), wdField, navButton('…', { browseDir(wdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Cihaz:'), deviceChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Hedef çözünürlük (µm/px):'), tmppField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Batch size:'), batchField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Duyarlılık eşiği (0–1):'), threshField)
        center.getChildren().add(grid)
        def mcf = modelCacheFile()
        def mcLbl = new javafx.scene.control.Label(mcf.isFile() ? ('✓ Yerel model VAR: ' + mcf.getAbsolutePath()) : '○ Yerel model yok — "Modeli yerel indir" ile depo + ağırlığı bir kez indirin.')
        mcLbl.setWrapText(true); mcLbl.setMaxWidth(Double.MAX_VALUE); mcLbl.setStyle('-fx-opacity: 0.85; -fx-font-size: 11px;'); center.getChildren().add(mcLbl)
        addWarnLabel('⚠ ESKİ/DOĞRULANMAMIŞ ORTAM: fastai 1.0.61 + eski torch stack. Modern torch/GPU ile uyumsuzluk olası (ortam import bile edemeyebilir); çözülürse GPU kullanabilir ama kırılgan/yavaştır. Aşılmış model — MIDOG25 FCOS önerilir.')
        addGuidance('Model: 2021/22 MIDOG referans DA-RetinaNet, sabit. "Modeli yerel indir" referans deposunun KODUNU + ağırlığını v1 yayınından çeker (LİSANS yok → araştırma/eğitim). Hedef çözünürlük: ROI bu µm/px\'e örneklenir (varsayılan 0.5). Duyarlılık eşiği: referans 0.64.')
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('⚙ Python ortamı', { launchBundledScript('yardimci-python-ortam-yoneticisi.groovy') }, 'Atölye Python ortam yöneticisini aç'))
        actions.add(navButton('Modeli yerel indir', { startModelDownload() }, 'Referans depo + RetinaNetDA.pth ağırlığını bir kez indir'))
        actions.add(navButton('Bağımlılık kontrolü', { startSelftest() }, 'retinanet_runner.py selftest'))
        actions.add(navButton('Kaydet ▶', { persistFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
    } else if (cur == 'CHECK_RUNNING') {
        title.setText('Bağımlılık kontrolü çalışıyor…'); center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'CHECK_DONE') {
        title.setText(selftestOkRef.get() ? 'Bağımlılık kontrolü tamam ✅' : '⚠ Bağımlılık kontrolü BAŞARISIZ — eski fastai/torch ortamı kurulamadı (FCOS önerilir)'); addLiveLog()
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
    } else if (cur == 'DL_RUNNING') {
        title.setText('Model + kod indiriliyor…'); addGuidance('Referans depo (kod + RetinaNetDA.pth ~74 MB) veri kökü altına indiriliyor.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'DL_DONE') {
        title.setText(dlOkRef.get() ? 'Model + kod indirildi ✅' : '⚠ İndirilemedi — günlüğe bakın'); addLiveLog()
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText('Görüntü açık değil'); addGuidance('Önce bir H&E slaydı açın, ilgi ALANINI çizip seçin, sonra "⟳ Yenile".')
            actions.add(navButton('Kapat', { stage.close() })); actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() })); actions.add(navButton('⟳ Yenile', { render() }))
        } else {
            def targets = regionAnnotationsOf(imageData); def ci = calibrationInfo(imageData); def cal = ci.cal
            double targetMpp = cfg.targetMpp?.trim() ? parseDoubleOr(cfg.targetMpp, TARGET_MPP) : TARGET_MPP
            def typeName = (imageData.getImageType()?.name() ?: '').toUpperCase(java.util.Locale.ROOT); boolean isHE = typeName.contains('BRIGHTFIELD_H_E')
            title.setText('Mitoz tespiti — DA-RetinaNet (bölgede, eski)')
            def sb = new StringBuilder()
            sb << "Slayt          : " << imageNameOf(imageData) << "\n"
            sb << "Model          : " << MODEL << "\n"
            sb << "Python         : " << (cfg.python ?: '(ayarsız)') << "\n"
            sb << "Cihaz          : " << (cfg.device ?: 'cuda') << " (eski stack; yoksa CPU)\n"
            sb << String.format(java.util.Locale.US, "Hedef çözünürlük: %.3f µm/px%n", targetMpp)
            sb << "Duyarlılık eşiği: " << (cfg.threshold?.trim() ? cfg.threshold.trim() : '0.64 (referans)') << "\n"
            sb << "Yerel model    : " << (modelCacheFile().isFile() ? 'VAR' : 'yok — "Modeli yerel indir"') << "\n"
            sb << String.format(java.util.Locale.US, "Seçili bölge   : %,d alan anotasyonu%n", targets.size())
            if (cal != null) { sb << String.format(java.util.Locale.US, "Piksel boyutu  : %.4f × %.4f µm/px%s%n", cal.pw, cal.ph, (Double.isFinite(ci.mag) && ci.mag > 0 ? String.format(java.util.Locale.US, '   (~%.0f×)', ci.mag) : '')); sb << String.format(java.util.Locale.US, "Yeniden örnek. : %s%n", resampleNote(ci.mpp, targetMpp)) }
            else sb << "Piksel boyutu  : KALİBRE DEĞİL — yoğunluk hesaplanmaz\n"
            addMonoArea(sb.toString())
            addWarnLabel('⚠ ESKİ/DOĞRULANMAMIŞ ortam (kırılgan, yavaş olabilir, aşılmış). Modern GPU\'lu tespit için MIDOG25 FCOS kullanın.')
            addGuidance('RetinaNet YALNIZ seçili/çizili alan içinde çalışır. Sonuç: "Mitosis" mor nokta-anotasyonları + sayım + yoğunluk (mitoz/mm²).')
            if (!isHE) addWarnLabel('⚠ Görüntü tipi H&E değil (' + typeName + ').')
            if (ci.warn != null) addWarnLabel('⚠ Kalibrasyon: ' + ci.warn)
            boolean canRun = configComplete(cfg) && targets.size() >= 1
            if (!configComplete(cfg)) addWarnLabel('⚠ Python ortamı (midog-retinanet-legacy) kurulu değil — "⚙ Python ortamını kur/aç" ile kurun.')
            actions.add(navButton('Kapat', { stage.close() }))
            if (!configComplete(cfg)) actions.add(navButton('⚙ Python ortamını kur/aç', { launchBundledScript('yardimci-python-ortam-yoneticisi.groovy') }, 'Atölye Python ortam yöneticisini açar'))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() })); actions.add(navButton('⟳ Yenile', { render() }))
            def runBtn = navButton('Bölgede çalıştır ▶', { startRun() }, 'RetinaNet mitoz dedektörünü seçili bölgede çalıştırır (CPU, yavaş)'); runBtn.setDisable(!canRun)
            if (!canRun && targets.size() < 1) addWarnLabel('⚠ Önce en az 1 alan anotasyonu çizin/seçin.')
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING') {
        title.setText(runPhaseRef.get()); addGuidance('RetinaNet köprüsü koşuyor (eski stack; yavaş olabilir). Zaman aşımı: ' + PYTHON_TIMEOUT_SECONDS + ' sn.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
        actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
    } else if (cur == 'BUSY') {
        title.setText(busyLabelRef.get()); addGuidance('Lütfen bekleyin…'); center.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅'); addMonoArea(resultTextRef.get())
        int nMit = (mitosisCoordsRef.get()?.size() ?: 0)
        if (nMit > 0) addGuidance('Tespitler "' + MITOSIS_CLASS + '" sınıflı MOR nokta-anotasyonları olarak eklendi ("' + MITOSIS_PREFIX + ' #…"). "Mitoza git" ile üstüne gidin.')
        def lf = logFileRef.get(); if (lf != null) addGuidance('Çalışma günlüğü otomatik kaydedildi: ' + lf.getAbsolutePath())
        actions.add(navButton('Kapat', { stage.close() }))
        if (nMit > 0) actions.add(navButton('◀ Önceki', { goToMitosis(navIdxRef.get() - 1); render() }))
        if (nMit > 0) actions.add(navButton('Mitoza git ' + (navIdxRef.get() >= 0 ? ((navIdxRef.get() + 1) + '/' + nMit) : ('1/' + nMit)) + ' ▶', { goToMitosis(navIdxRef.get() + 1); render() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
        actions.add(navButton('↻ Yeniden çalıştır', { step.set('READY'); render() }))
    } else {
        title.setText('Hata'); addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Geri', { step.set('READY'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
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
    stage.setScene(new javafx.scene.Scene(root, 900, 660))
}

step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage(); stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Mitoz tespiti — DA-RetinaNet (MIDOG 2021/22, eski)'); stage.setAlwaysOnTop(alwaysTop.get())
        render(); stage.show()
    } catch (Throwable t) { Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')) }
}
println "✓ Mitoz tespiti (DA-RetinaNet · MIDOG 2021/22) sihirbazı açıldı."
