/**
 * Modül 3a - Tek Tıkla Ki-67 / Nükleer İHK Kantifikasyonu
 * --------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Atölye için "hızlı deneme" betiği. Seçilen anotasyon içinde
 * Ki-67 ve benzeri nükleer DAB boyamalarını (p53 hariç — marker-özgü bir
 * iş akışı gerektirir) otomatik olarak skorlar ve **Ki-67 LI**
 * (etiketleme indeksi) ile grup dağılımını kaydeder.
 *
 * KULLANIM:
 *   1. Ki-67 (veya başka nükleer DAB) İHK slaytını açın
 *   2. Image type → "Brightfield (H-DAB)" olduğundan emin olun
 *      ([Image → Image type → Brightfield (H-DAB)])
 *   3. [R] tuşu → tümör içeren ~1×1 mm dikdörtgen anotasyon çizin ve SEÇİN
 *   4. Bu betiği çalıştırın → açılan TEK pencerede "Çalıştır"
 *   5. Pencere açık kalır: eşikleri değiştirip (Gelişmiş ayarlar) tekrar
 *      çalıştırabilir, sonuçları aynı pencerede güncel görebilirsiniz
 *
 * NE YAPAR:
 *   • Atölye boya vektörleri ile çekirdek-yoğun DAB sinyalini ayırır
 *   • Her çekirdeği Negative / 1+ / 2+ / 3+ olarak sınıflar
 *     (eşikler: 0.2 / 0.4 / 0.6 OD)
 *   • Pozitif yüzdesini (Ki-67 LI), grup dağılımını ve hücre yoğunluğunu hesaplar
 *
 * NE YAPMAZ:
 *   • Boya vektörlerinizi otomatik tahmin etmez — önceden ayarlanmış olmalı
 *     ya da QuPath'in varsayılan H-DAB vektörlerini kullanır
 *
 * YÖNTEM REFERANSLARI:
 *   • Nielsen TO et al. (2021), J Natl Cancer Inst — Ki-67 Working Group sayma standardı
 *     (≥500-1.000 tümör hücresi). doi:10.1093/jnci/djaa201
 *   • Skjervold AH et al. (2022), Diagn Pathol — manuel vs dijital uyum
 *     doi:10.1186/s13000-022-01225-4
 *   • Acs B et al. (2018), Lab Invest 99(1):107–117 — platform/gözlemci arası
 *     Ki-67 yeniden-üretilebilirliği. doi:10.1038/s41374-018-0123-7
 *   • Catteau X et al. (2023), Technol Cancer Res Treat — dijital Ki-67 yalnızca
 *     patolog-işaretli bölgede manuel skorla uyumlu. doi:10.1177/15330338231169603
 *   • Spyretos C et al. (2026), J Neuropathol Exp Neurol 85(5):475–486 — StarDist
 *     ile tam otomatik, proje-ölçeğinde Ki-67 LI. doi:10.1093/jnen/nlaf163
 *   • Arayüz tarafı eğitimi (cancer-informatics.org, J. Cieślik et al., CC-BY-SA):
 *     cancer-informatics.org/de/docs/ai/qupath_04_ki67_index
 *
 * EŞİK HASSASİYETİ:
 *   • Pozitif %, aynı slaytta yalnızca tespit + DAB eşiği değiştiğinde ~%3.7 →
 *     ~%24 arası oynayabilir ve tarayıcıya göre farklılaşır (Bankhead 2022
 *     parametre-duyarlılık örneği). Karşılaştırılan tüm slaytlarda aynı
 *     parametreleri kullanın; boya vektörlerini her tarayıcı için yeniden
 *     kestirin ([Analyze → Estimate stain vectors]).
 */

import qupath.lib.gui.dialogs.Dialogs
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
// 1) Ön kontroller
// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(
        "Görüntü açık değil",
        "Önce bir Ki-67 İHK slaytı açın, sonra bu betiği tekrar çalıştırın."
    )
    return
}

