/**
 * Yardımcı - Görüntü Alanı Çıkart (Extract Region) Sihirbazı (tek pencere)
 * -------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Aperio ImageScope'un "Extract Region" aracının QuPath-yerel karşılığı.
 *   SEÇİLİ anotasyon(lar)ın sınırlayıcı kutusunu, tam çözünürlükte (ya da
 *   yeniden boyutlandırılmış) bağımsız bir görüntü dosyasına çıkarır — her
 *   anotasyon için bir dosya + yanında bir .json künyesi (koordinat/çözünürlük).
 *   Biçimler:
 *     • Piramidal OME-TIFF (.ome.tif)  — açık, QuPath/OMERO/Fiji ile yeniden açılır
 *     • Düz TIFF (.tif) · PNG (.png) · JPEG (.jpg, kalite ayarlı)
 *   Sıkıştırma (OME-TIFF için), yeniden boyutlandırma yüzdesi / hedef µm/px,
 *   parça (tile) boyutu ve tahmini dosya boyutu seçilebilir.
 *
 * SINIRLAR (ImageScope'ta olup QuPath'te OLMAYAN):
 *   • Gerçek .svs ÜRETİLEMEZ (Aperio'ya özel biçim) → açık karşılığı piramidal
 *     OME-TIFF'tir. • Thumbnail/Label/Macro (Aperio ek görüntüleri) OME-TIFF'e
 *     gömülemez. • ICC profili UYGULAMA QuPath 0.6'da desteklenmez (qupath#982;
 *     bkz. Ekler → Renk Yönetimi). • CWS biçimi yoktur.
 *   Bu araç piksel dosyası üretir; nesne/ölçüm/hiyerarşiyi DEĞİŞTİRMEZ.
 *
 * KULLANIM:
 *   1. Bir slayt açın; Rectangle (R) ya da herhangi bir alan aracıyla bölgeyi
 *      çizip SEÇİN (birden çok seçiliyse her biri ayrı dosyaya çıkar).
 *   2. [Extensions → Atölye → Yardımcılar → İçe/dışa aktarma & veri → Görüntü alanı çıkart (Extract Region)]
 *   3. Biçim / çözünürlük / sıkıştırmayı seçip "Çıkart".
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlıdır.
 */

import qupath.fx.dialogs.Dialogs
import qupath.fx.dialogs.FileChoosers
import qupath.lib.scripting.QP
import qupath.lib.images.writers.ImageWriterTools
import qupath.lib.images.writers.ome.OMEPyramidWriter
import qupath.lib.images.writers.ome.OMEPyramidWriter.CompressionType
import qupath.lib.regions.RegionRequest
import qupath.lib.regions.ImageRegion
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.IIOImage
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"

// ── Kalıcı seçimler (serbest biçim, makineye özel) — yerel prefs düğümü ─────────
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/bolge-cikart')

// ── Atölye ayarları köprüsü — yalnız 'exports' klasör adı için (ortak, kayıtlı) ─
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeS = { String k, String d -> (String) __wpCall('str', [String.class, String.class] as Class[], [k, d] as Object[], d) }
def exportFolder = atolyeS('atolye.exportFolder', 'exports')

// ── Yardımcılar ────────────────────────────────────────────────────────────────
def toSlug = { String s ->
    if (s == null || s.trim().isEmpty()) return 'bolge'
    return s.replace('ı', 'i').replace('İ', 'I').replace('ğ', 'g').replace('Ğ', 'G')
            .replace('ü', 'u').replace('Ü', 'U').replace('ş', 's').replace('Ş', 'S')
            .replace('ö', 'o').replace('Ö', 'O').replace('ç', 'c').replace('Ç', 'C')
            .replaceAll('[^a-zA-Z0-9_\\-]', '_').replaceAll('_+', '_')
}
def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().trim()) } catch (Throwable t) { return d } }
def parseIntOr    = { s, int d    -> try { return Integer.parseInt((s ?: '').toString().trim()) }    catch (Throwable t) { return d } }
def jsonEsc = { String s -> (s ?: '').replace('\\', '\\\\').replace('"', '\\"').replace('\n', ' ').replace('\r', ' ').replace('\t', ' ') }

