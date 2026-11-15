/**
 * Yardımcı - Atölye Python ortam yöneticisi (uv tabanlı, tek pencere)
 * ------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Atölyenin Python köprülü entegrasyonları (TIA Toolbox bölge/boya, Kaiko, SPIDER,
 *   Sectra) için **izole** Python ortamlarını tek tıkla kurar/onarır. Manuel "venv kur,
 *   pip install, python.exe'ye gözat" sürtünmesini kaldırır. Resmî TIA Toolbox eklentisinin
 *   `uv` tabanlı kurulum desenini TÜM entegrasyonlara genelleştirir.
 *
 *   PYTHON ÖN KOŞUL DEĞİLDİR: yönetici saf Groovy'dir ve kendi kendine yeten **uv**
 *   ikilisini çağırır; uv gerekirse Python 3.11'i kendisi indirir. uv yoksa resmî
 *   sürümü `<veri-kökü>/uv/`'a indirilir (PATH'te uv varsa o kullanılır).
 *
 * VERİ KÖKÜ (yapılandırılabilir — C: dolmasın diye):
 *   Öntanımlı `<kullanıcı>/.atolye`. LIST ekranındaki "Veri kökü → Değiştir…" ile
 *   başka bir sürücü/klasör seçilebilir (ör. D:\atolye). Paylaşılan prefs düğümünde
 *   (`/qupath/atolye/common` → `dataRoot`) saklanır; TÜM atölye sihirbazları (GrandQC,
 *   Kaiko, SPIDER, Sectra, TIA) aynı kökü okur. uv/pip önbelleği + indirilen model
 *   ağırlıkları (HF/torch) da bu köke yönlendirilir. Değişiklik yalnız YENİ kurulumları
 *   etkiler — mevcut ortamlar taşınmaz.
 *
 * KURULUM HEDEFLERİ (kural):
 *   <veri-kökü>/runtimes/<id>/.venv/Scripts/python.exe   (Windows)
 *   <veri-kökü>/runtimes/<id>/.venv/bin/python           (macOS/Linux)
 *   Sihirbazlar python'u önce resmî eklenti ortamından, sonra bu dizinden, sonra
 *   manuel ayardan bulur.
 *
 * NE ÜRETİR (ve ne ÜRETMEZ):
 *   Yalnızca Python ortamları kurar (bağımlılıklar). Model AĞIRLIKLARINI indirmez;
 *   kapılı modeller (ör. SPIDER, CC-BY-NC) için ayrıca HF girişi gerekir. Cellpose
 *   ortamları (.venv*) BIOP eklentisiyle yönetilir — bu yönetici kapsamı dışındadır.
 *
 * KULLANIM:
 *   [Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi]
 *   İlgili ortamın yanındaki "Kur" düğmesine basın; log akar, durum güncellenir.
 *   Bir sihirbazın "Python ortamı" düğmesiyle açıldıysa: gereken ortam satırı vurgulanır ve kurulum
 *   bitince "Sihirbaza dön ▶" o sihirbazın çalıştırma ekranına döndürür (pencereyi kapatmak da döndürür).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlıdır.
 */

import qupath.fx.dialogs.Dialogs
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler / platform ──────────────────────────────────────────────────────
long UV_TIMEOUT = 3600L
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def OS = System.getProperty('os.name').toLowerCase(java.util.Locale.ROOT)
boolean IS_WIN = OS.contains('win')
boolean IS_MAC = OS.contains('mac')
def ARCH = System.getProperty('os.arch').toLowerCase(java.util.Locale.ROOT)
def UV_BASE = 'https://github.com/astral-sh/uv/releases/latest/download/'
def uvAsset = { ->
    if (IS_WIN) return 'uv-x86_64-pc-windows-msvc.zip'
    if (IS_MAC) return (ARCH.contains('aarch64') || ARCH.contains('arm')) ? 'uv-aarch64-apple-darwin.tar.gz' : 'uv-x86_64-apple-darwin.tar.gz'
    return (ARCH.contains('aarch64') || ARCH.contains('arm')) ? 'uv-aarch64-unknown-linux-gnu.tar.gz' : 'uv-x86_64-unknown-linux-gnu.tar.gz'
}
def uvExeName = IS_WIN ? 'uv.exe' : 'uv'

