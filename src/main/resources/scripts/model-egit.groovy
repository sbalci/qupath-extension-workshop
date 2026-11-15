/**
 * Tümör/Stroma (Adım 1) - Tek Tıkla Tümör/Stroma Modeli Eğitme
 * --------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * Bu betik, lama ÇİZDİĞİNİZ Tumor / Stroma anotasyonlarından bir **Random
 * Forest piksel sınıflandırıcı** eğitir ve projeye `tumor-stroma-RF` adıyla
 * kaydeder — `[Classify → Pixel classification → Train pixel classifier]`
 * diyaloğunu açmadan. Kaydedilen model, **Tümör/Stroma sihirbazı** tarafından
 * seçili anotasyon sınırı içinde alan ölçümü için kullanılır.
 * Akış: birkaç eğitim bölgesi çiz → modeli eğit → ölçüm sınırını seç → ölç.
 *
 * ÖN KOŞUL — anotasyonlar:
 *   1. H&E lamı açık ve görüntü tipi Brightfield (H&E) olmalı (boya
 *      vektörleri tanımlı). Değilse: Yardımcılar → Görüntü tipi ayarla.
 *   2. Piksel boyutu (µm/px) kalibre olmalı. Değilse: Yardımcılar → Kalibrasyon.
 *   3. Lamda en az birer **Tumor** ve **Stroma** sınıflı anotasyon olmalı
 *      (Tümör/Stroma modülü §2'deki gibi 3-5 küçük temsili bölge). İsterseniz Background* /
 *      Necrosis* gibi ek sınıflar da çizebilirsiniz; sona eklenen * o sınıfı hem
 *      nesne üretiminden hem de alan ölçümünden çıkarır (Tümör/Stroma modülündeki Ignore* kuralı).
 *
 * KULLANIM:
 *   1. H&E lamında Tumor (kırmızı) + Stroma (yeşil) bölgeleri çizin
 *   2. [Automate → Project scripts → model-egit] (ya da Tümör/Stroma sihirbazından 'Yeni sınıflandırıcı eğit')
 *   3. Model kaydedilir; isterseniz bu lamda hızlı önizleme yapın
 *   4. Ölçüm sınırını seçin; Tümör/Stroma sihirbazı ile alan ölçümlerini alın
 *
 * MODEL (atölye varsayılanı — sabit, "küçük" model):
 *   • Sınıflandırıcı : Random Forest (OpenCV RTrees, QuPath varsayılan ağaç ayarları)
 *   • Özellikler     : Hematoxylin OD + Eosin OD; ham + Gaussian σ = 1, 2, 4 piksel
 *                      (eğitim çözünürlüğünde; 2 µm/px'te 2, 4, 8 µm)
 *   • Çözünürlük     : 2 µm/px (Tümör/Stroma modülü §3 başlangıç değeri; doku-ölçekli, orta çözünürlük)
 *   Bu ayarlar Tümör/Stroma modülü §3'te öğretilen ayarlarla birebir aynıdır.
 *
 * METODOLOJI NOTU:
 *   Az sayıda küçük bölgeden eğitilen RF, tek lamda iyi; başka lamlara
 *   genelleme sınırlıdır (Tümör/Stroma modülü §4.2/§4.4). Çoklu-slayt eğitimi ve hazır
 *   modeller için Tümör/Stroma modülü §4.4 ve Hazır Tümör/Stroma Modelleri ekine bakın.
 *
 *   ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

import qupath.lib.images.servers.ColorTransforms
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.classifiers.pixel.PixelClassifier
import qupath.lib.classifiers.pixel.PixelClassifierMetadata
import qupath.lib.roi.RoiTools

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
// İlk ikisi always-on-top açık başlar; kullanıcı kapatmadan lamda dolaşabilir,
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

            def okBtn = new javafx.scene.control.Button(workshopText('script.79ef801ff99f', "Çalıştır"))
            okBtn.setDefaultButton(true)
            okBtn.setOnAction({
                confirmed.set(true)
                stage.close()
            })

            def cancelBtn = new javafx.scene.control.Button(workshopText('script.72278748133a', "İptal"))
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({
                confirmed.set(false)
                stage.close()
            })

            stage.setOnHidden({ latch.countDown() })

            def alwaysTop = new javafx.scene.control.CheckBox(workshopText('script.70ca64c0c43a', "Üstte tut"))
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
            confirmed.set(qupath.fx.dialogs.Dialogs.showConfirmDialog(windowTitle, windowBody))
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

            stage.setScene(new javafx.scene.Scene(root, 760, 560))
            stage.show()
        } catch (Throwable t) {
            // FX başarısızsa modal'a geri dön — kayıp olmasın
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
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
            stage.setTitle(workshopText('script.d51cf3ad0424', "Sınıflandırıcı zaten var"))
            stage.setAlwaysOnTop(true)

            def label = new javafx.scene.control.Label(
                "${workshopText('script.6262c9f6828d', 'Projede \'')}${existingName}${workshopText('script.db440695c39b', '\' adlı bir piksel sınıflandırıcı zaten var.\n\n')}" +
                "${workshopText('script.fe9402d6d2d7', '• Üzerine yaz : eski model silinir, yenisi \'')}${existingName}${workshopText('script.c6e4aa3752cc', '\' olur.\n')}" +
                "${workshopText('script.4272c6ff9eaf', '• Sürümle     : eski model korunur; yeni model \'')}${versionedName}${workshopText('script.95aca84d8ffd', '\' olur\n')}" +
                "${workshopText('script.3c5da07fa47a', '                (Tümör/Stroma sihirbazı (uygula) varsayılan olarak \'')}${existingName}'\n" +
                workshopText('script.cb68389080db', "                arar — sürümlerseniz Atölye Ayarları'ndan adı değiştirin).\n\n") +
                workshopText('script.00498729bc98', "Hatalı bir güncellemenin geri dönüşü zordur (Tümör/Stroma modülü §4.3).")
            )
            label.setWrapText(true)
            label.setStyle("-fx-font-size: 12px; -fx-padding: 8px;")

            def owBtn = new javafx.scene.control.Button(workshopText('script.983952c4424b', "Üzerine yaz"))
            owBtn.setOnAction({ choice.set("OVERWRITE"); stage.close() })
            def verBtn = new javafx.scene.control.Button("${workshopText('script.f4ab6a75ba8d', 'Sürümle (')}${versionedName})")
            verBtn.setDefaultButton(true)
            verBtn.setOnAction({ choice.set("VERSION"); stage.close() })
            def cancelBtn = new javafx.scene.control.Button(workshopText('script.72278748133a', "İptal"))
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
            choice.set(qupath.fx.dialogs.Dialogs.showConfirmDialog(
                workshopText('script.d51cf3ad0424', "Sınıflandırıcı zaten var"),
                "'${existingName}${workshopText('script.b67b787edaf5', '\' var. Üzerine yazmak için Tamam, korumak (sürümlemek) için İptal.')}")
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
        workshopText('script.b4c901dc184b', "Görüntü açık değil"),
        workshopText('script.f3f3a5d73643', "Önce bir H&E lamı açın, Tumor/Stroma bölgeleri çizin, sonra bu betiği tekrar çalıştırın.")
    )
    return
}

def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage(
        workshopText('script.a38d0e6adac0', "Proje açık değil"),
        workshopText('script.b05e604138a8', "Bu betik modeli proje klasörüne kaydeder.\n" +
        "Önce [File → Project → Create project] ile bir proje oluşturun ve lamlarınızı ekleyin.")
    )
    return
}

// Boya vektörleri ve gerçek Brightfield (H&E) görüntü tipi gerekli.
def imageTypeName = imageData.getImageType()?.toString() ?: ''
def normalizedImageType = imageTypeName.toUpperCase(java.util.Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
def stains = imageData.getColorDeconvolutionStains()
if (stains == null || !normalizedImageType.contains('BRIGHTFIELD_H_E')) {
    Dialogs.showErrorMessage(
        workshopText('script.762ff995cda6', "Görüntü tipi uygun değil"),
        workshopText('script.1fae35d24240', "Bu betik Hematoxylin/Eosin renk ayrıştırma kanallarını kullanır; bunun için\n" +
        "görüntü tipi Brightfield (H&E) olmalı ve boya vektörleri tanımlı olmalı.\n\n" +
        "Çözüm: Extensions → Atölye → Yardımcılar → Görüntü tipi ayarla ile tipi\n" +
        "Brightfield (H&E) yapın, sonra bu betiği tekrar çalıştırın.")
    )
    return
}

// Piksel kalibrasyonu gerekli — çözünürlük (µm/px) bundan hesaplanır.
def cal = imageData.getServer().getPixelCalibration()
def basePixelMicrons = cal.getAveragedPixelSizeMicrons()
if (!(basePixelMicrons > 0)) {
    Dialogs.showErrorMessage(
        workshopText('script.a5a01874aff2', "Kalibrasyon yok"),
        workshopText('script.bff255079a29', "Lamda piksel boyutu (µm/px) tanımlı değil; sınıflandırıcı çözünürlüğü hesaplanamaz.\n\n" +
        "Çözüm: Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu).\n" +
        "Sonra bu betiği tekrar çalıştırın.")
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
        workshopText('script.f5fcfe6d1ac7', "Eğitim anotasyonu eksik"),
        workshopText('script.f47059686bbb', "Bir Random Forest eğitmek için lamda en az birer **Tumor** ve **Stroma**\n" +
        "sınıflı (alan) anotasyon gerekir.\n\n") +
        "${workshopText('script.d44f8e99c9e3', 'Şu an bulunan: Tumor = ')}${tumorCount}, Stroma = ${stromaCount}\n\n" +
        workshopText('script.9cb21ab3346e', "Çözüm (Tümör/Stroma modülü §2):\n") +
        workshopText('script.aeb9aa13eebf', "  1. Annotations panel → + Add class ile Tumor (kırmızı) ve Stroma (yeşil) ekleyin\n") +
        workshopText('script.97c3ff43b99d', "  2. Brush (B) ile 3-5 farklı tümör adasına küçük Tumor anotasyonları çizin\n") +
        workshopText('script.4ae80a14de67', "  3. Sınıfı Stroma'ya çevirip 3-5 stromal bölge çizin\n") +
        workshopText('script.0dc4e69b8522', "  4. Bu betiği tekrar çalıştırın\n\n") +
        workshopText('script.2d1948071a50', "İsterseniz Background* / Necrosis* gibi ek sınıflar da çizebilirsiniz (sona * ekleyin: nesne ve alan ölçümü dışı kalır).")
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
def trainResolutionMicrons = 2.0   // atölye sabiti — Tümör/Stroma modülü §3 başlangıç çözünürlüğü (2 µm/px, doku-ölçekli)
def extraClasses = classCounts.keySet().findAll { !(it in ['Tumor', 'Stroma', 'Region', 'TSR Özet']) }   // Region/TSR Özet eğitime girmez

def devam = waitForConfirm(
    "Tümör/Stroma (Adım 1) - Tümör/Stroma modeli eğit",
    "Bu betik AÇIK lamdaki çizdiğiniz anotasyonlardan bir Random Forest piksel\n" +
    "sınıflandırıcı eğitir ve projeye '${targetName}' adıyla kaydeder.\n\n" +
    "Eğitim verisi (bu lam):\n" +
    "  • Tumor  : ${tumorCount} anotasyon\n" +
    "  • Stroma : ${stromaCount} anotasyon\n" +
    (extraClasses ? "  • Ek sınıflar: ${extraClasses.join(', ')}\n" : "") +
    "\nModel ayarları (atölye varsayılanı — Tümör/Stroma modülü §3 ile aynı):\n" +
    "  • Sınıflandırıcı : Random Forest\n" +
    "  • Özellikler     : Hematoxylin OD + Eosin OD; ham + Gaussian σ = 1, 2, 4 piksel\n" +
    "                     (eğitim çözünürlüğünde; 2 µm/px'te 2, 4, 8 µm)\n" +
    "  • Çözünürlük     : ${trainResolutionMicrons} µm/px\n\n" +
    "Not: Bu işlem yalnızca modeli EĞİTİP KAYDEDER — lamdaki anotasyonlarınıza\n" +
    "dokunmaz (silmez). Modeli seçili anotasyon sınırında ölçmek için sonra\n" +
    "'Tümör/Stroma sihirbazı' betiğini çalıştırın.\n\n" +
    "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.\n\n" +
    "Hazırsanız Çalıştır düğmesine basın."
)
if (!devam) { println "İptal."; return }

// ──────────────────────────────────────────────────────────────
// 4) Modeli eğit
// ──────────────────────────────────────────────────────────────
println "─────────────────────────────────────"
println "Tümör/Stroma (Adım 1) - Tümör/Stroma modeli eğit"
println "─────────────────────────────────────"
println "Lam: ${QP.getProjectEntry()?.getImageName() ?: 'unnamed'}"
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
    // Etiket haritası AÇIKÇA kurulur. createTrainingData(imageData) sınıflı HER alan anotasyonunu
    // etiket sayar: ölçüm kapsamı için çizilen 'Region' ve özet sınıfı 'TSR Özet' de modele ek
    // sınıf olarak girer; Tumor/Stroma yüzdeleri küçülmüş bir paydada ölçülürdü. Bu iki
    // yardımcı sınıf eğitime alınmaz; Tumor, Stroma ve kullanıcının çizdiği diğer sınıflar alınır.
    def NON_TRAINING_CLASSES = ['Region', 'TSR Özet'] as Set
    def labelMap = new java.util.LinkedHashMap()       // Map<PathClass, Integer>
    QP.getAnnotationObjects().each { a ->
        def pc = a.getPathClass()
        if (pc == null || a.getROI() == null || !a.getROI().isArea()) return
        if (pc.getName() in NON_TRAINING_CLASSES) return
        if (!labelMap.containsKey(pc)) labelMap.put(pc, labelMap.size())
    }
    def trainingData = (labelMap.size() >= 2)
        ? training.createTrainingDataForLabelMap(java.util.Collections.singletonList(imageData), labelMap)
        : null

    if (labelMap == null || labelMap.size() < 2) {
        Dialogs.showErrorMessage(
            workshopText('script.43dc59f56fbb', "Eğitim verisi yetersiz"),
            workshopText('script.fc30ea13fc1e', "Anotasyonlardan en az iki sınıf çıkarılamadı. Tumor ve Stroma bölgelerinin\n" +
            "boş olmadığından ve alan (area) anotasyonu olduğundan emin olun.")
        )
        return
    }

    // Random Forest eğit (QuPath varsayılan ağaç parametreleri). QuPath'in yerel
    // "Train pixel classifier" penceresi gibi eğitim örneklerini sınırla (Atölye
    // ayarı 'atolye.maxTrainingSamples', varsayılan 100000; 0 ⇒ sınır yok): RF eğitim
    // süresi örnek sayısıyla artar; sınır olmadan büyük anotasyonlarda eğitim yerel
    // pencereden çok daha yavaş olur.
    def trainData = trainingData.getTrainData()
    int maxTrainingSamples = atolyeI('atolye.maxTrainingSamples', 100000)
    if (maxTrainingSamples > 0 && trainData.getNTrainSamples() > maxTrainingSamples)
        trainData.setTrainTestSplit(maxTrainingSamples, true)
    def model = OpenCVClassifiers.createStatModel(RTrees.class)
    model.train(trainData)

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
        workshopText('script.4768938ec5c2', "Eğitim başarısız"),
        workshopText('script.1d797877ecfc', "Model eğitilirken bir hata oluştu:\n\n") +
        t.getClass().getSimpleName() + ": " + (t.getMessage() ?: workshopText('script.5c099e44e889', "(mesaj yok)")) + "\n\n" +
        workshopText('script.3075dc1ed51c', "Sık nedenler: anotasyonlar çok küçük/boş, görüntü tipi H&E değil, veya boya\n") +
        workshopText('script.6edcf9d33c6f', "vektörleri hatalı. Detay için View → Show log dialogue.")
    )
    return
}

def elapsed = (System.currentTimeMillis() - t0) / 1000.0
println String.format(java.util.Locale.US, "Model kaydedildi: %s  |  Süre: %.1f sn", targetName, elapsed)

// ──────────────────────────────────────────────────────────────
// 5) İsteğe bağlı önizleme (tahribatsız) — lamdaki anotasyonlara dokunmaz.
//   Ölçüm sınırı: SEÇİLİ anotasyonlar (seçim yoksa sınıfı 'Region' olanlar);
//   hiçbir nesne oluşturmaz/silmez (aktif öğrenme döngüsü korunur).
// ──────────────────────────────────────────────────────────────
def previewText = { ->
    try {
        def regionObjects = QP.getSelectedObjects().findAll {
            it.isAnnotation() && it.getROI()?.isArea()
        }
        if (regionObjects.isEmpty()) {
            regionObjects = QP.getAnnotationObjects().findAll {
                it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Region'
            }
        }
        if (regionObjects.isEmpty()) {
            return '  Önizleme için bir anotasyon seçin (ya da sınıfı "Region" olan bir anotasyon çizin).'
        }
        def roi = RoiTools.union(regionObjects.collect { it.getROI() })
        def manager = PixelClassifierTools.createMeasurementManager(imageData, classifier)
        def names = manager.getMeasurementNames()
        def tumorName = names.find { it.startsWith('Tumor area ') }
        def stromaName = names.find { it.startsWith('Stroma area ') }
        if (tumorName == null || stromaName == null) {
            return '  Önizleme için sınıflandırıcıda Tumor ve Stroma çıktı sınıfları bulunmalı.'
        }
        double tumor = manager.getMeasurementValue(roi, tumorName)?.doubleValue() ?: 0.0
        double stroma = manager.getMeasurementValue(roi, stromaName)?.doubleValue() ?: 0.0
        double total = tumor + stroma
        double tumorPct = total > 0 ? 100.0 * tumor / total : Double.NaN
        double stromaPct = total > 0 ? 100.0 * stroma / total : Double.NaN
        double ratio = stroma > 0 ? tumor / stroma : Double.NaN
        def fmt = { double value, String pattern ->
            Double.isFinite(value) ? String.format(java.util.Locale.US, pattern, value) : 'hesaplanamadı'
        }
        return '  Kapsam: seçili anotasyonların birleşimi\n' +
            "  Tümör alanı (%)    : ${fmt(tumorPct, '%.2f%%')}\n" +
            "  Stroma alanı (%)   : ${fmt(stromaPct, '%.2f%%')}\n" +
            "  Tümör/Stroma oranı : ${fmt(ratio, '%.4f')}"
    } catch (Throwable t) {
        return "  (Önizleme yapılamadı: ${t.getClass().getSimpleName()} — model yine de kaydedildi.)"
    }
}
def baseResult = String.format(java.util.Locale.US,
    "Model kaydedildi ✅\n\n" +
    "  Ad        : %s\n" +
    "  Konum     : <proje>/classifiers/%s.json\n" +
    "  Eğitim    : Tumor=%d, Stroma=%d (bu lam)\n" +
    "  Çözünürlük: %.1f µm/px  |  Süre: %.1f sn\n\n" +
    "Sıradaki: ölçüm sınırını seçin; 'Tümör/Stroma sihirbazı' ile alanları ölçün.\n" +
    "Lamda canlı renk haritasını görmek için: [Classify → Pixel classification →\n" +
    "Load classifier] → %s.\n\n" +
    "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
    targetName, targetName, tumorCount, stromaCount, trainResolutionMicrons, elapsed, targetName)

if (isHeadless) {
    println baseResult
    println "Önizleme (seçili alan ölçümleri):"
    println previewText()
} else {
    // Önizleme isteğe bağlı ve seçili anotasyon sınırı içinde hesaplanır.
    def wantPreview = waitForConfirm(
        "Model kaydedildi — önizleme?",
        baseResult + "\n\n" +
        "Seçili anotasyon(lar) içinde hızlı bir alan önizlemesi yapmak ister misiniz?\n" +
        "Bu, modelin işe yarayıp yaramadığını görmek içindir; anotasyonlarınıza dokunmaz.\n" +
        "Önce ölçmek istediğiniz anotasyonu seçin (seçim yoksa 'Region' sınıfı kullanılır).\n\n" +
        "Önizleme için Çalıştır, atlamak için İptal."
    )
    if (wantPreview) {
        println "Önizleme hesaplanıyor (tahribatsız)..."
        def pv = previewText()
        showResultWindow(
            "Önizleme — kaba alan dağılımı",
            baseResult + "\n\n" +
            "Önizleme (seçili sınır, betimsel alan ölçümleri):\n" + pv + "\n\n" +
            "Kalıcı ve dışa aktarılabilir ölçümler için Tümör/Stroma sihirbazını kullanın."
        )
    } else {
        showResultWindow("Tamamlandı 🧠", baseResult)
    }
}

println "─────────────────────────────────────"
println "Tamamlandı: ${targetName}"
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
