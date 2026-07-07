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
 *   1. Kalibre (µm/px) bir slayt açın; A ve B sınıflı alan anotasyonları bulunsun
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
 *
 * YÖNTEM / KAYNAK:
 *   • JTS (Java Topology Suite) geometri kesişimi — QuPath içinde. Dikkat: JTS'in
 *     getArea() yöntemi PİKSEL² döndürür; µm² için pw·ph ile çarpılır.
 *   • Teknik esin: JamesCrichton/QuPath-Scripting-Workshop (Exeter Üniversitesi, 2025),
 *     "Islet Nerve Overlap" — egzersiz verisi Zenodo doi:10.5281/zenodo.17940263 (CC-BY-4.0).
 *     Depo açık-kaynak lisansı taşımaz; bu betik teknik yeniden yazımdır (koddan aktarım yok).
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
            def __footer = new javafx.scene.control.Label("QuPath Atölye Scriptleri · araştırma/eğitim amaçlı")
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

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce A ve B sınıflı alan anotasyonları olan bir slayt açın.")
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok", "Slaytta piksel boyutu (µm) tanımlı değil; alan µm² olarak hesaplanamaz." +
        "\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu).")
    return
}

// Görüntüdeki, SINIFI olan alan anotasyonlarının sınıf adları (özet hariç)
def classNames = QP.getAnnotationObjects()
    .findAll { it.getROI()?.isArea() && it.getPathClass() != null && it.getName() != summaryName }
    .collect { it.getPathClass().toString() }
    .unique()
    .sort()

if (classNames.size() < 2) {
    def msg = "Kesişim için en az İKİ farklı sınıfta alan anotasyonu gerekir.\n" +
              "Mevcut sınıf(lar): " + (classNames.join(', ') ?: '(yok)') + "\n\n" +
              "Anotasyonları elle çizip sınıflayın ya da bir piksel sınıflandırıcı (Tümör/Stroma modülü) çalıştırın."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Yetersiz sınıf", msg)
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
    classA = Dialogs.showChoiceDialog("Kesişim alanı — A sınıfı",
        "Ölçüm hangi sınıfın anotasyonlarına yazılsın? (A — 'B ile örtüşme' burada birikir)", classNames, defA)
    if (classA == null) { println "İptal edildi."; return }
    def bOpts = classNames.findAll { it != classA }
    def defB = bOpts.contains(prefs.get('classB', '')) ? prefs.get('classB', '') : bOpts[0]
    classB = Dialogs.showChoiceDialog("Kesişim alanı — B sınıfı",
        "A'nın hangi sınıfla (B) örtüşmesi ölçülsün?", bOpts, defB)
    if (classB == null) { println "İptal edildi."; return }
}
if (classA == classB) {
    Dialogs.showErrorMessage("Aynı sınıf", "A ve B sınıfı farklı olmalı.")
    return
}
prefs.put('classA', classA); prefs.put('classB', classB)
try { prefs.flush() } catch (Throwable ig) {}

// ── 3) Anotasyonları topla ──────────────────────────────────────────
def aAnnos = QP.getAnnotationObjects().findAll { it.getROI()?.isArea() && it.getPathClass()?.toString() == classA }
def bAnnos = QP.getAnnotationObjects().findAll { it.getROI()?.isArea() && it.getPathClass()?.toString() == classB }
if (aAnnos.isEmpty() || bAnnos.isEmpty()) {
    def msg = "Seçilen sınıflarda alan anotasyonu kalmadı (A: ${aAnnos.size()}, B: ${bAnnos.size()})."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Anotasyon yok", msg)
    return
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

String measArea = "Kesişen ${classB} alanı (µm²)"
String measPct  = "Kesişen ${classB} oranı (%)"
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
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == summaryName }, false)
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName(summaryName)
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
