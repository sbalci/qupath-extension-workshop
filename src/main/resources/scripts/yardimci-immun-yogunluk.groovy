/**
 * Yardımcı - İmmün Hücre Yoğunluğu (DAB)
 * ----------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Seçili anotasyon(lar) içinde bir DAB-İHK immün belirteci (CD8, CD57, PD1…)
 * için pozitif hücre tespiti yapar ve **pozitif hücre yoğunluğu (hücre/mm²)**
 * ile **pozitif hücre %** üretir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • DAB-pozitif hücrelerin alana göre YOĞUNLUĞUNU ölçer. Bu bir
 *     SAYIM/YOĞUNLUKtur — klinik immün skor, eşik veya yorum DEĞİL.
 *   • Stromal TIL helper'ından FARKLIDIR: o, H&E'de küçük çekirdek
 *     morfolojisini sayar; bu betik açık bir İHK belirtecinin DAB
 *     pozitifliğini sayar.
 *
 * KULLANIM:
 *   1. DAB-İHK lamını açın, Image type → "Brightfield (H-DAB)"
 *   2. Ölçmek istediğiniz alanı anotasyon olarak çizin ve SEÇİN
 *      (ölçülecek anotasyonlar açıkça seçilmelidir)
 *   3. [Automate → Project scripts → bu betik]
 *
 * ÇIKTI:
 *   • Her anotasyona: "İmmün pozitif hücre yoğunluğu (hücre/mm2)" +
 *     "Pozitif hücre %"
 *   • Kilitli "İmmün Yoğunluk Özet" anotasyonu (Veri dışa aktarma modülü ile dışa aktarılır)
 *
 * YÖNTEM REFERANSLARI:
 *   • de Ruiter EJ et al. (2022), Virchows Arch 481(2):223–231 — CD57+
 *     hücre/mm², QuPath vs iki gözlemci ICC. doi:10.1007/s00428-022-03323-6
 *   • Russell GG et al. (2023), Immunol Invest 52(6):661–680 — PD1/PDL1
 *     yoğunluğu, yarı otomatik QuPath. doi:10.1080/08820139.2023.2217845
 *   • Reichling C et al. (2019), Gut — CD3/CD8 yoğunluğu (tümör/invaziv kenar).
 *     doi:10.1136/gutjnl-2019-319292
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
// Hücreleri değiştirme sorusu: varsayılan seçim "dokunma"dır — Enter/Tamam hiçbir şeyi değiştirmez.
// (Evet/Hayır penceresinde Enter "Evet" anlamına gelir; bu yüzden seçim penceresi kullanılır.)
def confirmReplaceCells = { String title, String body ->
    String keepOpt = workshopText('script.665d4074c4fe', 'Mevcut hücrelere dokunma — tespiti çalıştırma (varsayılan)')
    String replaceOpt = workshopText('script.6b4af35c53fe', 'Mevcut hücreleri değiştir — tespiti çalıştır')
    def ask = { -> qupath.fx.dialogs.Dialogs.showChoiceDialog(title, body, [keepOpt, replaceOpt], keepOpt) }
    if (javafx.application.Platform.isFxApplicationThread()) return ask() == replaceOpt
    def picked = new java.util.concurrent.atomic.AtomicReference(null)
    def latch = new java.util.concurrent.CountDownLatch(1)
    javafx.application.Platform.runLater {
        try { picked.set(ask()) } finally { latch.countDown() }
    }
    latch.await()
    return picked.get() == replaceOpt
}

// ──────────────────────────────────────────────────────────────
// Boya vektörü önerisi + tek tıklamalı tahmin (tüm DAB-OD modüllerinde aynı blok).
//   • Aktif vektörler QuPath VARSAYILANI ise uyarır: kantitatif ölçümden önce bu
//     lamdan tahmin etmeyi önerir (eşikler mutlak DAB OD; yanlış vektör skoru kaydırır).
//   • "Tamam" → SEÇİLİ alan anotasyonundan tahmin edip uygular (Boya vektörleri
//     sihirbazı ile aynı yöntem). "İptal" → yine de devam. Kalibre görünüyorsa sessiz geçer.
// ──────────────────────────────────────────────────────────────
double SV_TOL = 0.02d
double[] SV_DEF_HEMA = [0.651d, 0.701d, 0.290d] as double[]
double[] SV_DEF_DAB  = [0.269d, 0.568d, 0.776d] as double[]
try {
    def svEnum = Class.forName('qupath.lib.color.ColorDeconvolutionStains$DefaultColorDeconvolutionStains')
    def svDab = Class.forName('qupath.lib.color.ColorDeconvolutionStains')
                     .getMethod('makeDefaultColorDeconvolutionStains', svEnum)
                     .invoke(null, Enum.valueOf(svEnum, 'H_DAB'))
    SV_DEF_HEMA = [svDab.getStain(1).getRed(), svDab.getStain(1).getGreen(), svDab.getStain(1).getBlue()] as double[]
    SV_DEF_DAB  = [svDab.getStain(2).getRed(), svDab.getStain(2).getGreen(), svDab.getStain(2).getBlue()] as double[]
} catch (Throwable svIgnore) { /* tablo değerleri kullanılır */ }
def svVecOf = { s -> [s.getRed() as double, s.getGreen() as double, s.getBlue() as double] as double[] }
def svClose = { double[] a, double[] b ->
    Math.abs(a[0] - b[0]) <= SV_TOL && Math.abs(a[1] - b[1]) <= SV_TOL && Math.abs(a[2] - b[2]) <= SV_TOL
}
def suggestStainVectors = { ->
    def svId = QP.getCurrentImageData()
    if (svId == null) return
    def svStains = svId.getColorDeconvolutionStains()
    if (svStains == null) return
    def svS2 = (svStains.getStain(2)?.getName() ?: '').toLowerCase(java.util.Locale.ROOT)
    if (!svS2.contains('dab')) return
    boolean svDefault = svClose(svVecOf(svStains.getStain(1)), SV_DEF_HEMA) &&
                        svClose(svVecOf(svStains.getStain(2)), SV_DEF_DAB)
    if (!svDefault) { println '✓ Boya vektörleri bu lamdan tahmin/kalibre edilmiş görünüyor.'; return }
    def svMsg =
        workshopText('script.f3f75b6ee2d7', '⚠ Boya vektörleri QuPath VARSAYILANI görünüyor — bu lamdan tahmin EDİLMEMİŞ.\n\n') +
        workshopText('script.0236cb297d65', 'Eşikler mutlak DAB OD üzerinden çalışır; doğru vektör olmadan skorlar kayar.\n') +
        workshopText('script.06d512ead76b', 'Tarayıcı/boyama partisi başına bir kez tahmin yeterlidir.\n\n') +
        workshopText('script.41b8026c8dbe', 'Şimdi SEÇİLİ alandan tahmin edilsin mi?\n') +
        workshopText('script.61c29a66c32b', '(İki boya + biraz arka plan içeren temsilî bir alan anotasyonu seçili olmalı.)\n\n') +
        workshopText('script.e6ac9add601c', '[Tamam] = tahmin et ve uygula      [İptal] = yine de devam et')
    if (isHeadless) { println svMsg; return }
    if (!qupath.fx.dialogs.Dialogs.showConfirmDialog(workshopText('script.cb5b26abe800', 'Boya vektörleri — öneri'), svMsg)) return
    def svSel = QP.getSelectedObjects().findAll { it.hasROI() && it.getROI().isArea() }
    if (svSel.isEmpty()) {
        qupath.fx.dialogs.Dialogs.showWarningNotification(workshopText('script.c1cc09de3ac8', 'Boya vektörleri'),
            workshopText('script.525b4898d046', 'Önce iki boya + biraz arka plan içeren temsilî bir alan anotasyonu çizip SEÇİN, sonra tekrar deneyin.'))
        return
    }
    try {
        def svRoi = svSel[0].getROI()
        def svServer = svId.getServer()
        double svPix = (svRoi.getBoundsWidth() as double) * (svRoi.getBoundsHeight() as double)
        double svDown = Math.max(1.0d, Math.sqrt(svPix / 16_000_000d))
        def svImg = svServer.readRegion(
            qupath.lib.regions.RegionRequest.createInstance(svServer.getPath(), svDown, svRoi))
        def svOld = svId.getColorDeconvolutionStains()
        def svNew = qupath.lib.analysis.algorithms.EstimateStainVectors.estimateStains(
            svImg, svOld, 0.05d, 1.0d, 1.0d, true)
        svId.setColorDeconvolutionStains(svNew.changeName('Bölgeden tahmin (Atölye)'))
        javafx.application.Platform.runLater {
            try { qupath.lib.gui.QuPathGUI.getInstance()?.getViewer()?.repaintEntireImage() } catch (Throwable svR) { }
        }
        qupath.fx.dialogs.Dialogs.showMessageDialog(workshopText('script.c1cc09de3ac8', 'Boya vektörleri'),
            workshopText('script.4e0fe0741a03', 'Vektörler seçili bölgeden tahmin edilip uygulandı. Modülü şimdi çalıştırabilirsiniz.'))
    } catch (Throwable svErr) {
        qupath.fx.dialogs.Dialogs.showMessageDialog(workshopText('script.c1cc09de3ac8', 'Boya vektörleri'),
            workshopText('script.c0b06389df5e', 'Tahmin yapılamadı: ') + (svErr.getMessage() ?: svErr.getClass().getSimpleName()))
    }
}
suggestStainVectors()

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

            stage.setScene(new javafx.scene.Scene(root, 720, 540))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler (sabit; kalibre edilebilir başlangıç değerleri) ────
