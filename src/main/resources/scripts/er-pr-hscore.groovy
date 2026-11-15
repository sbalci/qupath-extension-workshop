/**
 * Tek Tıkla ER / PR Nükleer H-score
 * ----------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Atölye için "hızlı deneme" betiği. Seçilen anotasyon içinde
 * ER / PR (östrojen / progesteron reseptörü) gibi nükleer hormon
 * reseptörlerini skorlar ve **H-score**'u (0–300) kaydeder.
 *
 * KULLANIM:
 *   1. ER veya PR İHK lamını açın
 *   2. Image type → "Brightfield (H-DAB)"
 *   3. [R] tuşu → tümör içeren ~1×1 mm dikdörtgen anotasyon çizin ve SEÇİN
 *   4. Bu betiği çalıştırın → açılan TEK pencerede "Çalıştır"
 *   5. Pencere açık kalır: eşikleri değiştirip "Yeniden say (hızlı)" ile
 *      hücre tespitini TEKRAR ÇALIŞTIRMADAN saniyeler içinde yeniden sınıflandırın.
 *      "Öğretim modu" açıkken varsayılan ↔ sizin eşik karşılaştırması da gösterilir.
 *
 * NE YAPAR:
 *   • Çekirdek tespiti + DAB OD'ye göre Negative / 1+ / 2+ / 3+ sınıflama
 *   • H-score = (1 × %1+) + (2 × %2+) + (3 × %3+)        [0–300]
 *   • Pozitif yüzdesi (≥1+ olan her çekirdek)
 *   • Hücre yoğunluğu (hücre/mm²)
 *
 * NE YAPMAZ:
 *   • Allred skoru hesaplamaz (oran puanı 0–5 + yoğunluk puanı 0–3, toplam 0–8 — manuel)
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathAnnotationObject

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
// Hücreleri değiştirme sorusu: varsayılan seçim "dokunma"dır — Enter/Tamam hiçbir şeyi değiştirmez.
// (Evet/Hayır penceresinde Enter "Evet" anlamına gelir; bu yüzden seçim penceresi kullanılır.)
def confirmReplaceCells = { String title, String body ->
    String keepOpt = workshopText('script.665d4074c4fe', 'Mevcut hücrelere dokunma — tespiti çalıştırma (varsayılan)')
    String replaceOpt = workshopText('script.6b4af35c53fe', 'Mevcut hücreleri değiştir — tespiti çalıştır')
    def ask = { -> qupath.fx.dialogs.Dialogs.showChoiceDialog(title, body, [keepOpt, replaceOpt], keepOpt) }
    if (javafx.application.Platform.isFxApplicationThread()) return ask() == replaceOpt
    def picked = new java.util.concurrent.atomic.AtomicReference(null)
    def latch = new java.util.concurrent.CountDownLatch(1)
    javafx.application.Platform.runLater {
        try { picked.set(ask()) } finally { latch.countDown() }
    }
    latch.await()
    return picked.get() == replaceOpt
}
// --- Atölye ayarları: eklenti yüklüyse oku, yoksa atölye varsayılanı kullanılır ---
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeD = { String k, double  d -> (double)  __wpCall('dbl',  [String.class, double.class]  as Class[], [k, d] as Object[], d) }
def atolyeS = { String k, String  d -> (String)  __wpCall('str',  [String.class, String.class]  as Class[], [k, d] as Object[], d) }
def atolyeI = { String k, int     d -> (int)     __wpCall('intg', [String.class, int.class]     as Class[], [k, d] as Object[], d) }
def atolyeB = { String k, boolean d -> (boolean) __wpCall('bool', [String.class, boolean.class] as Class[], [k, d] as Object[], d) }

// ──────────────────────────────────────────────────────────────
// 1) Ön kontroller
// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(
        workshopText('script.b4c901dc184b', "Görüntü açık değil"),
        workshopText('script.11c16d5465a5', "Önce bir ER veya PR İHK lamı açın.")
    )
    return
}

// Image type kontrolü (Brightfield (H-DAB) zorunlu — DAB ayrımı için).
// Modal pencere yerine: aşağıdaki pencere her zaman açılır; tip yanlışsa kullanıcı
// pencere içinde yönlendirilir, Image panelinden tipi düzeltip "Çalıştır"a tekrar
// basabilir (her çalıştırmada yeniden denetlenir — pencere kapanmaz).
def imageTypeOk = { ->
    def n = (imageData.getImageType()?.toString() ?: "")
        .toUpperCase(java.util.Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
    n.contains('H_DAB')
}
def imageTypeGuidance = { ->
    "Bu lam 'Brightfield (H-DAB)' olarak ayarlı değil.\n" +
    "Şu anki tip: ${imageData.getImageType()?.toString() ?: ''}\n\n" +
    "Çözüm:\n" +
    "  • Penceredeki \"Görüntü tipini 'Brightfield (H-DAB)' olarak ayarla\" düğmesine tıklayın, VEYA\n" +
    "  1. Image panelini açın (sol-üst)\n" +
    "  2. 'Image type' → 'Brightfield (H-DAB)' seçin (DAB ayrımı için gerekli)\n" +
    "  3. Yukarıdaki 'Çalıştır' düğmesine tekrar basın"
}

// Evet/Hayır sorusu — betik FX iş parçacığında da, betik iş parçacığında da çalışabilir.
def askYesNo = { String title, String body ->
    if (javafx.application.Platform.isFxApplicationThread()) return Dialogs.showConfirmDialog(title, body)
    def answer = new java.util.concurrent.atomic.AtomicBoolean(false)
    def latch = new java.util.concurrent.CountDownLatch(1)
    javafx.application.Platform.runLater {
        try { answer.set(Dialogs.showConfirmDialog(title, body)) } finally { latch.countDown() }
    }
    latch.await()
    return answer.get()
}
// Başsız kipte soru sorulamaz: açık bağımsız değişken gerekir (ör. -a boya=varsayilan).
def headlessArg = { String key ->
    def list = []
    try { if (binding.hasVariable('args')) { def a = binding.getVariable('args'); if (a != null) list = (a as List) } } catch (Throwable ignore) {}
    def found = null
    list.each { a -> def s = a?.toString() ?: ''; int i = s.indexOf('='); if (i > 0 && s.substring(0, i).trim() == key) found = s.substring(i + 1).trim().toLowerCase(java.util.Locale.ROOT) }
    return found
}

// H-DAB ise ve "Hematoxylin" adlı boya yoksa atölye varsayılanı GEREKİR — ama varsayılanı
// uygulamak lamın MEVCUT boya vektörlerini değiştirir. Bu yüzden SORULUR; sessizce yapılmaz.
// (Yanlış tipteki lamları otomatik dönüştürmeyiz — yönlendirme pencerede gösterilir.)
if (imageTypeOk()) {
    def stains = imageData.getColorDeconvolutionStains()
    def hasHematoxylin = false
    if (stains != null) {
        for (int i = 1; i <= 3; i++) {
            def name = stains.getStain(i)?.getName()?.toLowerCase(java.util.Locale.ROOT)
            if (name != null && name.contains("hematoxylin")) { hasHematoxylin = true; break }
        }
    }
    if (!hasHematoxylin) {
        def stainNames = []
        if (stains != null) { for (int i = 1; i <= 3; i++) { def nm = stains.getStain(i)?.getName(); if (nm) stainNames << nm } }
        String stainList = stainNames.isEmpty() ? workshopText('script.edd17dfa3e74', '(tanımlı boya yok)') : stainNames.join(', ')
        if (isHeadless) {
            if (headlessArg('boya') != 'varsayilan') {
                println String.format(java.util.Locale.US, workshopText('script.e7502113ef47', 'DURDU: görüntü tipi H-DAB ama "Hematoxylin" adlı boya yok (tanımlı boyalar: %s). Hiçbir şey değiştirilmedi. Varsayılan H-DAB boya vektörlerinin uygulanmasını (mevcut vektörlerin değişmesini) kabul ediyorsanız -a boya=varsayilan ile yeniden çalıştırın.'), stainList)
                return
            }
        } else if (!askYesNo(workshopText('script.9d995633289c', 'Boya vektörleri değişecek'), String.format(java.util.Locale.US, workshopText('script.fcd4e17529fd', 'Bu lamın görüntü tipi Brightfield (H-DAB), ancak "Hematoxylin" adlı bir boya tanımlı değil.\nTanımlı boyalar: %s\n\nBu araç Hematoxylin ve DAB kanallarına ihtiyaç duyar. Devam etmek için QuPath\'in varsayılan H-DAB boya vektörleri uygulanmalıdır. Bu işlem lamın MEVCUT boya vektörlerini DEĞİŞTİRİR; kendiniz tahmin ettiğiniz vektörler varsa kaybolur.\n\nVarsayılan H-DAB boya vektörleri uygulansın mı?\n\n"Hayır" derseniz hiçbir şey değiştirilmez ve araç açılmaz.'), stainList))) {
            Dialogs.showMessageDialog(workshopText('script.9d995633289c', 'Boya vektörleri değişecek'), workshopText('script.e9d070cff4f1', 'Hiçbir şey değiştirilmedi. Aracı kullanmak için boya vektörlerini kendiniz ayarlayın: Extensions → Atölye → Yardımcılar → Boya ve renk → Boya vektörleri sihirbazı. Boyalardan birinin adı "Hematoxylin" olmalıdır. Sonra bu aracı yeniden çalıştırın.'))
            return
        }
        println "⚠ H-DAB boya vektörleri: kullanıcı onayıyla BRIGHTFIELD_H_DAB varsayılanı uygulanıyor."
        QP.setImageType('BRIGHTFIELD_H_DAB')
    }
}

// ──────────────────────────────────────────────────────────────
// Boya vektörü önerisi + tek tıklamalı tahmin (tüm DAB-OD modüllerinde aynı blok).
//   • Aktif vektörler QuPath VARSAYILANI ise uyarır: kantitatif ölçümden önce bu
//     lamdan tahmin etmeyi önerir (eşikler mutlak DAB OD; yanlış vektör skoru kaydırır).
//   • "Tamam" → SEÇİLİ alan anotasyonundan tahmin edip uygular (Boya vektörleri
//     sihirbazı ile aynı yöntem). "İptal" → yine de devam. Kalibre görünüyorsa sessiz geçer.
// ──────────────────────────────────────────────────────────────
double SV_TOL = 0.02d
double[] SV_DEF_HEMA = [0.651d, 0.701d, 0.290d] as double[]
double[] SV_DEF_DAB  = [0.269d, 0.568d, 0.776d] as double[]
try {
    def svEnum = Class.forName('qupath.lib.color.ColorDeconvolutionStains$DefaultColorDeconvolutionStains')
    def svDab = Class.forName('qupath.lib.color.ColorDeconvolutionStains')
                     .getMethod('makeDefaultColorDeconvolutionStains', svEnum)
                     .invoke(null, Enum.valueOf(svEnum, 'H_DAB'))
    SV_DEF_HEMA = [svDab.getStain(1).getRed(), svDab.getStain(1).getGreen(), svDab.getStain(1).getBlue()] as double[]
    SV_DEF_DAB  = [svDab.getStain(2).getRed(), svDab.getStain(2).getGreen(), svDab.getStain(2).getBlue()] as double[]
} catch (Throwable svIgnore) { /* tablo değerleri kullanılır */ }
def svVecOf = { s -> [s.getRed() as double, s.getGreen() as double, s.getBlue() as double] as double[] }
def svClose = { double[] a, double[] b ->
    Math.abs(a[0] - b[0]) <= SV_TOL && Math.abs(a[1] - b[1]) <= SV_TOL && Math.abs(a[2] - b[2]) <= SV_TOL
}
def suggestStainVectors = { ->
    def svId = QP.getCurrentImageData()
    if (svId == null) return
    def svStains = svId.getColorDeconvolutionStains()
    if (svStains == null) return
    def svS2 = (svStains.getStain(2)?.getName() ?: '').toLowerCase(java.util.Locale.ROOT)
    if (!svS2.contains('dab')) return
    boolean svDefault = svClose(svVecOf(svStains.getStain(1)), SV_DEF_HEMA) &&
                        svClose(svVecOf(svStains.getStain(2)), SV_DEF_DAB)
    if (!svDefault) { println '✓ Boya vektörleri bu lamdan tahmin/kalibre edilmiş görünüyor.'; return }
    def svMsg =
        '⚠ Boya vektörleri QuPath VARSAYILANI görünüyor — bu lamdan tahmin EDİLMEMİŞ.\n\n' +
        'Eşikler mutlak DAB OD üzerinden çalışır; doğru vektör olmadan skorlar kayar.\n' +
        'Tarayıcı/boyama partisi başına bir kez tahmin yeterlidir.\n\n' +
        'Şimdi SEÇİLİ alandan tahmin edilsin mi?\n' +
        '(İki boya + biraz arka plan içeren temsilî bir alan anotasyonu seçili olmalı.)\n\n' +
        '[Tamam] = tahmin et ve uygula      [İptal] = yine de devam et'
    if (isHeadless) { println svMsg; return }
    if (!qupath.fx.dialogs.Dialogs.showConfirmDialog(workshopText('script.cb5b26abe800', 'Boya vektörleri — öneri'), svMsg)) return
    def svSel = QP.getSelectedObjects().findAll { it.hasROI() && it.getROI().isArea() }
    if (svSel.isEmpty()) {
        qupath.fx.dialogs.Dialogs.showWarningNotification(workshopText('script.c1cc09de3ac8', 'Boya vektörleri'),
            workshopText('script.525b4898d046', 'Önce iki boya + biraz arka plan içeren temsilî bir alan anotasyonu çizip SEÇİN, sonra tekrar deneyin.'))
        return
    }
    try {
        def svRoi = svSel[0].getROI()
        def svServer = svId.getServer()
        double svPix = (svRoi.getBoundsWidth() as double) * (svRoi.getBoundsHeight() as double)
        double svDown = Math.max(1.0d, Math.sqrt(svPix / 16_000_000d))
        def svImg = svServer.readRegion(
            qupath.lib.regions.RegionRequest.createInstance(svServer.getPath(), svDown, svRoi))
        def svOld = svId.getColorDeconvolutionStains()
        def svNew = qupath.lib.analysis.algorithms.EstimateStainVectors.estimateStains(
            svImg, svOld, 0.05d, 1.0d, 1.0d, true)
        svId.setColorDeconvolutionStains(svNew.changeName('Bölgeden tahmin (Atölye)'))
        javafx.application.Platform.runLater {
            try { qupath.lib.gui.QuPathGUI.getInstance()?.getViewer()?.repaintEntireImage() } catch (Throwable svR) { }
        }
        qupath.fx.dialogs.Dialogs.showMessageDialog(workshopText('script.c1cc09de3ac8', 'Boya vektörleri'),
            workshopText('script.4e0fe0741a03', 'Vektörler seçili bölgeden tahmin edilip uygulandı. Modülü şimdi çalıştırabilirsiniz.'))
    } catch (Throwable svErr) {
        qupath.fx.dialogs.Dialogs.showMessageDialog(workshopText('script.c1cc09de3ac8', 'Boya vektörleri'),
            workshopText('script.c0b06389df5e', 'Tahmin yapılamadı: ') + (svErr.getMessage() ?: svErr.getClass().getSimpleName()))
    }
}
if (QP.getSelectedObject() instanceof PathAnnotationObject && QP.getSelectedObject().getROI()?.isArea()) {
    suggestStainVectors()
}

