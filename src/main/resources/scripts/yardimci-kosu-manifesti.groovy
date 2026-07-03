/**
 * Yardımcı - Koşu Manifesti (salt-okur provenance/manifest dışa aktarıcı)
 * -------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Her görüntü için yapılandırılmış bir JSON manifesti oluşturur ve dile getirir:
 *     - Kimlik (dosya yolu, görüntü adı, piksel boyutu µm, büyütme, görüntü tipi)
 *     - Boya vektörleri (QuPath renk ayrıştırma; adlar + RGB bileşenleri + varsayılan mi?)
 *     - Analiz durumu (sınıfa göre anotasyon sayısı, sınıfa göre tespit sayısı)
 *     - Ortam (QuPath sürümü, işletim sistemi, zaman damgası)
 *     - Kullanıcı notu (isteğe bağlı serbest metin)
 *   Kapsam: "Açık görüntü" veya "Tüm proje" (iletişim kutusuyla seçilir).
 *   JSON şuraya yazılır: <proje>/manifests/<imageName>_manifest_<millis>.json
 *   (Proje yoksa geçici dizin kullanılır.)
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   Yalnızca sayılar, yüzdeler, piksel boyutları, kanallar ve boya vektörü
 *   bileşenleri gibi ÖLÇÜM meta verisi üretir. Klinik etiket, eşik, alt tip,
 *   risk kategorisi veya yorum ÜRETMEZ. saveImageData çağırmaz; salt-okur.
 *
 * KULLANIM:
 *   1. Bir slayt (veya proje) açın.
 *   2. [Extensions → Atölye → Yardımcılar → Koşu Manifesti]
 *   3. Kapsam seçin ("Açık görüntü" / "Tüm proje").
 *   4. İsteğe bağlı kullanıcı notu girin; Tamam'a basın.
 *   5. JSON dosyaları manifests/ klasörüne yazılır; sonuç penceresi açılır.
 *
 * ÇIKTI:
 *   • <proje>/manifests/<imageName>_manifest_<millis>.json (her görüntü için)
 *   • Sonuç penceresinde yol(lar) + özet + not
 *   • Panoya kopyalama düğmesi (showResultWindow)
 *
 * YÖNTEM (KAYNAK):
 *   • qupath.lib.io.GsonTools.getInstance(true).toJson(record) — JSON serileştirme
 *   • imageData.getColorDeconvolutionStains() — boya vektörü okuma
 *   • qupath.lib.common.GeneralTools.getVersion() (try/catch ile korunmuş)
 *   • sbalci/metadata-qupath ile uyumlu alan adları (downstream araçlar çalışır)
 *   • Bankhead P et al. (2017), Sci Rep. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.common.GeneralTools
import java.io.File

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/kosu-manifesti')

// ── showResultWindow (yardimci-kesisim-alani.groovy'den VERBATIM kopyalandı) ──
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
            def __footer = new javafx.scene.control.Label("QuPath Atölye Scriptleri · araştırma/eğitim amaçlı")
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons)
            __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)

            stage.setScene(new javafx.scene.Scene(root, 720, 520))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Ön kontrol ───────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    def msg = "Önce bir slayt açın."
    if (isHeadless) { println msg; return }
    Dialogs.showErrorMessage("Görüntü açık değil", msg)
    return
}

// ── Kapsam seçimi ─────────────────────────────────────────────────────────────
def scopeOpts = ["Açık görüntü", "Tüm proje"]
def defScope = prefs.get('scope', scopeOpts[0])
if (!scopeOpts.contains(defScope)) defScope = scopeOpts[0]

String scope
if (isHeadless) {
    scope = defScope
} else {
    scope = Dialogs.showChoiceDialog("Koşu manifesti — kapsam",
        "Manifest hangi kapsamda oluşturulsun?", scopeOpts, defScope)
    if (scope == null) { println "İptal edildi."; return }
}
prefs.put('scope', scope)
try { prefs.flush() } catch (Throwable ig) {}

// ── Kullanıcı notu ────────────────────────────────────────────────────────────
String userNote = ''
if (!isHeadless) {
    def noteInput = Dialogs.showInputDialog("Koşu manifesti — not",
        "İsteğe bağlı not (ör. kullanılan parametre seti, analiz amacı):", prefs.get('lastNote', ''))
    if (noteInput != null) {
        userNote = noteInput.trim()
        prefs.put('lastNote', userNote)
        try { prefs.flush() } catch (Throwable ig) {}
    }
}

// ── Manifest çıktı dizini ─────────────────────────────────────────────────────
def resolveManifestDir = { project ->
    if (project != null && project.getPath() != null) {
        def parent = project.getPath().getParent()
        if (parent != null) return new File(parent.toFile(), 'manifests')
    }
    return new File(System.getProperty('java.io.tmpdir'), 'qupath_manifests')
}

// ── Tek görüntüden manifest kaydı oluştur ─────────────────────────────────────
def buildRecord = { imgData, entry, project, String note ->
    def rec = new java.util.LinkedHashMap()
    def server = imgData.getServer()

    // Kimlik
    def identity = new java.util.LinkedHashMap()
    identity.image_name = (entry != null ? entry.getImageName() : null) ?: (server.getMetadata().getName() ?: 'slide')
    try {
        def uris = server.getURIs()
        if (uris != null && !uris.isEmpty()) {
            def uriStr = uris.iterator().next().toString()
            identity.file_uri = uriStr
            try { identity.file_path = new File(new URI(uriStr)).getAbsolutePath() } catch (Throwable ig) { identity.file_path = uriStr }
        }
    } catch (Throwable ig) {}
    try {
        def cal = server.getPixelCalibration()
        if (cal != null) {
            double pw = cal.getPixelWidthMicrons()
            double ph = cal.getPixelHeightMicrons()
            identity.pixel_width_um  = Double.isNaN(pw) ? null : pw
            identity.pixel_height_um = Double.isNaN(ph) ? null : ph
        }
    } catch (Throwable ig) {}
    try {
        double mag = server.getMetadata().getMagnification()
        identity.magnification = Double.isNaN(mag) ? null : mag
    } catch (Throwable ig) {}
    try { identity.image_type = imgData.getImageType()?.toString() } catch (Throwable ig) {}
    identity.width_pixels  = server.getWidth()
    identity.height_pixels = server.getHeight()
    rec.identity = identity

    // Boya vektörleri
    def stainVectors = new java.util.LinkedHashMap()
    try {
        def stains = imgData.getColorDeconvolutionStains()
        if (stains != null) {
            stainVectors.stain_name = stains.getName()
            stainVectors.is_default = stains.isDefault()
            def stainList = []
            for (int i = 1; i <= 3; i++) {
                try {
                    def s = stains.getStain(i)
                    if (s == null) continue
                    def sv = new java.util.LinkedHashMap()
                    sv.index = i
                    sv.name  = s.getName()
                    sv.r     = s.getRed()
                    sv.g     = s.getGreen()
                    sv.b     = s.getBlue()
                    stainList << sv
                } catch (Throwable ig2) {}
            }
            stainVectors.stains = stainList
        } else {
            stainVectors.stains = []
            stainVectors.note   = 'Boya vektörü tanımlı değil (görüntü tipi fluorescence olabilir).'
        }
    } catch (Throwable ig) { stainVectors.error = ig.getMessage() ?: ig.getClass().getSimpleName() }
    rec.stain_vectors = stainVectors

    // Analiz durumu
    def analysisState = new java.util.LinkedHashMap()
    try {
        def annotations = QP.getAnnotationObjects()
        def annoByClass = annotations.groupBy { it.getPathClass()?.toString() ?: '(sınıfsız)' }
        def annoSummary = new java.util.LinkedHashMap()
        annoByClass.each { cls, items -> annoSummary[cls] = items.size() }
        analysisState.annotation_count_total = annotations.size()
        analysisState.annotation_count_by_class = annoSummary
    } catch (Throwable ig) { analysisState.annotation_error = ig.getMessage() ?: ig.getClass().getSimpleName() }
    try {
        def detections = QP.getDetectionObjects()
        def detByClass = detections.groupBy { it.getPathClass()?.toString() ?: '(sınıfsız)' }
        def detSummary = new java.util.LinkedHashMap()
        detByClass.each { cls, items -> detSummary[cls] = items.size() }
        analysisState.detection_count_total = detections.size()
        analysisState.detection_count_by_class = detSummary
    } catch (Throwable ig) { analysisState.detection_error = ig.getMessage() ?: ig.getClass().getSimpleName() }
    rec.analysis_state = analysisState

    // Ortam
    def env = new java.util.LinkedHashMap()
    try { env.qupath_version = GeneralTools.getVersion() } catch (Throwable ig) { env.qupath_version = 'bilinmiyor' }
    env.os_name       = System.getProperty('os.name') ?: ''
    env.os_version    = System.getProperty('os.version') ?: ''
    env.timestamp_ms  = System.currentTimeMillis()
    env.timestamp_iso = new java.text.SimpleDateFormat('yyyy-MM-dd\'T\'HH:mm:ssZ').format(new Date())
    rec.environment = env

    // Kullanıcı notu
    rec.user_note = note ?: ''
    return rec
}

// ── JSON'a serileştir ve yaz ──────────────────────────────────────────────────
def writeManifest = { record, File outDir, String imageName ->
    outDir.mkdirs()
    def safeName = imageName.replaceAll('[^A-Za-z0-9._-]', '_')
    long millis = System.currentTimeMillis()
    def fname   = String.format(java.util.Locale.US, '%s_manifest_%d.json', safeName, millis)
    def jsonStr = qupath.lib.io.GsonTools.getInstance(true).toJson(record)
    def f       = new File(outDir, fname)
    f.withWriter('UTF-8') { w -> w.write(jsonStr) }
    return f
}

// ── Görüntü kümesini belirle ─────────────────────────────────────────────────
def project = QP.getProject()
def manifestDir = resolveManifestDir(project)

def writtenFiles = []
def errors = []

if (scope == "Tüm proje") {
    if (project == null) {
        def msg = "Tüm proje taraması için açık bir QuPath projesi gerekir."
        if (isHeadless) { println msg; return }
        Dialogs.showErrorMessage("Proje yok", msg); return
    }
    def entries = project.getImageList()
    if (entries == null || entries.isEmpty()) {
        def msg = "Projede hiç görüntü yok."
        if (isHeadless) { println msg; return }
        Dialogs.showErrorMessage("Boş proje", msg); return
    }
    entries.each { entry ->
        def nm = entry.getImageName()
        try {
            entry.readImageData().withCloseable { ed ->
                def rec = buildRecord(ed, entry, project, userNote)
                def f   = writeManifest(rec, manifestDir, nm)
                writtenFiles << f
                println String.format(java.util.Locale.US, "✓ %s → %s", nm, f.getName())
            }
        } catch (Throwable t) {
            def err = nm + ' → ' + (t.getMessage() ?: t.getClass().getSimpleName())
            errors << err
            println "⚠ Atlandı: ${err}"
        }
    }
} else {
    // "Açık görüntü"
    try {
        def entry = QP.getProjectEntry()
        def rec   = buildRecord(imageData, entry, project, userNote)
        def nm    = (rec.identity?.image_name ?: 'slide').toString()
        def f     = writeManifest(rec, manifestDir, nm)
        writtenFiles << f
        println String.format(java.util.Locale.US, "✓ Manifest yazıldı: %s", f.getAbsolutePath())
    } catch (Throwable t) {
        errors << (t.getMessage() ?: t.getClass().getSimpleName())
    }
}

// ── Sonuç penceresi ───────────────────────────────────────────────────────────
def sb = new StringBuilder()
sb << "KOŞU MANİFESTİ\n"
sb << "═══════════════════════════════════════\n\n"
sb << String.format(java.util.Locale.US, "Kapsam          : %s%n", scope)
sb << String.format(java.util.Locale.US, "Yazılan dosya   : %,d%n", writtenFiles.size())
if (errors.size() > 0)
    sb << String.format(java.util.Locale.US, "Atlanan / hatalı: %,d%n", errors.size())
sb << String.format(java.util.Locale.US, "Çıktı dizini    : %s%n%n", manifestDir.getAbsolutePath())
writtenFiles.each { f -> sb << "  • " + f.getName() + "\n" }
if (errors.size() > 0) {
    sb << "\nHatalar:\n"
    errors.take(10).each { sb << "  ⚠ " + it + "\n" }
}
if (userNote != null && !userNote.isEmpty())
    sb << String.format(java.util.Locale.US, "%nNot : %s%n", userNote)
sb << "\nManifest alanları: kimlik · boya vektörleri · analiz durumu · ortam · kullanıcı notu.\n"
sb << "JSON dosyalarını Python/R ile okuyarak pipeline provenance kaydı tutabilirsiniz.\n\n"
sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Koşu Manifesti", sb.toString())
println "✓ Koşu manifesti tamamlandı (${writtenFiles.size()} dosya → ${manifestDir.getAbsolutePath()})."
