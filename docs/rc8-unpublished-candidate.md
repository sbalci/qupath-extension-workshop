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
rc6/rc7 comparison is not duplicated. PR #5 and PR #6 are now merged on main.
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

## November 3 reconciliation

PR #7 incorporates main at `19d106e76a181cfcdd0076337c4d189560d5e4ad`,
including the read-only Python/MCP inspection helper introduced in `f489e0e9`.
The only production differences from the original rc8 are that script and its
menu registration (no image/project required). The anonymization wizard is unchanged.
The helper checks installed classes, local configuration/preferences, loopback TCP
ports and image metadata; it does not host MCP or send protocol requests. Its
third-party configuration assumptions and GUI behavior still require acceptance.

The original candidate at `222449d8` remains a separate historical artifact:
run `41270697133`, artifact `13068674430`, JAR SHA-256
`3a40cabe18b7108a47c301680df784ee00816de22233d5cac3484f7999042be1`.
Its evidence must never be relabeled as evidence for this rebuild. Retain the
original pinned acceptance profile and append a new profile for the new artifact.
Both builds are unpublished rc8; identify them by full digest/run/artifact,
never by filename alone. Build artifacts expire; do not rely on CI as permanent
release storage.

The current release workflow builds again and therefore cannot promise to ship
an acceptance-tested JAR. Before any authorized release, either promote the exact
accepted bytes and verify their digest or validate the newly built bytes afresh.
No release workflow, tag, rc7 replacement or workshop distribution change is
part of this reconciliation.

### Reconciled build identity

- PR source: `6a376ae58b46f1518569fa894f42b953c7d1ec6b`.
- Actions checkout: `9826cdf53c8c1dd5d066cd79e0baf7155b8a2cba`.
- [Build run 41582122603](https://github.com/sbalci/qupath-extension-workshop/actions/runs/41582122603), attempt 1.
- [Candidate artifact 13206675137](https://github.com/sbalci/qupath-extension-workshop/actions/runs/41582122603/artifacts/13206675137).
- JAR SHA-256: `0870de63395cdb044916a6c54e81ee1b64a936df6c79e5db4a0311997731cfdd`.
- Artifact ZIP SHA-256: `2e380a2a7ccd9c8fe148db530698ecac8db13e8087540ebef12148d42cf2afb8`.
- Source and packaged safety contracts: 49/49 each; metadata/resources verified.
- QuPath synthetic integration: pending in the pinned `rc8-reconciled` profile.

The acceptance workflow downloads this artifact, verifies run/source/ZIP/JAR
identities, and never rebuilds its input. Subsequent PR Build runs create other
artifacts; they are not substituted for this pinned candidate.
