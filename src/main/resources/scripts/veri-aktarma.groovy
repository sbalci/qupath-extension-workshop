/**
 * Etkileşimli Veri Dışa Aktarma
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
 *   1. Hücre tespiti, nükleer boya, ER/PR H-score, membran boya, sitoplazmik boya,
 *      tümör/stroma, tümör içi Ki-67 veya QuANTUM modüllerinden birinde ölçüm üretmiş olun
 *   2. [Automate → Project scripts → Veri dışa aktarma]
 *   3. Seçenekleri belirleyin → Dışa aktar
 *
 * ÇIKTI (varsayılan):
 *   <taban>/YYYY-MM-DD_HHmmss/
 *     ├── _all_detections.tsv          (yalnız "tüm proje" modunda)
 *     ├── _all_annotations.tsv         (yalnız "tüm proje" modunda)
 *     ├── <lam-adı>__detections.tsv
 *     ├── <lam-adı>__annotations.tsv
 *     └── <lam-adı>__annotations.geojson
 *   <taban> = seçilen kayıt yeri, yoksa <proje-klasörü>/exports
 *
 * Not: Türkçe karakterler ve özel sembolleri içeren lam adları
 * ASCII-güvenli bir slug'a çevrilerek dosya adında kullanılır.
 */

import qupath.fx.dialogs.Dialogs
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
// Açık lamda henüz nesne/ölçüm yoksa açık projedeki kayıtlı lamlardan da sütun örneklenir.
def allCols = new LinkedHashSet<String>([
    'Image', 'Name', 'Class',
    'Centroid X µm', 'Centroid Y µm',
    'Nucleus: DAB OD mean', 'Cytoplasm: DAB OD mean', 'Membrane: DAB OD mean', 'Cell: DAB OD mean',
    'Area µm^2', 'Num Detections', 'Positive %', 'H-score', 'Pixelwise H-score', 'cTCF (%)'
])
try {
    def curH = QP.getCurrentHierarchy()
    def sampleObjs = curH != null ? (curH.getAnnotationObjects().take(20) + curH.getDetectionObjects().take(50)) : []
    if (sampleObjs.isEmpty() && QP.getProject() != null) {
        for (entry in QP.getProject().getImageList()) {
            try {
                if (entry.hasImageData()) {
                    def h = entry.readHierarchy()
                    sampleObjs = (h.getAnnotationObjects().take(20) + h.getDetectionObjects().take(50))
                    if (!sampleObjs.isEmpty()) break
                }
            } catch (Throwable ignored) {}
        }
    }
    sampleObjs.each { obj ->
        try { allCols.addAll(obj.getMeasurementList().getNames()) } catch (Throwable ignored) {}
    }
} catch (Throwable ignored) {}
def COLUMN_MASTER = new ArrayList<String>(allCols)

