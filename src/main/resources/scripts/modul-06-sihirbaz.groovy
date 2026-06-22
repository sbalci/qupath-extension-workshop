/**
 * Modül 6 - Tümör/Stroma Sihirbazı (model kur/eğit/uygula)
 * --------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * Bu sihirbaz iki yol sunar:
 *   • Örnek sınıflandırıcıyı kullan → paketli `tumor-stroma-RF` modelini projeye
 *     kurar; doğrudan ölçüme geçebilirsiniz.
 *   • Yeni sınıflandırıcı eğit → adım adım yönlendirir:
 *       1. Tumor bölgesi çizin (sınıfı Tumor) → İleri (kontrol edilir)
 *       2. Stroma bölgesi çizin (sınıfı Stroma) → İleri (kontrol edilir)
 *       3. Sınıflandırıcı için bir ad seçin → eğit & kaydet
 *
 * Eğitim, Modül 6a (modeli oluştur) ile aynı atölye ayarlarını kullanır:
 * Random Forest, Hematoxylin + Eosin OD özellikleri, 2 µm/px çözünürlük.
 *   ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import java.util.Locale

import qupath.lib.images.servers.ColorTransforms
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.classifiers.pixel.PixelClassifier
import qupath.lib.classifiers.pixel.PixelClassifierMetadata
import qupath.opencv.ops.ImageOps
import qupath.opencv.ml.OpenCVClassifiers
import qupath.opencv.ml.pixel.PixelClassifiers
import qupath.process.gui.commands.ml.PixelClassifierTraining
import org.bytedeco.opencv.opencv_ml.RTrees

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── Atölye tercihleri (eklenti yoksa sabit varsayılan) ─────────────
def __wpCall = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = null
    try { c = Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { c = null }
    if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeS = { String k, String d -> (String) __wpCall('str', [String.class, String.class] as Class[], [k, d] as Object[], d) }

// ── Paketli kardeş betikleri çalıştırma ────────────────────────────
def bundledScript = { String name ->
    try {
        Class.forName('io.github.sbalci.qupath.workshop.WorkshopResources')
            .getMethod('getBundledScript', String.class).invoke(null, name)
    } catch (Throwable t) { null }
}
def launchBundled = { String name ->
    def text = bundledScript(name)
    if (text == null) return false
    def runner = new Thread({
        try { new groovy.lang.GroovyShell(this.class.classLoader).evaluate(text, name) }
        catch (Throwable err) { println "Alt betik hatası (${name}): ${err}" }
    }, "AtolyeWizard-${name}")
    runner.setDaemon(true); runner.start()
    return true
}
def menuHint = { String where ->
    Dialogs.showMessageDialog('Eklenti gerekli',
        "Bu adım atölye eklentisini gerektirir. Menüden çalıştırın:\n${where}")
}

// ── Durum hesaplama ────────────────────────────────────────────────
def computeState = { ->
    def st = [image:false, project:false, he:false, calib:false, tumor:0, stroma:0, classifiers:[]]
    def imageData = QP.getCurrentImageData()
    st.image = imageData != null
    def project = QP.getProject()
    st.project = project != null
    if (imageData != null) {
        def typeName = (imageData.getImageType()?.toString() ?: '').toUpperCase(Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
        st.he = typeName.contains('BRIGHTFIELD_H_E') && imageData.getColorDeconvolutionStains() != null
        def cal = imageData.getServer()?.getPixelCalibration()
        st.calib = cal != null && cal.getAveragedPixelSizeMicrons() > 0
        QP.getAnnotationObjects().each {
            def n = it.getPathClass()?.getName()
            if (it.getROI()?.isArea() && n == 'Tumor') st.tumor++
            if (it.getROI()?.isArea() && n == 'Stroma') st.stroma++
        }
    }
    if (project != null) st.classifiers = new ArrayList(project.getPixelClassifiers().getNames())
    return st
}

// ── Eğit & kaydet (Modül 6a ile aynı atölye ayarları) ──────────────
// Dönüş: [ok:bool, error:String]. GUI dışı bir iş parçacığından çağrılır.
def trainAndSave = { String targetName ->
    try {
        def imageData = QP.getCurrentImageData()
        def project = QP.getProject()
        def stains = imageData.getColorDeconvolutionStains()
        def cal = imageData.getServer().getPixelCalibration()
        double basePixelMicrons = cal.getAveragedPixelSizeMicrons()
        double trainResolutionMicrons = 2.0   // atölye sabiti — Modül 6 "High (2 µm/px)"

        def chH = ColorTransforms.createColorDeconvolvedChannel(stains, 1)   // Hematoxylin
        def chE = ColorTransforms.createColorDeconvolvedChannel(stains, 2)   // Eosin
        def featureOp = ImageOps.buildImageDataOp([chH, chE])
            .appendOps(ImageOps.Core.splitMerge(
                ImageOps.Core.identity(),
                ImageOps.Filters.gaussianBlur(1.0),
                ImageOps.Filters.gaussianBlur(2.0),
                ImageOps.Filters.gaussianBlur(4.0)
            ))

        def downsample = trainResolutionMicrons / basePixelMicrons
        def resolution = cal.createScaledInstance(downsample, downsample)

        def training = new PixelClassifierTraining(featureOp)
        training.setResolution(resolution)
        def trainingData = training.createTrainingData(imageData)

        def labelMap = trainingData.getLabelMap()
        if (labelMap == null || labelMap.size() < 2)
            return [ok:false, error:'Anotasyonlardan en az iki sınıf çıkarılamadı. Tumor ve Stroma bölgelerinin boş olmadığından emin olun.']

        def model = OpenCVClassifiers.createStatModel(RTrees.class)
        model.train(trainingData.getTrainData())

        def classifications = [:]
        labelMap.each { pathClass, label -> classifications[label] = pathClass }

        def metadata = new PixelClassifierMetadata.Builder()
            .inputResolution(resolution)
            .inputShape(512, 512)
            .setChannelType(ImageServerMetadata.ChannelType.CLASSIFICATION)
            .classificationLabels(classifications)
            .build()

        def classifier = PixelClassifiers.createClassifier(model, featureOp, metadata, true)
        project.getPixelClassifiers().put(targetName, classifier)
        return [ok:true]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '(mesaj yok)')]
    }
}

if (isHeadless) {
    def s = computeState()
    println "Sihirbaz (headless): image=${s.image} project=${s.project} H&E=${s.he} " +
            "calib=${s.calib} Tumor=${s.tumor} Stroma=${s.stroma} models=${s.classifiers}"
    println "GUI olmadan sihirbaz etkileşimi yok. Eğitim için modul-06-model-egit.groovy, ölçüm için modul-06-tumor-stroma.groovy çalıştırın."
    return
}

// ── Tek pencere, adım adım render ──────────────────────────────────
def stage = new javafx.stage.Stage()
stage.initModality(javafx.stage.Modality.NONE)
stage.setTitle('Modül 6 - Tümör/Stroma Sihirbazı')
stage.setAlwaysOnTop(true)

// CHOICE | PREREQ | TUMOR | STROMA | NAME | TRAINING | TRAIN_DONE | EXAMPLE_DONE
def step = new java.util.concurrent.atomic.AtomicReference('CHOICE')
def trainedName = new java.util.concurrent.atomic.AtomicReference(null)
def render  // forward declaration

def navButton = { String text, Closure action ->
    def b = new javafx.scene.control.Button(text); b.setOnAction({ action() }); return b
}
def openApply = {
    if (launchBundled('modul-06-tumor-stroma.groovy')) stage.close()
    else menuHint('Extensions → Atölye → Modüller → Modül 6b - Tümör vs stroma (uygula)')
}

render = { ->
    def s = computeState()
    def content = new javafx.scene.layout.VBox(10)
    content.setPadding(new javafx.geometry.Insets(14))
    def buttons = new javafx.scene.layout.HBox(8)
    buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def bodyLbl = new javafx.scene.control.Label()
    bodyLbl.setWrapText(true)
    content.getChildren().addAll(title, bodyLbl)

    def cur = step.get()
    if (cur == 'CHOICE') {
        title.setText('Tümör/Stroma sihirbazı')
        bodyLbl.setText(
            'Nasıl devam etmek istersiniz?\n\n' +
            '• Örnek sınıflandırıcı: hazır paketli modeli projeye kurar.\n' +
            '• Yeni sınıflandırıcı eğit: kendi Tumor/Stroma bölgelerinizden eğitir.\n\n' +
            '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')
        buttons.getChildren().addAll(
            navButton('İptal', { stage.close() }),
            navButton('Örnek sınıflandırıcıyı kullan', {
                if (launchBundled('yardimci-ornek-siniflandirici.groovy')) { step.set('EXAMPLE_DONE'); render() }
                else menuHint('Extensions → Atölye → Yardımcılar → Örnek tümör/stroma sınıflandırıcısını projeye kaydet')
            }),
            navButton('Yeni sınıflandırıcı eğit ▶', {
                step.set((s.he && s.calib) ? 'TUMOR' : 'PREREQ'); render()
            }))
    } else if (cur == 'PREREQ') {
        title.setText('Önce: görüntü tipi ve kalibrasyon')
        bodyLbl.setText(
            "Eğitim için iki önkoşul gerekir:\n\n" +
            "  ${s.he ? '✓' : '✗'} Görüntü tipi Brightfield (H&E) + boya vektörleri\n" +
            "  ${s.calib ? '✓' : '✗'} Piksel kalibrasyonu (µm/px)\n\n" +
            "Eksik olanı aşağıdaki düğmelerle düzeltin, sonra 'Yenile'.")
        def fixType = navButton('Görüntü tipini ayarla',
            { if (!launchBundled('yardimci-image-type.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Görüntü tipi ayarla') })
        def fixCal = navButton('Kalibrasyonu ayarla',
            { if (!launchBundled('yardimci-kalibrasyon.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu)') })
        content.getChildren().add(new javafx.scene.layout.HBox(8, fixType, fixCal))
        def cont = navButton('Devam ▶', { step.set('TUMOR'); render() })
        cont.setDisable(!(s.he && s.calib))
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('CHOICE'); render() }),
            navButton('⟳ Yenile', { render() }), cont)
    } else if (cur == 'TUMOR') {
        title.setText('1/3 — Tümör bölgesi çizin')
        bodyLbl.setText(
            'Slaytta birkaç temsilî tümör adasını anotasyonla çizin ve sınıfını\n' +
            '**Tumor** yapın (Annotations panelinden sınıf atayın). Bitince İleri.\n\n' +
            "Şu an bulunan Tumor anotasyonu: ${s.tumor}")
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('CHOICE'); render() }),
            navButton('⟳ Yenile', { render() }),
            navButton('İleri ▶', {
                if (s.tumor > 0) { step.set('STROMA'); render() }
                else Dialogs.showWarningNotification('Tumor anotasyonu yok',
                    'Henüz sınıfı "Tumor" olan bir alan anotasyonu görünmüyor. Bir bölge çizip\n' +
                    'sınıfını Tumor yaptığınızı kontrol edin, sonra tekrar İleri.')
            }))
    } else if (cur == 'STROMA') {
        title.setText('2/3 — Stroma bölgesi çizin')
        bodyLbl.setText(
            'Şimdi birkaç stromal bölgeyi anotasyonla çizin ve sınıfını **Stroma**\n' +
            'yapın. Bitince İleri.\n\n' +
            "Şu an bulunan Stroma anotasyonu: ${s.stroma}")
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('TUMOR'); render() }),
            navButton('⟳ Yenile', { render() }),
            navButton('İleri ▶', {
                if (s.stroma > 0) { step.set('NAME'); render() }
                else Dialogs.showWarningNotification('Stroma anotasyonu yok',
                    'Henüz sınıfı "Stroma" olan bir alan anotasyonu görünmüyor. Bir bölge çizip\n' +
                    'sınıfını Stroma yaptığınızı kontrol edin, sonra tekrar İleri.')
            }))
    } else if (cur == 'NAME') {
        title.setText('3/3 — Sınıflandırıcıya ad verin')
        bodyLbl.setText(
            "Eğitim verisi: Tumor=${s.tumor}, Stroma=${s.stroma} (bu slayt).\n" +
            'Modeli kaydetmek için bir ad girin, sonra "Eğit & kaydet".')
        def nameField = new javafx.scene.control.TextField(atolyeS('atolye.classifierName', 'tumor-stroma-RF'))
        nameField.setPrefColumnCount(28)
        content.getChildren().add(new javafx.scene.layout.HBox(8,
            new javafx.scene.control.Label('Ad:'), nameField))
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('STROMA'); render() }),
            navButton('Eğit & kaydet', {
                String nm = nameField.getText()?.trim()
                if (!nm) { Dialogs.showWarningNotification('Ad gerekli', 'Bir sınıflandırıcı adı girin.'); return }
                if (s.classifiers.contains(nm) &&
                    !Dialogs.showConfirmDialog('Üzerine yaz?', "'${nm}' zaten var. Üzerine yazılsın mı?")) return
                trainedName.set(nm)
                step.set('TRAINING'); render()
                def t0 = System.currentTimeMillis()
                def worker = new Thread({
                    def res = trainAndSave(nm)
                    double elapsed = (System.currentTimeMillis() - t0) / 1000.0
                    javafx.application.Platform.runLater {
                        if (res.ok) {
                            println String.format(java.util.Locale.US, 'Sihirbaz: model kaydedildi %s (%.1f sn)', nm, elapsed)
                            step.set('TRAIN_DONE'); render()
                        } else {
                            Dialogs.showErrorMessage('Eğitim başarısız', res.error ?: '(bilinmeyen hata)')
                            step.set('NAME'); render()
                        }
                    }
                }, 'AtolyeWizardTrain')
                worker.setDaemon(true); worker.start()
            }))
    } else if (cur == 'TRAINING') {
        title.setText('Eğitiliyor…')
        bodyLbl.setText("'${trainedName.get()}' eğitiliyor ve kaydediliyor. Lütfen bekleyin…")
    } else if (cur == 'TRAIN_DONE') {
        title.setText('Model kaydedildi ✅')
        bodyLbl.setText(
            "'${trainedName.get()}' projeye kaydedildi (<proje>/classifiers/).\n\n" +
            'Ölçüm için uygula penceresini açabilirsiniz. Orada ölçüm sınırını seçili\n' +
            'anotasyon(lar)ınız belirler.\n\n' +
            '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')
        buttons.getChildren().addAll(
            navButton('Kapat', { stage.close() }),
            navButton('Uygula penceresini aç', { openApply() }))
    } else if (cur == 'EXAMPLE_DONE') {
        title.setText('Örnek sınıflandırıcı kuruldu')
        bodyLbl.setText(
            'Paketli örnek model projeye kaydedildi (tumor-stroma-RF).\n\n' +
            'Ölçüm için uygula penceresini açın; ölçüm sınırını seçili anotasyon(lar)ınız\n' +
            'belirler (seçim yoksa "Region" sınıfı kullanılır).')
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('CHOICE'); render() }),
            navButton('Uygula penceresini aç', { openApply() }))
    }

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(content)
    def bottom = new javafx.scene.layout.VBox(buttons)
    bottom.setPadding(new javafx.geometry.Insets(10))
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 600, 440))
}

javafx.application.Platform.runLater {
    try { render(); stage.show() }
    catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
