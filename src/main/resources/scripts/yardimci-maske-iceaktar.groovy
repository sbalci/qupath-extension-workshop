/**
 * Yardımcı - Maske görüntüsünü içe aktar (raster → anotasyon)
 * ----------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   İndeksli ya da ikili bir RASTER maske görüntüsünü (PNG/TIFF; arka plan = 0,
 *   ön plan = 1, 2, ...) okur ve her sınıf indeksini izleyerek (contour tracing)
 *   QuPath anotasyon nesnelerine çevirir. "TIA Toolbox için bölge maskesi"
 *   yardımcısının yazdığı maskeyi geri yükler — böylece dışa-aktar → (harici
 *   Python derin öğrenme / U-Net çıkarımı) → içe-aktar tam döngüsü kapanır.
 *
 *   Karşılaştırma: "AI tahmin maskelerini içe aktar" GeoJSON POLİGON tahminlerini
 *   alır; bu betik ise tek-kanallı RASTER (piksel) maskeleri alır. İkisi
 *   birbirini tamamlar. İlham: DBM-MCF qupath-extension-efficientv2unet'in
 *   "Load a mask image" işlevi (ikili maske → anotasyon).
 *
 *   KAVRAM (Bankhead): tek-kanallı indeksli maske bir "etiketli görüntü"dür
 *   (labelled image: 0 = arka plan, her sıfır-dışı tamsayı = ayrı nesne/sınıf);
 *   ikili (binary) maske yalnız ön plan/arka plan ayırır.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Yalnız bir maskeyi QuPath görselleştirme/ölçüm katmanına TAŞIR; sınıf
 *     başına anotasyon sayısı ve alan (mm²) üretir. Patoloji yorumu, grade veya
 *     klinik karar üretmez. Maske bir derin öğrenme çıktısıysa görsel doğrulama
 *     gerekir (Yapay Zekâ Araçlarını Değerlendirme eki).
 *   • Maske, açık görüntünün TABAN (level-0) piksel uzayında ölçeklenmiş kabul
 *     edilir; downsample (1 maske pikseli = kaç görüntü pikseli) yan dosyadan
 *     (<maske>.json) okunur, yoksa boyut oranından önerilip sorulur.
 *
 * MASKE BİÇİMİ:
 *   Tek kanallı indeksli/gri PNG (önerilir) ya da TIFF. Değerler: 0 = arka plan,
 *   sıfır-dışı = ön plan sınıf indeksi. Yan dosya <maske>.json varsa
 *   (downsample + "labels" {indeks→sınıf}) ölçek ve sınıf adları otomatik okunur.
 *
 * KULLANIM:
 *   1. Maskenin türetildiği lamı QuPath'te açın.
 *   2. [Extensions → Atölye → Yardımcılar → İçe ve dışa aktarma / veri → Maske görüntüsünü içe aktar]
 *   3. Maske dosyasını seçin; (yan dosya yoksa) downsample ve ön plan sınıf adını girin.
 *
 * ÇIKTI:
 *   • Sınıflandırılmış, kilitli anotasyonlar ("Maske içe aktarım" adıyla; yeniden
 *     içe aktarımda otomatik temizlenir → idempotent).
 *   • Sınıf başına anotasyon sayısı + alan dökümünü gösteren özet penceresi.
 *
 * API / BELGE: ContourTracing.createTracedROI(Raster, min, max, band, RegionRequest)
 *   + RegionRequest (downsample/öteleme ile taban piksel uzayına eşleme) — QuPath 0.6.0+.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.regions.RegionRequest
import qupath.lib.analysis.images.ContourTracing
import java.io.File

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

def showResultWindow = { String windowTitle, String windowBody ->
    if (isHeadless) {
        println "=== ${windowTitle} ===\n${windowBody}\n=================="
        return
    }
    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle(windowTitle)
            stage.setAlwaysOnTop(true)

            def textArea = new javafx.scene.control.TextArea(windowBody)
            textArea.setEditable(false)
            textArea.setWrapText(false)
            textArea.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;")

            def alwaysTop = new javafx.scene.control.CheckBox(workshopText('script.70ca64c0c43a', "Üstte tut"))
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )

            def copyBtn = new javafx.scene.control.Button(workshopText('script.a8bcca42d9ce', "Kopyala"))
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent()
                content.putString(windowBody)
                cb.setContent(content)
            })

            def closeBtn = new javafx.scene.control.Button(workshopText('script.7b31a9fc4816', "Kapat"))
            closeBtn.setDefaultButton(true)
            closeBtn.setOnAction({ stage.close() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)

            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, copyBtn, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(textArea)
            def __footer = new javafx.scene.control.Label(workshopText('script.be46103cef99', "QuPath Atölye Betikleri · araştırma/eğitim amaçlı"))
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons)
            __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)

            stage.setScene(new javafx.scene.Scene(root, 760, 580))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
String importSentinelName = 'Maske içe aktarım'   // önceki içe aktarımı tanımak için
boolean lockImported      = true                  // anotasyonları kilitle (kazara düzenlemeyi önler)
int maxDistinctLabels     = 256                   // bunun üstünde maske ikili kabul edilir (tüm sıfır-dışı = ön plan)

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.8138977fc63e', "Önce maskenin türetildiği lamı açın."))
    return
}
if (isHeadless) {
    println "Maske dosya seçimi için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    return
}
def server = imageData.getServer()

// ── 2) Maske dosyasını seç ──────────────────────────────────────────
// qupath.fx.dialogs.Dialogs'ta promptForFile YOK (0.6/0.7'de doğrulandı) → FileChoosers kullanılır.
def maskFile = qupath.fx.dialogs.FileChoosers.promptForFile(
    "Maske görüntüsünü seçin",
    qupath.fx.dialogs.FileChoosers.createExtensionFilter("Maske görüntüsü (*.png, *.tif, *.tiff)", "*.png", "*.tif", "*.tiff"))
if (maskFile == null) {
    println "İptal edildi — dosya seçilmedi."
    return
}

// ── 3) Yan dosya (<maske>.json) — downsample + sınıf adları ──────────
def baseName = maskFile.getName().replaceAll(/\.[^.]+$/, '')
def jsonSidecar = new File(maskFile.getParentFile(), baseName + ".json")
Map sidecar = null
if (jsonSidecar.isFile()) {
    try { sidecar = (Map) qupath.lib.io.GsonTools.getInstance().fromJson(jsonSidecar.getText("UTF-8"), Map.class) }
    catch (Throwable t) { sidecar = null }
}
def labelClass = [:]   // int indeks → sınıf adı (yan dosyadan)
if (sidecar != null && sidecar.labels instanceof Map) {
    sidecar.labels.each { k, v ->
        try {
            int iv = Integer.parseInt(k.toString().trim())
            if (iv != 0 && v != null) labelClass[iv] = v.toString()
        } catch (Throwable ignore) {}
    }
}

// ── 4) Maskeyi oku (ImageIO; başarısızsa QuPath sunucusu) ───────────
java.awt.image.BufferedImage img = null
try { img = javax.imageio.ImageIO.read(maskFile) } catch (Throwable t) { img = null }
if (img == null) {
    def ms = null
    try {
        ms = qupath.lib.images.servers.ImageServerProvider.buildServer(
            maskFile.toURI().toString(), java.awt.image.BufferedImage.class)
        img = ms.readRegion(RegionRequest.createInstance(
            ms.getPath(), 1.0d, 0, 0, ms.getWidth(), ms.getHeight()))
    } catch (Throwable t) { img = null }
    finally { try { ms?.close() } catch (Throwable ignore) {} }
}
if (img == null) {
    Dialogs.showErrorMessage(workshopText('script.218214fb0be5', "Maske okunamadı"),
        workshopText('script.ff368f6f756d', "Dosya bir görüntü olarak açılamadı.\nTek kanallı indeksli/gri PNG önerilir."))
    return
}
def raster = img.getRaster()
int maskW = raster.getWidth()
int maskH = raster.getHeight()
int numBands = raster.getNumBands()
if (maskW <= 0 || maskH <= 0) {
    Dialogs.showErrorMessage(workshopText('script.2b3e2a1bab37', "Geçersiz maske"), workshopText('script.2b4aac9fce3c', "Maske boyutları okunamadı."))
    return
}

// ── 5) Downsample (yan dosyadan ya da boyut oranından sorularak) ─────
double downsample
if (sidecar != null && sidecar.downsample != null) {
    try { downsample = ((Number) sidecar.downsample).doubleValue() }
    catch (Throwable t) { downsample = 1.0d }
} else {
    double suggested = (maskW > 0) ? (server.getWidth() / (double) maskW) : 1.0d
    if (!(suggested > 0)) suggested = 1.0d
    def dsText = Dialogs.showInputDialog(
        workshopText('script.a1e83025004d', "Maske içe aktar"),
        workshopText('script.718a1e0c9997', "Downsample — 1 maske pikseli kaç görüntü pikseline denk?\n" +
        "(Boyut oranından öneri: ") + String.format(java.util.Locale.US, "%.3f", suggested) + ")",
        String.format(java.util.Locale.US, "%.3f", suggested))
    if (dsText == null) { println "İptal edildi."; return }
    try { downsample = Double.parseDouble(dsText.trim().replace(',', '.')) }
    catch (NumberFormatException nfe) {
        Dialogs.showErrorMessage(workshopText('script.9cc391204f66', "Sayı formatı"), workshopText('script.0d0e0e36ccff', "Downsample ondalık bir sayı olmalı (ör. 4.0)."))
        return
    }
}
if (!(downsample > 0)) downsample = 1.0d

// Boyut tutarlılık uyarısı (yanlış lam açık olabilir) — engelleyici değil
double impliedW = maskW * downsample
double tolerance = Math.max(8.0d, server.getWidth() * 0.02d)
if (Math.abs(impliedW - server.getWidth()) > tolerance) {
    def proceed = Dialogs.showConfirmDialog(workshopText('script.2c91f9a51247', "Boyut uyuşmuyor"),
        String.format(java.util.Locale.US,
            workshopText('script.cca1fafeaa39', "Maske × downsample = %,.0f px, açık görüntü genişliği = %,d px.%n%n" +
            "Maske bu lamdan türememiş ya da downsample yanlış olabilir.%n" +
            "Yine de devam edilsin mi?"),
            impliedW, server.getWidth()))
    if (!proceed) { println "Kullanıcı boyut-uyuşmazlığı onayını reddetti."; return }
}

// ── 6) Maskede bulunan sıfır-dışı indeksleri tara (satır-satır) ──────
def labelsPresent = new TreeSet<Integer>()
boolean binaryFallback = false
int[] rowBuf = new int[maskW]
int maxSeen = 0   // BÜTÜN maskedeki en büyük değer; ikili geri dönüşte izleme üst sınırıdır
for (int y = 0; y < maskH; y++) {
    raster.getSamples(0, y, maskW, 1, 0, rowBuf)
    for (int x = 0; x < maskW; x++) {
        int v = rowBuf[x]
        if (v == 0) continue
        if (v > maxSeen) maxSeen = v
        if (!binaryFallback) labelsPresent.add(v)
    }
    // Çok fazla ayrı değer → ikili (ön plan/arka plan) okuma. Tarama burada KESİLMEZ:
    // erken kesilseydi üst sınır yalnız ilk satırlardan gelir, alttaki daha büyük değerli pikseller dışarıda kalırdı.
    if (labelsPresent.size() > maxDistinctLabels) binaryFallback = true
}
if (labelsPresent.isEmpty()) {
    Dialogs.showWarningNotification(workshopText('script.f8130546efea', "Boş maske"),
        workshopText('script.91f9c6f28913', "Maskede sıfır-dışı (ön plan) piksel bulunamadı — tüm görüntü arka plan (0)."))
    return
}

// ── 7) Eksik sınıf adları için ön plan adı iste (yalnız gerektiğinde) ─
String fgName = null
def unmapped = labelsPresent.findAll { !labelClass.containsKey(it) }
if (binaryFallback || !unmapped.isEmpty()) {
    fgName = Dialogs.showInputDialog(workshopText('script.a1e83025004d', "Maske içe aktar"), workshopText('script.203eedab575f', "Ön plan (foreground) sınıf adı:"), "Maske")
    if (fgName == null) { println "İptal edildi."; return }
    fgName = fgName.trim()
    if (fgName.isEmpty()) fgName = "Maske"
}

// ── 8) Bölme seçeneği ───────────────────────────────────────────────
boolean split = Dialogs.showConfirmDialog(workshopText('script.662023511552', "Bölme seçeneği"),
    workshopText('script.8ecb4f6a92b7', "Evet: bitişik her bölge ayrı anotasyon.\n" +
    "Hayır: sınıf başına tek (birleşik) anotasyon."))

// ── 9) Maske → anotasyon (ContourTracing) ───────────────────────────
def request = RegionRequest.createInstance(
    server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight())

def safeSplit = { roi ->
    try { return qupath.lib.roi.RoiTools.splitROI(roi) }
    catch (Throwable t) { return [roi] }
}
def newAnns = []
def classCounts = new TreeMap<String, Integer>()
def classAreaPx = new TreeMap<String, Double>()
def addRoiAsAnnotations = { roi, String className ->
    if (roi == null || roi.isEmpty()) return
    def pc = QP.getPathClass(className)
    def roiList = split ? safeSplit(roi) : [roi]
    roiList.each { r ->
        if (r == null || r.isEmpty()) return
        def ann = PathObjects.createAnnotationObject(r, pc)
        ann.setName(importSentinelName)
        if (lockImported) ann.setLocked(true)
        newAnns << ann
        classCounts[className] = (classCounts.getOrDefault(className, 0)) + 1
        classAreaPx[className] = (classAreaPx.getOrDefault(className, 0.0d)) + r.getArea()
    }
}

if (numBands > 1) {
    println "Uyarı: maske ${numBands} kanallı — yalnız ilk kanal (band 0) kullanılıyor."
}

if (binaryFallback) {
    int maxVal = maxSeen
    def roi = ContourTracing.createTracedROI(raster, 1.0d, (double) maxVal, 0, request)
    addRoiAsAnnotations(roi, fgName)
} else {
    labelsPresent.each { v ->
        String cn = labelClass.containsKey(v)
            ? (String) labelClass[v]
            : (labelsPresent.size() == 1 ? fgName : (fgName + " " + v))
        def roi = ContourTracing.createTracedROI(raster, (double) v, (double) v, 0, request)
        addRoiAsAnnotations(roi, cn)
    }
}

if (newAnns.isEmpty()) {
    Dialogs.showWarningNotification(workshopText('script.feae2d0ca081', "Bölge izlenemedi"),
        workshopText('script.373b013cf087', "Maske okundu ancak izlenebilir bir ön plan bölgesi üretilemedi."))
    return
}

// ── 10) Önceki içe aktarım varsa SOR (öntanımlı: hiçbir şeyi silme), sonra ekle ──
// Eski davranış önceki içe aktarımı ve ALTINDAKİ her şeyi (ör. içinde çalıştırılmış hücre
// tespitleri) sormadan siliyordu. Artık: öntanımlı yalnız ekler; "değiştir" seçilirse yalnız
// bu yardımcının kendi anotasyonları kaldırılır, altlarındaki nesneler korunur.
def oldImports = QP.getAnnotationObjects().findAll { it.getName() == importSentinelName }
if (!oldImports.isEmpty()) {
    def optAdd = "Ekle — hiçbir şeyi silme"
    def optRep = "Önceki içe aktarımı değiştir"
    def ch = Dialogs.showChoiceDialog(workshopText('script.dd2c102cce23', "Önceki içe aktarım bulundu"),
        workshopText('script.cb9ed0395ead', "Bu görüntüde önceki bir içe aktarımdan ") + oldImports.size() + workshopText('script.465be7ff125b', " anotasyon var (\"") + importSentinelName + "\").\n\n" +
        workshopText('script.529d5ee71f24', "• Ekle: hiçbir şey silinmez; yeni bölgeler öncekilerin yanına eklenir (aynı maskeyi\n") +
        workshopText('script.186ea928ba70', "  yeniden içe aktarıyorsanız çift kopya oluşur).\n") +
        workshopText('script.51a408148751', "• Değiştir: yalnız bu önceki anotasyonlar kaldırılır (elle düzelttiğiniz sınırlar dahil);\n") +
        workshopText('script.4d12c7800032', "  altlarındaki tespitler ve diğer anotasyonlar korunur."),
        [optAdd, optRep], optAdd)
    if (ch == null) { println "İptal edildi — hiçbir şey değiştirilmedi."; return }
    if (ch == optRep) QP.removeObjects(oldImports, true)
}
QP.addObjects(newAnns)
QP.fireHierarchyUpdate()

// ── 11) Özet ────────────────────────────────────────────────────────
def cal = server.getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
boolean calibrated = (pw > 0 && ph > 0 && !Double.isNaN(pw) && !Double.isNaN(ph))

def body = new StringBuilder()
body << "MASKE İÇE AKTARMA — RASTER → ANOTASYON\n"
body << "══════════════════════════════════════\n\n"
body << "Maske    : ${maskFile.getName()}\n"
body << "Görüntü  : ${server.getMetadata().getName()}\n"
body << (sidecar != null ? "Yan dosya: ${jsonSidecar.getName()} (downsample + sınıf adları okundu)\n" : "Yan dosya: yok (downsample/sınıf elle girildi)\n")
body << String.format(java.util.Locale.US, "Maske     : %,d × %,d px → downsample %.3f%n", maskW, maskH, downsample)
body << (binaryFallback ? "Mod      : ikili (çok sayıda farklı değer → tüm sıfır-dışı = ön plan)\n" : "")
body << String.format(java.util.Locale.US, "%nİçe aktarılan anotasyon : %,d  (bölme: %s)%n", newAnns.size(), (split ? "evet" : "hayır"))
body << "\nSınıf dökümü (sınıf → adet" << (calibrated ? " → alan mm²" : "") << "):\n"
classCounts.each { cls, n ->
    if (calibrated) {
        double mm2 = (classAreaPx.getOrDefault(cls, 0.0d)) * pw * ph / 1_000_000.0d
        body << String.format(java.util.Locale.US, "  %-22s : %,6d   %.3f mm²%n", cls, n, mm2)
    } else {
        body << String.format(java.util.Locale.US, "  %-22s : %,6d%n", cls, n)
    }
}
if (!calibrated) body << "\n(Görüntü kalibre değil — alanlar mm² olarak verilemedi.)\n"
body << "\nTüm bölgeler" << (lockImported ? " kilitli" : "") << " anotasyon olarak eklendi.\n"
body << "Maske bir derin öğrenme çıktısıysa görsel olarak doğrulayın; klinik yorum üretilmez.\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar."

showResultWindow("Maske içe aktarma", body.toString())
println "✓ Maske içe aktarımı tamamlandı (${newAnns.size()} anotasyon, ${labelsPresent.size()} indeks)."

// Resolve extension UI language; standalone scripts keep their Turkish fallback.
private String workshopText(String key, String fallback) {
    try {
        def bundle = Class.forName('io.github.sbalci.qupath.workshop.WorkshopI18n')
        String translated = bundle.getMethod('t', String.class).invoke(null, key) as String
        return translated == key ? fallback : translated
    } catch (Throwable ignored) {
        return fallback
    }
}
