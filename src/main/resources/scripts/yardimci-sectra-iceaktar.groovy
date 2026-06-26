/**
 * Yardımcı - Sectra PACS Anotasyon Sihirbazı (tek pencere: Python köprüsü)
 * -----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Sectra IDS7 PACS'ta çizilen anotasyonları taşıyan DICOM Presentation-State
 *   (GSPS) dosyasını (.dcm) QuPath'e TEK pencereden aktarır:
 *     1. DÖNÜŞTÜR — bir Python köprüsü (sectra_dicom_to_qupath.py) GSPS'i okur,
 *        normalize [0–1] koordinatları WSI piksel uzayına ölçekler ve GeoJSON yazar.
 *        Görüntü boyutu QuPath'ten geçirilir (openslide GEREKMEZ).
 *     2. İÇE AKTAR — sihirbaz GeoJSON'u okuyup anotasyon nesneleri (poligon / çizgi
 *        / nokta) oluşturur; seçilen renk şemasıyla (geometri tipi veya etiket metni)
 *        sınıflandırır ve Sectra metin etiketini anotasyon adı olarak korur.
 *   Köprü QuPath DIŞINDA bir Python venv'inde koşar; tek bağımlılığı pydicom'dur.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Bu bir VERİ AKTARIM betiğidir: Sectra anotasyonlarını QuPath görselleştirme/
 *     ölçüm katmanına TAŞIR. Patoloji yorumu, tanı, grade veya klinik karar üretmez.
 *   • Sınıf adları ya geometri tipinden (Sectra: CIRCLE …) ya da kullanıcının kendi
 *     Sectra etiketinden türetilir; betik hiçbir tanı sınıfı sabitlemez.
 *   • Koordinat dönüşümü görüntü boyutuna dayanır; yanlış boyut anotasyonları kaydırır.
 *     Sihirbaz boyutu açık WSI'dan otomatik alır.
 *
 * KULLANIM:
 *   1. Sectra köprü ortamını kurun (venv + pydicom). Bkz. Ekler → Klinik PACS → QuPath
 *      Entegrasyonu, ya da Kaynaklar → İleri kurulumlar § H.
 *   2. Sectra'da bir slaytta anotasyon çizip DICOM grafik (.dcm) dışa aktarın.
 *   3. Aynı WSI'ı QuPath'te açın.
 *   4. [Extensions → Atölye → Yardımcılar → Sectra PACS anotasyon sihirbazı]
 *   5. İlk açılışta yapılandırın: python.exe + sectra_dicom_to_qupath.py yolu.
 *   6. Renk şemasını seçin → ".dcm seç ve Aktar" → anotasyonlar otomatik içe aktarılır.
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Balcı S. — Using Sectra PACS Annotations in QuPath, github.com/sbalci/sectra-qupath
 *     (MIT). doi:10.5281/zenodo.15542395
 *   • DICOM GSPS: Grayscale Softcopy Presentation State (GraphicAnnotationSequence).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.
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
def SECTRA_FLAG = 'Sectra: içe aktarıldı'     // idempotent yeniden-içe-aktarım ölçüm bayrağı
long PYTHON_TIMEOUT_SECONDS = 600L
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def SCHEME_GEOMETRY = 'geometry'
def SCHEME_LABEL = 'label'
def SCHEME_LABELS = ['Geometri tipine göre': SCHEME_GEOMETRY, 'Etiket metnine göre': SCHEME_LABEL]
def SCHEME_DISPLAY = [(SCHEME_GEOMETRY): 'Geometri tipine göre', (SCHEME_LABEL): 'Etiket metnine göre']

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır) ──
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/sectra')
def PREF_PYTHON = 'python'
def PREF_BRIDGE = 'bridge'
def PREF_SCHEME = 'scheme'
def PREF_WORK   = 'workDir'

def loadConfig = { ->
    [ python  : prefs.get(PREF_PYTHON, ''),
      bridge  : prefs.get(PREF_BRIDGE, ''),
      workDir : prefs.get(PREF_WORK,   ''),
      scheme  : prefs.get(PREF_SCHEME, SCHEME_GEOMETRY) ]
}

// Zorunlu: python.exe + sectra_dicom_to_qupath.py
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile())
        miss << 'Python yürütülebilir (python.exe)'
    if (!cfg.bridge?.trim() || !(new File(cfg.bridge)).isFile())
        miss << 'Sectra köprüsü (sectra_dicom_to_qupath.py)'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

def imageNameOf = { imageData ->
    def nm = imageData.getServer().getMetadata().getName() ?: 'slide'
    return nm.replaceAll(/\.[^.\/\\]+$/, '')
}

// ── Çalışma dizinini çöz (proje dizini → slayt klasörü → geçici) ────────────
def resolveWorkDir = { cfg, imageData ->
    def wd = cfg.workDir?.trim()
    if (wd) return new File(wd)
    def project = QP.getProject()
    if (project != null && project.getPath() != null)
        return new File(project.getPath().getParent().toFile(), 'sectra_geojson')
    try {
        def uris = imageData.getServer().getURIs()
        if (uris != null && !uris.isEmpty()) {
            def uri = uris.iterator().next()
            if ('file'.equals(uri.getScheme())) {
                def f = new File(uri)
                if (f.getParentFile() != null) return new File(f.getParentFile(), 'sectra_geojson')
            }
        }
    } catch (Throwable ignore) {}
    return new File(System.getProperty('java.io.tmpdir'), 'sectra_geojson')
}

// ── Python süreci (ProcessBuilder) → satır akışı ────────────────────────────
def processRef   = new java.util.concurrent.atomic.AtomicReference(null)
def cancelledRef = new java.util.concurrent.atomic.AtomicBoolean(false)
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

def convertCmd = { cfg, File dicom, File outGeojson, int width, int height ->
    [cfg.python, cfg.bridge, 'convert',
     '--dicom', dicom.getAbsolutePath(),
     '--out', outGeojson.getAbsolutePath(),
     '--width', String.valueOf(width),
     '--height', String.valueOf(height),
     '--color-scheme', (cfg.scheme ?: SCHEME_GEOMETRY)]
}
def selftestCmd = { cfg -> [cfg.python, cfg.bridge, 'selftest'] }

// ── PathClass'ı (varsa) gömülü renkle hazırla ───────────────────────────────
def ensureClass = { String name, Integer rgb ->
    def pc = QP.getPathClass(name)
    if (rgb != null && pc != null) { try { pc.setColor(rgb) } catch (Throwable ignore) {} }
    return pc
}

// ── GeoJSON içe aktarma (Polygon / LineString / Point) ──────────────────────
def importGeoJSON = { File geojsonFile ->
    if (geojsonFile == null || !geojsonFile.isFile())
        return [ok: false, error: 'GeoJSON bulunamadı:\n' + (geojsonFile?.getAbsolutePath() ?: '(yol yok)')]
    def plane = ImagePlane.getDefaultPlane()

    def ptsOf = { arr ->
        def pts = new ArrayList<Point2>(arr.size())
        for (int i = 0; i < arr.size(); i++) {
            def c = arr.get(i).getAsJsonArray()
            if (c.size() < 2) return null
            double x = c.get(0).getAsDouble(); double y = c.get(1).getAsDouble()
            if (!Double.isFinite(x) || !Double.isFinite(y)) return null
            pts.add(new Point2(x, y))
        }
        return pts
    }
    def strProp = { props, key, fallback ->
        if (props == null || !props.has(key) || props.get(key).isJsonNull()) return fallback
        def v = props.get(key)
        return v.isJsonPrimitive() ? v.getAsString() : fallback
    }
    def colorOf = { props ->
        if (props == null || !props.has('color') || !props.get('color').isJsonArray()) return null
        def a = props.getAsJsonArray('color')
        if (a.size() < 3) return null
        try { return (Integer) ColorTools.packRGB(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()) }
        catch (Throwable t) { return null }
    }
    def lockedOf = { props ->
        if (props == null || !props.has('isLocked') || !props.get('isLocked').isJsonPrimitive()) return true
        try { return props.get('isLocked').getAsBoolean() } catch (Throwable t) { return true }
    }

    def newAnns = []
    def byClass = new TreeMap<String, Integer>()
    def byType  = new TreeMap<String, Integer>()
    int skipped = 0
    try {
        def reader = new java.io.InputStreamReader(
            new java.io.FileInputStream(geojsonFile), java.nio.charset.StandardCharsets.UTF_8)
        try {
            def root = JsonParser.parseReader(reader).getAsJsonObject()
            if (!root.has('features'))
                return [ok: false, error: "Geçersiz GeoJSON: 'features' dizisi yok."]
            root.getAsJsonArray('features').each { fe ->
                def feature = fe.getAsJsonObject()
                if (!feature.has('geometry') || feature.get('geometry').isJsonNull()) { skipped++; return }
                def geom = feature.getAsJsonObject('geometry')
                if (!geom.has('type') || !geom.has('coordinates')) { skipped++; return }
                def props = (feature.has('properties') && feature.get('properties').isJsonObject())
                    ? feature.getAsJsonObject('properties') : null
                String cn = strProp(props, 'classification', 'Sectra anotasyon')
                String nm = strProp(props, 'name', cn)
                String st = strProp(props, 'type', '?')
                Integer rgb = colorOf(props)
                boolean locked = lockedOf(props)
                def pc = ensureClass(cn, rgb)

                def rois = []
                String gt = geom.get('type').getAsString()
                try {
                    if (gt == 'Polygon') {
                        def coords = geom.getAsJsonArray('coordinates')
                        if (coords != null && coords.size() > 0) {
                            def pts = ptsOf(coords.get(0).getAsJsonArray())
                            if (pts != null && pts.size() >= 3) rois << ROIs.createPolygonROI(pts, plane)
                        }
                    } else if (gt == 'MultiPolygon') {
                        geom.getAsJsonArray('coordinates')?.each { poly ->
                            def rr = poly.getAsJsonArray()
                            if (rr != null && rr.size() > 0) {
                                def pts = ptsOf(rr.get(0).getAsJsonArray())
                                if (pts != null && pts.size() >= 3) rois << ROIs.createPolygonROI(pts, plane)
                            }
                        }
                    } else if (gt == 'LineString') {
                        def pts = ptsOf(geom.getAsJsonArray('coordinates'))
                        if (pts != null && pts.size() >= 2) rois << ROIs.createPolylineROI(pts, plane)
                    } else if (gt == 'Point') {
                        def c = geom.getAsJsonArray('coordinates')
                        if (c != null && c.size() >= 2) {
                            double x = c.get(0).getAsDouble(); double y = c.get(1).getAsDouble()
                            if (Double.isFinite(x) && Double.isFinite(y))
                                rois << ROIs.createPointsROI(x, y, plane)
                        }
                    } else { skipped++; return }
                } catch (Throwable t) { skipped++; return }

                if (rois.isEmpty()) { skipped++; return }
                rois.each { roi ->
                    if (roi == null || roi.isEmpty()) { skipped++; return }
                    def ann = PathObjects.createAnnotationObject(roi, pc)
                    if (nm) ann.setName(nm)
                    try { ann.getMeasurementList().put(SECTRA_FLAG, 1.0d) } catch (Throwable ignore) {}
                    if (locked) ann.setLocked(true)
                    newAnns << ann
                    byClass[cn] = (byClass.getOrDefault(cn, 0)) + 1
                    byType[st]  = (byType.getOrDefault(st, 0)) + 1
                }
            }
        } finally { reader.close() }
    } catch (Throwable t) {
        return [ok: false, error: 'GeoJSON okunamadı:\n' + (t.getMessage() ?: t.getClass().getSimpleName())]
    }
    if (newAnns.isEmpty())
        return [ok: false, error: 'GeoJSON içinde geçerli geometri bulunamadı (atlanan: ' + skipped + ').\n' +
                'Dosya bir Sectra GSPS (GraphicAnnotationSequence) dışa aktarımı mı?']
    // Önceki Sectra içe aktarımını temizle → idempotent
    QP.removeObjects(QP.getAnnotationObjects().findAll {
        try { it.getMeasurementList().getNames().contains(SECTRA_FLAG) } catch (Throwable t) { false }
    }, false)
    QP.addObjects(newAnns)
    QP.fireHierarchyUpdate()
    return [ok: true, annotations: newAnns, byClass: byClass, byType: byType, skipped: skipped, file: geojsonFile]
}

// ── Özet metni ──────────────────────────────────────────────────────────────
def buildResultText = { slideName, schemeDisp, imp ->
    def sb = new StringBuilder()
    sb << "SECTRA PACS ANOTASYON — İÇE AKTARMA\n"
    sb << "═════════════════════════════════════\n\n"
    sb << "Slayt       : " << slideName << "\n"
    sb << "GeoJSON     : " << (imp.file?.getAbsolutePath() ?: '-') << "\n"
    sb << "Renk şeması : " << schemeDisp << "\n\n"
    int total = 0
    sb << "Geometri tipine göre:\n"
    imp.byType.each { t, n -> sb << String.format(java.util.Locale.US, "  %-22s : %,d%n", t, n); total += n }
    sb << String.format(java.util.Locale.US, "  %-22s : %,d%n", '(toplam)', total)
    sb << String.format(java.util.Locale.US, "  %-22s : %,d%n%n", 'atlanan özellik', (imp.skipped ?: 0))
    sb << "Sınıf (PathClass) dökümü:\n"
    imp.byClass.each { cn, n -> sb << String.format(java.util.Locale.US, "  %-22s : %,d%n", cn, n) }
    sb << "\nTüm anotasyonlar kilitli eklendi; Sectra metin etiketi anotasyon adıdır.\n"
    sb << "Ek D ile düzeltebilir, Modül 9 ile dışa aktarabilirsiniz.\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar."
    return sb.toString()
}

// ── Headless: yapılandırmayı raporla, etkileşim yok ─────────────────────────
if (isHeadless) {
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    println "Sectra yapılandırması: python=${cfg.python ?: '(ayarsız)'} köprü=${cfg.bridge ?: '(ayarsız)'} şema=${cfg.scheme}"
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(', ')}"
    def imageData = QP.getCurrentImageData()
    if (imageData == null) println "Önce bir slayt açın (içe aktarım için)."
    else println "Açık slayt: ${imageNameOf(imageData)} (${imageData.getServer().getWidth()}x${imageData.getServer().getHeight()})"
    println "Sectra sihirbazı için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar."
    return
}

// ── Durum makinesi ──────────────────────────────────────────────────────────
// CONFIG_INCOMPLETE | CONFIG | CHECK_RUNNING | CHECK_DONE | READY
//   | CONVERT_RUNNING | BUSY | RESULT | ERROR
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def busyLabelRef  = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def pyFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def bridgeFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def workFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def schemeChoiceRef= new java.util.concurrent.atomic.AtomicReference(null)
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

// ── Yapılandırmayı kaydet ────────────────────────────────────────────────────
def persistConfig = {
    def py = pyFieldRef.get(); def br = bridgeFieldRef.get(); def wd = workFieldRef.get()
    prefs.put(PREF_PYTHON, (py != null ? py.getText() : '').trim())
    prefs.put(PREF_BRIDGE, (br != null ? br.getText() : '').trim())
    prefs.put(PREF_WORK,   (wd != null ? wd.getText() : '').trim())
    try { prefs.flush() } catch (Throwable ignore) {}
}
def persistScheme = { String value -> prefs.put(PREF_SCHEME, value); try { prefs.flush() } catch (Throwable ignore) {} }

// ── Bağımlılık kontrolü (selftest) ──────────────────────────────────────────
def startSelftest = {
    persistConfig()
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    if (!miss.isEmpty()) { errorTextRef.set('Önce yapılandırmayı tamamlayın:\n  • ' + miss.join('\n  • ')); step.set('ERROR'); render(); return }
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    step.set('CHECK_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        runPython(selftestCmd(cfg), appendLine)
        javafx.application.Platform.runLater { step.set('CHECK_DONE'); render() }
    }, 'AtolyeSectra-Check')
    worker.setDaemon(true); worker.start()
}

// ── Dönüştür + içe aktar akışı ──────────────────────────────────────────────
def startConvert = { File dicomFile ->
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def server = imageData.getServer()
    int width = server.getWidth(); int height = server.getHeight()
    def workDir = resolveWorkDir(cfg, imageData)
    workDir.mkdirs()
    def slideName = imageNameOf(imageData)
    def outGeojson = new File(workDir, slideName + '.sectra.geojson')
    def schemeDisp = SCHEME_DISPLAY[cfg.scheme] ?: cfg.scheme
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    step.set('CONVERT_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        appendLine('DICOM     : ' + dicomFile.getAbsolutePath())
        appendLine('Çıktı     : ' + outGeojson.getAbsolutePath())
        appendLine('Boyut     : ' + width + 'x' + height + '  (QuPath\'ten)')
        appendLine('Renk şeması: ' + schemeDisp)
        appendLine('')
        def r = runPython(convertCmd(cfg, dicomFile, outGeojson, width, height), appendLine)
        if (!r.ok) {
            javafx.application.Platform.runLater {
                errorTextRef.set('Dönüştürme başarısız (çıkış: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: ''))
                step.set('ERROR'); render()
            }
            return
        }
        javafx.application.Platform.runLater { busyLabelRef.set('GeoJSON içe aktarılıyor…'); step.set('BUSY'); render() }
        def imp = importGeoJSON(outGeojson)
        javafx.application.Platform.runLater {
            if (!imp.ok) { errorTextRef.set(imp.error); step.set('ERROR'); render() }
            else {
                try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
                resultTextRef.set(buildResultText(slideName, schemeDisp, imp)); step.set('RESULT'); render()
            }
        }
    }, 'AtolyeSectra-Convert')
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
    def addLiveLog = { ->
        def la = logAreaRef.get()
        if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) }
    }

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('Sectra köprüsü yapılandırması gerekli')
        def miss = configMissing(cfg)
        addGuidance('Sectra köprüsü bir Python ortamı gerektirir. Aşağıdakiler eksik/geçersiz:\n  • ' +
            (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')) +
            '\n\nKurulum: Ekler → Klinik PACS → QuPath Entegrasyonu (veya Kaynaklar → İleri kurulumlar H).')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText('Sectra köprüsü yapılandırması')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def brField = new javafx.scene.control.TextField(cfg.bridge ?: '')
        def wdField = new javafx.scene.control.TextField(cfg.workDir ?: '')
        [pyField, brField, wdField].each { it.setPrefColumnCount(34) }
        pyFieldRef.set(pyField); bridgeFieldRef.set(brField); workFieldRef.set(wdField)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Dosya seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir  = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Dizin seç', null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (python.exe):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Sectra köprüsü (sectra_dicom_to_qupath.py):'), brField, navButton('…', { browseFile(brField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Çıktı dizini (ops.):'), wdField, navButton('…', { browseDir(wdField) }))
        center.getChildren().add(grid)
        addGuidance('Köprünün tek bağımlılığı pydicom\'dur. "Bağımlılık kontrolü" ile ortamı doğrulayın. ' +
            'Çıktı dizini boşsa proje/slayt klasörü altında sectra_geojson kullanılır.')
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('Bağımlılık kontrolü', { startSelftest() }, 'sectra_dicom_to_qupath.py selftest — pydicom doğrular'))
        actions.add(navButton('Kaydet ▶', { persistConfig(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
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
            addGuidance('Önce Sectra anotasyonlarının ait olduğu WSI\'ı açın, sonra "⟳ Yenile".')
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
        } else {
            def server = imageData.getServer()
            def workDir = resolveWorkDir(cfg, imageData)
            title.setText('Sectra PACS anotasyon — hazır')
            def sb = new StringBuilder()
            sb << "Slayt        : " << imageNameOf(imageData) << "\n"
            sb << "Boyut (px)   : " << server.getWidth() << " x " << server.getHeight() << "\n"
            sb << "Python       : " << (cfg.python ?: '(ayarsız)') << "\n"
            sb << "Köprü        : " << (cfg.bridge ?: '(ayarsız)') << "\n"
            sb << "Çıktı dizini : " << workDir.getAbsolutePath() << "\n"
            addMonoArea(sb.toString())
            def schemeChoice = new javafx.scene.control.ChoiceBox()
            SCHEME_LABELS.keySet().each { schemeChoice.getItems().add(it) }
            schemeChoice.setValue(SCHEME_DISPLAY[cfg.scheme] ?: 'Geometri tipine göre')
            schemeChoice.valueProperty().addListener({ o, ov, nv ->
                if (nv != null) persistScheme(SCHEME_LABELS[nv] ?: SCHEME_GEOMETRY)
            } as javafx.beans.value.ChangeListener)
            schemeChoiceRef.set(schemeChoice)
            def schemeRow = new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('Renk şeması:'), schemeChoice)
            schemeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            center.getChildren().add(schemeRow)
            addGuidance('Geometri şeması klinik açıdan nötrdür (CIRCLE=altın, POLYLINE=mavi …). ' +
                'Etiket şeması Sectra metnine göre renklendirir (tümör=kırmızı …); her iki şemada da etiket anotasyon adı olur.')
            boolean canRun = configComplete(cfg)
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
            def runBtn = navButton('.dcm seç ve Aktar ▶', {
                def dcm = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Sectra DICOM (.dcm) dosyasını seçin',
                    qupath.fx.dialogs.FileChoosers.createExtensionFilter('DICOM (*.dcm)', '*.dcm'),
                    qupath.fx.dialogs.FileChoosers.FILTER_ALL_FILES)
                if (dcm != null) startConvert(dcm)
            }, 'Sectra GSPS .dcm dosyasını GeoJSON\'a çevirir ve içe aktarır')
            runBtn.setDisable(!canRun)
            actions.add(runBtn)
        }
    } else if (cur == 'CONVERT_RUNNING') {
        title.setText('Dönüştürülüyor (DICOM → GeoJSON)…')
        addGuidance('Python köprüsü koşuyor. Çıktı aşağıda akıyor. Zaman aşımı: ' + PYTHON_TIMEOUT_SECONDS + ' sn.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'BUSY') {
        title.setText(busyLabelRef.get()); addGuidance('Lütfen bekleyin…'); center.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅')
        addMonoArea(resultTextRef.get())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('↻ Yeni .dcm', { step.set('READY'); render() }))
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

    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(center)
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 840, 620))
}

// ── Açılış durumu ───────────────────────────────────────────────────────────
step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Sectra PACS anotasyon sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ Sectra PACS anotasyon sihirbazı açıldı."
