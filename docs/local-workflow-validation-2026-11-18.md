# Local workflow implementation and validation — 18 November 2026

This change implements a shared local result inspector, prepared-model checks,
declarative recipes and a persistent single-worker ROI queue. The implementation
plan is in the parent repository at `docs/histoforge-local-workflow-plan.md`.
Validation results and remaining acceptance limits are recorded below. This
document is not a release approval.

## Script source-contract review

Before changing each recorded source fingerprint, the original `HEAD` script
was restored through the unchanged localization checker and its SHA-256 was
verified against its existing contract. The four script diffs were then read.
New fingerprints identify the uncommitted implementation, with the original base
revision, previous/new digests and `working_tree: true` retained in each
`baseline_reviews` entry. The checker and its restoration rules were not weakened.

- HistoPLUS and Classpose import/count helpers now return the objects and summary
  they created. New workflow integrations record those exact objects, not all
  objects sharing a class. With the new helper available, import/count changes
  run on the host thread; the invoking project is captured before the worker.
- NuClick returns its generated detections/summary, records per-prompt outcomes,
  and performs record persistence on a worker after its existing UI-thread import.
- CytoFormer records references to the cells it relabelled and retains previous
  classes. It does not transfer ownership from the original detection tool.
  Complete successful GUI and headless runs are recorded; partial legacy runs
  keep their existing behavior and do not claim a complete workflow record.
- Existing standalone use without the new helper remains available. A saved
  project is required for the new durable record workflow. Historical untagged
  outputs are not adopted automatically.

Observed identities in legacy-wizard records are captured after inference;
automatic input reuse is disabled for those records. Queue runs verify inputs,
prepared files and the actual runtime before inference and recheck source/model
files before import. The inference guard blocks Python network operations and
invalidates a run even if model code catches the attempted connection; it is not
a security sandbox for arbitrary native/subprocess code.

## Validation results

An unrelated pre-existing contract mismatch was also found for
`yardimci-tiatoolbox-maske.groovy`. Its script is byte-identical to committed
`b44990ecbd4baf985adb59548dad68b0ea61e0d4`, whose seven-line diff changes mask
palette background from white to black and documents that behavior. The old
fingerprint exactly matches that commit's parent after restoration. The existing
fix was reviewed and its fingerprint/history reconciled; no TIA script bytes or
checker rules were changed in this work.

### Candidate identity

- Artifact: `build/libs/qupath-extension-workshop-0.2.2-rc11.jar`
- Size: 4,858,013 bytes; Java class-file baseline: 21 (major version 65).
- SHA-256: `ad14ad4012aaf4bdbc37fc01ecc02d09885bb4ea28ac90afa9c9058eaf165319`

This is an unpublished build of the working tree. The version remains the
existing `0.2.2-rc11`; the digest distinguishes these bytes from any previously
published artifact with that version. No tag, release, distribution upload or
installation into the user's QuPath profile was performed.

### Automated checks

| Check | Observed result |
|---|---|
| `gradlew.bat --offline --no-daemon --console=plain clean build` | Passed: 303 tests in 16 suites, zero failures/errors. |
| New `LocalWorkflowTest` suite | Passed: 21 tests using real QuPath objects and project save/reopen, plus deterministic model-process fixtures. |
| `python tools/test_local_workflow.py` in the extension | Passed: five tests covering prepared execution, blocked/caught network attempts, NuClick local weights and explicit CUDA failure without fallback. |
| Generated script strings/source contracts | Passed: 102 scripts and 5,814 paired strings. |
| Parent `tools/check-script-sync.ps1` | Passed: 103 Groovy pairs and 33 Python pairs. |
| Parent Groovy lint and preference-default checks | Passed. |
| Version synchronization | Passed for the existing `0.2.2-rc11` / QuPath 0.6.0 baseline. |
| `python tools/verify-candidate.py` in the extension | Passed: packaged resources, source identities and classifiers matched; checksum/evidence sidecars were produced. |
| Parent Turkish terminology, English menu paths and links | Passed; links were checked for all four profile/language targets. |
| Cloudflare configuration synchronization and whitespace checks | Passed. |

The new suite exercises current geometry/class/calibration changes, anisotropic
areas and holes, successful empty output versus missing/failed work, pooled
counts, export overlap rejection, portable evidence filtering, recipe validation,
readiness without execution, writer conflicts, exact ownership, manual inclusion,
classification references and previous classes. Queue tests include cancellation,
interrupted jobs and recovery after a result was committed before the queue
finalization. Replacement tests inject an import/save failure and concurrent
edits. The prepared-runner fixture exercises Add, Complete, Regenerate and late
cancellation without downloading or running a real model.

### Packaged artifact in Windows QuPath

The exact JAR above passed the host harness in **QuPath 0.6.0 and 0.7.0**. Each
run used a newly generated image and project. The harness recorded one owned
object beside an unrelated object of the same class, saved/reopened the project,
edited the owned geometry, refreshed its measurements, and exported an evidence
bundle. It verified the candidate's loaded SHA-256 and preservation of the
unrelated object.

Both hosts also passed the JavaFX smoke check: the extension menu entry opened
the results window, all three tabs were present, and switching between Turkish
and English updated the window. Six screenshots per host captured the tabs in
both languages; selected screenshots were visually inspected. These checks
establish menu/layout/language behavior, not a manual exercise of every control.
An isolated profile and the included in-memory preferences factory kept these
checks separate from the user's active QuPath configuration.

The reusable harness is `tools/test-local-workflow-host.groovy`.
`tools/run-local-workflow-host.groovy` loads candidate classes/resources ahead of
any installed Workshop extension when invoked by a host's script command.
GUI runs require the compiled
`tools/acceptance/WorkflowTestPreferencesFactory.java` on the JVM classpath and
the matching `java.util.prefs.PreferencesFactory` system property; the harness
asserts that isolation before creating a window. Always supply a new output
directory. QuPath 0.7.0 was exercised with its bundled JVM.

The candidate verifier's sidecar still reports its own `gui_acceptance` field as
`NOT RUN`: that verifier does not launch QuPath. The separate host/GUI results
reported here are identified by the same artifact digest.

### Documentation

Full post-profile renders succeeded for the Turkish root project and the
English project into ignored scratch directories under `.quarto/`. The five
affected chapters in each language passed headless-browser checks at 390 and
1280 pixels: 30 page/width checks, zero horizontal document overflows. The root
render also contained English chapter copies, accounting for the extra checks.

The tracked `_site/` and `_site-pre/` publication directories were not rebuilt.
Preparation-profile content was unchanged; both profiles/languages still need
the repository's normal publication renders before publishing the website.

### Remaining acceptance limits

No HistoPLUS/NuClick weights were downloaded and no real-model or GPU inference
was run for this change. Consequently, model accuracy, representative tissue
behavior, full-slide resources, correction-time savings and real prepared-model
offline compatibility remain unvalidated. The next acceptance exercise is a
small permitted H&E ROI and NuClick prompt set using explicitly prepared local
weights, with actual runtime/runner/model digests and CPU/GPU outcomes recorded.

Classpose and CytoFormer supply result records from their existing successful
wizard paths; they are not new queue adapters. Their full model executions and
partial legacy branches were not accepted by the host harness. Legacy records
cannot establish the stronger input identity needed for automatic reuse.
HistoForge model architectures were not ported. The Python network guard is a
reliability check for the approved runners, not isolation for arbitrary code.
