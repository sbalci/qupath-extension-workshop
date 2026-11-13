# Phase 1 Audit Report — `qupath-extension-workshop`

- **Repository**: `https://github.com/sbalci/qupath-extension-workshop.git` (submodule at `D:\qupath-workshop\qupath-extension-workshop`)
- **Audited commit / version**: `59f1eeb` (`version=0.2.2-rc8`, `qupathVersion=0.6.0`, JDK 21 toolchain)
- **Audit mode**: Read-only (zero tracked files modified)

---

## 1. Build, Lint & Test Verification

| Check | Command | Result | Notes |
|---|---|---|---|
| Clean Gradle build + unit tests | `.\gradlew.bat --no-daemon clean build` | **PASS (exit 0)** | Produces `build/libs/qupath-extension-workshop-0.2.2-rc8.jar`; runs 55 `GroovyTestCase` tests (`AnonymizationRegressionTest`: 39, `SafetyRegressionTest`: 10, `ParameterValidationTest`: 6). |
| Script mirror parity (`handson/scripts/` ↔ `src/main/resources/scripts/`) | `pwsh ../tools/check-script-sync.ps1` | **PASS (exit 0)** | All **103** packaged Groovy scripts are byte-identical to `handson/scripts/`. |
| Groovy static lint (14 rules + prefs defaults) | `pwsh ../tools/lint-groovy-scripts.ps1` | **PASS (exit 0)** | 103 scripts checked (`Locale.US`, imports, encoding, prefs defaults). |
| Version & `catalog.json` sync | `pwsh ../tools/check-version-sync.ps1` | **PASS (exit 0)** | `gradle.properties` (`0.2.2-rc8`) matches `catalog.json` top release entry. |
| Release candidate acceptance workflow | `.github/workflows/candidate-acceptance.yml:79-80` | **BROKEN ON HEAD** | Hardcodes `assert len(cases) == 49`, whereas `HEAD` has **55** test cases after `ParameterValidationTest.groovy` (+6) was added in `f9c915f`. |

---

## 2. Inventory of Packaged Code & Documentation

### 2.1 Java Classes (`src/main/java/io/github/sbalci/qupath/workshop/`, 8 files)
1. `WorkshopExtension.java` (910 lines) — QuPath `QuPathExtension` entry point; builds `Extensions → Atölye` menu (**101** active script items across 8 submenus; 2 commented out) plus optional top-level `Senkron Izgara` menu, environment check dialog, settings launcher, and background script runner.
2. `WorkshopSettingsDialog.java` (356 lines) — JavaFX settings dialog (`PathPrefs`-backed defaults for calibration, stains, detection parameters, top-level grid toggle).
3. `WorkshopPrefs.java` (231 lines) — Persistent `BooleanProperty` / `DoubleProperty` / `StringProperty` preferences under `io.github.sbalci.qupath.workshop`.
4. `WorkshopMicrocopy.java` (169 lines) — Turkish tooltips, status microcopy, and menu helper descriptions.
5. `WorkshopColorPalettes.java` (104 lines) — Okabe–Ito / Paul Tol colour-blind-safe `PathClass` palettes.
6. `WorkshopParameterValidation.java` (81 lines) — Numeric range validation helpers for pixel size, nuclei area, thresholds, and intensity cut-offs.
7. `WorkshopErrors.java` (41 lines) — Maps common Java exceptions (`OutOfMemoryError`, `NoClassDefFoundError`, `FileNotFoundException`, etc.) to short Turkish recovery hints.
8. `WorkshopResources.java` (68 lines) — Classpath resource loader for bundled scripts and classifiers.

