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

    /** Choice options for string prefs that should render as a dropdown. */
    private static final Map<String, String[]> CHOICES = new LinkedHashMap<>();
    static {
        CHOICES.put("atolye.cellposeModel", new String[]{"cyto3", "cyto2", "nuclei"});
        CHOICES.put("atolye.detectionChannel", new String[]{"Hematoxylin OD", "Optical density sum"});
        CHOICES.put("atolye.exportSeparator", new String[]{"\t", ","}); // label mapped below
    }
    private static String sepLabel(String v) { return "\t".equals(v) ? "TAB (TSV)" : "Virgül (CSV)"; }
    private static String sepValue(String label) { return label.startsWith("TAB") ? "\t" : ","; }

    public static void show() {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(WorkshopSettingsDialog::show); return; }
        if (stage != null) { stage.show(); stage.toFront(); return; }

        VBox root = new VBox(10);
        root.setPadding(new Insets(12));

        Label header = new Label("Atölye Ayarları");
        header.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label sub = new Label("Değişiklikler hatırlanır (oturumlar arası). Değerler atölye kalibrasyonudur; "
                + "değiştirmeden önce dikkat edin.  ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm.");
        sub.setWrapText(true);
        sub.setStyle("-fx-text-fill: #555; -fx-font-size: 11px;");

        VBox sections = new VBox(8);
        // group keys by section, preserving registry order
        Map<String, List<String>> bySection = new LinkedHashMap<>();
        for (String key : WorkshopPrefs.keys()) {
            bySection.computeIfAbsent(WorkshopPrefs.section(key), s -> new ArrayList<>()).add(key);
        }
        for (Map.Entry<String, List<String>> e : bySection.entrySet()) {
            sections.getChildren().add(buildSection(e.getKey(), e.getValue()));
        }

        Button resetAll = new Button("↺ Tüm atölye varsayılanlarına dön");
        resetAll.setOnAction(ev -> { WorkshopPrefs.resetAll(); rebuild(); });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("Kapat");
        close.setOnAction(ev -> stage.hide());
        HBox footer = new HBox(8, resetAll, spacer, close);
        footer.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        root.getChildren().addAll(header, sub, scroll, footer);

        stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle("Atölye Ayarları");
        stage.setScene(new Scene(root, 560, 620));
        stage.show();
    }

    private static void rebuild() {
        if (stage != null) { stage.close(); stage = null; show(); }
    }

    private static TitledPane buildSection(String section, List<String> keys) {
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(6);
        grid.setPadding(new Insets(8));
        int row = 0;
        for (String key : keys) {
            grid.add(new Label(labelFor(key)), 0, row);
            grid.add(controlFor(key), 1, row);
            Label badge = new Label(WorkshopPrefs.isDefault(key) ? "" : "değiştirildi");
            badge.setStyle("-fx-text-fill: #b8860b; -fx-font-size: 10px;");
            grid.add(badge, 2, row);
            row++;
        }
        Button resetSec = new Button("Sıfırla");
        resetSec.setOnAction(ev -> { WorkshopPrefs.resetSection(section); rebuild(); });
        VBox box = new VBox(6, grid, resetSec);
        TitledPane pane = new TitledPane(section, box);
        pane.setExpanded(!WorkshopPrefs.SEC_ADVANCED.equals(section)); // İleri düzey collapsed
        return pane;
    }

    private static Region controlFor(String key) {
        Property<?> p = WorkshopPrefs.property(key);
        if (CHOICES.containsKey(key)) {
            ChoiceBox<String> cb = new ChoiceBox<>();
            boolean isSep = "atolye.exportSeparator".equals(key);
            for (String opt : CHOICES.get(key)) cb.getItems().add(isSep ? sepLabel(opt) : opt);
            String cur = ((StringProperty) p).get();
            cb.setValue(isSep ? sepLabel(cur) : cur);
            cb.valueProperty().addListener((o, a, b) -> ((StringProperty) p).set(isSep ? sepValue(b) : b));
            return cb;
        }
        if (p instanceof BooleanProperty bp) {
            CheckBox c = new CheckBox();
            c.setSelected(bp.get());
            c.selectedProperty().addListener((o, a, b) -> bp.set(b));
            return c;
        }
        TextField tf = new TextField(String.valueOf(p.getValue()));
        tf.setPrefColumnCount(8);
        tf.focusedProperty().addListener((o, was, now) -> { if (!now) commit(key, tf); });
        tf.setOnAction(ev -> commit(key, tf));
        return tf;
    }

    @SuppressWarnings("unchecked")
    private static void commit(String key, TextField tf) {
        Property<?> p = WorkshopPrefs.property(key);
        String text = tf.getText() == null ? "" : tf.getText().trim().replace(',', '.');
        try {
            if (p instanceof DoubleProperty dp) {
                if (text.isEmpty()) { dp.set((double) WorkshopPrefs.defaultValue(key)); }
                else dp.set(Double.parseDouble(text));
            } else if (p instanceof IntegerProperty ip) {
                if (text.isEmpty()) { ip.set((int) WorkshopPrefs.defaultValue(key)); }
                else ip.set(Integer.parseInt(text));
            } else if (p instanceof StringProperty sp) {
                sp.set(tf.getText() == null ? "" : tf.getText());
            }
        } catch (NumberFormatException nfe) {
            // revert field to current stored value
            tf.setText(String.valueOf(p.getValue()));
        }
        rebuild(); // refresh badges
    }

    private static String labelFor(String key) {
        switch (key) {
            case "atolye.nuclear1": return "Nükleer 1+";
            case "atolye.nuclear2": return "Nükleer 2+";
            case "atolye.nuclear3": return "Nükleer 3+";
            case "atolye.membrane1": return "Membran 1+";
            case "atolye.membrane2": return "Membran 2+";
            case "atolye.membrane3": return "Membran 3+";
            case "atolye.cyto1": return "Sitoplazmik 1+";
            case "atolye.cyto2": return "Sitoplazmik 2+";
            case "atolye.cyto3": return "Sitoplazmik 3+";
            case "atolye.pixDab1": return "Piksel H-score 1+";
            case "atolye.pixDab2": return "Piksel H-score 2+";
            case "atolye.pixDab3": return "Piksel H-score 3+";
            case "atolye.pixHmask": return "Piksel Hematoksilin maskesi";
            case "atolye.cellposeModel": return "Cellpose modeli";
            case "atolye.cellposeDiameter": return "Cellpose çapı (px)";
            case "atolye.pixelSize": return "Piksel boyutu (µm)";
            case "atolye.cellExpansionNuclear": return "Hücre genişlemesi — nükleer (µm)";
            case "atolye.cellExpansionCyto": return "Hücre genişlemesi — sitoplazma (µm)";
            case "atolye.sigma": return "Sigma (µm)";
            case "atolye.backgroundRadius": return "Arka plan yarıçapı (µm)";
            case "atolye.minArea": return "Min alan (µm²)";
            case "atolye.maxArea": return "Maks alan (µm²)";
            case "atolye.medianRadius": return "Medyan filtre (µm)";
            case "atolye.detectionThreshold": return "Çekirdek eşik (OD)";
            case "atolye.detectionChannel": return "Tespit kanalı";
            case "atolye.watershed": return "Watershed bölme";
            case "atolye.pixScale": return "Piksel ölçek (downsample)";
            case "atolye.classifierName": return "Sınıflandırıcı adı";
            case "atolye.minObjectArea": return "Min nesne alanı (µm²)";
            case "atolye.minHoleArea": return "Min boşluk alanı (µm²)";
            case "atolye.warnNuclearCount": return "Uyarı: düşük çekirdek (Nielsen)";
            case "atolye.warnGenericCount": return "Uyarı: düşük hücre";
            case "atolye.warnTissueAreaMm2": return "Uyarı: küçük doku (mm²)";
            case "atolye.exportFolder": return "Dışa aktarma klasörü";
            case "atolye.exportSeparator": return "Ayraç";
            default: return key;
        }
    }
}