// ──────────────────────────────────────────────────────────────
// 2) Ölçüm + sonuç oluşturma — paylaşılan yardımcılar.
//    Hücreler "Nucleus: DAB OD mean" sütununda sınıflandırılır. Eşik değişince hücre
//    tespitini yeniden çalıştırmak yerine sadece bu sütun üzerinden yeniden
//    sınıflandırırız (setIntensityClassifications). "recount" bunu kullanır.
// ──────────────────────────────────────────────────────────────
def COMPARTMENT = 'Nucleus: DAB OD mean'
def DEF1 = atolyeD('atolye.nuclear1', 0.20)
def DEF2 = atolyeD('atolye.nuclear2', 0.40)
def DEF3 = atolyeD('atolye.nuclear3', 0.60)
def warnGenericCount = atolyeI('atolye.warnGenericCount', 200)

def resolveTarget = { ->
    if (!imageTypeOk())
        return [ok:false, error: imageTypeGuidance(), imageTypeIssue:true]
    def selected = QP.getSelectedObject()
    if (selected == null || !(selected instanceof PathAnnotationObject))
        return [ok:false, error:'Önce ölçmek istediğiniz alan anotasyonunu seçin (üst araç çubuğundan Dikdörtgen [R], Çokgen [P] veya Fırça [B] ile çizip üzerine tıklayın; seçili anotasyonun kenarı SARI görünür).']
    def cal = imageData.getServer().getPixelCalibration()
    def pw = cal.getPixelWidthMicrons()
    def ph = cal.getPixelHeightMicrons()
    if (!(pw > 0) || !(ph > 0))
        return [ok:false, error:'Piksel kalibrasyonu yok; alan/yoğunluk (mm²) hesaplanamaz (Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)).']
    return [ok:true, ann:selected, pw:pw, ph:ph]
}

