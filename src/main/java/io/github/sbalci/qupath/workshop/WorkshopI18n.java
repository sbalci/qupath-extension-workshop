package io.github.sbalci.qupath.workshop;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure-JDK bilingual (Turkish default / English) string bundle and language state
 * for the QuPath Workshop extension.
 *
 * <p>Intentionally free of JavaFX and {@code qupath-gui-fx} imports so headless
 * JUnit tests can exercise {@link WorkshopI18n}, {@link WorkshopMicrocopy},
 * {@link WorkshopErrors}, and {@link WorkshopParameterValidation} directly.
 */
public final class WorkshopI18n {

    private static final Logger logger = LoggerFactory.getLogger(WorkshopI18n.class);

    private static final String TR_RESOURCE = "/i18n/strings_tr.properties";
    private static final String EN_RESOURCE = "/i18n/strings_en.properties";
    private static final String PREF_NODE = "/qupath/atolye/i18n";
    private static final String PREF_KEY = "language";

    private static final Properties TR_PROPS = loadBundle(TR_RESOURCE);
    private static final Properties EN_PROPS = loadBundle(EN_RESOURCE);
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile String activeLanguage = loadInitialLanguage();

    private WorkshopI18n() {}

    private static String loadInitialLanguage() {
        try {
            String stored = Preferences.userRoot().node(PREF_NODE).get(PREF_KEY, "tr");
            return "en".equalsIgnoreCase(stored) ? "en" : "tr";
        } catch (Throwable t) {
            logger.debug("Preferences unavailable; defaulting language to tr", t);
            return "tr";
        }
    }

    private static Properties loadBundle(String resourcePath) {
        Properties props = new Properties();
        try (InputStream raw = WorkshopI18n.class.getResourceAsStream(resourcePath)) {
            if (raw != null) {
                try (InputStreamReader reader = new InputStreamReader(raw, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
            } else {
                logger.warn("Missing i18n bundle resource: {}", resourcePath);
            }
        } catch (IOException ex) {
            logger.warn("Failed to read i18n bundle resource: {}", resourcePath, ex);
        }
        return props;
    }

    /** Returns normalized active language code ({@code "tr"} or {@code "en"}). */
    public static String getLanguage() {
        return activeLanguage;
    }

    /** Sets active UI language ({@code "tr"} or {@code "en"}), persists it, and notifies UI listeners. */
    public static void setLanguage(String lang) {
        String normalized = "en".equalsIgnoreCase(lang) ? "en" : "tr";
        boolean changed = !normalized.equals(activeLanguage);
        activeLanguage = normalized;
        try {
            Preferences.userRoot().node(PREF_NODE).put(PREF_KEY, normalized);
        } catch (Throwable ignored) {
            // In-memory update still succeeds in restricted/sandboxed test environments
        }
        if (changed) {
            for (Runnable listener : LISTENERS) {
                try {
                    listener.run();
                } catch (Throwable ex) {
                    logger.debug("Language listener threw exception", ex);
                }
            }
        }
    }

    /** Registers a callback invoked whenever {@link #setLanguage(String)} changes the language. */
    public static void addListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    /** Toggles active UI language between {@code "tr"} and {@code "en"}. */
    public static String toggleLanguage() {
        String next = isEnglish() ? "tr" : "en";
        setLanguage(next);
        return next;
    }

    /** True when the active UI language is English ({@code "en"}). */
    public static boolean isEnglish() {
        return "en".equals(activeLanguage);
    }

    /** Looks up {@code key} in the active language bundle, falling back to Turkish and then {@code key}. */
    public static String t(String key) {
        return t(key, isEnglish());
    }

    /** Looks up {@code key} explicitly in English ({@code english == true}) or Turkish ({@code false}). */
    public static String t(String key, boolean english) {
        if (key == null) return "";
        Properties primary = english ? EN_PROPS : TR_PROPS;
        String val = primary.getProperty(key);
        if (val != null && !val.isEmpty()) {
            return val;
        }
        String fallback = TR_PROPS.getProperty(key);
        return (fallback != null && !fallback.isEmpty()) ? fallback : key;
    }

    /** Formats a localized pattern with {@link Locale#US} to keep numeric specifiers deterministic. */
    public static String tf(String key, Object... args) {
        return String.format(Locale.US, t(key), args);
    }

    /** Returns all keys defined in {@code strings_tr.properties} (for parity unit tests). */
    public static Set<String> trKeys() {
        Set<String> out = new LinkedHashSet<>();
        for (String k : TR_PROPS.stringPropertyNames()) out.add(k);
        return Collections.unmodifiableSet(out);
    }

    /** Returns all keys defined in {@code strings_en.properties} (for parity unit tests). */
    public static Set<String> enKeys() {
        Set<String> out = new LinkedHashSet<>();
        for (String k : EN_PROPS.stringPropertyNames()) out.add(k);
        return Collections.unmodifiableSet(out);
    }
}