// ── Atölye veri kökü (venv/uv/repo/model) ────────────────────────────────────
// Öntanımlı: ~/.atolye  (C: sürücüsü). Kullanıcı başka bir sürücü/klasör seçebilir
// (ör. D:\atolye) — C: dolmasın diye. PAYLAŞILAN prefs düğümünde saklanır, böylece
// TÜM atölye sihirbazları (env yöneticisi, GrandQC, Kaiko, SPIDER, Sectra, TIA)
// AYNI kökü kullanır. `java.util.prefs` → eklenti JAR'ı olmadan da çalışır.
def commonPrefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common')
def PREF_DATA_ROOT = 'dataRoot'
def atolyeDataRoot = { ->
    def p = ''
    try { p = commonPrefs.get(PREF_DATA_ROOT, '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
def uvDir       = { -> new File(atolyeDataRoot(), 'uv') }
def runtimesDir = { -> new File(atolyeDataRoot(), 'runtimes') }

// ── Ortam kataloğu ───────────────────────────────────────────────────────────
// torchBackend: uv --torch-backend (auto = CUDA sürümünü tespit et); null = torch yok.
def CATALOG = [
    [id:'tiatoolbox-region', label:workshopText('script.6c0695506dcb', 'TIA Toolbox — bölge modelleri (KongNet / MapDe)'), python:'3.11',
     packages:['tiatoolbox>=2.1.2','torch','torchvision'], torchBackend:'auto', reuseOfficial:'tiatoolbox-runtime',
     note:workshopText('script.ebe126fc6158', 'Bölge sihirbazı. Resmî TIAToolbox eklentisi kuruluysa onun ortamı kullanılır (ayrı kuruluma gerek yok).')],
    [id:'tiatoolbox-stain', label:workshopText('script.0222999b0a3d', 'TIA Toolbox — boyama normalizasyonu / doku maskesi'), python:'3.11',
     packages:['tiatoolbox>=2.0','numpy','pillow'], torchBackend:null, reuseOfficial:null,
     note:workshopText('script.2254f20baf4a', 'Hafif (torch gerekmez). Boya-normalizasyonu / doku-maskesi sihirbazı.')],
    [id:'grandqc', label:workshopText('script.5ea9ba75b46b', 'GrandQC — doku & artefakt segmentasyonu'), python:'3.10',
     packages:['torch>=2.0','torchvision','segmentation-models-pytorch==0.3.1','timm','six','opencv-python','numpy','Pillow','scipy','scikit-image','tifffile','tqdm','zarr','rasterio','imagecodecs','openslide-python','openslide-bin'],
     torchBackend:'auto', reuseOfficial:null,
     note:workshopText('script.310f509b76ae', '⚠️ Yalnızca Python ortamı. GrandQC betikleri + model ağırlıkları AYRICA gerekir (GrandQC sihirbazının ②③ butonları). openslide-bin, OpenSlide ikililerini pip ile getirir (conda gerekmez). smp 0.3.1 GrandQC kodunun beklediği sürümdür. six: pretrainedmodels (smp 0.3.1 bağımlılığı) six\'i bildirmeden kullanır — açıkça eklenir.')],
    [id:'kaiko', label:workshopText('script.c5ea6feb84c9', 'Kaiko Midnight — denetimli FM sınıflandırıcı'), python:'3.11',
     packages:['torch>=2.1','torchvision>=0.16','transformers>=4.38','safetensors>=0.4','scikit-learn>=1.3','Pillow>=10.0','numpy>=1.24'],
     torchBackend:'auto', reuseOfficial:null, note:workshopText('script.cca815375605', 'Kaiko sınıflandırıcı sihirbazı (eğit → tahmin).')],
    [id:'spider', label:workshopText('script.5e97014bf2b5', 'SPIDER — organ doku sınıflandırıcı'), python:'3.11',
     packages:['torch>=2.1','transformers>=4.40.0','huggingface_hub>=0.23','Pillow>=10.0','numpy>=1.24'],
     torchBackend:'auto', reuseOfficial:null, note:workshopText('script.d4a1bb4ffef2', '⚠️ Model ağırlıkları KAPILI — ayrıca HF girişi gerekir.')],
    [id:'histoart', label:workshopText('script.168ae77dcf81', 'HistoART — artefakt tespiti (WSI kalite kontrol)'), python:'3.11',
     packages:['torch>=2.1','torchvision>=0.16','timm>=1.0.16','huggingface_hub>=0.23','Pillow>=10.0','numpy>=1.24'],
     torchBackend:'auto', reuseOfficial:null,
     note:workshopText('script.71a4bb03610a', 'Yalnızca Python ortamı. Ağırlıklar (didsr/HistoArt, AÇIK) sihirbazın ③ "Modeli indir" düğmesiyle ayrıca çekilir. DLA varyantı açık/CC0 (~94 MB); FMA varyantı ince ayarlı UNI\'dir ve omurgası (MahmoodLab/uni) KAPILIDIR (CC-BY-NC-ND 4.0 + HF erişim onayı) — timm yalnız FMA için gerekir. Upstream requirements.txt\'teki nvidia-*/triton tekerlekleri BİLEREK alınmaz (Linux kilit dosyası; CUDA torchBackend ile çözülür).')],
    [id:'nuclick', label:workshopText('script.f45346430f41', 'NuClick — tıkla → çekirdek sınırı'), python:'3.11',
     packages:['tiatoolbox>=2.1.2','torch>=2.5','torchvision','scikit-image>=0.26','numpy','Pillow'],
     torchBackend:'auto', reuseOfficial:null,
     note:workshopText('script.bd9a4f9844ab', 'Yalnızca Python ortamı. Ağırlık (nuclick_original-pannuke, ~255 MB) sihirbazın ③ düğmesiyle ayrıca çekilir; PanNuke ile eğitildiği için CC BY-NC-SA 4.0\'dır (ticari kullanım yok). Ağ, TIA Toolbox\'ın BSD-3-Clause yeniden uygulamasıdır — upstream nuclick_torch (CC BY-NC-SA) paketlenmez. scikit-image>=0.26: NuClick.postproc `remove_small_objects(..., max_size=)` çağırır; bu kwarg 0.26 ile geldi (tiatoolbox 2.1.3 zaten sabitler, taban açıkça yazılır).')],
    [id:'sectra', label:workshopText('script.2093608ed22a', 'Sectra PACS — DICOM → GeoJSON'), python:'3.11',
     packages:['pydicom>=2.4','shapely','numpy'], torchBackend:null, reuseOfficial:null,
     note:workshopText('script.846a1d9df148', 'Hafif. Sectra PACS içe-aktarma sihirbazı.')],
    [id:'dicomtosvs', label:workshopText('script.04d7ddd29f78', 'DICOMtoSVS — DICOM → SVS dönüştürme'), python:'3.11',
     packages:['pydicom==2.4.5','tifffile','imagecodecs','pylibjpeg','natsort','numpy==1.26.4','pillow'],
     torchBackend:null, reuseOfficial:null,
     note:workshopText('script.3270e96f1d5e', 'Hafif (torch gerekmez). DICOM → SVS sihirbazı; DICOMtoSVS.py betiği AYRICA çalışma anında indirilir (depo LİSANSSIZ → paketlenmez). Sürümler yazarın README\'sine sabitlenmiştir (pydicom 2.4.5, numpy 1.26.4).')],
    [id:'hepatocyte', label:workshopText('script.58e78baba98b', 'Hepatocyte — karaciğer segmentasyon köprüsü'), python:'3.11',
     packages:['torch==2.7.0','numpy~=2.2.6','pandas==2.2.3','pillow==11.2.1',
               'openslide-python==1.4.3','openslide-bin==4.0.0.13','opencv-python-headless==4.13.0.92',
               'albumentations~=2.0.8','shapely==2.1.2','openpyxl==3.1.5'],
     torchBackend:'auto', reuseOfficial:null,
     note:workshopText('script.74211811727e', '⚠️ Yalnızca Python ortamı. hepatocyte-app deposu + model ağırlığı (🔒 talep üzerine) AYRICA gerekir — Hepatosit sihirbazının ②③ butonları. openslide-bin, OpenSlide ikililerini pip ile getirir (conda gerekmez).')],
    [id:'midog-fcos', label:workshopText('script.7d83676a4c70', 'MIDOG25 FCOS — mitoz dedektörü (torchvision)'), python:'3.11',
     packages:['torch>=2.1','torchvision>=0.16','numpy','Pillow','tifffile','SimpleITK','pyyaml','tqdm'],
     torchBackend:'auto', reuseOfficial:null,
     note:workshopText('script.7515de6c07f2', '⚠️ Yalnızca Python ortamı. FCOS_x101.ckpt ağırlığı + MIDOG25 T1 referans KODU AYRICA gerekir — mitoz sihirbazının "Modeli yerel indir" butonu ikisini de çalışma anında çeker (ağırlık: v1.0.0 yayını; kod: sabit commit; paketlenmez, LİSANS dosyası yok → araştırma/eğitim, kullanıcı sorumluluğunda). SimpleITK + pyyaml + tqdm referans kodunun import ettikleridir — bu ortamı daha önce kurduysanız YENİDEN KURUN.')],
    [id:'midog-retinanet-legacy', label:workshopText('script.f9a631d7b789', 'MIDOG DA-RetinaNet (eski, DOĞRULANMAMIŞ)'), python:'3.8',
     packages:['fastai==1.0.61','torch>=1.6,<1.10','torchvision>=0.10,<0.11','opencv-python==4.5.1.48','scikit-learn','scipy','tqdm','numpy','Pillow'],
     torchBackend:'auto', reuseOfficial:null,
     note:workshopText('script.2aeedb5adbab', '⚠️ ESKİ/DOĞRULANMAMIŞ ortam. fastai 1.0.61 + modern torch bilinen bir kırılgan kombinasyondur; çözülmeyebilir ya da import hatası verebilir. Referans depo kodu + ağırlık çalışma anında indirilir (paketlenmez, LİSANS yok). Aşılmış — bunun yerine MIDOG25 FCOS önerilir.')],
    [id:'midog-atypical', label:workshopText('script.cddfaa600bd9', 'MIDOG25 EffNetV2 — atipik sınıflandırıcı (timm)'), python:'3.11',
     packages:['torch>=2.1','torchvision>=0.16','timm>=1.0','numpy','Pillow'],
     torchBackend:'auto', reuseOfficial:null,
     note:workshopText('script.dbd64be69c2f', '⚠️ Yalnızca Python ortamı. Tipik/atipik SINIFLANDIRICI (dedektör değil — önce bir mitoz dedektörü çalıştırın). efficientnetv2_m_fold3_best.pth çalışma anında v1.0.0 yayınından indirilir (paketlenmez, LİSANS yok → araştırma/eğitim).')],
    [id:'sanofi-eftd', label:workshopText('script.387eb0379c6b', 'Sanofi EFTD — atipik sınıflandırıcı (DINOv3-H+, KAPILI)'), python:'3.11',
     packages:['torch>=2.1','torchvision>=0.16','transformers>=4.56','peft>=0.11','safetensors>=0.4','omegaconf','huggingface_hub>=0.23','numpy','Pillow'],
     torchBackend:'auto', reuseOfficial:null,
     note:workshopText('script.f32fefe9dc21', '⚠️ Tipik/atipik SINIFLANDIRICI (MIDOG25 T2 birincisi). KISMEN KAPILI: LoRA adaptörleri açık ama DINOv3-H+ omurgası (facebook/dinov3-vith16plus) HF\'te KAPILIDIR — Meta lisansı + `huggingface-cli login` gerekir (otomatik DEĞİL). QuPath menüsünde varsayılan DEVRE DIŞI. Ticari-olmayan araştırma lisansı.')],
    [id:'hovernext', label:workshopText('script.2f1de9594a30', 'HoVer-NeXt — çekirdek segmentasyon + 7 sınıf (Lizard; NVIDIA GPU)'), python:'3.11',
     packages:['torch==2.1.1','torchvision==0.16.1','timm==0.9.6','segmentation-models-pytorch==0.1.0','six','numpy<2',
               'zarr==2.16.1','numcodecs==0.12.1','scikit-image==0.22.0','scipy==1.11.4','opencv-python==4.8.1.78','Pillow',
               'openslide-python==1.4.6','openslide-bin==4.0.1.2','geojson==3.1.0','shapely==2.0.2','toml==0.10.2',
               'requests','tqdm','pylibCZIrw==4.1.3'],
     torchBackend:'cu121', reuseOfficial:null,
     note:workshopText('script.019fbf70e1c3', '⚠️ NVIDIA CUDA GPU ZORUNLU (HoVer-NeXt CPU\'da çalışmaz). Yalnızca Python ortamı: HoVer-NeXt KODU (GPL-3.0, sabit commit) ve Lizard AĞIRLIKLARI (Zenodo 10635618, CC BY-NC-SA 4.0 — ticari olmayan) sihirbazın "Modeli yerel indir" butonuyla çalışma anında indirilir; paketlenmez, kod ayrı süreç olarak çalıştırılır. Sürümler upstream\'e sabitlenmiştir (torch 2.1.1 + CUDA 12.1, smp 0.1.0, timm 0.9.6, zarr 2.16 → numpy<2); çok yeni GPU mimarileri (ör. RTX 50xx) bu torch sürümüyle desteklenmeyebilir. Cihaz seçimi CPU ise kurulum yapılır ama HoVer-NeXt çalışmaz.')],
    [id:'classpose', label:workshopText('script.2d34aca978cc', 'Classpose — H&E hücre fenotipleme (Cellpose-SAM)'), python:'3.13',
     packages:['classpose @ https://github.com/sohmandal/classpose/archive/f6aeadd54729f6b9e50db620af68616f202d7a72.zip',
               'cellpose==4.0.8', 'torch', 'torchvision'],
     torchBackend:'auto', reuseOfficial:null,
     note:workshopText('script.1c51a56a3693', '⚠️ Yalnızca Python ortamı (~6 GB; GPU torch). Classpose KODU (CC BY-NC 4.0, sabit commit f6aeadd) bu ortama çalışma anında kurulur — atölyede paketlenmez; sihirbaz onu ayrı süreç olarak çalıştırır. Model dosyaları (Hugging Face classpose/classpose, ~1.2 GB/model) ve isteğe bağlı GrandQC ağırlıkları sihirbazdan indirilir ya da yerel klasörden kullanılır. Python 3.13 gerekir (uv kendisi indirir). Windows\'ta GPU için sihirbaz TORCHDYNAMO_DISABLE=1 ayarlar (Triton yok). cellpose 4.0.8\'e sabitlenir (bu commit yeni cellpose ile açılmaz); yayımlanan paket alt modülleri atladığından her ortam kurulumundan sonra sihirbazın ① adımında bir kez "Kurulumu tamamla" gerekir.')],
    [id:'cytoformer', label:workshopText('script.e0c715b1ff8f', 'CytoFormer — H&E hücre tipi sınıflama (ViT-g; NVIDIA GPU önerilir)'), python:'3.11',
     packages:['torch>=2.1','torchvision>=0.16','timm>=1.0.9,<1.1','numpy>=1.24','pandas>=2.0','pyarrow>=12.0','Pillow>=9.5','huggingface_hub>=0.23'],
     torchBackend:'auto', reuseOfficial:null,
     note:workshopText('script.0c062d406eb1', '⚠️ Yalnızca Python ortamı (~5 GB; GPU torch). CytoFormer KODU (PENN Academic Software License — yalnız dahili araştırma/ticari olmayan kullanım, yeniden dağıtım yok) sihirbazda lisans onayından SONRA sabit commit\'ten (36b667e) veri köküne indirilir; atölyede paketlenmez, ayrı süreç olarak çalışır. Ağırlık (Hugging Face zhihuanglab/CytoFormer, KAPILI, CC BY-NC 4.0, 2.7 GB) yerel klasörden kullanılır ya da HF girişiyle indirilir. torchvision>=0.16 BİLEREK açıktır: upstream requirements.txt torchvision\'ı listelemez, timm onu sınırsız çeker ve uv 0.2.0\'a (2018) çözebilir → "torchvision.ops" hatası. timm 1.1 altında tutulur (mimariyi timm kurar; 1.0.29 doğrulandı). NVIDIA GPU yoksa ortamı cihaz = CPU ile kurun: CUDA torch\'u CPU kipine zorlamak Windows\'ta 0xC0000005 ile çöktü; CPU tekerleği sorunsuz (~1.5–2 hücre/sn).')],
    [id:'histoplus', label:workshopText('script.33f0312f3c7c', 'HistoPLUS — H&E hücre segmentasyonu + 13 (+1) sınıf (CellViT/H0-mini; NVIDIA GPU önerilir)'), python:'3.12',
     packages:['histoplus @ https://github.com/owkin/histoplus/archive/576b94e528791c9f22c4d755bee01ec9a5743558.zip',
               'torch==2.7.1','torchvision==0.22.1','xformers==0.0.31.post1','timm==1.0.8',
               'numpy==2.2.6','pandas==2.3.1','opencv-python-headless==4.12.0.88','scikit-image==0.25.2','shapely==2.1.1',
               'huggingface-hub==0.33.4','joblib==1.5.1','scipy==1.15.3','pillow==11.3.0',
               'openslide-python==1.4.2','openslide-bin==4.0.0.8','typer==0.16.0','pyyaml==6.0.2','tqdm==4.67.1','loguru==0.7.3'],
     torchBackend:'auto', cpuStandardWheel:true, reuseOfficial:null,
     note:workshopText('script.92019aaeec71', '⚠️ Yalnızca Python ortamı (~6 GB; GPU torch). HistoPLUS KODU (Owkin, CC BY-NC-ND 4.0 — ticari olmayan, türev PAYLAŞILMAZ) sabit commit\'ten (576b94e) bu ortama çalışma anında kurulur; atölyede paketlenmez ve DEĞİŞTİRİLMEZ. Ağırlıklar (Hugging Face Owkin-Bioptimus/histoplus, KAPILI, ~285 MB × 2) sihirbazın ② adımından kendi HF hesabınızla indirilir ya da yerel klasörden kullanılır. Sürümler upstream uv.lock\'a SABİTTİR (sabitlenmezse uv pandas 3 / OpenCV 5 çözer; upstream kodu bunlarla denenmedi). xformers torch 2.7.1\'e bağlıdır: CUDA 12.6/12.8 indeksinde var, CUDA 11.8\'de YOK (eski NVIDIA sürücüsü → sürücüyü güncelleyin ya da cihaz = CPU ile kurun). Cihaz = CPU ise standart PyPI tekerleği kullanılır (CPU indeksinde xformers yok). CPU çıkarımı çalışır ama çok yavaştır (~7 dk/mm²); GPU (RTX A4000) ~12 sn/mm².')],
    [id:'valis', label:workshopText('script.020227967a6f', 'VALIS — WSI hizalama (native; JDK/Java AYRICA gerekir)'), python:'3.10',
     packages:['valis-wsi', 'pyvips[binary]', 'openslide-python', 'openslide-bin'], torchBackend:'auto', reuseOfficial:null,
     note:workshopText('script.651fd7a2f468', '⚠️ Yalnız NATIVE mod içindir. valis-wsi + pyvips[binary] (libvips ikilisi) + openslide-python & openslide-bin (OpenSlide ikilisi → .svs/.ndpi HIZLI okunur; yoksa VALIS yavaş Bio-Formats yoluna düşer) pip ile kurulur; torch CUDA wheel\'i --torch-backend=auto ile otomatik seçilir (RTX A4000; GPU özellik-eşleştirmeyi hızlandırır — LAM OKUMA/DÖNÜŞTÜRME ise disk/IO bağımlıdır, GPU kullanmaz). AYRICA bir JDK (Bio-Formats/JPype) gerekir — sistemde Java varsa yeterlidir. ÖNERİLEN yol: Docker (cdgatenbee/valis-wsi; tüm bağımlılıklar hazır). Bkz. Kaynaklar → İleri kurulumlar → VALIS. Lisans: VALIS = MIT.')],
]
def specById = { String id -> CATALOG.find { it.id == id } }

// ── venv yol yardımcıları ────────────────────────────────────────────────────
def venvDirOf  = { String id -> new File(new File(runtimesDir(), id), '.venv') }
def venvPython = { String id -> def v = venvDirOf(id); IS_WIN ? new File(v, 'Scripts/python.exe') : new File(v, 'bin/python') }

// Resmî eklenti ortamını tara: <kullanıcı>/QuPath/v*/<name>/.venv
def detectOfficial = { String name ->
    def base = new File(System.getProperty('user.home'), 'QuPath')
    if (!base.isDirectory()) return null
    def vdirs = base.listFiles({ f -> f.isDirectory() && f.getName().startsWith('v') } as java.io.FileFilter)
    if (vdirs == null) return null
    def cands = []
    vdirs.sort { it.getName() }.each { vd ->
        def rt = new File(vd, name + '/.venv')
        def w = new File(rt, 'Scripts/python.exe'); def n = new File(rt, 'bin/python')
        if (w.isFile()) cands << w else if (n.isFile()) cands << n
    }
    return cands.isEmpty() ? null : cands.last().getAbsolutePath()
}
def statusOf = { spec ->
    if (spec.reuseOfficial) { def off = detectOfficial(spec.reuseOfficial); if (off != null) return [state:'official', python:off] }
    def py = venvPython(spec.id); if (py.isFile()) return [state:'installed', python:py.getAbsolutePath()]
    return [state:'missing', python:null]
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('LIST')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def processRef    = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef   = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def selectedDeviceRef = new java.util.concurrent.atomic.AtomicReference('auto')   // 'auto'|'cuda'|'mps'|'cpu'

// ── Çağıran sihirbaza dönüş (isteğe bağlı) ───────────────────────────────────
// Bir sihirbaz bu yöneticiyi kendi GroovyShell'inde `atolyeReturnHook` bağlamasıyla açabilir:
// [envId: 'kimlik' | envIds: [...], wizard: 'görünen ad', onReturn: { reopen -> … }].
// Varsa: ilgili ortam satırı vurgulanır, "Sihirbaza dön ▶" düğmesi görünür ve bu pencere
// kapanınca sihirbaz öne gelip yapılandırmasını yeniden okur. Menüden açılışta bağlama yoktur.
def returnHook = null
try { if (binding?.hasVariable('atolyeReturnHook')) returnHook = binding.getVariable('atolyeReturnHook') } catch (Throwable ignore) {}
def returnWantedIds = (returnHook == null) ? [] :
    (returnHook.envIds ? returnHook.envIds.collect { it?.toString() } : (returnHook.envId ? [returnHook.envId.toString()] : []))
def returnedRef = new java.util.concurrent.atomic.AtomicBoolean(false)
def notifyWizard = { boolean reopen ->
    if (returnHook == null || returnedRef.getAndSet(true)) return
    try { returnHook.onReturn?.call(reopen) } catch (Throwable ignore) {}
}
def returnToWizard = { -> notifyWizard(true); if (stage != null) stage.close() }

// ── Hızlandırıcı (GPU) algıla ve torch-backend'e çevir ───────────────────────
// Apple Silicon → mps; nvidia-smi çalışıyorsa → cuda; yoksa cpu.
// BİR KEZ algılanır ve önbelleğe alınır — render() (FX iş parçacığı) içinden
// çağrılsa bile `nvidia-smi` süreci tekrar tekrar başlatılmaz. Çıktı DISCARD'a
// yönlendirilir (asılı akışta bloklanmayı önler); waitFor zaman aşımıyla sınırlı.
def _accelCache = new java.util.concurrent.atomic.AtomicReference(null)
def detectAccelerator = { ->
    def cached = _accelCache.get()
    if (cached != null) return cached
    def result = 'cpu'
    def osn  = System.getProperty('os.name', '').toLowerCase(java.util.Locale.ROOT)
    def arch = System.getProperty('os.arch', '').toLowerCase(java.util.Locale.ROOT)
    if (osn.contains('mac') && (arch.contains('aarch64') || arch.contains('arm'))) {
        result = 'mps'
    } else {
        def p = null
        try {
            p = new ProcessBuilder('nvidia-smi')
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            if (p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                if (p.exitValue() == 0) result = 'cuda'
            } else { try { p.destroyForcibly() } catch (Throwable ignore) {} }
        } catch (Throwable t) {
            try { if (p != null) p.destroyForcibly() } catch (Throwable ig) {}
        }
    }
    _accelCache.set(result)
    return result
}
// Seçilen cihazı uv --torch-backend değerine çevir (yalnız torch İÇEREN specler için).
// cuda → spec'in AÇIK CUDA arka ucu varsa o (ör. 'cu121': eski torch'a sabitlenmiş ortamlar — 'auto'
// yeni sürücüde yalnız yeni torch barındıran indeksi seçip sabit sürümü bulamayabilir), yoksa 'auto'
// (uv CUDA sürümünü seçer); cpu → 'cpu'; mps → null (mac wheel'de MPS gömülü).
def effectiveTorchBackend = { spec ->
    if (spec.torchBackend == null) return null
    def dev = selectedDeviceRef.get() ?: 'auto'
    if (dev == 'auto') dev = detectAccelerator()
    // cpuStandardWheel: CPU'da PyTorch CPU indeksi YERİNE standart PyPI tekerleği. xformers PyTorch CPU
    // indeksinde yoktur (histoplus: 'torch==2.7.1 + xformers' --torch-backend=cpu ile ÇÖZÜLEMEZ); PyPI'ın
    // Windows torch tekerleği zaten yalnız-CPU'dur (Linux'ta CUDA'lı ama CPU'da da çalışır).
    if (dev == 'cpu') return spec.cpuStandardWheel ? null : 'cpu'
    if (dev == 'mps') return null
    def explicit = spec.torchBackend.toString()
    return explicit.startsWith('cu') ? explicit : 'auto'
}
def render

def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text); b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip)); return b
}
def busyBar = { -> def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb }
def copyToClipboard = { String txt ->
    def cb = javafx.scene.input.Clipboard.getSystemClipboard()
    def c = new javafx.scene.input.ClipboardContent(); c.putString(txt ?: ""); cb.setContent(c)
}
def openFolder = { File f -> try { if (f != null && f.isDirectory() && java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(f) } catch (Throwable ignore) {} }

// ── Süreç akışı ──────────────────────────────────────────────────────────────
def runProc = { List cmd, Closure onLine ->
    def pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true)
    // uv/pip tekerlek (wheel) önbelleğini de veri köküne yönlendir — aksi hâlde
    // uv, wheel'leri ~/.cache/uv (C:) altında tutar ve GB'larca yer kaplar.
    try {
        def cacheDir = new File(atolyeDataRoot(), 'uv-cache'); cacheDir.mkdirs()
        def env = pb.environment()
        env.put('UV_CACHE_DIR', cacheDir.getAbsolutePath())
        env.put('PIP_CACHE_DIR', new File(atolyeDataRoot(), 'pip-cache').getAbsolutePath())
    } catch (Throwable ignore) {}
    def proc; try { proc = pb.start() } catch (Throwable e) { return [ok:false, exitCode:-1, error:workshopText('script.71f0bfaced36', 'Başlatılamadı: ') + (e.getMessage() ?: e.getClass().getSimpleName())] }
    processRef.set(proc)
    def last = new java.util.ArrayDeque()
    try {
        def r = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
        String line
        while ((line = r.readLine()) != null) { last.addLast(line); while (last.size() > 200) last.pollFirst(); onLine(line); if (cancelledRef.get()) break }
        r.close()
    } catch (Throwable ignore) {}
    boolean fin
    try { fin = proc.waitFor(UV_TIMEOUT, java.util.concurrent.TimeUnit.SECONDS) }
    catch (InterruptedException ie) { proc.destroyForcibly(); return [ok:false, exitCode:-3, error:workshopText('script.dbd417da03e1', 'İptal edildi')] }
    if (!fin) { proc.destroyForcibly(); return [ok:false, exitCode:-2, error:workshopText('script.e6b0c32aaae9', 'Zaman aşımı (') + UV_TIMEOUT + workshopText('script.2ccf3f4af0dd', ' sn)')] }
    if (cancelledRef.get()) { proc.destroyForcibly(); return [ok:false, exitCode:-3, error:workshopText('script.dbd417da03e1', 'İptal edildi')] }
    int code = proc.exitValue(); return [ok: code == 0, exitCode: code, lastLines: last.join('\n')]
}

