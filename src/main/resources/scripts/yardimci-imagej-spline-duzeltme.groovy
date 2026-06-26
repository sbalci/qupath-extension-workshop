/**
 * Yardımcı - ImageJ ile Anotasyon Sınırını Yumuşat (spline)
 * -------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Seçili POLİGON / POLİLİNE anotasyonların köşeli sınırlarını ImageJ'nin
 *   PolygonRoi.fitSpline() yöntemiyle yumuşatır. Elle (fare ile) çizilmiş
 *   "dişli" sınırları temizlemek için pratiktir. Her seçili nesne için
 *   yumuşatılmış bir KOPYA oluşturur ("Smoothed" etiketi eklenir); orijinal
 *   nesneye dokunmaz — sonucu beğenmezseniz kopyayı silersiniz.
 *
 *   QuPath ⇄ ImageJ köprüsünün küçük bir gösterimidir: ROI ImageJ'ye gönderilir,
 *   spline uydurulur ve QuPath'e geri çevrilir.
 *
 * NE ÜRETİR (ve ne ÜRETMEZ):
 *   • Yalnız yumuşatılmış geometri üretir. Ölçüm, sınıflandırma veya yorum YAPMAZ.
 *   • Yalnız poligon/poliline ROI'leri işler; dikdörtgen/elips/nokta atlanır.
 *
 * KULLANIM:
 *   1. Bir veya daha çok poligon/poliline anotasyonu çizin ve SEÇİN.
 *   2. [Extensions → Atölye → İleri analiz → ImageJ ile sınır yumuşat (spline)]
 *      (ya da [Automate → Project scripts → bu betik]).
 *   3. Beğenirseniz orijinalleri silin; beğenmezseniz "Smoothed" kopyaları silin.
 *
 * KAYNAK / İLHAM:
 *   • Bankhead P, I2K 2024 "QuPath for Fiji Fans" — "Smooth with splines"
 *     (github.com/qupath/i2k2024, workshops/fiji-fans). Bu betik onun uyarlamasıdır.
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import ij.gui.PolygonRoi
import qupath.imagej.tools.IJTools
import qupath.fx.dialogs.Dialogs
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjectTools
import qupath.lib.roi.PolygonROI
import qupath.lib.roi.PolylineROI
import qupath.lib.roi.interfaces.ROI
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── Sonuç penceresi (atölye ortak yardımcısı; ekstansiyon/Project scripts'ten çalışır) ──
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

            stage.setScene(new javafx.scene.Scene(root, 700, 460))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Spline uydur: ROI → ImageJ → fitSpline → ROI ─────────────────────────────
def smoothPolylineROI = { ROI polyline ->
    def roi = IJTools.convertToIJRoi(polyline, null, 1.0)
    if (roi instanceof PolygonRoi) {
        roi.fitSpline()
        return IJTools.convertToROI(roi, null, 1.0, polyline.getImagePlane())
    }
    return polyline   // beklenmedik tür — olduğu gibi geri ver
}

// ── 1) Uygun seçili nesneleri topla (poligon / poliline anotasyonlar) ───────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    def msg = "Önce bir görüntü açın."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Görüntü açık değil", msg)
    return
}

def candidates = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI() != null &&
        (it.getROI() instanceof PolygonROI || it.getROI() instanceof PolylineROI)
}
if (candidates.isEmpty()) {
    def msg = "Önce poligon ya da poliline anotasyon(lar) çizip SEÇİN.\n" +
              "(Dikdörtgen / elips / nokta ROI'leri yumuşatılamaz.)"
    if (isHeadless) println msg else Dialogs.showWarningNotification("Uygun seçim yok", msg)
    return
}

// ── 2) Yumuşatılmış kopyaları oluştur ("Smoothed" etiketi; orijinal korunur) ─
def smoothed = []
int skipped = 0
candidates.each { PathObject src ->
    def newROI = smoothPolylineROI(src.getROI())
    if (newROI == null || newROI.is(src.getROI())) { skipped++; return }
    def newObject = PathObjectTools.createLike(src, newROI)
    newObject.classifications += ['Smoothed']
    smoothed << newObject
}

if (smoothed.isEmpty()) {
    def msg = "Seçili nesneler ImageJ poligonuna çevrilemedi; hiçbiri yumuşatılmadı."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Yumuşatma yapılamadı", msg)
    return
}

QP.addObjects(smoothed)
QP.fireHierarchyUpdate()

// ── 3) Özet ─────────────────────────────────────────────────────────────────
def body = new StringBuilder()
body << "IMAGEJ — SINIR YUMUŞATMA (spline)\n"
body << "═══════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Seçili uygun nesne : %d%n", candidates.size())
body << String.format(java.util.Locale.US, "Yumuşatılan kopya  : %d%n", smoothed.size())
if (skipped > 0)
    body << String.format(java.util.Locale.US, "Atlanan            : %d (poligona çevrilemedi)%n", skipped)
body << "\n"
body << "Her kopyaya 'Smoothed' etiketi eklendi; orijinaller korundu.\n"
body << "Beğenirseniz orijinalleri silin; beğenmezseniz 'Smoothed' kopyaları silin.\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("ImageJ sınır yumuşatma", body.toString())
println "✓ ${smoothed.size()} yumuşatılmış kopya eklendi ('Smoothed' etiketi)."
