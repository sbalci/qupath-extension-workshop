/*
 * Reads bundled binary resources (e.g. the example tumor/stroma pixel
 * classifier) from the extension JAR.
 *
 * Kept separate from WorkshopExtension so Groovy scripts can fetch resources
 * reflectively via Class.forName — the same pattern used for WorkshopPrefs — and
 * so the resource is read through THIS class's classloader (the extension
 * classloader), which can actually see the bundled files.
 */
package io.github.sbalci.qupath.workshop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public final class WorkshopResources {

    private WorkshopResources() {}

    /** Gzipped example classifier bundled in the JAR (decompresses to ~50 MB JSON). */
    private static final String TUMOR_STROMA_CLASSIFIER_GZ = "/classifiers/tumor-stroma-RF.json.gz";

    /**
     * Returns the bundled tumor/stroma pixel-classifier JSON (gunzipped), or
     * {@code null} if the resource is missing or unreadable. Decompresses on each
     * call — callers invoke it at most once per run, so we avoid pinning ~50 MB
     * in memory for the life of the JVM.
     */
    public static String getTumorStromaClassifierJson() {
        try (InputStream raw = WorkshopResources.class.getResourceAsStream(TUMOR_STROMA_CLASSIFIER_GZ)) {
            if (raw == null) {
                return null;
            }
            try (GZIPInputStream gz = new GZIPInputStream(raw)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = gz.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            return null;
        }
    }
}
