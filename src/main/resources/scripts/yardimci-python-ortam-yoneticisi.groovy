/**
 * Yardımcı - Atölye Python ortam yöneticisi (uv tabanlı, tek pencere)
 * ------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Atölyenin Python köprülü entegrasyonları (TIA Toolbox bölge/boya, Kaiko, SPIDER,
 *   Sectra) için **izole** Python ortamlarını tek tıkla kurar/onarır. Manuel "venv kur,
 *   pip install, python.exe'ye gözat" sürtünmesini kaldırır. Resmî TIA Toolbox eklentisinin
 *   `uv` tabanlı kurulum desenini TÜM entegrasyonlara genelleştirir.
 *
 *   PYTHON ÖN-KOŞUL DEĞİLDİR: yönetici saf Groovy'dir ve kendi kendine yeten **uv**
 *   ikilisini çağırır; uv gerekirse Python 3.11'i kendisi indirir. uv yoksa resmî
 *   sürümü `<kullanıcı>/.atolye/uv/`'a indirilir (PATH'te uv varsa o kullanılır).
 *
 * KURULUM HEDEFLERİ (kural):
 *   <kullanıcı>/.atolye/runtimes/<id>/.venv/Scripts/python.exe   (Windows)
 *   <kullanıcı>/.atolye/runtimes/<id>/.venv/bin/python           (macOS/Linux)
 *   Sihirbazlar python'u önce resmî eklenti ortamından, sonra bu dizinden, sonra
 *   manuel ayardan bulur.
 *
 * NE ÜRETİR (ve ne ÜRETMEZ):
 *   Yalnızca Python ortamları kurar (bağımlılıklar). Model AĞIRLIKLARINI indirmez;
 *   kapılı modeller (ör. SPIDER, CC-BY-NC) için ayrıca HF girişi gerekir. Cellpose
 *   ortamları (.venv*) BIOP eklentisiyle yönetilir — bu yönetici kapsamı dışındadır.
 *
 * KULLANIM:
 *   [Extensions → Atölye → Yardımcılar → Atölye Python ortam yöneticisi]
 *   İlgili ortamın yanındaki "Kur" düğmesine basın; log akar, durum güncellenir.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlıdır.
 */

import qupath.fx.dialogs.Dialogs
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler / platform ──────────────────────────────────────────────────────
long UV_TIMEOUT = 3600L
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def OS = System.getProperty('os.name').toLowerCase(java.util.Locale.ROOT)
boolean IS_WIN = OS.contains('win')
boolean IS_MAC = OS.contains('mac')
def ARCH = System.getProperty('os.arch').toLowerCase(java.util.Locale.ROOT)
def UV_BASE = 'https://github.com/astral-sh/uv/releases/latest/download/'
def uvAsset = { ->
    if (IS_WIN) return 'uv-x86_64-pc-windows-msvc.zip'
    if (IS_MAC) return (ARCH.contains('aarch64') || ARCH.contains('arm')) ? 'uv-aarch64-apple-darwin.tar.gz' : 'uv-x86_64-apple-darwin.tar.gz'
    return (ARCH.contains('aarch64') || ARCH.contains('arm')) ? 'uv-aarch64-unknown-linux-gnu.tar.gz' : 'uv-x86_64-unknown-linux-gnu.tar.gz'
}
def uvExeName = IS_WIN ? 'uv.exe' : 'uv'

def atolyeDir   = new File(System.getProperty('user.home'), '.atolye')
def uvDir       = new File(atolyeDir, 'uv')
def runtimesDir = new File(atolyeDir, 'runtimes')