// Verili eşiklerle (yeniden tespit YOK) hücreleri sınıflandır + H-score say.
def metricsAt = { targetAnnotation, double pw, double ph, double thr1, double thr2, double thr3 ->
    def cells = targetAnnotation.getChildObjects().findAll { it.isDetection() }
    QP.setIntensityClassifications(cells, COMPARTMENT, thr1, thr2, thr3)
    int total = cells.size(), n0 = 0, n1 = 0, n2 = 0, n3 = 0
    cells.each { c ->
        def cls = c.getPathClass()?.getName() ?: ""
        if (cls.contains("3+"))      n3++
        else if (cls.contains("2+")) n2++
        else if (cls.contains("1+")) n1++
        else                          n0++
    }
    int pos = n1 + n2 + n3
    double pctNeg = total > 0 ? 100.0 * n0 / total : 0.0
    double pct1   = total > 0 ? 100.0 * n1 / total : 0.0
    double pct2   = total > 0 ? 100.0 * n2 / total : 0.0
    double pct3   = total > 0 ? 100.0 * n3 / total : 0.0
    double posPct = total > 0 ? 100.0 * pos / total : 0.0
    double hScore = (1.0 * pct1) + (2.0 * pct2) + (3.0 * pct3)
    def roi = targetAnnotation.getROI()
    double areaMm2 = roi != null ? (roi.getArea() * pw * ph) / 1_000_000.0 : 0.0
    long density = areaMm2 > 0 ? Math.round(total / areaMm2) : 0L
    return [total:total, n0:n0, n1:n1, n2:n2, n3:n3, pos:pos,
            pctNeg:pctNeg, pct1:pct1, pct2:pct2, pct3:pct3, posPct:posPct,
            hScore:hScore, area:areaMm2, density:density]
}

