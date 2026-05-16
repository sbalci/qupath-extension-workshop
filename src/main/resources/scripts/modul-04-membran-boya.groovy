/**
 * Modül 4 — Tek Tıkla HER2 / Membran IHC Skorlaması
 * ---------------------------------------------------
 * Atölye için "hızlı deneme" scripti. Seçilen anotasyon içinde HER2 (veya
 * E-kadherin, β-katenin gibi başka bir membran IHC boyamasını) skorlar.
 * Her hücre **0 / 1+ / 2+ / 3+** olarak sınıflanır; **ASCO/CAP HER2** klinik
 * yorumu otomatik üretilir.
 *
 * KULLANIM:
 *   1. HER2 IHC slaytını açın
 *   2. Image type → "Brightfield (other)" olduğundan emin olun
 *   3. [R] tuşu → tümör içeren ~1×1 mm dikdörtgen anotasyon çizin
 *   4. Anotasyon seçili iken → [Automate → Project scripts → bu script]
 *
 * NEDEN MEMBRAN FARKLI?
 *   • Membran DAB ÇEKİRDEĞİN ÇEVRESİNDE → tek çekirdek değil "hücre" seviyesi
 *   • Bu yüzden Cell expansion (5 µm) ile çekirdek dışına genişletilir
 *   • Score compartment "Cell: DAB OD mean" — tüm hücredeki DAB
 *   • HER2 klinik patognomik özelliği: **tam, çevreleyici, koyu** membran
 *
 * KLİNİK YORUM (ASCO/CAP 2018 meme):
 *   • 0/1+        → HER2 negatif
 *   • 2+          → equivocal → refleks FISH
 *   • 3+          → HER2 pozitif (anti-HER2 endikasyonu)
 *
 * UYARI: Computational HER2 skoru ARAŞTIRMA ve EĞİTİM amaçlıdır.
 *        Klinik raporlamada patolog yorumu ve doğrulanmış sistem zorunlu.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathAnnotationObject

// ──────────────────────────────────────────────────────────────
// Non-modal pencere yardımcıları
//   - waitForConfirm    : modal-hissi veren ama QuPath'i bloklamayan onay penceresi
//   - showResultWindow  : sonuç penceresi — açık kalır, QuPath kullanılmaya devam edilebilir
//
// İkisi de always-on-top açık başlar; kullanıcı kapatmadan slaytta dolaşabilir,
// parametre değiştirip scripti tekrar koşabilir, sonuçları kopyalayabilir.
// ──────────────────────────────────────────────────────────────
def waitForConfirm = { String windowTitle, String windowBody ->
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
    Dialogs.showErrorMessage(
        "Görüntü açık değil",
        "Önce bir HER2 IHC slaytı açın, sonra bu scripti tekrar çalıştırın."
    )
    return
}

def imageTypeName = imageData.getImageType()?.toString() ?: ""
if (!imageTypeName.toLowerCase().contains("brightfield")) {
    Dialogs.showErrorMessage(
        "Yanlış görüntü tipi",
        "Image type 'Brightfield' olmalı. Şu anki: ${imageTypeName}\n\n" +
        "IHC için: Image type → 'Brightfield (other)' seçin."
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 2) Karşılama
// ──────────────────────────────────────────────────────────────
def devam = waitForConfirm(
    "Modül 4 — HER2 / Membran IHC skorlaması",
    "Bu script seçili anotasyon içindeki her hücreye 0 / 1+ / 2+ / 3+\n" +
    "skoru atar ve ASCO/CAP klinik yorumu üretir.\n\n" +
    "Atölye varsayılan eşikleri (Cell: DAB OD mean):\n" +
    "  • 1+ (zayıf):       0.15 OD\n" +
    "  • 2+ (orta):        0.40 OD  ← klinik equivocal eşiği\n" +
    "  • 3+ (güçlü):       0.70 OD  ← klinik pozitif eşiği\n\n" +
    "Cell expansion: 5 µm (membran sinyalinin örnekleneceği halka)\n\n" +
    "Çıktı: her bin için yüzdeler + H-score + ASCO/CAP yorumu.\n\n" +
    "⚠️ Computational HER2 ARAŞTIRMA içindir; klinik karara dönüştürmeyin.\n\n" +
    "Hazırsanız OK."
)
if (!devam) {
    println "Kullanıcı iptal etti."
    return
}

// ──────────────────────────────────────────────────────────────
// 3) Anotasyon kontrolü
// ──────────────────────────────────────────────────────────────
def selected = QP.getSelectedObject()
if (selected == null || !(selected instanceof PathAnnotationObject)) {
    Dialogs.showErrorMessage(
        "Anotasyon seçili değil",
        "Tümör içeren ~1×1 mm bir dikdörtgen anotasyon çizip seçili tutun."
    )
    return
}
def targetAnnotation = selected

// ──────────────────────────────────────────────────────────────
// 4) Positive cell detection (membran)
// ──────────────────────────────────────────────────────────────
println "─────────────────────────────────────"
println "Modül 4 — HER2 / Membran IHC"
println "─────────────────────────────────────"
println "Membran skorlaması başlatılıyor..."
println "  • Score compartment: Cell: DAB OD mean"
println "  • Threshold 1+ / 2+ / 3+: 0.15 / 0.40 / 0.70 OD"
println "  • Cell expansion: 5 µm"

def t0 = System.currentTimeMillis()

QP.selectObjects(targetAnnotation)
QP.runPlugin(
    'qupath.imagej.detect.cells.PositiveCellDetection',
    '{' +
        '"detectionImageBrightfield":"Hematoxylin OD",' +
        '"requestedPixelSizeMicrons":0.5,' +
        '"backgroundRadiusMicrons":8.0,' +
        '"medianRadiusMicrons":0.0,' +
        '"sigmaMicrons":1.5,' +
        '"minAreaMicrons":10.0,' +
        '"maxAreaMicrons":400.0,' +
        '"threshold":0.1,' +
        '"watershedPostProcess":true,' +
        '"cellExpansionMicrons":5.0,' +
        '"includeNuclei":true,' +
        '"smoothBoundaries":true,' +
        '"makeMeasurements":true,' +
        '"thresholdCompartment":"Cell: DAB OD mean",' +
        '"thresholdPositive1":0.15,' +
        '"thresholdPositive2":0.40,' +
        '"thresholdPositive3":0.70,' +
        '"singleThreshold":false' +
    '}'
)

def elapsed = (System.currentTimeMillis() - t0) / 1000.0

// ──────────────────────────────────────────────────────────────
// 5) Sonuçları topla
// ──────────────────────────────────────────────────────────────
def cells = targetAnnotation.getChildObjects()
def totalCells = cells.size()

def n0 = 0, n1 = 0, n2 = 0, n3 = 0
cells.each { c ->
    def cls = c.getPathClass()?.getName() ?: ""
    if (cls.contains("3+"))      n3++
    else if (cls.contains("2+")) n2++
    else if (cls.contains("1+")) n1++
    else                          n0++
}

def pct = { count -> totalCells > 0 ? 100.0 * count / totalCells : 0.0 }
def pct0 = pct(n0), pct1 = pct(n1), pct2 = pct(n2), pct3 = pct(n3)
// H-score membran/sitoplazmik markerlar için araştırmada kullanılır; HER2 klinik
// raporlama 0/1+/2+/3+ kategorik skorunu kullanır. İkisini de raporluyoruz.
def hScore = pct1 + 2.0 * pct2 + 3.0 * pct3

// ASCO/CAP paterninin hangi kategoriye uyduğunu tespit et — bu sadece
// yüzde eşiklerine göre bir etikettir (klinik karar değil)
def ascoCapPatern
if (pct3 >= 10) {
    ascoCapPatern = String.format("3+ paterni  (%%%.1f hücre 3+, ≥%%10 eşiği)", pct3).replace("%%", "%")
} else if (pct2 + pct3 >= 10) {
    ascoCapPatern = String.format("2+ paterni (equivocal)  (%%%.1f hücre 2+/3+)", pct2 + pct3).replace("%%", "%")
} else if (pct1 + pct2 + pct3 >= 10) {
    ascoCapPatern = String.format("1+ paterni  (toplam pozitif %%%.1f)", pct1 + pct2 + pct3).replace("%%", "%")
} else {
    ascoCapPatern = String.format("0 paterni  (toplam pozitif %%%.1f, <%%10 eşiği)", pct1 + pct2 + pct3).replace("%%", "%")
}

def uyari = ""
if (totalCells < 200) {
    uyari = String.format("\n📝 Not: %%,d hücre <200 — küçük örneklem.", totalCells).replace("%%,d", String.format("%,d", totalCells))
}

// ──────────────────────────────────────────────────────────────
// 7) Sonucu sun — yalnızca sayılar ve faktöel patern; klinik karar prose yok
// ──────────────────────────────────────────────────────────────
showResultWindow(
    "Tamamlandı 🧬",
    String.format(
        "HER2 / Membran IHC skorlaması bitti.\n\n" +
        "📊 Hücre dağılımı (n = %,d toplam)\n" +
        "────────────────────────────────────\n" +
        "  0  (negatif)        : %,d  (%%%.1f)\n" +
        "  1+ (zayıf)          : %,d  (%%%.1f)\n" +
        "  2+ (orta)           : %,d  (%%%.1f)\n" +
        "  3+ (güçlü)          : %,d  (%%%.1f)\n\n" +
        "🎯 Skor yüzdeleri (özet)\n" +
        "─────────────────────────\n" +
        "  %% 0  : %%%.1f\n" +
        "  %% 1+ : %%%.1f\n" +
        "  %% 2+ : %%%.1f\n" +
        "  %% 3+ : %%%.1f\n" +
        "  Toplam pozitif (≥1+) : %%%.1f\n\n" +
        "📐 Bütünleşik metrikler\n" +
        "────────────────────────\n" +
        "  H-score (0–300)     : %.0f       ← araştırmada kullanılır; klinik standart 0/1+/2+/3+\n" +
        "  Süre                : %.1f sn\n" +
        "%s\n" +
        "🩺 ASCO/CAP eşiklerine göre patern:\n" +
        "    %s\n\n" +
        "Klinik karar (Pozitif / Equivocal → FISH / Negatif) için patolog görsel\n" +
        "skoru zorunludur; computational yalnızca tarama amaçlı raporlanır.\n\n" +
        "Sıradaki: Web sitesindeki Modül 4 'Şimdi anlayalım' bölümünde\n" +
        "Cell expansion ve eşikleri değiştirip skor kaymasını gözlemleyin.",
        totalCells,
        n0, pct0, n1, pct1, n2, pct2, n3, pct3,
        pct0, pct1, pct2, pct3, (pct1 + pct2 + pct3),
        hScore, elapsed, uyari,
        ascoCapPatern
    )
)

println "─────────────────────────────────────"
println "Tamamlandı: ${totalCells} hücre"
println "  0: ${n0} (${String.format('%.1f', pct0)}%)  |  1+: ${n1} (${String.format('%.1f', pct1)}%)"
println "  2+: ${n2} (${String.format('%.1f', pct2)}%)  |  3+: ${n3} (${String.format('%.1f', pct3)}%)"
println "  H-score: ${String.format('%.0f', hScore)}  |  ASCO/CAP: ${ascoCapPatern}"
println "─────────────────────────────────────"
