#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
fcos_runner.py - QuPath atolyesi icin MIDOG 2025 Track-1 FCOS mitoz dedektoru koprusu
=====================================================================================

NE YAPAR:
    DeepMicroscopy'nin **MIDOG 2025 Gorev 1 resmi referans** dedektorunu (torchvision
    FCOS + ResNeXt101 omurga) QuPath'te secilen bir BOLGE uzerinde calistirir. QuPath
    tarafi bolgeyi hedef cozunurlukte (target-mpp) bir ROI goruntusu olarak disa aktarir;
    bu kopru o goruntuyu referansin KENDI koduyla (1024x1024 kacikli doseme + sinif-basi
    NMS) isler ve mitotik figurleri **TABAN-piksel** koordinatlarinda GeoJSON olarak verir.
    Koordinatlar QuPath'e geri alinabilsin diye ROI kokeni (--origin) ve downsample
    (--downsample) ile tam-cozunurluk taban pikseline eslenir.

    Model kurulumu ve dosemeli cikarim bu depoda YENIDEN YAZILMAZ / KOPYALANMAZ: referans
    deposu (MIDOG25_T1_reference_docker, sabitlenmis commit) `download` ile calisma aninda
    indirilir ve utils/model.py (make_fcos_model) + utils/inference_utils.py
    (Torchvision_Inference, PatchConfig, InferenceConfig) DEGISTIRILMEDEN import edilir.
    Bu koprunun kendi isi yalnizca: ROI okuma + beyaz dolgu, checkpoint yukleme, esik +
    taban-piksel donusumu ve GeoJSON yazimi. Referans varsayilanlari: patch 1024,
    overlap 0.4, nms_thresh 0.3, det_thresh 0.548.

NE URETIR (ve ne URETMEZ):
    Yalnizca tespit (GeoJSON nokta ozellikleri) uretir. Hicbir klinik esik/derece/yorum
    uretmez. Yalnizca arastirma/egitim amaclidir.

AGIRLIK + KOD LISANSI:
    FCOS_x101.ckpt (v1.0.0 GitHub yayini) ve referans kodu (sabit commit) CALISMA ANINDA
    indirilir; bu depoda/JAR'da PAKETLENMEZ. Kaynak deponun LISANS dosyasi yoktur ->
    arastirma/egitim amacli, kullanicinin sorumlulugunda kullanim. Atif: MIDOG 2025
    (midog2025.grand-challenge.org), DeepMicroscopy.

