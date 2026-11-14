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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/** Bilingual ("Atölye Ayarları" / "Workshop Settings") settings window. */
public final class WorkshopSettingsDialog {

    private static Stage stage;
    private static ScrollPane scroll;
    private static VBox sections;
    private static Label warningLabel;
    private static Label headerLabel;
    private static Label subLabel;
    private static Label langLabel;
    private static ChoiceBox<String> langChoice;
    private static ChoiceBox<String> presetChoice;
    private static Button applyPresetBtn;
    private static Button okabeBtn;
    private static Button classicBtn;
    private static Button resetAllBtn;
    private static CheckBox alwaysTopBox;
    private static Button closeBtn;
    private static boolean updatingLangChoice = false;

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
        CHOICES.put("atolye.cellposeModel", new String[]{"cyto3", "cyto2", "nuclei"});
        CHOICES.put("atolye.cellposeFamily", new String[]{"cyto3", "cyto2", "nuclei", "cpsam"});
        CHOICES.put("atolye.detectionChannel", new String[]{"Hematoxylin OD", "Optical density sum"});
        CHOICES.put("atolye.exportSeparator", new String[]{"\t", ","});
        CHOICES.put("atolye.exportScope", new String[]{"image", "project"});
        CHOICES.put("atolye.exportColumnsMode", new String[]{"all", "subset"});
    }

    private static Map<String, String> choiceMapFor(String key) {
        if ("atolye.exportSeparator".equals(key)) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("\t", WorkshopI18n.t("settings.choice.sepTab"));
            m.put(",", WorkshopI18n.t("settings.choice.sepComma"));
            return m;
        }
        if ("atolye.exportScope".equals(key)) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("image", WorkshopI18n.t("settings.choice.scopeImage"));
            m.put("project", WorkshopI18n.t("settings.choice.scopeProject"));
            return m;
        }
        if ("atolye.exportColumnsMode".equals(key)) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("all", WorkshopI18n.t("settings.choice.colsAll"));
            m.put("subset", WorkshopI18n.t("settings.choice.colsSubset"));
            return m;
        }
        return null;
    }

    private static String choiceLabel(String value, Map<String, String> labels) {
        return (labels != null && labels.containsKey(value)) ? labels.get(value) : value;
    }

    private static String choiceValue(String label, Map<String, String> labels) {
        if (labels != null) {
            for (Map.Entry<String, String> e : labels.entrySet()) {
                if (e.getValue().equals(label)) return e.getKey();
            }
        }
        return label;
    }

    public static void show() {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(WorkshopSettingsDialog::show); return; }
        if (stage != null) {
            refreshChrome();
            rebuild();
            stage.show();
            stage.toFront();
            return;
        }

        VBox root = new VBox(10);
        root.setPadding(new Insets(12));

        headerLabel = new Label();
        headerLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        langLabel = new Label();
        langChoice = new ChoiceBox<>();
        langChoice.getItems().addAll(WorkshopI18n.t("settings.langTr"), WorkshopI18n.t("settings.langEn"));
        langChoice.getSelectionModel().select(WorkshopI18n.isEnglish() ? 1 : 0);
        langChoice.getSelectionModel().selectedIndexProperty().addListener((obs, oldIdx, newIdx) -> {
            if (updatingLangChoice || newIdx == null) return;
            WorkshopI18n.setLanguage(newIdx.intValue() == 1 ? "en" : "tr");
        });
        WorkshopI18n.addListener(() -> Platform.runLater(() -> {
            if (stage == null) return;
            updatingLangChoice = true;
            try {
                langChoice.getSelectionModel().select(WorkshopI18n.isEnglish() ? 1 : 0);
            } finally {
                updatingLangChoice = false;
            }
            refreshChrome();
            rebuild();
        }));
        HBox headerRow = new HBox(8, headerLabel, topSpacer, langLabel, langChoice);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        subLabel = new Label();
        subLabel.setWrapText(true);
        subLabel.setStyle("-fx-text-fill: #555; -fx-font-size: 11px;");

        presetChoice = new ChoiceBox<>();
        applyPresetBtn = new Button();
        applyPresetBtn.setOnAction(ev -> {
            int index = Math.max(0, presetChoice.getSelectionModel().getSelectedIndex());
            String prefix = index == 0 ? "nuclear" : index == 1 ? "membrane" : "cyto";
            for (int i = 1; i <= 3; i++) restoreDouble("atolye." + prefix + i);
            restoreDouble("atolye.pixelSize");
            restoreDouble(index == 0 ? "atolye.cellExpansionNuclear" : "atolye.cellExpansionCyto");
            rebuild();
        });
        okabeBtn = new Button();
        okabeBtn.setOnAction(ev -> applyVisualPalette(true));
        classicBtn = new Button();
        classicBtn.setOnAction(ev -> applyVisualPalette(false));
        FlowPane presets = new FlowPane(8, 8, presetChoice, applyPresetBtn, okabeBtn, classicBtn);

        sections = new VBox(8);
        warningLabel = new Label("");
        warningLabel.setWrapText(true);
        warningLabel.setMaxWidth(Double.MAX_VALUE);
        warningLabel.setStyle("-fx-text-fill: #b94a00; -fx-background-color: #fff3cd; -fx-border-color: #e67e22; "
                + "-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-padding: 6px 8px; -fx-font-weight: bold;");
        warningLabel.setVisible(false);
        warningLabel.setManaged(false);

        resetAllBtn = new Button();
        resetAllBtn.setOnAction(ev -> { WorkshopPrefs.resetAll(); rebuild(); });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        alwaysTopBox = new CheckBox();
        alwaysTopBox.setSelected(true);
        alwaysTopBox.selectedProperty().addListener((o, was, now) -> {
            if (stage != null) stage.setAlwaysOnTop(now);
        });
        closeBtn = new Button();
        closeBtn.setOnAction(ev -> stage.hide());
        HBox footer = new HBox(10, resetAllBtn, spacer, alwaysTopBox, closeBtn);
        footer.setAlignment(Pos.CENTER_LEFT);

        scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        root.getChildren().addAll(headerRow, subLabel, presets, scroll, warningLabel, footer);

        stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(alwaysTopBox.isSelected());
        stage.setScene(new Scene(root, 600, 660));
        stage.setMinWidth(520);
        stage.setMinHeight(420);

        refreshChrome();
        populateSections();
        stage.show();
    }

    private static void refreshChrome() {
        if (stage != null) stage.setTitle(WorkshopI18n.t("settings.title"));
        if (headerLabel != null) headerLabel.setText(WorkshopI18n.t("settings.title"));
        if (langLabel != null) langLabel.setText(WorkshopI18n.t("settings.langLabel"));
        if (subLabel != null) subLabel.setText(WorkshopI18n.t("settings.subtitle"));
        if (presetChoice != null) {
            int idx = Math.max(0, presetChoice.getSelectionModel().getSelectedIndex());
            presetChoice.getItems().setAll(
                    WorkshopI18n.t("settings.preset.nuclear"),
                    WorkshopI18n.t("settings.preset.membrane"),
                    WorkshopI18n.t("settings.preset.cyto")
            );
            presetChoice.getSelectionModel().select(Math.min(idx, 2));
            presetChoice.setAccessibleText(WorkshopI18n.t("settings.preset.accessible"));
        }
        if (applyPresetBtn != null) {
            applyPresetBtn.setText(WorkshopI18n.t("settings.applyPreset"));
            applyPresetBtn.setTooltip(new Tooltip(WorkshopI18n.t("settings.applyPreset.tip")));
        }
        if (okabeBtn != null) {
            okabeBtn.setText(WorkshopI18n.t("settings.okabe"));
            okabeBtn.setTooltip(new Tooltip(WorkshopI18n.t("settings.okabe.tip")));
        }
        if (classicBtn != null) {
            classicBtn.setText(WorkshopI18n.t("settings.classic"));
            classicBtn.setTooltip(new Tooltip(WorkshopI18n.t("settings.classic.tip")));
        }
        if (resetAllBtn != null) resetAllBtn.setText(WorkshopI18n.t("settings.resetAll"));
        if (alwaysTopBox != null) alwaysTopBox.setText(WorkshopI18n.t("settings.alwaysTop"));
        if (closeBtn != null) closeBtn.setText(WorkshopI18n.t("settings.close"));
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
        String modifiedWord = WorkshopI18n.t("settings.badge.modified");
        for (String key : keys) {
            Label rowLabel = new Label(labelFor(key));
            Label badge = new Label(WorkshopPrefs.isDefault(key) ? "" : modifiedWord);
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
        Button resetSec = new Button(WorkshopI18n.t("settings.resetSection"));
        resetSec.setOnAction(ev -> { WorkshopPrefs.resetSection(section); rebuild(); });
        VBox box = new VBox(6, grid, resetSec);
        String localizedSection = WorkshopI18n.t("section." + section);
        TitledPane pane = new TitledPane(localizedSection.startsWith("section.") ? section : localizedSection, box);
        boolean defaultExpanded = !WorkshopPrefs.SEC_ADVANCED.equals(section);
        pane.setExpanded(EXPANDED_STATE.getOrDefault(section, defaultExpanded));
        pane.expandedProperty().addListener((o, was, now) -> EXPANDED_STATE.put(section, now));
        return pane;
    }

    private static Region controlFor(String key, Label badge) {
        Property<?> p = WorkshopPrefs.property(key);
        if (CHOICES.containsKey(key)) {
            ChoiceBox<String> cb = new ChoiceBox<>();
            Map<String, String> labels = choiceMapFor(key);
            for (String opt : CHOICES.get(key)) cb.getItems().add(choiceLabel(opt, labels));
            String cur = ((StringProperty) p).get();
            cb.setValue(choiceLabel(cur, labels));
            cb.valueProperty().addListener((o, a, b) -> {
                if (b == null) return;
                ((StringProperty) p).set(choiceValue(b, labels));
                badge.setText(WorkshopPrefs.isDefault(key) ? "" : WorkshopI18n.t("settings.badge.modified"));
            });
            return cb;
        }
        if (p instanceof BooleanProperty bp) {
            CheckBox c = new CheckBox();
            c.setSelected(bp.get());
            c.selectedProperty().addListener((o, a, b) -> {
                bp.set(b);
                badge.setText(WorkshopPrefs.isDefault(key) ? "" : WorkshopI18n.t("settings.badge.modified"));
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
            String rejected = tf.getText();
            tf.setText(String.valueOf(p.getValue()));
            badge.setText(WorkshopI18n.t("settings.badge.rejected"));
            badge.setStyle("-fx-text-fill: #b94a00; -fx-font-size: 10px; -fx-font-weight: bold;");
            badge.setTooltip(new Tooltip(WorkshopI18n.tf("settings.rejected.tip", rejected, String.valueOf(nfe.getMessage()))));
            tf.setAccessibleHelp(nfe.getMessage());
            warningLabel.setText(labelFor(key) + ": " + (nfe instanceof NumberFormatException
                    ? WorkshopI18n.t("settings.rejected.nan") : nfe.getMessage()));
            warningLabel.setVisible(true);
            warningLabel.setManaged(true);
            return;
        }
        badge.setTooltip(null);
        tf.setAccessibleHelp(tooltipFor(key));
        badge.setStyle("-fx-text-fill: #b8860b; -fx-font-size: 10px;");
        badge.setText(WorkshopPrefs.isDefault(key) ? "" : WorkshopI18n.t("settings.badge.modified"));
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
                warningLabel.setText(WorkshopI18n.t("settings.warn.thresholdOrder"));
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
            warningLabel.setText(WorkshopI18n.tf(
                    colorblindSafe ? "settings.palette.okabeApplied" : "settings.palette.classicApplied",
                    updated));
            warningLabel.setVisible(true);
            warningLabel.setManaged(true);
        }
    }

    static String tooltipFor(String key) {
        return WorkshopMicrocopy.tooltipFor(key);
    }
}
