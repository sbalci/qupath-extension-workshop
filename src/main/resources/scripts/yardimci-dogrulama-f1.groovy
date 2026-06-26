/**
 * Yardımcı - Tespit Doğrulama (F1 / IoU) — altın standarda karşı
 * ------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * SEÇİLİ tek bir "doğrulama bölgesi" anotasyonu içinde, ELLE çizilmiş REFERANS
 * (altın standart) nesnelerini OTOMATİK tespitlerle karşılaştırır ve uyumu
 * raporlar: doğru eşleşen (TP), yanlış pozitif (FP), kaçırılan (FN) ve bunlardan
 * türeyen precision / recall / F1. İki eşleştirme modu:
 *   • IoU (alan örtüşmesi)  — poligon-poligon; eşik (varsayılan 0.50).
 *   • Merkez (centroid)     — nokta/dot referans için; tahmin poligonu referans
 *     noktasını içeriyorsa ya da merkez mesafesi eşik (µm) altındaysa eşleşir.
 * Eşleştirme açgözlü (greedy) bire-bir yapılır.
 *
 * NEDEN: Atölyede tespit/sınıflandırma adımları (Modül 2, 3a, StarDist sihirbazı…)
 * görsel olarak "iyi görünür" — ama otomatik tespitin patoloğun işaretlediği
 * referansla NE KADAR uyuştuğunu SAYISAL ölçen bir araç yoktu. Ek J anotasyon
 * stratejisini, Ek W değerlendirmeyi KAVRAMSAL anlatır; bu yardımcı uyumu
 * ÖLÇER. (Thierry Pécot'un "Whole Slide Image Analysis with QuPath" eğitimindeki
 * compute_F1_score_for_IoU_threshold yaklaşımının atölye karşılığı.)
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Örneklenen KÜÇÜK bir bölgede otomatik tespit ↔ referans anotasyon UYUMU.
 *     Bu bir ÖLÇÜM kalite göstergesidir — klinik doğruluk iddiası ya da
 *     (vaka tasarımlı, çok okuyuculu) bir VALİDASYON çalışması DEĞİL.
 *   • Tespit/sınıflandırma YAPMAZ. Önce bir tespit adımı çalıştırın; referansı
 *     elle işaretleyin (Nokta aracı ya da küçük poligonlar, ayrı bir sınıfla).
 *   • Küçük bir doğrulama yaması içindir (onlarca – birkaç yüz nesne); tüm
 *     slaytta ÇALIŞTIRMAYIN (eşleştirme nesne sayısının karesiyle büyür).
 *
 * KULLANIM:
 *   1. Tespit içeren bir slayt açın ve bir tespit adımı çalıştırın (Modül 2 vb.)
 *   2. Küçük bir bölge anotasyonu çizin = "doğrulama bölgesi"
 *   3. O bölgenin içindeki gerçek çekirdekleri ELLE işaretleyin: ayrı bir sınıf
 *      verin (ör. "Altın standart"). Nokta aracı ya da küçük poligon kullanın.
 *   4. Doğrulama bölgesi anotasyonunu SEÇİN
 *   5. [Extensions → Atölye → Yardımcılar → Tespit doğrulama (F1 / IoU)]
 *      (ya da [Automate → Project scripts → bu betik])
 *   6. Açılan pencerede altın standart sınıfını, tahmin kümesini, modu ve eşiği
 *      seçip "Hesapla".
 *
 * ÇIKTI:
 *   • Sonuç penceresinde TP / FP / FN + precision / recall / F1
 *   • Kilitli "Doğrulama Özet" anotasyonu (Modül 9 ile dışa aktarılır)
 *   • (İsteğe bağlı) tahminleri renklendir: TP / FP sınıflarına ayır
 *
 * YÖNTEM REFERANSLARI:
 *   • Pécot T — Whole Slide Image Analysis with QuPath (CC-BY): zenodo 6391629;
 *     compute_F1_score_for_IoU_threshold.groovy.
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

String summaryName = "Doğrulama Özet"
String ALL_DET = "▸ Tüm tespitler (sınıf farketmez)"
double DEFAULT_IOU = 0.50d
double DEFAULT_CENTROID_UM = 5.0d   // ~ bir çekirdek yarıçapı (µm)

// ── Yardımcı: sınıf etiketi ────────────────────────────────────────────
def labelOf = { o ->
    def pc = o.getPathClass()
    return (pc != null) ? pc.toString() : "(sınıfsız)"
}

// ── Bağlam: görüntü + seçili doğrulama bölgesi + içindeki nesneler ──────
// Dönüş: [ok:true, imageData, patch, inPatch, hasMicrons, avgUm]
//      | [ok:false, reason:'NO_IMAGE'|'NO_SELECTION']
def gatherContext = { ->
    def imageData = QP.getCurrentImageData()
    if (imageData == null) return [ok: false, reason: 'NO_IMAGE']

    def patches = QP.getSelectedObjects().findAll {
        it.isAnnotation() && it.hasROI() && it.getROI().isArea() && it.getName() != summaryName
    }
    if (patches.isEmpty()) return [ok: false, reason: 'NO_SELECTION']
    def patch = patches[0]
    def patchRoi = patch.getROI()

    def cal = imageData.getServer().getPixelCalibration()
    boolean hasMicrons = (cal != null && cal.hasPixelSizeMicrons())
    double avgUm = hasMicrons ?
        ((cal.getPixelWidthMicrons() + cal.getPixelHeightMicrons()) / 2.0d) : Double.NaN

    // Bölge içi nesneler: tüm anotasyon + tespitler, centroid-içinde (getChildObjects'e
    // bağlı değil — iç içe anotasyonlara dayanıklı; sinif-sayim ile aynı kalıp).
    def all = QP.getAnnotationObjects() + QP.getDetectionObjects()
    def inPatch = all.findAll { o ->
        if (o == patch) return false
        if (o.getName() == summaryName) return false
        def r = o.getROI()
        r != null && patchRoi.contains(r.getCentroidX(), r.getCentroidY())
    }
    return [ok: true, imageData: imageData, patch: patch, inPatch: inPatch,
            hasMicrons: hasMicrons, avgUm: avgUm, multi: patches.size()]
}

// ── Nesneleri eşleştirme birimlerine çevir ─────────────────────────────
// Her birim: [cx, cy, geom (JTS alan geometrisi ya da null), roi, source]
// Nokta (PointsROI) nesneleri TEK TEK noktalara açılır (her nokta = bir birim).
def buildUnits = { objs ->
    def units = []
    objs.each { o ->
        def r = o.getROI()
        if (r == null) return
        if (r.isPoint()) {
            r.getAllPoints().each { pt ->
                units << [cx: pt.getX() as double, cy: pt.getY() as double,
                          geom: null, roi: null, source: null]
            }
        } else if (r.isArea()) {
            def g = null
            try { g = r.getGeometry() } catch (Throwable ignore) { g = null }
            units << [cx: r.getCentroidX(), cy: r.getCentroidY(), geom: g, roi: r, source: o]
        } else {
            units << [cx: r.getCentroidX(), cy: r.getCentroidY(), geom: null, roi: r, source: o]
        }
    }
    return units
}

// ── Açgözlü bire-bir eşleştirme ────────────────────────────────────────
// mode: 'IOU' | 'CENTROID'. Dönüş: [tp, fp, fn, matchedPred(set indices), error?]
def computeMatch = { gtUnits, predUnits, mode, double thr, boolean hasMicrons, double avgUm ->
    int nGt = gtUnits.size()
    int nPred = predUnits.size()

    if (mode == 'IOU') {
        boolean gtArea = gtUnits.every { it.geom != null }
        boolean predArea = predUnits.every { it.geom != null }
        if (!gtArea || !predArea) {
            return [error: 'IOU_NEEDS_AREA']
        }
    }

    def pairs = []   // [gi, pi, score] — IoU: yüksek iyi; Merkez: düşük (mesafe) iyi
    double dthrPx = hasMicrons ? (thr / avgUm) : thr   // Merkez modunda eşik px'e çevrilir

    gtUnits.eachWithIndex { g, gi ->
        predUnits.eachWithIndex { p, pi ->
            if (mode == 'IOU') {
                def ga = g.geom; def pa = p.geom
                if (ga == null || pa == null) return
                try {
                    if (!ga.getEnvelopeInternal().intersects(pa.getEnvelopeInternal())) return
                    double inter = ga.intersection(pa).getArea()
                    if (inter <= 0) return
                    double uni = ga.getArea() + pa.getArea() - inter
                    if (uni <= 0) return
                    double iou = inter / uni
                    if (iou >= thr) pairs << [gi: gi, pi: pi, score: iou]
                } catch (Throwable ignore) { /* geçersiz poligon → örtüşme yok say */ }
            } else { // CENTROID
                boolean contained = false
                if (p.roi != null && p.roi.isArea()) {
                    try { contained = p.roi.contains(g.cx, g.cy) } catch (Throwable ignore) {}
                }
                double dpx = Math.hypot(g.cx - p.cx, g.cy - p.cy)
                if (contained || dpx <= dthrPx) {
                    pairs << [gi: gi, pi: pi, score: (contained ? 0.0d : dpx)]
                }
            }
        }
    }

    if (mode == 'IOU') pairs.sort { a, b -> Double.compare(b.score, a.score) }   // desc
    else               pairs.sort { a, b -> Double.compare(a.score, b.score) }   // asc

    def usedG = new HashSet()
    def usedP = new HashSet()
    pairs.each { pr ->
        if (!usedG.contains(pr.gi) && !usedP.contains(pr.pi)) {
            usedG.add(pr.gi); usedP.add(pr.pi)
        }
    }
    int tp = usedG.size()
    int fn = nGt - tp
    int fp = nPred - usedP.size()
    return [tp: tp, fp: fp, fn: fn, usedP: usedP, nGt: nGt, nPred: nPred]
}

