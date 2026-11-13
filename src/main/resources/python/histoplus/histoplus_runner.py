#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
histoplus_runner.py - QuPath atolyesi icin HistoPLUS (H&E hucre segmentasyonu + siniflama) koprusu
====================================================================================================

NE YAPAR:
    HistoPLUS'ı (Adjadj, Bannier ve ark., Owkin; J Pathol Inform 2026;22:100696, doi:10.1016/j.jpi.2026.100696;
    on baski arXiv:2508.09926) QuPath'te SECILEN ALANDA calistirir:
    cekirdekleri segmente eder ve 13 (+1) hucre tipinden birine atar. Slayt dosyasini OpenSlide ile
    okur; um/px degeri DOSYADAN alinir (QuPath'te elle girilen kalibrasyon aktarilamaz).

NASIL (ve neden boyle):
    Upstream'in `extract()` fonksiyonu Windows'ta calismaz (iki hata, ikisi de bu makinede
    yeniden uretildi): (1) ConcurrentModelInference modeli spawn ile pickle etmeye calisir ->
    "cannot pickle 'weakref.ReferenceType'" (upstream issue #29) ve yarim kalan alt surec yuzunden
    kilitlenme; (2) son-isleme, kendi np.load'unun hala actigi .npz'yi siler -> WinError 32.
    Bu kopru upstream'in KENDI acik yapi taslarini (CellViT, CellViTSegmentor, DeepZoom karolama,
    get_tile_dataloader, segmentor.forward, CellViTPostprocessor.postprocess, fix_border_effects,
    SlideSegmentationData) extract_cell_segmentation_masks ile AYNI SIRADA cagirir; yalniz iki
    Windows-dusmani tesisat adimini (surecler arasi kuyruk + gecici dosyalar) bellek icinde yapar.
    Upstream kaynak kodu DEGISTIRILMEZ, KOPYALANMAZ, calisma aninda yamanmaz.
    Doku tespiti (detect_tissue_on_wsi) KULLANILMAZ: karolar, secili alanla kesisen 224 px izgara
    karolaridir (upstream #26 yeniden-sekillendirme hatasi ve um/px yoksa sessizce 0.25 varsayma
    davranisi boylece devre disi kalir).

LISANS SINIRI:
    HistoPLUS kodu ve agirliklari CC BY-NC-ND 4.0 (depo LICENSE.md + Hugging Face kapisi; makalenin
    "CC-BY-4.0" ifadesi depoyla celisir - gecerli olan depo/HF lisansidir). Agirliklar kapili
    (Owkin-Bioptimus/histoplus): yalniz ticari olmayan akademik arastirma, modeli dagitmama, her
    kullanici KENDI hesabiyla kaydolur. Atolye eklentisi Apache-2.0: HistoPLUS kodu ve agirliklari bu
    depoda/JAR'da PAKETLENMEZ; kod kullanicinin `histoplus` ortamina sabit commit'ten kurulur.

NE URETIR (ve ne URETMEZ):
    Cekirdek konturu + model sinifi + "sinif oy orani" (cekirdek piksellerinin cogunluk sinifina oy
    veren kesri - OLASILIK DEGILDIR). Klinik yorum URETMEZ.

KULLANIM:
    python histoplus_runner.py selftest
    python histoplus_runner.py precheck --slide SLAYT.svs
    python histoplus_runner.py verify --dir MODEL_KLASORU
    python histoplus_runner.py download --dir MODEL_KLASORU        (HF tokeni: HF_TOKEN / hf auth login)
    python histoplus_runner.py detect --slide SLAYT.svs --roi roi.geojson --model-dir MODEL_KLASORU \\
        --out CALISMA_KLASORU [--device auto|cuda|cpu] [--batch-size 4] [--pp-threads 2]

CIKIS KODLARI:
    0 tamam (0 hucre dahil) | 2 hatali arguman/eksik dosya | 3 import/ortam hatasi |
    5 slayt okunamadi / um/px yok / uygun cozunurluk yok | 6 HistoPLUS basarisiz | 7 indirme/dogrulama hatasi

KAYNAK:
    Kod: https://github.com/owkin/histoplus (commit 576b94e5)
    Agirlik: https://huggingface.co/Owkin-Bioptimus/histoplus (revizyon cde2eee8)
"""

import argparse
import hashlib
import json
import os
import sys
import time

HISTOPLUS_COMMIT = "576b94e528791c9f22c4d755bee01ec9a5743558"
HF_REPO = "Owkin-Bioptimus/histoplus"
HF_REVISION = "cde2eee81af9e39b03802fc33d4f284733b5ee5e"
# model anahtari -> (dosya adi, SHA-256, boyut bayt, modelin um/px degeri). HF LFS degerleri (HF_REVISION).
WEIGHTS = {
    "40x": ("histoplus_cellvit_segmentor_40x.pt",
            "9cccb30d3d73535535bcec0e6d7f322a8ee1be1584bde13a558cf0a5d352ea5c", 285687206, 0.25),
    "20x": ("histoplus_cellvit_segmentor_20x.pt",
            "aa19dc31c109c0a4fcf7bd773559c45480cee792a331ae0a9ffe42eb08267bfc", 284507558, 0.5),
}
MODEL_BY_MPP = {0.25: "40x", 0.5: "20x"}

# Upstream CLI ile ayni izgara: 224 px karolar, 0.5 um/px DeepZoom seviyesinde (TilingTool kurali).
GRID_TILE = 224
GRID_MPP = 0.5
# Upstream get_tiling_slide_level varsayilan toleransi (istenen ile bulunan um/px arasi goreli fark).
MPP_RTOL = 0.2

EXIT_OK, EXIT_ARGS, EXIT_IMPORT, EXIT_SLIDE, EXIT_HISTOPLUS, EXIT_DOWNLOAD = 0, 2, 3, 5, 6, 7

# (import adi, kurulum ipucu)
REQUIRED_MODULES = [
    ("torch", "torch==2.7.1 (--torch-backend=auto)"), ("torchvision", "torchvision==0.22.1"),
    ("xformers", "xformers==0.0.31.post1 (torch ile ayni surum cifti)"), ("timm", "timm==1.0.8"),
    ("numpy", "numpy==2.2.6"), ("pandas", "pandas==2.3.1"), ("cv2", "opencv-python-headless==4.12.0.88"),
    ("skimage", "scikit-image==0.25.2"), ("shapely", "shapely==2.1.1"),
    ("openslide", "openslide-python + openslide-bin"), ("huggingface_hub", "huggingface-hub==0.33.4"),
    ("histoplus", "histoplus @ GitHub zip (sabit commit %s)" % HISTOPLUS_COMMIT[:7]),
]

HF_STEP0 = (
    "Adim 0 (bir kez, her kullanici KENDISI yapar): 1) huggingface.co hesabi acin  "
    "2) https://huggingface.co/%s sayfasinda formu doldurup kosullari kabul edin (DIKKAT: 'owkin/histoplus' "
    "degil, 'Owkin-Bioptimus/histoplus')  3) Ayarlar -> Access Tokens'tan bir 'read' token olusturun  "
    "4) 'hf auth login' calistirin ya da tokeni sihirbazin alanina yapistirin. Alternatif: iki .pt dosyasini "
    "iceren yerel bir klasor - kosullari yine de SIZ kabul etmis olmalisiniz (model dagitilamaz)." % HF_REPO
)


def log(msg):
    """Satir-tamponlu yaz; QuPath gunluk alani canli akar."""
    print(msg, flush=True)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def weight_path(model_dir, key):
    return os.path.join(model_dir, WEIGHTS[key][0])


def check_weight(path, key):
    """(ok, neden). Boyut once (hizli), sonra SHA-256."""
    name, sha, size, _mpp = WEIGHTS[key]
    if not os.path.isfile(path):
        return False, "missing"
    if os.path.getsize(path) != size:
        return False, "size"
    if sha256_of(path) != sha:
        return False, "sha"
    return True, "ok"


# ----------------------------------------------------------------------------
# Saf yardimcilar (birim testli; torch/openslide gerektirmez)
# ----------------------------------------------------------------------------
def choose_model(level_mpps):
    """Upstream CLI kuralinin aynisi: 0.25 um/px'e %20 icinde bir seviye varsa 40x, yoksa 0.5 icin 20x.

    level_mpps: DeepZoom seviyelerinin ham um/px degerleri. Donus: (model_anahtari, secilen_ham_mpp) ya da
    (None, None) - hicbiri tolerans icinde degilse (or. 10x taramalar).
    """
    for target in (0.25, 0.5):
        best = min(level_mpps, key=lambda m: abs(m - target))
        if abs(best - target) / target <= MPP_RTOL:
            return MODEL_BY_MPP[target], best
    return None, None


def level_mpp_map(mpp_max, level_count):
    """Upstream get_level_raw_mpp_mapping ile ayni: seviye i -> mpp_max * 2^(n-1-i)."""
    return {i: mpp_max * (2 ** (level_count - 1 - i)) for i in range(level_count)}


def grid_tiles_for_roi(roi_level0, level_tiles, downsample, tile=GRID_TILE):
    """ROI (seviye-0 shapely geometrisi) ile kesisen izgara karolarinin (sutun, satir) listesi.

    Karo (c, r), izgara seviyesinde [c*tile, (c+1)*tile) araligini, seviye-0'da ise downsample katini kaplar.
    """
    from shapely.geometry import box
    from shapely.prepared import prep
    span = tile * downsample
    minx, miny, maxx, maxy = roi_level0.bounds
    cols, rows = level_tiles
    c0, c1 = max(0, int(minx // span)), min(cols - 1, int(maxx // span))
    r0, r1 = max(0, int(miny // span)), min(rows - 1, int(maxy // span))
    pg = prep(roi_level0)
    out = []
    for r in range(r0, r1 + 1):
        for c in range(c0, c1 + 1):
            if pg.intersects(box(c * span, r * span, (c + 1) * span, (r + 1) * span)):
                out.append((c, r))
    return out


def tile_local_to_qupath(tile_x, tile_y, pts, downsample, bounds_x=0.0, bounds_y=0.0, tile=GRID_TILE):
    """Upstream cikti koordinati -> QuPath (tam cozunurluk) koordinati.

    Upstream: hucre konturlari, 0.5 um/px izgara seviyesindeki 224 px karonun YEREL koordinatlaridir
    (x,y karo adresidir). Seviye-0 = (x*224 + yerel) * downsample; DeepZoom limit_bounds=False oldugu
    icin seviye-0 ham dosya uzayidir. QuPath (OpenSlide) goruntuyu sinir ofsetine gore kirpar -> ofset
    cikarilir.
    """
    ox, oy = tile_x * tile, tile_y * tile
    return [[(ox + px) * downsample - bounds_x, (oy + py) * downsample - bounds_y] for px, py in pts]


def read_roi(path, bounds_x=0.0, bounds_y=0.0):
    """QuPath koordinatlarinda (Multi)Polygon GeoJSON -> seviye-0 shapely geometrisi (+ sinir ofseti)."""
    from shapely.affinity import translate
    from shapely.geometry import shape
    from shapely.ops import unary_union
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    if isinstance(data, dict) and data.get("type") == "FeatureCollection":
        geoms = [shape(ft["geometry"]) for ft in data.get("features", []) if ft.get("geometry")]
    elif isinstance(data, dict) and data.get("type") == "Feature":
        geoms = [shape(data["geometry"])]
    else:
        geoms = [shape(data)]
    geoms = [g for g in geoms if not g.is_empty]
    if not geoms:
        raise ValueError("ROI bos")
    geom = unary_union(geoms)
    if not geom.is_valid:
        geom = geom.buffer(0)
    if geom.is_empty or geom.area <= 0:
        raise ValueError("ROI alani sifir")
    return translate(geom, xoff=bounds_x, yoff=bounds_y)


def cells_to_geojson(tiles, downsample, bounds_x=0.0, bounds_y=0.0):
    """Upstream SlideSegmentationData.to_dict()['cell_masks'] -> QuPath GeoJSON FeatureCollection.

    Her hucre bir Polygon; classification.name upstream etiketi (OLDUGU GIBI);
    measurements: 'HistoPLUS: sinif oy orani' (olasilik DEGIL).
    """
    feats = []
    for t in tiles:
        tx, ty = int(t["x"]), int(t["y"])
        for m in t.get("masks", []):
            ring = tile_local_to_qupath(tx, ty, m["coordinates"], downsample, bounds_x, bounds_y)
            if len(ring) < 3:
                continue
            if ring[0] != ring[-1]:
                ring.append(list(ring[0]))
            feats.append({
                "type": "Feature",
                "geometry": {"type": "Polygon", "coordinates": [ring]},
                "properties": {
                    "objectType": "detection",
                    "classification": {"name": m["cell_type"]},
                    "measurements": {"HistoPLUS: sinif oy orani": float(m["confidence"])},
                },
            })
    return {"type": "FeatureCollection", "features": feats}


def resolve_hf_token():
    """Sira: env HF_TOKEN -> $HF_HOME/token -> ~/.cache/huggingface/token. Token DEGERI loglanmaz.

    Sihirbaz HF_HOME'u veri kokune yonlendirir; 'hf auth login' ise varsayilan konuma yazar - bu yuzden
    varsayilan konum da okunur.
    """
    tok = os.environ.get("HF_TOKEN")
    if tok and tok.strip():
        return tok.strip(), "env HF_TOKEN"
    candidates = []
    if os.environ.get("HF_HOME"):
        candidates.append((os.path.join(os.environ["HF_HOME"], "token"), "$HF_HOME/token"))
    candidates.append((os.path.join(os.path.expanduser("~"), ".cache", "huggingface", "token"),
                       "~/.cache/huggingface/token"))
    for path, label in candidates:
        try:
            with open(path, "r", encoding="utf-8") as f:
                tok = f.read().strip()
            if tok:
                return tok, label
        except OSError:
            continue
    return None, None


def explain_hf_error(exc):
    name = type(exc).__name__
    status = getattr(getattr(exc, "response", None), "status_code", None)
    text = str(exc).lower()
    if name == "GatedRepoError" or status == 403 or "gated" in text or "access to model" in text:
        return "HATA: model kapili (gated) - bu hesap icin erisim onayi yok.\n" + HF_STEP0
    if status == 401 or "401" in text or "unauthorized" in text or ("invalid" in text and "token" in text):
        return "HATA: yetkisiz (401) - gecerli bir Hugging Face 'read' tokeni yok.\n" + HF_STEP0
    if name == "RepositoryNotFoundError" or status == 404:
        return "HATA: depo/dosya bulunamadi (%s @ %s)." % (HF_REPO, HF_REVISION[:8])
    return "HATA: indirilemedi: %s: %s" % (name, exc)


def installed_commit():
    """PEP 610 direct_url.json'dan kurulu HistoPLUS'un kaynak URL'si ve commit'i (varsa)."""
    try:
        from importlib.metadata import distribution
        raw = distribution("histoplus").read_text("direct_url.json")
        url = json.loads(raw).get("url", "") if raw else ""
    except Exception:  # noqa: BLE001
        return None, None
    commit = HISTOPLUS_COMMIT if HISTOPLUS_COMMIT in url else None
    return url, commit


# ----------------------------------------------------------------------------
# Alt komutlar
# ----------------------------------------------------------------------------
def pipeline_error():
    """Kopru'nun cagirdigi upstream yapi taslari ice aktarilabiliyor mu? Degilse hata metni."""
    try:
        from histoplus.extract.segmentation.dataloader import get_tile_dataloader  # noqa: F401
        from histoplus.extract.utils import get_tile_coordinates_and_deepzoom_for_segmentor  # noqa: F401
        from histoplus.helpers.border_effects import fix_border_effects  # noqa: F401
        from histoplus.helpers.constants import INFERENCE_TILE_OVERLAP, INFERENCE_TILE_SIZE  # noqa: F401
        from histoplus.helpers.data.slide_segmentation_data import SlideSegmentationData  # noqa: F401
        from histoplus.helpers.nn.cellvit import CellViT  # noqa: F401
        from histoplus.helpers.segmentor.cellvit import MODEL_FUNCS_FOR_VERSION, CellViTSegmentor  # noqa: F401
        from histoplus.helpers.tiling.optimal_mpp import get_slide_resolution, get_tiling_slide_level  # noqa: F401
        return None
    except Exception as exc:  # noqa: BLE001
        return "%s: %s" % (type(exc).__name__, exc)


def cmd_selftest(args):
    log("== histoplus_runner selftest ==")
    log("OK    %-20s %s" % ("python", sys.version.split()[0]))
    ok = True
    for mod, hint in REQUIRED_MODULES:
        try:
            m = __import__(mod)
            log("OK    %-20s %s" % (mod, getattr(m, "__version__", "?")))
        except Exception as exc:  # noqa: BLE001
            ok = False
            log("EKSIK %-20s %s  (kurulum: %s)" % (mod, exc, hint))
    err = pipeline_error()
    if err is None:
        log("PIPELINE ok=1")
    else:
        ok = False
        log("EKSIK upstream yapi taslari: %s" % err)
        log("PIPELINE ok=0")
    url, commit = installed_commit()
    if commit:
        log("HISTOPLUS commit=%s ok=1" % commit[:7])
    else:
        log("UYARI: kurulu HistoPLUS sabit commit'ten (%s) degil gibi: %s" % (HISTOPLUS_COMMIT[:7], url or "bilinmiyor"))
        log("HISTOPLUS commit=? ok=0")
    cuda = False
    try:
        import torch
        cuda = bool(torch.cuda.is_available())
        if cuda:
            log("OK    %-20s %s" % ("CUDA", torch.cuda.get_device_name(0)))
        else:
            log("BILGI CUDA yok - HistoPLUS CPU'da calisir (cok yavas; kucuk alan secin).")
    except Exception:  # noqa: BLE001
        pass
    log("CUDA cuda=%d" % (1 if cuda else 0))
    log("SELFTEST OK" if ok else "SELFTEST BASARISIZ - ortam yoneticisinden 'histoplus' ortamini yeniden kurun")
    return EXIT_OK if ok else EXIT_IMPORT


def open_and_probe(slide_path):
    """Slayti ac, um/px + model secimi + sinirlari cikar. Donus: (slide, bilgi_dict) ya da (None, hata_dict)."""
    import openslide
    from openslide.deepzoom import DeepZoomGenerator
    from histoplus.helpers.tiling.optimal_mpp import get_slide_resolution
    try:
        slide = openslide.OpenSlide(slide_path)
    except Exception as exc:  # noqa: BLE001
        return None, {"reason": "unreadable", "text": "%s: %s" % (type(exc).__name__, exc)}
    props = slide.properties
    try:
        mpp = float(get_slide_resolution(slide))  # upstream okuyucusu: openslide.mpp-x ya da tiff.XResolution
    except (KeyError, ValueError):
        mpp = None
    if not mpp or mpp <= 0:
        slide.close()
        return None, {"reason": "no_mpp", "text": "slayt dosyasinda um/px yok"}
    mpp_y = props.get("openslide.mpp-y")
    dz = DeepZoomGenerator(slide, tile_size=GRID_TILE, overlap=0, limit_bounds=False)
    levels = level_mpp_map(mpp, dz.level_count)
    model, level_mpp = choose_model(list(levels.values()))
    info = {
        "width": slide.dimensions[0], "height": slide.dimensions[1],
        "bounds_x": float(props.get("openslide.bounds-x") or 0), "bounds_y": float(props.get("openslide.bounds-y") or 0),
        "mpp_x": mpp, "mpp_y": float(mpp_y) if mpp_y else mpp,
        "model": model, "level_mpp": level_mpp, "vendor": props.get("openslide.vendor") or "?",
    }
    if model is None:
        slide.close()
        return None, dict(info, reason="no_level",
                          text="slaytta 0.25 ya da 0.5 um/px'e %%%d icinde bir seviye yok (en ince: %.3f)" % (MPP_RTOL * 100, mpp))
    return slide, info


def cmd_precheck(args):
    if not os.path.isfile(args.slide):
        log("HATA: slayt dosyasi yok: %s" % args.slide)
        log("PRECHECK ok=0 reason=missing")
        return EXIT_SLIDE
    try:
        slide, info = open_and_probe(args.slide)
    except Exception as exc:  # noqa: BLE001
        log("HATA: on denetim basarisiz: %s: %s" % (type(exc).__name__, exc))
        return EXIT_IMPORT
    if slide is None:
        if info["reason"] == "unreadable":
            log("HATA: OpenSlide bu dosyayi acamiyor (%s). HistoPLUS slayti OpenSlide ile okur (or. CZI desteklenmez)." % info["text"])
        elif info["reason"] == "no_mpp":
            log("HATA: slayt dosyasinda um/px bilgisi yok (openslide.mpp-x / tiff.XResolution). Upstream bu durumda "
                "SESSIZCE 0.25 varsayar; kopru reddeder. QuPath'te girilen kalibrasyon aktarilamaz.")
        else:
            log("HATA: %s" % info["text"])
        for k in ("width", "height", "mpp_x"):
            if k in info:
                log("PRECHECK %s=%s" % (k, info[k]))
        log("PRECHECK ok=0 reason=%s" % info["reason"])
        return EXIT_SLIDE
    slide.close()
    log("PRECHECK width=%d height=%d" % (info["width"], info["height"]))
    log("PRECHECK bounds_x=%.0f bounds_y=%.0f" % (info["bounds_x"], info["bounds_y"]))
    log("PRECHECK mpp_x=%.6f mpp_y=%.6f" % (info["mpp_x"], info["mpp_y"]))
    log("PRECHECK model=%s level_mpp=%.6f model_mpp=%.2f" % (info["model"], info["level_mpp"], WEIGHTS[info["model"]][3]))
    log("PRECHECK vendor=%s" % info["vendor"].replace(" ", "_"))
    log("PRECHECK ok=1")
    return EXIT_OK


def cmd_verify(args):
    found = 0
    bad = 0
    for key, (name, _sha, size, mpp) in sorted(WEIGHTS.items()):
        path = weight_path(args.dir, key)
        if not os.path.isfile(path):
            log("YOK   %s (%s, %.2f um/px)" % (name, key, mpp))
            continue
        found += 1
        log("SHA-256 hesaplaniyor: %s (%.0f MB)" % (name, size / 1e6))
        ok, why = check_weight(path, key)
        if ok:
            log("VERIFY %s ok=1 path=%s" % (key, path))
        else:
            bad += 1
            log("HATA: %s dogrulanamadi (%s) - sabitlenmis surumle ayni dosya degil." % (name, why))
            log("VERIFY %s ok=0 reason=%s" % (key, why))
    if found == 0:
        log("HATA: klasorde HistoPLUS agirligi yok: %s (beklenen: %s)" % (args.dir, ", ".join(v[0] for v in WEIGHTS.values())))
        return EXIT_DOWNLOAD
    return EXIT_OK if bad == 0 else EXIT_DOWNLOAD


def cmd_download(args):
    os.makedirs(args.dir, exist_ok=True)
    keys = [args.model] if args.model else sorted(WEIGHTS)
    token, source = resolve_hf_token()
    log("Token: %s" % (("bulundu (%s)" % source) if token else "yok - kapili depo muhtemelen 401 verir"))
    for key in keys:
        name = WEIGHTS[key][0]
        target = weight_path(args.dir, key)
        if os.path.isfile(target) and check_weight(target, key)[0]:
            log("Zaten var ve dogrulandi: %s" % target)
            log("VERIFY %s ok=1 path=%s" % (key, target))
            continue
        log("Indiriliyor: %s/%s @ %s (~%.0f MB)" % (HF_REPO, name, HF_REVISION[:8], WEIGHTS[key][2] / 1e6))
        try:
            from huggingface_hub import hf_hub_download
            path = hf_hub_download(repo_id=HF_REPO, filename=name, revision=HF_REVISION,
                                   local_dir=args.dir, token=token)
        except Exception as exc:  # noqa: BLE001
            log(explain_hf_error(exc))
            return EXIT_DOWNLOAD
        ok, why = check_weight(path, key)
        if not ok:
            try:
                os.remove(path)
            except OSError:
                pass
            log("HATA: %s dogrulanamadi (%s) - dosya silindi." % (name, why))
            return EXIT_DOWNLOAD
        log("SHA-256 dogrulandi: %s" % name)
        log("VERIFY %s ok=1 path=%s" % (key, path))
    log("DONE download ok=1 dir=%s" % os.path.abspath(args.dir))
    return EXIT_OK


def build_segmentor(model_key, weights_file, device):
    """Upstream sinif oznitelikleriyle CellViT kur, dogrulanmis agirligi weights_only=True ile yukle."""
    import torch
    from histoplus.helpers.constants import INFERENCE_TILE_SIZE
    from histoplus.helpers.nn.cellvit import CellViT
    from histoplus.helpers.segmentor.cellvit import MODEL_FUNCS_FOR_VERSION, CellViTSegmentor
    spec = MODEL_FUNCS_FOR_VERSION[WEIGHTS[model_key][3]]
    model = CellViT(cell_type_mapping=spec.class_mapping, mpp=spec.mpp, output_layers=spec.output_layers,
                    train_image_size=spec.train_image_size, inference_image_size=INFERENCE_TILE_SIZE)
    state = torch.load(weights_file, map_location="cpu", weights_only=True)
    model.load_state_dict(state)
    model.eval()
    gpu = -1 if device == "cpu" else 0
    return CellViTSegmentor(model=model, segmentor_name=spec.name, mean=spec.mean, std=spec.std,
                            gpu=gpu, mixed_precision=True)


def explain_failure(exc_text):
    low = exc_text.lower()
    if "out of memory" in low:
        log("IPUCU: GPU bellegi yetmedi - Gelismis'te batch size'i (or. 2) dusurun ya da CPU secin.")
    elif "xformers" in low or "swiglu" in low:
        log("IPUCU: xformers torch surumuyle uyumsuz - ortam yoneticisinden 'histoplus' ortamini yeniden kurun.")
    elif "numpy" in low and ("initialize" in low or "_array_api" in low):
        log("IPUCU: numpy/torch uyumsuzlugu - 'histoplus' ortamini yeniden kurun (numpy 2.2.6 sabit).")
    elif "modulenotfounderror" in low or "importerror" in low:
        log("IPUCU: Python paketi eksik - ortam yoneticisinden 'histoplus' ortamini yeniden kurun.")


def run_detect(args):
    """Asil hat. Upstream yapi taslari extract_cell_segmentation_masks sirasiyla cagrilir."""
    import numpy as np
    import torch
    from concurrent.futures import ThreadPoolExecutor
    from openslide.deepzoom import DeepZoomGenerator
    from histoplus.extract.segmentation.dataloader import get_tile_dataloader
    from histoplus.extract.utils import get_tile_coordinates_and_deepzoom_for_segmentor
    from histoplus.helpers.border_effects import fix_border_effects
    from histoplus.helpers.constants import INFERENCE_TILE_OVERLAP
    from histoplus.helpers.data.slide_segmentation_data import SlideSegmentationData
    from histoplus.helpers.tiling.optimal_mpp import get_tiling_slide_level

    t_all = time.time()
    slide, info = open_and_probe(args.slide)
    if slide is None:
        log("HATA: slayt kullanilamaz (%s): %s" % (info["reason"], info.get("text", "")))
        return EXIT_SLIDE
    try:
        model_key = info["model"]
        name, _sha, _size, model_mpp = WEIGHTS[model_key]
        wfile = weight_path(args.model_dir, model_key)
        log("Model: %s (%s um/px) - slayt seviyesi %.4f um/px (dosya um/px %.4f)" % (model_key, model_mpp, info["level_mpp"], info["mpp_x"]))
        log("Agirlik dogrulaniyor (SHA-256): %s" % wfile)
        ok, why = check_weight(wfile, model_key)
        if not ok:
            log("HATA: %s kullanilamaz (%s) - sihirbazin 2. adimindan indirin/dogrulayin." % (name, why))
            return EXIT_ARGS
        bx, by = info["bounds_x"], info["bounds_y"]
        roi = read_roi(args.roi, bx, by)

        dz = DeepZoomGenerator(slide, tile_size=GRID_TILE, overlap=0, limit_bounds=False)
        grid_level = get_tiling_slide_level(slide, dz, mpp=GRID_MPP, verbose=0)
        downsample = 2 ** (dz.level_count - 1 - grid_level)
        tiles = grid_tiles_for_roi(roi, dz.level_tiles[grid_level], downsample)
        if not tiles:
            log("HATA: secili alan slayt sinirlari icinde karo icermiyor.")
            return EXIT_ARGS
        coords = np.array(tiles, dtype=int)
        log("STAT grid_tiles=%d grid_level=%d downsample=%d" % (len(coords), grid_level, downsample))

        if args.device == "cuda" and not torch.cuda.is_available():
            log("HATA: GPU (CUDA) secildi ama torch CUDA goremiyor.")
            return EXIT_ARGS
        device = "cpu" if (args.device == "cpu" or not torch.cuda.is_available()) else "cuda"
        t = time.time()
        seg = build_segmentor(model_key, wfile, device)
        log("Model yuklendi (%.1f sn), cihaz=%s" % (time.time() - t, device))

        coarse, dzi, ext_level = get_tile_coordinates_and_deepzoom_for_segmentor(
            slide, coords, grid_level, seg, original_tile_size=GRID_TILE,
            inference_tile_overlap=INFERENCE_TILE_OVERLAP, verbose=0)
        n_inf = len(coarse)
        log("STAT inference_tiles=%d tile_px=%d overlap_px=%d" % (n_inf, seg.inference_image_size, INFERENCE_TILE_OVERLAP))
        loader = get_tile_dataloader(slide=slide, deepzoom=dzi, segmentor=seg, coords=coarse,
                                     level=ext_level, n_workers=0, batch_size=args.batch_size)
        postprocess = seg.get_postprocess_fn()
        t = time.time()
        futures = []
        waited = 0  # futures[:waited] tamamlandi; ham haritalar bellekte birikmesin diye bekleme sinirli
        max_backlog = 2 * max(1, args.pp_threads) + 2
        done_tiles = 0
        with ThreadPoolExecutor(max_workers=max(1, args.pp_threads)) as pool, torch.inference_mode():
            for images in loader:
                out = seg.forward(images)
                raw = {k: v.detach().cpu().numpy() for k, v in out.items()}
                del out
                futures.append(pool.submit(postprocess, raw))
                del raw
                while len(futures) - waited > max_backlog:
                    futures[waited].result()
                    waited += 1
                done_tiles += int(images.shape[0])
                log("PROGRESS %d/%d" % (done_tiles, n_inf))
            preds = [tp for fut in futures for tp in fut.result()]  # upstream assemble ile ayni sira
        t_inf = time.time() - t
        if device == "cuda":
            log("STAT peak_vram_gb=%.2f" % (torch.cuda.max_memory_allocated() / 1e9))
        t = time.time()
        preds = fix_border_effects(tile_predictions=preds, tile_coords=coords, tile_size=GRID_TILE,
                                   inference_tile_coords=coarse, inference_tile_size=seg.inference_image_size,
                                   inference_tile_overlap=INFERENCE_TILE_OVERLAP, original_dz_level=grid_level,
                                   extraction_dz_level=ext_level, verbose=0)
        data = SlideSegmentationData.from_predictions(
            model_name=seg.segmentor_name, inference_mpp=seg.target_mpp, deepzoom_level=grid_level,
            tile_size=GRID_TILE, tile_coordinates=coords, tile_predictions=preds)
        t_post = time.time() - t
    finally:
        slide.close()

    os.makedirs(args.out, exist_ok=True)
    raw_json = os.path.join(args.out, "cell_masks.json")
    data.save(raw_json)
    fc = cells_to_geojson(data.to_dict()["cell_masks"], downsample, bx, by)
    geo = os.path.join(args.out, "histoplus_cells.geojson")
    with open(geo, "w", encoding="utf-8") as f:
        json.dump(fc, f)
    with open(os.path.join(args.out, "histoplus_run.json"), "w", encoding="utf-8") as f:
        json.dump({"slide": os.path.abspath(args.slide), "model": model_key, "model_mpp": model_mpp,
                   "level_mpp": info["level_mpp"], "file_mpp": info["mpp_x"], "grid_level": grid_level,
                   "downsample": downsample, "bounds": [bx, by], "grid_tiles": len(coords),
                   "inference_tiles": n_inf, "device": device, "batch_size": args.batch_size,
                   "histoplus_commit": HISTOPLUS_COMMIT, "hf_revision": HF_REVISION,
                   "labels": {str(k): v for k, v in seg.class_mapping.items()}}, f, indent=2)
    log("STAT infer_s=%.1f border_s=%.1f total_s=%.1f" % (t_inf, t_post, time.time() - t_all))
    log("STAT model=%s" % model_key)
    log("COUNT n=%d" % len(fc["features"]))
    log("RESULT geojson=%s" % geo)
    log("RESULT json=%s" % raw_json)
    log("DONE")
    return EXIT_OK


def cmd_detect(args):
    for label, path in (("slayt", args.slide), ("ROI", args.roi)):
        if not os.path.isfile(path):
            log("HATA: %s dosyasi yok: %s" % (label, path))
            return EXIT_ARGS
    if args.batch_size < 1 or args.pp_threads < 1:
        log("HATA: batch size ve son-isleme is parcacigi >= 1 olmali.")
        return EXIT_ARGS
    try:
        return run_detect(args)
    except Exception as exc:  # noqa: BLE001
        import traceback
        text = traceback.format_exc()
        for line in text.rstrip().splitlines()[-12:]:
            log("  | " + line)
        explain_failure(text)
        log("HATA: HistoPLUS basarisiz: %s: %s" % (type(exc).__name__, exc))
        return EXIT_HISTOPLUS


def build_parser():
    p = argparse.ArgumentParser(description="HistoPLUS koprusu (upstream yapi taslari, surec-ici).")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("selftest", help="Paketler + upstream yapi taslari + commit + CUDA.")
    s.set_defaults(func=cmd_selftest)

    pc = sub.add_parser("precheck", help="Slayti OpenSlide ile ac: boyut, sinir, um/px, model secimi.")
    pc.add_argument("--slide", required=True)
    pc.set_defaults(func=cmd_precheck)

    v = sub.add_parser("verify", help="Klasordeki agirliklarin boyut + SHA-256 degerini dogrula.")
    v.add_argument("--dir", required=True)
    v.set_defaults(func=cmd_verify)

    dl = sub.add_parser("download", help="Agirliklari Hugging Face'ten (kapili, sabit revizyon) indir.")
    dl.add_argument("--dir", required=True)
    dl.add_argument("--model", choices=sorted(WEIGHTS), default=None, help="Yalniz biri (varsayilan: ikisi).")
    dl.set_defaults(func=cmd_download)

    d = sub.add_parser("detect", help="HistoPLUS'ı secili alanda calistir.")
    d.add_argument("--slide", required=True)
    d.add_argument("--roi", required=True, help="(Multi)Polygon GeoJSON, tam cozunurluk QuPath koordinatlari.")
    d.add_argument("--model-dir", required=True)
    d.add_argument("--out", required=True, help="Calisma/cikti klasoru.")
    d.add_argument("--device", default="auto", choices=["auto", "cuda", "cpu"])
    d.add_argument("--batch-size", type=int, default=4)
    d.add_argument("--pp-threads", type=int, default=2, help="Son-isleme is parcacigi (GPU ile ortusur).")
    d.set_defaults(func=cmd_detect)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except Exception as exc:  # noqa: BLE001
        log("HATA: %s: %s" % (type(exc).__name__, exc))
        return EXIT_IMPORT


if __name__ == "__main__":
    # QuPath runner'i boru ile cagirir; Windows'ta boru kodlamasi cp1252 olur, sihirbaz ise UTF-8 okur.
    for _stream in (sys.stdout, sys.stderr):
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
