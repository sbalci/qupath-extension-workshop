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
 *     2. İÇE AKTAR — görüntüde anotasyon varsa önce ne yapılacağını SORAR (varsayılan: hiçbir şey
 *        silinmez; isterseniz yalnız önceki Sectra içe aktarımı değiştirilir), sonra GeoJSON'u okuyup
 *        anotasyon nesneleri (poligon / çizgi
 *        / nokta) oluşturur; seçilen renk şemasıyla (geometri tipi veya etiket metni)
 *        sınıflandırır ve Sectra metin etiketini anotasyon adı olarak korur.
 *   Köprü QuPath DIŞINDA bir Python venv'inde çalışır; tek bağımlılığı pydicom'dur.
 *   TÜM PROJE kipi: bir klasördeki Sectra dışa aktarımlarını (<slayt dosyası>.graphics.<tarih>.dcm) proje
 *   görüntülerine AD ÖNEKİYLE birebir eşler, önce bir eşleştirme önizlemesi gösterir, sonra her görüntü için
 *   dönüştürüp içe aktarır ve kaydeder; çalıştırmadan önce mevcut anotasyonlar için ne yapılacağını SORAR
 *   (varsayılan: dokunma — yalnız ekle). Rapor: <proje>/exports/sectra_iceaktarma_<tarih>.tsv.
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
 *   7. (Tüm proje) Bir proje açıkken "Tüm proje (klasörden) ▶" → .dcm klasörünü seçin → önizlemeyi
 *      denetleyin → "İçe aktar ▶" → önceki anotasyonlar için seçim yapın.
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

// Önceki anotasyonlar sorusu (kullanıcı kararı D5) — varsayılan: hiçbir şey silme
def SECTRA_KEEP    = 'Mevcut anotasyonlara dokunma — yalnız ekle (varsayılan)'
def SECTRA_REPLACE = 'Önceki Sectra içe aktarımını değiştir (yalnız Sectra bayraklı anotasyonlar silinir)'

// Atölye ayarları köprüsü — yalnız 'exports' klasör adı için (Tüm proje raporu)
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeS = { String k, String d -> (String) __wpCall('str', [String.class, String.class] as Class[], [k, d] as Object[], d) }
def exportFolder = atolyeS('atolye.exportFolder', 'exports')

// Atölye veri kökü (env yöneticisiyle PAYLAŞILAN; öntanımlı ~/.atolye — C:).
def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}

