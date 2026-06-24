/**
 * Yardımcı - TIA Toolbox için bölge maskesi
 * -----------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Sınıflı anotasyonlardan, TÜM slaydı kaplayan TEK bir indeksli maske PNG'si
 *   yazar (arka plan = 0, her sınıf = 1, 2, ...). Maske, bir Python derin öğrenme
 *   kütüphanesinde — özellikle TIA Toolbox `engine.run(masks=[maske.png], ...)` —
 *   çıkarımı yalnızca anote ettiğiniz bölgeyle sınırlamak için kullanılır.
 *   Yanına ölçek bilgisini taşıyan bir `.json` (downsample, µm/px, boyutlar,
 *   sınıf→indeks) ve okunabilir bir `labels.txt` yazılır.
 *
 * EK X'TEN FARKI:
 *   Ek X (Karo dışa aktarma) çok sayıda KÜÇÜK karo (+ tiled maske) üretir; bu betik
 *   TEK, düşük çözünürlüklü, tüm-slayt maskesi üretir — TIA Toolbox'ın `masks=`
 *   parametresinin beklediği biçim. İki betik birbirini tamamlar, çakışmaz.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   Yalnızca bir GÖRÜNTÜ MASKESİ (veri) üretir. Hiçbir sayı/yüzde/klinik yorum
 *   üretmez. Maskenin doğruluğu çizdiğiniz anotasyonların doğruluğuna bağlıdır.
 *
 * KULLANIM:
 *   1. Bir slayt açın; ilgi bölgesini anote edip SINIFLANDIRIN (ör. Tumor / Stroma
 *      — bkz. Modül 6). Sınıfsız anotasyonlar maskeye dâhil edilmez.
 *   2. [Extensions → Atölye → Yardımcılar → TIA Toolbox için bölge maskesi]
 *   3. Maske çözünürlüğünü (µm/px) girin (düşük çözünürlük = küçük dosya).
 *
 * ÇIKTI:
 *   <proje-klasörü>/exports/masks/<slayt-slug>__<tarih>/
 *     ├── <slug>_mask.png   → tek kanallı indeksli maske (TIA Toolbox masks=)
 *     ├── <slug>_mask.json  → ölçek/koordinat üst verisi
 *     └── labels.txt        → sınıf → indeks haritası
 *   (Proje yoksa çıktı kullanıcı ana klasörünün altına yazılır.)
 *
 * API / BELGE: LabeledImageServer.Builder + ImageWriterTools.writeImage (QuPath 0.6.0+).
 *   Maske tek kanallı indekslidir (multichannelOutput=false); arka plan = 0.
 *   TIA Toolbox: https://tia-toolbox.readthedocs.io/en/stable/  (Ek: TIA Toolbox).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı veri üretir.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.images.servers.LabeledImageServer
import qupath.lib.images.writers.ImageWriterTools
import qupath.lib.common.ColorTools

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import groovy.json.JsonOutput

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// --- Atölye ayarları: eklenti yüklüyse oku, yoksa atölye varsayılanı kullanılır ---
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeS = { String k, String d -> (String) __wpCall('str', [String.class, String.class] as Class[], [k, d] as Object[], d) }
def exportFolder = atolyeS('atolye.exportFolder', 'exports')

// ──────────────────────────────────────────────────────────────
// Türkçe karakter + boşluk içeren slayt isimlerini güvenli slug'a çevirir
// ──────────────────────────────────────────────────────────────
def toSlug = { String s ->
    if (s == null) return "untitled"
    return s.replace('ı', 'i').replace('İ', 'I')
            .replace('ğ', 'g').replace('Ğ', 'G')
            .replace('ü', 'u').replace('Ü', 'U')
            .replace('ş', 's').replace('Ş', 'S')
            .replace('ö', 'o').replace('Ö', 'O')
            .replace('ç', 'c').replace('Ç', 'C')
            .replaceAll('[^a-zA-Z0-9_\\-]', '_')
            .replaceAll('_+', '_')
}

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
            stage.setScene(new javafx.scene.Scene(root, 760, 560))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 1) Ön kontrol: açık görüntü
// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir slayt açın, sonra bu betiği tekrar çalıştırın.")
    return
}
def server = imageData.getServer()
double avgPx = server.getPixelCalibration().getAveragedPixelSize()   // kalibre değilse NaN/0
boolean calibrated = (avgPx > 0 && !Double.isNaN(avgPx))

// ──────────────────────────────────────────────────────────────
// 2) Sınıflı anotasyonları topla
// ──────────────────────────────────────────────────────────────
def classifiedAnns = QP.getAnnotationObjects().findAll { it.getROI() != null && it.getPathClass() != null }
if (classifiedAnns.isEmpty()) {
    Dialogs.showErrorMessage(
        "Sınıflı anotasyon yok",
        "Bölge maskesi üretmek için anotasyonların bir SINIFI (PathClass) olmalı\n" +
        "(ör. Tumor, Stroma). Slaytta sınıflı anotasyon bulunamadı.\n\n" +
        "Çözüm: anotasyonları sınıflandırın (Modül 6 — tümör/stroma)."
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 3) Maske çözünürlüğü (µm/px) → downsample
// ──────────────────────────────────────────────────────────────
if (isHeadless) {
    println "Maske dışa aktarma için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    return
}
def umText = Dialogs.showInputDialog(
    "TIA Toolbox için bölge maskesi",
    "Maske çözünürlüğü (µm/px) — düşük çözünürlük = küçük dosya (öneri: 2–8):",
    "4.0")
if (umText == null) { println "İptal edildi."; return }
double umPerPx
try {
    umPerPx = Double.parseDouble(umText.trim().replace(',', '.'))
} catch (NumberFormatException nfe) {
    Dialogs.showErrorMessage("Sayı formatı", "Çözünürlük ondalık bir sayı olmalı (ör. 4.0).")
    return
}
if (!(umPerPx > 0)) { Dialogs.showErrorMessage("Geçersiz", "Çözünürlük pozitif olmalı."); return }

double downsample = calibrated ? (umPerPx / avgPx) : umPerPx
if (!(downsample > 0)) downsample = 1.0d

// Çıktı maske boyutu tahmini — çok büyükse onay iste
long maskW = (long) Math.ceil(server.getWidth() / downsample)
long maskH = (long) Math.ceil(server.getHeight() / downsample)
if (maskW > 10000 || maskH > 10000) {
    def ok = Dialogs.showConfirmDialog(
        "Büyük maske",
        String.format(java.util.Locale.US,
            "Maske yaklaşık %,d × %,d piksel olacak — büyük bir dosya.%n%n" +
            "Devam edilsin mi? (Çözünürlüğü [µm/px] artırmak maskeyi küçültür.)", maskW, maskH))
    if (!ok) { println "Kullanıcı büyük-maske onayını reddetti."; return }
}

// ──────────────────────────────────────────────────────────────
// 4) Sınıf → indeks haritalı tek kanallı maske sunucusu
// ──────────────────────────────────────────────────────────────
def classes = classifiedAnns.collect { it.getPathClass() }.unique().sort { it.toString() }
def builder = new LabeledImageServer.Builder(imageData)
    .backgroundLabel(0, ColorTools.WHITE)
    .downsample(downsample)
    .multichannelOutput(false)
def legend = ["0 = arka plan"]
def labelMap = new LinkedHashMap<String, Object>()
labelMap.put("0", "arka plan")
classes.eachWithIndex { pc, i ->
    builder.addLabel(pc.toString(), i + 1)
    legend << "${i + 1} = ${pc.toString()}".toString()
    labelMap.put((i + 1).toString(), pc.toString())
}
def labelServer = builder.build()

// ──────────────────────────────────────────────────────────────
// 5) Çıktı klasörü + dosya adları
// ──────────────────────────────────────────────────────────────
def project = QP.getProject()
def baseDir = (project != null && project.getPath() != null)
    ? project.getPath().getParent().toFile()
    : new File(System.getProperty("user.home"), "QuPath")
def stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
def slug = toSlug(server.getMetadata().getName())
def outDir = new File(new File(new File(baseDir, exportFolder), "masks"), "${slug}__${stamp}")
outDir.mkdirs()
def maskFile = new File(outDir, "${slug}_mask.png")
def jsonFile = new File(outDir, "${slug}_mask.json")

// ──────────────────────────────────────────────────────────────
// 6) Maskeyi yaz
// ──────────────────────────────────────────────────────────────
def t0 = System.currentTimeMillis()
boolean ok = false
String errMsg = null
try {
    ok = ImageWriterTools.writeImage(labelServer, maskFile.getAbsolutePath())
} catch (Throwable t) {
    errMsg = "${t.getClass().getSimpleName()}: ${t.getMessage()}"
}
double elapsed = (System.currentTimeMillis() - t0) / 1000.0

if (!ok && errMsg == null) errMsg = "Maske yazılamadı (bilinmeyen neden)."
if (errMsg != null) {
    Dialogs.showErrorMessage("Maske yazılamadı", errMsg)
    println "HATA: ${errMsg}"
    return
}

// ──────────────────────────────────────────────────────────────
// 7) Üst veri (ölçek/koordinat) + labels.txt
// ──────────────────────────────────────────────────────────────
def meta = new LinkedHashMap<String, Object>()
meta.put("image", server.getMetadata().getName())
meta.put("maskFile", maskFile.getName())
meta.put("baseMicronsPerPixel", calibrated ? avgPx : null)
meta.put("requestedMicronsPerPixel", umPerPx)
meta.put("downsample", downsample)
meta.put("fullWidth", server.getWidth())
meta.put("fullHeight", server.getHeight())
meta.put("maskWidth", (int) maskW)
meta.put("maskHeight", (int) maskH)
meta.put("labels", labelMap)
meta.put("note", "TIA Toolbox engine.run(masks=[...]) icin tek kanalli indeksli maske. " +
                 "Arka plan = 0; sifir-disi degerler cikarima dahil bolgedir. Yalnizca arastirma/egitim amacli.")
try { jsonFile.write(JsonOutput.prettyPrint(JsonOutput.toJson(meta)), "UTF-8") } catch (Throwable ignored) {}
try { new File(outDir, "labels.txt").write(legend.join("\n") + "\n", "UTF-8") } catch (Throwable ignored) {}

// ──────────────────────────────────────────────────────────────
// 8) Sonuç
// ──────────────────────────────────────────────────────────────
def legendBlock = legend.collect { "  ${it}" }.join("\n")
showResultWindow(
    "TIA Toolbox bölge maskesi — Tamamlandı 🗺️",
    String.format(java.util.Locale.US,
        "Görüntü   : %s%n" +
        "Maske     : %s%n" +
        "Üst veri  : %s%n" +
        "Çıktı klasörü:%n  %s%n%n" +
        "⚙️  Ayarlar%n" +
        "──────────%n" +
        "  Çözünürlük : %.3f µm/px → downsample %.3f%s%n" +
        "  Maske boyutu: %,d × %,d px%n" +
        "  Süre        : %.1f sn%n%n" +
        "🏷️  Sınıf → indeks (labels.txt)%n" +
        "──────────────────%n" +
        "%s%n%n" +
        "📝 Sonraki adım — Python tarafı:%n" +
        "  • TIA Toolbox: engine.run(images=[wsi], masks=['%s'], patch_mode=False, ...)%n" +
        "  • Yöntem ve tam döngü: Ek — TIA Toolbox.%n%n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı veri üretir.",
        server.getMetadata().getName(),
        maskFile.getName(),
        jsonFile.getName(),
        outDir.getAbsolutePath(),
        umPerPx, downsample, (calibrated ? "" : " (kalibre değil)"),
        maskW, maskH,
        elapsed,
        legendBlock,
        maskFile.getName()
    )
)

println String.format(java.util.Locale.US, "Tamamlandı: maske %,d × %,d px, %.1f sn", maskW, maskH, elapsed)
println "Çıktı: ${outDir.getAbsolutePath()}"
