/**
 * Yardımcı - Çapraz-tip En Yakın Komşu Mesafesi
 * ------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Kullanıcının seçtiği iki farklı tespit sınıfı (A ve B) arasında
 *   çapraz-tip en yakın komşu (cross-type nearest-neighbour) mesafesini
 *   hesaplar. Her A hücresi için B havuzundaki en yakın B hücresine olan
 *   merkez-merkez mesafesi (µm) hesaplanır ve A hücresine ölçüm olarak yazılır.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Farklı sınıflardaki hücreler ARASINDA çapraz mesafe ölçer.
 *     Ör. "tümör hücresinin en yakın CD8+ T hücresine uzaklığı".
 *   • Bir mesafe/uzamsal dağılım ÖLÇÜTÜDÜR — klinik skor, eşik veya
 *     immün yeterlilik yorumu DEĞİL.
 *   • Tespit YAPMAZ; Hücre Tespiti / Nükleer Boya / Membran Boya / Sitoplazmik Boya /
 *     Tümör İçi Ki-67 modüllerindeki tespitler sınıflı olmalıdır.
 *
 * KULLANIM:
 *   1. Sınıflı hücre tespitleri olan, kalibre (µm) bir slayt açın.
 *   2. [Extensions → Atölye → Yardımcılar → Uzamsal analiz →
 *      Çapraz-tip En Yakın Komşu]
 *   3. Açılan iletişimlerde A ve B sınıflarını seçin; yarıçap girin.
 *
 * ÇIKTI:
 *   • Her A hücresi: "<A>→<B> en yakın komşu (µm)" ölçümü
 *     (Veri Dışa Aktarma modülü ile dışa aktarılır; ölçüm haritasında görselleştirilebilir)
 *   • Kilitli özet anotasyonu: ortalama / SS / medyan / MAD / minimum / maksimum
 *     çapraz NN mesafesi, N, yarıçap içindeki A hücresi sayısı ve oranı
 *   • Sonuç penceresinde özet tablo
 *
 * YÖNTEM / KAYNAK:
 *   • Uzamsal hash ızgarası ile çok halkali en yakın komşu — aynı
 *     yardimci-nn-mesafe.groovy çekirdeği, yalnız B havuzu üzerinde
 *     indeks kurulur; her A için B'de arama yapılır.
 *   • Summers MA et al. (2022), Cell Rep Methods — uzamsal komşuluk ve
 *     mesafe ölçütleri doku analizinde. doi:10.1016/j.crmeth.2022.100348
 *   • Tanımlayıcı takımı (ort/SS/medyan/MAD/min/maks) için esin: DANEELpath
 *     "Distance Descriptors Cells NN" — Vieco-Martí ve ark. (2026), Sci Rep 16:6162.
 *     doi:10.1038/s41598-026-37134-5 (bağımsız uygulama; bkz. DANEELpath eki).
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/capraz-nn-mesafe')

// ── showResultWindow (VERBATIM from yardimci-kesisim-alani.groovy) ──────────
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

            stage.setScene(new javafx.scene.Scene(root, 720, 520))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── 1) Ön kontroller ────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce sınıflı hücre tespitleri olan bir slayt açın.")
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok",
        "Slaytta piksel boyutu (µm) tanımlı değil.\n\n" +
        "Piksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu).")
    return
}
// Anizotropik piksellerde (pw ≠ ph) Y ekseni ayrı ölçeklenmeli; yükseklik tanımsızsa genişliği kullan.
if (!(ph > 0)) ph = pw

// ── 2) Sınıf listesini topla ─────────────────────────────────────────────────
def allDetections = QP.getDetectionObjects().findAll { it.getROI() != null && it.getPathClass() != null }
def classNames = allDetections.collect { it.getPathClass().toString() }.unique().sort()

if (classNames.size() < 2) {
    def msg = "Çapraz NN için en az İKİ farklı sınıfta tespit gerekir.\n" +
              "Mevcut sınıf(lar): " + (classNames.join(', ') ?: '(yok)') + "\n\n" +
              "Önce Hücre Tespiti / Nükleer Boya / Membran Boya / Sitoplazmik Boya / " +
              "Tümör İçi Ki-67 modülleriyle sınıflı hücre tespiti yapın."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Yetersiz sınıf", msg)
    return
}

// ── 3) A ve B sınıfı seçimi ─────────────────────────────────────────────────
String classA
String classB
double radiusUm = 50.0

if (isHeadless) {
    classA   = prefs.get('classA', '')
    classB   = prefs.get('classB', '')
    radiusUm = prefs.getDouble('radiusUm', 50.0)
    if (!classNames.contains(classA) || !classNames.contains(classB)) {
        println "Headless: 'classA'/'classB' tercihleri ayarlı değil ya da bu görüntüde yok.\n" +
                "Önce arayüzden bir kez çalıştırın. Mevcut sınıflar: ${classNames.join(', ')}"
        return
    }
} else {
    def defA = classNames.contains(prefs.get('classA', '')) ? prefs.get('classA', '') : classNames[0]
    classA = Dialogs.showChoiceDialog("Çapraz NN — A sınıfı",
        "Hangi sınıfın hücreleri için mesafe ölçülsün? (A — en yakın B aranır)", classNames, defA)
    if (classA == null) { println "İptal edildi."; return }

    def bOpts = classNames.findAll { it != classA }
    def defB = bOpts.contains(prefs.get('classB', '')) ? prefs.get('classB', '') : bOpts[0]
    classB = Dialogs.showChoiceDialog("Çapraz NN — B sınıfı",
        "Hedef sınıf nedir? (B — bu havuzda en yakın komşu aranır)", bOpts, defB)
    if (classB == null) { println "İptal edildi."; return }

    String radStr = Dialogs.showInputDialog("Yakınlık yarıçapı (µm)",
        "Kaç µm yarıçap içindeki A hücreleri sayılsın?\n(Varsayılan: 50 µm)", "50")
    if (radStr == null) { println "İptal edildi."; return }
    try { radiusUm = Double.parseDouble(radStr.trim().replace(',', '.')) }
    catch (Throwable ig) {
        Dialogs.showErrorMessage("Geçersiz yarıçap",
            "Yarıçap bir sayı olmalı (ör. 50 ya da 75.5). Girilen: '${radStr}'.")
        return
    }
    if (!(radiusUm > 0)) radiusUm = 50.0
}

if (classA == classB) {
    Dialogs.showErrorMessage("Aynı sınıf", "A ve B sınıfı farklı olmalı.")
    return
}

prefs.put('classA', classA)
prefs.put('classB', classB)
prefs.putDouble('radiusUm', radiusUm)
try { prefs.flush() } catch (Throwable ig) {}

// ── 4) Havuzları böl + kontrol ───────────────────────────────────────────────
def poolA = QP.getDetectionObjects().findAll { it.getROI() != null && it.getPathClass()?.toString() == classA }
def poolB = QP.getDetectionObjects().findAll { it.getROI() != null && it.getPathClass()?.toString() == classB }

if (poolA.isEmpty() || poolB.isEmpty()) {
    def msg = String.format(java.util.Locale.US,
        "Seçilen sınıflarda tespit yok (A='%s': %d, B='%s': %d).",
        classA, poolA.size(), classB, poolB.size())
    if (isHeadless) println msg else Dialogs.showErrorMessage("Tespit yok", msg)
    return
}

// ── 5) B üzerinde uzamsal hash ızgarası kur ──────────────────────────────────
double bucketUm  = Math.max(radiusUm, 50.0)   // kova kenarı ≥ 50 µm (µm-uzayı ızgara)
int nb = poolB.size()
double[] bxs = new double[nb]
double[] bys = new double[nb]
// Merkezleri µm'ye çevir: X genişlikle, Y yükseklikle ölçeklenir.
poolB.eachWithIndex { c, i -> def r = c.getROI(); bxs[i] = r.getCentroidX() * pw; bys[i] = r.getCentroidY() * ph }

def bucketsB = new HashMap<Long, List<Integer>>()
int[] bcol = new int[nb]
int[] brow = new int[nb]
def keyOf = { int c, int r -> (((long) c) << 32) ^ (r & 0xffffffffL) }
for (int i = 0; i < nb; i++) {
    int c = (int) Math.floor(bxs[i] / bucketUm)
    int r = (int) Math.floor(bys[i] / bucketUm)
    bcol[i] = c; brow[i] = r
    bucketsB.computeIfAbsent(keyOf(c, r), { k -> new ArrayList<Integer>() }).add(i)
}

// ── 6) Her A hücresi için halka halka B'de NN ara ───────────────────────────
println String.format(java.util.Locale.US,
    "Çapraz NN hesaplanıyor: A='%s' (%,d) → B='%s' (%,d), yarıçap=%.0f µm...",
    classA, poolA.size(), classB, poolB.size(), radiusUm)

String measName  = "${classA}→${classB} en yakın komşu (µm)"
double radiusUm2 = radiusUm * radiusUm   // µm² (mesafeler µm-uzayında hesaplanır)

def nnUm      = []
int withinRad = 0
int na        = poolA.size()

for (int i = 0; i < na; i++) {
    def roi  = poolA[i].getROI()
    double ax = roi.getCentroidX() * pw
    double ay = roi.getCentroidY() * ph
    int ac = (int) Math.floor(ax / bucketUm)
    int ar = (int) Math.floor(ay / bucketUm)

    double best = Double.POSITIVE_INFINITY
    int ring = 0
    while (true) {
        for (int dc = -ring; dc <= ring; dc++) {
            for (int dr = -ring; dr <= ring; dr++) {
                if (ring > 0 && Math.max(Math.abs(dc), Math.abs(dr)) != ring) continue
                def lst = bucketsB.get(keyOf(ac + dc, ar + dr))
                if (lst == null) continue
                for (int j : lst) {
                    double dx = ax - bxs[j], dy = ay - bys[j]
                    double d = dx * dx + dy * dy
                    if (d < best) best = d
                }
            }
        }
        double guaranteed = ring * bucketUm
        if (best < Double.POSITIVE_INFINITY && guaranteed * guaranteed >= best) break
        ring++
        if (ring > 200) break
    }

    if (best < Double.POSITIVE_INFINITY) {
        double distUm = Math.sqrt(best)
        poolA[i].measurements[measName] = distUm
        nnUm << distUm
        if (best <= radiusUm2) withinRad++
    }
}

// ── 7) Özet istatistikler ────────────────────────────────────────────────────
double meanNN = 0.0, sdNN = 0.0, medianNN = 0.0, madNN = 0.0, minNN = 0.0, maxNN = 0.0
if (!nnUm.isEmpty()) {
    meanNN = nnUm.sum() / nnUm.size()
    def sorted = nnUm.sort(false)
    int m = sorted.size()
    medianNN = (m % 2 == 1) ? sorted[(int)(m / 2)] : (sorted[m / 2 - 1] + sorted[m / 2]) / 2.0
    minNN    = sorted[0]
    maxNN    = sorted[m - 1]
    if (m > 1) {
        double ss = 0.0
        for (v in nnUm) { double dv = (v as double) - meanNN; ss += dv * dv }
        sdNN = Math.sqrt(ss / (m - 1))   // örneklem standart sapması (N−1)
    }
    // Robust MAD = medyan(|xᵢ − medyan|)
    def dev = nnUm.collect { Math.abs((it as double) - medianNN) }.sort(false)
    int md = dev.size()
    madNN = (md % 2 == 1) ? dev[(int)(md / 2)] : (dev[md / 2 - 1] + dev[md / 2]) / 2.0
}
double withinPct = (na > 0) ? 100.0 * withinRad / na : 0.0

// ── 8) Kilitli özet anotasyonu ───────────────────────────────────────────────
String summaryName = "Çapraz NN Özet (${classA}→${classB})"
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == summaryName }, false)
def srv = imageData.getServer()
def summary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, srv.getWidth(), srv.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
summary.setName(summaryName)
summary.measurements["A (${classA}) hücre sayısı"]         = na as double
summary.measurements["B (${classB}) hücre sayısı"]         = nb as double
summary.measurements['Ortalama çapraz NN (µm)']             = meanNN
summary.measurements['SS çapraz NN (µm)']                   = sdNN
summary.measurements['Medyan çapraz NN (µm)']               = medianNN
summary.measurements['MAD çapraz NN (µm)']                  = madNN
summary.measurements['Minimum çapraz NN (µm)']              = minNN
summary.measurements['Maksimum çapraz NN (µm)']             = maxNN
summary.measurements["A hücresi yarıçap içinde (adet)"]    = withinRad as double
summary.measurements["A hücresi yarıçap içinde (%)"]       = withinPct
summary.measurements['Yarıçap (µm)']                        = radiusUm
summary.setLocked(true)
QP.addObjects([summary])
QP.fireHierarchyUpdate()

// ── 9) Sonucu sun ────────────────────────────────────────────────────────────
def body = new StringBuilder()
body << "ÇAPRAZ-TİP EN YAKIN KOMŞU MESAFESİ\n"
body << "═══════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "A sınıfı (kaynak)  : %s (%,d hücre)%n", classA, na)
body << String.format(java.util.Locale.US, "B sınıfı (hedef)   : %s (%,d hücre)%n", classB, nb)
body << String.format(java.util.Locale.US, "Yarıçap eşiği      : %.0f µm%n", radiusUm)
body << "\n"
body << String.format(java.util.Locale.US, "Ortalama NN        : %.1f µm%n", meanNN)
body << String.format(java.util.Locale.US, "SS (std) NN        : %.1f µm%n", sdNN)
body << String.format(java.util.Locale.US, "Medyan NN          : %.1f µm%n", medianNN)
body << String.format(java.util.Locale.US, "MAD NN             : %.1f µm%n", madNN)
body << String.format(java.util.Locale.US, "Minimum NN         : %.1f µm%n", minNN)
body << String.format(java.util.Locale.US, "Maksimum NN        : %.1f µm%n", maxNN)
body << "───────────────────────────────────────────\n"
body << String.format(java.util.Locale.US,
    "Yarıçap içinde A   : %,d / %,d  (%.1f %%)%n", withinRad, na, withinPct)
body << "\n"
body << "Her A hücresine '${measName}'\n"
body << "ölçümü yazıldı (Veri Dışa Aktarma modülü ile dışa aktarılır).\n"
body << "Ölçüme göre renklendirmek için: Measure → Show measurement maps.\n\n"
body << "Not: Mesafe merkez-merkez piksel mesafesi × µm/px kalibrasyon faktörüdür.\n"
body << "Bu bir UZAMSAL MESAFE ölçümüdür — klinik skor veya yorum DEĞİL.\n"
body << "(Summers 2022; Bankhead 2017)\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Çapraz NN mesafesi (${classA}→${classB})", body.toString())
println String.format(java.util.Locale.US,
    "✓ Çapraz NN yazıldı: '%s'→'%s', ortalama %.1f µm, medyan %.1f µm, yarıçap içinde %,d/%,d (%.1f%%).",
    classA, classB, meanNN, medianNN, withinRad, na, withinPct)