def loadConfig = { ->
    [ python  : ({ -> def __p = prefs.get(PREF_PYTHON, ''); if (__p?.trim()) return __p; def __r = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('py.sectra', ''); if (__r?.trim() && new File(__r.trim()).isFile()) return __r.trim(); def __v = new File(new File(atolyeDataRoot(), 'runtimes'), 'sectra/.venv'); def __w = new File(__v, 'Scripts/python.exe'); def __n = new File(__v, 'bin/python'); __w.isFile() ? __w.getAbsolutePath() : (__n.isFile() ? __n.getAbsolutePath() : '') }).call(),
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
// hier: hedef hiyerarşi (null → açık görüntü). replacePrevious: önceki SECTRA_FLAG'li anotasyonlar silinsin mi —
// her iki akışta da kullanıcı seçer (soru penceresi); varsayılan false = hiçbir şey silinmez.
def importGeoJSON = { File geojsonFile, hier = null, boolean replacePrevious = false ->
    def targetH = (hier != null) ? hier : QP.getCurrentHierarchy()
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
    // Önceki Sectra içe aktarımını temizle (istenirse) → idempotent
    def previous = targetH.getAnnotationObjects().findAll {
        try { it.getMeasurementList().getNames().contains(SECTRA_FLAG) } catch (Throwable t) { false }
    }
    int removedPrev = 0
    if (replacePrevious && !previous.isEmpty()) { targetH.removeObjects(previous, true); removedPrev = previous.size() }
    targetH.addObjects(newAnns)
    targetH.fireHierarchyChangedEvent(targetH)
    return [ok: true, annotations: newAnns, byClass: byClass, byType: byType, skipped: skipped, file: geojsonFile, removed: removedPrev]
}

// ── Özet metni ──────────────────────────────────────────────────────────────
def buildResultText = { slideName, schemeDisp, imp, boolean replacedPrev = false ->
    def sb = new StringBuilder()
    sb << "SECTRA PACS ANOTASYON — İÇE AKTARMA\n"
    sb << "═════════════════════════════════════\n\n"
    sb << "Slayt       : " << slideName << "\n"
    sb << "GeoJSON     : " << (imp.file?.getAbsolutePath() ?: '-') << "\n"
    sb << "Renk şeması : " << schemeDisp << "\n"
    sb << "Önceki      : " << (replacedPrev ? (String.valueOf(imp.removed) + ' Sectra bayraklı anotasyon değiştirildi') : 'mevcut anotasyonlara dokunulmadı (yalnız eklendi)') << "\n\n"
    int total = 0
    sb << "Geometri tipine göre:\n"
    imp.byType.each { t, n -> sb << String.format(java.util.Locale.US, "  %-22s : %,d%n", t, n); total += n }
    sb << String.format(java.util.Locale.US, "  %-22s : %,d%n", '(toplam)', total)
    sb << String.format(java.util.Locale.US, "  %-22s : %,d%n%n", 'atlanan özellik', (imp.skipped ?: 0))
    sb << "Sınıf (PathClass) dökümü:\n"
    imp.byClass.each { cn, n -> sb << String.format(java.util.Locale.US, "  %-22s : %,d%n", cn, n) }
    sb << "\nTüm anotasyonlar kilitli eklendi; Sectra metin etiketi anotasyon adıdır.\n"
    sb << "Manuel Düzeltme eki ile düzeltebilir, Veri dışa aktarma modülü ile dışa aktarabilirsiniz.\n"
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
//   | CONVERT_RUNNING | BATCH_PREVIEW | BATCH_RUNNING | BUSY | RESULT | ERROR
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
def resultTitleRef = new java.util.concurrent.atomic.AtomicReference('Tamamlandı ✅')
def resultBtnRef   = new java.util.concurrent.atomic.AtomicReference('↻ Yeni .dcm')
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
    // Önceki anotasyonlar için SOR — varsayılan: hiçbir şey silme (kullanıcı kararı D5). Görüntü boşsa sorulmaz.
    boolean replacePrevious = false
    def existingAnns = imageData.getHierarchy().getAnnotationObjects()
    if (!existingAnns.isEmpty()) {
        int nSectra = existingAnns.count { try { it.getMeasurementList().getNames().contains(SECTRA_FLAG) } catch (Throwable t) { false } } as int
        def pick = Dialogs.showChoiceDialog('Önceki anotasyonlar',
            String.format(java.util.Locale.US, 'Bu görüntüde %,d anotasyon var (%,d tanesi daha önce Sectra\'dan içe aktarılmış).%n"Yalnız ekle", aynı .dcm daha önce aktarıldıysa ikinci kopya oluşturur.%n%nÖnceki anotasyonlar ne olsun?', existingAnns.size(), nSectra),
            [SECTRA_KEEP, SECTRA_REPLACE], SECTRA_KEEP)
        if (pick == null) return
        replacePrevious = (pick == SECTRA_REPLACE)
    }
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
        def imp = importGeoJSON(outGeojson, imageData.getHierarchy(), replacePrevious)
        javafx.application.Platform.runLater {
            if (!imp.ok) { errorTextRef.set(imp.error); step.set('ERROR'); render() }
            else {
                try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
                resultTextRef.set(buildResultText(slideName, schemeDisp, imp, replacePrevious)); resultTitleRef.set('Tamamlandı ✅'); resultBtnRef.set('↻ Yeni .dcm'); step.set('RESULT'); render()
            }
        }
    }, 'AtolyeSectra-Convert')
    worker.setDaemon(true); worker.start()
}

