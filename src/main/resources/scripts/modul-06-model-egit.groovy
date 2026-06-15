/**
 * Modül 6 (Adım 1) - Tek Tıkla Tümör/Stroma Modeli Eğitme
 * --------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * Bu betik, slayta ÇİZDİĞİNİZ Tumor / Stroma anotasyonlarından bir **Random
 * Forest piksel sınıflandırıcı** eğitir ve projeye `tumor-stroma-RF` adıyla
 * kaydeder — `[Classify → Pixel classification → Train pixel classifier]`
 * diyaloğunu açmadan. Kaydedilen modeli sonra **Modül 6 (uygula)** tüm slayda
 * uygular ve TSR üretir. Akış: birkaç bölge çiz → bu betiği çalıştır → model
 * hazır → Modül 6 (uygula).
 *
 * ÖNKOŞUL — anotasyonlar:
 *   1. H&E slaytı açık ve görüntü tipi Brightfield (H&E) olmalı (boya
 *      vektörleri tanımlı). Değilse: Yardımcılar → Görüntü tipi ayarla.
 *   2. Piksel boyutu (µm/px) kalibre olmalı. Değilse: Yardımcılar → Kalibrasyon.
 *   3. Slaytta en az birer **Tumor** ve **Stroma** sınıflı anotasyon olmalı
 *      (Modül 6 §2'deki gibi 3-5 küçük temsili bölge). İsterseniz Background /
 *      Necrosis gibi ek sınıflar da çizebilirsiniz; hepsi otomatik kullanılır.
 *
 * KULLANIM:
 *   1. H&E slaytında Tumor (kırmızı) + Stroma (yeşil) bölgeleri çizin
 *   2. [Extensions → Atölye → Modüller → Modül 6 - Tümör/Stroma modeli oluştur]
 *   3. Model kaydedilir; isterseniz bu slaytta hızlı önizleme yapın
 *   4. Modül 6 (uygula) ile tüm slayda uygulayıp TSR alın
 *
 * MODEL (atölye varsayılanı — sabit, "küçük" model):
 *   • Sınıflandırıcı : Random Forest (OpenCV RTrees, QuPath varsayılan ağaç ayarları)
 *   • Özellikler     : Hematoxylin OD + Eosin OD; ham + Gaussian σ = 1, 2, 4 µm
 *   • Çözünürlük     : 2 µm/px (Modül 6'daki "High")
 *   Bu ayarlar Modül 6 §3'te öğretilen ayarlarla birebir aynıdır.
 *
 * METODOLOJI NOTU:
 *   Az sayıda küçük bölgeden eğitilen RF, tek slaytta iyi; başka slaytlara
 *   genelleme sınırlıdır (Modül 6 §4.2/§4.4). Çoklu-slayt eğitimi ve hazır
 *   modeller için Modül 6 §4.4 ve Ek C'ye bakın.
 *
 *   ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP

import qupath.lib.images.servers.ColorTransforms
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.classifiers.pixel.PixelClassifier
import qupath.lib.classifiers.pixel.PixelClassifierMetadata
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs

import qupath.opencv.ops.ImageOps
import qupath.opencv.ml.OpenCVClassifiers
import qupath.opencv.ml.pixel.PixelClassifiers
import qupath.opencv.ml.pixel.PixelClassifierTools
import qupath.process.gui.commands.ml.PixelClassifierTraining

import org.bytedeco.opencv.opencv_ml.RTrees

// ──────────────────────────────────────────────────────────────
// Modal olmayan pencere yardımcıları
//   - waitForConfirm    : modal hissi veren ama QuPath'i kilitlemeyen onay penceresi
//   - showResultWindow  : sonuç penceresi — açık kalır, QuPath kullanılmaya devam edilebilir
//   - askOverwrite      : 3-seçenekli (üzerine yaz / sürümle / iptal) küçük seçim penceresi
//
// İlk ikisi always-on-top açık başlar; kullanıcı kapatmadan slaytta dolaşabilir,
// sonuçları kopyalayabilir. (Diğer modül betikleriyle aynı affordance.)
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

def waitForConfirm = { String windowTitle, String windowBody ->
    if (isHeadless) {
        println "=== ${windowTitle} ===\n${windowBody}\n=================="
        return true
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def confirmed = new java.util.concurrent.atomic.AtomicBoolean(false)

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

            def okBtn = new javafx.scene.control.Button("Çalıştır")
            okBtn.setDefaultButton(true)
            okBtn.setOnAction({
                confirmed.set(true)
                stage.close()
            })

            def cancelBtn = new javafx.scene.control.Button("İptal")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({
                confirmed.set(false)
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

            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, cancelBtn, okBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(scrollPane)
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 640, 520))
            stage.show()
        } catch (Throwable t) {
            // FX kurulumu başarısızsa modal'a geri dön
            confirmed.set(qupath.lib.gui.dialogs.Dialogs.showConfirmDialog(windowTitle, windowBody))
            latch.countDown()
        }
    }

    latch.await()
    return confirmed.get()
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
            // FX başarısızsa modal'a geri dön — kayıp olmasın
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// 3-seçenekli küçük seçim penceresi. Dönüş: "OVERWRITE" | "VERSION" | "CANCEL".
def askOverwrite = { String existingName, String versionedName ->
    if (isHeadless) {
        // GUI yokken güvenli taraf: var olan modeli silme, sürümle.
        println "'${existingName}' zaten var (headless) → '${versionedName}' olarak kaydedilecek."
        return "VERSION"
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def choice = new java.util.concurrent.atomic.AtomicReference("CANCEL")
    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle("Sınıflandırıcı zaten var")
            stage.setAlwaysOnTop(true)

            def label = new javafx.scene.control.Label(
                "Projede '${existingName}' adlı bir piksel sınıflandırıcı zaten var.\n\n" +
                "• Üzerine yaz : eski model silinir, yenisi '${existingName}' olur.\n" +
                "• Sürümle     : eski model korunur; yeni model '${versionedName}' olur\n" +
                "                (Modül 6 (uygula) varsayılan olarak '${existingName}'\n" +
                "                arar — sürümlerseniz Atölye Ayarları'ndan adı değiştirin).\n\n" +
                "Hatalı bir güncellemenin geri dönüşü zordur (Modül 6 §4.3)."
            )
            label.setWrapText(true)
            label.setStyle("-fx-font-size: 12px; -fx-padding: 8px;")

            def owBtn = new javafx.scene.control.Button("Üzerine yaz")
            owBtn.setOnAction({ choice.set("OVERWRITE"); stage.close() })
            def verBtn = new javafx.scene.control.Button("Sürümle (${versionedName})")
            verBtn.setDefaultButton(true)
            verBtn.setOnAction({ choice.set("VERSION"); stage.close() })
            def cancelBtn = new javafx.scene.control.Button("İptal")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({ choice.set("CANCEL"); stage.close() })

            stage.setOnHidden({ latch.countDown() })

            def buttons = new javafx.scene.layout.HBox(10, cancelBtn, owBtn, verBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(label)
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 600, 320))
            stage.show()
        } catch (Throwable t) {
            choice.set(qupath.lib.gui.dialogs.Dialogs.showConfirmDialog(
                "Sınıflandırıcı zaten var",
                "'${existingName}' var. Üzerine yazmak için Tamam, korumak (sürümlemek) için İptal.")
                ? "OVERWRITE" : "VERSION")
            latch.countDown()
        }
    }
    latch.await()
    return choice.get()
}

// ──────────────────────────────────────────────────────────────
// 1) Ön kontroller
// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(
        "Görüntü açık değil",
        "Önce bir H&E slaytı açın, Tumor/Stroma bölgeleri çizin, sonra bu betiği tekrar çalıştırın."
    )
    return
}

def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage(
        "Proje açık değil",
        "Bu betik modeli proje klasörüne kaydeder.\n" +
        "Önce [File → Project → Create project] ile bir proje oluşturun ve slaytlarınızı ekleyin."
    )
    return
}

// Boya vektörleri (brightfield H&E) gerekli — renk ayrıştırma kanalları buradan gelir.
def stains = imageData.getColorDeconvolutionStains()
if (stains == null || !imageData.isBrightfield()) {
    Dialogs.showErrorMessage(
        "Görüntü tipi uygun değil",
        "Bu betik Hematoxylin/Eosin renk ayrıştırma kanallarını kullanır; bunun için\n" +
        "görüntü tipi Brightfield (H&E) olmalı ve boya vektörleri tanımlı olmalı.\n\n" +
        "Çözüm: Extensions → Atölye → Yardımcılar → Görüntü tipi ayarla ile tipi\n" +
        "Brightfield (H&E) yapın, sonra bu betiği tekrar çalıştırın."
    )
    return
}

// Piksel kalibrasyonu gerekli — çözünürlük (µm/px) bundan hesaplanır.
def cal = imageData.getServer().getPixelCalibration()
def basePixelMicrons = cal.getAveragedPixelSizeMicrons()
if (!(basePixelMicrons > 0)) {
    Dialogs.showErrorMessage(
        "Kalibrasyon yok",
        "Slaytta piksel boyutu (µm/px) tanımlı değil; sınıflandırıcı çözünürlüğü hesaplanamaz.\n\n" +
        "Çözüm: Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu).\n" +
        "Sonra bu betiği tekrar çalıştırın."
    )
    return
}

// Eğitim anotasyonları — en az birer Tumor ve Stroma sınıfı gerekli.
def classifiedAnnotations = QP.getAnnotationObjects().findAll {
    it.getPathClass() != null && it.getROI() != null && it.getROI().isArea()
}
def classCounts = [:]
classifiedAnnotations.each { ann ->
    def n = ann.getPathClass().getName()
    classCounts[n] = (classCounts[n] ?: 0) + 1
}
def tumorCount  = classCounts['Tumor']  ?: 0
def stromaCount = classCounts['Stroma'] ?: 0

if (tumorCount == 0 || stromaCount == 0) {
    Dialogs.showErrorMessage(
        "Eğitim anotasyonu eksik",
        "Bir Random Forest eğitmek için slaytta en az birer **Tumor** ve **Stroma**\n" +
        "sınıflı (alan) anotasyon gerekir.\n\n" +
        "Şu an bulunan: Tumor = ${tumorCount}, Stroma = ${stromaCount}\n\n" +
        "Çözüm (Modül 6 §2):\n" +
        "  1. Annotations panel → + Add class ile Tumor (kırmızı) ve Stroma (yeşil) ekleyin\n" +
        "  2. Brush (B) ile 3-5 farklı tümör adasına küçük Tumor anotasyonları çizin\n" +
        "  3. Sınıfı Stroma'ya çevirip 3-5 stromal bölge çizin\n" +
        "  4. Bu betiği tekrar çalıştırın\n\n" +
        "İsterseniz Background / Necrosis gibi ek sınıflar da çizebilirsiniz; hepsi kullanılır."
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 2) Hedef ad + üzerine yazma kararı
// ──────────────────────────────────────────────────────────────
def baseName = atolyeS('atolye.classifierName', 'tumor-stroma-RF')
def existingNames = project.getPixelClassifiers().getNames()
def targetName = baseName

if (existingNames.contains(baseName)) {
    // İlk boş -vN adını bul (v2, v3, ...).
    def n = 2
    while (existingNames.contains("${baseName}-v${n}")) { n++ }
    def versionedName = "${baseName}-v${n}"
    def decision = askOverwrite(baseName, versionedName)
    if (decision == "CANCEL") { println "İptal."; return }
    targetName = (decision == "VERSION") ? versionedName : baseName
}

// ──────────────────────────────────────────────────────────────
// 3) Karşılama / onay
// ──────────────────────────────────────────────────────────────
def trainResolutionMicrons = 2.0   // atölye sabiti — Modül 6 "High (2 µm/px)"
def extraClasses = classCounts.keySet().findAll { !(it in ['Tumor', 'Stroma']) }

def devam = waitForConfirm(
    "Modül 6 (Adım 1) - Tümör/Stroma modeli eğit",
    "Bu betik AÇIK slayttaki çizdiğiniz anotasyonlardan bir Random Forest piksel\n" +
    "sınıflandırıcı eğitir ve projeye '${targetName}' adıyla kaydeder.\n\n" +
    "Eğitim verisi (bu slayt):\n" +
    "  • Tumor  : ${tumorCount} anotasyon\n" +
    "  • Stroma : ${stromaCount} anotasyon\n" +
    (extraClasses ? "  • Ek sınıflar: ${extraClasses.join(', ')}\n" : "") +
    "\nModel ayarları (atölye varsayılanı — Modül 6 §3 ile aynı):\n" +
    "  • Sınıflandırıcı : Random Forest\n" +
    "  • Özellikler     : Hematoxylin OD + Eosin OD; ham + Gaussian σ = 1, 2, 4 µm\n" +
    "  • Çözünürlük     : ${trainResolutionMicrons} µm/px\n\n" +
    "Not: Bu işlem yalnızca modeli EĞİTİP KAYDEDER — slayttaki anotasyonlarınıza\n" +
    "dokunmaz (silmez). Modeli tüm slayda uygulamak ve TSR almak için sonra\n" +
    "'Modül 6 - Tümör vs stroma (uygula)' betiğini çalıştırın.\n\n" +
    "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.\n\n" +
    "Hazırsanız Çalıştır düğmesine basın."
)
if (!devam) { println "İptal."; return }

// ──────────────────────────────────────────────────────────────
// 4) Modeli eğit
// ──────────────────────────────────────────────────────────────
println "─────────────────────────────────────"
println "Modül 6 (Adım 1) - Tümör/Stroma modeli eğit"
println "─────────────────────────────────────"
println "Slayt: ${QP.getProjectEntry()?.getImageName() ?: 'unnamed'}"
println String.format(java.util.Locale.US,
    "Eğitim verisi: Tumor=%d, Stroma=%d  |  Çözünürlük: %.1f µm/px",
    tumorCount, stromaCount, trainResolutionMicrons)
println "Hedef ad: ${targetName}"

def t0 = System.currentTimeMillis()

PixelClassifier classifier
try {
    // Özellik hesaplayıcı: H&E renk ayrıştırma kanalları + çok-ölçekli Gaussian.
    def chH = ColorTransforms.createColorDeconvolvedChannel(stains, 1)   // Hematoxylin
    def chE = ColorTransforms.createColorDeconvolvedChannel(stains, 2)   // Eosin
    def featureOp = ImageOps.buildImageDataOp([chH, chE])
        .appendOps(ImageOps.Core.splitMerge(
            ImageOps.Core.identity(),            // ham kanal değerleri
            ImageOps.Filters.gaussianBlur(1.0),
            ImageOps.Filters.gaussianBlur(2.0),
            ImageOps.Filters.gaussianBlur(4.0)
        ))

    // Eğitim çözünürlüğü: taban kalibrasyonu 2 µm/px'e ölçekle.
    def downsample = trainResolutionMicrons / basePixelMicrons
    def resolution = cal.createScaledInstance(downsample, downsample)

    // Anotasyonlardan eğitim verisi (özellik + etiket) çıkar.
    def training = new PixelClassifierTraining(featureOp)
    training.setResolution(resolution)
    def trainingData = training.createTrainingData(imageData)

    def labelMap = trainingData.getLabelMap()          // Map<PathClass, Integer>
    if (labelMap == null || labelMap.size() < 2) {
        Dialogs.showErrorMessage(
            "Eğitim verisi yetersiz",
            "Anotasyonlardan en az iki sınıf çıkarılamadı. Tumor ve Stroma bölgelerinin\n" +
            "boş olmadığından ve alan (area) anotasyonu olduğundan emin olun."
        )
        return
    }

    // Random Forest eğit (QuPath varsayılan ağaç parametreleri).
    def model = OpenCVClassifiers.createStatModel(RTrees.class)
    model.train(trainingData.getTrainData())

    // Etiket haritasını ters çevir: Map<Integer, PathClass> (metadata bekler).
    def classifications = [:]
    labelMap.each { pathClass, label -> classifications[label] = pathClass }

    def metadata = new PixelClassifierMetadata.Builder()
        .inputResolution(resolution)
        .inputShape(512, 512)
        .setChannelType(ImageServerMetadata.ChannelType.CLASSIFICATION)
        .classificationLabels(classifications)
        .build()

    classifier = PixelClassifiers.createClassifier(model, featureOp, metadata, true)

    // Projeye kaydet (classifiers/<targetName>.json).
    project.getPixelClassifiers().put(targetName, classifier)
} catch (Throwable t) {
    Dialogs.showErrorMessage(
        "Eğitim başarısız",
        "Model eğitilirken bir hata oluştu:\n\n" +
        t.getClass().getSimpleName() + ": " + (t.getMessage() ?: "(mesaj yok)") + "\n\n" +
        "Sık nedenler: anotasyonlar çok küçük/boş, görüntü tipi H&E değil, veya boya\n" +
        "vektörleri hatalı. Detay için View → Show log dialogue."
    )
    return
}

def elapsed = (System.currentTimeMillis() - t0) / 1000.0
println String.format(java.util.Locale.US, "Model kaydedildi: %s  |  Süre: %.1f sn", targetName, elapsed)

// ──────────────────────────────────────────────────────────────
// 5) İsteğe bağlı önizleme (tahribatsız) — slayttaki anotasyonlara dokunmaz.
//   Tüm-görüntü ROI'si üzerinden kaba Tumor/Stroma alan dağılımını ölçer;
//   hiçbir nesne oluşturmaz/silmez (aktif öğrenme döngüsü korunur).
// ──────────────────────────────────────────────────────────────
def previewText = { ->
    try {
        def server = imageData.getServer()
        def manager = PixelClassifierTools.createMeasurementManager(imageData, classifier)
        def roi = ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(),
            ImagePlane.getDefaultPlane())

        def names = manager.getMeasurementNames()
        def lines = []
        // Sınıf yüzdeleri: "<Sınıf> %" biçimindeki ölçümler.
        names.findAll { it.endsWith(" %") }.each { nm ->
            def cls = nm.substring(0, nm.length() - 2)
            def val = manager.getMeasurementValue(roi, nm)
            if (val != null) {
                lines << String.format(java.util.Locale.US, "  %-12s: %%%.1f", cls, val.doubleValue())
            }
        }
        if (lines.isEmpty()) return "  (Önizleme ölçümü hesaplanamadı.)"
        return lines.join("\n")
    } catch (Throwable t) {
        return "  (Önizleme yapılamadı: ${t.getClass().getSimpleName()} — model yine de kaydedildi.)"
    }
}

def baseResult = String.format(java.util.Locale.US,
    "Model kaydedildi ✅\n\n" +
    "  Ad        : %s\n" +
    "  Konum     : <proje>/classifiers/%s.json\n" +
    "  Eğitim    : Tumor=%d, Stroma=%d (bu slayt)\n" +
    "  Çözünürlük: %.1f µm/px  |  Süre: %.1f sn\n\n" +
    "Sıradaki: 'Modül 6 - Tümör vs stroma (uygula)' ile tüm slayda uygulayıp TSR alın.\n" +
    "Slaytta canlı renk haritasını görmek için: [Classify → Pixel classification →\n" +
    "Load classifier] → %s.\n\n" +
    "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
    targetName, targetName, tumorCount, stromaCount, trainResolutionMicrons, elapsed, targetName)

if (isHeadless) {
    println baseResult
    println "Önizleme (kaba alan dağılımı):"
    println previewText()
} else {
    // Önizleme isteğe bağlı: hesaplama tüm slaytı tarayabilir, 1 dk sürebilir.
    def wantPreview = waitForConfirm(
        "Model kaydedildi — önizleme?",
        baseResult + "\n\n" +
        "Bu slaytta hızlı bir önizleme (kaba Tumor/Stroma alan dağılımı) yapmak ister\n" +
        "misiniz? Bu, modelin işe yarayıp yaramadığını görmek içindir; anotasyonlarınıza\n" +
        "dokunmaz. Tüm slaytı tarayacağı için ~1 dk sürebilir.\n\n" +
        "Önizleme için Çalıştır, atlamak için İptal."
    )
    if (wantPreview) {
        println "Önizleme hesaplanıyor (tahribatsız)..."
        def pv = previewText()
        showResultWindow(
            "Önizleme — kaba alan dağılımı",
            baseResult + "\n\n" +
            "Önizleme (tüm slayt, kaba sınıf yüzdeleri):\n" + pv + "\n\n" +
            "Belgelenen TSR ve alan değerleri için Modül 6 (uygula) betiğini kullanın."
        )
    } else {
        showResultWindow("Tamamlandı 🧠", baseResult)
    }
}

println "─────────────────────────────────────"
println "Tamamlandı: ${targetName}"
println "─────────────────────────────────────"
