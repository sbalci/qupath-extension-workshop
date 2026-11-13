#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
retinanet_runner.py - QuPath atolyesi icin 2021/22 MIDOG DA-RetinaNet mitoz koprusu
===================================================================================

NE YAPAR:
    DeepMicroscopy'nin 2021/22 MIDOG referans dedektorunu (Domain-Adversarial RetinaNet,
    ResNet18 omurga, fastai v1) QuPath'te secilen bir BOLGE uzerinde calistirir. QuPath
    tarafi bolgeyi bir ROI goruntusu olarak disa aktarir; bu kopru referans deposunun
    KODUNU + AGIRLIGINI calisma aninda indirir (GrandQC gibi; depoda paketlenmez), o kodu
    import eder ve mitotik figur merkezlerini TABAN-piksel koordinatlarinda GeoJSON verir.

    Cikarim mantigi referansin detection.py'sinden (MyMitosisDetection) DOGRUDAN kullanilir;
    yeniden yazilmaz (model fastai v1'e siki bagli). Doseme 512 px, mean/std normalizasyonu
    [0.7481,0.5692,0.7225]/[0.1759,0.2284,0.1792], decode esigi 0.4, cikti esigi 0.64.

⚠️ ESKI / DOGRULANMAMIS ORTAM UYARISI (onemli):
    Bu model **fastai==1.0.61** gerektirir (referans requirements: torch>=1.6 / torchvision>=0.10,
    yani GPU'lu olabilir). Ancak fastai 1.x + modern torch bilinen bir KIRILGAN kombinasyondur:
    ortam cozulmeyebilir ya da import sirasinda hata verebilir (fastai 1.x bazi surumlerde
    torch ic API'lerine dayanir). Bu ATOLYEDE DOGRULANMAMISTIR. Ortam cozulmez/import edilmezse
    bilinen bir SINIRLAMADIR — bunun yerine **MIDOG25 FCOS** (modern, saglam, aşilmis referans)
    kullanin. Bkz. plan abort kriteri.

NE URETIR (ve ne URETMEZ):
    Yalnizca tespit (GeoJSON nokta). Klinik esik/derece/yorum URETMEZ. Arastirma/egitim.

AGIRLIK/KOD LISANSI:
    Referans deponun (MIDOG_reference_docker) LISANS dosyasi yok; kod+agirlik calisma aninda
    indirilir, bu depoda paketlenmez -> arastirma/egitim, kullanici sorumlulugunda. GrandQC
    ile ayni "depo indir + kodunu calistir" modeli (indirilen torch.load weights_only=False
    kullanir — indirdiginiz depoya guveniyorsunuz demektir). Atif: Aubreville ve ark., MIDOG.

KULLANIM:
    python retinanet_runner.py selftest
    python retinanet_runner.py download [--overwrite]
    python retinanet_runner.py detect --roi ROI.png --out cikti.geojson \\
        --origin 12000,8400 --downsample 2.0 [--det-thresh 0.64]
    python retinanet_runner.py detect --roi test_roi.png --native --out gate.geojson

REFERANS:
    https://github.com/DeepMicroscopy/MIDOG_reference_docker
"""

import argparse
import io
import json
import os
import sys
import zipfile

REPO_ZIP = "https://codeload.github.com/DeepMicroscopy/MIDOG_reference_docker/zip/refs/heads/master"
REPO_SUBDIR = "MIDOG_reference_docker-master"
WEIGHTS_REL = os.path.join("model_weights", "RetinaNetDA.pth")
DEFAULT_DET_THRESH = 0.64      # referans process.py cikti esigi (mitotik figur)
DECODE_THRESH = 0.4            # referans decode/sub-threshold esigi (mAP icin ileri tasinir)
NMS_THRESH = 0.4
PATCH = 512
MITOSIS_CLASS = "Mitotic figure"


def log(msg):
    print(msg, flush=True)


def cache_dir():
    th = os.environ.get("TIATOOLBOX_HOME")  # <dataRoot>/cache/tiatoolbox
    base = os.path.dirname(os.path.abspath(th)) if th else os.path.join(os.path.expanduser("~"), ".atolye", "cache")
    d = os.path.join(base, "midog-retinanet")
    os.makedirs(d, exist_ok=True)
    return d


def repo_dir():
    return os.path.join(cache_dir(), REPO_SUBDIR)


def weights_path():
    return os.path.join(repo_dir(), WEIGHTS_REL)


def read_roi_rgb(path):
    import numpy as np
    try:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = None
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


def cmd_download(args):
    """Referans deposunu (kod + agirlik) calisma aninda indir + ac (paketlenmez)."""
    import urllib.request
    dest = repo_dir()
    if os.path.isfile(weights_path()) and not args.overwrite:
        log("Depo + agirlik zaten var: %s" % dest)
        log("DONE download ok=1 path=%s" % weights_path())
        return 0
    log("Indiriliyor (depo zip): %s" % REPO_ZIP)
    try:
        with urllib.request.urlopen(REPO_ZIP) as resp:
            data = resp.read()
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            zf.extractall(cache_dir())
    except Exception as exc:  # noqa: BLE001
        log("HATA: depo indirilemedi/acilamadi: %s: %s" % (type(exc).__name__, exc))
        return 1
    if not os.path.isfile(weights_path()):
        log("HATA: agirlik zip icinde bulunamadi: %s" % weights_path())
        return 1
    log("Indirildi: %s (%.1f MB)" % (weights_path(), os.path.getsize(weights_path()) / 1e6))
    log("DONE download ok=1 path=%s" % weights_path())
    return 0


def _load_detector(batch_size):
    """Referans MyMitosisDetection'i indirilen kodtan import edip yukle (CPU-oncelikli)."""
    rd = repo_dir()
    if not os.path.isdir(rd) or not os.path.isfile(weights_path()):
        raise FileNotFoundError("Referans depo/agirlik yok — once `download` calistirin.")
    if rd not in sys.path:
        sys.path.insert(0, rd)
    # detect_threshold = DECODE_THRESH (referans: sub-threshold ileri tasinir); cikti esigi ayri.
    from detection import MyMitosisDetection  # noqa: E402 — indirilen kod
    md = MyMitosisDetection(weights_path(), PATCH, int(batch_size),
                            detect_threshold=DECODE_THRESH, nms_threshold=NMS_THRESH)
    md.load_model()
    return md


def cmd_detect(args):
    import numpy as np  # noqa: F401
    if not os.path.isfile(args.roi):
        log("HATA: ROI goruntusu bulunamadi: %s" % args.roi)
        return 3
    try:
        md = _load_detector(args.batch_size)
    except Exception as exc:  # noqa: BLE001
        log("HATA: RetinaNet yuklenemedi (eski ortam? fastai/torch import?): %s: %s"
            % (type(exc).__name__, exc))
        log("      Bu model fastai 1.0.61 + eski torch gerektirir; modern GPU'da calismaz.")
        log("      Alternatif: MIDOG25 FCOS dedektorunu kullanin.")
        return 1

    from util.nms_WSI import nms  # noqa: E402 — indirilen kod
    img = read_roi_rgb(args.roi)
    log("ROI   : %s (%d x %d px)   det_thresh=%.3f" % (os.path.basename(args.roi), img.shape[1], img.shape[0], args.det_thresh))
    # Upstream process_image 512 px pencerelerle kayar; ROI bir kenarda 512 px'ten kisaysa
    # negatif ofset uretip ValueError verir. Beyaz dolguyla en az 512x512 yap; dolgu alanina
    # dusen tespitler asagida atilir.
    orig_h, orig_w = int(img.shape[0]), int(img.shape[1])
    if orig_h < 512 or orig_w < 512:
        import numpy as np
        padded = np.full((max(orig_h, 512), max(orig_w, 512), 3), 255, dtype=img.dtype)
        padded[:orig_h, :orig_w, :] = img[:, :, :3]
        img = padded
        log("ROI 512 px'ten kucuk: beyaz dolgu ile %d x %d px yapildi." % (img.shape[1], img.shape[0]))
    try:
        import torch
        with torch.no_grad():
            boxes = md.process_image(img)          # np [x1,y1,x2,y2,pred,score]
            boxes = nms(boxes, NMS_THRESH)
    except Exception as exc:  # noqa: BLE001
        log("HATA: cikarim basarisiz: %s: %s" % (type(exc).__name__, exc))
        return 1

    ox, oy = 0.0, 0.0
    if args.origin:
        try:
            ox, oy = [float(v) for v in args.origin.split(",")]
        except Exception:  # noqa: BLE001
            log("UYARI: --origin ayristirilamadi (%s); 0,0." % args.origin)
    ds = float(args.downsample)

    feats = []
    n_mit = 0
    for det in boxes:
        x1, y1, x2, y2, pred, score = det[0], det[1], det[2], det[3], det[4], det[5]
        if float(score) <= args.det_thresh:
            continue
        cx = (float(x1) + float(x2)) / 2.0
        cy = (float(y1) + float(y2)) / 2.0
        if cx >= orig_w or cy >= orig_h:
            continue  # beyaz dolgu alaninda
        feats.append({
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [ox + cx * ds, oy + cy * ds]},
            "properties": {"objectType": "annotation",
                           "classification": {"name": MITOSIS_CLASS},
                           "probability": float(score)},
        })
        n_mit += 1

    out = args.out or (os.path.splitext(args.roi)[0] + "_retinanet.geojson")
    if os.path.dirname(out):
        os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": feats}, f)
    log("GeoJSON: %s" % out)
    log("RESULT geojson=%s" % out)
    log("DONE detect n=%d" % n_mit)
    return 0


def cmd_selftest(args):
    log("== retinanet_runner selftest ==")
    ok = True
    # SimpleITK burada YOK: ortam onu kurmaz ve tespit yolu (detection.py / nms_WSI.py) kullanmaz;
    # listede kalinca kontrol saglam ortamda bile hep BASARISIZ cikiyordu.
    for mod in ("numpy", "PIL", "torch", "torchvision", "fastai", "cv2"):
        try:
            m = __import__(mod)
            log("OK  %-12s %s" % (mod, getattr(m, "__version__", "?")))
        except Exception as exc:  # noqa: BLE001
            ok = False
            log("EKSIK %-10s %s" % (mod, exc))
    try:
        import torch
        log("OK  cihaz        cuda: %s (RetinaNet CPU-oncelikli, eski torch)" % ("var" if torch.cuda.is_available() else "yok (cpu)"))
    except Exception:  # noqa: BLE001
        pass
    rd = repo_dir()
    log("Depo/agirlik: %s (%s)" % (weights_path(), "VAR" if os.path.isfile(weights_path()) else "yok - `download` calistirin"))
    if not ok:
        log("SELFTEST BASARISIZ - eksik paket. NOT: fastai 1.0.61 eski torch (<1.8) ister; modern GPU'da import bile kirilabilir. FCOS onerilir.")
    else:
        log("SELFTEST OK (import). Gercek cikarim CPU'da yavastir.")
    return 0 if ok else 1


def build_parser():
    p = argparse.ArgumentParser(description="2021/22 MIDOG DA-RetinaNet mitoz dedektoru koprusu (eski fastai).")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("selftest"); s.set_defaults(func=cmd_selftest)

    dl = sub.add_parser("download", help="Referans depo (kod + agirlik) indir.")
    dl.add_argument("--overwrite", action="store_true")
    dl.set_defaults(func=cmd_download)

    d = sub.add_parser("detect", help="ROI goruntusunde RetinaNet mitoz cikarimi (GeoJSON).")
    d.add_argument("--roi", required=True)
    d.add_argument("--out", default="")
    d.add_argument("--origin", default="0,0")
    d.add_argument("--downsample", type=float, default=1.0)
    d.add_argument("--native", action="store_true")
    d.add_argument("--det-thresh", type=float, default=DEFAULT_DET_THRESH)
    d.add_argument("--device", default="cpu")   # bilgilendirme; model kendi cuda-tespitini yapar
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
