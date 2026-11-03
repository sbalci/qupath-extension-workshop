/**
 * Yardımcı - Python ve Yapay Zekâ Ajanı Köprü Denetimi (Py4J · paquo · MCP)
 * --------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR (SALT OKUNUR):
 *   Bu bilgisayarda QuPath'e dışarıdan bağlanılabilecek yolları tek pencerede listeler:
 *     1. QuPath sürümü — iki MCP eklentisi de QuPath 0.7.0 ister; atölye tabanı 0.6.0'dır.
 *     2. Py4J eklentisi kurulu mu, varsayılan port (25333) dinliyor mu?
 *        Token kullanılıp kullanılmadığı dışarıdan GÖRÜLEMEZ — rapor bunu söyler.
 *     3. 00JackLu/qupath-mcp kurulu mu, port 9527 dinliyor mu, ayarlarda betik/eklenti/dosya
 *        erişimi açık mı? (mcp.properties → ortam değişkeni → -D sırasıyla okunur.)
 *     4. earthmover-support/qupath-mcp kurulu mu, port 51515 dinliyor mu, eylem günlüğü
 *        (mcp-logs) ne kadar yer tutuyor?
 *     5. Açık görüntüde ajana gidebilecek kimliklendirici yüzeyler: dosya adı, etiket/makro.
 *     6. QuBaLab ve paquo için kopyalanabilir bağlantı örnekleri.
 *
 * NE YAPMAZ:
 *   • MCP sunucusu BARINDIRMAZ, indirmez, kurmaz. Bilinçli karar: bkz. Ekler → Yapay Zekâ
 *     Ajanları ve MCP. İki sunucu da kimlik doğrulamasız ve keyfî kod çalıştırabilir.
 *   • Ağ geçidi BAŞLATMAZ: buradan açılan bir GatewayManager, Py4J menüsünden kapatılamaz.
 *   • Porta yalnızca TCP bağlantısı açıp kapatır; HTTP isteği GÖNDERMEZ, araç ÇAĞIRMAZ.
 *     Yalnız 127.0.0.1 denenir — ağa çıkılmaz.
 *   • Nesne oluşturmaz, değiştirmez, kaydetmez; ayar veya dosya yazmaz.
 *   • Klinik skor, eşik, alt-tip veya yorum ÜRETMEZ.
 *
 * KULLANIM:
 *   [Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller →
 *    Python ve yapay zekâ ajanı köprü denetimi] (açık slayt gerekmez)
 *   ya da [Automate → Project scripts → bu betik].
 *
 * ÇIKTI:
 *   • Sonuç penceresi: yukarıdaki beş başlık + kopyalanabilir Python örnekleri.
 *   • Headless: aynı rapor konsola yazılır.
 *
 * YÖNTEM REFERANSLARI:
 *   • qupath-extension-py4j ve QuBaLab (Apache-2.0) — github.com/qupath
 *   • paquo (GPL-3.0) — github.com/Bayer-Group/paquo
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.lib.gui.prefs.PathPrefs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── showResultWindow (lint Check 8 — VERBATIM from yardimci-kesisim-alani) ───
def showResultWindow = { String windowTitle, String windowBody ->
    if (isHeadless) {
        println "=== ${windowTitle} ===\n${windowBody}\n=================="
        return
    }
    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle(windowTitle)
            stage.setAlwaysOnTop(true)

            def textArea = new javafx.scene.control.TextArea(windowBody)
            textArea.setEditable(false)
            textArea.setWrapText(false)
            textArea.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;")

            def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut")
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )

            def copyBtn = new javafx.scene.control.Button("Kopyala")
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent()
                content.putString(windowBody)
                cb.setContent(content)
            })

            def closeBtn = new javafx.scene.control.Button("Kapat")
            closeBtn.setDefaultButton(true)
            closeBtn.setOnAction({ stage.close() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)

            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, copyBtn, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(textArea)
            def __footer = new javafx.scene.control.Label("QuPath Atölye Betikleri · araştırma/eğitim amaçlı")
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons)
            __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)

            stage.setScene(new javafx.scene.Scene(root, 740, 560))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── 1) Yardımcılar ──────────────────────────────────────────────────
final String LOOPBACK = "127.0.0.1"
final int PORT_PY4J = 25333
final int PORT_JACKLU = 9527
final int PORT_EARTHMOVER = 51515

// Eklenti sınıfları ayrı bir sınıf yükleyicide olabilir; hiçbirine derleme zamanında bağlanmıyoruz.
def classPresent = { String name ->
    def loaders = [this.class.classLoader, Thread.currentThread().getContextClassLoader()]
    try {
        loaders << qupath.lib.gui.QuPathGUI.getExtensionCatalogManager()?.getExtensionClassLoader()
    } catch (Throwable ignored) {}
    return loaders.findAll { it != null }.any { loader ->
        try {
            Class.forName(name, false, loader)
            return true
        } catch (Throwable ignored) {
            return false
        }
    }
}

// Yalnız TCP: bağlan ve kapat. Hiçbir protokol konuşulmaz.
def portOpen = { int port ->
    def socket = new java.net.Socket()
    try {
        socket.connect(new java.net.InetSocketAddress(LOOPBACK, port), 300)
        return true
    } catch (Throwable ignored) {
        return false
    } finally {
        try { socket.close() } catch (Throwable ignored) {}
    }
}

// 00JackLu/qupath-mcp ayar sırası (McpConfiguration): dosya → ortam değişkeni → -D.
def readJackluConfig = { ->
    def cfg = [enableScripts: 'true', enablePlugins: 'true', enableFileAccess: 'true',
               enableCaptureView: 'false', host: LOOPBACK, port: String.valueOf(PORT_JACKLU)]
    def envNames = [enableScripts: 'QUPATH_MCP_ENABLE_SCRIPTS', enablePlugins: 'QUPATH_MCP_ENABLE_PLUGINS',
                    enableFileAccess: 'QUPATH_MCP_ENABLE_FILE_ACCESS', enableCaptureView: 'QUPATH_MCP_ENABLE_CAPTURE_VIEW',
                    host: 'QUPATH_MCP_HOST', port: 'QUPATH_MCP_PORT']
    def sources = new LinkedHashSet<String>(["varsayılan"])
    String home = System.getProperty("user.home")
    def file = [new File(home, ".qupath/mcp.properties"), new File(home, "qupath-mcp.properties"),
                new File("qupath-mcp.properties")].find { it.isFile() }
    if (file != null) {
        try {
            def props = new Properties()
            file.withInputStream { props.load(it) }
            cfg.keySet().toList().each { key ->
                if (props.getProperty(key) != null) cfg[key] = props.getProperty(key).trim()
            }
            sources.clear()
            sources << file.getPath()
        } catch (Throwable t) {
            sources << ("okunamadı: " + file.getPath())
        }
    }
    cfg.keySet().toList().each { key ->
        String env = System.getenv(envNames[key])
        if (env != null) { cfg[key] = env.trim(); sources << "ortam değişkeni" }
        String sys = System.getProperty("qupath.mcp." + key)
        if (sys != null) { cfg[key] = sys.trim(); sources << "-D" }
    }
    cfg.source = sources.join(" + ")
    return cfg
}

// java.util.prefs üzerinden okur; createPersistentPreference gibi yeni anahtar kaydetmez.
def readEarthmoverPrefs = { ->
    def prefs = PathPrefs.getUserPreferences()
    boolean enabled = prefs.getBoolean("mcpServerEnabled", true)
    int port = Integer.getInteger("qupath.driver.port", prefs.getInt("mcpServerPort", PORT_EARTHMOVER))
    String folder = prefs.get("mcpActionLogDir", "")
    def logDir = null
    if (folder != null && !folder.isBlank()) {
        logDir = new File(folder)
    } else {
        String userPath = PathPrefs.userPathProperty().get()
        def base = (userPath != null && !userPath.isBlank()) ? new File(userPath) : PathPrefs.getDefaultQuPathUserDirectory().toFile()
        logDir = new File(base, "mcp-logs")
    }
    return [enabled: enabled, port: port, logDir: logDir]
}

def warnings = []
def body = new StringBuilder()
body << "PYTHON VE YAPAY ZEKÂ AJANI KÖPRÜ DENETİMİ (salt okunur)\n"
body << "═══════════════════════════════════════════════════════\n\n"

// ── 2) QuPath sürümü ────────────────────────────────────────────────
String version = qupath.lib.common.GeneralTools.getVersion()
boolean atLeast07 = version != null &&
    qupath.lib.common.Version.parse(version).compareTo(qupath.lib.common.Version.parse("0.7.0")) >= 0

body << "1) QUPATH SÜRÜMÜ\n"
body << "────────────────\n"
body << "   Çalışan sürüm: ${version}\n"
body << (atLeast07
    ? "   Py4J, paquo ve iki MCP eklentisi de bu sürümde kurulabilir.\n\n"
    : "   Py4J ve paquo kurulabilir. İki MCP eklentisi QuPath 0.7.0 ister; bu sürüme kurulamaz.\n\n")

// ── 3) Py4J ─────────────────────────────────────────────────────────
boolean py4jInstalled = classPresent('qupath.ext.py4j.core.GatewayManager')
boolean py4jOpen = portOpen(PORT_PY4J)

body << "2) PY4J (QuBaLab'in bağlandığı resmî eklenti)\n"
body << "─────────────────────────────────────────────\n"
body << (py4jInstalled
    ? "   ✓ Eklenti yüklü.\n"
    : "   ✗ Eklenti BULUNAMADI. Kurulum: [Extensions → Manage extensions] → QuPath Py4J extension.\n")
if (py4jOpen) {
    body << "   ⚠ Port ${PORT_PY4J} DİNLİYOR (ağ geçidi açık ya da porttaki başka bir program).\n"
    body << "     Token kullanılıp kullanılmadığı buradan görülemez. Tokensiz ağ geçidine bu\n"
    body << "     bilgisayardaki her süreç bağlanıp QuPath içinde kod çalıştırabilir.\n"
    warnings << "Py4J portu ${PORT_PY4J} açık: ağ geçidini token ile başlattığınızı doğrulayın."
} else {
    body << "   Port ${PORT_PY4J} kapalı. Farklı bir port seçtiyseniz bu denetim onu göremez.\n"
}
body << "\n"

// ── 4) 00JackLu/qupath-mcp ──────────────────────────────────────────
boolean jackluInstalled = classPresent('qupath.lib.extension.mcp.McpExtension')
def jacklu = readJackluConfig()
int jackluPort = (jacklu.port as String).isInteger() ? (jacklu.port as Integer) : PORT_JACKLU
boolean jackluOpen = portOpen(jackluPort)
def isOn = { String key -> Boolean.parseBoolean(jacklu[key] as String) }

body << "3) MCP — 00JackLu/qupath-mcp (üçüncü taraf, GPL-3.0)\n"
body << "────────────────────────────────────────────────────\n"
body << (jackluInstalled ? "   ⚠ Eklenti YÜKLÜ.\n" : "   Eklenti yüklü değil.\n")
body << (jackluOpen
    ? "   ⚠ Port ${jackluPort} DİNLİYOR.\n"
    : "   Port ${jackluPort} kapalı.\n")
if (jackluOpen && !jackluInstalled) {
    body << "   Eklenti bulunamadığı için portu başka bir program kullanıyor olabilir.\n"
    warnings << "Port ${jackluPort} açık; sahibi bilinmiyor."
}
if (jackluInstalled) {
    body << "   Ayar kaynağı: ${jacklu.source}\n"
    body << "   Betik çalıştırma: ${isOn('enableScripts') ? 'AÇIK' : 'kapalı'}  ·  Eklenti: ${isOn('enablePlugins') ? 'AÇIK' : 'kapalı'}"
    body << "  ·  Dosya: ${isOn('enableFileAccess') ? 'AÇIK' : 'kapalı'}  ·  Ekran görüntüsü: ${isOn('enableCaptureView') ? 'AÇIK' : 'kapalı'}\n"
    if (!(jacklu.host in [LOOPBACK, 'localhost', '::1'])) {
        body << "   ⚠ Bağlama adresi ${jacklu.host}: sunucu bu bilgisayarın dışından erişilebilir olabilir.\n"
        warnings << "00JackLu MCP yerel olmayan adrese bağlı (${jacklu.host})."
    }
    body << "   Kimlik doğrulama yok. v0.2.0 kaynağında web sayfalarından gelen istekleri\n"
    body << "   ayıklayan Origin/Host denetimi de yok (2026-10-30). Kullanmadığınızda kapatın.\n"
    if (isOn('enableScripts')) warnings << "00JackLu MCP'de betik çalıştırma açık: enableScripts=false ile kapatın."
}
body << "\n"

// ── 5) earthmover-support/qupath-mcp ────────────────────────────────
boolean earthmoverInstalled = classPresent('io.earthmover.qupath.driver.GuiDriverExtension')
def earthmover = readEarthmoverPrefs()
boolean earthmoverOpen = portOpen(earthmover.port as int)

body << "4) MCP — earthmover-support/qupath-mcp (üçüncü taraf, Apache-2.0)\n"
body << "─────────────────────────────────────────────────────────────────\n"
body << (earthmoverInstalled ? "   ⚠ Eklenti YÜKLÜ.\n" : "   Eklenti yüklü değil.\n")
body << (earthmoverOpen
    ? "   ⚠ Port ${earthmover.port} DİNLİYOR.\n"
    : "   Port ${earthmover.port} kapalı.\n")
if (earthmoverOpen && !earthmoverInstalled) {
    body << "   Eklenti bulunamadığı için portu başka bir program kullanıyor olabilir.\n"
    warnings << "Port ${earthmover.port} açık; sahibi bilinmiyor."
}
if (earthmoverInstalled) {
    body << "   Sunucu tercihi: ${earthmover.enabled ? 'AÇIK' : 'kapalı'}"
    body << "  (Kapatmak için: [Extensions → MCP server → Enabled])\n"
    body << "   Ajan QuPath içinde her kodu çalıştırabilir; bu yetenek kapatılamaz.\n"
    if (earthmoverOpen) warnings << "earthmover MCP açık: kullanmadığınızda [Extensions → MCP server → Enabled] ile kapatın."
}
def logFiles = (earthmover.logDir as File).listFiles()?.findAll { it.isFile() && it.getName().endsWith(".jsonl") } ?: []
if (!logFiles.isEmpty()) {
    double logMb = logFiles.sum { it.length() } / (1024.0d * 1024.0d)
    body << String.format(java.util.Locale.US, "   ⚠ Eylem günlüğü: %d dosya, %.1f MB%n", logFiles.size(), logMb)
    body << "     ${earthmover.logDir}\n"
    body << "     Çalışan Groovy kodunu, dosya yollarını ve iletişim kutusu metinlerini içerebilir.\n"
    warnings << "Ajan eylem günlüğü var: proje verisi gibi koruyun."
}
body << "\n"

// ── 6) Açık görüntü ─────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()

body << "5) AÇIK GÖRÜNTÜDE AJANA GİDEBİLECEK KİMLİKLENDİRİCİLER\n"
body << "─────────────────────────────────────────────────────\n"
if (imageData == null) {
    body << "   Açık görüntü yok; bu başlık atlandı.\n"
} else {
    def server = imageData.getServer()
    String name = server.getMetadata().getName()
    body << "   Görüntü adı: ${name}\n"
    if (name != null && name ==~ /(?s).*\d{2,}[-_\/]\d{2,}.*/) {
        body << "   ⚠ Adda vaka numarasına benzeyen bir örüntü var (olası; siz doğrulayın).\n"
        warnings << "Dosya adı vaka numarası içeriyor olabilir."
    }
    def associated = server.getAssociatedImageList() ?: []
    def identifying = associated.findAll { String item ->
        def lower = item.toLowerCase(java.util.Locale.ROOT)
        lower.contains("label") || lower.contains("macro")
    }
    body << "   Gömülü görüntüler: ${associated.isEmpty() ? '(yok)' : associated.join(', ')}\n"
    if (!identifying.isEmpty()) {
        body << "   ⚠ Etiket/makro görüntüsü var; hasta bilgisi taşıyabilir.\n"
        warnings << "Slaytta etiket/makro görüntüsü var."
    }
    body << "   Kesin denetim için: [Yardımcılar → Klinik ve kohort → WSI anonimleştirme sihirbazı → Denetle].\n"
}
body << "\n"