// uv'yi bul; yoksa indir + aç
def findUv = { ->
    def local = new File(uvDir(), uvExeName); if (local.isFile()) return local.getAbsolutePath()
    try {
        def cmd = IS_WIN ? ['where', 'uv'] : ['which', 'uv']
        def p = new ProcessBuilder(cmd).redirectErrorStream(true).start()
        def out = p.getInputStream().getText('UTF-8'); p.waitFor()
        if (p.exitValue() == 0) { def first = out.readLines().find { it?.trim() }; if (first) { def f = new File(first.trim()); if (f.isFile()) return f.getAbsolutePath() } }
    } catch (Throwable ignore) {}
    return null
}
def ensureUv = { Closure appendLine ->
    def existing = findUv()
    if (existing) { appendLine(workshopText('script.fc63b1dc0092', 'uv bulundu: ') + existing); return [ok:true, uv:existing] }
    def asset = uvAsset()
    appendLine(workshopText('script.3e02575de325', 'uv indiriliyor: ') + UV_BASE + asset)
    def ud = uvDir(); ud.mkdirs()
    def dl = new File(ud, asset)
    try {
        def client = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(30)).build()
        def req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(UV_BASE + asset)).GET().build()
        def resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofFile(dl.toPath()))
        if (resp.statusCode() != 200) return [ok:false, error:String.format(java.util.Locale.US, workshopText('script.7c39556c4fd4', 'uv indirilemedi (HTTP %d). Çevrimdışıysanız uv\'yi elle %s içine koyun.'), resp.statusCode(), ud.getAbsolutePath())]
    } catch (Throwable t) { return [ok:false, error:workshopText('script.f34a01259ab7', 'uv indirme hatası: ') + (t.getMessage() ?: t.getClass().getSimpleName())] }
    try {
        if (asset.endsWith('.zip')) {
            def zf = new java.util.zip.ZipFile(dl)
            try {
                zf.entries().each { e ->
                    if (e.isDirectory()) return
                    def nm = new File(e.getName()).getName()
                    if (!nm) return
                    def o = new File(ud, nm)
                    zf.getInputStream(e).withStream { is -> o.withOutputStream { os -> os << is } }
                }
            } finally {
                zf.close()
            }
        } else {
            def p = new ProcessBuilder(['tar','-xzf', dl.getAbsolutePath(), '-C', ud.getAbsolutePath(), '--strip-components=1']).redirectErrorStream(true).start()
            p.getInputStream().getText('UTF-8'); p.waitFor()
        }
    } catch (Throwable t) { return [ok:false, error:workshopText('script.e3cc1a23f120', 'uv açma hatası: ') + (t.getMessage() ?: t.getClass().getSimpleName())] }
    def uv = new File(ud, uvExeName)
    if (!uv.isFile()) return [ok:false, error:workshopText('script.422186e8271e', 'uv açıldı ama bulunamadı: ') + uv.getAbsolutePath()]
    if (!IS_WIN) { try { uv.setExecutable(true) } catch (Throwable ignore) {} }
    appendLine(workshopText('script.dd43b83e357f', 'uv hazır: ') + uv.getAbsolutePath())
    return [ok:true, uv:uv.getAbsolutePath()]
}