// ──────────────────────────────────────────────────────────────
// Etkileşimli seçenek penceresi — bir Map döndürür (İptal'de null).
// Headless modda diyalog atlanır, hatırlanan varsayılanlar kullanılır.
// ──────────────────────────────────────────────────────────────
def showExportOptions = { Map d ->
    if (isHeadless) {
        println "=== Veri dışa aktarma (headless) ==="
        println "Diyalog atlandı; hatırlanan/varsayılan seçimlerle devam ediliyor."
        return d
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def result = new java.util.concurrent.atomic.AtomicReference<Map>(null)

    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle(workshopText('script.b12a7a3f6e20', "Dışa aktarma seçenekleri"))
            stage.setAlwaysOnTop(true)

            def mk = { String t ->
                def l = new javafx.scene.control.Label(t)
                l.setStyle("-fx-font-weight: bold;")
                return l
            }

            // --- Kapsam ---
            def scopeGroup = new javafx.scene.control.ToggleGroup()
            def rbImage = new javafx.scene.control.RadioButton(workshopText('script.70fdd47a1816', "Sadece bu görüntü"))
            rbImage.setToggleGroup(scopeGroup); rbImage.setUserData("image")
            def rbProject = new javafx.scene.control.RadioButton(workshopText('script.3080d2dc7199', "Tüm proje"))
            rbProject.setToggleGroup(scopeGroup); rbProject.setUserData("project")
            (d.scope == "image" ? rbImage : rbProject).setSelected(true)
            def scopeBox = new javafx.scene.layout.HBox(12, rbImage, rbProject)

            // --- Çıktı stili: ayraç ---
            def sepGroup = new javafx.scene.control.ToggleGroup()
            def rbTab = new javafx.scene.control.RadioButton("TAB (.tsv)")
            rbTab.setToggleGroup(sepGroup); rbTab.setUserData("\t")
            def rbComma = new javafx.scene.control.RadioButton(workshopText('script.00a8fd0a1c4a', "Virgül (.csv)"))
            rbComma.setToggleGroup(sepGroup); rbComma.setUserData(",")
            (d.separator == "," ? rbComma : rbTab).setSelected(true)
            def sepBox = new javafx.scene.layout.HBox(12, rbTab, rbComma)

            // --- Çıktı stili: içerik (nesne tipleri) ---
            def cbDet = new javafx.scene.control.CheckBox(workshopText('script.17bdb25b5ce2', "Tespit ölçümleri (TSV/CSV)"))
            cbDet.setSelected(d.det as boolean)
            def cbAnn = new javafx.scene.control.CheckBox(workshopText('script.7a31191cc20c', "Anotasyon ölçümleri (TSV/CSV)"))
            cbAnn.setSelected(d.ann as boolean)
            def cbGeo = new javafx.scene.control.CheckBox(workshopText('script.1f0f6ec587bc', 'Anotasyon geometrisi (GeoJSON)'))
            cbGeo.setSelected(d.geo as boolean)
            def typesBox = new javafx.scene.layout.VBox(4, cbDet, cbAnn, cbGeo)

            // --- Çıktı stili: sütunlar ---
            def colGroup = new javafx.scene.control.ToggleGroup()
            def rbAllCols = new javafx.scene.control.RadioButton(workshopText('script.06fea0fdf4c2', "Tüm sütunlar"))
            rbAllCols.setToggleGroup(colGroup); rbAllCols.setUserData("all")
            def rbSubset = new javafx.scene.control.RadioButton(workshopText('script.a73883a123b2', "Seçili alt küme"))
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
            prefixField.setPromptText(workshopText('script.39f383d7f2fd', "(isteğe bağlı önek)"))
            def cbDate = new javafx.scene.control.CheckBox(workshopText('script.923cd2118e8f', "Tarihli alt klasör (YYYY-MM-DD_HHmmss)"))
            cbDate.setSelected(d.dateSubfolder as boolean)

            // --- Kayıt yeri ---
            def locField = new javafx.scene.control.TextField((d.location ?: "") as String)
            locField.setPromptText((d.defaultBaseHint ?: "") as String)
            javafx.scene.layout.HBox.setHgrow(locField, javafx.scene.layout.Priority.ALWAYS)
            def browseBtn = new javafx.scene.control.Button(workshopText('script.dfd12ee38484', "Seç…"))
            browseBtn.setOnAction({
                try {
                    def cur = (locField.getText() ?: "").trim()
                    def init = cur ? new File(cur) : (d.defaultBaseDir ? new File(d.defaultBaseDir as String) : null)
                    def initDir = (init != null && init.isDirectory()) ? init : null
                    def chosen = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, workshopText('script.cc9187efabde', 'Çıktı klasörü seçin'), initDir)
                    if (chosen != null) locField.setText(chosen.getAbsolutePath())
                } catch (Throwable ignored) { }
            })
            def locBox = new javafx.scene.layout.HBox(8, locField, browseBtn)
            def locHint = new javafx.scene.control.Label(workshopText('script.cd81fe71c798', "Boş bırakırsanız proje klasörünün yanındaki '") + exportFolder + workshopText('script.a1b96019947f', "' kullanılır."))
            locHint.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-size: 11px;")

            // --- Form ---
            def form = new javafx.scene.layout.VBox(10)
            form.setStyle("-fx-padding: 12px;")
            form.getChildren().addAll(
                mk(workshopText('script.7b0d5cffae67', 'Kapsam')), scopeBox,
                new javafx.scene.control.Separator(),
                mk(workshopText('script.7ae68d5863b0', 'Çıktı stili — ayraç')), sepBox,
                mk(workshopText('script.52de85bb63d1', 'Çıktı stili — içerik')), typesBox,
                mk(workshopText('script.1c9d40ba6180', 'Çıktı stili — sütunlar')), colBox,
                new javafx.scene.control.Separator(),
                mk(workshopText('script.1b4d02061eed', 'İsimlendirme')), prefixField, cbDate,
                new javafx.scene.control.Separator(),
                mk(workshopText('script.b556149f07f9', 'Kayıt yeri')), locBox, locHint
            )
            def scrollPane = new javafx.scene.control.ScrollPane(form)
            scrollPane.setFitToWidth(true)

            // --- Düğmeler ---
            def alwaysTop = new javafx.scene.control.CheckBox(workshopText('script.70ca64c0c43a', "Üstte tut"))
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )
            def cancelBtn = new javafx.scene.control.Button(workshopText('script.72278748133a', "İptal"))
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({ result.set(null); stage.close() })
            def exportBtn = new javafx.scene.control.Button(workshopText('script.d4f745cb3bf5', "Dışa aktar"))
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
                def yn = qupath.fx.dialogs.Dialogs.showConfirmDialog(
                    workshopText('script.331d9795a7eb', "Veri dışa aktarma"),
                    workshopText('script.eae1b670af1c', "Gelişmiş seçenek penceresi açılamadı.\n\n" +
                    "Hatırlanan/varsayılan ayarlarla devam edeyim mi?\n" +
                    "(kapsam, ayraç, içerik, sütunlar, isimlendirme, kayıt yeri)")
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

            stage.setScene(new javafx.scene.Scene(root, 760, 580))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Yardımcı: Türkçe karakter + boşluk içeren lam isimlerini güvenli slug'a çevirir
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
// Kopya grupları: bir lamda aynı sonucun "ad", "ad (2)", "ad (3)" … olarak yan yana durması.
// Kural (tam eşleşme, önek eşleşmesi YOK): "X (N)" (N tam sayı ≥ 2) "X" tabanına aittir. Bir grup,
// o tabanın en az İKİ farklı numarası (taban = 1) aynı lamda varsa kopya grubudur; tek başına bir
// "X (N)" (X silinmiş) grup değildir. "X (notlarım)" ve "X (1)" kopya değildir. Sondaki " (N)" yalnız
// aynı tabanın bir kardeşi varsa kopya numarasıdır: "… Mitotic figure (12)" (nokta sayısı) tek başına
// bir şey ifade etmez, "… (12) (2)" ile birlikte "… (12)" tabanının 1. ve 2. kopyasıdır.
// Sınır: aynı lamda "X (12)" ve "X (3)" varsa (X yok) ikisi kopya sayılır; ad tek başına nokta
// sayısı ile kopya numarasını ayıramaz. En yeni = en yüksek numara.
// ──────────────────────────────────────────────────────────────
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
def copiesAncestorHit = { obj, Set ids ->
    def o = obj
    while (o != null) {
        if (ids.contains(o.getID())) return true
        o = o.getParent()
    }
    return false
}
def copiesReportLines = { Map results, boolean newest ->
    def lines = []
    results.each { k, v ->
        v.scan.groups.each { b, idx ->
            lines << (v.name + ' · ' + b + ': ' + idx.keySet().join(', ') + (newest ? ' → ' + idx.keySet().max() : ''))
        }
    }
    return lines
}
def copiesScanHierarchy = { hierarchy ->
    def annos = hierarchy.getAnnotationObjects() as List
    def groups = copiesGroups(annos, { it.getName() })
    if (groups.isEmpty()) return null
    def ids = new HashSet(copiesOlder(groups).collect { it.getID() })
    int leftAnn = annos.count { copiesAncestorHit(it, ids) }
    int leftDet = hierarchy.getDetectionObjects().count { copiesAncestorHit(it, ids) }
    return [groups: groups, ids: ids, leftAnn: leftAnn, leftDet: leftDet]
}
def copiesEntryKey = { entry -> try { entry.getID() } catch (Throwable t) { entry.getImageName() } }
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

// ──────────────────────────────────────────────────────────────
// 1) Ön kontroller
// ──────────────────────────────────────────────────────────────
def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage(
        workshopText('script.a38d0e6adac0', "Proje açık değil"),
        workshopText('script.8f09552c4053', "Bu betik bir QuPath projesinin açık olmasını gerektirir.\n" +
        "Önce bir proje açın, sonra tekrar deneyin.")
    )
    return
}