// ── 7) Kopyalanabilir bağlantı örnekleri ────────────────────────────
String appPath = System.getProperty("jpackage.app-path")
def appFile = appPath != null ? new File(appPath) : null
// macOS'ta çalıştırılabilir dosya .app/Contents/MacOS içindedir; paquo .app klasörünü bekler.
while (appFile != null && !appFile.getName().endsWith(".app") && appPath?.contains(".app/"))
    appFile = appFile.getParentFile()
String qupathDir = (appFile == null)
    ? "QUPATH_KURULUM_KLASORU"
    : (appFile.getName().endsWith(".app") ? appFile : appFile.getParentFile()).getPath().replace('\\', '/')

body << "6) BAĞLANTI ÖRNEKLERİ\n"
body << "─────────────────────\n"
body << "   QuBaLab (Python 3.11+; önce [Extensions → Py4J → Start Py4J Gateway], Random ile token):\n\n"
body << "     from qubalab.qupath import qupath_gateway\n"
body << "     gateway = qupath_gateway.create_gateway(auth_token=\"TOKEN\", port=${PORT_PY4J})\n"
body << "     print(gateway.entry_point.getCurrentImageName())\n\n"
body << "   paquo (.paquo.toml; yazarken QuPath'te o proje açık OLMAMALI):\n\n"
body << "     qupath_dir = \"${qupathDir}\"\n\n"

// ── 8) Özet ─────────────────────────────────────────────────────────
body << "ÖZET\n"
body << "────\n"
if (warnings.isEmpty()) {
    body << "   Bu denetimin baktığı yerlerde açık bir köprü görünmüyor.\n"
    body << "   Farklı portlardaki ya da başka araçlara ait sunucular buradan görülemez.\n"
} else {
    warnings.each { body << "   ⚠ ${it}\n" }
}
body << "\n"
body << "Bu bir DENETİM raporudur — nesne oluşturmaz, değiştirmez, kaydetmez; ayar yazmaz.\n"
body << "Ayrıntı: Ekler → Yapay Zekâ Ajanları ve MCP.\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Python ve yapay zekâ ajanı köprü denetimi", body.toString())
println "✓ Köprü denetimi tamamlandı (salt okunur)."
