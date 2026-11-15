/**
 * Yardımcı - Stromal TIL Yoğunluğu (morfolojik ölçüm)
 * ----------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Tümör/Stroma modülü ile ayrılan **Stroma** alanı içinde çekirdek tespiti yapar ve
 * küçük-çekirdekli hücreleri (lenfosit adayı) sayarak **stromal hücre
 * yoğunluğu (hücre/mm²)** üretir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • H&E üzerinde "stromal TIL" görsel skoru (Salgado/IIOBWG) bir
 *     PATOLOG tahminidir. Bu betik onun yerini ALMAZ; yalnızca stromada
 *     küçük yuvarlak çekirdek **yoğunluğunu** ölçer — lenfosit/plazma
 *     hücresi morfolojik vekili. CD3/CD8 İHK doğrulaması gerekir.
 *   • Çıktı bir SAYIM/YOĞUNLUKtur — yüzde TIL, klinik eşik veya yorum DEĞİL.
 *
 * KULLANIM:
 *   1. H&E lamını açın, Image type → "Brightfield (H&E)" veya "(H-DAB)"
 *   2. Önce Tümör/Stroma modülünü çalıştırın → "Stroma" anotasyonları oluşsun
 *   3. [Automate → Project scripts → bu betik]
 *
 * ÇIKTI:
 *   • Her "Stroma" anotasyonuna: "Stromal hücre yoğunluğu (hücre/mm2)" +
 *     "Lenfosit adayı yoğunluğu (hücre/mm2)"
 *   • Kilitli "Stromal TIL Özet" anotasyonu (Veri dışa aktarma modülü ile dışa aktarılır)
 *
 * YÖNTEM REFERANSLARI:
 *   • Salgado R et al. (2015), Ann Oncol — IIOBWG stromal TIL skorlama
 *     standardı (görsel, H&E). doi:10.1093/annonc/mdu450
 *   • Thagaard J et al. (2021), Cancers — otomatik TIL kantifikasyonu
 *     ve patolog uyumu. doi:10.3390/cancers13123050
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

// ── Parametreler (sabit; eklenti tercihlerine bağlı değil) ──────────
double pixelSizeMicrons    = 0.5     // tespit çözünürlüğü
double detectionThreshold  = 0.10    // Hematoxylin OD eşiği
double minNucleusAreaUm2   = 5.0     // çok küçük gürültüyü ele
double maxNucleusAreaUm2   = 80.0    // büyük tümör/stroma çekirdeklerini ele
double lymphocyteMaxAreaUm2 = 40.0   // lenfosit adayı: küçük yuvarlak çekirdek
String detectionChannel    = "Hematoxylin OD"
String summaryName         = "Stromal TIL Özet"

// ── Önceki sonuç kipi (ev kuralı: hiçbir şey sorulmadan silinmez) ───────────
//   ekle     → hiçbir şey silinmez; yeni özet sıradaki adı alır ("<ad> (2)" …).
//   değiştir → yalnız bu yardımcının özet anotasyonları (tam ad ya da "ad (N)") silinir;
//              içlerindeki nesneler korunur (keepChildren = true).
//   Başsız kip: `-a mevcut=ekle` | `-a mevcut=degistir`; önceki sonuç varken belirtilmezse durur.
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
// Bu yardımcının özeti mi? Yalnız TAM ad ya da "ad (N)" — önek eşleşmesi YOK:
// kullanıcının "Stromal TIL Özet notlarım" diye adlandırdığı anotasyon süpürülmez.
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
    def pick = Dialogs.showChoiceDialog(workshopText('script.7206cf547d44', 'Önceki sonuç — Stromal TIL'),
        String.format(java.util.Locale.US, workshopText('script.72caa7fba38b', 'Bu lamda bu yardımcının yazdığı %,d özet anotasyonu zaten var ("Stromal TIL Özet").\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni özet "Stromal TIL Özet (2)" gibi ayrı bir ad alır ve iki özet birlikte durur.\n\n"Değiştir" seçilirse önceki özet anotasyonları silinir. Aynı adı kendiniz verdiyseniz o anotasyon da silinir. Diğer anotasyonlara dokunulmaz.\n\nİptal: hiçbir şey hesaplanmaz, hiçbir şey yazılmaz.\n\nÖnceki sonuç ne olsun?'), prev.size()),
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
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.3587b2e65fb9', "Önce bir H&E lamı açın."))
    return
}

def stromaAnnotations = QP.getAnnotationObjects().findAll { it.getPathClass()?.getName() == "Stroma" }
if (stromaAnnotations.isEmpty()) {
    def msg = "Bu lamda 'Stroma' anotasyonu yok.\n\n" +
              "Önce Tümör/Stroma modülünü (Tümör vs stroma sınıflandırıcı) çalıştırın; o betik\n" +
              "tümör/stroma alanlarını ayırır. Sonra bu betiği tekrar çalıştırın."
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.8094a507868c', "Stroma bulunamadı"), msg)
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
int redetectPrev = redetectCount(stromaAnnotations)
if (redetectPrev > 0) {
    if (isHeadless) {
        if (mevcut != 'degistir') {
            println String.format(java.util.Locale.US, workshopText('script.917f37316768', 'Stroma anotasyonlarında zaten %,d hücre tespiti var. Hiçbir şey yazılmadı. Bu hücreleri değiştirmek için -a mevcut=degistir ile yeniden çalıştırın.'), redetectPrev)
            return
        }
    } else {
        String redetectTitleText = workshopText('script.c8cded1ce05d', 'Önceki hücre tespitleri değiştirilecek')
        String redetectCancelledText = workshopText('script.0187c58bc6df', 'İptal edildi — hiçbir şey çalıştırılmadı, hiçbir şey değişmedi.')
        if (!confirmReplaceCells(redetectTitleText, String.format(java.util.Locale.US, workshopText('script.d500c84220ca', 'Stroma anotasyonlarında zaten %,d hücre var. Tespit bu hücrelerin yerini alacak; elle verdiğiniz sınıflar ve düzeltmeler de gider.\n\nDevam edilsin mi?\n\n"Hayır" derseniz hiçbir şey çalıştırılmaz ve hiçbir şey değişmez.'), redetectPrev))) {
            Dialogs.showInfoNotification(redetectTitleText, redetectCancelledText)
            println redetectCancelledText
            return
        }
    }
}

// ── 2) Stroma içinde çekirdek tespiti ──────────────────────────────
println "Stroma alanında çekirdek tespiti..."
QP.selectObjects(stromaAnnotations)
QP.runPlugin(
    'qupath.imagej.detect.cells.PositiveCellDetection',
    '{' +
        '"detectionImageBrightfield":"' + detectionChannel + '",' +
        '"requestedPixelSizeMicrons":' + pixelSizeMicrons + ',' +
        '"backgroundRadiusMicrons":8.0,' +
        '"medianRadiusMicrons":0.0,' +
        '"sigmaMicrons":1.0,' +
        '"minAreaMicrons":' + minNucleusAreaUm2 + ',' +
        '"maxAreaMicrons":' + maxNucleusAreaUm2 + ',' +
        '"threshold":' + detectionThreshold + ',' +
        '"watershedPostProcess":true,' +
        '"cellExpansionMicrons":2.0,' +
        '"includeNuclei":true,' +
        '"smoothBoundaries":true,' +
        '"makeMeasurements":true,' +
        '"thresholdCompartment":"Nucleus: DAB OD mean",' +
        '"thresholdPositive1":0.20,' +
        '"thresholdPositive2":0.40,' +
        '"thresholdPositive3":0.60,' +
        '"singleThreshold":true' +
    '}'
)

// ── 3) Hücreleri topla, lenfosit adaylarını ayır ────────────────────
def nucAreaKeys = ["Nucleus: Area µm^2", "Nucleus: Area", "Nucleus: Area um^2"]
def nucArea = { cell ->
    for (k in nucAreaKeys) { def v = cell.measurements[k]; if (v != null) return v as double }
    return Double.NaN
}

def areaMm2 = { ann ->
    def roi = ann.getROI()
    roi != null ? (roi.getArea() * pw * ph) / 1_000_000.0 : 0.0
}

int totalCells = 0
int totalLympho = 0
int areaKnown = 0
double totalStromaMm2 = 0.0

stromaAnnotations.each { ann ->
    def cells = ann.getChildObjects().findAll { it.isDetection() }
    double aMm2 = areaMm2(ann)
    totalStromaMm2 += aMm2

    int n = cells.size()
    int nLympho = cells.count { def a = nucArea(it); !Double.isNaN(a) && a > 0 && a <= lymphocyteMaxAreaUm2 } as int
    if (cells.any { !Double.isNaN(nucArea(it)) }) areaKnown++

    totalCells += n
    totalLympho += nLympho

    double dCell = aMm2 > 0 ? n / aMm2 : 0.0
    double dLympho = aMm2 > 0 ? nLympho / aMm2 : 0.0
    ann.measurements['Stromal hücre yoğunluğu (hücre/mm2)']    = dCell
    ann.measurements['Lenfosit adayı yoğunluğu (hücre/mm2)']   = dLympho
}

double overallCellDensity = totalStromaMm2 > 0 ? totalCells / totalStromaMm2 : 0.0
double overallLymphoDensity = totalStromaMm2 > 0 ? totalLympho / totalStromaMm2 : 0.0

// Hiç hücre çıkmadıysa sessiz "0" sonucu yanıltıcıdır — nedenini söyle.
if (totalCells == 0) {
    def zmsg = "Stroma alanında hiç hücre tespit edilmedi.\n" +
        "Olası nedenler: yanlış boya vektörü / Image type (ör. H&E yerine DAB ya da tersi), " +
        "çok yüksek tespit eşiği, ya da 'Stroma' anotasyonunun hücresiz boş alana denk gelmesi.\n" +
        "Boya vektörlerini ve eşiği kontrol edip yeniden çalıştırın."
    if (isHeadless) println "UYARI: ${zmsg}" else Dialogs.showWarningNotification(workshopText('script.b2d0e9e97b0d', "Hücre bulunamadı"), zmsg)
}

// ── 4) Kilitli özet anotasyonu (Veri dışa aktarma modülü için) ─────────
// Önceki özet SORULMADAN silinmez: kip yukarıda seçildi (ekle: sıradaki ad; değiştir: yalnız kendi özetleri).
def appliedMode = applyMode(imageData.getHierarchy(), runMode)
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName(appliedMode.setName)
summary.measurements['Stroma alanı (mm2)']                    = totalStromaMm2
summary.measurements['Stromal hücre sayısı']                  = totalCells as double
summary.measurements['Lenfosit adayı sayısı']                 = totalLympho as double
summary.measurements['Stromal hücre yoğunluğu (hücre/mm2)']   = overallCellDensity
summary.measurements['Lenfosit adayı yoğunluğu (hücre/mm2)']  = overallLymphoDensity
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

def areaNote = areaKnown == 0
    ? "\n⚠️ Çekirdek alan ölçümü bulunamadı → lenfosit adayı boyut filtresi uygulanamadı;\n   tüm stromal çekirdekler sayıldı. (Tespit ayarlarını kontrol edin.)"
    : ""

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
body << "STROMAL TIL YOĞUNLUĞU (morfolojik vekil)\n"
body << "════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Stroma alanı            : %.3f mm²%n", totalStromaMm2)
body << String.format(java.util.Locale.US, "Stromal hücre (toplam)  : %,d  →  %.0f hücre/mm²%n", totalCells, overallCellDensity)
body << String.format(java.util.Locale.US, "Lenfosit adayı (≤%.0f µm²): %,d  →  %.0f hücre/mm²%n",
        lymphocyteMaxAreaUm2, totalLympho, overallLymphoDensity)
body << areaNote << "\n"
body << modeNotes.toString() << "\n"
body << "Yöntem: stromada çekirdek tespiti; küçük yuvarlak çekirdek = lenfosit vekili.\n"
body << "Bu bir SAYIM/YOĞUNLUKtur — IIOBWG görsel TIL%'i, klinik eşik veya yorum DEĞİL.\n"
body << "Lenfosit kimliği için CD3/CD8 İHK doğrulaması gerekir (Salgado 2015; Thagaard 2021).\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Stromal TIL yoğunluğu", body.toString())
println "✓ Stromal TIL yoğunluğu yazıldı (her Stroma anotasyonu + '${appliedMode.setName}')."

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
