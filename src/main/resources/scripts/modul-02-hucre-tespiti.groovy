/**
 * Modül 2 - Tek Tıkla Hücre Tespiti
 * ----------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Atölye için "hızlı deneme" betiği. Seçilen anotasyon içindeki tüm
 * çekirdekleri H&E için atölye varsayılan parametreleriyle tespit eder.
 *
 * KULLANIM:
 *   1. Bir H&E slaytı açın
 *   2. [R] tuşu → tümör içeren küçük bir dikdörtgen (~1×1 mm) çizin ve SEÇİN
 *   3. Bu betiği çalıştırın → açılan TEK pencerede "Çalıştır"
 *   4. Pencere açık kalır: eşikleri değiştirip (Gelişmiş ayarlar) tekrar
 *      çalıştırabilir, sonuçları aynı pencerede güncel görebilirsiniz
 *
 * Bu betik atölyenin tek tıkla "wow" anı için yazılmıştır. Aynı parametrelerin
 * her birinin ne işe yaradığını ve nasıl ayarlayacağınızı öğrenmek için
 * web sitesindeki **Modül 2 - Hücre Tespiti** bölümüne dönün.
 *
 * NOT — manuel sayım alternatifi:
 *   Küçük örneklemlerde veya zor durumlarda QuPath'in **Point Tool**'u ile
 *   her hücreyi tek tek tıklayarak manuel sayım yapılabilir; sonuçlar `.tsv`
 *   olarak dışa aktarılır. Arayüz üzerinden manuel/otomatik karşılaştırma için:
 *   cancer-informatics.org/de/docs/ai/qupath_02_Cell-Counting (J. Cieślik et al.)
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathAnnotationObject

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

// ──────────────────────────────────────────────────────────────
// 1) Ön kontrol — açık görüntü var mı?
// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(
        "Görüntü açık değil",
        "Önce bir slayt açın (sol Proje panelinden çift tıklayın), sonra bu betiği tekrar çalıştırın."
    )
    return
}

// "Hematoxylin OD" kanalı yalnızca brightfield + Hematoxylin boyası ayarlanmışsa görünür.
// Aksi halde plugin "Unable to set parameter detectionImageBrightfield" hatası verir.
// Brightfield (other) açıkta ise H&E varsayılanı uygulanır (M2 H&E için yazılmıştır).
def stains = imageData.getColorDeconvolutionStains()
def hasHematoxylin = false
if (stains != null) {
    for (int i = 1; i <= 3; i++) {
        def name = stains.getStain(i)?.getName()?.toLowerCase(java.util.Locale.ROOT)
        if (name != null && name.contains("hematoxylin")) { hasHematoxylin = true; break }
    }
}
if (!hasHematoxylin) {
    println "⚠ Hematoxylin boyası tanımlı değil → BRIGHTFIELD_H_E varsayılanı uygulanıyor."
    QP.setImageType('BRIGHTFIELD_H_E')
}

// ──────────────────────────────────────────────────────────────
// 2) Tespit + sonuç toplama tek yerde — pencere kapanmadan tekrar
//    (eşikleri değiştirip) çağrılabilir. Dönüş: [ok, text] | [ok:false, error].
// ──────────────────────────────────────────────────────────────
def runDetection = { double thr, double sig, double exp ->
    def selected = QP.getSelectedObject()
    if (selected == null || !(selected instanceof PathAnnotationObject))
        return [ok:false, error:'Önce ölçmek istediğiniz dikdörtgen anotasyonu çizip SEÇİN (kenarı sarı görünür).']
    def target = selected
    def cal = imageData.getServer().getPixelCalibration()
    double pw = cal.getPixelWidthMicrons(), ph = cal.getPixelHeightMicrons()
    if (!(pw > 0) || !(ph > 0))
        return [ok:false, error:'Piksel kalibrasyonu yok; mm² hesaplanamaz (Yardımcılar → Kalibrasyon).']
    double pixelSize       = atolyeD('atolye.pixelSize', 0.5)
    double backgroundRadius = atolyeD('atolye.backgroundRadius', 8.0)
    double medianRadius    = atolyeD('atolye.medianRadius', 0.0)
    double minArea         = atolyeD('atolye.minArea', 10.0)
    double maxArea         = atolyeD('atolye.maxArea', 400.0)
    boolean doWatershed    = atolyeB('atolye.watershed', true)
    long t0 = System.currentTimeMillis()
    QP.selectObjects(target)
    QP.runPlugin(
        'qupath.imagej.detect.cells.WatershedCellDetection',
        '{' +
            '"detectionImageBrightfield":"Hematoxylin OD",' +
            '"requestedPixelSizeMicrons":' + pixelSize + ',' +
            '"backgroundRadiusMicrons":' + backgroundRadius + ',' +
            '"medianRadiusMicrons":' + medianRadius + ',' +
            '"sigmaMicrons":' + sig + ',' +
            '"minAreaMicrons":' + minArea + ',' +
            '"maxAreaMicrons":' + maxArea + ',' +
            '"threshold":' + thr + ',' +
            '"watershedPostProcess":' + doWatershed + ',' +
            '"cellExpansionMicrons":' + exp + ',' +
            '"includeNuclei":true,' +
            '"smoothBoundaries":true,' +
            '"makeMeasurements":true' +
        '}'
    )
    double elapsed = (System.currentTimeMillis() - t0) / 1000.0
    int totalCells = target.getChildObjects().findAll { it.isDetection() }.size()
    double areaMm2 = 0.0
    def roi = target.getROI()
    if (roi != null) areaMm2 = (roi.getArea() * pw * ph) / 1_000_000.0
    long density = areaMm2 > 0 ? Math.round(totalCells / areaMm2) : 0L
    def text = String.format(java.util.Locale.US,
        "Parametreler : eşik=%.2f  sigma=%.2f  genişletme=%.1f µm\n" +
        "────────────────────────────\n" +
        "  Toplam hücre        : %,d\n" +
        "  Anotasyon alanı     : %.2f mm²\n" +
        "  Hücre yoğunluğu     : ~%,d hücre/mm²\n" +
        "  Süre                : %.1f sn",
        thr, sig, exp, totalCells, areaMm2, density, elapsed)
    return [ok:true, text:text]
}

// Headless: tek sefer atölye varsayılanlarıyla çalıştır + yazdır.
if (isHeadless) {
    def r = runDetection(atolyeD('atolye.detectionThreshold', 0.1), atolyeD('atolye.sigma', 1.5), atolyeD('atolye.cellExpansionNuclear', 5.0))
    println r.ok ? r.text : ("Hata: " + r.error)
    return
}

// ──────────────────────────────────────────────────────────────
// 3) Tek pencere: ayarla → Çalıştır → sonuç → (gerekirse) tekrar
// ──────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Modül 2 - Hızlı hücre tespiti')
        stage.setAlwaysOnTop(true)

        def title = new javafx.scene.control.Label('Hızlı hücre tespiti')
        title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
        def info = new javafx.scene.control.Label(
            'Bir dikdörtgen anotasyon (R) çizip SEÇİN (kenarı sarı), sonra "Çalıştır".\n' +
            'Eşikleri değiştirip yeniden çalıştırabilirsiniz; sonuç aşağıda güncellenir.\n' +
            'Perde arkası: dekonvolüsyon → Gaussian (σ) yumuşatma → eşik (ikili maske) → ' +
            'mesafe dönüşümü + watershed (bitişik çekirdekleri ayır). Bu adımları kendi ' +
            'slaydınızda görmek için: Yardımcılar → Görüntü işleme kavramları.')
        info.setWrapText(true)

        def spThr = new javafx.scene.control.Spinner(0.0, 1.0, atolyeD('atolye.detectionThreshold', 0.1), 0.01)
        def spSig = new javafx.scene.control.Spinner(0.5, 5.0, atolyeD('atolye.sigma', 1.5), 0.1)
        def spExp = new javafx.scene.control.Spinner(0.0, 20.0, atolyeD('atolye.cellExpansionNuclear', 5.0), 0.5)
        [spThr, spSig, spExp].each { it.setEditable(true); it.setPrefWidth(110) }
        // Her parametrenin "perde arkası" görüntü-işleme karşılığı (Bankhead, dijital
        // patoloji için görüntü-işleme sözlüğü) — kavramı kullanım anında açıklar.
        spThr.setTooltip(new javafx.scene.control.Tooltip(
            'İkili maske eşiği: Hematoxylin OD bu değerin ÜSTÜNDEKİ pikseller çekirdek\n' +
            'adayı olur. Eşik, bulunan nesnelerin sayısını ve şeklini doğrudan değiştirir\n' +
            '(Bankhead: binary image / eşikleme). Eşik tek başına yetmez — devamında\n' +
            'mesafe dönüşümü + watershed bitişik çekirdekleri ayırır.'))
        spSig.setTooltip(new javafx.scene.control.Tooltip(
            'Gaussian filtre: σ (sigma) büyüdükçe görüntü daha çok yumuşatılır.\n' +
            'Daha büyük σ aşırı bölünmeyi azaltır ama bitişik çekirdekleri birleştirebilir;\n' +
            'çok küçük σ tek bir çekirdeği parçalara bölebilir.'))
        spExp.setTooltip(new javafx.scene.control.Tooltip(
            'ROI genişletme (µm): çekirdek maskesini dışa doğru büyüterek yaklaşık bir\n' +
            'hücre/sitoplazma bölgesi tanımlar. 0 = yalnız çekirdek.'))
        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(6); grid.setPadding(new javafx.geometry.Insets(6))
        grid.addRow(0, new javafx.scene.control.Label('Eşik (Hematoxylin OD)'), spThr)
        grid.addRow(1, new javafx.scene.control.Label('Sigma (µm)'), spSig)
        grid.addRow(2, new javafx.scene.control.Label('Hücre genişletme (µm)'), spExp)
        def adv = new javafx.scene.control.TitledPane('⚙ Gelişmiş ayarlar — eşikler', grid)
        adv.setExpanded(false); adv.setAnimated(false)

        def status = new javafx.scene.control.Label('Hazır.')
        def progress = new javafx.scene.control.ProgressBar()
        progress.setMaxWidth(Double.MAX_VALUE); progress.setVisible(false); progress.setManaged(false)
        def resultArea = new javafx.scene.control.TextArea()
        resultArea.setEditable(false); resultArea.setWrapText(false); resultArea.setPrefRowCount(8)
        resultArea.setPromptText('Sonuçlar burada görünecek…')
        resultArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;")

        def runBtn = new javafx.scene.control.Button('Çalıştır'); runBtn.setDefaultButton(true)
        runBtn.setOnAction({
            runBtn.setDisable(true)
            status.setStyle(''); status.setText('Hücreler tespit ediliyor…')
            progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
            double thr = spThr.getValue() as double
            double sig = spSig.getValue() as double
            double exp = spExp.getValue() as double
            def worker = new Thread({
                def res = runDetection(thr, sig, exp)
                javafx.application.Platform.runLater {
                    progress.setVisible(false); progress.setManaged(false); runBtn.setDisable(false)
                    if (res.ok) {
                        status.setStyle(''); status.setText('Tamamlandı ✅ — eşikleri değiştirip tekrar çalıştırabilirsiniz.')
                        resultArea.setText(res.text)
                    } else {
                        status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + res.error)
                    }
                }
            }, 'Modul2Detect')
            worker.setDaemon(true); worker.start()
        })

        def alwaysTop = new javafx.scene.control.CheckBox('Üstte tut'); alwaysTop.setSelected(true)
        alwaysTop.selectedProperty().addListener(
            { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
        def copyBtn = new javafx.scene.control.Button('Kopyala')
        copyBtn.setOnAction({
            def cb = javafx.scene.input.Clipboard.getSystemClipboard()
            def c = new javafx.scene.input.ClipboardContent(); c.putString(resultArea.getText()); cb.setContent(c)
        })
        def closeBtn = new javafx.scene.control.Button('Kapat'); closeBtn.setOnAction({ stage.close() })

        def footer = new javafx.scene.control.Label('QuPath Atölye Scriptleri · araştırma/eğitim amaçlı')
        footer.setMaxWidth(Double.MAX_VALUE)
        footer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;')

        def spacer = new javafx.scene.layout.Region()
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
        def btnRow = new javafx.scene.layout.HBox(8, alwaysTop, spacer, copyBtn, runBtn, closeBtn)
        btnRow.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

        def content = new javafx.scene.layout.VBox(10, title, info, adv, status, progress, resultArea)
        content.setPadding(new javafx.geometry.Insets(14))
        javafx.scene.layout.VBox.setVgrow(resultArea, javafx.scene.layout.Priority.ALWAYS)
        def bottom = new javafx.scene.layout.VBox(8, footer, btnRow)
        bottom.setPadding(new javafx.geometry.Insets(10))
        def root = new javafx.scene.layout.BorderPane()
        root.setCenter(content); root.setBottom(bottom)
        stage.setScene(new javafx.scene.Scene(root, 560, 520))
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Modül 2 açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
