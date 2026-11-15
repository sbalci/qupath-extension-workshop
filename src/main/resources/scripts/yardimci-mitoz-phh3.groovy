/**
 * Yardımcı - PHH3 Mitoz Kantifikasyonu
 * --------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Seçili anotasyon(lar) içinde **PHH3 (fosfo-histon H3)** İHK boyasının
 * DAB-pozitif çekirdeklerini sayar ve **mitoz yoğunluğu** üretir:
 * mitoz/mm² ve yalnız tam 2 mm² ROI için gözlenen mitoz sayısı.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Tanımlı bir alandaki PHH3-DAB-pozitif çekirdeklerin SAYIM ve
 *     YOĞUNLUĞUNU ölçer. Bu bir SAYIM/YOĞUNLUKtur — mitoz derecesi,
 *     grade eşiği veya klinik yorum DEĞİL.
 *   • PHH3 pozitifliği mitoz ile birebir eş DEĞİLDİR: PHH3 geç G2/profaz
 *     çekirdeklerini de boyar (Hücre tespiti modülündeki "freckle" örneği). Boyut filtresi
 *     bunları azaltır ama tümüyle elemez — sonuç görsel doğrulama gerektirir.
 *   • Nükleer Ki-67 betiğinden (modul-03) FARKI: orada amaç pozitif FRAKSİYON
 *     (LI %); burada amaç alana göre MUTLAK SAYIM (mitoz/mm²).
 *
 * KULLANIM:
 *   1. PHH3-İHK lamını açın, Image type → "Brightfield (H-DAB)"
 *   2. Mitozca yoğun bölgeyi (ör. tümörün en hücreli/sıcak alanı) anotasyon
 *      olarak çizin ve SEÇİN (ölçülecek anotasyonlar açıkça seçilmelidir)
 *   3. [Automate → Project scripts → bu betik]
 *
 * ÇIKTI:
 *   • Her anotasyona: "PHH3-pozitif çekirdek (mitoz adayı)" +
 *     "PHH3+ çekirdek yoğunluğu (çekirdek/mm2)" + "PHH3+ nükleer %"
 *   • Kilitli "Mitoz Özet" anotasyonu (Veri dışa aktarma modülü ile dışa aktarılır)
 *
 * YÖNTEM NOTU — EMPAIA "Mitosis Detection" not defteri ile bağlantı:
 *   EMPAIA ECDP2022 not defteri PHH3 mitozlarını klasik görüntü işlemeyle bulur:
 *   RGB->HSV -> HSV eşik (inRange) -> Otsu eşik -> morfolojik açma (MORPH_OPEN;
 *   küçük yapay sinyalleri/"freckle" eler) -> kontur sayımı (findContours). Bu betik
 *   aynı kavramsal zinciri QuPath ile uygular: boya vektörü ayrıştırma -> nükleer DAB
 *   OD eşiği (dabThreshold) -> watershed/morfoloji -> sayım. Not defterindeki
 *   "opening" adımının QuPath karşılığı, küçük profaz/non-mitotik PHH3+ sinyallerini
 *   azaltan minimum çekirdek alanı (minNucleusAreaUm2) filtresidir.
 *
 * YÖNTEM REFERANSLARI:
 *   • Hendzel MJ et al. (1997), Chromosoma 106(6):348–360 — H3 Ser10 fosforilasyonu
 *     geç G2/profazda başlar; PHH3'ün profaz çekirdeklerini de boyamasının (boyut
 *     filtresi gerekçesi) moleküler temeli. doi:10.1007/s004120050256
 *   • Ribalta T et al. (2004), Am J Surg Pathol 28(11):1532–1536 — PHH3 mitoz
 *     figürlerini H&E'ye göre daha hızlı/güvenilir ayırır (meningiom). doi:10.1097/01.pas.0000141389.06925.d5
 *   • Casper DJ et al. (2010), Am J Dermatopathol 32(7):650–654 — ince melanomda
 *     PHH3 H&E'ye göre %243 daha çok mitoz saptar. doi:10.1097/DAD.0b013e3181cf7cc1
 *   • Dessauvagie BF et al. (2015), Pathology 47(4):329–334 — PHH3 dijital görüntü
 *     analizi, mitoz/2 mm² (alan-tabanlı). doi:10.1097/PAT.0000000000000248
 *   • Ibrahim A et al. (2022), Histopathology 82(3):393–406 — PHH3-H&E, görüntü
 *     analizi ↔ patolog uyumunu artırır. doi:10.1111/his.14837
 *   • Kalsnes J et al. (2026), FEBS Open Bio — derin öğrenme ile mitoz/mm²,
 *     pan-kanser doğrulama. doi:10.1002/2211-5463.70210
 *   • Atölye eklentisi ve bu betik için bağlam: EMPAIA Academy, ECDP2022 uygulamalı
 *     atölyesi "Mitosis Detection" (https://www.empaia.org/hands-on-workshop-2022;
 *     Colab: https://colab.research.google.com/drive/1aPunyrBZ1rSHY3xMmL7hSpOWvpv5AKLq).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

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
            textArea.setWrapText(true)
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

            stage.setScene(new javafx.scene.Scene(root, 760, 580))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler (sabit; kalibre edilebilir başlangıç değerleri) ────
double pixelSizeMicrons          = 0.5
double dabThreshold              = 0.25    // PHH3 nükleer DAB OD eşiği — lam/tarayıcıya göre KALİBRE EDİN
double nucleusDetectionThreshold = 0.10    // Hematoxylin OD nükleus tespiti eşiği
double minNucleusAreaUm2         = 12.0    // küçük profaz/freckle sinyallerini azaltır (Hücre tespiti modülü "opening" karşılığı)
double maxNucleusAreaUm2         = 400.0
double cellExpansionUm           = 0.0     // nükleer belirteç — hücre genişletmeye gerek yok
double whoAreaMm2                = 2.0     // WHO alan-tabanlı raporlama birimi (mitoz / 2 mm²)
String detectionChannel          = "Hematoxylin OD"
String summaryName               = "Mitoz Özet"

// ── Önceki sonuç kipi (ev kuralı: hiçbir şey sorulmadan silinmez) ───────────
//   ekle     → hiçbir şey silinmez; yeni özet sıradaki adı alır ("<ad> (2)" …).
//   değiştir → yalnız bu yardımcının özet anotasyonları (tam ad ya da "ad (N)") silinir;
//              içlerindeki nesneler korunur (keepChildren = true).
//   Başsız kip: `-a mevcut=ekle` | `-a mevcut=degistir`; önceki sonuç varken belirtilmezse durur.
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
// Bu yardımcının özeti mi? Yalnız TAM ad ya da "ad (N)" — önek eşleşmesi YOK:
// kullanıcının "Mitoz Özet notlarım" diye adlandırdığı anotasyon süpürülmez.
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
    String optRep = workshopText('script.1f1df7a6f658', 'Değiştir — önceki özet anotasyonları ve altlarındaki PHH3 tespitleri silinir')
    def pick = Dialogs.showChoiceDialog(workshopText('script.6e02090f1c95', 'Önceki sonuç — PHH3 mitoz özeti'),
        String.format(java.util.Locale.US, workshopText('script.c83d76cfbf6d', 'Bu lamda bu yardımcının yazdığı %,d özet anotasyonu zaten var ("Mitoz Özet").\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni özet "Mitoz Özet (2)" gibi ayrı bir ad alır ve iki özet birlikte durur.\n\n"Değiştir" seçilirse önceki özet anotasyonları silinir (altlarındaki PHH3 tespitleri de). Aynı adı kendiniz verdiyseniz o anotasyon da silinir. Diğer anotasyonlara dokunulmaz.\n\nİptal: hiçbir şey hesaplanmaz, hiçbir şey yazılmaz.\n\nÖnceki sonuç ne olsun?'), prev.size()),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.89a856ac207d', 'Önceki özet silinecek'),
            String.format(java.util.Locale.US, workshopText('script.e80719a11971', '%,d özet anotasyonu ve altlarındaki PHH3 tespitleri silinecek. Bunlar üzerinde elle yaptığınız değişiklikler ve aynı adı kendiniz verdiğiniz anotasyonlar da gider; özetlerin altındaki başka anotasyonlar korunur.\n\nDevam edilsin mi?'), prev.size()))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}
def applyMode = { hierarchy, String mode ->
    def prev = prevOwnObjects(hierarchy, summaryName)
    def prevNames = prev.collect { it.getName() }.unique().sort()
    int removed = 0
    int removedDet = 0
    String setName
    if (mode == MODE_REPLACE) {
        removed = prev.size()
        // Önceki özetlerin ALTINDAKİ PHH3 tespitleri bu yardımcının kendi çıktısıdır; onlar da gider
        // (kalsalar yeni tespitle üst üste biner ve sayılar iki kez sayılırdı). Özetlerin altındaki
        // diğer nesneler (anotasyonlar) korunur: keepChildren = true.
        def ownDets = prev.collectMany { p -> p.getChildObjects().findAll { it.isDetection() } }
        removedDet = ownDets.size()
        if (removedDet > 0) hierarchy.removeObjects(ownDets, true)
        if (removed > 0) hierarchy.removeObjects(prev, true)
        setName = summaryName
    } else {
        setName = nextOwnSetName(hierarchy, summaryName)
    }
    return [setName: setName, removed: removed, removedDet: removedDet, prevNames: (mode == MODE_REPLACE ? [] : prevNames)]
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

// ── 1) Ön kontroller ve açık analiz sınırı ─────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.03e6208e26c2', "Önce bir PHH3-İHK lamı açın."))
    return
}

def imageTypeName = imageData.getImageType()?.toString() ?: ""
def normalizedImageType = imageTypeName.toUpperCase(java.util.Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
if (!normalizedImageType.contains('H_DAB')) {
    Dialogs.showErrorMessage(
        workshopText('script.be9dc7d830d5', "Yanlış görüntü tipi"),
        "${workshopText('script.2d940df6729d', 'Image type Brightfield (H-DAB) olmalı. Şu anki: ')}${imageTypeName}"
    )
    return
}

def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage(workshopText('script.a5a01874aff2', "Kalibrasyon yok"),
        workshopText('script.8a71051b5725', "Lamda piksel boyutu (µm) tanımlı değil; mitoz yoğunluğu (mitoz/mm²) hesaplanamaz."))
    return
}

def targets = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI()?.isArea() && !isOwnSetName(it.getName(), summaryName)
}
if (targets.isEmpty()) {
    Dialogs.showErrorMessage(workshopText('script.da079a56fe7d', "Anotasyon seçili değil"), workshopText('script.ada05aed8ac8', "PHH3 ölçümü için analiz anotasyonlarını açıkça seçin."))
    return
}

def analysisROI = qupath.lib.roi.RoiTools.union(targets.collect { it.getROI() })
if (analysisROI == null || analysisROI.isEmpty() || !analysisROI.isArea()) {
    Dialogs.showErrorMessage(workshopText('script.b4950149348d', "Geçersiz analiz alanı"), workshopText('script.01c291c2f0c9', "Seçili anotasyonlar geçerli bir birleşik ROI oluşturmuyor."))
    return
}

def stains = imageData.getColorDeconvolutionStains()
def hasHematoxylin = false
if (stains != null) {
    for (int i = 1; i <= 3; i++) {
        def name = stains.getStain(i)?.getName()?.toLowerCase(java.util.Locale.ROOT)
        if (name != null && name.contains("hematoxylin")) { hasHematoxylin = true; break }
    }
}
if (!hasHematoxylin) {
    Dialogs.showErrorMessage(workshopText('script.3869c47c0f86', "Boya vektörü eksik"), workshopText('script.b744628a7135', "H-DAB boya vektörlerinde Hematoxylin kanalı bulunamadı."))
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

// Önceki özet SORULMADAN silinmez: kip yukarıda seçildi (ekle: sıradaki ad; değiştir: kendi özetleri
// ve altlarındaki PHH3 tespitleri). Yeni özet boştur; tespit yalnız ona yazılır.
def appliedMode = applyMode(imageData.getHierarchy(), runMode)
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(analysisROI)
summary.setName(appliedMode.setName)
QP.addObjects([summary])

// ── 2) Birleşik ROI içinde pozitif çekirdek tespiti ─────────────────
println "PHH3 mitoz için pozitif çekirdek tespiti..."
QP.selectObjects(summary)
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
        '"thresholdCompartment":"Nucleus: DAB OD mean",' +
        '"thresholdPositive1":' + dabThreshold + ',' +
        '"singleThreshold":true' +
    '}'
)

def cells = summary.getChildObjects().findAll { it.isDetection() }.findAll { it.isCell() }
def isPositive = { cell ->
    def className = cell.getPathClass()?.getName()
    className != null && (className.equalsIgnoreCase("Positive") || className.equalsIgnoreCase("Pozitif") || className.endsWith("+"))
}
def areaMm2 = { roi -> roi.getArea() * pw * ph / 1_000_000.0 }
def writeMetrics = { object, roi, localCells ->
    int total = localCells.size()
    int positive = localCells.count { isPositive(it) } as int
    double area = areaMm2(roi)
    object.measurements['ROI alanı (mm2)'] = area
    object.measurements['Toplam çekirdek'] = total as double
    object.measurements['PHH3-pozitif çekirdek (mitoz adayı)'] = positive as double
    // Anahtar belirtece özgüdür: H&E mitoz sihirbazlarının yoğunluk ölçümüyle aynı adı paylaşmaz.
    object.measurements['PHH3+ çekirdek yoğunluğu (çekirdek/mm2)'] = area > 0 ? positive / area : Double.NaN
    object.measurements['PHH3+ nükleer %'] = total > 0 ? 100.0 * positive / total : Double.NaN
}

// Kaynak ROI sonuçları bağımsızdır; birleşik özet örtüşmeleri yalnız bir kez sayar.
targets.each { target ->
    def roi = target.getROI()
    def localCells = cells.findAll { cell ->
        def cellROI = cell.getROI()
        cellROI != null && roi.contains(cellROI.getCentroidX(), cellROI.getCentroidY())
    }
    writeMetrics(target, roi, localCells)
}

int totalNuclei = cells.size()
int totalPos = cells.count { isPositive(it) } as int
double totalAreaMm2 = areaMm2(analysisROI)
double overallDensity = totalAreaMm2 > 0 ? totalPos / totalAreaMm2 : Double.NaN
double overallPct = totalNuclei > 0 ? 100.0 * totalPos / totalNuclei : Double.NaN
double areaToleranceMm2 = 0.01
double observedPerWhoArea = Math.abs(totalAreaMm2 - whoAreaMm2) <= areaToleranceMm2 ? totalPos as double : Double.NaN

writeMetrics(summary, analysisROI, cells)
summary.measurements['Kaynak ROI sayısı'] = targets.size() as double
summary.measurements['PHH3+ çekirdek / 2 mm2 (yalnız 2.00 ± 0.01 mm2 ROI)'] = observedPerWhoArea
summary.setLocked(true)
QP.fireHierarchyUpdate()

def modeNotes = new StringBuilder()
if (!appliedMode.prevNames.isEmpty()) {
    modeNotes << "\n" << String.format(java.util.Locale.US, workshopText('script.7577a3bac9a2', 'DİKKAT — bu lamda önceki sonuç da duruyor: %s\nYeni özet şu adla eklendi: %s\nHiçbir şey silinmedi. Eski özeti kaldırmak için o addaki anotasyonu seçip silin ya da yeniden çalıştırırken "Değiştir"i seçin. Dışa aktarma ve kohort özetleri iki özeti de görür.'), appliedMode.prevNames.join(', '), appliedMode.setName) << "\n"
    modeNotes << workshopText('script.46fd40a69eb4', 'Seçili anotasyonların üzerindeki ölçümler bu YENİ çalıştırmayı gösterir; önceki özet nesnesi eski değerleri göstermeye devam eder.') << "\n"
} else if (appliedMode.removed > 0) {
    modeNotes << "\n" << String.format(java.util.Locale.US, workshopText('script.4c0b2cedea6d', 'Önceki sonuç değiştirildi: %,d özet anotasyonu ve altlarındaki %,d PHH3 tespiti silindi.'), appliedMode.removed, appliedMode.removedDet) << "\n"
}

def fmt = { double value, String pattern ->
    Double.isFinite(value) ? String.format(java.util.Locale.US, pattern, value) : workshopText('script.77fa6502fb41', 'hesaplanamadı')
}
def body = new StringBuilder()
body << workshopText('script.8aa42753c480', 'PHH3 MİTOZ KANTİFİKASYONU\n')
body << "═════════════════════════\n\n"
body << String.format(java.util.Locale.US, workshopText('script.31582a01fb1c', 'Seçili ROI                : %,d%n'), targets.size())
body << String.format(java.util.Locale.US, workshopText('script.21a5a838de8e', 'Birleşik ölçülen alan     : %.3f mm²%n'), totalAreaMm2)
body << String.format(java.util.Locale.US, workshopText('script.092e737d6bec', 'Toplam çekirdek           : %,d%n'), totalNuclei)
body << String.format(java.util.Locale.US, workshopText('script.8b0abc013820', 'PHH3-pozitif (mitoz adayı): %s  (%s)\n'), totalPos, fmt(overallPct, '%.2f%%'))
body << String.format(java.util.Locale.US, workshopText('script.fd456544f28f', 'PHH3+ çekirdek yoğunluğu  : %s\n'), fmt(overallDensity, workshopText('script.3e4207839698', '%.1f çekirdek/mm²')))
if (Double.isFinite(observedPerWhoArea))
    body << String.format(java.util.Locale.US, workshopText('script.831c565d21ef', 'Mitoz / %.0f mm² (gözlenen): %.0f%n'), whoAreaMm2, observedPerWhoArea)
else
    body << String.format(java.util.Locale.US, workshopText('script.de61cbe22dd1', 'Mitoz / %.0f mm²: hesaplanmadı (alan %.3f mm²; tolerans ±%.2f)%n'), whoAreaMm2, totalAreaMm2, areaToleranceMm2)
body << String.format(java.util.Locale.US, workshopText('script.a136016aacb5', '\nPHH3 DAB eşiği: %s; min çekirdek alanı: %s µm²\n\n'), String.format(java.util.Locale.US, '%.2f', dabThreshold), String.format(java.util.Locale.US, '%.0f', minNucleusAreaUm2))
body << modeNotes.toString()
body << workshopText('script.5c506867ba21', 'Birleşik özet örtüşen ROI alanlarını ve hücreleri yalnız bir kez sayar.\n')
body << workshopText('script.55c1e89c5b0d', 'Bu bir SAYIM/YOĞUNLUKtur; mitoz derecesi, grade eşiği veya klinik yorum değildir.\n')
body << workshopText('script.178689e357e5', 'PHH3 pozitifliği mitozla birebir eş değildir; adayları görsel olarak doğrulayın.\n')
body << workshopText('script.cf6099f0a005', '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')

showResultWindow(workshopText('script.3ede552f30d1', 'PHH3 mitoz kantifikasyonu'), body.toString())
println "✓ PHH3 mitoz ölçümü yazıldı (her seçili ROI + '${appliedMode.setName}')."

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
