/**
 * Yardımcı - WSInfer Çıkarım Denetimi (kurulum · kalibrasyon · sınıf adları)
 * --------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR (SALT OKUNUR):
 *   WSInfer çalıştırmadan ÖNCE ve SONRA bakılacak üç soruyu tek pencerede yanıtlar:
 *     1. WSInfer eklentisi kurulu mu? (qupath.ext.wsinfer.WSInfer sınıfı yüklenebiliyor mu)
 *     2. Bu görüntünün piksel kalibrasyonu (µm/px) var mı? — WSInfer karo boyutunu
 *        modelin config.json'undaki `spacing_um_px` değerine BÖLEREK hesaplar; kalibrasyon
 *        yoksa QuPath 1 pikseli 1 mikron sayar ve model YANLIŞ BÜYÜTMEDE çalışır.
 *        WSInfer bunu bir hata olarak bildirmez — çıkarım sessizce tamamlanır.
 *     3. Karolarda hangi sınıf adları ve hangi olasılık ÖLÇÜM ANAHTARLARI var? —
 *        WSInfer, sınıf adını modelin config.json'undaki `class_names` değerinden
 *        birebir alır. Bunlar veri setinin kısaltmaları DEĞİLDİR: kolorektal
 *        (kather100k) modelinde tümör epiteli sınıfı "TUM" değil
 *        "ColorectalAdenocarcinomaEpithelium"dur. Kendi betiğinizde yanlış adı
 *        kullanırsanız filtre sessizce BOŞ döner — hata almazsınız.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Yalnızca RAPORLAR. Hiçbir nesne oluşturmaz, silmez, değiştirmez; kaydetmez.
 *   • Çıkarım YAPMAZ ve model İNDİRMEZ. Çıkarım için: [Extensions → WSInfer].
 *   • Klinik skor, eşik, alt-tip veya yorum ÜRETMEZ.
 *
 * KULLANIM:
 *   1. Bir slayt açın (WSInfer öncesi ya da sonrası — her ikisinde de çalışır).
 *   2. [Extensions → Atölye → Yardımcılar → Skorlama ve ölçüm → WSInfer çıkarım denetimi]
 *      (ya da [Automate → Project scripts → bu betik])
 *
 * ÇIKTI:
 *   • Sonuç penceresi: kurulum durumu, kalibrasyon durumu ve uyarısı,
 *     karo sayısı, sınıf adları (birebir), her sınıfın ölçüm anahtarı ve
 *     kopyalanabilir örnek filtre satırı.
 *   • Headless: aynı rapor konsola yazılır.
 *
 * YÖNTEM REFERANSLARI:
 *   • Kaczmarzyk JR et al. (2024), npj Precis Oncol — WSInfer + QuPath.
 *     doi:10.1038/s41698-024-00499-9
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── showResultWindow (lint Check 8 — VERBATIM from yardimci-kesisim-alani) ───
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
            def __footer = new javafx.scene.control.Label("QuPath Atölye Betikleri · araştırma/eğitim amaçlı")
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons)
            __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)

            stage.setScene(new javafx.scene.Scene(root, 740, 560))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── 1) Ön kontrol ───────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir slayt açın.")
    return
}

def body = new StringBuilder()
body << "WSInfer ÇIKARIM DENETİMİ (salt okunur)\n"
body << "═════════════════════════════════════════\n\n"

// ── 2) Eklenti kurulu mu? ───────────────────────────────────────────
boolean wsinferInstalled = false
try {
    Class.forName('qupath.ext.wsinfer.WSInfer', false, this.class.classLoader)
    wsinferInstalled = true
} catch (Throwable ignored) {
    wsinferInstalled = false
}

body << "1) EKLENTİ\n"
body << "──────────\n"
if (wsinferInstalled) {
    body << "   ✓ WSInfer eklentisi yüklü (qupath.ext.wsinfer.WSInfer).\n"
    body << "     Çıkarım: [Extensions → WSInfer]\n"
} else {
    body << "   ✗ WSInfer eklentisi BULUNAMADI.\n"
    body << "     Kurulum: [Extensions → Manage extensions] → arama: WSInfer → Install\n"
    body << "     (Bu denetim yine de mevcut karoları raporlar.)\n"
}
body << "\n"

// ── 3) Kalibrasyon ──────────────────────────────────────────────────
def server = imageData.getServer()
def cal = server.getPixelCalibration()
boolean hasMicrons = (cal != null && cal.hasPixelSizeMicrons())
double pw = (cal != null) ? cal.getPixelWidthMicrons() : Double.NaN
double ph = (cal != null) ? cal.getPixelHeightMicrons() : Double.NaN

body << "2) PİKSEL KALİBRASYONU (µm/px)\n"
body << "──────────────────────────────\n"
if (hasMicrons) {
    body << String.format(java.util.Locale.US,
        "   ✓ Kalibre: %.4f x %.4f µm/px%n", pw, ph)
    body << "     WSInfer karo boyutunu modelin spacing_um_px değerine göre doğru ölçekler.\n"
    // Karo boyutu MODELE ÖZGÜDÜR (zoo'da 96-350 px). Aralığın iki ucunu örnekleyip
    // tek bir sayının genelleme olmadığını gösteriyoruz.
    body << "\n     Örnek — yerel karo genişliği modelin kendi patch/spacing değerlerine bağlıdır\n"
    body << "     (zoo'da patch 96-350 px arasında değişir; kesin değer modelin config.json'unda):\n"
    [[96, 1.0d], [224, 0.5d], [350, 0.25d]].each { pair ->
        int patch = pair[0] as int
        double spacing = pair[1] as double
        double downsample = spacing / ((pw + ph) / 2.0d)
        int wpx = Math.round(patch * downsample) as int
        body << String.format(java.util.Locale.US,
            "       patch %3d px @ spacing %.2f  →  %d px yerel genişlik%n", patch, spacing, wpx)
    }
} else {
    body << "   ✗ KALİBRASYON YOK — QuPath 1 pikseli 1 mikron sayıyor.\n\n"
    body << "     ⚠ WSInfer bu durumda karo boyutunu YANLIŞ hesaplar:\n"
    body << "       downsample = spacing_um_px / 1.0 olur, yani spacing_um_px = 0.5 olan bir\n"
    body << "       model 224 px yerine yaklaşık 112 px genişlik ister — eğitildiğinden\n"
    body << "       yaklaşık 2 kat daha yakın bir görüş alanı. Çıkarım HATASIZ tamamlanır;\n"
    body << "       yanlış olduğunu size söyleyen bir uyarı çıkmaz.\n\n"
    body << "     ⚠ Alan (mm²) ölçümleri de üretilemez / yanlış olur.\n\n"
    body << "     Düzeltme: [Image] sekmesinden piksel boyutunu girin.\n"
    body << "     (DZI/JPEG gibi kalibrasyon taşımayan kaynaklarda bu sık görülür.)\n"
}
body << "\n"

// ── 4) Karolar ve GERÇEK sınıf adları ───────────────────────────────
def allDet = QP.getDetectionObjects()
def tiles = allDet.findAll { it.isTile() }
boolean usedFallback = false
if (tiles.isEmpty()) {
    tiles = allDet.findAll { it.getPathClass() != null }
    usedFallback = true
}

body << "3) KARO SINIFLARI VE ÖLÇÜM ANAHTARLARI\n"
body << "──────────────────────────────────────\n"
if (tiles.isEmpty()) {
    body << "   Henüz karo yok — önce [Extensions → WSInfer] ile bir model çalıştırın.\n"
    body << "   Çıkarımdan sonra bu denetimi yeniden çalıştırın; gerçek sınıf adları burada listelenir.\n"
} else {
    if (usedFallback) {
        body << "   Not: karo (tile) nesnesi bulunamadı; sınıflı tüm tespitler kullanıldı.\n\n"
    }
    Map<String, Integer> byClass = new LinkedHashMap<String, Integer>()
    tiles.each { d ->
        def pc = d.getPathClass()
        String cls = (pc != null) ? pc.toString() : "(sınıfsız)"
        byClass[cls] = (byClass.containsKey(cls) ? byClass[cls] : 0) + 1
    }
    body << String.format(java.util.Locale.US, "   Toplam karo: %,d   ·   Sınıf sayısı: %d%n%n",
        tiles.size(), byClass.size())
    body << "   Sınıf adı (BİREBİR)                              Karo\n"
    body << "   ─────────────────────────────────────────────────────────\n"
    byClass.each { cls, cnt ->
        body << String.format(java.util.Locale.US, "   %-46s %,8d%n", cls, cnt)
    }

    // Olasılık ölçüm anahtarları — WSInfer bunları sınıf adının kendisiyle yazar.
    def sample = tiles.find { it.getMeasurementList() != null && !it.getMeasurementList().getMeasurementNames().isEmpty() }
    if (sample != null) {
        def names = sample.getMeasurementList().getMeasurementNames()
        body << "\n   Bir karodaki ölçüm anahtarları (olasılıklar; softmax ile normalize):\n"
        names.each { String n -> body << "     · ${n}\n" }
    }

    def first = byClass.keySet().find { it != "(sınıfsız)" }
    if (first != null) {
        body << "\n   Kopyalanabilir filtre örneği — kısaltma DEĞİL, birebir bu ad kullanılmalı:\n\n"
        body << "     def hedefSinif = \"${first}\"\n"
        body << "     def secilen = QP.getDetectionObjects().findAll {\n"
        body << "         it.getPathClass()?.getName() == hedefSinif\n"
        body << "     }\n"
    }
}
body << "\n"

body << "Bu bir DENETİM raporudur — nesne oluşturmaz, değiştirmez ve kaydetmez.\n"
body << "Sınıf adları modelin config.json dosyasındaki class_names değerinden gelir\n"
body << "(Kaczmarzyk 2024; Bankhead 2017).\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("WSInfer çıkarım denetimi", body.toString())
println "✓ WSInfer denetimi tamamlandı (salt okunur)."
