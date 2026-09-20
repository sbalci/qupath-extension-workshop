/**
 * Yardımcı - NuClick Sihirbazı (tıkla → çekirdek sınırı)
 * -------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Slaytta **nokta işareti** (Points aracı) koyduğunuz her çekirdek için o
 *   çekirdeğin **piksel düzeyinde sınırını** üretir — tek pencereden:
 *     1. Aygıt + eşik seçin; gerekiyorsa ortamı kurun ve ağırlıkları indirin
 *        (② ③ düğmeleri).
 *     2. ÇALIŞTIR — her nokta çevresinde 128×128'lik bir yama diske yazılır;
 *        Python köprüsü (nuclick_bridge.py) her yama için tek çekirdek maskesi
 *        üretir.
 *     3. İçe aktarım — her nokta için bir **poligon tespit** nesnesi.
 *   Derin öğrenme QuPath DIŞINDA bir Python venv'inde çalışır; yamaları bu
 *   betik QuPath tarafında üretir (openslide gibi yerel kütüphane GEREKMEZ).
 *
 * OTOMATİK SEGMENTASYONDAN FARKI (ne zaman bunu seçersiniz):
 *   Cellpose / StarDist / InstanSeg bir alandaki HER ŞEYİ segmentler. NuClick
 *   ise YALNIZCA tıkladığınız çekirdeği segmentler. Seçici sayımda, zor/örtüşen
 *   çekirdeklerde ve eğitim verisi hazırlamada elle çizimden çok daha hızlıdır.
 *   HistoPLUS (J Pathol Inform 2026) eğitim ve konsensüs kümelerini tam olarak
 *   bu yolla üretmiştir: patolog merkezi işaretler, sınırı model çizer.
 *
 * NOKTALAR KAYNAK, TESPİTLER TÜREVDİR (yeniden çalıştırma davranışı):
 *   Her çalıştırma, mevcut TÜM noktalardan TÜM çekirdekleri yeniden üretir ve
 *   önceki "NuClick çekirdekleri" anotasyonunu alt nesneleriyle birlikte
 *   değiştirir. Yani nokta eklemeye devam edip yeniden çalıştırabilirsiniz;
 *   sonuç birikmez, çoğalmaz. Nokta anotasyonlarınıza hiç dokunulmaz.
 *
 * ÖLÇEK (sessiz hata kaynağı):
 *   NuClick ağırlıkları PanNuke ile, **~0,25 µm/px (40×)** ve 128×128 yama ile
 *   eğitilmiştir. Bu sihirbaz downsample'ı slaytın µm/px değerinden KENDİSİ
 *   türetir ve kalibrasyon yoksa / hedeften uzaksa UYARIR. Yanlış ölçekte model
 *   hata vermez — yalnızca yanlış cevap verir (WSInfer dersi).
 *   GT450 (~0,26 µm/px) ve AT2 (~0,25 µm/px) için bu pratikte level 0'dır.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Çıktı: çekirdek başına kontur + alan (µm² ya da px²).
 *   • Hücre TİPİ ATAMAZ — yalnız sınır üretir. Tip için: HistoPLUS / CytoFormer.
 *   • Klinik eşik / derece / alt-tip üretmez.
 *
 * LİSANS ZİNCİRİ (önemli):
 *   • Upstream `nuclick_torch` (CC BY-NC-SA 4.0) KULLANILMAZ, indirilmez, port
 *     EDİLMEZ. Ağ, TIA Toolbox'ın BSD-3-Clause yeniden uygulamasıdır.
 *   • Ağırlık `nuclick_original-pannuke` PanNuke ile eğitilmiştir →
 *     **CC BY-NC-SA 4.0** (ticari kullanım yok). Atölye kullanımı uyumludur.
 *
 * NEDEN 'light' VARYANTI YOK:
 *   Kayıttaki `nuclick_light-pannuke` (~5 MB) farklı bir sınıftır
 *   (`unet.UNetModel`): girdiyi NHWC bekler, softmax + ×2 interpolasyon + argmax
 *   uygular ve kayıt onun için de tek çıkış kanalı der — tek kanalda softmax
 *   sabit 1 üretir. Hiçbir upstream test bu varyantı kapsamaz; sessizce yanlış
 *   çalışırdı. Bu yüzden yalnız `original` (~255 MB) sunulur.
 *
 * KULLANIM:
 *   1. [Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller →
 *      Atölye Python ortam yöneticisi] ile 'nuclick' ortamını kurun (② düğmesi
 *      bunu doğrudan açar).
 *   2. Bir slayt açın. Araç çubuğundan **Points** aracını seçin ve saymak
 *      istediğiniz çekirdeklerin ortasına tıklayın.
 *   3. Bu sihirbazı açın → ③ "Model ağırlıklarını indir" → "Çalıştır".
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Koohbanani NA, Jahanifar M, Tajadin NZ, Rajpoot N (2020) "NuClick: A Deep
 *     Learning Framework for Interactive Segmentation of Microscopic Images."
 *     Med Image Anal 65:101771. arXiv:2005.14511
 *   • Taşıyıcı: TIA Toolbox (BSD-3-Clause). Kanonik kullanım sırası upstream
 *     birim testinden alınmıştır (tests/models/test_arch_nuclick.py).
 *   • Ek: ekler/nuclick.qmd
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.ROIs
import qupath.lib.objects.PathObjects
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ────────────────────────────────────────────────────────────────
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def DEVICE_OPTIONS = ['auto', 'cpu', 'cuda']
final double NUCLICK_MPP = 0.25d      // PanNuke eğitim çözünürlüğü
final int    TILE        = 128        // NuClick yama boyu — DEĞİŞTİRMEYİN
def SENTINEL   = 'NuClick çekirdekleri'
def NUC_CLASS  = 'NuClick çekirdek'
def AREA_UM    = 'NuClick: Alan (µm²)'
def AREA_PX    = 'NuClick: Alan (piksel²)'
def DRIFT_MEAS = 'NuClick: Tıklama maske dışında'

def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/nuclick')
def PREF_PYTHON = 'python'
def PREF_BRIDGE = 'bridge'
def PREF_WORK   = 'workDir'
def PREF_DS     = 'downsample'
def PREF_MAXP   = 'maxPoints'
def PREF_THRESH = 'threshold'
def PREF_DEVICE = 'device'

def atolyeDataRoot = { ->
    def common = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common')
    def v = common.get('dataRoot', '')
    return (v ? new File(v) : new File(System.getProperty('user.home'), '.atolye'))
}
// Python önbelleklerini atölye veri köküne yönlendir (C: dolmasın).
def applyCacheEnv = { pb ->
    def root = atolyeDataRoot()
    def env = pb.environment()
    env.put('TIATOOLBOX_HOME', new File(new File(root, 'cache'), 'tiatoolbox').getAbsolutePath())
    env.put('TORCH_HOME',      new File(new File(root, 'cache'), 'torch').getAbsolutePath())
    env.put('HF_HOME',         new File(new File(root, 'cache'), 'huggingface').getAbsolutePath())
    return pb
}

def loadConfig = { ->
    [python    : prefs.get(PREF_PYTHON, ''),
     bridge    : prefs.get(PREF_BRIDGE, ''),
     workDir   : prefs.get(PREF_WORK, ''),
     downsample: prefs.get(PREF_DS, ''),
     maxPoints : prefs.get(PREF_MAXP, '2000'),
     threshold : prefs.get(PREF_THRESH, '0.33'),
     device    : prefs.get(PREF_DEVICE, 'auto')]
}
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim())  miss << "Python yorumlayıcısı ('nuclick' ortamının python.exe yolu)"
    if (!cfg.bridge?.trim())  miss << 'nuclick_bridge.py yolu'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

def parseIntOr = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }
def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().trim()) } catch (Throwable t) { return d } }

// ── Ölçek ───────────────────────────────────────────────────────────────────
def slideMpp = { imageData ->
    try {
        def cal = imageData.getServer().getPixelCalibration()
        if (cal != null && cal.hasPixelSizeMicrons()) return cal.getAveragedPixelSizeMicrons()
    } catch (Throwable ignore) {}
    return Double.NaN
}
def suggestedDownsample = { imageData ->
    double mpp = slideMpp(imageData)
    if (Double.isNaN(mpp) || mpp <= 0.0d) return 1.0d
    return NUCLICK_MPP / mpp
}
def effectiveDownsample = { cfg, imageData ->
    def s = (cfg.downsample ?: '').toString().trim()
    return s ? parseDoubleOr(s, 1.0d) : suggestedDownsample(imageData)
}
def scaleWarning = { cfg, imageData ->
    double mpp = slideMpp(imageData)
    if (Double.isNaN(mpp))
        return '⚠ Slayt KALİBRE DEĞİL (µm/px yok). NuClick 40× (~0,25 µm/px) bekler; ölçek doğrulanamıyor — sonuçlar sessizce yanlış olabilir.'
    double ds = effectiveDownsample(cfg, imageData)
    double eff = mpp * ds
    if (Math.abs(eff - NUCLICK_MPP) > (NUCLICK_MPP * 0.25d))
        return String.format(java.util.Locale.US,
            '⚠ Etkin çözünürlük %.3f µm/px — NuClick %.2f µm/px (40×) ile eğitildi. Downsample\'ı boş bırakın (otomatik) ya da düzeltin.', eff, NUCLICK_MPP)
    return null
}

def resolveWorkDir = { cfg, imageData ->
    def wd = cfg.workDir?.trim()
    if (wd) return new File(wd)
    def project = QP.getProject()
    if (project != null && project.getPath() != null)
        return new File(project.getPath().getParent().toFile(), 'nuclick_work')
    return new File(System.getProperty('java.io.tmpdir'), 'nuclick_work')
}
def imageNameOf = { imageData ->
    try { return imageData.getServer().getMetadata().getName() ?: 'slayt' } catch (Throwable t) { return 'slayt' }
}
def writePng = { img, File f -> if (f.getParentFile() != null) f.getParentFile().mkdirs(); javax.imageio.ImageIO.write(img, 'PNG', f) }
def writeJson = { obj, File f ->
    if (f.getParentFile() != null) f.getParentFile().mkdirs()
    f.setText(qupath.lib.io.GsonTools.getInstance(true).toJson(obj), 'UTF-8')
}

// ── Noktaları topla ─────────────────────────────────────────────────────────
//   Seçili nokta anotasyonları varsa yalnız onlar; yoksa slayttaki TÜM nokta
//   anotasyonları. Bir anotasyon birden çok nokta taşıyabilir — hepsi açılır.
def collectPoints = { imageData ->
    def hierarchy = imageData.getHierarchy()
    def selected = hierarchy.getSelectionModel().getSelectedObjects()
            .findAll { it.isAnnotation() && it.getROI() != null && it.getROI().isPoint() }
    def sources = selected ? selected : hierarchy.getAnnotationObjects()
            .findAll { it.getROI() != null && it.getROI().isPoint() }
    def pts = []
    sources.each { ann ->
        try {
            ann.getROI().getAllPoints().each { p -> pts << [x: p.getX(), y: p.getY()] }
        } catch (Throwable ignore) {}
    }
    return [points: pts, fromSelection: (selected ? true : false), nSources: sources.size()]
}

// ── Yamaları dışa aktar + points.json yaz ───────────────────────────────────
def exportPatches = { imageData, File workDir, cfg, Closure appendLine ->
    def server = imageData.getServer()
    double ds = effectiveDownsample(cfg, imageData)
    int phys = (int) Math.max(1, Math.round(TILE * ds))
    int sw = server.getWidth(), sh = server.getHeight()

    def col = collectPoints(imageData)
    def pts = col.points
    if (pts.isEmpty())
        return [ok: false, error: 'Hiç NOKTA işareti bulunamadı.\n\nAraç çubuğundan "Points" aracını seçip saymak istediğiniz\nçekirdeklerin ortasına tıklayın, sonra yeniden çalıştırın.']
    if (phys > sw || phys > sh)
        return [ok: false, error: 'Slayt 128×128 yama için çok küçük (downsample çok yüksek).']

    int maxPoints = parseIntOr(cfg.maxPoints, 2000)
    boolean capped = (maxPoints > 0 && pts.size() > maxPoints)
    if (capped) {
        appendLine('UYARI: ' + pts.size() + ' nokta var; ilk ' + maxPoints + ' tanesi işlenecek ("Maks. nokta" ayarı).')
        pts = pts.subList(0, maxPoints)
    }

    def patchDir = new File(workDir, 'patches')
    patchDir.mkdirs()
    // Eski yamalar birikmesin (yeniden çalıştırma idempotent olsun).
    patchDir.listFiles()?.each { if (it.isFile() && it.getName().endsWith('.png')) it.delete() }

    def items = []
    def geom = [:]
    int idx = 0, skipped = 0
    pts.each { p ->
        idx++
        String id = String.format(java.util.Locale.US, 'p%06d', idx)
        double half = phys / 2.0d
        // Yamanın sol-üstü; slayt kenarında içeri doğru kaydırılır.
        double ox = p.x - half, oy = p.y - half
        if (ox < 0) ox = 0
        if (oy < 0) oy = 0
        if (ox + phys > sw) ox = sw - phys
        if (oy + phys > sh) oy = sh - phys
        def img
        try {
            img = server.readRegion(RegionRequest.createInstance(
                    server.getPath(), ds, (int) Math.round(ox), (int) Math.round(oy), phys, phys))
        } catch (Throwable t) { skipped++; return }
        if (img == null) { skipped++; return }
        // readRegion yuvarlama nedeniyle 128'den 1-2 piksel sapabilir — sabitle.
        if (img.getWidth() != TILE || img.getHeight() != TILE) {
            def scaled = new java.awt.image.BufferedImage(TILE, TILE, java.awt.image.BufferedImage.TYPE_INT_RGB)
            def g2 = scaled.createGraphics()
            g2.drawImage(img, 0, 0, TILE, TILE, null)
            g2.dispose()
            img = scaled
        }
        writePng(img, new File(patchDir, id + '.png'))

        // Tıklamanın YAMA-YEREL koordinatı
        double lx = (p.x - ox) / ds, ly = (p.y - oy) / ds
        lx = Math.max(0.0d, Math.min(TILE - 1.0d, lx))
        ly = Math.max(0.0d, Math.min(TILE - 1.0d, ly))

        // exclusion map: AYNI yamaya düşen DİĞER tüm tıklamalar. Eksik verilirse
        // maske komşu çekirdeğe taşabilir — bu yüzden tüm noktalara bakılır.
        def others = []
        pts.each { q ->
            if (q.is(p)) return
            if (q.x >= ox && q.x < ox + phys && q.y >= oy && q.y < oy + phys) {
                double qx = (q.x - ox) / ds, qy = (q.y - oy) / ds
                if (qx >= 0 && qx < TILE && qy >= 0 && qy < TILE) others << [qx, qy]
            }
        }
        items << [id: id, patch_file: (id + '.png'), click: [lx, ly], others: others]
        geom[id] = [ox: ox, oy: oy, ds: ds]
    }

    if (items.isEmpty())
        return [ok: false, error: 'Hiçbir yama okunamadı (slayt erişilemez olabilir).']

    writeJson([tile_size: TILE, items: items], new File(workDir, 'points.json'))
    appendLine('Yama yazıldı: ' + items.size() + (skipped > 0 ? (' (atlanan: ' + skipped + ')') : ''))
    return [ok: true, n: items.size(), skipped: skipped, geom: geom, ds: ds,
            capped: capped, nTotal: col.points.size(), fromSelection: col.fromSelection]
}

// ── Komutlar ────────────────────────────────────────────────────────────────
def predictCmd = { cfg, File workDir ->
    def c = [cfg.python.trim(), cfg.bridge.trim(), 'predict',
             '--points', new File(workDir, 'points.json').getAbsolutePath(),
             '--patches-dir', new File(workDir, 'patches').getAbsolutePath(),
             '--output-dir', workDir.getAbsolutePath(),
             '--device', (cfg.device ?: 'auto'),
             '--thresh', String.format(java.util.Locale.US, '%.3f', parseDoubleOr(cfg.threshold, 0.33d))]
    return c
}
def selftestCmd = { cfg -> [cfg.python.trim(), cfg.bridge.trim(), 'selftest', '--device', (cfg.device ?: 'auto')] }
def downloadCmd = { cfg -> [cfg.python.trim(), cfg.bridge.trim(), 'download'] }

// ── Durum ───────────────────────────────────────────────────────────────────
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def processRef    = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def busyLabelRef  = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def pyFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def bridgeFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def workFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def dsFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def maxPFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def threshFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def deviceChoiceRef= new java.util.concurrent.atomic.AtomicReference(null)
def render  // ileri bildirim

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

// ── Sonuçları içe aktar ─────────────────────────────────────────────────────
def importResults = { File workDir, imageData, exp ->
    def predsFile = new File(workDir, 'predictions.json')
    if (!predsFile.isFile())
        return [ok: false, error: 'predictions.json bulunamadı:\n' + predsFile.getAbsolutePath()]

    def records = []
    try {
        def reader = new java.io.InputStreamReader(new java.io.FileInputStream(predsFile), java.nio.charset.StandardCharsets.UTF_8)
        try {
            def root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject()
            root.getAsJsonArray('items').each { el ->
                def o = el.getAsJsonObject()
                if (!o.has('id') || o.get('id').isJsonNull()) return
                def rec = [id: o.get('id').getAsString(),
                           ok: (o.has('ok') && !o.get('ok').isJsonNull()) ? o.get('ok').getAsBoolean() : false]
                rec.reason = (o.has('reason') && !o.get('reason').isJsonNull()) ? o.get('reason').getAsString() : null
                rec.clickInside = (o.has('click_inside') && !o.get('click_inside').isJsonNull()) ? o.get('click_inside').getAsBoolean() : true
                rec.areaPx = (o.has('area_px') && !o.get('area_px').isJsonNull()) ? o.get('area_px').getAsDouble() : Double.NaN
                rec.contour = []
                if (o.has('contour') && o.get('contour').isJsonArray())
                    o.getAsJsonArray('contour').each { pe ->
                        def pa = pe.getAsJsonArray()
                        rec.contour << [pa.get(0).getAsDouble(), pa.get(1).getAsDouble()]
                    }
                records << rec
            }
        } finally { reader.close() }
    } catch (Throwable t) {
        return [ok: false, error: 'predictions.json okunamadı:\n' + (t.getMessage() ?: t.getClass().getSimpleName())]
    }

    def hierarchy = imageData.getHierarchy()
    def plane = qupath.lib.regions.ImagePlane.getDefaultPlane()
    def pc = QP.getPathClass(NUC_CLASS)
    double mpp = slideMpp(imageData)
    boolean calibrated = !Double.isNaN(mpp) && mpp > 0.0d
    double ds = (double) exp.ds

    def dets = []
    int nOk = 0, nFail = 0, nDrift = 0
    double areaSum = 0.0d

    records.each { r ->
        if (!r.ok || r.contour.size() < 3) { nFail++; return }
        def g = exp.geom[r.id]
        if (g == null) { nFail++; return }
        int n = r.contour.size()
        double[] xs = new double[n]
        double[] ys = new double[n]
        for (int i = 0; i < n; i++) {
            // Yama-yerel piksel → slayt (level 0) koordinatı
            xs[i] = ((double) g.ox) + (((double) r.contour[i][0]) * ds)
            ys[i] = ((double) g.oy) + (((double) r.contour[i][1]) * ds)
        }
        def roi = ROIs.createPolygonROI(xs, ys, plane)
        def det = PathObjects.createDetectionObject(roi, pc)
        def ml = det.getMeasurementList()
        double areaSlidePx = (double) r.areaPx * ds * ds
        if (calibrated) {
            double um2 = areaSlidePx * mpp * mpp
            ml.put(AREA_UM, um2)
            areaSum += um2
        } else {
            ml.put(AREA_PX, areaSlidePx)
            areaSum += areaSlidePx
        }
        if (!r.clickInside) { ml.put(DRIFT_MEAS, 1.0d); nDrift++ }
        dets << det
        nOk++
    }

    // Noktalar kaynak, tespitler türev: önceki çalıştırmanın sentineli alt
    // nesneleriyle birlikte silinir, sonuç birikmez. Nokta anotasyonlarına
    // DOKUNULMAZ.
    hierarchy.removeObjects(hierarchy.getAnnotationObjects().findAll { it.getName() == SENTINEL }, false)

    def srv = imageData.getServer()
    def sentinelRoi = ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(), plane)
    def sentinel = PathObjects.createAnnotationObject(sentinelRoi)
    sentinel.setName(SENTINEL)
    if (!dets.isEmpty()) sentinel.addChildObjects(dets)
    sentinel.setLocked(true)
    hierarchy.addObjects([sentinel])
    QP.fireHierarchyUpdate()
    javafx.application.Platform.runLater { try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {} }

    double meanArea = (nOk > 0) ? (areaSum / nOk) : 0.0d
    return [ok: true, nOk: nOk, nFail: nFail, nDrift: nDrift,
            meanArea: meanArea, calibrated: calibrated]
}

// ── Özet metni ──────────────────────────────────────────────────────────────
def buildResultText = { exp, imp, cfg, imageData ->
    def sb = new StringBuilder()
    sb << "NuClick — ÇEKİRDEK SINIRLARI\n"
    sb << "═══════════════════════════\n\n"
    sb << "Slayt          : " << imageNameOf(imageData) << "\n"
    sb << "Kaynak noktalar: " << exp.nTotal << (exp.fromSelection ? "  (seçili anotasyonlardan)" : "  (slayttaki tüm nokta anotasyonları)") << "\n"
    if (exp.capped) sb << "                 ⚠ sınır nedeniyle yalnız " << exp.n << " tanesi işlendi\n"
    double mpp = slideMpp(imageData)
    if (!Double.isNaN(mpp))
        sb << String.format(java.util.Locale.US, "Ölçek          : %.3f µm/px × downsample %.2f = %.3f µm/px (hedef %.2f)\n",
                mpp, (double) exp.ds, mpp * ((double) exp.ds), NUCLICK_MPP)
    else
        sb << "Ölçek          : slayt KALİBRE DEĞİL — ölçek doğrulanamadı\n"
    sb << "\n"
    sb << "Sınırlandırılan: " << imp.nOk << " / " << exp.n << "\n"
    if (imp.nFail > 0)  sb << "Başarısız      : " << imp.nFail << "  (boş maske — eşiği düşürmeyi deneyin)\n"
    if (imp.nDrift > 0) sb << "⚠ Şüpheli      : " << imp.nDrift << "  (tıklama maskenin dışında kaldı — gözle doğrulayın)\n"
    if (imp.nOk > 0) {
        sb << String.format(java.util.Locale.US, "Ortalama alan  : %.1f %s\n",
                (double) imp.meanArea, imp.calibrated ? "µm²" : "piksel²")
    }
    sb << "\nNesneler \"" << SENTINEL << "\" anotasyonunun altına yazıldı.\n"
    sb << "Nokta eklemeye devam edip yeniden çalıştırabilirsiniz — sonuç birikmez,\n"
    sb << "her çalıştırma tüm noktalardan yeniden üretir.\n"
    sb << "\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.\n"
    return sb.toString()
}

// ── Ortam yöneticisine dönüş kancası ────────────────────────────────────────
def envReturnHook = [
    envId   : 'nuclick',
    wizard  : 'NuClick (tıkla → çekirdek sınırı)',
    onReturn: { reopen ->
        javafx.application.Platform.runLater {
            if (stage == null || (!stage.isShowing() && !reopen)) return
            try {
                if (['CONFIG_INCOMPLETE', 'CONFIG', 'READY', 'CHECK_DONE', 'DL_DONE', 'ERROR'].contains(step.get())) {
                    step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render()
                }
                if (stage.isIconified()) stage.setIconified(false)
                if (!stage.isShowing()) stage.show()
                stage.toFront(); stage.requestFocus()
            } catch (Throwable t) {
                Dialogs.showErrorMessage('Sihirbaza dönüş', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
            }
        }
    }
]
def launchEnvManager = { ->
    new Thread({
        try {
            def res = 'yardimci-python-ortam-yoneticisi.groovy'
            def url = null
            try { url = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getResource('/scripts/' + res) } catch (Throwable t) {}
            if (url == null) url = this.getClass().getResource('/scripts/' + res)
            if (url == null) {
                javafx.application.Platform.runLater { Dialogs.showInfoNotification('Betik bulunamadı',
                    'Menüden açın: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi ("nuclick" kaydını kurun).') }
                return
            }
            def cl = this.getClass().getClassLoader()
            try { cl = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getClassLoader() } catch (Throwable t) {}
            def shellBinding = new Binding()
            shellBinding.setVariable('atolyeReturnHook', envReturnHook)
            new GroovyShell(cl, shellBinding).evaluate(url.getText('UTF-8'), res)
        } catch (Throwable t) {
            javafx.application.Platform.runLater { Dialogs.showErrorMessage('Açılamadı', (t.getMessage() ?: t.getClass().getSimpleName())) }
        }
    } as Runnable).start()
}

// ── Alanları kalıcılaştır ───────────────────────────────────────────────────
def persistFields = {
    try {
        if (pyFieldRef.get() != null)     prefs.put(PREF_PYTHON, pyFieldRef.get().getText()?.trim() ?: '')
        if (bridgeFieldRef.get() != null) prefs.put(PREF_BRIDGE, bridgeFieldRef.get().getText()?.trim() ?: '')
        if (workFieldRef.get() != null)   prefs.put(PREF_WORK,   workFieldRef.get().getText()?.trim() ?: '')
        if (dsFieldRef.get() != null)     prefs.put(PREF_DS,     dsFieldRef.get().getText()?.trim() ?: '')
        if (maxPFieldRef.get() != null)   prefs.put(PREF_MAXP,   maxPFieldRef.get().getText()?.trim() ?: '2000')
        if (threshFieldRef.get() != null) prefs.put(PREF_THRESH, threshFieldRef.get().getText()?.trim() ?: '0.33')
        if (deviceChoiceRef.get() != null) prefs.put(PREF_DEVICE, (deviceChoiceRef.get().getValue() ?: 'auto').toString())
    } catch (Throwable ignore) {}
}

// ── Süreç çalıştırıcı ───────────────────────────────────────────────────────
def appendLog = { String line ->
    javafx.application.Platform.runLater {
        def ta = logAreaRef.get()
        if (ta != null) { ta.appendText(line + '\n') }
    }
}
def runProcess = { List cmd, File dir, Closure onLine ->
    def pb = new ProcessBuilder(cmd)
    if (dir != null) pb.directory(dir)
    pb.redirectErrorStream(true)
    applyCacheEnv(pb)
    def proc = pb.start()
    processRef.set(proc)
    def rd = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
    try {
        String ln
        while ((ln = rd.readLine()) != null) onLine(ln)
    } finally { try { rd.close() } catch (Throwable ignore) {} }
    int rc = proc.waitFor()
    processRef.set(null)
    return rc
}

def startSimple = { String phase, List cmd, String okStep ->
    persistFields()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set('Önce yapılandırmayı tamamlayın:\n  • ' + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    cancelledRef.set(false)
    busyLabelRef.set(phase)
    step.set('BUSY'); render()
    new Thread({
        int rc = -1
        try {
            rc = runProcess(cmd, null, { String ln -> appendLog(ln) })
        } catch (Throwable t) {
            appendLog('HATA: ' + (t.getMessage() ?: t.getClass().getSimpleName()))
        }
        final int frc = rc
        javafx.application.Platform.runLater {
            if (cancelledRef.get()) { step.set('READY'); render(); return }
            if (frc == 0) { step.set(okStep) } else { errorTextRef.set(phase + ' başarısız (çıkış kodu ' + frc + '). Ayrıntı için günlüğe bakın.'); step.set('ERROR') }
            render()
        }
    } as Runnable).start()
}

def startRun = {
    persistFields()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set('Önce yapılandırmayı tamamlayın:\n  • ' + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Önce bir slayt açın.'); step.set('ERROR'); render(); return }
    cancelledRef.set(false)
    busyLabelRef.set('Çalıştırılıyor')
    step.set('BUSY'); render()
    new Thread({
        try {
            def workDir = resolveWorkDir(cfg, imageData)
            workDir.mkdirs()
            appendLog('Çalışma dizini: ' + workDir.getAbsolutePath())
            def exp = exportPatches(imageData, workDir, cfg, { String l -> appendLog(l) })
            if (!exp.ok) {
                javafx.application.Platform.runLater { errorTextRef.set(exp.error); step.set('ERROR'); render() }
                return
            }
            if (cancelledRef.get()) { javafx.application.Platform.runLater { step.set('READY'); render() }; return }
            int rc = runProcess(predictCmd(cfg, workDir), null, { String ln -> appendLog(ln) })
            if (cancelledRef.get()) { javafx.application.Platform.runLater { step.set('READY'); render() }; return }
            if (rc != 0) {
                javafx.application.Platform.runLater {
                    errorTextRef.set('Python köprüsü başarısız (çıkış kodu ' + rc + ').\nGünlüğe bakın; ortam ya da ağırlık eksik olabilir (② ③ düğmeleri).')
                    step.set('ERROR'); render()
                }
                return
            }
            javafx.application.Platform.runLater {
                def imp = importResults(workDir, imageData, exp)
                if (!imp.ok) { errorTextRef.set(imp.error); step.set('ERROR'); render(); return }
                resultTextRef.set(buildResultText(exp, imp, cfg, imageData))
                step.set('DONE'); render()
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater {
                errorTextRef.set((t.getMessage() ?: t.getClass().getSimpleName()))
                step.set('ERROR'); render()
            }
        }
    } as Runnable).start()
}

// ── Arayüz ──────────────────────────────────────────────────────────────────
if (isHeadless) {
    print 'Bu sihirbaz QuPath arayüzü gerektirir (headless çalıştırılamaz).'
    return
}

def root = new javafx.scene.layout.VBox(10)
root.setPadding(new javafx.geometry.Insets(14))
root.setPrefWidth(760)
def title = new javafx.scene.control.Label()
title.setStyle('-fx-font-size: 15px; -fx-font-weight: bold;')
def body = new javafx.scene.layout.VBox(8)
def actionBar = new javafx.scene.layout.HBox(8)
actionBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
root.getChildren().addAll(title, new javafx.scene.control.Separator(), body, actionBar)

render = {
    def cfg = loadConfig()
    def imageData = QP.getCurrentImageData()
    body.getChildren().clear()
    actionBar.getChildren().clear()
    def actions = actionBar.getChildren()
    String cur = step.get()

    if (cur == 'READY' || cur == 'CONFIG_INCOMPLETE' || cur == 'CHECK_DONE' || cur == 'DL_DONE') {
        title.setText('NuClick — tıkla → çekirdek sınırı')
        def info = new javafx.scene.control.Label(
            'Slaytta "Points" aracıyla işaretlediğiniz her çekirdeğin sınırını üretir.\n' +
            'Hücre TİPİ atamaz — yalnız sınır çizer.')
        info.setWrapText(true)
        body.getChildren().add(info)

        if (imageData != null) {
            def col = collectPoints(imageData)
            def n = col.points.size()
            def lbl = new javafx.scene.control.Label(
                n == 0 ? '⚠ Hiç nokta işareti yok — önce "Points" aracıyla çekirdeklere tıklayın.'
                       : ('Bulunan nokta: ' + n + (col.fromSelection ? '  (seçili anotasyonlardan)' : '  (tüm nokta anotasyonları)')))
            lbl.setWrapText(true)
            if (n == 0) lbl.setStyle('-fx-text-fill: #b45309;')
            body.getChildren().add(lbl)
            def warn = scaleWarning(cfg, imageData)
            if (warn) {
                def w = new javafx.scene.control.Label(warn); w.setWrapText(true)
                w.setStyle('-fx-text-fill: #b45309;')
                body.getChildren().add(w)
            }
        } else {
            def l = new javafx.scene.control.Label('⚠ Açık slayt yok.')
            l.setStyle('-fx-text-fill: #b45309;')
            body.getChildren().add(l)
        }

        if (cur == 'CHECK_DONE') body.getChildren().add(new javafx.scene.control.Label('✓ Ortam denetimi tamam.'))
        if (cur == 'DL_DONE')    body.getChildren().add(new javafx.scene.control.Label('✓ Model ağırlıkları hazır.'))
        if (cur == 'CONFIG_INCOMPLETE') {
            def l = new javafx.scene.control.Label('Yapılandırma eksik:\n  • ' + configMissing(cfg).join('\n  • '))
            l.setWrapText(true); l.setStyle('-fx-text-fill: #b45309;')
            body.getChildren().add(l)
        }

        actions.add(navButton('② Ortamı kur', { launchEnvManager() }, 'Atölye Python ortam yöneticisini açar ve "nuclick" kaydını vurgular'))
        actions.add(navButton('③ Model ağırlıklarını indir', { startSimple('Ağırlık indirme', downloadCmd(cfg), 'DL_DONE') }, '~255 MB, bir kez'))
        actions.add(navButton('Bağımlılıkları denetle', { startSimple('Ortam denetimi', selftestCmd(cfg), 'CHECK_DONE') }))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
        def runBtn = navButton('Çalıştır', { startRun() })
        runBtn.setDefaultButton(true)
        runBtn.setDisable(imageData == null || !configComplete(cfg))
        actions.add(runBtn)

    } else if (cur == 'CONFIG') {
        title.setText('NuClick yapılandırması')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def brField = new javafx.scene.control.TextField(cfg.bridge ?: '')
        def wdField = new javafx.scene.control.TextField(cfg.workDir ?: '')
        def dsField = new javafx.scene.control.TextField(cfg.downsample ?: '')
        def mpField = new javafx.scene.control.TextField(cfg.maxPoints ?: '2000')
        def thField = new javafx.scene.control.TextField(cfg.threshold ?: '0.33')
        [pyField, brField, wdField].each { it.setPrefColumnCount(34) }
        [dsField, mpField, thField].each { it.setPrefColumnCount(8) }
        def devChoice = new javafx.scene.control.ChoiceBox()
        devChoice.getItems().addAll(DEVICE_OPTIONS)
        devChoice.setValue(cfg.device ?: 'auto')
        pyFieldRef.set(pyField); bridgeFieldRef.set(brField); workFieldRef.set(wdField)
        dsFieldRef.set(dsField); maxPFieldRef.set(mpField); threshFieldRef.set(thField)
        deviceChoiceRef.set(devChoice)

        def browse = { javafx.scene.control.TextField f, String titleTxt, boolean dirOnly ->
            navButton('…', {
                try {
                    def sel = dirOnly ? qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, titleTxt, null)
                                      : qupath.fx.dialogs.FileChoosers.promptForFile(stage, titleTxt)
                    if (sel != null) f.setText(sel.getAbsolutePath())
                } catch (Throwable t) { Dialogs.showErrorMessage('Seçilemedi', (t.getMessage() ?: t.getClass().getSimpleName())) }
            })
        }
        int r = 0
        grid.add(new javafx.scene.control.Label('Python (nuclick ortamı):'), 0, r); grid.add(pyField, 1, r); grid.add(browse(pyField, 'python.exe seçin', false), 2, r); r++
        grid.add(new javafx.scene.control.Label('nuclick_bridge.py:'), 0, r); grid.add(brField, 1, r); grid.add(browse(brField, 'nuclick_bridge.py seçin', false), 2, r); r++
        grid.add(new javafx.scene.control.Label('Çalışma dizini (boş = proje yanı):'), 0, r); grid.add(wdField, 1, r); grid.add(browse(wdField, 'Klasör seçin', true), 2, r); r++
        grid.add(new javafx.scene.control.Label('Downsample (boş = otomatik):'), 0, r); grid.add(dsField, 1, r); r++
        grid.add(new javafx.scene.control.Label('Maks. nokta:'), 0, r); grid.add(mpField, 1, r); r++
        grid.add(new javafx.scene.control.Label('Eşik (0-1, varsayılan 0,33):'), 0, r); grid.add(thField, 1, r); r++
        grid.add(new javafx.scene.control.Label('Aygıt:'), 0, r); grid.add(devChoice, 1, r); r++
        body.getChildren().add(grid)
        def hint = new javafx.scene.control.Label(
            'Downsample boş bırakılırsa slaytın µm/px değerinden hedef 0,25 µm/px (40×) için otomatik hesaplanır — önerilen budur.')
        hint.setWrapText(true)
        body.getChildren().add(hint)
        actions.add(navButton('◀ Geri', { persistFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('Kaydet', { persistFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))

    } else if (cur == 'BUSY') {
        title.setText('NuClick — ' + busyLabelRef.get())
        body.getChildren().add(busyBar())
        def ta = new javafx.scene.control.TextArea()
        ta.setEditable(false); ta.setPrefRowCount(16); ta.setStyle(MONO)
        logAreaRef.set(ta)
        body.getChildren().add(ta)
        actions.add(navButton('İptal', {
            cancelledRef.set(true)
            def p = processRef.get()
            if (p != null) { try { p.destroy() } catch (Throwable ignore) {} }
        }))

    } else if (cur == 'DONE') {
        title.setText('NuClick — bitti')
        def ta = new javafx.scene.control.TextArea(resultTextRef.get())
        ta.setEditable(false); ta.setPrefRowCount(16); ta.setStyle(MONO)
        body.getChildren().add(ta)
        actions.add(navButton('Panoya kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('◀ Başa dön', { step.set('READY'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))

    } else if (cur == 'ERROR') {
        title.setText('NuClick — hata')
        def ta = new javafx.scene.control.TextArea(errorTextRef.get())
        ta.setEditable(false); ta.setPrefRowCount(10); ta.setStyle(MONO)
        body.getChildren().add(ta)
        actions.add(navButton('② Ortamı kur', { launchEnvManager() }))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
        actions.add(navButton('◀ Başa dön', { step.set('READY'); render() }))
    }

    def spacer = new javafx.scene.layout.Region()
    javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    actions.add(spacer)
    def topCheck = new javafx.scene.control.CheckBox('Üstte kalsın')
    topCheck.setSelected(alwaysTop.get())
    topCheck.selectedProperty().addListener({ o, ov, nv ->
        alwaysTop.set(nv); try { stage.setAlwaysOnTop(nv) } catch (Throwable ignore) {}
    } as javafx.beans.value.ChangeListener)
    actions.add(topCheck)
}

javafx.application.Platform.runLater {
    stage = new javafx.stage.Stage()
    stage.setTitle('NuClick — tıkla → çekirdek sınırı')
    stage.initOwner(gui.getStage())
    stage.setAlwaysOnTop(alwaysTop.get())
    stage.setScene(new javafx.scene.Scene(root))
    step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')
    render()
    stage.show()
}
