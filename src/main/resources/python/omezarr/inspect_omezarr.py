#!/usr/bin/env python3
"""
inspect_omezarr.py — Bir OME-Zarr / OME-NGFF (.ome.zarr) deposunu okur ve ozetler.

Atolye koprusu (qupath-extension-workshop). QuPath'in YERLESIK OME-Zarr yazicisiyla
(Extensions -> Atolye -> Yardimcilar -> OME-Zarr disa aktarma) uretilen depolari
DOGRULAMAK ve incelemek icindir. Salt-OKUR: depoyu degistirmez, yeni dosya yazmaz.

  * WSI dosyasi acmaz, openslide GEREKMEZ — yalnizca .ome.zarr klasor deposunu okur.
  * Alt-komutlar: `selftest` ve `inspect`.
  * `inspect` piramit duzeylerini (sekil, parca/chunk, dtype), eksenleri (axes) ve
    koordinat donusumlerini (coordinateTransformations) yazdirir; istege bagli olarak
    bir bolgeyi okuyup (--region) sekil + min/maks/ortalama istatistigi raporlar.
  * Once ome-zarr-py denenir; yoksa duz `zarr` ile yapisal dokum yapilir.

Kullanim:
  python inspect_omezarr.py selftest
  python inspect_omezarr.py inspect <depo.ome.zarr> [--level N] [--region X Y W H]

Ilgili atolye bolumu: Ekler -> OME-Zarr / OME-NGFF (ekler/ome-zarr-ngff.qmd).

Yalniz arastirma/egitim amacli; klinik karar uretmez.
"""

import argparse
import os
import sys
import traceback


def _p(msg=""):
    print(msg, flush=True)


def _axes_names(axes):
    """OME-NGFF 'axes' meta listesini ('t','c','z','y','x') kisa isimlere cevirir."""
    names = []
    for ax in axes or []:
        if isinstance(ax, dict):
            names.append(str(ax.get("name", "?")))
        else:
            names.append(str(ax))
    return names


def _describe_array(arr):
    """Bir dizi-benzeri (dask/numpy/zarr) icin (shape, chunks, dtype) dondurur."""
    shape = tuple(getattr(arr, "shape", ()))
    chunks = getattr(arr, "chunksize", None) or getattr(arr, "chunks", None)
    dtype = getattr(arr, "dtype", "?")
    return shape, chunks, dtype


def _region_stats(arr, x, y, w, h):
    """Son iki ekseni (y, x) sayarak bir pencereyi okur; (shape, min, max, mean)."""
    import numpy as np

    ndim = len(arr.shape)
    if ndim < 2:
        raise ValueError("Dizi 2 boyuttan az; bolge okunamiyor.")
    sl = [slice(None)] * ndim
    sl[-2] = slice(y, y + h)   # y
    sl[-1] = slice(x, x + w)   # x
    window = arr[tuple(sl)]
    # dask ise hesapla
    if hasattr(window, "compute"):
        window = window.compute()
    window = np.asarray(window)
    return window.shape, float(window.min()), float(window.max()), float(window.mean())


def _inspect_ome(path, level, region):
    """ome-zarr-py ile cok-olcekli depoyu inceler. ImportError yukari firlatilir."""
    from ome_zarr.io import parse_url
    from ome_zarr.reader import Reader

    loc = parse_url(path, mode="r")
    if loc is None:
        _p("HATA: Gecerli bir OME-Zarr deposu degil (parse_url None dondu): %s" % path)
        return 1
    reader = Reader(loc)
    nodes = list(reader())
    image_node = None
    for node in nodes:
        data = getattr(node, "data", None)
        if data:
            image_node = node
            break
    if image_node is None:
        _p("HATA: Depoda cok-olcekli goruntu dugumu bulunamadi.")
        return 1

    data = image_node.data            # duzey basina (dask) dizi listesi
    meta = image_node.metadata or {}
    axes = _axes_names(meta.get("axes"))

    _p("Okuyucu        : ome-zarr-py")
    _p("Depo           : %s" % path)
    _p("Eksenler (axes): %s" % (", ".join(axes) if axes else "(bildirilmemis)"))
    _p("Piramit duzeyi : %d" % len(data))
    _p("")
    for i, arr in enumerate(data):
        shape, chunks, dtype = _describe_array(arr)
        _p("  Duzey %d: sekil %s  parca %s  dtype %s" % (i, shape, chunks, dtype))

    cts = meta.get("coordinateTransformations")
    if cts:
        _p("")
        _p("Koordinat donusumleri (coordinateTransformations):")
        for i, ct in enumerate(cts):
            _p("  Duzey %d: %s" % (i, ct))

    if region is not None:
        x, y, w, h = region
        lvl = max(0, min(level, len(data) - 1))
        _p("")
        _p("Bolge okunuyor (duzey %d, x=%d y=%d w=%d h=%d) ..." % (lvl, x, y, w, h))
        try:
            shape, vmin, vmax, vmean = _region_stats(data[lvl], x, y, w, h)
            _p("  Okunan sekil : %s" % (shape,))
            _p("  min / maks   : %g / %g" % (vmin, vmax))
            _p("  ortalama     : %g" % vmean)
        except Exception as exc:  # noqa: BLE001
            _p("  Bolge okunamadi: %s" % exc)
            return 1

    _p("")
    _p("INSPECT OK")
    return 0


