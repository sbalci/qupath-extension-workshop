/**
 * Yardımcı - Entegre DAB OD (IOD)
 * ---------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Seçili alan anotasyonu içinde, hiyerarşideki piksel sınıflandırıcı
 *   çıktısından (veya tek bir "pozitif" maskesinden) her SINIF için
 *   DAB kanal optik yoğunluğunu (OD) piksel düzeyinde örnekler;
 *   toplamı (∑OD), ortalama OD, alanı (µm²) ve Entegre OD (IOD = ortalama
 *   OD × alan µm²) hesaplar. Sonuçlar anotasyona ölçüm olarak yazılır.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • DAB kanalının piksel-düzeyinde optik yoğunluğunu ve bunun integralini
 *     (IOD) ölçer — bu bir ORAN/ALAN ölçümüdür, klinik skor veya yorum DEĞİL.
 *   • IOD kalibrasyon bağımlıdır: ışık kaynağı, tarayıcı, boya partisi,
 *     renk ayrışma matriksi lamdan lama değişir; değerleri yalnızca
 *     aynı koşullar altında karşılaştırın.
 *   • Piksel sınıflandırıcı çıktısının hiyerarşide bulunması GEREKİR
 *     (ör. Tümör/Stroma modülü → sınıflandırıcı çalıştırılmış + deteksiyonlar var).
 *
 * KULLANIM:
 *   1. DAB-İHK lamı açın, Image type → "Brightfield (H-DAB)".
 *   2. Ölçmek istediğiniz bölgeyi alan anotasyonu olarak çizin ve SEÇİN.
 *   3. Tümör/Stroma modülü piksel sınıflandırıcısını önce çalıştırın (sınıflı deteksiyonlar
 *      hiyerarşide bulunmalı), VEYA sadece "Pozitif" sınıf adıyla devam edin.
 *   4. [Extensions → Atölye → Yardımcılar → Skorlama ve ölçüm → Entegre DAB OD (alan × OD)]
 *      (ya da [Automate → Project scripts → bu betik])
 *
 * ÇIKTI:
 *   • Seçili anotasyona sınıf başına ölçümler:
 *       "Entegre DAB OD (<sınıf>)"   — IOD = ortalama OD × alan µm²
 *       "Ortalama DAB OD (<sınıf>)"  — örneklenen piksel başına ortalama OD
 *       "Alan (<sınıf>) µm2"          — o sınıfa ait alan (µm²)
 *   • Toplam IOD ve IOD/mm² anotasyon ölçümleri
 *   • Sonuç penceresi: sınıf tablosu + toplamlar + kalibrasyon notu
 *
 * YÖNTEM / KAYNAK:
 *   • Renk ayrışma (H-DAB): getColorDeconvolutionStains() + ColorTransformer (Stain_2 = DAB;
 *     Ruifrok AC & Johnston DA 2001, Anal Quant Cytol Histol 23(4):291-299).
 *   • IOD konsepti: Bacus JW & Grace LJ (1987), Appl Opt 26(16):3280-3293. doi:10.1364/AO.26.003280
 *   • Kalibrasyon bağımlılığı: van der Loos CM (2008), J Histochem Cytochem 56(4):313-328.
 *     doi:10.1369/jhc.2007.950170
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.color.ColorTransformer
import qupath.lib.color.ColorTransformer.ColorTransformMethod
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── showResultWindow (verbatim from yardimci-kesisim-alani.groovy) ───
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

            stage.setScene(new javafx.scene.Scene(root, 720, 520))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Parametreler ────────────────────────────────────────────────────
int DOWNSAMPLE = 2   // örnekleme için tam sayı ölçek düşürme (2x → ~%25 piksel)

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    if (isHeadless) println "HATA: Görüntü açık değil." else Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.284651a2e0c4', "Önce bir DAB-İHK lamı açın."))
    return
}

def server = imageData.getServer()
def cal = server.getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    def msg = workshopText('script.31cf243088d4', 'Lamda piksel boyutu (µm) tanımlı değil; alan µm² olarak hesaplanamaz.\n\nKalibrasyon için: Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu).')
    if (isHeadless) println "HATA: ${msg}" else Dialogs.showErrorMessage(workshopText('script.a5a01874aff2', "Kalibrasyon yok"), msg)
    return
}

def stains = imageData.getColorDeconvolutionStains()
if (stains == null) {
    def msg = workshopText('script.786ba57a1143', 'Renk ayrışma (color deconvolution) tanımlı değil.\nImage type → \'Brightfield (H-DAB)\' olarak ayarlayın.')
    if (isHeadless) println "HATA: ${msg}" else Dialogs.showErrorMessage(workshopText('script.0bf34cab947d', "Renk ayrışma yok"), msg)
    return
}

// Seçili tek alan anotasyonu: IOD kapsamı
def selectedAnnotations = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI()?.isArea() }
if (selectedAnnotations.isEmpty()) {
    def msg = workshopText('script.b62203a719cc', 'Ölçüm kapsamı olarak seçili bir ALAN anotasyonu yok.\n\nÖnce bölgeyi çizin, seçin, ardından tekrar çalıştırın.')
    if (isHeadless) println "UYARI: ${msg}" else Dialogs.showWarningNotification(workshopText('script.da079a56fe7d', "Anotasyon seçili değil"), msg)
    return
}
def scopeAnno = selectedAnnotations[0]
def scopeROI = scopeAnno.getROI()

// Kapsam içindeki alt deteksiyonların sınıflarını topla
def childDetections = QP.getDetectionObjects().findAll { det ->
    det.getPathClass() != null && scopeROI.getGeometry().intersects(det.getROI().getGeometry())
}
def classNames = childDetections.collect { it.getPathClass().toString() }.unique().sort()

if (classNames.isEmpty()) {
    def msg = workshopText('script.815202b550d4', 'Seçili anotasyon içinde sınıflandırılmış deteksiyon yok.\n\n') +
              workshopText('script.4e00f547d352', 'Önce Tümör/Stroma modülü piksel sınıflandırıcısını \'Seçili anotasyonda çalıştır\' ile\n') +
              workshopText('script.d4195729b0b9', 'çalıştırın (deteksiyonlar hiyerarşide bulunmalı).')
    if (isHeadless) println "HATA: ${msg}" else Dialogs.showErrorMessage(workshopText('script.e2ee9332714f', "Deteksiyon/sınıf yok"), msg)
    return
}

// ── 2) Her sınıf için piksel düzeyinde DAB OD örnekleme ─────────────
println String.format(java.util.Locale.US,
    "Entegre DAB OD: %d sınıf · kapsam '%s' · downsample=%d...",
    classNames.size(), (scopeAnno.getName() ?: "seçili"), DOWNSAMPLE)

// Stain 2 = DAB (H-DAB ayrışmasında). Yanlış boya kurulumunda (ör. H&E) 2. vektör
// DAB olmayabilir; o zaman "DAB OD" olarak etiketlemek yanıltıcı olur — önce doğrula.
def dabStain = stains.getStain(2)
if (dabStain == null || !((dabStain.getName() ?: '').toLowerCase(java.util.Locale.ROOT).contains('dab'))) {
    def msg = String.format(java.util.Locale.US, workshopText('script.73baf4104cde', '2. boya vektörü DAB değil (bulunan: \'%s\').\n\nImage type → \'Brightfield (H-DAB)\' olduğundan ve boya vektörlerinin doğru\nayarlandığından emin olun (Boyama normalizasyonu eki · boya vektörleri sihirbazı).'), (dabStain?.getName() ?: workshopText('script.715392eca0f8', '(yok)')))
    if (isHeadless) println "HATA: ${msg}" else Dialogs.showErrorMessage(workshopText('script.b21715242e3e', "DAB boyası bulunamadı"), msg)
    return
}

// Kapsam bölgesini downsample'lı görüntü olarak oku
def request = qupath.lib.regions.RegionRequest.createInstance(
    server.getPath(), DOWNSAMPLE, scopeROI)
def img = server.readRegion(request)
int imgW = img.getWidth()
int imgH = img.getHeight()
int[] pixels = new int[imgW * imgH]
img.getRGB(0, 0, imgW, imgH, pixels, 0, imgW)
float[] dabChannel = ColorTransformer.getTransformedPixels(pixels, ColorTransformMethod.Stain_2, null, stains)

// Koordinat dönüşümü: piksel (ix,iy) → görüntü koordinatı
double originX = request.getX()
double originY = request.getY()
def scopeGeom = scopeROI.getGeometry()

// Her sınıf için kümülatif OD ve piksel sayısı
def classCounts = [:]
def classSumOD  = [:]
classNames.each { cn -> classCounts[cn] = 0L; classSumOD[cn] = 0.0d }

// Sınıf geometri birleşimleri (JTS) — kapsamla kesişen deteksiyonlar
def classGeoms = [:]
classNames.each { cn ->
    def geoms = childDetections.findAll { it.getPathClass().toString() == cn }
        .collect { it.getROI().getGeometry() }
    def union = null
    geoms.each { g -> union = (union == null) ? g : union.union(g) }
    classGeoms[cn] = union
}

double pixelAreaUm2 = pw * ph * DOWNSAMPLE * DOWNSAMPLE

// Tek paylaşılan GeometryFactory — piksel döngüsünde yeniden oluşturmak çok pahalı.
def gf = new org.locationtech.jts.geom.GeometryFactory()
pixels.eachWithIndex { rgb, idx ->
    int ix = idx % imgW
    int iy = idx / imgW
    // Görüntü koordinatına çevir
    double gx = originX + ix * DOWNSAMPLE
    double gy = originY + iy * DOWNSAMPLE
    def pt = gf.createPoint(new org.locationtech.jts.geom.Coordinate(gx, gy))
    if (scopeGeom != null && !scopeGeom.contains(pt)) return

    // Hangi sınıf geometrisi bu noktayı içeriyor?
    String hitClass = null
    for (cn in classNames) {
        def g = classGeoms[cn]
        if (g != null && g.contains(pt)) { hitClass = cn; break }
    }
    if (hitClass == null) return

    // Gerçek 3×3 ters renk dekonvolüsyonu (Stain_2 = DAB OD; QuPath "DAB OD mean" ile birebir uyumlu)
    double dabOD = (double) dabChannel[idx]
    if (Double.isNaN(dabOD) || dabOD < 0.0d) dabOD = 0.0d

    classCounts[hitClass] = classCounts[hitClass] + 1
    classSumOD[hitClass]  = classSumOD[hitClass]  + dabOD
}

// ── 3) Ölçümleri anotasyona yaz ─────────────────────────────────────
double totalIOD       = 0.0
double totalAreaUm2   = 0.0

classNames.each { cn ->
    long count     = classCounts[cn]
    double sumOD   = classSumOD[cn]
    double areaUm2 = count * pixelAreaUm2
    double meanOD  = (count > 0) ? sumOD / count : 0.0
    double iod     = meanOD * areaUm2

    scopeAnno.measurements[String.format(java.util.Locale.US, "Entegre DAB OD (%s)", cn)] = iod
    scopeAnno.measurements[String.format(java.util.Locale.US, "Ortalama DAB OD (%s)", cn)] = meanOD
    scopeAnno.measurements[String.format(java.util.Locale.US, "Alan (%s) µm2", cn)] = areaUm2

    totalIOD     += iod
    totalAreaUm2 += areaUm2
}

// Payda KAPSAM anotasyonunun alanıdır. Sınıf alanlarının toplamına bölünseydi alan sadeleşir,
// sonuç ortalama OD × 10^6 olurdu (boyalı alanın büyüklüğünden bağımsız bir sayı).
double scopeAreaUm2 = scopeROI.getScaledArea(pw, ph)
double iodPerMm2 = (scopeAreaUm2 > 0) ? totalIOD / (scopeAreaUm2 / 1e6) : 0.0
scopeAnno.measurements["Toplam Entegre DAB OD"]   = totalIOD
scopeAnno.measurements["Entegre DAB OD / mm2"]    = iodPerMm2
QP.fireHierarchyUpdate()

// ── 4) Sonuç penceresi ───────────────────────────────────────────────
def colW = [28, 14, 14, 18]
def headerFmt = "%-${colW[0]}s %${colW[1]}s %${colW[2]}s %${colW[3]}s%n"
def rowFmt    = "%-${colW[0]}s %${colW[1]}.4f %${colW[2]}.4f %${colW[3]}.2f%n"

def body = new StringBuilder()
body << workshopText('script.9c0ee4ca65b5', 'ENTEGRE DAB OD (pozitif alan × ortalama OD)\n')
body << "═════════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, workshopText('script.deb7e2cf767f', 'Kapsam anotasyonu  : %s%n'), (scopeAnno.getName() ?: workshopText('script.ed0d50f17972', '(isimsiz)')))
body << String.format(java.util.Locale.US, workshopText('script.970e0680220c', 'Sınıf sayısı       : %d%n'), classNames.size())
body << String.format(java.util.Locale.US, "Downsample         : %dx%n", DOWNSAMPLE)
body << String.format(java.util.Locale.US, workshopText('script.cb24e7296816', 'Piksel boyutu      : %.4f × %.4f µm%n'), pw, ph)
body << "\n"
body << String.format(java.util.Locale.US, headerFmt, workshopText('script.75c4ae45637f', 'Sınıf'), workshopText('script.9ab280a12202', 'Alan µm²'), workshopText('script.373edb6356d0', 'Ort. OD'), "IOD")
body << ("-" * (colW.sum() + colW.size())) + "\n"
classNames.each { cn ->
    double areaUm2 = scopeAnno.measurements[String.format(java.util.Locale.US, "Alan (%s) µm2", cn)] ?: 0.0
    double meanOD  = scopeAnno.measurements[String.format(java.util.Locale.US, "Ortalama DAB OD (%s)", cn)] ?: 0.0
    double iod     = scopeAnno.measurements[String.format(java.util.Locale.US, "Entegre DAB OD (%s)", cn)] ?: 0.0
    body << String.format(java.util.Locale.US, rowFmt, cn, areaUm2, meanOD, iod)
}
body << ("-" * (colW.sum() + colW.size())) + "\n"
body << String.format(java.util.Locale.US, "%-${colW[0]}s %${colW[1]+colW[2]+2}s %${colW[3]}.2f%n", workshopText('script.1c54e7a8b22c', 'TOPLAM'), "", totalIOD)
body << String.format(java.util.Locale.US, "%n")
body << String.format(java.util.Locale.US, workshopText('script.029a5cb8f0fb', 'Toplam IOD        : %.2f%n'), totalIOD)
body << String.format(java.util.Locale.US, workshopText('script.55ed3c45b3d8', 'IOD / mm²         : %.2f   (payda: kapsam anotasyonunun alanı, %.4f mm²)%n'), iodPerMm2, scopeAreaUm2 / 1e6)
body << "\n"
body << workshopText('script.3ef39caedae9', 'Not: IOD kalibrasyon bağımlıdır (tarayıcı, boya, renk ayrışma matriksi).\n')
body << workshopText('script.3a4f53e66140', 'Değerleri YALNIZCA aynı protokol/tarayıcı koşullarında karşılaştırın.\n')
body << workshopText('script.9ffa3280f341', 'Bu bir ALAN/OD ölçümüdür — klinik skor veya yorum DEĞİL.\n')
body << "(Bacus & Grace 1987; van der Loos 2008; Bankhead 2017)\n\n"
body << workshopText('script.cf6099f0a005', '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')

showResultWindow(workshopText('script.a92f4f8ad4c5', 'Entegre DAB OD'), body.toString())
println String.format(java.util.Locale.US,
    "✓ Entegre DAB OD yazıldı: toplam IOD=%.2f, IOD/mm²=%.2f (%d sınıf).",
    totalIOD, iodPerMm2, classNames.size())

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
