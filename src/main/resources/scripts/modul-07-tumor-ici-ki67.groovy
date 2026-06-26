/**
 * Modül 7 - Tümör içi Ki-67 (iki yollu sihirbaz)
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
 * Yol B'nin sınıflandırıcısı, İHK (Ki-67 H-DAB) slaydı üzerinde eğitilen ayrı bir
 * NESNE (hücre) sınıflandırıcısıdır; Modül 6'nın H&E PİKSEL sınıflandırıcısından
 * farklıdır. Yoğunluk derecelemesi (DAB eşiği) ayrı, alt adımdır.
 *
 * KULLANIM:
 *   1. Bir Ki-67 H-DAB slaytı açın (Yol B için ayrıca bir proje açık olmalı)
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

// ── Önkoşul düzeltme için paketli yardımcı betikleri çalıştırma ──
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
        "Bu adım atölye eklentisini gerektirir. Menüden çalıştırın:\n${where}")
}

// ──────────────────────────────────────────────────────────────
// Yol A — Hızlı Ki-67: seçili Tumor anotasyonlarında DAB eşiğiyle ölçüm.
//   Dönüş: [ok:true, text:…] | [ok:false, error:…]. Pencere kapanmadan tekrar çağrılır.
// ──────────────────────────────────────────────────────────────
def runDetection = { double nuclear1, double nuclear2, double nuclear3 ->

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
            'Ki-67 slaydında ölçülecek tümör epitelini anotasyonla çevreleyin, sınıfını tam\n' +
            'olarak "Tumor" yapın ve bu anotasyon(lar)ı SEÇİN. Betik yalnız seçili Tumor\n' +
            'anotasyonlarını ölçer; H&E sınıflandırıcısı bu İHK slaydına otomatik uygulanmaz.']

    def tumorUnion = RoiTools.union(tumorObjects.collect { it.getROI() })
    if (tumorUnion == null || tumorUnion.isEmpty())
        return [ok:false, error:'Tumor anotasyonları geçerli bir alan oluşturmuyor.']

    // Eski özet anotasyonu ve alt tespitlerini temizle (yeniden çalıştırılabilirlik).
    def oldSummaries = QP.getAnnotationObjects().findAll { it.getName() == 'Ki-67 Tümör Özet' }
    if (!oldSummaries.isEmpty()) QP.removeObjects(oldSummaries, false)

    def summary = PathObjects.createAnnotationObject(tumorUnion)
    summary.setName('Ki-67 Tümör Özet')
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
        QP.removeObjects([summary], false)
        QP.fireHierarchyUpdate()
        return [ok:false, error:'Tumor ROI içinde çekirdek tespit edilmedi. Tespit ve boya vektörü ayarlarını kontrol edin.']
    }
    summary.setLocked(true)

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
    body << 'Bu çıktı betimsel bir ölçümdür; klinik yorum veya kategori üretmez.\n'
    body << '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'

    println String.format(java.util.Locale.US, 'Modül 7 Yol A tamamlandı: n=%d, Ki-67 pozitif=%s',
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

// Yol B — DETECT: seçili ROI içinde yoğunluksuz hücre tespiti (önce tüm tespitleri sil).
def runCellDetection = { regionAnno ->
    try {
        def existing = QP.getDetectionObjects()
        if (!existing.isEmpty()) QP.removeObjects(existing, false)
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
        def cells = QP.getDetectionObjects()
        if (cells.isEmpty())
            return [ok:false, error:'ROI içinde çekirdek tespit edilmedi. Boya vektörü/ayarlarını kontrol edin.']
        println String.format(java.util.Locale.US, 'Modül 7 Yol B tespit: %,d çekirdek (%.1f sn)', cells.size(), elapsed)
        return [ok:true, count:cells.size()]
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
        println 'Modül 7 Yol B: özellik pürüzsüzleştirme atlandı — ' + t.getClass().getSimpleName()
        return [ok:false, error: t.getMessage() ?: '']
    }
}

// Yol B — APPLY: kayıtlı nesne sınıflandırıcıyı tüm tespitlere uygula.
def applyClassifier = { String name ->
    try {
        def cells = QP.getDetectionObjects()
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
        println String.format(java.util.Locale.US, 'Modül 7 Yol B sınıflandırma: Tumor=%,d Other=%,d sınıfsız=%,d', tumor, other, unclassified)
        return [ok:true, tumorCount:tumor, otherCount:other, unclassified:unclassified]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
    }
}

// Yol B — INTENSITY: yalnız Tumor hücrelerini DAB eşiğiyle derecele → Ki-67 LI.
def scoreIntensity = { regionAnno, double n1, double n2, double n3 ->
    try {
        def cells = QP.getDetectionObjects()
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
        body << '\nLI yalnız nesne sınıflandırıcının Tumor dediği hücreleri kapsar;\n'
        body << 'Ki-67+ lenfositler/stromal hücreler paydadan çıkarılmıştır.\n'
        body << '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'

        println String.format(java.util.Locale.US, 'Modül 7 Yol B tamamlandı: Tumor n=%d, Ki-67 LI=%s',
            total, fmt(li, '%.2f%%'))
        return [ok:true, text:body.toString()]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
    }
}

// Headless: yalnız Yol A — atölye varsayılanlarıyla bir kez çalıştır + yazdır.
if (isHeadless) {
    def r = runDetection(
        atolyeD('atolye.nuclear1', 0.20),
        atolyeD('atolye.nuclear2', 0.40),
        atolyeD('atolye.nuclear3', 0.60)
    )
    println r.ok ? r.text : ("Hata: " + r.error)
    return
}

// ──────────────────────────────────────────────────────────────
// Tek pencere, adım adım render (Modül 6 sihirbaz deseni)
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

// Eşik + sonuç paneli — hem Yol A (Hızlı Ki-67) hem Yol B (yoğunluk) yerinde tekrar çalışır.
def buildScorePanel = { content, buttons, String infoText, boolean advExpanded, Closure runner, resultRef, Closure backAction ->
    def info = new javafx.scene.control.Label(infoText); info.setWrapText(true)
    def spN1 = new javafx.scene.control.Spinner(0.0, 1.0, (double) spN1Val.get(), 0.01)
    def spN2 = new javafx.scene.control.Spinner(0.0, 1.0, (double) spN2Val.get(), 0.01)
    def spN3 = new javafx.scene.control.Spinner(0.0, 1.0, (double) spN3Val.get(), 0.01)
    [spN1, spN2, spN3].each { it.setEditable(true); it.setPrefWidth(110) }
    def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(6); grid.setPadding(new javafx.geometry.Insets(6))
    grid.addRow(0, new javafx.scene.control.Label('DAB eşiği 1+ (Nucleus: DAB OD mean)'), spN1)
    grid.addRow(1, new javafx.scene.control.Label('DAB eşiği 2+ (Nucleus: DAB OD mean)'), spN2)
    grid.addRow(2, new javafx.scene.control.Label('DAB eşiği 3+ (Nucleus: DAB OD mean)'), spN3)
    def adv = new javafx.scene.control.TitledPane('⚙ DAB eşikleri', grid); adv.setExpanded(advExpanded); adv.setAnimated(false)
    def status = new javafx.scene.control.Label('Hazır.')
    def progress = new javafx.scene.control.ProgressBar(); progress.setMaxWidth(Double.MAX_VALUE); progress.setVisible(false); progress.setManaged(false)
    def resultArea = new javafx.scene.control.TextArea(); resultArea.setEditable(false); resultArea.setWrapText(false); resultArea.setPrefRowCount(11)
    resultArea.setPromptText('Sonuçlar burada görünecek…')
    resultArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;")
    if (resultRef.get() != null) resultArea.setText((String) resultRef.get())
    def runBtn = new javafx.scene.control.Button('Çalıştır'); runBtn.setDefaultButton(true)
    runBtn.setOnAction({
        runBtn.setDisable(true); status.setStyle(''); status.setText('Çalışıyor…')
        progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
        double a = spN1.getValue() as double, b = spN2.getValue() as double, c = spN3.getValue() as double
        spN1Val.set((Double) a); spN2Val.set((Double) b); spN3Val.set((Double) c)
        def worker = new Thread({
            def res = runner(a, b, c)
            javafx.application.Platform.runLater {
                progress.setVisible(false); progress.setManaged(false); runBtn.setDisable(false)
                if (res.ok) {
                    status.setStyle(''); status.setText('Tamamlandı ✅ — eşikleri değiştirip tekrar çalıştırabilirsiniz.')
                    resultArea.setText(res.text); resultRef.set(res.text)
                } else {
                    status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + res.error)
                }
            }
        }, 'Modul7Score'); worker.setDaemon(true); worker.start()
    })
    def copyBtn = new javafx.scene.control.Button('Kopyala')
    copyBtn.setOnAction({
        def cb = javafx.scene.input.Clipboard.getSystemClipboard()
        def cc = new javafx.scene.input.ClipboardContent(); cc.putString(resultArea.getText()); cb.setContent(cc)
    })
    content.getChildren().addAll(info, adv, status, progress, resultArea)
    javafx.scene.layout.VBox.setVgrow(resultArea, javafx.scene.layout.Priority.ALWAYS)
    buttons.getChildren().addAll(navButton('◀ Geri', backAction), copyBtn, runBtn)
}

// Arka plan: tespit + özellik (Yol B).
def startDetect = { regionAnno ->
    summaryAnnotation.set(regionAnno)
    step.set('DETECTING'); render()
    def worker = new Thread({
        def d = runCellDetection(regionAnno)
        if (d.ok) addSmoothedFeatures(regionAnno)
        javafx.application.Platform.runLater {
            detectResult.set(d)
            if (d.ok) { step.set('TUMOR_EXAMPLES'); render() }
            else {
                step.set('DETECT_REGION'); render()
                if (stage != null) stage.setAlwaysOnTop(false)
                Dialogs.showErrorMessage('Tespit başarısız', d.error ?: '')
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
        def r = applyClassifier(name)
        javafx.application.Platform.runLater {
            applyResult.set(r)
            if (r.ok) { step.set('INTENSITY'); render() }
            else {
                step.set('TRAIN_GUIDE'); render()
                if (stage != null) stage.setAlwaysOnTop(false)
                Dialogs.showErrorMessage('Sınıflandırıcı uygulanamadı', r.error ?: '')
                if (stage != null) stage.setAlwaysOnTop(true)
            }
        }
    }, 'Modul7Apply'); worker.setDaemon(true); worker.start()
}

render = { ->
    if (stage != null) stage.setAlwaysOnTop(true)
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
        title.setText('Tümör içi Ki-67 — iki yol')
        bodyLbl.setText(
            'Nasıl ilerlemek istersiniz?\n\n' +
            '• Hızlı Ki-67: sınıfı "Tumor" olan bölgeleri kendiniz çizip seçer, eşikle ölçersiniz (bugünkü yöntem).\n' +
            '• Hücre sınıflandırıcı eğit: bir nesne (hücre) sınıflandırıcısı eğitip tümör hücrelerini otomatik bulur, ' +
            'Ki-67\'yi yalnız tümör hücrelerinde ölçer (Ki-67+ lenfositler dışlanır).')
        buttons.getChildren().addAll(
            navButton('İptal', { stage.close() }),
            navButton('Hızlı Ki-67 ▶', {
                pathChoice.set('A'); step.set((s.image && s.hDab && s.calib) ? 'RUN_A' : 'PREREQ'); render()
            }, 'Tümör bölgesini kendiniz seçip eşikle ölçersiniz', 'BOLT'),
            navButton('Hücre sınıflandırıcı eğit ▶', {
                pathChoice.set('B'); step.set((s.image && s.hDab && s.calib && s.project) ? 'DETECT_REGION' : 'PREREQ'); render()
            }, 'Tümör hücrelerini otomatik bulan bir nesne sınıflandırıcısı eğitir', 'FLASK'))
    } else if (cur == 'PREREQ') {
        boolean needProject = pathChoice.get() == 'B'
        title.setText('Önce: görüntü tipi ve kalibrasyon' + (needProject ? ' + proje' : ''))
        bodyLbl.setText(
            'Gerekli önkoşullar:\n\n' +
            "  ${s.image ? '✓' : '✗'} Açık görüntü\n" +
            "  ${s.hDab ? '✓' : '✗'} Görüntü tipi Brightfield (H-DAB)\n" +
            "  ${s.calib ? '✓' : '✗'} Piksel kalibrasyonu (µm/px)\n" +
            (needProject ? "  ${s.project ? '✓' : '✗'} Açık proje (sınıflandırıcı kaydı için)\n" : '') +
            "\nEksikleri düzeltip 'Yenile'.")
        def fixType = navButton('Görüntü tipini ayarla', {
            if (stage != null) stage.setAlwaysOnTop(false)
            if (!launchBundled('yardimci-image-type.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Görüntü tipi ayarla')
        })
        def fixCal = navButton('Kalibrasyonu ayarla', {
            if (stage != null) stage.setAlwaysOnTop(false)
            if (!launchBundled('yardimci-kalibrasyon.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu)')
        })
        content.getChildren().add(new javafx.scene.layout.HBox(8, fixType, fixCal))
        boolean ready = s.image && s.hDab && s.calib && (!needProject || s.project)
        def cont = navButton('Devam ▶', { step.set(needProject ? 'DETECT_REGION' : 'RUN_A'); render() })
        cont.setDisable(!ready)
        buttons.getChildren().addAll(navButton('◀ Geri', { step.set('CHOICE'); render() }), navButton('⟳ Yenile', { render() }), cont)
    } else if (cur == 'RUN_A') {
        title.setText('Hızlı Ki-67 — tümör bölgesini seçin')
        buildScorePanel(content, buttons,
            'Sınıfı "Tumor" olan anotasyon(lar)ı çizip SEÇİN (kenarı sarı), sonra "Çalıştır".\n' +
            'Her çalıştırma önceki "Ki-67 Tümör Özet" anotasyonunu yeniler; eşikleri değiştirip tekrar çalıştırabilirsiniz.',
            false,
            { a, b, c -> runDetection(a, b, c) },
            pathAResult,
            { step.set('CHOICE'); render() })
    } else if (cur == 'DETECT_REGION') {
        title.setText('1/5 — Tespit bölgesini seçin')
        bodyLbl.setText(
            'Ki-67 slaydında ölçmek istediğiniz tümör alanını kapsayan GENİŞ bir anotasyon çizin\n' +
            '(Brush/Polygon) ve SEÇİN. Sınıf atamanıza gerek yok — bu yalnız hücre tespitinin sınırıdır.\n\n' +
            "Şu an seçili alan anotasyonu: ${s.selectedArea}")
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('CHOICE'); render() }),
            navButton('⟳ Yenile', { render() }),
            navButton('Tespit et ▶', {
                def sel = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI()?.isArea() }
                if (sel.isEmpty()) { Dialogs.showWarningNotification('Bölge seçili değil', 'Önce bir alan anotasyonu çizip SEÇİN.'); return }
                startDetect(sel[0])
            }, 'Seçili bölgede hücre tespiti + pürüzsüzleştirilmiş özellikler'))
    } else if (cur == 'DETECTING') {
        title.setText('Hücre tespiti + özellik hesaplama…')
        bodyLbl.setText('Seçili bölgede çekirdekler tespit ediliyor ve pürüzsüzleştirilmiş özellikler ekleniyor. Lütfen bekleyin…')
        content.getChildren().add(busyBar())
    } else if (cur == 'TUMOR_EXAMPLES') {
        def d = detectResult.get()
        title.setText('2/5 — Tümör hücresi örnekleri')
        bodyLbl.setText(
            ((d?.ok) ? "Tespit edilen çekirdek: ${d.count}\n\n" : '') +
            'Birkaç TİPİK TÜMÖR HÜCRESİ bölgesini küçük anotasyonlarla çizin ve sınıfını "Tumor" yapın\n' +
            '(Annotations paneli ya da sağ tık → Set class). Tümör epiteline ait çekirdekleri hedefleyin;\n' +
            'lenfosit ve stromadan uzak durun. Bitince İleri.\n\n' +
            "Şu an Tumor anotasyonu: ${s.tumor}")
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('DETECT_REGION'); render() }),
            navButton('⟳ Yenile', { render() }),
            navButton('İleri ▶', {
                if (s.tumor > 0) { step.set('OTHER_EXAMPLES'); render() }
                else Dialogs.showWarningNotification('Tumor örneği yok', 'Sınıfı "Tumor" olan en az bir alan anotasyonu çizin, sonra İleri.')
            }))
    } else if (cur == 'OTHER_EXAMPLES') {
        title.setText('3/5 — Tümör DIŞI hücre örnekleri')
        bodyLbl.setText(
            'Şimdi tümör DIŞI hücrelerin (lenfosit, fibroblast, endotel) bulunduğu bölgelerden birkaç örnek\n' +
            'çizin ve sınıfını "Other" yapın. Bu sınıf, Ki-67+ bağışıklık hücrelerini tümör paydasından\n' +
            'dışlamak için kritiktir. Bitince İleri.\n\n' +
            "Şu an Other anotasyonu: ${s.other}")
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('TUMOR_EXAMPLES'); render() }),
            navButton('⟳ Yenile', { render() }),
            navButton('İleri ▶', {
                if (s.other > 0) { step.set('TRAIN_GUIDE'); render() }
                else Dialogs.showWarningNotification('Other örneği yok', 'Sınıfı "Other" olan en az bir alan anotasyonu çizin, sonra İleri.')
            }))
    } else if (cur == 'TRAIN_GUIDE') {
        title.setText("4/5 — Sınıflandırıcıyı QuPath'te eğitin")
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
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('OTHER_EXAMPLES'); render() }),
            navButton('⟳ Yenile', { render() }),
            navButton('Kaydedildi ▶', {
                String nm = nameField.getText()?.trim()
                if (!nm) { Dialogs.showWarningNotification('Ad gerekli', 'Kaydettiğiniz sınıflandırıcının adını girin.'); return }
                def known = QP.getProject()?.getObjectClassifiers()?.getNames() ?: []
                if (!known.contains(nm)) {
                    if (stage != null) stage.setAlwaysOnTop(false)
                    Dialogs.showWarningNotification('Sınıflandırıcı bulunamadı',
                        "\"${nm}\" adlı nesne sınıflandırıcısı projede yok.\nMevcut: ${known.join(', ') ?: '(yok)'}\n" +
                        "Train Object Classifier penceresinde 'Save' yaptınız mı?")
                    if (stage != null) stage.setAlwaysOnTop(true)
                    return
                }
                startApply(nm)
            }, 'Kaydedilen nesne sınıflandırıcısını tüm hücrelere uygular'))
    } else if (cur == 'APPLYING') {
        title.setText('Sınıflandırıcı uygulanıyor…')
        bodyLbl.setText("'${savedClassifierName.get()}' tüm tespit edilen hücrelere uygulanıyor. Lütfen bekleyin…")
        content.getChildren().add(busyBar())
    } else if (cur == 'INTENSITY') {
        def ar = applyResult.get()
        title.setText('5/5 — Tümör hücrelerinde Ki-67 yoğunluğu')
        String applyLine = (ar?.ok) ? "Sınıflandırma: Tumor=${ar.tumorCount}, Other=${ar.otherCount}, sınıfsız=${ar.unclassified}\n" : ''
        buildScorePanel(content, buttons,
            applyLine +
            'DAB eşiklerini ayarlayıp "Çalıştır": yalnız Tumor hücreleri 0/1+/2+/3+ derecelenir.\n' +
            'Ki-67 LI = pozitif Tumor hücreleri / tüm Tumor hücreleri. Eşikleri değiştirip tekrar çalıştırabilirsiniz.',
            true,
            { a, b, c -> scoreIntensity(summaryAnnotation.get(), a, b, c) },
            pathBResult,
            { step.set('TRAIN_GUIDE'); render() })
    }

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(content)
    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.')
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
        stage.setTitle('Modül 7 - Tümör içi Ki-67')
        stage.setAlwaysOnTop(true)
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Modül 7 açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
