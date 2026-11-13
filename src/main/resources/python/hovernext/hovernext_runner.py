#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
hovernext_runner.py - QuPath atolyesi icin HoVer-NeXt cekirdek segmentasyon + siniflama koprusu
==============================================================================================

NE YAPAR:
    HoVer-NeXt'i (Baumann ve ark., MIDL 2024) QuPath'te secilen bir BOLGE uzerinde calistirir.
    QuPath bolgeyi ~0.5 um/px'te bir PNG ROI olarak disa aktarir; bu kopru HoVer-NeXt'in
    DEGISTIRILMEMIS `main.py` betigini AYRI BIR SUREC olarak calistirir, ciktisini (ornek-etiket
    haritasi `pinst_pp.zip` + `class_inst.json`) okur ve her cekirdegi TABAN-piksel
    koordinatlarinda GeoJSON olarak verir: poligon (kontur) ya da merkez noktasi.

    Lizard modelinin 7 sinifi (GeoJSON sinif adlari): neutrophil, epithelial-cell, lymphocyte,
    plasma-cell, eosinophil, connective-tissue-cell, mitosis.

LISANS SINIRI (onemli):
    HoVer-NeXt kodu GPL-3.0; atolye eklentisi Apache-2.0. Bu yuzden HoVer-NeXt kodu bu depoda /
    JAR'da PAKETLENMEZ, KOPYALANMAZ ve bu koprude IMPORT EDILMEZ: sabitlenmis commit calisma
    aninda indirilir ve yalnizca ayri bir surec olarak calistirilir; veri alisverisi dosyalar
    uzerindendir. Cekirdek konturlari bu koprude, HoVer-NeXt kodundan bagimsiz olarak OpenCV
    ile ornek-etiket haritasindan cikarilir.
    Agirliklar: Zenodo 10635618, CC BY-NC-SA 4.0 (ticari olmayan) - calisma aninda indirilir,
    Zenodo'nun yayimladigi MD5 ile dogrulanir.

NE URETIR (ve ne URETMEZ):
    Cekirdek konturu/merkezi + sinif adi. Klinik esik/derece/yorum URETMEZ. Arastirma/egitim.

