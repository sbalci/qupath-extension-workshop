/**
 * Yardımcı - TMA Çekirdek Bazlı Dışa Aktarım
 * --------------------------------------------
 * Dearray edilmiş (ızgaraya ayrılmış) bir TMA'da her çekirdek (core) için
 * tespit ölçümlerini toplar ve **çekirdek-başına bir TSV tablosu** yazar.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Her TMA çekirdeği için hücre sayısı, pozitif hücre sayısı, pozitif %
 *     ve hücre/mm² yoğunluğu — yani SAYIM/YOĞUNLUK. Klinik skor DEĞİL.
 *
 * ÖN KOŞULLAR:
 *   1. TMA dearrayer çalıştırılmış olmalı (çekirdek ızgarası var)
 *      [TMA → TMA dearrayer]
 *   2. Bir tespit modülü çalıştırılmış olmalı (Modül 2/3/4/5…) → hücreler var
 *
 * KULLANIM:
 *   [Automate → Project scripts → bu betik]
 *
 * ÇIKTI:
 *   <proje-klasörü>/exports/YYYY-MM-DD_HHmmss/tma_cekirdek_ozet.tsv
 *   (sütunlar: Çekirdek ID, Geçerli, Alan mm2, Hücre, Pozitif, Pozitif %, Hücre/mm2)
 *
 * YÖNTEM REFERANSLARI:
 *   • Kurowski K et al. (2024), Methods Protoc 7(6):96 — AI-destekli yüksek-verim TMA.
 *     doi:10.3390/mps7060096
 *   • Loughrey MB et al. (2018), Histopathology 73(2):327–338 — TMA skorlama doğrulaması.
 *     doi:10.1111/his.13516
 *   • Porter RJ et al. (2023), Diagnostics 13(11):1890 — MLH1 TMA sınıflandırıcı.
 *     doi:10.3390/diagnostics13111890
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

            stage.setScene(new javafx.scene.Scene(root, 760, 560))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── 1) Ön kontroller ───────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce dearray edilmiş bir TMA slaytı açın.")
    return
}

def hierarchy = imageData.getHierarchy()
def tmaGrid = hierarchy.getTMAGrid()
if (tmaGrid == null || tmaGrid.nCores() == 0) {
    def msg = "Bu slaytta TMA ızgarası yok.\n\n" +
              "Önce [TMA → TMA dearrayer] ile çekirdekleri ızgaraya ayırın,\n" +
              "sonra bir tespit modülü (Modül 2/3/…) çalıştırın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("TMA bulunamadı", msg)
    return
}

def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
boolean calibrated = (pw > 0 && ph > 0)

def cores = tmaGrid.getTMACoreList()
def isPositive = { cell ->
    def cn = cell.getPathClass()?.getName()
    cn != null && (cn.equalsIgnoreCase("Positive") || cn.equalsIgnoreCase("Pozitif") || cn.endsWith("+"))
}

// ── 2) Çekirdek başına topla ────────────────────────────────────────
def rows = []
int totalCells = 0
int totalPos = 0
int validCores = 0
boolean anyDetections = false

cores.each { core ->
    def coreId = core.getName() ?: "?"
    boolean missing = core.isMissing()
    def cells = core.getChildObjects().findAll { it.isDetection() }
    if (!cells.isEmpty()) anyDetections = true

    int n = cells.size()
    int nPos = cells.count { isPositive(it) } as int
    double aMm2 = 0.0
    def roi = core.getROI()
    if (roi != null && calibrated) aMm2 = (roi.getArea() * pw * ph) / 1_000_000.0
    double pct = n > 0 ? (100.0 * nPos / n) : 0.0
    double density = aMm2 > 0 ? n / aMm2 : 0.0

    if (!missing) validCores++
    totalCells += n
    totalPos += nPos

    // Çekirdeğe ölçüm yaz (Modül 9 ile de dışa aktarılabilir)
    core.measurements['Hücre sayısı']          = n as double
    core.measurements['Pozitif hücre']         = nPos as double
    core.measurements['Pozitif hücre %']       = pct
    core.measurements['Hücre yoğunluğu (mm2)'] = density

    rows << [coreId, (missing ? "hayır" : "evet"), aMm2, n, nPos, pct, density]
}
QP.fireHierarchyUpdate()

if (!anyDetections) {
    def msg = "TMA ızgarası var ama çekirdeklerde hiç hücre tespiti yok.\n\n" +
              "Önce bir tespit modülü (Modül 2/3/4/5…) çalıştırın, sonra bu betiği tekrar deneyin."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Tespit yok", msg)
    return
}

// ── 3) TSV yaz ───────────────────────────────────────────────────────
def project = QP.getProject()
def stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
def outDir
if (project != null) {
    def projectDir = project.getPath().getParent().toFile()
    outDir = new File(new File(projectDir, "exports"), stamp)
} else {
    outDir = new File(System.getProperty("java.io.tmpdir"), "qupath_tma_${stamp}")
}
outDir.mkdirs()
def tsvFile = new File(outDir, "tma_cekirdek_ozet.tsv")

def sb = new StringBuilder()
sb << "Cekirdek ID\tGecerli\tAlan mm2\tHucre\tPozitif\tPozitif %\tHucre/mm2\n"
rows.each { r ->
    sb << String.format(java.util.Locale.US, "%s\t%s\t%.4f\t%d\t%d\t%.2f\t%.1f%n",
        r[0], r[1], r[2] as double, r[3] as int, r[4] as int, r[5] as double, r[6] as double)
}
tsvFile.write(sb.toString(), "UTF-8")

// ── 4) Sonucu sun ───────────────────────────────────────────────────
double overallPct = totalCells > 0 ? (100.0 * totalPos / totalCells) : 0.0
def body = new StringBuilder()
body << "TMA ÇEKİRDEK BAZLI DIŞA AKTARIM\n"
body << "═══════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Çekirdek sayısı   : %d  (geçerli: %d)%n", cores.size(), validCores)
body << String.format(java.util.Locale.US, "Toplam hücre      : %,d%n", totalCells)
body << String.format(java.util.Locale.US, "Toplam pozitif    : %,d  (%.1f %%)%n", totalPos, overallPct)
if (!calibrated) body << "\n⚠️ Piksel kalibrasyonu yok → alan/yoğunluk sütunları 0 yazıldı.\n"
body << "\nTSV: " << tsvFile.getAbsolutePath() << "\n\n"
body << "Bu bir SAYIM/YOĞUNLUK tablosudur — klinik skor veya yorum DEĞİL.\n"
body << "(Kurowski 2024; Loughrey 2018; Porter 2023)\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("TMA çekirdek dışa aktarım", body.toString())
println "✓ TMA çekirdek özeti yazıldı: ${tsvFile.getAbsolutePath()}"
