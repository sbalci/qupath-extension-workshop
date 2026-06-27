/**
 * Yardımcı - TIA Toolbox bölgede çekirdek/mitoz tespiti (tek pencere köprü)
 * ------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Warwick TIA Centre'in **TIA Toolbox** model motorlarını (KongNet MIDOG mitoz,
 *   PanNuke / CoNIC / MONKEY / PUMA çekirdek, MapDe) bir WSI üzerinde ama YALNIZCA
 *   QuPath'te çizdiğiniz BÖLGE içinde çalıştırır. Bölge, ikili (binary) bir maske
 *   PNG'sine rasterlenir → Python köprüsü (region_runner.py) `engine.run(masks=[...],
 *   auto_get_mask=False)` ile yalnız maske bölgesinde çıkarım yapar → sonuç GeoJSON
 *   olarak QuPath'e geri alınır, bölgeye göre filtrelenip sınıf başına sayılır.
 *
 * NEDEN BU SİHİRBAZ:
 *   Resmî TIA Toolbox QuPath eklentisi (v0.5.0) çıkarımı bir BÖLGEYE kısıtlayamaz —
 *   yalnız "Current image" / "All project images". Bu sihirbaz, patoloğun beklediği
 *   "bir alan seç, modeli yalnız orada çalıştır" akışını sağlar (ör. 10 BBA sıcak-nokta
 *   içinde mitoz say). Boya-normalizasyonu sihirbazından (yardimci-tiatoolbox-sihirbaz)
 *   farkı: o PNG'leri işler ve torch GEREKMEZ; bu, WSI'ı doğrudan açan TIA Toolbox
 *   MODEL motorlarını çalıştırır (tiatoolbox + torch ortamı gerekir).
 *
 * ÇALIŞMA ZAMANI:
 *   Resmî eklentinin kurduğu ortam yeniden kullanılır:
 *     <kullanıcı>/QuPath/v<sürüm>/tiatoolbox-runtime/.venv/Scripts/python.exe
 *   Sihirbaz bunu otomatik bulur; bulamazsa "Gözat" ile seçin. Köprü betiği:
 *     handson/python/tiatoolbox/region_runner.py
 *
 * NE ÜRETİR (ve ne ÜRETMEZ):
 *   Bölge içi tespitleri sınıf başına nokta-anotasyonu + sayı olarak üretir. Hiçbir
 *   klinik eşik/alt-tip/grade/yorum üretmez. Tahminleri görsel doğrulayın (Ek W/Ek D).
 *
 * KULLANIM:
 *   1. Resmî TIA Toolbox eklentisini bir kez kurup çalışma zamanını yükleyin
 *      (Extensions → TIAToolbox → Install Python runtime…) — venv'i paylaşırız.
 *   2. Bir slayt açın (tercihen OpenSlide ile); ilgi BÖLGESİNİ çizip seçin.
 *   3. [Extensions → Atölye → Yardımcılar → TIA Toolbox bölgede çekirdek/mitoz tespiti]
 *   4. İlk açılışta python.exe + region_runner.py otomatik bulunur (gerekirse Gözat).
 *   5. Model seçip "Çalıştır" → bölge maskesi yazılır, çıkarım koşar, sonuç içe alınır.
 *
 * API: ROIs.createPointsROI + PathObjects.createAnnotationObject (QuPath 0.6.0+);
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
def SENTINEL_PREFIX = 'TIA bölge tespiti · '

// Atölye model kataloğu (TIA Toolbox nucleus_detector — patoloji nokta-tespiti).
def MODELS = [
    [name:'KongNet_Det_MIDOG_1', engine:'nucleus_detector', task:'Mitoz tespiti',
     classes:['Mitotic figure'], desc:'MIDOG mitotik figür dedektörü (H&E, ~0.25 µm/px).'],
    [name:'KongNet_PanNuke_1', engine:'nucleus_detector', task:'Çekirdek tespiti',
     classes:['Neoplastic','Inflammatory','Connective','Dead','Epithelial'],
     desc:'PanNuke 5-sınıf çekirdek (19 doku tipi, 0.25 µm/px).'],
    [name:'KongNet_CoNIC_1', engine:'nucleus_detector', task:'Çekirdek tespiti',
     classes:['Neutrophil','Epithelial','Lymphocyte','Plasma','Eosinophil','Connective'],
     desc:'CoNIC 6-sınıf kolorektal H&E (0.5 µm/px).'],
    [name:'KongNet_MONKEY_1', engine:'nucleus_detector', task:'İmmün hücre tespiti',
     classes:['Inflammatory','Lymphocyte','Monocyte'],
     desc:'MONKEY immün-hücre (PAS böbrek biyopsisi, 0.25 µm/px).'],
    [name:'KongNet_PUMA_T1_3', engine:'nucleus_detector', task:'Çekirdek (melanom)',
     classes:['Tumour cell','Lymphocyte','Other cell'],
     desc:'PUMA Track 1 melanom H&E 3-sınıf.'],
    [name:'KongNet_PUMA_T2_3', engine:'nucleus_detector', task:'Çekirdek (melanom)',
     classes:['Tumour cell','Lymphocyte','Plasma cell','Histiocyte','Melanophage','Neutrophil',
              'Stroma cell','Epithelial cell','Endothelial cell','Apoptotic cell'],
     desc:'PUMA Track 2 melanom H&E 10-sınıf.'],
    [name:'mapde-conic', engine:'nucleus_detector', task:'Çekirdek tespiti',
     classes:['Nucleus'], desc:'MapDe CoNIC tek-sınıf nokta dedektörü.'],
    [name:'mapde-crchisto', engine:'nucleus_detector', task:'Çekirdek tespiti',
     classes:['Nucleus'], desc:'MapDe CRCHisto tek-sınıf nokta dedektörü.'],
]
def MODEL_LABELS = MODELS.collect { it.name + '  —  ' + it.task }
def modelByLabel = { String lbl -> def i = MODEL_LABELS.indexOf(lbl); return (i >= 0) ? MODELS[i] : MODELS[0] }
def modelByName  = { String nm -> MODELS.find { it.name == nm } ?: MODELS[0] }

// ── Kalıcı yapılandırma: java.util.prefs ────────────────────────────────────
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/tiatoolbox-bolge')
def PREF_PYTHON = 'python'
def PREF_RUNNER = 'runner'
def PREF_WORK   = 'workDir'
def PREF_MODEL  = 'model'
def PREF_DEVICE = 'device'
def PREF_MDS    = 'maskDownsample'
def PREF_BATCH  = 'batchSize'

// ── Otomatik tespit: (1) resmî tiatoolbox-runtime, (2) atölye ortam yöneticisi venv ─
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
            if (!cands.isEmpty()) return cands.last().getAbsolutePath()   // en yeni v* sürümü
        }
    }
    // (2) Atölye ortam yöneticisi venv'i: <kullanıcı>/.atolye/runtimes/tiatoolbox-region/.venv
    def at = new File(System.getProperty('user.home'), '.atolye/runtimes/tiatoolbox-region/.venv')
    def aw = new File(at, 'Scripts/python.exe'); def an = new File(at, 'bin/python')
    if (aw.isFile()) return aw.getAbsolutePath()
    if (an.isFile()) return an.getAbsolutePath()
    return ''
}
def detectRunner = { ->
    def project = QP.getProject()
    def roots = []
    if (project != null && project.getPath() != null) {
        def handson = project.getPath().getParent().toFile()       // handson/
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
      model          : prefs.get(PREF_MODEL,  'KongNet_Det_MIDOG_1'),
      device         : prefs.get(PREF_DEVICE, 'cuda'),
      maskDownsample : prefs.get(PREF_MDS,    '16.0'),
      batchSize      : prefs.get(PREF_BATCH,  '8') ]
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

// WSI'in yerel dosya yolu (tiatoolbox doğrudan açar)
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

// Seçili (yoksa tüm) alan anotasyonları
def regionRoisOf = { imageData ->
    def h = imageData.getHierarchy()
    def sel = h.getSelectionModel().getSelectedObjects().findAll { it.isAnnotation() && it.hasROI() && it.getROI().isArea() }
    if (!sel.isEmpty()) return sel.collect { it.getROI() }
    return h.getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }.collect { it.getROI() }
}

// ── Bölgeyi TÜM-slayt ikili maskesine rasterle (sınıf gerektirmez) ──────────
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
    def f = new File(workDir, 'region_mask.png')
    if (f.getParentFile() != null) f.getParentFile().mkdirs()
    javax.imageio.ImageIO.write(img, 'PNG', f)
    appendLine('Bölge maskesi: ' + f.getName() + ' (' + mw + ' × ' + mh + ' px, downsample ' + downsample + ')')
    return [ok: true, file: f, w: mw, h: mh]
}

// ── GeoJSON içe al + bölgeye göre filtrele + sınıf başına nokta-anotasyonu ──
def importAndFilter = { File geojson, imageData, List regionRois ->
    if (geojson == null || !geojson.isFile())
        return [ok: false, error: 'GeoJSON çıktısı bulunamadı:\n' + (geojson?.getAbsolutePath() ?: '(yol yok)')]
    def root
    try { root = JsonParser.parseString(geojson.getText('UTF-8')).getAsJsonObject() }
    catch (Throwable t) { return [ok: false, error: 'GeoJSON ayrıştırılamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }
    def feats = root.has('features') ? root.getAsJsonArray('features') : null
    if (feats == null) return [ok: false, error: 'GeoJSON "features" içermiyor.']
    def plane = ImagePlane.getDefaultPlane()
    def byClass = new LinkedHashMap()
    int total = 0, inside = 0
    for (el in feats) {
        def ft = el.getAsJsonObject()
        if (!ft.has('geometry') || ft.get('geometry').isJsonNull()) continue
        def geom = ft.getAsJsonObject('geometry')
        if (!geom.has('type') || geom.get('type').getAsString() != 'Point') continue
        def c = geom.getAsJsonArray('coordinates')
        double x = c.get(0).getAsDouble(), y = c.get(1).getAsDouble()
        total++
        if (!regionRois.any { it.contains(x, y) }) continue
        inside++
        String cls = 'Detection'
        try {
            def props = ft.has('properties') ? ft.getAsJsonObject('properties') : null
            if (props != null && props.has('classification') && !props.get('classification').isJsonNull()
                    && props.get('classification').isJsonObject()
                    && props.getAsJsonObject('classification').has('name')) {
                cls = props.getAsJsonObject('classification').get('name').getAsString()
            } else if (ft.has('name') && !ft.get('name').isJsonNull()) {
                cls = ft.get('name').getAsString()
            }
        } catch (Throwable ignore) {}
        if (!byClass.containsKey(cls)) byClass.put(cls, new ArrayList())
        byClass.get(cls).add(new Point2(x, y))
    }
    QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() != null && it.getName().startsWith(SENTINEL_PREFIX) }, false)
    def newAnns = []
    byClass.each { cls, pts ->
        if (pts.isEmpty()) return
        def roi = ROIs.createPointsROI(pts, plane)
        def ann = PathObjects.createAnnotationObject(roi, QP.getPathClass(cls.toString()))
        ann.setName(SENTINEL_PREFIX + cls + ' (' + pts.size() + ')')
        ann.setLocked(true)
        newAnns << ann
    }
    QP.addObjects(newAnns)
    QP.fireHierarchyUpdate()
    def counts = new LinkedHashMap(); byClass.each { k, v -> counts.put(k, v.size()) }
    return [ok: true, total: total, inside: inside, counts: counts]
}

// ── Sonuç metni ─────────────────────────────────────────────────────────────
def resultText = { imageData, cfg, model, exp, imp ->
    def sb = new StringBuilder()
    sb << "TIA TOOLBOX — BÖLGEDE TESPİT\n"
    sb << "════════════════════════════\n\n"
    sb << "Slayt   : " << imageNameOf(imageData) << "\n"
    sb << "Model   : " << model.name << "  (" << model.task << ")\n"
    sb << "Cihaz   : " << (cfg.device ?: 'cuda') << "\n"
    sb << String.format(java.util.Locale.US, "Maske   : %,d × %,d px (downsample %s)%n", (int)(exp?.w ?: 0), (int)(exp?.h ?: 0), (cfg.maskDownsample ?: '16.0'))
    sb << String.format(java.util.Locale.US, "Tespit  : toplam %,d   ·   BÖLGE İÇİ %,d%n", (int)(imp?.total ?: 0), (int)(imp?.inside ?: 0))
    sb << "\nSınıf başına (bölge içi):\n"
    if (imp?.counts && !imp.counts.isEmpty()) imp.counts.each { k, v -> sb << String.format(java.util.Locale.US, "  • %-18s %,d%n", k.toString(), (int) v) }
    else sb << "  (bölge içinde tespit yok)\n"
    sb << "\nBölge içi tespitler sınıf başına kilitli nokta-anotasyonu olarak eklendi\n"
    sb << "(Annotations sekmesi: '" << SENTINEL_PREFIX << "…'). Tahminleri görsel doğrulayın.\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

// ── Headless ────────────────────────────────────────────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    println "TIA Toolbox bölge sihirbazı: python=${cfg.python ?: '(ayarsız)'} runner=${cfg.runner ?: '(ayarsız)'} model=${cfg.model} cihaz=${cfg.device}"
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(', ')}"
    if (imageData != null) println "Alan anotasyonu: ${regionRoisOf(imageData).size()}"
    else println "Açık görüntü yok."
    println "TIA Toolbox bölge sihirbazı için QuPath arayüzü gerekir (headless çalıştırılamaz)."
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
def busyLabelRef  = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def geojsonRef    = new java.util.concurrent.atomic.AtomicReference(null)
// CONFIG alanları
def pyFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def runnerFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def workFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def deviceChoiceRef= new java.util.concurrent.atomic.AtomicReference(null)
def mdsFieldRef    = new java.util.concurrent.atomic.AtomicReference(null)
def batchFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
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

// ── Alanları prefs'e yaz ─────────────────────────────────────────────────────
def textOf = { ref -> def f = ref.get(); return (f != null ? f.getText() : '').trim() }
def persistFields = {
    prefs.put(PREF_PYTHON, textOf(pyFieldRef))
    prefs.put(PREF_RUNNER, textOf(runnerFieldRef))
    prefs.put(PREF_WORK,   textOf(workFieldRef))
    def dv = deviceChoiceRef.get(); prefs.put(PREF_DEVICE, (dv != null && dv.getValue() != null) ? dv.getValue() : 'cuda')
    def md = textOf(mdsFieldRef);  prefs.put(PREF_MDS,   md ?: '16.0')
    def bs = textOf(batchFieldRef); prefs.put(PREF_BATCH, bs ?: '8')
    try { prefs.flush() } catch (Throwable ignore) {}
}

// ── Python süreci → satır akışı ──────────────────────────────────────────────
def runPython = { List cmd, Closure onLine ->
    def pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true)
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
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Bağımlılık kontrolü'); step.set('CHECK_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        runPython([cfg.python, cfg.runner, 'selftest'], appendLine)
        javafx.application.Platform.runLater { step.set('CHECK_DONE'); render() }
    }, 'AtolyeTIABolge-Check')
    worker.setDaemon(true); worker.start()
}

// ── Çalıştırma akışı ─────────────────────────────────────────────────────────
def startRun = {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def model = modelByName(cfg.model)
    def wsi = wsiPathOf(imageData)
    if (wsi == null) { errorTextRef.set('Slaytın yerel dosya yolu yok (WSI tiatoolbox tarafından açılamaz).'); step.set('ERROR'); render(); return }
    def regionRois = regionRoisOf(imageData)
    if (regionRois.isEmpty()) { errorTextRef.set('Bölge yok.\nÖnce bir alan anotasyonu çizin/seçin.'); step.set('ERROR'); render(); return }
    def workDir = resolveWorkDir(cfg, imageData); workDir.mkdirs()
    def saveDir = new File(workDir, 'region_out_' + imageNameOf(imageData))
    def outGeo  = new File(workDir, imageNameOf(imageData) + '_bolge.geojson')
    cancelledRef.set(false); geojsonRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Hazırlanıyor…'); step.set('RUN_RUNNING'); render()

    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        appendLine('Çalışma dizini: ' + workDir.getAbsolutePath())
        try {
            double mds = parseDoubleOr(cfg.maskDownsample, 16.0d)
            javafx.application.Platform.runLater { runPhaseRef.set('Bölge maskesi yazılıyor (1/2)…'); render() }
            def exp = exportRegionMask(imageData, workDir, mds, regionRois, appendLine)
            if (!exp.ok) { javafx.application.Platform.runLater { errorTextRef.set(exp.error); step.set('ERROR'); render() }; return }
            if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }

            def cmd = [cfg.python, cfg.runner, 'detect',
                       '--wsi', wsi,
                       '--mask', exp.file.getAbsolutePath(),
                       '--model', model.name,
                       '--engine', model.engine,
                       '--out', outGeo.getAbsolutePath(),
                       '--save-dir', saveDir.getAbsolutePath(),
                       '--device', (cfg.device ?: 'cuda'),
                       '--batch-size', String.valueOf(parseIntOr(cfg.batchSize, 8)),
                       '--classes', model.classes.join(',')]
            javafx.application.Platform.runLater { runPhaseRef.set('Çıkarım koşuyor (2/2) — ' + model.name + '…'); render() }
            def r = runPython(cmd, appendLine)
            if (!r.ok) { javafx.application.Platform.runLater { errorTextRef.set('Çıkarım başarısız (çıkış: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '')); step.set('ERROR'); render() }; return }

            // RESULT geojson=<path> satırından çıktı yolunu al, yoksa --out
            def geo = outGeo
            try { def m = (r.lastLines ?: '') =~ /RESULT geojson=(.+)/; if (m.find()) { def gp = new File(m.group(1).trim()); if (gp.isFile()) geo = gp } } catch (Throwable ignore) {}
            geojsonRef.set(geo)

            javafx.application.Platform.runLater { busyLabelRef.set('Sonuçlar içe aktarılıyor…'); step.set('BUSY'); render() }
            def imp = importAndFilter(geo, QP.getCurrentImageData(), regionRois)
            javafx.application.Platform.runLater {
                if (!imp.ok) { errorTextRef.set(imp.error); step.set('ERROR'); render() }
                else {
                    try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
                    resultTextRef.set(resultText(QP.getCurrentImageData(), cfg, model, exp, imp)); step.set('RESULT'); render()
                }
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Beklenmeyen hata:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
        }
    }, 'AtolyeTIABolge-Run')
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

    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); center.getChildren().add(lbl) }
    def addMonoArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: ''); ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(ta)
    }
    def addWarnLabel = { String txt ->
        def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true)
        lbl.setStyle('-fx-text-fill: #b8860b; -fx-font-weight: bold;'); center.getChildren().add(lbl)
    }
    def addLiveLog = { -> def la = logAreaRef.get(); if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) } }

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('TIA Toolbox yapılandırması gerekli')
        def miss = configMissing(cfg)
        addGuidance('Bu sihirbaz TIA Toolbox çalışma zamanını (tiatoolbox + torch) gerektirir.\nEksik/geçersiz:\n  • ' +
            (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')) +
            '\n\nÇalışma zamanı resmî eklentiyle kurulur: Extensions → TIAToolbox → Install Python runtime…\n' +
            'Köprü betiği: handson/python/tiatoolbox/region_runner.py')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText('TIA Toolbox bölge sihirbazı — yapılandırma')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def rnField = new javafx.scene.control.TextField(cfg.runner ?: '')
        def wdField = new javafx.scene.control.TextField(cfg.workDir ?: '')
        def mdsField = new javafx.scene.control.TextField(cfg.maskDownsample ?: '16.0')
        def batchField = new javafx.scene.control.TextField(cfg.batchSize ?: '8')
        [pyField, rnField, wdField].each { it.setPrefColumnCount(36) }
        [mdsField, batchField].each { it.setPrefColumnCount(8) }
        def deviceChoice = new javafx.scene.control.ChoiceBox(); ['cuda', 'cpu'].each { deviceChoice.getItems().add(it) }
        deviceChoice.setValue((cfg.device == 'cpu') ? 'cpu' : 'cuda')
        pyFieldRef.set(pyField); runnerFieldRef.set(rnField); workFieldRef.set(wdField)
        deviceChoiceRef.set(deviceChoice); mdsFieldRef.set(mdsField); batchFieldRef.set(batchField)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Dosya seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir  = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Dizin seç', null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (.venv/python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Köprü (region_runner.py):'), rnField, navButton('…', { browseFile(rnField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Çalışma dizini (ops.):'), wdField, navButton('…', { browseDir(wdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Cihaz:'), deviceChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Maske downsample:'), mdsField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Batch size:'), batchField)
        center.getChildren().add(grid)
        addGuidance('Python + köprü otomatik bulunur (tiatoolbox-runtime/.venv ve handson/python/tiatoolbox/). ' +
            'Maske downsample: büyük = küçük maske dosyası, kaba bölge sınırı (16–32 önerilir; tiatoolbox karo düzeyinde maskeler). ' +
            'Cihaz: GPU için cuda (RTX A4000 uyumlu).')
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('Bağımlılık kontrolü', { startSelftest() }, 'region_runner.py selftest'))
        actions.add(navButton('Kaydet ▶', { persistFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
    } else if (cur == 'CHECK_RUNNING') {
        title.setText('Bağımlılık kontrolü çalışıyor…')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'CHECK_DONE') {
        title.setText('Bağımlılık kontrolü tamam'); addLiveLog()
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText('Görüntü açık değil')
            addGuidance('Önce bir slayt açın (tercihen OpenSlide), ilgi BÖLGESİNİ çizip seçin, sonra "⟳ Yenile".')
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
        } else {
            def rois = regionRoisOf(imageData)
            title.setText('TIA Toolbox — bölgede tespit')
            def sb = new StringBuilder()
            sb << "Slayt          : " << imageNameOf(imageData) << "\n"
            sb << "Python         : " << (cfg.python ?: '(ayarsız)') << "\n"
            sb << String.format(java.util.Locale.US, "Bölge anotasyonu: %,d   (seçili → seçili kullanılır)%n", rois.size())
            addMonoArea(sb.toString())

            // ── Aranabilir model seçici (TIA Toolbox model-arama penceresi taklidi) ──
            def curModel = modelByName(cfg.model)
            def infoLbl = new javafx.scene.control.Label()
            infoLbl.setWrapText(true); infoLbl.setStyle('-fx-opacity: 0.85;')
            def labelOf = { m -> m.name + '  —  ' + m.task }
            def setInfo = { m -> if (m != null) infoLbl.setText('• Görev: ' + m.task + '\n• Çıktı sınıfları (' + m.classes.size() + '): ' + m.classes.join(', ') + '\n• ' + m.desc) }
            def matchModels = { String q ->
                def words = (q ?: '').toLowerCase(java.util.Locale.ROOT).trim().split(/\s+/).findAll { it }
                if (words.isEmpty()) return MODELS
                MODELS.findAll { m ->
                    def hay = (m.name + ' ' + m.task + ' ' + m.desc + ' ' + m.classes.join(' ')).toLowerCase(java.util.Locale.ROOT)
                    words.every { hay.contains(it) }
                }
            }
            def modelCombo = new javafx.scene.control.ComboBox(); modelCombo.setPrefWidth(440)
            def filterField = new javafx.scene.control.TextField()
            filterField.setPromptText('Model ara… (ad / görev / sınıf)'); filterField.setPrefColumnCount(24)
            def fillCombo = { List ms, String selectName ->
                modelCombo.getItems().setAll(ms.collect { labelOf(it) })
                def want = ms.find { it.name == selectName } ?: (ms.isEmpty() ? null : ms[0])
                if (want != null) modelCombo.setValue(labelOf(want))
            }
            fillCombo(MODELS, curModel.name); setInfo(curModel)
            filterField.textProperty().addListener({ obs, o, n ->
                def keepName = modelByLabel(modelCombo.getValue()?.toString() ?: '')?.name
                def ms = matchModels(n)
                fillCombo(ms, keepName)
                if (ms.isEmpty()) infoLbl.setText('(arama eşleşmedi — ' + MODELS.size() + ' modelden filtreyi daraltın)')
            } as javafx.beans.value.ChangeListener)
            modelCombo.valueProperty().addListener({ obs, o, n ->
                if (n != null) { def m = modelByLabel(n.toString()); prefs.put(PREF_MODEL, m.name); try { prefs.flush() } catch (Throwable ig) {}; setInfo(m) }
            } as javafx.beans.value.ChangeListener)
            def clearBtn = navButton('✕', { filterField.clear() }, 'Aramayı temizle')
            javafx.scene.layout.HBox.setHgrow(filterField, javafx.scene.layout.Priority.ALWAYS)
            def filterRow = new javafx.scene.layout.HBox(8); filterRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            filterRow.getChildren().addAll(new javafx.scene.control.Label('Model:'), filterField, clearBtn)
            def comboRow = new javafx.scene.layout.HBox(8); comboRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            comboRow.getChildren().addAll(modelCombo, new javafx.scene.control.Label('   cihaz: ' + (cfg.device ?: 'cuda')))
            center.getChildren().addAll(filterRow, comboRow, infoLbl)
            addGuidance('Modeli ad/görev/sınıf yazarak filtreleyin (' + MODELS.size() + ' model). Seçili model YALNIZCA bölge içinde çalışır; sonuç sınıf başına nokta-anotasyonu olarak içe alınır ve bölgeye göre sayılır.')

            boolean canRun = configComplete(cfg) && rois.size() >= 1
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
            def runBtn = navButton('Bölgede çalıştır ▶', { startRun() }, 'Seçili modeli bölgede çalıştırır')
            runBtn.setDisable(!canRun)
            if (!canRun && rois.size() < 1) addWarnLabel('⚠ Önce en az 1 alan anotasyonu çizin/seçin.')
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING') {
        title.setText(runPhaseRef.get())
        addGuidance('TIA Toolbox köprüsü koşuyor (ilk çalıştırmada model ağırlıkları indirilebilir). Zaman aşımı: ' + PYTHON_TIMEOUT_SECONDS + ' sn.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'BUSY') {
        title.setText(busyLabelRef.get()); addGuidance('Lütfen bekleyin…'); center.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅'); addMonoArea(resultTextRef.get())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('↻ Yeniden çalıştır', { step.set('READY'); render() }))
    } else { // ERROR
        title.setText('Hata'); addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Geri', { step.set('READY'); render() }))
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

// ── Açılış ────────────────────────────────────────────────────────────────────
step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('TIA Toolbox bölgede çekirdek/mitoz tespiti')
        stage.setAlwaysOnTop(alwaysTop.get())
        render(); stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ TIA Toolbox bölgede çekirdek/mitoz tespiti sihirbazı açıldı."
