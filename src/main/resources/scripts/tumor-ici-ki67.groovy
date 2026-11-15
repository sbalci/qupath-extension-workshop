/**
 * Tümör içi Ki-67 (iki yollu sihirbaz)
 * ----------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * Tek pencerede İKİ yol sunar:
 *   • Yol A — Hızlı Ki-67: patolog sınıfı `Tumor` olan anotasyon(lar)ı çizip
 *     SEÇER; birleşimleri içinde DAB eşiğiyle pozitif çekirdek tespiti yapılır.
 *   • Yol B — Hücre sınıflandırıcı: resmî QuPath "cell classification" akışı.
 *     Hücre tespiti → pürüzsüzleştirilmiş özellikler → Tumor/Other örnek hücreleri
 *     işaretle → QuPath'in Train object classifier penceresinde nesne sınıflandırıcı
 *     eğit/kaydet → uygula → Ki-67'yi YALNIZ Tumor hücrelerinde derecele. Böylece
 *     Ki-67+ lenfositler ve stromal hücreler indeksten dışlanır.
 *
 * Yol B'nin sınıflandırıcısı, İHK (Ki-67 H-DAB) lamı üzerinde eğitilen ayrı bir
 * NESNE (hücre) sınıflandırıcısıdır; Tümör/Stroma modülünün H&E PİKSEL sınıflandırıcısından
 * farklıdır. Yoğunluk derecelemesi (DAB eşiği) ayrı, alt adımdır.
 *
 * KULLANIM:
 *   1. Bir Ki-67 H-DAB lamı açın (Yol B için ayrıca bir proje açık olmalı)
 *   2. Bu betiği çalıştırın → açılan pencerede yolu seçin
 *
 * Çıktılar yalnız sayım, alan, yoğunluk ve pozitif yüzdesidir.
 * Klinik kategori, eşik veya yorum üretilmez.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.PathObjects
import qupath.lib.roi.RoiTools
import qupath.lib.scripting.QP

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

// ── Ön koşul düzeltme için paketli yardımcı betikleri çalıştırma ──
def bundledScript = { String name ->
    try {
        Class.forName('io.github.sbalci.qupath.workshop.WorkshopResources')
            .getMethod('getBundledScript', String.class).invoke(null, name)
    } catch (Throwable t) { null }
}
def launchBundled = { String name ->
    def text = bundledScript(name)
    if (text == null) return false
    def runner = new Thread({
        try { new groovy.lang.GroovyShell(this.class.classLoader).evaluate(text, name) }
        catch (Throwable err) { println "Alt betik hatası (${name}): ${err}" }
    }, "Modul7Wizard-${name}")
    runner.setDaemon(true); runner.start()
    return true
}
def menuHint = { String where ->
    Dialogs.showMessageDialog('Eklenti gerekli',
        "${workshopText('script.2da9535b4302', 'Bu adım atölye eklentisini gerektirir. Menüden çalıştırın:\n')}${where}")
}

// ── Önceki sonuç: SORULMADAN silinmez (ev kuralı: sonuç yazan her betik önce sorar) ──
// Ekle (varsayılan): hiçbir şey silinmez; yeni özet "Ki-67 Tümör Özet (2)", "(3)" … adını alır.
// Değiştir: yalnız bu betiğin özetleri (TAM ad ya da "ad (N)", önek eşleşmesi YOK) ve DOĞRUDAN
// içlerindeki hücre tespitleri silinir; içlerindeki anotasyonlar korunur (keepChildren = true).
def KI67_BASE = 'Ki-67 Tümör Özet'              // Yol A özet anotasyonunun adı (ilk set)
def KI67_REGION_BASE = 'Ki-67 tespit alanı'     // Yol B'de önceki tespitli bölge için kopya bölgenin adı
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
// Bu betiğin seti mi? Yalnız TAM ad ya da "ad (N)" — önek eşleşmesi YOK:
// kullanıcının "Ki-67 Tümör Özet düzeltmelerim" diye adlandırdığı anotasyon süpürülmez.
def isOwnSetName = { String name, String base ->
    if (name == null) return false
    if (name == base) return true
    return name.startsWith(base + ' (') && name.endsWith(')') &&
        name.substring(base.length() + 2, name.length() - 1).isInteger() &&
        (name.substring(base.length() + 2, name.length() - 1) as int) >= 2
}
def ownSetIndex = { String name, String base ->
    return (name == base) ? 1 : (name.substring(base.length() + 2, name.length() - 1) as int)
}
def prevOwnObjects = { hierarchy, String base ->
    if (hierarchy == null) return []
    return hierarchy.getAnnotationObjects().findAll { isOwnSetName(it.getName(), base) }
}
def nextOwnSetName = { hierarchy, String base ->
    def used = prevOwnObjects(hierarchy, base).collect { ownSetIndex(it.getName(), base) }
    if (used.isEmpty()) return base
    return base + ' (' + ((used.max() as int) + 1) + ')'
}
// Yol A özetinin DOĞRUDAN içindeki hücre tespitleri = bu betiğin kendi çıktısı (PositiveCellDetection).
def ki67OwnDetections = { summaryObj ->
    return summaryObj.getChildObjects().findAll { it.isDetection() }
}

// ──────────────────────────────────────────────────────────────
// Yol A — Hızlı Ki-67: seçili Tumor anotasyonlarında DAB eşiğiyle ölçüm.
//   Dönüş: [ok:true, text:…] | [ok:false, error:…]. Pencere kapanmadan tekrar çağrılır.
// ──────────────────────────────────────────────────────────────
def runDetection = { double nuclear1, double nuclear2, double nuclear3, String mode = 'add' ->
    if (!(nuclear1 < nuclear2 && nuclear2 < nuclear3))
        return [ok:false, error:'Eşik sırası hatalı: 1+ < 2+ < 3+ olmalıdır.']

    def imageData = QP.getCurrentImageData()
    if (imageData == null) return [ok:false, error:'Görüntü açık değil.']
    String typeName = (imageData.getImageType()?.toString() ?: '').toUpperCase(java.util.Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
    if (!typeName.contains('H_DAB')) return [ok:false, error:'Image type Brightfield (H-DAB) olmalı (Yardımcılar → Görüntü tipi ayarla).']
    def calibration = imageData.getServer().getPixelCalibration()
    double pixelWidth  = calibration.getPixelWidthMicrons()
    double pixelHeight = calibration.getPixelHeightMicrons()
    if (!(pixelWidth > 0) || !(pixelHeight > 0))
        return [ok:false, error:'Piksel kalibrasyonu yok; alan ve yoğunluk hesaplanamaz.']

    // Seçili Tumor anotasyonlarını doğrula — her çalıştırmada kontrol edilir.
    def tumorObjects = QP.getSelectedObjects().findAll {
        it.isAnnotation() && it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Tumor'
    }
    if (tumorObjects.isEmpty())
        return [ok:false, error:
            'Ki-67 lamında ölçülecek tümör epitelini anotasyonla çevreleyin, sınıfını tam\n' +
            'olarak "Tumor" yapın ve bu anotasyon(lar)ı SEÇİN. Betik yalnız seçili Tumor\n' +
            'anotasyonlarını ölçer; H&E sınıflandırıcısı bu İHK lamına otomatik uygulanmaz.']

    def tumorUnion = RoiTools.union(tumorObjects.collect { it.getROI() })
    if (tumorUnion == null || tumorUnion.isEmpty())
        return [ok:false, error:'Tumor anotasyonları geçerli bir alan oluşturmuyor.']

    // Önceki sonuç (bu betiğin özetleri: tam ad ya da "(N)") SORULMADAN silinmez. Kip çağırandan gelir:
    //   ekle     → hiçbir şey silinmez; yeni özet sıradaki adı alır ("Ki-67 Tümör Özet (2)" …).
    //   değiştir → önceki özetler ve doğrudan içlerindeki hücre tespitleri, yeni ölçüm BAŞARIYLA
    //              bittikten sonra silinir (tespit başarısız olursa eski sonuç sağlam kalır).
    // Önceki sonuç varken kip belirtilmediyse hiçbir şey yazılmadan durulur (fail closed).
    def hierA = imageData.getHierarchy()
    def prevSummaries = prevOwnObjects(hierA, KI67_BASE)
    if (!prevSummaries.isEmpty() && mode != MODE_ADD && mode != MODE_REPLACE)
        return [ok:false, error:workshopText('script.0f11d2259312', 'Bu lamda önceki bir Ki-67 sonucu var ve ne yapılacağı belirtilmedi. Hiçbir şey yazılmadı.')]
    boolean replaceRun = (mode == MODE_REPLACE) && !prevSummaries.isEmpty()
    String summaryName = (!prevSummaries.isEmpty() && !replaceRun) ? nextOwnSetName(hierA, KI67_BASE) : KI67_BASE

    def summary = PathObjects.createAnnotationObject(tumorUnion)
    summary.setName(summaryName)
    QP.addObjects([summary])

    String detectionChannel = atolyeS('atolye.detectionChannel', 'Hematoxylin OD')
    long t0 = System.currentTimeMillis()
    QP.selectObjects(summary)
    QP.runPlugin(
        'qupath.imagej.detect.cells.PositiveCellDetection',
        '{' +
            '"detectionImageBrightfield":"' + detectionChannel + '",' +
            '"requestedPixelSizeMicrons":' + atolyeD('atolye.pixelSize', 0.5) + ',' +
            '"backgroundRadiusMicrons":' + atolyeD('atolye.backgroundRadius', 8.0) + ',' +
            '"medianRadiusMicrons":' + atolyeD('atolye.medianRadius', 0.0) + ',' +
            '"sigmaMicrons":' + atolyeD('atolye.sigma', 1.5) + ',' +
            '"minAreaMicrons":' + atolyeD('atolye.minArea', 10.0) + ',' +
            '"maxAreaMicrons":' + atolyeD('atolye.maxArea', 400.0) + ',' +
            '"threshold":' + atolyeD('atolye.detectionThreshold', 0.1) + ',' +
            '"watershedPostProcess":' + atolyeB('atolye.watershed', true) + ',' +
            '"cellExpansionMicrons":' + atolyeD('atolye.cellExpansionNuclear', 5.0) + ',' +
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
    double elapsed = (System.currentTimeMillis() - t0) / 1000.0

    def detections = summary.getChildObjects().findAll { it.isDetection() }
    if (detections.isEmpty()) {
        // Bu çalıştırmanın KENDİ özeti (yukarıda referansla oluşturuldu) — başka nesne değil.
        QP.removeObjects([summary], true)
        QP.fireHierarchyUpdate()
        return [ok:false, error:'Tumor ROI içinde çekirdek tespit edilmedi. Tespit ve boya vektörü ayarlarını kontrol edin.']
    }
    summary.setLocked(true)

    // Değiştir: yeni ölçüm başarılı → ÖNCEKİ özetler (yukarıda, yeni özet eklenmeden önce alındı) şimdi silinir.
    // Önce doğrudan içlerindeki hücre tespitleri (bu betiğin çıktısı), sonra özet; diğer alt nesneler korunur.
    int removedSummaries = 0
    int removedDetections = 0
    if (replaceRun) {
        prevSummaries.each { oldSummary ->
            def oldDets = ki67OwnDetections(oldSummary)
            if (!oldDets.isEmpty()) QP.removeObjects(oldDets, true)
            removedDetections += oldDets.size()
        }
        QP.removeObjects(prevSummaries, true)
        removedSummaries = prevSummaries.size()
    }

    def summarize = { collection, roi ->
        int n0 = 0, n1 = 0, n2 = 0, n3 = 0
        collection.each { detection ->
            String className = detection.getPathClass()?.getName() ?: ''
            if (className.contains('3+')) n3++
            else if (className.contains('2+')) n2++
            else if (className.contains('1+')) n1++
            else n0++
        }
        int total = collection.size()
        int positive = n1 + n2 + n3
        double positivePct = total > 0 ? 100.0 * positive / total : Double.NaN
        double areaMm2 = roi == null ? 0.0 : roi.getArea() * pixelWidth * pixelHeight / 1_000_000.0
        double density = areaMm2 > 0 ? total / areaMm2 : Double.NaN
        return [total: total, positive: positive, n0: n0, n1: n1, n2: n2, n3: n3,
                positivePct: positivePct, area: areaMm2, density: density]
    }

    def writeMetrics = { object, values ->
        object.measurements['ROI alanı (mm2)']              = values.area
        object.measurements['Toplam çekirdek']              = values.total as double
        object.measurements['Pozitif çekirdek']             = values.positive as double
        object.measurements['Negatif çekirdek']             = values.n0 as double
        object.measurements['1+ çekirdek']                  = values.n1 as double
        object.measurements['2+ çekirdek']                  = values.n2 as double
        object.measurements['3+ çekirdek']                  = values.n3 as double
        object.measurements['Ki-67 pozitif (%)']            = values.positivePct
        object.measurements['Çekirdek yoğunluğu (hücre/mm2)'] = values.density
    }

    // Birleşik Tumor ROI sonucu: örtüşen anotasyonlar iki kez sayılmaz.
    def aggregate = summarize(detections, tumorUnion)
    writeMetrics(summary, aggregate)
    summary.measurements['Tumor ROI sayısı']  = tumorObjects.size() as double
    summary.measurements['DAB eşiği 1+']      = nuclear1
    summary.measurements['DAB eşiği 2+']      = nuclear2
    summary.measurements['DAB eşiği 3+']      = nuclear3

    // Her kaynak Tumor ROI için aynı tespitlerden centroid-temelli alt sonuç üret.
    tumorObjects.each { tumor ->
        def roi = tumor.getROI()
        def local = detections.findAll { detection ->
            def detectionROI = detection.getROI()
            detectionROI != null && roi.contains(detectionROI.getCentroidX(), detectionROI.getCentroidY())
        }
        writeMetrics(tumor, summarize(local, roi))
    }
    QP.fireHierarchyUpdate()

    def percent = { int count, int total2 -> total2 > 0 ? 100.0 * count / total2 : Double.NaN }
    def fmt = { double value, String pattern ->
        Double.isFinite(value) ? String.format(java.util.Locale.US, pattern, value) : 'hesaplanamadı'
    }

    def body = new StringBuilder()
    body << 'TÜMÖR ROI İÇİNDE Kİ-67 ÖLÇÜMÜ (Yol A)\n'
    body << '════════════════════════════════════════\n\n'
    body << String.format(java.util.Locale.US, 'Tumor ROI sayısı       : %,d%n', tumorObjects.size())
    body << String.format(java.util.Locale.US, 'Birleşik alan          : %.3f mm²%n', aggregate.area)
    body << String.format(java.util.Locale.US, 'Toplam çekirdek        : %,d%n', aggregate.total)
    body << String.format(java.util.Locale.US, 'Pozitif çekirdek       : %,d%n', aggregate.positive)
    body << "Ki-67 pozitif (%)     : ${fmt(aggregate.positivePct, '%.2f%%')}\n"
    body << "Çekirdek yoğunluğu    : ${fmt(aggregate.density, '%.1f hücre/mm²')}\n\n"
    body << 'YOĞUNLUK GRUPLARI\n'
    body << '────────────────────────────────────────\n'
    body << String.format(java.util.Locale.US, 'Negatif : %,d  (%s)%n', aggregate.n0, fmt(percent(aggregate.n0, aggregate.total), '%.2f%%'))
    body << String.format(java.util.Locale.US, '1+      : %,d  (%s)%n', aggregate.n1, fmt(percent(aggregate.n1, aggregate.total), '%.2f%%'))
    body << String.format(java.util.Locale.US, '2+      : %,d  (%s)%n', aggregate.n2, fmt(percent(aggregate.n2, aggregate.total), '%.2f%%'))
    body << String.format(java.util.Locale.US, '3+      : %,d  (%s)%n', aggregate.n3, fmt(percent(aggregate.n3, aggregate.total), '%.2f%%'))
    body << '\nTEKNİK KAYIT\n'
    body << '────────────────────────────────────────\n'
    body << "Tespit kanalı          : ${detectionChannel}\n"
    body << String.format(java.util.Locale.US, 'DAB eşikleri           : %.2f / %.2f / %.2f%n', nuclear1, nuclear2, nuclear3)
    body << String.format(java.util.Locale.US, 'İşlem süresi           : %.1f sn%n', elapsed)
    body << 'Analiz sınırı           : patolog tarafından seçilen Tumor ROI birleşimi\n\n'
    if (!prevSummaries.isEmpty() && !replaceRun) {
        // Ekle: önceki özet duruyor; Tumor anotasyonlarına yazılan ölçümler BU çalıştırmayı gösterir.
        body << String.format(java.util.Locale.US, workshopText('script.c4402f967aba', 'Özet anotasyonu: %s. Bu lamda önceki Ki-67 sonucu da duruyor: %s. Hiçbir şey silinmedi; eski özeti kaldırmak için o addaki anotasyonu seçip silin ya da bir sonraki çalıştırmada "Değiştir"i seçin. Tumor anotasyonlarınıza yazılan ölçümler (ROI alanı, çekirdek sayıları, Ki-67 pozitif yüzdesi ve yoğunluk) BU çalıştırmayı gösterir; önceki özet anotasyonu eski değerleri göstermeye devam eder.'), summaryName,
            prevSummaries.collect { it.getName() }.unique().sort().join(', ')) << '\n\n'
    } else if (replaceRun) {
        body << String.format(java.util.Locale.US, workshopText('script.46f3c5cb9752', 'Önceki sonuç değiştirildi: %,d özet anotasyonu ve doğrudan içlerindeki %,d hücre tespiti silindi (özetlerin içindeki anotasyonlar korundu).'), removedSummaries, removedDetections) << '\n\n'
    }
    body << 'Bu çıktı betimsel bir ölçümdür; klinik yorum veya kategori üretmez.\n'
    body << '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'

    println String.format(java.util.Locale.US, 'Tümör içi Ki-67 modülü Yol A tamamlandı: n=%d, Ki-67 pozitif=%s',
        aggregate.total, fmt(aggregate.positivePct, '%.2f%%'))
    return [ok:true, text:body.toString()]
}

// ──────────────────────────────────────────────────────────────
// Durum + Yol B yardımcı kapanışları
// ──────────────────────────────────────────────────────────────
def computeState = { ->
    def st = [image:false, project:false, hDab:false, calib:false, tumor:0, other:0, classifiers:[], selectedArea:0]
    def imageData = QP.getCurrentImageData()
    st.image = imageData != null
    def project = QP.getProject()
    st.project = project != null
    if (imageData != null) {
        def typeName = (imageData.getImageType()?.toString() ?: '').toUpperCase(java.util.Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
        st.hDab = typeName.contains('H_DAB')
        def cal = imageData.getServer()?.getPixelCalibration()
        st.calib = cal != null && cal.getAveragedPixelSizeMicrons() > 0
        QP.getAnnotationObjects().each {
            def n = it.getPathClass()?.getName()
            if (it.getROI()?.isArea() && n == 'Tumor') st.tumor++
            if (it.getROI()?.isArea() && n == 'Other') st.other++
        }
        st.selectedArea = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI()?.isArea() }.size()
    }
    if (project != null) st.classifiers = new ArrayList(project.getObjectClassifiers().getNames())
    return st
}

// Bir hücrenin taban sınıfı Tumor mu? ("Tumor" ya da yoğunluk sonrası "Tumor: 1+"…)
def isTumorCell = { c ->
    def pc = c.getPathClass()
    pc != null && (pc.getName() == 'Tumor' || pc.getParentClass()?.getName() == 'Tumor')
}

// Yol B — DETECT: seçili ROI içinde yoğunluksuz hücre tespiti.
// Bölgenin altındaki önceki tespitler SORULMADAN silinmez: yalnız kullanıcı "Değiştir"i seçtiyse (mode=replace)
// ve yalnız BU bölgenin altındakiler silinir — lamdaki diğer anotasyonların (ör. Hücre Tespiti /
// Nükleer Boya modülleri) tespitleri korunur. Tespit başarısız olursa silinenler geri konur.
def runCellDetection = { regionAnno, String mode = 'add' ->
    def removedExisting = []
    try {
        def existing = regionAnno.getChildObjects().findAll { it.isDetection() }
        if (!existing.isEmpty() && mode != MODE_REPLACE)
            return [ok:false, error:workshopText('script.37eb5f471b98', 'Bu bölgenin altında önceki tespitler var ve ne yapılacağı belirtilmedi. Hiçbir şey silinmedi.')]
        if (!existing.isEmpty()) {
            QP.removeObjects(existing, true)
            removedExisting = existing
        }
        String detectionChannel = atolyeS('atolye.detectionChannel', 'Hematoxylin OD')
        long t0 = System.currentTimeMillis()
        QP.selectObjects(regionAnno)
        QP.runPlugin(
            'qupath.imagej.detect.cells.WatershedCellDetection',
            '{' +
                '"detectionImageBrightfield":"' + detectionChannel + '",' +
                '"requestedPixelSizeMicrons":' + atolyeD('atolye.pixelSize', 0.5) + ',' +
                '"backgroundRadiusMicrons":' + atolyeD('atolye.backgroundRadius', 8.0) + ',' +
                '"medianRadiusMicrons":' + atolyeD('atolye.medianRadius', 0.0) + ',' +
                '"sigmaMicrons":' + atolyeD('atolye.sigma', 1.5) + ',' +
                '"minAreaMicrons":' + atolyeD('atolye.minArea', 10.0) + ',' +
                '"maxAreaMicrons":' + atolyeD('atolye.maxArea', 400.0) + ',' +
                '"threshold":' + atolyeD('atolye.detectionThreshold', 0.1) + ',' +
                '"watershedPostProcess":' + atolyeB('atolye.watershed', true) + ',' +
                '"cellExpansionMicrons":' + atolyeD('atolye.cellExpansionNuclear', 5.0) + ',' +
                '"includeNuclei":true,' +
                '"smoothBoundaries":true,' +
                '"makeMeasurements":true' +
            '}'
        )
        double elapsed = (System.currentTimeMillis() - t0) / 1000.0
        def cells = regionAnno.getChildObjects().findAll { it.isDetection() }
        if (cells.isEmpty()) {
            if (!removedExisting.isEmpty()) { regionAnno.addChildObjects(removedExisting); QP.fireHierarchyUpdate() }
            return [ok:false, error:'ROI içinde çekirdek tespit edilmedi. Boya vektörü/ayarlarını kontrol edin.']
        }
        println String.format(java.util.Locale.US, 'Tümör içi Ki-67 modülü Yol B tespit: %,d çekirdek (%.1f sn)', cells.size(), elapsed)
        return [ok:true, count:cells.size(), removed:removedExisting.size()]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
    }
}

// Yol B — FEATURES: pürüzsüzleştirilmiş (komşuluk) özellikler. Başarısız olursa atlanır.
def addSmoothedFeatures = { regionAnno ->
    try {
        QP.selectObjects(regionAnno)
        QP.runPlugin('qupath.lib.plugins.objects.SmoothFeaturesPlugin',
            '{"fwhmMicrons":25.0,"smoothWithinClasses":false}')
        return [ok:true]
    } catch (Throwable t) {
        println 'Tümör içi Ki-67 modülü Yol B: özellik pürüzsüzleştirme atlandı — ' + t.getClass().getSimpleName()
        return [ok:false, error: t.getMessage() ?: '']
    }
}

// Yol B — APPLY: kayıtlı nesne sınıflandırıcıyı bu bölgenin tespitlerine uygula.
def applyClassifier = { regionAnno, String name ->
    try {
        def cells = regionAnno.getChildObjects().findAll { it.isDetection() }
        if (cells.isEmpty()) return [ok:false, error:'Uygulanacak tespit yok.']
        QP.selectObjects(cells)
        QP.runObjectClassifier(name)
        QP.fireHierarchyUpdate()
        int tumor = 0, other = 0, unclassified = 0
        cells.each { c ->
            def pc = c.getPathClass()
            def nm = pc?.getName()
            def base = pc?.getParentClass()?.getName()
            if (nm == 'Tumor' || base == 'Tumor') tumor++
            else if (nm == 'Other' || base == 'Other') other++
            else unclassified++
        }
        println String.format(java.util.Locale.US, 'Tümör içi Ki-67 modülü Yol B sınıflandırma: Tumor=%,d Other=%,d sınıfsız=%,d', tumor, other, unclassified)
        return [ok:true, tumorCount:tumor, otherCount:other, unclassified:unclassified]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
    }
}

// Yol B — INTENSITY: yalnız Tumor hücrelerini DAB eşiğiyle derecele → Ki-67 LI.
def scoreIntensity = { regionAnno, double n1, double n2, double n3 ->
    if (!(n1 < n2 && n2 < n3))
        return [ok:false, error:'Eşik sırası hatalı: 1+ < 2+ < 3+ olmalıdır.']
    try {
        def cells = regionAnno.getChildObjects().findAll { it.isDetection() }
        def tumorCells = cells.findAll(isTumorCell)
        if (tumorCells.isEmpty())
            return [ok:false, error:'Tumor sınıfında hücre yok — önce sınıflandırıcıyı uygulayın.']
        QP.setIntensityClassifications(tumorCells, 'Nucleus: DAB OD mean', n1, n2, n3)
        QP.fireHierarchyUpdate()

        int tNeg = 0, t1 = 0, t2 = 0, t3 = 0
        tumorCells.each { c ->
            String nm = c.getPathClass()?.getName() ?: ''
            if (nm.contains('3+')) t3++
            else if (nm.contains('2+')) t2++
            else if (nm.contains('1+')) t1++
            else tNeg++
        }
        int total = tumorCells.size()
        int positive = t1 + t2 + t3
        double li = total > 0 ? 100.0 * positive / total : Double.NaN

        double areaMm2 = Double.NaN
        try {
            def cal = QP.getCurrentImageData().getServer().getPixelCalibration()
            double pw = cal.getPixelWidthMicrons(), ph = cal.getPixelHeightMicrons()
            def roi = regionAnno?.getROI()
            if (roi != null && pw > 0 && ph > 0) areaMm2 = roi.getArea() * pw * ph / 1_000_000.0
        } catch (Throwable ignored) { }

        if (regionAnno != null) {
            regionAnno.measurements['Tumor hücre toplamı']   = total as double
            regionAnno.measurements['Tumor: Pozitif çekirdek'] = positive as double
            regionAnno.measurements['Ki-67 LI - Tumor (%)']   = li
            regionAnno.measurements['Tumor: Negatif'] = tNeg as double
            regionAnno.measurements['Tumor: 1+'] = t1 as double
            regionAnno.measurements['Tumor: 2+'] = t2 as double
            regionAnno.measurements['Tumor: 3+'] = t3 as double
            regionAnno.measurements['DAB eşiği 1+'] = n1
            regionAnno.measurements['DAB eşiği 2+'] = n2
            regionAnno.measurements['DAB eşiği 3+'] = n3
            QP.fireHierarchyUpdate()
        }

        def fmt = { double v, String p -> Double.isFinite(v) ? String.format(java.util.Locale.US, p, v) : 'hesaplanamadı' }
        def body = new StringBuilder()
        body << 'TÜMÖR HÜCRELERİNDE Kİ-67 (Yol B — nesne sınıflandırıcı)\n'
        body << '══════════════════════════════════════════════════\n\n'
        body << String.format(java.util.Locale.US, 'Tumor hücresi toplamı  : %,d%n', total)
        body << String.format(java.util.Locale.US, 'Pozitif Tumor hücre    : %,d%n', positive)
        body << "Ki-67 LI (Tumor)       : ${fmt(li, '%.2f%%')}\n"
        if (Double.isFinite(areaMm2)) body << String.format(java.util.Locale.US, 'Tespit ROI alanı       : %.3f mm²%n', areaMm2)
        body << '\nYOĞUNLUK GRUPLARI (yalnız Tumor)\n'
        body << '──────────────────────────────────────\n'
        body << String.format(java.util.Locale.US, 'Negatif : %,d%n', tNeg)
        body << String.format(java.util.Locale.US, '1+      : %,d%n', t1)
        body << String.format(java.util.Locale.US, '2+      : %,d%n', t2)
        body << String.format(java.util.Locale.US, '3+      : %,d%n', t3)
        body << '\n' + String.format(java.util.Locale.US, 'DAB eşikleri: %.2f / %.2f / %.2f%n', n1, n2, n3)
        if (regionAnno != null && isOwnSetName(regionAnno.getName(), KI67_REGION_BASE)) {
            // Önceki tespitli bölgede "Ekle": ölçümler kopya bölgeye yazıldı, özgün bölge değişmedi.
            body << '\n' + String.format(java.util.Locale.US, workshopText('script.6ed907313908', 'Bu ölçümler "%s" anotasyonuna yazıldı. Özgün bölgedeki önceki tespitler ve ölçümler değişmedi; bu sayılar yalnız bu çalıştırmayı gösterir.'), regionAnno.getName()) + '\n'
        }
        body << '\nLI yalnız nesne sınıflandırıcının Tumor dediği hücreleri kapsar;\n'
        body << 'Ki-67+ lenfositler/stromal hücreler paydadan çıkarılmıştır.\n'
        body << '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'

        println String.format(java.util.Locale.US, 'Tümör içi Ki-67 modülü Yol B tamamlandı: Tumor n=%d, Ki-67 LI=%s',
            total, fmt(li, '%.2f%%'))
        return [ok:true, text:body.toString()]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
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

// Headless: yalnız Yol A — atölye varsayılanlarıyla bir kez çalıştır + yazdır.
if (isHeadless) {
    // Önceki sonuç: başsız modda diyalog yok — `-a mevcut=ekle|degistir`. Belirtilmezse (ve önceki sonuç
    // varsa) hiçbir şey yazılmaz. Bilinmeyen değer HER durumda durdurur (fail closed).
    def scriptArgs = []
    try { if (binding.hasVariable('args')) { def a = binding.getVariable('args'); if (a != null) scriptArgs = (a as List) } } catch (Throwable ignore) {}
    def argMap = [:]
    scriptArgs.each { a -> def sa = a?.toString() ?: ''; int ia = sa.indexOf('='); if (ia > 0) argMap[sa.substring(0, ia).trim()] = sa.substring(ia + 1).trim() }
    String mevcut = argMap.mevcut == null ? null : argMap.mevcut.toLowerCase(java.util.Locale.ROOT)
    if (mevcut != null && !['ekle', 'degistir'].contains(mevcut)) {
        println String.format(java.util.Locale.US, workshopText('script.768b9abc88f3', 'HATA: mevcut yalnız \'ekle\' ya da \'degistir\' olabilir (verilen: %s). Hiçbir şey yazılmadı.'), argMap.mevcut); return
    }
    def prevHeadless = prevOwnObjects(QP.getCurrentImageData()?.getHierarchy(), KI67_BASE)
    if (!prevHeadless.isEmpty() && mevcut == null) {
        println String.format(java.util.Locale.US, workshopText('script.02e697219cf5', 'Bu lamda bu betiğin oluşturduğu %,d "Ki-67 Tümör Özet" anotasyonu zaten var. Hiçbir şey yazılmadı. Yeni bir özet eklemek için -a mevcut=ekle, önceki sonucu değiştirmek için -a mevcut=degistir ile yeniden çalıştırın.'), prevHeadless.size()); return
    }
    String headlessMode = (mevcut == 'degistir') ? MODE_REPLACE : MODE_ADD
    def r = runDetection(
        atolyeD('atolye.nuclear1', 0.20),
        atolyeD('atolye.nuclear2', 0.40),
        atolyeD('atolye.nuclear3', 0.60),
        headlessMode
    )
    println r.ok ? r.text : ("Hata: " + r.error)
    return
}

// ──────────────────────────────────────────────────────────────
// Tek pencere, adım adım render (Tümör/Stroma modülü sihirbaz deseni)
// ──────────────────────────────────────────────────────────────
def stage = null   // YALNIZ FX iş parçacığında oluşturulur (aşağıda Platform.runLater)

// CHOICE | PREREQ | RUN_A | DETECT_REGION | DETECTING | TUMOR_EXAMPLES | OTHER_EXAMPLES | TRAIN_GUIDE | APPLYING | INTENSITY
def step = new java.util.concurrent.atomic.AtomicReference('CHOICE')
def pathChoice = new java.util.concurrent.atomic.AtomicReference(null)
def summaryAnnotation = new java.util.concurrent.atomic.AtomicReference(null)
def savedClassifierName = new java.util.concurrent.atomic.AtomicReference(null)
def detectResult = new java.util.concurrent.atomic.AtomicReference(null)
def applyResult = new java.util.concurrent.atomic.AtomicReference(null)
def pathAResult = new java.util.concurrent.atomic.AtomicReference(null)
def pathBResult = new java.util.concurrent.atomic.AtomicReference(null)
def spN1Val = new java.util.concurrent.atomic.AtomicReference((Double) atolyeD('atolye.nuclear1', 0.20))
def spN2Val = new java.util.concurrent.atomic.AtomicReference((Double) atolyeD('atolye.nuclear2', 0.40))
def spN3Val = new java.util.concurrent.atomic.AtomicReference((Double) atolyeD('atolye.nuclear3', 0.60))
def render  // forward declaration

def faIcon = { String glyphName ->
    try {
        def node = org.controlsfx.glyphfont.Glyph.create('FontAwesome|' + glyphName)
        node.setStyle('-fx-text-fill: -fx-text-base-color;')
        return node
    } catch (Throwable ignored) { return null }
}
def navButton = { String text, Closure action, String tooltip = null, String icon = null ->
    def b = new javafx.scene.control.Button(text); b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    if (icon) { def g = faIcon(icon); if (g != null) b.setGraphic(g) }
    return b
}
def busyBar = { ->
    def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb
}

// ── Önceki sonuç soruları (FX iş parçacığında, işlemden ÖNCE çağrılır) ──────────────
// Dönüş: 'add' | 'replace' | null (İptal → hiçbir şey çalışmaz, hiçbir şey yazılmaz).
// Önceki sonuç yoksa sormadan 'add' döner. Pencere üstte-kal iken diyalog arkada kalmasın diye geçici kapatılır.
def withStageBelow = { Closure body ->
    if (stage != null) stage.setAlwaysOnTop(false)
    try { return body() } finally { if (stage != null) stage.setAlwaysOnTop(true) }
}
// Yol A: önceki "Ki-67 Tümör Özet" (tam ad ya da "(N)") anotasyonları.
def askExistingA = { hierarchy ->
    def prev = prevOwnObjects(hierarchy, KI67_BASE)
    if (prev.isEmpty()) return MODE_ADD
    int nDet = prev.inject(0) { acc, o -> acc + ki67OwnDetections(o).size() } as int
    String optAdd = workshopText('script.1e749978a0e7', 'Ekle — öncekine dokunma, yeni bir özet olarak ekle (varsayılan)')
    String optRep = workshopText('script.718cddc070e2', 'Değiştir — önceki özet ve doğrudan içindeki hücre tespitleri silinir')
    def pick = withStageBelow {
        Dialogs.showChoiceDialog(workshopText('script.430d53352d5b', 'Önceki Ki-67 sonucu'),
            String.format(java.util.Locale.US, workshopText('script.f6ee0826b713', 'Bu lamda zaten %,d "Ki-67 Tümör Özet" anotasyonu var (doğrudan içlerinde toplam %,d hücre tespiti).\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni sonuç ayrı bir özet olur ("Ki-67 Tümör Özet (2)" gibi). Lamda iki sonuç birlikte durur.\n\n"Değiştir" seçilirse yeni ölçüm başarıyla bittikten sonra önceki özet anotasyonları ve doğrudan içlerindeki hücre tespitleri silinir — bu tespitlerde elle yaptığınız düzeltmeler de gider. Özetlerin içine çizdiğiniz diğer anotasyonlara dokunulmaz.\n\nİptal: hiçbir şey çalıştırılmaz, hiçbir şey yazılmaz.\n\nÖnceki Ki-67 sonucu ne olsun?'), prev.size(), nDet),
            [optAdd, optRep], optAdd)
    }
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = withStageBelow {
            Dialogs.showConfirmDialog(workshopText('script.fff8bd9bffe0', 'Önceki Ki-67 sonucu silinecek'),
                String.format(java.util.Locale.US, workshopText('script.327217399e62', '%,d "Ki-67 Tümör Özet" anotasyonu ile doğrudan içlerindeki %,d hücre tespiti silinecek. Bu tespitlerde elle yaptığınız düzeltmeler KAYBOLUR. Bu adı elle verdiğiniz bir anotasyon da, betiğin kendi özetinden ayırt edilemediği için silinir. Özetlerin içindeki diğer anotasyonlar korunur.\n\nDevam edilsin mi?'), prev.size(), nDet))
        }
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}
// Yol B: seçili bölgenin altında önceki hücre tespitleri var mı?
def askExistingB = { regionAnno ->
    def existingDets = regionAnno.getChildObjects().findAll { it.isDetection() }
    if (existingDets.isEmpty()) return MODE_ADD
    String optAdd = workshopText('script.c13fa0d17d1c', 'Ekle — öncekine dokunma, tespiti alanın yeni bir kopyasında yap (varsayılan)')
    String optRep = workshopText('script.7bdb766199cb', 'Değiştir — bu bölgenin altındaki önceki tespitler silinir')
    def pick = withStageBelow {
        Dialogs.showChoiceDialog(workshopText('script.33c0a605d048', 'Bölgede önceki tespitler var'),
            String.format(java.util.Locale.US, workshopText('script.8a73b3757999', 'Seçili bölgenin altında zaten %,d hücre tespiti var.\n\n"Ekle" seçilirse hiçbir şey silinmez; tespit, aynı alanın yeni bir kopyasında ("Ki-67 tespit alanı" adlı anotasyon) yapılır ve sonraki adımlar o kopya üzerinde çalışır. Özgün bölge ve altındaki tespitler olduğu gibi kalır.\n\n"Değiştir" seçilirse bu bölgenin altındaki tespitler silinip yeniden üretilir. Tespitlerin nereden geldiği ayırt edilemez; başka bir modülle ya da elle eklenenler de silinir. Başka anotasyonların altındaki tespitlere dokunulmaz.\n\nİptal: hiçbir şey çalıştırılmaz, hiçbir şey yazılmaz.\n\nBölgedeki önceki tespitler ne olsun?'), existingDets.size()),
            [optAdd, optRep], optAdd)
    }
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = withStageBelow {
            Dialogs.showConfirmDialog(workshopText('script.633240bd9ac7', 'Bölgedeki önceki tespitler silinecek'),
                String.format(java.util.Locale.US, workshopText('script.1dbc7aae12f6', 'Seçili bölgenin altındaki %,d hücre tespiti silinecek. Başka bir modülle ya da elle eklenenler ve elle yaptığınız düzeltmeler dahil KAYBOLUR. Yeni tespit hiç hücre bulamazsa silinenler geri konur.\n\nDevam edilsin mi?'), existingDets.size()))
        }
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}

// Eşik + sonuç paneli — hem Yol A (Hızlı Ki-67) hem Yol B (yoğunluk) yerinde tekrar çalışır.
def buildScorePanel = { content, buttons, String infoText, boolean advExpanded, Closure runner, resultRef, Closure backAction, Closure preAsk = null ->
    def info = new javafx.scene.control.Label(infoText); info.setWrapText(true)
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
    def spN1 = new javafx.scene.control.Spinner(0.0, 1.0, (double) spN1Val.get(), 0.01)
    def spN2 = new javafx.scene.control.Spinner(0.0, 1.0, (double) spN2Val.get(), 0.01)
    def spN3 = new javafx.scene.control.Spinner(0.0, 1.0, (double) spN3Val.get(), 0.01)
    [spN1, spN2, spN3].each { sp ->
        sp.setEditable(true); sp.setPrefWidth(110)
        sp.focusedProperty().addListener({ obs, was, now -> if (!now) commitSpinner(sp) } as javafx.beans.value.ChangeListener)
    }
    def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(6); grid.setPadding(new javafx.geometry.Insets(6))
    grid.addRow(0, new javafx.scene.control.Label(workshopText('script.f67c6942d322', 'DAB eşiği 1+ (Nucleus: DAB OD mean)')), spN1)
    grid.addRow(1, new javafx.scene.control.Label(workshopText('script.369a3f9e70f8', 'DAB eşiği 2+ (Nucleus: DAB OD mean)')), spN2)
    grid.addRow(2, new javafx.scene.control.Label(workshopText('script.5e51761150fd', 'DAB eşiği 3+ (Nucleus: DAB OD mean)')), spN3)
    def adv = new javafx.scene.control.TitledPane(workshopText('script.8eca38f1b522', '⚙ DAB eşikleri'), grid); adv.setExpanded(advExpanded); adv.setAnimated(false)
    def status = new javafx.scene.control.Label(workshopText('script.86bb45e59294', 'Hazır.'))
    def progress = new javafx.scene.control.ProgressBar(); progress.setMaxWidth(Double.MAX_VALUE); progress.setVisible(false); progress.setManaged(false)
    def resultArea = new javafx.scene.control.TextArea(); resultArea.setEditable(false); resultArea.setWrapText(false); resultArea.setPrefRowCount(11)
    resultArea.setPromptText(workshopText('script.4adde9b85aed', 'Sonuçlar burada görünecek…'))
    resultArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;")
    if (resultRef.get() != null) resultArea.setText((String) resultRef.get())
    def runBtn = new javafx.scene.control.Button(workshopText('script.79ef801ff99f', 'Çalıştır')); runBtn.setDefaultButton(true)
    runBtn.setOnAction({
        [spN1, spN2, spN3].each { commitSpinner(it) }
        // Önceki sonuç varsa İŞLEMDEN ÖNCE (bu işleyici FX iş parçacığında) sor; İptal → hiçbir şey çalışmaz/yazılmaz.
        def runMode = (preAsk != null) ? preAsk() : MODE_ADD
        if (runMode == null) return
        runBtn.setDisable(true); status.setStyle(''); status.setText(workshopText('script.29f409509207', 'Çalışıyor…'))
        progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
        double a = spN1.getValue() as double, b = spN2.getValue() as double, c = spN3.getValue() as double
        spN1Val.set((Double) a); spN2Val.set((Double) b); spN3Val.set((Double) c)
        def worker = new Thread({
            def res = runner(a, b, c, runMode)
            javafx.application.Platform.runLater {
                progress.setVisible(false); progress.setManaged(false); runBtn.setDisable(false)
                if (res.ok) {
                    status.setStyle(''); status.setText(workshopText('script.404ceb1c773d', 'Tamamlandı ✅ — eşikleri değiştirip tekrar çalıştırabilirsiniz.'))
                    resultArea.setText(res.text); resultRef.set(res.text)
                } else {
                    status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + res.error)
                }
            }
        }, 'Modul7Score'); worker.setDaemon(true); worker.start()
    })
    def copyBtn = new javafx.scene.control.Button(workshopText('script.a8bcca42d9ce', 'Kopyala'))
    copyBtn.setOnAction({
        def cb = javafx.scene.input.Clipboard.getSystemClipboard()
        def cc = new javafx.scene.input.ClipboardContent(); cc.putString(resultArea.getText()); cb.setContent(cc)
    })
    content.getChildren().addAll(info, adv, status, progress, resultArea)
    javafx.scene.layout.VBox.setVgrow(resultArea, javafx.scene.layout.Priority.ALWAYS)
    buttons.getChildren().addAll(navButton(workshopText('script.7dfd804d8334', '◀ Geri'), backAction), copyBtn, runBtn)
}

// Arka plan: tespit + özellik (Yol B).
def startDetect = { regionAnno, String mode = 'add' ->
    def target = regionAnno
    boolean madeCopy = false
    // Önceki tespitleri olan bölgede "Ekle": tespit YENİ bir kopya bölgede yapılır; özgün bölge ve
    // altındaki tespitler dokunulmadan kalır. (Bu işleyici FX iş parçacığında çalışır.)
    if (mode == MODE_ADD && !regionAnno.getChildObjects().findAll { it.isDetection() }.isEmpty()) {
        def copyAnno = PathObjects.createAnnotationObject(regionAnno.getROI())
        copyAnno.setName(nextOwnSetName(QP.getCurrentHierarchy(), KI67_REGION_BASE))
        QP.addObject(copyAnno)
        target = copyAnno
        madeCopy = true
    }
    summaryAnnotation.set(target)
    step.set('DETECTING'); render()
    def worker = new Thread({
        def d = runCellDetection(target, mode)
        if (d.ok) addSmoothedFeatures(target)
        else if (madeCopy) {
            // Başarısız çalıştırmanın KENDİ kopya bölgesi (yukarıda referansla oluşturuldu) kaldırılır.
            try { QP.removeObjects([target], true); QP.fireHierarchyUpdate() } catch (Throwable ignore) {}
            summaryAnnotation.set(regionAnno)
        }
        if (d.ok) d.copy = madeCopy ? target.getName() : null
        javafx.application.Platform.runLater {
            detectResult.set(d)
            if (d.ok) { step.set('TUMOR_EXAMPLES'); render() }
            else {
                step.set('DETECT_REGION'); render()
                if (stage != null) stage.setAlwaysOnTop(false)
                Dialogs.showErrorMessage(workshopText('script.eea6902a2c8c', 'Tespit başarısız'), d.error ?: '')
                if (stage != null) stage.setAlwaysOnTop(true)
            }
        }
    }, 'Modul7Detect'); worker.setDaemon(true); worker.start()
}

// Arka plan: nesne sınıflandırıcı uygula (Yol B).
def startApply = { String name ->
    savedClassifierName.set(name)
    step.set('APPLYING'); render()
    def worker = new Thread({
        def r = applyClassifier(summaryAnnotation.get(), name)
        javafx.application.Platform.runLater {
            applyResult.set(r)
            if (r.ok) { step.set('INTENSITY'); render() }
            else {
                step.set('TRAIN_GUIDE'); render()
                if (stage != null) stage.setAlwaysOnTop(false)
                Dialogs.showErrorMessage(workshopText('script.face316a825e', 'Sınıflandırıcı uygulanamadı'), r.error ?: '')
                if (stage != null) stage.setAlwaysOnTop(true)
            }
        }
    }, 'Modul7Apply'); worker.setDaemon(true); worker.start()
}

// ── Menü vurgusu (Atölye #3) — üst menü çubuğunda bir üst-menü başlığını turuncu iç
// parıltıyla işaretler (başlık bulunamazsa tüm menü çubuğunu). SALT-GÖRSEL: menüyü
// AÇMAZ, hiçbir şeyi tıklamaz/değiştirmez (sihirbazların salt okunur sözleşmesi). Tüm
// setEffect çağrıları FX iş parçacığında; render başında ve pencere kapanınca temizlenir.
def menuHiRef = new java.util.concurrent.atomic.AtomicReference(null)   // [node, origEffect] | null
def clearMenuHighlight = { ->
    javafx.application.Platform.runLater {
        def cur = menuHiRef.getAndSet(null)
        if (cur != null) { try { ((javafx.scene.Node) cur[0]).setEffect((javafx.scene.effect.Effect) cur[1]) } catch (Throwable t) {} }
    }
}
def applyMenuHighlight = { String menuName ->
    def g = qupath.lib.gui.QuPathGUI.getInstance()
    if (g == null) return
    javafx.application.Platform.runLater {
        try {
            // önce önceki vurguyu ATOMİK geri yükle (hızlı toggle'da takılı parıltı kalmasın)
            def prev = menuHiRef.getAndSet(null)
            if (prev != null) { try { ((javafx.scene.Node) prev[0]).setEffect((javafx.scene.effect.Effect) prev[1]) } catch (Throwable t) {} }
            def mb = g.getMenuBar()
            if (mb == null) return
            javafx.scene.Node target = null
            def want = menuName?.toLowerCase(java.util.Locale.ROOT)
            if (want != null) {
                // Yalnız üst-menü başlıkları (.menu-button); '.menu' alt menülere de inip yanlış
                // düğümü işaretleyebildiğinden kullanılmaz. Bulunamazsa tüm menü çubuğuna düşülür.
                for (n in mb.lookupAll('.menu-button')) {
                    try {
                        def txt = (n instanceof javafx.scene.control.Labeled) ? ((javafx.scene.control.Labeled) n).getText() : null
                        if (txt != null && txt.toLowerCase(java.util.Locale.ROOT).contains(want)) { target = n; break }
                    } catch (Throwable t) {}
                }
            }
            boolean spot = (target != null)
            if (target == null) target = mb
            def orig = target.getEffect()
            menuHiRef.set([target, orig])
            def glow = new javafx.scene.effect.InnerShadow()
            glow.setColor(javafx.scene.paint.Color.web('#FF7A00'))   // turuncu (tur sihirbazıyla aynı)
            glow.setRadius(spot ? 8.0d : 12.0d)
            glow.setChoke(spot ? 0.85d : 0.5d)
            target.setEffect(glow)
        } catch (Throwable t) { menuHiRef.set(null) }
    }
}
def menuHighlightToggle = { String menuName ->
    def tb = new javafx.scene.control.ToggleButton(menuName + workshopText('script.ef07407189c3', ' menüsünü göster'))
    tb.setTooltip(new javafx.scene.control.Tooltip(
        workshopText('script.242839f75398', 'Üst menü çubuğunda "') + menuName + workshopText('script.f4d336f42a58', '" menüsünü turuncu çerçeveyle işaretler (menüyü açmaz, hiçbir şeyi değiştirmez).')))
    tb.setOnAction({ if (tb.isSelected()) applyMenuHighlight(menuName) else clearMenuHighlight() })
    return tb
}

render = { ->
    if (stage != null) stage.setAlwaysOnTop(true)
    clearMenuHighlight()   // #3: adım değişiminde/yenilemede takılı menü vurgusunu kaldır
    def s = computeState()
    def content = new javafx.scene.layout.VBox(10)
    content.setPadding(new javafx.geometry.Insets(14))
    def buttons = new javafx.scene.layout.HBox(8)
    buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def bodyLbl = new javafx.scene.control.Label()
    bodyLbl.setWrapText(true)
    content.getChildren().addAll(title, bodyLbl)

    def cur = step.get()
    if (cur == 'CHOICE') {
        title.setText(workshopText('script.b69c46f7853e', 'Tümör içi Ki-67 — iki yol'))
        bodyLbl.setText(
            workshopText('script.f3c7070c9a0c', 'Nasıl ilerlemek istersiniz?\n\n' +
            '• Hızlı Ki-67: sınıfı "Tumor" olan bölgeleri kendiniz çizip seçer, eşikle ölçersiniz (bugünkü yöntem).\n' +
            '• Hücre sınıflandırıcı eğit: bir nesne (hücre) sınıflandırıcısı eğitip tümör hücrelerini otomatik bulur, ' +
            'Ki-67\'yi yalnız tümör hücrelerinde ölçer (Ki-67+ lenfositler dışlanır).'))
        buttons.getChildren().addAll(
            navButton(workshopText('script.72278748133a', 'İptal'), { stage.close() }),
            navButton(workshopText('script.1d44e8657a84', 'Hızlı Ki-67 ▶'), {
                pathChoice.set('A'); step.set((s.image && s.hDab && s.calib) ? 'RUN_A' : 'PREREQ'); render()
            }, workshopText('script.ce293bbcd0f1', 'Tümör bölgesini kendiniz seçip eşikle ölçersiniz'), 'BOLT'),
            navButton(workshopText('script.8c929ab4477b', 'Hücre sınıflandırıcı eğit ▶'), {
                pathChoice.set('B'); step.set((s.image && s.hDab && s.calib && s.project) ? 'DETECT_REGION' : 'PREREQ'); render()
            }, workshopText('script.ad5819839ccb', 'Tümör hücrelerini otomatik bulan bir nesne sınıflandırıcısı eğitir'), 'FLASK'))
    } else if (cur == 'PREREQ') {
        boolean needProject = pathChoice.get() == 'B'
        title.setText(workshopText('script.fdde113b8e5d', 'Önce: görüntü tipi ve kalibrasyon') + (needProject ? workshopText('script.8cf61a083dbc', ' + proje') : ''))
        bodyLbl.setText(
            workshopText('script.89737cbbb052', 'Gerekli ön koşullar:\n\n') +
            "  ${s.image ? '✓' : '✗'}${workshopText('script.c00010ebce02', ' Açık görüntü\n')}" +
            "  ${s.hDab ? '✓' : '✗'}${workshopText('script.1cab1439b568', ' Görüntü tipi Brightfield (H-DAB)\n')}" +
            "  ${s.calib ? '✓' : '✗'}${workshopText('script.d93396f804f2', ' Piksel kalibrasyonu (µm/px)\n')}" +
            (needProject ? "  ${s.project ? '✓' : '✗'}${workshopText('script.023b402ca7ac', ' Açık proje (sınıflandırıcı kaydı için)\n')}" : '') +
            workshopText('script.ff94ab283b7b', "\nEksikleri düzeltip 'Yenile'."))
        def fixType = navButton(workshopText('script.bbf6fba1e891', 'Görüntü tipini ayarla'), {
            if (stage != null) stage.setAlwaysOnTop(false)
            if (!launchBundled('yardimci-image-type.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Görüntü tipi ayarla')
        })
        def fixCal = navButton('Kalibrasyonu ayarla', {
            if (stage != null) stage.setAlwaysOnTop(false)
            if (!launchBundled('yardimci-kalibrasyon.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu)')
        })
        content.getChildren().add(new javafx.scene.layout.HBox(8, fixType, fixCal))
        boolean ready = s.image && s.hDab && s.calib && (!needProject || s.project)
        def cont = navButton(workshopText('script.09839547cef8', 'Devam ▶'), { step.set(needProject ? 'DETECT_REGION' : 'RUN_A'); render() })
        cont.setDisable(!ready)
        buttons.getChildren().addAll(navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('CHOICE'); render() }), navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }), cont)
    } else if (cur == 'RUN_A') {
        title.setText(workshopText('script.5f7e4a504cc0', 'Hızlı Ki-67 — tümör bölgesini seçin'))
        buildScorePanel(content, buttons,
            'Sınıfı "Tumor" olan anotasyon(lar)ı çizip SEÇİN (kenarı sarı), sonra "Çalıştır".\n' +
            workshopText('script.c48f60adadd6', 'Bu lamda önceki bir "Ki-67 Tümör Özet" varsa, çalıştırmadan önce ne yapılacağı sorulur (varsayılan: hiçbir şey silinmez). Eşikleri değiştirip tekrar çalıştırabilirsiniz.'),
            false,
            { a, b, c, m -> runDetection(a, b, c, m) },
            pathAResult,
            { step.set('CHOICE'); render() },
            { askExistingA(QP.getCurrentImageData()?.getHierarchy()) })
    } else if (cur == 'DETECT_REGION') {
        title.setText(workshopText('script.4924d86d6dd7', '1/5 — Tespit bölgesini seçin'))
        bodyLbl.setText(
            workshopText('script.cdce1e2f7c64', 'Ki-67 lamında ölçmek istediğiniz tümör alanını kapsayan GENİŞ bir anotasyon çizin\n' +
            '(Brush/Polygon) ve SEÇİN. Sınıf atamanıza gerek yok — bu yalnız hücre tespitinin sınırıdır.\n\n') +
            "${workshopText('script.fdefe60273a1', 'Şu an seçili alan anotasyonu: ')}${s.selectedArea}")
        buttons.getChildren().addAll(
            navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('CHOICE'); render() }),
            navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }),
            navButton(workshopText('script.a6ba34fc5c67', 'Tespit et ▶'), {
                def sel = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI()?.isArea() }
                if (sel.isEmpty()) { Dialogs.showWarningNotification(workshopText('script.e894b6d0ac6e', 'Bölge seçili değil'), workshopText('script.a97b5d9fae33', 'Önce bir alan anotasyonu çizip SEÇİN.')); return }
                // Bölgenin altında önceki tespitler varsa işlemden ÖNCE sor (İptal → hiçbir şey çalışmaz/yazılmaz).
                def detMode = askExistingB(sel[0])
                if (detMode == null) return
                startDetect(sel[0], detMode)
            }, workshopText('script.c87420ed98c7', 'Seçili bölgede hücre tespiti + pürüzsüzleştirilmiş özellikler')))
    } else if (cur == 'DETECTING') {
        title.setText(workshopText('script.9429f7ad6229', 'Hücre tespiti + özellik hesaplama…'))
        bodyLbl.setText(workshopText('script.dd305f2429fb', 'Seçili bölgede çekirdekler tespit ediliyor ve pürüzsüzleştirilmiş özellikler ekleniyor. Lütfen bekleyin…'))
        content.getChildren().add(busyBar())
    } else if (cur == 'TUMOR_EXAMPLES') {
        def d = detectResult.get()
        title.setText(workshopText('script.c7d32438535f', '2/5 — Tümör hücresi örnekleri'))
        bodyLbl.setText(
            ((d?.ok) ? "${workshopText('script.f089332eb95f', 'Tespit edilen çekirdek: ')}${d.count}\n\n" : '') +
            ((d?.ok && d.copy) ? String.format(java.util.Locale.US, workshopText('script.b88983480b0a', 'Bu bölgede önceki tespitler vardı ve hiçbirine dokunulmadı: yeni tespit "%s" anotasyonunda yapıldı. Sonraki adımlar (sınıflandırıcı ve yoğunluk) yalnız bu kopyada çalışır; önceki tespitler özgün bölgede duruyor. Eski olanı kaldırmak için o anotasyonu ya da altındaki tespitleri kendiniz silin.'), d.copy) + '\n\n' : '') +
            ((d?.ok && (d.removed ?: 0) > 0) ? String.format(java.util.Locale.US, workshopText('script.c9e2eebbf5ad', 'Önceki sonuç değiştirildi: bölgenin altındaki %,d hücre tespiti silindi.'), d.removed) + '\n\n' : '') +
            workshopText('script.6f4d56956c30', 'Birkaç TİPİK TÜMÖR HÜCRESİ bölgesini küçük anotasyonlarla çizin ve sınıfını "Tumor" yapın\n') +
            workshopText('script.5ceebee7b2f5', '(Annotations sekmesinde sınıfı seçip "Set selected" ya da sağ tık → Set classification).\n') +
            workshopText('script.7bec4bc81f61', 'Tümör epiteline ait çekirdekleri hedefleyin; lenfosit ve stromadan uzak durun. Bitince İleri.\n\n') +
            "${workshopText('script.8dbd07801613', 'Şu an Tumor anotasyonu: ')}${s.tumor}")
        buttons.getChildren().addAll(
            navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('DETECT_REGION'); render() }),
            navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }),
            navButton(workshopText('script.3f0144008e16', 'İleri ▶'), {
                if (s.tumor > 0) { step.set('OTHER_EXAMPLES'); render() }
                else Dialogs.showWarningNotification(workshopText('script.1bc051e5f818', 'Tumor örneği yok'), workshopText('script.1cd820930a4a', 'Sınıfı "Tumor" olan en az bir alan anotasyonu çizin, sonra İleri.'))
            }))
    } else if (cur == 'OTHER_EXAMPLES') {
        title.setText(workshopText('script.30fb200b111e', '3/5 — Tümör DIŞI hücre örnekleri'))
        bodyLbl.setText(
            workshopText('script.6a2b80a33f47', 'Şimdi tümör DIŞI hücrelerin (lenfosit, fibroblast, endotel) bulunduğu bölgelerden birkaç örnek\n' +
            'çizin ve sınıfını "Other" yapın. Bu sınıf, Ki-67+ bağışıklık hücrelerini tümör paydasından\n' +
            'dışlamak için kritiktir. Bitince İleri.\n\n') +
            "${workshopText('script.901836d4443e', 'Şu an Other anotasyonu: ')}${s.other}")
        buttons.getChildren().addAll(
            navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('TUMOR_EXAMPLES'); render() }),
            navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }),
            navButton(workshopText('script.3f0144008e16', 'İleri ▶'), {
                if (s.other > 0) { step.set('TRAIN_GUIDE'); render() }
                else Dialogs.showWarningNotification(workshopText('script.f2aedb9eea76', 'Other örneği yok'), workshopText('script.bbeb69446e36', 'Sınıfı "Other" olan en az bir alan anotasyonu çizin, sonra İleri.'))
            }))
    } else if (cur == 'TRAIN_GUIDE') {
        title.setText(workshopText('script.292c4d20e93e', "4/5 — Sınıflandırıcıyı QuPath'te eğitin"))
        def instr = new javafx.scene.control.TextArea(
            '1. Üst menü:  Classify → Object classification → Train object classifier\n' +
            '2. Sınıflandırıcı türü: Random Trees (varsayılan)\n' +
            '3. "Live update" kutusunu işaretleyin — hücreler Tumor/Other renklerine boyanmalı\n' +
            '4. Sınıflandırma kararlı görünene dek Tumor/Other örnekleri eklemeye devam edin\n' +
            '5. Bir AD girip "Save" ile kaydedin (ör. ki67-tumor-other)\n' +
            '6. Kaydettiğiniz adı aşağıya yazıp "Kaydedildi ▶"')
        instr.setEditable(false); instr.setWrapText(true); instr.setPrefRowCount(7)
        def nameField = new javafx.scene.control.TextField('ki67-tumor-other'); nameField.setPrefColumnCount(24)
        content.getChildren().addAll(instr, new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('Kaydedilen ad:'), nameField))
        content.getChildren().add(menuHighlightToggle('Classify'))   // #3: Classify menüsünü göster
        buttons.getChildren().addAll(
            navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('OTHER_EXAMPLES'); render() }),
            navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }),
            navButton('Kaydedildi ▶', {
                String nm = nameField.getText()?.trim()
                if (!nm) { Dialogs.showWarningNotification('Ad gerekli', workshopText('script.6c7b6f3bc12d', 'Kaydettiğiniz sınıflandırıcının adını girin.')); return }
                def known = QP.getProject()?.getObjectClassifiers()?.getNames() ?: []
                if (!known.contains(nm)) {
                    if (stage != null) stage.setAlwaysOnTop(false)
                    Dialogs.showWarningNotification(workshopText('script.57b501859ef7', 'Sınıflandırıcı bulunamadı'),
                        "\"${nm}${workshopText('script.6c177effcc99', '" adlı nesne sınıflandırıcısı projede yok.\nMevcut: ')}${known.join(', ') ?: workshopText('script.715392eca0f8', '(yok)')}\n" +
                        workshopText('script.d52c4dfa8ba7', "Train Object Classifier penceresinde 'Save' yaptınız mı?"))
                    if (stage != null) stage.setAlwaysOnTop(true)
                    return
                }
                startApply(nm)
            }, workshopText('script.a67848fb85ec', 'Kaydedilen nesne sınıflandırıcısını tüm hücrelere uygular')))
    } else if (cur == 'APPLYING') {
        title.setText(workshopText('script.c7f9b0ba20c4', 'Sınıflandırıcı uygulanıyor…'))
        bodyLbl.setText("'${savedClassifierName.get()}${workshopText('script.05ee8a3b0c49', '\' tüm tespit edilen hücrelere uygulanıyor. Lütfen bekleyin…')}")
        content.getChildren().add(busyBar())
    } else if (cur == 'INTENSITY') {
        def ar = applyResult.get()
        title.setText(workshopText('script.0d5823625cb1', '5/5 — Tümör hücrelerinde Ki-67 yoğunluğu'))
        String applyLine = (ar?.ok) ? "Sınıflandırma: Tumor=${ar.tumorCount}, Other=${ar.otherCount}, sınıfsız=${ar.unclassified}\n" : ''
        buildScorePanel(content, buttons,
            applyLine +
            'DAB eşiklerini ayarlayıp "Çalıştır": yalnız Tumor hücreleri 0/1+/2+/3+ derecelenir.\n' +
            'Ki-67 LI = pozitif Tumor hücreleri / tüm Tumor hücreleri. Eşikleri değiştirip tekrar çalıştırabilirsiniz.',
            true,
            { a, b, c, m -> scoreIntensity(summaryAnnotation.get(), a, b, c) },
            pathBResult,
            { step.set('TRAIN_GUIDE'); render() })
    }

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(content)
    def disclaimer = new javafx.scene.control.Label(workshopText('script.04e70fc651a3', 'Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.'))
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, buttons)
    bottom.setPadding(new javafx.geometry.Insets(10))
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 640, 560))
}

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle(workshopText('script.7989d346c1b4', 'Tümör İçi Ki-67'))
        stage.setAlwaysOnTop(true)
        stage.setOnHidden({ clearMenuHighlight() })   // #3: pencere kapanınca menü vurgusunu kaldır
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.24843995974c', 'Tümör içi Ki-67 modülü açılamadı'), t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
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