// Bilinmeyen -a kopyalar=… değeri HER durumda durdurur (kopya olmasa da; fail closed).
def copiesArg = headlessArg('kopyalar')
if (copiesArg != null && copiesArg != 'hepsi' && copiesArg != 'enyeni') {
    println String.format(java.util.Locale.US, workshopText('script.96d140502a36', 'DURDU: bilinmeyen kopyalar değeri "%s". Geçerli değerler: hepsi | enyeni. Hiçbir şey dışa aktarılmadı.'), copiesArg)
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
        workshopText('script.30a0fc6d7165', "Çıktı türü seçilmedi"),
        workshopText('script.a9e9f6957668', "En az bir çıktı türü seçin:\n" +
        "  • Tespit ölçümleri (TSV/CSV)\n" +
        "  • Anotasyon ölçümleri (TSV/CSV)\n" +
        "  • Anotasyon geometrisi (GeoJSON)")
    )
    return
}

def projectMode = (opt.scope == "project")

if (!projectMode && currentImageData == null) {
    Dialogs.showErrorMessage(
        workshopText('script.b4c901dc184b', "Görüntü açık değil"),
        workshopText('script.00ed49d99b56', "'Sadece bu görüntü' modu için bir lamın açık olması gerekir.")
    )
    return
}

if (!projectMode && currentEntry == null) {
    // Açık görüntü var ama projeye dahil değil — MeasurementExporter ve
    // entry.saveImageData çağrılarımız bir ProjectImageEntry gerektirir.
    Dialogs.showErrorMessage(
        workshopText('script.28bed412765d', "Lam projeye eklenmemiş"),
        workshopText('script.ec7042f2fcfe', "Açık olan lam projenin parçası değil; dışa aktarılamaz.\n\n" +
        "Çözüm: Project panelinden bu lamı projeye ekleyin\n" +
        "(sol üstte + ikonu) ve betiği tekrar çalıştırın.")
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 2.4) Kopya kontrolü — SORU, hiçbir dosya yazılmadan ve açık lam kaydedilmeden ÖNCE.
// Kopya grubu yoksa hiçbir şey sorulmaz, çıktı eskisiyle aynıdır.
// ──────────────────────────────────────────────────────────────
def copiesScanResults = [:]          // girdi kimliği -> [name, scan]
def copiesKeptIds = new HashSet()     // her lamda dışarıda BIRAKILMAYAN anotasyonların kimlikleri
def copiesScanErrors = []
def copiesTargets = projectMode ? project.getImageList() : [currentEntry]
copiesTargets.each { entry ->
    String key = copiesEntryKey(entry)
    def toClose = null
    try {
        def hier
        if (currentImageData != null && currentEntry != null && key == copiesEntryKey(currentEntry)) {
            hier = currentImageData.getHierarchy()      // açık lam: bellekteki hali (birazdan kaydedilir)
        } else {
            toClose = entry.readImageData()
            hier = toClose.getHierarchy()
        }
        def sc = copiesScanHierarchy(hier)
        hier.getAnnotationObjects().each { a -> if (sc == null || !copiesAncestorHit(a, sc.ids)) copiesKeptIds << a.getID() }
        if (sc != null) copiesScanResults[key] = [name: entry.getImageName(), scan: sc]
    } catch (Throwable t) {
        copiesScanErrors << String.format(java.util.Locale.US, workshopText('script.340a03244053', '%s → kopya taraması: %s'), entry.getImageName(), (t.getMessage() ?: t.getClass().getSimpleName()))
    } finally {
        try { toClose?.close() } catch (Throwable ignore) {}
    }
}
def copiesChoice = null     // null: kopya grubu yok | 'all' | 'newest'
if (!copiesScanResults.isEmpty()) {
    int copiesImgCount = copiesScanResults.size()
    int copiesGrpCount = copiesScanResults.values().sum { it.scan.groups.size() } as int
    def copiesExamples = copiesReportLines(copiesScanResults, false).take(5)
    if (isHeadless) {
        if (copiesArg == null) {
            println String.format(java.util.Locale.US, workshopText('script.49a1c50ae61c', 'DURDU: %,d görüntüde aynı sonucun birden çok kopyası var (ör. %s). Hiçbir şey dışa aktarılmadı. Tüm kopyalar için -a kopyalar=hepsi, yalnız en yeni kopyalar için -a kopyalar=enyeni ile yeniden çalıştırın.'), copiesImgCount, copiesExamples.join('; '))
            return
        }
        copiesChoice = (copiesArg == 'enyeni') ? 'newest' : 'all'
    } else {
        String copiesOptAll = workshopText('script.d3e7f99b619b', 'Tüm kopyalar — hepsi dışa aktarılır (varsayılan)')
        String copiesOptNewest = workshopText('script.7e68f1304c36', 'Yalnız en yenisi — eski kopyalar ve içlerindeki nesneler dışa aktarılmaz')
        def copiesPick = copiesAskChoice(workshopText('script.2a83adf213c8', 'Aynı sonucun birden çok kopyası var'),
            String.format(java.util.Locale.US, workshopText('script.a6d0de40a16d', '%,d görüntüde toplam %,d kopya grubu var: aynı sonuç lamda "ad", "ad (2)", "ad (3)" biçiminde yan yana duruyor. Kopyalar farklı sayılar içerebilir; hangisinin güncel olduğuna dışa aktarma karar veremez.\n\nÖrnekler (lam · ad: kopya numaraları; 1 = numarasız ad):\n%s\n\n"Tüm kopyalar": her şey dışa aktarılır. Veri dosyalarının biçimi değişmez; kopyalar sonuç penceresinde ve dışa aktarma klasöründeki ".kopyalar.txt" dosyasında listelenir.\n\n"Yalnız en yenisi": her grupta yalnız en yüksek numaralı kopya kalır; eski kopyalar ve içlerindeki nesneler (anotasyonlar, tespitler) dışa aktarılmaz. Lamdaki hiçbir şey silinmez.\n\nİptal: hiçbir şey dışa aktarılmaz.\n\nHangisi dışa aktarılsın?'), copiesImgCount, copiesGrpCount, copiesExamples.collect { "  • " + it }.join("\n")),
            [copiesOptAll, copiesOptNewest], copiesOptAll)
        if (copiesPick == null) {
            println workshopText('script.977448c268af', 'İptal: hiçbir şey dışa aktarılmadı.')
            return
        }
        copiesChoice = (copiesPick == copiesOptNewest) ? 'newest' : 'all'
    }
}
def copiesExcludedByKey = [:]
def copiesUnionIds = new HashSet()
def copiesLeftAnn = 0
def copiesLeftDet = 0
if (copiesChoice == 'newest') {
    copiesScanResults.each { k, v ->
        copiesExcludedByKey[k] = v.scan.ids
        copiesUnionIds.addAll(v.scan.ids)
        copiesLeftAnn += v.scan.leftAnn
        copiesLeftDet += v.scan.leftDet
    }
}
// Birleşik (_all_) dosyalar tek bir süzgeç kullanır. Süzgeç nesne kimliğine bakar; başka bir lamda
// dışarıda bırakılmayan bir anotasyon aynı kimliği taşıyorsa (anotasyon başka lama aynı kimlikle
// içe aktarılmış olabilir) süzgeç yanlış nesneyi atabilir — o durumda birleşik dosya YAZILMAZ.
def copiesCombinedSkip = (copiesChoice == 'newest') && !copiesUnionIds.intersect(copiesKeptIds).isEmpty()
def copiesPredicate = { Set ids -> ({ o -> !copiesAncestorHit(o, ids) } as java.util.function.Predicate) }
def copiesExcludedFor = { entry -> (copiesChoice == 'newest') ? copiesExcludedByKey[copiesEntryKey(entry)] : null }

// ──────────────────────────────────────────────────────────────
// 2.5) Açık lamı diske kaydet — kritik
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
        println "✓ Açık lam diske kaydedildi: ${currentEntry.getImageName()}"
    } catch (Throwable t) {
        currentSaveError = "${t.getClass().getSimpleName()}: ${t.getMessage()}"
        println "⚠ Açık lam kaydedilemedi: ${currentSaveError}"
    }
} else if (projectMode) {
    println "(Açık görüntü yok — projedeki tüm lamlar son kayıtlı halinden okunur.)"
}

