# v0.2.2-rc8 unpublished candidate

This candidate distinguishes the anonymization implementation merged in PR #4
(`e2806e06ab6e256ff2089fb9d29041449908df58`) from the October 25 published rc7
JAR. It is for acceptance testing on disposable copies, not approved for
patient-data anonymization. No release or tag is created by this work.

The Gradle version and extension-reported version are rc8. Gradle derives the
JAR filename, manifest and build-info version from that version. Both citation
versions are updated, with release dates omitted because rc8 is unpublished.
Historical audit/release references and catalog download entries remain rc7;
they describe published artifacts. Workshop distribution links are unchanged.

## Provenance and automated evidence

The existing pull-request Build workflow runs `clean build` and
`tools/verify-candidate.py`, then uploads the rc8 JAR, SHA-256 and verification
JSON. Identify a candidate by the PR head, Actions run/attempt, checkout commit
(usually the PR merge commit), artifact ID and digest, not version alone.
Build timestamps mean a rebuild need not produce the same digest.

Binary validation reuses the audit harness and tests from
[PR #5](https://github.com/sbalci/qupath-extension-workshop/pull/5), pinned at
`95bc3d468793d30fe797b7dd4710ff6a42d2913f`, in a separate checkout. The historical
rc6/rc7 comparison is not duplicated. PR #5 remains independently reviewable.
`tools/validate-candidate-binary.py` compares exact class/method identities with
the source-build results, requires at least 49 tests, rejects skipped/missing
tests and failures, and confirms the JAR digest before and after execution.

The `safety-regression-reports` artifact contains source-run JUnit/HTML results.
The separate `candidate-binary-regression-evidence` artifact contains the
packaged-script Gradle log, JUnit XML, per-contract outcomes, wizard hash and
summary. `WORKSHOP_SCRIPT_JAR` makes the existing harness read production Groovy
scripts directly from the candidate JAR. This does not execute the complete
compiled Java extension inside QuPath: menu assertions read harness Java source,
and reader checks are injected. The packaging verifier checks every source
resource byte-for-byte, Java 21 class metadata and embedded version metadata.

## Acceptance required before any release decision

- Install only this JAR in QuPath 0.6.0, restart, confirm loaded rc8 version and
  build information, and exercise the actual menu and wizard.
- Test open-image, selected-image and all-project scopes on disposable projects;
  exercise cancellation before, during and after copying/verification, and
  inspect incomplete outputs and error/review states.
- Use representative Aperio SVS, classic TIFF and BigTIFF scanner slides, plus
  unsupported vendors to verify refusal; inspect labels, macros, thumbnails,
  metadata and burned-in tissue identifiers. Edge scoring is not exhaustive OCR.
- Verify tissue/pyramid readability, dimensions, MPP, label/macro removal and
  original-file integrity with real readers; test actual Sectra barcodes and
  offline/failed ZXing dependency paths.
- Check separate key storage, HMAC reproducibility/collisions, accession formats
  (including patterns the default does not detect), timestamps, checksums,
  project creation, object transfer, save and reopen on intended operating systems.

GUI and representative real-slide acceptance are **NOT RUN** by CI. Sampled
source fingerprints, synthetic fixtures and passing packaging checks do not
establish complete de-identification or clinical/vendor suitability. Completed
outputs are not transactionally rolled back after cancellation. Record the exact
JAR hash, platform, fixtures and outcomes for each acceptance session before
considering a release.
