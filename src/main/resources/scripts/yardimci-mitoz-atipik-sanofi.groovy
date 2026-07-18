/**
 * Modül - Atipik mitoz sınıflama (Sanofi EFTD · DINOv3-H+, tespit SONRASI)
 * -----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Bu bir DEDEKTÖR DEĞİLDİR. Bir mitoz dedektörünün (KongNet / FCOS / RetinaNet, ya da
 *   karşılaştırma konsensüsü) daha önce eklediği "Mitosis" nokta-anotasyonlarını alır ve
 *   Sanofi/Mines-Paris'in **MIDOG 2025 Görev 2 BİRİNCİSİ** EFTD modeli (LoRA ince-ayarlı
 *   **DINOv3-H+** Vision Transformer) ile her mitozu **tipik vs atipik** olarak sınıflar.
 *   Seçili bölgeyi hedef çözünürlükte bir ROI görüntüsü + nokta listesi olarak dışa aktarır →
 *   köprü (sanofi/eftd_runner.py) her nokta çevresinde bir yama kırpar, sınıflar → her nokta
 *   "Mitoz (tipik)" (yeşil) / "Mitoz (atipik)" (kırmızı) olarak yeniden sınıflandırılır.
 *
 * ⚠️ KAPILI OMURGA — VARSAYILAN OLARAK DEVRE DIŞI:
 *   Omurga (facebook/dinov3-vith16plus-pretrain-lvd1689m) HuggingFace'te KAPILIDIR: Meta
 *   lisansını kabul edip `huggingface-cli login` ile giriş yapmanız gerekir (onay beklenebilir;
 *   OTOMATİK DEĞİL). Bu yüzden menüde bu giriş DEVRE DIŞIdır. Erişim edindikten sonra eklentiyi
 *   yeniden derleyip etkinleştirin ya da betiği [Automate → Project scripts]'ten çalıştırın.
 *   Depo lisansı ticari-olmayan araştırmadır. LoRA adaptörleri + kafa çalışma anında indirilir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Tipik/atipik ETİKET + atipik olasılık + atipik oranı. Klinik derece/eşik/yorum DEĞİL.
 *     Tahminleri görsel doğrulayın.
 *
 * GİRDİ SÖZLEŞMESİ:
 *   Seçili bölge içindeki "Mitosis" ve "Mitoz (konsensüs)" nokta-anotasyonları (ve önceden
 *   sınıflanmış "Mitoz (tipik/atipik)" — yeniden sınıflanır). Nokta yoksa: önce bir dedektör.
 *
 * ÇALIŞMA ZAMANI:
 *   torch + transformers + peft ortamı (env id: sanofi-eftd) + sanofi/eftd_runner.py köprüsü +
 *   KAPILI DINOv3-H+ omurgası (ayrı). Kurulum: Atölye Python ortam yöneticisi → "Sanofi EFTD".
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

long PYTHON_TIMEOUT_SECONDS = 3600L
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def MODEL          = 'DINOv3-H+ EFTD (Sanofi, MIDOG25 T2 1.)'
def ENV_ID         = 'sanofi-eftd'
def WEIGHTS_NAME   = 'model.safetensors'
def CLASS_ATYP     = 'Mitoz (atipik)'
def CLASS_TYP      = 'Mitoz (tipik)'
def SUMMARY_NAME   = 'Atipik mitoz sınıflama özeti'
// Sınıflanacak girdi noktalarının sınıfları (dedektör çıktısı + konsensüs + önceki sınıflama).
def INPUT_CLASSES  = ['Mitosis', 'Mitoz (konsensüs)', CLASS_TYP, CLASS_ATYP] as Set
double TARGET_MPP  = 0.25        // T2 sınıflandırıcılar 40x (~0.25 µm/px) yamalarla eğitildi
double PATCH_UM    = 32.0        // nokta çevresi kırpma (µm); 0.25 µm/px → ~128 px (referans 128px@40x)

def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/mitoz-atipik-sanofi')
def PREF_PYTHON = 'python'
def PREF_RUNNER = 'runner'
def PREF_WORK   = 'workDir'
def PREF_DEVICE = 'device'
def PREF_TMPP   = 'targetMpp'
def PREF_PATCHUM= 'patchUm'

def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
def modelCacheFile = { -> new File(new File(new File(atolyeDataRoot(), 'cache'), 'sanofi-eftd'), WEIGHTS_NAME) }
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
    try { def rec = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('py.' + ENV_ID, ''); if (rec?.trim() && new File(rec.trim()).isFile()) return rec.trim() } catch (Throwable ignore) {}
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
    for (r in roots) { def f = new File(r, 'python/sanofi/eftd_runner.py'); if (f.isFile()) return f.getAbsolutePath() }
    return ''
}
def loadConfig = { ->
    def py = prefs.get(PREF_PYTHON, ''); if (!py?.trim()) py = detectPython()
    def rn = prefs.get(PREF_RUNNER, ''); if (!rn?.trim()) rn = detectRunner()
    [ python: py, runner: rn, workDir: prefs.get(PREF_WORK, ''), device: prefs.get(PREF_DEVICE, 'cuda'),
      targetMpp: prefs.get(PREF_TMPP, ''), patchUm: prefs.get(PREF_PATCHUM, '') ]
}
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile()) miss << 'Python yürütülebilir (sanofi-eftd venv)'
    if (!cfg.runner?.trim() || !(new File(cfg.runner)).isFile()) miss << 'Köprü betiği (eftd_runner.py)'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }
def parseIntOr = { s, int dfl -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return dfl } }
def parseDoubleOr = { s, double dfl -> try { return Double.parseDouble((s ?: '').toString().trim().replace(',', '.')) } catch (Throwable t) { return dfl } }

def resolveWorkDir = { cfg, imageData ->
    def wd = cfg.workDir?.trim(); if (wd) return new File(wd)
    def project = QP.getProject()
    if (project != null && project.getPath() != null) return new File(project.getPath().getParent().toFile(), 'tiatoolbox_work')
    return new File(System.getProperty('java.io.tmpdir'), 'tiatoolbox_work')
}
def imageNameOf = { imageData -> (imageData.getServer().getMetadata().getName() ?: 'slide').replaceAll(/\.[^.\/\\]+$/, '') }
def pixelMicrons = { imageData ->
    try { def cal = imageData.getServer().getPixelCalibration(); if (cal != null && cal.hasPixelSizeMicrons()) return [pw: cal.getPixelWidthMicrons(), ph: cal.getPixelHeightMicrons()] } catch (Throwable ignore) {}
    return null
}
def selectedRegions = { imageData ->
    def h = imageData.getHierarchy()
    def sel = h.getSelectionModel().getSelectedObjects().findAll { it.isAnnotation() && it.hasROI() && it.getROI().isArea() }
    if (!sel.isEmpty()) return new ArrayList(sel)
    return new ArrayList(h.getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() })
}
// Sınıflanacak mitoz nokta-anotasyonları: seçili bölge içinde, INPUT_CLASSES sınıflı, nokta ROI.
def mitosisPointsIn = { imageData, List regionRois ->
    def h = imageData.getHierarchy()
    return new ArrayList(h.getAnnotationObjects().findAll { a ->
        a.hasROI() && a.getROI().isPoint() && a.getPathClass() != null && INPUT_CLASSES.contains(a.getPathClass().getName()) &&
        regionRois.any { r -> r.contains(a.getROI().getCentroidX(), a.getROI().getCentroidY()) }
    })
}

// ── Bölgeyi hedef çözünürlükte ROI görüntüsü olarak dışa aktar (birleşik sınır kutusu) ──
def exportRegionImage = { imageData, File workDir, double targetMpp, List regionRois, cal, Closure appendLine ->
    def server = imageData.getServer()
    double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY
    regionRois.each { roi -> minX = Math.min(minX, roi.getBoundsX()); minY = Math.min(minY, roi.getBoundsY()); maxX = Math.max(maxX, roi.getBoundsX() + roi.getBoundsWidth()); maxY = Math.max(maxY, roi.getBoundsY() + roi.getBoundsHeight()) }
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
    def request = qupath.lib.regions.RegionRequest.createInstance(server.getPath(), downsample, x, y, w, h)
    def img = server.readRegion(request)
    if (img == null) return [ok: false, error: 'Bölge okunamadı (readRegion null döndü).']
    def rgb = new java.awt.image.BufferedImage(img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB)
    def g = rgb.createGraphics()
    try { g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight()); g.drawImage(img, 0, 0, null) } finally { g.dispose() }
    def f = new File(workDir, 'atipik_roi.png'); if (f.getParentFile() != null) f.getParentFile().mkdirs()
    javax.imageio.ImageIO.write(rgb, 'PNG', f)
    appendLine(String.format(java.util.Locale.US, 'ROI görüntüsü: %s (%d × %d px, downsample %.3f, köken %d,%d)', f.getName(), rgb.getWidth(), rgb.getHeight(), downsample, x, y))
    return [ok: true, file: f, originX: x, originY: y, downsample: downsample]
}
// Girdi noktalarını GeoJSON (taban-piksel) olarak yaz.
def writePointsGeoJson = { File out, List points ->
    def sb = new StringBuilder(); sb << '{"type":"FeatureCollection","features":['
    points.eachWithIndex { a, i ->
        def roi = a.getROI(); double x = roi.getCentroidX(), y = roi.getCentroidY()
        if (i > 0) sb << ','
        sb << String.format(java.util.Locale.US, '{"type":"Feature","geometry":{"type":"Point","coordinates":[%.3f,%.3f]},"properties":{}}', x, y)
    }
    sb << ']}'; out.setText(sb.toString(), 'UTF-8')
}

// ── Headless ──
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    def cfg = loadConfig(); def miss = configMissing(cfg)
    println "Atipik sınıflama (Sanofi EFTD): python=${cfg.python ?: '(ayarsız)'} runner=${cfg.runner ?: '(ayarsız)'}"
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(', ')}"
    if (imageData != null) { def rr = selectedRegions(imageData).collect { it.getROI() }; println "Sınıflanacak mitoz noktası: ${mitosisPointsIn(imageData, rr).size()}" }
    println "Bu sihirbaz QuPath arayüzü gerektirir. ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Durum makinesi ──
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def selftestOkRef = new java.util.concurrent.atomic.AtomicBoolean(true)
def dlOkRef       = new java.util.concurrent.atomic.AtomicBoolean(true)
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
def patchFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def render

def navButton = { String text, Closure action, String tooltip = null -> def b = new javafx.scene.control.Button(text); b.setOnAction({ action() }); if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip)); return b }
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
def autoSaveLog = { File dir, String base -> try { if (dir == null) return null; dir.mkdirs(); def f = new File(dir, (base ?: 'mitoz-atipik') + '_run.log'); f.setText(logSnapshot(), 'UTF-8'); logFileRef.set(f); return f } catch (Throwable t) { return null } }
def saveLogInteractive = {
    def txt = logSnapshot(); if (!txt?.trim()) { Dialogs.showInfoNotification('Günlük', 'Kaydedilecek günlük yok.'); return }
    try {
        def suggested = logFileRef.get() ?: new File(System.getProperty('user.home'), 'mitoz-atipik_run.log')
        def f = qupath.fx.dialogs.FileChoosers.promptToSaveFile(stage, 'Çalışma günlüğünü kaydet', suggested, new javafx.stage.FileChooser.ExtensionFilter('Günlük (*.log, *.txt)', '*.log', '*.txt'))
        if (f != null) { f.setText(txt, 'UTF-8'); Dialogs.showInfoNotification('Günlük', 'Kaydedildi: ' + f.getAbsolutePath()) }
    } catch (Throwable t) { Dialogs.showErrorMessage('Günlük', 'Kaydedilemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName())) }
}
def textOf = { ref -> def f = ref.get(); return (f != null ? f.getText() : '').trim() }
def persistFields = {
    prefs.put(PREF_PYTHON, textOf(pyFieldRef)); prefs.put(PREF_RUNNER, textOf(runnerFieldRef)); prefs.put(PREF_WORK, textOf(workFieldRef))
    def dv = deviceChoiceRef.get(); prefs.put(PREF_DEVICE, (dv != null && dv.getValue() != null) ? dv.getValue() : 'cuda')
    prefs.put(PREF_TMPP, textOf(tmppFieldRef)); prefs.put(PREF_PATCHUM, textOf(patchFieldRef))
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
    }, 'AtolyeEftd-Check')
    worker.setDaemon(true); worker.start()
}
def startModelDownload = {
    persistFields(); def cfg = loadConfig(); def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set('Önce yapılandırmayı tamamlayın:\n  • ' + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO); logAreaRef.set(la)
    runPhaseRef.set('Model indiriliyor…'); step.set('DL_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython([cfg.python, cfg.runner, 'download'], appendLine)
        javafx.application.Platform.runLater { dlOkRef.set(r.ok); step.set('DL_DONE'); render() }
    }, 'AtolyeEftd-Download')
    worker.setDaemon(true); worker.start()
}

// ── Sınıflama akışı ──
def startRun = {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def regions = selectedRegions(imageData)
    if (regions.isEmpty()) { errorTextRef.set('Bölge yok.\nÖnce bir alan anotasyonu çizin/seçin.'); step.set('ERROR'); render(); return }
    def regionRois = regions.collect { it.getROI() }
    def points = mitosisPointsIn(imageData, regionRois)
    if (points.isEmpty()) { errorTextRef.set('Sınıflanacak mitoz noktası yok.\nÖnce bir mitoz DEDEKTÖRÜ çalıştırın (KongNet / FCOS / RetinaNet ya da Karşılaştır).'); step.set('ERROR'); render(); return }
    def cal = pixelMicrons(imageData)
    double targetMpp = cfg.targetMpp?.trim() ? parseDoubleOr(cfg.targetMpp, TARGET_MPP) : TARGET_MPP
    double patchUm = cfg.patchUm?.trim() ? parseDoubleOr(cfg.patchUm, PATCH_UM) : PATCH_UM
    int patchPx = (int) Math.max(16, Math.round(patchUm / targetMpp))
    def workDir = resolveWorkDir(cfg, imageData); workDir.mkdirs()
    def base = imageNameOf(imageData)
    def ptsGeo = new File(workDir, base + '_atipik_points.geojson')
    def outGeo = new File(workDir, base + '_atipik_out.geojson')
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO); logAreaRef.set(la)
    runPhaseRef.set('Hazırlanıyor…'); step.set('RUN_RUNNING'); render()

    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def setPhase = { String ph -> javafx.application.Platform.runLater { runPhaseRef.set(ph); render() } }
        try {
            appendLine('Model: ' + MODEL + '  ·  sınıflanacak nokta: ' + points.size() + '  ·  yama: ' + patchPx + ' px (' + String.format(java.util.Locale.US, '%.1f µm @ %.2f µm/px', patchUm, targetMpp) + ')')
            if (cal == null) appendLine('⚠ Piksel boyutu kalibre değil — yama boyutu hedef-mpp varsayımıyla hesaplandı.')
            setPhase('ROI + nokta listesi dışa aktarılıyor (1/2)…')
            // ROI centroid'leri değişmezdir → worker thread'den güvenle yazılır.
            writePointsGeoJson(ptsGeo, points)
            def exp = exportRegionImage(imageData, workDir, targetMpp, regionRois, cal, appendLine)
            if (!exp.ok) { javafx.application.Platform.runLater { errorTextRef.set(exp.error); step.set('ERROR'); render() }; return }
            if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }
            def cmd = [cfg.python, cfg.runner, 'classify', '--roi', exp.file.getAbsolutePath(), '--points', ptsGeo.getAbsolutePath(),
                       '--out', outGeo.getAbsolutePath(), '--origin', (exp.originX + ',' + exp.originY),
                       '--downsample', String.format(java.util.Locale.US, '%.6f', (double) exp.downsample),
                       '--patch-px', String.valueOf(patchPx), '--device', (cfg.device ?: 'cuda')]
            setPhase('EFTD (DINOv3) sınıflaması koşuyor (2/2)…')
            def r = runPython(cmd, appendLine)
            appendLine('# Çıkış kodu: ' + r.exitCode)
            def savedLog = autoSaveLog(workDir, base)
            if (savedLog != null) appendLine('# Günlük kaydedildi: ' + savedLog.getAbsolutePath())
            if (!r.ok) { javafx.application.Platform.runLater { errorTextRef.set('Sınıflama başarısız (çıkış: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '') + (savedLog != null ? ('\n\nGünlük: ' + savedLog.getAbsolutePath()) : '')); step.set('ERROR'); render() }; return }
            def geo = outGeo
            try { def m = (r.lastLines ?: '') =~ /RESULT geojson=(.+)/; if (m.find()) { def gp = new File(m.group(1).trim()); if (gp.isFile()) geo = gp } } catch (Throwable ignore) {}
            javafx.application.Platform.runLater {
                try {
                    // çıktı noktalarını oku + koordinatına göre girdi anotasyonuna eşle → yeniden sınıflandır
                    def root = JsonParser.parseString(geo.getText('UTF-8')).getAsJsonObject()
                    def feats = root.has('features') ? root.getAsJsonArray('features') : null
                    def atypClass = QP.getPathClass(CLASS_ATYP); def typClass = QP.getPathClass(CLASS_TYP)
                    try { atypClass.setColor(qupath.lib.common.ColorTools.packRGB(220, 40, 40)); typClass.setColor(qupath.lib.common.ColorTools.packRGB(30, 170, 90)) } catch (Throwable ignore) {}
                    int nAtyp = 0, nTyp = 0
                    def hier = imageData.getHierarchy()
                    if (feats != null) {
                        for (el in feats) {
                            def ft = el.getAsJsonObject()
                            def props = ft.has('properties') ? ft.getAsJsonObject('properties') : null
                            if (props == null || !props.has('id') || props.get('id').isJsonNull()) continue
                            int idx = props.get('id').getAsInt()
                            if (idx < 0 || idx >= points.size()) continue
                            def ann = points[idx]
                            def name = (props.has('classification') && !props.get('classification').isJsonNull()) ? props.getAsJsonObject('classification').get('name').getAsString() : CLASS_TYP
                            double pAtyp = (props != null && props.has('probability') && !props.get('probability').isJsonNull()) ? props.get('probability').getAsDouble() : Double.NaN
                            boolean isAtyp = (name == CLASS_ATYP)
                            ann.setPathClass(isAtyp ? atypClass : typClass)
                            if (Double.isFinite(pAtyp)) ann.measurements['Atipik olasılık'] = pAtyp
                            if (isAtyp) nAtyp++ else nTyp++
                        }
                    }
                    hier.fireHierarchyChangedEvent(hier)
                    try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
                    int tot = nAtyp + nTyp
                    def sb = new StringBuilder()
                    sb << "ATİPİK MİTOZ SINIFLAMA (Sanofi EFTD · DINOv3-H+)\n═══════════════════════════════════════════════\n\n"
                    sb << "Slayt        : " << imageNameOf(imageData) << "\n"
                    sb << "Sınıflanan   : " << tot << " mitoz noktası\n"
                    sb << "  • Atipik   : " << nAtyp << (tot > 0 ? String.format(java.util.Locale.US, "  (%.0f%%)", 100.0 * nAtyp / tot) : "") << "\n"
                    sb << "  • Tipik    : " << nTyp << "\n\n"
                    sb << "Noktalar yeniden sınıflandırıldı: KIRMIZI = atipik, YEŞİL = tipik; 'Atipik olasılık' ölçümü yazıldı.\n"
                    sb << "Tahminleri görsel doğrulayın; klinik derece/yorum üretilmez.\n"
                    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
                    if (savedLog != null) sb << "\n\nÇalışma günlüğü: " << savedLog.getAbsolutePath()
                    resultTextRef.set(sb.toString()); step.set('RESULT'); render()
                } catch (Throwable t) { errorTextRef.set('Sonuç işleme hatası: ' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Beklenmeyen hata:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
        }
    }, 'AtolyeEftd-Run')
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
        title.setText('Atipik sınıflama — çalışma zamanı gerekli')
        def miss = configMissing(cfg)
        addGuidance('Bu modül torch + transformers + peft ortamını (env id: sanofi-eftd) gerektirir.\nEksik/geçersiz:\n  • ' + (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')) +
            '\n\nKurulum: Extensions → Atölye → Yardımcılar → Python köprüleri → Atölye Python ortam yöneticisi → "Sanofi EFTD — atipik sınıflandırıcı".\nKöprü: handson/python/sanofi/eftd_runner.py')
        actions.add(navButton('Kapat', { stage.close() })); actions.add(navButton('⚙ Python ortamını kur/aç', { launchBundledScript('yardimci-python-ortam-yoneticisi.groovy') }, 'Atölye Python ortam yöneticisini açar → "Sanofi EFTD"yi kurun (omurga ayrıca kapılı)')); actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText('Atipik sınıflama — yapılandırma')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: ''); def rnField = new javafx.scene.control.TextField(cfg.runner ?: ''); def wdField = new javafx.scene.control.TextField(cfg.workDir ?: '')
        def tmppField = new javafx.scene.control.TextField(cfg.targetMpp ?: ''); tmppField.setPromptText('boş = 0.25')
        def patchField = new javafx.scene.control.TextField(cfg.patchUm ?: ''); patchField.setPromptText('boş = 32')
        [pyField, rnField, wdField].each { it.setPrefColumnCount(36) }; [tmppField, patchField].each { it.setPrefColumnCount(8) }
        def deviceChoice = new javafx.scene.control.ChoiceBox(); ['cuda', 'cpu'].each { deviceChoice.getItems().add(it) }; deviceChoice.setValue((cfg.device == 'cpu') ? 'cpu' : 'cuda')
        pyFieldRef.set(pyField); runnerFieldRef.set(rnField); workFieldRef.set(wdField); deviceChoiceRef.set(deviceChoice); tmppFieldRef.set(tmppField); patchFieldRef.set(patchField)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Dosya seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir  = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Dizin seç', null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Model:'), new javafx.scene.control.Label(MODEL + ' (sabit)'))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (.venv/python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Köprü (eftd_runner.py):'), rnField, navButton('…', { browseFile(rnField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Çalışma dizini (ops.):'), wdField, navButton('…', { browseDir(wdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Cihaz:'), deviceChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Hedef çözünürlük (µm/px):'), tmppField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Yama boyutu (µm):'), patchField)
        center.getChildren().add(grid)
        def mcf = modelCacheFile()
        def mcLbl = new javafx.scene.control.Label(mcf.isFile() ? ('✓ Yerel model VAR: ' + mcf.getAbsolutePath()) : '○ Yerel model yok — "Modeli yerel indir" ile bir kez indirin (LoRA adaptörleri).')
        mcLbl.setWrapText(true); mcLbl.setMaxWidth(Double.MAX_VALUE); mcLbl.setStyle('-fx-opacity: 0.85; -fx-font-size: 11px;'); center.getChildren().add(mcLbl)
        addGuidance('Bu bir DEDEKTÖR değildir: mevcut "Mitosis"/"Mitoz (konsensüs)" noktalarını tipik/atipik olarak sınıflar. Ağırlık paketlenmez; "Modeli yerel indir" v1.0.0 yayınından çeker (LİSANS yok → araştırma/eğitim). Yama boyutu: her mitoz çevresinde kırpılan alan (µm); referans ~32 µm (≈128 px @ 40x). Hedef çözünürlük varsayılanı 0.25 (T2 eğitim ölçeği).')
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('⚙ Python ortamı', { launchBundledScript('yardimci-python-ortam-yoneticisi.groovy') }, 'Atölye Python ortam yöneticisini aç'))
        actions.add(navButton('Modeli yerel indir', { startModelDownload() }))
        actions.add(navButton('Bağımlılık kontrolü', { startSelftest() }))
        actions.add(navButton('Kaydet ▶', { persistFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
    } else if (cur == 'CHECK_RUNNING') {
        title.setText('Bağımlılık kontrolü çalışıyor…'); center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'CHECK_DONE') {
        title.setText(selftestOkRef.get() ? 'Bağımlılık kontrolü tamam ✅' : '⚠ Bağımlılık kontrolü BAŞARISIZ — günlüğe bakın'); addLiveLog()
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
    } else if (cur == 'DL_RUNNING') {
        title.setText('Model indiriliyor…'); addGuidance('Sanofi EFTD LoRA adaptörleri + kafa (config.yaml + model.safetensors) indiriliyor. NOT: DINOv3-H+ omurgası KAPILI ve ayrıdır (Meta lisansı + hf login).'); center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'DL_DONE') {
        title.setText(dlOkRef.get() ? 'Model indirildi ✅' : '⚠ İndirilemedi — günlüğe bakın'); addLiveLog()
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText('Görüntü açık değil'); addGuidance('Önce bir H&E slaydı açıp bir dedektör çalıştırın, ilgi ALANINI seçin, sonra "⟳ Yenile".')
            actions.add(navButton('Kapat', { stage.close() })); actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() })); actions.add(navButton('⟳ Yenile', { render() }))
        } else {
            def regions = selectedRegions(imageData); def regionRois = regions.collect { it.getROI() }
            def points = mitosisPointsIn(imageData, regionRois); def cal = pixelMicrons(imageData)
            title.setText('Atipik mitoz sınıflama — Sanofi EFTD (DINOv3-H+)')
            addWarnLabel('⚠ KAPILI OMURGA: DINOv3-H+ için Meta lisansı + hf login gerekir (otomatik değil). Erişim yoksa çalışmaz. Menüde varsayılan olarak devre dışıdır.')
            def sb = new StringBuilder()
            sb << "Slayt          : " << imageNameOf(imageData) << "\n"
            sb << "Model          : " << MODEL << "\n"
            sb << "Cihaz          : " << (cfg.device ?: 'cuda') << "\n"
            sb << "Yerel model    : " << (modelCacheFile().isFile() ? 'VAR' : 'yok — "Modeli yerel indir"') << "\n"
            sb << String.format(java.util.Locale.US, "Seçili bölge   : %,d alan anotasyonu%n", regions.size())
            sb << String.format(java.util.Locale.US, "Sınıflanacak   : %,d mitoz noktası%n", points.size())
            sb << "Kalibrasyon    : " << (cal != null ? String.format(java.util.Locale.US, '%.3f µm/px', (cal.pw + cal.ph) / 2.0) : 'KALİBRE DEĞİL') << "\n"
            addMonoArea(sb.toString())
            addGuidance('Bu bir DEDEKTÖR değildir: seçili bölgedeki mevcut mitoz noktalarını (dedektör çıktısı / konsensüs) tipik vs atipik olarak sınıflar. Sonuç: noktalar KIRMIZI (atipik) / YEŞİL (tipik) yeniden renklenir + "Atipik olasılık" ölçümü.')
            if (points.isEmpty()) addWarnLabel('⚠ Sınıflanacak mitoz noktası yok — önce bir mitoz dedektörü (KongNet / FCOS / RetinaNet ya da Karşılaştır) çalıştırın.')
            boolean canRun = configComplete(cfg) && points.size() >= 1
            if (!configComplete(cfg)) addWarnLabel('⚠ Python ortamı (sanofi-eftd) kurulu değil — "⚙ Python ortamını kur/aç" ile kurun (DINOv3 omurgası ayrıca kapılı).')
            actions.add(navButton('Kapat', { stage.close() }))
            if (!configComplete(cfg)) actions.add(navButton('⚙ Python ortamını kur/aç', { launchBundledScript('yardimci-python-ortam-yoneticisi.groovy') }, 'Atölye Python ortam yöneticisini açar'))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() })); actions.add(navButton('⟳ Yenile', { render() }))
            def runBtn = navButton('Sınıfla ▶', { startRun() }, 'Seçili bölgedeki mitoz noktalarını tipik/atipik sınıfla'); runBtn.setDisable(!canRun)
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING') {
        title.setText(runPhaseRef.get()); addGuidance('EFTD (DINOv3) sınıflaması koşuyor.'); center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
        actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
    } else if (cur == 'RESULT') {
        title.setText('Sınıflama tamam ✅'); addMonoArea(resultTextRef.get())
        addGuidance('Noktalar Annotations panelinde "Mitoz (atipik)" (kırmızı) / "Mitoz (tipik)" (yeşil) sınıflarında; sınıfları aç/kapat ederek inceleyin.')
        def lf = logFileRef.get(); if (lf != null) addGuidance('Çalışma günlüğü: ' + lf.getAbsolutePath())
        actions.add(navButton('Kapat', { stage.close() })); actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
        actions.add(navButton('↻ Yeniden', { step.set('READY'); render() }))
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
    stage.setScene(new javafx.scene.Scene(root, 900, 640))
}

step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage(); stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Atipik mitoz sınıflama — Sanofi EFTD (DINOv3-H+, MIDOG25 T2)'); stage.setAlwaysOnTop(alwaysTop.get())
        render(); stage.show()
    } catch (Throwable t) { Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')) }
}
println "✓ Atipik mitoz sınıflama (Sanofi EFTD · DINOv3-H+) sihirbazı açıldı."