double pixelSizeMicrons        = 0.5
double dabThreshold            = 0.20    // DAB OD eşiği — belirtece göre KALİBRE EDİN
double nucleusDetectionThreshold = 0.10  // Hematoxylin OD nükleus eşiği
double minNucleusAreaUm2       = 10.0
double maxNucleusAreaUm2       = 400.0
double cellExpansionUm         = 2.0
String detectionChannel        = "Hematoxylin OD"
String summaryName             = "İmmün Yoğunluk Özet"

// ── Önceki sonuç kipi (ev kuralı: hiçbir şey sorulmadan silinmez) ───────────
//   ekle     → hiçbir şey silinmez; yeni özet sıradaki adı alır ("<ad> (2)" …).
//   değiştir → yalnız bu yardımcının özet anotasyonları (tam ad ya da "ad (N)") silinir;
//              içlerindeki nesneler korunur (keepChildren = true).
//   Başsız kip: `-a mevcut=ekle` | `-a mevcut=degistir`; önceki sonuç varken belirtilmezse durur.
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
// Bu yardımcının özeti mi? Yalnız TAM ad ya da "ad (N)" — önek eşleşmesi YOK:
// kullanıcının "İmmün Yoğunluk Özet notlarım" diye adlandırdığı anotasyon süpürülmez.
def isOwnSetName = { String name, String base ->
    if (name == null) return false
    if (name == base) return true
    return name.startsWith(base + ' (') && name.endsWith(')') &&
        name.substring(base.length() + 2, name.length() - 1).isInteger() &&
        (name.substring(base.length() + 2, name.length() - 1) as int) >= 2
}
def ownSetIndex = { String name, String base ->
    return (name == base) ? 1 : (name.substring(base.length() + 2, name.length() - 1) as int)
}
def prevOwnObjects = { hierarchy, String base ->
    if (hierarchy == null) return []
    return hierarchy.getAnnotationObjects().findAll { isOwnSetName(it.getName(), base) }
}
def nextOwnSetName = { hierarchy, String base ->
    def used = prevOwnObjects(hierarchy, base).collect { ownSetIndex(it.getName(), base) }
    if (used.isEmpty()) return base
    return base + ' (' + ((used.max() as int) + 1) + ')'
}
def askExisting = { hierarchy ->
    def prev = prevOwnObjects(hierarchy, summaryName)
    if (prev.isEmpty()) return MODE_ADD
    String optAdd = workshopText('script.1e749978a0e7', 'Ekle — öncekine dokunma, yeni bir özet olarak ekle (varsayılan)')
    String optRep = workshopText('script.3bb827725a09', 'Değiştir — önceki özet anotasyonları silinir')
    def pick = Dialogs.showChoiceDialog(workshopText('script.bce7a8193d8d', 'Önceki sonuç — İmmün yoğunluk'),
        String.format(java.util.Locale.US, workshopText('script.cd7dc9909880', 'Bu lamda bu yardımcının yazdığı %,d özet anotasyonu zaten var ("İmmün Yoğunluk Özet").\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni özet "İmmün Yoğunluk Özet (2)" gibi ayrı bir ad alır ve iki özet birlikte durur.\n\n"Değiştir" seçilirse önceki özet anotasyonları silinir. Aynı adı kendiniz verdiyseniz o anotasyon da silinir. Diğer anotasyonlara dokunulmaz.\n\nİptal: hiçbir şey hesaplanmaz, hiçbir şey yazılmaz.\n\nÖnceki sonuç ne olsun?'), prev.size()),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.89a856ac207d', 'Önceki özet silinecek'),
            String.format(java.util.Locale.US, workshopText('script.d9ffa820dc55', '%,d özet anotasyonu silinecek. Bunlar üzerinde elle yaptığınız değişiklikler ve aynı adı kendiniz verdiğiniz anotasyonlar da gider; içlerindeki diğer nesneler korunur.\n\nDevam edilsin mi?'), prev.size()))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}
