#!/usr/bin/env python3
# VALIS bridge runner - QuPath Atolye workshop (gomulu surumden yazildi)
# Hedef QuPath: 0.6.0+ (QuPath DISINDA, VALIS venv/Docker icinde calisir)
# Gatenbee ve ark., Nat Commun 2023, doi:10.1038/s41467-023-40218-9 ; VALIS = MIT.
# Yalnizca arastirma/egitim amaclidir.

import argparse
import json
import os
import sys
import threading
import time
import traceback

# Native cokme (segfault / Windows access violation 0xC0000005) durumunda Python yigin izini stderr'e
# doker — aksi halde surec SESSIZ olur (traceback/RESULT_JSON basmadan). Kok-neden tanisi icin kritik.
try:
    import faulthandler
    faulthandler.enable()
except Exception:
    pass


# ── Windows DLL-cakisma onlemi + libvips ayari ─────────────────────────────────
# Kullanicinin sistem PATH'i farkli-surum goruntul-kutuphaneleri icerebilir (or. C:\openslide\bin ->
# eski libjpeg-62/libtiff-5/openslide-jni; C:\vips-dev-*\bin -> farkli libvips). VALIS alt-surecinde
# (JVM/Bio-Formats ya da libvips) yanlis DLL yuklenip 0xC0000005 (access violation) SESSIZ cokmesine
# yol acabilir (traceback/RESULT_JSON basmadan). Bu girisleri PATH'ten cikar; venv kendi DLL'lerini
# os.add_dll_directory ile zaten yukler. Ayrica libvips is-parcacik sayisini sinirla (buyuk slayt
# donusumunde tepe RAM + yaris riskini azaltir).
_PATH_REMOVED = []
if os.name == "nt":
    _bad = ("\\openslide", "vips-dev")
    _parts = os.environ.get("PATH", "").split(os.pathsep)
    _PATH_REMOVED = [p for p in _parts if p and any(b in p.lower() for b in _bad)]
    if _PATH_REMOVED:
        os.environ["PATH"] = os.pathsep.join([p for p in _parts if p and p not in _PATH_REMOVED])
os.environ.setdefault("VIPS_CONCURRENCY", "4")

# Model/onbellek indirmelerini atolye veri koku altina yonlendir: DISK+LightGlue agirliklari (torch hub) +
# HF onbellegi buraya iner (C:/ev dizini dolmasin, tek yerde toplansin). Varsayilan ~/.atolye; kullanici
# ATOLYE_DATA_ROOT ortam degiskeniyle degistirebilir. Zaten ayarli TORCH_HOME/HF_HOME'a DOKUNMA (Windows
# sihirbazi native modda applyCacheEnv ile kendi veri-kok degerini verir; setdefault onu ezmez).
_ATOLYE_DATA_ROOT = os.environ.get("ATOLYE_DATA_ROOT") or os.path.join(os.path.expanduser("~"), ".atolye")
os.environ.setdefault("TORCH_HOME", os.path.join(_ATOLYE_DATA_ROOT, "cache", "torch"))
os.environ.setdefault("HF_HOME", os.path.join(_ATOLYE_DATA_ROOT, "cache", "huggingface"))


_EMIT_PREFIX = [""]  # tek-elemanli degistirilebilir konteyner: cmd_batch her vaka icin buraya
                      # "[vaka i/n: ad] " yazar (SIRALI calisir - ayni anda tek vaka aktif oldugundan
                      # her emit() cagrisina (heartbeat/merge/warp alt-fonksiyonlari dahil) TEK YERDEN
                      # ayirt edici bir onek eklemenin en az mudahaleci yolu; her cagri yerini
                      # emit_fn parametresiyle degistirmekten kacinir).


def emit(msg):
    print(_EMIT_PREFIX[0] + msg, flush=True)


# Kaba asama isaretleri (yalniz cmd_run'in KENDI kontrol ettigi asamalar - VALIS'in ic
# adimlarina (rigid/non-rigid/hata-olcumu) BOLUNMEZ, cunku bunlar registrar.register()
# icinde TEK bloklayici cagridir ve dogrulanmamis ic API varsayimi riskli olur). QuPath
# tarafi bu satirlari ayristirip bir ilerleme etiketi surer; log akisini DEGISTIRMEZ.
def _stage_begin(key, label):
    emit("ASAMA_BASLA: " + key + " | " + label)


def _stage_end(key):
    emit("ASAMA_BITTI: " + key)