// ──────────────────────────────────────────────────────────────
// 3) Seçimlerden çıktı parametrelerini türet + çıktı klasörünü hazırla
// ──────────────────────────────────────────────────────────────
// MeasurementExporter.separator(...) YALNIZ String kabul eder (0.6.0 + 0.7.0'da doğrulandı). Eskiden
// burada `char` üretiliyordu → her TSV/CSV dışa aktarımı MissingMethodException ile düşüyor,
// yalnız GeoJSON yazılıyordu. Ad geriye dönük uyum için korunur; değer tek karakterlik String'dir.
def exportSeparatorChar = ((opt.separator ?: "\t") as String).substring(0, 1)
def ext = ((opt.separator ?: "\t") == "\t") ? "tsv" : "csv"
def pfx = (opt.prefix) ? (opt.prefix + "_") : ""
def colList = (opt.columns ?: []) as List
def useSubset = (opt.columnsMode == "subset") && !colList.isEmpty()
def subsetCols = colList as String[]

def stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))

def chosenLoc = ((opt.location ?: "") as String).trim()
def base
String locFallbackNote = null
if (chosenLoc) {
    def f = new File(chosenLoc)
    if (f.isDirectory()) {
        base = f
    } else if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
        base = f   // mkdirs ile oluşturulacak
    } else {
        base = defaultBase
        println "⚠ Seçilen kayıt yeri bulunamadı (${chosenLoc}); proje varsayılanı kullanılıyor."
        locFallbackNote = String.format(java.util.Locale.US, workshopText('script.aab73e5d6aed', '⚠ Seçtiğiniz kayıt yeri bulunamadı (%s); dosyalar proje varsayılan klasörüne yazıldı: %s\n  Başka bir klasör istiyorsanız aracı yeniden çalıştırıp var olan bir klasör seçin.'), chosenLoc, defaultBase.getAbsolutePath())
    }
} else {
    base = defaultBase
}
def outDir = (opt.dateSubfolder as boolean) ? new File(base, stamp) : base
outDir.mkdirs()

