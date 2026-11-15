/**
 * Yardımcı - Yapıya Uzaklık (sınıra mesafe)
 * ------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Her hücre tespiti için, SEÇİLİ anotasyon(lar)ın (bir "yapı": tümör sınırı,
 * damar, bez, invazyon kenarı vb.) SINIRINA olan işaretli mesafeyi (µm)
 * hesaplar ve hücre ölçümü olarak yazar. İşaretli mesafe: NEGATİF değer
 * hücrenin yapının İÇİNDE olduğunu, pozitif değer DIŞINDA olduğunu gösterir.
 *
 * NEDEN: Atölyenin uzamsal yardımcıları (Delaunay, en yakın komşu, yoğunluk)
 * hücre-hücre düzenini ölçer. Ama çoğu patoloji sorusu bir YAPIYA göredir:
 * "immün hücreler tümör sınırına ne kadar yakın?", "Ki-67+ hücreler invazyon
 * kenarında mı yoğun?". Bu, From Samples to Knowledge (FS2K, Session 12)
 * eğitimindeki "signed distance to annotations → yakın/uzak sınıflama"
 * iş akışının atölye karşılığıdır ve doğrudan Stromal TIL / İmmün Skorlama
 * ekini / TSR Metodolojisi ekini besler.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • VAR OLAN tespitlerin merkezinden seçili yapının sınırına mesafe ölçer.
 *     Bu bir MESAFE ölçümüdür — klinik skor, eşik veya yorum DEĞİL.
 *   • Hücre TESPİTİ YAPMAZ. Önce bir tespit modülü çalıştırın (Hücre Tespiti,
 *     Nükleer Boya, Sitoplazmik Boya ya da Tümör içi Ki-67 modülü).
 *   • Yapıyı (anotasyonu) sizin çizmeniz gerekir; betik bunu üretmez.
 *
 * QuPath KARŞILIĞI (GUI):
 *   [Analyze → Spatial analysis → Signed distance to annotations 2D]
 *   yerleşik komutu aynı ölçümü üretir, ama yalnız SINIFLANDIRILMIŞ
 *   anotasyonlar için çalışır. Bu betik seçili herhangi bir anotasyonu
 *   (sınıfı olsun olmasın) hedef alır ve tek, temiz bir ölçüm sütunu yazar.
 *
 * KULLANIM:
 *   1. Hücre tespiti yapılmış, kalibre (µm/px) bir lam açın
 *   2. Yapıyı anotasyon olarak çizin ve SEÇİN (birden çok da olabilir)
 *   3. [Extensions → Atölye → Yardımcılar → Uzamsal analiz → Yapıya uzaklık (sınıra mesafe)]
 *      (ya da [Automate → Project scripts → bu betik])
 *
 * ÇIKTI:
 *   • Her hücre: "Yapıya uzaklık (µm)" ölçümü (− = içeride; Veri dışa aktarma modülü ile aktarılır)
 *   • Kilitli "Yapıya Uzaklık Özet": ortalama/medyan/min/maks, yapı içi adet,
 *     sınır bandı (|mesafe| ≤ N µm) adet ve %
 *   • (İsteğe bağlı) bir hücre ölçümünün (DAB OD, Ki-67 vb.) uzaklıkla Pearson/
 *     Spearman korelasyonu — yapı-içi / yapı-dışı ayrı ("belirteç yapıya
 *     yaklaştıkça değişiyor mu?")
 *   • Sonuç penceresinde özet tablo
 *   • Önceki özet varsa SORAR: "Ekle" (varsayılan) hiçbir şey silmez, yeni özet
 *     "Yapıya Uzaklık Özet (2)" gibi adlanır; "Değiştir" yalnız bu yardımcının özetlerini siler.
 *     Başsız kip: -a mevcut=ekle | -a mevcut=degistir (belirtilmezse durur, hiçbir şey yazmaz).
 *
 * YÖNTEM REFERANSLARI:
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *   • From Samples to Knowledge 2025 (FS2K), Session 12 — uzamsal analiz
 *     (yapıya işaretli mesafe → yakın/uzak): saramcardle.github.io/FS2K
 *   • Ölçüm ↔ işaretli-mesafe korelasyonu: HMS-IAC/stroma-spatial-analysis
 *     (Ruzette & Nørrelykke; Kozlova ve ark. 2024, Zenodo 10.5281/zenodo.13122087, MIT)
 *     — teknikten ilham; koddan aktarım yapılmadı.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.analysis.DistanceTools

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

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

// ── Parametreler ────────────────────────────────────────────────────
double bandMicrons = 50.0          // "sınır bandı" eşiği (µm): |mesafe| ≤ N → yakın
String distMeas    = "Yapıya uzaklık (µm)"
String summaryName = "Yapıya Uzaklık Özet"

// ── Önceki sonuç: SORULMADAN silinmez (ev kuralı) ────────────────────
// Önceki sonuç = bu yardımcının yazdığı özet anotasyonu: TAM ad ya da "ad (N)" (N >= 2) — önek
// eşleşmesi YOK; kullanıcının "… notlarım" diye adlandırdığı anotasyon süpürülmez.
// Varsayılan kip HİÇBİR ŞEY SİLMEZ: Ekle → yeni özet "Yapıya Uzaklık Özet (2)", "(3)" … adını alır.
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
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

// Önceki sonuç için kullanıcıya SOR (HESAPLAMADAN önce çağrılır).
//   Dönüş: 'add' | 'replace' | null (İptal → hiçbir şey hesaplanmaz, hiçbir şey yazılmaz).
def askExisting = { hierarchy ->
    def prev = prevOwnObjects(hierarchy, summaryName)
    if (prev.isEmpty()) return MODE_ADD
    String optAdd = workshopText('script.e6b0dca2f71e', 'Öncekine dokunma — yeni bir özet olarak ekle (varsayılan)')
    String optRep = workshopText('script.84d3e02923f2', 'Öncekini değiştir (bu yardımcının özeti silinir)')
    def pick = Dialogs.showChoiceDialog(workshopText('script.f00b78a85792', 'Önceki yapıya uzaklık özeti'),
        String.format(java.util.Locale.US, workshopText('script.bfafd5a844f3', 'Bu lamda bu yardımcının yazdığı %,d özet anotasyonu zaten var.\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni özet ayrı bir anotasyon olur ("%s (2)" gibi).\n\n"Değiştir" seçilirse önceki özet anotasyonları silinir. Kendi anotasyonlarınıza dokunulmaz.\n\nHer iki durumda da hücrelerdeki "%s" ölçümü bu çalıştırmanın değeriyle yeniden yazılır.\n\nİptal: hiçbir şey hesaplanmaz, hiçbir şey yazılmaz.\n\nÖnceki özet ne olsun?'), prev.size(), summaryName, distMeas),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.89a856ac207d', 'Önceki özet silinecek'),
            String.format(java.util.Locale.US, workshopText('script.5ad805a3ff2a', '%,d özet anotasyonu silinecek; bunlardaki ölçümler kaybolur. Kendi anotasyonlarınıza dokunulmaz. Hücrelerdeki "%s" ölçümü bu çalıştırmanın değeriyle yazılır.\n\nDevam edilsin mi?'), prev.size(), distMeas))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.2b498b3b5a90', "Önce hücre tespiti yapılmış bir lam açın."))
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage(workshopText('script.a5a01874aff2', "Kalibrasyon yok"), workshopText('script.13fdf447446f', 'Lamda piksel boyutu (µm) tanımlı değil; mesafe µm olarak hesaplanamaz.\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu).'))
    return
}

def detections = QP.getDetectionObjects().findAll { it.getROI() != null }
if (detections.isEmpty()) {
    def msg = workshopText('script.93c39f575e31', 'Lamda hücre tespiti yok.\nÖnce Hücre Tespiti, Nükleer Boya, Sitoplazmik Boya ya da Tümör içi Ki-67 modülü ile tespit yapın.')
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.4277b763a640', "Tespit yok"), msg)
    return
}

def targets = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI() != null && !isOwnSetName(it.getName(), summaryName)
}
if (targets.isEmpty()) {
    def msg = workshopText('script.56e1f1f3b451', 'Hedef yapı seçili değil.\n\nMesafe ölçülecek yapıyı (tümör sınırı, damar, invazyon kenarı vb.)\nanotasyon olarak çizin ve SEÇİN, sonra betiği tekrar çalıştırın.')
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.584f0675ba8a', "Yapı seçili değil"), msg)
    return
}

// ── 1b) Önceki özet var mı? HESAPLAMADAN ÖNCE sor ────────────────────
def imgHierarchy = imageData.getHierarchy()
def prevOwn = prevOwnObjects(imgHierarchy, summaryName)
def prevNames = prevOwn.collect { it.getName() }.unique().sort()
String writeMode = MODE_ADD
if (!prevOwn.isEmpty()) {
    if (isHeadless) {
        if (mevcutArg == null) {
            println String.format(java.util.Locale.US, workshopText('script.9ff4de821d21', 'Bu lamda bu yardımcının yazdığı %,d özet anotasyonu ("%s") zaten var. Hiçbir şey yazılmadı. Yeni bir kopya eklemek için -a mevcut=ekle, önceki sonucu değiştirmek için -a mevcut=degistir ile yeniden çalıştırın.'), prevOwn.size(), summaryName); return
        }
        writeMode = (mevcutArg == 'degistir') ? MODE_REPLACE : MODE_ADD
    } else {
        writeMode = askExisting(imgHierarchy)
        if (writeMode == null) { println "İptal edildi."; return }
    }
}

// ── 2) İşaretli mesafe (merkez → yapı sınırı), µm ───────────────────
// QuPath çekirdek API'si: her kaynak hücrenin merkezinden hedef yapıların
// BİRLEŞİK sınırına mesafe; signed=true → yapı içindeki hücrelerde negatif.
println String.format(java.util.Locale.US, "Yapıya uzaklık hesaplanıyor (%,d hücre, %d hedef yapı)...",
    detections.size(), targets.size())
DistanceTools.centroidToBoundsDistance2D(detections, targets, pw, ph, distMeas, true)

// ── 3) Ölçümleri oku + istatistik ───────────────────────────────────
def vals = []
detections.each { d ->
    def v = d.measurements[distMeas]
    if (v != null && !Double.isNaN(v as double)) vals << (v as double)
}
if (vals.isEmpty()) {
    showResultWindow(workshopText('script.6ee2c41ec59c', 'Yapıya uzaklık — ölçüm üretilemedi'),
        workshopText('script.aa01e06d5e4c', 'Mesafe ölçümü hiçbir hücreye yazılamadı.\n\nSeçili yapı(lar)ın geçerli bir alan/çizgi ROI\'si olduğundan ve hücrelerle\naynı z/t düzleminde bulunduğundan emin olun.\n\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'))
    return
}

int total = vals.size()
double meanD = vals.sum() / total
def sorted = vals.sort(false)
int m = sorted.size()
double medianD = (m % 2 == 1) ? sorted[(int) (m / 2)] : (sorted[(int) (m / 2) - 1] + sorted[(int) (m / 2)]) / 2.0
double minD = sorted[0]
double maxD = sorted[m - 1]
int insideCount = vals.count { it < 0 } as int
int bandCount   = vals.count { Math.abs(it) <= bandMicrons } as int
double insidePct = 100.0 * insideCount / total
double bandPct   = 100.0 * bandCount / total

// ── 3b) (İsteğe bağlı) Ölçüm ↔ işaretli-mesafe korelasyonu ───────────
// HMS-IAC/stroma-spatial-analysis (S1) deseni: bir hücre ölçümünün yapıya
// mesafeyle Pearson/Spearman ilişkisi, yapı-içi (−) / yapı-dışı (+) ayrılarak.
def pearson = { double[] xs, double[] ys ->
    int n = xs.length
    if (n < 3 || ys.length != n) return Double.NaN
    double mx = 0.0d, my = 0.0d
    for (int i = 0; i < n; i++) { mx += xs[i]; my += ys[i] }
    mx /= n; my /= n
    double sxy = 0.0d, sxx = 0.0d, syy = 0.0d
    for (int i = 0; i < n; i++) { double dx = xs[i] - mx, dy = ys[i] - my; sxy += dx * dy; sxx += dx * dx; syy += dy * dy }
    double den = Math.sqrt(sxx * syy)
    return den > 0 ? (sxy / den) : Double.NaN
}
def rankArr = { double[] v ->
    int n = v.length
    def idx = (0..<n).toList()
    idx.sort { a, b -> Double.compare(v[a], v[b]) }
    double[] r = new double[n]
    int i = 0
    while (i < n) {
        int j = i
        while (j + 1 < n && v[idx[j + 1]] == v[idx[i]]) j++
        double avg = (i + j) / 2.0d + 1.0d
        for (int k = i; k <= j; k++) r[idx[k]] = avg
        i = j + 1
    }
    return r
}
def spearman = { double[] xs, double[] ys ->
    if (xs.length < 3 || ys.length != xs.length) return Double.NaN
    return pearson(rankArr(xs), rankArr(ys))
}
def fmtR = { double v -> Double.isNaN(v) ? "—" : String.format(java.util.Locale.US, "%.3f", v) }

def measNames = new java.util.TreeSet()
detections.each { d -> try { d.getMeasurementList().getMeasurementNames().each { measNames.add(it) } } catch (Throwable ig) {} }
measNames.remove(distMeas)
String NONE_OPT = workshopText('script.82ac0e82f518', '(korelasyon yok)')
String corrMeas = null
if (!isHeadless && !measNames.isEmpty()) {
    def opts = [NONE_OPT] + measNames.toList()
    def sel = Dialogs.showChoiceDialog(workshopText('script.56149e028592', "Yapıya uzaklık — korelasyon"),
        workshopText('script.8203bed22323', "İsteğe bağlı: bir hücre ölçümünün mesafeyle Pearson/Spearman korelasyonu?\n" +
        "(\"Bu belirteç yapıya yaklaştıkça değişiyor mu?\")"), opts, NONE_OPT)
    if (sel != null && sel != NONE_OPT) corrMeas = sel
}

double rAll = Double.NaN, rhoAll = Double.NaN, rIn = Double.NaN, rhoIn = Double.NaN, rOut = Double.NaN, rhoOut = Double.NaN
int corrN = 0, corrNin = 0, corrNout = 0
if (corrMeas != null) {
    def aD = [], aM = [], iD = [], iM = [], oD = [], oM = []
    detections.each { d ->
        def dv = d.measurements[distMeas]; def mv = d.measurements[corrMeas]
        if (dv != null && mv != null) {
            double dd = dv as double, mm = mv as double
            if (!Double.isNaN(dd) && !Double.isNaN(mm)) {
                aD << dd; aM << mm
                if (dd < 0) { iD << dd; iM << mm } else if (dd > 0) { oD << dd; oM << mm }
            }
        }
    }
    corrN = aD.size(); corrNin = iD.size(); corrNout = oD.size()
    rAll = pearson(aD as double[], aM as double[]); rhoAll = spearman(aD as double[], aM as double[])
    rIn  = pearson(iD as double[], iM as double[]); rhoIn  = spearman(iD as double[], iM as double[])
    rOut = pearson(oD as double[], oM as double[]); rhoOut = spearman(oD as double[], oM as double[])
}

// ── 4) Kilitli özet anotasyonu ──────────────────────────────────────
// Önceki özet: kip çağırandan gelir. Ekle → hiçbir şey silinmez; Değiştir → yalnız bu yardımcının
// özetleri (tam ad ya da "(N)") silinir, içlerindeki nesneler korunur.
int removedPrev = 0
String thisSummaryName
if (writeMode == MODE_REPLACE) {
    def prevNow = prevOwnObjects(imgHierarchy, summaryName)
    removedPrev = prevNow.size()
    if (removedPrev > 0) QP.removeObjects(prevNow, true)
    thisSummaryName = summaryName
} else {
    thisSummaryName = nextOwnSetName(imgHierarchy, summaryName)
}
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName(thisSummaryName)
summary.measurements['Hücre sayısı']                = total as double
summary.measurements['Hedef yapı sayısı']             = targets.size() as double
summary.measurements['Sınır bandı eşiği (µm)']        = bandMicrons
summary.measurements['Ortalama uzaklık (µm)']         = meanD
summary.measurements['Medyan uzaklık (µm)']           = medianD
summary.measurements['Minimum uzaklık (µm)']          = minD
summary.measurements['Maksimum uzaklık (µm)']         = maxD
summary.measurements['Yapı içinde (adet)']            = insideCount as double
summary.measurements['Yapı içinde (%)']               = insidePct
summary.measurements["Sınır bandı ≤ ${bandMicrons as int} µm (adet)"] = bandCount as double
summary.measurements["Sınır bandı ≤ ${bandMicrons as int} µm (%)"]    = bandPct
if (corrMeas != null) {
    if (!Double.isNaN(rAll))   summary.measurements["Korelasyon Pearson (tüm)"]      = rAll
    if (!Double.isNaN(rhoAll)) summary.measurements["Korelasyon Spearman (tüm)"]     = rhoAll
    if (!Double.isNaN(rIn))    summary.measurements["Korelasyon Pearson (yapı içi)"] = rIn
    if (!Double.isNaN(rOut))   summary.measurements["Korelasyon Pearson (yapı dışı)"] = rOut
}
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

// ── 5) Sonucu sun ───────────────────────────────────────────────────
def structNames = targets.collect {
    def nm = it.getName()
    def pc = it.getPathClass()
    nm ?: (pc != null ? pc.toString() : workshopText('script.2966ac6511d8', '(adsız)'))
}.unique()

def body = new StringBuilder()
body << workshopText('script.6b9a691a319b', 'YAPIYA UZAKLIK (sınıra işaretli mesafe)\n')
body << "════════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, workshopText('script.569fea6c172d', 'Hücre sayısı       : %,d%n'), total)
body << String.format(java.util.Locale.US, workshopText('script.059f164f1c68', 'Hedef yapı(lar)    : %s\n'), structNames.join(', '))
body << String.format(java.util.Locale.US, workshopText('script.517291b2c51e', 'Sınır bandı eşiği  : %.0f µm%n'), bandMicrons)
body << "\n"
body << String.format(java.util.Locale.US, workshopText('script.cdc5ff99ec9b', 'Ortalama uzaklık   : %.1f µm%n'), meanD)
body << String.format(java.util.Locale.US, workshopText('script.84a4ac7ed7d9', 'Medyan uzaklık     : %.1f µm%n'), medianD)
body << String.format(java.util.Locale.US, workshopText('script.bf5b7a5e38f0', 'Min / Maks         : %.1f / %.1f µm%n'), minD, maxD)
body << "──────────────────────────────────────────────\n"
body << String.format(java.util.Locale.US, workshopText('script.027dd53c043b', 'Yapı içinde (− )   : %,d  (%.1f %%)%n'), insideCount, insidePct)
body << String.format(java.util.Locale.US, workshopText('script.375a9c955b5b', 'Sınır bandı ≤%.0fµm : %,d  (%.1f %%)%n'), bandMicrons, bandCount, bandPct)
if (corrMeas != null) {
    body << "──────────────────────────────────────────────\n"
    body << workshopText('script.8ff614033725', 'Korelasyon (ölçüm ↔ uzaklık): ') << corrMeas << "\n"
    body << String.format(java.util.Locale.US, workshopText('script.092eedf750d8', '  Pearson  r  (tüm/içi/dışı) : %s / %s / %s%n'), fmtR(rAll), fmtR(rIn), fmtR(rOut))
    body << String.format(java.util.Locale.US, workshopText('script.1cf7ac583af0', '  Spearman rho (tüm/içi/dışı): %s / %s / %s%n'), fmtR(rhoAll), fmtR(rhoIn), fmtR(rhoOut))
    body << String.format(java.util.Locale.US, workshopText('script.9d06ae8373a2', '  N            (tüm/içi/dışı) : %,d / %,d / %,d%n'), corrN, corrNin, corrNout)
    body << workshopText('script.774d0d5fe4eb', '  (pozitif r: ölçüm mesafeyle artar · HMS-IAC S1 deseni)\n')
}
body << "\n"
body << String.format(java.util.Locale.US, workshopText('script.a9b6daa16201', 'Özet adı           : %s'), thisSummaryName) << "\n"
if (writeMode == MODE_ADD && !prevNames.isEmpty()) {
    body << "\n" << String.format(java.util.Locale.US, workshopText('script.34266a313dd6', 'DİKKAT — bu lamda önceki özet de duruyor: %s\nHiçbir şey silinmedi. Eskisini kaldırmak için o addaki anotasyonu seçip silin ya da yardımcıyı yeniden çalıştırırken "Öncekini değiştir"i seçin.\nHer hücredeki "%s" ölçümü bu çalıştırmanın değerini gösterir; önceki özet anotasyonu ise önceki çalıştırmanın değerlerini gösterir.'), prevNames.join(', '), distMeas) << "\n"
} else if (removedPrev > 0) {
    body << "\n" << String.format(java.util.Locale.US, workshopText('script.be27a1bc19b6', 'Önceki özet değiştirildi: %,d anotasyon silindi.'), removedPrev) << "\n"
}
body << "\n"
body << workshopText('script.03c8e92a9579', 'İşaret kuralı: NEGATİF mesafe → hücre yapının İÇİNDE; pozitif → dışında.\n')
body << workshopText('script.29949c57ef1b', 'Her hücreye \'') << distMeas << workshopText('script.32ebce513fe0', '\' ölçümü yazıldı; ölçüme göre renklendirince\n')
body << workshopText('script.63e7379183bf', 'yapıya yakınlık haritası görünür ([Measure → Show measurement maps]).\n')
body << workshopText('script.90c1e2fcd1e3', 'Veri dışa aktarma modülü ile dışa aktarılır.\n\n')
body << workshopText('script.0b7cad4b01cb', 'Bu bir MESAFE ölçümüdür — klinik skor, eşik veya yorum DEĞİL.\n')
body << "(FS2K Session 12; Bankhead 2017)\n\n"
body << workshopText('script.cf6099f0a005', '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')

showResultWindow(workshopText('script.c261a7709e67', 'Yapıya uzaklık'), body.toString())
println String.format(java.util.Locale.US,
    "✓ Yapıya uzaklık yazıldı (ortalama %.1f µm, medyan %.1f µm; sınır bandı %%%.1f).",
    meanD, medianD, bandPct)

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
