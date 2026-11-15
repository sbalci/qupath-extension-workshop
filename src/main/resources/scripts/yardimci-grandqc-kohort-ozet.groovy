/**
 * Yardımcı - GrandQC Kohort KK Özeti
 * -----------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Açık projedeki HER görüntü için hiyerarşiyi diskten okur; GrandQC sihirbazının
 *   ürettiği "GrandQC KK" adlı (sentinel) anotasyonları toplar ve lam başına
 *   kalite-kontrol özetini GENİŞ TABLO (bir satır = bir görüntü) TSV'ye yazar:
 *     • doku alanı (mm²), temiz doku alanı (mm²), artefakt alanı (mm²) ve doku içi %
 *     • doku parça (bağlı bileşen) sayısı
 *     • her artefakt sınıfı için alan (mm²) + anotasyon (bölge) sayısı
 *   GrandQC anotasyonu olmayan lamlar atlanır (hata değil).
 *   Sihirbaz her çalıştırmada yeni bir set ekleyebilir ("GrandQC KK", "GrandQC KK (2)" …): bir lamda
 *   birden çok set varsa araç ÖNCE SORAR — tüm setler (set başına bir satır) ya da yalnız en yeni set.
 *   Başsız kipte: -a kopyalar=hepsi | enyeni.
 *
 *   GrandQC sihirbazının lam başına sonuç penceresini KOHORT düzeyine taşır:
 *   "tüm lam setimde kalite nasıldı?" sorusunu tek tabloda yanıtlar. Yerleşik
 *   "Kohort özet toplayıcı" ile aynı proje-döngüsü kalıbını izler.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Yalnızca var olan GrandQC anotasyonlarının GEOMETRİSİNDEN alan/sayı/% türetir.
 *   • Kalite notu, derece, eşik, kabul/ret ya da klinik yorum ÜRETMEZ.
 *   • Hiçbir imageData'yı KAYDETMEZ (saveImageData çağrısı yoktur) — salt okunur.
 *   • Alanlar yalnız kalibre görüntülerde mm² verilir; kalibre değilse hücre boş kalır.
 *   • Artefakt alanı doğrudan (artefakt sınıfları ∩ doku) olarak ölçülür — ayrı saklanan
 *     "Temiz doku" anotasyonuna bağlı DEĞİLDİR; böylece %100 artefaktlı lamda da doludur.
 *     "temiz_doku_mm2" ise "Temiz doku" anotasyonundan gelir (sihirbaz üretmediyse boş kalır).
 *
 * KULLANIM:
 *   1. Önce GrandQC sihirbazıyla lamları işleyip GeoJSON'u içe aktarın
 *      (tek lam ya da "Proje geneli içe aktar"). Anotasyonlar "GrandQC KK" adını taşır.
 *   2. [Extensions → Atölye → Yardımcılar → Klinik ve kohort → GrandQC kohort KK özeti]
 *      (ya da [Automate → Project scripts → bu betik])
 *   3. TSV <proje>/grandqc-kohort/ altında oluşur; sonuç penceresinde yol + özet görünür.
 *
 * ÇIKTI:
 *   • <proje>/grandqc-kohort/grandqc_kohort_ozet_<millis>.tsv
 *     Eksik hücreler boş string (Excel/R doğrudan okur).
 *   • Sonuç penceresi: özetlenen/atlanan görüntü sayısı, TSV yolu.
 *   • Headless: aynı TSV + konsol çıktısı.
 *
 * YÖNTEM / KAYNAK:
 *   • Weng Z ve ark. (2024), Nat Commun 15:10685 — GrandQC. doi:10.1038/s41467-024-54769-y
 *   • sbalci/metadata-qupath — proje döngüsü kalıbı (entry.readImageData() + close + gc).
 *   • Bankhead P et al. (2017), Sci Rep. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import java.io.File

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── showResultWindow (lint Check 8 — VERBATIM from yardimci-kesisim-alani) ───
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

            stage.setScene(new javafx.scene.Scene(root, 760, 540))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── GrandQC sabitleri (yardimci-grandqc-sihirbaz ile aynı) ───────────────────
def GRANDQC_SENTINEL = 'GrandQC KK'
def TISSUE_CLASS     = 'Tissue'
def CLEAN_CLASS      = 'Temiz doku'
def ARTIFACT_CLASSES = ['Tissue Fold', 'Dark Spot & Foreign Object',
                        'Pen Marking', 'Air Bubble & Slide Edge', 'Out of Focus']

// ── Birleştirme (RoiTools; başarısızsa JTS) + alan/parça sayımı ──────────────
def unionRoi = { rois ->
    if (rois == null || rois.isEmpty()) return null
    if (rois.size() == 1) return rois[0]
    try {
        return qupath.lib.roi.RoiTools.union(rois)
    } catch (Throwable t) {
        org.locationtech.jts.geom.Geometry g = null
        for (r in rois) { def gg = r.getGeometry(); g = (g == null) ? gg : g.union(gg) }
        return qupath.lib.roi.GeometryTools.geometryToROI(g, qupath.lib.regions.ImagePlane.getDefaultPlane())
    }
}
// Bir ROI için: kalibre ise mm² alan + parça (bağlı bileşen, alan>1px²) sayısı.
def areaAndFrags = { roi, double pw, double ph, boolean cal ->
    if (roi == null) return [mm2: Double.NaN, frags: 0]
    org.locationtech.jts.geom.Geometry g = null
    try { g = roi.getGeometry() } catch (Throwable t) { return [mm2: Double.NaN, frags: 0] }
    if (g == null) return [mm2: Double.NaN, frags: 0]
    double aPx = 0.0d; int frags = 0
    int n = g.getNumGeometries()
    for (int i = 0; i < n; i++) {
        double ai
        try { ai = g.getGeometryN(i).getArea() } catch (Throwable t) { ai = 0.0d }
        if (ai > 1.0d) { aPx += ai; frags++ }
    }
    return [mm2: (cal ? (aPx * pw * ph / 1_000_000.0d) : Double.NaN), frags: frags]
}
// İki ROI'nin KESİŞİM alanı (mm²). Artefakt alanını doğrudan (artefakt∩doku) ölç →
// ayrı saklanan "Temiz doku" anotasyonuna bağımlılığı kaldırır; %100 artefaktlı lamda
// (Temiz doku üretilememiş olsa bile) artefakt alanı yine doğru çıkar.
def intersectArea = { roiA, roiB, double pw, double ph, boolean cal ->
    if (roiA == null || roiB == null || !cal) return Double.NaN
    try {
        def g = roiA.getGeometry().intersection(roiB.getGeometry())
        if (g == null || g.isEmpty()) return 0.0d
        return g.getArea() * pw * ph / 1_000_000.0d
    } catch (Throwable t) { return Double.NaN }
}
def fmtArea = { double v -> Double.isNaN(v) ? '' : String.format(java.util.Locale.US, '%.4f', v) }
def fmtPct  = { double v -> Double.isNaN(v) ? '' : String.format(java.util.Locale.US, '%.1f', v) }

// ── Kopya grupları: bir lamda aynı sonucun "ad", "ad (2)", "ad (3)" … olarak yan yana durması ──
// Kural (tam eşleşme, önek eşleşmesi YOK): "X (N)" (N tam sayı ≥ 2) "X" tabanına aittir. Bir grup,
// o tabanın en az İKİ farklı numarası (taban = 1) aynı lamda varsa kopya grubudur; tek başına bir
// "X (N)" (X silinmiş) grup değildir. "X (notlarım)" ve "X (1)" kopya değildir. Sondaki " (N)" yalnız
// aynı tabanın bir kardeşi varsa kopya numarasıdır. Burada taban sabittir ("GrandQC KK"): "GrandQC KK",
// "GrandQC KK (2)", "GrandQC KK (3)" … ayrı SETLERDİR (aynı adı taşıyan tüm anotasyonlar bir set);
// en az iki set varsa kopya grubudur. En yeni = en yüksek numara (taban = 1).
// Başsız kipte soru sorulamaz: açık bağımsız değişken gerekir (ör. -a kopyalar=hepsi).
def headlessArg = { String key ->
    def list = []
    try { if (binding.hasVariable('args')) { def a = binding.getVariable('args'); if (a != null) list = (a as List) } } catch (Throwable ignore) {}
    def found = null
    list.each { a -> def s = a?.toString() ?: ''; int i = s.indexOf('='); if (i > 0 && s.substring(0, i).trim() == key) found = s.substring(i + 1).trim().toLowerCase(java.util.Locale.ROOT) }
    return found
}
def copiesParse = { String name ->
    if (name == null) return null
    def m = (name =~ '^(.+) \\((\\d{1,6})\\)$')
    if (!m.matches()) return null
    int n = Integer.parseInt(m.group(2))
    return (n >= 2) ? [base: m.group(1), index: n] : null
}
def copiesSetsOf = { Collection annos, String base ->
    def out = new TreeMap()
    annos.each { a ->
        String nm = a.getName()
        if (nm == null) return
        int idx = 0
        if (nm == base) idx = 1
        else { def p = copiesParse(nm); if (p != null && p.base == base) idx = p.index }
        if (idx > 0) out.get(idx, []) << a
    }
    return out
}
def copiesPickSets = { List setRows, boolean newest ->
    if (!newest) return setRows
    def best = new LinkedHashMap()
    setRows.each { r -> def cur = best[r.img]; if (cur == null || r.index > cur.index) best[r.img] = r }
    return new ArrayList(best.values())
}
def copiesAskChoice = { String title, String body, List options, String dflt ->
    if (javafx.application.Platform.isFxApplicationThread()) return Dialogs.showChoiceDialog(title, body, options, dflt)
    def answer = new java.util.concurrent.atomic.AtomicReference(null)
    def latch = new java.util.concurrent.CountDownLatch(1)
    javafx.application.Platform.runLater {
        try { answer.set(Dialogs.showChoiceDialog(title, body, options, dflt)) } finally { latch.countDown() }
    }
    latch.await()
    return answer.get()
}
// Bilinmeyen -a kopyalar=… değeri HER durumda durdurur (kopya olmasa da; fail closed).
def copiesArg = headlessArg('kopyalar')
if (copiesArg != null && copiesArg != 'hepsi' && copiesArg != 'enyeni') {
    println String.format(java.util.Locale.US, workshopText('script.866c3e4341b8', 'DURDU: bilinmeyen kopyalar değeri "%s". Geçerli değerler: hepsi | enyeni. Hiçbir şey yazılmadı.'), copiesArg)
    return
}

// ── 1) Ön kontrol: açık proje zorunlu ────────────────────────────────────────
def project = QP.getProject()
if (project == null) {
    def msg = "Önce bir QuPath projesi açın. (Dosya → Proje aç…)\nBu betik tek lam modunda çalışmaz."
    if (isHeadless) { println msg; return }
    Dialogs.showErrorMessage(workshopText('script.a38d0e6adac0', "Proje açık değil"), msg)
    return
}
def entries = project.getImageList()
if (entries == null || entries.isEmpty()) {
    def msg = "Projede görüntü bulunamadı."
    if (isHeadless) { println msg; return }
    Dialogs.showErrorMessage(workshopText('script.cb3ed2211f0a', "Boş proje"), msg)
    return
}

// ── 2) Çıktı dizini + sütun düzeni (klasör, soru yanıtlandıktan sonra, yazmadan hemen önce açılır) ──
def projectDir = null
try { projectDir = project.getPath()?.getParent()?.toFile() } catch (Throwable ignore) {}
def outDir = (projectDir != null) ? new File(projectDir, "grandqc-kohort") : new File(System.getProperty("java.io.tmpdir"), "grandqc-kohort")
long millis = System.currentTimeMillis()
def tsvFile = new File(outDir, String.format(java.util.Locale.US, "grandqc_kohort_ozet_%d.tsv", millis))

def columns = ["image_name", "doku_mm2", "temiz_doku_mm2", "artefakt_mm2", "artefakt_yuzde", "doku_parca_sayisi"]
ARTIFACT_CLASSES.each { c -> columns << (c + " · alan_mm2"); columns << (c + " · n") }

// Bir GrandQC setinin (aynı adı taşıyan anotasyonlar) lam başına özet satırı.
def buildRow = { String imgName, List sentinels, double pw, double ph, boolean isCal ->
    def byClass = sentinels.groupBy { it.getPathClass()?.getName() ?: 'Background' }
    def roisOf  = { String cn -> (byClass[cn] ?: []).collect { it.getROI() } }

    def tissueUnionRoi = unionRoi(roisOf(TISSUE_CLASS))
    def tissue = areaAndFrags(tissueUnionRoi, pw, ph, isCal)
    def clean  = areaAndFrags(unionRoi(roisOf(CLEAN_CLASS)), pw, ph, isCal)   // bilgi amaçlı (temiz_doku_mm2)
    // Artefakt alanı = (tüm artefakt sınıflarının birleşimi) ∩ doku — "Temiz doku"dan bağımsız.
    def artUnionRoi = unionRoi(ARTIFACT_CLASSES.collectMany { roisOf(it) })
    double artMm2
    if (tissueUnionRoi == null || !isCal)      artMm2 = Double.NaN         // doku yok / kalibre değil → boş
    else if (artUnionRoi == null)              artMm2 = 0.0d               // artefakt anotasyonu yok → 0
    else                                       artMm2 = intersectArea(artUnionRoi, tissueUnionRoi, pw, ph, isCal)
    double artPct = (!Double.isNaN(tissue.mm2) && tissue.mm2 > 0 && !Double.isNaN(artMm2)) ? (artMm2 / tissue.mm2 * 100.0d) : Double.NaN

    def row = new java.util.LinkedHashMap<String, String>()
    row["image_name"]        = imgName
    row["doku_mm2"]          = fmtArea(tissue.mm2)
    row["temiz_doku_mm2"]    = fmtArea(clean.mm2)
    row["artefakt_mm2"]      = fmtArea(artMm2)
    row["artefakt_yuzde"]    = fmtPct(artPct)
    row["doku_parca_sayisi"] = String.format(java.util.Locale.US, "%d", tissue.frags)
    ARTIFACT_CLASSES.each { c ->
        def rr = roisOf(c)
        def ar = areaAndFrags(unionRoi(rr), pw, ph, isCal)
        row[c + " · alan_mm2"] = fmtArea(ar.mm2)
        row[c + " · n"]        = String.format(java.util.Locale.US, "%d", rr.size())
    }
    return row
}

// ── 3) Tüm görüntüleri gez: GrandQC sentinel anotasyonlarını (her seti) topla ─
// setRows: set başına [img, name, index, setName, nAnn, row]. Bir lamda "GrandQC KK", "GrandQC KK (2)" …
// ayrı setlerdir (sihirbaz her çalıştırmada yeni set ekler); eskiden yalnız tam "GrandQC KK" okunuyordu.
def setRows     = []
int totalImages = entries.size()
int skipped     = 0   // GrandQC anotasyonu olmayan
int failed      = 0   // okunamayan
println String.format(java.util.Locale.US, "GrandQC kohort KK özeti başladı: %d görüntü", totalImages)

entries.eachWithIndex { entry, idx ->
    def imgName = entry.getImageName()
    def imageData = null
    try {
        imageData = entry.readImageData()
        def hierarchy = imageData.getHierarchy()
        def sets = copiesSetsOf(hierarchy.getAnnotationObjects() as List, GRANDQC_SENTINEL)
        if (sets.isEmpty()) {
            skipped++
            println String.format(java.util.Locale.US, "  [%d/%d] %s — GrandQC anotasyonu yok, atlandı", idx + 1, totalImages, imgName)
            return
        }
        def cal = imageData.getServer().getPixelCalibration()
        double pw = cal.getPixelWidthMicrons(); double ph = cal.getPixelHeightMicrons()
        boolean isCal = (pw > 0 && ph > 0 && !Double.isNaN(pw) && !Double.isNaN(ph))
        def built = []
        sets.each { setIdx, sentinels ->
            built << [img: idx, name: imgName, index: setIdx, setName: (setIdx == 1 ? GRANDQC_SENTINEL : (GRANDQC_SENTINEL + " (" + setIdx + ")")),
                      nAnn: sentinels.size(), row: buildRow(imgName, sentinels as List, pw, ph, isCal)]
        }
        setRows.addAll(built)
        println String.format(java.util.Locale.US, "  [%d/%d] %s — %d GrandQC anotasyonu", idx + 1, totalImages, imgName, sets.values().sum { it.size() })
    } catch (Throwable t) {
        failed++
        println String.format(java.util.Locale.US, "  [%d/%d] %s — HATA: %s", idx + 1, totalImages, imgName, (t.getMessage() ?: t.getClass().getSimpleName()))
    } finally {
        try { imageData?.getServer()?.close() } catch (Throwable ignore) {}
        imageData = null
        System.gc()
    }
}

if (setRows.isEmpty()) {
    def msg = "Hiçbir görüntüde GrandQC anotasyonu (\"" + GRANDQC_SENTINEL + "\") bulunamadı.\n" +
              "Önce GrandQC sihirbazıyla lamları işleyip GeoJSON'u içe aktarın."
    if (isHeadless) { println msg; return }
    Dialogs.showWarningNotification(workshopText('script.05f7e81a10c9', "GrandQC kohort KK özeti"), msg)
    return
}

// ── 3b) Kopya sorusu — TSV yazılmadan ÖNCE. Tek setli lamlarda sorulmaz, çıktı eskisiyle aynıdır. ──
def copiesByImage = setRows.groupBy { it.img }
def copiesMulti   = copiesByImage.findAll { k, v -> v.size() >= 2 }
def copiesLines   = copiesMulti.values().collect { v -> v[0].name + ' · ' + GRANDQC_SENTINEL + ': ' + v.collect { it.index }.join(', ') }
def copiesChoice  = null     // null: kopya yok | 'all' | 'newest'
if (!copiesMulti.isEmpty()) {
    if (isHeadless) {
        if (copiesArg == null) {
            println String.format(java.util.Locale.US, workshopText('script.14de14c01439', 'DURDU: %,d görüntüde birden çok GrandQC seti var (ör. %s). Hiçbir şey yazılmadı. Tüm setler için -a kopyalar=hepsi, yalnız en yeni set için -a kopyalar=enyeni ile yeniden çalıştırın.'), copiesMulti.size(), copiesLines.take(5).join('; '))
            return
        }
        copiesChoice = (copiesArg == 'enyeni') ? 'newest' : 'all'
    } else {
        String copiesOptAll = workshopText('script.23b429033e74', 'Tüm setler — set başına bir satır (varsayılan)')
        String copiesOptNewest = workshopText('script.b6232cc62bb8', 'Yalnız en yeni set — lam başına bir satır')
        def copiesPick = copiesAskChoice(workshopText('script.63ce111d915c', 'Birden çok GrandQC seti var'),
            String.format(java.util.Locale.US, workshopText('script.21c3ca2ac0c8', '%,d lamda birden çok GrandQC seti var: sihirbaz her çalıştırmada lama yeni bir set ekleyebilir ("GrandQC KK", "GrandQC KK (2)", "GrandQC KK (3)"). Setler farklı sayılar içerebilir; hangisinin güncel olduğuna bu araç karar veremez.\n\nÖrnekler (lam · ad: set numaraları; 1 = numarasız ad):\n%s\n\n"Tüm setler": her set ayrı bir satır olur ve satıra setin adını yazan bir "set_adi" sütunu eklenir.\n\n"Yalnız en yeni set": her lamdan yalnız en yüksek numaralı set özetlenir; "set_adi" sütunu hangi setin kullanıldığını gösterir. Lamdaki hiçbir şey silinmez.\n\nİptal: hiçbir şey yazılmaz.\n\nHangisi yazılsın?'), copiesMulti.size(), copiesLines.take(5).collect { "  • " + it }.join("\n")),
            [copiesOptAll, copiesOptNewest], copiesOptAll)
        if (copiesPick == null) {
            println workshopText('script.c2cdf009e404', 'İptal: hiçbir şey yazılmadı.')
            return
        }
        copiesChoice = (copiesPick == copiesOptNewest) ? 'newest' : 'all'
    }
}
def chosenRows = copiesPickSets(setRows, copiesChoice == 'newest')
int leftSets = setRows.size() - chosenRows.size()
int leftAnnotations = (setRows.sum { it.nAnn } as int) - (chosenRows.sum { it.nAnn } as int)
def rows = chosenRows.collect { it.row }
if (copiesChoice != null) {
    columns << "set_adi"
    chosenRows.each { it.row["set_adi"] = it.setName }
}
int imagesSummarized = chosenRows.collect { it.img }.unique().size()

// ── 4) TSV yaz (sekme ayırıcı; eksik hücre = boş string) ────────────────────
try {
    outDir.mkdirs()
    tsvFile.withWriter("UTF-8") { w ->
        w.writeLine(columns.join("\t"))
        rows.each { r ->
            w.writeLine(columns.collect { col -> (r[col] ?: "") }.join("\t"))
        }
    }
} catch (Throwable t) {
    def msg = "TSV yazılamadı: ${t.getMessage() ?: t.getClass().getSimpleName()}\nHedef: ${tsvFile.getAbsolutePath()}"
    if (isHeadless) println "HATA: ${msg}" else Dialogs.showErrorMessage(workshopText('script.540d5d46fe47', "Yazma hatası"), msg)
    return
}

// ── 5) Sonucu sun ─────────────────────────────────────────────────────────────
def body = new StringBuilder()
body << "GrandQC KOHORT KALİTE KONTROL ÖZETİ\n"
body << "═══════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Görüntü sayısı        : %,d%n", totalImages)
body << String.format(java.util.Locale.US, "Özetlenen (GrandQC'li): %,d%n", imagesSummarized)
body << String.format(java.util.Locale.US, "Atlanan (anotasyon yok): %,d%n", skipped)
body << String.format(java.util.Locale.US, "Okunamayan            : %,d%n", failed)
body << "\nTSV dosyası:\n  " + tsvFile.getAbsolutePath() + "\n"
body << "\nSütunlar: doku/temiz doku/artefakt alanı (mm²) + doku içi artefakt %,\n"
body << "doku parça sayısı ve her artefakt sınıfı için alan (mm²) + bölge sayısı.\n"
body << "Alanlar yalnız kalibre görüntülerde doldurulur; kalibre değilse hücre boş.\n"
body << "Anotasyon üretmez, hiçbir lamı kaydetmez — salt okunur.\n\n"
if (copiesChoice != null) {
    body << "🗂 "
    if (copiesChoice == 'newest') {
        body << String.format(java.util.Locale.US, workshopText('script.a9f3bcb2d7f3', 'Birden çok set — seçim: yalnız en yeni set\n  %,d lamda birden çok set var. Dışarıda bırakılan: %,d eski set (%,d anotasyon).'), copiesMulti.size(), leftSets, leftAnnotations)
    } else {
        body << String.format(java.util.Locale.US, workshopText('script.edd54177c405', 'Birden çok set — seçim: tüm setler\n  %,d lamda birden çok set var; toplam %,d satır yazıldı. Her satırın kendi seti "set_adi" sütununda.'), copiesMulti.size(), chosenRows.size())
    }
    body << "\n" + workshopText('script.051b44368449', 'Son sütun "set_adi": satırın hangi GrandQC setinden geldiğini gösterir. Etkilenen lamlar (ad: set numaraları):') + "\n"
    copiesLines.take(15).each { l -> body << "  • " + l + "\n" }
    if (copiesLines.size() > 15) body << String.format(java.util.Locale.US, "  … +%,d%n", copiesLines.size() - 15)
    body << "\n"
}
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("GrandQC Kohort KK Özeti", body.toString())
println String.format(java.util.Locale.US,
    "✓ TSV yazıldı: %d görüntü özetlendi (%d atlandı, %d hata) → %s",
    imagesSummarized, skipped, failed, tsvFile.getAbsolutePath())

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
