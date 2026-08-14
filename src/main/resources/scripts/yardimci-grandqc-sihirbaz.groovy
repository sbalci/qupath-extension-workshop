/**
 * Yardımcı - GrandQC Kalite Kontrol Sihirbazı (tek pencere: hibrit köprü)
 * ----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   GrandQC'nin (Weng ve ark., Nat Commun 2024) iki aşamalı PyTorch hattını —
 *   (1) doku tespiti, (2) 7 sınıflı artefakt kalite kontrolü — QuPath'e TEK
 *   pencereden bağlar. Derin öğrenme QuPath DIŞINDA bir Python venv'inde koşar;
 *   bu sihirbaz HİBRİT bir köprüdür:
 *     • KÖPRÜ — kopyalanabilir komut satırlarını üretir (terminalde çalıştırın),
 *       sonra üretilen GeoJSON'u içe aktarır.
 *     • DOĞRUDAN — venv yolu ayarlıysa iki Python komutunu QuPath içinden
 *       (ProcessBuilder) çalıştırır, çıktıyı canlı akıtır, bittiğinde içe aktarır.
 *   İçe aktarımdan sonra "Temiz doku = doku − artefaktlar" anotasyonu üretir;
 *   böylece sonraki analiz modülleri yalnız temiz dokuda çalıştırılabilir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • GrandQC çıktısı bir derin öğrenme TAHMİNİDİR; bu betik tahminleri yalnız
 *     QuPath görselleştirme/ölçüm katmanına TAŞIR ve alan/sayı/% üretir. Patoloji
 *     yorumu, grade veya klinik karar üretmez. Görsel doğrulama gerekir (Manuel Düzeltme eki, Yapay Zekâ Araçlarını Değerlendirme eki).
 *   • GeoJSON koordinatları WSI taban (level-0) piksel uzayında olmalıdır; betik
 *     yeniden ölçekleme yapmaz.
 *   • Lisans: GrandQC kodu/modelleri CC BY-NC-SA 4.0 (yalnız ticari olmayan).
 *
 * KULLANIM:
 *   1. GrandQC Python ortamını kurun (venv + modeller). Bkz. Ekler → Kalite Kontrol § GrandQC.
 *   2. Bir H&E slaydını açın (yerel diskte).
 *   3. [Extensions → Atölye → Modüller → Doku tespiti → GrandQC modeli (doku & artefakt, Python)]
 *   4. İlk açılışta yapılandırın: python.exe, GrandQC betik dizini, model dizini, MPP.
 *   5. "Komut üret" (kopyala-çalıştır) ya da "Doğrudan çalıştır"; sonra otomatik içe aktarım.
 *
 * QUPATH MENÜSÜ — MANUEL ALTERNATİF:
 *   • Üretilen GeoJSON'u yerleşik [File → Import objects] ile de yükleyebilirsiniz
 *     (bu sihirbaz ek olarak sınıf renkleri + "Temiz doku" üretir).
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Weng Z ve ark. (2024), Nat Commun 15:10685 — GrandQC. doi:10.1038/s41467-024-54769-y
 *   • Upstream: https://github.com/cpath-ukk/grandqc (CC BY-NC-SA 4.0)
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane
import qupath.lib.geom.Point2
import qupath.lib.common.ColorTools
import com.google.gson.JsonParser
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ───────────────────────────────────────────────────────────────
def GRANDQC_SENTINEL = 'GrandQC KK'           // yeniden-içe-aktarımda idempotent temizlik adı
long PYTHON_TIMEOUT_SECONDS = 600L            // büyük WSI için cömert üst sınır
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def MPP_OPTIONS = ['1.0', '1.5', '2.0']

// Ortam doğrulama için Python içe-aktarma testi (çok satırlı -c programı). TEK tırnaklı
// python literalleri kullanılır → Windows ProcessBuilder çift-tırnak mangling'ini önler
// (lint Check 17; bkz. yardimci-python-ortam-yoneticisi regresyonu). Yalnız bilgilendirici.
def PY_DIAG = "import importlib, sys\n" +
    "print('  python ' + sys.version.split()[0])\n" +
    "for m in ['numpy', 'cv2', 'torch', 'openslide']:\n" +
    "    try:\n" +
    "        importlib.import_module(m)\n" +
    "        print('  ' + m + ': OK')\n" +
    "    except Exception as e:\n" +
    "        print('  ' + m + ': HATA (' + type(e).__name__ + ')')\n" +
    "try:\n" +
    "    import torch\n" +
    "    print('  cuda kullanilabilir: ' + str(torch.cuda.is_available()))\n" +
    "except Exception:\n" +
    "    pass\n"

// GrandQC artefakt sınıfları (geojson properties.classification ile eşleşir)
def CLEAN_CLASS    = 'Temiz doku'
def TISSUE_CLASS   = 'Tissue'
def ARTIFACT_CLASSES = ['Tissue Fold', 'Dark Spot & Foreign Object',
                        'Pen Marking', 'Air Bubble & Slide Edge', 'Out of Focus'] as Set

// Sınıf → sabit renk (ColorTools.packRGB)
def CLASS_COLORS = [
    'Tissue'                     : ColorTools.packRGB(144, 238, 144),
    'Tissue Fold'                : ColorTools.packRGB(255, 165,   0),
    'Dark Spot & Foreign Object' : ColorTools.packRGB(139,   0,   0),
    'Pen Marking'                : ColorTools.packRGB( 75,   0, 130),
    'Air Bubble & Slide Edge'    : ColorTools.packRGB(135, 206, 250),
    'Out of Focus'               : ColorTools.packRGB(255, 215,   0),
    'Background'                 : ColorTools.packRGB(200, 200, 200),
    'Temiz doku'                 : ColorTools.packRGB( 50, 205,  50)
]

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır) ──
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/grandqc')
def PREF_PYTHON  = 'python'
def PREF_SCRIPTS = 'scriptsDir'
def PREF_MODEL   = 'modelDir'
def PREF_MPP     = 'mppModel'
def PREF_DEVICE  = 'device'

// ── Hızlandırıcı algılama (mps/cuda/cpu) ────────────────────────────────────
// GrandQC betikleri cihazı KAYNAKTA sabitler (DEVICE = 'cuda'); komut satırı
// bayrağı yoktur. Bu yüzden çalıştırmadan önce iki .py dosyasındaki DEVICE
// satırını seçilen cihaza göre yamalarız (bkz. patchDevice).
// Algılama BİR KEZ yapılır (session boyunca değişmez) ve önbelleğe alınır;
// böylece render() içinden çağrılsa bile FX iş parçacığında `nvidia-smi`
// süreci tekrar tekrar başlatılmaz. `nvidia-smi` bir zaman aşımıyla sınırlanır.
def _accelCache = new java.util.concurrent.atomic.AtomicReference(null)
def detectAccelerator = { ->
    def cached = _accelCache.get()
    if (cached != null) return cached
    def result = 'cpu'
    try {
        def os   = (System.getProperty('os.name') ?: '').toLowerCase(java.util.Locale.ROOT)
        def arch = (System.getProperty('os.arch') ?: '').toLowerCase(java.util.Locale.ROOT)
        if (os.contains('mac') && (arch.contains('aarch64') || arch.contains('arm'))) {
            result = 'mps'
        } else {
            def p = null
            try {
                // Çıktıyı OKUMA — DISCARD'a yönlendir; böylece asılı bir süreçte
                // akış okurken (readLines) sonsuza dek bloklanmayız. Yalnız çıkış
                // kodu gerekli; waitFor bir zaman aşımıyla sınırlı.
                p = new ProcessBuilder(['nvidia-smi'])
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                if (p.waitFor(4L, java.util.concurrent.TimeUnit.SECONDS)) {
                    if (p.exitValue() == 0) result = 'cuda'
                } else {
                    try { p.destroyForcibly() } catch (Throwable ignore) {}   // asılı kalırsa öldür
                }
            } catch (Throwable ignore) {
                try { if (p != null) p.destroyForcibly() } catch (Throwable ig2) {}
            }
        }
    } catch (Throwable ignore) {}
    _accelCache.set(result)
    return result
}

def loadConfig = { ->
    [ python   : prefs.get(PREF_PYTHON,  ''),
      scripts  : prefs.get(PREF_SCRIPTS, ''),
      modelDir : prefs.get(PREF_MODEL,   ''),
      mpp      : prefs.get(PREF_MPP,     '1.5'),
      device   : prefs.get(PREF_DEVICE,  '') ]   // boş = otomatik algıla
}

// Seçili (ya da boşsa algılanan) cihazı döndür.
def effectiveDevice = { cfg -> (cfg?.device?.trim()) ?: detectAccelerator() }

// wsi_tis_detect.py + main.py içindeki  DEVICE = 'cuda'  satırını yamala.
// Dönüş: [patched:[...], errors:[...]]  — errors GERÇEK bir okuma/yazma hatasını
// (izin/kilit) "zaten doğru"/"satır yok" iyi-huylu durumundan ayırır.
def patchDevice = { String scriptsDir, String device ->
    def patched = []; def errors = []
    if (!scriptsDir?.trim()) { errors << 'betik dizini ayarsız'; return [patched: patched, errors: errors] }
    def dev = (device?.trim()) ?: 'cpu'
    ['wsi_tis_detect.py', 'main.py'].each { fn ->
        def f = new File(scriptsDir, fn)
        if (!f.isFile()) return   // dosya yoksa configMissing zaten yakalar; burada sessiz
        String txt
        try { txt = f.getText('UTF-8') }
        catch (Throwable re) { errors << (fn + ' okunamadı: ' + (re.getMessage() ?: re.getClass().getSimpleName())); return }
        // ^ (isteğe bağlı boşluk) DEVICE = 'xxx'  |  "xxx:0"  → DEVICE = '<dev>'
        def out = txt.replaceAll(/(?m)^([ \t]*DEVICE[ \t]*=[ \t]*)['"][^'"\r\n]*['"]/, '$1\'' + dev + '\'')
        if (out == txt) return    // DEVICE satırı yok ya da zaten doğru — iyi huylu
        try { f.setText(out, 'UTF-8'); patched << (fn + " → DEVICE='" + dev + "'") }
        catch (Throwable we) { errors << (fn + ' yazılamadı: ' + (we.getMessage() ?: we.getClass().getSimpleName())) }
    }
    return [patched: patched, errors: errors]
}

// Zorunlu: python.exe + betik dizininde wsi_tis_detect.py & main.py.
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile())
        miss << 'Python yürütülebilir (python.exe)'
    if (!cfg.scripts?.trim() || !(new File(cfg.scripts, 'wsi_tis_detect.py')).isFile())
        miss << 'GrandQC betik dizini (wsi_tis_detect.py)'
    if (cfg.scripts?.trim() && !(new File(cfg.scripts, 'main.py')).isFile())
        miss << 'GrandQC betik dizini (main.py)'
    // Model ağırlıkları: betik dizinindeki (ya da modelDir) models/td ve models/qc altında en az birer .pth
    if (cfg.scripts?.trim()) {
        def md = cfg.modelDir?.trim() ? new File(cfg.modelDir) : new File(cfg.scripts, 'models')
        def hasPth = { File d -> d.isDirectory() && (((d.listFiles({ f, n -> n.toLowerCase(java.util.Locale.ROOT).endsWith('.pth') } as java.io.FilenameFilter))?.length) ?: 0) > 0 }
        if (!hasPth(new File(md, 'td')) || !hasPth(new File(md, 'qc')))
            miss << 'Model ağırlıkları (models/td + models/qc altında .pth)'
    }
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

// ── Slayt yolunu çöz (yerel dosya → klasör + temel ad → beklenen geojson) ──
def resolveSlide = { imageData ->
    def server = imageData.getServer()
    def slideFile = null
    try {
        def uris = server.getURIs()
        if (uris != null && !uris.isEmpty()) {
            def uri = uris.iterator().next()
            if ('file'.equals(uri.getScheme())) {
                try { slideFile = new File(uri) } catch (Throwable ignore) { slideFile = null }
            }
        }
    } catch (Throwable ignore) {}
    def folder = (slideFile != null && slideFile.getParentFile() != null)
        ? slideFile.getParentFile().getAbsolutePath() : null
    def rawName = (slideFile != null) ? slideFile.getName() : (server.getMetadata().getName() ?: 'slide')
    def baseName = rawName.replaceAll(/\.[^.\/\\]+$/, '')   // son uzantıyı at
    def geojson = (folder != null) ? new File(new File(folder, 'geojson_qc'), baseName + '.geojson') : null
    return [file: slideFile, folder: folder, name: baseName, geojson: geojson, local: (slideFile != null)]
}

// geojson_qc içinde tam ad yoksa makul bir yedeğe düş.
// NOT: `manualFolder` çağıranlarca effectiveFolder(slide) geçilir (elle seçilen
// klasör → yoksa slaytın klasörü). O yüzden BURADA da aynı öncelik: manualFolder ÖNCE.
def findGeoJSON = { slide, manualFolder ->
    def folder = manualFolder ?: slide.folder
    if (folder == null) return null
    // slide.geojson kısayolu YALNIZ etkin klasör slaydın kendi klasörüyse geçerli
    // (klasör geçersiz kılındıysa slide.geojson eski/yanlış klasörü işaret eder).
    def exact = (folder == slide.folder && slide.geojson != null && slide.geojson.isFile())
        ? slide.geojson : new File(new File(folder, 'geojson_qc'), slide.name + '.geojson')
    if (exact.isFile()) return exact
    def dir = new File(folder, 'geojson_qc')
    if (!dir.isDirectory()) return exact   // bulunamadı; çağıran .isFile() ile ele alır
    def candidates = dir.listFiles({ d, n -> n.toLowerCase(java.util.Locale.ROOT).endsWith('.geojson') } as java.io.FilenameFilter)
    if (candidates == null || candidates.length == 0) return exact
    def starts = candidates.find { it.getName().startsWith(slide.name) }
    if (starts != null) return starts
    if (candidates.length == 1) return candidates[0]
    return exact
}

// ── Batch (proje geneli) GeoJSON çözümü: YALNIZ tam ad eşleşmesi ─────────────
// Bulanık yedek (startsWith / tek-dosya) tek-slayt kolaylığıdır; proje döngüsünde
// paylaşılan bir klasörde bir slaydın GeoJSON'unu YANLIŞ slayda yazma (ve kaydetme)
// riskini taşır. Batch bu yüzden asla bulanık eşleşme kullanmaz.
def exactGeoJSON = { slide, folder ->
    if (folder == null || slide?.name == null) return null
    def f = new File(new File(folder.toString(), 'geojson_qc'), slide.name + '.geojson')
    return f.isFile() ? f : null
}

// GrandQC'nin main.py'sinin ürettiği slayt-başı istatistik raporu (salt okunur):
//   <output_dir>/report_<klasörAdı>_<baş>_<son>_stats_per_slide.txt
// output_dir = kapsam klasörü; en yeni eşleşeni döndür (yoksa null).
def findReportFile = { String folder ->
    if (folder == null) return null
    def dir = new File(folder)
    if (!dir.isDirectory()) return null
    def cand = dir.listFiles({ d, n ->
        def ln = n.toLowerCase(java.util.Locale.ROOT)
        ln.startsWith('report_') && ln.endsWith('stats_per_slide.txt')
    } as java.io.FilenameFilter)
    if (cand == null || cand.length == 0) return null
    return cand.toList().sort { -it.lastModified() }.first()
}

// ── Komut üretimi ───────────────────────────────────────────────────────────
def tissueCmd = { cfg, folder ->
    [cfg.python, new File(cfg.scripts, 'wsi_tis_detect.py').getAbsolutePath(),
     '--slide_folder', folder, '--output_dir', folder]
}
def artifactCmd = { cfg, folder ->
    [cfg.python, new File(cfg.scripts, 'main.py').getAbsolutePath(),
     '--slide_folder', folder, '--output_dir', folder,
     '--mpp_model', cfg.mpp, '--create_geojson', 'Y']
}
def cmdText = { cfg, folder, slideName ->
    def q = { s -> '"' + (s ?: '') + '"' }
    def py = cfg.python
    def s1 = new File(cfg.scripts, 'wsi_tis_detect.py').getAbsolutePath()
    def s2 = new File(cfg.scripts, 'main.py').getAbsolutePath()
    def sb = new StringBuilder()
    sb << "# GrandQC — bu klasördeki TÜM slaytları işler: " << folder << "\n"
    sb << "# NOT: GrandQC cihazı kaynakta sabitler. Bu iki .py dosyasında\n"
    sb << "#      DEVICE = '...' satırını cihazınıza göre ayarlayın (cuda / mps / cpu).\n"
    sb << "#      'Doğrudan çalıştır' bu yamayı sizin için otomatik yapar (seçili cihaz: " << effectiveDevice(cfg) << ").\n\n"
    sb << "# 1) Doku tespiti (MPP10)\n"
    sb << q(py) << ' ' << q(s1) << ' --slide_folder ' << q(folder) << ' --output_dir ' << q(folder) << "\n\n"
    sb << "# 2) Artefakt kalite kontrolü (MPP " << cfg.mpp << ")\n"
    sb << q(py) << ' ' << q(s2) << ' --slide_folder ' << q(folder) << ' --output_dir ' << q(folder)
    sb << ' --mpp_model ' << cfg.mpp << ' --create_geojson Y' << "\n\n"
    sb << "# Beklenen çıktı:\n"
    sb << "#   " << folder << File.separator << "geojson_qc" << File.separator << slideName << ".geojson\n\n"
    if (cfg.modelDir?.trim())
        sb << "# Model dizini: " << cfg.modelDir << "  (Tissue_Detection_MPP10.pth + GrandQC_MPP*.pth)\n"
    sb << "# Lisans: CC BY-NC-SA 4.0 — yalnız araştırma/eğitim.\n"
    return sb.toString()
}

// ── ROI birleştirme / çıkarma (RoiTools; başarısızsa JTS Geometry yedeği) ──
def unionRois = { rois ->
    if (rois == null || rois.isEmpty()) return null
    if (rois.size() == 1) return rois[0]
    try {
        return qupath.lib.roi.RoiTools.union(rois)
    } catch (Throwable t) {
        org.locationtech.jts.geom.Geometry g = null
        for (r in rois) { def gg = r.getGeometry(); g = (g == null) ? gg : g.union(gg) }
        return qupath.lib.roi.GeometryTools.geometryToROI(g, ImagePlane.getDefaultPlane())
    }
}
def subtractRois = { main, subs ->
    if (subs == null || subs.isEmpty()) return main
    try {
        return qupath.lib.roi.RoiTools.subtract(main, subs)
    } catch (Throwable t) {
        org.locationtech.jts.geom.Geometry g = main.getGeometry()
        for (r in subs) { g = g.difference(r.getGeometry()) }
        return qupath.lib.roi.GeometryTools.geometryToROI(g, main.getImagePlane())
    }
}

// ── PathClass'ı sabit renkle hazırla ────────────────────────────────────────
def ensureClass = { String name ->
    def pc = QP.getPathClass(name)
    def col = CLASS_COLORS[name]
    if (col != null && pc != null) { try { pc.setColor((Integer) col) } catch (Throwable ignore) {} }
    return pc
}

// ── GeoJSON içe aktarma (yardimci-tahmin-iceaktar kalıbından) ───────────────
// hierarchy: hedef hiyerarşi (canlı görüntü ya da proje girdisi) — QP.* yerine bunu
// kullanır; böylece hem tek slayt hem proje geneli (batch) yol aynı kodu paylaşır.
def importGeoJSON = { File geojsonFile, hierarchy ->
    if (geojsonFile == null || !geojsonFile.isFile())
        return [ok: false, error: 'GeoJSON bulunamadı:\n' + (geojsonFile?.getAbsolutePath() ?: '(yol yok)') +
                '\n\nÖnce GrandQC hattını çalıştırın (Komut üret / Doğrudan çalıştır).']
    int minVertex = 4
    def plane = ImagePlane.getDefaultPlane()
    def gf = new org.locationtech.jts.geom.GeometryFactory()
    // Bir GeoJSON halkasını (linear ring) kapalı JTS Coordinate[] dizisine çevir; geçersizse null.
    def ringCoords = { ring ->
        if (ring == null || ring.size() < minVertex) return null
        def cs = new ArrayList<org.locationtech.jts.geom.Coordinate>(ring.size() + 1)
        for (int i = 0; i < ring.size(); i++) {
            def c = ring.get(i).getAsJsonArray()
            if (c.size() < 2) return null
            double x = c.get(0).getAsDouble(); double y = c.get(1).getAsDouble()
            if (!Double.isFinite(x) || !Double.isFinite(y)) return null
            cs.add(new org.locationtech.jts.geom.Coordinate(x, y))
        }
        if (cs.size() < minVertex) return null
        def f = cs.get(0); def l = cs.get(cs.size() - 1)      // JTS LinearRing kapalı olmalı (ilk == son)
        if (f.x != l.x || f.y != l.y) cs.add(new org.locationtech.jts.geom.Coordinate(f.x, f.y))
        if (cs.size() < 4) return null
        return cs.toArray(new org.locationtech.jts.geom.Coordinate[0])
    }
    // Bir GeoJSON poligonunu (halka 0 = dış kabuk, halka 1..n = DELİKLER) delikleri KORUYARAK JTS Polygon'a çevir.
    // (Eski sürüm yalnız dış kabuğu alıp delikleri düşürüyordu → alanlar delik kadar şişiyordu.)
    def polyFromRingArray = { ringArr ->
        if (ringArr == null || ringArr.size() == 0) return null
        def shellC
        try { shellC = ringCoords(ringArr.get(0).getAsJsonArray()) } catch (Throwable t) { return null }   // kabuk dizisi değilse: bu poligonu at
        if (shellC == null) return null
        org.locationtech.jts.geom.LinearRing shell
        try { shell = gf.createLinearRing(shellC) } catch (Throwable t) { return null }
        def holes = new ArrayList<org.locationtech.jts.geom.LinearRing>()
        for (int i = 1; i < ringArr.size(); i++) {
            // Bozuk bir delik halkası TÜM içe aktarımı iptal etmesin — yalnız o deliği atla.
            try {
                def hc = ringCoords(ringArr.get(i).getAsJsonArray())
                if (hc == null) continue
                holes.add(gf.createLinearRing(hc))
            } catch (Throwable t) {}
        }
        try { return gf.createPolygon(shell, holes.toArray(new org.locationtech.jts.geom.LinearRing[0])) }
        catch (Throwable t) { return null }
    }
    def polyToRoi = { poly ->
        try { return qupath.lib.roi.GeometryTools.geometryToROI(poly, plane) } catch (Throwable t) { return null }
    }
    def classNameOf = { feature ->
        if (!feature.has('properties') || feature.get('properties').isJsonNull()) return 'Background'
        def props = feature.getAsJsonObject('properties')
        if (!props.has('classification') || props.get('classification').isJsonNull()) return 'Background'
        def cls = props.get('classification')
        if (cls.isJsonPrimitive()) return cls.getAsString()
        if (cls.isJsonObject() && cls.getAsJsonObject().has('name')) return cls.getAsJsonObject().get('name').getAsString()
        return 'Background'
    }
    def newAnns = []
    def counts = new TreeMap<String, Integer>()
    int skipped = 0
    try {
        def reader = new java.io.InputStreamReader(
            new java.io.FileInputStream(geojsonFile), java.nio.charset.StandardCharsets.UTF_8)
        try {
            def root = JsonParser.parseReader(reader).getAsJsonObject()
            if (!root.has('features'))
                return [ok: false, error: "Geçersiz GeoJSON: 'features' dizisi yok (FeatureCollection bekleniyor)."]
            root.getAsJsonArray('features').each { fe ->
              // Bozuk tek bir özellik TÜM dosyanın içe aktarımını iptal etmesin: say ve devam et.
              try {
                def feature = fe.getAsJsonObject()
                if (!feature.has('geometry') || feature.get('geometry').isJsonNull()) { skipped++; return }
                def geom = feature.getAsJsonObject('geometry')
                if (!geom.has('type') || !geom.has('coordinates')) { skipped++; return }
                String gt = geom.get('type').getAsString()
                def polys = []   // bu özelliğin JTS poligonları (delikler dahil)
                if (gt == 'Polygon') {
                    def p = polyFromRingArray(geom.getAsJsonArray('coordinates'))
                    if (p != null) polys << p
                } else if (gt == 'MultiPolygon') {
                    geom.getAsJsonArray('coordinates')?.each { poly ->
                        def p = polyFromRingArray(poly.getAsJsonArray())
                        if (p != null) polys << p
                    }
                } else { skipped++; return }
                if (polys.isEmpty()) { skipped++; return }   // hiç geçerli JTS poligonu üretilemedi
                String cn = classNameOf(feature)
                def pc = ensureClass(cn)
                polys.each { poly ->
                    def roi = polyToRoi(poly)
                    if (roi == null || roi.isEmpty()) { skipped++; return }   // parse edildi ama ROI'ye çevrilemedi → say (sessiz düşürme yok)
                    def ann = PathObjects.createAnnotationObject(roi, pc)
                    ann.setName(GRANDQC_SENTINEL)
                    ann.setLocked(true)
                    newAnns << ann
                    counts[cn] = (counts.getOrDefault(cn, 0)) + 1
                }
              } catch (Throwable feErr) { skipped++ }
            }
        } finally { reader.close() }
    } catch (Throwable t) {
        return [ok: false, error: 'GeoJSON okunamadı:\n' + (t.getMessage() ?: t.getClass().getSimpleName())]
    }
    if (newAnns.isEmpty())
        return [ok: false, error: 'GeoJSON dosyasında geçerli Polygon/MultiPolygon özelliği bulunamadı (atlanan: ' + skipped + ').']
    // Önceki içe aktarımı temizle → idempotent (verilen hiyerarşi üzerinde)
    hierarchy.removeObjects(hierarchy.getAnnotationObjects().findAll { it.getName() == GRANDQC_SENTINEL }, false)
    hierarchy.addObjects(newAnns)
    hierarchy.fireHierarchyUpdate()
    return [ok: true, annotations: newAnns, counts: counts, skipped: skipped, file: geojsonFile]
}

// ── Parça (bağlı bileşen) istatistiği — JTS geometrisinden; salt sayı/alan ──
// Bir ROI'nin kaç ayrık parçadan oluştuğunu (bağlı bileşen) ve parça alanlarını verir.
// difference() sonucu dejenere sliver'lar (çizgi/nokta, alan≈0) üretebildiğinden
// alanı ≤1 px² olan bileşenler sayılmaz. Yalnız ölçü; derece/eşik/yorum ÜRETMEZ.
def fragmentStats = { roi, double pw, double ph, boolean calibrated ->
    def out = [count: 0, maxMm2: Double.NaN, minMm2: Double.NaN, meanMm2: Double.NaN]
    if (roi == null) return out
    org.locationtech.jts.geom.Geometry g = null
    try { g = roi.getGeometry() } catch (Throwable t) { return out }
    if (g == null) return out
    def areas = []
    int n = g.getNumGeometries()
    for (int i = 0; i < n; i++) {
        double aPx
        try { aPx = g.getGeometryN(i).getArea() } catch (Throwable t) { aPx = 0.0d }
        if (aPx <= 1.0d) continue
        areas << (calibrated ? (aPx * pw * ph / 1_000_000.0d) : aPx)
    }
    out.count = areas.size()
    if (!areas.isEmpty() && calibrated) {
        out.maxMm2 = areas.max(); out.minMm2 = areas.min()
        out.meanMm2 = (areas.sum() as double) / areas.size()
    }
    return out
}

// ── Temiz doku = doku − artefaktlar ─────────────────────────────────────────
// hierarchy: hedef hiyerarşi (canlı ya da proje girdisi) — QP.* yerine kullanılır.
def computeCleanTissue = { imageData, importedAnns, hierarchy ->
    def tissueAnns   = importedAnns.findAll { it.getPathClass()?.getName() == TISSUE_CLASS }
    def artifactAnns = importedAnns.findAll { ARTIFACT_CLASSES.contains(it.getPathClass()?.getName()) }
    if (tissueAnns.isEmpty())
        return [ok: false, error: 'GeoJSON içinde "Tissue" sınıflı anotasyon yok — Temiz doku üretilemedi.']
    def cal = imageData.getServer().getPixelCalibration()
    double pw = cal.getPixelWidthMicrons()
    double ph = cal.getPixelHeightMicrons()
    boolean calibrated = (pw > 0 && ph > 0 && !Double.isNaN(pw) && !Double.isNaN(ph))
    def areaMm2 = { roi -> (roi != null && calibrated) ? (roi.getArea() * pw * ph / 1_000_000.0d) : Double.NaN }

    def tissueUnion
    try { tissueUnion = unionRois(tissueAnns.collect { it.getROI() }) }
    catch (Throwable t) { return [ok: false, error: 'Doku birleştirme hatası: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }

    def cleanRoi
    try { cleanRoi = subtractRois(tissueUnion, artifactAnns.collect { it.getROI() }) }
    catch (Throwable t) { return [ok: false, error: 'Artefakt çıkarma hatası: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }

    if (cleanRoi == null || cleanRoi.isEmpty() || !cleanRoi.isArea())
        return [ok: false, error: 'Temiz doku alanı boş kaldı (artefaktlar dokunun tamamını kaplıyor olabilir).']

    def cleanAnn = PathObjects.createAnnotationObject(cleanRoi, ensureClass(CLEAN_CLASS))
    cleanAnn.setName(GRANDQC_SENTINEL)
    cleanAnn.setLocked(true)
    hierarchy.addObjects([cleanAnn])
    hierarchy.fireHierarchyUpdate()

    double tissueMm2 = areaMm2(tissueUnion)
    double cleanMm2  = areaMm2(cleanRoi)
    double artMm2    = (!Double.isNaN(tissueMm2) && !Double.isNaN(cleanMm2)) ? Math.max(0.0d, tissueMm2 - cleanMm2) : Double.NaN
    double artPct    = (tissueMm2 > 0 && !Double.isNaN(artMm2)) ? (artMm2 / tissueMm2 * 100.0d) : Double.NaN
    // Parçalanma (bağlı bileşen) — salt sayı/alan; derece/eşik yok.
    def tFrag = fragmentStats(tissueUnion, pw, ph, calibrated)
    def cFrag = fragmentStats(cleanRoi, pw, ph, calibrated)
    return [ok: true, calibrated: calibrated, tissueMm2: tissueMm2, cleanMm2: cleanMm2, artMm2: artMm2, artPct: artPct,
            tissueFrag: tFrag.count, cleanFrag: cFrag.count,
            fragMaxMm2: tFrag.maxMm2, fragMinMm2: tFrag.minMm2, fragMeanMm2: tFrag.meanMm2]
}

// ── Özet metni ──────────────────────────────────────────────────────────────
def buildResultText = { slide, imp, clean ->
    def sb = new StringBuilder()
    sb << "GrandQC KALİTE KONTROL — ÖZET\n"
    sb << "═══════════════════════════════\n\n"
    sb << "Slayt   : " << slide.name << "\n"
    sb << "GeoJSON : " << (imp.file?.getAbsolutePath() ?: '-') << "\n\n"
    sb << "Anotasyon dökümü (sınıf → adet):\n"
    int total = 0
    imp.counts.each { cn, n ->
        sb << String.format(java.util.Locale.US, "  %-30s : %,d%n", cn, n)
        total += n
    }
    sb << String.format(java.util.Locale.US, "  %-30s : %,d%n", '(toplam)', total)
    sb << String.format(java.util.Locale.US, "  atlanan geometri: %,d%n%n", (imp.skipped ?: 0))
    if (clean != null && clean.ok) {
        sb << "Temiz doku (Temiz doku) eklendi.\n"
        if (clean.calibrated) {
            sb << String.format(java.util.Locale.US, "  Doku alanı       : %.3f mm²%n", clean.tissueMm2)
            sb << String.format(java.util.Locale.US, "  Temiz doku alanı : %.3f mm²%n", clean.cleanMm2)
            sb << String.format(java.util.Locale.US, "  Artefakt alanı   : %.3f mm² (doku içi %.1f%%)%n", clean.artMm2, clean.artPct)
        } else {
            sb << "  (Görüntü kalibre değil — alanlar mm² olarak verilemedi.)\n"
        }
        if (clean.tissueFrag != null) {
            sb << String.format(java.util.Locale.US, "  Doku parça sayısı: %,d   (temiz doku parçası: %,d)%n",
                (clean.tissueFrag ?: 0), (clean.cleanFrag ?: 0))
            if (clean.calibrated && (clean.tissueFrag ?: 0) > 0 && !Double.isNaN(clean.fragMeanMm2))
                sb << String.format(java.util.Locale.US, "  Parça alanı (mm²): en büyük %.3f · en küçük %.3f · ortalama %.3f%n",
                    clean.fragMaxMm2, clean.fragMinMm2, clean.fragMeanMm2)
        }
        sb << "\nSonraki analiz modüllerini bu \"Temiz doku\" anotasyonuyla sınırlayabilirsiniz.\n"
    } else {
        sb << "Temiz doku üretilemedi"
        if (clean != null && clean.error) sb << " — " << clean.error
        sb << "\nArtefakt/doku anotasyonları yine de eklendi.\n"
    }
    sb << "\nGrandQC çıktısı bir derin öğrenme tahminidir; görsel olarak doğrulayın (Manuel Düzeltme eki, Yapay Zekâ Araçlarını Değerlendirme eki).\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

// ── Headless: yapılandırmayı raporla, etkileşim yok ─────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println "Önce bir slayt açın."; return }
    def typeName = (imageData.getImageType()?.name() ?: '').toUpperCase(java.util.Locale.ROOT)
    if (!typeName.contains('BRIGHTFIELD_H_E'))
        println "Uyarı: Görüntü tipi H&E değil (${typeName}) — GrandQC H&E için tasarlanmıştır."
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    println "GrandQC yapılandırması: python=${cfg.python ?: '(ayarsız)'} betikDizini=${cfg.scripts ?: '(ayarsız)'} mpp=${cfg.mpp}"
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(', ')}"
    def slide = resolveSlide(imageData)
    println "Slayt klasörü: ${slide.folder ?: '(yerel değil)'}  beklenen GeoJSON: ${slide.geojson?.getAbsolutePath() ?: '-'}"
    println "GrandQC sihirbazı için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    return
}

// ── Durum makinesi ──────────────────────────────────────────────────────────
// CONFIG_INCOMPLETE | CONFIG | READY | CMD_READY | RUNNING | BUSY | RESULT | REPORT | DIAG | ERROR
def stage = null
def step           = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop      = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef   = new java.util.concurrent.atomic.AtomicBoolean(false)
def batchBusyRef   = new java.util.concurrent.atomic.AtomicBoolean(false)   // proje-geneli içe aktarım sürüyor mu (BUSY'de İptal butonu için)
def processRef     = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef     = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef    = new java.util.concurrent.atomic.AtomicReference('')
def busyLabelRef   = new java.util.concurrent.atomic.AtomicReference('')
def cmdTextRef     = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef   = new java.util.concurrent.atomic.AtomicReference('')
def resultFolderRef= new java.util.concurrent.atomic.AtomicReference(null)   // tek-slayt sonucu: rapor bu klasörde aranır (batch'te null)
def reportTextRef  = new java.util.concurrent.atomic.AtomicReference('')     // REPORT durumu metni
def reportPathRef  = new java.util.concurrent.atomic.AtomicReference(null)   // dışa aktarım için rapor dosyası
def diagTextRef    = new java.util.concurrent.atomic.AtomicReference('')     // DIAG durumu metni
// CONFIG düzenleme alanları (Kaydet bunları okur)
def pyFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def scriptsFieldRef= new java.util.concurrent.atomic.AtomicReference(null)
def modelFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def mppChoiceRef   = new java.util.concurrent.atomic.AtomicReference(null)
def deviceChoiceRef= new java.util.concurrent.atomic.AtomicReference(null)   // cuda/mps/cpu/Otomatik
def manualFolderRef= new java.util.concurrent.atomic.AtomicReference(null)   // yerel olmayan slayt için
def installLog     = new StringBuilder()                                     // ①②③ kurulum ilerleme günlüğü
def installLogAreaRef = new java.util.concurrent.atomic.AtomicReference(null)
def installBusyRef = new java.util.concurrent.atomic.AtomicBoolean(false)
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

// Çalışan klasörü çöz — elle seçilen klasör (kapsam) öncelikli, yoksa slayt klasörü.
// GrandQC daima bir KLASÖRDEKİ tüm slaytları işler; "kapsam" = hangi klasör.
def effectiveFolder = { slide ->
    def mf = manualFolderRef.get()
    if (mf != null && mf.toString().trim()) return mf.toString().trim()
    return slide.folder
}

// ── Arka plan: import + temiz doku ──────────────────────────────────────────
def runImportClean = { slide, manualFolder ->
    busyLabelRef.set('GeoJSON içe aktarılıyor…'); step.set('BUSY'); render()
    def worker = new Thread({
        def curData0 = QP.getCurrentImageData()
        if (curData0 == null) {
            javafx.application.Platform.runLater { errorTextRef.set('Açık görüntü yok — içe aktarım için bir slayt açın.'); step.set('ERROR'); render() }
            return
        }
        // Kimlik denetimi: pencere kipsiz (Modality.NONE) ve "Doğrudan çalıştır" koşusu dakikalarca
        // sürebilir; bu arada kullanıcı açık slaydı değiştirebilir. Yakalanan `slide` ≠ şu an açık
        // görüntü ise YANLIŞ hiyerarşiye yazma (batch'teki getID denetiminin tek-slayt karşılığı).
        def curSlide0 = resolveSlide(curData0)
        boolean sameSlide = (slide.file != null && curSlide0.file != null) ?
            (slide.file.getAbsolutePath() == curSlide0.file.getAbsolutePath()) :
            (slide.name == curSlide0.name)
        if (!sameSlide) {
            javafx.application.Platform.runLater {
                errorTextRef.set('Açık görüntü değişti — içe aktarım "' + slide.name + '" için başlatıldı ama şu an "' + curSlide0.name + '" açık.\n\n"' + slide.name + '" slaydını yeniden açıp tekrar deneyin.')
                step.set('ERROR'); render()
            }
            return
        }
        def geojson = findGeoJSON(slide, manualFolder)
        def imp = importGeoJSON(geojson, curData0.getHierarchy())
        if (!imp.ok) {
            javafx.application.Platform.runLater { errorTextRef.set(imp.error); step.set('ERROR'); render() }
            return
        }
        javafx.application.Platform.runLater { busyLabelRef.set('Temiz doku hesaplanıyor…'); render() }
        def clean
        try { clean = computeCleanTissue(curData0, imp.annotations, curData0.getHierarchy()) }
        catch (Throwable t) { clean = [ok: false, error: (t.getMessage() ?: t.getClass().getSimpleName())] }
        javafx.application.Platform.runLater {
            try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
            // Tek-slayt: istatistik raporu kapsam klasöründe (main.py output_dir) aranır.
            resultFolderRef.set(manualFolder?.toString()?.trim() ? manualFolder.toString().trim() : slide.folder)
            resultTextRef.set(buildResultText(slide, imp, clean))
            step.set('RESULT'); render()
        }
    }, 'AtolyeGrandQC-Import')
    worker.setDaemon(true); worker.start()
}

// ── Kısa yardımcı süreç (ortam doğrulama için) — birleşik çıktıyı toplar ─────
def runQuick = { List cmd, long tSec ->
    def pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true)
    def proc
    try { proc = pb.start() }
    catch (Throwable e) { return [ok: false, out: 'başlatılamadı: ' + (e.getMessage() ?: e.getClass().getSimpleName())] }
    def sb = new StringBuilder()
    try {
        def reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
        String line; while ((line = reader.readLine()) != null) { sb.append(line).append('\n') }
        reader.close()
    } catch (Throwable ignore) {}
    boolean fin
    try { fin = proc.waitFor(tSec, java.util.concurrent.TimeUnit.SECONDS) }
    catch (InterruptedException ie) { proc.destroyForcibly(); return [ok: false, out: sb.toString() + '(kesildi)'] }
    if (!fin) { proc.destroyForcibly(); return [ok: false, out: sb.toString() + '(zaman aşımı)'] }
    return [ok: (proc.exitValue() == 0), out: sb.toString()]
}

// ── Ön uçuş: ortam doğrulama (python + paketler + model dosyaları) — SALT bilgi ──
def runDiag = { ->
    def cfg = loadConfig()
    busyLabelRef.set('Ortam doğrulanıyor…'); step.set('BUSY'); render()
    def worker = new Thread({
        def sb = new StringBuilder()
        sb << "GrandQC ORTAM DOĞRULAMA\n"
        sb << "═══════════════════════════════\n\n"
        def py = cfg.python
        if (!py?.trim() || !(new File(py)).isFile()) {
            sb << "✗ python.exe : ayarlı değil ya da bulunamadı\n"
        } else {
            sb << "✓ python.exe : " << py << "\n"
            def v = runQuick([py, '--version'], 20L)
            sb << "  sürüm : " << ((v.out ?: '').trim() ?: '(okunamadı)') << "\n\n"
            sb << "Paketler / cihaz (import testi, ~torch soğuk başlatma nedeniyle biraz sürebilir):\n"
            def imp = runQuick([py, '-c', PY_DIAG], 120L)
            (imp.out ?: '').readLines().each { sb << it << "\n" }
        }
        def md = cfg.modelDir?.trim() ? new File(cfg.modelDir) : (cfg.scripts?.trim() ? new File(cfg.scripts, 'models') : null)
        sb << "\n"
        if (md == null) {
            sb << "✗ model dizini belirsiz (betik/model dizini ayarlı değil)\n"
        } else {
            def countPth = { File d -> (d.isDirectory() ? (((d.listFiles({ f, n -> n.toLowerCase(java.util.Locale.ROOT).endsWith('.pth') } as java.io.FilenameFilter))?.length) ?: 0) : 0) }
            int td = countPth(new File(md, 'td')); int qc = countPth(new File(md, 'qc'))
            sb << ((td > 0 && qc > 0) ? "✓ " : "✗ ") << String.format(java.util.Locale.US, "model dosyaları : models/td %d .pth · models/qc %d .pth%n", td, qc)
        }
        sb << "\nBu kontrol yalnız bilgilendiricidir; çalıştırmayı engellemez.\n"
        sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
        def txt = sb.toString()
        javafx.application.Platform.runLater { diagTextRef.set(txt); step.set('DIAG'); render() }
    }, 'AtolyeGrandQC-Diag')
    worker.setDaemon(true); worker.start()
}

// ── Proje geneli içe aktarım (batch) — HER girdinin KENDİ GeoJSON'unu içe aktarır ──
// tiatoolbox-bolge kalıbı: canlı veriyi yalnız gerçekten o girdiyse kullan (getEntry/getID),
// diğerlerini readImageData → import → temiz doku → saveImageData → server.close().
// GeoJSON çözümü YALNIZ tam ad (exactGeoJSON) — yanlış slayda yazmayı önler.
def runBatchImport = { manualFolder ->
    def project = QP.getProject()
    if (project == null) { errorTextRef.set('Proje açık değil — proje geneli içe aktarım için bir QuPath projesi açın.'); step.set('ERROR'); render(); return }
    def entries = project.getImageList()
    if (entries == null || entries.isEmpty()) { errorTextRef.set('Projede görüntü yok.'); step.set('ERROR'); render(); return }
    cancelledRef.set(false); batchBusyRef.set(true)   // cancelledRef Python-iptaliyle paylaşılır → burada sıfırla
    busyLabelRef.set('Proje geneli GeoJSON içe aktarılıyor…'); step.set('BUSY'); render()
    def worker = new Thread({
        int okN = 0, skipN = 0, failN = 0
        def lines = []
        boolean currentTouched = false
        boolean cancelled = false
        int idx = 0
        for (entry in entries) {
            idx++
            if (cancelledRef.get()) { cancelled = true; lines << '  ⏹ (iptal edildi — kalan görüntüler işlenmedi)'; break }
            def nm = null
            try { nm = entry.getImageName() } catch (Throwable ignore) {}
            if (nm == null) nm = ('görüntü ' + idx)
            javafx.application.Platform.runLater { busyLabelRef.set('Proje ' + idx + '/' + entries.size() + ' — ' + nm + '…'); render() }
            // Canlı veriyi YALNIZ gerçekten bu girdiyse kullan (referans yakalandıktan sonra sabittir).
            def liveData = null
            try {
                def liveCur = QP.getCurrentImageData()
                if (liveCur != null) {
                    def liveEntry = project.getEntry(liveCur)
                    if (liveEntry != null && liveEntry.getID() != null && liveEntry.getID() == entry.getID()) liveData = liveCur
                }
            } catch (Throwable ignore) {}
            def data = null; boolean opened = false
            try {
                data = (liveData != null) ? liveData : entry.readImageData()
                opened = (liveData == null)
                def slide = resolveSlide(data)
                // SADECE tam ad: önce slaydın kendi klasörü, sonra (varsa) elle kapsam klasörü.
                def gj = exactGeoJSON(slide, slide.folder)
                if (gj == null && manualFolder != null && manualFolder.toString().trim())
                    gj = exactGeoJSON(slide, manualFolder.toString().trim())
                if (gj == null) { skipN++; lines << ('  • ' + nm + ' — atlandı (GeoJSON yok)'); continue }
                def hier = data.getHierarchy()
                def imp = importGeoJSON(gj, hier)
                if (!imp.ok) { failN++; lines << ('  ✗ ' + nm + ' — ' + (imp.error ?: '').readLines().take(1).join(' ')); continue }
                // Temiz doku başarısız olsa bile doku/artefakt anotasyonları KAYDEDİLİR (tek-slayt yolu da böyle).
                def clean = null
                try { clean = computeCleanTissue(data, imp.annotations, hier) }
                catch (Throwable ct) { clean = [ok: false, error: (ct.getMessage() ?: ct.getClass().getSimpleName())] }
                boolean saved = true
                try { entry.saveImageData(data) }
                catch (Throwable se) { saved = false; failN++; lines << ('  ✗ ' + nm + ' — kaydedilemedi: ' + (se.getMessage() ?: se.getClass().getSimpleName())) }
                if (saved) {
                    okN++
                    def cleanNote = (clean != null && !clean.ok) ? (' [Temiz doku üretilemedi: ' + (clean.error ?: '-') + ']') : ''
                    lines << ('  ✓ ' + nm + ' — ' + imp.annotations.size() + ' nesne' + cleanNote)
                    if (liveData != null) currentTouched = true
                }
            } catch (Throwable t) {
                failN++; lines << ('  ✗ ' + nm + ' — ' + (t.getMessage() ?: t.getClass().getSimpleName()))
            } finally {
                try { if (opened && data != null) data.getServer()?.close() } catch (Throwable ignore) {}
            }
        }
        batchBusyRef.set(false)
        javafx.application.Platform.runLater {
            if (currentTouched) { try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {} }
            def rb = new StringBuilder()
            rb << "GrandQC — PROJE GENELİ İÇE AKTARIM" << (cancelled ? " (İPTAL EDİLDİ)" : "") << "\n"
            rb << "═══════════════════════════════\n\n"
            rb << String.format(java.util.Locale.US, "Görüntü: %,d   içe aktarıldı: %,d   atlandı: %,d   hata: %,d%n%n", entries.size(), okN, skipN, failN)
            lines.each { rb << it << "\n" }
            rb << "\nHer görüntünün GeoJSON'u KENDİ klasöründeki geojson_qc/ içinde TAM ADLA arandı.\n"
            rb << (currentTouched ? "Açık slaydın TÜM güncel hâli diske kaydedildi (yalnız GrandQC nesneleri değil); görüntüleyici yenilendi.\n"
                                  : "Açık slaytta değişiklik olduysa görmek için slaydı yeniden açın.\n")
            rb << "\nGrandQC çıktısı bir derin öğrenme tahminidir; görsel olarak doğrulayın.\n"
            rb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
            resultFolderRef.set(null)   // batch: çok klasör olabilir → tek rapor butonu gösterme
            resultTextRef.set(rb.toString()); step.set('RESULT'); render()
        }
    }, 'AtolyeGrandQC-BatchImport')
    worker.setDaemon(true); worker.start()
}

// ── Atölye veri kökü (env yöneticisiyle PAYLAŞILAN) ─────────────────────────
// Öntanımlı ~/.atolye; kullanıcı env yöneticisinden başka bir sürücü seçebilir
// (C: dolmasın diye). GrandQC deposu + model ağırlıkları + venv bu köke yazılır.
// NOT: runPython'dan ÖNCE tanımlanmalı — Groovy closure'ı daha sonra tanımlanan
// bir def-closure'ı yakalayamaz (aksi hâlde MissingMethodException: applyCacheEnv).
def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
// Model önbelleklerini (torch hub / HF timm) de veri köküne yönlendir; smp+timm
// kodlayıcı ağırlıklarını torch/HF hub'dan indirir → varsayılan ~/.cache (C:).
def applyCacheEnv = { pb ->
    try {
        def cache = new File(atolyeDataRoot(), 'cache'); cache.mkdirs()
        def hf = new File(cache, 'huggingface'); def env = pb.environment()
        env.put('HF_HOME', hf.getAbsolutePath()); env.put('HF_HUB_CACHE', new File(hf, 'hub').getAbsolutePath())
        env.put('TORCH_HOME', new File(cache, 'torch').getAbsolutePath())
    } catch (Throwable ignore) {}
}

// ── Arka plan: Python hattı (ProcessBuilder) → import ───────────────────────
def runPython = { List cmd, Closure onLine ->
    def pb = new ProcessBuilder(cmd)
    pb.redirectErrorStream(true)
    applyCacheEnv(pb)
    def proc
    try { proc = pb.start() }
    catch (Throwable e) { return [ok: false, exitCode: -1, error: 'Python başlatılamadı: ' + (e.getMessage() ?: e.getClass().getSimpleName())] }
    processRef.set(proc)
    def last = new java.util.ArrayDeque()
    try {
        def reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
        String line
        while ((line = reader.readLine()) != null) {
            last.addLast(line); while (last.size() > 40) last.pollFirst()
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

def startDirectRun = { slide ->
    def cfg = loadConfig()
    def folder = effectiveFolder(slide)
    if (folder == null) {
        errorTextRef.set('Slayt yerel diskte değil ve klasör elle girilmedi.\nKomut üret ekranında klasörü elle belirtin.')
        step.set('ERROR'); render(); return
    }
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea()
    la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Doku tespiti (1/2)'); step.set('RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln ->
            javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') }
        }
        // GrandQC betikleri cihazı kaynakta sabitler — çalıştırmadan önce yamala.
        def dev = effectiveDevice(cfg)
        def pd = patchDevice(cfg.scripts, dev)
        if (pd.patched) appendLine('# Cihaz: ' + dev + '  (yamalandı: ' + pd.patched.join(', ') + ')')
        else appendLine('# Cihaz: ' + dev + '  (DEVICE satırı zaten uygun ya da bulunamadı)')
        if (pd.errors) appendLine('# ⚠ Cihaz yaması BAŞARISIZ — betik dosyaları yazılamadı; DEVICE hâlâ eski değerde olabilir:\n#   ' + pd.errors.join('\n#   '))
        def r1 = runPython(tissueCmd(cfg, folder), appendLine)
        if (!r1.ok) {
            javafx.application.Platform.runLater {
                errorTextRef.set('Doku tespiti başarısız (çıkış kodu: ' + r1.exitCode + ')\n\n' + (r1.error ?: '') + '\n' + (r1.lastLines ?: ''))
                step.set('ERROR'); render()
            }
            return
        }
        javafx.application.Platform.runLater { runPhaseRef.set('Artefakt KK (2/2)'); render() }
        def r2 = runPython(artifactCmd(cfg, folder), appendLine)
        if (!r2.ok) {
            javafx.application.Platform.runLater {
                errorTextRef.set('Artefakt KK başarısız (çıkış kodu: ' + r2.exitCode + ')\n\n' + (r2.error ?: '') + '\n' + (r2.lastLines ?: ''))
                step.set('ERROR'); render()
            }
            return
        }
        javafx.application.Platform.runLater { runImportClean(slide, folder) }
    }, 'AtolyeGrandQC-Run')
    worker.setDaemon(true); worker.start()
}

def saveConfig = {
    def py = pyFieldRef.get(); def sc = scriptsFieldRef.get(); def md = modelFieldRef.get(); def mp = mppChoiceRef.get(); def dv = deviceChoiceRef.get()
    prefs.put(PREF_PYTHON,  (py != null ? py.getText() : '').trim())
    prefs.put(PREF_SCRIPTS, (sc != null ? sc.getText() : '').trim())
    prefs.put(PREF_MODEL,   (md != null ? md.getText() : '').trim())
    prefs.put(PREF_MPP,     (mp != null && mp.getValue() != null) ? mp.getValue() : '1.5')
    def dvVal = (dv != null && dv.getValue() != null) ? dv.getValue().toString() : ''
    prefs.put(PREF_DEVICE,  ['cuda', 'mps', 'cpu'].contains(dvVal) ? dvVal : '')   // Otomatik = boş
    try { prefs.flush() } catch (Throwable ignore) {}
    step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render()
}

// ── İlgili pencere/belge açıcılar (config ekranındaki butonlar) ─────────────
// Paketli başka bir Atölye betiğini (ör. Python ortam yöneticisi) kendi penceresinde açar.
def launchBundledScript = { String resourceName ->
    new Thread({
        try {
            def url = null
            try { url = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getResource('/scripts/' + resourceName) } catch (Throwable t) {}
            if (url == null) url = this.getClass().getResource('/scripts/' + resourceName)
            if (url == null) {
                javafx.application.Platform.runLater { Dialogs.showInfoNotification('Betik bulunamadı',
                    'Menüden açın: Extensions → Atölye → Yardımcılar → Python köprüleri & temel modeller → Atölye Python ortam yöneticisi') }
                return
            }
            def cl = this.getClass().getClassLoader()
            try { cl = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getClassLoader() } catch (Throwable t) {}
            new GroovyShell(cl).evaluate(url.getText('UTF-8'), resourceName)
        } catch (Throwable t) {
            javafx.application.Platform.runLater { Dialogs.showErrorMessage('Açılamadı', (t.getMessage() ?: t.getClass().getSimpleName())) }
        }
    } as Runnable).start()
}
def openUrl = { String u -> try { qupath.lib.gui.QuPathGUI.openInBrowser(u) } catch (Throwable t) {} }


// ── GrandQC dosya/model kaynakları (WebFetch ile doğrulandı 2026-07) ─────────
def grandqcBase       = { -> new File(atolyeDataRoot(), 'grandqc') }
def GRANDQC_REPO_ZIP  = 'https://github.com/cpath-ukk/grandqc/archive/refs/heads/main.zip'
def GRANDQC_ZIP_TOP   = 'grandqc-main'
def GRANDQC_SCRIPTSUB = '01_WSI_inference_OPENSLIDE_QC'   // wsi_tis_detect.py & main.py & models/ burada
def GRANDQC_MODELS = [
    [url:'https://zenodo.org/records/14507273/files/Tissue_Detection_MPP10.pth?download=1', sub:'td', name:'Tissue_Detection_MPP10.pth'],
    [url:'https://zenodo.org/records/14041538/files/GrandQC_MPP1.pth?download=1',  sub:'qc', name:'GrandQC_MPP1.pth'],
    [url:'https://zenodo.org/records/14041538/files/GrandQC_MPP15.pth?download=1', sub:'qc', name:'GrandQC_MPP15.pth'],
    [url:'https://zenodo.org/records/14041538/files/GrandQC_MPP2.pth?download=1',  sub:'qc', name:'GrandQC_MPP2.pth'],
]
def grandqcScriptDir  = { -> new File(new File(grandqcBase(), GRANDQC_ZIP_TOP), GRANDQC_SCRIPTSUB) }
def grandqcVenvPython = { ->
    // 1) Env yöneticisinin kaydettiği KESİN yol (veri kökü değişse bile doğru).
    try {
        def rec = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('py.grandqc', '')
        if (rec?.trim()) { def rf = new File(rec.trim()); if (rf.isFile()) return rf }
    } catch (Throwable ignore) {}
    // 2) Yedek: veri kökünden tahmin et.
    def v = new File(new File(new File(atolyeDataRoot(), 'runtimes'), 'grandqc'), '.venv')
    def w = new File(v, 'Scripts/python.exe'); def n = new File(v, 'bin/python')
    return w.isFile() ? w : (n.isFile() ? n : null)
}
def installLogSnapshot = { -> synchronized (installLog) { return installLog.toString() } }
def appendInstallLog = { String line ->
    String snap
    synchronized (installLog) { installLog.append(line).append('\n'); snap = installLog.toString() }
    javafx.application.Platform.runLater {
        def a = installLogAreaRef.get()
        if (a != null) { a.setText(snap); a.setScrollTop(Double.MAX_VALUE) }
    }
}
// Yönlendirmeli HTTPS indirici (Zenodo → S3, GitHub → codeload'a yönlendirir).
// Yalnız https'e izin verir (indirme düşürme saldırısını engeller); göreli Location'ı çözer;
// Content-Length ile karşılaştırıp eksik indirmeyi yakalar; hata/yarım dosyayı siler.
def httpDownload = { String urlStr, File dest ->
    if (dest.getParentFile() != null) dest.getParentFile().mkdirs()
    String cur = urlStr; int hops = 0
    while (true) {
        if (hops++ > 8) throw new RuntimeException('Çok fazla yönlendirme')
        def base = new java.net.URL(cur)
        if (!'https'.equalsIgnoreCase(base.getProtocol()))
            throw new RuntimeException('Güvensiz (https değil) bağlantı reddedildi: ' + cur)
        def conn = (java.net.HttpURLConnection) base.openConnection()
        conn.setInstanceFollowRedirects(false)
        conn.setConnectTimeout(30000); conn.setReadTimeout(120000)
        conn.setRequestProperty('User-Agent', 'atolye-grandqc-installer')
        int code
        try { code = conn.getResponseCode() } catch (Throwable t) { conn.disconnect(); throw t }
        if (code >= 300 && code < 400) {
            def loc = conn.getHeaderField('Location'); conn.disconnect()
            if (!loc) throw new RuntimeException('Yönlendirme konumu yok')
            cur = new java.net.URL(base, loc).toString()   // göreli Location'ı taban URL'ye göre çöz
            continue
        }
        if (code != 200) { conn.disconnect(); throw new RuntimeException('HTTP ' + code + ' — ' + cur) }
        long total = conn.getContentLengthLong()
        boolean ok = false
        try {
            def ins = conn.getInputStream()
            try {
                def os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(dest))
                long done = 0
                try {
                    byte[] buf = new byte[65536]; long last = 0; int r
                    while ((r = ins.read(buf)) > 0) {
                        os.write(buf, 0, r); done += r
                        if (done - last >= 4_000_000L) { last = done
                            appendInstallLog(String.format(java.util.Locale.US, '    %.1f MB%s', done / 1048576.0d, total > 0 ? String.format(java.util.Locale.US, ' / %.1f MB', total / 1048576.0d) : '')) }
                    }
                } finally { os.close() }
                if (total > 0 && done != total)
                    throw new RuntimeException('Eksik indirme (' + done + '/' + total + ' bayt): ' + dest.getName())
                ok = true
            } finally { ins.close() }
        } finally {
            conn.disconnect()
            if (!ok) { try { dest.delete() } catch (Throwable t) {} }   // yarım/bozuk dosyayı sil
        }
        return
    }
}
// Zip-slip korumalı çıkarıcı
def unzipTo = { File zipFile, File destDir ->
    destDir.mkdirs()
    def destCanon = destDir.getCanonicalPath()
    def zis = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(zipFile)))
    try {
        def e; int cnt = 0
        while ((e = zis.getNextEntry()) != null) {
            def out = new File(destDir, e.getName())
            def oc = out.getCanonicalPath()
            if (oc != destCanon && !oc.startsWith(destCanon + File.separator)) throw new RuntimeException('Güvensiz zip girdisi: ' + e.getName())
            if (e.isDirectory()) out.mkdirs()
            else {
                if (out.getParentFile() != null) out.getParentFile().mkdirs()
                def os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(out))
                try { byte[] b = new byte[65536]; int r; while ((r = zis.read(b)) > 0) os.write(b, 0, r) } finally { os.close() }
                cnt++
            }
            zis.closeEntry()
        }
        appendInstallLog('    ' + cnt + ' dosya açıldı')
    } finally { zis.close() }
}
// ② GrandQC deposunu indir + çıkar + betik dizinini otomatik ayarla
def installRepo = {
    if (installBusyRef.getAndSet(true)) { appendInstallLog('(Kurulum sürüyor — bekleyin.)'); return }
    new Thread({
        try {
            appendInstallLog(''); appendInstallLog('② GrandQC deposu indiriliyor…'); appendInstallLog('    ' + GRANDQC_REPO_ZIP)
            def gqBase = grandqcBase(); gqBase.mkdirs()
            def zipF = new File(gqBase, 'grandqc-main.zip')
            httpDownload(GRANDQC_REPO_ZIP, zipF)
            appendInstallLog('    açılıyor → ' + gqBase.getAbsolutePath())
            unzipTo(zipF, gqBase)
            try { zipF.delete() } catch (Throwable t) {}
            def sd = grandqcScriptDir()
            if (new File(sd, 'wsi_tis_detect.py').isFile()) {
                prefs.put(PREF_SCRIPTS, sd.getAbsolutePath()); try { prefs.flush() } catch (Throwable t) {}
                appendInstallLog('✓ Betik dizini ayarlandı: ' + sd.getAbsolutePath())
            } else appendInstallLog('⚠ wsi_tis_detect.py beklenen yerde yok: ' + sd.getAbsolutePath())
            javafx.application.Platform.runLater { if (step.get() == 'CONFIG_INCOMPLETE') { step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() } }
        } catch (Throwable t) {
            appendInstallLog('✗ Depo indirilemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName()))
            appendInstallLog('   Elle indirin: ' + GRANDQC_REPO_ZIP)
        } finally { installBusyRef.set(false) }
    } as Runnable).start()
}
// ③ Model ağırlıklarını indir → betik dizinindeki models/td, models/qc
def installModels = {
    if (installBusyRef.getAndSet(true)) { appendInstallLog('(Kurulum sürüyor — bekleyin.)'); return }
    new Thread({
        try {
            def sd = loadConfig().scripts?.trim() ? new File(loadConfig().scripts) : grandqcScriptDir()
            if (!new File(sd, 'wsi_tis_detect.py').isFile()) {
                appendInstallLog(''); appendInstallLog('⚠ Önce ② ile GrandQC deposunu indirin (modeller repo içine yerleşir).'); return
            }
            def modelsRoot = new File(sd, 'models')
            appendInstallLog(''); appendInstallLog('③ Model ağırlıkları indiriliyor (' + GRANDQC_MODELS.size() + ' dosya, ~100 MB)…')
            for (m in GRANDQC_MODELS) {
                def dest = new File(new File(modelsRoot, m.sub), m.name)
                appendInstallLog('  ' + m.name + ' → models/' + m.sub + '/')
                httpDownload(m.url, dest)
            }
            prefs.put(PREF_MODEL, modelsRoot.getAbsolutePath()); try { prefs.flush() } catch (Throwable t) {}
            appendInstallLog('✓ Modeller indirildi; model dizini: ' + modelsRoot.getAbsolutePath())
            javafx.application.Platform.runLater { if (step.get() == 'CONFIG_INCOMPLETE') { step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() } }
        } catch (Throwable t) {
            appendInstallLog('✗ Model indirilemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName()))
        } finally { installBusyRef.set(false) }
    } as Runnable).start()
}

// ── Render: her durum değişiminde sahneyi sıfırdan kurar ────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def imageData = QP.getCurrentImageData()
    def cfg = loadConfig()
    // ① Ortam yöneticisi GrandQC venv'ini kurmuşsa python'u kalıcı olarak otomatik doldur.
    if (!cfg.python?.trim()) {
        def vp = grandqcVenvPython()
        if (vp != null) { prefs.put(PREF_PYTHON, vp.getAbsolutePath()); try { prefs.flush() } catch (Throwable t) {}; cfg = loadConfig() }
    }

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10)
    center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)
    def actions = new ArrayList()

    def addGuidance = { String txt ->
        def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); lbl.setMaxWidth(Double.MAX_VALUE); center.getChildren().add(lbl)
    }
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

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('GrandQC yapılandırması gerekli')
        def miss = configMissing(cfg)
        addGuidance('GrandQC ÜÇ ayrı kurulum adımı gerektirir; aşağıdaki ①②③ butonları bu adımları ÇALIŞTIRIR ' +
            '(indirir/kurar). Eksik/geçersiz:\n  • ' + (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')))
        def gqPy = grandqcVenvPython()   // kayıtlı KESİN yol varsa onu göster, yoksa tahmin
        def resArea = new javafx.scene.control.TextArea(
            'KAYNAKLAR — ne · nereden · nereye:\n' +
            '① Python ortamı : ' + (gqPy != null ? gqPy.getAbsolutePath() + '  (kurulu)' :
                'uv venv + paketler → ' + new File(new File(atolyeDataRoot(), 'runtimes'), 'grandqc/.venv').getAbsolutePath()) + '\n' +
            '   (buton Atölye Python ortam yöneticisini açar; "GrandQC"yi kurun — python otomatik algılanır)\n' +
            '   (veri kökünü değiştirmek için ortam yöneticisindeki "Veri kökü → Değiştir…")\n' +
            '② GrandQC deposu: ' + GRANDQC_REPO_ZIP + '\n' +
            '   → ' + grandqcScriptDir().getAbsolutePath() + '\n' +
            '③ Model (Zenodo · CC BY-NC-SA 4.0):\n' +
            '   • Tissue_Detection_MPP10.pth  (~27 MB) → models/td/   [zenodo 14507273]\n' +
            '   • GrandQC_MPP1 / 15 / 2.pth   (3×~25 MB) → models/qc/  [zenodo 14041538]')
        resArea.setEditable(false); resArea.setWrapText(false); resArea.setStyle(MONO); resArea.setPrefRowCount(9); resArea.setMaxHeight(190)
        center.getChildren().add(resArea)
        actions.add(navButton('① Python ortamı', { launchBundledScript('yardimci-python-ortam-yoneticisi.groovy') },
            'Atölye Python ortam yöneticisini açar → "GrandQC"yi kurun; python otomatik algılanır'))
        actions.add(navButton('② GrandQC deposu indir', { installRepo() },
            'GitHub ZIP indirir + açar, betik dizinini otomatik ayarlar'))
        actions.add(navButton('③ Model indir', { installModels() },
            'Zenodo model ağırlıklarını models/td & models/qc içine indirir (önce ②)'))
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
        def ilog = new javafx.scene.control.TextArea(installLogSnapshot())
        ilog.setEditable(false); ilog.setWrapText(false); ilog.setStyle(MONO); ilog.setPrefRowCount(7)
        javafx.scene.layout.VBox.setVgrow(ilog, javafx.scene.layout.Priority.ALWAYS)
        installLogAreaRef.set(ilog); center.getChildren().add(ilog)
    } else if (cur == 'CONFIG') {
        title.setText('GrandQC yapılandırması')
        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def scField = new javafx.scene.control.TextField(cfg.scripts ?: '')
        def mdField = new javafx.scene.control.TextField(cfg.modelDir ?: '')
        pyField.setPrefColumnCount(34); scField.setPrefColumnCount(34); mdField.setPrefColumnCount(34)
        def mppChoice = new javafx.scene.control.ChoiceBox()
        MPP_OPTIONS.each { mppChoice.getItems().add(it) }
        mppChoice.setValue(MPP_OPTIONS.contains(cfg.mpp) ? cfg.mpp : '1.5')
        def detDev = detectAccelerator()
        def deviceChoice = new javafx.scene.control.ChoiceBox()
        ['Otomatik (' + detDev + ')', 'cuda', 'mps', 'cpu'].each { deviceChoice.getItems().add(it) }
        deviceChoice.setValue(['cuda', 'mps', 'cpu'].contains(cfg.device) ? cfg.device : ('Otomatik (' + detDev + ')'))
        pyFieldRef.set(pyField); scriptsFieldRef.set(scField); modelFieldRef.set(mdField); mppChoiceRef.set(mppChoice); deviceChoiceRef.set(deviceChoice)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Dosya seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir  = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Dizin seç', null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('GrandQC betik dizini:'), scField, navButton('…', { browseDir(scField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Model dizini (ops.):'), mdField, navButton('…', { browseDir(mdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Artefakt MPP modeli:'), mppChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Cihaz (hızlandırıcı):'), deviceChoice)
        center.getChildren().add(grid)
        addGuidance('Betik dizini wsi_tis_detect.py + main.py içermeli. Model dizini doku/artefakt .pth dosyalarını içerir.\nCihaz: GrandQC betikleri cihazı kaynakta sabitler; seçiminiz çalıştırmadan önce .py dosyalarına yamalanır. CUDA yoksa cpu seçin (yavaş ama çalışır).')
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('Ortamı doğrula', { runDiag() }, 'python + paketler (torch/cv2/openslide) + model dosyalarını kontrol eder (yalnız bilgi)'))
        actions.add(navButton('Kaydet ▶', { saveConfig() }))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText('Görüntü açık değil')
            addGuidance('Önce bir H&E slaydını açın, sonra "⟳ Yenile".')
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
        } else {
            def slide = resolveSlide(imageData)
            def scopeFolder = effectiveFolder(slide)
            boolean overridden = (manualFolderRef.get() != null && manualFolderRef.get().toString().trim())
            def typeName = (imageData.getImageType()?.name() ?: '').toUpperCase(java.util.Locale.ROOT)
            boolean isHE = typeName.contains('BRIGHTFIELD_H_E')
            title.setText('GrandQC — hazır')
            def sb = new StringBuilder()
            sb << "Slayt        : " << slide.name << "\n"
            sb << "Kapsam       : " << (scopeFolder ?: '(yerel disk değil — klasör seçin)')
            sb << (overridden ? '   [seçilen klasör]' : (scopeFolder != null ? '   [slaytın klasörü]' : '')) << "\n"
            sb << "Python       : " << (cfg.python ?: '(ayarsız)') << "\n"
            sb << "MPP modeli   : " << cfg.mpp << "\n"
            sb << "Cihaz        : " << effectiveDevice(cfg) << (cfg.device?.trim() ? '' : ' (otomatik)') << "\n"
            def gj = findGeoJSON(slide, scopeFolder)
            sb << "GeoJSON      : " << ((gj != null && gj.isFile()) ? ('mevcut — ' + gj.getName()) : 'yok (henüz çalıştırılmadı)') << "\n"
            addMonoArea(sb.toString())
            addGuidance('Kapsam: GrandQC seçili KLASÖRDEKİ tüm slaytları işler (tek slayt / anotasyon modu yoktur). ' +
                        'Öntanımlı olarak açık slaydın klasörü kullanılır; tüm projeyi/başka bir klasörü işlemek için "Klasör seç…".')
            if (!isHE) addWarnLabel('⚠ Görüntü tipi H&E değil (' + typeName + '). GrandQC H&E için tasarlanmıştır; yine de deneyebilirsiniz.')
            boolean canRun = configComplete(cfg) && (scopeFolder != null)
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Klasör seç…', {
                def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'İşlenecek slayt klasörü (kapsam)', scopeFolder != null ? new File(scopeFolder) : null)
                if (x != null) { manualFolderRef.set(x.getAbsolutePath()); render() }
            }, 'İşlenecek klasörü değiştir — o klasördeki tüm slaytlar işlenir'))
            if (overridden)
                actions.add(navButton('↺ Slaytın klasörü', { manualFolderRef.set(null); render() }, 'Kapsamı açık slaydın klasörüne döndür'))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('Komut üret ▶', { cmdTextRef.set(cmdText(cfg, (effectiveFolder(slide) ?: '<slayt-klasörü>'), slide.name)); step.set('CMD_READY'); render() }))
            if (gj != null && gj.isFile())
                actions.add(navButton('GeoJSON içe aktar', { runImportClean(slide, effectiveFolder(slide)) }, 'Mevcut GrandQC GeoJSON çıktısını içe aktarır'))
            def runBtn = navButton('Doğrudan çalıştır ▶', { startDirectRun(slide) }, 'Python hattını QuPath içinden çalıştırır (venv gerekli)')
            runBtn.setDisable(!canRun)
            actions.add(runBtn)
            // Proje geneli içe aktarım — Python/model gerektirmez (yalnız var olan GeoJSON'u okur).
            // Yüksek etki alanı (tüm proje + diske kayıt) → onay iste.
            if (QP.getProject() != null)
                actions.add(navButton('Proje geneli içe aktar', {
                    int nImg = 0
                    try { nImg = QP.getProject().getImageList().size() } catch (Throwable ignore) {}
                    boolean go = Dialogs.showConfirmDialog('Proje geneli içe aktarım',
                        'Projedeki ' + nImg + ' görüntünün her biri için var olan GrandQC GeoJSON\'u içe aktarılacak:\n\n' +
                        '• Her görüntüdeki önceki "GrandQC KK" anotasyonları (varsa elle düzeltmeler dahil) silinip yeniden üretilir.\n' +
                        '• Sonuç HER görüntü için diske KAYDEDİLİR (geri alınamaz); açık slaydın tüm güncel hâli de kaydedilir.\n\n' +
                        'Devam edilsin mi?')
                    if (go) runBatchImport(manualFolderRef.get())
                }, 'Projedeki TÜM görüntüler için her birinin KENDİ klasöründeki GeoJSON çıktısını içe aktarır ve diske kaydeder (tam ad eşleşmesi)'))
        }
    } else if (cur == 'CMD_READY') {
        title.setText('GrandQC komut satırları')
        addGuidance('Aşağıdaki iki komutu bir terminalde (venv etkin) sırayla çalıştırın; sonra "GeoJSON içe aktar".')
        addMonoArea(cmdTextRef.get())
        def slide = (imageData != null) ? resolveSlide(imageData) : null
        actions.add(navButton('◀ Geri', { step.set('READY'); render() }))
        actions.add(navButton('Kopyala', { copyToClipboard(cmdTextRef.get()) }))
        if (slide != null) {
            actions.add(navButton('GeoJSON içe aktar', { runImportClean(slide, effectiveFolder(slide)) }))
            if (configComplete(cfg) && effectiveFolder(slide) != null)
                actions.add(navButton('Doğrudan çalıştır ▶', { startDirectRun(slide) }))
        }
    } else if (cur == 'RUNNING') {
        title.setText(runPhaseRef.get() + ' çalışıyor…')
        addGuidance('Python hattı koşuyor. Çıktı aşağıda akıyor. Zaman aşımı: ' + PYTHON_TIMEOUT_SECONDS + ' sn.')
        center.getChildren().add(busyBar())
        def la = logAreaRef.get()
        if (la != null) {
            javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS)
            center.getChildren().add(la)
        }
        actions.add(navButton('İptal et', {
            cancelledRef.set(true)
            try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {}
        }))
    } else if (cur == 'BUSY') {
        title.setText(busyLabelRef.get())
        addGuidance('Lütfen bekleyin…')
        center.getChildren().add(busyBar())
        // Proje geneli içe aktarım uzun sürebilir → durdurma imkânı (işlenmiş görüntüler kaydedilmiş kalır).
        if (batchBusyRef.get())
            actions.add(navButton('İptal et', { cancelledRef.set(true) }, 'Proje geneli içe aktarımı durdur — o ana dek işlenen görüntüler kaydedilmiştir'))
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅')
        addMonoArea(resultTextRef.get())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        // İstatistik raporu yalnız tek-slayt sonucunda (kapsam klasörü bilinir) gösterilir.
        def rf = resultFolderRef.get()
        def reportFile = (rf != null) ? findReportFile(rf.toString()) : null
        if (reportFile != null)
            actions.add(navButton('Rapor görüntüle', {
                String rtxt
                try { rtxt = reportFile.getText('UTF-8') }
                catch (Throwable t) { rtxt = 'Rapor okunamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName()) }
                reportPathRef.set(reportFile)
                reportTextRef.set('Dosya: ' + reportFile.getAbsolutePath() + '\n\n' + rtxt)
                step.set('REPORT'); render()
            }, 'GrandQC per-slayt istatistik raporu (büyütme/MPP, karo sayısı, boyut, süre)'))
        actions.add(navButton('↻ Yeniden çalıştır', { step.set('READY'); render() }))
    } else if (cur == 'REPORT') {
        title.setText('GrandQC istatistik raporu')
        addGuidance('main.py çıktısı: slayt başına büyütme/MPP, karo sayıları, görüntü boyutu ve işlem süresi. Salt okunur — kalite notu/derece üretmez.')
        addMonoArea(reportTextRef.get())
        actions.add(navButton('◀ Geri', { step.set('RESULT'); render() }))
        actions.add(navButton('Kopyala', { copyToClipboard(reportTextRef.get()) }))
        actions.add(navButton('Dışa aktar…', {
            def src = reportPathRef.get()
            def suggested = new File(src != null ? src.getName() : 'grandqc_rapor.txt')
            def dest = qupath.fx.dialogs.FileChoosers.promptToSaveFile(stage, 'Raporu kaydet', suggested,
                new javafx.stage.FileChooser.ExtensionFilter('Metin (*.txt)', '*.txt'))
            if (dest != null) {
                try { dest.setText(reportTextRef.get(), 'UTF-8'); Dialogs.showInfoNotification('Rapor kaydedildi', dest.getName()) }
                catch (Throwable t) { Dialogs.showErrorMessage('Kaydedilemedi', (t.getMessage() ?: t.getClass().getSimpleName())) }
            }
        }))
    } else if (cur == 'DIAG') {
        title.setText('GrandQC ortam doğrulama')
        addMonoArea(diagTextRef.get())
        actions.add(navButton('◀ Geri', { step.set('CONFIG'); render() }))
        actions.add(navButton('Kopyala', { copyToClipboard(diagTextRef.get()) }))
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
    stage.setScene(new javafx.scene.Scene(root, 820, 600))
}

// ── Açılış durumu ───────────────────────────────────────────────────────────
// Hızlandırıcıyı ARKA PLAN (betik) iş parçacığında bir kez algıla/önbelleğe al —
// böylece sonraki render() çağrıları FX iş parçacığında `nvidia-smi` başlatmaz.
detectAccelerator()
step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('GrandQC kalite kontrol sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ GrandQC kalite kontrol sihirbazı açıldı."
