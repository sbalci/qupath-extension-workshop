# 0.2.2-rc6 candidate: safety delta and validation

This is an unpublished candidate. Keep the participant kit and workshop installation pinned to rc5 until GUI acceptance and explicit replacement approval. Do not install both versions together. No catalog, release tag, release, participant ZIP or companion submodule change is part of this candidate.

## Source comparison

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

Local execution: only Java 17 is installed; Gradle distribution retrieval failed with `Network is unreachable`, before compilation/tests. No local JAR or test pass is claimed. CI evidence is to be recorded on the PR after execution.

## Remaining GUI acceptance — required before participant replacement

Use a disposable QuPath 0.6.0 project with a calibrated teaching slide, independent point annotations, manual-class detections, unrelated measurements and old model results. Back up the project; record OS, QuPath/Java version and the exact candidate SHA-256. Remove rc5 from that test installation before adding rc6; verify the menu, icon, About/version and bundled scripts load after restart.

For HistoPLUS, NuClick and CytoFormer, test default add/skip, explicit overwrite/replace, both dialog cancellations and cancellation during inference. Confirm object identities/counts, manual classes, measurements and point annotations before/after. Include renamed summaries, numbered layers, overlapping/disjoint ROIs and manually assigned model classes. Check late cancellation just before results are applied and repeat/reopen/save behavior. HistoPLUS missing/malformed output must preserve old objects; valid empty output must follow the explicit mode. CytoFormer partial failure should clearly report completed chunks and its processed-cell denominator.

No QuPath GUI or model inference environment is available here. Automated core/API checks establish a limited 0.6.0 compatibility baseline, not successful extension installation, Groovy GUI execution, model setup or clinical validation. Do not replace the pinned participant binary until this acceptance is documented and approved.
