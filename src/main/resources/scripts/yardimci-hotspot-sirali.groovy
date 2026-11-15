/**
 * Yardımcı - Sıralı Yoğun Odak (Hotspot) Seçici
 * ---------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Mevcut KARO anotasyonlarını (bir piksel sınıflandırıcı ya da ızgara betiğiyle
 *   daha önce oluşturulmuş) seçilen bir ÖLÇÜME göre büyükten küçüğe sıralar ve
 *   ilk N (top-N) karoyu "yoğun odak (hotspot)" olarak işaretler.
 *
 *   Adımlar:
 *     1. Hangi sınıfa (PathClass) ait karolara bakılacağını soran iletişim.
 *     2. O karolarda bulunan ölçüm anahtarlarından birini seçme iletişimi.
 *     3. Kaç hotspot (N) istendiğini soran sayı girişi (varsayılan 3).
 *     4. Karolar seçilen ölçüme göre büyükten küçüğe sıralanır; ilk N alınır.
 *     5. Her top-N karosuna "Hotspot sırası" ölçümü yazılır (1 = en yüksek).
 *     6. İsteğe bağlı: top-N karolara "Hotspot" PathClass atanır.
 *     7. Kilitli "Hotspot Özet" anotasyonu: top-N ortalaması + SD.
 *     8. Sonuç penceresinde sıralı tablo (sıra, değer, alan µm², sentroid).
 *
 * NE ÖLÇER:
 *   • Seçilen ölçümün top-N ortalamasını ve SD değerini üretir.
 *   • Her karoyu "Hotspot sırası" numarası ile etiketler.
 *   • Klinik kategori, eşik, skor veya yorum ÜRETMEZ.
 *   • Sütun adı "Top-N karo ortalama (<ölçüm adı>)" biçimindedir —
 *     hiçbir kılavuz adı veya kesim değeri içermez.
 *
 * KULLANIM:
 *   1. Karo anotasyonları olan (ör. piksel sınıflandırıcı çıktısı,
 *      Ki-67 heterojenlik ızgarası) bir lam açın.
 *   2. [Extensions → Atölye → Yardımcılar → Skorlama ve ölçüm → Sıralı yoğun odak seçici (top-N)]
 *      (ya da [Automate → Project scripts → bu betik])
 *   3. Sınıf → ölçüm → N iletişimlerini yanıtlayın.
 *
 * ÇIKTI:
 *   • Seçilen top-N karolara "Hotspot sırası" ölçümü (Veri dışa aktarma modülü ile dışa aktarılır).
 *   • İsteğe bağlı: top-N karolara "Hotspot" PathClass.
 *   • Kilitli "Hotspot Özet" anotasyonu: ölçüm ortalaması + SD.
 *   • Sonuç penceresi: sıralı tablo.
 *
 * YENİDEN ÇALIŞTIRMA:
 *   Lamda bu betiğin önceki "Hotspot Özet" anotasyonu varsa betik hesaplamadan önce SORAR:
 *   Ekle (varsayılan; hiçbir şey silinmez, yeni özet "Hotspot Özet (2)" olur) / Değiştir
 *   (yalnız bu betiğin önceki özetleri silinir) / İptal (hiçbir şey yazılmaz). Başsız kipte
 *   önceki özet varsa betik durur: `-a mevcut=ekle` ya da `-a mevcut=degistir` verin.
 *   Karolardaki "Hotspot sırası" ölçümü her durumda yeni çalıştırmaya göre güncellenir.
 *
 * YÖNTEM / KAYNAK:
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *   • "Hotspot" ızgara yaklaşımı: dijital patolojide standart ön analiz adımı.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/hotspot-sirali')

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
String summaryName    = "Hotspot Özet"
String rankMeasKey    = "Hotspot sırası"
String hotspotClass   = "Hotspot"

// ── Önceki sonuç: SORULMADAN SİLİNMEZ (ev kuralı) ───────────────────────────
// Bu betik her çalıştırmada kilitli bir özet anotasyonu yazar. Eskiden aynı adlı özeti sessizce siliyordu.
//   Ekle (varsayılan) → hiçbir şey silinmez; yeni özet "ad (2)", "ad (3)" … adını alır.
//   Değiştir          → yalnız bu betiğin önceki özetleri silinir (tam ad ya da "ad (N)" VE betiğin imza ölçümü).
//   Hücrelere / karolara / anotasyonlara yazılan ölçümler her iki durumda da yeni çalıştırmanın değerini alır.
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
String summarySigKey = 'Seçilen top-N'     // yalnız bu betiğin özetinde bulunan ölçüm adı (imza)
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
        String.format(java.util.Locale.US, workshopText('script.b86513216eaf', 'Bu lamda bu betiğin önceki çalıştırmasından kalan %,d özet anotasyonu var ("%s").\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni özet ayrı bir anotasyon olur ("%s" gibi) ve lamda iki özet birlikte durur.\n\n"Değiştir" seçilirse önceki özet anotasyonları silinir. Kendi anotasyonlarınıza dokunulmaz.\n\nHer iki seçenekte de karolardaki "Hotspot sırası" ölçümü yeni çalıştırmaya göre güncellenir.\n\nİptal: hiçbir şey hesaplanmaz, hiçbir şey yazılmaz.\n\nÖnceki sonuç ne olsun?'), prev.size(), base, nextOwnSetName(hierarchy, base)),
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
    if (res.prevNames) return String.format(java.util.Locale.US, workshopText('script.9cf501b5b9fc', 'DİKKAT — bu lamda önceki sonuç da duruyor: %s\nHiçbir şey silinmedi. Bu çalıştırmanın özeti "%s" adını aldı. Karolardaki "Hotspot sırası" ölçümü YENİ çalıştırmayı yansıtır; önceki özet anotasyonu ise önceki değerleri gösterir. Eskisini kaldırmak için o anotasyonu seçip silin ya da betiği yeniden çalıştırırken "Öncekini değiştir"i seçin.'), res.prevNames.join(', '), res.setName)
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

// ── 1) Ön kontrol ───────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    if (isHeadless) println "Görüntü açık değil."
    else Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.c323a51a3390', "Önce karo anotasyonları olan bir lam açın."))
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
boolean hasCalibration = (pw > 0) && (ph > 0)

// Eski özet hariç tüm anotasyonlar. Sınıfsız karolar da adaydır: QuPath'in kendi "Create tiles" komutu
// sınıfsız anotasyon üretir. Sınıfsız seçenekte, içinde başka anotasyon bulunan (üst) anotasyonlar karo sayılmaz.
String NOCLASS = "(sınıfsız)"
def allAnnos = QP.getAnnotationObjects().findAll { !isOwnSetName(it.getName(), summaryName) }   // "Hotspot Özet" ve "Hotspot Özet (N)" karo sayılmaz
def annParents = allAnnos.collect { it.getParent() }.findAll { it != null } as Set   // içinde anotasyon bulunanlar
def classOf = { a ->
    a.getPathClass() != null ? a.getPathClass().toString()
        : (annParents.contains(a) ? null : NOCLASS)
}
def classNames = allAnnos.collect { classOf(it) }.findAll { it != null && it != hotspotClass }.unique().sort()

if (classNames.isEmpty()) {
    def msg = workshopText('script.54391e3e373c', 'Karo anotasyonu bulunamadı. Bir piksel sınıflandırıcı, ızgara betiği ya da Create tiles çalıştırın.')
    if (isHeadless) println msg else Dialogs.showErrorMessage(workshopText('script.ab46e48d894b', "Karo yok"), msg)
    return
}

// ── 2) Parametreleri belirle (GUI veya headless prefs) ──────────────
String chosenClass
String chosenKey
int topN
boolean assignHotspotClass = false

if (isHeadless) {
    chosenClass = prefs.get('chosenClass', '')
    chosenKey   = prefs.get('chosenKey', '')
    topN        = prefs.getInt('topN', 3)
    if (!classNames.contains(chosenClass) || chosenKey.isEmpty()) {
        println "Headless: prefs eksik. Önce arayüzden bir kez çalıştırın.\n" +
                "Sınıflar: ${classNames.join(', ')}"
        return
    }
    assignHotspotClass = false
} else {
    def defClass = classNames.contains(prefs.get('chosenClass', '')) ? prefs.get('chosenClass', '') : classNames[0]
    chosenClass = Dialogs.showChoiceDialog(workshopText('script.b86011fc7111', "Yoğun odak (hotspot) sınıfı"),
        workshopText('script.aae4db5f454e', "Hangi sınıftaki karolar sıralansın?"), classNames, defClass)
    if (chosenClass == null) { println "İptal edildi."; return }

    def tiles0 = allAnnos.findAll { classOf(it) == chosenClass }
    def measurementKeys = tiles0
        .collectMany { tile -> tile.measurements.keySet().toList() }
        .findAll { !it.startsWith("Hotspot") }
        .unique()
        .sort()

    if (measurementKeys.isEmpty()) {
        Dialogs.showErrorMessage(workshopText('script.e3dc9a444a25', "Ölçüm yok"),
            "'${chosenClass}${workshopText('script.57e265c19627', '\' sınıfındaki karolarda sayısal ölçüm bulunamadı.')}")
        return
    }
    def defKey = measurementKeys.contains(prefs.get('chosenKey', '')) ? prefs.get('chosenKey', '') : measurementKeys[0]
    chosenKey = Dialogs.showChoiceDialog(workshopText('script.59fee3f513c9', "Sıralama ölçümü"),
        workshopText('script.b0a99e142359', "Karolar hangi ölçüme göre sıralansın? (büyükten küçüğe)"), measurementKeys, defKey)
    if (chosenKey == null) { println "İptal edildi."; return }

    def nStr = Dialogs.showInputDialog(workshopText('script.a2bf2fab9c5f', "Top-N yoğun odak"), workshopText('script.ddc76c5c8b33', "Kaç yoğun odak (hotspot) karosu seçilsin? (ör. 3)"), prefs.get('topN', '3'))
    if (nStr == null) { println "İptal edildi."; return }
    topN = nStr.trim().isInteger() ? nStr.trim().toInteger() : 3   // (eski .coerceToType(int) Integer'da yok → MissingMethodException)
    if (topN < 1) topN = 1

    assignHotspotClass = Dialogs.showConfirmDialog(workshopText('script.8c878a235031', "Sınıf ata"),
        "${workshopText('script.a906a73ce2e7', 'Top-')}${topN}${workshopText('script.8eb5dea3ecbc', ' karolara "')}${hotspotClass}${workshopText('script.a6ce09fc47ec', '" sınıfı atansın mı?')}")

    prefs.put('chosenClass', chosenClass)
    prefs.put('chosenKey',   chosenKey)
    prefs.put('topN',        topN as String)
    try { prefs.flush() } catch (Throwable ig) {}
}

// ── 2b) Önceki özet var mı? SOR (GUI) ya da -a mevcut=… (başsız) — hesaplamadan ve yazmadan ÖNCE ─
String writeMode
if (isHeadless) {
    def hd = headlessDecision(imageData.getHierarchy(), summaryName, summarySigKey, mevcutArg)
    if (hd.mode == null) { println hd.error; return }
    writeMode = hd.mode
} else {
    writeMode = askExisting(imageData.getHierarchy(), summaryName, summarySigKey)
    if (writeMode == null) { println "İptal edildi."; return }
}

// ── 3) Karoları topla, sırala, top-N al ─────────────────────────────
// Önceki çalıştırmadan kalan "Hotspot" etiketlerini seçili sınıfa geri döndür ki aday
// havuzu çalıştırmalar arasında küçülmesin (yeniden çalıştırınca tüm karolar aday kalır).
if (assignHotspotClass) {
    // Yalnız bu betiğin işaretlediği (sıra ölçümü taşıyan) karolar geri döndürülür.
    def resetPC = (chosenClass == NOCLASS) ? null : QP.getPathClass(chosenClass)
    allAnnos.findAll { it.getPathClass()?.toString() == hotspotClass && it.measurements.containsKey(rankMeasKey) }
            .each { it.setPathClass(resetPC) }
}
def tiles = allAnnos.findAll { classOf(it) == chosenClass }
if (tiles.isEmpty()) {
    def msg = String.format(java.util.Locale.US, workshopText('script.7fab97699e26', '\'%s\' sınıfında karo bulunamadı.'), chosenClass)
    if (isHeadless) println msg else Dialogs.showErrorMessage(workshopText('script.ab46e48d894b', "Karo yok"), msg)
    return
}
// Seçili ölçümü TAŞIMAYAN karolar sıralamaya girmez: eskiden -Double.MAX_VALUE nöbetçi değeriyle
// listeye alınıp rank/"Hotspot" sınıfı alabiliyor ve ortalamayı (~ -1e308) bozuyordu.
def scoredTiles = tiles.findAll { tile ->
    def raw = tile.measurements[chosenKey]
    raw != null && Double.isFinite((raw as double))
}.collect { tile ->
    double val = tile.measurements[chosenKey] as double
    double area = hasCalibration ? tile.getROI().getArea() * pw * ph : tile.getROI().getArea()
    [tile: tile, value: val, area: area]
}.sort { -it.value }
if (scoredTiles.isEmpty()) {
    def msg = String.format(java.util.Locale.US, workshopText('script.83eb5cdf89a6', '\'%s\' sınıfındaki %s karonun hiçbirinde \'%s\' ölçümü yok.'), chosenClass, tiles.size(), chosenKey)
    if (isHeadless) println msg else Dialogs.showErrorMessage(workshopText('script.e3dc9a444a25', "Ölçüm yok"), msg)
    return
}
if (scoredTiles.size() < tiles.size())
    println "Not: ${tiles.size() - scoredTiles.size()} karo '${chosenKey}' ölçümünü taşımıyor — sıralamaya alınmadı."

int actualN = Math.min(topN, scoredTiles.size())
def topTiles = scoredTiles.take(actualN)

// ── 4) Ölçümleri yaz, PathClass ata, özet oluştur ───────────────────
// Önceki rank ölçümlerini temizle
tiles.each { tile -> tile.measurements.remove(rankMeasKey) }

topTiles.eachWithIndex { entry, idx ->
    entry.tile.measurements[rankMeasKey] = (idx + 1) as double
}

if (assignHotspotClass) {
    // Önceki "Hotspot" etiketleri (3) adımında seçili sınıfa geri döndürüldü;
    // burada yalnız yeni top-N karolara "Hotspot" sınıfı atanır.
    def hotspotPC = QP.getPathClass(hotspotClass)
    topTiles.each { entry -> entry.tile.setPathClass(hotspotPC) }
}

def topValues = topTiles.collect { it.value }.findAll { Double.isFinite(it) }
double topMean = topValues.isEmpty() ? Double.NaN : (topValues.sum() as double) / topValues.size()
double topSD   = Double.NaN
if (topValues.size() > 1) {
    // Örneklem SD (n−1): QuPath'in ve tablolama yazılımlarının SD'si ile aynı tanım.
    double var0 = topValues.collect { v -> (v - topMean) * (v - topMean) }.sum() / (topValues.size() - 1)
    topSD = Math.sqrt(var0)
}

def srv  = imageData.getServer()
def ozet = PathObjects.createAnnotationObject(
    ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(), ImagePlane.getDefaultPlane()))
// Ad commitSummary'de verilir: Ekle → "Hotspot Özet (2)" …; hiçbir şey sorulmadan silinmez.
ozet.measurements["Toplam karo sayısı (${chosenClass})"]  = tiles.size() as double
ozet.measurements["Seçilen top-N"]                        = actualN as double
ozet.measurements["Top-N karo ortalama (${chosenKey})"]   = topMean
// Ad "n-1" taşır: eski sürüm "Top-N karo SD (…)" adıyla n bölenli SD yazıyordu; karışmasın.
ozet.measurements["Top-N karo SD, n-1 (${chosenKey})"]    = topSD
ozet.setLocked(true)
def sumRes = commitSummary(imageData.getHierarchy(), summaryName, summarySigKey, writeMode, ozet)
QP.fireHierarchyUpdate()

// ── 5) Sonuç penceresi ───────────────────────────────────────────────
def fmt      = { double v, String pat -> Double.isFinite(v) ? String.format(java.util.Locale.US, pat, v) : 'NaN' }
def areaUnit = hasCalibration ? 'µm²' : 'px²'
def body     = new StringBuilder()
body << workshopText('script.233b62645c63', 'SIRALI YOĞUN ODAK (HOTSPOT) SEÇİCİ\n')
body << "═══════════════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, workshopText('script.7015e144b386', 'Sınıf       : %s (%d karo)%n'), chosenClass, tiles.size())
body << String.format(java.util.Locale.US, workshopText('script.73f198b3c847', 'Ölçüm       : %s%n'), chosenKey)
body << String.format(java.util.Locale.US, "Top-N       : %d%n%n", actualN)
body << String.format(java.util.Locale.US, "%-5s  %-14s  %-14s  %s%n",
    workshopText('script.a6e66797310e', 'Sıra'), workshopText('script.4cfa2b03d54b', 'Değer'), String.format(java.util.Locale.US, workshopText('script.c6f0aae57ccc', 'Alan (%s)'), areaUnit), workshopText('script.c8df26b74d89', 'Sentroid (px)'))
body << "─────  ──────────────  ────────────────  ──────────────────────\n"
topTiles.eachWithIndex { entry, idx ->
    double cx = entry.tile.getROI().getCentroidX()
    double cy = entry.tile.getROI().getCentroidY()
    body << String.format(java.util.Locale.US, "%-5d  %-14.4f  %,-14.0f  (%.0f, %.0f)%n",
        idx + 1, entry.value, entry.area, cx, cy)
}
body << "\n"
body << String.format(java.util.Locale.US,
    workshopText('script.6ad24188f9f0', 'Top-N karo ortalama (%s): %s%n'), chosenKey, fmt(topMean, "%.4f"))
body << String.format(java.util.Locale.US,
    workshopText('script.11219069afcd', 'Top-N karo SD (n−1)   (%s): %s%n'), chosenKey, fmt(topSD, "%.4f"))
if (scoredTiles.size() < tiles.size()) body << String.format(java.util.Locale.US,
    workshopText('script.c0ab73cadabc', '%nNot: %d karo bu ölçümü taşımıyor; sıralamaya alınmadı.%n'), tiles.size() - scoredTiles.size())
if (assignHotspotClass) body << String.format(java.util.Locale.US,
    workshopText('script.3c95e6a828bb', '%nTop-%d karolara "%s" sınıfı atandı.%n'), actualN, hotspotClass)
if (chosenClass == NOCLASS) body << workshopText('script.6e982c611621', '\nSınıfsız seçenek: içinde başka anotasyon bulunan (üst) anotasyonlar karo sayılmadı. Üst anotasyon\nhiyerarşide karolarla aynı düzeydeyse sıralamaya girebilir; tablodaki alanları denetleyin.\n')
body << String.format(java.util.Locale.US, workshopText('script.197eb63434bc', '\nHer top-N karosuna \'%s\' ölçümü yazıldı (Veri dışa aktarma modülü ile dışa aktarılır).\n'), rankMeasKey)
body << workshopText('script.755f84738421', 'Bu bir betimsel sıralama ölçümüdür; klinik kategori veya eşik üretmez.\n\n')
def noteText = modeNote(sumRes)
if (noteText) body << noteText << "\n\n"
body << workshopText('script.cf6099f0a005', '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')

showResultWindow(workshopText('script.1aa4965aa3ea', 'Sıralı Yoğun Odak Seçici'), body.toString())
println String.format(java.util.Locale.US,
    "✓ Yoğun odak sıralama: sınıf='%s', ölçüm='%s', top-%d, ortalama=%s",
    chosenClass, chosenKey, actualN, fmt(topMean, "%.4f"))

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
