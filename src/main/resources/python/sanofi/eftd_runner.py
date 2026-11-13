#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
eftd_runner.py - QuPath atolyesi icin Sanofi EFTD atipik-mitoz siniflandirici koprusu
=====================================================================================

NE YAPAR:
    Sanofi/Mines-Paris'in **MIDOG 2025 Gorev 2 BIRINCISI** (EFTD) siniflandiricisini —
    LoRA ile ince-ayarli **DINOv3-H+** Vision Transformer — kullanarak, ONCEDEN TESPIT
    EDILMIS mitoz noktalarini **tipik (normal) vs atipik** olarak siniflar. DEDEKTOR
    DEGILDIR; tespit-SONRASI bir adimdir. Inference mantigi deponun inference.ipynb'sinden
    birebir portlanmistir (DinoViTClassifier: backbone -> pooler_output -> Linear kafa).

⚠️ KAPILI OMURGA (onemli):
    Omurga **facebook/dinov3-vith16plus-pretrain-lvd1689m** HuggingFace'te KAPILIDIR: Meta
    lisansini kabul etmeniz + `huggingface-cli login` ile giris yapmaniz gerekir. Onay
    beklenebilir. Bu adim OTOMATIKLESTIRILEMEZ. Erisim yoksa bu kopru calismaz — bu bilinen
    bir gereksinimdir. LoRA adaptorleri + kafa (model.safetensors + config.yaml) v1.0.0
    yayinindan calisma aninda indirilir (paketlenmez). Depo lisansi ticari-olmayan arastirma.

NE URETIR (ve ne URETMEZ):
    Yalnizca tipik/atipik ETIKET + atipik olasilik. Klinik derece/esik/yorum URETMEZ.

KULLANIM:
    python eftd_runner.py selftest
    python eftd_runner.py download [--overwrite]
    python eftd_runner.py classify --roi ROI.png --points points.geojson \\
        --out cikti.geojson --origin 12000,8400 --downsample 1.0 --patch-px 128

REFERANS:
    https://github.com/Sanofi-Public/EFTD-midog-amf  (arXiv:2508.21041)