def applyMode = { hierarchy, String mode ->
    def prev = prevOwnObjects(hierarchy, summaryName)
    def prevNames = prev.collect { it.getName() }.unique().sort()
    int removed = 0
    String setName
    if (mode == MODE_REPLACE) {
        removed = prev.size()
        if (removed > 0) hierarchy.removeObjects(prev, true)
        setName = summaryName
    } else {
        setName = nextOwnSetName(hierarchy, summaryName)
    }
    return [setName: setName, removed: removed, prevNames: (mode == MODE_REPLACE ? [] : prevNames)]
}

def scriptArgs = []
try { if (binding.hasVariable('args')) { def a = binding.getVariable('args'); if (a != null) scriptArgs = (a as List) } } catch (Throwable ignore) {}
def argMap = [:]
scriptArgs.each { a -> def sa = a?.toString() ?: ''; int ia = sa.indexOf('='); if (ia > 0) argMap[sa.substring(0, ia).trim()] = sa.substring(ia + 1).trim() }
String mevcut = argMap.mevcut == null ? null : argMap.mevcut.toLowerCase(java.util.Locale.ROOT)
if (isHeadless && mevcut != null && !['ekle', 'degistir'].contains(mevcut)) {
    println String.format(java.util.Locale.US, workshopText('script.768b9abc88f3', 'HATA: mevcut yalnız \'ekle\' ya da \'degistir\' olabilir (verilen: %s). Hiçbir şey yazılmadı.'), argMap.mevcut)
    return
}

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.284651a2e0c4', "Önce bir DAB-İHK lamı açın."))
    return
}

