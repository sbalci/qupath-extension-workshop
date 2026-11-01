# Existing candidate: synthetic QuPath acceptance

This checks the **already-built, unpublished** candidate from [Build 40507617137](https://github.com/sbalci/qupath-extension-workshop/actions/runs/40507617137), not a new build or published rc7. Internal version strings are identical, so identify it by source commit, artifact ID and SHA-256.

| Identity | Value |
| --- | --- |
| Source | `e2806e06ab6e256ff2089fb9d29041449908df58` |
| CI artifact ID | `12735752184` (`qupath-extension-workshop-jar`) |
| ZIP SHA-256 | `70a0b36a8c648f27a7b45436acd23b585351460c69589a4eded51359a17c7db0` |
| Candidate JAR SHA-256 | `0eefa0e1953617965ce004885fa7ae7f104b3cfb27bb0ef852abe5f1e8966eec` |
| Candidate JAR bytes | `3463330` |
| Packaged wizard SHA-256 | `b6f3d19559e00d90b7efc8c6142c6e0c1b3b06778e62b2a774b8a28bcd68b307` |
| Published rc7 JAR SHA-256 — different binary | `7ed24440452eaa49c101b624d3522312d69828169f89ce0e681fbf1de5a13e70` |

The local extracted filename is `candidate-e2806e0-artifact-12735752184.jar`. The archive and JAR are never rewritten, rebuilt or committed. Resource comparison against `git archive` of the pinned source plus CI run metadata establishes source correspondence; this is not a claim of a reproducible, byte-identical rebuild of Java classes.

## Reproduce

Use a disposable machine/container with QuPath **0.6.0**, not a normal QuPath profile or installation containing other copies of the Workshop extension. The automated workflow pins the official Linux distribution archive digest and runs headlessly. Only synthetic files are used. No label-decoding dependency download is needed for anonymization.

```bash
# Checkout must contain the pinned source commit (fetch history if necessary).
gh api repos/sbalci/qupath-extension-workshop/actions/artifacts/12735752184/zip > /tmp/candidate.zip
python3 -m unittest discover -s tools -p test_candidate_acceptance.py -v
python3 tools/accept-candidate.py --archive /tmp/candidate.zip \
  --work /tmp/candidate-acceptance-new \
  --qupath /path/to/disposable/QuPath/bin/QuPath
```

`--work` must not exist. The harness uses a private directory and isolates HOME, Java user home, preferences and caches. It applies per-command timeouts. Without `--qupath`, provenance and fixture generation execute, the report explicitly records host acceptance as blocked, and exit status is **2**, never success. Failed checks return **1**; complete synthetic host checks return **0**. Run without Python's `-O` option because checks use assertions, as does the existing verifier.

The original artifact expires January 25, 2027 according to GitHub metadata observed November 1. Retrieval failure/expiry blocks acceptance; the job must not silently rebuild or substitute a release. A newly selected artifact requires an explicit provenance/digest update and a new review.

## Coverage and evidence

`Existing candidate synthetic acceptance` retrieves that fixed artifact and:

1. Verifies the run/source association, archive/JAR digests, Java 21 class metadata, service descriptor, all source resources byte for byte, 101 menu-referenced scripts and dependency boundaries using `verify-candidate.py`.
2. Runs the existing safety suite with `WORKSHOP_SCRIPT_JAR` pointing to the downloaded candidate; rejects missing, skipped or failed tests. These closure contracts use synthetic fixtures and injected host results; they are not application acceptance by themselves.
3. Uses real QuPath CLI service discovery to instantiate the candidate class from its JAR and checks its code-source location. This is not GUI installation or a menu click.
4. Runs the **whole, unmodified packaged wizard** through its normal CLI entry with a decodable, synthetic four-page TIFF: tiled RGB tissue pattern, thumbnail, label and macro.
5. Runs the packaged declarations with real `ImageServers`, `qupathCheck`, `mainScanCheck`, `runApply` and file operations. Requires original label/macro recognition, output associated-image removal, pixel-for-pixel retained image equality, unchanged 0.25 µm/px calibration, HMAC/key-directory separation and unchanged source SHA-256. A controlled log callback mutates a disposable synthetic source after copying; the real fingerprint checks must return `HATA`, flag the source change and remove the output. No host-success stubs are installed.
6. Independently checks retained TIFF-chain length, erased label/macro IFD tables/out-of-line values/pixels, absence of synthetic identifier tokens, and preserved retained pixel ranges using Python rather than the wizard's parser.

The fixture generator uses only the Python standard library. It creates a minimal Aperio-like classic little-endian TIFF, not a scanner-generated SVS or a substitute for scanner compatibility testing. Existing contracts cover other TIFF layouts. The harness's Python tests cover substituted archives/JARs, traversal, directory reuse and rejection of unsanitized output. Artifacts from this workflow contain **reports only**, with no candidate JAR or workshop distribution.

## Evidence available before a host run

November 1 local checks retrieved and verified both original CI archives. Candidate ZIP digest and embedded JAR checksum matched; every packaged resource matched the merge source; verifier checks passed. Independent Pillow decoding confirmed all four generated pages are RGB with the intended dimensions. Seven Python harness tests passed.

The original `safety-regression-reports` artifact (`12735892673`, ZIP SHA-256 `66c4bbd0b15e444b4fec285910cc4ee995bf1bd2d001f56afd574bbf798410cc`) contains 39 anonymization and 10 other safety tests with no failures/errors/skips. Those original reports are source-test evidence, not a newly executed packaged-candidate or QuPath integration run.

Local packaged-contract and QuPath execution were unavailable: only Java 17 was installed, no QuPath or display server was present, and Gradle/QuPath dependency download hosts were unreachable. The workflow makes the host run reproducible elsewhere. Review its actual logs/status before describing application integration as passed. A blocked or failed run is not acceptance.

## Acceptance still separate

Even if automated headless checks pass, record **GUI acceptance: NOT RUN** until the pinned candidate is installed into a disposable QuPath 0.6.0 profile, restarted, opened through the Workshop menu and exercised on these synthetic fixtures. Verify preview/confirmation, separate key selection, audit/apply behavior, error display, cancellation and reopen/visual inspection. Record QuPath/OS version and the exact JAR SHA-256; do not identify it solely as rc7.

Record **real-slide acceptance: NOT RUN** until a separately authorized local assessment checks copies of representative scanner files (including AT2/GT450 and relevant TIFF layouts), reopen/readability, dimensions, calibration, tissue/pyramid integrity, associated-image removal, private key placement and source checksums. Keep those files and any identifying logs outside CI. Synthetic success does not establish real-slide compatibility or absence of identifiers in scanned tissue.

No release tag, release upload, historical artifact modification or workshop redistribution is part of this test.
