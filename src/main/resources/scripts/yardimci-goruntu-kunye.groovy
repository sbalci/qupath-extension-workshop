/**
 * Yardımcı - Görüntü künyesi ve histogram (salt-okur görüntü temel kontrolü)
 * --------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   "Analiz etmeden önce verine bak." Açık slaydın (veya seçili bir ROI'nin)
 *   temel görüntü künyesini tek pencerede gösterir — Pete Bankhead'in ücretsiz
 *   kitabı *Introduction to Bioimage Analysis* (CC-BY 4.0) Bölüm 1'in QuPath
 *   içindeki tek-tık karşılığı:
 *     1. KÜNYE  — görüntü tipi/piksel tipi (bit derinliği), kanal sayısı, RGB,
 *        piksel boyutu (µm/px), boyut, kestirilen büyütme, piramit seviyesi.
 *     2. HİSTOGRAM — örneklenen pikseller üzerinden kanal başına en düşük /
 *        en yüksek / ortalama değer ve metin-tabanlı (ASCII) histogram.
 *     3. DOYGUNLUK — kanal başına %@0 ve %@max (kırpılma/clipping göstergesi).
 *
 *   Tümüyle SALT-OKUR: görüntü piksellerini, projeyi, anotasyonları veya
 *   tespitleri DEĞİŞTİRMEZ. Yalnızca okur ve raporlar.
 *
 * NASIL ÖRNEKLER (ve sınırları):
 *   • Bir anotasyon/ROI seçiliyse yalnız o bölge; değilse tüm görüntü okunur.
 *   • Büyük slaytlarda bellek taşmasını önlemek için bölge, uzun kenarı ~1536
 *     piksele inecek bir altörnekleme (downsample) oranıyla okunur; bu yüzden
 *     histogram ve istatistikler YAKLAŞIK'tır (örneklem üzerinden).
 *   • Brightfield (H&E/İHK) görüntülerde beyaz arka plan doğal olarak %@max'ı
 *     yükseltir — bu çoğunlukla doygunluk değil, arka plandır. Floresanda
 *     yüksek %@max gerçek dedektör doygunluğuna işaret eder.
 *   • Float (ondalık) piksel tiplerinde örnek değerleri tam sayıya yuvarlanır;
 *     bu durumda istatistikler kabadır (uyarı raporda belirtilir).
 *   • Hiçbir klinik yorum, tanı veya skor üretmez.
 *
 * KULLANIM:
 *   1. Bir slayt açın (isterseniz bir bölge anotasyonu seçin).
 *   2. [Extensions → Atölye → Yardımcılar → Görüntü künyesi ve histogram]
 *   3. Künyeyi inceleyin; "Kopyala" ile panoya alın.
 *
 * İLGİLİ: Ek — Görüntü Analizi Temelleri (kavram ⇄ atölye ⇄ kitap eşlemesi);
 *   Kalibrasyon (piksel boyutu) ve Kohort metadata sihirbazı yardımcıları.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.common.GeneralTools
import qupath.lib.regions.RegionRequest

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Biçim yardımcıları (Locale.US — Türkçe yerelde virgül-ondalık olmasın) ──
def fmt = { double d, int dec -> Double.isNaN(d) ? '—' : String.format(java.util.Locale.US, '%.' + dec + 'f', d) }
def fmtInt = { long v -> String.format(java.util.Locale.US, '%,d', v) }

// ── Kanal etiketi (RGB için renk adı; çok-kanallıda metadata adı ya da Kanal N) ─
def channelLabel = { int idx, boolean rgb, int nb, server ->
    if (rgb && nb == 3) return (['Kırmızı (R)', 'Yeşil (G)', 'Mavi (B)'] as List)[idx]
    try {
        def chs = server.getMetadata().getChannels()
        if (chs != null && idx < chs.size()) {
            def nm = chs[idx].getName()
            if (nm != null && !nm.isEmpty()) return nm
        }
    } catch (Throwable ignore) {}
    return 'Kanal ' + (idx + 1)
}

// ── Tek bölgeyi oku → kanal başına istatistik + 256-kovalı histogram ─────────
// Dönen: [ok: bool, note: String, bands: [ [label, min, max, mean, pct0, pctMax, maxVal, hist(256)] ] ]
def computeStats = { server, RegionRequest request, boolean rgb ->
    def result = [ok: false, note: '', bands: []]
    def img = null
    try { img = server.readRegion(request) } catch (Throwable t) { result.note = 'Piksel okunamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName()); return result }
    if (img == null) { result.note = 'Bölge boş döndü (okunamadı).'; return result }
    try {
        def raster = img.getRaster()
        int nb = raster.getNumBands()
        int iw = img.getWidth(), ih = img.getHeight()
        def sm = raster.getSampleModel()
        boolean isFloat = false
        try { isFloat = server.getPixelType()?.toString()?.contains('FLOAT') } catch (Throwable ignore) {}
        for (int b = 0; b < nb; b++) {
            int bits = 8
            try { bits = sm.getSampleSize(b) } catch (Throwable ignore) {}
            long maxVal = (bits >= 31) ? (long) Integer.MAX_VALUE : ((1L << bits) - 1L)
            int shift = (bits > 8) ? (bits - 8) : 0
            long[] hist = new long[256]
            long n = 0L, c0 = 0L, cmax = 0L
            double sum = 0.0d
            long mn = Long.MAX_VALUE, mx = Long.MIN_VALUE
            for (int y = 0; y < ih; y++) {
                for (int x = 0; x < iw; x++) {
                    long s = (long) raster.getSample(x, y, b)
                    n++
                    sum += s
                    if (s < mn) mn = s
                    if (s > mx) mx = s
                    if (s == 0L) c0++
                    if (s == maxVal) cmax++
                    int idx = (int) (s >>> shift)
                    if (idx < 0) idx = 0
                    if (idx > 255) idx = 255
                    hist[idx]++
                }
            }
            if (n == 0L) continue
            result.bands << [
                label : channelLabel(b, rgb, nb, server),
                min   : (mn == Long.MAX_VALUE ? 0L : mn),
                max   : (mx == Long.MIN_VALUE ? 0L : mx),
                mean  : sum / (double) n,
                pct0  : 100.0d * c0 / (double) n,
                pctMax: 100.0d * cmax / (double) n,
                maxVal: maxVal,
                hist  : hist
            ]
        }
        result.ok = !result.bands.isEmpty()
        if (isFloat) result.note = 'Float piksel tipi — örnek değerleri tam sayıya yuvarlandı; istatistikler kabadır.'
    } catch (Throwable t) {
        result.note = 'İstatistik hesaplanamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName())
    }
    return result
}

// ── ASCII histogram (256 kova → 16 satır, ölçekli çubuk) ─────────────────────
def asciiHistogram = { long[] hist ->
    int DISP = 16, BAR = 32
    long[] disp = new long[DISP]
    for (int i = 0; i < 256; i++) disp[(int) (i * DISP / 256)] += hist[i]
    long dmax = 0L
    for (long v : disp) if (v > dmax) dmax = v
    def sb = new StringBuilder()
    for (int i = 0; i < DISP; i++) {
        int lo = (int) Math.round(i * 256.0d / DISP)
        int hi = (int) Math.round((i + 1) * 256.0d / DISP) - 1
        int blen = (dmax > 0L) ? (int) Math.round(disp[i] * (double) BAR / dmax) : 0
        sb << String.format(java.util.Locale.US, '  %3d-%3d | %s%n', lo, hi, ('█' * blen))
    }
    return sb.toString()
}

// ── Raporu kur ───────────────────────────────────────────────────────────────
def buildReport = { imageData ->
    def server = imageData.getServer()
    def sb = new StringBuilder()
    sb << 'GÖRÜNTÜ KÜNYESİ VE HİSTOGRAM\n'
    sb << '════════════════════════════\n'

    // Künye
    int W = server.getWidth(), H = server.getHeight()
    boolean rgb = false
    try { rgb = server.isRGB() } catch (Throwable ignore) {}
    int nCh = 1
    try { nCh = server.nChannels() } catch (Throwable ignore) {}
    String pixelType = 'Unknown'
    try { pixelType = server.getPixelType()?.toString() ?: 'Unknown' } catch (Throwable ignore) {}
    double pw = Double.NaN, ph = Double.NaN
    String unit = 'pixel'
    try {
        def cal = server.getPixelCalibration()
        if (cal != null) {
            pw = cal.getPixelWidthMicrons(); ph = cal.getPixelHeightMicrons()
            unit = cal.getPixelWidthUnit()?.toString() ?: 'pixel'
        }
    } catch (Throwable ignore) {}
    int nRes = 1
    try { nRes = server.nResolutions() } catch (Throwable ignore) {}
    String imgName = 'slide'
    try { imgName = server.getMetadata().getName() ?: 'slide' } catch (Throwable ignore) {}
    String imgType = null
    try { imgType = imageData.getImageType()?.toString() } catch (Throwable ignore) {}

    def line = { String label, Object val -> sb << String.format(java.util.Locale.US, '  %-20s : %s%n', label, (val == null ? '—' : val.toString())) }
    line('Görüntü', imgName)
    if (imgType != null) line('Görüntü tipi', imgType)
    line('Piksel tipi', pixelType + (rgb ? ' (RGB)' : ''))
    line('Kanal sayısı', nCh)
    line('Boyut (px)', fmtInt((long) W) + ' × ' + fmtInt((long) H))
    if (!Double.isNaN(pw) && pw > 0) {
        line('Piksel (µm/px)', fmt(pw, 4) + ' × ' + fmt(ph, 4) + ' ' + unit)
        line('Kestirilen büyütme', '~' + Math.round(0.25d / pw * 40) + '×  (sezgisel)')
    } else {
        line('Piksel (µm/px)', '— (kalibre değil)')
    }
    line('Piramit seviyesi', nRes)

    // Örnekleme bölgesi
    def sel = QP.getSelectedObject()
    def roi = (sel != null && sel.hasROI()) ? sel.getROI() : null
    int rx, ry, rw, rh
    String regionDesc
    if (roi != null) {
        rx = (int) Math.floor(roi.getBoundsX()); ry = (int) Math.floor(roi.getBoundsY())
        rw = (int) Math.ceil(roi.getBoundsWidth()); rh = (int) Math.ceil(roi.getBoundsHeight())
        if (rx < 0) rx = 0
        if (ry < 0) ry = 0
        regionDesc = 'Seçili ROI'
    } else {
        rx = 0; ry = 0; rw = W; rh = H
        regionDesc = 'Tüm görüntü'
    }
    if (rw > W - rx) rw = W - rx
    if (rh > H - ry) rh = H - ry
    if (rw < 1 || rh < 1) { sb << '\n  ⚠ Geçerli bir bölge yok (boyut 0).\n'; return sb.toString() }

    int TARGET = 1536
    double downsample = Math.max(1.0d, Math.max(rw, rh) / (double) TARGET)
    def request = RegionRequest.createInstance(server.getPath(), downsample, rx, ry, rw, rh)

    sb << '\n'
    line('Örneklenen bölge', regionDesc)
    line('Altörnekleme', '1/' + fmt(downsample, 1) + '  (yaklaşık)')

    def stats = computeStats(server, request, rgb)
    if (!stats.ok) {
        sb << '\n  ⚠ ' + (stats.note ?: 'Piksel istatistiği üretilemedi.') + '\n'
        sb << '\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'
        return sb.toString()
    }

    // Kanal istatistik tablosu
    sb << '\nKANAL İSTATİSTİKLERİ (örnekten)\n'
    sb << '───────────────────────────────\n'
    sb << String.format(java.util.Locale.US, '  %-14s %8s %8s %9s %7s %7s%n', 'Kanal', 'En düşük', 'En yüksek', 'Ortalama', '%@0', '%@max')
    stats.bands.each { bd ->
        sb << String.format(java.util.Locale.US, '  %-14s %8d %8d %9s %7s %7s%n',
            bd.label, (long) bd.min, (long) bd.max, fmt((double) bd.mean, 1), fmt((double) bd.pct0, 1), fmt((double) bd.pctMax, 1))
    }

    // Histogramlar
    sb << '\nHİSTOGRAM (0–255 ölçekli, örnekten)\n'
    sb << '───────────────────────────────────\n'
    stats.bands.each { bd ->
        sb << bd.label + '\n'
        sb << asciiHistogram((long[]) bd.hist)
        sb << '\n'
    }

    // Doygunluk / kırpılma notu (dürüst çerçeve)
    sb << 'Doygunluk / kırpılma (clipping) notu\n'
    sb << '────────────────────────────────────\n'
    if (rgb) {
        sb << '  Brightfield/RGB: yüksek %@max çoğunlukla beyaz arka plandır\n'
        sb << '  (doygunluk değil). Boyalı/koyu bölgelerde %@max düşük olmalı.\n'
    } else {
        double worst = 0.0d
        stats.bands.each { bd -> if ((double) bd.pctMax > worst) worst = (double) bd.pctMax }
        if (worst >= 1.0d) sb << '  Floresan: bir/birkaç kanalda %@max yüksek → dedektör doygunluğu\n  olabilir; pozlama/kazanç ayarını gözden geçirin.\n'
        else sb << '  Floresan: %@max düşük → belirgin doygunluk işareti yok.\n'
    }
    if (stats.note != null && !stats.note.isEmpty()) sb << '  Not: ' + stats.note + '\n'

    sb << '\n📖 Kavramlar: bioimagebook "Measurements & histograms" + "Types & bit-depths"\n'
    sb << '   (atölye: Ek — Görüntü Analizi Temelleri)\n'
    sb << '\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'
    return sb.toString()
}

// ── Sonuç penceresi (salt-okur; headless'ta konsola yazar) ───────────────────
def showResultWindow = { String text ->
    if (isHeadless) {
        println text
        return
    }
    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle('Görüntü künyesi ve histogram')
            def alwaysTop = new javafx.scene.control.CheckBox('Üstte tut')
            alwaysTop.setSelected(true)
            stage.setAlwaysOnTop(true)
            alwaysTop.selectedProperty().addListener({ obs, o, n ->
                stage.setAlwaysOnTop(n)
            } as javafx.beans.value.ChangeListener)

            def ta = new javafx.scene.control.TextArea(text ?: '')
            ta.setEditable(false); ta.setWrapText(false)
            ta.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;")
            javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS)

            def copyBtn = new javafx.scene.control.Button('Kopyala')
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent()
                content.putString(text ?: '')
                cb.setContent(content)
            })
            def closeBtn = new javafx.scene.control.Button('Kapat')
            closeBtn.setOnAction({ stage.close() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def bar = new javafx.scene.layout.HBox(8, alwaysTop, spacer, copyBtn, closeBtn)
            bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            bar.setPadding(new javafx.geometry.Insets(10))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(ta)
            root.setBottom(bar)
            stage.setScene(new javafx.scene.Scene(root, 760, 680))
            stage.show()
        } catch (Throwable t) {
            Dialogs.showMessageDialog('Görüntü künyesi ve histogram', text ?: '')
        }
    }
}

// ── Çalıştır ─────────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    def msg = 'Önce bir slayt açın. (İsterseniz tek bir bölge anotasyonu seçin; yoksa tüm görüntü örneklenir.)'
    if (isHeadless) println msg
    else Dialogs.showMessageDialog('Görüntü künyesi ve histogram', msg)
    return
}
showResultWindow(buildReport(imageData))
println '✓ Görüntü künyesi ve histogram hazırlandı.'
