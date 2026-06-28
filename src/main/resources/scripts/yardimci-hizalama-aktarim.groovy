/**
 * Yardımcı - Hizalama dönüşümüyle anotasyon aktar (afin)
 * -----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Aynı projedeki bir KAYNAK slaytın üst düzey anotasyonlarını, bir afin
 *   dönüşüm matrisi uygulayarak şu anki (HEDEF) slayda kopyalar. Matris,
 *   QuPath'in yerleşik [Analyze → Interactive image alignment] komutunun
 *   gösterdiği 6 sayıdır (m00 m01 m02 m10 m11 m12). Böylece H&E üzerinde
 *   çizdiğiniz tümör/bölge anotasyonunu, hizaladığınız İHK slaydına TEK
 *   TIKLA ve YİNELENEBİLİR biçimde aktarırsınız — GUI'de elle aktarmaya
 *   gerek kalmaz.
 *
 *   Kavram + yöntem: Pete Bankhead'in "transfer objects between images"
 *   betiği (gist c696ffb…) — entry.readHierarchy() ile kaynak hiyerarşiyi
 *   okur, PathObjectTools.transformObject(...) ile her ROI'ye afin dönüşümü
 *   uygular, alt nesneleri özyinelemeli taşır. bkz. Ekler → Görüntü Hizalama §6.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Yalnız nesneleri TAŞIR (koordinat dönüşümü). Hizalama kalitesini,
 *     patoloji yorumunu veya klinik yeterliliği üretmez. Aktarılan sınır,
 *     seri kesitlerde YAKLAŞIK aynı bölgeyi gösterir (birebir hücre değil).
 *     Örtüşmeyi her zaman opaklık kaydırıcısıyla GÖZLE doğrulayın.
 *   • Esnek (non-rigid) deformasyon için afin yetmez → Warpy (Ek § Warpy).
 *
 * KULLANIM:
 *   1. Kaynak (anotasyonların çizili olduğu) ve hedef slayt AYNI projede olsun.
 *   2. Hedef slaydı açın; [Analyze → Interactive image alignment] ile hizalayıp
 *      pencerede görünen afin matrisini (6 sayı) kopyalayın.
 *   3. Bu yardımcıyı çalıştırın → kaynak slaydı seçin, matrisi yapıştırın, Aktar.
 *
 * ÇIKTI:
 *   • Kaynaktan dönüştürülmüş, KİLİTLİ anotasyonlar (ad: "↪ Hizalama aktarımı")
 *   • Aktarılan nesne sayısını gösteren özet
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjectTools
import java.awt.geom.AffineTransform

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

String SENTINEL = '↪ Hizalama aktarımı'

// ── Özyinelemeli dönüşüm (Bankhead gist deseni): nesneyi + tüm alt nesnelerini taşı
def transformRecursive
transformRecursive = { PathObject src, AffineTransform tf, boolean copyMeas ->
    def out = PathObjectTools.transformObject(src, tf, copyMeas)
    // Yalnız alt ANOTASYONLARI taşı; kaynak hücre tespitlerini (binlerce) sürüklemeyiz.
    def childAnns = src.getChildObjectsAsArray().findAll { it.isAnnotation() }
    if (!childAnns.isEmpty())
        out.addPathObjects(childAnns.collect { transformRecursive(it, tf, copyMeas) })
    return out
}

// ── 6 sayılık matrisi ayrıştır (boşluk / virgül / noktalı virgül / satır ayrılmış)
def parseMatrix = { String text ->
    if (text == null) return null
    def toks = text.trim().split('[\\s,;]+').findAll { it }
    if (toks.size() != 6) return null
    try { return toks.collect { Double.parseDouble(it) } } catch (Throwable t) { return null }
}

// ── Ön kontroller ───────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Aktarımın HEDEF slaytını açın (anotasyonların kopyalanacağı slayt).")
    return
}
def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage("Proje açık değil", "Kaynak ve hedef slayt AYNI QuPath projesinde olmalıdır.")
    return
}
def entries = project.getImageList()
if (entries == null || entries.isEmpty()) {
    Dialogs.showErrorMessage("Projede slayt yok", "Hizalama aktarımı için projede en az bir kaynak slayt olmalı.")
    return
}
if (isHeadless) {
    println "Bu yardımcı QuPath arayüzü gerektirir (kaynak slayt seçimi + matris girişi)."
    return
}

// ── Aktarım işi (arka planda; kaynak hiyerarşi okuma dosya IO'su yapar) ───
def doTransfer = { String sourceName, List<Double> m, boolean inv, boolean copyMeas, boolean lock ->
    try {
        def entry = project.getImageList().find { it.getImageName() == sourceName }
        if (entry == null) return [ok:false, error:"Kaynak slayt bulunamadı: ${sourceName}"]
        def srcHier
        try { srcHier = entry.readHierarchy() }
        catch (Throwable t) { return [ok:false, error:"Kaynak hiyerarşi okunamadı (${t.getClass().getSimpleName()}). Kaynak slaydı projede bir kez açıp kaydetmeyi deneyin."] }
        def srcAnns = srcHier.getRootObject().getChildObjectsAsArray().findAll { it.isAnnotation() }
        if (srcAnns.isEmpty()) return [ok:false, error:"Kaynak slaytta üst düzey anotasyon yok: ${sourceName}"]

        // Matris sırası: dialog m00 m01 m02 / m10 m11 m12 → AffineTransform(m00,m10,m01,m11,m02,m12)
        def tf = new AffineTransform(m[0], m[3], m[1], m[4], m[2], m[5])
        if (inv) {
            try { tf = tf.createInverse() }
            catch (Throwable t) { return [ok:false, error:"Matrisin tersi alınamadı (tekil matris). 'Ters çevir' kutusunu kapatmayı deneyin."] }
        }

        def newObjs = srcAnns.collect { transformRecursive(it, tf, copyMeas) }
        newObjs.each { o -> o.setName(SENTINEL); if (lock) o.setLocked(true) }

        // Önceki aktarımı (aynı ada sahip) temizle → yeniden çalıştırılabilir
        QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == SENTINEL }, false)
        QP.addObjects(newObjs)
        QP.fireHierarchyUpdate()
        return [ok:true, count:newObjs.size(), source:sourceName]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
    }
}

// ── Tek pencere form ─────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Hizalama dönüşümüyle anotasyon aktar')
        stage.setAlwaysOnTop(true)

        def info = new javafx.scene.control.Label(
            'Kaynak slaytı seçin ve [Analyze → Interactive image alignment] penceresindeki afin\n' +
            'matrisini (6 sayı) yapıştırın. Kaynağın üst düzey anotasyonları bu slayda kilitli\n' +
            'olarak kopyalanır. Örtüşmeyi sonra opaklık kaydırıcısıyla gözle doğrulayın.')
        info.setWrapText(true)

        def sourceBox = new javafx.scene.control.ChoiceBox()
        entries.each { sourceBox.getItems().add(it.getImageName()) }
        if (!sourceBox.getItems().isEmpty()) sourceBox.getSelectionModel().selectFirst()

        def matrixField = new javafx.scene.control.TextArea()
        matrixField.setPromptText('ör.  -0.998  -0.070  127256.994  0.070  -0.998  72627.371')
        matrixField.setPrefRowCount(2); matrixField.setWrapText(true)

        def invChk  = new javafx.scene.control.CheckBox('Ters çevir (konum yanlış çıkarsa açık tutun)'); invChk.setSelected(true)
        def measChk = new javafx.scene.control.CheckBox('Ölçümleri kopyala'); measChk.setSelected(true)
        def lockChk = new javafx.scene.control.CheckBox('Aktarılanları kilitle'); lockChk.setSelected(true)

        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(8); grid.setPadding(new javafx.geometry.Insets(4))
        grid.addRow(0, new javafx.scene.control.Label('Kaynak slayt:'), sourceBox)
        grid.addRow(1, new javafx.scene.control.Label('Afin matris (6 sayı):'), matrixField)
        javafx.scene.layout.GridPane.setHgrow(matrixField, javafx.scene.layout.Priority.ALWAYS)
        javafx.scene.layout.GridPane.setHgrow(sourceBox, javafx.scene.layout.Priority.ALWAYS)
        sourceBox.setMaxWidth(Double.MAX_VALUE)

        def status = new javafx.scene.control.Label('Hazır.'); status.setWrapText(true)
        def aktarBtn = new javafx.scene.control.Button('Aktar'); aktarBtn.setDefaultButton(true)
        def kapatBtn = new javafx.scene.control.Button('Kapat'); kapatBtn.setOnAction({ stage.close() })

        aktarBtn.setOnAction({
            def src = sourceBox.getValue()
            if (!src) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('Önce kaynak slaytı seçin.'); return }
            def m = parseMatrix(matrixField.getText())
            if (m == null) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('Matris tam 6 sayı olmalı (boşluk/virgül ayrılmış).'); return }
            aktarBtn.setDisable(true); status.setStyle(''); status.setText('Aktarılıyor…')
            def worker = new Thread({
                def r = doTransfer(src, m, invChk.isSelected(), measChk.isSelected(), lockChk.isSelected())
                javafx.application.Platform.runLater {
                    aktarBtn.setDisable(false)
                    if (r.ok) {
                        status.setStyle(''); status.setText(String.format(java.util.Locale.US,
                            'Tamamlandı ✅ — %,d anotasyon aktarıldı. Örtüşmeyi gözle doğrulayın.', r.count))
                        def msg = new StringBuilder()
                        msg << String.format(java.util.Locale.US, 'Kaynak: %s%n', r.source)
                        msg << String.format(java.util.Locale.US, 'Aktarılan anotasyon: %,d (kilitli; ad "%s")%n', r.count, SENTINEL)
                        msg << '\nAktarılan sınır seri kesitte YAKLAŞIK aynı bölgeyi gösterir; örtüşmeyi\n'
                        msg << 'opaklık kaydırıcısıyla doğrulayın. Hücre-hücre kesinlik için Warpy kullanın.\n'
                        msg << '⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.'
                        Dialogs.showMessageDialog('Hizalama aktarımı tamamlandı', msg.toString())
                    } else {
                        status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + (r.error ?: 'Bilinmeyen hata'))
                    }
                }
            }, 'HizalamaAktarim'); worker.setDaemon(true); worker.start()
        })

        def spacer = new javafx.scene.layout.Region()
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
        def buttons = new javafx.scene.layout.HBox(8, spacer, kapatBtn, aktarBtn)
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

        def disclaimer = new javafx.scene.control.Label('⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar; klinik karar üretmez.')
        disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
        disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-font-size: 11px; -fx-padding: 4 2 4 2;')

        def content = new javafx.scene.layout.VBox(10, info, grid, new javafx.scene.layout.HBox(16, invChk, measChk, lockChk), status)
        content.setPadding(new javafx.geometry.Insets(14))
        def root = new javafx.scene.layout.BorderPane()
        root.setCenter(content)
        def bottom = new javafx.scene.layout.VBox(8, disclaimer, buttons)
        bottom.setPadding(new javafx.geometry.Insets(10))
        root.setBottom(bottom)
        stage.setScene(new javafx.scene.Scene(root, 600, 360))
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Hizalama aktarımı açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
