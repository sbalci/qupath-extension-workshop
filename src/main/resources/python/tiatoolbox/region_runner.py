#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
region_runner.py — QuPath atolyesi icin TIA Toolbox BOLGE-KISITLI cikarim koprusu
=================================================================================

NE YAPAR:
    Warwick TIA Centre'ın **TIA Toolbox** model motorlarini (nucleus_detector,
    semantic_segmentor, ...) bir WSI uzerinde, ama yalnizca QuPath'te cizilen
    bir BOLGE icinde calistirir. Bolge, QuPath tarafindan disa aktarilan ikili
    (binary) bir maske PNG'siyle verilir; motor `masks=[maske]` + `auto_get_mask
    =False` ile yalnizca maskenin sifir-disi oldugu yerde cikarim yapar. Cikti,
    QuPath'e geri alinabilen bir **GeoJSON** (taban-piksel koordinatlari) dosyasidir.

    Resmi TIA Toolbox QuPath eklentisi bir bolgeye kisitlama YAPAMAZ (yalniz
    "Current image" / "All project images"); bu kopru o boslugu doldurur. Tasarim
    eklentinin kendi sidecar'indaki visible-bounds maskesiyle ayni: ikili numpy
    maskesini `engine.run(masks=[...], auto_get_mask=False)`'a verir.

ONEMLI — bu kopru WSI'i DOGRUDAN acar (OpenSlide/tiffslide):
    Boya-normalizasyonu koprusunun (tiatoolbox_bridge.py) aksine, bu betik
    tiatoolbox motorlariyla WSI'i kendisi okur; bu yuzden tiatoolbox + torch
    iceren bir ortam gerekir (atolyede: QuPath/v*/tiatoolbox-runtime/.venv).

NE URETIR (ve ne URETMEZ):
    Yalnizca tespit/segmentasyon (GeoJSON nesneleri) uretir. Hicbir klinik
    esik/alt-tip/grade/yorum uretmez. Yalnizca arastirma/egitim amaclidir.

KULLANIM:
    python region_runner.py selftest
    python region_runner.py detect --wsi SLAYT.svs --mask bolge_maske.png \\
        --model KongNet_Det_MIDOG_1 --engine nucleus_detector \\
        --out cikti.geojson --save-dir CIKTI --device cuda \\
        [--classes "Mitotic figure"]

REFERANS:
    Pocock J ve ark. (2022) "TIAToolbox ..." Commun Med.
    doi:10.1038/s43856-022-00186-5 ; https://tia-toolbox.readthedocs.io/en/stable/
"""

import argparse
import json
import os
import shutil
import sys

# QuPath GeoJSON cikti uzantilari (tiatoolbox output_type="qupath" .json yazar).
GEOJSON_SUFFIXES = (".geojson", ".json")

# engine adi -> (modul, sinif). runners.py (resmi eklenti) ile ayni harita.
ENGINES = {
    "patch_predictor": ("tiatoolbox.models.engine.patch_predictor", "PatchPredictor"),
    "semantic_segmentor": ("tiatoolbox.models.engine.semantic_segmentor", "SemanticSegmentor"),
    "multi_task_segmentor": ("tiatoolbox.models.engine.multi_task_segmentor", "MultiTaskSegmentor"),
    "nucleus_detector": ("tiatoolbox.models.engine.nucleus_detector", "NucleusDetector"),
}


def log(msg):
    """Satir-tamponlu yaz; QuPath log alani canli akar."""
    print(msg, flush=True)


def apply_tiatoolbox_home():
    """TIATOOLBOX_HOME ortam degiskenini tiatoolbox.rcParam'a uygula.

    tiatoolbox bu env var'i KENDISI OKUMAZ: rcParam["TIATOOLBOX_HOME"] sabit
    Path.home()/'.tiatoolbox' olarak import aninda hesaplanir ve model
    agirliklari (KongNet/MapDe pretrained) her zaman oraya (C:) iner. Atolye
    veri kokunu baska bir surucuye alan kullanici icin C:'yi doldurmamak adina,
    QuPath tarafinin gecirdigi TIATOOLBOX_HOME'u calisma zamaninda rcParam'a
    yaziyoruz. fetch_pretrained_weights rcParam'i cagri aninda okudugu icin bu
    HERHANGI bir engine olusturmadan ONCE cagrilmalidir.
    """
    th = os.environ.get("TIATOOLBOX_HOME")
    if not th:
        return
    try:
        import pathlib
        import tiatoolbox
        tiatoolbox.rcParam["TIATOOLBOX_HOME"] = pathlib.Path(th)
        log("TIATOOLBOX_HOME -> %s" % th)
    except Exception as exc:  # noqa: BLE001
        log("TIATOOLBOX_HOME uygulanamadi (yok sayildi): %s" % exc)


def quiet_benign_logs():
    """IYI-HUYLU log gurultusunu sustur (calisma bozulmaz, ciktilar aynidir).

    Bu 3 satir kullaniciyi bosuna endiselendiriyor ama tespiti etkilemiyor:
      - offline modda huggingface_hub 'Couldn't access the Hub ... offline mode'
        (yerel modeli kullanir; biz zaten 'Cevrimdisi mod' satirini yaziyoruz),
      - 'GPU is not compatible with torch.compile' (yalnizca hiz notu),
      - 'image mode is set to feature' (2B ikili MASKE sariliyor, RGB slayt degil).
    Yalnizca bu SABIT mesajlar susturulur; INFO ve gercek hatalar gecer.
    """
    import logging
    muted = (
        "Couldn't access the Hub",
        "not compatible with torch.compile",
        "image mode is set to 'feature'",
    )

    class _BenignFilter(logging.Filter):
        def filter(self, record):  # noqa: A003
            try:
                return not any(m in record.getMessage() for m in muted)
            except Exception:  # noqa: BLE001
                return True

    try:
        f = _BenignFilter()
        for name in ("", "tiatoolbox", "huggingface_hub"):
            lg = logging.getLogger(name)
            lg.addFilter(f)
            for h in list(lg.handlers):
                h.addFilter(f)
        # huggingface_hub offline uyarisi WARNING seviyesinde -> ERROR'a al (yedek).
        logging.getLogger("huggingface_hub").setLevel(logging.ERROR)
    except Exception:  # noqa: BLE001
        pass


def load_engine(engine_name):
    spec = ENGINES.get(engine_name)
    if spec is None:
        raise ValueError("Bilinmeyen engine '%s'. Secenekler: %s"
                         % (engine_name, ", ".join(sorted(ENGINES))))
    module, cls = spec
    mod = __import__(module, fromlist=[cls])
    return getattr(mod, cls)


def load_binary_mask(path):
    """Maske PNG -> uint8 ikili numpy dizisi (sifir-disi = dahil bolge)."""
    import numpy as np
    from PIL import Image

    if not os.path.isfile(path):
        raise FileNotFoundError("Maske PNG bulunamadi: %s" % path)
    arr = np.asarray(Image.open(path).convert("L"))
    return (arr > 0).astype("uint8")


def collect_geojson(result, save_dir):
    """Motorun donus degerini / save_dir'i tarayarak GeoJSON yolunu bul.

    tiatoolbox output_type='qupath' iken '.json' yazar ve save_dir'e zaman damgali
    bir alt-dizin ekleyebilir; bu yuzden donen yapiyi VE save_dir + kardes dizinleri
    tararız (runners.py ile ayni mantik).
    """
    found = []

    def walk(value):
        if value is None:
            return
        if isinstance(value, (str, bytes, os.PathLike)):
            p = str(value)
            if p.lower().endswith(GEOJSON_SUFFIXES) and os.path.isfile(p):
                found.append(p)
        elif isinstance(value, dict):
            for v in value.values():
                walk(v)
        elif isinstance(value, (list, tuple, set)):
            for v in value:
                walk(v)

    walk(result)
    if found:
        return sorted(set(found))

    cand_dirs = []
    if os.path.isdir(save_dir):
        cand_dirs.append(save_dir)
    parent = os.path.dirname(os.path.abspath(save_dir))
    base = os.path.basename(os.path.abspath(save_dir))
    if os.path.isdir(parent):
        for name in os.listdir(parent):
            d = os.path.join(parent, name)
            if os.path.isdir(d) and name.startswith(base) and os.path.abspath(d) != os.path.abspath(save_dir):
                cand_dirs.append(d)
    for d in cand_dirs:
        for root, _dirs, files in os.walk(d):
            for fn in files:
                if fn.lower().endswith(GEOJSON_SUFFIXES):
                    found.append(os.path.join(root, fn))
    return sorted(set(found))


def count_features(geojson_path):
    try:
        with open(geojson_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return len(data.get("features") or [])
    except Exception:
        return -1


def cmd_detect(args):
    import numpy as np  # noqa: F401  (PIL/numpy dolayli kullanim)

    if not os.path.isfile(args.wsi):
        log("HATA: WSI bulunamadi: %s" % args.wsi)
        return 3
    try:
        mask = load_binary_mask(args.mask)
    except Exception as exc:
        log("HATA: maske okunamadi: %s" % exc)
        return 2
    if int(mask.sum()) == 0:
        log("HATA: maske tamamen bos (sifir). QuPath'te bir bolge cizip "
            "SINIFLANDIRDIGINIZDAN emin olun.")
        return 2

    try:
        engine_cls = load_engine(args.engine)
    except Exception as exc:
        log("HATA: %s" % exc)
        return 2

    quiet_benign_logs()   # tiatoolbox + hf import edildi; iyi-huylu uyarilari sustur

    log("Engine : %s   Model: %s   Cihaz: %s"
        % (engine_cls.__name__, args.model, args.device))
    log("Maske  : %s (%d x %d, dahil piksel: %d)"
        % (os.path.basename(args.mask), mask.shape[1], mask.shape[0], int(mask.sum())))
    log("WSI    : %s" % os.path.basename(args.wsi))

    os.makedirs(args.save_dir, exist_ok=True)
    try:
        eng = engine_cls(
            model=args.model,
            batch_size=int(args.batch_size),
            num_workers=0,
            device=args.device,
            verbose=False,
        )
    except Exception as exc:
        log("HATA: motor kurulamadi (model adi / agirlik indirme?): %s" % exc)
        return 1

    # Duyarlilik: KongNet_Det_MIDOG_1 gibi modeller cok tutucu bir threshold_abs ile
    # gelir (registry'de 0.99) -> gercek mitozlarin cogu esigi gecemez, n=0 cikabilir.
    # Kullanici --threshold verdiyse modelin esigini dusur (dusuk = daha cok tespit).
    thr = getattr(args, "threshold", None)
    if thr is not None and thr >= 0:
        applied = False
        for owner in (getattr(eng, "model", None), getattr(getattr(eng, "model", None), "model", None)):
            if owner is not None and hasattr(owner, "threshold_abs"):
                try:
                    old = owner.threshold_abs
                    owner.threshold_abs = float(thr)
                    log("Duyarlilik: threshold_abs %.3f -> %.3f (dusuk = daha cok mitoz)"
                        % (float(old), float(thr)))
                    applied = True
                except Exception as exc:  # noqa: BLE001
                    log("UYARI: threshold_abs ayarlanamadi: %s" % exc)
                break
        if not applied:
            log("UYARI: model 'threshold_abs' ozelligi bulunamadi; --threshold yok sayildi "
                "(bu model esigi disaridan ayarlanamiyor olabilir).")

    run_kwargs = dict(
        images=[args.wsi],
        masks=[mask],
        patch_mode=False,
        save_dir=args.save_dir,
        overwrite=True,
        output_type="qupath",
        auto_get_mask=False,
    )
    # nucleus_detector: class_dict verilirse GeoJSON dogrudan sinif adlarini kullanir.
    classes = [c.strip() for c in (args.classes or "").split(",") if c.strip()]
    if args.engine == "nucleus_detector" and classes:
        run_kwargs["class_dict"] = {i: name for i, name in enumerate(classes)}

    log("Cikarim basliyor (yalniz maske bolgesi)...")
    try:
        result = eng.run(**run_kwargs)
    except Exception as exc:
        log("HATA: cikarim basarisiz: %s: %s" % (type(exc).__name__, exc))
        return 1

    geojsons = collect_geojson(result, args.save_dir)
    if not geojsons:
        log("HATA: motor calisti ama GeoJSON ciktisi bulunamadi (save_dir: %s)." % args.save_dir)
        return 1

    src = geojsons[0]
    out = args.out or src
    if os.path.abspath(out) != os.path.abspath(src):
        try:
            if os.path.dirname(out):
                os.makedirs(os.path.dirname(out), exist_ok=True)
            shutil.copyfile(src, out)
        except Exception as exc:
            log("UYARI: GeoJSON --out'a kopyalanamadi (%s); kaynak kullanilacak." % exc)
            out = src

    n = count_features(out)
    log("GeoJSON: %s" % out)
    log("RESULT geojson=%s" % out)
    log("DONE detect n=%d" % (n if n >= 0 else 0))
    return 0


def cmd_selftest(args):
    log("== region_runner selftest ==")
    ok = True
    for mod in ("numpy", "PIL", "tiatoolbox", "torch"):
        try:
            m = __import__(mod)
            log("OK  %-12s %s" % (mod, getattr(m, "__version__", "?")))
        except Exception as exc:
            ok = False
            log("EKSIK %-10s %s" % (mod, exc))
    if not ok:
        log("SELFTEST BASARISIZ — TIA Toolbox calisma zamani kurulu mu? "
            "(QuPath/v*/tiatoolbox-runtime/.venv)")
        return 1

    try:
        import torch
        log("OK  cihaz        cuda: %s" % ("var" if torch.cuda.is_available() else "yok (cpu)"))
    except Exception:
        pass

    # Motor siniflari yuklenebiliyor mu?
    for name in ("nucleus_detector", "semantic_segmentor"):
        try:
            load_engine(name)
            log("OK  engine       %s yuklendi" % name)
        except Exception as exc:
            ok = False
            log("EKSIK engine     %s: %s" % (name, exc))

    log("SELFTEST OK" if ok else "SELFTEST BASARISIZ — yukaridaki EKSIK satirlarina bakin.")
    return 0 if ok else 1


def cmd_download(args):
    """Model agirliklarini TIATOOLBOX_HOME/models altina BIR KEZ indir (yerel).

    Sonrasinda detect'i cevrimdisi (HF_HUB_OFFLINE=1) calistirmak agdan bagimsiz
    olur — GrandQC .pth indirme adiminin muadili. Indirme her zaman CEVRIMICI olur
    (offline env varsa kaldirilir).
    """
    for k in ("HF_HUB_OFFLINE", "TRANSFORMERS_OFFLINE", "HF_HUB_OFFLINE_MODE"):
        os.environ.pop(k, None)
    apply_tiatoolbox_home()
    th = os.environ.get("TIATOOLBOX_HOME")
    if th:
        log("TIATOOLBOX_HOME -> %s" % th)
    try:
        from tiatoolbox.models.architecture import fetch_pretrained_weights
    except Exception as exc:  # noqa: BLE001
        log("HATA: tiatoolbox import edilemedi: %s: %s" % (type(exc).__name__, exc))
        return 2
    try:
        path = fetch_pretrained_weights(
            args.model, overwrite=bool(getattr(args, "overwrite", False)))
    except Exception as exc:  # noqa: BLE001
        log("HATA: model indirilemedi: %s: %s" % (type(exc).__name__, exc))
        return 1
    p = str(path)
    if os.path.isfile(p):
        try:
            size_mb = os.path.getsize(p) / (1024.0 * 1024.0)
        except OSError:
            size_mb = 0.0
        log("Model yerel olarak indirildi: %s (%.1f MB)" % (p, size_mb))
        log("DONE download ok=1 path=%s" % p)
        return 0
    log("HATA: indirme bildirildi ama dosya bulunamadi: %s" % p)
    return 1


def build_parser():
    p = argparse.ArgumentParser(
        description="TIA Toolbox model motorlarini bir WSI'nin maskelenmis BOLGESINDE "
                    "calistirir; GeoJSON uretir (QuPath'e geri alinir).")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("selftest", help="Bagimliliklari (tiatoolbox, torch) dogrula.")
    s.set_defaults(func=cmd_selftest)

    dl = sub.add_parser("download", help="Model agirliklarini yerel indir (TIATOOLBOX_HOME/models).")
    dl.add_argument("--model", required=True, help="TIA Toolbox model adi (or. KongNet_Det_MIDOG_1).")
    dl.add_argument("--overwrite", action="store_true", help="Var olan dosyanin uzerine yaz.")
    dl.set_defaults(func=cmd_download)

    d = sub.add_parser("detect", help="Maskelenmis bolgede model cikarimi (GeoJSON).")
    d.add_argument("--wsi", required=True, help="WSI dosyasi (.svs/.ndpi/...).")
    d.add_argument("--mask", required=True, help="Ikili bolge maskesi PNG (sifir-disi = dahil).")
    d.add_argument("--model", required=True, help="TIA Toolbox model adi (or. KongNet_Det_MIDOG_1).")
    d.add_argument("--engine", default="nucleus_detector", choices=list(ENGINES),
                   help="TIA Toolbox motoru (varsayilan: nucleus_detector).")
    d.add_argument("--out", default="", help="Cikti GeoJSON yolu (bos = motorun yazdigi).")
    d.add_argument("--save-dir", required=True, help="Motor cikti dizini.")
    d.add_argument("--device", default="cuda", help="cuda ya da cpu (varsayilan: cuda).")
    d.add_argument("--batch-size", type=int, default=8)
    d.add_argument("--classes", default="", help="Virgulle ayrilmis sinif adlari (nucleus_detector).")
    d.add_argument("--threshold", type=float, default=-1.0,
                   help="Model 'threshold_abs' esigini geçersiz kıl (0-1; dusuk = daha cok tespit). "
                        "-1 = modelin varsayilanini kullan (KongNet: 0.99, cok tutucu).")
    d.set_defaults(func=cmd_detect)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    apply_tiatoolbox_home()   # engine olusturmadan ONCE model-cache kokunu ayarla
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