def _start_heartbeat(label, seconds=20):
    # Uzun/sessiz asamalarda (ozellik eslestirme, rigid/non-rigid kayit, OME yazma) her N saniyede
    # bir "hala calisiyor" nabzi bas -> kullanici surecin canli oldugunu gorsun. Dondurulen Event.set()
    # ile durur. Ayri thread; VALIS'in kendi ciktisini engellemez.
    stop = threading.Event()
    t0 = time.time()

    def _beat():
        while not stop.wait(seconds):
            el = int(time.time() - t0)
            emit("[%s] hala calisiyor... gecen sure %d:%02d" % (label, el // 60, el % 60))

    threading.Thread(target=_beat, daemon=True).start()
    return stop


def _kill_jvm():
    # VALIS kill_jvm() "JVM has been killed. If this was due to an error..." yaziyor -> hata sanilir.
    # O ciktiyi bastir ve yerine ACIK bir 'normal kapanis' mesaji ver. Gercek sonuc RESULT_JSON'dadir.
    try:
        from valis import registration
        import io
        import contextlib
        import warnings
        _buf = io.StringIO()
        with contextlib.redirect_stdout(_buf), contextlib.redirect_stderr(_buf), warnings.catch_warnings():
            warnings.simplefilter("ignore")
            registration.kill_jvm()
        emit("Java/Bio-Formats motoru kapatildi (islem bitince otomatik kapanir - NORMALDIR, hata degildir).")
        # VALIS'in BEKLENEN zararsiz satirini ('JVM has been killed. If this was due to an error...') AT;
        # o satir 'error' kelimesini icerdiginden onu yeniden basmak susturmanin amacini bozardi. Yalnizca
        # o satirin DISINDA gercek bir hata kaldiysa goster.
        _residual = "\n".join(ln for ln in _buf.getvalue().splitlines() if "jvm has been killed" not in ln.lower()).strip()
        if _residual and any(w in _residual.lower() for w in ("error", "exception", "traceback")):
            emit("kill_jvm ek cikti: " + " ".join(_residual.split())[:500])
    except Exception:
        pass


def cmd_selftest(_args):
    result = {"ok": False, "cmd": "selftest"}
    try:
        import valis
        from valis import registration  # noqa: F401
        result["valis"] = getattr(valis, "__version__", "unknown")
        try:
            import pyvips
            result["pyvips"] = getattr(pyvips, "__version__", "present")
            result["libvips"] = "%d.%d.%d" % (pyvips.version(0), pyvips.version(1), pyvips.version(2))
        except Exception as e:
            result["pyvips_error"] = repr(e)
        result["ok"] = True
        emit("selftest OK: valis " + str(result.get("valis")))
    except Exception as e:
        result["error"] = repr(e)
        emit("selftest HATA: " + repr(e))
    finally:
        _kill_jvm()
    emit("RESULT_JSON: " + json.dumps(result))
    return 0 if result["ok"] else 1


def _fix_pyvips_numpy():
    # VALIS 1.2.0 hatasi: warp_tools.is_pyvips_22() "major>=2 AND minor>=2" mantigiyla pyvips 3.1.x'i
    # (minor=1) YANLIS olarak <2.2 sayar -> guvensiz _vips2numpy_pre_22 (write_to_memory + ndarray-buffer)
    # yolu secilir -> convert_imgs sirasinda 0xC0000005 (access violation) SESSIZ cokme. Dogru yol vi.numpy();
    # is_pyvips_22'yi True'ya sabitle (pyvips >= 2.2 zaten .numpy() destekler; 3.1.1 kurulu).
    try:
        from valis import warp_tools
        if not warp_tools.is_pyvips_22():
            warp_tools.is_pyvips_22 = lambda: True
            return True
    except Exception:
        pass
    return False


def _fix_vips_slide_read():
    # VALIS 1.2.0 + libvips 8.18 (openslide loader YOK): SVS piramidinde thumbnail/label sayfalari yuzunden
    # convert_imgs'in level secimi (levels_in_range[0]-1) LEVEL-0'a (tam cozunurluk ~13 gigapiksel) duser.
    # Tam-VALIS yuklu iken bu devasa goruntuyu write_to_memory ile materiyalize etmek 0xC0000005 (access
    # violation) ile SESSIZ coker (kucuk goruntuler hep calisir). Cozum: tam-slayt okumada (xywh=None) EN
    # KUCUK uygun PIRAMIT sayfasini oku (ayni en-boy orani = etiket/makro degil; tercihen hedef 1024'ten
    # buyugu); convert_imgs onu 1024'e kuculutur. Koordinat dogrulugu KORUNUR cunku Slide.slide_shape_rc =
    # metadata.slide_dimensions[0] (tam cozunurluk). Bolge okumalari (xywh) degismez; hata -> orijinal metod.
    try:
        from valis import slide_io
        import pyvips
        _orig = slide_io.VipsSlideReader.slide2vips
        TARGET = 1024  # DEFAULT_MAX_IMG_DIM

        def _best_page(sd):
            try:
                w0, h0 = float(sd[0][0]), float(sd[0][1])
                ar0 = (w0 / h0) if h0 else 1.0
                over, atleast = [], []
                for i in range(len(sd)):
                    w, h = float(sd[i][0]), float(sd[i][1])
                    if w <= 0 or h <= 0:
                        continue
                    if ar0 > 0 and abs((w / h) - ar0) / ar0 > 0.02:
                        continue  # etiket/makro (farkli en-boy orani) -> atla
                    md = max(w, h)
                    if md > TARGET:
                        over.append((md, i))
                    if md >= TARGET:
                        atleast.append((md, i))
                if over:
                    return min(over)[1]
                if atleast:
                    return min(atleast)[1]
            except Exception:
                pass
            return 0

        def _patched(self, level, xywh=None, *args, **kwargs):
            if (xywh is None
                    and not getattr(self, "use_openslide", False)
                    and not getattr(self, "is_ome", False)):
                try:
                    pi = _best_page(self.metadata.slide_dimensions)
                    vs = pyvips.Image.new_from_file(self.src_f, page=pi, access="sequential")
                    if self.metadata.is_rgb and vs.hasalpha() >= 1:
                        vs = vs.flatten()
                    if vs.bands > 3:
                        vs = vs[0:3]
                    return vs
                except Exception:
                    pass
            return _orig(self, level, xywh, *args, **kwargs)

        slide_io.VipsSlideReader.slide2vips = _patched
        return True
    except Exception:
        return False


# QuPath varsayilan renk-ayristirma (color deconvolution) OD vektorleri (Ruifrok & Johnston
# tabanli; yardimci-boya-vektor-sihirbaz.groovy ile ayni). Parlak-alan lamlari QuPath'te RGB
# saklanir; ANLAMLI multipleks kanallari ise ayristirilmis BOYALARDIR — İHK'de Hematoksilen+DAB,
# H&E'de Hematoksilen+Eozin. H&E slaytini H-DAB vektorleriyle ayristirmak SAHTE bir 'DAB' kanali
# uretir (H&E'de DAB yoktur) — bu yuzden lam-basi boya tipi (--merge-stain) desteklenir.
_OD_HEM = (0.651, 0.701, 0.290)
_OD_DAB = (0.269, 0.568, 0.778)
_OD_EOSIN = (0.216, 0.801, 0.558)

# Boya tipi -> ayristirilacak boyalarin (kanal-adi, OD-vektoru) sirali listesi. Merge bu tabloyu
# kullanip N-boya renk-ayristirma yapar (recomb tembel/piramidal). YENI boya tipleri (or. Masson
# trichrome: 3 bilesen) yalnizca buraya + sihirbaz acilir-menusune EKLENIR; deconvolution kodu
# degismez. 'dab' = 'hed'in yalniz-DAB kirpimi (asagida ozel), 'rgb' = ham (ayristirma yok).
# Vektorler QuPath varsayilanlariyla ayni (yardimci-boya-vektor-sihirbaz.groovy).
_STAIN_TABLE = {
    "hed": [("Hematoksilen", _OD_HEM), ("DAB", _OD_DAB)],
    "he":  [("Hematoksilen", _OD_HEM), ("Eozin", _OD_EOSIN)],
    # Gelecek ornek (vektorler kalibre edilince eklenir):
    # "trichrome": [("Cekirdek", _OD_HEM), ("Kollajen", (r,g,b)), ("Sitoplazma", (r,g,b))],
}
# Ayristirma yapmayan/ozel tipler dahil gecerli boya tipleri (argparse + sihirbaz dogrulamasi icin).
_VALID_STAINS = sorted(set(_STAIN_TABLE.keys()) | {"dab", "rgb"})


def _recomb_for(od_vectors):
    """(name, OD) veya OD listesinden pyvips recomb matrisi (N x 3). N boyaya genellenir."""
    import numpy as np
    from valis import preprocessing
    ods = [(od[1] if isinstance(od, (tuple, list)) and len(od) == 2 and isinstance(od[0], str) else od)
           for od in od_vectors]
    stain_rgb255 = np.vstack([[255.0 * (10.0 ** (-v)) for v in od] for od in ods])
    dmat = preprocessing.stainmat2decon(stain_rgb255)          # (3, N)
    n = len(ods)
    return [[float(dmat[j][i]) for j in range(3)] for i in range(n)]   # N x 3 (recomb)


# QuPath'in kanallara atadigi VARSAYILAN LUT renkleri (OME Channel Color). Ayristirilmis
# multipleks additif gosterilir; DOGAL boya renkleri kanallarin taninmasini saglar (yesil/macenta
# gibi keyfi renkler yerine). Hematoksilen -> mavi-mor, Eozin -> pembe (H&E gibi). DAB kanallarina
# ayirt edilebilirlik icin sirayla belirgin renkler verilir (cok markerli multiplekste hepsi kahve
# olmasin diye). Boyle multipleks yine additiftir; "Invert background" ile beyaz-zemine yaklasir.
_HEM_COLOR = (95, 75, 185)      # hematoksilen: mavi-mor
_EOSIN_COLOR = (235, 90, 140)   # eozin: pembe
_DAB_PALETTE = [(170, 110, 40), (0, 200, 90), (200, 60, 200), (0, 190, 210), (240, 140, 0), (210, 40, 40)]
_FALLBACK_PALETTE = [(200, 200, 200), (255, 255, 0), (0, 255, 255), (255, 0, 255), (150, 220, 0)]


def _build_channel_colormap(names, color_map=None):
    """Kanal adlarindan {ad: (r,g,b)} renk sozlugu (create_ome_xml colormap). Sonek'e gore.
    color_map (marker -> (r,g,b)) verilirse o marker'in ISARET kanalina (DAB/Eozin) kullanicinin
    sectigi renk uygulanir; Hematoksilen daima mavi-mor kalir. rgb-gecis (R/G/B) kanallarina
    kullanici rengi uygulanmaz. Kanal adi '{marker}-{boya}' oldugundan marker son '-'den ayrilir."""
    color_map = color_map or {}
    cmap = {}
    dab_i = 0
    fb_i = 0
    for nm in names:
        s = str(nm)
        low = s.lower()
        marker = s.rsplit("-", 1)[0] if "-" in s else s
        user = color_map.get(marker)
        if low.endswith("hematoksilen"):
            cmap[nm] = _HEM_COLOR
        elif low.endswith("eozin"):
            cmap[nm] = user or _EOSIN_COLOR
        elif low.endswith("-dab") or low == "dab":
            cmap[nm] = user or _DAB_PALETTE[dab_i % len(_DAB_PALETTE)]
            dab_i += 1
        elif low.endswith("-r"):
            cmap[nm] = (255, 0, 0)
        elif low.endswith("-g"):
            cmap[nm] = (0, 255, 0)
        elif low.endswith("-b"):
            cmap[nm] = (0, 0, 255)
        else:
            # Kullanici rengini UYGULAMA: cok-kanalli (IF) gecis slaytinda ('Marker-DAPI','Marker-CD3'...)
            # hepsi ayni marker on-ekini paylasir; user uygulanirsa TUMU tek renge duserdi. Sadece
            # DAB/Eozin (isaret) kanallari kullanici rengini alir; digerleri ayrik palet.
            cmap[nm] = _FALLBACK_PALETTE[fb_i % len(_FALLBACK_PALETTE)]
            fb_i += 1
    return cmap


def _check_warp_wh(warped, expected_wh, sname):
    """Warp edilmis lamlarin AYNI boyutta olmasini garanti et. pyvips aritmetigi/bandjoin boyut
    uyusmazliginda HATA VERMEZ — kucuk goruntuyu sol-uste gomer (sessiz-yanlis birlesim). Uyusmazlikta
    net hata ver. Bilinen tetik: Windows 'best-page' yamasi + crop='reference' -> referans lam kuculur."""
    wh = (warped.width, warped.height)
    if expected_wh is None:
        return wh
    if wh != expected_wh:
        raise ValueError(
            "Warp boyutlari uyusmuyor: '%s' %dx%d, digerleri %dx%d. Sessiz-yanlis birlesim onlendi. "
            "Genelde neden: Windows 'best-page' yamasi + crop='reference' (referans lam kuculur). "
            "Cozum: crop='overlap' kullanin ya da WSL/Docker modunu tercih edin."
            % (sname, wh[0], wh[1], expected_wh[0], expected_wh[1]))
    return expected_wh


def _merge_slides_deconvolved(registrar, dst_f, level, non_rigid, crop, mode, name_map, stain_map, emit, color_map=None):
    """Renk-ayristirmali cok-kanalli merge. Her lami warp eder, boya tipine gore (bkz.
    _STAIN_TABLE) N-boya ayristirir (pyvips recomb, tembel), bandjoin ile birlestirir ve
    VALIS'in OME yazicisiyla piramidal OME-TIFF yazar.

    mode: lam-basi override yoksa VARSAYILAN boya tipi ('hed'|'he'|'dab'|'rgb'|...).
    stain_map: lam-adi -> boya tipi override. Bulunmazsa `mode` kullanilir.
      _STAIN_TABLE'daki her tip N kanal uretir; 'dab' = yalniz DAB kanali; 'rgb' = ham RGB.
    name_map: lam-adi -> marker etiketi (kanal adi on-eki). Bulunamazsa lam adi.
    IF (cok-kanalli, is_rgb=False) lamlar ayristirilmadan oldugu gibi eklenir.
    Doner: kanal adlari listesi."""
    from valis import slide_io, valtils

    od_scale = 100.0
    eps = 1e-6
    _recomb_cache = {}

    def _entries_recomb(stain_key):
        # 'dab' -> 'hed' tablosunu kullanir (sonra yalniz DAB kanali kirpilir). Bilinmeyen -> 'hed'.
        base = "hed" if stain_key == "dab" else stain_key
        entries = _STAIN_TABLE.get(base) or _STAIN_TABLE["hed"]
        if base not in _recomb_cache:
            _recomb_cache[base] = _recomb_for(entries)
        return entries, _recomb_cache[base]

    def _deconv(rgb, recomb):
        od = ((rgb.cast("float") / 255.0) + eps).log10() * -1.0
        od = (od < 0).ifthenelse(0, od)                        # negatif OD'yi kirp
        dec = od.recomb(recomb)                                # N band (OD konsantrasyon)
        return (dec * od_scale).cast("uchar")                  # cast satüre eder (0-255)

    src_f_list = registrar.get_sorted_img_f_list()
    merged = None
    names = []
    _exp_wh = None
    for f in src_f_list:
        sname = valtils.get_name(os.path.split(f)[1])
        slide_obj = registrar.slide_dict[sname]
        marker = name_map.get(sname) or sname
        stain = stain_map.get(sname) or mode                   # lam-basi override else global varsayilan
        warped = slide_obj.warp_slide(int(level), non_rigid=non_rigid, crop=crop, interp_method="bicubic")
        _exp_wh = _check_warp_wh(warped, _exp_wh, sname)       # boyut uyusmazliginda SESSIZ hizalama yerine HATA
        is_rgb = getattr(slide_obj.reader.metadata, "is_rgb", None)
        if stain == "rgb" or is_rgb is False:
            part = warped
            existing = getattr(slide_obj.reader.metadata, "channel_names", None)
            if existing:
                names += [str(marker) + "-" + str(c) for c in existing]
            elif warped.bands == 3:
                names += [str(marker) + "-" + c for c in ("R", "G", "B")]
            elif warped.bands == 4:
                names += [str(marker) + "-" + c for c in ("R", "G", "B", "A")]
            else:
                names += [str(marker) + "-C" + str(i + 1) for i in range(warped.bands)]
        elif stain == "dab":
            entries, recomb = _entries_recomb("dab")           # 'hed' tablosu (Hem, DAB)
            dec = _deconv(warped, recomb)
            _dab_idx = next((i for i, (nm, _od) in enumerate(entries) if nm == "DAB"), len(entries) - 1)
            part = dec[_dab_idx]                               # yalniz DAB kanali
            names += [str(marker) + "-DAB"]
        else:                                                   # 'hed'|'he'|... (N-boya)
            entries, recomb = _entries_recomb(stain)
            part = _deconv(warped, recomb)                     # N band, entries sirasinda
            names += [str(marker) + "-" + str(nm) for (nm, _od) in entries]
        merged = part if merged is None else merged.bandjoin(part)
        emit("  ayristirildi + eklendi: " + str(marker) + " [" + str(stain) + "] (" + sname + ")")

    merged = merged.copy(interpretation=("b-w" if merged.bands == 1 else "multiband"))
    ref = registrar.get_ref_slide()
    px = ref.reader.scale_physical_size(int(level))
    bf_dtype = slide_io.vips2bf_dtype(merged.format)
    xyczt = slide_io.get_shape_xyzct((merged.width, merged.height), merged.bands)
    # DOGAL kanal renkleri (OME Channel Color) -> QuPath varsayilan LUT'lar: Hem mavi-mor, Eozin
    # pembe, DAB ayirt edilebilir renkler (keyfi yesil/macenta yerine tanidik boya renkleri).
    ome_xml = slide_io.create_ome_xml(xyczt, bf_dtype, is_rgb=False,
                                      pixel_physical_size_xyu=px, channel_names=names,
                                      colormap=_build_channel_colormap(names, color_map)).to_xml()
    tile_wh = slide_io.get_tile_wh(reader=ref.reader, level=int(level), out_shape_wh=xyczt[0:2])
    dd = os.path.dirname(dst_f)
    if dd:
        os.makedirs(dd, exist_ok=True)
    slide_io.save_ome_tiff(merged, dst_f=dst_f, ome_xml=ome_xml, tile_wh=tile_wh, pyramid=True)
    return names


def _norm_vec(od):
    import math
    n = math.sqrt(sum(v * v for v in od))
    return [v / n for v in od] if n > 0 else list(od)


def _merge_slides_composite(registrar, dst_f, level, non_rigid, crop, mode, name_map, stain_map, emit):
    """DOGAL-RENK parlak-alan RGB bilesik (Beer-Lambert). Her lamin (boya tipine gore) ayristirilan
    boya OD katkilarini TOPLAR ve I = 255 * 10^(-OD_toplam) ile beyaz-zeminli RGB uretir. TEK lamda o
    lamin dogal gorunumudur (or. H&E gibi); COK lamda SENTETIK bir bilesiktir — gercek bir lama
    karsilik GELMEZ, cakisan boyalar koyulasir (fiziksel gecirgenlik modeli). Belirtecleri ayirt etmek
    icin cok-kanalli multipleks daha uygundur; bu, 'lam gibi gozuksun' isteyenler icindir.
    RGB parlak-alan OME-TIFF yazar (QuPath 'Brightfield' acar). Doner: ['Kirmizi','Yesil','Mavi']."""
    from valis import slide_io, valtils
    eps = 1e-6
    _cache = {}

    def _get(stain_key):
        # deconv recomb (tum entries) + forward recomb (yalniz KEEP boyalar, normalize OD vektorleri)
        base = "hed" if stain_key == "dab" else stain_key
        entries = _STAIN_TABLE.get(base) or _STAIN_TABLE["hed"]
        keep = [(nm, od) for (nm, od) in entries if nm == "DAB"] if stain_key == "dab" else list(entries)
        if stain_key not in _cache:
            deconv = _recomb_for(entries)                       # N×3 (ayristirma)
            mnorm = [_norm_vec(od) for (_nm, od) in keep]       # len(keep)×3 (normalize OD)
            fwd = [[mnorm[s][c] for s in range(len(keep))] for c in range(3)]   # 3×len(keep) (geri kurma)
            keep_idx = [entries.index(k) for k in keep]
            _cache[stain_key] = (deconv, fwd, keep_idx)
        return _cache[stain_key]

    src_f_list = registrar.get_sorted_img_f_list()
    od_total = None
    _exp_wh = None
    for f in src_f_list:
        sname = valtils.get_name(os.path.split(f)[1])
        slide_obj = registrar.slide_dict[sname]
        stain = stain_map.get(sname) or mode
        warped = slide_obj.warp_slide(int(level), non_rigid=non_rigid, crop=crop, interp_method="bicubic")
        is_rgb = getattr(slide_obj.reader.metadata, "is_rgb", None)
        if is_rgb is False:
            # Cok-kanalli (IF) slaytin dogal iletim (parlak-alan) rengi yoktur + band sayisi RGB ile
            # toplanmaz -> bilesige EKLEME (uyar). Composite parlak-alan slaytlar icindir.
            emit("  UYARI: cok-kanalli (IF) lam parlak-alan bilesigine eklenmedi: " + str(sname))
            continue
        _exp_wh = _check_warp_wh(warped, _exp_wh, sname)       # boyut uyusmazliginda SESSIZ hizalama yerine HATA
        od = ((warped.cast("float") / 255.0) + eps).log10() * -1.0
        od = (od < 0).ifthenelse(0, od)                        # 3-band OD (negatif kirp)
        if stain == "rgb":
            od_contrib = od                                     # ham absorbans (3-band parlak-alan)
        else:
            deconv, fwd, keep_idx = _get(stain)
            conc = od.recomb(deconv)                            # N-band konsantrasyon
            conc_keep = conc[keep_idx[0]] if len(keep_idx) == 1 else \
                conc[keep_idx[0]].bandjoin([conc[i] for i in keep_idx[1:]])
            od_contrib = conc_keep.recomb(fwd)                  # 3-band OD katkisi (yalniz KEEP boyalar)
        od_total = od_contrib if od_total is None else (od_total + od_contrib)
        emit("  bilesige eklendi: " + str(sname) + " [" + str(stain) + "]")

    if od_total is None:
        raise ValueError("Parlak-alan bilesigi icin uygun (parlak-alan/RGB) lam yok.")
    rgb = ((od_total * -1.0).exp10() * 255.0).cast("uchar")    # I = 255 * 10^(-OD_toplam); cast satüre eder
    rgb = rgb.copy(interpretation="srgb")
    ref = registrar.get_ref_slide()
    px = ref.reader.scale_physical_size(int(level))
    bf_dtype = slide_io.vips2bf_dtype(rgb.format)
    xyczt = slide_io.get_shape_xyzct((rgb.width, rgb.height), 3)
    ome_xml = slide_io.create_ome_xml(xyczt, bf_dtype, is_rgb=True, pixel_physical_size_xyu=px).to_xml()
    tile_wh = slide_io.get_tile_wh(reader=ref.reader, level=int(level), out_shape_wh=(rgb.width, rgb.height))
    dd = os.path.dirname(dst_f)
    if dd:
        os.makedirs(dd, exist_ok=True)
    slide_io.save_ome_tiff(rgb, dst_f=dst_f, ome_xml=ome_xml, tile_wh=tile_wh, pyramid=True)
    return ["Kirmizi", "Yesil", "Mavi"]


# ── Otomatik referans secimi (YALNIZ OLCUM - kayit YAPMAZ, piksel COZMEZ) ──────────────────
_WSI_EXTS = (".svs", ".ndpi", ".tif", ".tiff", ".scn", ".mrxs", ".vsi", ".czi")


def _slide_dims_and_mpp(path):
    """Lam genislik/yukseklik + (varsa) mikron/piksel (mpp) degerlerini BASLIKTAN oku - pyvips
    sequential-erisimle acar (piksel COZMEZ, agir degil; ayni yaklasim dosyanin baska yerinde
    Windows best-page yamasinda da kullanilir). MPP okunamazsa (mx/my None) yine de boyut-tabanli
    kaba alan kestirimi yapilabilir. Basarisizsa None doner."""
    try:
        import pyvips
        vi = pyvips.Image.new_from_file(path, access="sequential")
        w, h = float(vi.width), float(vi.height)
        mx = my = None
        try:
            if vi.get_typeof("openslide.mpp-x") != 0:
                mx = float(vi.get("openslide.mpp-x"))
            if vi.get_typeof("openslide.mpp-y") != 0:
                my = float(vi.get("openslide.mpp-y"))
        except Exception:
            pass
        return w, h, mx, my
    except Exception:
        return None


def _pick_reference(image_paths, emit_fn):
    """Aday lamlar arasindan OLCUM-TABANLI bir referans (kayit ceker) sec: doku-orani (Otsu esikleme,
    kucultulmus onizleme uzerinde) x baslik-alani (genislik*yukseklik*mpp_x*mpp_y - farkli mpp'li
    taramalari adil kiyaslar). Kayit YAPMAZ, piksel COZMEZ (yalniz pyvips thumbnail + baslik okur).
    Ilk iki adayin skoru YAKINSA (fark < %5) ORB anahtar-nokta yogunlugu ile ayirt eder (yalniz o
    adaylarda - varsayilan yolu ucuz tutmak icin). Her aday icin bir SATIR loglar (denetlenebilir
    secim - sessiz kara-kutu degil). Doner: (en_iyi_yol_veya_None, {tam_yol: {olcumler}}) - anahtar
    TAM YOLDUR (dosya adi degil): iki farkli klasordeki ayni-adli lamlar anahtari CARPISTIRMASIN
    diye (sadece log satirlarinda okunabilirlik icin dosya adi kullanilir)."""
    import numpy as np
    scores = {}
    for p in image_paths:
        info = {"tissue_fraction": None, "score": 0.0, "keypoints": None, "error": None}
        try:
            import pyvips
            thumb = pyvips.Image.thumbnail(p, 512)
            gray = thumb.colourspace("b-w")
            arr = np.ndarray(buffer=gray.write_to_memory(), dtype=np.uint8,
                              shape=[gray.height, gray.width, gray.bands])[:, :, 0]
            from skimage.filters import threshold_otsu
            thr = threshold_otsu(arr)
            tissue_fraction = float(np.mean(arr < thr))  # acik-zemin varsayimi (standart parlak-alan WSI)
            info["tissue_fraction"] = tissue_fraction
            dims = _slide_dims_and_mpp(p)
            if dims:
                w0, h0, mx, my = dims
                area_factor = (mx * my) if (mx and my) else 1.0
                info["score"] = tissue_fraction * w0 * h0 * area_factor
            else:
                info["score"] = tissue_fraction  # baslik okunamadi -> yalniz oran (kaba kiyas)
        except Exception as e:
            info["error"] = str(e)
        scores[p] = info
        emit_fn("  aday: " + os.path.basename(p) + " doku-orani=" +
                 ("%.3f" % info["tissue_fraction"] if info["tissue_fraction"] is not None else "?") +
                 " skor=" + ("%.6g" % info["score"]) +
                 (" (HATA: " + info["error"] + ")" if info["error"] else ""))
    ranked = [p for p in image_paths if p in scores]
    ranked.sort(key=lambda p: scores[p]["score"], reverse=True)
    if not ranked:
        return None, scores
    if len(ranked) >= 2:
        s0 = scores[ranked[0]]["score"]
        s1 = scores[ranked[1]]["score"]
        if s0 > 0 and abs(s0 - s1) / s0 < 0.05:
            emit_fn("  ilk iki aday skoru yakin (fark < %5) - anahtar-nokta yogunlugu ile ayirt ediliyor...")
            for p in (ranked[0], ranked[1]):
                nm = os.path.basename(p)
                try:
                    import cv2
                    import pyvips
                    thumb = pyvips.Image.thumbnail(p, 512)
                    gray = thumb.colourspace("b-w")
                    arr = np.ndarray(buffer=gray.write_to_memory(), dtype=np.uint8,
                                      shape=[gray.height, gray.width, gray.bands])[:, :, 0]
                    orb = cv2.ORB_create(nfeatures=800)
                    kp = orb.detect(arr, None)
                    kpn = len(kp) if kp is not None else 0
                    scores[p]["keypoints"] = kpn
                    emit_fn("    " + nm + ": anahtar-nokta=" + str(kpn))
                except Exception as e:
                    emit_fn("    " + nm + ": anahtar-nokta hesaplanamadi (" + str(e) + ") - siralama degismedi.")
            k0 = scores[ranked[0]].get("keypoints")
            k1 = scores[ranked[1]].get("keypoints")
            if k0 is not None and k1 is not None and k1 > k0:
                ranked[0], ranked[1] = ranked[1], ranked[0]
    best = ranked[0]
    emit_fn("Otomatik referans secildi: " + os.path.basename(best))
    return best, scores


def _find_registrar_pickle(dst):
    """dst altinda VALIS'in kaydettigi *_registrar.pickle'i bul (en yeni). Yoksa None."""
    import glob
    cands = glob.glob(os.path.join(dst, "**", "*_registrar.pickle"), recursive=True)
    if not cands:
        return None
    cands.sort(key=os.path.getmtime, reverse=True)
    if len(cands) > 1:
        emit("UYARI: birden fazla kayitli registrar bulundu, EN YENISI kullaniliyor: " + cands[0])
    return cands[0]


# Kaynak-kimlik yan-dosyasi: pickle'in yaninda orijinal --src'in mutlak yolunu tutar. Paylasilan cikti
# klasorunde iki ayri seri ayni govde adini (or. '...\svs') tasirsa ayni pickle yoluna yazip birbirini
# clobber'layabilir. reuse'da bu yan-dosya, YUKLENEN registrar'in GERCEKTEN bu kaynaga ait oldugunu
# dogrular -> sessiz-yanlis birlesimi HARD-FAIL'e cevirir (_check_warp_wh felsefesi). Eski (yan-dosyasiz)
# pickle'lar geriye-donuk kabul edilir (dogrulanamaz ama bozulmaz).
_SRC_SIDECAR_SUFFIX = "_atolye_src.txt"


def _write_src_sidecar(pkl_path, orig_src, emit_fn):
    """pkl_path'in yaninda <ad>_atolye_src.txt yaz (orijinal kaynak mutlak yolu)."""
    try:
        d = os.path.dirname(pkl_path)
        base = os.path.basename(pkl_path)
        stem = base[:-len("_registrar.pickle")] if base.endswith("_registrar.pickle") else base
        with open(os.path.join(d, stem + _SRC_SIDECAR_SUFFIX), "w", encoding="utf-8") as fh:
            fh.write(orig_src or "")
    except Exception:
        pass  # yan-dosya en-iyi-caba; yazilamamasi kayit/merge'i bozmamali


# Lam-listesi yan-dosyasi: pickle'in yaninda registrar'daki lam DOSYA ADLARINI tutar (satir basina bir).
# Groovy tarafi "bu hedef kayitli hizalamada var mi" on-kontrolunu (multi-hedef warp icin) dosya-tabanli,
# RESULT_JSON ayristirmadan yapabilsin diye. Eski (yan-dosyasiz) pickle'larda yumusak "bilinmiyor"a duser.
_SLIDES_SIDECAR_SUFFIX = "_atolye_slides.txt"


def _write_slides_sidecar(pkl_path, registrar, emit_fn):
    """pkl_path'in yaninda <ad>_atolye_slides.txt yaz (registrar lamlarinin basename'leri)."""
    try:
        d = os.path.dirname(pkl_path)
        base = os.path.basename(pkl_path)
        stem = base[:-len("_registrar.pickle")] if base.endswith("_registrar.pickle") else base
        names = [os.path.basename(f) for f in registrar.get_sorted_img_f_list()]
        with open(os.path.join(d, stem + _SLIDES_SIDECAR_SUFFIX), "w", encoding="utf-8") as fh:
            fh.write("\n".join(names))
    except Exception:
        pass  # en-iyi-caba; yazilamamasi kayit'i bozmamali


def _check_src_sidecar(pkl_path, orig_src, emit_fn):
    """reuse: yan-dosya varsa ve orijinal kaynakla ESLESMIYORSA ValueError firlat (sessiz-yanlis onlenir)."""
    try:
        d = os.path.dirname(pkl_path)
        base = os.path.basename(pkl_path)
        stem = base[:-len("_registrar.pickle")] if base.endswith("_registrar.pickle") else base
        sc = os.path.join(d, stem + _SRC_SIDECAR_SUFFIX)
        if not os.path.isfile(sc):
            emit_fn("Not: kayitli registrar'da kaynak-kimlik yan-dosyasi yok (eski calisma); kaynak dogrulanamiyor.")
            return
        with open(sc, "r", encoding="utf-8") as fh:
            stored = fh.read().strip()
    except Exception:
        return  # okunamiyorsa engelleme (best-effort dogrulama)
    cur = (orig_src or "").strip()
    if stored and cur and os.path.normcase(os.path.normpath(stored)) != os.path.normcase(os.path.normpath(cur)):
        raise ValueError(
            "Kayitli registrar BASKA bir kaynaktan uretilmis - sessiz yanlis birlesim onlendi.\n"
            "  kayitli kaynak: " + stored + "\n"
            "  guncel kaynak : " + cur + "\n"
            "(Paylasilan cikti klasorunde ayni govde adli iki seri birbirini clobber'lamis olabilir.)\n"
            "Bu seri icin tam (yeniden) kayit yapmak uzere --reuse-registrar bayragini kaldirin.")


def _ensure_registrar_pickle(registrar, orig_src, emit_fn):
    """VALIS register() govdesi try/except ile SARILIDIR: measure_error/cleanup adimi (ozellikle rigid-only'de)
    hata verirse traceback basar, exception'i YUTAR, register() normal doner AMA pickle.dump adimi ATLANIR ->
    registrar hic saklanmaz. reuse icin pickle'i GARANTILE: yoksa en-iyi-caba ile kaydet + yan-dosyayi yaz."""
    try:
        import pickle
        data_dir = getattr(registrar, "data_dir", None)
        name = getattr(registrar, "name", None)
        if not data_dir or name is None:
            return
        pkl = os.path.join(data_dir, str(name) + "_registrar.pickle")
        if os.path.isfile(pkl):
            _write_src_sidecar(pkl, orig_src, emit_fn)      # VALIS kaydetti; yalniz yan-dosyalari ekle
            _write_slides_sidecar(pkl, registrar, emit_fn)
            return
        emit_fn("Not: VALIS registrar'i otomatik saklamadi (muhtemelen rigid-only olcum adimi); reuse icin en-iyi-caba ile kaydediliyor...")
        try:
            registrar.cleanup()  # picklelenemeyen nesneleri temizle (cleanup zaten calismadiysa)
        except Exception:
            pass
        os.makedirs(data_dir, exist_ok=True)
        with open(pkl, "wb") as fh:
            pickle.dump(registrar, fh)
        try:
            registrar.reg_f = pkl
        except Exception:
            pass
        _write_src_sidecar(pkl, orig_src, emit_fn)
        _write_slides_sidecar(pkl, registrar, emit_fn)
        emit_fn("Registrar saklandi (reuse etkin): " + pkl)
    except Exception as e:
        emit_fn("UYARI: registrar saklanamadi - bu calisma reuse EDILEMEZ (tam non-rigid calisma reuse'u etkinlestirir): " + str(e))


def _stage_images(images, stage_root, emit_fn):
    """images'i stage_root'a KOPYALA (idempotent: ayni boyutta zaten varsa atla). {orijinal: stage} dondurur.
    Amac: lamlari yavas Windows-diski (/mnt/...) yerine WSL-yerel diskten okumak -> I/O ~6x hizli."""
    import shutil
    os.makedirs(stage_root, exist_ok=True)
    stage_map = {}
    for f in images:
        base = os.path.basename(f)
        dstf = os.path.join(stage_root, base)
        try:
            same = os.path.isfile(dstf) and os.path.getsize(dstf) == os.path.getsize(f)
        except OSError:
            same = False
        if same:
            emit_fn("Staging: zaten var, atlaniyor: " + base)
        else:
            emit_fn("Staging: kopyalaniyor " + base + " -> " + stage_root)
            shutil.copy2(f, dstf)
        stage_map[f] = dstf
    return stage_map


def _run_one_case(args):
    """Tek 'vaka' calistir: kayit (veya kayitli registrar'i yeniden-kullan) + anotasyon warp + OME
    yazma + merge. result dict DONER (basari: ok=True; hata: ok=False + error) - HATAYI FIRLATMAZ,
    boylece cmd_batch bir vaka basarisiz olsa bile digerlerine SIRALI devam edebilir (her vaka kendi
    HATA dalinda yakalanir, tipki tek-calisma cmd_run'daki gibi).

    ONEMLI: _kill_jvm() BURADA CAGRILMAZ. VALIS'in paylasilan Bio-Formats JVM'ini kapatmak CAGIRANIN
    (cmd_run: TEK calisma sonunda bir kez; cmd_batch: TUM vakalar bittikten SONRA bir kez) isidir.
    Bu fonksiyonu bir dongude cagirip HER vakadan sonra _kill_jvm() cagirmak JVM'i bozar/yavaslatir -
    bu refaktorun EN KRITIK tuzagi budur (bkz. cmd_batch)."""
    result = {"ok": False, "cmd": "run", "ome_files": []}
    try:
        # QuPath tarafinin (WSL modunda) surecin GERCEK PID'ini bulup dogrulanmis bir
        # kill -TERM/-KILL gonderebilmesi icin: wsl.exe cephesini oldurmek ic surecin
        # olumunu GARANTI ETMEZ (bilinen WSL2 mimari acigi). Ayirt edici onek (ATOLYE_PID)
        # kullan; genel bir 'PID:' alt-dizgesi gelecekte baska bir satirla YANLIS eslesmesin.
        emit("ATOLYE_PID: " + str(os.getpid()))
        _stage_begin("HAZIRLIK", "Hazirlik (ortam ayarlari + klasorler)")
        if getattr(args, "cpu", False):
            # VALIS 1.2.0 DiskFD, CUDA'da res.keypoints.detach().numpy() ile coker (Tensor.cpu() cagirmaz).
            # torch import'undan ONCE gizle → torch.cuda.is_available()=False → VALIS CPU kullanir.
            # Kayit kucuk (~512 px) goruntulerde yapildigi icin CPU hiz kaybi ihmal edilebilir.
            os.environ["CUDA_VISIBLE_DEVICES"] = "-1"
            emit("Cihaz: CPU (--cpu). Kayit kucuk goruntulerde CPU'da da hizlidir; GPU sarti degildir.")
        if _PATH_REMOVED:
            emit("PATH temizlendi (DLL cakismasi onlemi) - cikarilan: " + ", ".join(_PATH_REMOVED))
        from valis import registration
        _p1 = _fix_pyvips_numpy()
        # best-page okuma yamasi YALNIZ Windows icindir (orada tam-res materiyalize etmek torch<->libvips
        # DLL catismasiyla 0xC0000005 cokerdi). Linux/mac'te bu cokme YOK; ustelik yama'nin dondurdugu kucuk
        # sayfa VALIS'in ROI/crop matematigini (get_roi_for_processing -> extract_area) bozup kaydi kimlik
        # (identity) donusumune dusurur. Bu yuzden Windows disinda uygulanmaz -> VALIS normal okur, gercek kayit yapar.
        _p2 = _fix_vips_slide_read() if os.name == "nt" else False
        if _p1 or _p2:
            emit("Yama uygulandi (0xC0000005 onlenir): is_pyvips_22=" + str(_p1) + ", sequential-slayt-okuma=" + str(_p2) + ".")
        os.makedirs(args.dst, exist_ok=True)
        os.makedirs(args.ome, exist_ok=True)
        _stage_end("HAZIRLIK")

        # Orijinal --src (staging args.src'yi degistirmeden ONCE): registrar kaynak-kimlik yan-dosyasi ve reuse
        # dogrulamasi bunu kullanir. Paylasilan cikti klasorunde ayni govde adli iki seriyi ayirt eder.
        import hashlib as _hashlib
        _orig_src = os.path.abspath(args.src)
        _src_token = _hashlib.sha1(_orig_src.encode("utf-8", "replace")).hexdigest()[:8]

        # ── Stage: lamlari WSL-yerel diske kopyala (ilk kayit I/O'sunu ~6x hizlandirir) ──
        # stage kokunu KAYNAGA-OZGU bir alt-klasore (_src_token) koy -> ayni govde adli iki farkli seri birbirinin
        # stage'lenmis lamlarini clobber'lamaz. Yaprak govde adi (VALIS 'name'i) DEGISMEDEN kalir; boylece
        # registrar pickle adi (ve --reuse-registrar aramasi) tutarli kalir.
        if getattr(args, "stage", False) and not getattr(args, "reuse_registrar", False):
            _stage_root = os.path.join(_ATOLYE_DATA_ROOT, "stage", _src_token, os.path.basename(args.src.rstrip("/\\")))
            _imgs = list(args.images) if getattr(args, "images", None) else []
            for _extra in (getattr(args, "reference", None), getattr(args, "src_slide", None)):  # skalar
                if _extra and _extra not in _imgs:
                    _imgs.append(_extra)
            for _t in (getattr(args, "target_slide", None) or []):  # target_slide artik LISTE
                if _t and _t not in _imgs:
                    _imgs.append(_t)
            if _imgs:
                _stage_begin("STAGING", "Lamlar hizli (WSL-yerel) diske kopyalaniyor")
                emit("Lamlar hizli (WSL-yerel) diske stage'leniyor: " + _stage_root)
                _smap = _stage_images(_imgs, _stage_root, emit)
                if getattr(args, "images", None):
                    args.images = [_smap.get(x, x) for x in args.images]
                if getattr(args, "reference", None):
                    args.reference = _smap.get(args.reference, args.reference)
                if getattr(args, "src_slide", None):
                    args.src_slide = _smap.get(args.src_slide, args.src_slide)
                if getattr(args, "target_slide", None):
                    args.target_slide = [_smap.get(t, t) for t in args.target_slide]  # LISTE olarak remap
                args.src = _stage_root
                emit("Stage tamam. Kayit stage klasorunden okuyacak.")
                _stage_end("STAGING")

        emit("VALIS kayit basliyor. Girdi klasoru: " + args.src)
        emit("Not: 'Processing images' asamasi (ozellik eslestirme + rigid/non-rigid kayit) SESSIZ olabilir;")
        emit("     asagida her ~20 sn bir nabiz basilir. " + ("Hizalama CPU'da calisiyor (--cpu) — nvidia-smi bos gorunur, normaldir." if getattr(args, "cpu", False) else "Ilerleme cogunlukla GPU'da olur (nvidia-smi dmon ile izlenebilir)."))

        # ── Otomatik referans (yalniz OLCUM - kayit YAPMAZ). Acik --reference DAIMA kazanir; bu blok
        # yalniz --auto-reference istenmis VE --reference verilmemisse calisir. ──
        if not getattr(args, "reference", None) and getattr(args, "auto_reference", False):
            _ref_cands = list(args.images) if getattr(args, "images", None) else sorted(
                os.path.join(args.src, _f) for _f in os.listdir(args.src) if _f.lower().endswith(_WSI_EXTS))
            if len(_ref_cands) >= 2:
                emit("Otomatik referans hesaplaniyor (doku-orani x baslik-alani; yalniz olcum)...")
                _auto_ref, _ref_scores = _pick_reference(_ref_cands, emit)
                if _auto_ref:
                    args.reference = _auto_ref
                    result["reference_scores"] = _ref_scores
            else:
                emit("Otomatik referans atlandi (2'den az aday lam bulundu); VALIS kendi varsayilanini kullanacak.")

        vkwargs = {}
        if getattr(args, "images", None):
            vkwargs["img_list"] = list(args.images)
        if getattr(args, "reference", None):
            vkwargs["reference_img_f"] = args.reference
            result["reference_used"] = os.path.basename(args.reference)
            emit("Referans lam: " + os.path.basename(args.reference))
        if getattr(args, "max_processed_dim", None):
            vkwargs["max_processed_image_dim_px"] = int(args.max_processed_dim)
        if getattr(args, "max_nonrigid_dim", None):
            vkwargs["max_non_rigid_registration_dim_px"] = int(args.max_nonrigid_dim)
        if getattr(args, "correct_scale", False):
            # DOGRU kanca (VALIS 1.2.0 kaynagindan dogrulandi): Valis(transformer_cls=<skimage Transform SINIFI>)
            # — VALIS icten transformer() ile ornekler (_set_rigid_reg_kwargs). Onceki surum var olmayan
            # 'rigid_reg_kwargs' anahtarini gecirip TypeError'a dusuyordu. NOT: 1.2.0'da varsayilan rigid
            # ZATEN SimilarityTransform'dur (olcek tahminli; DEFAULT_TRANSFORM_CLASS) — bu bayrak davranisi
            # surumden bagimsiz ACIKCA sabitler; tahmini olcekler asagida her durumda raporlanir.
            try:
                from skimage.transform import SimilarityTransform as _SimTf
                vkwargs["transformer_cls"] = _SimTf
                emit("Olcek tahminli rigid SABITLENDI (--correct-scale): benzerlik (similarity) donusumu kullanilacak "
                     "(VALIS 1.2.0'da bu zaten varsayilandir; bayrak surum degisikliklerine karsi garanti verir).")
            except Exception as _sce:
                emit("UYARI: olcek sabitleme kurulamadi (" + str(_sce) + "); VALIS varsayilan rigid'i ile devam.")
        if getattr(args, "rigid_only", False):
            # VALIS varsayilan non-rigid'i OpenCV optical flow (OpticalFlowWarper) kullanir; bu makinede
            # native olarak 0xC0000005 ile coker. Yalniz rigid kayit -> calisir; anotasyon warp'i rigid
            # donusumu kullanir (seri kesitlerde cogu zaman yeterli). Tam non-rigid icin Docker onerilir.
            vkwargs["non_rigid_registrar_cls"] = None
            emit("Yalniz RIGID kayit (--rigid-only): non-rigid (OpenCV optical flow) asamasi atlaniyor.")
        # ── Kayit: yeniden-kullan (kayitli registrar) VEYA sifirdan kayit ──
        registrar = None
        if getattr(args, "reuse_registrar", False):
            _pkl = _find_registrar_pickle(args.dst)
            if _pkl:
                _stage_begin("YUKLEME", "Kayitli hizalama (registrar) yukleniyor")
                emit("Kayitli registrar kullaniliyor (YENIDEN KAYIT YOK): " + _pkl)
                registrar = registration.load_registrar(_pkl)
                if getattr(args, "images", None):
                    _loaded = sorted(os.path.basename(f) for f in registrar.get_sorted_img_f_list())
                    _wanted = sorted(os.path.basename(x) for x in args.images)
                    if _loaded != _wanted:
                        raise ValueError(
                            "Kayitli registrar'daki slayt seti GUNCEL secimden FARKLI - sessiz yanlis birlesim onlendi.\n"
                            "  yuklenen: " + ", ".join(_loaded) + "\n"
                            "  guncel  : " + ", ".join(_wanted) + "\n"
                            "Tam (yeniden) kayit icin --reuse-registrar bayragini kaldirin.")
                # Lam adlari CAKISABILIR (iki seride ayni dosya adlari) -> kaynak-kimlik yan-dosyasiyla da dogrula.
                _check_src_sidecar(_pkl, _orig_src, emit)
                _stage_end("YUKLEME")
            else:
                emit("UYARI: --reuse-registrar istendi ama kayitli registrar bulunamadi (" + args.dst + "); TAM kayit yapilacak.")
        if registrar is None:
            if getattr(args, "images", None):
                emit("Kayda dahil slaytlar (%d): %s" % (len(args.images), ", ".join(os.path.basename(x) for x in args.images)))
            _stage_begin("KAYIT", "Kayit (donusturme + ozellik eslestirme + rigid/non-rigid + hata olcumu)")
            registrar = registration.Valis(args.src, args.dst, **vkwargs)
            hb = _start_heartbeat("Kayit")
            try:
                registrar.register()
            finally:
                hb.set()
            emit("Kayit tamamlandi.")
            # VALIS register() bir hatayi YUTUP pickle kaydini atlayabilir (ozellikle rigid-only); reuse icin
            # pickle'i garantile + kaynak-kimlik yan-dosyasini yaz.
            _ensure_registrar_pickle(registrar, _orig_src, emit)
            _stage_end("KAYIT")
        else:
            emit("Kayit atlandi (kayitli registrar) - yeniden birlestirme.")
        # Merge/warp'ta non-rigid: varsayilan --rigid-only'e gore (sifirdan calistirmayla AYNI -> orijinal merge'i eslesir).
        # Kayitli registrar'da non_rigid_registrar_cls pickle sonrasi GUVENILMEZ (non-rigid yapilsa bile None olur);
        # gercek gosterge referans-disi bir lamin bk_dxdy (deformasyon alani) VARLIGIDIR. Yalniz gercekten
        # non-rigid YOKSA kapat (rigid-only registrar'da warp non_rigid=True cokmesini onlemek icin).
        non_rigid_merge = (not getattr(args, "rigid_only", False))
        if getattr(args, "reuse_registrar", False) and non_rigid_merge:
            try:
                _ref = registrar.get_ref_slide().name
                _has_nr = any(getattr(registrar.slide_dict[n], "bk_dxdy", None) is not None
                              for n in registrar.slide_dict if n != _ref)
                if not _has_nr:
                    non_rigid_merge = False
                    emit("Kayitli registrar'da non-rigid deformasyon yok (rigid-only) - non-rigid warp atlaniyor.")
                else:
                    emit("Kayitli registrar non-rigid iceriyor - non-rigid warp uygulanacak (orijinal hizalamayla ayni).")
            except Exception as _e:
                # Belirlenemedi -> GUVENLI tarafa dus (rigid): non_rigid=True warp'in rigid-only registrar'da
                # cokmesini onle. Sessizce riskli varsayilanda birakma (_check_warp_wh fail-safe felsefesi).
                non_rigid_merge = False
                emit("Not: kayitli registrar'da non-rigid varligi belirlenemedi (" + str(_e) + ") - guvenli rigid warp kullanilacak.")

        # ── um/px + (olcek duzeltmede) tahmini olcek raporu + istege bagli ince hizalama (register_micro) ──
        try:
            _report_resolution_and_scale(registrar, getattr(args, "correct_scale", False), emit)
        except Exception as _rre:
            emit("Not: um/px-olcek raporu uretilemedi (" + str(_rre) + ").")
        if getattr(args, "micro", False):
            if getattr(args, "rigid_only", False):
                emit("UYARI: ince hizalama (--micro) non-rigid gerektirir; --rigid-only ile birlikte KULLANILAMAZ - atlaniyor.")
            elif getattr(args, "reuse_registrar", False):
                emit("UYARI: ince hizalama (--micro) kayitli registrar ile bu surumde atlaniyor; TAZE kayit (--reuse-registrar'siz) ile kullanin.")
            else:
                _run_micro(registrar, args, emit)
                non_rigid_merge = True   # micro non-rigid deformasyon ekler -> merge/warp non-rigid kullanmali
        # VALIS'in kendi hata tablosu (varsa) - salt-okur QC ozeti (goruntu/Dice degil, ozellik-mesafesi).
        _emit_error_summary(args.dst, emit)

        # Anotasyon warp'i ONCE yap: kullanicinin BIRINCIL ciktisidir + hizlidir (yalniz koordinat donusumu).
        # OME-TIFF yazimi (tam-res hizalanmis lamlar) YAVAS + ikincildir; sonra ve --no-ome ile istege
        # bagli yapilir. Boylece OME yavas/basarisiz olsa bile warp'li anotasyon zaten yazilmis olur.
        # BIR kaynaktan BIR VEYA DAHA FAZLA hedefe warp: target_slide/geojson_out artik LISTE (sirali eslesir).
        _targets = args.target_slide or []
        _gjouts = args.geojson_out or []
        want_warp = bool(args.geojson_in) and bool(args.src_slide) and bool(_targets) and bool(_gjouts)
        if want_warp and len(_targets) != len(_gjouts):
            emit("UYARI: --target-slide sayisi (%d) --geojson-out sayisi (%d) ile eslesmiyor; warp atlaniyor." % (len(_targets), len(_gjouts)))
            want_warp = False
        if want_warp:
            _stage_begin("WARP", "Anotasyon warp (koordinat donusumu)")
            src_slide = registrar.get_slide(args.src_slide)
            _warp_results = []
            _ok = 0
            for _tgt, _gjo in zip(_targets, _gjouts):
                # her hedef KENDI try/except'inde: biri patlarsa digerleri + merge/OME asamalari etkilenmez.
                try:
                    emit("Anotasyon warp: " + str(args.src_slide) + " -> " + str(_tgt))
                    tgt_slide = registrar.get_slide(_tgt)
                    warped = src_slide.warp_geojson_from_to(args.geojson_in, tgt_slide)
                    gj_dir = os.path.dirname(_gjo)
                    if gj_dir:
                        os.makedirs(gj_dir, exist_ok=True)
                    with open(_gjo, "w", encoding="utf-8") as fh:
                        json.dump(warped, fh)
                    _ok += 1
                    _warp_results.append({"target": _tgt, "geojson_out": _gjo, "ok": True})
                    emit("Warp'lanmis GeoJSON yazildi: " + _gjo)
                except Exception as _we:
                    _warp_results.append({"target": _tgt, "geojson_out": _gjo, "ok": False, "error": str(_we)})
                    emit("HATA (bu hedef atlandi): " + str(_tgt) + " - " + str(_we))
            result["warp_results"] = _warp_results
            if _warp_results:
                result["geojson_out"] = _warp_results[0]["geojson_out"]  # geriye-donuk tek-hedef alani
            emit("Anotasyon warp ozeti: %d/%d hedef basarili." % (_ok, len(_targets)))
            _stage_end("WARP")
        else:
            emit("Anotasyon warp atlandi (geojson/slayt argumanlari verilmedi).")

        if getattr(args, "no_ome", False):
            emit("OME-TIFF yazimi atlandi (--no-ome). Yalniz warp'li anotasyon uretildi.")
        else:
            _stage_begin("OME", "Hizalanmis lamlari OME-TIFF olarak yazma")
            emit("Hizalanmis lamlar OME-TIFF olarak yaziliyor (crop=" + str(args.crop) + "): " + args.ome)
            hb2 = _start_heartbeat("OME yazma")
            try:
                registrar.warp_and_save_slides(args.ome, crop=args.crop)
            finally:
                hb2.set()
            ome_files = []
            for f in sorted(os.listdir(args.ome)):
                fl = f.lower()
                if fl.endswith(".ome.tiff") or fl.endswith(".ome.tif"):
                    ome_files.append(os.path.join(args.ome, f))
            result["ome_files"] = ome_files
            emit("OME-TIFF sayisi: " + str(len(ome_files)))
            _stage_end("OME")

        if getattr(args, "merge", False):
            _stage_begin("MERGE", "Birlesik/cok-kanalli cikti yazma")
            merge_out = args.merge_out or os.path.join(args.ome, "merged_overlay.ome.tiff")
            md = os.path.dirname(merge_out)
            if md:
                os.makedirs(md, exist_ok=True)
            mmode = getattr(args, "merge_mode", "hed") or "hed"
            # non_rigid_merge yukarida (kayit blogunda) belirlendi: --rigid-only VEYA kayitli registrar rigid-only.
            # Marker adlari: --merge-name KEY=MARKER (KEY = lam dosya adi/govdesi). Lam
            # sirasi VALIS'te yeniden siralanabildigi icin ada gore eslesme (indekse gore DEGIL).
            name_map = {}
            for _item in (getattr(args, "merge_name", None) or []):
                if "=" in _item:
                    _k, _v = _item.split("=", 1)
                    try:
                        from valis import valtils as _vt
                        _k = _vt.get_name(os.path.basename(_k))
                    except Exception:
                        pass
                    name_map[_k] = _v
                else:
                    emit("UYARI: --merge-name yok sayildi (KEY=MARKER bekleniyor): " + str(_item))
            # Slayt-basi boya tipi override: --merge-stain KEY=TYPE (hed|he|dab|rgb). H&E slaytlari
            # H-DAB vektorleriyle ayristirmak SAHTE bir DAB kanali uretir; H&E icin 'he' (Hem+Eozin) verin.
            # Hatali/taninmayan girdiler SESSIZ dusmesin -> uyar (aksi halde H&E slayti sessizce fake-DAB olur).
            stain_map = {}
            for _item in (getattr(args, "merge_stain", None) or []):
                if "=" not in _item:
                    emit("UYARI: --merge-stain yok sayildi (KEY=TIP bekleniyor): " + str(_item))
                    continue
                _k, _v = _item.split("=", 1)
                try:
                    from valis import valtils as _vt2
                    _k = _vt2.get_name(os.path.basename(_k))
                except Exception:
                    pass
                _v = (_v or "").strip().lower()
                if _v in _VALID_STAINS:
                    stain_map[_k] = _v
                else:
                    emit("UYARI: --merge-stain gecersiz boya tipi '" + str(_v) + "' (gecerli: " +
                         ",".join(_VALID_STAINS) + ") -> '" + str(_k) + "' global varsayilan moda dusecek.")
            # Marker rengi (kullanici secer): --merge-color MARKER=RRGGBB (hex). O marker'in ISARET
            # kanalinin (DAB/Eozin) OME/QuPath rengi olur; bulunmazsa otomatik palet. KEY = marker adi
            # (kanal on-eki), dosya adi DEGIL. Composite'i ETKILEMEZ (o gercek boya absorbansini kullanir).
            color_map = {}
            for _item in (getattr(args, "merge_color", None) or []):
                if "=" not in _item:
                    emit("UYARI: --merge-color yok sayildi (MARKER=RRGGBB bekleniyor): " + str(_item))
                    continue
                _k, _hex = _item.rsplit("=", 1)
                _hex = (_hex or "").strip().lstrip("#")
                if len(_hex) == 6:
                    try:
                        color_map[_k] = (int(_hex[0:2], 16), int(_hex[2:4], 16), int(_hex[4:6], 16))
                    except ValueError:
                        emit("UYARI: --merge-color gecersiz hex '" + str(_hex) + "' -> '" + str(_k) + "' otomatik renk kullanacak.")
                else:
                    emit("UYARI: --merge-color 6-hane hex bekliyor '" + str(_hex) + "' -> '" + str(_k) + "' otomatik renk kullanacak.")
            hb3 = _start_heartbeat("Merge yazma")
            try:
                if getattr(args, "composite", False):
                    # DOGAL-RENK parlak-alan RGB bilesik (Beer-Lambert). 'Lam gibi gozuksun' icin;
                    # cok slaytta sentetik (cakisan boyalar koyulasir). Slayt-basi boya tipleri korunur.
                    emit("Dogal-renk parlak-alan bilesik (composite, level=" + str(args.merge_level) + "): " + merge_out)
                    _names = _merge_slides_composite(registrar, merge_out, args.merge_level,
                                                     non_rigid_merge, args.crop, mmode, name_map, stain_map, emit)
                    result["composite"] = True
                else:
                    # Cok-kanalli renk-ayristirmali merge; 'rgb' de burada islenir (ham RGB gecis, tek
                    # tutarli kod yolu + dogal kanal renkleri). Slayt-basi stain_map > global varsayilan
                    # (mmode): İHK -> Hem+DAB, H&E -> Hem+Eozin. (Eski VALIS-native RGB yolu kaldirildi.)
                    emit("Renk-ayristirmali merge (varsayilan mode=" + str(mmode) + ", lam-basi override=" + json.dumps(stain_map) + ", level=" + str(args.merge_level) + "): " + merge_out)
                    _names = _merge_slides_deconvolved(registrar, merge_out, args.merge_level,
                                                       non_rigid_merge, args.crop, mmode, name_map, stain_map, emit,
                                                       color_map=color_map)
                result["merge_channels"] = _names
                emit("Merge kanallari: " + json.dumps(_names))
            finally:
                hb3.set()
            result["merge_out"] = merge_out
            result["merge_mode"] = mmode
            emit("Merge yazildi (QuPath'te cok-kanalli/multiplex olarak acilir): " + merge_out)
            _stage_end("MERGE")

        result["ok"] = True
    except Exception as e:
        _stage_begin("HATA", "Hata isleniyor")
        result["error"] = str(e)
        emit("HATA: " + repr(e))
        traceback.print_exc()
        _stage_end("HATA")
    return result


def _report_resolution_and_scale(registrar, correct_scale, emit):
    """Her lamin um/px (fiziksel piksel boyutu) degerini raporlar; farkli/eksik olanlari UYARIR.
    Rigid asamada TAHMIN EDILEN olcek HER ZAMAN yazilir (VALIS 1.2.0 varsayilan rigid'i benzerlik/olcek
    tahminlidir; correct_scale bayragindan bagimsiz) — kullanici olcegin ~1.0 civari mi yoksa asiri mi
    duzeltildigini gorebilir (max_scaling=3.0 bir guvenlik siniri, dogrulayici DEGIL)."""
    try:
        ref = registrar.get_ref_slide().name
    except Exception:
        ref = None
    res_list = []
    for name in registrar.slide_dict:
        slide = registrar.slide_dict[name]
        res = getattr(slide, "resolution", None)
        units = getattr(slide, "units", "") or "um"
        try:
            resf = float(res)
        except (TypeError, ValueError):
            resf = None
        res_list.append((name, resf))
        line = "  " + name + ": " + (("%.4f " % resf) + str(units) + "/px" if (resf is not None and resf == resf) else "um/px OKUNAMADI")
        if name != ref:
            try:
                from skimage import transform as _sktf
                s = float(_sktf.SimilarityTransform(slide.M).scale)
                line += "  (tahmini olcek: %.3f)" % s
            except Exception:
                pass
        emit(line)
    vals = [v for _, v in res_list if v is not None and v == v and v > 0]
    if len(vals) >= 2:
        lo, hi = min(vals), max(vals)
        if (hi / lo) > 1.02:
            emit("UYARI: lamlarin um/px degerleri FARKLI (en dusuk %.4f, en yuksek %.4f; fark ~%.1f%%). "
                 "Farkli tarayicilar (or. AT2 vs GT450) olabilir - '--correct-scale' (Olcek/um-px farkini duzelt) "
                 "ile rigid asama olcegi kendisi tahmin edip duzeltir." % (lo, hi, (hi / lo - 1.0) * 100.0))
    missing = [n for n, v in res_list if v is None or v != v]
    if missing:
        emit("UYARI: su lamlarda um/px KALIBRASYONU okunamadi: " + ", ".join(missing)
             + " - kalibrasyon eksikse olcek/hizalama guvenilmez (QuPath Image sekmesinden ayarlayin).")


def _micro_focus_mask(registrar, args, emit):
    """--micro-focus: --geojson-in'deki anotasyonu (kaynak lamin seviye-0 piksel uzayi) KAYITLI referans
    cercevesine warp edip ikili maske olarak dondurur - register_micro(mask=...) yalniz o bolgede inceltir.
    Koordinat sozlesmesi (VALIS 1.2.0 kaynagi + resmi ornekler sayfasindan dogrulandi):
    Slide.warp_geojson(geojson_f, slide_level=ref.reg_img_shape_rc, pt_level=0, non_rigid=True, crop=False)
    ciktiyi dogrudan non_rigid_reg_mask'in yasadigi KIRPILMAMIS kayitli cerceveye verir; herhangi bir lamin
    Slide nesnesi ayni PAYLASILAN kayitli uzaya varir (referans olmasi gerekmez). crop=True/overlap/reference
    kokeni kaydirir ve maske SESSIZCE yanlis hizalanir - kullanilmaz. Basarisizlik fail-safe'tir: None doner,
    ince hizalama TUM-doku olarak devam eder (yanlis maske hic maskeden kotudur)."""
    try:
        import numpy as np
        import cv2
        ref = registrar.get_ref_slide()
        shape_rc = tuple(int(v) for v in ref.reg_img_shape_rc)
        slide_obj = registrar.get_slide(args.src_slide)
        warped = slide_obj.warp_geojson(args.geojson_in, slide_level=shape_rc, pt_level=0,
                                        non_rigid=True, crop=False)
        mask = np.zeros(shape_rc, dtype=np.uint8)
        n_poly = 0
        for ft in (warped.get("features") or []):
            geom = ft.get("geometry") or {}
            gtype = geom.get("type")
            coords = geom.get("coordinates")
            rings = []
            if gtype == "Polygon" and coords:
                rings.append(coords[0])          # dis halka (delikler kaba maskede onemsiz)
            elif gtype == "MultiPolygon" and coords:
                for poly in coords:
                    if poly:
                        rings.append(poly[0])
            for ring in rings:
                pts = np.round(np.asarray(ring, dtype=float)).astype(np.int32)
                if pts.ndim == 2 and pts.shape[0] >= 3:
                    cv2.fillPoly(mask, [pts.reshape((-1, 1, 2))], 255)
                    n_poly += 1
        cov = float((mask > 0).sum()) / float(mask.size) * 100.0
        if n_poly == 0 or cov <= 0.0:
            emit("UYARI: bolge odagi maskesi BOS (alanli/poligon anotasyon yok ya da bolge cerceve disina "
                 "dustu) - TUM-doku ince hizalama yapilacak.")
            return None
        emit("Bolge odagi maskesi hazir: %d poligon, kayitli cerceve %dx%d px, kapsama ~%.1f%%."
             % (n_poly, shape_rc[1], shape_rc[0], cov))
        return mask
    except Exception as _me:
        emit("UYARI: bolge odagi maskesi olusturulamadi (" + str(_me) + ") - TUM-doku ince hizalama yapilacak.")
        return None


def _emit_error_summary(dst, emit):
    """VALIS'in KENDI hata tablosunu (data/<ad>_summary.csv; measure_error ciktisi) okuyup lam-basi kisa
    ozet yazar. '_D' ile biten sutunlar eslesen ozellikler arasi MEDYAN mesafedir (fiziksel birim, ~um) -
    kucuk = iyi; original_ oncesi, rigid_/non_rigid_ sonrasidir. Salt-okur + best-effort: tablo yoksa ya da
    okunamazsa sessizce gecilir (kayit sonucunu etkilemez). Yeniden-kullanim yolunda sayilar, kullanilan
    KAYITLI hizalamaya aittir (o da dogru olan budur)."""
    try:
        import csv
        import glob as _glob
        cands = _glob.glob(os.path.join(dst, "**", "*_summary.csv"), recursive=True)
        if not cands:
            return
        newest = max(cands, key=os.path.getmtime)
        with open(newest, "r", encoding="utf-8", newline="") as fh:
            rows = list(csv.DictReader(fh))
        if not rows:
            return
        cols = [c for c in rows[0].keys() if c and c.endswith("_D")][:6]
        if not cols:
            return
        emit("Kayit hata olcumleri (VALIS measure_error; '_D' = eslesen ozellik MEDYAN mesafesi, fiziksel birim - kucuk=iyi):")
        for r in rows:
            frm = r.get("from") or r.get("name") or "?"
            to = r.get("to") or ""
            parts = []
            for c in cols:
                try:
                    parts.append(c + "=%.1f" % float(r.get(c)))
                except (TypeError, ValueError):
                    pass
            if parts:
                emit("  " + str(frm) + ((" -> " + str(to)) if to else "") + ": " + ", ".join(parts))
        emit("  (tablo: " + newest + ")")
    except Exception as _ese:
        emit("Not: hata-olcum tablosu okunamadi (" + str(_ese) + ").")


def _run_micro(registrar, args, emit):
    """register_micro: ana hizalamadan sonra, DAHA YUKSEK cozunurlukte 2. bir non-rigid gecis yaparak
    'az kaymis' hizalamayi sikilastirir. Micro kenari, baslangic non-rigid kenarindan (registrar'in
    max_non_rigid_registration_dim_px'i; VALIS varsayilani 2048) BUYUK olMALI - degilse VALIS sessizce
    None dondurur (islem yapilmamis gibi gorunur), o yuzden burada ACIKCA dogrulanir. --micro-focus ile
    (--geojson-in + --src-slide gerekli) inceltme yalniz anotasyon BOLGESIYLE sinirlanir (maske)."""
    init_nr = getattr(registrar, "max_non_rigid_registration_dim_px", 2048) or 2048
    micro_dim = getattr(args, "micro_dim", None)
    micro_dim = int(micro_dim) if micro_dim else 4096   # VALIS DEFAULT_MAX_MICRO_REG_SIZE
    if micro_dim <= int(init_nr):
        emit("UYARI: ince hizalama kenari (%d px) baslangic non-rigid kenarindan (%d px) BUYUK olmali; "
             "ince hizalama ATLANIYOR (--micro-dim degerini artirin)." % (micro_dim, int(init_nr)))
        return
    mask = None
    if getattr(args, "micro_focus", False):
        if getattr(args, "geojson_in", None) and getattr(args, "src_slide", None):
            mask = _micro_focus_mask(registrar, args, emit)
        else:
            emit("UYARI: bolge odagi (--micro-focus) icin --geojson-in ve --src-slide gerekli - TUM-doku ince hizalama yapilacak.")
    _label = "BOLGE-ODAKLI ince hizalama" if mask is not None else "Ince hizalama"
    _stage_begin("INCE", _label + " (register_micro %d px - yuksek cozunurluklu 2. non-rigid gecis)" % micro_dim)
    hb = _start_heartbeat("Ince hizalama")
    try:
        if mask is not None:
            registrar.register_micro(max_non_rigid_registration_dim_px=micro_dim, mask=mask)
        else:
            registrar.register_micro(max_non_rigid_registration_dim_px=micro_dim)
        emit(_label + " tamamlandi (register_micro, kenar=%d px)." % micro_dim)
    except Exception as _me:
        emit("UYARI: ince hizalama basarisiz (" + str(_me) + "); ana hizalama ile devam.")
    finally:
        hb.set()
    _stage_end("INCE")


def cmd_run(args):
    try:
        result = _run_one_case(args)
    finally:
        _kill_jvm()   # tek calisma: bir vaka -> bir kez kapat (bkz. _run_one_case docstring)
    emit("RESULT_JSON: " + json.dumps(result))
    return 0 if result["ok"] else 1


# ── Toplu isleme (batch): birden cok vaka klasoru tek surecte, SIRALI ──────────────────────────
# Manifest JSON dosyasi ('--manifest'): {"common": {...ortak bayraklar...}, "cases": [{"name","src",
# "dst","ome",...}, ...]}. common + her vakanin kendi alanlari (case ONCELIKLI) birlestirilip
# _run_one_case'in bekledigi ad alanlariyla (--src -> "src" vb, argparse dest adlariyla AYNI) bir
# Namespace olusturulur. TUM yollar (src/dst/ome/images/...) Groovy tarafinca ZATEN doğru bicimde
# (container /work/..., WSL /mnt/x/... veya host) yazilir - burada donusum YAPILMAZ.
_RUN_ARG_DEFAULTS = {
    "src": None, "dst": None, "ome": None, "crop": "overlap",
    "geojson_in": None, "src_slide": None, "target_slide": None, "geojson_out": None,
    "images": None, "reference": None, "auto_reference": False,
    "max_processed_dim": None, "max_nonrigid_dim": None,
    "micro": False, "micro_dim": None, "micro_focus": False, "correct_scale": False,
    "cpu": False, "rigid_only": False, "reuse_registrar": False, "stage": False, "no_ome": False,
    "merge": False, "merge_out": None, "merge_level": 0, "merge_mode": "hed",
    "merge_name": None, "merge_stain": None, "merge_color": None, "composite": False,
}


def cmd_batch(args):
    """--manifest JSON'daki vakalari SIRALI calistir (paralel DEGIL - VALIS'in paylasilan JVM/GPU
    durumu birden cok es zamanli kayitla guvenli degildir). Bir vakanin hatasi digerlerini ETKILEMEZ
    (her vaka kendi try/except'inde). skip_existing (varsayilan True) + '<ad>.done.json' isaretcisi
    ile YARIM KALMIS bir toplu isi kaldigi yerden devam ettirir (bir onceki BASARILI _run_one_case
    SONUNDA yazilir - salt bir cikti-dosyasi glob'una gore DEGIL, kismi/yarim-kalmis bir onceki
    yazimda YANLIS 'tamamlanmis' sanmayi onler)."""
    result = {"ok": False, "cmd": "batch", "cases": []}
    try:
        with open(args.manifest, "r", encoding="utf-8") as fh:
            manifest = json.load(fh)
        common = dict(manifest.get("common") or {})
        cases = manifest.get("cases") or []
        n = len(cases)
        emit("Toplu isleme basliyor: " + str(n) + " vaka.")
        skip_existing = bool(common.pop("skip_existing", True))
        # Model agirliklarini BIR KEZ, ilk vakadan ONCE hazirla - ilk vakanin suresine bu gecikme eklenmesin.
        try:
            from valis import registration  # noqa: F401
            emit("Model agirliklari hazir (onbellekten veya az once indirildi).")
        except Exception as _pe:
            emit("UYARI: model on-yukleme basarisiz oldu (ilk vaka sirasinda tekrar denenecek): " + str(_pe))
        overall_ok = True
        for i, case in enumerate(cases, 1):
            case = dict(case or {})
            name = str(case.pop("name", None) or ("vaka" + str(i)))
            merged = dict(_RUN_ARG_DEFAULTS)
            merged.update(common)
            merged.update(case)
            case_args = argparse.Namespace(**merged)
            done_marker = (os.path.join(case_args.ome, name + ".done.json")
                           if case_args.ome else None)
            if skip_existing and done_marker and os.path.isfile(done_marker):
                emit("Vaka atlandi (onceden tamamlanmis isaretcisi bulundu): " + name)
                emit("BATCH_ILERLEME: " + json.dumps(
                    {"index": i, "total": n, "ad": name, "durum": "atlandi", "sure_sn": 0}))
                result["cases"].append({"name": name, "ok": True, "skipped": True})
                continue
            t0 = time.time()
            _EMIT_PREFIX[0] = "[vaka " + str(i) + "/" + str(n) + ": " + name + "] "
            emit("BATCH_ILERLEME: " + json.dumps(
                {"index": i, "total": n, "ad": name, "durum": "basladi", "sure_sn": 0}))
            try:
                case_result = _run_one_case(case_args)
            except Exception as _ce:
                # _run_one_case normalde hatayi YUTUP result['ok']=False doner; bu except beklenmedik
                # (guard disi) bir istisnaya karsi savunma - batch dongusu YINE DE devam etmelidir.
                case_result = {"ok": False, "error": str(_ce)}
                emit("HATA (bu vaka atlandi, digerleri devam ediyor): " + str(_ce))
                traceback.print_exc()
            finally:
                _EMIT_PREFIX[0] = ""
            elapsed = time.time() - t0
            case_result["name"] = name
            case_result["elapsed_sec"] = round(elapsed, 1)
            if case_result.get("ok"):
                if done_marker:
                    try:
                        dd = os.path.dirname(done_marker)
                        if dd:
                            os.makedirs(dd, exist_ok=True)
                        with open(done_marker, "w", encoding="utf-8") as fh:
                            json.dump({"name": name, "ok": True, "elapsed_sec": elapsed}, fh)
                    except Exception:
                        pass  # isaretci en-iyi-caba; yazilamamasi vakayi basarisiz saymamali
            else:
                overall_ok = False
            emit("BATCH_ILERLEME: " + json.dumps({
                "index": i, "total": n, "ad": name,
                "durum": ("tamam" if case_result.get("ok") else "hata"),
                "sure_sn": case_result["elapsed_sec"],
                "cikti": case_result.get("merge_out"),
                "referans": case_result.get("reference_used"),
            }))
            result["cases"].append(case_result)
        result["ok"] = overall_ok
    except Exception as e:
        result["error"] = str(e)
        emit("HATA (toplu isleme): " + repr(e))
        traceback.print_exc()
    finally:
        _kill_jvm()   # TUM vakalar (basarili/basarisiz) bittikten SONRA bir kez - per-vaka DEGIL
    emit("RESULT_JSON: " + json.dumps(result))
    return 0 if result["ok"] else 1


def _checkpoint_files():
    """torch.hub checkpoints klasoru + {ad: bayt} sozlugu (yoksa None, {})."""
    try:
        import torch
        ck = os.path.join(torch.hub.get_dir(), "checkpoints")
        if os.path.isdir(ck):
            return ck, {f: os.path.getsize(os.path.join(ck, f)) for f in os.listdir(ck)}
        return ck, {}
    except Exception:
        return None, {}


def cmd_prefetch(_args):
    # VALIS varsayilan modellerini (DISK + LightGlue) yerel onbellege indirir. registration ice
    # aktarilinca modul-duzeyi DEFAULT_MATCHER=LightGlueMatcher(feature_detector=DiskFD()) kurulur;
    # bu da DISK.from_pretrained('depth') + LightGlue agirliklarini torch.hub onbellegine indirir
    # (selftest ile ayni ice aktarma yolu). Onbellek konumu TORCH_HOME ile belirlenir (sihirbaz
    # native modda veri kokune yonlendirir). Sicak onbellekte hizli doner; asagida once/sonra raporlar.
    result = {"ok": False, "cmd": "prefetch", "already": [], "downloaded": [], "files": []}
    try:
        import torch
        result["hub_dir"] = torch.hub.get_dir()
        emit("torch.hub dizini: " + str(result["hub_dir"]))
        ck, before = _checkpoint_files()
        result["checkpoints_dir"] = ck
        emit("Onbellek klasoru: " + str(ck))
        if before:
            emit("Zaten yerelde (" + str(len(before)) + " dosya):")
            for n in sorted(before):
                emit("  - " + n + " (" + str(before[n]) + " B)")
        else:
            emit("Onbellek bos gorunuyor; agirliklar indirilecek (~50 MB: DISK + LightGlue).")
        emit("VALIS varsayilan modelleri hazirlaniyor (import valis.registration)...")
        from valis import registration  # noqa: F401
        ck2, after = _checkpoint_files()
        if ck2:
            result["checkpoints_dir"] = ck2
        new_files = sorted([n for n in after if n not in before])
        result["downloaded"] = new_files
        result["already"] = sorted([n for n in after if n in before])
        result["files"] = [{"name": n, "bytes": after[n]} for n in sorted(after)]
        if new_files:
            emit("Yeni indirilen (" + str(len(new_files)) + "):")
            for n in new_files:
                emit("  + " + n + " (" + str(after[n]) + " B)")
        else:
            emit("Yeni indirme yok - tum agirliklar zaten yereldeydi.")
        emit("Model agirliklari hazir. Onbellek: " + str(result["checkpoints_dir"]))
        result["ok"] = True
    except Exception as e:
        result["error"] = repr(e)
        emit("HATA: " + repr(e))
        traceback.print_exc()
    finally:
        _kill_jvm()
    emit("RESULT_JSON: " + json.dumps(result))
    return 0 if result["ok"] else 1


def build_parser():
    p = argparse.ArgumentParser(description="VALIS bridge runner (QuPath Atolye)")
    sub = p.add_subparsers(dest="cmd")
    sub.add_parser("selftest")
    sub.add_parser("prefetch")
    pr = sub.add_parser("run")
    pr.add_argument("--src", required=True)
    pr.add_argument("--dst", required=True)
    pr.add_argument("--ome", required=True)
    pr.add_argument("--crop", default="overlap", choices=["overlap", "reference", "all"])
    pr.add_argument("--geojson-in", dest="geojson_in", default=None)
    pr.add_argument("--src-slide", dest="src_slide", default=None)
    pr.add_argument("--target-slide", dest="target_slide", action="append", default=None)  # bir VEYA daha fazla hedef
    pr.add_argument("--geojson-out", dest="geojson_out", action="append", default=None)     # her hedef icin bir cikti (sirali)
    pr.add_argument("--images", dest="images", action="append", default=None)
    pr.add_argument("--reference", dest="reference", default=None)
    pr.add_argument("--auto-reference", dest="auto_reference", action="store_true")  # --reference verilmisse yok sayilir (o daima kazanir)
    pr.add_argument("--max-processed-dim", dest="max_processed_dim", type=int, default=None)
    pr.add_argument("--max-nonrigid-dim", dest="max_nonrigid_dim", type=int, default=None)
    pr.add_argument("--micro", dest="micro", action="store_true")               # register_micro: yuksek-cozunurluklu 2. non-rigid gecis
    pr.add_argument("--micro-dim", dest="micro_dim", type=int, default=None)     # micro pass max kenar (baslangic non-rigid'ten BUYUK olmali; bos=4096)
    pr.add_argument("--micro-focus", dest="micro_focus", action="store_true")    # ince hizalamayi --geojson-in/--src-slide anotasyon BOLGESIYLE sinirla (maske)
    pr.add_argument("--correct-scale", dest="correct_scale", action="store_true")  # rigid'i benzerlik (olcek tahminli) yap -> tarayici um/px farkini duzelt
    pr.add_argument("--cpu", dest="cpu", action="store_true")
    pr.add_argument("--rigid-only", dest="rigid_only", action="store_true")
    pr.add_argument("--reuse-registrar", dest="reuse_registrar", action="store_true")
    pr.add_argument("--stage", dest="stage", action="store_true")
    pr.add_argument("--no-ome", dest="no_ome", action="store_true")
    pr.add_argument("--merge", dest="merge", action="store_true")
    pr.add_argument("--merge-out", dest="merge_out", default=None)
    pr.add_argument("--merge-level", dest="merge_level", type=int, default=0)
    pr.add_argument("--merge-mode", dest="merge_mode", default="hed", choices=_VALID_STAINS)
    pr.add_argument("--merge-name", dest="merge_name", action="append", default=None)
    pr.add_argument("--merge-stain", dest="merge_stain", action="append", default=None)
    pr.add_argument("--merge-color", dest="merge_color", action="append", default=None)
    pr.add_argument("--composite", dest="composite", action="store_true")
    pb = sub.add_parser("batch")
    pb.add_argument("--manifest", dest="manifest", required=True)  # {"common":{...}, "cases":[{"name","src","dst","ome",...}, ...]}
    return p


def main():
    args = build_parser().parse_args()
    if args.cmd == "selftest":
        return cmd_selftest(args)
    if args.cmd == "prefetch":
        return cmd_prefetch(args)
    if args.cmd == "run":
        return cmd_run(args)
    if args.cmd == "batch":
        return cmd_batch(args)
    build_parser().print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
