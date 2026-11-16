/**
 * Yardımcı - Peritümöral Bant (halka / kenar bandı)
 * --------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   SEÇİLİ alan anotasyonlarının (tümör, bez, lezyon...) çevresinde belirli
 *   genişlikte bir HALKA (bant) anotasyonu üretir. Yöntem: geometriyi buffer()
 *   ile genişletip/daraltıp difference() ile özgün şekli çıkararak yalnız
 *   kenar bandını bırakır (JTS geometri cebri). Üç yön:
 *     • Dışa  — buffer(+r).difference(orij)          → peritümöral (dış) halka
 *     • İçe   — orij.difference(buffer(−r))           → iç kenar bandı
 *     • Her iki yön — buffer(+r).difference(buffer(−r)) → sınırı saran invazif kenar bandı
 *
 * NEDEN: İnvazif kenar / peritümöral bölge, TIL skorlama (Stromal TIL / İmmün Skorlama eki), tümör-stroma
 *   (TSR Metodolojisi eki) ve immün bağlam analizlerinde standart bir ROI'dir. Bu bant, "Yapıya
 *   uzaklık" yardımcısının (mesafe ile filtreleme) tamamlayıcısıdır: mesafe
 *   ölçmek yerine bölgeyi KALICI BİR ANOTASYON SINIFI olarak üretir; böylece
 *   içinde hücre tespiti/piksel sınıflandırıcı çalıştırıp birinci sınıf bir ROI
 *   gibi kullanabilirsiniz.
 *
 * NE ÜRETİR (ve ne ÜRETMEZ):
 *   • Yalnız GEOMETRİ üretir (halka anotasyonu + bant alanı µm²). Hücre tespiti,
 *     skor, eşik, evre veya klinik yorum ÜRETMEZ.
 *   • Kaynak yapıyı (tümör vb.) sizin çizip SEÇMENİZ gerekir.
 *   • Yalnız ALAN anotasyonlarıyla çalışır (nokta/çizgi ROI atlanır).
 *
 * QuPath KARŞILIĞI (GUI):
 *   [Objects → Annotations → Expand annotations] anotasyonu genişletir ama özgün
 *   şekli çıkarıp yalnız halkayı bırakmaz. Bu betik buffer+difference ile temiz
 *   bir bant üretir; "Her iki yön" seçeneği sınırı saran bandı tek adımda verir.
 *
 * KULLANIM:
 *   1. Kalibre (µm/px) bir lam açın; kaynak yapıyı anotasyon olarak çizip SEÇİN
 *      (birden çok da olabilir)
 *   2. [Extensions → Atölye → Yardımcılar → Uzamsal analiz → Peritümöral bant (halka)]
 *      (ya da [Automate → Project scripts → bu betik])
 *   3. Bant genişliğini (µm) ve yönü seçin
 *
 * ÇIKTI:
 *   • Her kaynak için bir "Peritümöral bant" sınıfı halka anotasyonu
 *     ("Bant alanı (µm²)" + "Bant genişliği (µm)" ölçümleriyle)
 *   • Sonuç penceresinde özet tablo
 *   • Önceki bant varsa SORAR: "Ekle" (varsayılan) hiçbir şey silmez, yeni bantlar adlarının
 *     sonuna " (2)" gibi bir ek alır; "Değiştir" yalnız bu yardımcının bantlarını siler (tüm kaynaklar).
 *     Başsız kip: -a mevcut=ekle | -a mevcut=degistir (belirtilmezse durur, hiçbir şey yazmaz).
 *     (istediğiniz tüm kaynakları birlikte seçip tek seferde çalıştırın)
 *
 * YÖNTEM / KAYNAK:
 *   • JTS (Java Topology Suite) buffer + difference — QuPath içinde. Dikkat:
 *     JTS getArea() PİKSEL² döndürür; µm² için pw·ph ile çarpılır.
 *   • Teknik esin: JamesCrichton/QuPath-Scripting-Workshop (Exeter Üniversitesi, 2025),
 *     "Islet Ring" (buffer→difference) — depo açık kaynak lisansı taşımaz; bu betik
 *     teknik yeniden yazımdır (koddan aktarım yok).
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı geometri üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/peritumoral-bant')

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

// ── Sabitler ────────────────────────────────────────────────────────
String BAND_CLASS = "Peritümöral bant"
// Bant yönü: kod DAİMA sabit ASCII kimlikle karşılaştırır; ekranda görünen metin çevrilebilir.
// dirStored = kalıcı tercihte (prefs 'direction') her zaman yazılan ESKİ Türkçe değer — eski sürümlerle uyumlu.
// Okurken hem eski Türkçe değer hem kimlik kabul edilir. Nesne adı (dirShort) ve ölçüm adları değişmez.
String DIR_OUT    = "outer"
String DIR_IN     = "inner"
String DIR_BOTH   = "both"
def dirIds = [DIR_OUT, DIR_IN, DIR_BOTH]
def dirStored = [(DIR_OUT): "Dışa (peritümöral halka)", (DIR_IN): "İçe (iç kenar bandı)", (DIR_BOTH): "Her iki yön (invazif kenar bandı)"]
def dirLabels = [(DIR_OUT): workshopText('script.601b913f3794', 'Dışa (peritümöral halka)'), (DIR_IN): workshopText('script.362c9158d823', 'İçe (iç kenar bandı)'), (DIR_BOTH): workshopText('script.d74e64b953b1', 'Her iki yön (invazif kenar bandı)')]
def dirIdOf = { String v -> v == null ? null : (dirIds.contains(v) ? v : dirIds.find { dirStored[it] == v }) }
double defWidth = 50.0

// ── Önceki sonuç: SORULMADAN silinmez (ev kuralı) ────────────────────
// Önceki sonuç = bu yardımcının ürettiği bant: sınıf "Peritümöral bant" + bu yardımcının ad biçimi
// ("<kaynak> · dış bant 50µm", isteğe bağlı " (N)", N >= 2) + yardımcının yazdığı iki ölçüm.
// Yalnız sınıfa bakılmaz: kullanıcının aynı sınıfta elle çizdiği ya da adını değiştirdiği anotasyon süpürülmez.
// Varsayılan kip HİÇBİR ŞEY SİLMEZ: Ekle → yeni bant seti adlarının sonuna " (2)", " (3)" … alır.
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
def bandSetIndex = { String name ->
    if (name == null) return 0
    def m = java.util.regex.Pattern.compile('^.+ · (?:iç bant|kenar bandı|dış bant) \\d+µm(?: \\((\\d{1,6})\\))?$').matcher(name)
    if (!m.matches()) return 0
    if (m.group(1) == null) return 1
    int n = Integer.parseInt(m.group(1))
    return n >= 2 ? n : 0
}
def prevOwnBands = { hierarchy ->
    if (hierarchy == null) return []
    return hierarchy.getAnnotationObjects().findAll {
        it.getPathClass()?.toString() == BAND_CLASS && bandSetIndex(it.getName()) >= 1 &&
            it.getMeasurements().containsKey('Bant alanı (µm²)') && it.getMeasurements().containsKey('Bant genişliği (µm)')
    }
}
def nextBandRunIndex = { hierarchy ->
    def used = prevOwnBands(hierarchy).collect { bandSetIndex(it.getName()) }
    return used.isEmpty() ? 1 : ((used.max() as int) + 1)
}

// Başsız kip: -a mevcut=ekle | -a mevcut=degistir. Bilinmeyen değer HER durumda durdurur (fail closed).
String mevcutArg = null
if (isHeadless) {
    def scriptArgs = []
    try { if (binding.hasVariable('args')) { def a = binding.getVariable('args'); if (a != null) scriptArgs = (a as List) } } catch (Throwable ignore) {}
    def argMap = [:]
    scriptArgs.each { a -> def sa = a?.toString() ?: ''; int ia = sa.indexOf('='); if (ia > 0) argMap[sa.substring(0, ia).trim()] = sa.substring(ia + 1).trim() }
    if (argMap.mevcut != null) {
        mevcutArg = argMap.mevcut.toLowerCase(java.util.Locale.ROOT)
        if (!['ekle', 'degistir'].contains(mevcutArg)) {
            println String.format(java.util.Locale.US, workshopText('script.768b9abc88f3', 'HATA: mevcut yalnız \'ekle\' ya da \'degistir\' olabilir (verilen: %s). Hiçbir şey yazılmadı.'), argMap.mevcut); return
        }
    }
}

// Önceki bantlar için kullanıcıya SOR (bant üretiminden ÖNCE çağrılır).
//   Dönüş: 'add' | 'replace' | null (İptal → hiçbir şey üretilmez, hiçbir şey yazılmaz).
def askExisting = { hierarchy ->
    def prev = prevOwnBands(hierarchy)
    if (prev.isEmpty()) return MODE_ADD
    int nextIdx = nextBandRunIndex(hierarchy)
    String optAdd = workshopText('script.dc75389b188e', 'Öncekilere dokunma — yeni bantları ayrı bir set olarak ekle (varsayılan)')
    String optRep = workshopText('script.f7fed54b4e97', 'Öncekileri değiştir (bu yardımcının bantlarının tümü silinir)')
    def pick = Dialogs.showChoiceDialog(workshopText('script.15ecc8735fb2', 'Önceki peritümöral bantlar'),
        String.format(java.util.Locale.US, workshopText('script.1ef4a0b87c6e', 'Bu lamda bu yardımcının ürettiği %,d "%s" sınıflı bant anotasyonu zaten var (tüm kaynak yapılar için).\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni bantların adlarının sonuna "(%d)" eklenir ve ayrı bir set olurlar. Lamda iki set birlikte durur; bantlar örtüşebilir.\n\n"Değiştir" seçilirse önceki bantların TÜMÜ silinir — onlarda elle yaptığınız düzeltmeler de gider; bantların içindeki hücre tespitleri ve diğer nesneler korunur. Elle çizdiğiniz ya da adını değiştirdiğiniz anotasyonlara dokunulmaz.\n\nİptal: hiçbir şey üretilmez, hiçbir şey yazılmaz.\n\nÖnceki bantlar ne olsun?'), prev.size(), BAND_CLASS, nextIdx),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.20d04e185edd', 'Önceki bantlar silinecek'),
            String.format(java.util.Locale.US, workshopText('script.63aae63cc86b', '%,d "%s" sınıflı bant anotasyonu (tüm kaynak yapılar için) silinecek; bantlarda elle yaptığınız düzeltmeler KAYBOLUR. İçlerindeki nesneler korunur.\n\nDevam edilsin mi?'), prev.size(), BAND_CLASS))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}

// ── 1) Ön kontroller ────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.b0233b7e0986', "Önce kaynak yapıyı (tümör vb.) anotasyon olarak çizip seçin."))
    return
}
def cal = imageData.getServer().getPixelCalibration()
double pw = cal.getPixelWidthMicrons()
double ph = cal.getPixelHeightMicrons()
double avgUm = cal.getAveragedPixelSizeMicrons()
if (!(pw > 0) || !(ph > 0) || !(avgUm > 0)) {
    Dialogs.showErrorMessage(workshopText('script.a5a01874aff2', "Kalibrasyon yok"), workshopText('script.53c041a466c1', 'Lamda piksel boyutu (µm) tanımlı değil; bant µm cinsinden üretilemez.\n\nPiksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu).'))
    return
}

def targets = QP.getSelectedObjects().findAll {
    it.isAnnotation() && it.getROI()?.isArea() && it.getPathClass()?.toString() != BAND_CLASS
}
if (targets.isEmpty()) {
    def msg = workshopText('script.56788b39ff7d', 'Kaynak yapı seçili değil.\n\nEtrafında bant oluşturulacak yapıyı (tümör, bez, lezyon vb.)\nALAN anotasyonu olarak çizin ve SEÇİN, sonra betiği tekrar çalıştırın.')
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.584f0675ba8a', "Yapı seçili değil"), msg)
    return
}

// ── 2) Genişlik + yön ───────────────────────────────────────────────
double widthUm
String direction
if (isHeadless) {
    try { widthUm = Double.parseDouble(prefs.get('widthUm', String.valueOf(defWidth))) } catch (Throwable t) { widthUm = defWidth }
    direction = dirIdOf(prefs.get('direction', dirStored[DIR_OUT])) ?: DIR_OUT
} else {
    def wTxt = Dialogs.showInputDialog(workshopText('script.28c908cb2604', "Peritümöral bant"),
        workshopText('script.24a50c4af048', "Bant genişliği (µm):"), String.format(java.util.Locale.US, "%.0f", defWidth))
    if (wTxt == null) { println "İptal edildi."; return }
    try { widthUm = Double.parseDouble(wTxt.trim().replace(',', '.')) }
    catch (Throwable t) { Dialogs.showErrorMessage(workshopText('script.54cde46c5e98', "Geçersiz sayı"), workshopText('script.6342080b5aeb', "Bant genişliği bir sayı olmalı (µm).")); return }
    String defDir = dirIdOf(prefs.get('direction', dirStored[DIR_OUT])) ?: DIR_OUT
    def dirLabelList = dirIds.collect { dirLabels[it] }
    def pickedDirLabel = Dialogs.showChoiceDialog(workshopText('script.28c908cb2604', "Peritümöral bant"), workshopText('script.842cac15d901', "Bant yönü:"), dirLabelList, dirLabels[defDir])
    if (pickedDirLabel == null) { println "İptal edildi."; return }
    direction = dirIds[dirLabelList.indexOf(pickedDirLabel)]
}
if (!(widthUm > 0)) {
    Dialogs.showErrorMessage(workshopText('script.e6fd00663be3', "Geçersiz genişlik"), workshopText('script.139137752d51', "Bant genişliği pozitif olmalı (µm)."))
    return
}
prefs.put('widthUm', String.valueOf(widthUm)); prefs.put('direction', dirStored[direction])
try { prefs.flush() } catch (Throwable ig) {}

double rPx = widthUm / avgUm     // µm → piksel (buffer piksel koordinatında çalışır)
String dirShort = (direction == DIR_IN) ? "iç bant" : ((direction == DIR_BOTH) ? "kenar bandı" : "dış bant")

// ── 3) Önceki bant var mı? Üretimden ÖNCE sor ───────────────────────
def imgHierarchy = imageData.getHierarchy()
def prevOwn = prevOwnBands(imgHierarchy)
def prevNames = prevOwn.collect { it.getName() }.unique().sort()
String writeMode = MODE_ADD
if (!prevOwn.isEmpty()) {
    if (isHeadless) {
        if (mevcutArg == null) {
            println String.format(java.util.Locale.US, workshopText('script.91a4b2a08092', 'Bu lamda bu yardımcının ürettiği %,d "%s" sınıflı bant anotasyonu zaten var. Hiçbir şey yazılmadı. Yeni bir set eklemek için -a mevcut=ekle, önceki bantları değiştirmek için -a mevcut=degistir ile yeniden çalıştırın.'), prevOwn.size(), BAND_CLASS); return
        }
        writeMode = (mevcutArg == 'degistir') ? MODE_REPLACE : MODE_ADD
    } else {
        writeMode = askExisting(imgHierarchy)
        if (writeMode == null) { println "İptal edildi."; return }
    }
}
// Ekle kipinde yeni bantlar sıradaki set numarasını alır; Değiştir / ilk çalıştırmada ek yok.
int runIdx = (writeMode == MODE_ADD && !prevOwn.isEmpty()) ? nextBandRunIndex(imgHierarchy) : 1
String runSuffix = (runIdx > 1) ? (' (' + runIdx + ')') : ''

// ── 4) Her kaynak için halka üret ───────────────────────────────────
def bandClass = QP.getPathClass(BAND_CLASS)
def created = []
int skipped = 0
def rows = []
int trimmed = 0
def ringGeoms = []
def srvB = QP.getCurrentImageData().getServer()
def imageBoundsGeom = new org.locationtech.jts.geom.GeometryFactory().toGeometry(
    new org.locationtech.jts.geom.Envelope(0.0d, (double) srvB.getWidth(), 0.0d, (double) srvB.getHeight()))

targets.eachWithIndex { t, i ->
    def roi = t.getROI()
    def orig = roi.getGeometry()
    def plane = roi.getImagePlane()
    def ring = null
    try {
        if (direction == DIR_OUT) {
            ring = orig.buffer(rPx).difference(orig)
        } else if (direction == DIR_IN) {
            def inner = orig.buffer(-rPx)
            // İç tampon çöktüyse (bant, şekilden geniş) tüm şekli döndürme — atla.
            ring = (inner == null || inner.isEmpty()) ? null : orig.difference(inner)
        } else {
            def inner = orig.buffer(-rPx)
            def outer = orig.buffer(rPx)
            // İç tampon çöktüyse yalnız dış bandı ver (tüm tamponu değil).
            ring = (inner == null || inner.isEmpty()) ? outer.difference(orig) : outer.difference(inner)
        }
    } catch (Throwable t2) {
        try {
            def ob = orig.buffer(0)
            if (direction == DIR_OUT) {
                ring = ob.buffer(rPx).difference(ob)
            } else if (direction == DIR_IN) {
                def innerB = ob.buffer(-rPx)
                ring = (innerB == null || innerB.isEmpty()) ? null : ob.difference(innerB)
            } else {
                def innerB = ob.buffer(-rPx)
                def outerB = ob.buffer(rPx)
                ring = (innerB == null || innerB.isEmpty()) ? outerB.difference(ob) : outerB.difference(innerB)
            }
        } catch (Throwable t3) { ring = null }
    }
    if (ring == null || ring.isEmpty()) { skipped++; return }
    // Dış halka komşu kaynak yapıların İÇİNE taşmasın (komşu tümörün kendisi "peritümöral" sayılmaz)
    // ve görüntü sınırının dışına çıkmasın.
    if (direction != DIR_IN) {
        try {
            double before = ring.getArea()
            targets.eachWithIndex { o, j ->
                if (j == i) return
                def og = o.getROI().getGeometry()
                if (ring.getEnvelopeInternal().intersects(og.getEnvelopeInternal())) ring = ring.difference(og)
            }
            ring = ring.intersection(imageBoundsGeom)
            if (ring.getArea() < before - 1e-6) trimmed++
        } catch (Throwable t4) { /* kırpılamadıysa halka olduğu gibi kalır */ }
        if (ring == null || ring.isEmpty()) { skipped++; return }
    }
    ringGeoms << ring

    def srcName = t.getName() ?: (t.getPathClass()?.toString()) ?: "bölge ${i + 1}"
    def ringROI = qupath.lib.roi.GeometryTools.geometryToROI(ring, plane)
    if (ringROI == null || ringROI.isEmpty() || !ringROI.isArea()) { skipped++; return }

    def ringAnno = qupath.lib.objects.PathObjects.createAnnotationObject(ringROI, bandClass)
    ringAnno.setName(String.format(java.util.Locale.US, "%s · %s %.0fµm", srcName, dirShort, widthUm) + runSuffix)
    // JTS getArea() piksel² döner → µm² için pw·ph ile çarpılır (piksel→µm² birim tuzağı).
    double bandUm2 = ring.getArea() * pw * ph
    ringAnno.measurements['Bant alanı (µm²)']    = bandUm2
    ringAnno.measurements['Bant genişliği (µm)'] = widthUm
    created << ringAnno
    rows << [name: srcName, area: bandUm2]
}