def FORMATS = ['OME-TIFF (piramidal .ome.tif)', 'TIFF düz (.tif)', 'PNG (.png)', 'JPEG (.jpg)']
def FMT_OME = FORMATS[0]; def FMT_TIF = FORMATS[1]; def FMT_PNG = FORMATS[2]; def FMT_JPG = FORMATS[3]
def extFor = { String fmt -> if (fmt == FMT_OME) return '.ome.tif'; if (fmt == FMT_TIF) return '.tif'; if (fmt == FMT_PNG) return '.png'; return '.jpg' }

// Seçili ALAN anotasyonları (nokta/çizgi/boş ROI hariç)
def selectedAreaAnns = { -> QP.getSelectedObjects().findAll { it.isAnnotation() && it.hasROI() && it.getROI().isArea() && it.getROI().getArea() > 0.0d } }

// Sınırlayıcı kutuyu görüntü içine kırp → [x,y,w,h] ya da null
def clampedBounds = { roi, server ->
    int rx = Math.max(0, (int) Math.floor(roi.getBoundsX()))
    int ry = Math.max(0, (int) Math.floor(roi.getBoundsY()))
    int rw = Math.min((int) Math.ceil(roi.getBoundsWidth()),  server.getWidth()  - rx)
    int rh = Math.min((int) Math.ceil(roi.getBoundsHeight()), server.getHeight() - ry)
    if (rw <= 0 || rh <= 0) return null
    return [rx, ry, rw, rh] as int[]
}

// Yeniden boyutlandırma → downsample (≥ 1.0; yukarı-örnekleme yok)
def downsampleFor = { server, double resizePct, String umppText ->
    def umpp = parseDoubleOr(umppText, -1.0d)
    if (umpp > 0.0d) {
        double nat = server.getPixelCalibration().getAveragedPixelSizeMicrons()
        if (nat > 0.0d) return Math.max(1.0d, umpp / nat)
    }
    double pct = (resizePct > 0.0d && resizePct <= 100.0d) ? resizePct : 100.0d
    return Math.max(1.0d, 100.0d / pct)
}

def bytesPerChannelOf = { server -> try { return Math.max(1, server.getPixelType().getBytesPerPixel()) } catch (Throwable t) { return 1 } }

def humanBytes = { double b ->
    if (b >= 1024.0d*1024.0d*1024.0d) return String.format(java.util.Locale.US, '%.2f GB', b / (1024.0d*1024.0d*1024.0d))
    if (b >= 1024.0d*1024.0d)         return String.format(java.util.Locale.US, '%.1f MB', b / (1024.0d*1024.0d))
    return String.format(java.util.Locale.US, '%.0f KB', b / 1024.0d)
}

// Çakışma korumalı hedef dosya (base, base-2, base-3, …)
def uniqueFile = { File dir, String base, String ext ->
    def f = new File(dir, base + ext)
    int i = 2
    while (f.exists()) { f = new File(dir, base + '-' + i + ext); i++ }
    return f
}

// ── Headless: yapılandırmayı raporla, etkileşim yok ─────────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println 'Önce bir slayt açın.'; return }
    println 'Görüntü alanı çıkart sihirbazı: QuPath arayüzü gerektirir (headless çalıştırılamaz).'
    println 'Seçili alan anotasyonu sayısı: ' + selectedAreaAnns().size()
    println '⚠️ Yalnızca araştırma/eğitim amaçlıdır.'
    return
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
// READY | CONFIG | RUNNING | RESULT | ERROR
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
// CONFIG denetimleri (render her seferinde yeniden kurar; startExport bunlardan okur)
def fmtBoxRef  = new java.util.concurrent.atomic.AtomicReference(null)
def compBoxRef = new java.util.concurrent.atomic.AtomicReference(null)
def qualRef    = new java.util.concurrent.atomic.AtomicReference(null)
def tileRef    = new java.util.concurrent.atomic.AtomicReference(null)
def resizeRef  = new java.util.concurrent.atomic.AtomicReference(null)
def umppRef    = new java.util.concurrent.atomic.AtomicReference(null)
def descRef    = new java.util.concurrent.atomic.AtomicReference(null)
def outDirRef  = new java.util.concurrent.atomic.AtomicReference(null)   // File
def render  // ileri bildirim

