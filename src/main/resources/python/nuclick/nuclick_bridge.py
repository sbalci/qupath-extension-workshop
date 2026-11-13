#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
nuclick_bridge.py — QuPath atolyesi icin NuClick (tikla -> cekirdek siniri) koprusu
===================================================================================

NE YAPAR:
    Patologun QuPath'te tikladigi NOKTA isaretlerini (Points anotasyonu) alir ve
    her nokta icin o cekirdegin PIKSEL DUZEYINDE sinirini uretir. QuPath sihirbazi
    (yardimci-nuclick-sihirbaz.groovy) bu betigi ProcessBuilder ile cagirir; veri
    alisverisi diskteki PNG karolari + JSON dosyalari uzerinden yapilir.

    Otomatik segmentasyondan (Cellpose / StarDist / InstanSeg) FARKI: burada
    TEK bir cekirdek hedeflenir — kullanici hangisini istediğini tiklayarak
    soyler. Elle cizime gore hizli, otomatige gore seceicidir.

ONEMLI:
    * Bu betik WSI dosyasi ACMAZ — yalnizca QuPath'in disa aktardigi 128x128
      PNG yamalarini okur. Olcek/koordinat sorumlulugu QuPath tarafindadir
      (SPIDER / Kaiko / HistoPLUS koprulerindeki tasarimin aynisi).
    * Upstream `nuclick_torch` deposu (CC BY-NC-SA 4.0) KULLANILMAZ, indirilmez,
      port EDILMEZ. Ag, TIA Toolbox'in BSD-3-Clause yeniden uygulamasidir
      (tiatoolbox.models.architecture.nuclick.NuClick).
    * AGIRLIK LISANSI: `nuclick_original-pannuke` PanNuke ile egitilmistir ve
      TIA Toolbox belgelerine gore PanNuke-egitimli modeller **CC BY-NC-SA 4.0**
      kapsamindadir — ticari kullanim yoktur. Atolye (egitim/arastirma) uyumludur.
    * Yalnizca arastirma/egitim amacli olcum uretir; klinik karar uretmez.

NEDEN YALNIZCA 'original' VARYANTI:
    Kayitta iki NuClick girdisi var ama ikisi AYNI kod yoluna oturmaz:
      * `nuclick_original-pannuke` -> sinif `nuclick.NuClick`. Sigmoid + NuClick
        postproc. Upstream BIRIM TESTI bu yolu dogrular. <- kullandigimiz
      * `nuclick_light-pannuke`    -> sinif `unet.UNetModel`. Onun `infer_batch`i
        girdiyi NHWC bekler, `softmax(dim=1)` uygular, x2 interpolasyon + merkez
        kirpma yapar; `postproc`u argmax'tir. Kayit bu model icin
        `num_output_channels: 1` der — tek kanalda softmax SABIT 1 uretir, yani
        yol dejenere olur. Hicbir upstream test bu varyanti kapsamaz.
    Bu yuzden 'light' (~5 MB, cazip) BILEREK sunulmaz: sessizce yanlis calisirdi.

OLCEK:
    PanNuke ~0,25 um/px (40x) ile egitilmistir ve yama boyu 128x128'dir. Bu
    betik olcek DENETIMI YAPMAZ — dogru downsample'i QuPath sihirbazi slaytin
    kendi um/px degerinden turetir ve kalibrasyon yoksa/uzaksa UYARIR.

KULLANIM:
    python nuclick_bridge.py selftest [--device auto|cpu|cuda]
    python nuclick_bridge.py download [--overwrite]
    python nuclick_bridge.py predict --points points.json --patches-dir DIR
                                     --output-dir DIR [--device auto]
                                     [--batch-size 16] [--thresh 0.33]
                                     [--min-size 10] [--min-hole-size 30]