def buildResultText = { m, double thr1, double thr2, double thr3, double elapsed, baseline ->
    def uyari = ""
    if (m.total < warnGenericCount) {
        uyari = String.format(java.util.Locale.US,
            "\n❗ Not: %,d hücre <%d — küçük örneklem; sonuçlar istatistiksel olarak güvenilir olmayabilir.",
            m.total, warnGenericCount)
    }
    def text = String.format(java.util.Locale.US,
        "ER / PR Nükleer H-score kantifikasyonu bitti.\n\n" +
        "Eşikler  : 1+=%.2f  2+=%.2f  3+=%.2f (DAB OD)%s\n\n" +
        "Sayım (n = %,d toplam çekirdek)\n" +
        "────────────────────────────────────\n" +
        "  Negatif (0)         : %,d  (%%%.1f)\n" +
        "  1+ (zayıf)          : %,d  (%%%.1f)\n" +
        "  2+ (orta)           : %,d  (%%%.1f)\n" +
        "  3+ (güçlü)          : %,d  (%%%.1f)\n\n" +
        "H-score (özet)\n" +
        "─────────────────\n" +
        "  H-score (0–300)        : %.0f\n" +
        "  Pozitif yüzdesi (≥1+)  : %%%.1f\n" +
        "  %% 0 / 1+ / 2+ / 3+    : %%%.1f / %%%.1f / %%%.1f / %%%.1f\n\n" +
        "  Hücre yoğunluğu        : ~%,d hücre/mm²\n" +
        "  Anotasyon alanı        : %.2f mm²\n" +
        "  Süre                   : %.1f sn\n",
        thr1, thr2, thr3,
        (thr1 != DEF1 || thr2 != DEF2 || thr3 != DEF3) ? ' (değiştirildi)' : '',
        m.total,
        m.n0, m.pctNeg, m.n1, m.pct1, m.n2, m.pct2, m.n3, m.pct3,
        m.hScore, m.posPct,
        m.pctNeg, m.pct1, m.pct2, m.pct3,
        m.density, m.area, elapsed
    )
    if (baseline != null) {
        text += String.format(java.util.Locale.US,
            "\n🎓 Öğretim — varsayılan ↔ sizin (aynı hücreler, yeniden sınıflandırıldı)\n" +
            "──────────────────────────────────────────────\n" +
            "  Varsayılan %.2f/%.2f/%.2f → H-score %.0f  (≥1+ %%%.1f)\n" +
            "  Sizin      %.2f/%.2f/%.2f → H-score %.0f  (≥1+ %%%.1f)\n" +
            "  Fark: %+.0f puan  (eşik yükseldikçe H-score düşer)\n",
            DEF1, DEF2, DEF3, baseline.hScore, baseline.posPct,
            thr1, thr2, thr3, m.hScore, m.posPct,
            (m.hScore - baseline.hScore)
        )
    }
    text += "\nPayda: anotasyonda tespit edilen TÜM çekirdekler. Hücre tipi ayrılmaz; stroma ve iltihap\n" +
            "hücreleri de sayılır. Yalnız tümör hücreleri için anotasyonu tümör alanına sınırlayın.\n"
    text += uyari + "\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return text
}