if (created.isEmpty()) {
    def msg = workshopText('script.750f7da1978f', 'Bant üretilemedi (seçili yapıların geometrisi bant için uygun değil).\nGenişliği küçültmeyi ya da \'Dışa\' yönünü deneyin.')
    if (isHeadless) println msg else Dialogs.showWarningNotification(workshopText('script.cc7d90585cd5', "Bant yok"), msg)
    return
}

// Önceki bantlar: kip çağırandan gelir. Ekle → hiçbir şey silinmez; Değiştir → yalnız bu yardımcının
// bantları (yukarıdaki tanım) silinir, içlerindeki nesneler korunur. Üretim başarısız olsaydı
// buraya gelinmez: önceki bantlar yerinde kalır.
int removedPrev = 0
if (writeMode == MODE_REPLACE) {
    def prevNow = prevOwnBands(imgHierarchy)
    removedPrev = prevNow.size()
    if (removedPrev > 0) QP.removeObjects(prevNow, true)
}
QP.addObjects(created)
QP.fireHierarchyUpdate()

// ── 5) Sonucu sun ───────────────────────────────────────────────────
double totalBandUm2 = rows.collect { it.area as double }.sum() as double

def body = new StringBuilder()
body << workshopText('script.b8b65bc95f26', 'PERİTÜMÖRAL BANT (kenar halkası)\n')
body << "════════════════════════════════════════════\n\n"
body << String.format(java.util.Locale.US, workshopText('script.35213e37777b', 'Yön                : %s\n'), dirLabels[direction])
body << String.format(java.util.Locale.US, workshopText('script.9411ac235cf3', 'Bant genişliği     : %.0f µm%n'), widthUm)
body << String.format(java.util.Locale.US, workshopText('script.b43b676e595e', 'Üretilen bant      : %,d  (atlanan: %,d)%n'), created.size(), skipped)
body << String.format(java.util.Locale.US, workshopText('script.a1eb00b127ed', 'Toplam bant alanı  : %,.0f µm²%n'), totalBandUm2)
if (ringGeoms.size() > 1) {
    try {
        double unionUm2 = org.locationtech.jts.operation.union.UnaryUnionOp.union(ringGeoms).getArea() * pw * ph
        if (unionUm2 < totalBandUm2 * 0.999d)
            body << String.format(java.util.Locale.US, workshopText('script.c376a5735de4', '  Bantlar birbiriyle örtüşüyor: örtüşme bir kez sayılırsa %,.0f µm².%n'), unionUm2)
    } catch (Throwable ignore) {}
}
if (trimmed > 0)
    body << String.format(java.util.Locale.US, workshopText('script.7f5f277f0ddf', 'Kırpılan bant      : %,d  (komşu kaynak yapının içi ya da görüntü dışı çıkarıldı)%n'), trimmed)
