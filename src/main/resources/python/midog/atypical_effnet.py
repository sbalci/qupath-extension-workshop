#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
atypical_effnet.py - QuPath atolyesi icin MIDOG25 Track-2 atipik-mitoz siniflandirici koprusu
=============================================================================================

NE YAPAR:
    DeepMicroscopy'nin **MIDOG 2025 Gorev 2 resmi referans** siniflandiricisini
    (EfficientNetV2-M, timm) kullanarak, ONCEDEN TESPIT EDILMIS mitoz noktalarini
    **tipik (normal) vs atipik** olarak siniflar. Bu bir DEDEKTOR DEGILDIR - mitoz
    BULMAZ; bir dedektor (KongNet/FCOS/RetinaNet) tarafindan bulunmus noktalari
    derecelendiren tespit-SONRASI bir adimdir.

GIRDI SOZLESMESI (yama manifesti):
    QuPath tarafi her mitoz noktasi cevresinde KUCUK bir yama kirpar (kenarda beyaz
    dolgu ile, nokta yamanin MERKEZINDE kalacak sekilde) ve bir manifest JSON yazar:

        {"patch_px": 128,
         "items": [{"id": 0, "file": "p00000.png", "x": 12345.5, "y": 6789.0}, ...]}

    `file` manifest dizinine gore goreli (ya da mutlak) yoldur; `x`/`y` noktanin
    TABAN-piksel koordinatidir ve ciktiya oldugu gibi geri yazilir. `id` girdi nokta
    indeksidir: QuPath tarafi sonucu bu anahtarla anotasyona esler.

    Dev bir ROI goruntusu YAZILMAZ/OKUNMAZ. (Onceki surum secili bolgenin tamamini tek
    PNG olarak disa aktariyordu; genis bir bolgede bu yuzlerce MB dosya + GB'larca RAM
    demekti - oysa siniflama icin yalnizca nokta cevresi gerekir.)

NE URETIR (ve ne URETMEZ):
    Yalnizca tipik/atipik ETIKET + olasilik. Klinik derece/esik/yorum URETMEZ.
    Arastirma/egitim amaclidir; gorsel dogrulama sarttir.

MIMARI - REFERANSLA BIREBIR (onemli):
    Checkpoint, referansin `BinaryEfficientNetV2M` SARMALAYICISINDAN kaydedilmistir:
    state_dict anahtarlarinin tamami `net.` onekiyle baslar (net.conv_stem.weight ...
    net.classifier.weight). Bu yuzden model DUZ bir timm modeli olarak kurulamaz; ayni
    sarmalayici gerekir. Aksi halde yukleme su hatayla duser:
        Missing key(s): "conv_stem.weight" ... Unexpected key(s): "net.conv_stem.weight" ...
    Asagidaki BinaryEfficientNetV2M + val_transform + THRESHOLD tanimlari, referansin
    inference.py dosyasindaki tanimlarin birebir karsiligidir:
        depo   : DeepMicroscopy/MIDOG25_T2_reference_docker
        commit : 5c6215cc82d586294f6c7bdea43aa4e8ac8e5889
        dosya  : inference.py
        sha256 : 0b904e5235743adf38dd76b297aa7b518dbac4e9e52ad3cf85cfa6fd30e82601
    Referans kodu bu depoya kopyalanmaz/indirilmez; yalnizca mimari tanimi (dort satirlik
    nn.Module sarmalayici + dogrulama donusumu + esik) yukaridaki kaynakla ayni tutulur.
    Bu dosya degistirilirken ustteki commit ile karsilastirin.

AGIRLIK LISANSI:
    efficientnetv2_m_fold3_best.pth, MIDOG25_T2_reference_docker v1.0.0 yayinindan CALISMA
    ANINDA indirilir; paketlenmez. Kaynak deponun LISANS dosyasi yok -> arastirma/egitim,
    kullanici sorumlulugunda. Atif: MIDOG 2025, DeepMicroscopy.

KULLANIM:
    python atypical_effnet.py selftest
    python atypical_effnet.py download [--overwrite]
    python atypical_effnet.py classify --patches manifest.json --out cikti.geojson \\
        [--device cuda]

REFERANS:
    https://github.com/DeepMicroscopy/MIDOG25_T2_reference_docker
