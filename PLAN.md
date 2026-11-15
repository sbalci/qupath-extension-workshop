# Phase 2 plan — QuPath workshop extension

Status: **approved by the owner on 15 November 2026. Phase 3 runs extension first, then the QuPath site, gozkarari, the AI workshop site.**
Decisions added at approval: item 12 is settled — "Tümör/Stroma" becomes a submenu with three entries (the wizard, "Adım 1 — Model eğit", "Adım 2 — Alan ölçümü"), each step checking its own prerequisites; `CLAUDE.md` corrections are applied by Claude in their own commit on the feature branch.
Based on `AUDIT.md` of 15 November 2026 (HEAD `ceb2935`) and the owner's decisions of the same day.
Branch for Phase 3: `fix/ask-first-and-rc9` (one logical change per commit; no push, no tag, no release until the owner says so).

## Owner decisions this plan follows

| Topic | Decision |
|---|---|
| Deleting or replacing existing objects | Never without asking. Applies to every wizard and helper: detections, annotations, a wizard's own summary or sentinel annotation, transferred or aligned results. Default is "add, delete nothing". |
| Not misguiding the user | Docs, messages and menu hints must describe what the installable build really does. |
| Next release | Fix first, then publish `v0.2.2-rc9` as a test build. A final `v0.2.2` only after the owner's GUI pass. |
| Root menu name | Stays "Atölye" in both languages. English docs are corrected. |
| Decimal format | Point everywhere in the UI (`0.25`). `GLOSSARY.md` gets an explicit exception for software output. |
| Diagnostic summary | Masking code is left as is. The README is corrected so it no longer says the summary is safe to share. |
| `uv` download | Left as is (no checksum pin). |
| Sanofi entry, `model-egit`, `tumor-stroma` | Implement all three in the extension, the scripts and the website. |
| "slayt" in UI text | Kept (the QuPath site keeps clinical loanwords). |

## Work items, in order

