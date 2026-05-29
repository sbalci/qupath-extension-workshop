/**
 * Modül 9 - Tek Tıkla Veri Dışa Aktarma
 * ----------------------------------------
 * Anotasyon + tespit ölçümlerini ve anotasyon geometrisini (GeoJSON)
 * proje klasörünün yanındaki `exports/<tarih>/` altına yazar.
 *
 * İki mod:
 *   • Sadece bu görüntü — sadece açık olan slaytın verisi
 *   • Tüm proje      — projedeki her slaytın ayrı dosyaları + birleştirilmiş
 *                       _all_detections.tsv / _all_annotations.tsv
 *
 * KULLANIM:
 *   1. Modüllerden birinde (2, 3, 3b, 4, 5, 7, 8) ölçüm üretmiş olun
 *   2. [Automate → Project scripts → Modül 9 - Veri dışa aktarma]
 *   3. Diyalogdan mod seçin → Çalıştır
 *
 * ÇIKTI:
 *   <proje-klasörü>/exports/YYYY-MM-DD_HHmm/
 *     ├── _all_detections.tsv          (yalnız "tüm proje" modunda)
 *     ├── _all_annotations.tsv         (yalnız "tüm proje" modunda)
 *     ├── <slayt-adı>__detections.tsv
 *     ├── <slayt-adı>__annotations.tsv
 *     └── <slayt-adı>__annotations.geojson
 *
 * Not: Türkçe karakterler ve özel sembolleri içeren slayt adları
 * ASCII-güvenli bir slug'a çevrilerek dosya adında kullanılır.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.gui.tools.MeasurementExporter
import qupath.lib.io.GsonTools
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.PathDetectionObject
import qupath.lib.scripting.QP

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ──────────────────────────────────────────────────────────────
// Modal olmayan pencere yardımcıları (atölyenin tüm betiklerinde paylaşılan iskelet)
// ──────────────────────────────────────────────────────────────
def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// --- Atölye ayarları: eklenti yüklüyse oku, yoksa atölye varsayılanı kullanılır ---
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeD = { String k, double  d -> (double)  __wpCall('dbl',  [String.class, double.class]  as Class[], [k, d] as Object[], d) }
def atolyeS = { String k, String  d -> (String)  __wpCall('str',  [String.class, String.class]  as Class[], [k, d] as Object[], d) }
def atolyeI = { String k, int     d -> (int)     __wpCall('intg', [String.class, int.class]     as Class[], [k, d] as Object[], d) }
def atolyeB = { String k, boolean d -> (boolean) __wpCall('bool', [String.class, boolean.class] as Class[], [k, d] as Object[], d) }
def exportFolder = atolyeS('atolye.exportFolder', 'exports')
def exportSeparatorChar = (atolyeS('atolye.exportSeparator', '\t')).charAt(0) as char

def waitForChoice = { String windowTitle, String windowBody,
                      String optionA, String optionB ->
    def latch = new java.util.concurrent.CountDownLatch(1)
    def picked = new java.util.concurrent.atomic.AtomicReference<String>(null)

    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle(windowTitle)
            stage.setAlwaysOnTop(true)

            def label = new javafx.scene.control.Label(windowBody)
            label.setWrapText(true)
            label.setStyle("-fx-font-size: 12px; -fx-padding: 8px;")

            def scrollPane = new javafx.scene.control.ScrollPane(label)
            scrollPane.setFitToWidth(true)

            def btnA = new javafx.scene.control.Button(optionA)
            btnA.setOnAction({
                picked.set("A")
                stage.close()
            })

            def btnB = new javafx.scene.control.Button(optionB)
            btnB.setDefaultButton(true)
            btnB.setOnAction({
                picked.set("B")
                stage.close()
            })

            def cancelBtn = new javafx.scene.control.Button("İptal")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({
                picked.set(null)
                stage.close()
            })

            stage.setOnHidden({ latch.countDown() })

            def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut")
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)

            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, cancelBtn, btnA, btnB)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(scrollPane)
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 640, 420))
            stage.show()
        } catch (Throwable t) {
            // Modal fallback
            def yn = qupath.lib.gui.dialogs.Dialogs.showConfirmDialog(
                windowTitle, "${windowBody}\n\nEvet: ${optionB}\nHayır: ${optionA}"
            )
            picked.set(yn ? "B" : "A")
            latch.countDown()
        }
    }

    latch.await()
    return picked.get()
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
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 760, 580))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Yardımcı: Türkçe karakter + boşluk içeren slayt isimlerini güvenli slug'a çevirir
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

// ──────────────────────────────────────────────────────────────
// 1) Ön kontroller
// ──────────────────────────────────────────────────────────────
def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage(
        "Proje açık değil",
        "Bu betik bir QuPath projesinin açık olmasını gerektirir.\n" +
        "Önce bir proje açın, sonra tekrar deneyin."
    )
    return
}

def currentImageData = QP.getCurrentImageData()
def currentEntry = QP.getProjectEntry()

// ──────────────────────────────────────────────────────────────
// 2) Mod seçimi
// ──────────────────────────────────────────────────────────────
def choice = waitForChoice(
    "Modül 9 - Veri dışa aktarma",
    "Anotasyon + tespit ölçümlerini ve anotasyon geometrilerini\n" +
    "(GeoJSON) dışa aktaracağım.\n\n" +
    "⚠️ Önemli: Dışa aktarım slaytın **diske kaydedilmiş** halinden okunur\n" +
    "(QuPath'in MeasurementExporter'ı .qpdata dosyasından çalışır).\n" +
    "Açık slaydı sizin için otomatik kaydedeceğim. Eğer son anotasyon /\n" +
    "tespit değişikliklerinizin dahil olmasını istiyorsanız, devam edin —\n" +
    "kaydetme bu betiğin ilk adımı olacak.\n\n" +
    "Çıktı klasörü:\n" +
    "  <proje-klasörü>/${exportFolder}/YYYY-MM-DD_HHmm/${exportFolder != 'exports' ? ' ← değiştirildi' : ''}\n\n" +
    "Hangi modu istiyorsunuz?\n" +
    "  • Sadece bu görüntü — sadece şu an açık slayt\n" +
    "  • Tüm proje         — projedeki her slayt için ayrı dosya,\n" +
    "                         + tüm projenin birleştirilmiş TSV'leri\n\n" +
    "Tüm proje modu zaman alır (slayt başına ~1-3 sn).",
    "Sadece bu görüntü", "Tüm proje"
)

if (choice == null) { println "İptal."; return }
def projectMode = (choice == "B")

if (!projectMode && currentImageData == null) {
    Dialogs.showErrorMessage(
        "Görüntü açık değil",
        "'Sadece bu görüntü' modu için bir slaytın açık olması gerekir."
    )
    return
}

if (!projectMode && currentEntry == null) {
    // Açık görüntü var ama projeye dahil değil — MeasurementExporter ve
    // entry.saveImageData çağrılarımız bir ProjectImageEntry gerektirir.
    Dialogs.showErrorMessage(
        "Slayt projeye eklenmemiş",
        "Açık olan slayt projenin parçası değil; dışa aktarılamaz.\n\n" +
        "Çözüm: Project panelinden bu slaytı projeye ekleyin\n" +
        "(sol üstte + ikonu) ve betiği tekrar çalıştırın."
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 2.5) Açık slaydı diske kaydet — kritik
// MeasurementExporter ve readImageData her ikisi de .qpdata dosyasından
// okur. Kaydedilmemiş anotasyon / tespit değişiklikleri dışa aktarımda görünmez,
// dosyalar boş çıkar. Burada savunmacı bir kaydetme yapıyoruz.
// ──────────────────────────────────────────────────────────────
def currentSaved = false
def currentSaveError = null
if (currentImageData != null && currentEntry != null) {
    try {
        currentEntry.saveImageData(currentImageData)
        currentSaved = true
        println "✓ Açık slayt diske kaydedildi: ${currentEntry.getImageName()}"
    } catch (Throwable t) {
        currentSaveError = "${t.getClass().getSimpleName()}: ${t.getMessage()}"
        println "⚠ Açık slayt kaydedilemedi: ${currentSaveError}"
    }
} else if (projectMode) {
    println "(Açık görüntü yok — projedeki tüm slaytlar son kayıtlı halinden okunur.)"
}

// ──────────────────────────────────────────────────────────────
// 3) Çıktı klasörünü hazırla — proje klasörünün yanına 'exports/tarih/'
// ──────────────────────────────────────────────────────────────
def stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
def projectPath = project.getPath()
def projectDir = projectPath.getParent().toFile()
def exportsRoot = new File(projectDir, exportFolder)
def outDir = new File(exportsRoot, stamp)
outDir.mkdirs()

println "─────────────────────────────────────"
println "Modül 9 - Veri dışa aktarma"
println "─────────────────────────────────────"
println "Mod: " + (projectMode ? "Tüm proje" : "Sadece bu görüntü")
println "Çıktı: ${outDir.getAbsolutePath()}"

def t0 = System.currentTimeMillis()
def imagesProcessed = 0
def detectionsTotal = 0
def annotationsTotal = 0
def filesWritten = []
def errors = []

def gson = GsonTools.getInstance(true)

// ──────────────────────────────────────────────────────────────
// 4) Tek-slayt yardımcısı — verilen entry için per-image dosyaları yazar
// ──────────────────────────────────────────────────────────────
def exportEntry = { entry, imageDataForGeo ->
    def slug = toSlug(entry.getImageName())

    // Tespit TSV (per-image)
    try {
        def detFile = new File(outDir, "${slug}__detections.tsv")
        new MeasurementExporter()
            .imageList([entry])
            .separator(exportSeparatorChar)
            .exportType(PathDetectionObject.class)
            .exportMeasurements(detFile)
        if (detFile.length() > 0) filesWritten << detFile.getName()
    } catch (Throwable t) {
        errors << "${entry.getImageName()} → tespitler: ${t.getMessage()}"
    }

    // Anotasyon TSV (per-image)
    try {
        def annFile = new File(outDir, "${slug}__annotations.tsv")
        new MeasurementExporter()
            .imageList([entry])
            .separator(exportSeparatorChar)
            .exportType(PathAnnotationObject.class)
            .exportMeasurements(annFile)
        if (annFile.length() > 0) filesWritten << annFile.getName()
    } catch (Throwable t) {
        errors << "${entry.getImageName()} → anotasyonlar: ${t.getMessage()}"
    }

    // Annotation GeoJSON — only if we have an ImageData with annotations
    try {
        def annos = imageDataForGeo?.getHierarchy()?.getAnnotationObjects() ?: []
        if (!annos.isEmpty()) {
            def geoFile = new File(outDir, "${slug}__annotations.geojson")
            geoFile.text = gson.toJson(annos)
            filesWritten << geoFile.getName()
            annotationsTotal += annos.size()
        }
        def dets = imageDataForGeo?.getHierarchy()?.getDetectionObjects() ?: []
        detectionsTotal += dets.size()
    } catch (Throwable t) {
        errors << "${entry.getImageName()} → geojson: ${t.getMessage()}"
    }
}

// ──────────────────────────────────────────────────────────────
// 5) Modu uygula
// ──────────────────────────────────────────────────────────────
if (projectMode) {
    def imageList = project.getImageList()
    println "  ${imageList.size()} slayt işlenecek..."

    // Per-image dosyalar
    imageList.each { entry ->
        try {
            def imgData = entry.readImageData()
            exportEntry(entry, imgData)
            imagesProcessed++
        } catch (Throwable t) {
            errors << "${entry.getImageName()} → readImageData: ${t.getMessage()}"
        }
    }

    // Birleştirilmiş TSV'ler — tek dosyada tüm projenin tespitler + anotasyonlar
    try {
        def allDet = new File(outDir, '_all_detections.tsv')
        new MeasurementExporter()
            .imageList(imageList)
            .separator(exportSeparatorChar)
            .exportType(PathDetectionObject.class)
            .exportMeasurements(allDet)
        if (allDet.length() > 0) filesWritten << allDet.getName()
    } catch (Throwable t) {
        errors << "_all_detections: ${t.getMessage()}"
    }

    try {
        def allAnn = new File(outDir, '_all_annotations.tsv')
        new MeasurementExporter()
            .imageList(imageList)
            .separator(exportSeparatorChar)
            .exportType(PathAnnotationObject.class)
            .exportMeasurements(allAnn)
        if (allAnn.length() > 0) filesWritten << allAnn.getName()
    } catch (Throwable t) {
        errors << "_all_annotations: ${t.getMessage()}"
    }

} else {
    // Sadece açık slayt
    exportEntry(currentEntry, currentImageData)
    imagesProcessed = 1
}

def elapsed = (System.currentTimeMillis() - t0) / 1000.0

// ──────────────────────────────────────────────────────────────
// 6) Sonucu sun
// ──────────────────────────────────────────────────────────────
def fileList = filesWritten.size() <= 20
    ? filesWritten.collect { "  • ${it}" }.join("\n")
    : (filesWritten.take(20).collect { "  • ${it}" }.join("\n") +
       "\n  ... ve ${filesWritten.size() - 20} dosya daha")

def errSection = errors.isEmpty()
    ? ""
    : "\n\n⚠️  Bazı dosyalar yazılamadı:\n" + errors.take(10).collect { "  • ${it}" }.join("\n") +
      (errors.size() > 10 ? "\n  ... ve ${errors.size() - 10} hata daha" : "")

def saveStatusLine
if (currentSaved) {
    saveStatusLine = "✓ Açık slayt diske kaydedildi (anotasyon/tespitler dahildir)"
} else if (currentSaveError != null) {
    saveStatusLine = "⚠ Açık slayt KAYDEDİLEMEDİ — son değişiklikler dışa aktarımda görünmeyebilir.\n" +
                     "  Detay: " + currentSaveError + "\n" +
                     "  Çözüm: QuPath ana penceresinde [Ctrl+S] basın, betiği tekrar çalıştırın."
} else {
    saveStatusLine = "(Açık slayt yok — projedeki tüm slaytlar son kayıtlı halinden okundu.)"
}

// Eğer dışa aktarım toplamları sıfırsa — çoğunlukla kaydedilmemiş slayt göstergesi
def emptyHint = ""
if (annotationsTotal == 0 && detectionsTotal == 0 && currentSaveError == null) {
    emptyHint = "\n\n💡 Çıktıda 0 anotasyon / 0 tespit görünüyor. Olası nedenler:\n" +
                "  1. Bu slaytta gerçekten hiç anotasyon / tespit yoktu — önce Modül 2-8'den birini çalıştırın.\n" +
                "  2. Slayt betikten ÖNCE QuPath dışında değiştirildi ve .qpdata kaydedilmedi.\n" +
                "  3. ROI seçilip içinde tespit üretildi ama anotasyon kaydedilmedi.\n" +
                "QuPath'te [Ctrl+S] ile slaydı kaydedin ve betiği tekrar çalıştırın."
}

showResultWindow(
    "Veri dışa aktarma — Tamamlandı 📤",
    String.format(
        "Mod: %s\n" +
        "Çıktı klasörü:\n  %s\n\n" +
        "💾 Kaydetme durumu\n" +
        "──────────────────\n" +
        "  %s\n\n" +
        "📊 Özet\n" +
        "──────\n" +
        "  Slayt sayısı            : %d\n" +
        "  Toplam anotasyon       : %d\n" +
        "  Toplam tespit        : %d\n" +
        "  Yazılan dosya sayısı    : %d\n" +
        "  Süre                    : %.1f sn\n\n" +
        "📁 Yazılan dosyalar:\n%s%s%s\n\n" +
        "📝 Sonraki adım:\n" +
        "  • R / Python / Excel ile `${exportFolder}/%s/` içindeki TSV dosyalarını okuyun.\n" +
        "  • GeoJSON dosyaları geopandas / sf paketleriyle açılır (anotasyon\n" +
        "    geometrisini başka araçlara taşımak için).\n" +
        "  • Modül 9'un web sayfasında format karşılaştırması ve örnek R/Python\n" +
        "    kodları var.",
        (projectMode ? "Tüm proje" : "Sadece bu görüntü"),
        outDir.getAbsolutePath(),
        saveStatusLine,
        imagesProcessed, annotationsTotal, detectionsTotal,
        filesWritten.size(), elapsed,
        fileList, errSection, emptyHint,
        stamp
    )
)

println "─────────────────────────────────────"
println String.format("Tamamlandı: %d slayt, %d dosya, %.1f sn",
    imagesProcessed, filesWritten.size(), elapsed)
println "Çıktı: ${outDir.getAbsolutePath()}"
if (!errors.isEmpty()) {
    println "⚠️  ${errors.size()} hata (detay sonuç penceresinde)"
}
println "─────────────────────────────────────"