// ── Bir doğrulama koşusu: bağlam + seçimler → sonuç metni + sayılar ─────
// Dönüş: [ok:true, text, tp, fp, fn, precision, recall, f1, ...]
//      | [ok:false, reason, ...]
def runValidation = { String gtLabel, String predLabel, String mode, double thr,
                      boolean recolor, ctx ->
    def inPatch = ctx.inPatch
    boolean predAll = (predLabel == ALL_DET)

    def gtObjs = inPatch.findAll { labelOf(it) == gtLabel }
    def predObjs = predAll ?
        inPatch.findAll { it.isDetection() && labelOf(it) != gtLabel } :
        inPatch.findAll { labelOf(it) == predLabel }

    if (!predAll && predLabel == gtLabel) return [ok: false, reason: 'SAME']
    if (gtObjs.isEmpty()) return [ok: false, reason: 'NO_GT']
    if (predObjs.isEmpty()) return [ok: false, reason: 'NO_PRED']

    def gtUnits = buildUnits(gtObjs)
    def predUnits = buildUnits(predObjs)
    if (gtUnits.isEmpty()) return [ok: false, reason: 'NO_GT']
    if (predUnits.isEmpty()) return [ok: false, reason: 'NO_PRED']

    def m = computeMatch(gtUnits, predUnits, mode, thr, ctx.hasMicrons, ctx.avgUm)
    if (m.error == 'IOU_NEEDS_AREA') return [ok: false, reason: 'IOU_NEEDS_AREA']

    int tp = m.tp; int fp = m.fp; int fn = m.fn
    double precision = (tp + fp) > 0 ? (tp / (double) (tp + fp)) : Double.NaN
    double recall    = (tp + fn) > 0 ? (tp / (double) (tp + fn)) : Double.NaN
    double f1        = (2 * tp + fp + fn) > 0 ? ((2.0d * tp) / (2.0d * tp + fp + fn)) : Double.NaN

    boolean gtArea = gtUnits.every { it.geom != null }
    String gtKind = gtArea ? "poligon" : "nokta/karışık"
    String thrLine = (mode == 'IOU') ?
        String.format(java.util.Locale.US, "IoU (eşik %.2f)", thr) :
        (ctx.hasMicrons ?
            String.format(java.util.Locale.US, "Merkez (≤ %.1f µm)", thr) :
            String.format(java.util.Locale.US, "Merkez (≤ %.1f px — kalibrasyon yok)", thr))

    def fmtMetric = { double v -> Double.isNaN(v) ? "—" : String.format(java.util.Locale.US, "%.3f", v) }

    def b = new StringBuilder()
    b << "TESPİT DOĞRULAMA (altın standarda karşı)\n"
    b << "═══════════════════════════════════════════\n\n"
    b << "Doğrulama bölgesi : " << (ctx.patch.getName() ?: "(adsız)") << "\n"
    b << "Eşleştirme modu   : " << thrLine << "\n"
    b << String.format(java.util.Locale.US, "Altın standart    : %-28s (N=%d, %s)%n", gtLabel, gtUnits.size(), gtKind)
    b << String.format(java.util.Locale.US, "Tahmin            : %-28s (N=%d)%n", (predAll ? "tüm tespitler" : predLabel), predUnits.size())
    b << "\n"
    b << String.format(java.util.Locale.US, "Eşleşen (TP)        : %d%n", tp)
    b << String.format(java.util.Locale.US, "Yanlış pozitif (FP) : %d   (tahmin var, referans yok)%n", fp)
    b << String.format(java.util.Locale.US, "Kaçırılan (FN)      : %d   (referans var, tahmin yok)%n", fn)
    b << "\n"
    b << "Precision (kesinlik)  : " << fmtMetric(precision) << "   = TP/(TP+FP)\n"
    b << "Recall (duyarlılık)   : " << fmtMetric(recall) << "   = TP/(TP+FN)\n"
    b << "F1 skoru              : " << fmtMetric(f1) << "   = 2·TP/(2·TP+FP+FN)\n"
    b << "\n"
    b << "Eşleştirme açgözlü bire-bir; skorlar 0–1 arası (yüzde değil).\n"
    b << "Bu bir ÖLÇÜM kalite göstergesidir (örneklenen bölgede otomatik tespit ile\n"
    b << "referans anotasyon UYUMU) — klinik doğruluk iddiası ya da validasyon\n"
    b << "çalışması DEĞİL. Validasyon tasarımı için bkz. Ek U; AI değerlendirme: Ek W.\n\n"
    b << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

    // ── Kilitli özet anotasyonu (Modül 9 ile dışa aktarılır) ───────────
    QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == summaryName }, false)
    def srv = ctx.imageData.getServer()
    def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
        qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
            qupath.lib.regions.ImagePlane.getDefaultPlane()))
    summary.setName(summaryName)
    summary.measurements["Doğrulama: TP"] = tp as double
    summary.measurements["Doğrulama: FP"] = fp as double
    summary.measurements["Doğrulama: FN"] = fn as double
    if (!Double.isNaN(precision)) summary.measurements["Doğrulama: Precision"] = precision
    if (!Double.isNaN(recall))    summary.measurements["Doğrulama: Recall"] = recall
    if (!Double.isNaN(f1))        summary.measurements["Doğrulama: F1"] = f1
    summary.measurements["Doğrulama: Altın standart (N)"] = gtUnits.size() as double
    summary.measurements["Doğrulama: Tahmin (N)"] = predUnits.size() as double
    if (mode == 'IOU') summary.measurements["Doğrulama: IoU eşik"] = thr
    else summary.measurements[ctx.hasMicrons ? "Doğrulama: Merkez eşik (µm)" : "Doğrulama: Merkez eşik (px)"] = thr
    summary.setLocked(true)
    QP.addObjects([summary])

    // ── (İsteğe bağlı) tahminleri renklendir: TP / FP ──────────────────
    int recolored = 0
    if (recolor) {
        def makeClass = { String name, int r, int g, int bl ->
            try {
                return qupath.lib.objects.classes.PathClass.getInstance(name,
                        qupath.lib.common.ColorTools.packRGB(r, g, bl))
            } catch (Throwable t) {
                return QP.getPathClass(name)
            }
        }
        def tpClass = makeClass("Doğrulama: TP", 0, 160, 0)
        def fpClass = makeClass("Doğrulama: FP", 215, 45, 45)
        predUnits.eachWithIndex { p, pi ->
            if (p.source == null) return   // açılmış nokta — tek tek renklendirilemez
            p.source.setPathClass(m.usedP.contains(pi) ? tpClass : fpClass)
            recolored++
        }
    }
    QP.fireHierarchyUpdate()

    return [ok: true, text: b.toString(), tp: tp, fp: fp, fn: fn,
            precision: precision, recall: recall, f1: f1, recolored: recolored]
}

