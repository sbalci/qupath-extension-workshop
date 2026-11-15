/**
 * Yardımcı - Yoğunluk Haritası (ızgara)
 * --------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Hücre tespitlerini kareli bir ızgaraya böler ve her karede hücre
 * **yoğunluğunu (hücre/mm²)** ölçer. Karelere yoğunluğa göre renk verince
 * doku boyunca hücresellik HARİTASI ortaya çıkar.
 *
 * QuPath KARŞILIĞI (GUI):
 *   [Analyze → Density maps] yerleşik komutu yumuşak (Gaussian) bir ısı
 *   haritası üretir ve görsel olarak daha şıktır. Bu betik aynı fikrin
 *   eklenti gerektirmeyen, SAYISAL (kare başına hücre/mm²) ve Veri Dışa Aktarma
 *   modülü ile dışa aktarılabilen karşılığıdır.
 *
 * GİRDİ (ÖN KOŞUL):
 *   • Lamda hücre tespitleri olmalı (Hücre Tespiti, Nükleer Boya, Sitoplazmik Boya
 *     veya Tümör İçi Ki-67 modüllerinden biriyle üretilmiş). Betik tespit YAPMAZ.
 *
 * ÇIKTI:
 *   • Analiz sınırını kesen her kare (sıfır hücreli kareler dahil): kilitli "Yoğunluk grid r,c" +
 *     "Hücre yoğunluğu (hücre/mm2)", "Hücre sayısı"
 *   • Kilitli "Yoğunluk Özet": birleşik ROI yoğunluğu ve yalnız tam karolar
 *     arasındaki maksimum yoğunluk
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs
import qupath.lib.roi.RoiTools

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── Önceki sonuç: SORULMADAN silinmez (ev kuralı) ───────────────────
// Bu betiğin çıktısı: "Yoğunluk grid r,c" kareleri + "Yoğunluk Özet". Yalnız TAM ad ya da
// "ad (N)" (N >= 2) eşleşir — önek eşleşmesi YOK; kullanıcının verdiği başka adlara dokunulmaz.
def GRID_BASE    = 'Yoğunluk grid'
def SUMMARY_BASE = 'Yoğunluk Özet'
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
// Izgara karesi adı: "<base> r,c" (ilk set) ya da "<base> (N) r,c" (N >= 2).
def isOwnTileName = { String name, String base ->
    if (name == null) return false
    return name ==~ (java.util.regex.Pattern.quote(base) + ' (\\((?:[2-9]|[1-9][0-9]+)\\) )?[0-9]+,[0-9]+')
}
def tileSetIndex = { String name, String base ->
    def m = (name =~ ('^' + java.util.regex.Pattern.quote(base) + ' \\(([0-9]+)\\) [0-9]+,[0-9]+$'))
    return m.find() ? (m.group(1) as int) : 1
}
def prevOwnGridObjects = { hierarchy, String gridBase, String summaryBase ->
    if (hierarchy == null) return []
    return hierarchy.getAnnotationObjects().findAll { isOwnTileName(it.getName(), gridBase) || isOwnSetName(it.getName(), summaryBase) }
}
def gridSetIndexes = { hierarchy, String gridBase, String summaryBase ->
    return prevOwnGridObjects(hierarchy, gridBase, summaryBase).collect {
        isOwnSetName(it.getName(), summaryBase) ? ownSetIndex(it.getName(), summaryBase) : tileSetIndex(it.getName(), gridBase)
    }.unique().sort()
}
def nextGridSetIndex = { hierarchy, String gridBase, String summaryBase ->
    def used = gridSetIndexes(hierarchy, gridBase, summaryBase)
    return used.isEmpty() ? 1 : ((used.max() as int) + 1)
}
def setSuffix = { int index -> index > 1 ? (' (' + index + ')') : '' }

// Önceki sonuç için kullanıcıya SOR (hesaptan ÖNCE). Dönüş: 'add' | 'replace' | null (İptal → hiçbir şey yazılmaz).
def askExisting = { hierarchy ->
    def prev = prevOwnGridObjects(hierarchy, GRID_BASE, SUMMARY_BASE)
    if (prev.isEmpty()) return MODE_ADD
    String optAdd = workshopText('script.5fcbb9588b11', 'Öncekine dokunma — yeni bir set olarak ekle (varsayılan)')
    String optRep = workshopText('script.a2d620f13645', 'Öncekini değiştir (bu betiğin ızgara kareleri ve özet anotasyonu silinir)')
    def pick = Dialogs.showChoiceDialog(workshopText('script.6c29143b08ce', 'Önceki yoğunluk haritası sonucu'),
        String.format(java.util.Locale.US, workshopText('script.e087287a1920', 'Bu lamda bu betiğin ürettiği %,d anotasyon zaten var (ızgara kareleri ve özet).\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni sonuç ayrı bir set olur ("Yoğunluk grid (2) 0,0" ve "Yoğunluk Özet (2)" gibi). Lamda iki set birlikte durur; dışa aktarım ve özetler ikisini de görür.\n\n"Değiştir" seçilirse önceki ızgara kareleri ve özet anotasyonu silinir. Kendi anotasyonlarınıza dokunulmaz; silinen karelerin içindeki nesneler korunur.\n\nİptal: hiçbir şey hesaplanmaz, hiçbir şey yazılmaz.\n\nÖnceki sonuç ne olsun?'), prev.size()),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.7ac01d7e1a49', 'Önceki yoğunluk haritası sonucu silinecek'),
            String.format(java.util.Locale.US, workshopText('script.89d4eec58021', '%,d anotasyon silinecek: bu betiğin önceki ızgara kareleri ve özet anotasyonu (adı "Yoğunluk grid …" ya da "Yoğunluk Özet" olanlar). Bu adlardan birini kendiniz verdiyseniz o anotasyon da silinir; karelerde elle yaptığınız değişiklikler kaybolur. Karelerin içindeki nesneler korunur.\n\nDevam edilsin mi?'), prev.size()))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}

// Başsız kip: önceki sonuç varsa `mevcut=ekle` (kopya ekle) ya da `mevcut=degistir` olmadan hiçbir şey yazılmaz.
def scriptArgs = []
try { if (binding.hasVariable('args')) { def a = binding.getVariable('args'); if (a != null) scriptArgs = (a as List) } } catch (Throwable ignore) {}
def argMap = [:]
scriptArgs.each { a -> def sa = a?.toString() ?: ''; int ia = sa.indexOf('='); if (ia > 0) argMap[sa.substring(0, ia).trim()] = sa.substring(ia + 1).trim() }
String mevcutArg = argMap.mevcut == null ? null : argMap.mevcut.toLowerCase(java.util.Locale.ROOT)
if (isHeadless && mevcutArg != null && !['ekle', 'degistir'].contains(mevcutArg)) {
    println String.format(java.util.Locale.US, workshopText('script.37ab92155fc9', 'HATA: mevcut yalnızca \'ekle\' ya da \'degistir\' olabilir (verilen: %s). Hiçbir şey yazılmadı.'), argMap.mevcut)
    return
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

            stage.setScene(new javafx.scene.Scene(root, 700, 500))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
double tileSizeMicrons = 250.0
int maxTiles = 600

// ── 1) Açık görüntü, kalibrasyon ve açıkça seçilmiş analiz sınırı ──
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', 'Görüntü açık değil'), workshopText('script.2b498b3b5a90', 'Önce hücre tespiti yapılmış bir lam açın.'))
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage(workshopText('script.a5a01874aff2', 'Kalibrasyon yok'), workshopText('script.ffc031074489', 'Piksel boyutu tanımlı değil; hücre/mm² hesaplanamaz.'))
    return
}

def targets = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI()?.isArea() &&
        !isOwnTileName(it.getName(), GRID_BASE) && !isOwnSetName(it.getName(), SUMMARY_BASE)
}
if (targets.isEmpty()) {
    Dialogs.showErrorMessage(workshopText('script.acef16918bfc', 'Analiz alanı seçili değil'), workshopText('script.17f12d8e0b7d', 'Yoğunluk haritası için ölçülecek anotasyonları açıkça seçin.'))
    return
}
def analysisROI = RoiTools.union(targets.collect { it.getROI() })
if (analysisROI == null || analysisROI.isEmpty() || !analysisROI.isArea()) {
    Dialogs.showErrorMessage(workshopText('script.b4950149348d', 'Geçersiz analiz alanı'), workshopText('script.fb9e7cf45f98', 'Seçili anotasyonlar geçerli bir analiz alanı oluşturmuyor.'))
    return
}

def cells = QP.getDetectionObjects().findAll { detection ->
    def roi = detection.getROI()
    detection.isCell() && roi != null && analysisROI.contains(roi.getCentroidX(), roi.getCentroidY())
}
int n = cells.size()

// Önceki sonuç varsa hesaptan ÖNCE sor; İptal'de hiçbir şey yazılmaz.
def hierarchy = imageData.getHierarchy()
String writeMode = MODE_ADD
def prevAtStart = prevOwnGridObjects(hierarchy, GRID_BASE, SUMMARY_BASE)
if (!prevAtStart.isEmpty()) {
    if (isHeadless) {
        if (mevcutArg == null) {
            println String.format(java.util.Locale.US, workshopText('script.54ea69e92480', 'Bu lamda bu betiğin önceki sonucu zaten var (%,d anotasyon). Hiçbir şey yazılmadı. Yeni bir set eklemek için mevcut=ekle, önceki sonucu değiştirmek için mevcut=degistir ile yeniden çalıştırın.'), prevAtStart.size())
            return
        }
        writeMode = (mevcutArg == 'degistir') ? MODE_REPLACE : MODE_ADD
    } else {
        writeMode = askExisting(hierarchy)
        if (writeMode == null) return
    }
}
// Replace: yeni set ilk setin adlarını alır; Add: sıradaki set numarası.
int setIndex = (writeMode == MODE_REPLACE) ? 1 : nextGridSetIndex(hierarchy, GRID_BASE, SUMMARY_BASE)
def keptSetNames = (writeMode == MODE_REPLACE) ? [] : gridSetIndexes(hierarchy, GRID_BASE, SUMMARY_BASE).collect { SUMMARY_BASE + setSuffix(it as int) }

// ── 2) Fiziksel kare boyutuyla, analiz ROI sınırlarından ızgara ─────
double tilePxX = tileSizeMicrons / pw
double tilePxY = tileSizeMicrons / ph
double minX = analysisROI.getBoundsX()
double minY = analysisROI.getBoundsY()
double width = analysisROI.getBoundsWidth()
double height = analysisROI.getBoundsHeight()
int nCols = Math.max(1, (int)Math.ceil(width / tilePxX))
int nRows = Math.max(1, (int)Math.ceil(height / tilePxY))
while ((long)nCols * nRows > maxTiles) {
    tilePxX *= 1.4
    tilePxY *= 1.4
    nCols = Math.max(1, (int)Math.ceil(width / tilePxX))
    nRows = Math.max(1, (int)Math.ceil(height / tilePxY))
}

int[] tileCount = new int[nCols * nRows]
cells.each { cell ->
    def roi = cell.getROI()
    int col = Math.min(nCols - 1, Math.max(0, (int)((roi.getCentroidX() - minX) / tilePxX)))
    int row = Math.min(nRows - 1, Math.max(0, (int)((roi.getCentroidY() - minY) / tilePxY)))
    tileCount[row * nCols + col]++
}

// ── 3) Eski çıktıyı temizle; ROI ile kesişen sıfır/dolu tüm kareleri yaz ──
// (Önceki sonuç burada SİLİNMEZ: yalnız Replace kolu, yeni nesneler hazır olunca siler.)
def plane = analysisROI.getImagePlane()
def newTiles = []
double maxFullTileDensity = Double.NaN
double analyzedAreaMm2 = 0.0
int zeroTiles = 0
double fullTileAreaPx = tilePxX * tilePxY
for (int row = 0; row < nRows; row++) {
    for (int col = 0; col < nCols; col++) {
        double x = minX + col * tilePxX
        double y = minY + row * tilePxY
        def rectangle = ROIs.createRectangleROI(x, y, tilePxX, tilePxY, plane)
        def clipped = RoiTools.intersection([rectangle, analysisROI])
        if (clipped == null || clipped.isEmpty() || !clipped.isArea()) continue

        int count = tileCount[row * nCols + col]
        if (count == 0) zeroTiles++
        double areaMm2 = clipped.getArea() * pw * ph / 1_000_000.0
        if (!(areaMm2 > 0)) continue
        double density = count / areaMm2
        double coverage = clipped.getArea() / fullTileAreaPx
        analyzedAreaMm2 += areaMm2
        if (coverage >= 0.99 && (!Double.isFinite(maxFullTileDensity) || density > maxFullTileDensity)) {
            maxFullTileDensity = density
        }

        def tile = PathObjects.createAnnotationObject(clipped)
        tile.setName(String.format(java.util.Locale.US, '%s %d,%d', GRID_BASE + setSuffix(setIndex), row, col))
        tile.measurements['Kare analiz alanı (mm2)'] = areaMm2
        tile.measurements['Kare kapsamı (%)'] = 100.0 * coverage
        tile.measurements['Hücre sayısı'] = count as double
        tile.measurements['Hücre yoğunluğu (hücre/mm2)'] = density
        tile.setLocked(true)
        newTiles << tile
    }
}

double overallDensity = analyzedAreaMm2 > 0 ? n / analyzedAreaMm2 : Double.NaN

def summary = PathObjects.createAnnotationObject(analysisROI)
summary.setName(SUMMARY_BASE + setSuffix(setIndex))
summary.measurements['Hücre sayısı (toplam)'] = n as double
summary.measurements['Analiz alanı (mm2)'] = analyzedAreaMm2
summary.measurements['Kare sayısı'] = newTiles.size() as double
summary.measurements['Sıfır hücreli kare'] = zeroTiles as double
summary.measurements['Kare boyutu (µm)'] = tilePxX * pw
summary.measurements['Genel yoğunluk (hücre/mm2)'] = overallDensity
summary.measurements['Maksimum tam-karo yoğunluğu (hücre/mm2)'] = maxFullTileDensity
summary.setLocked(true)

// Replace: yalnız bu betiğin önceki ızgara/özet anotasyonları (tam ad ya da "ad (N)") silinir;
// içlerindeki nesneler KORUNUR (keepChildren = true). Yeni nesneler hazır olduktan SONRA silinir.
int removedCount = 0
if (writeMode == MODE_REPLACE) {
    def prevNow = prevOwnGridObjects(hierarchy, GRID_BASE, SUMMARY_BASE)
    removedCount = prevNow.size()
    if (removedCount > 0) hierarchy.removeObjects(prevNow, true)
}
hierarchy.addObjects(newTiles)
hierarchy.addObjects([summary])
QP.fireHierarchyUpdate(hierarchy)

def fmt = { double value, String pattern ->
    Double.isFinite(value) ? String.format(java.util.Locale.US, pattern, value) : 'hesaplanamadı'
}
def body = new StringBuilder()
body << 'HÜCRE YOĞUNLUĞU HARİTASI\n'
body << '══════════════════════════════════\n\n'
if (!keptSetNames.isEmpty()) {
    body << String.format(java.util.Locale.US, workshopText('script.b880deb01e0a', 'DİKKAT — önceki sonuç hâlâ lamda duruyor: %s. Hiçbir şey silinmedi. Eski seti kaldırmak için o addaki anotasyonları seçip silin ya da betiği yeniden çalıştırıp "Öncekini değiştir"i seçin. Dışa aktarım ve özetler iki seti de görür.\nBu çalıştırmanın özeti: %s'), keptSetNames.join(', '), summary.getName()) << '\n\n'
} else if (removedCount > 0) {
    body << String.format(java.util.Locale.US, workshopText('script.d0cc14f27b20', 'Önceki sonuç değiştirildi: %,d anotasyon silindi (içlerindeki nesneler korundu).'), removedCount) << '\n\n'
}
body << String.format(java.util.Locale.US, 'Analiz alanı         : %.3f mm²%n', analyzedAreaMm2)
body << String.format(java.util.Locale.US, 'Hücre (toplam)        : %,d%n', n)
body << String.format(java.util.Locale.US, 'Izgara                : %d × %d; kare ≈ %.0f µm%n', nCols, nRows, tilePxX * pw)
body << String.format(java.util.Locale.US, 'ROI ile kesişen kare  : %,d%n', newTiles.size())
body << String.format(java.util.Locale.US, 'Sıfır hücreli kare    : %,d%n', zeroTiles)
body << '──────────────────────────────────\n'
body << "Genel yoğunluk        : ${fmt(overallDensity, '%.1f hücre/mm²')}\n"
body << "Maksimum tam karo     : ${fmt(maxFullTileDensity, '%.1f hücre/mm²')}\n"
body << '\nGenel yoğunluk = toplam hücre / toplam analiz alanı.\n'
body << 'Kısmi kenar karelerinde gerçek ROI-kesişim alanı payda olarak kullanılır.\n'
body << 'Maksimum değer yalnız yaklaşık tam alanlı karolar arasında hesaplanır.\n'
body << 'Sıfır hücreli kareler toplam analiz alanına dahildir.\n\n'
body << 'Bu çıktı betimsel bir ölçümdür; klinik yorum veya kategori üretmez.'

showResultWindow('Yoğunluk haritası', body.toString())
println String.format(java.util.Locale.US, 'Yoğunluk haritası: %d kare, genel yoğunluk %.1f hücre/mm²', newTiles.size(), overallDensity)

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
