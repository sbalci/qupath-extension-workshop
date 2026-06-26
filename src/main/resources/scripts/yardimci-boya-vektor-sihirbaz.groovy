/**
 * Yardımcı - Boya Vektörleri Sihirbazı (tek pencere: kontrol + tahmin)
 * ------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * Renk-dekonvolüsyon (boya) vektörlerini TEK pencerede yönetir; ayrı betik/pencere
 * açmaz. Akış adım adım aynı pencerede yürür:
 *   1. KONTROL — aktif görüntünün mevcut vektörlerini OKUR ve raporlar; bunların
 *      QuPath VARSAYILANI mı yoksa bu slayttan tahmin/kalibre mi edildiğini işaretler.
 *   2. TAHMİN  — SEÇİLİ temsilî bir bölgeden vektörleri yeniden tahmin eder,
 *      eski → yeni karşılaştırmasını (boya başına açısal değişim dahil) gösterir.
 *   3. UYGULA / GERİ AL — onayınızla görüntüye uygular; istenirse geri yükler.
 * (Eski iki ayrı yardımcı — "kontrol et" + "tahmin et" — bu sihirbaza katlandı.)
 *
 * NEDEN ÖNEMLİ:
 *   • Boya vektörleri tarayıcı/laboratuvar/protokole özgüdür. Kantitatif H-DAB
 *     ölçümünde (Modül 3a, 3b, 4, 5, 7) eşikler MUTLAK DAB OD üzerindendir;
 *     yanlış vektör her skoru kaydırır.
 *   • QuPath'in yerleşik [Analyze → Preprocessing → Estimate stain vectors]
 *     komutunun tek-tıkla, sadeleştirilmiş (kontrol → önizle → uygula) karşılığıdır.
 *
 * QUPATH MENÜSÜ — AYRINTILI / MANUEL AYAR:
 *   • Otomatik tahmin (resmî komut): [Analyze → Preprocessing → Estimate stain vectors].
 *   • Tek tek vektörleri ELLE düzenlemek için: Image sekmesinde ilgili boyaya
 *     (Stain 1 / Stain 2) ya da arka plana ÇİFT TIKLAYIN ve değerleri girin.
 *   • Resmî rehber:
 *     https://qupath.readthedocs.io/en/stable/docs/tutorials/separating_stains.html
 *
 * BOYA AYRIMI ≠ BOYA NORMALİZASYONU:
 *   • Ayrım (bu betik): bu slaydı doğru OKUMAK için kanallara kalibre eder.
 *   • Normalizasyon: slaytları birbirine BENZETMEK için yeniden renklendirir
 *     (Macenko/Reinhard/Vahadane — bkz. Ek A).
 *
 * KULLANIM:
 *   1. İHK/H&E slaytını açın; Image type'ı brightfield yapın
 *      (gerekirse: Yardımcılar → Görüntü tipi ayarla)
 *   2. [Extensions → Atölye → Yardımcılar → Boya vektörleri sihirbazı]
 *      → açılışta mevcut vektörleri raporlar.
 *   3. Tahmin için: küçük ve temsilî bir bölge ÇİZİN ve SEÇİN (iki boya + biraz
 *      arka plan), sonra "Seçili bölgeden tahmin et" → eski/yeni inceleyin → Uygula.
 *
 * YÖNTEM REFERANSLARI:
 *   • Ruifrok AC, Johnston DA (2001), Anal Quant Cytol Histol 23(4):291–299 —
 *     renk dekonvolüsyonu.
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.regions.RegionRequest
import qupath.lib.analysis.algorithms.EstimateStainVectors

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// QuPath rehber URL'si — kullanıcıyı ayrıntı için resmî dokümana yönlendirir.
def DOCS_URL = "https://qupath.readthedocs.io/en/stable/docs/tutorials/separating_stains.html"

// ── Tahmin sabitleri (QuPath EstimateStainVectorsCommand referansı, 0.6.0) ──
double MIN_STAIN_OD = 0.05d
double MAX_STAIN_OD = 1.0d
double IGNORE_PCT   = 1.0d
boolean CHECK_COLORS = true            // "Exclude unrecognised colors" (HE) karşılığı
double MAX_PIXELS   = 16_000_000d
double TOL = 0.02d                      // varsayılan-vektör karşılaştırma toleransı

// ── Referans varsayılan vektörler (QuPath; birim/normalize OD) ───────
// Çalışma anında fabrika ile üretmeyi dener; olmazsa bu tabloya düşer.
double[] DEF_HEMA  = [0.651d, 0.701d, 0.290d] as double[]
double[] DEF_EOSIN = [0.216d, 0.801d, 0.558d] as double[]
double[] DEF_DAB   = [0.269d, 0.568d, 0.776d] as double[]

def vecOf = { stain ->
    [stain.getRed() as double, stain.getGreen() as double, stain.getBlue() as double] as double[]
}
def fmtVec = { double[] v ->
    String.format(java.util.Locale.US, "r=%.4f  g=%.4f  b=%.4f", v[0], v[1], v[2])
}
def vecClose = { double[] a, double[] b ->
    (Math.abs(a[0] - b[0]) <= TOL) && (Math.abs(a[1] - b[1]) <= TOL) && (Math.abs(a[2] - b[2]) <= TOL)
}
def angleDeg = { double[] a, double[] b ->
    double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    dot = Math.max(-1.0d, Math.min(1.0d, dot))
    Math.toDegrees(Math.acos(dot))
}

// Fabrika varsayılanlarını dene (sürüm sürmez); başarısız olursa tablo kalır.
try {
    def cds = Class.forName("qupath.lib.color.ColorDeconvolutionStains")
    def enumCls = Class.forName("qupath.lib.color.ColorDeconvolutionStains\$DefaultColorDeconvolutionStains")
    def makeDefault = { String enumName ->
        def enumVal = Enum.valueOf(enumCls, enumName)
        cds.getMethod("makeDefaultColorDeconvolutionStains", enumCls).invoke(null, enumVal)
    }
    def heDef = makeDefault("H_E")
    def dabDef = makeDefault("H_DAB")
    DEF_HEMA  = vecOf(heDef.getStain(1))
    DEF_EOSIN = vecOf(heDef.getStain(2))
    DEF_DAB   = vecOf(dabDef.getStain(2))
} catch (Throwable ignore) {
    // tablo değerleri kullanılır
}

// ── KONTROL: mevcut vektörleri oku ve raporla (görüntüyü değiştirmez) ──
// Dönüş: [state:'REPORT', text] | [state:'NO_IMAGE'] | [state:'NO_STAINS']
def buildReport = { ->
    def imageData = QP.getCurrentImageData()
    if (imageData == null) return [state: 'NO_IMAGE']
    def stains = imageData.getColorDeconvolutionStains()
    if (stains == null) return [state: 'NO_STAINS']

    def s1 = stains.getStain(1)
    def s2 = stains.getStain(2)
    def s3 = stains.getStain(3)

    def n2 = (s2?.getName() ?: "").toLowerCase(java.util.Locale.ROOT)
    boolean isHE   = n2.contains("eosin")
    boolean isHDAB = n2.contains("dab")

    double[] v1 = vecOf(s1)
    double[] v2 = vecOf(s2)

    String verdict
    boolean unknownType = false
    if (isHE) {
        boolean def1 = vecClose(v1, DEF_HEMA)
        boolean def2 = vecClose(v2, DEF_EOSIN)
        verdict = (def1 && def2)
            ? "⚠ Vektörler QuPath H&E VARSAYILANI ile aynı — bu slayttan tahmin EDİLMEMİŞ olabilir."
            : "✓ Vektörler varsayılandan farklı — muhtemelen kalibre/tahmin edilmiş."
    } else if (isHDAB) {
        boolean def1 = vecClose(v1, DEF_HEMA)
        boolean def2 = vecClose(v2, DEF_DAB)
        verdict = (def1 && def2)
            ? "⚠ Vektörler QuPath H-DAB VARSAYILANI ile aynı — bu slayttan tahmin EDİLMEMİŞ olabilir."
            : "✓ Vektörler varsayılandan farklı — muhtemelen kalibre/tahmin edilmiş."
    } else {
        unknownType = true
        verdict = "ℹ Boya tipi otomatik tanınamadı (H&E/H-DAB değil). Vektörleri elle doğrulayın."
    }

    def body = new StringBuilder()
    body << "BOYA (RENK-DEKONVOLÜSYON) VEKTÖRLERİ\n"
    body << "═════════════════════════════════════\n\n"
    body << "Set adı       : " << (stains.getName() ?: "(adsız)") << "\n"
    body << "Boya tipi     : " << (isHE ? "H&E" : (isHDAB ? "H-DAB" : "özel/bilinmiyor")) << "\n\n"
    body << String.format(java.util.Locale.US, "Stain 1  %-14s %s%n", (s1?.getName() ?: "?"), fmtVec(v1))
    body << String.format(java.util.Locale.US, "Stain 2  %-14s %s%n", (s2?.getName() ?: "?"), fmtVec(v2))
    if (s3 != null) {
        String s3label = s3.isResidual() ? (s3.getName() + " (residual)") : s3.getName()
        body << String.format(java.util.Locale.US, "Stain 3  %-14s %s%n", s3label, fmtVec(vecOf(s3)))
    }
    body << String.format(java.util.Locale.US, "%nArka plan (maks RGB) : %.1f, %.1f, %.1f%n",
            stains.getMaxRed() as double, stains.getMaxGreen() as double, stains.getMaxBlue() as double)
    body << "\n"
    body << verdict << "\n\n"
    if (!unknownType) {
        body << "Karşılaştırma (varsayılan, ±" << String.format(java.util.Locale.US, "%.2f", TOL) << "):\n"
        body << String.format(java.util.Locale.US, "  Hematoxylin varsayılan : %s%n", fmtVec(DEF_HEMA))
        body << String.format(java.util.Locale.US, "  %-22s : %s%n",
                (isHE ? "Eosin varsayılan" : "DAB varsayılan"), fmtVec(isHE ? DEF_EOSIN : DEF_DAB))
        body << "\n"
    }
    body << "Renk dekonvolüsyonu — bilinen sınırlar (Bankhead):\n"
    body << "  • Yöntem 'imkânsız' sonuç verebilir: kusurlu görüntü ya da yanlış vektörde\n"
    body << "    bir boya NEGATİF değer alabilir — kantitatif eşiklerde bunu hesaba katın.\n"
    body << "  • Kuvvetli DAB-pozitif (kahverengi) pikseller genelde YÜKSEK hematoksilen\n"
    body << "    değeri de taşır; H kanalı pozitif alanlarda 'saf çekirdek' değildir.\n\n"
    body << "Öneri: kantitatif H-DAB ölçümünde boya vektörlerini HER SLAYTTAN (ya da aynı\n"
    body << "boyama/tarayıcı ile doğrulanmış bir grup için bir kez) tahmin edin.\n\n"
    body << "Tahmin için: küçük ve temsilî bir bölge (iki boya + biraz arka plan) çizip seçin,\n"
    body << "sonra aşağıdan \"Seçili bölgeden tahmin et\".\n\n"
    body << "QuPath menüsü (ayrıntı/manuel):\n"
    body << "  • Otomatik: Analyze → Preprocessing → Estimate stain vectors\n"
    body << "  • Elle: Image sekmesinde boyaya (Stain 1/2) ya da arka plana ÇİFT TIKLAYIN\n"
    body << "  • Resmî rehber: " << DOCS_URL << "\n\n"
    body << "Boya normalizasyonu (slaytları birbirine benzetme) ayrı bir adımdır — bkz. Ek A.\n\n"
    body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return [state: 'REPORT', text: body.toString()]
}

// ── TAHMİN: seçili bölgeden yeni vektörleri hesapla (henüz UYGULAMAZ) ──
// Dönüş: [ok:true, oldStains, newStains, text]
//      | [ok:false, reason:'NO_IMAGE'|'NO_STAINS'|'NO_REGION'|'FAIL', error]
def estimateFromSelection = { ->
    def imageData = QP.getCurrentImageData()
    if (imageData == null) return [ok: false, reason: 'NO_IMAGE']
    def oldStains = imageData.getColorDeconvolutionStains()
    if (oldStains == null) return [ok: false, reason: 'NO_STAINS']

    def selected = QP.getSelectedObjects().findAll { it.hasROI() && it.getROI().isArea() }
    if (selected.isEmpty()) return [ok: false, reason: 'NO_REGION']
    def roi = selected[0].getROI()
    def multiNote = selected.size() > 1 ?
        ("\n(Not: " + selected.size() + " nesne seçiliydi; ilk alan bölgesi kullanıldı.)") : ""

    def server = imageData.getServer()
    double regionPixels = (roi.getBoundsWidth() as double) * (roi.getBoundsHeight() as double)
    double downsample = Math.max(1.0d, Math.sqrt(regionPixels / MAX_PIXELS))
    def request = RegionRequest.createInstance(server.getPath(), downsample, roi)
    def img
    try {
        img = server.readRegion(request)
    } catch (Throwable t) {
        return [ok: false, reason: 'FAIL', error: "Seçili bölge okunamadı: " + (t.getMessage() ?: t.getClass().getSimpleName())]
    }
    if (img == null) return [ok: false, reason: 'FAIL', error: "Seçili bölgeden görüntü alınamadı (boş bölge?)."]

    def newStains
    try {
        newStains = EstimateStainVectors.estimateStains(
            img, oldStains, MIN_STAIN_OD, MAX_STAIN_OD, IGNORE_PCT, CHECK_COLORS)
    } catch (Throwable t) {
        return [ok: false, reason: 'FAIL', error: (t.getMessage() ?: t.getClass().getSimpleName())]
    }

    double[] os1 = vecOf(oldStains.getStain(1)); double[] ns1 = vecOf(newStains.getStain(1))
    double[] os2 = vecOf(oldStains.getStain(2)); double[] ns2 = vecOf(newStains.getStain(2))
    double d1 = angleDeg(os1, ns1)
    double d2 = angleDeg(os2, ns2)

    def report = new StringBuilder()
    report << "BOYA VEKTÖRÜ TAHMİNİ — ESKİ → YENİ\n"
    report << "═══════════════════════════════════════\n\n"
    report << "Set adı : " << (oldStains.getName() ?: "(adsız)") << multiNote << "\n"
    report << String.format(java.util.Locale.US, "Örnekleme downsample : %.2f%n%n", downsample)
    report << (oldStains.getStain(1).getName() ?: "Stain 1") << "\n"
    report << "  eski : " << fmtVec(os1) << "\n"
    report << "  yeni : " << fmtVec(ns1) << "\n"
    report << String.format(java.util.Locale.US, "  açısal değişim : %.2f derece%n%n", d1)
    report << (oldStains.getStain(2).getName() ?: "Stain 2") << "\n"
    report << "  eski : " << fmtVec(os2) << "\n"
    report << "  yeni : " << fmtVec(ns2) << "\n"
    report << String.format(java.util.Locale.US, "  açısal değişim : %.2f derece%n%n", d2)
    report << String.format(java.util.Locale.US,
            "Arka plan (maks RGB) eski : %.1f, %.1f, %.1f%n",
            oldStains.getMaxRed() as double, oldStains.getMaxGreen() as double, oldStains.getMaxBlue() as double)
    report << String.format(java.util.Locale.US,
            "Arka plan (maks RGB) yeni : %.1f, %.1f, %.1f%n",
            newStains.getMaxRed() as double, newStains.getMaxGreen() as double, newStains.getMaxBlue() as double)
    return [ok: true, oldStains: oldStains, newStains: newStains, text: report.toString()]
}

// ── Headless: yalnız raporla, UYGULAMA ──────────────────────────────
if (isHeadless) {
    def r = buildReport()
    if (r.state == 'NO_IMAGE') { println "Önce bir slayt açın."; return }
    if (r.state == 'NO_STAINS') {
        println "Bu görüntüde renk-dekonvolüsyon (boya) vektörleri tanımlı değil " +
                "(görüntü tipi brightfield mi?). Görüntü tipi ayarla yardımcısına bakın."
        return
    }
    println "=== Boya vektörleri (kontrol) ===\n" + r.text
    def est = estimateFromSelection()
    if (est.ok) {
        println "\n=== Boya vektörü tahmini — ESKİ → YENİ ===\n" + est.text
        println "\n(Headless mod: vektörler UYGULANMADI. Uygulamak için QuPath arayüzünde çalıştırın.)"
    } else if (est.reason == 'NO_REGION') {
        println "\n(Tahmin için temsilî bir alan anotasyonu seçili olmalı — atlandı.)"
    } else {
        println "\n(Tahmin yapılamadı: ${est.error ?: est.reason})"
    }
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Tek pencere, adım adım render (modul-06-sihirbaz kalıbı) ────────
// Stage/Scene YALNIZ FX uygulama iş parçacığında oluşturulabilir; betik arka
// planda çalıştığından stage aşağıdaki Platform.runLater içinde oluşturulur.
def stage = null

// REPORT | ESTIMATING | PREVIEW | APPLIED | NO_REGION | ERROR
def step = new java.util.concurrent.atomic.AtomicReference('REPORT')
def estRef = new java.util.concurrent.atomic.AtomicReference(null)        // estimateFromSelection sonucu
def appliedRef = new java.util.concurrent.atomic.AtomicReference(null)    // [oldStains, newStains] (geri al için)
def alwaysTop = new java.util.concurrent.atomic.AtomicBoolean(true)
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

// ── Arka planda tahmin (bölge okuma + EstimateStainVectors yavaş olabilir) ──
def startEstimate = {
    step.set('ESTIMATING'); render()
    def worker = new Thread({
        def est = estimateFromSelection()
        javafx.application.Platform.runLater {
            estRef.set(est)
            if (est.ok) step.set('PREVIEW')
            else if (est.reason == 'NO_REGION') step.set('NO_REGION')
            else if (est.reason == 'NO_IMAGE' || est.reason == 'NO_STAINS') step.set('REPORT')
            else step.set('ERROR')
            render()
        }
    }, 'AtolyeStainEstimate')
    worker.setDaemon(true); worker.start()
}

def applyVectors = {
    def est = estRef.get()
    if (est == null || !est.ok) return
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { step.set('REPORT'); render(); return }
    imageData.setColorDeconvolutionStains(est.newStains.changeName("Bölgeden tahmin (Atölye)"))
    appliedRef.set([oldStains: est.oldStains, newStains: est.newStains])
    repaintViewer()
    step.set('APPLIED'); render()
}

def revertVectors = {
    def ap = appliedRef.get()
    def imageData = QP.getCurrentImageData()
    if (ap != null && imageData != null) {
        imageData.setColorDeconvolutionStains(ap.oldStains)
        repaintViewer()
    }
    step.set('REPORT'); render()
}

render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())   // her render'da üstte-kal yeniden uygulanır
    def cur = step.get()

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10)
    center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)
    def actions = new ArrayList()

    def addReportArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: "")
        ta.setEditable(false); ta.setWrapText(false)
        ta.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;")
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(ta)
    }
    def addGuidance = { String txt ->
        def lbl = new javafx.scene.control.Label(txt)
        lbl.setWrapText(true)
        center.getChildren().add(lbl)
    }

    if (cur == 'REPORT') {
        def r = buildReport()
        if (r.state == 'NO_IMAGE') {
            title.setText('Görüntü açık değil')
            addGuidance('Önce bir slayt açın, sonra "⟳ Yenile".')
            actions.add(navButton('⟳ Yenile', { render() }))
            actions.add(navButton('Kapat', { stage.close() }))
        } else if (r.state == 'NO_STAINS') {
            title.setText('Boya vektörü tanımlı değil')
            addGuidance(
                'Bu görüntüde renk-dekonvolüsyon (boya) vektörleri yok.\n\n' +
                'Olası neden: görüntü tipi brightfield değil (örn. floresan/diğer).\n\n' +
                'Çözüm:\n' +
                '  • [Extensions → Atölye → Yardımcılar → Görüntü tipi ayarla] ile tipi\n' +
                '    Brightfield (H&E) ya da Brightfield (H-DAB) yapın.\n' +
                '  • QuPath\'in kendi komutu: Analyze → Preprocessing → Estimate stain vectors\n' +
                '  • Ayrıntı: ' + DOCS_URL + '\n\n' +
                'Sonra "⟳ Yenile".')
            actions.add(navButton('⟳ Yenile', { render() }))
            actions.add(navButton('Kapat', { stage.close() }))
        } else {
            title.setText('Boya vektörleri — kontrol')
            addReportArea(r.text)
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Kopyala', { copyToClipboard(r.text) }))
            actions.add(navButton('Seçili bölgeden tahmin et ▶', { startEstimate() },
                'Küçük, temsilî bir seçili bölgeden (iki boya + biraz arka plan) vektörleri yeniden tahmin eder'))
        }
    } else if (cur == 'ESTIMATING') {
        title.setText('Tahmin hesaplanıyor…')
        addGuidance('Seçili bölgenin pikselleri üzerinden boya vektörleri tahmin ediliyor. Lütfen bekleyin…')
        center.getChildren().add(busyBar())
    } else if (cur == 'PREVIEW') {
        def est = estRef.get()
        title.setText('Boya vektörleri — tahmin (eski → yeni)')
        addReportArea(est?.text)
        addGuidance('Yeni vektörleri inceleyin. Uygunsa [Uygula] (geri alınabilir); ' +
            'değilse [◀ Rapora dön] — görüntü değişmez.')
        actions.add(navButton('◀ Rapora dön', { step.set('REPORT'); render() }))
        actions.add(navButton('↻ Yeniden tahmin', { startEstimate() }, 'Seçimi yeniden okuyup tekrar tahmin eder'))
        actions.add(navButton('Kopyala', { copyToClipboard(est?.text) }))
        actions.add(navButton('Uygula ▶', { applyVectors() }, 'Yeni boya vektörlerini görüntüye uygular (geri alınabilir)'))
    } else if (cur == 'APPLIED') {
        def ap = appliedRef.get()
        def sb = new StringBuilder()
        sb << "✓ Yeni boya vektörleri uygulandı (ad: \"Bölgeden tahmin (Atölye)\").\n\n"
        if (ap?.newStains != null) {
            def ns = ap.newStains
            sb << String.format(java.util.Locale.US, "%-12s : %s%n", (ns.getStain(1).getName() ?: "Stain 1"), fmtVec(vecOf(ns.getStain(1))))
            sb << String.format(java.util.Locale.US, "%-12s : %s%n", (ns.getStain(2).getName() ?: "Stain 2"), fmtVec(vecOf(ns.getStain(2))))
        }
        sb << "\nGörüntü bu vektörlerle yeniden çizildi. Beğenmediyseniz [↩ Geri al].\n"
        sb << "İnce ayar için: Image sekmesinde ilgili boyaya çift tıklayın."
        title.setText('Uygulandı ✅')
        addReportArea(sb.toString())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(sb.toString()) }))
        actions.add(navButton('↩ Geri al', { revertVectors() }, 'Önceki boya vektörlerini geri yükler'))
    } else if (cur == 'NO_REGION') {
        title.setText('Önce bir bölge seçin')
        addGuidance(
            'Tahmin için temsilî bir BÖLGE seçili olmalı.\n\n' +
            'Yapın:\n' +
            '  1. Dikdörtgen/çokgen anotasyon aracı ile iki boyayı + biraz arka planı\n' +
            '     içeren KÜÇÜK bir alan çizin (büyük bölge gereksiz yere örneklemeyi düşürür)\n' +
            '  2. O anotasyonu seçili bırakın (üzerine tıklayın)\n' +
            '  3. Aşağıdan "↻ Tekrar dene"\n\n' +
            'QuPath tahmini bu bölgenin pikselleri üzerinden yapar.\n' +
            '(Resmî komut: Analyze → Preprocessing → Estimate stain vectors · ' + DOCS_URL + ')')
        actions.add(navButton('◀ Rapora dön', { step.set('REPORT'); render() }))
        actions.add(navButton('↻ Tekrar dene', { startEstimate() }))
    } else { // ERROR
        def est = estRef.get()
        title.setText('Tahmin yapılamadı')
        addGuidance(
            (est?.error ?: 'Bilinmeyen hata.').toString() + '\n\n' +
            'Olası neden: seçili bölge iki boyayı + biraz arka planı birlikte içermiyor\n' +
            'olabilir (QuPath renk denetimini geçemedi). Daha temsilî, küçük bir alan seçip\n' +
            'tekrar deneyin.\n\n' +
            '(Resmî komut: Analyze → Preprocessing → Estimate stain vectors · ' + DOCS_URL + ')')
        actions.add(navButton('◀ Rapora dön', { step.set('REPORT'); render() }))
        actions.add(navButton('↻ Tekrar dene', { startEstimate() }))
    }

    // Alt çubuk: "Üstte tut" (sol) + eylem düğmeleri (sağ)
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
    stage.setScene(new javafx.scene.Scene(root, 760, 560))
}

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Boya vektörleri sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ Boya vektörleri sihirbazı açıldı (kontrol + tahmin tek pencerede)."
