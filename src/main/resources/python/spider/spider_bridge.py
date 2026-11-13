#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
spider_bridge.py — QuPath atolyesi icin SPIDER doku siniflandirici koprusu
==========================================================================

NE YAPAR:
    HistAI'nin **SPIDER** organ-ozellesmis, ON-EGITIMLI patoloji siniflandirici
    modellerini (histai/SPIDER-{colorectal,skin,thorax,breast}-model; Hibou-L
    omurgasi) kullanarak QuPath'ten disa aktarilmis karo (tile) klasorlerini
    siniflandirir. QuPath sihirbazi (yardimci-spider-sihirbaz.groovy) bu betigi
    ProcessBuilder ile cagirir; veri alisverisi diskteki PNG klasorleri + JSON
    dosyalari uzerinden yapilir.

    Kaiko koprusunden FARKI: SPIDER modelleri ZATEN egitilmistir (organ basina
    sabit sinif kumesi) — bu yuzden EGITIM ADIMI YOKTUR. Yalniz 'predict':
    organ modeli secilir, model her 1120x1120 karoyu dogrudan bir sinifa atar.

ONEMLI:
    * Bu betik WSI dosyasi ACMAZ — yalnizca onceden disa aktarilmis PNG karolari
      okur. Karo uretimini QuPath tarafi (sihirbaz) yapar; dolayisiyla openslide
      gibi yerel kutuphanelere ihtiyac yoktur.
    * Siniflar MODELIN sabit organ sinifloridir (ornegin kolorektal icin
      "Adenocarcinoma", "Stroma", ...) — bu betik bunlarin uzerine hicbir klinik
      esik/alt-tip/grade mantigi EKLEMEZ; cikti yalniz sinif etiketi + guven +
      olasiliktir.
    * Lisans: SPIDER modelleri **CC BY-NC 4.0** ve **KAPILI** (gated) —
      HuggingFace hesabi + erisim onayi + token gerekir. Onceden indirip
      --model-dir ile yerel klasoru gosterebilirsiniz (cevrimdisi).
    * Yalnizca arastirma/egitim amacli olcum/tahmin uretir; klinik karar uretmez.

KULLANIM:
    python spider_bridge.py selftest [--organ colorectal] [--model-dir DIR]
    python spider_bridge.py predict  --annotations predict.json --tiles-dir DIR
                                     --output-dir DIR --organ colorectal
                                     [--mode annotation|tile-grid]
                                     [--model-dir DIR] [--max-tiles 0]
                                     [--device auto|cpu|cuda]

JSON SOZLESMESI:
    predict.json : [{"id","image_name","tile_folder","roi",
                     "parent_id"(ops.),"mode"(ops.)}]
    cikti        : output-dir/predictions.json =
                   [{"id","prediction","confidence","probabilities":{...},
                     "parent_id","roi","mode"}]
                   output-dir/classes.json = modelin sinif adlari (id2label)

REFERANS:
    Nechaev D, Pchelnikov A, Ivanova E (2025) "SPIDER: A Comprehensive
    Multi-Organ Supervised Pathology Dataset and Baseline Models."
    arXiv:2503.02876.  Omurga: Hibou (arXiv:2406.05074).
    Modeller: https://huggingface.co/histai  (CC BY-NC 4.0, gated)