def finishWithMetrics = { t, double thr1, double thr2, double thr3, boolean teaching, double extraElapsed ->
    long t0 = System.currentTimeMillis()
    def m = metricsAt(t.ann, t.pw, t.ph, thr1, thr2, thr3)
    def baseline = null
    if (teaching && (thr1 != DEF1 || thr2 != DEF2 || thr3 != DEF3)) {
        baseline = metricsAt(t.ann, t.pw, t.ph, DEF1, DEF2, DEF3)
        m = metricsAt(t.ann, t.pw, t.ph, thr1, thr2, thr3)
    }
    QP.fireHierarchyUpdate()
    double elapsed = extraElapsed + (System.currentTimeMillis() - t0) / 1000.0
    def text = buildResultText(m, thr1, thr2, thr3, elapsed, baseline)

    println "─────────────────────────────────────"
    println "ER / PR Nükleer H-score"
    println "  n=${m.total} | 0:${m.n0} 1+:${m.n1} 2+:${m.n2} 3+:${m.n3} | H-score: ${String.format(java.util.Locale.US, '%.0f', m.hScore)}"
    println "─────────────────────────────────────"

    return [ok:true, text:text]
}

// QuPath'in hücre tespiti, çalıştığı anotasyonun içindeki MEVCUT tespitleri değiştirir;
// yalnızca bunlar sayılır (anotasyonun alt nesnesi olan tespitler).
def countReplaceable = { ann -> ann == null ? 0 : ann.getChildObjects().findAll { it.isDetection() }.size() }

def runDetection = { double nuclear1, double nuclear2, double nuclear3, boolean teaching, int replaced = 0 ->
    if (!(nuclear1 < nuclear2 && nuclear2 < nuclear3))
        return [ok:false, error:'Eşik sırası hatalı: 1+ < 2+ < 3+ olmalıdır.']
    def t = resolveTarget()
    if (!t.ok) return t
    def targetAnnotation = t.ann

    double pixelSizeMicrons = atolyeD('atolye.pixelSize', 0.5)
    double backgroundRadius = atolyeD('atolye.backgroundRadius', 8.0)
    double medianRadius     = atolyeD('atolye.medianRadius', 0.0)
    double sigma            = atolyeD('atolye.sigma', 1.5)
    double minArea          = atolyeD('atolye.minArea', 10.0)
    double maxArea          = atolyeD('atolye.maxArea', 400.0)
    double detectionThreshold = atolyeD('atolye.detectionThreshold', 0.1)
    double cellExpansion    = atolyeD('atolye.cellExpansionNuclear', 5.0)
    String detectionChannel = atolyeS('atolye.detectionChannel', 'Hematoxylin OD')
    boolean watershedPostProcess = atolyeB('atolye.watershed', true)

    long t0 = System.currentTimeMillis()
    QP.selectObjects(targetAnnotation)
    QP.runPlugin(
        'qupath.imagej.detect.cells.PositiveCellDetection',
        '{' +
            '"detectionImageBrightfield":"' + detectionChannel + '",' +
            '"requestedPixelSizeMicrons":' + pixelSizeMicrons + ',' +
            '"backgroundRadiusMicrons":' + backgroundRadius + ',' +
            '"medianRadiusMicrons":' + medianRadius + ',' +
            '"sigmaMicrons":' + sigma + ',' +
            '"minAreaMicrons":' + minArea + ',' +
            '"maxAreaMicrons":' + maxArea + ',' +
            '"threshold":' + detectionThreshold + ',' +
            '"watershedPostProcess":' + watershedPostProcess + ',' +
            '"cellExpansionMicrons":' + cellExpansion + ',' +
            '"includeNuclei":true,' +
            '"smoothBoundaries":true,' +
            '"makeMeasurements":true,' +
            '"thresholdCompartment":"Nucleus: DAB OD mean",' +
            '"thresholdPositive1":' + nuclear1 + ',' +
            '"thresholdPositive2":' + nuclear2 + ',' +
            '"thresholdPositive3":' + nuclear3 + ',' +
            '"singleThreshold":false' +
        '}'
    )
    double detectElapsed = (System.currentTimeMillis() - t0) / 1000.0
    def detRes = finishWithMetrics(t, nuclear1, nuclear2, nuclear3, teaching, detectElapsed)
    if (detRes.ok && replaced > 0)
        detRes.text = String.format(java.util.Locale.US, workshopText('script.d0c7ed3d2fd2', 'Not: önceki %,d hücre değiştirildi.'), replaced) + "\n" + detRes.text
    return detRes
}

