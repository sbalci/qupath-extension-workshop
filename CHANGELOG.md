# Changelog

All notable changes to `qupath-extension-workshop` are documented in this file.
Versions follow `X.Y.Z` or `X.Y.Z-rcN` (the pre-release format supported by QuPath's extension manager).

## [Unreleased] — `feat/bilingual-pedagogy-audit` (targeting `0.2.2-rc8+`)

### Added
- **Bilingual UI (`TR` default / `EN`):** Added `WorkshopI18n` with UTF-8 resource bundles (`strings_tr.properties`, `strings_en.properties`), live menu/dialog language toggle (`Extensions → Atölye → 🌐 Dil / Language`), and language selector inside `WorkshopSettingsDialog` (persisted as key `language` in Java Preferences node `/qupath/atolye/i18n`, without requiring a QuPath restart). Individual Groovy windows still need their own translation coverage; bilingual menus do not imply that every script is translated.
- **Self-extracting Python bridges:** Packaged all 15 external Python bridge folders (`/python/<tool>/*`, 31 files) inside the extension JAR and added SHA-256-verified extraction to `~/.atolye/bridges/<version>/` via `WorkshopResources.extractBundledPythonBridge(...)` so JAR-only installs run without a cloned workshop repository.
- **Guided 5-tab Tile Exporter & Read-Only TIFF Metadata Scanner:** Enhanced `yardimci-karo-disa-aktarma.groovy` with presets, grid preview, stain normalization hooks, and dataset splits; enhanced `yardimci-metadata-sihirbaz.groovy` with read-only TIFF header inspection.
- **Copyable, privacy-redacted error dialog:** Added `WorkshopErrors.redactSensitiveText` and `formatCopyableReport` (`<HOME>` and `<SLIDE_FILE>` masking) with one-click clipboard copy on script exceptions.
- **Canonical HistoPLUS cell-type mapping:** Added `CANONICAL_ALIASES` + `canonicalKeyOf` normalization in `yardimci-histoplus-sihirbaz.groovy` so `cancer cell` / `Epithelial` / `Endothelial` variants resolve deterministically across upstream checkpoints.
- **Expanded JUnit regression suite:** Added `SafetyRegressionTest` and `WorkshopI18nAndCatalogTest` covering non-destructive overwrite prompts, `killTree` cleanup, HistoPLUS alias resolution, diagnostic path redaction, TR/EN bundle key parity, classpath presence of all registered scripts, and `catalog.json` integrity.

### Changed & Fixed
- Localised the cell-detection window's controls, tooltips, validation and result summary through the existing TR/EN bundles. Standalone Groovy use retains Turkish fallback text. Window-opening failures now include the existing actionable error guidance; detection settings and public APIs are unchanged.
- **Non-destructive detection/annotation safety:** Added `Ekle / Değiştir` (`Add / Replace`) mode selection and overwrite confirmations across `yardimci-classpose-sihirbaz.groovy`, `yardimci-hovernext-sihirbaz.groovy`, `yardimci-mitoz-fcos.groovy`, `yardimci-mitoz-retinanet.groovy`, `yardimci-mitoz-tiatoolbox.groovy`, `yardimci-histoart-sihirbaz.groovy`, `membran-boya.groovy`, and `yardimci-cellpose-sihirbaz.groovy`, plus subprocess `killTree(proc)` cleanup on cancel/timeout.
- **Unified Turkish terminology (`GLOSSARY.md`):** Applied approved `OB-1..OB-14` terms (`yoğun odak (hotspot)`, `tümörü infiltre eden lenfositler (TİL)`, `bölütleme`, `öznitelik`, `sanal lam`, `immünohistokimya (İHK)`).
- **Settings badge & CI workflow fixes:** Fixed validation error badge overwrite in `WorkshopSettingsDialog` (accepting both `0,25` and `0.25`) and updated `.github/workflows/candidate-acceptance.yml` test-count assertion.

## [0.2.2-rc8] — 2026
- Bundled 103 workshop Groovy scripts across 13 module entries/submenus, 9 utility topic groups, and top-level `Senkron Izgara`, targeting QuPath `0.6.0+` (Java 21).

## [0.2.1] — 2026
- Added persistent `WorkshopPrefs` + `WorkshopSettingsDialog`, Okabe–Ito color-blind-safe palette toggle, and environment check dialog.

## [0.1.0] — 2026-05
- Initial release bundling core IHC, cell detection, tumor/stroma, and export workshop modules.