def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text)
    b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    return b
}
def busyBar = { -> def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb }
def copyToClipboard = { String txt ->
    def cb = javafx.scene.input.Clipboard.getSystemClipboard()
    def c = new javafx.scene.input.ClipboardContent(); c.putString(txt ?: ''); cb.setContent(c)
}

// Varsayılan çıktı klasörü (proje varsa proje/exports/regions, yoksa ~/QuPath/…)
def defaultOutDir = {
    def project = QP.getProject()
    def baseDir = (project != null && project.getPath() != null)
        ? project.getPath().getParent().toFile()
        : new File(System.getProperty('user.home'), 'QuPath')
    return new File(new File(baseDir, exportFolder), 'regions')
}

// Sunucu için uyumlu sıkıştırma tipleri (OME-TIFF)
def compressionChoices = { server ->
    def out = []
    try {
        for (ct in CompressionType.values()) {
            boolean ok = true
            try { ok = ct.supportsImage(server) } catch (Throwable t) { ok = true }
            if (ok) out << ct.name()
        }
    } catch (Throwable t) { out = ['DEFAULT', 'LZW', 'UNCOMPRESSED', 'ZLIB'] }
    if (out.isEmpty()) out = ['DEFAULT', 'LZW', 'UNCOMPRESSED']
    return out
}

// ── Tek bölge çıkarımı (arka plan iş parçacığında çağrılır) ─────────────────────
def exportOne = { server, ann, java.util.Map cfg, File outFile ->
    def roi = ann.getROI()
    def cb = clampedBounds(roi, server)
    if (cb == null) throw new IOException('Bölge tümüyle görüntü dışında')
    int rx = cb[0], ry = cb[1], rw = cb[2], rh = cb[3]
    boolean clamped = (rx != (int) Math.floor(roi.getBoundsX())
                    || ry != (int) Math.floor(roi.getBoundsY())
                    || rw != (int) Math.ceil(roi.getBoundsWidth())
                    || rh != (int) Math.ceil(roi.getBoundsHeight()))
    double ds = (double) cfg.ds
    int z = roi.getZ(), t = roi.getT()
    String fmt = (String) cfg.format

    if (fmt == FMT_OME) {
        def region = ImageRegion.createInstance(rx, ry, rw, rh, z, t)
        def builder = new OMEPyramidWriter.Builder(server).region(region).tileSize((int) cfg.tile).parallelize()
        try { builder = builder.compression(CompressionType.valueOf((String) cfg.compression)) } catch (Throwable t2) {}
        int outW = (int) (rw / ds), outH = (int) (rh / ds)
        builder = (Math.max(outW, outH) > ((int) cfg.tile) * 4) ? builder.scaledDownsampling(ds, 2.0d) : builder.downsamples(ds)
        if (((String) cfg.compression) == 'JPEG' && ((int) cfg.quality) > 0) {
            try { def opts = new loci.formats.codec.CodecOptions(); opts.quality = ((int) cfg.quality) / 100.0d; builder = builder.codecOptions(opts) } catch (Throwable t3) {}
        }
        builder.build().writeSeries(outFile.getAbsolutePath())
    } else if (fmt == FMT_JPG) {
        def request = RegionRequest.createInstance(server.getPath(), ds, rx, ry, rw, rh, z, t)
        def img = server.readRegion(request)
        if (img == null) throw new IOException('Bölge okunamadı')
        def rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB)
        def g2 = rgb.createGraphics(); g2.drawImage(img, 0, 0, java.awt.Color.WHITE, null); g2.dispose()
        def iw = ImageIO.getImageWritersByFormatName('jpg').next()
        def param = iw.getDefaultWriteParam()
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
        param.setCompressionQuality((float) (((int) cfg.quality) / 100.0d))
        def ios = ImageIO.createImageOutputStream(outFile)
        try { iw.setOutput(ios); iw.write(null, new IIOImage(rgb, null, null), param) }
        finally { try { ios.close() } catch (Throwable ig) {}; iw.dispose() }
    } else { // FMT_TIF / FMT_PNG (düz)
        def request = RegionRequest.createInstance(server.getPath(), ds, rx, ry, rw, rh, z, t)
        ImageWriterTools.writeImageRegion(server, request, outFile.getAbsolutePath())
    }
    return [rx: rx, ry: ry, rw: rw, rh: rh, clamped: clamped, ds: ds,
            outW: (int) (rw / ds), outH: (int) (rh / ds)]
}