### 2.2 Packaged Groovy Scripts (`src/main/resources/scripts/`, 103 files)
- **Core guided modules & tour (11)**: `arayuz-turu.groovy`, `sihirbaz.groovy`, `hucre-tespiti.groovy`, `nukleer-boya.groovy`, `er-pr-hscore.groovy`, `membran-boya.groovy`, `sitoplazmik-boya.groovy`, `tumor-ici-ki67.groovy`, `quantum-ctcf.groovy`, `model-egit.groovy`, `veri-aktarma.groovy`.
- **Tissue, Stain & QC helpers (10)**: `yardimci-doku-tespiti.groovy`, `yardimci-piksel-esikleyici.groovy`, `yardimci-grandqc-sihirbaz.groovy`, `yardimci-histoart-sihirbaz.groovy`, `yardimci-boya-vektor-sihirbaz.groovy`, `yardimci-boya-ayrimi-onizleme.groovy`, `yardimci-boya-kalite-qc.groovy`, `yardimci-kalibrasyon.groovy`, `yardimci-odak-bulaniklik.groovy`, `yardimci-renk-paleti.groovy`.
- **Segmentation & Foundation-Model Python/Deep-Learning wizards (14)**: `yardimci-stardist-sihirbaz.groovy`, `yardimci-instanseg-sihirbaz.groovy`, `yardimci-cellpose-sihirbaz.groovy`, `yardimci-classpose-sihirbaz.groovy`, `yardimci-hovernext-sihirbaz.groovy`, `yardimci-histoplus-sihirbaz.groovy`, `yardimci-cytoformer-sihirbaz.groovy`, `yardimci-nuclick-sihirbaz.groovy`, `yardimci-tiatoolbox-sihirbaz.groovy`, `yardimci-tiatoolbox-bolge-sihirbaz.groovy`, `yardimci-kaiko-sihirbaz.groovy`, `yardimci-spider-sihirbaz.groovy`, `yardimci-hepatosit-sihirbaz.groovy`, `yardimci-python-ortam-yoneticisi.groovy`.
- **Mitosis detection & comparison (7)**: `yardimci-mitoz-modelleri.groovy`, `yardimci-mitoz-tiatoolbox.groovy`, `yardimci-mitoz-fcos.groovy`, `yardimci-mitoz-retinanet.groovy`, `yardimci-mitoz-atipik-effnet.groovy`, `yardimci-mitoz-atipik-sanofi.groovy` (disabled in menu), `yardimci-mitoz-karsilastir.groovy`.
- **Registration (`Görüntü Hizalama`, 3)**: `yardimci-valis-sihirbaz.groovy`, `yardimci-warpy-sihirbaz.groovy`, `yardimci-hizalama-aktarim.groovy`.
- **Scoring, Spatial, Anonymization, Export, TMA & Project utilities (58)**: remaining `yardimci-*.groovy` scripts (`yardimci-anonim-sihirbaz.groovy`, `yardimci-senkron-izgara.groovy`, `yardimci-karo-ihrac.groovy`, `yardimci-omezarr-disa-aktarma.groovy`, `yardimci-dicom-svs-sihirbaz.groovy`, `yardimci-sectra-iceaktar.groovy`, `yardimci-hotspot-sirali.groovy`, `yardimci-dogrulama-f1.groovy`, `yardimci-tma-*.groovy`, etc.).

### 2.3 Documentation & Metadata Files
- `README.md`, `catalog.json`, `CITATION.cff`, `.zenodo.json`
- `docs/candidate-integration-acceptance.md`, `docs/rc6-published-artifact-audit.md`, `docs/rc6-safety-validation.md`, `docs/rc7-published-artifact-audit.md`, `docs/releases/v0.2.2-rc7.md` (no user-facing docs, no `v0.2.2-rc8.md`, no `CHANGELOG.md`)
- `src/main/resources/images/tour/README.md` (0 images present)

---

## 3. Findings Ranked by Teaching & Learner Impact

### P0 — Critical Blockers (Break Standalone Use, Install Parity, Data Safety, or CI)

