#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
histoart_bridge.py — QuPath atolyesi icin HistoART artefakt tespiti koprusu
===========================================================================

NE YAPAR:
    FDA CDRH/OSEL (DIDSR) **HistoART** artefakt siniflandiricilarini kullanarak
    QuPath'ten disa aktarilmis karo (tile) PNG'lerini siniflandirir. QuPath
    sihirbazi (yardimci-histoart-sihirbaz.groovy) bu betigi ProcessBuilder ile
    cagirir; veri alisverisi diskteki PNG'ler + JSON dosyalari uzerinden yapilir.

    IKI ASAMALI TASARIM (onemli):
      1) IKILI (binary) model her karoyu "artefakt var / yok" olarak etiketler.
      2) COKLU (multiclass) model YALNIZCA 1. asamada artefakt bulunan karolara
         uygulanir ve artefakt TURUNU verir.
    Bunun nedeni: coklu modelin sinif kumesinde "temiz" sinifi YOKTUR (6 sinifin
    hepsi artefakttir). Coklu model tek basina calistirilirsa temiz bir karo da
    mecburen alti artefakt etiketinden birini alir — sessizce ve yuksek gorunen
    bir olasilikla. Bu yuzden 'predict' varsayilan olarak once ikili modeli
    calistirir (--task both).

ONEMLI:
    * Bu betik WSI dosyasi ACMAZ — yalnizca onceden disa aktarilmis PNG karolari
      okur. Karo uretimini QuPath tarafi (sihirbaz) yapar; openslide gerekmez.
    * ON ISLEME upstream ile BIRE BIR ayni olmalidir, yoksa tahminler sessizce
      bozulur (hicbir hata verilmez):
          read_image(RGB).float() / 255.0   -> ONCE [0,1]'e olcekle
          Resize((224, 224))                -> en-boy orani KORUNMAZ (squash)
          Normalize(ImageNet mean/std)      -> CenterCrop YOK, ToTensor YOK
      Kaynak: utils/datasets.py (CombinedClassDataset.__getitem__ + returnDataLoader).
    * COKLU SINIF SIRASI depoda hicbir yerde sabit yazili DEGILDIR — egitimde
      cagirana verilen bir listeden gelir (load_images_and_labels:
      `label_value = class_names.index(cname)`). Asagidaki sira, deponun
      results/ altindaki UC bagimsiz CSV basligindan (dla_multi, fma_multi,
      kba_multi) kurtarilmistir; ucu de ayni: blood, blur, bubble, damage,
      fold, marker. Bu sirayi DEGISTIRMEYIN.
    * Ikili eslesme: 0 = artefakt YOK (artifact_free), 1 = artefakt VAR.
      Kaynak: utils/datasets.py CombinedClassDataset (clean -> 0, artifact -> 1).
    * DESTEKLENEN VARYANTLAR: DLA (ResNet50, varsayilan) ve FMA (ince ayarli UNI).
      KBA (pyfeats ozellikleri + SVM) KOPRULENMEZ — turlanmis (pickle) SVM'in
      ozellik cikarim hatti dogrulanmadi ve farkli scikit-learn surumunde
      kirilgandir.
    * FMA, omurgasini `hf-hub:MahmoodLab/uni` uzerinden kurar; UNI **KAPILIDIR**
      (CC-BY-NC-ND 4.0 + HF erisim onayi). Erisiminiz yoksa FMA model kurulum
      aninda 401/403 ile basarisiz olur. DLA ungated ve CC0'dir — varsayilan odur.
    * Yalnizca arastirma/egitim amacli olcum uretir; klinik karar uretmez.

KULLANIM:
    python histoart_bridge.py selftest [--variant dla|fma] [--model-dir DIR]
    python histoart_bridge.py download --model-dir DIR [--variant dla|fma]
                                       [--task binary|multi|both]
    python histoart_bridge.py predict  --manifest tiles.json --output-dir DIR
                                       --model-dir DIR [--variant dla|fma]
                                       [--task both|binary|multi]
                                       [--threshold 0.5] [--batch-size 16]
                                       [--device auto|cpu|cuda]

JSON SOZLESMESI:
    manifest : [{"id": "...", "path": "/abs/tile.png"}]
    cikti    : output-dir/predictions.json =
               [{"id","artifact","artifact_prob","artifact_type",
                 "type_probabilities":{...}}]
               output-dir/scorecard.json = ozet karne (sayim + oran + dagilim)