// Künye (.json) yaz — groovy.json KULLANILMAZ (elle, güvenli kaçışlı)
def writeSidecar = { File jsonFile, server, ann, java.util.Map cfg, java.util.Map r ->
    double nat = 0.0d
    try { nat = server.getPixelCalibration().getAveragedPixelSizeMicrons() } catch (Throwable t) {}
    def sb = new StringBuilder()
    sb << '{\n'
    sb << '  "slide": "'          << jsonEsc(server.getMetadata().getName()) << '",\n'
    sb << '  "annotation": "'     << jsonEsc(ann.getName() ?: '') << '",\n'
    sb << '  "description": "'    << jsonEsc((String) cfg.desc) << '",\n'
    sb << String.format(java.util.Locale.US, '  "regionX": %d,%n', (int) r.rx)
    sb << String.format(java.util.Locale.US, '  "regionY": %d,%n', (int) r.ry)
    sb << String.format(java.util.Locale.US, '  "regionWidth": %d,%n', (int) r.rw)
    sb << String.format(java.util.Locale.US, '  "regionHeight": %d,%n', (int) r.rh)
    sb << String.format(java.util.Locale.US, '  "downsample": %.4f,%n', (double) r.ds)
    sb << String.format(java.util.Locale.US, '  "outputWidthPx": %d,%n', (int) r.outW)
    sb << String.format(java.util.Locale.US, '  "outputHeightPx": %d,%n', (int) r.outH)
    sb << String.format(java.util.Locale.US, '  "nativeUmPerPx": %.5f,%n', nat)
    sb << String.format(java.util.Locale.US, '  "exportUmPerPx": %.5f,%n', (nat > 0.0d ? nat * (double) r.ds : 0.0d))
    sb << '  "format": "'         << jsonEsc((String) cfg.format) << '",\n'
    sb << '  "compression": "'    << jsonEsc((String) cfg.compression) << '",\n'
    sb << String.format(java.util.Locale.US, '  "tileSize": %d,%n', (int) cfg.tile)
    sb << '  "boundsClamped": '   << (((boolean) r.clamped) ? 'true' : 'false') << ',\n'
    sb << '  "timestamp": "'      << LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyy-MM-dd_HHmmss')) << '"\n'
    sb << '}\n'
    jsonFile.setText(sb.toString(), 'UTF-8')
}

