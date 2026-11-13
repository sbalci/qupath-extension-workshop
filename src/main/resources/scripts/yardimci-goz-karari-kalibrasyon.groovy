/**
 * Yardımcı - Göz Kararı Kalibrasyon Alıştırması (Kör Tahmin vs. QuPath Ölçümü)
 * ----------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * Seçili kapalı alan anotasyonu (ROI) içinde MEVCUT sınıflandırılmış hücre
 * tespitlerini geçici olarak gizler; kullanıcının çıplak mikroskop görüntüsü
 * üzerinden kör göz kararı değerlendirme (%) girmesini sağlar ve ardından
 * QuPath'in hücre sayısı bazlı (%) ile pozitif tespit alanı bazlı (%)
 * ölçümlerini açıklayarak karşılaştırır.
 *
 * NEDEN:
 *   • Kümeleşme yanılsaması ve hücre boyutu yanılgısı (geniş tümör çekirdekleri
 *     ile küçük lenfositlerin aynı karede bulunması), göz kararı tahminlerin
 *     sistematik olarak alan oranına kaymasına yol açar.
 *   • 4×4 kalibrasyon ızgarası (16 alt kare) açıldığında gözün tek yoğun odağa
 *     demirlemesi azalır; alt kare ortalaması gerçek sayıma yaklaşır.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Var olan sınıflandırılmış tespitlerin sayısal oranını (%), pozitif tespit
 *     alanı oranını (%), yönlü sapmayı (yüzde puan), mutlak hatayı (puan) ve
 *     göreli hatayı (%) hesaplar; proje açıksa oturum kayıtlarını
 *     exports/goz-karari-kalibrasyon.tsv dosyasına ekler ve Bland–Altman
 *     ortalama sapma ile ±1.96 SD uyum sınırlarını özetler.
 *   • Klinik tanı, derece, tedavi yanıtı veya tanısal eşik kararı ÜRETMEZ.
 *
 * KULLANIM:
 *   1. Önce Hücre Tespiti + Nükleer/Membran/Sitoplazmik Boya veya Tümör İçi Ki-67
 *      modüllerinden biriyle bölgedeki hücreleri sınıflandırın.
 *   2. Değerlendirmek istediğiniz kapalı bölge anotasyonunu SEÇİN (sarı kenar).
 *   3. [Extensions → Atölye → Yardımcılar → Skorlama ve ölçüm →
 *       Göz kararı kalibrasyon alıştırması] menüsünü çalıştırın.
 *
 * ⚠️ Yalnızca araştırma ve eğitim amaçlı ölçüm üretir.
 */

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import qupath.fx.dialogs.Dialogs
import qupath.lib.gui.QuPathGUI
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs
import qupath.lib.scripting.QP

def isHeadless = QuPathGUI.getInstance() == null
final String GRID_NAME = "__GozKarariKalibrasyonIzgarasi__"

def imageData = QP.getCurrentImageData()
if (imageData == null) {
    def msg = "Açık sanal lam bulunamadı.\n\nÖnce sınıflandırılmış hücre tespitleri içeren bir lam açın."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Görüntü açık değil", msg)
    return
}

def selectedAreas = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getName() != GRID_NAME && it.getROI() != null && it.getROI().isArea() && it.getROI().getArea() > 0
}
if (selectedAreas.isEmpty()) {
    def msg = "Geçerli bir kapalı alan (ROI) seçili değil.\n\n" +
              "Nokta veya çizgi yerine dikdörtgen/poligon/fırça aracıyla kapalı bir bölge çizin\n" +
              "ve kenarı sarı olana kadar içine tıklayın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Bölge seçilmedi", msg)
    return
}

def targetAnn = selectedAreas[0]
def targetRoi = targetAnn.getROI()

def insideDetections = QP.getDetectionObjects().findAll { d ->
    def dr = d.getROI()
    dr != null && targetRoi.contains(dr.getCentroidX(), dr.getCentroidY())
}

