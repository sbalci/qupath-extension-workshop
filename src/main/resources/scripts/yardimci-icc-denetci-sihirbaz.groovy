/**
 * Yardımcı - ICC Renk Profili Denetçisi (tek pencere, salt okunur)
 * -------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Açık lamın DOSYASINDA gömülü bir ICC renk profili olup olmadığını okur
 *   ve raporlar — tek pencereden, görüntüyü/renkleri DEĞİŞTİRMEDEN:
 *     1. Lam dosyasını (TIFF/SVS) QuPath'in paketlediği Bio-Formats TIFF
 *        ayrıştırıcısıyla açar; yalnız ETİKET (IFD) verisini okur, pikselleri DEĞİL.
 *     2. ImageDescription (TIFF etiketi 270) → tarayıcı ipucunu (Aperio/GT450/AT2) verir.
 *     3. Gömülü ICC profilini iki konumda arar:
 *          • standart konum  — TIFF etiketi 34675 (0x8773),
 *          • Aperio konumu    — TIFF etiketi 0xFFFF (65535); GT450 sınıfı tarayıcılar
 *            profili buraya TAŞIR ki standart okuyucular çifte renk dönüşümü yapmasın
 *            (bu yüzden QuPath/Bio-Formats onu uygulamaz → renkler ImageScope'tan farklı).
 *     4. Profil bulunursa boyut, renk uzayı, bileşen sayısı, profil sınıfı ve
 *        (varsa) açıklama metnini raporlar.
 *
 *   Tümüyle SALT OKUNUR. Hücre/anotasyon/hiyerarşi/boya vektörü DEĞİŞTİRMEZ;
 *   profili UYGULAMAZ (QuPath 0.6/0.7 analiz hattında ICC uygulamayı kararlı
 *   biçimde desteklemez — bkz. qupath/qupath#982). Yalnız DENETLER.
 *
 * NEDEN ÖNEMLİ:
 *   • Aperio SVS dosyaları gömülü bir ICC profili taşır; ImageScope bunu varsayılan
 *     olarak UYGULAR, QuPath UYGULAMAZ → aynı lam iki yazılımda farklı renkte görünür.
 *   • H-DAB / Ki-67 / H-score gibi OPTİK YOĞUNLUK (OD) tabanlı ölçümlerde doğru
 *     yaklaşım renk eşleme değil, boya vektörlerini HER LAM için yeniden tahmin
 *     etmektir (Yardımcılar → Boya ve renk → Boya vektörleri sihirbazı). Renk yönetimi çoğunlukla
 *     GÖRÜNÜMÜ etkiler, OD ölçümünü değil.
 *
 * KULLANIM:
 *   1. Bir lam açın (yerel TIFF/SVS dosyası).
 *   2. [Extensions → Atölye → Yardımcılar → Boya ve renk → ICC renk profili denetçisi]
 *   3. "Bu lamı denetle" → raporu inceleyin / panoya kopyalayın.
 *
 * AYRINTI:
 *   Ekler → Renk Yönetimi (ICC). Arka plan: petebankhead/ICC-Profiles,
 *   Janowczyk (andrewjanowczyk.com), qupath/qupath#982.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// TIFF etiket numaraları
final int TAG_IMAGE_DESCRIPTION = 270
final int TAG_ICC_STANDARD      = 34675      // 0x8773 — standart ICC_PROFILE
final int TAG_ICC_APERIO        = 65535      // 0xFFFF — Aperio/GT450 taşınmış konum

// ── Biçim yardımcıları (Locale.US — Türkçe yerelde virgül-ondalık olmasın) ──
def fmtBytes = { long n ->
    if (n < 1024L) return String.format(java.util.Locale.US, '%d B', n)
    if (n < 1024L * 1024L) return String.format(java.util.Locale.US, '%.1f KB', n / 1024.0d)
    return String.format(java.util.Locale.US, '%.1f MB', n / (1024.0d * 1024.0d))
}

// ── Açık lamın dosya yolunu çöz (file: URI → mutlak yol) ──────────────────
def serverFilePath = { server ->
    try {
        def uris = server.getURIs()
        if (uris == null || uris.isEmpty()) return null
        def uriString = uris.iterator().next().toString()
        if (uriString.startsWith('file:')) {
            try { return new File(new URI(uriString)).getAbsolutePath() } catch (Throwable ignore) {}
        }
        return uriString
    } catch (Throwable t) { return null }
}

def fileExtension = { String path ->
    if (path == null) return 'bilinmiyor'
    int lastDot = path.lastIndexOf('.')
    return (lastDot > 0) ? path.substring(lastDot + 1).toLowerCase(java.util.Locale.ROOT) : 'bilinmiyor'
}

// ── ICC 'desc' / 'mluc' açıklama metnini en iyi çabayla çıkar ────────────────
def profileDescription = { java.awt.color.ICC_Profile prof ->
    try {
        byte[] data = prof.getData(java.awt.color.ICC_Profile.icSigProfileDescriptionTag)
        if (data == null || data.length < 12) return null
        def sig = new String(data, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)
        if (sig == 'desc') {
            // textDescriptionType (ICC v2): 8 bayt başlık, 4 bayt ASCII sayacı, sonra ASCII metin
            def buf = java.nio.ByteBuffer.wrap(data)
            int count = buf.getInt(8)
            if (count <= 1 || 12 + count > data.length) return null
            def s = new String(data, 12, count - 1, java.nio.charset.StandardCharsets.US_ASCII)
            return s.trim().isEmpty() ? null : s.trim()
        } else if (sig == 'mluc') {
            // multiLocalizedUnicodeType (ICC v4): ilk kaydı UTF-16BE oku
            def buf = java.nio.ByteBuffer.wrap(data)
            int nRecords = buf.getInt(8)
            if (nRecords < 1) return null
            int recLen = buf.getInt(20)
            int recOff = buf.getInt(24)
            if (recOff < 0 || recLen <= 0 || recOff + recLen > data.length) return null
            def s = new String(data, recOff, recLen, java.nio.charset.StandardCharsets.UTF_16BE)
            return s.trim().isEmpty() ? null : s.trim()
        }
        return null
    } catch (Throwable t) { return null }
}

def colorSpaceName = { int type ->
    switch (type) {
        case java.awt.color.ColorSpace.TYPE_RGB:  return 'RGB'
        case java.awt.color.ColorSpace.TYPE_GRAY: return workshopText('script.afb2a22c17ee', 'Gri tonlama')
        case java.awt.color.ColorSpace.TYPE_CMYK: return 'CMYK'
        case java.awt.color.ColorSpace.TYPE_Lab:  return 'L*a*b*'
        case java.awt.color.ColorSpace.TYPE_XYZ:  return 'XYZ'
        default: return workshopText('script.3701d2d37718', 'tip #') + type
    }
}
def profileClassName = { int cls ->
    switch (cls) {
        case java.awt.color.ICC_Profile.CLASS_INPUT:               return workshopText('script.11d50e96ccd5', 'Girdi (scnr)')
        case java.awt.color.ICC_Profile.CLASS_DISPLAY:             return workshopText('script.4214d2ef2c71', 'Görüntüleme (mntr)')
        case java.awt.color.ICC_Profile.CLASS_OUTPUT:              return workshopText('script.0848d2ac0ce7', 'Çıktı (prtr)')
        case java.awt.color.ICC_Profile.CLASS_DEVICELINK:          return workshopText('script.d77d9946243e', 'Aygıt bağlantı (link)')
        case java.awt.color.ICC_Profile.CLASS_COLORSPACECONVERSION: return workshopText('script.21679d6309bb', 'Renk uzayı dönüşüm (spac)')
        case java.awt.color.ICC_Profile.CLASS_ABSTRACT:            return workshopText('script.493b6dc3d41c', 'Soyut (abst)')
        case java.awt.color.ICC_Profile.CLASS_NAMEDCOLOR:          return workshopText('script.eb3103238853', 'Adlı renk (nmcl)')
        default: return workshopText('script.da9b22129061', 'sınıf #') + cls
    }
}

// ── Bir IFD etiketinden ham bayt dizisini güvenle çöz (byte[] veya entry) ────
def resolveTagBytes = { parser, ifd, int tag ->
    try {
        def v = ifd.get(Integer.valueOf(tag))
        if (v == null) return null
        if (v instanceof byte[]) return (byte[]) v
        // Bazı Bio-Formats sürümleri büyük değerleri TiffIFDEntry olarak saklar → çöz
        if (v.getClass().getSimpleName() == 'TiffIFDEntry') {
            try { def r = parser.getIFDValue(v); return (r instanceof byte[]) ? (byte[]) r : null }
            catch (Throwable ignore) { return null }
        }
        return null
    } catch (Throwable t) { return null }
}

// ── Çekirdek inceleme: dosyayı Bio-Formats TiffParser ile okur (salt okunur) ───
// Dönen LinkedHashMap: ok, error, imageDescription, scannerHint, ext,
//   has34675/bytes34675, hasFFFF/bytesFFFF
def gatherIcc = { String path ->
    def out = new java.util.LinkedHashMap()
    out.ext = fileExtension(path)
    if (path == null) { out.ok = false; out.error = workshopText('script.34a4152e2dcc', 'Dosya yolu çözülemedi (uzak/yerel olmayan kaynak olabilir).'); return out }
    def f = new File(path)
    if (!f.exists()) { out.ok = false; out.error = workshopText('script.927d7419ec60', 'Dosya bulunamadı: ') + path; return out }
    out.fileSize = f.length()

    def ris = null
    def parser = null
    try {
        ris = new loci.common.RandomAccessInputStream(path)
        parser = new loci.formats.tiff.TiffParser(ris)
        def ifd = parser.getFirstIFD()
        if (ifd == null) { out.ok = false; out.error = workshopText('script.b510cfb683f0', 'Geçerli bir TIFF/SVS ilk IFD bulunamadı (bu biçim ICC denetimini desteklemiyor olabilir).'); return out }

        // ImageDescription (tarayıcı ipucu)
        try {
            def d = ifd.get(Integer.valueOf(TAG_IMAGE_DESCRIPTION))
            out.imageDescription = (d == null ? null : d.toString())
        } catch (Throwable ignore) { out.imageDescription = null }

        // İki olası ICC konumu
        out.has34675 = ifd.containsKey(Integer.valueOf(TAG_ICC_STANDARD))
        out.hasFFFF  = ifd.containsKey(Integer.valueOf(TAG_ICC_APERIO))
        out.bytes34675 = out.has34675 ? resolveTagBytes(parser, ifd, TAG_ICC_STANDARD) : null
        out.bytesFFFF  = out.hasFFFF  ? resolveTagBytes(parser, ifd, TAG_ICC_APERIO)  : null

        out.ok = true
        return out
    } catch (Throwable t) {
        out.ok = false
        out.error = (t.getMessage() ?: t.getClass().getSimpleName())
        // Bio-Formats sınıfları yüklenemediyse açık bir not
        if (t instanceof NoClassDefFoundError || t instanceof ClassNotFoundException) {
            out.error = workshopText('script.4a7432951d1e', 'QuPath Bio-Formats okuyucusu bulunamadı; ICC denetimi için Bio-Formats eklentisi gerekir.')
        }
        return out
    } finally {
        try { if (parser != null) parser.close() } catch (Throwable t2) { try { if (ris != null) ris.close() } catch (Throwable t3) {} }
        try { if (ris != null) ris.close() } catch (Throwable t4) {}
    }
}

// ── Tarayıcı ipucu (ImageDescription içinde anahtar arama) ───────────────────
def scannerHint = { String desc ->
    if (desc == null) return null
    def dl = desc.toLowerCase(java.util.Locale.ROOT)
    def hits = []
    if (dl.contains('gt450') || dl.contains('gt 450')) hits << 'Aperio GT450'
    if (dl.contains('at2'))       hits << 'Aperio AT2'
    if (dl.contains('scanscope')) hits << 'ScanScope'
    if (hits.isEmpty()) {
        if (dl.contains('aperio')) hits << 'Aperio'
        else if (dl.contains('leica')) hits << 'Leica'
        else if (dl.contains('hamamatsu')) hits << 'Hamamatsu'
    }
    return hits.isEmpty() ? null : hits.unique().join(' / ')
}

// ── Profil bayt dizisini okunabilir satırlara dök ────────────────────────────
def appendProfileLines = { StringBuilder sb, Closure line, byte[] bytes ->
    line(workshopText('script.180ca5639c0e', '  Profil boyutu'), fmtBytes((long) bytes.length))
    try {
        def prof = java.awt.color.ICC_Profile.getInstance(bytes)
        line(workshopText('script.e376f2384bbd', '  Renk uzayı'), colorSpaceName(prof.getColorSpaceType()))
        line(workshopText('script.ca8f04d726db', '  Bileşen sayısı'), '' + prof.getNumComponents())
        line(workshopText('script.153550b034b2', '  Profil sınıfı'), profileClassName(prof.getProfileClass()))
        def desc = profileDescription(prof)
        if (desc != null) line(workshopText('script.c97e74da3122', '  Açıklama'), '"' + desc + '"')
    } catch (Throwable t) {
        line(workshopText('script.90b627a17e93', '  Not'), workshopText('script.ba805d3f2bd2', 'Profil bayt verisi okundu ama çözümlenemedi (') + (t.getMessage() ?: t.getClass().getSimpleName()) + ')')
    }
}

// ── Rapor metnini kur ────────────────────────────────────────────────────────
def buildReport = { String imageName, String path, Map g ->
    def sb = new StringBuilder()
    def line = { Object label, Object val -> sb << String.format(java.util.Locale.US, '  %-26s : %s%n', label.toString(), (val == null ? '—' : val.toString())) }

    sb << workshopText('script.8b0e3f9e80f9', 'ICC RENK PROFİLİ DENETİMİ\n')
    sb << '═════════════════════════\n'
    line(workshopText('script.461af90e2417', 'Görüntü'), imageName)
    line(workshopText('script.9cecd7340f24', 'Dosya'), path)
    line(workshopText('script.90bea824e588', 'Biçim / uzantı'), g.ext)
    if (g.fileSize != null) line(workshopText('script.edd1366d6cd2', 'Dosya boyutu'), fmtBytes((long) g.fileSize))

    if (!g.ok) {
        sb << workshopText('script.274e4a8ccb43', '\nDENETİM YAPILAMADI\n──────────────────\n')
        sb << '  ' + (g.error ?: workshopText('script.da001998a5bd', 'Bilinmeyen hata')) + '\n'
        sb << workshopText('script.d4ba469ebf1a', '\n  İpucu: bu araç yerel TIFF tabanlı lamlarda (.svs/.tif/.tiff) çalışır.\n')
        sb << workshopText('script.0a96d0aa4dbc', '\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')
        return sb.toString()
    }

    def hint = scannerHint((String) g.imageDescription)
    line(workshopText('script.0aedc2a8fa6f', 'Tarayıcı (ipucu)'), hint ?: workshopText('script.48c7827ca069', '— (ImageDescription\'da tanınmadı)'))
    if (g.imageDescription != null) {
        def d = ((String) g.imageDescription).replaceAll('[\\r\\n]+', ' ').trim()
        if (d.length() > 160) d = d.substring(0, 160) + '…'
        line('ImageDescription', d)
    }

    sb << workshopText('script.ea4168fbf7cc', '\nGÖMÜLÜ ICC PROFİLİ\n──────────────────\n')
    boolean any = false
    if (g.has34675) {
        any = true
        line(workshopText('script.6815f095be2e', 'Standart konum (34675)'), g.bytes34675 != null ? workshopText('script.b0398cdfc1c0', 'VAR') : workshopText('script.9a505ca8fc08', 'VAR (bayt okunamadı)'))
        if (g.bytes34675 != null) appendProfileLines(sb, line, (byte[]) g.bytes34675)
    } else {
        line(workshopText('script.6815f095be2e', 'Standart konum (34675)'), workshopText('script.aec8e694277c', 'YOK'))
    }
    if (g.hasFFFF) {
        any = true
        line(workshopText('script.096d44beff1c', 'Aperio konumu (0xFFFF)'), g.bytesFFFF != null ? workshopText('script.b0398cdfc1c0', 'VAR') : workshopText('script.9a505ca8fc08', 'VAR (bayt okunamadı)'))
        if (g.bytesFFFF != null) appendProfileLines(sb, line, (byte[]) g.bytesFFFF)
    } else {
        line(workshopText('script.096d44beff1c', 'Aperio konumu (0xFFFF)'), workshopText('script.aec8e694277c', 'YOK'))
    }

    sb << workshopText('script.0c251619da77', '\nNE ANLAMA GELİYOR\n─────────────────\n')
    if (any) {
        sb << workshopText('script.f92f2220f109', '  • Profilin dosyada bulunması, kullanılan okuyucunun onu uyguladığını göstermez.\n')
        sb << workshopText('script.1e009a0b4987', '  • QuPath 0.7+ OpenSlide ile ICC profilini isteğe bağlı uygulayabilir;\n')
        sb << workshopText('script.e028a4aa4bf5', '    okuyucuyu ve ICC tercihini ayrıca kontrol edin. Bu denetçi etkin dönüşümü sınamaz.\n')
        if (g.hasFFFF) {
            sb << workshopText('script.66e6c87ed4b0', '  • Profil 0xFFFF etiketinde: GT450 sınıfı tarayıcılar profili buraya\n')
            sb << workshopText('script.4683c29a1119', '    TAŞIR ki standart okuyucular çifte renk dönüşümü yapmasın — bu yüzden\n')
            sb << workshopText('script.b97780f0f043', '    QuPath onu görmez/uygulamaz.\n')
        }
    } else {
        sb << workshopText('script.439901808056', '  • Bu dosyada gömülü ICC profili bulunmadı; QuPath ile ImageScope\n')
        sb << workshopText('script.4a2a1732c959', '    arasında renk yönetiminden kaynaklı belirgin fark beklenmez.\n')
    }

    sb << workshopText('script.ee1c9d68b0c3', '\nÖLÇÜME ETKİSİ\n─────────────\n')
    sb << workshopText('script.40ee22c66642', '  • OD tabanlı ölçümde (Ki-67, H-score, HER2) doğru yaklaşım: boya\n')
    sb << workshopText('script.06a3e8aba9ac', '    vektörlerini HER LAM için yeniden tahmin etmek (Yardımcılar → Boya ve renk →\n')
    sb << workshopText('script.73c6145f179e', '    Boya vektörleri sihirbazı).\n')
    sb << workshopText('script.8233c441353e', '  • QuPath 0.7+ ICC profilini uygulayabilir (yalnız OpenSlide ile açılan\n')
    sb << workshopText('script.acc893d68c17', '    lamlarda, isteğe bağlı). Açıkken yalnız görünüm değil ANALİZ pikselleri\n')
    sb << workshopText('script.d973308865c1', '    de değişir: OD ölçümleri profil açık ve kapalı alınmış lamlar arasında\n')
    sb << workshopText('script.e435e1f442b8', '    karşılaştırılamaz. Bir kohortu tek ayarla ölçün.\n')
    sb << workshopText('script.6243b8d533f8', '  • Sabit-RGB piksel sınıflandırıcı / derin öğrenme modelleri renk\n')
    sb << workshopText('script.2b728d58558e', '    kaymasına duyarlı olabilir → renk-sağlam girdi ya da yeniden eğitim.\n')
    sb << workshopText('script.2aa8bc6a3005', '\n  Bu araç yalnızca DENETLER; görüntüyü ve renkleri DEĞİŞTİRMEZ.\n')
    sb << workshopText('script.a49394e001c3', '  Ayrıntı: Ekler → Renk Yönetimi (ICC).\n')
    sb << workshopText('script.0a96d0aa4dbc', '\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')
    return sb.toString()
}

// ── Headless: açık lamı oku, raporu yazdır (UI yok, değişiklik yok) ─────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println 'Önce bir lam açın (headless modda yalnız açık lam denetlenir).'; return }
    def server = imageData.getServer()
    def path = serverFilePath(server)
    def name = server.getMetadata().getName() ?: 'slide'
    println buildReport(name, path, gatherIcc(path))
    return
}

// ── Durum makinesi: READY | SCANNING | RESULT | ERROR ───────────────────────
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def busyRef       = new java.util.concurrent.atomic.AtomicReference(null)
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
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

// ── Açık lamı arka planda denetle ──────────────────────────────────────────
def startInspect = {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set(workshopText('script.ff78989d4a0e', 'Açık lam yok. Önce bir TIFF/SVS lamı açın.')); step.set('ERROR'); render(); return }
    def server = imageData.getServer()
    def path = serverFilePath(server)
    def name = server.getMetadata().getName() ?: 'slide'
    step.set('SCANNING'); render()
    def worker = new Thread({
        String report
        try { report = buildReport(name, path, gatherIcc(path)) }
        catch (Throwable t) { javafx.application.Platform.runLater { errorTextRef.set(workshopText('script.9044c16f7be3', 'Denetim sırasında hata:\n') + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }; return }
        javafx.application.Platform.runLater { resultTextRef.set(report); step.set('RESULT'); render() }
    }, 'AtolyeICC-Inspect')
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

    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); lbl.setMaxWidth(Double.MAX_VALUE); center.getChildren().add(lbl) }
    def addMonoArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: '')
        ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(ta)
    }

    if (cur == 'READY') {
        def imageData = QP.getCurrentImageData()
        title.setText(workshopText('script.447623c0be7e', 'ICC renk profili denetçisi'))
        addGuidance(workshopText('script.e9ecd278fd33', 'Açık lamın DOSYASINDA gömülü ICC renk profili arar; görüntüyü, renkleri veya analizinizi değiştirmez.\n\nQuPath 0.7+ OpenSlide ile ICC profilini isteğe bağlı uygulayabilir; 0.6 bu özelliği içermez. Profilin dosyada bulunması, görüntüde etkin olduğunu göstermez. Kullanılan okuyucuyu ve ICC tercihini ayrıca kontrol edin. Bu araç etkin renk dönüşümünü sınamaz.'))
        if (imageData == null) addGuidance(workshopText('script.ba1e453bc8d3', '⚠ Açık lam yok. Önce yerel bir TIFF/SVS lamı açın.'))
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
        def go = navButton(workshopText('script.f6a7fa26df29', 'Bu lamı denetle ▶'), { startInspect() }, workshopText('script.e58218743302', 'Açık lamın dosyasında gömülü ICC profilini arar'))
        go.setDisable(imageData == null)
        actions.add(go)
    } else if (cur == 'SCANNING') {
        title.setText(workshopText('script.076c5e2bb229', 'Lam dosyası okunuyor…'))
        addGuidance(workshopText('script.1e1982d39c71', 'Dosyanın etiket (IFD) verisi okunuyor; pikseller okunmaz, bu yüzden büyük lamlarda da hızlıdır.'))
        center.getChildren().add(busyBar())
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
    } else if (cur == 'RESULT') {
        title.setText(workshopText('script.0ac1ec6f0095', 'Denetim tamamlandı ✅'))
        addMonoArea(resultTextRef.get())
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
        actions.add(navButton(workshopText('script.a8bcca42d9ce', 'Kopyala'), { copyToClipboard(resultTextRef.get()) }, workshopText('script.8d19ae1b08d1', 'Raporu panoya kopyala')))
        actions.add(navButton(workshopText('script.47e225e64d35', '↻ Yeniden denetle'), { startInspect() }))
    } else { // ERROR
        title.setText(workshopText('script.307b9dc7912d', 'Hata'))
        addMonoArea(errorTextRef.get())
        actions.add(navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('READY'); render() }))
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
    }

    // Alt çubuk: "Üstte tut" (sol) + disclaimer + eylem düğmeleri (sağ)
    def topChk = new javafx.scene.control.CheckBox(workshopText('script.70ca64c0c43a', 'Üstte tut'))
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

    def disclaimer = new javafx.scene.control.Label(workshopText('script.04e70fc651a3', 'Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.'))
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(center)
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 820, 600))
}

// ── Açılış ───────────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle(workshopText('script.447623c0be7e', 'ICC renk profili denetçisi'))
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.7fd583bc0c88', 'Sihirbaz açılamadı'), workshopText('script.9aac4912cb86', 'Bu araç başlatılamadı.\n\nYapabilecekleriniz:\n  1. Araç açık bir lam istiyorsa önce lamı açın.\n  2. QuPath\'i tamamen kapatıp yeniden açın ve tekrar deneyin.\n  3. Sorun sürerse View → Show log penceresinin ekran görüntüsünü atölye düzenleyicisine gönderin.\n\nTeknik ayrıntı: ') + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: workshopText('script.e136f4a3bb45', '(ileti yok)')))
    }
}
println '✓ ICC renk profili denetçisi açıldı.'

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