// Paket adından Python import adını türet (scikit-learn→sklearn, Pillow→PIL, ...)
def importNameOf = { String pkg ->
    // PEP 508 doğrudan başvuru ("paket @ https://…zip") → yalnız paket adı; URL import edilmeye çalışılmasın.
    def b = pkg.replaceAll(/\s*@.*$/, '').replaceAll(/[<>=!~\[].*$/, '').trim().toLowerCase(java.util.Locale.ROOT)
    if (b == 'scikit-learn') return 'sklearn'
    if (b == 'scikit-image') return 'skimage'
    if (b == 'pillow') return 'PIL'
    if (b == 'opencv-python' || b == 'opencv-python-headless') return 'cv2'
    if (b == 'openslide-python') return 'openslide'
    if (b == 'pyyaml') return 'yaml'
    if (b == 'simpleitk') return 'SimpleITK'      // import adı büyük/küçük harfe duyarlı
    if (b == 'pylibczirw') return 'pylibCZIrw'
    if (b == 'valis-wsi') return 'valis'          // dağıtım adı valis-wsi, modül adı valis
    return b.replace('-', '_')
}

def installEnv = { spec, String uvPath, Closure appendLine ->
    def venv = venvDirOf(spec.id); venv.getParentFile().mkdirs()
    appendLine(''); appendLine(String.format(java.util.Locale.US, workshopText('script.0fbbebc82ace', '── %s — venv oluşturuluyor (Python %s) ──'), spec.id, spec.python))
    // Güncel uv, VAR OLAN bir venv üzerinde `uv venv` çalıştırılınca hata (çıkış 2) verir →
    // "Onar / güncelle" ve yarım kalmış kurulumun yeniden denenmesi ilk adımda düşüyordu.
    // --allow-existing mevcut ortamı korur ve devam eder.
    def venvCmd = [uvPath, 'venv', venv.getAbsolutePath(), '--python', spec.python]
    if (venv.isDirectory() && new File(venv, 'pyvenv.cfg').isFile()) venvCmd << '--allow-existing'
    def r1 = runProc(venvCmd, appendLine)
    if (!r1.ok) return r1
    if (cancelledRef.get()) return [ok:false, error:workshopText('script.dbd417da03e1', 'İptal edildi')]
    def py = venvPython(spec.id).getAbsolutePath()
    def base = [uvPath, 'pip', 'install', '--python', py]
    // Seçilen cihaza göre torch-backend: cuda→'auto', cpu→'cpu', mps→null (mac wheel).
    def bk = effectiveTorchBackend(spec)
    def devSel = selectedDeviceRef.get() ?: 'auto'
    def devEff = (devSel == 'auto') ? detectAccelerator() : devSel
    appendLine(''); appendLine(workshopText('script.b0bef6c4115d', '── paketler kuruluyor: ') + spec.packages.join(', ') +
        (spec.torchBackend != null ? (workshopText('script.c5c8a8b6e944', '  [cihaz=') + devEff + (bk ? (', torch-backend=' + bk) : workshopText('script.99791a107787', ', standart wheel')) + ']') : '') + ' ──')
    def r2
    if (bk) {
        // 1) uv --torch-backend. Eski uv tanımazsa cihaza uygun --extra-index-url'e düş.
        def cmd = new ArrayList(base); cmd.add('--torch-backend=' + bk); cmd.addAll(spec.packages)
        r2 = runProc(cmd, appendLine)
        if (!r2.ok && !cancelledRef.get()) {
            def ll = (r2.lastLines ?: '').toLowerCase(java.util.Locale.ROOT)
            if (ll.contains('torch-backend') && (ll.contains('unexpected') || ll.contains('unrecognized') || ll.contains('invalid value') || ll.contains('found argument'))) {
                def idxName = (bk == 'cpu') ? 'cpu' : (bk == 'auto' ? 'cu128' : bk)   // açık arka uç (ör. cu121) korunur
                def idx = 'https://download.pytorch.org/whl/' + idxName
                appendLine(''); appendLine(String.format(java.util.Locale.US, workshopText('script.15de4c41fb2d', 'uv bu sürüm --torch-backend desteklemiyor; --extra-index-url (%s) ile yeniden deneniyor…'), idxName))
                def cmd2 = new ArrayList(base)
                cmd2.add('--index-strategy'); cmd2.add('unsafe-best-match')
                cmd2.add('--extra-index-url'); cmd2.add(idx)
                cmd2.addAll(spec.packages)
                r2 = runProc(cmd2, appendLine)
            }
        }
    } else {
        def cmd = new ArrayList(base); cmd.addAll(spec.packages)
        r2 = runProc(cmd, appendLine)
    }
    if (!r2.ok) return r2
    // 2) Kurulum sonrası doğrulama: anahtar paketleri import et (kırık venv'i hemen yakala)
    def imports = spec.packages.collect { importNameOf(it) }.unique()
    boolean verifyOk = true; String verifyMsg = ''
    if (!imports.isEmpty() && !cancelledRef.get()) {
        appendLine(''); appendLine(workshopText('script.9ac2a8f950cd', '── doğrulama: import ') + imports.join(', ') + ' ──')
        // NOT: Python tek-satırında ÇİFT tırnak kullanma. Windows'ta Java ProcessBuilder,
        // boşluk içeren argümanı "..." ile sarar ve içteki çift tırnakları KAÇIRMAZ; böylece
        // print("VERIFY_OK") → print(VERIFY_OK) olur (NameError). Tek tırnak güvenlidir.
        def vr = runProc([py, '-c', 'import ' + imports.join(', ') + "; print('VERIFY_OK')"], appendLine)
        verifyOk = vr.ok && (vr.lastLines ?: '').contains('VERIFY_OK')
        if (!verifyOk) verifyMsg = (vr.lastLines ?: '')
    }
    try {
        def marker = new File(venv.getParentFile(), '.atolye-installed.json')
        marker.setText(qupath.lib.io.GsonTools.getInstance(true).toJson(
            [id: spec.id, python: spec.python, packages: spec.packages, torchBackend: spec.torchBackend, verifyOk: verifyOk]), 'UTF-8')
    } catch (Throwable ignore) {}
    // Kurulan python'un TAM yolunu paylaşılan düğüme yaz — ilgili sihirbazlar
    // veri kökünü "tahmin etmek" yerine bu KESİN adresi okur (veri kökü sonradan
    // değişse bile kurulu ortam bulunur). Anahtar: py.<id>.
    try { commonPrefs.put('py.' + spec.id, py); commonPrefs.flush() } catch (Throwable ignore) {}
    return [ok:true, python: py, verifyOk: verifyOk, verifyMsg: verifyMsg, imports: imports]
}

def startInstall = { spec ->
    // Torch (çok-GB) ortamları için disk uyarısı + onay
    if (spec.torchBackend) {
        def rtDir = runtimesDir()
        try { rtDir.mkdirs() } catch (Throwable ignore) {}
        long freeBytes = 0L; try { freeBytes = rtDir.getUsableSpace() } catch (Throwable ignore) {}
        double freeGB = freeBytes / (1024.0d * 1024.0d * 1024.0d)
        def msg = spec.label + workshopText('script.3660f91f9a89', ' ortamı torch + CUDA içerir → yaklaşık 3–5 GB indirilir ve kurulur.\n\n') +
            String.format(java.util.Locale.US, workshopText('script.2790dec6086b', 'Hedef    : %s%nBoş alan : %.1f GB%s%n%nDevam edilsin mi?'),
                rtDir.getAbsolutePath(), freeGB, (freeGB > 0 && freeGB < 6.0d ? workshopText('script.4f8829f7c825', '  ⚠ düşük (≥6 GB önerilir)') : ''))
        if (!Dialogs.showConfirmDialog(workshopText('script.4b9fa7c7f11c', 'Disk alanı — ') + spec.id, msg)) return
    }
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set(spec.label + workshopText('script.199730d07c80', ' kuruluyor…')); step.set('RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        try {
            def u = ensureUv(appendLine)
            if (!u.ok) { javafx.application.Platform.runLater { errorTextRef.set(u.error); step.set('ERROR'); render() }; return }
            if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set(workshopText('script.fee85e45842b', 'İptal edildi.')); step.set('ERROR'); render() }; return }
            def r = installEnv(spec, u.uv, appendLine)
            javafx.application.Platform.runLater {
                if (!r.ok) { errorTextRef.set(workshopText('script.ab28a12df1b5', 'Kurulum başarısız (çıkış: ') + (r.exitCode ?: '?') + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '')); step.set('ERROR'); render() }
                else {
                    def sb = new StringBuilder()
                    sb << workshopText('script.8f4d46bf4631', 'ORTAM KURULDU ✅\n══════════════\n\n')
                    sb << workshopText('script.e98230a62c29', 'Ortam   : ') << spec.label << ' (' << spec.id << ')\n'
                    sb << workshopText('script.f29279f64470', 'Python  : ') << r.python << '\n'
                    sb << workshopText('script.eab789b1b897', 'Paketler: ') << spec.packages.join(', ') << '\n'
                    sb << workshopText('script.7ef2bd801a3a', 'Doğrulama: ') << (r.verifyOk ? (workshopText('script.83683c92bbae', 'import OK — ') + (r.imports ? r.imports.join(', ') : '')) : (workshopText('script.e72f06373c60', '⚠ BAŞARISIZ:\n') + (r.verifyMsg ?: ''))) << '\n'
                    if (spec.note) sb << workshopText('script.1c6626275c2e', '\nNot: ') << spec.note << '\n'
                    sb << workshopText('script.0540f3c2e7d6', '\nİlgili sihirbaz artık bu ortamı otomatik bulur.\n⚠️ Yalnızca araştırma/eğitim amaçlıdır.')
                    resultTextRef.set(sb.toString()); step.set('RESULT'); render()
                }
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set(workshopText('script.6af403a9fc32', 'Beklenmeyen hata:\n') + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render() }
        }
    }, 'AtolyeEnv-Install')
    worker.setDaemon(true); worker.start()
}

