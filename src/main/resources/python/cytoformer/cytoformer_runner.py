#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
cytoformer_runner.py - QuPath atolyesi icin CytoFormer (H&E hucre tipi siniflama) koprusu
==========================================================================================

NE YAPAR:
    CytoFormer'i (Yao, Li, Yu, Huang; Precision Pathology 2026, doi:10.1016/j.prpath.2026.100006),
    16 organ icin egitilmis bir hucre-vakfi (cell foundation) modelini, QuPath'in disa aktardigi
    224x224 hucre yamalari (her biri bir cekirdegin 56x56 um alani, 224 piksele olceklenmis)
    uzerinde AYRI BIR SUREC olarak calistirir. Upstream'in DEGISTIRILMEMIS `scripts/infer.py`
    betigi CytoFormer kod klasorunde calistirilir; bu kopru yalniz komut satirini kurar, gunlugu
    okur, parquet ciktisini TSV'ye cevirir ve organ-disi sinif / satir-sayisi denetimi yapar.

    CytoFormer kodu (Penn Academic Software License) bu depoda/JAR'da HICBIR ZAMAN paketlenmez,
    kopyalanmaz, portlanmaz ya da yeniden uygulanmaz: 8 sabit dosya, kullanicinin acik lisans
    onayindan SONRA, GitHub'dan sabitlenmis bir commit'ten dogrudan indirilir ve sha256 ile
    dogrulanir (boylece indiren kisi lisansin RECIPIENT'i olur). Bu kopru `cytoformer` paketini
    HICBIR ZAMAN import ETMEZ.

    Agirliklar (checkpoint.pth, organ_celltype_map.json) CC-BY-NC-4.0, Hugging Face'te kapida
    (gated, otomatik onay) - `fetch-weights` kullanicinin kendi tokeniyla indirir, ya da mevcut
    yerel bir klasor (or. J:\\zhihuanglab\\CytoFormer) `verify-weights` ile dogrulanarak kullanilir.

NE URETIR (ve ne URETMEZ):
    Hucre basina bir sinif adi (organun sinif listesiyle sinirli) + olasilik (top-1 softmax).
    Katman-basi olasilik, gomme (embedding) ya da klinik yorum URETMEZ. Yalnizca arastirma/egitim.

CIKIS KODLARI:
    0 tamam | 1 genel hata | 2 yapilandirma/dosya eksik (lisans onayi, kod, agirlik) |
    3 hatali girdi (bilinmeyen organ, yama/dizin yok, satir sayisi/sinif uyusmazligi) |
    4 cihaz hatasi

KULLANIM:
    python cytoformer_runner.py selftest [--model-dir DIR] [--device auto|cuda|cpu]
    python cytoformer_runner.py fetch-code --accept-license [--overwrite]
    python cytoformer_runner.py fetch-weights --dest DIR
    python cytoformer_runner.py verify-weights --model-dir DIR [--force]
    python cytoformer_runner.py classify --patches DIR --model-dir DIR --organ colon \\
        --out sonuc.tsv --batch 64 [--device auto|cuda|cpu] [--expect N] [--keep-raw]

REFERANS:
    Yao J, Li S, Yu A, Huang Z. CytoFormer: a molecularly supervised cell foundation model for
    histopathology cell classification. Precision Pathology 1 (2026) 100006.
    doi:10.1016/j.prpath.2026.100006 (CC BY).
    Kod: https://github.com/zhihuanglab/CytoFormer (Penn Academic Software License Agreement)
    Agirlik: https://huggingface.co/zhihuanglab/CytoFormer (CC-BY-NC-4.0, gated)
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path

# ----------------------------------------------------------------------------
# Pinned upstream constants (bkz. tasarim belgesi "Exact contract" - degistirmeyin)
# ----------------------------------------------------------------------------
CODE_SHA = "36b667e40a440d0a8faea8296709aadfcffe6616"
CODE_SHA_SHORT = CODE_SHA[:12]  # "36b667e40a44" -> onbellek klasor adi
RAW_BASE = "https://raw.githubusercontent.com/zhihuanglab/CytoFormer/%s/" % CODE_SHA
MARKER_NAME = ".atolye-ok.json"

# relpath -> (sha256, boyut-bayt) - raw.githubusercontent'in verdigi LF baytlari uzerinden.
CODE_FILES = [
    ("LICENSE", "d98061ad36f16048de21bab9d72fe819dc06722fd63dda970033b487c6d4e877", 6049),
    ("README.md", "3a7b6704c325e4555ffce34a1ffd88bff0888b3a6fdb2001bb9b312544d57d4a", 6986),
    ("requirements.txt", "ddd274bf6f515a01214161c52333dd89655f48c620d9e6d93ed8c69b4e476d03", 361),
    ("cytoformer/__init__.py", "581bf394f2bd265b2b636e357e21209aa792642137b278defa420c507b933bb9", 409),
    ("cytoformer/common.py", "f6629f3d02e3fe76097026bca931d06c270b816d4d47cb1b463c7e6f2182c537", 5894),
    ("cytoformer/model.py", "4b678c169a1f73b1c3aa70c894cad179a7ea962a653b8c6647a9ef5503b45b31", 1154),
    ("cytoformer/organ_celltype_map.json", "8dc8613c48f5fcad29b263f8c5e3e4a8e4588f2f94cef7c9628c738716eeea73", 3396),
    ("scripts/infer.py", "9ab9749841b98163c2ebe76968e1566ebec81a6d7fd7c80d5d46100544658a66", 4265),
]

HF_REPO = "zhihuanglab/CytoFormer"
HF_REVISION = "3e9691e45c3bd7139059dafa8dc5da716d67801a"
CKPT_NAME = "checkpoint.pth"
CKPT_SIZE = 2726343169
CKPT_SHA256 = "e93362f4e894cad5d02cf72d944702f7fd9b95b5fc25eedf01406adf1c7b7ead"
MAP_NAME = "organ_celltype_map.json"

# Taksonomi (upstream README/organ_celltype_map.json tablosu, oldugu gibi) - yalniz DOGRULAMA
# icin gomulu; model kodu bu depoda YOK, taksonomi verisi ayri bir olgudur.
ORGANS = [
    "bone", "bone_marrow", "brain", "breast", "cervix", "colon", "heart", "kidney",
    "liver", "lung", "lymph_node", "ovary", "pancreas", "prostate", "skin", "tonsil",
]
GLOBAL_CLASSES = [
    "Acinar", "Bone_cell", "Cardiomyocyte", "Cholangiocyte", "Chondrocyte", "Ductal",
    "Endothelium", "Epithelium", "Erythroid", "Hepatocyte", "Islet", "Lymphocyte",
    "Macrophage", "Microglia", "Myeloid_progenitor", "Nerve", "Neutrophil", "Plasma_cell",
    "Renal_tubule", "Squamous_epithelium", "Stroma", "melanocytic", "tumor",
]
ORGAN_CELLTYPES = {
    "bone": ["Bone_cell", "Endothelium", "Lymphocyte", "Stroma"],
    "bone_marrow": ["Endothelium", "Erythroid", "Myeloid_progenitor", "Neutrophil", "Stroma"],
    "brain": ["Endothelium", "Microglia", "tumor"],
    "breast": ["Endothelium", "Epithelium", "Lymphocyte", "Macrophage", "Plasma_cell", "Stroma", "tumor"],
    "cervix": ["Endothelium", "Epithelium", "Lymphocyte", "Macrophage", "Plasma_cell", "Stroma", "tumor"],
    "colon": ["Endothelium", "Epithelium", "Lymphocyte", "Macrophage", "Plasma_cell", "Stroma", "tumor"],
    "heart": ["Cardiomyocyte", "Endothelium", "Macrophage", "Stroma"],
    "kidney": ["Endothelium", "Lymphocyte", "Macrophage", "Plasma_cell", "Renal_tubule", "Stroma", "tumor"],
    "liver": ["Cholangiocyte", "Endothelium", "Hepatocyte", "Lymphocyte", "Macrophage", "Stroma", "tumor"],
    "lung": ["Chondrocyte", "Endothelium", "Epithelium", "Lymphocyte", "Macrophage", "Plasma_cell", "Stroma", "tumor"],
    "lymph_node": ["Endothelium", "Lymphocyte", "Macrophage", "Plasma_cell", "Squamous_epithelium", "Stroma", "tumor"],
    "ovary": ["Endothelium", "Epithelium", "Lymphocyte", "Macrophage", "Plasma_cell", "Stroma", "tumor"],
    "pancreas": ["Acinar", "Ductal", "Endothelium", "Islet", "Lymphocyte", "Macrophage", "Plasma_cell", "Stroma", "tumor"],
    "prostate": ["Endothelium", "Epithelium", "Lymphocyte", "Macrophage", "Nerve", "Stroma", "tumor"],
    "skin": ["Endothelium", "Epithelium", "Lymphocyte", "Macrophage", "Plasma_cell", "Stroma", "melanocytic", "tumor"],
    "tonsil": ["Endothelium", "Epithelium", "Lymphocyte", "Macrophage", "Plasma_cell", "Stroma"],
}

VERIFIED_CACHE_NAME = "verified.json"

EXIT_OK, EXIT_GENERIC, EXIT_CONFIG, EXIT_INPUT, EXIT_DEVICE = 0, 1, 2, 3, 4

# (import adi, kurulum ipucu) - yalniz selftest icinde kullanilir.
REQUIRED_MODULES = [
    ("torch", "torch>=2.1 (--torch-backend=auto)"),
    ("torchvision", "torchvision>=0.16"),
    ("timm", "timm>=1.0.9,<1.1"),
    ("numpy", "numpy>=1.24"),
    ("pandas", "pandas>=2.0"),
    ("pyarrow", "pyarrow>=12.0"),
    ("PIL", "Pillow>=9.5"),
    ("huggingface_hub", "huggingface_hub>=0.23"),
]

HF_STEP0 = (
    "Adim 0 (bir kez, her kullanici kendisi yapar): "
    "1) huggingface.co hesabi acin  "
    "2) https://huggingface.co/%s sayfasini acip kosullari kabul edin (otomatik onay)  "
    "3) Ayarlar -> Access Tokens'tan bir 'read' token olusturun  "
    "4) 'hf auth login' calistirin ya da tokeni sihirbazin alanina yapistirin. "
    "Alternatif: birinin daha once indirdigi bir klasoru kullanin (or. J:\\zhihuanglab\\CytoFormer) - "
    "kosullari yine de SIZ kabul etmis olmalisiniz." % HF_REPO
)


def log(msg):
    """Satir-tamponlu yaz; QuPath log alani canli akar."""
    print(msg, flush=True)


# ----------------------------------------------------------------------------
# Onbellek / depo-disi kural
# ----------------------------------------------------------------------------
class RepoCacheError(RuntimeError):
    """Onbellek yolu bu deponun (handson/ iceren) agaci icinde cozumlendiginde firlatilir."""

    def __init__(self, path, root):
        super().__init__("onbellek dizini depo agacinin icinde cozumlendi: %s (depo koku: %s)" % (path, root))
        self.path = path
        self.root = root


def repo_root(start=None):
    """Bu dosyanin (ya da 'start') icinde bulundugu depo kokunu dondur; degilse None.

    Kural: start.resolve().parents[3] (calistirici dosyadan 3 ust dizin) 'handson' alt
    klasorunu iceriyorsa bu bizim atolye deposudur; aksi halde None (kontrol devre disi).
    'start' testler icin: gercek __file__ yerine sahte bir yol verilebilir.
    """
    here = Path(start if start is not None else __file__).resolve()
    try:
        candidate = here.parents[3]
    except IndexError:
        return None
    return candidate if (candidate / "handson").is_dir() else None


def cache_dir():
    """Onbellek: $CYTOFORMER_CACHE, yoksa $TIATOOLBOX_HOME kardesi, yoksa ~/.atolye/cache/cytoformer.

    Cozumlenen yol depo agacinin icindeyse (repo_root() bunu bilirse) RepoCacheError firlatilir
    VE dizin OLUSTURULMAZ - upstream kodu bu depoya asla inmemelidir.
    """
    override = os.environ.get("CYTOFORMER_CACHE")
    if override:
        base = os.path.abspath(override)
    else:
        th = os.environ.get("TIATOOLBOX_HOME")  # <dataRoot>/cache/tiatoolbox
        if th:
            base = os.path.join(os.path.dirname(os.path.abspath(th)), "cytoformer")
        else:
            base = os.path.join(os.path.expanduser("~"), ".atolye", "cache", "cytoformer")
    root = repo_root()
    if root is not None:
        try:
            Path(base).resolve().relative_to(root)
        except ValueError:
            pass
        else:
            raise RepoCacheError(base, root)
    os.makedirs(base, exist_ok=True)
    return base


def code_dir():
    return os.path.join(cache_dir(), "code-" + CODE_SHA_SHORT)


def code_ready(require_marker=True):
    d = code_dir()
    infer = os.path.join(d, "scripts", "infer.py")
    if not os.path.isfile(infer):
        return False
    if require_marker and not os.path.isfile(os.path.join(d, MARKER_NAME)):
        return False
    return True


# ----------------------------------------------------------------------------
# Genel yardimcilar
# ----------------------------------------------------------------------------
def sha256_of(path, chunk_size=1 << 20):
    """SHA-256'yi 1 MiB parcalar halinde akitarak hesapla (buyuk dosyalar icin)."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(chunk_size), b""):
            h.update(chunk)
    return h.hexdigest()


def _version_tuple(v):
    """'0.24.0+cu128' -> (0, 24, 0); '0.2.0' -> (0, 2, 0). Sayisal karsilastirma icin."""
    core = str(v).split("+")[0]
    parts = []
    for p in core.split(".")[:3]:
        digits = "".join(ch for ch in p if ch.isdigit())
        parts.append(int(digits) if digits else 0)
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts)


def _torch_dist_version():
    """torch'u IMPORT ETMEDEN paket surumunu oku (or. '2.9.0+cu129'); yoksa None.

    classify komutu torch/timm import etmez (bu yalniz selftest'e ozeldir); CUDA-tekeri-CPU'da
    uyarisi icin surum bilgisi importlib.metadata ile yeterlidir.
    """
    try:
        import importlib.metadata as md
        return md.version("torch")
    except Exception:  # noqa: BLE001
        return None


def run_child(cmd, cwd, env):
    """Alt sureci calistir; ciktisini canli akit ('  | ' onekiyle). Donus: (kod, son_satirlar)."""
    proc = subprocess.Popen(cmd, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace", bufsize=1)
    tail = []
    try:
        for line in proc.stdout:
            line = line.rstrip("\r\n")
            if not line.strip():
                continue
            log("  | " + line)
            tail.append(line)
            if len(tail) > 80:
                tail.pop(0)
        proc.wait()
    except BaseException:
        proc.kill()
        raise
    finally:
        if proc.stdout:
            proc.stdout.close()
    return proc.returncode, tail


def classify_failure(tail, rc=None):
    """Alt surecin gunluk kuyrugunu/kodunu ayrik bir hata sinifina esler (explain_failure ve
    cmd_classify'in EXIT_DEVICE karari AYNI siniflandirmayi kullanir - tek dogru kaynak burasi)."""
    txt = "\n".join(tail)
    low = txt.lower()
    if rc in (-1073741819, 3221225477):
        return "native_crash"
    if "out of memory" in low or "cuda out of memory" in low:
        return "oom"
    if "torchvision" in low and ("modulenotfounderror" in low or "no module named" in low):
        return "torchvision"
    if "bfloat16" in low or "bf16" in low:
        return "bf16"
    if "modulenotfounderror" in low or "importerror" in low:
        return "module_missing"
    return "generic"


# "cihaz bulunamadi/uyumsuz" (EXIT_DEVICE=4) sinifina giren hata turleri - digerleri EXIT_GENERIC.
DEVICE_FAILURE_KINDS = ("native_crash", "bf16")


def explain_failure(tail, rc=None):
    """Alt surec basarisiz oldugunda anlasilir bir aciklama yaz (tasarim notundaki eslesmeler)."""
    kind = classify_failure(tail, rc)
    if kind == "native_crash":
        log("HATA: alt surec COKTU (0xC0000005, kod %s)." % rc)
        log("      Bilinen neden: CUDA'li bir torch tekeri --device cpu ile zorlanmis olabilir "
            "(Windows'ta nn.Linear.forward icinde cokme).")
        log("      Cozum: 'cytoformer' ortamini device=CPU secenegiyle YENIDEN KURUN (CPU tekeri) - "
            "CUDA tekerini CPU'ya zorlamayin.")
        return
    if kind == "oom":
        log("HATA: GPU bellegi yetmedi - --batch degerini dusurun (or. 16).")
        return
    if kind == "torchvision":
        log("HATA: torchvision surumu cok eski/uyumsuz (torchvision.ops bulunamadi) - "
            "'cytoformer' ortamini yeniden kurun (torchvision>=0.16 gerekir; cozulmemis "
            "bagimlilik torchvision 0.2.0'a duserse bu hata olusur).")
        return
    if kind == "bf16":
        log("HATA: bf16 (bfloat16) desteklenmiyor - Ampere ve sonrasi bir NVIDIA GPU ya da "
            "CPU ortami kullanin.")
        return
    if kind == "module_missing":
        log("HATA: Python paketi eksik/uyumsuz - Atolye Python ortam yoneticisinden "
            "'cytoformer' ortamini yeniden kurun.")
        return
    log("HATA: CytoFormer sureci basarisiz oldu (kod %s) - yukaridaki gunluge bakin." % rc)


# ----------------------------------------------------------------------------
# fetch-code (Penn Academic Software License - yalniz acik onaydan sonra)
# ----------------------------------------------------------------------------
def _fetch_url_to_file(url, dest_tmp):
    """Agdan indir (testler bu fonksiyonu degistirir, geriye kalanini degil)."""
    import urllib.request
    req = urllib.request.Request(url, headers={"User-Agent": "qupath-atolye-cytoformer"})
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest_tmp, "wb") as out:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            out.write(chunk)


def _download_pinned(url, dest, expected_sha, expected_size):
    """.part'a indir -> boyut+sha256 dogrula -> atomik yeniden adlandir; basarisizsa .part'i sil."""
    tmp = dest + ".part"
    try:
        _fetch_url_to_file(url, tmp)
        got_size = os.path.getsize(tmp)
        if got_size != expected_size:
            raise RuntimeError("boyut uyusmuyor (beklenen %d, bulunan %d)" % (expected_size, got_size))
        got_sha = sha256_of(tmp)
        if got_sha != expected_sha:
            raise RuntimeError("SHA-256 uyusmuyor (beklenen %s, bulunan %s)" % (expected_sha, got_sha))
        os.replace(tmp, dest)
    except BaseException:
        if os.path.isfile(tmp):
            os.remove(tmp)
        raise


def cmd_fetch_code(args):
    if not args.accept_license:
        log("HATA: --accept-license gerekli. CytoFormer kodu Penn Academic Software License "
            "Agreement ile lisanslidir (yalniz dahili arastirma / ticari olmayan kullanim, dagitim "
            "yasak, PENN mulkiyeti korunur). Lisans metni: %sLICENSE" % RAW_BASE)
        return EXIT_CONFIG
    # Komut satirindan cagiran da neyi kabul ettigini indirmeden ONCE gormeli (GUI'deki metnin ozeti).
    log("Lisans: Penn Academic Software License Agreement - --accept-license ile KENDI ADINIZA kabul ettiniz:")
    log("  - yalniz dahili arastirma / ticari olmayan kullanim (madde 4)")
    log("  - PENN'in yazili izni olmadan baska yere/kisiye aktarma veya dagitma yok (madde 4, 10)")
    log("  - kaynak kodu yeniden olusturma yok; yazilima dayali her program PENN'e ait turev sayilir (madde 9)")
    log("  - her iki tarafca 30 gun onceden bildirimle feshedilebilir (madde 15)")
    log("  Tam metin: %sLICENSE  (bu bir hukuki gorus degildir)" % RAW_BASE)
    dest_dir = code_dir()
    os.makedirs(dest_dir, exist_ok=True)
    all_ok = True
    for relpath, sha, size in CODE_FILES:
        dest = os.path.join(dest_dir, *relpath.split("/"))
        parent = os.path.dirname(dest)
        if parent:
            os.makedirs(parent, exist_ok=True)
        if os.path.isfile(dest) and not args.overwrite:
            if os.path.getsize(dest) == size and sha256_of(dest) == sha:
                log("Var (dogrulandi, atlandi): %s" % relpath)
                continue
            log("UYARI: mevcut dosya beklenen ozetle uyusmuyor, yeniden indiriliyor: %s" % relpath)
        url = RAW_BASE + relpath
        log("Indiriliyor (commit %s): %s" % (CODE_SHA_SHORT, url))
        try:
            _download_pinned(url, dest, sha, size)
        except Exception as exc:  # noqa: BLE001
            log("HATA: %s indirilemedi/dogrulanamadi: %s: %s" % (relpath, type(exc).__name__, exc))
            all_ok = False
            continue
        log("Dogrulandi: %s" % relpath)
    if not all_ok:
        return EXIT_GENERIC
    marker = {
        "code_sha": CODE_SHA,
        "accepted_license": True,
        "files": [f[0] for f in CODE_FILES],
        "fetched_at": time.time(),
    }
    with open(os.path.join(dest_dir, MARKER_NAME), "w", encoding="utf-8") as f:
        json.dump(marker, f, indent=2)
    log("DONE fetch-code path=%s" % dest_dir)
    return EXIT_OK


# ----------------------------------------------------------------------------
# fetch-weights / verify-weights (CC-BY-NC-4.0, Hugging Face, gated)
# ----------------------------------------------------------------------------
def _read_token_file(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            tok = f.read().strip()
        return tok or None
    except Exception:  # noqa: BLE001
        return None


def resolve_hf_token():
    """Sira: env HF_TOKEN -> $HF_HOME/token -> ~/.cache/huggingface/token. Asla CLI argumani.

    Donus: (token_ya_da_None, kaynak_etiketi_ya_da_None). Token DEGERI hicbir yerde loglanmaz.
    """
    tok = os.environ.get("HF_TOKEN")
    if tok:
        return tok, "env HF_TOKEN"
    hf_home = os.environ.get("HF_HOME")
    if hf_home:
        tok = _read_token_file(os.path.join(hf_home, "token"))
        if tok:
            return tok, "$HF_HOME/token"
    tok = _read_token_file(os.path.join(os.path.expanduser("~"), ".cache", "huggingface", "token"))
    if tok:
        return tok, "~/.cache/huggingface/token"
    return None, None


def _explain_hf_error(exc):
    name = type(exc).__name__
    status = getattr(getattr(exc, "response", None), "status_code", None)
    text = str(exc).lower()
    if name == "GatedRepoError" or status == 403 or "gated" in text or "access to model" in text:
        return "HATA: model kapida (gated repo) - erisim onayi/kabul gerekiyor.\n" + HF_STEP0
    if name == "RepositoryNotFoundError" or status == 404:
        return ("HATA: depo/dosya bulunamadi (%s @ %s) - repo adini ve revizyonu denetleyin."
                % (HF_REPO, HF_REVISION))
    if status == 401 or "401" in text or "unauthorized" in text or "invalid token" in text or "invalid user token" in text:
        return "HATA: yetkisiz (401) - gecerli bir Hugging Face 'read' tokeni yok/gecersiz.\n" + HF_STEP0
    return "HATA: agirlik indirilemedi: %s: %s" % (name, exc)


def _hf_hub_download(**kwargs):
    """huggingface_hub.hf_hub_download'i sarmalar (testler bunu degistirir)."""
    from huggingface_hub import hf_hub_download
    return hf_hub_download(**kwargs)


def _verified_cache_path():
    return os.path.join(cache_dir(), VERIFIED_CACHE_NAME)


def _load_verified_cache():
    p = _verified_cache_path()
    if os.path.isfile(p):
        try:
            with open(p, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:  # noqa: BLE001
            return {}
    return {}


def _save_verified_cache(data):
    p = _verified_cache_path()
    tmp = p + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, p)


def _cache_key(path):
    st = os.stat(path)
    return "%s|%d|%d" % (os.path.realpath(path), st.st_size, st.st_mtime_ns)


def _validate_map_content(map_path):
    """organ_celltype_map.json ICERIGINI pinlenmis taksonomiyle karsilastir (HASH DEGIL - HF
    klonlarinda CRLF satir sonu olabilir)."""
    try:
        with open(map_path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as exc:  # noqa: BLE001
        return False, "map okunamadi: %s: %s" % (type(exc).__name__, exc)
    if data.get("organs") != ORGANS:
        return False, "map 'organs' listesi pinlenmis taksonomiyle uyusmuyor"
    otc = data.get("organ_to_celltypes") or {}
    for organ in ORGANS:
        if otc.get(organ) != ORGAN_CELLTYPES[organ]:
            return False, "map '%s' organi icin sinif listesi pinlenmis taksonomiyle uyusmuyor" % organ
    return True, None


def verify_weights_impl(model_dir, force=False):
    """checkpoint.pth boyut+sha256 (onbellekli) + organ_celltype_map.json icerik dogrulamasi.

    Donus: (ok: bool, sebep_ya_da_None). Onbellek anahtari checkpoint'in gercek yolu+boyut+
    mtime_ns'idir; model_dir SALT-OKUNUR olabilir (onbellek dosyasi HER ZAMAN cache_dir()
    altina yazilir, model_dir'e degil).
    """
    ckpt = os.path.join(model_dir, CKPT_NAME)
    if not os.path.isfile(ckpt):
        return False, "checkpoint.pth yok: %s" % ckpt
    size = os.path.getsize(ckpt)
    if size != CKPT_SIZE:
        return False, "boyut uyusmuyor (beklenen %d, bulunan %d)" % (CKPT_SIZE, size)
    key = _cache_key(ckpt)
    cache = _load_verified_cache()
    entry = cache.get(key)
    if entry and not force:
        log("SHA-256 onbellekten (daha once dogrulandi, %s): %s" % (key.split("|")[0], ckpt))
        got = entry.get("sha256")
    else:
        log("SHA-256 hesaplaniyor (%.2f GB) - bir kez calisir, sonraki calistirmalar onbellekten..."
            % (size / 1e9))
        got = sha256_of(ckpt)
        cache[key] = {"sha256": got, "checked_at": time.time()}
        _save_verified_cache(cache)
    if got != CKPT_SHA256:
        return False, "SHA-256 uyusmuyor (beklenen %s, bulunan %s)" % (CKPT_SHA256, got)
    map_path = os.path.join(model_dir, MAP_NAME)
    if not os.path.isfile(map_path):
        return False, "organ_celltype_map.json yok: %s" % map_path
    return _validate_map_content(map_path)


def cmd_fetch_weights(args):
    dest = args.dest
    os.makedirs(dest, exist_ok=True)
    token, source = resolve_hf_token()
    log("Token: %s" % (("bulundu (%s)" % source) if token else "yok (herkese-acik indirme denenecek, muhtemelen 401/403 verir)"))
    try:
        _hf_hub_download(repo_id=HF_REPO, filename=CKPT_NAME, revision=HF_REVISION, local_dir=dest, token=token)
        _hf_hub_download(repo_id=HF_REPO, filename=MAP_NAME, revision=HF_REVISION, local_dir=dest, token=token)
    except Exception as exc:  # noqa: BLE001
        log(_explain_hf_error(exc))
        return EXIT_CONFIG
    ok, reason = verify_weights_impl(dest, force=True)
    if not ok:
        log("HATA: indirilen dosyalar dogrulanamadi: %s" % reason)
        return EXIT_GENERIC
    log("DONE fetch-weights path=%s" % dest)
    return EXIT_OK


def cmd_verify_weights(args):
    ok, reason = verify_weights_impl(args.model_dir, force=args.force)
    if ok:
        log("VERIFY OK")
        log("DONE verify ok=1")
        return EXIT_OK
    log("VERIFY FAIL %s" % reason)
    log("DONE verify ok=0")
    if not os.path.isfile(os.path.join(args.model_dir, CKPT_NAME)):
        return EXIT_CONFIG
    return EXIT_GENERIC


# ----------------------------------------------------------------------------
# selftest
# ----------------------------------------------------------------------------
def cmd_selftest(args):
    log("== cytoformer_runner selftest ==")
    ok = True
    for mod, hint in REQUIRED_MODULES:
        try:
            m = __import__(mod)
            log("OK    %-20s %s" % (mod, getattr(m, "__version__", "?")))
        except Exception as exc:  # noqa: BLE001
            ok = False
            log("EKSIK %-20s %s  (kurulum: %s)" % (mod, exc, hint))

    try:
        import torchvision
        tv = _version_tuple(torchvision.__version__)
        if tv < (0, 16, 0):
            ok = False
            log("UYUMSUZ torchvision %s - >=0.16 gerekir (timm 1.0.29 import zamaninda "
                "torchvision.ops ister; cozulmemis bagimlilik 0.2.0'a duserse ModuleNotFoundError "
                "verir)." % torchvision.__version__)
    except Exception:  # noqa: BLE001
        pass

    try:
        import timm
        tv = _version_tuple(timm.__version__)
        if tv < (1, 0, 9) or tv >= (1, 1, 0):
            ok = False
            log("UYUMSUZ timm %s - >=1.0.9,<1.1 gerekir." % timm.__version__)
        if not hasattr(getattr(timm, "layers", None), "SwiGLUPacked"):
            ok = False
            log("UYUMSUZ timm.layers.SwiGLUPacked yok - UNI2-h (vit_giant_patch14_224) mimarisi kurulamaz.")
    except Exception:  # noqa: BLE001
        pass

    torch_ver = None
    try:
        import torch
        torch_ver = getattr(torch, "__version__", None)
        if torch.cuda.is_available():
            name = torch.cuda.get_device_name(0)
            bf16 = None
            if hasattr(torch.cuda, "is_bf16_supported"):
                try:
                    bf16 = torch.cuda.is_bf16_supported()
                except Exception:  # noqa: BLE001
                    bf16 = None
            log("OK    %-20s %s (bf16: %s)" % ("CUDA", name, "var" if bf16 else "yok/bilinmiyor"))
        else:
            log("BILGI CUDA yok - CPU'da calisir (yavas, ~1.5-2 hucre/sn).")
    except Exception:  # noqa: BLE001
        pass
    if torch_ver and "+cu" in torch_ver and args.device == "cpu":
        log("UYARI: torch CUDA tekeri (%s) kurulu ama --device cpu istendi - Windows'ta "
            "0xC0000005 ile cokme riski var. CPU icin ortami AYRI bir CPU tekeriyle yeniden "
            "kurun (CUDA tekerini CPU'ya zorlamayin)." % torch_ver)

    try:
        d = code_dir()
        log("Kod   : %s (%s)" % (d, "VAR" if code_ready() else "yok - `fetch-code --accept-license` calistirin"))
    except RepoCacheError as exc:
        ok = False
        log("HATA  : %s" % exc)

    if args.model_dir:
        ckpt = os.path.join(args.model_dir, CKPT_NAME)
        if os.path.isfile(ckpt) and os.path.getsize(ckpt) == CKPT_SIZE:
            log("Model : VAR (boyut dogru, hizli denetim - tam dogrulama icin `verify-weights` calistirin): %s" % ckpt)
        else:
            ok = False
            log("Model : eksik ya da boyut uyusmuyor: %s" % ckpt)

    log("SELFTEST OK" if ok else "SELFTEST BASARISIZ - eksikleri giderin (env yoneticisi: cytoformer)")
    return EXIT_OK if ok else EXIT_GENERIC


# ----------------------------------------------------------------------------
# classify
# ----------------------------------------------------------------------------
def _safe_remove(path):
    try:
        os.remove(path)
    except OSError:
        pass


def cmd_classify(args):
    if args.organ not in ORGANS:
        log("HATA: bilinmeyen organ: %s (secenekler: %s)" % (args.organ, ", ".join(ORGANS)))
        return EXIT_INPUT
    if not os.path.isdir(args.patches):
        log("HATA: yama klasoru yok: %s" % args.patches)
        return EXIT_INPUT
    if not os.path.isfile(os.path.join(args.model_dir, CKPT_NAME)):
        log("HATA: model dosyasi yok: %s" % os.path.join(args.model_dir, CKPT_NAME))
        return EXIT_CONFIG
    try:
        ready = code_ready()
    except RepoCacheError as exc:
        log("HATA: %s" % exc)
        return EXIT_CONFIG
    if not ready:
        log("HATA: CytoFormer kodu yok (%s) - once `fetch-code --accept-license` calistirin." % code_dir())
        return EXIT_CONFIG

    device = args.device
    torch_ver = _torch_dist_version()
    if device == "cpu" and torch_ver and "+cu" in torch_ver:
        log("UYARI UYARI UYARI: torch CUDA tekeriyle kurulu (%s) ama --device cpu istendi." % torch_ver)
        log("UYARI: Bu kombinasyon Windows'ta 0xC0000005 (erisim ihlali) ile COKEBILIR "
            "(nn.Linear.forward, batch>=4).")
        log("UYARI: Kalici cozum - 'cytoformer' ortamini device=CPU secenegiyle YENIDEN KURUN "
            "(ayri bir CPU tekeri ile).")

    out_path = os.path.abspath(args.out)
    out_dir = os.path.dirname(out_path) or "."
    os.makedirs(out_dir, exist_ok=True)
    tmp_parquet = out_path + (".tmp-%d.parquet" % os.getpid())

    code = code_dir()
    cmd = [sys.executable, "-u", os.path.join(code, "scripts", "infer.py"),
           "--model_dir", os.path.abspath(args.model_dir),
           "--patches", os.path.abspath(args.patches),
           "--organ", args.organ,
           "--out", tmp_parquet,
           "--batch", str(args.batch)]

    env = dict(os.environ)
    env.setdefault("PYTHONIOENCODING", "utf-8")
    env["PYTHONUNBUFFERED"] = "1"
    if device == "cpu":
        env["CUDA_VISIBLE_DEVICES"] = "-1"

    log("Model : CytoFormer (kod %s, ayri surec)   organ=%s  batch=%d  cihaz=%s"
        % (CODE_SHA_SHORT, args.organ, args.batch, device))
    log("Komut : " + " ".join(cmd))
    rc, tail = run_child(cmd, code, env)
    if rc != 0:
        explain_failure(tail, rc)
        _safe_remove(tmp_parquet)
        if classify_failure(tail, rc) in DEVICE_FAILURE_KINDS:
            return EXIT_DEVICE
        return EXIT_GENERIC

    if not os.path.isfile(tmp_parquet):
        log("HATA: cikti parquet dosyasi olusmadi: %s" % tmp_parquet)
        return EXIT_GENERIC

    try:
        import pandas as pd
        df = pd.read_parquet(tmp_parquet)
    except Exception as exc:  # noqa: BLE001
        log("HATA: parquet okunamadi: %s: %s" % (type(exc).__name__, exc))
        _safe_remove(tmp_parquet)
        return EXIT_GENERIC

    n = len(df)
    if args.expect is not None and n != args.expect:
        log("HATA: satir sayisi beklenenle uyusmuyor (beklenen %d, bulunan %d)." % (args.expect, n))
        if not args.keep_raw:
            _safe_remove(tmp_parquet)
        return EXIT_INPUT

    allowed = set(ORGAN_CELLTYPES[args.organ])
    preds = df["pred_celltype"].astype(str) if n else []
    bad = sorted(set(preds) - allowed) if n else []
    if bad:
        log("HATA: organ-disi sinif(lar) bulundu ('%s' organinda gecerli degil): %s"
            % (args.organ, ", ".join(bad)))
        if not args.keep_raw:
            _safe_remove(tmp_parquet)
        return EXIT_INPUT

    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("cell_id\tpred_celltype\tprob\n")
        if n:
            for cid, cls, prob in zip(df["cell_id"].astype(str), preds, df["prob"]):
                f.write("%s\t%s\t%.4f\n" % (cid, cls, float(prob)))

    counts = {}
    for cls in preds:
        counts[cls] = counts.get(cls, 0) + 1
    for cls in ORGAN_CELLTYPES[args.organ]:
        log("COUNT %s=%d" % (cls, counts.get(cls, 0)))

    log("RESULT tsv=%s" % out_path)
    log("DONE classify n=%d" % n)
    if not args.keep_raw:
        _safe_remove(tmp_parquet)
    else:
        log("BILGI: ham parquet korundu: %s" % tmp_parquet)
    return EXIT_OK


# ----------------------------------------------------------------------------
def build_parser():
    p = argparse.ArgumentParser(description="CytoFormer koprusu (upstream infer.py ayri surec olarak).")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("selftest", help="Bagimliliklari + CUDA/bf16 + kod/model varligini dogrula.")
    s.add_argument("--model-dir", default=None, dest="model_dir")
    s.add_argument("--device", default="auto", choices=["auto", "cuda", "cpu"])
    s.set_defaults(func=cmd_selftest)

    fc = sub.add_parser("fetch-code", help="Pinlenmis 8 dosyayi GitHub'dan indir (lisans onayi gerekir).")
    fc.add_argument("--accept-license", action="store_true", dest="accept_license",
                    help="Penn Academic Software License Agreement'i kabul ettiginizi onaylar.")
    fc.add_argument("--overwrite", action="store_true")
    fc.set_defaults(func=cmd_fetch_code)

    fw = sub.add_parser("fetch-weights", help="checkpoint.pth + organ_celltype_map.json'i Hugging Face'ten indir.")
    fw.add_argument("--dest", required=True)
    fw.set_defaults(func=cmd_fetch_weights)

    vw = sub.add_parser("verify-weights", help="Model klasorunu boyut+sha256+map icerigiyle dogrula.")
    vw.add_argument("--model-dir", required=True, dest="model_dir")
    vw.add_argument("--force", action="store_true", help="Onbellegi yok say, sha256'yi yeniden hesapla.")
    vw.set_defaults(func=cmd_verify_weights)

    c = sub.add_parser("classify", help="Yama klasorunde CytoFormer cikarimi (TSV).")
    c.add_argument("--patches", required=True, help="224x224 hucre yamalarini iceren klasor.")
    c.add_argument("--model-dir", required=True, dest="model_dir")
    c.add_argument("--organ", required=True, help="16 organdan biri (bkz. --help ciktisindaki liste).")
    c.add_argument("--out", required=True, help="Cikti TSV yolu.")
    c.add_argument("--batch", type=int, default=64)
    c.add_argument("--device", default="auto", choices=["auto", "cuda", "cpu"])
    c.add_argument("--expect", type=int, default=None, help="Beklenen hucre sayisi (uyusmazsa HATA).")
    c.add_argument("--keep-raw", action="store_true", dest="keep_raw", help="Ham parquet dosyasini silme.")
    c.set_defaults(func=cmd_classify)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except RepoCacheError as exc:
        log("HATA: %s" % exc)
        log("      CYTOFORMER_CACHE ortam degiskenini depo DISINDA bir klasore ayarlayin.")
        return EXIT_CONFIG
    except FileNotFoundError as exc:
        log("HATA (dosya yok): %s" % exc)
        return EXIT_INPUT
    except Exception as exc:  # noqa: BLE001
        log("HATA: %s: %s" % (type(exc).__name__, exc))
        return EXIT_GENERIC


if __name__ == "__main__":
    # QuPath runner'i boru ile cagirir; Windows'ta borunun varsayilan kodlamasi cp1252'dir.
    # Turkce klasor adlari ve alt surec satirlari bozulmasin diye UTF-8'e sabitlenir.
    for _stream in (sys.stdout, sys.stderr):
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
