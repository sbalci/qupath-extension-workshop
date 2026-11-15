/**
 * Yardımcı - Tümör tomurcuklanma kantifikasyonu (CK / ITBCC)
 * ---------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * Pan-sitokeratin (CK) DAB parlak alan lamlarında tümör tomurcuklarını
 * yarı otomatik sayar. Yöntem: gömülü bir DAB piksel sınıflandırıcısı CK⁺
 * bölgeleri ayırır → boyut penceresi (varsayılan 40–700 µm², ≈ ITBCC'nin
 * 1–4 hücrelik tomurcuğu) ile süzülür → kalan nesneler "Tumor bud" sınıfına
 * atanır → sayılır. ITBCC sahası 0.785 mm²'dir (1 mm Ø).
 *
 * QuPath KARŞILIĞI (GUI):
 *   Classify → Pixel classification (ck-tumor-dab) → Create detections →
 *   boyut süzme → yeniden sınıflandırma. Bu sihirbaz tüm adımları birleştirir.
 *
 * GİRDİ (ÖN KOŞUL):
 *   • Parlak-alan (brightfield) CK-DAB görüntüsü + piksel kalibrasyonu (µm/px)
 *   • Moda göre seçim:
 *       - Seçili alan       → bir alan anotasyonu seçin (Brush/Polygon)
 *       - İnvazif cephe     → bir çizgi anotasyonu seçin (bant otomatik üretilir)
 *       - Hotspot           → ≈0.785 mm² (1 mm Ø) bir alan anotasyonu seçin
 *       - TMA               → dearray edilmiş çekirdekler (TMA → Dearray)
 *
 * ÖNCEKİ SONUÇ: Bu sihirbazın önceki bir sonucu varsa ("Tumor bud" sınıflı tespitler; cephe/TMA
 *   kipinde ayrıca sihirbazın oluşturduğu bant / çekirdek bölgesi anotasyonları) "Tomurcukları say"
 *   ÖNCE SORAR: Ekle (varsayılan, hiçbir şey silinmez; yeni sonuç "(2)", "(3)" … adını alır) ·
 *   Değiştir (yalnız bu sihirbazın önceki nesneleri silinir; ikinci onay ister) · İptal (hiçbir şey
 *   çalışmaz). Seçtiğiniz anotasyonlardaki başka tespitlere (ör. hücre tespitleri) HİÇ dokunulmaz.
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
 * NOT: Gömülü sınıflandırıcının boya vektörleri ("Cam5.2") kaynak lama
 * özgüdür. Kendi CK antikoru/tarayıcınız için [Boya vektörleri sihirbazı] ile
 * vektörleri yeniden kestirip eşiği uyarlamanız gerekebilir.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */
import qupath.fx.dialogs.Dialogs
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
final String BUD_CLASS_NAME = 'Tumor bud'
final String TMA_BASE = 'TMA çekirdek bölgesi'
// "Önceki sonuç ne olsun?" kipleri — varsayılan HİÇBİR ŞEY SİLMEZ (ev kuralı: sonuç yazan her sihirbaz önce sorar).
final String MODE_ADD = 'add'
final String MODE_REPLACE = 'replace'

// ── Gömülü CK/DAB piksel sınıflandırıcısı (handson/classifiers/.../ck-tumor-dab.json) ──
// Aynı JSON proje klasöründe de bulunur; sihirbaz, projeye bağımlı olmadan
// kendi kopyasından çalışır. Boya vektörleri kaynak lama özgüdür (yukarıdaki NOT).
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

