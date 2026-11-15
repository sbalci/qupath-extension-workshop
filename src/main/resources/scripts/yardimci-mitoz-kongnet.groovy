/**
 * Yardımcı - KongNet H&E Mitoz Tespiti (içe aktarma)
 * --------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   KongNet (KongNet_Inference_Main, MIDOG) ile H&E lamında bulunan mitoz
 *   merkezlerini içeren bir GeoJSON dosyasını QuPath'e aktarır; her mitozu
 *   "Mitosis" sınıflı bir nokta-tespiti (point detection) olarak oluşturur ve
 *   açıkça seçilmiş anotasyon(lar) için mitoz sayısı + yoğunluğu üretir:
 *   mitoz/mm² ve yalnız tam 2 mm² ROI için gözlenen mitoz sayısı.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Tanımlı bir alandaki KongNet-tespitli mitoz NOKTALARININ sayım ve
 *     YOĞUNLUĞUNU ölçer. Bu bir SAYIM/YOĞUNLUKtur — mitoz derecesi, grade
 *     eşiği veya klinik yorum DEĞİL.
 *   • KongNet doğrudan H&E üzerinde çalışır (PHH3 Mitoz ekindeki PHH3-İHK'nin aksine
 *     immün boya gerekmez). Çıktı bir derin öğrenme tahminidir; alan kayması /
 *     genelleme sınırları için bkz. Yapay Zekâ Araçlarını Değerlendirme eki. Görsel doğrulama gerekir.
 *
 * GeoJSON ÜRETİMİ (ön koşul, QuPath dışında):
 *   KongNet'i resmi depo (Jiaqi-Lv/KongNet_Inference_Main) ile çalıştırın:
 *   inference_MIDOG.py → SQLite çıktı; ardından output_to_qupath.py ile mitoz
 *   merkezlerini WSI taban (level-0) piksel koordinatlarında GeoJSON
 *   FeatureCollection (her özellik bir Point) olarak dışa aktarın. Bağlam,
 *   akış ve sınırlar: PHH3 Mitoz eki § KongNet
 *   (atolye.patoloji.dev/ekler/mitoz-phh3.html#kongnet).
 *
 * KULLANIM:
 *   1. H&E lamını açın; piksel boyutu (µm/px) kalibre olsun
 *      (Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)).
 *   2. İlgili bölge(ler)i anotasyon olarak çizin ve açıkça SEÇİN.
 *   3. Bu betiği çalıştırın; KongNet GeoJSON dosyasını seçin.
 *
 * ÇIKTI:
 *   • Her mitoz için "Mitosis" sınıflı nokta-tespiti (tespitin kendisi) + çevresinde,
 *     etiketi ("KongNet mitoz #i") taşıyan bir GÖRÜNTÜ halkası (sınıfsız, kilitli
 *     anotasyon; tespit sayımlarına girmez) — etiket mitozu örtmesin diye
 *   • Her anotasyona: "Mitoz (KongNet)" + "Mitoz yoğunluğu (KongNet, mitoz/mm2)"
 *   • Kilitli "KongNet Mitoz Özet" anotasyonu (Veri dışa aktarma modülü ile dışa aktarılır)
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Lv J ve ark. (2026) KongNet — Med Image Anal 114:104233, doi:10.1016/j.media.2026.104233
 *     (arXiv:2510.23559); MIDOG 2025 mitoz tespiti
 *     birincisi (KongNet-Det). Resmi çıkarım deposu + output_to_qupath.py:
 *     github.com/Jiaqi-Lv/KongNet_Inference_Main. Kod BSD-3; ağırlıklar
 *     CC BY-NC-SA (ticari-dışı).
 *   • Alan-tabanlı mitoz birimi (mitoz/2 mm²): bkz. PHH3 Mitoz eki ve oradaki
 *     Dessauvagie 2015 referansı.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.geom.Point2
import qupath.lib.regions.ImagePlane
import com.google.gson.JsonParser

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

// ── Parametreler ────────────────────────────────────────────────────
double whoAreaMm2       = 2.0        // WHO alan-tabanlı raporlama birimi (mitoz / 2 mm²)
String mitosisClassName = "Mitosis"  // KongNet GeoJSON sınıf adı
String summaryName      = "KongNet Mitoz Özet"
// Mitoz görünümü: NOKTA (veri) + HALKA (yalnız görüntü). QuPath ad etiketini nesnenin ÜST
// kenarının hemen üstüne çizer; noktada bu kenar mitozun kendisidir → etiket figürü örter.
// Bu yüzden nokta-tespiti ADSIZ kalır; etiket, mitozu ortada açık bırakan ayrı bir HALKA
// anotasyonuna taşınır (kapalı çizgi → doldurulmaz; sınıfsız → sınıf sayımlarına girmez;
// anotasyon → tespit sayımlarına girmez; kilitli). Bağ: halkanın metadata'sında noktanın ID'si.
double markerRingUm       = 8.0       // halka yarıçapı (µm) — tipik mitoz figürünü içine alır
int    markerRingVertices = 64
String metaSource         = "atolye.mitoz.kaynak"   // noktada: kaynak (yeniden içe aktarmada temizlik)
String metaPoint          = "atolye.mitoz.nokta"    // halkada: bağlı noktanın ID'si
String generatedName      = 'Generated by KongNet import'   // bu sihirbazın noktalarının kaynak etiketi (eski sürümde nokta adı)
// "Önceki sonuç ne olsun?" kipleri — varsayılan HİÇBİR ŞEY SİLMEZ (ev kuralı:
// sonuç yazan her sihirbaz önce sorar). Ekle: yeni özet "KongNet Mitoz Özet (2)", "(3)" … adını alır.
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'

// Bu sihirbazın özeti mi? Yalnız TAM ad ya da "ad (N)" — önek eşleşmesi YOK:
// kullanıcının "KongNet Mitoz Özet notlarım" diye adlandırdığı anotasyon süpürülmez.
// (isOwnSetName / ownSetIndex / prevOwnObjects / nextOwnSetName: yardimci-grandqc-sihirbaz.groovy'den AYNEN.)
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
// Seçili alandaki önceki KongNet noktaları: kaynak etiketi (ya da eski sürümün adı) VE merkezi alan içinde.
// Başka modellerin noktaları (başka kaynak etiketi) ve kullanıcının kendi nesneleri eşleşmez.
def prevOwnPoints = { hierarchy, roi ->
    if (hierarchy == null || roi == null) return []
    return hierarchy.getDetectionObjects().findAll { d ->
        (d.getName() == generatedName || d.getMetadata().get(metaSource) == generatedName) &&
        d.getROI() != null && roi.contains(d.getROI().getCentroidX(), d.getROI().getCentroidY())
    }
}
// Seçili alanla kesişen önceki KongNet özetleri (tam ad ya da "(N)").
def prevOwnSummaries = { hierarchy, roi ->
    if (hierarchy == null || roi == null) return []
    return prevOwnObjects(hierarchy, summaryName).findAll {
        it.getROI() != null && it.getROI().getGeometry().intersects(roi.getGeometry())
    }
}
// Yalnız verilen noktalara bağlı görüntü halkaları (bağ = noktanın ID'si). Yetim halkalar SÜPÜRÜLMEZ.
def ringsOfPoints = { hierarchy, pts ->
    def ids = new HashSet<String>()
    pts.each { ids.add(it.getID().toString()) }
    return hierarchy.getAnnotationObjects().findAll { a ->
        def link = a.getMetadata().get(metaPoint)
        link != null && ids.contains(link)
    }
}
// Önceki KongNet sonucu için kullanıcıya SOR. Dönüş: 'add' | 'replace' | null (İptal → hiçbir şey yazılmaz).
// Önceki sonuç yoksa sormadan 'add' döner.
def askExisting = { int nPts, int nSum ->
    if (nPts == 0 && nSum == 0) return MODE_ADD
    String optAdd = workshopText('script.5fcbb9588b11', 'Öncekine dokunma — yeni bir set olarak ekle (varsayılan)')
    String optRep = workshopText('script.c5e460ca07d9', 'Öncekini değiştir (bu alandaki KongNet mitoz noktaları ve özetleri silinir)')
    def pick = Dialogs.showChoiceDialog(workshopText('script.bc3c8c3a345a', 'Önceki KongNet sonucu'),
        String.format(java.util.Locale.US, workshopText('script.c83e335af033', 'Seçili alanda zaten %,d KongNet mitoz noktası ve %,d KongNet özeti var.\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni sonuç ayrı bir özet olur ("KongNet Mitoz Özet (2)" gibi) ve yeni noktalar öncekilerin yanında durur. Bu durumda seçili anotasyonların ölçümleri değiştirilmez.\n\n"Değiştir" seçilirse bu alandaki önceki KongNet noktaları, etiket halkaları ve özetleri silinir — onlarda elle yaptığınız düzeltmeler de gider. Kendi anotasyonlarınıza, başka modellerin mitoz noktalarına ve özetlerin içindeki diğer nesnelere dokunulmaz.\n\nİptal: hiçbir şey çalıştırılmaz, hiçbir şey yazılmaz.\n\nÖnceki KongNet sonucu ne olsun?'), nPts, nSum), [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.e1495ceceb6a', 'Önceki KongNet sonucu silinecek'),
            String.format(java.util.Locale.US, workshopText('script.3bcf3505b270', '%,d KongNet mitoz noktası (etiket halkalarıyla birlikte) ve %,d özet silinip yeniden içe aktarılacak. Bunlarda elle yaptığınız düzeltmeler KAYBOLUR.\n\nDevam edilsin mi?'), nPts, nSum))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}

// ── 1) Ön kontroller ve açık analiz sınırı ─────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.3587b2e65fb9', "Önce bir H&E lamı açın."))
    return
}

def imageTypeName = imageData.getImageType()?.toString() ?: ''
def normalizedImageType = imageTypeName.toUpperCase(java.util.Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
if (!normalizedImageType.contains('BRIGHTFIELD_H_E')) {
    Dialogs.showErrorMessage(workshopText('script.be9dc7d830d5', "Yanlış görüntü tipi"), "${workshopText('script.e8e8eb0908b1', 'KongNet GeoJSON\'u Brightfield (H&E) görüntüyle eşleşmelidir. Şu anki: ')}${imageTypeName}")
    return
}

def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage(workshopText('script.a5a01874aff2', "Kalibrasyon yok"), workshopText('script.81a44358db2a', "Piksel boyutu tanımlı değil; mitoz/mm² hesaplanamaz."))
    return
}

def targets = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI()?.isArea() && !isOwnSetName(it.getName(), summaryName)
}
if (targets.isEmpty()) {
    Dialogs.showErrorMessage(workshopText('script.da079a56fe7d', "Anotasyon seçili değil"), workshopText('script.cde1541e45c6', "KongNet ölçümü için analiz anotasyonlarını açıkça seçin."))
    return
}
def analysisROI = qupath.lib.roi.RoiTools.union(targets.collect { it.getROI() })
if (analysisROI == null || analysisROI.isEmpty() || !analysisROI.isArea()) {
    Dialogs.showErrorMessage(workshopText('script.b4950149348d', "Geçersiz analiz alanı"), workshopText('script.01c291c2f0c9', "Seçili anotasyonlar geçerli bir birleşik ROI oluşturmuyor."))
    return
}

// ── 2) KongNet GeoJSON dosyasını seç ────────────────────────────────
if (isHeadless) {
    println "GeoJSON dosya seçimi için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    return
}
// Önceki sonuç varsa DOSYA SEÇİMİNDEN ÖNCE sor: İptal'de hiçbir şey çalışmaz, hiçbir şey yazılmaz.
def hier = QP.getCurrentHierarchy()
String mode = askExisting(prevOwnPoints(hier, analysisROI).size() as int, prevOwnSummaries(hier, analysisROI).size() as int)
if (mode == null) {
    println "İptal edildi — önceki KongNet sonucu için seçim yapılmadı; hiçbir şey yazılmadı."
    return
}
// qupath.fx.dialogs.Dialogs'ta promptForFile YOK (0.6/0.7'de doğrulandı) → FileChoosers kullanılır.
def geojsonFile = qupath.fx.dialogs.FileChoosers.promptForFile("KongNet mitoz GeoJSON dosyasını seçin",
    qupath.fx.dialogs.FileChoosers.createExtensionFilter("KongNet GeoJSON (*.geojson, *.json)", "*.geojson", "*.json"))
if (geojsonFile == null) {
    println "İptal edildi — dosya seçilmedi."
    return
}

// ── 3) GeoJSON'u ayrıştır ──────────────────────────────────────────
def points = []
try {
    def reader = new java.io.InputStreamReader(
        new java.io.FileInputStream(geojsonFile), java.nio.charset.StandardCharsets.UTF_8)
    try {
        def root = JsonParser.parseReader(reader).getAsJsonObject()
        if (!root.has("features")) {
            Dialogs.showErrorMessage(workshopText('script.21192fd373aa', "Geçersiz GeoJSON"), workshopText('script.5e3831af61ed', "Dosyada 'features' dizisi yok (FeatureCollection bekleniyor)."))
            return
        }
        root.getAsJsonArray("features").each { featureElement ->
            def feature = featureElement.getAsJsonObject()
            if (!feature.has("geometry") || feature.get("geometry").isJsonNull()) return
            def geometry = feature.getAsJsonObject("geometry")
            if (!geometry.has("type") || geometry.get("type").getAsString() != "Point") return
            def coordinates = geometry.getAsJsonArray("coordinates")
            if (coordinates == null || coordinates.size() < 2) return
            double x = coordinates.get(0).getAsDouble()
            double y = coordinates.get(1).getAsDouble()
            if (Double.isFinite(x) && Double.isFinite(y)) points << [x, y]
        }
    } finally {
        reader.close()
    }
} catch (Throwable t) {
    Dialogs.showErrorMessage(workshopText('script.b147d1274e17', "GeoJSON okunamadı"), workshopText('script.5430313684bd', "Dosya ayrıştırılamadı:\n\n") + (t.getMessage() ?: t.getClass().getSimpleName()))
    return
}
if (points.isEmpty()) {
    Dialogs.showWarningNotification(workshopText('script.f2a69c7d3d41', "Mitoz yok"), workshopText('script.64fe10780970', "GeoJSON dosyasında geçerli Point özelliği bulunamadı."))
    return
}

def pointsInROI = points.findAll { point -> analysisROI.contains(point[0] as double, point[1] as double) }

// ── 4) Yalnız seçili birleşik ROI içindeki KongNet noktalarını ekle ─
// Önceki içe aktarım: SORULMADAN SİLİNMEZ (kip yukarıda soruldu; varsayılan: ekle).
//   ekle     → hiçbir şey silinmez; yeni özet sıradaki adı alır ("KongNet Mitoz Özet (2)" …).
//   değiştir → yalnız bu alandaki KongNet noktaları, onlara bağlı etiket halkaları ve bu sihirbazın
//              özetleri silinir; özetlerin içindeki diğer nesneler KORUNUR (keepChildren = true).
def prevPts = prevOwnPoints(hier, analysisROI)
def prevSums = prevOwnSummaries(hier, analysisROI)
def prevSumNames = prevSums.collect { it.getName() }.unique().sort()
boolean hadPrev = !(prevPts.isEmpty() && prevSums.isEmpty())
int removedPts = 0, removedRings = 0, removedSums = 0
if (mode == MODE_REPLACE && hadPrev) {
    def prevRings = ringsOfPoints(hier, prevPts)
    removedPts = prevPts.size(); removedRings = prevRings.size(); removedSums = prevSums.size()
    QP.removeObjects(prevRings + prevPts + prevSums, true)
}
String setName = nextOwnSetName(hier, summaryName)
def plane = analysisROI.getImagePlane()
def mitosisClass = QP.getPathClass(mitosisClassName)
double ringPx = markerRingUm / ((pw + ph) / 2.0)   // kalibrasyon yukarıda zorunlu
Integer ringRgb = mitosisClass?.getColor() ?: qupath.lib.common.ColorTools.packRGB(255, 40, 40)
def mitosisRing = { detection, String label ->
    double cx = detection.getROI().getCentroidX(), cy = detection.getROI().getCentroidY()
    def ringPoints = new ArrayList<Point2>()
    for (int i = 0; i <= markerRingVertices; i++) {
        double t = -Math.PI / 2 + 2 * Math.PI * (i % markerRingVertices) / markerRingVertices
        ringPoints.add(new Point2(cx + ringPx * Math.cos(t), cy + ringPx * Math.sin(t)))
    }
    def ring = PathObjects.createAnnotationObject(ROIs.createPolylineROI(ringPoints, plane))
    ring.setName(label)
    ring.setColor(ringRgb)
    ring.getMetadata().put(metaPoint, detection.getID().toString())
    ring.setLocked(true)
    ring
}
def detections = pointsInROI.collect { point ->
    def detection = PathObjects.createDetectionObject(
        ROIs.createPointsROI(point[0] as double, point[1] as double, plane), mitosisClass)
    detection.getMetadata().put(metaSource, generatedName)
    detection
}
def rings = []
detections.eachWithIndex { detection, i -> rings << mitosisRing(detection, 'KongNet mitoz #' + (i + 1)) }
if (!detections.isEmpty()) QP.addObjects(detections + rings)

// ── 5) Kaynak ROI ve birleşik sonuç ────────────────────────────────
def areaMm2 = { roi -> roi.getArea() * pw * ph / 1_000_000.0 }
// Önceki sonuç duruyorsa (ekle kipi) seçili anotasyonların ölçümleri YENİDEN YAZILMAZ: eski noktalar
// yerinde kaldığı için bu sayılar yalnız yeni dosyayı yansıtıp yanıltırdı. Sayımlar yeni özettedir.
boolean writeTargetMeasurements = !(hadPrev && mode == MODE_ADD)
if (writeTargetMeasurements) targets.each { target ->
    def roi = target.getROI()
    int count = pointsInROI.count { point -> roi.contains(point[0] as double, point[1] as double) } as int
    double area = areaMm2(roi)
    target.measurements['ROI alanı (mm2)'] = area
    target.measurements['Mitoz (KongNet)'] = count as double
    target.measurements['Mitoz yoğunluğu (KongNet, mitoz/mm2)'] = area > 0 ? count / area : Double.NaN
}

int totalMitosis = pointsInROI.size()
double totalAreaMm2 = areaMm2(analysisROI)
double overallDensity = totalAreaMm2 > 0 ? totalMitosis / totalAreaMm2 : Double.NaN
double areaToleranceMm2 = 0.01
double observedPerWhoArea = Math.abs(totalAreaMm2 - whoAreaMm2) <= areaToleranceMm2 ? totalMitosis as double : Double.NaN

def summary = PathObjects.createAnnotationObject(analysisROI)
summary.setName(setName)
summary.measurements['Kaynak ROI sayısı'] = targets.size() as double
summary.measurements['GeoJSON nokta sayısı'] = points.size() as double
summary.measurements['ROI içi mitoz (KongNet)'] = totalMitosis as double
summary.measurements['Ölçülen alan (mm2)'] = totalAreaMm2
summary.measurements['Mitoz yoğunluğu (KongNet, mitoz/mm2)'] = overallDensity
summary.measurements['Mitoz / 2 mm2 (yalnız 2.00 ± 0.01 mm2 ROI)'] = observedPerWhoArea
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

def fmt = { double value, String pattern ->
    Double.isFinite(value) ? String.format(java.util.Locale.US, pattern, value) : 'hesaplanamadı'
}
def body = new StringBuilder()
body << "KONGNET H&E MİTOZ TESPİTİ\n"
body << "═════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Seçili ROI           : %,d%n", targets.size())
body << String.format(java.util.Locale.US, "GeoJSON noktası      : %,d%n", points.size())
body << String.format(java.util.Locale.US, "ROI içi mitoz        : %,d%n", totalMitosis)
body << String.format(java.util.Locale.US, "Birleşik alan        : %.3f mm²%n", totalAreaMm2)
body << "Mitoz yoğunluğu      : ${fmt(overallDensity, '%.1f mitoz/mm²')}\n"
if (Double.isFinite(observedPerWhoArea))
    body << String.format(java.util.Locale.US, "Mitoz / %.0f mm² (gözlenen): %.0f%n", whoAreaMm2, observedPerWhoArea)
else
    body << String.format(java.util.Locale.US, "Mitoz / %.0f mm²: hesaplanmadı (alan %.3f mm²; tolerans ±%.2f)%n", whoAreaMm2, totalAreaMm2, areaToleranceMm2)
body << "\n" << String.format(java.util.Locale.US, workshopText('script.9bb2e191ad74', 'Özet adı             : %s'), setName) << "\n"
if (hadPrev && mode == MODE_ADD) {
    body << "\n" << String.format(java.util.Locale.US, workshopText('script.3b56d23e7de4', 'DİKKAT — bu alanda önceki KongNet sonucu da duruyor (özet: %s; %,d mitoz noktası). Hiçbir şey silinmedi ve seçili anotasyonların ölçümleri değiştirilmedi. Eskisini kaldırmak için o adlı özeti ve KongNet mitoz noktalarını (etiket halkalarıyla) silin ya da yeniden içe aktarırken "Öncekini değiştir"i seçin.'), prevSumNames.isEmpty() ? '-' : prevSumNames.join(', '), prevPts.size()) << "\n"
} else if (removedPts + removedRings + removedSums > 0) {
    body << "\n" << String.format(java.util.Locale.US, workshopText('script.fd1ddf2819cb', 'Önceki KongNet sonucu değiştirildi: %,d mitoz noktası, %,d etiket halkası ve %,d özet silindi (özetlerin içindeki diğer nesneler korundu).'), removedPts, removedRings, removedSums) << "\n"
}
body << "\nBirleşik özet örtüşen ROI alanlarını ve noktaları yalnız bir kez sayar.\n"
body << "Yalnız seçili birleşik ROI içindeki GeoJSON noktaları QuPath'e eklenir; her noktanın\n"
body << "çevresinde etiketli bir görüntü halkası var (mitoz ortada açık kalır; veri noktadadır).\n"
body << "KongNet çıktısı görsel olarak doğrulanmalıdır; klinik kategori veya yorum üretilmez.\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("KongNet H&E mitoz tespiti", body.toString())
println "✓ KongNet mitoz içe aktarımı tamamlandı (${totalMitosis} ROI-içi mitoz)."

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
