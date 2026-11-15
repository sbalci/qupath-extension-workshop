#!/usr/bin/env python3
"""
sectra_dicom_to_qupath.py — Sectra PACS DICOM (GSPS) anotasyonlarini QuPath GeoJSON'a cevirir.

Atolye koprusu (qupath-extension-workshop). Kaynak proje:
  Balci S. "Using Sectra PACS Annotations in QuPath", github.com/sbalci/sectra-qupath
  MIT lisansi - Zenodo DOI 10.5281/zenodo.15542395 - OSF 10.17605/OSF.IO/5KR7Q

Bu surum atolye sihirbazi (Extensions -> Atolye -> Yardimcilar -> Sectra PACS anotasyon
sihirbazi) icin yeniden duzenlendi:
  * Alt-komutlar: `selftest` ve `convert`.
  * Goruntu boyutlari QuPath'ten --width/--height ile gelir (openslide GEREKMEZ);
    yoksa DICOM DisplayedAreaSelectionSequence'a dusulur.
  * Cikti GeoJSON ozelliklerine QuPath sihirbazinin okudugu `classification` (sinif adi),
    `color` ([r,g,b]) ve `name` (Sectra metni + olcum) gomulur; renk semasi --color-scheme
    ile secilir (geometry | label).
  * Tek bagimlilik: pydicom (numpy / geojson / openslide GEREKMEZ).

Kullanim:
  python sectra_dicom_to_qupath.py selftest
  python sectra_dicom_to_qupath.py convert --dicom ann.dcm --out ann.geojson \
         [--width 100000 --height 80000] [--color-scheme geometry|label]

Yalniz arastirma/egitim amacli veri aktarimi yapar; klinik karar uretmez.
"""

import argparse
import hashlib
import json
import math
import os
import re
import sys
import traceback

SCHEME_GEOMETRY = "geometry"
SCHEME_LABEL = "label"

# Geometri tipine gore renk (kaynak deponun semasi)
GEOMETRY_COLORS = {
    "CIRCLE": [255, 215, 0],     # altin
    "POLYLINE": [30, 144, 255],  # dodger blue
    "POLYGON": [255, 69, 0],     # orange red
    "ARROW": [0, 191, 255],      # deep sky blue
    "POINT": [255, 0, 255],      # magenta
}

# Etiket metnindeki anahtar kelimeye gore renk (kullanicinin Sectra etiketinden)
LABEL_KEYWORD_COLORS = [
    (("tumor", "tumor", "tumour", "tm"), [255, 0, 0]),
    (("adeno",), [220, 20, 60]),
    (("stroma",), [50, 205, 50]),
    (("muscle", "kas"), [139, 0, 139]),
    (("vessel", "damar", "vaskuler"), [0, 0, 255]),
    (("normal", "benign"), [0, 128, 0]),
    (("nekroz", "necros"), [105, 105, 105]),
]
MEASUREMENT_COLOR = [255, 165, 0]  # olcum (mm / mm2) icin turuncu
DEFAULT_COLOR = [128, 128, 128]


def _stable_color(text):
    """Etikete bagli, calistirmalar arasi degismeyen okunur bir renk."""
    h = hashlib.md5((text or "sectra").encode("utf-8")).digest()
    return [120 + (h[0] % 116), 120 + (h[1] % 116), 120 + (h[2] % 116)]


def _fmt(value):
    return ("%g" % value)


def circle_to_polygon(center_x, center_y, radius, num_points=36):
    """Bir cemberi (merkez + yaricap) kapali bir poligona cevirir."""
    points = []
    for i in range(num_points):
        angle = 2.0 * math.pi * i / num_points
        points.append([center_x + radius * math.cos(angle),
                       center_y + radius * math.sin(angle)])
    points.append(points[0])  # poligonu kapat
    return points


def _parse_floats(graphic_data):
    """GraphicData'yi (liste veya '\\' ayrali string) float listesine cevirir."""
    if graphic_data is None:
        return []
    if isinstance(graphic_data, (list, tuple)):
        out = []
        for value in graphic_data:
            try:
                out.append(float(value))
            except (TypeError, ValueError):
                pass
        return out
    out = []
    for part in str(graphic_data).split("\\"):
        part = part.strip()
        if not part:
            continue
        try:
            out.append(float(part))
        except ValueError:
            pass
    return out


def _pairs(flat):
    """Duz [x0,y0,x1,y1,...] dizisini [[x0,y0],[x1,y1],...] ciftlerine boler."""
    return [[flat[i], flat[i + 1]] for i in range(0, len(flat) - 1, 2)]


def extract_text(seq):
    """TextObjectSequence.UnformattedTextValue -> etiket metni."""
    try:
        tos = getattr(seq, "TextObjectSequence", None)
        if tos and len(tos) > 0:
            value = getattr(tos[0], "UnformattedTextValue", "")
            return str(value).strip() if value else ""
    except Exception:
        pass
    return ""


