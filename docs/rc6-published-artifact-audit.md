# Published rc6 audit and unpublished rc7 correction

Audit date: 24 October 2026. Do not replace the rc6 asset or repoint its tag. This PR prepares **0.2.2-rc7**, without a tag, release, catalog entry, or participant dependency update. Publishing and participant installation changes require explicit approval after the acceptance below.

## Exact binary evidence

Both genuine binaries were downloaded and independently hashed. Every one of the 105 source resources matches its corresponding commit byte-for-byte. Both pass the extended `tools/verify-candidate.py`: Java 21 class version, extension/manifest/build-info version, service registration, 101 menu scripts, icon/classifier, pinned external ZXing 3.5.3 and absence of bundled host/test/ZXing dependencies.

| Artifact | Source | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| [Published rc6](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.2-rc6) | `84407eefed3a2d457d279b79f8bf61de5f19c7b6` | 3,444,412 | `9509d3245c6e3df388066c1f213b6afdf6826a80296f30323fce822e42c6649d` |
| [Previously validated PR #3 artifact](https://github.com/sbalci/qupath-extension-workshop/actions/runs/39910049657/artifacts/12482561902) | `f7c4fb8cc196152ba13399e75a46d4781080c535` (documentation-only successor of tested source) | 3,444,824 | `aa618da3f757e4cdd193eceaf64c6e2e7d15eba21d734ae87a265245bf3d3c0e` |

Their ZIP entries differ only in `build-info.properties` and four scripts: `yardimci-anonim-sihirbaz.groovy`, `yardimci-cytoformer-sihirbaz.groovy`, `yardimci-histoplus-sihirbaz.groovy`, `yardimci-nuclick-sihirbaz.groovy`. Their compiled extension class is identical. Packaging integrity is not safety validation.

Published wizard SHA-256: `c44a16913fe021797acdd101e957b8dc7d6fa906a823ccee2a85b9c5510644c9`; candidate wizard: `f915b685b151b8d2a62868307af7b502b7190529628ca5e6c3261ca3c6c180cd`.

## Missing protections and correction

| Area | Published rc6 | Correction |
| --- | --- | --- |
| Temporary files | Deletes a predictable `.anonim-gecici` destination before copying, regardless of ownership | Create a unique owned temporary file; clean only that file |
| Output paths | No canonical parent/source-alias rejection; naming prefix accepts directory separators | Reject invalid prefixes, source aliases and destinations outside the selected directory |
| Copy cancellation | Checks after copying/editing, but not before work or after final host verification | Check before copying and immediately before final naming |
| Audit verdict | Failed host/TIFF checks become notes and can still yield `TEMİZ` | Failures yield uncertainty (`BELİRSİZ`); pre-cancelled audit skips decoder setup |
| HistoPLUS replace | Removes previous summaries/detections before parsing/constructing replacement | Validate and construct every new object before removing previous work |
| Late model cancellation | No final guards in queued CytoFormer/NuClick writes or immediately before HistoPLUS import | Restore candidate guards; no transactional rollback claim |

An additional issue exists in the earlier candidate: nonempty invalid HistoPLUS features can be silently skipped and interpreted as an empty replacement. rc7 rejects missing/non-Polygon geometry, missing/blank classification, short/nonfinite/zero-area polygons before removal. An explicitly empty `features: []` remains valid when the user chose replacement; valid polygons outside the selected ROI remain excluded normally.

The source reconciliation merges released main into the existing PR lineage, keeps the new Java wizard, and preserves published catalog/citation metadata. The proposed version is rc7 in both Gradle and the extension. The release workflow now runs the same JAR verifier, checks tag/build-version agreement and publishes exact checksum/verification sidecars only when separately invoked. No release workflow is invoked by this work.

## Reproduction and regression evidence

`SafetyRegressionTest.source()` optionally reads **the actual JAR entries** via `WORKSHOP_SCRIPT_JAR`; production scripts are not reconstructed or patched for these checks. `AnonymizationRegressionTest` supplies synthetic TIFFs and injected host checks. Real QuPath core objects exercise HistoPLUS hierarchy changes. The legacy `tmpNameFor` closure is included so old packaged code executes rather than failing because a test binding is absent. Path traversal fixtures are contained within an owned temporary parent directory.

The `Published rc6 safety audit` workflow builds/tests/verifies corrected source, downloads both pinned binaries, checks their exact digests, runs the contracts against each packaged script set and retains JUnit XML, Gradle logs and `summary.json`. Known unsafe baseline failures are expected; the workflow requires named regressions and a final clean corrected-source test run. Its passing status must be read together with the baseline failure reports.

Commands:

```sh
./gradlew --no-daemon clean build
python3 tools/verify-candidate.py
# After downloading the two pinned JARs into audit-artifacts/ as in the workflow:
python3 tools/audit-packaged-scripts.py
# Independent source/resource identity check for released rc6:
python3 tools/verify-candidate.py --jar audit-artifacts/published-rc6.jar \
  --source-ref 84407eefed3a2d457d279b79f8bf61de5f19c7b6 \
  --expect-sha256 9509d3245c6e3df388066c1f213b6afdf6826a80296f30323fce822e42c6649d
```

The original candidate's 19-test October 23 result applies to that candidate only. See PR #3 for the new workflow runs and exact tested commits; do not transfer a result between different JAR digests. Local artifact verification and Python/whitespace checks passed. Local Gradle execution was blocked retrieving plugins through the execution environment's Java network/proxy; executed Java 21 regression evidence comes from GitHub Actions.

## Workshop implications and required acceptance

The workshop's [October 24 submodule update](https://github.com/sbalci/qupath-workshop/commit/2d3c498dfa68fe66a26200e92d9fe1b4f5d2d987) selects released source, not PR #3's validated artifact. A passing Quarto build does not validate these operations. Workshop documentation should identify the exact installed JAR/digest, describe rc6's missing safeguards, and avoid claiming complete anonymization or transferring PR #3's safety results to rc6. Prepare the future rc7 dependency/docs/ZIP/submodule changes only after separate approval; none are made here. The published catalog remains on rc6.

Pending that decision, use disposable slide copies and projects for testing. In particular, rc6's anonymization temporary-file handling and HistoPLUS replacement paths should not be used on irreplaceable data. There is no evidence here of exploitation, a remote attack, or clinical harm; the concerns are local data integrity and misleading anonymization confidence.

QuPath 0.6.0 GUI and real-slide acceptance remains outstanding: install/restart/menu/version; open/selected/all scopes; real classic TIFF/BigTIFF and unsupported vendors; real Sectra labels and offline decoder behavior; visual and metadata identifier review; tissue/pyramid/MPP preservation; all cancellation timings; project creation/object transfer/save/reopen. For HistoPLUS/CytoFormer/NuClick test add/skip and confirmed replace, malformed and valid-empty results, overlapping ROIs, manual annotations/classes/measurements and late cancellation. Cancellation is stage-based, completed copies are not rolled back, and replacement matching is name/class based rather than provenance. These tests do not establish complete anonymization, GUI safety or scanner compatibility.

## Executed artifact audit

At source/test commit `d899002d5496195a3804b68358106d28817a95f2`, [Build](https://github.com/sbalci/qupath-extension-workshop/actions/runs/40123581651) and [Published rc6 safety audit](https://github.com/sbalci/qupath-extension-workshop/actions/runs/40123581746) both passed on Java 21. The downloaded [audit evidence](https://github.com/sbalci/qupath-extension-workshop/actions/runs/40123581746/artifacts/12571559132) independently confirms:

| Tested scripts | Tests | Failing safety contracts | Errors |
| --- | ---: | ---: | ---: |
| Published rc6 | 22 | 10 | 0 |
| Previously validated PR #3 JAR | 22 | 1 | 0 |
| Corrected rc7 source | 22 | 0 | 0 |

Published rc6 execution removed the pre-existing legacy temporary file, returned `TAMAM` and finalized output after late cancellation, allowed an escaped output path, and reported `TEMİZ` despite injected host-check failure. HistoPLUS accepted missing/invalid output; inspecting its executed closure shows replacement occurs before parsing. The candidate's single additional failure concerns nonempty malformed features silently becoming an empty replacement. Queued model cancellation coverage remains a source-order contract, not executed GUI scheduling.

One original prefix-rejection test failed on the legacy source due to a missing `groupUnits` test binding after the absent early guard; this was a harness limitation, not independent evidence of exploitation. The follow-up supplies all production helpers, isolates the escaped-path output in an owned temporary parent, and explicitly checks old HistoPLUS objects before asserting an exception so data loss is directly demonstrated.

The exact [unpublished rc7 build artifact](https://github.com/sbalci/qupath-extension-workshop/actions/runs/40123581651/artifacts/12571708806) from that run is 3,444,988 bytes; SHA-256 `a038cfcc1d78b5bb6391aea29da57a6fdc9e59dc66ced7b3551e113a244d6fc1`. It was downloaded and rehashed independently. Later builds can differ because of embedded timestamps. Fixture-only follow-ups do not change production scripts, but their own workflow results must be checked before assigning the new test evidence to them.
