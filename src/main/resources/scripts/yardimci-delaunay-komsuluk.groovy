/**
 * Yardımcı - Delaunay Komşuluk Özellikleri
 * -----------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Hücre merkezlerinden bir Delaunay komşuluk ağı kurar ve her hücreye komşu
 * SAYISI (+ kalibre ise ortalama komşu MESAFESİ, µm) ölçümü ekler. Doku
 * mimarisinin (hücre-hücre bağlantısallığı) sayısal bir betimidir.
 *
 * YÖNTEM: QuPath'in YERLEŞİK çekirdek API'si `qupath.lib.analysis.DelaunayTools`
 * kullanılır (0.6+). Eski `DelaunayClusteringPlugin` sınıfı 0.6'da kaldırıldığı
 * için artık ona BAĞLI DEĞİLDİR — komşuluk ağı doğrudan koordinatlardan, ek
 * eklenti gerektirmeden hesaplanır.
 *
 * QuPath KARŞILIĞI (GUI):
 *   [Analyze → Spatial analysis → Delaunay cluster features 2D]
 *   Aynı komşuluk grafiğini menüden de üretebilirsiniz; bu betik onu tek
 *   tıkla otomatikleştirir ve sade bir özet yazar.
 *
 * GİRDİ (ÖN KOŞUL):
 *   • Lamda hücre tespitleri olmalı (Hücre Tespiti, Nükleer Boya, Sitoplazmik
 *     Boya veya Tümör İçi Ki-67 modüllerinde üretilmiş olabilir). Betik tespit YAPMAZ.
 *
 * ÇIKTI:
 *   • Her hücre: "Delaunay: Num neighbors" (+ kalibre ise
 *     "Delaunay: Mean neighbor distance (µm)") ölçümleri (Veri Dışa Aktarma modülü ile aktarılır)
 *   • Kilitli "Delaunay Özet": ortalama komşu sayısı (+ ortalama komşu mesafesi)
 *
 * PARAMETRE:
 *   • maxEdgeMicrons > 0 ise yalnız bu mesafeden YAKIN komşular sayılır
 *     (uzun kenarları budar; yalnız kalibre lamlarda etkilidir). 0 = sınırsız.
 *
 * YENİDEN ÇALIŞTIRMA:
 *   Lamda bu betiğin önceki "Delaunay Özet" anotasyonu varsa betik hesaplamadan önce SORAR:
 *   Ekle (varsayılan; hiçbir şey silinmez, yeni özet "Delaunay Özet (2)" olur) / Değiştir
 *   (yalnız bu betiğin önceki özetleri silinir) / İptal (hiçbir şey yazılmaz). Başsız kipte
 *   önceki özet varsa betik durur: `-a mevcut=ekle` ya da `-a mevcut=degistir` verin.
 *   Hücrelerdeki "Delaunay: …" ölçümleri her durumda yeni değerle üzerine yazılır.
 *
 * YÖNTEM REFERANSI:
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *   • From Samples to Knowledge 2025 (FS2K), Session 12 — uzamsal analiz:
 *     saramcardle.github.io/FS2K
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.analysis.DelaunayTools

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
            textArea.setWrapText(true)
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
double maxEdgeMicrons = 0.0   // 0 = sınırsız (saf Delaunay); >0 = uzun kenarları kes (yalnız kalibre)

// ── Önceki sonuç: SORULMADAN SİLİNMEZ (ev kuralı) ───────────────────────────
// Bu betik her çalıştırmada kilitli bir özet anotasyonu yazar. Eskiden aynı adlı özeti sessizce siliyordu.
//   Ekle (varsayılan) → hiçbir şey silinmez; yeni özet "ad (2)", "ad (3)" … adını alır.
//   Değiştir          → yalnız bu betiğin önceki özetleri silinir (tam ad ya da "ad (N)" VE betiğin imza ölçümü).
//   Hücrelere / karolara / anotasyonlara yazılan ölçümler her iki durumda da yeni çalıştırmanın değerini alır.
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
String summarySigKey = 'Ortalama komşu sayısı'     // yalnız bu betiğin özetinde bulunan ölçüm adı (imza)
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
        String.format(java.util.Locale.US, workshopText('script.f784569d8add', 'Bu lamda bu betiğin önceki çalıştırmasından kalan %,d özet anotasyonu var ("%s").\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni özet ayrı bir anotasyon olur ("%s" gibi) ve lamda iki özet birlikte durur.\n\n"Değiştir" seçilirse önceki özet anotasyonları silinir. Kendi anotasyonlarınıza dokunulmaz.\n\nHer iki seçenekte de hücrelerdeki "Delaunay: …" ölçümleri yeni değerle üzerine yazılır.\n\nİptal: hiçbir şey hesaplanmaz, hiçbir şey yazılmaz.\n\nÖnceki sonuç ne olsun?'), prev.size(), base, nextOwnSetName(hierarchy, base)),
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
    if (res.prevNames) return String.format(java.util.Locale.US, workshopText('script.ae22ca275c23', 'DİKKAT — bu lamda önceki sonuç da duruyor: %s\nHiçbir şey silinmedi. Bu çalıştırmanın özeti "%s" adını aldı. Hücrelerdeki "Delaunay: …" ölçümleri YENİ çalıştırmayı yansıtır; önceki özet anotasyonu ise önceki değerleri gösterir. Eskisini kaldırmak için o anotasyonu seçip silin ya da betiği yeniden çalıştırırken "Öncekini değiştir"i seçin.'), res.prevNames.join(', '), res.setName)
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
def cells = QP.getDetectionObjects().findAll { it.getROI() != null }
if (cells.size() < 3) {
    def msg = workshopText('script.58ecef12b4c3', 'Delaunay üçgenlemesi için en az 3 hücre gerekir.\nÖnce Hücre Tespiti, Nükleer Boya, Sitoplazmik Boya veya Tümör İçi Ki-67 modüllerinden biriyle tespit yapın.')
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.bd9ad9944f29', "Yetersiz hücre"), msg)
    return
}

def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
boolean hasMicrons = (cal.hasPixelSizeMicrons() && pw > 0 && ph > 0)
boolean limitByDist = hasMicrons && maxEdgeMicrons > 0

// ── 1b) Önceki özet var mı? SOR (GUI) ya da -a mevcut=… (başsız) — hesaplamadan ve yazmadan ÖNCE ─
String writeMode
if (isHeadless) {
    def hd = headlessDecision(imageData.getHierarchy(), "Delaunay Özet", summarySigKey, mevcutArg)
    if (hd.mode == null) { println hd.error; return }
    writeMode = hd.mode
} else {
    writeMode = askExisting(imageData.getHierarchy(), "Delaunay Özet", summarySigKey)
    if (writeMode == null) { println "İptal edildi."; return }
}

// ── 2) Delaunay komşuluk ağı (yerleşik DelaunayTools, 0.6+) ─────────
println String.format(java.util.Locale.US, "Delaunay komşuluk hesaplanıyor (%,d hücre)...", cells.size())
def subdivision = DelaunayTools.newBuilder(cells).centroids().calibration(cal).build()
def allNeighbors = subdivision.getAllNeighbors()   // Map<PathObject, List<PathObject>>

// ── 3) Her hücreye komşu sayısı (+ ortalama komşu mesafesi) yaz ─────
def neighborStats = { cell ->
    def nbs = allNeighbors.getOrDefault(cell, Collections.emptyList())
    double sx = cell.getROI().getCentroidX(), sy = cell.getROI().getCentroidY()
    int kept = 0
    double distSum = 0.0
    nbs.each { nb ->
        double dx = (sx - nb.getROI().getCentroidX()) * pw
        double dy = (sy - nb.getROI().getCentroidY()) * ph
        double d = Math.sqrt(dx * dx + dy * dy)
        if (limitByDist && d > maxEdgeMicrons) return
        kept++
        distSum += d
    }
    [count: kept, meanDist: kept > 0 ? distSum / kept : Double.NaN]
}

def counts = []
def dists = []
cells.each { c ->
    def st = neighborStats(c)
    c.measurements['Delaunay: Num neighbors'] = st.count as double
    counts << (st.count as double)
    if (hasMicrons && st.count > 0) {
        c.measurements['Delaunay: Mean neighbor distance (µm)'] = st.meanDist
        dists << st.meanDist
    }
}

double meanNeighbors = counts.isEmpty() ? 0.0 : counts.sum() / counts.size()
double meanDistOverall = dists.isEmpty() ? Double.NaN : dists.sum() / dists.size()

// ── 4) Kilitli özet anotasyonu ──────────────────────────────────────
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
// Ad commitSummary'de verilir: Ekle → "Delaunay Özet (2)" …; hiçbir şey sorulmadan silinmez.
summary.measurements['Hücre sayısı']           = cells.size() as double
summary.measurements['Ortalama komşu sayısı']   = meanNeighbors
if (!Double.isNaN(meanDistOverall)) summary.measurements['Ortalama komşu mesafesi (µm)'] = meanDistOverall
if (limitByDist) summary.measurements['Maks kenar eşiği (µm)'] = maxEdgeMicrons
summary.setLocked(true)
def sumRes = commitSummary(imageData.getHierarchy(), "Delaunay Özet", summarySigKey, writeMode, summary)
QP.fireHierarchyUpdate()

def body = new StringBuilder()
body << workshopText('script.e5dbc68993e1', 'DELAUNAY KOMŞULUK\n')
body << "════════════════════\n\n"
body << String.format(java.util.Locale.US, workshopText('script.439cc27833b7', 'Hücre sayısı          : %,d%n'), cells.size())
body << String.format(java.util.Locale.US, workshopText('script.ba255791a418', 'Ortalama komşu sayısı : %.2f%n'), meanNeighbors)
if (!Double.isNaN(meanDistOverall)) {
    body << String.format(java.util.Locale.US, workshopText('script.a388ce10cd42', 'Ortalama komşu mesafe : %.1f µm%n'), meanDistOverall)
}
if (limitByDist) {
    body << String.format(java.util.Locale.US, workshopText('script.9294c1bea302', 'Maks kenar eşiği      : %.0f µm (uzun kenarlar budandı)%n'), maxEdgeMicrons)
}
body << "\n"
body << workshopText('script.2896e40397be', 'Her hücreye \'Delaunay: Num neighbors\'')
body << (hasMicrons ? " + 'Delaunay: Mean neighbor distance (µm)'" : "")
body << workshopText('script.2717387d2b6e', ' yazıldı.\n')
body << workshopText('script.c74fe62d4972', 'Yüksek komşu sayısı / küçük komşu mesafesi → sıkı doku (kümelenme).\n')
body << workshopText('script.a198b78cfd4b', 'Veri Dışa Aktarma modülü ile dışa aktarılır.\n\n')
def noteText = modeNote(sumRes)
if (noteText) body << noteText << "\n\n"
body << workshopText('script.cf6099f0a005', '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')

showResultWindow(workshopText('script.30a9112c0c22', 'Delaunay komşuluk'), body.toString())
println String.format(java.util.Locale.US, "✓ Delaunay özeti yazıldı (ortalama %.2f komşu).", meanNeighbors)

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