// ── Tüm proje (batch) içe aktarım ───────────────────────────────────────────
// Eşleştirme: <slayt dosyası UZANTISIYLA>.graphics.<YYYY-MM-DD_HH-MM-SS>.dcm — ad ÖNEKİ birebir (getURIs'ten).
// sectra-qupath'in "herhangi bir .dcm / ilk bulunan" hatası burada yok: başka slaytın dosyası ya da kendi
// .geojson çıktısı asla eşleşmez. Aynı slaytın birden çok dışa aktarımında en yeni zaman damgası önseçilidir.
def batchRowsRef = new java.util.concurrent.atomic.AtomicReference([project: null, rows: [], unmatched: []])
def batchDirRef  = new java.util.concurrent.atomic.AtomicReference(null)
def SECTRA_TS = ~/(?i)\.graphics\.(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})\.dcm$/
def slideFileOf = { entry ->
    try { for (u in (entry.getURIs() ?: [])) { if (u != null && 'file'.equals(u.getScheme())) return new File(u).getName() } } catch (Throwable ignore) {}
    return null
}
def tsOf = { String name -> def m = (name =~ SECTRA_TS); m.find() ? m.group(1) : '' }
def TRANSFORM_BUILDER = ~/(?i)^.*\$(Cropped|Rotated|AffineTransform|Flipped|Sparse)ImageServerBuilder$/
def isTransformedEntry = { entry ->
    try { def b = entry.getServerBuilder(); return b != null && (b.getClass().getName() ==~ TRANSFORM_BUILDER) } catch (Throwable t) { return false }
}
def buildSectraRows = { File dir ->
    def project = QP.getProject()
    def dcms = (dir.listFiles()?.findAll { it.isFile() && it.getName().toLowerCase(java.util.Locale.ROOT).endsWith('.dcm') } ?: []).sort { it.getName() }
    def rows = []
    project.getImageList().each { e ->
        String sf = slideFileOf(e)
        String pfx = (sf == null) ? null : (sf.toLowerCase(java.util.Locale.ROOT) + '.graphics.')
        def cands = (pfx == null) ? [] : dcms.findAll { it.getName().toLowerCase(java.util.Locale.ROOT).startsWith(pfx) }
        cands = cands.sort { a, b -> tsOf(b.getName()) <=> tsOf(a.getName()) }   // en yeni önce
        String status = (sf == null) ? 'dosya URI\'si yok' : (cands.isEmpty() ? '.dcm yok' : (cands.size() > 1 ? (cands.size() + ' sürüm — en yenisi seçili') : 'eşleşti'))
        def row = [entry: e, name: e.getImageName(), slideFile: sf, candidates: cands, file: (cands ? cands[0] : null), include: !cands.isEmpty(), status: status]
        if (isTransformedEntry(e)) {
            // kırpılmış/dönüştürülmüş görüntüde Sectra (tam slayt) koordinatları uymaz → önseçim yok
            row.file = null; row.include = false; row.transformed = true; row.status = 'dönüştürülmüş görüntü (kırpma vb.) — Sectra koordinatları uymaz'
        }
        rows.add(row)
    }
    // Aynı .dcm birden çok satıra önseçiliyorsa (aynı slayt dosyası adı, kırpma girdisi…) hepsi belirsiz
    def claims = [:]
    rows.each { r -> if (r.file != null) { String k = r.file.getPath(); claims.put(k, ((claims.get(k) ?: 0) as int) + 1) } }
    rows.each { r ->
        if (r.file != null && (claims.get(r.file.getPath()) as int) > 1) {
            r.include = false; r.status = 'belirsiz — aynı .dcm birden çok görüntüye uyuyor'
        }
    }
    def used = new HashSet(rows.collectMany { it.candidates }.collect { it.getPath() })
    return [project: project, rows: rows, unmatched: dcms.findAll { !used.contains(it.getPath()) }]
}