REFERANS:
    Kahaki S, Webber AR, Zamzmi G, Subbaswamy A, Deshpande R, Badano A (2025)
    "HistoART: Histopathology Artifact Detection and Reporting Tool."
    arXiv:2507.00044.  Kod: https://github.com/DIDSR/HistoART (CC0-1.0)
    Agirliklar: https://huggingface.co/didsr/HistoArt
"""

import argparse
import json
import os
import sys

# --------------------------------------------------------------------------- #
# Sabitler — upstream'den dogrulanmis; degistirmeyin
# --------------------------------------------------------------------------- #

# Coklu sinif SIRASI: results/{dla,fma,kba}_multi.csv basliklarindan kurtarildi.
# Ucu de ayni sirayi verir. Indeks = softmax cikti indeksi.
ARTIFACT_CLASSES = ["blood", "blur", "bubble", "damage", "fold", "marker"]

# QuPath tarafinda gosterilecek Turkce adlar. GrandQC ekindeki (§7.3) karsiliklarla
# bilerek uyumludur, boylece iki KK araci karsilastirilabilir kalir.
CLASS_TR = {
    "blood":  "Kan",
    "blur":   "Odak disi",
    "bubble": "Hava kabarcigi",
    "damage": "Doku hasari",
    "fold":   "Doku katlanmasi",
    "marker": "Kalem isareti",
}

# Ikili eslesme (utils/datasets.py CombinedClassDataset): clean -> 0, artifact -> 1
BINARY_ARTIFACT_INDEX = 1

IMAGENET_MEAN = [0.485, 0.456, 0.406]
IMAGENET_STD = [0.229, 0.224, 0.225]
INPUT_SIZE = (224, 224)

HF_REPO = "didsr/HistoArt"
WEIGHT_FILES = {
    ("dla", "binary"): "dla_binary.pth",
    ("dla", "multi"): "dla_multi.pth",
    ("fma", "binary"): "fma_binary.pth",
    ("fma", "multi"): "fma_multi.pth",
}
UNI_HUB = "hf-hub:MahmoodLab/uni"


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


def weight_path(model_dir, variant, task):
    return os.path.join(model_dir, WEIGHT_FILES[(variant, task)])


def _load_state(path, device):
    import torch
    # GUVENLIK: agirliklar aginda indirilir. weights_only=True, turlanmis (pickle)
    # rastgele Python nesnesi calistirilmasini engeller — yalniz tensor/temel yapi
    # okunur. torch 2.6+ zaten bunu varsayar; eski surumlerde kwarg yoksa geri duseriz.
    try:
        state = torch.load(path, map_location=device, weights_only=True)
    except TypeError:
        state = torch.load(path, map_location=device)
    # Bazi kontrol noktalari {'state_dict': ...} sarmalayicisiyla gelir.
    if isinstance(state, dict) and "state_dict" in state and isinstance(state["state_dict"], dict):
        state = state["state_dict"]
    # DataParallel ile kaydedilmisse 'module.' onekini temizle.
    if any(k.startswith("module.") for k in state):
        state = {k[len("module."):] if k.startswith("module.") else k: v for k, v in state.items()}
    return state


def head_width(state, variant):
    """
    Cikis katmani genisligini KONTROL NOKTASINDAN oku (tahmin etme).
    Ikili basligin 1 mi 2 mi cikis verdigi depoda belgelenmemistir.
    """
    key = "fc.weight" if variant == "dla" else "head.weight"
    if key not in state:
        cands = [k for k in state if k.endswith(("fc.weight", "head.weight"))]
        if not cands:
            raise RuntimeError(
                "Kontrol noktasinda cikis katmani bulunamadi (%s bekleniyordu)." % key)
        key = cands[-1]
    return int(state[key].shape[0]), key


def build_model(variant, num_classes, device):
    import torch.nn as nn
    if variant == "dla":
        from torchvision import models
        model = models.resnet50(weights=None)
        model.fc = nn.Linear(model.fc.in_features, num_classes)
        return model.to(device).eval()
    if variant == "fma":
        try:
            import timm
        except ImportError:
            raise RuntimeError("FMA icin 'timm' gerekir (pip install timm>=1.0.16).")
        # DIKKAT: pretrained=False olsa bile timm, model YAPILANDIRMASINI kapili
        # MahmoodLab/uni deposundan ceker. Erisim onayi yoksa burada 401/403 alinir.
        model = timm.create_model(UNI_HUB, pretrained=False, num_classes=0,
                                  init_values=1e-5, dynamic_img_size=True)
        feat = getattr(model, "num_features", 1024)
        model.head = nn.Linear(feat, num_classes)
        return model.to(device).eval()
    raise RuntimeError("Bilinmeyen varyant: %s" % variant)


def load_model(model_dir, variant, task, device):
    path = weight_path(model_dir, variant, task)
    if not os.path.isfile(path):
        raise FileNotFoundError(
            "Model agirligi yok: %s\n'download' komutuyla indirin ya da --model-dir'i duzeltin." % path)
    # Git LFS isaretcisi (agirlik yerine ~130 baytlik metin) yaygin bir tuzak.
    if os.path.getsize(path) < 100000:
        raise RuntimeError(
            "Model dosyasi cok kucuk (%d bayt): %s\n"
            "Bu bir Git LFS isaretcisi olabilir — agirligi Hugging Face'ten indirin."
            % (os.path.getsize(path), path))
    state = _load_state(path, device)
    n_out, key = head_width(state, variant)
    model = build_model(variant, n_out, device)
    model.load_state_dict(state, strict=True)
    log("Model hazir: %s/%s  cikis=%d (%s)  aygit=%s" % (variant, task, n_out, key, device))
    return model, n_out


def make_transform():
    from torchvision import transforms
    # ToTensor YOK: girdi zaten float [0,1] tensor. CenterCrop YOK.
    return transforms.Compose([
        transforms.Resize(INPUT_SIZE),
        transforms.Normalize(mean=IMAGENET_MEAN, std=IMAGENET_STD),
    ])


def load_tile(path, transform):
    """Upstream ile ayni okuma yolu: read_image(RGB).float()/255 -> transform."""
    import torchvision
    from torchvision.io import ImageReadMode
    img = torchvision.io.read_image(path, mode=ImageReadMode.RGB).float() / 255.0
    return transform(img)


def batched(seq, n):
    for i in range(0, len(seq), n):
        yield seq[i:i + n]


def run_model(model, entries, transform, device, batch_size):
    """entries -> her giris icin ham logit tensoru (list of 1-D tensor)."""
    import torch
    out = []
    done = 0
    total = len(entries)
    for chunk in batched(entries, batch_size):
        tensors, keep = [], []
        for e in chunk:
            try:
                tensors.append(load_tile(e["path"], transform))
                keep.append(e)
            except Exception as exc:
                log("  karo atlandi (%s): %s" % (os.path.basename(e.get("path", "?")), exc))
                out.append((e, None))
        if tensors:
            batch = torch.stack(tensors).to(device)
            with torch.no_grad():
                logits = model(batch)
            for e, row in zip(keep, logits):
                out.append((e, row.detach().float().cpu()))
        done += len(chunk)
        log("PROGRESS %d/%d" % (min(done, total), total))
    return out


def artifact_probability(logit_row, n_out):
    """Ikili modelin 'artefakt' olasiligi — cikis genisligine gore dogru aktivasyon."""
    import torch
    if n_out == 1:
        return float(torch.sigmoid(logit_row)[0])
    probs = torch.softmax(logit_row, dim=-1)
    return float(probs[BINARY_ARTIFACT_INDEX])


def type_probabilities(logit_row):
    import torch
    probs = torch.softmax(logit_row, dim=-1)
    n = min(len(probs), len(ARTIFACT_CLASSES))
    return {ARTIFACT_CLASSES[i]: float(probs[i]) for i in range(n)}


# --------------------------------------------------------------------------- #
# Komutlar
# --------------------------------------------------------------------------- #
def cmd_selftest(args):
    log("== histoart_bridge selftest ==")
    ok = True
    for mod in ("torch", "torchvision", "PIL", "numpy"):
        try:
            m = __import__(mod)
            log("OK  %-13s %s" % (mod, getattr(m, "__version__", "?")))
        except Exception as exc:
            ok = False
            log("EKSIK %-11s %s" % (mod, exc))
    if args.variant == "fma":
        try:
            import timm
            log("OK  %-13s %s" % ("timm", getattr(timm, "__version__", "?")))
        except Exception as exc:
            ok = False
            log("EKSIK %-11s %s" % ("timm", exc))
    if not ok:
        log("SELFTEST BASARISIZ — eksik bagimliliklar var.")
        return 1

    try:
        import torch
        log("Aygit: %s" % ("cuda" if torch.cuda.is_available() else "cpu"))
    except Exception:
        pass

    md = args.model_dir or ""
    for task in ("binary", "multi"):
        p = weight_path(md, args.variant, task) if md else ""
        if p and os.path.isfile(p):
            size_mb = os.path.getsize(p) / (1024.0 * 1024.0)
            if os.path.getsize(p) < 100000:
                log("UYARI %-6s %s — yalniz %.1f KB; Git LFS isaretcisi olabilir."
                    % (task, p, os.path.getsize(p) / 1024.0))
            else:
                log("OK  agirlik %-6s %s (%.0f MB)" % (task, p, size_mb))
        else:
            log("YOK agirlik %-6s — 'download' komutuyla indirin." % task)

    if args.variant == "fma":
        log("NOT: FMA omurgasi %s uzerinden kurulur — bu depo KAPILIDIR "
            "(CC-BY-NC-ND 4.0). Erisim onayi + 'huggingface-cli login' gerekir." % UNI_HUB)
        try:
            from huggingface_hub import whoami
            log("OK  HuggingFace oturumu: %s" % whoami().get("name", "?"))
        except Exception:
            log("UYARI: HF oturumu yok — FMA model kurulumunda 401/403 alinabilir. "
                "DLA varyanti ungated'dir ve erisim gerektirmez.")

    log("Coklu sinif sirasi: %s" % ", ".join(ARTIFACT_CLASSES))
    log("SELFTEST OK")
    return 0


def cmd_download(args):
    try:
        from huggingface_hub import hf_hub_download
    except ImportError:
        log("HATA: huggingface_hub gerekir (pip install huggingface_hub).")
        return 1
    os.makedirs(args.model_dir, exist_ok=True)
    tasks = ["binary", "multi"] if args.task == "both" else [args.task]
    for task in tasks:
        fname = WEIGHT_FILES[(args.variant, task)]
        log("Indiriliyor: %s / %s ..." % (HF_REPO, fname))
        try:
            src = hf_hub_download(repo_id=HF_REPO, filename=fname)
        except Exception as exc:
            log("HATA indirme basarisiz (%s): %s" % (fname, exc))
            return 1
        dst = os.path.join(args.model_dir, fname)
        if os.path.abspath(src) != os.path.abspath(dst):
            import shutil
            shutil.copyfile(src, dst)
        log("OK  %s (%.0f MB)" % (dst, os.path.getsize(dst) / (1024.0 * 1024.0)))
    log("DONE download n=%d" % len(tasks))
    return 0


def cmd_predict(args):
    with open(args.manifest, "r", encoding="utf-8") as f:
        entries = json.load(f)
    entries = [e for e in entries if e.get("path")]
    log("Tahmin: %d karo (varyant=%s, gorev=%s, esik=%.2f)."
        % (len(entries), args.variant, args.task, args.threshold))
    if not entries:
        log("HATA: siniflandirilacak karo yok.")
        return 2

    device = resolve_device(args.device)
    transform = make_transform()
    results = {e["id"]: {"id": e["id"], "artifact": None, "artifact_prob": None,
                         "artifact_type": None, "type_probabilities": None}
               for e in entries}

    positives = entries
    if args.task in ("binary", "both"):
        model, n_out = load_model(args.model_dir, args.variant, "binary", device)
        log("Asama 1/%d — ikili artefakt tespiti" % (2 if args.task == "both" else 1))
        rows = run_model(model, entries, transform, device, args.batch_size)
        positives = []
        for e, row in rows:
            if row is None:
                continue
            p = artifact_probability(row, n_out)
            rec = results[e["id"]]
            rec["artifact_prob"] = p
            rec["artifact"] = bool(p >= args.threshold)
            if rec["artifact"]:
                positives.append(e)
        del model
        log("Ikili sonuc: %d/%d karoda artefakt (esik %.2f)."
            % (len(positives), len(entries), args.threshold))

    if args.task in ("multi", "both"):
        if not positives:
            log("Artefakt bulunmadi — tur siniflandirmasi atlandi.")
        else:
            model, n_out = load_model(args.model_dir, args.variant, "multi", device)
            if n_out != len(ARTIFACT_CLASSES):
                log("UYARI: coklu model %d cikis veriyor ama beklenen %d sinif var (%s). "
                    "Etiket eslesmesi guvenilmez olabilir."
                    % (n_out, len(ARTIFACT_CLASSES), ", ".join(ARTIFACT_CLASSES)))
            log("Asama 2 — artefakt turu (%d karo)" % len(positives))
            rows = run_model(model, positives, transform, device, args.batch_size)
            for e, row in rows:
                if row is None:
                    continue
                probs = type_probabilities(row)
                best = max(probs.items(), key=lambda kv: kv[1])
                rec = results[e["id"]]
                rec["type_probabilities"] = probs
                rec["artifact_type"] = best[0]
            del model

    out_list = [results[e["id"]] for e in entries]
    os.makedirs(args.output_dir, exist_ok=True)
    with open(os.path.join(args.output_dir, "predictions.json"), "w", encoding="utf-8") as f:
        json.dump(out_list, f, ensure_ascii=False)

    scorecard = build_scorecard(out_list, args)
    with open(os.path.join(args.output_dir, "scorecard.json"), "w", encoding="utf-8") as f:
        json.dump(scorecard, f, ensure_ascii=False)

    log("Karne: %d karo, %d temiz (%.1f%%), %d artefaktli."
        % (scorecard["n_tiles"], scorecard["n_clean"],
           scorecard["clean_percent"], scorecard["n_artifact"]))
    for name, cnt in scorecard["type_counts"].items():
        log("  %-16s %d" % (CLASS_TR.get(name, name), cnt))
    log("DONE predict n=%d" % len(out_list))
    return 0


def build_scorecard(out_list, args):
    n = len(out_list)
    scored = [r for r in out_list if r["artifact"] is not None]
    n_art = sum(1 for r in scored if r["artifact"])
    n_clean = sum(1 for r in scored if not r["artifact"])
    counts = {}
    for r in out_list:
        t = r.get("artifact_type")
        if t:
            counts[t] = counts.get(t, 0) + 1
    ordered = {c: counts.get(c, 0) for c in ARTIFACT_CLASSES if counts.get(c, 0) > 0}
    denom = float(len(scored)) if scored else 0.0
    return {
        "n_tiles": n,
        "n_scored": len(scored),
        "n_clean": n_clean,
        "n_artifact": n_art,
        "clean_percent": (100.0 * n_clean / denom) if denom else 0.0,
        "artifact_percent": (100.0 * n_art / denom) if denom else 0.0,
        "type_counts": ordered,
        "type_labels_tr": {c: CLASS_TR.get(c, c) for c in ARTIFACT_CLASSES},
        "class_order": list(ARTIFACT_CLASSES),
        "variant": args.variant,
        "task": args.task,
        "threshold": args.threshold,
    }


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def build_parser():
    p = argparse.ArgumentParser(
        description="HistoART artefakt tespiti QuPath koprusu (yalniz PNG karolari okur).")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("selftest", help="Bagimliliklari, aygiti ve agirliklari dogrula.")
    s.add_argument("--variant", choices=["dla", "fma"], default="dla")
    s.add_argument("--model-dir", default="")
    s.set_defaults(func=cmd_selftest)

    d = sub.add_parser("download", help="Agirliklari Hugging Face'ten indir (didsr/HistoArt).")
    d.add_argument("--model-dir", required=True)
    d.add_argument("--variant", choices=["dla", "fma"], default="dla")
    d.add_argument("--task", choices=["binary", "multi", "both"], default="both")
    d.set_defaults(func=cmd_download)

    r = sub.add_parser("predict", help="Karolari siniflandir (ikili -> tur).")
    r.add_argument("--manifest", required=True)
    r.add_argument("--output-dir", required=True)
    r.add_argument("--model-dir", required=True)
    r.add_argument("--variant", choices=["dla", "fma"], default="dla")
    r.add_argument("--task", choices=["both", "binary", "multi"], default="both")
    r.add_argument("--threshold", type=float, default=0.5)
    r.add_argument("--batch-size", type=int, default=16)
    r.add_argument("--device", choices=["auto", "cpu", "cuda"], default="auto")
    r.set_defaults(func=cmd_predict)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except FileNotFoundError as exc:
        log("HATA (dosya yok): %s" % exc)
        return 3
    except Exception as exc:
        log("HATA: %s: %s" % (type(exc).__name__, exc))
        return 1


if __name__ == "__main__":
    sys.exit(main())