// ── Ortam kataloğu ───────────────────────────────────────────────────────────
// torchBackend: uv --torch-backend (auto = CUDA sürümünü tespit et); null = torch yok.
def CATALOG = [
    [id:'tiatoolbox-region', label:'TIA Toolbox — bölge modelleri (KongNet / MapDe)', python:'3.11',
     packages:['tiatoolbox>=2.1.2','torch','torchvision'], torchBackend:'auto', reuseOfficial:'tiatoolbox-runtime',
     note:'Bölge sihirbazı. Resmî TIAToolbox eklentisi kuruluysa onun ortamı kullanılır (ayrı kuruluma gerek yok).'],
    [id:'tiatoolbox-stain', label:'TIA Toolbox — boya normalizasyonu / doku maskesi', python:'3.11',
     packages:['tiatoolbox>=2.0','numpy','pillow'], torchBackend:null, reuseOfficial:null,
     note:'Hafif (torch gerekmez). Boya-normalizasyonu / doku-maskesi sihirbazı.'],
    [id:'kaiko', label:'Kaiko Midnight — denetimli FM sınıflandırıcı', python:'3.11',
     packages:['torch>=2.1','torchvision>=0.16','transformers>=4.38','safetensors>=0.4','scikit-learn>=1.3','Pillow>=10.0','numpy>=1.24'],
     torchBackend:'auto', reuseOfficial:null, note:'Kaiko sınıflandırıcı sihirbazı (eğit → tahmin).'],
    [id:'spider', label:'SPIDER — organ doku sınıflandırıcı', python:'3.11',
     packages:['torch>=2.1','transformers>=4.40.0','huggingface_hub>=0.23','Pillow>=10.0','numpy>=1.24'],
     torchBackend:'auto', reuseOfficial:null, note:'⚠️ Model ağırlıkları KAPILI — ayrıca HF girişi gerekir.'],
    [id:'sectra', label:'Sectra PACS — DICOM → GeoJSON', python:'3.11',
     packages:['pydicom>=2.4','shapely','numpy'], torchBackend:null, reuseOfficial:null,
     note:'Hafif. Sectra PACS içe-aktarma sihirbazı.'],
]
def specById = { String id -> CATALOG.find { it.id == id } }

// ── venv yol yardımcıları ────────────────────────────────────────────────────
def venvDirOf  = { String id -> new File(new File(runtimesDir, id), '.venv') }
def venvPython = { String id -> def v = venvDirOf(id); IS_WIN ? new File(v, 'Scripts/python.exe') : new File(v, 'bin/python') }

// Resmî eklenti ortamını tara: <kullanıcı>/QuPath/v*/<name>/.venv
def detectOfficial = { String name ->
    def base = new File(System.getProperty('user.home'), 'QuPath')
    if (!base.isDirectory()) return null
    def vdirs = base.listFiles({ f -> f.isDirectory() && f.getName().startsWith('v') } as java.io.FileFilter)
    if (vdirs == null) return null
    def cands = []
    vdirs.sort { it.getName() }.each { vd ->
        def rt = new File(vd, name + '/.venv')
        def w = new File(rt, 'Scripts/python.exe'); def n = new File(rt, 'bin/python')
        if (w.isFile()) cands << w else if (n.isFile()) cands << n
    }
    return cands.isEmpty() ? null : cands.last().getAbsolutePath()
}
def statusOf = { spec ->
    if (spec.reuseOfficial) { def off = detectOfficial(spec.reuseOfficial); if (off != null) return [state:'official', python:off] }
    def py = venvPython(spec.id); if (py.isFile()) return [state:'installed', python:py.getAbsolutePath()]
    return [state:'missing', python:null]
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('LIST')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def processRef    = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef   = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def render

def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text); b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip)); return b
}
def busyBar = { -> def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb }
def copyToClipboard = { String txt ->
    def cb = javafx.scene.input.Clipboard.getSystemClipboard()
    def c = new javafx.scene.input.ClipboardContent(); c.putString(txt ?: ""); cb.setContent(c)
}
def openFolder = { File f -> try { if (f != null && f.isDirectory() && java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(f) } catch (Throwable ignore) {} }

// ── Süreç akışı ──────────────────────────────────────────────────────────────
def runProc = { List cmd, Closure onLine ->
    def pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true)
    def proc; try { proc = pb.start() } catch (Throwable e) { return [ok:false, exitCode:-1, error:'Başlatılamadı: ' + (e.getMessage() ?: e.getClass().getSimpleName())] }
    processRef.set(proc)
    def last = new java.util.ArrayDeque()
    try {
        def r = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
        String line
        while ((line = r.readLine()) != null) { last.addLast(line); while (last.size() > 200) last.pollFirst(); onLine(line); if (cancelledRef.get()) break }
        r.close()
    } catch (Throwable ignore) {}
    boolean fin
    try { fin = proc.waitFor(UV_TIMEOUT, java.util.concurrent.TimeUnit.SECONDS) }
    catch (InterruptedException ie) { proc.destroyForcibly(); return [ok:false, exitCode:-3, error:'İptal edildi'] }
    if (!fin) { proc.destroyForcibly(); return [ok:false, exitCode:-2, error:'Zaman aşımı (' + UV_TIMEOUT + ' sn)'] }
    if (cancelledRef.get()) { proc.destroyForcibly(); return [ok:false, exitCode:-3, error:'İptal edildi'] }
    int code = proc.exitValue(); return [ok: code == 0, exitCode: code, lastLines: last.join('\n')]
}

