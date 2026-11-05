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

        root.getChildren().addAll(header, sub, scroll, warningLabel, footer);

        stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(alwaysTop.isSelected());
        stage.setTitle("Atölye Ayarları");
        stage.setScene(new Scene(root, 580, 650));
        stage.show();
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
            String tipText = tooltipFor(key);
            if (tipText != null && !tipText.isEmpty()) {
                Tooltip tip = new Tooltip(tipText);
                tip.setWrapText(true);
                tip.setMaxWidth(360);
                rowLabel.setTooltip(tip);
                Tooltip.install(ctrl, tip);
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
                if (text.isEmpty()) { dp.set((double) WorkshopPrefs.defaultValue(key)); }
                else dp.set(Double.parseDouble(text));
                tf.setText(String.valueOf(dp.get()));
            } else if (p instanceof IntegerProperty ip) {
                if (text.isEmpty()) { ip.set((int) WorkshopPrefs.defaultValue(key)); }
                else ip.set(Integer.parseInt(text));
                tf.setText(String.valueOf(ip.get()));
            } else if (p instanceof StringProperty sp) {
                sp.set(tf.getText() == null ? "" : tf.getText());
            }
        } catch (NumberFormatException nfe) {
            // revert field to current stored value
            tf.setText(String.valueOf(p.getValue()));
        }
        badge.setText(WorkshopPrefs.isDefault(key) ? "" : "değiştirildi");
        validateThresholdOrder();
    }

    private static void validateThresholdOrder() {
        boolean anyInvalid = false;
        for (String[] triple : THRESHOLD_TRIPLES) {
            double v1 = WorkshopPrefs.dbl(triple[0], 0.1);
            double v2 = WorkshopPrefs.dbl(triple[1], 0.2);
            double v3 = WorkshopPrefs.dbl(triple[2], 0.3);
            boolean bad = (v1 >= v2) || (v2 >= v3);
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
            case "atolye.pixHmask": return "Piksel Hematoksilen maskesi";
            case "atolye.cellposeModel": return "Cellpose modeli";
            case "atolye.cellposeDiameter": return "Cellpose çapı (px)";
            case "atolye.cellposeFamily": return "Cellpose model ailesi";
            case "atolye.cellposeFlow": return "Cellpose akış eşiği (flow)";
            case "atolye.cellposeCellprob": return "Cellpose hücre olasılığı (cellprob)";
            case "atolye.stardistThreshold": return "StarDist olasılık eşiği";
            case "atolye.stardistPixelSize": return "StarDist piksel boyutu (µm)";
            case "atolye.stardistCellExpansion": return "StarDist hücre genişlemesi (µm)";
            case "atolye.pixelSize": return "Piksel boyutu (µm)";
            case "atolye.cellExpansionNuclear": return "Hücre genişlemesi — nükleer (µm)";
            case "atolye.cellExpansionCyto": return "Hücre genişlemesi — sitoplazma (µm)";
            case "atolye.sigma": return "Sigma (µm)";
            case "atolye.backgroundRadius": return "Arka plan yarıçapı (µm)";
            case "atolye.minArea": return "En küçük alan (µm²)";
            case "atolye.maxArea": return "En büyük alan (µm²)";
            case "atolye.medianRadius": return "Medyan filtre (µm)";
            case "atolye.detectionThreshold": return "Çekirdek eşiği (OD)";
            case "atolye.detectionChannel": return "Tespit kanalı";
            case "atolye.watershed": return "Watershed ile bölme";
            case "atolye.pixScale": return "Piksel ölçeği (alt örnekleme)";
            case "atolye.classifierName": return "Sınıflandırıcı adı";
            case "atolye.minObjectArea": return "En küçük nesne alanı (µm²)";
            case "atolye.minHoleArea": return "En küçük boşluk alanı (µm²)";
            case "atolye.maxTrainingSamples": return "En fazla eğitim örneği (0 = sınırsız)";
            case "atolye.warnNuclearCount": return "Uyarı: düşük çekirdek sayısı (Nielsen)";
            case "atolye.warnGenericCount": return "Uyarı: düşük hücre sayısı";
            case "atolye.warnTissueAreaMm2": return "Uyarı: küçük doku (mm²)";
            case "atolye.exportFolder": return "Dışa aktarma klasörü";
            case "atolye.exportSeparator": return "Ayraç";
            case "atolye.exportScope": return "Varsayılan kapsam";
            case "atolye.exportDet": return "Dışa aktar: tespit TSV";
            case "atolye.exportAnn": return "Dışa aktar: anotasyon TSV";
            case "atolye.exportGeo": return "Dışa aktar: anotasyon GeoJSON";
            case "atolye.exportColumnsMode": return "Sütun seçimi";
            case "atolye.exportColumns": return "Seçili sütunlar (| ile)";
            case "atolye.exportPrefix": return "Dosya adı öneki";
            case "atolye.exportDateSubfolder": return "Tarihli alt klasör";
            case "atolye.exportLocation": return "Kayıt yeri (boş = proje/exports)";
            default: return key;
        }
    }

    static String tooltipFor(String key) {
        switch (key) {
            case "atolye.nuclear1": return "Nükleer DAB optik yoğunluk (OD) 1+ (zayıf pozitif) alt eşiği. Tipik aralık: 0.10–0.25 (varsayılan: 0.20).";
            case "atolye.nuclear2": return "Nükleer DAB optik yoğunluk (OD) 2+ (orta pozitif) alt eşiği. Tipik aralık: 0.30–0.50 (varsayılan: 0.40).";
            case "atolye.nuclear3": return "Nükleer DAB optik yoğunluk (OD) 3+ (güçlü pozitif) alt eşiği. Tipik aralık: 0.50–0.80 (varsayılan: 0.60).";
            case "atolye.membrane1": return "Membran DAB optik yoğunluk (OD) 1+ (zayıf pozitif) alt eşiği (HER2). Tipik aralık: 0.10–0.25 (varsayılan: 0.15).";
            case "atolye.membrane2": return "Membran DAB optik yoğunluk (OD) 2+ (orta pozitif) alt eşiği (HER2). Tipik aralık: 0.30–0.50 (varsayılan: 0.40).";
            case "atolye.membrane3": return "Membran DAB optik yoğunluk (OD) 3+ (güçlü pozitif) alt eşiği (HER2). Tipik aralık: 0.55–0.85 (varsayılan: 0.70).";
            case "atolye.cyto1": return "Sitoplazmik DAB optik yoğunluk (OD) 1+ (zayıf pozitif) alt eşiği (CD68). Tipik aralık: 0.05–0.15 (varsayılan: 0.10).";
            case "atolye.cyto2": return "Sitoplazmik DAB optik yoğunluk (OD) 2+ (orta pozitif) alt eşiği (CD68). Tipik aralık: 0.15–0.30 (varsayılan: 0.20).";
            case "atolye.cyto3": return "Sitoplazmik DAB optik yoğunluk (OD) 3+ (güçlü pozitif) alt eşiği (CD68). Tipik aralık: 0.30–0.50 (varsayılan: 0.35).";
            case "atolye.pixDab1": return "Piksel tabanlı H-score için DAB 1+ (zayıf) optik yoğunluk alt eşiği. Tipik aralık: 0.08–0.15 (varsayılan: 0.10).";
            case "atolye.pixDab2": return "Piksel tabanlı H-score için DAB 2+ (orta) optik yoğunluk alt eşiği. Tipik aralık: 0.20–0.40 (varsayılan: 0.30).";
            case "atolye.pixDab3": return "Piksel tabanlı H-score için DAB 3+ (güçlü) optik yoğunluk alt eşiği. Tipik aralık: 0.45–0.75 (varsayılan: 0.60).";
            case "atolye.pixHmask": return "Piksel tabanlı H-score'da çekirdek/doku varlığını doğrulayan Hematoksilen OD alt eşiği (boş arka planı dışlar). Tipik aralık: 0.03–0.10 (varsayılan: 0.05).";
            case "atolye.cellposeModel": return "Membran boya (HER2) modülünde Cellpose için kullanılacak ön-eğitimli model (cyto3: genel hücre, cyto2: önceki sürüm, nuclei: yalnız çekirdek).";
            case "atolye.cellposeDiameter": return "Cellpose'un beklediği ortalama hücre çapı (piksel cinsinden; 0 girilirse otomatik tahmin edilir). Tipik aralık: 15–40 px (varsayılan: 25).";
            case "atolye.cellposeFamily": return "Cellpose sihirbazında (Yardımcılar) seçilen model ailesi (cyto3, cyto2, nuclei veya cpsam).";
            case "atolye.cellposeFlow": return "Cellpose akış hatası eşiği (flow threshold). Daha yüksek değer daha fazla hücre kabul eder. Tipik aralık: 0.2–0.8 (varsayılan: 0.4).";
            case "atolye.cellposeCellprob": return "Cellpose hücre olasılık eşiği (cellprob). Düşürülürse soluk hücreler de dahil edilir; yükseltilirse yalnız belirgin hücreler alınır. Tipik aralık: -2.0 ile +2.0 (varsayılan: 0.0).";
            case "atolye.stardistThreshold": return "StarDist çekirdek tespiti olasılık eşiği (QuANTUM cTCF). Yükseltildikçe yanlış pozitifler azalır. Tipik aralık: 0.3–0.7 (varsayılan: 0.5).";
            case "atolye.stardistPixelSize": return "StarDist çıkarımında kullanılacak piksel boyutu (µm/px). 0.5 µm ≈ 20× büyütme çözünürlüğüdür. Tipik aralık: 0.25–1.0 µm (varsayılan: 0.5).";
            case "atolye.stardistCellExpansion": return "StarDist ile bulunan çekirdek sınırından dışa doğru tahmin edilen sitoplazma halkası genişliği (µm). Tipik aralık: 3.0–8.0 µm (varsayılan: 5.0).";
            case "atolye.pixelSize": return "Klasik hücre tespitinde (Watershed) görüntünün işleneceği piksel boyutu (µm). Küçük değer daha ayrıntılı ama yavaştır. Tipik aralık: 0.25–1.0 µm (varsayılan: 0.5).";
            case "atolye.cellExpansionNuclear": return "Nükleer boya ve H&E modüllerinde çekirdek etrafına eklenen tahmini sitoplazma sınırı (µm). Tipik aralık: 3.0–7.0 µm (varsayılan: 5.0).";
            case "atolye.cellExpansionCyto": return "Sitoplazmik (CD68) ve membran (HER2) modüllerinde çekirdek etrafına eklenen sitoplazma/membran genişlemesi (µm). Tipik aralık: 5.0–10.0 µm (varsayılan: 7.0).";
            case "atolye.sigma": return "Gaussian yumuşatma (bulanıklaştırma) yarıçapı (µm). Çekirdek içi beneklenmeyi azaltıp tek çekirdeğin parçalanmasını önler. Tipik aralık: 1.0–2.5 µm (varsayılan: 1.5).";
            case "atolye.backgroundRadius": return "Yerel arka plan çıkarma yarıçapı (µm). Düzensiz boyanma aydınlatmasını dengeler (0 = kapalı). Tipik aralık: 6.0–15.0 µm (varsayılan: 8.0).";
            case "atolye.minArea": return "Kabul edilecek en küçük çekirdek alanı (µm²). Daha küçük kırıntı ve artefaktlar elenir. Tipik aralık: 5.0–25.0 µm² (varsayılan: 10.0).";
            case "atolye.maxArea": return "Kabul edilecek en büyük çekirdek alanı (µm²). Daha büyük kümeler elenir veya bölünür. Tipik aralık: 200.0–600.0 µm² (varsayılan: 400.0).";
            case "atolye.medianRadius": return "Ön işlemede medyan filtre yarıçapı (µm). Tuz-biber gürültüsünü azaltır (0 = kapalı). Tipik aralık: 0.0–2.0 µm (varsayılan: 0.0).";
            case "atolye.detectionThreshold": return "Çekirdek varlığını kabul etmek için gereken en düşük optik yoğunluk (OD) eşiği. Düşürülürse soluk çekirdekler de yakalanır. Tipik aralık: 0.05–0.25 (varsayılan: 0.1).";
            case "atolye.detectionChannel": return "Çekirdek tespitinin yapılacağı renk dekonvolüsyon kanalı (Hematoxylin OD veya Optical density sum).";
            case "atolye.watershed": return "Birbirine değen komşu çekirdekleri şekil ve yoğunluk vadilerinden ayırır (Watershed). Açık kalması önerilir.";
            case "atolye.pixScale": return "Piksel tabanlı H-score hesaplamasında alt örnekleme (downsample) çarpanı. 1.0 = tam çözünürlük; yüksek değerler daha hızlıdır (varsayılan: 1.0).";
            case "atolye.classifierName": return "Projede kaydedilecek veya yüklenecek tümör/stroma piksel sınıflandırıcısının dosya adı (varsayılan: tumor-stroma-RF).";
            case "atolye.minObjectArea": return "Tümör/stroma sınıflandırıcısı anotasyon üretirken korunacak en küçük adacık alanı (µm²). Küçük gürültü parçalarını temizler (varsayılan: 10000.0).";
            case "atolye.minHoleArea": return "Tümör/stroma anotasyonları içindeki doldurulacak en küçük boşluk/delik alanı (µm²) (varsayılan: 5000.0).";
            case "atolye.maxTrainingSamples": return "Piksel sınıflandırıcı eğitiminde kullanılacak en fazla örnek piksel sayısı (0 = sınırsız; varsayılan: 100000).";
            case "atolye.warnNuclearCount": return "Ki-67 ve ER/PR modüllerinde düşük hücre sayısı uyarısı için eşik (ör. 500 çekirdek altı uyarı verir).";
            case "atolye.warnGenericCount": return "Genel hücre sayımı modüllerinde düşük hücre sayısı uyarısı için eşik (varsayılan: 200).";
            case "atolye.warnTissueAreaMm2": return "Ölçülen bölge alanı bu değerin (mm²) altındaysa küçük doku alanı uyarısı gösterilir (varsayılan: 1.0 mm²).";
            case "atolye.exportFolder": return "Proje klasörü altında dışa aktarılan tabloların kaydedileceği varsayılan alt klasör adı (varsayılan: exports).";
            case "atolye.exportSeparator": return "Dışa aktarılan tablolarda sütun ayracı: TAB (.tsv, önerilen) veya Virgül (.csv).";
            case "atolye.exportScope": return "Veri dışa aktarma penceresi açıldığında varsayılan kapsam (yalnız bu görüntü veya tüm proje).";
            case "atolye.exportDet": return "Hücre/tespit düzeyi ölçümlerin (her satır bir hücre) dışa aktarılıp aktarılmayacağı.";
            case "atolye.exportAnn": return "Anotasyon/bölge düzeyi özet ölçümlerin (her satır bir bölge) dışa aktarılıp aktarılmayacağı.";
            case "atolye.exportGeo": return "Anotasyon sınır geometrilerinin GeoJSON biçiminde dışa aktarılıp aktarılmayacağı.";
            case "atolye.exportColumnsMode": return "Dışa aktarmada tüm sütunların mı yoksa yalnız seçili alt kümenin mi yazılacağı.";
            case "atolye.exportColumns": return "Alt küme modunda dışa aktarılacak sütun adları ('|' karakteri ile ayrılmış liste; boşsa varsayılan özet sütunlar).";
            case "atolye.exportPrefix": return "Dışa aktarılan dosya adlarının başına eklenecek isteğe bağlı önek (ör. çalışma veya kohort kodu).";
            case "atolye.exportDateSubfolder": return "Dışa aktarma klasörü içinde günün tarihiyle (YYYY-AA-GG) ayrı bir alt klasör oluşturulup oluşturulmayacağı.";
            case "atolye.exportLocation": return "Özel dışa aktarma klasör yolu. Boş bırakılırsa aktif QuPath projesinin içindeki dışa aktarma klasörü kullanılır.";
            default: return "";
        }
    }
}