if (insideDetections.isEmpty()) {
    def msg = "Seçili bölge içinde hücre tespiti bulunamadı.\n\n" +
              "Önce Nükleer boya (Ki-67), ER/PR H-score, Membran boya (HER2),\n" +
              "Sitoplazmik boya (CD68) veya Tümör içi Ki-67 modüllerinden birini çalıştırın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Tespit yok", msg)
    return
}

def normClass = { det ->
    def pc = det.getPathClass()
    pc == null ? "" : pc.toString().trim().toLowerCase(Locale.ROOT)
}

def isIhcPositive = { String c ->
    c == "positive" || c == "1+" || c == "2+" || c == "3+" ||
        c.endsWith(": positive") || c.endsWith(": 1+") || c.endsWith(": 2+") || c.endsWith(": 3+")
}
def isIhcNegative = { String c ->
    c == "negative" || c.endsWith(": negative")
}
def isTumorClass = { String c ->
    c.startsWith("tumor") || c.startsWith("tümör")
}

int ihcClassified = insideDetections.count { d ->
    String c = normClass(d)
    isIhcPositive(c) || isIhcNegative(c)
}
int tumorClassified = insideDetections.count { d ->
    String c = normClass(d)
    !c.isEmpty() && c != "ignore*"
}

if (ihcClassified == 0 && tumorClassified == 0) {
    def msg = "Seçili bölgedeki tespitler henüz sınıflandırılmamış (yalnız sınıfsız çekirdekler var).\n\n" +
              "Önce pozitif/negatif veya hücre tipi sınıflandırması üreten bir modül çalıştırın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Sınıflandırma yok", msg)
    return
}

boolean ihcMode = ihcClassified > 0
String metricLabel = ihcMode ? "Pozitif hücre oranı (1+/2+/3+/Positive)" : "Tümör hücre oranı (Tumor / tüm sınıflı hücreler)"

List denominatorCells = insideDetections.findAll { d ->
    String c = normClass(d)
    ihcMode ? (isIhcPositive(c) || isIhcNegative(c)) : (!c.isEmpty() && c != "ignore*")
}
List positiveCells = denominatorCells.findAll { d ->
    String c = normClass(d)
    ihcMode ? isIhcPositive(c) : isTumorClass(c)
}

int totalCount = denominatorCells.size()
int posCount = positiveCells.size()
double trueCellPct = totalCount > 0 ? (100.0 * posCount / totalCount) : 0.0

double totalCellAreaPx = 0.0
double posCellAreaPx = 0.0
denominatorCells.each { d ->
    double a = d.getROI() != null ? d.getROI().getArea() : 0.0
    if (Double.isFinite(a) && a > 0) totalCellAreaPx += a
}
positiveCells.each { d ->
    double a = d.getROI() != null ? d.getROI().getArea() : 0.0
    if (Double.isFinite(a) && a > 0) posCellAreaPx += a
}
double trueAreaPct = totalCellAreaPx > 0 ? (100.0 * posCellAreaPx / totalCellAreaPx) : trueCellPct

def cal = imageData.getServer().getPixelCalibration()
boolean hasMicrons = cal != null && cal.hasPixelSizeMicrons() &&
    cal.getPixelWidthMicrons() > 0 && cal.getPixelHeightMicrons() > 0
double roiAreaMm2 = hasMicrons
    ? (targetRoi.getArea() * cal.getPixelWidthMicrons() * cal.getPixelHeightMicrons()) / 1_000_000.0
    : Double.NaN

String imageName = imageData.getServer().getMetadata().getName() ?: "isimsiz-lam"
String roiName = targetAnn.getName() ?: "Secili-ROI"

if (isHeadless) {
    println String.format(Locale.US,
        "Göz Kararı Kalibrasyon Özeti [Headless] | Lam=%s | ROI=%s | Hücre=%d/%d (%.2f%%) | Pozitif hücre alanı oranı=%.2f%%",
        imageName, roiName, posCount, totalCount, trueCellPct, trueAreaPct)
    return
}