def _inspect_plain_zarr(path, level, region):
    """ome-zarr-py yoksa duz zarr ile yapisal dokum (yedek yol)."""
    import zarr

    _p("Okuyucu        : zarr (duz; ome-zarr-py bulunamadi)")
    _p("Depo           : %s" % path)
    grp = zarr.open(path, mode="r")

    # Cok-olcekli dizileri topla: '0','1',... ya da grup icindeki tum diziler
    arrays = []
    try:
        for key, arr in grp.arrays():
            arrays.append((key, arr))
    except Exception:  # noqa: BLE001
        pass
    arrays.sort(key=lambda kv: (len(kv[0]), kv[0]))

    attrs = dict(getattr(grp, "attrs", {}) or {})
    multiscales = attrs.get("multiscales")
    if multiscales:
        try:
            axes = _axes_names(multiscales[0].get("axes"))
            _p("Eksenler (axes): %s" % (", ".join(axes) if axes else "(bildirilmemis)"))
        except Exception:  # noqa: BLE001
            pass

    if not arrays:
        _p("HATA: Depoda dizi bulunamadi (cok-olcekli OME-Zarr degil?).")
        return 1

    _p("Piramit duzeyi : %d" % len(arrays))
    _p("")
    level_arrays = []
    for key, arr in arrays:
        shape, chunks, dtype = _describe_array(arr)
        _p("  Dizi '%s': sekil %s  parca %s  dtype %s" % (key, shape, chunks, dtype))
        level_arrays.append(arr)

    if region is not None:
        x, y, w, h = region
        lvl = max(0, min(level, len(level_arrays) - 1))
        _p("")
        _p("Bolge okunuyor (dizi indeks %d, x=%d y=%d w=%d h=%d) ..." % (lvl, x, y, w, h))
        try:
            shape, vmin, vmax, vmean = _region_stats(level_arrays[lvl], x, y, w, h)
            _p("  Okunan sekil : %s" % (shape,))
            _p("  min / maks   : %g / %g" % (vmin, vmax))
            _p("  ortalama     : %g" % vmean)
        except Exception as exc:  # noqa: BLE001
            _p("  Bolge okunamadi: %s" % exc)
            return 1

    _p("")
    _p("INSPECT OK")
    return 0


def inspect(path, level, region):
    if not os.path.exists(path):
        _p("HATA: Yol bulunamadi: %s" % path)
        return 1
    try:
        return _inspect_ome(path, level, region)
    except ImportError:
        _p("(ome-zarr-py bulunamadi — duz zarr'a dusuluyor)")
        try:
            return _inspect_plain_zarr(path, level, region)
        except ImportError:
            _p("HATA: ne ome-zarr-py ne de zarr kurulu.")
            _p("  Kurulum: pip install -r handson/python/omezarr/requirements.txt")
            return 1


def selftest():
    _p("Python: %s" % sys.version.split()[0])
    ok = True
    for name in ("numpy", "zarr", "dask", "ome_zarr"):
        try:
            mod = __import__(name)
            _p("%-9s: %s  OK" % (name, getattr(mod, "__version__", "?")))
        except Exception as exc:  # noqa: BLE001
            # ome_zarr olmasa bile duz zarr ile inspect calisir; uyari ver, cokme.
            level = "UYARI" if name == "ome_zarr" else "EKSIK"
            _p("%-9s: %s - %s" % (name, level, exc))
            if name != "ome_zarr":
                ok = False
    if not ok:
        _p("  Kurulum: pip install -r handson/python/omezarr/requirements.txt")
        _p("SELFTEST FAILED")
        return 1
    _p("SELFTEST OK")
    return 0


def main():
    parser = argparse.ArgumentParser(
        description="OME-Zarr / OME-NGFF (.ome.zarr) depo inceleyici (salt-okur)")
    sub = parser.add_subparsers(dest="cmd")
    sub.add_parser("selftest", help="Bagimlilik kontrolu")
    insp = sub.add_parser("inspect", help="Bir .ome.zarr deposunu ozetle")
    insp.add_argument("path", help="Girdi .ome.zarr klasor deposu")
    insp.add_argument("--level", type=int, default=0,
                      help="--region icin piramit duzey indeksi (varsayilan 0 = tam cozunurluk)")
    insp.add_argument("--region", type=int, nargs=4, metavar=("X", "Y", "W", "H"),
                      default=None, help="Istege bagli: bir pencere oku ve istatistik raporla")

    args = parser.parse_args()
    if args.cmd == "selftest":
        sys.exit(selftest())
    if args.cmd == "inspect":
        try:
            sys.exit(inspect(args.path, args.level, args.region))
        except SystemExit:
            raise
        except Exception as exc:  # noqa: BLE001
            _p("HATA: %s" % exc)
            traceback.print_exc()
            sys.exit(1)
    parser.print_help()
    sys.exit(2)


if __name__ == "__main__":
    main()