"""

import argparse
import json
import os
import sys

BACKBONE_ID = "facebook/dinov3-vith16plus-pretrain-lvd1689m"   # KAPILI (Meta lisansi)
REL_BASE = "https://github.com/Sanofi-Public/EFTD-midog-amf/releases/download/v1.0.0/"
REL_FILES = ["config.yaml", "model.safetensors"]
CLASS_ATYPICAL = "Mitoz (atipik)"
CLASS_TYPICAL = "Mitoz (tipik)"
THRESHOLD = 0.5           # P(normal) > 0.5 -> tipik (referans inference.ipynb)
DEFAULT_PATCH_PX = 128


def log(msg):
    print(msg, flush=True)


def cache_dir():
    th = os.environ.get("TIATOOLBOX_HOME")
    base = os.path.dirname(os.path.abspath(th)) if th else os.path.join(os.path.expanduser("~"), ".atolye", "cache")
    d = os.path.join(base, "sanofi-eftd")
    os.makedirs(d, exist_ok=True)
    return d


def asset_path(name):
    return os.path.join(cache_dir(), name)


def read_roi_rgb(path):
    import numpy as np
    from PIL import Image
    Image.MAX_IMAGE_PIXELS = None
    return np.asarray(Image.open(path).convert("RGB"))


def read_points(geojson_path):
    with open(geojson_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    pts = []
    for ft in (data.get("features") or []):
        g = ft.get("geometry") or {}
        if g.get("type") == "Point":
            c = g.get("coordinates") or []
            if len(c) >= 2:
                pts.append([float(c[0]), float(c[1])])
    return pts


def build_classifier(device):
    """DinoViTClassifier (inference.ipynb ile birebir): DINOv3-H+ + LoRA + Linear kafa."""
    import torch
    import torch.nn as nn
    from transformers import AutoModel
    from safetensors.torch import load_file
    from peft import LoraConfig, get_peft_model, TaskType
    from omegaconf import OmegaConf

    class DinoViTClassifier(nn.Module):
        def __init__(self, backbone, feat_dim, num_classes=1, dropout=0.0):
            super().__init__()
            self.backbone = backbone
            self.dropout = nn.Dropout(dropout)
            self.head = nn.Linear(feat_dim, num_classes)

        def forward(self, x):
            outputs = self.backbone(pixel_values=x)
            pooled = outputs.pooler_output
            return self.head(self.dropout(pooled))

    cfg = OmegaConf.load(asset_path("config.yaml")).model
    # KAPILI omurga — erisim yoksa buradan hata alinir (Meta lisansi + hf login gerekir).
    backbone = AutoModel.from_pretrained(BACKBONE_ID, device_map="cpu")
    peft_cfg = LoraConfig(
        task_type=TaskType.FEATURE_EXTRACTION,
        r=cfg.lora.get("rank", 4), lora_alpha=cfg.lora.get("alpha", 8),
        lora_dropout=cfg.lora.get("dropout", 0.05), bias=cfg.lora.get("bias", "none"),
        target_modules=cfg.lora.get("lora_targets", ["q_proj", "v_proj"]),
    )
    backbone = get_peft_model(backbone, peft_cfg)
    feat_dim = backbone.config.hidden_size
    model = DinoViTClassifier(backbone, feat_dim)
    state = load_file(asset_path("model.safetensors"))
    model.load_state_dict(state, strict=False)
    model.backbone = model.backbone.merge_and_unload()
    use_half = (device == "cuda")
    model = model.to(device)
    if use_half:
        model = model.half()
    return model.eval(), use_half


def cmd_classify(args):
    import numpy as np  # noqa: F401
    import torch
    from PIL import Image
    from torchvision import transforms

    if not os.path.isfile(args.roi):
        log("HATA: ROI goruntusu bulunamadi: %s" % args.roi); return 3
    if not os.path.isfile(args.points):
        log("HATA: nokta GeoJSON bulunamadi: %s" % args.points); return 3
    for name in REL_FILES:
        if not os.path.isfile(asset_path(name)):
            log("HATA: %s yok — once `download` calistirin." % name); return 2

    device = args.device
    if device == "cuda" and not torch.cuda.is_available():
        log("UYARI: CUDA yok, CPU'ya dusuluyor (DINOv3-H+ CPU'da cok yavas)."); device = "cpu"
    try:
        model, use_half = build_classifier(device)
    except Exception as exc:  # noqa: BLE001
        log("HATA: model kurulamadi: %s: %s" % (type(exc).__name__, exc))
        log("      KAPILI omurga? %s icin Meta lisansini kabul edip `huggingface-cli login`" % BACKBONE_ID)
        log("      ile giris yaptigimizdan emin olun. Erisim yoksa bu kopru calismaz.")
        return 1

    tfm = transforms.Compose([
        transforms.Resize((128, 128)),
        transforms.ToTensor(),
        transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
    ])

    img = read_roi_rgb(args.roi)
    H, W = img.shape[0], img.shape[1]
    pts = read_points(args.points)
    ox, oy = 0.0, 0.0
    if args.origin:
        try:
            ox, oy = [float(v) for v in args.origin.split(",")]
        except Exception:  # noqa: BLE001
            log("UYARI: --origin ayristirilamadi (%s); 0,0." % args.origin)
    ds = float(args.downsample)
    half = int(args.patch_px) // 2

    log("Model : Sanofi EFTD (DINOv3-H+ + LoRA)   nokta: %d   patch: %d px" % (len(pts), args.patch_px))
    feats, n, n_atyp = [], 0, 0
    with torch.inference_mode():
        # id = girdi nokta indeksi (QuPath tarafi bununla eslesir; atlananlarin id'si olmaz).
        for i, (bx, by) in enumerate(pts):
            rx = int(round((bx - ox) / ds)); ry = int(round((by - oy) / ds))
            x0, y0 = max(0, rx - half), max(0, ry - half)
            x1, y1 = min(W, rx + half), min(H, ry + half)
            if x1 <= x0 or y1 <= y0:
                continue
            patch = img[y0:y1, x0:x1, :3]
            x = tfm(Image.fromarray(patch)).unsqueeze(0).to(device)
            if use_half:
                x = x.half()
            p_normal = torch.sigmoid(model(x)).item()
            p_atyp = 1.0 - p_normal
            is_atyp = p_normal < THRESHOLD
            if is_atyp:
                n_atyp += 1
            feats.append({
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [bx, by]},
                "properties": {"objectType": "annotation", "id": i,
                               "classification": {"name": CLASS_ATYPICAL if is_atyp else CLASS_TYPICAL},
                               "probability": float(p_atyp)},
            })
            n += 1

    out = args.out or (os.path.splitext(args.points)[0] + "_eftd.geojson")
    if os.path.dirname(out):
        os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": feats}, f)
    log("GeoJSON: %s" % out)
    log("RESULT geojson=%s" % out)
    log("DONE classify n=%d atypical=%d" % (n, n_atyp))
    return 0


def cmd_download(args):
    """LoRA adaptor + kafa (config.yaml + model.safetensors) indir. Omurga KAPILI, ayri."""
    import urllib.request
    ok = True
    for name in REL_FILES:
        dest = asset_path(name)
        if os.path.isfile(dest) and not args.overwrite:
            log("Zaten var: %s" % dest); continue
        url = REL_BASE + name
        log("Indiriliyor: %s" % url)
        try:
            tmp = dest + ".part"
            with urllib.request.urlopen(url) as resp, open(tmp, "wb") as out:
                while True:
                    chunk = resp.read(1 << 20)
                    if not chunk:
                        break
                    out.write(chunk)
            os.replace(tmp, dest)
            log("  -> %s (%.1f MB)" % (dest, os.path.getsize(dest) / 1e6))
        except Exception as exc:  # noqa: BLE001
            log("HATA: %s indirilemedi: %s: %s" % (name, type(exc).__name__, exc)); ok = False
    log("NOT: DINOv3-H+ OMURGASI KAPILI — %s icin Meta lisansi + hf login gerekir (ayri)." % BACKBONE_ID)
    if ok:
        log("DONE download ok=1 path=%s" % cache_dir())
        return 0
    return 1


def cmd_selftest(args):
    log("== eftd_runner selftest ==")
    ok = True
    for mod in ("numpy", "PIL", "torch", "torchvision", "transformers", "peft", "safetensors", "omegaconf"):
        try:
            m = __import__(mod)
            log("OK  %-14s %s" % (mod, getattr(m, "__version__", "?")))
        except Exception as exc:  # noqa: BLE001
            ok = False
            log("EKSIK %-12s %s" % (mod, exc))
    try:
        import torch
        log("OK  cihaz          cuda: %s" % ("var" if torch.cuda.is_available() else "yok (cpu — DINOv3-H+ cok yavas)"))
    except Exception:  # noqa: BLE001
        pass
    for name in REL_FILES:
        log("Adaptor %s: %s" % (name, "VAR" if os.path.isfile(asset_path(name)) else "yok - `download`"))
    log("⚠ OMURGA KAPILI: %s (Meta lisansi + hf login gerekir; OTOMATIK DEGIL)." % BACKBONE_ID)
    log("SELFTEST OK (paket)" if ok else "SELFTEST BASARISIZ - eksik paket (env: sanofi-eftd)")
    return 0 if ok else 1


def build_parser():
    p = argparse.ArgumentParser(description="Sanofi EFTD atipik-mitoz siniflandirici koprusu (DINOv3-H+ + LoRA).")
    sub = p.add_subparsers(dest="command", required=True)
    s = sub.add_parser("selftest"); s.set_defaults(func=cmd_selftest)
    dl = sub.add_parser("download"); dl.add_argument("--overwrite", action="store_true"); dl.set_defaults(func=cmd_download)
    c = sub.add_parser("classify", help="ROI + nokta -> tipik/atipik (GeoJSON).")
    c.add_argument("--roi", required=True)
    c.add_argument("--points", required=True)
    c.add_argument("--out", default="")
    c.add_argument("--origin", default="0,0")
    c.add_argument("--downsample", type=float, default=1.0)
    c.add_argument("--patch-px", type=int, default=DEFAULT_PATCH_PX)
    c.add_argument("--device", default="cuda")
    c.set_defaults(func=cmd_classify)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except FileNotFoundError as exc:
        log("HATA (dosya yok): %s" % exc); return 3
    except Exception as exc:  # noqa: BLE001
        log("HATA: %s: %s" % (type(exc).__name__, exc)); return 1


if __name__ == "__main__":
    sys.exit(main())
