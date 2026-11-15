/**
 * Yardımcı - Kohort Üst Veri Sihirbazı (tek pencere: bu lam + tüm proje)
 * ------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   "Analiz etmeden önce ne elinde olduğunu bil." Açık lamın VEYA tüm
 *   projenin teknik/tarayıcı üst verisini (metadata) okur — hepsi tek pencereden:
 *     1. BU LAM — açık lamın boyut, piksel boyutu (µm/px), kestirilen
 *        büyütme, piramit, dosya bilgisi vb. üst verisini anında raporlar.
 *     2. TÜM PROJE — projedeki her görüntüyü arka planda gezer; çekirdek
 *        alanları (+ okunabiliyorsa gömülü tarayıcı anahtarları) bir CSV'ye
 *        ve özet rapora yazar (<proje>/cohort_metadata/). İsteğe bağlı olarak
 *        anahtar alanları QuPath Proje sekmesine SIRALANABİLİR sütun olarak da
 *        yazabilir (entry.getMetadata()) — kohortu QuPath'ten çıkmadan eleyin.
 *
 *   Tümüyle SALT OKUNUR ölçüm üretir: hücre tespiti, sınıflandırma veya hiyerarşi
 *   DEĞİŞTİRMEZ. Yalnız "Proje sütunlarına da yaz" seçeneği işaretliyse proje
 *   girdi üst verisini günceller (görüntü pikselleri/anotasyonlar dokunulmaz).
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Çekirdek alanlar (boyut, piksel boyutu, alan, piramit, dosya) QuPath'in
 *     kararlı genel API'sinden gelir; güvenilirdir.
 *   • "Kestirilen büyütme" bir SEZGİSEL'dir (0.25 µm/px ≈ 40×). Lam gömülü
 *     "objektif büyütme" varsa (getMagnification) ayrıca raporlanır.
 *   • Gömülü tarayıcı anahtarları (tarayıcı modeli ve yazılım sürümü, tarama
 *     tarihi, MPP, AppMag, sıkıştırma ve kalite, Rack/Slide vb.) TIFF tabanlı
 *     dosyalarda (SVS, TIFF, BigTIFF) doğrudan dosya başlığından SALT OKUNUR
 *     okunur: Bio-Formats ve OpenSlide sunucuları bu anahtarları açığa
 *     çıkarmaz. Aperio dışı biçimlerde bu alanlar boş kalır — hata değil.
 *   • Aynı okuma, kimlik / yarı kimlik taşıyabilen gömülü alanların envanterini
 *     de verir (embedded_identity_filled / _blank): Filename, Date, Time, User,
 *     ScanScope ID, ImageID, Barcode, Rack, Slide … Liste WSI anonimleştirme
 *     sihirbazının temizlediği anahtarlarla aynıdır. CSV bu alanların
 *     değerlerini içerir; paylaşmadan önce gözden geçirin.
 *   • Hiçbir klinik yorum, tanı veya skor üretmez.
 *
 * KULLANIM:
 *   1. Bir QuPath projesi açın (tek lam raporu için bir de lam açın).
 *   2. [Extensions → Atölye → Yardımcılar → Klinik ve kohort → Kohort üst veri sihirbazı]
 *   3. "Bu lam" panosunu inceleyin VEYA "Tüm projeyi tara → CSV".
 *   4. CSV'yi Excel/R/Python'da açıp kohort dahil/dışlama ölçütlerinizi tanımlayın.
 *
 * KAYNAK:
 *   sbalci/metadata-qupath (MIT) — https://github.com/sbalci/metadata-qupath
 *   Çekirdek alan adları o araçla uyumlu tutuldu (downstream python/R/SQL çalışır).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
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

// ── Gömülü özellik araştırması (salt okunur, en iyi çaba, tamamen korumalı) ───
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

// ── TIFF / Aperio başlığı (salt okunur, saf Java; klasik TIFF + BigTIFF) ───────
// Bio-Formats ve OpenSlide sunucuları Aperio ImageDescription anahtarlarını Map
// olarak açığa çıkarmaz (yukarıdaki yansıma boş döner). Tarayıcı bilgisi bu yüzden
// doğrudan dosyadan okunur: her IFD'nin ASCII etiketleri (ImageDescription, Make,
// Model, Software, DateTime …). Dosya 'r' kipinde açılır; hiçbir bayt yazılmaz.
def TIFF_TYPE_SIZE  = [1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 13: 4, 16: 8, 17: 8, 18: 8]
def TIFF_HEADER_EXT = ['svs', 'tif', 'tiff', 'btf', 'scn', 'bif', 'qptiff'] as Set
def TIFF_INFO_TAGS  = [271: 'Make', 272: 'Model', 305: 'Software']
// Kimlik / yarı kimlik taşıyabilen alanlar — WSI anonimleştirme sihirbazının temizlediği listeyle aynı.
def TIFF_ID_TAGS    = [269: 'DocumentName', 285: 'PageName', 306: 'DateTime', 315: 'Artist', 316: 'HostComputer', 42016: 'ImageUniqueID']
def ID_KEYS_EXACT   = ['filename', 'title', 'date', 'time', 'timezone', 'datetime', 'user', 'username', 'operator',
                       'scanscopeid', 'scannerid', 'imageid', 'barcode', 'label', 'slideid', 'slidename',
                       'name', 'dsrid', 'rack', 'slide'] as Set
def ID_KEYS_PREFIX  = ['patient', 'case', 'accession', 'specimen', 'barcode']
def normKey  = { String k -> (k ?: '').trim().toLowerCase(java.util.Locale.ROOT).replaceAll('[\\s_\\-]', '') }
def isIdKey  = { String k -> def n = normKey(k); ID_KEYS_EXACT.contains(n) || ID_KEYS_PREFIX.any { n.startsWith(it) } }
// Boş ya da anonimleştirme aracının X ile doldurduğu değer "boş" sayılır.
def isBlankValue = { String v -> v == null || v.trim().isEmpty() || (v.trim() ==~ /[Xx ]+/) }

def tiffBuf = { java.io.RandomAccessFile raf, long pos, int n, boolean littleEndian ->
    byte[] b = new byte[n]
    raf.seek(pos); raf.readFully(b)
    def bb = java.nio.ByteBuffer.wrap(b)
    bb.order(littleEndian ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN)
    return bb
}
def tiffU16 = { java.nio.ByteBuffer bb -> ((int) bb.getShort()) & 0xFFFF }
def tiffU32 = { java.nio.ByteBuffer bb -> ((long) bb.getInt()) & 0xFFFFFFFFL }

def readTiffHeader = { File f ->    java.io.RandomAccessFile raf = null
    try {
        raf = new java.io.RandomAccessFile(f, 'r')
        long len = raf.length()
        if (len < 16L) return null
        def hdr = tiffBuf(raf, 0L, 16, true)

        byte b0 = hdr.get(0), b1 = hdr.get(1)
        boolean isII = (b0 == (byte) 0x49 && b1 == (byte) 0x49)
        boolean isMM = (b0 == (byte) 0x4D && b1 == (byte) 0x4D)
        if (!isII && !isMM) return null
        boolean le = isII

        hdr = tiffBuf(raf, 0L, 16, le)
        hdr.position(2)
        int magic = tiffU16(hdr)
        if (magic != 42 && magic != 43) return null
        boolean big = (magic == 43)
        long first = 0L
        if (big) {
            int osz = tiffU16(hdr); hdr.getShort()
            if (osz != 8) return null
            first = hdr.getLong()
        } else {
            first = tiffU32(hdr)
        }
        int cntSize = big ? 8 : 2, entSize = big ? 20 : 12, ptrSize = big ? 8 : 4
        def ifds = []
        def seen = new HashSet()
        long off = first
        while (off != 0L) {
            if (off < 8L || off >= len || !seen.add(off) || ifds.size() > 4096) break
            def cb = tiffBuf(raf, off, cntSize, le)
            long n = big ? cb.getLong() : tiffU16(cb)
            if (n <= 0L || n > 4096L) break
            long entStart = off + cntSize
            def eb = tiffBuf(raf, entStart, (int) (n * entSize), le)
            def ascii = new LinkedHashMap()
            boolean icc = false
            byte[] iccBytes = null
            for (int k = 0; k < n; k++) {
                int base = k * entSize
                eb.position(base)
                int tag = tiffU16(eb)
                int type = tiffU16(eb)
                long count = big ? eb.getLong() : tiffU32(eb)
                if (tag == 34675) {
                    icc = true
                    // ICC profili baytları yalnız ilk IFD'den (kimlik: md5 + başlık); 32 MB üstü okunmaz
                    if (ifds.isEmpty() && count > 128L && count <= 33554432L) {
                        long iccPos = 0L
                        if (count <= ptrSize) iccPos = entStart + base + (big ? 12 : 8)
                        else { eb.position(base + (big ? 12 : 8)); iccPos = big ? eb.getLong() : tiffU32(eb) }
                        if (iccPos > 0L && iccPos + count <= len) {
                            iccBytes = new byte[(int) count]
                            raf.seek(iccPos); raf.readFully(iccBytes)
                        }
                    }
                }
                boolean wanted = (tag == 270) || TIFF_INFO_TAGS.containsKey(tag) || TIFF_ID_TAGS.containsKey(tag)
                if (!wanted || type != 2 || count <= 0L || count > 4000000L) continue
                long size = count * ((TIFF_TYPE_SIZE.get(type) ?: 1) as int)
                long dataPos = 0L
                if (size <= ptrSize) dataPos = entStart + base + (big ? 12 : 8)
                else { eb.position(base + (big ? 12 : 8)); dataPos = big ? eb.getLong() : tiffU32(eb) }
                if (dataPos < 0L || dataPos + count > len) continue
                byte[] vb = new byte[(int) count]
                raf.seek(dataPos); raf.readFully(vb)
                ascii.put(tag, new String(vb, java.nio.charset.StandardCharsets.ISO_8859_1).replace('\u0000', '').trim())
            }
            ifds << [ascii: ascii, icc: icc, iccBytes: iccBytes]
            def pb = tiffBuf(raf, entStart + n * entSize, ptrSize, le)
            off = big ? pb.getLong() : tiffU32(pb)
        }
        return ifds.isEmpty() ? null : [big: big, ifds: ifds]
    } catch (Throwable t) {
        return null
    } finally {
        try { if (raf != null) raf.close() } catch (Throwable ignore) {}
    }
}

// Aperio ImageDescription: "Aperio … vX \n GxY [..] (256x256) JPEG/YCC Q=91|Anahtar = Değer|…"
// Etiket ve makro IFD'lerinde ikinci satır "label GxY" / "macro GxY" ile başlar.
def parseAperio = { String desc ->
    if (desc == null || !desc.startsWith('Aperio')) return null
    def parts = desc.split('\\|')
    def head = parts[0]
    def lines = head.split('\\r?\\n')
    def res = [scanner: lines[0].trim(), keys: new LinkedHashMap(), assoc: null]
    if (lines.length > 1) {
        def second = lines[1].trim().toLowerCase(java.util.Locale.ROOT)
        if (second.startsWith('label')) res.assoc = 'label'
        else if (second.startsWith('macro')) res.assoc = 'macro'
    }
    def mq = (head =~ /Q=(\d+)/);                    if (mq.find()) res.quality = mq.group(1)
    def mt = (head =~ /\((\d+x\d+)\)/);              if (mt.find()) res.tile = mt.group(1)
    def mc = (head =~ /\)\s+([A-Za-z0-9\/]+)\s+Q=/); if (mc.find()) res.compression = mc.group(1)
    // Yazılım dizgesi: aile + sürüm ("Aperio Leica Biosystems GT450 DX" + "1.2.5"); sürüm, bakım/güncelleme izidir
    def mv = (res.scanner =~ /\bv?(\d+(?:\.\d+)+)\s*$/)
    if (mv.find()) {
        res.softwareVersion = mv.group(1)
        res.softwareFamily  = res.scanner.substring(0, mv.start()).trim()
    } else {
        res.softwareFamily = res.scanner
    }
    if (res.compression != null && res.compression.contains('/')) {
        res.codec = res.compression.substring(0, res.compression.indexOf('/'))
        res.jpegColorspace = res.compression.substring(res.compression.indexOf('/') + 1)
    }
    for (int i = 1; i < parts.length; i++) {
        int eq = parts[i].indexOf('=')
        if (eq > 0) res.keys.put(parts[i].substring(0, eq).trim(), parts[i].substring(eq + 1).trim())
    }
    return res
}

// Başlıktan tarayıcı alanlarını + gömülü kimlik alanı envanterini satıra işle.
def APERIO_COLUMNS = ['ScannerType': 'scanner_type', 'ScanScope ID': 'scanscope_id', 'Date': 'scan_date', 'Time': 'scan_time',
                      'Time Zone': 'time_zone', 'AppMag': 'apparent_magnification', 'MPP': 'mpp', 'Rack': 'rack',
                      'Slide': 'slide_number', 'SessionMode': 'session_mode', 'Scan Warning': 'scan_warning',
                      'Exposure Time': 'exposure_time', 'Exposure Scale': 'exposure_scale', 'Gamma': 'gamma',
                      'Filtered': 'filtered', 'StripeWidth': 'stripe_width', 'Filename': 'embedded_filename',
                      'ImageID': 'image_id', 'User': 'scan_user', 'Title': 'embedded_title', 'Barcode': 'barcode',
                      'CalibrationAverageRed': 'calibration_average_red', 'CalibrationAverageGreen': 'calibration_average_green',
                      'CalibrationAverageBlue': 'calibration_average_blue', 'Focus Offset': 'focus_offset',
                      'Extended Focus': 'extended_focus', 'Parmset': 'parmset', 'Left': 'stage_left', 'Top': 'stage_top']

// Gömülü ICC profilinin kimliği (ICC.1:2010 §7.2 başlık + 'desc' etiketi). İki profil aynı boyutta
// olup yine de farklı olabilir; md5 + profilin kendi oluşturma tarihi ayırt eder. Yalnız okur.
def iccSig = { byte[] blob, int pos -> new String(blob, pos, 4, java.nio.charset.StandardCharsets.ISO_8859_1).replace('\u0000', ' ').trim() }
def parseIcc = { byte[] blob ->
    def out = new LinkedHashMap()
    try {
        def md = java.security.MessageDigest.getInstance('MD5').digest(blob)
        def hex = new StringBuilder()
        for (byte b : md) hex.append(String.format(java.util.Locale.US, '%02x', b & 0xFF))
        out.icc_md5   = hex.toString()
        out.icc_bytes = blob.length
        if (blob.length < 132) return out
        def bb = java.nio.ByteBuffer.wrap(blob)   // ICC her zaman big-endian
        out.icc_version      = '' + (blob[8] & 0xFF) + '.' + ((blob[9] & 0xFF) >> 4) + '.' + (blob[9] & 0x0F)
        out.icc_device_class = iccSig(blob, 12)
        out.icc_colorspace   = iccSig(blob, 16)
        int y = bb.getShort(24) & 0xFFFF
        int mo = bb.getShort(26) & 0xFFFF
        int d = bb.getShort(28) & 0xFFFF
        int hh = bb.getShort(30) & 0xFFFF
        int mi = bb.getShort(32) & 0xFFFF
        int ss = bb.getShort(34) & 0xFFFF
        if (y > 1990 && y < 2100) out.icc_created = String.format(java.util.Locale.US, '%04d-%02d-%02dT%02d:%02d:%02d', y, mo, d, hh, mi, ss)
        int nTags = bb.getInt(128)
        if (nTags > 0 && nTags < 200 && 132 + nTags * 12 <= blob.length) {
            for (int i = 0; i < nTags; i++) {
                int p = 132 + i * 12
                if (iccSig(blob, p) != 'desc') continue
                int o = bb.getInt(p + 4)
                int sz = bb.getInt(p + 8)
                if (o < 0 || sz < 12 || o + sz > blob.length) break
                String type = iccSig(blob, o)
                String text = ''
                if (type == 'desc') {                      // ICC v2: ASCII
                    int n = bb.getInt(o + 8)
                    if (n > 0 && o + 12 + n <= blob.length) text = new String(blob, o + 12, n, java.nio.charset.StandardCharsets.ISO_8859_1)
                } else if (type == 'mluc') {               // ICC v4: UTF-16BE, ilk kayıt
                    int recs = bb.getInt(o + 8)
                    if (recs > 0) {
                        int ln = bb.getInt(o + 20)
                        int ro = bb.getInt(o + 24)
                        if (ln > 0 && o + ro + ln <= blob.length) text = new String(blob, o + ro, ln, java.nio.charset.StandardCharsets.UTF_16BE)
                    }
                }
                text = text.replace('\u0000', '').trim()
                if (!text.isEmpty()) out.icc_description = (text.length() > 200 ? text.substring(0, 200) : text)
                break
            }
        }
    } catch (Throwable ignore) {}
    return out
}
def applyTiffHeader = { m, File f ->
    if (f == null || !f.isFile()) return
    if (!TIFF_HEADER_EXT.contains((m.file_extension ?: '').toString())) return
    def h = readTiffHeader(f)
    if (h == null) return
    m.is_bigtiff      = h.big
    m.tiff_ifd_count  = h.ifds.size()
    m.has_icc_profile = h.ifds[0].icc
    if (h.ifds[0].iccBytes != null) parseIcc((byte[]) h.ifds[0].iccBytes).each { k, v -> m[k] = v }
    TIFF_INFO_TAGS.each { tag, name ->
        def v = h.ifds[0].ascii.get(tag)
        if (v) m['tiff_' + name.toLowerCase(java.util.Locale.ROOT)] = v
    }
    def filled = new LinkedHashSet(), blank = new LinkedHashSet()
    h.ifds.eachWithIndex { ifd, int idx ->
        TIFF_ID_TAGS.each { tag, name ->
            if (!ifd.ascii.containsKey(tag)) return
            (isBlankValue(ifd.ascii.get(tag)) ? blank : filled).add(name)
        }
        def ap = parseAperio(ifd.ascii.get(270))
        if (ap == null) return
        if (ap.assoc == 'label') m.label_ifd_present = true
        if (ap.assoc == 'macro') m.macro_ifd_present = true
        ap.keys.each { k, v -> if (isIdKey(k)) (isBlankValue(v) ? blank : filled).add(k) }
        if (idx != 0) return
        m.scanner = ap.scanner            // "Aperio Leica Biosystems GT450 v1.3.3" (satıcı + model + yazılım sürümü)
        if (ap.compression != null) m.compression = ap.compression
        if (ap.quality != null)     m.compression_quality = ap.quality
        if (ap.tile != null)        m.tile_size = ap.tile
        if (ap.softwareFamily != null)  m.scanner_software_family  = ap.softwareFamily
        if (ap.softwareVersion != null) m.scanner_software_version = ap.softwareVersion
        if (ap.codec != null)           m.codec = ap.codec
        if (ap.jpegColorspace != null)  m.jpeg_colorspace = ap.jpegColorspace
        ap.keys.each { k, v ->
            def col = APERIO_COLUMNS.get(k)
            if (col != null) m[col] = v
            def rawKey = 'raw_' + k.replaceAll('[\\r\\n",]', ' ').trim()
            if (!m.containsKey(rawKey)) m[rawKey] = v
        }
    }
    blank.removeAll(filled)
    m.embedded_identity_filled_count = filled.size()
    m.embedded_identity_filled       = filled.join('; ')
    m.embedded_identity_blank        = blank.join('; ')
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
    // TIFF / Aperio başlığı doğrudan dosyadan (tarayıcı, sıkıştırma, gömülü kimlik alanları)
    try { if (m.file_path != null) applyTiffHeader(m, new File(m.file_path.toString())) } catch (Throwable ignore) {}
    return m
}

// ── Tek lam için okunabilir pano metni ────────────────────────────────────
def reportText = { m ->
    def sb = new StringBuilder()
    def line = { String label, Object val -> sb << String.format(java.util.Locale.US, '  %-22s : %s%n', label, (val == null ? '—' : val.toString())) }
    sb << 'LAM ÜST VERİSİ\n'
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
    if (m.area_mm2 != null) line('Tarama alanı (mm²)', fmt((double) m.area_mm2, 1) + '  (tüm görüntü; doku alanı değil)')
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
    if (m.compression != null) line('Sıkıştırma', m.compression + (m.compression_quality != null ? (' Q=' + m.compression_quality) : ''))
    if (m.tiff_ifd_count != null) line('TIFF IFD sayısı', '' + m.tiff_ifd_count + (m.is_bigtiff ? '  (BigTIFF)' : ''))
    if (m.has_icc_profile != null) line('ICC profili', m.has_icc_profile ? ('var' + (m.icc_md5 != null ? ('  md5 ' + m.icc_md5.toString().substring(0, 12) + (m.icc_created != null ? ('  oluşturma ' + m.icc_created) : '')) : '')) : 'yok')
    if (m.icc_description != null) line('ICC açıklaması', m.icc_description)
    if (m.has_label_image != null) line('Etiket / makro görüntü', (m.has_label_image ? 'var' : 'yok') + ' / ' + (m.has_macro_image ? 'var' : 'yok'))
    if (m.embedded_identity_filled_count != null) {
        sb << '\n'
        line('Gömülü kimlik alanı (dolu)', m.embedded_identity_filled ?: 'yok')
        line('Gömülü kimlik alanı (boş)', m.embedded_identity_blank ?: 'yok')
    }
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

// ── Headless: yalnız açık lamı raporla (UI yok, değişiklik yok) ────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println 'Önce bir lam açın (headless modda yalnız açık lam raporlanır).'; return }
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
    sb << 'KOHORT ÜST VERİSİ — ÖZET\n'
    sb << '════════════════════════\n\n'
    sb << String.format(java.util.Locale.US, '  Taranan lam   : %,d%n', rows.size())
    sb << String.format(java.util.Locale.US, '  Atlanan / hatalı: %,d%n', errors.size())
    def pixels = rows.collect { it.pixel_width_um }.findAll { it != null && !Double.isNaN((double) it) && it > 0 }
    if (!pixels.isEmpty()) {
        sb << String.format(java.util.Locale.US, '  Piksel (µm/px)  : %.3f – %.3f%n', (double) pixels.min(), (double) pixels.max())
    }
    int withPyr = rows.count { it.has_pyramid }
    sb << String.format(java.util.Locale.US, '  Piramitli       : %,d / %,d%n', withPyr, rows.size())
    def scanners = rows.collect { it.scanner }.findAll { it }.countBy { it }
    if (!scanners.isEmpty()) sb << '  Tarayıcılar     : ' + scanners.collect { k, v -> k + ' (' + v + ')' }.join(', ') + '\n'
    def iccs = rows.collect { it.icc_md5 }.findAll { it }.countBy { it.toString().substring(0, 12) }
    if (!iccs.isEmpty()) sb << '  ICC profilleri  : ' + iccs.size() + ' farklı: ' + iccs.collect { k, v -> k + ' (' + v + ')' }.join(', ') + '\n'
    def formats = rows.collect { it.file_extension }.findAll { it }.countBy { it }
    if (!formats.isEmpty()) sb << '  Biçimler        : ' + formats.collect { k, v -> k + '(' + v + ')' }.join(', ') + '\n'
    double totMB = 0.0d; rows.each { if (it.file_size_mb != null) totMB += (double) it.file_size_mb }
    if (totMB > 0) sb << String.format(java.util.Locale.US, '  Toplam boyut    : %,d MB%n', Math.round(totMB))
    int withLabel = rows.count { it.has_label_image }, withMacro = rows.count { it.has_macro_image }
    sb << String.format(java.util.Locale.US, '  Etiket görüntüsü: %,d / %,d%n', withLabel, rows.size())
    sb << String.format(java.util.Locale.US, '  Makro görüntü   : %,d / %,d%n', withMacro, rows.size())
    def headerRows = rows.findAll { it.embedded_identity_filled_count != null }
    if (!headerRows.isEmpty()) {
        int withId = headerRows.count { ((int) it.embedded_identity_filled_count) > 0 }
        sb << String.format(java.util.Locale.US, '  Gömülü kimlik alanı dolu lam: %,d / %,d%n', withId, headerRows.size())
        def idKeys = new java.util.TreeSet()
        headerRows.each { r -> (r.embedded_identity_filled ?: '').toString().split(';').each { if (it.trim()) idKeys.add(it.trim()) } }
        if (!idKeys.isEmpty()) sb << '  Dolu alanlar    : ' + idKeys.join(', ') + '\n'
        sb << '\n  Dikkat: CSV bu alanların DEĞERLERİNİ de içerir (raw_* ve tarama sütunları).\n'
        sb << '  CSV\'yi paylaşmadan önce bu sütunları ve file_path / file_uri sütunlarını gözden geçirin.\n'
    }
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
            w.writeLine('Kohort Üst Verisi — ' + nowStr())
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

    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); lbl.setMaxWidth(Double.MAX_VALUE); center.getChildren().add(lbl) }
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
        title.setText(workshopText('script.f96ffe3410c3', 'Kohort üst veri sihirbazı'))
        if (imageData != null) {
            def rep
            try { rep = reportText(extractMetadata(imageData, QP.getProjectEntry(), project)) }
            catch (Throwable t) { rep = 'Açık lam okunamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName()) }
            reportRef.set(rep)
            addMonoArea(rep)
        } else {
            reportRef.set('')
            addGuidance('Tek lam raporu için bir lam açın. Tüm projeyi taramak için bir proje açıp aşağıdaki düğmeyi kullanın.')
        }
        def chk = new javafx.scene.control.CheckBox(workshopText('script.4a70604a5fba', 'Proje sütunlarına da yaz (sıralanabilir)'))
        chk.setSelected(writeColsRef.get())
        chk.selectedProperty().addListener({ obs, o, n -> writeColsRef.set(n) } as javafx.beans.value.ChangeListener)
        chk.setTooltip(new javafx.scene.control.Tooltip(workshopText('script.0eae8e676a20', 'İşaretliyse çekirdek alanlar (mpp, büyütme, tarayıcı, boyut, piramit, alan) QuPath Proje sekmesine sütun olarak yazılır.')))
        center.getChildren().add(chk)

        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
        if (imageData != null) actions.add(navButton(workshopText('script.a8bcca42d9ce', 'Kopyala'), { copyToClipboard(reportRef.get()) }, workshopText('script.1df93d0a3b69', 'Bu lam raporunu panoya kopyala')))
        actions.add(navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }))
        def scanBtn = navButton(workshopText('script.450eaada98e2', 'Tüm projeyi tara → CSV ▶'), { startScan() }, workshopText('script.534918cc247c', 'Projedeki her görüntünün üst verisini CSV\'ye yazar'))
        scanBtn.setDisable(project == null)
        actions.add(scanBtn)
    } else if (cur == 'SCANNING') {
        title.setText(workshopText('script.ca10a4616efe', 'Proje taranıyor…'))
        addGuidance('Her görüntü diskten okunuyor; büyük kohortlarda zaman alabilir. Çıktı aşağıda akıyor.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton(workshopText('script.3f6920a9ead3', 'İptal et'), { cancelledRef.set(true) }))
    } else if (cur == 'RESULT') {
        title.setText(workshopText('script.92c17020894f', 'Tamamlandı ✅'))
        addMonoArea(resultTextRef.get())
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
        actions.add(navButton(workshopText('script.a8bcca42d9ce', 'Kopyala'), { copyToClipboard(resultTextRef.get()) }))
        def dir = outDirRef.get()
        def openBtn = navButton(workshopText('script.d6f2f6cad078', 'Klasörü aç'), {
            try { if (java.awt.Desktop.isDesktopSupported() && dir != null && dir.isDirectory()) java.awt.Desktop.getDesktop().open(dir) }
            catch (Throwable t) { Dialogs.showErrorMessage(workshopText('script.4b2ba1214a96', 'Klasör açılamadı'), t.getMessage() ?: '') }
        })
        openBtn.setDisable(dir == null)
        actions.add(openBtn)
        actions.add(navButton(workshopText('script.171029fff08d', '↻ Yeniden'), { step.set('READY'); render() }))
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
    stage.setScene(new javafx.scene.Scene(root, 820, 640))
}

// ── Açılış ───────────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle(workshopText('script.f96ffe3410c3', 'Kohort üst veri sihirbazı'))
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.7fd583bc0c88', 'Sihirbaz açılamadı'), workshopText('script.9aac4912cb86', 'Bu araç başlatılamadı.\n\nYapabilecekleriniz:\n  1. Araç açık bir lam istiyorsa önce lamı açın.\n  2. QuPath\'i tamamen kapatıp yeniden açın ve tekrar deneyin.\n  3. Sorun sürerse View → Show log penceresinin ekran görüntüsünü atölye düzenleyicisine gönderin.\n\nTeknik ayrıntı: ') + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '(ileti yok)'))
    }
}
println '✓ Kohort üst veri sihirbazı açıldı.'

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
