/**
 * Yardımcı - VALIS Hizalama (Registration) Sihirbazı (tek pencere: hibrit köprü)
 * -----------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   VALIS'i (Gatenbee ve ark., Nat Commun 2023) QuPath'e TEK pencereden bağlar.
 *   VALIS, bir klasördeki tüm slaytları OTOMATİK (elle işaret gerektirmez) hizalar —
 *   rigid + non-rigid — ve şunları üretir:
 *     • hizalanmış slaytları PİRAMİDAL OME-TIFF olarak (QuPath doğrudan açar), ve
 *     • QuPath'ten dışa aktarılan bir GeoJSON anotasyonunu kaynak slayttan hedef
 *       slaydın koordinat uzayına WARP ederek (geri QuPath'e aktarılır).
 *   Bu, Interactive image alignment (afin) ve Warpy (Fiji) yollarının OTOMATİK,
 *   tüm-seriyi-birden, esnek karşılığıdır. Bkz. Ekler → Görüntü Hizalama § VALIS.
 *
 *   VALIS Python'dur ve QuPath DIŞINDA koşar; bu sihirbaz HİBRİT bir köprüdür:
 *     • KÖPRÜ — Docker VE yerel (native venv) için kopyalanabilir komut üretir.
 *     • DOĞRUDAN — yapılandırılmış modu QuPath içinden çalıştırır (en iyi çaba).
 *   Kayıt + warp TEK Python sürecinde olur (registrar bellekte kalmalı).
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • VALIS bir hizalama TAHMİNİDİR; bu betik yalnız OME-TIFF/warp'lı GeoJSON'u
 *     QuPath'e taşır. Klinik yorum/skor üretmez. Hizalamayı GÖRSEL doğrulayın.
 *   • Warp'lanan GeoJSON koordinatları WSI TABAN (level-0) piksel uzayında,
 *     köşe sol-üst, aynı en-boy oranı olmalıdır (VALIS varsayımı = QuPath dışa
 *     aktarım varsayımı). Yeniden ölçekleme yapılmaz.
 *   • Lisans: VALIS = MIT (izin verici).
 *
 * KULLANIM:
 *   1. VALIS ortamını hazırlayın: Docker (önerilen; tüm bağımlılıklar hazır) ya da
 *      yerel venv (Python 3.10 + libvips + Java). Bkz. Kaynaklar → İleri kurulumlar § VALIS.
 *   2. Kaynak (anotasyonlu) ve hedef slaytları AYNI QuPath projesine ekleyin ve
 *      slayt DOSYALARINI ortak bir "çalışma klasörü" altına koyun (Docker tek-kök şartı).
 *   3. [Extensions → Atölye → Yardımcılar → Python köprüleri & temel modeller → VALIS hizalama sihirbazı (Docker/native)]
 *   4. Yapılandırın, kaynak/hedef slaydı ve warp'lanacak anotasyonu seçin,
 *      "Komut üret" (kopyala-çalıştır) ya da "Doğrudan çalıştır"; sonra sonuçları içe aktarın.
 *
 * YÖNTEM / KAYNAK:
 *   • Gatenbee C ve ark. (2023), Nat Commun 14:4502 — VALIS. doi:10.1038/s41467-023-40218-9
 *   • Depo: https://github.com/MathOnco/valis (MIT) · Docker: cdgatenbee/valis-wsi
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.io.PathIO
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Sabitler ───────────────────────────────────────────────────────────────
def VALIS_SENTINEL = 'VALIS'                  // warp'lı içe aktarımda idempotent temizlik/kilit adı
def DOCKER_IMAGE   = 'cdgatenbee/valis-wsi'
def CONTAINER_MOUNT= '/work'                  // çalışma kökü konteynerde buraya bağlanır
def CROP_OPTIONS   = ['overlap', 'reference', 'all']
long PYTHON_TIMEOUT_SECONDS = 21600L          // 6 saat: WSI kaydı uzun sürebilir
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"

// ── Gömülü VALIS runner — extension-only kullanıcıda handson/python bağımlılığı
// olmasın diye köprü betiği talep üzerine <çalışma-kökü>/valis_runner.py olarak yazılır.
// YETKİLİ KOPYA BUDUR — çalışan budur. handson/python/valis/valis_runner.py bunun BAYT-EŞ
// aynasıdır ve tools/check-script-sync.ps1 ile eş tutulur (drift CI'da yakalanır). Docker
// modunda bu dosya /work altından mount edilir; native modda doğrudan çalışır.
def VALIS_RUNNER_PY = $/#!/usr/bin/env python3
# VALIS bridge runner - QuPath Atolye workshop (gomulu surumden yazildi)
# Hedef QuPath: 0.6.0+ (QuPath DISINDA, VALIS venv/Docker icinde kosar)
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


def emit(msg):
    print(msg, flush=True)


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
# tabanli; yardimci-boya-vektor-sihirbaz.groovy ile ayni). Parlak-alan slaytlari QuPath'te RGB
# saklanir; ANLAMLI multipleks kanallari ise ayristirilmis BOYALARDIR — İHK'de Hematoksilen+DAB,
# H&E'de Hematoksilen+Eozin. H&E slaydini H-DAB vektorleriyle ayristirmak SAHTE bir 'DAB' kanali
# uretir (H&E'de DAB yoktur) — bu yuzden slayt-basi boya tipi (--merge-stain) desteklenir.
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
            # Kullanici rengini UYGULAMA: cok-kanalli (IF) gecis slaydinda ('Marker-DAPI','Marker-CD3'...)
            # hepsi ayni marker on-ekini paylasir; user uygulanirsa TUMU tek renge duserdi. Sadece
            # DAB/Eozin (isaret) kanallari kullanici rengini alir; digerleri ayrik palet.
            cmap[nm] = _FALLBACK_PALETTE[fb_i % len(_FALLBACK_PALETTE)]
            fb_i += 1
    return cmap


def _check_warp_wh(warped, expected_wh, sname):
    """Warp edilmis slaytlarin AYNI boyutta olmasini garanti et. pyvips aritmetigi/bandjoin boyut
    uyusmazliginda HATA VERMEZ — kucuk goruntuyu sol-uste gomer (sessiz-yanlis birlesim). Uyusmazlikta
    net hata ver. Bilinen tetik: Windows 'best-page' yamasi + crop='reference' -> referans slayt kuculur."""
    wh = (warped.width, warped.height)
    if expected_wh is None:
        return wh
    if wh != expected_wh:
        raise ValueError(
            "Warp boyutlari uyusmuyor: '%s' %dx%d, digerleri %dx%d. Sessiz-yanlis birlesim onlendi. "
            "Genelde neden: Windows 'best-page' yamasi + crop='reference' (referans slayt kuculur). "
            "Cozum: crop='overlap' kullanin ya da WSL/Docker modunu tercih edin."
            % (sname, wh[0], wh[1], expected_wh[0], expected_wh[1]))
    return expected_wh


def _merge_slides_deconvolved(registrar, dst_f, level, non_rigid, crop, mode, name_map, stain_map, emit, color_map=None):
    """Renk-ayristirmali cok-kanalli merge. Her slaydi warp eder, boya tipine gore (bkz.
    _STAIN_TABLE) N-boya ayristirir (pyvips recomb, tembel), bandjoin ile birlestirir ve
    VALIS'in OME yazicisiyla piramidal OME-TIFF yazar.

    mode: slayt-basi override yoksa VARSAYILAN boya tipi ('hed'|'he'|'dab'|'rgb'|...).
    stain_map: slayt-adi -> boya tipi override. Bulunmazsa `mode` kullanilir.
      _STAIN_TABLE'daki her tip N kanal uretir; 'dab' = yalniz DAB kanali; 'rgb' = ham RGB.
    name_map: slayt-adi -> marker etiketi (kanal adi on-eki). Bulunamazsa slayt adi.
    IF (cok-kanalli, is_rgb=False) slaytlar ayristirilmadan oldugu gibi eklenir.
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
        stain = stain_map.get(sname) or mode                   # slayt-basi override else global varsayilan
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
    """DOGAL-RENK parlak-alan RGB bilesik (Beer-Lambert). Her slaydin (boya tipine gore) ayristirilan
    boya OD katkilarini TOPLAR ve I = 255 * 10^(-OD_toplam) ile beyaz-zeminli RGB uretir. TEK slaytta o
    slaydin dogal gorunumudur (or. H&E gibi); COK slaytta SENTETIK bir bilesiktir — gercek bir slayda
    karsilik GELMEZ, cakisan boyalar koyulasir (fiziksel gecirgenlik modeli). Marker'lari ayirt etmek
    icin cok-kanalli multipleks daha uygundur; bu, 'slayt gibi gozuksun' isteyenler icindir.
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
            # Cok-kanalli (IF) slaydin dogal iletim (parlak-alan) rengi yoktur + band sayisi RGB ile
            # toplanmaz -> bilesige EKLEME (uyar). Composite parlak-alan slaytlar icindir.
            emit("  UYARI: cok-kanalli (IF) slayt parlak-alan bilesigine eklenmedi: " + str(sname))
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
        raise ValueError("Parlak-alan bilesigi icin uygun (parlak-alan/RGB) slayt yok.")
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
            _write_src_sidecar(pkl, orig_src, emit_fn)  # VALIS kaydetti; yalniz yan-dosyayi ekle
            return
        emit_fn("Not: VALIS registrar'i otomatik saklamadi (muhtemelen rigid-only olcum adimi); reuse icin en-iyi-caba ile kaydediliyor...")
        try:
            registrar.cleanup()  # picklelenemeyen nesneleri temizle (cleanup zaten kosmadiysa)
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
        emit_fn("Registrar saklandi (reuse etkin): " + pkl)
    except Exception as e:
        emit_fn("UYARI: registrar saklanamadi - bu calisma reuse EDILEMEZ (tam non-rigid calisma reuse'u etkinlestirir): " + str(e))


def _stage_images(images, stage_root, emit_fn):
    """images'i stage_root'a KOPYALA (idempotent: ayni boyutta zaten varsa atla). {orijinal: stage} dondurur.
    Amac: slaytlari yavas Windows-diski (/mnt/...) yerine WSL-yerel diskten okumak -> I/O ~6x hizli."""
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


def cmd_run(args):
    result = {"ok": False, "cmd": "run", "ome_files": []}
    try:
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

        # Orijinal --src (staging args.src'yi degistirmeden ONCE): registrar kaynak-kimlik yan-dosyasi ve reuse
        # dogrulamasi bunu kullanir. Paylasilan cikti klasorunde ayni govde adli iki seriyi ayirt eder.
        import hashlib as _hashlib
        _orig_src = os.path.abspath(args.src)
        _src_token = _hashlib.sha1(_orig_src.encode("utf-8", "replace")).hexdigest()[:8]

        # ── Stage: slaytlari WSL-yerel diske kopyala (ilk kayit I/O'sunu ~6x hizlandirir) ──
        # stage kokunu KAYNAGA-OZGU bir alt-klasore (_src_token) koy -> ayni govde adli iki farkli seri birbirinin
        # stage'lenmis slaytlarini clobber'lamaz. Yaprak govde adi (VALIS 'name'i) DEGISMEDEN kalir; boylece
        # registrar pickle adi (ve --reuse-registrar aramasi) tutarli kalir.
        if getattr(args, "stage", False) and not getattr(args, "reuse_registrar", False):
            _stage_root = os.path.join(_ATOLYE_DATA_ROOT, "stage", _src_token, os.path.basename(args.src.rstrip("/\\")))
            _imgs = list(args.images) if getattr(args, "images", None) else []
            for _extra in (getattr(args, "reference", None), getattr(args, "src_slide", None), getattr(args, "target_slide", None)):
                if _extra and _extra not in _imgs:
                    _imgs.append(_extra)
            if _imgs:
                emit("Slaytlar hizli (WSL-yerel) diske stage'leniyor: " + _stage_root)
                _smap = _stage_images(_imgs, _stage_root, emit)
                if getattr(args, "images", None):
                    args.images = [_smap.get(x, x) for x in args.images]
                if getattr(args, "reference", None):
                    args.reference = _smap.get(args.reference, args.reference)
                if getattr(args, "src_slide", None):
                    args.src_slide = _smap.get(args.src_slide, args.src_slide)
                if getattr(args, "target_slide", None):
                    args.target_slide = _smap.get(args.target_slide, args.target_slide)
                args.src = _stage_root
                emit("Stage tamam. Kayit stage klasorunden okuyacak.")

        emit("VALIS kayit basliyor. Girdi klasoru: " + args.src)
        emit("Not: 'Processing images' asamasi (ozellik eslestirme + rigid/non-rigid kayit) SESSIZ olabilir;")
        emit("     asagida her ~20 sn bir nabiz basilir. " + ("Kayit CPU'da kosuyor (--cpu) — nvidia-smi bos gorunur, normaldir." if getattr(args, "cpu", False) else "Ilerleme cogunlukla GPU'da olur (nvidia-smi dmon ile izlenebilir)."))
        vkwargs = {}
        if getattr(args, "images", None):
            vkwargs["img_list"] = list(args.images)
        if getattr(args, "reference", None):
            vkwargs["reference_img_f"] = args.reference
            emit("Referans slayt: " + os.path.basename(args.reference))
        if getattr(args, "max_processed_dim", None):
            vkwargs["max_processed_image_dim_px"] = int(args.max_processed_dim)
        if getattr(args, "max_nonrigid_dim", None):
            vkwargs["max_non_rigid_registration_dim_px"] = int(args.max_nonrigid_dim)
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
                # Slayt adlari CAKISABILIR (iki seride ayni dosya adlari) -> kaynak-kimlik yan-dosyasiyla da dogrula.
                _check_src_sidecar(_pkl, _orig_src, emit)
            else:
                emit("UYARI: --reuse-registrar istendi ama kayitli registrar bulunamadi (" + args.dst + "); TAM kayit yapilacak.")
        if registrar is None:
            if getattr(args, "images", None):
                emit("Kayda dahil slaytlar (%d): %s" % (len(args.images), ", ".join(os.path.basename(x) for x in args.images)))
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
        else:
            emit("Kayit atlandi (kayitli registrar) - yeniden birlestirme.")
        # Merge/warp'ta non-rigid: varsayilan --rigid-only'e gore (sifirdan koşuyla AYNI -> orijinal merge'i eslesir).
        # Kayitli registrar'da non_rigid_registrar_cls pickle sonrasi GUVENILMEZ (non-rigid yapilsa bile None olur);
        # gercek gosterge referans-disi bir slaydin bk_dxdy (deformasyon alani) VARLIGIDIR. Yalniz gercekten
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

        # Anotasyon warp'i ONCE yap: kullanicinin BIRINCIL ciktisidir + hizlidir (yalniz koordinat donusumu).
        # OME-TIFF yazimi (tam-res hizalanmis slaytlar) YAVAS + ikincildir; sonra ve --no-ome ile istege
        # bagli yapilir. Boylece OME yavas/basarisiz olsa bile warp'li anotasyon zaten yazilmis olur.
        want_warp = bool(args.geojson_in) and bool(args.src_slide) and bool(args.target_slide) and bool(args.geojson_out)
        if want_warp:
            emit("Anotasyon warp: " + str(args.src_slide) + " -> " + str(args.target_slide))
            src_slide = registrar.get_slide(args.src_slide)
            tgt_slide = registrar.get_slide(args.target_slide)
            warped = src_slide.warp_geojson_from_to(args.geojson_in, tgt_slide)
            gj_dir = os.path.dirname(args.geojson_out)
            if gj_dir:
                os.makedirs(gj_dir, exist_ok=True)
            with open(args.geojson_out, "w", encoding="utf-8") as fh:
                json.dump(warped, fh)
            result["geojson_out"] = args.geojson_out
            emit("Warp'lanmis GeoJSON yazildi: " + args.geojson_out)
        else:
            emit("Anotasyon warp atlandi (geojson/slayt argumanlari verilmedi).")

        if getattr(args, "no_ome", False):
            emit("OME-TIFF yazimi atlandi (--no-ome). Yalniz warp'li anotasyon uretildi.")
        else:
            emit("Hizalanmis slaytlar OME-TIFF olarak yaziliyor (crop=" + str(args.crop) + "): " + args.ome)
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

        if getattr(args, "merge", False):
            merge_out = args.merge_out or os.path.join(args.ome, "merged_overlay.ome.tiff")
            md = os.path.dirname(merge_out)
            if md:
                os.makedirs(md, exist_ok=True)
            mmode = getattr(args, "merge_mode", "hed") or "hed"
            # non_rigid_merge yukarida (kayit blogunda) belirlendi: --rigid-only VEYA kayitli registrar rigid-only.
            # Marker adlari: --merge-name KEY=MARKER (KEY = slayt dosya adi/govdesi). Slayt
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
            # Hatali/taninmayan girdiler SESSIZ dusmesin -> uyar (aksi halde H&E slaydi sessizce fake-DAB olur).
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
                    # DOGAL-RENK parlak-alan RGB bilesik (Beer-Lambert). 'Slayt gibi gozuksun' icin;
                    # cok slaytta sentetik (cakisan boyalar koyulasir). Slayt-basi boya tipleri korunur.
                    emit("Dogal-renk parlak-alan bilesik (composite, level=" + str(args.merge_level) + "): " + merge_out)
                    _names = _merge_slides_composite(registrar, merge_out, args.merge_level,
                                                     non_rigid_merge, args.crop, mmode, name_map, stain_map, emit)
                    result["composite"] = True
                else:
                    # Cok-kanalli renk-ayristirmali merge; 'rgb' de burada islenir (ham RGB gecis, tek
                    # tutarli kod yolu + dogal kanal renkleri). Slayt-basi stain_map > global varsayilan
                    # (mmode): İHK -> Hem+DAB, H&E -> Hem+Eozin. (Eski VALIS-native RGB yolu kaldirildi.)
                    emit("Renk-ayristirmali merge (varsayilan mode=" + str(mmode) + ", slayt-basi override=" + json.dumps(stain_map) + ", level=" + str(args.merge_level) + "): " + merge_out)
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

        result["ok"] = True
    except Exception as e:
        result["error"] = str(e)
        emit("HATA: " + repr(e))
        traceback.print_exc()
    finally:
        _kill_jvm()
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
    pr.add_argument("--target-slide", dest="target_slide", default=None)
    pr.add_argument("--geojson-out", dest="geojson_out", default=None)
    pr.add_argument("--images", dest="images", action="append", default=None)
    pr.add_argument("--reference", dest="reference", default=None)
    pr.add_argument("--max-processed-dim", dest="max_processed_dim", type=int, default=None)
    pr.add_argument("--max-nonrigid-dim", dest="max_nonrigid_dim", type=int, default=None)
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
    return p


def main():
    args = build_parser().parse_args()
    if args.cmd == "selftest":
        return cmd_selftest(args)
    if args.cmd == "prefetch":
        return cmd_prefetch(args)
    if args.cmd == "run":
        return cmd_run(args)
    build_parser().print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
/$

// ── Kalıcı yapılandırma ──────────────────────────────────────────────────────
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/valis')
def PREF_MODE   = 'mode'        // 'docker' | 'native' | 'wsl'
def PREF_PYTHON = 'python'      // native python.exe
def PREF_WSLPY  = 'wslPython'   // WSL içindeki python (ör. /root/.valis-venv/bin/python)
def PREF_WORKDIR= 'workDir'     // tek-kök çalışma klasörü
def PREF_OUTDIR = 'outDir'      // çıktı kökü (boşsa = workDir); native'de bağımsız olabilir
def PREF_MEM    = 'dockerMem'   // GB
def PREF_CROP   = 'crop'

// ── Atölye veri kökü (env yöneticisiyle PAYLAŞILAN) ─────────────────────────
def atolyeDataRoot = { ->
    def p = ''
    try { p = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
// Env yöneticisinin kaydettiği valis venv python'u (native mod otomatik doldurma).
def valisVenvPython = { ->
    try {
        def rec = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common').get('py.valis', '')
        if (rec?.trim()) { def rf = new File(rec.trim()); if (rf.isFile()) return rf }
    } catch (Throwable ignore) {}
    def v = new File(new File(new File(atolyeDataRoot(), 'runtimes'), 'valis'), '.venv')
    def w = new File(v, 'Scripts/python.exe'); def n = new File(v, 'bin/python')
    return w.isFile() ? w : (n.isFile() ? n : null)
}

def loadConfig = { ->
    [ mode     : prefs.get(PREF_MODE,   'docker'),
      python   : prefs.get(PREF_PYTHON, ''),
      wslPython: prefs.get(PREF_WSLPY,  '/root/.valis-venv/bin/python'),
      workDir  : prefs.get(PREF_WORKDIR, new File(atolyeDataRoot(), 'valis').getAbsolutePath()),
      outDir   : prefs.get(PREF_OUTDIR, ''),
      mem      : prefs.get(PREF_MEM,    '20'),
      crop     : prefs.get(PREF_CROP,   'overlap') ]
}
def workRootOf   = { cfg -> new File((cfg.workDir?.trim()) ? cfg.workDir.trim() : new File(atolyeDataRoot(), 'valis').getAbsolutePath()) }
// Çıktı kökü — boşsa çalışma köküne düşer. Native'de bağımsız sürücü/klasör olabilir (ör. büyük OME-TIFF'ler
// için E:\); Docker'da çalışma kökünün ALTINDA olmalı (tek /work mount) — prepareRun bunu doğrular.
def outRootOf    = { cfg -> new File((cfg.outDir?.trim()) ? cfg.outDir.trim() : workRootOf(cfg).getAbsolutePath()) }
def resultsDirOf = { cfg -> new File(outRootOf(cfg), 'results') }
def omeDirOf     = { cfg -> new File(outRootOf(cfg), 'ome') }
def geojsonDirOf = { cfg -> new File(outRootOf(cfg), 'geojson') }
def runnerFileOf = { cfg -> new File(workRootOf(cfg), 'valis_runner.py') }

// Köprü betiğini gömülü metinden diske yaz (talep üzerine; extension-only kullanıcı için).
def writeRunner = { cfg ->
    def wr = workRootOf(cfg); wr.mkdirs()
    def rf = runnerFileOf(cfg)
    try { rf.setText(VALIS_RUNNER_PY, 'UTF-8'); return [ok: true, file: rf] }
    catch (Throwable t) { return [ok: false, error: (t.getMessage() ?: t.getClass().getSimpleName())] }
}

def configMissing = { cfg ->
    def miss = []
    if (cfg.mode == 'native') {
        if (!cfg.python?.trim() || !(new File(cfg.python)).isFile()) miss << 'Native mod: python.exe (VALIS venv)'
    }
    if (cfg.mode == 'wsl') {
        // WSL yolu Windows'tan güvenilir doğrulanamaz (dosya WSL kök fs'inde olabilir); yalnız boş olmasın.
        if (!cfg.wslPython?.trim()) miss << 'WSL mod: WSL python yolu (ör. /root/.valis-venv/bin/python)'
    }
    if (!cfg.workDir?.trim()) miss << 'Çalışma klasörü'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

// ── Docker tek-kök yol çevirisi: hostPath (çalışma-kökü altında) → /work/<göreli> ──
// Docker'da TÜM girdi/çıktı tek bir mount (çalışma-kökü → /work) altında olmalı.
def toContainer = { String hostPath, File workRoot ->
    if (hostPath == null || workRoot == null) return null
    try {
        def wr = workRoot.getCanonicalFile().getAbsolutePath()
        def hp = new File(hostPath).getCanonicalFile().getAbsolutePath()
        if (!hp.equals(wr) && !hp.startsWith(wr + File.separator)) return null   // kök dışında
        def rel = hp.substring(wr.length()).replace('\\', '/')
        if (!rel.startsWith('/')) rel = '/' + rel
        return CONTAINER_MOUNT + (rel == '/' ? '' : rel)
    } catch (Throwable t) { return null }
}

// ── WSL yol çevirisi: Windows yolu → /mnt/<sürücü>/... (Docker'ın tek-kök şartı YOK) ──
// C:\a\b → /mnt/c/a/b. Zaten POSIX (/...) ise dokunma (kullanıcı WSL-yerel yol vermiş olabilir).
def toWsl = { String hostPath ->
    if (hostPath == null) return null
    def s = hostPath.replace('\\', '/')
    def m = (s =~ /^([A-Za-z]):\/(.*)$/)
    if (m.find()) return '/mnt/' + m.group(1).toLowerCase(java.util.Locale.ROOT) + '/' + m.group(2)
    def m2 = (s =~ /^([A-Za-z]):$/)
    if (m2.find()) return '/mnt/' + m2.group(1).toLowerCase(java.util.Locale.ROOT)
    return s
}

// ── Slayt yolu çöz (yerel dosya) ────────────────────────────────────────────
def slideFileOf = { imageData ->
    try {
        def uris = imageData.getServer().getURIs()
        if (uris != null && !uris.isEmpty()) {
            def uri = uris.iterator().next()
            if ('file'.equals(uri.getScheme())) return new File(uri)
        }
    } catch (Throwable ignore) {}
    return null
}

// ── Komut üretimi (Docker + native) ──────────────────────────────────────────
def q = { s -> '"' + (s ?: '') + '"' }
// imagesList: kaydedilecek slaytların (çevrilmiş) yolları — HER BİRİ açıkça --images ile
// geçilir (klasör-tümü YEDEK YOK; alakasız/farklı-büyütme slayt karışımı 'extract_area' hatasına
// yol açar, bu yüzden set daima açık). reference: referans slayt yolu. merge: [enabled,mode,level,out,names].
// mergeNames: "govde=marker" listesi (govde = slayt dosya adı, uzantısız — yol çevirisinden bağımsız).
def runArgs = { cfg, String srcDir, String dstDir, String omeDir, String crop, List imagesList, String reference,
                geojsonIn, srcSlide, tgtSlide, geojsonOut, Map merge, boolean writeOme, Map opts = [:] ->
    // --cpu: VALIS 1.2.0 DiskFD CUDA'da (res.keypoints.numpy) çöküyor; kayıt küçük (~512px) görüntülerde
    // CPU'da da hızlı olduğundan CPU'ya zorlarız (GPU kurulu olsa bile). VALIS düzeltince kaldırılabilir.
    def a = ['run', '--cpu', '--src', srcDir, '--dst', dstDir, '--ome', omeDir, '--crop', crop]
    (imagesList ?: []).each { if (it) a += ['--images', it] }
    if (reference) a += ['--reference', reference]
    if (!writeOme) a += ['--no-ome']   // ayrı hizalı OME slaytları YAVAŞ; merge birincil çıktı olduğunda atla
    // ── Hız seçenekleri (opts) ──
    if (opts?.rigidOnly) a += ['--rigid-only']                                  // non-rigid'i atla (hızlı, daha az hassas)
    if (opts?.maxProcessedDim) a += ['--max-processed-dim', opts.maxProcessedDim.toString()]   // kayıt çözünürlüğü
    if (opts?.stage) a += ['--stage']                                           // slaytları WSL-yerel diske kopyala (ilk kayıt hızlı)
    if (opts?.reuseRegistrar) a += ['--reuse-registrar']                        // kayıtlı hizalamadan yeniden birleştir (yeniden KAYIT yok)
    if (geojsonIn && srcSlide && tgtSlide && geojsonOut) {
        a += ['--geojson-in', geojsonIn, '--src-slide', srcSlide, '--target-slide', tgtSlide, '--geojson-out', geojsonOut]
    }
    if (merge?.enabled) {
        a += ['--merge', '--merge-mode', (merge.mode ?: 'hed'), '--merge-level', (merge.level ?: '2').toString()]
        if (merge.out) a += ['--merge-out', merge.out]
        (merge.names ?: []).each { if (it) a += ['--merge-name', it] }
        (merge.stains ?: []).each { if (it) a += ['--merge-stain', it] }   // slayt-başı boya tipi (İHK/H&E)
        (merge.colors ?: []).each { if (it) a += ['--merge-color', it] }   // marker işaret (DAB/Eozin) rengi (MARKER=RRGGBB)
        if (merge.composite) a += ['--composite']                          // doğal-renk parlak-alan RGB bileşik
    }
    return a
}
// Docker komutu — host çalışma-kökü /work'e bağlanır; runner ve tüm yollar /work altında.
// name: konteynere isim verir → iptal/zaman aşımında `docker stop <name>` ile durdurulabilir
// (yalnız yerel `docker` CLI'yı öldürmek konteyneri durdurmaz).
def dockerCmd = { cfg, File workRoot, String name, List runnerArgsContainer ->
    def cmd = ['docker', 'run', '--rm']
    if (name?.trim()) cmd += ['--name', name.trim()]
    if (cfg.mem?.trim()) cmd += ['--memory=' + cfg.mem.trim() + 'g']
    cmd += ['-v', workRoot.getAbsolutePath() + ':' + CONTAINER_MOUNT, DOCKER_IMAGE,
            'python3', CONTAINER_MOUNT + '/valis_runner.py']
    cmd += runnerArgsContainer
    return cmd
}
def nativeCmd = { cfg, List runnerArgsHost ->
    def cmd = [cfg.python, runnerFileOf(cfg).getAbsolutePath()]
    cmd += runnerArgsHost
    return cmd
}
def cmdToText = { List cmd -> cmd.collect { it?.toString()?.contains(' ') ? q(it) : (it ?: '') }.join(' ') }
// WSL komutu — Windows'ta yerel VALIS 0xC0000005 çöktüğü için ÖNERİLEN yol. runnerArgsWsl yolları
// /mnt/... biçiminde (buildArgs 'wsl' çeviriyle üretir). wsl.exe'yi Java ProcessBuilder ile doğrudan
// çağırırız (Git Bash MSYS yol bozması OLMAZ). İç komut tek dizge; boşluklu yollar cmdToText ile tırnaklanır.
// POSIX tek-tırnak kaçışı — bash -lc katmanı için. İç katmanda YALNIZ ' kullanılır; böylece
// dış Windows sarması ("...", cmdToText görüntüde ya da Java ProcessBuilder çalıştırmada
// boşluklu tek argümanı sarınca) iç " ile ÇAKIŞMAZ. (Aksi halde iç içe " → bozuk komut; lint Check 17.)
def qsh = { s -> "'" + (s ?: '').toString().replace("'", "'\\''") + "'" }
def wslCmd = { cfg, List runnerArgsWsl ->
    def wslPy = (cfg.wslPython?.trim()) ? cfg.wslPython.trim() : '/root/.valis-venv/bin/python'
    def wslRunner = toWsl(runnerFileOf(cfg).getAbsolutePath())
    // Boşluk/özel karakter içeren her token'ı POSIX tek-tırnakla; --merge gibi düz bayraklar tırnaksız kalır.
    def needsQuote = { String t -> t != null && (t.contains(' ') || t.contains('"') || t.contains('(') || t.contains(')') || t.contains('&') || t.contains(';')) }
    def inner = ([wslPy, wslRunner] + runnerArgsWsl)
        .collect { def t = (it ?: '').toString(); needsQuote(t) ? qsh(t) : t }.join(' ')
    return ['wsl', 'bash', '-lc', inner]
}

// Hedef girdinin yerel dosyasını çöz — readImageData pahalı; hedef için BİR kez çağrılır.
def entrySlideFileVia = { entry ->
    def data = null
    try { data = entry.readImageData(); return slideFileOf(data) }
    catch (Throwable ignore) { return null }
    finally { try { data?.getServer()?.close() } catch (Throwable ig) {} }
}

// ── QuPath: seçili anotasyonları GeoJSON'a dışa aktar (VALIS girdisi) ────────
def exportAnnotations = { imageData, File outFile, anns ->
    try {
        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs()
        def list = (anns != null && !anns.isEmpty()) ? anns : imageData.getHierarchy().getAnnotationObjects()
        if (list == null || list.isEmpty())
            return [ok: false, error: 'Kaynak slaytta anotasyon yok — warp için önce bir bölge/anotasyon çizin (ve seçin).']
        PathIO.exportObjectsAsGeoJSON(outFile, list,
            PathIO.GeoJsonExportOptions.FEATURE_COLLECTION, PathIO.GeoJsonExportOptions.PRETTY_JSON)
        return [ok: true, file: outFile, count: list.size()]
    } catch (Throwable t) { return [ok: false, error: (t.getMessage() ?: t.getClass().getSimpleName())] }
}

// ── QuPath: warp'lı GeoJSON'u HEDEF slayda içe aktar (sentinel + kilit; idempotent) ──
def importWarpedToTarget = { project, targetEntry, File warpedFile ->
    if (warpedFile == null || !warpedFile.isFile())
        return [ok: false, error: 'Warp\'lı GeoJSON bulunamadı:\n' + (warpedFile?.getAbsolutePath() ?: '-')]
    def objs = null
    try {
        def ins = new java.io.FileInputStream(warpedFile)
        try { objs = PathIO.readObjectsFromGeoJSON(ins) } finally { ins.close() }
    } catch (Throwable t) { return [ok: false, error: 'GeoJSON okunamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }
    if (objs == null || objs.isEmpty()) return [ok: false, error: 'Warp\'lı GeoJSON\'da nesne yok.']
    objs.each { try { it.setName(VALIS_SENTINEL); it.setLocked(true) } catch (Throwable ignore) {} }
    def curData = QP.getCurrentImageData()
    def curEntry = null
    try { curEntry = (curData != null) ? project.getEntry(curData) : null } catch (Throwable ignore) {}
    boolean isLive = (curEntry != null && curEntry.getID() != null && targetEntry.getID() != null && curEntry.getID() == targetEntry.getID())
    def data = null; boolean opened = false
    try {
        data = isLive ? curData : targetEntry.readImageData()
        opened = !isLive
        def hier = data.getHierarchy()
        hier.removeObjects(hier.getAnnotationObjects().findAll { it.getName() == VALIS_SENTINEL }, false)
        hier.addObjects(objs)
        hier.fireHierarchyUpdate()
        if (isLive) { javafx.application.Platform.runLater { try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {} } }
        else { targetEntry.saveImageData(data) }
        return [ok: true, count: objs.size(), live: isLive]
    } catch (Throwable t) {
        return [ok: false, error: (t.getMessage() ?: t.getClass().getSimpleName())]
    } finally {
        try { if (opened && data != null) data.getServer()?.close() } catch (Throwable ignore) {}
    }
}

// ── QuPath: hizalanmış OME-TIFF'leri projeye ekle (FX thread; idempotent; syncChanges) ──
def addOmeToProject = { project, List omeFiles, Closure onDone ->
    javafx.application.Platform.runLater {
        int added = 0, skipped = 0, failed = 0
        def notes = []
        def existingNames = new HashSet<String>()
        try { project.getImageList().each { e -> def n = e.getImageName(); if (n) existingNames.add(n) } } catch (Throwable ignore) {}
        omeFiles.each { String p ->
            def f = new File(p)
            if (!f.isFile()) { failed++; notes << ('  • yok: ' + f.getName()); return }
            if (existingNames.any { it.contains(f.getName()) }) { skipped++; notes << ('  = zaten projede: ' + f.getName()); return }
            def server = null
            try {
                server = qupath.lib.images.servers.ImageServers.buildServer(f.toURI())
                qupath.lib.gui.commands.ProjectCommands.addSingleImageToProject(project, server, qupath.lib.images.ImageData.ImageType.UNSET)
                added++; notes << ('  ✓ eklendi: ' + f.getName())
            } catch (Throwable t) {
                failed++; notes << ('  ✗ eklenemedi: ' + f.getName() + ' — ' + (t.getMessage() ?: t.getClass().getSimpleName()))
            } finally { try { server?.close() } catch (Throwable ignore) {} }
        }
        boolean syncOk = true
        try { project.syncChanges() }
        catch (Throwable t) { syncOk = false; notes << ('  ✗ proje diske KAYDEDİLEMEDİ (syncChanges): ' + (t.getMessage() ?: t.getClass().getSimpleName())) }
        try { gui.refreshProject() } catch (Throwable ignore) {}
        onDone([added: added, skipped: skipped, failed: failed, syncOk: syncOk, notes: notes])
    }
}

// ── Arka plan: Python/Docker süreci (stream + timeout + iptal) ───────────────
// VALIS/torch LightGlue+DISK eşleştirici ağırlıkları torch/HF hub'dan iner; önbelleği veri
// köküne yönlendir ki C: dolmasın (bu makinede C: kronik dolu) ve indirilenler yeniden kullanılsın.
// NOT: Docker modunda konteyner kendi ortamını kullanır; bu env yalnız NATIVE süreç için etkilidir.
def applyCacheEnv = { pb ->
    try {
        def cache = new File(atolyeDataRoot(), 'cache'); cache.mkdirs()
        def hf = new File(cache, 'huggingface'); def env = pb.environment()
        env.put('HF_HOME', hf.getAbsolutePath()); env.put('HF_HUB_CACHE', new File(hf, 'hub').getAbsolutePath())
        env.put('TORCH_HOME', new File(cache, 'torch').getAbsolutePath())
    } catch (Throwable ignore) {}
}

// Native torch önbelleğinde (applyCacheEnv TORCH_HOME'u buraya yönlendirir) VALIS varsayılan model
// ağırlıkları (DISK 'depth-save' + LightGlue) indirilmiş mi? READY ekranında durum göstermek için.
def valisModelsCached = { ->
    try {
        def ck = new File(new File(new File(new File(atolyeDataRoot(), 'cache'), 'torch'), 'hub'), 'checkpoints')
        if (!ck.isDirectory()) return [cached: false, dir: ck, hasDisk: false, hasLG: false]
        def names = (ck.listFiles()?.findAll { it.isFile() }?.collect { it.getName().toLowerCase(java.util.Locale.ROOT) }) ?: []
        def hasDisk = names.any { it.contains('depth-save') }
        def hasLG   = names.any { it.contains('lightglue') }
        return [cached: (hasDisk && hasLG), dir: ck, hasDisk: hasDisk, hasLG: hasLG]
    } catch (Throwable t) { return [cached: false, dir: null, hasDisk: false, hasLG: false] }
}

// Çıktı klasöründe VALIS'in kaydettiği *_registrar.pickle var mı? ("Yeniden birleştir" düğmesini etkinleştirmek için).
// srcDir'in gövde adıyla eşleşen pickle tercih edilir (VALIS dst/<govde>/data/<govde>_registrar.pickle yazar).
def registrarPickleFor = { File resultsDir, File srcDir ->
    if (resultsDir == null || !resultsDir.isDirectory()) return null
    def cands = []
    try { resultsDir.eachFileRecurse(groovy.io.FileType.FILES) { f -> if (f.getName().endsWith('_registrar.pickle')) cands << f } } catch (Throwable ignore) {}
    if (cands.isEmpty()) return null
    def wanted = (srcDir != null) ? (srcDir.getName() + '_registrar.pickle') : null
    return (wanted ? cands.find { it.getName() == wanted } : null) ?: cands.sort { -it.lastModified() }[0]
}

def runProcess = { List cmd, java.util.concurrent.atomic.AtomicReference procRef,
                   java.util.concurrent.atomic.AtomicBoolean cancelledRef, Closure onLine ->
    def pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true)
    applyCacheEnv(pb)
    def proc
    try { proc = pb.start() }
    catch (Throwable e) { return [ok: false, exitCode: -1, error: 'Başlatılamadı: ' + (e.getMessage() ?: e.getClass().getSimpleName())] }
    procRef.set(proc)
    def last = new java.util.ArrayDeque()
    def resultJson = new java.util.concurrent.atomic.AtomicReference(null)
    try {
        def reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
        String line
        while ((line = reader.readLine()) != null) {
            last.addLast(line); while (last.size() > 60) last.pollFirst()
            if (line.startsWith('RESULT_JSON: ')) resultJson.set(line.substring('RESULT_JSON: '.length()))
            onLine(line)
            if (cancelledRef.get()) break
        }
        reader.close()
    } catch (Throwable ignore) {}
    boolean finished
    try { finished = proc.waitFor(PYTHON_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) }
    catch (InterruptedException ie) { proc.destroyForcibly(); return [ok: false, exitCode: -3, error: 'İptal edildi'] }
    if (!finished) { proc.destroyForcibly(); return [ok: false, exitCode: -2, error: 'Zaman aşımı (' + PYTHON_TIMEOUT_SECONDS + ' sn)'] }
    if (cancelledRef.get()) { proc.destroyForcibly(); return [ok: false, exitCode: -3, error: 'İptal edildi'] }
    int code = proc.exitValue()
    return [ok: (code == 0), exitCode: code, lastLines: last.join('\n'), resultJson: resultJson.get()]
}

// ── Headless ─────────────────────────────────────────────────────────────────
if (isHeadless) {
    def cfg = loadConfig()
    println "VALIS sihirbazı: mod=${cfg.mode} çalışmaKökü=${cfg.workDir} crop=${cfg.crop}"
    def miss = configMissing(cfg)
    if (!miss.isEmpty()) println "Eksik yapılandırma: ${miss.join(', ')}"
    println "VALIS sihirbazı için QuPath arayüzü gerekir (headless çalıştırılamaz)."
    return
}

// ── Durum makinesi ──────────────────────────────────────────────────────────
// CONFIG_INCOMPLETE | CONFIG | READY | CMD_READY | RUNNING | RESULT | ERROR
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def processRef    = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def busyLabelRef  = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def resultKindRef = new java.util.concurrent.atomic.AtomicReference('run')   // 'run' | 'prefetch' — RESULT ekranı butonları buna göre
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def dockerTextRef = new java.util.concurrent.atomic.AtomicReference('')
def nativeTextRef = new java.util.concurrent.atomic.AtomicReference('')
def dockerUsableRef = new java.util.concurrent.atomic.AtomicBoolean(false)   // Docker komutu bu slayt konumu için geçerli mi (tek-kök mount'a sığıyor mu)
def targetEntryRef= new java.util.concurrent.atomic.AtomicReference(null)   // seçili hedef ProjectImageEntry (ada göre DEĞİL — kimlik/getID)
def dockerNameRef = new java.util.concurrent.atomic.AtomicReference(null)   // çalışan docker konteyner adı (iptal/zaman aşımında durdurmak için)
def lastGeojsonOutRef = new java.util.concurrent.atomic.AtomicReference(null)
def lastOmeDirRef     = new java.util.concurrent.atomic.AtomicReference(null)
def lastMergeOutRef   = new java.util.concurrent.atomic.AtomicReference(null)   // üretilen birleşik multipleks OME-TIFF
def lastCompositeRef  = new java.util.concurrent.atomic.AtomicBoolean(false)    // son merge doğal-renk bileşik miydi (Brightfield) yoksa çok-kanallı mı (Fluorescence)
def lastTargetEntryRef= new java.util.concurrent.atomic.AtomicReference(null)
def lastCmdRef        = new java.util.concurrent.atomic.AtomicReference(null)   // Doğrudan çalıştır için seçili komut
def wslTextRef        = new java.util.concurrent.atomic.AtomicReference('')     // üretilen WSL komutu (CMD_READY)
// Çok-slayt seçimi + marker adları + merge seçenekleri (render'lar arası KALICI; entry-ID anahtarlı)
def includeIdsRef = new java.util.concurrent.atomic.AtomicReference(new java.util.LinkedHashSet())   // merge/kayıt setine dahil entry-ID'ler (kaynak hariç; kaynak daima dahil)
def markerMapRef  = new java.util.concurrent.atomic.AtomicReference(new java.util.HashMap())          // entry-ID -> marker adı
def stainMapRef   = new java.util.concurrent.atomic.AtomicReference(new java.util.HashMap())          // entry-ID -> boya tipi override ('hed'|'he'|'dab'|'rgb'); yoksa ada göre çıkarım / global mod
def colorMapRef   = new java.util.concurrent.atomic.AtomicReference(new java.util.HashMap())          // entry-ID -> marker işaret (DAB/Eozin) kanal rengi 'RRGGBB' (hex); yoksa otomatik palet
def mergeEnabledRef = new java.util.concurrent.atomic.AtomicBoolean(true)
def mergeModeRef  = new java.util.concurrent.atomic.AtomicReference('hed')      // 'hed' | 'dab' | 'rgb'
def mergeLevelRef = new java.util.concurrent.atomic.AtomicReference('2')
def writeOmeRef   = new java.util.concurrent.atomic.AtomicBoolean(false)        // ayrı hizalı OME slaytları da yaz (yavaş)
def compositeRef  = new java.util.concurrent.atomic.AtomicBoolean(false)        // çok-kanallı yerine doğal-renk parlak-alan RGB bileşik
def rigidOnlyRef  = new java.util.concurrent.atomic.AtomicBoolean(false)        // HIZLI: yalnız rigid kayıt (non-rigid atla)
def stageSlidesRef= new java.util.concurrent.atomic.AtomicBoolean(false)        // slaytları WSL-yerel diske kopyala (ilk kayıt I/O ~6x hızlı) — 'stage' adı JavaFX Stage ile çakışır, kullanma
def maxProcDimRef = new java.util.concurrent.atomic.AtomicReference('')         // kayıt için maks. kenar (px); boş = VALIS varsayılanı
// CONFIG alan referansları
def modeChoiceRef = new java.util.concurrent.atomic.AtomicReference(null)
def pyFieldRef    = new java.util.concurrent.atomic.AtomicReference(null)
def wslPyFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def workFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def outFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def memFieldRef   = new java.util.concurrent.atomic.AtomicReference(null)
def cropChoiceRef = new java.util.concurrent.atomic.AtomicReference(null)
def render  // forward decl

def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text)
    b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    return b
}
def busyBar = { -> def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb }
def copyToClipboard = { String txt ->
    def cb = javafx.scene.input.Clipboard.getSystemClipboard()
    def c = new javafx.scene.input.ClipboardContent(); c.putString(txt ?: ''); cb.setContent(c)
}

def saveConfig = {
    def mc = modeChoiceRef.get(); def py = pyFieldRef.get(); def wp = wslPyFieldRef.get(); def wf = workFieldRef.get(); def of = outFieldRef.get(); def mf = memFieldRef.get(); def cc = cropChoiceRef.get()
    prefs.put(PREF_MODE,    (mc != null && mc.getValue() != null) ? mc.getValue().toString() : 'docker')
    prefs.put(PREF_PYTHON,  (py != null ? py.getText() : '').trim())
    prefs.put(PREF_WSLPY,   (wp != null ? wp.getText() : '/root/.valis-venv/bin/python').trim())
    prefs.put(PREF_WORKDIR, (wf != null ? wf.getText() : '').trim())
    prefs.put(PREF_OUTDIR,  (of != null ? of.getText() : '').trim())
    prefs.put(PREF_MEM,     (mf != null ? mf.getText() : '20').trim())
    prefs.put(PREF_CROP,    (cc != null && cc.getValue() != null) ? cc.getValue().toString() : 'overlap')
    try { prefs.flush() } catch (Throwable ignore) {}
    step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render()
}

// ── Boya tipi (renk ayrıştırma) — slayt başı ────────────────────────────────
// Parlak-alan slaytları QuPath'te RGB'dir; merge'de ANLAMLI kanallar ayrıştırılmış boyalardır.
// İHK → Hematoksilen+DAB, H&E → Hematoksilen+Eozin. H&E slaydını H-DAB vektörleriyle ayrıştırmak
// SAHTE bir 'DAB' kanalı üretir. Ad "HE"/"H&E" gibiyse H&E çıkarılır; aksi halde global mod kullanılır.
def STAIN_LABEL_TO_CODE = ['H-DAB (Hem+DAB)': 'hed', 'H&E (Hem+Eozin)': 'he', 'Yalnız DAB': 'dab', 'Ham RGB': 'rgb']
def STAIN_CODE_TO_LABEL = ['hed': 'H-DAB (Hem+DAB)', 'he': 'H&E (Hem+Eozin)', 'dab': 'Yalnız DAB', 'rgb': 'Ham RGB']
def inferStainType = { String stem ->
    def s = (stem ?: '').toLowerCase(java.util.Locale.ROOT).trim()
    if (s == 'he' || s == 'h&e' || s == 'hande' || s.contains('h&e') ||
        s.startsWith('he-') || s.startsWith('he_') || s.startsWith('he ') || s.startsWith('he.') ||
        s.endsWith('-he') || s.endsWith('_he') || s.endsWith(' he')) return 'he'
    return null   // null → global mod (İHK varsayılanı)
}
// Bir slaydın etkin boya tipini çöz: kullanıcı override > ad çıkarımı > global mod.
def effectiveStain = { String entryId, String stem, String globalMode ->
    def m = (entryId != null) ? stainMapRef.get().get(entryId) : null
    (m?.toString()?.trim()) ? m.toString().trim() : (inferStainType(stem) ?: (globalMode ?: 'hed'))
}
// ── Marker işaret (DAB/Eozin) kanal rengi — sihirbazda seçilebilir ────────────
// Varsayılan palet runner'ın _DAB_PALETTE / _EOSIN_COLOR değerleriyle AYNI hex'lerdir; kullanıcı
// seçmezse otomatik renkle aynı görünür, seçerse --merge-color ile o markerın DAB/Eozin kanalına geçer.
def DAB_PALETTE_HEX = ['AA6E28', '00C85A', 'C83CC8', '00BED2', 'F08C00', 'D22828']
def EOSIN_HEX = 'EB5A8C'
def colorToHex = { javafx.scene.paint.Color c -> String.format(java.util.Locale.US, '%02X%02X%02X',
    (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255)) }
def hexToColor = { String hex -> try { javafx.scene.paint.Color.web('#' + (hex ?: 'AA6E28')) } catch (Throwable t) { javafx.scene.paint.Color.web('#AA6E28') } }

// ── Çalıştırma planı (dışa aktar + komutları kur) ───────────────────────────
def prepareRun = { cfg, boolean reuseRegistrar = false ->
    def project = QP.getProject()
    if (project == null) return [ok: false, error: 'Proje açık değil — slaytları AYNI projeye ekleyin.']
    def imageData = QP.getCurrentImageData()
    if (imageData == null) return [ok: false, error: 'Kaynak/referans slayt açık değil — açın, sonra "⟳ Yenile".']
    def srcFile = slideFileOf(imageData)
    if (srcFile == null) return [ok: false, error: 'Kaynak slayt yerel dosya değil (VALIS yerel dosya gerektirir).']
    def srcDir = srcFile.getParentFile()
    if (srcDir == null) return [ok: false, error: 'Kaynak slaydın klasörü çözülemedi (sürücü kökünde olamaz).']
    def srcEntry = null
    try { srcEntry = project.getEntry(imageData) } catch (Throwable ignore) {}
    def srcId = srcEntry?.getID()?.toString()

    // ── Hizalanacak/merge edilecek EK slaytlar (kaynak daima referans + dahil) ──
    def includeIds = includeIdsRef.get() ?: new java.util.LinkedHashSet()
    def selectedEntries = []
    project.getImageList().each { e ->
        def id = e?.getID()?.toString()
        if (id != null && id != srcId && includeIds.contains(id)) selectedEntries << e
    }
    // Warp hedefi seçildiyse register setine de dahil et (anotasyon warp'ı için gerekli).
    def targetEntry = targetEntryRef.get()
    if (targetEntry != null && targetEntry.getID() != null) {
        def tid = targetEntry.getID().toString()
        if (tid != srcId && !selectedEntries.any { it.getID()?.toString() == tid }) selectedEntries << targetEntry
    }
    if (selectedEntries.isEmpty())
        return [ok: false, error: 'En az bir EK slayt seçin ("Hizalanacak slaytlar" listesinden işaretleyin) — VALIS ≥2 slaytı birlikte hizalar.']

    // Her seçilinin yerel dosyasını çöz + KAYNAKLA AYNI klasör doğrula (karışık klasör/büyütme = extract_area hatası).
    def selFiles = []
    def badFolder = []
    for (e in selectedEntries) {
        def f = entrySlideFileVia(e)
        if (f == null) return [ok: false, error: 'Seçili slaytın yerel dosyası çözülemedi: ' + (e.getImageName() ?: '?')]
        try {
            if (f.getParentFile() == null || srcDir == null || f.getParentFile().getCanonicalPath() != srcDir.getCanonicalPath())
                badFolder << (e.getImageName() ?: f.getName())
        } catch (Throwable t) { badFolder << (e.getImageName() ?: f.getName()) }
        selFiles << [entry: e, file: f]
    }
    if (!badFolder.isEmpty())
        return [ok: false, error: 'Tüm seçili slaytlar KAYNAKLA AYNI klasörde olmalı (VALIS bir klasördeki slaytları birlikte hizalar).\n  kaynak klasörü: ' + srcDir?.getAbsolutePath() + '\n  farklı klasör : ' + badFolder.join(', ')]

    def workRoot = workRootOf(cfg)
    if (cfg.mode == 'docker' && toContainer(srcDir.getAbsolutePath(), workRoot) == null)
        return [ok: false, error: 'Docker modu TEK KÖK gerektirir: slayt klasörü çalışma klasörünün ALTINDA olmalı.\n  slaytlar     : ' + srcDir.getAbsolutePath() + '\n  çalışma kökü : ' + workRoot.getAbsolutePath() + '\nWSL ya da native modu kullanın (bu kısıt yok) ya da slaytları çalışma klasörü altına taşıyın.']
    if (cfg.mode == 'docker' && toContainer(outRootOf(cfg).getAbsolutePath(), workRoot) == null)
        return [ok: false, error: 'Docker modu TEK KÖK gerektirir: çıktı klasörü de çalışma klasörünün ALTINDA olmalı.\n  çıktı kökü   : ' + outRootOf(cfg).getAbsolutePath() + '\n  çalışma kökü : ' + workRoot.getAbsolutePath() + '\nWSL/native modu kullanın ya da çıktı klasörünü boş bırakın (= çalışma klasörü).']

    def resultsDir = resultsDirOf(cfg); resultsDir.mkdirs()
    def omeDir = omeDirOf(cfg); omeDir.mkdirs()
    def gjDir = geojsonDirOf(cfg); gjDir.mkdirs()

    // ── Marker adları (entry-ID -> marker; boşsa dosya gövdesi). --merge-name gövde=marker. ──
    def markerMap = markerMapRef.get() ?: [:]
    def stemOf = { File f -> def n = f.getName(); def i = n.toLowerCase(java.util.Locale.ROOT).indexOf('.'); (i > 0 ? n.substring(0, i) : n) }
    def markerFor = { entry, File f -> def id = entry?.getID()?.toString(); def m = (id != null) ? markerMap.get(id) : null; (m?.toString()?.trim()) ? m.toString().trim() : stemOf(f) }
    def imageFiles = [srcFile]; selFiles.each { imageFiles << it.file }
    def mergeNames = [stemOf(srcFile) + '=' + markerFor(srcEntry, srcFile)]
    selFiles.each { mergeNames << (stemOf(it.file) + '=' + markerFor(it.entry, it.file)) }
    // ── Slayt-başı boya tipi (İHK→hed / H&E→he) — gövde=tip. Etkin tip: override > ad çıkarımı > global mod. ──
    def gMode = mergeModeRef.get() ?: 'hed'
    def mergeStains = [stemOf(srcFile) + '=' + effectiveStain(srcEntry?.getID()?.toString(), stemOf(srcFile), gMode)]
    selFiles.each { mergeStains << (stemOf(it.file) + '=' + effectiveStain(it.entry?.getID()?.toString(), stemOf(it.file), gMode)) }
    // ── Marker işaret (DAB/Eozin) rengi — MARKER=RRGGBB. colorMap = kullanıcının seçtikleri; yoksa
    // STABİL proje-indeksli palet (READY'deki defColorHex ile AYNI → gösterilen == geçen). ──
    def colorMap = colorMapRef.get() ?: [:]
    def _fbPal = ['AA6E28', '00C85A', 'C83CC8', '00BED2', 'F08C00', 'D22828']
    def _idIndex = [:]
    try { project.getImageList().eachWithIndex { pe, pi -> def pid = pe?.getID()?.toString(); if (pid != null) _idIndex[pid] = pi } } catch (Throwable ignore) {}
    def colorFor = { entry, File f ->
        def id = entry?.getID()?.toString(); def h = (id != null) ? colorMap.get(id) : null
        def hex = (h?.toString()?.trim()) ? h.toString().trim() : _fbPal[(((id != null ? (_idIndex[id] ?: 0) : 0) as int) % _fbPal.size())]
        return markerFor(entry, f) + '=' + hex
    }
    def mergeColors = [colorFor(srcEntry, srcFile)]
    selFiles.each { mergeColors << colorFor(it.entry, it.file) }

    // ── Anotasyon warp — YALNIZ warp hedefi seçildiyse (opsiyonel). Aksi halde sadece kayıt+merge. ──
    def tgtFile = null; def gjIn = null; def gjOut = null; int annCount = 0
    if (targetEntry != null && targetEntry.getID() != null) {
        if (srcId != null && targetEntry.getID().toString() == srcId)
            return [ok: false, error: 'Warp hedefi kaynakla aynı olamaz — farklı bir hedef seçin ya da hedefi boş bırakın.']
        tgtFile = entrySlideFileVia(targetEntry)
        if (tgtFile == null) return [ok: false, error: 'Warp hedefi yerel dosyası çözülemedi.']
        def sanitize = { String s -> (s ?: 'x').replaceAll('[^A-Za-z0-9._-]', '_') }
        def pairKey = srcDir.getAbsolutePath() + '|' + srcFile.getName() + '|' + tgtFile.getName()
        def pairId = sanitize(srcFile.getName()) + '__' + sanitize(tgtFile.getName()) + '__' + Integer.toHexString(pairKey.hashCode())
        gjIn  = new File(gjDir, 'kaynak_' + pairId + '.geojson')
        gjOut = new File(gjDir, 'warp_' + pairId + '.geojson')
        try { if (gjOut.isFile()) gjOut.delete() } catch (Throwable ignore) {}
        def sel = imageData.getHierarchy().getSelectionModel().getSelectedObjects().findAll { it.isAnnotation() }
        def exp = exportAnnotations(imageData, gjIn, sel)
        if (!exp.ok) return [ok: false, error: exp.error]
        annCount = exp.count
    }

    def wr = writeRunner(cfg)
    if (!wr.ok) return [ok: false, error: 'Köprü betiği yazılamadı: ' + wr.error]

    // ── Merge seçenekleri ──
    boolean mergeEnabled = mergeEnabledRef.get()
    def mergeMode = mergeModeRef.get() ?: 'hed'
    def mergeLevel = (mergeLevelRef.get() ?: '2')
    boolean composite = compositeRef.get()
    def _mergeBase = composite ? 'multiplex_composite' : ('multiplex_' + mergeMode)
    // Yeniden birleştirmede SÜRÜMLÜ ad (_r<millis>): QuPath önceki dosyayı açık tutarken bile çakışmaz/kilitlenmez.
    def mergeOutFile = mergeEnabled ? new File(outRootOf(cfg), _mergeBase + (reuseRegistrar ? ('_r' + System.currentTimeMillis()) : '') + '.ome.tiff') : null
    // Normal koşuda üstüne yaz; silinemiyorsa (QuPath'te açık) NET hata ver (save_ome_tiff'te sessiz başarısızlık yerine).
    if (!reuseRegistrar && mergeOutFile != null && mergeOutFile.isFile()) {
        try {
            if (!mergeOutFile.delete())
                return [ok: false, error: 'Önceki birleşik dosya silinemedi (QuPath\'te açık olabilir):\n' + mergeOutFile.getAbsolutePath() + '\nProjeden kaldırın / QuPath\'te kapatın ya da "Yeniden birleştir" (sürümlü dosya yazar) kullanın.']
        } catch (Throwable t) { return [ok: false, error: 'Önceki birleşik dosya silinemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }
    }
    // Yeniden birleştirmede ayrı hizalı OME yazma HER ZAMAN kapalı (amaç: yalnız merge; aynı kilit tuzağı + gereksiz).
    boolean writeOme = reuseRegistrar ? false : writeOmeRef.get()
    def _maxProc = (maxProcDimRef.get() ?: '').toString().trim()
    if (_maxProc && !(_maxProc ==~ /\d+/)) return [ok: false, error: 'Kayıt için maks. kenar SAYI olmalı (px) ya da boş: "' + _maxProc + '"']

    def buildArgs = { String kind ->
        def conv = { File f ->
            if (f == null) return null
            if (kind == 'container') return toContainer(f.getAbsolutePath(), workRoot)
            if (kind == 'wsl') return toWsl(f.getAbsolutePath())
            return f.getAbsolutePath()
        }
        def imgs = imageFiles.collect { conv(it) }
        def mrg = mergeEnabled ? [enabled: true, mode: mergeMode, level: mergeLevel, out: conv(mergeOutFile), names: mergeNames, stains: mergeStains, colors: mergeColors, composite: composite] : [enabled: false]
        def opts = [rigidOnly: rigidOnlyRef.get(), maxProcessedDim: (_maxProc ?: null),
                    stage: (stageSlidesRef.get() && cfg.mode == 'wsl' && !reuseRegistrar), reuseRegistrar: reuseRegistrar]
        runArgs(cfg, conv(srcDir), conv(resultsDir), conv(omeDir), cfg.crop, imgs, conv(srcFile),
                conv(gjIn), conv(srcFile), conv(tgtFile), conv(gjOut), mrg, writeOme, opts)
    }

    def dockerName = 'atolye-valis-' + System.currentTimeMillis()
    dockerNameRef.set(cfg.mode == 'docker' ? dockerName : null)
    lastGeojsonOutRef.set(gjOut); lastOmeDirRef.set(omeDir); lastTargetEntryRef.set(targetEntry); lastMergeOutRef.set(mergeOutFile); lastCompositeRef.set(composite)

    def containerArgs = buildArgs('container')
    def dockerUsable = !containerArgs.any { it == null }
    def dockerBlock = null
    if (!dockerUsable) {
        def srcBad = (toContainer(srcDir.getAbsolutePath(), workRoot) == null)
        def outBad = (toContainer(outRootOf(cfg).getAbsolutePath(), workRoot) == null)
        dockerBlock = (srcBad && outBad) ? 'both' : (srcBad ? 'src' : (outBad ? 'out' : 'other'))
    }
    return [ok: true, dockerCmd: (dockerUsable ? dockerCmd(cfg, workRoot, dockerName, containerArgs) : null),
            dockerUsable: dockerUsable, dockerBlock: dockerBlock, nativeCmd: nativeCmd(cfg, buildArgs('host')),
            wslCmd: wslCmd(cfg, buildArgs('wsl')), dockerName: dockerName, srcDir: srcDir, annCount: annCount,
            omeDir: omeDir, gjOut: gjOut, mergeOut: mergeOutFile, imageCount: imageFiles.size(), mergeEnabled: mergeEnabled]
}

// Süreci durdur: önce SIGTERM (docker CLI iletir), docker modunda AYRICA `docker stop/kill <name>`
// (yalnız yerel docker CLI'yı öldürmek konteyneri durdurmaz → yetim konteyner riskini kapatır).
def killProc = { ->
    try { processRef.get()?.destroy() } catch (Throwable ignore) {}
    def dn = dockerNameRef.get()
    if (dn) { try { new ProcessBuilder(['docker', 'stop', dn.toString()]).redirectErrorStream(true).start() } catch (Throwable ignore) {} }
    try { Thread.sleep(1500) } catch (Throwable ignore) {}
    try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {}
    if (dn) { try { new ProcessBuilder(['docker', 'kill', dn.toString()]).redirectErrorStream(true).start() } catch (Throwable ignore) {} }
}

def startRun = { List cmd, String busyLabel, Closure onSuccess ->
    cancelledRef.set(false)
    def timedOut = new java.util.concurrent.atomic.AtomicBoolean(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO); logAreaRef.set(la)
    busyLabelRef.set(busyLabel); step.set('RUNNING'); render()
    // Zaman aşımı bekçisi — engelli readLine'a BAĞLI DEĞİL: süreç sessizce takılsa bile süreyi doldurunca öldürür.
    def watchdog = new Thread({
        try { Thread.sleep(PYTHON_TIMEOUT_SECONDS * 1000L) } catch (InterruptedException ie) { return }
        timedOut.set(true); cancelledRef.set(true); killProc()
    }, 'AtolyeVALIS-Watchdog')
    watchdog.setDaemon(true); watchdog.start()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runProcess(cmd, processRef, cancelledRef, appendLine)
        try { watchdog.interrupt() } catch (Throwable ignore) {}
        javafx.application.Platform.runLater {
            if (timedOut.get()) {
                errorTextRef.set('Zaman aşımı (' + (PYTHON_TIMEOUT_SECONDS / 3600) + ' saat) — süreç durduruldu' + (dockerNameRef.get() ? ' (docker stop denendi).' : '.'))
                step.set('ERROR'); render(); return
            }
            if (!r.ok) {
                errorTextRef.set('VALIS başarısız / iptal (çıkış kodu: ' + r.exitCode + ')\n\n' + (r.error ?: '') + '\n' + (r.lastLines ?: ''))
                step.set('ERROR'); render(); return
            }
            onSuccess()
        }
    }, 'AtolyeVALIS-Run')
    worker.setDaemon(true); worker.start()
}
// Ana hizalama akışı — startRun'ı OME/GeoJSON sonuç ekranıyla sarar (mevcut çağrı yerleri değişmez).
def startDirectRun = { List cmd ->
    startRun(cmd, 'VALIS çalışıyor', {
        def sb = new StringBuilder()
        def mo = lastMergeOutRef.get(); boolean isComp = lastCompositeRef.get()
        sb << "VALIS TAMAMLANDI\n═══════════════════════════════\n\n"
        sb << (isComp ? "Doğal-renk bileşik (RGB): " : "Çok-kanallı multipleks  : ") << (mo?.getAbsolutePath() ?: '(üretilmedi)') << "\n"
        sb << "Warp'lı GeoJSON        : " << (lastGeojsonOutRef.get()?.getAbsolutePath() ?: '-') << "\n\n"
        sb << "Aşağıdan sonuçları QuPath'e aktarın:\n"
        sb << (isComp
            ? " • \"Birleşik multipleksi ekle\" → doğal-renk RGB'yi projeye ekler. Tür: BRIGHTFIELD; tek görünüm, kanal seçilemez (\"slayt gibi\").\n"
            : " • \"Birleşik multipleksi ekle\" → çok-kanallı hizalı görüntüyü ekler. Tür: FLUORESCENCE; Ctrl+Shift+C ile 2–3 kanalı aç/kapat.\n")
        sb << " • \"Warp'lı anotasyonu içe aktar\" → hedef slayda (VALIS adlı, kilitli) anotasyon ekler.\n\n"
        sb << "Çıktı türleri: Ekler → Görüntü Hizalama § 7.2 (hangisini ne zaman/hangi tür).\n"
        sb << "Hizalamayı GÖRSEL doğrulayın.\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
        resultKindRef.set('run'); resultTextRef.set(sb.toString()); step.set('RESULT'); render()
    })
}
// VALIS varsayılan model ağırlıklarını (DISK + LightGlue, ~50 MB) yerel önbelleğe indir — native modda
// applyCacheEnv TORCH_HOME'u veri kökü altına yönlendirir → ilk hizalama ortada durmaz + çevrimdışı çalışır.
// Docker modunda ağırlıklar konteynerde yönetilir; bu yerel ön-indirme yalnız native içindir (dockerCmd'ye dokunulmaz).
def doPrefetch = { cfg ->
    if (cfg.mode == 'docker') {
        Dialogs.showInfoNotification('Model ağırlıkları', 'Docker modunda model ağırlıkları konteyner içinde yönetilir; bu yerel ön-indirme yalnız native mod içindir.')
        return
    }
    if (cfg.mode == 'wsl') {
        // WSL süreci Windows önbelleğini (bu düğmenin yazacağı yeri) OKUMAZ (WSLENV yok) → yanıltıcı olur.
        // WSL modunda ağırlıklar ilk hizalamada WSL venv/ev dizinine iner; ayrı ön-indirme gereksiz.
        Dialogs.showInfoNotification('Model ağırlıkları', 'WSL modunda model ağırlıkları ilk hizalama sırasında WSL ortamında iner (Windows önbelleği kullanılmaz); ayrı ön-indirme gerekmez.')
        return
    }
    if (!cfg.python?.trim()) { Dialogs.showErrorMessage('Model ağırlıkları', 'Önce native python.exe yapılandırın (Yapılandır).'); return }
    def wr = writeRunner(cfg)
    if (!wr.ok) { Dialogs.showErrorMessage('Model ağırlıkları', 'Köprü betiği yazılamadı: ' + (wr.error ?: '?')); return }
    def ck = new File(new File(new File(new File(atolyeDataRoot(), 'cache'), 'torch'), 'hub'), 'checkpoints')
    startRun(nativeCmd(cfg, ['prefetch']), 'Model ağırlıkları indiriliyor', {
        def sb = new StringBuilder()
        sb << "MODEL AĞIRLIKLARI HAZIR ✅\n═══════════════════════════════\n\n"
        sb << "VALIS varsayılan modelleri (DISK + LightGlue) yerel önbelleğe indirildi/doğrulandı.\n"
        sb << "Önbellek (native): " << ck.getAbsolutePath() << "\n\n"
        sb << "Ayrıntılı önce/sonra dökümü yukarıdaki çalışma günlüğünde gösterildi.\n"
        sb << "Sonraki hizalamalar bu ağırlıkları çevrimdışı kullanır (native mod).\n"
        sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
        resultKindRef.set('prefetch'); resultTextRef.set(sb.toString()); step.set('RESULT'); render()
    })
}

def doImportWarped = {
    def project = QP.getProject(); def te = lastTargetEntryRef.get(); def gj = lastGeojsonOutRef.get()
    if (project == null || te == null) { Dialogs.showErrorMessage('İçe aktarım', 'Proje/hedef bilgisi yok — önce "Komut üret" ya da "Doğrudan çalıştır".'); return }
    if (gj == null || !gj.isFile()) { Dialogs.showWarningNotification('İçe aktarım', 'Warp\'lı GeoJSON henüz yok — önce üretilen VALIS komutunu çalıştırın, bittiğinde tekrar deneyin.'); return }
    def r = importWarpedToTarget(project, te, gj)
    if (r.ok) Dialogs.showInfoNotification('Warp\'lı anotasyon', r.count + ' nesne hedef slayda aktarıldı' + (r.live ? ' (açık slayt).' : ' (diske kaydedildi).'))
    else Dialogs.showErrorMessage('İçe aktarım başarısız', r.error ?: '?')
}
def doAddOme = {
    def project = QP.getProject(); def od = lastOmeDirRef.get()
    if (project == null || od == null) return   // sessiz — merge birincil; ayrı OME opsiyonel
    def files = []
    try { od.listFiles({ d, n -> def ln = n.toLowerCase(java.util.Locale.ROOT); ln.endsWith('.ome.tiff') || ln.endsWith('.ome.tif') } as java.io.FilenameFilter)?.each { files << it.getAbsolutePath() } } catch (Throwable ignore) {}
    if (files.isEmpty()) return   // ayrı hizalı OME üretilmemiş (--no-ome) → sessiz geç; merge doAddMergeOut ile eklenir
    addOmeToProject(project, files, { res ->
        def msg = String.format(java.util.Locale.US, '%d eklendi, %d zaten vardı, %d hata.', (res.added ?: 0), (res.skipped ?: 0), (res.failed ?: 0))
        boolean bad = ((res.failed ?: 0) > 0) || (res.syncOk == false)
        if (bad) Dialogs.showErrorMessage('OME-TIFF — kısmen/hata', msg + '\n\n' + ((res.notes) ? res.notes.join('\n') : ''))
        else Dialogs.showInfoNotification('OME-TIFF (ayrı hizalı slaytlar)', msg)
    })
}
// Birleşik multipleks OME-TIFF'i projeye ekle (merge birincil çıktı; wizard koşusundan sonra).
def doAddMergeOut = {
    def project = QP.getProject(); def mf = lastMergeOutRef.get()
    if (project == null || mf == null) return
    if (!(mf instanceof File)) { try { mf = new File(mf.toString()) } catch (Throwable ignore) { return } }
    if (!mf.isFile()) { Dialogs.showWarningNotification('Multipleks ekle', 'Birleşik multipleks henüz yok — önce üretilen VALIS komutunu çalıştırın:\n' + mf.getAbsolutePath()); return }
    boolean isComp = lastCompositeRef.get()
    addOmeToProject(project, [mf.getAbsolutePath()], { res ->
        def msg = String.format(java.util.Locale.US, '%d eklendi, %d zaten vardı, %d hata.', (res.added ?: 0), (res.skipped ?: 0), (res.failed ?: 0))
        boolean bad = ((res.failed ?: 0) > 0) || (res.syncOk == false)
        def hint = isComp
            ? '\nDoğal-renk parlak-alan bileşiği → tür sorulursa "Brightfield" seçin. Tek pişmiş RGB görüntüdür; kanal seçilemez ("slayt gibi" görünüm).'
            : '\nÇok kanallı → tür sorulursa "Fluorescence" seçin. Ctrl+Shift+C ile 2–3 kanalı (ör. marker-DAB + marker-Hematoksilen) "Göster" ile aç/kapat; beyaz zemin için "Invert background".'
        if (bad) Dialogs.showErrorMessage('Birleşik görüntü — kısmen/hata', msg + '\n\n' + ((res.notes) ? res.notes.join('\n') : ''))
        else Dialogs.showInfoNotification('Birleşik görüntü', msg + hint)
    })
}

// ── DIŞARIDAN içe aktar (CLI/WSL çıktısı) ────────────────────────────────────
// Wizard'ın "Sonuçları içe aktar" butonları YALNIZ wizard'ın kendi ürettiği koşunun
// referanslarını (lastOmeDirRef/lastGeojsonOutRef) kullanır → WSL/terminalde elle koşulmuş
// bir sonuç bunlarla aktarılamaz. Bu iki yol herhangi bir GeoJSON/OME-TIFF dosyasını seçtirip
// aktarır (importWarpedToTarget/addOmeToProject mantığını yeniden kullanır; değiştirmez).
def importGeojsonToCurrent = { File gjFile ->
    def data = QP.getCurrentImageData()
    if (data == null) return [ok: false, error: 'Açık slayt yok — warp\'lı anotasyonun ait olduğu (hedef) slaydı açın, sonra tekrar deneyin.']
    def objs = null
    try {
        def ins = new java.io.FileInputStream(gjFile)
        try { objs = PathIO.readObjectsFromGeoJSON(ins) } finally { ins.close() }
    } catch (Throwable t) { return [ok: false, error: 'GeoJSON okunamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }
    if (objs == null || objs.isEmpty()) return [ok: false, error: 'GeoJSON\'da nesne yok.']
    objs.each { try { it.setName(VALIS_SENTINEL); it.setLocked(true) } catch (Throwable ignore) {} }
    try {
        def hier = data.getHierarchy()
        hier.removeObjects(hier.getAnnotationObjects().findAll { it.getName() == VALIS_SENTINEL }, false)
        hier.addObjects(objs)
        hier.fireHierarchyUpdate()
        javafx.application.Platform.runLater { try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {} }
        return [ok: true, count: objs.size()]
    } catch (Throwable t) { return [ok: false, error: (t.getMessage() ?: t.getClass().getSimpleName())] }
}
def doImportGeojsonFromFile = {
    def gj = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Warp\'lı GeoJSON seç (VALIS çıktısı)')
    if (gj == null) return
    def r = importGeojsonToCurrent(gj)
    if (r.ok) Dialogs.showInfoNotification('Warp\'lı anotasyon', r.count + ' nesne açık slayda aktarıldı (VALIS adlı, kilitli). Hizalamayı görsel doğrulayın.')
    else Dialogs.showErrorMessage('İçe aktarım başarısız', r.error ?: '?')
}
def doAddOmeFromFile = {
    def project = QP.getProject()
    if (project == null) { Dialogs.showErrorMessage('OME ekle', 'Proje açık değil — önce bir QuPath projesi açın.'); return }
    def f = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'Hizalanmış/birleşik OME-TIFF seç (VALIS çıktısı)')
    if (f == null) return
    def ln = f.getName().toLowerCase(java.util.Locale.ROOT)
    if (!(ln.endsWith('.ome.tiff') || ln.endsWith('.ome.tif') || ln.endsWith('.tiff') || ln.endsWith('.tif'))) {
        Dialogs.showWarningNotification('OME ekle', 'Seçilen dosya bir OME-TIFF/TIFF değil:\n' + f.getName()); return
    }
    addOmeToProject(project, [f.getAbsolutePath()], { res ->
        def msg = String.format(java.util.Locale.US, '%d eklendi, %d zaten vardı, %d hata.', (res.added ?: 0), (res.skipped ?: 0), (res.failed ?: 0))
        boolean bad = ((res.failed ?: 0) > 0) || (res.syncOk == false)
        if (bad) Dialogs.showErrorMessage('OME-TIFF — kısmen/hata', msg + '\n\n' + ((res.notes) ? res.notes.join('\n') : ''))
        else Dialogs.showInfoNotification('OME-TIFF', msg + '\nÇok kanallı birleşik görüntü ise tür sorulur → "Fluorescence" seçin; sonra Brightness/Contrast (Ctrl+Shift+C) ile kanallara renk atayın.')
    })
}

// ── "Yeniden birleştir" (kayıtlı hizalamadan) düğmeleri — READY ve RESULT'ta paylaşılır ──
// Kayıtlı registrar varsa yeniden KAYIT yapmadan (~5 dk) mevcut boya/renk/seviye/composite ile
// yeniden birleştirir. Pickle yoksa düğmeler pasiftir.
def addRemergeAction = { cfg, actions ->
    def srcDirForCheck = null
    try { def d = QP.getCurrentImageData(); def sf = (d != null) ? slideFileOf(d) : null; srcDirForCheck = sf?.getParentFile() } catch (Throwable ignore) {}
    def pkl = registrarPickleFor(resultsDirOf(cfg), srcDirForCheck)
    def tip = (pkl != null)
        ? ('Kayıtlı hizalamayı KULLANIR (yeniden KAYIT yok, ~5 dk): mevcut boya/renk/seviye/composite ile SADECE yeniden birleştirir [' + pkl.getName() + ']')
        : 'Henüz kayıtlı hizalama yok — önce "Doğrudan çalıştır" ile bir kez tam kayıt yapın.'
    def runBtn = navButton('↻ Yeniden birleştir (kayıtlı hizalamadan)', {
        def plan = prepareRun(cfg, true)
        if (!plan.ok) { errorTextRef.set(plan.error); step.set('ERROR'); render(); return }
        startDirectRun(cfg.mode == 'wsl' ? plan.wslCmd : (cfg.mode == 'native' ? plan.nativeCmd : plan.dockerCmd))
    }, tip)
    runBtn.setDisable(pkl == null); actions.add(runBtn)
    def copyBtn = navButton('↻ …komutu (WSL) kopyala', {
        def plan = prepareRun(cfg, true)
        if (!plan.ok) { Dialogs.showErrorMessage('Yeniden birleştir', plan.error ?: '?'); return }
        copyToClipboard(cmdToText(plan.wslCmd))
        Dialogs.showInfoNotification('Yeniden birleştir', 'WSL yeniden-birleştir komutu panoya kopyalandı (yeniden KAYIT yok). Bir terminalde çalıştırın.')
    }, tip)
    copyBtn.setDisable(pkl == null); actions.add(copyBtn)
}

// ── Render ───────────────────────────────────────────────────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def cfg = loadConfig()
    if (cfg.mode == 'native' && !cfg.python?.trim()) {
        def vp = valisVenvPython(); if (vp != null) { prefs.put(PREF_PYTHON, vp.getAbsolutePath()); try { prefs.flush() } catch (Throwable t) {}; cfg = loadConfig() }
    }
    def title = new javafx.scene.control.Label(); title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10); center.setPadding(new javafx.geometry.Insets(14)); center.getChildren().add(title)
    def actions = new ArrayList()
    def addGuidance = { String txt -> def l = new javafx.scene.control.Label(txt); l.setWrapText(true); l.setMaxWidth(Double.MAX_VALUE); center.getChildren().add(l) }
    def addMono = { String txt -> def ta = new javafx.scene.control.TextArea(txt ?: ''); ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO); javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(ta) }

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('VALIS yapılandırması gerekli')
        def miss = configMissing(cfg)
        addGuidance('VALIS Python\'dur ve QuPath dışında koşar. İki yol: (1) Docker (önerilen; tüm bağımlılıklar hazır), ' +
            '(2) native venv (Python 3.10 + libvips + Java). Eksik/geçersiz:\n  • ' + (miss.isEmpty() ? '(yok)' : miss.join('\n  • ')) +
            '\n\nKurulum ayrıntıları: Kaynaklar → İleri kurulumlar → VALIS.')
        actions.add(navButton('Python ortam yöneticisi (native)', {
            new Thread({ try {
                def url = null
                try { url = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getResource('/scripts/yardimci-python-ortam-yoneticisi.groovy') } catch (Throwable t) {}
                if (url == null) url = this.getClass().getResource('/scripts/yardimci-python-ortam-yoneticisi.groovy')
                if (url != null) { def cl = this.getClass().getClassLoader(); try { cl = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getClassLoader() } catch (Throwable t) {}; new GroovyShell(cl).evaluate(url.getText('UTF-8'), 'env.groovy') }
                else javafx.application.Platform.runLater { Dialogs.showInfoNotification('Ortam yöneticisi', 'Menüden açın: Yardımcılar → Python köprüleri → Atölye Python ortam yöneticisi') }
            } catch (Throwable t) {} } as Runnable).start()
        }, 'Native mod için VALIS venv\'ini (Python 3.10) kurar; libvips + Java\'yı ayrıca kurun'))
        actions.add(navButton('Yapılandır ▶', { step.set('CONFIG'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    } else if (cur == 'CONFIG') {
        title.setText('VALIS yapılandırması')
        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def modeChoice = new javafx.scene.control.ChoiceBox(); ['docker', 'native', 'wsl'].each { modeChoice.getItems().add(it) }; modeChoice.setValue(['docker', 'native', 'wsl'].contains(cfg.mode) ? cfg.mode : 'docker')
        def pyField = new javafx.scene.control.TextField(cfg.python ?: ''); pyField.setPrefColumnCount(34)
        def wslPyField = new javafx.scene.control.TextField(cfg.wslPython ?: '/root/.valis-venv/bin/python'); wslPyField.setPrefColumnCount(34)
        def wfField = new javafx.scene.control.TextField(cfg.workDir ?: ''); wfField.setPrefColumnCount(34)
        def outField = new javafx.scene.control.TextField(cfg.outDir ?: ''); outField.setPrefColumnCount(34)
        def memField = new javafx.scene.control.TextField(cfg.mem ?: '20'); memField.setPrefColumnCount(6)
        def cropChoice = new javafx.scene.control.ChoiceBox(); CROP_OPTIONS.each { cropChoice.getItems().add(it) }; cropChoice.setValue(CROP_OPTIONS.contains(cfg.crop) ? cfg.crop : 'overlap')
        modeChoiceRef.set(modeChoice); pyFieldRef.set(pyField); wslPyFieldRef.set(wslPyField); workFieldRef.set(wfField); outFieldRef.set(outField); memFieldRef.set(memField); cropChoiceRef.set(cropChoice)
        def browseFile = { f -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, 'python.exe seç'); if (x != null) f.setText(x.getAbsolutePath()) }
        def browseDir = { f, ttl -> def x = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, ttl, null); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Mod:'), modeChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Çalışma klasörü (tek kök):'), wfField, navButton('…', { browseDir(wfField, 'Çalışma klasörü seç') }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Çıktı klasörü (boş = çalışma klasörü):'), outField, navButton('…', { browseDir(outField, 'Çıktı klasörü seç') }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('python.exe (native):'), pyField, navButton('…', { browseFile(pyField) }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('WSL python (wsl mod):'), wslPyField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Docker bellek (GB):'), memField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Kırpma (crop):'), cropChoice)
        center.getChildren().add(grid)
        addGuidance('Çıktılar (results / ome / geojson / multiplex) çıktı klasörünün altına yazılır; boşsa çalışma klasörü kullanılır. ' +
            'Native modda çıktı başka bir sürücüde olabilir (ör. büyük OME-TIFF\'ler için E:\\). ' +
            'Docker modu: girdi VE çıktı çalışma klasörünün ALTINDA olmalı (tek mount → /work). ' +
            'Native mod: python.exe VALIS venv\'ini göstermeli; libvips + Java ayrıca kurulmalı. ' +
            'WSL modu (Windows\'ta ÖNERİLEN — yerel Windows VALIS bu makinede çökebilir): WSL içindeki python yolunu (valis-wsi + pyvips[binary] kurulu venv) verin; ' +
            'Windows yolları otomatik /mnt/... olarak çevrilir. Docker\'ın tek-kök şartı WSL\'de YOKTUR.')
        actions.add(navButton('İptal', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('Kaydet ▶', { saveConfig() }))
    } else if (cur == 'READY') {
        title.setText('VALIS — hizala + anotasyon warp')
        def project = QP.getProject(); def imageData = QP.getCurrentImageData()
        if (project == null) { addGuidance('Proje açık değil — kaynak ve hedef slaytları AYNI projeye ekleyin, sonra "⟳ Yenile".'); actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() })); actions.add(navButton('⟳ Yenile', { render() })); actions.add(navButton('Kapat', { stage.close() })) }
        else if (imageData == null) { addGuidance('Kaynak slayt (anotasyonun çizildiği) açık değil — açın, sonra "⟳ Yenile".'); actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() })); actions.add(navButton('⟳ Yenile', { render() })); actions.add(navButton('Kapat', { stage.close() })) }
        else {
            def srcFile = slideFileOf(imageData); def srcName = imageData.getServer().getMetadata().getName() ?: (srcFile?.getName() ?: '?')
            def srcEntry0 = null; try { srcEntry0 = project.getEntry(imageData) } catch (Throwable ignore) {}
            def srcId0 = srcEntry0?.getID()?.toString()
            def others = project.getImageList().findAll { it != null && (srcId0 == null || it.getID()?.toString() != srcId0) }
            def includeIds = includeIdsRef.get(); def markerMap = markerMapRef.get()
            def stemOfName = { String nm -> if (nm == null) return 'slayt'; def i = nm.toLowerCase(java.util.Locale.ROOT).indexOf('.'); (i > 0 ? nm.substring(0, i) : nm) }
            int selAnn = 0; try { selAnn = imageData.getHierarchy().getSelectionModel().getSelectedObjects().findAll { it.isAnnotation() }.size() } catch (Throwable ignore) {}
            def sb = new StringBuilder()
            sb << "Kaynak/referans (açık): " << srcName << (srcFile != null ? ("   [" + srcFile.getParentFile()?.getName() + "]") : "  (yerel değil!)") << "\n"
            sb << "Mod          : " << cfg.mode << (cfg.mode == 'wsl' ? "  (Windows'ta önerilen)" : (cfg.mode == 'native' ? "  (Windows'ta çökebilir → WSL önerilir)" : "")) << "\n"
            sb << "Çıktı kökü   : " << outRootOf(cfg).getAbsolutePath() << (cfg.outDir?.trim() ? "" : "  (= çalışma kökü)") << "\n"
            def mc = valisModelsCached()
            sb << "Model ağırlıkları: " << ((cfg.mode == 'docker') ? "Docker konteynerinde yönetilir"
                : ((cfg.mode == 'wsl') ? "WSL ortamında yönetilir (ilk koşuda iner)"
                : (mc.cached ? "✅ yerelde hazır (DISK + LightGlue)"
                             : "⬇ indirilmemiş — \"Model ağırlıklarını indir\""))) << "\n"
            sb << "Warp anotasyon: " << (selAnn > 0 ? (selAnn + " seçili") : "seçili yok → TÜM anotasyonlar") << "\n"
            addMono(sb.toString())

            // ── Hizalanacak slaytlar (çok-seçim) + marker adı + boya tipi ──
            addGuidance('Hizalanacak slaytları işaretleyin (kaynakla AYNI klasörde olmalı; VALIS ≥2 slaytı birlikte hizalar). ' +
                '"Marker adı" = kanal adı ön-eki. "Boya" = renk ayrıştırma tipi: İHK için H-DAB (→ {marker}-DAB + -Hematoksilen), ' +
                'H&E için H&E (→ -Hematoksilen + -Eozin); ad "HE" içerenler otomatik H&E seçilir. ' +
                '"Renk" = o markerın İŞARET kanalının (DAB/Eozin) QuPath\'teki rengi — her marker için farklı renk seçin (varsayılanlar zaten ayrıktır). ' +
                'Renk yalnız ÇOK-KANALLI multipleksi etkiler; doğal-renk bileşik gerçek boya rengini kullanır. Hematoksilen daima mavi-mor.')
            def stainMap = stainMapRef.get()
            def stainBoxFor = { String id, String stem ->
                def box = new javafx.scene.control.ChoiceBox()
                ['H-DAB (Hem+DAB)', 'H&E (Hem+Eozin)', 'Yalnız DAB', 'Ham RGB'].each { box.getItems().add(it) }
                def code = effectiveStain(id, stem, mergeModeRef.get())
                box.setValue(STAIN_CODE_TO_LABEL[code] ?: 'H-DAB (Hem+DAB)')
                box.valueProperty().addListener({ o, ov, nv -> if (id != null && nv != null) stainMap.put(id, STAIN_LABEL_TO_CODE[nv.toString()] ?: 'hed') } as javafx.beans.value.ChangeListener)
                return box
            }
            // Marker işaret (DAB/Eozin) kanal rengi — ColorPicker. Varsayılan runner paletiyle aynı; her satıra
            // benzersiz bir varsayılan atanır ve gösterilen renk colorMap'e YAZILIR (böylece gösterilen = geçen).
            def colorMap = colorMapRef.get()
            // Varsayılan renk STABİL proje-indeksine göre — render'lar arası DEĞİŞMEZ ve prepareRun ile
            // AYNI hesaplanır (gösterilen == geçen). colorMap YALNIZ kullanıcının AÇIKÇA seçtiği renkleri
            // tutar (varsayılan saklanmaz) → boya tipi sonradan değişse bile takılı kalmaz, çift renk oluşmaz.
            def idIndex = [:]
            try { project.getImageList().eachWithIndex { pe, pi -> def pid = pe?.getID()?.toString(); if (pid != null) idIndex[pid] = pi } } catch (Throwable ignore) {}
            def defColorHex = { String id -> DAB_PALETTE_HEX[(((id != null ? (idIndex[id] ?: 0) : 0) as int) % DAB_PALETTE_HEX.size())] }
            def colorBoxFor = { String id, String stem ->
                def curHex = (id != null ? colorMap.get(id)?.toString() : null) ?: defColorHex(id)
                def cp = new javafx.scene.control.ColorPicker(hexToColor(curHex)); cp.setPrefWidth(64)
                cp.valueProperty().addListener({ o, ov, nv -> if (id != null && nv != null) colorMap.put(id, colorToHex(nv)) } as javafx.beans.value.ChangeListener)
                return cp
            }
            def rowsBox = new javafx.scene.layout.VBox(4)
            def hdr = new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('✓'),
                (({ def l = new javafx.scene.control.Label('Slayt'); l.setPrefWidth(220); l })()),
                (({ def l = new javafx.scene.control.Label('Marker adı'); l.setPrefWidth(110); l })()),
                (({ def l = new javafx.scene.control.Label('Boya'); l.setPrefWidth(140); l })()),
                new javafx.scene.control.Label('Renk'))
            rowsBox.getChildren().add(hdr)
            // Kaynak satırı — daima dahil (referans); marker + boya + renk düzenlenebilir
            if (srcId0 != null && !markerMap.containsKey(srcId0)) markerMap.put(srcId0, stemOfName(srcName))
            def srcCb = new javafx.scene.control.CheckBox(); srcCb.setSelected(true); srcCb.setDisable(true)
            def srcMk = new javafx.scene.control.TextField(markerMap.get(srcId0)?.toString() ?: stemOfName(srcName)); srcMk.setPrefColumnCount(11)
            srcMk.textProperty().addListener({ o, ov, nv -> if (srcId0 != null) markerMap.put(srcId0, nv) } as javafx.beans.value.ChangeListener)
            def srcLbl = new javafx.scene.control.Label(srcName + '  (referans)'); srcLbl.setPrefWidth(220)
            def srcStainBox = stainBoxFor(srcId0, stemOfName(srcName)); srcStainBox.setPrefWidth(140)
            rowsBox.getChildren().add(new javafx.scene.layout.HBox(8, srcCb, srcLbl, srcMk, srcStainBox, colorBoxFor(srcId0, stemOfName(srcName))))
            others.each { e ->
                def id = e.getID()?.toString(); def nm = e.getImageName() ?: '(adsız)'
                if (id != null && !markerMap.containsKey(id)) markerMap.put(id, stemOfName(nm))
                def cb = new javafx.scene.control.CheckBox(); cb.setSelected(includeIds.contains(id))
                cb.selectedProperty().addListener({ o, ov, nv -> if (id != null) { if (nv) includeIds.add(id) else includeIds.remove(id) } } as javafx.beans.value.ChangeListener)
                def lbl = new javafx.scene.control.Label(nm); lbl.setPrefWidth(220)
                def mk = new javafx.scene.control.TextField(markerMap.get(id)?.toString() ?: stemOfName(nm)); mk.setPrefColumnCount(11)
                mk.textProperty().addListener({ o, ov, nv -> if (id != null) markerMap.put(id, nv) } as javafx.beans.value.ChangeListener)
                def stBox = stainBoxFor(id, stemOfName(nm)); stBox.setPrefWidth(140)
                rowsBox.getChildren().add(new javafx.scene.layout.HBox(8, cb, lbl, mk, stBox, colorBoxFor(id, stemOfName(nm))))
            }
            def rowsScroll = new javafx.scene.control.ScrollPane(rowsBox); rowsScroll.setFitToWidth(true); rowsScroll.setPrefHeight(160)
            center.getChildren().add(rowsScroll)

            // ── Hız seçenekleri (kayıt aşaması) ──
            def rigidCb = new javafx.scene.control.CheckBox('Hızlı: yalnız rigid kayıt (non-rigid atla; daha az hassas)'); rigidCb.setSelected(rigidOnlyRef.get())
            rigidCb.selectedProperty().addListener({ o, ov, nv -> rigidOnlyRef.set(nv) } as javafx.beans.value.ChangeListener)
            def maxProcField = new javafx.scene.control.TextField(maxProcDimRef.get() ?: ''); maxProcField.setPrefColumnCount(5)
            maxProcField.textProperty().addListener({ o, ov, nv -> maxProcDimRef.set(nv) } as javafx.beans.value.ChangeListener)
            def hrow = new javafx.scene.layout.HBox(8, rigidCb, new javafx.scene.control.Label('Kayıt için maks. kenar (px, boş=varsayılan):'), maxProcField)
            hrow.setAlignment(javafx.geometry.Pos.CENTER_LEFT); center.getChildren().add(hrow)
            if (cfg.mode == 'wsl') {
                def stageCb = new javafx.scene.control.CheckBox('Slaytları WSL-yerel diske kopyala (ilk kaydı ~6× hızlandırır; ~2.5 GB/slayt yer gerekir)'); stageCb.setSelected(stageSlidesRef.get())
                stageCb.selectedProperty().addListener({ o, ov, nv -> stageSlidesRef.set(nv) } as javafx.beans.value.ChangeListener)
                center.getChildren().add(stageCb)
            }
            addGuidance('Hız: "Yalnız rigid" non-rigid aşamasını (~40 dk olabilir) atlar — seri kesitlerde çoğu zaman yeterli, hassasiyet biraz düşer. ' +
                (cfg.mode == 'wsl' ? '"WSL-yerel diske kopyala" slaytları yavaş /mnt sürücüsünden hızlı diske alır (ilk kayıt çok hızlanır). ' : '') +
                'Bir kez tam kayıt yaptıktan sonra boya/renk/seviye/composite değiştirmek için "↻ Yeniden birleştir" ile ~5 dk\'da yeniden birleştirin (yeniden KAYIT yok). Ayrıntı: Ekler → Görüntü Hizalama § 7.4.')

            // ── Birleşik multipleks (merge) seçenekleri ──
            def mergeCb = new javafx.scene.control.CheckBox('Birleşik multipleks (merge) üret'); mergeCb.setSelected(mergeEnabledRef.get())
            mergeCb.selectedProperty().addListener({ o, ov, nv -> mergeEnabledRef.set(nv) } as javafx.beans.value.ChangeListener)
            def mmChoice = new javafx.scene.control.ChoiceBox(); ['hed', 'dab', 'rgb'].each { mmChoice.getItems().add(it) }; mmChoice.setValue(['hed', 'dab', 'rgb'].contains(mergeModeRef.get()) ? mergeModeRef.get() : 'hed')
            mmChoice.valueProperty().addListener({ o, ov, nv -> if (nv != null) mergeModeRef.set(nv.toString()) } as javafx.beans.value.ChangeListener)
            def lvlField = new javafx.scene.control.TextField(mergeLevelRef.get() ?: '2'); lvlField.setPrefColumnCount(3)
            lvlField.textProperty().addListener({ o, ov, nv -> mergeLevelRef.set(nv) } as javafx.beans.value.ChangeListener)
            def mrow = new javafx.scene.layout.HBox(8, mergeCb, new javafx.scene.control.Label('varsayılan kanal modu:'), mmChoice, new javafx.scene.control.Label('piramit seviyesi:'), lvlField)
            mrow.setAlignment(javafx.geometry.Pos.CENTER_LEFT); center.getChildren().add(mrow)
            def compCb = new javafx.scene.control.CheckBox('Doğal-renk parlak-alan bileşiği (RGB, "slayt gibi" — QuPath\'te Brightfield; TEK görünüm, kanal seçilemez)'); compCb.setSelected(compositeRef.get())
            compCb.selectedProperty().addListener({ o, ov, nv -> compositeRef.set(nv) } as javafx.beans.value.ChangeListener)
            center.getChildren().add(compCb)
            def omeCb = new javafx.scene.control.CheckBox('Ayrı hizalanmış slaytlar (OME-TIFF) da üret (YAVAŞ; merge yeterliyse gerekmez)'); omeCb.setSelected(writeOmeRef.get())
            omeCb.selectedProperty().addListener({ o, ov, nv -> writeOmeRef.set(nv) } as javafx.beans.value.ChangeListener)
            center.getChildren().add(omeCb)
            addGuidance('Varsayılan kanal modu, yukarıda "Boya"su elle seçilmemiş ve adından H&E çıkarılamayan İHK slaytlarına uygulanır: ' +
                'hed = Hematoksilen + DAB (ÖNERİLEN) · dab = yalnız DAB · rgb = ham RGB. ' +
                'H&E slaytları satırdaki "Boya = H&E" ile Hematoksilen + Eozin üretir. Seviye 0 = tam çözünürlük (yavaş/büyük), 2 ≈ görüntüleme için iyi denge; ' +
                '"↻ Yeniden birleştir" ile hızlı denerken seviye 3 daha da hızlıdır (küçük warp+yazma).')
            addGuidance('İKİ ÇIKTI TÜRÜ — amacınıza göre seçin (ayrıntı: Ekler → Görüntü Hizalama § 7.2):\n' +
                '  • BİLEŞİK KAPALI (varsayılan) → ÇOK-KANALLI multipleks (multiplex_*.ome.tiff). QuPath\'te tür = FLUORESCENCE; ' +
                'her kanalı ayrı aç/kapat, 2–3 markerı üst üste bindir. Markerları AYIRT/KARŞILAŞTIR için budur.\n' +
                '  • BİLEŞİK AÇIK → DOĞAL-RENK parlak-alan RGB (multiplex_composite.ome.tiff, Beer-Lambert). QuPath\'te tür = BRIGHTFIELD; ' +
                'beyaz zemin, doğal boya renkleri, "SLAYT GİBİ" görünür — ama TEK görüntüdür, kanal seçilemez. Tek slaytta doğal görünüm; ' +
                'çok slaytta sentetik bileşik (çakışan boyalar koyulaşır).')

            // ── Anotasyon warp hedefi (opsiyonel) ──
            def targetChoice = new javafx.scene.control.ChoiceBox(); targetChoice.getItems().add(null); others.each { targetChoice.getItems().add(it) }
            targetChoice.setConverter(new javafx.util.StringConverter() {
                String toString(Object e) { e == null ? '(yok — yalnız hizala + merge)' : (((qupath.lib.projects.ProjectImageEntry) e).getImageName() ?: '(adsız)') }
                Object fromString(String s) { return null }
            })
            def prevT = targetEntryRef.get()
            def restore = (prevT != null) ? others.find { it.getID()?.toString() == prevT.getID()?.toString() } : null
            targetChoice.setValue(restore); if (restore == null) targetEntryRef.set(null)
            targetChoice.valueProperty().addListener({ o, ov, nv -> targetEntryRef.set(nv) } as javafx.beans.value.ChangeListener)
            def hb = new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('Anotasyon warp hedefi (ops.):'), targetChoice); hb.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            center.getChildren().add(hb)
            addGuidance('"Komut üret" seçili modun (Docker/native/WSL) komutunu hazırlar; "Doğrudan çalıştır" QuPath içinden koşar. Warp hedefi seçilirse kaynağın anotasyonu o slayda taşınır.')
            // Dış (CLI/WSL) sonuçlarını içe aktar — wizard koşusu gerektirmez; herhangi bir dosyayı seçtirir.
            def impSep = new javafx.scene.control.Separator()
            center.getChildren().add(impSep)
            addGuidance('Terminalde/WSL\'de üretilmiş sonuçlarınız varsa doğrudan içe aktarın (wizard koşusu gerekmez): warp\'lı GeoJSON\'u AÇIK slayda, birleşik/hizalanmış OME-TIFF\'i projeye ekler.')
            def impBox = new javafx.scene.layout.HBox(8,
                navButton('Warp\'lı GeoJSON içe aktar (dosyadan)…', { doImportGeojsonFromFile() }, 'Bir GeoJSON dosyası seçip AÇIK (hedef) slayda VALIS-adlı, kilitli anotasyon olarak ekler'),
                navButton('OME-TIFF ekle (dosyadan)…', { doAddOmeFromFile() }, 'Birleşik/hizalanmış bir OME-TIFF dosyası seçip projeye ekler (çok kanallı ise "Fluorescence" seçin)'))
            impBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
            center.getChildren().add(impBox)
            actions.add(navButton('Kapat', { stage.close() }))
            actions.add(navButton('Yapılandır', { step.set('CONFIG'); render() }))
            actions.add(navButton('Model ağırlıklarını indir (~50 MB)', { doPrefetch(cfg) }, 'VALIS varsayılan modellerini (DISK + LightGlue) yerel önbelleğe indirir; ilk hizalamanın ortasında beklememek + çevrimdışı için (yalnız native mod)'))
            actions.add(navButton('Komut üret ▶', {
                def plan = prepareRun(cfg)
                if (!plan.ok) { errorTextRef.set(plan.error); step.set('ERROR'); render(); return }
                nativeTextRef.set(cmdToText(plan.nativeCmd))
                dockerUsableRef.set((plan.dockerUsable ? true : false) as boolean)
                def wrPath  = workRootOf(cfg).getAbsolutePath()
                def srcPath = plan.srcDir?.getAbsolutePath() ?: '(bilinmiyor)'
                def outPath = outRootOf(cfg).getAbsolutePath()
                def naHead  = 'Docker BU YAPILANDIRMADA kullanılamaz.\n\n' +
                    'NEDEN: Docker konteynerine yalnızca TEK klasör bağlanır (çalışma klasörü → /work) ve konteyner\n' +
                    'yalnızca o klasörün ALTINDAKİ dosyaları görebilir/oraya yazabilir. Farklı bir sürücüdeki (ör. E:\\)\n' +
                    'ya da çalışma klasörü dışındaki yollar konteynere ULAŞMAZ — bu yüzden Docker komutu üretilmedi.\n\n'
                def naMsg
                if (plan.dockerBlock == 'out')
                    naMsg = naHead +
                        'SORUN: çıktı klasörü çalışma klasörünün altında değil.\n' +
                        '  • çıktı klasörü   : ' + outPath + '\n' +
                        '  • çalışma klasörü : ' + wrPath + '   (→ /work)\n\n' +
                        'ÇÖZÜM (birini seçin):\n' +
                        '  1) NATIVE komutu kullanın — bu kısıt yoktur; çıktı herhangi bir sürücüde olabilir.\n' +
                        '  2) Çıktı klasörünü BOŞ bırakın (= çalışma klasörü) ya da çalışma klasörünün altına alın (Yapılandır → Çıktı klasörü).'
                else if (plan.dockerBlock == 'both')
                    naMsg = naHead +
                        'SORUN: hem slayt hem çıktı klasörü çalışma klasörünün dışında.\n' +
                        '  • slayt klasörü    : ' + srcPath + '\n' +
                        '  • çıktı klasörü     : ' + outPath + '\n' +
                        '  • çalışma klasörü   : ' + wrPath + '   (→ /work)\n\n' +
                        'ÇÖZÜM (birini seçin):\n' +
                        '  1) NATIVE komutu kullanın — bu kısıt yoktur.\n' +
                        '  2) Çalışma klasörünü İKİSİNİ DE içeren bir üst klasör yapın (Yapılandır → Çalışma klasörü).\n' +
                        '  3) Slaytları + çıktıyı çalışma klasörünün altına taşıyın.'
                else
                    naMsg = naHead +
                        'SORUN: slaytlar çalışma klasörünün altında değil.\n' +
                        '  • slayt klasörü   : ' + srcPath + '\n' +
                        '  • çalışma klasörü : ' + wrPath + '   (→ /work)\n\n' +
                        'ÇÖZÜM (birini seçin):\n' +
                        '  1) NATIVE komutu kullanın — bu kısıt yoktur; slaytlar herhangi bir sürücüde olabilir.\n' +
                        '  2) Çalışma klasörünü slaytları İÇEREN bir üst klasör yapın (Yapılandır → Çalışma klasörü).\n' +
                        '  3) Slaytları çalışma klasörünün altına kopyalayın.'
                dockerTextRef.set(plan.dockerUsable ? cmdToText(plan.dockerCmd) : naMsg)
                wslTextRef.set(cmdToText(plan.wslCmd))
                lastCmdRef.set(cfg.mode == 'wsl' ? plan.wslCmd : (cfg.mode == 'native' ? plan.nativeCmd : plan.dockerCmd))
                step.set('CMD_READY'); render()
            }))
            actions.add(navButton('Doğrudan çalıştır ▶', {
                def plan = prepareRun(cfg)
                if (!plan.ok) { errorTextRef.set(plan.error); step.set('ERROR'); render(); return }
                startDirectRun(cfg.mode == 'wsl' ? plan.wslCmd : (cfg.mode == 'native' ? plan.nativeCmd : plan.dockerCmd))
            }, 'Yapılandırılmış modu (Docker/native/WSL) QuPath içinden çalıştırır — uzun sürebilir'))
            addRemergeAction(cfg, actions)
        }
    } else if (cur == 'CMD_READY') {
        title.setText('VALIS komutları (kopyala-çalıştır)')
        def dockerOk = dockerUsableRef.get()
        addGuidance('Yapılandırılan mod: ' + cfg.mode + '. Bir terminalde ilgili komutu çalıştırın; bittiğinde "Sonuçları içe aktar". ' +
            'WSL, Windows\'ta önerilen yoldur (yerel VALIS bu makinede 0xC0000005 ile çökebilir). ' +
            (dockerOk ? '' : 'Docker BU konumda kullanılamaz (girdi/çıktı çalışma klasörü dışında → tek-kök mount\'a sığmıyor; ayrıntı aşağıda).'))
        def parts = []
        def wslBlock    = '# WSL (Windows\'ta önerilen):\n' + wslTextRef.get()
        def nativeBlock = '# Native venv:\n' + nativeTextRef.get()
        def dockerBlock = dockerOk ? ('# Docker:\n' + dockerTextRef.get()) : ('# Docker (bu konumda kullanılamaz):\n' + dockerTextRef.get())
        if (cfg.mode == 'wsl')        { parts << wslBlock; parts << nativeBlock; parts << dockerBlock }
        else if (cfg.mode == 'native'){ parts << nativeBlock; parts << wslBlock; parts << dockerBlock }
        else                          { parts << dockerBlock; parts << wslBlock; parts << nativeBlock }
        def ta = new javafx.scene.control.TextArea(parts.join('\n\n'))
        ta.setEditable(false); ta.setWrapText(true); ta.setStyle(MONO); javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(ta)
        actions.add(navButton('◀ Geri', { step.set('READY'); render() }))
        actions.add(navButton('Kopyala (WSL)', { copyToClipboard(wslTextRef.get()) }))
        actions.add(navButton('Kopyala (native)', { copyToClipboard(nativeTextRef.get()) }))
        if (dockerOk) actions.add(navButton('Kopyala (Docker)', { copyToClipboard(dockerTextRef.get()) }))
        actions.add(navButton('Doğrudan çalıştır ▶', { def c = lastCmdRef.get(); if (c != null) startDirectRun(c) }))
        actions.add(navButton('Sonuçları içe aktar', { doImportWarped(); doAddOme(); doAddMergeOut() }, 'Warp\'lı anotasyonu hedefe + OME-TIFF\'leri + birleşik multipleksi projeye ekler'))
    } else if (cur == 'RUNNING') {
        title.setText(busyLabelRef.get() + '…')
        addGuidance('VALIS koşuyor (kayıt uzun sürebilir; günlük aşağıda akıyor). Zaman aşımı: ' + (PYTHON_TIMEOUT_SECONDS / 3600) + ' saat.')
        center.getChildren().add(busyBar())
        def la = logAreaRef.get(); if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) }
        actions.add(navButton('İptal et', { cancelledRef.set(true); new Thread({ killProc() } as Runnable).start() }, 'Süreci durdurur; docker modunda konteyneri de durdurur (docker stop/kill)'))
    } else if (cur == 'RESULT') {
        def rkind = resultKindRef.get()
        title.setText(rkind == 'prefetch' ? 'Model ağırlıkları hazır ✅' : 'Tamamlandı ✅')
        addMono(resultTextRef.get())
        // Model indirmeden sonra ilk pencereye (READY) dönüp komut üret/çalıştır; hizalama sonrası içe-aktarma butonları.
        actions.add(navButton('◀ Ana ekrana dön', { step.set('READY'); render() }, 'İlk pencereye döner — "Komut üret" / "Doğrudan çalıştır"'))
        if (rkind != 'prefetch') {
            actions.add(navButton('Birleşik multipleksi ekle', { doAddMergeOut() }, 'Çok kanallı hizalı multipleksi (marker-DAB / -Hematoksilen) projeye ekler'))
            actions.add(navButton('Warp\'lı anotasyonu içe aktar', { doImportWarped() }))
            actions.add(navButton('Ayrı OME slaytları ekle', { doAddOme() }, 'Varsa ayrı hizalanmış slaytları ekler (yalnız "ayrı OME de üret" seçiliyse)'))
            addRemergeAction(cfg, actions)
        }
        actions.add(navButton('Kapat', { stage.close() }))
    } else { // ERROR
        title.setText('Hata')
        addMono(errorTextRef.get())
        actions.add(navButton('◀ Geri', { step.set('READY'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    }

    def topChk = new javafx.scene.control.CheckBox('Üstte tut'); topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ o, ov, nv -> alwaysTop.set(nv); if (stage != null) stage.setAlwaysOnTop(nv) } as javafx.beans.value.ChangeListener)
    def spacer = new javafx.scene.layout.Region(); javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8); bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT); bar.getChildren().add(topChk); bar.getChildren().add(spacer); bar.getChildren().addAll(actions)
    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlıdır; hizalamayı görsel doğrulayın. Klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE); disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar); bottom.setPadding(new javafx.geometry.Insets(10))
    // READY ekranı (çok-slayt listesi + merge + içe-aktarma) uzun → yalnız o ekranda merkezi kaydırılabilir yap
    // (RUNNING/CMD_READY/RESULT ekranları VBox.Vgrow ile büyüyen log/komut alanı kullandığından sarılmaz).
    def rootCenter = center
    if (cur == 'READY') {
        def centerScroll = new javafx.scene.control.ScrollPane(center); centerScroll.setFitToWidth(true)
        centerScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER)
        rootCenter = centerScroll
    }
    def root = new javafx.scene.layout.BorderPane(); root.setCenter(rootCenter); root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 900, 720))
}

step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('VALIS hizalama sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ VALIS hizalama sihirbazı açıldı."