#### 1. Fifteen Python-bridge wizards depend on external `.py` files (`handson/python/**`) absent from the extension repo and JAR
- **Impact on Audience A (code-naive pathologists) & standalone Audience B users**: Anyone installing the extension via the QuPath Extension Catalog or GitHub Releases (without also cloning the parent `qupath-workshop` repository and opening its `handson/` project) hits `"Köprü betiği bulunamadı"` and a file chooser asking them to locate a `.py` script that was never shipped to them.
- **Evidence**:
  - Unlike `yardimci-valis-sihirbaz.groovy:1558` and `yardimci-dicom-svs-sihirbaz.groovy` (which embed their Python drivers inside the Groovy resource), the following **15** wizards look for external files on disk:
    - Auto-detected only relative to `handson/` project, otherwise missing: `yardimci-classpose-sihirbaz.groovy:63,157-166,988`, `yardimci-cytoformer-sihirbaz.groovy:82,218-224,1023`, `yardimci-histoplus-sihirbaz.groovy:80,169-178,1057`, `yardimci-hovernext-sihirbaz.groovy:72,157-164,797`, `yardimci-mitoz-fcos.groovy:192-199,813`, `yardimci-mitoz-retinanet.groovy:163-169,658`, `yardimci-mitoz-atipik-effnet.groovy:100-105`, `yardimci-mitoz-atipik-sanofi.groovy:99-104,488`, `yardimci-mitoz-tiatoolbox.groovy:196-203,834`, `yardimci-tiatoolbox-bolge-sihirbaz.groovy:158-165,656`, `yardimci-mitoz-karsilastir.groovy:125-157,222-225`.
    - Not even auto-detected (`prefs` defaults to `""`): `yardimci-histoart-sihirbaz.groovy:135,150,731,758` (`histoart_bridge.py`), `yardimci-nuclick-sihirbaz.groovy:138,148,816` (`nuclick_bridge.py`), `yardimci-kaiko-sihirbaz.groovy:91,108,740` (`kaiko_bridge.py`), `yardimci-spider-sihirbaz.groovy:114,131,666` (`spider_bridge.py`), `yardimci-tiatoolbox-sihirbaz.groovy:123,142,724` (`tiatoolbox_bridge.py` — UI text even says `"repoda hazır gelir"`!), `yardimci-sectra-iceaktar.groovy:101,385` (`sectra_dicom_to_qupath.py`), `yardimci-hepatosit-sihirbaz.groovy:101,488`.

#### 2. `README.md` manual download (`/releases/latest`) serves `v0.2.1` (~23 scripts) while `catalog.json` serves `v0.2.2-rc8` (103 scripts)
- **Impact**: Code-naive pathologists who drag-and-drop the JAR from `README.md`'s `/releases/latest` link (`README.md:7,94-99`) get `v0.2.1` (because `.github/workflows/release.yml:128` marks `-rc` tags `prerelease: true`), missing ~80 scripts and every renamed module slug documented in the workshop.

#### 3. Premature destructive deletion & wrong-slide race condition in multiple wizards
- **Impact**: Learners lose manual polygon edits or prior model runs when a re-run fails, or overwrite detections on another slide if they click around during a multi-minute Python inference.
- **Evidence**:
  1. **`yardimci-classpose-sihirbaz.groovy:705-721`**: Calls `hier.removeObjects(oldSummaries, true)` (`L711`) and `hier.removeObjects(old, false)` (`L716`) **before** checking `if (geojson != null)` (`L720`) or parsing GeoJSON (`L721`). If Python exits without producing valid GeoJSON, prior Classpose detections in the ROI are permanently deleted before the error surfaces.
  2. **`yardimci-hovernext-sihirbaz.groovy:398` & `yardimci-mitoz-{fcos:393, retinanet:330, tiatoolbox:399, karsilastir:559}`**: Delete all `SUMMARY_NAME` annotations across the **entire slide** (not restricted to `unionRoi`) with `keepChildren = false`, contradicting `hovernext:365`'s own comment that runs in other regions are preserved.
  3. **`yardimci-histoart-sihirbaz.groovy:433,678`**: Unconditionally deletes `'HistoART KK'` (`L433`) without confirmation, and on `L678` calls `importPredictions(workDir, QP.getCurrentImageData(), exp)` after Python finishes rather than using the `ImageData` captured at launch — importing onto (and deleting `'HistoART KK'` from) whichever slide happens to be active.
  4. **`yardimci-grandqc-sihirbaz.groovy:440`**: Batch mode (`L1289-1294`) asks for overwrite confirmation, whereas single-slide mode (`L440`) silently deletes existing `'GrandQC KK'` annotations (including any manual refinements).
  5. **Silent child-detection replacement without confirmation** in `membran-boya.groovy:224`, `quantum-ctcf.groovy:184`, `yardimci-instanseg-sihirbaz.groovy:184`, `yardimci-stardist-sihirbaz.groovy:318`, `yardimci-cellpose-sihirbaz.groovy:116`, `yardimci-tiatoolbox-sihirbaz.groovy:355`, `yardimci-tiatoolbox-bolge-sihirbaz.groovy:306`, `yardimci-valis-sihirbaz.groovy:1764,2748`.

