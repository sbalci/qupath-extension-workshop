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

    /** Geçerli değerde null; geçersiz değerde aktif dilde çözüm metni döndürür. */
    public static String problem(String key, double value, ToDoubleFunction<String> current) {
        return problem(key, value, current, WorkshopI18n.isEnglish());
    }

    /** Geçerli değerde null; geçersiz değerde seçilen dilde (TR/EN) çözüm metni döndürür. */
    public static String problem(String key, double value, ToDoubleFunction<String> current, boolean english) {
        if (!Double.isFinite(value))
            return WorkshopI18n.t("val.nonFinite", english);
        if (key.equals("atolye.cellposeCellprob")) return null; // Model puanı negatif de olabilir.
        if (key.equals("atolye.stardistThreshold") && (value < 0 || value > 1))
            return WorkshopI18n.t("val.probBounds", english);
        if (key.equals("atolye.pixelSize") || key.equals("atolye.stardistPixelSize")) {
            if (value <= 0) return WorkshopI18n.t("val.pixelSizePos", english);
        } else if (key.equals("atolye.pixScale")) {
            if (value < 1) return WorkshopI18n.t("val.pixScaleMin", english);
        } else if (value < 0) {
            return WorkshopI18n.t("val.nonNegative", english);
        }
        for (String[] group : THRESHOLDS) {
            boolean belongs = false;
            for (String member : group) if (member.equals(key)) belongs = true;
            if (!belongs) continue;
            double first = group[0].equals(key) ? value : current.applyAsDouble(group[0]);
            double second = group[1].equals(key) ? value : current.applyAsDouble(group[1]);
            double third = group[2].equals(key) ? value : current.applyAsDouble(group[2]);
            if (!(first < second && second < third))
                return WorkshopI18n.t("val.thresholdOrder", english);
        }
        if (key.equals("atolye.minArea") && value > current.applyAsDouble("atolye.maxArea"))
            return WorkshopI18n.t("val.minGtMax", english);
        if (key.equals("atolye.maxArea") && value < current.applyAsDouble("atolye.minArea"))
            return WorkshopI18n.t("val.maxLtMin", english);
        return null;
    }
}
