/**
 * Yardımcı - WSInfer Karo Özeti (sınıf alanı / %)
 * ------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * WSInfer eklentisinin (qupath-extension-wsinfer) bıraktığı KARO (tile)
 * tespitlerini MEVCUT sınıflandırmalarına göre toplar: her sınıf için
 * karo adedi, kapladığı ALAN (mm²) ve analiz edilen toplam karo alanına
 * oranı (%). İsteğe bağlı olarak aynı sınıftaki karoları tek bir
 * anotasyona BİRLEŞTİRİR (tümör alanı vb. için).
 *
 * NEDEN: WSInfer sonuçları karo tespiti olarak yazılır; klinik soruların
 * çoğu (tümör alanı, tümör oranı) için bunları sınıf bazında alana/yüzdeye
 * çevirmek gerekir. Sınıf-bazlı HÜCRE sayımı için ayrı yardımcı vardır
 * ("Sınıf bazlı hücre sayımı"); bu yardımcı KARO ALANI içindir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • VAR OLAN karo tespitlerini sınıfına göre toplar — bir ÖLÇÜMdür,
 *     klinik skor/eşik/yorum DEĞİL.
 *   • Çıkarım YAPMAZ. Önce WSInfer çalıştırın: [Extensions → WSInfer].
 *   • Sınıfsız (classification = yok) karolar "(sınıfsız)" altında toplanır.
 *
 * KULLANIM:
 *   1. WSInfer çıkarımını çalıştırmış bir slayt açın (karolar sınıflı olmalı)
 *   2. (Opsiyonel) özetlenecek bölgeyi anotasyon olarak çizip SEÇİN
 *      (seçim yoksa tüm görüntüdeki karolar özetlenir)
 *   3. [Extensions → Atölye → Yardımcılar → WSInfer karo özeti (sınıf alanı / %)]
 *      (ya da [Automate → Project scripts → bu betik])
 *
 * ÇIKTI:
 *   • Kilitli "WSInfer Alan Özet" anotasyonu: her sınıf için
 *     "WSInfer alan: <Sınıf> (karo)" + "(mm2)" + "(%)" ölçümleri
 *     (Modül 9 ile dışa aktarılır)
 *   • Sonuç penceresinde sınıf bazlı alan tablosu
 *   • (Onaylarsanız) sınıf başına birleştirilmiş kilitli anotasyon
 *
 * YÖNTEM REFERANSLARI:
 *   • Kaczmarzyk JR et al. (2024), npj Precis Oncol — WSInfer + QuPath.
 *     doi:10.1038/s41698-024-00499-9
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
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
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
String summaryName = "WSInfer Alan Özet"

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce WSInfer çıkarımı yapılmış bir slayt açın.")
    return
}

def cal = imageData.getServer().getPixelCalibration()
double pw = cal != null ? cal.getPixelWidthMicrons() : Double.NaN
double ph = cal != null ? cal.getPixelHeightMicrons() : Double.NaN
boolean hasMicrons = (cal != null && cal.hasPixelSizeMicrons() && pw > 0 && ph > 0)

// ── 2) Karo tespitlerini topla ──────────────────────────────────────
// Önce gerçek karo (tile) nesnelerini ara; bulunamazsa sınıflı tüm
// tespitlere düş (WSInfer sürüm farkları için dayanıklı).
def allDet = QP.getDetectionObjects()
def tiles = allDet.findAll { it.isTile() }
boolean usedFallback = false
if (tiles.isEmpty()) {
    tiles = allDet.findAll { it.getPathClass() != null }
    usedFallback = true
}
if (tiles.isEmpty()) {
    def msg = "WSInfer karosu bulunamadı.\n\n" +
              "Önce [Extensions → WSInfer] ile bir model çalıştırın;\n" +
              "karolar sınıflandırıldıktan sonra bu yardımcıyı yeniden çağırın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Karo yok", msg)
    return
}

// Seçili anotasyon(lar) varsa bölgeyle sınırla (özet anotasyonunu hariç tut)
def selAnns = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getName() != summaryName && it.hasROI() }
if (!selAnns.isEmpty()) {
    tiles = tiles.findAll { d ->
        def dr = d.getROI()
        dr != null && selAnns.any { it.getROI().contains(dr.getCentroidX(), dr.getCentroidY()) }
    }
}
if (tiles.isEmpty()) {
    def msg = "Seçili bölgede WSInfer karosu yok.\n\nSeçimi kaldırın (tüm görüntü) ya da karo içeren bir bölge seçin."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Karo yok", msg)
    return
}

def classOf = { d ->
    def pc = d.getPathClass()
    return (pc != null) ? pc.toString() : "(sınıfsız)"
}

Map<String, Integer> counts = new LinkedHashMap<String, Integer>()
Map<String, Double>  areas  = new LinkedHashMap<String, Double>()   // mm²
int totalTiles = 0
double totalAreaMm2 = 0.0

tiles.each { d ->
    def roi = d.getROI()
    if (roi == null) return
    String cls = classOf(d)
    double aMm2 = hasMicrons ? (roi.getArea() * pw * ph) / 1_000_000.0 : 0.0
    counts[cls] = (counts.containsKey(cls) ? counts[cls] : 0) + 1
    areas[cls]  = (areas.containsKey(cls)  ? areas[cls]  : 0.0) + aMm2
    totalTiles++
    totalAreaMm2 += aMm2
}

// ── 3) Kilitli özet anotasyonu ──────────────────────────────────────
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == summaryName }, false)
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName(summaryName)
summary.measurements['Toplam karo'] = totalTiles as double
if (hasMicrons) summary.measurements['Analiz edilen karo alanı (mm2)'] = totalAreaMm2
counts.each { cls, cnt ->
    summary.measurements["WSInfer alan: ${cls} (karo)"] = cnt as double
    if (hasMicrons) {
        summary.measurements["WSInfer alan: ${cls} (mm2)"] = areas[cls]
        summary.measurements["WSInfer alan: ${cls} (%)"] = totalAreaMm2 > 0 ? (100.0 * areas[cls] / totalAreaMm2) : 0.0
    } else {
        summary.measurements["WSInfer alan: ${cls} (%)"] = totalTiles > 0 ? (100.0 * cnt / totalTiles) : 0.0
    }
}
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

// ── 4) (Opsiyonel) sınıf başına birleştirilmiş anotasyon ────────────
int mergedClasses = 0
boolean doMerge = false
if (!isHeadless) {
    doMerge = Dialogs.showConfirmDialog(
        "Karoları birleştir?",
        "Her sınıf için karoları tek bir kilitli anotasyona birleştireyim mi?\n" +
        "(Tümör alanı gibi bölge ölçümleri için pratiktir; çok sayıda karoda biraz sürebilir.)")
}
if (doMerge) {
    Map<String, List> byClass = new LinkedHashMap<String, List>()
    tiles.each { d ->
        def roi = d.getROI(); if (roi == null) return
        String cls = classOf(d)
        byClass.computeIfAbsent(cls, { [] }).add(roi)
    }
    def newAnns = []
    byClass.each { cls, rois ->
        try {
            def merged = qupath.lib.roi.RoiTools.union(rois)
            def a = qupath.lib.objects.PathObjects.createAnnotationObject(merged, QP.getPathClass(cls))
            a.setName(cls)
            a.setLocked(true)
            newAnns << a
            mergedClasses++
        } catch (Throwable t) {
            println "Birleştirme atlandı (${cls}): ${t.getMessage()}"
        }
    }
    if (!newAnns.isEmpty()) { QP.addObjects(newAnns); QP.fireHierarchyUpdate() }
}

// ── 5) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "WSInfer KARO ÖZETİ (sınıf alanı / %)\n"
body << "═════════════════════════════════════════\n\n"
if (usedFallback) {
    body << "Not: karo (tile) nesnesi bulunamadı; sınıflı tüm tespitler kullanıldı.\n\n"
}
if (!selAnns.isEmpty()) {
    body << String.format(java.util.Locale.US, "Kapsam             : %d seçili anotasyon%n", selAnns.size())
} else {
    body << "Kapsam             : tüm görüntü\n"
}
body << String.format(java.util.Locale.US, "Toplam karo        : %,d%n", totalTiles)
if (hasMicrons) {
    body << String.format(java.util.Locale.US, "Analiz edilen alan : %.3f mm²%n", totalAreaMm2)
} else {
    body << "Analiz edilen alan : kalibrasyon (µm/px) yok → alan atlandı, % karo sayısına göre\n"
}
body << "\n"

if (hasMicrons) {
    body << "Sınıf                      Karo        mm²          %\n"
    body << "──────────────────────────────────────────────────────────────\n"
    counts.each { cls, cnt ->
        double aMm2 = areas[cls]
        double pct = totalAreaMm2 > 0 ? (100.0 * aMm2 / totalAreaMm2) : 0.0
        body << String.format(java.util.Locale.US, "%-24s %8d %10.4f %9.2f %%%n", cls, cnt, aMm2, pct)
    }
    body << "──────────────────────────────────────────────────────────────\n"
    body << String.format(java.util.Locale.US, "%-24s %8d %10.4f %9.2f %%%n", "TOPLAM", totalTiles, totalAreaMm2, 100.0)
} else {
    body << "Sınıf                      Karo          %\n"
    body << "─────────────────────────────────────────────────\n"
    counts.each { cls, cnt ->
        double pct = totalTiles > 0 ? (100.0 * cnt / totalTiles) : 0.0
        body << String.format(java.util.Locale.US, "%-24s %8d %9.2f %%%n", cls, cnt, pct)
    }
    body << "─────────────────────────────────────────────────\n"
    body << String.format(java.util.Locale.US, "%-24s %8d %9.2f %%%n", "TOPLAM", totalTiles, 100.0)
}
body << "\n"
if (mergedClasses > 0) {
    body << String.format(java.util.Locale.US, "Birleştirilen sınıf anotasyonu: %d%n", mergedClasses)
}
body << "Bu bir ALAN ÖLÇÜMÜdür — klinik skor, eşik veya yorum DEĞİL.\n"
body << "Sınıflandırma WSInfer modelinden gelir (Kaczmarzyk 2024; Bankhead 2017).\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("WSInfer karo özeti", body.toString())
println "✓ WSInfer alan özeti yazıldı ('${summaryName}')."