#### 4. `.github/workflows/candidate-acceptance.yml:79-80` fails on `HEAD`
- **Impact**: Release candidate acceptance gate asserts `len(cases) == 49`, failing against the **55** actual JUnit cases on `HEAD`.

---

### P1 — High Teaching Impact (Audience Friction, Bilingual Parity, Docs & Onboarding)

#### 5. 100% Turkish-only UI across all 8 Java classes and 103 Groovy scripts
- **Impact**: English-speaking learners (and bilingual site visitors switching from `/en/` on sibling sites or reading the English `README.md` / Zenodo record) have no `TR`/`EN` language toggle in `WorkshopSettingsDialog`, no `ResourceBundle` message catalog, and no English menu/dialog strings.

#### 6. Severe installation & workshop Wi-Fi friction for code-naive pathologists
- **Conflicting extension names during install**:
  - `README.md:86` instructs users to select **`Atölye eklentisi`**, `catalog.json:5` actually lists **`QuPath Workshop extension (Atölye)`**, `WorkshopExtension.java:841` registers **`Atölye Betikleri`** in Installed Extensions, and the menu bar shows **`Atölye`** (`L372`) + **`Senkron Izgara`** (`L160`).
  - Zero annotated screenshots accompany the Extension Manager URL-paste steps (`Manage extensions → Manage catalogs → Add → Paste raw catalog.json URL`), where code-naive users routinely paste the GitHub HTML blob URL instead of the `raw.githubusercontent.com` URL.
- **Disk & bandwidth explosion in `yardimci-python-ortam-yoneticisi.groovy:82-163`**:
  - Creates up to **17 separate `.venv` environments**, each pulling 3–6 GB of PyTorch/CUDA wheels (25–40+ GB total under `C:\Users\<user>\.atolye\runtimes` by default), with no shared wheel cache import or pre-seeded offline USB archive workflow for poor workshop Wi-Fi.
  - Line `58-62` downloads unpinned `uv/releases/latest` without SHA-256 verification and hardcodes `uv-x86_64-pc-windows-msvc.zip` on Windows (`L59`), breaking Windows ARM64 laptops.
- **Gated-model & multi-step setup friction**:
  - `WorkshopExtension.java:134`: `Atipik sınıflandırma — Sanofi EFTD (kapılı)` is permanently disabled (`enabled = false`); because disabled JavaFX `MenuItem`s do not display tooltips, users cannot tell why it cannot be clicked or how to unlock it.
  - `Classpose` (`yardimci-classpose-sihirbaz.groovy:989-1039`), `CytoFormer` (`yardimci-cytoformer-sihirbaz.groovy:1029-1081`), and `HistoPLUS` (`yardimci-histoplus-sihirbaz.groovy:1071-1127`) require multi-stage environment builds, academic license checkboxes, HuggingFace account approval + Read token creation (with tokens not persisted across retries in HistoPLUS), and separate GrandQC weight setup.
  - `GrandQC` (`yardimci-grandqc-sihirbaz.groovy:161-178,879`) regex-patches downloaded `.py` files in place before every run, failing on read-only shared lab directories.

