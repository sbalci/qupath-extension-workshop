/**
 * Yardımcı - Kesişim Alanı (iki kompartman örtüşmesi)
 * ----------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Bir sınıfın (A) her alan anotasyonu için, başka bir sınıfın (B) anotasyonlarıyla
 *   olan GEOMETRİK KESİŞİM ALANINI (µm²) hesaplar ve A anotasyonuna ölçüm olarak yazar.
 *   "A kompartmanının ne kadarı B ile örtüşüyor?" sorusunu niceler — ör. tümör içindeki
 *   immün alan, sinir–tümör örtüşmesi (peri-nöral invazyon analoğu), bez içi boyalı alan.
 *
 *   Yöntem: A'nın geometrisi ile B anotasyonlarının BİRLEŞİK geometrisinin kesişimi
 *   alınır (geomA.intersection(B_union).getArea()). Birleşik B kullanmak, üst üste binen
 *   B anotasyonlarının kesişim alanını iki kez saymayı önler.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • VAR OLAN alan anotasyonları arasındaki örtüşme ALANINI ölçer. Bu bir ALAN
 *     ölçümüdür — klinik skor, eşik, evre veya yorum DEĞİL.
 *   • Anotasyonları (A ve B) sizin çizmeniz/üretmeniz gerekir (elle ya da piksel
 *     sınıflandırıcı — Tümör/Stroma modülü). Betik anotasyon üretmez.
 *   • Yalnız ALAN anotasyonlarıyla çalışır (nokta/çizgi ROI atlanır).
 *
 * QuPath KARŞILIĞI (GUI):
 *   Yerleşik tek komut yoktur; elle her çift için geometri kesişimi hesaplamak
 *   gerekir. Bu betik seçilen iki sınıf için bunu tek adımda üretir.
 *
 * KULLANIM:
 *   1. Kalibre (µm/px) bir lam açın; A ve B sınıflı alan anotasyonları bulunsun
 *   2. [Extensions → Atölye → Yardımcılar → Uzamsal analiz → Kesişim alanı (örtüşme)]
 *      (ya da [Automate → Project scripts → bu betik])
 *   3. Açılan iletişimlerde A ve B sınıflarını seçin
 *
 * ÇIKTI:
 *   • Her A anotasyonu: "Kesişen <B> alanı (µm²)" + "Kesişen <B> oranı (%)" ölçümleri
 *     (Veri dışa aktarma modülü ile dışa aktarılır)
 *   • Kilitli "Kesişim Alanı Özet": A/B adet, toplam A alanı, toplam kesişim, genel
 *     ve ortalama örtüşme %, örtüşen A adedi
 *   • Sonuç penceresinde özet tablo
 *   • Önceki özet varsa SORAR: "Ekle" (varsayılan) hiçbir şey silmez, yeni özet
 *     "Kesişim Alanı Özet (2)" gibi adlanır; "Değiştir" yalnız bu yardımcının özetlerini siler.
 *     Başsız kip: -a mevcut=ekle | -a mevcut=degistir (belirtilmezse durur, hiçbir şey yazmaz).
 *
 * YÖNTEM / KAYNAK:
 *   • JTS (Java Topology Suite) geometri kesişimi — QuPath içinde. Dikkat: JTS'in
 *     getArea() yöntemi PİKSEL² döndürür; µm² için pw·ph ile çarpılır.
 *   • Teknik esin: JamesCrichton/QuPath-Scripting-Workshop (Exeter Üniversitesi, 2025),
 *     "Islet Nerve Overlap" — egzersiz verisi Zenodo doi:10.5281/zenodo.17940263 (CC-BY-4.0).
 *     Depo açık kaynak lisansı taşımaz; bu betik teknik yeniden yazımdır (koddan aktarım yok).
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/kesisim-alani')

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

            stage.setScene(new javafx.scene.Scene(root, 720, 520))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Sabitler ────────────────────────────────────────────────────────
String summaryName = "Kesişim Alanı Özet"

// ── Önceki sonuç: SORULMADAN silinmez (ev kuralı) ────────────────────
// Önceki sonuç = bu yardımcının yazdığı özet anotasyonu: TAM ad ya da "ad (N)" (N >= 2) — önek
// eşleşmesi YOK; kullanıcının "… notlarım" diye adlandırdığı anotasyon süpürülmez.
// Varsayılan kip HİÇBİR ŞEY SİLMEZ: Ekle → yeni özet "Kesişim Alanı Özet (2)", "(3)" … adını alır.
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
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

// Başsız kip: -a mevcut=ekle | -a mevcut=degistir. Bilinmeyen değer HER durumda durdurur (fail closed).
String mevcutArg = null
if (isHeadless) {
    def scriptArgs = []
    try { if (binding.hasVariable('args')) { def a = binding.getVariable('args'); if (a != null) scriptArgs = (a as List) } } catch (Throwable ignore) {}
    def argMap = [:]
    scriptArgs.each { a -> def sa = a?.toString() ?: ''; int ia = sa.indexOf('='); if (ia > 0) argMap[sa.substring(0, ia).trim()] = sa.substring(ia + 1).trim() }
    if (argMap.mevcut != null) {
        mevcutArg = argMap.mevcut.toLowerCase(java.util.Locale.ROOT)
        if (!['ekle', 'degistir'].contains(mevcutArg)) {
            println String.format(java.util.Locale.US, workshopText('script.768b9abc88f3', 'HATA: mevcut yalnız \'ekle\' ya da \'degistir\' olabilir (verilen: %s). Hiçbir şey yazılmadı.'), argMap.mevcut); return
        }
    }
}

// Önceki sonuç için kullanıcıya SOR (HESAPLAMADAN önce çağrılır).
//   Dönüş: 'add' | 'replace' | null (İptal → hiçbir şey hesaplanmaz, hiçbir şey yazılmaz).
def askExisting = { hierarchy, String measAreaName, String measPctName ->
    def prev = prevOwnObjects(hierarchy, summaryName)
    if (prev.isEmpty()) return MODE_ADD
    String optAdd = workshopText('script.e6b0dca2f71e', 'Öncekine dokunma — yeni bir özet olarak ekle (varsayılan)')
    String optRep = workshopText('script.84d3e02923f2', 'Öncekini değiştir (bu yardımcının özeti silinir)')
    def pick = Dialogs.showChoiceDialog(workshopText('script.16e598cbc210', 'Önceki kesişim alanı özeti'),
        String.format(java.util.Locale.US, workshopText('script.de2339c36248', 'Bu lamda bu yardımcının yazdığı %,d özet anotasyonu zaten var.\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni özet ayrı bir anotasyon olur ("%s (2)" gibi).\n\n"Değiştir" seçilirse önceki özet anotasyonları silinir. Kendi anotasyonlarınıza dokunulmaz.\n\nHer iki durumda da A anotasyonlarındaki "%s" ve "%s" ölçümleri bu çalıştırmanın değeriyle yeniden yazılır.\n\nİptal: hiçbir şey hesaplanmaz, hiçbir şey yazılmaz.\n\nÖnceki özet ne olsun?'), prev.size(), summaryName, measAreaName, measPctName),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.89a856ac207d', 'Önceki özet silinecek'),
            String.format(java.util.Locale.US, workshopText('script.78d2e9b841ed', '%,d özet anotasyonu silinecek; bunlardaki ölçümler kaybolur. Kendi anotasyonlarınıza dokunulmaz. A anotasyonlarındaki "%s" ve "%s" ölçümleri bu çalıştırmanın değeriyle yazılır.\n\nDevam edilsin mi?'), prev.size(), measAreaName, measPctName))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.daf4df7bf253', "Önce A ve B sınıflı alan anotasyonları olan bir lam açın."))
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage(workshopText('script.a5a01874aff2', "Kalibrasyon yok"), workshopText('script.542aac19e376', "Lamda piksel boyutu (µm) tanımlı değil; alan µm² olarak hesaplanamaz." +
        "\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu)."))
    return
}

// Görüntüdeki, SINIFI olan alan anotasyonlarının sınıf adları (özet hariç)
def classNames = QP.getAnnotationObjects()
    .findAll { it.getROI()?.isArea() && it.getPathClass() != null && !isOwnSetName(it.getName(), summaryName) }
    .collect { it.getPathClass().toString() }
    .unique()
    .sort()

if (classNames.size() < 2) {
    def msg = "Kesişim için en az İKİ farklı sınıfta alan anotasyonu gerekir.\n" +
              "Mevcut sınıf(lar): " + (classNames.join(', ') ?: '(yok)') + "\n\n" +
              "Anotasyonları elle çizip sınıflayın ya da bir piksel sınıflandırıcı (Tümör/Stroma modülü) çalıştırın."
    if (isHeadless) println msg else Dialogs.showErrorMessage(workshopText('script.f34b330b89e2', "Yetersiz sınıf"), msg)
    return
}

// ── 2) A ve B sınıfını belirle ──────────────────────────────────────
String classA
String classB
if (isHeadless) {
    classA = prefs.get('classA', '')
    classB = prefs.get('classB', '')
    if (!classNames.contains(classA) || !classNames.contains(classB)) {
        println "Headless: 'classA'/'classB' tercihleri ayarlı değil ya da bu görüntüde yok.\n" +
                "Önce arayüzden bir kez çalıştırın. Mevcut sınıflar: ${classNames.join(', ')}"
        return
    }
} else {
    def defA = classNames.contains(prefs.get('classA', '')) ? prefs.get('classA', '') : classNames[0]
    classA = Dialogs.showChoiceDialog(workshopText('script.c2526a49ba64', "Kesişim alanı — A sınıfı"),
        workshopText('script.b0d880955b72', "Ölçüm hangi sınıfın anotasyonlarına yazılsın? (A — 'B ile örtüşme' burada birikir)"), classNames, defA)
    if (classA == null) { println "İptal edildi."; return }
    def bOpts = classNames.findAll { it != classA }
    def defB = bOpts.contains(prefs.get('classB', '')) ? prefs.get('classB', '') : bOpts[0]
    classB = Dialogs.showChoiceDialog(workshopText('script.837fc98ea13b', "Kesişim alanı — B sınıfı"),
        workshopText('script.0a644bad5227', "A'nın hangi sınıfla (B) örtüşmesi ölçülsün?"), bOpts, defB)
    if (classB == null) { println "İptal edildi."; return }
}
if (classA == classB) {
    Dialogs.showErrorMessage(workshopText('script.aaae99e0c668', "Aynı sınıf"), workshopText('script.53e35a091a93', "A ve B sınıfı farklı olmalı."))
    return
}
prefs.put('classA', classA); prefs.put('classB', classB)
try { prefs.flush() } catch (Throwable ig) {}

// ── 3) Anotasyonları topla ──────────────────────────────────────────
def aAnnos = QP.getAnnotationObjects().findAll { it.getROI()?.isArea() && it.getPathClass()?.toString() == classA }
def bAnnos = QP.getAnnotationObjects().findAll { it.getROI()?.isArea() && it.getPathClass()?.toString() == classB }
if (aAnnos.isEmpty() || bAnnos.isEmpty()) {
    def msg = "Seçilen sınıflarda alan anotasyonu kalmadı (A: ${aAnnos.size()}, B: ${bAnnos.size()})."
    if (isHeadless) println msg else Dialogs.showErrorMessage(workshopText('script.469472ee53f3', "Anotasyon yok"), msg)
    return
}

// ── 3b) Önceki özet var mı? HESAPLAMADAN ÖNCE sor ─────────────────────
String measArea = "Kesişen ${classB} alanı (µm²)"
String measPct  = "Kesişen ${classB} oranı (%)"
def imgHierarchy = imageData.getHierarchy()
def prevOwn = prevOwnObjects(imgHierarchy, summaryName)
def prevNames = prevOwn.collect { it.getName() }.unique().sort()
String writeMode = MODE_ADD
if (!prevOwn.isEmpty()) {
    if (isHeadless) {
        if (mevcutArg == null) {
            println String.format(java.util.Locale.US, workshopText('script.9ff4de821d21', 'Bu lamda bu yardımcının yazdığı %,d özet anotasyonu ("%s") zaten var. Hiçbir şey yazılmadı. Yeni bir kopya eklemek için -a mevcut=ekle, önceki sonucu değiştirmek için -a mevcut=degistir ile yeniden çalıştırın.'), prevOwn.size(), summaryName); return
        }
        writeMode = (mevcutArg == 'degistir') ? MODE_REPLACE : MODE_ADD
    } else {
        writeMode = askExisting(imgHierarchy, measArea, measPct)
        if (writeMode == null) { println "İptal edildi."; return }
    }
}

// B anotasyonlarının BİRLEŞİK geometrisi (üst üste binenleri iki kez saymamak için)
def bUnion = null
bAnnos.each { b ->
    try {
        def g = b.getROI().getGeometry()
        if (g != null && !g.isEmpty()) bUnion = (bUnion == null) ? g : bUnion.union(g)
    } catch (Throwable ignored) { }
}

// ── 4) Her A için kesişim alanı (µm²) ───────────────────────────────
println String.format(java.util.Locale.US,
    "Kesişim alanı hesaplanıyor: A='%s' (%d anot.) ∩ B='%s' (%d anot.)...",
    classA, aAnnos.size(), classB, bAnnos.size())

double totalAreaA = 0.0
double totalInter = 0.0
int overlapCount = 0
int errCount = 0
def pctList = []

aAnnos.each { a ->
    def ga = a.getROI().getGeometry()
    // JTS getArea() piksel² döner → µm² için pw·ph ile çarpılır (piksel→µm² birim tuzağı).
    double aAreaUm2 = ga.getArea() * pw * ph
    double interUm2 = 0.0
    if (bUnion != null) {
        try {
            def inter = ga.intersection(bUnion)
            interUm2 = (inter != null && !inter.isEmpty()) ? inter.getArea() * pw * ph : 0.0
        } catch (Throwable t) {
            // Geçersiz/kendini kesen poligon → buffer(0) ile onar, yeniden dene
            try {
                def inter = ga.buffer(0).intersection(bUnion.buffer(0))
                interUm2 = (inter != null && !inter.isEmpty()) ? inter.getArea() * pw * ph : 0.0
            } catch (Throwable t2) { interUm2 = 0.0; errCount++ }
        }
    }
    double pct = (aAreaUm2 > 0) ? 100.0 * interUm2 / aAreaUm2 : 0.0
    a.measurements[measArea] = interUm2
    a.measurements[measPct]  = pct
    totalAreaA += aAreaUm2
    totalInter += interUm2
    if (interUm2 > 0) overlapCount++
    pctList << pct
}

double overallPct = (totalAreaA > 0) ? 100.0 * totalInter / totalAreaA : 0.0
double meanPct    = pctList.isEmpty() ? 0.0 : (pctList.sum() as double) / pctList.size()

// ── 5) Kilitli özet anotasyonu ──────────────────────────────────────
// Önceki özet: kip çağırandan gelir. Ekle → hiçbir şey silinmez; Değiştir → yalnız bu yardımcının
// özetleri (tam ad ya da "(N)") silinir, içlerindeki nesneler korunur.
int removedPrev = 0
String thisSummaryName
if (writeMode == MODE_REPLACE) {
    def prevNow = prevOwnObjects(imgHierarchy, summaryName)
    removedPrev = prevNow.size()
    if (removedPrev > 0) QP.removeObjects(prevNow, true)
    thisSummaryName = summaryName
} else {
    thisSummaryName = nextOwnSetName(imgHierarchy, summaryName)
}
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName(thisSummaryName)
summary.measurements['A anotasyon sayısı']        = aAnnos.size() as double
summary.measurements['B anotasyon sayısı']        = bAnnos.size() as double
summary.measurements['Toplam A alanı (µm²)']      = totalAreaA
summary.measurements['Toplam kesişim alanı (µm²)'] = totalInter
summary.measurements['Genel örtüşme (%)']         = overallPct
summary.measurements['Ortalama örtüşme (%)']      = meanPct
summary.measurements['Örtüşen A anotasyonu (adet)'] = overlapCount as double
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

// ── 6) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "KESİŞİM ALANI (iki kompartman örtüşmesi)\n"
body << "════════════════════════════════════════════\n\n"
body << "A sınıfı (ölçüm)   : ${classA}\n"
body << "B sınıfı (örtüşen) : ${classB}\n"
body << String.format(java.util.Locale.US, "A / B anotasyon    : %,d / %,d%n", aAnnos.size(), bAnnos.size())
body << "\n"
body << String.format(java.util.Locale.US, "Toplam A alanı     : %,.0f µm²%n", totalAreaA)
body << String.format(java.util.Locale.US, "Toplam kesişim     : %,.0f µm²%n", totalInter)
body << "──────────────────────────────────────────────\n"
body << String.format(java.util.Locale.US, "Genel örtüşme      : %.1f %%%n", overallPct)
body << String.format(java.util.Locale.US, "Ortalama örtüşme   : %.1f %%  (A başına)%n", meanPct)
body << String.format(java.util.Locale.US, "Örtüşen A adedi    : %,d / %,d%n", overlapCount, aAnnos.size())
if (errCount > 0)
    body << String.format(java.util.Locale.US, "Atlanan (geçersiz) : %,d%n", errCount)
body << "\n"
body << String.format(java.util.Locale.US, workshopText('script.a9b6daa16201', 'Özet adı           : %s'), thisSummaryName) << "\n"
if (writeMode == MODE_ADD && !prevNames.isEmpty()) {
    body << "\n" << String.format(java.util.Locale.US, workshopText('script.64c8beb1374c', 'DİKKAT — bu lamda önceki özet de duruyor: %s\nHiçbir şey silinmedi. Eskisini kaldırmak için o addaki anotasyonu seçip silin ya da yardımcıyı yeniden çalıştırırken "Öncekini değiştir"i seçin.\nA anotasyonlarındaki "%s" ve "%s" ölçümleri bu çalıştırmanın değerlerini gösterir; önceki özet anotasyonu ise önceki çalıştırmanın değerlerini gösterir.'), prevNames.join(', '), measArea, measPct) << "\n"
} else if (removedPrev > 0) {
    body << "\n" << String.format(java.util.Locale.US, workshopText('script.be27a1bc19b6', 'Önceki özet değiştirildi: %,d anotasyon silindi.'), removedPrev) << "\n"
}
body << "\n"
body << "Her A anotasyonuna '${measArea}' ve\n"
body << "'${measPct}' ölçümleri yazıldı (Veri dışa aktarma modülü ile dışa aktarılır).\n"
body << "Ölçüme göre renklendirmek için: Measure → Show measurement maps.\n\n"
body << "Not: JTS geometri alanı piksel² döner; µm²'ye pw·ph ile ölçeklenir.\n"
body << "Bu bir ALAN ölçümüdür — klinik skor, eşik veya yorum DEĞİL.\n"
body << "(Teknik esin: Crichton/Exeter 2025; Bankhead 2017)\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Kesişim alanı", body.toString())
println String.format(java.util.Locale.US,
    "✓ Kesişim alanı yazıldı: '%s' ∩ '%s' → genel örtüşme %%%.1f (%,d/%,d A örtüşüyor).",
    classA, classB, overallPct, overlapCount, aAnnos.size())

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
