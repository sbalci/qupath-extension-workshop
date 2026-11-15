/**
 * Yardımcı - Eşit-alan Merkez / Çevre bölütleme
 * ----------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * ESİN (KAYNAK):
 *   Bu araç, DANEELpath'in "Centre-Periphery" (eşit alanlı merkez/çevre)
 *   aracından ESİNLENİLMİŞTİR:
 *     Vieco-Martí I ve ark. (2026), Scientific Reports 16:6162 —
 *     "DANEELpath open source digital analysis tools for histopathological
 *     research in neuroblastoma models". doi:10.1038/s41598-026-37134-5
 *   DANEELpath eklentisi GPL-3.0; bu betik ise makalede TARİF EDİLEN
 *   algoritmanın bağımsız (temiz-oda) yeniden yazımıdır — GPL kaynaktan
 *   kod aktarımı YOKTUR. Ayrıntı: DANEELpath eki (ekler/daneelpath.qmd).
 *
 * NE YAPAR:
 *   SEÇİLİ alan anotasyonlarını, ŞEKİLDEN BAĞIMSIZ olarak ~EŞİT ALANLI iç/dış
 *   bölgelere böler (varsayılan: merkez + çevre = 2 bölge). Yöntem: geometriyi
 *   içe doğru buffer(−d) ile aşındırıp, alanı hedef değere getiren mesafeyi
 *   ikili arama (binary search) ile bulur; ardışık sınırların difference()'ı
 *   her bölgeyi verir (JTS geometri cebri). Bölge sayısı 2–6 seçilebilir.
 *
 * NEDEN yarıçap değil EŞİT ALAN:
 *   Yarıçap-temelli merkez/çevre tanımının iki sorunu vardır: (i) çoğu ROI tam
 *   daire değildir (uzun/kısa eksen); (ii) dairede alan yarıçapın KARESİYLE (r²)
 *   büyür, küçük yarıçap değişimi örneklemeyi yanlı yapar. Eşit-alan bölütleme
 *   şekilden bağımsız DENGELİ ÖRNEKLEME verir — merkez ile çevre karşılaştırmaları
 *   (ör. TIL/immün gradyan, çeperde makrofaj zenginleşmesi) için istatistiksel
 *   olarak daha adil bir zemindir.
 *
 * NE ÜRETİR (ve ne ÜRETMEZ):
 *   • Yalnız GEOMETRİ + ölçüm üretir: her bölge için alan (µm²), toplamın %'si ve
 *     (tespit varsa) içindeki tespit sayısı + yoğunluk (adet/mm²).
 *   • Kaynak alan(lar)ı sizin çizip SEÇMENİZ gerekir. Skor/eşik/evre/klinik
 *     yorum ÜRETMEZ.
 *   • Yalnız ALAN anotasyonlarıyla çalışır (nokta/çizgi ROI atlanır).
 *
 * KULLANIM:
 *   1. Kalibre (µm/px) bir lam açın; kaynak alanı anotasyon olarak çizip SEÇİN
 *      (birden çok da olabilir — her biri ayrı bölünür)
 *   2. [Extensions → Atölye → Yardımcılar → Uzamsal analiz → Eşit alanlı merkez/çevre]
 *      (ya da [Automate → Project scripts → bu betik])
 *   3. Bölge sayısını girin (varsayılan 2 = merkez + çevre)
 *
 * ÇIKTI:
 *   • Her kaynak için "Merkez (eşit alanlı)" / "Çevre (eşit alanlı)" (+ ara bölge)
 *     sınıflı bölge anotasyonları (alan + % + tespit ölçümleriyle)
 *   • Sonuç penceresinde bölge alanları ve bölgeler-arası maks. % fark
 *     (eşit alanlı doğruluğu — makalede 48 WSI'de <%0,4)
 *   • Önceki bölgeler varsa SORAR: "Ekle" (varsayılan) hiçbir şey silmez, yeni bölgeler adlarının
 *     sonuna " (2)" gibi bir ek alır; "Değiştir" yalnız bu yardımcının bölgelerini siler (tüm kaynaklar).
 *     Başsız kip: -a mevcut=ekle | -a mevcut=degistir (belirtilmezse durur, hiçbir şey yazmaz).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı geometri/ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/merkez-cevre')

def showResultWindow = { String windowTitle, String windowBody ->
    if (isHeadless) {
        println "=== ${windowTitle} ===\n${windowBody}\n=================="
        return
    }
    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle(windowTitle)
            stage.setAlwaysOnTop(true)

            def textArea = new javafx.scene.control.TextArea(windowBody)
            textArea.setEditable(false)
            textArea.setWrapText(false)
            textArea.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;")

            def alwaysTop = new javafx.scene.control.CheckBox(workshopText('script.70ca64c0c43a', "Üstte tut"))
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )

            def copyBtn = new javafx.scene.control.Button(workshopText('script.a8bcca42d9ce', "Kopyala"))
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent()
                content.putString(windowBody)
                cb.setContent(content)
            })

            def closeBtn = new javafx.scene.control.Button(workshopText('script.7b31a9fc4816', "Kapat"))
            closeBtn.setDefaultButton(true)
            closeBtn.setOnAction({ stage.close() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)

            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, copyBtn, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(textArea)
            def __footer = new javafx.scene.control.Label(workshopText('script.be46103cef99', "QuPath Atölye Betikleri · araştırma/eğitim amaçlı"))
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons)
            __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)

            stage.setScene(new javafx.scene.Scene(root, 720, 520))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Sabitler ────────────────────────────────────────────────────────
String CLS_CENTER = "Merkez (eşit alanlı)"
String CLS_PERIPH = "Çevre (eşit alanlı)"
String CLS_MIDDLE = "Ara bölge (eşit alanlı)"
def REGION_CLASSES = [CLS_CENTER, CLS_PERIPH, CLS_MIDDLE]
int DEF_REGIONS = 2
int MAX_REGIONS = 6
int DET_CAP = 200000        // bu sayıdan çok tespit varsa bölge-başı sayım atlanır

// ── Önceki sonuç: SORULMADAN silinmez (ev kuralı) ────────────────────
// Önceki sonuç = bu yardımcının ürettiği bölge: üç sınıftan biri + bu yardımcının ad biçimi
// ("<kaynak> · Merkez (2/2)", isteğe bağlı " (N)", N >= 2) + yardımcının yazdığı iki ölçüm.
// Yalnız sınıfa bakılmaz: kullanıcının aynı sınıfta elle çizdiği ya da adını değiştirdiği anotasyon süpürülmez.
// Varsayılan kip HİÇBİR ŞEY SİLMEZ: Ekle → yeni bölge seti adlarının sonuna " (2)", " (3)" … alır.
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
def regionSetIndex = { String name ->
    if (name == null) return 0
    def m = java.util.regex.Pattern.compile('^.+ · (?:Çevre|Merkez|Ara \\d+) \\(\\d+/\\d+\\)(?: \\((\\d{1,6})\\))?$').matcher(name)
    if (!m.matches()) return 0
    if (m.group(1) == null) return 1
    int n = Integer.parseInt(m.group(1))
    return n >= 2 ? n : 0
}
def prevOwnRegions = { hierarchy ->
    if (hierarchy == null) return []
    return hierarchy.getAnnotationObjects().findAll {
        REGION_CLASSES.contains(it.getPathClass()?.toString()) && regionSetIndex(it.getName()) >= 1 &&
            it.getMeasurements().containsKey('Bölge alanı (µm²)') && it.getMeasurements().containsKey('Bölge sırası (dıştan)')
    }
}
def nextRegionRunIndex = { hierarchy ->
    def used = prevOwnRegions(hierarchy).collect { regionSetIndex(it.getName()) }
    return used.isEmpty() ? 1 : ((used.max() as int) + 1)
}

// Başsız kip: -a mevcut=ekle | -a mevcut=degistir. Bilinmeyen değer HER durumda durdurur (fail closed).
String mevcutArg = null
if (isHeadless) {
    def scriptArgs = []
    try { if (binding.hasVariable('args')) { def a = binding.getVariable('args'); if (a != null) scriptArgs = (a as List) } } catch (Throwable ignore) {}
    def argMap = [:]
    scriptArgs.each { a -> def sa = a?.toString() ?: ''; int ia = sa.indexOf('='); if (ia > 0) argMap[sa.substring(0, ia).trim()] = sa.substring(ia + 1).trim() }
    if (argMap.mevcut != null) {
        mevcutArg = argMap.mevcut.toLowerCase(java.util.Locale.ROOT)
        if (!['ekle', 'degistir'].contains(mevcutArg)) {
            println String.format(java.util.Locale.US, workshopText('script.768b9abc88f3', 'HATA: mevcut yalnız \'ekle\' ya da \'degistir\' olabilir (verilen: %s). Hiçbir şey yazılmadı.'), argMap.mevcut); return
        }
    }
}

// Önceki bölgeler için kullanıcıya SOR (bölge üretiminden ÖNCE çağrılır).
//   Dönüş: 'add' | 'replace' | null (İptal → hiçbir şey üretilmez, hiçbir şey yazılmaz).
def askExisting = { hierarchy ->
    def prev = prevOwnRegions(hierarchy)
    if (prev.isEmpty()) return MODE_ADD
    int nextIdx = nextRegionRunIndex(hierarchy)
    String classList = REGION_CLASSES.join(' / ')
    String optAdd = workshopText('script.6d37bf757141', 'Öncekilere dokunma — yeni bölgeleri ayrı bir set olarak ekle (varsayılan)')
    String optRep = workshopText('script.b48dce23d678', 'Öncekileri değiştir (bu yardımcının bölgelerinin tümü silinir)')
    def pick = Dialogs.showChoiceDialog(workshopText('script.2cef9c21c618', 'Önceki eşit alanlı bölgeler'),
        String.format(java.util.Locale.US, workshopText('script.345d108b15b1', 'Bu lamda bu yardımcının ürettiği %,d eşit alanlı bölge anotasyonu zaten var (sınıflar: %s; tüm kaynak alanlar için).\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni bölgelerin adlarının sonuna "(%d)" eklenir ve ayrı bir set olurlar. Lamda iki set birlikte durur; bölgeler üst üste biner.\n\n"Değiştir" seçilirse önceki bölgelerin TÜMÜ silinir — onlarda elle yaptığınız düzeltmeler de gider; bölgelerin içindeki hücre tespitleri ve diğer nesneler korunur. Elle çizdiğiniz ya da adını değiştirdiğiniz anotasyonlara dokunulmaz.\n\nİptal: hiçbir şey üretilmez, hiçbir şey yazılmaz.\n\nÖnceki bölgeler ne olsun?'), prev.size(), classList, nextIdx),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.4514d3b5f1bd', 'Önceki bölgeler silinecek'),
            String.format(java.util.Locale.US, workshopText('script.44bc4ade563e', '%,d eşit alanlı bölge anotasyonu (%s; tüm kaynak alanlar için) silinecek; bölgelerde elle yaptığınız düzeltmeler KAYBOLUR. İçlerindeki nesneler korunur.\n\nDevam edilsin mi?'), prev.size(), classList))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.6595248f2575', "Önce merkez/çevreye bölünecek alanı anotasyon olarak çizip seçin."))
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage(workshopText('script.a5a01874aff2', "Kalibrasyon yok"), workshopText('script.4b0f699b35dd', 'Lamda piksel boyutu (µm) tanımlı değil; alan µm² olarak üretilemez.\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu).'))
    return
}

def targets = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI()?.isArea() && !REGION_CLASSES.contains(it.getPathClass()?.toString())
}
if (targets.isEmpty()) {
    def msg = workshopText('script.201ac1a763e2', 'Kaynak alan seçili değil.\n\n') +
              workshopText('script.d61ca07415a4', 'Merkez/çevreye bölünecek alanı (tümör yuvası, hidrojel, lezyon vb.)\n') +
              workshopText('script.37aff5b8a2d8', 'ALAN anotasyonu olarak çizin ve SEÇİN, sonra betiği tekrar çalıştırın.')
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.22e7e0edca91', "Alan seçili değil"), msg)
    return
}

// ── 2) Bölge sayısı ─────────────────────────────────────────────────
int numRegions
if (isHeadless) {
    try { numRegions = Integer.parseInt(prefs.get('numRegions', String.valueOf(DEF_REGIONS))) } catch (Throwable t) { numRegions = DEF_REGIONS }
} else {
    def defN = prefs.get('numRegions', String.valueOf(DEF_REGIONS))
    def nTxt = Dialogs.showInputDialog(workshopText('script.827e93b4fcce', "Eşit-alan merkez/çevre"),
        "${workshopText('script.3eddff49b493', 'Kaç eşit alanlı bölge? (2 = merkez + çevre; en çok ')}${MAX_REGIONS})", defN)
    if (nTxt == null) { println "İptal edildi."; return }
    try { numRegions = Integer.parseInt(nTxt.trim()) }
    catch (Throwable t) { Dialogs.showErrorMessage(workshopText('script.54cde46c5e98', "Geçersiz sayı"), "${workshopText('script.cd8581a452e1', 'Bölge sayısı bir tam sayı olmalı (2–')}${MAX_REGIONS})."); return }
}
if (numRegions < 2) numRegions = 2
if (numRegions > MAX_REGIONS) numRegions = MAX_REGIONS
prefs.put('numRegions', String.valueOf(numRegions))
try { prefs.flush() } catch (Throwable ig) {}

// ── Yardımcı: alanı hedefe getiren içe-buffer mesafesini ikili arama ile bul ──
// area(geom.buffer(−d)) d ile monoton azalır; alanı targetPx'e getiren d'yi bul.
def bufferDistForArea = { org.locationtech.jts.geom.Geometry geom, double targetPx, double hiMaxPx ->
    double lo = 0.0d
    double hi = hiMaxPx
    for (int it2 = 0; it2 < 60; it2++) {
        double mid = (lo + hi) / 2.0d
        double a
        try {
            def b = geom.buffer(-mid)
            a = (b == null || b.isEmpty()) ? 0.0d : b.getArea()
        } catch (Throwable t) { a = 0.0d }
        if (a > targetPx) lo = mid else hi = mid
    }
    return (lo + hi) / 2.0d
}

def labelFor = { int idx, int n ->
    if (idx == 0) return "Çevre"
    if (idx == n - 1) return "Merkez"
    return "Ara ${idx}"
}
def classNameFor = { int idx, int n ->
    if (idx == 0) return CLS_PERIPH
    if (idx == n - 1) return CLS_CENTER
    return CLS_MIDDLE
}

// ── 3) Önceki bölgeler var mı? Üretimden ÖNCE sor ────────────────────
def imgHierarchy = imageData.getHierarchy()
def prevOwn = prevOwnRegions(imgHierarchy)
def prevNames = prevOwn.collect { it.getName() }.unique().sort()
String writeMode = MODE_ADD
if (!prevOwn.isEmpty()) {
    if (isHeadless) {
        if (mevcutArg == null) {
            println String.format(java.util.Locale.US, workshopText('script.05748f3441b3', 'Bu lamda bu yardımcının ürettiği %,d eşit alanlı bölge anotasyonu (%s) zaten var. Hiçbir şey yazılmadı. Yeni bir set eklemek için -a mevcut=ekle, önceki bölgeleri değiştirmek için -a mevcut=degistir ile yeniden çalıştırın.'), prevOwn.size(), REGION_CLASSES.join(' / ')); return
        }
        writeMode = (mevcutArg == 'degistir') ? MODE_REPLACE : MODE_ADD
    } else {
        writeMode = askExisting(imgHierarchy)
        if (writeMode == null) { println "İptal edildi."; return }
    }
}
// Ekle kipinde yeni bölgeler sıradaki set numarasını alır; Değiştir / ilk çalıştırmada ek yok.
int runIdx = (writeMode == MODE_ADD && !prevOwn.isEmpty()) ? nextRegionRunIndex(imgHierarchy) : 1
String runSuffix = (runIdx > 1) ? (' (' + runIdx + ')') : ''

// ── 4) Tespitleri (varsa) hazırlığa al ──────────────────────────────
def allDets = QP.getDetectionObjects().findAll { it.getROI() != null }
boolean countDets = !allDets.isEmpty() && allDets.size() <= DET_CAP

// ── 5) Her kaynağı böl ──────────────────────────────────────────────
def created = []
def rows = []          // [src, label, cls, areaUm2, pct, detCount]
def srcAccuracy = []   // [src, maxPctDiff]
int skippedSrc = 0

targets.eachWithIndex { t, si ->
    def roi = t.getROI()
    def plane = roi.getImagePlane()
    def orig
    try { orig = roi.getGeometry().buffer(0) } catch (Throwable tg) { orig = roi.getGeometry() }
    if (orig == null || orig.isEmpty()) { skippedSrc++; return }
    double totalPx = orig.getArea()
    if (!(totalPx > 0)) { skippedSrc++; return }
    // İçe-buffer inradius ≤ sqrt(A/π) olduğundan bu üst sınır geometriyi boşaltmayı garanti eder.
    double hiMaxPx = Math.sqrt(totalPx / Math.PI) * 1.05d

    def srcName = t.getName() ?: (t.getPathClass()?.toString()) ?: "alan ${si + 1}"
    def srcDets = countDets ? allDets.findAll { roi.contains(it.getROI().getCentroidX(), it.getROI().getCentroidY()) } : []

    // Sınırlar: bounds[k−1] = orig.buffer(−d_k), area = A·(n−k)/n, k=1..n−1
    def bounds = []
    boolean ok = true
    for (int k = 1; k <= numRegions - 1 && ok; k++) {
        double targetPx = totalPx * (numRegions - k) / (double) numRegions
        double d = bufferDistForArea(orig, targetPx, hiMaxPx)
        def b
        try { b = orig.buffer(-d).buffer(0) } catch (Throwable tb) { b = null }
        if (b == null || b.isEmpty()) { ok = false; break }
        bounds << b
    }
    if (!ok) { skippedSrc++; return }

    // Bölge geometrileri (dıştan içe): 0=çevre ... n−1=merkez
    def regionGeoms = []
    try {
        regionGeoms << orig.difference(bounds[0])                      // 0: çevre
        for (int i = 1; i <= numRegions - 2; i++) {
            regionGeoms << bounds[i - 1].difference(bounds[i])         // ara bölgeler
        }
        regionGeoms << bounds[numRegions - 2]                          // merkez
    } catch (Throwable tr) { skippedSrc++; return }

    // Bölgeleri anotasyona çevir + ölç
    def srcRegionPcts = []
    regionGeoms.eachWithIndex { g, idx ->
        if (g == null || g.isEmpty()) return
        def rROI = qupath.lib.roi.GeometryTools.geometryToROI(g, plane)
        if (rROI == null || rROI.isEmpty() || !rROI.isArea()) return
        String label = labelFor(idx, numRegions)
        String cls = classNameFor(idx, numRegions)
        def anno = qupath.lib.objects.PathObjects.createAnnotationObject(rROI, QP.getPathClass(cls))
        anno.setName(String.format(java.util.Locale.US, "%s · %s (%d/%d)", srcName, label, idx + 1, numRegions) + runSuffix)
        double areaUm2 = g.getArea() * pw * ph
        double pct = 100.0d * g.getArea() / totalPx
        anno.measurements['Bölge alanı (µm²)']   = areaUm2
        anno.measurements["Toplam alanın %'si"]  = pct
        anno.measurements['Bölge sırası (dıştan)'] = (idx + 1) as double
        anno.measurements['Bölge sayısı']         = numRegions as double
        int dc = -1
        if (countDets) {
            dc = srcDets.count { rROI.contains(it.getROI().getCentroidX(), it.getROI().getCentroidY()) } as int
            double areaMm2 = areaUm2 / 1_000_000.0d
            anno.measurements['İçindeki tespit sayısı'] = dc as double
            anno.measurements['Tespit yoğunluğu (adet/mm²)'] = (areaMm2 > 0) ? (dc / areaMm2) : 0.0d
        }
        created << anno
        srcRegionPcts << pct
        rows << [src: srcName, label: label, cls: cls, area: areaUm2, pct: pct, det: dc]
    }
    if (srcRegionPcts.size() >= 2) {
        double maxDiff = (srcRegionPcts.max() as double) - (srcRegionPcts.min() as double)
        srcAccuracy << [src: srcName, diff: maxDiff]
    }
}

if (created.isEmpty()) {
    def msg = workshopText('script.6ae9a357f094', 'Eşit-alan bölge üretilemedi (seçili alanların geometrisi bölme için uygun değil).\n') +
              workshopText('script.06cc1d412311', 'Bölge sayısını azaltmayı ya da daha kompakt/geniş bir alan seçmeyi deneyin.')
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.62605099298f', "Bölge yok"), msg)
    return
}

// Önceki bölgeler: kip çağırandan gelir. Ekle → hiçbir şey silinmez; Değiştir → yalnız bu yardımcının
// bölgeleri (yukarıdaki tanım) silinir, içlerindeki nesneler (ör. hücre tespitleri) korunur. Üretim
// başarısız olsaydı buraya gelinmez: önceki bölgeler yerinde kalır.
int removedPrev = 0
if (writeMode == MODE_REPLACE) {
    def prevNow = prevOwnRegions(imgHierarchy)
    removedPrev = prevNow.size()
    if (removedPrev > 0) QP.removeObjects(prevNow, true)
}
QP.addObjects(created)
QP.fireHierarchyUpdate()

// ── 6) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << workshopText('script.6aee7c38e8ac', 'EŞİT-ALAN MERKEZ / ÇEVRE\n')
body << "════════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, workshopText('script.b11a2462bf0b', 'Kaynak alan sayısı : %,d  (atlanan: %,d)%n'), targets.size() - skippedSrc, skippedSrc)
body << String.format(java.util.Locale.US, workshopText('script.8e82a299b629', 'Bölge / kaynak     : %d%n'), numRegions)
body << String.format(java.util.Locale.US, workshopText('script.3eedc991e4df', 'Üretilen bölge     : %,d%n'), created.size())
body << (countDets ? workshopText('script.3b43f05397c6', 'Tespit sayımı      : açık\n') : String.format(java.util.Locale.US, workshopText('script.6aeb5c63db5c', 'Tespit sayımı      : kapalı (tespit yok veya >%s)\n'), String.format(java.util.Locale.US, "%,d", DET_CAP)))
if (runIdx > 1)
    body << String.format(java.util.Locale.US, workshopText('script.6d16aa739502', 'Bölge seti         : %d  (önceki set duruyor)'), runIdx) << "\n"
if (writeMode == MODE_ADD && !prevNames.isEmpty()) {
    body << "\n" << String.format(java.util.Locale.US, workshopText('script.1167d104b3b2', 'DİKKAT — bu lamda önceki bölge seti de duruyor (%,d anotasyon: %s).\nHiçbir şey silinmedi. Eskisini kaldırmak için o bölgeleri seçip silin ya da yardımcıyı yeniden çalıştırırken "Öncekileri değiştir"i seçin. İki set üst üste biner; ikisinin içinde de çalışan bir analiz aynı bölgeyi iki kez sayabilir.'), prevOwn.size(),
        prevNames.take(5).join(', ') + (prevNames.size() > 5 ? ', …' : '')) << "\n\n"
} else if (removedPrev > 0) {
    body << "\n" << String.format(java.util.Locale.US, workshopText('script.85eecb52630b', 'Önceki bölgeler değiştirildi: %,d anotasyon silindi (içlerindeki nesneler korundu).'), removedPrev) << "\n\n"
}
body << "──────────────────────────────────────────────\n"
def bySrc = rows.groupBy { it.src }
bySrc.each { src, rs ->
    body << "▸ ${src.length() > 40 ? src.substring(0, 39) + "…" : src}\n"
    rs.each { r ->
        if (r.det >= 0)
            body << String.format(java.util.Locale.US, workshopText('script.c1c752173439', '    %-10s %,12.0f µm²  %5.1f%%   %,d tespit%n'), r.label, r.area as double, r.pct as double, r.det as int)
        else
            body << String.format(java.util.Locale.US, "    %-10s %,12.0f µm²  %5.1f%%%n", r.label, r.area as double, r.pct as double)
    }
}
if (!srcAccuracy.isEmpty()) {
    double worst = srcAccuracy.collect { it.diff as double }.max() as double
    body << "──────────────────────────────────────────────\n"
    body << String.format(java.util.Locale.US, workshopText('script.8d2d409ddfd2', 'Eşit-alan doğruluğu: bölgeler-arası maks. fark %.2f%%%n'), worst)
    body << workshopText('script.76e47b42940d', '(DANEELpath makalesinde 48 WSI\'de bu fark <%0,4)\n')
}
body << "\n"
body << String.format(java.util.Locale.US, workshopText('script.0e2dba2da950', 'Bölgeler \'%s\' / \'%s\''), CLS_CENTER, CLS_PERIPH)
body << (numRegions > 2 ? " / '${CLS_MIDDLE}'" : "") << workshopText('script.1c8b0beee59e', ' sınıflarıyla eklendi.\n')
body << workshopText('script.4603d1491ba1', 'İçlerinde hücre tespiti / piksel sınıflandırıcı çalıştırabilir,\n')
body << workshopText('script.c4d024aa3ffa', 'merkez↔çevre farkını (ör. TIL/immün gradyan) karşılaştırabilirsiniz.\n\n')
body << workshopText('script.c5ba0c047d2e', 'Yöntem: içe-buffer(−d) + ikili arama ile eşit alanlı sınırı (JTS).\n')
body << workshopText('script.078b977cbd70', 'Esin: DANEELpath (Vieco-Martí 2026, Sci Rep; doi:10.1038/s41598-026-37134-5) —\n')
body << workshopText('script.489d9e9793d1', 'makalede tarif edilen algoritmanın temiz-oda yeniden yazımı (GPL koddan aktarım yok).\n')
body << workshopText('script.77cbe8b99948', 'Bu bir GEOMETRİ/ölçüm üretimidir — klinik skor, eşik veya yorum DEĞİL.\n\n')
body << workshopText('script.e061d3eb2a32', '⚠️ Yalnızca araştırma/eğitim amaçlı geometri/ölçüm üretir.')

showResultWindow(workshopText('script.827e93b4fcce', 'Eşit-alan merkez/çevre'), body.toString())
println String.format(java.util.Locale.US,
    "✓ %,d eşit alanlı bölge üretildi (%d bölge/kaynak, %,d kaynak).",
    created.size(), numRegions, targets.size() - skippedSrc)

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
