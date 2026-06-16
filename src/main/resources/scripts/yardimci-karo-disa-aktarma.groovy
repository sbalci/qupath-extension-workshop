/**
 * Yardımcı - Karo (tile) dışa aktarma
 * ------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Açık slayttan görüntü karoları (tiles) — isteğe bağlı olarak eşleşen etiket
 * maskesi karolarıyla birlikte — dışa aktarır. Derin öğrenme / harici yapay
 * zekâ araçlarına veri hazırlamak, bir bölgeyi paylaşmak veya segmentasyon
 * eğitim seti üretmek içindir.
 *
 * İKİ MOD (diyalogdan seçilir):
 *   • Sadece görüntü            — düz RGB karolar.
 *   • Görüntü + etiket maskesi   — her karo + anotasyon sınıflarından üretilen
 *                                  tek kanallı indeksli maske (segmentasyon).
 *
 * KAPSAM (yalnızca AÇIK görüntü; diyalogdan seçilir):
 *   • Seçili anotasyon(lar)  — yalnızca seçtiğiniz ROI'lerin içindeki karolar.
 *   • Tüm anotasyonlar       — slayttaki her anotasyonun içindeki karolar.
 *   (Her iki kapsam da anotasyonla sınırlıdır — boş slayt döşenmez.)
 *
 * SEÇENEKLER (diyalogda açıklamalı):
 *   Mod · Kapsam · Karo boyutu (px) · Çözünürlük (µm/px) · Örtüşme (px) · Format.
 *   Maske modunda format PNG'ye zorlanır (JPEG tamsayı etiketleri bozar).
 *
 * KULLANIM:
 *   1. Bir slayt açın; en az bir anotasyon çizin (maske modu için sınıflı
 *      anotasyon gerekir — bkz. Modül 6 tümör/stroma).
 *   2. "Seçili" kapsam için anotasyon(lar)ı seçili tutun.
 *   3. [Extensions → Atölye → Yardımcılar → Karo (tile) dışa aktarma]
 *   4. Formdan seçenekleri ayarlayıp "Dışa aktar".
 *
 * ÇIKTI:
 *   <proje-klasörü>/exports/tiles/<slayt-slug>__<tarih>/
 *     ├── (sadece görüntü)        tile_*.png|jpg|tif
 *     └── (görüntü + maske)        images/ ...  labels/ ...  labels.txt
 *   (Proje yoksa çıktı kullanıcı ana klasörünün altına yazılır.)
 *
 * NE YAPMAZ: Tüm projeyi (çoklu slayt) ya da anotasyonsuz tüm slaytı döşemez.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.images.servers.LabeledImageServer
import qupath.lib.images.writers.TileExporter
import qupath.lib.regions.RegionRequest
import qupath.lib.common.ColorTools

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

// ──────────────────────────────────────────────────────────────
// Seçenek formu — tek pencere, açıklamalı satırlar, latch ile Map döner
// ──────────────────────────────────────────────────────────────
def showOptionsForm = { int selCount, int allCount ->
    def defaults = [mode: 'image', scope: 'all', tileSize: 512, umPerPx: 1.0d, overlap: 0, ext: '.png']
    if (isHeadless) {
        println "=== Karo dışa aktarma (headless: varsayılan seçenekler) ==="
        return defaults
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def result = new java.util.concurrent.atomic.AtomicReference<Map>(null)

    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle("Karo (tile) dışa aktarma — seçenekler")
            stage.setAlwaysOnTop(true)

            def modeBox = new javafx.scene.control.ChoiceBox<String>()
            modeBox.getItems().addAll("Sadece görüntü", "Görüntü + etiket maskesi")
            modeBox.setValue("Sadece görüntü")

            def scopeBox = new javafx.scene.control.ChoiceBox<String>()
            scopeBox.getItems().addAll("Tüm anotasyonlar (${allCount})".toString(),
                                       "Seçili anotasyon(lar) (${selCount})".toString())
            scopeBox.setValue("Tüm anotasyonlar (${allCount})".toString())

            def tileField    = new javafx.scene.control.TextField("512")
            def umField      = new javafx.scene.control.TextField("1.0")
            def overlapField = new javafx.scene.control.TextField("0")
            [tileField, umField, overlapField].each { it.setPrefColumnCount(8) }

            def formatBox = new javafx.scene.control.ChoiceBox<String>()
            formatBox.getItems().addAll("PNG (kayıpsız)", "JPG (küçük, kayıplı)", "TIFF (kayıpsız)")
            formatBox.setValue("PNG (kayıpsız)")

            def help = { String s ->
                def l = new javafx.scene.control.Label(s)
                l.setWrapText(true)
                l.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;")
                l.setMaxWidth(320)
                return l
            }

            def grid = new javafx.scene.layout.GridPane()
            grid.setHgap(12); grid.setVgap(6)
            grid.setPadding(new javafx.geometry.Insets(12))
            int r = 0
            grid.add(new javafx.scene.control.Label("Mod:"), 0, r);        grid.add(modeBox, 1, r); grid.add(help("Sadece görüntü: RGB karolar. Görüntü + etiket maskesi: anotasyon sınıflarından maske (segmentasyon eğitimi)."), 2, r); r++
            grid.add(new javafx.scene.control.Label("Kapsam:"), 0, r);     grid.add(scopeBox, 1, r); grid.add(help("Seçili anotasyon(lar): yalnızca seçtiğiniz ROI'ler. Tüm anotasyonlar: slayttaki her anotasyon."), 2, r); r++
            grid.add(new javafx.scene.control.Label("Karo boyutu (px):"), 0, r); grid.add(tileField, 1, r); grid.add(help("Her karonun kenar uzunluğu (piksel). Tipik: 256–512."), 2, r); r++
            grid.add(new javafx.scene.control.Label("Çözünürlük (µm/px):"), 0, r); grid.add(umField, 1, r); grid.add(help("Hedef çözünürlük. Kalibre değilse downsample katsayısı olarak yorumlanır. Küçük değer = daha ayrıntılı/daha çok karo."), 2, r); r++
            grid.add(new javafx.scene.control.Label("Örtüşme (px):"), 0, r); grid.add(overlapField, 1, r); grid.add(help("Komşu karolar arası örtüşme. Sınır hücreleri için >0 (ör. 64)."), 2, r); r++
            grid.add(new javafx.scene.control.Label("Format:"), 0, r);     grid.add(formatBox, 1, r); grid.add(help("PNG: kayıpsız. JPG: küçük ama kayıplı. TIFF: kayıpsız, büyük. Maske modunda PNG'ye zorlanır."), 2, r); r++

            def exportBtn = new javafx.scene.control.Button("Dışa aktar")
            exportBtn.setDefaultButton(true)
            exportBtn.setOnAction({
                try {
                    int ts = Integer.parseInt(tileField.getText().trim())
                    double um = Double.parseDouble(umField.getText().trim().replace(',', '.'))
                    int ov = Integer.parseInt(overlapField.getText().trim())
                    if (ts <= 0)            { Dialogs.showErrorMessage("Geçersiz", "Karo boyutu pozitif olmalı."); return }
                    if (!(um > 0))          { Dialogs.showErrorMessage("Geçersiz", "Çözünürlük pozitif olmalı."); return }
                    if (ov < 0 || ov >= ts) { Dialogs.showErrorMessage("Geçersiz", "Örtüşme 0 ile karo boyutu arasında olmalı."); return }
                    def mode = modeBox.getValue().startsWith("Görüntü +") ? 'mask' : 'image'
                    def scope = scopeBox.getValue().startsWith("Seçili") ? 'selected' : 'all'
                    def fv = formatBox.getValue()
                    def ext = fv.startsWith("JPG") ? '.jpg' : (fv.startsWith("TIFF") ? '.tif' : '.png')
                    result.set([mode: mode, scope: scope, tileSize: ts, umPerPx: um, overlap: ov, ext: ext])
                    stage.close()
                } catch (NumberFormatException nfe) {
                    Dialogs.showErrorMessage("Sayı formatı", "Karo boyutu ve örtüşme tam sayı, çözünürlük ondalık olmalı (ör. 512 / 0.5 / 64).")
                }
            })
            def cancelBtn = new javafx.scene.control.Button("İptal")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({ result.set(null); stage.close() })

            stage.setOnHidden({ latch.countDown() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def buttons = new javafx.scene.layout.HBox(10, spacer, cancelBtn, exportBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(grid)
            root.setBottom(buttons)
            stage.setScene(new javafx.scene.Scene(root, 720, 320))
            stage.show()
        } catch (Throwable t) {
            result.set(null)
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
            root.setBottom(buttons)
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

def selectedAnns = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI() != null }
def allAnns = QP.getAnnotationObjects().findAll { it.getROI() != null }

if (allAnns.isEmpty()) {
    Dialogs.showErrorMessage(
        "Anotasyon yok",
        "Karo dışa aktarımı anotasyonla sınırlıdır; slayttaki hiçbir anotasyon bulunamadı.\n\n" +
        "Önce bir bölge çizin (dikdörtgen/çokgen) ya da maske için sınıflı anotasyon\n" +
        "üretin (Modül 6 — tümör/stroma), sonra tekrar deneyin."
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 2) Seçenek formu
// ──────────────────────────────────────────────────────────────
def opt = showOptionsForm(selectedAnns.size(), allAnns.size())
if (opt == null) { println "Karo dışa aktarımı iptal edildi."; return }

def maskMode = (opt.mode == 'mask')
def selectedScope = (opt.scope == 'selected')
int tileSize = opt.tileSize
double umPerPx = opt.umPerPx
int overlap = opt.overlap
String imageExt = opt.ext

// ──────────────────────────────────────────────────────────────
// 3) Çözünürlük → downsample
// ──────────────────────────────────────────────────────────────
boolean calibrated = (avgPx > 0 && !Double.isNaN(avgPx))
double downsample = calibrated ? (umPerPx / avgPx) : umPerPx
if (!(downsample > 0)) downsample = 1.0d

// ──────────────────────────────────────────────────────────────
// 4) Kapsam nesnelerini çöz + doğrula
// ──────────────────────────────────────────────────────────────
def inScope = selectedScope ? selectedAnns : allAnns
if (selectedScope && inScope.isEmpty()) {
    Dialogs.showErrorMessage(
        "Seçili anotasyon yok",
        "'Seçili anotasyon(lar)' kapsamı için önce bir veya birden çok anotasyon seçin\n" +
        "(Project/Annotations panelinden ya da slayt üzerinde tıklayarak), sonra tekrar çalıştırın."
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 5) Maske modu: sınıf → indeks haritası
// ──────────────────────────────────────────────────────────────
def labelServer = null
def legend = []   // ["0 = arka plan", "1 = Tumor", ...]
if (maskMode) {
    def classes = inScope.collect { it.getPathClass() }.findAll { it != null }.unique().sort { it.toString() }
    if (classes.isEmpty()) {
        Dialogs.showErrorMessage(
            "Sınıflı anotasyon yok",
            "Etiket maskesi üretmek için anotasyonların bir SINIFI (PathClass) olmalı\n" +
            "(ör. Tumor, Stroma). Kapsamdaki anotasyonların hiçbiri sınıflı değil.\n\n" +
            "Çözüm: anotasyonları sınıflandırın (Modül 6 — tümör/stroma) ya da\n" +
            "'Sadece görüntü' modunu kullanın."
        )
        return
    }
    imageExt = '.png'   // maske modunda RGB karolar da kayıpsız olsun (JPEG önerilmez)
    def builder = new LabeledImageServer.Builder(imageData)
        .backgroundLabel(0, ColorTools.WHITE)
        .downsample(downsample)
        .multichannelOutput(false)
    legend << "0 = arka plan"
    classes.eachWithIndex { pc, i ->
        builder.addLabel(pc.toString(), i + 1)
        legend << "${i + 1} = ${pc.toString()}".toString()
    }
    labelServer = builder.build()
}

// ──────────────────────────────────────────────────────────────
// 6) Karo sayısı tahmini (üst sınır) → çok büyükse onay iste
// ──────────────────────────────────────────────────────────────
double stride = Math.max(1.0d, (double) (tileSize - overlap))
long tileEstimate = 0
inScope.each { ann ->
    def b = ann.getROI()
    double wExp = (b.getBoundsWidth() / downsample)
    double hExp = (b.getBoundsHeight() / downsample)
    long nx = (long) Math.max(1, Math.ceil(wExp / stride))
    long ny = (long) Math.max(1, Math.ceil(hExp / stride))
    tileEstimate += nx * ny
}
if (!isHeadless && tileEstimate > 5000) {
    def ok = Dialogs.showConfirmDialog(
        "Çok sayıda karo",
        String.format(java.util.Locale.US,
            "Bu ayarlarla yaklaşık %,d karoya kadar üretilebilir (üst sınır tahmini;\n" +
            "gerçek sayı anotasyon şekline göre daha az olur).\n\n" +
            "Devam edilsin mi? (Karo boyutunu büyütmek ya da çözünürlüğü artırmak\n" +
            "[µm/px değerini büyütmek] karo sayısını azaltır.)", tileEstimate)
    )
    if (!ok) { println "Kullanıcı çok-karo onayını reddetti."; return }
}

// ──────────────────────────────────────────────────────────────
// 7) Çıktı klasörü
// ──────────────────────────────────────────────────────────────
def project = QP.getProject()
def baseDir = (project != null && project.getPath() != null)
    ? project.getPath().getParent().toFile()
    : new File(System.getProperty("user.home"), "QuPath")
def stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
def slug = toSlug(server.getMetadata().getName())
def outDir = new File(new File(new File(baseDir, exportFolder), "tiles"), "${slug}__${stamp}")
outDir.mkdirs()

println "─────────────────────────────────────"
println "Karo (tile) dışa aktarma"
println "─────────────────────────────────────"
println "Mod      : " + (maskMode ? "Görüntü + etiket maskesi" : "Sadece görüntü")
println "Kapsam   : " + (selectedScope ? "Seçili anotasyon(lar)" : "Tüm anotasyonlar") + " (${inScope.size()})"
println String.format(java.util.Locale.US, "Çözünürlük: %.3f µm/px → downsample %.3f%s", umPerPx, downsample, (calibrated ? "" : " (kalibre değil)"))
println "Çıktı    : ${outDir.getAbsolutePath()}"

// ──────────────────────────────────────────────────────────────
// 8) Dışa aktar
// ──────────────────────────────────────────────────────────────
def configure = { exporter ->
    exporter.downsample(downsample)
            .tileSize(tileSize)
            .imageExtension(imageExt)
            .overlap(overlap)
            .annotatedTilesOnly(true)
    if (maskMode) {
        exporter.labeledServer(labelServer)
                .labeledImageExtension('.png')
                .imageSubDir('images')
                .labeledImageSubDir('labels')
    }
    return exporter
}

def t0 = System.currentTimeMillis()
def errors = []
try {
    if (selectedScope) {
        inScope.each { ann ->
            try {
                def req = RegionRequest.createInstance(server.getPath(), downsample, ann.getROI())
                configure(new TileExporter(imageData).region(req)).writeTiles(outDir.getAbsolutePath())
            } catch (Throwable t) {
                errors << "${ann.getROI()} → ${t.getClass().getSimpleName()}: ${t.getMessage()}"
            }
        }
    } else {
        configure(new TileExporter(imageData)).writeTiles(outDir.getAbsolutePath())
    }
} catch (Throwable t) {
    errors << "${t.getClass().getSimpleName()}: ${t.getMessage()}"
}
def elapsed = (System.currentTimeMillis() - t0) / 1000.0

// ──────────────────────────────────────────────────────────────
// 9) Karoları say
// ──────────────────────────────────────────────────────────────
def isImageFile = { File f -> f.isFile() && f.getName().toLowerCase(java.util.Locale.ROOT) ==~ /.*\.(png|jpg|jpeg|tif|tiff)$/ }
def countIn = { File dir -> (dir != null && dir.isDirectory()) ? (dir.listFiles().findAll { isImageFile(it) }).size() : 0 }

int imageTiles, maskTiles
if (maskMode) {
    imageTiles = countIn(new File(outDir, "images"))
    maskTiles  = countIn(new File(outDir, "labels"))
    // labels.txt — sınıf → indeks açıklaması
    try { new File(outDir, "labels.txt").write(legend.join("\n") + "\n", "UTF-8") } catch (Throwable ignored) {}
} else {
    imageTiles = countIn(outDir)
    maskTiles = 0
}

// ──────────────────────────────────────────────────────────────
// 10) Sonuç
// ──────────────────────────────────────────────────────────────
def legendBlock = maskMode ? ("\n🏷️  Sınıf → indeks (labels.txt)\n──────────────────\n" + legend.collect { "  ${it}" }.join("\n") + "\n") : ""
def errBlock = errors.isEmpty() ? "" :
    ("\n⚠️  Bazı bölgeler yazılamadı:\n" + errors.take(8).collect { "  • ${it}" }.join("\n") +
     (errors.size() > 8 ? "\n  ... ve ${errors.size() - 8} hata daha" : "") + "\n")
def emptyHint = (imageTiles == 0 && errors.isEmpty()) ?
    "\n💡 0 karo yazıldı. Olası nedenler:\n" +
    "  • Anotasyonlar çok küçük ya da seçilen çözünürlük/karo boyutu için tek karo bile sığmıyor.\n" +
    "  • Karo boyutunu küçültün ya da çözünürlüğü (µm/px) düşürün ve tekrar deneyin.\n" : ""

showResultWindow(
    "Karo dışa aktarma — Tamamlandı 🧩",
    String.format(java.util.Locale.US,
        "Mod      : %s\n" +
        "Kapsam   : %s (%d anotasyon)\n" +
        "Çıktı klasörü:\n  %s\n\n" +
        "⚙️  Ayarlar\n" +
        "──────────\n" +
        "  Karo boyutu : %d px\n" +
        "  Çözünürlük  : %.3f µm/px → downsample %.3f%s\n" +
        "  Örtüşme     : %d px\n" +
        "  Format      : %s%s\n\n" +
        "📊 Özet\n" +
        "──────\n" +
        "  Görüntü karosu : %,d\n" +
        "  Maske karosu   : %,d\n" +
        "  Süre           : %.1f sn\n" +
        "%s%s%s\n" +
        "📝 Sonraki adım:\n" +
        "  • Karoları derin öğrenme / harici AI araçlarına verin.\n" +
        "  • Yöntem ve ML'e devir notları: Ek — Karo (tile) dışa aktarma ve Modül 9.\n\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
        (maskMode ? "Görüntü + etiket maskesi" : "Sadece görüntü"),
        (selectedScope ? "Seçili anotasyon(lar)" : "Tüm anotasyonlar"), inScope.size(),
        outDir.getAbsolutePath(),
        tileSize,
        umPerPx, downsample, (calibrated ? "" : " (kalibre değil)"),
        overlap,
        (maskMode ? "PNG (maske için zorunlu)" : imageExt.substring(1).toUpperCase(java.util.Locale.ROOT)),
        legendBlock,
        imageTiles, maskTiles, elapsed,
        errBlock, emptyHint, ""
    )
)

println String.format(java.util.Locale.US, "Tamamlandı: %,d görüntü + %,d maske karosu, %.1f sn", imageTiles, maskTiles, elapsed)
if (!errors.isEmpty()) println "⚠️  ${errors.size()} hata (detay sonuç penceresinde)"
println "Çıktı: ${outDir.getAbsolutePath()}"
println "─────────────────────────────────────"