def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage(workshopText('script.a5a01874aff2', "Kalibrasyon yok"), workshopText('script.66586705b08c', "Lamda piksel boyutu (µm) tanımlı değil; yoğunluk hesaplanamaz." +
        "\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)."))
    return
}

def selected = QP.getSelectedObjects().findAll { it.isAnnotation() && !isOwnSetName(it.getName(), summaryName) }
def targets = selected
if (targets.isEmpty()) {
    def msg = workshopText('script.971118766cab', 'Ölçülecek anotasyon yok.\n\nÖnce ilgilendiğiniz bölgeyi anotasyon olarak çizin (ve seçin).')
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.469472ee53f3', "Anotasyon yok"), msg)
    return
}

// Önceki sonuç varsa HESAPLAMADAN ÖNCE sor (İptal: hiçbir şey çalışmaz, hiçbir şey yazılmaz).
String runMode = MODE_ADD
def prevOwnNow = prevOwnObjects(imageData.getHierarchy(), summaryName)
if (!prevOwnNow.isEmpty()) {
    if (isHeadless) {
        if (mevcut == null) {
            println String.format(java.util.Locale.US, workshopText('script.24a442295e5a', 'Bu lamda bu yardımcının yazdığı %,d özet anotasyonu ("%s") zaten var. Hiçbir şey yazılmadı. Yeni bir kopya eklemek için mevcut=ekle, önceki sonucu değiştirmek için mevcut=degistir ile yeniden çalıştırın.'), prevOwnNow.size(), summaryName)
            return
        }
        runMode = (mevcut == 'degistir') ? MODE_REPLACE : MODE_ADD
    } else {
        runMode = askExisting(imageData.getHierarchy())
        if (runMode == null) return
    }
} else if (isHeadless && mevcut == 'degistir') {
    runMode = MODE_REPLACE
}


