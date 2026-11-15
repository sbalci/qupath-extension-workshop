# Ask-first review — intentional non-UI script changes

Started 15 November 2026 on branch `fix/ask-first-and-rc9`.

Owner rule (15 November 2026): no wizard or helper may delete or replace an
existing object without asking. This covers detections, annotations, a
script's own summary or sentinel annotation, and transferred results.

These edits change script behaviour on purpose, so each one gets a new source
fingerprint in `script-core.json` (`baseline_reviews`, with the previous hash).
The `revision` recorded there is the commit the diff was reviewed against.
The preservation check itself is unchanged.

## The pattern

| Situation | Behaviour |
|---|---|
| No earlier result of this script on the slide | Runs as before, no question. |
| Earlier result exists, GUI | Asks before any heavy work. **Add** (default) deletes nothing; the new result gets the next name, `… (2)`, `… (3)`. **Replace** needs a second confirmation that says what is removed. **Cancel** runs nothing and writes nothing. |
| Earlier result exists, no window (headless) | Stops and writes nothing; the message names the arguments `mevcut=ekle` and `mevcut=degistir`. |
| What counts as "this script's result" | The exact name or the name followed by ` (N)` with N ≥ 2. Never a prefix, so an annotation the user renamed is not touched. |
| Replace | Removes only this script's own objects and keeps the objects inside them. |
| Result window after Add | States that the earlier result is still on the slide, names it, and says how to remove it. |

## Scripts changed

| Script | Before | After | Automated check |
|---|---|---|---|
| `yardimci-grandqc-sihirbaz.groovy` | Every import (single slide, direct run, project-wide) removed all annotations named `GrandQC KK`, with their child objects. The project-wide path warned but offered only yes/no. | All three paths ask. Add names the new set `GrandQC KK (2)` …; the derived clean-tissue annotation carries its set's name. Replace keeps child objects. The direct run asks before the Python pipeline starts. Headless mode of this wizard only reports and never wrote. | `SafetyRegressionTest.testGrandQcOwnSetNamingNeverMatchesByPrefix`, `testGrandQcAsksBeforeRemoving` |

## Not covered by automated tests

The dialogs themselves, Cancel in the GUI, and the project-wide import on a
real project. These are on the owner's GUI pass list.

## Known follow-up

`yardimci-grandqc-kohort-ozet.groovy` reads only the exact name `GrandQC KK`.
Until the export tools ask about copies (plan item 4b), a slide with two sets
is summarised from the first set only.
