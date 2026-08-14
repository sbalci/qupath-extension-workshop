/**
 * Yardımcı - Doku Tespiti Sihirbazı (native, tek pencere · canlı önizleme)
 * -----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Python veya ImageJ gerektirmeden, tamamen QuPath içinde (native) bir slaytın
 *   doku bölgesini düşük çözünürlüklü piksele eşikleyerek tespit eder ve kilitli
 *   "Doku" sınıflı bir anotasyon üretir. TEK bir pencerede çalışır; parametreleri
 *   KAYDIRICILARLA (slider) ayarlarken maskeyi CANLI önizlersiniz.
 *   Yöntem:
 *     1. Eşik tabanı (OD toplamı / Hematoksilen / Arka plan parlaklığı) ve downsample
 *        ile düşük çözünürlüklü bölge okunur; her piksel için skaler hesaplanır.
 *     2. Otsu otomatik eşiği hesaplanır; kaydırıcıyla eşik elle de ayarlanabilir.
 *     3. Önizlemede ön plan (doku) pikselleri turkuaz ile boyanır — canlı güncellenir.
 *     4. "Uygula" ile ikili maske kontür izlemesiyle (ContourTracing) ROI'ye çevrilir,
 *        minimum alandan küçük parçalar atılır ve kilitli "Doku" anotasyonu eklenir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Tespit edilen doku alanını (mm²) ve tüm görüntüye oranını (%) ölçer.
 *   • Klinik yorum, eşik, grade veya patoloji tanısı üretmez.
 *
 * KULLANIM:
 *   1. Kalibre (µm/px) bir slayt açın.
 *   2. [Extensions → Atölye → Yardımcılar → Doku Tespiti (native)]
 *   3. Eşik tabanını seçin; downsample / eşik / minimum alan kaydırıcılarını oynatın;
 *      önizleme yeterli görününce "Uygula".
 *
 * ÇIKTI:
 *   • Kilitli, "Doku" sınıflı kütlesel anotasyon (idempotent — yeniden uygulamada temizlenir).
 *   • Doku alanı (mm²) ve doku fraksiyonu (%) — pencere içi özet.
 *
 * YÖNTEM / KAYNAK:
 *   • Renk ayırma: imageData.getColorDeconvolutionStains() — QuPath 0.6.0+.
 *   • ContourTracing.createTracedROI(Raster, min, max, band, RegionRequest) — QuPath 0.6.0+.
 *   • Otsu eşikleme: Otsu N (1979), IEEE Trans SMC — histogram-tabanlı varyans minimizasyonu.
 *   • Bankhead P et al. (2017), Sci Rep. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.regions.RegionRequest
import qupath.lib.analysis.images.ContourTracing

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── Sabitler ────────────────────────────────────────────────────────
String SENTINEL_NAME = "Doku"
String SENTINEL_CLASS = "Doku"
int BINS = 512
List<String> CHOICES = ["OD toplamı (OD sum)", "Hematoksilen", "Arka plan (parlaklık)"]
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/doku-tespiti-sihirbaz')

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    if (isHeadless) { println "Görüntü açık değil."; return }
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce analiz edilecek slaydı açın.")
    return
}
def server = imageData.getServer()
def cal = server.getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
boolean calibrated = (pw > 0 && ph > 0 && !Double.isNaN(pw) && !Double.isNaN(ph))
def stains = imageData.getColorDeconvolutionStains()

// ── Ortak hesaplama kapanışları (headless + GUI paylaşır) ───────────
def odOf = { int v -> -Math.log((v + 1.0d) / 256.0d) / Math.log(10.0d) }

// Skaler harita: seçilen eşik tabanına göre float[W*H]
def computeScalar = { java.awt.image.Raster raster, int W, int H, String choice ->
    float[] scalar = new float[W * H]
    if (choice == CHOICES[1] && stains != null) {
        def s1 = stains.getStain(1)
        double vr = s1.getRed(), vg = s1.getGreen(), vb = s1.getBlue()
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double hem = odOf(raster.getSample(x, y, 0) & 0xFF) * vr +
                         odOf(raster.getSample(x, y, 1) & 0xFF) * vg +
                         odOf(raster.getSample(x, y, 2) & 0xFF) * vb
            scalar[y * W + x] = (float) Math.max(0.0d, hem)
        }
    } else if (choice == CHOICES[2]) {
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int r = raster.getSample(x, y, 0) & 0xFF
            int g = raster.getSample(x, y, 1) & 0xFF
            int b = raster.getSample(x, y, 2) & 0xFF
            scalar[y * W + x] = (float) (255.0d - (r + g + b) / 3.0d)
        }
    } else {
        // OD toplamı (varsayılan; Hematoksilen'de stain yoksa da buraya düşer)
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            scalar[y * W + x] = (float) (odOf(raster.getSample(x, y, 0) & 0xFF) +
                                         odOf(raster.getSample(x, y, 1) & 0xFF) +
                                         odOf(raster.getSample(x, y, 2) & 0xFF))
        }
    }
    return scalar
}

// Otsu eşiği + sinyal aralığı
def otsuOf = { float[] scalar ->
    float minVal = scalar[0], maxVal = scalar[0]
    for (float v : scalar) { if (v < minVal) minVal = v; if (v > maxVal) maxVal = v }
    float range = maxVal - minVal
    if (range < 1e-6f) return [minVal: (double) minVal, maxVal: (double) maxVal, otsu: (double) minVal, flat: true]
    long[] hist = new long[BINS]
    for (float v : scalar) {
        int bin = (int) ((v - minVal) / range * (BINS - 1))
        if (bin < 0) bin = 0; else if (bin >= BINS) bin = BINS - 1
        hist[bin]++
    }
    long tot = (long) scalar.length
    double sum = 0.0d; for (int i = 0; i < BINS; i++) sum += i * hist[i]
    double sumB = 0.0d, maxVar = 0.0d; long wB = 0L; int otsuBin = 0
    for (int t = 0; t < BINS; t++) {
        wB += hist[t]; if (wB == 0L) continue
        long wF = tot - wB; if (wF == 0L) break
        sumB += t * hist[t]
        double mB = sumB / wB, mF = (sum - sumB) / wF
        double varr = wB * wF * (mB - mF) * (mB - mF)
        if (varr > maxVar) { maxVar = varr; otsuBin = t }
    }
    double thr = minVal + (otsuBin.toDouble() / (BINS - 1)) * range
    return [minVal: (double) minVal, maxVal: (double) maxVal, otsu: thr, flat: false]
}

// Downsample'da bölgeyi oku + skaler + otsu → cache map (ya da [error])
def buildCache = { double ds, String choice ->
    def request = RegionRequest.createInstance(server.getPath(), ds, 0, 0, server.getWidth(), server.getHeight())
    java.awt.image.BufferedImage img = server.readRegion(request)
    if (img == null) return [error: "Bölge okunamadı (boş görüntü). Downsample çok küçük olabilir."]
    def raster = img.getRaster()
    int W = raster.getWidth(), H = raster.getHeight()
    if (raster.getNumBands() < 3)
        return [error: "Bu araç RGB (parlak alan) görüntü bekler; bant sayısı: ${raster.getNumBands()}."]
    float[] scalar = computeScalar(raster, W, H, choice)
    def o = otsuOf(scalar)
    int[] baseArgb = new int[W * H]
    img.getRGB(0, 0, W, H, baseArgb, 0, W)
    return [request: request, W: W, H: H, scalar: scalar, baseArgb: baseArgb,
            minVal: o.minVal, maxVal: o.maxVal, otsu: o.otsu, flat: o.flat]
}

// Eşik + minAlan → birleşik ROI ([roi, pieces]) ya da null
def detect = { float[] scalar, int W, int H, double threshold, def request, double minAreaPx2 ->
    def maskImg = new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
    def maskRaster = maskImg.getRaster()
    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
        maskRaster.setSample(x, y, 0, scalar[y * W + x] >= threshold ? 255 : 0)
    def tracedRoi = ContourTracing.createTracedROI(maskRaster, 1.0d, 255.0d, 0, request)
    if (tracedRoi == null || tracedRoi.isEmpty()) return null
    def pieces
    try { pieces = qupath.lib.roi.RoiTools.splitROI(tracedRoi) } catch (Throwable t) { pieces = [tracedRoi] }
    def kept = pieces.findAll { it != null && !it.isEmpty() && it.getArea() >= minAreaPx2 }
    if (kept.isEmpty()) return null
    def finalRoi
    if (kept.size() == 1) finalRoi = kept[0]
    else {
        def union = kept[0].getGeometry()
        for (int i = 1; i < kept.size(); i++) union = union.union(kept[i].getGeometry())
        finalRoi = qupath.lib.roi.GeometryTools.geometryToROI(union, qupath.lib.regions.ImagePlane.getDefaultPlane())
    }
    return [roi: finalRoi, pieces: kept.size()]
}

// Kilitli 'Doku' anotasyonunu (idempotent) ekle
def applyAnno = { def finalRoi ->
    QP.removeObjects(QP.getAnnotationObjects().findAll {
        it.getPathClass()?.toString() == SENTINEL_CLASS && it.getName() == SENTINEL_NAME
    }, false)
    def a = PathObjects.createAnnotationObject(finalRoi, QP.getPathClass(SENTINEL_CLASS))
    a.setName(SENTINEL_NAME); a.setLocked(true)
    QP.addObjects([a]); QP.fireHierarchyUpdate()
}

// Özet metni
def summaryOf = { String choice, double ds, double thr, boolean useOtsu, double minAreaMm2, int pieces, def finalRoi ->
    double tissuePx2 = finalRoi.getArea()
    double slidePx2  = (double) server.getWidth() * server.getHeight()
    double frac = (slidePx2 > 0) ? 100.0d * tissuePx2 / slidePx2 : 0.0d
    def b = new StringBuilder()
    b << "DOKU TESPİTİ (native)\n"
    b << "Eşik tabanı  : ${choice}\n"
    b << String.format(java.util.Locale.US, "Downsample   : %.0f%n", ds)
    b << String.format(java.util.Locale.US, "Eşik         : %.4f  (%s)%n", thr, useOtsu ? "Otsu" : "elle")
    b << String.format(java.util.Locale.US, "Minimum alan : %.2f mm²%n", minAreaMm2)
    b << String.format(java.util.Locale.US, "Parça sayısı : %d → birleştirildi%n", pieces)
    if (calibrated)
        b << String.format(java.util.Locale.US, "Doku alanı   : %.2f mm²%n", tissuePx2 * pw * ph / 1_000_000.0d)
    else
        b << String.format(java.util.Locale.US, "Doku alanı   : %,.0f px²  (kalibre değil)%n", tissuePx2)
    b << String.format(java.util.Locale.US, "Doku fraksiyonu : %.1f %%%n", frac)
    b << "\nKilitli 'Doku' anotasyonu eklendi. ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm."
    return b.toString()
}

// ── Kayıtlı varsayılanlar ───────────────────────────────────────────
String defChoice = prefs.get('choice', CHOICES[0]); if (!CHOICES.contains(defChoice)) defChoice = CHOICES[0]
double defDs = 32.0d;  try { defDs = Double.parseDouble(prefs.get('downsample', '32').replace(',', '.')) } catch (Throwable ig) {}
if (defDs < 16.0d) defDs = 16.0d; if (defDs > 64.0d) defDs = 64.0d
double defMin = 1.0d;  try { defMin = Double.parseDouble(prefs.get('minAreaMm2', '1.0').replace(',', '.')) } catch (Throwable ig) {}
if (defMin < 0.0d) defMin = 0.0d; if (defMin > 5.0d) defMin = 5.0d
boolean defOtsu = Boolean.parseBoolean(prefs.get('useOtsu', 'true'))

// ── Headless: tek-atış (Otsu eşiği + kayıtlı parametreler) ───────────
if (isHeadless) {
    def c = buildCache(defDs, defChoice)
    if (c.error != null) { println "HATA: ${c.error}"; return }
    if (c.flat) { println "Tüm pikseller aynı değerde — eşikleme yapılamadı."; return }
    double minAreaPx2 = (calibrated && defMin > 0) ? (defMin * 1_000_000.0d / (pw * ph)) : 0.0d
    def d = detect((float[]) c.scalar, (int) c.W, (int) c.H, (double) c.otsu, c.request, minAreaPx2)
    if (d == null) { println "Doku bulunamadı (eşik/downsample/min alan değerlerini değiştirin)."; return }
    applyAnno(d.roi)
    println summaryOf(defChoice, defDs, (double) c.otsu, true, defMin, (int) d.pieces, d.roi)
    return
}

// ── GUI: tek pencere · canlı önizleme ───────────────────────────────
javafx.application.Platform.runLater {
    try {
        def cache   = new java.util.concurrent.atomic.AtomicReference(null)   // buildCache sonucu
        def busy    = new java.util.concurrent.atomic.AtomicBoolean(false)
        def token   = new java.util.concurrent.atomic.AtomicInteger(0)        // eskimiş rebuild sonuçlarını at
        def updatePreview            // ileri bildirim
        def rebuild                  // ileri bildirim

        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle("Doku Tespiti — canlı önizleme")
        stage.setAlwaysOnTop(true)

        // Kontroller
        def choiceBox = new javafx.scene.control.ComboBox(javafx.collections.FXCollections.observableArrayList(CHOICES))
        choiceBox.getSelectionModel().select(defChoice)
        choiceBox.setMaxWidth(Double.MAX_VALUE)

        def dsSlider = new javafx.scene.control.Slider(16, 64, defDs)
        dsSlider.setMajorTickUnit(16); dsSlider.setMinorTickCount(3)
        dsSlider.setShowTickMarks(true)
        def dsVal = new javafx.scene.control.Label()

        def otsuChk = new javafx.scene.control.CheckBox("Otsu (otomatik eşik)")
        otsuChk.setSelected(defOtsu)

        def thrSlider = new javafx.scene.control.Slider(0, 1, 0.5)
        thrSlider.setDisable(defOtsu)
        def thrVal = new javafx.scene.control.Label()

        def minSlider = new javafx.scene.control.Slider(0, 5, defMin)
        minSlider.setMajorTickUnit(1); minSlider.setShowTickMarks(true)
        def minVal = new javafx.scene.control.Label()

        def statLabel = new javafx.scene.control.Label("Yükleniyor…")
        statLabel.setWrapText(true); statLabel.setStyle("-fx-font-size: 12px;")

        // Önizleme
        def imageView = new javafx.scene.image.ImageView()
        imageView.setPreserveRatio(true); imageView.setFitWidth(520); imageView.setSmooth(true)
        def previewHolder = new javafx.scene.layout.StackPane(imageView)
        previewHolder.setMinSize(520, 380); previewHolder.setPrefSize(520, 460)
        previewHolder.setStyle("-fx-background-color: -fx-box-border, derive(-fx-base, -6%); -fx-background-insets: 0, 1; -fx-padding: 4;")

        def summaryArea = new javafx.scene.control.TextArea()
        summaryArea.setEditable(false); summaryArea.setWrapText(false)
        summaryArea.setPrefRowCount(8)
        summaryArea.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;")
        summaryArea.setPromptText("Parametreleri ayarlayın; 'Uygula' ile 'Doku' anotasyonunu ekleyin.")

        def applyBtn = new javafx.scene.control.Button("Uygula (Doku anotasyonu)")
        applyBtn.setDefaultButton(true); applyBtn.setDisable(true)
        def copyBtn = new javafx.scene.control.Button("Özeti kopyala")
        copyBtn.setDisable(true)
        def closeBtn = new javafx.scene.control.Button("Kapat")
        def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut")
        alwaysTop.setSelected(true)
        alwaysTop.selectedProperty().addListener({ obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)

        // Geçerli eşik değeri (Otsu ya da kaydırıcı)
        def currentThreshold = { ->
            def c = cache.get()
            if (c == null) return 0.0d
            return otsuChk.isSelected() ? (double) c.otsu : thrSlider.getValue()
        }

        // Önizlemeyi çiz: ön plan (>=eşik) turkuazla harmanla; istatistiği güncelle
        updatePreview = { ->
            def c = cache.get()
            if (c == null) return
            int W = (int) c.W, H = (int) c.H
            float[] scalar = (float[]) c.scalar
            int[] base = (int[]) c.baseArgb
            double thr = currentThreshold()
            int[] outp = new int[W * H]
            long fg = 0L
            int tr = 0, tg = 153, tb = 153   // turkuaz tint
            for (int i = 0; i < scalar.length; i++) {
                int p = base[i]
                if (scalar[i] >= thr) {
                    int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF
                    int nr = (int) (r * 0.55d + tr * 0.45d)
                    int ng = (int) (g * 0.55d + tg * 0.45d)
                    int nb = (int) (b * 0.55d + tb * 0.45d)
                    outp[i] = (0xFF << 24) | (nr << 16) | (ng << 8) | nb
                    fg++
                } else {
                    outp[i] = 0xFF000000 | (p & 0x00FFFFFF)
                }
            }
            def wimg = new javafx.scene.image.WritableImage(W, H)
            wimg.getPixelWriter().setPixels(0, 0, W, H,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), outp, 0, W)
            imageView.setImage(wimg)
            double pct = 100.0d * fg / (double) scalar.length
            thrVal.setText(String.format(java.util.Locale.US, "%.4f", thr))
            statLabel.setText(String.format(java.util.Locale.US,
                "Ön plan (doku): %.1f%%   ·   eşik %.4f   ·   Otsu %.4f   ·   önizleme %d×%d px",
                pct, thr, (double) c.otsu, W, H))
        }

        // Bölgeyi (yeniden) oku / skaleri güncelle; ağır iş arka planda
        rebuild = { boolean reload ->
            if (busy.get()) return
            busy.set(true)
            applyBtn.setDisable(true); choiceBox.setDisable(true); dsSlider.setDisable(true)
            int myTok = token.incrementAndGet()
            String choice = (String) choiceBox.getValue()
            double ds = (double) Math.round(dsSlider.getValue())
            statLabel.setText("Hesaplanıyor…")
            new Thread({
                def result
                try { result = buildCache(ds, choice) }
                catch (Throwable t) { result = [error: (t.getMessage() ?: t.getClass().getSimpleName())] }
                javafx.application.Platform.runLater {
                    try {
                        if (myTok != token.get()) return   // daha yeni bir rebuild var → bunu at
                        if (result.error != null) {
                            statLabel.setText("Hata: ${result.error}")
                            applyBtn.setDisable(true); cache.set(null); imageView.setImage(null)
                            return
                        }
                        if (result.flat) {
                            statLabel.setText("Tüm pikseller aynı değerde — eşikleme yapılamadı.")
                            applyBtn.setDisable(true); cache.set(null); imageView.setImage(null)
                            return
                        }
                        cache.set(result)
                        double mn = (double) result.minVal, mx = (double) result.maxVal
                        // eşik kaydırıcı aralığını sinyal aralığına ayarla
                        double cur = thrSlider.getValue()
                        thrSlider.setMin(mn); thrSlider.setMax(mx)
                        thrSlider.setBlockIncrement((mx - mn) / 100.0d)
                        thrSlider.setMajorTickUnit(Math.max(1e-6d, (mx - mn) / 4.0d))
                        if (otsuChk.isSelected() || cur < mn || cur > mx) thrSlider.setValue((double) result.otsu)
                        applyBtn.setDisable(false)
                        updatePreview()
                    } finally { busy.set(false); choiceBox.setDisable(false); dsSlider.setDisable(false) }
                }
            } as Runnable).start()
        }

        // Etiket güncelleyiciler
        dsVal.setText(String.format(java.util.Locale.US, "%.0f", defDs))
        minVal.setText(String.format(java.util.Locale.US, "%.2f mm²", defMin))
        dsSlider.valueProperty().addListener({ obs, o, n ->
            dsVal.setText(String.format(java.util.Locale.US, "%.0f", (double) Math.round(n.doubleValue())))
        } as javafx.beans.value.ChangeListener)
        minSlider.valueProperty().addListener({ obs, o, n ->
            minVal.setText(String.format(java.util.Locale.US, "%.2f mm²", n.doubleValue()))
        } as javafx.beans.value.ChangeListener)

        // Etkileşim: eşik kaydırıcı canlı önizler (ucuz); downsample/choice ağır → yeniden oku
        thrSlider.valueProperty().addListener({ obs, o, n -> if (!otsuChk.isSelected()) updatePreview() } as javafx.beans.value.ChangeListener)
        otsuChk.selectedProperty().addListener({ obs, o, n ->
            thrSlider.setDisable(n)
            def c = cache.get(); if (c != null && n) thrSlider.setValue((double) c.otsu)
            updatePreview()
        } as javafx.beans.value.ChangeListener)
        choiceBox.setOnAction({ rebuild(true) })
        // downsample: sürükleme bitince yeniden oku (ağır)
        dsSlider.valueChangingProperty().addListener({ obs, o, n -> if (!n) rebuild(true) } as javafx.beans.value.ChangeListener)
        dsSlider.setOnMouseReleased({ rebuild(true) })

        // Uygula: mevcut parametrelerle tespit et + anotasyon ekle (arka planda)
        applyBtn.setOnAction({
            def c = cache.get(); if (c == null) return
            double ds = (double) Math.round(dsSlider.getValue())
            double thr = currentThreshold()
            boolean useOtsu = otsuChk.isSelected()
            double minAreaMm2 = minSlider.getValue()
            String choice = (String) choiceBox.getValue()
            double minAreaPx2 = (calibrated && minAreaMm2 > 0) ? (minAreaMm2 * 1_000_000.0d / (pw * ph)) : 0.0d
            applyBtn.setDisable(true); applyBtn.setText("Uygulanıyor…")
            new Thread({
                def d
                try { d = detect((float[]) c.scalar, (int) c.W, (int) c.H, thr, c.request, minAreaPx2) }
                catch (Throwable t) { d = null }
                def summary = null
                if (d != null) { try { applyAnno(d.roi) } catch (Throwable t) {}; summary = summaryOf(choice, ds, thr, useOtsu, minAreaMm2, (int) d.pieces, d.roi) }
                javafx.application.Platform.runLater {
                    applyBtn.setDisable(false); applyBtn.setText("Uygula (Doku anotasyonu)")
                    if (d == null) {
                        summaryArea.setText("Doku bulunamadı — eşiği düşürün, downsample'ı ya da minimum alanı değiştirin.")
                        copyBtn.setDisable(true)
                    } else {
                        summaryArea.setText(summary); copyBtn.setDisable(false)
                        // parametreleri kaydet
                        prefs.put('choice', choice)
                        prefs.put('downsample', String.format(java.util.Locale.US, "%.1f", ds))
                        prefs.put('minAreaMm2', String.format(java.util.Locale.US, "%.3f", minAreaMm2))
                        prefs.put('useOtsu', Boolean.toString(useOtsu))
                        try { prefs.flush() } catch (Throwable ig) {}
                        println summary
                    }
                }
            } as Runnable).start()
        })

        copyBtn.setOnAction({
            def cb = javafx.scene.input.Clipboard.getSystemClipboard()
            def content = new javafx.scene.input.ClipboardContent()
            content.putString(summaryArea.getText()); cb.setContent(content)
        })
        closeBtn.setOnAction({ stage.close() })

        // ── Yerleşim ────────────────────────────────────────────────
        def form = new javafx.scene.layout.GridPane()
        form.setHgap(8); form.setVgap(8)
        def col0 = new javafx.scene.layout.ColumnConstraints(); col0.setMinWidth(120)
        def col1 = new javafx.scene.layout.ColumnConstraints(); col1.setHgrow(javafx.scene.layout.Priority.ALWAYS)
        def col2 = new javafx.scene.layout.ColumnConstraints(); col2.setMinWidth(80)
        form.getColumnConstraints().addAll(col0, col1, col2)
        int rr = 0
        form.add(new javafx.scene.control.Label("Eşik tabanı"), 0, rr); form.add(choiceBox, 1, rr, 2, 1); rr++
        form.add(new javafx.scene.control.Label("Downsample"), 0, rr); form.add(dsSlider, 1, rr); form.add(dsVal, 2, rr); rr++
        form.add(otsuChk, 0, rr, 3, 1); rr++
        form.add(new javafx.scene.control.Label("Eşik"), 0, rr); form.add(thrSlider, 1, rr); form.add(thrVal, 2, rr); rr++
        form.add(new javafx.scene.control.Label("Minimum alan"), 0, rr); form.add(minSlider, 1, rr); form.add(minVal, 2, rr); rr++

        def intro = new javafx.scene.control.Label(
            "Kaydırıcılarla parametreleri ayarlayın; ön plan (doku) turkuaz gösterilir. " +
            "Eşik tabanı ve downsample değişince görüntü yeniden okunur (biraz sürebilir).")
        intro.setWrapText(true); intro.setStyle("-fx-font-size: 12px; -fx-opacity: 0.85;")

        def leftBox = new javafx.scene.layout.VBox(10, intro, form, new javafx.scene.control.Separator(), statLabel, summaryArea)
        leftBox.setPadding(new javafx.geometry.Insets(12))
        leftBox.setPrefWidth(440)
        javafx.scene.layout.VBox.setVgrow(summaryArea, javafx.scene.layout.Priority.ALWAYS)

        def centerBox = new javafx.scene.layout.VBox(6,
            new javafx.scene.control.Label("Önizleme (doku = turkuaz):"), previewHolder)
        centerBox.setPadding(new javafx.geometry.Insets(12, 12, 12, 0))
        javafx.scene.layout.VBox.setVgrow(previewHolder, javafx.scene.layout.Priority.ALWAYS)

        def split = new javafx.scene.layout.HBox(0, leftBox, centerBox)
        javafx.scene.layout.HBox.setHgrow(centerBox, javafx.scene.layout.Priority.ALWAYS)

        def spacer = new javafx.scene.layout.Region()
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
        def bar = new javafx.scene.layout.HBox(10, alwaysTop, spacer, copyBtn, applyBtn, closeBtn)
        bar.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
        bar.setPadding(new javafx.geometry.Insets(8, 12, 8, 12))

        def footer = new javafx.scene.control.Label(
            "Bir ALAN ölçümüdür — klinik yorum/grade/tanı üretmez. Kilitli 'Doku' anotasyonu (idempotent).")
        footer.setMaxWidth(Double.MAX_VALUE)
        footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 12 2 12; -fx-font-size: 11px;")

        def bottom = new javafx.scene.layout.VBox(4, footer, bar)
        def root = new javafx.scene.layout.BorderPane()
        root.setCenter(split); root.setBottom(bottom)
        stage.setScene(new javafx.scene.Scene(root, 980, 640))
        stage.show()

        // İlk yükleme
        rebuild(true)
    } catch (Throwable t) {
        Dialogs.showErrorMessage("Sihirbaz açılamadı", t.getClass().getSimpleName() + ": " + (t.getMessage() ?: ""))
    }
}
println "✓ Doku Tespiti sihirbazı açıldı."
