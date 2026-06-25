/**
 * Yardımcı - Makine Öğrenmesi için Özellik Matrisi
 * --------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * QuPath'in her hücre (tespit) için ürettiği sayısal ölçümleri, denetimli
 * makine öğrenmesine hazır bir "özellik matrisi" (X) + etiket sütunları (y)
 * olarak TSV'ye yazar. Model EĞİTMEZ; yalnızca matrisi üretir — modelleme
 * Python / R tarafında yapılır.
 *
 * Sütun grupları (veri sözlüğünde etiketlenir):
 *   • id      : image, object_type (cell/tile/detection), object_id (QuPath UUID),
 *               centroid_x_um, centroid_y_um
 *   • label   : cell_class   (tespitin PathClass'ı),
 *               region_class (hiyerarşideki üst anotasyonun PathClass'ı)
 *   • feature : her hücrenin MeasurementList'indeki tüm sayısal ölçümler
 *
 * İki mod:
 *   • Sadece bu görüntü — açık slaytın CANLI hiyerarşisinden okur
 *   • Tüm proje        — her slayt için ayrı dosya + birleştirilmiş
 *                         _all_features.tsv
 *
 * KULLANIM:
 *   1. Önce hücre tespiti + (isteğe bağlı) sınıflandırma üretin
 *      (örn. Modül 2 + Modül 6 tümör/stroma; ya da Modül 3a Ki-67).
 *   2. [Extensions → Atölye → Yardımcılar → Makine öğrenmesi için özellik matrisi]
 *      ya da [Automate → Project scripts → yardimci-ozellik-matrisi]
 *   3. Diyalogdan mod seçin → Çalıştır
 *
 * ÇIKTI: <proje-klasörü>/exports/YYYY-MM-DD_HHmmss/
 *   ├── _all_features.tsv        (yalnız "tüm proje" modunda)
 *   ├── _feature_dictionary.tsv  (sütun → id/label/feature)
 *   └── <slayt-adı>__features.tsv
 *
 * NOT: "Tüm proje" modu birleştirilmiş matris için tüm tespitleri bellekte
 *   tutar; çok büyük kohortlarda (>100k hücre) bellek yoğun olabilir — bu
 *   durumda slayt-başına TSV'leri kullanıp harici olarak birleştirin.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 * VARSAYIM: PathClass ve görüntü adları ayraç karakterini (varsayılan: sekme)
 *   içermez. İçeriyorsa sütun hizası bozulur — downstream'de temizleyin.
 */

import qupath.lib.gui.dialogs.Dialogs
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
def atolyeS = { String k, String  d -> (String)  __wpCall('str',  [String.class, String.class]  as Class[], [k, d] as Object[], d) }
def exportFolder = atolyeS('atolye.exportFolder', 'exports')
def __rawSep = atolyeS('atolye.exportSeparator', "\t")
def exportSeparatorChar = ((__rawSep != null && !__rawSep.isEmpty()) ? __rawSep : "\t").charAt(0) as char

def waitForChoice = { String windowTitle, String windowBody,
                      String optionA, String optionB ->
    if (isHeadless) {
        println "=== ${windowTitle} ===\n${windowBody}\n=================="
        return "B"
    }
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
            def __footer = new javafx.scene.control.Label("QuPath Atölye Scriptleri · araştırma/eğitim amaçlı")
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons)
            __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)

            stage.setScene(new javafx.scene.Scene(root, 820, 600))
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
// Matris şeması ve biçimleyiciler
// ──────────────────────────────────────────────────────────────
def ID_COLS    = ["image", "object_type", "object_id", "centroid_x_um", "centroid_y_um"]
def LABEL_COLS = ["cell_class", "region_class"]

// Locale-bağımsız sayısal biçim: Double.toString daima '.' kullanır (Türkçe
// JVM'de bile virgül üretmez); NaN / null → boş hücre.
def fmtVal = { v ->
    if (v == null) return ""
    try {
        double d = v as double
        return Double.isNaN(d) ? "" : Double.toString(d)
    } catch (Throwable t) { return "" }
}

