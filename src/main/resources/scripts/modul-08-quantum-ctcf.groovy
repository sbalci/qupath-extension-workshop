/**
 * Modül 8 - QuANTUM-tarzı cTCF (Computational Tumor Cellular Fraction)
 * -----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Atölyenin en önemli betiği: yayınlanmış (Virchows Archiv 2025) QuANTUM
 * iş akışının basitleştirilmiş bir versiyonu. NSCLC slaytında **cTCF**
 * hesaplar — NGS için tümör hücre fraksiyonu.
 *
 * İŞ AKIŞI:
 *   1. TCR (Tumor-Containing Region) anotasyonunuz seçili olmalı
 *      (Pen/Polygon ile manuel çizilmiş)
 *   2. StarDist → TCR içindeki TÜM çekirdekler tespit edilir
 *   3. Nesne sınıflandırıcı (Tumor / Non-neoplastic) → her hücreyi sınıflar
 *      ← Önkoşul: önceden eğitilmiş nesne sınıflandırıcı
 *   4. cTCF = Tumor / (Tumor + Non-neoplastic) × 100
 *
 * ÖNKOŞULLAR:
 *   1. **StarDist eklentisi yüklü** (Modül 1 hazırlığı)
 *   2. **Model dosyası**: ~/.qupath/stardist/he_heavy_augment.pb
 *      (github.com/qupath/models)
 *   3. **Nesne sınıflandırıcı** projede: classifiers/object_classifiers/
 *      `tumor-vs-nonneoplastic-RT.json` adıyla
 *
 *   Nesne sınıflandırıcı yoksa betik size eğitim adımlarını gösterir.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathAnnotationObject

// ──────────────────────────────────────────────────────────────
// Modal olmayan pencere yardımcıları
//   - waitForConfirm    : modal hissi veren ama QuPath'i kilitlemeyen onay penceresi
//   - showResultWindow  : sonuç penceresi — açık kalır, QuPath kullanılmaya devam edilebilir
//
// İkisi de always-on-top açık başlar; kullanıcı kapatmadan slaytta dolaşabilir,
// parametre değiştirip betiği tekrar çalıştırabilir, sonuçları kopyalayabilir.
// ──────────────────────────────────────────────────────────────
def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// --- Atölye ayarları: eklenti yüklüyse oku, yoksa atölye varsayılanı kullanılır ---
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeD = { String k, double d -> (double) __wpCall('dbl', [String.class, double.class] as Class[], [k, d] as Object[], d) }

def stardistThreshold     = atolyeD('atolye.stardistThreshold', 0.5)
def stardistPixelSize     = atolyeD('atolye.stardistPixelSize', 0.5)
def stardistCellExpansion = atolyeD('atolye.stardistCellExpansion', 5.0)

def waitForConfirm = { String windowTitle, String windowBody ->
    if (isHeadless) {
        println "=== ${windowTitle} ===\n${windowBody}\n=================="
        return true
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def confirmed = new java.util.concurrent.atomic.AtomicBoolean(false)

    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle(windowTitle)
            stage.setAlwaysOnTop(true)

            def label = new javafx.scene.control.Label(windowBody)
            label.setWrapText(true)
            label.setStyle("-fx-font-size: 12px; -fx-padding: 8px;")

            def scrollPane = new javafx.scene.control.ScrollPane(label)
            scrollPane.setFitToWidth(true)

            def okBtn = new javafx.scene.control.Button("Çalıştır")
            okBtn.setDefaultButton(true)
            okBtn.setOnAction({
                confirmed.set(true)
                stage.close()
            })

            def cancelBtn = new javafx.scene.control.Button("İptal")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({
                confirmed.set(false)
                stage.close()
            })

            stage.setOnHidden({ latch.countDown() })

            def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut")
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)

            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, cancelBtn, okBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(scrollPane)
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 620, 460))
            stage.show()
        } catch (Throwable t) {
            // FX kurulumu başarısızsa modal'a geri dön
            confirmed.set(qupath.lib.gui.dialogs.Dialogs.showConfirmDialog(windowTitle, windowBody))
            latch.countDown()
        }
    }

    latch.await()
    return confirmed.get()
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

            stage.setScene(new javafx.scene.Scene(root, 760, 580))
            stage.show()
        } catch (Throwable t) {
            // FX başarısızsa modal'a geri dön — kayıp olmasın
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 1) Ön kontroller
// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir NSCLC H&E slaytı açın.")
    return
}

def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage("Proje açık değil", "Önce bir proje açın.")
    return
}

// StarDist eklentisi yüklü mü? (model indirmeden önce kontrol et)
try {
    Class.forName("qupath.ext.stardist.StarDist2D")
} catch (Throwable t) {
    Dialogs.showErrorMessage(
        "StarDist eklentisi yüklü değil",
        "qupath.ext.stardist.StarDist2D sınıfı bulunamadı.\n\n" +
        "Çözüm:\n" +
        "  1. QuPath → [Extensions → Manage extensions]\n" +
        "  2. StarDist'i kur\n" +
        "  3. QuPath'i yeniden başlat\n" +
        "  4. Bu betiği tekrar çalıştır"
    )
    return
}

// StarDist model dosyası
def userHome = System.getProperty('user.home')
def modelPath = "${userHome}/.qupath/stardist/he_heavy_augment.pb"
def modelFile = new File(modelPath)

if (!modelFile.exists()) {
    def browse = Dialogs.showConfirmDialog(
        "StarDist modeli bulunamadı",
        "Beklenen yol:\n  ${modelPath}\n\n" +
        "Model dosyası yok. El ile seçmek ister misiniz?\n" +
        "(Detaylar: Modül 1 'Yazılım kurulumu' → StarDist model bölümü)"
    )
    if (browse) {
        def selectedFile = Dialogs.promptForFile("StarDist Modelini Seç (.pb)", null, "StarDist Model (.pb)", "pb")
        if (selectedFile != null) {
            modelFile = selectedFile
            modelPath = modelFile.getAbsolutePath()
        } else {
            return
        }
    } else {
        return
    }
}

// Nesne sınıflandırıcı
def objClassifierName = 'tumor-vs-nonneoplastic-RT'
def availableObjClassifiers = project.getObjectClassifiers().getNames()
def hasClassifier = availableObjClassifiers.contains(objClassifierName)

// ──────────────────────────────────────────────────────────────
// 2) TCR anotasyonu kontrolü
// ──────────────────────────────────────────────────────────────
def selected = QP.getSelectedObject()
if (selected == null || !(selected instanceof PathAnnotationObject)) {
    Dialogs.showErrorMessage(
        "TCR anotasyonu seçili değil",
        "QuANTUM iş akışı bir **TCR (Tumor-Containing Region)** anotasyonu gerektirir.\n\n" +
        "Nasıl çizilir:\n" +
        "  1. [P] tuşu → Polygon aracı\n" +
        "  2. NSCLC slaytında **NGS için kullanılacak** dokunun çevresini çizin\n" +
        "     (diseksiyon öncesi iş akışını taklit eder)\n" +
        "  3. Bu manuel olarak yapılır — seçilen araştırma ROI'si\n" +
        "  4. Anotasyona 'TCR' sınıfı atayabilirsiniz (opsiyonel)\n" +
        "  5. TCR seçili iken bu betiği çalıştırın"
    )
    return
}
def tcr = selected

// ──────────────────────────────────────────────────────────────
// 3) Karşılama
// ──────────────────────────────────────────────────────────────
def workflowDesc = hasClassifier
    ? "  3️⃣ Nesne sınıflandırıcı '${objClassifierName}' → her hücre Tumor vs Non-neoplastic\n  4️⃣ cTCF = tümör / toplam × 100"
    : "  3️⃣ Nesne sınıflandırıcı yok — sadece StarDist tespiti çalışacak\n  4️⃣ Sınıflandırıcıyı eğitmeniz gerekecek (eğitim adımları sonda açıklanır)"

def expDesc = stardistCellExpansion > 0
    ? String.format(java.util.Locale.US, "Hücre genişletme: %.1f µm (tüm hücre), eşik: %.2f", stardistCellExpansion, stardistThreshold)
    : String.format(java.util.Locale.US, "Hücre genişletme yok (yalnız çekirdek), eşik: %.2f", stardistThreshold)
def devam = waitForConfirm(
    "Modül 8 - QuANTUM cTCF İş Akışı",
    "Bu betik QuANTUM yayınının iş akışını uygular:\n\n" +
    "  1️⃣ Seçili TCR içinde StarDist → tüm çekirdekleri tespit\n" +
    "  2️⃣ ${expDesc}\n" +
    "${workflowDesc}\n\n" +
    "Beklenen süre:\n" +
    "  • CPU: TCR boyutuna göre 1–5 dakika\n" +
    "  • GPU varsa: 5–30 saniye\n\n" +
    "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.\n\n" +
    "Hazırsanız Çalıştır düğmesine basın."
)
if (!devam) { println "İptal."; return }

// ──────────────────────────────────────────────────────────────
// 4) Adım 1 — TCR'yi etiketle
// ──────────────────────────────────────────────────────────────
println "─────────────────────────────────────"
println "Modül 8 - QuANTUM cTCF İş Akışı"
println "─────────────────────────────────────"
println "Adım 1/4: TCR hazırlığı..."

tcr.setName("TCR")
def viewer = qupath.lib.gui.QuPathGUI.getInstance()?.getViewer()
if (viewer != null) {
    javafx.application.Platform.runLater { viewer.repaint() }
}

// ──────────────────────────────────────────────────────────────
// 5) Adım 2 — StarDist
// ──────────────────────────────────────────────────────────────
println "Adım 2/4: StarDist H&E nükleus tespiti..."
println "  Model: ${modelPath}"

def t0 = System.currentTimeMillis()

// Önceki tespitleri temizle (TCR altındaki)
def oldDetections = tcr.getChildObjects().findAll { it.isDetection() }
if (!oldDetections.isEmpty()) {
    tcr.removePathObjects(oldDetections)
    println "  Önceki ${oldDetections.size()} tespit temizlendi."
}

def stardist = qupath.ext.stardist.StarDist2D
        .builder(modelPath)
        .threshold(stardistThreshold)
        .normalizePercentiles(1, 99)        // sabit — aşırı ayardan kaçınmak için açılmadı
        .pixelSize(stardistPixelSize)
        .cellExpansion(stardistCellExpansion)
        .constrainToParent(true)
        .measureShape()
        .measureIntensity()
        .build()

QP.selectObjects(tcr)
stardist.detectObjects(imageData, [tcr])
stardist.close()

def t1 = System.currentTimeMillis()
def step2Time = (t1 - t0) / 1000.0

def detectedCells = tcr.getChildObjects().findAll { it.isDetection() }
def totalNuclei = detectedCells.size()
println String.format(java.util.Locale.US, "  ✓ %,d nükleus tespit edildi (%.1f sn)", totalNuclei, step2Time)

if (totalNuclei == 0) {
    Dialogs.showErrorMessage(
        "Hiç nükleus tespit edilmedi",
        "TCR içinde StarDist 0 hücre buldu.\n" +
        "Olası nedenler:\n" +
        "  • Eşik çok yüksek (0.5) — düşürmeyi deneyin (0.3)\n" +
        "  • TCR çok küçük\n" +
        "  • Slayt boyama H&E için tipik aralıkta değil"
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 6) Adım 3 — Nesne sınıflandırıcı (varsa)
// ──────────────────────────────────────────────────────────────
def tumorCount = 0
def nonNeoCount = 0
def ignoreCount = 0
def step3Time = 0.0

if (hasClassifier) {
    println "Adım 3/4: Tümör vs Non-neoplastic sınıflandırma..."

    def t2 = System.currentTimeMillis()
    // Sınıflandırmayı yalnızca TCR içinde üretilen hücrelere uygula.
    QP.selectObjects(detectedCells)
    QP.runObjectClassifier(objClassifierName)
    step3Time = (System.currentTimeMillis() - t2) / 1000.0

    detectedCells.each { c ->
        def cls = c.getPathClass()?.getName()?.toLowerCase(java.util.Locale.ROOT) ?: ""
        if (cls.contains("tumor"))      tumorCount++
        else if (cls.contains("ignore")) ignoreCount++
        else                              nonNeoCount++   // Non-neoplastic / Other / vb.
    }
    println String.format(java.util.Locale.US, "  ✓ Tumor: %d  |  Non-neoplastic: %d  |  Ignore: %d  (%.1f sn)",
        tumorCount, nonNeoCount, ignoreCount, step3Time)
} else {
    println "Adım 3/4: Atlandı — nesne sınıflandırıcı yok."
}

// ──────────────────────────────────────────────────────────────
// 7) Adım 4 — cTCF
// ──────────────────────────────────────────────────────────────
def cTCF = 0.0
def cTCFmetin = "Sınıflandırıcı yok — cTCF hesaplanamadı"

if (hasClassifier) {
    def validTotal = tumorCount + nonNeoCount  // Ignore'u dışla
    cTCF = validTotal > 0 ? 100.0 * tumorCount / validTotal : 0.0
    cTCFmetin = String.format(java.util.Locale.US, "%.1f%%", cTCF)
}

// Alan hesabı
def cal = imageData.getServer().getPixelCalibration()
def pixelWidth  = cal.getPixelWidthMicrons()
def pixelHeight = cal.getPixelHeightMicrons()
def roi = tcr.getROI()
def tcrAreaMm2 = roi != null
    ? (roi.getArea() * pixelWidth * pixelHeight) / 1_000_000.0
    : 0.0

def totalElapsed = (System.currentTimeMillis() - t0) / 1000.0

// ──────────────────────────────────────────────────────────────
// 7b) cTCF'yi TCR anotasyonunun ölçüm listesine yaz → Modül 9 dışa aktarır.
//    cTCF skaler değeri ekranda gösteriliyor ama hiçbir nesneye yazılmıyordu;
//    bu nedenle Modül 9 TSV'sinde görünmüyordu. TCR anotasyonu zaten var, ona yazıyoruz.
// ──────────────────────────────────────────────────────────────
tcr.measurements['TCR alanı (mm2)'] = tcrAreaMm2
if (hasClassifier) {
    tcr.measurements['cTCF (%)']            = cTCF
    tcr.measurements['Tümör hücre sayısı']  = tumorCount as double
    tcr.measurements['Non-neoplastik sayı'] = nonNeoCount as double
}
QP.fireHierarchyUpdate()

// ──────────────────────────────────────────────────────────────
// 8) Sonucu sun
// ──────────────────────────────────────────────────────────────
def egitimNot = ""
if (!hasClassifier) {
    egitimNot = "\n\n⚠️ ÖNEMLİ: Nesne sınıflandırıcı '${objClassifierName}' projenizde yok.\n" +
                "cTCF hesaplanamadı — sınıflandırıcıyı şu adımlarla eğitin:\n\n" +
                "  1. StarDist sonuçları üzerinde ~5-10 hücreyi 'Tumor' sınıfına atayın\n" +
                "  2. Aynı şekilde ~5-10 hücreyi 'Non-neoplastic'e atayın\n" +
                "  3. [Classify → Object classification → Train object classifier]\n" +
                "  4. Random Tree seçin → Train → Save as '${objClassifierName}'\n" +
                "  5. Bu betiği tekrar çalıştırın → cTCF hesaplanacak"
}

showResultWindow(
    "QuANTUM Tamamlandı 🧪",
    String.format(java.util.Locale.US, 
        "QuANTUM-tarzı cTCF iş akışı bitti.\n\n" +
        "🎯 TCR ve Sayım\n" +
        "────────────────\n" +
        "  TCR alanı            : %.2f mm²\n" +
        "  Toplam nükleus        : %,d\n" +
        "  Tumor sınıfı          : %,d\n" +
        "  Non-neoplastic sınıfı : %,d\n" +
        "  Ignore                : %,d\n\n" +
        "🧬 ANA SONUÇ: cTCF = %s\n" +
        "════════════════════════════\n\n" +
        "⏱ Toplam süre: %.1f sn (StarDist %.1f sn, sınıflandırma %.1f sn)\n\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir." +
        "%s",
        tcrAreaMm2, totalNuclei, tumorCount, nonNeoCount, ignoreCount,
        cTCFmetin,
        totalElapsed, step2Time, step3Time, egitimNot
    )
)

println "─────────────────────────────────────"
println String.format(java.util.Locale.US, "QuANTUM Tamamlandı:")
println String.format(java.util.Locale.US, "  TCR: %.2f mm²  |  Nükleus: %,d", tcrAreaMm2, totalNuclei)
if (hasClassifier) {
    println String.format(java.util.Locale.US, "  Tumor: %,d  |  Non-neo: %,d  |  cTCF: %s", tumorCount, nonNeoCount, cTCFmetin)
}
println String.format(java.util.Locale.US, "  Toplam süre: %.1f sn", totalElapsed)
println "─────────────────────────────────────"
