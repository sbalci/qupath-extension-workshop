/**
 * Yardımcı - Kohort Metadata Sihirbazı (tek pencere: bu slayt + tüm proje)
 * ------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   "Analiz etmeden önce ne elinde olduğunu bil." Açık slaytın VEYA tüm
 *   projenin teknik/tarayıcı üst verisini (metadata) okur — hepsi tek pencereden:
 *     1. BU SLAYT — açık slaydın boyut, piksel boyutu (µm/px), kestirilen
 *        büyütme, piramit, dosya bilgisi vb. üst verisini anında raporlar.
 *     2. TÜM PROJE — projedeki her görüntüyü arka planda gezer; çekirdek
 *        alanları (+ okunabiliyorsa gömülü tarayıcı anahtarları) bir CSV'ye
 *        ve özet rapora yazar (<proje>/cohort_metadata/). İsteğe bağlı olarak
 *        anahtar alanları QuPath Proje sekmesine SIRALANABİLİR sütun olarak da
 *        yazabilir (entry.getMetadata()) — kohortu QuPath'ten çıkmadan eleyin.
 *
 *   Tümüyle SALT-OKUR ölçüm üretir: hücre tespiti, sınıflandırma veya hiyerarşi
 *   DEĞİŞTİRMEZ. Yalnız "Proje sütunlarına da yaz" seçeneği işaretliyse proje
 *   girdi üst verisini günceller (görüntü pikselleri/anotasyonlar dokunulmaz).
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Çekirdek alanlar (boyut, piksel boyutu, alan, piramit, dosya) QuPath'in
 *     kararlı genel API'sinden gelir; güvenilirdir.
 *   • "Kestirilen büyütme" bir SEZGİSEL'dir (0.25 µm/px ≈ 40×). Slayt gömülü
 *     "objektif büyütme" varsa (getMagnification) ayrıca raporlanır.
 *   • Gömülü tarayıcı anahtarları (tarayıcı markası, tarama tarihi, MPP vb.)
 *     yalnız okuyucu/biçim bunları açığa çıkardığında doldurulur (örn. bazı
 *     Aperio SVS okuyucuları). Açığa çıkmazsa o alanlar boş kalır — hata değil.
 *   • Hiçbir klinik yorum, tanı veya skor üretmez.
 *
 * KULLANIM:
 *   1. Bir QuPath projesi açın (tek slayt raporu için bir de slayt açın).
 *   2. [Extensions → Atölye → Yardımcılar → Kohort metadata sihirbazı]
 *   3. "Bu slayt" panosunu inceleyin VEYA "Tüm projeyi tara → CSV".
 *   4. CSV'yi Excel/R/Python'da açıp kohort dahil/dışlama ölçütlerinizi tanımlayın.
 *
 * KAYNAK:
 *   sbalci/metadata-qupath (MIT) — https://github.com/sbalci/metadata-qupath
 *   Çekirdek alan adları o araçla uyumlu tutuldu (downstream python/R/SQL çalışır).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.common.GeneralTools
import java.io.File
import java.text.SimpleDateFormat

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Biçim yardımcıları (Locale.US — Türkçe yerelde virgül-ondalık olmasın) ──
def fmt = { double d, int dec -> Double.isNaN(d) ? '—' : String.format(java.util.Locale.US, '%.' + dec + 'f', d) }
def fmtInt = { long v -> String.format(java.util.Locale.US, '%,d', v) }
def nowStr = { -> new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(new Date()) }

// ── Gömülü özellik araştırması (salt-okur, en iyi çaba, tamamen korumalı) ───
// ImageServerMetadata gömülü tarayıcı anahtarlarını içermez; bunlar okuyucuya
// özgüdür. Sunucuda parametresiz + Map dönen "metadata/properties" adlı bir
// yöntem varsa onu yansıma (reflection) ile okuruz; yoksa boş Map döner.
def probeEmbedded = { server ->
    def out = new java.util.LinkedHashMap()
    try {
        for (m in server.getClass().getMethods()) {
            try {
                if (m.getParameterCount() != 0) continue
                if (!java.util.Map.class.isAssignableFrom(m.getReturnType())) continue
                def nm = m.getName().toLowerCase(java.util.Locale.ROOT)
                if (!(nm.contains('metadata') || nm.contains('properties') || nm.contains('props'))) continue
                if (nm.contains('classification')) continue
                def val = m.invoke(server)
                if (!(val instanceof java.util.Map)) continue
                val.each { k, v ->
                    try {
                        if (k == null) return
                        def ks = k.toString()
                        if (ks.isEmpty() || out.containsKey(ks)) return
                        out[ks] = (v == null ? '' : v.toString())
                    } catch (Throwable ignore) {}
                }
            } catch (Throwable ignore) {}
            if (out.size() > 300) break
        }
    } catch (Throwable ignore) {}
    return out
}

// Gömülü anahtarlardan tarayıcı alanlarını eşle (metadata-qupath ile aynı mantık)
def applyEmbedded = { m, embedded ->
    embedded.each { k, v ->
        try {
            def ks = k.toString()
            def kl = ks.toLowerCase(java.util.Locale.ROOT)
            def vs = (v == null ? '' : v.toString())
            if (kl.contains('scannertype'))                                         m.scanner_type = vs
            else if (kl.contains('scanner') || kl.contains('instrument') || kl.contains('device')) { if (!m.scanner) m.scanner = vs }
            else if (kl.contains('scanscope'))                                      m.scanscope_id = vs
            else if (kl.contains('apparent magnification'))                         m.apparent_magnification = vs
            else if (kl == 'mpp' || kl.contains('mpp'))                             { if (!m.mpp) m.mpp = vs }
            else if (ks == 'Date')                                                  m.scan_date = vs
            else if (ks == 'Time')                                                  m.scan_time = vs
            else if (kl.contains('compression'))                                    { if (!m.compression) m.compression = vs }
            else if (kl.contains('warning'))                                        m.scan_warning = vs
            // Ham döküm: her gömülü anahtar raw_<anahtar> sütunu olarak
            def rawKey = 'raw_' + ks.replaceAll('[\\r\\n",]', ' ').trim()
            if (!m.containsKey(rawKey)) m[rawKey] = vs
        } catch (Throwable ignore) {}
    }
}

// ── Tek görüntüden çekirdek üst veriyi çıkar (LinkedHashMap → mantıksal sıra) ─
def extractMetadata = { imageData, entry, project ->
    def m = new java.util.LinkedHashMap()
    def server = imageData.getServer()

    // Temel kimlik
    m.image_name      = (entry != null ? entry.getImageName() : null) ?: (server.getMetadata().getName() ?: 'slide')
    m.project_name    = (project != null ? project.getName() : '')
    m.extraction_date = nowStr()
    m.qupath_version  = GeneralTools.getVersion()

    // Görüntü özellikleri
    int w = server.getWidth(); int h = server.getHeight()
    m.width_pixels    = w
    m.height_pixels   = h
    m.num_channels    = server.nChannels()
    m.num_z_slices    = server.nZSlices()
    m.num_timepoints  = server.nTimepoints()
    m.pyramid_levels  = server.nResolutions()
    m.is_rgb          = server.isRGB()
    try { m.pixel_type = server.getPixelType()?.toString() ?: 'Unknown' } catch (Throwable t) { m.pixel_type = 'Unknown' }
    try { m.server_type = server.getServerType() ?: server.getClass().getSimpleName() } catch (Throwable t) { m.server_type = server.getClass().getSimpleName() }
    m.image_area_pixels = ((long) w) * ((long) h)

    // Piksel kalibrasyonu + fiziksel boyut
    double pw = Double.NaN, ph = Double.NaN
    try {
        def cal = server.getPixelCalibration()
        if (cal != null) {
            pw = cal.getPixelWidthMicrons()
            ph = cal.getPixelHeightMicrons()
            try { m.pixel_units = cal.getPixelWidthUnit()?.toString() ?: 'pixel' } catch (Throwable t) { m.pixel_units = 'pixel' }
        }
    } catch (Throwable ignore) {}
    m.pixel_width_um  = pw
    m.pixel_height_um = ph
    if (!Double.isNaN(pw) && pw > 0) {
        double wu = w * pw, hu = h * ph
        m.width_um  = wu
        m.height_um = hu
        m.area_mm2  = (wu * hu) / 1_000_000.0d
        m.estimated_magnification = Math.round(0.25d / pw * 40)   // sezgisel
    }
    // Gömülü objektif büyütme (varsa) — sezgiselden güvenilir
    try { double mag = server.getMetadata().getMagnification(); if (!Double.isNaN(mag) && mag > 0) m.objective_magnification = mag } catch (Throwable ignore) {}

    // Dosya bilgisi
    try {
        def uris = server.getURIs()
        if (uris != null && !uris.isEmpty()) {
            def uriString = uris.iterator().next().toString()
            m.file_uri = uriString
            def path = uriString
            try { if (uriString.startsWith('file:')) path = new File(new URI(uriString)).getAbsolutePath() } catch (Throwable ignore) {}
            m.file_path = path
            int lastDot = path.lastIndexOf('.')
            m.file_extension = (lastDot > 0) ? path.substring(lastDot + 1).toLowerCase(java.util.Locale.ROOT) : 'unknown'
            def f = new File(path)
            if (f.exists()) {
                m.file_size_bytes = f.length()
                m.file_size_mb    = Math.round(f.length() / (1024.0d * 1024.0d) * 100) / 100.0d
                m.last_modified   = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(new Date(f.lastModified()))
            }
        }
    } catch (Throwable ignore) {}

    // İlişkili görüntüler (makro / etiket)
    try {
        def assoc = server.getAssociatedImageList()
        if (assoc != null) {
            m.has_macro_image = assoc.any { it?.toString()?.toLowerCase(java.util.Locale.ROOT)?.contains('macro') }
            m.has_label_image = assoc.any { it?.toString()?.toLowerCase(java.util.Locale.ROOT)?.contains('label') }
            m.associated_image_count = assoc.size()
            m.associated_images = assoc.join(', ')
        }
    } catch (Throwable ignore) {}

    // Kalite göstergeleri
    m.has_pyramid = server.nResolutions() > 1
    if (m.has_pyramid) {
        try {
            double[] ds = server.getPreferredDownsamples()   // seviye-başı altörnekleme oranları
            if (ds != null && ds.length > 1 && ds[0] > 0) m.pyramid_factor = Math.round(ds[1] / ds[0] * 100) / 100.0d
        } catch (Throwable ignore) {}
    }
    // Önerilen analiz seviyesi (~1 µm/px hedefi)
    m.suggested_analysis_level = 0
    if (!Double.isNaN(pw) && pw > 0) {
        for (int level = 0; level < server.nResolutions(); level++) {
            try {
                double ds = server.getDownsampleForResolution(level)
                if (pw * ds >= 1.0d) { m.suggested_analysis_level = level; break }
            } catch (Throwable t) { break }
        }
    }
    // Türetilmiş işaretler
    m.is_fluorescence = (m.num_channels > 3) || !(m.is_rgb)
    m.needs_pyramid   = !(m.has_pyramid) && (m.image_area_pixels > 100_000_000L)   // >100 MP

    // Gömülü tarayıcı anahtarları + ham döküm (en iyi çaba)
    try { applyEmbedded(m, probeEmbedded(server)) } catch (Throwable ignore) {}
    return m
}

// ── Tek slayt için okunabilir pano metni ────────────────────────────────────
def reportText = { m ->
    def sb = new StringBuilder()
    def line = { String label, Object val -> sb << String.format(java.util.Locale.US, '  %-22s : %s%n', label, (val == null ? '—' : val.toString())) }
    sb << 'SLAYT ÜST VERİSİ\n'
    sb << '═════════════════\n'
    line('Görüntü', m.image_name)
    line('Boyut (px)', fmtInt((long) m.width_pixels) + ' × ' + fmtInt((long) m.height_pixels))
    line('Kanal / z / t', '' + m.num_channels + ' / ' + m.num_z_slices + ' / ' + m.num_timepoints)
    line('RGB', m.is_rgb ? 'evet' : 'hayır')
    line('Piksel tipi', m.pixel_type)
    line('Sunucu', m.server_type)
    sb << '\n'
    line('Piksel (µm/px)', (m.pixel_width_um != null && !Double.isNaN((double) m.pixel_width_um)) ? (fmt((double) m.pixel_width_um, 4) + ' × ' + fmt((double) m.pixel_height_um, 4)) : '— (kalibre değil)')
    line('Kestirilen büyütme', (m.estimated_magnification != null) ? ('~' + m.estimated_magnification + '×  (sezgisel)') : '—')
    if (m.objective_magnification != null) line('Objektif büyütme', fmt((double) m.objective_magnification, 1) + '×  (gömülü)')
    if (m.area_mm2 != null) line('Doku alanı (mm²)', fmt((double) m.area_mm2, 1))
    sb << '\n'
    line('Piramit', (m.has_pyramid ? ('evet, ' + m.pyramid_levels + ' seviye') : 'YOK ⚠'))
    if (m.pyramid_factor != null) line('Piramit faktörü', fmt((double) m.pyramid_factor, 2))
    line('Önerilen analiz sv.', m.suggested_analysis_level)
    sb << '\n'
    if (m.file_extension != null) line('Dosya türü', m.file_extension)
    if (m.file_size_mb != null) line('Dosya boyutu (MB)', fmt((double) m.file_size_mb, 1))
    if (m.scanner != null) line('Tarayıcı', m.scanner)
    if (m.scanner_type != null) line('Tarayıcı tipi', m.scanner_type)
    if (m.scan_date != null) line('Tarama tarihi', m.scan_date + (m.scan_time != null ? (' ' + m.scan_time) : ''))
    int rawN = m.keySet().count { it.toString().startsWith('raw_') }
    if (rawN > 0) sb << String.format(java.util.Locale.US, '%n  (+ %d gömülü ham anahtar — CSV\'de raw_* sütunları)%n', rawN)
    sb << '\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'
    return sb.toString()
}

// ── CSV yazımı (birleşik başlık, RFC-4180 kaçışı) ────────────────────────────
def csvEscape = { Object v ->
    if (v == null) return ''
    def s = v.toString()
    (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) ? ('"' + s.replace('"', '""') + '"') : s
}
def writeCsv = { List rows, File csvFile ->
    def keys = new java.util.LinkedHashSet()
    rows.each { r -> r.keySet().each { keys.add(it) } }   // çekirdek-önce ekleme sırası korunur
    def keyList = new ArrayList(keys)
    csvFile.withWriter('UTF-8') { writer ->
        writer.writeLine(keyList.collect { csvEscape(it) }.join(','))
        rows.each { r -> writer.writeLine(keyList.collect { csvEscape(r[it]) }.join(',')) }
    }
}

// ── İsteğe bağlı: çekirdek alanları Proje sekmesi sütunlarına yaz ────────────
def writeProjectColumns = { entry, m ->
    try {
        def md = entry.getMetadata()
        if (m.pixel_width_um != null && !Double.isNaN((double) m.pixel_width_um)) md.put('mpp', fmt((double) m.pixel_width_um, 3))
        if (m.estimated_magnification != null) md.put('buyutme', m.estimated_magnification.toString() + 'x')
        if (m.scanner != null) md.put('tarayici', m.scanner.toString())
        md.put('boyut', '' + m.width_pixels + ' x ' + m.height_pixels)
        md.put('piramit', (m.has_pyramid ? 'evet' : 'hayir'))
        if (m.area_mm2 != null) md.put('alan_mm2', fmt((double) m.area_mm2, 1))
    } catch (Throwable ignore) {}
}

// ── Headless: yalnız açık slaydı raporla (UI yok, değişiklik yok) ────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println 'Önce bir slayt açın (headless modda yalnız açık slayt raporlanır).'; return }
    println reportText(extractMetadata(imageData, QP.getProjectEntry(), QP.getProject()))
    def project = QP.getProject()
    if (project != null) println '\nTüm proje taraması + CSV için sihirbazı QuPath arayüzünde açın.'
    return
}

// ── Durum makinesi: READY | SCANNING | RESULT | ERROR ───────────────────────
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def writeColsRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def reportRef     = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def outDirRef     = new java.util.concurrent.atomic.AtomicReference(null)
def render  // ileri bildirim

def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text)
    b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    return b
}
def busyBar = { -> def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb }
def copyToClipboard = { String txt ->
    def cb = javafx.scene.input.Clipboard.getSystemClipboard()
    def content = new javafx.scene.input.ClipboardContent()
    content.putString(txt ?: '')
    cb.setContent(content)
}

// ── Çalışma dizinini çöz (<proje>/cohort_metadata → yoksa geçici) ───────────
def resolveOutDir = { project ->
    if (project != null && project.getPath() != null) {
        def parent = project.getPath().getParent()
        if (parent != null) return new File(parent.toFile(), 'cohort_metadata')
    }
    return new File(System.getProperty('java.io.tmpdir'), 'cohort_metadata')
}

// ── Özet metni ───────────────────────────────────────────────────────────────
def buildSummary = { List rows, List errors, File outDir, File csvFile, boolean wroteCols ->
    def sb = new StringBuilder()
    sb << 'KOHORT METADATA — ÖZET\n'
    sb << '════════════════════════\n\n'
    sb << String.format(java.util.Locale.US, '  Taranan slayt   : %,d%n', rows.size())
    sb << String.format(java.util.Locale.US, '  Atlanan / hatalı: %,d%n', errors.size())
    def pixels = rows.collect { it.pixel_width_um }.findAll { it != null && !Double.isNaN((double) it) && it > 0 }
    if (!pixels.isEmpty()) {
        sb << String.format(java.util.Locale.US, '  Piksel (µm/px)  : %.3f – %.3f%n', (double) pixels.min(), (double) pixels.max())
    }
    int withPyr = rows.count { it.has_pyramid }
    sb << String.format(java.util.Locale.US, '  Piramitli       : %,d / %,d%n', withPyr, rows.size())
    def scanners = rows.collect { it.scanner }.findAll { it }.unique()
    if (!scanners.isEmpty()) sb << '  Tarayıcılar     : ' + scanners.join(', ') + '\n'
    def formats = rows.collect { it.file_extension }.findAll { it }.countBy { it }
    if (!formats.isEmpty()) sb << '  Biçimler        : ' + formats.collect { k, v -> k + '(' + v + ')' }.join(', ') + '\n'
    double totMB = 0.0d; rows.each { if (it.file_size_mb != null) totMB += (double) it.file_size_mb }
    if (totMB > 0) sb << String.format(java.util.Locale.US, '  Toplam boyut    : %,d MB%n', Math.round(totMB))
    sb << '\n  CSV   : ' + csvFile.getAbsolutePath() + '\n'
    sb << '  Özet  : ' + new File(outDir, 'detailed_summary.txt').getAbsolutePath() + '\n'
    if (wroteCols) sb << '\n  ✓ Çekirdek alanlar Proje sekmesine sütun olarak da yazıldı (sıralanabilir).\n'
    if (!errors.isEmpty()) {
        sb << '\n  Hatalar:\n'
        errors.take(12).each { sb << '   • ' + it + '\n' }
        if (errors.size() > 12) sb << '   … (+' + (errors.size() - 12) + ' daha)\n'
    }
    sb << '\nCSV\'yi Excel/R/Python\'da açıp kohort dahil/dışlama ölçütlerinizi tanımlayın.\n'
    sb << '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'
    return sb.toString()
}

// ── Düz metin özet dosyası ───────────────────────────────────────────────────
def writeSummaryFile = { List rows, List errors, File outDir, File csvFile, String summaryText ->
    try {
        def f = new File(outDir, 'detailed_summary.txt')
        f.withWriter('UTF-8') { w ->
            w.writeLine('Kohort Metadata — ' + nowStr())
            w.writeLine('QuPath ' + GeneralTools.getVersion())
            w.writeLine(summaryText.replace('—', '-'))
        }
    } catch (Throwable ignore) {}
}

// ── Tüm projeyi tara (arka plan iş parçacığı) ───────────────────────────────
def startScan = {
    def project = QP.getProject()
    if (project == null) { errorTextRef.set('Açık proje yok. Önce bir QuPath projesi açın.'); step.set('ERROR'); render(); return }
    def entries = project.getImageList()
    if (entries == null || entries.isEmpty()) { errorTextRef.set('Projede görüntü yok.'); step.set('ERROR'); render(); return }
    boolean wantCols = writeColsRef.get()
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    step.set('SCANNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def outDir = resolveOutDir(project)
        try { outDir.mkdirs() } catch (Throwable ignore) {}
        outDirRef.set(outDir)
        appendLine('Çıktı dizini: ' + outDir.getAbsolutePath())
        def rows = []; def errors = []
        int total = entries.size(); int i = 0
        for (entry in entries) {
            if (cancelledRef.get()) { appendLine('İptal edildi.'); break }
            i++
            def nm = entry.getImageName()
            appendLine(String.format(java.util.Locale.US, '[%d/%d] %s', i, total, nm))
            try {
                entry.readImageData().withCloseable { ed ->
                    def m = extractMetadata(ed, entry, project)
                    rows << m
                    if (wantCols) writeProjectColumns(entry, m)
                }
            } catch (Throwable t) {
                errors << (nm + ' → ' + (t.getMessage() ?: t.getClass().getSimpleName()))
                appendLine('   ⚠ atlandı: ' + (t.getMessage() ?: t.getClass().getSimpleName()))
            }
        }
        boolean wroteCols = false
        if (wantCols && !rows.isEmpty()) {
            try { project.syncChanges(); wroteCols = true } catch (Throwable t) { errors << ('Proje sütunları kaydedilemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName())) }
        }
        def csvFile = new File(outDir, 'cohort_metadata.csv')
        if (!rows.isEmpty()) {
            try { writeCsv(rows, csvFile) }
            catch (Throwable t) { javafx.application.Platform.runLater { errorTextRef.set('CSV yazılamadı:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }; return }
        }
        def summary = buildSummary(rows, errors, outDir, csvFile, wroteCols)
        writeSummaryFile(rows, errors, outDir, csvFile, summary)
        javafx.application.Platform.runLater {
            if (rows.isEmpty()) { errorTextRef.set('Hiçbir görüntü okunamadı.\n' + (errors.isEmpty() ? '' : errors.take(8).join('\n'))); step.set('ERROR'); render() }
            else { resultTextRef.set(summary); step.set('RESULT'); render() }
        }
    }, 'AtolyeMetadata-Scan')
    worker.setDaemon(true); worker.start()
}

// ── Render: her durum değişiminde sahneyi sıfırdan kurar ────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10)
    center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)
    def actions = new ArrayList()

    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); center.getChildren().add(lbl) }
    def addMonoArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: '')
        ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(ta)
    }
    def addLiveLog = { -> def la = logAreaRef.get(); if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) } }

    if (cur == 'READY') {
        def imageData = QP.getCurrentImageData()
        def project = QP.getProject()
        title.setText('Kohort metadata sihirbazı')
        if (imageData != null) {
            def rep
            try { rep = reportText(extractMetadata(imageData, QP.getProjectEntry(), project)) }
            catch (Throwable t) { rep = 'Açık slayt okunamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName()) }
            reportRef.set(rep)
            addMonoArea(rep)
        } else {
            reportRef.set('')
            addGuidance('Tek slayt raporu için bir slayt açın. Tüm projeyi taramak için bir proje açıp aşağıdaki düğmeyi kullanın.')
        }
        def chk = new javafx.scene.control.CheckBox('Proje sütunlarına da yaz (sıralanabilir)')
        chk.setSelected(writeColsRef.get())
        chk.selectedProperty().addListener({ obs, o, n -> writeColsRef.set(n) } as javafx.beans.value.ChangeListener)
        chk.setTooltip(new javafx.scene.control.Tooltip('İşaretliyse çekirdek alanlar (mpp, büyütme, tarayıcı, boyut, piramit, alan) QuPath Proje sekmesine sütun olarak yazılır.'))
        center.getChildren().add(chk)

        actions.add(navButton('Kapat', { stage.close() }))
        if (imageData != null) actions.add(navButton('Kopyala', { copyToClipboard(reportRef.get()) }, 'Bu slayt raporunu panoya kopyala'))
        actions.add(navButton('⟳ Yenile', { render() }))
        def scanBtn = navButton('Tüm projeyi tara → CSV ▶', { startScan() }, 'Projedeki her görüntünün üst verisini CSV\'ye yazar')
        scanBtn.setDisable(project == null)
        actions.add(scanBtn)
    } else if (cur == 'SCANNING') {
        title.setText('Proje taranıyor…')
        addGuidance('Her görüntü diskten okunuyor; büyük kohortlarda zaman alabilir. Çıktı aşağıda akıyor.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true) }))
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅')
        addMonoArea(resultTextRef.get())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        def dir = outDirRef.get()
        def openBtn = navButton('Klasörü aç', {
            try { if (java.awt.Desktop.isDesktopSupported() && dir != null && dir.isDirectory()) java.awt.Desktop.getDesktop().open(dir) }
            catch (Throwable t) { Dialogs.showErrorMessage('Klasör açılamadı', t.getMessage() ?: '') }
        })
        openBtn.setDisable(dir == null)
        actions.add(openBtn)
        actions.add(navButton('↻ Yeniden', { step.set('READY'); render() }))
    } else { // ERROR
        title.setText('Hata')
        addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Geri', { step.set('READY'); render() }))
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
    stage.setScene(new javafx.scene.Scene(root, 820, 640))
}

// ── Açılış ───────────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Kohort metadata sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println '✓ Kohort metadata sihirbazı açıldı.'
