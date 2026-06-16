/*
 * Atölye (workshop) adjustable parameters, persisted via QuPath's PathPrefs.
 *
 * Scripts read these reflectively (Class.forName) and fall back to the SAME
 * literal default embedded in the script, so they remain byte-identical across
 * handson/scripts and the JAR resources and run even when the extension is absent.
 */
package io.github.sbalci.qupath.workshop;

import java.util.LinkedHashMap;
import java.util.Map;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.Property;
import javafx.beans.property.StringProperty;

import qupath.lib.gui.prefs.PathPrefs;

public final class WorkshopPrefs {

    private WorkshopPrefs() {}

    /** key -> JavaFX Property (insertion order = display order). */
    private static final Map<String, Property<?>> PROPS = new LinkedHashMap<>();
    /** key -> default value (for reset + lint introspection). */
    private static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();
    /** key -> section label (for grouping + per-section reset). */
    private static final Map<String, String> SECTIONS = new LinkedHashMap<>();

    private static DoubleProperty regD(String section, String key, double def) {
        DoubleProperty p = PathPrefs.createPersistentPreference(key, def);
        PROPS.put(key, p); DEFAULTS.put(key, def); SECTIONS.put(key, section);
        return p;
    }
    private static IntegerProperty regI(String section, String key, int def) {
        IntegerProperty p = PathPrefs.createPersistentPreference(key, def);
        PROPS.put(key, p); DEFAULTS.put(key, def); SECTIONS.put(key, section);
        return p;
    }
    private static StringProperty regS(String section, String key, String def) {
        StringProperty p = PathPrefs.createPersistentPreference(key, def);
        PROPS.put(key, p); DEFAULTS.put(key, def); SECTIONS.put(key, section);
        return p;
    }
    private static BooleanProperty regB(String section, String key, boolean def) {
        BooleanProperty p = PathPrefs.createPersistentPreference(key, def);
        PROPS.put(key, p); DEFAULTS.put(key, def); SECTIONS.put(key, section);
        return p;
    }

    // Section labels (Turkish; used by the settings window).
    public static final String SEC_THRESHOLDS = "Eşikler (DAB OD)";
    public static final String SEC_DETECTION  = "Hücre tespiti";
    public static final String SEC_ADVANCED   = "İleri düzey";
    public static final String SEC_CLASSIFIER = "Sınıflandırıcı";
    public static final String SEC_STARDIST   = "StarDist (Modül 8)";
    public static final String SEC_WARN       = "Uyarı eşikleri";
    public static final String SEC_EXPORT     = "Dışa aktarma";

    // --- Eşikler (DAB OD) ---
    public static final DoubleProperty nuclear1 = regD(SEC_THRESHOLDS, "atolye.nuclear1", 0.20);
    public static final DoubleProperty nuclear2 = regD(SEC_THRESHOLDS, "atolye.nuclear2", 0.40);
    public static final DoubleProperty nuclear3 = regD(SEC_THRESHOLDS, "atolye.nuclear3", 0.60);
    public static final DoubleProperty membrane1 = regD(SEC_THRESHOLDS, "atolye.membrane1", 0.15);
    public static final DoubleProperty membrane2 = regD(SEC_THRESHOLDS, "atolye.membrane2", 0.40);
    public static final DoubleProperty membrane3 = regD(SEC_THRESHOLDS, "atolye.membrane3", 0.70);
    public static final DoubleProperty cyto1 = regD(SEC_THRESHOLDS, "atolye.cyto1", 0.10);
    public static final DoubleProperty cyto2 = regD(SEC_THRESHOLDS, "atolye.cyto2", 0.20);
    public static final DoubleProperty cyto3 = regD(SEC_THRESHOLDS, "atolye.cyto3", 0.35);
    public static final DoubleProperty pixDab1 = regD(SEC_THRESHOLDS, "atolye.pixDab1", 0.10);
    public static final DoubleProperty pixDab2 = regD(SEC_THRESHOLDS, "atolye.pixDab2", 0.30);
    public static final DoubleProperty pixDab3 = regD(SEC_THRESHOLDS, "atolye.pixDab3", 0.60);
    public static final DoubleProperty pixHmask = regD(SEC_THRESHOLDS, "atolye.pixHmask", 0.05);

    // --- Hücre tespiti ---
    public static final StringProperty  cellposeModel = regS(SEC_DETECTION, "atolye.cellposeModel", "cyto3");
    public static final IntegerProperty cellposeDiameter = regI(SEC_DETECTION, "atolye.cellposeDiameter", 25);
    public static final DoubleProperty  pixelSize = regD(SEC_DETECTION, "atolye.pixelSize", 0.5);
    public static final DoubleProperty  cellExpansionNuclear = regD(SEC_DETECTION, "atolye.cellExpansionNuclear", 5.0);
    public static final DoubleProperty  cellExpansionCyto = regD(SEC_DETECTION, "atolye.cellExpansionCyto", 7.0);

    // --- StarDist (Modül 8) ---
    public static final DoubleProperty stardistThreshold     = regD(SEC_STARDIST, "atolye.stardistThreshold", 0.5);
    public static final DoubleProperty stardistPixelSize     = regD(SEC_STARDIST, "atolye.stardistPixelSize", 0.5);
    public static final DoubleProperty stardistCellExpansion = regD(SEC_STARDIST, "atolye.stardistCellExpansion", 5.0);

