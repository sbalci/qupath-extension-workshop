# Script window language — review scope

The extension's selected language is read when each display label is built.
Close and reopen a script window after switching language. With no extension
available, the same script retains its original Turkish text.

The shared authoring source is `src/main/resources/i18n/script-core.json`.
`python tools/build-script-strings.py` generates the two property bundles;
`--check` verifies them without writes. No runtime dependency was added.

This review adds four helpers to the eight existing core scripts:

| Helper | Display lookup sites added | Included text |
|---|---:|---|
| `yardimci-kalibrasyon.groovy` | 60 | Method choice, ruler instructions, validation, project-scope question and window captions |
| `yardimci-boya-vektor-sihirbaz.groovy` | 38 | Estimate/apply/undo controls, status captions and fixed help/error text |
| `yardimci-metadata-sihirbaz.groovy` | 25 | Project scan, cancellation, CSV action, folder and clipboard controls |
| `yardimci-python-ortam-yoneticisi.groovy` | 46 | Install/repair/return controls, data-root guidance and fixed status text |

There are now 255 paired strings across 12 scripts, plus the separate cell
detection keys. This is fixed-text coverage. Interpolated result/log text,
environment-catalog descriptions, other helpers and model installation paths
are not claimed to be fully translated or GUI-tested.

## Source preservation check

The catalog records a source revision and SHA-256 for each of the 12 scripts.
`python tools/check-script-ui-contract.py` removes the exact reviewed language
helper, unwraps only known `workshopText` calls and compares the restored source
with that fingerprint. Apart from line endings and trailing whitespace at EOF,
the original source must be recovered byte for byte. This protects commands,
parameters, state identifiers, option identifiers, output columns and callbacks.
The eight older scripts also recover their source from before the translation
commit; the four new helpers recover their source at `6c648c9`.

An intentional non-UI edit needs its own review and a new source fingerprint;
do not update the hash simply to silence the check. The check runs before
bundle generation, including in CI. The Java tests additionally compare both
bundles with the catalog and exercise numeric/path format arguments.

This check does not prove clinical performance, translation layout, GUI
installation, cancellation, overwrite prompts or external model compatibility.
QuPath CLI checks use synthetic data and explicitly load the test JAR.
