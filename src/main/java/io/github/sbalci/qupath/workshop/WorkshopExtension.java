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
        // Modül 1: interaktif arayüz turu (modul-01-arayuz-turu). QuPath arayüzünü
        // gezdirir; nesne/ölçüm DEĞİŞTİRMEZ ve açık slayt gerektirmez (needsImage=false)
        // — atölyenin ilk adımı, slayt açılmadan çalıştırılabilir. Statik karşılığı
        // Modül 1 (01-qupath-tanitim); ilham: qupath-extension-training (Apache-2.0).
        new ScriptEntry("Modül 1 - Arayüz turu",                    "modul-01-arayuz-turu.groovy", false, false),
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

    /** A named topic group of utility scripts → renders as a sub-menu under "Yardımcılar". */
    private record ScriptGroup(String title, List<ScriptEntry> entries) {}

    /**
     * Utility (non-module) helper scripts, GROUPED BY TOPIC. Each group renders as a
     * sub-menu under "Yardımcılar" (Extensions → Atölye → Yardımcılar → &lt;konu&gt; → script).
     * Add a new helper to the most relevant group (or add a new group); the menu builder
     * renders groups + entries in list order. Byte-identity with handson/scripts is enforced
     * separately in tools/check-script-sync.ps1 (its pair list, not this structure).
     */
    private static final List<ScriptGroup> UTILITY_GROUPS = List.of(
        new ScriptGroup("Temel araçlar", List.of(
            new ScriptEntry("Tespitleri sil",              "yardimci-tespitleri-sil.groovy"),
            new ScriptEntry("Görüntü tipi ayarla",         "yardimci-image-type.groovy", false, false),  // tüm-proje kapsamı: açık slayt gerekmez
            new ScriptEntry("Eşikleri ayarla",             "yardimci-esik-ayarla.groovy"),
            new ScriptEntry("Kalibrasyon (piksel boyutu)", "yardimci-kalibrasyon.groovy"),
            // "Analiz etmeden önce verine bak" — bioimagebook Bölüm 1: salt-okur künye + kanal
            // histogramı + doygunluk/clipping. bkz. Ekler → Görüntü Analizi Temelleri.
            new ScriptEntry("Görüntü künyesi ve histogram", "yardimci-goruntu-kunye.groovy"),
            // Native doku tespiti (OD-sum/hematoksilen Otsu → ContourTracing); Python/ImageJ gerekmez.
            new ScriptEntry("Doku tespiti sihirbazı (native)", "yardimci-doku-tespiti-sihirbaz.groovy"),
            // Salt-okur anotasyon bütünlüğü denetimi: örtüşme/kopya/boş/adsız/sınır-dışı/geçersiz geometri.
            new ScriptEntry("Anotasyon yapısı QC (denetçi)", "yardimci-anotasyon-qc.groovy")
        )),
        new ScriptGroup("Boya & renk", List.of(
            // Tek-pencere: mevcut vektörleri raporlar (kontrol) + seçili bölgeden tahmin → önizle → uygula → geri al.
            new ScriptEntry("Boya vektörleri sihirbazı", "yardimci-boya-vektor-sihirbaz.groovy"),
            // Boya KALİTESİ QC: H:E OD oranı + CIELAB L* + doku% (ölçüm-only); tarayıcı/zaman karşılaştırma. Bkz. Ek A.
            new ScriptEntry("Boya kalitesi QC ölçümü", "yardimci-boya-kalite-qc.groovy"),
            // Salt-okur ICC denetçisi — gömülü profili okur; UYGULAMAZ (qupath#982). bkz. Ekler → Renk Yönetimi (ICC).
            new ScriptEntry("ICC renk profili denetçisi", "yardimci-icc-denetci-sihirbaz.groovy"),
            // Proje geneli boya OD drift raporu (tarayıcı/zaman karşılaştırma → CSV + IQR aykırı işareti); proje-düzeyi, açık slayt gerekmez.
            new ScriptEntry("Kohort boya OD drift raporu", "yardimci-kohort-boya-qc.groovy", false, false)
        )),
        new ScriptGroup("Hücre / çekirdek tespiti", List.of(
            // StarDist (yerel eklenti) köprüsü — seçili ROI'de H&E çekirdek tespiti; yoksa kuruluma yönlendirir. Ek G.
            new ScriptEntry("StarDist çekirdek tespiti sihirbazı", "yardimci-stardist-sihirbaz.groovy"),
            // Cellpose (BIOP eklentisi) köprüsü — cyto3/cpsam/Omnipose + brightfield İHK; Python venv gerekir. Ek F.
            new ScriptEntry("Cellpose hücre/çekirdek tespiti sihirbazı", "yardimci-cellpose-sihirbaz.groovy"),
            // InstanSeg (yerel eklenti) köprüsü — I2K 2024 uyarlaması. Ek H.
            new ScriptEntry("InstanSeg çekirdek/hücre tespiti sihirbazı", "yardimci-instanseg-sihirbaz.groovy"),
            new ScriptEntry("KongNet H&E mitoz tespiti (DL)",       "yardimci-mitoz-kongnet.groovy"),
            // Tespit doğrulama — otomatik tespiti ELLE altın standartla karşılaştırır (TP/FP/FN → F1; JTS IoU). Salt Groovy.
            new ScriptEntry("Tespit doğrulama (F1 / IoU)", "yardimci-dogrulama-f1.groovy")
        )),
        new ScriptGroup("Skorlama & ölçüm", List.of(
            new ScriptEntry("Eşik ile alan ölçümü",        "yardimci-esik-alan.groovy"),
            // Var olan sınıflandırmaları seçili bölgede sayar; her sınıfın adet + % dağılımı (tespit YAPMAZ).
            new ScriptEntry("Sınıf bazlı hücre sayımı (% dağılım)", "yardimci-sinif-sayim.groovy"),
            new ScriptEntry("Ki-67 heterojenlik grid",              "yardimci-ki67-heterojenlik.groovy"),
            new ScriptEntry("Stromal TIL yoğunluğu",                "yardimci-stromal-til.groovy"),
            new ScriptEntry("Alan-bazlı pozitiflik (% positivity)", "yardimci-alan-pozitiflik.groovy"),
            new ScriptEntry("İmmün hücre yoğunluğu (DAB)",          "yardimci-immun-yogunluk.groovy"),
            new ScriptEntry("PHH3 mitoz kantifikasyonu",            "yardimci-mitoz-phh3.groovy"),
            new ScriptEntry("Tümör tomurcuklanma kantifikasyonu (CK / ITBCC)", "yardimci-tumor-tomurcuklanma.groovy"),
            // WSInfer karo tespitlerini sınıf bazında ALAN (mm²) + %'ye toplar (çıkarım yapmaz). bkz. Ekler → WSInfer.
            new ScriptEntry("WSInfer karo özeti (sınıf alanı / %)", "yardimci-wsinfer-ozet.groovy"),
            // Var olan karoları seçili ölçüme göre sıralayıp top-N'i çıkarır (Ki-67 LI, mitoz/mm² vb.). Rehber eşiği YOK.
            new ScriptEntry("Sıralı hotspot seçici (top-N)", "yardimci-hotspot-sirali.groovy"),
            // Sınıf bazlı entegre DAB OD = pozitif alan × ortalama OD (toplam kromojen yükü; kalibrasyona bağlı).
            new ScriptEntry("Entegre DAB OD (alan × OD)", "yardimci-entegre-od.groovy")
        )),
        new ScriptGroup("Uzamsal analiz", List.of(
            // FS2K Session 12 — var olan tespitlerden doku düzenini ÖLÇER (klinik yorum üretmez). bkz. Ek M.
            // Yapıya uzaklık: her hücreden seçili yapının (tümör sınırı, damar) sınırına işaretli µm mesafe.
            new ScriptEntry("Yapıya uzaklık (sınıra mesafe)", "yardimci-yapi-uzaklik.groovy"),
            new ScriptEntry("Delaunay komşuluk özellikleri",  "yardimci-delaunay-komsuluk.groovy"),
            new ScriptEntry("En yakın komşu mesafesi",        "yardimci-nn-mesafe.groovy"),
            new ScriptEntry("Yoğunluk haritası",              "yardimci-yogunluk-haritasi.groovy"),
            // İki sınıflı geometri örtüşmesi: kesişim alanı (µm²) + peritümöral halka (buffer/difference). bkz. Ek M.
            new ScriptEntry("Kesişim alanı (örtüşme)",        "yardimci-kesisim-alani.groovy"),
            new ScriptEntry("Peritümöral bant (halka)",       "yardimci-peritumoral-bant.groovy"),
            // Nesne kümesinin dış bükey zarfı (JTS convexHull) — yayılım alanı. bkz. Ek M.
            new ScriptEntry("Dış bükey zarf (convex hull)",   "yardimci-konveks-zarf.groovy"),
            // Çapraz-tip en yakın komşu: her A hücresinden en yakın B hücresine µm mesafe (sınıf-bağımlı NN).
            new ScriptEntry("Çapraz-tip en yakın komşu (A→B)", "yardimci-capraz-nn-mesafe.groovy")
        )),
        new ScriptGroup("İçe / dışa aktarma & veri", List.of(
            new ScriptEntry("Karo (tile) dışa aktarma",    "yardimci-karo-disa-aktarma.groovy"),
            // QuPath'in YERLEŞİK OME-Zarr (OME-NGFF) yazıcısını saran sihirbaz; parçalı+piramidal .ome.zarr.
            new ScriptEntry("OME-Zarr dışa aktarma",       "yardimci-omezarr-disa-aktarma.groovy"),
            // Görüntü alanı çıkart (Extract Region) — seçili anotasyon(lar)ı bağımsız görüntü dosyasına
            // (OME-TIFF / TIFF / PNG / JPEG, sıkıştırma/çözünürlük seçmeli). Aperio ImageScope Extract Region karşılığı.
            new ScriptEntry("Görüntü alanı çıkart (Extract Region)", "yardimci-bolge-cikart-sihirbaz.groovy"),
            new ScriptEntry("Makine öğrenmesi için özellik matrisi", "yardimci-ozellik-matrisi.groovy"),
            // Küme/UMAP sonuçlarını TSV'den tespitlere geri yazar (fenotipleme round-trip). bkz. Ekler → Hücre Fenotipleme.
            new ScriptEntry("Kümeleme/fenotip etiketlerini içe aktar (TSV)", "yardimci-kume-etiketi-iceaktar.groovy"),
            new ScriptEntry("AI tahmin maskelerini içe aktar (GeoJSON)", "yardimci-tahmin-iceaktar.groovy"),
            // Raster maske köprüsü — indeksli/ikili PNG/TIFF maskeyi anotasyona çevirir.
            new ScriptEntry("Maske görüntüsünü içe aktar",            "yardimci-maske-iceaktar.groovy"),
            // TIA Toolbox için tek-kanallı bölge maskesi (engine.run(masks=) için). bkz. Ekler → TIA Toolbox.
            new ScriptEntry("TIA Toolbox için bölge maskesi",        "yardimci-tiatoolbox-maske.groovy"),
            // Hizalama (qupath-extension-align) afin matrisini uygular: anotasyonları hedef slayda kilitli kopyalar. Ek → Görüntü Hizalama §6.
            new ScriptEntry("Hizalama dönüşümüyle anotasyon aktar", "yardimci-hizalama-aktarim.groovy"),
            new ScriptEntry("TMA çekirdek bazlı dışa aktarım",      "yardimci-tma-cekirdek-aktarim.groovy"),
            new ScriptEntry("Örnek tümör/stroma sınıflandırıcısını projeye kaydet", "yardimci-ornek-siniflandirici.groovy", false, false)  // proje düzeyi: açık slayt gerekmez
        )),
        new ScriptGroup("Python köprüleri & temel modeller", List.of(
            // uv tabanlı ortam yöneticisi — Python köprülerinin izole venv'lerini kurar/onarır (proje/sistem düzeyi).
            new ScriptEntry("Atölye Python ortam yöneticisi",        "yardimci-python-ortam-yoneticisi.groovy", false, false),
            // GrandQC (Python) — hibrit doku/artefakt KK. bkz. Ekler → Ek B § GrandQC.
            new ScriptEntry("GrandQC kalite kontrol sihirbazı",      "yardimci-grandqc-sihirbaz.groovy"),
            // TIA Toolbox: boya normalizasyonu (Macenko/Vahadane/Reinhard; QuPath'in YAPMADIĞI) + doku maskesi. handson/python/tiatoolbox.
            new ScriptEntry("TIA Toolbox boya normalizasyonu / doku maskesi sihirbazı", "yardimci-tiatoolbox-sihirbaz.groovy"),
            // TIA Toolbox model çıkarımını SEÇİLİ BÖLGEYLE sınırlar (KongNet MIDOG mitoz vb.); tiatoolbox-runtime/.venv.
            new ScriptEntry("TIA Toolbox bölgede çekirdek/mitoz tespiti", "yardimci-tiatoolbox-bolge-sihirbaz.groovy"),
            // Kaiko Midnight (Python) — denetimli FM sınıflandırıcı (eğit → tahmin). bkz. Ekler → Kaiko Midnight.
            new ScriptEntry("Kaiko Midnight sınıflandırıcı sihirbazı", "yardimci-kaiko-sihirbaz.groovy"),
            // SPIDER (Python) — organ-özelleşmiş HAZIR sınıflandırıcı (yalnız tahmin; CC BY-NC, kapılı). bkz. Ekler → SPIDER.
            new ScriptEntry("SPIDER doku sınıflandırıcı sihirbazı", "yardimci-spider-sihirbaz.groovy"),
            // Salt-okur FM-hazırlık denetimi + sağlamlık kontrol listesi (batch/UTAP/doğrulama). FM ÇALIŞTIRMAZ. bkz. Ekler → Patolojide Temel Modeller.
            new ScriptEntry("Foundation model hazırlık ve sağlamlık sihirbazı", "yardimci-foundation-model-sihirbaz.groovy")
        )),
        new ScriptGroup("Klinik & kohort", List.of(
            // metadata-qupath (sbalci, MIT) köprüsü — proje geneli slayt/tarayıcı üst verisi → CSV + Proje sütunları.
            new ScriptEntry("Kohort metadata sihirbazı", "yardimci-metadata-sihirbaz.groovy", false, false),  // proje geneli, salt-okunur: açık slayt gerekmez
            // sectra-qupath (sbalci, MIT) köprüsü — Sectra PACS DICOM (GSPS) → GeoJSON. bkz. Ekler → Klinik PACS.
            new ScriptEntry("Sectra PACS anotasyon sihirbazı", "yardimci-sectra-iceaktar.groovy"),
            new ScriptEntry("WSI anonimleştirme sihirbazı",         "yardimci-anonim-sihirbaz.groovy"),
            // Proje geneli kilitli özet anotasyon ölçümlerini tek satır/görüntü geniş TSV'ye toplar; salt-okur, açık slayt gerekmez.
            new ScriptEntry("Kohort özet toplayıcı (proje tablosu)", "yardimci-kohort-ozet-topla.groovy", false, false),
            // Salt-okur koşu manifesti: kimlik + boya vektörleri + sınıf bazlı sayımlar → provenance JSON; açık slayt gerekmez.
            new ScriptEntry("Koşu manifesti (provenance JSON)", "yardimci-kosu-manifesti.groovy", false, false)
        )),
        new ScriptGroup("Eğitim, sunum & ImageJ", List.of(
            // Bankhead'in görüntü-işleme sözlüğünü kendi slaydında canlı önizlemelerle gezdiren tur (Modül 2'nin perde arkası).
            new ScriptEntry("Görüntü işleme kavramları", "yardimci-goruntu-isleme-turu.groovy"),
            // Ekran kaydı / canlı sunum — bastığınız tuş ve fare işlemlerini gösterir (InputDisplay aç/kapa).
            new ScriptEntry("Tuş/fare göstergesi (kayıt için)", "yardimci-tus-fare-gostergesi.groovy", false, false),  // global ekran katmanı: açık slayt gerekmez
            // Görüntüleyiciyi/pencereyi PNG/JPEG'e veya panoya alır (rapor & sunum). GuiTools.makeSnapshot.
            new ScriptEntry("Görüntü yakala (rapor/sunum)", "yardimci-goruntu-yakala.groovy", false, false),  // açık slayt şart değil — pencere/UI de yakalanır
            // I2K 2024 (Bankhead) uyarlamaları — ImageJ köprüsü (Otsu eşik / spline) + QuPath-içi grafik.
            new ScriptEntry("ImageJ ile otomatik eşik → anotasyon", "yardimci-imagej-otsu-anotasyon.groovy"),
            new ScriptEntry("ImageJ ile sınır yumuşat (spline)",    "yardimci-imagej-spline-duzeltme.groovy"),
            new ScriptEntry("Dağılım grafiği (scatter chart)",      "yardimci-dagilim-grafigi.groovy")
        ))
    );

    /**
     * Advanced-analysis helpers prepared for a LATER workshop session. They are
     * bundled in the JAR and kept byte-synced with handson/scripts (see
     * tools/check-script-sync.ps1), but rendered as DISABLED (greyed-out) menu
     * items below so participants can see what's coming without running them
     * yet. To activate one later, move its entry into the relevant {@link #UTILITY_GROUPS} group.
     */
    private static final List<ScriptEntry> UPCOMING_SCRIPTS = List.<ScriptEntry>of(
        // Atölye ilk oturumu tamamlandı — eski "İleri analiz — sonraki oturum" yardımcılarının
        // tümü yardımcı gruplarına (UTILITY_GROUPS) taşındı ve artık etkin. Yeni hazırlanıp
        // henüz etkinleştirilmemiş bir yardımcı çıkarsa buraya (gri/önizleme) eklenir.
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

            if (!UTILITY_GROUPS.isEmpty()) {
                Menu utilsMenu = new Menu("Yardımcılar");
                for (ScriptGroup group : UTILITY_GROUPS) {
                    Menu groupMenu = new Menu(group.title());
                    for (ScriptEntry entry : group.entries()) {
                        MenuItem item = new MenuItem(entry.label);
                        item.setOnAction(e -> runScriptSafely(qupath, entry));
                        item.disableProperty().bind(disableBinding(qupath, entry));
                        groupMenu.getItems().add(item);
                    }
                    utilsMenu.getItems().add(groupMenu);
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

            logger.info("Workshop extension installed with {} module + {} utility (in {} groups) + {} upcoming (disabled) scripts.",
                    SCRIPTS.size(), UTILITY_GROUPS.stream().mapToInt(g -> g.entries().size()).sum(), UTILITY_GROUPS.size(), UPCOMING_SCRIPTS.size());
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
            "  1 — Arayüz turu (interaktif UI gezintisi; açık slayt gerekmez)\n" +
            "  2 — Hücre tespiti\n" +
            "  3 — Nükleer boya (Ki-67)\n" +
            "  3b — ER / PR H-score\n" +
            "  4 — Membran boya (HER2)\n" +
            "  5 — Sitoplazmik boya (CD68)\n" +
            "  6 — Tümör/Stroma modeli oluştur (eğit) + Tümör vs stroma (uygula)\n" +
            "  7 — Tümör içi Ki-67\n" +
            "  8 — QuANTUM cTCF (StarDist + nesne sınıflandırıcı eğitimi)\n" +
            "  9 — Veri dışa aktarma (TSV / GeoJSON)\n\n" +
            "Yardımcılar — konuya göre gruplu alt-menüler (Yardımcılar → <konu> → script):\n" +
            "  • Temel araçlar · Boya & renk · Hücre/çekirdek tespiti\n" +
            "  • Skorlama & ölçüm · Uzamsal analiz · İçe/dışa aktarma & veri\n" +
            "  • Python köprüleri & temel modeller · Klinik & kohort · Eğitim, sunum & ImageJ\n\n" +
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
