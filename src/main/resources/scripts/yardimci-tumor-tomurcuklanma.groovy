/**
 * Yardımcı - Tümör tomurcuklanma kantifikasyonu (CK / ITBCC)
 * ---------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * Pan-sitokeratin (CK) DAB parlak-alan slaytlarında tümör tomurcuklarını
 * yarı-otomatik sayar. Yöntem: gömülü bir DAB piksel sınıflandırıcısı CK⁺
 * bölgeleri ayırır → boyut penceresi (varsayılan 40–700 µm², ≈ ITBCC'nin
 * 1–4 hücrelik tomurcuğu) ile süzülür → kalan nesneler "Tumor bud" sınıfına
 * atanır → sayılır. ITBCC sahası 0.785 mm²'dir (1 mm Ø).
 *
 * QuPath KARŞILIĞI (GUI):
 *   Classify → Pixel classification (ck-tumor-dab) → Create detections →
 *   boyut süzme → yeniden sınıflandırma. Bu sihirbaz tüm adımları birleştirir.
 *
 * GİRDİ (ÖNKOŞUL):
 *   • Parlak-alan (brightfield) CK-DAB görüntüsü + piksel kalibrasyonu (µm/px)
 *   • Moda göre seçim:
 *       - Seçili alan       → bir alan anotasyonu seçin (Brush/Polygon)
 *       - İnvazif cephe     → bir çizgi anotasyonu seçin (bant otomatik üretilir)
 *       - Hotspot           → ≈0.785 mm² (1 mm Ø) bir alan anotasyonu seçin
 *       - TMA               → dearray edilmiş çekirdekler (TMA → Dearray)
 *
 * ÇIKTI (yalnızca ölçüm):
 *   • "Tumor bud" tespitleri (her birinde tomurcuk alanı µm²)
 *   • Bölge başına: tomurcuk sayısı, alan (mm²), yoğunluk (tomurcuk/mm²)
 *   • Özet metin (kopyalanabilir). ITBCC DERECESİ (Bd1/Bd2/Bd3) ÜRETMEZ —
 *     derece klinik yorumdur, kapsam dışıdır.
 *
 * KÖKEN / ATIF:
 *   • Uyarlandığı kaynak: petebankhead/qupath-budding-scripts (MIT) —
 *     P. Bankhead, N. C. Fisher, M. B. Loughrey.
 *   • Fisher NC ve ark. "Development of a semi-automated method for tumour
 *     budding assessment in colorectal cancer and comparison with manual
 *     methods." Histopathology, 2022.
 *   • ITBCC 2016: Lugli A ve ark. Mod Pathol 2017;30(9):1299-1311.
 *     DOI: 10.1038/modpathol.2017.46
 *
 * NOT: Gömülü sınıflandırıcının boya vektörleri ("Cam5.2") kaynak slayda
 * özgüdür. Kendi CK antikoru/tarayıcınız için [Boya vektörleri sihirbazı] ile
 * vektörleri yeniden kestirip eşiği uyarlamanız gerekebilir.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObjects
import qupath.lib.io.GsonTools
import qupath.lib.classifiers.pixel.PixelClassifier
import qupath.lib.roi.GeometryTools
import qupath.lib.common.ColorTools

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── Atölye varsayılanları (sihirbazda düzenlenebilir alanlar) ──────────────
// WorkshopPrefs anahtarı KULLANILMAZ (lint prefs-drift kuralı); değerler tek
// pencerede düzenlenir. ITBCC/atölye kalibrasyonu: 40–700 µm², cephe 1000 µm,
// TMA aşındırma 30 µm. DAB eşiği gömülü sınıflandırıcıda 0.4'tür.
final double DEF_BUD_MIN   = 40.0     // µm² — alt boyut eşiği
final double DEF_BUD_MAX   = 700.0    // µm² — üst boyut eşiği (≈ >4 hücre adası elenir)
final double DEF_FRONT_UM  = 1000.0   // µm — invazif cephe çizgisi çevresi yarıçapı
final double DEF_ERODE_UM  = 30.0     // µm — TMA çekirdek kenarı aşındırma
final boolean DEF_FILL     = true     // CK⁺ bölge içi delikleri doldur
final double ITBCC_FIELD_MM2 = 0.785  // ITBCC sayım sahası (1 mm Ø)
final String BAND_NAME = 'Tomurcuk bandı (invazif cephe)'

// ── Gömülü CK/DAB piksel sınıflandırıcısı (handson/classifiers/.../ck-tumor-dab.json) ──
// Aynı JSON proje klasöründe de bulunur; sihirbaz, projeye bağımlı olmadan
// kendi kopyasından çalışır. Boya vektörleri kaynak slayda özgüdür (yukarıdaki NOT).
final String CK_CLASSIFIER_JSON = '''{
  "pixel_classifier_type": "OpenCVPixelClassifier",
  "metadata": {
    "inputPadding": 0,
    "inputResolution": {
      "pixelWidth": { "value": 1.86, "unit": "µm" },
      "pixelHeight": { "value": 1.86, "unit": "µm" },
      "zSpacing": { "value": 1.0, "unit": "z-slice" },
      "timeUnit": "SECONDS",
      "timepoints": []
    },
    "inputWidth": 512,
    "inputHeight": 512,
    "inputNumChannels": 3,
    "outputType": "CLASSIFICATION",
    "outputChannels": [],
    "classificationLabels": {
      "0": { "name": "Stroma", "colorRGB": -6895466 },
      "1": { "name": "Tumor", "colorRGB": -3670016 }
    }
  },
  "op": {
    "type": "data.op.channels",
    "colorTransforms": [
      {
        "stains": {
          "name": "Cam5.2 2d",
          "stain1": { "r": 0.7265802803872045, "g": 0.5886531203137013, "b": 0.35435660018884146, "name": "Hematoxylin", "isResidual": false },
          "stain2": { "r": 0.35231482908522016, "g": 0.5416559385935988, "b": 0.7632058080183483, "name": "DAB", "isResidual": false },
          "stain3": { "r": 0.48158779185809236, "g": -0.804165370370866, "b": 0.3484124794400088, "name": "Residual", "isResidual": true },
          "maxRed": 236.0, "maxGreen": 234.0, "maxBlue": 235.0
        },
        "stainNumber": 2
      }
    ],
    "op": {
      "type": "op.core.sequential",
      "ops": [
        { "type": "op.gaussian", "sigmaX": 1.0, "sigmaY": 1.0 },
        { "type": "op.constant", "thresholds": [ 0.4 ] }
      ]
    }
  }
}'''

def modeLabel = { String m ->
    switch (m) {
        case 'area':    return 'Seçili alan anotasyonu'
        case 'front':   return 'İnvazif cephe (çizgi → bant)'
        case 'hotspot': return 'Hotspot alanı (ITBCC 0.785 mm²)'
        case 'tma':     return 'TMA çekirdekleri'
        default:        return m
    }
}

// ── Durum hesaplama ────────────────────────────────────────────────────────
def computeState = { ->
    def st = [image:false, bf:false, calib:false, areas:0, lines:0, tma:0]
    def imageData = QP.getCurrentImageData()
    st.image = imageData != null
    if (imageData != null) {
        st.bf = imageData.isBrightfield()
        def cal = imageData.getServer()?.getPixelCalibration()
        st.calib = cal != null && cal.getAveragedPixelSizeMicrons() > 0
        def sel = QP.getSelectedObjects()
        st.areas = sel.count { it.isAnnotation() && it.getROI()?.isArea() && it.getPathClass()?.getName() != 'Tumor bud' }
        st.lines = sel.count { it.isAnnotation() && it.getROI()?.isLine() }
        def grid = imageData.getHierarchy().getTMAGrid()
        st.tma = (grid == null) ? 0 : grid.nCores()
    }
    return st
}

// ── CK sınıflandırıcısını gömülü JSON'dan kur ──────────────────────────────
def buildClassifier = { ->
    try { return GsonTools.getInstance().fromJson(CK_CLASSIFIER_JSON, PixelClassifier.class) }
    catch (Throwable t) { return null }
}

// ── Bir parent anotasyon içinde tomurcukları tespit et + süz + say ─────────
def detectBudsIn = { parentAnn, classifier, double pw, double ph, double budMin, double budMax, boolean fillHoles ->
    QP.selectObjects(parentAnn)
    double holeArea = fillHoles ? Double.MAX_VALUE : 0.0
    // SPLIT → bitişik CK⁺ bölgeleri ayrı nesnelere böler; DELETE_EXISTING → bu
    // parent altındaki önceki tespitleri temizler (tekrar çalıştırma idempotent).
    QP.createDetectionsFromPixelClassifier(classifier, budMin, holeArea, 'SPLIT', 'DELETE_EXISTING')
    def children = parentAnn.getChildObjects().findAll { it.isDetection() }
    def budClass = QP.getPathClass('Tumor bud')
    try { budClass.setColor((Integer) ColorTools.packRGB(150, 20, 20)) } catch (Throwable ignore) { }
    def toRemove = []
    int kept = 0
    children.each { d ->
        def cn = d.getPathClass()?.getName()
        double areaUm2 = d.getROI().getArea() * pw * ph
        // Tumor sınıfı dışındakiler (Stroma) ve boyut penceresi dışı kalanlar elenir.
        if (cn != 'Tumor' || areaUm2 < budMin || areaUm2 > budMax) {
            toRemove << d
        } else {
            d.setPathClass(budClass)
            d.measurements['Tomurcuk alanı (µm²)'] = areaUm2
            kept++
        }
    }
    if (!toRemove.isEmpty()) QP.removeObjects(toRemove, true)
    return kept
}

// ── Ana akış: mod + parametrelerle çalıştır ────────────────────────────────
// Dönüş: [ok:true, text] | [ok:false, error:'NO_REGION'|'NO_LINE'|'NO_TMA'|<mesaj>]
def runBudding = { String mode, double budMin, double budMax, double frontMargin, double erodeUm, boolean fillHoles ->
    try {
        def imageData = QP.getCurrentImageData()
        if (imageData == null) return [ok:false, error:'Görüntü açık değil.']
        if (!imageData.isBrightfield())
            return [ok:false, error:'Bu araç parlak-alan (brightfield) DAB görüntüsü gerektirir.\n' +
                                    'Görüntü tipini [Image → Set image type] ile Brightfield (H-DAB) yapın.']
        def cal = imageData.getServer().getPixelCalibration()
        double pw = cal.getPixelWidthMicrons(), ph = cal.getPixelHeightMicrons()
        if (!(pw > 0) || !(ph > 0))
            return [ok:false, error:'Piksel kalibrasyonu yok (µm/px). Önce [Yardımcılar → Kalibrasyon] ile ayarlayın.']
        if (budMax <= budMin) return [ok:false, error:'Üst boyut eşiği alt eşikten büyük olmalı.']

        def classifier = buildClassifier()
        if (classifier == null) return [ok:false, error:'CK piksel sınıflandırıcısı yüklenemedi (gömülü JSON ayrıştırılamadı).']

        def hierarchy = imageData.getHierarchy()
        def parents = []          // her biri [ann:..., label:...]
        boolean hotspotCheck = (mode == 'hotspot')
        double avgUmPerPx = (pw + ph) / 2.0

        if (mode == 'area' || mode == 'hotspot') {
            def sel = QP.getSelectedObjects().findAll {
                it.isAnnotation() && it.getROI()?.isArea() && it.getPathClass()?.getName() != 'Tumor bud' && it.getName() != BAND_NAME
            }
            if (sel.isEmpty()) return [ok:false, error:'NO_REGION']
            sel.eachWithIndex { a, i -> parents << [ann:a, label:(a.getName() ?: ('Bölge ' + (i + 1)))] }
        } else if (mode == 'front') {
            def lines = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI()?.isLine() }
            if (lines.isEmpty()) return [ok:false, error:'NO_LINE']
            def oldBands = QP.getAnnotationObjects().findAll { it.getName() == BAND_NAME }
            if (!oldBands.isEmpty()) QP.removeObjects(oldBands, true)
            double radPx = frontMargin / avgUmPerPx
            lines.eachWithIndex { ln, i ->
                def roi = ln.getROI()
                def band = roi.getGeometry().buffer(radPx)
                def bandRoi = GeometryTools.geometryToROI(band, roi.getImagePlane())
                def bandAnn = PathObjects.createAnnotationObject(bandRoi)
                bandAnn.setName(BAND_NAME)
                QP.addObject(bandAnn)
                parents << [ann:bandAnn, label:('İnvazif cephe bandı ' + (i + 1))]
            }
        } else if (mode == 'tma') {
            def grid = hierarchy.getTMAGrid()
            if (grid == null || grid.nCores() == 0) return [ok:false, error:'NO_TMA']
            double erodePx = erodeUm / avgUmPerPx
            grid.getTMACoreList().each { core ->
                if (core.isMissing()) return
                def roi = core.getROI()
                if (roi == null) return
                def eroded = roi.getGeometry().buffer(-erodePx)
                if (eroded == null || eroded.isEmpty()) eroded = roi.getGeometry()
                def pRoi = GeometryTools.geometryToROI(eroded, roi.getImagePlane())
                def pAnn = PathObjects.createAnnotationObject(pRoi)
                pAnn.setName('TMA çekirdek bölgesi: ' + (core.getName() ?: '?'))
                QP.addObject(pAnn)
                parents << [ann:pAnn, label:('Çekirdek ' + (core.getName() ?: '?'))]
            }
            if (parents.isEmpty()) return [ok:false, error:'Geçerli (missing olmayan) TMA çekirdeği bulunamadı.']
        } else {
            return [ok:false, error:'Bilinmeyen mod: ' + mode]
        }

        def perRegion = []
        int totalBuds = 0
        double totalAreaMm2 = 0.0
        parents.each { p ->
            int n = detectBudsIn(p.ann, classifier, pw, ph, budMin, budMax, fillHoles)
            double areaMm2 = p.ann.getROI().getArea() * pw * ph / 1_000_000.0
            p.ann.measurements['Tomurcuk sayısı'] = n as double
            p.ann.measurements['Bölge alanı (mm²)'] = areaMm2
            p.ann.measurements['Tomurcuk yoğunluğu (1/mm²)'] = areaMm2 > 0 ? (n / areaMm2) : Double.NaN
            p.ann.setLocked(true)
            perRegion << [label:p.label, n:n, areaMm2:areaMm2]
            totalBuds += n
            totalAreaMm2 += areaMm2
        }
        QP.fireHierarchyUpdate()

        double density = totalAreaMm2 > 0 ? (totalBuds / totalAreaMm2) : Double.NaN
        StringBuilder sb = new StringBuilder()
        sb.append('Mod                  : ').append(modeLabel(mode)).append('\n')
        sb.append('Bölge/çekirdek sayısı: ').append(parents.size()).append('\n')
        sb.append('Toplam tomurcuk      : ').append(totalBuds).append('\n')
        sb.append(String.format(java.util.Locale.US, 'Ölçülen alan         : %.4f mm²%n', totalAreaMm2))
        sb.append(Double.isFinite(density)
                ? String.format(java.util.Locale.US, 'Tomurcuk yoğunluğu   : %.1f tomurcuk/mm²%n', density)
                : 'Tomurcuk yoğunluğu   : hesaplanamadı\n')
        sb.append(String.format(java.util.Locale.US, 'Boyut penceresi      : %.0f–%.0f µm²%n', budMin, budMax))
        if (mode == 'front')
            sb.append(String.format(java.util.Locale.US, 'Cephe bandı yarıçapı : %.0f µm%n', frontMargin))
        if (mode == 'tma')
            sb.append(String.format(java.util.Locale.US, 'Çekirdek aşındırma   : %.0f µm%n', erodeUm))

        if (parents.size() > 1) {
            sb.append('\nBölge dökümü:\n')
            perRegion.each { r ->
                sb.append(String.format(java.util.Locale.US, '  • %-26s %d tomurcuk  (%.4f mm²)%n', r.label, r.n, r.areaMm2))
            }
        }

        if (hotspotCheck) {
            double tol = 0.15
            boolean near = !perRegion.isEmpty() && perRegion.every { Math.abs(it.areaMm2 - ITBCC_FIELD_MM2) <= tol }
            sb.append('\n')
            sb.append(near
                ? 'Seçili alan ≈ ITBCC sahası (0.785 mm²); ham tomurcuk sayısı doğrudan saha sayımıdır.\n'
                : 'Not: seçili alan 0.785 mm² (ITBCC sahası) ile aynı değil; saha sayımı için\n     1 mm Ø (≈0.785 mm²) bir daire kullanın ([Objects → Annotations → Specify annotation]).\n')
        }

        sb.append('\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir; ITBCC derecesi (Bd1/Bd2/Bd3) üretmez.')
        return [ok:true, text:sb.toString()]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '(mesaj yok)')]
    }
}

// ── Headless: GUI yoksa yalnız durum bildir ────────────────────────────────
if (isHeadless) {
    def s = computeState()
    println "Tümör tomurcuklanma (headless): image=${s.image} brightfield=${s.bf} calib=${s.calib} " +
            "seçiliAlan=${s.areas} seçiliÇizgi=${s.lines} tmaÇekirdek=${s.tma}"
    println "GUI olmadan sihirbaz etkileşimi yoktur. Bu aracı QuPath arayüzünde çalıştırın."
    return
}

// ── Tek pencere, adım adım render (alwaysOnTop 'arkada kalma' hatasını önler) ──
def stage = null
def step = new java.util.concurrent.atomic.AtomicReference('CONFIG')   // CONFIG | RUNNING | RESULT
def runResult = new java.util.concurrent.atomic.AtomicReference(null)
def modeRef = new java.util.concurrent.atomic.AtomicReference('area')
def budMinRef = new java.util.concurrent.atomic.AtomicReference(Double.valueOf(DEF_BUD_MIN))
def budMaxRef = new java.util.concurrent.atomic.AtomicReference(Double.valueOf(DEF_BUD_MAX))
def frontRef = new java.util.concurrent.atomic.AtomicReference(Double.valueOf(DEF_FRONT_UM))
def erodeRef = new java.util.concurrent.atomic.AtomicReference(Double.valueOf(DEF_ERODE_UM))
def fillRef = new java.util.concurrent.atomic.AtomicBoolean(DEF_FILL)
def render   // forward declaration

def faIcon = { String glyphName ->
    try {
        def node = org.controlsfx.glyphfont.Glyph.create('FontAwesome|' + glyphName)
        node.setStyle('-fx-text-fill: -fx-text-base-color;')
        return node
    } catch (Throwable ignored) { return null }
}
def navButton = { String text, Closure action, String tooltip = null, String icon = null ->
    def b = new javafx.scene.control.Button(text); b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    if (icon) { def g = faIcon(icon); if (g != null) b.setGraphic(g) }
    return b
}
def busyBar = { ->
    def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb
}
def fmtNum = { double v -> String.format(java.util.Locale.US, '%.0f', v) }
def numField = { holder ->
    def tf = new javafx.scene.control.TextField(fmtNum(((Number) holder.get()).doubleValue()))
    tf.setPrefColumnCount(6)
    tf.textProperty().addListener({ obs, o, n ->
        try { holder.set(Double.valueOf(Double.parseDouble(n.trim()))) } catch (Throwable ignored) { }
    } as javafx.beans.value.ChangeListener)
    return tf
}

def startRun = {
    step.set('RUNNING'); render()
    def worker = new Thread({
        def res = runBudding(modeRef.get(),
                ((Number) budMinRef.get()).doubleValue(),
                ((Number) budMaxRef.get()).doubleValue(),
                ((Number) frontRef.get()).doubleValue(),
                ((Number) erodeRef.get()).doubleValue(),
                fillRef.get())
        javafx.application.Platform.runLater { runResult.set(res); step.set('RESULT'); render() }
    }, 'AtolyeBuddingRun')
    worker.setDaemon(true); worker.start()
}

render = { ->
    if (stage != null) stage.setAlwaysOnTop(true)
    def s = computeState()
    def content = new javafx.scene.layout.VBox(10)
    content.setPadding(new javafx.geometry.Insets(14))
    def buttons = new javafx.scene.layout.HBox(8)
    buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def bodyLbl = new javafx.scene.control.Label()
    bodyLbl.setWrapText(true)
    content.getChildren().addAll(title, bodyLbl)

    def cur = step.get()
    if (cur == 'CONFIG') {
        title.setText('Tümör tomurcuklanma kantifikasyonu')
        bodyLbl.setText(
            "Önkoşullar:\n" +
            "  ${s.image ? '✓' : '✗'} Görüntü açık\n" +
            "  ${s.bf ? '✓' : '✗'} Parlak-alan (brightfield) — CK-DAB beklenir\n" +
            "  ${s.calib ? '✓' : '✗'} Piksel kalibrasyonu (µm/px)\n\n" +
            "Seçim: alan=${s.areas}, çizgi=${s.lines}, TMA çekirdek=${s.tma}\n\n" +
            "Mod seçin, sonra istediğiniz bölge(ler)i SEÇİP 'Tomurcukları say'.")

        def tg = new javafx.scene.control.ToggleGroup()
        def modesBox = new javafx.scene.layout.VBox(4)
        [['area','Seçili alan anotasyonu'],
         ['front','İnvazif cephe (çizgi → bant)'],
         ['hotspot','Hotspot alanı (ITBCC 0.785 mm²)'],
         ['tma','TMA çekirdekleri (dearray edilmiş)']].each { m ->
            def rb = new javafx.scene.control.RadioButton(m[1])
            rb.setToggleGroup(tg)
            if (m[0] == modeRef.get()) rb.setSelected(true)
            rb.selectedProperty().addListener({ obs, o, n -> if (n) modeRef.set(m[0]) } as javafx.beans.value.ChangeListener)
            modesBox.getChildren().add(rb)
        }
        content.getChildren().add(modesBox)

        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(6)
        grid.add(new javafx.scene.control.Label('Alt boyut (µm²):'), 0, 0); grid.add(numField(budMinRef), 1, 0)
        grid.add(new javafx.scene.control.Label('Üst boyut (µm²):'), 2, 0); grid.add(numField(budMaxRef), 3, 0)
        grid.add(new javafx.scene.control.Label('Cephe yarıçapı (µm):'), 0, 1); grid.add(numField(frontRef), 1, 1)
        grid.add(new javafx.scene.control.Label('TMA aşındırma (µm):'), 2, 1); grid.add(numField(erodeRef), 3, 1)
        def fillCb = new javafx.scene.control.CheckBox('CK⁺ bölge içi delikleri doldur')
        fillCb.setSelected(fillRef.get())
        fillCb.selectedProperty().addListener({ obs, o, n -> fillRef.set(n) } as javafx.beans.value.ChangeListener)
        content.getChildren().addAll(grid, fillCb)

        buttons.getChildren().addAll(
            navButton('Kapat', { stage.close() }),
            navButton('⟳ Yenile', { render() }, 'Önkoşul ve seçim durumunu yeniler'),
            navButton('Tomurcukları say ▶', { startRun() },
                'Seçili bölge(ler)de CK⁺ tomurcukları tespit eder, boyutla süzer ve sayar', 'PLAY'))
    } else if (cur == 'RUNNING') {
        title.setText('Tomurcuklar sayılıyor…')
        bodyLbl.setText("CK piksel sınıflandırıcısı çalışıyor, tespitler boyutla süzülüyor. Lütfen bekleyin…")
        content.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        def r = runResult.get()
        if (r != null && r.ok) {
            title.setText('Tomurcuk sayımı ✅')
            def ta = new javafx.scene.control.TextArea(r.text)
            ta.setEditable(false); ta.setWrapText(false)
            ta.setStyle('-fx-font-family: monospace;')
            ta.setPrefRowCount(14)
            content.getChildren().add(ta)
            buttons.getChildren().addAll(
                navButton('Kapat', { stage.close() }),
                navButton('Kopyala', {
                    def cc = new javafx.scene.input.ClipboardContent(); cc.putString(r.text)
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc)
                }, 'Sonuç metnini panoya kopyalar'),
                navButton('◀ Yeni sayım', { step.set('CONFIG'); render() }))
        } else {
            def err = r?.error
            if (err == 'NO_REGION') {
                title.setText('Önce bir alan seçin')
                bodyLbl.setText('Bir alan anotasyonu çizip SEÇİN (Brush/Polygon), sonra tekrar deneyin.')
            } else if (err == 'NO_LINE') {
                title.setText('Önce bir çizgi seçin')
                bodyLbl.setText('İnvazif cepheyi bir çizgi (V) anotasyonuyla çizip SEÇİN, sonra tekrar deneyin.')
            } else if (err == 'NO_TMA') {
                title.setText('TMA dearray gerekli')
                bodyLbl.setText('Önce [TMA → Dearray] ile çekirdekleri oluşturun, sonra TMA modunu çalıştırın.')
            } else {
                title.setText('Sayım yapılamadı')
                bodyLbl.setText((err ?: 'Bilinmeyen hata.').toString())
            }
            buttons.getChildren().addAll(
                navButton('Kapat', { stage.close() }),
                navButton('◀ Geri', { step.set('CONFIG'); render() }))
        }
    }

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(content)
    def disclaimer = new javafx.scene.control.Label(
        'Yalnızca araştırma/eğitim amaçlı ölçüm üretir; ITBCC derecesi (Bd1/2/3) ya da klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, buttons)
    bottom.setPadding(new javafx.geometry.Insets(10))
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 640, 560))
}

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Tümör tomurcuklanma kantifikasyonu (CK / ITBCC)')
        stage.setAlwaysOnTop(true)
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