if (runIdx > 1)
    body << String.format(java.util.Locale.US, workshopText('script.500fc52c8b85', 'Bant seti          : %d  (önceki set duruyor)'), runIdx) << "\n"
if (writeMode == MODE_ADD && !prevNames.isEmpty()) {
    body << "\n" << String.format(java.util.Locale.US, workshopText('script.c7b3b9d01938', 'DİKKAT — bu lamda önceki bant seti de duruyor (%,d anotasyon: %s).\nHiçbir şey silinmedi. Eskisini kaldırmak için o bantları seçip silin ya da yardımcıyı yeniden çalıştırırken "Öncekileri değiştir"i seçin. İki set örtüşebilir; ikisinin içinde de çalışan bir analiz aynı bölgeyi iki kez sayabilir.'), prevOwn.size(),
        prevNames.take(5).join(', ') + (prevNames.size() > 5 ? ', …' : '')) << "\n\n"
} else if (removedPrev > 0) {
    body << "\n" << String.format(java.util.Locale.US, workshopText('script.eaf4bd61041f', 'Önceki bantlar değiştirildi: %,d anotasyon silindi (içlerindeki nesneler korundu).'), removedPrev) << "\n\n"
}
body << "──────────────────────────────────────────────\n"
rows.each { r ->
    body << String.format(java.util.Locale.US, "  %-28s %,.0f µm²%n",
        (r.name.length() > 28 ? r.name.substring(0, 27) + "…" : r.name), r.area as double)
}
body << "\n"
body << String.format(java.util.Locale.US, workshopText('script.5a752a77b5a6', 'Bantlar \'%s\' sınıfıyla eklendi (kilitli değil).\n'), BAND_CLASS)
body << workshopText('script.4603d1491ba1', 'İçlerinde hücre tespiti / piksel sınıflandırıcı çalıştırabilir,\n')
body << workshopText('script.000277a0a699', '\'Yapıya uzaklık\' yardımcısıyla birlikte kullanabilirsiniz.\n\n')
body << workshopText('script.e3c2c8f536a4', 'Not: JTS geometri alanı piksel² döner; µm²\'ye pw·ph ile ölçeklenir.\n')
body << workshopText('script.2550cce16d41', 'Bu bir GEOMETRİ üretimidir — klinik skor, eşik veya yorum DEĞİL.\n')
body << workshopText('script.b76ab34dd1f8', '(Teknik esin: Crichton/Exeter 2025; Bankhead 2017)\n\n')
body << workshopText('script.4a3b575b62de', '⚠️ Yalnızca araştırma/eğitim amaçlı geometri üretir.')

showResultWindow(workshopText('script.28c908cb2604', 'Peritümöral bant'), body.toString())
println String.format(java.util.Locale.US,
    "✓ %,d peritümöral bant üretildi (%s, %.0f µm; toplam %,.0f µm²).",
    created.size(), dirShort, widthUm, totalBandUm2)

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
