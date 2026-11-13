package io.github.sbalci.qupath.workshop;

import java.util.LinkedHashMap;
import java.util.Map;

import qupath.lib.common.ColorTools;
import qupath.lib.objects.classes.PathClass;

/**
 * Erişilebilir (Okabe–Ito renk körlüğüne güvenli) ve klasik H&E/DAB sınıf renk paletleri.
 * JavaFX oturumu gerektirmeden PathClass renklerini günceller ve birim testlerinde doğrulanabilir.
 */
public final class WorkshopColorPalettes {

    private WorkshopColorPalettes() {}

    /** Okabe–Ito tabanlı yüksek kontrastlı, kırmızı-yeşil renk körlüğüne güvenli sınıf renkleri. */
    public static Map<String, Integer> colorblindSafePalette() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("Negative", ColorTools.packRGB(0, 114, 178));       // Okabe-Ito Mavi (#0072B2)
        map.put("Positive", ColorTools.packRGB(213, 94, 0));        // Okabe-Ito Vermilyon (#D55E00)
        map.put("1+", ColorTools.packRGB(230, 159, 0));             // Okabe-Ito Kehribar (#E69F00)
        map.put("2+", ColorTools.packRGB(213, 94, 0));              // Okabe-Ito Vermilyon (#D55E00)
        map.put("3+", ColorTools.packRGB(204, 121, 167));           // Okabe-Ito Erguvan (#CC79A7)
        map.put("Tumor", ColorTools.packRGB(213, 94, 0));           // Vermilyon
        map.put("Stroma", ColorTools.packRGB(0, 158, 115));         // Mavimsi Yeşil (#009E73)
        map.put("Immune cells", ColorTools.packRGB(86, 180, 233));  // Gök Mavisi (#56B4E9)
        map.put("Other", ColorTools.packRGB(240, 228, 66));         // Açık Sarı (#F0E442)
        map.put("Ignore*", ColorTools.packRGB(140, 140, 140));      // Nötr Gri
        return map;
    }

    /** QuPath ve H&E / DAB iş akışlarının klasik patoloji sınıf renkleri. */
    public static Map<String, Integer> classicPathologyPalette() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("Negative", ColorTools.packRGB(70, 130, 180));      // Hematoksilen Çelik Mavisi
        map.put("Positive", ColorTools.packRGB(200, 50, 40));       // Klasik Pozitif Kırmızı
        map.put("1+", ColorTools.packRGB(240, 190, 60));            // Zayıf DAB Sarı-Turuncu
        map.put("2+", ColorTools.packRGB(225, 120, 40));            // Orta DAB Turuncu
        map.put("3+", ColorTools.packRGB(175, 35, 35));             // Güçlü DAB Koyu Kırmızı-Kahve
        map.put("Tumor", ColorTools.packRGB(200, 0, 0));            // Tümör Kırmızı
        map.put("Stroma", ColorTools.packRGB(150, 200, 150));       // Stroma Yeşil
        map.put("Immune cells", ColorTools.packRGB(160, 90, 220));  // İmmün Mor
        map.put("Other", ColorTools.packRGB(255, 200, 0));          // Diğer Sarı
        map.put("Ignore*", ColorTools.packRGB(180, 180, 180));      // Dışlanan Gri
        return map;
    }

    /** Seçilen paleti QuPath tekil PathClass örneklerine uygular ve güncellenen sınıf sayısını döndürür. */
    public static int applyPalette(boolean colorblindSafe) {
        Map<String, Integer> palette = colorblindSafe ? colorblindSafePalette() : classicPathologyPalette();
        int updated = 0;
        for (Map.Entry<String, Integer> entry : palette.entrySet()) {
            PathClass pc = PathClass.fromString(entry.getKey());
            if (pc != null) {
                pc.setColor(entry.getValue());
                updated++;
            }
        }
        return updated;
    }
}