"""

import argparse
import json
import os
import sys

WEIGHTS_URL = ("https://github.com/DeepMicroscopy/MIDOG25_T2_reference_docker/"
               "releases/download/v1.0.0/efficientnetv2_m_fold3_best.pth")
WEIGHTS_NAME = "efficientnetv2_m_fold3_best.pth"
MODEL_NAME = "efficientnetv2_m"
CLASS_ATYPICAL = "Mitoz (atipik)"
CLASS_TYPICAL = "Mitoz (tipik)"
THRESHOLD = 0.5           # P(normal) >= 0.5 -> tipik (referans inference.py)


def log(msg):
    print(msg, flush=True)


def cache_dir():
    th = os.environ.get("TIATOOLBOX_HOME")
    base = os.path.dirname(os.path.abspath(th)) if th else os.path.join(os.path.expanduser("~"), ".atolye", "cache")
    d = os.path.join(base, "midog-atypical")
    os.makedirs(d, exist_ok=True)
    return d


def weights_path():
    return os.path.join(cache_dir(), WEIGHTS_NAME)


def build_model():
    """Referansin BinaryEfficientNetV2M sarmalayicisi (timm modeli `self.net` altinda).

    Checkpoint bu sarmalayicidan kaydedildigi icin anahtarlar `net.` onekli gelir;
    duz timm modeli kullanilirsa strict yukleme basarisiz olur (ustteki MIMARI notu).
    """
    import timm
    import torch.nn as nn

    class BinaryEfficientNetV2M(nn.Module):
        def __init__(self, pretrained=False):
            super().__init__()
            # Egitimdeki cagri ile ayni imza (num_classes varsayilan 1000 kalir)
            self.net = timm.create_model(MODEL_NAME, pretrained=pretrained)
            # Siniflandiriciyi tek-logit katmanla degistir
            n_feats = self.net.classifier.in_features
            self.net.classifier = nn.Linear(n_feats, 1)

        def forward(self, x):
            return self.net(x)

    return BinaryEfficientNetV2M(pretrained=False)


def build_transform():
    """Referansin val_transform'u (dogrulamayla ayni olmak ZORUNDA)."""
    from torchvision import transforms
    return transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
    ])


def load_model(device):
    import torch
    net = build_model()
    # Duz state_dict; guvenlik icin once weights_only=True.
    try:
        state = torch.load(weights_path(), map_location=device, weights_only=True)
    except Exception:  # noqa: BLE001
        log("UYARI: weights_only=True basarisiz; guvenilir yayin varsayimiyla tam yukleme.")
        state = torch.load(weights_path(), map_location=device, weights_only=False)
    if isinstance(state, dict) and "state_dict" in state:
        state = state["state_dict"]
    net.load_state_dict(state, strict=True)
    net.eval().to(device)
    return net


def read_manifest(path):
    """Yama manifestini oku -> (patch_px, [{id, path, x, y}, ...])."""
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    base = os.path.dirname(os.path.abspath(path))
    items = []
    for it in (data.get("items") or []):
        fp = it.get("file") or ""
        if not fp:
            continue
        if not os.path.isabs(fp):
            fp = os.path.join(base, fp)
        items.append({"id": int(it.get("id", len(items))),
                      "path": fp,
                      "x": float(it.get("x", 0.0)),
                      "y": float(it.get("y", 0.0))})
    return int(data.get("patch_px", 0)), items