def extract_graphic_objects(seq):
    """Bir GraphicAnnotationSequence ogesinden grafik nesnelerini cikarir."""
    label = extract_text(seq)
    objects = []

    for graphic in getattr(seq, "GraphicObjectSequence", None) or []:
        try:
            gtype = str(getattr(graphic, "GraphicType", "UNKNOWN")).upper()
            coords = _pairs(_parse_floats(getattr(graphic, "GraphicData", None)))
            obj = {"type": gtype, "coordinates": coords, "label": label}
            if gtype == "CIRCLE" and len(coords) >= 2:
                (cx, cy), (rx, ry) = coords[0], coords[1]
                radius = math.hypot(rx - cx, ry - cy)
                obj["center"] = [cx, cy]
                obj["radius"] = radius
                obj["polygon"] = circle_to_polygon(cx, cy, radius)
            objects.append(obj)
        except Exception:
            traceback.print_exc()

    for compound in getattr(seq, "CompoundGraphicSequence", None) or []:
        try:
            ctype = str(getattr(compound, "CompoundGraphicType", "UNKNOWN")).upper()
            coords = _pairs(_parse_floats(getattr(compound, "GraphicData", None)))
            objects.append({"type": ctype, "coordinates": coords, "label": label})
        except Exception:
            traceback.print_exc()

    return objects


def scale(coords, width, height):
    """Normalize [0-1] koordinatlari piksel uzayina olcekler."""
    if width is None or height is None:
        return [[x, y] for x, y in coords]
    return [[x * width, y * height] for x, y in coords]


def _measurement(label):
    """Etiketten olcum (deger, birim) cikarir; yoksa None."""
    if not label or "mm" not in label.lower():
        return None
    matches = re.findall(r"(\d+[.,]?\d*)\s*(mm2|mm)", label.lower())
    if not matches:
        return None
    value, unit = matches[0]
    try:
        return float(value.replace(",", ".")), ("mm2" if unit == "mm2" else "mm")
    except ValueError:
        return None


def classify(obj, scheme):
    """(sinif_adi, [r,g,b]) dondurur. Sinif adlari kullanici verisinden tureyebilir."""
    gtype = obj["type"]
    label = (obj.get("label") or "").strip()
    if scheme == SCHEME_LABEL:
        if label:
            low = label.lower()
            for keys, color in LABEL_KEYWORD_COLORS:
                if any(k in low for k in keys):
                    return label, color
            if "mm" in low:
                return label, MEASUREMENT_COLOR
            return label, _stable_color(label)
        return "Sectra anotasyon", DEFAULT_COLOR
    # geometry (varsayilan) - klinik acidan notr
    return "Sectra: %s" % gtype, GEOMETRY_COLORS.get(gtype, DEFAULT_COLOR)


def _feature(geometry, properties):
    return {"type": "Feature", "geometry": geometry, "properties": properties}


def obj_to_feature(obj, width, height, scheme):
    """Bir grafik nesnesini QuPath sihirbazinin okudugu GeoJSON ozelligine cevirir."""
    gtype = obj["type"]
    label = (obj.get("label") or "").strip()
    class_name, color = classify(obj, scheme)
    name = label if label else ("Sectra %s" % gtype)

    properties = {
        "objectType": "annotation",
        "type": gtype,
        "label": label,
        "name": name,
        "classification": class_name,
        "color": color,
        "isLocked": True,
    }
    measurement = _measurement(label)
    if measurement:
        properties["measurement_value"] = measurement[0]
        properties["measurement_unit"] = measurement[1]
        if "[" not in properties["name"]:
            properties["name"] = "%s [%s %s]" % (name, _fmt(measurement[0]), measurement[1])

    try:
        if gtype == "CIRCLE" and obj.get("polygon"):
            # Yaricap PIKSEL uzayinda hesaplanir. Normalize [0-1] uzayda hesaplanip sonra
            # x*genislik, y*yukseklik ile olceklenseydi kare olmayan lamda daire elipse donerdi.
            if len(obj["coordinates"]) >= 2:
                (pcx, pcy), (prx, pry) = scale(obj["coordinates"][:2], width, height)
                ring = circle_to_polygon(pcx, pcy, math.hypot(prx - pcx, pry - pcy))
            else:
                ring = scale(obj["polygon"], width, height)
            return _feature({"type": "Polygon", "coordinates": [ring]}, properties)
        if gtype in ("POLYLINE", "ARROW") and len(obj["coordinates"]) >= 2:
            return _feature({"type": "LineString",
                             "coordinates": scale(obj["coordinates"], width, height)}, properties)
        if gtype == "POLYGON" and len(obj["coordinates"]) >= 3:
            ring = scale(obj["coordinates"], width, height)
            if ring[0] != ring[-1]:
                ring = ring + [ring[0]]
            return _feature({"type": "Polygon", "coordinates": [ring]}, properties)
        if gtype == "POINT" and len(obj["coordinates"]) >= 1:
            point = scale(obj["coordinates"][:1], width, height)[0]
            return _feature({"type": "Point", "coordinates": point}, properties)
    except Exception:
        traceback.print_exc()
    return None


