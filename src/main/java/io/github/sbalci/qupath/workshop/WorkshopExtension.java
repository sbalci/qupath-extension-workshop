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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import javafx.application.Platform;
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

    /**
     * The workshop scripts in display order. Each entry maps a human-readable
     * menu label (Turkish) to the script filename inside the JAR's
     * {@code /scripts/} resource directory (ASCII for cross-platform safety).
     */
    private static final List<ScriptEntry> SCRIPTS = List.of(
        new ScriptEntry("Modül 2 — Hücre tespiti",                  "modul-02-hucre-tespiti.groovy"),
        new ScriptEntry("Modül 3 — Nükleer boya (Ki-67)",           "modul-03-nukleer-boya.groovy"),
        new ScriptEntry("Modül 3b — ER / PR H-score",               "modul-03b-er-pr-hscore.groovy"),
        new ScriptEntry("Modül 4 — Membran boya (HER2)",            "modul-04-membran-boya.groovy"),
        new ScriptEntry("Modül 5 — Sitoplazmik boya (CD68)",        "modul-05-sitoplazmik-boya.groovy"),
        new ScriptEntry("Modül 6 — Tümör vs stroma sınıflandırıcı", "modul-06-tumor-stroma.groovy"),
        new ScriptEntry("Modül 7 — Tümör içi Ki-67",                "modul-07-tumor-ici-ki67.groovy"),
        new ScriptEntry("Modül 8 — QuANTUM cTCF",                   "modul-08-quantum-ctcf.groovy")
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

            for (ScriptEntry entry : SCRIPTS) {
                MenuItem item = new MenuItem(entry.label);
                item.setOnAction(e -> runScriptSafely(qupath, entry));
                menu.getItems().add(item);
            }

            menu.getItems().add(new SeparatorMenuItem());
            var about = new MenuItem("Atölye hakkında…");
            about.setOnAction(e -> showAboutDialog());
            menu.getItems().add(about);

            logger.info("Workshop extension installed with {} scripts.", SCRIPTS.size());
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
        String scriptBody = readScriptResource(entry.resource);
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
                Platform.runLater(() -> Dialogs.showErrorMessage(
                    "Script hatası — " + entry.label,
                    "Script çalıştırılırken bir hata oluştu:\n\n" +
                    t.getClass().getSimpleName() + ": " + t.getMessage() +
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
        } catch (IOException ex) {
            logger.error("Failed to read script resource: {}", path, ex);
            return null;
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
            "  8 — QuANTUM cTCF (NSCLC, NGS yeterlilik)\n\n" +
            "Versiyon: " + getVersion() + "\n" +
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
        return Version.parse("0.1.0");
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