"""

import argparse
import glob
import json
import os
import sys

# Organ -> HuggingFace deposu. --model-dir verilirse onun yerine yerel klasor okunur.
MODEL_REPOS = {
    "colorectal": "histai/SPIDER-colorectal-model",
    "skin": "histai/SPIDER-skin-model",
    "thorax": "histai/SPIDER-thorax-model",
    "breast": "histai/SPIDER-breast-model",
}
ORGANS = sorted(MODEL_REPOS.keys())
PATCH_SIZE = 1120          # SPIDER giris baglam penceresi (224 merkez + cevre baglam)
MIN_TRANSFORMERS = (4, 40)  # trust_remote_code + predicted_class_names icin


def log(msg):
    """Satir-tamponlu yaz, boylece QuPath log alani canli akar."""
    print(msg, flush=True)


# --------------------------------------------------------------------------- #
# Model
# --------------------------------------------------------------------------- #
def resolve_device(name):
    import torch
    if name == "cpu":
        return torch.device("cpu")
    if name == "cuda":
        if not torch.cuda.is_available():
            log("UYARI: --device cuda istendi ama CUDA yok; CPU'ya dusuluyor.")
            return torch.device("cpu")
        return torch.device("cuda")
    return torch.device("cuda" if torch.cuda.is_available() else "cpu")


def model_source(organ, model_dir):
    """
    --model-dir gecerli bir model klasoruyse (config.json icerir) yereli, aksi
    halde HuggingFace deposunu dondur. (local, kaynak) ikilisi dondurur.
    """
    if model_dir and os.path.isdir(model_dir) and os.path.isfile(os.path.join(model_dir, "config.json")):
        return True, model_dir
    return False, MODEL_REPOS[organ]


def load_spider_model(organ, model_dir, device, revision=""):
    from transformers import AutoModel, AutoProcessor

    local, src = model_source(organ, model_dir)
    kwargs = {"trust_remote_code": True}
    # Yeniden-uretilebilirlik: --revision verilirse HF commit'ini sabitle. SPIDER
    # depolari KAPILI ve organ-basina ayri oldugu icin SHA varsayilan DEGILDIR;
    # sabitlemek isterseniz model kartindan commit SHA'sini siz verirsiniz.
    rev = (revision or "").strip()
    pin = bool(rev) and rev.lower() != "auto"
    if pin and not local:
        kwargs["revision"] = rev
    if local:
        kwargs["local_files_only"] = True
        log("Model yukleniyor (yerel): %s" % src)
    else:
        log("Model yukleniyor (HuggingFace: %s @ %s) — KAPILI model; HF token + erisim onayi gerekir."
            % (src, rev if pin else "(en guncel)"))
    model = AutoModel.from_pretrained(src, **kwargs).to(device).eval()
    processor = AutoProcessor.from_pretrained(src, **kwargs)
    log("Model hazir. Organ: %s  Aygit: %s" % (organ, device))
    return model, processor


def class_names_of(model):
    """id2label'i sira-korumali bir listeye cevir; yoksa None."""
    cfg = getattr(model, "config", None)
    id2label = getattr(cfg, "id2label", None) if cfg is not None else None
    if not id2label:
        return None
    try:
        return [id2label[i] for i in sorted(id2label, key=lambda k: int(k))]
    except Exception:
        # Anahtarlar tam sayiya cevrilemiyorsa ekleme sirasini koru
        return [id2label[k] for k in id2label]


def softmax_probs(logits):
    import torch
    return torch.softmax(logits.float(), dim=-1).cpu().numpy()


def tile_probabilities(pil_image, model, processor, device, names):
    """
    Bir karo -> {sinif: olasilik} sozlugu. Once outputs.logits'ten softmax dener;
    yoksa outputs.predicted_class_names'e duser.
    """
    import numpy as np
    import torch

    inputs = processor(images=pil_image, return_tensors="pt")
    inputs = {k: (v.to(device) if hasattr(v, "to") else v) for k, v in inputs.items()}
    with torch.no_grad():
        outputs = model(**inputs)

    logits = getattr(outputs, "logits", None)
    if logits is not None and names is not None:
        probs = softmax_probs(logits[0])
        if len(probs) == len(names):
            return {names[i]: float(probs[i]) for i in range(len(names))}

    # Geri donus: yalniz tahmin edilen sinif adi (olasilik 1.0)
    pred_names = getattr(outputs, "predicted_class_names", None)
    if pred_names:
        name = pred_names[0] if isinstance(pred_names, (list, tuple)) else str(pred_names)
        return {str(name): 1.0}

    # Son care: logits var ama isim yok
    if logits is not None:
        probs = softmax_probs(logits[0])
        return {("class_%d" % i): float(probs[i]) for i in range(len(probs))}

    raise RuntimeError("Model ciktisinda logits/predicted_class_names bulunamadi.")


