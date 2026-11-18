/*
 * QuPath Atölye Betikleri Uzantısı
 *
 * Registers a top-level "Atölye" menu under QuPath's Extensions menu,
 * with one item per workshop module. Each item runs the bundled Groovy
 * script (loaded from classpath resources) in QuPath's Groovy runtime.
 *
 * Modeled after https://github.com/qupath/qupath-extension-template
 */
package io.github.sbalci.qupath.workshop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.zip.ZipException;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.Property;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import org.controlsfx.control.PropertySheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import qupath.fx.dialogs.Dialogs;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.common.GeneralTools;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Main entry point for the Workshop extension.
 *
 * Discovered by QuPath via the {@code META-INF/services/qupath.lib.gui.extensions.QuPathExtension}
 * resource file (ServiceLoader mechanism).
 */
public class WorkshopExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(WorkshopExtension.class);

    private static final String MENU_PATH = "Extensions>Atölye";
    private static final String SCRIPT_RESOURCE_ROOT = "/scripts/";
    private static final String BUILD_INFO_RESOURCE = "/build-info.properties";

    /** Build timestamp injected by Gradle's processResources at JAR assembly time. */
    private static final String BUILD_TIMESTAMP = loadBuildProperty("build.timestamp", "bilinmiyor");

    /**
     * The workshop scripts in display order. Each entry maps a human-readable
     * menu label (Turkish default, dynamically localized via {@link WorkshopI18n})
     * to the script filename inside the JAR's {@code /scripts/} resource directory.
     */
    private static final List<ScriptEntry> SCRIPTS = List.of(
        new ScriptEntry("Arayüz turu",                    "arayuz-turu.groovy", false, false),
        new ScriptEntry("Hücre tespiti",                  "hucre-tespiti.groovy"),
        new ScriptEntry("Nükleer boya (Ki-67)",           "nukleer-boya.groovy"),
        new ScriptEntry("ER / PR H-score",                "er-pr-hscore.groovy"),
        new ScriptEntry("Membran boya (HER2)",            "membran-boya.groovy"),
        new ScriptEntry("Sitoplazmik boya (CD68)",        "sitoplazmik-boya.groovy"),
        new ScriptEntry("Tümör içi Ki-67",                "tumor-ici-ki67.groovy"),
        new ScriptEntry("QuANTUM cTCF",                   "quantum-ctcf.groovy"),
        new ScriptEntry("Veri dışa aktarma",              "veri-aktarma.groovy")
    );

    /**
     * Tümör/Stroma: the wizard plus its two steps as standalone entries. Each step
     * script checks its own prerequisites and says what is missing.
     */
    private static final ScriptGroup TUMOR_STROMA_MODULE = new ScriptGroup("Tümör/Stroma", "group.tumorStroma", List.of(
        new ScriptEntry("Tümör/Stroma sihirbazı",          "sihirbaz.groovy"),
        new ScriptEntry("Adım 1 — Model eğit",             "model-egit.groovy"),
        new ScriptEntry("Adım 2 — Alan ölçümü",            "tumor-stroma.groovy")
    ));

    private static final ScriptGroup TISSUE_MODULE = new ScriptGroup("Doku tespiti", "group.tissue", List.of(
        new ScriptEntry("Yerleşik (Otsu / ContourTracing)",             "yardimci-doku-tespiti-sihirbaz.groovy"),
        new ScriptEntry("Piksel eşikleyici (Create thresholder)",       "yardimci-esik-siniflandirici.groovy"),
        new ScriptEntry("GrandQC modeli (doku ve artefakt, Python)",    "yardimci-grandqc-sihirbaz.groovy"),
        new ScriptEntry("TIA Toolbox maskesi (Otsu/morfoloji, Python)", "yardimci-tiatoolbox-sihirbaz.groovy", "script.tissue.tiatoolbox", false, true)
    ));

    private static final ScriptGroup MITOSIS_MODULE = new ScriptGroup("Mitoz tespiti", "group.mitosis", List.of(
        new ScriptEntry("Mitoz modelleri listesi",                        "yardimci-mitoz-merkez.groovy", false, false),
        new ScriptEntry("KongNet MIDOG (TIA Toolbox)",                    "yardimci-mitoz-tiatoolbox.groovy"),
        new ScriptEntry("MIDOG25 FCOS (torchvision)",                     "yardimci-mitoz-fcos.groovy"),
        new ScriptEntry("MIDOG DA-RetinaNet (2021, eski)",                "yardimci-mitoz-retinanet.groovy"),
        new ScriptEntry("Atipik sınıflandırma — MIDOG25 EffNetV2",        "yardimci-mitoz-atipik-effnet.groovy"),
        new ScriptEntry("Atipik sınıflandırma — Sanofi EFTD (kapılı)",    "yardimci-mitoz-atipik-sanofi.groovy"),
        new ScriptEntry("Modelleri karşılaştır (aynı bölge)",             "yardimci-mitoz-karsilastir.groovy")
    ));

    private static final ScriptGroup ALIGNMENT_MODULE = new ScriptGroup("Görüntü Hizalama", "group.alignment", List.of(
        new ScriptEntry("VALIS (Docker/yerel)",   "yardimci-valis-sihirbaz.groovy"),
        new ScriptEntry("Warpy (esnek; Fiji)",    "yardimci-warpy-sihirbaz.groovy"),
        new ScriptEntry("Afin (otomatik/elle)",   "yardimci-hizalama-aktarim.groovy")
    ));

    private static final ScriptEntry SYNC_GRID_ENTRY =
        new ScriptEntry("Senkron Izgara", "yardimci-senkron-izgara.groovy", "menu.syncGrid", false, false);

    private record ScriptGroup(String title, String i18nKey, List<ScriptEntry> entries) {}

    private static final List<ScriptGroup> UTILITY_GROUPS = List.of(
        new ScriptGroup("Temel araçlar", "group.util.basic", List.of(
            new ScriptEntry("Tespitleri sil",                 "yardimci-tespitleri-sil.groovy"),
            new ScriptEntry("Görüntü tipi ayarla",            "yardimci-image-type.groovy", false, false),
            new ScriptEntry("Eşikleri ayarla",                "yardimci-esik-ayarla.groovy"),
            new ScriptEntry("Kalibrasyon (piksel boyutu)",    "yardimci-kalibrasyon.groovy"),
            new ScriptEntry("Görüntü künyesi ve histogram",   "yardimci-goruntu-kunye.groovy"),
            new ScriptEntry("Anotasyon yapısı QC (denetçi)",  "yardimci-anotasyon-qc.groovy")
        )),
        new ScriptGroup("Boya ve renk", "group.util.stain", List.of(
            new ScriptEntry("Boya vektörleri sihirbazı",      "yardimci-boya-vektor-sihirbaz.groovy"),
            new ScriptEntry("Boya kalitesi QC ölçümü",        "yardimci-boya-kalite-qc.groovy"),
            new ScriptEntry("ICC renk profili denetçisi",     "yardimci-icc-denetci-sihirbaz.groovy"),
            new ScriptEntry("Kohort boya OD sürüklenme raporu", "yardimci-kohort-boya-qc.groovy", false, false)
        )),
        new ScriptGroup("Hücre ve çekirdek tespiti", "group.util.cell", List.of(
            new ScriptEntry("StarDist çekirdek tespiti sihirbazı",                       "yardimci-stardist-sihirbaz.groovy"),
            new ScriptEntry("Cellpose hücre/çekirdek tespiti sihirbazı",                 "yardimci-cellpose-sihirbaz.groovy"),
            new ScriptEntry("InstanSeg çekirdek/hücre tespiti sihirbazı",                "yardimci-instanseg-sihirbaz.groovy"),
            new ScriptEntry("HoVer-NeXt çekirdek sınıflandırma sihirbazı (7 sınıf, GPU)","yardimci-hovernext-sihirbaz.groovy"),
            new ScriptEntry("Classpose hücre fenotipleme sihirbazı (H&E)",               "yardimci-classpose-sihirbaz.groovy"),
            new ScriptEntry("CytoFormer hücre tipi sınıflama sihirbazı (H&E, 16 organ)", "yardimci-cytoformer-sihirbaz.groovy"),
            new ScriptEntry("HistoPLUS hücre bölütleme + sınıflama sihirbazı (H&E, 13 sınıf)", "yardimci-histoplus-sihirbaz.groovy"),
            new ScriptEntry("NuClick (tıkla → çekirdek sınırı) sihirbazı",               "yardimci-nuclick-sihirbaz.groovy"),
            new ScriptEntry("Hepatosit bölütleme sihirbazı (Python)",                    "yardimci-hepatosit-sihirbaz.groovy"),
            new ScriptEntry("KongNet mitoz sonuçlarını içe aktar (GeoJSON)",                            "yardimci-mitoz-kongnet.groovy"),
            new ScriptEntry("Tespit doğrulama (F1 / IoU)",                               "yardimci-dogrulama-f1.groovy")
        )),
        new ScriptGroup("Skorlama ve ölçüm", "group.util.scoring", List.of(
            new ScriptEntry("Göz kararı kalibrasyon alıştırması",                        "yardimci-goz-karari-kalibrasyon.groovy"),
            new ScriptEntry("Eşik ile alan ölçümü",                                      "yardimci-esik-alan.groovy"),
            new ScriptEntry("Sınıf bazlı hücre sayımı (% dağılım)",                      "yardimci-sinif-sayim.groovy"),
            new ScriptEntry("Ki-67 heterojenlik ızgarası",                               "yardimci-ki67-heterojenlik.groovy"),
            new ScriptEntry("Stromal TİL yoğunluğu",                                     "yardimci-stromal-til.groovy"),
            new ScriptEntry("Alan temelli pozitiflik (% pozitiflik)",                    "yardimci-alan-pozitiflik.groovy"),
            new ScriptEntry("İmmün hücre yoğunluğu (DAB)",                               "yardimci-immun-yogunluk.groovy"),
            new ScriptEntry("PHH3 mitoz kantifikasyonu",                                 "yardimci-mitoz-phh3.groovy"),
            new ScriptEntry("Tümör tomurcuklanma kantifikasyonu (CK / ITBCC)",           "yardimci-tumor-tomurcuklanma.groovy"),
            new ScriptEntry("WSInfer karo özeti (sınıf alanı / %)",                      "yardimci-wsinfer-ozet.groovy"),
            new ScriptEntry("WSInfer çıkarım denetimi (kurulum · kalibrasyon · sınıf adları)", "yardimci-wsinfer-denetim.groovy"),
            new ScriptEntry("Sıralı yoğun odak seçici (top-N)",                          "yardimci-hotspot-sirali.groovy"),
            new ScriptEntry("Entegre DAB OD (alan × OD)",                                "yardimci-entegre-od.groovy")
        )),
        new ScriptGroup("Uzamsal analiz", "group.util.spatial", List.of(
            new ScriptEntry("Yapıya uzaklık (sınıra mesafe)",    "yardimci-yapi-uzaklik.groovy"),
            new ScriptEntry("Delaunay komşuluk özellikleri",     "yardimci-delaunay-komsuluk.groovy"),
            new ScriptEntry("En yakın komşu mesafesi",           "yardimci-nn-mesafe.groovy"),
            new ScriptEntry("Yoğunluk haritası",                 "yardimci-yogunluk-haritasi.groovy"),
            new ScriptEntry("Kesişim alanı (örtüşme)",           "yardimci-kesisim-alani.groovy"),
            new ScriptEntry("Peritümöral bant (halka)",          "yardimci-peritumoral-bant.groovy"),
            new ScriptEntry("Dış bükey zarf (convex hull)",      "yardimci-konveks-zarf.groovy"),
            new ScriptEntry("Türler arası en yakın komşu (A→B)", "yardimci-capraz-nn-mesafe.groovy"),
            new ScriptEntry("Eşit alanlı merkez/çevre",          "yardimci-merkez-cevre.groovy")
        )),
        new ScriptGroup("İçe ve dışa aktarma / veri", "group.util.io", List.of(
            new ScriptEntry("Toplu anotasyon dışa aktarma",                         "yardimci-anotasyon-disa-aktar.groovy", false, false),
            new ScriptEntry("Toplu anotasyon içe aktarma",                          "yardimci-anotasyon-ice-aktar.groovy",  false, false),
            new ScriptEntry("Karo (tile) dışa aktarma",                             "yardimci-karo-disa-aktarma.groovy"),
            new ScriptEntry("OME-Zarr dışa aktarma",                                "yardimci-omezarr-disa-aktarma.groovy"),
            new ScriptEntry("Görüntü alanı çıkart (Extract Region)",                "yardimci-bolge-cikart-sihirbaz.groovy"),
            new ScriptEntry("Dikdörtgen seçimlerden görüntü oluştur",               "yardimci-goruntuden-alan-cikart.groovy"),
            new ScriptEntry("Makine öğrenmesi için öznitelik matrisi",              "yardimci-ozellik-matrisi.groovy"),
            new ScriptEntry("Kümeleme/fenotip etiketlerini içe aktar (TSV)",        "yardimci-kume-etiketi-iceaktar.groovy"),
            new ScriptEntry("Yapay zekâ tahmin maskelerini içe aktar (GeoJSON)",    "yardimci-tahmin-iceaktar.groovy"),
            new ScriptEntry("Maske görüntüsünü içe aktar",                          "yardimci-maske-iceaktar.groovy"),
            new ScriptEntry("TIA Toolbox için bölge maskesi",                       "yardimci-tiatoolbox-maske.groovy"),
            new ScriptEntry("TMA çekirdek bazlı dışa aktarım",                      "yardimci-tma-cekirdek-aktarim.groovy"),
            new ScriptEntry("DICOM → SVS dönüştürme (DICOMtoSVS)",                  "yardimci-dicom-svs-sihirbaz.groovy", false, false),
            new ScriptEntry("Örnek tümör/stroma sınıflandırıcısını projeye kaydet", "yardimci-ornek-siniflandirici.groovy", false, false)
        )),
        new ScriptGroup("Python köprüleri ve temel modeller", "group.util.python", List.of(
            new ScriptEntry("Atölye Python ortam yöneticisi",                       "yardimci-python-ortam-yoneticisi.groovy", false, false),
            new ScriptEntry("TIA Toolbox boyama normalizasyonu / doku maskesi sihirbazı", "yardimci-tiatoolbox-sihirbaz.groovy", "script.util.tiatoolbox", false, true),
            new ScriptEntry("TIA Toolbox bölgede çekirdek/mitoz tespiti",           "yardimci-tiatoolbox-bolge-sihirbaz.groovy"),
            new ScriptEntry("Kaiko Midnight sınıflandırıcı sihirbazı",              "yardimci-kaiko-sihirbaz.groovy"),
            new ScriptEntry("SPIDER doku sınıflandırıcı sihirbazı",                 "yardimci-spider-sihirbaz.groovy"),
            new ScriptEntry("HistoART artefakt tespiti sihirbazı",                  "yardimci-histoart-sihirbaz.groovy"),
            new ScriptEntry("Temel model hazırlık ve sağlamlık sihirbazı",          "yardimci-foundation-model-sihirbaz.groovy"),
            new ScriptEntry("Python ve yapay zekâ ajanı köprü denetimi",            "yardimci-ajan-kopru-denetimi.groovy", false, false)
        )),
        new ScriptGroup("Klinik ve kohort", "group.util.clinical", List.of(
            new ScriptEntry("Kohort üst veri sihirbazı",              "yardimci-metadata-sihirbaz.groovy", false, false),
            // needsImage=false: "Tüm proje (klasörden)" modu açık lam olmadan çalışır; betik lamsız durumu kendi ekranında ele alır.
            new ScriptEntry("Sectra PACS anotasyon sihirbazı",        "yardimci-sectra-iceaktar.groovy", false, false),
            new ScriptEntry("WSI anonimleştirme sihirbazı",           "yardimci-anonim-sihirbaz.groovy", false, false),
            new ScriptEntry("Kohort özet toplayıcı (proje tablosu)",  "yardimci-kohort-ozet-topla.groovy", false, false),
            new ScriptEntry("GrandQC kohort KK özeti",                "yardimci-grandqc-kohort-ozet.groovy", false, false),
            new ScriptEntry("İşlem kaydı (provenance JSON)",          "yardimci-kosu-manifesti.groovy", false, false)
        )),
        new ScriptGroup("Eğitim, sunum ve ImageJ", "group.util.edu", List.of(
            new ScriptEntry("Görüntü işleme kavramları",              "yardimci-goruntu-isleme-turu.groovy"),
            new ScriptEntry("Tuş/fare göstergesi (kayıt için)",       "yardimci-tus-fare-gostergesi.groovy", false, false),
            new ScriptEntry("Görüntü yakala (rapor/sunum)",           "yardimci-goruntu-yakala.groovy", false, false),
            new ScriptEntry("ImageJ ile otomatik eşik → anotasyon",   "yardimci-imagej-otsu-anotasyon.groovy"),
            new ScriptEntry("ImageJ ile sınır yumuşat (spline)",      "yardimci-imagej-spline-duzeltme.groovy"),
            new ScriptEntry("Dağılım grafiği (scatter chart)",        "yardimci-dagilim-grafigi.groovy")
        ))
    );

    private static final List<ScriptEntry> UPCOMING_SCRIPTS = List.<ScriptEntry>of();

    private boolean alreadyInstalled = false;

    public static final BooleanProperty enableExtensionProperty =
            PathPrefs.createPersistentPreference("atolye.enableExtension", true);

    private static final StringProperty FX_LANGUAGE = new SimpleStringProperty(WorkshopI18n.getLanguage());
    static {
        WorkshopI18n.addListener(() -> {
            Runnable sync = () -> FX_LANGUAGE.set(WorkshopI18n.getLanguage());
            try {
                if (Platform.isFxApplicationThread()) sync.run();
                else Platform.runLater(sync);
            } catch (IllegalStateException ex) {
                sync.run();
            }
        });
    }

    private static StringBinding bindI18n(String key) {
        return Bindings.createStringBinding(() -> WorkshopI18n.t(key), FX_LANGUAGE);
    }

    /** Package-private introspection for unit tests verifying classpath resources and i18n keys. */
    static List<String> registeredScriptResources() {
        List<String> out = new ArrayList<>();
        for (ScriptEntry e : SCRIPTS) out.add(e.resource);
        for (ScriptEntry e : TISSUE_MODULE.entries()) out.add(e.resource);
        for (ScriptEntry e : TUMOR_STROMA_MODULE.entries()) out.add(e.resource);
        for (ScriptEntry e : MITOSIS_MODULE.entries()) out.add(e.resource);
        for (ScriptEntry e : ALIGNMENT_MODULE.entries()) out.add(e.resource);
        out.add(SYNC_GRID_ENTRY.resource);
        for (ScriptGroup g : UTILITY_GROUPS) {
            for (ScriptEntry e : g.entries()) out.add(e.resource);
        }
        for (ScriptEntry e : UPCOMING_SCRIPTS) out.add(e.resource);
        return Collections.unmodifiableList(out);
    }

    /** Package-private introspection returning every menu/group/script i18n key registered in the extension. */
    static List<String> registeredI18nKeys() {
        List<String> out = new ArrayList<>();
        for (ScriptEntry e : SCRIPTS) out.add(e.i18nKey);
        out.add(TISSUE_MODULE.i18nKey());
        for (ScriptEntry e : TISSUE_MODULE.entries()) out.add(e.i18nKey);
        out.add(TUMOR_STROMA_MODULE.i18nKey());
        for (ScriptEntry e : TUMOR_STROMA_MODULE.entries()) out.add(e.i18nKey);
        out.add(MITOSIS_MODULE.i18nKey());
        for (ScriptEntry e : MITOSIS_MODULE.entries()) out.add(e.i18nKey);
        out.add(ALIGNMENT_MODULE.i18nKey());
        for (ScriptEntry e : ALIGNMENT_MODULE.entries()) out.add(e.i18nKey);
        out.add(SYNC_GRID_ENTRY.i18nKey);
        for (ScriptGroup g : UTILITY_GROUPS) {
            out.add(g.i18nKey());
            for (ScriptEntry e : g.entries()) out.add(e.i18nKey);
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (alreadyInstalled) {
            logger.warn("Workshop extension already installed; skipping duplicate install.");
            return;
        }
        alreadyInstalled = true;

        try {
            var menu = qupath.getMenu(MENU_PATH, true);

            try (InputStream iconStream = WorkshopExtension.class.getResourceAsStream("/images/workshop-icon.png")) {
                if (iconStream != null) {
                    ImageView icon = new ImageView(new Image(iconStream));
                    icon.setFitWidth(18);
                    icon.setFitHeight(18);
                    icon.setPreserveRatio(true);
                    menu.setGraphic(icon);
                }
            }

            var header = new MenuItem();
            header.textProperty().bind(Bindings.createStringBinding(
                    () -> qupath.getImageData() == null
                            ? WorkshopI18n.t("menu.header.noImage")
                            : WorkshopI18n.t("menu.header.ready"),
                    qupath.imageDataProperty(), FX_LANGUAGE));
            header.setDisable(true);
            menu.getItems().add(header);
            menu.getItems().add(new SeparatorMenuItem());

            Menu modulesMenu = new Menu();
            modulesMenu.textProperty().bind(bindI18n("menu.modules"));
            int moduleIndex = 0;
            for (ScriptEntry entry : SCRIPTS) {
                modulesMenu.getItems().add(buildMenuItem(qupath, entry));
                if (moduleIndex == 0) {
                    modulesMenu.getItems().add(buildSubmenu(qupath, TISSUE_MODULE));
                }
                if ("sitoplazmik-boya.groovy".equals(entry.resource)) {
                    modulesMenu.getItems().add(buildSubmenu(qupath, TUMOR_STROMA_MODULE));
                }
                if ("quantum-ctcf.groovy".equals(entry.resource)) {
                    modulesMenu.getItems().add(buildSubmenu(qupath, MITOSIS_MODULE));
                    modulesMenu.getItems().add(buildSubmenu(qupath, ALIGNMENT_MODULE));
                }
                moduleIndex++;
            }
            menu.getItems().add(modulesMenu);

            if (!UTILITY_GROUPS.isEmpty()) {
                Menu utilsMenu = new Menu();
                utilsMenu.textProperty().bind(bindI18n("menu.utilities"));
                for (ScriptGroup group : UTILITY_GROUPS) {
                    utilsMenu.getItems().add(buildSubmenu(qupath, group));
                }
                menu.getItems().add(utilsMenu);
            }

            if (!UPCOMING_SCRIPTS.isEmpty()) {
                Menu upcomingMenu = new Menu();
                upcomingMenu.textProperty().bind(bindI18n("menu.upcoming"));
                for (ScriptEntry entry : UPCOMING_SCRIPTS) {
                    MenuItem item = new MenuItem();
                    item.textProperty().bind(bindI18n(entry.i18nKey));
                    item.setDisable(true);
                    upcomingMenu.getItems().add(item);
                }
                menu.getItems().add(upcomingMenu);
            }

            menu.getItems().add(new SeparatorMenuItem());
            var localWorkflow = new MenuItem();
            localWorkflow.textProperty().bind(bindI18n("workflow.title"));
            localWorkflow.setOnAction(e -> WorkshopResultsDialog.show(qupath));
            menu.getItems().add(localWorkflow);
            var settings = new MenuItem();
            settings.textProperty().bind(bindI18n("menu.settings"));
            settings.setOnAction(e -> WorkshopSettingsDialog.show());
            menu.getItems().add(settings);

            var langItem = new MenuItem();
            langItem.textProperty().bind(bindI18n("menu.language"));
            langItem.setOnAction(e -> {
                WorkshopI18n.toggleLanguage();
                Dialogs.showInfoNotification(
                        WorkshopI18n.t("menu.language.toastTitle"),
                        WorkshopI18n.t("menu.language.toastBody"));
            });
            menu.getItems().add(langItem);

            var envCheck = new MenuItem();
            envCheck.textProperty().bind(bindI18n("menu.envCheck"));
            envCheck.setOnAction(e -> showEnvironmentCheck(qupath));
            menu.getItems().add(envCheck);

            menu.getItems().add(new SeparatorMenuItem());
            var about = new MenuItem();
            about.textProperty().bind(bindI18n("menu.about"));
            about.setOnAction(e -> showAboutDialog());
            menu.getItems().add(about);

            try {
                var extMenu = qupath.getMenu("Extensions", true);
                MenuItem syncGrid = buildMenuItem(qupath, SYNC_GRID_ENTRY);
                extMenu.getItems().add(syncGrid);
            } catch (Exception exGrid) {
                logger.warn("Could not add top-level 'Senkron Izgara' menu item", exGrid);
            }

            installPreferences(qupath);

            int totalModuleScripts = SCRIPTS.size()
                    + TISSUE_MODULE.entries().size()
                    + TUMOR_STROMA_MODULE.entries().size()
                    + MITOSIS_MODULE.entries().size()
                    + ALIGNMENT_MODULE.entries().size();
            int totalUtilityScripts = UTILITY_GROUPS.stream().mapToInt(g -> g.entries().size()).sum();
            logger.info("Workshop extension installed with {} module + {} utility (in {} groups) + 1 top-level (Senkron Izgara) + {} upcoming (disabled) scripts.",
                    totalModuleScripts, totalUtilityScripts, UTILITY_GROUPS.size(), UPCOMING_SCRIPTS.size());
        } catch (Exception ex) {
            logger.error("Failed to install Workshop extension menu", ex);
        }
    }

    private Menu buildSubmenu(QuPathGUI qupath, ScriptGroup group) {
        Menu sub = new Menu();
        sub.textProperty().bind(bindI18n(group.i18nKey()));
        for (ScriptEntry entry : group.entries()) {
            sub.getItems().add(buildMenuItem(qupath, entry));
        }
        return sub;
    }

    private MenuItem buildMenuItem(QuPathGUI qupath, ScriptEntry entry) {
        MenuItem item = new MenuItem();
        item.textProperty().bind(bindI18n(entry.i18nKey));
        if (entry.disabled) {
            item.setDisable(true);
        } else {
            item.setOnAction(e -> runScriptSafely(qupath, entry));
            item.disableProperty().bind(disableBinding(qupath, entry));
        }
        return item;
    }

    @SuppressWarnings("unchecked")
    private void installPreferences(QuPathGUI qupath) {
        try {
            var items = qupath.getPreferencePane().getPropertySheet().getItems();

            items.add(new PropertyItemBuilder<>(enableExtensionProperty, Boolean.class)
                    .name(WorkshopI18n.t("prefs.enable.name"))
                    .category("Atölye")
                    .description(WorkshopI18n.t("prefs.enable.desc"))
                    .build());

            for (String key : WorkshopPrefs.keys()) {
                Property<?> p = WorkshopPrefs.property(key);
                String name = WorkshopSettingsDialog.labelFor(key);
                String desc = WorkshopSettingsDialog.tooltipFor(key);
                String sec = WorkshopPrefs.section(key);
                String locSec = WorkshopI18n.t("section." + sec);
                String category = "Atölye · " + (locSec.startsWith("section.") ? sec : locSec);
                PropertySheet.Item item = null;
                if (p instanceof DoubleProperty dp) {
                    item = new PropertyItemBuilder<>(dp.asObject(), Double.class).name(name).category(category).description(desc).build();
                } else if (p instanceof IntegerProperty ip) {
                    item = new PropertyItemBuilder<>(ip.asObject(), Integer.class).name(name).category(category).description(desc).build();
                } else if (p instanceof BooleanProperty bp) {
                    item = new PropertyItemBuilder<>(bp.asObject(), Boolean.class).name(name).category(category).description(desc).build();
                } else if (p instanceof StringProperty sp) {
                    item = new PropertyItemBuilder<>(sp, String.class).name(name).category(category).description(desc).build();
                }
                if (item != null) items.add(item);
            }
        } catch (Throwable t) {
            logger.warn("Could not register Atölye preferences in QuPath's Preferences pane", t);
        }
    }

    public static void openLocalSetup(QuPathGUI qupath, String adapter) {
        String filename = switch (adapter) {
            case "histoplus" -> "yardimci-histoplus-sihirbaz.groovy";
            case "nuclick" -> "yardimci-nuclick-sihirbaz.groovy";
            default -> throw new IllegalArgumentException("Unsupported local model");
        };
        new WorkshopExtension().runScriptSafely(qupath, new ScriptEntry(adapter, filename));
    }

    private void runScriptSafely(QuPathGUI qupath, ScriptEntry entry) {
        String activeLabel = entry.currentLabel();
        String scriptBody;
        try {
            scriptBody = readScriptResource(entry.resource);
        } catch (JarCorruptedException jce) {
            Dialogs.showErrorMessage(
                WorkshopI18n.t("dialog.jarReload.title"),
                WorkshopI18n.t("dialog.jarReload.body")
            );
            return;
        }
        // An invalid numeric setting blocks only the scripts that read it. It used to block every
        // menu entry, including scripts that never touch that setting.
        if (scriptBody != null) {
            for (String key : WorkshopPrefs.keys()) {
                Object stored = WorkshopPrefs.property(key).getValue();
                if (!(stored instanceof Number number)) continue;
                String problem = WorkshopParameterValidation.problem(key, number.doubleValue(), other ->
                        ((Number) WorkshopPrefs.property(other).getValue()).doubleValue());
                if (problem == null) continue;
                if (!scriptBody.contains(key)) {
                    logger.warn("Invalid Atölye setting {} ignored: {} does not read it", key, entry.resource);
                    continue;
                }
                Dialogs.showErrorMessage(
                        WorkshopI18n.t("dialog.fixSetting.title"),
                        WorkshopSettingsDialog.labelFor(key) + ": " + problem
                        + WorkshopI18n.t("dialog.fixSetting.footer"));
                return;
            }
        }
        if (scriptBody == null) {
            Dialogs.showErrorMessage(
                WorkshopI18n.t("dialog.missingScript.title"),
                WorkshopI18n.tf("dialog.missingScript.body", activeLabel, entry.resource)
            );
            return;
        }

        Thread runner = new Thread(() -> {
            try {
                Binding binding = new Binding();
                binding.setVariable("EXTENSION_NAME", "qupath-extension-workshop");
                // Which menu entry launched the script; lets a script shared by two entries open in the matching mode.
                binding.setVariable("ATOLYE_ENTRY_KEY", entry.i18nKey);
                GroovyShell shell = new GroovyShell(
                    WorkshopExtension.class.getClassLoader(),
                    binding
                );
                shell.evaluate(scriptBody, entry.resource);
            } catch (Throwable t) {
                logger.error("Script execution failed: {}", activeLabel, t);
                String guidance = WorkshopErrors.guidance(t);
                String report = WorkshopErrors.formatCopyableReport(activeLabel, entry.resource, t);
                Platform.runLater(() -> showCopyableErrorDialog(activeLabel, guidance, report));
            }
        }, "WorkshopScript-" + entry.resource);
        runner.setDaemon(true);
        runner.start();
    }

    private static void showCopyableErrorDialog(String scriptLabel, String guidance, String redactedReport) {
        String body = WorkshopI18n.t("dialog.error.intro") + "\n\n"
                + guidance + "\n\n"
                + WorkshopI18n.t("dialog.error.outro");
        var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
        alert.setTitle(WorkshopI18n.tf("dialog.error.title", scriptLabel));
        alert.setHeaderText(null);
        alert.setResizable(true);
        var msgLabel = new javafx.scene.control.Label(body);
        msgLabel.setWrapText(true);
        msgLabel.setMaxWidth(520);
        var reportArea = new javafx.scene.control.TextArea(redactedReport);
        reportArea.setEditable(false);
        reportArea.setWrapText(false);
        reportArea.setPrefRowCount(8);
        reportArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 11px;");
        var box = new javafx.scene.layout.VBox(8, msgLabel, reportArea);
        alert.getDialogPane().setContent(box);
        var copyBtn = new javafx.scene.control.ButtonType(
                WorkshopI18n.t("dialog.error.copyBtn"), javafx.scene.control.ButtonBar.ButtonData.LEFT);
        alert.getButtonTypes().setAll(copyBtn, javafx.scene.control.ButtonType.OK);
        var choice = alert.showAndWait();
        if (choice.isPresent() && choice.get() == copyBtn) {
            var cb = javafx.scene.input.Clipboard.getSystemClipboard();
            var cc = new javafx.scene.input.ClipboardContent();
            cc.putString(redactedReport);
            cb.setContent(cc);
            Dialogs.showInfoNotification(
                    WorkshopI18n.t("dialog.error.copiedTitle"),
                    WorkshopI18n.t("dialog.error.copiedBody"));
        }
    }

    private String readScriptResource(String filename) {
        String path = SCRIPT_RESOURCE_ROOT + filename;
        try (InputStream in = WorkshopExtension.class.getResourceAsStream(path)) {
            if (in == null) {
                logger.error("Missing script resource: {}", path);
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (UncheckedIOException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ZipException) {
                logger.error("JAR corruption reading {} — likely JAR replaced while QuPath running", path, ex);
                throw new JarCorruptedException(ex);
            }
            logger.error("Unchecked I/O reading script resource: {}", path, ex);
            return null;
        } catch (IOException ex) {
            if (ex instanceof ZipException) {
                logger.error("JAR corruption reading {} — likely JAR replaced while QuPath running", path, ex);
                throw new JarCorruptedException(ex);
            }
            logger.error("Failed to read script resource: {}", path, ex);
            return null;
        }
    }

    private static final class JarCorruptedException extends RuntimeException {
        JarCorruptedException(Throwable cause) { super(cause); }
    }

    private static String loadBuildProperty(String key, String fallback) {
        try (InputStream in = WorkshopExtension.class.getResourceAsStream(BUILD_INFO_RESOURCE)) {
            if (in == null) return fallback;
            Properties props = new Properties();
            props.load(in);
            String value = props.getProperty(key);
            return (value == null || value.isBlank() || value.startsWith("@")) ? fallback : value;
        } catch (IOException ex) {
            return fallback;
        }
    }

    private void showEnvironmentCheck(QuPathGUI qupath) {
        boolean en = WorkshopI18n.isEnglish();
        String found = WorkshopI18n.t("env.found");
        String missing = WorkshopI18n.t("env.missing");
        boolean hasImage = qupath.getImageData() != null;
        boolean hasProject = qupath.getProject() != null;
        boolean cellpose = isOnClasspath("qupath.ext.biop.cellpose.Cellpose2D",
                                         "qupath.ext.biop.cellpose.CellposeExtension");
        boolean stardist = isOnClasspath("qupath.ext.stardist.StarDist2D",
                                         "qupath.ext.stardist.StarDistExtension");
        boolean instanseg = isOnClasspath("qupath.ext.instanseg.core.InstanSeg",
                                          "qupath.ext.instanseg.InstanSegExtension");
        boolean djl = isOnClasspath("ai.djl.engine.Engine",
                                    "qupath.ext.djl.DjlTools",
                                    "qupath.ext.djl.DjlExtension");
        String envMessage = en
            ? (
                "QuPath version:    " + GeneralTools.getVersion() + "\n" +
                "Workshop ext.:     v" + getVersion() + "  (built " + BUILD_TIMESTAMP + ")\n" +
                "QuPath baseline:   " + getQuPathVersion() + "+\n\n" +
                "Active image:      " + (hasImage ? "open" : "none — open a slide via File → Open") + "\n" +
                "Active project:    " + (hasProject ? "open" : "none") + "\n\n" +
                "Optional components (required only for deep-learning modules):\n" +
                "  • Cellpose extension:   " + (cellpose ? found : missing) + "\n" +
                "  • StarDist extension:   " + (stardist ? found : missing) + "\n" +
                "  • InstanSeg extension:  " + (instanseg ? found : missing) + "\n" +
                "  • Deep Java Library:    " + (djl ? found : missing) + "  (shared runtime for InstanSeg/WSInfer/StarDist-TF)\n\n" +
                "Core workflows (Cell detection, Nuclear stain (Ki-67), ER / PR H-score, Cytoplasmic stain (CD68),\n" +
                "Tumor/Stroma wizard, Intra-tumoral Ki-67, Data export) require only QuPath.\n" +
                "Membrane stain (HER2) uses Cellpose + Python venv when installed, and QuANTUM cTCF uses StarDist;\n" +
                "both automatically fall back to built-in Watershed cell detection when those extensions are absent.\n" +
                "InstanSeg runs inside the JVM without a separate Python environment.\n" +
                (cellpose ? "Configure python.exe in Edit → Preferences → Cellpose/Omnipose.\n"
                          : "Optional setup guide: https://atolye.patoloji.dev/en/kaynaklar.html\n") +
                "\nFor research and educational use only."
            )
            : (
                "QuPath sürümü:     " + GeneralTools.getVersion() + "\n" +
                "Atölye eklentisi:  v" + getVersion() + "  (derlenme " + BUILD_TIMESTAMP + ")\n" +
                "QuPath tabanı:     " + getQuPathVersion() + "+\n\n" +
                "Açık görüntü:      " + (hasImage ? "var" : "yok — File → Open ile bir sanal lam açın") + "\n" +
                "Açık proje:        " + (hasProject ? "var" : "yok") + "\n\n" +
                "İsteğe bağlı bileşenler (yalnızca ileri derin öğrenme modülleri için):\n" +
                "  • Cellpose eklentisi:   " + (cellpose ? found : missing) + "\n" +
                "  • StarDist eklentisi:   " + (stardist ? found : missing) + "\n" +
                "  • InstanSeg eklentisi:  " + (instanseg ? found : missing) + "\n" +
                "  • Deep Java Library:    " + (djl ? found : missing) + "  (InstanSeg/WSInfer/StarDist-TF ortak çalışma zamanı)\n\n" +
                "Temel iş akışları (Hücre tespiti, Nükleer boya (Ki-67), ER / PR H-score, Sitoplazmik boya (CD68),\n" +
                "Tümör/Stroma sihirbazı, Tümör içi Ki-67, Veri dışa aktarma) yalnızca QuPath gerektirir.\n" +
                "Membran boya (HER2) için Cellpose eklentisi + Python venv, QuANTUM cTCF için StarDist eklentisi\n" +
                "önerilir; bu eklentiler kurulu değilse her iki modül de otomatik olarak yerleşik Watershed\n" +
                "hücre tespitine yedeklenir ve çalışmaya devam eder.\n" +
                "InstanSeg ayrı bir Python ortamı gerektirmez (en sade derin öğrenme seçeneği).\n" +
                (cellpose ? "Cellpose için python.exe yolunu ayarlayın: Edit → Preferences → Cellpose/Omnipose.\n"
                          : "Cellpose ile membran bölütlemesi için Cellpose eklentisi + Python venv kurulabilir (bkz. kaynaklar → ileri kurulumlar).\n") +
                "\"bulunamadı\" görünen bileşenler yalnızca ilgili derin öğrenme seçeneği için gerekir;\n" +
                "kurulum rehberi: https://atolye.patoloji.dev/kaynaklar.html#ileri-kurulumlar\n\n" +
                "Yalnızca araştırma ve eğitim amaçlıdır."
            );
        var envArea = new javafx.scene.control.TextArea(envMessage);
        envArea.setEditable(false);
        envArea.setWrapText(false);
        envArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;");
        envArea.setPrefColumnCount(56);
        envArea.setPrefRowCount(24);
        var envAlert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
        envAlert.setTitle(WorkshopI18n.t("env.title"));
        envAlert.setHeaderText(null);
        envAlert.setResizable(true);
        envAlert.getDialogPane().setContent(envArea);
        var openEnvBtn = new javafx.scene.control.ButtonType(
                WorkshopI18n.t("env.openPythonBtn"), javafx.scene.control.ButtonBar.ButtonData.LEFT);
        envAlert.getButtonTypes().setAll(openEnvBtn, javafx.scene.control.ButtonType.OK);
        var envChoice = envAlert.showAndWait();
        if (envChoice.isPresent() && envChoice.get() == openEnvBtn) {
            runScriptSafely(qupath, new ScriptEntry(
                    "Atölye Python ortam yöneticisi", "yardimci-python-ortam-yoneticisi.groovy", false, false));
        }
    }

    private static boolean isOnClasspath(String... candidates) {
        ClassLoader loader = WorkshopExtension.class.getClassLoader();
        for (String className : candidates) {
            try {
                Class.forName(className, false, loader);
                return true;
            } catch (Throwable ignored) {
                // try next candidate
            }
        }
        return false;
    }

    private void showAboutDialog() {
        boolean en = WorkshopI18n.isEnglish();
        String body = en
            ? (
                "Bundles the hands-on digital pathology & AI workshop workflows\n" +
                "into one-click QuPath menus, guided wizards, and persistent settings.\n\n" +
                "Modules (Extensions → Atölye → Modules):\n" +
                "  • Interface tour (interactive UI walkthrough; no open slide required)\n" +
                "  • Tissue detection (Built-in Otsu, Pixel thresholder, GrandQC, TIA Toolbox)\n" +
                "  • Cell detection\n" +
                "  • Nuclear stain (Ki-67)\n" +
                "  • ER / PR H-score\n" +
                "  • Membrane stain (HER2)\n" +
                "  • Cytoplasmic stain (CD68)\n" +
                "  • Tumor/Stroma wizard (install sample model / train / apply)\n" +
                "  • Intra-tumoral Ki-67\n" +
                "  • QuANTUM cTCF (StarDist / Watershed + interactive object classifier)\n" +
                "  • Mitosis detection (KongNet, MIDOG25 FCOS, DA-RetinaNet, EffNetV2, comparison)\n" +
                "  • Image Registration (VALIS, Warpy, Affine)\n" +
                "  • Data export (TSV / CSV / GeoJSON)\n\n" +
                "Top-level shortcut:\n" +
                "  • Extensions → Synchronized Grid (opens serial slides in linked multi-view)\n\n" +
                "Utilities — 9 topic-grouped submenus (Utilities → <topic> → tool):\n" +
                "  • Basic tools · Stain & color · Cell & nucleus detection\n" +
                "  • Scoring & quantification · Spatial analysis · Import, export & data\n" +
                "  • Python bridges & foundation models · Clinical & cohort · Education & ImageJ\n\n" +
                "Version: " + getVersion() + "\n" +
                "Built:   " + BUILD_TIMESTAMP + "\n" +
                "Baseline QuPath: " + getQuPathVersion() + "+\n\n" +
                "🌐 Preparation site: https://atolye.patoloji.dev/en/\n" +
                "🌐 Reference site:   https://atolye2.patoloji.dev/en/\n" +
                "👤 Contact:          https://www.serdarbalci.com\n" +
                "✉️  Email:            serdarbalci@serdarbalci.com\n\n" +
                "For research and educational use only. Not a clinical diagnostic medical device."
            )
            : (
                "Patologlar ve bilgisayar bilimciler için dijital patoloji ve yapay zekâ atölyesinin\n" +
                "tek tıkla çalışan betiklerini bir araya getirir.\n\n" +
                "Modüller (Extensions → Atölye → Modüller):\n" +
                "  • Arayüz turu (etkileşimli arayüz gezintisi; açık sanal lam gerekmez)\n" +
                "  • Doku tespiti (Yerleşik Otsu, Piksel eşikleyici, GrandQC, TIA Toolbox)\n" +
                "  • Hücre tespiti\n" +
                "  • Nükleer boya (Ki-67)\n" +
                "  • ER / PR H-score\n" +
                "  • Membran boya (HER2)\n" +
                "  • Sitoplazmik boya (CD68)\n" +
                "  • Tümör/Stroma sihirbazı (model kur / eğit / uygula)\n" +
                "  • Tümör içi Ki-67\n" +
                "  • QuANTUM cTCF (StarDist / Watershed + nesne sınıflandırıcı eğitimi)\n" +
                "  • Mitoz tespiti (KongNet, MIDOG25 FCOS, DA-RetinaNet, EffNetV2, karşılaştırma)\n" +
                "  • Görüntü Hizalama (VALIS, Warpy, Afin)\n" +
                "  • Veri dışa aktarma (TSV / CSV / GeoJSON)\n\n" +
                "Üst düzey kısayol:\n" +
                "  • Extensions → Senkron Izgara (seri kesitleri eş zamanlı çoklu görünümde açar)\n\n" +
                "Yardımcılar — konuya göre gruplu alt menüler (Yardımcılar → <konu> → betik):\n" +
                "  • Temel araçlar · Boya ve renk · Hücre ve çekirdek tespiti\n" +
                "  • Skorlama ve ölçüm · Uzamsal analiz · İçe ve dışa aktarma / veri\n" +
                "  • Python köprüleri ve temel modeller · Klinik ve kohort · Eğitim, sunum ve ImageJ\n\n" +
                "Ayarlar:\n" +
                "  • Atölye Ayarları — parametreleri ve dili (TR / EN) değiştirin, oturumlar arası hatırlanır\n\n" +
                "Sürüm: " + getVersion() + "\n" +
                "Derlenme tarihi: " + BUILD_TIMESTAMP + "\n" +
                "Temel QuPath sürümü: " + getQuPathVersion() + "+\n\n" +
                "🌐 Hazırlık sitesi:  https://atolye.patoloji.dev\n" +
                "🌐 Referans sitesi:  https://atolye2.patoloji.dev\n" +
                "👤 İletişim:         https://www.serdarbalci.com\n" +
                "✉️  E-posta:          serdarbalci@serdarbalci.com\n\n" +
                "Yalnızca araştırma ve eğitim amaçlıdır. Klinik karar için kullanmayın."
            );
        Dialogs.showMessageDialog(WorkshopI18n.t("about.title"), body);
    }

    // ─── QuPathExtension contract ─────────────────────────────────────

    @Override
    public String getName() {
        return WorkshopI18n.t("ext.name");
    }

    @Override
    public String getDescription() {
        return WorkshopI18n.t("ext.description");
    }

    @Override
    public Version getVersion() {
        return Version.parse("0.2.2-rc11");
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse("0.6.0");
    }

    @Override
    public GitHubProject.GitHubRepo getRepository() {
        return GitHubProject.GitHubRepo.create(getName(), "sbalci", "qupath-extension-workshop");
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private static BooleanBinding disableBinding(QuPathGUI qupath, ScriptEntry entry) {
        BooleanBinding lock = enableExtensionProperty.not();
        return entry.needsImage ? lock.or(qupath.imageDataProperty().isNull()) : lock;
    }

    private static final class ScriptEntry {
        final String label;
        final String resource;
        final String i18nKey;
        final boolean disabled;
        final boolean needsImage;

        ScriptEntry(String label, String resource) {
            this(label, resource, "script." + resource, false, true);
        }

        ScriptEntry(String label, String resource, boolean disabled) {
            this(label, resource, "script." + resource, disabled, true);
        }

        ScriptEntry(String label, String resource, boolean disabled, boolean needsImage) {
            this(label, resource, "script." + resource, disabled, needsImage);
        }

        ScriptEntry(String label, String resource, String i18nKey, boolean disabled, boolean needsImage) {
            this.label = label;
            this.resource = resource;
            this.i18nKey = i18nKey;
            this.disabled = disabled;
            this.needsImage = needsImage;
        }

        String currentLabel() {
            String loc = WorkshopI18n.t(i18nKey);
            return i18nKey.equals(loc) ? label : loc;
        }
    }
}
