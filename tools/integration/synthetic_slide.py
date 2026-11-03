"""Deterministic, nonclinical, decodable classic TIFF with an Aperio-like header.

No external dependencies. Main image is RGB, tiled; thumbnail/label/macro are
stripped RGB pages. Deflate avoids the host libtiff uncompressed tile path. Owned ranges are recorded independently of the wizard parser.
This is a tiny format fixture, not a scanner-equivalence or real-slide claim.
"""
import struct
import zlib
from pathlib import Path

TOKEN = b'SYNTHETIC_PATIENT_ONLY'
ORPHAN = b'SYNTHETIC_LABEL_ONLY'


def make_slide(path):
    buf = bytearray(b'II*\x00\x08\x00\x00\x00')
    pages = []
    for i, (w, h, tiled, desc) in enumerate([
        (64, 48, True, b'Aperio Image Library v12.0.0\n64x48 [0,0 64x48] (16x16) RGB|Patient=' + TOKEN + b'|MPP=0.25|AppMag=40'),
        (32, 24, False, b'Aperio Image Library v12.0.0\n32x24 -> 64x48 - |MPP=0.25'),
        (16, 16, False, b'Aperio Image Library v12.0.0\nlabel 16x16|Comment=' + ORPHAN),
        (16, 16, False, b'Aperio Image Library v12.0.0\nmacro 16x16|Comment=' + ORPHAN),
    ]):
        if len(buf) % 2:
            buf.append(0)
        ifd = len(buf)
        n = 12 if tiled else 11
        buf.extend(b'\x00' * (2 + 12 * n + 4))
        owned = [(ifd, 2 + 12 * n + 4)]

        def alloc(data):
            if len(buf) % 2:
                buf.append(0)
            pos = len(buf)
            buf.extend(data)
            owned.append((pos, len(data)))
            return pos

        desc += b'\x00'
        desc_at = alloc(desc)
        bits = alloc(struct.pack('<HHH', 8, 8, 8))
        # Bright, spatially varying, nonclinical RGB pixels. No burned-in text.
        def rgb(x, y):
            return bytes((180 + (x * 3) % 64, 180 + (y * 5) % 64, 210 + (x + y) % 32))
        chunks = []
        if tiled:
            for ty in range(0, h, 16):
                for tx in range(0, w, 16):
                    chunks.append(b''.join(rgb(x, y) for y in range(ty, ty + 16) for x in range(tx, tx + 16)))
        else:
            chunks = [b''.join(rgb(x, y) for y in range(h) for x in range(w))]
        encoded = [zlib.compress(c, level=6) for c in chunks]
        pixels = [(alloc(c), len(c)) for c in encoded]
        offs = [p for p, _ in pixels]
        counts = [s for _, s in pixels]
        op = alloc(struct.pack('<' + 'I' * len(offs), *offs)) if len(offs) > 1 else offs[0]
        cp = alloc(struct.pack('<' + 'I' * len(counts), *counts)) if len(counts) > 1 else counts[0]
        entries = [(256, 4, 1, w), (257, 4, 1, h), (258, 3, 3, bits),
                   (259, 3, 1, 8), (262, 3, 1, 2), (270, 2, len(desc), desc_at),
                   (277, 3, 1, 3), (284, 3, 1, 1)]
        if tiled:
            entries += [(322, 4, 1, 16), (323, 4, 1, 16), (324, 4, len(offs), op), (325, 4, len(counts), cp)]
        else:
            entries += [(273, 4, 1, op), (278, 4, 1, h), (279, 4, 1, cp)]
        assert len(entries) == n
        struct.pack_into('<H', buf, ifd, n)
        for j, entry in enumerate(sorted(entries)):
            struct.pack_into('<HHII', buf, ifd + 2 + j * 12, *entry)
        if pages:
            struct.pack_into('<I', buf, pages[-1]['next_at'], ifd)
        pages.append(dict(ifd=ifd, next_at=ifd + 2 + n * 12, owned=owned, pixels=pixels, width=w, height=h))
    Path(path).write_bytes(buf)
    return dict(pages=pages, bytes=len(buf))


def ifd_chain(data):
    """Independent strict classic little-endian TIFF reader for this fixture only."""
    assert data[:4] == b'II*\x00'
    p = struct.unpack_from('<I', data, 4)[0]
    seen, pages = set(), []
    while p:
        assert p not in seen and p + 2 <= len(data)
        seen.add(p)
        n = struct.unpack_from('<H', data, p)[0]
        assert p + 2 + n * 12 + 4 <= len(data)
        fields = dict((tag, (typ, cnt, val)) for tag, typ, cnt, val in
                      (struct.unpack_from('<HHII', data, p + 2 + j * 12) for j in range(n)))
        pages.append(fields)
        p = struct.unpack_from('<I', data, p + 2 + n * 12)[0]
    return pages


def check_output(source, output, layout):
    before, after = Path(source).read_bytes(), Path(output).read_bytes()
    assert len(before) == len(after) == layout['bytes']
    assert len(ifd_chain(before)) == 4 and len(ifd_chain(after)) == 2
    assert TOKEN not in after and ORPHAN not in after
    assert b'MPP=0.25|AppMag=40' in after
    for page in layout['pages'][:2]:
        for pos, size in page['pixels']:
            assert after[pos:pos + size] == before[pos:pos + size], 'Retained pixels changed'
    for page in layout['pages'][2:]:
        for pos, size in page['owned']:
            assert after[pos:pos + size] == b'\x00' * size, 'Removed page bytes survived'
