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
 *   böylece Modül 2–7 yalnız temiz dokuda çalıştırılabilir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • GrandQC çıktısı bir derin öğrenme TAHMİNİDİR; bu betik tahminleri yalnız
 *     QuPath görselleştirme/ölçüm katmanına TAŞIR ve alan/sayı/% üretir. Patoloji
 *     yorumu, grade veya klinik karar üretmez. Görsel doğrulama gerekir (Ek D, Ek W).
 *   • GeoJSON koordinatları WSI taban (level-0) piksel uzayında olmalıdır; betik
 *     yeniden ölçekleme yapmaz.
 *   • Lisans: GrandQC kodu/modelleri CC BY-NC-SA 4.0 (yalnız ticari olmayan).
 *
 * KULLANIM:
 *   1. GrandQC Python ortamını kurun (venv + modeller). Bkz. Ekler → Ek B § GrandQC.
 *   2. Bir H&E slaydını açın (yerel diskte).
 *   3. [Extensions → Atölye → Yardımcılar → GrandQC kalite kontrol sihirbazı]
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

def loadConfig = { ->
    [ python   : prefs.get(PREF_PYTHON,  ''),
      scripts  : prefs.get(PREF_SCRIPTS, ''),
      modelDir : prefs.get(PREF_MODEL,   ''),
      mpp      : prefs.get(PREF_MPP,     '1.5') ]
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

// geojson_qc içinde tam ad yoksa makul bir yedeğe düş
def findGeoJSON = { slide, manualFolder ->
    def folder = slide.folder ?: manualFolder
    if (folder == null) return null
    def exact = (slide.geojson != null && slide.geojson.isFile())
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
    sb << "# GrandQC — bu klasördeki TÜM slaytları işler: " << folder << "\n\n"
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
def importGeoJSON = { File geojsonFile ->
    if (geojsonFile == null || !geojsonFile.isFile())
        return [ok: false, error: 'GeoJSON bulunamadı:\n' + (geojsonFile?.getAbsolutePath() ?: '(yol yok)') +
                '\n\nÖnce GrandQC hattını çalıştırın (Komut üret / Doğrudan çalıştır).']
    int minVertex = 4
    def plane = ImagePlane.getDefaultPlane()
    def ringToRoi = { ring ->
        if (ring == null || ring.size() < minVertex) return null
        def pts = new ArrayList<Point2>(ring.size())
        for (int i = 0; i < ring.size(); i++) {
            def c = ring.get(i).getAsJsonArray()
            if (c.size() < 2) return null
            double x = c.get(0).getAsDouble(); double y = c.get(1).getAsDouble()
            if (!Double.isFinite(x) || !Double.isFinite(y)) return null
            pts.add(new Point2(x, y))
        }
        if (pts.size() < minVertex) return null
        return ROIs.createPolygonROI(pts, plane)
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
                def feature = fe.getAsJsonObject()
                if (!feature.has('geometry') || feature.get('geometry').isJsonNull()) { skipped++; return }
                def geom = feature.getAsJsonObject('geometry')
                if (!geom.has('type') || !geom.has('coordinates')) { skipped++; return }
                String gt = geom.get('type').getAsString()
                def rings = []
                if (gt == 'Polygon') {
                    def coords = geom.getAsJsonArray('coordinates')
                    if (coords != null && coords.size() > 0) rings << coords.get(0).getAsJsonArray()
                } else if (gt == 'MultiPolygon') {
                    geom.getAsJsonArray('coordinates')?.each { poly ->
                        def rr = poly.getAsJsonArray()
                        if (rr != null && rr.size() > 0) rings << rr.get(0).getAsJsonArray()
                    }
                } else { skipped++; return }
                String cn = classNameOf(feature)
                def pc = ensureClass(cn)
                rings.each { ring ->
                    def roi = ringToRoi(ring)
                    if (roi == null || roi.isEmpty()) { skipped++; return }
                    def ann = PathObjects.createAnnotationObject(roi, pc)
                    ann.setName(GRANDQC_SENTINEL)
                    ann.setLocked(true)
                    newAnns << ann
                    counts[cn] = (counts.getOrDefault(cn, 0)) + 1
                }
            }
        } finally { reader.close() }
    } catch (Throwable t) {
        return [ok: false, error: 'GeoJSON okunamadı:\n' + (t.getMessage() ?: t.getClass().getSimpleName())]
    }
    if (newAnns.isEmpty())
        return [ok: false, error: 'GeoJSON dosyasında geçerli Polygon/MultiPolygon özelliği bulunamadı (atlanan: ' + skipped + ').']
    // Önceki içe aktarımı temizle → idempotent
    QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == GRANDQC_SENTINEL }, false)
    QP.addObjects(newAnns)
    QP.fireHierarchyUpdate()
    return [ok: true, annotations: newAnns, counts: counts, skipped: skipped, file: geojsonFile]
}