// ── Çıkarım akışı (FX düğmesinden tetiklenir; iş arka planda) ───────────────────
def startExport = {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def anns = selectedAreaAnns()
    if (anns.isEmpty()) { errorTextRef.set('Önce bir ALAN anotasyonu (dikdörtgen vb.) çizip SEÇİN.'); step.set('ERROR'); render(); return }
    def server = imageData.getServer()

    // Denetimlerden yapılandırmayı oku
    def fmtBox = fmtBoxRef.get(); def compBox = compBoxRef.get(); def qual = qualRef.get()
    def tile = tileRef.get(); def resize = resizeRef.get(); def umpp = umppRef.get(); def desc = descRef.get()
    def outDir = outDirRef.get() ?: defaultOutDir()
    String fmt = (fmtBox != null) ? (String) fmtBox.getValue() : FMT_OME
    String comp = (compBox != null && compBox.getValue() != null) ? (String) compBox.getValue() : 'DEFAULT'
    int quality = (qual != null) ? (int) qual.getValue() : 85
    int tileSize = parseIntOr(tile?.getText(), 512); if (tileSize < 16) tileSize = 512
    double resizePct = parseDoubleOr(resize?.getText(), 100.0d)
    double ds = downsampleFor(server, resizePct, umpp?.getText())
    String description = (desc != null) ? desc.getText() : ''

    def cfg = [format: fmt, compression: comp, quality: quality, tile: tileSize, ds: ds, desc: description]

    // Kalıcılaştır (son kullanılan)
    prefs.put('format', fmt); prefs.put('compression', comp); prefs.putInt('quality', quality)
    prefs.putInt('tile', tileSize); prefs.put('resizePct', String.format(java.util.Locale.US, '%.1f', resizePct))
    prefs.put('umpp', umpp?.getText() ?: ''); prefs.put('outDir', outDir.getAbsolutePath())
    try { prefs.flush() } catch (Throwable ig) {}

    // Büyük çıkarım onayı (en büyük bölge > 1 GB sıkıştırılmamış)
    long nCh = server.nChannels(); int bpc = bytesPerChannelOf(server)
    long maxBytes = 0L
    anns.each { ann ->
        def cb = clampedBounds(ann.getROI(), server)
        if (cb != null) {
            long ow = (long) (cb[2] / ds), oh = (long) (cb[3] / ds)
            long b = ow * oh * nCh * bpc
            if (b > maxBytes) maxBytes = b
        }
    }
    if (maxBytes > 1_073_741_824L) {
        boolean ok = Dialogs.showConfirmDialog('Büyük çıkarım',
            'En büyük bölge yaklaşık ' + humanBytes(maxBytes) + ' (sıkıştırılmamış).\n' +
            'Büyük slaytlarda dakikalar sürebilir ve diskte çok yer kaplayabilir.\n\nDevam edilsin mi?')
        if (!ok) { return }
    }

    outDir.mkdirs()
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    step.set('RUNNING'); render()

    def slideSlug = toSlug(server.getMetadata().getName())
    def stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyy-MM-dd_HHmmss'))
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def written = []
        int idx = 0, n = anns.size()
        appendLine('Çıktı klasörü: ' + outDir.getAbsolutePath())
        appendLine(String.format(java.util.Locale.US, 'Biçim: %s · sıkıştırma: %s · downsample: %.3f · %d bölge', fmt, comp, ds, n))
        try {
            for (ann in anns) {
                idx++
                if (cancelledRef.get()) { appendLine('İptal edildi (' + (idx - 1) + '/' + n + ' tamamlandı).'); break }
                def nm = ann.getName()
                def base = toSlug(nm ?: (slideSlug + '_' + String.format(java.util.Locale.US, '%03d', idx))) + '__' + stamp
                def outFile = uniqueFile(outDir, base, extFor(fmt))
                appendLine(idx + '/' + n + ': ' + outFile.getName() + ' …')
                try {
                    def r = exportOne(server, ann, cfg, outFile)
                    writeSidecar(new File(outDir, outFile.getName().replaceAll('\\.[^.]+$', '') + '.json'), server, ann, cfg, r)
                    written << outFile.getName()
                    appendLine(String.format(java.util.Locale.US, '   ✓ %,d × %,d px%s', (int) r.outW, (int) r.outH, (((boolean) r.clamped) ? ' (kenarda kırpıldı)' : '')))
                } catch (Throwable t) {
                    appendLine('   ✗ hata: ' + (t.getMessage() ?: t.getClass().getSimpleName()))
                }
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Çıkarım başarısız:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
            return
        }
        def sb = new StringBuilder()
        sb << 'GÖRÜNTÜ ALANI ÇIKARTMA — ÖZET\n'
        sb << '════════════════════════════════\n\n'
        sb << 'Yazılan dosya   : ' << written.size() << ' / ' << n << '\n'
        sb << 'Klasör          : ' << outDir.getAbsolutePath() << '\n'
        sb << String.format(java.util.Locale.US, 'Biçim/sıkıştırma: %s · %s%n', fmt, (fmt == FMT_OME ? comp : '—'))
        sb << String.format(java.util.Locale.US, 'Downsample      : %.3f (≈ %%%.0f)%n', ds, (100.0d / ds))
        if (!written.isEmpty()) { sb << '\nDosyalar:\n'; written.each { sb << '  • ' << it << '\n' } }
        sb << '\nHer görüntünün yanına bir .json künyesi (koordinat/çözünürlük) yazıldı.\n'
        if (fmt == FMT_OME) sb << 'OME-TIFF; QuPath/OMERO/Fiji ile yeniden açılabilir (gerçek .svs değil).\n'
        sb << '⚠️ Yalnızca araştırma/eğitim amaçlıdır.'
        javafx.application.Platform.runLater { resultTextRef.set(sb.toString()); step.set('RESULT'); render() }
    }, 'AtolyeBolgeCikart-Run')
    worker.setDaemon(true); worker.start()
}

