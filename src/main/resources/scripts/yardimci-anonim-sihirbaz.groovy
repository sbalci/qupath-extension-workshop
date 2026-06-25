/**
 * Yardımcı - WSI Anonimleştirme Sihirbazı (tek pencere)
 * -----------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Açık projedeki (ya da yalnız aktif) slaytların anonimleştirilmiş KOPYALARINI
 *   seçtiğiniz YENİ bir klasöre yazar:
 *     • Dosyaları sıralı (case_1, case_2 …) ya da UUID adlarıyla yeniden adlandırır.
 *     • İsteğe bağlı: Python (tifffile) ile SVS/TIFF ImageDescription içindeki PHI
 *       alanlarını (Filename, Patient, Barcode, ID, Date …) redakte eder.
 *     • Orijinal → anonim "eşleştirme anahtarını" CSV + JSON olarak üretir
 *       (geri alınabilir mod) ya da yalnız anonim dosya listesini yazar (geri alınamaz).
 *
 * GÜVENLİ TASARIM (yerinde değiştirmez):
 *   • Orijinal slaytlara DOKUNMAZ, yeniden adlandırmaz, SİLMEZ; açık projeyi
 *     DEĞİŞTİRMEZ. Yalnız seçtiğiniz çıktı klasörüne yeni kopyalar yazar.
 *   • Böylece Windows dosya-kilidi sorunu ve proje bozulma riski oluşmaz.
 *   • Bittiğinde çıktı klasörünü QuPath'te YENİ bir proje olarak açabilirsiniz.
 *
 * KAPSAM / SINIRLAR (dürüst):
 *   • Varsayılan akış (kopya + yeniden adlandırma) = Seviye I (dosya adı). Her zaman
 *     güvenli, çalışan slayt üretir.
 *   • "Metadata sil" (Python) = ImageDescription redaksiyonu (SVS/TIFF). Etiket/makro
 *     GÖRÜNTÜSÜNÜ silmez; piramidi koruyan tam temizlik için wsi-anon / tifftools /
 *     ImageDePHI kullanın (Ek E § Araç ekosistemi). tifffile yoksa kopyaya düşer.
 *   • Anonimleştirmeyi HER dosyada gözle DOĞRULAYIN.
 *
 * EŞLEŞTİRME ANAHTARI = PHI:
 *   • Üretilen CSV/JSON anahtar orijinal kimlikleri içerir → kişisel veridir.
 *     Güvenli, erişimi kısıtlı bir yerde tutun; anonim slaytlarla ASLA paylaşmayın.
 *
 * KULLANIM:
 *   1. Anonimleştirilecek slaytları içeren bir QuPath projesi açın.
 *   2. [Extensions → Atölye → İleri analiz — sonraki oturum → WSI anonimleştirme sihirbazı]
 *      (bu giriş bir sonraki oturumda etkinleştirilecek).
 *   3. Çıktı klasörünü + adlandırma + (ops.) Python yolunu seçin → Önizle → Başlat.
 *
 * YÖNTEM / KAYNAK:
 *   • S. Balcı, anonym-qupath (atölye eğitmeninin referans projesi; gizli, talep
 *     üzerine erişilebilir) — bu sihirbaz onun "Compatible" iş akışının atölyeye
 *     uyarlanmış, yerinde-değiştirmeyen sürümüdür.
 *   • Bisson 2023 (beş seviyeli çerçeve); EMPAIA wsi-anon; Kitware ImageDePHI.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlıdır; klinik karar üretmez.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import groovy.json.JsonOutput
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ─────────────────────────────────────────────────────────────────
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
long PYTHON_TIMEOUT_SECONDS = 1200L            // büyük WSI kopyası + redaksiyon için cömert sınır
def NAMING_OPTIONS = ['sequential', 'uuid']

// ── Gömülü Python (tifffile): tam dosyayı kopyalar (piramit korunur), sonra
//    ImageDescription içindeki PHI alanlarını yerinde redakte eder. Backslash
//    KULLANMAZ (Groovy '''...''' kaçışlarıyla çakışmaması için).
def PY_STRIP = '''
import sys, shutil
try:
    import tifffile
except Exception as e:
    print("ERROR tifffile yok: " + str(e)); sys.exit(2)

PHI = ("filename","title","patient","patientname","name","case","barcode",
       "id","slideid","date","time","scanscope id","user","label")

def redact(desc):
    parts = desc.split("|")
    out = []
    for p in parts:
        if "=" in p:
            k, sep, v = p.partition("=")
            if k.strip().lower() in PHI:
                p = k + "= REDACTED"
        out.append(p)
    return "|".join(out)

def main(src, dst):
    shutil.copy2(src, dst)
    try:
        desc = tifffile.tiffcomment(dst)
    except Exception as e:
        print("WARN ImageDescription okunamadi: " + str(e)); return 0
    if not desc:
        print("OK ImageDescription bos"); return 0
    try:
        tifffile.tiffcomment(dst, redact(desc))
        print("OK ImageDescription redakte edildi")
    except Exception as e:
        print("WARN ImageDescription yazilamadi: " + str(e))
    return 0

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: anon.py input output"); sys.exit(1)
    sys.exit(main(sys.argv[1], sys.argv[2]))
'''

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır;
//    WorkshopPrefs.atolye.* anahtarı KULLANMAZ → prefs-drift lint'ini tetiklemez) ──
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/anonimize')
def loadConfig = { ->
    [ python    : prefs.get('python',     'python'),
      prefix    : prefs.get('prefix',     'case'),
      naming    : prefs.get('naming',     'sequential'),
      outDir    : prefs.get('outDir',     ''),
      processAll: prefs.getBoolean('processAll', true),
      removeMeta: prefs.getBoolean('removeMeta', false),
      reversible: prefs.getBoolean('reversible', true) ]
}
def saveConfig = { cfg ->
    prefs.put('python', (cfg.python ?: 'python'))
    prefs.put('prefix', (cfg.prefix ?: 'case'))
    prefs.put('naming', (cfg.naming ?: 'sequential'))
    prefs.put('outDir', (cfg.outDir ?: ''))
    prefs.putBoolean('processAll', (boolean) cfg.processAll)
    prefs.putBoolean('removeMeta', (boolean) cfg.removeMeta)
    prefs.putBoolean('reversible', (boolean) cfg.reversible)
    try { prefs.flush() } catch (Throwable ignore) {}
}

// ── Python erişilebilir mi? (yalnız metadata silme için gerekir) ─────────────
def checkPython = { String pythonExe ->
    if (!pythonExe?.trim()) return false
    try {
        def pb = new ProcessBuilder(pythonExe, '--version')
        pb.redirectErrorStream(true)
        def proc = pb.start()
        boolean fin = proc.waitFor(8L, java.util.concurrent.TimeUnit.SECONDS)
        if (!fin) { proc.destroyForcibly(); return false }
        return proc.exitValue() == 0
    } catch (Throwable t) { return false }
}
def createTempPy = { ->
    def f = File.createTempFile('atolye_anonim_', '.py')
    f.deleteOnExit()
    f.setText(PY_STRIP, 'UTF-8')
    return f
}

// ── Bir URI listesinden ilk yerel (file://) dosyayı çöz ──────────────────────
def firstLocalFile = { uris ->
    if (uris == null) return null
    def it = uris.iterator()
    while (it.hasNext()) {
        def u = it.next()
        try {
            if ('file'.equals(u.getScheme())) {
                def f = new File(u)
                if (f.isFile()) return f
            }
        } catch (Throwable ignore) {}
    }
    return null
}

// ── İşlenecek öğeleri topla: [name, file] (yalnız yerel dosyalar) ─────────────
// processAll → proje girişleri; değilse → yalnız aktif görüntü.
def collectItems = { cfg ->
    def project = QP.getProject()
    def items = []
    if (cfg.processAll) {
        if (project == null) return items
        project.getImageList().each { entry ->
            def uris = null
            try { uris = entry.getServerBuilder()?.getURIs() } catch (Throwable ignore) {}
            def f = firstLocalFile(uris)
            def nm = (entry.getImageName() ?: (f != null ? f.getName() : 'slayt'))
            items << [name: nm, file: f]
        }
    } else {
        def imageData = QP.getCurrentImageData()
        if (imageData != null) {
            def server = imageData.getServer()
            def uris = null
            try { uris = server.getURIs() } catch (Throwable ignore) {}
            def f = firstLocalFile(uris)
            def nm = (f != null ? f.getName() : (server.getMetadata().getName() ?: 'slayt'))
            items << [name: nm, file: f]
        }
    }
    return items
}

def extOf = { File f ->
    if (f == null) return '.svs'
    def n = f.getName()
    int d = n.lastIndexOf('.')
    return (d > 0) ? n.substring(d) : ''
}

// ── Anonim adların üretimi (sıralı + çakışma koruması, ya da UUID) ───────────
def buildMappings = { cfg, items, File outDir ->
    def used = new HashSet<String>()
    def maps = []
    int counter = 1
    items.each { item ->
        def ext = extOf(item.file)
        String base
        if (cfg.naming == 'uuid') {
            base = (cfg.prefix ?: 'case') + '_' + UUID.randomUUID().toString()
        } else {
            base = (cfg.prefix ?: 'case') + '_' + counter
            counter++
        }
        String anon = base + ext
        // Çıktı klasöründe / parti içinde çakışma olursa harf eki ekle
        char suffix = 'a'
        while (used.contains(anon) || (outDir != null && new File(outDir, anon).exists())) {
            anon = base + (suffix as String) + ext
            suffix = (char) (suffix + 1)
        }
        used.add(anon)
        maps << [orig: item.name, anon: anon, file: item.file]
    }
    return maps
}

// ── Tek dosyayı anonim kopyala (Python redaksiyonu ya da düz kopya) ──────────
def anonymizeOne = { File src, File dst, cfg, File pyFile ->
    if (src == null) return [ok: false, status: 'Atlandı (yerel dosya bulunamadı)']
    try {
        if (cfg.removeMeta && pyFile != null) {
            def cmd = [cfg.python, pyFile.getAbsolutePath(), src.getAbsolutePath(), dst.getAbsolutePath()]
            def pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true)
            def proc = pb.start()
            def out = new StringBuilder()
            def rdr = new java.io.BufferedReader(new java.io.InputStreamReader(
                proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
            String line
            while ((line = rdr.readLine()) != null) { out.append(line).append('\n') }
            rdr.close()
            boolean fin = proc.waitFor(PYTHON_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            if (!fin) { proc.destroyForcibly(); return [ok: false, status: 'Python zaman aşımı'] }
            if (proc.exitValue() == 0) {
                def msg = out.toString()
                String detail = msg.contains('redakte') ? 'metadata redakte edildi'
                    : (msg.contains('bos') ? 'ImageDescription boştu' : 'kopyalandı')
                return [ok: true, status: 'Kopyalandı + ' + detail]
            }
            // Python başarısız → düz kopyaya düş
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return [ok: true, status: 'Kopyalandı (metadata silme başarısız — yalnız dosya adı)']
        } else {
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            String note = cfg.removeMeta ? 'Python yok — yalnız dosya adı' : 'yalnız dosya adı'
            return [ok: true, status: 'Kopyalandı (' + note + ')']
        }
    } catch (Throwable t) {
        return [ok: false, status: 'Hata: ' + (t.getMessage() ?: t.getClass().getSimpleName())]
    }
}

// ── Anahtar / manifest yazımı ────────────────────────────────────────────────
def csvCell = { s -> '"' + ((s ?: '').toString().replace('"', '""')) + '"' }

def writeKey = { File outDir, cfg, String ts, List rows ->
    // rows: [orig, anon, status]
    def written = []
    if (cfg.reversible) {
        // Geri alınabilir: tam eşleştirme anahtarı (orijinal ↔ anonim) — PHI içerir
        def csv = new File(outDir, "anonymization_lookup_${ts}.csv")
        csv.withWriter('UTF-8') { w ->
            w.writeLine('Original_Filename,Anonymized_Filename,Status,Timestamp')
            rows.each { r ->
                w.writeLine([csvCell(r.orig), csvCell(r.anon), csvCell(r.status), csvCell(ts)].join(','))
            }
        }
        written << csv
        def mappings = [:]
        def log = [:]
        rows.each { r -> mappings[r.orig] = r.anon; log[r.orig] = r.status }
        def json = new File(outDir, "anonymization_lookup_${ts}.json")
        def data = [
            timestamp: ts,
            mode     : 'reversible',
            settings : [namePrefix: cfg.prefix, namingPattern: cfg.naming, removeMetadata: cfg.removeMeta],
            mappings : mappings,
            processingLog: log
        ]
        json.setText(JsonOutput.prettyPrint(JsonOutput.toJson(data)), 'UTF-8')
        written << json
    } else {
        // Geri alınamaz: yalnız anonim dosya listesi (orijinal ad YAZILMAZ)
        def csv = new File(outDir, "anonymization_manifest_${ts}.csv")
        csv.withWriter('UTF-8') { w ->
            w.writeLine('Anonymized_Filename,Status,Timestamp')
            rows.each { r -> w.writeLine([csvCell(r.anon), csvCell(r.status), csvCell(ts)].join(',')) }
        }
        written << csv
    }
    return written
}

// ── Özet metni ───────────────────────────────────────────────────────────────
def buildResultText = { File outDir, cfg, List rows, List keyFiles ->
    int ok = rows.count { it.ok }
    int total = rows.size()
    def sb = new StringBuilder()
    sb << "WSI ANONİMLEŞTİRME — ÖZET\n"
    sb << "═══════════════════════════\n\n"
    sb << "Çıktı klasörü : " << outDir.getAbsolutePath() << "\n"
    sb << "Mod           : " << (cfg.reversible ? 'Geri alınabilir (anahtar yazıldı)' : 'Geri alınamaz (anahtar yok)') << "\n"
    sb << "Metadata silme: " << (cfg.removeMeta ? 'evet (Python/tifffile)' : 'hayır (yalnız dosya adı)') << "\n"
    sb << String.format(java.util.Locale.US, "Sonuç         : %d/%d slayt işlendi%n%n", ok, total)
    sb << "Eşleştirme (ilk satırlar):\n"
    int shown = 0
    rows.each { r ->
        if (shown < 12) {
            String mark = r.ok ? '✓' : '✗'
            String origShown = cfg.reversible ? r.orig : '(gizli)'
            sb << String.format(java.util.Locale.US, "  %s %-28s → %-20s  %s%n", mark, origShown, r.anon, r.status)
            shown++
        }
    }
    if (total > shown) sb << String.format(java.util.Locale.US, "  … ve %d slayt daha%n", (total - shown))
    sb << "\nÜretilen dosyalar:\n"
    keyFiles.each { f -> sb << "  • " << f.getName() << "\n" }
    sb << "\nSonraki adım: çıktı klasörünü QuPath'te YENİ bir proje olarak açabilirsiniz\n"
    sb << "(File → Project → Create project → bu klasör).\n\n"
    if (cfg.reversible)
        sb << "⚠ Eşleştirme anahtarı (CSV/JSON) PHI içerir — güvenli saklayın, anonim slaytlarla paylaşmayın.\n"
    else
        sb << "⚠ Geri alınamaz mod: orijinal↔anonim bağlantısı YAZILMADI. Geri dönüş gerekiyorsa\n  orijinalleri + kendi anahtarınızı ayrıca güvenli tutun.\n"
    sb << "⚠ Etiket/makro görüntü silinmedi; tam temizlik için wsi-anon/tifftools (Ek E).\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlıdır; klinik karar üretmez."
    return sb.toString()
}

// ── Headless: yapılandırmayı raporla, etkileşim yok ──────────────────────────
if (isHeadless) {
    def project = QP.getProject()
    if (project == null) { println "Önce anonimleştirilecek slaytları içeren bir proje açın."; return }
    def cfg = loadConfig()
    def items = collectItems(cfg)
    println "WSI anonimleştirme yapılandırması: prefix=${cfg.prefix} naming=${cfg.naming} " +
            "processAll=${cfg.processAll} removeMeta=${cfg.removeMeta} reversible=${cfg.reversible}"
    println "İşlenecek slayt sayısı (yerel): ${items.count { it.file != null }}/${items.size()}"
    println "Çıktı klasörü: ${cfg.outDir ?: '(ayarsız)'}"
    println "Bu sihirbaz çıktı klasörü seçimi + onay için QuPath arayüzü gerektirir (headless çalıştırılamaz)."
    return
}

// ── Durum makinesi ──────────────────────────────────────────────────────────
// NO_PROJECT | CONFIG | CONFIRM | RUNNING | DONE | ERROR
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('CONFIG')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def previewRef    = new java.util.concurrent.atomic.AtomicReference(null)   // [outDir, cfg, maps]
def resultRef     = new java.util.concurrent.atomic.AtomicReference(null)   // sonuç metni
def errorRef      = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
// CONFIG alanları (Önizle bunları okur)
def prefixFieldRef= new java.util.concurrent.atomic.AtomicReference(null)
def outFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def pyFieldRef    = new java.util.concurrent.atomic.AtomicReference(null)
def namingRef     = new java.util.concurrent.atomic.AtomicReference(null)
def scopeAllRef   = new java.util.concurrent.atomic.AtomicReference(null)
def removeMetaRef = new java.util.concurrent.atomic.AtomicReference(null)
def reversibleRef = new java.util.concurrent.atomic.AtomicReference(null)
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

// ── CONFIG alanlarını oku → cfg map ──────────────────────────────────────────
def readConfigFromFields = { ->
    def base = loadConfig()
    def pf = prefixFieldRef.get(); def of = outFieldRef.get(); def py = pyFieldRef.get()
    def nm = namingRef.get(); def sa = scopeAllRef.get(); def rm = removeMetaRef.get(); def rv = reversibleRef.get()
    [ python    : (py != null ? py.getText() : base.python)?.trim(),
      prefix    : (pf != null && pf.getText()?.trim()) ? pf.getText().trim() : 'case',
      naming    : (nm != null && nm.getValue() != null) ? nm.getValue() : base.naming,
      outDir    : (of != null ? of.getText() : base.outDir)?.trim(),
      processAll: (sa != null ? sa.isSelected() : base.processAll),
      removeMeta: (rm != null ? rm.isSelected() : base.removeMeta),
      reversible: (rv != null ? rv.isSelected() : base.reversible) ]
}

// ── Önizleme oluştur → CONFIRM ────────────────────────────────────────────────
def goPreview = { ->
    def cfg = readConfigFromFields()
    if (!cfg.outDir?.trim()) {
        Dialogs.showWarningNotification('Çıktı klasörü gerekli', 'Önce bir çıktı (hedef) klasörü seçin.')
        return
    }
    def outDir = new File(cfg.outDir)
    if (!outDir.isDirectory()) {
        if (!outDir.mkdirs()) {
            Dialogs.showErrorMessage('Klasör oluşturulamadı', 'Çıktı klasörü oluşturulamadı:\n' + outDir.getAbsolutePath())
            return
        }
    }
    def items = collectItems(cfg)
    if (items.isEmpty()) {
        Dialogs.showWarningNotification('Slayt yok',
            cfg.processAll ? 'Projede yerel slayt bulunamadı.' : 'Aktif görüntü yok ya da yerel dosya değil.')
        return
    }
    def maps = buildMappings(cfg, items, outDir)
    saveConfig(cfg)
    previewRef.set([outDir: outDir, cfg: cfg, maps: maps])
    step.set('CONFIRM'); render()
}

// ── Arka plan işleme → DONE ──────────────────────────────────────────────────
def startProcessing = { ->
    def pv = previewRef.get()
    if (pv == null) { step.set('CONFIG'); render(); return }
    def cfg = pv.cfg; def outDir = pv.outDir; def maps = pv.maps
    def la = new javafx.scene.control.TextArea()
    la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    step.set('RUNNING'); render()
    def worker = new Thread({
        def pyFile = null
        if (cfg.removeMeta && checkPython(cfg.python)) {
            try { pyFile = createTempPy() } catch (Throwable ignore) { pyFile = null }
        }
        def append = { String ln ->
            javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') }
        }
        def rows = []
        int i = 0
        for (m in maps) {
            i++
            def dst = new File(outDir, m.anon)
            def res = anonymizeOne(m.file, dst, cfg, pyFile)
            rows << [orig: m.orig, anon: m.anon, status: res.status, ok: res.ok]
            String mark = res.ok ? '✓' : '✗'
            append(String.format(java.util.Locale.US, "%s %d/%d  %s → %s  [%s]",
                mark, i, maps.size(), m.orig, m.anon, res.status))
        }
        def ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyyMMdd_HHmmss'))
        def keyFiles = []
        try { keyFiles = writeKey(outDir, cfg, ts, rows) }
        catch (Throwable t) { append('Anahtar yazılamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName())) }
        def text = buildResultText(outDir, cfg, rows, keyFiles)
        javafx.application.Platform.runLater { resultRef.set(text); step.set('DONE'); render() }
    }, 'AtolyeAnonimSihirbaz')
    worker.setDaemon(true); worker.start()
}

// ── Render: her durum değişiminde sahneyi sıfırdan kurar ─────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())   // her render'da üstte-kal yeniden uygulanır
    def cur = step.get()
    def cfg = loadConfig()

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10)
    center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)
    def actions = new ArrayList()

    def addGuidance = { String txt ->
        def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); center.getChildren().add(lbl)
    }
    def addMonoArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: '')
        ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(ta)
    }
    def addWarnLabel = { String txt ->
        def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true)
        lbl.setStyle('-fx-text-fill: #b8860b; -fx-font-weight: bold;')
        center.getChildren().add(lbl)
    }

    if (cur == 'NO_PROJECT') {
        title.setText('Proje açık değil')
        addGuidance('Anonimleştirme proje üzerinde çalışır.\n\n' +
            'Önce anonimleştirilecek slaytları içeren bir QuPath projesi açın, sonra "⟳ Yenile".')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('⟳ Yenile', { step.set(QP.getProject() == null ? 'NO_PROJECT' : 'CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText('WSI anonimleştirme — yapılandırma')
        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(8)

        def outField = new javafx.scene.control.TextField(cfg.outDir ?: '')
        def prefixField = new javafx.scene.control.TextField(cfg.prefix ?: 'case')
        def pyField = new javafx.scene.control.TextField(cfg.python ?: 'python')
        outField.setPrefColumnCount(32); prefixField.setPrefColumnCount(16); pyField.setPrefColumnCount(32)
        def namingChoice = new javafx.scene.control.ChoiceBox()
        NAMING_OPTIONS.each { namingChoice.getItems().add(it) }
        namingChoice.setValue(NAMING_OPTIONS.contains(cfg.naming) ? cfg.naming : 'sequential')
        def scopeAll = new javafx.scene.control.CheckBox('Projedeki tüm slaytlar (kapalı: yalnız aktif görüntü)')
        scopeAll.setSelected((boolean) cfg.processAll)
        def removeMeta = new javafx.scene.control.CheckBox('Metadata sil (Python/tifffile; SVS/TIFF ImageDescription)')
        removeMeta.setSelected((boolean) cfg.removeMeta)
        def reversible = new javafx.scene.control.CheckBox('Geri alınabilir mod (eşleştirme anahtarı CSV/JSON yaz)')
        reversible.setSelected((boolean) cfg.reversible)

        prefixFieldRef.set(prefixField); outFieldRef.set(outField); pyFieldRef.set(pyField)
        namingRef.set(namingChoice); scopeAllRef.set(scopeAll); removeMetaRef.set(removeMeta); reversibleRef.set(reversible)

        def browseDir = { f -> def dc = new javafx.stage.DirectoryChooser(); def x = dc.showDialog(stage); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseFile = { f -> def fc = new javafx.stage.FileChooser(); def x = fc.showOpenDialog(stage); if (x != null) f.setText(x.getAbsolutePath()) }

        int row = 0
        grid.add(new javafx.scene.control.Label('Çıktı klasörü:'), 0, row); grid.add(outField, 1, row); grid.add(navButton('…', { browseDir(outField) }), 2, row); row++
        grid.add(new javafx.scene.control.Label('Ad öneki:'), 0, row); grid.add(prefixField, 1, row); row++
        grid.add(new javafx.scene.control.Label('Adlandırma:'), 0, row); grid.add(namingChoice, 1, row); row++
        grid.add(new javafx.scene.control.Label('Python (ops.):'), 0, row); grid.add(pyField, 1, row); grid.add(navButton('…', { browseFile(pyField) }), 2, row); row++
        center.getChildren().add(grid)
        center.getChildren().addAll(scopeAll, removeMeta, reversible)

        addWarnLabel('⚠ Eşleştirme anahtarı (CSV/JSON) PHI içerir — güvenli saklayın, anonim slaytlarla paylaşmayın.')
        addGuidance('Orijinal slaytlara DOKUNULMAZ; yalnız çıktı klasörüne anonim kopyalar yazılır. ' +
            'Etiket/makro görüntü silinmez (tam temizlik için wsi-anon/tifftools — Ek E). ' +
            'Anonimleştirmeyi her dosyada gözle doğrulayın.')

        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Önizle ▶', { goPreview() }, 'Eşleştirmeyi hesaplar ve onay ekranını gösterir'))
    } else if (cur == 'CONFIRM') {
        def pv = previewRef.get()
        title.setText('Onay — eşleştirme önizlemesi')
        def sb = new StringBuilder()
        sb << "Çıktı klasörü : " << pv.outDir.getAbsolutePath() << "\n"
        sb << "Slayt sayısı  : " << pv.maps.size() << "\n"
        sb << "Mod           : " << (pv.cfg.reversible ? 'Geri alınabilir (anahtar yazılır)' : 'Geri alınamaz (anahtar yok)') << "\n"
        sb << "Metadata silme: " << (pv.cfg.removeMeta ? 'evet (Python)' : 'hayır') << "\n\n"
        sb << "Örnek eşleştirme:\n"
        int shown = 0
        pv.maps.each { m ->
            if (shown < 8) {
                String src = (m.file != null) ? m.orig : (m.orig + '  [yerel değil → atlanır]')
                sb << String.format(java.util.Locale.US, "  %-30s → %s%n", src, m.anon)
                shown++
            }
        }
        if (pv.maps.size() > shown) sb << String.format(java.util.Locale.US, "  … ve %d slayt daha%n", (pv.maps.size() - shown))
        addMonoArea(sb.toString())

        if (pv.cfg.removeMeta && !checkPython(pv.cfg.python))
            addWarnLabel('⚠ Python bulunamadı — metadata silinemeyecek; yalnız dosya adı anonimleştirmesi (Seviye I) yapılacak.')
        int nonLocal = pv.maps.count { it.file == null }
        if (nonLocal > 0)
            addWarnLabel('⚠ ' + nonLocal + ' slayt yerel dosya değil ve atlanacak.')

        actions.add(navButton('◀ Geri', { step.set('CONFIG'); render() }))
        actions.add(navButton('Başlat ▶', { startProcessing() }, 'Anonim kopyaları çıktı klasörüne yazar'))
    } else if (cur == 'RUNNING') {
        title.setText('İşleniyor…')
        addGuidance('Slaytlar çıktı klasörüne kopyalanıyor. Büyük WSI dosyaları için sürebilir.')
        center.getChildren().add(busyBar())
        def la = logAreaRef.get()
        if (la != null) {
            javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS)
            center.getChildren().add(la)
        }
    } else if (cur == 'DONE') {
        title.setText('Tamamlandı ✅')
        addMonoArea(resultRef.get())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultRef.get()) }))
        actions.add(navButton('↻ Yeni anonimleştirme', { step.set('CONFIG'); render() }))
    } else { // ERROR
        title.setText('Hata')
        addMonoArea(errorRef.get() ?: 'Bilinmeyen hata.')
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
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

    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlıdır; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(center)
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 720, 560))
}

// ── Açılış durumu ─────────────────────────────────────────────────────────────
step.set(QP.getProject() == null ? 'NO_PROJECT' : 'CONFIG')

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('WSI anonimleştirme sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ WSI anonimleştirme sihirbazı açıldı."
