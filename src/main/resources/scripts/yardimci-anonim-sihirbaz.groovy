/**
 * Yardımcı - WSI Anonimleştirme Sihirbazı (denetim + uygulama, tek pencere)
 * ------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir; Python GEREKMEZ).
 *
 * İKİ KİP:
 *   1. DENETLE (salt okunur) — "Bu slayt / bu proje anonim mi?" sorusunu yanıtlar.
 *      Her dosya için: etiket/makro görüntüsü var mı, hangi kimlik alanları
 *      (Filename, Date, User, ScanScope ID, ImageID …) HANGİ IFD'lerde dolu, standart
 *      TIFF etiketleri (DateTime, Artist …) dolu mu, dosya adı dosyanın içinde geçiyor mu.
 *      ETİKET/MAKRO: Sectra anonim dışa aktarımı etiketi sentetik bir çizimdir (DataMatrix +
 *      "ANON<kod>;;<blok>;<boya>"), makrosu tek renktir. Denetim etiketin DataMatrix kodunu çözer
 *      (ZXing core 3.5.3, Apache-2.0; paketlenmez — ilk kullanımda Maven Central'dan SHA-256 sabitli
 *      indirilir) ve yalnız ÇÖZÜLMÜŞ kod "ANON" ile başlıyorsa etiketi anonim sayar; fotoğraf benzeri
 *      etiket ve dolu makro EKSİK'tir. ANON ile başlamayan kodun metni gösterilmez. Varsa Sectra
 *      <slayt>.import yan dosyasındaki RequestId de karşılaştırılır. Arayüzde etiket/makro galerisi.
 *      Karar: TEMİZ / EKSİK / BELİRSİZ. Hiçbir slayta yazmaz.
 *   2. ANONİMLEŞTİR — seçilen slaytların anonim KOPYALARINI yeni bir klasöre yazar:
 *      • Seviye I   : dosya adı → case_1, case_2 … ya da UUID.
 *      • Seviye III : etiket + makro görüntüsü IFD zincirinden çıkarılır VE piksel
 *                     verisi sıfırlanır (yalnız "bağlantı kesme" = Seviye II'de kalmaz).
 *      • Seviye IV  : Aperio başlığındaki kimlik alanları TÜM IFD'lerde (küçük resim ve
 *                     GT450'de her piramit düzeyi dahil) aynı uzunlukta 'X' ile doldurulur;
 *                     standart TIFF metin etiketleri (DateTime, Artist, HostComputer …)
 *                     temizlenir. MPP/AppMag/Left/Top gibi ölçüm alanlarına DOKUNULMAZ.
 *      Her kopya önce geçici adla yazılır, sonra DOĞRULANIR (IFD yeniden okunur, dosyanın
 *      tamamı orijinal ad/silinen değerler için bayt bayt taranır, QuPath ile açılıp boyut
 *      + piksel boyutu orijinalle karşılaştırılır). Doğrulamayı geçemeyen kopya SİLİNİR;
 *      anonim adı yalnız doğrulanmış dosya alır.
 *      İsteğe bağlı: anonim kopyalardan YENİ bir QuPath projesi kurar (giriş adları anonim;
 *      proje sütunları/açıklama/etiketler taşınmaz; anotasyon/tespitler isteğe bağlı ve
 *      YENİ kimliklerle kopyalanır).
 *
 * KAPSAM: açık görüntü ya da proje görüntülerinden seçtikleriniz (tümü = proje geneli).
 *   Proje tarayıcısının seçimi betiklerden okunamadığı için seçim sihirbazın listesindedir.
 *
 * BİÇİM DESTEĞİ (dürüst):
 *   • Aperio SVS (AT2/GT450; klasik TIFF ve BigTIFF) → tam (I + III + IV), doğrulamalı.
 *   • Genel TIFF / SCN / BIF / NDPI / CZI … → yalnız "kısmi biçimler" açıksa: ad + standart
 *     etiketler + dosya adının ASCII etiketlerden silinmesi; KISMİ olarak işaretlenir
 *     (NDPI'nin 4 GB üstü ofset düzeni nedeniyle NDPI dosyasına hiç yazılmaz).
 *   • MRXS / VSI / DICOM / OME-Zarr gibi çok dosyalı biçimler → ATLANIR (wsi-anon / üretici aracı).
 *   • Doku görüntüsünün kendisi (Seviye V) bu araçla anonimleştirilemez.
 *
 * GÜVENLİ TASARIM: orijinal slaytlar yalnız OKUNUR; açık proje DEĞİŞTİRİLMEZ; çıktı klasöründe
 *   var olan dosyanın üzerine yazılmaz; yeni proje yalnız BOŞ bir klasöre kurulur.
 *
 * EŞLEŞTİRME ANAHTARI = PHI: geri alınabilir modda yazılan CSV/JSON orijinal kimlikleri içerir.
 *   Tuz (salt.bin), eklemeli anahtar tablosu (anahtar.csv), kaynak parmak izleri ve eşleştirme/manifest
 *   dosyaları ÇIKTI KLASÖRÜNÜN DIŞINDA, "anahtar klasöründe" tutulur (varsayılan <veri kökü>/anonim-anahtar/;
 *   çıktı klasörünün içinde olamaz). Güvenli, erişimi kısıtlı bir yerde tutun; salt.bin'i YEDEKLEYİN;
 *   anonim slaytlarla ASLA paylaşmayın. Çıktı klasöründeki "anonimlestirme_raporu_*.txt" orijinal ad
 *   İÇERMEZ (paylaşılabilir); başarılı çalışmadan sonra SHA256SUMS.txt (LF) + BENIOKU.md de yazılır.
 *   KANIT: her kaynağın parmak izi (boyut + zaman + ilk/son 16 MB SHA-256) işlemden önce/sonra/çalışma
 *   sonunda karşılaştırılır; fark → o birim HATA. Paylaşılabilir dosyaların zaman damgaları 2000-01-01Z.
 *
 * KULLANIM (arayüz): [Extensions → Atölye → Yardımcılar → Klinik ve kohort →
 *   WSI anonimleştirme sihirbazı] → kapsam + çıktı klasörü → "Denetle" ya da "Önizle ▶ → Başlat".
 *
 * KULLANIM (arayüzsüz):
 *   QuPath script yardimci-anonim-sihirbaz.groovy --args "proje=<proje.qpproj>" [--args "kip=denetle"]
 *   QuPath script yardimci-anonim-sihirbaz.groovy -i <slayt.svs> --args "kip=anonimlestir" --args "cikti=<klasör>"
 *     Anahtarlar: proje, cikti, kip (denetle|anonimlestir; varsayılan denetle), onek, adlandirma
 *     (sirali|uuid|hmac), anahtarklasor (tuz + anahtar tablosu; çıktının DIŞINDA), desen (kayıt no regex'i;
 *     boş = varsayılan), tarama (ana görüntü kenar şeridi denetimi; evet/hayır),
 *     etiket, ustveri, anahtar, kismi, yeniproje, nesneler, barkod (evet/hayır),
 *     etiketler=<klasör> (denetimde etiket/makro PNG'lerini yazar).
 *     -p KULLANMAYIN (betik projeyi kendisi açar). Bilinmeyen anahtar/değer HATA verir.
 *
 * YÖNTEM / KAYNAK:
 *   • Bisson 2023 (WSI anonimleştirme seviyeleri I–V); EMPAIA wsi-anon; Kitware ImageDePHI.
 *   • Aperio etiket/makro ayrıştırma: OpenSlide Aperio sürücüsünün ImageDescription kuralı
 *     ("label WxH" / "macro WxH"); IFD bağlantı kesme + veri sıfırlama: anonymize-slide yaklaşımı.
 *   • S. Balcı, anonym-qupath (gizli; talep üzerine) — önceki sürüm bunun "Compatible" akışıydı.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlıdır; klinik karar üretmez.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.images.ImageData
import qupath.lib.images.servers.ImageServerBuilder
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.images.servers.ImageServers
import qupath.lib.objects.PathObjectTools
import qupath.lib.projects.ProjectIO
import qupath.lib.projects.Projects
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ─────────────────────────────────────────────────────────────────
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def NAMING_OPTIONS = ['sequential', 'uuid', 'hmac']
def TIFF_EXT  = ['svs', 'tif', 'tiff', 'btf', 'scn', 'bif', 'qptiff'] as Set
def MULTI_EXT = ['mrxs', 'vsi', 'vms', 'vmu', 'dcm', 'ets', 'zarr'] as Set
def KIND_LABEL = [
    aperio : 'Aperio SVS (tam destek)',
    tiff   : 'Genel TIFF (kısmi)',
    ndpi   : 'NDPI (kısmi)',
    other  : 'Diğer tek dosya (kısmi)',
    multi  : 'Çok dosyalı biçim (atlanır)',
    missing: 'Yerel dosya yok (atlanır)',
    unreadable: 'Okunamadı (kilitli / erişim yok — atlanır)'
]
// Aperio başlığında temizlenen anahtarlar (boşluk/altçizgi atılmış, küçük harf). Ölçüm alanları
// (MPP, AppMag, Left, Top, StripeWidth …) bu listede YOK → kalibrasyon bozulmaz.
// Üretimde (AT2/GT450) kimlik / yarı kimlik taşıyan alanlar: Filename, Title, Patient*, Case*, Barcode,
// Date, Time, Time Zone, User, ImageID, ScanScope ID, Rack, Slide. MPP ve AppMag ASLA listeye girmez
// (aşağı akım araçlar MPP olmadan çöker).
def PHI_EXACT  = ['filename', 'title', 'date', 'time', 'timezone', 'datetime', 'user', 'username', 'operator',
                  'scanscopeid', 'scannerid', 'imageid', 'barcode', 'label', 'slideid', 'slidename',
                  'name', 'dsrid', 'rack', 'slide'] as Set
def PHI_PREFIX = ['patient', 'case', 'accession', 'specimen', 'barcode']
def STD_PHI_TAGS = [269: 'DocumentName', 285: 'PageName', 306: 'DateTime', 315: 'Artist',
                    316: 'HostComputer', 42016: 'ImageUniqueID']
def TYPE_SIZE = [1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8,
                 13: 4, 16: 8, 17: 8, 18: 8]
int MIN_PROBE = 6           // bayt taramasında rastgele eşleşmeyi önlemek için en kısa arama dizisi
def ISO = StandardCharsets.ISO_8859_1
// Kayıt (accession) numarası deseni — yapılandırılabilir (tercih 'accPattern' / arayüzsüz desen=).
// Varsayılan iki biçimi yakalar: \d{1,6}-\d{2} (ör. 7-25, 1021-25) ve referans biçimi
// \d{3,6}\s*-\s*[A-Za-z0-9] (ör. 12345-1A). Harfe yapışık belirteçler (PDL1-22C3, SS12345-01) başta
// (?<![A-Za-z0-9]) ile elenir; ISO tarihleri (2026-09-28) ayrıca her zaman yok sayılır (accessionHits).
def ACCESSION_DEFAULT = '(?<![A-Za-z0-9])(?:\\d{1,6}-\\d{2}(?![0-9A-Za-z])|\\d{3,6}\\s*-\\s*[A-Za-z0-9])'
def ISO_DATE_RE = '(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)'
def ACC_COVERAGE_TR = 'Varsayılan kayıt numarası deseni yalnız NNN-YY ve NNNNN-NA biçimlerini (ör. 1021-25, 7-25, 12345-1A) arar; S25-12345, B2025-1234 ya da 25-12345 gibi biçimleri YAKALAMAZ. Kurumunuzun biçimi farklıysa desen= (arayüzde "Kayıt no deseni") ile verin; "desen bulunmadı" yalnız aranan desen için geçerlidir.'
def PIN_ISO ='2000-01-01T00:00:00Z'      // paylaşılabilir dosyaların sabitlenen zamanı (mtime + oluşturma + erişim)
def PKG_STATUSES = ['TAMAM', 'KISMİ', 'İNCELE']   // diskte kopyası bulunan durumlar
def SALT_FILE = 'salt.bin'
def CROSSWALK_FILE = 'anahtar.csv'
def KEY_COLUMNS = ['Original_Name', 'Original_Path', 'Pseudonym', 'Naming_Mode', 'Run_Timestamp', 'Status', 'Fingerprint_Before', 'Fingerprint_After']
def FP_BLOCK = 16L << 20                  // parmak izi: dosyanın ilk ve son 16 MB'ı (TIFF başlığı, IFD'ler, etiket/makro şeritleri)
def SUB_IFD_TAGS = [330, 34665, 34853, 40965] as Set   // alt-IFD göstericileri: etiket sayfasında varsa dosya REDDEDİLİR
// Tarama alanı kenar bandı (ders 7): ~1024 px küçük resimde en soldaki / en sağdaki %12 sütun, satırların
// >%60'ında ortalama parlaklık < 120 ise "etiket şeridi olabilir"
def EDGE_THUMB = 1024
double EDGE_FRAC = 0.12d
double EDGE_LUM = 120d
double EDGE_ROWS = 0.60d

// ── Kalıcı yapılandırma (java.util.prefs; WorkshopPrefs.atolye.* KULLANMAZ) ───
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/anonimize')
def loadConfig = { ->
    [ prefix         : prefs.get('prefix', 'case'),
      naming         : prefs.get('naming', 'sequential'),
      outDir         : prefs.get('outDir', ''),
      scope          : prefs.get('scope', 'proje'),
      removeLabels   : prefs.getBoolean('removeLabels', true),
      redactMeta     : prefs.getBoolean('redactMeta', true),
      reversible     : prefs.getBoolean('reversible', true),
      includePartial : prefs.getBoolean('includePartial', false),
      makeProject    : prefs.getBoolean('makeProject', false),
      transferObjects: prefs.getBoolean('transferObjects', false),
      decodeLabels   : prefs.getBoolean('decodeLabels', true),
      mainScan       : prefs.getBoolean('mainScan', true),
      keyDir         : prefs.get('keyDir', ''),
      accPattern     : prefs.get('accPattern', '') ]
}
def saveConfig = { cfg ->
    prefs.put('prefix', (cfg.prefix ?: 'case'))
    prefs.put('naming', (cfg.naming ?: 'sequential'))
    prefs.put('outDir', (cfg.outDir ?: ''))
    prefs.put('scope', (cfg.scope ?: 'proje'))
    prefs.put('keyDir', (cfg.keyDir ?: ''))
    prefs.put('accPattern', (cfg.accPattern ?: ''))
    ['removeLabels', 'redactMeta', 'reversible', 'includePartial', 'makeProject', 'transferObjects', 'decodeLabels', 'mainScan'].each { k ->
        prefs.putBoolean(k, (boolean) cfg[k])
    }
    try { prefs.flush() } catch (Throwable ignore) {}
}

// ── Küçük yardımcılar ────────────────────────────────────────────────────────
def extOf = { File f ->
    String n = f?.getName() ?: ''
    int d = n.lastIndexOf('.')
    return (d > 0) ? n.substring(d + 1).toLowerCase(java.util.Locale.ROOT) : ''
}
def stemOf = { String n ->
    if (!n) return ''
    int d = n.lastIndexOf('.')
    return (d > 0) ? n.substring(0, d) : n
}
// Dosyadaki ASCII etiketler bayt olarak okunur (ISO-8859-1: 1 karakter = 1 bayt). Türkçe harfli bir
// adın UTF-8 baytlarını aynı uzaya taşır → arama/değiştirme bayt düzeyinde doğru eşleşir.
def latin1Form = { String s -> s == null ? null : new String(s.getBytes(StandardCharsets.UTF_8), ISO) }
def localFile = { uri ->
    try {
        if (uri != null && 'file'.equals(uri.getScheme())) {
            def f = new File(uri)
            if (f.exists()) return f
        }
    } catch (Throwable ignore) {}
    return null
}
def isLabelOrMacro = { String n ->
    String s = (n ?: '').toLowerCase(java.util.Locale.ROOT)
    return s.contains('label') || s.contains('macro')
}
// Değerin "boş" sayılması: boş, yalnız X / 0 / ayraç, ya da önceki sürümün "REDACTED" işareti
def isBlankValue = { String core ->
    String c = (core ?: '').trim()
    if (c.isEmpty() || c.equalsIgnoreCase('REDACTED')) return true
    return c.replaceAll('[X0:\\s./\\-]', '').isEmpty()
}
def fmtBytes = { long b ->
    if (b >= (1L << 30)) return String.format(java.util.Locale.US, '%.2f GB', b / (double) (1L << 30))
    return String.format(java.util.Locale.US, '%.1f MB', b / (double) (1L << 20))
}

// ═════════════════════════════════════════════════════════════════════════════
// TIFF MOTORU (saf Java; klasik TIFF + BigTIFF). Yapıyı ASLA değiştirmez: yalnız
// (a) ASCII değerleri AYNI UZUNLUKTA yeniden yazar, (b) bir IFD'nin "sonraki IFD"
// işaretçisini günceller, (c) çıkarılan görüntünün piksel baytlarını sıfırlar.
// ═════════════════════════════════════════════════════════════════════════════
def rdBuf = { RandomAccessFile raf, long pos, int n, boolean le ->
    byte[] b = new byte[n]
    raf.seek(pos); raf.readFully(b)
    def bb = ByteBuffer.wrap(b)
    bb.order(le ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN)
    return bb
}
def u16 = { ByteBuffer bb -> ((int) bb.getShort()) & 0xFFFF }
def u32 = { ByteBuffer bb -> ((long) bb.getInt()) & 0xFFFFFFFFL }

def parseTiff = { RandomAccessFile raf ->
    long len = raf.length()
    if (len < 16) return null
    def hdr = rdBuf(raf, 0L, 16, true)
    byte b0 = hdr.get(0), b1 = hdr.get(1)
    boolean le
    if (b0 == (byte) 0x49 && b1 == (byte) 0x49) le = true
    else if (b0 == (byte) 0x4D && b1 == (byte) 0x4D) le = false
    else return null
    hdr = rdBuf(raf, 0L, 16, le)
    hdr.position(2)
    int magic = u16(hdr)
    boolean big
    long first
    if (magic == 42) { big = false; first = u32(hdr) }
    else if (magic == 43) {
        big = true
        int osz = u16(hdr); hdr.getShort()
        if (osz != 8) return null
        first = hdr.getLong()
    } else return null
    int cntSize = big ? 8 : 2, entSize = big ? 20 : 12, ptrSize = big ? 8 : 4
    def ifds = []
    def seen = new HashSet<Long>()
    long off = first
    while (off != 0L) {
        if (off < 8L || off >= len || !seen.add(off) || ifds.size() > 4096)
            throw new java.io.IOException('Bozuk IFD zinciri (ofset ' + off + ')')
        def cb = rdBuf(raf, off, cntSize, le)
        long n = big ? cb.getLong() : u16(cb)
        if (n <= 0L || n > 4096L) throw new java.io.IOException('Geçersiz etiket sayısı (ofset ' + off + ')')
        long entStart = off + cntSize
        long nextPtrPos = entStart + n * entSize
        def eb = rdBuf(raf, entStart, (int) (n * entSize), le)
        def entries = new LinkedHashMap()
        for (int k = 0; k < n; k++) {
            int base = k * entSize
            eb.position(base)
            int tag = u16(eb)
            int type = u16(eb)
            long count = big ? eb.getLong() : u32(eb)
            long fieldPos = entStart + base + (big ? 12 : 8)
            int tsz = (TYPE_SIZE.get(type) ?: 1) as int
            long size = count * tsz
            long dataPos
            if (size <= ptrSize) dataPos = fieldPos
            else { eb.position(base + (big ? 12 : 8)); dataPos = big ? eb.getLong() : u32(eb) }
            entries.put(tag, [tag: tag, type: type, count: count, size: size, dataPos: dataPos])
        }
        def pb = rdBuf(raf, nextPtrPos, ptrSize, le)
        long next = big ? pb.getLong() : u32(pb)
        ifds << [index: ifds.size(), offset: off, nextPtrPos: nextPtrPos, next: next, entries: entries]
        off = next
    }
    return [le: le, big: big, ifds: ifds, length: len]
}
def readAscii = { RandomAccessFile raf, Map e ->
    if (e == null || e.type != 2 || e.count <= 0L || e.count > 50000000L) return null
    if (e.dataPos + e.count > raf.length()) return null
    byte[] b = new byte[(int) e.count]
    raf.seek(e.dataPos); raf.readFully(b)
    return new String(b, ISO)
}
def writeAscii = { RandomAccessFile raf, Map e, String s ->
    byte[] b = s.getBytes(ISO)
    if (b.length != e.count) throw new IllegalStateException('ASCII uzunluğu değişti (etiket ' + e.tag + ')')
    raf.seek(e.dataPos); raf.write(b)
}
def readUInts = { RandomAccessFile raf, Map e, boolean le ->
    int tsz = (TYPE_SIZE.get(e.type) ?: 4) as int
    if (!(e.type in [3, 4, 13, 16, 18]) || e.count <= 0L || e.count * tsz > 64000000L) return []
    def bb = rdBuf(raf, e.dataPos, (int) (e.count * tsz), le)
    def out = new ArrayList<Long>((int) e.count)
    for (int i = 0; i < e.count; i++) {
        if (tsz == 2) out.add((long) u16(bb))
        else if (tsz == 4) out.add(u32(bb))
        else out.add(bb.getLong())
    }
    return out
}
def writePtr = { RandomAccessFile raf, long pos, long val, boolean le, boolean big ->
    def bb = ByteBuffer.allocate(big ? 8 : 4)
    bb.order(le ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN)
    if (big) bb.putLong(val) else bb.putInt((int) val)
    raf.seek(pos); raf.write(bb.array())
}
// Aperio: "…\nlabel 711x733" / "…\nmacro 1600x618" (OpenSlide Aperio sürücüsünün kuralı)
def assocKind = { String desc ->
    if (desc == null) return null
    def m = (desc =~ /(?i)(?:^|[\r\n])\s*(label|macro)\b/)
    return m.find() ? m.group(1).toLowerCase(java.util.Locale.ROOT) : null
}
def normKey = { String k -> (k ?: '').trim().toLowerCase(java.util.Locale.ROOT).replaceAll('[\\s_\\-]', '') }
def isPhiKey = { String k ->
    String n = normKey(k)
    return PHI_EXACT.contains(n) || PHI_PREFIX.any { n.startsWith(it) }
}
// Aperio ImageDescription: ilk bölüm (başlık satırı, "Q=70" içerir) atlanır; sonraki "Anahtar = Değer"
// bölümlerinden kimlik alanlarının DEĞERİ aynı uzunlukta 'X' ile doldurulur. dryRun → yalnız raporla.
def redactAperioDesc = { String desc ->
    String[] parts = desc.split('\\|', -1)
    def keys = []; def values = []
    for (int i = 1; i < parts.length; i++) {
        String p = parts[i]
        int eq = p.indexOf('=')
        if (eq <= 0) continue
        String k = p.substring(0, eq)
        if (!isPhiKey(k)) continue
        def m = (p.substring(eq + 1) =~ /(?s)^(\s*)(.*?)([\s\x00]*)$/)
        if (!m.matches()) continue
        String core = m.group(2)
        if (isBlankValue(core)) continue
        keys << k.trim(); values << core
        parts[i] = k + '=' + m.group(1) + ('X' * core.length()) + m.group(3)
    }
    return [text: String.join('|', parts), keys: keys, values: values]
}
// Standart metin etiketi: içerik → X (DateTime'da rakam → 0, biçim korunur); sondaki NUL korunur
def blankAscii = { String s, boolean isDate ->
    def m = (s =~ /(?s)^(.*?)([\s\x00]*)$/)
    if (!m.matches()) return s
    String core = m.group(1)
    String nc = isDate ? core.replaceAll('[0-9]', '0') : ('X' * core.length())
    return nc + m.group(2)
}
// Dosya adının TAMAMI (parça değil) ASCII etiketlerden silinir. Yalnız ≥6 karakter ve en az bir harf
// içeren adlar: "2", "36" gibi parçalar MPP/boyut sayılarını bozabileceği için ASLA aranmaz.
def stemReplaceable = { String st -> st != null && st.length() >= MIN_PROBE && (st =~ /\p{L}/).find() }

// ── Dosya sınıflandırma ──────────────────────────────────────────────────────
def classifyFile = { File f, List uris ->
    if (uris != null && uris.size() > 1) return 'multi'
    if (f == null) return 'missing'
    if (f.isDirectory()) return 'multi'
    String ext = extOf(f)
    if (MULTI_EXT.contains(ext)) return 'multi'
    if (ext == 'ndpi') return 'ndpi'
    if (!TIFF_EXT.contains(ext)) return 'other'
    RandomAccessFile raf = null
    try {
        raf = new RandomAccessFile(f, 'r')
        def t = parseTiff(raf)
        if (t == null || t.ifds.isEmpty()) return 'other'
        String d0 = readAscii(raf, t.ifds[0].entries.get(270))
        return (d0 != null && d0.startsWith('Aperio')) ? 'aperio' : 'tiff'
    } catch (java.io.FileNotFoundException | java.nio.file.AccessDeniedException e) {
        return 'unreadable'          // kilitli dosya ya da erişim izni yok
    } catch (Throwable e) {
        return 'other'
    } finally {
        try { raf?.close() } catch (Throwable ignore) {}
    }
}

// ── QuPath ile aç: boyut, piksel boyutu, ilişkili görüntüler ─────────────────
def qupathCheck = { File f ->
    def srv = null
    try {
        srv = ImageServers.buildServer(f.toURI())
        def cal = srv.getPixelCalibration()
        return [ok    : true,
                width : srv.getWidth(), height: srv.getHeight(),
                px    : cal.hasPixelSizeMicrons() ? cal.getAveragedPixelSizeMicrons() : Double.NaN,
                assoc : new ArrayList(srv.getAssociatedImageList() ?: [])]
    } catch (Throwable t) {
        return [ok: false, error: (t.getMessage() ?: t.getClass().getSimpleName())]
    } finally {
        try { srv?.close() } catch (Throwable ignore) {}
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// ETİKET / MAKRO DENETİMİ — Sectra anonim dışa aktarımı etiketi bir FOTOĞRAF değil, sentetik bir
// çizimdir: DataMatrix + tek satır "ANON<kod>;;<blok>;<boya>"; makro tek renk (boş) bir yer tutucudur.
// Kanıt merdiveni: yalnız ÇÖZÜLMÜŞ DataMatrix (Reed–Solomon düzeltmesi geçti) "ANON" der; siyah-beyaz
// oranı yalnız destekler, tek başına temiz SAYDIRMAZ. ANON ile başlamayan kod METNİ GÖSTERİLMEZ.
// DataMatrix çözücü: ZXing core (Apache-2.0) — paketlenmez; ilk kullanımda Maven Central'dan SHA-256
// sabitli indirilir → <veri kökü>/runtimes/zxing/. İnternet yoksa denetim çözücüsüz sürer.
// ═════════════════════════════════════════════════════════════════════════════
def ZX_URL    = 'https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar'
def ZX_SHA256 = '8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82'
def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
def zxJarFile = { -> new File(new File(new File(atolyeDataRoot(), 'runtimes'), 'zxing'), 'core-3.5.3.jar') }
def sha256Of = { File f ->
    def md = java.security.MessageDigest.getInstance('SHA-256')
    f.withInputStream { ins -> byte[] b = new byte[1 << 16]; int n; while ((n = ins.read(b)) > 0) md.update(b, 0, n) }
    return md.digest().collect { String.format(java.util.Locale.US, '%02x', it & 0xff) }.join('')
}
def ensureZxing = { boolean allowDownload, Closure log ->
    File jar = zxJarFile()
    if (jar.isFile() && sha256Of(jar) == ZX_SHA256) return jar
    if (!allowDownload) return null
    try {
        log('ZXing (DataMatrix çözücü, Apache-2.0) indiriliyor: ' + ZX_URL)
        def client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(20)).build()
        def req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(ZX_URL)).timeout(java.time.Duration.ofSeconds(60)).GET().build()
        def resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() != 200) { log('ZXing indirilemedi: HTTP ' + resp.statusCode()); return null }
        jar.getParentFile().mkdirs()
        File tmp = new File(jar.getParentFile(), jar.getName() + '.tmp')
        tmp.bytes = resp.body()
        if (sha256Of(tmp) != ZX_SHA256) { tmp.delete(); log('ZXing SHA-256 uyuşmadı — dosya reddedildi.'); return null }
        Files.move(tmp.toPath(), jar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return jar
    } catch (Throwable t) {
        log('ZXing indirilemedi (' + (t.getMessage() ?: t.getClass().getSimpleName()) + ') — barkod çözülmeden devam ediliyor.')
        return null
    }
}
def lum = { int p -> (((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255)) / 3.0d }
// Çözücü kapanışı: önce doğrudan, sonra beyaz kenar boşluğuyla, sonra "yeniden ızgaralama" (modül
// merkezlerinden örnekleyip temiz bir sembol çizme; tam sayı olmayan modül boyutlu etiketler için).
def makeDecoder = { File jar ->
    if (jar == null) return null
    def cl = new URLClassLoader([jar.toURI().toURL()] as java.net.URL[], getClass().getClassLoader())
    def RGB = cl.loadClass('com.google.zxing.RGBLuminanceSource')
    def HB  = cl.loadClass('com.google.zxing.common.HybridBinarizer')
    def BBm = cl.loadClass('com.google.zxing.BinaryBitmap')
    def Rdr = cl.loadClass('com.google.zxing.datamatrix.DataMatrixReader')
    def DHT = cl.loadClass('com.google.zxing.DecodeHintType')
    def dec = { BufferedImage img, boolean pure ->
        int w = img.getWidth(), h = img.getHeight(); int[] px = img.getRGB(0, 0, w, h, null, 0, w)
        def hints = new EnumMap(DHT); hints.put(Enum.valueOf(DHT, 'TRY_HARDER'), Boolean.TRUE)
        if (pure) hints.put(Enum.valueOf(DHT, 'PURE_BARCODE'), Boolean.TRUE)
        try { return Rdr.newInstance().decode(BBm.newInstance(HB.newInstance(RGB.newInstance(w, h, px))), hints).getText() }
        catch (Throwable t) { return null }
    }
    def pad = { BufferedImage src, int m ->
        def out = new BufferedImage(src.getWidth() + 2 * m, src.getHeight() + 2 * m, BufferedImage.TYPE_INT_RGB)
        def g = out.createGraphics(); g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, out.getWidth(), out.getHeight())
        g.drawImage(src, m, m, null); g.dispose(); return out
    }
    def SIZES = [10, 12, 14, 16, 18, 20, 22, 24, 26, 32, 36, 40, 44, 48, 52]
    def regrid = { BufferedImage img ->
        int w = img.getWidth(), h = img.getHeight(); int[] px = img.getRGB(0, 0, w, h, null, 0, w)
        boolean[] dk = new boolean[px.length]
        for (int i = 0; i < px.length; i++) dk[i] = lum(px[i]) < 128
        def rowHas = { int y -> for (int x = 0; x < w; x++) if (dk[y * w + x]) return true; return false }
        int y0 = -1; for (int y = 0; y < h; y++) if (rowHas(y)) { y0 = y; break }
        if (y0 < 0) return null
        int y1 = h - 1; for (int y = y0; y < h; y++) if (!rowHas(y)) { y1 = y - 1; break }
        int x0 = -1, x1 = -1
        for (int x = 0; x < w && x0 < 0; x++) for (int y = y0; y <= y1; y++) if (dk[y * w + x]) { x0 = x; break }
        for (int x = w - 1; x >= 0 && x1 < 0; x--) for (int y = y0; y <= y1; y++) if (dk[y * w + x]) { x1 = x; break }
        if (x0 < 0 || x1 <= x0) return null
        double bw = x1 - x0 + 1, bh = y1 - y0 + 1
        for (int n : SIZES) {
            double mw = bw / n, mh = bh / n
            if (mw < 3d || mh < 3d) continue
            int q = 8, cell = 10, side = (n + 2 * q) * cell
            def o = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB); def g = o.createGraphics()
            g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, side, side); g.setColor(java.awt.Color.BLACK)
            for (int r = 0; r < n; r++) for (int c = 0; c < n; c++) {
                int sx = Math.min(w - 1, (int) Math.round(x0 + (c + 0.5d) * mw)), sy = Math.min(h - 1, (int) Math.round(y0 + (r + 0.5d) * mh))
                if (dk[sy * w + sx]) g.fillRect((c + q) * cell, (r + q) * cell, cell, cell)
            }
            g.dispose()
            String t = dec(o, true) ?: dec(o, false)
            if (t != null) return t
        }
        return null
    }
    return { BufferedImage img -> dec(img, false) ?: dec(pad(img, 40), false) ?: regrid(img) }
}
// Bio-Formats okuyucusu (OpenSlide'ın çözemediği AT2 makro JPEG'i için yedek yol)
def bioFormatsBuilder = { ->
    try { return qupath.lib.images.servers.ImageServerProvider.getInstalledImageServerBuilders(BufferedImage.class).find { it.getClass().getName().contains('BioFormats') } }
    catch (Throwable t) { return null }
}
def thumbOf = { BufferedImage img, int maxW ->
    if (img == null) return null
    double s = Math.min(1d, maxW / (double) img.getWidth())
    int tw = Math.max(1, (int) Math.round(img.getWidth() * s)), th = Math.max(1, (int) Math.round(img.getHeight() * s))
    def out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB); def g = out.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(img, 0, 0, tw, th, null); g.dispose(); return out
}
// ── Ana tarama alanı kenar bandı (ders 7) ────────────────────────────────────
// Etiket/makro silme ANA taranmış görüntünün İÇİNDEKİ kimliği silemez: bazı GT450 slaytları taramanın kenarında
// basılı etiket şeridini (koyu bant) yakalar. Ölçüt: ~1024 px küçük resimde en soldaki YA DA en sağdaki %12
// sütun; satırların > %60'ında bandın ortalama parlaklığı < 120 ise bayraklanır. Bu bir İNCELEME uyarısıdır —
// TEMİZ/EKSİK kararını tek başına değiştirmez.
def scaleMax = { BufferedImage img, int maxSide ->
    if (img == null) return null
    double s = Math.min(1d, maxSide / (double) Math.max(img.getWidth(), img.getHeight()))
    if (s >= 1d) return img
    int tw = Math.max(1, (int) Math.round(img.getWidth() * s)), th = Math.max(1, (int) Math.round(img.getHeight() * s))
    def out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB); def g = out.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(img, 0, 0, tw, th, null); g.dispose(); return out
}
def edgeBandScore = { BufferedImage img ->
    int w = img.getWidth(), h = img.getHeight()
    int bw = Math.max(1, (int) Math.round(w * EDGE_FRAC))
    if (bw * 2 > w) bw = Math.max(1, (int) (w / 2))
    int[] row = new int[w]
    int darkL = 0, darkR = 0
    for (int y = 0; y < h; y++) {
        img.getRGB(0, y, w, 1, row, 0, w)
        double sl = 0d, sr = 0d
        for (int x = 0; x < bw; x++) { sl += lum(row[x]); sr += lum(row[w - 1 - x]) }
        if (sl / bw < EDGE_LUM) darkL++
        if (sr / bw < EDGE_LUM) darkR++
    }
    double left = h > 0 ? darkL / (double) h : 0d, right = h > 0 ? darkR / (double) h : 0d
    return [left: left, right: right, flagged: (left > EDGE_ROWS || right > EDGE_ROWS)]
}
// QuPath ImageServer ile ana görüntünün en düşük çözünürlüklü (≤ ~1024 px) küçük resmi + kenar bandı skoru
def mainScanCheck = { File f, boolean keepThumb ->
    def srv = null
    try {
        srv = ImageServers.buildServer(f.toURI())
        double ds = Math.max(1d, Math.max(srv.getWidth(), srv.getHeight()) / (double) EDGE_THUMB)
        BufferedImage img = null
        try { img = srv.readRegion(qupath.lib.regions.RegionRequest.createInstance(srv.getPath(), ds, 0, 0, srv.getWidth(), srv.getHeight())) } catch (Throwable ignore) {}
        if (img == null) img = srv.getDefaultThumbnail(0, 0)
        if (img == null) return [ok: false]
        img = scaleMax(img, EDGE_THUMB)
        def sc = edgeBandScore(img)
        sc.ok = true
        if (keepThumb) sc.thumb = thumbOf(img, 300)
        return sc
    } catch (Throwable t) {
        return [ok: false, error: (t.getMessage() ?: t.getClass().getSimpleName())]
    } finally {
        try { srv?.close() } catch (Throwable ignore) {}
    }
}
// Sonuç: label [state: YOK|ANON|ANON_DEGIL|SENTETIK_COZULEMEDI|FOTOGRAF|OKUNAMADI, code, bw],
//        macro [state: YOK|BOS|ICERIK|OKUNAMADI], sidecar RequestId, küçük resimler (yalnız arayüz için)
def assocCheck = { File f, Closure decoder, boolean keepThumbs, File saveDir ->
    def res = [label: [state: 'YOK'], macro: [state: 'YOK'], opened: false]
    def readAssoc = { String kind ->
        def tryServer = { srv ->
            String nm = srv.getAssociatedImageList()?.find { it.toLowerCase(java.util.Locale.ROOT).contains(kind) }
            if (nm == null) return [found: false]
            try { return [found: true, img: (BufferedImage) srv.getAssociatedImage(nm)] } catch (Throwable t) { return [found: true, img: null] }
        }
        def srv = null
        try {
            srv = ImageServers.buildServer(f.toURI()); res.opened = true
            def r = tryServer(srv)
            if (!r.found || r.img != null) return r
        } catch (Throwable ignore) {
        } finally { try { srv?.close() } catch (Throwable ignore) {} }
        def bf = bioFormatsBuilder(); def srv2 = null
        try {
            if (bf == null) return [found: true, img: null]
            srv2 = bf.buildServer(f.toURI()); res.opened = true
            return tryServer(srv2)
        } catch (Throwable t) { return [found: true, img: null] }
        finally { try { srv2?.close() } catch (Throwable ignore) {} }
    }
    def lab = readAssoc('label')
    if (lab.found && lab.img == null) res.label = [state: 'OKUNAMADI']
    else if (lab.found) {
        BufferedImage img = lab.img
        int w = img.getWidth(), h = img.getHeight(); int[] px = img.getRGB(0, 0, w, h, null, 0, w)
        int bwn = 0
        for (int p : px) { double m = lum(p); if (m < 40d || m > 215d) bwn++ }
        double bw = bwn / (double) px.length
        String text = (decoder != null) ? decoder(img) : null
        if (text != null && text.startsWith('ANON')) res.label = [state: 'ANON', code: text, bw: bw]
        else if (text != null) res.label = [state: 'ANON_DEGIL', bw: bw]            // metin GÖSTERİLMEZ
        else res.label = [state: (bw >= 0.95d ? 'SENTETIK_COZULEMEDI' : 'FOTOGRAF'), bw: bw]
        if (keepThumbs) res.labelThumb = thumbOf(img, 300)
        if (saveDir != null) { saveDir.mkdirs(); javax.imageio.ImageIO.write(img, 'png', new File(saveDir, f.getName() + '__etiket.png')) }
    }
    def mac = readAssoc('macro')
    if (mac.found && mac.img == null) res.macro = [state: 'OKUNAMADI']
    else if (mac.found) {
        BufferedImage img = mac.img
        int w = img.getWidth(), h = img.getHeight(); int[] px = img.getRGB(0, 0, w, h, null, 0, w)
        double s = 0d, s2 = 0d
        for (int p : px) { double m = lum(p); s += m; s2 += m * m }
        double mean = s / px.length, sd = Math.sqrt(Math.max(0d, s2 / px.length - mean * mean))
        res.macro = [state: (sd < 3d && (mean > 245d || mean < 10d)) ? 'BOS' : 'ICERIK', mean: mean, sd: sd]
        if (keepThumbs) res.macroThumb = thumbOf(img, 300)
        if (saveDir != null) { saveDir.mkdirs(); javax.imageio.ImageIO.write(img, 'png', new File(saveDir, f.getName() + '__makro.png')) }
    }
    // Sectra yan dosyası: <slayt>.import (JSON; RequestId/ExamId)
    File side = new File(f.getParentFile(), f.getName() + '.import')
    if (side.isFile()) {
        try {
            def m = qupath.lib.io.GsonTools.getInstance().fromJson(side.getText('UTF-8'), Map.class)
            res.sidecar = [requestId: (m?.get('RequestId') ?: '').toString(), examId: (m?.get('ExamId') ?: '').toString()]
        } catch (Throwable t) { res.sidecar = [error: true] }
    }
    return res
}

// ── Bayt taraması: dosyanın TAMAMINDA arama dizileri (ISO-8859-1, String.indexOf hızlı yolu) ─
def scanForProbes = { File f, Collection probes, AtomicBoolean cancel ->
    def ps = new ArrayList(new LinkedHashSet(probes.findAll { it != null && it.length() >= MIN_PROBE }))
    def hits = new LinkedHashMap()
    if (ps.isEmpty()) return hits
    int maxLen = ps.collect { it.length() }.max()
    int CHUNK = 8 << 20
    byte[] buf = new byte[CHUNK + maxLen]
    def ins = new FileInputStream(f)
    try {
        int carry = 0
        while (true) {
            if (cancel != null && cancel.get()) break
            int n = ins.read(buf, carry, CHUNK)
            if (n < 0) break
            int total = carry + n
            String s = new String(buf, 0, total, ISO)
            for (p in ps) {
                int idx = s.indexOf((String) p)
                while (idx >= 0) {
                    if (idx + p.length() > carry) hits.put(p, ((hits.get(p) ?: 0) as int) + 1)
                    idx = s.indexOf((String) p, idx + 1)
                }
            }
            int keep = Math.min(maxLen - 1, total)
            System.arraycopy(buf, total - keep, buf, 0, keep)
            carry = keep
        }
    } finally {
        ins.close()
    }
    return hits
}

// ── Kopya üzerinde düzenleme (orijinale ASLA yazılmaz) ────────────────────────
// Bir sayfanın "sahip olduğu" tüm baytlar: IFD bloğu (sayı + girdiler + sonraki işaretçisi), satır dışı etiket
// değerleri (ImageDescription, StripOffsets/ByteCounts dizileri …) ve şerit/karo verisi. Aralıklar [başlangıç,
// bitiş) biçimindedir. Belirlenemeyen / dosya dışına taşan aralık → IOException (belirsizlik güvenli tarafta
// çözülür: dosya REDDEDİLİR).
def ownedRanges = { RandomAccessFile raf, Map t, Map ifd ->
    int ptrSize = t.big ? 8 : 4
    long len = t.length
    def out = []
    long hdr = t.big ? 16L : 8L
    long blockEnd = ifd.nextPtrPos + ptrSize
    if (ifd.offset < hdr || blockEnd > len) throw new java.io.IOException('IFD bloğu dosya sınırları dışında (ofset ' + ifd.offset + ')')
    out << [ifd.offset, blockEnd, 'IFD bloğu']
    ifd.entries.values().each { e ->
        if (e.size > ptrSize) {
            if (e.dataPos < hdr || e.dataPos + e.size > len || e.size < 0L)
                throw new java.io.IOException('Etiket ' + e.tag + ' değeri dosya sınırları dışında')
            out << [e.dataPos, e.dataPos + e.size, 'etiket ' + e.tag + ' değeri']
        }
    }
    boolean strips = ifd.entries.containsKey(273)
    def offE = ifd.entries.get(strips ? 273 : 324)
    def cntE = ifd.entries.get(strips ? 279 : 325)
    if (offE != null || cntE != null) {
        if (offE == null || cntE == null) throw new java.io.IOException('Görüntü verisi aralığı belirlenemedi (eksik şerit/karo etiketi)')
        def offs = readUInts(raf, offE, t.le); def cnts = readUInts(raf, cntE, t.le)
        if (offs.size() != offE.count || cnts.size() != cntE.count || offs.size() != cnts.size())
            throw new java.io.IOException('Görüntü verisi aralığı belirlenemedi (ofset/bayt sayısı dizileri okunamadı)')
        for (int j = 0; j < offs.size(); j++) {
            long pos = offs[j]; long n = cnts[j]
            if (n == 0L) continue
            if (pos < hdr || n < 0L || pos + n > len) throw new java.io.IOException('Görüntü verisi dosya sınırları dışında')
            out << [pos, pos + n, 'görüntü verisi']
        }
    }
    return out
}
def mergeRanges = { Collection ranges ->
    def sorted = new ArrayList(ranges)
    sorted.sort { a, b -> a[0] <=> b[0] }
    def merged = []
    sorted.each { r ->
        def last = merged.isEmpty() ? null : merged[merged.size() - 1]
        if (last != null && r[0] <= last[1]) { if (r[1] > last[1]) last[1] = r[1] }
        else merged << [r[0], r[1]]
    }
    return merged
}
// merged: ayrık + sıralı. [s,e) ile kesişen bir aralık var mı?
def overlapsMerged = { List merged, long s, long e ->
    int lo = 0, hi = merged.size() - 1, cand = -1
    while (lo <= hi) {
        int mid = (lo + hi) >>> 1
        if (merged[mid][0] < e) { cand = mid; lo = mid + 1 } else hi = mid - 1
    }
    return cand >= 0 && merged[cand][1] > s
}
// Sıfırlanması gereken aralıkların gerçekten sıfır olduğunu DOSYADAN geri okuyarak doğrular.
def verifyZeroed = { File f, Collection ranges ->
    def bad = new LinkedHashSet()
    def raf = new RandomAccessFile(f, 'r')
    try {
        byte[] buf = new byte[65536]
        ranges.each { rg ->
            long pos = rg[0]; long left = rg[1] - rg[0]
            boolean dirty = false
            while (left > 0L && !dirty) {
                int w = (int) Math.min(left, (long) buf.length)
                raf.seek(pos); raf.readFully(buf, 0, w)
                for (int k = 0; k < w; k++) if (buf[k] != (byte) 0) { dirty = true; break }
                pos += w; left -= w
            }
            if (dirty) bad.add(rg[2])
        }
    } finally {
        raf.close()
    }
    return new ArrayList(bad)
}
def editTiff = { File f, String kind, cfg, Collection stems ->
    def res = [values: [], keys: new LinkedHashSet(), removed: [], stemEdits: 0, zeroed: [], ifdCountBefore: 0]
    def raf = new RandomAccessFile(f, 'rw')
    try {
        def t = parseTiff(raf)
        if (t == null) throw new java.io.IOException('TIFF yapısı okunamadı')
        res.ifdCountBefore = t.ifds.size()
        def repl = stems.findAll { stemReplaceable(it) }
        if (cfg.redactMeta) {
            t.ifds.each { ifd ->
                ifd.entries.values().findAll { it.type == 2 }.each { e ->
                    String s = readAscii(raf, e)
                    if (s == null) return
                    String ns = s
                    if (e.tag == 270 && kind == 'aperio') {
                        def r = redactAperioDesc(ns)
                        ns = r.text; res.values.addAll(r.values); res.keys.addAll(r.keys)
                    }
                    if (STD_PHI_TAGS.containsKey(e.tag)) {
                        String b = blankAscii(ns, e.tag == 306)
                        if (b != ns) { ns = b; res.keys.add(STD_PHI_TAGS.get(e.tag)) }
                    }
                    repl.each { st ->
                        if (ns.contains(st)) { ns = ns.replace(st, 'X' * st.length()); res.stemEdits++ }
                    }
                    if (ns != s) writeAscii(raf, e, ns)
                }
            }
        }
        if (kind == 'aperio' && cfg.removeLabels && t.ifds.size() > 1) {
            // 1) Kaldırılacak sayfalar (indeks 0 ASLA kaldırılmaz)
            def removeIdx = []; def kinds = [:]
            for (int i = 1; i < t.ifds.size(); i++) {
                String ak = assocKind(readAscii(raf, t.ifds[i].entries.get(270)))
                if (ak != null) { removeIdx << i; kinds[i] = ak }
            }
            if (!removeIdx.isEmpty()) {
                // 2) Sıfırlanacak her bayt: IFD bloğu + satır dışı değerler (ImageDescription dahil) + piksel verisi.
                //    Bağlantısı kesilen IFD sayfası da, tablosu ve açıklaması da dosyada YETİM bayt olarak kalırdı
                //    (GT450'de açıklama boru ayraçlı üst veri bloğunu taşıyabilir).
                def zero = []
                removeIdx.each { i ->
                    def ifd = t.ifds[i]
                    SUB_IFD_TAGS.each { tg ->
                        if (ifd.entries.containsKey(tg)) throw new java.io.IOException('Etiket sayfası alt-IFD içeriyor (etiket ' + tg + ') — dosya reddedildi')
                    }
                    ownedRanges(raf, t, ifd).each { r -> zero << [r[0], r[1], kinds[i] + ' ' + r[2]] }
                }
                // 3) Korunan sayfaların sahip olduğu baytlarla ÇAKIŞMA denetimi — çakışırsa HİÇBİR ŞEY yazılmaz
                def kept = [[0L, t.big ? 16L : 8L]]
                def removeSet = removeIdx as Set
                for (int i = 0; i < t.ifds.size(); i++) {
                    if (removeSet.contains(i)) continue
                    ownedRanges(raf, t, t.ifds[i]).each { r -> kept << [r[0], r[1]] }
                }
                def merged = mergeRanges(kept)
                zero.each { z ->
                    if (overlapsMerged(merged, z[0], z[1]))
                        throw new java.io.IOException('Sıfırlanacak aralık (' + z[2] + ') korunan bir sayfanın baytlarıyla çakışıyor — dosya reddedildi')
                }
                // 4) Zincir: korunan sayfalar sırayla bağlanır (kaldırılan sayfa sonda olsa da ortada olsa da)
                def keptIfds = t.ifds.findAll { !removeSet.contains(it.index) }
                for (int k = 0; k < keptIfds.size(); k++) {
                    long target = (k + 1 < keptIfds.size()) ? keptIfds[k + 1].offset : 0L
                    if (keptIfds[k].next != target) {
                        writePtr(raf, keptIfds[k].nextPtrPos, target, t.le, t.big)
                        keptIfds[k].next = target
                    }
                }
                // 5) Sıfırlama
                byte[] z0 = new byte[65536]
                zero.each { z ->
                    long pos = z[0]; long left = z[1] - z[0]
                    while (left > 0L) {
                        int w = (int) Math.min(left, (long) z0.length)
                        raf.seek(pos); raf.write(z0, 0, w)
                        pos += w; left -= w
                    }
                }
                res.zeroed = zero
                removeIdx.each { i -> res.removed << kinds[i] }
            }
        }
    } finally {
        raf.close()
    }
    return res
}

// ── Kaynak parmak izi (orijinallerin değişmediğinin kanıtı) ───────────────────
// Boyut + değiştirilme zamanı + ilk ve son 16 MB'ın SHA-256'sı (TIFF başlığı, IFD'ler, açıklamalar ve
// etiket/makro şeritleri dosyanın başında/sonunda durur). Salt okunur açılır.
def fingerprintOf = { File f ->
    def raf = new RandomAccessFile(f, 'r')
    try {
        long len = raf.length()
        def digest = { long from, long n ->
            def md = java.security.MessageDigest.getInstance('SHA-256')
            byte[] buf = new byte[1 << 20]
            raf.seek(from)
            long left = n
            while (left > 0L) {
                int r = raf.read(buf, 0, (int) Math.min(left, (long) buf.length))
                if (r < 0) break
                md.update(buf, 0, r); left -= r
            }
            return md.digest().collect { String.format(java.util.Locale.US, '%02x', it & 0xff) }.join('')
        }
        long head = Math.min(len, FP_BLOCK)
        long tailStart = Math.max(0L, len - FP_BLOCK)
        return [size: len, mtime: f.lastModified(), head: digest(0L, head), tail: digest(tailStart, len - tailStart)]
    } finally {
        raf.close()
    }
}
def fpEqual = { a, b -> a != null && b != null && a.size == b.size && a.mtime == b.mtime && a.head == b.head && a.tail == b.tail }
def fpString = { fp -> fp == null ? '' : ('size=' + fp.size + ';mtime=' + fp.mtime + ';head=' + fp.head + ';tail=' + fp.tail) }

// ── Kayıt (accession) numarası deseni — yeniden düzenleme SONRASI kopyada kalan her ASCII değer ──
def compileAccession = { String p ->
    String src = (p != null && !p.trim().isEmpty()) ? p.trim() : ACCESSION_DEFAULT
    try { return java.util.regex.Pattern.compile(src) }
    catch (java.util.regex.PatternSyntaxException e) { throw new IllegalArgumentException('Geçersiz kayıt numarası deseni: ' + e.getDescription()) }
}
// Değer ASLA yazılmaz: yalnız şekil (rakam → #, harf → a) — ör. "7-25" → "#-##"
def maskHit = { String s -> (s ?: '').replaceAll('[0-9]', '#').replaceAll('[A-Za-z]', 'a') }
// Bağlantılı HER IFD'nin HER ASCII etiket değerini tarar; ISO tarihleriyle örtüşen eşleşmeler yok sayılır.
// Sonuç: [[page: IFD indeksi, tag: etiket, masked: maskeli şekil], …]
// Kullanıcı desenine zaman/iptal sınırı: metin, charAt içinde iptal bayrağını ve bir süre sınırını denetleyen bir
// CharSequence ile sarılır (felaket geri izlemeli desen takılıp kalamaz); değer başına taranan uzunluk sınırlıdır.
// lim: [cancel: AtomicBoolean, budgetMs: dosya başına toplam süre (varsayılan 10000), maxLen: değer başına (65536)].
// Süre aşımı → IllegalStateException('ZAMANASIMI') — çağıran birimi İNCELE yapar (asla TAMAM değil).
def accessionHits = { File f, java.util.regex.Pattern pat, Map lim = [:] ->
    def hits = []
    long deadline = System.nanoTime() + ((lim.budgetMs ?: 10000L) as long) * 1000000L
    int maxLen = (lim.maxLen ?: 65536) as int
    def cancelFlag = lim.cancel
    int[] tick = [0] as int[]
    def guard = { String text ->
        return ([length  : { -> text.length() },
                 charAt   : { int i ->
                     if ((++tick[0] & 1023) == 0) {
                         if (cancelFlag != null && cancelFlag.get()) throw new IllegalStateException('IPTAL')    // denetlenen istisna Proxy'de sarılırdı
                         if (System.nanoTime() > deadline) throw new IllegalStateException('ZAMANASIMI')
                     }
                     text.charAt(i)
                 },
                 subSequence: { int a, int b -> text.substring(a, b) },
                 toString : { -> text }] as CharSequence)
    }
    def raf = new RandomAccessFile(f, 'r')
    try {
        def t = parseTiff(raf)
        if (t == null) return hits
        def isoPat = java.util.regex.Pattern.compile(ISO_DATE_RE)
        t.ifds.each { ifd ->
            ifd.entries.values().findAll { it.type == 2 }.each { e ->
                String s = readAscii(raf, e)
                if (s == null) return
                s = s.replace('\u0000', ' ')
                // Never report a clean scan when a bounded value was not fully examined.
                if (s.length() > maxLen) throw new IllegalStateException('ZAMANASIMI')
                def isoSpans = []
                def im = isoPat.matcher(s)
                while (im.find()) isoSpans << [im.start(), im.end()]
                def m = pat.matcher(guard(s))
                while (m.find()) {
                    int a = m.start(), b = m.end()
                    if (isoSpans.any { sp -> a < sp[1] && b > sp[0] }) continue
                    hits << [page: ifd.index, tag: e.tag, masked: maskHit(m.group())]
                }
            }
        }
    } finally {
        raf.close()
    }
    return hits
}

// ── Zaman damgası sabitleme (paylaşılabilir dosyalar) ─────────────────────────
// Kopyalar çalıştırmanın zamanını taşır; sıralı adlandırma + yazma sırası özgün sırayı (çoğu kez kabul
// sırası) sızdırır. Değiştirilme + OLUŞTURMA + erişim zamanı tek bir sabit değere çekilir. Java oluşturma
// zamanını Windows/NTFS'te yazar; yazamayan dosya sistemlerinde sonuç false döner (çağıran günlüğe yazar).
def pinTimes = { File f ->
    def res = [mtime: false, creation: false]
    try {
        def ft = java.nio.file.attribute.FileTime.from(java.time.Instant.parse(PIN_ISO))
        def p = f.toPath()
        def v = Files.getFileAttributeView(p, java.nio.file.attribute.BasicFileAttributeView.class)
        v.setTimes(ft, ft, ft)
        def at = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class)
        res.mtime = (at.lastModifiedTime() == ft)
        res.creation = (at.creationTime() == ft)
    } catch (Throwable t) {
        res.error = (t.getMessage() ?: t.getClass().getSimpleName())
    }
    return res
}

// ── Yapısal denetim (salt okunur): hem DENETLE kipi hem de yazım sonrası doğrulama kullanır ─
def inspectTiff = { File f, String kind, Collection stems ->
    def out = [ok: true, phi: new LinkedHashMap(), assocIfds: [], std: new LinkedHashSet(), stemHit: false]
    def raf = null
    try {
        raf = new RandomAccessFile(f, 'r')
        def t = parseTiff(raf)
        if (t == null) { out.ok = false; out.error = 'TIFF yapısı okunamadı'; return out }
        out.ifdCount = t.ifds.size(); out.big = t.big
        t.ifds.each { ifd ->
            String d = readAscii(raf, ifd.entries.get(270))
            if (kind == 'aperio' && d != null) {
                redactAperioDesc(d).keys.each { k ->
                    def set = out.phi.get(k)
                    if (set == null) { set = new TreeSet(); out.phi.put(k, set) }
                    set.add(ifd.index)
                }
                String ak = assocKind(d)
                if (ak != null && ifd.index > 0) out.assocIfds << (ak + ' (IFD ' + ifd.index + ')')
            }
            STD_PHI_TAGS.each { tag, nm ->
                String s = readAscii(raf, ifd.entries.get(tag))
                if (s != null && !isBlankValue(s.replace('\u0000', ''))) out.std.add(nm)
            }
            if (!stems.isEmpty()) {
                ifd.entries.values().findAll { it.type == 2 }.each { e ->
                    String s = readAscii(raf, e)
                    if (s != null && stems.any { st -> st && st.length() >= 4 && s.contains(st) }) out.stemHit = true
                }
            }
        }
    } catch (Throwable t2) {
        out.ok = false; out.error = (t2.getMessage() ?: t2.getClass().getSimpleName())
    } finally {
        try { raf?.close() } catch (Throwable ignore) {}
    }
    return out
}

// ── Kapsamdaki öğeler ────────────────────────────────────────────────────────
def describeEntry = { entry ->
    def b = null
    try { b = entry.getServerBuilder() } catch (Throwable ignore) {}
    def uris = []
    try { if (b != null) uris = new ArrayList(b.getURIs() ?: []) } catch (Throwable ignore) {}
    File f = (uris.size() == 1) ? localFile(uris[0]) : null
    return [entry: entry, id: entry.getID(), name: (entry.getImageName() ?: (f?.getName() ?: 'görüntü')),
            builder: b, uris: uris, file: f,
            plain: (b instanceof ImageServerBuilder.DefaultImageServerBuilder) && uris.size() == 1]
}
def describeOpenImage = { imageData, project ->
    def entry = null
    try { entry = project?.getEntry(imageData) } catch (Throwable ignore) {}
    if (entry != null) return describeEntry(entry)
    def server = imageData.getServer()
    def uris = []
    try { uris = new ArrayList(server.getURIs() ?: []) } catch (Throwable ignore) {}
    File f = (uris.size() == 1) ? localFile(uris[0]) : null
    return [entry: null, id: null, name: (f?.getName() ?: (server.getMetadata().getName() ?: 'görüntü')),
            builder: null, uris: uris, file: f, plain: false]
}
def collectItems = { String scope, Set chosenIds, project, imageData ->
    if (scope == 'acik') return imageData != null ? [describeOpenImage(imageData, project)] : []
    if (project == null) return []
    return project.getImageList().findAll { chosenIds == null || chosenIds.contains(it.getID()) }.collect { describeEntry(it) }
}
// Aynı dosyaya bakan birden çok giriş (ör. kırpılmış girişler) → tek "birim"; dosya bir kez işlenir
def groupUnits = { List items ->
    def byKey = new LinkedHashMap()
    items.eachWithIndex { it, int i ->
        String key = (it.file != null) ? it.file.getCanonicalPath() : ('#' + i)
        def u = byKey.get(key)
        if (u == null) {
            u = [file: it.file, uris: it.uris, kind: classifyFile(it.file, it.uris), items: []]
            byKey.put(key, u)
        }
        u.items << it
    }
    return new ArrayList(byKey.values())
}
def stemsOf = { u ->
    def s = new LinkedHashSet()
    if (u.file != null) s.add(latin1Form(stemOf(u.file.getName())))
    u.items.each { it -> if (it.name) s.add(latin1Form(stemOf(it.name))) }
    return s.findAll { it }
}

// ═════════════════════════════════════════════════════════════════════════════
// DENETLE (salt okunur)
// ═════════════════════════════════════════════════════════════════════════════
// opts: decodeLabels (ZXing indir/kullan), keepThumbs (arayüz galerisi), saveDir (etiket/makro PNG klasörü)
def runAudit = { List items, Closure log, AtomicBoolean cancel, Map opts = [:] ->
    def units = groupUnits(items)
    def counts = ['TEMİZ': 0, 'EKSİK': 0, 'BELİRSİZ': 0, 'ATLANDI': 0]
    def gallery = []
    def edgeNames = []
    def decoder = null
    if (opts.decodeLabels && !(cancel != null && cancel.get())) {
        try { decoder = makeDecoder(ensureZxing(true, log)) } catch (Throwable t) { log('Barkod çözücü yüklenemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName())) }
    }
    def sb = new StringBuilder()
    sb << 'ANONİMLİK DENETİMİ (salt okunur)\n'
    sb << '════════════════════════════════\n'
    sb << 'Etiket barkodu (DataMatrix): ' << (decoder != null ? 'çözülüyor (ZXing)' : (opts.decodeLabels ? 'ÇÖZÜCÜ YOK — çevrimdışı; etiketler yalnız görsel ölçütle değerlendirildi' : 'kapalı')) << '\n\n'
    int i = 0
    for (u in units) {
        if (cancel != null && cancel.get()) { sb << '\n(İptal edildi)\n'; break }
        i++
        String fname = u.file?.getName() ?: (u.items[0].name ?: '?')
        log(String.format(java.util.Locale.US, '[%d/%d] %s', i, units.size(), fname))
        def findings = []; def notes = []; def unsure = []
        String verdict
        if (u.kind in ['multi', 'missing', 'unreadable']) {
            verdict = 'ATLANDI'; notes << KIND_LABEL[u.kind]
        } else {
            def qp = qupathCheck(u.file)
            if (!qp.ok) unsure << 'QuPath açamadı: ' + qp.error
            def ac = assocCheck(u.file, decoder, (boolean) opts.keepThumbs, (File) opts.saveDir)
            switch (ac.label.state) {
                case 'ANON': notes << 'Etiket: Sectra anonim etiketi ✓ — "' + ac.label.code + '" (DataMatrix doğrulandı)'; break
                case 'ANON_DEGIL': findings << 'Etiket kodu çözüldü ama ANON ile BAŞLAMIYOR (içerik gösterilmez) — gözle kontrol edin'; break
                case 'SENTETIK_COZULEMEDI': unsure << 'Etiket sentetik çizim (siyah-beyaz) ama kodu okunamadı — gözle doğrulayın'; break
                case 'FOTOGRAF': findings << String.format(java.util.Locale.US, 'Etiket FOTOĞRAF gibi (siyah-beyaz oranı %%%.0f) — kimlik içerebilir', 100d * ac.label.bw); break
                case 'OKUNAMADI': unsure << 'Etiket görüntüsü var ama okunamadı — gözle doğrulayın'; break
                default: break
            }
            switch (ac.macro.state) {
                case 'BOS': notes << 'Makro: boş (tek renk) ✓'; break
                case 'ICERIK': findings << 'Makro görüntüsü içerik taşıyor (slaytın tamamı; etiket bölgesi görünebilir)'; break
                case 'OKUNAMADI': unsure << 'Makro görüntüsü var ama okunamadı — gözle doğrulayın'; break
                default: break
            }
            if (ac.sidecar != null) {
                String rid = ac.sidecar.requestId ?: ''
                String code = (ac.label.state == 'ANON') ? ac.label.code.split(';')[0] : null
                if (ac.sidecar.error) unsure << 'Sectra .import yan dosyası okunamadı'
                else if (!rid.startsWith('ANON')) findings << 'Sectra .import yan dosyasında RequestId ANON ile başlamıyor (içerik gösterilmez)'
                else if (code != null && rid != code) findings << 'Sectra .import RequestId etiketteki kodla uyuşmuyor'
                else notes << 'Sectra .import RequestId: ' + rid + (code != null ? ' (etiketle uyumlu ✓)' : '')
            }
            // Ana tarama alanı: en düşük çözünürlüklü küçük resim + kenar bandı skoru. Yalnız İNCELEME notudur;
            // TEMİZ/EKSİK/BELİRSİZ kararını DEĞİŞTİRMEZ.
            def ms = null
            if (opts.mainScan) {
                ms = mainScanCheck(u.file, (boolean) opts.keepThumbs)
                if (ms != null && ms.ok && ms.flagged) {
                    edgeNames << fname
                    notes << String.format(java.util.Locale.US, 'Tarama alanında etiket şeridi olabilir — gözle kontrol edin (sol %%%.0f · sağ %%%.0f koyu satır)', 100d * ms.left, 100d * ms.right)
                } else if (ms != null && !ms.ok) notes << 'Tarama kenarı denetlenemedi (ana görüntü okunamadı)'
            }
            if (opts.keepThumbs && gallery.size() < 60)
                gallery << [name: fname, label: ac.labelThumb, macro: ac.macroThumb, state: ac.label.state, macroState: ac.macro.state,
                            main: ms?.thumb, edgeFlagged: (ms != null && ms.ok && ms.flagged)]
            if (u.kind in ['aperio', 'tiff']) {
                def ins = inspectTiff(u.file, u.kind, stemsOf(u))
                if (!ins.ok) unsure << 'TIFF okunamadı: ' + ins.error
                else {
                    if (!ins.phi.isEmpty())
                        findings << 'Dolu kimlik alanları: ' + ins.phi.collect { k, v -> k + ' (IFD ' + v.join(',') + ')' }.join('; ')
                    if (!ins.std.isEmpty()) findings << 'Dolu standart TIFF etiketleri: ' + ins.std.join(', ')
                    if (ins.stemHit) findings << 'Dosya/giriş adı dosyanın içinde (ASCII etiket) geçiyor'
                    if (!ins.assocIfds.isEmpty() && !ac.opened)
                        unsure << 'Etiket/makro IFD var (görüntü açılamadı): ' + ins.assocIfds.join(', ')
                }
                if (u.kind == 'tiff') notes << 'ImageDescription biçimi tanınmadı (OME/üretici XML) — elle kontrol edin'
            } else {
                notes << 'Bu biçimin üst verisi dosya düzeyinde denetlenemiyor (yalnız QuPath ilişkili görüntü listesi)'
            }
            verdict = !findings.isEmpty() ? 'EKSİK' : ((!unsure.isEmpty() || u.kind != 'aperio') ? 'BELİRSİZ' : 'TEMİZ')
        }
        counts[verdict] = counts[verdict] + 1
        String mark = ['TEMİZ': '✓', 'EKSİK': '✗', 'BELİRSİZ': '?', 'ATLANDI': '–'][verdict]
        sb << String.format(java.util.Locale.US, '%s %-8s %s   [%s]%n', mark, verdict, fname, KIND_LABEL[u.kind])
        u.items.findAll { it.name && it.name != fname }.each { it -> sb << '      QuPath giriş adı: ' << it.name << '\n' }
        findings.each { sb << '      • ' << it << '\n' }
        unsure.each { sb << '      ? ' << it << '\n' }
        notes.each { sb << '      · ' << it << '\n' }
    }
    sb << String.format(java.util.Locale.US, '%nSonuç: TEMİZ %d · EKSİK %d · BELİRSİZ %d · ATLANDI %d (toplam %d dosya)%n',
        counts['TEMİZ'], counts['EKSİK'], counts['BELİRSİZ'], counts['ATLANDI'], units.size())
    if (opts.mainScan) {
        sb << '\nTarama alanı kenar şeridi (olası etiket; ana görüntü içinde — etiket/makro silme buna ULAŞMAZ): '
        if (edgeNames.isEmpty()) sb << 'şüpheli dosya yok (yine de gözle bakın).\n'
        else {
            sb << edgeNames.size() << ' dosya\n'
            edgeNames.each { sb << '   ⚑ ' << it << '\n' }
        }
    }
    sb << '\nOkuma kılavuzu:\n'
    sb << '  TEMİZ    = Aperio yapısal denetimi temiz: kimlik alanları boş; etiket yok ya da DataMatrix\'i\n'
    sb << '             "ANON" ile başlayan Sectra anonim etiketi; makro yok ya da boş.\n'
    sb << '  EKSİK    = en az bir PHI katmanı duruyor → "Anonimleştir" ile temiz kopya üretin.\n'
    sb << '  BELİRSİZ = tam denetlenemedi (genel TIFF/NDPI/CZI … ya da etiket/makro okunamadı) → gözle bakın.\n'
    sb << '⚠ Dosya adlarının ve QuPath giriş adlarının kimlik içerip içermediğini SİZ değerlendirin;\n'
    sb << '  bayt düzeyi tam tarama anonimleştirme sırasında her kopyada yapılır.\n'
    if (opts.saveDir != null) sb << '\nEtiket/makro görüntüleri kaydedildi: ' << ((File) opts.saveDir).getAbsolutePath() << '\n'
    sb << '⚠️ Yalnızca araştırma/eğitim amaçlıdır; klinik karar üretmez.'
    return [text: sb.toString(), counts: counts, gallery: gallery, edgeFlagged: edgeNames]
}

// ═════════════════════════════════════════════════════════════════════════════
// ANONİMLEŞTİR — plan + uygulama
// ═════════════════════════════════════════════════════════════════════════════
def tmpNameFor = { String anon ->
    int d = anon.lastIndexOf('.')
    return (d > 0) ? (anon.substring(0, d) + '.anonim-gecici' + anon.substring(d)) : (anon + '.anonim-gecici')
}
// ═════════════════════════════════════════════════════════════════════════════
// ANAHTAR KLASÖRÜ / TUZ / ÇAPRAZ TABLO
// Takma adların tekrarlanabilir olması için tuz (salt.bin) ve eşleştirme tablosu (anahtar.csv) ÇIKTI KLASÖRÜNÜN
// DIŞINDA, ayrı bir "anahtar klasöründe" tutulur. İçerik KİŞİSEL VERİDİR; paylaşılan klasöre asla girmez.
// ═════════════════════════════════════════════════════════════════════════════
def csvCell = { s -> '"' + ((s ?: '').toString().replaceAll('[\\r\\n]+', ' ').replace('"', '""')) + '"' }
def parseCsvLine = { String line ->
    def out = []; def cur = new StringBuilder(); boolean q = false
    for (int i = 0; i < line.length(); i++) {
        int c = line.charAt(i)
        if (q) {
            if (c == 34) {
                if (i + 1 < line.length() && line.charAt(i + 1) == (char) 34) { cur.append('"'); i++ } else q = false
            } else cur.append((char) c)
        } else if (c == 34) q = true
        else if (c == 44) { out << cur.toString(); cur.setLength(0) }
        else cur.append((char) c)
    }
    out << cur.toString()
    return out
}
def hexOf = { byte[] b -> b.collect { String.format(java.util.Locale.US, '%02x', it & 0xff) }.join('') }
// Takma ad = önek + '_' + HMAC-SHA256(anahtar = tuz, ileti = ÖZGÜN DOSYA ADI) onaltılı gösteriminin ilk 10 karakteri.
// Kararlı anahtar olarak yol DEĞİL dosya adı seçildi: aynı slayt başka klasöre taşınsa da aynı takma ad çıkar.
// Bedeli: iki farklı klasördeki aynı adlı iki ayrı dosya aynı takma adı alırdı → bir planda aynı ad iki kez
// geçerse hmac kipi REDDEDER. Çarpışma (farklı adlar, aynı 10 karakter) → 12, sonra 16 karaktere uzatılır;
// sessiz ek (a, b …) HİÇBİR ZAMAN eklenmez.
def hmacHex = { byte[] salt, String msg ->
    def mac = javax.crypto.Mac.getInstance('HmacSHA256')
    mac.init(new javax.crypto.spec.SecretKeySpec(salt, 'HmacSHA256'))
    return hexOf(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)))
}
// Karşılaştırma anahtarı: yolun VAR OLAN en derin atası toRealPath() ile çözülür (sembolik bağ / junction
// içinden dolanma yakalanır), kalan kısım eklenir; Windows ve macOS'ta büyük-küçük harf farkı yok sayılır.
def dirKey = { File d ->
    def cur = d.getAbsoluteFile().toPath().normalize()
    def rest = []
    while (cur != null && !Files.exists(cur)) { rest.add(0, cur.getFileName().toString()); cur = cur.getParent() }
    def real = (cur != null) ? cur.toRealPath() : d.getAbsoluteFile().toPath().getRoot()
    rest.each { real = real.resolve((String) it) }
    String s = real.normalize().toString()
    if (!s.endsWith(File.separator)) s += File.separator
    String os = System.getProperty('os.name', '').toLowerCase(java.util.Locale.ROOT)
    return (os.contains('win') || os.contains('mac')) ? s.toLowerCase(java.util.Locale.ROOT) : s
}
// Denetim etiket/makro PNG'leri ÖZGÜN dosya adlarıyla yazılır → çıktı ya da anahtar klasörünün içine/üstüne yazılamaz
def assertSaveDirSafe = { File saveDir, Collection forbidden ->
    if (saveDir == null) return
    String s = dirKey(saveDir)
    forbidden.findAll { it != null }.each { File x ->
        String k = dirKey(x)
        if (s.startsWith(k) || k.startsWith(s))
            throw new IllegalArgumentException('Etiket/makro PNG klasörü çıktı ya da anahtar klasörünün içinde (ya da üstünde) olamaz: PNG\'ler özgün dosya adlarını taşır.')
    }
}
def assertKeyDirSafe = { File keyDir, File outDir ->
    String k = dirKey(keyDir), o = dirKey(outDir)
    if (k.startsWith(o) || o.startsWith(k))
        throw new IllegalArgumentException('Anahtar klasörü çıktı klasörünün İÇİNDE (ya da çıktı klasörü anahtar klasörünün içinde) olamaz: ' +
            'anahtar ve tuz kişisel veridir, paylaşılan klasöre sızar. Çıktı dışında ayrı bir anahtar klasörü seçin.')
}
def keyDirOf = { cfg ->
    String k = (cfg.keyDir ?: '').toString().trim()
    return k ? new File(k) : new File(atolyeDataRoot(), 'anonim-anahtar')
}
def saltNotice = { File keyDir ->
    '⚠ YENİ TUZ ÜRETİLDİ: ' + new File(keyDir, SALT_FILE).getAbsolutePath() + '\n' +
    '   Bu dosyayı (salt.bin, 32 bayt) ŞİMDİ güvenli bir yere YEDEKLEYİN (çıktı klasöründen ve paylaşılan yerlerden AYRI).\n' +
    '   Kaybederseniz sonraki partilerde aynı takma adlar yeniden üretilemez; eski çıktılarla eşleşme bozulur.\n' +
    '   Tuzu başkasıyla paylaşmayın: tuz + aday dosya adı ile takma ad doğrulanabilir.'
}
def loadSalt = { File keyDir, boolean create ->
    File sf = new File(keyDir, SALT_FILE)
    if (sf.exists()) {
        byte[] b = Files.readAllBytes(sf.toPath())
        if (b.length != 32) throw new IllegalStateException('salt.bin 32 bayt değil (' + b.length + ') — DOKUNULMADI; yedekten geri yükleyin ya da dosyayı inceleyin: ' + sf.getAbsolutePath())
        return [salt: b, created: false]
    }
    if (!create) return [salt: null, created: false]
    keyDir.mkdirs()
    byte[] b = new byte[32]
    new java.security.SecureRandom().nextBytes(b)
    Files.write(sf.toPath(), b, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
    try { Files.setPosixFilePermissions(sf.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString('rw-------')) } catch (Throwable ignore) {}
    return [salt: b, created: true]
}
// anahtar.csv yalnız EKLENİR (olay günlüğü); okuma: bir özgün adın İLK satırı takma adı belirler,
// sonraki satırlar aynı takma adı taşımak ZORUNDADIR; aynı takma ad iki farklı özgün ada verilemez.
def loadCrosswalk = { File keyDir ->
    def st = [byOrig: [:], usedBases: [:], rows: 0]
    File cf = new File(keyDir, CROSSWALK_FILE)
    if (!cf.isFile()) return st
    def lines = cf.getText('UTF-8').split('\r?\n').toList()
    if (lines.isEmpty() || lines[0].replace('﻿', '') != KEY_COLUMNS.join(','))
        throw new IllegalStateException('anahtar.csv başlığı tanınmadı — dosya DEĞİŞTİRİLMEDİ: ' + cf.getAbsolutePath())
    for (int i = 1; i < lines.size(); i++) {
        if (lines[i].trim().isEmpty()) continue
        def c = parseCsvLine(lines[i])
        if (c.size() < 6) throw new IllegalStateException('anahtar.csv satır ' + (i + 1) + ' bozuk')
        String name = c[0], pseud = c[2], mode = c[3]
        if (!pseud) continue
        st.rows++
        if (name) {
            def first = st.byOrig.get(name)
            if (first == null) st.byOrig.put(name, [pseudonym: pseud, mode: mode])
            else if (first.pseudonym != pseud) throw new IllegalStateException('anahtar.csv çelişkili: aynı özgün ad iki farklı takma adla kayıtlı (satır ' + (i + 1) + ')')
        }
        String key = pseud.toLowerCase(java.util.Locale.ROOT)
        String owner = st.usedBases.get(key)
        if (owner == null || owner.isEmpty()) st.usedBases.put(key, name)
        else if (name && owner != name) throw new IllegalStateException('anahtar.csv çelişkili: bir takma ad iki farklı özgün ada verilmiş (satır ' + (i + 1) + ')')
    }
    return st
}
def prepareKeyState = { cfg, File outDir, Closure log ->
    File kd = keyDirOf(cfg)
    assertKeyDirSafe(kd, outDir)
    kd.mkdirs()
    if (!kd.isDirectory()) throw new IllegalStateException('Anahtar klasörü oluşturulamadı: ' + kd.getAbsolutePath())
    def sl = loadSalt(kd, cfg.naming == 'hmac')
    def cw = loadCrosswalk(kd)
    if (sl.created) log(saltNotice(kd))
    return [dir: kd, salt: sl.salt, saltCreated: sl.created, byOrig: cw.byOrig, usedBases: cw.usedBases, rows: cw.rows]
}
def appendCrosswalk = { File keyDir, Collection rows ->
    if (rows.isEmpty()) return
    File cf = new File(keyDir, CROSSWALK_FILE)
    def ch = java.nio.channels.FileChannel.open(cf.toPath(), java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND, java.nio.file.StandardOpenOption.WRITE)
    try {
        def lock = ch.lock()
        try {
            def sb = new StringBuilder()
            if (ch.size() == 0L) sb.append(KEY_COLUMNS.join(',')).append('\n')
            rows.each { r ->
                sb.append([r.origName, r.origPath, r.pseudonym, r.mode, r.ts, r.status, r.fpBefore, r.fpAfter].collect { csvCell(it) }.join(',')).append('\n')
            }
            ch.write(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8)))
            ch.force(true)
        } finally { lock.release() }
    } finally { ch.close() }
}

def buildPlan = { cfg, List items, File outDir ->
    String prefix = cfg.prefix ?: 'case'
    if (prefix in ['.', '..'] || prefix.contains('/') || prefix.contains('\\'))
        throw new IllegalArgumentException('Önek bir dosya adı olmalıdır; klasör yolu olamaz.')
    String naming = cfg.naming ?: 'sequential'
    def ks = cfg.keyState
    def units = groupUnits(items)
    def usedBase = new HashSet<String>()
    def usedFile = new HashSet<String>()
    long bytes = 0L
    def proc = []
    units.each { u ->
        boolean partialKind = u.kind in ['tiff', 'ndpi', 'other']
        u.process = (u.kind == 'aperio') || (cfg.includePartial && partialKind)
        if (!u.process) {
            u.skipReason = (u.kind in ['multi', 'missing', 'unreadable']) ? KIND_LABEL[u.kind]
                : 'Tam anonimleştirilemeyen biçim (kısmi biçimler kapalı)'
            return
        }
        proc << u
    }
    // Anahtar tablosu özgün DOSYA ADINI anahtar alır: geri alınabilir çalıştırmada aynı ad iki farklı dosyada geçerse
    // iki farklı takma ad aynı ada bağlanır ve (eklemeli) tablo kalıcı olarak çelişkili olurdu → baştan reddet.
    if (cfg.reversible && ks != null) {
        def seenNames = new HashSet<String>()
        proc.each { u ->
            if (!seenNames.add(u.file.getName()))
                throw new IllegalArgumentException('Aynı dosya adı birden çok dosyada geçiyor (geri alınabilir mod özgün adı anahtar tablosunda tutar): ' +
                    'dosyaları ayrı partilere bölün ya da geri alınamaz modu seçin.')
        }
    }
    // Numaralama sırası özgün (proje-giriş / kabul) sırasını YANSITMAZ: tuz varsa tuzlu-HMAC sırası, yoksa
    // SecureRandom karıştırması. Sıralı adlar + yazma sırası aksi hâlde özgün sırayı (çoğu kez kabul numarası
    // sırasını) sızdırırdı.
    if (naming == 'hmac') {
        if (ks == null || ks.salt == null) throw new IllegalStateException('hmac adlandırması için tuz (salt.bin) gerekli')
        def seen = new HashSet<String>()
        proc.each { u ->
            if (!seen.add(u.file.getName().toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException('hmac kipi dosya ADINA dayanır; aynı ad birden çok dosyada geçiyor: bu plan reddedildi (adlandırmayı değiştirin ya da dosyaları ayrı partilere bölün).')
            u.hmacFull = hmacHex(ks.salt, u.file.getName())
        }
        proc.sort { a, b -> a.hmacFull <=> b.hmacFull }
    } else if (naming == 'sequential') {
        if (ks != null && ks.salt != null) {
            proc.each { u -> u.orderKey = hmacHex(ks.salt, u.file.getName()) }
            proc.sort { a, b -> a.orderKey <=> b.orderKey }
        } else {
            Collections.shuffle(proc, new java.security.SecureRandom())
        }
    }
    int counter = 1
    // Çarpışma = bu çalıştırmada ya da anahtar tablosunda BAŞKA bir özgün adın takma adı. hmac'te tablodaki sahibi
    // bilinmeyen (geri alınamaz çalıştırma: özgün ad yazılmaz) ya da aynı dosya adı olan kayıt çarpışma SAYILMAZ —
    // yoksa ikinci geri alınamaz çalıştırmada aynı girdi 12, sonra 16 karakter alırdı. (Bedeli: geri alınamaz modda
    // partiler ARASI gerçek çarpışma tespit edilemez; parti içi edilir.)
    def taken = { String cand, String who ->
        String lc = cand.toLowerCase(java.util.Locale.ROOT)
        if (usedBase.contains(lc)) return true
        if (ks == null || !ks.usedBases.containsKey(lc)) return false
        if (naming == 'hmac') {
            String owner = ks.usedBases.get(lc)
            return owner != null && !owner.isEmpty() && owner != who
        }
        return true
    }
    proc.each { u ->
        String ext = extOf(u.file) ? ('.' + extOf(u.file)) : ''
        def prior = (ks != null) ? ks.byOrig.get(u.file.getName()) : null     // yalnız geri alınabilir çalıştırmalarda dolu
        String base
        u.reused = false
        if (prior != null) {
            if (naming == 'hmac' && prior.mode == 'hmac') {
                String hexPart = prior.pseudonym.substring(prior.pseudonym.lastIndexOf('_') + 1)
                if (!(hexPart.length() in [10, 12, 16]) || !u.hmacFull.startsWith(hexPart))
                    throw new IllegalStateException('HMAC uyuşmazlığı: anahtar tablosundaki takma ad bu tuzla üretilen değerle örtüşmüyor (tuz değişmiş ya da tablo bozulmuş olabilir). ' +
                        'Sessizce yeni takma ad verilmedi; salt.bin yedeğini geri yükleyin.')
            }
            base = prior.pseudonym; u.reused = true
        } else if (naming == 'hmac') {
            base = null
            for (int len : [10, 12, 16]) {
                String cand = prefix + '_' + u.hmacFull.substring(0, len)
                if (!taken(cand, u.file.getName())) { base = cand; break }
            }
            if (base == null) throw new IllegalStateException('Takma ad çarpışması 16 karakterde de çözülemedi')
        } else if (naming == 'uuid') {
            base = prefix + '_' + UUID.randomUUID().toString()
        } else {
            while (taken(prefix + '_' + counter, u.file.getName())) counter++
            base = prefix + '_' + (counter++)
        }
        String anon = base + ext
        if (u.reused || naming == 'hmac') {
            // Tekrarlanabilir takma adlara sessiz ek ('a', 'b' …) EKLENMEZ: hedef doluysa dosya atlanır.
            if (usedFile.contains(anon.toLowerCase(java.util.Locale.ROOT)) || new File(outDir, anon).exists()) {
                u.process = false
                u.skipReason = 'Çıktı klasöründe aynı takma adlı dosya zaten var (üzerine yazılmaz): ' + anon
                return
            }
        } else {
            char sfx = 'a'
            String b0 = base
            while (usedFile.contains(anon.toLowerCase(java.util.Locale.ROOT)) || new File(outDir, anon).exists()) {
                base = b0 + (sfx as String)
                anon = base + ext
                sfx = (char) (sfx + 1)
            }
        }
        usedBase.add(base.toLowerCase(java.util.Locale.ROOT))
        usedFile.add(anon.toLowerCase(java.util.Locale.ROOT))
        u.base = base
        u.anon = anon
        bytes += u.file.length()
    }
    // Plan sırası = takma ad sırası (rapor, proje girişleri ve YAZMA sırası özgün sırayı göstermesin)
    def done = units.findAll { it.process && it.anon }
    done.sort { a, b -> a.anon.length() <=> b.anon.length() ?: a.anon <=> b.anon }
    def rest = units.findAll { !(it.process && it.anon) }
    return [units: done + rest, outDir: outDir, cfg: cfg, bytes: bytes]
}

def moveWithRetry = { File a, File b ->
    Throwable last = null
    for (int k = 0; k < 6; k++) {
        try { Files.move(a.toPath(), b.toPath()); return }
        catch (java.nio.file.FileAlreadyExistsException e) { throw e }
        catch (Throwable t) { last = t; System.gc(); Thread.sleep(400L * (k + 1)) }
    }
    throw last
}

// Tek birim (çekirdek): geçici kopya → düzenle → doğrula → anonim ada taşı (başarısızsa geçici silinir)
def processUnitCore = { u, cfg, File outDir, Closure log, AtomicBoolean cancel ->
    File tmp = null
    File fin = new File(outDir, u.anon)
    def stems = stemsOf(u)
    try {
        if (cancel.get()) throw new InterruptedException('iptal')
        if (fin.getCanonicalFile().getParentFile() != outDir.getCanonicalFile() || fin.getCanonicalFile() == u.file.getCanonicalFile())
            throw new IllegalArgumentException('Geçersiz çıktı yolu')
        // Own a unique temporary file; never delete a pre-existing user's file.
        tmp = File.createTempFile('atolye-anon-', '.' + extOf(u.file), outDir)
        def orig = qupathCheck(u.file)
        log('    kopyalanıyor (' + fmtBytes(u.file.length()) + ') …')
        Files.copy(u.file.toPath(), tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)            // öznitelik (tarih) KOPYALANMAZ
        if (cancel.get()) throw new InterruptedException('iptal')
        def ed = [values: [], keys: [], removed: [], stemEdits: 0, zeroed: [], ifdCountBefore: 0]
        if (u.kind in ['aperio', 'tiff']) {
            log('    üst veri / etiket düzenleniyor …')
            ed = editTiff(tmp, u.kind, cfg, stems)
        }
        log('    doğrulanıyor (IFD + sıfırlama + bayt taraması + desen + QuPath) …')
        def issues = []; def notes = []; def review = []
        if (u.kind in ['aperio', 'tiff']) {
            def ins = inspectTiff(tmp, u.kind, [])
            if (!ins.ok) issues << 'TIFF yeniden okunamadı: ' + ins.error
            else {
                if (cfg.redactMeta && !ins.phi.isEmpty()) issues << 'Kimlik alanı kaldı: ' + ins.phi.keySet().join(', ')
                if (cfg.redactMeta && !ins.std.isEmpty()) issues << 'Standart etiket kaldı: ' + ins.std.join(', ')
                if (u.kind == 'aperio' && cfg.removeLabels && !ins.assocIfds.isEmpty()) issues << 'Etiket/makro IFD kaldı: ' + ins.assocIfds.join(', ')
                if (u.kind == 'aperio' && cfg.removeLabels && !ed.removed.isEmpty() && ins.ifdCount != ed.ifdCountBefore - ed.removed.size())
                    issues << 'IFD sayısı beklenenden farklı'
            }
            // Bağlantısı kesilen sayfanın IFD bloğu + satır dışı değerleri + piksel verisi GERÇEKTEN sıfır mı?
            if (u.kind == 'aperio' && cfg.removeLabels && !ed.zeroed.isEmpty()) {
                def zbad = verifyZeroed(tmp, ed.zeroed)
                if (!zbad.isEmpty()) issues << 'Etiket/makro baytları sıfırlanmadı: ' + zbad.join(', ')
            }
            // Düzenleme SONRASI kayıt numarası deseni taraması (kimlik-benzeri izler gözle incelenir)
            try {
                def seenHit = new LinkedHashSet()
                accessionHits(tmp, compileAccession(cfg.accPattern), [cancel: cancel, budgetMs: (cfg.accBudgetMs ?: 10000L)]).each { h ->
                    seenHit.add('IFD ' + h.page + ' etiket ' + h.tag + ' (şekil ' + h.masked + ')')
                }
                review.addAll(seenHit)
            } catch (Throwable th) {
                if (th.getMessage() == 'IPTAL') throw new InterruptedException('iptal')
                if (th.getMessage() == 'ZAMANASIMI')       // desen çok pahalı: tarama TAMAMLANAMADI → asla TAMAM sayılmaz
                    review.add('kayıt numarası taraması ZAMAN AŞIMINA uğradı (desen çok pahalı ya da değer çok uzun) — taranamayan değerleri elle inceleyin')
                else issues << 'Kayıt numarası taraması başarısız: ' + (th.getMessage() ?: th.getClass().getSimpleName())
            }
        }
        def probes = new LinkedHashSet(stems)
        probes.addAll(ed.values)      // ed.values zaten ISO-8859-1 ile çözülmüş DOSYA baytlarıdır → latin1Form'dan GEÇİRİLMEZ (çift kodlama UTF-8 değerleri kaçırırdı); latin1Form yalnız Unicode dosya adı gövdeleri içindir
        def hits = scanForProbes(tmp, probes, cancel)
        if (cancel.get()) throw new InterruptedException('iptal')
        if (!hits.isEmpty()) {
            String h = 'Dosyada orijinal değer geçiyor (' + hits.values().sum() + ' yer)'
            if (cfg.redactMeta && u.kind in ['aperio', 'tiff']) issues << h else notes << h
        }
        def qp = qupathCheck(tmp)
        def edge = null
        if (!qp.ok) issues << 'QuPath açamadı: ' + qp.error
        else {
            if (orig.ok && (orig.width != qp.width || orig.height != qp.height)) issues << 'Görüntü boyutu değişti'
            if (orig.ok) {
                boolean na = Double.isNaN((double) orig.px), nb = Double.isNaN((double) qp.px)
                if (na != nb || (!na && Math.abs((double) orig.px - (double) qp.px) > 1e-9)) issues << 'Piksel boyutu değişti'
            }
            def lm = qp.assoc.findAll { isLabelOrMacro(it) }
            if (!lm.isEmpty()) {
                if (u.kind == 'aperio' && cfg.removeLabels) issues << 'QuPath hâlâ ilişkili görüntü görüyor: ' + lm.join(', ')
                else notes << 'Etiket/makro görüntüsü duruyor: ' + lm.join(', ')
            }
            if (cfg.mainScan != false) {
                edge = mainScanCheck(tmp, false)
                if (edge == null || !edge.ok) notes << 'tarama kenarı denetlenemedi'
            }
        }
        if (!issues.isEmpty()) {
            Files.deleteIfExists(tmp.toPath())
            return [status: 'HATA', detail: issues.join('; ') + ' → kopya silindi', ed: ed]
        }
        if (cancel.get()) throw new InterruptedException('iptal')
        moveWithRetry(tmp, fin)
        u.edge = (edge != null && edge.ok) ? [left: edge.left, right: edge.right, flagged: edge.flagged] : null
        u.review = review
        def lv = ['I']
        if (u.kind == 'aperio') {
            if (cfg.removeLabels) lv << 'III'
            if (cfg.redactMeta) lv << 'IV'
        }
        if (u.kind != 'aperio') notes << KIND_LABEL[u.kind] + ': ' + (u.kind == 'tiff' ? 'ImageDescription biçimi tanınmadı'
                                                                       : 'üst veri/etiket bu biçimde düzenlenmiyor')
        if (u.kind == 'aperio' && !cfg.removeLabels) notes << 'etiket/makro silme kapalı'
        if (u.kind == 'aperio' && !cfg.redactMeta) notes << 'üst veri temizleme kapalı'
        boolean full = (u.kind == 'aperio') && cfg.removeLabels && cfg.redactMeta && notes.isEmpty()
        def parts = ['Seviye ' + lv.join('+')]
        if (!ed.removed.isEmpty()) parts << ed.removed.join('+') + ' silindi (IFD tablosu + değerler + piksel sıfırlandı)'
        if (!ed.keys.isEmpty()) parts << ed.keys.size() + ' alan temizlendi (' + ed.keys.join(', ') + ')'
        if (ed.stemEdits > 0) parts << 'dosya adı ' + ed.stemEdits + ' etikette silindi'
        parts << 'doğrulama ' + (hits.isEmpty() ? 'temiz' : 'UYARILI')
        if (!review.isEmpty()) parts << 'İNCELEYİN — kayıt numarası benzeri desen: ' + review.join('; ')
        if (u.edge?.flagged) parts << 'tarama kenarında koyu şerit şüphesi (etiket bölgesi olabilir; Seviye V kapsam dışı)'
        notes.each { parts << it }
        return [status: !review.isEmpty() ? 'İNCELE' : (full ? 'TAMAM' : 'KISMİ'), detail: parts.join(' · '), ed: ed]
    } catch (Throwable t) {
        try { if (tmp != null) Files.deleteIfExists(tmp.toPath()) } catch (Throwable ignore) {}
        String msg = (t instanceof InterruptedException) ? 'İptal edildi' : ('Hata: ' + (t.getMessage() ?: t.getClass().getSimpleName()))
        return [status: (t instanceof InterruptedException) ? 'ATLANDI' : 'HATA', detail: msg + ' → kopya silindi']
    }
}

// Tek birim: kaynak parmak izi ÖNCE ve SONRA alınır; fark → HATA (yüksek sesle) + kopya silinir. Başarılı
// kopyanın zaman damgaları sabitlenir (A3).
def processUnit = { u, cfg, File outDir, Closure log, AtomicBoolean cancel ->
    u.fpBefore = null; u.fpAfter = null; u.sourceChanged = false
    try { u.fpBefore = fingerprintOf(u.file) }
    catch (Throwable t) { return [status: 'HATA', detail: 'Kaynak parmak izi alınamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName()) + ' → kopya yazılmadı'] }
    def r = processUnitCore(u, cfg, outDir, log, cancel)
    boolean produced = r.status in ['TAMAM', 'KISMİ', 'İNCELE']
    File fin = new File(outDir, u.anon)
    try { u.fpAfter = fingerprintOf(u.file) } catch (Throwable t) { u.fpAfter = null }
    if (!fpEqual(u.fpBefore, u.fpAfter)) {
        u.sourceChanged = true
        if (produced) { try { Files.deleteIfExists(fin.toPath()) } catch (Throwable ignore) {} }
        return [status: 'HATA', ed: r.ed,
                detail: '⚠ ORİJİNAL DOSYA DEĞİŞMİŞ GÖRÜNÜYOR (işlem öncesi/sonrası parmak izi farklı) → kopya silindi; ' +
                        'kaynak dosyayı ve eşitleme/yedekleme araçlarını hemen denetleyin']
    }
    if (produced) {
        u.pin = pinTimes(fin)
        if (!u.pin.mtime) log('    ⚠ zaman damgası sabitlenemedi' + (u.pin.error ? (': ' + u.pin.error) : ''))
    }
    return r
}

// İsteğe bağlı: anonim kopyalardan YENİ QuPath projesi (yalnız boş klasöre)
def createAnonProject = { plan, Closure log ->
    def cfg = plan.cfg
    def eligible = []
    plan.units.each { u ->
        if (u.status in PKG_STATUSES) u.items.each { it -> if (it.entry != null && it.plain) eligible << [u: u, it: it] }
    }
    def skipped = []
    plan.units.each { u ->
        if (u.status in PKG_STATUSES) u.items.each { it -> if (it.entry != null && !it.plain) skipped << it.name }
    }
    if (eligible.isEmpty()) return [created: false, reason: 'Projeye eklenecek uygun giriş yok', skipped: skipped]
    File projDir = new File(plan.outDir, 'qupath-projesi')
    int k = 2
    while (projDir.exists() && !(projDir.isDirectory() && (projDir.list()?.length ?: 0) == 0)) {
        projDir = new File(plan.outDir, 'qupath-projesi-' + (k++))
    }
    projDir.mkdirs()
    def project = Projects.createProject(projDir, BufferedImage.class)
    def perUnit = [:]
    int added = 0, objCount = 0, named = 0, tma = 0
    def warns = []
    eligible.each { e ->
        def u = e.u; def it = e.it
        int nth = ((perUnit[u.anon] ?: 0) as int) + 1
        perUnit[u.anon] = nth
        String entryName = (nth == 1) ? u.anon : (u.anon + ' (' + nth + ')')
        def server = null
        try {
            def nb = it.builder.updateURIs([(it.uris[0]): new File(plan.outDir, u.anon).toURI()])
            server = nb.build()
            // Özgün builder'ın üst verisi (kalibrasyon, seri) korunur; YALNIZ ad anonimleştirilir
            server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata()).name(entryName).build())
            def imgType = ImageData.ImageType.UNSET
            def stains = null
            def copies = []
            if (it.entry.hasImageData()) {
                def od = it.entry.readImageData()
                try {
                    imgType = od.getImageType() ?: ImageData.ImageType.UNSET
                    stains = od.getColorDeconvolutionStains()
                    if (cfg.transferObjects) {
                        def hier = od.getHierarchy()
                        hier.getRootObject().getChildObjectsAsArray().each { po ->
                            copies << PathObjectTools.transformObjectRecursive(po, new java.awt.geom.AffineTransform(), true, true)
                        }
                        def all = hier.getAllObjects(false)
                        objCount += all.size()
                        named += all.count { po -> po.getName() }
                        if (hier.getTMAGrid() != null) tma++
                    }
                } finally {
                    try { od.close() } catch (Throwable ignore) {}
                }
            }
            def pentry = project.addImage(server.getBuilder())
            pentry.setImageName(entryName)
            def nd = new ImageData(server, imgType)
            if (stains != null) nd.setColorDeconvolutionStains(stains)
            if (!copies.isEmpty()) nd.getHierarchy().addObjects(copies)
            pentry.saveImageData(nd)
            try { pentry.setThumbnail(qupath.lib.gui.commands.ProjectCommands.getThumbnailRGB(server)) } catch (Throwable ignore) {}
            added++
            log('    projeye eklendi: ' + entryName)
        } catch (Throwable t) {
            warns << (entryName + ': ' + (t.getMessage() ?: t.getClass().getSimpleName()))
        } finally {
            try { server?.close() } catch (Throwable ignore) {}
        }
    }
    project.syncChanges()
    // Öz denetim: proje dosyasında orijinal dosya adı / klasör yolu geçiyor mu?
    def leaks = []
    try {
        File qp = new File(projDir, 'project.qpproj')
        String txt = qp.isFile() ? qp.getText('UTF-8') : ''
        plan.units.findAll { it.file != null }.each { u ->
            String st = stemOf(u.file.getName())
            if (st.length() >= 4 && txt.contains(st)) leaks << st
            String par = u.file.getParentFile()?.getAbsolutePath()
            if (par && txt.contains(par.replace('\\', '/'))) leaks << '(orijinal klasör yolu)'
        }
    } catch (Throwable ignore) {}
    return [created: true, dir: projDir, file: new File(projDir, 'project.qpproj'), added: added,
            objects: objCount, named: named, tma: tma, warns: warns, skipped: skipped, leaks: leaks.unique()]
}

// Eşleştirme / manifest dosyaları ANAHTAR KLASÖRÜNE yazılır (çıktı klasörüne DEĞİL). Kaynak parmak izleri de
// burada tutulur (özel); paylaşılabilir raporda YOKTUR. Geri alınamaz modda özgün ad/yol/parmak izi YAZILMAZ:
// parmak izi (boyut + zaman + ilk/son 16 MB özeti) özgün dosyayı tanımaya yarar → tersine çevrilebilirlik sayılır;
// bu modda yalnız "kaynak değişmedi" hükmü (evet/hayır) tutulur.
def writeKeyFiles = { plan, String ts ->
    File keyDir = plan.cfg.keyState.dir; def cfg = plan.cfg
    def written = []
    def rows = []
    plan.units.each { u ->
        u.items.each { it ->
            rows << [origName: it.name, origPath: u.file?.getAbsolutePath() ?: '', origId: it.id ?: '',
                     anon: u.anon ?: '', status: u.status, detail: u.detail,
                     fpBefore: fpString(u.fpBefore), fpAfter: fpString(u.fpAfter), fpEnd: fpString(u.fpEnd)]
        }
    }
    if (cfg.reversible) {
        def csv = new File(keyDir, "anonymization_lookup_${ts}.csv")
        csv.withWriter('UTF-8') { w ->
            w.writeLine('Original_Name,Original_Path,Original_Entry_ID,Anonymized_Filename,Status,Detail,Fingerprint_Before,Fingerprint_After,Fingerprint_End,Timestamp')
            rows.each { r -> w.writeLine([r.origName, r.origPath, r.origId, r.anon, r.status, r.detail, r.fpBefore, r.fpAfter, r.fpEnd, ts].collect { csvCell(it) }.join(',')) }
        }
        written << csv
        def json = new File(keyDir, "anonymization_lookup_${ts}.json")
        def data = [timestamp: ts, mode: 'reversible',
                    settings : [namePrefix: cfg.prefix, namingPattern: cfg.naming, removeLabels: cfg.removeLabels,
                                redactMetadata: cfg.redactMeta, includePartial: cfg.includePartial],
                    mappings : rows]
        json.setText(qupath.lib.io.GsonTools.getInstance(true).toJson(data), 'UTF-8')
        written << json
    } else {
        def csv = new File(keyDir, "anonymization_manifest_${ts}.csv")
        csv.withWriter('UTF-8') { w ->
            w.writeLine('Anonymized_Filename,Status,Detail,Source_Unchanged,Timestamp')
            plan.units.findAll { it.anon }.each { u ->
                String same = (u.fpBefore == null) ? '' : ((!u.sourceChanged && (u.fpEnd == null || fpEqual(u.fpBefore, u.fpEnd))) ? 'evet' : 'HAYIR')
                w.writeLine([u.anon, u.status, u.detail, same, ts].collect { csvCell(it) }.join(','))
            }
        }
        written << csv
    }
    return written
}

// Paketin dışında kalan ("bu çalıştırmanın paylaşılabilir çıktısı olmayan") dosyalar — SİLİNMEZ, yalnız bildirilir.
def foreignOutputs = { File outDir, Collection expectedNames ->
    def expected = new HashSet<String>()
    expectedNames.each { expected.add(it.toString().toLowerCase(java.util.Locale.ROOT)) }
    def found = []
    (outDir.listFiles() ?: new File[0]).each { f ->
        String n = f.getName()
        if (expected.contains(n.toLowerCase(java.util.Locale.ROOT))) return
        boolean phiLike = (n =~ /(?i)^(anonymization_(lookup|manifest)_|anahtar|salt\.bin)/).find()
        found << [name: (f.isDirectory() ? (n + '/') : n), phi: phiLike]
    }
    return found.sort { it.name }
}

def edgeFlagged = { plan -> plan.units.findAll { it.status in PKG_STATUSES && it.anon && it.edge?.flagged }.collect { it.anon } }
def reviewUnits = { plan -> plan.units.findAll { it.status == 'İNCELE' && it.anon } }

// BENIOKU.md — alıcıya (Türkçe + kısa İngilizce özet). Tarihler kayıt numarası gibi görünmesin diye yyyyMMdd yazılır.
def buildBenioku = { plan, String ts, String reportName, String sumsName, String readmeName, int nSlides ->
    def cfg = plan.cfg
    def sb = new StringBuilder()
    String day = ts.substring(0, 8)
    def edge = edgeFlagged(plan); def rev = reviewUnits(plan)
    sb << '# WSI anonim slayt paketi — BENİOKU\n\n'
    sb << 'Hazırlanma tarihi: ' << day << '\n\n'
    sb << '## İçindekiler\n\n'
    sb << '- ' << nSlides << ' anonimleştirilmiş slayt kopyası\n'
    sb << '- `' << reportName << '` — işlem raporu (özgün ad içermez)\n'
    sb << '- `' << sumsName << '` — dosya bütünlüğü sağlama toplamları (SHA-256)\n'
    sb << '- `' << readmeName << '` — bu dosya\n\n'
    sb << '**Anahtar / eşleştirme tablosu ve tuz bu pakete DAHİL DEĞİLDİR**; özgün adlarla bağlantı kurulamaz ve paylaşılmaz.\n\n'
    sb << '## Adlandırma\n\n'
    if (cfg.naming == 'hmac')
        sb << 'Dosya adı `' << (cfg.prefix ?: 'case') << '_<10 onaltılı karakter>` biçimindedir: özgün dosya adından, gizli bir tuzla HMAC-SHA256 ile türetilir. Tuz paylaşılmamıştır; adlardan özgün ada ulaşılamaz.\n\n'
    else if (cfg.naming == 'uuid')
        sb << 'Dosya adı `' << (cfg.prefix ?: 'case') << '_<UUID>` biçimindedir (rastgele).\n\n'
    else
        sb << 'Dosya adı `' << (cfg.prefix ?: 'case') << '_<sayı>` biçimindedir. Sayılar özgün (kabul / proje) sırasını YANSITMAZ; karıştırılmıştır.\n\n'
    def prod = plan.units.findAll { it.status in PKG_STATUSES && it.anon }
    def aperioU = prod.findAll { it.kind == 'aperio' }
    def partialU = prod.findAll { it.kind != 'aperio' }
    boolean l3 = cfg.removeLabels && !aperioU.isEmpty(), l4 = cfg.redactMeta && !aperioU.isEmpty()
    def lvList = ['I'] + (l3 ? ['III'] : []) + (l4 ? ['IV'] : [])
    sb << '## Uygulanan anonimleştirme düzeyleri (Bisson ve ark. 2023)\n\n'
    sb << '- **Seviye I** — dosya adları anonimleştirildi.\n'
    if (l3) sb << '- **Seviye III** (' << aperioU.size() << ' Aperio SVS dosyasında) — etiket ve makro görüntüsü dosyadan çıkarıldı; yalnız piksel verisi değil, bu sayfaların IFD tablosu ve satır dışı değerleri (ImageDescription dahil) de sıfırlandı ve geri okunarak doğrulandı.\n'
    else sb << '- **Seviye III** — UYGULANMADI (etiket/makro silme kapalıydı ya da dosyalar Aperio SVS değil).\n'
    if (l4) sb << '- **Seviye IV** (' << aperioU.size() << ' Aperio SVS dosyasında) — Aperio başlığındaki kimlik alanları (Filename, Title, Patient, Case, Barcode, Date, Time, Time Zone, User, ImageID, ScanScope ID, Rack, Slide) tüm sayfalarda aynı uzunlukta `X` ile dolduruldu; standart TIFF metin etiketleri (DateTime, Artist, HostComputer …) temizlendi.\n'
    else sb << '- **Seviye IV** — UYGULANMADI (üst veri temizleme kapalıydı ya da dosyalar Aperio SVS değil).\n'
    sb << '- **Seviye V** (doku / tarama alanı içeriği) — UYGULANMADI.\n\n'
    if (!partialU.isEmpty()) {
        sb << 'Aşağıdaki dosyalar tam desteklenmeyen biçimdedir (KISMİ): yalnız Seviye I uygulanmış, dosya adı ASCII etiketlerden silinmiş ve standart etiketler temizlenmiştir; etiket/makro görüntüsü ve biçime özgü üst veri dosyada KALMIŞ olabilir:\n\n'
        partialU.each { sb << '- `' << it.anon << '`\n' }
        sb << '\n'
    }
    sb << 'Ölçüm alanları **MPP** ve **AppMag** (ve Left/Top gibi geometri alanları) bilerek korunmuştur; aşağı akım araçlar MPP olmadan çalışmaz.\n\n'
    sb << '## Kalan riskler (elle kontrol edin)\n\n'
    sb << '- Etiket/makro silme, ana taranmış görüntünün **içindeki** kimliği silemez. Bazı GT450 taramaları baskılı etiket şeridini tarama kenarında yakalar; camın üzerindeki el yazısı da görüntünün parçasıdır.\n'
    if (edge.isEmpty()) sb << '- Otomatik kenar denetiminde şüpheli dosya bulunmadı (yine de gözle bakın).\n'
    else {
        sb << '- Tarama kenarında koyu şerit (olası etiket) şüphesi olan dosyalar:\n'
        edge.each { sb << '  - `' << it << '`\n' }
    }
    if (!rev.isEmpty()) {
        sb << '- Kayıt numarasına benzeyen bir desen kalan dosyalar (gönderici incelemesi gerekir):\n'
        rev.each { sb << '  - `' << it.anon << '`\n' }
    }
    sb << '- Kayıt numarası taraması: ' << ACC_COVERAGE_TR << '\n'
    sb << '\n## Bütünlük doğrulaması\n\n'
    sb << 'Linux / macOS (dosyaların bulunduğu klasörde):\n\n'
    sb << '    sha256sum -c ' << sumsName << '\n'
    sb << '    # macOS: shasum -a 256 -c ' << sumsName << '\n\n'
    sb << 'Windows PowerShell (tek dosya):\n\n'
    sb << '    (Get-FileHash -Algorithm SHA256 -LiteralPath .\\dosya.svs).Hash.ToLower()\n\n'
    sb << 'Windows PowerShell (tümü):\n\n'
    sb << '    Get-Content ' << sumsName << ' | ForEach-Object { $h, $n = $_ -split \'  \', 2; if ((Get-FileHash -Algorithm SHA256 -LiteralPath $n).Hash.ToLower() -ne $h) { "UYUŞMUYOR: $n" } }\n\n'
    sb << '## English summary\n\n'
    sb << 'This package holds ' << nSlides << ' de-identified whole-slide image copies. Filenames are pseudonymised (Bisson level I)'
    if (l3) sb << '; the label and macro images were removed and their IFD tables, out-of-line values and pixel data zeroed (level III)'
    if (l4) sb << '; identifying Aperio header fields and standard TIFF text tags were overwritten with same-length filler (level IV)'
    sb << '. Levels reached: ' << lvList.join(' + ') << (partialU.isEmpty() ? '' : (' (' + partialU.size() + ' file(s) in a not fully supported format reached level I only and may still hold a label/macro image - see the list above)')) << '. MPP and AppMag are intentionally kept. '
    sb << 'Level V (content inside the scanned tissue area, including a printed label strip captured at the scan edge, or handwriting on the glass) is NOT addressed; please inspect the slides visually. '
    sb << 'No key, crosswalk or salt is included. Verify integrity with `sha256sum -c ' << sumsName << '` (LF line endings) or the PowerShell snippet above.\n'
    return sb.toString()
}

// D1 + D2 + D3: SHA256SUMS.txt (LF, UTF-8) + BENIOKU.md; paylaşılabilir dosyaların zaman damgaları sabitlenir.
def writePackage = { plan, String ts, File reportFile, Closure log ->
    File outDir = plan.outDir
    def slides = plan.units.findAll { it.status in PKG_STATUSES && it.anon && new File(outDir, it.anon).isFile() }.collect { new File(outDir, it.anon) }
    String readmeName = new File(outDir, 'BENIOKU.md').exists() ? ("BENIOKU_${ts}.md" as String) : 'BENIOKU.md'
    String sumsName = new File(outDir, 'SHA256SUMS.txt').exists() ? ("SHA256SUMS_${ts}.txt" as String) : 'SHA256SUMS.txt'
    File readme = new File(outDir, readmeName)
    Files.write(readme.toPath(), buildBenioku(plan, ts, reportFile?.getName() ?: '(rapor yazılamadı)', sumsName, readmeName, slides.size()).getBytes(StandardCharsets.UTF_8),
        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
    def share = new ArrayList(slides)
    if (reportFile != null && reportFile.isFile()) share << reportFile
    share << readme
    share.each { pinTimes(it) }                       // içerik kesinleşti → zamanı sabitle
    File sums = new File(outDir, sumsName)
    def sb = new StringBuilder()
    share.sort { it.getName() }.each { f ->
        String rel = outDir.toPath().relativize(f.toPath()).toString().replace('\\', '/')
        sb.append(sha256Of(f)).append('  ').append(rel).append('\n')                 // yalnız LF: sha256sum -c için
    }
    Files.write(sums.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8),
        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
    share << sums
    int notCreation = 0, notMtime = 0
    share.each { f ->
        def p = pinTimes(f)                           // okuma erişim zamanını değiştirmiş olabilir → son kez sabitle
        if (!p.mtime) notMtime++
        if (!p.creation) notCreation++
    }
    return [sums: sums, readme: readme, files: share.collect { it.getName() }.sort(), notMtime: notMtime, notCreation: notCreation]
}

def buildApplyText = { plan, projInfo, List keyFiles, boolean showOrig, Map ext = [:] ->
    def cfg = plan.cfg
    def c = ['TAMAM': 0, 'KISMİ': 0, 'İNCELE': 0, 'HATA': 0, 'ATLANDI': 0]
    plan.units.each { u -> c[u.status] = (c[u.status] ?: 0) + 1 }
    def sb = new StringBuilder()
    sb << 'WSI ANONİMLEŞTİRME — ÖZET\n'
    sb << '═══════════════════════════\n\n'
    // Paylaşılabilir raporda mutlak yol YOK (kullanıcı adı / kurum klasörü sızdırabilir): yalnız klasör adı
    sb << 'Çıktı klasörü : ' << (showOrig ? plan.outDir.getAbsolutePath() : plan.outDir.getName()) << '\n'
    sb << 'Mod           : ' << (cfg.reversible ? 'Geri alınabilir (eşleştirme anahtarı yazıldı)' : 'Geri alınamaz (özgün ad yazılmadı)') << '\n'
    sb << 'Adlandırma    : ' << (cfg.naming ?: 'sequential') << (cfg.naming == 'hmac' ? ' (tuzlu HMAC-SHA256; her çalıştırmada aynı girdi → aynı takma ad)' : '') << '\n'
    if (showOrig && cfg.keyState != null) sb << 'Anahtar klasörü: ' << cfg.keyState.dir.getAbsolutePath() << '  (PHI — paylaşmayın)\n'
    else sb << 'Anahtar/tuz   : çıktı klasörünün DIŞINDA tutuldu (pakete dahil değil)\n'
    sb << 'Seçenekler    : etiket/makro sil=' << (cfg.removeLabels ? 'evet' : 'hayır') << ' · üst veri temizle=' <<
        (cfg.redactMeta ? 'evet' : 'hayır') << ' · kısmi biçimler=' << (cfg.includePartial ? 'evet' : 'hayır') << '\n'
    sb << String.format(java.util.Locale.US, 'Sonuç         : TAMAM %d · KISMİ %d · İNCELE %d · HATA %d · ATLANDI %d (toplam %d dosya)%n%n',
        c['TAMAM'], c['KISMİ'], c['İNCELE'], c['HATA'], c['ATLANDI'], plan.units.size())
    plan.units.each { u ->
        String mark = ['TAMAM': '✓', 'KISMİ': '◐', 'İNCELE': '⚑', 'HATA': '✗', 'ATLANDI': '–'][u.status] ?: '?'
        String origShown = showOrig ? (u.file?.getName() ?: u.items[0].name) : '(gizli)'
        sb << String.format(java.util.Locale.US, '%s %-7s %-22s ← %s%n', mark, u.status, (u.anon ?: '—'), origShown)
        if (u.detail) sb << '      ' << u.detail << '\n'
    }
    def changed = plan.units.findAll { it.sourceChanged }
    if (!changed.isEmpty()) {
        sb << '\n⚠⚠ ORİJİNAL DOSYA DEĞİŞİKLİĞİ: ' << changed.size() << ' kaynak dosyanın parmak izi işlem sırasında DEĞİŞTİ (ilgili kopyalar silindi, durum HATA):\n'
        changed.each { u -> sb << '   • ' << (u.anon ?: '—') << (showOrig ? ('  ← ' + (u.file?.getName() ?: '?')) : '') << '\n' }
        sb << '   Bu bir yazılım hatası ya da eşitleme/yedekleme/antivirüs müdahalesi olabilir; kaynak dosyaları ve bulut eşitleme durumunu hemen denetleyin.\n'
    } else if (plan.units.any { it.fpBefore != null }) {
        sb << '\n✓ Orijinal dosyalar: işlem öncesi, her birim sonrası ve çalışma sonunda parmak izi (boyut + zaman + ilk/son 16 MB SHA-256) değişmedi.\n'
    }
    def ed = edgeFlagged(plan)
    sb << '\nTarama alanı kenar şeridi (olası etiket): '
    if (ed.isEmpty()) sb << 'şüpheli dosya yok (yine de gözle bakın).\n'
    else {
        sb << ed.size() << ' dosya işaretlendi:\n'
        ed.each { sb << '   • ' << it << '\n' }
    }
    sb << '   Etiket/makro silme ana tarama alanına (Seviye V) uzanmaz; bu dosyaları QuPath\'te gözle kontrol edin. El yazısı da görüntünün parçasıdır.\n'
    def rv = reviewUnits(plan)
    if (!rv.isEmpty()) {
        sb << '\nKayıt numarasına benzeyen desen kalan dosyalar (İNCELE) — değerler gösterilmez, yalnız şekil:\n'
        rv.each { u -> sb << '   • ' << u.anon << ': ' << (u.review ?: []).join('; ') << '\n' }
        sb << '   Bunları gözle doğrulamadan paylaşmayın.\n'
    }
    sb << '\nKayıt numarası taraması: ' << (cfg.accPattern ? 'özel desen kullanıldı.' : ACC_COVERAGE_TR) << '\n'
    if (projInfo != null) {
        sb << '\nAnonim QuPath projesi:\n'
        if (projInfo.created) {
            sb << '  ' << (showOrig ? projInfo.file.getAbsolutePath() : (projInfo.dir.getName() + '/' + projInfo.file.getName())) << '\n'
            sb << '  ' << projInfo.added << ' giriş eklendi' << (cfg.transferObjects ? (', ' + projInfo.objects + ' nesne YENİ kimliklerle aktarıldı') : ' (nesne aktarımı kapalı)') << '\n'
            if (projInfo.named > 0) sb << '  ⚠ ' << projInfo.named << ' nesnenin adı/açıklaması var — PHI içermediğini kontrol edin.\n'
            if (projInfo.tma > 0) sb << '  ⚠ ' << projInfo.tma << ' girişteki TMA ızgarası aktarılmadı.\n'
            if (!projInfo.skipped.isEmpty()) sb << '  · Dönüştürülmüş/kırpılmış ' << projInfo.skipped.size() << ' giriş eklenmedi (kaynağı anonimleştirildi; yeni projede yeniden oluşturun).\n'
            projInfo.warns.each { sb << '  ✗ ' << it << '\n' }
            if (!projInfo.leaks.isEmpty()) sb << '  ⚠ project.qpproj içinde orijinal ad/yol izi: ' << projInfo.leaks.size() << ' — kontrol edin.\n'
            else sb << '  ✓ project.qpproj içinde orijinal dosya adı / klasör yolu bulunmadı.\n'
            sb << '  · Proje klasörü paylaşılabilir pakete (SHA256SUMS/BENIOKU) DAHİL DEĞİLDİR.\n'
        } else sb << '  oluşturulmadı: ' << projInfo.reason << '\n'
    }
    if (showOrig && !keyFiles.isEmpty()) {
        sb << '\nAnahtar klasöründe üretilen dosyalar (PHI):\n'
        keyFiles.each { f -> sb << '  • ' << f.getName() << '\n' }
    }
    if (ext.pkg != null) {
        sb << '\nPaylaşım paketi (' << ext.pkg.files.size() << ' dosya): ' << ext.pkg.sums.getName() << ' (LF, sha256sum -c ile doğrulanır) + ' << ext.pkg.readme.getName() << '\n'
        if (showOrig) {
            if (ext.pkg.notCreation > 0)
                sb << '  ⚠ ' << ext.pkg.notCreation << ' dosyada OLUŞTURMA zamanı sabitlenemedi (dosya sistemi desteklemiyor olabilir); değiştirilme zamanı sabitlendi: ' << PIN_ISO << '\n'
            else sb << '  ✓ Değiştirilme + oluşturma + erişim zamanı ' << PIN_ISO << ' olarak sabitlendi.\n'
        }
    } else if (ext.pkgPlanned) sb << '\nPaylaşım paketi: SHA256SUMS.txt (LF; sha256sum -c ile doğrulanır) ve BENIOKU.md bu klasörde; rapor dahil tüm paylaşılabilir dosyaların zaman damgaları ' << PIN_ISO << ' olarak sabitlenir.\n'
    else if (ext.pkgSkipped) sb << '\nPaylaşım paketi (SHA256SUMS/BENIOKU) yazılmadı: ' << ext.pkgSkipped << '\n'
    if (showOrig && ext.foreign) {
        sb << '\n⚠ Çıktı klasöründe BU çalıştırmanın paylaşılabilir çıktısı olmayan dosyalar var (SİLİNMEDİ) — paylaşmadan önce gözden geçirin:\n'
        ext.foreign.each { f -> sb << '   • ' << f.name << (f.phi ? '   ← anahtar/PHI olabilir, paylaşmayın!' : '') << '\n' }
    }
    if (showOrig && ext.saltNotice) sb << '\n' << ext.saltNotice << '\n'
    sb << '\nDurumlar: TAMAM = Seviye I+III+IV, doğrulandı · KISMİ = yazıldı ama en az bir katman kapsanmadı ·\n'
    sb << '          İNCELE = yazıldı ama kayıt numarası benzeri desen kaldı; gözle doğrulayın ·\n'
    sb << '          HATA = doğrulama geçmedi, kopya silindi · ATLANDI = biçim desteklenmiyor / iptal.\n'
    if (cfg.reversible) sb << '⚠ Eşleştirme anahtarı (anahtar klasörü) PHI içerir — güvenli saklayın, anonim slaytlarla paylaşmayın.\n'
    else sb << '⚠ Geri alınamaz mod: orijinal↔anonim bağlantısı YAZILMADI.\n'
    sb << '⚠ Doku görüntüsünün kendisi (Seviye V) anonimleştirilmez; her kopyayı QuPath\'te gözle de kontrol edin.\n'
    sb << '⚠️ Yalnızca araştırma/eğitim amaçlıdır; klinik karar üretmez.'
    return sb.toString()
}

// Kalıcı çıktılara (manifest, paylaşılabilir rapor) giden metinlerden orijinal yol / ad / kök silinir:
// istisna mesajları (ör. kilitli dosyada Files.copy) kaynak yolunu tam olarak içerebilir.
def sanitizerFor = { plan ->
    def secrets = new LinkedHashSet()
    plan.units.each { u ->
        if (u.file != null) {
            String p = u.file.getAbsolutePath(); String par = u.file.getParent()
            secrets.addAll([p, p.replace('\\', '/'), par, par?.replace('\\', '/'), u.file.getName(), stemOf(u.file.getName())])
        }
        u.items.each { it -> if (it.name) { secrets.add(it.name); secrets.add(stemOf(it.name)) } }
    }
    def list = secrets.findAll { it != null && it.length() >= 4 }.sort { a, b -> b.length() <=> a.length() }
    return { String s ->
        if (s == null) return s
        String r = s
        list.each { x -> r = r.replace((String) x, '(gizli)') }
        return r
    }
}

def runApply = { plan, Closure log, AtomicBoolean cancel ->
    def cfg = plan.cfg
    def ks = cfg.keyState
    if (ks == null) throw new IllegalStateException('Anahtar klasörü hazırlanmadı (anahtar durumu yok)')
    String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyyMMdd_HHmmss'))
    def clean = sanitizerFor(plan)
    def todo = plan.units.findAll { it.process }       // takma ad sırası: dosyalar özgün sırada YAZILMAZ
    plan.units.findAll { !it.process }.each { u -> u.status = 'ATLANDI'; u.detail = u.skipReason }
    def xrow = { u, String status ->
        [origName: cfg.reversible ? (u.file?.getName() ?: '') : '', origPath: cfg.reversible ? (u.file?.getAbsolutePath() ?: '') : '',
         pseudonym: u.base ?: stemOf(u.anon), mode: (cfg.naming ?: 'sequential'), ts: ts, status: status,
         fpBefore: cfg.reversible ? fpString(u.fpBefore) : '', fpAfter: cfg.reversible ? fpString(u.fpAfter) : '']
    }
    int i = 0
    for (u in todo) {
        i++
        if (cancel.get()) { u.status = 'ATLANDI'; u.detail = 'İptal edildi'; continue }
        log(String.format(java.util.Locale.US, '[%d/%d] %s → %s', i, todo.size(), u.file.getName(), u.anon))
        try { appendCrosswalk(ks.dir, [xrow(u, 'BASLADI')]) }
        catch (Throwable t) {
            u.status = 'HATA'; u.detail = 'Anahtar tablosuna yazılamadı (' + clean(t.getMessage() ?: t.getClass().getSimpleName()) + ') → kopya yazılmadı'
            log('    ' + u.status + ' — ' + u.detail); continue
        }
        def r = processUnit(u, cfg, plan.outDir, log, cancel)
        u.status = r.status; u.detail = clean(r.detail)
        try { appendCrosswalk(ks.dir, [xrow(u, u.status)]) }
        catch (Throwable t) {
            if (u.status in PKG_STATUSES) {
                try { Files.deleteIfExists(new File(plan.outDir, u.anon).toPath()) } catch (Throwable ignore) {}
                u.status = 'HATA'; u.detail = 'Anahtar tablosuna yazılamadı → kopya silindi'
            }
        }
        log('    ' + u.status + ' — ' + u.detail)
    }
    // Çalışma sonu: TÜM kaynakların parmak izi bir kez daha
    todo.findAll { it.fpBefore != null && it.file != null }.each { u ->
        def fe = null
        try { fe = fingerprintOf(u.file) } catch (Throwable t) { fe = null }
        u.fpEnd = fe
        if (!fpEqual(u.fpBefore, fe)) {
            if (!u.sourceChanged) {
                u.sourceChanged = true
                try { Files.deleteIfExists(new File(plan.outDir, u.anon).toPath()) } catch (Throwable ignore) {}
                u.status = 'HATA'
                u.detail = '⚠ ORİJİNAL DOSYA ÇALIŞMA SONUNDA DEĞİŞMİŞ GÖRÜNÜYOR (parmak izi farklı) → kopya silindi; kaynağı ve eşitleme araçlarını hemen denetleyin'
                try { appendCrosswalk(ks.dir, [xrow(u, 'HATA')]) } catch (Throwable ignore) {}
            }
            log('    ⚠⚠ ORİJİNAL DEĞİŞTİ: ' + u.anon)
        }
    }
    def projInfo = null
    if (cfg.makeProject && !cancel.get()) {
        log('Anonim QuPath projesi oluşturuluyor …')
        try { projInfo = createAnonProject(plan, log) }
        catch (Throwable t) { projInfo = [created: false, reason: (t.getMessage() ?: t.getClass().getSimpleName())] }
        projInfo.reason = clean((String) projInfo.reason)
        if (projInfo.warns) projInfo.warns = projInfo.warns.collect { clean((String) it) }
    }
    def keyFiles = []
    try { keyFiles = writeKeyFiles(plan, ts) }
    catch (Throwable t) { log('Anahtar dosyaları yazılamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName())) }
    // D3: paylaşılabilir çıktı olmayan dosyalar (rapor/BENIOKU/SHA256SUMS yazılmadan ÖNCE)
    def expected = plan.units.findAll { it.status in PKG_STATUSES && it.anon }.collect { it.anon }
    if (projInfo?.created) expected << projInfo.dir.getName()
    def foreign = foreignOutputs(plan.outDir, expected)
    foreign.each { f -> log('    ⚠ çıktı klasöründe paylaşılabilir çıktı olmayan öğe: ' + f.name + (f.phi ? '  (anahtar/PHI olabilir!)' : '')) }
    boolean producedAny = plan.units.any { it.status in PKG_STATUSES }
    boolean willPackage = producedAny && !cancel.get()
    def ext = [foreign: foreign, saltNotice: (ks.saltCreated ? saltNotice(ks.dir) : null)]
    File rep = null
    try {
        rep = new File(plan.outDir, "anonimlestirme_raporu_${ts}.txt")
        def shareExt = [pkgPlanned: willPackage,
                        pkgSkipped: willPackage ? null : (cancel.get() ? 'çalışma iptal edildi' : 'üretilen slayt yok')]
        Files.write(rep.toPath(), buildApplyText(plan, projInfo, [], false, shareExt).getBytes(StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)   // orijinal ad İÇERMEZ
    } catch (Throwable t) { log('Rapor yazılamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName())); rep = null }
    def pkg = null
    if (willPackage) {
        try { pkg = writePackage(plan, ts, rep, log) }
        catch (Throwable t) { log('Paket (SHA256SUMS/BENIOKU) yazılamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName())) }
    }
    if (pkg != null && pkg.notCreation > 0) log('⚠ ' + pkg.notCreation + ' dosyada oluşturma zamanı sabitlenemedi (dosya sistemi desteklemiyor); değiştirilme zamanı sabitlendi.')
    ext.pkg = pkg
    if (rep != null && rep.isFile()) pinTimes(rep)       // paket yazılmasa bile paylaşılabilir rapor sabitlenir
    if (pkg == null) ext.pkgSkipped = willPackage ? 'yazma hatası (günlüğe bakın)' : (cancel.get() ? 'çalışma iptal edildi' : 'üretilen slayt yok')
    return [text: buildApplyText(plan, projInfo, keyFiles, true, ext), projInfo: projInfo, saltCreated: ks.saltCreated,
            saltNotice: ext.saltNotice, pkg: pkg, foreign: foreign]
}

def planPreviewText = { plan ->
    def sb = new StringBuilder()
    def cfg = plan.cfg
    sb << 'Çıktı klasörü : ' << plan.outDir.getAbsolutePath() << '\n'
    sb << 'Dosya / giriş : ' << plan.units.size() << ' dosya, ' << plan.units.sum { it.items.size() } << ' giriş\n'
    sb << 'Mod           : ' << (cfg.reversible ? 'Geri alınabilir (anahtar yazılır)' : 'Geri alınamaz (özgün ad yazılmaz)') << '\n'
    sb << 'Adlandırma    : ' << (cfg.naming ?: 'sequential') << '\n'
    if (cfg.keyState != null) {
        sb << 'Anahtar klasörü: ' << cfg.keyState.dir.getAbsolutePath() << '  (çıktı DIŞI; PHI — paylaşmayın)\n'
        if (cfg.keyState.saltCreated) sb << '\n' << saltNotice(cfg.keyState.dir) << '\n\n'
    }
    sb << 'Yazılacak     : ' << fmtBytes(plan.bytes) << ' (boş alan: ' << fmtBytes(plan.outDir.getUsableSpace()) << ')\n\n'
    def byKind = plan.units.groupBy { it.kind }
    byKind.each { k, list -> sb << String.format(java.util.Locale.US, '  %-34s %d%n', KIND_LABEL[k], list.size()) }
    sb << '\nEşleştirme:\n'
    int shown = 0
    plan.units.each { u ->
        if (shown++ < 12) {
            String src = u.file?.getName() ?: u.items[0].name
            sb << String.format(java.util.Locale.US, '  %-34s → %s%n', src, u.process ? u.anon : ('ATLANACAK: ' + u.skipReason))
        }
    }
    if (plan.units.size() > 12) sb << String.format(java.util.Locale.US, '  … ve %d dosya daha%n', plan.units.size() - 12)
    return sb.toString()
}

// ═════════════════════════════════════════════════════════════════════════════
// ARAYÜZSÜZ (headless) giriş
// ═════════════════════════════════════════════════════════════════════════════
def foldTr = { String s ->
    (s ?: '').trim().toLowerCase(java.util.Locale.ROOT)
        .replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i').replace('ö', 'o').replace('ş', 's').replace('ü', 'u')
}
def scriptArgs = { ->
    def m = [:]
    def raw = []
    try { if (this.binding.hasVariable('args')) raw = (this.binding.getVariable('args') ?: []) as List } catch (Throwable ignore) {}
    raw.each { s ->
        String str = String.valueOf(s); int i = str.indexOf('=')
        if (i > 0) m.put(foldTr(str.substring(0, i)), str.substring(i + 1).trim())
        else if (str.trim()) m.put(foldTr(str), null)
    }
    return m
}
def BOOL_TRUE  = ['evet', 'e', 'true', '1', 'yes']
def BOOL_FALSE = ['hayir', 'h', 'false', '0', 'no']
def argBool = { Map a, String key, boolean dflt ->
    String v = a.get(key)
    if (v == null || v.isEmpty()) return dflt
    if (foldTr(v) in BOOL_TRUE) return true
    if (foldTr(v) in BOOL_FALSE) return false
    throw new IllegalArgumentException('Geçersiz değer: ' + key + '=' + v + ' (evet/hayır, true/false, 1/0 kabul edilir)')
}

if (isHeadless) {
    def a = scriptArgs()
    def VALID_KEYS = ['proje', 'cikti', 'kip', 'onek', 'adlandirma', 'etiket', 'ustveri', 'anahtar', 'kismi', 'yeniproje', 'nesneler', 'barkod', 'etiketler',
                      'anahtarklasor', 'desen', 'tarama']
    def badKeys = a.keySet().findAll { !VALID_KEYS.contains(it) }
    if (!badKeys.isEmpty())
        throw new IllegalArgumentException('Bilinmeyen argüman: ' + badKeys.join(', ') + '. Geçerli anahtarlar: ' + VALID_KEYS.join(', '))
    String kip = a.kip ? foldTr((String) a.kip) : 'denetle'
    if (!(kip in ['denetle', 'anonimlestir']))
        throw new IllegalArgumentException('Geçersiz kip: ' + a.kip + ' (denetle | anonimlestir)')
    String naming = a.adlandirma ? foldTr((String) a.adlandirma) : 'sirali'
    if (!(naming in ['sirali', 'uuid', 'hmac']))
        throw new IllegalArgumentException('Geçersiz adlandırma: ' + a.adlandirma + ' (sirali | uuid | hmac)')
    def cfg = [prefix         : (a.onek ?: 'case'),
               naming         : (naming == 'uuid' ? 'uuid' : (naming == 'hmac' ? 'hmac' : 'sequential')),
               keyDir         : (a.anahtarklasor ?: ''),
               accPattern     : (a.desen ?: ''),
               mainScan       : argBool(a, 'tarama', true),
               removeLabels   : argBool(a, 'etiket', true),
               redactMeta     : argBool(a, 'ustveri', true),
               reversible     : argBool(a, 'anahtar', true),
               includePartial : argBool(a, 'kismi', false),
               makeProject    : argBool(a, 'yeniproje', false),
               transferObjects: argBool(a, 'nesneler', false)]
    if (QP.getProject() != null)
        throw new IllegalArgumentException("Bu betik projeyi kendisi açar: QuPath'i -p OLMADAN, --args \"proje=<proje.qpproj>\" ile çalıştırın.")
    def project = a.proje ? ProjectIO.loadProject(new File((String) a.proje), BufferedImage.class) : null
    def items = (project != null) ? collectItems('proje', null, project, null)
                                  : collectItems('acik', null, null, QP.getCurrentImageData())
    if (items.isEmpty())
        throw new IllegalArgumentException('Kapsamda görüntü yok: --args "proje=<proje.qpproj>" ya da -i <slayt> verin.')
    def cancel = new AtomicBoolean(false)
    def logc = { String s -> println s }
    if (kip == 'denetle') {
        assertSaveDirSafe(a.etiketler ? new File((String) a.etiketler) : null, [a.cikti ? new File((String) a.cikti) : null, keyDirOf(cfg)])
        println runAudit(items, logc, cancel, [decodeLabels: argBool(a, 'barkod', true), mainScan: cfg.mainScan,
                                               saveDir: (a.etiketler ? new File((String) a.etiketler) : null)]).text
        return
    }
    if (!a.cikti) throw new IllegalArgumentException('Çıktı klasörü gerekli: --args "cikti=<klasör>"')
    if (cfg.makeProject && project == null) throw new IllegalArgumentException('yeniproje=evet yalnız proje= ile kullanılabilir.')
    File outDir = new File((String) a.cikti)
    if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IllegalStateException('Çıktı klasörü oluşturulamadı: ' + outDir)
    compileAccession(cfg.accPattern)                                   // geçersiz desen → hemen hata
    cfg.keyState = prepareKeyState(cfg, outDir, logc)                  // anahtar klasörü çıktı içindeyse HATA; tuz yoksa (hmac) üretilir
    def plan = buildPlan(cfg, items, outDir)
    if (plan.bytes > outDir.getUsableSpace()) throw new IllegalStateException('Yetersiz disk alanı: ' + fmtBytes(plan.bytes) + ' gerekli')
    println planPreviewText(plan)
    println runApply(plan, logc, cancel).text
    return
}

// ═════════════════════════════════════════════════════════════════════════════
// ARAYÜZ — tek pencere durum makinesi
// NO_SOURCE | CONFIG | CONFIRM | RUNNING | AUDIT_DONE | DONE | ERROR
// ═════════════════════════════════════════════════════════════════════════════
def stage = null
def step       = new AtomicReference('CONFIG')
def alwaysTop  = new AtomicBoolean(true)
def cancelFlag = new AtomicBoolean(false)
def running    = new AtomicBoolean(false)
def planRef    = new AtomicReference(null)
def saltPending = new AtomicReference(null)    // bu oturumda üretilen tuzun klasörü (yedek uyarısı için)
def resultRef  = new AtomicReference(null)     // [text, projInfo]
def errorRef   = new AtomicReference(null)
def logAreaRef = new AtomicReference(null)
def ctl        = new java.util.concurrent.ConcurrentHashMap()   // CONFIG denetimleri
def checkedIds = new java.util.concurrent.ConcurrentHashMap()   // giriş ID → seçili mi
def render  // ileri bildirim

def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text)
    b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    return b
}
def copyToClipboard = { String txt ->
    def content = new javafx.scene.input.ClipboardContent()
    content.putString(txt ?: '')
    javafx.scene.input.Clipboard.getSystemClipboard().setContent(content)
}

def readConfigFromFields = { ->
    def base = loadConfig()
    def get = { String k -> ctl.get(k) }
    boolean open = get('rbOpen')?.isSelected() ?: false
    return [prefix         : (get('prefix')?.getText()?.trim() ?: 'case'),
            naming         : (get('naming')?.getValue() ?: base.naming),
            outDir         : (get('outDir')?.getText() ?: base.outDir)?.trim(),
            scope          : open ? 'acik' : 'proje',
            removeLabels   : get('removeLabels')?.isSelected() ?: false,
            redactMeta     : get('redactMeta')?.isSelected() ?: false,
            reversible     : get('reversible')?.isSelected() ?: false,
            includePartial : get('includePartial')?.isSelected() ?: false,
            makeProject    : get('makeProject')?.isSelected() ?: false,
            transferObjects: get('transferObjects')?.isSelected() ?: false,
            decodeLabels   : get('decodeLabels')?.isSelected() ?: false,
            mainScan       : get('mainScan')?.isSelected() ?: false,
            keyDir         : (get('keyDir') != null ? get('keyDir').getText() : base.keyDir)?.trim(),   // boş alan = varsayılan klasör
            accPattern     : (get('accPattern')?.getText() ?: '')?.trim()]
}
def currentItems = { cfg ->
    def project = QP.getProject()
    def chosen = (cfg.scope == 'proje') ? (checkedIds.findAll { k, v -> v }.keySet() as Set) : null
    return collectItems(cfg.scope, chosen, project, QP.getCurrentImageData())
}
def startWorker = { String name, Closure body ->
    cancelFlag.set(false)
    running.set(true)
    def la = new javafx.scene.control.TextArea()
    la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    step.set('RUNNING'); render()
    def log = { String ln ->
        javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') }
    }
    def t = new Thread({
        try { body(log) }
        catch (Throwable e) {
            javafx.application.Platform.runLater {
                errorRef.set(e.getClass().getSimpleName() + ': ' + (e.getMessage() ?: '')); step.set('ERROR'); render()
            }
        } finally { running.set(false) }
    }, name)
    t.setDaemon(true); t.start()
}

def goAudit = { File saveDirFor = null ->
    def cfg = readConfigFromFields()
    def items = currentItems(cfg)
    if (items.isEmpty()) {
        Dialogs.showWarningNotification('Görüntü yok', cfg.scope == 'acik' ? 'Açık görüntü yok.' : 'Listeden en az bir görüntü seçin.')
        return
    }
    try { assertSaveDirSafe(saveDirFor, [cfg.outDir ? new File((String) cfg.outDir) : null, keyDirOf(cfg)]) }
    catch (Throwable t) { Dialogs.showErrorMessage('PNG klasörü reddedildi', t.getMessage() ?: t.getClass().getSimpleName()); return }
    saveConfig(cfg)
    startWorker('AtolyeAnonimDenetim', { log ->
        def r = runAudit(items, log, cancelFlag, [decodeLabels: cfg.decodeLabels, mainScan: cfg.mainScan, keepThumbs: true, saveDir: saveDirFor])
        javafx.application.Platform.runLater { resultRef.set([text: r.text, gallery: r.gallery]); step.set('AUDIT_DONE'); render() }
    })
}

def goPreview = { ->
    def cfg = readConfigFromFields()
    if (!cfg.outDir) {
        Dialogs.showWarningNotification('Çıktı klasörü gerekli', 'Önce bir çıktı (hedef) klasörü seçin.')
        return
    }
    def outDir = new File(cfg.outDir)
    if (!outDir.isDirectory() && !outDir.mkdirs()) {
        Dialogs.showErrorMessage('Klasör oluşturulamadı', 'Çıktı klasörü oluşturulamadı:\n' + outDir.getAbsolutePath())
        return
    }
    def items = currentItems(cfg)
    if (items.isEmpty()) {
        Dialogs.showWarningNotification('Görüntü yok', cfg.scope == 'acik' ? 'Açık görüntü yok.' : 'Listeden en az bir görüntü seçin.')
        return
    }
    def plan
    try {
        compileAccession(cfg.accPattern)
        cfg.keyState = prepareKeyState(cfg, outDir, { String s -> })    // anahtar klasörü çıktı içindeyse HATA; hmac için tuz üretilir
        // Tuz bu oturumda üretildiyse, Önizle'ye ikinci kez basılsa bile yedekleme uyarısı kaybolmasın
        if (cfg.keyState.saltCreated) saltPending.set(cfg.keyState.dir.getAbsolutePath())
        else if (saltPending.get() != null && saltPending.get() == cfg.keyState.dir.getAbsolutePath()) cfg.keyState.saltCreated = true
        plan = buildPlan(cfg, items, outDir)
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Plan kurulamadı', t.getMessage() ?: t.getClass().getSimpleName())
        return
    }
    saveConfig(cfg)
    planRef.set(plan)
    step.set('CONFIRM'); render()
}

def goApply = { ->
    def plan = planRef.get()
    if (plan == null) { step.set('CONFIG'); render(); return }
    startWorker('AtolyeAnonimSihirbaz', { log ->
        def r = runApply(plan, log, cancelFlag)
        javafx.application.Platform.runLater {
            resultRef.set(r); step.set('DONE'); render()
            if (r.saltNotice) Dialogs.showMessageDialog('Tuzu (salt.bin) YEDEKLEYİN', (String) r.saltNotice)
        }
    })
}

render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def cfg = loadConfig()
    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(8)
    center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)
    def actions = new ArrayList()

    def addGuidance = { String txt ->
        def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); lbl.setMaxWidth(Double.MAX_VALUE)
        center.getChildren().add(lbl)
    }
    def addWarnLabel = { String txt ->
        def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); lbl.setMaxWidth(Double.MAX_VALUE)
        lbl.setStyle('-fx-text-fill: #b8860b; -fx-font-weight: bold;')
        center.getChildren().add(lbl)
    }
    def addMonoArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: '')
        ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(ta)
    }

    if (cur == 'NO_SOURCE') {
        title.setText('Proje ya da görüntü açık değil')
        addGuidance('Anonimleştirme/denetim bir proje ya da açık bir görüntü üzerinde çalışır.\n\n' +
            'Bir QuPath projesi ya da slayt açın, sonra "⟳ Yenile".')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('⟳ Yenile', {
            step.set((QP.getProject() == null && QP.getCurrentImageData() == null) ? 'NO_SOURCE' : 'CONFIG'); render()
        }))
    } else if (cur == 'CONFIG') {
        title.setText('WSI anonimleştirme — kapsam ve seçenekler')
        def project = QP.getProject()
        def imageData = QP.getCurrentImageData()
        ctl.clear()

        // Kapsam
        def tg = new javafx.scene.control.ToggleGroup()
        def rbOpen = new javafx.scene.control.RadioButton('Yalnız açık görüntü' + (imageData == null ? ' (açık görüntü yok)' : ''))
        def rbProj = new javafx.scene.control.RadioButton('Proje görüntüleri — aşağıdan seçin (tümü = proje geneli)')
        rbOpen.setToggleGroup(tg); rbProj.setToggleGroup(tg)
        rbOpen.setDisable(imageData == null); rbProj.setDisable(project == null)
        boolean useOpen = (project == null) || (cfg.scope == 'acik' && imageData != null)
        (useOpen ? rbOpen : rbProj).setSelected(true)
        ctl.put('rbOpen', rbOpen)

        def listBox = new javafx.scene.layout.VBox(2)
        def boxes = []
        if (project != null) {
            project.getImageList().each { e ->
                String id = e.getID()
                if (!checkedIds.containsKey(id)) checkedIds.put(id, Boolean.TRUE)
                def cb = new javafx.scene.control.CheckBox(e.getImageName() ?: id)
                cb.setSelected(checkedIds.get(id))
                cb.selectedProperty().addListener({ obs, o, n -> checkedIds.put(id, n) } as javafx.beans.value.ChangeListener)
                boxes << cb
                listBox.getChildren().add(cb)
            }
        }
        def scroll = new javafx.scene.control.ScrollPane(listBox)
        scroll.setFitToWidth(true); scroll.setPrefViewportHeight(130)
        def allBtn = navButton('Tümünü seç', { boxes.each { it.setSelected(true) } })
        def noneBtn = navButton('Hiçbirini seçme', { boxes.each { it.setSelected(false) } })
        def listRow = new javafx.scene.layout.HBox(8, allBtn, noneBtn)
        def syncScope = { ->
            boolean dis = rbOpen.isSelected() || project == null
            scroll.setDisable(dis); listRow.setDisable(dis)
            def mp = ctl.get('makeProject')
            if (mp != null) mp.setDisable(project == null)
        }
        tg.selectedToggleProperty().addListener({ obs, o, n -> syncScope() } as javafx.beans.value.ChangeListener)
        center.getChildren().addAll(rbOpen, rbProj, scroll, listRow)

        // Çıktı + adlandırma
        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(6)
        def outField = new javafx.scene.control.TextField(cfg.outDir ?: '')
        outField.setPrefColumnCount(34)
        def prefixField = new javafx.scene.control.TextField(cfg.prefix ?: 'case')
        prefixField.setPrefColumnCount(12)
        def namingChoice = new javafx.scene.control.ChoiceBox()
        NAMING_OPTIONS.each { namingChoice.getItems().add(it) }
        namingChoice.setValue(NAMING_OPTIONS.contains(cfg.naming) ? cfg.naming : 'sequential')
        ctl.put('outDir', outField); ctl.put('prefix', prefixField); ctl.put('naming', namingChoice)
        def browseDir = { ->
            def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Çıktı klasörü seç', null)
            if (x != null) outField.setText(x.getAbsolutePath())
        }
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, 0, 0, null, new javafx.scene.control.Label('Çıktı klasörü:'), outField, navButton('…', { browseDir() }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, 1, 0, null, new javafx.scene.control.Label('Ad öneki:'), prefixField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, 2, 0, null, new javafx.scene.control.Label('Adlandırma (sequential | uuid | hmac):'), namingChoice)
        // Anahtar klasörü: tuz + eşleştirme tablosu; ÇIKTI klasörünün içinde olamaz
        def keyField = new javafx.scene.control.TextField(cfg.keyDir ?: '')
        keyField.setPrefColumnCount(34)
        keyField.setPromptText('boş = ' + new File(atolyeDataRoot(), 'anonim-anahtar').getAbsolutePath())
        ctl.put('keyDir', keyField)
        def browseKey = { ->
            def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Anahtar klasörü seç (çıktı klasörü DIŞINDA)', null)
            if (x != null) keyField.setText(x.getAbsolutePath())
        }
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, 3, 0, null, new javafx.scene.control.Label('Anahtar klasörü (PHI):'), keyField, navButton('…', { browseKey() }))
        def accField = new javafx.scene.control.TextField(cfg.accPattern ?: '')
        accField.setPrefColumnCount(34)
        accField.setPromptText('boş = varsayılan: yalnız NNN-YY / NNNNN-NA (1021-25, 7-25, 12345-1A); S25-12345, 25-12345 gibi biçimleri KENDİNİZ yazın')
        ctl.put('accPattern', accField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, 4, 0, null, new javafx.scene.control.Label('Kayıt no deseni (regex):'), accField)
        center.getChildren().add(grid)
        addGuidance('hmac: takma ad = önek + tuzlu HMAC-SHA256(özgün dosya adı) ilk 10 onaltılı karakter; aynı girdi + aynı tuz = her partide aynı takma ad. ' +
            'Tuz (salt.bin) ilk kullanımda anahtar klasöründe üretilir — YEDEKLEYİN; kaybolursa sonraki partilerde aynı takma adlar üretilemez.')

        // Seçenekler
        def mk = { String key, String text, boolean val ->
            def cb = new javafx.scene.control.CheckBox(text); cb.setSelected(val); ctl.put(key, cb); center.getChildren().add(cb); cb
        }
        mk('decodeLabels', 'Denetimde etiket DataMatrix kodunu çöz — Sectra "ANON…" etiketini tanır (ZXing; ilk kullanımda ~0,6 MB indirilir)', (boolean) cfg.decodeLabels)
        mk('mainScan', 'Denetimde ve anonimleştirmede ana tarama alanı kenar şeridini (olası etiket) ara — küçük resim + koyu bant ölçütü; yalnız uyarı verir', (boolean) cfg.mainScan)
        mk('removeLabels', 'Etiket ve makro görüntüsünü sil (Aperio SVS — Seviye III; IFD tablosu ve değerleri de sıfırlanır)', (boolean) cfg.removeLabels)
        mk('redactMeta', 'Üst veriyi temizle: Filename/Date/User/ScanScope ID/ImageID … tüm IFD\'lerde + standart TIFF etiketleri (Seviye IV)', (boolean) cfg.redactMeta)
        mk('reversible', 'Geri alınabilir mod (özgün adları anahtar klasörüne yaz: anahtar.csv + eşleştirme CSV/JSON)', (boolean) cfg.reversible)
        mk('includePartial', 'Tam anonimleştirilemeyen biçimleri de kopyala (genel TIFF/NDPI/CZI …; KISMİ olarak işaretlenir)', (boolean) cfg.includePartial)
        def mp = mk('makeProject', 'Anonim kopyalardan yeni QuPath projesi kur (çıktı klasöründe qupath-projesi/)', (boolean) cfg.makeProject)
        def to = mk('transferObjects', '   └ anotasyon/tespitleri yeni projeye aktar (yeni kimliklerle; kaydedilmiş hâl)', (boolean) cfg.transferObjects)
        to.disableProperty().bind(mp.selectedProperty().not().or(mp.disableProperty()))
        syncScope()

        addWarnLabel('⚠ Eşleştirme anahtarı ve tuz PHI içerir — güvenli saklayın, çıktı klasörüne KOYMAYIN. Çıktı klasörünün yolunda hasta adı/numarası olmasın.')
        addGuidance('Orijinal slaytlar yalnız okunur. "Denetle" hiçbir dosyaya yazmaz; mevcut slaytların ne kadar anonim olduğunu raporlar.')

        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Denetle (salt okunur)', { goAudit(null) },'Etiket/makro, dolu kimlik alanları ve standart etiketleri raporlar; yazmaz'))
        actions.add(navButton('Önizle ▶', { goPreview() }, 'Eşleştirmeyi hesaplar ve onay ekranını gösterir'))
    } else if (cur == 'CONFIRM') {
        def plan = planRef.get()
        title.setText('Onay — anonimleştirme planı')
        addMonoArea(planPreviewText(plan))
        int nProc = plan.units.count { it.process }
        if (nProc == 0) addWarnLabel('⚠ İşlenecek dosya yok (hepsi atlanacak). Kısmi biçimleri açmayı ya da kapsamı değiştirmeyi deneyin.')
        if (plan.bytes > plan.outDir.getUsableSpace()) addWarnLabel('⚠ Çıktı diskinde yeterli boş alan yok.')
        if (plan.cfg.makeProject && plan.cfg.transferObjects) {
            def cur2 = QP.getCurrentImageData()
            if (cur2 != null && cur2.isChanged()) addWarnLabel('⚠ Açık görüntüde kaydedilmemiş değişiklik var — nesne aktarımı KAYDEDİLMİŞ hâli kullanır (önce Ctrl+S).')
        }
        String pathLower = plan.outDir.getAbsolutePath().toLowerCase(java.util.Locale.ROOT)
        if (plan.units.any { u -> u.file != null && stemOf(u.file.getName()).length() >= 4 && pathLower.contains(stemOf(u.file.getName()).toLowerCase(java.util.Locale.ROOT)) })
            addWarnLabel('⚠ Çıktı klasörünün yolu bir orijinal dosya adını içeriyor — başka bir klasör seçin.')
        actions.add(navButton('◀ Geri', { step.set('CONFIG'); render() }))
        def start = navButton('Başlat ▶', { goApply() }, 'Anonim kopyaları yazar, her birini doğrular')
        start.setDisable(nProc == 0 || plan.bytes > plan.outDir.getUsableSpace())
        actions.add(start)
    } else if (cur == 'RUNNING') {
        title.setText('Çalışıyor…')
        addGuidance('Büyük WSI dosyalarında kopyalama + bayt taraması dosya başına birkaç dakika sürebilir.')
        def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE)
        center.getChildren().add(pb)
        def la = logAreaRef.get()
        if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) }
        actions.add(navButton('İptal', { cancelFlag.set(true) }, 'Geçerli adımdan sonra durur; yarım kopya silinir'))
    } else if (cur == 'AUDIT_DONE') {
        title.setText('Denetim sonucu')
        // Etiket / makro galerisi: kullanıcının GÖZLE doğrulaması için (en çok 60 slayt)
        def gal = resultRef.get()?.gallery ?: []
        if (!gal.isEmpty()) {
            def flow = new javafx.scene.layout.FlowPane(10, 10)
            flow.setPadding(new javafx.geometry.Insets(4))
            def STATE_TR = [ANON: '✓ Sectra ANON', ANON_DEGIL: '✗ ANON değil', SENTETIK_COZULEMEDI: '? kod okunamadı',
                            FOTOGRAF: '✗ fotoğraf etiket', OKUNAMADI: '? okunamadı', YOK: '— etiket yok']
            def MAC_TR = [BOS: 'makro boş ✓', ICERIK: 'makro DOLU ✗', OKUNAMADI: 'makro okunamadı ?', YOK: 'makro yok']
            gal.each { g ->
                def card = new javafx.scene.layout.VBox(4)
                card.setStyle(g.edgeFlagged ? '-fx-border-color: #d9822b; -fx-border-width: 2; -fx-border-radius: 4; -fx-padding: 6;'
                                            : '-fx-border-color: #c8c8c8; -fx-border-radius: 4; -fx-padding: 6;')
                def row = new javafx.scene.layout.HBox(6)
                [g.label, g.macro, g.main].each { BufferedImage im ->
                    if (im != null) {
                        def iv = new javafx.scene.image.ImageView(javafx.embed.swing.SwingFXUtils.toFXImage(im, null))
                        iv.setPreserveRatio(true); iv.setFitHeight(150)
                        row.getChildren().add(iv)
                    }
                }
                def cap = new javafx.scene.control.Label(g.name + '\n' + (STATE_TR[g.state] ?: g.state) + ' · ' + (MAC_TR[g.macroState] ?: g.macroState) +
                    (g.edgeFlagged ? '\n⚑ tarama kenarında etiket şeridi olabilir — gözle kontrol edin' : ''))
                cap.setWrapText(true); cap.setMaxWidth(420)
                card.getChildren().addAll(row, cap)
                flow.getChildren().add(card)
            }
            def gs = new javafx.scene.control.ScrollPane(flow)
            gs.setFitToWidth(true); gs.setPrefViewportHeight(240)
            center.getChildren().add(gs)
        }
        addMonoArea(resultRef.get()?.text)
        actions.add(navButton('◀ Yapılandırma', { step.set('CONFIG'); render() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultRef.get()?.text) }))
        actions.add(navButton('Etiketleri klasöre kaydet…', {
            def d = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Etiket/makro PNG klasörü', null)
            if (d != null) goAudit(d)
        }, 'Denetimi yeniden çalıştırır ve her slaytın etiket + makro görüntüsünü tam boyutta PNG olarak yazar'))
        actions.add(navButton('Anonimleştirmeye geç ▶', { goPreview() }))
    } else if (cur == 'DONE') {
        title.setText('Tamamlandı')
        def r = resultRef.get()
        addMonoArea(r?.text)
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(r?.text) }))
        if (r?.projInfo?.created) {
            actions.add(navButton('Anonim projeyi aç', {
                if (Dialogs.showConfirmDialog('Anonim projeyi aç', 'Açık proje kapatılıp anonim proje açılacak. Devam edilsin mi?')) {
                    try { gui.setProject(ProjectIO.loadProject(r.projInfo.file, BufferedImage.class)) }
                    catch (Throwable t) { Dialogs.showErrorMessage('Proje açılamadı', t.getMessage() ?: t.getClass().getSimpleName()) }
                }
            }))
        }
        actions.add(navButton('↻ Yeni işlem', { step.set('CONFIG'); render() }))
    } else {
        title.setText('Hata')
        addMonoArea(errorRef.get() ?: 'Bilinmeyen hata.')
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    }

    def topChk = new javafx.scene.control.CheckBox('Üstte tut')
    topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n ->
        alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n)
    } as javafx.beans.value.ChangeListener)
    def spacer = new javafx.scene.layout.Region()
    javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8)
    bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().addAll(topChk, spacer)
    bar.getChildren().addAll(actions)
    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlıdır; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))
    def root = new javafx.scene.layout.BorderPane()
    def sc = new javafx.scene.control.ScrollPane(center)
    sc.setFitToWidth(true); sc.setFitToHeight(true)
    root.setCenter(sc)
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 780, 680))
}

// ── Açılış ───────────────────────────────────────────────────────────────────
step.set((QP.getProject() == null && QP.getCurrentImageData() == null) ? 'NO_SOURCE' : 'CONFIG')

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('WSI anonimleştirme sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        stage.setOnCloseRequest({ ev -> if (running.get()) cancelFlag.set(true) } as javafx.event.EventHandler)
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println '✓ WSI anonimleştirme sihirbazı açıldı.'
