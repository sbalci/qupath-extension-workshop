"""Verify that UI lookup wrappers preserve the reviewed scripts byte for byte.

Only line endings and trailing whitespace at EOF are normalised. Removing the
approved helper and unwrapping workshopText must reproduce the recorded source
hash, including numerical settings, state keys, commands and output columns.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HELPER_SUFFIX = '''// Resolve extension UI language; standalone scripts keep their Turkish fallback.
private String workshopText(String key, String fallback) {
    try {
        def bundle = Class.forName('io.github.sbalci.qupath.workshop.WorkshopI18n')
        String translated = bundle.getMethod('t', String.class).invoke(null, key) as String
        return translated == key ? fallback : translated
    } catch (Throwable ignored) {
        return fallback
    }
}
'''
CALL = re.compile(r"\bworkshopText\('(script\.[0-9a-f]{12})', ")


def canonical(source):
    return source.replace('\r\n', '\n').rstrip() + '\n'


def quoted_end(source, start):
    quote = source[start]
    token = quote * 3 if source.startswith(quote * 3, start) else quote
    pos = start + len(token)
    while pos < len(source):
        if source[pos] == '\\':
            pos += 2
        elif source.startswith(token, pos):
            return pos + len(token)
        else:
            pos += 1
    raise ValueError('Unclosed Groovy string in UI fallback')


def restored_source(source, known_keys):
    source = canonical(source)
    if not source.endswith(HELPER_SUFFIX):
        raise ValueError('UI helper differs from its reviewed implementation')
    source = source[:-len(HELPER_SUFFIX)].rstrip() + '\n'
    edits = []
    for call in CALL.finditer(source):
        if call.group(1) not in known_keys:
            raise ValueError('Unknown UI key: ' + call.group(1))
        pos = call.end()
        depth = 1
        while pos < len(source):
            char = source[pos]
            if char in "'\"":
                pos = quoted_end(source, pos)
                continue
            if char == '(':
                depth += 1
            elif char == ')':
                depth -= 1
                if not depth:
                    edits.append((call.start(), pos + 1, source[call.end():pos]))
                    break
            pos += 1
        else:
            raise ValueError('Unclosed UI lookup')
    if not edits:
        raise ValueError('Script has no UI lookup wrappers')
    for start, end, fallback in reversed(edits):
        source = source[:start] + fallback + source[end:]
    return canonical(source)


def source_digest(source):
    return hashlib.sha256(canonical(source).encode('utf-8')).hexdigest()


def check():
    catalog = json.loads((ROOT / 'src/main/resources/i18n/script-core.json').read_text(encoding='utf-8'))
    keys = {row['key'] for row in catalog['strings']}
    contracts = catalog['source_contracts']
    if set(contracts) != set(catalog['scripts']):
        raise ValueError('Every translated script needs a reviewed source contract')
    for filename, contract in contracts.items():
        source = (ROOT / 'src/main/resources/scripts' / filename).read_text(encoding='utf-8')
        restored = restored_source(source, keys)
        if source_digest(restored) != contract['sha256']:
            raise ValueError(filename + ': non-UI source changed; review the source contract before updating it')
    print('UI-only source contracts passed for ' + str(len(contracts)) + ' scripts.')


if __name__ == '__main__':
    argparse.ArgumentParser(description=__doc__).parse_args()
    check()