// ── Sınıf seçeneklerini ve makul varsayılanları hesapla ────────────────
def buildOptions = { inPatch ->
    def gtOptions = inPatch.collect { labelOf(it) }.unique()
    def predDetLabels = inPatch.findAll { it.isDetection() }.collect { labelOf(it) }.unique()
    def predOptions = [ALL_DET] + predDetLabels

    // GT varsayılanı: adı referans/altın standart çağrıştıran sınıf, yoksa ilk anotasyon sınıfı
    def gtDefault = gtOptions.find { lbl ->
        def lc = lbl.toLowerCase(java.util.Locale.ROOT)
        lc.contains("altın") || lc.contains("standart") || lc.contains("referans") ||
        lc.contains("gerçek") || lc.contains("ground") || lc.contains("truth") || lc == "gt"
    }
    if (gtDefault == null) {
        def annLabel = inPatch.find { it.isAnnotation() }
        gtDefault = annLabel != null ? labelOf(annLabel) : (gtOptions.isEmpty() ? null : gtOptions[0])
    }
    // GT'nin alan mı nokta mı olduğuna göre mod varsayılanı
    String modeDefault = 'IOU'
    if (gtDefault != null) {
        def gtObjs = inPatch.findAll { labelOf(it) == gtDefault }
        boolean anyPoint = gtObjs.any { it.getROI() != null && it.getROI().isPoint() }
        boolean allArea = gtObjs.every { it.getROI() != null && it.getROI().isArea() }
        if (anyPoint || !allArea) modeDefault = 'CENTROID'
    }
    return [gtOptions: gtOptions, predOptions: predOptions,
            gtDefault: gtDefault, modeDefault: modeDefault]
}