// Bu sihirbazın seti mi? Yalnız TAM ad ya da "ad (N)" — önek eşleşmesi YOK:
// kullanıcının "Tomurcuk bandı (invazif cephe) notlarım" diye adlandırdığı anotasyon süpürülmez.
def isOwnSetName = { String name, String base ->
    if (name == null) return false
    if (name == base) return true
    return name.startsWith(base + ' (') && name.endsWith(')') &&
        name.substring(base.length() + 2, name.length() - 1).isInteger() &&
        (name.substring(base.length() + 2, name.length() - 1) as int) >= 2
}
def ownSetIndex = { String name, String base ->
    return (name == base) ? 1 : (name.substring(base.length() + 2, name.length() - 1) as int)
}
// TMA çekirdek bölgesi adı çekirdek adını taşır ("TMA çekirdek bölgesi: A-1"; 2. set: "TMA çekirdek bölgesi (2): A-1").
// Eşleşme yalnız gridde bulunan çekirdek adlarıyla TAM eşleşmedir; dönüş: set numarası ya da 0 (bizim değil).
def tmaSetIndex = { String name, Collection coreNames, String base ->
    if (name == null) return 0
    for (String cn : coreNames) {
        if (name == base + ': ' + cn) return 1
        String pre = base + ' ('
        String suf = '): ' + cn
        if (name.startsWith(pre) && name.endsWith(suf) && name.length() > pre.length() + suf.length()) {
            String mid = name.substring(pre.length(), name.length() - suf.length())
            if (mid.isInteger() && (mid as int) >= 2) return (mid as int)
        }
    }
    return 0
}
def isBudDetection = { o -> o.isDetection() && o.getPathClass()?.getName() == BUD_CLASS_NAME }
def budIndexOf = { d ->
    String n = d.getName()
    return (n != null && isOwnSetName(n, BUD_CLASS_NAME)) ? ownSetIndex(n, BUD_CLASS_NAME) : 1
}
// Bu sihirbazın ÖNCEKİ sonucu: area/hotspot → seçili bölgelerin doğrudan çocuğu olan "Tumor bud" sınıflı
// tespitler; front → sihirbazın bant anotasyonları (+ içlerindeki "Tumor bud" tespitleri); tma → sihirbazın
// çekirdek bölgesi anotasyonları (+ içlerindeki "Tumor bud" tespitleri). Başka hiçbir nesne (hücre tespitleri,
// kullanıcının anotasyonları) önceki sonuç sayılmaz.
def earlierOf = { hierarchy, String mode, List selected ->
    def parents = []
    def buds = []
    def idx = []
    if (hierarchy != null) {
        if (mode == 'area' || mode == 'hotspot') {
            selected.findAll {
                it.isAnnotation() && it.getROI()?.isArea() && it.getPathClass()?.getName() != BUD_CLASS_NAME &&
                    !isOwnSetName(it.getName(), BAND_NAME)
            }.each { p -> p.getChildObjects().findAll { it.isDetection() }.findAll { isBudDetection(it) }.each { buds << it } }
            buds.each { idx << budIndexOf(it) }
        } else if (mode == 'front') {
            hierarchy.getAnnotationObjects().findAll { isOwnSetName(it.getName(), BAND_NAME) }.each { b ->
                parents << b; idx << ownSetIndex(b.getName(), BAND_NAME)
                b.getChildObjects().findAll { it.isDetection() }.findAll { isBudDetection(it) }.each { buds << it }
            }
        } else if (mode == 'tma') {
            def grid = hierarchy.getTMAGrid()
            if (grid != null) {
                def coreNames = grid.getTMACoreList().collect { (it.getName() ?: '?').toString() }
                hierarchy.getAnnotationObjects().each { a ->
                    int k = tmaSetIndex(a.getName(), coreNames, TMA_BASE)
                    if (k > 0) {
                        parents << a; idx << k
                        a.getChildObjects().findAll { it.isDetection() }.findAll { isBudDetection(it) }.each { buds << it }
                    }
                }
            }
        }
    }
    def names = (parents.isEmpty() ? buds.collect { it.getName() ?: BUD_CLASS_NAME } : parents.collect { it.getName() }).unique().sort()
    return [parents: parents, buds: buds, count: parents.size() + buds.size(),
            maxIdx: (idx.isEmpty() ? 0 : (idx.max() as int)), names: names]
}
// Değiştir kipi: yalnız earlierOf'un döndürdüğü nesneler; içlerindeki diğer nesneler KORUNUR (keepChildren = true).
def removeEarlier = { hierarchy, e ->
    if (!e.buds.isEmpty()) hierarchy.removeObjects(e.buds, true)
    if (!e.parents.isEmpty()) hierarchy.removeObjects(e.parents, true)
    return (int) e.count
}
// Önceki sonuç için kullanıcıya SOR (FX iş parçacığında, "Tomurcukları say" işlemi başlamadan ÖNCE çağrılır).
//   Dönüş: 'add' | 'replace' | null (İptal → hiçbir şey çalışmaz, hiçbir şey yazılmaz).
//   Önceki sonuç yoksa sormadan 'add' döner.
def askExisting = { hierarchy, String mode ->
    def e = earlierOf(hierarchy, mode, QP.getSelectedObjects() as List)
    if (e.count == 0) return MODE_ADD
    String optAdd = workshopText('script.5fcbb9588b11', 'Öncekine dokunma — yeni bir set olarak ekle (varsayılan)')
    String optRep = workshopText('script.1c7f212dcbd3', 'Öncekini değiştir (bu sihirbazın önceki nesneleri silinir)')
    def pick = Dialogs.showChoiceDialog(workshopText('script.4e8e0af236b8', 'Önceki tomurcuk sonucu'),
        String.format(java.util.Locale.US, workshopText('script.e09d59235832', 'Bu lamda bu sihirbazın önceki sonucundan kalan %,d nesne var: sınıfı "Tumor bud" olan tespitler ve (cephe ya da TMA kipinde) sihirbazın oluşturduğu bant / çekirdek bölgesi anotasyonları.\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni sonuç ayrı bir set olur ("Tumor bud (2)" gibi) ve seçili bölgelerdeki ölçüm adlarına "(2)" eklenir. İki set lamda birlikte durur; tomurcukları sayarken ikisini toplamayın.\n\n"Değiştir" seçilirse bu nesneler silinir; üzerlerinde ELLE yaptığınız düzeltmeler de gider. Seçtiğiniz anotasyonlara ve sınıfı "Tumor bud" olmayan nesnelere (ör. hücre tespitleri) dokunulmaz.\n\nİptal: hiçbir şey çalıştırılmaz, hiçbir şey yazılmaz.\n\nÖnceki tomurcuk sonucu ne olsun?'), e.count),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.2a445666bd81', 'Önceki tomurcuk sonucu silinecek'),
            String.format(java.util.Locale.US, workshopText('script.220920d88ad6', '%,d nesne silinecek: sınıfı "Tumor bud" olan tüm tespitler (elle sınıfladıklarınız dahil) ve cephe / TMA kipinde sihirbazın oluşturduğu bant / çekirdek bölgesi anotasyonları. Bunlarda elle yaptığınız düzeltmeler KAYBOLUR; anotasyonların içindeki diğer nesneler korunur.\n\nDevam edilsin mi?'), e.count))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}

