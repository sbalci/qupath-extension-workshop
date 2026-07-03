/**
 * Yardımcı - Peritümöral Bant (halka / kenar bandı)
 * --------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   SEÇİLİ alan anotasyonlarının (tümör, bez, lezyon...) çevresinde belirli
 *   genişlikte bir HALKA (bant) anotasyonu üretir. Yöntem: geometriyi buffer()
 *   ile genişletip/daraltıp difference() ile özgün şekli çıkararak yalnız
 *   kenar bandını bırakır (JTS geometri cebri). Üç yön:
 *     • Dışa  — buffer(+r).difference(orij)          → peritümöral (dış) halka
 *     • İçe   — orij.difference(buffer(−r))           → iç kenar bandı
 *     • Her iki yön — buffer(+r).difference(buffer(−r)) → sınırı saran invazif kenar bandı
 *
 * NEDEN: İnvazif kenar / peritümöral bölge, TIL skorlama (Ek O), tümör-stroma
 *   (Ek L) ve immün bağlam analizlerinde standart bir ROI'dir. Bu bant, "Yapıya
 *   uzaklık" yardımcısının (mesafe ile filtreleme) tamamlayıcısıdır: mesafe
 *   ölçmek yerine bölgeyi KALICI BİR ANOTASYON SINIFI olarak üretir; böylece
 *   içinde hücre tespiti/piksel sınıflandırıcı çalıştırıp birinci sınıf bir ROI
 *   gibi kullanabilirsiniz.
 *
 * NE ÜRETİR (ve ne ÜRETMEZ):
 *   • Yalnız GEOMETRİ üretir (halka anotasyonu + bant alanı µm²). Hücre tespiti,
 *     skor, eşik, evre veya klinik yorum ÜRETMEZ.
 *   • Kaynak yapıyı (tümör vb.) sizin çizip SEÇMENİZ gerekir.
 *   • Yalnız ALAN anotasyonlarıyla çalışır (nokta/çizgi ROI atlanır).
 *
 * QuPath KARŞILIĞI (GUI):
 *   [Objects → Annotations → Expand annotations] anotasyonu genişletir ama özgün
 *   şekli çıkarıp yalnız halkayı bırakmaz. Bu betik buffer+difference ile temiz
 *   bir bant üretir; "Her iki yön" seçeneği sınırı saran bandı tek adımda verir.
 *
 * KULLANIM:
 *   1. Kalibre (µm/px) bir slayt açın; kaynak yapıyı anotasyon olarak çizip SEÇİN
 *      (birden çok da olabilir)
 *   2. [Extensions → Atölye → Yardımcılar → Uzamsal analiz → Peritümöral bant (halka)]
 *      (ya da [Automate → Project scripts → bu betik])
 *   3. Bant genişliğini (µm) ve yönü seçin
 *
 * ÇIKTI:
 *   • Her kaynak için bir "Peritümöral bant" sınıfı halka anotasyonu
 *     ("Bant alanı (µm²)" + "Bant genişliği (µm)" ölçümleriyle)
 *   • Sonuç penceresinde özet tablo
 *   • Her çalıştırma önceki "Peritümöral bant" anotasyonlarını YENİLER
 *     (istediğiniz tüm kaynakları birlikte seçip tek seferde çalıştırın)
 *
 * YÖNTEM / KAYNAK:
 *   • JTS (Java Topology Suite) buffer + difference — QuPath içinde. Dikkat:
 *     JTS getArea() PİKSEL² döndürür; µm² için pw·ph ile çarpılır.
 *   • Teknik esin: JamesCrichton/QuPath-Scripting-Workshop (Exeter Üniversitesi, 2025),
 *     "Islet Ring" (buffer→difference) — depo açık-kaynak lisansı taşımaz; bu betik
 *     teknik yeniden yazımdır (koddan aktarım yok).
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı geometri üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/peritumoral-bant')

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
String BAND_CLASS = "Peritümöral bant"
String DIR_OUT    = "Dışa (peritümöral halka)"
String DIR_IN     = "İçe (iç kenar bandı)"
String DIR_BOTH   = "Her iki yön (invazif kenar bandı)"
def dirOpts = [DIR_OUT, DIR_IN, DIR_BOTH]
double defWidth = 50.0

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce kaynak yapıyı (tümör vb.) anotasyon olarak çizip seçin.")
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
double avgUm = cal.getAveragedPixelSizeMicrons()
if (!(pw > 0) || !(ph > 0) || !(avgUm > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok", "Slaytta piksel boyutu (µm) tanımlı değil; bant µm cinsinden üretilemez." +
        "\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu).")
    return
}

def targets = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI()?.isArea() && it.getPathClass()?.toString() != BAND_CLASS
}
if (targets.isEmpty()) {
    def msg = "Kaynak yapı seçili değil.\n\n" +
              "Etrafında bant oluşturulacak yapıyı (tümör, bez, lezyon vb.)\n" +
              "ALAN anotasyonu olarak çizin ve SEÇİN, sonra betiği tekrar çalıştırın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Yapı seçili değil", msg)
    return
}

// ── 2) Genişlik + yön ───────────────────────────────────────────────
double widthUm
String direction
if (isHeadless) {
    try { widthUm = Double.parseDouble(prefs.get('widthUm', String.valueOf(defWidth))) } catch (Throwable t) { widthUm = defWidth }
    direction = prefs.get('direction', DIR_OUT)
    if (!dirOpts.contains(direction)) direction = DIR_OUT
} else {
    def wTxt = Dialogs.showInputDialog("Peritümöral bant",
        "Bant genişliği (µm):", String.format(java.util.Locale.US, "%.0f", defWidth))
    if (wTxt == null) { println "İptal edildi."; return }
    try { widthUm = Double.parseDouble(wTxt.trim().replace(',', '.')) }
    catch (Throwable t) { Dialogs.showErrorMessage("Geçersiz sayı", "Bant genişliği bir sayı olmalı (µm)."); return }
    def defDir = dirOpts.contains(prefs.get('direction', DIR_OUT)) ? prefs.get('direction', DIR_OUT) : DIR_OUT
    direction = Dialogs.showChoiceDialog("Peritümöral bant", "Bant yönü:", dirOpts, defDir)
    if (direction == null) { println "İptal edildi."; return }
}
if (!(widthUm > 0)) {
    Dialogs.showErrorMessage("Geçersiz genişlik", "Bant genişliği pozitif olmalı (µm).")
    return
}
prefs.put('widthUm', String.valueOf(widthUm)); prefs.put('direction', direction)
try { prefs.flush() } catch (Throwable ig) {}

double rPx = widthUm / avgUm     // µm → piksel (buffer piksel koordinatında çalışır)
String dirShort = (direction == DIR_IN) ? "iç bant" : ((direction == DIR_BOTH) ? "kenar bandı" : "dış bant")

// ── 3) Önceki bantları temizle (idempotent) ────────────────────────
def stale = QP.getAnnotationObjects().findAll { it.getPathClass()?.toString() == BAND_CLASS }
if (!stale.isEmpty()) QP.removeObjects(stale, false)

// ── 4) Her kaynak için halka üret ───────────────────────────────────
def bandClass = QP.getPathClass(BAND_CLASS)
def created = []
int skipped = 0
def rows = []

targets.eachWithIndex { t, i ->
    def roi = t.getROI()
    def orig = roi.getGeometry()
    def plane = roi.getImagePlane()
    def ring = null
    try {
        if (direction == DIR_OUT) {
            ring = orig.buffer(rPx).difference(orig)
        } else if (direction == DIR_IN) {
            def inner = orig.buffer(-rPx)
            // İç tampon çöktüyse (bant, şekilden geniş) tüm şekli döndürme — atla.
            ring = (inner == null || inner.isEmpty()) ? null : orig.difference(inner)
        } else {
            def inner = orig.buffer(-rPx)
            def outer = orig.buffer(rPx)
            // İç tampon çöktüyse yalnız dış bandı ver (tüm tamponu değil).
            ring = (inner == null || inner.isEmpty()) ? outer.difference(orig) : outer.difference(inner)
        }
    } catch (Throwable t2) {
        try {
            def ob = orig.buffer(0)
            ring = (direction == DIR_IN) ? ob.difference(ob.buffer(-rPx)) : ob.buffer(rPx).difference(ob)
        } catch (Throwable t3) { ring = null }
    }
    if (ring == null || ring.isEmpty()) { skipped++; return }

    def srcName = t.getName() ?: (t.getPathClass()?.toString()) ?: "bölge ${i + 1}"
    def ringROI = qupath.lib.roi.GeometryTools.geometryToROI(ring, plane)
    if (ringROI == null || ringROI.isEmpty() || !ringROI.isArea()) { skipped++; return }

    def ringAnno = qupath.lib.objects.PathObjects.createAnnotationObject(ringROI, bandClass)
    ringAnno.setName(String.format(java.util.Locale.US, "%s · %s %.0fµm", srcName, dirShort, widthUm))
    // JTS getArea() piksel² döner → µm² için pw·ph ile çarpılır (piksel→µm² birim tuzağı).
    double bandUm2 = ring.getArea() * pw * ph
    ringAnno.measurements['Bant alanı (µm²)']    = bandUm2
    ringAnno.measurements['Bant genişliği (µm)'] = widthUm
    created << ringAnno
    rows << [name: srcName, area: bandUm2]
}

if (created.isEmpty()) {
    def msg = "Bant üretilemedi (seçili yapıların geometrisi bant için uygun değil).\n" +
              "Genişliği küçültmeyi ya da 'Dışa' yönünü deneyin."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Bant yok", msg)
    return
}

QP.addObjects(created)
QP.fireHierarchyUpdate()

// ── 5) Sonucu sun ───────────────────────────────────────────────────
double totalBandUm2 = rows.collect { it.area as double }.sum() as double

def body = new StringBuilder()
body << "PERİTÜMÖRAL BANT (kenar halkası)\n"
body << "════════════════════════════════════════════\n\n"
body << "Yön                : ${direction}\n"
body << String.format(java.util.Locale.US, "Bant genişliği     : %.0f µm%n", widthUm)
body << String.format(java.util.Locale.US, "Üretilen bant      : %,d  (atlanan: %,d)%n", created.size(), skipped)
body << String.format(java.util.Locale.US, "Toplam bant alanı  : %,.0f µm²%n", totalBandUm2)
body << "──────────────────────────────────────────────\n"
rows.each { r ->
    body << String.format(java.util.Locale.US, "  %-28s %,.0f µm²%n",
        (r.name.length() > 28 ? r.name.substring(0, 27) + "…" : r.name), r.area as double)
}
body << "\n"
body << "Bantlar '${BAND_CLASS}' sınıfıyla eklendi (kilitli değil).\n"
body << "İçlerinde hücre tespiti / piksel sınıflandırıcı çalıştırabilir,\n"
body << "'Yapıya uzaklık' yardımcısıyla birlikte kullanabilirsiniz.\n\n"
body << "Not: JTS geometri alanı piksel² döner; µm²'ye pw·ph ile ölçeklenir.\n"
body << "Bu bir GEOMETRİ üretimidir — klinik skor, eşik veya yorum DEĞİL.\n"
body << "(Teknik esin: Crichton/Exeter 2025; Bankhead 2017)\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı geometri üretir."

showResultWindow("Peritümöral bant", body.toString())
println String.format(java.util.Locale.US,
    "✓ %,d peritümöral bant üretildi (%s, %.0f µm; toplam %,.0f µm²).",
    created.size(), dirShort, widthUm, totalBandUm2)
