/**
 * Modül 9 - Etkileşimli Veri Dışa Aktarma
 * ----------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Anotasyon + tespit ölçümlerini (TSV/CSV) ve anotasyon geometrisini (GeoJSON)
 * dışa aktarır. Çalıştırınca bir seçenek penceresi açılır; seçimler hatırlanır
 * (Atölye Ayarları → Dışa aktarma altında da görünür/düzenlenir).
 *
 * Pencerede seçilebilenler:
 *   • Kapsam      — Sadece bu görüntü / Tüm proje
 *   • Çıktı stili — ayraç (TAB→.tsv / Virgül→.csv), içerik (tespit/anotasyon/GeoJSON),
 *                   sütunlar (tüm sütunlar / seçili alt küme)
 *   • İsimlendirme— dosya adı öneki + tarihli alt klasör (aç/kapat)
 *   • Kayıt yeri  — "Seç…" ile klasör seçici (boş = proje/exports)
 *
 * KULLANIM:
 *   1. Modüllerden birinde (2, 3, 3b, 4, 5, 6, 7, 8) ölçüm üretmiş olun
 *   2. [Automate → Project scripts → Modül 9 - Veri dışa aktarma]
 *   3. Seçenekleri belirleyin → Dışa aktar
 *
 * ÇIKTI (varsayılan):
 *   <taban>/YYYY-MM-DD_HHmmss/
 *     ├── _all_detections.tsv          (yalnız "tüm proje" modunda)
 *     ├── _all_annotations.tsv         (yalnız "tüm proje" modunda)
 *     ├── <slayt-adı>__detections.tsv
 *     ├── <slayt-adı>__annotations.tsv
 *     └── <slayt-adı>__annotations.geojson
 *   <taban> = seçilen kayıt yeri, yoksa <proje-klasörü>/exports
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
// Seçimleri hatırlamak için geri-yazma (eklenti yoksa sessizce yok sayılır)
def atolyeSetS = { String k, String  v -> __wpCall('setStr',  [String.class, String.class]  as Class[], [k, v] as Object[], null) }
def atolyeSetB = { String k, boolean v -> __wpCall('setBool', [String.class, boolean.class] as Class[], [k, v] as Object[], null) }

def exportFolder = atolyeS('atolye.exportFolder', 'exports')

// Sütun "alt küme" modunda sunulan aday liste. "" tercih değeri ⇒ bu listenin tamamı.
def COLUMN_MASTER = [
    'Image', 'Name', 'Class',
    'Centroid X µm', 'Centroid Y µm',
    'Nucleus: DAB OD mean', 'Cytoplasm: DAB OD mean', 'Cell: DAB OD mean',
    'Area µm^2', 'Num Detections', 'Positive %', 'H-score'
]

// ──────────────────────────────────────────────────────────────
// Etkileşimli seçenek penceresi — bir Map döndürür (İptal'de null).
// Headless modda diyalog atlanır, hatırlanan varsayılanlar kullanılır.
// ──────────────────────────────────────────────────────────────
def showExportOptions = { Map d ->
    if (isHeadless) {
        println "=== Modül 9 - Veri dışa aktarma (headless) ==="
        println "Diyalog atlandı; hatırlanan/varsayılan seçimlerle devam ediliyor."
        return d
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def result = new java.util.concurrent.atomic.AtomicReference<Map>(null)

    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle("Modül 9 - Dışa aktarma seçenekleri")
            stage.setAlwaysOnTop(true)

            def mk = { String t ->
                def l = new javafx.scene.control.Label(t)
                l.setStyle("-fx-font-weight: bold;")
                return l
            }

            // --- Kapsam ---
            def scopeGroup = new javafx.scene.control.ToggleGroup()
            def rbImage = new javafx.scene.control.RadioButton("Sadece bu görüntü")
            rbImage.setToggleGroup(scopeGroup); rbImage.setUserData("image")
            def rbProject = new javafx.scene.control.RadioButton("Tüm proje")
            rbProject.setToggleGroup(scopeGroup); rbProject.setUserData("project")
            (d.scope == "image" ? rbImage : rbProject).setSelected(true)
            def scopeBox = new javafx.scene.layout.HBox(12, rbImage, rbProject)

            // --- Çıktı stili: ayraç ---
            def sepGroup = new javafx.scene.control.ToggleGroup()
            def rbTab = new javafx.scene.control.RadioButton("TAB (.tsv)")
            rbTab.setToggleGroup(sepGroup); rbTab.setUserData("\t")
            def rbComma = new javafx.scene.control.RadioButton("Virgül (.csv)")
            rbComma.setToggleGroup(sepGroup); rbComma.setUserData(",")
            (d.separator == "," ? rbComma : rbTab).setSelected(true)
            def sepBox = new javafx.scene.layout.HBox(12, rbTab, rbComma)

            // --- Çıktı stili: içerik (nesne tipleri) ---
            def cbDet = new javafx.scene.control.CheckBox("Tespit ölçümleri (TSV/CSV)")
            cbDet.setSelected(d.det as boolean)
            def cbAnn = new javafx.scene.control.CheckBox("Anotasyon ölçümleri (TSV/CSV)")
            cbAnn.setSelected(d.ann as boolean)
            def cbGeo = new javafx.scene.control.CheckBox("Anotasyon geometrisi (GeoJSON)")
            cbGeo.setSelected(d.geo as boolean)
            def typesBox = new javafx.scene.layout.VBox(4, cbDet, cbAnn, cbGeo)

            // --- Çıktı stili: sütunlar ---
            def colGroup = new javafx.scene.control.ToggleGroup()
            def rbAllCols = new javafx.scene.control.RadioButton("Tüm sütunlar")
            rbAllCols.setToggleGroup(colGroup); rbAllCols.setUserData("all")
            def rbSubset = new javafx.scene.control.RadioButton("Seçili alt küme")
            rbSubset.setToggleGroup(colGroup); rbSubset.setUserData("subset")
            (d.columnsMode == "subset" ? rbSubset : rbAllCols).setSelected(true)
            def preChecked = (d.columns ?: []) as List
            def colChecks = []
            def colChecksBox = new javafx.scene.layout.VBox(2)
            COLUMN_MASTER.each { cn ->
                def cb = new javafx.scene.control.CheckBox(cn)
                cb.setSelected(preChecked.contains(cn))
                colChecks << cb
                colChecksBox.getChildren().add(cb)
            }
            def colScroll = new javafx.scene.control.ScrollPane(colChecksBox)
            colScroll.setFitToWidth(true)
            colScroll.setPrefHeight(130)
            def syncColEnabled = { ->
                def subset = (colGroup.getSelectedToggle()?.getUserData() == "subset")
                colChecksBox.setDisable(!subset)
            }
            colGroup.selectedToggleProperty().addListener(
                { obs, o, n -> syncColEnabled() } as javafx.beans.value.ChangeListener
            )
            syncColEnabled()
            def colModeBox = new javafx.scene.layout.HBox(12, rbAllCols, rbSubset)
            def colBox = new javafx.scene.layout.VBox(4, colModeBox, colScroll)

            // --- İsimlendirme ---
            def prefixField = new javafx.scene.control.TextField((d.prefix ?: "") as String)
            prefixField.setPromptText("(opsiyonel önek)")
            def cbDate = new javafx.scene.control.CheckBox("Tarihli alt klasör (YYYY-MM-DD_HHmmss)")
            cbDate.setSelected(d.dateSubfolder as boolean)

            // --- Kayıt yeri ---
            def locField = new javafx.scene.control.TextField((d.location ?: "") as String)
            locField.setPromptText((d.defaultBaseHint ?: "") as String)
            javafx.scene.layout.HBox.setHgrow(locField, javafx.scene.layout.Priority.ALWAYS)
            def browseBtn = new javafx.scene.control.Button("Seç…")
            browseBtn.setOnAction({
                try {
                    def chooser = new javafx.stage.DirectoryChooser()
                    chooser.setTitle("Çıktı klasörü seçin")
                    def cur = (locField.getText() ?: "").trim()
                    def init = cur ? new File(cur) : (d.defaultBaseDir ? new File(d.defaultBaseDir as String) : null)
                    if (init != null && init.isDirectory()) chooser.setInitialDirectory(init)
                    def chosen = chooser.showDialog(stage)
                    if (chosen != null) locField.setText(chosen.getAbsolutePath())
                } catch (Throwable ignored) { }
            })
            def locBox = new javafx.scene.layout.HBox(8, locField, browseBtn)
            def locHint = new javafx.scene.control.Label("Boş bırakırsanız proje klasörünün yanındaki '" + exportFolder + "' kullanılır.")
            locHint.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-size: 11px;")

            // --- Form ---
            def form = new javafx.scene.layout.VBox(10)
            form.setStyle("-fx-padding: 12px;")
            form.getChildren().addAll(
                mk("Kapsam"), scopeBox,
                new javafx.scene.control.Separator(),
                mk("Çıktı stili — ayraç"), sepBox,
                mk("Çıktı stili — içerik"), typesBox,
                mk("Çıktı stili — sütunlar"), colBox,
                new javafx.scene.control.Separator(),
                mk("İsimlendirme"), prefixField, cbDate,
                new javafx.scene.control.Separator(),
                mk("Kayıt yeri"), locBox, locHint
            )
            def scrollPane = new javafx.scene.control.ScrollPane(form)
            scrollPane.setFitToWidth(true)

            // --- Düğmeler ---
            def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut")
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )
            def cancelBtn = new javafx.scene.control.Button("İptal")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({ result.set(null); stage.close() })
            def exportBtn = new javafx.scene.control.Button("Dışa aktar")
            exportBtn.setDefaultButton(true)
            exportBtn.setOnAction({
                def scope = (scopeGroup.getSelectedToggle()?.getUserData() ?: "project") as String
                def sep = (sepGroup.getSelectedToggle()?.getUserData() ?: "\t") as String
                def colsMode = (colGroup.getSelectedToggle()?.getUserData() ?: "all") as String
                def selCols = colChecks.findAll { it.isSelected() }.collect { it.getText() }
                def prefix = ((prefixField.getText() ?: "").trim())
                def loc = ((locField.getText() ?: "").trim())
                def m = [
                    scope        : scope,
                    det          : cbDet.isSelected(),
                    ann          : cbAnn.isSelected(),
                    geo          : cbGeo.isSelected(),
                    columnsMode  : colsMode,
                    columns      : selCols,
                    separator    : sep,
                    prefix       : prefix,
                    dateSubfolder: cbDate.isSelected(),
                    location     : loc
                ]
                // Seçimleri hatırla
                atolyeSetS('atolye.exportScope', scope)
                atolyeSetB('atolye.exportDet', cbDet.isSelected())
                atolyeSetB('atolye.exportAnn', cbAnn.isSelected())
                atolyeSetB('atolye.exportGeo', cbGeo.isSelected())
                atolyeSetS('atolye.exportColumnsMode', colsMode)
                atolyeSetS('atolye.exportColumns', selCols.join('|'))
                atolyeSetS('atolye.exportSeparator', sep)
                atolyeSetS('atolye.exportPrefix', prefix)
                atolyeSetB('atolye.exportDateSubfolder', cbDate.isSelected())
                atolyeSetS('atolye.exportLocation', loc)
                result.set(m)
                stage.close()
            })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, cancelBtn, exportBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))

            stage.setOnHidden({ latch.countDown() })

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(scrollPane)
            root.setBottom(buttons)
            stage.setScene(new javafx.scene.Scene(root, 560, 660))
            stage.show()
        } catch (Throwable t) {
            // Gelişmiş diyalog açılamadı — modal yedek: varsayılanlarla devam?
            try {
                def yn = qupath.lib.gui.dialogs.Dialogs.showConfirmDialog(
                    "Modül 9 - Veri dışa aktarma",
                    "Gelişmiş seçenek penceresi açılamadı.\n\n" +
                    "Hatırlanan/varsayılan ayarlarla devam edeyim mi?\n" +
                    "(kapsam, ayraç, içerik, sütunlar, isimlendirme, kayıt yeri)"
                )
                result.set(yn ? d : null)
            } catch (Throwable t2) {
                result.set(null)
            }
            latch.countDown()
        }
    }

    latch.await()
    return result.get()
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
// 2) Hatırlanan varsayılanları topla + seçenek penceresini aç
// ──────────────────────────────────────────────────────────────
def projectPath = project.getPath()
def projectDir = projectPath.getParent().toFile()
def defaultBase = new File(projectDir, exportFolder)

def storedCols = atolyeS('atolye.exportColumns', '')
def defaultCols = storedCols ? (storedCols.split('\\|') as List) : new ArrayList(COLUMN_MASTER)

def defaults = [
    scope          : atolyeS('atolye.exportScope', 'project'),
    det            : atolyeB('atolye.exportDet', true),
    ann            : atolyeB('atolye.exportAnn', true),
    geo            : atolyeB('atolye.exportGeo', true),
    columnsMode    : atolyeS('atolye.exportColumnsMode', 'all'),
    columns        : defaultCols,
    separator      : atolyeS('atolye.exportSeparator', "\t"),
    prefix         : atolyeS('atolye.exportPrefix', ''),
    dateSubfolder  : atolyeB('atolye.exportDateSubfolder', true),
    location       : atolyeS('atolye.exportLocation', ''),
    defaultBaseDir : defaultBase.getAbsolutePath(),
    defaultBaseHint: defaultBase.getAbsolutePath()
]

def opt = showExportOptions(defaults)
if (opt == null) { println "İptal."; return }

if (!(opt.det as boolean) && !(opt.ann as boolean) && !(opt.geo as boolean)) {
    Dialogs.showErrorMessage(
        "Çıktı türü seçilmedi",
        "En az bir çıktı türü seçin:\n" +
        "  • Tespit ölçümleri (TSV/CSV)\n" +
        "  • Anotasyon ölçümleri (TSV/CSV)\n" +
        "  • Anotasyon geometrisi (GeoJSON)"
    )
    return
}

def projectMode = (opt.scope == "project")

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
// 3) Seçimlerden çıktı parametrelerini türet + çıktı klasörünü hazırla
// ──────────────────────────────────────────────────────────────
def exportSeparatorChar = ((opt.separator ?: "\t") as String).charAt(0) as char
def ext = ((opt.separator ?: "\t") == "\t") ? "tsv" : "csv"
def pfx = (opt.prefix) ? (opt.prefix + "_") : ""
def colList = (opt.columns ?: []) as List
def useSubset = (opt.columnsMode == "subset") && !colList.isEmpty()
def subsetCols = colList as String[]

def stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))

def chosenLoc = ((opt.location ?: "") as String).trim()
def base
if (chosenLoc) {
    def f = new File(chosenLoc)
    if (f.isDirectory()) {
        base = f
    } else if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
        base = f   // mkdirs ile oluşturulacak
    } else {
        base = defaultBase
        println "⚠ Seçilen kayıt yeri bulunamadı (${chosenLoc}); proje varsayılanı kullanılıyor."
    }
} else {
    base = defaultBase
}
def outDir = (opt.dateSubfolder as boolean) ? new File(base, stamp) : base
outDir.mkdirs()

println "─────────────────────────────────────"
println "Modül 9 - Veri dışa aktarma"
println "─────────────────────────────────────"
println "Mod: " + (projectMode ? "Tüm proje" : "Sadece bu görüntü")
println "Stil: " + (ext == "tsv" ? "TSV (TAB)" : "CSV (virgül)") +
        " · " + (useSubset ? "${subsetCols.length} sütun" : "tüm sütunlar")
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

    // Tespit ölçümleri (per-image)
    if (opt.det as boolean) {
        try {
            def detFile = new File(outDir, "${pfx}${slug}__detections.${ext}")
            def me = new MeasurementExporter()
                .imageList([entry])
                .separator(exportSeparatorChar)
                .exportType(PathDetectionObject.class)
            if (useSubset) me = me.includeOnlyColumns(subsetCols)
            me.exportMeasurements(detFile)
            if (detFile.length() > 0) filesWritten << detFile.getName()
        } catch (Throwable t) {
            errors << "${entry.getImageName()} → tespitler: ${t.getMessage()}"
        }
    }

    // Anotasyon ölçümleri (per-image)
    if (opt.ann as boolean) {
        try {
            def annFile = new File(outDir, "${pfx}${slug}__annotations.${ext}")
            def me = new MeasurementExporter()
                .imageList([entry])
                .separator(exportSeparatorChar)
                .exportType(PathAnnotationObject.class)
            if (useSubset) me = me.includeOnlyColumns(subsetCols)
            me.exportMeasurements(annFile)
            if (annFile.length() > 0) filesWritten << annFile.getName()
        } catch (Throwable t) {
            errors << "${entry.getImageName()} → anotasyonlar: ${t.getMessage()}"
        }
    }

    // Anotasyon GeoJSON + sayım
    try {
        def annos = imageDataForGeo?.getHierarchy()?.getAnnotationObjects() ?: []
        if ((opt.geo as boolean) && !annos.isEmpty()) {
            def geoFile = new File(outDir, "${pfx}${slug}__annotations.geojson")
            geoFile.write(gson.toJson(annos), "UTF-8")
            filesWritten << geoFile.getName()
        }
        annotationsTotal += annos.size()
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

    // Birleştirilmiş dosyalar — tek dosyada tüm projenin tespitler / anotasyonlar
    if (opt.det as boolean) {
        try {
            def allDet = new File(outDir, "${pfx}_all_detections.${ext}")
            def me = new MeasurementExporter()
                .imageList(imageList)
                .separator(exportSeparatorChar)
                .exportType(PathDetectionObject.class)
            if (useSubset) me = me.includeOnlyColumns(subsetCols)
            me.exportMeasurements(allDet)
            if (allDet.length() > 0) filesWritten << allDet.getName()
        } catch (Throwable t) {
            errors << "_all_detections: ${t.getMessage()}"
        }
    }

    if (opt.ann as boolean) {
        try {
            def allAnn = new File(outDir, "${pfx}_all_annotations.${ext}")
            def me = new MeasurementExporter()
                .imageList(imageList)
                .separator(exportSeparatorChar)
                .exportType(PathAnnotationObject.class)
            if (useSubset) me = me.includeOnlyColumns(subsetCols)
            me.exportMeasurements(allAnn)
            if (allAnn.length() > 0) filesWritten << allAnn.getName()
        } catch (Throwable t) {
            errors << "_all_annotations: ${t.getMessage()}"
        }
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
if (filesWritten.isEmpty()) fileList = "  (dosya yazılmadı)"

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

def fmtName = (ext == "tsv") ? "TSV (TAB)" : "CSV (virgül)"
def typesSummary = [
    (opt.det as boolean) ? "tespit ${ext.toUpperCase(java.util.Locale.ROOT)}" : null,
    (opt.ann as boolean) ? "anotasyon ${ext.toUpperCase(java.util.Locale.ROOT)}" : null,
    (opt.geo as boolean) ? "anotasyon GeoJSON" : null
].findAll { it != null }.join(", ")
def colsSummary = useSubset ? "seçili ${subsetCols.length} sütun" : "tüm sütunlar"
def elapsedStr = String.format(java.util.Locale.US, "%.1f", elapsed)

def resultBody =
    "Kapsam: ${projectMode ? 'Tüm proje' : 'Sadece bu görüntü'}\n" +
    "Çıktı stili: ${fmtName} · ${colsSummary}\n" +
    "İçerik: ${typesSummary}\n" +
    "Önek: ${opt.prefix ? opt.prefix : '(yok)'}    Tarihli alt klasör: ${(opt.dateSubfolder as boolean) ? 'evet' : 'hayır'}\n" +
    "Çıktı klasörü:\n  ${outDir.getAbsolutePath()}\n\n" +
    "💾 Kaydetme durumu\n" +
    "──────────────────\n" +
    "  ${saveStatusLine}\n\n" +
    "📊 Özet\n" +
    "──────\n" +
    "  Slayt sayısı         : ${imagesProcessed}\n" +
    "  Toplam anotasyon     : ${annotationsTotal}\n" +
    "  Toplam tespit        : ${detectionsTotal}\n" +
    "  Yazılan dosya sayısı : ${filesWritten.size()}\n" +
    "  Süre                 : ${elapsedStr} sn\n\n" +
    "📁 Yazılan dosyalar:\n${fileList}${errSection}${emptyHint}\n\n" +
    "📝 Sonraki adım:\n" +
    "  • R / Python / Excel ile yukarıdaki klasördeki ${fmtName} dosyalarını okuyun.\n" +
    "  • GeoJSON dosyaları geopandas / sf paketleriyle açılır (anotasyon\n" +
    "    geometrisini başka araçlara taşımak için).\n" +
    "  • Modül 9'un web sayfasında format karşılaştırması ve örnek R/Python\n" +
    "    kodları var."

showResultWindow("Veri dışa aktarma — Tamamlandı 📤", resultBody)

println "─────────────────────────────────────"
println String.format(java.util.Locale.US, "Tamamlandı: %d slayt, %d dosya, %.1f sn",
    imagesProcessed, filesWritten.size(), elapsed)
println "Çıktı: ${outDir.getAbsolutePath()}"
if (!errors.isEmpty()) {
    println "⚠️  ${errors.size()} hata (detay sonuç penceresinde)"
}
println "─────────────────────────────────────"
