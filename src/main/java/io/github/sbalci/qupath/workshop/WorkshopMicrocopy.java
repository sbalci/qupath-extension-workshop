package io.github.sbalci.qupath.workshop;

import java.util.List;

/**
 * JavaFX veya PathPrefs oturumu gerektirmeyen çift dilli (TR / EN) arayüz etiketleri
 * ve çift kitleli (🩺 Klinik/Clinical + 💻 Matematik/Computation) ipucu metinleri.
 * Birim testlerinde doğrudan sınanır.
 */
public final class WorkshopMicrocopy {

    private WorkshopMicrocopy() {}

    public static final List<String> KNOWN_KEYS = List.of(
        "atolye.nuclear1", "atolye.nuclear2", "atolye.nuclear3",
        "atolye.membrane1", "atolye.membrane2", "atolye.membrane3",
        "atolye.cyto1", "atolye.cyto2", "atolye.cyto3",
        "atolye.pixDab1", "atolye.pixDab2", "atolye.pixDab3", "atolye.pixHmask",
        "atolye.cellposeModel", "atolye.cellposeDiameter", "atolye.pixelSize",
        "atolye.cellExpansionNuclear", "atolye.cellExpansionCyto",
        "atolye.cellposeFamily", "atolye.cellposeFlow", "atolye.cellposeCellprob",
        "atolye.stardistThreshold", "atolye.stardistPixelSize", "atolye.stardistCellExpansion",
        "atolye.sigma", "atolye.backgroundRadius", "atolye.minArea", "atolye.maxArea",
        "atolye.medianRadius", "atolye.detectionThreshold", "atolye.detectionChannel",
        "atolye.watershed", "atolye.pixScale",
        "atolye.classifierName", "atolye.minObjectArea", "atolye.minHoleArea", "atolye.maxTrainingSamples",
        "atolye.warnNuclearCount", "atolye.warnGenericCount", "atolye.warnTissueAreaMm2",
        "atolye.exportFolder", "atolye.exportSeparator", "atolye.exportScope",
        "atolye.exportDet", "atolye.exportAnn", "atolye.exportGeo",
        "atolye.exportColumnsMode", "atolye.exportColumns", "atolye.exportPrefix",
        "atolye.exportDateSubfolder", "atolye.exportLocation"
    );

    public static String labelFor(String key) {
        return labelFor(key, WorkshopI18n.isEnglish());
    }

    public static String labelFor(String key, boolean english) {
        if (key == null || !KNOWN_KEYS.contains(key)) return key;
        String lookupKey = "pref.label." + key;
        String val = WorkshopI18n.t(lookupKey, english);
        return lookupKey.equals(val) ? key : val;
    }

    public static String tooltipFor(String key) {
        return tooltipFor(key, WorkshopI18n.isEnglish());
    }

    public static String tooltipFor(String key, boolean english) {
        if (key == null || !KNOWN_KEYS.contains(key)) return "";
        String lookupKey = "pref.tip." + key;
        String val = WorkshopI18n.t(lookupKey, english);
        return lookupKey.equals(val) ? "" : val;
    }
}