def removeGrid = {
    def stale = QP.getAnnotationObjects().findAll { it.getName() == GRID_NAME }
    if (!stale.isEmpty()) {
        QP.removeObjects(stale, true)
        QP.fireHierarchyUpdate()
    }
}

def addGrid = {
    removeGrid()
    double bx = targetRoi.getBoundsX()
    double by = targetRoi.getBoundsY()
    double bw = targetRoi.getBoundsWidth()
    double bh = targetRoi.getBoundsHeight()
    if (bw <= 4 || bh <= 4) return
    ImagePlane plane = targetRoi.getImagePlane() ?: ImagePlane.getDefaultPlane()
    PathClass gridPc = PathClass.fromString("Kalibrasyon Izgarası", qupath.lib.common.ColorTools.packRGB(0, 114, 178))
    def cells = []
    for (int r = 0; r < 4; r++) {
        for (int c = 0; c < 4; c++) {
            def rect = ROIs.createRectangleROI(bx + c * (bw / 4.0), by + r * (bh / 4.0), bw / 4.0, bh / 4.0, plane)
            def ann = PathObjects.createAnnotationObject(rect, gridPc)
            ann.setName(GRID_NAME)
            ann.setLocked(true)
            cells << ann
        }
    }
    QP.addObjects(cells)
    QP.fireHierarchyUpdate()
}

def appendAndSummarizeLog = { double guessCellPct, Double guessAreaPct, double signedBias, double absErr, double relErr ->
    def project = QP.getProject()
    java.nio.file.Path logPath
    if (project != null && project.getPath() != null) {
        def baseDir = project.getPath().getParent() ?: project.getPath()
        def expDir = baseDir.resolve("exports")
        Files.createDirectories(expDir)
        logPath = expDir.resolve("goz-karari-kalibrasyon.tsv")
    } else {
        def tmpDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "qupath-atolye")
        Files.createDirectories(tmpDir)
        logPath = tmpDir.resolve("goz-karari-kalibrasyon.tsv")
    }

    if (!Files.exists(logPath)) {
        String header = "tarih_saat\tlam\tbolge\tolcum_tipi\ttoplam_hucre\thedef_hucre\tqupath_hucre_yuzde\tqupath_alan_yuzde\tgoz_karari_hucre_yuzde\tgoz_karari_alan_yuzde\tyonlu_sapma_puan\tmutlak_hata_puan\tgoreli_hata_yuzde\n"
        Files.writeString(logPath, header, StandardCharsets.UTF_8)
    }

    String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT))
    String safeImg = imageName.replace('\t', ' ').replace('\n', ' ')
    String safeRoi = roiName.replace('\t', ' ').replace('\n', ' ')
    String areaGuessStr = guessAreaPct != null ? String.format(Locale.US, "%.2f", guessAreaPct) : ""
    String row = String.format(Locale.US,
        "%s\t%s\t%s\t%s\t%d\t%d\t%.2f\t%.2f\t%.2f\t%s\t%.2f\t%.2f\t%.2f%n",
        ts, safeImg, safeRoi, ihcMode ? "IHK_Pozitif" : "Tumor_Orani",
        totalCount, posCount, trueCellPct, trueAreaPct, guessCellPct, areaGuessStr,
        signedBias, absErr, relErr)
    Files.writeString(logPath, row, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND)

    List<Double> diffs = []
    List<Double> absList = []
    Files.readAllLines(logPath, StandardCharsets.UTF_8).drop(1).each { line ->
        def parts = line.split('\t', -1)
        if (parts.length >= 12) {
            try {
                diffs << Double.parseDouble(parts[10])
                absList << Double.parseDouble(parts[11])
            } catch (Exception ignored) {}
        }
    }
    int n = diffs.size()
    double meanBias = n > 0 ? (diffs.sum() as double) / n : 0.0
    double mae = n > 0 ? (absList.sum() as double) / n : 0.0
    double sd = 0.0
    if (n > 1) {
        double varSum = 0.0
        diffs.each { d -> varSum += (d - meanBias) * (d - meanBias) }
        sd = Math.sqrt(varSum / (n - 1))
    }
    return [
        path: logPath.toString(),
        n: n,
        mae: mae,
        bias: meanBias,
        loaLow: meanBias - 1.96 * sd,
        loaHigh: meanBias + 1.96 * sd
    ]
}