def recount = { double thr1, double thr2, double thr3, boolean teaching ->
    if (!(thr1 < thr2 && thr2 < thr3))
        return [ok:false, error:'Eşik sırası hatalı: 1+ < 2+ < 3+ olmalıdır.']
    def t = resolveTarget()
    if (!t.ok) return t
    def existing = t.ann.getChildObjects().findAll { it.isDetection() }
    if (existing.isEmpty())
        return [ok:false, error:'Bu anotasyonda hücre tespiti yok. Önce "Çalıştır" ile tespit yapın, sonra "Yeniden say (hızlı)" kullanın.']
    return finishWithMetrics(t, thr1, thr2, thr3, teaching, 0.0)
}

// Headless: tek sefer atölye varsayılanlarıyla çalıştır + yazdır.
if (isHeadless) {
    // Tespit mevcut hücreleri DEĞİŞTİRİR: soru sorulamaz, açık onay (-a mevcut=degistir) gerekir.
    def hlMode = headlessArg('mevcut')
    if (hlMode != null && hlMode != 'degistir') {
        println String.format(java.util.Locale.US, workshopText('script.25b257bce6d1', 'DURDU: -a mevcut için geçersiz değer "%s" (yalnızca degistir kabul edilir). Hiçbir şey değiştirilmedi.'), hlMode)
        return
    }
    def hlTarget = resolveTarget()
    int hlPrev = hlTarget.ok ? countReplaceable(hlTarget.ann) : 0
    if (hlPrev > 0 && hlMode != 'degistir') {
        println String.format(java.util.Locale.US, workshopText('script.b1cf692ac056', 'DURDU: hedef anotasyonda zaten %,d hücre tespiti var. Hiçbir şey değiştirilmedi. Bu hücrelerin (elle yapılmış sınıf ve düzeltmeler dahil) değiştirilmesini kabul ediyorsanız -a mevcut=degistir ile yeniden çalıştırın.'), hlPrev)
        return
    }
    def r = runDetection(DEF1, DEF2, DEF3, false, hlPrev)
    println r.ok ? r.text : ("Hata: " + r.error)
    return
}

