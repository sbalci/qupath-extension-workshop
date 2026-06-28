/*
 * QuPath Atölye Scriptleri Extension
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
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.zip.ZipException;

import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.Property;
import javafx.beans.property.StringProperty;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

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
     * menu label (Turkish) to the script filename inside the JAR's
     * {@code /scripts/} resource directory (ASCII for cross-platform safety).
     */
    private static final List<ScriptEntry> SCRIPTS = List.of(
        new ScriptEntry("Modül 2 - Hücre tespiti",                  "modul-02-hucre-tespiti.groovy"),
        new ScriptEntry("Modül 3a - Nükleer boya (Ki-67)",          "modul-03a-nukleer-boya.groovy"),
        new ScriptEntry("Modül 3b - ER / PR H-score",               "modul-03b-er-pr-hscore.groovy"),
        new ScriptEntry("Modül 4 - Membran boya (HER2)",            "modul-04-membran-boya.groovy"),
        new ScriptEntry("Modül 5 - Sitoplazmik boya (CD68)",        "modul-05-sitoplazmik-boya.groovy"),
        // Modül 6: tek pencere sihirbaz — örnek modeli kur YA DA yeni eğit, sonra
        // seçili bölge / tüm slayt ölçümü (Ignore* dışlama dahil) hepsi burada.
        // Eski 6a (eğit) ve 6b (uygula) sihirbaza katlandı; scriptler JAR'da +
        // handson/scripts'te kalır (Automate → Project scripts'ten erişilebilir).
        // Menüye geri eklemek için aşağıdaki iki satırın yorumunu kaldırın.
        new ScriptEntry("Modül 6 - Tümör/Stroma sihirbazı", "modul-06-sihirbaz.groovy"),
        // new ScriptEntry("Modül 6a - Tümör/Stroma modeli oluştur (eğit)", "modul-06-model-egit.groovy"),
        // new ScriptEntry("Modül 6b - Tümör vs stroma (uygula)",      "modul-06-tumor-stroma.groovy"),
        new ScriptEntry("Modül 7 - Tümör içi Ki-67",                "modul-07-tumor-ici-ki67.groovy"),
        // Modül 8: StarDist eklentisi + sihirbaz içinde interaktif eğitilen nesne sınıflandırıcı
        // gerektirir; StarDist yoksa sihirbaz kullanıcıyı kuruluma yönlendirir (çökmemez).
        new ScriptEntry("Modül 8 - QuANTUM cTCF",                   "modul-08-quantum-ctcf.groovy"),
        new ScriptEntry("Modül 9 - Veri dışa aktarma",              "modul-09-veri-aktarma.groovy")
    );

    /**
     * Utility (non-module) scripts displayed below a separator, after the
     * numbered workflow modules. Add new helpers here without touching the
     * module list.
     */
    private static final List<ScriptEntry> UTILITY_SCRIPTS = List.of(
        new ScriptEntry("Tespitleri sil",              "yardimci-tespitleri-sil.groovy"),
        new ScriptEntry("Görüntü tipi ayarla",         "yardimci-image-type.groovy", false, false),  // tüm-proje kapsamı: açık slayt gerekmez
        new ScriptEntry("Eşikleri ayarla",             "yardimci-esik-ayarla.groovy"),
        new ScriptEntry("Kalibrasyon (piksel boyutu)", "yardimci-kalibrasyon.groovy"),
        // "Analiz etmeden önce verine bak" — bioimagebook (Bankhead, CC-BY 4.0) Bölüm 1'in
        // tek-tık karşılığı: salt-okur künye + kanal histogramı + doygunluk/clipping.
        // bkz. Ekler → Görüntü Analizi Temelleri.
        new ScriptEntry("Görüntü künyesi ve histogram", "yardimci-goruntu-kunye.groovy"),
        // Ekran kaydı / canlı sunum yardımcısı — bastığınız tuş ve fare işlemlerini
        // ekranda gösterir/gizler (qupath.fx.controls.InputDisplay; aç/kapa anahtarı).
        new ScriptEntry("Tuş/fare göstergesi (kayıt için)", "yardimci-tus-fare-gostergesi.groovy", false, false),  // global ekran katmanı: açık slayt gerekmez
        // Tek-pencere sihirbaz: mevcut vektörleri raporlar (kontrol) + seçili bölgeden
        // tahmin → önizle → uygula → geri al, hepsi aynı pencerede. Eski iki ayrı
        // yardımcı ("kontrol et" + "tahmin et") buna katlandı.
        new ScriptEntry("Boya vektörleri sihirbazı", "yardimci-boya-vektor-sihirbaz.groovy"),
        // Boya KALİTESİ QC: seçili bölgede H:E OD oranı + ortalama OD + CIELAB L* (H:E L* oranı,
        // Dunn 2025 0,94–0,99) + doku%. Tarayıcı/zaman karşılaştırma; ölçüm-only. Bkz. Ek A.
        new ScriptEntry("Boya kalitesi QC ölçümü", "yardimci-boya-kalite-qc.groovy"),
        // Salt-okur renk denetçisi — açık slaytın dosyasında gömülü ICC profilini
        // (standart TIFF etiketi 34675 + Aperio/GT450 taşınmış 0xFFFF) Bio-Formats ile
        // okur; ImageScope↔QuPath renk farkını açıklar. Görüntüyü DEĞİŞTİRMEZ; profili
        // UYGULAMAZ (QuPath 0.6/0.7 ICC uygulamayı kararlı desteklemez — qupath#982).
        // bkz. Ekler → Renk Yönetimi (ICC).
        new ScriptEntry("ICC renk profili denetçisi", "yardimci-icc-denetci-sihirbaz.groovy"),
        new ScriptEntry("Örnek tümör/stroma sınıflandırıcısını projeye kaydet", "yardimci-ornek-siniflandirici.groovy", false, false),  // proje düzeyi: açık slayt gerekmez
        new ScriptEntry("Eşik ile alan ölçümü",        "yardimci-esik-alan.groovy"),
        // Çok-sınıflı / fenotip sonuçlarında her sınıfın adet + % dağılımı (FS2K Session 6–8 /
        // CellClassPct eşi). Tespit YAPMAZ; var olan sınıflandırmaları seçili bölgede sayar.
        new ScriptEntry("Sınıf bazlı hücre sayımı (% dağılım)", "yardimci-sinif-sayim.groovy"),
        // ── Uzamsal analiz (FS2K Session 12) — var olan tespitlerden doku düzenini ÖLÇER
        // (klinik yorum üretmez). bkz. Ekler → Uzamsal Komşuluk Analizi (Ek M).
        // Yapıya uzaklık: her hücrenin merkezinden seçili yapının (tümör sınırı, damar,
        // invazyon kenarı) sınırına işaretli µm mesafe (− = içeride); TIL (Ek O) / TSR (Ek L)'yi besler.
        new ScriptEntry("Yapıya uzaklık (sınıra mesafe)", "yardimci-yapi-uzaklik.groovy"),
        new ScriptEntry("Delaunay komşuluk özellikleri",  "yardimci-delaunay-komsuluk.groovy"),
        new ScriptEntry("En yakın komşu mesafesi",        "yardimci-nn-mesafe.groovy"),
        new ScriptEntry("Yoğunluk haritası",              "yardimci-yogunluk-haritasi.groovy"),
        // WSInfer (qupath-extension-wsinfer) karo tespitlerini sınıf bazında ALAN (mm²) + %'ye toplar
        // (+ ops. sınıf başına birleştirme). Çıkarım yapmaz; var olan karoları özetler. bkz. Ekler → WSInfer.
        new ScriptEntry("WSInfer karo özeti (sınıf alanı / %)", "yardimci-wsinfer-ozet.groovy"),
        new ScriptEntry("Karo (tile) dışa aktarma",    "yardimci-karo-disa-aktarma.groovy"),
        // OME-Zarr dışa aktarma — QuPath'in YERLEŞİK OME-Zarr (OME-NGFF) yazıcısını saran
        // sihirbaz; açık slaytı/seçili bölgeyi parçalı + piramidal .ome.zarr deposuna yazar.
        // Python tarafı (salt-okur inceleyici): handson/python/omezarr. bkz. Ekler → OME-Zarr / OME-NGFF.
        new ScriptEntry("OME-Zarr dışa aktarma",       "yardimci-omezarr-disa-aktarma.groovy"),
        new ScriptEntry("Makine öğrenmesi için özellik matrisi", "yardimci-ozellik-matrisi.groovy"),
        // Fenotipleme round-trip eşi: küme / UMAP sonuçlarını TSV'den tespitlere geri yazar
        // (bkz. Ekler → Hücre Fenotipleme; canlı köprü için Ekler → QuBaLab).
        new ScriptEntry("Kümeleme/fenotip etiketlerini içe aktar (TSV)", "yardimci-kume-etiketi-iceaktar.groovy"),
        // TIA Toolbox (Python) köprüsü — bkz. Ekler → TIA Toolbox
        new ScriptEntry("TIA Toolbox için bölge maskesi",        "yardimci-tiatoolbox-maske.groovy"),
        // TIA Toolbox temel-işlev sihirbazı — boya normalizasyonu (Macenko/Vahadane/Reinhard;
        // QuPath'in YAPMADIĞI işlem) + doku maskesi (Otsu/Morphological → "Doku" anotasyonu).
        // Python köprüsü: handson/python/tiatoolbox. bkz. Ekler → TIA Toolbox § Temel işlevler.
        new ScriptEntry("TIA Toolbox boya normalizasyonu / doku maskesi sihirbazı", "yardimci-tiatoolbox-sihirbaz.groovy"),
        new ScriptEntry("AI tahmin maskelerini içe aktar (GeoJSON)", "yardimci-tahmin-iceaktar.groovy"),
        // Raster maske köprüsü — indeksli/ikili PNG/TIFF maskeyi anotasyona çevirir
        // (TIA Toolbox bölge maskesi + harici U-Net çıktısı için içe-aktarım eşi)
        new ScriptEntry("Maske görüntüsünü içe aktar",            "yardimci-maske-iceaktar.groovy"),
        // Hizalama dönüşümüyle anotasyon aktar — Interactive image alignment'in (qupath-extension-align)
        // afin matrisini uygular: kaynak slaytın anotasyonlarını PathObjectTools.transformObject ile
        // hedef slayda kilitli kopyalar (seri kesit H&E↔İHK). bkz. Ekler → Görüntü Hizalama §6.
        new ScriptEntry("Hizalama dönüşümüyle anotasyon aktar", "yardimci-hizalama-aktarim.groovy"),
        // Atölye Python ortam yöneticisi — uv tabanlı; aşağıdaki Python köprülerinin
        // (TIA Toolbox / Kaiko / SPIDER / Sectra) izole venv'lerini tek tıkla kurar/onarır.
        // Açık slayt gerekmez (proje/sistem düzeyi).
        new ScriptEntry("Atölye Python ortam yöneticisi",        "yardimci-python-ortam-yoneticisi.groovy", false, false),
        // GrandQC (Python) köprüsü — hibrit doku/artefakt KK; bkz. Ekler → Ek B § GrandQC
        new ScriptEntry("GrandQC kalite kontrol sihirbazı",      "yardimci-grandqc-sihirbaz.groovy"),
        // Kaiko Midnight (Python) köprüsü — denetimli FM sınıflandırıcı (eğit → tahmin);
        // bkz. Ekler → Kaiko Midnight Denetimli FM Sınıflandırıcı
        new ScriptEntry("Kaiko Midnight sınıflandırıcı sihirbazı", "yardimci-kaiko-sihirbaz.groovy"),
        // SPIDER (Python) köprüsü — organ-özelleşmiş HAZIR sınıflandırıcı (yalnız tahmin,
        // eğitim yok; CC BY-NC, kapılı); bkz. Ekler → SPIDER Organ Doku Sınıflandırıcı
        new ScriptEntry("SPIDER doku sınıflandırıcı sihirbazı", "yardimci-spider-sihirbaz.groovy"),
        // TIA Toolbox (Python) köprüsü — model çıkarımını SEÇİLİ BÖLGEYLE sınırlar (resmî
        // TIAToolbox eklentisi bölgeye kısıtlayamaz: yalnız Current/All image). KongNet MIDOG
        // mitoz / PanNuke / CoNIC ... ; ikili bölge maskesi → engine.run(masks=) → GeoJSON →
        // bölgeye göre sayım. Çalışma zamanı: tiatoolbox-runtime/.venv; bkz. Ekler → TIA Toolbox.
        new ScriptEntry("TIA Toolbox bölgede çekirdek/mitoz tespiti", "yardimci-tiatoolbox-bolge-sihirbaz.groovy"),
        // metadata-qupath (sbalci, MIT) köprüsü — proje genelinde slayt/tarayıcı üst verisini
        // okur → CSV + (ops.) sıralanabilir Proje sütunları; bkz. Ekler → Kohort Metadata
        new ScriptEntry("Kohort metadata sihirbazı", "yardimci-metadata-sihirbaz.groovy", false, false),  // proje geneli, salt-okunur: açık slayt gerekmez
        // sectra-qupath (sbalci, MIT) köprüsü — Sectra PACS DICOM (GSPS) anotasyonlarını
        // GeoJSON'a çevirip içe aktarır; bkz. Ekler → Klinik PACS → QuPath Entegrasyonu
        new ScriptEntry("Sectra PACS anotasyon sihirbazı", "yardimci-sectra-iceaktar.groovy"),
        // Arayüz turu — interaktif QuPath UI gezintisi (qupath-extension-training ilhamı,
        // Apache-2.0, Pete Bankhead). Saf eğitim; nesne/ölçüm DEĞİŞTİRMEZ; bölge vurgusu +
        // güvenli geri çekilme. bkz. Ekler → Arayüz Turu; statik karşılığı Modül 1 — Arayüz turu.
        new ScriptEntry("Arayüz turu", "yardimci-arayuz-turu.groovy"),
        // Görüntü işleme kavramları — Bankhead'in dijital patoloji görüntü-işleme sözlüğünü
        // (CC-BY 4.0) KENDİ slaydında canlı önizlemelerle gezdiren tur: dekonvolüsyon → Gaussian (σ)
        // → eşik (ikili) → mesafe + watershed. Modül 2'nin "perde arkası"; saf eğitim, salt-okur.
        // bkz. Ekler → Görüntü Analizi Temelleri.
        new ScriptEntry("Görüntü işleme kavramları", "yardimci-goruntu-isleme-turu.groovy"),
        // StarDist (yerel QuPath eklentisi) köprüsü — seçili ROI'de H&E çekirdek tespiti +
        // sayım/yoğunluk (Modül 8'i etkinleştirmeden no-code yol); StarDist eklentisi yoksa
        // kullanıcıyı kuruluma yönlendirir. bkz. Ekler → Ek G (StarDist Eklentisi).
        new ScriptEntry("StarDist çekirdek tespiti sihirbazı", "yardimci-stardist-sihirbaz.groovy"),
        // Cellpose (BIOP qupath-extension-cellpose) köprüsü — Cellpose2D builder'ını tek
        // pencereden kurar; cyto3/cpsam/Omnipose + brightfield İHK kanal hazırlığı. BIOP
        // eklentisi + Python venv gerektirir (yoksa sihirbaz kuruluma yönlendirir).
        // bkz. Ekler → Ek F (Cellpose Eklentisi).
        new ScriptEntry("Cellpose hücre/çekirdek tespiti sihirbazı", "yardimci-cellpose-sihirbaz.groovy"),
        // Tespit doğrulama (F1 / IoU) — otomatik tespiti ELLE çizilmiş altın standartla
        // karşılaştırır (TP/FP/FN → precision/recall/F1). Salt Groovy/JTS, ek bağımlılık yok.
        // Pécot WSI-QuPath eğitiminin (CC-BY) atölye karşılığı. bkz. Ekler → Tespit Doğrulama.
        new ScriptEntry("Tespit doğrulama (F1 / IoU)", "yardimci-dogrulama-f1.groovy"),
        // Görüntü yakala — görüntüleyiciyi (overlay'lerle) veya tüm pencereyi PNG/JPEG
        // dosyasına/panoya alır; rapor & sunum için. QuPath GuiTools.makeSnapshot kullanır
        // (petebankhead/qupath-extension-snapshots ilhamı). Ölçüm YAPMAZ; saf yakalama aracı.
        new ScriptEntry("Görüntü yakala (rapor/sunum)", "yardimci-goruntu-yakala.groovy", false, false),  // açık slayt şart değil — pencere/UI de yakalanır
        // ── İleri analiz — atölye ilk oturumundan sonra etkinleştirildi (eski "sonraki oturum" grubu) ──
        // Ölçüm/skorlama yardımcıları (var olan tespit/sınıflandırmalardan türetir):
        new ScriptEntry("Ki-67 heterojenlik grid",              "yardimci-ki67-heterojenlik.groovy"),
        new ScriptEntry("Stromal TIL yoğunluğu",                "yardimci-stromal-til.groovy"),
        new ScriptEntry("Alan-bazlı pozitiflik (% positivity)", "yardimci-alan-pozitiflik.groovy"),
        new ScriptEntry("TMA çekirdek bazlı dışa aktarım",      "yardimci-tma-cekirdek-aktarim.groovy"),
        new ScriptEntry("İmmün hücre yoğunluğu (DAB)",          "yardimci-immun-yogunluk.groovy"),
        new ScriptEntry("PHH3 mitoz kantifikasyonu",            "yardimci-mitoz-phh3.groovy"),
        new ScriptEntry("KongNet H&E mitoz tespiti (DL)",       "yardimci-mitoz-kongnet.groovy"),
        new ScriptEntry("Tümör tomurcuklanma kantifikasyonu (CK / ITBCC)", "yardimci-tumor-tomurcuklanma.groovy"),
        new ScriptEntry("WSI anonimleştirme sihirbazı",         "yardimci-anonim-sihirbaz.groovy"),
        // ImageJ/Fiji köprüsü + QuPath-içi grafik + InstanSeg (I2K 2024 uyarlamaları):
        new ScriptEntry("ImageJ ile otomatik eşik → anotasyon", "yardimci-imagej-otsu-anotasyon.groovy"),
        new ScriptEntry("ImageJ ile sınır yumuşat (spline)",    "yardimci-imagej-spline-duzeltme.groovy"),
        new ScriptEntry("Dağılım grafiği (scatter chart)",      "yardimci-dagilim-grafigi.groovy"),
        new ScriptEntry("InstanSeg çekirdek/hücre tespiti sihirbazı", "yardimci-instanseg-sihirbaz.groovy")
    );

    /**
     * Advanced-analysis helpers prepared for a LATER workshop session. They are
     * bundled in the JAR and kept byte-synced with handson/scripts (see
     * tools/check-script-sync.ps1), but rendered as DISABLED (greyed-out) menu
     * items below so participants can see what's coming without running them
     * yet. To activate one later, move its entry into {@link #UTILITY_SCRIPTS}.
     */
    private static final List<ScriptEntry> UPCOMING_SCRIPTS = List.<ScriptEntry>of(
        // Atölye ilk oturumu tamamlandı — eski "İleri analiz — sonraki oturum" yardımcılarının
        // tümü UTILITY_SCRIPTS'e taşındı ve artık etkin. Yeni hazırlanıp henüz
        // etkinleştirilmemiş bir yardımcı çıkarsa buraya (gri/önizleme) eklenir.
    );

    private boolean alreadyInstalled = false;

    /**
     * Master toggle. When off, the runnable Atölye module/utility menu items grey
     * out (an "instructor lock" so participants can't click ahead). Persisted via
     * PathPrefs and surfaced in QuPath's Preferences pane (see installPreferences).
     */
    public static final BooleanProperty enableExtensionProperty =
            PathPrefs.createPersistentPreference("atolye.enableExtension", true);

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (alreadyInstalled) {
            logger.warn("Workshop extension already installed; skipping duplicate install.");
            return;
        }
        alreadyInstalled = true;

        try {
            var menu = qupath.getMenu(MENU_PATH, true);

            // Header item (disabled) so the menu purpose is obvious
            var header = new MenuItem("— Atölye scriptleri —");
            header.setDisable(true);
            menu.getItems().add(header);
            menu.getItems().add(new SeparatorMenuItem());

            Menu modulesMenu = new Menu("Modüller");
            for (ScriptEntry entry : SCRIPTS) {
                MenuItem item = new MenuItem(entry.label);
                if (entry.disabled) {
                    item.setDisable(true);   // sonraki oturuma ertelendi — gri görünür, tıklama etkisiz
                } else {
                    item.setOnAction(e -> runScriptSafely(qupath, entry));
                    item.disableProperty().bind(disableBinding(qupath, entry));
                }
                modulesMenu.getItems().add(item);
            }
            menu.getItems().add(modulesMenu);

            if (!UTILITY_SCRIPTS.isEmpty()) {
                Menu utilsMenu = new Menu("Yardımcılar");
                for (ScriptEntry entry : UTILITY_SCRIPTS) {
                    MenuItem item = new MenuItem(entry.label);
                    item.setOnAction(e -> runScriptSafely(qupath, entry));
                    item.disableProperty().bind(disableBinding(qupath, entry));
                    utilsMenu.getItems().add(item);
                }
                menu.getItems().add(utilsMenu);
            }

            // İleri analiz — sonraki oturum (gri/disabled; tıklama etkisiz, yalnızca önizleme)
            if (!UPCOMING_SCRIPTS.isEmpty()) {
                Menu upcomingMenu = new Menu("İleri analiz — sonraki oturum");
                for (ScriptEntry entry : UPCOMING_SCRIPTS) {
                    MenuItem item = new MenuItem(entry.label);
                    item.setDisable(true);   // henüz etkin değil — sonraki oturumda açılacak
                    upcomingMenu.getItems().add(item);
                }
                menu.getItems().add(upcomingMenu);
            }

            menu.getItems().add(new SeparatorMenuItem());
            var settings = new MenuItem("Atölye Ayarları…");
            settings.setOnAction(e -> WorkshopSettingsDialog.show());
            menu.getItems().add(settings);

            var envCheck = new MenuItem("Ortam kontrolü…");
            envCheck.setOnAction(e -> showEnvironmentCheck(qupath));
            menu.getItems().add(envCheck);

            menu.getItems().add(new SeparatorMenuItem());
            var about = new MenuItem("Atölye hakkında…");
            about.setOnAction(e -> showAboutDialog());
            menu.getItems().add(about);

            installPreferences(qupath);

            logger.info("Workshop extension installed with {} module + {} utility + {} upcoming (disabled) scripts.",
                    SCRIPTS.size(), UTILITY_SCRIPTS.size(), UPCOMING_SCRIPTS.size());
        } catch (Exception ex) {
            logger.error("Failed to install Workshop extension menu", ex);
        }
    }

    /**
     * Registers the master toggle and every adjustable workshop parameter in
     * QuPath's native Preferences pane (Edit → Preferences), grouped by section
     * under "Atölye" / "Atölye · &lt;bölüm&gt;". This mirrors the richer
     * Extensions → Atölye → Atölye Ayarları… window for users who look in
     * Preferences. Each numeric pref is exposed via {@code asObject()} so edits
     * write straight back to the persisted PathPrefs property.
     */
    @SuppressWarnings("unchecked")
    private void installPreferences(QuPathGUI qupath) {
        try {
            var items = qupath.getPreferencePane().getPropertySheet().getItems();

            items.add(new PropertyItemBuilder<>(enableExtensionProperty, Boolean.class)
                    .name("Atölye menüsü etkin")
                    .category("Atölye")
                    .description("Kapalıyken Atölye modül ve yardımcı menü öğeleri devre dışı olur "
                            + "(eğitmen kilidi). Tüm eşik/parametre ayarları için ayrıca: "
                            + "Extensions → Atölye → Atölye Ayarları…")
                    .build());

            for (String key : WorkshopPrefs.keys()) {
                Property<?> p = WorkshopPrefs.property(key);
                String name = key.startsWith("atolye.") ? key.substring("atolye.".length()) : key;
                String category = "Atölye · " + WorkshopPrefs.section(key);
                PropertySheet.Item item = null;
                if (p instanceof DoubleProperty dp) {
                    item = new PropertyItemBuilder<>(dp.asObject(), Double.class).name(name).category(category).build();
                } else if (p instanceof IntegerProperty ip) {
                    item = new PropertyItemBuilder<>(ip.asObject(), Integer.class).name(name).category(category).build();
                } else if (p instanceof BooleanProperty bp) {
                    item = new PropertyItemBuilder<>(bp.asObject(), Boolean.class).name(name).category(category).build();
                } else if (p instanceof StringProperty sp) {
                    item = new PropertyItemBuilder<>(sp, String.class).name(name).category(category).build();
                }
                if (item != null) items.add(item);
            }
        } catch (Throwable t) {
            logger.warn("Could not register Atölye preferences in QuPath's Preferences pane", t);
        }
    }

    /**
     * Loads the named script from JAR resources and evaluates it through
     * a fresh {@link GroovyShell}. Runs on a background thread so the FX
     * thread isn't blocked while the script displays its own dialogs.
     */
    private void runScriptSafely(QuPathGUI qupath, ScriptEntry entry) {
        String scriptBody;
        try {
            scriptBody = readScriptResource(entry.resource);
        } catch (JarCorruptedException jce) {
            Dialogs.showErrorMessage(
                "Eklenti yeniden yüklenmeli",
                "QuPath bu eklenti JAR'ını başlatma sırasında okudu, ama dosya bundan sonra " +
                "değişmiş görünüyor (büyük olasılıkla atölye eklentisinin yeni bir sürümü ile " +
                "değiştirildi). Java'nın iç ZIP indeksi artık dosyanın güncel içeriğiyle " +
                "eşleşmiyor.\n\n" +
                "Çözüm:\n" +
                "  1. QuPath'ı tamamen kapatın (sadece projeyi değil)\n" +
                "  2. QuPath'ı yeniden açın\n" +
                "  3. Atölye menüsünü tekrar deneyin\n\n" +
                "Eğer hata devam ederse JAR dosyasının bozulmuş olabileceği için " +
                "yeni bir kopyasını atölye organizatöründen isteyin."
            );
            return;
        }
        if (scriptBody == null) {
            Dialogs.showErrorMessage(
                "Script bulunamadı",
                "Eklenti içinde script kaynağı bulunamadı:\n  " + entry.resource +
                "\n\nLütfen güncel sürümü kullandığınızdan emin olun ve atölye organizatörüne bildirin."
            );
            return;
        }

        Thread runner = new Thread(() -> {
            try {
                Binding binding = new Binding();
                // Provide a hint binding so QuPath helpers resolve cleanly even without
                // a project script context.
                binding.setVariable("EXTENSION_NAME", "qupath-extension-workshop");

                GroovyShell shell = new GroovyShell(
                    qupath.getClass().getClassLoader(),
                    binding
                );
                shell.evaluate(scriptBody, entry.resource);
            } catch (Throwable t) {
                logger.error("Script execution failed: {}", entry.label, t);
                String msg = t.getMessage();
                String detail = t.getClass().getSimpleName() + ": " + (msg != null ? msg : "(no message)");
                Platform.runLater(() -> Dialogs.showErrorMessage(
                    "Script hatası — " + entry.label,
                    "Script çalıştırılırken bir hata oluştu:\n\n" +
                    detail +
                    "\n\nDetaylar için View → Show log dialogue'a bakın."
                ));
            }
        }, "WorkshopScript-" + entry.resource);
        runner.setDaemon(true);
        runner.start();
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
            // BufferedReader.lines() wraps IOException as UncheckedIOException.
            // ZipException here almost always means the JAR was replaced on disk
            // while QuPath had it open — stale central directory points at offsets
            // that no longer contain valid local file headers in the new JAR.
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

    /** Marker exception: JAR central directory and on-disk content disagree.
     *  Almost always caused by replacing the JAR while QuPath has it open. */
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

    /**
     * Diagnostic dialog for workshop-day troubleshooting. Reports the running
     * QuPath version, the current image/project state, and whether the optional
     * sibling extensions used by advanced/deferred modules (Cellpose, StarDist,
     * InstanSeg) — plus the shared Deep Java Library (DJL) runtime they rely on —
     * are present on the classpath, so a participant can see at a glance what
     * still needs installing instead of discovering it via a cryptic error
     * mid-script. Core modules (2, 3, 3b, 5, 6, 7, 9) need only QuPath.
     */
    private void showEnvironmentCheck(QuPathGUI qupath) {
        String found = "✅ bulundu";
        String missing = "—  bulunamadı";
        boolean hasImage = qupath.getImageData() != null;
        boolean hasProject = qupath.getProject() != null;
        boolean cellpose = isOnClasspath("qupath.ext.biop.cellpose.Cellpose2D",
                                         "qupath.ext.biop.cellpose.CellposeExtension");
        boolean stardist = isOnClasspath("qupath.ext.stardist.StarDist2D",
                                         "qupath.ext.stardist.StarDistExtension");
        boolean instanseg = isOnClasspath("qupath.ext.instanseg.core.InstanSeg",
                                          "qupath.ext.instanseg.InstanSegExtension");
        // DJL is the in-process inference runtime shared by InstanSeg, WSInfer
        // and the TensorFlow build of StarDist — a missing DJL is the most common
        // reason "InstanSeg won't run". ai.djl.engine.Engine is the stable
        // canonical FQN; the qupath.ext.djl.* names are belt-and-suspenders.
        boolean djl = isOnClasspath("ai.djl.engine.Engine",
                                    "qupath.ext.djl.DjlTools",
                                    "qupath.ext.djl.DjlExtension");
        Dialogs.showMessageDialog(
            "Atölye — Ortam kontrolü",
            "QuPath sürümü:     " + GeneralTools.getVersion() + "\n" +
            "Atölye eklentisi:  v" + getVersion() + "  (derlenme " + BUILD_TIMESTAMP + ")\n" +
            "QuPath baseline:   " + getQuPathVersion() + "+\n\n" +
            "Açık görüntü:      " + (hasImage ? "var" : "yok — File → Open ile bir slayt açın") + "\n" +
            "Açık proje:        " + (hasProject ? "var" : "yok") + "\n\n" +
            "Opsiyonel bileşenler (yalnızca ileri modüller için):\n" +
            "  • Cellpose eklentisi:   " + (cellpose ? found : missing) + "\n" +
            "  • StarDist eklentisi:   " + (stardist ? found : missing) + "\n" +
            "  • InstanSeg eklentisi:  " + (instanseg ? found : missing) + "\n" +
            "  • Deep Java Library:    " + (djl ? found : missing) + "  (InstanSeg/WSInfer/StarDist-TF ortak çalışma zamanı)\n\n" +
            "Çekirdek modüller (2, 3, 3b, 4, 5, 6, 7, 9) yalnızca QuPath gerektirir; Modül 8 StarDist eklentisi ister.\n" +
            "InstanSeg ayrı bir Python ortamı gerektirmez (en sade derin öğrenme seçeneği).\n" +
            (cellpose ? "Cellpose için python.exe yolunu ayarlayın: Edit → Preferences → Cellpose/Omnipose.\n" : "") +
            "\"bulunamadı\" görünen bileşenler yalnızca ilgili ileri modül için gerekir;\n" +
            "kurulum rehberi: https://atolye.patoloji.dev/kaynaklar.html#ileri-kurulumlar\n\n" +
            "Yalnızca araştırma ve eğitim amaçlıdır."
        );
    }

    /**
     * True if any candidate fully-qualified class name resolves on the current
     * classpath. Used to detect optional sibling extensions without a hard
     * compile-time dependency on them. {@code initialize=false} avoids running
     * the target class's static initializers.
     */
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
        Dialogs.showMessageDialog(
            "QuPath Atölye Scriptleri",
            "Patologlar için dijital patoloji ve yapay zekâ atölyesinin\n" +
            "tek-tıkla scriptlerini bir araya getirir.\n\n" +
            "Modüller:\n" +
            "  2 — Hücre tespiti\n" +
            "  3 — Nükleer boya (Ki-67)\n" +
            "  3b — ER / PR H-score\n" +
            "  4 — Membran boya (HER2)\n" +
            "  5 — Sitoplazmik boya (CD68)\n" +
            "  6 — Tümör/Stroma modeli oluştur (eğit) + Tümör vs stroma (uygula)\n" +
            "  7 — Tümör içi Ki-67\n" +
            "  8 — QuANTUM cTCF (StarDist + nesne sınıflandırıcı eğitimi)\n" +
            "  9 — Veri dışa aktarma (TSV / GeoJSON)\n\n" +
            "Yardımcılar:\n" +
            "  • Tespitleri sil (orphan / tümü)\n" +
            "  • Görüntü tipi ayarla (slayt / proje)\n" +
            "  • Eşikleri ayarla (yeniden tespit etmeden re-binning)\n" +
            "  • Kalibrasyon (piksel boyutu — µm/px ayarla)\n" +
            "  • Boya vektörleri sihirbazı (kontrol + seçili bölgeden tahmin; önizle → uygula → geri al)\n" +
            "  • Örnek tümör/stroma sınıflandırıcısı (projeye kaydet)\n" +
            "  • Karo (tile) dışa aktarma (derin öğrenme için görüntü/maske karoları)\n" +
            "  • Makine öğrenmesi için özellik matrisi (X özellik + y etiket, TSV)\n" +
            "  • TIA Toolbox için bölge maskesi (tek-kanallı maske, masks= için)\n" +
            "  • AI tahmin maskelerini içe aktar (GeoJSON → kilitli anotasyon)\n" +
            "  • Sectra PACS anotasyon sihirbazı (DICOM GSPS → GeoJSON → QuPath)\n\n" +
            "İleri analiz yardımcıları (heterojenlik, stromal TIL, alan pozitifliği, TMA,\n" +
            "immün yoğunluk, PHH3/KongNet mitoz, tümör tomurcuklanma, anonimleştirme,\n" +
            "ImageJ köprüsü, dağılım grafiği, InstanSeg) artık Yardımcılar menüsünde etkin.\n\n" +
            "Ayarlar:\n" +
            "  • Atölye Ayarları — parametreleri değiştir, hatırlanır, sıfırlanabilir\n\n" +
            "Versiyon: " + getVersion() + "\n" +
            "Derlenme tarihi: " + BUILD_TIMESTAMP + "\n" +
            "QuPath baseline: " + getQuPathVersion() + "+\n\n" +
            "🌐 Atölye sitesi: https://atolye.patoloji.dev\n" +
            "👤 İletişim:     https://www.serdarbalci.com\n" +
            "✉️  İletişim:     serdarbalci@serdarbalci.com\n\n" +
            "Yalnızca araştırma ve eğitim amaçlıdır. Klinik karar için kullanmayın."
        );
    }

    // ─── QuPathExtension contract ─────────────────────────────────────

    @Override
    public String getName() {
        return "Atölye Scriptleri";
    }

    @Override
    public String getDescription() {
        return "Patolog atölyesi tek-tıkla iş akışları: hücre tespiti, IHC, tümör/stroma, cTCF.";
    }

    @Override
    public Version getVersion() {
        return Version.parse("0.2.2-rc4");
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse("0.6.0");
    }

    // ─── GitHubProject contract ───────────────────────────────────────
    // Lets QuPath check GitHub Releases and notify the user when a newer
    // workshop-extension version is available (participants typically install
    // the JAR once and work offline for months afterwards). NOTE: this is the
    // extension's OWN repo — unrelated to getQuPathVersion()'s 0.6.0 baseline.

    @Override
    public GitHubProject.GitHubRepo getRepository() {
        return GitHubProject.GitHubRepo.create(getName(), "sbalci", "qupath-extension-workshop");
    }

    // ─── helpers ───────────────────────────────────────────────────────

    /**
     * Disable-binding for a runnable menu item. Always greyed while the instructor
     * lock is off; additionally greyed while no image is open when the script needs
     * a slide — mirroring how sibling extensions (e.g. LiverQuant) grey their menu
     * item via {@code imageDataProperty().isNull()}. Project-wide helpers
     * ({@code needsImage == false}, e.g. the cohort-metadata wizard, project-scope
     * image-type setter, sample-classifier saver) bind to the lock only, so they
     * stay clickable with no image open.
     */
    private static BooleanBinding disableBinding(QuPathGUI qupath, ScriptEntry entry) {
        BooleanBinding lock = enableExtensionProperty.not();
        return entry.needsImage ? lock.or(qupath.imageDataProperty().isNull()) : lock;
    }

    private static final class ScriptEntry {
        final String label;
        final String resource;
        /** When true the menu item is shown greyed-out / unclickable (deferred to a later session). */
        final boolean disabled;
        /** When true the item additionally greys out while no image is open (most
         *  analysis scripts need a slide). Project-wide helpers pass {@code false}. */
        final boolean needsImage;

        ScriptEntry(String label, String resource) {
            this(label, resource, false, true);
        }

        ScriptEntry(String label, String resource, boolean disabled) {
            this(label, resource, disabled, true);
        }

        ScriptEntry(String label, String resource, boolean disabled, boolean needsImage) {
            this.label = label;
            this.resource = resource;
            this.disabled = disabled;
            this.needsImage = needsImage;
        }
    }
}
