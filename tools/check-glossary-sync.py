"""Check the glossary snapshot; optionally compare explicit sibling repository paths."""
import argparse
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--peer', action='append', type=Path, default=[], help='Sibling repo to compare (repeatable).')
    args = parser.parse_args()
    source = (ROOT / 'GLOSSARY.md').read_bytes()
    digest = hashlib.sha256(source).hexdigest()
    expected = digest + '  GLOSSARY.md\n'
    if (ROOT / 'glossary-source.sha256').read_text(encoding='utf-8') != expected:
        raise SystemExit('GLOSSARY.md differs from its reviewed snapshot; synchronise all four mirrors and SHA-256 records.')
    for peer in args.peer:
        if (peer / 'GLOSSARY.md').read_bytes() != source:
            raise SystemExit('Glossary mirror differs: ' + str(peer))
        if (peer / 'glossary-source.sha256').read_text(encoding='utf-8') != expected:
            raise SystemExit('Glossary snapshot record differs: ' + str(peer))
    print('Glossary snapshot and ' + str(len(args.peer)) + ' explicit peer(s) match: ' + digest[:12])


if __name__ == '__main__':
    main()