// uv'yi bul; yoksa indir + aç
def findUv = { ->
    def local = new File(uvDir, uvExeName); if (local.isFile()) return local.getAbsolutePath()
    try {
        def cmd = IS_WIN ? ['where', 'uv'] : ['which', 'uv']
        def p = new ProcessBuilder(cmd).redirectErrorStream(true).start()
        def out = p.getInputStream().getText('UTF-8'); p.waitFor()
        if (p.exitValue() == 0) { def first = out.readLines().find { it?.trim() }; if (first) { def f = new File(first.trim()); if (f.isFile()) return f.getAbsolutePath() } }
    } catch (Throwable ignore) {}
    return null
}
def ensureUv = { Closure appendLine ->
    def existing = findUv()
    if (existing) { appendLine('uv bulundu: ' + existing); return [ok:true, uv:existing] }
    def asset = uvAsset()
    appendLine('uv indiriliyor: ' + UV_BASE + asset)
    uvDir.mkdirs()
    def dl = new File(uvDir, asset)
    try {
        def client = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(30)).build()
        def req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(UV_BASE + asset)).GET().build()
        def resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofFile(dl.toPath()))
        if (resp.statusCode() != 200) return [ok:false, error:'uv indirilemedi (HTTP ' + resp.statusCode() + '). Çevrimdışıysanız uv\'yi elle ' + uvDir.getAbsolutePath() + ' içine koyun.']
    } catch (Throwable t) { return [ok:false, error:'uv indirme hatası: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }
    try {
        if (asset.endsWith('.zip')) {
            def zf = new java.util.zip.ZipFile(dl)
            try {
                zf.entries().each { e ->
                    if (e.isDirectory()) return
                    def nm = new File(e.getName()).getName()
                    if (!nm) return
                    def o = new File(uvDir, nm)
                    zf.getInputStream(e).withStream { is -> o.withOutputStream { os -> os << is } }
                }
            } finally {
                zf.close()
            }
        } else {
            def p = new ProcessBuilder(['tar','-xzf', dl.getAbsolutePath(), '-C', uvDir.getAbsolutePath(), '--strip-components=1']).redirectErrorStream(true).start()
            p.getInputStream().getText('UTF-8'); p.waitFor()
        }
    } catch (Throwable t) { return [ok:false, error:'uv açma hatası: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }
    def uv = new File(uvDir, uvExeName)
    if (!uv.isFile()) return [ok:false, error:'uv açıldı ama bulunamadı: ' + uv.getAbsolutePath()]
    if (!IS_WIN) { try { uv.setExecutable(true) } catch (Throwable ignore) {} }
    appendLine('uv hazır: ' + uv.getAbsolutePath())
    return [ok:true, uv:uv.getAbsolutePath()]
}

// Paket adından Python import adını türet (scikit-learn→sklearn, Pillow→PIL, ...)
def importNameOf = { String pkg ->
    def b = pkg.replaceAll(/[<>=!~\[].*$/, '').trim().toLowerCase(java.util.Locale.ROOT)
    if (b == 'scikit-learn') return 'sklearn'
    if (b == 'pillow') return 'PIL'
    if (b == 'opencv-python') return 'cv2'
    return b.replace('-', '_')
}

def installEnv = { spec, String uvPath, Closure appendLine ->
    def venv = venvDirOf(spec.id); venv.getParentFile().mkdirs()
    appendLine(''); appendLine('── ' + spec.id + ' — venv oluşturuluyor (Python ' + spec.python + ') ──')
    def r1 = runProc([uvPath, 'venv', venv.getAbsolutePath(), '--python', spec.python], appendLine)
    if (!r1.ok) return r1
    if (cancelledRef.get()) return [ok:false, error:'İptal edildi']
    def py = venvPython(spec.id).getAbsolutePath()
    def base = [uvPath, 'pip', 'install', '--python', py]
    appendLine(''); appendLine('── paketler kuruluyor: ' + spec.packages.join(', ') + (spec.torchBackend ? ('  [torch-backend=' + spec.torchBackend + ']') : '') + ' ──')
    def r2
    if (spec.torchBackend) {
        // 1) uv --torch-backend (CUDA otomatik). Eski uv tanımazsa cu128 indexine düş.
        def cmd = new ArrayList(base); cmd.add('--torch-backend=' + spec.torchBackend); cmd.addAll(spec.packages)
        r2 = runProc(cmd, appendLine)
        if (!r2.ok && !cancelledRef.get()) {
            def ll = (r2.lastLines ?: '').toLowerCase(java.util.Locale.ROOT)
            if (ll.contains('torch-backend') && (ll.contains('unexpected') || ll.contains('unrecognized') || ll.contains('invalid value') || ll.contains('found argument'))) {
                appendLine(''); appendLine('uv bu sürüm --torch-backend desteklemiyor; --extra-index-url (cu128) ile yeniden deneniyor…')
                def cmd2 = new ArrayList(base)
                cmd2.add('--index-strategy'); cmd2.add('unsafe-best-match')
                cmd2.add('--extra-index-url'); cmd2.add('https://download.pytorch.org/whl/cu128')
                cmd2.addAll(spec.packages)
                r2 = runProc(cmd2, appendLine)
            }
        }
    } else {
        def cmd = new ArrayList(base); cmd.addAll(spec.packages)
        r2 = runProc(cmd, appendLine)
    }
    if (!r2.ok) return r2
    // 2) Kurulum sonrası doğrulama: anahtar paketleri import et (kırık venv'i hemen yakala)
    def imports = spec.packages.collect { importNameOf(it) }.unique()
    boolean verifyOk = true; String verifyMsg = ''
    if (!imports.isEmpty() && !cancelledRef.get()) {
        appendLine(''); appendLine('── doğrulama: import ' + imports.join(', ') + ' ──')
        def vr = runProc([py, '-c', 'import ' + imports.join(', ') + '; print("VERIFY_OK")'], appendLine)
        verifyOk = vr.ok && (vr.lastLines ?: '').contains('VERIFY_OK')
        if (!verifyOk) verifyMsg = (vr.lastLines ?: '')
    }
    try {
        def marker = new File(venv.getParentFile(), '.atolye-installed.json')
        marker.setText(qupath.lib.io.GsonTools.getInstance(true).toJson(
            [id: spec.id, python: spec.python, packages: spec.packages, torchBackend: spec.torchBackend, verifyOk: verifyOk]), 'UTF-8')
    } catch (Throwable ignore) {}
    return [ok:true, python: py, verifyOk: verifyOk, verifyMsg: verifyMsg, imports: imports]
}

def startInstall = { spec ->
    // Torch (çok-GB) ortamları için disk uyarısı + onay
    if (spec.torchBackend) {
        try { runtimesDir.mkdirs() } catch (Throwable ignore) {}
        long freeBytes = 0L; try { freeBytes = runtimesDir.getUsableSpace() } catch (Throwable ignore) {}
        double freeGB = freeBytes / (1024.0d * 1024.0d * 1024.0d)
        def msg = spec.label + ' ortamı torch + CUDA içerir → yaklaşık 3–5 GB indirilir ve kurulur.\n\n' +
            String.format(java.util.Locale.US, 'Hedef    : %s%nBoş alan : %.1f GB%s%n%nDevam edilsin mi?',
                runtimesDir.getAbsolutePath(), freeGB, (freeGB > 0 && freeGB < 6.0d ? '  ⚠ düşük (≥6 GB önerilir)' : ''))
        if (!Dialogs.showConfirmDialog('Disk alanı — ' + spec.id, msg)) return
    }
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set(spec.label + ' kuruluyor…'); step.set('RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        try {
            def u = ensureUv(appendLine)
            if (!u.ok) { javafx.application.Platform.runLater { errorTextRef.set(u.error); step.set('ERROR'); render() }; return }
            if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }
            def r = installEnv(spec, u.uv, appendLine)
            javafx.application.Platform.runLater {
                if (!r.ok) { errorTextRef.set('Kurulum başarısız (çıkış: ' + (r.exitCode ?: '?') + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '')); step.set('ERROR'); render() }
                else {
                    def sb = new StringBuilder()
                    sb << 'ORTAM KURULDU ✅\n══════════════\n\n'
                    sb << 'Ortam   : ' << spec.label << ' (' << spec.id << ')\n'
                    sb << 'Python  : ' << r.python << '\n'
                    sb << 'Paketler: ' << spec.packages.join(', ') << '\n'
                    sb << 'Doğrulama: ' << (r.verifyOk ? ('import OK — ' + (r.imports ? r.imports.join(', ') : '')) : ('⚠ BAŞARISIZ:\n' + (r.verifyMsg ?: ''))) << '\n'
                    if (spec.note) sb << '\nNot: ' << spec.note << '\n'
                    sb << '\nİlgili sihirbaz artık bu ortamı otomatik bulur.\n⚠️ Yalnızca araştırma/eğitim amaçlıdır.'
                    resultTextRef.set(sb.toString()); step.set('RESULT'); render()
                }
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Beklenmeyen hata:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
        }
    }, 'AtolyeEnv-Install')
    worker.setDaemon(true); worker.start()
}

// ── Headless ────────────────────────────────────────────────────────────────
if (isHeadless) {
    println "Atölye Python ortam yöneticisi — durum:"
    println "  uv: " + (findUv() ?: '(yok — kurulumda indirilecek)')
    CATALOG.each { spec -> def st = statusOf(spec); println String.format(java.util.Locale.US, "  %-18s %s", spec.id, (st.state == 'official' ? 'resmî ortam: ' + st.python : (st.state == 'installed' ? 'kurulu: ' + st.python : 'kurulu değil'))) }
    println "Ortam yöneticisi için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlıdır."
    return
}

// ── Render ───────────────────────────────────────────────────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def title = new javafx.scene.control.Label(); title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10); center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)
    def actions = new ArrayList()
    def addMonoArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: ''); ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(ta)
    }
    def addLiveLog = { -> def la = logAreaRef.get(); if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) } }

    if (cur == 'LIST') {
        title.setText('Atölye Python ortam yöneticisi')
        def uvFound = findUv()
        def uvLbl = new javafx.scene.control.Label('uv: ' + (uvFound ?: '(yok — ilk kurulumda otomatik indirilecek)'))
        uvLbl.setStyle('-fx-opacity: 0.8;'); center.getChildren().add(uvLbl)

        def listBox = new javafx.scene.layout.VBox(8)
        CATALOG.each { spec ->
            def st = statusOf(spec)
            def row = new javafx.scene.layout.VBox(2)
            row.setStyle('-fx-border-color: -fx-box-border; -fx-border-width: 0 0 1 0; -fx-padding: 6 2 6 2;')
            def head = new javafx.scene.layout.HBox(8); head.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            def name = new javafx.scene.control.Label(spec.label); name.setStyle('-fx-font-weight: bold;')
            def chip = new javafx.scene.control.Label(
                st.state == 'official'  ? '✓ resmî ortam kullanılıyor' :
                st.state == 'installed' ? '✓ kurulu' : '○ kurulu değil')
            chip.setStyle(st.state == 'missing' ? '-fx-text-fill: #b8860b;' : '-fx-text-fill: #2e8b57;')
            def spacer = new javafx.scene.layout.Region(); javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            head.getChildren().addAll(name, spacer, chip)
            def note = new javafx.scene.control.Label(spec.note ?: ''); note.setWrapText(true); note.setStyle('-fx-opacity: 0.75; -fx-font-size: 11px;')
            def btnRow = new javafx.scene.layout.HBox(6); btnRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            if (st.state == 'official') {
                btnRow.getChildren().add(navButton('Yine de kur', { startInstall(spec) }, 'Resmî ortam yerine ayrı bir atölye ortamı kur'))
            } else if (st.state == 'installed') {
                btnRow.getChildren().add(navButton('Onar / güncelle', { startInstall(spec) }))
                btnRow.getChildren().add(navButton('Klasörü aç', { openFolder(venvDirOf(spec.id).getParentFile()) }))
            } else {
                btnRow.getChildren().add(navButton('Kur ▶', { startInstall(spec) }))
            }
            row.getChildren().addAll(head, note, btnRow)
            listBox.getChildren().add(row)
        }
        def scroll = new javafx.scene.control.ScrollPane(listBox); scroll.setFitToWidth(true)
        javafx.scene.layout.VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(scroll)
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('⟳ Yenile', { render() }))
    } else if (cur == 'RUNNING') {
        title.setText(runPhaseRef.get())
        def g = new javafx.scene.control.Label('uv Python + paketleri indiriyor (torch ortamları birkaç dakika sürer). İlk kurulum büyük olabilir.'); g.setWrapText(true)
        center.getChildren().add(g); center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅'); addMonoArea(resultTextRef.get())
        actions.add(navButton('◀ Ortam listesi', { step.set('LIST'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
    } else { // ERROR
        title.setText('Hata'); addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Ortam listesi', { step.set('LIST'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    }

    def topChk = new javafx.scene.control.CheckBox('Üstte tut'); topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n -> alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
    def spacer2 = new javafx.scene.layout.Region(); javafx.scene.layout.HBox.setHgrow(spacer2, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8); bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk); bar.getChildren().add(spacer2); bar.getChildren().addAll(actions)
    def disclaimer = new javafx.scene.control.Label('Yalnızca Python bağımlılıkları kurar; model ağırlıklarını/lisanslarını sağlamaz. Araştırma/eğitim amaçlıdır.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar); bottom.setPadding(new javafx.geometry.Insets(10))
    def root = new javafx.scene.layout.BorderPane(); root.setCenter(center); root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 820, 620))
}

// ── Açılış ────────────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Atölye Python ortam yöneticisi')
        stage.setAlwaysOnTop(alwaysTop.get())
        render(); stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Yönetici açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ Atölye Python ortam yöneticisi açıldı."
