/**
 * Yardımcı - Eşikleri ayarla
 * ---------------------------
 * Modül 3, 3b, 4, 5 veya 7'yi varsayılan eşiklerle çalıştırdıktan sonra
 * sonuç beklediğiniz gibi değilse: bu betik **hücre tespitini yeniden
 * çalıştırmadan** sadece bin eşiklerini değiştirip yeniden sınıflandırma yapar.
 *
 * NE İŞE YARAR?
 *   • Mevcut tespitlerin hangi ölçüm sütununda bin'lendiğini otomatik bulur:
 *       - Membrane: DAB OD mean      → Modül 4 (HER2)
 *       - Nucleus: DAB OD mean       → Modül 3, 3b, 7 (Ki-67 / ER / PR)
 *       - Cytoplasm: DAB OD mean     → Modül 5 (CD68)
 *   • Üç eşik (1+, 2+, 3+) için form gösterir, mevcut değerleri önceden yazar
 *   • Yeni değerlerle `setCellIntensityClassifications` çalıştırır
 *   • Negative / 1+ / 2+ / 3+ sayımı + yüzdeler + H-score'u yeniden hesaplar
 *
 * KULLANIM:
 *   1. Önce Modül 3/3b/4/5/7'den birini çalıştırın (hücre tespiti yapılır)
 *   2. Hücreleri içeren anotasyonu seçili tutun
 *   3. [Extensions → Atölye → Yardımcı - Eşikleri ayarla]
 *   4. Form açılır, eşikleri değiştirip "Yeniden hesapla" → yeni özet penceresi
 *   5. Memnun değilseniz tekrar açın, başka eşik deneyin (saniyeler içinde)
 *
 * NE YAPMAZ:
 *   • Cellpose / Watershed tespit adımını yeniden çalıştırmaz (hızlı tutmak için)
 *   • Hücre genişletme (cell expansion) gibi tespit parametrelerini değiştiremez
 *     — onun için modülün kendisini yeni parametrelerle yeniden çalıştırın
 *   • Piksel-bazlı (pixel-wise) H-score eşiklerini değiştirmez — o, modül 4'ün
 *     pikselsel pass'ini gerektirir
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// --- Atölye ayarları: eklenti yüklüyse oku, yoksa atölye varsayılanı kullanılır ---
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeD = { String k, double  d -> (double)  __wpCall('dbl',  [String.class, double.class]  as Class[], [k, d] as Object[], d) }
def atolyeS = { String k, String  d -> (String)  __wpCall('str',  [String.class, String.class]  as Class[], [k, d] as Object[], d) }
def atolyeI = { String k, int     d -> (int)     __wpCall('intg', [String.class, int.class]     as Class[], [k, d] as Object[], d) }
def atolyeB = { String k, boolean d -> (boolean) __wpCall('bool', [String.class, boolean.class] as Class[], [k, d] as Object[], d) }

// ──────────────────────────────────────────────────────────────
// Form penceresi: üç eşik girişli, varsayılan-değer önceden yazılı
// ──────────────────────────────────────────────────────────────
def showThresholdForm = { String title, String body, double t1, double t2, double t3, String k1 = '', String k2 = '', String k3 = '' ->
    if (isHeadless) {
        println "=== ${title} === ${body} (headless: değerler değişmedi)"
        return [t1: t1, t2: t2, t3: t3]
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def result = new java.util.concurrent.atomic.AtomicReference<Map>(null)

    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle(title)
            stage.setAlwaysOnTop(true)

            def header = new javafx.scene.control.Label(body)
            header.setWrapText(true)
            header.setStyle("-fx-font-size: 12px;")

            // Üç TextField, sayısal validasyonlu
            def f1 = new javafx.scene.control.TextField(String.format(java.util.Locale.US, "%.3f", t1))
            def f2 = new javafx.scene.control.TextField(String.format(java.util.Locale.US, "%.3f", t2))
            def f3 = new javafx.scene.control.TextField(String.format(java.util.Locale.US, "%.3f", t3))
            [f1, f2, f3].each { it.setPrefColumnCount(8) }

            def grid = new javafx.scene.layout.GridPane()
            grid.setHgap(10); grid.setVgap(8)
            grid.setPadding(new javafx.geometry.Insets(12, 8, 12, 8))
            grid.add(new javafx.scene.control.Label("1+ eşiği (OD):"), 0, 0); grid.add(f1, 1, 0)
            grid.add(new javafx.scene.control.Label("2+ eşiği (OD):"), 0, 1); grid.add(f2, 1, 1)
            grid.add(new javafx.scene.control.Label("3+ eşiği (OD):"), 0, 2); grid.add(f3, 1, 2)

            def info = new javafx.scene.control.Label(
                "Kural: 1+ < 2+ < 3+ olmalı. Üç değer ondalık nokta ile (.) girilir.\n" +
                "Boş alanda ENTER ile varsayılana dönmek için 'Sıfırla' düğmesini kullanın."
            )
            info.setWrapText(true)
            info.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;")

            def root = new javafx.scene.layout.VBox(10, header, grid, info)
            root.setPadding(new javafx.geometry.Insets(12))

            // Düğmeler
            def runBtn = new javafx.scene.control.Button("Yeniden hesapla")
            runBtn.setDefaultButton(true)
            runBtn.setOnAction({
                try {
                    def v1 = Double.parseDouble(f1.getText().trim().replace(',', '.'))
                    def v2 = Double.parseDouble(f2.getText().trim().replace(',', '.'))
                    def v3 = Double.parseDouble(f3.getText().trim().replace(',', '.'))
                    if (!(v1 < v2 && v2 < v3)) {
                        Dialogs.showErrorMessage("Sıralama hatası",
                            "Eşikler 1+ < 2+ < 3+ olmalı. Girdi: ${v1} / ${v2} / ${v3}")
                        return
                    }
                    result.set([t1: v1, t2: v2, t3: v3])
                    stage.close()
                } catch (NumberFormatException nfe) {
                    Dialogs.showErrorMessage("Sayı formatı",
                        "Eşikler ondalık sayı olmalı. Örnek: 0.15 / 0.40 / 0.70")
                }
            })

            def resetBtn = new javafx.scene.control.Button("Sıfırla")
            resetBtn.setOnAction({
                f1.setText(String.format(java.util.Locale.US, "%.3f", t1))
                f2.setText(String.format(java.util.Locale.US, "%.3f", t2))
                f3.setText(String.format(java.util.Locale.US, "%.3f", t3))
            })

            def cancelBtn = new javafx.scene.control.Button("Kapat")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({ result.set(null); stage.close() })

            def saveDefaultsBtn = new javafx.scene.control.Button("Bu eşikleri varsayılan yap")
            saveDefaultsBtn.setOnAction({
                try {
                    def v1 = Double.parseDouble(f1.getText().trim().replace(',', '.'))
                    def v2 = Double.parseDouble(f2.getText().trim().replace(',', '.'))
                    def v3 = Double.parseDouble(f3.getText().trim().replace(',', '.'))
                    if (!(v1 < v2 && v2 < v3)) {
                        Dialogs.showErrorMessage("Sıralama hatası",
                            "Kaydetmeden önce: eşikler 1+ < 2+ < 3+ olmalı. Girdi: ${v1} / ${v2} / ${v3}")
                        return
                    }
                    def wpCls = __wpClass()
                    if (wpCls != null && k1 && k2 && k3) {
                        [[k1, v1], [k2, v2], [k3, v3]].each { pair ->
                            try { wpCls.getMethod('setDbl', String.class, double.class).invoke(null, pair[0], (double) pair[1]) } catch (Throwable ignored) {}
                        }
                        Dialogs.showMessageDialog("Varsayılan kaydedildi",
                            "Yeni varsayılan eşikler kaydedildi:\n  1+: ${v1}\n  2+: ${v2}\n  3+: ${v3}")
                    } else {
                        Dialogs.showWarningNotification("Kaydedilemedi",
                            "Atölye eklentisi yüklü değil ya da anahtar yok — varsayılan kaydedilemedi.")
                    }
                } catch (NumberFormatException nfe) {
                    Dialogs.showErrorMessage("Sayı formatı", "Eşikler ondalık sayı olmalı.")
                }
            })

            stage.setOnHidden({ latch.countDown() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def buttons = new javafx.scene.layout.HBox(10, resetBtn, saveDefaultsBtn, spacer, cancelBtn, runBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))

            def container = new javafx.scene.layout.BorderPane()
            container.setCenter(root)
            container.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(container, 560, 380))
            stage.show()
        } catch (Throwable t) {
            result.set(null)
            latch.countDown()
        }
    }

    latch.await()
    return result.get()
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
            def buttons = new javafx.scene.layout.HBox(10, spacer, copyBtn, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(textArea)
            root.setBottom(buttons)

            stage.setScene(new javafx.scene.Scene(root, 660, 420))
            stage.show()
        } catch (Throwable t) {
            qupath.lib.gui.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 1) Ön kontrol: açık görüntü + seçili anotasyon + tespit varlığı
// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir slayt açın.")
    return
}

def selected = QP.getSelectedObject()
if (selected == null || !selected.isAnnotation()) {
    Dialogs.showErrorMessage(
        "Anotasyon seçili değil",
        "Eşik ayarlamak için hücre tespiti içeren bir anotasyonu seçin.\n" +
        "Önce Modül 3 / 3b / 4 / 5 / 7'den birini çalıştırmış olmalısınız."
    )
    return
}

def cells = selected.getChildObjects().findAll { it.isDetection() }
if (cells.isEmpty()) {
    Dialogs.showErrorMessage(
        "Tespit yok",
        "Seçili anotasyonda hücre tespiti bulunmuyor.\n" +
        "Önce Modül 3 / 3b / 4 / 5 / 7'den birini çalıştırın, sonra bu yardımcıyı kullanın."
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 2) Ölçüm sütununu otomatik bul (hangi modül çıktısı?)
// ──────────────────────────────────────────────────────────────
def candidateColumns = [
    "Membrane: DAB OD mean",   // Modül 4 (HER2)
    "Nucleus: DAB OD mean",    // Modül 3, 3b, 7 (Ki-67 / ER / PR)
    "Cytoplasm: DAB OD mean"   // Modül 5 (CD68)
]
def sampleCell = cells[0]
def measKeys = sampleCell.getMeasurementList().getNames()

def measurement = candidateColumns.find { col -> measKeys.contains(col) }
if (measurement == null) {
    Dialogs.showErrorMessage(
        "Uyumlu ölçüm bulunamadı",
        "Hücrelerde tanınan bir ölçüm sütunu yok:\n" +
        "  • Membrane: DAB OD mean (M4)\n" +
        "  • Nucleus: DAB OD mean (M3 / M3b / M7)\n" +
        "  • Cytoplasm: DAB OD mean (M5)\n\n" +
        "Modül scriptlerinden birini önce çalıştırın, ardından bu yardımcıyı tekrar deneyin."
    )
    return
}

// Modüle özgü varsayılan eşikler (her zaman önce mevcut sınıf etiketlerinden
// tahmin etmeyi denemeyiz çünkü etiketler aynı; varsayılanlar modüldeki ile aynı)
def defaultsFor = [
    "Membrane: DAB OD mean":  [atolyeD("atolye.membrane1", 0.15), atolyeD("atolye.membrane2", 0.40), atolyeD("atolye.membrane3", 0.70)],   // M4
    "Nucleus: DAB OD mean":   [atolyeD("atolye.nuclear1", 0.20), atolyeD("atolye.nuclear2", 0.40), atolyeD("atolye.nuclear3", 0.60)],   // M3 / M3b / M7
    "Cytoplasm: DAB OD mean": [atolyeD("atolye.cyto1", 0.10), atolyeD("atolye.cyto2", 0.20), atolyeD("atolye.cyto3", 0.35)]    // M5
]
def defaults = defaultsFor[measurement]

// Mevcut bin etiketlerinin sayımı (referans olarak göstermek için)
def existingCounts = [Negative: 0, "1+": 0, "2+": 0, "3+": 0, other: 0]
cells.each { c ->
    def cls = c.getPathClass()?.getName() ?: ""
    if (cls.contains("3+"))      existingCounts["3+"]++
    else if (cls.contains("2+")) existingCounts["2+"]++
    else if (cls.contains("1+")) existingCounts["1+"]++
    else if (cls.toLowerCase().contains("negative") || cls.isEmpty()) existingCounts.Negative++
    else                          existingCounts.other++
}

def moduleHint = [
    "Membrane: DAB OD mean":  "Modül 4 (HER2)",
    "Nucleus: DAB OD mean":   "Modül 3 / 3b / 7 (Ki-67 / ER / PR)",
    "Cytoplasm: DAB OD mean": "Modül 5 (CD68)"
][measurement]

// ──────────────────────────────────────────────────────────────
// 3) Form göster (mevcut/varsayılan eşikler önceden yazılı)
// ──────────────────────────────────────────────────────────────
def formBody = String.format(
    "Tespit edilen: %s\n" +
    "Ölçüm sütunu  : %s\n" +
    "Toplam hücre  : %,d\n\n" +
    "Mevcut grup dağılımı:\n" +
    "  Negative : %,d   (%.1f%%)\n" +
    "  1+       : %,d   (%.1f%%)\n" +
    "  2+       : %,d   (%.1f%%)\n" +
    "  3+       : %,d   (%.1f%%)\n",
    moduleHint, measurement, cells.size(),
    existingCounts.Negative, 100.0 * existingCounts.Negative / cells.size(),
    existingCounts["1+"],    100.0 * existingCounts["1+"] / cells.size(),
    existingCounts["2+"],    100.0 * existingCounts["2+"] / cells.size(),
    existingCounts["3+"],    100.0 * existingCounts["3+"] / cells.size()
)

def prefKeysFor = [
    "Membrane: DAB OD mean":  ["atolye.membrane1", "atolye.membrane2", "atolye.membrane3"],
    "Nucleus: DAB OD mean":   ["atolye.nuclear1",  "atolye.nuclear2",  "atolye.nuclear3"],
    "Cytoplasm: DAB OD mean": ["atolye.cyto1",     "atolye.cyto2",     "atolye.cyto3"]
]
def prefKeys = prefKeysFor[measurement] ?: ['', '', '']
def newThr = showThresholdForm(
    "Eşikleri ayarla — ${moduleHint}",
    formBody,
    (double) defaults[0],
    (double) defaults[1],
    (double) defaults[2],
    prefKeys[0], prefKeys[1], prefKeys[2]
)
if (newThr == null) {
    println "Kullanıcı kapattı."
    return
}

// ──────────────────────────────────────────────────────────────
// 4) Yeniden sınıflandır — yalnızca seçili anotasyonun altındaki hücreler
//    QP.setCellIntensityClassifications() varsayılan olarak slayttaki TÜM
//    hücreleri re-bin'ler. Birden fazla anotasyonda hücreniz varsa diğer
//    anotasyonların etiketleri de değişir. Aşağıdaki form sadece seçili
//    anotasyonun child detection'larına uygular.
// ──────────────────────────────────────────────────────────────
def t0 = System.currentTimeMillis()
QP.setIntensityClassifications(cells, measurement, newThr.t1, newThr.t2, newThr.t3)
QP.fireHierarchyUpdate()
def elapsed = (System.currentTimeMillis() - t0) / 1000.0

def updatedCells = selected.getChildObjects().findAll { it.isDetection() }
def n0 = 0, n1 = 0, n2 = 0, n3 = 0
updatedCells.each { c ->
    def cls = c.getPathClass()?.getName() ?: ""
    if (cls.contains("3+"))      n3++
    else if (cls.contains("2+")) n2++
    else if (cls.contains("1+")) n1++
    else                          n0++
}
def total = updatedCells.size()
def pct = { c -> total > 0 ? 100.0 * c / total : 0.0 }
def pct0 = pct(n0), pct1 = pct(n1), pct2 = pct(n2), pct3 = pct(n3)
def hScore = pct1 + 2.0 * pct2 + 3.0 * pct3

// Bayatlamış piksel-bazlı H-score ölçümünü temizle (sadece M4 için var olur).
// Yeni hücre eşikleri uygulandıktan sonra annotation'da kalan "Pixelwise H-score"
// ve "H-score-px: ..." ölçümleri eski eşiklerle hesaplandığı için yanıltıcıdır.
// Piksel-bazlı H-score'u yeniden hesaplamak için kullanıcı M4'ü yeni eşiklerle
// yeniden çalıştırmalıdır.
def pixelStaleRemoved = false
def annotationMeasNames = selected.getMeasurementList().getNames().toList()
def staleKeys = annotationMeasNames.findAll {
    it == 'Pixelwise H-score' || it.startsWith('H-score-px:')
}
if (!staleKeys.isEmpty()) {
    def list = selected.getMeasurementList()
    staleKeys.each { key -> list.remove(key) }
    list.close()
    pixelStaleRemoved = true
}

def pixelNote = pixelStaleRemoved
    ? "\nℹ Annotation'daki bayatlamış piksel-bazlı H-score ölçümleri (Pixelwise H-score, H-score-px: …) temizlendi.\n  Piksel-bazlı H-score'u güncel eşiklerle yeniden hesaplamak için Modül 4'ü yeniden çalıştırın.\n"
    : ""

// ──────────────────────────────────────────────────────────────
// 5) Sonuç penceresi
// ──────────────────────────────────────────────────────────────
showResultWindow(
    "Eşikler Yeniden Uygulandı 🎛️",
    String.format(
        "Modül: %s\nÖlçüm: %s\n\n" +
        "Yeni eşikler:\n" +
        "  1+ ≥ %.3f OD%s\n" +
        "  2+ ≥ %.3f OD%s\n" +
        "  3+ ≥ %.3f OD%s\n\n" +
        "📊 Yeni grup dağılımı (n = %,d, sadece seçili anotasyon)\n" +
        "──────────────────────────────────\n" +
        "  0  (negatif)  : %,d  (%%%.1f)\n" +
        "  1+ (zayıf)    : %,d  (%%%.1f)\n" +
        "  2+ (orta)     : %,d  (%%%.1f)\n" +
        "  3+ (güçlü)    : %,d  (%%%.1f)\n" +
        "  Toplam ≥1+    : %,d  (%%%.1f)\n\n" +
        "🎯 Metrikler\n" +
        "──────────────\n" +
        "  H-score (0–300)   : %.0f\n" +
        "  Süre              : %.2f sn\n%s\n" +
        "ℹ Memnun değilseniz Yardımcı'yı tekrar açıp yeni eşikler deneyebilirsiniz —\n" +
        "  hücre tespiti yeniden çalıştırılmaz, sadece sınıflandırma güncellenir.\n\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
        moduleHint, measurement,
        newThr.t1, (newThr.t1 != defaults[0] ? ' (değiştirildi)' : ''),
        newThr.t2, (newThr.t2 != defaults[1] ? ' (değiştirildi)' : ''),
        newThr.t3, (newThr.t3 != defaults[2] ? ' (değiştirildi)' : ''),
        total,
        n0, pct0, n1, pct1, n2, pct2, n3, pct3,
        n1 + n2 + n3, pct1 + pct2 + pct3,
        hScore, elapsed, pixelNote
    )
)

println "─────────────────────────────────────"
println "Eşikler yeniden uygulandı: ${moduleHint}"
println "  ${newThr.t1} / ${newThr.t2} / ${newThr.t3}"
println "  n=${total} | 0:${n0} 1+:${n1} 2+:${n2} 3+:${n3} | H-score: ${String.format('%.0f', hScore)}"
println "─────────────────────────────────────"