def folder_probabilities(tile_folder, model, processor, device, names, max_tiles):
    """Bir klasordeki PNG karolarinin olasiliklarini ortala -> (probs, n)."""
    from PIL import Image

    if not tile_folder or not os.path.isdir(tile_folder):
        return None, 0
    paths = sorted(glob.glob(os.path.join(tile_folder, "*.png")))
    if max_tiles and max_tiles > 0:
        paths = paths[:max_tiles]
    if not paths:
        return None, 0

    acc = {}
    n = 0
    for p in paths:
        try:
            img = Image.open(p).convert("RGB")
            probs = tile_probabilities(img, model, processor, device, names)
        except Exception as exc:
            log("  karo atlandi (%s): %s" % (os.path.basename(p), exc))
            continue
        for k, v in probs.items():
            acc[k] = acc.get(k, 0.0) + v
        n += 1
    if n == 0:
        return None, 0
    return {k: (v / n) for k, v in acc.items()}, n


# --------------------------------------------------------------------------- #
# Komutlar
# --------------------------------------------------------------------------- #
def cmd_selftest(args):
    log("== spider_bridge selftest ==")
    ok = True
    versions = {}
    for mod in ("torch", "transformers", "PIL", "numpy"):
        try:
            m = __import__(mod)
            ver = getattr(m, "__version__", "?")
            versions[mod] = ver
            log("OK  %-13s %s" % (mod, ver))
        except Exception as exc:
            ok = False
            log("EKSIK %-11s %s" % (mod, exc))
    if not ok:
        log("SELFTEST BASARISIZ — eksik bagimliliklar var.")
        return 1

    # transformers surum kontrolu (trust_remote_code + predicted_class_names)
    try:
        parts = str(versions.get("transformers", "0")).split(".")
        major, minor = int(parts[0]), int(parts[1]) if len(parts) > 1 else 0
        if (major, minor) < MIN_TRANSFORMERS:
            log("UYARI: transformers %s < %d.%d — SPIDER icin >= %d.%d onerilir."
                % (versions.get("transformers"), MIN_TRANSFORMERS[0], MIN_TRANSFORMERS[1],
                   MIN_TRANSFORMERS[0], MIN_TRANSFORMERS[1]))
    except Exception:
        pass

    try:
        import torch
        log("Aygit: %s" % ("cuda" if torch.cuda.is_available() else "cpu"))
    except Exception:
        pass

    # HF token (kapili model icin gerekir)
    try:
        from huggingface_hub import whoami
        who = whoami()
        log("OK  HuggingFace oturumu: %s" % who.get("name", "?"))
    except Exception:
        log("UYARI: HF_TOKEN/oturum yok. SPIDER KAPILI bir modeldir; cevrimici "
            "indirme icin 'huggingface-cli login' gerekir ya da --model-dir ile "
            "onceden indirilmis yerel klasoru gosterin.")

    organ = args.organ if args.organ in MODEL_REPOS else "colorectal"
    local, src = model_source(organ, args.model_dir)
    if local:
        log("OK  yerel model klasoru: %s (organ=%s)" % (src, organ))
    else:
        # Agirlik indirmeden yalniz yapilandirmayi dene (kapiliysa basarisiz olabilir)
        try:
            from transformers import AutoConfig
            rev = (getattr(args, "revision", "") or "").strip()
            ckw = {"trust_remote_code": True}
            if rev and rev.lower() != "auto":
                ckw["revision"] = rev
            cfg = AutoConfig.from_pretrained(src, **ckw)
            n = len(getattr(cfg, "id2label", {}) or {})
            log("OK  model yapilandirmasi: %s @ %s (%d sinif)"
                % (src, rev if (rev and rev.lower() != "auto") else "(en guncel)", n))
        except Exception as exc:
            log("UYARI model yapilandirmasi alinamadi (kapili/cevrimdisi olabilir): %s" % exc)
    log("SELFTEST OK")
    return 0


