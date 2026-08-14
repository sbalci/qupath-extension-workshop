/**
 * Modül - Mitoz modelleri karşılaştırma (aynı bölge, çoklu dedektör)
 * -----------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   QuPath'te ÇİZDİĞİNİZ alan anotasyonu içinde SEÇİLEN mitoz DEDEKTÖRLERİNİ (KongNet
 *   MIDOG ve/veya MIDOG25 FCOS) — YALNIZ orada — sırayla çalıştırır ve tespitlerini
 *   KARŞILAŞTIRIR. Her modelin mitozları AYRI renk/sınıfta nokta-anotasyonu olarak eklenir
 *   (Mitoz — KongNet = kırmızı, Mitoz — FCOS = mavi). Sonra modeller-arası UZLAŞI hesaplanır:
 *   birbirine R µm'den yakın noktalar EŞLEŞMİŞ sayılır (tek-bağ kümeleme) → model-başı sayım,
 *   ikili uyum oranı ve KONSENSÜS kümesi (≥K modelde bulunan mitozlar).
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Modeller-ARASI UYUM (aynı yeri kaç model buldu). Bu bir DOĞRULUK ölçüsü DEĞİLDİR —
 *     yer-gerçeği (ground truth) yok; yalnızca modellerin birbiriyle ne kadar örtüştüğü.
 *   • Klinik kategori/derece/yorum üretilmez. Konsensüs = "yüksek-güvenli" değil, "modeller
 *     hemfikir" demektir; tahminleri görsel doğrulayın.
 *
 * ÇALIŞMA ZAMANI:
 *   Her dedektör kendi ortamını (env) + köprüsünü kullanır. Kurulu OLMAYAN dedektör gri
 *   görünür. Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri → Atölye Python
 *   ortam yöneticisi ("TIA Toolbox — bölge modelleri" ve "MIDOG25 FCOS — mitoz dedektörü").
 *
 * KULLANIM:
 *   1. H&E slaydını açın; piksel boyutu (µm/px) kalibre olsun.
 *   2. İlgi ALANINI anotasyon olarak çizin ve SEÇİN.
 *   3. Karşılaştırılacak modelleri işaretleyin → "Karşılaştır".
 *
 * ÇIKTI:
 *   • Model-başına "Mitoz — <model>" renkli nokta-anotasyonları
 *   • Konsensüs noktaları "Mitoz (konsensüs)" (≥K model)
 *   • Kilitli "Mitoz — model karşılaştırması" özet anotasyonu (model sayıları + uyum matrisi)
 *
 * API: RegionRequest/ImageServer.readRegion (ROI), ROIs.createPointsROI +
 *      PathObjects.createAnnotationObject; GeoJSON com.google.gson.JsonParser.
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
def SUMMARY_NAME    = 'Mitoz — model karşılaştırması'
def CONSENSUS_CLASS = 'Mitoz (konsensüs)'
double TARGET_MPP   = 0.5        // FCOS için ROI yeniden örnekleme hedefi
double DEFAULT_R_UM = 10.0       // eşleşme yarıçapı (µm) ≈ bir çekirdek çapı
double WHO_AREA_MM2 = 2.0
double AREA_TOL_MM2 = 0.01
int    ROI_WARN_PX = 12000

// ── Dedektör tanımları (per-detector descriptor) ─────────────────────────────
// Her dedektörün KENDİ ortamı/köprüsü/girdi-türü/CLI'si var; ortak sözleşme YOK —
// bu yüzden buildCmd her modelin argümanlarını kendi şablonundan kurar. inputKind:
// 'mask' = tüm slayt ikili maske (region_runner) · 'roi' = hedef-mpp ROI görüntüsü (fcos_runner).
def DETECTORS = [
    [ id:'kongnet', label:'KongNet MIDOG (TIA Toolbox)', envId:'tiatoolbox-region',
      runnerRel:'python/tiatoolbox/region_runner.py', inputKind:'mask',
      cls:'Mitoz — KongNet', color:[255, 40, 40],
      buildCmd:{ py, runner, art, outGeo, device, thr ->
          def c = [py, runner, 'detect', '--wsi', art.wsi, '--mask', art.mask,
                   '--model', 'KongNet_Det_MIDOG_1', '--engine', 'nucleus_detector',
                   '--out', outGeo, '--save-dir', art.saveDir, '--device', device,
                   '--batch-size', '8', '--classes', 'Mitotic figure']
          if (thr != null) { c.add('--threshold'); c.add(String.format(java.util.Locale.US, '%.4f', thr)) }
          return c } ],
    [ id:'fcos', label:'MIDOG25 FCOS (torchvision)', envId:'midog-fcos',
      runnerRel:'python/midog/fcos_runner.py', inputKind:'roi',
      cls:'Mitoz — FCOS', color:[40, 120, 255],
      buildCmd:{ py, runner, art, outGeo, device, thr ->
          def c = [py, runner, 'detect', '--roi', art.roi, '--out', outGeo,
                   '--origin', art.origin, '--downsample', art.downsample,
                   '--device', device, '--batch-size', '8']
          if (thr != null) { c.add('--det-thresh'); c.add(String.format(java.util.Locale.US, '%.4f', thr)) }
          return c } ],
    [ id:'retinanet', label:'MIDOG DA-RetinaNet (2021, eski)', envId:'midog-retinanet-legacy',
      runnerRel:'python/midog/retinanet_runner.py', inputKind:'roi',
      cls:'Mitoz — RetinaNet', color:[170, 70, 200],
      buildCmd:{ py, runner, art, outGeo, device, thr ->
          def c = [py, runner, 'detect', '--roi', art.roi, '--out', outGeo,
                   '--origin', art.origin, '--downsample', art.downsample,
                   '--device', device, '--batch-size', '8']
          if (thr != null) { c.add('--det-thresh'); c.add(String.format(java.util.Locale.US, '%.4f', thr)) }
          return c } ],
]

// ── Kalıcı yapılandırma ──────────────────────────────────────────────────────
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/mitoz-karsilastir')
def commonPrefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common')
def PREF_DEVICE = 'device'
def PREF_RADIUS = 'radiusUm'
def PREF_THRESH = 'threshold'       // ortak duyarlılık (boş = her modelin varsayılanı)
def PREF_SEL    = 'selected'        // virgülle ayrılmış seçili dedektör id'leri

def atolyeDataRoot = { ->
    def p = ''
    try { p = commonPrefs.get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
def applyCacheEnv = { pb ->
    try {
        def cache = new File(atolyeDataRoot(), 'cache'); cache.mkdirs()
        def env = pb.environment()
        env.put('HF_HOME', new File(cache, 'huggingface').getAbsolutePath())
        env.put('TORCH_HOME', new File(cache, 'torch').getAbsolutePath())
        env.put('TIATOOLBOX_HOME', new File(cache, 'tiatoolbox').getAbsolutePath())
    } catch (Throwable ignore) {}
}

// Bir dedektörün python + köprü yolunu çöz (env yöneticisinin kaydettiği py.<id> önce).
def detectPythonFor = { String envId ->
    try { def rec = commonPrefs.get('py.' + envId, ''); if (rec?.trim() && new File(rec.trim()).isFile()) return rec.trim() } catch (Throwable ignore) {}
    // TIA Toolbox resmî eklenti ortamı yedeği (yalnız tiatoolbox-region için anlamlı)
    if (envId == 'tiatoolbox-region') {
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
    }
    def at = new File(new File(atolyeDataRoot(), 'runtimes'), envId + '/.venv')
    def aw = new File(at, 'Scripts/python.exe'); def an = new File(at, 'bin/python')
    if (aw.isFile()) return aw.getAbsolutePath()
    if (an.isFile()) return an.getAbsolutePath()
    return ''
}
def detectRunnerFor = { String runnerRel ->
    def project = QP.getProject()
    def roots = []
    if (project != null && project.getPath() != null) {
        def handson = project.getPath().getParent().toFile()
        roots << handson
        if (handson.getParentFile() != null) roots << new File(handson.getParentFile(), 'handson')
    }
    for (r in roots) { def f = new File(r, runnerRel); if (f.isFile()) return f.getAbsolutePath() }
    return ''
}
// Bir dedektör kurulu mu? (python + köprü var)
def detectorReady = { d -> def py = detectPythonFor(d.envId); def rn = detectRunnerFor(d.runnerRel); return (py && rn) }

def parseIntOr = { s, int dfl -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return dfl } }
def parseDoubleOr = { s, double dfl -> try { return Double.parseDouble((s ?: '').toString().trim().replace(',', '.')) } catch (Throwable t) { return dfl } }

def imageNameOf = { imageData -> (imageData.getServer().getMetadata().getName() ?: 'slide').replaceAll(/\.[^.\/\\]+$/, '') }
def wsiPathOf = { imageData ->
    try {
        def uris = imageData.getServer().getURIs()
        if (uris != null && !uris.isEmpty()) { def uri = uris.iterator().next(); if ('file'.equals(uri.getScheme())) return new File(uri).getAbsolutePath() }
    } catch (Throwable ignore) {}
    return null
}
def pixelMicrons = { imageData ->
    try { def cal = imageData.getServer().getPixelCalibration(); if (cal != null && cal.hasPixelSizeMicrons()) return [pw: cal.getPixelWidthMicrons(), ph: cal.getPixelHeightMicrons()] } catch (Throwable ignore) {}
    return null
}
def resolveWorkDir = { imageData ->
    def project = QP.getProject()
    if (project != null && project.getPath() != null) return new File(project.getPath().getParent().toFile(), 'tiatoolbox_work')
    return new File(System.getProperty('java.io.tmpdir'), 'tiatoolbox_work')
}

def notSummary = { ann -> ann.getName() == null || (!ann.getName().startsWith(SUMMARY_NAME)) }
def regionAnnotationsOf = { imageData ->
    def h = imageData.getHierarchy()
    def sel = h.getSelectionModel().getSelectedObjects().findAll { it.isAnnotation() && it.hasROI() && it.getROI().isArea() && notSummary(it) }
    if (!sel.isEmpty()) return new ArrayList(sel)
    return new ArrayList(h.getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() && notSummary(it) })
}

// ── Girdi üretimi: tüm slayt maskesi (KongNet) + hedef-mpp ROI görüntüsü (FCOS) ──
def exportRegionMask = { imageData, File workDir, double downsample, List regionRois, Closure appendLine ->
    def server = imageData.getServer()
    int W = server.getWidth(), H = server.getHeight()
    int mw = (int) Math.max(1, Math.ceil(W / downsample)); int mh = (int) Math.max(1, Math.ceil(H / downsample))
    def img = new java.awt.image.BufferedImage(mw, mh, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
    def g = img.createGraphics()
    try {
        g.setColor(java.awt.Color.BLACK); g.fillRect(0, 0, mw, mh); g.setColor(java.awt.Color.WHITE)
        def at = java.awt.geom.AffineTransform.getScaleInstance(1.0d / downsample, 1.0d / downsample)
        regionRois.each { roi -> def s = roi.getShape(); if (s != null) g.fill(at.createTransformedShape(s)) }
    } finally { g.dispose() }
    def f = new File(workDir, 'karsilastir_mask.png'); if (f.getParentFile() != null) f.getParentFile().mkdirs()
    javax.imageio.ImageIO.write(img, 'PNG', f)
    appendLine('Bölge maskesi: ' + f.getName() + ' (' + mw + ' × ' + mh + ' px)')
    return [ok: true, file: f]
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
        appendLine(String.format(java.util.Locale.US, '⚠ Büyük ROI (%d × %d px) — bellek yoğun. Daha küçük bir sıcak-nokta seçmeyi düşünün.', outW, outH))
    def request = qupath.lib.regions.RegionRequest.createInstance(server.getPath(), downsample, x, y, w, h)
    def img = server.readRegion(request)
    if (img == null) return [ok: false, error: 'Bölge okunamadı (readRegion null döndü).']
    def rgb = new java.awt.image.BufferedImage(img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB)
    def g = rgb.createGraphics()
    try { g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight()); g.drawImage(img, 0, 0, null) } finally { g.dispose() }
    def f = new File(workDir, 'karsilastir_roi.png'); if (f.getParentFile() != null) f.getParentFile().mkdirs()
    javax.imageio.ImageIO.write(rgb, 'PNG', f)
    appendLine(String.format(java.util.Locale.US, 'ROI görüntüsü: %s (%d × %d px, downsample %.3f, köken %d,%d)', f.getName(), rgb.getWidth(), rgb.getHeight(), downsample, x, y))
    return [ok: true, file: f, originX: x, originY: y, downsample: downsample]
}

// ── GeoJSON'dan bölge-içi nokta koordinatlarını oku (anotasyon EKLEMEZ) ──
def readPoints = { File geojson, List regionRois ->
    def out = new ArrayList()
    if (geojson == null || !geojson.isFile()) return out
    def root
    try { root = JsonParser.parseString(geojson.getText('UTF-8')).getAsJsonObject() } catch (Throwable t) { return out }
    def feats = root.has('features') ? root.getAsJsonArray('features') : null
    if (feats == null) return out
    for (el in feats) {
        def ft = el.getAsJsonObject()
        if (!ft.has('geometry') || ft.get('geometry').isJsonNull()) continue
        def geom = ft.getAsJsonObject('geometry')
        if (!geom.has('type') || geom.get('type').getAsString() != 'Point') continue
        def c = geom.getAsJsonArray('coordinates')
        double x = c.get(0).getAsDouble(), y = c.get(1).getAsDouble()
        if (!regionRois.any { it.contains(x, y) }) continue
        out.add([x, y])
    }
    return out
}
// Bir modelin noktalarını renkli nokta-anotasyonu olarak ekle (önceki aynı-sınıfı bölgede sil).
def importModelPoints = { imageData, List regionRois, String cls, List rgbColor, List coords ->
    def hier = imageData.getHierarchy()
    def plane = ImagePlane.getDefaultPlane()
    def pc = QP.getPathClass(cls)
    try { pc.setColor(qupath.lib.common.ColorTools.packRGB(rgbColor[0] as int, rgbColor[1] as int, rgbColor[2] as int)) } catch (Throwable ignore) {}
    hier.removeObjects(hier.getAnnotationObjects().findAll { a ->
        a.getPathClass() == pc && a.hasROI() && a.getROI().isPoint() &&
        regionRois.any { r -> r.contains(a.getROI().getCentroidX(), a.getROI().getCentroidY()) }
    }, false)
    def anns = []
    coords.eachWithIndex { pt, i ->
        def ann = PathObjects.createAnnotationObject(ROIs.createPointsROI(pt[0] as double, pt[1] as double, plane), pc)
        ann.setName(cls + ' #' + (i + 1)); anns << ann
    }
    if (!anns.isEmpty()) hier.addObjects(anns)
    hier.fireHierarchyChangedEvent(hier)
}

// ── Uzlaşı: tek-bağ kümeleme (R piksel) → küme başına model kümesi ──
// perModel: [modelIdx -> List<[x,y]>]. Döner: kümeler [[modelSet:Set, cx, cy], ...]
def clusterAgreement = { Map perModel, double rPx ->
    def pts = new ArrayList()   // [x, y, modelIdx]
    perModel.each { mi, list -> list.each { p -> pts.add([p[0] as double, p[1] as double, mi as int]) } }
    int n = pts.size()
    int[] parent = new int[n]
    for (int i = 0; i < n; i++) parent[i] = i
    def find; find = { int a -> while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
    def union = { int a, int b -> int ra = find(a), rb = find(b); if (ra != rb) parent[ra] = rb }
    double r2 = rPx * rPx
    for (int i = 0; i < n; i++) {
        for (int j = i + 1; j < n; j++) {
            double dx = (pts[i][0] as double) - (pts[j][0] as double)
            double dy = (pts[i][1] as double) - (pts[j][1] as double)
            if (dx * dx + dy * dy <= r2) union(i, j)
        }
    }
    def groups = [:]   // root -> list of indices
    for (int i = 0; i < n; i++) { int r = find(i); groups.get(r, []).add(i) }
    def clusters = []
    groups.each { root, idxs ->
        def modelSet = new HashSet()
        double sx = 0, sy = 0
        idxs.each { k -> modelSet.add(pts[k][2] as int); sx += (pts[k][0] as double); sy += (pts[k][1] as double) }
        clusters.add([modelSet: modelSet, cx: sx / idxs.size(), cy: sy / idxs.size()])
    }
    return clusters
}

// ── Headless ────────────────────────────────────────────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    println "Mitoz karşılaştırma sihirbazı."
    DETECTORS.each { d -> println "  ${d.label}: ${detectorReady(d) ? 'KURULU' : 'kurulu değil'}" }
    if (imageData != null) println "Alan anotasyonu: ${regionAnnotationsOf(imageData).size()}"
    println "Bu sihirbaz QuPath arayüzü gerektirir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def processRef    = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef   = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def selChkRefs    = new java.util.concurrent.atomic.AtomicReference([:])   // id -> CheckBox
def deviceChoiceRef = new java.util.concurrent.atomic.AtomicReference(null)
def radiusFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def threshFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def render

def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text); b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip)); return b
}
def busyBar = { -> def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb }
def copyToClipboard = { String txt ->
    def cb = javafx.scene.input.Clipboard.getSystemClipboard(); def content = new javafx.scene.input.ClipboardContent(); content.putString(txt ?: ""); cb.setContent(content)
}
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

// ── Çalıştırma günlüğü ──
def runLog = new StringBuilder()
def logFileRef = new java.util.concurrent.atomic.AtomicReference(null)
def resetLog = { -> synchronized (runLog) { runLog.setLength(0) } }
def appendLog = { String ln -> synchronized (runLog) { runLog.append(ln == null ? '' : ln).append('\n') } }
def logSnapshot = { -> synchronized (runLog) { return runLog.toString() } }
def autoSaveLog = { File dir, String base ->
    try { if (dir == null) return null; dir.mkdirs(); def f = new File(dir, (base ?: 'mitoz-karsilastir') + '_run.log'); f.setText(logSnapshot(), 'UTF-8'); logFileRef.set(f); return f } catch (Throwable t) { return null }
}
def saveLogInteractive = {
    def txt = logSnapshot(); if (!txt?.trim()) { Dialogs.showInfoNotification('Günlük', 'Kaydedilecek günlük yok.'); return }
    try {
        def suggested = logFileRef.get() ?: new File(System.getProperty('user.home'), 'mitoz-karsilastir_run.log')
        def f = qupath.fx.dialogs.FileChoosers.promptToSaveFile(stage, 'Çalışma günlüğünü kaydet', suggested, new javafx.stage.FileChooser.ExtensionFilter('Günlük (*.log, *.txt)', '*.log', '*.txt'))
        if (f != null) { f.setText(txt, 'UTF-8'); Dialogs.showInfoNotification('Günlük', 'Kaydedildi: ' + f.getAbsolutePath()) }
    } catch (Throwable t) { Dialogs.showErrorMessage('Günlük', 'Kaydedilemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName())) }
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

def persistFields = {
    def dv = deviceChoiceRef.get(); prefs.put(PREF_DEVICE, (dv != null && dv.getValue() != null) ? dv.getValue() : 'cuda')
    def rf = radiusFieldRef.get(); prefs.put(PREF_RADIUS, (rf != null ? rf.getText() : '').trim())
    def tf = threshFieldRef.get(); prefs.put(PREF_THRESH, (tf != null ? tf.getText() : '').trim())
    def chks = selChkRefs.get(); def sel = []; chks.each { id, cb -> if (cb.isSelected()) sel.add(id) }
    prefs.put(PREF_SEL, sel.join(','))
    try { prefs.flush() } catch (Throwable ignore) {}
}

// ── Karşılaştırma akışı ──────────────────────────────────────────────────────
def startRun = { List chosen, String device, double radiusUm, Double thr ->
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def wsi = wsiPathOf(imageData)
    def targets = regionAnnotationsOf(imageData)
    if (targets.isEmpty()) { errorTextRef.set('Bölge yok.\nÖnce bir alan anotasyonu çizin/seçin.'); step.set('ERROR'); render(); return }
    def regionRois = targets.collect { it.getROI() }
    def cal = pixelMicrons(imageData)
    def workDir = resolveWorkDir(imageData); workDir.mkdirs()
    def base = imageNameOf(imageData)
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO); logAreaRef.set(la)
    runPhaseRef.set('Hazırlanıyor…'); step.set('RUN_RUNNING'); render()

    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def setPhase = { String ph -> javafx.application.Platform.runLater { runPhaseRef.set(ph); render() } }
        try {
            boolean needMask = chosen.any { it.inputKind == 'mask' }
            boolean needRoi  = chosen.any { it.inputKind == 'roi' }
            if (needMask && wsi == null) { javafx.application.Platform.runLater { errorTextRef.set('KongNet için slaytın yerel dosya yolu gerekli (OpenSlide ile açın) — ya da yalnız FCOS seçin.'); step.set('ERROR'); render() }; return }
            def art = [wsi: wsi, saveDir: new File(workDir, 'karsilastir_out_' + base).getAbsolutePath()]
            if (needMask) {
                setPhase('Bölge maskesi yazılıyor…')
                def em = exportRegionMask(imageData, workDir, 16.0d, regionRois, appendLine)
                if (!em.ok) { javafx.application.Platform.runLater { errorTextRef.set('Maske yazılamadı.'); step.set('ERROR'); render() }; return }
                art.mask = em.file.getAbsolutePath()
            }
            if (needRoi) {
                setPhase('ROI görüntüsü dışa aktarılıyor…')
                def ei = exportRegionImage(imageData, workDir, TARGET_MPP, regionRois, cal, ROI_WARN_PX, appendLine)
                if (!ei.ok) { javafx.application.Platform.runLater { errorTextRef.set(ei.error); step.set('ERROR'); render() }; return }
                art.roi = ei.file.getAbsolutePath(); art.origin = (ei.originX + ',' + ei.originY); art.downsample = String.format(java.util.Locale.US, '%.6f', (double) ei.downsample)
            }

            def perModel = [:]   // modelIdx -> coords
            def counts = [:]     // id -> count
            chosen.eachWithIndex { d, mi ->
                if (cancelledRef.get()) return
                setPhase('Çalışıyor: ' + d.label + '…')
                def py = detectPythonFor(d.envId); def rn = detectRunnerFor(d.runnerRel)
                def outGeo = new File(workDir, base + '_' + d.id + '_mitoz.geojson')
                def cmd = d.buildCmd(py, rn, art, outGeo.getAbsolutePath(), device, thr)
                appendLine('▶ ' + d.label)
                def r = runPython(cmd, appendLine)
                if (!r.ok) { appendLine('  ✗ ' + d.label + ' başarısız (çıkış ' + r.exitCode + ') — atlanıyor.'); counts[d.id] = -1; return }
                def geo = outGeo
                try { def m = (r.lastLines ?: '') =~ /RESULT geojson=(.+)/; if (m.find()) { def gp = new File(m.group(1).trim()); if (gp.isFile()) geo = gp } } catch (Throwable ignore) {}
                def coords = readPoints(geo, regionRois)
                perModel[mi] = coords; counts[d.id] = coords.size()
                appendLine('  ✓ ' + d.label + ': ' + coords.size() + ' mitoz')
            }
            if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }

            def savedLog = autoSaveLog(workDir, base)
            // Sonuçları içe al + uzlaşı hesapla (captured imageData'ya yaz — TOCTOU güvenli).
            javafx.application.Platform.runLater {
                try {
                    chosen.eachWithIndex { d, mi -> if (perModel.containsKey(mi)) importModelPoints(imageData, regionRois, d.cls, d.color, perModel[mi]) }
                    // R µm → piksel
                    double rPx = (cal != null) ? (radiusUm / ((cal.pw + cal.ph) / 2.0)) : (radiusUm / TARGET_MPP)
                    def clusters = clusterAgreement(perModel, rPx)
                    int K = perModel.size()   // konsensüs = tüm çalışan modeller
                    // özet + konsensüs noktaları
                    def hier = imageData.getHierarchy()
                    hier.removeObjects(hier.getAnnotationObjects().findAll { it.getName() != null && it.getName().startsWith(SUMMARY_NAME) }, false)
                    def consClass = QP.getPathClass(CONSENSUS_CLASS)
                    try { consClass.setColor(qupath.lib.common.ColorTools.packRGB(30, 170, 90)) } catch (Throwable ignore) {}
                    hier.removeObjects(hier.getAnnotationObjects().findAll { it.getPathClass() == consClass && it.hasROI() && it.getROI().isPoint() && regionRois.any { r -> r.contains(it.getROI().getCentroidX(), it.getROI().getCentroidY()) } }, false)
                    def plane = ImagePlane.getDefaultPlane()
                    int consensus = 0
                    def consAnns = []
                    clusters.each { c -> if (c.modelSet.size() >= K && K >= 2) { consensus++; def a = PathObjects.createAnnotationObject(ROIs.createPointsROI(c.cx as double, c.cy as double, plane), consClass); a.setName(CONSENSUS_CLASS + ' #' + consensus); consAnns << a } }
                    if (!consAnns.isEmpty()) hier.addObjects(consAnns)

                    def unionRoi = (targets.size() == 1) ? targets[0].getROI() : qupath.lib.roi.RoiTools.union(targets.collect { it.getROI() })
                    def summary = PathObjects.createAnnotationObject(unionRoi); summary.setName(SUMMARY_NAME)
                    counts.each { id, cnt -> if (cnt >= 0) summary.measurements['Mitoz — ' + id] = cnt as double }
                    summary.measurements['Konsensüs (≥' + K + ' model)'] = consensus as double
                    if (cal != null) { double areaMm2 = unionRoi.getArea() * cal.pw * cal.ph / 1_000_000.0; summary.measurements['Ölçülen alan (mm2)'] = areaMm2 }
                    summary.setLocked(true); hier.addObjects([summary]); hier.fireHierarchyChangedEvent(hier)

                    // ikili uyum matrisi (metin)
                    def runIdx = perModel.keySet().sort()
                    def sb = new StringBuilder()
                    sb << "MİTOZ MODEL KARŞILAŞTIRMASI\n═══════════════════════════\n\n"
                    sb << "Bölge   : " << (targets.size()) << " anotasyon\n"
                    sb << String.format(java.util.Locale.US, "Eşleşme yarıçapı: %.1f µm%n%n", radiusUm)
                    sb << "Model başı mitoz:\n"
                    chosen.eachWithIndex { d, mi -> def cnt = counts[d.id]; sb << "  • " << d.label << " : " << (cnt != null && cnt >= 0 ? ('' + cnt) : 'BAŞARISIZ') << "\n" }
                    sb << "\nKonsensüs (≥" << K << " model, aynı noktada): " << consensus << "\n"
                    if (runIdx.size() >= 2) {
                        sb << "\nİkili uyum (eşleşen / birleşik):\n"
                        for (int a = 0; a < runIdx.size(); a++) {
                            for (int b = a + 1; b < runIdx.size(); b++) {
                                int ia = runIdx[a], ib = runIdx[b]
                                int both = clusters.count { it.modelSet.contains(ia) && it.modelSet.contains(ib) } as int
                                int either = clusters.count { it.modelSet.contains(ia) || it.modelSet.contains(ib) } as int
                                double frac = either > 0 ? (100.0 * both / either) : 0.0
                                sb << "  • " << chosen[ia].label << " ↔ " << chosen[ib].label << " : " << String.format(java.util.Locale.US, "%d / %d (%.0f%%)%n", both, either, frac)
                            }
                        }
                    }
                    sb << "\nUYARI: Bu bir UYUM ölçüsüdür, DOĞRULUK değil (yer-gerçeği yok). Konsensüs\n= modeller hemfikir; görsel doğrulayın. Klinik yorum üretilmez.\n"
                    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
                    if (savedLog != null) sb << "\n\nÇalışma günlüğü: " << savedLog.getAbsolutePath()
                    resultTextRef.set(sb.toString())
                    try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
                    step.set('RESULT'); render()
                } catch (Throwable t) {
                    errorTextRef.set('Sonuç işleme hatası: ' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render()
                }
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Beklenmeyen hata:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
        }
    }, 'AtolyeMitozCmp-Run')
    worker.setDaemon(true); worker.start()
}

// ── Render ───────────────────────────────────────────────────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def imageData = QP.getCurrentImageData()

    def title = new javafx.scene.control.Label(); title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10); center.setPadding(new javafx.geometry.Insets(14)); center.getChildren().add(title)
    def actions = new ArrayList()

    def wrapBind = { javafx.scene.control.Label lbl ->
        lbl.setWrapText(true)
        lbl.sceneProperty().addListener({ obs, o, sc -> if (sc != null) { try { lbl.maxWidthProperty().unbind() } catch (Throwable ig) {}; lbl.maxWidthProperty().bind(sc.widthProperty().subtract(38)) } } as javafx.beans.value.ChangeListener)
    }
    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); wrapBind(lbl); center.getChildren().add(lbl) }
    def addMonoArea = { String txt -> def ta = new javafx.scene.control.TextArea(txt ?: ''); ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO); javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(ta) }
    def addWarnLabel = { String txt -> def lbl = new javafx.scene.control.Label(txt); wrapBind(lbl); lbl.setStyle('-fx-text-fill: #b8860b; -fx-font-weight: bold;'); center.getChildren().add(lbl) }
    def addLiveLog = { -> def la = logAreaRef.get(); if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) } }

    if (cur == 'READY') {
        title.setText('Mitoz modelleri karşılaştırma')
        if (imageData == null) {
            addGuidance('Önce bir H&E slaydı açın, ilgi ALANINI çizip seçin, sonra "⟳ Yenile".')
            actions.add(navButton('Kapat', { stage.close() })); actions.add(navButton('⟳ Yenile', { render() }))
        } else {
            def targets = regionAnnotationsOf(imageData); def cal = pixelMicrons(imageData)
            addGuidance('Aynı seçili bölgede birden çok mitoz DEDEKTÖRÜNÜ çalıştırıp tespitlerini karşılaştırır. Her model ayrı renkte eklenir; sonra modeller-arası uzlaşı (konsensüs) hesaplanır. Kurulu olmayan model gri görünür.')
            def prevSel = (prefs.get(PREF_SEL, '') ?: '').split(',').findAll { it?.trim() } as Set
            def chks = [:]
            def box = new javafx.scene.layout.VBox(4)
            DETECTORS.each { d ->
                boolean ready = detectorReady(d)
                def cb = new javafx.scene.control.CheckBox(d.label + (ready ? '' : '  (kurulu değil)'))
                cb.setDisable(!ready)
                cb.setSelected(ready && (prevSel.isEmpty() ? true : prevSel.contains(d.id)))
                chks[d.id] = cb; box.getChildren().add(cb)
            }
            selChkRefs.set(chks); center.getChildren().add(box)
            if (DETECTORS.any { !detectorReady(it) }) addWarnLabel('⚠ "kurulu değil" görünen model(ler) için "⚙ Python ortamı" ile ilgili ortamı kurun.')
            def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(6)
            def deviceChoice = new javafx.scene.control.ChoiceBox(); ['cuda', 'cpu'].each { deviceChoice.getItems().add(it) }; deviceChoice.setValue(prefs.get(PREF_DEVICE, 'cuda') == 'cpu' ? 'cpu' : 'cuda'); deviceChoiceRef.set(deviceChoice)
            def radiusField = new javafx.scene.control.TextField(prefs.get(PREF_RADIUS, '')); radiusField.setPromptText('boş = 10'); radiusField.setPrefColumnCount(6); radiusFieldRef.set(radiusField)
            def threshField = new javafx.scene.control.TextField(prefs.get(PREF_THRESH, '')); threshField.setPromptText('boş = her modelin varsayılanı'); threshField.setPrefColumnCount(10); threshFieldRef.set(threshField)
            int row = 0
            qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Cihaz:'), deviceChoice)
            qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Eşleşme yarıçapı (µm):'), radiusField)
            qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Ortak duyarlılık eşiği:'), threshField)
            center.getChildren().add(grid)
            addGuidance('Eşleşme yarıçapı: iki modelin noktaları bu mesafeden (µm) yakınsa "aynı mitoz" sayılır (varsayılan ~10 µm). Ortak duyarlılık: boşsa her model kendi varsayılan eşiğini kullanır (KongNet 0.99 çok tutucudur → adil karşılaştırma için ör. 0.5 girin, iki modele de uygulanır).')
            def infoLbl = new javafx.scene.control.Label(String.format(java.util.Locale.US, 'Seçili bölge: %d anotasyon · Kalibrasyon: %s', targets.size(), (cal != null ? String.format(java.util.Locale.US, '%.3f µm/px', (cal.pw + cal.ph) / 2.0) : 'KALİBRE DEĞİL')))
            infoLbl.setStyle('-fx-opacity: 0.85;'); center.getChildren().add(infoLbl)
            if (targets.size() < 1) addWarnLabel('⚠ Önce en az 1 alan anotasyonu çizin/seçin.')
            if (cal == null) addWarnLabel('⚠ Kalibrasyon yok — FCOS native çözünürlükte çalışır ve yarıçap piksel varsayımıyla hesaplanır.')
            actions.add(navButton('Kapat', { stage.close() }))
            if (DETECTORS.any { !detectorReady(it) }) actions.add(navButton('⚙ Python ortamı', { launchBundledScript('yardimci-python-ortam-yoneticisi.groovy') }, 'Atölye Python ortam yöneticisini aç (eksik modelleri kur)'))
            actions.add(navButton('⟳ Yenile', { render() }))
            def runBtn = navButton('Karşılaştır ▶', {
                persistFields()
                def chosen = DETECTORS.findAll { chks[it.id] != null && chks[it.id].isSelected() }
                if (chosen.size() < 1) { errorTextRef.set('En az bir model seçin.'); step.set('ERROR'); render(); return }
                double radiusUm = radiusField.getText()?.trim() ? parseDoubleOr(radiusField.getText(), DEFAULT_R_UM) : DEFAULT_R_UM
                Double thr = null
                if (threshField.getText()?.trim()) { double t = parseDoubleOr(threshField.getText(), -1.0d); if (t >= 0.0d && t <= 1.0d) thr = t }
                startRun(chosen, (deviceChoice.getValue() ?: 'cuda'), radiusUm, thr)
            }, 'Seçili modelleri aynı bölgede çalıştırıp karşılaştır')
            runBtn.setDisable(targets.size() < 1)
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING') {
        title.setText(runPhaseRef.get())
        addGuidance('Modeller sırayla koşuyor (her biri ilk çalıştırmada ağırlık indirmiş olmalı).')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
        actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
    } else if (cur == 'RESULT') {
        title.setText('Karşılaştırma tamam ✅'); addMonoArea(resultTextRef.get())
        addGuidance('Her model "Mitoz — <model>" renkli nokta-anotasyonlarında; ortak bulunanlar "' + CONSENSUS_CLASS + '" (yeşil). Annotations panelinden sınıfları aç/kapat ederek örtüşmeyi inceleyin.')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
        actions.add(navButton('↻ Yeniden', { step.set('READY'); render() }))
    } else { // ERROR
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
    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlı UYUM ölçüsü üretir; doğruluk/klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar); bottom.setPadding(new javafx.geometry.Insets(10))
    def root = new javafx.scene.layout.BorderPane(); root.setCenter(center); root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 900, 660))
}

// ── Açılış ────────────────────────────────────────────────────────────────────
step.set('READY')
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage(); stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Mitoz modelleri karşılaştırma'); stage.setAlwaysOnTop(alwaysTop.get())
        render(); stage.show()
    } catch (Throwable t) { Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')) }
}
println "✓ Mitoz modelleri karşılaştırma sihirbazı açıldı."