// ── HEADLESS: form yok — makul varsayılanlarla çalıştır, raporla ───────
if (isHeadless) {
    def ctx = gatherContext()
    if (!ctx.ok) {
        if (ctx.reason == 'NO_IMAGE') println "Önce bir slayt açın."
        else println "Önce bir doğrulama bölgesi anotasyonu çizip SEÇİN."
        return
    }
    def opt = buildOptions(ctx.inPatch)
    if (opt.gtDefault == null) { println "Bölge içinde altın standart adayı nesne yok."; return }
    double thr = (opt.modeDefault == 'IOU') ? DEFAULT_IOU : DEFAULT_CENTROID_UM
    def res = runValidation(opt.gtDefault, ALL_DET, opt.modeDefault, thr, false, ctx)
    if (!res.ok) {
        def msg = [NO_GT: "Altın standart sınıfında nesne yok.",
                   NO_PRED: "Tahmin (tespit) bulunamadı.",
                   SAME: "GT ve tahmin aynı topluluk olamaz.",
                   IOU_NEEDS_AREA: "IoU modu alan (poligon) gerektirir; Merkez modunu kullanın."][res.reason]
        println(msg ?: ("Doğrulama yapılamadı: " + res.reason))
        return
    }
    println "=== Tespit doğrulama (headless, varsayılan seçimler) ===\n" + res.text
    return
}

