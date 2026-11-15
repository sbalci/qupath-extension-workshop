# English coverage of the script windows

15 November 2026, branch `fix/ask-first-and-rc9`. Audit findings E1 and Y8: in English mode
many script windows still showed Turkish text.

About 4,170 user-visible strings in 101 scripts were made translatable (dialogs, window
labels, guidance, status and error text, result text). The catalog now holds 5,589 paired
strings. Text only: conditions, values, names and outputs are unchanged.

## How it was done and checked

- Each Turkish literal became a `workshopText(key, '<the same Turkish text>')` call; the
  Turkish fallback was compared mechanically with the original literal for every string.
- About 170 concatenations or GStrings with simple values became
  `String.format(java.util.Locale.US, <pattern>, values…)`. These are the changes that are
  not byte-for-byte text swaps; a pattern with `%d` assumes an integer value.
- Checks run: the repository's language tests (keys, fallbacks, placeholder order); a scan
  that every English format piece is a valid pattern with the same placeholders as the
  Turkish one (878 pieces, 0 invalid); all 103 scripts compile in QuPath 0.6.0 and 0.7.0.

Review status: prepared by automated agents. No window was opened in QuPath in either
language, and the English wording was not read by a person. The owner's GUI pass in English
mode is the review.

## Deliberately left Turkish

- Anything that is data: measurement, class and annotation names, file and column names,
  preference keys, values compared as text, text written to files that other tools read.
- Choice items whose text is also compared in code (for example in the tile exporter and
  the VALIS reference choice). Translating them needs a code change, not a text change.
- Log and console output (`println`) and headless-only messages.
- `yardimci-anonim-sihirbaz.groovy` as a whole: its status codes are compared as text and
  its reports are written to files. It needs its own careful pass.
- The embedded Python text in the VALIS wizard.

## Known rough edges

- Some labels show a Turkish value inside an English sentence (for example the direction
  value in the peritumoural band helper), because the value is stored and compared.
- English names of site appendices and of a few buttons were written by the translators
  where the catalog had none; they may differ from the site's English page titles.
- Spelling is mostly American ("tumor", "color"); the older catalog rows are mixed.
- Column-aligned result rows keep their alignment within English, but some sit one column
  off the Turkish layout.