def cmd_classify(args):
    import torch
    from PIL import Image

    if not os.path.isfile(args.patches):
        log("HATA: yama manifesti bulunamadi: %s" % args.patches)
        return 3
    if not os.path.isfile(weights_path()):
        log("HATA: agirlik yok: %s - once `download` calistirin." % weights_path())
        return 2

    try:
        patch_px, items = read_manifest(args.patches)
    except Exception as exc:  # noqa: BLE001
        log("HATA: manifest okunamadi: %s: %s" % (type(exc).__name__, exc))
        return 3
    if not items:
        log("HATA: manifestte yama yok (siniflanacak nokta yok).")
        return 3

    device = args.device
    if device == "cuda" and not torch.cuda.is_available():
        log("UYARI: CUDA yok, CPU'ya dusuluyor.")
        device = "cpu"
    try:
        model = load_model(device)
    except Exception as exc:  # noqa: BLE001
        log("HATA: model/agirlik yuklenemedi: %s: %s" % (type(exc).__name__, exc))
        return 1
    tfm = build_transform()

    log("Model : EfficientNetV2-M   yama: %d   boyut: %d px   cihaz: %s" % (len(items), patch_px, device))
    feats = []
    n = 0
    n_atyp = 0
    n_miss = 0
    with torch.no_grad():
        # id = girdi nokta indeksi (QuPath tarafi bununla anotasyona esler). Kirpilamayan
        # nokta CIKTIDA YER ALMAZ -> yeniden siniflanmaz (varsayilan etiket ATANMAZ).
        for it in items:
            if not os.path.isfile(it["path"]):
                n_miss += 1
                continue
            with Image.open(it["path"]) as im:
                patch = im.convert("RGB")
                x = tfm(patch).unsqueeze(0).to(device)
            logit = model(x)
            p_normal = torch.sigmoid(logit).item()   # referans: P(normal)
            p_atyp = 1.0 - p_normal
            is_atyp = p_normal < THRESHOLD
            if is_atyp:
                n_atyp += 1
            feats.append({
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [it["x"], it["y"]]},
                "properties": {"objectType": "annotation", "id": it["id"],
                               "classification": {"name": CLASS_ATYPICAL if is_atyp else CLASS_TYPICAL},
                               "probability": float(p_atyp)},
            })
            n += 1

    if n_miss:
        log("UYARI: %d yama dosyasi bulunamadi - o noktalar siniflanmadi." % n_miss)

    out = args.out or (os.path.splitext(args.patches)[0] + "_classified.geojson")
    if os.path.dirname(out):
        os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": feats}, f)
    log("GeoJSON: %s" % out)
    log("RESULT geojson=%s" % out)
    log("DONE classify n=%d atypical=%d" % (n, n_atyp))
    return 0


def cmd_download(args):
    import urllib.request
    dest = weights_path()
    if os.path.isfile(dest) and not args.overwrite:
        log("Agirlik zaten var: %s (%.1f MB)" % (dest, os.path.getsize(dest) / 1e6))
        log("DONE download ok=1 path=%s" % dest)
        return 0
    log("Indiriliyor: %s" % WEIGHTS_URL)
    try:
        tmp = dest + ".part"
        with urllib.request.urlopen(WEIGHTS_URL) as resp, open(tmp, "wb") as out:
            total = int(resp.headers.get("Content-Length", 0))
            done = 0
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk); done += len(chunk)
                if total:
                    log("  %d / %d MB" % (done // (1 << 20), total // (1 << 20)))
        os.replace(tmp, dest)
    except Exception as exc:  # noqa: BLE001
        log("HATA: indirilemedi: %s: %s" % (type(exc).__name__, exc))
        return 1
    log("Indirildi: %s (%.1f MB)" % (dest, os.path.getsize(dest) / 1e6))
    log("DONE download ok=1 path=%s" % dest)
    return 0


def cmd_selftest(args):
    log("== atypical_effnet selftest ==")
    ok = True
    for mod in ("numpy", "PIL", "torch", "torchvision", "timm"):
        try:
            m = __import__(mod)
            log("OK  %-12s %s" % (mod, getattr(m, "__version__", "?")))
        except Exception as exc:  # noqa: BLE001
            ok = False
            log("EKSIK %-10s %s" % (mod, exc))
    try:
        import torch
        log("OK  cihaz        cuda: %s" % ("var" if torch.cuda.is_available() else "yok (cpu)"))
    except Exception:  # noqa: BLE001
        pass
    wp = weights_path()
    log("Agirlik: %s (%s)" % (wp, "VAR" if os.path.isfile(wp) else "yok - `download` calistirin"))
    log("SELFTEST OK" if ok else "SELFTEST BASARISIZ - eksik paket (env: midog-atypical)")
    return 0 if ok else 1


def build_parser():
    p = argparse.ArgumentParser(description="MIDOG25 Track-2 atipik-mitoz siniflandirici koprusu (EfficientNetV2-M).")
    sub = p.add_subparsers(dest="command", required=True)
    s = sub.add_parser("selftest"); s.set_defaults(func=cmd_selftest)
    dl = sub.add_parser("download"); dl.add_argument("--overwrite", action="store_true"); dl.set_defaults(func=cmd_download)
    c = sub.add_parser("classify", help="Yama manifesti -> tipik/atipik siniflama (GeoJSON).")
    c.add_argument("--patches", required=True, help="QuPath'in yazdigi yama manifesti (JSON).")
    c.add_argument("--out", default="")
    c.add_argument("--device", default="cuda")
    c.set_defaults(func=cmd_classify)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
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