// ──────────────────────────────────────────────────────────────
// Tek görüntü için satırları + özellik adları birleşimini toplar
// ──────────────────────────────────────────────────────────────
def collectImage = { imageData, String imageName ->
    def hierarchy = imageData.getHierarchy()
    def cal = imageData.getServer().getPixelCalibration()
    def hasMicrons = cal.hasPixelSizeMicrons()
    double pw = hasMicrons ? cal.getPixelWidthMicrons() : 1.0d
    double ph = hasMicrons ? cal.getPixelHeightMicrons() : 1.0d

    def rows = []
    def featureNames = new java.util.TreeSet<String>()

    hierarchy.getDetectionObjects().each { det ->
        def roi = det.getROI()
        double cx = roi != null ? roi.getCentroidX() * pw : Double.NaN
        double cy = roi != null ? roi.getCentroidY() * ph : Double.NaN

        def cellClass = det.getPathClass() != null ? det.getPathClass().toString() : ""

        String regionClass = ""
        def p = det.getParent()
        while (p != null) {
            if (p.isAnnotation()) {
                regionClass = p.getPathClass() != null ? p.getPathClass().toString() : ""
                break
            }
            p = p.getParent()
        }

        def feats = [:]
        def ml = det.getMeasurementList()
        ml.getNames().each { String nm ->
            double v = ml.get(nm)
            feats[nm] = v
            featureNames.add(nm)
        }

        rows << [
            image        : imageName,
            object_type  : (det.isCell() ? "cell" : (det.isTile() ? "tile" : "detection")),
            object_id    : (det.getID() != null ? det.getID().toString() : ""),
            centroid_x_um: cx,
            centroid_y_um: cy,
            cell_class   : cellClass,
            region_class : regionClass,
            feats        : feats
        ]
    }
    return [rows: rows, featureNames: featureNames, hasMicrons: hasMicrons]
}

// ──────────────────────────────────────────────────────────────
// Matris ve veri sözlüğü yazıcıları
// ──────────────────────────────────────────────────────────────
def writeMatrix = { File outFile, List rows, java.util.TreeSet featureNames, char sep ->
    def sepStr = sep as String
    def featList = featureNames.toList()
    def sb = new StringBuilder()
    sb.append((ID_COLS + LABEL_COLS + featList).join(sepStr)).append("\n")
    rows.each { r ->
        def line = []
        line << r.image
        line << r.object_type
        line << (r.object_id ?: "")
        line << fmtVal(r.centroid_x_um)
        line << fmtVal(r.centroid_y_um)
        line << (r.cell_class ?: "")
        line << (r.region_class ?: "")
        featList.each { nm -> line << fmtVal(r.feats.containsKey(nm) ? r.feats[nm] : Double.NaN) }
        sb.append(line.join(sepStr)).append("\n")
    }
    outFile.write(sb.toString(), "UTF-8")
}

