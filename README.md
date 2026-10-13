<img src="assets/logo.svg" alt="Patologlar için QuPath Atölyesi" width="320">

# qupath-extension-workshop

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.20375397.svg)](https://doi.org/10.5281/zenodo.20375397)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Release](https://img.shields.io/github/v/release/sbalci/qupath-extension-workshop)](https://github.com/sbalci/qupath-extension-workshop/releases/latest)

QuPath extension that bundles the workshop scripts of the **Patologlar için QuPath** workshop (Dijital Patoloji & Yapay Zekâ) as one-click menu entries.

> Modeled after the official [QuPath extension template](https://github.com/qupath/qupath-extension-template).

## Citation

If you use this software in published work, please cite it using the metadata in [`CITATION.cff`](CITATION.cff). You can cite all versions by using the DOI [`10.5281/zenodo.20375397`](https://doi.org/10.5281/zenodo.20375397) — this DOI represents all versions and will always resolve to the latest one.

## What it does

After installation, a new **Extensions → Atölye** submenu appears in QuPath. The entries available depend on the JAR you installed.

**Release/source status, checked October 13, 2026:** the newest published JAR is [v0.2.2-rc5](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.2-rc5), published August 30, 2026. [`catalog.json`](catalog.json) points to that release. The `main` branch has newer scripts and safeguards; installing through the extension manager does **not** build or install `main`.

### Current source menu (`main`, not a released-JAR feature list)

The following outline reflects [`WorkshopExtension.java`](src/main/java/io/github/sbalci/qupath/workshop/WorkshopExtension.java) at [`f81faf5`](https://github.com/sbalci/qupath-extension-workshop/commit/f81faf56db9a86aad81187b07dba4c59edbff3ce) (October 12, 2026). Module entries and utility groups are shown in registration order; utility examples are abbreviated.

| Menu path | Entries / submenus |
|-----------|--------------------|
| Extensions → Atölye | Disabled header **— Atölye betikleri —**; **Modüller**; **Yardımcılar**; **Atölye Ayarları…**; **Ortam kontrolü…**; **Atölye hakkında…** |
| Atölye → Modüller | **Arayüz turu**; **Doku tespiti** submenu; **Hücre tespiti**; **Nükleer boya (Ki-67)**; **ER / PR H-score**; **Membran boya (HER2)**; **Sitoplazmik boya (CD68)**; **Tümör/Stroma sihirbazı**; **Tümör içi Ki-67**; **QuANTUM cTCF**; **Mitoz tespiti** submenu; **Görüntü Hizalama** submenu; **Veri dışa aktarma** |
| Modüller → Doku tespiti | **Yerleşik (Otsu / ContourTracing)**; **Piksel eşikleyici (Create thresholder)**; **GrandQC modeli (doku ve artefakt, Python)**; **TIA Toolbox maskesi (Otsu/morfoloji, Python)** |
| Modüller → Mitoz tespiti | **Mitoz modelleri listesi**; **KongNet MIDOG (TIA Toolbox)**; **MIDOG25 FCOS (torchvision)**; **MIDOG DA-RetinaNet (2021, eski)**; **Atipik sınıflandırma — MIDOG25 EffNetV2**; **Atipik sınıflandırma — Sanofi EFTD (kapılı)** (disabled); **Modelleri karşılaştır (aynı bölge)** |
| Modüller → Görüntü Hizalama | **VALIS (Docker/yerel)**; **Warpy (esnek; Fiji)**; **Afin (otomatik/elle)** |
| Yardımcılar → Temel araçlar | Detection cleanup, image type, thresholds, calibration, image information/histogram, annotation QC |
| Yardımcılar → Boya ve renk | Stain-vector wizard, stain-quality QC, ICC inspector, cohort stain-OD drift report |
| Yardımcılar → Hücre ve çekirdek tespiti | StarDist, Cellpose, InstanSeg, HoVer-NeXt, Classpose, **CytoFormer hücre tipi sınıflama sihirbazı (H&E, 16 organ)**, **HistoPLUS hücre segmentasyonu + sınıflama sihirbazı (H&E, 13 sınıf)**, **NuClick (tıkla → çekirdek sınırı) sihirbazı**, hepatocyte segmentation, KongNet, F1/IoU validation |
| Yardımcılar → Skorlama ve ölçüm | Area/cell scoring, Ki-67 heterogeneity, TILs, PHH3, tumor budding, WSInfer summaries/checks, hotspots, integrated DAB OD |
| Yardımcılar → Uzamsal analiz | Boundary distances, Delaunay/nearest-neighbor features, density map, overlap, peritumoral band, convex hull, centre/periphery |
| Yardımcılar → İçe ve dışa aktarma / veri | Batch annotation export/import, tiles, OME-Zarr, crops, feature matrices, cluster labels, GeoJSON/raster masks, TMA, DICOM conversion, example classifier |
| Yardımcılar → Python köprüleri ve temel modeller | Python environment manager, TIA Toolbox, Kaiko Midnight, SPIDER, HistoART, foundation-model readiness |
| Yardımcılar → Klinik ve kohort | Cohort metadata, Sectra annotations, anonymization, cohort summaries, GrandQC QC summary, provenance JSON |
| Yardımcılar → Eğitim, sunum ve ImageJ | Image-processing tour, input display, snapshots, ImageJ threshold/spline tools, scatter chart |
| Extensions → Senkron Izgara | Separate top-level viewing command, outside **Atölye** |

`UPCOMING_SCRIPTS` is empty in this source snapshot, so **İleri analiz — sonraki oturum** is not displayed. Other runnable entries can be greyed out when their required image/project is absent or the extension is disabled in preferences.

### Released JAR versus newer source

The [v0.2.2-rc5 source](https://github.com/sbalci/qupath-extension-workshop/blob/v0.2.2-rc5/src/main/java/io/github/sbalci/qupath/workshop/WorkshopExtension.java) already contains the grouped module/utility structure, tissue and mitosis detection, image alignment, and the separate **Senkron Izgara** command. It does **not** contain the newer HoVer-NeXt, Classpose, CytoFormer, HistoPLUS, NuClick, HistoART, batch annotation import/export, or mitosis-model-list menu additions shown above.

The October 12 CytoFormer, HistoPLUS, and NuClick safeguards are also source-only: they preserve existing classifications or results by default and require explicit choices/confirmation for replacement. These safeguards are not delivered by the published rc5 JAR. To use them, build the current source as described below; model access, Python environments, and any required third-party extensions remain separate prerequisites.

Each item runs the corresponding workshop Groovy script: friendly Turkish dialogs explain what's about to happen, sensible defaults are applied, and the results pop-up gives measurements plus research/education context.

The extension is a convenience wrapper around the loose scripts at [qupath-workshop/handson/scripts/](https://github.com/sbalci/qupath-workshop/tree/main/handson/scripts). Both coexist: the loose scripts surface via **Automate → Project scripts** when the workshop project is open; the extension runs the scripts packaged in the installed JAR without requiring participants to open the workshop project. Updating the workshop's scripts or its extension submodule (for example, [qupath-workshop PR #13](https://github.com/sbalci/qupath-workshop/pull/13)) does not update an installed JAR or publish a new one.

## Requirements

- **QuPath 0.6.0** is the compile baseline and the catalog's minimum version. Check individual tools and dependencies before using a newer QuPath version.
- Java 21 is the build toolchain; normal QuPath installations supply their own runtime, so installing a released JAR does not require a separate JDK.
- Optional tools require their own dependencies: for example, QuANTUM cTCF requires StarDist, and Python/model bridges need the environments, weights, permissions, or extensions described by their wizards. A menu entry does not mean these dependencies are bundled.

## Build the current source (development JAR)

Install a JDK 21 and use the checked-in Gradle wrapper (no separate Gradle installation is needed). From a fresh clone:

```bash
git clone https://github.com/sbalci/qupath-extension-workshop.git
cd qupath-extension-workshop
git switch main
git rev-parse HEAD
./gradlew clean jar
```

On Windows PowerShell, use `.\gradlew.bat clean jar`. If Java is not found, set `$env:JAVA_HOME` to your own JDK 21 installation directory for that session.

Output: `build/libs/qupath-extension-workshop-<version>.jar`, where `<version>` comes from [`build.gradle.kts`](build.gradle.kts). At the source snapshot above, that value is still `0.2.2-rc5`: a local build can therefore have the **same filename/version as the published JAR while containing newer code**. Record the commit printed by `git rev-parse HEAD` alongside your build; use **Atölye hakkında…** to inspect its build timestamp.

`clean jar` assembles the development JAR without running automated test suites. `clean build` is the full build/check command for later validation. A local build is not a published release and does not change the extension catalog. No tag push or release workflow is needed for local installation.

## Install a released or locally built JAR

For the published version, open the [v0.2.2-rc5 release](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.2-rc5) and download **`qupath-extension-workshop-0.2.2-rc5.jar`** from **Assets**, not the source ZIP/tarball. For current-source features, use the JAR built above.

1. Open QuPath (0.6.0+).
2. Drag the downloaded or locally built `.jar` onto the QuPath main window.
3. QuPath asks: *"Move to extensions folder?"* → click **Yes**.
4. Restart QuPath.
5. **Extensions → Atölye** menu appears.

When replacing an existing installation, close QuPath before replacing the old workshop JAR in its extensions folder, keep only one workshop JAR there, and reopen QuPath. Do not overwrite a JAR while QuPath is using it.

## Distribution through the extension manager

Users can install the **published catalog release** via QuPath's built-in extension manager:

1. **Extensions → Manage extensions**
2. **Manage extension catalogs → Add**: add this repository's catalog URL, `https://github.com/sbalci/qupath-extension-workshop` (the catalog is [`catalog.json`](catalog.json) at the repository root).
3. Install **QuPath Workshop extension (Atölye)** using its **+ / Install** control and restart QuPath.

As of October 13, 2026, the catalog's newest entry is **v0.2.2-rc5**. The manager downloads the published JAR referenced by the catalog; it does not compile the current branch. New source functionality becomes available through this route only after a new JAR is published and the catalog references it. Use the explicit release link above to obtain rc5; GitHub's **Latest** link/badge can differ when releases are marked as prereleases.

## Versioning

Dates below are GitHub **publication dates (UTC)**, not commit or tag dates. Each link provides the release notes and downloadable JAR. The `-alpha` and `-rc` suffixes denote development/release-candidate versions, not a final 0.2.2 release.

<!-- Published release rows are maintained from GitHub release metadata. -->
| Published version | Published (UTC) | Distribution |
|-------------------|-----------------|--------------|
| [v0.2.2-rc5](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.2-rc5) | 2026-08-30 | Published JAR; newest catalog entry |
| [v0.2.2-rc4](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.2-rc4) | 2026-06-27 | Published JAR |
| [v0.2.2-rc3](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.2-rc3) | 2026-06-22 | Published JAR |
| [v0.2.2-rc2](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.2-rc2) | 2026-06-21 | Published JAR |
| [v0.2.2-rc1](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.2-rc1) | 2026-06-21 | Published JAR |
| [v0.2.1](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.1) | 2026-06-16 | Published JAR |
| [v0.2.0-alpha1](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.0-alpha1) | 2026-06-05 | Published JAR |
| [v0.2.0](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.0) | 2026-06-05 | Published JAR |
| [v0.2.0-alpha5](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.0-alpha5) | 2026-05-31 | Published JAR |
| [v0.2.0-alpha4](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.0-alpha4) | 2026-05-31 | Published JAR |
| [v0.2.0-alpha3](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.0-alpha3) | 2026-05-30 | Published JAR |
| [v0.2.0-alpha2](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.0-alpha2) | 2026-05-29 | Published JAR |
| [v0.1.1-alpha2](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.1.1-alpha2) | 2026-05-26 | Published JAR |
| [v0.1.0](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.1.0) | 2026-05-25 | Published JAR |

Current `main` changes after rc5 are **unreleased**. Neither a source commit nor a parent-repository submodule update is a compiled-extension release.

## How it works internally

- `WorkshopExtension.java` implements `qupath.lib.gui.extensions.QuPathExtension` and is discovered by QuPath via the `META-INF/services/qupath.lib.gui.extensions.QuPathExtension` service loader file.
- On install, it creates the **Atölye** submenu under **Extensions** and registers one menu item per workshop script.
- Each menu item, when clicked, reads its script from `src/main/resources/scripts/` (packaged inside the JAR) and evaluates it through a fresh `GroovyShell` on a background thread.
- The scripts themselves use `qupath.lib.gui.dialogs.Dialogs` for user interaction, so they run identically whether launched from this menu or from `Automate → Project scripts`.

## License

Apache 2.0 — see [`LICENSE`](LICENSE). Third-party tools and models retain their own licenses.

## Disclaimer

⚠️ Yalnızca **araştırma ve eğitim** amaçlıdır. Klinik karar verme için onaylı tıbbi cihaz değildir.
