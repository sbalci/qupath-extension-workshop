package io.github.sbalci.qupath.workshop;

import java.util.Locale;

/** Teknik istisnayı eyleme dönük Türkçe yönlendirmeye dönüştürür. Ayrıntılar günlükte kalır. */
public final class WorkshopErrors {
    private WorkshopErrors() {}

    public static String guidance(Throwable error) {
        if (error == null) {
            return "Beklenmeyen boş hata kaydı alındı. View → Show log penceresinden ayrıntıyı kontrol edin.";
        }
        Throwable cause = error;
        // Döngüsel veya aşırı uzun cause zincirlerine karşı sınırlı ilerle.
        for (int depth = 0; depth < 12 && cause.getCause() != null && cause.getCause() != cause; depth++)
            cause = cause.getCause();
        String className = cause.getClass().getName().toLowerCase(Locale.ROOT);
        String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
        if (cause instanceof OutOfMemoryError)
            return "Bellek yetersiz. Önce 1–2 mm² büyüklüğünde küçük bir alan anotasyonunda deneyin; gereksiz açık sanal lamları kapatın ve tam kesiti tek seferde işlemeyin.";
        if (cause instanceof java.io.FileNotFoundException || cause instanceof java.nio.file.NoSuchFileException)
            return "Gerekli dosya bulunamadı. Sanal lam, model veya sınıflandırıcı yolunu yeniden seçin; taşınmış proje için Project → Check image paths komutunu çalıştırın.";
        if (className.contains("format") || message.contains("unsupported") || message.contains("bio-formats")
                || message.contains("openslide") || message.contains("corrupt") || message.contains("image server"))
            return "Görüntü biçimi okunamadı veya piramit yapısı bozuk. Dosyanın tam indirildiğini ve QuPath 0.6.0+ tarafından desteklenen bir TKG biçimi (.svs, .ndpi, .ome.tif, .ome.zarr) olduğunu doğrulayın.";
        if (message.contains("stain") || message.contains("deconvolution") || message.contains("h-dab") || message.contains("image type"))
            return "Boyama vektörleri veya görüntü tipi eksik/uyumsuz. Sol paneldeki Image sekmesinden Image type değerini H&E için 'Brightfield (H-E)', İHK için 'Brightfield (H-DAB)' olarak seçin.";
        if (message.contains("empty roi") || message.contains("area roi") || message.contains("point roi")
                || message.contains("line roi") || message.contains("no annotation") || message.contains("seçili alan"))
            return "Geçerli bir kapalı alan (ROI) seçili değil. Nokta veya çizgi yerine dikdörtgen/poligon/fırça aracıyla doku üzerinde kapalı alan çizin ve kenarı sarı olana kadar içine tıklayın.";
        if (message.contains("nan") || message.contains("infinity") || message.contains("pixel calibration") || message.contains("microns"))
            return "Geçersiz sayısal ölçüm (NaN/sonsuz) veya fiziksel ölçek (MPP) uyumsuzluğu bulundu. Image sekmesindeki piksel genişliği/yüksekliğini (µm) ve Atölye Ayarları'ndaki sayıları denetleyin.";
        if (message.contains("column") || message.contains("measurement"))
            return "Beklenen ölçüm sütunu bulunamadı veya okunamadı. Önce hücre tespitini ve yoğunluk ölçümlerini üretin; dışa aktarmada seçili sütun adlarını Measure tablosuyla karşılaştırın.";
        if (message.contains("python") || message.contains("cellpose") || message.contains("stardist")
                || message.contains("instanseg") || message.contains("djl"))
            return "İsteğe bağlı derin öğrenme / Python ortamı bulunamadı. Extensions → Atölye → Ortam kontrolü penceresinden kurulumu denetleyin veya çekirdek modüllerde yerleşik Watershed yedeğiyle devam edin.";
        return "Bir sanal lam açın. Araç bölge istiyorsa kapalı bir alan anotasyonu çizip seçin (kenarı sarı olmalı). Görüntü tipini (H&E / H-DAB) ve gereken eklenti kurulumunu denetleyin.";
    }
}