println "─────────────────────────────────────"
println "Veri dışa aktarma"
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
            if (copiesExcludedFor(entry) != null) me = me.filter(copiesPredicate(copiesExcludedFor(entry)))
            me.exportMeasurements(detFile)
            if (detFile.length() > 0) filesWritten << detFile.getName()
        } catch (Throwable t) {
            errors << String.format(java.util.Locale.US, workshopText('script.8e2b390995c2', '%s → tespitler: %s'), entry.getImageName(), t.getMessage())
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
            if (copiesExcludedFor(entry) != null) me = me.filter(copiesPredicate(copiesExcludedFor(entry)))
            me.exportMeasurements(annFile)
            if (annFile.length() > 0) filesWritten << annFile.getName()
        } catch (Throwable t) {
            errors << String.format(java.util.Locale.US, workshopText('script.7cea736480b6', '%s → anotasyonlar: %s'), entry.getImageName(), t.getMessage())
        }
    }

    // Anotasyon GeoJSON + sayım
    try {
        def annos = imageDataForGeo?.getHierarchy()?.getAnnotationObjects() ?: []
        def exIds = copiesExcludedFor(entry)
        if (exIds != null) annos = annos.findAll { !copiesAncestorHit(it, exIds) }
        if ((opt.geo as boolean) && !annos.isEmpty()) {
            def geoFile = new File(outDir, "${pfx}${slug}__annotations.geojson")
            geoFile.write(gson.toJson(annos), "UTF-8")
            filesWritten << geoFile.getName()
        }
        annotationsTotal += annos.size()
        def dets = imageDataForGeo?.getHierarchy()?.getDetectionObjects() ?: []
        if (exIds != null) dets = dets.findAll { !copiesAncestorHit(it, exIds) }
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
    println "  ${imageList.size()} lam işlenecek..."

    // Per-image dosyalar
    imageList.each { entry ->
        try {
            // ImageData 0.6'da AutoCloseable — proje döngüsünde her lamı kapatarak
            // sunucu/karo-önbelleği tanıtıcılarının birikmesini önle (bkz. sib. kohort betikleri).
            entry.readImageData().withCloseable { imgData ->
                exportEntry(entry, imgData)
                imagesProcessed++
            }
        } catch (Throwable t) {
            errors << "${entry.getImageName()} → readImageData: ${t.getMessage()}"
        }
    }

    // Birleştirilmiş dosyalar — tek dosyada tüm projenin tespitler / anotasyonlar
    if ((opt.det as boolean) && !copiesCombinedSkip) {
        try {
            def allDet = new File(outDir, "${pfx}_all_detections.${ext}")
            def me = new MeasurementExporter()
                .imageList(imageList)
                .separator(exportSeparatorChar)
                .exportType(PathDetectionObject.class)
            if (useSubset) me = me.includeOnlyColumns(subsetCols)
            if (!copiesUnionIds.isEmpty() && copiesChoice == 'newest') me = me.filter(copiesPredicate(copiesUnionIds))
            me.exportMeasurements(allDet)
            if (allDet.length() > 0) filesWritten << allDet.getName()
        } catch (Throwable t) {
            errors << "_all_detections: ${t.getMessage()}"
        }
    }

    if ((opt.ann as boolean) && !copiesCombinedSkip) {
        try {
            def allAnn = new File(outDir, "${pfx}_all_annotations.${ext}")
            def me = new MeasurementExporter()
                .imageList(imageList)
                .separator(exportSeparatorChar)
                .exportType(PathAnnotationObject.class)
            if (useSubset) me = me.includeOnlyColumns(subsetCols)
            if (!copiesUnionIds.isEmpty() && copiesChoice == 'newest') me = me.filter(copiesPredicate(copiesUnionIds))
            me.exportMeasurements(allAnn)
            if (allAnn.length() > 0) filesWritten << allAnn.getName()
        } catch (Throwable t) {
            errors << "_all_annotations: ${t.getMessage()}"
        }
    }

} else {
    // Sadece açık lam
    exportEntry(currentEntry, currentImageData)
    imagesProcessed = 1
}

