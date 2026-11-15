/**
 * Modül - Atipik mitoz sınıflama (MIDOG25 Track-2 EfficientNetV2, tespit SONRASI)
 * ------------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Bu bir DEDEKTÖR DEĞİLDİR. Bir mitoz dedektörünün (KongNet / FCOS / RetinaNet, ya da
 *   karşılaştırma konsensüsü) daha önce eklediği "Mitosis" nokta-anotasyonlarını alır ve
 *   DeepMicroscopy'nin **MIDOG 2025 Görev 2 resmî referans** sınıflandırıcısı (EfficientNetV2-M)
 *   ile her mitozu **tipik vs atipik** olarak sınıflar. Her mitoz noktasının çevresinden hedef
 *   çözünürlükte KÜÇÜK bir yama kırpar (kenarda beyaz dolgu → nokta yamanın merkezinde kalır)
 *   ve yamaları bir manifest dosyasıyla köprüye (midog/atypical_effnet.py) verir → her nokta
 *   "Mitoz (tipik)" (yeşil) / "Mitoz (atipik)" (kırmızı) olarak yeniden sınıflandırılır, atipik
 *   olasılığı ölçüm olarak yazılır. Dev bir bölge görüntüsü dışa AKTARILMAZ (yalnız yamalar).
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Tipik/atipik ETİKET + atipik olasılık + atipik oranı. Klinik derece/eşik/yorum DEĞİL.
 *     Tahminleri görsel doğrulayın.
 *
 * GİRDİ SÖZLEŞMESİ:
 *   Seçili bölge içindeki "Mitosis" ve "Mitoz (konsensüs)" nokta-anotasyonları (ve önceden
 *   sınıflanmış "Mitoz (tipik/atipik)" — yeniden sınıflanır). Nokta yoksa: önce bir dedektör
 *   çalıştırın.
 *
 * AĞIRLIK LİSANSI:
 *   efficientnetv2_m_fold3_best.pth çalışma anında v1.0.0 yayınından indirilir; paketlenmez.
 *   Kaynak deponun LİSANS dosyası yok → araştırma/eğitim, kullanıcı sorumluluğunda.
 *   Atıf: MIDOG 2025, DeepMicroscopy.
 *
 * ÇALIŞMA ZAMANI:
 *   torch + timm ortamı (env id: midog-atypical) + midog/atypical_effnet.py köprüsü.
 *   Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam
 *   yöneticisi → "MIDOG25 EffNetV2 — atipik sınıflandırıcı".
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
def MODEL          = 'EfficientNetV2-M (MIDOG25 Track-2)'
def ENV_ID         = 'midog-atypical'
def WEIGHTS_NAME   = 'efficientnetv2_m_fold3_best.pth'
def CLASS_ATYP     = 'Mitoz (atipik)'
def CLASS_TYP      = 'Mitoz (tipik)'
def META_POINT     = 'atolye.mitoz.nokta'   // dedektörlerin görüntü halkası → bağlı noktanın ID'si
def SUMMARY_NAME   = 'Atipik mitoz sınıflama özeti'
// Sınıflanacak girdi noktalarının sınıfları (dedektör çıktısı + konsensüs + önceki sınıflama).
def INPUT_CLASSES  = ['Mitosis', 'Mitoz (konsensüs)', CLASS_TYP, CLASS_ATYP] as Set
double TARGET_MPP  = 0.25        // T2 sınıflandırıcılar 40x (~0.25 µm/px) yamalarla eğitildi
double PATCH_UM    = 32.0        // nokta çevresi kırpma (µm); 0.25 µm/px → ~128 px (referans 128px@40x)

def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/mitoz-atipik-effnet')
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
def modelCacheFile = { -> new File(new File(new File(atolyeDataRoot(), 'cache'), 'midog-atypical'), WEIGHTS_NAME) }
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
def detectRunner = { ->
    def project = QP.getProject()
    def roots = []
    if (project != null && project.getPath() != null) {
        def handson = project.getPath().getParent().toFile()
        roots << handson
        if (handson.getParentFile() != null) roots << new File(handson.getParentFile(), 'handson')
    }
    for (r in roots) { def f = new File(r, 'python/midog/atypical_effnet.py'); if (f.isFile()) return f.getAbsolutePath() }
    return extractBundledBridge('python/midog/atypical_effnet.py')
}
def loadConfig = { ->
    def py = prefs.get(PREF_PYTHON, ''); if (!py?.trim()) py = detectPython()
    def rn = prefs.get(PREF_RUNNER, ''); if (!rn?.trim()) rn = detectRunner()
    [ python: py, runner: rn, workDir: prefs.get(PREF_WORK, ''), device: prefs.get(PREF_DEVICE, 'cuda'),
      targetMpp: prefs.get(PREF_TMPP, ''), patchUm: prefs.get(PREF_PATCHUM, '') ]
}
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile()) miss << workshopText('script.665d449ee88a', 'Python yürütülebilir (midog-atypical venv)')
    if (!cfg.runner?.trim() || !(new File(cfg.runner)).isFile()) miss << workshopText('script.a83e2690cb42', 'Köprü betiği (atypical_effnet.py)')
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

// ── Her mitoz noktası çevresinden küçük bir yama dışa aktar (dev bölge görüntüsü YOK) ──
// Sınıflandırıcı yalnız nokta çevresini görür. Bölgenin tamamını tek PNG olarak yazmak geniş
// seçimlerde yüzlerce MB dosya + GB'larca RAM demekti; burada nokta başına ~128 px yama yazılır.
// Görüntü kenarında kırpılan yama BEYAZ ile doldurulur → nokta, model 224'e ölçeklemeden önce
// yamanın MERKEZİNDE kalır (kaydırılmış yama tahmini sessizce değiştirir).
def exportPatches = { imageData, File workDir, double targetMpp, double patchUm, List points, cal, Closure appendLine ->
    def server = imageData.getServer()
    double baseMpp = (cal != null) ? (cal.pw + cal.ph) / 2.0 : Double.NaN
    boolean calibrated = (cal != null) && Double.isFinite(baseMpp) && baseMpp > 0
    double downsample = 1.0
    if (calibrated && targetMpp > 0) { downsample = targetMpp / baseMpp; if (downsample < 1.0) downsample = 1.0 }
    // Yama kenarı taban piksel cinsinden; kalibre değilse taban piksel = hedef piksel varsayılır.
    int sideBase = (int) Math.max(8, Math.round(calibrated ? (patchUm / baseMpp) : (patchUm / targetMpp)))
    int outSide  = (int) Math.max(8, Math.round(sideBase / downsample))
    def dir = new File(workDir, 'atipik_patches')
    // Önceki çalıştırmanın yamaları birikmesin.
    try { if (dir.isDirectory()) dir.listFiles()?.each { if (it.isFile() && it.getName().endsWith('.png')) it.delete() } } catch (Throwable ignore) {}
    dir.mkdirs()
    def sb = new StringBuilder(); sb << '{"patch_px":' << outSide << ',"items":['
    int written = 0, skipped = 0
    points.eachWithIndex { a, i ->
        def roi = a.getROI(); double cx = roi.getCentroidX(), cy = roi.getCentroidY()
        int x0 = (int) Math.round(cx - sideBase / 2.0), y0 = (int) Math.round(cy - sideBase / 2.0)
        int ix0 = Math.max(0, x0), iy0 = Math.max(0, y0)
        int ix1 = Math.min(server.getWidth(), x0 + sideBase), iy1 = Math.min(server.getHeight(), y0 + sideBase)
        if (ix1 <= ix0 || iy1 <= iy0) { skipped++; return }
        def img = null
        try { img = server.readRegion(qupath.lib.regions.RegionRequest.createInstance(server.getPath(), downsample, ix0, iy0, ix1 - ix0, iy1 - iy0)) } catch (Throwable t) { img = null }
        if (img == null) { skipped++; return }
        def patch = new java.awt.image.BufferedImage(outSide, outSide, java.awt.image.BufferedImage.TYPE_INT_RGB)
        def g = patch.createGraphics()
        try {
            g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, outSide, outSide)
            g.drawImage(img, (int) Math.round((ix0 - x0) / downsample), (int) Math.round((iy0 - y0) / downsample), null)
        } finally { g.dispose() }
        def pf = new File(dir, String.format(java.util.Locale.US, 'p%05d.png', i))
        javax.imageio.ImageIO.write(patch, 'PNG', pf)
        // id = girdi nokta indeksi; köprü bunu aynen geri verir, sonuç bu anahtarla eşlenir.
        if (written > 0) sb << ','
        sb << String.format(java.util.Locale.US, '{"id":%d,"file":"%s","x":%.3f,"y":%.3f}', i, pf.getName(), cx, cy)
        written++
    }
    sb << ']}'
    if (written == 0) return [ok: false, error: workshopText('script.bcb3acdad519', 'Hiçbir yama kırpılamadı — noktalar görüntü sınırlarının dışında olabilir.')]
    def man = new File(dir, 'atipik_patches.json'); man.setText(sb.toString(), 'UTF-8')
    appendLine(String.format(java.util.Locale.US, workshopText('script.6541afe2e289', 'Yama: %,d adet · %d × %d px · downsample %.3f%s'),
        written, outSide, outSide, downsample, (skipped > 0 ? (workshopText('script.7826e004cc66', '  (kırpılamayan: ') + skipped + ')') : '')))
    return [ok: true, manifest: man, count: written, skipped: skipped, patchPx: outSide, downsample: downsample]
}

// ── Headless ──
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    def cfg = loadConfig(); def miss = configMissing(cfg)
    println "Atipik sınıflama (EffNetV2): python=${cfg.python ?: '(ayarsız)'} runner=${cfg.runner ?: '(ayarsız)'}"
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
            if (url == null) { javafx.application.Platform.runLater { Dialogs.showInfoNotification(workshopText('script.612821b4f218', 'Betik bulunamadı'), workshopText('script.9641266ba42d', 'Menüden açın: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi')) }; return }
            def cl = this.getClass().getClassLoader()
            try { cl = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getClassLoader() } catch (Throwable t) {}
            new GroovyShell(cl).evaluate(url.getText('UTF-8'), resourceName)
        } catch (Throwable t) { javafx.application.Platform.runLater { Dialogs.showErrorMessage(workshopText('script.e83c5641f38e', 'Açılamadı'), (t.getMessage() ?: t.getClass().getSimpleName())) } }
    } as Runnable).start()
}

def runLog = new StringBuilder()
def logFileRef = new java.util.concurrent.atomic.AtomicReference(null)
def resetLog = { -> synchronized (runLog) { runLog.setLength(0) } }
def appendLog = { String ln -> synchronized (runLog) { runLog.append(ln == null ? '' : ln).append('\n') } }
def logSnapshot = { -> synchronized (runLog) { return runLog.toString() } }
def autoSaveLog = { File dir, String base -> try { if (dir == null) return null; dir.mkdirs(); def f = new File(dir, (base ?: 'mitoz-atipik') + '_run.log'); f.setText(logSnapshot(), 'UTF-8'); logFileRef.set(f); return f } catch (Throwable t) { return null } }
def saveLogInteractive = {
    def txt = logSnapshot(); if (!txt?.trim()) { Dialogs.showInfoNotification(workshopText('script.301ef766a15b', 'Günlük'), workshopText('script.6d25a44e68f7', 'Kaydedilecek günlük yok.')); return }
    try {
        def suggested = logFileRef.get() ?: new File(System.getProperty('user.home'), 'mitoz-atipik_run.log')
        def f = qupath.fx.dialogs.FileChoosers.promptToSaveFile(stage, workshopText('script.ae2303e91c93', 'Çalışma günlüğünü kaydet'), suggested, new javafx.stage.FileChooser.ExtensionFilter(workshopText('script.c0f44ce4fc46', 'Günlük (*.log, *.txt)'), '*.log', '*.txt'))
        if (f != null) { f.setText(txt, 'UTF-8'); Dialogs.showInfoNotification(workshopText('script.301ef766a15b', 'Günlük'), workshopText('script.cbe9b591564d', 'Kaydedildi: ') + f.getAbsolutePath()) }
    } catch (Throwable t) { Dialogs.showErrorMessage(workshopText('script.301ef766a15b', 'Günlük'), workshopText('script.a0f73789f289', 'Kaydedilemedi: ') + (t.getMessage() ?: t.getClass().getSimpleName())) }
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
    try { proc = pb.start() } catch (Throwable e) { return [ok: false, exitCode: -1, error: workshopText('script.f0e1e3289660', 'Python başlatılamadı: ') + (e.getMessage() ?: e.getClass().getSimpleName())] }
    processRef.set(proc)
    def last = new java.util.ArrayDeque()
    try {
        def reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
        String line
        while ((line = reader.readLine()) != null) { last.addLast(line); while (last.size() > 80) last.pollFirst(); onLine(line); if (cancelledRef.get()) break }
        reader.close()
    } catch (Throwable ignore) {}
    boolean finished
    try { finished = proc.waitFor(PYTHON_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) } catch (InterruptedException ie) { proc.destroyForcibly(); return [ok: false, exitCode: -3, error: workshopText('script.dbd417da03e1', 'İptal edildi')] }
    if (!finished) { proc.destroyForcibly(); return [ok: false, exitCode: -2, error: workshopText('script.f87dda747be8', 'Zaman aşımı')] }
    if (cancelledRef.get()) { proc.destroyForcibly(); return [ok: false, exitCode: -3, error: workshopText('script.dbd417da03e1', 'İptal edildi')] }
    int code = proc.exitValue()
    return [ok: (code == 0), exitCode: code, lastLines: last.join('\n')]
}

def startSelftest = {
    persistFields(); def cfg = loadConfig(); def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set(workshopText('script.59d7792abf1b', 'Önce yapılandırmayı tamamlayın:\n  • ') + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(true); la.setStyle(MONO); logAreaRef.set(la)
    runPhaseRef.set(workshopText('script.8369fe631575', 'Bağımlılık kontrolü')); step.set('CHECK_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython([cfg.python, cfg.runner, 'selftest'], appendLine)
        javafx.application.Platform.runLater { selftestOkRef.set(r.ok); step.set('CHECK_DONE'); render() }
    }, 'AtolyeAtipik-Check')
    worker.setDaemon(true); worker.start()
}
def startModelDownload = {
    persistFields(); def cfg = loadConfig(); def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set(workshopText('script.59d7792abf1b', 'Önce yapılandırmayı tamamlayın:\n  • ') + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(true); la.setStyle(MONO); logAreaRef.set(la)
    runPhaseRef.set(workshopText('script.4177a3b67263', 'Model indiriliyor…')); step.set('DL_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython([cfg.python, cfg.runner, 'download'], appendLine)
        javafx.application.Platform.runLater { dlOkRef.set(r.ok); step.set('DL_DONE'); render() }
    }, 'AtolyeAtipik-Download')
    worker.setDaemon(true); worker.start()
}

// ── Sınıflama akışı ──
def startRun = {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set(workshopText('script.da80fab350d1', 'Görüntü açık değil.')); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def regions = selectedRegions(imageData)
    if (regions.isEmpty()) { errorTextRef.set(workshopText('script.adcdc59914b9', 'Bölge yok.\nÖnce bir alan anotasyonu çizin/seçin.')); step.set('ERROR'); render(); return }
    def regionRois = regions.collect { it.getROI() }
    def points = mitosisPointsIn(imageData, regionRois)
    if (points.isEmpty()) { errorTextRef.set(workshopText('script.b218e822da52', 'Sınıflanacak mitoz noktası yok.\nÖnce bir mitoz DEDEKTÖRÜ çalıştırın (KongNet / FCOS / RetinaNet ya da Karşılaştır).')); step.set('ERROR'); render(); return }
    def cal = pixelMicrons(imageData)
    double targetMpp = cfg.targetMpp?.trim() ? parseDoubleOr(cfg.targetMpp, TARGET_MPP) : TARGET_MPP
    double patchUm = cfg.patchUm?.trim() ? parseDoubleOr(cfg.patchUm, PATCH_UM) : PATCH_UM
    int patchPx = (int) Math.max(16, Math.round(patchUm / targetMpp))
    def workDir = resolveWorkDir(cfg, imageData); workDir.mkdirs()
    def base = imageNameOf(imageData)
    def outGeo = new File(workDir, base + '_atipik_out.geojson')
    cancelledRef.set(false); resetLog(); logFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(true); la.setStyle(MONO); logAreaRef.set(la)
    runPhaseRef.set(workshopText('script.074e464dc790', 'Hazırlanıyor…')); step.set('RUN_RUNNING'); render()

    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def setPhase = { String ph -> javafx.application.Platform.runLater { runPhaseRef.set(ph); render() } }
        try {
            appendLine('Model: ' + MODEL + workshopText('script.2a60e3747a97', '  ·  sınıflanacak nokta: ') + points.size() + workshopText('script.44abb14197d9', '  ·  yama: ') + patchPx + ' px (' + String.format(java.util.Locale.US, '%.1f µm @ %.2f µm/px', patchUm, targetMpp) + ')')
            if (cal == null) appendLine(workshopText('script.76d33521bf92', '⚠ Piksel boyutu kalibre değil — yama boyutu hedef-mpp varsayımıyla hesaplandı.'))
            setPhase(workshopText('script.7edf604febfd', 'Yamalar dışa aktarılıyor (1/2)…'))
            // Nokta centroid'leri değişmezdir → worker thread'den güvenle okunur.
            def exp = exportPatches(imageData, workDir, targetMpp, patchUm, points, cal, appendLine)
            if (!exp.ok) { javafx.application.Platform.runLater { errorTextRef.set(exp.error); step.set('ERROR'); render() }; return }
            if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set(workshopText('script.fee85e45842b', 'İptal edildi.')); step.set('ERROR'); render() }; return }
            def cmd = [cfg.python, cfg.runner, 'classify', '--patches', exp.manifest.getAbsolutePath(),
                       '--out', outGeo.getAbsolutePath(), '--device', (cfg.device ?: 'cuda')]
            setPhase(workshopText('script.3046e0ba5cff', 'EfficientNetV2 sınıflaması çalışıyor (2/2)…'))
            def r = runPython(cmd, appendLine)
            appendLine(workshopText('script.3510e56b50f2', '# Çıkış kodu: ') + r.exitCode)
            def savedLog = autoSaveLog(workDir, base)
            if (savedLog != null) appendLine(workshopText('script.f93ecddd706d', '# Günlük kaydedildi: ') + savedLog.getAbsolutePath())
            if (!r.ok) { javafx.application.Platform.runLater { errorTextRef.set(workshopText('script.465f4c5819e4', 'Sınıflama başarısız (çıkış: ') + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '') + (savedLog != null ? (workshopText('script.f8ae995ffb5b', '\n\nGünlük: ') + savedLog.getAbsolutePath()) : '')); step.set('ERROR'); render() }; return }
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
                    // Dedektörlerin görüntü halkaları (nokta ID'siyle bağlı) noktanın yeni sınıf rengini alır.
                    def ringsByPoint = [:]
                    hier.getAnnotationObjects().each { a -> def link = a.getMetadata().get(META_POINT); if (link != null) { if (!ringsByPoint.containsKey(link)) ringsByPoint[link] = []; ringsByPoint[link] << a } }
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
                            ringsByPoint[ann.getID().toString()]?.each { it.setColor((isAtyp ? atypClass : typClass).getColor()) }
                            if (Double.isFinite(pAtyp)) ann.measurements['Atipik olasılık'] = pAtyp
                            if (isAtyp) nAtyp++ else nTyp++
                        }
                    }
                    hier.fireHierarchyChangedEvent(hier)
                    try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
                    int tot = nAtyp + nTyp
                    def sb = new StringBuilder()
                    sb << workshopText('script.82367bf6b934', 'ATİPİK MİTOZ SINIFLAMA (EfficientNetV2)\n═══════════════════════════════════════\n\n')
                    sb << workshopText('script.13db0a0038b7', 'Lam        : ') << imageNameOf(imageData) << "\n"
                    sb << workshopText('script.d445d704109f', 'Sınıflanan   : ') << tot << workshopText('script.322f42480491', ' mitoz noktası\n')
                    sb << workshopText('script.6189e3826684', '  • Atipik   : ') << nAtyp << (tot > 0 ? String.format(java.util.Locale.US, "  (%.0f%%)", 100.0 * nAtyp / tot) : "") << "\n"
                    sb << workshopText('script.e862e3042554', '  • Tipik    : ') << nTyp << "\n\n"
                    sb << workshopText('script.ba3fc2922c90', 'Noktalar yeniden sınıflandırıldı: KIRMIZI = atipik, YEŞİL = tipik; \'Atipik olasılık\' ölçümü yazıldı.\n')
                    sb << workshopText('script.f93ccb62b791', 'Tahminleri görsel doğrulayın; klinik derece/yorum üretilmez.\n')
                    sb << workshopText('script.cf6099f0a005', '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')
                    if (savedLog != null) sb << workshopText('script.98abd255a614', '\n\nÇalışma günlüğü: ') << savedLog.getAbsolutePath()
                    resultTextRef.set(sb.toString()); step.set('RESULT'); render()
                } catch (Throwable t) { errorTextRef.set(workshopText('script.cd62cf56dc30', 'Sonuç işleme hatası: ') + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set(workshopText('script.6af403a9fc32', 'Beklenmeyen hata:\n') + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
        }
    }, 'AtolyeAtipik-Run')
    worker.setDaemon(true); worker.start()
}

// ── Ortam yöneticisinden bu sihirbaza dönüş ──────────────────────────────────
// "Python ortamı" düğmesi Atölye Python ortam yöneticisini bu kancayla (`atolyeReturnHook`) açar.
// Kurulum bitince yöneticideki "Sihirbaza dön ▶" (ya da yönetici penceresini kapatmak) bu pencereyi
// öne getirir, yapılandırmayı yeniden okur ve çalıştırma ekranına (READY) geçer. Çalışan bir işlem
// sürerken ekran değiştirilmez; yalnız pencere öne gelir.
def envReturnHook = [
    envId   : ENV_ID,
    wizard  : workshopText('script.cb403a4afdee', 'Atipik sınıflandırma (MIDOG25 EffNetV2)'),
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
                Dialogs.showErrorMessage(workshopText('script.95854db5b310', 'Sihirbaza dönüş'), t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
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
                javafx.application.Platform.runLater { Dialogs.showInfoNotification(workshopText('script.612821b4f218', 'Betik bulunamadı'),
                    workshopText('script.9641266ba42d', 'Menüden açın: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi')) }
                return
            }
            def cl = this.getClass().getClassLoader()
            try { cl = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getClassLoader() } catch (Throwable t) {}
            def shellBinding = new Binding()
            shellBinding.setVariable('atolyeReturnHook', envReturnHook)
            new GroovyShell(cl, shellBinding).evaluate(url.getText('UTF-8'), res)
        } catch (Throwable t) {
            javafx.application.Platform.runLater { Dialogs.showErrorMessage(workshopText('script.e83c5641f38e', 'Açılamadı'), (t.getMessage() ?: t.getClass().getSimpleName())) }
        }
    } as Runnable).start()
}
// ── Mitoz modelleri listesine dön ─────────────────────────────────────────────
// "◀ Mitoz listesi": bu pencereyi kapatır ve "Mitoz modelleri listesi"ni açar (başka bir model
// başlatmak için). Liste betiği bulunamazsa pencere açık kalır.
def openMitosisHub = { ->
    def hubScript = 'yardimci-mitoz-merkez.groovy'
    def url = null
    try { url = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getResource('/scripts/' + hubScript) } catch (Throwable t) {}
    if (url == null) url = this.getClass().getResource('/scripts/' + hubScript)
    if (url == null) {
        Dialogs.showInfoNotification(workshopText('script.f78f1962faba', 'Mitoz modelleri listesi'), workshopText('script.6c014450748f', 'Menüden açın: Extensions → Atölye → Modüller → Mitoz tespiti → Mitoz modelleri listesi'))
        return
    }
    launchBundledScript(hubScript)
    if (stage != null) stage.close()
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
    def addMonoArea = { String txt -> def ta = new javafx.scene.control.TextArea(txt ?: ''); ta.setEditable(false); ta.setWrapText(true); ta.setStyle(MONO); javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(ta) }
    def addWarnLabel = { String txt -> def lbl = new javafx.scene.control.Label(txt); wrapBind(lbl); lbl.setStyle('-fx-text-fill: #b8860b; -fx-font-weight: bold;'); center.getChildren().add(lbl) }
    def addLiveLog = { -> def la = logAreaRef.get(); if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) } }

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText(workshopText('script.6a2788fed365', 'Atipik sınıflama — çalışma zamanı gerekli'))
        def miss = configMissing(cfg)
        addGuidance(workshopText('script.d339d2a46b88', 'Bu modül torch + timm ortamını (env id: midog-atypical) gerektirir.\nEksik/geçersiz:\n  • ') + (miss.isEmpty() ? workshopText('script.715392eca0f8', '(yok)') : miss.join('\n  • ')) +
            workshopText('script.a446e942c76e', '\n\nKurulum: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi → "MIDOG25 EffNetV2 — atipik sınıflandırıcı".\nKöprü: handson/python/midog/atypical_effnet.py'))
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() })); actions.add(navButton(workshopText('script.719f58ad7970', '⚙ Python ortamını kur/aç'), { launchEnvManager() }, workshopText('script.d48b765b668a', 'Atölye Python ortam yöneticisini açar → "MIDOG25 EffNetV2"yi kurun'))); actions.add(navButton(workshopText('script.3efe57ecbec5', 'Yapılandır ▶'), { step.set('CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText(workshopText('script.327f4ae1f23a', 'Atipik sınıflama — yapılandırma'))
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: ''); def rnField = new javafx.scene.control.TextField(cfg.runner ?: ''); def wdField = new javafx.scene.control.TextField(cfg.workDir ?: '')
        def tmppField = new javafx.scene.control.TextField(cfg.targetMpp ?: ''); tmppField.setPromptText(workshopText('script.388c69411b39', 'boş = 0.25'))
        def patchField = new javafx.scene.control.TextField(cfg.patchUm ?: ''); patchField.setPromptText(workshopText('script.e4c278b0c6c4', 'boş = 32'))
        [pyField, rnField, wdField].each { it.setPrefColumnCount(36) }; [tmppField, patchField].each { it.setPrefColumnCount(8) }
        def deviceChoice = new javafx.scene.control.ChoiceBox(); ['cuda', 'cpu'].each { deviceChoice.getItems().add(it) }; deviceChoice.setValue((cfg.device == 'cpu') ? 'cpu' : 'cuda')
        pyFieldRef.set(pyField); runnerFieldRef.set(rnField); workFieldRef.set(wdField); deviceChoiceRef.set(deviceChoice); tmppFieldRef.set(tmppField); patchFieldRef.set(patchField)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, workshopText('script.8622b674942a', 'Dosya seç')); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir  = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, workshopText('script.15f8cfe83dc9', 'Dizin seç'), null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Model:'), new javafx.scene.control.Label(MODEL + workshopText('script.cb60f7ba43c4', ' (sabit)')))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (.venv/python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(workshopText('script.541544ff0809', 'Köprü (atypical_effnet.py):')), rnField, navButton('…', { browseFile(rnField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(workshopText('script.2719ca64ba94', 'Çalışma dizini (ops.):')), wdField, navButton('…', { browseDir(wdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(workshopText('script.d4336c3867bc', 'Cihaz:')), deviceChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(workshopText('script.c353a87a343a', 'Hedef çözünürlük (µm/px):')), tmppField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(workshopText('script.afc01d2888e6', 'Yama boyutu (µm):')), patchField)
        center.getChildren().add(grid)
        def mcf = modelCacheFile()
        def mcLbl = new javafx.scene.control.Label(mcf.isFile() ? (workshopText('script.8d6e67c0e1b6', '✓ Yerel model VAR: ') + mcf.getAbsolutePath()) : workshopText('script.c58019b14294', '○ Yerel model yok — "Modeli yerel indir" ile bir kez indirin (~213 MB).'))
        mcLbl.setWrapText(true); mcLbl.setMaxWidth(Double.MAX_VALUE); mcLbl.setStyle('-fx-opacity: 0.85; -fx-font-size: 11px;'); center.getChildren().add(mcLbl)
        addGuidance(workshopText('script.d2b5efebebf2', 'Bu bir DEDEKTÖR değildir: mevcut "Mitosis"/"Mitoz (konsensüs)" noktalarını tipik/atipik olarak sınıflar. Ağırlık paketlenmez; "Modeli yerel indir" v1.0.0 yayınından çeker (LİSANS yok → araştırma/eğitim). Yama boyutu: her mitoz çevresinde kırpılan alan (µm); referans ~32 µm (≈128 px @ 40x). Hedef çözünürlük varsayılanı 0.25 (T2 eğitim ölçeği).'))
        actions.add(navButton(workshopText('script.72278748133a', 'İptal'), { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton(workshopText('script.edfc11aab1a1', '⚙ Python ortamı'), { launchEnvManager() }, workshopText('script.9569e7820482', 'Atölye Python ortam yöneticisini aç')))
        actions.add(navButton(workshopText('script.3352626164a4', 'Modeli yerel indir'), { startModelDownload() }))
        actions.add(navButton(workshopText('script.8369fe631575', 'Bağımlılık kontrolü'), { startSelftest() }))
        actions.add(navButton(workshopText('script.96f6bd4b06cc', 'Kaydet ▶'), { persistFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
    } else if (cur == 'CHECK_RUNNING') {
        title.setText(workshopText('script.8e84f306c3c6', 'Bağımlılık kontrolü çalışıyor…')); center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton(workshopText('script.3f6920a9ead3', 'İptal et'), { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'CHECK_DONE') {
        title.setText(selftestOkRef.get() ? workshopText('script.93f447fe25d4', 'Bağımlılık kontrolü tamam ✅') : workshopText('script.5557b2e2bc01', '⚠ Bağımlılık kontrolü BAŞARISIZ — günlüğe bakın')); addLiveLog()
        actions.add(navButton(workshopText('script.c8b5f18cded5', '◀ Yapılandırmaya dön'), { step.set('CONFIG'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton(workshopText('script.bba9c203d381', 'Günlüğü kaydet…'), { saveLogInteractive() }))
        if (selftestOkRef.get()) actions.add(navButton(workshopText('script.2065029c612d', 'Çalıştırma ekranına dön ▶'), { step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }, workshopText('script.a61a0e32a390', 'Kontrol tamam — bölgede çalıştırma ekranına döner')))
    } else if (cur == 'DL_RUNNING') {
        title.setText(workshopText('script.4177a3b67263', 'Model indiriliyor…')); addGuidance(workshopText('script.f0435ad31a2e', 'EfficientNetV2-M ağırlığı (~213 MB) veri kökü altına indiriliyor.')); center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton(workshopText('script.3f6920a9ead3', 'İptal et'), { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'DL_DONE') {
        title.setText(dlOkRef.get() ? workshopText('script.393975cd47cc', 'Model indirildi ✅') : workshopText('script.302555a8f8cc', '⚠ İndirilemedi — günlüğe bakın')); addLiveLog()
        actions.add(navButton(workshopText('script.c8b5f18cded5', '◀ Yapılandırmaya dön'), { step.set('CONFIG'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton(workshopText('script.bba9c203d381', 'Günlüğü kaydet…'), { saveLogInteractive() }))
        if (dlOkRef.get()) actions.add(navButton(workshopText('script.2065029c612d', 'Çalıştırma ekranına dön ▶'), { step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }, workshopText('script.760bddd41f88', 'İndirme tamam — bölgede çalıştırma ekranına döner')))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText(workshopText('script.b4c901dc184b', 'Görüntü açık değil')); addGuidance(workshopText('script.3c9b707a1d9e', 'Önce bir H&E lamı açıp bir dedektör çalıştırın, ilgi ALANINI seçin, sonra "⟳ Yenile".'))
            actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() })); actions.add(navButton(workshopText('script.b528a2cc114e', 'Yapılandır'), { step.set('CONFIG'); render() })); actions.add(navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }))
        } else {
            def regions = selectedRegions(imageData); def regionRois = regions.collect { it.getROI() }
            def points = mitosisPointsIn(imageData, regionRois); def cal = pixelMicrons(imageData)
            title.setText(workshopText('script.186b725ffb20', 'Atipik mitoz sınıflama — EfficientNetV2'))
            def sb = new StringBuilder()
            sb << workshopText('script.c482c0d5e596', 'Lam          : ') << imageNameOf(imageData) << "\n"
            sb << "Model          : " << MODEL << "\n"
            sb << workshopText('script.3b7d627f2bc5', 'Cihaz          : ') << (cfg.device ?: 'cuda') << "\n"
            sb << workshopText('script.d07fd70da9fc', 'Yerel model    : ') << (modelCacheFile().isFile() ? workshopText('script.b0398cdfc1c0', 'VAR') : workshopText('script.c8fae458bc81', 'yok — "Modeli yerel indir"')) << "\n"
            sb << String.format(java.util.Locale.US, workshopText('script.0f30470dbbba', 'Seçili bölge   : %,d alan anotasyonu%n'), regions.size())
            sb << String.format(java.util.Locale.US, workshopText('script.896b06e338c3', 'Sınıflanacak   : %,d mitoz noktası%n'), points.size())
            sb << workshopText('script.5f3a7bf05b28', 'Kalibrasyon    : ') << (cal != null ? String.format(java.util.Locale.US, '%.3f µm/px', (cal.pw + cal.ph) / 2.0) : workshopText('script.167d3bfed570', 'KALİBRE DEĞİL')) << "\n"
            addMonoArea(sb.toString())
            addGuidance(workshopText('script.50a3d37ce892', 'Bu bir DEDEKTÖR değildir: seçili bölgedeki mevcut mitoz noktalarını (dedektör çıktısı / konsensüs) tipik vs atipik olarak sınıflar. Sonuç: noktalar (ve çevrelerindeki etiketli görüntü halkaları) KIRMIZI (atipik) / YEŞİL (tipik) yeniden renklenir + "Atipik olasılık" ölçümü (noktada; halka yalnız görüntüdür).'))
            if (points.isEmpty()) addWarnLabel(workshopText('script.a2477cfc9755', '⚠ Sınıflanacak mitoz noktası yok — önce bir mitoz dedektörü (KongNet / FCOS / RetinaNet ya da Karşılaştır) çalıştırın.'))
            boolean canRun = configComplete(cfg) && points.size() >= 1
            if (!configComplete(cfg)) addWarnLabel(workshopText('script.f8447d25667b', '⚠ Python ortamı (midog-atypical) kurulu değil — "⚙ Python ortamını kur/aç" ile kurun.'))
            actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
            if (!configComplete(cfg)) actions.add(navButton(workshopText('script.719f58ad7970', '⚙ Python ortamını kur/aç'), { launchEnvManager() }, workshopText('script.b70b9a59c9eb', 'Atölye Python ortam yöneticisini açar')))
            actions.add(navButton(workshopText('script.b528a2cc114e', 'Yapılandır'), { step.set('CONFIG'); render() })); actions.add(navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }))
            def runBtn = navButton(workshopText('script.bf8e5ce730b0', 'Sınıfla ▶'), { startRun() }, workshopText('script.d213e3279390', 'Seçili bölgedeki mitoz noktalarını tipik/atipik sınıfla')); runBtn.setDisable(!canRun)
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING') {
        title.setText(runPhaseRef.get()); addGuidance(workshopText('script.3732066df69d', 'EfficientNetV2 sınıflaması çalışıyor.')); center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton(workshopText('script.3f6920a9ead3', 'İptal et'), { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
        actions.add(navButton(workshopText('script.bba9c203d381', 'Günlüğü kaydet…'), { saveLogInteractive() }))
    } else if (cur == 'RESULT') {
        title.setText(workshopText('script.c00e74c04c57', 'Sınıflama tamam ✅')); addMonoArea(resultTextRef.get())
        addGuidance(workshopText('script.859d7ffa965f', 'Noktalar Annotations panelinde "Mitoz (atipik)" (kırmızı) / "Mitoz (tipik)" (yeşil) sınıflarında; sınıfları aç/kapat ederek inceleyin.'))
        def lf = logFileRef.get(); if (lf != null) addGuidance(workshopText('script.ecfba16bfe62', 'Çalışma günlüğü: ') + lf.getAbsolutePath())
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() })); actions.add(navButton(workshopText('script.a8bcca42d9ce', 'Kopyala'), { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton(workshopText('script.bba9c203d381', 'Günlüğü kaydet…'), { saveLogInteractive() }))
        actions.add(navButton(workshopText('script.171029fff08d', '↻ Yeniden'), { step.set('READY'); render() }))
    } else {
        title.setText(workshopText('script.307b9dc7912d', 'Hata')); addMonoArea(errorTextRef.get())
        actions.add(navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('READY'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton(workshopText('script.bba9c203d381', 'Günlüğü kaydet…'), { saveLogInteractive() }))
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
    }

    def topChk = new javafx.scene.control.CheckBox(workshopText('script.70ca64c0c43a', 'Üstte tut')); topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n -> alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
    def spacer = new javafx.scene.layout.Region(); javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8); bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk)
    // Çalışan işlem yokken: bu pencereyi kapatıp mitoz modelleri listesine dön (başka bir model başlatmak için).
    if (!['RUN_RUNNING', 'CHECK_RUNNING', 'DL_RUNNING', 'BUSY'].contains(cur)) bar.getChildren().add(navButton(workshopText('script.b92ed056af0e', '◀ Mitoz listesi'), { openMitosisHub() }, workshopText('script.aa26895608db', 'Bu pencereyi kapatıp mitoz modelleri listesini açar — başka bir model başlatmak için')))
    bar.getChildren().add(spacer); bar.getChildren().addAll(actions)
    def disclaimer = new javafx.scene.control.Label(workshopText('script.04e70fc651a3', 'Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.'))
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
        stage.setTitle(workshopText('script.6ccb162c3cba', 'Atipik mitoz sınıflama — EfficientNetV2 (MIDOG25 Track-2)')); stage.setAlwaysOnTop(alwaysTop.get())
        render(); stage.show()
    } catch (Throwable t) { Dialogs.showErrorMessage(workshopText('script.7fd583bc0c88', 'Sihirbaz açılamadı'), workshopText('script.9aac4912cb86', 'Bu araç başlatılamadı.\n\nYapabilecekleriniz:\n  1. Araç açık bir lam istiyorsa önce lamı açın.\n  2. QuPath\'i tamamen kapatıp yeniden açın ve tekrar deneyin.\n  3. Sorun sürerse View → Show log penceresinin ekran görüntüsünü atölye düzenleyicisine gönderin.\n\nTeknik ayrıntı: ') + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: workshopText('script.e136f4a3bb45', '(ileti yok)'))) }
}
println "✓ Atipik mitoz sınıflama (EfficientNetV2 · MIDOG25 Track-2) sihirbazı açıldı."

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
