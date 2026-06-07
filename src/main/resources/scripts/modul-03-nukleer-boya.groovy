/**
 * Modül 3 - Tek Tıkla Ki-67 / Nükleer İHK Kantifikasyonu
 * --------------------------------------------------------
 * Atölye için "hızlı deneme" betiği. Seçilen anotasyon içinde
 * Ki-67 / p53 / başka bir nükleer İHK boyamasını otomatik olarak skorlar
 * ve **Ki-67 LI** (etiketleme indeksi) ile grup dağılımını kaydeder.
 *
 * KULLANIM:
 *   1. Ki-67 (veya başka nükleer DAB) İHK slaytını açın
 *   2. Image type → "Brightfield (H-DAB)" olduğundan emin olun
 *      ([Image → Image type → Brightfield (H-DAB)])
 *   3. [R] tuşu → tümör içeren ~1×1 mm dikdörtgen anotasyon çizin
 *   4. Anotasyon seçili iken → [Automate → Project scripts → bu betik]
 *   5. Sonuçları sonuç penceresinden okuyun
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

// ──────────────────────────────────────────────────────────────
// Modal olmayan pencere yardımcıları
//   - waitForConfirm    : modal hissi veren ama QuPath'i kilitlemeyen onay penceresi
//   - showResultWindow  : sonuç penceresi — açık kalır, QuPath kullanılmaya devam edilebilir
//
// İkisi de always-on-top açık başlar; kullanıcı kapatmadan slaytta dolaşabilir,
// parametre değiştirip betiği tekrar çalıştırabilir, sonuçları kopyalayabilir.
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

def detectionImageBrightfield = atolyeS('atolye.detectionChannel', 'Hematoxylin OD')   // veya 'Optical density sum'
def requestedPixelSizeMicrons = atolyeD('atolye.pixelSize', 0.5)
def backgroundRadiusMicrons   = atolyeD('atolye.backgroundRadius', 8.0)
def medianRadiusMicrons       = atolyeD('atolye.medianRadius', 0.0)
def sigmaMicrons              = atolyeD('atolye.sigma', 1.5)
def minAreaMicrons            = atolyeD('atolye.minArea', 10.0)
def maxAreaMicrons            = atolyeD('atolye.maxArea', 400.0)
def detectionThreshold        = atolyeD('atolye.detectionThreshold', 0.1)
def watershedPostProcess      = atolyeB('atolye.watershed', true)
def cellExpansionMicrons      = atolyeD('atolye.cellExpansionNuclear', 5.0)
def thresholdPositive1        = atolyeD('atolye.nuclear1', 0.20)
def thresholdPositive2        = atolyeD('atolye.nuclear2', 0.40)
def thresholdPositive3        = atolyeD('atolye.nuclear3', 0.60)
def warnNuclearCount          = atolyeI('atolye.warnNuclearCount', 500)

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

            stage.setScene(new javafx.scene.Scene(root, 620, 460))
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

            stage.setScene(new javafx.scene.Scene(root, 760, 580))
            stage.show()
        } catch (Throwable t) {
            // FX başarısızsa modal'a geri dön — kayıp olmasın
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

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

// Image type uyarısı (Brightfield (H-DAB) zorunlu — DAB ayrımı için)
def imageType = imageData.getImageType()
def imageTypeName = imageType?.toString() ?: ""
if (!imageTypeName.toLowerCase().contains("brightfield")) {
    Dialogs.showErrorMessage(
        "Yanlış görüntü tipi",
        "Bu slayt 'Brightfield' olarak ayarlı değil.\n" +
        "Şu anki tip: ${imageTypeName}\n\n" +
        "Çözüm:\n" +
        "  1. Image panelini açın (sol-üst)\n" +
        "  2. 'Image type' → 'Brightfield (H-DAB)' seçin (DAB ayrımı için gerekli)\n" +
        "  3. Bu betiği tekrar çalıştırın"
    )
    return
}

// "Hematoxylin OD" kanalı yalnızca H-DAB boya vektörleri ayarlanmışsa görünür.
// Image type 'Brightfield (other)' veya boya vektörleri eksikse parametre reddedilir.
def stains = imageData.getColorDeconvolutionStains()
def hasHematoxylin = false
if (stains != null) {
    for (int i = 1; i <= 3; i++) {
        def name = stains.getStain(i)?.getName()?.toLowerCase()
        if (name != null && name.contains("hematoxylin")) { hasHematoxylin = true; break }
    }
}
if (!hasHematoxylin) {
    println "⚠ H-DAB boya vektörleri tanımlı değil → BRIGHTFIELD_H_DAB varsayılanı uygulanıyor."
    QP.setImageType('BRIGHTFIELD_H_DAB')
}

// ──────────────────────────────────────────────────────────────
// 2) Karşılama dialog
// ──────────────────────────────────────────────────────────────
def devam = waitForConfirm(
    "Modül 3 - Ki-67 / Nükleer İHK kantifikasyonu",
    "Bu betik, seçtiğiniz anotasyon içindeki tüm çekirdekleri tespit edip\n" +
    "her birini DAB yoğunluğuna göre Negative / 1+ / 2+ / 3+ olarak sınıflar.\n\n" +
    "Atölye eşikleri (DAB OD — Nucleus mean)" + (thresholdPositive1 != 0.20 || thresholdPositive2 != 0.40 || thresholdPositive3 != 0.60 ? " (değiştirildi)" : "") + ":\n" +
    "  • 1+ (zayıf):  " + String.format(java.util.Locale.US, '%.2f', thresholdPositive1) + " OD\n" +
    "  • 2+ (orta):   " + String.format(java.util.Locale.US, '%.2f', thresholdPositive2) + " OD\n" +
    "  • 3+ (güçlü):  " + String.format(java.util.Locale.US, '%.2f', thresholdPositive3) + " OD\n\n" +
    "Çıktı:\n" +
    "  • Ki-67 LI (Pozitif %) — ölçüm çıktısı\n" +
    "  • Grup dağılımı (% 0 / 1+ / 2+ / 3+)\n" +
    "  • Hücre yoğunluğu (hücre/mm²) + anotasyon alanı\n\n" +
    "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.\n\n" +
    "Hazırsanız Çalıştır düğmesine basın; devam etmek istemiyorsanız İptal ile çıkın."
)
if (!devam) {
    println "Kullanıcı iptal etti."
    return
}

// ──────────────────────────────────────────────────────────────
// 3) Anotasyon kontrolü
// ──────────────────────────────────────────────────────────────
def selected = QP.getSelectedObject()
def annotations = QP.getAnnotationObjects()

if (annotations.isEmpty()) {
    Dialogs.showErrorMessage(
        "Anotasyon bulunamadı",
        "Önce bir anotasyon çizmelisiniz.\n\n" +
        "Nasıl:\n" +
        "  1. R tuşu → tümör içeren ~1×1 mm bir dikdörtgen sürükleyin\n" +
        "  2. Anotasyona tıklayarak seçili tutun (kenarı sarı görünmeli)\n" +
        "  3. Bu betiği tekrar çalıştırın"
    )
    return
}

if (selected == null || !(selected instanceof PathAnnotationObject)) {
    Dialogs.showErrorMessage(
        "Anotasyon seçili değil",
        "Çalıştırmak istediğiniz dikdörtgen anotasyonu seçili tutun (kenarı sarı)."
    )
    return
}

def targetAnnotation = selected

// ──────────────────────────────────────────────────────────────
// 4) Positive cell detection — atölye varsayılanları
// ──────────────────────────────────────────────────────────────
println "─────────────────────────────────────"
println "Modül 3 - Ki-67 / Nükleer İHK"
println "─────────────────────────────────────"
println "Pozitif hücre tespiti başlatılıyor..."
println "  • Image: ${QP.getProjectEntry()?.getImageName() ?: imageData.getServer().getMetadata().getName()}"
println "  • Score compartment: Nucleus: DAB OD mean"
println "  • Eşik 1+ / 2+ / 3+: ${thresholdPositive1} / ${thresholdPositive2} / ${thresholdPositive3} OD"
println "  • Requested pixel size: ${requestedPixelSizeMicrons} µm/px"
println "  • Nucleus background radius: ${backgroundRadiusMicrons} µm"

def t0 = System.currentTimeMillis()

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

QP.selectObjects(targetAnnotation)
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
        '"thresholdPositive1":' + thresholdPositive1 + ',' +
        '"thresholdPositive2":' + thresholdPositive2 + ',' +
        '"thresholdPositive3":' + thresholdPositive3 + ',' +
        '"singleThreshold":false' +
    '}'
)

def elapsed = (System.currentTimeMillis() - t0) / 1000.0

// ──────────────────────────────────────────────────────────────
// 5) Sonuçları topla — her bin için sayım
// ──────────────────────────────────────────────────────────────
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
def cal = imageData.getServer().getPixelCalibration()
def pixelWidth  = cal.getPixelWidthMicrons()
def pixelHeight = cal.getPixelHeightMicrons()
def roi = targetAnnotation.getROI()
def totalAreaMm2 = roi != null
    ? (roi.getArea() * pixelWidth * pixelHeight) / 1_000_000.0
    : 0.0
def density = totalAreaMm2 > 0 ? Math.round(totalCells / totalAreaMm2) : 0

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

// ──────────────────────────────────────────────────────────────
// 6) Sonucu sun
// ──────────────────────────────────────────────────────────────
showResultWindow(
    "Tamamlandı 🔬",
    String.format(java.util.Locale.US, 
        "Ki-67 / Nükleer İHK kantifikasyonu bitti.\n\n" +
        "📊 Sayım sonuçları\n" +
        "────────────────────\n" +
        "  Toplam hücre        : %,d\n" +
        "  Negatif             : %,d  (%%%.1f)\n" +
        "  1+ (zayıf)          : %,d  (%%%.1f)\n" +
        "  2+ (orta)           : %,d  (%%%.1f)\n" +
        "  3+ (güçlü)          : %,d  (%%%.1f)\n\n" +
        "🎯 Metrikler\n" +
        "─────────────\n" +
        "  Ki-67 LI (Pozitif %%)  : %%%.1f\n" +
        "  Hücre yoğunluğu        : ~%,d hücre/mm²\n" +
        "  Anotasyon alanı        : %.2f mm²\n" +
        "  Süre                   : %.1f sn\n" +
        "%s\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
        totalCells,
        numNegative, totalCells > 0 ? 100.0 * numNegative / totalCells : 0.0,
        num1Plus,    totalCells > 0 ? 100.0 * num1Plus / totalCells : 0.0,
        num2Plus,    totalCells > 0 ? 100.0 * num2Plus / totalCells : 0.0,
        num3Plus,    totalCells > 0 ? 100.0 * num3Plus / totalCells : 0.0,
        ki67LI, density, totalAreaMm2, elapsed, uyari
    )
)

println "─────────────────────────────────────"
println "Tamamlandı:"
println "  Toplam: ${totalCells}  |  Pozitif: ${numPositive}  |  Ki-67 LI: ${String.format(java.util.Locale.US, '%.1f', ki67LI)}%"
println "  Yoğunluk: ${density}/mm²  |  Süre: ${elapsed} sn"
println "─────────────────────────────────────"
