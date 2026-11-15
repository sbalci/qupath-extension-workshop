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
 *   2. [Extensions → Atölye → Yardımcılar → Uzamsal analiz → Sıralı Hotspot Seçici]
 *      (ya da [Automate → Project scripts → bu betik])
 *   3. Sınıf → ölçüm → N iletişimlerini yanıtlayın.
 *
 * ÇIKTI:
 *   • Seçilen top-N karolara "Hotspot sırası" ölçümü (Veri dışa aktarma modülü ile dışa aktarılır).
 *   • İsteğe bağlı: top-N karolara "Hotspot" PathClass.
 *   • Kilitli "Hotspot Özet" anotasyonu: ölçüm ortalaması + SD.
 *   • Sonuç penceresi: sıralı tablo.
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
def allAnnos = QP.getAnnotationObjects().findAll { it.getName() != summaryName }
def annParents = allAnnos.collect { it.getParent() }.findAll { it != null } as Set   // içinde anotasyon bulunanlar
def classOf = { a ->
    a.getPathClass() != null ? a.getPathClass().toString()
        : (annParents.contains(a) ? null : NOCLASS)
}
def classNames = allAnnos.collect { classOf(it) }.findAll { it != null && it != hotspotClass }.unique().sort()

if (classNames.isEmpty()) {
    def msg = "Karo anotasyonu bulunamadı. Bir piksel sınıflandırıcı, ızgara betiği ya da Create tiles çalıştırın."
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
    def msg = "'${chosenClass}' sınıfında karo bulunamadı."
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
    def msg = "'${chosenClass}' sınıfındaki ${tiles.size()} karonun hiçbirinde '${chosenKey}' ölçümü yok."
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
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == summaryName }, false)

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
ozet.setName(summaryName)
ozet.measurements["Toplam karo sayısı (${chosenClass})"]  = tiles.size() as double
ozet.measurements["Seçilen top-N"]                        = actualN as double
ozet.measurements["Top-N karo ortalama (${chosenKey})"]   = topMean
// Ad "n-1" taşır: eski sürüm "Top-N karo SD (…)" adıyla n bölenli SD yazıyordu; karışmasın.
ozet.measurements["Top-N karo SD, n-1 (${chosenKey})"]    = topSD
ozet.setLocked(true)
QP.addObjects([ozet])
QP.fireHierarchyUpdate()

// ── 5) Sonuç penceresi ───────────────────────────────────────────────
def fmt      = { double v, String pat -> Double.isFinite(v) ? String.format(java.util.Locale.US, pat, v) : 'NaN' }
def areaUnit = hasCalibration ? 'µm²' : 'px²'
def body     = new StringBuilder()
body << "SIRALI YOĞUN ODAK (HOTSPOT) SEÇİCİ\n"
body << "═══════════════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Sınıf       : %s (%d karo)%n", chosenClass, tiles.size())
body << String.format(java.util.Locale.US, "Ölçüm       : %s%n", chosenKey)
body << String.format(java.util.Locale.US, "Top-N       : %d%n%n", actualN)
body << String.format(java.util.Locale.US, "%-5s  %-14s  %-14s  %s%n",
    "Sıra", "Değer", String.format(java.util.Locale.US, "Alan (%s)", areaUnit), "Sentroid (px)")
body << "─────  ──────────────  ────────────────  ──────────────────────\n"
topTiles.eachWithIndex { entry, idx ->
    double cx = entry.tile.getROI().getCentroidX()
    double cy = entry.tile.getROI().getCentroidY()
    body << String.format(java.util.Locale.US, "%-5d  %-14.4f  %,-14.0f  (%.0f, %.0f)%n",
        idx + 1, entry.value, entry.area, cx, cy)
}
body << "\n"
body << String.format(java.util.Locale.US,
    "Top-N karo ortalama (%s): %s%n", chosenKey, fmt(topMean, "%.4f"))
body << String.format(java.util.Locale.US,
    "Top-N karo SD (n−1)   (%s): %s%n", chosenKey, fmt(topSD, "%.4f"))
if (scoredTiles.size() < tiles.size()) body << String.format(java.util.Locale.US,
    "%nNot: %d karo bu ölçümü taşımıyor; sıralamaya alınmadı.%n", tiles.size() - scoredTiles.size())
if (assignHotspotClass) body << String.format(java.util.Locale.US,
    "%nTop-%d karolara \"%s\" sınıfı atandı.%n", actualN, hotspotClass)
if (chosenClass == NOCLASS) body << "\nSınıfsız seçenek: içinde başka anotasyon bulunan (üst) anotasyonlar karo sayılmadı. Üst anotasyon\nhiyerarşide karolarla aynı düzeydeyse sıralamaya girebilir; tablodaki alanları denetleyin.\n"
body << "\nHer top-N karosuna '${rankMeasKey}' ölçümü yazıldı (Veri dışa aktarma modülü ile dışa aktarılır).\n"
body << "Bu bir betimsel sıralama ölçümüdür; klinik kategori veya eşik üretmez.\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Sıralı Yoğun Odak Seçici", body.toString())
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
