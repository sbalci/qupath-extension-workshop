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
 *   1. Kalibre (µm/px) bir lam açın; ilgilenilen nesneleri (ör. pozitif hücreler)
 *      tespit edin/sınıflayın
 *   2. Zarfını istediğiniz nesneleri SEÇİN — ya da bir sınıf üzerinden çalıştırın
 *   3. [Extensions → Atölye → Yardımcılar → Uzamsal analiz → Dış bükey zarf (convex hull)]
 *      (ya da [Automate → Project scripts → bu betik])
 *
 * ÇIKTI:
 *   • "Konveks Zarf" sınıflı bir anotasyon ("Zarf alanı (µm²)" + "Kapsanan nesne (N)")
 *   • Sonuç penceresinde özet (nesne sayısı, zarf alanı)
 *   • Aynı kaynağın önceki zarfı varsa SORAR: "Ekle" (varsayılan) hiçbir şey silmez, yeni zarf
 *     "Konveks Zarf · <kaynak> (2)" gibi adlanır; "Değiştir" yalnız bu yardımcının zarfını siler.
 *     Başsız kip: -a mevcut=ekle | -a mevcut=degistir (belirtilmezse durur, hiçbir şey yazmaz).
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

// ── Önceki sonuç: SORULMADAN silinmez (ev kuralı) ────────────────────
// Önceki sonuç = bu yardımcının aynı kaynak için ürettiği zarf: sınıf "Konveks Zarf" + TAM ad
// ("Konveks Zarf · <kaynak>") ya da "ad (N)" (N >= 2) — önek eşleşmesi YOK; kullanıcının adını
// değiştirdiği ya da sınıfsız aynı adı verdiği anotasyon süpürülmez.
// Varsayılan kip HİÇBİR ŞEY SİLMEZ: Ekle → yeni zarf "Konveks Zarf · <kaynak> (2)", "(3)" … adını alır.
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
// Bu yardımcının zarfı = ad eşleşmesi + "Konveks Zarf" sınıfı (sınıfsız aynı adlı anotasyon sayılmaz).
def prevHullObjects = { hierarchy, String base ->
    return prevOwnObjects(hierarchy, base).findAll { it.getPathClass()?.toString() == HULL_CLASS }
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

// Önceki zarf için kullanıcıya SOR (zarf hesaplanmadan ÖNCE çağrılır).
//   Dönüş: 'add' | 'replace' | null (İptal → hiçbir şey hesaplanmaz, hiçbir şey yazılmaz).
def askExisting = { hierarchy, String base ->
    def prev = prevHullObjects(hierarchy, base)
    if (prev.isEmpty()) return MODE_ADD
    String optAdd = workshopText('script.e91e1fb24ec7', 'Öncekine dokunma — yeni bir zarf olarak ekle (varsayılan)')
    String optRep = workshopText('script.86a92c953153', 'Öncekini değiştir (bu yardımcının zarfı silinir)')
    def pick = Dialogs.showChoiceDialog(workshopText('script.ed8dc2180552', 'Önceki dış bükey zarf'),
        String.format(java.util.Locale.US, workshopText('script.a6b16dda5235', 'Bu lamda bu yardımcının "%s" adıyla ürettiği %,d zarf anotasyonu zaten var.\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni zarf ayrı bir anotasyon olur ("%s (2)" gibi). Lamda iki zarf birlikte durur.\n\n"Değiştir" seçilirse önceki zarf anotasyonu silinir; içindeki nesneler korunur. Kendi anotasyonlarınıza ve adını değiştirdiklerinize dokunulmaz.\n\nİptal: hiçbir şey hesaplanmaz, hiçbir şey yazılmaz.\n\nÖnceki zarf ne olsun?'), base, prev.size(), base),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.a0f7fb3f1d46', 'Önceki zarf silinecek'),
            String.format(java.util.Locale.US, workshopText('script.f961df8c3556', '%,d zarf anotasyonu ("%s") silinecek; bunda elle yaptığınız düzeltmeler kaybolur. İçindeki nesneler korunur.\n\nDevam edilsin mi?'), prev.size(), base))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.f5ab1abd2b8c', "Önce nesneleri (hücre tespiti/anotasyon) olan bir lam açın."))
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
    source = Dialogs.showChoiceDialog(workshopText('script.f012b5aa73f5', "Dış bükey zarf"),
        workshopText('script.e0ff07c9d734', "Zarfı hangi nesnelerden oluşturayım?"), options, def0)
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
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.1cdff0f34508', "Yetersiz nesne"), msg)
    return
}

// ── 3b) Önceki zarf var mı? HESAPLAMADAN ÖNCE sor ─────────────────────
String hullName = "${HULL_CLASS} · ${srcLabel}"
def imgHierarchy = imageData.getHierarchy()
def prevOwn = prevHullObjects(imgHierarchy, hullName)
def prevNames = prevOwn.collect { it.getName() }.unique().sort()
String writeMode = MODE_ADD
if (!prevOwn.isEmpty()) {
    if (isHeadless) {
        if (mevcutArg == null) {
            println String.format(java.util.Locale.US, workshopText('script.3eb0835f5507', 'Bu lamda bu yardımcının aynı kaynak için ürettiği %,d zarf anotasyonu ("%s") zaten var. Hiçbir şey yazılmadı. Yeni bir kopya eklemek için -a mevcut=ekle, önceki sonucu değiştirmek için -a mevcut=degistir ile yeniden çalıştırın.'), prevOwn.size(), hullName); return
        }
        writeMode = (mevcutArg == 'degistir') ? MODE_REPLACE : MODE_ADD
    } else {
        writeMode = askExisting(imgHierarchy, hullName)
        if (writeMode == null) { println "İptal edildi."; return }
    }
}

// ── 4) Zarfı hesapla (nesne geometrilerinin birleşimi → convexHull) ─
def plane = objs[0].getROI().getImagePlane()
def geoms = objs.collect { it.getROI().getGeometry() }.findAll { it != null && !it.isEmpty() }
if (geoms.isEmpty()) {
    def msg = "Nesnelerden geçerli geometri elde edilemedi."
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.53cb8e0abe69', "Geometri yok"), msg)
    return
}
def union = (geoms.size() == 1) ? geoms[0] : org.locationtech.jts.operation.union.UnaryUnionOp.union(geoms)
def hull = union.convexHull()
def hullRoi = qupath.lib.roi.GeometryTools.geometryToROI(hull, plane)
if (hullRoi == null || hullRoi.isEmpty() || !hullRoi.isArea()) {
    def msg = "Zarf bir alan poligonu oluşturmadı (nesneler eşdoğrusal olabilir)."
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.131e5a40a612', "Zarf yok"), msg)
    return
}

// JTS getArea() piksel² döner → µm² için pw·ph ile çarpılır (piksel→µm² birim tuzağı).
double hullAreaUm2 = hasMicrons ? (hull.getArea() * pw * ph) : Double.NaN

// ── 5) Önceki zarf: kip çağırandan gelir + yeni zarfı ekle ──────────
// Ekle → hiçbir şey silinmez, yeni zarf sıradaki adı alır; Değiştir → yalnız bu yardımcının aynı
// kaynak için ürettiği zarflar (tam ad ya da "(N)" + sınıf) silinir, içlerindeki nesneler korunur.
int removedPrev = 0
String thisHullName = hullName
if (writeMode == MODE_REPLACE) {
    def prevNow = prevHullObjects(imgHierarchy, hullName)
    removedPrev = prevNow.size()
    if (removedPrev > 0) QP.removeObjects(prevNow, true)
} else if (!prevOwn.isEmpty()) {
    thisHullName = nextOwnSetName(imgHierarchy, hullName)
}

def hullClass = QP.getPathClass(HULL_CLASS)
def hullAnno = qupath.lib.objects.PathObjects.createAnnotationObject(hullRoi, hullClass)
hullAnno.setName(thisHullName)
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
body << String.format(java.util.Locale.US, workshopText('script.eacfcadb7333', 'Zarf adı          : %s'), thisHullName) << "\n"
if (writeMode == MODE_ADD && !prevNames.isEmpty()) {
    body << "\n" << String.format(java.util.Locale.US, workshopText('script.2e6a78aa3dfd', 'DİKKAT — bu lamda önceki zarf da duruyor: %s\nHiçbir şey silinmedi. Eskisini kaldırmak için o addaki anotasyonu seçip silin ya da yardımcıyı yeniden çalıştırırken "Öncekini değiştir"i seçin.'), prevNames.join(', ')) << "\n"
} else if (removedPrev > 0) {
    body << "\n" << String.format(java.util.Locale.US, workshopText('script.6c1a190e218d', 'Önceki zarf değiştirildi: %,d anotasyon silindi (içindeki nesneler korundu).'), removedPrev) << "\n"
}
body << "\n"
body << "'${HULL_CLASS}' sınıflı bir anotasyon eklendi. İçinde hücre tespiti/piksel\n"
body << "sınıflandırıcı çalıştırabilir, uzamsal yardımcılarla (Uzamsal Komşuluk Analizi eki) birlikte kullanabilirsiniz.\n\n"
body << "Not: JTS geometri alanı piksel² döner; µm²'ye pw·ph ile ölçeklenir.\n"
body << "Bu bir GEOMETRİ üretimidir — klinik skor, eşik veya yorum DEĞİL.\n"
body << "(Teknik esin: Bankhead/image.sc #76833; MRI BioCampus 2026)\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı geometri üretir."

showResultWindow("Dış bükey zarf", body.toString())
println String.format(java.util.Locale.US,
    "✓ Dış bükey zarf oluşturuldu — kaynak '%s', %,d nesne%s.",
    srcLabel, objs.size(),
    (Double.isNaN(hullAreaUm2) ? "" : String.format(java.util.Locale.US, ", alan %,.0f µm²", hullAreaUm2)))

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
