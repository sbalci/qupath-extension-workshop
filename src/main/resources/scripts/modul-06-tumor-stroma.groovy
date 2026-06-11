/**
 * Modül 6 - Tek Tıkla Tümör vs Stroma Segmentasyonu
 * ---------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Bu betik önceden eğitilmiş bir **piksel sınıflandırıcısını** projedeki
 * `classifiers/` klasöründen yükleyip aktif slayttaki tüm doku üzerinde
 * çalıştırır. Sonuçtan **Tumor** ve **Stroma** sınıflı anotasyonlar üretir
 * ve **Tümör/Stroma Oranı (TSR)** ölçümünü kaydeder.
 *
 * ÖNKOŞUL — sınıflandırıcı dosyası:
 *   Proje klasörünüzde şu yol bulunmalı:
 *     <proje>/classifiers/tumor-stroma-RF.json
 *
 *   Sınıflandırıcı yoksa: Modül 6'nın "Şimdi anlayalım" bölümündeki
 *   adımları izleyerek anotasyon çizip eğitin (5 dk), sonra "Save classifier"
 *   ile bu isimle kaydedin.
 *
 * KULLANIM:
 *   1. Bir H&E slaytı açın
 *   2. Tüm slaytı işlemek için anotasyon ÇİZMENIZE GEREK YOK
 *      — betik tüm görüntüye uygular
 *   3. [Automate → Project scripts → bu betik]
 *
 * ÇIKTI:
 *   Tümör/Stroma Oranı (TSR) alan temelli araştırma/eğitim ölçümüdür.
 *   Modül 7'de bu sınıflandırıcı çıktısını İHK sayımıyla birleştireceğiz.
 *
 * METODOLOJI NOTLARI (cancer-informatics eğitim 03'ten uyarlanmış):
 *   • Eğitim örnekleri farklı bölgelerden gelmeli — aynı alanın 10 katmanı
 *     yerine slayta yayılmış birkaç temsili örnek daha öğreticidir.
 *   • "Create objects" adımında minimum object size + hole size kalibrasyonu
 *     gürültü temizliği için kritiktir; bu betik `10000.0 µm² / 5000.0 µm²`
 *     varsayılanını kullanır (atölye seçimi — slayt çözünürlüğüne göre değişir).
 *   • Arayüz tarafı eğitimi (J. Cieślik et al., CC-BY-SA):
 *     cancer-informatics.org/de/docs/ai/qupath_03_tissue_segmentation
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.projects.Project

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
def atolyeD = { String k, double  d -> (double)  __wpCall('dbl',  [String.class, double.class]  as Class[], [k, d] as Object[], d) }
def atolyeS = { String k, String  d -> (String)  __wpCall('str',  [String.class, String.class]  as Class[], [k, d] as Object[], d) }
def atolyeI = { String k, int     d -> (int)     __wpCall('intg', [String.class, int.class]     as Class[], [k, d] as Object[], d) }
def atolyeB = { String k, boolean d -> (boolean) __wpCall('bool', [String.class, boolean.class] as Class[], [k, d] as Object[], d) }

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
    Dialogs.showErrorMessage(
        "Görüntü açık değil",
        "Önce bir H&E slaytı açın, sonra bu betiği tekrar çalıştırın."
    )
    return
}

def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage(
        "Proje açık değil",
        "Bu betik proje seviyesinde çalışır.\n" +
        "Önce [File → Project → Create project] ile bir proje oluşturun ve slaytlarınızı ekleyin."
    )
    return
}

def classifierName = atolyeS('atolye.classifierName', 'tumor-stroma-RF')
def availableClassifiers = project.getPixelClassifiers().getNames()

if (!availableClassifiers.contains(classifierName)) {
    Dialogs.showErrorMessage(
        "Sınıflandırıcı bulunamadı",
        "Bu betik şu sınıflandırıcıyı arıyor:\n" +
        "  classifiers/${classifierName}.json\n\n" +
        "Projenizde bu dosya yok. Bulunan sınıflandırıcılar:\n" +
        "  ${availableClassifiers.join(', ') ?: '(hiçbiri)'}\n\n" +
        "Çözüm — Modül 6'daki adımları izleyin:\n" +
        "  1. H&E slaytında Tumor / Stroma anotasyonları çizin (~5 dk)\n" +
        "  2. [Classify → Pixel classification → Train pixel classifier]\n" +
        "  3. Random Forest, Resolution: High (2 µm/px)\n" +
        "  4. Eğitin ve 'Save classifier' ile **${classifierName}** ismiyle kaydedin\n" +
        "  5. Bu betiği tekrar çalıştırın"
    )
    return
}

def minObjectArea = atolyeD('atolye.minObjectArea', 10000.0)
def minHoleArea   = atolyeD('atolye.minHoleArea',   5000.0)

// ──────────────────────────────────────────────────────────────
// 2) Karşılama
// ──────────────────────────────────────────────────────────────
def devam = waitForConfirm(
    "Modül 6 - Tümör vs Stroma segmentasyonu",
    "Bu betik projenizdeki '${classifierName}' adlı piksel sınıflandırıcıyı\n" +
    "açık slaytın tamamına uygular ve **Tumor** + **Stroma** sınıflı anotasyon\n" +
    "nesneleri üretir.\n\n" +
    "Atölye varsayılan post-işlem parametreleri:\n" +
    "  • Minimum object size  : ${minObjectArea} µm²${minObjectArea != 10000.0 ? ' (değiştirildi)' : ''}  (≈ 0.01 mm²)\n" +
    "  • Minimum hole size    : ${minHoleArea} µm²${minHoleArea != 5000.0 ? ' (değiştirildi)' : ''}\n" +
    "  • Split objects        : Açık (birleşik tümör adalarını ayır)\n\n" +
    "Çıktı:\n" +
    "  • Annotations panelinde Tumor ve Stroma sınıflı yeni anotasyonlar\n" +
    "  • Toplam alan ve TSR (tümör/stroma oranı)\n\n" +
    "Bu işlem tüm slaytı tarayacağı için 1–3 dakika sürebilir.\n\n" +
    "⚠️ Bu işlem slayttaki MEVCUT ANOTASYONLARI — elle çizdiğiniz Tumor/Stroma\n" +
    "eğitim bölgeleri dahil — siler ve sınıflandırıcı çıktısıyla değiştirir.\n" +
    "Sınıflandırıcı kaydedildiyse yeniden çizmeniz gerekmez; saklamak isterseniz\n" +
    "önce [File → Export objects] ile dışa aktarın.\n\n" +
    "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.\n\n" +
    "Hazırsanız Çalıştır düğmesine basın."
)
if (!devam) { println "İptal."; return }

// ──────────────────────────────────────────────────────────────
// 3) Sınıflandırıcıyı çalıştır
// ──────────────────────────────────────────────────────────────
println "─────────────────────────────────────"
println "Modül 6 - Tümör vs Stroma"
println "─────────────────────────────────────"
println "Sınıflandırıcı: ${classifierName}"
println "Aktif slayt: ${QP.getProjectEntry()?.getImageName() ?: 'unnamed'}"
println "Tüm görüntü üzerine uygulanıyor..."

def t0 = System.currentTimeMillis()

def generatedName = "Generated by Modül 6 - ${classifierName}"

// Önceki betik çıktısını temizle. (Aşağıdaki DELETE_EXISTING zaten tüm
// anotasyonları — eğitim çizimleri dahil — siler; başlangıç onayına bakın.)
def existing = QP.getAnnotationObjects().findAll {
    (it.getName() ?: "") == generatedName
}
if (!existing.isEmpty()) {
    QP.removeObjects(existing, true)
    println "  Önceki ${existing.size()} betik çıktısı temizlendi."
}

def beforeAnnotations = QP.getAnnotationObjects() as Set

// Sınıflandırıcıyı uygula → anotasyonlar üret.
// minArea + minHoleArea açıkça veriliyor; QuPath varsayılanları (0/0) yüksek
// çözünürlüklü sınıflandırıcılarda binlerce küçük parça üretir
// (cancer-informatics ci_03: bu adım "create objects" sırasında en sık atlanan
// kalibrasyondur). 10.000 µm² ≈ 0.01 mm² eşiği small-noise temizliği yapar
// ama ~100 µm çaplı küçük tümör adacıklarını hala tutar; 5.000 µm² hole eşiği
// stromal cep'leri (lakün) doldurmaz ama mikro-bir delikleri kapatır.
QP.createAnnotationsFromPixelClassifier(
    classifierName,
    minObjectArea,       // minimum object area (µm²) — 0.01 mm² gürültü filtresi
    minHoleArea,        // minimum hole area (µm²)  — mikro-deliklerin doldurulma eşiği
    "SPLIT",            // split into multiple annotations
    "DELETE_EXISTING",  // deletes ALL existing child annotations (incl. training) — see confirm
    "SELECT_NEW"        // select newly created objects
)

def generatedAnnotations = QP.getAnnotationObjects().findAll {
    !beforeAnnotations.contains(it) && (it.getPathClass()?.getName() in ["Tumor", "Stroma"])
}
generatedAnnotations.each { it.setName(generatedName) }

def elapsed = (System.currentTimeMillis() - t0) / 1000.0

// ──────────────────────────────────────────────────────────────
// 4) Sonuçları topla
// ──────────────────────────────────────────────────────────────
def cal = imageData.getServer().getPixelCalibration()
def pixelWidth  = cal.getPixelWidthMicrons()
def pixelHeight = cal.getPixelHeightMicrons()
if (!(pixelWidth > 0) || !(pixelHeight > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok",
        "Slaytta piksel boyutu (µm) tanımlı değil; alan ölçümleri (mm²) ve TSR hesaplanamaz.\n\n" +
        "Piksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar →\n" +
        "Kalibrasyon (piksel boyutu). Sonra bu betiği tekrar çalıştırın.")
    return
}

def tumorAnnotations = QP.getAnnotationObjects().findAll {
    it.getPathClass()?.getName() == "Tumor"
}
def stromaAnnotations = QP.getAnnotationObjects().findAll {
    it.getPathClass()?.getName() == "Stroma"
}

def areaFn = { ann ->
    def roi = ann.getROI()
    return roi != null ? (roi.getArea() * pixelWidth * pixelHeight) / 1_000_000.0 : 0.0
}

def tumorAreaMm2  = tumorAnnotations.sum(0.0)  { areaFn(it) }
def stromaAreaMm2 = stromaAnnotations.sum(0.0) { areaFn(it) }
def totalAreaMm2  = tumorAreaMm2 + stromaAreaMm2

def tsr = totalAreaMm2 > 0 ? 100.0 * tumorAreaMm2 / totalAreaMm2 : 0.0  // % tumor of total tissue

def warnTissueAreaMm2 = atolyeD('atolye.warnTissueAreaMm2', 1.0)
def uyari = ""
if (tumorAnnotations.isEmpty()) {
    uyari = "\n⚠️ Hiç tümör bölgesi tespit edilmedi.\n" +
            "  Sınıflandırıcı bu slayt için iyi eğitilmemiş olabilir.\n" +
            "  Modül 6'daki aktif öğrenme iş akışıyla daha fazla anotasyon ekleyin."
} else if (totalAreaMm2 < warnTissueAreaMm2) {
    uyari = String.format(java.util.Locale.US, "\n⚠️ Çok küçük doku alanı (%.2f mm²) — sonuçlar güvenilir olmayabilir.", totalAreaMm2)
}

// ──────────────────────────────────────────────────────────────
// 4b) TSR'yi ölçüm listesine yaz → Modül 9 (MeasurementExporter) dışa aktarır.
//    Skaler TSR değeri ekranda gösterilirken hiçbir nesneye yazılmıyordu; bu
//    nedenle Modül 9 TSV'sinde görünmüyordu. Burada tam-görüntü ROI'li tek bir
//    "TSR Özet" anotasyonu oluşturup değerleri ölçüm listesine yazıyoruz.
//    (Eski "TSR Özet" varsa önce silinir → tekrar çalıştırmada birikmez.)
// ──────────────────────────────────────────────────────────────
QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == "TSR Özet" }, false)
def tsrServer  = imageData.getServer()
def tsrSummary = qupath.lib.objects.PathObjects.createAnnotationObject(
    qupath.lib.roi.ROIs.createRectangleROI(0, 0, tsrServer.getWidth(), tsrServer.getHeight(),
        qupath.lib.regions.ImagePlane.getDefaultPlane()))
tsrSummary.setName("TSR Özet")
tsrSummary.measurements['TSR (%)']                 = tsr
tsrSummary.measurements['Tümör alanı (mm2)']       = tumorAreaMm2
tsrSummary.measurements['Stroma alanı (mm2)']      = stromaAreaMm2
tsrSummary.measurements['Toplam doku alanı (mm2)'] = totalAreaMm2
tsrSummary.setLocked(true)
QP.addObjects([tsrSummary])
QP.fireHierarchyUpdate()
println String.format(java.util.Locale.US,
    "  TSR (%%) = %.1f → \"TSR Özet\" anotasyonunun ölçüm listesine yazıldı (Modül 9 dışa aktarımında görünür).", tsr)

// ──────────────────────────────────────────────────────────────
// 5) Sonucu sun
// ──────────────────────────────────────────────────────────────
showResultWindow(
    "Tamamlandı 🧠",
    String.format(java.util.Locale.US, 
        "Tümör vs Stroma segmentasyonu bitti.\n\n" +
        "📊 Alan dağılımı\n" +
        "─────────────────\n" +
        "  Tümör  : %.2f mm²  (%,d anotasyon nesnesi)\n" +
        "  Stroma : %.2f mm²  (%,d anotasyon nesnesi)\n" +
        "  Toplam : %.2f mm²\n\n" +
        "🎯 Tümör/Stroma Oranı (TSR)\n" +
        "────────────────────────────\n" +
        "  TSR (%%tümör / toplam doku) : %%%.1f\n" +
        "  Süre                       : %.1f sn\n" +
        "%s\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
        tumorAreaMm2, tumorAnnotations.size(),
        stromaAreaMm2, stromaAnnotations.size(),
        totalAreaMm2, tsr, elapsed, uyari
    )
)

println "─────────────────────────────────────"
println "Tamamlandı:"
println String.format(java.util.Locale.US, "  Tümör: %.2f mm² (%d nesne)", tumorAreaMm2, tumorAnnotations.size())
println String.format(java.util.Locale.US, "  Stroma: %.2f mm² (%d nesne)", stromaAreaMm2, stromaAnnotations.size())
println String.format(java.util.Locale.US, "  TSR: %.1f%%  |  Süre: %.1f sn", tsr, elapsed)
println "─────────────────────────────────────"
