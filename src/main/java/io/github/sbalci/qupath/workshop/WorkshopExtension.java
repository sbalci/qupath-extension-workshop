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
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * Main entry point for the Workshop extension.
 *
 * Discovered by QuPath via the {@code META-INF/services/qupath.lib.gui.extensions.QuPathExtension}
 * resource file (ServiceLoader mechanism).
 */
public class WorkshopExtension implements QuPathExtension {

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
        new ScriptEntry("Modül 3 - Nükleer boya (Ki-67)",           "modul-03-nukleer-boya.groovy"),
        new ScriptEntry("Modül 3b - ER / PR H-score",               "modul-03b-er-pr-hscore.groovy"),
        new ScriptEntry("Modül 4 - Membran boya (HER2)",            "modul-04-membran-boya.groovy"),
        new ScriptEntry("Modül 5 - Sitoplazmik boya (CD68)",        "modul-05-sitoplazmik-boya.groovy"),
        new ScriptEntry("Modül 6 - Tümör vs stroma sınıflandırıcı", "modul-06-tumor-stroma.groovy"),
        new ScriptEntry("Modül 7 - Tümör içi Ki-67",                "modul-07-tumor-ici-ki67.groovy"),
        // Modül 8 (QuANTUM cTCF) sonraki sürümlere ertelendi — StarDist + object classifier
        // ön-gereksinimleri ilk sürüm katılımcıları için fazla. Script JAR resource olarak
        // kalır (modul-08-quantum-ctcf.groovy) → ileride aşağıdaki satır yorum-dışı bırakılır.
        // new ScriptEntry("Modül 8 - QuANTUM cTCF",                "modul-08-quantum-ctcf.groovy"),
        new ScriptEntry("Modül 9 - Veri dışa aktarma",              "modul-09-veri-aktarma.groovy")
    );

    /**
     * Utility (non-module) scripts displayed below a separator, after the
     * numbered workflow modules. Add new helpers here without touching the
     * module list.
     */
    private static final List<ScriptEntry> UTILITY_SCRIPTS = List.of(
        new ScriptEntry("Yardımcı - Tespitleri sil",                "yardimci-tespitleri-sil.groovy"),
        new ScriptEntry("Yardımcı - Görüntü tipi ayarla",           "yardimci-image-type.groovy"),
        new ScriptEntry("Yardımcı - Eşikleri ayarla",               "yardimci-esik-ayarla.groovy")
    );

    /**
     * Advanced-analysis helpers prepared for a LATER workshop session. They are
     * bundled in the JAR and kept byte-synced with handson/scripts (see
     * tools/check-script-sync.ps1), but rendered as DISABLED (greyed-out) menu
     * items below so participants can see what's coming without running them
     * yet. To activate one later, move its entry into {@link #UTILITY_SCRIPTS}.
     */
    private static final List<ScriptEntry> UPCOMING_SCRIPTS = List.of(
        new ScriptEntry("Delaunay komşuluk özellikleri", "yardimci-delaunay-komsuluk.groovy"),
        new ScriptEntry("En yakın komşu mesafesi",       "yardimci-nn-mesafe.groovy"),
        new ScriptEntry("Yoğunluk haritası",             "yardimci-yogunluk-haritasi.groovy"),
        new ScriptEntry("Ki-67 heterojenlik grid",       "yardimci-ki67-heterojenlik.groovy"),
        new ScriptEntry("Stromal TIL yoğunluğu",         "yardimci-stromal-til.groovy")
    );

    private boolean alreadyInstalled = false;

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
                item.setOnAction(e -> runScriptSafely(qupath, entry));
                modulesMenu.getItems().add(item);
            }
            menu.getItems().add(modulesMenu);

            if (!UTILITY_SCRIPTS.isEmpty()) {
                Menu utilsMenu = new Menu("Yardımcılar");
                for (ScriptEntry entry : UTILITY_SCRIPTS) {
                    MenuItem item = new MenuItem(entry.label);
                    item.setOnAction(e -> runScriptSafely(qupath, entry));
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

            menu.getItems().add(new SeparatorMenuItem());
            var about = new MenuItem("Atölye hakkında…");
            about.setOnAction(e -> showAboutDialog());
            menu.getItems().add(about);

            logger.info("Workshop extension installed with {} module + {} utility + {} upcoming (disabled) scripts.",
                    SCRIPTS.size(), UTILITY_SCRIPTS.size(), UPCOMING_SCRIPTS.size());
        } catch (Exception ex) {
            logger.error("Failed to install Workshop extension menu", ex);
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
            "  6 — Tümör vs stroma sınıflandırıcı\n" +
            "  7 — Tümör içi Ki-67\n" +
            "  9 — Veri dışa aktarma (TSV / GeoJSON)\n\n" +
            "  (Modül 8 - QuANTUM cTCF: sonraki sürümlerde)\n\n" +
            "Yardımcılar:\n" +
            "  • Tespitleri sil (orphan / tümü)\n" +
            "  • Görüntü tipi ayarla (slayt / proje)\n" +
            "  • Eşikleri ayarla (yeniden tespit etmeden re-binning)\n\n" +
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
        return Version.parse("0.2.0-alpha5");
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse("0.6.0");
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private static final class ScriptEntry {
        final String label;
        final String resource;

        ScriptEntry(String label, String resource) {
            this.label = label;
            this.resource = resource;
        }
    }
}
