"""Build the core scripts' TR/EN display strings without changing their data keys."""
import argparse
import json
import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUNDLE = ROOT / 'src/main/resources/i18n'
START = '# BEGIN GENERATED CORE SCRIPT UI'
END = '# END GENERATED CORE SCRIPT UI'


def escape(value):
    value = value.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t')
    if value.startswith(' '):
        value = '\\ ' + value[1:]
    return value


def build(check):
    catalog = json.loads((BUNDLE / 'script-core.json').read_text(encoding='utf-8'))
    runpy.run_path(str(ROOT / 'tools/check-script-ui-contract.py'))['check']()
    rows = catalog['strings']
    if len({row['key'] for row in rows}) != len(rows):
        raise SystemExit('Duplicate core UI key')
    writes = []
    for language in ('tr', 'en'):
        if any(not row.get(language) for row in rows):
            raise SystemExit('Missing translation: ' + language)
        target = BUNDLE / ('strings_' + language + '.properties')
        original = target.read_text(encoding='utf-8')
        generated = START + '\n' + '\n'.join(row['key'] + '=' + escape(row[language]) for row in rows) + '\n' + END
        if START in original:
            start, end = original.index(START), original.index(END) + len(END)
            source = original[:start] + generated + original[end:]
        else:
            source = original.rstrip() + '\n\n' + generated + '\n'
        if source != original:
            if check:
                raise SystemExit('Stale core UI bundle: ' + str(target))
            writes.append((target, source))
    for target, source in writes:
        target.write_bytes(source.encode('utf-8'))
    print('Core script UI: ' + str(len(rows)) + ' paired strings; standalone Turkish fallbacks retained.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    build(parser.parse_args().check)
