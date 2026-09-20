/**
 * Yardımcı - CytoFormer hücre tipi sınıflama sihirbazı (H&E, 16 organ; seçili alanda)
 * ------------------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir; 0.7.0'da da çalışır).
 *
 * NE YAPAR:
 *   QuPath'te ÇİZİP SEÇTİĞİNİZ alan(lar)daki MEVCUT hücre tespitlerini (StarDist / InstanSeg /
 *   Cellpose / Watershed — herhangi biri) alır; her hücrenin (çekirdek varsa çekirdek ROI'sinin)
 *   merkezinden 56×56 µm'lik bir yama kırpıp 224×224'e ölçekler ve CytoFormer'ın (Yao, Li, Yu,
 *   Huang; Precision Pathology 2026) ViT-giant kodlayıcısına + seçtiğiniz ORGANIN doğrusal başlığına
 *   verir → her hücre o organın 23 küresel hücre tipinden BİRİNE atanır. Bu bir DEDEKTÖR DEĞİLDİR:
 *   önce bir hücre tespiti çalıştırın.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Seçili alandaki hücrelerin sınıf başına sayımı, yüzdesi, yoğunluğu (hücre/mm²) ve model
 *     olasılığı. Sınıflar modelin eğitim veri setinin (Xenium uzamsal transkriptom) kategorileridir;
 *     TANI DEĞİLDİR. Klinik derece/alt-tip/yorum ÜRETMEZ. Tahminleri görsel doğrulayın.
 *
 * KURULUM (sihirbaz adım adım yönlendirir):
 *   ① Python ortamı : Atölye Python ortam yöneticisi → "CytoFormer — H&E hücre tipi sınıflama"
 *   ② Kod           : Penn Academic Software License onayından SONRA sabit commit'ten (36b667e)
 *                     8 dosya indirilir (~30 KB); paketlenmez, kod bu depoya asla KOPYALANMAZ.
 *   ③ Ağırlık       : yerel klasörden (ör. J:\zhihuanglab\CytoFormer) ya da Hugging Face'ten
 *                     (KAPILI, kendi jetonunuzla) — 2.7 GB, SHA-256 bir kez doğrulanır.
 *
 * HEADLESS: -a organ=<id> [-a model=<klasör>] [-a device=auto|cuda|cpu] [-a batch=N] [-a chunk=N]
 *   [-a minprob=0..1] — kod ve lisans onayı GUI'de ② adımıyla ÖNCEDEN tamamlanmış OLMALIDIR (bu KİŞİYE
 *   özel java.util.prefs kaydı, paylaşılan makinede başka birinin indirmesi YETMEZ); HPC/toplu-iş için
 *   onay kutusu yerine [-a accept-license=<tam SHA, 36b667e40a440d0a8faea8296709aadfcffe6616>] geçilebilir
 *   (yalnız bu çalıştırma için — kalıcı değildir, lisans metnini yine de okumuş olmanız gerekir).
 *
 * LİSANS SINIRI (üç katman):
 *   • KOD: "PENN ACADEMIC SOFTWARE LICENSE AGREEMENT" — yalnız dahili araştırma/ticari olmayan
 *     kullanım, yeniden dağıtım YOK, kaynağı yeniden oluşturmaya çalışmak YASAK (§9). Bu atölye
 *     kodu ASLA kopyalamaz/paketlemez/'cytoformer' paketini İÇE AKTARMAZ; upstream scripts/infer.py
 *     AYRI bir süreç olarak DEĞİŞTİRİLMEDEN çalıştırılır. Kodu indirmek sizi bu lisansın ALICISI
 *     (RECIPIENT) yapar — ② adımındaki onay kutusu bunu açıkça belirtir.
 *   • AĞIRLIK: Hugging Face zhihuanglab/CytoFormer, CC BY-NC 4.0, KAPILI (otomatik onay) —
 *     araştırma amaçlı kullanım.
 *   • OMURGA SOYU: ViT-giant kodlayıcı UNI2-h'den (MahmoodLab) başlatılıp uçtan uca eğitilmiştir;
 *     bu bir hukuki görüş değildir — belgeler yalnız gerçekleri aktarır.
 *   Bu bir hukuki görüş değildir. Sorularınız için: zhi.huang@pennmedicine.upenn.edu
 *
 * ÇIKTI:
 *   • Her hücre: "<Türkçe hücre tipi> (CytoFormer)" sınıfı (düşük olasılıkta "Belirsiz (CytoFormer)")
 *     + "CytoFormer: olasılık" ölçümü (mevcut sınıf varsa DEĞİŞTİRİLİR).
 *   • Her seçili anotasyona: "CytoFormer: <tip> (n) / (%) / (/mm²)" ölçümleri.
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Yao J, Li S, Yu A, Huang Z. CytoFormer — Precision Pathology 1 (2026) 100006.
 *     doi:10.1016/j.prpath.2026.100006 (açık erişim, CC BY). arXiv:2608.16718.
 *   • Kod: https://github.com/zhihuanglab/CytoFormer (commit 36b667e, PENN Academic License)
 *   • Ağırlık: https://huggingface.co/zhihuanglab/CytoFormer (CC BY-NC 4.0, kapılı)
 *   • Bütün tutulan (held-out) test seti: doğruluk 0.846, makro-F1 0.779. En zayıf sınıflar:
 *     Kondrosit, Sinir hücresi, Miyeloid öncül hücre, Kemik hücresi, Mikroglia, Makrofaj.
 *
 * API: server.readRegion (RegionRequest, thread-safe) + BufferedImage/Graphics2D (bilinear,
 *      kalite ipuçları) → 224×224 PNG; ProcessBuilder (ayrı süreç) → TSV; PathClass ataması.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.regions.RegionRequest
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ───────────────────────────────────────────────────────────────
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def ENV_ID          = 'cytoformer'
def RUNNER_REL      = 'python/cytoformer/cytoformer_runner.py'
def CODE_SHA        = '36b667e40a440d0a8faea8296709aadfcffe6616'
def CODE_SHA_SHORT  = '36b667e40a44'
def WEIGHTS_SUBDIR  = 'weights-3e9691e45c3b'
def LICENSE_URL     = 'https://github.com/zhihuanglab/CytoFormer/blob/36b667e40a440d0a8faea8296709aadfcffe6616/LICENSE'
def CLASS_SUFFIX    = ' (CytoFormer)'
def MEAS_PREFIX     = 'CytoFormer: '
def UNCERTAIN_CLASS = 'Belirsiz (CytoFormer)'
double HALF_UM       = 28.0d   // 56 µm alan görüşü / 2
int    OUT_PX        = 224
int    MAX_EXPORT_THREADS = 8
int    DEFAULT_CHUNK = 20000
int    DEFAULT_BATCH = 64
double MPP_WARN_LOW  = 0.18d
double MPP_WARN_HIGH = 0.35d
int    CONFIRM_GPU_CELLS = 50000
int    CONFIRM_CPU_CELLS = 2000
double RATE_GPU_CELLS_S  = 80.0d
double RATE_CPU_CELLS_S  = 1.5d
double LOAD_SEC_PER_CHUNK = 25.0d
int    CONFIRM_MINUTES   = 30

// Küresel hücre tipleri (organ_celltype_map.json'daki 23 sınıfla BİREBİR, alfabetik sıra) —
// Türkçe ad + görüntüleme rengi. İlk yedisi Classpose ile PAYLAŞILAN renkler (aynı hücre türü).
def CLASSES = [
    [key:'Acinar',              tr:'Asiner hücre',              rgb:[180, 140, 0]],
    [key:'Bone_cell',           tr:'Kemik hücresi',             rgb:[160, 120, 80]],
    [key:'Cardiomyocyte',       tr:'Kardiyomiyosit',            rgb:[200, 50, 50]],
    [key:'Cholangiocyte',       tr:'Kolanjiyosit',              rgb:[0, 150, 150]],
    [key:'Chondrocyte',         tr:'Kondrosit',                 rgb:[130, 130, 200]],
    [key:'Ductal',              tr:'Duktal hücre',              rgb:[120, 80, 180]],
    [key:'Endothelium',         tr:'Endotel hücresi',           rgb:[0, 160, 255]],   // Classpose: Endothelial
    [key:'Epithelium',          tr:'Epitel hücresi',            rgb:[230, 40, 40]],   // Classpose: Epithelial
    [key:'Erythroid',           tr:'Eritroid hücre',            rgb:[220, 20, 20]],
    [key:'Hepatocyte',          tr:'Hepatosit',                 rgb:[150, 100, 50]],
    [key:'Islet',               tr:'Adacık hücresi',            rgb:[255, 180, 0]],
    [key:'Lymphocyte',          tr:'Lenfosit',                  rgb:[40, 80, 230]],   // Classpose: Lymphocyte
    [key:'Macrophage',          tr:'Makrofaj',                  rgb:[255, 120, 0]],   // Classpose: Macrophage
    [key:'Microglia',           tr:'Mikroglia',                 rgb:[180, 60, 180]],
    [key:'Myeloid_progenitor',  tr:'Miyeloid öncül hücre',      rgb:[140, 180, 60]],
    [key:'Nerve',               tr:'Sinir hücresi',             rgb:[255, 220, 80]],
    [key:'Neutrophil',          tr:'Nötrofil',                  rgb:[0, 200, 0]],     // Classpose: Neutrophil
    [key:'Plasma_cell',         tr:'Plazma hücresi',            rgb:[0, 120, 60]],    // Classpose: Plasma cell
    [key:'Renal_tubule',        tr:'Renal tübül hücresi',       rgb:[100, 180, 220]],
    [key:'Squamous_epithelium', tr:'Skuamöz epitel hücresi',    rgb:[210, 90, 140]],
    [key:'Stroma',              tr:'Stromal hücre',             rgb:[200, 200, 60]],  // Classpose: Stroma
    [key:'melanocytic',         tr:'Melanositik hücre',         rgb:[80, 40, 120]],
    [key:'tumor',               tr:'Tümör hücresi',             rgb:[200, 0, 120]],   // Classpose: Tumor
]
def classByKey = [:]; CLASSES.each { classByKey[it.key] = it }
def trOf = { String key -> (classByKey[key]?.tr) ?: key }

// 16 organ: id → Türkçe ad + o organın desteklediği hücre tipleri (organ_celltype_map.json ile
// İÇERİK olarak birebir — bkz. tasarım belgesi "Pinned upstream constants").
def ORGANS = [
    [id:'bone',        tr:'Kemik',      types:['Bone_cell','Endothelium','Lymphocyte','Stroma']],
    [id:'bone_marrow', tr:'Kemik iliği',types:['Endothelium','Erythroid','Myeloid_progenitor','Neutrophil','Stroma']],
    [id:'brain',       tr:'Beyin',      types:['Endothelium','Microglia','tumor']],
    [id:'breast',      tr:'Meme',       types:['Endothelium','Epithelium','Lymphocyte','Macrophage','Plasma_cell','Stroma','tumor']],
    [id:'cervix',      tr:'Serviks',    types:['Endothelium','Epithelium','Lymphocyte','Macrophage','Plasma_cell','Stroma','tumor']],
    [id:'colon',       tr:'Kolon',      types:['Endothelium','Epithelium','Lymphocyte','Macrophage','Plasma_cell','Stroma','tumor']],
    [id:'heart',       tr:'Kalp',       types:['Cardiomyocyte','Endothelium','Macrophage','Stroma']],
    [id:'kidney',      tr:'Böbrek',     types:['Endothelium','Lymphocyte','Macrophage','Plasma_cell','Renal_tubule','Stroma','tumor']],
    [id:'liver',       tr:'Karaciğer',  types:['Cholangiocyte','Endothelium','Hepatocyte','Lymphocyte','Macrophage','Stroma','tumor']],
    [id:'lung',        tr:'Akciğer',    types:['Chondrocyte','Endothelium','Epithelium','Lymphocyte','Macrophage','Plasma_cell','Stroma','tumor']],
    [id:'lymph_node',  tr:'Lenf nodu',  types:['Endothelium','Lymphocyte','Macrophage','Plasma_cell','Squamous_epithelium','Stroma','tumor']],
    [id:'ovary',       tr:'Over',       types:['Endothelium','Epithelium','Lymphocyte','Macrophage','Plasma_cell','Stroma','tumor']],
    [id:'pancreas',    tr:'Pankreas',   types:['Acinar','Ductal','Endothelium','Islet','Lymphocyte','Macrophage','Plasma_cell','Stroma','tumor']],
    [id:'prostate',    tr:'Prostat',    types:['Endothelium','Epithelium','Lymphocyte','Macrophage','Nerve','Stroma','tumor']],
    [id:'skin',        tr:'Deri',       types:['Endothelium','Epithelium','Lymphocyte','Macrophage','Plasma_cell','Stroma','melanocytic','tumor']],
    [id:'tonsil',      tr:'Tonsil',     types:['Endothelium','Epithelium','Lymphocyte','Macrophage','Plasma_cell','Stroma']],
]
def organById = [:]; ORGANS.each { organById[it.id] = it }
def organTrOf = { String id -> (organById[id]?.tr) ?: id }
// 7 sınıflı "karsinom" başlıkları (tumor·Stroma·Epithelium·Lymphocyte·Endothelium·Macrophage·Plasma_cell) —
// organ listesinde OLMAYAN bir doku için en yakın vekil seçilirken önerilir (Ek: kaynak spesifikasyonu D6).
def CARCINOMA_ORGANS = ['colon', 'breast', 'ovary', 'cervix']

// ── Kalıcı yapılandırma: java.util.prefs ─────────────────────────────────────
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/cytoformer')
def PREF_PYTHON  = 'python'
def PREF_RUNNER  = 'runner'
def PREF_WORK    = 'workDir'
def PREF_DEVICE  = 'device'
def PREF_ORGAN   = 'organ'
def PREF_SOURCE  = 'modelSource'   // 'folder' | 'download'
def PREF_FOLDER  = 'modelFolder'
def PREF_BATCH   = 'batch'
def PREF_CHUNK   = 'chunk'
def PREF_MINPROB = 'minProb'
def PREF_LICENSE = 'licenseAccepted'
def putPref = { String k, String v -> prefs.put(k, v ?: ''); try { prefs.flush() } catch (Throwable ignore) {} }

// ── Atölye veri kökü (env yöneticisiyle PAYLAŞILAN) + önbellek yönlendirme ──
def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
def cytoformerCacheDir = { -> new File(new File(atolyeDataRoot(), 'cache'), 'cytoformer') }
def codeDir    = { -> new File(cytoformerCacheDir(), 'code-' + CODE_SHA_SHORT) }
def codeMarker = { -> new File(codeDir(), '.atolye-ok.json') }
def defaultWeightsDir = { -> new File(cytoformerCacheDir(), WEIGHTS_SUBDIR) }
// HF_HOME: huggingface_hub önbelleği; TORCH_HOME: ViT ağırlık önbelleği; CYTOFORMER_CACHE: köprünün
// kod/ağırlık önbelleği kökü (runner --dest/--model-dir VERİLMEDİĞİNDE bunu kullanır).
def applyCacheEnv = { pb, Map extra = [:] ->
    try {
        def cache = new File(atolyeDataRoot(), 'cache'); cache.mkdirs()
        def env = pb.environment()
        env.put('HF_HOME', new File(cache, 'huggingface').getAbsolutePath())
        env.put('TORCH_HOME', new File(cache, 'torch').getAbsolutePath())
        env.put('TIATOOLBOX_HOME', new File(cache, 'tiatoolbox').getAbsolutePath())
        env.put('CYTOFORMER_CACHE', cytoformerCacheDir().getAbsolutePath())
        extra.each { k, v -> if (v != null) env.put(k.toString(), v.toString()) }
    } catch (Throwable ignore) {}
}

// ── Otomatik tespit: cytoformer ortamı + cytoformer_runner.py köprüsü ────────
def detectPython = { ->
    try {
        def rec = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('py.' + ENV_ID, '')
        if (rec?.trim() && new File(rec.trim()).isFile()) return rec.trim()
    } catch (Throwable ignore) {}
    def at = new File(new File(atolyeDataRoot(), 'runtimes'), ENV_ID + '/.venv')
    def aw = new File(at, 'Scripts/python.exe'); def an = new File(at, 'bin/python')
    if (aw.isFile()) return aw.getAbsolutePath()
    if (an.isFile()) return an.getAbsolutePath()
    return ''
}
def detectRunner = { ->
    def project = QP.getProject()
    def roots = []
    if (project != null && project.getPath() != null) {
        def handson = project.getPath().getParent().toFile()
        roots << handson
        if (handson.getParentFile() != null) roots << new File(handson.getParentFile(), 'handson')
    }
    for (r in roots) { def f = new File(r, RUNNER_REL); if (f.isFile()) return f.getAbsolutePath() }
    return ''
}

// ── SHA-256 doğrulama önbelleği (2.7 GB dosya bir kez hesaplanır; yol+boyut+mtime damgası) ──
def verifyKeyOf   = { File f -> 'sha.' + Integer.toHexString(f.getAbsolutePath().hashCode()) }
def verifyStampOf = { File f -> f.getAbsolutePath() + '|' + f.length() + '|' + f.lastModified() }
def isVerified    = { File f -> f != null && f.isFile() && prefs.get(verifyKeyOf(f), '') == verifyStampOf(f) }
def markVerified  = { File f -> if (f != null && f.isFile()) putPref(verifyKeyOf(f), verifyStampOf(f)) }

// ── Yapılandırma ────────────────────────────────────────────────────────────
def loadConfig = { ->
    def src = prefs.get(PREF_SOURCE, 'download'); if (!['folder', 'download'].contains(src)) src = 'download'
    def dv  = prefs.get(PREF_DEVICE, 'auto'); if (!['auto', 'cuda', 'cpu'].contains(dv)) dv = 'auto'
    def og  = prefs.get(PREF_ORGAN, '')
    [ python: (prefs.get(PREF_PYTHON, '')?.trim() ?: detectPython()), runner: (prefs.get(PREF_RUNNER, '')?.trim() ?: detectRunner()),
      workDir: prefs.get(PREF_WORK, ''), device: dv, organ: og,
      source: src, folder: prefs.get(PREF_FOLDER, ''),
      batch: prefs.get(PREF_BATCH, String.valueOf(DEFAULT_BATCH)),
      chunk: prefs.get(PREF_CHUNK, String.valueOf(DEFAULT_CHUNK)),
      minProb: prefs.get(PREF_MINPROB, '0'),
      licenseAccepted: prefs.get(PREF_LICENSE, '') ]
}
def modelDirOf = { cfg -> cfg.source == 'folder' ? (cfg.folder?.trim() ? new File(cfg.folder.trim()) : null) : defaultWeightsDir() }
def ckptFileOf = { cfg -> def d = modelDirOf(cfg); d == null ? null : new File(d, 'checkpoint.pth') }
def mapFileOf  = { cfg -> def d = modelDirOf(cfg); d == null ? null : new File(d, 'organ_celltype_map.json') }
def setupStatus = { cfg ->
    def ckpt = ckptFileOf(cfg); def map = mapFileOf(cfg)
    [ pythonOk : cfg.python?.trim() ? new File(cfg.python).isFile() : false,
      runnerOk : cfg.runner?.trim() ? new File(cfg.runner).isFile() : false,
      licenseOk: cfg.licenseAccepted == CODE_SHA,
      codeOk   : codeMarker().isFile(),
      weightsPresent: ckpt != null && ckpt.isFile() && map != null && map.isFile(),
      weightsOk: ckpt != null && isVerified(ckpt) && map != null && map.isFile() ]
}
// licenseOk da ŞARTTIR: paylaşılan veri kökünde kodu BAŞKA bir hesap indirmiş olabilir (codeOk=true) —
// bu hesap Penn Academic Software License'ı kendisi onaylamadan çalıştırma ekranına geçilmez (headless ile aynı kural).
def setupComplete = { cfg -> def s = setupStatus(cfg); s.pythonOk && s.runnerOk && s.codeOk && s.licenseOk && s.weightsOk }
def parseIntOr    = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }
def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().trim().replace(',', '.')) } catch (Throwable t) { return d } }

def imageNameOf = { imageData -> (imageData.getServer().getMetadata().getName() ?: 'slide').replaceAll(/\.[^.\/\\]+$/, '') }
def safeName = { String s ->
    def original = s ?: 'slide'
    def cleaned = original.replaceAll(/[^A-Za-z0-9._-]+/, '_')
    return cleaned == original ? cleaned : (cleaned + '_' + Integer.toHexString(original.hashCode()))
}
// Varsayılan: atölye veri kökü altında (proje klasörü İÇİNDE DEĞİL — bir parça yamaları ~2,5 GB'a
// kadar çıkabilir; handson/ altına yazmak depoyu şişirir). Ayar (cfg.workDir) her zaman önceliklidir.
def resolveWorkDir = { cfg, imageData ->
    def name = safeName(imageNameOf(imageData))
    def wd = cfg.workDir?.trim()
    if (wd) return new File(new File(wd), name)
    return new File(new File(cytoformerCacheDir(), 'work'), name)
}
def pixelMicrons = { imageData ->
    try { def cal = imageData.getServer().getPixelCalibration(); if (cal != null && cal.hasPixelSizeMicrons()) return (cal.getPixelWidthMicrons() + cal.getPixelHeightMicrons()) / 2.0d } catch (Throwable ignore) {}
    return Double.NaN
}

// ── Girdi seçimi: hücre tespitleri + hedef alan anotasyonları ────────────────
// Etkileşimli: YALNIZ seçili alan anotasyonları (sessiz "tüm anotasyonlara" geri düşüş YOK —
// lint Check 11'in yasakladığı örüntü budur; boş seçimde çağıran taraf rehberlik gösterir).
def selectedTargets = { imageData ->
    def picked = imageData.getHierarchy().getSelectionModel().getSelectedObjects()
    return new ArrayList(picked.findAll { it.isAnnotation() && it.hasROI() && it.getROI().isArea() })
}
// Headless: seçim kavramı yok → tüm alan anotasyonları (spec: "no selection exists headless").
def allAreaTargets = { imageData -> new ArrayList(imageData.getHierarchy().getAnnotationObjects().findAll { it.hasROI() && it.getROI().isArea() }) }
def unionRoiOf = { List targets -> targets.size() == 1 ? targets[0].getROI() : qupath.lib.roi.RoiTools.union(targets.collect { it.getROI() }) }
def areaMm2Of  = { roi, double mpp -> roi.getArea() * mpp * mpp / 1_000_000.0d }
// Hücre merkezi: PathCellObject ise çekirdek ROI'sinin merkezi, yoksa nesnenin kendi ROI'si.
def centroidOf = { obj ->
    def roi = (obj instanceof qupath.lib.objects.PathCellObject && obj.getNucleusROI() != null) ? obj.getNucleusROI() : obj.getROI()
    return [roi.getCentroidX(), roi.getCentroidY()] as double[]
}
// Hedef alan(lar) içindeki hücre-benzeri (tile OLMAYAN) tespitler — Check 3: getChildObjects() DEĞİL,
// hiyerarşinin UZAMSAL İNDEKSİ (getAllDetectionsForRegion, 0.6.0'da mevcut) ile ROI'nin sınırlayıcı
// kutusuna ÖNCE daraltılır (tüm slaytın değil), SONRA aynı centroid+contains testi UYGULANIR — sonuç
// eski düz-tarama ile birebir aynıdır, yalnız tüm hiyerarşiyi taramaz (proto_export.groovy ile aynı örüntü).
def detectionsIn = { imageData, unionRoi ->
    def hierarchy = imageData.getHierarchy()
    def candidates
    try {
        candidates = hierarchy.getAllDetectionsForRegion(qupath.lib.regions.ImageRegion.createInstance(unionRoi))
    } catch (Throwable ignore) {
        // Dejenere/aşırı ince (alt-piksel) bir ROI ImageRegion'ın tamsayı sınırlarına yuvarlanırken
        // sorun çıkarabilir — doğruluktan ödün vermemek için düz taramaya (eski davranış) geri düş.
        candidates = hierarchy.getDetectionObjects()
    }
    return new ArrayList(candidates.findAll { d ->
        if (d.isTile() || !d.hasROI()) return false
        def c = centroidOf(d)
        return unionRoi.contains(c[0], c[1])
    })
}

// ── Süre kestirimi ────────────────────────────────────────────────────────────
def estimateSeconds = { int nCells, int nChunks, boolean gpu ->
    double rate = gpu ? RATE_GPU_CELLS_S : RATE_CPU_CELLS_S
    return nChunks * LOAD_SEC_PER_CHUNK + nCells / rate
}
def formatDuration = { double sec ->
    long s = Math.max(0L, Math.round(sec))
    long h = s.intdiv(3600L); long m = (s % 3600L).intdiv(60L); long ss = s % 60L
    return h > 0 ? String.format(java.util.Locale.US, '%d sa %02d dk', h, m) : String.format(java.util.Locale.US, '%d dk %02d sn', m, ss)
}

// ── Python süreci → satır akışı (zaman aşımı YOK: 2.7 GB indirme / uzun çalıştırma sürebilir) ──
def processRef = new java.util.concurrent.atomic.AtomicReference(null)
def cancelledRef = new java.util.concurrent.atomic.AtomicBoolean(false)
// Süreç AĞACINI sonlandır: köprü infer.py'yi alt süreç olarak başlatır, torch da işçi süreçler açabilir.
def killTree = { proc ->
    if (proc == null) return
    try { proc.descendants().forEach({ h -> try { h.destroyForcibly() } catch (Throwable ignore) {} } as java.util.function.Consumer) } catch (Throwable ignore) {}
    try { proc.destroyForcibly() } catch (Throwable ignore) {}
}
def runPython = { List cmd, Map extraEnv, Closure onLine ->
    def pb = new ProcessBuilder(cmd.collect { it.toString() }); pb.redirectErrorStream(true)
    applyCacheEnv(pb, extraEnv ?: [:])
    def proc
    try { proc = pb.start() } catch (Throwable e) { return [ok: false, exitCode: -1, error: 'Python başlatılamadı: ' + (e.getMessage() ?: e.getClass().getSimpleName()), lines: []] }
    processRef.set(proc)
    def lines = new ArrayList()
    try {
        def reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
        String line
        while ((line = reader.readLine()) != null) { lines.add(line); onLine(line); if (cancelledRef.get()) break }
        reader.close()
    } catch (Throwable ignore) {}
    if (cancelledRef.get()) { killTree(proc); return [ok: false, exitCode: -3, error: 'İptal edildi', lines: lines] }
    int code
    try { code = proc.waitFor() } catch (InterruptedException ie) { killTree(proc); return [ok: false, exitCode: -3, error: 'İptal edildi', lines: lines] }
    return [ok: (code == 0), exitCode: code, lines: lines]
}
def tailOf = { List lines, int n -> lines.size() <= n ? lines.join('\n') : lines.subList(lines.size() - n, lines.size()).join('\n') }
def exitCodeHint = { int code ->
    if (code == 2) return 'Yapılandırma eksik (Python ortamı / kod / model bulunamadı).'
    if (code == 3) return 'Geçersiz girdi (yama klasörü boş ya da beklenen sayı ile dışa aktarılan sayı uyuşmuyor).'
    if (code == 4) return 'Cihaz hatası (CUDA bulunamadı/uyumsuz) — cihazı CPU yapın ya da NVIDIA sürücüsünü denetleyin.'
    return null
}

// ── 224×224 yama dışa aktarımı (D2 geometri: proto_export.groovy ile AYNI, headless doğrulanmış) ──
// half = max(8, round(28/mpp)) taban piksel; pencere [cx-half, cx+half); görüntü sınırına kırpılır;
// d = max(1, pencere/224) ile OKUNUR; beyaz tuvale bilinear/kalite ipuçlarıyla çizilir → TAM 224×224.
def exportOne = { server, obj, int half, int win, double d, File outFile ->
    def c = centroidOf(obj)
    int cx = (int) Math.round(c[0]), cy = (int) Math.round(c[1])
    int x0 = cx - half, y0 = cy - half
    int ix0 = Math.max(0, x0), iy0 = Math.max(0, y0)
    int ix1 = Math.min(server.getWidth(), x0 + win), iy1 = Math.min(server.getHeight(), y0 + win)
    if (ix1 <= ix0 || iy1 <= iy0) return false
    def img
    try { img = server.readRegion(RegionRequest.createInstance(server.getPath(), d, ix0, iy0, ix1 - ix0, iy1 - iy0)) } catch (Throwable t) { return false }
    if (img == null) return false
    def out = new java.awt.image.BufferedImage(OUT_PX, OUT_PX, java.awt.image.BufferedImage.TYPE_INT_RGB)
    def g = out.createGraphics()
    try {
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
        g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, OUT_PX, OUT_PX)
        double s = OUT_PX / (double) win
        int dx = (int) Math.round((ix0 - x0) * s), dy = (int) Math.round((iy0 - y0) * s)
        int dw = (int) Math.round((ix1 - ix0) * s), dh = (int) Math.round((iy1 - iy0) * s)
        g.drawImage(img, dx, dy, dw, dh, null)
    } finally { g.dispose() }
    // Yarım yazılmış bir PNG diskte KALIRSA runner'ın klasördeki *.png sayımı --expect'i aşar
    // ("satır sayısı beklenenle uyuşmuyor" → tüm parça başarısız olur) — hatada dosyayı temizle.
    try { javax.imageio.ImageIO.write(out, 'PNG', outFile) } catch (Throwable t) { try { outFile.delete() } catch (Throwable ignore) {}; return false }
    return true
}
// Sabit iş parçacığı havuzu (≤8, daemon) ile PARALEL dışa aktarım — readRegion iş parçacığı güvenlidir.
// isCancelled: her Future.get() öncesi denetlenir — iptal edilirse BEKLEMEYEN görevler shutdownNow() ile
// düşürülür (çoktan çalışmakta olanlar tamamlanır) — "İptal et" 20.000 yamanın tümünü beklemeden döner.
def exportChunkParallel = { server, List subList, File patchDir, int half, int win, double d, Closure isCancelled = { -> false } ->
    patchDir.mkdirs()
    try { patchDir.listFiles()?.each { if (it.isFile() && it.getName().endsWith('.png')) it.delete() } } catch (Throwable ignore) {}
    int nThreads = Math.max(1, Math.min(MAX_EXPORT_THREADS, Runtime.getRuntime().availableProcessors()))
    def tf = { r -> def t = new Thread(r); t.setDaemon(true); return t } as java.util.concurrent.ThreadFactory
    def pool = java.util.concurrent.Executors.newFixedThreadPool(nThreads, tf)
    def okCount = new java.util.concurrent.atomic.AtomicInteger(0)
    def futures = new ArrayList()
    try {
        subList.eachWithIndex { obj, i ->
            def outFile = new File(patchDir, String.format(java.util.Locale.US, 'c%07d.png', i))
            futures << pool.submit({ if (exportOne(server, obj, half, win, d, outFile)) okCount.incrementAndGet() } as Runnable)
        }
        for (f in futures) {
            if (isCancelled()) { pool.shutdownNow(); break }
            try { f.get() } catch (Throwable ignore) {}
        }
    } finally {
        pool.shutdown()
        try { pool.awaitTermination(5, java.util.concurrent.TimeUnit.MINUTES) } catch (Throwable ignore) {}
    }
    return [exported: okCount.get(), skipped: subList.size() - okCount.get()]
}

// ── TSV ayrıştırma: cell_id (c%07d, ÇALIŞMA-YEREL indeks) → sınıf + olasılık ────────────────────
def parseClassifyTsv = { File tsv ->
    def out = [:]
    if (!tsv.isFile()) return out
    def lines = tsv.readLines('UTF-8')
    if (lines.isEmpty()) return out
    for (int i = 1; i < lines.size(); i++) {   // 0 = başlık: cell_id\tpred_celltype\tprob
        def parts = lines[i].split('\t')
        if (parts.length < 3) continue
        def id = parts[0].trim()
        if (!id.startsWith('c')) continue
        int idx
        try { idx = Integer.parseInt(id.substring(1)) } catch (Throwable t) { continue }
        double p = Double.NaN
        try { p = Double.parseDouble(parts[2].trim()) } catch (Throwable ignore) {}
        out[idx] = [key: parts[1].trim(), prob: p]
    }
    return out
}

// ── Sınıflama hattı (PLAIN — JavaFX'e DOKUNMAZ; hem etkileşimli işçi hem de headless bunu çağırır) ──
// appendLine(String), setPhase(String), isCancelled() -> boolean : arayan taraf runLater'a SARMALAR
// ya da (headless'ta) doğrudan çağırır. Dönüş: [ok, assignments:(globalIndex->[key,prob]),
// exported, skipped, chunks, error] — atama SADECE bellekte tutulur; QuPath nesnelerine YAZILMAZ
// (bkz. applyAssignments — çağıran taraf bunu bir kez uygular: TÜM parçalar bitince BAŞARIYLA, ya da
// sonraki bir parça BAŞARISIZ olursa o ana kadarki 'assignments' ile — İPTAL durumunda ise HİÇ, çünkü
// UI "İptal et mevcut parçayı durdurur; o ana kadar HİÇBİR ŞEY yazılmaz" der ve bu iki başarısızlık
// return'ü kasıtlı olarak 'assignments' TAŞIMAZ).
def runClassifyPipeline = { server, List allCells, double mpp, cfg, File modelDir, String organKey, File workDir,
                             int batchSize, int chunkSize,
                             Closure appendLine, Closure setPhase, Closure isCancelled ->
    int half = (int) Math.max(8, Math.round(HALF_UM / mpp))
    int win  = 2 * half
    double d = Math.max(1.0d, win / (double) OUT_PX)
    def patchDir = new File(workDir, 'patches'); patchDir.mkdirs()
    def outTsv   = new File(workDir, 'classify_out.tsv')
    int n = allCells.size()
    int nChunks = (int) Math.ceil(n / (double) chunkSize)
    def assignments = [:]   // globalIndex -> [key, prob]
    int totalExported = 0, totalSkippedExport = 0, totalNotReturned = 0
    for (int ci = 0; ci < nChunks; ci++) {
        if (isCancelled()) return [ok: false, error: 'İptal edildi', exitCode: -3]
        int off = ci * chunkSize
        int end = Math.min(n, off + chunkSize)
        def subList = allCells.subList(off, end)
        setPhase(String.format(java.util.Locale.US, 'Yamalar dışa aktarılıyor (parça %d/%d)…', ci + 1, nChunks))
        def exp = exportChunkParallel(server, subList, patchDir, half, win, d, isCancelled)
        totalExported += exp.exported; totalSkippedExport += exp.skipped
        appendLine(String.format(java.util.Locale.US, 'Parça %d/%d: %,d yama (%d atlanan) · %d×%d px, downsample %.3f',
            ci + 1, nChunks, exp.exported, exp.skipped, win, win, d))
        if (exp.exported == 0) continue
        if (isCancelled()) return [ok: false, error: 'İptal edildi', exitCode: -3]
        setPhase(String.format(java.util.Locale.US, 'CytoFormer çalışıyor (parça %d/%d)…', ci + 1, nChunks))
        def cmd = [cfg.python, cfg.runner, 'classify',
                   '--patches', patchDir.getAbsolutePath(), '--model-dir', modelDir.getAbsolutePath(),
                   '--organ', organKey, '--out', outTsv.getAbsolutePath(),
                   '--batch', String.valueOf(batchSize), '--device', (cfg.device ?: 'auto'),
                   '--expect', String.valueOf(exp.exported)]
        def r = runPython(cmd, [:], appendLine)
        if (!r.ok) {
            def hint = exitCodeHint(r.exitCode)
            // Önceki parçalar BAŞARIYLA sınıflandıysa 'assignments' burada TAŞINIR — çağıran taraf bu
            // kısmi sonucu uygulayabilir; tamamen çöpe atmak zaten tamamlanmış GPU işini kaybettirir.
            return [ok: false, error: 'Sınıflama başarısız (çıkış: ' + r.exitCode + ')' + (hint ? ('\n' + hint) : '') +
                (r.error ? ('\n' + r.error) : '') + '\n' + tailOf(r.lines, 30), exitCode: r.exitCode, assignments: assignments]
        }
        def rowsByLocal = parseClassifyTsv(outTsv)
        rowsByLocal.each { localIdx, v -> assignments[off + localIdx] = v }
        int notReturned = exp.exported - rowsByLocal.size()
        if (notReturned > 0) totalNotReturned += notReturned
    }
    // Tüm parçalar başarıyla bitti: patches/ artık gereksiz (bir parça kadarı ≤ ~2,5 GB) — sil; günlük ve
    // classify_out.tsv (outTsv, workDir'de — patches/ İÇİNDE DEĞİL) kalır (bkz. "Klasörü aç").
    try { patchDir.listFiles()?.each { if (it.isFile()) it.delete() }; patchDir.delete() } catch (Throwable ignore) {}
    return [ok: true, assignments: assignments, exported: totalExported, skippedExport: totalSkippedExport,
             notReturned: totalNotReturned, chunks: nChunks, half: half, win: win, downsample: d]
}

// ── Sonuçları QuPath nesnelerine yaz (TEK SEFERDE; çağıran taraf runLater/hierarchy-event/repaint sarmalar) ──
def applyAssignments = { List allCells, Map assignments, double minProb ->
    int nAssigned = 0, nUncertain = 0
    def perClassCount = [:]
    allCells.eachWithIndex { obj, i ->
        def a = assignments[i]
        if (a == null) return
        boolean uncertain = (minProb > 0.0d) && Double.isFinite(a.prob) && (a.prob < minProb)
        def clsName = uncertain ? UNCERTAIN_CLASS : (trOf(a.key) + CLASS_SUFFIX)
        def pc = QP.getPathClass(clsName)
        if (!uncertain) { def info = classByKey[a.key]; if (info != null) try { pc.setColor(qupath.lib.common.ColorTools.packRGB(info.rgb[0] as int, info.rgb[1] as int, info.rgb[2] as int)) } catch (Throwable ignore) {} }
        else { try { pc.setColor(qupath.lib.common.ColorTools.packRGB(130, 130, 130)) } catch (Throwable ignore) {} }
        obj.setPathClass(pc)
        if (Double.isFinite(a.prob)) obj.getMeasurements().put(MEAS_PREFIX + 'olasılık', a.prob)
        if (uncertain) nUncertain++
        def bucket = uncertain ? UNCERTAIN_CLASS : a.key
        perClassCount[bucket] = (perClassCount[bucket] ?: 0) + 1
        nAssigned++
    }
    return [assigned: nAssigned, uncertain: nUncertain, perClass: perClassCount]
}

// ── Seçili hedef anotasyonlara sayım/%/yoğunluk ölçümü (eski 'CytoFormer: ' anahtarları önce silinir) ──
def writeAnnotationMeasurements = { List targets, List allCells, Map assignments, double minProb, double mpp ->
    targets.each { ann ->
        def roi = ann.getROI()
        def pts = []
        allCells.eachWithIndex { obj, i ->
            def a = assignments[i]; if (a == null) return
            def c = centroidOf(obj)
            if (!roi.contains(c[0], c[1])) return
            boolean uncertain = (minProb > 0.0d) && Double.isFinite(a.prob) && (a.prob < minProb)
            pts << (uncertain ? UNCERTAIN_CLASS : a.key)
        }
        def ml = ann.getMeasurements()
        new ArrayList(ml.keySet()).findAll { it.startsWith(MEAS_PREFIX) }.each { ml.remove(it) }
        int tot = pts.size()
        double areaMm2 = areaMm2Of(roi, mpp)
        ml.put(MEAS_PREFIX + 'toplam (n)', tot as double)
        if (areaMm2 > 0) ml.put(MEAS_PREFIX + 'toplam (/mm²)', tot / areaMm2)
        def present = pts as LinkedHashSet
        (CLASSES.collect { it.key } + [UNCERTAIN_CLASS]).findAll { present.contains(it) }.each { k ->
            int cnt = pts.count { it == k } as int
            def label = (k == UNCERTAIN_CLASS) ? UNCERTAIN_CLASS : trOf(k)
            ml.put(MEAS_PREFIX + label + ' (n)', cnt as double)
            ml.put(MEAS_PREFIX + label + ' (%)', tot > 0 ? 100.0d * cnt / tot : Double.NaN)
            if (areaMm2 > 0) ml.put(MEAS_PREFIX + label + ' (/mm²)', cnt / areaMm2)
        }
    }
}

// ── Sonuç metni (yalnız sayım/yüzde/yoğunluk + modelin bilinen sınırları) ────────────────────────
def buildResultText = { imageData, cfg, String organKey, Map pipeline, Map applyStats, double areaMm2, long elapsedMs ->
    def sb = new StringBuilder()
    int tot = applyStats.assigned as int
    sb << "CYTOFORMER — SEÇİLİ ALANDA HÜCRE TİPLERİ\n"
    sb << "══════════════════════════════════════════\n\n"
    sb << "Slayt   : " << imageNameOf(imageData) << "\n"
    sb << "Organ   : " << organTrOf(organKey) << " (" << organKey << ")\n"
    sb << "Cihaz   : " << (cfg.device ?: 'auto') << "\n"
    if (Double.isFinite(areaMm2) && areaMm2 > 0) sb << String.format(java.util.Locale.US, "Alan    : %.3f mm²\n", areaMm2)
    sb << String.format(java.util.Locale.US, "Hücre   : %,d sınıflandı", tot)
    if (pipeline.skippedExport || pipeline.notReturned) sb << String.format(java.util.Locale.US, "  (%,d atlandı — dışa aktarım/eşleşme)", (pipeline.skippedExport ?: 0) + (pipeline.notReturned ?: 0))
    sb << "\n"
    sb << "Süre    : " << formatDuration(elapsedMs / 1000.0d) << "  (" << pipeline.chunks << " parça)\n\n"
    def organTypes = (organById[organKey]?.types ?: []) as List
    sb << String.format(java.util.Locale.US, "%-28s %8s %8s %12s\n", 'Hücre tipi', 'n', '%', 'hücre/mm²')
    organTypes.each { k ->
        int n = (applyStats.perClass[k] ?: 0) as int
        String pct = tot > 0 ? String.format(java.util.Locale.US, '%.1f', 100.0d * n / tot) : '—'
        String dm = (Double.isFinite(areaMm2) && areaMm2 > 0) ? String.format(java.util.Locale.US, '%.1f', n / areaMm2) : '—'
        sb << String.format(java.util.Locale.US, "%-28s %8d %8s %12s\n", trOf(k), n, pct, dm)
    }
    if (applyStats.uncertain > 0) {
        String pct = tot > 0 ? String.format(java.util.Locale.US, '%.1f', 100.0d * applyStats.uncertain / tot) : '—'
        sb << String.format(java.util.Locale.US, "%-28s %8d %8s %12s\n", UNCERTAIN_CLASS, applyStats.uncertain as int, pct, '—')
    }
    if (tot == 0) sb << "\n0 hücre sınıflandı — seçili alanda hücre tespiti bulunduğunu ve organ seçimini denetleyin.\n"
    sb << "\nHücreler '<tip> (CytoFormer)' sınıfına atandı (mevcut sınıf varsa değiştirildi); 'CytoFormer: olasılık'\n"
    sb << "ölçümü yazıldı; seçili anotasyonlara 'CytoFormer: <tip> (n) / (%) / (/mm²)' eklendi.\n\n"
    sb << "MODELİN BİLİNEN SINIRLARI (Yao ve ark. 2026, tutulan test seti):\n"
    sb << "  • Genel doğruluk 0.846, makro-F1 0.779. En zayıf sınıflar: Kondrosit, Sinir hücresi,\n"
    sb << "    Miyeloid öncül hücre, Kemik hücresi, Mikroglia, Makrofaj.\n"
    sb << "  • Mononükleer karışıklık (tutulan MEME kesiti): makrofajların %44,6'sı ve plazma hücrelerinin\n"
    sb << "    %63,5'i lenfosit olarak; epitel hücrelerinin %25,8'i tümör olarak öngörülmüştür.\n"
    sb << "  • Organ başlığı: tahminler YALNIZCA seçtiğiniz organın sınıf listesiyle sınırlıdır; başka bir\n"
    sb << "    organa ait doku için yakın bir vekil organ seçildiyse (ör. " << CARCINOMA_ORGANS.join('/') << ") bu organda DOĞRULANMAMIŞTIR.\n"
    sb << "Sınıflar modelin eğitim veri setinin (uzamsal transkriptom) kategorileridir; tanı değildir — görsel doğrulayın.\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

// ── Çalıştırma kapısı — hem READY ekranı hem startRun AYNI kuralı kullanır ───────────────────────
// targetsProvider: etkileşimlide selectedTargets, headless'ta allAreaTargets (spec: "no selection exists headless").
def readiness = { imageData, cfg, Closure targetsProvider ->
    def blocks = [], warns = []
    def st = setupStatus(cfg)
    if (!setupComplete(cfg)) blocks << 'Kurulum tamamlanmadı (① Python ortamı, ② kod indirme, ③ ağırlık doğrulama) — "◀ Kurulum".'
    def targets = targetsProvider(imageData)
    if (targets.isEmpty()) blocks << 'Bir alan çizip seçin (birden çok alan için Ctrl+tık), sonra "⟳ Yenile".'
    double mpp = pixelMicrons(imageData)
    if (!Double.isFinite(mpp) || mpp <= 0) blocks << 'Görüntü kalibre değil — CytoFormer 56 µm alan görüşü hesaplamak için piksel boyutu (µm/px) gerektirir.'
    else if (mpp > MPP_WARN_HIGH || mpp < MPP_WARN_LOW) warns << String.format(java.util.Locale.US, 'Piksel boyutu %.3f µm/px — eğitim aralığının (yaklaşık 0.18–0.35) dışında; sonuçlar daha az güvenilir olabilir.', mpp)
    if (!organById.containsKey(cfg.organ)) blocks << 'Bir organ seçin.'
    // RoiTools.union aynı düzlemde (z/t) OLMAYAN ROI'ler için IllegalArgumentException fırlatır — çoklu
    // seçim bir z-yığını/zaman noktası karışımıysa bunu burada YAKALA (diğer tüm ön koşul ihlalleri gibi
    // 'blocks' üzerinden akar), FX iş parçacığında yakalanmamış istisna olarak sızmasın.
    def unionRoi = null
    if (!targets.isEmpty()) {
        try { unionRoi = unionRoiOf(targets) }
        catch (Throwable t) { blocks << 'Seçili alanlar aynı z/t düzleminde değil — birleştirilemedi (' + (t.getMessage() ?: t.getClass().getSimpleName()) + ').' }
    }
    def cells = (unionRoi != null && Double.isFinite(mpp) && mpp > 0) ? detectionsIn(imageData, unionRoi) : []
    if (!targets.isEmpty() && Double.isFinite(mpp) && mpp > 0 && cells.isEmpty())
        blocks << 'Seçili alanda hücre tespiti yok — önce bir hücre tespiti çalıştırın (Hücre tespiti / StarDist / InstanSeg / Cellpose).'
    int batch = parseIntOr(cfg.batch, DEFAULT_BATCH); if (batch < 1) blocks << 'Batch değeri geçersiz (en az 1) — Kurulum ekranından düzeltin.'
    int chunk = parseIntOr(cfg.chunk, DEFAULT_CHUNK); if (chunk < 1) blocks << 'Parça (chunk) değeri geçersiz (en az 1) — Kurulum ekranından düzeltin.'
    double minProb = parseDoubleOr(cfg.minProb, 0.0d)
    boolean gpu = (cfg.device ?: 'auto') != 'cpu'
    int nChunks = chunk > 0 ? (int) Math.ceil(cells.size() / (double) chunk) : 0
    double estSec = estimateSeconds(cells.size(), nChunks, gpu)
    boolean overThreshold = cells.size() > (gpu ? CONFIRM_GPU_CELLS : CONFIRM_CPU_CELLS) || estSec > CONFIRM_MINUTES * 60.0d
    double areaMm2 = (unionRoi != null && Double.isFinite(mpp) && mpp > 0) ? areaMm2Of(unionRoi, mpp) : Double.NaN
    return [ok: blocks.isEmpty(), blocks: blocks, warns: warns, targets: targets, unionRoi: unionRoi, cells: cells,
             mpp: mpp, batch: batch, chunk: chunk, minProb: minProb, gpu: gpu, nChunks: nChunks, estSec: estSec,
             overThreshold: overThreshold, areaMm2: areaMm2]
}

// ── Headless: prefs + `-a key=value` argümanlarıyla TAM hat (spec: organ, model, device, batch, chunk, minprob) ──
if (isHeadless) {
    def scriptArgs = []
    try { if (binding.hasVariable('args')) { def a = binding.getVariable('args'); if (a != null) scriptArgs = (a as List) } } catch (Throwable ignore) {}
    def argMap = [:]
    scriptArgs.each { a -> def s = a?.toString() ?: ''; int i = s.indexOf('='); if (i > 0) argMap[s.substring(0, i).trim()] = s.substring(i + 1).trim() }

    def cfg = loadConfig()
    if (argMap.organ)   cfg.organ = argMap.organ
    if (argMap.device && ['auto', 'cuda', 'cpu'].contains(argMap.device)) cfg.device = argMap.device
    if (argMap.batch)   cfg.batch = argMap.batch
    if (argMap.chunk)   cfg.chunk = argMap.chunk
    if (argMap.minprob) cfg.minProb = argMap.minprob
    // HPC/toplu-iş: GUI onay kutusu yerine SHA'nın kendisini geçirme (runner'ın kendi 'fetch-code
    // --accept-license' deyimiyle tutarlı) — YALNIZ bu çalıştırma için cfg'de tutulur, java.util.prefs'e
    // YAZILMAZ; böylece paylaşılan bir makinede kalıcı "herkes onayladı" izlenimi bırakmaz. Katılımcı yine
    // de lisans metnini (② adımı, GUI) okumuş ve tam SHA'yı ('${CODE_SHA}') kopyalamış olmalıdır.
    if (argMap['accept-license'] == CODE_SHA) cfg.licenseAccepted = CODE_SHA
    // İsteğe bağlı testerlik/CI kolaylığı: proje YOKKEN de python/runner/model bulunabilsin diye
    // düz "python=" / "runner=" geçersiz kılmaları — spec'in listelediği argümanlara EK, zararsız.
    if (argMap.python) cfg.python = argMap.python
    if (argMap.runner) cfg.runner = argMap.runner
    File modelDirOverride = null
    if (argMap.model) { modelDirOverride = new File(argMap.model); cfg.source = 'folder'; cfg.folder = argMap.model }

    println "CytoFormer sihirbazı: python=${cfg.python ?: '(yok)'} runner=${cfg.runner ?: '(yok)'} organ=${cfg.organ ?: '(seçilmedi)'}"
    def st = setupStatus(cfg)
    println "① Python ortamı: ${st.pythonOk ? 'VAR' : 'yok'} · köprü: ${st.runnerOk ? 'VAR' : 'yok'}"
    println "② Kod (lisans onaylı indirme): ${st.codeOk ? 'VAR' : 'yok'} — ${codeDir().getAbsolutePath()}"
    def effModelDir = modelDirOverride ?: modelDirOf(cfg)
    println "③ Model dosyası: ${(effModelDir != null && new File(effModelDir, 'checkpoint.pth').isFile()) ? 'VAR' : 'yok'} — ${effModelDir?.getAbsolutePath() ?: '(klasör yok)'}"

    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println "Görüntü açık değil (bkz. QuPath script -i <slayt>)."; println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."; return }
    if (!cfg.organ?.trim() || !organById.containsKey(cfg.organ)) { println "HATA: organ zorunlu — -a organ=<${ORGANS.collect { it.id }.join('|')}>"; return }
    if (!st.pythonOk || !st.runnerOk) { println "HATA: Python ortamı/köprü eksik — GUI'de Kurulum ekranından tamamlayın."; return }
    if (effModelDir == null || !new File(effModelDir, 'checkpoint.pth').isFile()) { println "HATA: model dosyası bulunamadı — -a model=<klasör> ya da GUI'de ③ Ağırlık adımını tamamlayın."; return }
    // Kod paylaşılan atölye veri kökünde (dataRoot) OLABİLİR ama lisans onayı KULLANICI BAŞINA (java.util.prefs,
    // Windows hesabına özel) tutulur — paylaşılan makinede BAŞKA bir katılımcının indirmiş olması BU kullanıcıyı
    // onaylamış saymaz. Headless çalıştırma Penn Academic Software License'lı kodu bu onay olmadan tetiklememeli.
    if (!st.codeOk || !st.licenseOk) { println "HATA: Kod indirilmemiş veya lisans bu Windows hesabı için onaylanmamış — GUI'de ② adımını (Penn Academic Software License onayı + kodu indir) bu hesapla tamamlayın, ya da HPC/toplu-iş için -a accept-license=<SHA> geçin (bkz. betik başlığı)."; return }

    def rd = readiness(imageData, cfg, allAreaTargets)
    // Headless'ta kurulum/model denetimi yukarıda YAPILDI; readiness'in "kurulum eksik" blok'unu (③
    // doğrulama önbelleği GUI'de yazıldığı için burada boş kalabilir) yok sayıp diğer blokları göster.
    def realBlocks = rd.blocks.findAll { !it.startsWith('Kurulum tamamlanmadı') }
    if (!realBlocks.isEmpty()) { println "Çalıştırılamadı:"; realBlocks.each { println '  • ' + it }; println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."; return }
    rd.warns.each { println '⚠ ' + it }
    println String.format(java.util.Locale.US, 'Tahmini süre: %s (%d hücre, %d parça, %s)', formatDuration(rd.estSec), rd.cells.size(), rd.nChunks, rd.gpu ? 'GPU varsayımı' : 'CPU')

    def workDir = resolveWorkDir(cfg, imageData); workDir.mkdirs()
    long t0 = System.currentTimeMillis()
    def appendLine = { String ln -> println '  | ' + ln }
    def setPhase = { String ph -> println '# ' + ph }
    def pipeline = runClassifyPipeline(imageData.getServer(), rd.cells, rd.mpp, cfg, effModelDir, cfg.organ, workDir,
        rd.batch, rd.chunk, appendLine, setPhase, { -> false })
    if (!pipeline.ok) {
        // İptal durumunda pipeline.assignments YOK (kasıtlı — bkz. runClassifyPipeline yorumu); gerçek bir
        // hatada ise ÖNCEKİ parçalardan kalan kısmi sonucu yine de yaz — tamamlanmış GPU işi kaybolmasın.
        if (pipeline.assignments) {
            def partial = applyAssignments(rd.cells, pipeline.assignments, rd.minProb)
            writeAnnotationMeasurements(rd.targets, rd.cells, pipeline.assignments, rd.minProb, rd.mpp)
            try { imageData.getHierarchy().fireHierarchyChangedEvent(imageData.getHierarchy()) } catch (Throwable ignore) {}
            println String.format(java.util.Locale.US, 'Kısmi sonuç uygulandı: %,d hücre (başarısız olan parçadan ÖNCEKİ parçalar).', partial.assigned as int)
        }
        println 'HATA: ' + (pipeline.error ?: 'bilinmeyen hata'); println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."; return
    }
    def applyStats = applyAssignments(rd.cells, pipeline.assignments, rd.minProb)
    writeAnnotationMeasurements(rd.targets, rd.cells, pipeline.assignments, rd.minProb, rd.mpp)
    try { imageData.getHierarchy().fireHierarchyChangedEvent(imageData.getHierarchy()) } catch (Throwable ignore) {}
    long elapsed = System.currentTimeMillis() - t0
    println buildResultText(imageData, cfg, cfg.organ, pipeline, applyStats, rd.areaMm2, elapsed)
    return
}

// ── Durum makinesi (yalnız etkileşimli mod buradan sonrasına ulaşır) ─────────────────────────────
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def jobOkRef      = new java.util.concurrent.atomic.AtomicBoolean(true)
def jobTitleRef   = new java.util.concurrent.atomic.AtomicReference('')
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef   = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def workDirRef    = new java.util.concurrent.atomic.AtomicReference(null)
def runStartRef   = new java.util.concurrent.atomic.AtomicLong(0L)
def timelineRef   = new java.util.concurrent.atomic.AtomicReference(null)
def tokenFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def render

def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text); b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    return b
}
def busyBar = { -> def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb }
def copyToClipboard = { String txt -> def cb = javafx.scene.input.Clipboard.getSystemClipboard(); def content = new javafx.scene.input.ClipboardContent(); content.putString(txt ?: ""); cb.setContent(content) }
def openFolder = { File f -> try { if (f != null && f.isDirectory() && java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(f) } catch (Throwable ignore) {} }
def later = { Closure c -> javafx.application.Platform.runLater { c() } }

// ── Çalışma günlüğü ───────────────────────────────────────────────────────────
def runLog      = new StringBuilder()
def resetLog    = { -> synchronized (runLog) { runLog.setLength(0) } }
def appendLog   = { String ln -> synchronized (runLog) { runLog.append(ln == null ? '' : ln).append('\n') } }
def logSnapshot = { -> synchronized (runLog) { return runLog.toString() } }
def saveLogInteractive = {
    def txt = logSnapshot()
    if (!txt?.trim()) { Dialogs.showInfoNotification('Günlük', 'Kaydedilecek günlük yok.'); return }
    try {
        def suggested = new File(workDirRef.get() ?: new File(System.getProperty('user.home')), 'cytoformer_wizard.log')
        def f = qupath.fx.dialogs.FileChoosers.promptToSaveFile(stage, 'Günlüğü kaydet', suggested,
            new javafx.stage.FileChooser.ExtensionFilter('Günlük (*.log, *.txt)', '*.log', '*.txt'))
        if (f != null) { f.setText(txt, 'UTF-8'); Dialogs.showInfoNotification('Günlük', 'Kaydedildi: ' + f.getAbsolutePath()) }
    } catch (Throwable t) { Dialogs.showErrorMessage('Günlük', 'Kaydedilemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName())) }
}

// ── Kurulum işleri (selftest / kod indir / ağırlık indir-doğrula): tek biçim, canlı günlük ──
def startJob = { String title, List args, Map extraEnv, Closure onFinish ->
    def cfg = loadConfig()
    if (!cfg.python?.trim() || !new File(cfg.python).isFile() || !cfg.runner?.trim() || !new File(cfg.runner).isFile()) {
        errorTextRef.set('Önce ① Python ortamını kurun ve köprü betiğini bulun.'); step.set('ERROR'); render(); return
    }
    cancelledRef.set(false); resetLog()
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(true); la.setStyle(MONO)
    logAreaRef.set(la); jobTitleRef.set(title)
    boolean isCheck = (args[0] == 'selftest')
    step.set(isCheck ? 'CHECK_RUNNING' : 'DL_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython([cfg.python, cfg.runner] + args, extraEnv ?: [:], appendLine)
        if (r.error) appendLine('HATA: ' + r.error)
        appendLine('# Çıkış kodu: ' + r.exitCode)
        try { onFinish(r, cfg) } catch (Throwable t) { appendLine('HATA (sonuç işlenemedi): ' + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')) }
        javafx.application.Platform.runLater { jobOkRef.set(r.ok); step.set(isCheck ? 'CHECK_DONE' : 'DL_DONE'); render() }
    }, 'AtolyeCytoFormer-Job')
    worker.setDaemon(true); worker.start()
}
def startSelftest    = { -> startJob('Bağımlılık denetimi (selftest)', ['selftest'], [:], { r, cfg -> }) }
def startFetchCode   = { ->
    if (prefs.get(PREF_LICENSE, '') != CODE_SHA) { errorTextRef.set('Önce ② lisans onay kutusunu işaretleyin.'); step.set('ERROR'); render(); return }
    startJob('Kod hazırlanıyor (~30 KB, 8 dosya, commit ' + CODE_SHA_SHORT + ')', ['fetch-code', '--accept-license'], [:], { r, cfg -> })
}
def startVerifyWeights = { ->
    def cfg = loadConfig(); def d = modelDirOf(cfg)
    if (d == null) { errorTextRef.set('Önce ③ model klasörünü seçin.'); step.set('ERROR'); render(); return }
    startJob('Model doğrulanıyor (SHA-256, ~2,7 GB)', ['verify-weights', '--model-dir', d.getAbsolutePath()], [:], { r, cfg2 -> if (r.ok) markVerified(ckptFileOf(cfg2)) })
}
def startFetchWeights = { String token ->
    def dir = defaultWeightsDir(); dir.mkdirs()
    def extra = (token?.trim()) ? [HF_TOKEN: token.trim()] : [:]
    startJob('Ağırlık indiriliyor (~2,7 GB, Hugging Face)', ['fetch-weights', '--dest', dir.getAbsolutePath()], extra, { r, cfg2 -> if (r.ok) markVerified(new File(dir, 'checkpoint.pth')) })
}

// ── Ortam yöneticisinden bu sihirbaza dönüş ──────────────────────────────────
def envReturnHook = [
    envId   : ENV_ID,
    wizard  : 'CytoFormer hücre tipi sınıflama',
    onReturn: { reopen ->
        javafx.application.Platform.runLater {
            if (stage == null || (!stage.isShowing() && !reopen)) return
            try {
                def savedPy = prefs.get(PREF_PYTHON, '')
                if (savedPy?.trim() && !(new File(savedPy.trim())).isFile()) { prefs.remove(PREF_PYTHON); try { prefs.flush() } catch (Throwable ignore) {} }
                if (['CONFIG_INCOMPLETE', 'READY', 'CHECK_DONE', 'DL_DONE', 'ERROR'].contains(step.get())) {
                    step.set(setupComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render()
                }
                if (stage.isIconified()) stage.setIconified(false)
                if (!stage.isShowing()) stage.show()
                stage.toFront(); stage.requestFocus()
            } catch (Throwable t) {
                Dialogs.showErrorMessage('Sihirbaza dönüş', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
            }
        }
    }
]
def launchEnvManager = { ->
    new Thread({
        try {
            def res = 'yardimci-python-ortam-yoneticisi.groovy'
            def url = null
            try { url = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getResource('/scripts/' + res) } catch (Throwable t) {}
            if (url == null) url = this.getClass().getResource('/scripts/' + res)
            if (url == null) {
                javafx.application.Platform.runLater { Dialogs.showInfoNotification('Betik bulunamadı',
                    'Menüden açın: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi') }
                return
            }
            def cl = this.getClass().getClassLoader()
            try { cl = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getClassLoader() } catch (Throwable t) {}
            def shellBinding = new Binding()
            shellBinding.setVariable('atolyeReturnHook', envReturnHook)
            new GroovyShell(cl, shellBinding).evaluate(url.getText('UTF-8'), res)
        } catch (Throwable t) {
            javafx.application.Platform.runLater { Dialogs.showErrorMessage('Açılamadı', (t.getMessage() ?: t.getClass().getSimpleName())) }
        }
    } as Runnable).start()
}

// ── Çalıştırma akışı (etkileşimli) ────────────────────────────────────────────
// rd HER ZAMAN burada yeniden hesaplanır — render() sırasında üretilen rd render ANINDAKİ seçimi
// yansıtır; kullanıcı "Çalıştır"a basmadan önce görüntüdeki seçimi değiştirmiş olabilir (sessizce
// YANLIŞ bölgeyi sınıflamak yerine burada TAZE bir denetim yapılır — bkz. Classpose'un aynı deseni).
def startRun = { ->
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def rd = readiness(imageData, cfg, selectedTargets)
    if (!rd.ok) { errorTextRef.set('Çalıştırılamadı (seçim değişmiş olabilir):\n  • ' + rd.blocks.join('\n  • ')); step.set('ERROR'); render(); return }
    def modelDir = modelDirOf(cfg)
    def workDir = resolveWorkDir(cfg, imageData); workDir.mkdirs(); workDirRef.set(workDir)
    cancelledRef.set(false); resetLog(); runStartRef.set(System.currentTimeMillis())
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(true); la.setStyle(MONO); logAreaRef.set(la)
    runPhaseRef.set('Hazırlanıyor…'); step.set('RUN_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> appendLog(ln); javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def setPhase   = { String ph -> javafx.application.Platform.runLater { runPhaseRef.set(ph); render() } }
        try {
            appendLine('Organ: ' + organTrOf(cfg.organ) + ' (' + cfg.organ + ')  ·  hücre: ' + rd.cells.size() + '  ·  parça: ' + rd.chunk)
            def pipeline = runClassifyPipeline(imageData.getServer(), rd.cells, rd.mpp, cfg, modelDir, cfg.organ, workDir,
                rd.batch, rd.chunk, appendLine, setPhase, { -> cancelledRef.get() })
            long elapsed = System.currentTimeMillis() - runStartRef.get()
            if (!pipeline.ok) {
                if (pipeline.exitCode == -3) {
                    javafx.application.Platform.runLater { Dialogs.showInfoNotification('CytoFormer', 'Çalıştırma iptal edildi.'); step.set('READY'); render() }
                    return
                }
                javafx.application.Platform.runLater {
                    // İptal değil, GERÇEK bir hata: önceki parçalardan kalan kısmi 'assignments' varsa
                    // (bkz. runClassifyPipeline) o kadarını yine de yaz — tamamlanmış GPU işi kaybolmasın.
                    def nPartial = 0
                    if (pipeline.assignments) {
                        try {
                            def partial = applyAssignments(rd.cells, pipeline.assignments, rd.minProb)
                            writeAnnotationMeasurements(rd.targets, rd.cells, pipeline.assignments, rd.minProb, rd.mpp)
                            def hier = imageData.getHierarchy(); hier.fireHierarchyChangedEvent(hier)
                            try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
                            nPartial = partial.assigned as int
                        } catch (Throwable ignore) {}
                    }
                    errorTextRef.set((nPartial > 0 ? String.format(java.util.Locale.US, 'Kısmi sonuç uygulandı: %,d hücre (başarısız olan parçadan ÖNCEKİ parçalar).\n\n', nPartial) : '') +
                        (pipeline.error ?: 'Bilinmeyen hata'))
                    step.set('ERROR'); render()
                }
                return
            }
            javafx.application.Platform.runLater {
                try {
                    def applyStats = applyAssignments(rd.cells, pipeline.assignments, rd.minProb)
                    writeAnnotationMeasurements(rd.targets, rd.cells, pipeline.assignments, rd.minProb, rd.mpp)
                    def hier = imageData.getHierarchy()
                    hier.fireHierarchyChangedEvent(hier)
                    try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
                    def txt = buildResultText(imageData, cfg, cfg.organ, pipeline, applyStats, rd.areaMm2, elapsed)
                    resultTextRef.set(txt); step.set('RESULT'); render()
                } catch (Throwable t) { errorTextRef.set('Sonuç işleme hatası: ' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('Beklenmeyen hata:\n' + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')); step.set('ERROR'); render() }
        }
    }, 'AtolyeCytoFormer-Run')
    worker.setDaemon(true); worker.start()
}
// rd burada yalnız EŞİK/tahmin metnini göstermek için kullanılır (render anındaki seçim) — asıl
// çalıştırma startRun() içinde TAZE bir denetimle yapılır, bu yüzden birkaç saniyelik eskime zararsızdır.
def confirmAndRun = { rd ->
    if (rd.overThreshold) {
        def msg = String.format(java.util.Locale.US, '%,d hücre · tahmini süre %s. Devam edilsin mi?', rd.cells.size(), formatDuration(rd.estSec))
        if (!Dialogs.showConfirmDialog('Büyük çalıştırma', msg)) return
    }
    startRun()
}

// ── Render ─────────────────────────────────────────────────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def oldTimeline = timelineRef.get(); if (oldTimeline != null) { oldTimeline.stop(); timelineRef.set(null) }
    def cur = step.get()
    def imageData = QP.getCurrentImageData()
    def cfg = loadConfig()

    def title = new javafx.scene.control.Label(); title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10); center.setPadding(new javafx.geometry.Insets(14)); center.getChildren().add(title)
    def actions = new ArrayList()

    def wrapBind = { javafx.scene.control.Label lbl ->
        lbl.setWrapText(true)
        lbl.sceneProperty().addListener({ obs, o, sc -> if (sc != null) { try { lbl.maxWidthProperty().unbind() } catch (Throwable ig) {}; lbl.maxWidthProperty().bind(sc.widthProperty().subtract(38)) } } as javafx.beans.value.ChangeListener)
    }
    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); wrapBind(lbl); center.getChildren().add(lbl) }
    def addMonoArea = { String txt -> def ta = new javafx.scene.control.TextArea(txt ?: ''); ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO); javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(ta) }
    def addWarnLabel = { String txt -> def lbl = new javafx.scene.control.Label(txt); wrapBind(lbl); lbl.setStyle('-fx-text-fill: #b8860b; -fx-font-weight: bold;'); center.getChildren().add(lbl) }
    def addLiveLog = { -> def la = logAreaRef.get(); if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) } }
    def statusLine = { boolean ok, String msg -> def l = new javafx.scene.control.Label((ok ? '✓ ' : '○ ') + msg); wrapBind(l); l.setStyle(ok ? '-fx-text-fill: #2e7d32;' : '-fx-text-fill: #8a6d00;'); center.getChildren().add(l) }
    def section = { String head -> def l = new javafx.scene.control.Label(head); l.setStyle('-fx-font-weight: bold; -fx-padding: 6 0 0 0;'); center.getChildren().add(l) }
    def rowOf = { List nodes -> def h = new javafx.scene.layout.HBox(8); h.setAlignment(javafx.geometry.Pos.CENTER_LEFT); h.getChildren().addAll(nodes); center.getChildren().add(h); return h }
    def killRunning = { -> cancelledRef.set(true); killTree(processRef.get()) }
    def closeWizard = { ->
        if ((step.get() ?: '').endsWith('_RUNNING')) killRunning()
        timelineRef.get()?.stop()
        stage.close()
    }

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('CytoFormer — kurulum')
        def st = setupStatus(cfg)
        addGuidance('Üç kurulum adımı vardır; hepsi tamamlanınca "Çalıştırma ekranına geç" etkinleşir.')

        section('① Python ortamı')
        statusLine(st.pythonOk, st.pythonOk ? ('Python: ' + cfg.python) : 'Python ortamı (cytoformer) kurulu değil — ortam yöneticisinde "CytoFormer — H&E hücre tipi sınıflama" satırını kurun (~5 GB, GPU önerilir).')
        statusLine(st.runnerOk, st.runnerOk ? ('Köprü: ' + cfg.runner) : 'Köprü betiği bulunamadı (handson/python/cytoformer/cytoformer_runner.py) — atölye projesini açın ya da "Köprü seç…".')
        rowOf([navButton('Python ortam yöneticisi…', { launchEnvManager() }, 'Atölye Python ortam yöneticisini açar; "Sihirbaza dön ▶" ile geri gelirsiniz'),
               navButton('Denetle', { startSelftest() }, 'cytoformer_runner.py selftest — paketler + CUDA + kod/model durumu'),
               navButton('Python seç…', { def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'python.exe seçin'); if (x != null) { putPref(PREF_PYTHON, x.getAbsolutePath()); later { render() } } }),
               navButton('Köprü seç…', { def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'cytoformer_runner.py seçin'); if (x != null) { putPref(PREF_RUNNER, x.getAbsolutePath()); later { render() } } })])

        section('② Kod (Penn Academic Software License)')
        statusLine(st.codeOk, (st.codeOk ? 'Kod indirilmiş: ' : 'Kod yok: ') + codeDir().getAbsolutePath())
        def licenseArea = new javafx.scene.control.TextArea(
            'PENN ACADEMIC SOFTWARE LICENSE AGREEMENT — özet (Türkçe): Kodu yalnız dahili araştırma/ticari olmayan ' +
            'amaçla kullanabilirsiniz; PENN\'in yazılı izni olmadan başka bir yere ya da kişiye aktaramaz/dağıtamazsınız; ' +
            'kaynak kodu tersine mühendislikle yeniden oluşturmaya çalışamazsınız; yazılıma dayalı oluşturduğunuz her ' +
            'program türev sayılır ve PENN\'e ait olur; sözleşme 30 gün önceden bildirimle feshedilebilir. İndirdiğinizde bu ' +
            'lisansın ALICISI (RECIPIENT) siz olursunuz.\n\n' +
            'İngilizce özgün metin (birebir):\n\n' +
            '§4. "RECIPIENT agrees to use the Software solely for internal research or non-commercial purposes and ' +
            'shall not distribute or transfer the Software to another location or to any other person without prior ' +
            'written permission from PENN. Nothing in this license grants any rights for commercial use."\n\n' +
            '§9. "RECIPIENT agrees not to reverse engineer, reverse assemble, reverse compile decompile, disassemble, ' +
            'or otherwise attempt to re-create the source code for the Software. RECIPIENT acknowledges that any ' +
            'programs created based on the Software will be considered a derivative of Software and owned by PENN."\n\n' +
            'Tam metin (indirilecek 8 dosyadan biri, LICENSE): ' + LICENSE_URL + '\n\n' +
            'Araştırma dışı/ticari/yeniden dağıtım kullanımı için: zhi.huang@pennmedicine.upenn.edu — bu bir hukuki görüş değildir.')
        licenseArea.setEditable(false); licenseArea.setWrapText(true); licenseArea.setPrefRowCount(10)
        center.getChildren().add(licenseArea)
        def licenseChk = new javafx.scene.control.CheckBox('Okudum; bu koşulları (yalnız dahili araştırma / ticari olmayan kullanım, yeniden dağıtım yok) kendi adıma kabul ediyorum.')
        licenseChk.setWrapText(true); licenseChk.setMaxWidth(Double.MAX_VALUE)
        licenseChk.setSelected(st.licenseOk)
        licenseChk.selectedProperty().addListener({ obs, o, n -> putPref(PREF_LICENSE, n ? CODE_SHA : ''); later { render() } } as javafx.beans.value.ChangeListener)
        center.getChildren().add(licenseChk)
        def dlCodeBtn = navButton('Kodu indir', { startFetchCode() }, 'fetch-code --accept-license — commit ' + CODE_SHA_SHORT + '\'den 8 dosya')
        dlCodeBtn.setDisable(!(st.pythonOk && st.runnerOk && (prefs.get(PREF_LICENSE, '') == CODE_SHA)))
        rowOf([dlCodeBtn])

        section('③ Ağırlık (checkpoint.pth, CC BY-NC 4.0, kapılı)')
        def tg = new javafx.scene.control.ToggleGroup()
        def rbFolder = new javafx.scene.control.RadioButton('Klasördeki modeli kullan'); rbFolder.setToggleGroup(tg)
        def rbDl = new javafx.scene.control.RadioButton('Hugging Face\'ten indir'); rbDl.setToggleGroup(tg)
        (cfg.source == 'folder' ? rbFolder : rbDl).setSelected(true)
        tg.selectedToggleProperty().addListener({ obs, o, n -> if (n != null) { putPref(PREF_SOURCE, n == rbFolder ? 'folder' : 'download'); later { render() } } } as javafx.beans.value.ChangeListener)
        rowOf([rbFolder, rbDl])
        if (cfg.source == 'folder') {
            rowOf([navButton('Klasör seç…', { def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'Model klasörü (checkpoint.pth + organ_celltype_map.json)', null); if (x != null) { putPref(PREF_FOLDER, x.getAbsolutePath()); later { render() } } }),
                   new javafx.scene.control.Label(cfg.folder?.trim() ? cfg.folder : '(klasör seçilmedi — ör. J:\\zhihuanglab\\CytoFormer)')])
        } else {
            addGuidance('İndirmeden önce: (1) Hugging Face hesabı açın, (2) huggingface.co/zhihuanglab/CytoFormer sayfasında koşulları kabul edin (otomatik onay), (3) bir OKUMA (read) jetonu oluşturun, (4) bir terminalde "hf auth login" çalıştırın YA DA jetonu aşağıya yapıştırın (diske yazılmaz).')
            def tokenField = new javafx.scene.control.PasswordField(); tokenField.setPromptText('Hugging Face jetonu (isteğe bağlı — hf auth login yapıldıysa boş bırakın)'); tokenField.setPrefColumnCount(30)
            tokenFieldRef.set(tokenField)
            rowOf([new javafx.scene.control.Label('HF jetonu:'), tokenField])
        }
        def ckpt = ckptFileOf(cfg)
        if (ckpt == null) statusLine(false, 'Model klasörü seçilmedi.')
        else if (!st.weightsPresent) statusLine(false, 'Dosya yok: ' + ckpt.getAbsolutePath())
        else if (!st.weightsOk) statusLine(false, 'Dosya var ama doğrulanmadı: ' + ckpt.getAbsolutePath() + ' — "Doğrula" (SHA-256, ~2,7 GB — bir kez)')
        else statusLine(true, 'Doğrulandı: ' + ckpt.getAbsolutePath())
        def modelBtns = []
        if (st.weightsPresent && !st.weightsOk) modelBtns << navButton('Doğrula', { startVerifyWeights() }, 'SHA-256 sabitlenmiş sürümle karşılaştırılır')
        if (cfg.source == 'download' && !st.weightsOk) modelBtns << navButton('İndir (~2,7 GB)', { startFetchWeights(tokenFieldRef.get()?.getText()) }, 'Hugging Face (sabit revizyon 3e9691e) → veri kökü')
        if (!modelBtns.isEmpty()) rowOf(modelBtns)

        actions.add(navButton('Kapat', { closeWizard() }))
        def goBtn = navButton('Çalıştırma ekranına geç ▶', { step.set('READY'); render() })
        goBtn.setDisable(!setupComplete(cfg))
        actions.add(goBtn)
    } else if (cur == 'CHECK_RUNNING' || cur == 'DL_RUNNING') {
        title.setText(jobTitleRef.get() + '…'); center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { killRunning() }))
    } else if (cur == 'CHECK_DONE' || cur == 'DL_DONE') {
        title.setText(jobTitleRef.get() + (jobOkRef.get() ? ' — tamam ✅' : (cancelledRef.get() ? ' — iptal edildi' : ' — BAŞARISIZ (günlüğe bakın)')))
        addLiveLog()
        actions.add(navButton('◀ Kuruluma dön', { step.set('CONFIG_INCOMPLETE'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
        if (jobOkRef.get() && setupComplete(loadConfig())) actions.add(navButton('Çalıştırma ekranına dön ▶', { step.set('READY'); render() }))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText('Görüntü açık değil')
            addGuidance('Önce bir H&E slaytı açın, hücre tespiti çalıştırın, ilgi alanınızı çizip seçin, sonra "⟳ Yenile".')
            actions.add(navButton('◀ Kurulum', { step.set('CONFIG_INCOMPLETE'); render() })); actions.add(navButton('⟳ Yenile', { render() }))
        } else {
            def st = setupStatus(cfg)
            title.setText('CytoFormer — H&E hücre tipi sınıflama')
            addGuidance(String.format(java.util.Locale.US, 'Kurulum: ① Python %s · ② Kod %s · ③ Ağırlık %s',
                st.pythonOk && st.runnerOk ? '✓' : '✗', st.codeOk ? '✓' : '✗', st.weightsOk ? '✓' : '✗'))

            int idx = ORGANS.findIndexOf { it.id == cfg.organ }
            def organChoice = new javafx.scene.control.ChoiceBox()
            ORGANS.each { organChoice.getItems().add(it.tr + ' (' + it.id + ')') }
            if (idx >= 0) organChoice.getSelectionModel().select(idx)
            organChoice.getSelectionModel().selectedIndexProperty().addListener({ obs, o, n -> int i = n as int; if (i >= 0 && i < ORGANS.size()) { putPref(PREF_ORGAN, ORGANS[i].id); later { render() } } } as javafx.beans.value.ChangeListener)
            rowOf([new javafx.scene.control.Label('⑤ Organ:'), organChoice])
            if (cfg.organ?.trim() && !organById.containsKey(cfg.organ)) addWarnLabel('⚠ Bilinmeyen organ anahtarı.')
            addGuidance('16 organın dışındaki bir doku için genel bir vekil seçilebilir — ' + CARCINOMA_ORGANS.collect { organTrOf(it) + ' (' + it + ')' }.join(', ') +
                ' aynı 7 sınıfı (Tümör hücresi·Stromal hücre·Epitel hücresi·Lenfosit·Endotel hücresi·Makrofaj·Plazma hücresi) paylaşır; sonuç o organ için doğrulanmamıştır.')

            def rd = readiness(imageData, cfg, selectedTargets)
            def sb = new StringBuilder()
            sb << "Slayt        : " << imageNameOf(imageData) << "\n"
            sb << "④ Hücre sayısı (seçili alanda): " << rd.cells.size() << "\n"
            sb << "Kalibrasyon  : " << (Double.isFinite(rd.mpp) && rd.mpp > 0 ? String.format(java.util.Locale.US, '%.4f µm/px', rd.mpp) : 'KALİBRE DEĞİL') << "\n"
            if (Double.isFinite(rd.areaMm2)) sb << String.format(java.util.Locale.US, "Alan         : %.3f mm²%n", rd.areaMm2)
            if (rd.cells.size() > 0) sb << String.format(java.util.Locale.US, "Tahmini süre : %s (%d parça, %s)%n", formatDuration(rd.estSec), rd.nChunks, rd.gpu ? 'GPU varsayımı' : 'CPU')
            addMonoArea(sb.toString())

            def devIds = ['auto', 'cuda', 'cpu']
            def devChoice = new javafx.scene.control.ChoiceBox(); devChoice.getItems().addAll('Otomatik', 'GPU (CUDA)', 'CPU')
            devChoice.getSelectionModel().select(Math.max(0, devIds.indexOf(cfg.device)))
            devChoice.getSelectionModel().selectedIndexProperty().addListener({ obs, o, n -> int i = n as int; if (i >= 0) { putPref(PREF_DEVICE, devIds[i]); later { render() } } } as javafx.beans.value.ChangeListener)
            rowOf([new javafx.scene.control.Label('Cihaz:'), devChoice])

            def batchField = new javafx.scene.control.TextField(cfg.batch); batchField.setPrefColumnCount(6)
            def chunkField = new javafx.scene.control.TextField(cfg.chunk); chunkField.setPrefColumnCount(8)
            def probField  = new javafx.scene.control.TextField(cfg.minProb); probField.setPrefColumnCount(6); probField.setPromptText('0 = kapalı')
            [batchField, chunkField, probField].each { f -> f.focusedProperty().addListener({ obs, o, n -> if (!n) later { render() } } as javafx.beans.value.ChangeListener) }
            batchField.textProperty().addListener({ obs, o, n -> putPref(PREF_BATCH, n) } as javafx.beans.value.ChangeListener)
            chunkField.textProperty().addListener({ obs, o, n -> putPref(PREF_CHUNK, n) } as javafx.beans.value.ChangeListener)
            probField.textProperty().addListener({ obs, o, n -> putPref(PREF_MINPROB, n) } as javafx.beans.value.ChangeListener)
            def adv = new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('Batch:'), batchField,
                new javafx.scene.control.Label('Parça (chunk):'), chunkField, new javafx.scene.control.Label('Min. olasılık (0–1):'), probField)
            adv.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            def advPane = new javafx.scene.control.TitledPane('Gelişmiş', adv); advPane.setExpanded(false)
            center.getChildren().add(advPane)

            rd.blocks.each { addWarnLabel('⛔ ' + it) }
            rd.warns.each { addWarnLabel('⚠ ' + it) }
            addGuidance('⑥ "Seçili alanda çalıştır ▶" mevcut sınıfları DEĞİŞTİRİR. Sonuç: her hücre için "<tip> (CytoFormer)" sınıfı + olasılık ölçümü; seçili anotasyonlara sayım/%/yoğunluk.')

            actions.add(navButton('Kapat', { closeWizard() }))
            actions.add(navButton('◀ Kurulum', { step.set('CONFIG_INCOMPLETE'); render() }))
            actions.add(navButton('⟳ Yenile', { render() }))
            def runBtn = navButton('Seçili alanda çalıştır ▶', { confirmAndRun(rd) }, 'CytoFormer\'ı seçili alanda çalıştırır')
            runBtn.setDisable(!rd.ok)
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING') {
        title.setText(runPhaseRef.get())
        def clock = new javafx.scene.control.Label(); clock.setStyle('-fx-opacity: 0.8;')
        def tick = { clock.setText('Geçen süre: ' + formatDuration((System.currentTimeMillis() - runStartRef.get()) / 1000.0d)) }
        tick()
        def tl = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), { e -> tick() } as javafx.event.EventHandler))
        tl.setCycleCount(javafx.animation.Animation.INDEFINITE); tl.play(); timelineRef.set(tl)
        addGuidance('CytoFormer ayrı bir süreç olarak çalışıyor (yama dışa aktarımı + model çıkarımı, parça parça). "İptal et" mevcut parçayı durdurur; o ana kadar HİÇBİR ŞEY QuPath nesnelerine yazılmaz.')
        center.getChildren().add(clock); center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { killRunning() }))
        actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
    } else if (cur == 'RESULT') {
        title.setText('Sınıflama tamam ✅'); addMonoArea(resultTextRef.get())
        addGuidance('Hücre sınıflarını Annotations/Hierarchy panelinden ya da View → Show detections ile inceleyin; sınıf renklerini "Classes" listesinden açıp kapatın.')
        actions.add(navButton('Kapat', { closeWizard() }))
        actions.add(navButton('Klasörü aç', { openFolder(workDirRef.get()) }, 'Yamalar ve classify_out.tsv'))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('↻ Yeniden', { step.set('READY'); render() }))
    } else { // ERROR
        title.setText('Hata'); addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Geri', { step.set(setupComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        if (logSnapshot()?.trim()) actions.add(navButton('Günlüğü kaydet…', { saveLogInteractive() }))
        // Bir sınıflama hatasında (iptal DEĞİL) patches/ klasörü kasıtlı olarak SİLİNMEZ (bkz.
        // runClassifyPipeline) — hata ayıklama için nerede olduğunu göster.
        if (workDirRef.get() != null && ((File) workDirRef.get()).isDirectory())
            actions.add(navButton('Klasörü aç', { openFolder(workDirRef.get()) }, 'Yamalar (varsa) ve classify_out.tsv — hata ayıklama için tutulur'))
        actions.add(navButton('Kapat', { closeWizard() }))
    }

    def topChk = new javafx.scene.control.CheckBox('Üstte tut'); topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n -> alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
    def spacer = new javafx.scene.layout.Region(); javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8); bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk); bar.getChildren().add(spacer); bar.getChildren().addAll(actions)
    def disclaimer = new javafx.scene.control.Label('⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def scroll = new javafx.scene.control.ScrollPane(center); scroll.setFitToWidth(true); scroll.setFitToHeight(true)
    center.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE)
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar); bottom.setPadding(new javafx.geometry.Insets(10))
    def root = new javafx.scene.layout.BorderPane(); root.setCenter(scroll); root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 920, 760))
}

// ── Açılış ────────────────────────────────────────────────────────────────────
step.set(setupComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('CytoFormer — H&E hücre tipi sınıflama')
        stage.setAlwaysOnTop(alwaysTop.get())
        stage.setOnCloseRequest({ e ->
            if ((step.get() ?: '').endsWith('_RUNNING')) { cancelledRef.set(true); killTree(processRef.get()) }
            timelineRef.get()?.stop()
        } as javafx.event.EventHandler)
        render(); stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ CytoFormer hücre tipi sınıflama sihirbazı açıldı."