def startBatch = {
    def cfg = loadConfig()
    def project = batchRowsRef.get()?.project          // önizleme kurulurken SABİTLENEN proje
    if (project == null || QP.getProject() == null) { errorTextRef.set('Proje açık değil.'); step.set('ERROR'); render(); return }
    def rows = (batchRowsRef.get()?.rows ?: []).findAll { it.include && it.file != null }
    if (!QP.getProject().is(project) || rows.any { r -> !project.getImageList().any { e -> e.is(r.entry) } }) {
        errorTextRef.set('Proje değişti; önizlemeyi yeniden oluşturun.'); step.set('ERROR'); render(); return
    }
    if (rows.isEmpty()) { errorTextRef.set('İçe aktarılacak işaretli satır yok.'); step.set('ERROR'); render(); return }
    def dupCount = [:]
    rows.each { r -> String k = r.file.getPath(); dupCount.put(k, ((dupCount.get(k) ?: 0) as int) + 1) }
    if (dupCount.values().any { (it as int) > 1 }) {
        errorTextRef.set('Aynı .dcm birden çok görüntü için seçili; önizlemede düzeltin.'); step.set('ERROR'); render(); return
    }
    // Önceki anotasyonlar için SOR — varsayılan: hiçbir şey silme (kullanıcı kararı D5)
    def pick = Dialogs.showChoiceDialog('Önceki anotasyonlar',
        rows.size() + ' görüntüye Sectra anotasyonu aktarılacak.\n"Yalnız ekle", aynı .dcm daha önce aktarıldıysa ikinci kopya oluşturur.\n\nÖnceki anotasyonlar ne olsun?',
        [SECTRA_KEEP, SECTRA_REPLACE], SECTRA_KEEP)
    if (pick == null) return
    boolean replacePrevious = (pick == SECTRA_REPLACE)
    def workDir = cfg.workDir?.trim() ? new File(cfg.workDir.trim()) : new File(project.getPath().getParent().toFile(), 'sectra_geojson')
    workDir.mkdirs()
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    step.set('BATCH_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        // Açık görüntü sorgusu FX iş parçacığında yapılır (ImageData erişimi); başarısızlıkta KAPALI-BAŞARISIZ:
        // o görüntü 'hata' olur ve hiçbir şey yazılmaz (yoksa açık slaytın ikinci kopyası diskten okunup kaydedilirdi).
        def onFx = { Closure c -> def ft = new java.util.concurrent.FutureTask(c as java.util.concurrent.Callable); javafx.application.Platform.runLater(ft); ft.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        def report = new StringBuilder(['goruntu_adi', 'dcm', 'durum', 'eklenen', 'silinen', 'atlanan', 'not'].join('\t') + '\n')
        def clean = { v -> (v == null ? '' : String.valueOf(v)).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }
        int ok = 0, fail = 0, skip = 0, skipT = 0
        boolean liveTouched = false, cancelled = false
        int idx = 0
        for (r in rows) {
            idx++
            if (cancelledRef.get()) {
                cancelled = true; appendLine('⏹ İptal edildi — kalan görüntüler işlenmedi.')
                for (int k = idx - 1; k < rows.size(); k++) {
                    skip++
                    report << [rows[k].name, rows[k].file.getName(), 'atlandı', 0, 0, 0, 'iptal edildi — işlenmedi'].collect { clean(it) }.join('\t') << '\n'
                }
                break
            }
            appendLine('[' + idx + '/' + rows.size() + '] ' + r.name + '  ←  ' + r.file.getName())
            if (r.transformed || isTransformedEntry(r.entry)) {
                skipT++
                report << [r.name, r.file.getName(), 'atlandı', 0, 0, 0, 'dönüştürülmüş görüntü — Sectra koordinatları uymaz'].collect { clean(it) }.join('\t') << '\n'
                appendLine('   ⏭ dönüştürülmüş görüntü — Sectra koordinatları uymaz')
                continue
            }
            def data = null
            def live = null
            boolean opened = false
            try {
                def findLive = { -> onFx { ->
                    for (v in gui.getAllViewers()) {
                        def d = v.getImageData()
                        if (d == null) continue
                        def le = project.getEntry(d)
                        if (le != null && le.is(r.entry)) return d
                    }
                    return null
                } }
                live = findLive()
                data = live
                if (data == null) { data = r.entry.readImageData(); opened = true }
                def server = data.getServer()
                def outGeojson = new File(workDir, (r.slideFile ?: r.name) + '.sectra.geojson')
                def pr = runPython(convertCmd(cfg, r.file, outGeojson, server.getWidth(), server.getHeight()), { String ln -> })
                if (!pr.ok && pr.exitCode == -3) throw new java.util.concurrent.CancellationException('iptal edildi')
                if (!pr.ok) throw new IOException('dönüştürme başarısız (çıkış ' + pr.exitCode + '): ' + ((pr.error ?: pr.lastLines ?: '').readLines().takeRight(1).join(' ')))
                // Dönüştürme sürerken kullanıcı slaytı açmış olabilir: içe aktarmadan önce yeniden çöz (başarısızlıkta KAPALI-BAŞARISIZ)
                def live2 = findLive()
                if (live2 != null && opened) {
                    try { data.getServer().close() } catch (Throwable ignore) {}
                    data = live2; live = live2; opened = false
                }
                def imp = importGeoJSON(outGeojson, data.getHierarchy(), replacePrevious)
                if (!imp.ok) throw new IOException(imp.error)
                r.entry.saveImageData(data)
                if (live != null) liveTouched = true
                ok++
                report << [r.name, r.file.getName(), 'tamam', imp.annotations.size(), imp.removed, imp.skipped, ''].collect { clean(it) }.join('\t') << '\n'
                appendLine('   ✓ ' + imp.annotations.size() + ' anotasyon eklendi' + (replacePrevious ? (', önceki Sectra içe aktarımından ' + imp.removed + ' silindi') : ''))
            } catch (java.util.concurrent.CancellationException ce) {
                cancelled = true; skip++
                report << [r.name, r.file.getName(), 'atlandı', 0, 0, 0, 'iptal edildi'].collect { clean(it) }.join('\t') << '\n'
                appendLine('   ⏹ iptal edildi')
            } catch (Throwable e) {
                fail++
                String msg = (e instanceof java.util.concurrent.TimeoutException) ? 'açık görüntü sorgusu zaman aşımına uğradı' : (e.getMessage() ?: e.getClass().getSimpleName())
                report << [r.name, r.file.getName(), 'hata', 0, 0, 0, msg].collect { clean(it) }.join('\t') << '\n'
                appendLine('   ✗ ' + msg)
            } finally {
                if (opened && data != null) { try { data.getServer().close() } catch (Throwable ignore) {} }
            }
        }
        def rf = new File(new File(project.getPath().getParent().toFile(), exportFolder),
            'sectra_iceaktarma_' + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern('yyyy-MM-dd_HHmmss')) + '.tsv')
        String reportErr = null
        try { rf.getParentFile().mkdirs(); rf.setText(report.toString(), 'UTF-8') } catch (Throwable we) { reportErr = we.getMessage() ?: we.getClass().getSimpleName() }
        def sb = new StringBuilder()
        sb << 'SECTRA PACS ANOTASYON — TÜM PROJE İÇE AKTARMA' << (cancelled ? ' (İPTAL EDİLDİ)' : '') << '\n'
        sb << '══════════════════════════════════════════════\n\n'
        sb << String.format(java.util.Locale.US, 'İşaretli görüntü: %,d   içe aktarıldı: %,d   hata: %,d%n', rows.size(), ok, fail)
        sb << 'Önceki anotasyonlar: ' << (replacePrevious ? 'Sectra bayraklı olanlar değiştirildi' : 'korundu (yalnız eklendi)') << '\n'
        if (skip > 0) sb << String.format(java.util.Locale.US, 'İptal nedeniyle işlenmedi: %,d%n', skip)
        if (skipT > 0) sb << String.format(java.util.Locale.US, 'Dönüştürülmüş görüntü nedeniyle atlandı: %,d%n', skipT)
        sb << 'Rapor   : ' << (reportErr == null ? rf.getAbsolutePath() : ('⚠ Rapor yazılamadı: ' + reportErr)) << '\n'
        sb << 'GeoJSON : ' << workDir.getAbsolutePath() << '\n\n'
        sb << '⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.'
        javafx.application.Platform.runLater {
            if (liveTouched) { try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {} }
            resultTitleRef.set(cancelled ? 'İptal edildi' : (fail > 0 ? 'Tamamlandı (hatalarla) ⚠' : 'Tamamlandı ✅')); resultBtnRef.set('↻ Yeni içe aktarma')
            resultTextRef.set(sb.toString()); step.set('RESULT'); render()
        }
    }, 'AtolyeSectra-Batch')
    worker.setDaemon(true); worker.start()
}

