/**
 * Yardımcı - OME-Zarr dışa aktarma
 * --------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Açık slaytı — ya da seçili bir anotasyonun bölgesini — QuPath'in YERLEŞİK
 * OME-Zarr (OME-NGFF v0.4) yazıcısıyla parçalı (chunked), çok düzeyli (piramidal),
 * cloud-native bir `.ome.zarr` deposuna dışa aktarır. Çok büyük WSI ölçeğinde
 * düz PNG karo yığını yerine tercih edilen biçimdir (bkz. Ekler → OME-Zarr / OME-NGFF
 * ve Ek X — Karo dışa aktarma).
 *
 * KAPSAM (diyalogdan seçilir):
 *   • Tüm görüntü          — açık slaytın tamamı (büyük slaytlarda dakikalar/GB).
 *   • Seçili anotasyon(lar) — yalnızca seçtiğiniz ROI'lerin sınırlayıcı kutusu.
 *
 * SEÇENEKLER (diyalogda açıklamalı):
 *   Kapsam · Parça (chunk/tile) boyutu (px) · Piramit (çok düzeyli) oluştur.
 *   Sıkıştırma: QuPath varsayılanı (blosc). Taban çözünürlük yeniden örneklenmez
 *   (downsample 1.0 her zaman yazılır); piramit seçilirse 2× katları eklenir.
 *
 * KULLANIM:
 *   1. Bir slayt açın. "Seçili" kapsam için bir anotasyon çizip seçili tutun.
 *   2. [Extensions → Atölye → Yardımcılar → OME-Zarr dışa aktarma]
 *   3. Formdan seçenekleri ayarlayıp "Dışa aktar".
 *   4. Çıkan `.ome.zarr` deposunu QuPath'e geri sürükleyebilir ya da Python'da
 *      inceleyebilirsiniz (handson/python/omezarr/inspect_omezarr.py).
 *
 * ÇIKTI:
 *   <proje-klasörü>/exports/omezarr/<slayt-slug>__<tarih>.ome.zarr/   (klasör deposu)
 *   (Proje yoksa çıktı kullanıcı ana klasörünün altına yazılır.)
 *
 * NE YAPMAZ: Klinik yorum üretmez; yalnızca biçim dönüştürür. Tüm projeyi
 *   (çoklu slayt) toplu yazmaz — yalnızca açık görüntüyü/seçili bölgeyi.
 *
 * API / BELGE: qupath.lib.images.writers.ome.zarr.OMEZarrWriter (QuPath 0.6.0+).
 *   Resmi belgeler:
 *     https://qupath.readthedocs.io/en/stable/docs/advanced/exporting_images.html
 *   Biçim spesifikasyonu (OME-NGFF): https://ngff.openmicroscopy.org
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.images.writers.ome.zarr.OMEZarrWriter
import qupath.lib.regions.ImageRegion

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
// Taban genişlik/yükseklikten piramit downsample listesi (1, 2, 4, ...) üretir.
// Piramit kapalıysa yalnızca taban düzey [1.0] yazılır.
// ──────────────────────────────────────────────────────────────
def computeDownsamples = { long w, long h, int chunk, boolean pyramid ->
    def list = [1.0d]
    if (pyramid) {
        double d = 1.0d
        while (list.size() < 8) {
            d *= 2.0d
            double maxDim = Math.max(w / d, h / d)
            list << d
            if (maxDim <= chunk) break
        }
    }
    return list as double[]
}

// ──────────────────────────────────────────────────────────────
// Seçenek formu — tek pencere, açıklamalı satırlar, latch ile Map döner
// ──────────────────────────────────────────────────────────────
def showOptionsForm = { int selCount ->
    def defaults = [scope: 'whole', chunk: 512, pyramid: true]
    if (isHeadless) {
        println "=== OME-Zarr dışa aktarma (headless: varsayılan seçenekler) ==="
        return defaults
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def result = new java.util.concurrent.atomic.AtomicReference<Map>(null)

    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle("OME-Zarr dışa aktarma — seçenekler")
            stage.setAlwaysOnTop(true)

            def scopeBox = new javafx.scene.control.ChoiceBox<String>()
            scopeBox.getItems().addAll("Tüm görüntü",
                                       "Seçili anotasyon(lar) (${selCount})".toString())
            scopeBox.setValue("Tüm görüntü")

            def chunkField = new javafx.scene.control.TextField("512")
            chunkField.setPrefColumnCount(8)

            def pyramidCheck = new javafx.scene.control.CheckBox("Piramit (çok düzeyli) oluştur")
            pyramidCheck.setSelected(true)

            def help = { String s ->
                def l = new javafx.scene.control.Label(s)
                l.setWrapText(true)
                l.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;")
                l.setMaxWidth(340)
                return l
            }

            def grid = new javafx.scene.layout.GridPane()
            grid.setHgap(12); grid.setVgap(6)
            grid.setPadding(new javafx.geometry.Insets(12))
            int r = 0
            grid.add(new javafx.scene.control.Label("Kapsam:"), 0, r);  grid.add(scopeBox, 1, r); grid.add(help("Tüm görüntü: açık slaytın tamamı (büyük slaytlarda yavaş/büyük). Seçili anotasyon(lar): seçtiğiniz ROI'lerin sınırlayıcı kutusu."), 2, r); r++
            grid.add(new javafx.scene.control.Label("Parça boyutu (px):"), 0, r); grid.add(chunkField, 1, r); grid.add(help("OME-Zarr parça (chunk/tile) kenarı. Tipik: 256–1024. Varsayılan 512."), 2, r); r++
            grid.add(new javafx.scene.control.Label("Piramit:"), 0, r);  grid.add(pyramidCheck, 1, r); grid.add(help("İşaretliyse taban çözünürlük + 2× katları (çok düzeyli) yazılır — yakınlaş/uzaklaş için gereklidir. Kapalıysa yalnızca tam çözünürlük."), 2, r); r++
            grid.add(new javafx.scene.control.Label("Sıkıştırma:"), 0, r); grid.add(new javafx.scene.control.Label("blosc (QuPath varsayılanı)"), 1, r); grid.add(help("Parçalar varsayılan blosc kodlayıcısıyla sıkıştırılır; ayrı ayar gerekmez."), 2, r); r++

            def exportBtn = new javafx.scene.control.Button("Dışa aktar")
            exportBtn.setDefaultButton(true)
            exportBtn.setOnAction({
                try {
                    int ck = Integer.parseInt(chunkField.getText().trim())
                    if (ck <= 0)     { Dialogs.showErrorMessage("Geçersiz", "Parça boyutu pozitif olmalı."); return }
                    if (ck > 8192)   { Dialogs.showErrorMessage("Geçersiz", "Parça boyutu çok büyük (≤ 8192)."); return }
                    def scope = scopeBox.getValue().startsWith("Seçili") ? 'selected' : 'whole'
                    result.set([scope: scope, chunk: ck, pyramid: pyramidCheck.isSelected()])
                    stage.close()
                } catch (NumberFormatException nfe) {
                    Dialogs.showErrorMessage("Sayı formatı", "Parça boyutu tam sayı olmalı (ör. 512).")
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
            stage.setScene(new javafx.scene.Scene(root, 760, 280))
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
            def __footer = new javafx.scene.control.Label("QuPath Atölye Scriptleri · araştırma/eğitim amaçlı")
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons)
            __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)
            stage.setScene(new javafx.scene.Scene(root, 760, 560))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Bir klasör deposunun toplam boyutunu (bayt) yürüyerek hesaplar
// ──────────────────────────────────────────────────────────────
def dirSize
dirSize = { File dir ->
    long total = 0
    if (dir == null || !dir.exists()) return total
    def kids = dir.listFiles()
    if (kids == null) return total
    for (File f : kids) {
        total += f.isDirectory() ? dirSize(f) : f.length()
    }
    return total
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
def selectedAnns = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI() != null }

// ──────────────────────────────────────────────────────────────
// 2) Seçenek formu
// ──────────────────────────────────────────────────────────────
def opt = showOptionsForm(selectedAnns.size())
if (opt == null) { println "OME-Zarr dışa aktarımı iptal edildi."; return }

boolean selectedScope = (opt.scope == 'selected')
int chunk = opt.chunk
boolean pyramid = opt.pyramid

// ──────────────────────────────────────────────────────────────
// 3) Kapsam → bölge (region) çöz
// ──────────────────────────────────────────────────────────────
def region = null
long expW = server.getWidth()
long expH = server.getHeight()
if (selectedScope) {
    if (selectedAnns.isEmpty()) {
        Dialogs.showErrorMessage(
            "Seçili anotasyon yok",
            "'Seçili anotasyon(lar)' kapsamı için önce bir veya birden çok anotasyon seçin\n" +
            "(Annotations panelinden ya da slayt üzerinde tıklayarak), sonra tekrar çalıştırın."
        )
        return
    }
    // Seçili anotasyon(lar)ın birleşik sınırlayıcı kutusu
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE
    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE
    def first = selectedAnns[0].getROI()
    int z = first.getZ(), t = first.getT()
    selectedAnns.each { ann ->
        def roi = ann.getROI()
        minX = Math.min(minX, roi.getBoundsX())
        minY = Math.min(minY, roi.getBoundsY())
        maxX = Math.max(maxX, roi.getBoundsX() + roi.getBoundsWidth())
        maxY = Math.max(maxY, roi.getBoundsY() + roi.getBoundsHeight())
    }
    int rx = (int) Math.floor(minX)
    int ry = (int) Math.floor(minY)
    int rw = (int) Math.ceil(maxX - minX)
    int rh = (int) Math.ceil(maxY - minY)
    rx = Math.max(0, rx); ry = Math.max(0, ry)
    rw = Math.min(rw, server.getWidth() - rx)
    rh = Math.min(rh, server.getHeight() - ry)
    if (rw <= 0 || rh <= 0) {
        Dialogs.showErrorMessage("Geçersiz bölge", "Seçili anotasyonun sınırlayıcı kutusu görüntü dışında.")
        return
    }
    region = ImageRegion.createInstance(rx, ry, rw, rh, z, t)
    expW = rw; expH = rh
}

// ──────────────────────────────────────────────────────────────
// 4) Büyük "tüm görüntü" yazımı için onay
// ──────────────────────────────────────────────────────────────
long pixels = expW * expH
if (!isHeadless && !selectedScope && pixels > 100_000_000L) {
    def ok = Dialogs.showConfirmDialog(
        "Büyük dışa aktarım",
        String.format(java.util.Locale.US,
            "Bu, tüm slaytı (%,d × %,d px) OME-Zarr'a yazacak.\n" +
            "Büyük slaytlarda dakikalar sürebilir ve diskte GB'larca yer kaplayabilir.\n\n" +
            "Devam edilsin mi? (Yalnızca bir bölge istiyorsanız iptal edip bir anotasyon\n" +
            "seçin ve 'Seçili anotasyon(lar)' kapsamını kullanın.)", expW, expH)
    )
    if (!ok) { println "Kullanıcı büyük-dışa-aktarım onayını reddetti."; return }
}

// ──────────────────────────────────────────────────────────────
// 5) Piramit downsample listesi
// ──────────────────────────────────────────────────────────────
double[] downsamples = computeDownsamples(expW, expH, chunk, pyramid)

// ──────────────────────────────────────────────────────────────
// 6) Çıktı yolu
// ──────────────────────────────────────────────────────────────
def project = QP.getProject()
def baseDir = (project != null && project.getPath() != null)
    ? project.getPath().getParent().toFile()
    : new File(System.getProperty("user.home"), "QuPath")
def stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
def slug = toSlug(server.getMetadata().getName())
def outParent = new File(new File(baseDir, exportFolder), "omezarr")
outParent.mkdirs()
def outStore = new File(outParent, "${slug}__${stamp}.ome.zarr")
def outPath = outStore.getAbsolutePath()

println "─────────────────────────────────────"
println "OME-Zarr dışa aktarma"
println "─────────────────────────────────────"
println "Kapsam   : " + (selectedScope ? "Seçili anotasyon(lar) (${selectedAnns.size()})" : "Tüm görüntü")
println String.format(java.util.Locale.US, "Boyut    : %,d × %,d px · %d kanal", expW, expH, server.nChannels())
println String.format(java.util.Locale.US, "Parça    : %d px · Piramit düzeyi: %d", chunk, downsamples.length)
println "Çıktı    : ${outPath}"

// ──────────────────────────────────────────────────────────────
// 7) Dışa aktar (yerleşik OMEZarrWriter)
// ──────────────────────────────────────────────────────────────
def t0 = System.currentTimeMillis()
def errorMsg = null
try {
    def builder = new OMEZarrWriter.Builder(server)
            .downsamples(downsamples)
            .tileSize(chunk)
    if (region != null) builder = builder.region(region)
    def writer = builder.build(outPath)
    try {
        writer.writeImage()
    } finally {
        writer.close()   // yazımı tamamlar + bekleyen parçaları boşaltır
    }
} catch (Throwable t) {
    errorMsg = "${t.getClass().getSimpleName()}: ${t.getMessage()}"
}
def elapsed = (System.currentTimeMillis() - t0) / 1000.0

// ──────────────────────────────────────────────────────────────
// 8) Sonuç
// ──────────────────────────────────────────────────────────────
if (errorMsg != null) {
    showResultWindow(
        "OME-Zarr dışa aktarma — Hata ⚠️",
        "Dışa aktarım sırasında bir hata oluştu:\n\n" + errorMsg + "\n\n" +
        "Olası nedenler:\n" +
        "  • Disk dolu ya da çıktı klasörüne yazma izni yok.\n" +
        "  • Çok büyük tüm-slayt yazımı kesildi — bir bölge (anotasyon) deneyin.\n" +
        "  • QuPath 0.6.0+ gerekir (OME-Zarr yazıcısı bu sürümde geldi).\n\n" +
        "Detay: View → Show log dialogue.\n\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    )
    println "⚠️  OME-Zarr dışa aktarımı başarısız: ${errorMsg}"
    return
}

long bytes = dirSize(outStore)
double mb = bytes / (1024.0 * 1024.0)
def levelLines = []
downsamples.eachWithIndex { d, i ->
    long lw = (long) Math.ceil(expW / d)
    long lh = (long) Math.ceil(expH / d)
    levelLines << String.format(java.util.Locale.US, "  Düzey %d: downsample %.0f → %,d × %,d px", i, d, lw, lh)
}

showResultWindow(
    "OME-Zarr dışa aktarma — Tamamlandı 🧊",
    String.format(java.util.Locale.US,
        "Kapsam   : %s\n" +
        "Depo (klasör):\n  %s\n\n" +
        "⚙️  Ayarlar\n" +
        "──────────\n" +
        "  Taban boyut : %,d × %,d px\n" +
        "  Kanal       : %d\n" +
        "  Parça (chunk): %d px\n" +
        "  Sıkıştırma  : blosc (varsayılan)\n" +
        "  Biçim       : OME-NGFF v0.4 (.ome.zarr)\n\n" +
        "🗂️  Piramit düzeyleri (%d)\n" +
        "──────────\n" +
        "%s\n\n" +
        "📊 Özet\n" +
        "──────\n" +
        "  Disk boyutu : %.1f MB\n" +
        "  Süre        : %.1f sn\n\n" +
        "📝 Sonraki adım:\n" +
        "  • Depoyu QuPath'e geri sürükleyerek açabilirsiniz (Bio-Formats / OME-Zarr okuyucu).\n" +
        "  • Python'da inceleyin: handson/python/omezarr/inspect_omezarr.py inspect <depo>\n" +
        "  • Yöntem ve ekosistem: Ekler → OME-Zarr / OME-NGFF.\n\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
        (selectedScope ? "Seçili anotasyon(lar) (${selectedAnns.size()})".toString() : "Tüm görüntü"),
        outPath,
        expW, expH,
        server.nChannels(),
        chunk,
        downsamples.length,
        levelLines.join("\n"),
        mb,
        elapsed
    )
)

println String.format(java.util.Locale.US, "Tamamlandı: %d düzey, %.1f MB, %.1f sn", downsamples.length, mb, elapsed)
println "Çıktı: ${outPath}"
println "─────────────────────────────────────"
