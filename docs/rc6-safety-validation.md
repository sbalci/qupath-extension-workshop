> Historical candidate record. rc6 was subsequently published from a different commit. The active correction is unpublished rc7; see [published artifact audit](rc6-published-artifact-audit.md). Older rc5 installation/publishing statements below describe the historical candidate, not current workshop dependencies.

# 0.2.2-rc6 candidate: safety delta and validation

This is an unpublished candidate. Keep the participant kit and workshop installation pinned to rc5 until GUI acceptance and explicit replacement approval. Do not install both versions together. No catalog, release tag, release, participant ZIP or companion submodule change is part of this candidate.

## Original October 20 source comparison

Compared published `v0.2.2-rc5` (`d1df6f335ccf374826731c1d69026acaad11cd27`, August 30) with October 12 main `f81faf56db9a86aad81187b07dba4c59edbff3ce` (eight commits ahead). The published rc5 asset digest is `8717da4902e2d3c0b52726b8b4e8e20f56b772a53f176526944b99c7c0bc4624` (release metadata/user-provided verification; not a local binary rehash).

HistoPLUS, CytoFormer and NuClick are **absent from rc5**. The behavior below describes new source-only helpers, then the exact safeguards added to those helpers by the October 12 commit; it must not be described as rc5 already offering unsafe versions of these three commands.

| Helper / production closures | Before October 12 | October 12 behavior included in rc6 |
| --- | --- | --- |
| HistoPLUS `importCells`, `prevSummariesIn`, `prevDetectionsIn` | Automatically removed summaries by prefix and HistoPLUS-class detections within the selected union ROI | Default add removes nothing. Explicit replace removes only exact model summary names or numbered variants, preserving summary children, then removes HistoPLUS-class detections by centroid within the union ROI. Detection matching uses class suffix, **not provenance**: manually assigned HistoPLUS classes and overlapping runs can match. |
| HistoPLUS `nextSummaryName`, `writeCounts` | Reused summary name; rewrote selected annotation counts | Add produces a region-scoped numbered summary, writes new counts only there and preserves previous target measurements. Replace updates HistoPLUS-prefixed target measurements while retaining unrelated keys. Other-model summary names and renamed notes do not match the exact summary selector. |
| HistoPLUS `startRun` | Launched inference without a previous-results choice | Asks before creating the run folder/ROI and starting Python. Default add; null choice or rejected second replacement confirmation returns. Cancelled Python returns to READY without importing. |
| CytoFormer `isOwnClass`, `foreignClassed`, `keepableCells` | Classified all target cells, overwriting prior classes | Default keeps foreign classes, including derived classes, by excluding those cells before inference. Unclassified cells and classes ending ` (CytoFormer)` are eligible; this is a naming convention, not provenance. No detections are created/deleted. |
| CytoFormer `startRun`, headless branch | No overwrite choice | GUI skip is default; overwrite requires a second confirmation before work starts. Headless `mevcutsinif=atla` is default, `yaz` explicitly overwrites; invalid values return. Own CytoFormer classes can be refreshed without a prompt. If all targets are foreign, skip returns without writing. |
| CytoFormer `applyAssignments`, `writeAnnotationMeasurements` | Existing class/probability writes and annotation summaries | Eligible assigned cells get a class and finite probability; unrelated cell measurements survive. Annotation `CytoFormer: ` keys are replaced with counts for **processed cells**, not all cells; unrelated annotation keys remain. A real pipeline error can apply completed chunks; cancellation (-3) does not intentionally apply partial results. |
| NuClick `isNuclickSentinel`, `findSentinels`, `importResults` | Automatically replaced the exact old sentinel and its children | Default add keeps existing layers and numbers the new one. Replace requires a choice and second confirmation before inference and removes exact/numbered sentinel layers **with all descendants**, including manual changes placed under those layers. Renamed notes outside the exact pattern and independent point annotations survive. Matching is by name, not provenance. |

Other changes since rc5 include new Classpose, HoVer-NeXt, HistoArt, annotation import/export and mitosis helpers; expanded menu registration; changes to GrandQC, hepatocyte, TIAToolbox, VALIS and other scripts. These are inherited from main, not newly implemented or fully validated here. The three October 12 safeguards are not a universal non-destructive guarantee for all menu items: other helpers still contain automatic replacement paths.

## Additional candidate corrections

- Assign `0.2.2-rc6` in both Gradle and `WorkshopExtension.getVersion()`; rc5 identity remains untouched.
- Recheck cancellation immediately before queued CytoFormer success/partial-error writes and NuClick import; recheck HistoPLUS cancellation immediately before import. These guards address late cancellation before mutation, not rollback once import has begun.
- HistoPLUS validates that a result file and `features` array exist and constructs new detections before deleting previous work. Missing, malformed or structurally invalid results leave previous objects intact. A valid empty features array remains a legitimate zero-detection replacement.
- Replace the stale comment referring to an absent copied CytoFormer test with the production-closure test location.

