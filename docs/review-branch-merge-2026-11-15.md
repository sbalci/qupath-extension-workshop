# Review branch consolidation — 15 November 2026

The user requested merging and pushing all work and removing merged/stale
branches. `main` includes the current bilingual work (`b73858c`) and the
`fix/accepted-artifact-release-promotion` tip `dea74a3`. That tip already
contains `candidate/rc8-unpublished` (`b83cd01`), so both branch histories are
preserved by the merge.

The citation conflict retains version `0.2.2-rc8` without inventing a release
date. The accepted-candidate manifest is historical: merging newer source does
not make that older JAR the newly built JAR. No tag or release is created or
promotion workflow dispatched by this consolidation. The promotion source gate
continues to reject production code that differs from its pinned accepted source.

The incoming binary validator compared the pinned PR #5 suite with every test
in current source. Its fixed harness has 49 contracts; current source has 67
tests, including later language and parameter checks. The validator now runs
the exact pinned source suite first, checks its required safeguard identities,
requires those identities to remain in the current source suite, and compares
the packaged JAR run against that exact pinned set. Additional current tests
are listed separately in the evidence. No count-only comparison replaces test
identities, and no skipped/failed/errored safety contract is accepted.

The runner also supports Windows and an optional offline Gradle invocation.
It runs `cleanTest` before each invocation, preserves separate current-source
results and refuses to overwrite prior evidence directories.

Validation before the merge commit:

- Java 21, offline Gradle clean build: 67 tests, no failures/errors/skips.
- Generated UI check: 102 source contracts and 2,096 TR/EN strings passed.
- Artifact inspection: all packaged resources match the current source bytes.
- Pinned `95bc3d4` source: 49 contracts passed; exact built JAR: the same 49
  identities passed, with no missing/unexpected tests or errors.
- Release-promotion regression suite: 20 tests passed without network mutation.

The tested JAR SHA-256 is
`b156b97fcceacb4cfb01c65c9fd62d9203402f964b89c4d1bbb0ca6e749cde8d`.
The build occurred while the merge was uncommitted, so the evidence reports
the pre-merge `HEAD`; resource equality was checked against the merged working
source. Tooling/documentation conflict resolution does not change those resources.
These tests establish no GUI, representative real-slide or clinical acceptance.
