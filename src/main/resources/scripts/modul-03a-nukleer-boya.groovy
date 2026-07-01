/**
 * Modül 3a - Tek Tıkla Ki-67 / Nükleer İHK Kantifikasyonu
 * --------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Atölye için "hızlı deneme" betiği. Seçilen anotasyon içinde
 * Ki-67 ve benzeri nükleer DAB boyamalarını (p53 hariç — marker-özgü bir
 * iş akışı gerektirir) otomatik olarak skorlar ve **Ki-67 LI**
 * (etiketleme indeksi) ile grup dağılımını kaydeder.
 *
 * KULLANIM:
 *   1. Ki-67 (veya başka nükleer DAB) İHK slaytını açın
 *   2. Image type → "Brightfield (H-DAB)" olduğundan emin olun
 *      ([Image → Image type → Brightfield (H-DAB)])
 *   3. [R] tuşu → tümör içeren ~1×1 mm dikdörtgen anotasyon çizin ve SEÇİN
 *   4. Bu betiği çalıştırın → açılan TEK pencerede "Çalıştır"
 *   5. Pencere açık kalır: eşikleri değiştirip "Yeniden say (hızlı)" ile
 *      hücre tespitini TEKRAR ÇALIŞTIRMADAN saniyeler içinde yeniden bin'leyin.
 *      "Öğretim modu" açıkken varsayılan ↔ sizin eşik karşılaştırması da gösterilir
 *      (parametrenin etkisini görmek için — HMS-IAC keşif yöntemiyle aynı mantık).
 *
 * NE YAPAR:
 *   • Atölye boya vektörleri ile çekirdek-yoğun DAB sinyalini ayırır
 *   • Her çekirdeği Negative / 1+ / 2+ / 3+ olarak sınıflar
 *     (eşikler: 0.2 / 0.4 / 0.6 OD)
 *   • Pozitif yüzdesini (Ki-67 LI), grup dağılımını ve hücre yoğunluğunu hesaplar
 *
 * NE YAPMAZ:
 *   • Boya vektörlerinizi otomatik tahmin etmez — önceden ayarlanmış olmalı
 *     ya da QuPath'in varsayılan H-DAB vektörlerini kullanır
 *
 * YÖNTEM REFERANSLARI:
 *   • Nielsen TO et al. (2021), J Natl Cancer Inst — Ki-67 Working Group sayma standardı
 *     (≥500-1.000 tümör hücresi). doi:10.1093/jnci/djaa201
 *   • Skjervold AH et al. (2022), Diagn Pathol — manuel vs dijital uyum
 *     doi:10.1186/s13000-022-01225-4
 *   • Acs B et al. (2018), Lab Invest 99(1):107–117 — platform/gözlemci arası
 *     Ki-67 yeniden-üretilebilirliği. doi:10.1038/s41374-018-0123-7
 *   • Catteau X et al. (2023), Technol Cancer Res Treat — dijital Ki-67 yalnızca
 *     patolog-işaretli bölgede manuel skorla uyumlu. doi:10.1177/15330338231169603
 *   • Spyretos C et al. (2026), J Neuropathol Exp Neurol 85(5):475–486 — StarDist
 *     ile tam otomatik, proje-ölçeğinde Ki-67 LI. doi:10.1093/jnen/nlaf163
 *   • Arayüz tarafı eğitimi (cancer-informatics.org, J. Cieślik et al., CC-BY-SA):
 *     cancer-informatics.org/de/docs/ai/qupath_04_ki67_index
 *
 * EŞİK HASSASİYETİ:
 *   • Pozitif %, aynı slaytta yalnızca tespit + DAB eşiği değiştiğinde ~%3.7 →
 *     ~%24 arası oynayabilir ve tarayıcıya göre farklılaşır (Bankhead 2022
 *     parametre-duyarlılık örneği). Karşılaştırılan tüm slaytlarda aynı
 *     parametreleri kullanın; boya vektörlerini her tarayıcı için yeniden
 *     kestirin ([Analyze → Estimate stain vectors]).
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathAnnotationObject

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
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
        "Görüntü açık değil",
        "Önce bir Ki-67 İHK slaytı açın, sonra bu betiği tekrar çalıştırın."
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
    "Bu slayt 'Brightfield (H-DAB)' olarak ayarlı değil.\n" +
    "Şu anki tip: ${imageData.getImageType()?.toString() ?: ''}\n\n" +
    "Çözüm:\n" +
    "  1. Image panelini açın (sol-üst)\n" +
    "  2. 'Image type' → 'Brightfield (H-DAB)' seçin (DAB ayrımı için gerekli)\n" +
    "  3. Yukarıdaki 'Çalıştır' düğmesine tekrar basın"
}

// H-DAB ise ve boya vektörleri eksikse atölye varsayılanını uygula.
// (Yanlış tipteki slaytları otomatik dönüştürmeyiz — yönlendirme pencerede gösterilir.)
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
        println "⚠ H-DAB boya vektörleri tanımlı değil → BRIGHTFIELD_H_DAB varsayılanı uygulanıyor."
        QP.setImageType('BRIGHTFIELD_H_DAB')
    }
}

// ──────────────────────────────────────────────────────────────
// 1b) Boya vektörü önerisi + tek-tık tahmin (tüm DAB-OD modüllerinde aynı blok).
//   • Aktif vektörler QuPath VARSAYILANI ise uyarır: kantitatif ölçümden önce bu
//     slayttan tahmin etmeyi önerir (eşikler mutlak DAB OD; yanlış vektör skoru kaydırır).
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
    if (!svDefault) { println '✓ Boya vektörleri bu slayttan tahmin/kalibre edilmiş görünüyor.'; return }
    def svMsg =
        '⚠ Boya vektörleri QuPath VARSAYILANI görünüyor — bu slayttan tahmin EDİLMEMİŞ.\n\n' +
        'Eşikler mutlak DAB OD üzerinden çalışır; doğru vektör olmadan skorlar kayar.\n' +
        'Tarayıcı/boyama partisi başına bir kez tahmin yeterlidir.\n\n' +
        'Şimdi SEÇİLİ alandan tahmin edilsin mi?\n' +
        '(İki boya + biraz arka plan içeren temsilî bir alan anotasyonu seçili olmalı.)\n\n' +
        '[Tamam] = tahmin et ve uygula      [İptal] = yine de devam et'
    if (isHeadless) { println svMsg; return }
    if (!qupath.fx.dialogs.Dialogs.showConfirmDialog('Boya vektörleri — öneri', svMsg)) return
    def svSel = QP.getSelectedObjects().findAll { it.hasROI() && it.getROI().isArea() }
    if (svSel.isEmpty()) {
        qupath.fx.dialogs.Dialogs.showWarningNotification('Boya vektörleri',
            'Önce iki boya + biraz arka plan içeren temsilî bir alan anotasyonu çizip SEÇİN, sonra tekrar deneyin.')
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
        qupath.fx.dialogs.Dialogs.showMessageDialog('Boya vektörleri',
            'Vektörler seçili bölgeden tahmin edilip uygulandı. Modülü şimdi çalıştırabilirsiniz.')
    } catch (Throwable svErr) {
        qupath.fx.dialogs.Dialogs.showMessageDialog('Boya vektörleri',
            'Tahmin yapılamadı: ' + (svErr.getMessage() ?: svErr.getClass().getSimpleName()))
    }
}
suggestStainVectors()

// ──────────────────────────────────────────────────────────────
// 2) Ölçüm + sonuç oluşturma — paylaşılan yardımcılar.
//    Hücreler "Nucleus: DAB OD mean" sütununda bin'lenir (3a/3b/7 nükleer).
//    Eşik değişince hücre tespitini yeniden çalıştırmak yerine sadece bu sütun
//    üzerinden yeniden sınıflandırırız (setIntensityClassifications) — saniyeler
//    yerine milisaniyeler. "recount" bunu kullanır, "runDetection" tam tespit yapar.
// ──────────────────────────────────────────────────────────────
def COMPARTMENT = 'Nucleus: DAB OD mean'
// Atölye varsayılan eşikleri — öğretim modu "varsayılan ↔ sizin" karşılaştırması için.
def DEF1 = atolyeD('atolye.nuclear1', 0.20)
def DEF2 = atolyeD('atolye.nuclear2', 0.40)
def DEF3 = atolyeD('atolye.nuclear3', 0.60)
def warnNuclearCount = atolyeI('atolye.warnNuclearCount', 500)

// Seçili dikdörtgen anotasyonu + kalibrasyonu çöz (her çalıştırmada yeniden denetlenir).
def resolveTarget = { ->
    if (!imageTypeOk())
        return [ok:false, error: imageTypeGuidance(), imageTypeIssue:true]
    def selected = QP.getSelectedObject()
    if (selected == null || !(selected instanceof PathAnnotationObject))
        return [ok:false, error:'Önce ölçmek istediğiniz dikdörtgen anotasyonu çizip SEÇİN (kenarı sarı görünür).']
    def cal = imageData.getServer().getPixelCalibration()
    def pw = cal.getPixelWidthMicrons()
    def ph = cal.getPixelHeightMicrons()
    if (!(pw > 0) || !(ph > 0))
        return [ok:false, error:'Piksel kalibrasyonu yok; mm² hesaplanamaz (Yardımcılar → Kalibrasyon).']
    return [ok:true, ann:selected, pw:pw, ph:ph]
}

// Verili eşiklerle (yeniden tespit YOK) seçili anotasyonun hücrelerini bin'le + say.
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
    double li = total > 0 ? 100.0 * pos / total : 0.0
    def roi = targetAnnotation.getROI()
    double areaMm2 = roi != null ? (roi.getArea() * pw * ph) / 1_000_000.0 : 0.0
    long density = areaMm2 > 0 ? Math.round(total / areaMm2) : 0L
    return [total:total, n0:n0, n1:n1, n2:n2, n3:n3, pos:pos, li:li, area:areaMm2, density:density]
}

// Sonuç metnini oluştur. baseline != null ise öğretim karşılaştırması eklenir.
def buildResultText = { m, double thr1, double thr2, double thr3, double elapsed, baseline ->
    def pctOf = { int c -> m.total > 0 ? 100.0 * c / m.total : 0.0 }
    def uyari = ""
    if (m.total < warnNuclearCount) {
        uyari = String.format(java.util.Locale.US,
            "\n📝 Not: %,d hücre <500 — Ki-67 Working Group (Nielsen 2021) sayma standardının altında.\n" +
            "  Daha büyük bir ROI ile tekrar deneyin (hedef: ≥500-1.000 hücre).",
            m.total)
    } else if (m.total > 50000) {
        uyari = String.format(java.util.Locale.US,
            "\n📝 Not: %,d hücre çok fazla — ROI küçültmek hesaplama hızını artırır.",
            m.total)
    }
    def text = String.format(java.util.Locale.US,
        "Eşikler: 1+=%.2f  2+=%.2f  3+=%.2f (Nucleus: DAB OD mean)\n" +
        "────────────────────────────────────\n" +
        "📊 Sayım sonuçları\n" +
        "────────────────────\n" +
        "  Toplam hücre        : %,d\n" +
        "  Negatif             : %,d  (%%%.1f)\n" +
        "  1+ (zayıf)          : %,d  (%%%.1f)\n" +
        "  2+ (orta)           : %,d  (%%%.1f)\n" +
        "  3+ (güçlü)          : %,d  (%%%.1f)\n\n" +
        "🎯 Metrikler\n" +
        "─────────────\n" +
        "  Ki-67 LI (Pozitif%%)  : %%%.1f\n" +
        "  Hücre yoğunluğu       : ~%,d hücre/mm²\n" +
        "  Anotasyon alanı       : %.2f mm²\n" +
        "  Süre                  : %.1f sn\n",
        thr1, thr2, thr3,
        m.total,
        m.n0, pctOf(m.n0),
        m.n1, pctOf(m.n1),
        m.n2, pctOf(m.n2),
        m.n3, pctOf(m.n3),
        m.li, m.density, m.area, elapsed
    )
    if (baseline != null) {
        text += String.format(java.util.Locale.US,
            "\n🎓 Öğretim — varsayılan ↔ sizin (aynı hücreler, yeniden bin'lendi)\n" +
            "──────────────────────────────────────────────\n" +
            "  Varsayılan %.2f/%.2f/%.2f → LI %%%.1f  (%,d/%,d pozitif)\n" +
            "  Sizin      %.2f/%.2f/%.2f → LI %%%.1f  (%,d/%,d pozitif)\n" +
            "  Fark: %+.1f puan  (eşik yükseldikçe LI düşer)\n",
            DEF1, DEF2, DEF3, baseline.li, baseline.pos, baseline.total,
            thr1, thr2, thr3, m.li, m.pos, m.total,
            (m.li - baseline.li)
        )
    }
    text += uyari + "\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return text
}

// Bin'le + (öğretim modunda) varsayılanla karşılaştır + metni kur. Son durum daima
// kullanıcı eşikleridir. extraElapsed = tespit süresi (recount'ta 0).
def finishWithMetrics = { t, double thr1, double thr2, double thr3, boolean teaching, double extraElapsed ->
    long t0 = System.currentTimeMillis()
    def m = metricsAt(t.ann, t.pw, t.ph, thr1, thr2, thr3)
    def baseline = null
    if (teaching && (thr1 != DEF1 || thr2 != DEF2 || thr3 != DEF3)) {
        baseline = metricsAt(t.ann, t.pw, t.ph, DEF1, DEF2, DEF3)
        m = metricsAt(t.ann, t.pw, t.ph, thr1, thr2, thr3)   // son durum = sizin eşikleriniz
    }
    QP.fireHierarchyUpdate()
    double elapsed = extraElapsed + (System.currentTimeMillis() - t0) / 1000.0
    def text = buildResultText(m, thr1, thr2, thr3, elapsed, baseline)

    println "─────────────────────────────────────"
    println "Modül 3a - Ki-67 / Nükleer İHK"
    println "─────────────────────────────────────"
    println "  Toplam: ${m.total}  |  Pozitif: ${m.pos}  |  Ki-67 LI: ${String.format(java.util.Locale.US, '%.1f', m.li)}%"
    println "  Yoğunluk: ${m.density}/mm²  |  Süre: ${String.format(java.util.Locale.US, '%.1f', elapsed)} sn"
    println "─────────────────────────────────────"

    return [ok:true, text:text]
}

// Tam tespit (yavaş): PositiveCellDetection çalıştırır, sonra bin'ler + özetler.
def runDetection = { double thr1, double thr2, double thr3, boolean teaching ->
    def t = resolveTarget()
    if (!t.ok) return t
    def targetAnnotation = t.ann

    def detectionImageBrightfield = atolyeS('atolye.detectionChannel', 'Hematoxylin OD')
    def requestedPixelSizeMicrons = atolyeD('atolye.pixelSize', 0.5)
    def backgroundRadiusMicrons   = atolyeD('atolye.backgroundRadius', 8.0)
    def medianRadiusMicrons       = atolyeD('atolye.medianRadius', 0.0)
    def sigmaMicrons              = atolyeD('atolye.sigma', 1.5)
    def minAreaMicrons            = atolyeD('atolye.minArea', 10.0)
    def maxAreaMicrons            = atolyeD('atolye.maxArea', 400.0)
    def detectionThreshold        = atolyeD('atolye.detectionThreshold', 0.1)
    def watershedPostProcess      = atolyeB('atolye.watershed', true)
    def cellExpansionMicrons      = atolyeD('atolye.cellExpansionNuclear', 5.0)

    long t0 = System.currentTimeMillis()
    QP.selectObjects(targetAnnotation)

    // Tespit kanalı seçimi — yöntemsel not:
    //   "Hematoxylin OD" → çekirdek tespiti hematoksilin sinyali üzerinden (varsayılan).
    //     Yüksek-LI Ki-67'de güçlü DAB hematoksilin sinyalini bastırabilir, bazı
    //     pozitif çekirdekler kaçabilir.
    //   "Optical density sum" → H + DAB + Eozin OD kombinasyonu; ASCO/cancer-informatics
    //     eğitimleri Ki-67 için bu kanalı önerir (DAB-yoğun pozitiflerde daha güvenli).
    //     Trade-off: arka plan gürültüsüne biraz daha duyarlı; eşiklerin yeniden
    //     kalibre edilmesi gerekebilir.
    // Atölye varsayılanı "Hematoxylin OD" — düşük-orta LI'da daha temiz segmentasyon verir.
    // Yüksek-LI vakada WorkshopPrefs'ten 'atolye.detectionChannel' → 'Optical density sum' yapın
    // ve eşikleri referans hücrelerde yeniden test edin.
    QP.runPlugin(
        'qupath.imagej.detect.cells.PositiveCellDetection',
        '{' +
            '"detectionImageBrightfield":"' + detectionImageBrightfield + '",' +
            '"requestedPixelSizeMicrons":' + requestedPixelSizeMicrons + ',' +
            '"backgroundRadiusMicrons":' + backgroundRadiusMicrons + ',' +
            '"medianRadiusMicrons":' + medianRadiusMicrons + ',' +
            '"sigmaMicrons":' + sigmaMicrons + ',' +
            '"minAreaMicrons":' + minAreaMicrons + ',' +
            '"maxAreaMicrons":' + maxAreaMicrons + ',' +
            '"threshold":' + detectionThreshold + ',' +
            '"watershedPostProcess":' + watershedPostProcess + ',' +
            '"cellExpansionMicrons":' + cellExpansionMicrons + ',' +
            '"includeNuclei":true,' +
            '"smoothBoundaries":true,' +
            '"makeMeasurements":true,' +
            '"thresholdCompartment":"Nucleus: DAB OD mean",' +
            '"thresholdPositive1":' + thr1 + ',' +
            '"thresholdPositive2":' + thr2 + ',' +
            '"thresholdPositive3":' + thr3 + ',' +
            '"singleThreshold":false' +
        '}'
    )

    double detectElapsed = (System.currentTimeMillis() - t0) / 1000.0
    return finishWithMetrics(t, thr1, thr2, thr3, teaching, detectElapsed)
}

// Hızlı yeniden say (tespit YOK): yalnızca mevcut hücreleri yeni eşiklerle bin'ler.
def recount = { double thr1, double thr2, double thr3, boolean teaching ->
    def t = resolveTarget()
    if (!t.ok) return t
    def existing = t.ann.getChildObjects().findAll { it.isDetection() }
    if (existing.isEmpty())
        return [ok:false, error:'Bu anotasyonda hücre tespiti yok. Önce "Çalıştır" ile tespit yapın, sonra "Yeniden say (hızlı)" kullanın.']
    return finishWithMetrics(t, thr1, thr2, thr3, teaching, 0.0)
}

// Headless: tek sefer atölye varsayılanlarıyla çalıştır + yazdır.
if (isHeadless) {
    def r = runDetection(DEF1, DEF2, DEF3, false)
    println r.ok ? r.text : ("Hata: " + r.error)
    return
}

// ──────────────────────────────────────────────────────────────
// 3) Tek pencere: ayarla → Çalıştır → sonuç → (gerekirse) hızlı yeniden say
// ──────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        def hasDetections = new java.util.concurrent.atomic.AtomicBoolean(false)

        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Modül 3a - Ki-67 / Nükleer İHK kantifikasyonu')
        stage.setAlwaysOnTop(true)

        def title = new javafx.scene.control.Label('Ki-67 / Nükleer İHK kantifikasyonu')
        title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
        def info = new javafx.scene.control.Label(
            'Bir dikdörtgen anotasyon (R) çizip SEÇİN (kenarı sarı), sonra "Çalıştır".\n' +
            'Eşik değiştirince "Yeniden say (hızlı)" tespiti tekrarlamadan saniyeler içinde günceller.')
        info.setWrapText(true)

        def spThr1 = new javafx.scene.control.Spinner(0.0, 2.0, DEF1, 0.01)
        def spThr2 = new javafx.scene.control.Spinner(0.0, 2.0, DEF2, 0.01)
        def spThr3 = new javafx.scene.control.Spinner(0.0, 2.0, DEF3, 0.01)
        [spThr1, spThr2, spThr3].each { it.setEditable(true); it.setPrefWidth(110) }
        // Feature 2 — parametre açıklayıcısı: DAB optik yoğunluğu (OD) nedir, ne yapar.
        def thrTip = new javafx.scene.control.Tooltip(
            'DAB optik yoğunluğu (OD) eşiği: bir çekirdeğin "Nucleus: DAB OD mean" değeri\n' +
            'bu eşiğin üzerindeyse o sınıfa (1+/2+/3+) atanır. Eşiği YÜKSELTMEK pozitif\n' +
            'sayısını AZALTIR; düşürmek artırır. Sıra: 1+ < 2+ < 3+ olmalı.')
        [spThr1, spThr2, spThr3].each { it.setTooltip(thrTip) }
        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(6); grid.setPadding(new javafx.geometry.Insets(6))
        grid.addRow(0, new javafx.scene.control.Label('1+ eşiği (zayıf, DAB OD)'), spThr1)
        grid.addRow(1, new javafx.scene.control.Label('2+ eşiği (orta, DAB OD)'),  spThr2)
        grid.addRow(2, new javafx.scene.control.Label('3+ eşiği (güçlü, DAB OD)'), spThr3)
        def advHint = new javafx.scene.control.Label(
            'DAB OD eşiği: çekirdeğin DAB sinyali bu değerin üzerindeyse pozitif sayılır. ' +
            'Yükseltmek pozitif sayısını azaltır.')
        advHint.setWrapText(true)
        advHint.setStyle('-fx-font-style: italic; -fx-opacity: 0.7; -fx-font-size: 11px;')
        def advBox = new javafx.scene.layout.VBox(6, grid, advHint)
        def adv = new javafx.scene.control.TitledPane('⚙ Gelişmiş ayarlar — eşikler', advBox)
        adv.setExpanded(false); adv.setAnimated(false)

        def teachChk = new javafx.scene.control.CheckBox('Öğretim modu (varsayılan ↔ sizin karşılaştırması)')
        teachChk.setSelected(true)

        def status = new javafx.scene.control.Label('Hazır.')
        def progress = new javafx.scene.control.ProgressBar()
        progress.setMaxWidth(Double.MAX_VALUE); progress.setVisible(false); progress.setManaged(false)
        def resultArea = new javafx.scene.control.TextArea()
        resultArea.setEditable(false); resultArea.setWrapText(false); resultArea.setPrefRowCount(10)
        resultArea.setPromptText('Sonuçlar burada görünecek…')
        resultArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;")

        def runBtn = new javafx.scene.control.Button('Çalıştır'); runBtn.setDefaultButton(true)
        def recountBtn = new javafx.scene.control.Button('Yeniden say (hızlı)'); recountBtn.setDisable(true)

        // İlk açılışta görüntü tipi yanlışsa kullanıcıyı pencere içinde yönlendir (modal yok).
        if (!imageTypeOk()) {
            status.setStyle('-fx-text-fill: -qp-script-error-color;')
            status.setText("⚠ Görüntü tipi 'Brightfield (H-DAB)' değil — adımlar aşağıda.")
            resultArea.setText(imageTypeGuidance())
        }

        def handleResult = { res ->
            progress.setVisible(false); progress.setManaged(false)
            runBtn.setDisable(false)
            recountBtn.setDisable(!hasDetections.get())
            if (res.ok) {
                hasDetections.set(true); recountBtn.setDisable(false)
                status.setStyle(''); status.setText('Tamamlandı ✅ — eşikleri değiştirip "Yeniden say (hızlı)" deneyin.')
                resultArea.setText(res.text)
            } else if (res.imageTypeIssue) {
                status.setStyle('-fx-text-fill: -qp-script-error-color;')
                status.setText("⚠ Görüntü tipi 'Brightfield (H-DAB)' değil — adımlar aşağıda.")
                resultArea.setText(res.error)
            } else {
                status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + res.error)
            }
        }

        runBtn.setOnAction({
            runBtn.setDisable(true); recountBtn.setDisable(true)
            status.setStyle(''); status.setText('Çalışıyor… (hücre tespiti)')
            progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
            double thr1 = spThr1.getValue() as double
            double thr2 = spThr2.getValue() as double
            double thr3 = spThr3.getValue() as double
            boolean teaching = teachChk.isSelected()
            def worker = new Thread({
                def res = runDetection(thr1, thr2, thr3, teaching)
                javafx.application.Platform.runLater { handleResult(res) }
            }, 'Modul3Detect')
            worker.setDaemon(true); worker.start()
        })

        recountBtn.setOnAction({
            runBtn.setDisable(true); recountBtn.setDisable(true)
            status.setStyle(''); status.setText('Yeniden sayılıyor… (tespit yok, sadece bin)')
            progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
            double thr1 = spThr1.getValue() as double
            double thr2 = spThr2.getValue() as double
            double thr3 = spThr3.getValue() as double
            boolean teaching = teachChk.isSelected()
            def worker = new Thread({
                def res = recount(thr1, thr2, thr3, teaching)
                javafx.application.Platform.runLater { handleResult(res) }
            }, 'Modul3Recount')
            worker.setDaemon(true); worker.start()
        })

        def alwaysTop = new javafx.scene.control.CheckBox('Üstte tut'); alwaysTop.setSelected(true)
        alwaysTop.selectedProperty().addListener(
            { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
        def copyBtn = new javafx.scene.control.Button('Kopyala')
        copyBtn.setOnAction({
            def cb = javafx.scene.input.Clipboard.getSystemClipboard()
            def c = new javafx.scene.input.ClipboardContent(); c.putString(resultArea.getText()); cb.setContent(c)
        })
        def closeBtn = new javafx.scene.control.Button('Kapat'); closeBtn.setOnAction({ stage.close() })

        def footer = new javafx.scene.control.Label('QuPath Atölye Scriptleri · araştırma/eğitim amaçlı')
        footer.setMaxWidth(Double.MAX_VALUE)
        footer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;')

        def spacer = new javafx.scene.layout.Region()
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
        def btnRow = new javafx.scene.layout.HBox(8, alwaysTop, spacer, copyBtn, recountBtn, runBtn, closeBtn)
        btnRow.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

        def content = new javafx.scene.layout.VBox(10, title, info, adv, teachChk, status, progress, resultArea)
        content.setPadding(new javafx.geometry.Insets(14))
        javafx.scene.layout.VBox.setVgrow(resultArea, javafx.scene.layout.Priority.ALWAYS)
        def bottom = new javafx.scene.layout.VBox(8, footer, btnRow)
        bottom.setPadding(new javafx.geometry.Insets(10))
        def root = new javafx.scene.layout.BorderPane()
        root.setCenter(content); root.setBottom(bottom)
        stage.setScene(new javafx.scene.Scene(root, 580, 580))
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Modül 3a açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