DOGRULUK KONTROLU (kullanici calistirir):
    Ayni ROI + ayni esikle onceki bir calistirmanin `DONE detect n=` sayisi ve nokta konumlari
    yeniden uretilmelidir. Referans deposunun test ROI'si indirilen kodun icindedir:
    <onbellek>/MIDOG25_T1_reference_docker-<commit>/test/input/interf0/images/
    histopathology-roi-cropout/350.tiff  (`detect --roi 350.tiff --native`).
    mpp/olcek ise QuPath tarafinin ayri sorumlulugudur ("noktalar yuksek yakinlastirmada
    gercek mitozlarin uzerine dusuyor mu?" gorsel kontrolu).

KULLANIM:
    python fcos_runner.py selftest
    python fcos_runner.py download [--overwrite]
    python fcos_runner.py detect --roi ROI.png --out cikti.geojson \\
        --origin 12000,8400 --downsample 2.0 --device cuda [--det-thresh 0.548]
    python fcos_runner.py detect --roi 350.tiff --native --out kontrol.geojson

REFERANS:
    Aubreville M ve ark., MIDOG 2025 (Mitosis Domain Generalization). Resmi Track-1
    referans: https://github.com/DeepMicroscopy/MIDOG25_T1_reference_docker
"""

import argparse
import io
import json
import os
import sys
import types
import zipfile

# Agirlik indirme adresi (v1.0.0 yayini) — koprude PAKETLENMEZ, calisma aninda cekilir.
WEIGHTS_URL = ("https://github.com/DeepMicroscopy/MIDOG25_T1_reference_docker/"
               "releases/download/v1.0.0/FCOS_x101.ckpt")
WEIGHTS_NAME = "FCOS_x101.ckpt"

# Referans kodu — sabitlenmis commit (tekrarlanabilirlik); calisma aninda indirilir, paketlenmez.
UPSTREAM_SHA = "633e72d9cbb8ad704c2169008bd6050430713cd3"
UPSTREAM_ZIP = ("https://codeload.github.com/DeepMicroscopy/MIDOG25_T1_reference_docker/zip/"
                + UPSTREAM_SHA)
UPSTREAM_DIRNAME = "MIDOG25_T1_reference_docker-" + UPSTREAM_SHA

# Referans docker varsayilanlari (resources/*.yaml).
DEFAULT_BACKBONE = "resnext101_32x8d"
DEFAULT_PATCH = 1024
DEFAULT_OVERLAP = 0.4
DEFAULT_NMS = 0.3
DEFAULT_DET_THRESH = 0.548
MITOSIS_CLASS = "Mitotic figure"

# import adi -> pip paket adi (eksik-paket mesajlari icin)
PIP_NAME = {"SimpleITK": "SimpleITK", "yaml": "pyyaml", "tqdm": "tqdm", "torch": "torch",
            "torchvision": "torchvision", "numpy": "numpy", "PIL": "Pillow"}


def log(msg):
    """Satir-tamponlu yaz; QuPath log alani canli akar."""
    print(msg, flush=True)


def cache_dir():
    """Onbellek dizini: <TIATOOLBOX_HOME kardesi>/midog-fcos veya ~/.atolye/cache/midog-fcos.

    QuPath tarafi HF_HOME/TIATOOLBOX_HOME gibi env'leri veri koku altina yonlendirir;
    burada FCOS icin ayni cache kokunu (bir ust dizin) kullaniyoruz.
    """
    th = os.environ.get("TIATOOLBOX_HOME")  # <dataRoot>/cache/tiatoolbox
    if th:
        base = os.path.dirname(os.path.abspath(th))  # <dataRoot>/cache
    else:
        base = os.path.join(os.path.expanduser("~"), ".atolye", "cache")
    d = os.path.join(base, "midog-fcos")
    os.makedirs(d, exist_ok=True)
    return d


def weights_path():
    return os.path.join(cache_dir(), WEIGHTS_NAME)


def upstream_dir():
    return os.path.join(cache_dir(), UPSTREAM_DIRNAME)


def upstream_ready():
    u = os.path.join(upstream_dir(), "utils")
    return (os.path.isfile(os.path.join(u, "model.py"))
            and os.path.isfile(os.path.join(u, "inference_utils.py")))


def safe_extract(zf, dest):
    """Zip'i hedef disina tasan (../) girdileri reddederek ac."""
    root = os.path.realpath(dest)
    for member in zf.namelist():
        target = os.path.realpath(os.path.join(dest, member))
        if not (target == root or target.startswith(root + os.sep)):
            raise RuntimeError("guvensiz zip girdisi: %s" % member)
    zf.extractall(dest)


def download_upstream(overwrite=False):
    """Referans kodunu (sabit commit) onbellege indir + ac. Basariliysa True."""
    if upstream_ready() and not overwrite:
        log("Referans kod zaten var: %s" % upstream_dir())
        return True
    import urllib.request
    log("Referans kod indiriliyor (commit %s): %s" % (UPSTREAM_SHA[:12], UPSTREAM_ZIP))
    try:
        with urllib.request.urlopen(UPSTREAM_ZIP, timeout=120) as resp:
            data = resp.read()
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            safe_extract(zf, cache_dir())
    except Exception as exc:  # noqa: BLE001
        log("HATA: referans kod indirilemedi/acilamadi: %s: %s" % (type(exc).__name__, exc))
        return False
    if not upstream_ready():
        log("HATA: zip icinde utils/model.py + utils/inference_utils.py bulunamadi: %s" % upstream_dir())
        return False
    log("Referans kod hazir: %s" % upstream_dir())
    return True


def import_upstream():
    """Indirilen referans kodunu DEGISTIRMEDEN 'utils' paketi olarak import et.

    Depoda utils/__init__.py yoktur; site-packages'taki olasi baska bir 'utils' ile
    karismasin diye paket nesnesini sys.modules'e acikca kaydediyoruz.
    """
    if not upstream_ready():
        raise FileNotFoundError("Referans kod yok (%s) - once `download` calistirin "
                                "(sihirbazda: Modeli yerel indir)." % upstream_dir())
    import importlib
    pkg = types.ModuleType("utils")
    pkg.__path__ = [os.path.join(upstream_dir(), "utils")]
    sys.modules["utils"] = pkg
    try:
        model_mod = importlib.import_module("utils.model")
        infer_mod = importlib.import_module("utils.inference_utils")
    except ModuleNotFoundError as exc:
        missing = (exc.name or "?").split(".")[0]
        raise RuntimeError(
            "Referans kod icin Python paketi eksik: %s (pip: %s). midog-fcos ortami eski olabilir - "
            "Atolye Python ortam yoneticisinden 'MIDOG25 FCOS - mitoz dedektoru' ortamini YENIDEN KURUN."
            % (missing, PIP_NAME.get(missing, missing)))
    return model_mod, infer_mod


def load_checkpoint_into(model, checkpoint):
    """Lightning checkpoint'inden agirliklari modele yukle ('model.' onekini soy).

    Guvenlik: once weights_only=True dene (yalnizca tensor cozer, kod calistirmaz).
    Bazi Lightning .ckpt'leri tensor-disi ustveri (hyper_parameters vb.) tasidigi icin
    weights_only=True basarisiz olabilir; o durumda weights_only=False'a dus (agirliklar
    kullanicinin bilerek indirdigi genel bir yayindan gelir).
    """
    import torch
    try:
        ckpt = torch.load(checkpoint, map_location="cpu", weights_only=True)
    except Exception:  # noqa: BLE001 — Lightning ustverisi weights_only=True'yu kirabilir
        log("UYARI: weights_only=True basarisiz; guvenilir yayin varsayimiyla tam yukleme.")
        ckpt = torch.load(checkpoint, map_location="cpu", weights_only=False)
    state = ckpt["state_dict"] if isinstance(ckpt, dict) and "state_dict" in ckpt else ckpt
    cleaned = {(k[6:] if k.startswith("model.") else k): v for k, v in state.items()}
    result = model.load_state_dict(cleaned, strict=False)
    missing = list(getattr(result, "missing_keys", []) or [])
    unexpected = list(getattr(result, "unexpected_keys", []) or [])
    if missing:
        log("UYARI: eksik agirlik anahtari: %d (ilk: %s)" % (len(missing), missing[0]))
    if unexpected:
        log("UYARI: fazladan agirlik anahtari: %d (ilk: %s)" % (len(unexpected), unexpected[0]))
    model.eval()
    return model


def read_roi_rgb(path):
    """ROI goruntusunu HxWx3 uint8 numpy dizisine oku (PIL; buyuk TIFF icin tifffile yedek)."""
    import numpy as np
    try:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = None  # buyuk ROI'lerde DecompressionBomb uyarisini kapat
        return np.asarray(Image.open(path).convert("RGB"))
    except Exception as exc:  # noqa: BLE001
        try:
            import tifffile
            arr = tifffile.imread(path)
            if arr.ndim == 2:
                arr = np.stack([arr] * 3, axis=-1)
            return np.ascontiguousarray(arr[..., :3])
        except Exception as exc2:  # noqa: BLE001
            raise RuntimeError("ROI goruntusu okunamadi (%s / %s)" % (exc, exc2))


def pad_white(img, size):
    """ROI'yi en az size x size olacak sekilde sag/alttan BEYAZ doldur.

    Referans doseme (_get_coords) goruntunun patch boyutundan buyuk oldugunu varsayar;
    kucuk ROI'de koordinat negatife duser. Dolgu olcegi korur (yeniden boyutlandirma yok),
    koken degismez; dolgu alanindaki tespitler asagida elenir.
    """
    import numpy as np
    h, w = img.shape[0], img.shape[1]
    ph, pw = max(h, size), max(w, size)
    if ph == h and pw == w:
        return np.ascontiguousarray(img[:, :, :3].astype(np.uint8))
    canvas = np.full((ph, pw, 3), 255, dtype=np.uint8)
    canvas[:h, :w, :] = img[:, :, :3]
    return canvas


# ----------------------------------------------------------------------------
# Alt komutlar
# ----------------------------------------------------------------------------
def cmd_detect(args):
    import torch

    if not os.path.isfile(args.roi):
        log("HATA: ROI goruntusu bulunamadi: %s" % args.roi)
        return 3
    ckpt = args.checkpoint or weights_path()
    if not os.path.isfile(ckpt):
        log("HATA: FCOS agirligi bulunamadi: %s" % ckpt)
        log("      Once `download` alt komutunu calistirin (veya --checkpoint verin).")
        return 2
    try:
        model_mod, infer_mod = import_upstream()
    except FileNotFoundError as exc:
        log("HATA: %s" % exc)
        return 2
    except Exception as exc:  # noqa: BLE001
        log("HATA: %s" % exc)
        return 1
    import SimpleITK as sitk  # referans kodu zaten import etti; burada eksik olamaz

    device = args.device
    if device == "cuda" and not torch.cuda.is_available():
        log("UYARI: CUDA yok, CPU'ya dusuluyor (yavas).")
        device = "cpu"

    log("Model : FCOS/%s   ckpt: %s   referans kod: %s   cihaz: %s"
        % (args.backbone, os.path.basename(ckpt), UPSTREAM_SHA[:12], device))
    try:
        # weights=None: omurga ImageNet agirligi INDIRILMEZ; tum agirliklar checkpoint'ten gelir.
        model = model_mod.make_fcos_model(backbone=args.backbone, weights=None,
                                          det_thresh=0.2, patch_size=args.patch_size)
        load_checkpoint_into(model, ckpt)
    except Exception as exc:  # noqa: BLE001
        log("HATA: model/agirlik yuklenemedi: %s: %s" % (type(exc).__name__, exc))
        return 1

    img = read_roi_rgb(args.roi)
    roi_h, roi_w = img.shape[0], img.shape[1]
    log("ROI   : %s (%d x %d px)   overlap=%.2f  nms=%.2f  det_thresh=%.3f"
        % (os.path.basename(args.roi), roi_w, roi_h, args.overlap, args.nms, args.det_thresh))

    patch_cfg = infer_mod.PatchConfig(size=args.patch_size, overlap=args.overlap, level=0)
    infer_cfg = infer_mod.InferenceConfig(batch_size=args.batch_size, num_workers=0, device=device,
                                          nms_thresh=args.nms, score_thresh=args.det_thresh)
    strategy = infer_mod.Torchvision_Inference(model, inference_config=infer_cfg, patch_config=patch_cfg)
    sitk_img = sitk.GetImageFromArray(pad_white(img, args.patch_size), isVector=True)
    res = strategy.process_image(sitk_img)   # ROI-piksel kutulari, sinif-basi NMS uygulanmis

    # ROI-piksel merkez -> taban-piksel: base = origin + roi_px * downsample
    ox, oy = 0.0, 0.0
    if args.origin:
        try:
            ox, oy = [float(v) for v in args.origin.split(",")]
        except Exception:  # noqa: BLE001
            log("UYARI: --origin ayristirilamadi (%s); 0,0 kullanildi." % args.origin)
    ds = float(args.downsample)

    feats = []
    for (x1, y1, x2, y2), score in zip(res["boxes"], res["scores"]):
        if float(score) <= args.det_thresh:      # referans: label = score > det_thresh
            continue
        cx = (float(x1) + float(x2)) / 2.0
        cy = (float(y1) + float(y2)) / 2.0
        if cx >= roi_w or cy >= roi_h:           # beyaz dolgu alani
            continue
        feats.append({
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [ox + cx * ds, oy + cy * ds]},
            "properties": {"objectType": "annotation",
                           "classification": {"name": MITOSIS_CLASS},
                           "probability": float(score)},
        })

    out = args.out or (os.path.splitext(args.roi)[0] + "_fcos.geojson")
    if os.path.dirname(out):
        os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": feats}, f)
    log("GeoJSON: %s" % out)
    log("RESULT geojson=%s" % out)
    log("DONE detect n=%d" % len(feats))
    return 0