// ── Headless ────────────────────────────────────────────────────────────────
if (isHeadless) {
    println "Atölye Python ortam yöneticisi — durum:"
    println "  uv: " + (findUv() ?: '(yok — kurulumda indirilecek)')
    CATALOG.each { spec -> def st = statusOf(spec); println String.format(java.util.Locale.US, "  %-18s %s", spec.id, (st.state == 'official' ? 'resmî ortam: ' + st.python : (st.state == 'installed' ? 'kurulu: ' + st.python : 'kurulu değil'))) }
    println "Ortam yöneticisi için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlıdır."
    return
}

// ── Render ───────────────────────────────────────────────────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def title = new javafx.scene.control.Label(); title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10); center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)
    def actions = new ArrayList()
    // Sarma (wrap): setMaxWidth(MAX_VALUE) tek başına yetmiyor (kapsayıcı VBox pencereden
    // geniş olabiliyor). Etiketin maxWidth'ini SAHNE genişliğine bağla — sahne daima pencere
    // içi genişliğidir, böylece etiket her koşulda pencerede sarılır.
    def wrapBind = { javafx.scene.control.Label lbl ->
        lbl.setWrapText(true)
        lbl.sceneProperty().addListener({ obs, o, sc ->
            if (sc != null) { try { lbl.maxWidthProperty().unbind() } catch (Throwable ig) {}; lbl.maxWidthProperty().bind(sc.widthProperty().subtract(38)) }
        } as javafx.beans.value.ChangeListener)
    }
    def addMonoArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: ''); ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(ta)
    }
    def addLiveLog = { -> def la = logAreaRef.get(); if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) } }

    if (cur == 'LIST') {
        title.setText(workshopText('script.60371b636d3f', 'Atölye Python ortam yöneticisi'))
        def uvFound = findUv()
        def uvLbl = new javafx.scene.control.Label('uv: ' + (uvFound ?: workshopText('script.7950c42fa329', '(yok — ilk kurulumda otomatik indirilecek)')))
        uvLbl.setStyle('-fx-opacity: 0.8;'); center.getChildren().add(uvLbl)

        // ── Veri kökü (venv/uv/model) — C: dolmasın diye başka sürücü seçilebilir ──
        def dataRoot = atolyeDataRoot()
        boolean isCustom = false
        try { isCustom = (commonPrefs.get(PREF_DATA_ROOT, '') ?: '').trim() as boolean } catch (Throwable ignore) {}
        long drFree = 0L; try { drFree = dataRoot.exists() ? dataRoot.getUsableSpace() : (dataRoot.getParentFile()?.getUsableSpace() ?: 0L) } catch (Throwable ignore) {}
        double drFreeGB = drFree / (1024.0d * 1024.0d * 1024.0d)
        def rootLbl = new javafx.scene.control.Label(String.format(java.util.Locale.US,
            workshopText('script.5559b201977a', 'Veri kökü: %s%s   (boş alan: %.1f GB)'), dataRoot.getAbsolutePath(), (isCustom ? '' : workshopText('script.3df35642de06', '  [varsayılan]')), drFreeGB))
        rootLbl.setStyle('-fx-opacity: 0.85; -fx-font-weight: bold;'); rootLbl.setWrapText(true)
        def chooseRoot = navButton(workshopText('script.18b5fd32f00e', 'Değiştir…'), {
            def start = dataRoot.exists() ? dataRoot : dataRoot.getParentFile()
            def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, workshopText('script.e9cfd7d981ba', 'Atölye veri klasörü — içine bir ".atolye" klasörü oluşturulur'), start)
            if (x != null) {
                // Seçilen klasörü PARENT kabul et; içine '.atolye' koy (varsayılan ~/.atolye
                // ile tutarlı — sürücü kökü D:\ seçilirse D:\.atolye olur; doğrudan D:\ kirlenmez).
                // Zaten '.atolye' adlı bir klasör seçildiyse tekrar ekleme (yeniden seçimde idempotent).
                def target = '.atolye'.equalsIgnoreCase(x.getName()) ? x : new File(x, '.atolye')
                // Yazılabilirlik ön-kontrolü — salt-okunur ağ paylaşımı / yazma korumalı
                // sürücü seçilirse, geç ve anlaşılmaz bir uv/pip hatası yerine burada uyar.
                boolean writable = false
                try {
                    if (!target.exists()) target.mkdirs()
                    def t = new File(target, '.atolye-write-test.tmp')
                    t.text = 'ok'; writable = t.isFile(); try { t.delete() } catch (Throwable ig2) {}
                } catch (Throwable pe) { writable = false }
                if (!writable) {
                    Dialogs.showErrorMessage(workshopText('script.40d2b21bb73f', 'Veri kökü'), workshopText('script.a0681813792e', 'Seçilen klasöre yazılamıyor:\n') + target.getAbsolutePath() +
                        workshopText('script.a770f27be3ab', '\n\nYazma izni olan bir klasör seçin (salt-okunur ağ paylaşımı / korumalı sürücü olmasın).'))
                    return
                }
                commonPrefs.put(PREF_DATA_ROOT, target.getAbsolutePath()); try { commonPrefs.flush() } catch (Throwable ig) {}
                render()
            }
        }, workshopText('script.7d15e596a957', 'Yeni kurulumların yazılacağı klasörü seç — içine ".atolye" oluşturulur (ör. D:\\ → D:\\.atolye)'))
        def resetRoot = navButton(workshopText('script.7ba9c1127860', '↺ Varsayılan'), {
            commonPrefs.remove(PREF_DATA_ROOT); try { commonPrefs.flush() } catch (Throwable ig) {}
            render()
        }, workshopText('script.f93627b70ee6', 'Veri kökünü ~/.atolye yap'))
        rootLbl.setMaxWidth(Double.MAX_VALUE); javafx.scene.layout.HBox.setHgrow(rootLbl, javafx.scene.layout.Priority.ALWAYS)
        def rootRow = new javafx.scene.layout.HBox(8, rootLbl, chooseRoot); rootRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        if (isCustom) rootRow.getChildren().add(resetRoot)
        center.getChildren().add(rootRow)
        def rootHint = new javafx.scene.control.Label(
            workshopText('script.9310c554582d', 'Tüm atölye Python ortamları, uv/pip önbelleği ve indirilen model ağırlıkları bu köke yazılır (Kaiko/SPIDER/GrandQC/TIA/Sectra ortak). ' +
            'Seçtiğiniz klasörün İÇİNE bir ".atolye" klasörü oluşturulur (ör. D:\\ seçerseniz kök D:\\.atolye olur). ' +
            'Değiştirmek YALNIZ yeni kurulumları etkiler — mevcut ortamlar eski yerinde kalır (taşınmaz); gerekirse ilgili ortamı yeniden kurun. ' +
            '(cellpose/resmî TIAToolbox kendi klasörlerini kullanır; bunları bu ayar taşımaz.)'))
        wrapBind(rootHint); rootHint.setStyle('-fx-opacity: 0.7; -fx-font-size: 11px;')
        center.getChildren().add(rootHint)

        // ── Cihaz (torch hızlandırıcı) seçimi — GPU otomatik algılanır ──────────
        def detected = detectAccelerator()
        def isMac = System.getProperty('os.name', '').toLowerCase(java.util.Locale.ROOT).contains('mac')
        def devOpts = []
        devOpts << ['auto', workshopText('script.9da8faf54d93', 'Otomatik (algılanan: ') + detected.toUpperCase(java.util.Locale.ROOT) + ')']
        if (isMac) devOpts << ['mps', 'MPS — Apple GPU']
        else devOpts << ['cuda', 'CUDA — NVIDIA GPU']
        devOpts << ['cpu', workshopText('script.b39fc37f7246', 'CPU — GPU yok (yavaş)')]
        def devBox = new javafx.scene.control.ComboBox(javafx.collections.FXCollections.observableArrayList(devOpts.collect { it[1] }))
        int curIdx = devOpts.findIndexOf { it[0] == (selectedDeviceRef.get() ?: 'auto') }; if (curIdx < 0) curIdx = 0
        devBox.getSelectionModel().select(curIdx)
        devBox.setOnAction({ int i = devBox.getSelectionModel().getSelectedIndex(); if (i >= 0) selectedDeviceRef.set(devOpts[i][0]) })
        def devRow = new javafx.scene.layout.HBox(8, new javafx.scene.control.Label(workshopText('script.9184719c5f61', 'Cihaz (torch):')), devBox)
        devRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        center.getChildren().add(devRow)
        def devHint = new javafx.scene.control.Label(
            workshopText('script.83d2e0caaddf', 'torch içeren ortamlar bu cihaza göre kurulur: CUDA→GPU wheel, MPS→Apple wheel, CPU→cpu wheel. ' +
            'nvidia-smi bulunursa CUDA otomatik algılanır. (Modeli çalıştırma cihazı ilgili sihirbazda seçilir.)'))
        wrapBind(devHint); devHint.setStyle('-fx-opacity: 0.7; -fx-font-size: 11px;')
        center.getChildren().add(devHint)

        def listBox = new javafx.scene.layout.VBox(8)
        CATALOG.each { spec ->
            def st = statusOf(spec)
            def row = new javafx.scene.layout.VBox(2)
            row.setStyle('-fx-border-color: -fx-box-border; -fx-border-width: 0 0 1 0; -fx-padding: 6 2 6 2;')
            def head = new javafx.scene.layout.HBox(8); head.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            def name = new javafx.scene.control.Label(spec.label); name.setStyle('-fx-font-weight: bold;')
            // Çağıran sihirbazın ihtiyaç duyduğu ortam(lar)ı vurgula (sihirbazdan açıldıysa).
            if (returnWantedIds.contains(spec.id)) { name.setText(spec.label + '   ← ' + (returnHook.wizard ?: workshopText('script.63605651e451', 'sihirbaz'))); row.setStyle(row.getStyle() + ' -fx-background-color: rgba(46,139,87,0.12);') }
            def chip = new javafx.scene.control.Label(
                st.state == 'official'  ? workshopText('script.a4964849d915', '✓ resmî ortam kullanılıyor') :
                st.state == 'installed' ? workshopText('script.c6ee85e7ce6a', '✓ kurulu') : workshopText('script.2a8bd24ca869', '○ kurulu değil'))
            chip.setStyle(st.state == 'missing' ? '-fx-text-fill: #b8860b;' : '-fx-text-fill: #2e8b57;')
            def spacer = new javafx.scene.layout.Region(); javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            head.getChildren().addAll(name, spacer, chip)
            def note = new javafx.scene.control.Label(spec.note ?: ''); note.setWrapText(true); note.setMaxWidth(Double.MAX_VALUE); note.setStyle('-fx-opacity: 0.75; -fx-font-size: 11px;')
            def btnRow = new javafx.scene.layout.HBox(6); btnRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            if (st.state == 'official') {
                btnRow.getChildren().add(navButton(workshopText('script.b62b9c32e78f', 'Yine de kur'), { startInstall(spec) }, workshopText('script.f468243797ff', 'Resmî ortam yerine ayrı bir atölye ortamı kur')))
            } else if (st.state == 'installed') {
                btnRow.getChildren().add(navButton(workshopText('script.525b1ef16eb5', 'Onar / güncelle'), { startInstall(spec) }))
                btnRow.getChildren().add(navButton(workshopText('script.d6f2f6cad078', 'Klasörü aç'), { openFolder(venvDirOf(spec.id).getParentFile()) }))
            } else {
                btnRow.getChildren().add(navButton(workshopText('script.80421ba7de79', 'Kur ▶'), { startInstall(spec) }))
            }
            row.getChildren().addAll(head, note, btnRow)
            listBox.getChildren().add(row)
        }
        def scroll = new javafx.scene.control.ScrollPane(listBox); scroll.setFitToWidth(true)
        javafx.scene.layout.VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(scroll)
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
        actions.add(navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }))
        if (returnHook != null) actions.add(navButton(workshopText('script.f2e58a613b8e', 'Sihirbaza dön ▶'), { returnToWizard() }, (returnHook.wizard ?: workshopText('script.df35cd306255', 'Sihirbaz')) + workshopText('script.d6f707e9e2c8', ' penceresine döner; kurulu ortam otomatik bulunur')))
    } else if (cur == 'RUNNING') {
        title.setText(runPhaseRef.get())
        def g = new javafx.scene.control.Label(workshopText('script.77ec5412872e', 'uv Python + paketleri indiriyor (torch ortamları birkaç dakika sürer). İlk kurulum büyük olabilir.')); wrapBind(g)
        center.getChildren().add(g); center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton(workshopText('script.3f6920a9ead3', 'İptal et'), { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'RESULT') {
        title.setText(workshopText('script.92c17020894f', 'Tamamlandı ✅')); addMonoArea(resultTextRef.get())
        actions.add(navButton(workshopText('script.0c8363f7e03c', '◀ Ortam listesi'), { step.set('LIST'); render() }))
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
        actions.add(navButton(workshopText('script.a8bcca42d9ce', 'Kopyala'), { copyToClipboard(resultTextRef.get()) }))
        if (returnHook != null) actions.add(navButton(workshopText('script.f2e58a613b8e', 'Sihirbaza dön ▶'), { returnToWizard() }, (returnHook.wizard ?: workshopText('script.df35cd306255', 'Sihirbaz')) + workshopText('script.fc8852a80983', ' penceresine döner; kurulan ortam otomatik bulunur')))
    } else { // ERROR
        title.setText(workshopText('script.307b9dc7912d', 'Hata')); addMonoArea(errorTextRef.get())
        actions.add(navButton(workshopText('script.0c8363f7e03c', '◀ Ortam listesi'), { step.set('LIST'); render() }))
        actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
        if (returnHook != null) actions.add(navButton(workshopText('script.295a63405c69', 'Sihirbaza dön'), { returnToWizard() }, (returnHook.wizard ?: workshopText('script.df35cd306255', 'Sihirbaz')) + workshopText('script.9ad61540b4dc', ' penceresine döner')))
    }

    def topChk = new javafx.scene.control.CheckBox(workshopText('script.70ca64c0c43a', 'Üstte tut')); topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n -> alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
    def spacer2 = new javafx.scene.layout.Region(); javafx.scene.layout.HBox.setHgrow(spacer2, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8); bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk); bar.getChildren().add(spacer2); bar.getChildren().addAll(actions)
    def disclaimer = new javafx.scene.control.Label(workshopText('script.b8679b53726e', 'Yalnızca Python bağımlılıkları kurar; model ağırlıklarını/lisanslarını sağlamaz. Araştırma/eğitim amaçlıdır.'))
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar); bottom.setPadding(new javafx.geometry.Insets(10))
    def root = new javafx.scene.layout.BorderPane(); root.setCenter(center); root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 820, 620))
}

// ── Açılış ────────────────────────────────────────────────────────────────────
// Hızlandırıcıyı ARKA PLAN (betik) iş parçacığında bir kez algıla/önbelleğe al —
// böylece render() (FX) içinden çağrı `nvidia-smi` başlatıp arayüzü dondurmaz.
detectAccelerator()
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle(workshopText('script.60371b636d3f', 'Atölye Python ortam yöneticisi'))
        stage.setAlwaysOnTop(alwaysTop.get())
        // Sihirbazdan açıldıysa: pencere nasıl kapanırsa kapansın sihirbaza haber ver (öne gelip yenilensin).
        stage.setOnHidden({ e -> notifyWizard(false) } as javafx.event.EventHandler)
        render(); stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.b8fa2004f496', 'Yönetici açılamadı'), t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ Atölye Python ortam yöneticisi açıldı."

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
