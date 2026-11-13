#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
kaiko_bridge.py — QuPath atolyesi icin Kaiko Midnight koprusu
=============================================================

NE YAPAR:
    Kaiko AI'nin **Midnight** patoloji foundation modelini (kaiko-ai/midnight,
    DINOv2-giant, MIT lisansli) kullanarak QuPath'ten disa aktarilmis karo (tile)
    klasorlerinden gomme (embedding) cikarir, bu gommeler uzerinde bir
    **Random Forest** siniflandiricisi egitir ve etiketlenmemis anotasyonlari
    tahmin eder. QuPath sihirbazi (yardimci-kaiko-sihirbaz.groovy) bu betigi
    ProcessBuilder ile cagirir; veri alisverisi diskteki PNG klasorleri + JSON
    dosyalari uzerinden yapilir.

ONEMLI:
    * Bu betik WSI dosyasi ACMAZ — yalnizca onceden disa aktarilmis PNG karolari
      okur. Karo uretimini QuPath tarafi (sihirbaz) yapar; dolayisiyla openslide
      gibi yerel kutuphanelere ihtiyac yoktur.
    * Siniflar tamamen kullanicinin QuPath anotasyonlarina atadigi PathClass
      isimleridir; bu betik hicbir hastalik/tani sinifini sabitlemez.
    * Yalnizca arastirma/egitim amacli olcum/tahmin uretir; klinik karar uretmez.

KULLANIM:
    python kaiko_bridge.py selftest [--model-dir DIR]
    python kaiko_bridge.py train   --annotations train.json   --tiles-dir DIR --output-dir DIR
                                   [--model-dir DIR] [--n-estimators 100]
                                   [--min-per-class 2] [--max-tiles-per-annotation 20]
                                   [--device auto|cpu|cuda]
    python kaiko_bridge.py predict --annotations predict.json --tiles-dir DIR --output-dir DIR
                                   [--model-dir DIR] [--max-tiles-per-annotation 0]
                                   [--device auto|cpu|cuda]
    python kaiko_bridge.py serve   [--model-dir DIR] [--device auto|cpu|cuda]
        # Kalici calisan: modeli BIR KEZ belleke yukler, sonra stdin'den satir-satir
        # JSON komutu okur ({"cmd":"train"|"predict"|"ping"|"capabilities"|"quit", ...}),
        # her komuta stdout'a "##KAIKO-RESP## {json}" satiri yazar. Model yuklenince
        # "##KAIKO-READY##" satiri basar. Boylece train->predict dongusunde model
        # tekrar tekrar yuklenmez (SAM/samapi'nin kalici-sunucu dersinin uyarlamasi).
        # stdin EOF veya {"cmd":"quit"} ile temiz cikar.

JSON SOZLESMESI:
    train.json   : [{"id","image_name","classification","tile_folder","roi"}]
    predict.json : [{"id","image_name","tile_folder","roi"}]  (classification yok)
    cikti        : output-dir/classifier.pkl, classes.json, training_summary.json,
                   predictions.json = [{"id","prediction","confidence","probabilities":{...}}]

REFERANS:
    Karasikov M ve ark. (2025) "Training state-of-the-art pathology foundation
    models with orders of magnitude less data." arXiv:2504.05186.
    Model: https://huggingface.co/kaiko-ai/midnight  (MIT)
