package io.github.sbalci.qupath.workshop;

import java.util.Locale;

/** Teknik istisnayı eyleme dönük Türkçe/İngilizce yönlendirmeye ve anonimleştirilmiş tanılama özetine dönüştürür. */
public final class WorkshopErrors {
    private WorkshopErrors() {}

    public static String guidance(Throwable error) {
        return guidance(error, false);
    }

    public static String guidance(Throwable error, boolean english) {
        if (error == null) {
            return english
                    ? "Unexpected empty error record. Open View → Show log to inspect details."
                    : "Beklenmeyen boş hata kaydı alındı. View → Show log penceresinden ayrıntıyı kontrol edin.";
        }
        Throwable cause = rootCause(error);
        String className = cause.getClass().getName().toLowerCase(Locale.ROOT);
        String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
        if (cause instanceof OutOfMemoryError)
            return english
                    ? "Out of memory. Try running on a smaller 1–2 mm² area annotation first, close unused whole slide images, and avoid processing the entire slide at once."
                    : "Bellek yetersiz. Önce 1–2 mm² büyüklüğünde küçük bir alan anotasyonunda deneyin; gereksiz açık sanal lamları kapatın ve tam kesiti tek seferde işlemeyin.";
        if (cause instanceof java.io.FileNotFoundException || cause instanceof java.nio.file.NoSuchFileException)
            return english
                    ? "Required file not found. Re-select the slide, model, or classifier path; if the project moved, run Project → Check image paths."
                    : "Gerekli dosya bulunamadı. Sanal lam, model veya sınıflandırıcı yolunu yeniden seçin; taşınmış proje için Project → Check image paths komutunu çalıştırın.";
        if (className.contains("format") || message.contains("unsupported") || message.contains("bio-formats")
                || message.contains("openslide") || message.contains("corrupt") || message.contains("image server"))
            return english
                    ? "Image format unreadable or pyramid corrupted. Confirm the download finished completely and the file is a WSI format supported by QuPath 0.6.0+ (.svs, .ndpi, .ome.tif, .ome.zarr)."
                    : "Görüntü biçimi okunamadı veya piramit yapısı bozuk. Dosyanın tam indirildiğini ve QuPath 0.6.0+ tarafından desteklenen bir TKG biçimi (.svs, .ndpi, .ome.tif, .ome.zarr) olduğunu doğrulayın.";
        if (message.contains("stain") || message.contains("deconvolution") || message.contains("h-dab") || message.contains("image type"))
            return english
                    ? "Stain vectors or image type missing/mismatched. In the left Image tab, set Image type to 'Brightfield (H-E)' for H&E or 'Brightfield (H-DAB)' for IHC."
                    : "Boyama vektörleri veya görüntü tipi eksik/uyumsuz. Sol paneldeki Image sekmesinden Image type değerini H&E için 'Brightfield (H-E)', İHK için 'Brightfield (H-DAB)' olarak seçin.";
        if (message.contains("empty roi") || message.contains("area roi") || message.contains("point roi")
                || message.contains("line roi") || message.contains("no annotation") || message.contains("seçili alan"))
            return english
                    ? "No valid closed area (ROI) selected. Draw a closed area on tissue using the rectangle/polygon/brush tool (not a point or line) and click inside until its border turns yellow."
                    : "Geçerli bir kapalı alan (ROI) seçili değil. Nokta veya çizgi yerine dikdörtgen/poligon/fırça aracıyla doku üzerinde kapalı alan çizin ve kenarı sarı olana kadar içine tıklayın.";
        if (message.contains("nan") || message.contains("infinity") || message.contains("pixel calibration") || message.contains("microns"))
            return english
                    ? "Invalid numeric measurement (NaN/Infinity) or physical scale (MPP) mismatch. Check pixel width/height (µm) in the Image tab and numeric values in Workshop Settings."
                    : "Geçersiz sayısal ölçüm (NaN/sonsuz) veya fiziksel ölçek (MPP) uyumsuzluğu bulundu. Image sekmesindeki piksel genişliği/yüksekliğini (µm) ve Atölye Ayarları'ndaki sayıları denetleyin.";
        if (message.contains("column") || message.contains("measurement"))
            return english
                    ? "Expected measurement column missing or unreadable. Generate cell detection and intensity measurements first; compare selected export column names with the Measure table."
                    : "Beklenen ölçüm sütunu bulunamadı veya okunamadı. Önce hücre tespitini ve yoğunluk ölçümlerini üretin; dışa aktarmada seçili sütun adlarını Measure tablosuyla karşılaştırın.";
        if (message.contains("python") || message.contains("cellpose") || message.contains("stardist")
                || message.contains("instanseg") || message.contains("djl"))
            return english
                    ? "Optional deep-learning / Python runtime not found. Check setup via Extensions → Atölye → Environment check…, or continue with the built-in Watershed fallback in core modules."
                    : "İsteğe bağlı derin öğrenme / Python ortamı bulunamadı. Extensions → Atölye → Ortam kontrolü penceresinden kurulumu denetleyin veya çekirdek modüllerde yerleşik Watershed yedeğiyle devam edin.";
        return english
                ? "Open a whole slide image first. If the tool expects a region, draw and select a closed area annotation (yellow border). Verify image type (H&E / H-DAB) and required extensions."
                : "Bir sanal lam açın. Araç bölge istiyorsa kapalı bir alan anotasyonu çizip seçin (kenarı sarı olmalı). Görüntü tipini (H&E / H-DAB) ve gereken eklenti kurulumunu denetleyin.";
    }