Effort: S = under an hour, M = half a day, L = a day or more. Every script edit is mirrored to `D:\qupath-workshop\handson\scripts\` and checked with `check-script-sync.ps1`.

| # | Change | Why (audit id) | Effort | Files |
|---|---|---|---|---|
| 1 | One shared "existing results" dialog pattern: Add (default) / Replace (second confirm, states exactly what will be removed) / Cancel (writes nothing, asked before any heavy work). Headless default deletes nothing; a named argument is required to replace; unknown values fail closed. | Y3, owner rule | M | copied-verbatim closures in each script (scripts stay self-contained), `strings_tr/en.properties` |
| 2 | Apply item 1 to the wizards that still replace silently: GrandQC import, VALIS (two paths), Warpy, affine transfer, KongNet import, mitosis compare, TIA Toolbox region wizard, TIA Toolbox tissue mask, native tissue-detection wizard, tumour budding bands. | Y3 | L | `yardimci-grandqc-sihirbaz`, `-valis-sihirbaz`, `-warpy-sihirbaz`, `-hizalama-aktarim`, `-mitoz-kongnet`, `-mitoz-karsilastir`, `-tiatoolbox-bolge-sihirbaz`, `-tiatoolbox-sihirbaz`, `-doku-tespiti-sihirbaz`, `-tumor-tomurcuklanma` |
| 3 | HistoART: the GUI already asks; make the headless path delete nothing by default. | Y3, section 7 | S | `yardimci-histoart-sihirbaz.groovy` |
| 4 | Apply item 1 to the ~21 measurement helpers that silently replace their own summary, grid or ring annotation on a re-run (hotspot, nearest-neighbour, class count, Delaunay, F1, immune density, stromal TIL, WSInfer summary, pHH3, structure distance, intersection area, peritumoural band, convex hull, centre/periphery, Ki-67 heterogeneity, density map, eyeball calibration, tumour-internal Ki-67, tumour/stroma wizard and script). Matching is by exact name or `(N)` suffix, never by prefix, so a user-renamed annotation is never swept. | Owner: "ask for everything" | L | the scripts listed in `AUDIT.md` section 7 |
| 5 | Stop changing the image type silently: `hucre-tespiti` (H&E) and the four stain modules (H-DAB default vectors) show what they are about to set and ask, instead of only printing to the log. | Section 7, "do not misguide" | M | `hucre-tespiti`, `nukleer-boya`, `er-pr-hscore`, `membran-boya`, `sitoplazmik-boya` |
| 6 | Tests for items 1–5: extend `SafetyRegressionTest` with the naming/matching closures of each changed script (add → add → add numbering, replace → add, user-renamed annotation survives, cancel writes nothing). Add automated coverage for the ask paths that have none today: Classpose, HoVer-NeXt, FCOS, RetinaNet, Cellpose, Membran. | Y10, E5 | L | `src/test/…`, CI test-count floor |
| 7 | Error guidance fixes: `NumberFormatException` (a typed "0,25") says "enter the number with a point or comma, e.g. 0.25" instead of "image unreadable"; NaN rule matches the word, not the substring; `NullPointerException` / `MissingMethodException` no longer say "open a slide". Unit tests for each. | Y5 | M | `WorkshopErrors.java`, tests |
| 8 | Messages that state a problem without a next step get one: "GeoJSON okunamadı", "Alt betik hatası", "Bölge sınır dışı ya da boş". Warnings that only go to the log (fluorescence image in a brightfield module) are shown to the user. | Section 5 | M | GrandQC, Sectra, HoVer-NeXt, `tumor-ici-ki67`, `quantum-ctcf`, `sihirbaz`, FCOS, RetinaNet, `hucre-tespiti` |
| 9 | English mode: translate the remaining Turkish-only UI lines (about 841 by heuristic count), starting with error paths and the Add/Replace choice labels; move the `Ortam kontrolü` and `Hakkında` texts out of Java into the properties files; add the missing "Settings" section to the English About text. | E1, Y8 | L | scripts, `strings_tr/en.properties`, `WorkshopExtension.java:604-750` |
| 10 | Menu path hints include the topic group ("Yardımcılar → Temel araçlar → Kalibrasyon"), 94 lines. | Y6 | M | scripts, properties |
| 11 | Sanofi EFTD entry becomes active. On start the wizard checks the Python environment and Hugging Face access to the gated backbone; if either is missing it explains, in the user's language, the exact steps (accept the licence on the model page, log in, wait for approval) and stops. The script docstring no longer tells the user to rebuild the extension. | Y12, owner | M | `WorkshopExtension.java:105`, `yardimci-mitoz-atipik-sanofi.groovy`, README |
| 12 | Menu entries for `model-egit` and `tumor-stroma`. **To confirm before coding:** both look like sub-steps that the Tümör/Stroma wizard runs. If so, standalone entries need their own "what must exist first" check, or they would mislead. I will report what I find and ask before adding them. | Y12, owner | M | `WorkshopExtension.java`, both scripts, properties |
| 13 | README and docs corrections: diagnostic summary "may contain file and slide names — read it before sharing"; install steps identical to the site pages (same order, same button names — button names to be confirmed in the owner's GUI pass); English paths say `Extensions → Atölye (Workshop)`; stale counts ("143 texts / eight scripts"); `docs/rc8-unpublished-candidate.md` and `CITATION.cff` no longer call rc8 unpublished; where the synthetic first-run PNG goes on disk. | Y4, Y6, Y7, Y11, Y16 | M | `README.md`, `docs/first-run.md`, `CITATION.cff`, `CHANGES.md` |
| 14 | `CHANGELOG.md`: complete entries for 0.2.0 → rc8 from git history, dated; `[Unreleased]` describes this branch. | Y11, K4 | M | `CHANGELOG.md` |
| 15 | `tools/verify-candidate.py` reads files as UTF-8 so it runs on Windows. | Y14 | S | `tools/verify-candidate.py` |
| 16 | Release `v0.2.2-rc9` with `bump-extension-version.ps1` — **only on the owner's explicit go-ahead**, after items 1–15 and the owner's GUI pass of the ask dialogs. Then `check-release-sync.ps1`. | Y1, Y2, Y9 | S | version-bearing files, `catalog.json` |

## What I cannot do and will hand to the owner

- A GUI pass list: each changed wizard, the three answers (Add / Replace / Cancel), language switch, the Sanofi gate message, catalog install button names.
- Real model runs for the Python bridges touched (GrandQC, VALIS, Warpy, KongNet, TIA Toolbox).

## Not in this plan (by decision)

Diagnostic masking code, `uv` checksum pin, renaming the root menu, decimal comma in the UI.

## Dependencies added

None.