// ── Bir parent anotasyon içinde tomurcukları tespit et + süz + say ─────────
def detectBudsIn = { parentAnn, classifier, double pw, double ph, double budMin, double budMax, boolean fillHoles, int setIdx ->
    QP.selectObjects(parentAnn)
    double holeArea = fillHoles ? Double.MAX_VALUE : 0.0
    // Bu parent altında ÖNCEDEN var olan tespitler (önceki sonuç, kullanıcının hücre tespitleri …) kaydedilir;
    // yalnız BU çalıştırmanın yeni ürettiği nesneler süzülür/silinir. (DELETE_EXISTING kullanılmaz: o seçenek
    // parent altındaki HER tespiti sorulmadan silerdi.)
    def before = parentAnn.getChildObjects().findAll { it.isDetection() } as Set
    // SPLIT → bitişik CK⁺ bölgeleri ayrı nesnelere böler.
    QP.createDetectionsFromPixelClassifier(classifier, budMin, holeArea, 'SPLIT')
    def children = parentAnn.getChildObjects().findAll { it.isDetection() }.findAll { !before.contains(it) }
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
            if (setIdx > 1) d.setName(BUD_CLASS_NAME + ' (' + setIdx + ')')
            d.measurements['Tomurcuk alanı (µm²)'] = areaUm2
            kept++
        }
    }
    // toRemove YALNIZ bu çalıştırmanın az önce ürettiği, henüz kullanıcıya sunulmamış geçici nesnelerdir.
    if (!toRemove.isEmpty()) QP.removeObjects(toRemove, true)
    return kept
}

