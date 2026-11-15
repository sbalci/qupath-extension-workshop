/**
 * Yardımcı - En Yakın Komşu Mesafesi
 * -----------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Her hücre için en yakın komşu hücreye olan merkez-merkez mesafesini (µm)
 * hesaplar ve hücre ölçümü olarak yazar. Hücrelerarası uzamsal düzeni
 * (kümelenme/dağınıklık) sayısallaştıran basit bir ÖLÇÜTtür.
 *
 * QuPath KARŞILIĞI (GUI):
 *   [Analyze → Spatial analysis → Detect centroid distances of detections]
 *   yerleşik komutu sınıflar-arası mesafe de üretir. Bu betik sınıf-bağımsız
 *   "en yakın herhangi bir hücre" mesafesini doğrudan, eklenti gerektirmeden
 *   hesaplar (uzamsal hash ızgarası ile hızlı + tam sonuç).
 *
 * GİRDİ (ÖN KOŞUL):
 *   • Lamda hücre tespitleri olmalı (Hücre Tespiti / Nükleer Boya / Sitoplazmik Boya /
 *     Tümör İçi Ki-67 modüllerinden biriyle üretilmiş). Betik tespit YAPMAZ.
 *
 * ÇIKTI:
 *   • Her hücre: "En yakın komşu (µm)" ölçümü (Veri Dışa Aktarma modülü ile dışa aktarılır)
 *   • Kilitli "En Yakın Komşu Özet": ortalama / SS / medyan / MAD / minimum / maksimum
 *     NN mesafesi (tam tanımlayıcı takımı)
 *
 * YENİDEN ÇALIŞTIRMA:
 *   Lamda bu betiğin önceki "En Yakın Komşu Özet" anotasyonu varsa betik hesaplamadan önce SORAR:
 *   Ekle (varsayılan; hiçbir şey silinmez, yeni özet "En Yakın Komşu Özet (2)" olur) / Değiştir
 *   (yalnız bu betiğin önceki özetleri silinir) / İptal (hiçbir şey yazılmaz). Başsız kipte
 *   önceki özet varsa betik durur: `-a mevcut=ekle` ya da `-a mevcut=degistir` verin.
 *   Hücrelerdeki "En yakın komşu (µm)" ölçümü her durumda yeni değerle üzerine yazılır.
 *
 * YÖNTEM REFERANSI:
 *   • Summers MA et al. (2022), Cell Rep Methods — uzamsal komşuluk/mesafe
 *     ölçütlerinin doku analizinde kullanımı. doi:10.1016/j.crmeth.2022.100348
 *   • Tanımlayıcı takımı (ort/SS/medyan/MAD/min/maks) için esin: DANEELpath
 *     "Distance Descriptors Cells NN" — Vieco-Martí ve ark. (2026), Sci Rep 16:6162.
 *     doi:10.1038/s41598-026-37134-5 (bağımsız uygulama; bkz. DANEELpath eki).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

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

            stage.setScene(new javafx.scene.Scene(root, 700, 480))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
double bucketMicrons = 50.0   // uzamsal hash kovası kenarı (µm)

// ── Önceki sonuç: SORULMADAN SİLİNMEZ (ev kuralı) ───────────────────────────
// Bu betik her çalıştırmada kilitli bir özet anotasyonu yazar. Eskiden aynı adlı özeti sessizce siliyordu.
//   Ekle (varsayılan) → hiçbir şey silinmez; yeni özet "ad (2)", "ad (3)" … adını alır.
//   Değiştir          → yalnız bu betiğin önceki özetleri silinir (tam ad ya da "ad (N)" VE betiğin imza ölçümü).
//   Hücrelere / karolara / anotasyonlara yazılan ölçümler her iki durumda da yeni çalıştırmanın değerini alır.
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
String summarySigKey = 'Ortalama NN mesafesi (µm)'     // yalnız bu betiğin özetinde bulunan ölçüm adı (imza)
// Bu betiğin seti mi? Yalnız TAM ad ya da "ad (N)" — önek eşleşmesi YOK:
// kullanıcının kendi adlandırdığı bir anotasyon süpürülmez.
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
// Silinebilecek "kendi" özetler: ad eşleşmesi + betiğin imza ölçümü. Kullanıcının aynı adı verdiği
// bir anotasyon imza ölçümünü taşımaz → sorulmaz, silinmez (yalnız adı atlanır).
def prevOwnSummaries = { hierarchy, String base, String sigKey ->
    return prevOwnObjects(hierarchy, base).findAll { it.getMeasurements().containsKey(sigKey) }
}
// Önceki özet için kullanıcıya SOR (ağır işten ÖNCE çağrılır).
//   Dönüş: 'add' | 'replace' | null (İptal → hiçbir şey hesaplanmaz, hiçbir şey yazılmaz).
//   Önceki özet yoksa sormadan 'add' döner.
def askExisting = { hierarchy, String base, String sigKey ->
    def prev = prevOwnSummaries(hierarchy, base, sigKey)
    if (prev.isEmpty()) return MODE_ADD
    String optAdd = workshopText('script.e6b0dca2f71e', 'Öncekine dokunma — yeni bir özet olarak ekle (varsayılan)')
    String optRep = workshopText('script.58a389002eb4', 'Öncekini değiştir (bu betiğin önceki özet anotasyonları silinir)')
    def pick = Dialogs.showChoiceDialog(workshopText('script.5237b5cec33c', 'Önceki sonuç'),
        String.format(java.util.Locale.US, workshopText('script.ce3bec237956', 'Bu lamda bu betiğin önceki çalıştırmasından kalan %,d özet anotasyonu var ("%s").\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni özet ayrı bir anotasyon olur ("%s" gibi) ve lamda iki özet birlikte durur.\n\n"Değiştir" seçilirse önceki özet anotasyonları silinir. Kendi anotasyonlarınıza dokunulmaz.\n\nHer iki seçenekte de hücrelerdeki "En yakın komşu (µm)" ölçümü yeni değerle üzerine yazılır.\n\nİptal: hiçbir şey hesaplanmaz, hiçbir şey yazılmaz.\n\nÖnceki sonuç ne olsun?'), prev.size(), base, nextOwnSetName(hierarchy, base)),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.89a856ac207d', 'Önceki özet silinecek'),
            String.format(java.util.Locale.US, workshopText('script.4ea846e9c562', '%,d özet anotasyonu silinecek: %s.\n\nYalnızca bu betiğin önceki özet anotasyonları silinir (adı tam ya da "ad (N)" biçiminde olan ve betiğin ölçümlerini taşıyanlar). Kendi anotasyonlarınıza ve içlerindeki nesnelere dokunulmaz. Bu işlem geri alınamaz.\n\nDevam edilsin mi?'), prev.size(), prev.collect { it.getName() }.unique().sort().join(', ')))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}
// Başsız karar: iletişim yok. -a mevcut=ekle | degistir. Önceki özet varsa ve değer verilmediyse DUR.
// Bilinmeyen değer HER durumda durdurur (fail closed). Dönüş: [mode: 'add'|'replace'|null, error: String|null]
def headlessDecision = { hierarchy, String base, String sigKey, String mevcut ->
    if (mevcut != null && !['ekle', 'degistir'].contains(mevcut))
        return [mode: null, error: String.format(java.util.Locale.US, workshopText('script.768b9abc88f3', 'HATA: mevcut yalnız \'ekle\' ya da \'degistir\' olabilir (verilen: %s). Hiçbir şey yazılmadı.'), mevcut)]
    def prev = prevOwnSummaries(hierarchy, base, sigKey)
    if (!prev.isEmpty() && mevcut == null)
        return [mode: null, error: String.format(java.util.Locale.US, workshopText('script.da8de65c00dc', 'Bu lamda bu betiğin önceki sonucu zaten var (%,d özet anotasyonu: %s). Hiçbir şey yazılmadı. Yeni bir kopya eklemek için -a mevcut=ekle, önceki sonucu değiştirmek için -a mevcut=degistir ile yeniden çalıştırın.'), prev.size(), prev.collect { it.getName() }.unique().sort().join(', '))]
    return [mode: (mevcut == 'degistir') ? MODE_REPLACE : MODE_ADD, error: null]
}
// Özeti hiyerarşiye yazar. Ekle: silme yok, sıradaki ad. Değiştir: yalnız kendi (imzalı) özetler silinir,
// içlerindeki nesneler korunur (keepChildren = true). Her iki durumda ad, var olan bir ada çarpmaz.
def commitSummary = { hierarchy, String base, String sigKey, String mode, summary ->
    def prevNames = prevOwnObjects(hierarchy, base).collect { it.getName() }.unique().sort()
    int removed = 0
    if (mode == MODE_REPLACE) {
        def prev = prevOwnSummaries(hierarchy, base, sigKey)
        removed = prev.size()
        if (removed > 0) hierarchy.removeObjects(prev, true)
        prevNames = []
    }
    String setName = nextOwnSetName(hierarchy, base)
    summary.setName(setName)
    hierarchy.addObjects([summary])
    return [setName: setName, removed: removed, prevNames: prevNames]
}
// Sonuç penceresi notu: Ekle sonrası önceki özetin durduğu, Değiştir sonrası kaç nesnenin silindiği.
def modeNote = { res ->
    if (res.prevNames) return String.format(java.util.Locale.US, workshopText('script.b73539319fd1', 'DİKKAT — bu lamda önceki sonuç da duruyor: %s\nHiçbir şey silinmedi. Bu çalıştırmanın özeti "%s" adını aldı. Hücrelerdeki "En yakın komşu (µm)" ölçümü YENİ çalıştırmayı yansıtır; önceki özet anotasyonu ise önceki değerleri gösterir. Eskisini kaldırmak için o anotasyonu seçip silin ya da betiği yeniden çalıştırırken "Öncekini değiştir"i seçin.'), res.prevNames.join(', '), res.setName)
    if ((res.removed ?: 0) > 0) return String.format(java.util.Locale.US, workshopText('script.d495b71072f5', 'Önceki sonuç değiştirildi: %,d özet anotasyonu silindi.'), res.removed)
    return ''
}
// Başsız argüman: -a mevcut=ekle | degistir (iletişim yok).
String mevcutArg = null
if (isHeadless) {
    def scriptArgs = []
    try { if (binding.hasVariable('args')) { def av = binding.getVariable('args'); if (av != null) scriptArgs = (av as List) } } catch (Throwable ignore) {}
    def argMap = [:]
    scriptArgs.each { sa0 -> def sa = sa0?.toString() ?: ''; int ia = sa.indexOf('='); if (ia > 0) argMap[sa.substring(0, ia).trim()] = sa.substring(ia + 1).trim() }
    mevcutArg = argMap.mevcut == null ? null : argMap.mevcut.toLowerCase(java.util.Locale.ROOT)
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
if (!(pw > 0)) {
    Dialogs.showErrorMessage(workshopText('script.a5a01874aff2', "Kalibrasyon yok"), workshopText('script.f65782dc5773', 'Lamda piksel boyutu (µm) tanımlı değil.\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu).'))
    return
}
// Anizotropik piksellerde (pw ≠ ph) düşey mesafeler ayrı ölçeklenmeli;
// yükseklik tanımsızsa genişliği kullan.
if (!(ph > 0)) ph = pw

def cells = QP.getDetectionObjects().findAll { it.getROI() != null }
int n = cells.size()
if (n < 2) {
    def msg = "En yakın komşu için en az 2 hücre gerekir.\n" +
              "Önce Hücre Tespiti / Nükleer Boya / Sitoplazmik Boya / Tümör İçi Ki-67 modüllerinden biriyle hücre tespiti yapın."
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.bd9ad9944f29', "Yetersiz hücre"), msg)
    return
}

// ── 1b) Önceki özet var mı? SOR (GUI) ya da -a mevcut=… (başsız) — hesaplamadan ve yazmadan ÖNCE ─
String writeMode
if (isHeadless) {
    def hd = headlessDecision(imageData.getHierarchy(), "En Yakın Komşu Özet", summarySigKey, mevcutArg)
    if (hd.mode == null) { println hd.error; return }
    writeMode = hd.mode
} else {
    writeMode = askExisting(imageData.getHierarchy(), "En Yakın Komşu Özet", summarySigKey)
    if (writeMode == null) { println "İptal edildi."; return }
}

// ── 2) Merkez koordinatları (µm) + uzamsal hash ızgarası ────────────
// Koordinatları baştan µm'ye çevir: x genişlikle, y yükseklikle ölçeklenir;
// böylece mesafeler anizotropik piksellerde de doğru olur.
double[] xs = new double[n]
double[] ys = new double[n]
cells.eachWithIndex { c, i -> def r = c.getROI(); xs[i] = r.getCentroidX() * pw; ys[i] = r.getCentroidY() * ph }

double bucketUm = bucketMicrons
def buckets = new HashMap<Long, List<Integer>>()
int[] bcol = new int[n]
int[] brow = new int[n]
def keyOf = { int c, int r -> (((long) c) << 32) ^ (r & 0xffffffffL) }
for (int i = 0; i < n; i++) {
    int c = (int) Math.floor(xs[i] / bucketUm)
    int r = (int) Math.floor(ys[i] / bucketUm)
    bcol[i] = c; brow[i] = r
    buckets.computeIfAbsent(keyOf(c, r), { k -> new ArrayList<Integer>() }).add(i)
}

// ── 3) Her hücre için en yakın komşu (halka halka genişleyen arama) ─
println String.format(java.util.Locale.US, "En yakın komşu hesaplanıyor (%,d hücre)...", n)
double[] nnUmArr = new double[n]
for (int i = 0; i < n; i++) {
    double best = Double.POSITIVE_INFINITY
    int ring = 0
    while (true) {
        for (int dc = -ring; dc <= ring; dc++) {
            for (int dr = -ring; dr <= ring; dr++) {
                if (ring > 0 && Math.max(Math.abs(dc), Math.abs(dr)) != ring) continue  // yalnız dış halka
                def lst = buckets.get(keyOf(bcol[i] + dc, brow[i] + dr))
                if (lst == null) continue
                for (int j : lst) {
                    if (j == i) continue
                    double dx = xs[i] - xs[j], dy = ys[i] - ys[j]
                    double d = dx * dx + dy * dy
                    if (d < best) best = d
                }
            }
        }
        // Aranmamış en yakın kova ≥ ring*bucketUm uzakta → o mesafe best'i geçemezse dur
        double guaranteed = ring * bucketUm
        if (best < Double.POSITIVE_INFINITY && guaranteed * guaranteed >= best) break
        ring++
        if (ring > 200) break  // güvenlik
    }
    nnUmArr[i] = best < Double.POSITIVE_INFINITY ? Math.sqrt(best) : Double.NaN
}

// ── 4) Hücre ölçümlerini yaz + istatistik topla (mesafeler zaten µm) ─
def nnUm = []
for (int i = 0; i < n; i++) {
    if (Double.isNaN(nnUmArr[i])) continue
    double um = nnUmArr[i]
    cells[i].measurements['En yakın komşu (µm)'] = um
    nnUm << um
}

double meanNN = 0.0, sdNN = 0.0, medianNN = 0.0, madNN = 0.0, minNN = 0.0, maxNN = 0.0
if (!nnUm.isEmpty()) {
    meanNN = nnUm.sum() / nnUm.size()
    def sorted = nnUm.sort(false)
    int m = sorted.size()
    medianNN = (m % 2 == 1) ? sorted[(int) (m / 2)] : (sorted[m / 2 - 1] + sorted[m / 2]) / 2.0
    minNN = sorted[0]
    maxNN = sorted[m - 1]
    if (m > 1) {
        double ss = 0.0
        for (v in nnUm) { double dv = (v as double) - meanNN; ss += dv * dv }
        sdNN = Math.sqrt(ss / (m - 1))   // örneklem standart sapması (N−1)
    }
    // Robust MAD = medyan(|xᵢ − medyan|)
    def dev = nnUm.collect { Math.abs((it as double) - medianNN) }.sort(false)
    int md = dev.size()
    madNN = (md % 2 == 1) ? dev[(int) (md / 2)] : (dev[md / 2 - 1] + dev[md / 2]) / 2.0
}

// ── 5) Kilitli özet anotasyonu ──────────────────────────────────────
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
// Ad commitSummary'de verilir: Ekle → "En Yakın Komşu Özet (2)" …; hiçbir şey sorulmadan silinmez.
summary.measurements['Hücre sayısı']              = n as double
summary.measurements['Ortalama NN mesafesi (µm)']  = meanNN
summary.measurements['SS NN mesafesi (µm)']        = sdNN
summary.measurements['Medyan NN mesafesi (µm)']    = medianNN
summary.measurements['MAD NN mesafesi (µm)']       = madNN
summary.measurements['Minimum NN mesafesi (µm)']   = minNN
summary.measurements['Maksimum NN mesafesi (µm)']  = maxNN
summary.setLocked(true)
def sumRes = commitSummary(imageData.getHierarchy(), "En Yakın Komşu Özet", summarySigKey, writeMode, summary)
QP.fireHierarchyUpdate()

// ── 6) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "EN YAKIN KOMŞU MESAFESİ\n"
body << "════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Hücre sayısı     : %,d%n", n)
body << String.format(java.util.Locale.US, "Ortalama NN      : %.1f µm%n", meanNN)
body << String.format(java.util.Locale.US, "SS (std) NN      : %.1f µm%n", sdNN)
body << String.format(java.util.Locale.US, "Medyan NN        : %.1f µm%n", medianNN)
body << String.format(java.util.Locale.US, "MAD NN           : %.1f µm%n", madNN)
body << String.format(java.util.Locale.US, "Minimum NN       : %.1f µm%n", minNN)
body << String.format(java.util.Locale.US, "Maksimum NN      : %.1f µm%n", maxNN)
body << "\n"
body << "Küçük NN mesafesi → hücreler sıkı (yüksek yoğunluk/kümelenme).\n"
body << "Her hücreye 'En yakın komşu (µm)' ölçümü yazıldı; ölçüme göre\n"
body << "renklendirilince yerel sıkışıklık haritası görünür.\n\n"
def noteText = modeNote(sumRes)
if (noteText) body << noteText << "\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("En yakın komşu mesafesi", body.toString())
println String.format(java.util.Locale.US, "✓ NN mesafesi yazıldı (ortalama %.1f µm, medyan %.1f µm).", meanNN, medianNN)

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