SINIRLAMALAR:
    * Lizard = kolorektal H&E; diger dokulara genelleme dogrulanmamistir.
    * Mitoz sinifi pHH3 destekli etiketlerle egitildi; yayimlanmis MITOZA-OZEL basarim metrigi
      YOKTUR (makale genel cekirdek metrikleri verir). Mitoz dedektoru degildir.
    * NVIDIA CUDA GPU ZORUNLUDUR (HoVer-NeXt CPU'da calismayi reddeder).
    * Model 0.5 um/px icin egitildi; --mpp 0.40-0.60 disindaysa kopru reddeder (kalibrasyonsuz
      goruntude sessizce yanlis olcek + piksel-tabanli boyut esikleri bozulur).
    * HoVer-NeXt model kurarken timm ile ImageNet on-egitimli ConvNeXtV2 omurgasini
      HuggingFace'ten ceker; `download` bunu onbellege almaya calisir ama cevrimdisi calisma
      garanti edilmez.

KULLANIM:
    python hovernext_runner.py selftest
    python hovernext_runner.py download --model lizard_convnextv2_large
    python hovernext_runner.py detect --roi ROI.png --out cikti.geojson \\
        --origin 12000,8400 --downsample 2.0 --mpp 0.5 --mode polygons --classes all
    python hovernext_runner.py detect --roi ROI.png --out mitoz.geojson \\
        --origin 12000,8400 --downsample 2.0 --mpp 0.5 --mode points --classes mitosis

REFERANS:
    Baumann E, Dislich B, Rumberger JL, Nagtegaal ID, Martinez MR, Zlobec I. HoVer-NeXt: A Fast
    Nuclei Segmentation and Classification Pipeline for Next Generation Histopathology.
    MIDL 2024, PMLR 250. https://proceedings.mlr.press/v250/baumann24a.html
    Kod: https://github.com/digitalpathologybern/hover_next_inference (GPL-3.0)
    Agirlik: https://zenodo.org/records/10635618 (CC BY-NC-SA 4.0)
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import zipfile

# HoVer-NeXt cikarim kodu — sabitlenmis commit (ciktilarin bicimi bu surume gore okunur).
REPO_SHA = "0e99d535fd64640b5486ca9ee4f3864fa96ba83c"
REPO_ZIP = "https://codeload.github.com/digitalpathologybern/hover_next_inference/zip/" + REPO_SHA
REPO_DIRNAME = "hover_next_inference-" + REPO_SHA

ZENODO_URL = "https://zenodo.org/records/10635618/files/%s.zip?download=1"
# Zenodo kaydinin (records/10635618) yayimladigi MD5 saglama toplamlari.
WEIGHTS_MD5 = {
    "lizard_convnextv2_large": "50b8e05c30f91356d20690ac60ce3181",
    "lizard_convnextv2_base": "af62e2228397180913447ca6e3b7a0e5",
    "lizard_convnextv2_tiny": "e244745464814f8e9ef0cd408c749c8f",
}
DEFAULT_MODEL = "lizard_convnextv2_large"

# Lizard sinif kimlikleri -> GeoJSON sinif adi (class_inst.json degerleri).
LIZARD_CLASSES = {
    1: "neutrophil",
    2: "epithelial-cell",
    3: "lymphocyte",
    4: "plasma-cell",
    5: "eosinophil",
    6: "connective-tissue-cell",
    7: "mitosis",
}
MODEL_MPP = 0.5
MPP_TOL = 0.20  # +/- %20 -> 0.40-0.60 um/px

# (import adi, kurulum ipucu) — HoVer-NeXt cikarim yolunun import ettikleri.
REQUIRED_MODULES = [
    ("numpy", "numpy<2"), ("torch", "torch==2.1.1"), ("torchvision", "torchvision==0.16.1"),
    ("timm", "timm==0.9.6"), ("segmentation_models_pytorch", "segmentation-models-pytorch==0.1.0"),
    ("zarr", "zarr==2.16.1"), ("numcodecs", "numcodecs==0.12.1"), ("cv2", "opencv-python"),
    ("skimage", "scikit-image"), ("scipy", "scipy"), ("openslide", "openslide-python + openslide-bin"),
    ("pylibCZIrw", "pylibCZIrw"), ("geojson", "geojson"), ("shapely", "shapely"),
    ("toml", "toml"), ("requests", "requests"), ("tqdm", "tqdm"), ("PIL", "Pillow"),
]


def log(msg):
    """Satir-tamponlu yaz; QuPath log alani canli akar."""
    print(msg, flush=True)


def cache_dir():
    """Onbellek: <TIATOOLBOX_HOME kardesi>/hovernext veya ~/.atolye/cache/hovernext."""
    th = os.environ.get("TIATOOLBOX_HOME")  # <dataRoot>/cache/tiatoolbox
    base = os.path.dirname(os.path.abspath(th)) if th else os.path.join(os.path.expanduser("~"), ".atolye", "cache")
    d = os.path.join(base, "hovernext")
    os.makedirs(d, exist_ok=True)
    return d


def repo_dir():
    return os.path.join(cache_dir(), REPO_DIRNAME)


def repo_ready():
    return os.path.isfile(os.path.join(repo_dir(), "main.py"))


def weights_ready(model):
    d = os.path.join(repo_dir(), model)
    return (os.path.isfile(os.path.join(d, "params.toml"))
            and os.path.isfile(os.path.join(d, "train", "best_model")))


def safe_extract(zf, dest):
    """Zip'i hedef disina tasan (../) girdileri reddederek ac."""
    root = os.path.realpath(dest)
    for member in zf.namelist():
        target = os.path.realpath(os.path.join(dest, member))
        if not (target == root or target.startswith(root + os.sep)):
            raise RuntimeError("guvensiz zip girdisi: %s" % member)
    zf.extractall(dest)


def download_file(url, dest, expected_md5=None):
    """Akisli indir (.part -> yeniden adlandir); verilirse MD5 dogrula."""
    import urllib.request
    tmp = dest + ".part"
    digest = hashlib.md5(usedforsecurity=False)  # yalniz butunluk (Zenodo MD5 yayimlar)
    req = urllib.request.Request(url, headers={"User-Agent": "qupath-atolye-hovernext"})
    with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as out:
        total = int(resp.headers.get("Content-Length", 0) or 0)
        done, last_step = 0, -1
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            out.write(chunk)
            digest.update(chunk)
            done += len(chunk)
            step = (done >> 20) // 25  # ~25 MB'da bir satir
            if step != last_step:
                last_step = step
                log("  %d / %s MB" % (done >> 20, (total >> 20) if total else "?"))
    if expected_md5 and digest.hexdigest().lower() != expected_md5.lower():
        os.remove(tmp)
        raise RuntimeError("MD5 uyusmuyor (beklenen %s, bulunan %s)" % (expected_md5, digest.hexdigest()))
    os.replace(tmp, dest)
    return dest


def parse_classes(spec):
    """'all' -> None; 'mitosis,lymphocyte' / '7,3' -> {7, 3}."""
    spec = (spec or "all").strip()
    if spec.lower() in ("", "all", "*"):
        return None
    by_name = {v: k for k, v in LIZARD_CLASSES.items()}
    out = set()
    for tok in spec.split(","):
        tok = tok.strip()
        if not tok:
            continue
        if tok.isdigit() and int(tok) in LIZARD_CLASSES:
            out.add(int(tok))
        elif tok in by_name:
            out.add(by_name[tok])
        else:
            raise ValueError("bilinmeyen sinif: %s (secenekler: %s)" % (tok, ", ".join(by_name)))
    return out


def load_labels(path):
    """pinst_pp.zip (zarr) ornek-etiket haritasini 2B numpy dizisi olarak oku."""
    import numpy as np
    if not os.path.isfile(path):
        return None
    import zarr
    arr = None
    try:
        arr = zarr.load(path)
    except Exception:  # noqa: BLE001
        arr = None
    if arr is None:
        try:
            z = zarr.open(path, mode="r")
            arr = z[:]
            try:
                z.store.close()
            except Exception:  # noqa: BLE001
                pass
        except Exception as exc:  # noqa: BLE001
            log("UYARI: %s okunamadi: %s" % (path, exc))
            return None
    arr = np.asarray(arr)
    while arr.ndim > 2:
        arr = arr[0]
    return arr


def contour_ring(labels, objs, nid, ox, oy, ds):
    """Tek bir cekirdegin dis konturunu TABAN-piksel halkasi olarak dondur (kapali)."""
    import cv2
    import numpy as np
    if nid < 1 or nid > len(objs) or objs[nid - 1] is None:
        return None
    sl = objs[nid - 1]
    r0, c0 = sl[0].start, sl[1].start
    sub = (labels[sl] == nid).astype(np.uint8)
    if not sub.any():
        return None
    found = cv2.findContours(sub, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    cnts = found[-2]  # OpenCV 3 (3 deger) ve 4 (2 deger) ile uyumlu
    big = max(cnts, key=cv2.contourArea).reshape(-1, 2) if len(cnts) else None
    if big is None or len(big) < 3 or cv2.contourArea(big) < 1.0:
        # cok kucuk nesne: piksel kenarlarindan sinir kutusu
        r1, c1 = sl[0].stop, sl[1].stop
        pts = [(c0, r0), (c1, r0), (c1, r1), (c0, r1)]
    else:
        # OpenCV sinir PIKSEL indekslerini verir; piksel merkezi = indeks + 0.5
        pts = [(float(x) + c0 + 0.5, float(y) + r0 + 0.5) for x, y in big]
    ring = [[round(ox + x * ds, 2), round(oy + y * ds, 2)] for x, y in pts]
    ring.append(list(ring[0]))
    return ring


def run_child(cmd, cwd):
    """HoVer-NeXt'i ayri surec olarak calistir; ciktisini canli akit. (donus kodu, son satirlar)"""
    env = dict(os.environ)
    env.setdefault("PYTHONIOENCODING", "utf-8")
    env["PYTHONUNBUFFERED"] = "1"
    env.setdefault("TQDM_MININTERVAL", "10")  # ilerleme cubugu satir seli olmasin
    tail = []
    proc = subprocess.Popen(cmd, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace", bufsize=1)
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
    return proc.returncode, tail


def explain_failure(tail):
    txt = "\n".join(tail)
    low = txt.lower()
    if "no gpu available" in low or "cuda driver" in low or "no cuda gpus" in low:
        log("HATA: GPU/CUDA bulunamadi - HoVer-NeXt yalnizca NVIDIA GPU ile calisir.")
    elif "out of memory" in low:
        log("HATA: GPU bellegi yetmedi - Batch size'i dusurun (or. 8) veya daha kucuk model (base/tiny) secin.")
    elif "modulenotfounderror" in low or "importerror" in low:
        log("HATA: HoVer-NeXt icin Python paketi eksik/uyumsuz - ortam yoneticisinden 'hovernext' ortamini yeniden kurun.")
    else:
        log("HATA: HoVer-NeXt sureci basarisiz oldu - yukaridaki gunluge bakin.")


# ----------------------------------------------------------------------------
# Alt komutlar
# ----------------------------------------------------------------------------
def cmd_download(args):
    model = args.model
    if model not in WEIGHTS_MD5:
        log("HATA: desteklenmeyen model: %s (secenekler: %s)" % (model, ", ".join(WEIGHTS_MD5)))
        return 2
    # 1) kod (GPL-3.0, sabit commit) — yalniz ayri surec olarak calistirilir
    if not repo_ready() or args.overwrite:
        log("HoVer-NeXt kodu indiriliyor (GPL-3.0, commit %s): %s" % (REPO_SHA[:12], REPO_ZIP))
        zpath = os.path.join(cache_dir(), REPO_DIRNAME + ".zip")
        try:
            download_file(REPO_ZIP, zpath)
            with zipfile.ZipFile(zpath) as zf:
                safe_extract(zf, cache_dir())
            os.remove(zpath)
        except Exception as exc:  # noqa: BLE001
            log("HATA: kod indirilemedi/acilamadi: %s: %s" % (type(exc).__name__, exc))
            return 1
        if not repo_ready():
            log("HATA: main.py bulunamadi: %s" % repo_dir())
            return 1
    log("Kod hazir: %s" % repo_dir())
    # 2) agirlik (Zenodo, CC BY-NC-SA 4.0) — HoVer-NeXt'in bekledigi yere: <repo>/<model>/
    if not weights_ready(model) or args.overwrite:
        url = ZENODO_URL % model
        zpath = os.path.join(cache_dir(), model + ".zip")
        log("Agirlik indiriliyor (Zenodo 10635618, CC BY-NC-SA 4.0): %s" % url)
        try:
            download_file(url, zpath, WEIGHTS_MD5[model])
            log("MD5 dogrulandi.")
            with zipfile.ZipFile(zpath) as zf:
                safe_extract(zf, repo_dir())
            os.remove(zpath)
        except Exception as exc:  # noqa: BLE001
            log("HATA: agirlik indirilemedi/acilamadi: %s: %s" % (type(exc).__name__, exc))
            return 1
        if not weights_ready(model):
            log("HATA: agirlik klasoru beklenen yapida degil: %s" % os.path.join(repo_dir(), model))
            return 1
    log("Agirlik hazir: %s" % os.path.join(repo_dir(), model))
    # 3) ImageNet on-egitimli omurga: HoVer-NeXt modeli kurarken timm pretrained=True ile ceker.
    if not args.skip_backbone:
        try:
            import timm
            import toml
            enc = toml.load(os.path.join(repo_dir(), model, "params.toml")).get("encoder")
            if enc:
                log("ImageNet omurgasi onbellege aliniyor (timm / HuggingFace): %s" % enc)
                timm.create_model(enc, pretrained=True, features_only=True)
                log("Omurga onbellekte.")
        except Exception as exc:  # noqa: BLE001
            log("UYARI: omurga onbellege alinamadi (%s: %s) - ilk calistirmada internet gerekir."
                % (type(exc).__name__, exc))
    log("DONE download ok=1 path=%s" % os.path.join(repo_dir(), model))
    return 0


def cmd_detect(args):
    model = args.model
    if model not in WEIGHTS_MD5:
        log("HATA: desteklenmeyen model: %s (secenekler: %s)" % (model, ", ".join(WEIGHTS_MD5)))
        return 2
    if not os.path.isfile(args.roi):
        log("HATA: ROI goruntusu bulunamadi: %s" % args.roi)
        return 3
    if os.path.splitext(args.roi)[1].lower() not in (".png", ".jpg", ".jpeg", ".bmp"):
        log("HATA: ROI PNG/JPEG/BMP olmali (HoVer-NeXt goruntu girdisi): %s" % args.roi)
        return 3
    if not repo_ready() or not weights_ready(model):
        log("HATA: HoVer-NeXt kodu/agirligi yok - once `download --model %s` calistirin "
            "(sihirbazda: Modeli yerel indir)." % model)
        return 2
    try:
        classes = parse_classes(args.classes)
    except ValueError as exc:
        log("HATA: %s" % exc)
        return 2

    # Olcek kapisi: model 0.5 um/px icin egitildi.
    if not args.native:
        if args.mpp is None or args.mpp <= 0:
            log("HATA: --mpp (ROI'nin etkin um/px degeri) verilmedi. Kalibrasyonsuz goruntude HoVer-NeXt "
                "sessizce yanlis olcekte calisir - reddedildi. Slayti kalibre edin.")
            return 5
        lo, hi = MODEL_MPP * (1 - MPP_TOL), MODEL_MPP * (1 + MPP_TOL)
        if not (lo <= args.mpp <= hi):
            log("HATA: ROI cozunurlugu %.3f um/px; model %.2f um/px icin egitildi (izin: %.2f-%.2f). "
                "Reddedildi." % (args.mpp, MODEL_MPP, lo, hi))
            return 5

    if args.device != "cuda":
        log("HATA: HoVer-NeXt yalnizca NVIDIA CUDA GPU ile calisir (cihaz=%s)." % args.device)
        return 4
    try:
        import torch
        if not torch.cuda.is_available():
            log("HATA: CUDA kullanilamiyor - HoVer-NeXt CPU'da calismaz (NVIDIA GPU + CUDA'li torch gerekir).")
            return 4
    except Exception as exc:  # noqa: BLE001
        log("HATA: torch import edilemedi: %s" % exc)
        return 1

    work = args.work or os.path.dirname(os.path.abspath(args.out or args.roi))
    run_root = os.path.join(work, "hovernext_run_%d_%d" % (os.getpid(), int(time.time())))
    os.makedirs(run_root, exist_ok=True)
    list_file = os.path.join(run_root, "input.txt")
    with open(list_file, "w", encoding="utf-8") as f:
        f.write(os.path.abspath(args.roi) + "\n")   # .txt girdi: glob ozel karakterlerinden kacinir
    out_root = os.path.join(run_root, "out")
    cmd = [sys.executable, "-u", os.path.join(repo_dir(), "main.py"),
           "--input", list_file, "--output_root", out_root, "--cp", model,
           "--tta", str(args.tta), "--batch_size", str(args.batch_size),
           "--inf_workers", "0", "--pp_workers", "1"]
    log("Model : HoVer-NeXt %s (kod %s, ayri surec)   tta=%d  batch=%d  mpp=%s"
        % (model, REPO_SHA[:12], args.tta, args.batch_size,
           ("%.3f" % args.mpp) if args.mpp else "native"))
    try:
        rc, tail = run_child(cmd, repo_dir())
        if rc != 0:
            explain_failure(tail)
            return 1

        out_dir = os.path.join(out_root, os.path.splitext(os.path.basename(args.roi))[0])
        ci_path = os.path.join(out_dir, "class_inst.json")
        if not os.path.isfile(ci_path):
            log("HATA: HoVer-NeXt ciktisi bulunamadi: %s (bolgede doku bulunamamis olabilir)" % ci_path)
            return 1
        with open(ci_path, "r", encoding="utf-8") as f:
            class_inst = json.load(f)

        ox, oy = 0.0, 0.0
        try:
            ox, oy = [float(v) for v in (args.origin or "0,0").split(",")]
        except Exception:  # noqa: BLE001
            log("UYARI: --origin ayristirilamadi (%s); 0,0 kullanildi." % args.origin)
        ds = float(args.downsample)

        labels, objs = None, None
        if args.mode == "polygons":
            labels = load_labels(os.path.join(out_dir, "pinst_pp.zip"))
            if labels is None:
                log("UYARI: ornek-etiket haritasi okunamadi - merkez noktalarina dusuluyor.")
            else:
                from scipy.ndimage import find_objects
                objs = find_objects(labels)

        feats, counts = [], {}
        for key, val in class_inst.items():
            try:
                nid = int(key)
                cls = int(val[0])
                crd = val[1]          # goruntu girdisi: (karo, satir, sutun); WSI: (satir, sutun)
                row, col = float(crd[-2]), float(crd[-1])
            except Exception:  # noqa: BLE001
                continue
            if classes is not None and cls not in classes:
                continue
            name = LIZARD_CLASSES.get(cls, "class-%d" % cls)
            cx = round(ox + (col + 0.5) * ds, 2)   # piksel merkezi -> taban piksel
            cy = round(oy + (row + 0.5) * ds, 2)
            geom = None
            if objs is not None:
                ring = contour_ring(labels, objs, nid, ox, oy, ds)
                if ring is not None:
                    geom = {"type": "Polygon", "coordinates": [ring]}
            if geom is None:
                geom = {"type": "Point", "coordinates": [cx, cy]}
            feats.append({
                "type": "Feature",
                "geometry": geom,
                "properties": {"objectType": "detection",
                               "classification": {"name": name},
                               "hovernext_class": cls, "id": nid, "centroid": [cx, cy]},
            })
            counts[name] = counts.get(name, 0) + 1

        out = args.out or (os.path.splitext(args.roi)[0] + "_hovernext.geojson")
        if os.path.dirname(out):
            os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            json.dump({"type": "FeatureCollection", "features": feats}, f)
        for cls_id in sorted(LIZARD_CLASSES):
            name = LIZARD_CLASSES[cls_id]
            if classes is None or cls_id in classes:
                log("COUNT %s=%d" % (name, counts.get(name, 0)))
        log("GeoJSON: %s" % out)
        log("RESULT geojson=%s" % out)
        log("DONE detect n=%d" % len(feats))
        return 0
    finally:
        if not args.keep_raw:
            shutil.rmtree(run_root, ignore_errors=True)


def cmd_selftest(args):
    log("== hovernext_runner selftest ==")
    ok = True
    for mod, hint in REQUIRED_MODULES:
        try:
            m = __import__(mod)
            log("OK    %-28s %s" % (mod, getattr(m, "__version__", "?")))
        except Exception as exc:  # noqa: BLE001
            ok = False
            log("EKSIK %-28s %s  (kurulum: %s)" % (mod, exc, hint))
    try:
        import numpy
        if int(numpy.__version__.split(".")[0]) >= 2:
            ok = False
            log("UYUMSUZ numpy %s - zarr 2.16 / numcodecs 0.12 numpy<2 ister." % numpy.__version__)
    except Exception:  # noqa: BLE001
        pass
    try:
        import torch
        if torch.cuda.is_available():
            log("OK    %-28s %s" % ("CUDA", torch.cuda.get_device_name(0)))
        else:
            ok = False
            log("EKSIK CUDA - HoVer-NeXt yalnizca NVIDIA GPU ile calisir (CPU torch mu kuruldu?).")
    except Exception:  # noqa: BLE001
        pass
    log("Kod     : %s (%s)" % (repo_dir(), "VAR" if repo_ready() else "yok - `download` calistirin"))
    for m in WEIGHTS_MD5:
        log("Agirlik : %-26s %s" % (m, "VAR" if weights_ready(m) else "yok"))
    log("SELFTEST OK" if ok else "SELFTEST BASARISIZ - eksikleri giderin (env yoneticisi: hovernext)")
    return 0 if ok else 1


def build_parser():
    p = argparse.ArgumentParser(description="HoVer-NeXt cekirdek segmentasyon + siniflama koprusu (ayri surec).")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("selftest", help="Bagimliliklari + CUDA + kod/agirlik varligini dogrula.")
    s.set_defaults(func=cmd_selftest)

    dl = sub.add_parser("download", help="HoVer-NeXt kodu (sabit commit) + Lizard agirligi (Zenodo) indir.")
    dl.add_argument("--model", default=DEFAULT_MODEL, choices=sorted(WEIGHTS_MD5))
    dl.add_argument("--overwrite", action="store_true")
    dl.add_argument("--skip-backbone", action="store_true", help="ImageNet omurgasini onbellege alma.")
    dl.set_defaults(func=cmd_download)

    d = sub.add_parser("detect", help="ROI goruntusunde HoVer-NeXt (GeoJSON cekirdekler).")
    d.add_argument("--roi", required=True, help="Disa aktarilmis ROI (PNG/JPEG/BMP, RGB).")
    d.add_argument("--out", default="", help="Cikti GeoJSON yolu.")
    d.add_argument("--origin", default="0,0", help="ROI'nin sol-ust TABAN-piksel kokeni 'x,y'.")
    d.add_argument("--downsample", type=float, default=1.0, help="ROI piksel -> taban piksel olcegi.")
    d.add_argument("--mpp", type=float, default=None, help="ROI'nin etkin um/px degeri (olcek kapisi).")
    d.add_argument("--native", action="store_true",
                   help="Test: origin=0,0 downsample=1 ve olcek kapisini ATLA (ROI'nin ~0.5 um/px oldugunu siz garanti edin).")
    d.add_argument("--model", default=DEFAULT_MODEL, choices=sorted(WEIGHTS_MD5))
    d.add_argument("--mode", default="points", choices=["points", "polygons"])
    d.add_argument("--classes", default="all", help="'all' ya da virgullu sinif adlari/kimlikleri (or. mitosis).")
    d.add_argument("--batch-size", type=int, default=16)
    d.add_argument("--tta", type=int, default=4, help="Test-zamani artirma gorunumu (HoVer-NeXt varsayilani 4).")
    d.add_argument("--device", default="cuda")
    d.add_argument("--work", default="", help="Gecici HoVer-NeXt cikti koku (bos = --out dizini).")
    d.add_argument("--keep-raw", action="store_true", help="HoVer-NeXt ham cikti dizinini silme.")
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