// Kopya grubu varsa: seçimi ve etkilenen lamları yan dosyaya yaz (veri sütunlarına HİÇBİR şey eklenmez).
def copiesSidecarName = null
if (copiesChoice != null) {
    try {
        copiesSidecarName = pfx + outDir.getName() + ".kopyalar.txt"
        def sideFile = new File(outDir, copiesSidecarName)
        def sideLines = [String.format(java.util.Locale.US, workshopText('script.e5d7e6223d81', 'Kopya grupları — seçim: %s'),
            (copiesChoice == 'newest') ? workshopText('script.3b5c8754d6fc', 'yalnız en yenisi') : workshopText('script.b353e57316fe', 'tüm kopyalar')), ""]
        sideLines.addAll(copiesReportLines(copiesScanResults, copiesChoice == 'newest'))
        sideFile.write(sideLines.join("\n") + "\n", "UTF-8")
        filesWritten << sideFile.getName()
    } catch (Throwable t) {
        copiesSidecarName = null
        errors << "kopyalar.txt: ${t.getMessage()}"
    }
    errors.addAll(copiesScanErrors)
}

def elapsed = (System.currentTimeMillis() - t0) / 1000.0

// ──────────────────────────────────────────────────────────────
// 6) Sonucu sun
// ──────────────────────────────────────────────────────────────
def fileList = filesWritten.size() <= 20
    ? filesWritten.collect { "  • ${it}" }.join("\n")
    : (filesWritten.take(20).collect { "  • ${it}" }.join("\n") +
       String.format(java.util.Locale.US, workshopText('script.43c20a2342d0', '\n  ... ve %s dosya daha'), filesWritten.size() - 20))