// ── Mevcut hücre tespitleri ─────────────────────────────────────────
// PositiveCellDetection, çalıştığı anotasyonların içindeki mevcut hücre tespitlerinin YERİNİ alır
// (elle verilen sınıflar/düzeltmeler dahil). Bu yüzden tespitten ÖNCE sorulur; İptal: hiçbir şey çalışmaz.
// Özet sorusu (yukarıda) ayrı bir şeydir: o yalnız önceki ÖZET anotasyonları içindir.
def askYesNo = { String title, String body ->
    if (javafx.application.Platform.isFxApplicationThread()) return Dialogs.showConfirmDialog(title, body)
    def answer = new java.util.concurrent.atomic.AtomicBoolean(false)
    def latch = new java.util.concurrent.CountDownLatch(1)
    javafx.application.Platform.runLater {
        try { answer.set(Dialogs.showConfirmDialog(title, body)) } finally { latch.countDown() }
    }
    latch.await()
    return answer.get()
}
def redetectCount = { anns ->
    int n = 0
    anns.each { a -> n += a.getChildObjects().findAll { it.isDetection() }.size() }
    return n
}
int redetectPrev = redetectCount(targets)
if (redetectPrev > 0) {
    if (isHeadless) {
        if (mevcut != 'degistir') {
            println String.format(java.util.Locale.US, workshopText('script.24b91d6d005b', 'Seçili anotasyon(lar)da zaten %,d hücre tespiti var. Hiçbir şey yazılmadı. Bu hücreleri değiştirmek için -a mevcut=degistir ile yeniden çalıştırın.'), redetectPrev)
            return
        }
    } else {
        String redetectTitleText = workshopText('script.c8cded1ce05d', 'Önceki hücre tespitleri değiştirilecek')
        String redetectCancelledText = workshopText('script.0187c58bc6df', 'İptal edildi — hiçbir şey çalıştırılmadı, hiçbir şey değişmedi.')
        if (!confirmReplaceCells(redetectTitleText, String.format(java.util.Locale.US, workshopText('script.cb7f8ecd03c8', 'Seçili anotasyon(lar)da zaten %,d hücre var. Tespit bu hücrelerin yerini alacak; elle verdiğiniz sınıflar ve düzeltmeler de gider.\n\nDevam edilsin mi?\n\n"Hayır" derseniz hiçbir şey çalıştırılmaz ve hiçbir şey değişmez.'), redetectPrev))) {
            Dialogs.showInfoNotification(redetectTitleText, redetectCancelledText)
            println redetectCancelledText
            return
        }
    }
}

// ── 2) Pozitif hücre tespiti (DAB tek eşik) ─────────────────────────
println "İmmün belirteç için pozitif hücre tespiti..."
QP.selectObjects(targets)
QP.runPlugin(
    'qupath.imagej.detect.cells.PositiveCellDetection',
    '{' +
        '"detectionImageBrightfield":"' + detectionChannel + '",' +
        '"requestedPixelSizeMicrons":' + pixelSizeMicrons + ',' +
        '"backgroundRadiusMicrons":8.0,' +
        '"medianRadiusMicrons":0.0,' +
        '"sigmaMicrons":1.5,' +
        '"minAreaMicrons":' + minNucleusAreaUm2 + ',' +
        '"maxAreaMicrons":' + maxNucleusAreaUm2 + ',' +
        '"threshold":' + nucleusDetectionThreshold + ',' +
        '"watershedPostProcess":true,' +
        '"cellExpansionMicrons":' + cellExpansionUm + ',' +
        '"includeNuclei":true,' +
        '"smoothBoundaries":true,' +
        '"makeMeasurements":true,' +
        // Membranöz immün belirteçler (CD8/PD-L1/PD1) için hücre gövdesi (membran dahil) compartmenti
        '"thresholdCompartment":"Cell: DAB OD mean",' +
        '"thresholdPositive1":' + dabThreshold + ',' +
        '"singleThreshold":true' +
    '}'
)

// ── 3) Pozitif hücreleri say, yoğunluk hesapla ──────────────────────
def isPositive = { cell -> def cn = cell.getPathClass()?.getName(); cn != null && (cn.equalsIgnoreCase("Positive") || cn.equalsIgnoreCase("Pozitif") || cn.endsWith("+")) }
def areaMm2 = { ann ->
    def roi = ann.getROI()
    roi != null ? (roi.getArea() * pw * ph) / 1_000_000.0 : 0.0
}

int totalCells = 0
int totalPos = 0
double totalAreaMm2 = 0.0

targets.each { ann ->
    def cells = ann.getChildObjects().findAll { it.isDetection() }
    int n = cells.size()
    int nPos = cells.count { isPositive(it) } as int
    double aMm2 = areaMm2(ann)

    totalCells += n
    totalPos += nPos
    totalAreaMm2 += aMm2

    double dPos = aMm2 > 0 ? nPos / aMm2 : 0.0
    double pctPos = n > 0 ? (100.0 * nPos / n) : 0.0
    ann.measurements['İmmün pozitif hücre yoğunluğu (hücre/mm2)'] = dPos
    ann.measurements['Pozitif hücre %'] = pctPos
}

