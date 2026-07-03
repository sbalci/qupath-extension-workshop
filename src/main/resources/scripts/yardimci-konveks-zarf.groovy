/**
 * Yardımcı - Dış Bükey Zarf (convex hull)
 * ----------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Seçili nesnelerin ya da belirli bir SINIFA ait tüm nesnelerin (hücre tespiti
 *   veya anotasyon) DIŞ BÜKEY ZARFINI (convex hull) hesaplar ve "Konveks Zarf"
 *   sınıflı bir anotasyon olarak üretir. Zarf, bir nesne kümesini saran en küçük
 *   dışbükey poligondur — "bu hücre topluluğunun uzamsal yayılımı nedir?" sorusunun
 *   geometrik karşılığıdır: pozitif-hücre kümesi yayılımı, tümör yayılım alanı,
 *   immün infiltrat sınırı gibi.
 *
 * NE ÜRETİR (ve ne ÜRETMEZ):
 *   • Yalnız GEOMETRİ üretir (zarf anotasyonu + zarf alanı µm²). Tespit, skor,
 *     eşik, evre veya klinik yorum ÜRETMEZ.
 *   • Girdiyi (nesneler/sınıf) sizin sağlamanız gerekir — betik tespit yapmaz.
 *
 * QuPath KARŞILIĞI (GUI):
 *   Yerleşik tek menü komutu yoktur; zarf, `qupath.lib.roi.ConvexHull` /
 *   JTS `Geometry.convexHull()` API'siyle hesaplanır.
 *
 * KULLANIM:
 *   1. Kalibre (µm/px) bir slayt açın; ilgilenilen nesneleri (ör. pozitif hücreler)
 *      tespit edin/sınıflayın
 *   2. Zarfını istediğiniz nesneleri SEÇİN — ya da bir sınıf üzerinden çalıştırın
 *   3. [Extensions → Atölye → Yardımcılar → Uzamsal analiz → Dış bükey zarf (convex hull)]
 *      (ya da [Automate → Project scripts → bu betik])
 *
 * ÇIKTI:
 *   • "Konveks Zarf" sınıflı bir anotasyon ("Zarf alanı (µm²)" + "Kapsanan nesne (N)")
 *   • Sonuç penceresinde özet (nesne sayısı, zarf alanı)
 *   • Her çalıştırma aynı kaynağın önceki zarfını YENİLER
 *
 * YÖNTEM / KAYNAK:
 *   • JTS `Geometry.convexHull()` (nesne geometrilerinin birleşiminden). Dikkat:
 *     JTS getArea() piksel² döner; µm² için pw·ph ile çarpılır.
 *   • Pete Bankhead / image.sc #76833 — "make-convex-hull" forum betiği (kamuya açık örnek).
 *   • MontpellierRessourcesImagerie BioCampus 2026 (qupath-bcm-workshop), make-convex-hull.groovy —
 *     yöntemin atölyedeki kaynağı (teknikten ilham; depo lisanssız, koddan aktarım yok).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı geometri üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/konveks-zarf')

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

            stage.setScene(new javafx.scene.Scene(root, 640, 420))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Sabitler ────────────────────────────────────────────────────────
String HULL_CLASS = "Konveks Zarf"
String SEL_OPT    = "▸ Seçili nesneler"

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce nesneleri (hücre tespiti/anotasyon) olan bir slayt açın.")
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
boolean hasMicrons = (pw > 0 && ph > 0)

// Sınıfı olan nesnelerin (tespit + anotasyon, zarf hariç) sınıf adları
def classNames = (QP.getDetectionObjects() + QP.getAnnotationObjects())
    .findAll { it.getROI() != null && it.getPathClass() != null && it.getPathClass().toString() != HULL_CLASS }
    .collect { it.getPathClass().toString() }
    .unique()
    .sort()

// ── 2) Kaynağı belirle ──────────────────────────────────────────────
String source
if (isHeadless) {
    source = prefs.get('source', SEL_OPT)
    if (source != SEL_OPT && !classNames.contains(source)) {
        println "Headless: 'source' tercihi ayarlı değil ya da bu görüntüde yok.\n" +
                "Mevcut sınıflar: ${classNames.join(', ') ?: '(yok)'}"
        return
    }
} else {
    def options = [SEL_OPT] + classNames
    def def0 = options.contains(prefs.get('source', '')) ? prefs.get('source', '') : SEL_OPT
    source = Dialogs.showChoiceDialog("Dış bükey zarf",
        "Zarfı hangi nesnelerden oluşturayım?", options, def0)
    if (source == null) { println "İptal edildi."; return }
}
prefs.put('source', source)
try { prefs.flush() } catch (Throwable ig) {}

// ── 3) Girdi nesnelerini topla ──────────────────────────────────────
def objs
String srcLabel
if (source == SEL_OPT) {
    objs = QP.getSelectedObjects().findAll { it.getROI() != null && it.getPathClass()?.toString() != HULL_CLASS }
    srcLabel = "seçili"
} else {
    objs = (QP.getDetectionObjects() + QP.getAnnotationObjects()).findAll { it.getROI() != null && it.getPathClass()?.toString() == source }
    srcLabel = source
}
if (objs.size() < 3) {
    def msg = "Dış bükey zarf için en az 3 nesne gerekir (bulunan: ${objs.size()}).\n" +
              (source == SEL_OPT ? "Önce zarfını istediğiniz nesneleri SEÇİN." : "Bu sınıfta yeterli nesne yok.")
    if (isHeadless) println msg else Dialogs.showWarningNotification("Yetersiz nesne", msg)
    return
}

// ── 4) Zarfı hesapla (nesne geometrilerinin birleşimi → convexHull) ─
def plane = objs[0].getROI().getImagePlane()
def geoms = objs.collect { it.getROI().getGeometry() }.findAll { it != null && !it.isEmpty() }
if (geoms.isEmpty()) {
    def msg = "Nesnelerden geçerli geometri elde edilemedi."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Geometri yok", msg)
    return
}
def union = (geoms.size() == 1) ? geoms[0] : org.locationtech.jts.operation.union.UnaryUnionOp.union(geoms)
def hull = union.convexHull()
def hullRoi = qupath.lib.roi.GeometryTools.geometryToROI(hull, plane)
if (hullRoi == null || hullRoi.isEmpty() || !hullRoi.isArea()) {
    def msg = "Zarf bir alan poligonu oluşturmadı (nesneler eşdoğrusal olabilir)."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Zarf yok", msg)
    return
}

// JTS getArea() piksel² döner → µm² için pw·ph ile çarpılır (piksel→µm² birim tuzağı).
double hullAreaUm2 = hasMicrons ? (hull.getArea() * pw * ph) : Double.NaN

// ── 5) Önceki zarfı yenile + yeni zarfı ekle ────────────────────────
String hullName = "${HULL_CLASS} · ${srcLabel}"
def stale = QP.getAnnotationObjects().findAll { it.getPathClass()?.toString() == HULL_CLASS && (it.getName() ?: '') == hullName }
if (!stale.isEmpty()) QP.removeObjects(stale, false)

def hullClass = QP.getPathClass(HULL_CLASS)
def hullAnno = qupath.lib.objects.PathObjects.createAnnotationObject(hullRoi, hullClass)
hullAnno.setName(hullName)
hullAnno.measurements['Kapsanan nesne (N)'] = objs.size() as double
if (!Double.isNaN(hullAreaUm2)) hullAnno.measurements['Zarf alanı (µm²)'] = hullAreaUm2
QP.addObjects([hullAnno])
QP.fireHierarchyUpdate()

// ── 6) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "DIŞ BÜKEY ZARF (convex hull)\n"
body << "════════════════════════════════════════════\n\n"
body << "Kaynak            : ${srcLabel}\n"
body << String.format(java.util.Locale.US, "Kapsanan nesne    : %,d%n", objs.size())
if (!Double.isNaN(hullAreaUm2))
    body << String.format(java.util.Locale.US, "Zarf alanı        : %,.0f µm²%n", hullAreaUm2)
else
    body << "Zarf alanı        : (kalibrasyon yok — µm² hesaplanamadı)\n"
body << "\n"
body << "'${HULL_CLASS}' sınıflı bir anotasyon eklendi. İçinde hücre tespiti/piksel\n"
body << "sınıflandırıcı çalıştırabilir, uzamsal yardımcılarla (Ek M) birlikte kullanabilirsiniz.\n\n"
body << "Not: JTS geometri alanı piksel² döner; µm²'ye pw·ph ile ölçeklenir.\n"
body << "Bu bir GEOMETRİ üretimidir — klinik skor, eşik veya yorum DEĞİL.\n"
body << "(Teknik esin: Bankhead/image.sc #76833; MRI BioCampus 2026)\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı geometri üretir."

showResultWindow("Dış bükey zarf", body.toString())
println String.format(java.util.Locale.US,
    "✓ Dış bükey zarf oluşturuldu — kaynak '%s', %,d nesne%s.",
    srcLabel, objs.size(),
    (Double.isNaN(hullAreaUm2) ? "" : String.format(java.util.Locale.US, ", alan %,.0f µm²", hullAreaUm2)))
