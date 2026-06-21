/**
 * Modül 6 - Tümör/Stroma Sihirbazı (model kur/eğit/uygula)
 * --------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * Bu sihirbaz, tümör/stroma piksel sınıflandırıcısı (modeli) EĞİTMENİZE,
 * KAYDETMENİZE ve UYGULAMANIZA yardımcı olur. Karar ağacı:
 *   Karşılama → "Mevcut bir sınıflandırıcı kullanmak ister misiniz?"
 *     • Evet → uygula hub'ı (model seç + kapsam + ölç)
 *     • Hayır → eğit-ve-kaydet (önkoşullar → bölge çiz → eğit) → uygula hub'ı
 *
 * Her eylem mevcut bir atölye betiğini çalıştırır; sihirbaz yalnız yönlendirir.
 *   ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import java.util.Locale

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

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

if (isHeadless) {
    def s = computeState()
    println "Sihirbaz (headless): image=${s.image} project=${s.project} H&E=${s.he} " +
            "calib=${s.calib} Tumor=${s.tumor} Stroma=${s.stroma} models=${s.classifiers}"
    println "GUI olmadan sihirbaz etkileşimi yok. Ölçüm için modul-06-tumor-stroma.groovy çalıştırın."
    return
}

// ── Tek pencere, adım adım render ──────────────────────────────────
def stage = new javafx.stage.Stage()
stage.initModality(javafx.stage.Modality.NONE)
stage.setTitle('Modül 6 - Tümör/Stroma Sihirbazı')
stage.setAlwaysOnTop(true)

def step = new java.util.concurrent.atomic.AtomicReference('WELCOME')  // WELCOME|CHOICE|TRAIN|APPLY
def render  // forward declaration

def navButton = { String text, Closure action ->
    def b = new javafx.scene.control.Button(text); b.setOnAction({ action() }); return b
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
    if (cur == 'WELCOME') {
        title.setText('Tümör/Stroma sihirbazına hoş geldiniz')
        bodyLbl.setText(
            'Bu sihirbaz tümör/stroma piksel sınıflandırıcısını (modelini) eğitmenize,\n' +
            'kaydetmenize ve uygulamanıza yardımcı olur.\n\n' +
            '• Hazır bir modeliniz/örnek model varsa: doğrudan uygulayabilirsiniz.\n' +
            '• Yoksa: birkaç Tumor/Stroma bölgesi çizip kendi modelinizi eğitiriz.\n\n' +
            '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')
        buttons.getChildren().addAll(
            navButton('İptal', { stage.close() }),
            navButton('İleri ▶', { step.set('CHOICE'); render() }))
    } else if (cur == 'CHOICE') {
        title.setText('Mevcut bir sınıflandırıcı kullanmak ister misiniz?')
        def models = s.classifiers.isEmpty() ? '(projede kayıtlı model yok)' : s.classifiers.join(', ')
        bodyLbl.setText("Projedeki modeller: ${models}\n\n" +
            'Evet → mevcut modellerden seçip uygulayın.\n' +
            'Hayır → yeni bir model eğitip kaydedin.')
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('WELCOME'); render() }),
            navButton('Hayır — yeni eğit', { step.set('TRAIN'); render() }),
            navButton('Evet — mevcut kullan', { step.set('APPLY'); render() }))
    } else if (cur == 'TRAIN') {
        title.setText('Yeni model: eğit ve kaydet')
        def sb = new StringBuilder()
        sb << "Önkoşullar:\n"
        sb << "  ${s.he ? '✓' : '✗'} Görüntü tipi Brightfield (H&E) + boya vektörleri\n"
        sb << "  ${s.calib ? '✓' : '✗'} Piksel kalibrasyonu (µm/px)\n\n"
        sb << "Eğitim bölgeleri (canlı): Tumor=${s.tumor}, Stroma=${s.stroma}\n"
        sb << "  En az birer Tumor ve Stroma alan anotasyonu gerekir (Modül 6 §2).\n\n"
        sb << "Sırasıyla: tipi/kalibrasyonu düzeltin → bölgeleri çizin → 'Eğit & kaydet'.\n"
        sb << "Her eylemden sonra 'Yenile' ile durumu güncelleyin."
        bodyLbl.setText(sb.toString())
        def fixType = navButton('Görüntü tipini ayarla',
            { if (!launchBundled('yardimci-image-type.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Görüntü tipi ayarla') })
        def fixCal = navButton('Kalibrasyonu ayarla',
            { if (!launchBundled('yardimci-kalibrasyon.groovy')) menuHint('Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu)') })
        def train = navButton('Eğit & kaydet',
            { if (!launchBundled('modul-06-model-egit.groovy')) menuHint('Extensions → Atölye → Modüller → Modül 6 - Tümör/Stroma modeli oluştur (eğit)') })
        train.setDisable(!(s.he && s.calib && s.tumor > 0 && s.stroma > 0))
        def tools = new javafx.scene.layout.HBox(8, fixType, fixCal, train)
        content.getChildren().add(tools)
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('CHOICE'); render() }),
            navButton('⟳ Yenile', { render() }),
            navButton('Uygulamaya geç ▶', { step.set('APPLY'); render() }))
    } else if (cur == 'APPLY') {
        title.setText('Modeli uygula')
        bodyLbl.setText(
            'Uygula penceresinde hangi model(ler)i ve kapsamı (seçili bölge / tüm slayt)\n' +
            'seçeceksiniz; birden çok model seçerseniz sonuçlar karşılaştırılır.\n\n' +
            "Projedeki modeller: ${s.classifiers.isEmpty() ? '(yok — örnek kurabilir veya eğitebilirsiniz)' : s.classifiers.join(', ')}")
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('CHOICE'); render() }),
            navButton('Uygula penceresini aç', {
                if (launchBundled('modul-06-tumor-stroma.groovy')) {
                    stage.close()
                } else {
                    menuHint('Extensions → Atölye → Modüller → Modül 6 - Tümör vs stroma (uygula)')
                }
            }))
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