def cmd_download(args):
    """FCOS_x101.ckpt (v1.0.0 yayini) + referans kodunu (sabit commit) onbellege indir."""
    import urllib.request
    ok = download_upstream(overwrite=args.overwrite)
    dest = weights_path()
    if os.path.isfile(dest) and not args.overwrite:
        log("Agirlik zaten var: %s (%.1f MB)" % (dest, os.path.getsize(dest) / 1e6))
    else:
        log("Indiriliyor: %s" % WEIGHTS_URL)
        log("  -> %s" % dest)
        try:
            tmp = dest + ".part"
            with urllib.request.urlopen(WEIGHTS_URL) as resp, open(tmp, "wb") as out:
                total = int(resp.headers.get("Content-Length", 0))
                done = 0
                while True:
                    chunk = resp.read(1 << 20)
                    if not chunk:
                        break
                    out.write(chunk)
                    done += len(chunk)
                    if total:
                        log("  %d / %d MB" % (done // (1 << 20), total // (1 << 20)))
            os.replace(tmp, dest)
            log("Indirildi: %s (%.1f MB)" % (dest, os.path.getsize(dest) / 1e6))
        except Exception as exc:  # noqa: BLE001
            log("HATA: agirlik indirilemedi: %s: %s" % (type(exc).__name__, exc))
            ok = False
    if not ok:
        return 1
    log("DONE download ok=1 path=%s" % dest)
    return 0


def cmd_selftest(args):
    log("== fcos_runner selftest ==")
    ok = True
    for mod in ("numpy", "PIL", "torch", "torchvision", "SimpleITK", "yaml", "tqdm"):
        try:
            m = __import__(mod)
            log("OK  %-12s %s" % (mod, getattr(m, "__version__", "?")))
        except Exception as exc:  # noqa: BLE001
            ok = False
            log("EKSIK %-10s %s  (pip: %s)" % (mod, exc, PIP_NAME.get(mod, mod)))
    try:
        import torch
        log("OK  cihaz        cuda: %s" % ("var" if torch.cuda.is_available() else "yok (cpu)"))
    except Exception:  # noqa: BLE001
        pass
    wp = weights_path()
    log("Agirlik     : %s (%s)" % (wp, "VAR" if os.path.isfile(wp) else "yok - `download` calistirin"))
    log("Referans kod: %s (%s)" % (upstream_dir(), "VAR" if upstream_ready() else "yok - `download` calistirin"))
    if ok and upstream_ready():
        try:
            import_upstream()
            log("OK  referans kod import edildi (utils.model, utils.inference_utils)")
        except Exception as exc:  # noqa: BLE001
            ok = False
            log("HATA referans kod import edilemedi: %s" % exc)
    log("SELFTEST OK" if ok else "SELFTEST BASARISIZ - eksik paketler var (env yoneticisi: midog-fcos, yeniden kurun)")
    return 0 if ok else 1


def build_parser():
    p = argparse.ArgumentParser(description="MIDOG 2025 Track-1 FCOS mitoz dedektoru koprusu.")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("selftest", help="Bagimliliklari (torch, torchvision, SimpleITK, yaml) dogrula.")
    s.set_defaults(func=cmd_selftest)

    dl = sub.add_parser("download", help="FCOS agirligi (v1.0.0) + referans kodu (sabit commit) indir.")
    dl.add_argument("--overwrite", action="store_true")
    dl.set_defaults(func=cmd_download)

    d = sub.add_parser("detect", help="ROI goruntusunde FCOS mitoz cikarimi (GeoJSON).")
    d.add_argument("--roi", required=True, help="Onceden cikarilmis ROI goruntusu (TIFF/PNG, RGB).")
    d.add_argument("--out", default="", help="Cikti GeoJSON yolu.")
    d.add_argument("--origin", default="0,0", help="ROI'nin sol-ust TABAN-piksel kokeni 'x,y'.")
    d.add_argument("--downsample", type=float, default=1.0, help="ROI piksel -> taban piksel olcegi.")
    d.add_argument("--native", action="store_true",
                   help="Dogruluk kontrolu: origin=0,0 downsample=1 (referans ROI native).")
    d.add_argument("--checkpoint", default="", help="FCOS_x101.ckpt yolu (bos = onbellek).")
    d.add_argument("--backbone", default=DEFAULT_BACKBONE)
    d.add_argument("--patch-size", type=int, default=DEFAULT_PATCH)
    d.add_argument("--overlap", type=float, default=DEFAULT_OVERLAP)
    d.add_argument("--nms", type=float, default=DEFAULT_NMS)
    d.add_argument("--det-thresh", type=float, default=DEFAULT_DET_THRESH,
                   help="Mitotik-figur esigi (referans 0.548); dusuk = daha cok tespit.")
    d.add_argument("--device", default="cuda")
    d.add_argument("--batch-size", type=int, default=8)
    d.set_defaults(func=cmd_detect)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    if getattr(args, "native", False):
        args.origin, args.downsample = "0,0", 1.0
    try:
        return args.func(args)
    except FileNotFoundError as exc:
        log("HATA (dosya yok): %s" % exc)
        return 3
    except Exception as exc:  # noqa: BLE001
        log("HATA: %s: %s" % (type(exc).__name__, exc))
        return 1


if __name__ == "__main__":
    sys.exit(main())
