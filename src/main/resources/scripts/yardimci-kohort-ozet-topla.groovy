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
 *   Bu araç "Modül 9 dışa aktarma" ile tamamlayıcıdır: Modül 9 UZUN FORMAT
 *   (per-nesne satır) üretirken bu betik GENİŞ FORMAT (per-görüntü satır) üretir;
 *   her görüntünün özet skorunu tek satırda görmek isteyenler için idealdir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Kilitli anotasyonların MeasurementList değerlerini salt-okur şekilde toplar.
 *   • Klinik skor, eşik, alt-tip, risk kategorisi veya yorum ÜRETMEZ.
 *   • Hiçbir imageData'yı kaydetmez (saveImageData çağrısı yoktur).
 *
 * KULLANIM:
 *   1. Kilitli özet anotasyonları (Modül 6 piksel sınıflandırıcı, intersect, vb.)
 *      içeren bir QuPath projesi açın.
 *   2. [Extensions → Atölye → Yardımcılar → Kohort → Kohort özet toplayıcı]
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

            def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut")
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )

            def copyBtn = new javafx.scene.control.Button("Kopyala")
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent()
                content.putString(windowBody)
                cb.setContent(content)
            })

            def closeBtn = new javafx.scene.control.Button("Kapat")
            closeBtn.setDefaultButton(true)
            closeBtn.setOnAction({ stage.close() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)

            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, copyBtn, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(textArea)
            def __footer = new javafx.scene.control.Label("QuPath Atölye Scriptleri · araştırma/eğitim amaçlı")
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

// ── 1) Ön kontrol: açık proje zorunlu ────────────────────────────────────────
def project = QP.getProject()
if (project == null) {
    def msg = "Önce bir QuPath projesi açın. (Dosya → Proje aç…)\nBu betik tek slayt modunda çalışmaz."
    if (isHeadless) { println msg; return }
    Dialogs.showErrorMessage("Proje açık değil", msg)
    return
}

def entries = project.getImageList()
if (entries == null || entries.isEmpty()) {
    def msg = "Projede görüntü bulunamadı."
    if (isHeadless) { println msg; return }
    Dialogs.showErrorMessage("Boş proje", msg)
    return
}

// ── 2) Çıktı dizinini hazırla ────────────────────────────────────────────────
def projectDir = null
try { projectDir = project.getPath()?.getParent()?.toFile() } catch (Throwable ignore) {}
def outDir = (projectDir != null) ? new File(projectDir, "kohort-ozet") : new File(System.getProperty("java.io.tmpdir"), "kohort-ozet")
outDir.mkdirs()
long millis = System.currentTimeMillis()
def tsvFile = new File(outDir, String.format(java.util.Locale.US, "kohort_ozet_%d.tsv", millis))

// ── 3) Tüm görüntüleri gez: kilitli anotasyonların ölçümlerini topla ─────────
// rows: List<Map<String,String>>  (image_name + "<ad·sınıf> · <ölçüm>" → değer)
// columnOrder: ilk görülen sırası korunur (image_name hariç)
def rows         = []
def columnOrder  = new java.util.LinkedHashSet<String>()
int totalImages  = entries.size()
int skipped      = 0
println String.format(java.util.Locale.US, "Kohort özet toplayıcı başladı: %d görüntü", totalImages)

entries.eachWithIndex { entry, idx ->
    def imgName = entry.getImageName()
    def row = new java.util.LinkedHashMap<String, String>()
    row["image_name"] = imgName
    def imageData = null
    try {
        imageData = entry.readImageData()
        def hierarchy = imageData.getHierarchy()
        // Kilitli anotasyonları al (QP statik API'si burada entry kapsamında değil;
        // doğrudan hierarchy üzerinden çalışıyoruz)
        def lockedAnnos = hierarchy.getAnnotationObjects().findAll { it.isLocked() }
        lockedAnnos.each { anno ->
            // Sütun ön eki: ad varsa adı, yoksa sınıf adını kullan
            def label = (anno.getName() ?: anno.getPathClass()?.toString() ?: "Anotasyon").trim()
            def ml = anno.getMeasurementList()
            def names = ml.getMeasurementNames()
            names.each { mKey ->
                double val = ml.getMeasurementValue(mKey)
                def colName = label + " · " + mKey   // middot (·) ayırıcı
                columnOrder.add(colName)
                // NaN → boş; sayısal değer → tam ya da ondalık
                String cellVal = Double.isNaN(val) ? "" : (val == Math.floor(val) && !Double.isInfinite(val)
                    ? String.format(java.util.Locale.US, "%.0f", val)
                    : String.format(java.util.Locale.US, "%.6g", val))
                // İlk kilitli anotasyon kazanır; birden fazla aynı sütunu varsa birleştir
                if (!row.containsKey(colName) || row[colName].isEmpty()) {
                    row[colName] = cellVal
                }
            }
        }
        rows << row
        println String.format(java.util.Locale.US, "  [%d/%d] %s — %d kilitli anotasyon", idx + 1, totalImages, imgName, lockedAnnos.size())
    } catch (Throwable t) {
        skipped++
        println String.format(java.util.Locale.US, "  [%d/%d] %s — ATLANIL: %s", idx + 1, totalImages, imgName, (t.getMessage() ?: t.getClass().getSimpleName()))
    } finally {
        try { imageData?.getServer()?.close() } catch (Throwable ignore) {}
        imageData = null
        System.gc()
    }
}

if (rows.isEmpty()) {
    def msg = "Hiçbir görüntüden kilitli anotasyon okunamadı.\nÖnce bir Modül 6 piksel sınıflandırıcısı çalıştırıp sonuç anotasyonunu kilitleyin."
    if (isHeadless) { println msg; return }
    Dialogs.showWarningNotification("Kohort özet toplayıcı", msg)
    return
}

// ── 4) TSV yaz (sekme ayırıcı; eksik hücre = boş string) ────────────────────
def colList = new ArrayList<String>(["image_name"] + new ArrayList<String>(columnOrder))
tsvFile.withWriter("UTF-8") { w ->
    w.writeLine(colList.join("\t"))
    rows.each { r ->
        def line = colList.collect { col -> (r[col] ?: "") }.join("\t")
        w.writeLine(line)
    }
}

// ── 5) Sonucu sun ─────────────────────────────────────────────────────────────
int colCount = colList.size() - 1   // image_name hariç
def previewCols = colList.drop(1).take(15)

def body = new StringBuilder()
body << "KOHORT ÖZET TOPLAYICI\n"
body << "═══════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Görüntü sayısı   : %,d  (atlanan: %,d)%n", rows.size(), skipped)
body << String.format(java.util.Locale.US, "Ölçüm sütunu     : %,d%n", colCount)
body << "\nTSV dosyası:\n  " + tsvFile.getAbsolutePath() + "\n"
body << "\nİlk " + previewCols.size() + " sütun:\n"
previewCols.each { c -> body << "  • " + c + "\n" }
if (colCount > 15) body << String.format(java.util.Locale.US, "  … ve %,d sütun daha%n", colCount - 15)
body << "\nSütun adı formatı: \"<anotasyon adı veya sınıfı> · <ölçüm adı>\"\n"
body << "Eksik değerler TSV'de boş hücre olarak bırakıldı (Excel/R doğrudan okur).\n"
body << "Modül 9 ile üretilen uzun-format tablosu ile birleştirilebilir.\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Kohort Özet Toplayıcı", body.toString())
println String.format(java.util.Locale.US,
    "✓ TSV yazıldı: %d görüntü, %d sütun → %s",
    rows.size(), colCount, tsvFile.getAbsolutePath())