double overallDensity = totalAreaMm2 > 0 ? totalPos / totalAreaMm2 : 0.0
double overallPct = totalCells > 0 ? (100.0 * totalPos / totalCells) : 0.0

// ── 4) Kilitli özet anotasyonu ──────────────────────────────────────
// Önceki özet SORULMADAN silinmez: kip yukarıda seçildi (ekle: sıradaki ad; değiştir: yalnız kendi özetleri).
def appliedMode = applyMode(imageData.getHierarchy(), runMode)
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName(appliedMode.setName)
summary.measurements['Ölçülen alan (mm2)']                      = totalAreaMm2
summary.measurements['Toplam hücre']                            = totalCells as double
summary.measurements['Pozitif hücre']                          = totalPos as double
summary.measurements['İmmün pozitif hücre yoğunluğu (hücre/mm2)'] = overallDensity
summary.measurements['Pozitif hücre %']                        = overallPct
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

// ── 5) Sonucu sun ───────────────────────────────────────────────────
def modeNotes = new StringBuilder()
if (redetectPrev > 0) {
    modeNotes << "\n" << String.format(java.util.Locale.US, workshopText('script.784fe373ea77', 'Önceki %,d hücre değiştirildi.'), redetectPrev) << "\n"
}
if (!appliedMode.prevNames.isEmpty()) {
    modeNotes << "\n" << String.format(java.util.Locale.US, workshopText('script.7577a3bac9a2', 'DİKKAT — bu lamda önceki sonuç da duruyor: %s\nYeni özet şu adla eklendi: %s\nHiçbir şey silinmedi. Eski özeti kaldırmak için o addaki anotasyonu seçip silin ya da yeniden çalıştırırken "Değiştir"i seçin. Dışa aktarma ve kohort özetleri iki özeti de görür.'), appliedMode.prevNames.join(', '), appliedMode.setName) << "\n"
    modeNotes << workshopText('script.46fd40a69eb4', 'Seçili anotasyonların üzerindeki ölçümler bu YENİ çalıştırmayı gösterir; önceki özet nesnesi eski değerleri göstermeye devam eder.') << "\n"
} else if (appliedMode.removed > 0) {
    modeNotes << "\n" << String.format(java.util.Locale.US, workshopText('script.bc31ff876c31', 'Önceki sonuç değiştirildi: %,d özet anotasyonu silindi (içlerindeki diğer nesneler korundu).'), appliedMode.removed) << "\n"
}
def body = new StringBuilder()
body << workshopText('script.e57dc00d8e2e', 'İMMÜN HÜCRE YOĞUNLUĞU (DAB)\n')
body << "════════════════════════════\n\n"
body << String.format(java.util.Locale.US, workshopText('script.2b77df24cdb0', 'Ölçülen alan          : %.3f mm²%n'), totalAreaMm2)
body << String.format(java.util.Locale.US, workshopText('script.15055b8c6dc2', 'Toplam hücre          : %,d%n'), totalCells)
body << String.format(java.util.Locale.US, workshopText('script.5e248f593c80', 'Pozitif hücre         : %,d  (%.1f %%)%n'), totalPos, overallPct)
body << String.format(java.util.Locale.US, workshopText('script.01a38b723352', 'Pozitif yoğunluk      : %.0f hücre/mm²%n'), overallDensity)
body << "\n"
body << String.format(java.util.Locale.US, workshopText('script.c349203931c3', 'DAB eşiği: %.2f (kalibre edilebilir başlangıç değeri)%n'), dabThreshold)
body << modeNotes.toString()
body << "\n"
body << workshopText('script.63ba61231a78', 'Bu bir SAYIM/YOĞUNLUKtur — klinik immün skor, eşik veya yorum DEĞİL.\n')
body << workshopText('script.87489a2e5c25', 'Belirteç kimliği ve eşik kalibrasyonu kullanıcıya aittir (de Ruiter 2022; Russell 2023).\n\n')
body << workshopText('script.cf6099f0a005', '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')

showResultWindow(workshopText('script.18296bca9161', 'İmmün hücre yoğunluğu (DAB)'), body.toString())
println "✓ İmmün hücre yoğunluğu yazıldı (her anotasyon + '${appliedMode.setName}')."

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
