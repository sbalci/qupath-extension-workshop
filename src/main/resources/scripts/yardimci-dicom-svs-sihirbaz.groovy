/**
 * Yardımcı - DICOM → SVS Dönüştürme Sihirbazı (DICOMtoSVS köprüsü, tek pencere)
 * -----------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   DICOM-WSI slaytlarını SVS-benzeri piramidal TIFF'e çevirir — Bertrand
 *   Chauveau'nun açık kaynaklı **DICOMtoSVS** aracını (pydicom + tifffile +
 *   imagecodecs) QuPath içinden sürer. Dönüşüm KAYIPSIZDIR: JPEG/JPEG2000
 *   karoları yeniden kodlanmadan kopyalanır; piramit düzeyleri ve ICC profili
 *   korunur. Parlak alan + singlepleks floresan (MONOCHROME2 → FITC renk
 *   haritası) desteklenir; multipleks/z-stack ve JPEG/J2K dışı sıkıştırma
 *   araç tarafından tanınıp ATLANIR.
 *   İki mod:
 *     1. PYTHON KÖPRÜSÜ (önerilen) — seçenekler bu pencerede, ilerleme canlı
 *        konsolda; bitince dönüştürülen dosyalar QuPath projesine aktarılabilir
 *        (ProjectCommands.promptToImportImages).
 *     2. RESMÎ UYGULAMA — indirdiğiniz DICOMtoSVS uygulamasını (exe) başlatır;
 *        araç kendi penceresini ve konsolunu açar.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Yalnız BİÇİM dönüştürür — piksel verisi aynı kalır; hiçbir sayım, skor,
 *     sınıf ya da klinik yorum üretmez.
 *   • LİSANS: DICOMtoSVS deposunda lisans dosyası YOKTUR — bu yüzden aracın
 *     kodu eklentiyle PAKETLENMEZ; sihirbaz `DICOMtoSVS.py` betiğini çalışma
 *     anında, SABİTLENMİŞ sürümden, sizin adınıza GitHub'dan indirir. Kurum
 *     cihazında kullanmadan önce BT onayı alın; yalnız güvendiğiniz
 *     kaynaklardan gelen DICOM dosyalarını işleyin.
 *
 * KULLANIM:
 *   1. Python ortamını kurun: [Extensions → Atölye → Yardımcılar → Python
 *      köprüleri ve temel modeller → Atölye Python ortam yöneticisi] →
 *      "DICOMtoSVS" ortamı (hafif; torch gerekmez).
 *   2. [Extensions → Atölye → Yardımcılar → İçe ve dışa aktarma / veri →
 *      DICOM → SVS dönüştürme (DICOMtoSVS)]
 *   3. İlk açılışta "İndir/Güncelle" ile betiği indirin (bir kez).
 *   4. Girdi klasörünü seçin (her slayt kendi alt klasöründe ya da kendi .zip
 *      dosyasında), seçenekleri işaretleyin, "Dönüştür ▶".
 *   5. Sonuç ekranından "Projeye aktar" ya da "Klasörü aç".
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Chauveau B (2025), Virchows Archiv — Converting whole slide images from
 *     DICOM to ScanScope Virtual Slide-like TIFF. doi:10.1007/s00428-025-04135-0
 *   • Araç: https://github.com/bertrandchauveau/DICOMtoSVS  (lisans dosyası yok)
 *   • Güvenlik: Desjardins B ve ark. (2020), AJR. doi:10.2214/AJR.19.21958
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ────────────────────────────────────────────────────────────────
long PYTHON_TIMEOUT_SECONDS = 7200L          // çok GB'lık toplu dönüşüm — cömert üst sınır
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
// Depo lisanssız → sürüm SABİTLENİR: davranış, depo değişse de aynı kalır.
// Sabit sürümü güncellemek bilinçli bir depo düzenlemesidir (bkz. Ekler → WSI Dosya Formatları).
def PIN_SHA  = '603528ba9e2e37b0f9f8072cbc84368a9a6fcf99'   // 2026-08-15
def RAW_URL  = 'https://raw.githubusercontent.com/bertrandchauveau/DICOMtoSVS/' + PIN_SHA + '/DICOMtoSVS.py'
def REPO_URL = 'https://github.com/bertrandchauveau/DICOMtoSVS'

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır) ──
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/dicomtosvs')
def PREF_PYTHON = 'python'
def PREF_SCRIPT = 'script'
def PREF_INPUT  = 'inputDir'
def PREF_ZIP    = 'isZipped'
def PREF_LABEL  = 'label'
def PREF_MACRO  = 'macro'
def PREF_ANON   = 'anonymize'
def PREF_TAGS   = 'addTags'
def PREF_EXE    = 'exePath'

// Atölye veri kökü (env yöneticisiyle PAYLAŞILAN; öntanımlı ~/.atolye — C:).
def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
def toolDir = { -> new File(new File(atolyeDataRoot(), 'runtimes'), 'dicomtosvs') }
def defaultScriptFile = { -> new File(toolDir(), 'DICOMtoSVS.py') }
def driverFile = { -> new File(toolDir(), 'atolye_dicomtosvs_driver.py') }

def loadConfig = { ->
    [ python   : ({ -> def __p = prefs.get(PREF_PYTHON, ''); if (__p?.trim()) return __p; def __r = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('py.dicomtosvs', ''); if (__r?.trim() && new File(__r.trim()).isFile()) return __r.trim(); def __v = new File(toolDir(), '.venv'); def __w = new File(__v, 'Scripts/python.exe'); def __n = new File(__v, 'bin/python'); __w.isFile() ? __w.getAbsolutePath() : (__n.isFile() ? __n.getAbsolutePath() : '') }).call(),
      script   : ({ -> def __s = prefs.get(PREF_SCRIPT, ''); (__s?.trim()) ? __s.trim() : defaultScriptFile().getAbsolutePath() }).call(),
      inputDir : prefs.get(PREF_INPUT, ''),
      isZipped : prefs.getBoolean(PREF_ZIP,   false),
      label    : prefs.getBoolean(PREF_LABEL, false),
      macro    : prefs.getBoolean(PREF_MACRO, false),
      anonymize: prefs.getBoolean(PREF_ANON,  false),
      addTags  : prefs.getBoolean(PREF_TAGS,  false),
      exePath  : prefs.get(PREF_EXE, '') ]
}

// Zorunlu (köprü modu): python.exe + DICOMtoSVS.py
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile())
        miss << 'Python yürütülebilir (python.exe) — ortam yöneticisinden "DICOMtoSVS" ortamını kurun'
    if (!cfg.script?.trim() || !(new File(cfg.script)).isFile())
        miss << 'DICOMtoSVS.py betiği — "İndir/Güncelle" ile indirin (ya da elle indirip seçin)'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

// Girdi klasörünün yanına yazılan çıktı klasörü (aracın kendi kuralı: <klasör>_output)
def outputDirOf = { String inputDir ->
    def f = new File(inputDir)
    return new File(f.getParentFile() ?: f, f.getName() + '_output')
}
def listConverted = { File outDir ->
    if (!outDir.isDirectory()) return []
    def files = outDir.listFiles({ File f -> f.isFile() && (
        f.getName().toLowerCase(java.util.Locale.ROOT).endsWith('.svs') ||
        f.getName().toLowerCase(java.util.Locale.ROOT).endsWith('.tif') ||
        f.getName().toLowerCase(java.util.Locale.ROOT).endsWith('.tiff')) } as java.io.FileFilter)
    return (files == null) ? [] : (files.toList().sort { it.getName() })
}

// ── DICOMtoSVS.py indirme (SHA-sabitli; kullanıcı adına, çalışma anında) ─────
def downloadScript = { ->
    try {
        def client = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(30)).build()
        def req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(RAW_URL))
            .timeout(java.time.Duration.ofSeconds(120)).GET().build()
        def resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() != 200)
            return [ok: false, error: 'İndirme başarısız: HTTP ' + resp.statusCode() + '\n' + RAW_URL]
        byte[] bytes = resp.body()
        def text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
        if (!text.contains('def from_DICOM_to_SVS'))
            return [ok: false, error: 'İndirilen dosya beklenen işlevi (from_DICOM_to_SVS) içermiyor — sabitlenmiş sürüm bozulmuş olabilir.']
        def dest = defaultScriptFile()
        dest.getParentFile().mkdirs()
        def tmp = new File(dest.getParentFile(), dest.getName() + '.tmp')
        tmp.bytes = bytes
        java.nio.file.Files.move(tmp.toPath(), dest.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return [ok: true, size: bytes.length, dest: dest.getAbsolutePath()]
    } catch (Throwable t) {
        return [ok: false, error: 'İndirme hatası: ' + (t.getMessage() ?: t.getClass().getSimpleName()) +
            '\nİnternet yoksa betiği elle indirip "Yapılandır" ekranından seçin:\n' + REPO_URL]
    }
}

// ── Sürücü betiği (BİZİM kodumuz; her çalıştırmada üzerine yazılır) ────────────────
// DICOMtoSVS.py'yi içe aktarır (GUI'si __main__ korumasının arkasında — açılmaz)
// ve from_DICOM_to_SVS(...) işlevini komut satırı bayraklarıyla çağırır.
// Çift tırnak YOK (Windows ProcessBuilder tırnak tuzağına karşı tek tırnak).
def DRIVER_CODE = '''# -*- coding: utf-8 -*-
# Atolye QuPath calisma-ani surucusu (atölye kodu; DICOMtoSVS deposundan DEĞİL).
# Kullanim: python -u atolye_dicomtosvs_driver.py <DICOMtoSVS.py> <klasor> <zip> <label> <macro> <anon> <tags>
import sys, importlib.util

def as_bool(s):
    return s == '1'

def main():
    if len(sys.argv) != 8:
        print('HATA: 7 arguman beklenir (betik, klasor, 5 bayrak)')
        return 2
    script, folder = sys.argv[1], sys.argv[2]
    z, lab, mac, anon, tags = (as_bool(a) for a in sys.argv[3:8])
    spec = importlib.util.spec_from_file_location('dicomtosvs', script)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    mod.from_DICOM_to_SVS(folder, z, lab, mac, anon, tags)
    print('ATOLYE_DONE')
    return 0

if __name__ == '__main__':
    sys.exit(main())
'''
def writeDriver = { ->
    def f = driverFile()
    f.getParentFile().mkdirs()
    f.setText(DRIVER_CODE, 'UTF-8')
    return f
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
// CONFIG_INCOMPLETE | CONFIG | CHECK_RUNNING | CHECK_DONE | READY | RUNNING | RESULT | ERROR | EXE
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def processRef    = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def resultDirRef  = new java.util.concurrent.atomic.AtomicReference(null)   // File — çıktı klasörü
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
// CONFIG / READY düzenleme alanları
def pyFieldRef    = new java.util.concurrent.atomic.AtomicReference(null)
def scriptFieldRef= new java.util.concurrent.atomic.AtomicReference(null)
def inputFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def zipChkRef     = new java.util.concurrent.atomic.AtomicReference(null)
def labelChkRef   = new java.util.concurrent.atomic.AtomicReference(null)
def macroChkRef   = new java.util.concurrent.atomic.AtomicReference(null)
def anonChkRef    = new java.util.concurrent.atomic.AtomicReference(null)
def tagsChkRef    = new java.util.concurrent.atomic.AtomicReference(null)
def exeFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
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

// ── Headless: yapılandırmayı raporla, etkileşim yok ─────────────────────────
if (isHeadless) {
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    println "DICOM→SVS yapılandırması: python=${cfg.python ?: '(ayarsız)'} betik=${cfg.script ?: '(ayarsız)'}"
    println "Sabitlenmiş sürüm: ${PIN_SHA} (2026-08-15) — ${REPO_URL}"
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(' · ')}"
    if (cfg.inputDir?.trim()) println "Son girdi klasörü: ${cfg.inputDir}"
    println "DICOM→SVS sihirbazı için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Alanları prefs'e yaz ─────────────────────────────────────────────────────
def textOf = { ref -> def f = ref.get(); return (f != null ? f.getText() : '').trim() }
def boolOf = { ref, boolean d -> def c = ref.get(); return (c != null) ? c.isSelected() : d }
def persistConfigFields = {
    if (pyFieldRef.get() != null)     prefs.put(PREF_PYTHON, textOf(pyFieldRef))
    if (scriptFieldRef.get() != null) prefs.put(PREF_SCRIPT, textOf(scriptFieldRef))
    try { prefs.flush() } catch (Throwable ignore) {}
}
def persistRunFields = {
    if (inputFieldRef.get() != null) prefs.put(PREF_INPUT, textOf(inputFieldRef))
    prefs.putBoolean(PREF_ZIP,   boolOf(zipChkRef,   false))
    prefs.putBoolean(PREF_LABEL, boolOf(labelChkRef, false))
    prefs.putBoolean(PREF_MACRO, boolOf(macroChkRef, false))
    prefs.putBoolean(PREF_ANON,  boolOf(anonChkRef,  false))
    prefs.putBoolean(PREF_TAGS,  boolOf(tagsChkRef,  false))
    try { prefs.flush() } catch (Throwable ignore) {}
}

// ── Python süreci (ProcessBuilder, List-form) → satır akışı ─────────────────
def runPython = { List cmd, Closure onLine ->
    def pb = new ProcessBuilder(cmd)
    pb.redirectErrorStream(true)
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

// ── Bağımlılık kontrolü (pydicom yığını) ────────────────────────────────────
// Python tek-satırında ÇİFT tırnak kullanma (Windows ProcessBuilder içteki çift
// tırnakları kaçırmaz); tek tırnak güvenlidir.
def selftestCmd = { cfg ->
    return [cfg.python, '-c',
        'import pydicom, tifffile, imagecodecs, natsort, numpy, PIL, pylibjpeg; print(\'VERIFY_OK: pydicom \' + pydicom.__version__)']
}
def startSelftest = {
    persistConfigFields()
    def cfg = loadConfig()
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile()) {
        errorTextRef.set('Önce Python ortamını kurun/seçin (ortam yöneticisi → "DICOMtoSVS").'); step.set('ERROR'); render(); return
    }
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    step.set('CHECK_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython(selftestCmd(cfg), appendLine)
        if (!r.ok && r.error) appendLine(r.error)
        javafx.application.Platform.runLater { step.set('CHECK_DONE'); render() }
    }, 'AtolyeDicomSvs-Check')
    worker.setDaemon(true); worker.start()
}

// ── İndir/Güncelle akışı ────────────────────────────────────────────────────
def startDownload = {
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    step.set('CHECK_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        appendLine('DICOMtoSVS.py indiriliyor (sabitlenmiş sürüm ' + PIN_SHA.substring(0, 7) + ', 2026-08-15)…')
        appendLine(RAW_URL)
        def r = downloadScript()
        if (r.ok) {
            appendLine('✓ İndirildi: ' + r.dest + '  (' + r.size + ' bayt)')
            prefs.remove(PREF_SCRIPT)   // öntanımlı konuma dönüldü
            try { prefs.flush() } catch (Throwable ignore) {}
        } else {
            appendLine('✗ ' + r.error)
        }
        javafx.application.Platform.runLater { step.set('CHECK_DONE'); render() }
    }, 'AtolyeDicomSvs-Download')
    worker.setDaemon(true); worker.start()
}

// ── Dönüştürme akışı ────────────────────────────────────────────────────────
def startRun = {
    persistRunFields()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set('Önce yapılandırmayı tamamlayın:\n  • ' + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    def inDirTxt = cfg.inputDir?.trim()
    if (!inDirTxt) { errorTextRef.set('Girdi klasörü seçilmedi.'); step.set('ERROR'); render(); return }
    def inDir = new File(inDirTxt)
    if (!inDir.isDirectory()) { errorTextRef.set('Girdi klasörü bulunamadı:\n' + inDirTxt); step.set('ERROR'); render(); return }
    def kids = inDir.listFiles()
    if (kids == null || kids.length == 0) { errorTextRef.set('Girdi klasörü boş:\n' + inDirTxt); step.set('ERROR'); render(); return }
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    step.set('RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def driver
        try { driver = writeDriver() }
        catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Sürücü betiği yazılamadı:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }; return
        }
        appendLine('Girdi  : ' + inDir.getAbsolutePath())
        appendLine('Çıktı  : ' + outputDirOf(inDirTxt).getAbsolutePath())
        appendLine('Seçenekler: zip=' + cfg.isZipped + ' etiket=' + cfg.label + ' makro=' + cfg.macro +
            ' anonim=' + cfg.anonymize + ' DICOM-etiketleri=' + cfg.addTags)
        appendLine('─'.multiply(60))
        // '-u': Python stdout arabelleğini kapat → ilerleme satırları canlı aksın.
        def cmd = [cfg.python, '-u', driver.getAbsolutePath(), cfg.script, inDir.getAbsolutePath(),
                   cfg.isZipped ? '1' : '0', cfg.label ? '1' : '0', cfg.macro ? '1' : '0',
                   cfg.anonymize ? '1' : '0', cfg.addTags ? '1' : '0']
        def r = runPython(cmd, appendLine)
        def outDir = outputDirOf(inDirTxt)
        def converted = listConverted(outDir)
        javafx.application.Platform.runLater {
            if (!r.ok && converted.isEmpty()) {
                errorTextRef.set('Dönüştürme başarısız (çıkış: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: ''))
                step.set('ERROR'); render()
            } else {
                def sb = new StringBuilder()
                sb << 'DICOM → SVS DÖNÜŞTÜRME — ÖZET\n'
                sb << '═══════════════════════════════\n\n'
                sb << 'Girdi klasörü : ' << inDir.getAbsolutePath() << '\n'
                sb << 'Çıktı klasörü : ' << outDir.getAbsolutePath() << '\n'
                sb << 'Dönüştürülen  : ' << converted.size() << ' dosya\n'
                if (!r.ok)
                    sb << '\n⚠ Süreç ' << r.exitCode << ' koduyla bitti — bazı slaytlar atlanmış/başarısız olabilir (konsola bakın).\n'
                sb << '\nDosyalar:\n'
                converted.take(30).each { f -> sb << '  • ' << f.getName() << '\n' }
                if (converted.size() > 30) sb << '  … (+' << (converted.size() - 30) << ' dosya)\n'
                sb << '\nDesteklenmeyen slaytlar (multipleks, z-stack, JPEG/J2K dışı) araç tarafından atlanır.\n'
                sb << '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'
                resultTextRef.set(sb.toString())
                resultDirRef.set(outDir)
                step.set('RESULT'); render()
            }
        }
    }, 'AtolyeDicomSvs-Run')
    worker.setDaemon(true); worker.start()
}

// ── Dönüştürülenleri projeye aktar (QuPath'in kendi içe aktarma diyaloğu) ────
def importToProject = {
    def outDir = resultDirRef.get()
    def converted = (outDir != null) ? listConverted(outDir) : []
    if (converted.isEmpty()) { Dialogs.showWarningNotification('DICOM → SVS', 'Aktarılacak dönüştürülmüş dosya bulunamadı.'); return }
    if (QP.getProject() == null) { Dialogs.showWarningNotification('DICOM → SVS', 'Önce bir proje açın (File → Project…).'); return }
    try {
        String[] paths = converted.collect { it.getAbsolutePath() } as String[]
        qupath.lib.gui.commands.ProjectCommands.promptToImportImages(gui, paths)
    } catch (Throwable t) {
        Dialogs.showErrorMessage('DICOM → SVS', 'İçe aktarma başlatılamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName()))
    }
}
def openFolder = { File dir ->
    try {
        if (dir != null && dir.isDirectory() && java.awt.Desktop.isDesktopSupported())
            java.awt.Desktop.getDesktop().open(dir)
        else
            Dialogs.showWarningNotification('DICOM → SVS', 'Klasör açılamadı: ' + (dir != null ? dir.getAbsolutePath() : '(yok)'))
    } catch (Throwable t) {
        Dialogs.showWarningNotification('DICOM → SVS', 'Klasör açılamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName()))
    }
}

// ── Resmî uygulamayı (exe) başlat — Warpy başlatıcı deseni ──────────────────
def launchExe = {
    def p = textOf(exeFieldRef)
    if (!p || !(new File(p)).isFile()) {
        Dialogs.showWarningNotification('DICOM → SVS', 'Önce indirdiğiniz DICOMtoSVS uygulamasını seçin.'); return
    }
    prefs.put(PREF_EXE, p)
    try { prefs.flush() } catch (Throwable ignore) {}
    try {
        // ProcessBuilder List<String> — önceden tırnaklanmış komut dizesi KULLANMA (boşluklu yol güvenli).
        new ProcessBuilder([p]).start()
        Dialogs.showInfoNotification('DICOM → SVS', 'Resmî uygulama başlatıldı — kendi penceresini ve konsolunu açar.')
    } catch (Throwable t) {
        Dialogs.showErrorMessage('DICOM → SVS', 'Uygulama başlatılamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName()))
    }
}

// ── Render: her durum değişiminde sahneyi sıfırdan kurar ────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
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

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('DICOM → SVS yapılandırması gerekli')
        def miss = configMissing(cfg)
        addGuidance('Köprü bir Python ortamı + DICOMtoSVS.py betiği gerektirir. Eksik/geçersiz:\n  • ' +
            (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')) +
            '\n\nOrtam: [Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi] → "DICOMtoSVS".' +
            '\nBetik: aşağıdaki "İndir/Güncelle" düğmesi sabitlenmiş sürümü indirir (depo lisanssız → paketlenmez).')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('İndir/Güncelle ⇩', { startDownload() }, 'DICOMtoSVS.py — sabitlenmiş sürüm ' + PIN_SHA.substring(0, 7)))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText('DICOM → SVS yapılandırması')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def scField = new javafx.scene.control.TextField(cfg.script ?: '')
        [pyField, scField].each { it.setPrefColumnCount(40) }
        pyFieldRef.set(pyField); scriptFieldRef.set(scField)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Dosya seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('DICOMtoSVS.py:'), scField, navButton('…', { browseFile(scField) }))
        center.getChildren().add(grid)
        addGuidance('Python alanı boş bırakılırsa ortam yöneticisinin kurduğu "DICOMtoSVS" ortamı otomatik bulunur. ' +
            'Betik alanı boş bırakılırsa öntanımlı konum kullanılır: ' + defaultScriptFile().getAbsolutePath())
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('İndir/Güncelle ⇩', { persistConfigFields(); startDownload() }, 'DICOMtoSVS.py — sabitlenmiş sürüm ' + PIN_SHA.substring(0, 7)))
        actions.add(navButton('Bağımlılık kontrolü', { startSelftest() }, 'pydicom + tifffile + imagecodecs + pylibjpeg içe aktarılabiliyor mu?'))
        actions.add(navButton('Kaydet ▶', { persistConfigFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
    } else if (cur == 'CHECK_RUNNING') {
        title.setText('Çalışıyor…')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'CHECK_DONE') {
        title.setText('Tamamlandı')
        addLiveLog()
        actions.add(navButton('◀ Devam', { step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
    } else if (cur == 'READY') {
        title.setText('DICOM → SVS dönüştürme — hazır')
        def sb = new StringBuilder()
        sb << 'Python  : ' << (cfg.python ?: '(ayarsız)') << '\n'
        def scF = new File(cfg.script ?: '')
        sb << 'Betik   : ' << (scF.isFile() ? cfg.script : '(indirilmedi)') << '\n'
        sb << 'Sürüm   : sabitlenmiş ' << PIN_SHA.substring(0, 7) << ' (2026-08-15) — ' << REPO_URL << '\n'
        addMonoArea(sb.toString())
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def inField = new javafx.scene.control.TextField(cfg.inputDir ?: '')
        inField.setPrefColumnCount(40)
        inputFieldRef.set(inField)
        def browseDir = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'DICOM klasörünü seç', null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('DICOM girdi klasörü:'), inField, navButton('…', { browseDir(inField) }))
        center.getChildren().add(grid)
        def zipChk   = new javafx.scene.control.CheckBox('Slaytlar zip\'li (her slayt kendi .zip dosyasında)')
        def labelChk = new javafx.scene.control.CheckBox('Etiket (label) görüntüsünü ekle')
        def macroChk = new javafx.scene.control.CheckBox('Makro (overview) görüntüsünü ekle')
        def anonChk  = new javafx.scene.control.CheckBox('Anonimleştir (dosya adı yenilenir; etiket/makro atılır; duyarlı meta veri temizlenir)')
        def tagsChk  = new javafx.scene.control.CheckBox('DICOM etiketlerini TIFF\'e göm (özel etiket 65000)')
        zipChk.setSelected(cfg.isZipped); labelChk.setSelected(cfg.label); macroChk.setSelected(cfg.macro)
        anonChk.setSelected(cfg.anonymize); tagsChk.setSelected(cfg.addTags)
        zipChkRef.set(zipChk); labelChkRef.set(labelChk); macroChkRef.set(macroChk)
        anonChkRef.set(anonChk); tagsChkRef.set(tagsChk)
        // Anonimleştirme etiket/makro/etiket-gömme ile çelişir — araç anonim modda bunları zaten atar.
        def syncAnon = { ->
            boolean a = anonChk.isSelected()
            [labelChk, macroChk, tagsChk].each { it.setDisable(a); if (a) it.setSelected(false) }
        }
        anonChk.selectedProperty().addListener({ obs, o, n -> syncAnon() } as javafx.beans.value.ChangeListener)
        syncAnon()
        def opts = new javafx.scene.layout.VBox(6, zipChk, labelChk, macroChk, anonChk, tagsChk)
        center.getChildren().add(opts)
        addGuidance('Girdi klasörü her slaytı kendi ALT KLASÖRÜNDE (ya da zip\'li seçeneğiyle kendi .zip dosyasında) barındırmalıdır. ' +
            'Çıktılar "<girdi-klasörü>_output" içine yazılır. Dönüşüm kayıpsızdır; multipleks/z-stack slaytlar atlanır.')
        addWarnLabel('⚠ İnternetten indirilen araçlar için kurumunuzun BT onayını alın; yalnız güvendiğiniz kaynaklardan gelen DICOM dosyalarını işleyin.')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Yapılandır', { persistRunFields(); step.set('CONFIG'); render() }))
        actions.add(navButton('Resmî uygulama…', { persistRunFields(); step.set('EXE'); render() }, 'İndirdiğiniz DICOMtoSVS uygulamasını (exe) başlat'))
        def runBtn = navButton('Dönüştür ▶', { startRun() }, 'Klasördeki tüm DICOM slaytlarını SVS-benzeri TIFF\'e çevirir')
        runBtn.setDisable(!configComplete(cfg))
        actions.add(runBtn)
    } else if (cur == 'RUNNING') {
        title.setText('Dönüştürülüyor…')
        addGuidance('DICOMtoSVS köprüsü çalışıyor; ilerleme aşağıda akıyor. ~1 GB\'lık bir slayt tipik olarak saniyeler sürer. ' +
            'Zaman aşımı: ' + PYTHON_TIMEOUT_SECONDS + ' sn.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅')
        addMonoArea(resultTextRef.get())
        boolean hasProject = QP.getProject() != null
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('Klasörü aç', { openFolder(resultDirRef.get()) }))
        def impBtn = navButton('Projeye aktar ▶', { importToProject() },
            hasProject ? 'Dönüştürülen dosyaları QuPath\'in içe aktarma diyaloğuna verir'
                       : 'Önce bir proje açın (File → Project…)')
        impBtn.setDisable(!hasProject)
        actions.add(impBtn)
        actions.add(navButton('↻ Yeni dönüşüm', { step.set('READY'); render() }))
    } else if (cur == 'EXE') {
        title.setText('Resmî DICOMtoSVS uygulaması (exe)')
        addGuidance('Kurumsal/çevrimdışı kullanım için resmî uygulamayı GitHub README\'sindeki bağlantıdan indirin ' +
            '(' + REPO_URL + '), zip\'i açın ("_internal" klasörü exe\'nin yanında kalmalı) ve aşağıda exe\'yi seçin. ' +
            'Uygulama kendi penceresini ve konsolunu açar; çıktılar yine "<klasör>_output" içine yazılır. ' +
            'Windows\'ta Microsoft Visual C++ Redistributable gerekir.')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def exField = new javafx.scene.control.TextField(cfg.exePath ?: '')
        exField.setPrefColumnCount(40)
        exeFieldRef.set(exField)
        def browseExe = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'DICOMtoSVS uygulamasını seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('DICOMtoSVS uygulaması:'), exField, navButton('…', { browseExe(exField) }))
        center.getChildren().add(grid)
        addWarnLabel('⚠ İnternetten indirilen çalıştırılabilirler için kurumunuzun BT onayını alın (bkz. Ekler → WSI Dosya Formatları).')
        actions.add(navButton('◀ Geri', { step.set('READY'); render() }))
        actions.add(navButton('Başlat ▶', { launchExe() }))
    } else { // ERROR
        title.setText('Hata')
        addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Geri', { step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    }

    // Alt çubuk: "Üstte tut" (sol) + disclaimer + eylem düğmeleri (sağ)
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
    stage.setScene(new javafx.scene.Scene(root, 880, 620))
}

// ── Açılış durumu ───────────────────────────────────────────────────────────
step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('DICOM → SVS dönüştürme (DICOMtoSVS)')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ DICOM → SVS dönüştürme sihirbazı açıldı."
