package io.github.sbalci.qupath.workshop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.Property;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/** "Atölye Ayarları" settings window. Opened from Extensions ▸ Atölye ▸ Atölye Ayarları… */
public final class WorkshopSettingsDialog {

    private static Stage stage;
    private static ScrollPane scroll;
    private static VBox sections;
    private static Label warningLabel;
    private static final Map<String, Boolean> EXPANDED_STATE = new LinkedHashMap<>();
    private static final Map<String, TextField> TEXT_FIELDS = new LinkedHashMap<>();

    private static final String[][] THRESHOLD_TRIPLES = new String[][]{
        {"atolye.nuclear1", "atolye.nuclear2", "atolye.nuclear3"},
        {"atolye.membrane1", "atolye.membrane2", "atolye.membrane3"},
        {"atolye.cyto1", "atolye.cyto2", "atolye.cyto3"},
        {"atolye.pixDab1", "atolye.pixDab2", "atolye.pixDab3"}
    };

    /** Choice options for string prefs that should render as a dropdown. */
    private static final Map<String, String[]> CHOICES = new LinkedHashMap<>();
    static {
        // cellposeModel: Membran boya (HER2) Cellpose dedektörü okur. cellposeFamily: Cellpose sihirbazı
        // (yardimci-cellpose-sihirbaz) okur — cpsam (Cellpose-SAM) dahil. İki ayrı anahtar; silmeyin.
        CHOICES.put("atolye.cellposeModel", new String[]{"cyto3", "cyto2", "nuclei"});
        CHOICES.put("atolye.cellposeFamily", new String[]{"cyto3", "cyto2", "nuclei", "cpsam"});
        CHOICES.put("atolye.detectionChannel", new String[]{"Hematoxylin OD", "Optical density sum"});
        CHOICES.put("atolye.exportSeparator", new String[]{"\t", ","});      // label mapped below
        CHOICES.put("atolye.exportScope", new String[]{"image", "project"}); // label mapped below
        CHOICES.put("atolye.exportColumnsMode", new String[]{"all", "subset"}); // label mapped below
    }
    private static String sepLabel(String v) { return "\t".equals(v) ? "TAB (TSV)" : "Virgül (CSV)"; }
    private static String sepValue(String label) { return label.startsWith("TAB") ? "\t" : ","; }

    /** Per-key stored-value → friendly Turkish label maps for CHOICES that aren't the separator. */
    private static final Map<String, Map<String, String>> CHOICE_LABELS = new LinkedHashMap<>();
    static {
        Map<String, String> scope = new LinkedHashMap<>();
        scope.put("image", "Bu görüntü"); scope.put("project", "Tüm proje");
        CHOICE_LABELS.put("atolye.exportScope", scope);
        Map<String, String> cols = new LinkedHashMap<>();
        cols.put("all", "Tüm sütunlar"); cols.put("subset", "Seçili alt küme");
        CHOICE_LABELS.put("atolye.exportColumnsMode", cols);
    }
    private static String choiceLabel(String value, boolean isSep, Map<String, String> labels) {
        if (isSep) return sepLabel(value);
        return (labels != null && labels.containsKey(value)) ? labels.get(value) : value;
    }
    private static String choiceValue(String label, boolean isSep, Map<String, String> labels) {
        if (isSep) return sepValue(label);
        if (labels != null) {
            for (Map.Entry<String, String> e : labels.entrySet())
                if (e.getValue().equals(label)) return e.getKey();
        }
        return label;
    }

    public static void show() {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(WorkshopSettingsDialog::show); return; }
        if (stage != null) { stage.show(); stage.toFront(); return; }

        VBox root = new VBox(10);
        root.setPadding(new Insets(12));

        Label header = new Label("Atölye Ayarları");
        header.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label sub = new Label("Değişiklikler hatırlanır (oturumlar arası). Değerler atölye kalibrasyonudur; "
                + "değiştirmeden önce dikkat edin. Bir satırın üzerine fareyle gelerek açıklamayı okuyabilirsiniz.  "
                + "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm.");
        sub.setWrapText(true);
        sub.setStyle("-fx-text-fill: #555; -fx-font-size: 11px;");