#### 7. Missing worked example, sample slide/project, tour screenshots, and `CHANGELOG.md`
- **No worked example or sample data**: Neither the repo nor `README.md` provides a tiny licensed/synthetic sample image (even though `tools/integration/synthetic_slide.py` already generates a synthetic pyramidal OME-TIFF in CI!) with a 5-minute end-to-end click-path (`Kalibrasyon → Doku tespiti → Hücre tespiti → Ki-67 skorlama → CSV dışa aktarma`) and expected numbers.
- **No `CHANGELOG.md`**: Release history is scattered across internal RC audit notes (`docs/rc6-*.md`, `docs/rc7-*.md`, `docs/releases/v0.2.2-rc7.md`, with `v0.2.2-rc8` missing).
- **Empty tour image folder**: `src/main/resources/images/tour/` has zero `.png`/`.gif` assets (`arayuz-turu.groovy:508-521` falls back to text-only), and `src/main/resources/images/tour/README.md:3,40` still points to deleted `modul-01-arayuz-turu.groovy`.
- **`README.md` / metadata drift**:
  - `README.md:68` says `Java 17+` (`build.gradle.kts:33` compiles Java **21**, classfile `65.0`).
  - `README.md:107-111` compatibility table lists `0.3.x` and `0.1.0`, omitting `0.2.x`.
  - `README.md:26-59` menu tree lists old script counts and labels (`⚠ Önce bir slayt açın`, 4 tissue scripts, 7 old mitosis scripts, old registration names, 12 scoring scripts) that no longer match `WorkshopExtension.java`.
  - `CITATION.cff:12-15`, `.zenodo.json:3`, and `catalog.json:6` still describe ~11 scripts from `v0.1.0`.

---

### P2 — Medium Impact (Error Guidance, UI Bugs, Terminology, Test Coverage & Compatibility)

#### 8. Raw exception dialogs, dead validation badge in `WorkshopSettingsDialog`, and swallowed errors
- **`WorkshopSettingsDialog.java:283-287` bug**: Line `283` sets `badge.setText("geçersiz sayı — eski değer korundu")`, which is unconditionally overwritten four lines later (`L287`) by `badge.setText("Kaydedilmedi — eski değer korundu")`.
- **Unused `WorkshopErrors.guidance()`**: `WorkshopErrors.java` is called only in `WorkshopExtension.java:636` and never from any of the 103 Groovy scripts. Roughly **45** `Dialogs.showErrorMessage` sites (including core module dialogs `hucre-tespiti.groovy:261`, `nukleer-boya.groovy:578`, `er-pr-hscore.groovy:523`, `membran-boya.groovy:728`, `sitoplazmik-boya.groovy:464`, `tumor-ici-ki67.groovy:862`, `quantum-ctcf.groovy:674`, `yardimci-python-ortam-yoneticisi.groovy:668`, `yardimci-warpy-sihirbaz.groovy:257,392`) print raw Java exception class names (`IllegalArgumentException: ...`) without telling the pathologist what to click next.
- **Silent failures via console-only `println` / 695 empty `catch` blocks**:
  - `yardimci-senkron-izgara.groovy:171,174,180`: Logs slide read failures to `println` while still showing `"X lam senkron ızgarada açılıyor"`.
  - `sihirbaz.groovy:84` & `quantum-ctcf.groovy:56`: `launchBundled` catches `Throwable` and only `println`s to the hidden log window when child helper buttons fail.

