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
| `yardimci-valis-sihirbaz.groovy` | Every warp import (project-wide, quick warp, import from file) removed all annotations named `VALIS` on each target. | One question covers all targets, asked before the Python/warp run (quick warp) or before writing (other imports). Add names the new set `VALIS (2)`. Cancel also skips the OME-TIFF and merge additions. Headless never wrote; an unknown `mevcut` value fails closed. | `testValisOwnSetNamingNeverMatchesByPrefix`, `testValisAsksBeforeRemoving`, `testValisAskExistingDecisions`, `testValisHeadlessFailsClosed`, `testTransferReplaceRemovesTaggedTreeAndKeepsUserObjects` |
| `yardimci-warpy-sihirbaz.groovy` | Every transfer removed all annotations named `↪ Warpy aktarımı`. | Asks on the FX thread before the worker starts. Add names the new set `… (2)`. Headless still refuses to run. | `testWarpyOwnSetNamingNeverMatchesByPrefix`, `testWarpyAsksBeforeRemoving`, `testTransferReplaceRemovesTaggedTreeAndKeepsUserObjects` |
| `yardimci-hizalama-aktarim.groovy` | Manual and automatic transfers removed all annotations named `↪ Hizalama aktarımı`. | Both paths ask before the source image is opened and the aligner runs. Headless still refuses to run. | `testAffineTransferOwnSetNamingNeverMatchesByPrefix`, `testAffineTransferAsksBeforeRemoving`, `testTransferReplaceRemovesTaggedTreeAndKeepsUserObjects` |
| `yardimci-mitoz-kongnet.groovy` | Every import removed all KongNet points on the whole slide, any annotation named `KongNet Mitoz Özet`, and every display ring whose point was missing (including other wizards' rings). | Asks before the file chooser. Add names the summary `… (2)` and leaves the selected region's measurements alone. Replace removes only this wizard's points in the selected area, the rings linked to them and its summaries there. The orphan-ring sweep is gone. | `testKongNetOwnResultNeverMatchesByPrefixOrForeign`, `testKongNetAsksBeforeRemoving` |
| `yardimci-mitoz-karsilastir.groovy` | Every run removed same-class points in the region (also points the user had put in that class), consensus points, orphan rings, and any annotation whose name **started with** the summary name. | One question for the whole run, before export or Python. Own points are those carrying the model's source tag. Summaries match by exact name or `(N)`. A model that fails in a Replace run keeps its earlier points. | `testMitosisCompareOwnResultNeverMatchesByPrefixOrForeign`, `testMitosisCompareAsksOnceBeforeRunning` |
| `yardimci-tiatoolbox-bolge-sihirbaz.groovy` | Removed every annotation whose name **started with** `TIA bölge tespiti · ` on each run (region, whole image, project). | Asks before Python; the project run asks once up front. An own set must match name, class and point count exactly, so a point set edited by hand is no longer swept. Headless: `mevcut=ekle|degistir`, unknown value fails closed. | `testTiaRegionOwnSetNamingNeverMatchesByPrefix`, `testTiaRegionAsksBeforeRemoving` |
| `yardimci-tiatoolbox-sihirbaz.groovy` | Tissue-mask mode removed every `Doku maskesi (TIA Toolbox)` annotation on whichever image was open when the import ran. | Asks before export and Python (tissue-mask mode only). Works on the image captured at the start. An empty new mask removes nothing. | `testTiaMaskOwnSetNamingNeverMatchesByPrefix`, `testTiaMaskAsksBeforeRemoving` |
| `yardimci-doku-tespiti-sihirbaz.groovy` | Every apply (GUI and headless) removed all annotations named and classed `Doku`, with the objects inside them. | Asks on Uygula. The live preview never writes to the hierarchy. Headless stops when an earlier result exists unless `-a mevcut=ekle` or `-a mevcut=degistir` is given. | `testTissueDetectOwnSetNeverMatchesByPrefixOrOtherClass`, `testTissueDetectApplyAddKeepsEarlierAndReplaceKeepsChildren`, `testTissueDetectAsksBeforeRemoving` |
| `yardimci-tumor-tomurcuklanma.groovy` | `DELETE_EXISTING` deleted **every detection** under the selected region, including the user's cell detections (not in the audit). Front mode removed old bands; TMA mode duplicated its annotations. | `DELETE_EXISTING` is gone; only this run's new objects are filtered. Earlier buds, bands and core regions trigger the question. After Add the region's measurements get a ` (N)` suffix so earlier values are not overwritten. | `testBuddingOwnSetNamingNeverMatchesByPrefix`, `testBuddingEarlierResultIsOnlyOwnObjects`, `testBuddingAsksBeforeRemoving` |
| `yardimci-hotspot-sirali`, `-nn-mesafe`, `-capraz-nn-mesafe`, `-sinif-sayim`, `-delaunay-komsuluk` | Each re-run removed its summary annotation by exact name. | Ask before any write. An own summary must have the exact name (or `(N)`) **and** the script's signature measurement, so a same-named user annotation is neither asked about nor removed. Headless needs `mevcut`. | five tests per script: `testHotspot…`, `testNnDistance…`, `testCrossNn…`, `testClassCount…`, `testDelaunay…` |
| `yardimci-dogrulama-f1`, `-immun-yogunluk`, `-stromal-til`, `-wsinfer-ozet` | Each re-run removed its summary by exact name. | Ask before computing; Replace keeps objects inside. Headless needs `mevcut`. | three tests per script: `testValidationF1…`, `testImmuneDensity…`, `testStromalTil…`, `testWsinferSummary…` |
| `yardimci-mitoz-phh3` | Re-run removed the old `Mitoz Özet` with its PHH3 detections. | Asks first. Replace removes the old summaries and the PHH3 detections directly under them (this helper's own output; keeping them would double-count) and keeps other annotations under them. The dialog says so. | `testPhh3…` (3) |
| `yardimci-yapi-uzaklik`, `-kesisim-alani` | Each re-run removed its summary by exact name. | Ask before computing. The dialog names the measurements on the user's objects that the new run rewrites. | `testStructureDistance…`, `testIntersectionArea…` (3 each) |
| `yardimci-peritumoral-bant`, `-merkez-cevre` | Removed **every annotation of the output class(es)** before generating, including hand-drawn ones, even when generation then failed. | Own output = class + the script's name pattern + both measurements it writes. Removal happens only in Replace and only after generation succeeded. Replace covers all sources on the slide; the confirmation says so. | `testPeritumoralBand…`, `testCentrePeriphery…` (3 each) |
| `yardimci-konveks-zarf` | Removed the same-source hull by class and exact name. | Asks first; own hull = exact name or `(N)` and class. | `testConvexHull…` (3) |
| `yardimci-ki67-heterojenlik`, `-yogunluk-haritasi` | Removed everything whose name **started with** the grid name, plus the summary. | Ask before computing. Tiles match a strict pattern, summaries the exact name or `(N)`. Removal only in Replace, after the new objects are built. | `testKi67Heterogeneity…`, `testDensityMap…` (4 each) |
| `yardimci-goz-karari-kalibrasyon` | A name sweep deleted every grid annotation on each toggle and on window close, including another window's grid. | The window removes only the tiles it placed (tracked by reference). A grid from another window triggers the question. | `testEyeballCalibration…` (4) |
| `yardimci-histoart-sihirbaz` | Yes/no confirmation, then the whole-slide sentinel was removed with all objects inside. | Add / Replace / Cancel before the run. Replace removes own sets and their tile detections and keeps other objects. Earlier sets are no longer scanned as regions. Headless never wrote. | `testHistoArt…` (5) |
| `tumor-ici-ki67.groovy` | Re-run removed the earlier summary with its detections; the second path removed all detections under the selected region. | Asks before the work. Add: new summary `(2)`, or (second path) detection in a new copy of the region so the original and its detections stay. Replace removes after the new run succeeded and restores if the new run finds nothing. | `testTumorKi67…` (4) |
| `tumor-stroma.groovy`, `sihirbaz.groovy` | Removed old summaries with everything inside, earlier own regions, and generated objects. The wizard's sweep called `getDescription()` on detections, which do not have it. | Ask before measuring. Replace removes only own summaries and tagged class regions (exact tag) and keeps what is inside. This run's intermediate objects are removed by reference, never by name. | `testTumorStromaArea…` (5), `testTumorStromaWizard…` (5) |
| `hucre-tespiti.groovy`; `nukleer-boya`, `er-pr-hscore`, `membran-boya`, `sitoplazmik-boya` | Changed the image type (and with it the stain vectors) with only a log line when no stain was named Hematoxylin. | Say what will change and ask. "No" changes nothing and explains what to set by hand. Headless needs `-a tip=he` or `-a boya=varsayilan`. | `testModulesAskBeforeChangingImageTypeOrStains` |
| `hucre-tespiti`, `nukleer-boya`, `er-pr-hscore`, `sitoplazmik-boya`, `quantum-ctcf`, `yardimci-immun-yogunluk`, `yardimci-stromal-til`, `yardimci-stardist-sihirbaz`, `yardimci-instanseg-sihirbaz` | Running detection replaced the cells already inside the target annotation without a question. | When the target already holds cells the tool asks, with "keep, do not run" preselected. A choice dialog is used because a yes/no dialog makes Yes the Enter-key default. Headless stops unless `-a mevcut=degistir`. "Yeniden say (hızlı)" deletes nothing and needs no question. | `testRedetect…` (33 methods) |
| `membran-boya`, `yardimci-cellpose-sihirbaz` | The GUI asked yes/no; headless replaced silently. | Same keep-by-default choice as the others; headless stops unless `-a mevcut=degistir`. | `testRedetectMembrane…`, `testRedetectCellpose…` |
| `tumor-stroma.groovy`, `sihirbaz.groovy` (follow-up) | Replace removed the earlier summary, which could be a region the user drew (the tool renames a single selected region to the summary). | Summaries carry the metadata key `atolye_ozet_kaynak` (`arac` or `kullanici`). Only a summary marked `arac` can be deleted. A user region, and any unmarked summary from an older version, is re-measured in place. | `testTumorStromaAreaKeepsUserRegion…`, `testTumorStromaWizardKeepsUserRegion…` |
| `veri-aktarma`, `yardimci-kohort-ozet-topla`, `yardimci-grandqc-kohort-ozet` | Exports included every copy of a result without saying so; the GrandQC cohort summary read only the first set. | When an image holds copies (`X` and `X (2)`) the tool asks: all copies, or only the newest of each kind. Both choices are reported; "all copies" labels them. Headless needs `kopyalar=hepsi` or `kopyalar=enyeni`. No copies: no question, output unchanged. | `testCopiesExport…`, `testCopiesCohort…`, `testCopiesGrandQc…` (18 methods) |

## Not covered by automated tests

The dialogs themselves, Cancel in the GUI, and the project-wide import on a
real project. These are on the owner's GUI pass list.

## Known follow-up

`yardimci-grandqc-kohort-ozet.groovy` reads only the exact name `GrandQC KK`.
Until the export tools ask about copies (plan item 4b), a slide with two sets
is summarised from the first set only.

## Transfer wizards: what Replace removes

Owner decision, 15 November 2026: for VALIS, Warpy and the affine transfer,
Replace removes the old transfer whole. Every transferred object (outlines and
the objects inside them) now carries the metadata key `atolye_aktarim`. Replace
removes this wizard's outlines plus the tagged objects inside them and keeps
untagged ones, so an object the user added inside afterwards stays. Transfers
made before this change carry no tag: their inner objects stay, and the dialog
says so.

## Limits that remain

- An annotation the user names exactly like a wizard's set (for example `VALIS`
  or `Doku` with class `Doku`) cannot be told apart from the wizard's own. The
  confirmation states the count before anything is removed.
- In the budding helper, Replace removes earlier results before the new
  detection runs; if detection then fails, the earlier results are gone.
- Display rings left behind when a user deletes a mitosis point are no longer
  swept automatically.
- `yardimci-tiatoolbox-bolge-sihirbaz` still clears its own tile PNGs in its
  work folder before a run; these are intermediate files, not results.
- Measurements a helper writes onto the user's own objects (per-cell distances,
  per-annotation counts) are still rewritten by a new run; after Add the result
  text says they describe the new run.
- The re-detect question counts the cells that are direct children of the target
  annotation; cells nested under an inner annotation are not counted.
- A trailing ` (N)` is read as a copy number only when another member of the same
  base name is on the same image. `A (12)` and `A (3)` without `A` are both read
  as copies of `A`; a name alone cannot tell a point count from a copy number.
- Other exporters still include copies without saying so:
  `yardimci-anotasyon-disa-aktar`, `yardimci-karo-disa-aktarma`,
  `yardimci-kohort-boya-qc`, `yardimci-kosu-manifesti`, `yardimci-wsinfer-ozet`,
  `yardimci-tma-cekirdek-aktarim`, `yardimci-omezarr-disa-aktarma`.
- The StarDist wizard's optional classifier filter still removes the
  false-positive class after detection without a separate question.
- In `tumor-ici-ki67` (second path) the detections under a region carry no
  marker, so Replace removes whatever detections are there; the confirmation
  says so.