JSON SOZLESMESI:
    points.json:
      {"tile_size": 128,
       "items": [{"id": "p000001",
                  "patch_file": "p000001.png",
                  "click":  [x, y],          # YAMA-YEREL piksel (x=sutun, y=satir)
                  "others": [[x, y], ...]}]} # ayni yamaya dusen DIGER tiklamalar

    output-dir/predictions.json:
      {"tile_size": 128,
       "items": [{"id": "p000001", "ok": true,
                  "contour": [[x, y], ...],  # YAMA-YEREL; slayt koordinatina
                                             # donusumu QuPath yapar
                  "area_px": 137,
                  "click_inside": true,
                  "reconstructed": true}
                 | {"id": "...", "ok": false, "reason": "..."}]}

REFERANS:
    Koohbanani NA, Jahanifar M, Tajadin NZ, Rajpoot N. "NuClick: A Deep Learning
    Framework for Interactive Segmentation of Microscopic Images."
    Medical Image Analysis 65 (2020) 101771. arXiv:2005.14511.
    Tasiyici: TIA Toolbox (BSD-3-Clause) — https://github.com/TissueImageAnalytics/tiatoolbox
    Kanonik kullanim: tiatoolbox tests/models/test_arch_nuclick.py
"""

import argparse
import json
import os
import sys

MODEL_NAME = "nuclick_original-pannuke"
TILE_SIZE = 128            # PanNuke/NuClick egitim yama boyu — DEGISTIRMEYIN
IN_CHANNELS = 5            # RGB + inclusion + exclusion
OUT_CHANNELS = 1


def log(msg):
    """Satir-tamponlu yaz, boylece QuPath log alani canli akar."""
    print(msg, flush=True)


def die(msg, code=2):
    log("HATA: %s" % msg)
    sys.exit(code)


# --------------------------------------------------------------------------- #
# Ortam / model
# --------------------------------------------------------------------------- #
def resolve_device(name):
    """'auto'/'cpu'/'cuda' -> torch device dizesi."""
    import torch
    if name == "cpu":
        return "cpu"
    if name == "cuda":
        if not torch.cuda.is_available():
            log("UYARI: --device cuda istendi ama CUDA yok; CPU'ya dusuluyor.")
            return "cpu"
        return "cuda"
    return "cuda" if torch.cuda.is_available() else "cpu"


def weights_path(overwrite=False):
    """Agirliklari TIATOOLBOX_HOME altina indir/bul; yerel yolu dondur."""
    from tiatoolbox.models.architecture import fetch_pretrained_weights
    return fetch_pretrained_weights(MODEL_NAME, overwrite=bool(overwrite))


def load_model(device):
    """NuClick agini kur ve on-egitilmis agirliklari yukle."""
    import torch
    from tiatoolbox.models import NuClick

    wp = weights_path()
    log("Agirlik: %s" % wp)
    model = NuClick(num_input_channels=IN_CHANNELS, num_output_channels=OUT_CHANNELS)
    # weights_only=True: dosya duz bir state_dict'tir; keyfi pickle calistirmayiz.
    # (Upstream testi bunu belirtmeden yukler; biz guvenli tarafi seciyoruz.)
    try:
        state = torch.load(wp, map_location="cpu", weights_only=True)
    except Exception as exc:
        die("agirlik guvenli modda (weights_only=True) okunamadi: %s: %s\n"
            "     Iki olasilik var:\n"
            "       1) Indirme bozuk  -> `download --overwrite` ile yeniden cekin.\n"
            "       2) Dosya duz bir state_dict DEGIL -> yeniden indirmek bunu COZMEZ;\n"
            "          upstream agirlik bicimi degismis olabilir, kopruyu guncelleyin.\n"
            "     (Not: upstream birim testi bare torch.load kullanir; biz bilerek\n"
            "      weights_only=True ile yukleyerek keyfi pickle calistirmiyoruz.)"
            % (type(exc).__name__, exc))
    model.load_state_dict(state)
    model.to(device)
    model.eval()
    return model


# --------------------------------------------------------------------------- #
# Girdi hazirligi
# --------------------------------------------------------------------------- #
def build_batch_item(png_path, click, others):
    """
    Tek bir yama icin (5, 128, 128) float32 dizi + inclusion haritasi uret.

    Kanonik kodlama (tiatoolbox tests/models/test_arch_nuclick.py):
      * inclusion / exclusion haritalari TEK PIKSELDIR (disk/Gauss DEGIL).
      * indisleme [satir, sutun] = [y, x] sirasindadir.
      * RGB 0-1'e bolunur ve CHW'ye cevrilir; kanal sirasi RGB, incl, excl.
    """
    import numpy as np
    from PIL import Image

    img = Image.open(png_path).convert("RGB")
    if img.size != (TILE_SIZE, TILE_SIZE):
        raise ValueError("yama %dx%d degil: %s (%dx%d)"
                         % (TILE_SIZE, TILE_SIZE, os.path.basename(png_path),
                            img.size[0], img.size[1]))
    patch = np.asarray(img, dtype=np.float32) / 255.0        # HWC
    patch = np.moveaxis(patch, -1, 0)                        # CHW

    incl = np.zeros((TILE_SIZE, TILE_SIZE), dtype=np.float32)
    excl = np.zeros((TILE_SIZE, TILE_SIZE), dtype=np.float32)

    cx, cy = int(round(click[0])), int(round(click[1]))
    if not (0 <= cx < TILE_SIZE and 0 <= cy < TILE_SIZE):
        raise ValueError("tiklama yama disinda: (%d, %d)" % (cx, cy))
    incl[cy, cx] = 1.0                                       # [y, x]

    for ox, oy in (others or []):
        ox, oy = int(round(ox)), int(round(oy))
        if 0 <= ox < TILE_SIZE and 0 <= oy < TILE_SIZE and not (ox == cx and oy == cy):
            excl[oy, ox] = 1.0                               # [y, x]

    batch = np.concatenate((patch, incl[np.newaxis, ...], excl[np.newaxis, ...]), axis=0)
    return batch.astype(np.float32), incl


# --------------------------------------------------------------------------- #
# Cikti: maske -> kontur
# --------------------------------------------------------------------------- #
def mask_to_contour(mask, click):
    """
    Ikili maskeden, TIKLAMAYI ICEREN bilesenin konturunu cikar.

    'En buyuk bileseni al' YERINE tiklamayi iceren bileseni aliyoruz: NuClick tek
    bir hedef cekirdek icin egitilmistir; esikleme sonrasi komsu cekirdekten artik
    bir parca kalirsa 'en buyuk' yanlis nesneyi secebilir.

    Doner: (kontur, click_inside) — kontur [[x, y], ...] ya da None.
    """
    import numpy as np
    from skimage.measure import label, find_contours

    if not mask.any():
        return None, False

    lab = label(mask)
    cx, cy = int(round(click[0])), int(round(click[1]))
    comp_id = int(lab[cy, cx])
    click_inside = comp_id > 0

    if not click_inside:
        # Tiklama maskenin disinda kaldi (reconstruction da basarisiz olmustur).
        # En buyuk bilesene duseriz ama bunu cagirana BILDIRIRIZ.
        counts = np.bincount(lab.ravel())
        if len(counts) < 2:
            return None, False
        comp_id = int(counts[1:].argmax()) + 1

    comp = (lab == comp_id)
    contours = find_contours(comp.astype(np.float32), 0.5)
    if not contours:
        return None, click_inside
    # find_contours (satir, sutun) dondurur -> (x, y) = (sutun, satir)
    best = max(contours, key=len)
    poly = [[float(c), float(r)] for r, c in best]
    # find_contours KAPALI bir halka dondurur (ilk nokta == son nokta). QuPath
    # poligonu zaten kapatir; tekrarlanan koseyi birakirsak cift vertex olusur.
    if len(poly) >= 2 and poly[0][0] == poly[-1][0] and poly[0][1] == poly[-1][1]:
        poly = poly[:-1]
    if len(poly) < 3:
        return None, click_inside
    return poly, click_inside


# --------------------------------------------------------------------------- #
# Komutlar
# --------------------------------------------------------------------------- #
def cmd_selftest(args):
    log("NuClick koprusu — ortam denetimi")
    try:
        import torch
        log("  torch            : %s" % torch.__version__)
        log("  CUDA kullanilabilir: %s" % torch.cuda.is_available())
    except Exception as exc:
        die("torch bulunamadi: %s" % exc)
    try:
        import tiatoolbox
        log("  tiatoolbox       : %s" % tiatoolbox.__version__)
    except Exception as exc:
        die("tiatoolbox bulunamadi: %s" % exc)
    try:
        from tiatoolbox.models import NuClick  # noqa: F401
        log("  NuClick mimarisi : bulundu")
    except Exception as exc:
        die("tiatoolbox surumunde NuClick yok (>=2.1.2 gerekir): %s" % exc)
    try:
        import skimage
        from skimage.morphology import remove_small_objects
        import inspect
        sig = inspect.signature(remove_small_objects)
        has_max = "max_size" in sig.parameters
        log("  scikit-image     : %s (max_size destegi: %s)" % (skimage.__version__, has_max))
        if not has_max:
            die("scikit-image >= 0.26 gerekir — TIA Toolbox'in NuClick.postproc'u\n"
                "     remove_small_objects(..., max_size=...) cagirir; eski surumlerde\n"
                "     bu TypeError verir. Ortami guncelleyin.")
    except SystemExit:
        raise
    except Exception as exc:
        die("scikit-image denetlenemedi: %s" % exc)
    dev = resolve_device(args.device)
    log("  secilen aygit    : %s" % dev)
    log("Ortam TAMAM.")


def cmd_download(args):
    log("Agirlik indiriliyor/denetleniyor: %s" % MODEL_NAME)
    try:
        wp = weights_path(overwrite=args.overwrite)
    except Exception as exc:
        die("agirlik indirilemedi: %s: %s" % (type(exc).__name__, exc))
    try:
        size_mb = os.path.getsize(wp) / (1024.0 * 1024.0)
        log("Hazir: %s (%.1f MB)" % (wp, size_mb))
    except OSError:
        log("Hazir: %s" % wp)


def cmd_predict(args):
    import numpy as np
    import torch

    with open(args.points, "r", encoding="utf-8") as fh:
        spec = json.load(fh)
    items = spec.get("items") or []
    if not items:
        die("points.json bos — hic nokta isareti gonderilmedi.")
    if int(spec.get("tile_size", TILE_SIZE)) != TILE_SIZE:
        die("tile_size %d olmali (gelen: %s)" % (TILE_SIZE, spec.get("tile_size")))

    device = resolve_device(args.device)
    log("Aygit: %s | nokta sayisi: %d | yigin: %d" % (device, len(items), args.batch_size))
    model = load_model(device)

    results = []
    prepared = []   # (item, batch_array, inclusion_map)
    failed_prep = 0

    for it in items:
        png = os.path.join(args.patches_dir, it["patch_file"])
        if not os.path.isfile(png):
            results.append({"id": it["id"], "ok": False, "reason": "yama dosyasi yok"})
            failed_prep += 1
            continue
        try:
            arr, incl = build_batch_item(png, it["click"], it.get("others"))
        except Exception as exc:
            results.append({"id": it["id"], "ok": False, "reason": str(exc)})
            failed_prep += 1
            continue
        prepared.append((it, arr, incl))

    if failed_prep:
        log("UYARI: %d nokta hazirlanamadi (ayrinti predictions.json icinde)." % failed_prep)

    done = 0
    bs = max(1, int(args.batch_size))
    for start in range(0, len(prepared), bs):
        chunk = prepared[start:start + bs]
        batch = np.stack([c[1] for c in chunk], axis=0)          # (N,5,128,128)
        tensor = torch.from_numpy(batch)
        try:
            preds = model.infer_batch(model, tensor, device=device)   # (N,128,128)
        except Exception as exc:
            die("cikarim basarisiz: %s: %s" % (type(exc).__name__, exc))

        for k, (it, _arr, incl) in enumerate(chunk):
            # postproc'u TEK TEK cagiriyoruz. Nedeni: upstream postproc,
            # remove_small_objects/remove_small_holes'u (N,h,w) dizisinin TAMAMINA
            # uygular; 3B baglanti nedeniyle ardisik yamalar birbirine baglanabilir
            # ve kucuk-nesne temizligi yamalar arasi sizabilir. N=1 ile bu tamamen
            # ortadan kalkar; cikarim yine de yiginlidir.
            one = preds[k][np.newaxis, ...]
            nuc = incl[np.newaxis, ...]
            masks = model.postproc(
                one,
                thresh=float(args.thresh),
                min_size=int(args.min_size),
                min_hole_size=int(args.min_hole_size),
                nuc_points=nuc,
                do_reconstruction=True,
            )
            mask = np.asarray(masks[0]).astype(bool)
            contour, click_inside = mask_to_contour(mask, it["click"])
            if contour is None:
                results.append({"id": it["id"], "ok": False,
                                "reason": "bos maske (esik yuksek olabilir)"})
            else:
                results.append({"id": it["id"], "ok": True,
                                "contour": contour,
                                "area_px": int(mask.sum()),
                                "click_inside": bool(click_inside),
                                "reconstructed": bool(click_inside)})
            done += 1
        log("  ... %d / %d" % (done, len(prepared)))

    os.makedirs(args.output_dir, exist_ok=True)
    out = os.path.join(args.output_dir, "predictions.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump({"tile_size": TILE_SIZE, "model": MODEL_NAME, "items": results},
                  fh, ensure_ascii=False)

    ok = sum(1 for r in results if r.get("ok"))
    drifted = sum(1 for r in results if r.get("ok") and not r.get("click_inside"))
    log("Bitti: %d / %d cekirdek sinirlandirildi." % (ok, len(results)))
    if drifted:
        log("UYARI: %d cekirdekte tiklama maskenin disinda kaldi — bu konturlar"
            " suphelidir, gozle dogrulayin." % drifted)
    log("Cikti: %s" % out)


# --------------------------------------------------------------------------- #
def main(argv=None):
    ap = argparse.ArgumentParser(
        description="NuClick koprusu — QuPath nokta isaretlerinden cekirdek siniri "
                    "(TIA Toolbox tasiyicisi). Yalnizca arastirma/egitim amaclidir.")
    sub = ap.add_subparsers(dest="cmd", required=True)

    st = sub.add_parser("selftest", help="Ortami (torch/tiatoolbox/skimage) denetle.")
    st.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda"])

    dl = sub.add_parser("download", help="Model agirliklarini indir (TIATOOLBOX_HOME).")
    dl.add_argument("--overwrite", action="store_true")

    pr = sub.add_parser("predict", help="Nokta isaretlerinden cekirdek konturu uret.")
    pr.add_argument("--points", required=True, help="points.json yolu.")
    pr.add_argument("--patches-dir", required=True, help="128x128 PNG yamalarin klasoru.")
    pr.add_argument("--output-dir", required=True, help="predictions.json yazilacak klasor.")
    pr.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda"])
    pr.add_argument("--batch-size", type=int, default=16)
    pr.add_argument("--thresh", type=float, default=0.33,
                    help="Olasilik esigi (upstream varsayilani 0,33).")
    pr.add_argument("--min-size", type=int, default=10)
    pr.add_argument("--min-hole-size", type=int, default=30)

    args = ap.parse_args(argv)
    if args.cmd == "selftest":
        cmd_selftest(args)
    elif args.cmd == "download":
        cmd_download(args)
    elif args.cmd == "predict":
        cmd_predict(args)


if __name__ == "__main__":
    main()