#### 9. Internal terminology, capitalization, and decimal-separator inconsistencies
- **`lam` (642) vs `slayt` (22)**: UI strings mix both within the same file (e.g. `yardimci-senkron-izgara.groovy:52` `"Projede en az 2 slayt olmalı"` vs `L47,88,131` `"lam"`; also `sihirbaz.groovy:627`, `yardimci-sectra-iceaktar.groovy:709`, `yardimci-boya-kalite-qc.groovy:192`).
- **`isteğe bağlı` (23) vs `ops.` / `opsiyonel` (28)**: `yardimci-grandqc-sihirbaz.groovy:1233`, `yardimci-histoart-sihirbaz.groovy:760`, `membran-boya.groovy`, `model-egit.groovy`, `sihirbaz.groovy` use `(ops.)` against TDK house style.
- **`yoğun odak` (0) vs `hotspot` (27)**: `WorkshopExtension.java:251` and `yardimci-hotspot-sirali.groovy:55,117-118` use untranslated `"hotspot"` / `"Hotspot"`.
- **`bölütleme` (7) vs `segmentasyon` (27)**: `WorkshopExtension.java:218,227,754` uses `"segmentasyonu"`, while `WorkshopMicrocopy.java:107,122,123` uses `"bölütleme"` for the same items.
- **Mixed menu capitalization (`WorkshopExtension.java`)**: `Doku tespiti` and `Mitoz tespiti` (sentence case) sit alongside `Görüntü Hizalama`, `Senkron Izgara`, and `Atölye Ayarları…` (Title Case).
- **Contradictory decimal separator hint (`WorkshopSettingsDialog.java:286,290`)**: Line `286` instructs `"Örnek biçim: 0.25"` while line `290` says `"örneğin 0,25"`.

#### 10. Test coverage gaps & silent exit-0 bug in parent standalone test script
- **Zero Java unit tests** (`src/test/java/` absent): `WorkshopExtension`, `WorkshopSettingsDialog`, and `WorkshopPrefs` have 0 unit tests.
- **96 of 103 Groovy scripts lack behavioral unit tests** (only 4 scripts tested in `src/test/groovy/` + 3 CLI smoke scripts in `tools/integration/host-checks.groovy`).
- **Verbatim-copied closures & exit-0 bug**: Parent repo `../tools/test-{cytoformer-overwrite,histoplus-naming,nuclick-naming}.groovy` paste copies of production closures instead of invoking shared helpers, and `../tools/test-histoplus-naming.groovy:118-119` omits `System.exit(failed == 0 ? 0 : 1)`, returning exit code `0` even when assertions fail.

#### 11. QuPath 0.6.0 / 0.7.x & Cross-Platform Compatibility Risks
- **Fragile JavaFX CSS / `fx:id` lookups**: `arayuz-turu.groovy:204,210,309,346-368` (`#opacitySlider`, `#measurementTablesMenuButton`, `.tab-label`), `quantum-ctcf.groovy:470`, `tumor-ici-ki67.groovy:654`, `yardimci-boya-vektor-sihirbaz.groovy:368` (`.menu-button`), and `-qp-script-error-color` in `hucre-tespiti.groovy:225` / `membran-boya.groovy:636`.
- **Legacy / internal QuPath APIs**: `removePathObjects` (`quantum-ctcf.groovy:184`, `yardimci-instanseg-sihirbaz.groovy:184`, `yardimci-stardist-sihirbaz.groovy:318,357`); `PathClass.getInstance` (`membran-boya.groovy:261-265`); internal `qupath.process.gui.commands.ml.PixelClassifierTraining` (`sihirbaz.groovy:28,192`, `model-egit.groovy:459`); `OMEZarrWriter.Builder` (`yardimci-omezarr-disa-aktarma.groovy:365-374`).
- **Unpublished external extension API**: `yardimci-hizalama-aktarim.groovy:63-78` reflects `qupath.ext.align.core.AutoAligner`, which is not in released `qupath-extension-align 0.5.0`, leaving Mode A permanently unavailable to standard users.
- **OS assumptions**: Windows-specific `"Python (python.exe):"` labels (`grandqc:1231`, `histoart:757`, `nuclick:147`, `valis`) and Windows example paths (`J:\zhihuanglab\CytoFormer` in `cytoformer:1066`); `grandqc:854` and `histoart:780` call `proc.destroyForcibly()` without killing `proc.descendants()`, risking orphan Python GPU workers on Windows after cancel.