def convert(dicom_path, out_path, width, height, scheme):
    import pydicom  # gec import: selftest pydicom yokken de mesaj verebilsin

    ds = pydicom.dcmread(dicom_path)

    if width is None or height is None:
        try:
            das = getattr(ds, "DisplayedAreaSelectionSequence", None)
            if das and len(das) > 0:
                corner = getattr(das[0], "DisplayedAreaBottomRightHandCorner", None)
                if corner and len(corner) >= 2:
                    width = float(corner[0])
                    height = float(corner[1])
                    print("Boyut DICOM'dan: %sx%s" % (width, height))
        except Exception:
            pass
    if width is None or height is None:
        print("UYARI: goruntu boyutu yok - koordinatlar normalize [0-1] kalir, "
              "QuPath'te yanlis konumlanir. Sihirbaz boyutlari QuPath'ten gecirir.")

    features = []
    skipped = 0
    gas = getattr(ds, "GraphicAnnotationSequence", None)
    if not gas:
        print("UYARI: GraphicAnnotationSequence bulunamadi - bu bir Sectra GSPS dosyasi mi?")
    else:
        for seq in gas:
            for obj in extract_graphic_objects(seq):
                feature = obj_to_feature(obj, width, height, scheme)
                if feature is not None:
                    features.append(feature)
                else:
                    skipped += 1

    collection = {
        "type": "FeatureCollection",
        "features": features,
        "sectra_metadata": {
            "source_file": os.path.basename(dicom_path),
            "image_width": width,
            "image_height": height,
            "color_scheme": scheme,
            "creation_date": str(ds.get("PresentationCreationDate", "")),
            "content_description": str(ds.get("ContentDescription", "")),
        },
    }
    with open(out_path, "w", encoding="utf-8") as fp:
        json.dump(collection, fp, ensure_ascii=False, indent=2)
    print("CONVERT OK: %d ozellik yazildi, %d atlandi -> %s"
          % (len(features), skipped, out_path))
    return 0


def selftest():
    print("Python: %s" % sys.version.split()[0])
    try:
        import pydicom
        print("pydicom: %s  OK" % getattr(pydicom, "__version__", "?"))
    except Exception as exc:  # noqa: BLE001
        print("pydicom: EKSIK - %s" % exc)
        print("  Kurulum: pip install -r handson/python/sectra/requirements.txt")
        print("SELFTEST FAILED")
        return 1

    poly = circle_to_polygon(0.0, 0.0, 1.0)
    assert len(poly) == 37 and poly[0] == poly[-1]
    assert _pairs(_parse_floats("0.1\\0.2\\0.3\\0.4")) == [[0.1, 0.2], [0.3, 0.4]]
    name, color = classify({"type": "CIRCLE", "label": ""}, SCHEME_GEOMETRY)
    assert name == "Sectra: CIRCLE" and color == [255, 215, 0]
    print("cekirdek mantik: OK")
    print("SELFTEST OK")
    return 0


def main():
    parser = argparse.ArgumentParser(
        description="Sectra PACS DICOM (GSPS) -> QuPath GeoJSON koprusu")
    sub = parser.add_subparsers(dest="cmd")
    sub.add_parser("selftest", help="Bagimlilik + cekirdek mantik kontrolu")
    conv = sub.add_parser("convert", help="DICOM GSPS -> GeoJSON")
    conv.add_argument("--dicom", required=True, help="Girdi DICOM (.dcm) dosyasi")
    conv.add_argument("--out", required=True, help="Cikti GeoJSON dosyasi")
    conv.add_argument("--width", type=float, default=None, help="WSI taban genisligi (px)")
    conv.add_argument("--height", type=float, default=None, help="WSI taban yuksekligi (px)")
    conv.add_argument("--color-scheme", choices=[SCHEME_GEOMETRY, SCHEME_LABEL],
                      default=SCHEME_GEOMETRY, help="Renklendirme semasi")

    args = parser.parse_args()
    if args.cmd == "selftest":
        sys.exit(selftest())
    if args.cmd == "convert":
        try:
            sys.exit(convert(args.dicom, args.out, args.width, args.height, args.color_scheme))
        except SystemExit:
            raise
        except Exception as exc:  # noqa: BLE001
            print("HATA: %s" % exc)
            traceback.print_exc()
            sys.exit(1)
    parser.print_help()
    sys.exit(2)


if __name__ == "__main__":
    main()