    // --- İleri düzey (detection geometry + toggles) ---
    public static final DoubleProperty  sigma = regD(SEC_ADVANCED, "atolye.sigma", 1.5);
    public static final DoubleProperty  backgroundRadius = regD(SEC_ADVANCED, "atolye.backgroundRadius", 8.0);
    public static final DoubleProperty  minArea = regD(SEC_ADVANCED, "atolye.minArea", 10.0);
    public static final DoubleProperty  maxArea = regD(SEC_ADVANCED, "atolye.maxArea", 400.0);
    public static final DoubleProperty  medianRadius = regD(SEC_ADVANCED, "atolye.medianRadius", 0.0);
    public static final DoubleProperty  detectionThreshold = regD(SEC_ADVANCED, "atolye.detectionThreshold", 0.1);
    public static final StringProperty  detectionChannel = regS(SEC_ADVANCED, "atolye.detectionChannel", "Hematoxylin OD");
    public static final BooleanProperty watershed = regB(SEC_ADVANCED, "atolye.watershed", true);
    public static final DoubleProperty  pixScale = regD(SEC_ADVANCED, "atolye.pixScale", 1.0);

    // --- Sınıflandırıcı (M6/M7) ---
    public static final StringProperty classifierName = regS(SEC_CLASSIFIER, "atolye.classifierName", "tumor-stroma-RF");
    public static final DoubleProperty minObjectArea = regD(SEC_CLASSIFIER, "atolye.minObjectArea", 10000.0);
    public static final DoubleProperty minHoleArea = regD(SEC_CLASSIFIER, "atolye.minHoleArea", 5000.0);

    // --- Uyarı eşikleri (display-only) ---
    public static final IntegerProperty warnNuclearCount = regI(SEC_WARN, "atolye.warnNuclearCount", 500);
    public static final IntegerProperty warnGenericCount = regI(SEC_WARN, "atolye.warnGenericCount", 200);
    public static final DoubleProperty  warnTissueAreaMm2 = regD(SEC_WARN, "atolye.warnTissueAreaMm2", 1.0);
    public static final IntegerProperty ciMinCells = regI(SEC_WARN, "atolye.ciMinCells", 30);
    public static final DoubleProperty  ciZ = regD(SEC_WARN, "atolye.ciZ", 1.96);

    // --- Dışa aktarma (M9) ---
    public static final StringProperty exportFolder = regS(SEC_EXPORT, "atolye.exportFolder", "exports");
    // Default is a real TAB character ("\t"); UI offers TAB/CSV. Filenames keep .tsv extension regardless.
    public static final StringProperty exportSeparator = regS(SEC_EXPORT, "atolye.exportSeparator", "\t");

    // ---- Reflective-friendly accessors (called from Groovy scripts) ----

    public static double dbl(String key, double dflt) {
        Property<?> p = PROPS.get(key);
        return (p instanceof DoubleProperty) ? ((DoubleProperty) p).get() : dflt;
    }
    public static int intg(String key, int dflt) {
        Property<?> p = PROPS.get(key);
        return (p instanceof IntegerProperty) ? ((IntegerProperty) p).get() : dflt;
    }
    public static String str(String key, String dflt) {
        Property<?> p = PROPS.get(key);
        return (p instanceof StringProperty) ? ((StringProperty) p).get() : dflt;
    }
    public static boolean bool(String key, boolean dflt) {
        Property<?> p = PROPS.get(key);
        return (p instanceof BooleanProperty) ? ((BooleanProperty) p).get() : dflt;
    }

    /** Used by the "Bu eşikleri varsayılan yap" button in yardimci-esik-ayarla. */
    public static void setDbl(String key, double value) {
        Property<?> p = PROPS.get(key);
        if (p instanceof DoubleProperty) ((DoubleProperty) p).set(value);
    }

    // ---- Introspection + reset (used by the settings window and lint) ----

    public static Iterable<String> keys() { return PROPS.keySet(); }
    public static String section(String key) { return SECTIONS.get(key); }
    public static Object defaultValue(String key) { return DEFAULTS.get(key); }
    public static Property<?> property(String key) { return PROPS.get(key); }
    public static Map<String, Object> defaults() { return new LinkedHashMap<>(DEFAULTS); }

    public static boolean isDefault(String key) {
        Property<?> p = PROPS.get(key);
        Object def = DEFAULTS.get(key);
        return p != null && p.getValue() != null && p.getValue().equals(def);
    }

    private static void resetKey(String key) {
        Property<?> p = PROPS.get(key);
        Object def = DEFAULTS.get(key);
        if (p == null) return;
        if (p instanceof DoubleProperty) ((DoubleProperty) p).set((double) def);
        else if (p instanceof IntegerProperty) ((IntegerProperty) p).set((int) def);
        else if (p instanceof BooleanProperty) ((BooleanProperty) p).set((boolean) def);
        else if (p instanceof StringProperty) ((StringProperty) p).set((String) def);
    }

    public static void resetAll() {
        for (String k : PROPS.keySet()) resetKey(k);
    }
    public static void resetSection(String section) {
        for (String k : PROPS.keySet()) {
            if (section.equals(SECTIONS.get(k))) resetKey(k);
        }
    }
}
