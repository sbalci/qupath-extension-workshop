/**
 * Yardımcı - Kohort Özet Toplayıcı
 * -----------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Açık projede yer alan HER görüntü için hiyerarşiyi diskten okur; yalnızca
 *   KİLİTLİ anotasyonları (isLocked() == true) alır ve bunların MeasurementList
 *   değerlerini toplar. Sonuçları GENİŞ TABLO formatında (bir satır = bir görüntü,
 *   sütunlar = tüm kilitli anotasyonlardaki ölçümlerin birleşimi) TSV dosyasına
 *   yazar: <proje>/kohort-ozet/kohort_ozet_<millis>.tsv
 *
 *   Bu araç "Veri dışa aktarma modülü" ile tamamlayıcıdır: o modül UZUN FORMAT
 *   (per-nesne satır) üretirken bu betik GENİŞ FORMAT (per-görüntü satır) üretir;
 *   her görüntünün özet skorunu tek satırda görmek isteyenler için idealdir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Kilitli anotasyonların MeasurementList değerlerini salt okunur şekilde toplar.
 *   • Klinik skor, eşik, alt-tip, risk kategorisi veya yorum ÜRETMEZ.
 *   • Hiçbir imageData'yı kaydetmez (saveImageData çağrısı yoktur).
 *
 * KULLANIM:
 *   1. Kilitli özet anotasyonları (Tümör/Stroma modülü piksel sınıflandırıcı, intersect, vb.)
 *      içeren bir QuPath projesi açın.
 *   2. [Extensions → Atölye → Yardımcılar → Klinik ve kohort → Kohort özet toplayıcı (proje tablosu)]
 *      (ya da [Automate → Project scripts → bu betik])
 *   3. TSV dosyası <proje>/kohort-ozet/ altında oluşur; sonuç penceresinde yol
 *      ve sütun listesi gösterilir.
 *
 * ÇIKTI:
 *   • <proje>/kohort-ozet/kohort_ozet_<millis>.tsv
 *     Sütun başlığı: image_name + "<anotasyonAd/Sınıf> · <ölçümAdı>" (çakışmasın)
 *     Eksik sütun hücreleri boş (NaN değil, boş string) → Excel/R doğrudan okur.
 *   • Sonuç penceresi: görüntü sayısı, sütun sayısı, TSV yolu, ilk 15 sütun.
 *   • Headless: aynı TSV + konsol çıktısı.
 *
 * YÖNTEM / KAYNAK:
 *   • sbalci/metadata-qupath — proje döngüsü kalıbı (entry.readImageData() +
 *     try/finally close + System.gc()).
 *   • QuPath MeasurementList API: getMeasurementNames() + getMeasurementValue(name).
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

            stage.setScene(new javafx.scene.Scene(root, 720, 520))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Kopya grupları: bir lamda aynı sonucun "ad", "ad (2)", "ad (3)" … olarak yan yana durması ──
// Kural (tam eşleşme, önek eşleşmesi YOK): "X (N)" (N tam sayı ≥ 2) "X" tabanına aittir. Bir grup,
// o tabanın en az İKİ farklı numarası (taban = 1) aynı lamda varsa kopya grubudur; tek başına bir
// "X (N)" (X silinmiş) grup değildir. "X (notlarım)" ve "X (1)" kopya değildir. Sondaki " (N)" yalnız
// aynı tabanın bir kardeşi varsa kopya numarasıdır ("… Mitotic figure (12)" tek başına kopya değil;
// "… (12) (2)" ile birlikte "… (12)" tabanının 1. ve 2. kopyası). Sınır: aynı lamda "X (12)" ve
// "X (3)" varsa (X yok) ikisi kopya sayılır. En yeni = en yüksek numara.
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
def copiesGroups = { Collection items, Closure nameOf ->
    def all = new LinkedHashMap()
    def bases = new HashSet()
    items.each { a -> def p = copiesParse(nameOf(a)); if (p != null) bases << p.base }
    items.each { a ->
        String nm = nameOf(a)
        if (nm == null) return
        def p = copiesParse(nm)
        if (p != null) all.get(p.base, new TreeMap()).get(p.index, []) << a
        if (p == null || bases.contains(nm)) all.get(nm, new TreeMap()).get(1, []) << a
    }
    def out = new LinkedHashMap()
    all.each { b, idx -> if (idx.size() >= 2) out[b] = idx }
    return out
}
def copiesOlder = { Map groups ->
    def older = Collections.newSetFromMap(new IdentityHashMap())
    groups.each { b, idx ->
        def mx = idx.keySet().max()
        idx.each { i, objs -> if (i != mx) older.addAll(objs) }
    }
    return older
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
// Soru yanıtlandıktan sonra satırları kurar. choice: null | 'all' | 'newest'.
// rows: image_name + "<ad·sınıf> · <ölçüm>" → değer; columnOrder: ilk görülen sıra korunur.
def copiesBuildRows = { List recs, String choice ->
    def rows            = []
    def columnOrder     = new java.util.LinkedHashSet<String>()
    def duplicateImages = new java.util.LinkedHashSet<String>()
    def leftColumns     = new java.util.LinkedHashSet<String>()
    int leftAnnotations = 0
    boolean newestOnly  = (choice == 'newest')
    recs.each { rec ->
        def row = new java.util.LinkedHashMap<String, String>()
        row["image_name"] = rec.name
        def kept = rec.items.findAll { !(newestOnly && it.older) }
        leftAnnotations += rec.items.size() - kept.size()
        rec.items.each { dropped ->
            if (newestOnly && dropped.older) dropped.cells.keySet().each { mKey -> leftColumns << (dropped.base + " · " + mKey) }
        }
        def labelsHere = new HashSet(kept.collect { it.base })
        // Aynı lamda aynı adı taşıyan birden çok kilitli anotasyon varsa hiçbiri atılmaz: ikincisi
        // "<ad> (2)", üçüncüsü "<ad> (3)" sütunlarına yazılır (eskiden yalnız ilki kalıyordu).
        def seenLabels = [:]
        kept.each { item ->
            def baseLabel = item.base
            int ordinal = ((seenLabels[baseLabel] ?: 0) as int) + 1
            seenLabels[baseLabel] = ordinal
            if (ordinal == 2) duplicateImages << rec.name
            def label = (ordinal == 1) ? baseLabel : (baseLabel + " (" + ordinal + ")")
            // "<ad> (2)" adlı gerçek bir anotasyon da varsa (kopya), sıra numarası sütunu onunkiyle çakışmasın.
            if (ordinal > 1 && labelsHere.contains(label)) label = baseLabel + " [" + ordinal + "]"
            item.cells.each { mKey, cellVal ->
                def colName = label + " · " + mKey   // middot (·) ayırıcı
                columnOrder.add(colName)
                // İlk kilitli anotasyon kazanır; birden fazla aynı sütunu varsa birleştir
                if (!row.containsKey(colName) || row[colName].isEmpty()) {
                    row[colName] = cellVal
                }
            }
        }
        if (choice != null) {
            row["kopya_bilgisi"] = rec.affected.collect { b, ix ->
                b + ': ' + ix.keySet().join(',') + (newestOnly ? ' -> ' + ix.keySet().max() : '')
            }.join('; ')
        }
        rows << row
    }
    leftColumns.removeAll(columnOrder)
    return [rows: rows, columnOrder: columnOrder, duplicateImages: duplicateImages, leftColumns: leftColumns, leftAnnotations: leftAnnotations]
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
    def msg = workshopText('script.3fa7ab791c90', 'Önce bir QuPath projesi açın. (Dosya → Proje aç…)\nBu betik tek lam modunda çalışmaz.')
    if (isHeadless) { println msg; return }
    Dialogs.showErrorMessage(workshopText('script.a38d0e6adac0', "Proje açık değil"), msg)
    return
}

def entries = project.getImageList()
if (entries == null || entries.isEmpty()) {
    def msg = workshopText('script.87ae843447a4', 'Projede görüntü bulunamadı.')
    if (isHeadless) { println msg; return }
    Dialogs.showErrorMessage(workshopText('script.cb3ed2211f0a', "Boş proje"), msg)
    return
}

// ── 2) Çıktı dizinini hazırla (klasör, soru yanıtlandıktan sonra, yazmadan hemen önce açılır) ──
def projectDir = null
try { projectDir = project.getPath()?.getParent()?.toFile() } catch (Throwable ignore) {}
def outDir = (projectDir != null) ? new File(projectDir, "kohort-ozet") : new File(System.getProperty("java.io.tmpdir"), "kohort-ozet")
long millis = System.currentTimeMillis()
def tsvFile = new File(outDir, String.format(java.util.Locale.US, "kohort_ozet_%d.tsv", millis))

// ── 3) Tüm görüntüleri gez: kilitli anotasyonların ölçümlerini topla ─────────
// recs: lam başına [name, items: [[base, older, cells]], affected: kopya grupları]
// Satırlar soru yanıtlandıktan sonra buildRows ile kurulur.
def recs         = []
int totalImages  = entries.size()
int skipped      = 0
println String.format(java.util.Locale.US, "Kohort özet toplayıcı başladı: %d görüntü", totalImages)

entries.eachWithIndex { entry, idx ->
    def imgName = entry.getImageName()
    def imageData = null
    try {
        imageData = entry.readImageData()
        def hierarchy = imageData.getHierarchy()
        // Kilitli anotasyonları al (QP statik API'si burada entry kapsamında değil;
        // doğrudan hierarchy üzerinden çalışıyoruz)
        def allAnnos = hierarchy.getAnnotationObjects() as List
        def lockedAnnos = allAnnos.findAll { it.isLocked() }
        // Kopya grupları TÜM anotasyonlar üzerinden bulunur: en yeni kopya kilitli değilse, kilitli eski
        // kopya "en yeni" sayılıp sessizce tabloya girmesin. Soru yalnız kilitli üyesi olan grupları sayar.
        def groups = copiesGroups(allAnnos, { it.getName() })
        def olderSet = copiesOlder(groups)
        def affected = groups.findAll { b, ix -> ix.values().flatten().any { it.isLocked() } }
        def items = []
        lockedAnnos.each { anno ->
            // Sütun ön eki: ad varsa adı, yoksa sınıf adını kullan
            def baseLabel = (anno.getName() ?: anno.getPathClass()?.toString() ?: "Anotasyon").trim()
            def ml = anno.getMeasurementList()
            def cells = new java.util.LinkedHashMap<String, String>()
            ml.getMeasurementNames().each { mKey ->
                double val = ml.getMeasurementValue(mKey)
                // NaN → boş; sayısal değer → tam ya da ondalık
                cells[mKey] = Double.isNaN(val) ? "" : (val == Math.floor(val) && !Double.isInfinite(val)
                    ? String.format(java.util.Locale.US, "%.0f", val)
                    : String.format(java.util.Locale.US, "%.6g", val))
            }
            items << [base: baseLabel, older: olderSet.contains(anno), cells: cells]
        }
        recs << [name: imgName, items: items, affected: affected]
        println String.format(java.util.Locale.US, "  [%d/%d] %s — %d kilitli anotasyon", idx + 1, totalImages, imgName, lockedAnnos.size())
    } catch (Throwable t) {
        skipped++
        println String.format(java.util.Locale.US, "  [%d/%d] %s — ATLANDI: %s", idx + 1, totalImages, imgName, (t.getMessage() ?: t.getClass().getSimpleName()))
    } finally {
        try { imageData?.getServer()?.close() } catch (Throwable ignore) {}
        imageData = null
        System.gc()
    }
}

if (recs.isEmpty()) {
    def msg = workshopText('script.3816a6162572', 'Hiçbir görüntüden kilitli anotasyon okunamadı.\nÖnce Tümör/Stroma modülünün piksel sınıflandırıcısını çalıştırıp sonuç anotasyonunu kilitleyin.')
    if (isHeadless) { println msg; return }
    Dialogs.showWarningNotification(workshopText('script.527be9b53936', "Kohort özet toplayıcı"), msg)
    return
}

// ── 3b) Kopya sorusu — TSV yazılmadan ÖNCE. Kopya grubu yoksa sorulmaz, çıktı eskisiyle aynıdır. ──
def copiesChoice = null     // null: kopya yok | 'all' | 'newest'
def copiesAffectedRecs = recs.findAll { !it.affected.isEmpty() }
def copiesLines = []
copiesAffectedRecs.each { rc -> rc.affected.each { b, ix -> copiesLines << (rc.name + ' · ' + b + ': ' + ix.keySet().join(', ')) } }
if (!copiesAffectedRecs.isEmpty()) {
    if (isHeadless) {
        if (copiesArg == null) {
            println String.format(java.util.Locale.US, workshopText('script.81b5fc1eeb8b', 'DURDU: %,d görüntüde kilitli özetin birden çok kopyası var (ör. %s). Hiçbir şey yazılmadı. Tüm kopyalar için -a kopyalar=hepsi, yalnız en yeni kopyalar için -a kopyalar=enyeni ile yeniden çalıştırın.'), copiesAffectedRecs.size(), copiesLines.take(5).join('; '))
            return
        }
        copiesChoice = (copiesArg == 'enyeni') ? 'newest' : 'all'
    } else {
        String copiesOptAll = workshopText('script.664b17ebb1d0', 'Tüm kopyalar — her kopya kendi sütunlarında (varsayılan)')
        String copiesOptNewest = workshopText('script.f82a1fd783d2', 'Yalnız en yenisi — eski kopyalar tabloya girmez')
        def copiesPick = copiesAskChoice(workshopText('script.08096a5c93b4', 'Aynı özetin birden çok kopyası var'),
            String.format(java.util.Locale.US, workshopText('script.1a1597c219e8', '%,d görüntüde toplam %,d kopya grubu var: aynı özet lamda "ad", "ad (2)", "ad (3)" biçiminde yan yana duruyor. Kopyalar farklı sayılar içerebilir; hangisinin güncel olduğuna bu araç karar veremez.\n\nÖrnekler (lam · ad: kopya numaraları; 1 = numarasız ad):\n%s\n\n"Tüm kopyalar": her kopya kendi adıyla ayrı sütunlara yazılır ("ad · ölçüm", "ad (2) · ölçüm"); tabloya kopya bilgisi sütunu eklenir.\n\n"Yalnız en yenisi": her grupta yalnız en yüksek numaralı kopya tabloya girer. En yeni kopya kilitli değilse o grup tabloda hiç yer almaz. Lamdaki hiçbir şey silinmez.\n\nİptal: hiçbir şey yazılmaz.\n\nHangisi yazılsın?'), copiesAffectedRecs.size(), copiesLines.size(), copiesLines.take(5).collect { "  • " + it }.join("\n")),
            [copiesOptAll, copiesOptNewest], copiesOptAll)
        if (copiesPick == null) {
            println workshopText('script.c2cdf009e404', 'İptal: hiçbir şey yazılmadı.')
            return
        }
        copiesChoice = (copiesPick == copiesOptNewest) ? 'newest' : 'all'
    }
}

// ── 3c) Satırları kur ─────────────────────────────────────────────────────────
boolean newestOnly  = (copiesChoice == 'newest')
def builtRows       = copiesBuildRows(recs, copiesChoice)
def rows            = builtRows.rows
def columnOrder     = builtRows.columnOrder
def duplicateImages = builtRows.duplicateImages
def leftColumns     = builtRows.leftColumns
int leftAnnotations = builtRows.leftAnnotations

// ── 4) TSV yaz (sekme ayırıcı; eksik hücre = boş string) ────────────────────
def colList = new ArrayList<String>(["image_name"] + new ArrayList<String>(columnOrder))
if (copiesChoice != null) colList << "kopya_bilgisi"
try {
    outDir.mkdirs()
    tsvFile.withWriter("UTF-8") { w ->
        w.writeLine(colList.join("\t"))
        rows.each { r ->
            def line = colList.collect { col -> (r[col] ?: "") }.join("\t")
            w.writeLine(line)
        }
    }
} catch (Throwable t) {
    def msg = String.format(java.util.Locale.US, workshopText('script.85a43ea5543c', 'TSV yazılamadı: %s\nHedef: %s'), (t.getMessage() ?: t.getClass().getSimpleName()), tsvFile.getAbsolutePath())
    if (isHeadless) println "HATA: ${msg}" else Dialogs.showErrorMessage(workshopText('script.540d5d46fe47', "Yazma hatası"), msg)
    return
}

// ── 5) Sonucu sun ─────────────────────────────────────────────────────────────
int colCount = colList.size() - 1 - (copiesChoice != null ? 1 : 0)   // image_name (ve kopya_bilgisi) hariç
def previewCols = colList.drop(1).take(15)

def body = new StringBuilder()
body << workshopText('script.c1a1c97bb3ea', 'KOHORT ÖZET TOPLAYICI\n')
body << "═══════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, workshopText('script.e4329c4e2a28', 'Görüntü sayısı   : %,d  (atlanan: %,d)%n'), rows.size(), skipped)
body << String.format(java.util.Locale.US, workshopText('script.51098563fa77', 'Ölçüm sütunu     : %,d%n'), colCount)
body << workshopText('script.d4e41942ccc5', '\nTSV dosyası:\n  ') + tsvFile.getAbsolutePath() + "\n"
body << String.format(java.util.Locale.US, workshopText('script.54a7b1cd01c7', '\nİlk %d sütun:\n'), previewCols.size())
previewCols.each { c -> body << "  • " + c + "\n" }
if (colList.size() - 1 > 15) body << String.format(java.util.Locale.US, workshopText('script.15469a7d4563', '  … ve %,d sütun daha%n'), colList.size() - 1 - 15)
if (!duplicateImages.isEmpty()) {
    body << String.format(java.util.Locale.US, workshopText('script.b62b4634823d', '\nAynı adlı birden çok kilitli anotasyon: %,d görüntü. İkinci ve sonrakiler "<ad> (2)", "<ad> (3)" sütunlarında.%n'), duplicateImages.size())
    duplicateImages.take(5).each { n -> body << "  • " + n + "\n" }
}
if (copiesChoice != null) {
    body << "\n🗂 "
    if (newestOnly) {
        body << String.format(java.util.Locale.US, workshopText('script.fee4550c8637', 'Kopya grupları — seçim: yalnız en yenisi\n  %,d görüntüde %,d grup. Dışarıda bırakılan: %,d kilitli anotasyon, %,d sütun (yalnız eski kopyalarda bulunan).'), copiesAffectedRecs.size(), copiesLines.size(), leftAnnotations, leftColumns.size())
    } else {
        body << String.format(java.util.Locale.US, workshopText('script.5934930c5034', 'Kopya grupları — seçim: tüm kopyalar\n  %,d görüntüde %,d grup. Her kopya kendi sütunlarında; hangi kopyanın güncel olduğunu aşağıdaki listeden kontrol edin.'), copiesAffectedRecs.size(), copiesLines.size())
    }
    body << "\n" + workshopText('script.87fb0f304dc9', 'Son sütun "kopya_bilgisi": lam başına kopya grupları (ad: numaralar, 1 = numarasız ad; en yeniyse "-> numara").') + "\n"
    copiesLines.take(15).each { l -> body << "  • " + l + "\n" }
    if (copiesLines.size() > 15) body << String.format(java.util.Locale.US, "  … +%,d%n", copiesLines.size() - 15)
}
body << workshopText('script.5a80bd4eaa5f', '\nSütun adı formatı: "<anotasyon adı veya sınıfı> · <ölçüm adı>"\n')
body << workshopText('script.5faebd8185ae', 'Eksik değerler TSV\'de boş hücre olarak bırakıldı (Excel/R doğrudan okur).\n')
body << workshopText('script.0a1d0b2be7a7', 'Veri dışa aktarma modülünde üretilen uzun-format tablosu ile birleştirilebilir.\n\n')
body << workshopText('script.cf6099f0a005', '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')

showResultWindow(workshopText('script.9d0592d107dc', 'Kohort Özet Toplayıcı'), body.toString())
println String.format(java.util.Locale.US,
    "✓ TSV yazıldı: %d görüntü, %d sütun → %s",
    rows.size(), colCount, tsvFile.getAbsolutePath())

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
