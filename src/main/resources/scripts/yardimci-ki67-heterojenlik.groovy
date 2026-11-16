/**
 * Yardımcı - Ki-67 Heterojenlik Grid
 * -----------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * MEVCUT Ki-67 tespitlerini (Nükleer boya modülünden veya Tümör içi Ki-67
 * modülünden) bir kareli ızgaraya böler, her karede Ki-67 işaretleme
 * indeksini (LI) ölçer ve karelerarası
 * **heterojenlik** ölçütleri üretir: varyasyon katsayısı (CV) ve pozitif
 * hücrelerin uzamsal kümelenmesi için Morisita dağılım indeksi (Iδ).
 *
 * NEDEN?
 *   Tek bir "tümör LI %" değeri bölgesel değişkenliği gizler. Bir tümörde
 *   LI bölgeden bölgeye 5%–40% arası değişebilir (Ki-67 "hot-spot"). Bu
 *   betik bu değişkenliği bir ÖLÇÜME çevirir.
 *
 * GİRDİ (ÖN KOŞUL):
 *   • Lamda zaten Ki-67 hücre tespitleri olmalı (Nükleer boya modülünü ya
 *     da Tümör içi Ki-67 modülünü önce çalıştırın). Betik tespit YAPMAZ; var
 *     olanı kullanır.
 *   • Patolog tarafından gözden geçirilmiş "Tumor" anotasyonu zorunludur.
 *
 * ÇIKTI:
 *   • Her ızgara karesi: kilitli "Ki-67 grid r,c" anotasyonu +
 *     "Ki-67 LI (%)", "Hücre sayısı", "Pozitif hücre" ölçümleri
 *     → ölçüme göre renklendirilince heterojenlik HARİTASI olur
 *   • Kilitli "Ki-67 Heterojenlik Özet": tüm hücrelerden ağırlıklı LI,
 *     geçerli karoların eşit-ağırlıklı ortalama/SD/CV değerleri ve Iδ
 *
 * YÖNTEM REFERANSLARI:
 *   • Zhang Z et al. (2023), Mod Pathol — bölgesel Ki-67 heterojenliğinin
 *     dijital kantifikasyonu. doi:10.1016/j.modpat.2022.100017
 *   • Lu W et al. (2024), J Pathol Clin Res 10(1):e346 — ızgara temelli
 *     heterojenlik ölçütleri. doi:10.1002/cjp2.346
 *   • Morisita M (1959) — dağılım indeksi Iδ (≈1 rastgele, >1 kümelenmiş).
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
// Bu betiğin çıktısı: "Ki-67 grid r,c" kareleri + "Ki-67 Heterojenlik Özet". Yalnız TAM ad ya da
// "ad (N)" (N >= 2) eşleşir — önek eşleşmesi YOK; kullanıcının verdiği başka adlara dokunulmaz.
def GRID_BASE    = 'Ki-67 grid'
def SUMMARY_BASE = 'Ki-67 Heterojenlik Özet'
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
    def pick = Dialogs.showChoiceDialog(workshopText('script.483dcf5a0d31', 'Önceki Ki-67 heterojenlik sonucu'),
        String.format(java.util.Locale.US, workshopText('script.fc0022c3c70a', 'Bu lamda bu betiğin ürettiği %,d anotasyon zaten var (ızgara kareleri ve özet).\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni sonuç ayrı bir set olur ("Ki-67 grid (2) 0,0" ve "Ki-67 Heterojenlik Özet (2)" gibi). Lamda iki set birlikte durur; dışa aktarım ve özetler ikisini de görür.\n\n"Değiştir" seçilirse önceki ızgara kareleri ve özet anotasyonu silinir. Kendi anotasyonlarınıza dokunulmaz; silinen karelerin içindeki nesneler korunur.\n\nİptal: hiçbir şey hesaplanmaz, hiçbir şey yazılmaz.\n\nÖnceki sonuç ne olsun?'), prev.size()),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.fa2d2d520f90', 'Önceki Ki-67 heterojenlik sonucu silinecek'),
            String.format(java.util.Locale.US, workshopText('script.04bb9562edac', '%,d anotasyon silinecek: bu betiğin önceki ızgara kareleri ve özet anotasyonu (adı "Ki-67 grid …" ya da "Ki-67 Heterojenlik Özet" olanlar). Bu adlardan birini kendiniz verdiyseniz o anotasyon da silinir; karelerde elle yaptığınız değişiklikler kaybolur. Karelerin içindeki nesneler korunur.\n\nDevam edilsin mi?'), prev.size()))
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

            stage.setScene(new javafx.scene.Scene(root, 720, 560))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
double tileSizeMicrons = 500.0
int minCellsPerTile = 20
int maxTiles = 400
double dabThreshold = 0.20
String dabKey = 'Nucleus: DAB OD mean'

// ── 1) Görüntü, kalibrasyon ve gözden geçirilmiş Tumor sınırı ──────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', 'Görüntü açık değil'), workshopText('script.50e0ebf27e86', 'Önce Ki-67 tespiti yapılmış bir lam açın.'))
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage(workshopText('script.a5a01874aff2', 'Kalibrasyon yok'), workshopText('script.419875c9a222', 'Piksel boyutu tanımlı değil.'))
    return
}

// Analiz sınırı yalnız açıkça SEÇİLEN, sınıfı 'Tumor' olan anotasyonlardır;
// seçim yoksa betik durur (gözden geçirilmemiş bölgeleri ölçüme katmamak için).
def tumorObjects = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Tumor'
}
if (tumorObjects.isEmpty()) {
    Dialogs.showErrorMessage(workshopText('script.212df4ff20ab', 'Tumor ROI seçili değil'), workshopText('script.689c8d34f00a', 'Ki-67 heterojenliği için gözden geçirilmiş Tumor anotasyonlarını seçin.'))
    return
}
def analysisROI = RoiTools.union(tumorObjects.collect { it.getROI() })
if (analysisROI == null || analysisROI.isEmpty() || !analysisROI.isArea()) {
    Dialogs.showErrorMessage(workshopText('script.b4b955ce35ce', 'Geçersiz Tumor ROI'), workshopText('script.24a766d77287', 'Tumor anotasyonları geçerli bir analiz alanı oluşturmuyor.'))
    return
}

def cells = QP.getDetectionObjects().findAll { detection ->
    def roi = detection.getROI()
    def dabValue = detection.measurements[dabKey]
    detection.isCell() && roi != null && dabValue != null &&
        Double.isFinite((dabValue as Number).doubleValue()) &&
        analysisROI.contains(roi.getCentroidX(), roi.getCentroidY())
}
if (cells.isEmpty()) {
    Dialogs.showErrorMessage(workshopText('script.4277b763a640', 'Tespit yok'), workshopText('script.cff0bde20d20', 'Tumor ROI içinde mevcut Ki-67 tespiti bulunamadı.'))
    return
}

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

def isPositive = { detection ->
    String className = detection.getPathClass()?.getName() ?: ''
    if (className.equalsIgnoreCase('Positive') || className.endsWith('+')) return true
    // Sınıfı açıkça 'Negative' olan hücre eşikle yeniden pozitif sayılmaz: sınıf ve eşik aynı
    // tabloda karışırsa pozitif oranı hiçbir tek ölçüte karşılık gelmez. Eşik yalnız sınıfsız hücreler içindir.
    if (className.equalsIgnoreCase('Negative')) return false
    def value = detection.measurements[dabKey]
    return value != null && (value as double) >= dabThreshold
}

// ── 2) ROI sınırlarından fiziksel ızgara ───────────────────────────
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

def tileTotal = new int[nCols * nRows]
def tilePositive = new int[nCols * nRows]
cells.each { cell ->
    def roi = cell.getROI()
    int col = Math.min(nCols - 1, Math.max(0, (int)((roi.getCentroidX() - minX) / tilePxX)))
    int row = Math.min(nRows - 1, Math.max(0, (int)((roi.getCentroidY() - minY) / tilePxY)))
    int index = row * nCols + col
    tileTotal[index]++
    if (isPositive(cell)) tilePositive[index]++
}

// ── 3) ROI-kesişimli tüm kareleri oluştur; sıfır quadratları koru ──
// (Önceki sonuç burada SİLİNMEZ: yalnız Replace kolu, yeni nesneler hazır olunca siler.)
def plane = analysisROI.getImagePlane()
def newTiles = []
def validTileLI = []
def morisitaCounts = []
int partialTiles = 0
int zeroCellTiles = 0
double fullTileAreaPx = tilePxX * tilePxY
for (int row = 0; row < nRows; row++) {
    for (int col = 0; col < nCols; col++) {
        int index = row * nCols + col
        double x = minX + col * tilePxX
        double y = minY + row * tilePxY
        def rectangle = ROIs.createRectangleROI(x, y, tilePxX, tilePxY, plane)
        def clipped = null
        try { clipped = RoiTools.intersection([rectangle, analysisROI]) } catch (Throwable ignore) {}
        if (clipped == null || clipped.isEmpty() || !clipped.isArea()) continue

        int total = tileTotal[index]
        int positive = tilePositive[index]
        if (total == 0) zeroCellTiles++
        double coverage = clipped.getArea() / fullTileAreaPx
        boolean fullQuadrat = coverage >= 0.99
        if (fullQuadrat) morisitaCounts << positive
        else partialTiles++

        double li = total >= minCellsPerTile ? 100.0 * positive / total : Double.NaN
        if (Double.isFinite(li)) validTileLI << li

        def tile = PathObjects.createAnnotationObject(clipped)
        tile.setName(String.format(java.util.Locale.US, '%s %d,%d', GRID_BASE + setSuffix(setIndex), row, col))
        tile.measurements['Kare kapsamı (%)'] = 100.0 * coverage
        tile.measurements['Hücre sayısı'] = total as double
        tile.measurements['Pozitif hücre'] = positive as double
        tile.measurements['Ki-67 LI (%)'] = li
        tile.setLocked(true)
        newTiles << tile
    }
}

// ── 4) Ağırlıklı genel LI ve betimsel karo dağılımı ────────────────
int totalCells = cells.size()
int totalPositive = cells.count { isPositive(it) } as int
double overallLI = totalCells > 0 ? 100.0 * totalPositive / totalCells : Double.NaN
double meanTileLI = validTileLI.isEmpty() ? Double.NaN : validTileLI.sum() / validTileLI.size()
double sdTileLI = Double.NaN
double cvTileLI = Double.NaN
if (validTileLI.size() > 1) {
    double variance = validTileLI.collect { value -> (value - meanTileLI) * (value - meanTileLI) }.sum() / (validTileLI.size() - 1)   // örneklem SD (n−1)
    sdTileLI = Math.sqrt(variance)
    cvTileLI = meanTileLI > 0 ? 100.0 * sdTileLI / meanTileLI : Double.NaN
}

double sumXX = 0.0
long totalMorisitaPositive = 0
morisitaCounts.each { count ->
    sumXX += (double)count * (count - 1)
    totalMorisitaPositive += count
}
int quadrats = morisitaCounts.size()
double morisita = totalMorisitaPositive > 1 && quadrats > 1
    ? quadrats * sumXX / (totalMorisitaPositive * (totalMorisitaPositive - 1.0))
    : Double.NaN

def summary = PathObjects.createAnnotationObject(analysisROI)
summary.setName(SUMMARY_BASE + setSuffix(setIndex))
summary.measurements['Ağırlıklı genel LI (%)'] = overallLI
summary.measurements['Karo LI ortalaması (%)'] = meanTileLI
// Adlar "n-1" taşır: eski sürüm aynı değerleri n böleniyle ('Karo LI SD (%)', 'Karo LI CV (%)') yazıyordu;
// ad değiştiği için eski ve yeni dışa aktarımlar aynı sütunda sessizce karışmaz.
summary.measurements['Karo LI SD, n-1 (%)'] = sdTileLI
summary.measurements['Karo LI CV, n-1 (%)'] = cvTileLI
summary.measurements['LI için geçerli karo'] = validTileLI.size() as double
summary.measurements['Morisita quadrat sayısı'] = quadrats as double
summary.measurements['Morisita Iδ'] = morisita
summary.measurements['Sıfır hücreli karo'] = zeroCellTiles as double
summary.measurements['Kısmi kenar karosu'] = partialTiles as double
summary.measurements['Karo boyutu (µm)'] = tilePxX * pw
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

def fmt = { double value, String pattern -> Double.isFinite(value) ? String.format(java.util.Locale.US, pattern, value) : workshopText('script.77fa6502fb41', 'hesaplanamadı') }
def body = new StringBuilder()
body << workshopText('script.84827b298e22', 'Kİ-67 HETEROJENLİK IZGARASI\n')
body << '════════════════════════════════\n\n'
if (!keptSetNames.isEmpty()) {
    body << String.format(java.util.Locale.US, workshopText('script.b880deb01e0a', 'DİKKAT — önceki sonuç hâlâ lamda duruyor: %s. Hiçbir şey silinmedi. Eski seti kaldırmak için o addaki anotasyonları seçip silin ya da betiği yeniden çalıştırıp "Öncekini değiştir"i seçin. Dışa aktarım ve özetler iki seti de görür.\nBu çalıştırmanın özeti: %s'), keptSetNames.join(', '), summary.getName()) << '\n\n'
} else if (removedCount > 0) {
    body << String.format(java.util.Locale.US, workshopText('script.d0cc14f27b20', 'Önceki sonuç değiştirildi: %,d anotasyon silindi (içlerindeki nesneler korundu).'), removedCount) << '\n\n'
}
body << String.format(java.util.Locale.US, workshopText('script.f21d46de7a2b', 'Tumor ROI sayısı       : %,d%n'), tumorObjects.size())
body << String.format(java.util.Locale.US, workshopText('script.60b742a569db', 'Toplam hücre           : %,d%n'), totalCells)
body << String.format(java.util.Locale.US, workshopText('script.ad6ce95a35ef', 'Pozitif hücre          : %,d%n'), totalPositive)
body << String.format(java.util.Locale.US, workshopText('script.80e10f09ed14', 'Ağırlıklı genel LI    : %s\n'), fmt(overallLI, '%.2f%%'))
body << String.format(java.util.Locale.US, workshopText('script.7c7655aafe10', 'ROI-kesişimli karo     : %,d%n'), newTiles.size())
body << String.format(java.util.Locale.US, workshopText('script.aacc6c91eafe', 'Sıfır hücreli karo     : %,d%n'), zeroCellTiles)
body << String.format(java.util.Locale.US, workshopText('script.40ab979a7ed1', 'LI için geçerli karo   : %,d (karo başına ≥%d hücre)%n'), validTileLI.size(), minCellsPerTile)
body << '────────────────────────────────\n'
body << String.format(java.util.Locale.US, workshopText('script.019f07d85bf4', 'Karo LI ortalaması    : %s\n'), fmt(meanTileLI, '%.2f%%'))
body << String.format(java.util.Locale.US, workshopText('script.a870b3c14eef', 'Karo LI SD (n−1)      : %s\n'), fmt(sdTileLI, '%.2f%%'))
body << String.format(java.util.Locale.US, workshopText('script.1e0fa23d6d29', 'Karo LI CV (n−1)      :%s\n'), fmt(cvTileLI, '%.2f%%'))
body << "Morisita Iδ           : ${fmt(morisita, '%.4f')}\n"
body << String.format(java.util.Locale.US, workshopText('script.f505608a72f5', 'Morisita quadratları   : %,d tam alanlı karo%n'), quadrats)
body << String.format(java.util.Locale.US, workshopText('script.2d920d7623e6', 'Dışlanan kenar karosu  : %,d kısmi karo%n'), partialTiles)
body << workshopText('script.95d8c15d2ce7', '\nAğırlıklı genel LI, tüm hücrelerin birleşik oranıdır.\n')
body << workshopText('script.c4480a3ef49a', 'Karo ortalaması her geçerli karoya eşit ağırlık verir ve ayrı raporlanır.\n')
body << workshopText('script.5564564d35a9', 'Morisita hesabı eşit alan varsayımı için yalnız tam karoları kullanır; sıfır quadratlar dahildir.\n\n')
body << workshopText('script.89893f30f3c3', 'Bu çıktı betimsel bir ölçümdür; klinik yorum veya kategori üretmez.\n')
body << workshopText('script.cf6099f0a005', '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')

showResultWindow(workshopText('script.56821367a541', 'Ki-67 heterojenlik'), body.toString())
println String.format(java.util.Locale.US, 'Ki-67 heterojenlik: %d karo, genel LI %s', newTiles.size(), fmt(overallLI, '%.2f%%'))

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