// ── Render: her durum değişiminde sahneyi sıfırdan kurar ────────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def imageData = QP.getCurrentImageData()

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
    def addLiveLog = { -> def la = logAreaRef.get(); if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) } }

    boolean hasSel = (imageData != null) && !selectedAreaAnns().isEmpty()

    if (cur == 'READY' || (cur == 'CONFIG' && !hasSel)) {
        title.setText('Görüntü alanı çıkart (Extract Region)')
        if (imageData == null) {
            addGuidance('Önce bir slayt açın. Sonra Rectangle (R) ya da herhangi bir alan aracıyla ' +
                'çıkarmak istediğiniz bölgeyi çizip SEÇİN ve "⟳ Yenile"ye basın.')
        } else {
            addGuidance('Çıkarmak istediğiniz bölge için bir ALAN anotasyonu (dikdörtgen, poligon, fırça) ' +
                'çizip SEÇİN. Birden çok anotasyon seçiliyse her biri ayrı dosyaya çıkar. Sonra "⟳ Yenile".\n\n' +
                'Aperio ImageScope\'un "Extract Region" aracının QuPath-yerel karşılığıdır.')
        }
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('⟳ Yenile', { step.set(selectedAreaAnns().isEmpty() ? 'READY' : 'CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText('Görüntü alanı çıkart — ayarlar')
        def server = imageData.getServer()
        def anns = selectedAreaAnns()

        // Denetimler (kalıcı son değerlerden başlar)
        def fmtBox = new javafx.scene.control.ChoiceBox(); fmtBox.getItems().addAll(FORMATS)
        fmtBox.setValue(FORMATS.contains(prefs.get('format', FMT_OME)) ? prefs.get('format', FMT_OME) : FMT_OME)
        def compBox = new javafx.scene.control.ChoiceBox()
        def comps = compressionChoices(server); compBox.getItems().addAll(comps)
        compBox.setValue(comps.contains(prefs.get('compression', 'LZW')) ? prefs.get('compression', 'LZW') : comps[0])
        def qual = new javafx.scene.control.Spinner(1, 100, prefs.getInt('quality', 85), 1); qual.setEditable(true); qual.setPrefWidth(90)
        def tile = new javafx.scene.control.TextField(Integer.toString(prefs.getInt('tile', 512))); tile.setPrefColumnCount(6)
        def resize = new javafx.scene.control.TextField(prefs.get('resizePct', '100')); resize.setPrefColumnCount(6)
        def umpp = new javafx.scene.control.TextField(prefs.get('umpp', '')); umpp.setPrefColumnCount(6)
        def desc = new javafx.scene.control.TextField(''); desc.setPrefColumnCount(28)
        double nat = 0.0d; try { nat = server.getPixelCalibration().getAveragedPixelSizeMicrons() } catch (Throwable t) {}
        if (!(nat > 0.0d)) { umpp.setDisable(true); umpp.setPromptText('kalibrasyon yok') }
        def outDir = outDirRef.get() ?: defaultOutDir(); outDirRef.set(outDir)
        def outLbl = new javafx.scene.control.Label(outDir.getAbsolutePath()); outLbl.setWrapText(true)

        fmtBoxRef.set(fmtBox); compBoxRef.set(compBox); qualRef.set(qual); tileRef.set(tile)
        resizeRef.set(resize); umppRef.set(umpp); descRef.set(desc)

        def estLbl = new javafx.scene.control.Label(); estLbl.setWrapText(true); estLbl.setStyle('-fx-opacity: 0.85;')
        def refresh = {
            String fmt = (String) fmtBox.getValue()
            boolean ome = (fmt == FMT_OME); boolean jpg = (fmt == FMT_JPG)
            compBox.setDisable(!ome)
            tile.setDisable(!ome)
            qual.setDisable(!(jpg || (ome && ((String) compBox.getValue()) == 'JPEG')))
            double ds = downsampleFor(server, parseDoubleOr(resize.getText(), 100.0d), umpp.getText())
            def a0 = anns[0]; def cb = clampedBounds(a0.getROI(), server)
            if (cb != null) {
                long ow = (long) (cb[2] / ds), oh = (long) (cb[3] / ds)
                long bytes = ow * oh * server.nChannels() * bytesPerChannelOf(server)
                estLbl.setText(String.format(java.util.Locale.US,
                    'İlk bölge çıktısı: %,d × %,d px · ~%s sıkıştırılmamış (sıkıştırma sonrası daha küçük) · downsample %.3f',
                    ow, oh, humanBytes((double) bytes), ds) + (anns.size() > 1 ? ('  ·  toplam ' + anns.size() + ' bölge') : ''))
            }
        }
        fmtBox.valueProperty().addListener({ o, a, b -> refresh() } as javafx.beans.value.ChangeListener)
        compBox.valueProperty().addListener({ o, a, b -> refresh() } as javafx.beans.value.ChangeListener)
        resize.textProperty().addListener({ o, a, b -> refresh() } as javafx.beans.value.ChangeListener)
        umpp.textProperty().addListener({ o, a, b -> refresh() } as javafx.beans.value.ChangeListener)
        refresh()

        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        int row = 0
        def browse = navButton('…', { def d = FileChoosers.promptForDirectory('Çıktı klasörü seç', outDirRef.get()); if (d != null) { outDirRef.set(d); outLbl.setText(d.getAbsolutePath()) } })
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Biçim:'), fmtBox)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Sıkıştırma (OME-TIFF):'), compBox)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('JPEG kalitesi (1-100):'), qual)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Parça (tile) boyutu:'), tile)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Yeniden boyutlandır (%):'), resize)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Hedef µm/px (ops.):'), umpp)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Açıklama (ops.):'), desc)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Çıktı klasörü:'), outLbl, browse)
        center.getChildren().add(grid)
        center.getChildren().add(estLbl)
        addGuidance('Seçili ' + anns.size() + ' alan anotasyonu → her biri ayrı dosya + .json künyesi. ' +
            'Gerçek .svs üretilmez; piramidal OME-TIFF açık karşılığıdır.')

        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('⟳ Yenile', { step.set(selectedAreaAnns().isEmpty() ? 'READY' : 'CONFIG'); render() }))
        actions.add(navButton('Çıkart ▶', { startExport() }, 'Seçili bölge(ler)i görüntü dosyasına çıkarır'))
    } else if (cur == 'RUNNING') {
        title.setText('Çıkarılıyor…')
        addGuidance('Bölgeler yazılıyor. Büyük/tam çözünürlüklü bölgelerde birkaç dakika sürebilir.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true) }, 'Sıradaki bölgeden önce durur; yazılmakta olan dosya tamamlanır'))
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅')
        addMonoArea(resultTextRef.get())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('↻ Yeniden', { step.set(selectedAreaAnns().isEmpty() ? 'READY' : 'CONFIG'); render() }))
    } else { // ERROR
        title.setText('Hata')
        addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Geri', { step.set(selectedAreaAnns().isEmpty() ? 'READY' : 'CONFIG'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    }

    // Alt çubuk: "Üstte tut" (sol) + disclaimer + eylem düğmeleri (sağ)
    def topChk = new javafx.scene.control.CheckBox('Üstte tut')
    topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n -> alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
    def spacer = new javafx.scene.layout.Region()
    javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8)
    bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk)
    bar.getChildren().add(spacer)
    bar.getChildren().addAll(actions)

    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlıdır; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(center)
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 720, 620))
}

// ── Açılış ───────────────────────────────────────────────────────────────────
step.set(selectedAreaAnns().isEmpty() ? 'READY' : 'CONFIG')
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Görüntü alanı çıkart (Extract Region)')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println '✓ Görüntü alanı çıkart (Extract Region) sihirbazı açıldı.'
