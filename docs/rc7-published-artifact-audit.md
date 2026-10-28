# Published rc7 versus merged anonymization implementation

Audit date: 28 October 2026. This comparison does not publish a release or change historical assets. The earlier [rc6 audit](rc6-published-artifact-audit.md) remains historical evidence.

## Artifact identity and version difference

The independently downloaded [v0.2.2-rc7 JAR](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.2-rc7) is **3,444,988 bytes**, SHA-256 `7ed24440452eaa49c101b624d3522312d69828169f89ce0e681fbf1de5a13e70`. `tools/verify-candidate.py` confirms every packaged source resource matches release commit `644a0bba12350781015787a77974df6c79b67d02`; Java 21 metadata, version, menu scripts and external pinned ZXing checks also pass.

That release was published on **25 October**, before [PR #4](https://github.com/sbalci/qupath-extension-workshop/pull/4) merged on **27 October** at `e2806e06ab6e256ff2089fb9d29041449908df58`. Main still declares **0.2.2-rc7**. Thus a version string alone cannot distinguish the merged implementation from the older download. The published wizard SHA-256 is `f915b685b151b8d2a62868307af7b502b7190529628ca5e6c3261ca3c6c180cd`; the merged wizard is `b6f3d19559e00d90b7efc8c6142c6e0c1b3b06778e62b2a774b8a28bcd68b307`. This PR does not change production code or either version declaration.

## Executed comparison

At PR head `85b327d45eedd8fc091d2e8fa9b8e60bde478187` (CI merge checkout `611eeaf9834056be0e1f13148127b704a06291e1`), [Build](https://github.com/sbalci/qupath-extension-workshop/actions/runs/40644410578) and the [packaged-script audit](https://github.com/sbalci/qupath-extension-workshop/actions/runs/40644410658) succeeded on Java 21. The downloaded [evidence archive](https://github.com/sbalci/qupath-extension-workshop/actions/runs/40644410658/artifacts/12796389676) has SHA-256 `ba5024209c87f724d82103b7b9903a21e9c95b9b3b3fedad1517d1449f7fb139`.

| Script set | Executed | Passed | Failed | JUnit errors / skips |
| --- | ---: | ---: | ---: | --- |
| Published rc6 | 49 | 12 | 37 | 0 / 0 |
| Published rc7 | 49 | 22 | 27 | 0 / 0 |
| Historical PR #3 candidate | 49 | 21 | 28 | 0 / 0 |
| Current source, before and after comparisons | 49 each | 49 each | 0 | 0 / 0 |

The current complete suite has **49 tests (39 anonymization + 10 other safety tests)**, not 48: this is the observed JUnit identity set, with no tests added or removed by this PR. Do not compare the historical 22-test audit's failure counts directly with this expanded suite. The rc6 and candidate retain every previously required named failure.

rc7's 27 failed contracts comprise **9 behavioral assertion failures** and **18 unavailable-helper failures** (including missing-helper exceptions wrapped by `shouldFail`, and explicit missing-closure assertions). There are no infrastructure/XML errors or skipped test cases. The latter 18 do not demonstrate execution of absent functionality, and are not counted as 18 independently demonstrated unsafe behaviors.

| Safeguard | Published rc7 finding | Current source evidence |
| --- | --- | --- |
| Owned temporary files, output path/alias validation, cancellation guards | **Present in tested paths.** Legacy temporary files survive, invalid paths are rejected, and cancellation does not finalize output. | Same contracts pass. |
| Synthetic tissue/calibration preservation and read-only audit | **Present in tested paths.** Classic/BigTIFF, both byte orders, pass existing fixtures. | Same contracts pass. |
| Failed reader checks produce uncertainty; pinned decoder/cache integrity | **Present in tested paths.** Injected failed checks stay uncertain; dependency corruption is rejected; synthetic DataMatrix decodes. | Same contracts pass. |
| Complete label/macro page zeroing | **Absent.** The executed editor leaves the removed page's IFD table nonzero. The legacy linked-page removal/strip-zeroing fixture still passes, which does not cover orphan bytes. | Entire removed IFD tables, descriptions, arrays and strips zeroed across tested layouts; zero-range readback passes. |
| Reject overlap with retained page data | **Absent.** Overlapping strip/count fixtures do not throw the required IOException. | Fixtures refuse editing/copy publication. |
| Source integrity checks | **Absent.** Injected source mtime mutation still returns `TAMAM` and publishes the copy. `fingerprintOf`/`fpEqual` are absent. | Mutation yields the loud source-change error and removes output; fingerprint mutation assertions pass. |
| HMAC pseudonyms | **Absent.** Requesting `naming:'hmac'` falls through to order-dependent sequential names. `hmacHex` is absent, so HMAC primitive/collision contracts cannot execute in rc7. | Determinism, salt dependence, collision extension and reuse contracts pass. |
| Separate key storage and append-only crosswalk | **Absent by packaged-code inspection.** Legacy `writeKeyFiles` writes lookup/manifest files under `plan.outDir`. `prepareKeyState`, `assertKeyDirSafe`, `appendCrosswalk` and related helpers are absent. New folder/crosswalk integration behavior is **not testable** through these missing APIs. | Folder exclusion, salt reuse, crosswalk consistency and full package integration pass. |
| Input-order masking / duplicate original-name refusal | **Absent.** Sequential order follows input order; duplicate original names in reversible mode are accepted. | Both behavioral contracts pass. |
| Accession-pattern detection / review verdict | **Absent.** Residual `case 7-25` yields `TAMAM` instead of `İNCELE`. `compileAccession`/`accessionHits` are absent, so custom-pattern, timeout and oversized-value behavior is **not testable** in rc7. | Pattern, masking, review, timeout and scan-bound contracts pass. |
| GT450 metadata key coverage / non-ASCII residual detection | **Incomplete.** Expanded key coverage fails (Time Zone, Rack and Slide missing from redacted keys); the planted non-ASCII residual does not cause the required error. | Expanded-key and residual-byte contracts pass. |
| Edge-band review, timestamp pinning, checksum/README package | **Absent structurally.** The corresponding helpers are missing; those new helper/integration contracts are **not testable** in rc7. | Synthetic scoring, timestamps and package contracts pass. |
| QuPath GUI, real WSI and exhaustive identifier removal | **Not testable by this suite**, for either implementation. | Acceptance remains outstanding below. |

The nine behavioral failures are `testOrphanIfdTableDescriptionAndValuesAreZeroedForEveryLayout`, `testOverlapWithKeptPageRefusesBeforeAnythingIsZeroed`, `testFingerprintDetectsChangeAndOriginalMutationBecomesLoudError`, `testHmacNamingIsReproducibleAcrossRunsAndOrders`, `testSequentialNumberingDoesNotFollowInputOrderAndPlanIsInPseudonymOrder`, `testReversibleRunRefusesDuplicateOriginalNames`, `testFlaggedAccessionHitMakesUnitReviewNotClean`, `testGt450StyleDescriptionsOnEveryPageRedactAllKeysAndKeepMppAppMag`, and `testNonAsciiResidualValueIsFoundByTheByteScan`. Full failure messages and traces, including the 18 unavailable-helper results, are retained in `published-rc7-contracts.json` and JUnit XML.

## Reproduction and interpretation

The existing `Published packaged-script safety audit` workflow retains the historical rc6 and PR #3 artifact downloads, SHA-256 pins and required named failures, and adds the published rc7 JAR. It verifies rc7 resources against the exact release commit before testing. Run locally with Java 21 and the three downloaded JARs in the paths declared in `tools/audit-packaged-scripts.py`:

```sh
python3 -m unittest discover -s tools -p 'test_audit_packaged_scripts.py'
./gradlew --no-daemon clean build
python3 tools/verify-candidate.py
python3 tools/verify-candidate.py --jar audit-artifacts/published-rc7.jar \
  --source-ref 644a0bba12350781015787a77974df6c79b67d02 \
  --expect-sha256 7ed24440452eaa49c101b624d3522312d69828169f89ce0e681fbf1de5a13e70
python3 tools/audit-packaged-scripts.py
```

The passing current-source run defines the complete suite, with a floor of 49 tests. Each binary must execute exactly the same class/method identities, with no JUnit skips or XML errors. Current source must pass before and after the binary comparisons. A green audit means the pinned historical failures were reproduced and source passed; it does **not** mean each binary passed.

`WORKSHOP_SCRIPT_JAR` reads scripts directly from the selected JAR. Dependency bundles now load only helpers actually present in that script, without copying, implementing or replacing missing functions. Directly invoking a missing function still fails. This allows older copy/edit/cancellation behavior to execute without failing first on an unrelated new dependency. Two existing tests check orphan IFD bytes and source-mutation behavior before requiring new-only fields/helpers; their full current-source assertions remain intact.

Evidence includes exact JAR and wizard hashes, `summary.json`, per-artifact JUnit XML and Gradle logs, `*-contracts.json` with failure type/message/trace, and a structural inventory of missing current closures. A missing-helper exception is not a demonstrated behavioral failure; the inventory plus inspection can establish that the corresponding implementation is absent, while its behavior remains untestable in that binary. Passing synthetic contracts establish only the conditions they exercise.

## Remaining acceptance limits

Tests use synthetic TIFF/BigTIFF data in both byte orders, production closures and injected QuPath reader checks; they do not start the QuPath GUI or open real scanner slides. Whole-wizard parsing and the menu-entry assertion do not establish that the released Java menu works: the latter reads the checkout's Java source. Object-preservation tests use real QuPath core objects, but queued GUI cancellation remains a source-order check. Linux CI does not establish Windows/macOS case handling, NTFS creation-time behavior or all junction/link behavior.

QuPath 0.6.0 acceptance remains: install one extension/restart/confirm loaded version; open/selected/all project scopes; real Aperio/classic TIFF/BigTIFF and unsupported vendors; label/macro visual and metadata review; real Sectra labels and offline decoder handling; tissue, pyramid, dimensions and MPP preservation; all cancellation timings; project creation, object transfer, save and reopen. Use disposable copies and projects. Inspect burned-in identifiers throughout tissue and thumbnails: edge-band scoring is a heuristic, not OCR or exhaustive image review. The default accession pattern deliberately misses formats such as `S25-12345`; timeouts and oversized values require review. Source fingerprints sample size, mtime, head and tail rather than hashing every source byte. Timestamps and checksums do not establish de-identification. Completed outputs are not transactionally rolled back after cancellation. Neither these automated tests nor packaging verification establish complete anonymization or clinical/vendor acceptance.