    public static Throwable rootCause(Throwable error) {
        if (error == null) return null;
        Throwable cause = error;
        for (int depth = 0; depth < 12 && cause.getCause() != null && cause.getCause() != cause; depth++)
            cause = cause.getCause();
        return cause;
    }

    /** Redacts local home directory paths and common WSI filenames so participants can safely paste logs. */
    public static String redactSensitiveText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String out = raw;
        String home = System.getProperty("user.home", "");
        if (home != null && home.length() > 3) {
            out = out.replace(home, "<HOME>");
            out = out.replace(home.replace('\\', '/'), "<HOME>");
        }
        out = out.replaceAll("(?i)[A-Z]:\\\\Users\\\\[^\\\\/\\s\"':]+", "<HOME>");
        out = out.replaceAll("(?i)/(?:Users|home)/[^/\\s\"':]+", "<HOME>");
        out = out.replaceAll("(?i)[\\w.\\- ()]+\\.(?:svs|ndpi|mrxs|scn|bif|vms|vmu|isyntax|dcm|ome\\.tiff?|ome\\.zarr)", "<SLIDE_FILE>");
        return out;
    }

    /** Builds a short, privacy-redacted diagnostic report suitable for copying to clipboard. */
    public static String formatCopyableReport(String scriptLabel, String resource, Throwable error) {
        Throwable cause = rootCause(error);
        StringBuilder sb = new StringBuilder();
        sb.append("QuPath Workshop Extension Diagnostic Report (Redacted)\n");
        sb.append("Extension : v").append(WorkshopResources.getExtensionVersion()).append("\n");
        sb.append("Script    : ").append(scriptLabel != null ? scriptLabel : "?").append(" (").append(resource != null ? resource : "?").append(")\n");
        sb.append("OS / Java : ").append(System.getProperty("os.name", "?")).append(" / ").append(System.getProperty("java.version", "?")).append("\n");
        if (cause != null) {
            sb.append("Root cause: ").append(cause.getClass().getName()).append(": ")
              .append(redactSensitiveText(String.valueOf(cause.getMessage()))).append("\n");
            StackTraceElement[] trace = cause.getStackTrace();
            int limit = Math.min(trace != null ? trace.length : 0, 6);
            for (int i = 0; i < limit; i++) {
                sb.append("  at ").append(redactSensitiveText(String.valueOf(trace[i]))).append("\n");
            }
        }
        return sb.toString();
    }
}
