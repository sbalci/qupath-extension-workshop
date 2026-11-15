# Changelog

All notable changes to `qupath-extension-workshop` are documented in this file.
Versions follow `X.Y.Z` or `X.Y.Z-rcN` (the pre-release format supported by QuPath's extension manager).
Dates are tag dates. Entries before `0.2.2-rc9` were reconstructed from the git history on 15 November 2026.

## [Unreleased] — branch `fix/ask-first-and-rc9` (next test build: `0.2.2-rc9`)

The review record for the behaviour changes is `docs/ask-first-review-2026-11-15.md`.

### Changed — nothing is deleted or replaced without asking
- **Every wizard and helper asks before it removes or replaces an earlier result.** *Add* is the default and deletes nothing; the new result is named `… (2)`, `… (3)`. *Replace* needs a second confirmation that says what is removed. *Cancel* runs nothing and writes nothing. Without a window (headless), a script that finds an earlier result stops and names the arguments `mevcut=ekle` and `mevcut=degistir`.
- Covered: GrandQC import (single slide, direct run, project-wide), VALIS, Warpy, affine transfer, KongNet import, mitosis model comparison, both TIA Toolbox wizards, native tissue detection, tumour budding, HistoART, the Tümör/Stroma wizard and area script, tumour-internal Ki-67, and the measurement helpers that write a summary, grid, band or region (hotspot, nearest neighbour, cross-type nearest neighbour, class count, Delaunay, validation F1, immune density, stromal TIL, WSInfer summary, PHH3, structure distance, intersection area, peritumoural band, convex hull, centre/periphery, Ki-67 heterogeneity, density map, eyeball calibration).
- The peritumoural-band and centre/periphery helpers no longer remove hand-drawn annotations that merely share their output class.
- "This tool's own result" is matched by exact name or `name (N)`, never by a prefix, so an annotation you renamed is not swept.
- VALIS, Warpy and the affine transfer mark what they transfer; *Replace* removes the old transfer whole and keeps objects you added inside it afterwards.
- Tumour budding no longer deletes the existing detections under the selected region.
- Tools that run cell detection ask before replacing the cells already in the selected annotation, with "keep, do not run" preselected (cell detection, nuclear, ER/PR, cytoplasmic and membrane modules, QuANTUM, immune density, stromal TIL, StarDist, InstanSeg, Cellpose). Headless runs need `-a mevcut=degistir`.
- In the Tümör/Stroma tools, *Replace* re-measures in place and never deletes a region you drew.
- Measurement export and the two cohort summaries ask when a slide holds copies of the same result: all copies, or only the newest of each kind (headless: `kopyalar=hepsi` or `kopyalar=enyeni`). The GrandQC cohort summary now sees `GrandQC KK (N)` sets.
- Cell detection and the four stain modules no longer change the image type or stain vectors with only a log line: they say what will change and ask. Headless runs need `-a tip=he` (cell detection) or `-a boya=varsayilan` (stain modules).

### Changed — English mode
- About 4,170 more strings in 101 script windows follow the language setting (dialogs, labels, guidance, status, errors and result text); the catalog now holds 5,589 paired strings. Log output, data names, values that code compares as text, and the anonymisation wizard stay Turkish. See `docs/english-coverage-2026-11-15.md`.

### Changed — menu
- **Tümör/Stroma** is a submenu: the wizard, *Adım 1 — Model eğit* and *Adım 2 — Alan ölçümü*. The two step scripts were bundled before but had no menu entry.
- *Atipik sınıflandırma — Sanofi EFTD (kapılı)* is no longer permanently greyed out. When access to the gated model is missing the wizard shows the steps to obtain it; a Hugging Face login done in an ordinary command window is now picked up.

### Fixed
- Error guidance: a mistyped number says how to type it instead of reporting an unreadable image; internal faults no longer tell you to open a slide; `NaN` is matched as a word.
- The error dialog no longer calls the diagnostic summary anonymised. Home-folder paths and slide file names are masked; other names may remain.
- `tools/verify-candidate.py` runs on Windows.
- Messages that only stated a problem now say what to do next: a file that cannot be read, a helper step that failed inside a wizard, a region outside the image. Warnings that went only to the log (a fluorescence image in a brightfield module, a skipped detection step, a save that fell back to another location) are shown to the user.
- Menu-path hints name the topic group, so the path can be followed (92 hints in 58 scripts).

### Documentation
- README install steps follow the workshop site (drag-and-drop first) and use the button names of QuPath 0.6.0's extension manager. English paths name the `Atölye` menu.

## [0.2.2-rc8] — 2026-11-13
- Turkish/English interface: menu, settings, error dialogs and a first set of script windows; language switch without restart.
- Python bridges are packed in the JAR and extracted on first use.
- Settings window with validation, colour-blind-safe palette option, environment check.
- Two-audience tooltips (clinical / computational), eyeball-calibration wizard, guided tile exporter, read-only TIFF metadata scanner.
- Copyable error report with path masking.
- Note: the `main` branch moved on after this tag under the same version string; the published JAR is the tagged build.

## [0.2.2-rc7] — 2026-10-25
- Anonymisation wizard rewritten: read-only audit mode and a pure-Java TIFF engine, with regression tests.
- Packaged-script audit and JAR verification tooling.

## [0.2.2-rc6] — 2026-10-24
- Batch annotation export and import (GeoJSON, ImageScope XML, ASAP XML, COCO), asking before existing annotations are touched.
- Sectra import gains a project-wide mode.
- Atypical mitosis classifiers and the model-comparison wizard.

## [0.2.2-rc5] — 2026-08-30
- Python environment manager and wizards for the Python bridges.
- Image registration wizards (VALIS, Warpy, affine transfer).
- GrandQC wizard and cohort summary; WSInfer audit helper; DICOM → SVS conversion wizard; region extraction; threshold-adjustment rework.
- Mitosis detection wizards.

## [0.2.2-rc4] — 2026-06-27
- TIA Toolbox, Kaiko and SPIDER wizards; tumour budding; Sectra import; cohort metadata wizard; anonymisation wizard (first version); StarDist workflow helpers; image-processing tutorial.

## [0.2.2-rc3] — 2026-06-22
- Packaging fixes for the module scripts and the classifier helper.

## [0.2.2-rc2] — 2026-06-22
- Tümör/Stroma wizard: model choice, region or whole-slide scope, multi-model comparison; example classifier download.

## [0.2.2-rc1] — 2026-06-21
- Bundled example tumour/stroma classifier; tile export; feature-matrix helper; StarDist model download with checksum.

## [0.2.1] — 2026-06-16
- Calibration helper; KongNet mitosis import and PHH3 helper; update check and environment check; pre-release tags marked as such.

## [0.2.0] — 2026-06-05
- Persistent workshop settings read by the module scripts; pixel-wise H-score in the membrane module; module scripts warn before removing annotations; number formatting fixed for Turkish-locale systems.

## [0.1.0] — 2026-05-25
- Initial release: IHC modules, cell detection, tumour/stroma and export.
