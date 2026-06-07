/**
 * Yardımcı - En Yakın Komşu Mesafesi
 * -----------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Her hücre için en yakın komşu hücreye olan merkez-merkez mesafesini (µm)
 * hesaplar ve hücre ölçümü olarak yazar. Karelerarası uzamsal düzeni
 * (kümelenme/dağınıklık) sayısallaştıran basit bir ÖLÇÜTtür.
 *
 * QuPath KARŞILIĞI (GUI):
 *   [Analyze → Spatial analysis → Detect centroid distances of detections]
 *   yerleşik komutu sınıflar-arası mesafe de üretir. Bu betik sınıf-bağımsız
 *   "en yakın herhangi bir hücre" mesafesini doğrudan, eklenti gerektirmeden
 *   hesaplar (uzamsal hash ızgarası ile hızlı + tam sonuç).
 *
 * GİRDİ (ÖNKOŞUL):
 *   • Slaytta hücre tespitleri olmalı (Modül 2/3/5/7). Betik tespit YAPMAZ.
 *
 * ÇIKTI:
 *   • Her hücre: "En yakın komşu (µm)" ölçümü (Modül 9 ile dışa aktarılır)
 *   • Kilitli "En Yakın Komşu Özet": ortalama / medyan / minimum NN mesafesi
 *
 * YÖNTEM REFERANSI:
 *   • Summers MA et al. (2022), Cell Rep Methods — uzamsal komşuluk/mesafe
 *     ölçütlerinin doku analizinde kullanımı. doi:10.1016/j.crmeth.2022.100348
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
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 700, 480))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
double bucketMicrons = 50.0   // uzamsal hash kovası kenarı (µm)

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce hücre tespiti yapılmış bir slayt açın.")
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
if (!(pw > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok", "Slaytta piksel boyutu (µm) tanımlı değil.")
    return
}

def cells = QP.getDetectionObjects().findAll { it.getROI() != null }
int n = cells.size()
if (n < 2) {
    def msg = "En yakın komşu için en az 2 hücre gerekir.\n" +
              "Önce Modül 2/3/5/7 ile hücre tespiti yapın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Yetersiz hücre", msg)
    return
}

// ── 2) Merkez koordinatları + uzamsal hash ızgarası ─────────────────
double[] xs = new double[n]
double[] ys = new double[n]
cells.eachWithIndex { c, i -> def r = c.getROI(); xs[i] = r.getCentroidX(); ys[i] = r.getCentroidY() }

double bucketPx = bucketMicrons / pw
def buckets = new HashMap<Long, List<Integer>>()
int[] bcol = new int[n]
int[] brow = new int[n]
def keyOf = { int c, int r -> (((long) c) << 32) ^ (r & 0xffffffffL) }
for (int i = 0; i < n; i++) {
    int c = (int) Math.floor(xs[i] / bucketPx)
    int r = (int) Math.floor(ys[i] / bucketPx)
    bcol[i] = c; brow[i] = r
    buckets.computeIfAbsent(keyOf(c, r), { k -> new ArrayList<Integer>() }).add(i)
}

// ── 3) Her hücre için en yakın komşu (halka halka genişleyen arama) ─
println String.format(java.util.Locale.US, "En yakın komşu hesaplanıyor (%,d hücre)...", n)
double[] nnPx = new double[n]
for (int i = 0; i < n; i++) {
    double best = Double.POSITIVE_INFINITY
    int ring = 0
    while (true) {
        for (int dc = -ring; dc <= ring; dc++) {
            for (int dr = -ring; dr <= ring; dr++) {
                if (ring > 0 && Math.max(Math.abs(dc), Math.abs(dr)) != ring) continue  // yalnız dış halka
                def lst = buckets.get(keyOf(bcol[i] + dc, brow[i] + dr))
                if (lst == null) continue
                for (int j : lst) {
                    if (j == i) continue
                    double dx = xs[i] - xs[j], dy = ys[i] - ys[j]
                    double d = dx * dx + dy * dy
                    if (d < best) best = d
                }
            }
        }
        // Aranmamış en yakın kova ≥ ring*bucketPx uzakta → o mesafe best'i geçemezse dur
        double guaranteed = ring * bucketPx
        if (best < Double.POSITIVE_INFINITY && guaranteed * guaranteed >= best) break
        ring++
        if (ring > 200) break  // güvenlik
    }
    nnPx[i] = best < Double.POSITIVE_INFINITY ? Math.sqrt(best) : Double.NaN
}

// ── 4) Hücre ölçümlerini yaz + istatistik topla ─────────────────────
def nnUm = []
for (int i = 0; i < n; i++) {
    if (Double.isNaN(nnPx[i])) continue
    double um = nnPx[i] * pw
    cells[i].measurements['En yakın komşu (µm)'] = um
    nnUm << um
}

double meanNN = 0.0, medianNN = 0.0, minNN = 0.0
if (!nnUm.isEmpty()) {
    meanNN = nnUm.sum() / nnUm.size()
    def sorted = nnUm.sort(false)
    int m = sorted.size()
    medianNN = (m % 2 == 1) ? sorted[(int) (m / 2)] : (sorted[m / 2 - 1] + sorted[m / 2]) / 2.0
    minNN = sorted[0]
}

// ── 5) Kilitli özet anotasyonu ──────────────────────────────────────
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == "En Yakın Komşu Özet" }, false)
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName("En Yakın Komşu Özet")
summary.measurements['Hücre sayısı']               = n as double
summary.measurements['Ortalama NN mesafesi (µm)']  = meanNN
summary.measurements['Medyan NN mesafesi (µm)']    = medianNN
summary.measurements['Minimum NN mesafesi (µm)']   = minNN
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

// ── 6) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "EN YAKIN KOMŞU MESAFESİ\n"
body << "════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Hücre sayısı     : %,d%n", n)
body << String.format(java.util.Locale.US, "Ortalama NN      : %.1f µm%n", meanNN)
body << String.format(java.util.Locale.US, "Medyan NN        : %.1f µm%n", medianNN)
body << String.format(java.util.Locale.US, "Minimum NN       : %.1f µm%n", minNN)
body << "\n"
body << "Küçük NN mesafesi → hücreler sıkı (yüksek yoğunluk/kümelenme).\n"
body << "Her hücreye 'En yakın komşu (µm)' ölçümü yazıldı; ölçüme göre\n"
body << "renklendirilince yerel sıkışıklık haritası görünür.\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("En yakın komşu mesafesi", body.toString())
println String.format(java.util.Locale.US, "✓ NN mesafesi yazıldı (ortalama %.1f µm, medyan %.1f µm).", meanNN, medianNN)