"""

import argparse
import datetime
import glob
import json
import os
import pickle
import sys
import types

# serve modu protokol isaretleyicileri (wizard bunlari log satirlarindan ayirir)
READY_MARK = "##KAIKO-READY##"
RESP_PREFIX = "##KAIKO-RESP## "

MODEL_ID = "kaiko-ai/midnight"   # HuggingFace; ilk calistirmada indirilir (~4 GB)
# Yeniden-uretilebilirlik: model surumunu (HF commit) sabitle, boylece sonuclar
# model deposu guncellense de tekrar uretilebilir. '--revision auto' (ya da bos)
# ile en guncel surume duser. (WSInfer'in hf_revision sabitlemesinden ilham.)
MODEL_REVISION = "adc6b15679c981cce6f9b018bbad09d16eeeda9f"
TILE_SIZE = 224                  # Midnight giris boyutu
NORM_MEAN = (0.5, 0.5, 0.5)
NORM_STD = (0.5, 0.5, 0.5)


def log(msg):
    """Satir-tamponlu yaz, boylece QuPath log alani canli akar."""
    print(msg, flush=True)


# --------------------------------------------------------------------------- #
# Model + gomme
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
    # auto
    return torch.device("cuda" if torch.cuda.is_available() else "cpu")


def _resolve_revision(revision):
    """Pin'lenmis HF commit'i dondur. None -> varsayilan pin (MODEL_REVISION);
    'auto' ya da bos -> en guncel surum (None)."""
    if revision is None:
        return MODEL_REVISION
    r = str(revision).strip()
    if r == "" or r.lower() == "auto":
        return None
    return r


def build_transform():
    from torchvision.transforms import v2
    return v2.Compose([
        v2.Resize(TILE_SIZE),
        v2.CenterCrop(TILE_SIZE),
        v2.ToTensor(),
        v2.Normalize(mean=NORM_MEAN, std=NORM_STD),
    ])


def load_model(model_dir, device, revision=None):
    """
    model_dir verilmis ve icinde model.safetensors varsa cevrimdisi yukle;
    aksi halde HuggingFace hub'dan indir/onbellekten yukle. `revision` HF
    commit'ini sabitler (yeniden-uretilebilirlik); None -> MODEL_REVISION pin.
    """
    import torch  # noqa: F401  (device tipine erisim icin)
    from transformers import AutoModel

    rev = _resolve_revision(revision)
    rev_label = rev if rev else "(en guncel)"

    safetensors_path = None
    if model_dir:
        cand = os.path.join(model_dir, "model.safetensors")
        if os.path.isfile(cand):
            safetensors_path = cand

    if safetensors_path:
        from transformers import AutoConfig
        from safetensors.torch import load_file
        log("Model yukleniyor (yerel safetensors): %s  (surum: %s)" % (safetensors_path, rev_label))
        config = AutoConfig.from_pretrained(MODEL_ID, revision=rev)
        model = AutoModel.from_config(config)
        state = load_file(safetensors_path)
        model.load_state_dict(state)
    else:
        log("Model yukleniyor (HuggingFace: %s @ %s) — ilk calistirmada indirilebilir..." % (MODEL_ID, rev_label))
        model = AutoModel.from_pretrained(MODEL_ID, revision=rev)

    model = model.to(device).eval()
    log("Model hazir. Surum: %s  Aygit: %s" % (rev_label, device))
    return model


def extract_embedding(pil_image, model, device, transform):
    """
    Siniflandirma gommesi = concat(CLS belirteci, yama belirteclerinin ortalamasi).
    Midnight model kartindaki resmi yontemle birebir ayni.
    """
    import torch
    x = transform(pil_image).unsqueeze(0).to(device)
    with torch.no_grad():
        out = model(x).last_hidden_state
        cls = out[:, 0, :]
        patch = out[:, 1:, :].mean(1)
        emb = torch.cat([cls, patch], dim=-1)
    return emb.cpu().numpy()[0]


def embed_folder(tile_folder, model, device, transform, max_tiles):
    """Bir anotasyonun PNG klasorunu oku → karo gommelerinin ortalamasi (1 vektor)."""
    import numpy as np
    from PIL import Image

    if not tile_folder or not os.path.isdir(tile_folder):
        return None, 0
    paths = sorted(glob.glob(os.path.join(tile_folder, "*.png")))
    if max_tiles and max_tiles > 0:
        paths = paths[:max_tiles]
    if not paths:
        return None, 0

    embs = []
    for p in paths:
        try:
            img = Image.open(p).convert("RGB")
            embs.append(extract_embedding(img, model, device, transform))
        except Exception as exc:  # tek karo bozuksa atla, devam et
            log("  karo atlandi (%s): %s" % (os.path.basename(p), exc))
    if not embs:
        return None, 0
    return np.mean(embs, axis=0), len(embs)


class _ModelContext:
    """Onceden yuklenmis model + aygit + donusum (serve modunda tekrar kullanilir)."""
    __slots__ = ("model", "device", "transform", "model_dir")

    def __init__(self, model, device, transform, model_dir):
        self.model = model
        self.device = device
        self.transform = transform
        self.model_dir = model_dir


def _get_context(args, ctx=None):
    """ctx verilmisse onu dondur (modeli YENIDEN yuklemeden); aksi halde yukle.

    Boylece cmd_train/cmd_predict hem tek-seferlik CLI cagrisinda (ctx=None ->
    eskisiyle birebir ayni davranis) hem de serve modunda (resident model)
    ayni kod yolunu kullanir.
    """
    if ctx is not None:
        return ctx
    device = resolve_device(args.device)
    transform = build_transform()
    model = load_model(args.model_dir, device, getattr(args, "revision", None))
    return _ModelContext(model, device, transform, args.model_dir)


# --------------------------------------------------------------------------- #
# Komutlar
# --------------------------------------------------------------------------- #
def cmd_selftest(args):
    log("== kaiko_bridge selftest ==")
    ok = True
    for mod in ("torch", "torchvision", "transformers", "sklearn", "PIL", "numpy", "safetensors"):
        try:
            m = __import__(mod)
            ver = getattr(m, "__version__", "?")
            log("OK  %-13s %s" % (mod, ver))
        except Exception as exc:
            ok = False
            log("EKSIK %-11s %s" % (mod, exc))
    if not ok:
        log("SELFTEST BASARISIZ — eksik bagimliliklar var.")
        return 1
    try:
        import torch
        dev = "cuda" if torch.cuda.is_available() else "cpu"
        log("Aygit: %s" % dev)
    except Exception:
        pass
    # Model agirligi indirmeden yalniz mimari yapilandirmasini dene
    try:
        from transformers import AutoConfig
        rev = _resolve_revision(getattr(args, "revision", None))
        cfg = AutoConfig.from_pretrained(MODEL_ID, revision=rev)
        hidden = getattr(cfg, "hidden_size", "?")
        log("OK  model yapilandirmasi: %s @ %s (hidden_size=%s)"
            % (MODEL_ID, rev if rev else "(en guncel)", hidden))
    except Exception as exc:
        log("UYARI model yapilandirmasi alinamadi (cevrimdisi olabilirsiniz): %s" % exc)
    log("SELFTEST OK")
    return 0


def cmd_train(args, ctx=None):
    import numpy as np
    from sklearn.ensemble import RandomForestClassifier

    with open(args.annotations, "r", encoding="utf-8") as f:
        annotations = json.load(f)
    log("Egitim: %d anotasyon yuklendi." % len(annotations))

    c = _get_context(args, ctx)
    device, transform, model = c.device, c.transform, c.model

    X, y = [], []
    class_counts = {}
    total_tiles = 0
    for idx, ann in enumerate(annotations):
        cls = ann.get("classification")
        folder = ann.get("tile_folder")
        if not cls:
            continue
        mean_emb, n_tiles = embed_folder(folder, model, device, transform,
                                         args.max_tiles_per_annotation)
        if mean_emb is None:
            log("PROGRESS %d/%d: %s — karo yok, atlandi" % (idx + 1, len(annotations), cls))
            continue
        X.append(mean_emb)
        y.append(cls)
        class_counts[cls] = class_counts.get(cls, 0) + 1
        total_tiles += n_tiles
        log("PROGRESS %d/%d: %s (%d karo)" % (idx + 1, len(annotations), cls, n_tiles))

    if len(X) < 2:
        log("HATA: en az 2 etiketli anotasyon gerekir (bulunan: %d)." % len(X))
        return 2
    if len(set(y)) < 2:
        log("HATA: en az 2 farkli sinif gerekir (bulunan: %s)." % sorted(set(y)))
        return 2
    weak = {c: n for c, n in class_counts.items() if n < args.min_per_class}
    if weak:
        log("UYARI: bazi siniflarda yetersiz ornek (< %d): %s" % (args.min_per_class, weak))

    log("Random Forest egitiliyor (n_estimators=%d, gomme boyutu=%d)..."
        % (args.n_estimators, len(X[0])))
    clf = RandomForestClassifier(n_estimators=args.n_estimators, random_state=42, n_jobs=-1)
    clf.fit(X, y)
    train_acc = float(np.mean(clf.predict(X) == np.array(y)))

    os.makedirs(args.output_dir, exist_ok=True)
    with open(os.path.join(args.output_dir, "classifier.pkl"), "wb") as f:
        pickle.dump(clf, f)
    classes = clf.classes_.tolist()   # predict_proba sutun sirasiyla AYNI
    with open(os.path.join(args.output_dir, "classes.json"), "w", encoding="utf-8") as f:
        json.dump(classes, f, ensure_ascii=False)
    summary = {
        "classes": classes,
        "class_counts": class_counts,
        "n_annotations": len(X),
        "n_tiles": total_tiles,
        "embedding_dim": len(X[0]),
        "train_accuracy": train_acc,
        "n_estimators": args.n_estimators,
        "model_id": MODEL_ID,
        "model_revision": _resolve_revision(getattr(args, "revision", None)),
        "trained_at": datetime.datetime.now().isoformat(timespec="seconds"),
    }
    with open(os.path.join(args.output_dir, "training_summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    log("Egitim tamam. Siniflar=%s  egitim dogrulugu=%.3f" % (classes, train_acc))
    log("DONE train n_annotations=%d n_tiles=%d" % (len(X), total_tiles))
    return 0


def cmd_predict(args, ctx=None):
    import numpy as np

    classifier_path = os.path.join(args.output_dir, "classifier.pkl")
    classes_path = os.path.join(args.output_dir, "classes.json")
    if not os.path.isfile(classifier_path) or not os.path.isfile(classes_path):
        log("HATA: classifier.pkl veya classes.json yok — once 'train' calistirin (%s)."
            % args.output_dir)
        return 2

    with open(args.annotations, "r", encoding="utf-8") as f:
        annotations = json.load(f)
    log("Tahmin: %d anotasyon yuklendi." % len(annotations))
    with open(classes_path, "r", encoding="utf-8") as f:
        classes = json.load(f)
    # GUVENLIK: classifier.pkl bu betigin 'train' komutuyla YERELDE uretilir
    # (ayni output-dir); harici/guvenilmez kaynaktan pickle yuklenmez.
    with open(classifier_path, "rb") as f:
        clf = pickle.load(f)

    c = _get_context(args, ctx)
    device, transform, model = c.device, c.transform, c.model

    results = []
    for idx, ann in enumerate(annotations):
        ann_id = ann.get("id")
        folder = ann.get("tile_folder")
        mean_emb, n_tiles = embed_folder(folder, model, device, transform,
                                         args.max_tiles_per_annotation)
        if mean_emb is None:
            log("PROGRESS %d/%d: %s — karo yok, tahmin atlandi" % (idx + 1, len(annotations), ann_id))
            results.append({"id": ann_id, "prediction": None, "confidence": None,
                            "probabilities": None})
            continue
        proba = clf.predict_proba(mean_emb.reshape(1, -1))[0]
        probs = {str(c): float(p) for c, p in zip(clf.classes_, proba)}
        # En yuksek olasilikli sinif (classes.json sirasindan bagimsiz, guvenli)
        best = max(probs.items(), key=lambda kv: kv[1])
        results.append({
            "id": ann_id,
            "prediction": best[0],
            "confidence": best[1],
            "probabilities": probs,
        })
        log("PROGRESS %d/%d: %s -> %s (%.2f, %d karo)"
            % (idx + 1, len(annotations), ann_id, best[0], best[1], n_tiles))

    os.makedirs(args.output_dir, exist_ok=True)
    out = os.path.join(args.output_dir, "predictions.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False)
    log("Tahmin tamam. %d sonuc -> %s" % (len(results), out))
    log("DONE predict n=%d" % len(results))
    return 0


# --------------------------------------------------------------------------- #
# serve — kalici calisan (model bellekte resident)
# --------------------------------------------------------------------------- #
def _req_to_args(req, serve_args, cmd):
    """stdin JSON istegini cmd_train/cmd_predict'in bekledigi args-benzeri
    nesneye cevirir. Aygit/model serve_args'tan gelir (model zaten yuklu)."""
    a = types.SimpleNamespace()
    a.annotations = req["annotations"]
    a.output_dir = req["output_dir"]
    a.model_dir = serve_args.model_dir
    a.device = serve_args.device
    a.revision = getattr(serve_args, "revision", None)
    a.tiles_dir = ""
    if cmd == "train":
        a.max_tiles_per_annotation = int(req.get("max_tiles_per_annotation", 20))
        a.n_estimators = int(req.get("n_estimators", 100))
        a.min_per_class = int(req.get("min_per_class", 2))
    else:  # predict
        a.max_tiles_per_annotation = int(req.get("max_tiles_per_annotation", 0))
    return a