def writeDictionary = { File dictFile, java.util.TreeSet featureNames, char sep ->
    def sepStr = sep as String
    def sb = new StringBuilder()
    sb.append(["column", "group"].join(sepStr)).append("\n")
    ID_COLS.each    { sb.append([it, "id"].join(sepStr)).append("\n") }
    LABEL_COLS.each { sb.append([it, "label"].join(sepStr)).append("\n") }
    featureNames.each { sb.append([it, "feature"].join(sepStr)).append("\n") }
    dictFile.write(sb.toString(), "UTF-8")
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
    "Özellik matrisi - Makine öğrenmesi",
    "Her hücre (tespit) için sayısal ölçümleri, etiket sütunlarıyla (cell_class,\n" +
    "region_class) birlikte makine öğrenmesine hazır bir TSV matrisine yazacağım.\n\n" +
    "Bu betik MODEL EĞİTMEZ — yalnızca X (özellikler) + y (etiketler) matrisini\n" +
    "üretir. Modelleme Python / R tarafında yapılır.\n\n" +
    "Çıktı klasörü:\n" +
    "  <proje-klasörü>/${exportFolder}/YYYY-MM-DD_HHmmss/\n\n" +
    "Hangi modu istiyorsunuz?\n" +
    "  • Sadece bu görüntü — açık slaytın canlı hiyerarşisi\n" +
    "  • Tüm proje         — her slayt + birleştirilmiş _all_features.tsv",
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

// ──────────────────────────────────────────────────────────────
// 3) Çıktı klasörü
// ──────────────────────────────────────────────────────────────
def stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
def projectDir = project.getPath().getParent().toFile()
def outDir = new File(new File(projectDir, exportFolder), stamp)
outDir.mkdirs()

println "─────────────────────────────────────"
println "Özellik matrisi (ML) - dışa aktarma"
println "Mod: " + (projectMode ? "Tüm proje" : "Sadece bu görüntü")
println "Çıktı: ${outDir.getAbsolutePath()}"

def t0 = System.currentTimeMillis()
def imagesProcessed = 0
def totalRows = 0
def filesWritten = []
def errors = []
def uncalibratedImages = []
def masterFeatures = new java.util.TreeSet<String>()
def combinedRows = []
def cellClassCounts = [:]
def regionClassCounts = [:]

def tally = { Map counts, String key ->
    def k = (key == null || key.isEmpty()) ? "(boş)" : key
    counts[k] = (counts.containsKey(k) ? counts[k] : 0) + 1
}

// ──────────────────────────────────────────────────────────────
// 4) Tek-görüntü işleyici
// ──────────────────────────────────────────────────────────────
def processImage = { imageData, String imageName ->
    def res = collectImage(imageData, imageName)
    def rows = res.rows
    if (!res.hasMicrons) uncalibratedImages << imageName

    def slug = toSlug(imageName)
    def perFile = new File(outDir, "${slug}__features.tsv")
    try {
        writeMatrix(perFile, rows, res.featureNames, exportSeparatorChar)
        if (perFile.length() > 0) filesWritten << perFile.getName()
    } catch (Throwable t) {
        errors << "${imageName} → matris: ${t.getMessage()}"
    }

    masterFeatures.addAll(res.featureNames)
    combinedRows.addAll(rows)
    rows.each { r -> tally(cellClassCounts, r.cell_class); tally(regionClassCounts, r.region_class) }
    totalRows += rows.size()
    imagesProcessed++
}

// ──────────────────────────────────────────────────────────────
// 5) Modu uygula
// ──────────────────────────────────────────────────────────────
if (projectMode) {
    // Açık slaydı önce diske kaydet — readImageData diskten okur
    if (currentImageData != null && currentEntry != null) {
        try { currentEntry.saveImageData(currentImageData) }
        catch (Throwable t) { println "⚠ Açık slayt kaydedilemedi: ${t.getMessage()}" }
    }
    def imageList = project.getImageList()
    println "  ${imageList.size()} slayt işlenecek..."
    if (imageList.size() > 10)
        println "⚠ Büyük proje: birleştirilmiş matris bellek yoğundur (${imageList.size()} slayt)."
    imageList.each { entry ->
        try {
            def imgData = entry.readImageData()
            processImage(imgData, entry.getImageName())
        } catch (Throwable t) {
            errors << "${entry.getImageName()} → readImageData: ${t.getMessage()}"
        }
    }
    // Birleştirilmiş matris + sözlük
    try {
        def allFile = new File(outDir, "_all_features.tsv")
        writeMatrix(allFile, combinedRows, masterFeatures, exportSeparatorChar)
        if (allFile.length() > 0) filesWritten << allFile.getName()
    } catch (Throwable t) {
        errors << "_all_features: ${t.getMessage()}"
    }
} else {
    def name = currentEntry != null ? currentEntry.getImageName()
                                    : currentImageData.getServer().getMetadata().getName()
    processImage(currentImageData, name)
}

// Veri sözlüğü (master birleşimden)
try {
    def dictFile = new File(outDir, "_feature_dictionary.tsv")
    writeDictionary(dictFile, masterFeatures, exportSeparatorChar)
    if (dictFile.length() > 0) filesWritten << dictFile.getName()
} catch (Throwable t) {
    errors << "_feature_dictionary: ${t.getMessage()}"
}

def elapsed = (System.currentTimeMillis() - t0) / 1000.0

// ──────────────────────────────────────────────────────────────
// 6) Sonuç penceresi
// ──────────────────────────────────────────────────────────────
def fmtCounts = { Map counts ->
    if (counts.isEmpty()) return "  (yok)"
    counts.sort { -it.value }.take(12).collect { k, v -> "  ${k}: ${v}" }.join("\n")  // sort: yeni map döner, orijinal değişmez
}

def emptyHint = ""
def allLabelsEmpty = (cellClassCounts.keySet() == ["(boş)"].toSet() || cellClassCounts.isEmpty()) &&
                     (regionClassCounts.keySet() == ["(boş)"].toSet() || regionClassCounts.isEmpty())
if (totalRows == 0) {
    emptyHint = "\n\n💡 Hiç tespit bulunamadı. Önce bir modülde hücre tespiti çalıştırın (Modül 2-7)."
} else if (allLabelsEmpty) {
    emptyHint = "\n\n💡 cell_class ve region_class boş. Denetimli ML için etiket gerekir:\n" +
                "  • cell_class için: hücreleri sınıflandırın (Modül 3a / 6).\n" +
                "  • region_class için: tümör/stroma anotasyonu içinde tespit üretin (Modül 6 → Modül 7)."
}

def calibNote = uncalibratedImages.isEmpty() ? "" :
    "\n\n⚠ Kalibrasyonsuz slayt(lar) — centroid PİKSEL cinsinden yazıldı:\n" +
    uncalibratedImages.take(5).collect { "  • ${it}" }.join("\n") +
    (uncalibratedImages.size() > 5 ? "\n  ... ve ${uncalibratedImages.size() - 5} slayt daha" : "")

def errSection = errors.isEmpty() ? "" :
    "\n\n⚠️ Bazı dosyalar yazılamadı:\n" + errors.take(10).collect { "  • ${it}" }.join("\n")

showResultWindow(
    "Özellik matrisi — Tamamlandı 🧮",
    String.format(java.util.Locale.US,
        "Mod: %s\n" +
        "Çıktı klasörü:\n  %s\n\n" +
        "📊 Özet\n──────\n" +
        "  Slayt sayısı       : %d\n" +
        "  Toplam hücre (satır): %d\n" +
        "  Özellik sütunu     : %d\n" +
        "  Yazılan dosya      : %d\n" +
        "  Süre               : %.1f sn\n\n" +
        "🏷 cell_class dağılımı\n──────────────────\n%s\n\n" +
        "🏷 region_class dağılımı\n────────────────────\n%s%s%s%s\n\n" +
        "📝 Sonraki adım:\n" +
        "  • `${exportFolder}/%s/_all_features.tsv` (ya da <slayt>__features.tsv) dosyasını\n" +
        "    Python (pandas) / R ile okuyun. X = feature sütunları, y = cell_class veya region_class.\n" +
        "  • Sütun grupları `_feature_dictionary.tsv` içinde id/label/feature olarak etiketlidir.\n" +
        "  • Modül 9 web sayfasındaki 'Makine öğrenmesi için özellik matrisi' bölümünde\n" +
        "    scikit-learn örneği var. Bu betik yalnızca matrisi üretir; model eğitmez.\n\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
        (projectMode ? "Tüm proje" : "Sadece bu görüntü"),
        outDir.getAbsolutePath(),
        imagesProcessed, totalRows, masterFeatures.size(),
        filesWritten.size(), elapsed,
        fmtCounts(cellClassCounts), fmtCounts(regionClassCounts),
        calibNote, emptyHint, errSection,
        stamp
    )
)

println String.format(java.util.Locale.US, "Tamamlandı: %d slayt, %d satır, %d özellik, %.1f sn",
    imagesProcessed, totalRows, masterFeatures.size(), elapsed)
println "─────────────────────────────────────"
