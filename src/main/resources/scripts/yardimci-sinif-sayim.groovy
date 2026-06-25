/**
 * Yardımcı - Sınıf Bazlı Hücre Sayımı (% dağılım)
 * ------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Seçili anotasyon(lar) içindeki tespitleri (hücreleri) MEVCUT
 * sınıflandırmalarına göre sayar: her sınıf için adet, tüm tespitlere
 * oranı (%) ve (kalibre ise) yoğunluğu (hücre/mm²). Tek belirteç değil,
 * o bölgede bulunan TÜM sınıfları tek pencerede listeler.
 *
 * NEDEN: Atölyenin tek-belirteç yardımcıları (ör. "İmmün hücre yoğunluğu")
 * tek bir pozitif sınıfı sayar. Çok-sınıflı / çoklu-işaret sonuçlarında
 * (nesne sınıflandırıcı, fenotipleme) hücre tipi DAĞILIMINI bir bakışta
 * görmek istersiniz. Bu, From Samples to Knowledge (FS2K, Session 6–8)
 * eğitimindeki hücre-tipi frekansı çıktısının ve Sara McArdle'ın
 * CellClassPct.groovy betiğinin parlak-alan İHK karşılığıdır.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • VAR OLAN tespitleri sınıfına göre SAYAR. Bu bir SAYIM/DAĞILIMdır —
 *     klinik skor, eşik veya yorum DEĞİL.
 *   • Hücre TESPİTİ veya SINIFLANDIRMA YAPMAZ. Önce bir tespit/
 *     sınıflandırma modülü çalıştırın (Modül 2/3/5/7, nesne sınıflandırıcı).
 *   • Sınıfsız (classification = yok) tespitler "(sınıfsız)" altında sayılır.
 *
 * KULLANIM:
 *   1. Sınıflandırılmış tespitler içeren bir slayt açın
 *   2. Saymak istediğiniz bölgeyi anotasyon olarak çizin ve SEÇİN
 *      (ölçülecek anotasyonlar açıkça seçilmelidir)
 *   3. [Extensions → Atölye → Yardımcılar → Sınıf bazlı hücre sayımı (% dağılım)]
 *      (ya da [Automate → Project scripts → bu betik])
 *
 * ÇIKTI:
 *   • Her anotasyona: "Sınıf sayım: <Sınıf> (adet)" + "(%)" + (kalibre ise)
 *     "(hücre/mm2)" ölçümleri
 *   • Kilitli "Sınıf Sayım Özet" anotasyonu (Modül 9 ile dışa aktarılır)
 *   • Sonuç penceresinde sınıf bazlı dağılım tablosu
 *
 * YÖNTEM REFERANSLARI:
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *   • From Samples to Knowledge 2025 (FS2K), Session 6–8 — hücre sınıflandırma
 *     ve tip frekansları: saramcardle.github.io/FS2K
 *   • CellClassPct.groovy (Sara McArdle): github.com/saramcardle/Image-Analysis-Scripts
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

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

            stage.setScene(new javafx.scene.Scene(root, 740, 560))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
String summaryName = "Sınıf Sayım Özet"

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce sınıflandırılmış tespitler içeren bir slayt açın.")
    return
}

def cal = imageData.getServer().getPixelCalibration()
double pw = cal != null ? cal.getPixelWidthMicrons() : Double.NaN
double ph = cal != null ? cal.getPixelHeightMicrons() : Double.NaN
boolean hasMicrons = (cal != null && cal.hasPixelSizeMicrons() && pw > 0 && ph > 0)

def targets = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getName() != summaryName }
if (targets.isEmpty()) {
    def msg = "Sayılacak anotasyon yok.\n\nÖnce bölgeyi anotasyon olarak çizin ve SEÇİN."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Anotasyon yok", msg)
    return
}

// ── 2) Bölge içi tespitleri sınıfa göre say ─────────────────────────
// Tüm tespitleri bir kez al; her anotasyon için centroid-içinde testiyle
// filtrele (modül 7 / yoğunluk-haritası ile aynı kalıp; iç içe anotasyonlara
// dayanıklı, getChildObjects'e bağlı değil).
def allDetections = QP.getDetectionObjects()

def classOf = { det ->
    def pc = det.getPathClass()
    return (pc != null) ? pc.toString() : "(sınıfsız)"
}
def areaMm2 = { ann ->
    def roi = ann.getROI()
    (roi != null && hasMicrons) ? (roi.getArea() * pw * ph) / 1_000_000.0 : 0.0
}

Map<String, Integer> overallCounts = new LinkedHashMap<String, Integer>()
int overallTotal = 0
double overallAreaMm2 = 0.0
int measured = 0

targets.each { ann ->
    def roi = ann.getROI()
    if (roi == null) return
    def inside = allDetections.findAll { d ->
        def dr = d.getROI()
        dr != null && roi.contains(dr.getCentroidX(), dr.getCentroidY())
    }

    Map<String, Integer> counts = new LinkedHashMap<String, Integer>()
    inside.each { d ->
        String cls = classOf(d)
        counts[cls] = (counts.containsKey(cls) ? counts[cls] : 0) + 1
    }
    int annTotal = inside.size()
    double aMm2 = areaMm2(ann)

    measured++
    overallTotal += annTotal
    overallAreaMm2 += aMm2

    counts.each { cls, cnt ->
        overallCounts[cls] = (overallCounts.containsKey(cls) ? overallCounts[cls] : 0) + cnt
        ann.measurements["Sınıf sayım: ${cls} (adet)"] = cnt as double
        ann.measurements["Sınıf sayım: ${cls} (%)"] = annTotal > 0 ? (100.0 * cnt / annTotal) : 0.0
        if (hasMicrons && aMm2 > 0) {
            ann.measurements["Sınıf sayım: ${cls} (hücre/mm2)"] = cnt / aMm2
        }
    }
}

if (overallTotal == 0) {
    def msg = "Seçili anotasyon(lar) içinde tespit bulunamadı.\n\n" +
              "Önce bir hücre tespiti / sınıflandırma adımı çalıştırın\n" +
              "(Modül 2/3/5/7 veya bir nesne sınıflandırıcı)."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Tespit yok", msg)
    return
}

// ── 3) Kilitli özet anotasyonu ──────────────────────────────────────
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == summaryName }, false)
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName(summaryName)
summary.measurements['Ölçülen anotasyon'] = measured as double
summary.measurements['Toplam hücre']      = overallTotal as double
if (hasMicrons) summary.measurements['Ölçülen alan (mm2)'] = overallAreaMm2
overallCounts.each { cls, cnt ->
    summary.measurements["Sınıf sayım: ${cls} (adet)"] = cnt as double
    summary.measurements["Sınıf sayım: ${cls} (%)"] = overallTotal > 0 ? (100.0 * cnt / overallTotal) : 0.0
    if (hasMicrons && overallAreaMm2 > 0) {
        summary.measurements["Sınıf sayım: ${cls} (hücre/mm2)"] = cnt / overallAreaMm2
    }
}
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

// ── 4) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "SINIF BAZLI HÜCRE SAYIMI (% dağılım)\n"
body << "═════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Ölçülen anotasyon  : %d%n", measured)
if (hasMicrons) {
    body << String.format(java.util.Locale.US, "Ölçülen alan       : %.3f mm²%n", overallAreaMm2)
} else {
    body << "Ölçülen alan       : kalibrasyon (µm/px) yok → yoğunluk atlandı\n"
}
body << String.format(java.util.Locale.US, "Toplam hücre       : %,d%n", overallTotal)
body << "\n"

if (hasMicrons) {
    body << "Sınıf                      Adet         %       hücre/mm²\n"
    body << "──────────────────────────────────────────────────────────────\n"
    overallCounts.each { cls, cnt ->
        double pct = overallTotal > 0 ? (100.0 * cnt / overallTotal) : 0.0
        double dens = overallAreaMm2 > 0 ? (cnt / overallAreaMm2) : 0.0
        body << String.format(java.util.Locale.US, "%-24s %8d %9.2f %% %11.1f%n", cls, cnt, pct, dens)
    }
    body << "──────────────────────────────────────────────────────────────\n"
    body << String.format(java.util.Locale.US, "%-24s %8d %9.2f %%%n", "TOPLAM", overallTotal, 100.0)
} else {
    body << "Sınıf                      Adet         %\n"
    body << "─────────────────────────────────────────────────\n"
    overallCounts.each { cls, cnt ->
        double pct = overallTotal > 0 ? (100.0 * cnt / overallTotal) : 0.0
        body << String.format(java.util.Locale.US, "%-24s %8d %9.2f %%%n", cls, cnt, pct)
    }
    body << "─────────────────────────────────────────────────\n"
    body << String.format(java.util.Locale.US, "%-24s %8d %9.2f %%%n", "TOPLAM", overallTotal, 100.0)
}
body << "\n"
body << "Bu bir SAYIM/DAĞILIMdır — klinik skor, eşik veya yorum DEĞİL.\n"
body << "Sınıflandırma kullanıcının tespit/sınıflandırma adımından gelir.\n"
body << "(FS2K Session 6–8 / CellClassPct — Sara McArdle; Bankhead 2017)\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Sınıf bazlı hücre sayımı", body.toString())
println "✓ Sınıf bazlı sayım yazıldı (her anotasyon + '${summaryName}')."