def _emit(obj):
    """Bir JSON yanit satirini RESP_PREFIX ile yaz (log satirlarindan ayrilir)."""
    print(RESP_PREFIX + json.dumps(obj, ensure_ascii=False), flush=True)


def cmd_serve(args):
    """Modeli bir kez yukle, stdin'den satir-satir JSON komutu isle."""
    log("== kaiko_bridge serve == (kalici calisan; model bir kez yuklenir)")
    try:
        device = resolve_device(args.device)
        transform = build_transform()
        model = load_model(args.model_dir, device, getattr(args, "revision", None))
    except Exception as exc:
        log("HATA: model yuklenemedi: %s: %s" % (type(exc).__name__, exc))
        _emit({"ok": False, "cmd": "init", "error": "%s: %s" % (type(exc).__name__, exc)})
        return 1
    ctx = _ModelContext(model, device, transform, args.model_dir)
    # Wizard bu satiri bekler -> model hazir, predict/train gonderebilir.
    print(READY_MARK, flush=True)

    for raw in sys.stdin:
        line = raw.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception as exc:
            _emit({"ok": False, "cmd": None, "error": "gecersiz JSON: %s" % exc})
            continue
        cmd = req.get("cmd")
        try:
            if cmd in ("quit", "exit"):
                _emit({"ok": True, "cmd": "quit"})
                break
            elif cmd in ("ping", "health"):
                _emit({"ok": True, "cmd": cmd, "device": str(device)})
            elif cmd == "capabilities":
                _emit({"ok": True, "cmd": "capabilities", "model_id": MODEL_ID,
                       "device": str(device),
                       "commands": ["ping", "train", "predict", "capabilities", "quit"]})
            elif cmd in ("train", "predict"):
                sub_args = _req_to_args(req, args, cmd)
                handler = cmd_train if cmd == "train" else cmd_predict
                rc = handler(sub_args, ctx)
                _emit({"ok": rc == 0, "cmd": cmd, "rc": rc,
                       "output_dir": sub_args.output_dir})
            else:
                _emit({"ok": False, "cmd": cmd, "error": "bilinmeyen komut: %s" % cmd})
        except Exception as exc:
            log("HATA (serve %s): %s: %s" % (cmd, type(exc).__name__, exc))
            _emit({"ok": False, "cmd": cmd, "error": "%s: %s" % (type(exc).__name__, exc)})

    log("serve durdu (stdin kapandi).")
    return 0


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def build_parser():
    p = argparse.ArgumentParser(description="Kaiko Midnight QuPath koprusu (yalniz PNG karo klasorleri okur).")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("selftest", help="Bagimliliklari ve model yapilandirmasini dogrula.")
    s.add_argument("--model-dir", default="")
    s.add_argument("--revision", default=MODEL_REVISION,
                   help="HF model commit'i (yeniden-uretilebilirlik). 'auto' = en guncel.")
    s.set_defaults(func=cmd_selftest)

    t = sub.add_parser("train", help="Etiketli anotasyon karolarindan RF egit.")
    t.add_argument("--annotations", required=True)
    t.add_argument("--tiles-dir", default="")   # belge amacli; klasorler JSON'da
    t.add_argument("--output-dir", required=True)
    t.add_argument("--model-dir", default="")
    t.add_argument("--n-estimators", type=int, default=100)
    t.add_argument("--min-per-class", type=int, default=2)
    t.add_argument("--max-tiles-per-annotation", type=int, default=20)
    t.add_argument("--device", choices=["auto", "cpu", "cuda"], default="auto")
    t.add_argument("--revision", default=MODEL_REVISION,
                   help="HF model commit'i (yeniden-uretilebilirlik). 'auto' = en guncel.")
    t.set_defaults(func=cmd_train)

    r = sub.add_parser("predict", help="Etiketlenmemis anotasyon karolarini tahmin et.")
    r.add_argument("--annotations", required=True)
    r.add_argument("--tiles-dir", default="")
    r.add_argument("--output-dir", required=True)
    r.add_argument("--model-dir", default="")
    r.add_argument("--max-tiles-per-annotation", type=int, default=0)  # 0 = tum karolar
    r.add_argument("--device", choices=["auto", "cpu", "cuda"], default="auto")
    r.add_argument("--revision", default=MODEL_REVISION,
                   help="HF model commit'i (yeniden-uretilebilirlik). 'auto' = en guncel.")
    r.set_defaults(func=cmd_predict)

    v = sub.add_parser("serve", help="Kalici calisan: modeli bellekte tut, stdin'den JSON komutu isle.")
    v.add_argument("--model-dir", default="")
    v.add_argument("--device", choices=["auto", "cpu", "cuda"], default="auto")
    v.add_argument("--revision", default=MODEL_REVISION,
                   help="HF model commit'i (yeniden-uretilebilirlik). 'auto' = en guncel.")
    v.set_defaults(func=cmd_serve)
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