// ── Temiz doku = doku − artefaktlar ─────────────────────────────────────────
def computeCleanTissue = { imageData, importedAnns ->
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
    QP.addObjects([cleanAnn])
    QP.fireHierarchyUpdate()

    double tissueMm2 = areaMm2(tissueUnion)
    double cleanMm2  = areaMm2(cleanRoi)
    double artMm2    = (!Double.isNaN(tissueMm2) && !Double.isNaN(cleanMm2)) ? Math.max(0.0d, tissueMm2 - cleanMm2) : Double.NaN
    double artPct    = (tissueMm2 > 0 && !Double.isNaN(artMm2)) ? (artMm2 / tissueMm2 * 100.0d) : Double.NaN
    return [ok: true, calibrated: calibrated, tissueMm2: tissueMm2, cleanMm2: cleanMm2, artMm2: artMm2, artPct: artPct]
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
    sb << String.format(java.util.Locale.US, "  atlanan özellik : %,d%n%n", (imp.skipped ?: 0))
    if (clean != null && clean.ok) {
        sb << "Temiz doku (Temiz doku) eklendi.\n"
        if (clean.calibrated) {
            sb << String.format(java.util.Locale.US, "  Doku alanı       : %.3f mm²%n", clean.tissueMm2)
            sb << String.format(java.util.Locale.US, "  Temiz doku alanı : %.3f mm²%n", clean.cleanMm2)
            sb << String.format(java.util.Locale.US, "  Artefakt alanı   : %.3f mm² (doku içi %.1f%%)%n", clean.artMm2, clean.artPct)
        } else {
            sb << "  (Görüntü kalibre değil — alanlar mm² olarak verilemedi.)\n"
        }
        sb << "\nModül 2–7'yi bu \"Temiz doku\" anotasyonuyla sınırlayabilirsiniz.\n"
    } else {
        sb << "Temiz doku üretilemedi"
        if (clean != null && clean.error) sb << " — " << clean.error
        sb << "\nArtefakt/doku anotasyonları yine de eklendi.\n"
    }
    sb << "\nGrandQC çıktısı bir derin öğrenme tahminidir; görsel olarak doğrulayın (Ek D, Ek W).\n"
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
// CONFIG_INCOMPLETE | CONFIG | READY | CMD_READY | RUNNING | BUSY | RESULT | ERROR
def stage = null
def step           = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop      = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef   = new java.util.concurrent.atomic.AtomicBoolean(false)
def processRef     = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef     = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef    = new java.util.concurrent.atomic.AtomicReference('')
def busyLabelRef   = new java.util.concurrent.atomic.AtomicReference('')
def cmdTextRef     = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef   = new java.util.concurrent.atomic.AtomicReference('')
// CONFIG düzenleme alanları (Kaydet bunları okur)
def pyFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def scriptsFieldRef= new java.util.concurrent.atomic.AtomicReference(null)
def modelFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def mppChoiceRef   = new java.util.concurrent.atomic.AtomicReference(null)
def manualFolderRef= new java.util.concurrent.atomic.AtomicReference(null)   // yerel olmayan slayt için
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

// Çalışan klasörü çöz (yerel slayt klasörü ya da elle girilen)
def effectiveFolder = { slide ->
    if (slide.folder != null) return slide.folder
    def mf = manualFolderRef.get()
    return (mf != null && mf.toString().trim()) ? mf.toString().trim() : null
}

// ── Arka plan: import + temiz doku ──────────────────────────────────────────
def runImportClean = { slide, manualFolder ->
    busyLabelRef.set('GeoJSON içe aktarılıyor…'); step.set('BUSY'); render()
    def worker = new Thread({
        def geojson = findGeoJSON(slide, manualFolder)
        def imp = importGeoJSON(geojson)
        if (!imp.ok) {
            javafx.application.Platform.runLater { errorTextRef.set(imp.error); step.set('ERROR'); render() }
            return
        }
        javafx.application.Platform.runLater { busyLabelRef.set('Temiz doku hesaplanıyor…'); render() }
        def clean
        try { clean = computeCleanTissue(QP.getCurrentImageData(), imp.annotations) }
        catch (Throwable t) { clean = [ok: false, error: (t.getMessage() ?: t.getClass().getSimpleName())] }
        javafx.application.Platform.runLater {
            try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
            resultTextRef.set(buildResultText(slide, imp, clean))
            step.set('RESULT'); render()
        }
    }, 'AtolyeGrandQC-Import')
    worker.setDaemon(true); worker.start()
}

// ── Arka plan: Python hattı (ProcessBuilder) → import ───────────────────────
def runPython = { List cmd, Closure onLine ->
    def pb = new ProcessBuilder(cmd)
    pb.redirectErrorStream(true)
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
    def py = pyFieldRef.get(); def sc = scriptsFieldRef.get(); def md = modelFieldRef.get(); def mp = mppChoiceRef.get()
    prefs.put(PREF_PYTHON,  (py != null ? py.getText() : '').trim())
    prefs.put(PREF_SCRIPTS, (sc != null ? sc.getText() : '').trim())
    prefs.put(PREF_MODEL,   (md != null ? md.getText() : '').trim())
    prefs.put(PREF_MPP,     (mp != null && mp.getValue() != null) ? mp.getValue() : '1.5')
    try { prefs.flush() } catch (Throwable ignore) {}
    step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render()
}

// ── Render: her durum değişiminde sahneyi sıfırdan kurar ────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def imageData = QP.getCurrentImageData()
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

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('GrandQC yapılandırması gerekli')
        def miss = configMissing(cfg)
        addGuidance('GrandQC bir Python ortamı gerektirir. Aşağıdakiler eksik/geçersiz:\n  • ' +
            (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')) +
            '\n\nKurulum: Ekler → Ek B § GrandQC.')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
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
        pyFieldRef.set(pyField); scriptsFieldRef.set(scField); modelFieldRef.set(mdField); mppChoiceRef.set(mppChoice)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Dosya seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir  = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Dizin seç', null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('GrandQC betik dizini:'), scField, navButton('…', { browseDir(scField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Model dizini (ops.):'), mdField, navButton('…', { browseDir(mdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Artefakt MPP modeli:'), mppChoice)
        center.getChildren().add(grid)
        addGuidance('Betik dizini wsi_tis_detect.py + main.py içermeli. Model dizini doku/artefakt .pth dosyalarını içerir.')
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
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
            def typeName = (imageData.getImageType()?.name() ?: '').toUpperCase(java.util.Locale.ROOT)
            boolean isHE = typeName.contains('BRIGHTFIELD_H_E')
            title.setText('GrandQC — hazır')
            def sb = new StringBuilder()
            sb << "Slayt        : " << slide.name << "\n"
            sb << "Klasör       : " << (slide.folder ?: '(yerel disk değil — elle girin)') << "\n"
            sb << "Python       : " << (cfg.python ?: '(ayarsız)') << "\n"
            sb << "MPP modeli   : " << cfg.mpp << "\n"
            def gj = findGeoJSON(slide, effectiveFolder(slide))
            sb << "GeoJSON      : " << ((gj != null && gj.isFile()) ? ('mevcut — ' + gj.getName()) : 'yok (henüz çalıştırılmadı)') << "\n"
            addMonoArea(sb.toString())
            if (!isHE) addWarnLabel('⚠ Görüntü tipi H&E değil (' + typeName + '). GrandQC H&E için tasarlanmıştır; yine de deneyebilirsiniz.')
            if (slide.folder == null) {
                addGuidance('Slayt yerel diskte çözülemedi. Slayt klasörünü elle girin:')
                def mf = new javafx.scene.control.TextField(manualFolderRef.get()?.toString() ?: '')
                mf.setPrefColumnCount(34)
                mf.textProperty().addListener({ o, ov, nv -> manualFolderRef.set(nv) } as javafx.beans.value.ChangeListener)
                center.getChildren().add(mf)
            }
            boolean canRun = configComplete(cfg) && (effectiveFolder(slide) != null)
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('Komut üret ▶', { cmdTextRef.set(cmdText(cfg, (effectiveFolder(slide) ?: '<slayt-klasörü>'), slide.name)); step.set('CMD_READY'); render() }))
            if (gj != null && gj.isFile())
                actions.add(navButton('GeoJSON içe aktar', { runImportClean(slide, effectiveFolder(slide)) }, 'Mevcut GrandQC GeoJSON çıktısını içe aktarır'))
            def runBtn = navButton('Doğrudan çalıştır ▶', { startDirectRun(slide) }, 'Python hattını QuPath içinden çalıştırır (venv gerekli)')
            runBtn.setDisable(!canRun)
            actions.add(runBtn)
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
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅')
        addMonoArea(resultTextRef.get())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('↻ Yeniden çalıştır', { step.set('READY'); render() }))
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
