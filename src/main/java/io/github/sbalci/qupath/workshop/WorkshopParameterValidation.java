package io.github.sbalci.qupath.workshop;

import java.util.function.ToDoubleFunction;

/** Sayısal ayarları kaydetmeden önce doğrular; JavaFX veya QuPath oturumu gerektirmez. */
public final class WorkshopParameterValidation {
    private WorkshopParameterValidation() {}

    private static final String[][] THRESHOLDS = {
        {"atolye.nuclear1", "atolye.nuclear2", "atolye.nuclear3"},
        {"atolye.membrane1", "atolye.membrane2", "atolye.membrane3"},
        {"atolye.cyto1", "atolye.cyto2", "atolye.cyto3"},
        {"atolye.pixDab1", "atolye.pixDab2", "atolye.pixDab3"}
    };

    /** Geçerli değerde null; geçersiz değerde kullanıcıya gösterilecek çözüm döndürür. */
    public static String problem(String key, double value, ToDoubleFunction<String> current) {
        if (!Double.isFinite(value))
            return "Sonlu bir sayı girin; NaN ve sonsuzluk ölçüm parametresi olamaz.";
        if (key.equals("atolye.cellposeCellprob")) return null; // Model puanı negatif de olabilir.
        if (key.equals("atolye.stardistThreshold") && (value < 0 || value > 1))
            return "Olasılık eşiğini 0 ile 1 arasında girin.";
        if (key.equals("atolye.pixelSize") || key.equals("atolye.stardistPixelSize")) {
            if (value <= 0) return "İşleme piksel boyutu sıfırdan büyük olmalı. Lamın µm/piksel bilgisini Image sekmesinden denetleyin.";
        } else if (key.equals("atolye.pixScale")) {
            if (value < 1) return "Alt örnekleme çarpanı en az 1 olmalı; 1 tam çözünürlüktür.";
        } else if (value < 0) {
            return "Bu değer negatif olamaz. Sıfırın bu ayarda ne anlama geldiğini alanın açıklamasından öğrenin.";
        }
        for (String[] group : THRESHOLDS) {
            boolean belongs = false;
            for (String member : group) if (member.equals(key)) belongs = true;
            if (!belongs) continue;
            double first = group[0].equals(key) ? value : current.applyAsDouble(group[0]);
            double second = group[1].equals(key) ? value : current.applyAsDouble(group[1]);
            double third = group[2].equals(key) ? value : current.applyAsDouble(group[2]);
            if (!(first < second && second < third))
                return "Eşikler 1+ < 2+ < 3+ sırasında olmalı. Artırırken önce üst eşiği, azaltırken önce alt eşiği düzenleyin; gerekirse bölüm varsayılanlarını geri yükleyin.";
        }
        if (key.equals("atolye.minArea") && value > current.applyAsDouble("atolye.maxArea"))
            return "En küçük çekirdek alanı en büyük alandan büyük olamaz. Önce üst alan sınırını artırın.";
        if (key.equals("atolye.maxArea") && value < current.applyAsDouble("atolye.minArea"))
            return "En büyük çekirdek alanı en küçük alandan küçük olamaz. Önce alt alan sınırını azaltın.";
        return null;
    }
}
