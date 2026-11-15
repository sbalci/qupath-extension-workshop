/**
 * Yardımcı - Tuş/fare göstergesi (ekran kaydı / canlı sunum için)
 * ---------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   QuPath pencerelerinde bastığınız klavye tuşlarını ve fare
 *   işlemlerini ekranda canlı etiketler olarak GÖSTERİR / GİZLER.
 *   Atölye demolarını ekran kaydederken veya canlı paylaşırken
 *   izleyiciler hangi kısayolu kullandığınızı görür — kendi
 *   ekranınızı kaydederken de işinize yarar.
 *
 *   Aç/kapa anahtarı: betiği bir kez çalıştırın → gösterge açılır;
 *   tekrar çalıştırın → kapanır. qupath.fx.controls.InputDisplay
 *   kullanır — QuPath 0.6.0+ ile birlikte gelir, ek bağımlılık
 *   gerektirmez (eklenti olmadan da, Automate → Script editor'dan
 *   çalışır).
 *
 * NE ÖLÇMEZ:
 *   Hiçbir ölçüm / analiz yapmaz; yalnızca görsel bir sunum
 *   yardımcısıdır. Anotasyon, tespit veya proje verisine dokunmaz.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı görsel bir yardımcıdır.
 */

import qupath.fx.controls.InputDisplay
import qupath.fx.dialogs.Dialogs

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = (gui == null)
if (isHeadless) {
    println "Tuş/fare göstergesi yalnızca QuPath arayüzünde çalışır (headless modda atlandı)."
    return
}

// Gösterge örneğini ana pencerenin özellik haritasında saklarız; böylece
// betiği yeniden çalıştırmak onu bulup kapatabilir (aç/kapa anahtarı).
def PROP_KEY = 'atolye.inputDisplay'

javafx.application.Platform.runLater {
    try {
        def win = gui.getStage()
        if (win == null) {
            Dialogs.showErrorMessage(workshopText('script.c12ffb3efff3', 'Tuş/fare göstergesi'),
                workshopText('script.b33b352bd187', 'QuPath ana penceresi bulunamadı; gösterge açılamadı.'))
            return
        }
        def existing = win.getProperties().get(PROP_KEY)
        if (existing != null) {
            try { existing.hide() } catch (Throwable ignore) {}
            win.getProperties().remove(PROP_KEY)
            Dialogs.showInfoNotification(workshopText('script.c12ffb3efff3', 'Tuş/fare göstergesi'), workshopText('script.e66e0cbe2791', 'Kapatıldı.'))
        } else {
            // Tüm QuPath pencerelerini izle (ana pencere + diyaloglar), böylece
            // odak hangi pencerede olursa olsun tuşlar görünür.
            def display = new InputDisplay(win, javafx.stage.Window.getWindows())
            display.show()
            win.getProperties().put(PROP_KEY, display)
            Dialogs.showInfoNotification(workshopText('script.c12ffb3efff3', 'Tuş/fare göstergesi'),
                workshopText('script.71b0b7844134', 'Açıldı — bastığınız tuşlar ve fare işlemleri ekranda görünür. ' +
                'Kapatmak için betiği yeniden çalıştırın.'))
        }
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.c12ffb3efff3', 'Tuş/fare göstergesi'),
            workshopText('script.17804adf064e', 'Gösterge açılamadı: ') + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}

// Resolve extension UI language; standalone scripts keep their Turkish fallback.
private String workshopText(String key, String fallback) {
    try {
        def bundle = Class.forName('io.github.sbalci.qupath.workshop.WorkshopI18n')
        String translated = bundle.getMethod('t', String.class).invoke(null, key) as String
        return translated == key ? fallback : translated
    } catch (Throwable ignored) {
        return fallback
    }
}
