/**
 * Yardımcı - TIA Toolbox boya normalizasyonu / doku maskesi sihirbazı (tek pencere köprü)
 * --------------------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Warwick TIA Centre'in **TIA Toolbox** kütüphanesinin iki "temel işlevini" tek
 *   pencereden çalıştırır (Python köprüsü: tiatoolbox_bridge.py):
 *     1. Boya normalizasyonu — bölge önizleme : seçili alan anotasyonunu tek PNG
 *        olarak dışa aktarır → Macenko/Vahadane/Reinhard ile bir referans H&E
 *        görünümüne eşitler → orijinal | normalize yan-yana montaj üretir (KK).
 *     2. Boya normalizasyonu — karo grubu      : alan anotasyonlarının karolarını
 *        dışa aktarır → hepsini normalize eder → <çalışma>/normalized/ klasörüne
 *        yazar (downstream ML için ön-işleme; çıktı GÖRÜNTÜdür, QuPath nesnesi değil).
 *     3. Doku maskesi (Otsu / Morphological)   : küçük-çözünürlüklü bir küçükresim
 *        dışa aktarır → doku/arka plan maskesi üretir → maskeyi AÇIK SLAYDA hizalı
 *        "Doku" anotasyonuna çevirir (yerinde, tek tık — raster maske içe-aktarım eşi).
 *
 *   Derin öğrenme/torch GEREKMEZ; boya normalizasyonu + doku maskesi saf numpy/opencv.
 *   QuPath color *deconvolution* yapar ama boya *normalizasyonu* YAPMAZ — bu, bu
 *   sihirbazın doldurduğu boşluktur (tarayıcıdan-tarayıcıya, ör. GT450 ↔ AT2, renk farkı).
 *
 * ÖNEMLİ — OpenSlide gerekmez:
 *   Python köprüsü WSI AÇMAZ; yalnız bu betiğin dışa aktardığı PNG'leri okur (SPIDER /
 *   Kaiko köprüsüyle aynı tasarım). WSI'ı doğrudan okuyan TIA Toolbox MODEL iş akışları
 *   (BCSS / KongNet / IDARS) için: Ek — TIA Toolbox.
 *
 * NE ÜRETİR (ve ne ÜRETMEZ):
 *   • Boya normalizasyonu: disktedeki normalize görüntü dosyaları (QuPath nesnesi değil).
 *   • Doku maskesi: "Doku" sınıflı, kilitli anotasyon + alan (mm²) ölçümü.
 *   • Hiçbir klinik eşik/alt-tip/grade/yorum üretmez. Sonuçları görsel doğrulayın (Ek W).
 *
 * KULLANIM:
 *   1. TIA Toolbox Python ortamını kurun (venv) — Ek → TIA Toolbox § Kurulum ya da
 *      handson/python/tiatoolbox/README.md. (pip install tiatoolbox numpy pillow)
 *   2. Bir slayt açın; (normalize için) bölge/alan anotasyonu çizin/seçin.
 *   3. [Extensions → Atölye → Yardımcılar → TIA Toolbox boya normalizasyonu / doku maskesi sihirbazı]
 *   4. İlk açılışta yapılandırın: python.exe, tiatoolbox_bridge.py, (ops.) referans PNG.
 *   5. Mod + yöntem seçip "Çalıştır" → çıktı üretilir / içe aktarılır.
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Pocock J ve ark. (2022), Commun Med — TIAToolbox. doi:10.1038/s43856-022-00186-5
 *   • Macenko M ve ark. (2009) doi:10.1109/ISBI.2009.5193250
 *   • Vahadane A ve ark. (2016) doi:10.1109/TMI.2016.2529665
 *   • Reinhard E ve ark. (2001) doi:10.1109/38.946629
 *   • Belge: https://tia-toolbox.readthedocs.io/en/stable/
 *
 * API: ContourTracing.createTracedROI + RegionRequest (maske → anotasyon, QuPath 0.6.0+).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı veri/ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.regions.RegionRequest
import qupath.lib.objects.PathObjects
import qupath.lib.analysis.images.ContourTracing
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ───────────────────────────────────────────────────────────────
long PYTHON_TIMEOUT_SECONDS = 1800L
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def NORM_METHODS = ['macenko', 'vahadane', 'reinhard']
def MASK_METHODS = ['otsu', 'morphological']
def MODE_LABELS = [
    'normalize-region': 'Boya normalizasyonu — bölge önizleme',
    'normalize-tiles' : 'Boya normalizasyonu — karo grubu',
    'tissue-mask'     : 'Doku maskesi (Otsu / Morphological)'
]
def modeLabelToKey = { String lbl -> def k = MODE_LABELS.find { it.value == lbl }?.key; return (k ?: 'normalize-region') }
def TISSUE_SENTINEL = 'Doku maskesi (TIA Toolbox)'

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır) ──
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/tiatoolbox')
def PREF_PYTHON = 'python'
def PREF_BRIDGE = 'bridge'
def PREF_REF    = 'reference'
def PREF_BUILT  = 'useBuiltin'
def PREF_WORK   = 'workDir'
def PREF_MODE   = 'mode'
def PREF_NORM   = 'normMethod'
def PREF_MASK   = 'maskMethod'
def PREF_DS     = 'downsample'       // normalize bölge/karo dışa aktarma downsample'ı
def PREF_TILE   = 'tileSize'
def PREF_MAXT   = 'maxTiles'
def PREF_MDS    = 'maskDownsample'   // doku maskesi küçükresim downsample'ı

def loadConfig = { ->
    [ python         : prefs.get(PREF_PYTHON, ''),
      bridge         : prefs.get(PREF_BRIDGE, ''),
      reference      : prefs.get(PREF_REF,    ''),
      useBuiltin     : prefs.get(PREF_BUILT,  'true'),
      workDir        : prefs.get(PREF_WORK,   ''),
      mode           : prefs.get(PREF_MODE,   'normalize-region'),
      normMethod     : prefs.get(PREF_NORM,   'macenko'),
      maskMethod     : prefs.get(PREF_MASK,   'otsu'),
      downsample     : prefs.get(PREF_DS,     '1.0'),
      tileSize       : prefs.get(PREF_TILE,   '1024'),
      maxTiles       : prefs.get(PREF_MAXT,   '0'),
      maskDownsample : prefs.get(PREF_MDS,    '16.0') ]
}

// Zorunlu: python.exe + tiatoolbox_bridge.py
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile())
        miss << 'Python yürütülebilir (python.exe)'
    if (!cfg.bridge?.trim() || !(new File(cfg.bridge)).isFile())
        miss << 'TIA Toolbox köprüsü (tiatoolbox_bridge.py)'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

def parseIntOr = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }
def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().trim().replace(',', '.')) } catch (Throwable t) { return d } }

// ── Çalışma dizinini çöz (proje dizini → slayt klasörü → geçici) ────────────
def resolveWorkDir = { cfg, imageData ->
    def wd = cfg.workDir?.trim()
    if (wd) return new File(wd)
    def project = QP.getProject()
    if (project != null && project.getPath() != null)
        return new File(project.getPath().getParent().toFile(), 'tiatoolbox_work')
    try {
        def uris = imageData.getServer().getURIs()
        if (uris != null && !uris.isEmpty()) {
            def uri = uris.iterator().next()
            if ('file'.equals(uri.getScheme())) {
                def f = new File(uri)
                if (f.getParentFile() != null) return new File(f.getParentFile(), 'tiatoolbox_work')
            }
        }
    } catch (Throwable ignore) {}
    return new File(System.getProperty('java.io.tmpdir'), 'tiatoolbox_work')
}

def imageNameOf = { imageData ->
    def nm = imageData.getServer().getMetadata().getName() ?: 'slide'
    return nm.replaceAll(/\.[^.\/\\]+$/, '')
}

def writePng = { img, File f -> if (f.getParentFile() != null) f.getParentFile().mkdirs(); javax.imageio.ImageIO.write(img, 'PNG', f) }

// ── Arka plan tespiti (ortalama parlaklık > 220 → arka plan) ────────────────
def isBackground = { java.awt.image.BufferedImage img ->
    int w = img.getWidth(); int h = img.getHeight()
    if (w <= 0 || h <= 0) return true
    int[] px = img.getRGB(0, 0, w, h, null, 0, w)
    if (px.length == 0) return true
    int stepP = Math.max(1, (int) (px.length / 4096))
    long sum = 0L; int n = 0
    for (int i = 0; i < px.length; i += stepP) {
        int p = px[i]
        int r = (p >> 16) & 0xFF; int g = (p >> 8) & 0xFF; int b = p & 0xFF
        sum += (r + g + b); n++
    }
    double mean = (n > 0) ? (sum / (3.0d * n)) : 255.0d
    return mean > 220.0d
}

// ── Bir anotasyon ROI'sini ızgara karolara böl; her karo için sink() çağrılır ─
def gridTiles = { server, roi, double downsample, int tileSize, int maxTiles, Closure sink ->
    if (roi == null || !roi.isArea()) return 0
    int phys = (int) Math.max(1, Math.round(tileSize * downsample))
    int bx = (int) Math.floor(roi.getBoundsX())
    int by = (int) Math.floor(roi.getBoundsY())
    int bw = (int) Math.ceil(roi.getBoundsWidth())
    int bh = (int) Math.ceil(roi.getBoundsHeight())
    int sw = server.getWidth(); int sh = server.getHeight()
    int count = 0
    for (int yy = by; yy + phys <= by + bh; yy += phys) {
        for (int xx = bx; xx + phys <= bx + bw; xx += phys) {
            if (xx < 0 || yy < 0 || xx + phys > sw || yy + phys > sh) continue
            double cx = xx + phys / 2.0d, cy = yy + phys / 2.0d
            if (!roi.contains(cx, cy)) continue
            def img
            try { img = server.readRegion(RegionRequest.createInstance(server.getPath(), downsample, xx, yy, phys, phys)) }
            catch (Throwable t) { continue }
            if (img == null || isBackground(img)) continue
            sink(xx, yy, phys, img)
            count++
            if (maxTiles > 0 && count >= maxTiles) return count
        }
    }
    return count
}

// ── Bölge dışa aktar (normalize-region) ──────────────────────────────────────
def exportRegionPng = { imageData, File workDir, double downsample, Closure appendLine ->
    def server = imageData.getServer()
    def hierarchy = imageData.getHierarchy()
    def sel = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isAnnotation() && it.hasROI() && it.getROI().isArea() }
    def roi = null
    if (!sel.isEmpty()) roi = sel.iterator().next().getROI()
    else {
        def anns = hierarchy.getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }
        if (!anns.isEmpty()) roi = anns.iterator().next().getROI()
    }
    if (roi == null) return [ok: false, error: 'Önizlenecek alan anotasyonu yok.\nBir bölge seçin ya da çizin.']
    int sw = server.getWidth(); int sh = server.getHeight()
    int x = Math.max(0, (int) Math.floor(roi.getBoundsX()))
    int y = Math.max(0, (int) Math.floor(roi.getBoundsY()))
    int w = Math.min(sw - x, (int) Math.ceil(roi.getBoundsWidth()))
    int h = Math.min(sh - y, (int) Math.ceil(roi.getBoundsHeight()))
    if (w <= 0 || h <= 0) return [ok: false, error: 'Bölge boyutu geçersiz.']
    def img
    try { img = server.readRegion(RegionRequest.createInstance(server.getPath(), downsample, x, y, w, h)) }
    catch (Throwable t) { return [ok: false, error: 'Bölge okunamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }
    if (img == null) return [ok: false, error: 'Bölge okunamadı (boş).']
    def f = new File(workDir, 'region.png')
    writePng(img, f)
    appendLine('Bölge dışa aktarıldı: ' + f.getName() + ' (' + img.getWidth() + ' × ' + img.getHeight() + ' px)')
    return [ok: true, file: f, w: img.getWidth(), h: img.getHeight()]
}

// ── Karo grubu dışa aktar (normalize-tiles) ──────────────────────────────────
def exportTilesFlat = { imageData, File workDir, double downsample, int tileSize, int maxTiles, Closure appendLine ->
    def server = imageData.getServer()
    def anns = imageData.getHierarchy().getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }
    if (anns.isEmpty()) return [ok: false, error: 'Karolanacak alan anotasyonu yok.\nEn az 1 bölge çizin.']
    def tilesDir = new File(workDir, 'tiles')
    // Önceki karoları temizle (idempotent)
    if (tilesDir.isDirectory()) { tilesDir.listFiles()?.each { it.delete() } }
    tilesDir.mkdirs()
    int total = 0; int i = 0
    anns.each { ann ->
        i++
        int n = gridTiles(server, ann.getROI(), downsample, tileSize, maxTiles, { x, y, phys, img ->
            writePng(img, new File(tilesDir, 'tile_' + i + '_x' + x + '_y' + y + '.png'))
        })
        total += n
        appendLine('  karolar: anotasyon ' + i + '/' + anns.size() + ' (' + n + ')')
    }
    return [ok: true, nAnns: anns.size(), nTiles: total, tilesDir: tilesDir]
}

// ── Küçükresim dışa aktar (tissue-mask) ──────────────────────────────────────
def exportThumbnailPng = { imageData, File workDir, double downsample, Closure appendLine ->
    def server = imageData.getServer()
    int W = server.getWidth(); int H = server.getHeight()
    def img
    try { img = server.readRegion(RegionRequest.createInstance(server.getPath(), downsample, 0, 0, W, H)) }
    catch (Throwable t) { return [ok: false, error: 'Küçükresim okunamadı (downsample çok küçük olabilir): ' + (t.getMessage() ?: t.getClass().getSimpleName())] }
    if (img == null) return [ok: false, error: 'Küçükresim okunamadı (boş).']
    def f = new File(workDir, 'thumbnail.png')
    writePng(img, f)
    appendLine('Küçükresim: ' + f.getName() + ' (' + img.getWidth() + ' × ' + img.getHeight() + ' px, downsample ' + downsample + ')')
    return [ok: true, file: f, w: img.getWidth(), h: img.getHeight()]
}

// ── Referans argümanları ─────────────────────────────────────────────────────
def addRefArgs = { List cmd, cfg, boolean allowFromTiles ->
    if (cfg.useBuiltin == 'true') { cmd.add('--builtin-reference'); return }
    if (cfg.reference?.trim()) { cmd.add('--reference'); cmd.add(cfg.reference.trim()); return }
    if (allowFromTiles) { cmd.add('--reference-from-tiles'); return }
    cmd.add('--builtin-reference')   // bölge modunda kendi-kendine referansı önle
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def processRef    = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef   = new java.util.concurrent.atomic.AtomicReference('')
def busyLabelRef  = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def openFileRef   = new java.util.concurrent.atomic.AtomicReference(null)
// CONFIG düzenleme alanları
def pyFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def bridgeFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def refFieldRef    = new java.util.concurrent.atomic.AtomicReference(null)
def builtinChkRef  = new java.util.concurrent.atomic.AtomicReference(null)
def workFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def normChoiceRef  = new java.util.concurrent.atomic.AtomicReference(null)
def maskChoiceRef  = new java.util.concurrent.atomic.AtomicReference(null)
def dsFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def tileFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def maxTFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def mdsFieldRef    = new java.util.concurrent.atomic.AtomicReference(null)
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
def openFileExternally = { File f ->
    try { if (f != null && f.isFile() && java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(f) }
    catch (Throwable ignore) {}
}

// ── Doku maskesini içe aktar (raster → "Doku" anotasyonu) ───────────────────
def importTissueMask = { File workDir, imageData, double downsample ->
    def maskFile = new File(workDir, 'tissue_mask.png')
    if (!maskFile.isFile())
        return [ok: false, error: 'tissue_mask.png bulunamadı:\n' + maskFile.getAbsolutePath()]
    def img = null
    try { img = javax.imageio.ImageIO.read(maskFile) } catch (Throwable t) { img = null }
    if (img == null) return [ok: false, error: 'Maske görüntüsü okunamadı.']
    def raster = img.getRaster()
    def server = imageData.getServer()
    def request = RegionRequest.createInstance(server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight())
    def roi
    try { roi = ContourTracing.createTracedROI(raster, 1.0d, 255.0d, 0, request) }
    catch (Throwable t) { return [ok: false, error: 'Maske izlenemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }

    def hierarchy = imageData.getHierarchy()
    QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == TISSUE_SENTINEL }, false)
    def newAnns = []
    double areaPx = 0.0d
    if (roi != null && !roi.isEmpty()) {
        def pc = QP.getPathClass('Doku')
        def ann = PathObjects.createAnnotationObject(roi, pc)
        ann.setName(TISSUE_SENTINEL); ann.setLocked(true)
        newAnns << ann
        areaPx = roi.getArea()
    }
    QP.addObjects(newAnns)
    QP.fireHierarchyUpdate()
    javafx.application.Platform.runLater { try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {} }

    def cal = server.getPixelCalibration()
    double pw = cal.getPixelWidthMicrons(); double ph = cal.getPixelHeightMicrons()
    boolean calibrated = (pw > 0 && ph > 0 && !Double.isNaN(pw) && !Double.isNaN(ph))
    double mm2 = calibrated ? (areaPx * pw * ph / 1_000_000.0d) : Double.NaN
    return [ok: true, nAnns: newAnns.size(), areaPx: areaPx, mm2: mm2, calibrated: calibrated,
            maskW: img.getWidth(), maskH: img.getHeight()]
}

// ── Özet metinleri ────────────────────────────────────────────────────────────
def normRegionResultText = { File workDir, cfg, exp ->
    def sb = new StringBuilder()
    sb << "BOYA NORMALİZASYONU — BÖLGE ÖNİZLEME\n"
    sb << "════════════════════════════════════\n\n"
    sb << "Yöntem    : " << (cfg.normMethod ?: 'macenko') << "\n"
    sb << "Referans  : " << (cfg.useBuiltin == 'true' ? 'TIA Toolbox yerleşik (stain_norm_target)' : (cfg.reference?.trim() ?: '(yok)')) << "\n"
    sb << String.format(java.util.Locale.US, "Bölge     : %,d × %,d px (downsample %s)%n", (int) (exp?.w ?: 0), (int) (exp?.h ?: 0), (cfg.downsample ?: '1.0'))
    sb << "\nÇıktı:\n"
    sb << "  • " << new File(new File(workDir, 'normalized'), 'region.png').getAbsolutePath() << "\n"
    sb << "  • " << new File(workDir, 'montage.png').getAbsolutePath() << "  (orijinal | normalize)\n"
    sb << "\nMontaj penceresi açıldı (açılmadıysa yukarıdaki yolu açın).\n"
    sb << "Çıktı bir GÖRÜNTÜdür; QuPath nesnesi/ölçümü üretmez.\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlıdır."
    return sb.toString()
}
def normTilesResultText = { File workDir, cfg, exp, int normalized ->
    def sb = new StringBuilder()
    sb << "BOYA NORMALİZASYONU — KARO GRUBU\n"
    sb << "═════════════════════════════════\n\n"
    sb << "Yöntem    : " << (cfg.normMethod ?: 'macenko') << "\n"
    sb << "Referans  : " << (cfg.useBuiltin == 'true' ? 'TIA Toolbox yerleşik (stain_norm_target)' : (cfg.reference?.trim() ?: 'ilk dolu karo')) << "\n"
    sb << String.format(java.util.Locale.US, "Anotasyon : %,d   ·   dışa aktarılan karo: %,d%n", (int) (exp?.nAnns ?: 0), (int) (exp?.nTiles ?: 0))
    sb << String.format(java.util.Locale.US, "Normalize : %,d karo%n", normalized)
    sb << "\nÇıktı klasörü:\n  " << new File(workDir, 'normalized').getAbsolutePath() << "\n"
    sb << "\nNormalize karolar bir ÖN-İŞLEME çıktısıdır (Ek Q / Kaiko / SPIDER'a beslenebilir);\n"
    sb << "QuPath nesnesi/ölçümü üretmez.\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlıdır."
    return sb.toString()
}
def tissueResultText = { File workDir, cfg, exp, imp ->
    def sb = new StringBuilder()
    sb << "DOKU MASKESİ — TIA Toolbox → QuPath\n"
    sb << "═══════════════════════════════════\n\n"
    sb << "Yöntem    : " << (cfg.maskMethod ?: 'otsu') << "\n"
    sb << String.format(java.util.Locale.US, "Küçükresim: %,d × %,d px (downsample %s)%n", (int) (exp?.w ?: 0), (int) (exp?.h ?: 0), (cfg.maskDownsample ?: '16.0'))
    sb << String.format(java.util.Locale.US, "Anotasyon : %,d ('Doku', kilitli)%n", (int) (imp?.nAnns ?: 0))
    if (imp?.calibrated && !Double.isNaN((double) imp.mm2))
        sb << String.format(java.util.Locale.US, "Doku alanı: %.3f mm²%n", (double) imp.mm2)
    else
        sb << "Doku alanı: (görüntü kalibre değil — mm² verilemedi)\n"
    sb << "\nMaske açık slayda hizalı 'Doku' anotasyonu olarak eklendi.\n"
    sb << "Maske bir tahmindir; görsel olarak doğrulayın (Ek W). Klinik yorum üretilmez.\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

// ── Headless: yapılandırmayı raporla, etkileşim yok ─────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    println "TIA Toolbox sihirbazı yapılandırması: python=${cfg.python ?: '(ayarsız)'} köprü=${cfg.bridge ?: '(ayarsız)'} mod=${cfg.mode} normYöntem=${cfg.normMethod} maskeYöntem=${cfg.maskMethod}"
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(', ')}"
    if (imageData != null) {
        def anns = imageData.getHierarchy().getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }
        println "Alan anotasyonu: ${anns.size()}"
    } else println "Açık görüntü yok."
    println "TIA Toolbox sihirbazı için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlı veri/ölçüm üretir."
    return
}

// ── Alanları prefs'e yaz ─────────────────────────────────────────────────────
def textOf = { ref -> def f = ref.get(); return (f != null ? f.getText() : '').trim() }
def persistFields = {
    prefs.put(PREF_PYTHON, textOf(pyFieldRef))
    prefs.put(PREF_BRIDGE, textOf(bridgeFieldRef))
    prefs.put(PREF_REF,    textOf(refFieldRef))
    def chk = builtinChkRef.get(); prefs.put(PREF_BUILT, (chk != null && chk.isSelected()) ? 'true' : 'false')
    prefs.put(PREF_WORK,   textOf(workFieldRef))
    def nm = normChoiceRef.get(); prefs.put(PREF_NORM, (nm != null && nm.getValue() != null) ? nm.getValue() : 'macenko')
    def mk = maskChoiceRef.get(); prefs.put(PREF_MASK, (mk != null && mk.getValue() != null) ? mk.getValue() : 'otsu')
    def ds = textOf(dsFieldRef);   prefs.put(PREF_DS,   ds ?: '1.0')
    def ts = textOf(tileFieldRef); prefs.put(PREF_TILE, ts ?: '1024')
    def mt = textOf(maxTFieldRef); prefs.put(PREF_MAXT, mt ?: '0')
    def md = textOf(mdsFieldRef);  prefs.put(PREF_MDS,  md ?: '16.0')
    try { prefs.flush() } catch (Throwable ignore) {}
}

// ── Python süreci (ProcessBuilder) → satır akışı ────────────────────────────
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
            last.addLast(line); while (last.size() > 60) last.pollFirst()
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

def selftestCmd = { cfg -> [cfg.python, cfg.bridge, 'selftest'] }

// ── Bağımlılık kontrolü (selftest) ──────────────────────────────────────────
def startSelftest = {
    persistFields()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set('Önce yapılandırmayı tamamlayın:\n  • ' + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Bağımlılık kontrolü'); step.set('CHECK_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        runPython(selftestCmd(cfg), appendLine)
        javafx.application.Platform.runLater { step.set('CHECK_DONE'); render() }
    }, 'AtolyeTIA-Check')
    worker.setDaemon(true); worker.start()
}

// ── Çalıştırma akışı (moda göre dallanır) ───────────────────────────────────
def startRun = {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def mode = cfg.mode
    def workDir = resolveWorkDir(cfg, imageData)
    workDir.mkdirs()
    cancelledRef.set(false)
    openFileRef.set(null)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Hazırlanıyor…'); step.set('RUN_RUNNING'); render()

    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        appendLine('Çalışma dizini: ' + workDir.getAbsolutePath())
        try {
            if (mode == 'tissue-mask') {
                double mds = parseDoubleOr(cfg.maskDownsample, 16.0d)
                javafx.application.Platform.runLater { runPhaseRef.set('Küçükresim dışa aktarılıyor (1/3)…'); render() }
                def exp = exportThumbnailPng(imageData, workDir, mds, appendLine)
                if (!exp.ok) { javafx.application.Platform.runLater { errorTextRef.set(exp.error); step.set('ERROR'); render() }; return }
                if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }
                double avgPx = imageData.getServer().getPixelCalibration().getAveragedPixelSize()
                double mpp = (avgPx > 0 && !Double.isNaN(avgPx)) ? (avgPx * mds) : 0.0d
                def cmd = [cfg.python, cfg.bridge, 'tissue-mask',
                           '--input', exp.file.getAbsolutePath(),
                           '--output', new File(workDir, 'tissue_mask.png').getAbsolutePath(),
                           '--method', (cfg.maskMethod ?: 'otsu'),
                           '--downsample', String.format(java.util.Locale.US, '%.4f', mds)]
                if (mpp > 0) { cmd.add('--mpp'); cmd.add(String.format(java.util.Locale.US, '%.4f', mpp)) }
                javafx.application.Platform.runLater { runPhaseRef.set('Doku maskesi üretiliyor (2/3)…'); render() }
                def r = runPython(cmd, appendLine)
                if (!r.ok) { javafx.application.Platform.runLater { errorTextRef.set('Doku maskesi başarısız (çıkış: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '')); step.set('ERROR'); render() }; return }
                javafx.application.Platform.runLater { busyLabelRef.set('Maske QuPath\'e içe aktarılıyor (3/3)…'); step.set('BUSY'); render() }
                def imp = importTissueMask(workDir, QP.getCurrentImageData(), mds)
                javafx.application.Platform.runLater {
                    if (!imp.ok) { errorTextRef.set(imp.error); step.set('ERROR'); render() }
                    else { resultTextRef.set(tissueResultText(workDir, cfg, exp, imp)); step.set('RESULT'); render() }
                }
            } else if (mode == 'normalize-tiles') {
                double ds = parseDoubleOr(cfg.downsample, 1.0d)
                int tileSize = parseIntOr(cfg.tileSize, 1024)
                int maxTiles = parseIntOr(cfg.maxTiles, 0)
                javafx.application.Platform.runLater { runPhaseRef.set('Karolar dışa aktarılıyor (1/2)…'); render() }
                def exp = exportTilesFlat(imageData, workDir, ds, tileSize, maxTiles, appendLine)
                if (!exp.ok) { javafx.application.Platform.runLater { errorTextRef.set(exp.error); step.set('ERROR'); render() }; return }
                if (exp.nTiles == 0) { javafx.application.Platform.runLater { errorTextRef.set('Normalize edilecek karo üretilemedi (çok küçük ya da tamamen arka plan olabilir).'); step.set('ERROR'); render() }; return }
                if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }
                def cmd = [cfg.python, cfg.bridge, 'normalize',
                           '--tiles-dir', exp.tilesDir.getAbsolutePath(),
                           '--output-dir', workDir.getAbsolutePath(),
                           '--method', (cfg.normMethod ?: 'macenko')]
                addRefArgs(cmd, cfg, true)
                javafx.application.Platform.runLater { runPhaseRef.set('Boya normalizasyonu (2/2)…'); render() }
                def r = runPython(cmd, appendLine)
                if (!r.ok) { javafx.application.Platform.runLater { errorTextRef.set('Normalize başarısız (çıkış: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '')); step.set('ERROR'); render() }; return }
                int normalized = exp.nTiles
                try { def m = (r.lastLines ?: '') =~ /DONE normalize n=(\d+)/; if (m.find()) normalized = Integer.parseInt(m.group(1)) } catch (Throwable ignore) {}
                int nfinal = normalized
                javafx.application.Platform.runLater { resultTextRef.set(normTilesResultText(workDir, cfg, exp, nfinal)); step.set('RESULT'); render() }
            } else { // normalize-region
                double ds = parseDoubleOr(cfg.downsample, 1.0d)
                javafx.application.Platform.runLater { runPhaseRef.set('Bölge dışa aktarılıyor (1/2)…'); render() }
                def exp = exportRegionPng(imageData, workDir, ds, appendLine)
                if (!exp.ok) { javafx.application.Platform.runLater { errorTextRef.set(exp.error); step.set('ERROR'); render() }; return }
                if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }
                def cmd = [cfg.python, cfg.bridge, 'normalize',
                           '--input', exp.file.getAbsolutePath(),
                           '--output-dir', workDir.getAbsolutePath(),
                           '--method', (cfg.normMethod ?: 'macenko')]
                addRefArgs(cmd, cfg, false)
                javafx.application.Platform.runLater { runPhaseRef.set('Boya normalizasyonu (2/2)…'); render() }
                def r = runPython(cmd, appendLine)
                if (!r.ok) { javafx.application.Platform.runLater { errorTextRef.set('Normalize başarısız (çıkış: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '')); step.set('ERROR'); render() }; return }
                def montage = new File(workDir, 'montage.png')
                openFileRef.set(montage.isFile() ? montage : null)
                javafx.application.Platform.runLater {
                    openFileExternally(openFileRef.get())
                    resultTextRef.set(normRegionResultText(workDir, cfg, exp)); step.set('RESULT'); render()
                }
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Beklenmeyen hata:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
        }
    }, 'AtolyeTIA-Run')
    worker.setDaemon(true); worker.start()
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

    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); center.getChildren().add(lbl) }
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
    def addLiveLog = { ->
        def la = logAreaRef.get()
        if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) }
    }
    def makeModeChoice = { ->
        def cb = new javafx.scene.control.ChoiceBox()
        MODE_LABELS.values().each { cb.getItems().add(it) }
        def key = MODE_LABELS.containsKey(cfg.mode) ? cfg.mode : 'normalize-region'
        cb.setValue(MODE_LABELS[key])
        return cb
    }
    def makeNormChoice = { ->
        def cb = new javafx.scene.control.ChoiceBox(); NORM_METHODS.each { cb.getItems().add(it) }
        cb.setValue(NORM_METHODS.contains(cfg.normMethod) ? cfg.normMethod : 'macenko'); return cb
    }
    def makeMaskChoice = { ->
        def cb = new javafx.scene.control.ChoiceBox(); MASK_METHODS.each { cb.getItems().add(it) }
        cb.setValue(MASK_METHODS.contains(cfg.maskMethod) ? cfg.maskMethod : 'otsu'); return cb
    }

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('TIA Toolbox yapılandırması gerekli')
        def miss = configMissing(cfg)
        addGuidance('Bu sihirbaz bir Python ortamı (TIA Toolbox) gerektirir. Eksik/geçersiz:\n  • ' +
            (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')) +
            '\n\nKurulum: Ekler → TIA Toolbox § Kurulum (ya da handson/python/tiatoolbox/README.md).')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText('TIA Toolbox yapılandırması')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def brField = new javafx.scene.control.TextField(cfg.bridge ?: '')
        def refField = new javafx.scene.control.TextField(cfg.reference ?: '')
        def wdField = new javafx.scene.control.TextField(cfg.workDir ?: '')
        def dsField = new javafx.scene.control.TextField(cfg.downsample ?: '1.0')
        def tsField = new javafx.scene.control.TextField(cfg.tileSize ?: '1024')
        def mtField = new javafx.scene.control.TextField(cfg.maxTiles ?: '0')
        def mdsField = new javafx.scene.control.TextField(cfg.maskDownsample ?: '16.0')
        [pyField, brField, refField, wdField].each { it.setPrefColumnCount(34) }
        [dsField, tsField, mtField, mdsField].each { it.setPrefColumnCount(8) }
        def builtinChk = new javafx.scene.control.CheckBox('TIA Toolbox yerleşik referansını kullan (önerilir)')
        builtinChk.setSelected(cfg.useBuiltin == 'true')
        def normChoice = makeNormChoice()
        def maskChoice = makeMaskChoice()
        pyFieldRef.set(pyField); bridgeFieldRef.set(brField); refFieldRef.set(refField); builtinChkRef.set(builtinChk)
        workFieldRef.set(wdField); normChoiceRef.set(normChoice); maskChoiceRef.set(maskChoice)
        dsFieldRef.set(dsField); tileFieldRef.set(tsField); maxTFieldRef.set(mtField); mdsFieldRef.set(mdsField)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Dosya seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir  = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Dizin seç', null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Köprü (tiatoolbox_bridge.py):'), brField, navButton('…', { browseFile(brField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Referans PNG (ops.):'), refField, navButton('…', { browseFile(refField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label(''), builtinChk)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Çalışma dizini (ops.):'), wdField, navButton('…', { browseDir(wdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Normalize yöntemi:'), normChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Doku maske yöntemi:'), maskChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Normalize downsample (bölge/karo):'), dsField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Karo boyutu (px):'), tsField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Anotasyon başı maks. karo (0=tümü):'), mtField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Doku maske downsample (küçükresim):'), mdsField)
        center.getChildren().add(grid)
        addGuidance('Referans: yerleşik kutu işaretliyse TIA Toolbox\'ın standart H&E referansı kullanılır; ' +
            'kendi referansınız için kutuyu kaldırıp bir PNG seçin. Normalize downsample: 0,25 µm/px tarayıcıda ' +
            '2.0 (≈0,5 µm/px) önizlemeyi hızlandırır. Doku maske downsample: büyük = küçük küçükresim (16–32 önerilir).')
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('Bağımlılık kontrolü', { startSelftest() }, 'tiatoolbox_bridge.py selftest'))
        actions.add(navButton('Kaydet ▶', { persistFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
    } else if (cur == 'CHECK_RUNNING') {
        title.setText('Bağımlılık kontrolü çalışıyor…')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'CHECK_DONE') {
        title.setText('Bağımlılık kontrolü tamam')
        addLiveLog()
        actions.add(navButton('◀ Yapılandırmaya dön', { step.set('CONFIG'); render() }))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText('Görüntü açık değil')
            addGuidance('Önce bir slayt açın (boya normalizasyonu için bölge/alan anotasyonu çizin), sonra "⟳ Yenile".')
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
        } else {
            def anns = imageData.getHierarchy().getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }
            title.setText('TIA Toolbox — hazır')
            def sb = new StringBuilder()
            sb << "Slayt          : " << imageNameOf(imageData) << "\n"
            sb << "Python         : " << (cfg.python ?: '(ayarsız)') << "\n"
            sb << String.format(java.util.Locale.US, "Alan anotasyonu: %,d%n", anns.size())
            addMonoArea(sb.toString())

            // Mod seçici (anında kaydeder + yeniler)
            def modeChoice = makeModeChoice()
            modeChoice.valueProperty().addListener({ obs, o, n ->
                if (n != null) { prefs.put(PREF_MODE, modeLabelToKey(n.toString())); try { prefs.flush() } catch (Throwable ig) {}; render() }
            } as javafx.beans.value.ChangeListener)
            def modeRow = new javafx.scene.layout.HBox(8)
            modeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            modeRow.getChildren().addAll(new javafx.scene.control.Label('Mod:'), modeChoice)
            center.getChildren().add(modeRow)

            boolean isTissue = (cfg.mode == 'tissue-mask')
            if (isTissue) {
                def maskChoice = makeMaskChoice()
                maskChoice.valueProperty().addListener({ obs, o, n ->
                    if (n != null) { prefs.put(PREF_MASK, n.toString()); try { prefs.flush() } catch (Throwable ig) {} }
                } as javafx.beans.value.ChangeListener)
                def r = new javafx.scene.layout.HBox(8); r.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
                r.getChildren().addAll(new javafx.scene.control.Label('Doku maske yöntemi:'), maskChoice,
                    new javafx.scene.control.Label('   downsample: ' + (cfg.maskDownsample ?: '16.0')))
                center.getChildren().add(r)
                addGuidance('Doku maskesi açık slaydın TAMAMINDAN üretilir ve hizalı "Doku" anotasyonu olarak içe aktarılır. Anotasyon gerekmez.')
            } else {
                def normChoice = makeNormChoice()
                normChoice.valueProperty().addListener({ obs, o, n ->
                    if (n != null) { prefs.put(PREF_NORM, n.toString()); try { prefs.flush() } catch (Throwable ig) {} }
                } as javafx.beans.value.ChangeListener)
                def r = new javafx.scene.layout.HBox(8); r.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
                r.getChildren().addAll(new javafx.scene.control.Label('Normalize yöntemi:'), normChoice,
                    new javafx.scene.control.Label('   referans: ' + (cfg.useBuiltin == 'true' ? 'yerleşik' : (cfg.reference?.trim() ? 'özel PNG' : 'ilk dolu karo'))))
                center.getChildren().add(r)
                if (cfg.mode == 'normalize-region')
                    addGuidance('Seçili (yoksa ilk) alan anotasyonu normalize edilir; orijinal | normalize montajı açılır. Çıktı görüntüdür, QuPath nesnesi değil.')
                else
                    addGuidance('Alan anotasyonlarının karoları normalize edilir → <çalışma>/normalized/. Çıktı görüntüdür (downstream ML için).')
            }

            boolean needAnn = !isTissue
            boolean canRun = configComplete(cfg) && (!needAnn || anns.size() >= 1)
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
            def runLabel = isTissue ? 'Doku maskesi çıkar ▶' : 'Normalize et ▶'
            def runBtn = navButton(runLabel, { startRun() }, 'Seçili modu çalıştırır')
            runBtn.setDisable(!canRun)
            if (!canRun && needAnn && anns.size() < 1) addWarnLabel('⚠ Boya normalizasyonu için en az 1 alan anotasyonu gerekir.')
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING') {
        title.setText(runPhaseRef.get())
        addGuidance('Python köprüsü koşuyor. Çıktı aşağıda akıyor. Zaman aşımı: ' + PYTHON_TIMEOUT_SECONDS + ' sn.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'BUSY') {
        title.setText(busyLabelRef.get()); addGuidance('Lütfen bekleyin…'); center.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅')
        addMonoArea(resultTextRef.get())
        def of = openFileRef.get()
        if (of != null) actions.add(navButton('Montajı aç', { openFileExternally(of) }))
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

    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlı veri/ölçüm üretir; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(center)
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 900, 680))
}

// ── Açılış durumu ───────────────────────────────────────────────────────────
step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('TIA Toolbox boya normalizasyonu / doku maskesi sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ TIA Toolbox boya normalizasyonu / doku maskesi sihirbazı açıldı."