#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
tiatoolbox_bridge.py — QuPath atolyesi icin TIA Toolbox temel-islev koprusu
===========================================================================

NE YAPAR:
    Warwick TIA Centre'ın **TIA Toolbox** kutuphanesinin iki "temel islevini"
    QuPath'ten disa aktarilmis PNG'ler uzerinde calistirir:

      1. normalize    — Boyama normalizasyonu (Macenko / Vahadane / Reinhard).
                        QuPath color *deconvolution* yapar ama boya
                        *normalizasyonu* YAPMAZ; bu, tarayicidan-tarayiciya
                        (ornegin Aperio GT450 vs AT2) renk farkini bir referans
                        H&E gorunumune esitler. Cikti: normalize edilmis PNG('ler)
                        + (tek bolge modunda) yan-yana montage.png.
      2. tissue-mask  — Doku maskesi (Otsu / Morphological). Bir kucuk-cozunurluk
                        kucukresim (thumbnail) PNG'sini alir, doku/arka plan
                        ikili maskesi uretir; QuPath tarafi bunu anotasyona
                        cevirir (raster maske ice-aktarim eslesi).

    QuPath sihirbazi (yardimci-tiatoolbox-sihirbaz.groovy) bu betigi
    ProcessBuilder ile cagirir; veri alisverisi diskteki PNG + JSON uzerinden
    yapilir.

ONEMLI — OpenSlide GEREKMEZ:
    Bu betik WSI dosyasi ACMAZ. Yalnizca QuPath'in disa aktardigi PNG'leri
    (bolge / karo / kucukresim) okur. Boylece openslide gibi yerel kutuphanelere
    ihtiyac yoktur (SPIDER / Kaiko koprusuyle ayni tasarim). WSI'i dogrudan okuyan
    TIA Toolbox model is akislari (BCSS / KongNet / IDARS) icin: Ek — TIA Toolbox.

NE URETIR (ve ne URETMEZ):
    Yalnizca goruntu/maske (veri) uretir. Hicbir sayi/yuzde/klinik yorum
    uretmez. Boyama normalizasyonu bir on-isleme adimidir; ciktisi QuPath
    nesnesi degil, disktedeki goruntu dosyalaridir. Yalnizca arastirma/egitim
    amaclidir.

KULLANIM:
    python tiatoolbox_bridge.py selftest
    python tiatoolbox_bridge.py normalize --input bolge.png --reference ref.png
                                          --output-dir CIKTI --method macenko
    python tiatoolbox_bridge.py normalize --tiles-dir KAROLAR --reference ref.png
                                          --output-dir CIKTI --method vahadane
    python tiatoolbox_bridge.py tissue-mask --input kucukresim.png
                                            --output maske.png --method otsu
                                            --downsample 16.0 [--mpp 4.0]

REFERANS:
    Pocock J ve ark. (2022) "TIAToolbox as an end-to-end library for advanced
    tissue image analytics." Commun Med. doi:10.1038/s43856-022-00186-5
    Boyama normalizasyonu: Macenko ve ark. (2009) doi:10.1109/ISBI.2009.5193250;
    Vahadane ve ark. (2016) doi:10.1109/TMI.2016.2529665;
    Reinhard ve ark. (2001) doi:10.1109/38.946629.
    https://tia-toolbox.readthedocs.io/en/stable/  (Ek: TIA Toolbox)
"""

import argparse
import glob
import json
import os
import sys

# Boyama normalizasyonunda arka plan (neredeyse beyaz) karolari atla: stain yok →
# Macenko/Vahadane stain-matrisi kestiremez, hata verir.
BACKGROUND_MEAN = 220.0
VALID_METHODS = ("macenko", "vahadane", "reinhard")


def log(msg):
    """Satir-tamponlu yaz; QuPath log alani canli akar."""
    print(msg, flush=True)


# --------------------------------------------------------------------------- #
# Goruntu yardimcilari (numpy + PIL)
# --------------------------------------------------------------------------- #
def load_rgb(path):
    """PNG/JPG → uint8 RGB numpy dizisi."""
    import numpy as np
    from PIL import Image

    img = Image.open(path).convert("RGB")
    return np.asarray(img, dtype="uint8")


def save_rgb(arr, path):
    import numpy as np
    from PIL import Image

    if path and os.path.dirname(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(np.asarray(arr, dtype="uint8"), mode="RGB").save(path)


def is_background(arr):
    """Ortalama parlaklik > BACKGROUND_MEAN → arka plan (stain icermez)."""
    try:
        return float(arr.mean()) > BACKGROUND_MEAN
    except Exception:
        return False


def make_montage(original, normalized, path):
    """Orijinal | normalize edilmis goruntuyu yan yana tek PNG'ye yaz."""
    import numpy as np
    from PIL import Image

    a = Image.fromarray(np.asarray(original, dtype="uint8"), mode="RGB")
    b = Image.fromarray(np.asarray(normalized, dtype="uint8"), mode="RGB")
    h = max(a.height, b.height)
    gap = 8
    canvas = Image.new("RGB", (a.width + gap + b.width, h), (255, 255, 255))
    canvas.paste(a, (0, 0))
    canvas.paste(b, (a.width + gap, 0))
    if path and os.path.dirname(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
    canvas.save(path)


# --------------------------------------------------------------------------- #
# normalize
# --------------------------------------------------------------------------- #
def build_normalizer(method, reference_rgb):
    """get_normalizer(method) → .fit(referans). method: macenko/vahadane/reinhard."""
    from tiatoolbox.tools.stainnorm import get_normalizer

    normalizer = get_normalizer(method.lower())
    normalizer.fit(reference_rgb)
    return normalizer


def collect_inputs(args):
    """--input tek dosya VEYA --tiles-dir altindaki PNG'ler → (yol listesi, tekil mi)."""
    if args.input:
        if not os.path.isfile(args.input):
            raise FileNotFoundError("Girdi PNG bulunamadi: %s" % args.input)
        return [args.input], True
    if args.tiles_dir:
        if not os.path.isdir(args.tiles_dir):
            raise FileNotFoundError("Karo dizini bulunamadi: %s" % args.tiles_dir)
        paths = sorted(
            glob.glob(os.path.join(args.tiles_dir, "**", "*.png"), recursive=True)
        )
        return paths, False
    raise ValueError("--input ya da --tiles-dir verilmeli.")


def cmd_normalize(args):
    import numpy as np  # noqa: F401  (PIL/numpy dolayli kullanim)

    method = args.method.lower()
    if method not in VALID_METHODS:
        log("HATA: gecersiz yontem '%s'. Secenekler: %s" % (method, ", ".join(VALID_METHODS)))
        return 2

    inputs, single = collect_inputs(args)
    if not inputs:
        log("HATA: normalize edilecek PNG bulunamadi.")
        return 2

    # Referans goruntu sirasi: --builtin-reference > --reference > --reference-from-tiles.
    reference_rgb = None
    ref_label = None
    if args.builtin_reference:
        try:
            from tiatoolbox.data import stain_norm_target
            reference_rgb = stain_norm_target()
            ref_label = "(TIA Toolbox built-in: stain_norm_target)"
        except Exception as exc:
            log("HATA: TIA Toolbox built-in referans alinamadi: %s" % exc)
            return 1
    else:
        ref_path = args.reference
        if not ref_path and args.reference_from_tiles:
            for p in inputs:
                try:
                    a = load_rgb(p)
                except Exception:
                    continue
                if not is_background(a):
                    ref_path = p
                    break
        if not ref_path or not os.path.isfile(ref_path):
            log("HATA: referans goruntu yok. --builtin-reference, --reference <png> ya da "
                "--reference-from-tiles seceneklerinden birini verin.")
            return 2
        reference_rgb = load_rgb(ref_path)
        ref_label = os.path.abspath(ref_path)

    log("Yontem: %s   Referans: %s   Girdi: %d PNG" % (method, ref_label, len(inputs)))
    try:
        normalizer = build_normalizer(method, reference_rgb)
    except Exception as exc:
        log("HATA: normalizer kurulamadi (referansta stain kestirilemedi olabilir): %s" % exc)
        return 1

    out_dir = os.path.join(args.output_dir, "normalized")
    os.makedirs(out_dir, exist_ok=True)

    done = 0
    skipped = 0
    failed = 0
    montage_path = None
    total = len(inputs)
    for idx, p in enumerate(inputs):
        try:
            src = load_rgb(p)
        except Exception as exc:
            log("PROGRESS %d/%d: %s — okunamadi (%s)" % (idx + 1, total, os.path.basename(p), exc))
            failed += 1
            continue
        if is_background(src):
            skipped += 1
            log("PROGRESS %d/%d: %s — arka plan, atlandi" % (idx + 1, total, os.path.basename(p)))
            continue
        try:
            out = normalizer.transform(src)
        except Exception as exc:
            failed += 1
            log("PROGRESS %d/%d: %s — normalize hatasi (%s)" % (idx + 1, total, os.path.basename(p), exc))
            continue
        out_path = os.path.join(out_dir, os.path.basename(p))
        save_rgb(out, out_path)
        done += 1
        log("PROGRESS %d/%d: %s -> normalized/%s" % (idx + 1, total, os.path.basename(p), os.path.basename(p)))
        if single and montage_path is None:
            montage_path = os.path.join(args.output_dir, "montage.png")
            try:
                make_montage(src, out, montage_path)
                log("Montaj (orijinal | normalize): %s" % montage_path)
            except Exception as exc:
                montage_path = None
                log("UYARI: montaj yazilamadi: %s" % exc)

    manifest = {
        "command": "normalize",
        "method": method,
        "reference": ref_label,
        "input_count": total,
        "normalized": done,
        "skipped_background": skipped,
        "failed": failed,
        "output_dir": os.path.abspath(out_dir),
        "montage": os.path.abspath(montage_path) if montage_path else None,
        "note": "Yalnizca arastirma/egitim amacli on-isleme. Cikti goruntu dosyalaridir, QuPath nesnesi degil.",
    }
    with open(os.path.join(args.output_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    log("Normalize tamam. %d yazildi, %d arka plan atlandi, %d hata -> %s"
        % (done, skipped, failed, out_dir))
    log("DONE normalize n=%d" % done)
    return 0 if done > 0 else 1


# --------------------------------------------------------------------------- #
# tissue-mask
# --------------------------------------------------------------------------- #
def cmd_tissue_mask(args):
    import numpy as np
    from PIL import Image

    if not os.path.isfile(args.input):
        log("HATA: girdi kucukresim PNG bulunamadi: %s" % args.input)
        return 2

    method = args.method.lower()
    thumb = load_rgb(args.input)

    try:
        if method == "morphological":
            from tiatoolbox.tools.tissuemask import MorphologicalMasker
            if args.mpp and args.mpp > 0:
                masker = MorphologicalMasker(mpp=float(args.mpp))
            else:
                masker = MorphologicalMasker()
        else:
            from tiatoolbox.tools.tissuemask import OtsuTissueMasker
            masker = OtsuTissueMasker()
        masks = masker.fit_transform([thumb])
        mask_bool = np.asarray(masks[0])
    except Exception as exc:
        log("HATA: doku maskesi uretilemedi: %s" % exc)
        return 1

    # Bool → indeksli uint8 (0 = arka plan, 1 = doku)
    mask_idx = (mask_bool.astype("uint8") > 0).astype("uint8")
    h, w = mask_idx.shape[:2]

    out_png = args.output
    if out_png and os.path.dirname(out_png):
        os.makedirs(os.path.dirname(out_png), exist_ok=True)
    Image.fromarray(mask_idx, mode="L").save(out_png)

    # Yan dosya <maske>.json — QuPath "Maske goruntusunu ice aktar" yardimcisi
    # bundan downsample + sinif adlarini okur (anahtarlar: downsample, labels).
    stem = os.path.splitext(out_png)[0]
    sidecar = stem + ".json"
    tissue_px = int(mask_idx.sum())
    meta = {
        "maskFile": os.path.basename(out_png),
        "command": "tissue-mask",
        "method": method,
        "downsample": float(args.downsample) if args.downsample else 1.0,
        "mpp": float(args.mpp) if args.mpp else None,
        "maskWidth": w,
        "maskHeight": h,
        "labels": {"0": "arka plan", "1": "Doku"},
        "tissuePixels": tissue_px,
        "tissueFraction": (tissue_px / float(w * h)) if (w * h) else 0.0,
        "note": "TIA Toolbox doku maskesi (0=arka plan, 1=doku). downsample = "
                "1 maske pikseli kac taban-piksele denk. Yalnizca arastirma/egitim amacli.",
    }
    with open(sidecar, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)

    log("Doku maskesi: %s (%d x %d, doku orani %.1f%%)"
        % (out_png, w, h, 100.0 * meta["tissueFraction"]))
    log("Yan dosya  : %s" % sidecar)
    log("DONE tissue-mask tissue_fraction=%.4f" % meta["tissueFraction"])
    return 0


# --------------------------------------------------------------------------- #
# selftest
# --------------------------------------------------------------------------- #
def cmd_selftest(args):
    log("== tiatoolbox_bridge selftest ==")
    ok = True
    versions = {}
    for mod in ("numpy", "PIL", "tiatoolbox"):
        try:
            m = __import__(mod)
            ver = getattr(m, "__version__", "?")
            versions[mod] = ver
            log("OK  %-12s %s" % (mod, ver))
        except Exception as exc:
            ok = False
            log("EKSIK %-10s %s" % (mod, exc))
    if not ok:
        log("SELFTEST BASARISIZ — once: pip install tiatoolbox numpy pillow")
        return 1

    # Temel islev modulleri yuklenebiliyor mu?
    try:
        from tiatoolbox.tools.stainnorm import get_normalizer  # noqa: F401
        log("OK  stainnorm  get_normalizer yuklendi (macenko/vahadane/reinhard)")
    except Exception as exc:
        ok = False
        log("EKSIK stainnorm  %s" % exc)
    try:
        from tiatoolbox.tools.tissuemask import OtsuTissueMasker, MorphologicalMasker  # noqa: F401
        log("OK  tissuemask Otsu + Morphological yuklendi")
    except Exception as exc:
        ok = False
        log("EKSIK tissuemask %s" % exc)

    # Istege bagli: torch/cuda (boyama normalizasyonu icin GEREKMEZ; bilgilendirme)
    try:
        import torch
        log("OK  torch        %s  (cuda: %s)"
            % (torch.__version__, "var" if torch.cuda.is_available() else "yok"))
    except Exception:
        log("Bilgi: torch yok — boyama normalizasyonu / doku maskesi icin GEREKMEZ.")

    log("SELFTEST OK" if ok else "SELFTEST BASARISIZ — yukaridaki EKSIK satirlarina bakin.")
    return 0 if ok else 1


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def build_parser():
    p = argparse.ArgumentParser(
        description="TIA Toolbox temel-islev QuPath koprusu (boyama normalizasyonu + doku maskesi; "
                    "yalniz QuPath'in disa aktardigi PNG'leri okur, WSI ACMAZ).")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("selftest", help="Bagimliliklari (tiatoolbox, numpy, PIL) dogrula.")
    s.set_defaults(func=cmd_selftest)

    n = sub.add_parser("normalize", help="Boyama normalizasyonu (Macenko/Vahadane/Reinhard).")
    n.add_argument("--input", default="", help="Tek girdi PNG (bolge onizleme modu).")
    n.add_argument("--tiles-dir", default="", help="PNG karo dizini (karo grubu modu; ozyinelemeli).")
    n.add_argument("--reference", default="", help="Referans H&E PNG (hedef boya gorunumu).")
    n.add_argument("--reference-from-tiles", action="store_true",
                   help="Referans verilmezse ilk stain-iceren girdiyi referans al.")
    n.add_argument("--builtin-reference", action="store_true",
                   help="TIA Toolbox'in yerlesik referansini kullan (tiatoolbox.data.stain_norm_target).")
    n.add_argument("--output-dir", required=True)
    n.add_argument("--method", choices=list(VALID_METHODS), default="macenko")
    n.set_defaults(func=cmd_normalize)

    t = sub.add_parser("tissue-mask", help="Doku maskesi (Otsu / Morphological).")
    t.add_argument("--input", required=True, help="Kucuk-cozunurluk kucukresim PNG (QuPath'ten).")
    t.add_argument("--output", required=True, help="Cikti maske PNG (0=arka plan, 1=doku).")
    t.add_argument("--method", choices=["otsu", "morphological"], default="otsu")
    t.add_argument("--downsample", type=float, default=1.0,
                   help="1 maske pikseli kac taban-piksele denk (yan JSON'a yazilir).")
    t.add_argument("--mpp", type=float, default=0.0,
                   help="Maskenin µm/px cozunurlugu (Morphological masker icin; ops.).")
    t.set_defaults(func=cmd_tissue_mask)
    return p


def apply_tiatoolbox_home():
    """TIATOOLBOX_HOME ortam degiskenini tiatoolbox.rcParam'a uygula (bkz.
    region_runner.apply_tiatoolbox_home). tiatoolbox env var'i okumaz; atolye
    veri kokunu C: disina alan kullanici icin cache kokunu calisma zamaninda
    override eder. Bu kopru cogunlukla klasik (model-suz) islemler yapar; guard
    tutarlilik ve ileri kullanim icin eklenmistir."""
    th = os.environ.get("TIATOOLBOX_HOME")
    if not th:
        return
    try:
        import pathlib
        import tiatoolbox
        tiatoolbox.rcParam["TIATOOLBOX_HOME"] = pathlib.Path(th)
    except Exception:  # noqa: BLE001
        pass


def main(argv=None):
    args = build_parser().parse_args(argv)
    apply_tiatoolbox_home()
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