// ── Ana akış: mod + parametrelerle çalıştır ────────────────────────────────
// Dönüş: [ok:true, text] | [ok:false, error:'NO_REGION'|'NO_LINE'|'NO_TMA'|<mesaj>]
def runBudding = { String mode, double budMin, double budMax, double frontMargin, double erodeUm, boolean fillHoles, String existMode = 'add' ->
    try {
        def imageData = QP.getCurrentImageData()
        if (imageData == null) return [ok:false, error:'Görüntü açık değil.']
        if (!imageData.isBrightfield())
            return [ok:false, error:'Bu araç parlak alan (brightfield) DAB görüntüsü gerektirir.\n' +
                                    'Görüntü tipini [Image → Set image type] ile Brightfield (H-DAB) yapın.']
        def cal = imageData.getServer().getPixelCalibration()
        double pw = cal.getPixelWidthMicrons(), ph = cal.getPixelHeightMicrons()
        if (!(pw > 0) || !(ph > 0))
            return [ok:false, error:'Piksel kalibrasyonu yok (µm/px). Önce [Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)] ile ayarlayın.']
        if (budMax <= budMin) return [ok:false, error:'Üst boyut eşiği alt eşikten büyük olmalı.']

        def classifier = buildClassifier()
        if (classifier == null) return [ok:false, error:'CK piksel sınıflandırıcısı yüklenemedi (gömülü JSON ayrıştırılamadı).']

        def hierarchy = imageData.getHierarchy()
        def parents = []          // her biri [ann:..., label:...]
        // Önceki sonuç: kip çağırandan gelir ('add' varsayılan = hiçbir şey silinmez). 'replace' yalnız
        // kullanıcı iki kez onayladıysa gelir ve yalnız earlierOf'un bulduğu nesneleri siler.
        def earlier = earlierOf(hierarchy, mode, QP.getSelectedObjects() as List)
        boolean doReplace = (existMode == MODE_REPLACE)
        int setIdx = (doReplace || earlier.count == 0) ? 1 : (earlier.maxIdx + 1)
        int removedN = 0
        String bandName = (setIdx > 1) ? (BAND_NAME + ' (' + setIdx + ')') : BAND_NAME
        String tmaBase = (setIdx > 1) ? (TMA_BASE + ' (' + setIdx + ')') : TMA_BASE
        boolean hotspotCheck = (mode == 'hotspot')
        double avgUmPerPx = (pw + ph) / 2.0

        if (mode == 'area' || mode == 'hotspot') {
            def sel = QP.getSelectedObjects().findAll {
                it.isAnnotation() && it.getROI()?.isArea() && it.getPathClass()?.getName() != 'Tumor bud' && !isOwnSetName(it.getName(), BAND_NAME)
            }
            if (sel.isEmpty()) return [ok:false, error:'NO_REGION']
            if (doReplace) removedN = removeEarlier(hierarchy, earlier)
            sel.eachWithIndex { a, i -> parents << [ann:a, label:(a.getName() ?: ('Bölge ' + (i + 1)))] }
        } else if (mode == 'front') {
            def lines = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI()?.isLine() }
            if (lines.isEmpty()) return [ok:false, error:'NO_LINE']
            if (doReplace) removedN = removeEarlier(hierarchy, earlier)
            double radPx = frontMargin / avgUmPerPx
            lines.eachWithIndex { ln, i ->
                def roi = ln.getROI()
                def band = roi.getGeometry().buffer(radPx)
                def bandRoi = GeometryTools.geometryToROI(band, roi.getImagePlane())
                def bandAnn = PathObjects.createAnnotationObject(bandRoi)
                bandAnn.setName(bandName)
                QP.addObject(bandAnn)
                parents << [ann:bandAnn, label:('İnvazif cephe bandı ' + (i + 1))]
            }
        } else if (mode == 'tma') {
            def grid = hierarchy.getTMAGrid()
            if (grid == null || grid.nCores() == 0) return [ok:false, error:'NO_TMA']
            if (doReplace) removedN = removeEarlier(hierarchy, earlier)
            double erodePx = erodeUm / avgUmPerPx
            grid.getTMACoreList().each { core ->
                if (core.isMissing()) return
                def roi = core.getROI()
                if (roi == null) return
                def eroded = roi.getGeometry().buffer(-erodePx)
                if (eroded == null || eroded.isEmpty()) eroded = roi.getGeometry()
                def pRoi = GeometryTools.geometryToROI(eroded, roi.getImagePlane())
                def pAnn = PathObjects.createAnnotationObject(pRoi)
                pAnn.setName(tmaBase + ': ' + (core.getName() ?: '?'))
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
        // Ekle kipinde ve önceki sonuç varken, SEÇİLİ (kullanıcının) bölgelerdeki ölçüm adları "(N)" son eki alır;
        // böylece önceki sonucun ölçümleri sessizce üzerine yazılmaz. Bu sihirbazın kendi oluşturduğu bant /
        // çekirdek bölgesi anotasyonları yenidir ve ad taşır.
        String msfx = ((mode == 'area' || mode == 'hotspot') && setIdx > 1) ? (' (' + setIdx + ')') : ''
        parents.each { p ->
            int n = detectBudsIn(p.ann, classifier, pw, ph, budMin, budMax, fillHoles, setIdx)
            double areaMm2 = p.ann.getROI().getArea() * pw * ph / 1_000_000.0
            p.ann.measurements['Tomurcuk sayısı' + msfx] = n as double
            p.ann.measurements['Bölge alanı (mm²)' + msfx] = areaMm2
            p.ann.measurements['Tomurcuk yoğunluğu (1/mm²)' + msfx] = areaMm2 > 0 ? (n / areaMm2) : Double.NaN
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
            // ±0,02 mm² ≈ %2,5 (1 mm çaplı dairenin çizim/poligonlama payı). Eski ±0,15 mm² (%19) payı
            // 0,64–0,94 mm² arasındaki her alanı "ITBCC sahası" sayıyordu; sayım alanla orantılı olduğundan
            // bu, ham sayıda %19'a varan sapmayı "doğrudan saha sayımı" diye sunmak demekti.
            double tol = 0.02
            boolean near = !perRegion.isEmpty() && perRegion.every { Math.abs(it.areaMm2 - ITBCC_FIELD_MM2) <= tol }
            sb.append('\n')
            sb.append(near
                ? 'Seçili alan ≈ ITBCC sahası (0.785 mm²); ham tomurcuk sayısı doğrudan saha sayımıdır.\n'
                : 'Not: seçili alan 0.785 mm² (ITBCC sahası) ile aynı değil; saha sayımı için\n     1 mm Ø (≈0.785 mm²) bir daire kullanın ([Objects → Annotations → Specify annotation]).\n')
        }

        if (!doReplace && earlier.count > 0) {
            String newLabel = (mode == 'front') ? bandName
                            : (mode == 'tma') ? (tmaBase + ': …')
                            : (BUD_CLASS_NAME + ' (' + setIdx + ')')
            sb.append('\n').append(String.format(java.util.Locale.US, workshopText('script.9a0b0897a5e7', 'DİKKAT — lamda önceki tomurcuk sonucu da duruyor: %s\nHiçbir şey silinmedi. Bu sayımın sonucu "%s" adını taşır. Eskisini kaldırmak için o nesneleri seçip silin ya da yeniden sayarken "Öncekini değiştir"i seçin. Dışa aktarım ve özetler iki sonucu da görür.'), earlier.names.take(6).join(', ') + (earlier.names.size() > 6 ? ' …' : ''), newLabel)).append('\n')
            if (msfx) sb.append(String.format(java.util.Locale.US, workshopText('script.1ae5e41c44be', 'Seçili bölgelerdeki ölçümler "%1$s" son ekiyle yazıldı (ör. "Tomurcuk sayısı%1$s"); önceki ölçümler değişmedi.'), msfx)).append('\n')
        } else if (removedN > 0) {
            sb.append('\n').append(String.format(java.util.Locale.US, workshopText('script.2f88f960c3b6', 'Önceki tomurcuk sonucu değiştirildi: %,d nesne silindi (anotasyonların içindeki diğer nesneler korundu).'), removedN)).append('\n')
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
    // Türkçe klavyede ondalık virgül ("40,5") yazılır → noktaya çevir; aksi hâlde değer sessizce
    // eski hâlinde kalırdı. Ayrıştırılamayan metin kırmızı çerçeveyle işaretlenir.
    tf.textProperty().addListener({ obs, o, n ->
        try {
            holder.set(Double.valueOf(Double.parseDouble((n ?: '').trim().replace(',', '.'))))
            tf.setStyle('')
        } catch (Throwable ignored) { tf.setStyle('-fx-border-color: #c0392b; -fx-border-width: 1.5;') }
    } as javafx.beans.value.ChangeListener)
    return tf
}

def startRun = {
    // Önceki sonuç varsa işlemden ÖNCE sor (FX iş parçacığı); İptal'de hiçbir şey çalışmaz, hiçbir şey yazılmaz.
    String existMode = askExisting(QP.getCurrentImageData()?.getHierarchy(), modeRef.get())
    if (existMode == null) return
    step.set('RUNNING'); render()
    def worker = new Thread({
        def res = runBudding(modeRef.get(),
                ((Number) budMinRef.get()).doubleValue(),
                ((Number) budMaxRef.get()).doubleValue(),
                ((Number) frontRef.get()).doubleValue(),
                ((Number) erodeRef.get()).doubleValue(),
                fillRef.get(), existMode)
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
        title.setText(workshopText('script.93c91460eae6', 'Tümör tomurcuklanma kantifikasyonu'))
        bodyLbl.setText(
            workshopText('script.6e0412933740', "Ön koşullar:\n") +
            "  ${s.image ? '✓' : '✗'}${workshopText('script.9ff446d4392c', ' Görüntü açık\n')}" +
            "  ${s.bf ? '✓' : '✗'}${workshopText('script.e9e13c68c531', ' Parlak-alan (brightfield) — CK-DAB beklenir\n')}" +
            "  ${s.calib ? '✓' : '✗'}${workshopText('script.3422216beab9', ' Piksel kalibrasyonu (µm/px)\n\n')}" +
            "${workshopText('script.5eec2726000d', 'Seçim: alan=')}${s.areas}${workshopText('script.254d0bd1384d', ', çizgi=')}${s.lines}${workshopText('script.b0690b5e023a', ', TMA çekirdek=')}${s.tma}\n\n" +
            workshopText('script.0bc8304cadae', "Mod seçin, sonra istediğiniz bölge(ler)i SEÇİP 'Tomurcukları say'."))

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
        grid.add(new javafx.scene.control.Label(workshopText('script.e161f8656a18', 'Alt boyut (µm²):')), 0, 0); grid.add(numField(budMinRef), 1, 0)
        grid.add(new javafx.scene.control.Label(workshopText('script.579416aa3a27', 'Üst boyut (µm²):')), 2, 0); grid.add(numField(budMaxRef), 3, 0)
        grid.add(new javafx.scene.control.Label(workshopText('script.c91e74f657f8', 'Cephe yarıçapı (µm):')), 0, 1); grid.add(numField(frontRef), 1, 1)
        grid.add(new javafx.scene.control.Label(workshopText('script.7c373bf008ff', 'TMA aşındırma (µm):')), 2, 1); grid.add(numField(erodeRef), 3, 1)
        def fillCb = new javafx.scene.control.CheckBox(workshopText('script.16fc5fbd1773', 'CK⁺ bölge içi delikleri doldur'))
        fillCb.setSelected(fillRef.get())
        fillCb.selectedProperty().addListener({ obs, o, n -> fillRef.set(n) } as javafx.beans.value.ChangeListener)
        content.getChildren().addAll(grid, fillCb)

        buttons.getChildren().addAll(
            navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }),
            navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }, workshopText('script.871ea66be61e', 'Ön koşul ve seçim durumunu yeniler')),
            navButton(workshopText('script.4d64e50dbed5', 'Tomurcukları say ▶'), { startRun() },
                workshopText('script.e41c7bf73262', 'Seçili bölge(ler)de CK⁺ tomurcukları tespit eder, boyutla süzer ve sayar'), 'PLAY'))
    } else if (cur == 'RUNNING') {
        title.setText(workshopText('script.a2558dd13558', 'Tomurcuklar sayılıyor…'))
        bodyLbl.setText(workshopText('script.072d9a72d988', "CK piksel sınıflandırıcısı çalışıyor, tespitler boyutla süzülüyor. Lütfen bekleyin…"))
        content.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        def r = runResult.get()
        if (r != null && r.ok) {
            title.setText(workshopText('script.a396d26ade0e', 'Tomurcuk sayımı ✅'))
            def ta = new javafx.scene.control.TextArea(r.text)
            ta.setEditable(false); ta.setWrapText(false)
            ta.setStyle('-fx-font-family: monospace;')
            ta.setPrefRowCount(14)
            content.getChildren().add(ta)
            buttons.getChildren().addAll(
                navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }),
                navButton(workshopText('script.a8bcca42d9ce', 'Kopyala'), {
                    def cc = new javafx.scene.input.ClipboardContent(); cc.putString(r.text)
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc)
                }, workshopText('script.26a9ce9d6020', 'Sonuç metnini panoya kopyalar')),
                navButton(workshopText('script.4570a7ae7bd9', '◀ Yeni sayım'), { step.set('CONFIG'); render() }))
        } else {
            def err = r?.error
            if (err == 'NO_REGION') {
                title.setText(workshopText('script.48e4eeeb7d93', 'Önce bir alan seçin'))
                bodyLbl.setText(workshopText('script.0307c8681acf', 'Bir alan anotasyonu çizip SEÇİN (Brush/Polygon), sonra tekrar deneyin.'))
            } else if (err == 'NO_LINE') {
                title.setText(workshopText('script.05346c905644', 'Önce bir çizgi seçin'))
                bodyLbl.setText(workshopText('script.433c610f2735', 'İnvazif cepheyi bir çizgi (V) anotasyonuyla çizip SEÇİN, sonra tekrar deneyin.'))
            } else if (err == 'NO_TMA') {
                title.setText(workshopText('script.0c1d78a7a2a6', 'TMA dearray gerekli'))
                bodyLbl.setText(workshopText('script.7cc002f26e32', 'Önce [TMA → Dearray] ile çekirdekleri oluşturun, sonra TMA modunu çalıştırın.'))
            } else {
                title.setText(workshopText('script.451ad8636fdc', 'Sayım yapılamadı'))
                bodyLbl.setText((err ?: workshopText('script.334b848286b9', 'Bilinmeyen hata.')).toString())
            }
            buttons.getChildren().addAll(
                navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }),
                navButton(workshopText('script.7dfd804d8334', '◀ Geri'), { step.set('CONFIG'); render() }))
        }
    }

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(content)
    def disclaimer = new javafx.scene.control.Label(
        workshopText('script.31f35d66136d', 'Yalnızca araştırma/eğitim amaçlı ölçüm üretir; ITBCC derecesi (Bd1/2/3) ya da klinik karar üretmez.'))
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
        stage.setTitle(workshopText('script.32d46968c0a3', 'Tümör tomurcuklanma kantifikasyonu (CK / ITBCC)'))
        stage.setAlwaysOnTop(true)
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.7fd583bc0c88', 'Sihirbaz açılamadı'), workshopText('script.9aac4912cb86', 'Bu araç başlatılamadı.\n\nYapabilecekleriniz:\n  1. Araç açık bir lam istiyorsa önce lamı açın.\n  2. QuPath\'i tamamen kapatıp yeniden açın ve tekrar deneyin.\n  3. Sorun sürerse View → Show log penceresinin ekran görüntüsünü atölye düzenleyicisine gönderin.\n\nTeknik ayrıntı: ') + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: workshopText('script.e136f4a3bb45', '(ileti yok)')))
    }
}

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
