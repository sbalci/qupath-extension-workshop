package io.github.sbalci.qupath.workshop;

import java.util.Locale;

/** Teknik istisnayı eyleme dönük Türkçe yönlendirmeye dönüştürür. Ayrıntılar günlükte kalır. */
public final class WorkshopErrors {
    private WorkshopErrors() {}

    public static String guidance(Throwable error) {
        Throwable cause = error;
        // Döngüsel veya aşırı uzun cause zincirlerine karşı sınırlı ilerle.
        for (int depth = 0; depth < 12 && cause.getCause() != null && cause.getCause() != cause; depth++)
            cause = cause.getCause();
        String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
        if (cause instanceof OutOfMemoryError)
            return "Bellek yetersiz. Önce küçük bir alan anotasyonunda deneyin; gereksiz açık görüntüleri kapatın. Tam kesiti tek seferde işlemeyin.";
        if (cause instanceof java.io.FileNotFoundException || cause instanceof java.nio.file.NoSuchFileException)
            return "Gerekli dosya bulunamadı. Görüntü, model veya sınıflandırıcı yolunu yeniden seçin; taşınmış proje için Project → Check image paths komutunu denetleyin.";
        if (message.contains("nan") || message.contains("infinity") || message.contains("pixel calibration"))
            return "Geçersiz ölçüm veya fiziksel ölçek bulundu. Image sekmesindeki piksel genişliği/yüksekliğini ve Atölye Ayarları'ndaki sayıları denetleyin.";
        if (message.contains("column") || message.contains("measurement"))
            return "Beklenen ölçüm sütunu bulunamadı veya okunamadı. Önce hücre tespitini ve ölçümleri üretin; dışa aktarmada seçili sütun adlarını tabloyla karşılaştırın.";
        return "Bir sanal lam açın. Araç bölge istiyorsa alan anotasyonu çizip seçin (kenarı sarı olmalı). Görüntü tipini ve gereken eklenti/modelin kurulumunu denetleyin.";
    }
}
