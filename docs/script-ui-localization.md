# Script window language — review scope

The extension's selected language is read when each display label is built.
Close and reopen a script window after switching language. With no extension
available, the same script retains its original Turkish text.

The shared authoring source is `src/main/resources/i18n/script-core.json`.
`python tools/build-script-strings.py` generates the two property bundles;
`--check` verifies them without writes. No runtime dependency was added.

The fixed-text review on 14 November added four helpers to the eight core scripts:

| Helper | Display lookup sites added | Included text |
|---|---:|---|
| `yardimci-kalibrasyon.groovy` | 60 | Method choice, ruler instructions, validation, project-scope question and window captions |
| `yardimci-boya-vektor-sihirbaz.groovy` | 38 | Estimate/apply/undo controls, status captions and fixed help/error text |
| `yardimci-metadata-sihirbaz.groovy` | 25 | Project scan, cancellation, CSV action, folder and clipboard controls |
| `yardimci-python-ortam-yoneticisi.groovy` | 46 | Install/repair/return controls, data-root guidance and fixed status text |

The current catalog (15 November) has **565 paired strings across 15 scripts**,
plus the separate cell detection keys. The dynamic-text review added 188 display
expressions: 151 fixed expressions and 37 GString occurrences (36 unique
expressions). Checks, counts, classifier names, pixel size and execution scope
can now appear inside translated UI sentences. A subsequent review added 312
display lookups in the Classpose (105), CytoFormer (105) and GrandQC (102)
wizards, including their local guidance/status helpers and file chooser titles.
Their commands, stored model/class names, consent state and overwrite decisions
are unchanged. Quoted environment-entry names match the actual catalog labels;
the English menu path retains the fixed `Atölye` menu name.

Only literal text becomes a lookup. Original values retain their source code
and order, and the result remains a GString. Lazy closure values are coerced
when the consumer needs text; they are not flattened into a String early.
The compiler and real-host tests cover this distinction, described in the
[Groovy interpolation documentation](https://groovy-lang.org/syntax.html#_special_case_of_interpolating_closure_expressions).
The two scope labels and missing-classifier fallback are translated only in
conditional result arms; the conditions and classifier identifiers stay exact.

File-backed summaries, logs, environment-catalog descriptions and other helpers
remain outside this display-only change. Diagnostic/data values keep the text
produced by their source. Model installation and GUI layout are not certified.

## Source preservation check

The catalog records a source revision and SHA-256 for each of the 15 scripts.
`python tools/check-script-ui-contract.py` removes the exact reviewed language
helper, restores recorded UI expressions, unwraps only known `workshopText` calls
and compares the restored source with that fingerprint. Apart from line endings and trailing whitespace at EOF,
the original source must be recovered byte for byte. This protects commands,
parameters, state identifiers, option identifiers, output columns and callbacks.
The eight older scripts also recover their source from before the translation
commit; four helpers recover their source at `6c648c9`, and the three model
wizards recover their source at `a93a211`.

An intentional non-UI edit needs its own review and a new source fingerprint;
do not update the hash simply to silence the check. The check runs before
bundle generation, including in CI. A source hash alone is not a syntax or
semantic proof: the Gradle compiler tests also fold literal lookups back into
the original GString parts and compare the original value source/conditions.
They reject changed data code and mismatched keys/fallbacks. The real lookup
helper is exercised with lazy values, numbers, Unicode paths and scope choices.
The Java tests also compare both bundles with the catalog and check printf
arguments and precision.

This check does not prove clinical performance, translation layout, GUI
installation, cancellation, overwrite prompts or external model compatibility.
QuPath CLI checks use synthetic data and explicitly load the test JAR.

Two CytoFormer texts were excluded pending source reconciliation: the weights'
`CC BY-NC 4.0` heading and the automatic-approval statement. The current
[official model card](https://huggingface.co/zhihuanglab/CytoFormer) identifies
the Penn licence; the pinned revision's gated README could not be verified.
The new translations do not certify the older licence claim. See
[the model wizard record](model-wizard-ui-validation-2026-11-15.md).
