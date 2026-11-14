"""Build a small, deterministic first-run image using Python's standard library."""
import hashlib
import json
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / 'assets/examples'


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)


def main():
    width = height = 512
    pixels = bytearray([255] * (width * height * 3))
    centres = [(x, y) for y in range(40, 473, 48) for x in range(40, 473, 48)]
    for x, y in centres:
        for yy in range(y - 10, y + 11):
            for xx in range(x - 10, x + 11):
                if (xx - x) ** 2 + (yy - y) ** 2 <= 100:
                    offset = (yy * width + xx) * 3
                    pixels[offset:offset + 3] = bytes([95, 90, 145])
    scanlines = b''.join(b'\x00' + pixels[y * width * 3:(y + 1) * width * 3] for y in range(height))
    image = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
             + chunk(b'IDAT', zlib.compress(scanlines, 9)) + chunk(b'IEND', b''))
    DEST.mkdir(parents=True, exist_ok=True)
    (DEST / 'synthetic-nuclei.png').write_bytes(image)
    manifest = dict(image='synthetic-nuclei.png', width=width, height=height,
                    objects=len(centres), pixelSizeMicrons=0.5, licence='CC0-1.0',
                    description='Synthetic blue circles on white; no tissue or patient data.',
                    generator='tools/build-synthetic-example.py', sha256=hashlib.sha256(image).hexdigest())
    with (DEST / 'synthetic-nuclei.json').open('w', encoding='utf-8', newline='\n') as stream:
        json.dump(manifest, stream, indent=2)
        stream.write('\n')
    print('Synthetic example: 100 circles, 512 × 512 px, intended scale 0.5 µm/px.')


if __name__ == '__main__':
    main()
