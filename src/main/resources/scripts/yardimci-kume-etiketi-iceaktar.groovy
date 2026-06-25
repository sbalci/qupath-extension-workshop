/**
 * Yardımcı - Kümeleme / Fenotip Etiketlerini İçe Aktar (TSV)
 * ----------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Python / R tarafında üretilmiş bir küme / fenotip tablosunu (TSV veya CSV)
 *   QuPath'teki MEVCUT tespitlere geri bağlar. "Makine öğrenmesi için özellik
 *   matrisi" yardımcısıyla dışa aktardığınız matris üzerinde denetimsiz kümeleme
 *   (ör. UMAP + Leiden — bkz. Ek: Hücre Fenotipleme) çalıştırıp her hücreye bir
 *   sınıf (ör. "Cluster 3") ve sayısal ölçümler (ör. UMAP1, UMAP2) atadıktan
 *   sonra, bu betik o sonuçları tek tıkla QuPath'e geri yazar. Dosya-tabanlı
 *   yol: Py4J / QuBaLab köprüsü gerekmez (canlı köprü için bkz. Ek: QuBaLab).
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Yalnızca harici sonuçları (sınıf etiketi + ölçüm) QuPath nesnelerine TAŞIR.
 *     Bu bir VERİ AKTARIM betiğidir; küme/fenotip bir KEŞİF çıktısıdır, klinik
 *     bir tanı kategorisi DEĞİLDİR ve grade/alt-tip/karar üretmez. Görsel
 *     doğrulama gerekir.
 *   • Eşleşen tespitlerin var olan PathClass'ını ÜZERİNE YAZAR (küme etiketiyle).
 *
 * EŞLEŞTİRME:
 *   • Birincil: `object_id` (QuPath UUID) sütunu — özellik matrisi yardımcısı bu
 *     sütunu yazar; birebir, kesin eşleşme.
 *   • Yedek: `object_id` yoksa `centroid_x_um` + `centroid_y_um` ile en yakın
 *     tespit (≤ 1 µm tolerans). Kalibrasyonsuz slaytta piksel cinsindendir.
 *
 * SÜTUN SÖZLEŞMESİ (başlık satırı zorunlu):
 *   • Anahtar : object_id   (ya da centroid_x_um + centroid_y_um)
 *   • Sınıf   : ilk bulunan — cluster / phenotype / classification / class /
 *               pathclass / label / cell_class  (boşsa atlanır)
 *   • Ölçüm   : kalan tüm SAYISAL sütunlar (ör. UMAP1, UMAP2) ölçüm olarak yazılır
 *   image / object_type / region_class sütunları yok sayılır.
 *
 * KULLANIM:
 *   1. Önce hücre tespiti üretin (Modül 2-7) ve "özellik matrisi" ile dışa aktarın.
 *   2. Python tarafında kümeleme yapıp object_id + sınıf + (UMAP vb.) sütunlu bir
 *      TSV üretin (bkz. Ek: Hücre Fenotipleme).
 *   3. Eşleşen WSI'ı QuPath'te açın → bu betiği çalıştırın → TSV'yi seçin.
 *
 * ÇIKTI:
 *   • Eşleşen tespitlerde sınıf (ör. "Cluster N") + sayısal ölçümler (ör. UMAP1/2)
 *   • Eşleşen / eşleşmeyen satır, kullanılan sütunlar ve sınıf dökümü özeti
 *   • UMAP1/UMAP2 ölçümlerini QuPath'in dağılım grafiğinde görselleştirebilirsiniz.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.
 * VARSAYIM: Düz TSV/CSV (alan içi tırnak yok); ondalık ayraç '.' (nokta).
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import java.util.regex.Pattern

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

            stage.setScene(new javafx.scene.Scene(root, 760, 580))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
double centroidToleranceUm = 1.0d   // yedek (centroid) eşleştirme yarıçapı
def CLASS_CANDIDATES = ["cluster", "phenotype", "classification", "class", "pathclass", "label", "cell_class"]
def NON_MEASUREMENT  = ["object_id", "centroid_x_um", "centroid_y_um", "image", "object_type", "region_class"]

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce etiketlerin ait olduğu WSI'ı açın.")
    return
}
def dets = QP.getDetectionObjects()
if (dets == null || dets.isEmpty()) {
    Dialogs.showErrorMessage("Tespit yok",
        "Bu görüntüde hiç tespit yok. Önce hücre tespiti çalıştırın (Modül 2-7).")
    return
}
if (isHeadless) {
    println "TSV dosya seçimi için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    return
}

// ── 2) TSV / CSV dosyasını seç ───────────────────────────────────────
def tsvFile = Dialogs.promptForFile(
    "Küme / fenotip etiketi tablosunu seçin", null, "Küme/Fenotip TSV", "tsv", "csv", "txt")
if (tsvFile == null) {
    println "İptal edildi — dosya seçilmedi."
    return
}

// ── 3) Başlığı oku, ayracı sapta ─────────────────────────────────────
def lines = tsvFile.readLines("UTF-8")
if (lines == null || lines.size() < 2) {
    Dialogs.showErrorMessage("Boş dosya", "Tabloda başlık + en az bir veri satırı bekleniyor.")
    return
}
def headerLine = lines[0]
def sep = ["\t", ",", ";"].max { headerLine.count(it) }
def splitRx = Pattern.quote(sep)
def headers = headerLine.split(splitRx, -1).collect { it.trim() }

def idxOf = { String name -> headers.findIndexOf { it.equalsIgnoreCase(name) } }

int idIdx = idxOf("object_id")
int xIdx  = idxOf("centroid_x_um")
int yIdx  = idxOf("centroid_y_um")
boolean byId = (idIdx >= 0)
if (!byId && (xIdx < 0 || yIdx < 0)) {
    Dialogs.showErrorMessage("Eşleştirme sütunu yok",
        "Tablo, tespitleri eşleştirmek için 'object_id' (önerilen) ya da\n" +
        "'centroid_x_um' + 'centroid_y_um' sütunlarını içermelidir.\n\n" +
        "İpucu: özellik matrisi yardımcısı object_id sütununu otomatik yazar.")
    return
}

int classIdx = -1
String classColName = null
for (String c : CLASS_CANDIDATES) {
    int i = idxOf(c)
    if (i >= 0) { classIdx = i; classColName = headers[i]; break }
}

def measCols = []   // [index, name]
headers.eachWithIndex { String h, int i ->
    if (i == classIdx) return
    if (NON_MEASUREMENT.any { it.equalsIgnoreCase(h) }) return
    measCols << [i, h]
}

def parseDouble = { String s ->
    if (s == null) return null
    def t = s.trim()
    if (t.isEmpty()) return null
    try { return Double.parseDouble(t) } catch (Throwable e) { return null }
}

// ── 4) Tespit indeksleri (UUID + centroid ızgarası) ──────────────────
def byUuid = [:]
dets.each { d -> if (d.getID() != null) byUuid[d.getID().toString()] = d }

def cal = imageData.getServer().getPixelCalibration()
boolean hasMicrons = cal.hasPixelSizeMicrons()
double pw = hasMicrons ? cal.getPixelWidthMicrons() : 1.0d
double ph = hasMicrons ? cal.getPixelHeightMicrons() : 1.0d
double cellSize = Math.max(centroidToleranceUm, 1e-6d)
def grid = [:]
if (!byId) {
    dets.each { d ->
        def roi = d.getROI()
        if (roi == null) return
        double cx = roi.getCentroidX() * pw
        double cy = roi.getCentroidY() * ph
        def k = ((long) Math.floor(cx / cellSize)) + "_" + ((long) Math.floor(cy / cellSize))
        def list = grid.get(k)
        if (list == null) { list = []; grid.put(k, list) }
        list << d
    }
}
def findByCentroid = { double x, double y ->
    def best = null
    double bestDist = centroidToleranceUm
    long bx = (long) Math.floor(x / cellSize)
    long by = (long) Math.floor(y / cellSize)
    for (long gx = bx - 1; gx <= bx + 1; gx++) {
        for (long gy = by - 1; gy <= by + 1; gy++) {
            def list = grid.get(gx + "_" + gy)
            if (list == null) continue
            list.each { d ->
                def roi = d.getROI()
                if (roi == null) return
                double dx = roi.getCentroidX() * pw - x
                double dy = roi.getCentroidY() * ph - y
                double dist = Math.sqrt(dx * dx + dy * dy)
                if (dist <= bestDist) { bestDist = dist; best = d }
            }
        }
    }
    return best
}

// ── 5) Satırları işle ────────────────────────────────────────────────
int matched = 0
int unmatched = 0
int classApplied = 0
int measApplied = 0
def classCounts = new TreeMap<String, Integer>()
def measColsUsed = new TreeSet<String>()
def coveredDets = new HashSet<String>()

for (int r = 1; r < lines.size(); r++) {
    def lineStr = lines[r]
    if (lineStr == null || lineStr.trim().isEmpty()) continue
    def cols = lineStr.split(splitRx, -1)

    def det = null
    if (byId) {
        if (idIdx < cols.length) {
            def key = cols[idIdx].trim()
            if (!key.isEmpty()) det = byUuid.get(key)
        }
    } else if (xIdx < cols.length && yIdx < cols.length) {
        def xv = parseDouble(cols[xIdx])
        def yv = parseDouble(cols[yIdx])
        if (xv != null && yv != null) det = findByCentroid(xv, yv)
    }

    if (det == null) { unmatched++; continue }
    matched++
    coveredDets.add(det.getID().toString())

    if (classIdx >= 0 && classIdx < cols.length) {
        def cv = cols[classIdx].trim()
        if (!cv.isEmpty()) {
            det.setPathClass(QP.getPathClass(cv))
            classApplied++
            classCounts[cv] = (classCounts.getOrDefault(cv, 0)) + 1
        }
    }

    def ml = det.getMeasurementList()
    measCols.each { pair ->
        int ci = (int) pair[0]
        String nm = (String) pair[1]
        if (ci < cols.length) {
            def mv = parseDouble(cols[ci])
            if (mv != null) {
                ml.put(nm, (double) mv)
                measApplied++
                measColsUsed.add(nm)
            }
        }
    }
}

QP.fireHierarchyUpdate()

if (matched == 0) {
    Dialogs.showWarningNotification("Eşleşme yok",
        "Tablodaki hiçbir satır mevcut tespitlerle eşleşmedi. " +
        (byId ? "object_id'ler aynı projeden mi?" : "centroid'ler aynı kalibrasyonda mı?"))
}

// ── 6) Özet ─────────────────────────────────────────────────────────
int detsWithoutRow = dets.size() - coveredDets.size()
def body = new StringBuilder()
body << "KÜME / FENOTİP ETİKETİ — İÇE AKTARMA\n"
body << "════════════════════════════════════\n\n"
body << "Dosya       : ${tsvFile.getName()}\n"
body << "Eşleştirme  : " + (byId ? "object_id (UUID, kesin)" : "centroid (≤ ${centroidToleranceUm} µm)") + "\n"
body << "Sınıf sütunu: " + (classColName != null ? classColName : "(yok — yalnız ölçüm yazıldı)") + "\n\n"
body << String.format(java.util.Locale.US, "Eşleşen satır        : %,d%n", matched)
body << String.format(java.util.Locale.US, "Eşleşmeyen satır     : %,d%n", unmatched)
body << String.format(java.util.Locale.US, "Sınıf atanan tespit  : %,d%n", classApplied)
body << String.format(java.util.Locale.US, "Yazılan ölçüm değeri : %,d%n", measApplied)
body << String.format(java.util.Locale.US, "Satırsız tespit      : %,d%n", detsWithoutRow)

if (!measColsUsed.isEmpty()) {
    body << "\nÖlçüm sütunları:\n"
    measColsUsed.each { body << "  • ${it}\n" }
}
if (!classCounts.isEmpty()) {
    body << "\nSınıf dökümü:\n"
    classCounts.each { cls, n ->
        body << String.format(java.util.Locale.US, "  %-20s : %,d%n", cls, n)
    }
}
body << "\nKüme/fenotip etiketleri bir KEŞİF çıktısıdır — klinik tanı kategorisi değildir;\n"
body << "görsel olarak doğrulanmalıdır. UMAP1/UMAP2 ölçümlerini QuPath'in dağılım grafiğinde\n"
body << "([Measure menüsü]) görselleştirebilirsiniz.\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar."

showResultWindow("Küme / fenotip etiketi içe aktarma", body.toString())
println String.format(java.util.Locale.US,
    "✓ İçe aktarma tamamlandı: %d eşleşti, %d eşleşmedi, %d sınıf, %d ölçüm.",
    matched, unmatched, classApplied, measApplied)