if (filesWritten.isEmpty()) fileList = workshopText('script.9dbe4123ceaf', '  (dosya yazılmadı)')

def errSection = errors.isEmpty()
    ? ""
    : workshopText('script.c2b270d6f651', '\n\n⚠️  Bazı dosyalar yazılamadı:\n') + errors.take(10).collect { "  • ${it}" }.join("\n") +
      (errors.size() > 10 ? String.format(java.util.Locale.US, workshopText('script.1b822ccc5e47', '\n  ... ve %s hata daha'), errors.size() - 10) : "")

def saveStatusLine
if (currentSaved) {
    saveStatusLine = workshopText('script.1b0277cb2ab8', '✓ Açık lam diske kaydedildi (anotasyon/tespitler dahildir)')
} else if (currentSaveError != null) {
    saveStatusLine = workshopText('script.70e4605dd9aa', '⚠ Açık lam KAYDEDİLEMEDİ — son değişiklikler dışa aktarımda görünmeyebilir.\n') +
                     workshopText('script.27fb888c505d', '  Detay: ') + currentSaveError + "\n" +
                     workshopText('script.4cf55b646e9e', '  Çözüm: QuPath ana penceresinde [Ctrl+S] basın, betiği tekrar çalıştırın.')
} else {
    saveStatusLine = workshopText('script.b17b5deeb81e', '(Açık lam yok — projedeki tüm lamlar son kayıtlı halinden okundu.)')
}
if (locFallbackNote != null) saveStatusLine = saveStatusLine + "\n" + locFallbackNote

// Eğer dışa aktarım toplamları sıfırsa — çoğunlukla kaydedilmemiş slayt göstergesi
def emptyHint = ""
if (annotationsTotal == 0 && detectionsTotal == 0 && currentSaveError == null) {
    emptyHint = workshopText('script.90d535ea55eb', '\n\n💡 Çıktıda 0 anotasyon / 0 tespit görünüyor. Olası nedenler:\n') +
                workshopText('script.57824e6c6c66', '  1. Bu lamda gerçekten hiç anotasyon / tespit yoktu — önce ilgili modüllerden birini çalıştırın.\n') +
                workshopText('script.4ef61f56b391', '  2. Lam betikten ÖNCE QuPath dışında değiştirildi ve .qpdata kaydedilmedi.\n') +
                workshopText('script.18704ecc384a', '  3. ROI seçilip içinde tespit üretildi ama anotasyon kaydedilmedi.\n') +
                workshopText('script.7a6d7f406246', 'QuPath\'te [Ctrl+S] ile lamı kaydedin ve betiği tekrar çalıştırın.')
}

def fmtName = (ext == "tsv") ? "TSV (TAB)" : workshopText('script.4586ff54d18d', 'CSV (virgül)')
def typesSummary = [
    (opt.det as boolean) ? String.format(java.util.Locale.US, workshopText('script.2f7f284bf1e6', 'tespit %s'), ext.toUpperCase(java.util.Locale.ROOT)) : null,
    (opt.ann as boolean) ? String.format(java.util.Locale.US, workshopText('script.d1dcbb719213', 'anotasyon %s'), ext.toUpperCase(java.util.Locale.ROOT)) : null,
    (opt.geo as boolean) ? workshopText('script.fbf8a880addf', 'anotasyon GeoJSON') : null
].findAll { it != null }.join(", ")
def colsSummary = useSubset ? String.format(java.util.Locale.US, workshopText('script.c5cc8ab9b998', 'seçili %s sütun'), subsetCols.length) : workshopText('script.94d1e9d12a29', 'tüm sütunlar')
def elapsedStr = String.format(java.util.Locale.US, "%.1f", elapsed)

