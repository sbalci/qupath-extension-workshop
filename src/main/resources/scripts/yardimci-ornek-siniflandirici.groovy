/**
 * Yardımcı - Örnek tümör/stroma sınıflandırıcısını projeye kaydet
 * --------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * Atölye eklentisiyle gelen örnek piksel sınıflandırıcısını ('tumor-stroma-RF')
 * açık projenin classifiers/ klasörüne kaydeder. Böylece model
 * [Classify → Pixel classification] listesinde görünür; inceleyebilir veya
 * yeniden eğitebilirsiniz.
 *
 * KURAL: Projede aynı isimli bir sınıflandırıcı zaten varsa (örn. kendi eğittiğiniz
 * model) ÜZERİNE YAZILMAZ — mevcut modeliniz korunur.
 *
 * Not: Modül 6 ve 7, bu örnek modeli projeye kaydetmeseniz de otomatik kullanır;
 * bu yardımcı yalnızca modeli görünür/incelenebilir kılmak için vardır.
 */
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.io.GsonTools
import qupath.lib.classifiers.pixel.PixelClassifier

// --- Atölye ayarları: eklenti yüklüyse oku, yoksa atölye varsayılanı kullanılır ---
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeS = { String k, String d -> (String) __wpCall('str', [String.class, String.class] as Class[], [k, d] as Object[], d) }

def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage(
        "Proje açık değil",
        "Bu yardımcı proje seviyesinde çalışır.\n" +
        "Önce [File → Project] ile bir proje açın, sonra tekrar çalıştırın."
    )
    return
}

def classifierName = atolyeS('atolye.classifierName', 'tumor-stroma-RF')

if (project.getPixelClassifiers().getNames().contains(classifierName)) {
    Dialogs.showMessageDialog(
        "Zaten mevcut",
        "Projenizde zaten '${classifierName}' adlı bir sınıflandırıcı var.\n" +
        "Mevcut modeliniz korunuyor; örnek model kurulmadı.\n\n" +
        "Kendi modelinizi kullanmak istemiyorsanız önce onu silin, sonra bu\n" +
        "yardımcıyı tekrar çalıştırın."
    )
    return
}

def json = null
try {
    json = Class.forName('io.github.sbalci.qupath.workshop.WorkshopResources')
        .getMethod('getTumorStromaClassifierJson').invoke(null)
} catch (Throwable t) { json = null }

if (json == null) {
    Dialogs.showErrorMessage(
        "Örnek model bulunamadı",
        "Eklentiyle gelen örnek sınıflandırıcıya ulaşılamadı.\n" +
        "Atölye eklentisinin güncel sürümünü kullandığınızdan emin olun."
    )
    return
}

def classifier = GsonTools.getInstance().fromJson(json, PixelClassifier.class)
if (classifier == null) {
    Dialogs.showErrorMessage(
        "Sınıflandırıcı yüklenemedi",
        "Eklentiyle gelen örnek model okunamadı (JSON ayrıştırılamadı)."
    )
    return
}
project.getPixelClassifiers().put(classifierName, classifier)

Dialogs.showMessageDialog(
    "Kuruldu",
    "Örnek sınıflandırıcı '${classifierName}' projenize kaydedildi.\n\n" +
    "[Classify → Pixel classification] listesinde görünür; Modül 6 ve 7'yi\n" +
    "artık bu modelle çalıştırabilirsiniz.\n\n" +
    "⚠️ Yalnızca araştırma/eğitim amaçlıdır."
)