## Automated checks and binary evidence

Existing Java 21 `Build` workflow runs `./gradlew --no-daemon clean build` against QuPath 0.6.0. Groovy/JUnit tests evaluate production closures directly using real QuPath 0.6.0 core ROI, hierarchy, classification and measurement objects. They cover foreign-class preservation, explicit overwrite and missing assignments, exact/numbered summary matching, spatial replacement scope, preservation of summary children and target measurements, malformed/missing results, valid empty results and NuClick matching. Dialog ordering and late cancellation have source-contract checks only; these do not execute a GUI.

`python3 tools/verify-candidate.py` inspects the genuine Gradle JAR: manifest and build-info version, extension class version constant and Java 21 bytecode, service registration, every source resource byte-for-byte, every Java menu script, PNG signature, decompressed classifier JSON and absence of bundled host/test dependencies. It produces `.jar.sha256` and `.jar.verification.json` beside the JAR. The existing artifact upload carries all three plus a separate test-report artifact. Build timestamps make repeat builds potentially byte-different; always use the digest from the exact artifact being evaluated.

Local execution: only Java 17 is installed; Gradle distribution retrieval failed with `Network is unreachable`, before compilation/tests. No local JAR or test pass is claimed. The current CI and independent artifact evidence is recorded below; the October 20 artifact predates the redesigned wizard.

## Remaining GUI acceptance — required before participant replacement

Use a disposable QuPath 0.6.0 project with a calibrated teaching slide, independent point annotations, manual-class detections, unrelated measurements and old model results. Back up the project; record OS, QuPath/Java version and the exact candidate SHA-256. Remove rc5 from that test installation before adding rc6; verify the menu, icon, About/version and bundled scripts load after restart.

For HistoPLUS, NuClick and CytoFormer, test default add/skip, explicit overwrite/replace, both dialog cancellations and cancellation during inference. Confirm object identities/counts, manual classes, measurements and point annotations before/after. Include renamed summaries, numbered layers, overlapping/disjoint ROIs and manually assigned model classes. Check late cancellation just before results are applied and repeat/reopen/save behavior. HistoPLUS missing/malformed output must preserve old objects; valid empty output must follow the explicit mode. CytoFormer partial failure should clearly report completed chunks and its processed-cell denominator.

No QuPath GUI or model inference environment is available here. Automated core/API checks establish a limited 0.6.0 compatibility baseline, not successful extension installation, Groovy GUI execution, model setup or clinical validation. Do not replace the pinned participant binary until this acceptance is documented and approved.

## October 22 wizard integration (reconciled October 23)

The original October 20 rc6 build packaged the older Python/tifffile wizard. It is historical evidence only and does not validate the redesigned wizard. Main `8c8910b608620516ce1e40cb391d53c99929970e` changes exactly two files relative to October 12: the wizard (1,429 additions / 436 deletions) and its Java menu entry. Those changes were merged into the existing candidate, preserving both version declarations at `0.2.2-rc6` and preserving the candidate's CytoFormer, NuClick and HistoPLUS files byte-for-byte relative to `a16e83acbb032b060ee3984fc4060fc0d2405079`.

| Area | October 20 candidate | Integrated candidate |
| --- | --- | --- |
| Audit | No dedicated read-only audit | Open, selected or all project entries; structural TIFF and associated-image checks; optional Sectra DataMatrix decoding |
| Processing | Python/tifffile description edits; copy fallback | Java classic TIFF/BigTIFF processing; copy, edit and verify before final naming; optional separate project |
| Decoder | None | External ZXing core 3.5.3, SHA-256 `8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82`; verified cache/download; not bundled |
| Menu | Required an open image | Available without an open image for project-wide audit/processing |

Compatibility: the Java entry compiles against QuPath 0.6.0. The wizard no longer invokes Python or tifffile. ZXing is optional at runtime; absent/invalid offline cache means no barcode decoding, and unreadable labels require visual review. An audit reads source slides/project data; optional decoder setup writes its runtime cache, and the explicit gallery-export action writes label/macro images. Thus “read-only” describes slide/project handling, not zero filesystem writes. Those exported images and reversible lookup keys can contain identifiers.

Additional integration fixes: failed QuPath/TIFF audit checks now yield uncertainty rather than a clean verdict; pre-cancelled audits do not initialize/download the decoder. Copy processing checks cancellation before copying and again after final QuPath verification before final naming. It owns a unique temporary file, cleans only that file on failure/cancellation, rejects source aliases and output paths outside the chosen folder, and preserves existing final files. Naming prefixes cannot contain path separators. Cancellation stops between stages; copying/TIFF editing/host checks are not immediately interruptible and completed earlier copies are not rolled back.

