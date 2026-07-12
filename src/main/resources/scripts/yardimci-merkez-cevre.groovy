/**
 * Yardımcı - Eşit-alan Merkez / Çevre bölütleme
 * ----------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * ESİN (KAYNAK):
 *   Bu araç, DANEELpath'in "Centre-Periphery" (eşit-alan merkez/çevre)
 *   aracından ESİNLENİLMİŞTİR:
 *     Vieco-Martí I ve ark. (2026), Scientific Reports 16:6162 —
 *     "DANEELpath open source digital analysis tools for histopathological
 *     research in neuroblastoma models". doi:10.1038/s41598-026-37134-5
 *   DANEELpath eklentisi GPL-3.0; bu betik ise makalede TARİF EDİLEN
 *   algoritmanın bağımsız (temiz-oda) yeniden yazımıdır — GPL kaynaktan
 *   kod aktarımı YOKTUR. Ayrıntı: DANEELpath eki (ekler/daneelpath.qmd).
 *
 * NE YAPAR:
 *   SEÇİLİ alan anotasyonlarını, ŞEKİLDEN BAĞIMSIZ olarak ~EŞİT ALANLI iç/dış
 *   bölgelere böler (varsayılan: merkez + çevre = 2 bölge). Yöntem: geometriyi
 *   içe doğru buffer(−d) ile aşındırıp, alanı hedef değere getiren mesafeyi
 *   ikili arama (binary search) ile bulur; ardışık sınırların difference()'ı
 *   her bölgeyi verir (JTS geometri cebri). Bölge sayısı 2–6 seçilebilir.
 *
 * NEDEN yarıçap değil EŞİT ALAN:
 *   Yarıçap-temelli merkez/çevre tanımının iki sorunu vardır: (i) çoğu ROI tam
 *   daire değildir (uzun/kısa eksen); (ii) dairede alan yarıçapın KARESİYLE (r²)
 *   büyür, küçük yarıçap değişimi örneklemeyi yanlı yapar. Eşit-alan bölütleme
 *   şekilden bağımsız DENGELİ ÖRNEKLEME verir — merkez ile çevre karşılaştırmaları
 *   (ör. TIL/immün gradyan, çeperde makrofaj zenginleşmesi) için istatistiksel
 *   olarak daha adil bir zemindir.
 *
 * NE ÜRETİR (ve ne ÜRETMEZ):
 *   • Yalnız GEOMETRİ + ölçüm üretir: her bölge için alan (µm²), toplamın %'si ve
 *     (tespit varsa) içindeki tespit sayısı + yoğunluk (adet/mm²).
 *   • Kaynak alan(lar)ı sizin çizip SEÇMENİZ gerekir. Skor/eşik/evre/klinik
 *     yorum ÜRETMEZ.
 *   • Yalnız ALAN anotasyonlarıyla çalışır (nokta/çizgi ROI atlanır).
 *
 * KULLANIM:
 *   1. Kalibre (µm/px) bir slayt açın; kaynak alanı anotasyon olarak çizip SEÇİN
 *      (birden çok da olabilir — her biri ayrı bölünür)
 *   2. [Extensions → Atölye → Yardımcılar → Uzamsal analiz → Eşit-alan merkez/çevre]
 *      (ya da [Automate → Project scripts → bu betik])
 *   3. Bölge sayısını girin (varsayılan 2 = merkez + çevre)
 *
 * ÇIKTI:
 *   • Her kaynak için "Merkez (eşit-alan)" / "Çevre (eşit-alan)" (+ ara bölge)
 *     sınıflı bölge anotasyonları (alan + % + tespit ölçümleriyle)
 *   • Sonuç penceresinde bölge alanları ve bölgeler-arası maks. % fark
 *     (eşit-alan doğruluğu — makalede 48 WSI'da <%0,4)
 *   • Her çalıştırma önceki eşit-alan bölgelerini YENİLER
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı geometri/ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/merkez-cevre')

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

            def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut")
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )

            def copyBtn = new javafx.scene.control.Button("Kopyala")
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent()
                content.putString(windowBody)
                cb.setContent(content)
            })

            def closeBtn = new javafx.scene.control.Button("Kapat")
            closeBtn.setDefaultButton(true)
            closeBtn.setOnAction({ stage.close() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)

            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, copyBtn, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(textArea)
            def __footer = new javafx.scene.control.Label("QuPath Atölye Scriptleri · araştırma/eğitim amaçlı")
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

// ── Sabitler ────────────────────────────────────────────────────────
String CLS_CENTER = "Merkez (eşit-alan)"
String CLS_PERIPH = "Çevre (eşit-alan)"
String CLS_MIDDLE = "Ara bölge (eşit-alan)"
def REGION_CLASSES = [CLS_CENTER, CLS_PERIPH, CLS_MIDDLE]
int DEF_REGIONS = 2
int MAX_REGIONS = 6
int DET_CAP = 200000        // bu sayıdan çok tespit varsa bölge-başı sayım atlanır

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce merkez/çevreye bölünecek alanı anotasyon olarak çizip seçin.")
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
if (!(pw > 0) || !(ph > 0)) {
    Dialogs.showErrorMessage("Kalibrasyon yok", "Slaytta piksel boyutu (µm) tanımlı değil; alan µm² olarak üretilemez." +
        "\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu).")
    return
}

def targets = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI()?.isArea() && !REGION_CLASSES.contains(it.getPathClass()?.toString())
}
if (targets.isEmpty()) {
    def msg = "Kaynak alan seçili değil.\n\n" +
              "Merkez/çevreye bölünecek alanı (tümör yuvası, hidrojel, lezyon vb.)\n" +
              "ALAN anotasyonu olarak çizin ve SEÇİN, sonra betiği tekrar çalıştırın."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Alan seçili değil", msg)
    return
}

// ── 2) Bölge sayısı ─────────────────────────────────────────────────
int numRegions
if (isHeadless) {
    try { numRegions = Integer.parseInt(prefs.get('numRegions', String.valueOf(DEF_REGIONS))) } catch (Throwable t) { numRegions = DEF_REGIONS }
} else {
    def defN = prefs.get('numRegions', String.valueOf(DEF_REGIONS))
    def nTxt = Dialogs.showInputDialog("Eşit-alan merkez/çevre",
        "Kaç eşit-alan bölge? (2 = merkez + çevre; en çok ${MAX_REGIONS})", defN)
    if (nTxt == null) { println "İptal edildi."; return }
    try { numRegions = Integer.parseInt(nTxt.trim()) }
    catch (Throwable t) { Dialogs.showErrorMessage("Geçersiz sayı", "Bölge sayısı bir tam sayı olmalı (2–${MAX_REGIONS})."); return }
}
if (numRegions < 2) numRegions = 2
if (numRegions > MAX_REGIONS) numRegions = MAX_REGIONS
prefs.put('numRegions', String.valueOf(numRegions))
try { prefs.flush() } catch (Throwable ig) {}

// ── Yardımcı: alanı hedefe getiren içe-buffer mesafesini ikili arama ile bul ──
// area(geom.buffer(−d)) d ile monoton azalır; alanı targetPx'e getiren d'yi bul.
def bufferDistForArea = { org.locationtech.jts.geom.Geometry geom, double targetPx, double hiMaxPx ->
    double lo = 0.0d
    double hi = hiMaxPx
    for (int it2 = 0; it2 < 60; it2++) {
        double mid = (lo + hi) / 2.0d
        double a
        try {
            def b = geom.buffer(-mid)
            a = (b == null || b.isEmpty()) ? 0.0d : b.getArea()
        } catch (Throwable t) { a = 0.0d }
        if (a > targetPx) lo = mid else hi = mid
    }
    return (lo + hi) / 2.0d
}

def labelFor = { int idx, int n ->
    if (idx == 0) return "Çevre"
    if (idx == n - 1) return "Merkez"
    return "Ara ${idx}"
}
def classNameFor = { int idx, int n ->
    if (idx == 0) return CLS_PERIPH
    if (idx == n - 1) return CLS_CENTER
    return CLS_MIDDLE
}

// ── 3) Önceki eşit-alan bölgelerini temizle (idempotent) ────────────
def stale = QP.getAnnotationObjects().findAll { REGION_CLASSES.contains(it.getPathClass()?.toString()) }
if (!stale.isEmpty()) QP.removeObjects(stale, false)

// ── 4) Tespitleri (varsa) hazırlığa al ──────────────────────────────
def allDets = QP.getDetectionObjects().findAll { it.getROI() != null }
boolean countDets = !allDets.isEmpty() && allDets.size() <= DET_CAP

// ── 5) Her kaynağı böl ──────────────────────────────────────────────
def created = []
def rows = []          // [src, label, cls, areaUm2, pct, detCount]
def srcAccuracy = []   // [src, maxPctDiff]
int skippedSrc = 0

targets.eachWithIndex { t, si ->
    def roi = t.getROI()
    def plane = roi.getImagePlane()
    def orig
    try { orig = roi.getGeometry().buffer(0) } catch (Throwable tg) { orig = roi.getGeometry() }
    if (orig == null || orig.isEmpty()) { skippedSrc++; return }
    double totalPx = orig.getArea()
    if (!(totalPx > 0)) { skippedSrc++; return }
    // İçe-buffer inradius ≤ sqrt(A/π) olduğundan bu üst sınır geometriyi boşaltmayı garanti eder.
    double hiMaxPx = Math.sqrt(totalPx / Math.PI) * 1.05d

    def srcName = t.getName() ?: (t.getPathClass()?.toString()) ?: "alan ${si + 1}"
    def srcDets = countDets ? allDets.findAll { roi.contains(it.getROI().getCentroidX(), it.getROI().getCentroidY()) } : []

    // Sınırlar: bounds[k−1] = orig.buffer(−d_k), area = A·(n−k)/n, k=1..n−1
    def bounds = []
    boolean ok = true
    for (int k = 1; k <= numRegions - 1 && ok; k++) {
        double targetPx = totalPx * (numRegions - k) / (double) numRegions
        double d = bufferDistForArea(orig, targetPx, hiMaxPx)
        def b
        try { b = orig.buffer(-d).buffer(0) } catch (Throwable tb) { b = null }
        if (b == null || b.isEmpty()) { ok = false; break }
        bounds << b
    }
    if (!ok) { skippedSrc++; return }

    // Bölge geometrileri (dıştan içe): 0=çevre ... n−1=merkez
    def regionGeoms = []
    try {
        regionGeoms << orig.difference(bounds[0])                      // 0: çevre
        for (int i = 1; i <= numRegions - 2; i++) {
            regionGeoms << bounds[i - 1].difference(bounds[i])         // ara bölgeler
        }
        regionGeoms << bounds[numRegions - 2]                          // merkez
    } catch (Throwable tr) { skippedSrc++; return }

    // Bölgeleri anotasyona çevir + ölç
    def srcRegionPcts = []
    regionGeoms.eachWithIndex { g, idx ->
        if (g == null || g.isEmpty()) return
        def rROI = qupath.lib.roi.GeometryTools.geometryToROI(g, plane)
        if (rROI == null || rROI.isEmpty() || !rROI.isArea()) return
        String label = labelFor(idx, numRegions)
        String cls = classNameFor(idx, numRegions)
        def anno = qupath.lib.objects.PathObjects.createAnnotationObject(rROI, QP.getPathClass(cls))
        anno.setName(String.format(java.util.Locale.US, "%s · %s (%d/%d)", srcName, label, idx + 1, numRegions))
        double areaUm2 = g.getArea() * pw * ph
        double pct = 100.0d * g.getArea() / totalPx
        anno.measurements['Bölge alanı (µm²)']   = areaUm2
        anno.measurements["Toplam alanın %'si"]  = pct
        anno.measurements['Bölge sırası (dıştan)'] = (idx + 1) as double
        anno.measurements['Bölge sayısı']         = numRegions as double
        int dc = -1
        if (countDets) {
            dc = srcDets.count { rROI.contains(it.getROI().getCentroidX(), it.getROI().getCentroidY()) } as int
            double areaMm2 = areaUm2 / 1_000_000.0d
            anno.measurements['İçindeki tespit sayısı'] = dc as double
            anno.measurements['Tespit yoğunluğu (adet/mm²)'] = (areaMm2 > 0) ? (dc / areaMm2) : 0.0d
        }
        created << anno
        srcRegionPcts << pct
        rows << [src: srcName, label: label, cls: cls, area: areaUm2, pct: pct, det: dc]
    }
    if (srcRegionPcts.size() >= 2) {
        double maxDiff = (srcRegionPcts.max() as double) - (srcRegionPcts.min() as double)
        srcAccuracy << [src: srcName, diff: maxDiff]
    }
}

if (created.isEmpty()) {
    def msg = "Eşit-alan bölge üretilemedi (seçili alanların geometrisi bölme için uygun değil).\n" +
              "Bölge sayısını azaltmayı ya da daha kompakt/geniş bir alan seçmeyi deneyin."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Bölge yok", msg)
    return
}

QP.addObjects(created)
QP.fireHierarchyUpdate()

// ── 6) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "EŞİT-ALAN MERKEZ / ÇEVRE\n"
body << "════════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Kaynak alan sayısı : %,d  (atlanan: %,d)%n", targets.size() - skippedSrc, skippedSrc)
body << String.format(java.util.Locale.US, "Bölge / kaynak     : %d%n", numRegions)
body << String.format(java.util.Locale.US, "Üretilen bölge     : %,d%n", created.size())
body << (countDets ? "Tespit sayımı      : açık\n" : "Tespit sayımı      : kapalı (tespit yok veya >${String.format(java.util.Locale.US, "%,d", DET_CAP)})\n")
body << "──────────────────────────────────────────────\n"
def bySrc = rows.groupBy { it.src }
bySrc.each { src, rs ->
    body << "▸ ${src.length() > 40 ? src.substring(0, 39) + "…" : src}\n"
    rs.each { r ->
        if (r.det >= 0)
            body << String.format(java.util.Locale.US, "    %-10s %,12.0f µm²  %5.1f%%   %,d tespit%n", r.label, r.area as double, r.pct as double, r.det as int)
        else
            body << String.format(java.util.Locale.US, "    %-10s %,12.0f µm²  %5.1f%%%n", r.label, r.area as double, r.pct as double)
    }
}
if (!srcAccuracy.isEmpty()) {
    double worst = srcAccuracy.collect { it.diff as double }.max() as double
    body << "──────────────────────────────────────────────\n"
    body << String.format(java.util.Locale.US, "Eşit-alan doğruluğu: bölgeler-arası maks. fark %.2f%%%n", worst)
    body << "(DANEELpath makalesinde 48 WSI'da bu fark <%0,4)\n"
}
body << "\n"
body << "Bölgeler '${CLS_CENTER}' / '${CLS_PERIPH}'"
body << (numRegions > 2 ? " / '${CLS_MIDDLE}'" : "") << " sınıflarıyla eklendi.\n"
body << "İçlerinde hücre tespiti / piksel sınıflandırıcı çalıştırabilir,\n"
body << "merkez↔çevre farkını (ör. TIL/immün gradyan) karşılaştırabilirsiniz.\n\n"
body << "Yöntem: içe-buffer(−d) + ikili arama ile eşit-alan sınırı (JTS).\n"
body << "Esin: DANEELpath (Vieco-Martí 2026, Sci Rep; doi:10.1038/s41598-026-37134-5) —\n"
body << "makalede tarif edilen algoritmanın temiz-oda yeniden yazımı (GPL koddan aktarım yok).\n"
body << "Bu bir GEOMETRİ/ölçüm üretimidir — klinik skor, eşik veya yorum DEĞİL.\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı geometri/ölçüm üretir."

showResultWindow("Eşit-alan merkez/çevre", body.toString())
println String.format(java.util.Locale.US,
    "✓ %,d eşit-alan bölge üretildi (%d bölge/kaynak, %,d kaynak).",
    created.size(), numRegions, targets.size() - skippedSrc)
