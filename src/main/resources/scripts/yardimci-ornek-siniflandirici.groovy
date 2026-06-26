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
import qupath.fx.dialogs.Dialogs
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

// Modelin kaynağı: önce eklenti JAR'ındaki paketli JSON, yoksa GitHub'dan .gz indir.
def EXAMPLE_MODEL_GZ_URL =
    'https://raw.githubusercontent.com/sbalci/qupath-extension-workshop/main/src/main/resources/classifiers/tumor-stroma-RF.json.gz'

def downloadGunzip = { String url ->
    // URL kasıtlı olarak 'main' dalına sabitlenir: katılımcılar her zaman güncel
    // modeli alır (sürüm sabitlemesi yerine "her zaman en yeni" tercih edildi).
    def conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection()
    conn.setConnectTimeout(20000)
    conn.setReadTimeout(60000)
    conn.setRequestProperty('User-Agent', 'qupath-extension-workshop')
    try {
        int code = conn.getResponseCode()
        if (code != 200) throw new java.io.IOException("HTTP ${code}")
        def out = new java.io.ByteArrayOutputStream()
        conn.getInputStream().withCloseable { rawIn ->
            new java.util.zip.GZIPInputStream(rawIn).withCloseable { gz ->
                byte[] buf = new byte[1 << 16]
                int n
                while ((n = gz.read(buf)) != -1) out.write(buf, 0, n)
            }
        }
        return new String(out.toByteArray(), 'UTF-8')
    } finally {
        conn.disconnect()
    }
}

def json = null
try {
    json = Class.forName('io.github.sbalci.qupath.workshop.WorkshopResources')
        .getMethod('getTumorStromaClassifierJson').invoke(null)
} catch (Throwable t) { json = null }

if (json == null) {
    println "Paketli örnek model yok; GitHub'dan indiriliyor: ${EXAMPLE_MODEL_GZ_URL}"
    try {
        json = downloadGunzip(EXAMPLE_MODEL_GZ_URL)
    } catch (Throwable t) {
        json = null
        println "İndirme başarısız: ${t.getClass().getSimpleName()} — ${t.getMessage()}"
    }
}

if (json == null) {
    Dialogs.showErrorMessage(
        "Örnek model alınamadı",
        "Örnek sınıflandırıcı ne eklenti JAR'ında bulundu ne de internetten indirilebildi.\n\n" +
        "Çözüm:\n" +
        "  • İnternet bağlantınızı kontrol edip tekrar deneyin, veya\n" +
        "  • Modeli elle indirin: OSF veri paketi osf.io/v7mjq → atolye-eklenti/, veya\n" +
        "    GitHub: github.com/sbalci/qupath-extension-workshop (classifiers/), ve\n" +
        "    projenizin classifiers/ klasörüne koyun.\n\n" +
        "Alternatif: kendi modelinizi Modül 6 sihirbazından 'Yeni sınıflandırıcı eğit' ile eğitin."
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