// Image type kontrolü (Brightfield (H-DAB) zorunlu — DAB ayrımı için).
// Modal pencere yerine: aşağıdaki pencere her zaman açılır; tip yanlışsa kullanıcı
// pencere içinde yönlendirilir, Image panelinden tipi düzeltip "Çalıştır"a tekrar
// basabilir (her çalıştırmada yeniden denetlenir — pencere kapanmaz).
def imageTypeOk = { ->
    def n = (imageData.getImageType()?.toString() ?: "")
        .toUpperCase(java.util.Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
    n.contains('H_DAB')
}
def imageTypeGuidance = { ->
    "Bu slayt 'Brightfield (H-DAB)' olarak ayarlı değil.\n" +
    "Şu anki tip: ${imageData.getImageType()?.toString() ?: ''}\n\n" +
    "Çözüm:\n" +
    "  1. Image panelini açın (sol-üst)\n" +
    "  2. 'Image type' → 'Brightfield (H-DAB)' seçin (DAB ayrımı için gerekli)\n" +
    "  3. Yukarıdaki 'Çalıştır' düğmesine tekrar basın"
}

// H-DAB ise ve boya vektörleri eksikse atölye varsayılanını uygula.
// (Yanlış tipteki slaytları otomatik dönüştürmeyiz — yönlendirme pencerede gösterilir.)
if (imageTypeOk()) {
    def stains = imageData.getColorDeconvolutionStains()
    def hasHematoxylin = false
    if (stains != null) {
        for (int i = 1; i <= 3; i++) {
            def name = stains.getStain(i)?.getName()?.toLowerCase(java.util.Locale.ROOT)
            if (name != null && name.contains("hematoxylin")) { hasHematoxylin = true; break }
        }
    }
    if (!hasHematoxylin) {
        println "⚠ H-DAB boya vektörleri tanımlı değil → BRIGHTFIELD_H_DAB varsayılanı uygulanıyor."
        QP.setImageType('BRIGHTFIELD_H_DAB')
    }
}

// ──────────────────────────────────────────────────────────────
// 2) Tespit + sonuç toplama tek yerde — pencere kapanmadan tekrar
//    (eşikleri değiştirip) çağrılabilir. Dönüş: [ok, text] | [ok:false, error].
// ──────────────────────────────────────────────────────────────
def runDetection = { double thr1, double thr2, double thr3 ->
    // Görüntü tipini her çalıştırmada yeniden denetle (kullanıcı bu arada düzeltmiş olabilir).
    if (!imageTypeOk())
        return [ok:false, error: imageTypeGuidance(), imageTypeIssue:true]
    def selected = QP.getSelectedObject()
    if (selected == null || !(selected instanceof PathAnnotationObject))
        return [ok:false, error:'Önce ölçmek istediğiniz dikdörtgen anotasyonu çizip SEÇİN (kenarı sarı görünür).']
    def targetAnnotation = selected
    def cal = imageData.getServer().getPixelCalibration()
    def pixelWidth  = cal.getPixelWidthMicrons()
    def pixelHeight = cal.getPixelHeightMicrons()
    if (!(pixelWidth > 0) || !(pixelHeight > 0))
        return [ok:false, error:'Piksel kalibrasyonu yok; mm² hesaplanamaz (Yardımcılar → Kalibrasyon).']

    def detectionImageBrightfield = atolyeS('atolye.detectionChannel', 'Hematoxylin OD')
    def requestedPixelSizeMicrons = atolyeD('atolye.pixelSize', 0.5)
    def backgroundRadiusMicrons   = atolyeD('atolye.backgroundRadius', 8.0)
    def medianRadiusMicrons       = atolyeD('atolye.medianRadius', 0.0)
    def sigmaMicrons              = atolyeD('atolye.sigma', 1.5)
    def minAreaMicrons            = atolyeD('atolye.minArea', 10.0)
    def maxAreaMicrons            = atolyeD('atolye.maxArea', 400.0)
    def detectionThreshold        = atolyeD('atolye.detectionThreshold', 0.1)
    def watershedPostProcess      = atolyeB('atolye.watershed', true)
    def cellExpansionMicrons      = atolyeD('atolye.cellExpansionNuclear', 5.0)
    def warnNuclearCount          = atolyeI('atolye.warnNuclearCount', 500)

    long t0 = System.currentTimeMillis()
    QP.selectObjects(targetAnnotation)

    // Tespit kanalı seçimi — yöntemsel not:
    //   "Hematoxylin OD" → çekirdek tespiti hematoksilin sinyali üzerinden (varsayılan).
    //     Yüksek-LI Ki-67'de güçlü DAB hematoksilin sinyalini bastırabilir, bazı
    //     pozitif çekirdekler kaçabilir.
    //   "Optical density sum" → H + DAB + Eozin OD kombinasyonu; ASCO/cancer-informatics
    //     eğitimleri Ki-67 için bu kanalı önerir (DAB-yoğun pozitiflerde daha güvenli).
    //     Trade-off: arka plan gürültüsüne biraz daha duyarlı; eşiklerin yeniden
    //     kalibre edilmesi gerekebilir.
    // Atölye varsayılanı "Hematoxylin OD" — düşük-orta LI'da daha temiz segmentasyon verir.
    // Yüksek-LI vakada WorkshopPrefs'ten 'atolye.detectionChannel' → 'Optical density sum' yapın
    // ve eşikleri referans hücrelerde yeniden test edin.
    QP.runPlugin(
        'qupath.imagej.detect.cells.PositiveCellDetection',
        '{' +
            '"detectionImageBrightfield":"' + detectionImageBrightfield + '",' +
            '"requestedPixelSizeMicrons":' + requestedPixelSizeMicrons + ',' +
            '"backgroundRadiusMicrons":' + backgroundRadiusMicrons + ',' +
            '"medianRadiusMicrons":' + medianRadiusMicrons + ',' +
            '"sigmaMicrons":' + sigmaMicrons + ',' +
            '"minAreaMicrons":' + minAreaMicrons + ',' +
            '"maxAreaMicrons":' + maxAreaMicrons + ',' +
            '"threshold":' + detectionThreshold + ',' +
            '"watershedPostProcess":' + watershedPostProcess + ',' +
            '"cellExpansionMicrons":' + cellExpansionMicrons + ',' +
            '"includeNuclei":true,' +
            '"smoothBoundaries":true,' +
            '"makeMeasurements":true,' +
            '"thresholdCompartment":"Nucleus: DAB OD mean",' +
            '"thresholdPositive1":' + thr1 + ',' +
            '"thresholdPositive2":' + thr2 + ',' +
            '"thresholdPositive3":' + thr3 + ',' +
            '"singleThreshold":false' +
        '}'
    )

    double elapsed = (System.currentTimeMillis() - t0) / 1000.0

    // Sonuçları topla — her bin için sayım
    def cells = targetAnnotation.getChildObjects().findAll { it.isDetection() }
    def totalCells = cells.size()

    def numNegative = 0
    def num1Plus = 0
    def num2Plus = 0
    def num3Plus = 0

    cells.each { c ->
        def cls = c.getPathClass()?.getName() ?: ""
        if (cls.contains("3+"))      num3Plus++
        else if (cls.contains("2+")) num2Plus++
        else if (cls.contains("1+")) num1Plus++
        else                          numNegative++
    }

    def numPositive = num1Plus + num2Plus + num3Plus
    def ki67LI = totalCells > 0 ? 100.0 * numPositive / totalCells : 0.0

    // Alan ve yoğunluk
    def roi = targetAnnotation.getROI()
    def totalAreaMm2 = roi != null
        ? (roi.getArea() * pixelWidth * pixelHeight) / 1_000_000.0
        : 0.0
    def density = totalAreaMm2 > 0 ? Math.round(totalCells / totalAreaMm2) : 0L

    // Örneklem boyutu uyarısı — International Ki-67 in Breast Cancer Working Group (Nielsen 2021)
    // minimum 500-1000 tümör hücresi sayılmasını metodoloji standardı olarak önerir.
    // Klinik yorum değil, ölçüm hassasiyeti notu.
    def uyari = ""
    if (totalCells < warnNuclearCount) {
        uyari = String.format(java.util.Locale.US,
            "\n📝 Not: %,d hücre <500 — Ki-67 Working Group (Nielsen 2021) sayma standardının altında.\n" +
            "  Daha büyük bir ROI ile tekrar deneyin (hedef: ≥500-1.000 hücre).",
            totalCells)
    } else if (totalCells > 50000) {
        uyari = String.format(java.util.Locale.US,
            "\n📝 Not: %,d hücre çok fazla — ROI küçültmek hesaplama hızını artırır.",
            totalCells)
    }

    def text = String.format(java.util.Locale.US,
        "Eşikler: 1+=%.2f  2+=%.2f  3+=%.2f (Nucleus: DAB OD mean)\n" +
        "────────────────────────────────────\n" +
        "📊 Sayım sonuçları\n" +
        "────────────────────\n" +
        "  Toplam hücre        : %,d\n" +
        "  Negatif             : %,d  (%%%.1f)\n" +
        "  1+ (zayıf)          : %,d  (%%%.1f)\n" +
        "  2+ (orta)           : %,d  (%%%.1f)\n" +
        "  3+ (güçlü)          : %,d  (%%%.1f)\n\n" +
        "🎯 Metrikler\n" +
        "─────────────\n" +
        "  Ki-67 LI (Pozitif%%)  : %%%.1f\n" +
        "  Hücre yoğunluğu       : ~%,d hücre/mm²\n" +
        "  Anotasyon alanı       : %.2f mm²\n" +
        "  Süre                  : %.1f sn\n" +
        "%s\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
        thr1, thr2, thr3,
        totalCells,
        numNegative, totalCells > 0 ? 100.0 * numNegative / totalCells : 0.0,
        num1Plus,    totalCells > 0 ? 100.0 * num1Plus / totalCells : 0.0,
        num2Plus,    totalCells > 0 ? 100.0 * num2Plus / totalCells : 0.0,
        num3Plus,    totalCells > 0 ? 100.0 * num3Plus / totalCells : 0.0,
        ki67LI, density, totalAreaMm2, elapsed, uyari
    )

    println "─────────────────────────────────────"
    println "Modül 3a - Ki-67 / Nükleer İHK"
    println "─────────────────────────────────────"
    println "  Toplam: ${totalCells}  |  Pozitif: ${numPositive}  |  Ki-67 LI: ${String.format(java.util.Locale.US, '%.1f', ki67LI)}%"
    println "  Yoğunluk: ${density}/mm²  |  Süre: ${elapsed} sn"
    println "─────────────────────────────────────"

    return [ok:true, text:text]
}

// Headless: tek sefer atölye varsayılanlarıyla çalıştır + yazdır.
if (isHeadless) {
    def r = runDetection(
        atolyeD('atolye.nuclear1', 0.20),
        atolyeD('atolye.nuclear2', 0.40),
        atolyeD('atolye.nuclear3', 0.60)
    )
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
        stage.setTitle('Modül 3a - Ki-67 / Nükleer İHK kantifikasyonu')
        stage.setAlwaysOnTop(true)

        def title = new javafx.scene.control.Label('Ki-67 / Nükleer İHK kantifikasyonu')
        title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
        def info = new javafx.scene.control.Label(
            'Bir dikdörtgen anotasyon (R) çizip SEÇİN (kenarı sarı), sonra "Çalıştır".\n' +
            'Eşikleri değiştirip yeniden çalıştırabilirsiniz; sonuç aşağıda güncellenir.')
        info.setWrapText(true)

        def spThr1 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.nuclear1', 0.20), 0.01)
        def spThr2 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.nuclear2', 0.40), 0.01)
        def spThr3 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.nuclear3', 0.60), 0.01)
        [spThr1, spThr2, spThr3].each { it.setEditable(true); it.setPrefWidth(110) }
        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(6); grid.setPadding(new javafx.geometry.Insets(6))
        grid.addRow(0, new javafx.scene.control.Label('1+ eşiği (zayıf, DAB OD)'), spThr1)
        grid.addRow(1, new javafx.scene.control.Label('2+ eşiği (orta, DAB OD)'),  spThr2)
        grid.addRow(2, new javafx.scene.control.Label('3+ eşiği (güçlü, DAB OD)'), spThr3)
        def adv = new javafx.scene.control.TitledPane('⚙ Gelişmiş ayarlar — eşikler', grid)
        adv.setExpanded(false); adv.setAnimated(false)

        def status = new javafx.scene.control.Label('Hazır.')
        def progress = new javafx.scene.control.ProgressBar()
        progress.setMaxWidth(Double.MAX_VALUE); progress.setVisible(false); progress.setManaged(false)
        def resultArea = new javafx.scene.control.TextArea()
        resultArea.setEditable(false); resultArea.setWrapText(false); resultArea.setPrefRowCount(10)
        resultArea.setPromptText('Sonuçlar burada görünecek…')
        resultArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;")

        // İlk açılışta görüntü tipi yanlışsa kullanıcıyı pencere içinde yönlendir (modal yok).
        if (!imageTypeOk()) {
            status.setStyle('-fx-text-fill: -qp-script-error-color;')
            status.setText("⚠ Görüntü tipi 'Brightfield (H-DAB)' değil — adımlar aşağıda.")
            resultArea.setText(imageTypeGuidance())
        }

        def runBtn = new javafx.scene.control.Button('Çalıştır'); runBtn.setDefaultButton(true)
        runBtn.setOnAction({
            runBtn.setDisable(true)
            status.setStyle(''); status.setText('Çalışıyor…')
            progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
            double thr1 = spThr1.getValue() as double
            double thr2 = spThr2.getValue() as double
            double thr3 = spThr3.getValue() as double
            def worker = new Thread({
                def res = runDetection(thr1, thr2, thr3)
                javafx.application.Platform.runLater {
                    progress.setVisible(false); progress.setManaged(false); runBtn.setDisable(false)
                    if (res.ok) {
                        status.setStyle(''); status.setText('Tamamlandı ✅ — eşikleri değiştirip tekrar çalıştırabilirsiniz.')
                        resultArea.setText(res.text)
                    } else if (res.imageTypeIssue) {
                        status.setStyle('-fx-text-fill: -qp-script-error-color;')
                        status.setText("⚠ Görüntü tipi 'Brightfield (H-DAB)' değil — adımlar aşağıda.")
                        resultArea.setText(res.error)
                    } else {
                        status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + res.error)
                    }
                }
            }, 'Modul3Detect')
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
        stage.setScene(new javafx.scene.Scene(root, 560, 540))
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Modül 3a açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
