/**
 * Yardımcı - Görüntü yakala (rapor/sunum)
 * ---------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Açık görüntüleyiciyi (hücre/sınıf/anotasyon OVERLAY'leriyle birlikte) ya da
 *   tüm QuPath penceresini ekran görüntüsü olarak yakalar; PNG/JPEG dosyasına
 *   kaydeder VEYA panoya kopyalar. Rapor, sunum ve eğitim materyali için
 *   analiz sonucunuzun göründüğü hâlini tek tıkla almanızı sağlar.
 *
 *   QuPath'in kendi yakalama API'sini kullanır
 *   (qupath.lib.gui.tools.GuiTools.makeSnapshot) — ek bağımlılık yoktur.
 *   Görüntü, ekran piksellerinden değil QuPath'in SAHNE GRAFİĞİNDEN üretilir;
 *   bu yüzden bu pencere (ayrı bir Stage) yakalanan karede GÖRÜNMEZ ve
 *   üst üste binen pencereler kareyi kirletmez (gecikme/saydamlık hilesi
 *   gerekmez). Tam ekran (üstteki tüm pencereler) modu kasıtlı olarak yoktur.
 *
 * İKİ YAKALAMA TÜRÜ:
 *   • Görüntüleyici (overlay'lerle) → yalnız aktif görüntüleyici tuvali
 *   • Tüm QuPath penceresi          → ana pencere sahnesi (araç çubukları dâhil)
 *
 * NE ÖLÇMEZ:
 *   Hiçbir ölçüm/analiz yapmaz; anotasyon, tespit veya proje verisine
 *   dokunmaz. Yalnızca görsel bir yakalama yardımcısıdır.
 *
 * YÜKSEK ÇÖZÜNÜRLÜK: Yayın kalitesinde (ekran çözünürlüğünün ötesinde) figür
 *   için QuPath'in yerleşik [File → Export images… → Rendered RGB] yolunu
 *   kullanın; bu araç hızlı ekran-çözünürlüklü kareler içindir.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı görsel bir yardımcıdır.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.tools.GuiTools
import qupath.lib.gui.tools.GuiTools.SnapshotType

import javafx.embed.swing.SwingFXUtils
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent

import java.awt.image.BufferedImage
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

def isHeadless = QuPathGUI.getInstance() == null

// ── Headless (GUI yok): yakalanacak görüntüleyici/pencere yok ───────────────
if (isHeadless) {
    println "Görüntü yakala yalnızca QuPath arayüzünde çalışır (headless modda atlandı)."
    return
}

// ── UI yardımcısı (diğer sihirbazlarla aynı idiom) ──────────────────────────
def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text); b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    return b
}

// İlk kayıt klasörü: proje klasörü (varsa), yoksa kullanıcı ev dizini.
def defaultDir = { ->
    try {
        def project = qupath.lib.scripting.QP.getProject()
        def path = project?.getPath()
        if (path != null) {
            def base = path.getParent()           // .../project.qpproj → ana klasör
            if (base != null) return base.toFile()
        }
    } catch (Throwable ignored) { }
    return new File(System.getProperty('user.home') ?: '.')
}

// Benzersiz dosya: base.ext varsa base-1.ext, base-2.ext … döndür (üzerine yazmaz).
def confirmFile = { File dir, String base, String ext, boolean unique ->
    def f = new File(dir, base + '.' + ext)
    if (!unique) return f
    int i = 1
    while (f.exists()) { f = new File(dir, base + '-' + i + '.' + ext); i++ }
    return f
}

// JPEG'i belirli kalitede yaz (alfa kanalını düşürüp TYPE_INT_RGB'ye çevirir).
def writeJpeg = { BufferedImage img, File f, float quality ->
    def rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB)
    def g = rgb.createGraphics()
    g.drawImage(img, 0, 0, java.awt.Color.WHITE, null); g.dispose()
    def writer = ImageIO.getImageWritersByFormatName('jpg').next()
    def param = writer.getDefaultWriteParam()
    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
    param.setCompressionQuality(quality)
    def ios = ImageIO.createImageOutputStream(f)
    try { writer.setOutput(ios); writer.write(null, new IIOImage(rgb, null, null), param) }
    finally { try { ios.close() } catch (Throwable ignore) {}; writer.dispose() }
}

// ── Tek pencere — tüm seçimler + durum aynı pencerede ───────────────────────
javafx.application.Platform.runLater {
    try {
        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Görüntü yakala (rapor/sunum)')
        stage.setAlwaysOnTop(true)

        // --- Yakalama türü ---
        def typeGroup = new javafx.scene.control.ToggleGroup()
        def rbViewer = new javafx.scene.control.RadioButton('Görüntüleyici (overlay\'lerle)')
        rbViewer.setToggleGroup(typeGroup); rbViewer.setSelected(true)
        rbViewer.setUserData(SnapshotType.VIEWER)
        def rbWindow = new javafx.scene.control.RadioButton('Tüm QuPath penceresi')
        rbWindow.setToggleGroup(typeGroup); rbWindow.setUserData(SnapshotType.MAIN_SCENE)

        // --- Çıktı hedefi ---
        def outGroup = new javafx.scene.control.ToggleGroup()
        def rbSave = new javafx.scene.control.RadioButton('Dosyaya kaydet')
        rbSave.setToggleGroup(outGroup); rbSave.setSelected(true)
        def rbClip = new javafx.scene.control.RadioButton('Panoya kopyala')
        rbClip.setToggleGroup(outGroup)

        // --- Kayıt seçenekleri (panoda devre dışı) ---
        def dirField = new javafx.scene.control.TextField(defaultDir().getAbsolutePath())
        javafx.scene.layout.HBox.setHgrow(dirField, javafx.scene.layout.Priority.ALWAYS)
        def browseBtn = navButton('Seç…', {
            try {
                def cur = (dirField.getText() ?: '').trim()
                def init = cur ? new File(cur) : null
                def initDir = (init != null && init.isDirectory()) ? init : null
                def chosen = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Çıktı klasörü seçin', initDir)
                if (chosen != null) dirField.setText(chosen.getAbsolutePath())
            } catch (Throwable ignored) { }
        }, 'Görüntünün kaydedileceği klasörü seçin')
        def dirBox = new javafx.scene.layout.HBox(8, dirField, browseBtn)

        def nameField = new javafx.scene.control.TextField('qupath-goruntu')
        nameField.setPromptText('temel dosya adı (uzantısız)')

        def formatBox = new javafx.scene.control.ChoiceBox()
        formatBox.getItems().addAll('PNG', 'JPEG (yüksek kalite)')
        formatBox.getSelectionModel().select(0)

        def cbUnique = new javafx.scene.control.CheckBox('Benzersiz isim (üzerine yazma)')
        cbUnique.setSelected(true)

        def saveBox = new javafx.scene.layout.VBox(8,
            new javafx.scene.control.Label('Klasör:'), dirBox,
            new javafx.scene.control.Label('Dosya adı:'), nameField,
            new javafx.scene.layout.HBox(12,
                new javafx.scene.control.Label('Biçim:'), formatBox, cbUnique))
        def syncSaveEnabled = { -> saveBox.setDisable(rbClip.isSelected()) }
        outGroup.selectedToggleProperty().addListener(
            { obs, o, n -> syncSaveEnabled() } as javafx.beans.value.ChangeListener)
        syncSaveEnabled()

        // --- Durum satırı ---
        def statusLbl = new javafx.scene.control.Label('Hazır.')
        statusLbl.setWrapText(true); statusLbl.setMaxWidth(Double.MAX_VALUE)

        // --- Yakalama eylemi ---
        // makeSnapshot SAHNE GRAFİĞİNDEN çalışır → FX iş parçacığında üretilmeli
        // (buton zaten FX thread'de). Dosya yazımı I/O olduğundan arka plana alınır.
        def doCapture = {
            statusLbl.setStyle('-fx-text-fill: -fx-text-base-color;')
            def type = (SnapshotType) typeGroup.getSelectedToggle().getUserData()
            BufferedImage img
            try {
                img = GuiTools.makeSnapshot(QuPathGUI.getInstance(), type)
            } catch (Throwable t) {
                statusLbl.setStyle('-fx-text-fill: -qp-script-error-color;')
                statusLbl.setText('⚠ Yakalama başarısız: ' + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
                return
            }
            if (img == null) {
                statusLbl.setStyle('-fx-text-fill: -qp-script-error-color;')
                statusLbl.setText('⚠ Yakalanacak içerik bulunamadı (önce bir slayt açın).')
                return
            }

            if (rbClip.isSelected()) {
                try {
                    def cc = new ClipboardContent()
                    cc.putImage(SwingFXUtils.toFXImage(img, null))
                    Clipboard.getSystemClipboard().setContent(cc)
                    statusLbl.setStyle('-fx-text-fill: -fx-text-base-color;')
                    statusLbl.setText(String.format(java.util.Locale.US,
                        '✅ Panoya kopyalandı (%d×%d px). Başka bir uygulamaya yapıştırabilirsiniz.',
                        img.getWidth(), img.getHeight()))
                } catch (Throwable t) {
                    statusLbl.setStyle('-fx-text-fill: -qp-script-error-color;')
                    statusLbl.setText('⚠ Panoya kopyalanamadı: ' + t.getClass().getSimpleName())
                }
                return
            }

            // Dosyaya kaydet — girişleri doğrula
            def dirText = (dirField.getText() ?: '').trim()
            def base = (nameField.getText() ?: '').trim()
            if (dirText.isEmpty() || base.isEmpty()) {
                statusLbl.setStyle('-fx-text-fill: -qp-script-error-color;')
                statusLbl.setText('⚠ Klasör ve dosya adı boş olamaz.')
                return
            }
            def dir = new File(dirText)
            if (!dir.isDirectory()) {
                statusLbl.setStyle('-fx-text-fill: -qp-script-error-color;')
                statusLbl.setText('⚠ Geçerli bir klasör seçin (klasör bulunamadı).')
                return
            }
            boolean jpeg = formatBox.getSelectionModel().getSelectedIndex() == 1
            def ext = jpeg ? 'jpg' : 'png'
            def outFile = confirmFile(dir, base, ext, cbUnique.isSelected())

            statusLbl.setStyle('-fx-text-fill: -fx-text-base-color;')
            statusLbl.setText('Yazılıyor: ' + outFile.getName() + ' …')
            def worker = new Thread({
                String err = null
                try {
                    if (jpeg) writeJpeg(img, outFile, 0.9f)
                    else      ImageIO.write(img, 'png', outFile)
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')
                }
                javafx.application.Platform.runLater {
                    if (err != null) {
                        statusLbl.setStyle('-fx-text-fill: -qp-script-error-color;')
                        statusLbl.setText('⚠ Kaydedilemedi: ' + err)
                    } else {
                        statusLbl.setStyle('-fx-text-fill: -fx-text-base-color;')
                        statusLbl.setText(String.format(java.util.Locale.US,
                            '✅ Kaydedildi (%d×%d px):\n%s', img.getWidth(), img.getHeight(),
                            outFile.getAbsolutePath()))
                        println 'Görüntü yakalandı → ' + outFile.getAbsolutePath()
                    }
                }
            }, 'AtolyeCapture'); worker.setDaemon(true); worker.start()
        }

        // --- Yerleşim ---
        def mk = { String t -> def l = new javafx.scene.control.Label(t); l.setStyle('-fx-font-weight: bold;'); return l }
        def form = new javafx.scene.layout.VBox(10)
        form.setPadding(new javafx.geometry.Insets(14))
        form.getChildren().addAll(
            mk('Yakalama türü'),
            new javafx.scene.layout.VBox(4, rbViewer, rbWindow),
            new javafx.scene.control.Separator(),
            mk('Çıktı'),
            new javafx.scene.layout.HBox(12, rbSave, rbClip),
            saveBox,
            new javafx.scene.control.Separator(),
            statusLbl)

        def alwaysTop = new javafx.scene.control.CheckBox('Üstte tut')
        alwaysTop.setSelected(true)
        alwaysTop.selectedProperty().addListener(
            { obs, o, n -> stage.setAlwaysOnTop(n as boolean) } as javafx.beans.value.ChangeListener)

        def buttons = new javafx.scene.layout.HBox(8,
            alwaysTop,
            navButton('Kapat', { stage.close() }),
            navButton('Yakala ▶', { doCapture() }, 'Seçili türde görüntüyü yakalar'))
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

        // Kalıcı sorumluluk reddi — tema-duyarlı (açık/koyu tema).
        def disclaimer = new javafx.scene.control.Label(
            'Yalnızca araştırma/eğitim amaçlı görsel bir yardımcıdır; ölçüm/klinik karar üretmez.')
        disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
        disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
            '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')

        def bottom = new javafx.scene.layout.VBox(8, disclaimer, buttons)
        bottom.setPadding(new javafx.geometry.Insets(10))

        def root = new javafx.scene.layout.BorderPane()
        root.setCenter(form)
        root.setBottom(bottom)
        stage.setScene(new javafx.scene.Scene(root, 520, 420))
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Görüntü yakala penceresi açılamadı',
            t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}