// ──────────────────────────────────────────────────────────────
// 3) Tek pencere: ayarla → Çalıştır → sonuç → (gerekirse) hızlı yeniden say
// ──────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        def hasDetections = new java.util.concurrent.atomic.AtomicBoolean(false)
        def initSel = QP.getSelectedObject()
        if (initSel instanceof PathAnnotationObject && !initSel.getChildObjects().findAll { it.isDetection() }.isEmpty()) {
            hasDetections.set(true)
        }

        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle(workshopText('script.1777188d6945', 'ER / PR Nükleer H-score'))
        stage.setAlwaysOnTop(true)

        def title = new javafx.scene.control.Label(workshopText('script.1777188d6945', 'ER / PR Nükleer H-score'))
        title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
        def info = new javafx.scene.control.Label(
            workshopText('script.9fd4af8ce38e', 'Bir dikdörtgen anotasyon (R) çizip SEÇİN (kenarı sarı), sonra "Çalıştır".\n' +
            'Eşik değiştirince "Yeniden say (hızlı)" tespiti tekrarlamadan saniyeler içinde günceller.'))
        info.setWrapText(true)

        def commitSpinner = { javafx.scene.control.Spinner sp ->
            try {
                def vf = sp.getValueFactory()
                def conv = vf?.getConverter()
                def txt = sp.getEditor()?.getText()?.trim()?.replace(',', '.')
                if (vf != null && conv != null && txt != null && !txt.isEmpty()) {
                    vf.setValue(conv.fromString(txt))
                }
            } catch (Throwable ignore) {
                if (sp.getValueFactory() != null && sp.getEditor() != null)
                    sp.getEditor().setText(String.valueOf(sp.getValueFactory().getValue()))
            }
        }
        def spT1 = new javafx.scene.control.Spinner(0.0, 2.0, DEF1, 0.01)
        def spT2 = new javafx.scene.control.Spinner(0.0, 2.0, DEF2, 0.01)
        def spT3 = new javafx.scene.control.Spinner(0.0, 2.0, DEF3, 0.01)
        [spT1, spT2, spT3].each { sp ->
            sp.setEditable(true); sp.setPrefWidth(110)
            sp.focusedProperty().addListener({ obs, was, now -> if (!now) commitSpinner(sp) } as javafx.beans.value.ChangeListener)
        }
        def thrTip = new javafx.scene.control.Tooltip(
            workshopText('script.f12fe9de9b64', 'DAB optik yoğunluğu (OD) eşiği: bir çekirdeğin "Nucleus: DAB OD mean" değeri\n' +
            'bu eşiğin üzerindeyse o sınıfa (1+/2+/3+) atanır. H-score = 1×%1+ + 2×%2+ + 3×%3+.\n' +
            'Eşiği YÜKSELTMEK H-score\'u düşürür. Sıra: 1+ < 2+ < 3+ olmalı.'))
        [spT1, spT2, spT3].each { it.setTooltip(thrTip) }
        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(6); grid.setPadding(new javafx.geometry.Insets(6))
        grid.addRow(0, new javafx.scene.control.Label(workshopText('script.24c882487fa5', '1+ eşiği (zayıf, DAB OD)')), spT1)
        grid.addRow(1, new javafx.scene.control.Label(workshopText('script.27657aeeb615', '2+ eşiği (orta, DAB OD)')),  spT2)
        grid.addRow(2, new javafx.scene.control.Label(workshopText('script.f442dcf8feff', '3+ eşiği (güçlü, DAB OD)')), spT3)
        def advHint = new javafx.scene.control.Label(
            workshopText('script.c182160af513', 'DAB OD eşiği: çekirdeğin DAB sinyali bu değerin üzerindeyse pozitif sayılır. ' +
            'Yükseltmek H-score\'u düşürür.'))
        advHint.setWrapText(true)
        advHint.setStyle('-fx-font-style: italic; -fx-opacity: 0.7; -fx-font-size: 11px;')
        def advBox = new javafx.scene.layout.VBox(6, grid, advHint)
        def adv = new javafx.scene.control.TitledPane(workshopText('script.782a54764f62', '⚙ Gelişmiş ayarlar — eşikler'), advBox)
        adv.setExpanded(false); adv.setAnimated(false)

        def teachChk = new javafx.scene.control.CheckBox(workshopText('script.e8258a4a26d9', 'Öğretim modu (varsayılan ↔ sizin karşılaştırması)'))
        teachChk.setSelected(true)

        def status = new javafx.scene.control.Label(workshopText('script.86bb45e59294', 'Hazır.'))
        def progress = new javafx.scene.control.ProgressBar()
        progress.setMaxWidth(Double.MAX_VALUE); progress.setVisible(false); progress.setManaged(false)
        def resultArea = new javafx.scene.control.TextArea()
        resultArea.setEditable(false); resultArea.setWrapText(false); resultArea.setPrefRowCount(10)
        resultArea.setPromptText(workshopText('script.4adde9b85aed', 'Sonuçlar burada görünecek…'))
        resultArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;")

        def runBtn = new javafx.scene.control.Button(workshopText('script.79ef801ff99f', 'Çalıştır')); runBtn.setDefaultButton(true)
        def recountBtn = new javafx.scene.control.Button(workshopText('script.12c291ed7b57', 'Yeniden say (hızlı)')); recountBtn.setDisable(!hasDetections.get())
        def fixTypeBtn = new javafx.scene.control.Button(workshopText('script.8a62116b4df8', "Görüntü tipini 'Brightfield (H-DAB)' olarak ayarla"))
        fixTypeBtn.setVisible(false); fixTypeBtn.setManaged(false)
        fixTypeBtn.setOnAction({
            imageData.setImageType(qupath.lib.images.ImageData.ImageType.BRIGHTFIELD_H_DAB)
            QP.setImageType('BRIGHTFIELD_H_DAB')
            fixTypeBtn.setVisible(false); fixTypeBtn.setManaged(false)
            status.setStyle(''); status.setText(workshopText('script.1a0b412fdbec', "✓ Görüntü tipi 'Brightfield (H-DAB)' olarak ayarlandı. Şimdi bir alan anotasyonu seçip 'Çalıştır'a basın."))
            resultArea.setText('')
        })

        // İlk açılışta görüntü tipi yanlışsa kullanıcıyı pencere içinde yönlendir (modal yok).
        if (!imageTypeOk()) {
            status.setStyle('-fx-text-fill: -qp-script-error-color;')
            status.setText(workshopText('script.d6e9c7dd78f5', "⚠ Görüntü tipi 'Brightfield (H-DAB)' değil — tek tıkla düzeltebilirsiniz."))
            resultArea.setText(imageTypeGuidance())
            fixTypeBtn.setVisible(true); fixTypeBtn.setManaged(true)
        }

        def handleResult = { res ->
            progress.setVisible(false); progress.setManaged(false)
            runBtn.setDisable(false)
            recountBtn.setDisable(!hasDetections.get())
            if (res.ok) {
                fixTypeBtn.setVisible(false); fixTypeBtn.setManaged(false)
                hasDetections.set(true); recountBtn.setDisable(false)
                status.setStyle(''); status.setText(workshopText('script.0d10b0792204', 'Tamamlandı ✅ — eşikleri değiştirip "Yeniden say (hızlı)" deneyin.'))
                resultArea.setText(res.text)
            } else if (res.imageTypeIssue) {
                status.setStyle('-fx-text-fill: -qp-script-error-color;')
                status.setText(workshopText('script.d6e9c7dd78f5', "⚠ Görüntü tipi 'Brightfield (H-DAB)' değil — tek tıkla düzeltebilirsiniz."))
                resultArea.setText(res.error)
                fixTypeBtn.setVisible(true); fixTypeBtn.setManaged(true)
            } else {
                status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + res.error)
            }
        }

        runBtn.setOnAction({
            [spT1, spT2, spT3].each { commitSpinner(it) }
            double t1 = spT1.getValue() as double
            double t2 = spT2.getValue() as double
            double t3 = spT3.getValue() as double
            boolean teaching = teachChk.isSelected()
            // Tespit, seçili anotasyondaki mevcut hücreleri DEĞİŞTİRİR: tespitten ÖNCE sorulur.
            def selNow = QP.getSelectedObject()
            int nPrev = (selNow instanceof PathAnnotationObject && imageTypeOk() && t1 < t2 && t2 < t3) ? countReplaceable(selNow) : 0
            if (nPrev > 0) {
                boolean okReplace = confirmReplaceCells(workshopText('script.c8cded1ce05d', 'Önceki hücre tespitleri değiştirilecek'),
                    String.format(java.util.Locale.US, workshopText('script.461bbed70a95', 'Seçili anotasyonda zaten %,d hücre bulunuyor; tespit bunları, elle yapılmış sınıf ve düzeltmeler dahil, değiştirecek.\n\nYalnız eşikleri değiştirmek istiyorsanız "İptal" deyip "Yeniden say (hızlı)" düğmesini kullanın; bu düğme hücreleri silmez.\n\nDevam edilsin mi? ("Tamam" = değiştir, "İptal" = hiçbir şey çalıştırılmaz)'), nPrev))
                if (!okReplace) {
                    status.setStyle(''); status.setText(workshopText('script.0bce6aa77f6b', 'İptal edildi — hiçbir şey çalıştırılmadı, mevcut hücreler korundu.'))
                    return
                }
            }
            runBtn.setDisable(true); recountBtn.setDisable(true)
            status.setStyle(''); status.setText(workshopText('script.e6d86b4eba06', 'Çalışıyor… (hücre tespiti)'))
            progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
            def worker = new Thread({
                def res = runDetection(t1, t2, t3, teaching, nPrev)
                javafx.application.Platform.runLater { handleResult(res) }
            }, 'Modul3bHScore')
            worker.setDaemon(true); worker.start()
        })

        recountBtn.setOnAction({
            [spT1, spT2, spT3].each { commitSpinner(it) }
            runBtn.setDisable(true); recountBtn.setDisable(true)
            status.setStyle(''); status.setText(workshopText('script.001dd7d79da4', 'Yeniden sayılıyor… (tespit yok, yalnızca sınıflandırma)'))
            progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
            double t1 = spT1.getValue() as double
            double t2 = spT2.getValue() as double
            double t3 = spT3.getValue() as double
            boolean teaching = teachChk.isSelected()
            def worker = new Thread({
                def res = recount(t1, t2, t3, teaching)
                javafx.application.Platform.runLater { handleResult(res) }
            }, 'Modul3bRecount')
            worker.setDaemon(true); worker.start()
        })

        def alwaysTop = new javafx.scene.control.CheckBox(workshopText('script.70ca64c0c43a', 'Üstte tut')); alwaysTop.setSelected(true)
        alwaysTop.selectedProperty().addListener(
            { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
        def copyBtn = new javafx.scene.control.Button(workshopText('script.a8bcca42d9ce', 'Kopyala'))
        copyBtn.setOnAction({
            def cb = javafx.scene.input.Clipboard.getSystemClipboard()
            def c = new javafx.scene.input.ClipboardContent(); c.putString(resultArea.getText()); cb.setContent(c)
        })
        def closeBtn = new javafx.scene.control.Button(workshopText('script.7b31a9fc4816', 'Kapat')); closeBtn.setOnAction({ stage.close() })

        def footer = new javafx.scene.control.Label(workshopText('script.be46103cef99', 'QuPath Atölye Betikleri · araştırma/eğitim amaçlı'))
        footer.setMaxWidth(Double.MAX_VALUE)
        footer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;')

        def spacer = new javafx.scene.layout.Region()
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
        def btnRow = new javafx.scene.layout.HBox(8, alwaysTop, spacer, copyBtn, recountBtn, runBtn, closeBtn)
        btnRow.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

        def content = new javafx.scene.layout.VBox(10, title, info, adv, teachChk, status, fixTypeBtn, progress, resultArea)
        content.setPadding(new javafx.geometry.Insets(14))
        javafx.scene.layout.VBox.setVgrow(resultArea, javafx.scene.layout.Priority.ALWAYS)
        def bottom = new javafx.scene.layout.VBox(8, footer, btnRow)
        bottom.setPadding(new javafx.geometry.Insets(10))
        def root = new javafx.scene.layout.BorderPane()
        root.setCenter(content); root.setBottom(bottom)
        stage.setScene(new javafx.scene.Scene(root, 580, 580))
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.ebedf27f3475', 'ER / PR H-score açılamadı'), t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}

// Resolve extension UI language; standalone scripts keep their Turkish fallback.
private String workshopText(String key, String fallback) {
    try {
        def bundle = Class.forName('io.github.sbalci.qupath.workshop.WorkshopI18n')
        String translated = bundle.getMethod('t', String.class).invoke(null, key) as String
        return translated == key ? fallback : translated
    } catch (Throwable ignored) {
        return fallback
    }
}