def cmd_predict(args):
    organ = args.organ
    if organ not in MODEL_REPOS:
        log("HATA: gecersiz organ '%s'. Secenekler: %s" % (organ, ", ".join(ORGANS)))
        return 2

    with open(args.annotations, "r", encoding="utf-8") as f:
        annotations = json.load(f)
    log("Tahmin: %d giris yuklendi (organ=%s, mod=%s)." % (len(annotations), organ, args.mode))
    if not annotations:
        log("HATA: tahmin edilecek anotasyon/karo yok.")
        return 2

    device = resolve_device(args.device)
    model, processor = load_spider_model(organ, args.model_dir, device, getattr(args, "revision", ""))
    names = class_names_of(model)
    if names:
        log("Model siniflari (%d): %s" % (len(names), ", ".join(names)))

    results = []
    total = len(annotations)
    for idx, ann in enumerate(annotations):
        ann_id = ann.get("id")
        folder = ann.get("tile_folder")
        probs, n_tiles = folder_probabilities(
            folder, model, processor, device, names, args.max_tiles)
        base = {
            "id": ann_id,
            "parent_id": ann.get("parent_id"),
            "roi": ann.get("roi"),
            "mode": ann.get("mode", args.mode),
        }
        if probs is None:
            log("PROGRESS %d/%d: %s — karo yok, atlandi" % (idx + 1, total, ann_id))
            base.update({"prediction": None, "confidence": None, "probabilities": None})
            results.append(base)
            continue
        best = max(probs.items(), key=lambda kv: kv[1])
        base.update({"prediction": best[0], "confidence": best[1], "probabilities": probs})
        results.append(base)
        log("PROGRESS %d/%d: %s -> %s (%.2f, %d karo)"
            % (idx + 1, total, ann_id, best[0], best[1], n_tiles))

    os.makedirs(args.output_dir, exist_ok=True)
    out = os.path.join(args.output_dir, "predictions.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False)
    if names:
        with open(os.path.join(args.output_dir, "classes.json"), "w", encoding="utf-8") as f:
            json.dump(names, f, ensure_ascii=False)
    log("Tahmin tamam. %d sonuc -> %s" % (len(results), out))
    log("DONE predict n=%d" % len(results))
    return 0


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def build_parser():
    p = argparse.ArgumentParser(
        description="SPIDER organ doku siniflandirici QuPath koprusu (yalniz PNG karo klasorleri okur).")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("selftest", help="Bagimliliklari, HF oturumunu ve model erisimini dogrula.")
    s.add_argument("--organ", choices=ORGANS, default="colorectal")
    s.add_argument("--model-dir", default="")
    s.add_argument("--revision", default="",
                   help="HF model commit'i (yeniden-uretilebilirlik; KAPILI repo, varsayilan: en guncel).")
    s.set_defaults(func=cmd_selftest)

    r = sub.add_parser("predict", help="Anotasyon/karo PNG'lerini SPIDER ile siniflandir.")
    r.add_argument("--annotations", required=True)
    r.add_argument("--tiles-dir", default="")   # belge amacli; klasorler JSON'da
    r.add_argument("--output-dir", required=True)
    r.add_argument("--organ", choices=ORGANS, required=True)
    r.add_argument("--mode", choices=["annotation", "tile-grid"], default="annotation")
    r.add_argument("--model-dir", default="")
    r.add_argument("--max-tiles", type=int, default=0)   # 0 = tum karolar
    r.add_argument("--device", choices=["auto", "cpu", "cuda"], default="auto")
    r.add_argument("--revision", default="",
                   help="HF model commit'i (yeniden-uretilebilirlik; KAPILI repo, varsayilan: en guncel).")
    r.set_defaults(func=cmd_predict)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except FileNotFoundError as exc:
        log("HATA (dosya yok): %s" % exc)
        return 3
    except Exception as exc:  # son care: QuPath log'una anlamli mesaj dussun
        log("HATA: %s: %s" % (type(exc).__name__, exc))
        return 1


if __name__ == "__main__":
    sys.exit(main())
