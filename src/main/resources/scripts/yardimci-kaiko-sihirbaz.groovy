/**
 * Yardımcı - Kaiko Midnight Sınıflandırıcı Sihirbazı (tek pencere köprü)
 * ---------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Kaiko AI'nin **Midnight** patoloji foundation modelini (kaiko-ai/midnight,
 *   DINOv2-giant, MIT lisanslı) kullanarak DENETİMLİ bir doku sınıflandırıcısı
 *   eğitir ve uygular — hepsi tek pencereden:
 *     1. EĞİTİM — açık slayttaki SINIFLI (PathClass atanmış) anotasyonların
 *        karolarını diske yazar; Python köprüsü (kaiko_bridge.py) bu karolardan
 *        Midnight gömmeleri çıkarıp bir Random Forest eğitir.
 *     2. TAHMİN — SINIFSIZ (etiketsiz) anotasyonların karolarını yazar; köprü
 *        bunları tahmin eder; sihirbaz her anotasyona sınıf + güven + sınıf
 *        olasılığı ölçümlerini geri yazar.
 *   Derin öğrenme QuPath DIŞINDA bir Python venv'inde koşar; bu betik karoları
 *   QuPath tarafında üretir (openslide gibi yerel kütüphane GEREKMEZ).
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Sınıflar tamamen SİZİN anotasyonlarınıza atadığınız PathClass isimleridir;
 *     bu betik hiçbir hastalık/tanı sınıfını sabitlemez (RCC/onkositom vb. yok).
 *   • Çıktı yalnız sınıf etiketi + güven + olasılık ölçümüdür; patoloji yorumu,
 *     grade veya klinik karar üretmez. Midnight çıktısı bir DERİN ÖĞRENME
 *     TAHMİNİDİR; görsel doğrulama gerekir (Ek W).
 *   • Lisans: Midnight MIT (kapısız, kayıt gerektirmez).
 *
 * KULLANIM:
 *   1. Kaiko Python ortamını kurun (venv). Bkz. Ekler → Kaiko Midnight § kurulum
 *      veya Kaynaklar → § İleri kurulumlar F.
 *   2. Bir slayt açın; en az 2 sınıf için sınıflı anotasyonlar + en az 1 sınıfsız
 *      anotasyon çizin.
 *   3. [Extensions → Atölye → Yardımcılar → Kaiko Midnight sınıflandırıcı sihirbazı]
 *   4. İlk açılışta yapılandırın: python.exe, kaiko_bridge.py, (ops.) model dizini.
 *   5. "Eğit ve Tahmin Et" → çıktı otomatik içe aktarılır.
 *   6. (Ops.) "Kalıcı çalışan (hızlı)" işaretliyse model bellekte tutulur; eğit→tahmin
 *      ve tekrarlı tahminlerde yeniden yüklenmez (köprünün 'serve' modu).
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Karasikov M ve ark. (2025), arXiv:2504.05186 — Midnight. doi:10.48550/arXiv.2504.05186
 *   • Model: https://huggingface.co/kaiko-ai/midnight  (MIT)
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.regions.RegionRequest
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ───────────────────────────────────────────────────────────────
long PYTHON_TIMEOUT_SECONDS = 3600L          // Midnight (1.1B) CPU'da yavaş — cömert üst sınır
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def DEVICE_OPTIONS = ['auto', 'cpu', 'cuda']
def CONF_MEAS = 'Kaiko: Güven'

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır) ──
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/kaiko')
def PREF_PYTHON = 'python'
def PREF_BRIDGE = 'bridge'
def PREF_MODEL  = 'modelDir'
def PREF_WORK   = 'workDir'
def PREF_TILE   = 'tileSize'
def PREF_DS     = 'downsample'
def PREF_NEST   = 'nEstimators'
def PREF_MINPC  = 'minPerClass'
def PREF_MAXT   = 'maxTiles'
def PREF_DEVICE = 'device'
def PREF_PERSIST = 'persistent'   // "Kalıcı çalışan" tercihi (serve modu açık/kapalı)

def loadConfig = { ->
    [ python      : ({ -> def __p = prefs.get(PREF_PYTHON, ''); if (__p?.trim()) return __p; def __v = new File(System.getProperty('user.home'), '.atolye/runtimes/kaiko/.venv'); def __w = new File(__v, 'Scripts/python.exe'); def __n = new File(__v, 'bin/python'); __w.isFile() ? __w.getAbsolutePath() : (__n.isFile() ? __n.getAbsolutePath() : '') }).call(),
      bridge      : prefs.get(PREF_BRIDGE, ''),
      modelDir    : prefs.get(PREF_MODEL,  ''),
      workDir     : prefs.get(PREF_WORK,   ''),
      tileSize    : prefs.get(PREF_TILE,   '224'),
      downsample  : prefs.get(PREF_DS,     '1.0'),
      nEstimators : prefs.get(PREF_NEST,   '100'),
      minPerClass : prefs.get(PREF_MINPC,  '2'),
      maxTiles    : prefs.get(PREF_MAXT,   '20'),
      device      : prefs.get(PREF_DEVICE, 'auto') ]
}

// Zorunlu: python.exe + kaiko_bridge.py
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile())
        miss << 'Python yürütülebilir (python.exe)'
    if (!cfg.bridge?.trim() || !(new File(cfg.bridge)).isFile())
        miss << 'Kaiko köprüsü (kaiko_bridge.py)'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

def parseIntOr = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }
def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().trim()) } catch (Throwable t) { return d } }

// ── Çalışma dizinini çöz (proje dizini → slayt klasörü → geçici) ────────────
def resolveWorkDir = { cfg, imageData ->
    def wd = cfg.workDir?.trim()
    if (wd) return new File(wd)
    def project = QP.getProject()
    if (project != null && project.getPath() != null)
        return new File(project.getPath().getParent().toFile(), 'kaiko_work')
    try {
        def uris = imageData.getServer().getURIs()
        if (uris != null && !uris.isEmpty()) {
            def uri = uris.iterator().next()
            if ('file'.equals(uri.getScheme())) {
                def f = new File(uri)
                if (f.getParentFile() != null) return new File(f.getParentFile(), 'kaiko_work')
            }
        }
    } catch (Throwable ignore) {}
    return new File(System.getProperty('java.io.tmpdir'), 'kaiko_work')
}

def imageNameOf = { imageData ->
    def nm = imageData.getServer().getMetadata().getName() ?: 'slide'
    return nm.replaceAll(/\.[^.\/\\]+$/, '')
}

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

// ── Bir anotasyon ROI'sini ızgara karolara böl → PNG (arka plan elenir) ─────
def exportRoiTiles = { server, roi, double downsample, int tileSize, File outDir, int maxTiles ->
    outDir.mkdirs()
    if (roi == null || !roi.isArea()) return 0
    int phys = (int) Math.max(1, Math.round(tileSize * downsample))   // level-0 piksel boyutu
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
            try { javax.imageio.ImageIO.write(img, 'PNG', new File(outDir, 'tile_x' + xx + '_y' + yy + '.png')) }
            catch (Throwable t) { continue }
            count++
            if (maxTiles > 0 && count >= maxTiles) return count
        }
    }
    // Karo boyutundan küçük anotasyon: merkeze (sunucu sınırına kırpılmış) tek karo
    if (count == 0) {
        int cx = (int) Math.round(roi.getCentroidX() - phys / 2.0d)
        int cy = (int) Math.round(roi.getCentroidY() - phys / 2.0d)
        cx = Math.max(0, Math.min(cx, sw - phys))
        cy = Math.max(0, Math.min(cy, sh - phys))
        if (cx >= 0 && cy >= 0 && cx + phys <= sw && cy + phys <= sh) {
            try {
                def img = server.readRegion(RegionRequest.createInstance(server.getPath(), downsample, cx, cy, phys, phys))
                if (img != null) {
                    javax.imageio.ImageIO.write(img, 'PNG', new File(outDir, 'tile_x' + cx + '_y' + cy + '.png'))
                    count = 1
                }
            } catch (Throwable ignore) {}
        }
    }
    return count
}

def writeJson = { obj, File f ->
    if (f.getParentFile() != null) f.getParentFile().mkdirs()
    f.setText(qupath.lib.io.GsonTools.getInstance(true).toJson(obj), 'UTF-8')
}

// ── Karoları dışa aktar + JSON yaz (her anotasyon kendi UUID klasörüne) ──────
def exportTiles = { imageData, File workDir, cfg, Closure appendLine, boolean includeTrain ->
    def server = imageData.getServer()
    def imageName = imageNameOf(imageData)
    int tileSize = parseIntOr(cfg.tileSize, 224)
    double downsample = parseDoubleOr(cfg.downsample, 1.0d)
    int maxTiles = parseIntOr(cfg.maxTiles, 20)
    def tilesRoot = new File(workDir, 'tiles')
    def anns = imageData.getHierarchy().getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }
    def classified = anns.findAll { it.getPathClass() != null }
    def unclassified = anns.findAll { it.getPathClass() == null }
    def trainList = []; def predictList = []
    def classCounts = new TreeMap<String, Integer>()
    def roiMap = { roi -> [x: roi.getBoundsX(), y: roi.getBoundsY(), width: roi.getBoundsWidth(), height: roi.getBoundsHeight()] }

    if (includeTrain) {
        int i = 0
        classified.each { ann ->
            i++
            def annId = ann.getID().toString()
            def outDir = new File(tilesRoot, annId)
            int n = exportRoiTiles(server, ann.getROI(), downsample, tileSize, outDir, maxTiles)
            def cn = ann.getPathClass().getName()
            if (n > 0) {
                trainList << [id: annId, image_name: imageName, classification: cn,
                              tile_folder: outDir.getAbsolutePath(), roi: roiMap(ann.getROI())]
                classCounts[cn] = (classCounts.getOrDefault(cn, 0)) + 1
            }
            appendLine('  eğitim karoları: ' + cn + ' (' + n + ') [' + i + '/' + classified.size() + ']')
        }
    }
    int j = 0
    unclassified.each { ann ->
        j++
        def annId = ann.getID().toString()
        def outDir = new File(tilesRoot, annId)
        int n = exportRoiTiles(server, ann.getROI(), downsample, tileSize, outDir, maxTiles)
        if (n > 0)
            predictList << [id: annId, image_name: imageName,
                            tile_folder: outDir.getAbsolutePath(), roi: roiMap(ann.getROI())]
        appendLine('  tahmin karoları: anotasyon ' + j + '/' + unclassified.size() + ' (' + n + ')')
    }
    writeJson(trainList, new File(workDir, 'train_annotations.json'))
    writeJson(predictList, new File(workDir, 'predict_annotations.json'))
    return [ok: true, nTrain: trainList.size(), nPredict: predictList.size(), classCounts: classCounts]
}

// ── Python komutları ────────────────────────────────────────────────────────
def trainCmd = { cfg, File workDir ->
    def cmd = [cfg.python, cfg.bridge, 'train',
               '--annotations', new File(workDir, 'train_annotations.json').getAbsolutePath(),
               '--tiles-dir',   new File(workDir, 'tiles').getAbsolutePath(),
               '--output-dir',  workDir.getAbsolutePath(),
               '--n-estimators', (cfg.nEstimators ?: '100'),
               '--min-per-class', (cfg.minPerClass ?: '2'),
               '--max-tiles-per-annotation', (cfg.maxTiles ?: '20'),
               '--device', (cfg.device ?: 'auto')]
    if (cfg.modelDir?.trim()) { cmd.add('--model-dir'); cmd.add(cfg.modelDir.trim()) }
    return cmd
}
def predictCmd = { cfg, File workDir ->
    def cmd = [cfg.python, cfg.bridge, 'predict',
               '--annotations', new File(workDir, 'predict_annotations.json').getAbsolutePath(),
               '--tiles-dir',   new File(workDir, 'tiles').getAbsolutePath(),
               '--output-dir',  workDir.getAbsolutePath(),
               '--device', (cfg.device ?: 'auto')]
    if (cfg.modelDir?.trim()) { cmd.add('--model-dir'); cmd.add(cfg.modelDir.trim()) }
    return cmd
}
def selftestCmd = { cfg ->
    def cmd = [cfg.python, cfg.bridge, 'selftest']
    if (cfg.modelDir?.trim()) { cmd.add('--model-dir'); cmd.add(cfg.modelDir.trim()) }
    return cmd
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
// CONFIG_INCOMPLETE | CONFIG | CHECK_RUNNING | CHECK_DONE | READY
//   | TRAIN_RUNNING | PREDICT_RUNNING | BUSY | RESULT | ERROR
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
// CONFIG düzenleme alanları
def pyFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def bridgeFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def modelFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def workFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def tileFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def dsFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def nEstFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def minPCFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def maxTFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def deviceChoiceRef= new java.util.concurrent.atomic.AtomicReference(null)
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

// ── Tahminleri içe aktar (anotasyona sınıf + güven + olasılık ölçümü yaz) ────
def importPredictions = { File workDir, imageData ->
    def predsFile = new File(workDir, 'predictions.json')
    if (!predsFile.isFile())
        return [ok: false, error: 'predictions.json bulunamadı:\n' + predsFile.getAbsolutePath()]
    def predMap = [:]
    try {
        def reader = new java.io.InputStreamReader(
            new java.io.FileInputStream(predsFile), java.nio.charset.StandardCharsets.UTF_8)
        try {
            def arr = com.google.gson.JsonParser.parseReader(reader).getAsJsonArray()
            arr.each { el ->
                def o = el.getAsJsonObject()
                if (!o.has('id') || o.get('id').isJsonNull()) return
                def id = o.get('id').getAsString()
                if (!o.has('prediction') || o.get('prediction').isJsonNull()) { predMap[id] = null; return }
                def pred = o.get('prediction').getAsString()
                double conf = (o.has('confidence') && !o.get('confidence').isJsonNull()) ? o.get('confidence').getAsDouble() : Double.NaN
                def probs = [:]
                if (o.has('probabilities') && o.get('probabilities').isJsonObject())
                    o.getAsJsonObject('probabilities').entrySet().each { e -> probs[e.getKey()] = e.getValue().getAsDouble() }
                predMap[id] = [prediction: pred, confidence: conf, probabilities: probs]
            }
        } finally { reader.close() }
    } catch (Throwable t) {
        return [ok: false, error: 'predictions.json okunamadı:\n' + (t.getMessage() ?: t.getClass().getSimpleName())]
    }
    def hierarchy = imageData.getHierarchy()
    int updated = 0; int skipped = 0
    def predDist = new TreeMap<String, Integer>()
    double confSum = 0.0d; int confN = 0
    hierarchy.getAnnotationObjects().each { ann ->
        def id = ann.getID().toString()
        if (!predMap.containsKey(id)) return
        def r = predMap[id]
        if (r == null) { skipped++; return }
        ann.setPathClass(QP.getPathClass(r.prediction))
        if (!Double.isNaN(r.confidence))
            ann.setName(String.format(java.util.Locale.US, '%s (%.2f)', r.prediction, r.confidence))
        else
            ann.setName(r.prediction)
        def ml = ann.getMeasurementList()
        if (!Double.isNaN(r.confidence)) ml.put(CONF_MEAS, (double) r.confidence)
        r.probabilities.each { k, v -> ml.put('Kaiko: ' + k + ' olasılığı', (double) v) }
        predDist[r.prediction] = (predDist.getOrDefault(r.prediction, 0)) + 1
        if (!Double.isNaN(r.confidence)) { confSum += r.confidence; confN++ }
        updated++
    }
    QP.fireHierarchyUpdate()
    javafx.application.Platform.runLater { try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {} }
    double meanConf = (confN > 0) ? (confSum / confN) : Double.NaN
    return [ok: true, updated: updated, skipped: skipped, predDist: predDist, meanConf: meanConf]
}

// ── Özet metni ──────────────────────────────────────────────────────────────
def buildResultText = { File workDir, exp, imp, boolean trained ->
    def sb = new StringBuilder()
    sb << "KAIKO MIDNIGHT — ÖZET\n"
    sb << "═════════════════════════\n\n"
    if (trained) {
        def sumFile = new File(workDir, 'training_summary.json')
        if (sumFile.isFile()) {
            try {
                def reader = new java.io.InputStreamReader(
                    new java.io.FileInputStream(sumFile), java.nio.charset.StandardCharsets.UTF_8)
                def o = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject()
                reader.close()
                sb << "Eğitim:\n"
                if (o.has('class_counts') && o.get('class_counts').isJsonObject())
                    o.getAsJsonObject('class_counts').entrySet().each { e ->
                        sb << String.format(java.util.Locale.US, "  %-24s : %,d anotasyon%n", e.getKey(), e.getValue().getAsInt())
                    }
                if (o.has('n_tiles'))       sb << String.format(java.util.Locale.US, "  %-24s : %,d%n", '(toplam karo)', o.get('n_tiles').getAsInt())
                if (o.has('embedding_dim')) sb << String.format(java.util.Locale.US, "  %-24s : %,d%n", 'gömme boyutu', o.get('embedding_dim').getAsInt())
                if (o.has('train_accuracy'))sb << String.format(java.util.Locale.US, "  %-24s : %.3f%n", 'eğitim doğruluğu (set üstü)', o.get('train_accuracy').getAsDouble())
                sb << "\n"
            } catch (Throwable ignore) {}
        }
    }
    sb << "Tahmin:\n"
    if (imp != null && imp.ok) {
        int total = 0
        imp.predDist.each { cn, n ->
            sb << String.format(java.util.Locale.US, "  %-24s : %,d anotasyon%n", cn, n); total += n
        }
        sb << String.format(java.util.Locale.US, "  %-24s : %,d%n", '(sınıflandırılan)', total)
        if (imp.skipped > 0) sb << String.format(java.util.Locale.US, "  %-24s : %,d%n", '(karo yok, atlanan)', imp.skipped)
        if (!Double.isNaN(imp.meanConf)) sb << String.format(java.util.Locale.US, "  %-24s : %.3f%n", 'ortalama güven', imp.meanConf)
        sb << "\nHer anotasyona '" << CONF_MEAS << "' ve sınıf olasılığı ölçümleri yazıldı (Measurements paneli).\n"
    } else {
        sb << "  (tahmin uygulanamadı)\n"
    }
    sb << "\nMidnight çıktısı bir derin öğrenme tahminidir; görsel olarak doğrulayın (Ek W).\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

// ── Headless: yapılandırmayı raporla, etkileşim yok ─────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println "Önce bir slayt açın."; return }
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    println "Kaiko yapılandırması: python=${cfg.python ?: '(ayarsız)'} köprü=${cfg.bridge ?: '(ayarsız)'} model=${cfg.modelDir ?: '(HF hub)'} aygıt=${cfg.device}"
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(', ')}"
    def anns = imageData.getHierarchy().getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }
    int nClassified = anns.count { it.getPathClass() != null }
    int nUnclassified = anns.count { it.getPathClass() == null }
    println "Sınıflı anotasyon: ${nClassified}  ·  sınıfsız anotasyon: ${nUnclassified}"
    println "Kaiko sihirbazı için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Alanları prefs'e yaz ─────────────────────────────────────────────────────
def textOf = { ref -> def f = ref.get(); return (f != null ? f.getText() : '').trim() }
def persistFields = {
    prefs.put(PREF_PYTHON, textOf(pyFieldRef))
    prefs.put(PREF_BRIDGE, textOf(bridgeFieldRef))
    prefs.put(PREF_MODEL,  textOf(modelFieldRef))
    prefs.put(PREF_WORK,   textOf(workFieldRef))
    def ts = textOf(tileFieldRef);  prefs.put(PREF_TILE,  ts ?: '224')
    def ds = textOf(dsFieldRef);    prefs.put(PREF_DS,    ds ?: '1.0')
    def ne = textOf(nEstFieldRef);  prefs.put(PREF_NEST,  ne ?: '100')
    def mp = textOf(minPCFieldRef); prefs.put(PREF_MINPC, mp ?: '2')
    def mt = textOf(maxTFieldRef);  prefs.put(PREF_MAXT,  mt ?: '20')
    def dv = deviceChoiceRef.get()
    prefs.put(PREF_DEVICE, (dv != null && dv.getValue() != null) ? dv.getValue() : 'auto')
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

// ── Kalıcı çalışan (persistent worker) — modeli bellekte tutar ───────────────
// SAM/samapi'nin kalıcı-sunucu dersinin uyarlaması: köprüyü 'serve' modunda BİR
// KEZ başlat, modeli bir kez yükle, sonraki train/predict'leri aynı sürece
// stdin/stdout JSON ile gönder. Opsiyonel; varsayılan KAPALI (alt çubuktaki onay
// kutusu). Kapalıyken davranış eskisiyle birebir aynıdır (çağrı-başına süreç).
def READY_MARK  = '##KAIKO-READY##'
def RESP_PREFIX = '##KAIKO-RESP## '
def persistentRef   = new java.util.concurrent.atomic.AtomicBoolean(prefs.getBoolean(PREF_PERSIST, false))
def workerProcRef   = new java.util.concurrent.atomic.AtomicReference(null)
def workerWriterRef = new java.util.concurrent.atomic.AtomicReference(null)
def workerReadyRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def workerSigRef    = new java.util.concurrent.atomic.AtomicReference('')
def workerRespQueue = new java.util.concurrent.LinkedBlockingQueue()
def workerLastLines = java.util.Collections.synchronizedList(new java.util.ArrayList())

def workerSig = { cfg -> [cfg.python, cfg.bridge, cfg.modelDir, cfg.device].join('|') }

def stopWorker = { ->
    def w = workerWriterRef.getAndSet(null)
    def p = workerProcRef.getAndSet(null)
    workerReadyRef.set(false)
    if (w != null) { try { w.write('{"cmd":"quit"}\n'); w.flush() } catch (Throwable ignore) {}; try { w.close() } catch (Throwable ignore) {} }
    if (p != null) { try { p.destroyForcibly() } catch (Throwable ignore) {} }
    workerRespQueue.clear()
}

def ensureWorker = { cfg, Closure appendLine ->
    def existing = workerProcRef.get()
    if (existing != null && existing.isAlive() && workerReadyRef.get() && workerSig(cfg) == workerSigRef.get())
        return [ok: true]
    stopWorker()
    def cmd = [cfg.python, cfg.bridge, 'serve', '--device', (cfg.device ?: 'auto')]
    if (cfg.modelDir?.trim()) { cmd.add('--model-dir'); cmd.add(cfg.modelDir.trim()) }
    def pb = new ProcessBuilder(cmd)
    pb.redirectErrorStream(true)
    def proc
    try { proc = pb.start() }
    catch (Throwable e) { return [ok: false, exitCode: -1, error: 'Kalıcı çalışan başlatılamadı: ' + (e.getMessage() ?: e.getClass().getSimpleName())] }
    workerProcRef.set(proc); workerSigRef.set(workerSig(cfg)); workerReadyRef.set(false); workerRespQueue.clear()
    def writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(proc.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8))
    workerWriterRef.set(writer)
    def appendLog = { String ln -> javafx.application.Platform.runLater { def la = logAreaRef.get(); if (la != null) la.appendText(ln + '\n') } }
    def readerThread = new Thread({
        try {
            def reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
            String line
            while ((line = reader.readLine()) != null) {
                if (line.equals(READY_MARK)) { workerReadyRef.set(true) }
                else if (line.startsWith(RESP_PREFIX)) { workerRespQueue.offer(line.substring(RESP_PREFIX.length())) }
                else { appendLog(line); synchronized (workerLastLines) { workerLastLines.add(line); while (workerLastLines.size() > 60) workerLastLines.remove(0) } }
            }
            reader.close()
        } catch (Throwable ignore) {}
        workerReadyRef.set(false)
    }, 'AtolyeKaiko-Worker')
    readerThread.setDaemon(true); readerThread.start()
    appendLine('Kalıcı çalışan başlatıldı; model yükleniyor (ilk seferde uzun sürebilir)…')
    long deadline = System.currentTimeMillis() + PYTHON_TIMEOUT_SECONDS * 1000L
    while (!workerReadyRef.get()) {
        if (cancelledRef.get())      { stopWorker(); return [ok: false, exitCode: -3, error: 'İptal edildi'] }
        if (!proc.isAlive())         { return [ok: false, exitCode: -1, error: 'Kalıcı çalışan beklenmedik şekilde durdu (bağımlılıkları "Bağımlılık kontrolü" ile doğrulayın).'] }
        if (System.currentTimeMillis() > deadline) { stopWorker(); return [ok: false, exitCode: -2, error: 'Kalıcı çalışan zaman aşımı (' + PYTHON_TIMEOUT_SECONDS + ' sn).'] }
        try { Thread.sleep(150) } catch (InterruptedException ie) { return [ok: false, exitCode: -3, error: 'İptal edildi'] }
    }
    appendLine('Kalıcı çalışan hazır (model bellekte; sonraki çağrılarda yeniden yüklenmez).')
    return [ok: true]
}

def workerSend = { java.util.Map req ->
    def w = workerWriterRef.get(); def p = workerProcRef.get()
    if (w == null || p == null || !p.isAlive()) return [ok: false, exitCode: -1, error: 'Kalıcı çalışan etkin değil.']
    workerRespQueue.clear()
    def json = qupath.lib.io.GsonTools.getInstance(false).toJson(req)
    try { w.write(json); w.write('\n'); w.flush() }
    catch (Throwable e) { return [ok: false, exitCode: -1, error: 'Komut gönderilemedi: ' + (e.getMessage() ?: e.getClass().getSimpleName())] }
    String resp = null
    long deadline = System.currentTimeMillis() + PYTHON_TIMEOUT_SECONDS * 1000L
    while (resp == null) {
        if (cancelledRef.get()) return [ok: false, exitCode: -3, error: 'İptal edildi']
        if (!p.isAlive())       return [ok: false, exitCode: -1, error: 'Kalıcı çalışan durdu.']
        if (System.currentTimeMillis() > deadline) return [ok: false, exitCode: -2, error: 'Kalıcı çalışan yanıt vermedi (zaman aşımı).']
        try { resp = workerRespQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
        catch (InterruptedException ie) { return [ok: false, exitCode: -3, error: 'İptal edildi'] }
    }
    def tail; synchronized (workerLastLines) { tail = new ArrayList(workerLastLines).join('\n') }
    try {
        def o = com.google.gson.JsonParser.parseString(resp).getAsJsonObject()
        boolean ok = o.has('ok') && o.get('ok').getAsBoolean()
        int rc = (o.has('rc') && !o.get('rc').isJsonNull()) ? o.get('rc').getAsInt() : (ok ? 0 : 1)
        def err = (o.has('error') && !o.get('error').isJsonNull()) ? o.get('error').getAsString() : null
        return [ok: ok, exitCode: rc, error: err, lastLines: tail]
    } catch (Throwable t) {
        return [ok: false, exitCode: -1, error: 'Yanıt çözümlenemedi: ' + resp, lastLines: tail]
    }
}

// train/predict adımını kalıcı çalışan (açıksa) veya çağrı-başına süreçle yürüt
def doTrain = { cfg, File workDir, Closure appendLine ->
    if (persistentRef.get()) {
        def e = ensureWorker(cfg, appendLine); if (!e.ok) return e
        return workerSend([cmd: 'train',
            annotations: new File(workDir, 'train_annotations.json').getAbsolutePath(),
            output_dir : workDir.getAbsolutePath(),
            max_tiles_per_annotation: parseIntOr(cfg.maxTiles, 20),
            n_estimators: parseIntOr(cfg.nEstimators, 100),
            min_per_class: parseIntOr(cfg.minPerClass, 2)])
    }
    return runPython(trainCmd(cfg, workDir), appendLine)
}
def doPredict = { cfg, File workDir, Closure appendLine ->
    if (persistentRef.get()) {
        def e = ensureWorker(cfg, appendLine); if (!e.ok) return e
        return workerSend([cmd: 'predict',
            annotations: new File(workDir, 'predict_annotations.json').getAbsolutePath(),
            output_dir : workDir.getAbsolutePath()])
    }
    return runPython(predictCmd(cfg, workDir), appendLine)
}

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
    }, 'AtolyeKaiko-Check')
    worker.setDaemon(true); worker.start()
}

// ── Eğit+tahmin / yalnız tahmin akışı ───────────────────────────────────────
def startRun = { boolean wantTrain ->
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def workDir = resolveWorkDir(cfg, imageData)
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set(wantTrain ? 'Karo dışa aktarılıyor (1/3)…' : 'Karo dışa aktarılıyor (1/2)…')
    step.set('TRAIN_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        appendLine('Çalışma dizini: ' + workDir.getAbsolutePath())
        def exp
        try { exp = exportTiles(imageData, workDir, cfg, appendLine, wantTrain) }
        catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Karo dışa aktarma hatası:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }; return
        }
        if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }
        if (wantTrain && exp.nTrain < 2) {
            javafx.application.Platform.runLater { errorTextRef.set('Eğitim için en az 2 SINIFLI anotasyon (karolu) gerekir. Bulunan: ' + exp.nTrain); step.set('ERROR'); render() }; return
        }
        if (exp.nPredict == 0) {
            javafx.application.Platform.runLater { errorTextRef.set('Tahmin için en az 1 SINIFSIZ anotasyon (karolu) gerekir. Bulunan: 0'); step.set('ERROR'); render() }; return
        }
        if (wantTrain) {
            javafx.application.Platform.runLater { runPhaseRef.set('Gömme + RF eğitimi (2/3)…'); render() }
            def r1 = doTrain(cfg, workDir, appendLine)
            if (!r1.ok) {
                javafx.application.Platform.runLater { errorTextRef.set('Eğitim başarısız (çıkış: ' + r1.exitCode + ')\n' + (r1.error ?: '') + '\n' + (r1.lastLines ?: '')); step.set('ERROR'); render() }; return
            }
        } else if (!(new File(workDir, 'classifier.pkl')).isFile()) {
            javafx.application.Platform.runLater { errorTextRef.set('Eğitilmiş sınıflandırıcı (classifier.pkl) bulunamadı.\nÖnce "Eğit ve Tahmin Et" çalıştırın.\n' + workDir.getAbsolutePath()); step.set('ERROR'); render() }; return
        }
        if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }
        javafx.application.Platform.runLater { runPhaseRef.set(wantTrain ? 'Tahmin (3/3)…' : 'Tahmin (2/2)…'); step.set('PREDICT_RUNNING'); render() }
        def r2 = doPredict(cfg, workDir, appendLine)
        if (!r2.ok) {
            javafx.application.Platform.runLater { errorTextRef.set('Tahmin başarısız (çıkış: ' + r2.exitCode + ')\n' + (r2.error ?: '') + '\n' + (r2.lastLines ?: '')); step.set('ERROR'); render() }; return
        }
        javafx.application.Platform.runLater { busyLabelRef.set('Tahminler QuPath\'e aktarılıyor…'); step.set('BUSY'); render() }
        def imp = importPredictions(workDir, QP.getCurrentImageData())
        javafx.application.Platform.runLater {
            if (!imp.ok) { errorTextRef.set(imp.error); step.set('ERROR'); render() }
            else { resultTextRef.set(buildResultText(workDir, exp, imp, wantTrain)); step.set('RESULT'); render() }
        }
    }, 'AtolyeKaiko-Run')
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

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('Kaiko Midnight yapılandırması gerekli')
        def miss = configMissing(cfg)
        addGuidance('Kaiko köprüsü bir Python ortamı gerektirir. Aşağıdakiler eksik/geçersiz:\n  • ' +
            (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')) +
            '\n\nKurulum: Ekler → Kaiko Midnight § kurulum (veya Kaynaklar → İleri kurulumlar F).')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText('Kaiko Midnight yapılandırması')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def brField = new javafx.scene.control.TextField(cfg.bridge ?: '')
        def mdField = new javafx.scene.control.TextField(cfg.modelDir ?: '')
        def wdField = new javafx.scene.control.TextField(cfg.workDir ?: '')
        def tsField = new javafx.scene.control.TextField(cfg.tileSize ?: '224')
        def dsField = new javafx.scene.control.TextField(cfg.downsample ?: '1.0')
        def neField = new javafx.scene.control.TextField(cfg.nEstimators ?: '100')
        def mpField = new javafx.scene.control.TextField(cfg.minPerClass ?: '2')
        def mtField = new javafx.scene.control.TextField(cfg.maxTiles ?: '20')
        [pyField, brField, mdField, wdField].each { it.setPrefColumnCount(34) }
        [tsField, dsField, neField, mpField, mtField].each { it.setPrefColumnCount(8) }
        def deviceChoice = new javafx.scene.control.ChoiceBox()
        DEVICE_OPTIONS.each { deviceChoice.getItems().add(it) }
        deviceChoice.setValue(DEVICE_OPTIONS.contains(cfg.device) ? cfg.device : 'auto')
        pyFieldRef.set(pyField); bridgeFieldRef.set(brField); modelFieldRef.set(mdField); workFieldRef.set(wdField)
        tileFieldRef.set(tsField); dsFieldRef.set(dsField); nEstFieldRef.set(neField); minPCFieldRef.set(mpField)
        maxTFieldRef.set(mtField); deviceChoiceRef.set(deviceChoice)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Dosya seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir  = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Dizin seç', null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Kaiko köprüsü (kaiko_bridge.py):'), brField, navButton('…', { browseFile(brField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Model dizini (ops.):'), mdField, navButton('…', { browseDir(mdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Çalışma dizini (ops.):'), wdField, navButton('…', { browseDir(wdField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Karo boyutu (px):'), tsField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Downsample:'), dsField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('RF ağaç sayısı:'), neField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Sınıf başı min. anotasyon:'), mpField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Anotasyon başı maks. karo:'), mtField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Aygıt:'), deviceChoice)
        center.getChildren().add(grid)
        addGuidance('Downsample: 0.5 µm/px tarayıcıda 1.0; 0.25 µm/px tarayıcıda büyük anotasyon için 2.0 deneyin. ' +
            'Model dizini boşsa Midnight ilk çalıştırmada HuggingFace\'ten indirilir (~4 GB).')
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('Bağımlılık kontrolü', { startSelftest() }, 'kaiko_bridge.py selftest — bağımlılıkları doğrular'))
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
            addGuidance('Önce bir slayt açın ve sınıflı + sınıfsız anotasyonlar çizin, sonra "⟳ Yenile".')
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
        } else {
            def workDir = resolveWorkDir(cfg, imageData)
            def anns = imageData.getHierarchy().getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }
            def classified = anns.findAll { it.getPathClass() != null }
            def unclassified = anns.findAll { it.getPathClass() == null }
            def byClass = new TreeMap<String, Integer>()
            classified.each { def cn = it.getPathClass().getName(); byClass[cn] = (byClass.getOrDefault(cn, 0)) + 1 }
            boolean classifierExists = (new File(workDir, 'classifier.pkl')).isFile()
            title.setText('Kaiko Midnight — hazır')
            def sb = new StringBuilder()
            sb << "Slayt          : " << imageNameOf(imageData) << "\n"
            sb << "Çalışma dizini : " << workDir.getAbsolutePath() << "\n"
            sb << "Python         : " << (cfg.python ?: '(ayarsız)') << "\n"
            sb << "Model          : " << (cfg.modelDir?.trim() ? cfg.modelDir : 'HuggingFace (kaiko-ai/midnight)') << "\n"
            sb << "Aygıt          : " << cfg.device << "\n"
            sb << "Sınıflandırıcı : " << (classifierExists ? 'mevcut (classifier.pkl)' : 'yok (henüz eğitilmedi)') << "\n\n"
            sb << "Sınıflı anotasyonlar (eğitim):\n"
            if (byClass.isEmpty()) sb << "  (yok)\n"
            else byClass.each { cn, n -> sb << String.format(java.util.Locale.US, "  %-24s : %,d%n", cn, n) }
            sb << String.format(java.util.Locale.US, "Sınıfsız anotasyonlar (tahmin) : %,d%n", unclassified.size())
            addMonoArea(sb.toString())
            addWarnLabel('⚠ Midnight ~1.1B parametre. İlk çalıştırmada model (~4 GB) indirilir; CPU\'da karo başına birkaç saniye (GPU önerilir).')

            boolean canTrain = configComplete(cfg) && byClass.size() >= 2 && classified.size() >= 2 && unclassified.size() >= 1
            boolean canPredict = configComplete(cfg) && classifierExists && unclassified.size() >= 1
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
            def predBtn = navButton('Yalnızca Tahmin Et', { startRun(false) }, 'Mevcut sınıflandırıcıyı sınıfsız anotasyonlara uygular')
            predBtn.setDisable(!canPredict)
            actions.add(predBtn)
            def trainBtn = navButton('Eğit ve Tahmin Et ▶', { startRun(true) }, '≥2 sınıf için sınıflı anotasyon + ≥1 sınıfsız anotasyon gerekir')
            trainBtn.setDisable(!canTrain)
            actions.add(trainBtn)
        }
    } else if (cur == 'TRAIN_RUNNING' || cur == 'PREDICT_RUNNING') {
        title.setText(runPhaseRef.get())
        addGuidance('Python köprüsü koşuyor. Çıktı aşağıda akıyor. Zaman aşımı: ' + PYTHON_TIMEOUT_SECONDS + ' sn.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {}; try { if (persistentRef.get()) stopWorker() } catch (Throwable ignore) {} }))
    } else if (cur == 'BUSY') {
        title.setText(busyLabelRef.get()); addGuidance('Lütfen bekleyin…'); center.getChildren().add(busyBar())
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
    def persistChk = new javafx.scene.control.CheckBox('Kalıcı çalışan (hızlı)')
    persistChk.setSelected(persistentRef.get())
    persistChk.setTooltip(new javafx.scene.control.Tooltip(
        'Modeli bellekte tutar: eğit→tahmin ve tekrarlı tahminlerde modeli yeniden yüklemez.\n' +
        'İlk açılışta model bir kez yüklenir (ilk sefer uzun sürebilir). Kapalıyken her çağrıda yeni süreç başlar.'))
    persistChk.selectedProperty().addListener({ obs, o, n ->
        persistentRef.set(n); prefs.putBoolean(PREF_PERSIST, n)
        try { prefs.flush() } catch (Throwable ignore) {}
        if (!n) stopWorker()
    } as javafx.beans.value.ChangeListener)
    def spacer = new javafx.scene.layout.Region()
    javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8)
    bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk)
    bar.getChildren().add(persistChk)
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
    stage.setScene(new javafx.scene.Scene(root, 880, 660))
}

// ── Açılış durumu ───────────────────────────────────────────────────────────
step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Kaiko Midnight sınıflandırıcı sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        stage.setOnHidden({ e -> try { stopWorker() } catch (Throwable ignore) {} } as javafx.event.EventHandler)
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ Kaiko Midnight sınıflandırıcı sihirbazı açıldı."
