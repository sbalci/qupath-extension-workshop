#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
classpose_runner.py - QuPath atolyesi icin Classpose (H&E hucre fenotipleme) koprusu
====================================================================================

NE YAPAR:
    Classpose'un (Mandal, de Almeida ve ark.; bioRxiv doi:10.64898/2025.12.18.695211) kendi
    WSI hattini `python -m classpose.entrypoints.predict_wsi` olarak, QuPath'te SECILEN ALAN
    icin AYRI BIR SUREC halinde calistirir. Classpose slayt dosyasini kendisi OpenSlide ile
    okur; um/px degeri DOSYADAN alinir (QuPath'te elle girilen kalibrasyon aktarilamaz).

LISANS SINIRI:
    Classpose kodu CC BY-NC 4.0 (depo LICENSE); Hugging Face model karti "MIT" der; GrandQC
    agirliklari CC BY-NC 4.0 (doku) / CC BY-NC-SA 4.0 (artefakt). Atolye eklentisi Apache-2.0:
    Classpose kodu bu depoda/JAR'da PAKETLENMEZ ve KOPYALANMAZ. Kullanicinin `classpose`
    ortamina calisma aninda kurulur; bu kopru yalnizca komut satirini kurar, gunlugu okur ve
    GeoJSON ciktisinin yolunu bildirir. Kullanim: arastirma/egitim, ticari olmayan.

NE URETIR (ve ne URETMEZ):
    Hucre konturu + model sinifi (Classpose'un yazdigi GeoJSON). Klinik yorum URETMEZ.

KULLANIM:
    python classpose_runner.py selftest
    python classpose_runner.py precheck --slide SLAYT.svs
    python classpose_runner.py verify --model conic --dir MODEL_KLASORU
    python classpose_runner.py download --model conic --dir MODEL_KLASORU
    python classpose_runner.py download --grandqc --grandqc-dir GRANDQC_MODELS_KLASORU
    python classpose_runner.py detect --slide SLAYT.svs --model conic --model-dir MODEL_KLASORU \\
        --roi roi.geojson --out CALISMA_KLASORU [--device auto|cuda|cpu] [--tta] \\
        [--batch-size 8] [--tile-size 1024] [--tissue TD.pth] [--artefact QC.pth]

CIKIS KODLARI:
    0 tamam (0 hucre dahil) | 2 hatali arguman/eksik dosya | 3 import/ortam hatasi |
    5 slayt okunamadi ya da dosyada um/px yok | 6 Classpose basarisiz | 7 indirme/dogrulama hatasi

KAYNAK:
    Kod: https://github.com/sohmandal/classpose (commit f6aeadd5)
    Agirlik: https://huggingface.co/classpose/classpose (revizyon 26518acc)
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys

HF_REPO = "classpose/classpose"
HF_REVISION = "26518acc9e755aa38a2ca55d6bb83bda18a5f65c"
# Hugging Face LFS SHA-256 degerleri (revizyon HF_REVISION).
MODEL_SHA256 = {
    "conic": "3200ca6daf3abd3adcc69dedfceb8fe7fa388362cced70be8a2d91581cdf40d3",
    "consep": "8e6d6a68a746bd7aad5266d40fc5a61f57e01e1aa55e8c60aad4c749ed1cae56",
    "glysac": "8d9b56117fc75290b1463b1643ec1a3fd71069cb1dfedb710fb758c767679eb0",
    "monusac": "57fa12fb0dad741c4177e51e4bd0f38953c82254995292a62103ae7af0624660",
    "nucls": "e9db3a15929c8bddd944b5ee0c67f9a397360cb3dbd71106a1505c716761d790",
    "puma": "1b18471863847508ecc807ebd7f6a46ee577f38de480b36da274bc3450f3c648",
}

EXIT_OK, EXIT_ARGS, EXIT_IMPORT, EXIT_SLIDE, EXIT_CLASSPOSE, EXIT_DOWNLOAD = 0, 2, 3, 5, 6, 7

# (import adi, kurulum ipucu)
REQUIRED_MODULES = [
    ("torch", "torch (GPU icin --torch-backend=auto)"), ("torchvision", "torchvision"),
    ("cellpose", "cellpose==4.0.8"), ("classpose", "classpose @ GitHub zip (sabit commit) + prepare"),
    ("openslide", "openslide-python + openslide-bin"),
    ("segmentation_models_pytorch", "segmentation-models-pytorch"), ("timm", "timm<0.5"),
    ("huggingface_hub", "huggingface-hub"), ("shapely", "shapely"),
]


def log(msg):
    """Satir-tamponlu yaz; QuPath gunluk alani canli akar."""
    print(msg, flush=True)


def _digest(path, h):
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_of(path):
    return _digest(path, hashlib.sha256())


def md5_of(path):
    return _digest(path, hashlib.md5(usedforsecurity=False))  # yalniz butunluk: Zenodo MD5 yayimlar


def resolution_of(slide):
    """Classpose'un KENDI um/px okuyucusu (classpose.utils.get_slide_resolution); yoksa None.

    Ayni fonksiyon cagrilir ki on-denetim, Classpose calismasiyla ayni karari versin.
    """
    from classpose.utils import get_slide_resolution
    try:
        return get_slide_resolution(slide)
    except (ValueError, KeyError):
        return None


# ----------------------------------------------------------------------------
# Alt komutlar
# ----------------------------------------------------------------------------
def cmd_selftest(args):
    log("== classpose_runner selftest ==")
    log("OK    %-28s %s" % ("python", sys.version.split()[0]))
    ok = True
    for mod, hint in REQUIRED_MODULES:
        try:
            m = __import__(mod)
            log("OK    %-28s %s" % (mod, getattr(m, "__version__", "?")))
        except Exception as exc:  # noqa: BLE001
            ok = False
            log("EKSIK %-28s %s  (kurulum: %s)" % (mod, exc, hint))
    err = entrypoints_error()
    if err is None:
        log("OK    %-28s %s" % ("classpose.entrypoints", "predict_wsi"))
        log("ENTRYPOINTS ok=1")
    else:
        ok = False
        log("EKSIK %-28s %s" % ("classpose.entrypoints", err))
        log("IPUCU: Classpose kurulumu eksik - sihirbazdaki 'Kurulumu tamamla' dugmesini kullanin (kopru: prepare --uv <uv.exe>).")
        log("ENTRYPOINTS ok=0")
    cuda = False
    try:
        import torch
        cuda = bool(torch.cuda.is_available())
        if cuda:
            log("OK    %-28s %s" % ("CUDA", torch.cuda.get_device_name(0)))
        else:
            log("BILGI CUDA yok - Classpose CPU'da calisir (cok yavas).")
    except Exception:  # noqa: BLE001
        pass
    log("CUDA cuda=%d" % (1 if cuda else 0))
    log("SELFTEST OK" if ok else "SELFTEST BASARISIZ - ortam yoneticisinden 'classpose' ortamini yeniden kurun")
    return EXIT_OK if ok else EXIT_IMPORT


def cmd_precheck(args):
    if not os.path.isfile(args.slide):
        log("HATA: slayt dosyasi yok: %s" % args.slide)
        log("PRECHECK ok=0 reason=missing")
        return EXIT_SLIDE
    try:
        import openslide
    except Exception as exc:  # noqa: BLE001
        log("HATA: openslide import edilemedi: %s" % exc)
        return EXIT_IMPORT
    try:
        slide = openslide.OpenSlide(args.slide)
    except Exception as exc:  # noqa: BLE001
        log("HATA: OpenSlide bu dosyayi acamiyor (%s: %s). Classpose slayti OpenSlide ile okur "
            "(or. CZI desteklenmez)." % (type(exc).__name__, exc))
        log("PRECHECK ok=0 reason=unreadable")
        return EXIT_SLIDE
    try:
        props = slide.properties
        width, height = slide.dimensions
        bx = float(props.get("openslide.bounds-x") or 0)
        by = float(props.get("openslide.bounds-y") or 0)
        mpp = resolution_of(slide)
    finally:
        slide.close()
    log("PRECHECK width=%d height=%d" % (width, height))
    log("PRECHECK bounds_x=%.0f bounds_y=%.0f" % (bx, by))
    if mpp is None:
        log("HATA: slayt dosyasinda um/px bilgisi yok (openslide.mpp-x/y ya da tiff.XResolution). "
            "Classpose um/px degerini dosyadan okur; QuPath'te girilen kalibrasyon aktarilamaz.")
        log("PRECHECK ok=0 reason=no_mpp")
        return EXIT_SLIDE
    log("PRECHECK mpp_x=%.6f mpp_y=%.6f" % (float(mpp[0]), float(mpp[1])))
    log("PRECHECK ok=1")
    return EXIT_OK


def cmd_verify(args):
    path = os.path.join(args.dir, args.model + ".pt")
    if not os.path.isfile(path):
        log("HATA: model dosyasi yok: %s" % path)
        log("VERIFY ok=0 reason=missing")
        return EXIT_DOWNLOAD
    log("SHA-256 hesaplaniyor (%.2f GB) - biraz surebilir..." % (os.path.getsize(path) / 1e9))
    got = sha256_of(path)
    if got != MODEL_SHA256[args.model]:
        log("HATA: SHA-256 uyusmuyor (beklenen %s, bulunan %s) - dosya sabitlenmis surumle ayni degil."
            % (MODEL_SHA256[args.model], got))
        log("VERIFY ok=0 reason=mismatch")
        return EXIT_DOWNLOAD
    log("VERIFY ok=1 path=%s" % path)
    return EXIT_OK


GRANDQC_FILES = [
    ("td", "Tissue_Detection_MPP10.pth",
     "https://zenodo.org/records/14507273/files/Tissue_Detection_MPP10.pth?download=1", "25ccb2592f5d44b4f30143501390e078"),
    ("qc", "GrandQC_MPP1.pth",
     "https://zenodo.org/records/14041538/files/GrandQC_MPP1.pth?download=1", "ece5ac9967ec4d4320465ce0241de97f"),
    ("qc", "GrandQC_MPP15.pth",
     "https://zenodo.org/records/14041538/files/GrandQC_MPP15.pth?download=1", "005c8f4ce99ea1bbb8f00a9dd397574b"),
    ("qc", "GrandQC_MPP2.pth",
     "https://zenodo.org/records/14041538/files/GrandQC_MPP2.pth?download=1", "1844b220750c19bda6a256ff996c2cfb"),
]


def fetch_model(repo_id, filename, revision, local_dir):
    """Hugging Face'ten tek dosya indir (sabit revizyon); yerel yolu dondur."""
    from huggingface_hub import hf_hub_download
    return hf_hub_download(repo_id=repo_id, filename=filename, revision=revision, local_dir=local_dir)


def http_download(url, dest, expected_md5):
    """Akisli indir (.part -> yeniden adlandir) ve MD5 dogrula; uyusmazsa .part silinir."""
    import urllib.request
    tmp = dest + ".part"
    h = hashlib.md5(usedforsecurity=False)  # yalniz butunluk/kaynak dogrulama
    req = urllib.request.Request(url, headers={"User-Agent": "qupath-atolye-classpose"})
    try:
        with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as out:
            for chunk in iter(lambda: resp.read(1 << 20), b""):
                out.write(chunk)
                h.update(chunk)
    except BaseException:
        if os.path.exists(tmp):
            os.remove(tmp)
        raise
    if expected_md5 is not None and h.hexdigest().lower() != expected_md5.lower():
        os.remove(tmp)
        raise RuntimeError("MD5 uyusmuyor (beklenen %s, bulunan %s)" % (expected_md5, h.hexdigest()))
    os.replace(tmp, dest)
    return dest


def precache_encoder():
    """GrandQC doku modelinin ImageNet kodlayicisini (timm-efficientnet-b0) TORCH_HOME'a indir.

    Classpose doku modelini encoder_weights='imagenet' ile kurar; bu agirlik ilk kullanimda
    torch hub'dan cekilir. Onceden cekilirse sonraki calismalar cevrimdisi olur.
    """
    try:
        import segmentation_models_pytorch as smp
        smp.UnetPlusPlus(encoder_name="timm-efficientnet-b0", encoder_weights="imagenet", classes=2, activation=None)
        log("ImageNet kodlayici onbellekte (TORCH_HOME=%s)." % os.environ.get("TORCH_HOME", "~/.cache/torch"))
        return True
    except Exception as exc:  # noqa: BLE001
        log("UYARI: kodlayici onbellege alinamadi (%s: %s) - ilk doku tespitinde internet gerekir."
            % (type(exc).__name__, exc))
        return False


def download_grandqc(models_dir):
    """Eksik GrandQC dosyalarini GrandQC sihirbaziyla ORTAK klasore indir; mevcutlara dokunma."""
    if not models_dir:
        log("HATA: --grandqc-dir gerekli.")
        return EXIT_ARGS
    for sub, name, url, md5 in GRANDQC_FILES:
        folder = os.path.join(models_dir, sub)
        os.makedirs(folder, exist_ok=True)
        dest = os.path.join(folder, name)
        if os.path.isfile(dest):
            log("Var (atlandi): %s" % dest)
            continue
        log("Indiriliyor (Zenodo): %s" % name)
        try:
            http_download(url, dest, md5)
        except Exception as exc:  # noqa: BLE001
            log("HATA: %s indirilemedi: %s: %s" % (name, type(exc).__name__, exc))
            log("Elle indirme: %s -> %s" % (url, dest))
            return EXIT_DOWNLOAD
        log("MD5 dogrulandi: %s" % name)
    precache_encoder()
    log("DONE download grandqc ok=1 dir=%s" % models_dir)
    return EXIT_OK


def cmd_download(args):
    if args.grandqc:
        return download_grandqc(args.grandqc_dir)
    if not args.model or not args.dir:
        log("HATA: --model ve --dir gerekli (ya da --grandqc --grandqc-dir).")
        return EXIT_ARGS
    filename = args.model + ".pt"
    target = os.path.join(args.dir, filename)
    expected = MODEL_SHA256[args.model]
    if os.path.isfile(target) and sha256_of(target) == expected:
        log("Model zaten var ve dogrulandi: %s" % target)
        log("DONE download ok=1 path=%s" % target)
        return EXIT_OK
    os.makedirs(args.dir, exist_ok=True)
    log("Indiriliyor: %s/%s @ %s (~1.2 GB)" % (HF_REPO, filename, HF_REVISION[:12]))
    try:
        path = fetch_model(HF_REPO, filename, HF_REVISION, args.dir)
    except Exception as exc:  # noqa: BLE001
        log("HATA: indirilemedi: %s: %s" % (type(exc).__name__, exc))
        log("Elle indirme: https://huggingface.co/%s/resolve/%s/%s -> %s" % (HF_REPO, HF_REVISION, filename, target))
        return EXIT_DOWNLOAD
    got = sha256_of(path)
    if got != expected:
        try:
            os.remove(path)
        except OSError:
            pass
        log("HATA: SHA-256 uyusmuyor (beklenen %s, bulunan %s) - dosya silindi." % (expected, got))
        return EXIT_DOWNLOAD
    log("SHA-256 dogrulandi.")
    log("DONE download ok=1 path=%s" % path)
    return EXIT_OK


OUTPUT_KINDS = ("cell_contours", "cell_centroids", "tissue_contours", "artefact_contours")
# Classpose cikti dosya adi sablonlarini bu ortam degiskenleriyle degistirir
# (utils.GEOJSON_OUTPUT_TEMPLATES); varsayilan adlara guvendigimiz icin alt surecten silinir.
FILENAME_ENV_VARS = (
    "CLASSPOSE_CELL_CONTOURS_GEOJSON", "CLASSPOSE_CELL_CENTROIDS_GEOJSON",
    "CLASSPOSE_TISSUE_CONTOURS_GEOJSON", "CLASSPOSE_ARTEFACT_CONTOURS_GEOJSON", "CLASSPOSE_ROI_GEOJSON",
)
_RX_DETECTED = re.compile(r"Number of detected cells: (\d+)")
_RX_AFTER = re.compile(r"Number of cells after filtering: (\d+)")
_RX_ARTEFACT = re.compile(r"Removed (\d+) cells in artefact regions")
MIN_TILE, MAX_TILE = 256, 2048


def output_paths(out_dir, slide):
    """Classpose'un varsayilan cikti adlari (mutlak yol): <slayt govdesi>_<tur>.geojson (Path(slide).stem ile ayni)."""
    stem = os.path.splitext(os.path.basename(slide))[0]
    out_dir = os.path.abspath(out_dir)
    return {kind: os.path.join(out_dir, "%s_%s.geojson" % (stem, kind)) for kind in OUTPUT_KINDS}


def remove_stale_outputs(out_dir, slide):
    stem = os.path.splitext(os.path.basename(slide))[0]
    # Classpose ROI'yi de <govde>_roi.geojson olarak geri yazar; sihirbazin girdisi roi.geojson ayri addadir.
    echo = os.path.join(os.path.abspath(out_dir), "%s_roi.geojson" % stem)
    removed = []
    for path in list(output_paths(out_dir, slide).values()) + [echo]:
        if os.path.isfile(path):
            os.remove(path)
            removed.append(path)
    return removed


def build_predict_command(args, python_exe=None):
    cmd = [python_exe or sys.executable, "-u", "-m", "classpose.entrypoints.predict_wsi",
           "--model_config", args.model,
           "--slide_path", os.path.abspath(args.slide),
           "--roi_geojson", os.path.abspath(args.roi),
           "--output_folder", os.path.abspath(args.out),
           "--batch_size", str(args.batch_size),
           "--tile_size", str(args.tile_size)]
    if args.device in ("cuda", "cpu"):
        cmd += ["--device", args.device]  # auto: bayrak yok, Classpose cihazi kendisi secer
    if args.tta:
        cmd.append("--tta")
    if args.tissue:
        cmd += ["--tissue_detection_model_path", os.path.abspath(args.tissue)]
        if args.artefact:
            # --filter_artefacts OLMADAN Classpose artefaktlari yalniz cizer, hucreleri cikarmaz.
            cmd += ["--artefact_detection_model_path", os.path.abspath(args.artefact), "--filter_artefacts"]
    return cmd


def child_env(model_dir, base_env=None, is_windows=None):
    env = dict(os.environ if base_env is None else base_env)
    for name in FILENAME_ENV_VARS:
        env.pop(name, None)
    env["CLASSPOSE_MODEL_DIR"] = os.path.abspath(model_dir)
    env["PYTHONUNBUFFERED"] = "1"
    env.setdefault("PYTHONIOENCODING", "utf-8")
    windows = (os.name == "nt") if is_windows is None else is_windows
    if windows:
        # Windows'ta Triton yok: Classpose'un CUDA'da cagirdigi torch.compile TritonMissing ile duser
        # (RTX A4000'de dogrulandi). Bu degisken torch.compile'i etkisiz kilar.
        env["TORCHDYNAMO_DISABLE"] = "1"
    return env


class StatTracker:
    """Classpose gunlugunden sayilari toplar (bulunmayan satirlar sessizce yok sayilir)."""

    def __init__(self):
        self.stats = {}
        self.no_cells = False
        self._stage = None

    def feed(self, line):
        if "Filtering cells based on ROI contours" in line:
            self._stage = "after_roi"
        elif "Filtering cells based on tissue contours" in line:
            self._stage = "after_tissue"
        if "No cells detected" in line:
            self.no_cells = True
        m = _RX_DETECTED.search(line)
        if m:
            self.stats["detected"] = int(m.group(1))
        m = _RX_AFTER.search(line)
        if m and self._stage:
            self.stats[self._stage] = int(m.group(1))
        m = _RX_ARTEFACT.search(line)
        if m:
            self.stats["artefact_removed"] = int(m.group(1))


def count_features(path):
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    feats = data.get("features", []) if isinstance(data, dict) else data
    return len(feats)


def run_streaming(cmd, env, log_path, on_line):
    """Alt sureci calistir; ciktisini canli akit + dosyaya yaz. Donus: cikis kodu."""
    with open(log_path, "w", encoding="utf-8") as logf, \
            subprocess.Popen(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, encoding="utf-8", errors="replace", bufsize=1) as proc:
        try:
            for raw in proc.stdout:
                line = raw.rstrip("\r\n")
                logf.write(line + "\n")
                if not line.strip():
                    continue
                log("  | " + line)
                on_line(line)
            proc.wait()
        except BaseException:
            proc.kill()
            raise
    return proc.returncode


def explain_failure(tail):
    low = "\n".join(tail).lower()
    if "out of memory" in low:
        log("IPUCU: GPU bellegi yetmedi - Gelismis'te batch size (or. 4) ya da karo boyutunu (or. 512) dusurun veya CPU secin.")
    elif "does not have mpp" in low:
        log("IPUCU: slayt dosyasinda um/px yok - Classpose bu dosyayi olcekleyemez.")
    elif "tritonmissing" in low or "triton installation" in low:
        log("IPUCU: torch.compile Triton istiyor - TORCHDYNAMO_DISABLE=1 ayarli olmali (kopru Windows'ta ayarlar).")
    elif ".pth" in low and ("unpickling" in low or "pytorchstreamreader" in low
                            or "eoferror" in low or "invalid load key" in low):
        log("IPUCU: GrandQC model dosyasi bozuk olabilir - dosyayi silip sihirbazdaki GrandQC 'Indir' ile yeniden indirin.")
    elif "classpose.entrypoints" in low or "cache_cpsam_model_path" in low:
        log("IPUCU: Classpose kurulumu eksik - sihirbazdaki 'Kurulumu tamamla' dugmesini kullanin (kopru: prepare).")
    elif "modulenotfounderror" in low or "importerror" in low:
        log("IPUCU: Python paketi eksik - ortam yoneticisinden 'classpose' ortamini yeniden kurun.")
    else:
        log("IPUCU: Classpose sureci basarisiz - yukaridaki gunluge bakin.")


def cmd_detect(args):
    for label, path in (("slayt", args.slide), ("ROI", args.roi)):
        if not os.path.isfile(path):
            log("HATA: %s dosyasi yok: %s" % (label, path))
            return EXIT_ARGS
    model_file = os.path.join(args.model_dir, args.model + ".pt")
    if not os.path.isfile(model_file):
        log("HATA: model dosyasi yok: %s" % model_file)
        return EXIT_ARGS
    if not (MIN_TILE <= args.tile_size <= MAX_TILE) or args.batch_size < 1:
        log("HATA: karo boyutu %d-%d, batch size >= 1 olmali." % (MIN_TILE, MAX_TILE))
        return EXIT_ARGS
    if args.artefact and not args.tissue:
        log("UYARI: artefakt filtreleme doku tespiti olmadan calismaz - artefakt atlandi.")
    for label, path in (("doku modeli", args.tissue), ("artefakt modeli", args.artefact if args.tissue else None)):
        if path and not os.path.isfile(path):
            log("HATA: GrandQC %s yok: %s" % (label, path))
            return EXIT_ARGS
    os.makedirs(args.out, exist_ok=True)
    for path in remove_stale_outputs(args.out, args.slide):
        log("Eski cikti silindi: %s" % path)
    cmd = build_predict_command(args)
    log("Komut: " + " ".join(cmd))
    tracker = StatTracker()
    tail = []

    def on_line(line):
        tracker.feed(line)
        tail.append(line)
        if len(tail) > 80:
            tail.pop(0)

    rc = run_streaming(cmd, child_env(args.model_dir), os.path.join(args.out, "classpose_run.log"), on_line)
    if rc != 0:
        explain_failure(tail)
        log("HATA: Classpose cikis kodu %d" % rc)
        return EXIT_CLASSPOSE
    for key in ("detected", "after_roi", "after_tissue", "artefact_removed"):
        if key in tracker.stats:
            log("STAT %s=%d" % (key, tracker.stats[key]))
    contours = output_paths(args.out, args.slide)["cell_contours"]
    if os.path.isfile(contours):
        log("COUNT n=%d" % count_features(contours))
        log("RESULT geojson=%s" % contours)
    else:
        log("COUNT n=0")
    log("DONE")
    return EXIT_OK


CLASSPOSE_COMMIT = "f6aeadd54729f6b9e50db620af68616f202d7a72"
SOURCE_ZIP_URL = "https://github.com/sohmandal/classpose/archive/%s.zip" % CLASSPOSE_COMMIT
# Bu commit cellpose.models.cache_CPSAM_model_path ister; cellpose 4.2'de yok (upstream uv.lock: 4.0.8).
CELLPOSE_PIN = "cellpose==4.0.8"
# Upstream pyproject: [tool.setuptools] packages = ["classpose"] -> alt paketler (entrypoints, grandqc,
# metrics, transforms) kurulmaz. Yalniz bu satir packages.find ile degistirilir.
_PACKAGES_LINE = re.compile(r'(?m)^packages = \["classpose"\]\r?\n')
_FIND_BLOCK = '[tool.setuptools.packages.find]\nwhere = ["src"]\n'


def entrypoints_error():
    """predict_wsi gercekten ice aktarilabiliyor mu? Degilse hata metni, tamamsa None.

    'import classpose' eksik alt paketleri ve yanlis cellpose surumunu yakalamaz; bu denetim ikisini de yakalar.
    """
    import importlib
    try:
        importlib.import_module("classpose.entrypoints.predict_wsi")
        return None
    except Exception as exc:  # noqa: BLE001
        return "%s: %s" % (type(exc).__name__, exc)


def patch_pyproject(text):
    """packages = ["classpose"] satirini packages.find (where = src) ile degistir; zaten duzeltilmisse aynen dondur."""
    if "[tool.setuptools.packages.find]" in text:
        return text
    if len(_PACKAGES_LINE.findall(text)) != 1:
        raise ValueError('pyproject.toml beklenen bicimde degil (tek bir packages = ["classpose"] satiri yok)')
    return _PACKAGES_LINE.sub("", text).rstrip() + "\n\n" + _FIND_BLOCK


def usable_source_zip(zip_path):
    """Elle yerlestirilmis ya da onceki bir calismadan kalan arsiv aynen kullanilabilir mi?

    Yalniz zip_path bir dosyaysa, gecerli bir zip arsiviyse ve icindeki TEK ust klasor tam olarak
    beklenen commit adiysa (classpose-<40 karakterlik commit>) True doner; aksi halde yeniden
    indirilir (bozuk, eksik ya da baska bir commit'e ait arsiv sessizce kabul edilmez).
    """
    import zipfile
    if not os.path.isfile(zip_path):
        return False
    if not zipfile.is_zipfile(zip_path):
        return False
    with zipfile.ZipFile(zip_path) as zf:
        roots = {name.split("/", 1)[0] for name in zf.namelist() if name.strip("/")}
    return roots == {"classpose-" + CLASSPOSE_COMMIT}


def extract_project(zip_path, dest_dir):
    """Arsivi dest_dir altina ac; pyproject.toml iceren tek ust klasorun yolunu dondur."""
    import zipfile
    with zipfile.ZipFile(zip_path) as zf:
        roots = {name.split("/", 1)[0] for name in zf.namelist() if name.strip("/")}
        if len(roots) != 1:
            raise ValueError("beklenmeyen arsiv yapisi: %s" % ", ".join(sorted(roots)[:3]))
        zf.extractall(dest_dir)
    project = os.path.join(dest_dir, roots.pop())
    if not os.path.isfile(os.path.join(project, "pyproject.toml")):
        raise ValueError("arsivde pyproject.toml yok")
    return project


def default_work_dir():
    """<ortam>/../classpose-src: ortam silinip yeniden kurulursa kaynak da yeniden hazirlanir."""
    return os.path.join(os.path.dirname(os.path.abspath(sys.prefix)), "classpose-src")


def cmd_prepare(args):
    if not os.path.isfile(args.uv):
        log("HATA: uv bulunamadi: %s - once ortam yoneticisinden 'classpose' ortamini kurun." % args.uv)
        return EXIT_ARGS
    work = os.path.abspath(args.work or default_work_dir())
    os.makedirs(work, exist_ok=True)
    env = dict(os.environ)
    if args.uv_cache:
        env["UV_CACHE_DIR"] = os.path.abspath(args.uv_cache)
    py = sys.executable
    log("1/4 cellpose sabitleniyor: %s" % CELLPOSE_PIN)
    pin_log = os.path.join(work, "prepare_cellpose.log")
    pin_cmd = [args.uv, "pip", "install", "--python", py, "--torch-backend=auto", CELLPOSE_PIN]
    if run_streaming(pin_cmd, env, pin_log, lambda _line: None) != 0:
        log("HATA: %s kurulamadi - gunluk: %s" % (CELLPOSE_PIN, pin_log))
        return EXIT_IMPORT
    zip_path = os.path.join(work, "classpose-%s.zip" % CLASSPOSE_COMMIT[:7])
    log("2/4 Classpose kaynak kodu indiriliyor (sabit commit %s, ~1 MB)" % CLASSPOSE_COMMIT[:7])
    try:
        if usable_source_zip(zip_path):
            log("Mevcut arsiv kullaniliyor (indirme atlandi): %s" % zip_path)
        else:
            http_download(SOURCE_ZIP_URL, zip_path, None)
        project = extract_project(zip_path, work)
        pyproject = os.path.join(project, "pyproject.toml")
        with open(pyproject, "r", encoding="utf-8") as f:
            text = f.read()
        patched = patch_pyproject(text)
        with open(pyproject, "w", encoding="utf-8", newline="\n") as f:
            f.write(patched)
    except Exception as exc:  # noqa: BLE001
        log("HATA: kaynak kod hazirlanamadi: %s: %s" % (type(exc).__name__, exc))
        log("Elle indirme: %s -> %s" % (SOURCE_ZIP_URL, zip_path))
        return EXIT_DOWNLOAD
    log("3/4 Classpose alt paketleriyle yeniden kuruluyor")
    install_log = os.path.join(work, "prepare_install.log")
    cmd = [args.uv, "pip", "install", "--python", py, "--no-deps", "--reinstall-package", "classpose", project]
    if run_streaming(cmd, env, install_log, lambda _line: None) != 0:
        log("HATA: Classpose kurulamadi - gunluk: %s" % install_log)
        return EXIT_IMPORT
    log("4/4 predict_wsi ice aktarma denetimi")
    check = subprocess.run([py, "-c", "import classpose.entrypoints.predict_wsi"], env=env,
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check.returncode != 0:
        last = (check.stderr or "").strip().splitlines()
        log("HATA: predict_wsi hala ice aktarilamiyor: %s" % (last[-1] if last else "?"))
        return EXIT_IMPORT
    log("DONE prepare ok=1")
    return EXIT_OK


def build_parser():
    p = argparse.ArgumentParser(description="Classpose koprusu (predict_wsi ayri surec olarak).")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("selftest", help="Paketleri + CUDA'yi dogrula.")
    s.set_defaults(func=cmd_selftest)

    pc = sub.add_parser("precheck", help="Slayti OpenSlide ile ac: boyut, bounds, um/px.")
    pc.add_argument("--slide", required=True)
    pc.set_defaults(func=cmd_precheck)

    v = sub.add_parser("verify", help="Model dosyasinin SHA-256 degerini dogrula.")
    v.add_argument("--model", required=True, choices=sorted(MODEL_SHA256))
    v.add_argument("--dir", required=True)
    v.set_defaults(func=cmd_verify)

    dl = sub.add_parser("download", help="Model (Hugging Face) ya da GrandQC agirliklari (Zenodo) indir.")
    dl.add_argument("--model", choices=sorted(MODEL_SHA256))
    dl.add_argument("--dir")
    dl.add_argument("--grandqc", action="store_true")
    dl.add_argument("--grandqc-dir")
    dl.set_defaults(func=cmd_download)

    d = sub.add_parser("detect", help="Classpose'u secili alanda calistir (predict_wsi, ayri surec).")
    d.add_argument("--slide", required=True)
    d.add_argument("--model", required=True, choices=sorted(MODEL_SHA256))
    d.add_argument("--model-dir", required=True)
    d.add_argument("--roi", required=True, help="FeatureCollection, (Multi)Polygon, tam cozunurluk QuPath koordinatlari.")
    d.add_argument("--out", required=True, help="Calisma/cikti klasoru.")
    d.add_argument("--device", default="auto", choices=["auto", "cuda", "cpu"])
    d.add_argument("--tta", action="store_true")
    d.add_argument("--batch-size", type=int, default=8)
    d.add_argument("--tile-size", type=int, default=1024)
    d.add_argument("--tissue", default=None, help="GrandQC Tissue_Detection_MPP10.pth yolu.")
    d.add_argument("--artefact", default=None, help="GrandQC GrandQC_MPP1.pth yolu (--tissue gerekir).")
    d.set_defaults(func=cmd_detect)

    pr = sub.add_parser("prepare", help="Kurulumu tamamla: cellpose'u sabitle, Classpose'u alt paketleriyle yeniden kur.")
    pr.add_argument("--uv", required=True, help="uv yolu (ortam yoneticisinin uv.exe'si).")
    pr.add_argument("--uv-cache", default=None)
    pr.add_argument("--work", default=None, help="Kaynak kodun acilacagi klasor (varsayilan: <ortam>/../classpose-src).")
    pr.set_defaults(func=cmd_prepare)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except Exception as exc:  # noqa: BLE001
        log("HATA: %s: %s" % (type(exc).__name__, exc))
        return EXIT_IMPORT


if __name__ == "__main__":
    # QuPath runner'i boru ile cagirir; Windows'ta boru kodlamasi cp1252 olur, sihirbaz ise UTF-8 okur.
    # Turkce klasor adlari (RESULT geojson=...) ve alt surec satirlari bozulmasin diye UTF-8'e sabitlenir.
    for _stream in (sys.stdout, sys.stderr):
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