`AnonymizationRegressionTest` executes production closures with synthetic data: classic TIFF and BigTIFF in both byte orders; source bytes unchanged during inspection/copy; PHI redaction, label/macro unlinking and pixel blanking; tissue payload and MPP/AppMag preservation; cancellation before copying, after copying and after host verification; existing output/legacy temporary-file preservation; failed verification cleanup; invalid output paths; uncertain audits; pre-cancelled decoder setup; corrupt downloaded/cache dependency rejection; the actual resolved ZXing 3.5.3 JAR digest; synthetic DataMatrix decoding through the production decoder. Extra checks parse the complete wizard at Groovy's conversion phase (syntax, not host API resolution) and exercise duplicate-source grouping and output-name collision avoidance. Host QuPath server checks are injected in copy/audit tests; these are not real WSI-reader or GUI tests. The previous nine model-helper regression tests remain in place.

The packaged-resource verifier additionally checks the wizard's Java workflow/decoder pin, absence of the old Python workflow and absence of bundled ZXing, and records the packaged wizard's digest. All resource identity checks are against the actual Gradle-built JAR.

### Remaining QuPath GUI/real-slide acceptance

Use a disposable QuPath 0.6.0 installation/project and the exact candidate artifact checksum. Record OS, Java/QuPath version, slide formats and results. In addition to the model-helper checks above:

- Install/restart with rc5 removed from this test installation; check About/version and the wizard menu with no image open, then active/selected/all scopes and duplicate project entries.
- Audit representative Aperio SVS, BigTIFF and partial/unsupported formats; compare source-slide hashes and project files before/after. Check failed/unreadable slides cannot appear clean. Test Sectra ANON labels, non-ANON codes, unreadable label/macro images and sidecar mismatches. A synthetic DataMatrix test is not a Sectra scanner-validation claim.
- Test first-use online decoder download, verified offline cache, missing/corrupt offline cache, blocked network and explicit thumbnail export. Check UI uncertainty/warnings and export destination.
- Produce copies with metadata/label settings on/off, reversible/irreversible modes and existing destinations. Open copies through the real WSI reader; verify tissue at multiple pyramid levels, dimensions, MPP, associated-image removal and remaining identifiers visually. TIFF fixture checks cannot establish every vendor format's safety.
- Cancel via button/window-close during audit, copy/edit, byte scan and near final verification. Confirm source/project unchanged and no unfinished copy receives a final name; already completed copies and reports may remain.
- Test optional new-project creation, save/reopen and optional object transfer. Inspect copied object names, measurements/metadata and lookup keys for identifiers; object transfer does not establish anonymization of user annotations. Test cropped/transformed/multi-file inputs follow documented exclusions.

No GUI, real WSI reader, optional project/object-transfer or model-inference acceptance was performed here. Keep rc6 unpublished and the participant kit pinned to rc5 until GUI acceptance is documented and replacement explicitly approved.

### Verified integrated build and artifact

Tested source/test commit: `7fd5e8a964a4bd7cbf24498d5127f01ca68716bd` (the later documentation-only update does not change those sources). [Build run 39910049657](https://github.com/sbalci/qupath-extension-workshop/actions/runs/39910049657) passed with Temurin Java 21.0.12, Gradle 8.10.2 and QuPath 0.6.0: `./gradlew --no-daemon clean build` and the candidate JAR verifier both succeeded.

Downloaded [test reports](https://github.com/sbalci/qupath-extension-workshop/actions/runs/39910049657/artifacts/12482084235) contain JUnit XML for **19 tests: 10 anonymization + 9 existing safety tests; 0 failures, 0 errors, 0 skipped**. Whole-script parsing is syntax validation only; injected host checks do not exercise a real QuPath WSI reader.

Downloaded [JAR artifact](https://github.com/sbalci/qupath-extension-workshop/actions/runs/39910049657/artifacts/12482561902) contains the candidate JAR, checksum and verification JSON. Independent extraction and rehash confirmed:

- `qupath-extension-workshop-0.2.2-rc6.jar`: **3,444,824 bytes**, SHA-256 `aa618da3f757e4cdd193eceaf64c6e2e7d15eba21d734ae87a265245bf3d3c0e`.
- Packaged wizard SHA-256: `f915b685b151b8d2a62868307af7b502b7190529628ca5e6c3261ca3c6c180cd`.
- Re-running `tools/verify-candidate.py` locally on the actual downloaded JAR passed: manifest, build-info and extension class all report `0.2.2-rc6`; Java 21 bytecode; 101 menu scripts; all source resources byte-for-byte; extension service, icon and classifier valid; host/test/ZXing dependencies absent.
- Artifact ZIP SHA-256: `0be7722895fd14767dd60b38e47c2ab428492cf82966ccfe0bcba55bd8a62310` (not the JAR checksum). Artifacts currently expire January 21, 2027.

Local `git diff --check` and verifier Python syntax checks passed. Local Gradle could not download its distribution (`Network is unreachable`; only Java 17 installed); the executed Java 21 build/test evidence comes from GitHub CI. No rc6 release, tag, catalog modification or participant/workshop rc5 dependency change was made.