def openBatchPreview = {
    def d = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Sectra .dcm klasörünü seçin', batchDirRef.get())
    if (d == null) return
    batchDirRef.set(d)
    def built = buildSectraRows(d)
    batchRowsRef.set([project: built.project, rows: built.rows, unmatched: built.unmatched])
    step.set('BATCH_PREVIEW'); render()
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

    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); lbl.setMaxWidth(Double.MAX_VALUE); center.getChildren().add(lbl) }
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
            if (QP.getProject() != null) {
                addGuidance('Proje açık: "Tüm proje (klasörden)" ile bir klasördeki Sectra .dcm dosyalarını tüm proje görüntülerine aktarabilirsiniz.')
                def batchBtn = navButton('Tüm proje (klasörden) ▶', { openBatchPreview() }, '<slayt>.graphics.<tarih>.dcm dosyalarını proje görüntüleriyle eşler')
                batchBtn.setDisable(!configComplete(cfg))
                actions.add(batchBtn)
            }
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
            if (QP.getProject() != null) {
                def batchBtn = navButton('Tüm proje (klasörden) ▶', { openBatchPreview() }, '<slayt>.graphics.<tarih>.dcm dosyalarını proje görüntüleriyle eşler')
                batchBtn.setDisable(!canRun)
                actions.add(batchBtn)
            }
        }
    } else if (cur == 'CONVERT_RUNNING') {
        title.setText('Dönüştürülüyor (DICOM → GeoJSON)…')
        addGuidance('Python köprüsü çalışıyor. Çıktı aşağıda akıyor. Zaman aşımı: ' + PYTHON_TIMEOUT_SECONDS + ' sn.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'BATCH_PREVIEW') {
        def pv = batchRowsRef.get()
        def rows = pv.rows
        title.setText('Tüm proje — eşleştirme önizlemesi (henüz hiçbir şey yazılmadı)')
        int nOk = rows.count { it.file != null } as int
        addGuidance(String.format(java.util.Locale.US, '%,d görüntü · .dcm eşleşen %,d · eşleşmeyen .dcm %,d — klasör: %s',
            rows.size(), nOk, pv.unmatched.size(), batchDirRef.get()?.getAbsolutePath() ?: '-'))
        String NONE = '— dosya yok —'
        def refreshBatchBtn = { -> }
        def byName = new LinkedHashMap()
        rows.each { r -> r.candidates.each { f -> byName.put(f.getName(), f) } }
        pv.unmatched.each { f -> byName.put(f.getName(), f) }
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(10); grid.setVgap(6)
        ['İçe aktar', 'Görüntü', 'Sectra .dcm', 'Durum'].eachWithIndex { String h, int c ->
            def l = new javafx.scene.control.Label(h); l.setStyle('-fx-font-weight: bold;'); grid.add(l, c, 0)
        }
        String DUP = 'belirsiz — aynı .dcm birden çok görüntüde seçili'
        def stLabels = [:]
        def refreshClaims = { -> }
        rows.eachWithIndex { r, int i ->
            int rowIdx = i + 1
            def inc = new javafx.scene.control.CheckBox(); inc.setSelected((boolean) r.include); inc.setDisable(r.file == null || r.transformed == true)
            inc.selectedProperty().addListener({ o, ov, nv -> r.include = nv; refreshClaims(); refreshBatchBtn() } as javafx.beans.value.ChangeListener)
            def combo = new javafx.scene.control.ComboBox()
            combo.getItems().addAll(([NONE] + r.candidates.collect { it.getName() } + pv.unmatched.collect { it.getName() }).unique())
            combo.setValue(r.file != null ? r.file.getName() : NONE)
            def st = new javafx.scene.control.Label((String) r.status); st.setWrapText(true); st.setMaxWidth(320)
            stLabels.put(r, st)
            if (r.transformed == true) { combo.setDisable(true) }
            combo.valueProperty().addListener({ o, ov, nv ->
                def f = (nv == null || nv == NONE) ? null : byName.get(nv)
                r.file = f; r.include = (f != null)
                inc.setSelected((boolean) r.include); inc.setDisable(f == null)
                r.status = (f == null) ? '.dcm yok' : 'elle seçildi'
                st.setText((String) r.status)
                refreshClaims()
                refreshBatchBtn()
            } as javafx.beans.value.ChangeListener)
            grid.add(inc, 0, rowIdx); grid.add(new javafx.scene.control.Label((String) r.name), 1, rowIdx)
            grid.add(combo, 2, rowIdx); grid.add(st, 3, rowIdx)
        }
        refreshClaims = { ->
            def cnt = [:]
            rows.each { r -> if (r.include && r.file != null) { String k = r.file.getPath(); cnt.put(k, ((cnt.get(k) ?: 0) as int) + 1) } }
            rows.each { r ->
                boolean dup = r.include && r.file != null && (cnt.get(r.file.getPath()) as int) > 1
                if (dup && r.status != DUP) { r.status = DUP; stLabels.get(r)?.setText(DUP) }
                else if (!dup && r.status == DUP) { r.status = 'elle seçildi'; stLabels.get(r)?.setText((String) r.status) }
            }
        }
        def sp = new javafx.scene.control.ScrollPane(grid); sp.setFitToWidth(true)
        javafx.scene.layout.VBox.setVgrow(sp, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(sp)
        addGuidance('Eşleşme yalnız tam ad önekiyle yapılır: <slayt dosyası>.graphics.<tarih>.dcm. "İçe aktar" önce mevcut ' +
            'anotasyonlar için ne yapılacağını sorar (varsayılan: dokunma).')
        actions.add(navButton('◀ Geri', { step.set('READY'); render() }))
        def runBatchBtn = navButton('İçe aktar ▶', { startBatch() }, 'İşaretli görüntüleri dönüştürür, içe aktarır ve kaydeder')
        refreshBatchBtn = { -> runBatchBtn.setDisable(!rows.any { it.include && it.file != null }) }
        refreshBatchBtn()
        actions.add(runBatchBtn)
    } else if (cur == 'BATCH_RUNNING') {
        title.setText('Tüm proje içe aktarılıyor (DICOM → GeoJSON → QuPath)…')
        addGuidance('Her görüntü için Python köprüsü çalışır; iptal, sıradaki görüntüden önce durur.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'BUSY') {
        title.setText(busyLabelRef.get()); addGuidance('Lütfen bekleyin…'); center.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        title.setText(resultTitleRef.get())
        addMonoArea(resultTextRef.get())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton(resultBtnRef.get(), { step.set('READY'); render() }))
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