javafx.application.Platform.runLater {
    def gui = QuPathGUI.getInstance()
    def viewer = gui?.getViewer()
    def overlayOpts = viewer?.getOverlayOptions()
    boolean initialShowDetections = overlayOpts != null ? overlayOpts.getShowDetections() : true

    // Kör göz kararı tahmini için tespit halkalarını geçici olarak gizle
    if (overlayOpts != null) {
        overlayOpts.setShowDetections(false)
        javafx.application.Platform.runLater { viewer?.repaint() }
    }

    def stage = new javafx.stage.Stage()
    stage.initModality(javafx.stage.Modality.NONE)
    stage.setTitle("Göz Kararı Kalibrasyon Alıştırması")
    stage.setAlwaysOnTop(true)

    def titleLbl = new javafx.scene.control.Label("🔬 Göz Kararı Değerlendirme vs. QuPath Sayısal Ölçümü")
    titleLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;")
    titleLbl.setWrapText(true)
    titleLbl.setMaxWidth(Double.MAX_VALUE)

    String areaInfo = hasMicrons ? String.format(Locale.US, "%.3f mm²", roiAreaMm2) : "kalibrasyonsuz (piksel)"
    def infoLbl = new javafx.scene.control.Label(
        "Seçili bölgedeki renkli tespit halkaları KÖR TAHMİN için geçici olarak gizlendi.\n" +
        "Bölge: " + roiName + " (" + areaInfo + ") · Hedef oran: " + metricLabel
    )
    infoLbl.setWrapText(true)
    infoLbl.setMaxWidth(Double.MAX_VALUE)
    infoLbl.setStyle("-fx-background-color: #eef6fb; -fx-border-color: #9ac4e2; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-padding: 8px;")

    def gridCheck = new javafx.scene.control.CheckBox("📐 Seçili bölge üzerine geçici 4×4 kalibrasyon ızgarası (16 alt kare) yerleştir")
    gridCheck.selectedProperty().addListener({ obs, oldV, newV ->
        if (newV) addGrid() else removeGrid()
        javafx.application.Platform.runLater { viewer?.repaint() }
    } as javafx.beans.value.ChangeListener)

    def blindToggle = new javafx.scene.control.CheckBox("🙈 Kör görünüm: hücre tespit halkalarını gizli tut")
    blindToggle.setSelected(true)
    blindToggle.selectedProperty().addListener({ obs, oldV, newV ->
        if (overlayOpts != null) {
            overlayOpts.setShowDetections(!newV)
            javafx.application.Platform.runLater { viewer?.repaint() }
        }
    } as javafx.beans.value.ChangeListener)

    def form = new javafx.scene.layout.GridPane()
    form.setHgap(10)
    form.setVgap(8)

    def cellGuessLbl = new javafx.scene.control.Label("1. Göz kararı HÜCRE oranı tahmininiz (%):")
    def cellGuessTf = new javafx.scene.control.TextField()
    cellGuessTf.setPromptText("Örn. 25 veya 27,5 (0–100)")
    cellGuessTf.setPrefColumnCount(14)

    def areaGuessLbl = new javafx.scene.control.Label("2. İsteğe bağlı — pozitif ALAN oranı tahmininiz (%):")
    def areaGuessTf = new javafx.scene.control.TextField()
    areaGuessTf.setPromptText("Boş bırakılabilir (boyut yanılgısı testi)")
    areaGuessTf.setPrefColumnCount(14)

    form.add(cellGuessLbl, 0, 0)
    form.add(cellGuessTf, 1, 0)
    form.add(areaGuessLbl, 0, 1)
    form.add(areaGuessTf, 1, 1)

    def resultArea = new javafx.scene.control.TextArea(
        "Adımlar:\n" +
        "  1) Mikroskop görünümünde seçili bölgeyi inceleyin (isterseniz 4×4 ızgarayı açıp 16 alt karenin ortalamasını alın).\n" +
        "  2) Yukarıya göz kararı yüzde tahmininizi yazın.\n" +
        "  3) 'QuPath Ölçümünü Açıkla' düğmesine basın: gizlenen hücre halkaları açılır, hücre sayısı oranı ile\n" +
        "     hücre alanı oranı yan yana gösterilir ve kişisel Bland–Altman sapma özetiniz güncellenir."
    )
    resultArea.setEditable(false)
    resultArea.setWrapText(false)
    resultArea.setPrefRowCount(15)
    resultArea.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;")
    javafx.scene.layout.VBox.setVgrow(resultArea, javafx.scene.layout.Priority.ALWAYS)

    def revealBtn = new javafx.scene.control.Button("🔍 QuPath Ölçümünü Açıkla ve Karşılaştır")
    revealBtn.setStyle("-fx-font-weight: bold;")

    revealBtn.setOnAction({
        String rawCell = (cellGuessTf.getText() ?: "").trim().replace(',', '.')
        double guessCell
        try {
            guessCell = Double.parseDouble(rawCell)
            if (!Double.isFinite(guessCell) || guessCell < 0.0 || guessCell > 100.0) {
                throw new IllegalArgumentException("out of bounds")
            }
        } catch (Exception ignored) {
            Dialogs.showWarningNotification("Geçersiz yüzde", "Hücre oranı tahmini için 0 ile 100 arasında bir sayı girin (ör. 25 veya 27,5).")
            return
        }

        String rawArea = (areaGuessTf.getText() ?: "").trim().replace(',', '.')
        Double guessArea = null
        if (!rawArea.isEmpty()) {
            try {
                double ga = Double.parseDouble(rawArea)
                if (!Double.isFinite(ga) || ga < 0.0 || ga > 100.0) throw new IllegalArgumentException("bounds")
                guessArea = ga
            } catch (Exception ignored) {
                Dialogs.showWarningNotification("Geçersiz alan yüzdesi", "İsteğe bağlı alan tahmini 0 ile 100 arasında olmalıdır.")
                return
            }
        }

        blindToggle.setSelected(false)
        if (overlayOpts != null) {
            overlayOpts.setShowDetections(true)
            javafx.application.Platform.runLater { viewer?.repaint() }
        }

        double signedBias = guessCell - trueCellPct
        double absErr = Math.abs(signedBias)
        double relErr = 100.0 * absErr / Math.max(trueCellPct, 0.5)
        double sizeIllusionGap = trueAreaPct - trueCellPct

        def stats = [path: "(kaydedilemedi)", n: 1, mae: absErr, bias: signedBias, loaLow: signedBias, loaHigh: signedBias]
        try {
            stats = appendAndSummarizeLog(guessCell, guessArea, signedBias, absErr, relErr)
        } catch (Exception ex) {
            stats.path = "Kayıt yazılamadı: " + ex.getMessage()
        }

        def sb = new StringBuilder()
        sb << "GÖZ KARARI KALİBRASYON KARŞILAŞTIRMASI\n"
        sb << "══════════════════════════════════════════════════════════════\n"
        sb << String.format(Locale.US, "Sanal lam / Bölge             : %s / %s (%s)%n", imageName, roiName, areaInfo)
        sb << String.format(Locale.US, "Ölçülen oran tanımı           : %s%n", metricLabel)
        sb << String.format(Locale.US, "Sınıflandırılmış hücre        : %,d (hedef: %,d)%n%n", totalCount, posCount)
        sb << String.format(Locale.US, "  • Göz kararı hücre tahmini  : %6.2f %%%n", guessCell)
        sb << String.format(Locale.US, "  • QuPath HÜCRE sayısı oranı : %6.2f %%%n", trueCellPct)
        sb << String.format(Locale.US, "  • QuPath HÜCRE ALANI oranı  : %6.2f %%  (Alan − Sayı farkı: %+.2f puan)%n", trueAreaPct, sizeIllusionGap)
        if (guessArea != null) {
            sb << String.format(Locale.US, "  • Göz kararı alan tahmini   : %6.2f %%  (Alan hatası: %+.2f puan)%n", guessArea, guessArea - trueAreaPct)
        }
        sb << "──────────────────────────────────────────────────────────────\n"
        sb << String.format(Locale.US, "  • Yönlü sapma (Tahmin−Sayı) : %+6.2f yüzde puan (%s)%n",
            signedBias, signedBias > 0.5 ? "olduğundan yüksek tahmin" : (signedBias < -0.5 ? "olduğundan düşük tahmin" : "çok yakın kalibrasyon"))
        sb << String.format(Locale.US, "  • Mutlak hata               : %6.2f yüzde puan%n", absErr)
        sb << String.format(Locale.US, "  • Göreli hata               : %6.1f %%%n%n", relErr)

        if (Math.abs(sizeIllusionGap) >= 3.0 && Math.abs(guessCell - trueAreaPct) < Math.abs(guessCell - trueCellPct)) {
            sb << "💡 Algısal Not (Hücre Boyutu Yanılgısı): Tahmininiz hücre SAYISI oranından çok pozitif ALAN oranına\n"
            sb << "   yakın düştü. Geniş tümör hücreleri mikroskop alanında küçük lenfositlerden daha fazla piksel kaplar!\n\n"
        }

        sb << String.format(Locale.US, "OTURUM ÖZETİ (Toplam N = %d deneme)%n", stats.n)
        sb << String.format(Locale.US, "  • Ortalama mutlak hata (MAE): %.2f puan%n", stats.mae)
        sb << String.format(Locale.US, "  • Bland–Altman ortalama fark: %+.2f puan%n", stats.bias)
        if (stats.n > 1) {
            sb << String.format(Locale.US, "  • Bland–Altman ±1.96 SD LoA : [%+.2f , %+.2f] puan%n", stats.loaLow, stats.loaHigh)
        }
        sb << "  • Kayıt dosyası             : " + stats.path + "\n\n"
        sb << "⚠️ Yalnızca araştırma ve eğitim amaçlı kalibrasyon çıktısıdır; klinik tanı kararı üretmez."

        resultArea.setText(sb.toString())
    })

    def copyBtn = new javafx.scene.control.Button("📋 Kopyala")
    copyBtn.setOnAction({
        def cb = javafx.scene.input.Clipboard.getSystemClipboard()
        def content = new javafx.scene.input.ClipboardContent()
        content.putString(resultArea.getText())
        cb.setContent(content)
    })

    def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut")
    alwaysTop.setSelected(true)
    alwaysTop.selectedProperty().addListener(
        { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
    )

    def closeBtn = new javafx.scene.control.Button("Kapat")
    closeBtn.setOnAction({ stage.close() })

    def spacer = new javafx.scene.layout.Region()
    javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def footer = new javafx.scene.layout.HBox(10, revealBtn, copyBtn, spacer, alwaysTop, closeBtn)
    footer.setAlignment(javafx.geometry.Pos.CENTER_LEFT)

    def root = new javafx.scene.layout.VBox(10, titleLbl, infoLbl, gridCheck, blindToggle, form, resultArea, footer)
    root.setPadding(new javafx.geometry.Insets(12))

    stage.setOnHidden({
        removeGrid()
        if (overlayOpts != null) {
            overlayOpts.setShowDetections(initialShowDetections)
            javafx.application.Platform.runLater { viewer?.repaint() }
        }
    })

    stage.setScene(new javafx.scene.Scene(root, 680, 560))
    stage.show()
}
