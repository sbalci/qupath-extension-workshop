/**
 * Yardımcı - Piksel Eşikleyici başlatıcı (Create thresholder)
 * -----------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   QuPath'in YERLEŞİK "Create thresholder" (piksel eşikleyici) aracını açar.
 *   Bu araç, tek bir ölçüm/kanal (ör. Hematoksilen OD, parlaklık) üzerinde SABİT
 *   bir eşik uygulayan basit bir piksel sınıflandırıcı oluşturur — tam çözünürlükte,
 *   canlı kaplama önizlemesiyle, kaydedilebilir ve proje geneli uygulanabilir.
 *   Doku/arka plan ayrımı için native Otsu sihirbazına göre daha esnek bir yöntemdir.
 *
 * NATIVE OTSU SİHİRBAZINDAN FARKI:
 *   • Native sihirbaz: düşük çözünürlükte Otsu → hızlı, otomatik, tek anotasyon.
 *   • Piksel eşikleyici: tam çözünürlük, elle eşik + kanal seçimi, kaydedilir,
 *     görüntüleyicide canlı kaplama; sonucu anotasyon/ölçüm nesnelerine çevirebilir.
 *
 * KULLANIM:
 *   1. Bir slayt açın (ideali bir ROI de seçin).
 *   2. [Extensions → Atölye → Yardımcılar → Doku tespiti → Piksel eşikleyici]
 *   3. Açılan pencerede ölçümü (ör. Hematoksilen OD) ve eşiği seçin; önizleyin;
 *      "Create objects" ile doku bölgelerini anotasyona çevirin.
 *   Aynı araç menüden de açılır: [Classify → Pixel classification → Create thresholder].
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def gui = qupath.lib.gui.QuPathGUI.getInstance()
if (gui == null) {
    println "Piksel eşikleyici için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    println "Menü: Classify → Pixel classification → Create thresholder"
    return
}
if (QP.getCurrentImageData() == null) {
    Dialogs.showWarningNotification("Piksel eşikleyici", "Önce bir slayt açın; eşikleyici açık görüntü ister.")
    return
}

// QuPath'in yerleşik komutunu metnine göre bul (İngilizce arayüz varsayılan; TR yedekleri de dener).
def labels = ["Create thresholder...", "Create thresholder", "Eşikleyici oluştur...", "Eşikleyici oluştur"]
def action = null
for (t in labels) {
    try { def a = gui.lookupActionByText(t); if (a != null) { action = a; break } } catch (Throwable ignore) {}
}
def act = action
javafx.application.Platform.runLater {
    try {
        if (act != null) {
            act.handle(new javafx.event.ActionEvent())
            println "✓ Piksel eşikleyici açıldı."
        } else {
            Dialogs.showInfoNotification("Piksel eşikleyici",
                "Menüden açın: Classify → Pixel classification → Create thresholder.\n" +
                "Doku maskesi için: çözünürlük düşük, ölçüm = Hematoksilen OD (ya da parlaklık), eşik > 0 seçin; " +
                "sonra 'Create objects' ile 'Doku' anotasyonuna çevirin.")
        }
    } catch (Throwable t) {
        Dialogs.showErrorMessage("Piksel eşikleyici", (t.getMessage() ?: t.getClass().getSimpleName()))
    }
}
println "Piksel eşikleyici başlatıcı çalıştı. ⚠️ Yalnızca araştırma/eğitim amaçlıdır."
