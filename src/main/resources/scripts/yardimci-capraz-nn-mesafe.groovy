/**
 * Yardımcı - Çapraz-tip En Yakın Komşu Mesafesi
 * ------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Kullanıcının seçtiği iki farklı tespit sınıfı (A ve B) arasında
 *   türler arası en yakın komşu (cross-type nearest-neighbour) mesafesini
 *   hesaplar. Her A hücresi için B havuzundaki en yakın B hücresine olan
 *   merkez-merkez mesafesi (µm) hesaplanır ve A hücresine ölçüm olarak yazılır.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Farklı sınıflardaki hücreler ARASINDA çapraz mesafe ölçer.
 *     Ör. "tümör hücresinin en yakın CD8+ T hücresine uzaklığı".
 *   • Bir mesafe/uzamsal dağılım ÖLÇÜTÜDÜR — klinik skor, eşik veya
 *     immün yeterlilik yorumu DEĞİL.
 *   • Tespit YAPMAZ; Hücre Tespiti / Nükleer Boya / Membran Boya / Sitoplazmik Boya /
 *     Tümör İçi Ki-67 modüllerindeki tespitler sınıflı olmalıdır.
 *
 * KULLANIM:
 *   1. Sınıflı hücre tespitleri olan, kalibre (µm) bir lam açın.
 *   2. [Extensions → Atölye → Yardımcılar → Uzamsal analiz →
 *      Türler arası en yakın komşu (A→B)]
 *   3. Açılan iletişimlerde A ve B sınıflarını seçin; yarıçap girin.
 *
 * ÇIKTI:
 *   • Her A hücresi: "<A>→<B> en yakın komşu (µm)" ölçümü
 *     (Veri Dışa Aktarma modülü ile dışa aktarılır; ölçüm haritasında görselleştirilebilir)
 *   • Kilitli özet anotasyonu: ortalama / SS / medyan / MAD / minimum / maksimum
 *     çapraz NN mesafesi, N, yarıçap içindeki A hücresi sayısı ve oranı
 *   • Sonuç penceresinde özet tablo
 *
 * YENİDEN ÇALIŞTIRMA:
 *   Lamda bu A→B çiftinin önceki "Çapraz NN Özet (A→B)" anotasyonu varsa betik hesaplamadan önce
 *   SORAR: Ekle (varsayılan; hiçbir şey silinmez, yeni özet "… (2)" olur) / Değiştir (yalnız bu
 *   betiğin bu çiftteki önceki özetleri silinir) / İptal (hiçbir şey yazılmaz). Başsız kipte önceki
 *   özet varsa betik durur: `-a mevcut=ekle` ya da `-a mevcut=degistir` verin. A hücrelerindeki
 *   "<A>→<B> en yakın komşu (µm)" ölçümü her durumda yeni değerle üzerine yazılır.
 *
 * YÖNTEM / KAYNAK:
 *   • Uzamsal hash ızgarası ile çok halkalı en yakın komşu — aynı
 *     yardimci-nn-mesafe.groovy çekirdeği, yalnız B havuzu üzerinde
 *     indeks kurulur; her A için B'de arama yapılır.
 *   • Summers MA et al. (2022), Cell Rep Methods — uzamsal komşuluk ve
 *     mesafe ölçütleri doku analizinde. doi:10.1016/j.crmeth.2022.100348
 *   • Tanımlayıcı takımı (ort/SS/medyan/MAD/min/maks) için esin: DANEELpath
 *     "Distance Descriptors Cells NN" — Vieco-Martí ve ark. (2026), Sci Rep 16:6162.
 *     doi:10.1038/s41598-026-37134-5 (bağımsız uygulama; bkz. DANEELpath eki).
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/capraz-nn-mesafe')

// ── showResultWindow (VERBATIM from yardimci-kesisim-alani.groovy) ──────────
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

// ── Önceki sonuç: SORULMADAN SİLİNMEZ (ev kuralı) ───────────────────────────
// Bu betik her çalıştırmada kilitli bir özet anotasyonu yazar. Eskiden aynı adlı özeti sessizce siliyordu.
//   Ekle (varsayılan) → hiçbir şey silinmez; yeni özet "ad (2)", "ad (3)" … adını alır.
//   Değiştir          → yalnız bu betiğin önceki özetleri silinir (tam ad ya da "ad (N)" VE betiğin imza ölçümü).
//   Hücrelere / karolara / anotasyonlara yazılan ölçümler her iki durumda da yeni çalıştırmanın değerini alır.
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
String summarySigKey = 'Ortalama çapraz NN (µm)'     // yalnız bu betiğin özetinde bulunan ölçüm adı (imza)
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
def askExisting = { hierarchy, String base, String sigKey, String measLabel ->
    def prev = prevOwnSummaries(hierarchy, base, sigKey)
    if (prev.isEmpty()) return MODE_ADD
    String optAdd = workshopText('script.e6b0dca2f71e', 'Öncekine dokunma — yeni bir özet olarak ekle (varsayılan)')
    String optRep = workshopText('script.58a389002eb4', 'Öncekini değiştir (bu betiğin önceki özet anotasyonları silinir)')
    def pick = Dialogs.showChoiceDialog(workshopText('script.5237b5cec33c', 'Önceki sonuç'),
        String.format(java.util.Locale.US, workshopText('script.53bd9d440cf6', 'Bu lamda bu betiğin önceki çalıştırmasından kalan %,d özet anotasyonu var ("%s").\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni özet ayrı bir anotasyon olur ("%s" gibi) ve lamda iki özet birlikte durur.\n\n"Değiştir" seçilirse önceki özet anotasyonları silinir. Kendi anotasyonlarınıza dokunulmaz.\n\nHer iki seçenekte de A hücrelerindeki "%s" ölçümü yeni değerle üzerine yazılır.\n\nİptal: hiçbir şey hesaplanmaz, hiçbir şey yazılmaz.\n\nÖnceki sonuç ne olsun?'), prev.size(), base, nextOwnSetName(hierarchy, base), measLabel),
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
def modeNote = { res, String measLabel ->
    if (res.prevNames) return String.format(java.util.Locale.US, workshopText('script.21087de35ce6', 'DİKKAT — bu lamda önceki sonuç da duruyor: %s\nHiçbir şey silinmedi. Bu çalıştırmanın özeti "%s" adını aldı. A hücrelerindeki "%s" ölçümü YENİ çalıştırmayı yansıtır; önceki özet anotasyonu ise önceki değerleri gösterir. Eskisini kaldırmak için o anotasyonu seçip silin ya da betiği yeniden çalıştırırken "Öncekini değiştir"i seçin.'), res.prevNames.join(', '), res.setName, measLabel)
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

// ── 1) Ön kontroller ────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.8cb0e3843afa', "Önce sınıflı hücre tespitleri olan bir lam açın."))
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0)) {
    Dialogs.showErrorMessage(workshopText('script.a5a01874aff2', "Kalibrasyon yok"),
        workshopText('script.f65782dc5773', 'Lamda piksel boyutu (µm) tanımlı değil.\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu).'))
    return
}
// Anizotropik piksellerde (pw ≠ ph) Y ekseni ayrı ölçeklenmeli; yükseklik tanımsızsa genişliği kullan.
if (!(ph > 0)) ph = pw

// ── 2) Sınıf listesini topla ─────────────────────────────────────────────────
def allDetections = QP.getDetectionObjects().findAll { it.getROI() != null && it.getPathClass() != null }
def classNames = allDetections.collect { it.getPathClass().toString() }.unique().sort()

if (classNames.size() < 2) {
    def msg = "Çapraz NN için en az İKİ farklı sınıfta tespit gerekir.\n" +
              "Mevcut sınıf(lar): " + (classNames.join(', ') ?: '(yok)') + "\n\n" +
              "Önce Hücre Tespiti / Nükleer Boya / Membran Boya / Sitoplazmik Boya / " +
              "Tümör İçi Ki-67 modülleriyle sınıflı hücre tespiti yapın."
    if (isHeadless) println msg else Dialogs.showErrorMessage(workshopText('script.f34b330b89e2', "Yetersiz sınıf"), msg)
    return
}

// ── 3) A ve B sınıfı seçimi ─────────────────────────────────────────────────
String classA
String classB
double radiusUm = 50.0

if (isHeadless) {
    classA   = prefs.get('classA', '')
    classB   = prefs.get('classB', '')
    radiusUm = prefs.getDouble('radiusUm', 50.0)
    if (!classNames.contains(classA) || !classNames.contains(classB)) {
        println "Headless: 'classA'/'classB' tercihleri ayarlı değil ya da bu görüntüde yok.\n" +
                "Önce arayüzden bir kez çalıştırın. Mevcut sınıflar: ${classNames.join(', ')}"
        return
    }
} else {
    def defA = classNames.contains(prefs.get('classA', '')) ? prefs.get('classA', '') : classNames[0]
    classA = Dialogs.showChoiceDialog(workshopText('script.5917c2b9fdbb', "Çapraz NN — A sınıfı"),
        workshopText('script.7740736a303e', "Hangi sınıfın hücreleri için mesafe ölçülsün? (A — en yakın B aranır)"), classNames, defA)
    if (classA == null) { println "İptal edildi."; return }

    def bOpts = classNames.findAll { it != classA }
    def defB = bOpts.contains(prefs.get('classB', '')) ? prefs.get('classB', '') : bOpts[0]
    classB = Dialogs.showChoiceDialog(workshopText('script.b6a89849ae0d', "Çapraz NN — B sınıfı"),
        workshopText('script.5895fdfea359', "Hedef sınıf nedir? (B — bu havuzda en yakın komşu aranır)"), bOpts, defB)
    if (classB == null) { println "İptal edildi."; return }

    String radStr = Dialogs.showInputDialog(workshopText('script.139542876e7b', "Yakınlık yarıçapı (µm)"),
        workshopText('script.9fed9b706b6e', "Kaç µm yarıçap içindeki A hücreleri sayılsın?\n(Varsayılan: 50 µm)"), "50")
    if (radStr == null) { println "İptal edildi."; return }
    try { radiusUm = Double.parseDouble(radStr.trim().replace(',', '.')) }
    catch (Throwable ig) {
        Dialogs.showErrorMessage(workshopText('script.9d8438d9cdaf', "Geçersiz yarıçap"),
            "${workshopText('script.eb63d123cb3f', 'Yarıçap bir sayı olmalı (ör. 50 ya da 75.5). Girilen: \'')}${radStr}'.")
        return
    }
    if (!(radiusUm > 0)) radiusUm = 50.0
}

if (classA == classB) {
    Dialogs.showErrorMessage(workshopText('script.aaae99e0c668', "Aynı sınıf"), workshopText('script.53e35a091a93', "A ve B sınıfı farklı olmalı."))
    return
}

prefs.put('classA', classA)
prefs.put('classB', classB)
prefs.putDouble('radiusUm', radiusUm)
try { prefs.flush() } catch (Throwable ig) {}

// ── 4) Havuzları böl + kontrol ───────────────────────────────────────────────
def poolA = QP.getDetectionObjects().findAll { it.getROI() != null && it.getPathClass()?.toString() == classA }
def poolB = QP.getDetectionObjects().findAll { it.getROI() != null && it.getPathClass()?.toString() == classB }

if (poolA.isEmpty() || poolB.isEmpty()) {
    def msg = String.format(java.util.Locale.US,
        "Seçilen sınıflarda tespit yok (A='%s': %d, B='%s': %d).",
        classA, poolA.size(), classB, poolB.size())
    if (isHeadless) println msg else Dialogs.showErrorMessage(workshopText('script.4277b763a640', "Tespit yok"), msg)
    return
}

// ── 4b) Önceki özet var mı? SOR (GUI) ya da -a mevcut=… (başsız) — hesaplamadan ve yazmadan ÖNCE ─
// Özet adı A→B çiftine özeldir; başka çiftin özetine (ör. B→A) dokunulmaz.
String summaryName = "Çapraz NN Özet (${classA}→${classB})"
String measLabel   = "${classA}→${classB} en yakın komşu (µm)"
String writeMode
if (isHeadless) {
    def hd = headlessDecision(imageData.getHierarchy(), summaryName, summarySigKey, mevcutArg)
    if (hd.mode == null) { println hd.error; return }
    writeMode = hd.mode
} else {
    writeMode = askExisting(imageData.getHierarchy(), summaryName, summarySigKey, measLabel)
    if (writeMode == null) { println "İptal edildi."; return }
}

// ── 5) B üzerinde uzamsal hash ızgarası kur ──────────────────────────────────
double bucketUm  = Math.max(radiusUm, 50.0)   // kova kenarı ≥ 50 µm (µm-uzayı ızgara)
int nb = poolB.size()
double[] bxs = new double[nb]
double[] bys = new double[nb]
// Merkezleri µm'ye çevir: X genişlikle, Y yükseklikle ölçeklenir.
poolB.eachWithIndex { c, i -> def r = c.getROI(); bxs[i] = r.getCentroidX() * pw; bys[i] = r.getCentroidY() * ph }

def bucketsB = new HashMap<Long, List<Integer>>()
int[] bcol = new int[nb]
int[] brow = new int[nb]
def keyOf = { int c, int r -> (((long) c) << 32) ^ (r & 0xffffffffL) }
for (int i = 0; i < nb; i++) {
    int c = (int) Math.floor(bxs[i] / bucketUm)
    int r = (int) Math.floor(bys[i] / bucketUm)
    bcol[i] = c; brow[i] = r
    bucketsB.computeIfAbsent(keyOf(c, r), { k -> new ArrayList<Integer>() }).add(i)
}

// ── 6) Her A hücresi için halka halka B'de NN ara ───────────────────────────
println String.format(java.util.Locale.US,
    "Çapraz NN hesaplanıyor: A='%s' (%,d) → B='%s' (%,d), yarıçap=%.0f µm...",
    classA, poolA.size(), classB, poolB.size(), radiusUm)

String measName  = measLabel
double radiusUm2 = radiusUm * radiusUm   // µm² (mesafeler µm-uzayında hesaplanır)

def nnUm      = []
int withinRad = 0
int na        = poolA.size()

for (int i = 0; i < na; i++) {
    def roi  = poolA[i].getROI()
    double ax = roi.getCentroidX() * pw
    double ay = roi.getCentroidY() * ph
    int ac = (int) Math.floor(ax / bucketUm)
    int ar = (int) Math.floor(ay / bucketUm)

    double best = Double.POSITIVE_INFINITY
    int ring = 0
    while (true) {
        for (int dc = -ring; dc <= ring; dc++) {
            for (int dr = -ring; dr <= ring; dr++) {
                if (ring > 0 && Math.max(Math.abs(dc), Math.abs(dr)) != ring) continue
                def lst = bucketsB.get(keyOf(ac + dc, ar + dr))
                if (lst == null) continue
                for (int j : lst) {
                    double dx = ax - bxs[j], dy = ay - bys[j]
                    double d = dx * dx + dy * dy
                    if (d < best) best = d
                }
            }
        }
        double guaranteed = ring * bucketUm
        if (best < Double.POSITIVE_INFINITY && guaranteed * guaranteed >= best) break
        ring++
        if (ring > 200) break
    }

    if (best < Double.POSITIVE_INFINITY) {
        double distUm = Math.sqrt(best)
        poolA[i].measurements[measName] = distUm
        nnUm << distUm
        if (best <= radiusUm2) withinRad++
    }
}

// ── 7) Özet istatistikler ────────────────────────────────────────────────────
double meanNN = 0.0, sdNN = 0.0, medianNN = 0.0, madNN = 0.0, minNN = 0.0, maxNN = 0.0
if (!nnUm.isEmpty()) {
    meanNN = nnUm.sum() / nnUm.size()
    def sorted = nnUm.sort(false)
    int m = sorted.size()
    medianNN = (m % 2 == 1) ? sorted[(int)(m / 2)] : (sorted[m / 2 - 1] + sorted[m / 2]) / 2.0
    minNN    = sorted[0]
    maxNN    = sorted[m - 1]
    if (m > 1) {
        double ss = 0.0
        for (v in nnUm) { double dv = (v as double) - meanNN; ss += dv * dv }
        sdNN = Math.sqrt(ss / (m - 1))   // örneklem standart sapması (N−1)
    }
    // Robust MAD = medyan(|xᵢ − medyan|)
    def dev = nnUm.collect { Math.abs((it as double) - medianNN) }.sort(false)
    int md = dev.size()
    madNN = (md % 2 == 1) ? dev[(int)(md / 2)] : (dev[md / 2 - 1] + dev[md / 2]) / 2.0
}
double withinPct = (na > 0) ? 100.0 * withinRad / na : 0.0

// ── 8) Kilitli özet anotasyonu ───────────────────────────────────────────────
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
// Ad commitSummary'de verilir: Ekle → "Çapraz NN Özet (A→B) (2)" …; hiçbir şey sorulmadan silinmez.
summary.measurements["A (${classA}) hücre sayısı"]        = na as double
summary.measurements["B (${classB}) hücre sayısı"]         = nb as double
summary.measurements['Ortalama çapraz NN (µm)']             = meanNN
summary.measurements['SS çapraz NN (µm)']                   = sdNN
summary.measurements['Medyan çapraz NN (µm)']               = medianNN
summary.measurements['MAD çapraz NN (µm)']                  = madNN
summary.measurements['Minimum çapraz NN (µm)']              = minNN
summary.measurements['Maksimum çapraz NN (µm)']             = maxNN
summary.measurements["A hücresi yarıçap içinde (adet)"]    = withinRad as double
summary.measurements["A hücresi yarıçap içinde (%)"]       = withinPct
summary.measurements['Yarıçap (µm)']                        = radiusUm
summary.setLocked(true)
def sumRes = commitSummary(imageData.getHierarchy(), summaryName, summarySigKey, writeMode, summary)
QP.fireHierarchyUpdate()

// ── 9) Sonucu sun ────────────────────────────────────────────────────────────
def body = new StringBuilder()
body << "ÇAPRAZ-TİP EN YAKIN KOMŞU MESAFESİ\n"
body << "═══════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "A sınıfı (kaynak)  : %s (%,d hücre)%n", classA, na)
body << String.format(java.util.Locale.US, "B sınıfı (hedef)   : %s (%,d hücre)%n", classB, nb)
body << String.format(java.util.Locale.US, "Yarıçap eşiği      : %.0f µm%n", radiusUm)
body << "\n"
body << String.format(java.util.Locale.US, "Ortalama NN        : %.1f µm%n", meanNN)
body << String.format(java.util.Locale.US, "SS (std) NN        : %.1f µm%n", sdNN)
body << String.format(java.util.Locale.US, "Medyan NN          : %.1f µm%n", medianNN)
body << String.format(java.util.Locale.US, "MAD NN             : %.1f µm%n", madNN)
body << String.format(java.util.Locale.US, "Minimum NN         : %.1f µm%n", minNN)
body << String.format(java.util.Locale.US, "Maksimum NN        : %.1f µm%n", maxNN)
body << "───────────────────────────────────────────\n"
body << String.format(java.util.Locale.US,
    "Yarıçap içinde A   : %,d / %,d  (%.1f %%)%n", withinRad, na, withinPct)
body << "\n"
body << "Her A hücresine '${measName}'\n"
body << "ölçümü yazıldı (Veri Dışa Aktarma modülü ile dışa aktarılır).\n"
body << "Ölçüme göre renklendirmek için: Measure → Show measurement maps.\n\n"
body << "Not: Mesafe merkez-merkez piksel mesafesi × µm/px kalibrasyon faktörüdür.\n"
body << "Bu bir UZAMSAL MESAFE ölçümüdür — klinik skor veya yorum DEĞİL.\n"
body << "(Summers 2022; Bankhead 2017)\n\n"
def noteText = modeNote(sumRes, measName)
if (noteText) body << noteText << "\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Çapraz NN mesafesi (${classA}→${classB})", body.toString())
println String.format(java.util.Locale.US,
    "✓ Çapraz NN yazıldı: '%s'→'%s', ortalama %.1f µm, medyan %.1f µm, yarıçap içinde %,d/%,d (%.1f%%).",
    classA, classB, meanNN, medianNN, withinRad, na, withinPct)

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