// ── Tek pencere, adım adım render (modul-06 / boya-vektor kalıbı) ───────
def stage = null
def step = new AtomicReference('FORM')   // FORM | COMPUTING | RESULT | error states
def ctxRef = new AtomicReference(null)
def resultRef = new AtomicReference(null)
def errorRef = new AtomicReference(null)
def alwaysTop = new AtomicBoolean(true)

// Form durumu (render yeniden kursa da korunur)
def gtChoice = new AtomicReference(null)
def predChoice = new AtomicReference(ALL_DET)
def modeChoice = new AtomicReference('IOU')
def iouThr = new AtomicReference(DEFAULT_IOU as Double)
def centThr = new AtomicReference(DEFAULT_CENTROID_UM as Double)
def recolor = new AtomicBoolean(false)
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
def repaintViewer = {
    javafx.application.Platform.runLater {
        try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignored) {}
    }
}

// Arka planda hesapla (bölge okuma + JTS kesişimleri bloklamasın)
def startCompute = {
    def ctx = gatherContext()
    if (!ctx.ok) {
        step.set(ctx.reason == 'NO_IMAGE' ? 'NO_IMAGE' : 'NO_SELECTION'); render(); return
    }
    ctxRef.set(ctx)
    step.set('COMPUTING'); render()
    String gtL = gtChoice.get()
    String predL = predChoice.get()
    String mode = modeChoice.get()
    double thr = (mode == 'IOU') ? (iouThr.get() as double) : (centThr.get() as double)
    boolean rc = recolor.get()
    def worker = new Thread({
        def res = runValidation(gtL, predL, mode, thr, rc, ctx)
        javafx.application.Platform.runLater {
            if (res.ok) {
                resultRef.set(res)
                if (rc && res.recolored > 0) repaintViewer()
                step.set('RESULT')
            } else {
                errorRef.set(res.reason)
                step.set('ERROR')
            }
            render()
        }
    }, 'AtolyeValidate')
    worker.setDaemon(true); worker.start()
}

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

    def addGuidance = { String txt ->
        def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true)
        center.getChildren().add(lbl)
    }
    def addReportArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: "")
        ta.setEditable(false); ta.setWrapText(false)
        ta.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;")
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(ta)
    }

    if (cur == 'FORM') {
        title.setText('Tespit doğrulama — altın standarda karşı')
        def ctx = gatherContext()
        if (!ctx.ok) {
            if (ctx.reason == 'NO_IMAGE') {
                addGuidance('Önce bir slayt açın, sonra "⟳ Yenile".')
            } else {
                addGuidance(
                    'Önce bir DOĞRULAMA BÖLGESİ seçin.\n\n' +
                    'Yapın:\n' +
                    '  1. Tespit içeren bir slaytta küçük bir bölge anotasyonu çizin\n' +
                    '  2. O bölge içindeki gerçek çekirdekleri ELLE işaretleyin; ayrı bir\n' +
                    '     sınıf verin (ör. "Altın standart"). Nokta aracı ya da küçük poligon.\n' +
                    '  3. Doğrulama bölgesi anotasyonunu SEÇİN\n' +
                    '  4. "⟳ Yenile"')
            }
            actions.add(navButton('⟳ Yenile', { render() }))
            actions.add(navButton('Kapat', { stage.close() }))
        } else {
            def opt = buildOptions(ctx.inPatch)
            if (opt.gtDefault == null) {
                addGuidance('Seçili bölge içinde hiç nesne yok. Önce tespit çalıştırın ve\n' +
                            'referans (altın standart) nesnelerini işaretleyin, sonra "⟳ Yenile".')
                actions.add(navButton('⟳ Yenile', { render() }))
                actions.add(navButton('Kapat', { stage.close() }))
            } else {
                if (gtChoice.get() == null) gtChoice.set(opt.gtDefault)
                if (modeChoice.get() == null) modeChoice.set(opt.modeDefault)
                // ilk açılışta GT alan değilse merkez moduna düş
                if (resultRef.get() == null && modeChoice.get() == 'IOU' && opt.modeDefault == 'CENTROID'
                        && gtChoice.get() == opt.gtDefault) {
                    modeChoice.set('CENTROID')
                }

                addGuidance('Seçili bölge içinde ' + ctx.inPatch.size() + ' nesne bulundu.' +
                    (ctx.multi > 1 ? (' (' + ctx.multi + ' bölge seçiliydi; ilki kullanıldı.)') : ''))

                def grid = new javafx.scene.layout.GridPane()
                grid.setHgap(10); grid.setVgap(10)
                int row = 0

                grid.add(new javafx.scene.control.Label('Altın standart sınıfı:'), 0, row)
                def gtBox = new javafx.scene.control.ComboBox()
                gtBox.getItems().addAll(opt.gtOptions)
                gtBox.setValue(gtChoice.get())
                gtBox.valueProperty().addListener({ obs, o, n -> gtChoice.set(n) } as javafx.beans.value.ChangeListener)
                grid.add(gtBox, 1, row); row++

                grid.add(new javafx.scene.control.Label('Tahmin kümesi:'), 0, row)
                def predBox = new javafx.scene.control.ComboBox()
                predBox.getItems().addAll(opt.predOptions)
                predBox.setValue(opt.predOptions.contains(predChoice.get()) ? predChoice.get() : ALL_DET)
                predBox.valueProperty().addListener({ obs, o, n -> predChoice.set(n) } as javafx.beans.value.ChangeListener)
                grid.add(predBox, 1, row); row++

                grid.add(new javafx.scene.control.Label('Eşleştirme modu:'), 0, row)
                def tg = new javafx.scene.control.ToggleGroup()
                def rIou = new javafx.scene.control.RadioButton('IoU (alan örtüşmesi)')
                def rCen = new javafx.scene.control.RadioButton('Merkez (nokta GT)')
                rIou.setToggleGroup(tg); rCen.setToggleGroup(tg)
                rIou.setSelected(modeChoice.get() == 'IOU')
                rCen.setSelected(modeChoice.get() == 'CENTROID')
                tg.selectedToggleProperty().addListener({ obs, o, n ->
                    modeChoice.set(rIou.isSelected() ? 'IOU' : 'CENTROID'); render()
                } as javafx.beans.value.ChangeListener)
                def modeBox = new javafx.scene.layout.HBox(12, rIou, rCen)
                grid.add(modeBox, 1, row); row++

                grid.add(new javafx.scene.control.Label(
                    modeChoice.get() == 'IOU' ? 'IoU eşiği:' :
                    (ctx.hasMicrons ? 'Merkez eşiği (µm):' : 'Merkez eşiği (px):')), 0, row)
                def sp = new javafx.scene.control.Spinner()
                if (modeChoice.get() == 'IOU') {
                    sp.setValueFactory(new javafx.scene.control.SpinnerValueFactory.DoubleSpinnerValueFactory(
                        0.05d, 0.95d, (iouThr.get() as double), 0.05d))
                    sp.valueProperty().addListener({ obs, o, n -> iouThr.set(n as Double) } as javafx.beans.value.ChangeListener)
                } else {
                    sp.setValueFactory(new javafx.scene.control.SpinnerValueFactory.DoubleSpinnerValueFactory(
                        0.5d, 50.0d, (centThr.get() as double), 0.5d))
                    sp.valueProperty().addListener({ obs, o, n -> centThr.set(n as Double) } as javafx.beans.value.ChangeListener)
                }
                grid.add(sp, 1, row); row++

                def rcChk = new javafx.scene.control.CheckBox('Tahminleri renklendir (TP yeşil / FP kırmızı) — sınıflarını değiştirir')
                rcChk.setSelected(recolor.get())
                rcChk.selectedProperty().addListener({ obs, o, n -> recolor.set(n) } as javafx.beans.value.ChangeListener)
                grid.add(rcChk, 0, row, 2, 1); row++

                center.getChildren().add(grid)
                addGuidance('Nokta (dot) ile işaretlenmiş referans için "Merkez" modunu kullanın;\n' +
                            'küçük poligonlarla işaretlediyseniz "IoU" modu daha katıdır.')

                actions.add(navButton('Kapat', { stage.close() }))
                actions.add(navButton('Hesapla ▶', { startCompute() },
                    'Seçili bölgede altın standart ile tahminleri eşleştirir; F1 hesaplar'))
            }
        }
    } else if (cur == 'COMPUTING') {
        title.setText('Hesaplanıyor…')
        addGuidance('Altın standart ile tahminler eşleştiriliyor. Lütfen bekleyin…')
        center.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        def res = resultRef.get()
        title.setText('Doğrulama sonucu')
        addReportArea(res?.text)
        if (res?.recolored != null && res.recolored > 0) {
            addGuidance('Renklendirildi: ' + res.recolored + ' tahmin TP/FP sınıfına ayrıldı. ' +
                        'Geri almak için tespiti yeniden çalıştırın ya da sınıfları temizleyin.')
        }
        actions.add(navButton('◀ Forma dön', { step.set('FORM'); render() }))
        actions.add(navButton('Kopyala', { copyToClipboard(res?.text) }))
        actions.add(navButton('Kapat', { stage.close() }))
    } else if (cur == 'NO_IMAGE') {
        title.setText('Görüntü açık değil')
        addGuidance('Önce bir slayt açın, sonra "◀ Forma dön".')
        actions.add(navButton('◀ Forma dön', { step.set('FORM'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    } else if (cur == 'NO_SELECTION') {
        title.setText('Doğrulama bölgesi seçili değil')
        addGuidance('Bir bölge anotasyonu çizip SEÇİN, sonra "◀ Forma dön".')
        actions.add(navButton('◀ Forma dön', { step.set('FORM'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    } else { // ERROR
        def reason = errorRef.get()
        title.setText('Doğrulama yapılamadı')
        if (reason == 'IOU_NEEDS_AREA') {
            addGuidance('IoU modu alan (poligon) referans gerektirir, ama altın standart nokta\n' +
                        'olarak işaretlenmiş görünüyor.\n\n"Merkez moduna geç" ile tekrar deneyin.')
            actions.add(navButton('Merkez moduna geç ▶', { modeChoice.set('CENTROID'); step.set('FORM'); render() }))
        } else if (reason == 'SAME') {
            addGuidance('Altın standart ve tahmin aynı topluluk olamaz. Farklı sınıflar seçin\n' +
                        '(ör. GT = "Altın standart", Tahmin = "▸ Tüm tespitler").')
            actions.add(navButton('◀ Forma dön', { step.set('FORM'); render() }))
        } else if (reason == 'NO_GT') {
            addGuidance('Seçilen altın standart sınıfında bölge içinde nesne yok.\n' +
                        'Referansı işaretleyip o sınıfı verdiğinizden emin olun.')
            actions.add(navButton('◀ Forma dön', { step.set('FORM'); render() }))
        } else if (reason == 'NO_PRED') {
            addGuidance('Tahmin (tespit) bulunamadı. Önce bir tespit adımı çalıştırın\n' +
                        '(Modül 2 / 3a / StarDist sihirbazı).')
            actions.add(navButton('◀ Forma dön', { step.set('FORM'); render() }))
        } else {
            addGuidance('Beklenmeyen durum: ' + (reason ?: '(bilinmiyor)'))
            actions.add(navButton('◀ Forma dön', { step.set('FORM'); render() }))
        }
        actions.add(navButton('Kapat', { stage.close() }))
    }

    // Alt çubuk: "Üstte tut" + eylemler + sorumluluk notu
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
    stage.setScene(new javafx.scene.Scene(root, 780, 600))
}

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Tespit doğrulama (F1 / IoU)')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ Tespit doğrulama sihirbazı açıldı (altın standart ↔ tespit; F1 / IoU)."