        ChoiceBox<String> preset = new ChoiceBox<>();
        preset.getItems().addAll("Nükleer sayım (Ki-67 / ER-PR)", "Membran sayımı (HER2)", "Sitoplazmik sayım (CD68)");
        preset.getSelectionModel().selectFirst();
        preset.setAccessibleText("Atölye başlangıç ayarı");
        Button applyPreset = new Button("Başlangıç ayarını yükle");
        applyPreset.setTooltip(new Tooltip("İlgili DAB eşiklerini, piksel boyutunu ve hücre genişlemesini atölye varsayılanına döndürür. Klinik karar eşiği yüklemez; küçük bir alanda sayımı denetleyin."));
        applyPreset.setOnAction(ev -> {
            int index = preset.getSelectionModel().getSelectedIndex();
            String prefix = index == 0 ? "nuclear" : index == 1 ? "membrane" : "cyto";
            for (int i = 1; i <= 3; i++) restoreDouble("atolye." + prefix + i);
            restoreDouble("atolye.pixelSize");
            restoreDouble(index == 0 ? "atolye.cellExpansionNuclear" : "atolye.cellExpansionCyto");
            rebuild();
        });
        Button okabeBtn = new Button("🎨 Okabe–Ito paleti (renk körü dostu)");
        okabeBtn.setTooltip(new Tooltip("Sınıf renklerini (Negatif, Pozitif, 1+/2+/3+, Tümör, Stroma, İmmün) kırmızı-yeşil renk körlüğüne güvenli yüksek kontrastlı Okabe–Ito paletine geçirir; ölçüm sayılarını değiştirmez."));
        okabeBtn.setOnAction(ev -> applyVisualPalette(true));
        Button classicBtn = new Button("🔬 Klasik H&E / DAB paleti");
        classicBtn.setTooltip(new Tooltip("Sınıf renklerini standart H&E ve DAB patoloji görünüm renklerine geri döndürür; ölçüm sayılarını değiştirmez."));
        classicBtn.setOnAction(ev -> applyVisualPalette(false));
        javafx.scene.layout.FlowPane presets = new javafx.scene.layout.FlowPane(8, 8, preset, applyPreset, okabeBtn, classicBtn);

        sections = new VBox(8);
        warningLabel = new Label("");
        warningLabel.setWrapText(true);
        warningLabel.setMaxWidth(Double.MAX_VALUE);
        warningLabel.setStyle("-fx-text-fill: #b94a00; -fx-background-color: #fff3cd; -fx-border-color: #e67e22; "
                + "-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-padding: 6px 8px; -fx-font-weight: bold;");
        warningLabel.setVisible(false);
        warningLabel.setManaged(false);

        populateSections();

        Button resetAll = new Button("↺ Tüm atölye varsayılanlarına dön");
        resetAll.setOnAction(ev -> { WorkshopPrefs.resetAll(); rebuild(); });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        CheckBox alwaysTop = new CheckBox("Üstte tut");
        alwaysTop.setSelected(true);
        alwaysTop.selectedProperty().addListener((o, was, now) -> {
            if (stage != null) stage.setAlwaysOnTop(now);
        });
        Button close = new Button("Kapat");
        close.setOnAction(ev -> stage.hide());
        HBox footer = new HBox(10, resetAll, spacer, alwaysTop, close);
        footer.setAlignment(Pos.CENTER_LEFT);

        scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        root.getChildren().addAll(header, sub, presets, scroll, warningLabel, footer);

        stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(alwaysTop.isSelected());
        stage.setTitle("Atölye Ayarları");
        stage.setScene(new Scene(root, 580, 650));
        stage.setMinWidth(520);
        stage.setMinHeight(420);
        stage.show();
    }

    private static void restoreDouble(String key) {
        WorkshopPrefs.setDbl(key, (double) WorkshopPrefs.defaultValue(key));
    }

    private static void populateSections() {
        TEXT_FIELDS.clear();
        sections.getChildren().clear();
        Map<String, List<String>> bySection = new LinkedHashMap<>();
        for (String key : WorkshopPrefs.keys()) {
            bySection.computeIfAbsent(WorkshopPrefs.section(key), s -> new ArrayList<>()).add(key);
        }
        for (Map.Entry<String, List<String>> e : bySection.entrySet()) {
            sections.getChildren().add(buildSection(e.getKey(), e.getValue()));
        }
        validateThresholdOrder();
    }

    private static void rebuild() {
        if (stage == null || scroll == null || sections == null) return;
        double vval = scroll.getVvalue();
        populateSections();
        scroll.setContent(sections);
        Platform.runLater(() -> scroll.setVvalue(vval));
    }

    private static TitledPane buildSection(String section, List<String> keys) {
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(6);
        grid.setPadding(new Insets(8));
        int row = 0;
        for (String key : keys) {
            Label rowLabel = new Label(labelFor(key));
            Label badge = new Label(WorkshopPrefs.isDefault(key) ? "" : "değiştirildi");
            badge.setStyle("-fx-text-fill: #b8860b; -fx-font-size: 10px;");
            Region ctrl = controlFor(key, badge);
            rowLabel.setWrapText(true);
            rowLabel.setMaxWidth(240);
            rowLabel.setLabelFor(ctrl);
            ctrl.setAccessibleText(labelFor(key));
            String tipText = tooltipFor(key);
            if (tipText != null && !tipText.isEmpty()) {
                Tooltip tip = new Tooltip(tipText);
                tip.setWrapText(true);
                tip.setMaxWidth(360);
                rowLabel.setTooltip(tip);
                Tooltip.install(ctrl, tip);
                ctrl.setAccessibleHelp(tipText);
            }
            grid.add(rowLabel, 0, row);
            grid.add(ctrl, 1, row);
            grid.add(badge, 2, row);
            row++;
        }
        Button resetSec = new Button("Sıfırla");
        resetSec.setOnAction(ev -> { WorkshopPrefs.resetSection(section); rebuild(); });
        VBox box = new VBox(6, grid, resetSec);
        TitledPane pane = new TitledPane(section, box);
        boolean defaultExpanded = !WorkshopPrefs.SEC_ADVANCED.equals(section);
        pane.setExpanded(EXPANDED_STATE.getOrDefault(section, defaultExpanded));
        pane.expandedProperty().addListener((o, was, now) -> EXPANDED_STATE.put(section, now));
        return pane;
    }

    private static Region controlFor(String key, Label badge) {
        Property<?> p = WorkshopPrefs.property(key);
        if (CHOICES.containsKey(key)) {
            ChoiceBox<String> cb = new ChoiceBox<>();
            boolean isSep = "atolye.exportSeparator".equals(key);
            Map<String, String> labels = CHOICE_LABELS.get(key);
            for (String opt : CHOICES.get(key)) cb.getItems().add(choiceLabel(opt, isSep, labels));
            String cur = ((StringProperty) p).get();
            cb.setValue(choiceLabel(cur, isSep, labels));
            cb.valueProperty().addListener((o, a, b) -> {
                ((StringProperty) p).set(choiceValue(b, isSep, labels));
                badge.setText(WorkshopPrefs.isDefault(key) ? "" : "değiştirildi");
            });
            return cb;
        }
        if (p instanceof BooleanProperty bp) {
            CheckBox c = new CheckBox();
            c.setSelected(bp.get());
            c.selectedProperty().addListener((o, a, b) -> {
                bp.set(b);
                badge.setText(WorkshopPrefs.isDefault(key) ? "" : "değiştirildi");
            });
            return c;
        }
        TextField tf = new TextField(String.valueOf(p.getValue()));
        tf.setPrefColumnCount(8);
        TEXT_FIELDS.put(key, tf);
        tf.focusedProperty().addListener((o, was, now) -> { if (!now) commit(key, tf, badge); });
        tf.setOnAction(ev -> commit(key, tf, badge));
        return tf;
    }

    private static void commit(String key, TextField tf, Label badge) {
        Property<?> p = WorkshopPrefs.property(key);
        String text = tf.getText() == null ? "" : tf.getText().trim().replace(',', '.');
        try {
            if (p instanceof DoubleProperty dp) {
                double candidate = text.isEmpty() ? (double) WorkshopPrefs.defaultValue(key) : Double.parseDouble(text);
                checkNumber(key, candidate);
                dp.set(candidate);
                tf.setText(String.valueOf(dp.get()));
            } else if (p instanceof IntegerProperty ip) {
                int candidate = text.isEmpty() ? (int) WorkshopPrefs.defaultValue(key) : Integer.parseInt(text);
                checkNumber(key, candidate);
                ip.set(candidate);
                tf.setText(String.valueOf(ip.get()));
            } else if (p instanceof StringProperty sp) {
                sp.set(tf.getText() == null ? "" : tf.getText());
            }
        } catch (IllegalArgumentException nfe) {
            // Revert to the stored value, but SAY so: a silent revert lets a novice believe
            // a mistyped value ("0.2 5", "yirmi") was accepted.
            String rejected = tf.getText();
            tf.setText(String.valueOf(p.getValue()));
            badge.setText("geçersiz sayı — eski değer korundu");
            badge.setStyle("-fx-text-fill: #b94a00; -fx-font-size: 10px; -fx-font-weight: bold;");
            badge.setTooltip(new Tooltip("\"" + rejected + "\" kabul edilmedi. "
                    + "Örnek biçim: 0.25 (ondalık için nokta veya virgül kullanabilirsiniz).\n" + nfe.getMessage()));
            badge.setText("Kaydedilmedi — eski değer korundu");
            tf.setAccessibleHelp(nfe.getMessage());
            warningLabel.setText(labelFor(key) + ": " + (nfe instanceof NumberFormatException
                    ? "Sayı girin; örneğin 0,25. Eski değer korundu." : nfe.getMessage()));
            warningLabel.setVisible(true);
            warningLabel.setManaged(true);
            return;
        }
        badge.setTooltip(null);   // clear a previous "geçersiz sayı" tooltip once a valid value is saved
        tf.setAccessibleHelp(tooltipFor(key));
        badge.setStyle("-fx-text-fill: #b8860b; -fx-font-size: 10px;");
        badge.setText(WorkshopPrefs.isDefault(key) ? "" : "değiştirildi");
        validateThresholdOrder();
    }

    private static void checkNumber(String key, double value) {
        String problem = WorkshopParameterValidation.problem(key, value, other ->
                ((Number) WorkshopPrefs.property(other).getValue()).doubleValue());
        if (problem != null) throw new IllegalArgumentException(problem);
    }

    private static void validateThresholdOrder() {
        boolean anyInvalid = false;
        for (String[] triple : THRESHOLD_TRIPLES) {
            double v1 = WorkshopPrefs.dbl(triple[0], 0.1);
            double v2 = WorkshopPrefs.dbl(triple[1], 0.2);
            double v3 = WorkshopPrefs.dbl(triple[2], 0.3);
            boolean bad = !Double.isFinite(v1) || !Double.isFinite(v2) || !Double.isFinite(v3) || (v1 >= v2) || (v2 >= v3);
            if (bad) anyInvalid = true;
            String style = bad ? "-fx-border-color: #e67e22; -fx-border-width: 1.5px; -fx-border-radius: 3px;" : "";
            for (String k : triple) {
                TextField tf = TEXT_FIELDS.get(k);
                if (tf != null) tf.setStyle(style);
            }
        }
        if (warningLabel != null) {
            if (anyInvalid) {
                warningLabel.setText("⚠ Uyarı: Eşik değerleri 1+ < 2+ < 3+ sırasında olmalıdır");
                warningLabel.setVisible(true);
                warningLabel.setManaged(true);
            } else {
                warningLabel.setText("");
                warningLabel.setVisible(false);
                warningLabel.setManaged(false);
            }
        }
    }

    static String labelFor(String key) {
        return WorkshopMicrocopy.labelFor(key);
    }

    private static void applyVisualPalette(boolean colorblindSafe) {
        int updated = WorkshopColorPalettes.applyPalette(colorblindSafe);
        qupath.lib.gui.QuPathGUI gui = qupath.lib.gui.QuPathGUI.getInstance();
        if (gui != null && gui.getAllViewers() != null) {
            Platform.runLater(() -> gui.getAllViewers().forEach(v -> v.repaint()));
        }
        if (warningLabel != null) {
            warningLabel.setText((colorblindSafe ? "✓ Okabe–Ito renk körlüğüne güvenli palet " : "✓ Klasik H&E / DAB paleti ")
                    + updated + " sınıfa uygulandı (ölçüm sayıları değişmez).");
            warningLabel.setVisible(true);
            warningLabel.setManaged(true);
        }
    }

    static String tooltipFor(String key) {
        return WorkshopMicrocopy.tooltipFor(key);
    }
}