def copiesBlock = ""
if (copiesChoice != null) {
    def lines = copiesReportLines(copiesScanResults, copiesChoice == 'newest')
    def listed = lines.take(20).collect { "    • " + it }.join("\n") + (lines.size() > 20 ? "\n    …" : "")
    copiesBlock = "🗂 " + ((copiesChoice == 'newest')
        ? String.format(java.util.Locale.US, workshopText('script.120555c8d702', 'Kopya grupları — seçim: yalnız en yenisi\n  %,d görüntüde %,d grup. Dışarıda bırakılan: %,d anotasyon ve %,d tespit (eski kopyalar ve içlerindekiler).'), copiesScanResults.size(), lines.size(), copiesLeftAnn, copiesLeftDet)
        : String.format(java.util.Locale.US, workshopText('script.8f8300c4bcaf', 'Kopya grupları — seçim: tüm kopyalar\n  %,d görüntüde %,d grup. Kopyalar veri dosyalarında işaretli DEĞİLDİR; hangi sonucun güncel olduğunu aşağıdaki listeden kontrol edin.'), copiesScanResults.size(), lines.size())) + "\n" +
        (copiesCombinedSkip ? "  " + workshopText('script.64fb4051b58b', 'Birleşik (_all_) dosyalar yazılmadı: bir lamdaki eski kopyanın kimliği başka bir lamdaki bir anotasyonla aynı olduğundan tek süzgeç doğru nesneleri ayıramaz. Lam başına dosyalar yazıldı.') + "\n" : "") +
        (copiesSidecarName != null ? "  " + String.format(java.util.Locale.US, workshopText('script.34c095b0b6b2', 'Kopya listesi dosyası: %s'), copiesSidecarName) + "\n" : "") +
        listed + "\n\n"
}

def resultBody =
    String.format(java.util.Locale.US, workshopText('script.3d65600467c6', 'Kapsam: %s\n'), projectMode ? workshopText('script.3080d2dc7199', 'Tüm proje') : workshopText('script.70fdd47a1816', 'Sadece bu görüntü')) +
    String.format(java.util.Locale.US, workshopText('script.af80aa273645', 'Çıktı stili: %s · %s\n'), fmtName, colsSummary) +
    String.format(java.util.Locale.US, workshopText('script.3608c8bc5a4b', 'İçerik: %s\n'), typesSummary) +
    String.format(java.util.Locale.US, workshopText('script.ce4dd33eb405', 'Önek: %s    Tarihli alt klasör: %s\n'), opt.prefix ? opt.prefix : workshopText('script.715392eca0f8', '(yok)'), (opt.dateSubfolder as boolean) ? workshopText('script.23f31d553dea', 'evet') : workshopText('script.5935eed908c4', 'hayır')) +
    String.format(java.util.Locale.US, workshopText('script.110db99d0987', 'Çıktı klasörü:\n  %s\n\n'), outDir.getAbsolutePath()) +
    workshopText('script.5c7edb12f789', '💾 Kaydetme durumu\n') +
    "──────────────────\n" +
    "  ${saveStatusLine}\n\n" +
    workshopText('script.10e7d671084f', '📊 Özet\n') +
    "──────\n" +
    String.format(java.util.Locale.US, workshopText('script.ecba696b4c65', '  Lam sayısı         : %s\n'), imagesProcessed) +
    String.format(java.util.Locale.US, workshopText('script.718e7b910302', '  Toplam anotasyon     : %s\n'), annotationsTotal) +
    String.format(java.util.Locale.US, workshopText('script.d8847928dc87', '  Toplam tespit        : %s\n'), detectionsTotal) +
    String.format(java.util.Locale.US, workshopText('script.854f1098a071', '  Yazılan dosya sayısı : %s\n'), filesWritten.size()) +
    String.format(java.util.Locale.US, workshopText('script.18213cc654a0', '  Süre                 : %s sn\n\n'), elapsedStr) +
    copiesBlock +
    String.format(java.util.Locale.US, workshopText('script.9dc079d18a26', '📁 Yazılan dosyalar:\n%s%s%s\n\n'), fileList, errSection, emptyHint) +
    workshopText('script.bccb206f399a', '📝 Sonraki adım:\n') +
    String.format(java.util.Locale.US, workshopText('script.e13769c47ff0', '  • R / Python / Excel ile yukarıdaki klasördeki %s dosyalarını okuyun.\n'), fmtName) +
    workshopText('script.5664d0fbee4d', '  • GeoJSON dosyaları geopandas / sf paketleriyle açılır (anotasyon\n') +
    workshopText('script.89a9dbc6cc1e', '    geometrisini başka araçlara taşımak için).\n') +
    workshopText('script.65f4e999505f', '  • Veri dışa aktarma modülünün web sayfasında format karşılaştırması ve örnek R/Python\n') +
    workshopText('script.88b1baedc81c', '    kodları var.\n\n') +
    workshopText('script.cf6099f0a005', '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')

showResultWindow(workshopText('script.5de4e7997199', 'Veri dışa aktarma — Tamamlandı 📤'), resultBody)

println "─────────────────────────────────────"
println String.format(java.util.Locale.US, "Tamamlandı: %d lam, %d dosya, %.1f sn",
    imagesProcessed, filesWritten.size(), elapsed)
println "Çıktı: ${outDir.getAbsolutePath()}"
println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
if (!errors.isEmpty()) {
    println "⚠️  ${errors.size()} hata (detay sonuç penceresinde)"
}
println "─────────────────────────────────────"

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
