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

**Release/source status, checked October 20, 2026:** the newest published JAR is [v0.2.2-rc5](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.2-rc5), published August 30, 2026. [`catalog.json`](catalog.json) points to that release. The `main` branch has newer scripts and safeguards; installing through the extension manager does **not** build or install `main`.

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

The extension is a convenience wrapper around the loose scripts at [qupath-workshop/handson/scripts/](https://github.com/sbalci/qupath-workshop/tree/main/handson/scripts). The companion workshop repository currently requires repository access; the public rc5 fallback files are linked in the session section below. Both coexist: the loose scripts surface via **Automate → Project scripts** when the workshop project is open; the extension runs the scripts packaged in the installed JAR without requiring participants to open the workshop project. Updating the workshop's scripts or its extension submodule (for example, [qupath-workshop PR #13](https://github.com/sbalci/qupath-workshop/pull/13)) does not update an installed JAR or publish a new one.

## Veri hazırlama oturumu: sabit sürüm ve yardımcılar

Katılımcının ayrıntılı [uygulama rehberi](https://github.com/sbalci/qupath-workshop/blob/codex/data-preparation-practical-source-only/sunumlar.qmd), veri hazırlama → takma adlandırma incelemesi → anotasyon → karo → devir sırasını izler. Süre yaklaşık 90 dakikadır ve esnektir. Patologlar ile patoloji deneyimi sınırlı teknik katılımcılar aynı **açık öğretim görüntüsünü** kullanır; klinik slayt/rapor kullanılmaz. Mevcut kapsamlı modüller ve dört sunum başvuru olarak korunur. Atölye deposu ve aşağıdaki PR/kaynak bağlantıları depo erişimi gerektirir. Rehber [qupath-workshop PR #15](https://github.com/sbalci/qupath-workshop/pull/15) içindeki `sunumlar.qmd` dosyasının `uygulama-rotasi` bölümünde yer alan taslaktır; yayımlanmış sitenin güncellendiği anlamına gelmez. Rehberin yönlendirdiği [çevrimdışı paket taslağı](https://github.com/sbalci/qupath-workshop/pull/16), ayrı dağıtılan ZIP ve yerel `BASLA.html` başlangıcını açıklar; GitHub kaynak ZIP'i indirilen görüntü/JAR ve üretilen başvuru çıktılarının yerine geçmez.

**Sürüm kaydı (20 Ekim 2026, statik inceleme):**

| Kaynak | Durum | Oturum için anlamı |
|---|---|---|
| [Kararlı Latest](https://github.com/sbalci/qupath-extension-workshop/releases/latest) | `v0.2.1` | Bu oturumun yardımcılarını içermez; hazırlık için tek başına bu bağlantıyı vermeyin |
| [Sabit aday yayın](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.2-rc5) | `v0.2.2-rc5`, **prerelease**, 30 Ağustos 2026 | Aşağıdaki menü ve kaynak dosyalarını içerir; katılımcı bilgisayarında prova ayrıca gerekir |
| rc5 kaynak commit'i | `d1df6f335ccf374826731c1d69026acaad11cd27` | Bu tablodaki kaynak incelemesinin temeli; `main` ile aynı kabul edilmez |

Aday dosya: [`qupath-extension-workshop-0.2.2-rc5.jar`](https://github.com/sbalci/qupath-extension-workshop/releases/download/v0.2.2-rc5/qupath-extension-workshop-0.2.2-rc5.jar), 3.226.968 bayt. GitHub yayın kaydının SHA-256 değeri:

```text
8717da4902e2d3c0b52726b8b4e8e20f56b772a53f176526944b99c7c0bc4624
```

Boyut ve özet yayın kaydından okunmuştur; bu README uzlaştırmasında JAR indirilmedi/çalıştırılmadı ve GUI provası yapılmadı. Kurulum adımları aşağıdaki [JAR kurulumu](#install-a-released-or-locally-built-jar) bölümündedir. Kullanılacak QuPath ve eklenti sürümü oturumdan önce birlikte sabitlenmelidir. Temel rota Python/GPU/model kurulumu gerektirmez.

**Menü yoksa kaynak dosyası yedeği:** [rc5 `src/main/resources/scripts/`](https://github.com/sbalci/qupath-extension-workshop/tree/v0.2.2-rc5/src/main/resources/scripts) içindeki dosyayı önceden hazırlayın; **Automate → Script editor** ile açıp **Run** kullanın. Projenin `scripts/` klasörüne konmuşsa **Automate → Project scripts** altında da bulunabilir. Kod düzenlemek gerekmez; QuPath arayüzünde çalıştırın, komut satırı/headless yedeği olarak kullanmayın. Dosyaları `main` sürümüyle karıştırmayın. Bazı betiklerin başlık yorumlarındaki eski menü yolları yerine aşağıdaki rc5 Java menü kayıtlarını esas alın. Aşağıdaki bütün menüler **Extensions → Atölye → Yardımcılar** altındadır.

| Alt menü → işlev | Dosya | Girdi / beklenen çıktı |
|---|---|---|
| Klinik ve kohort → Kohort üst veri sihirbazı | [`yardimci-metadata-sihirbaz.groovy`](https://github.com/sbalci/qupath-extension-workshop/blob/v0.2.2-rc5/src/main/resources/scripts/yardimci-metadata-sihirbaz.groovy) | Proje → `cohort_metadata/` içinde CSV + `detailed_summary.txt` |
| Klinik ve kohort → WSI anonimleştirme sihirbazı | [`yardimci-anonim-sihirbaz.groovy`](https://github.com/sbalci/qupath-extension-workshop/blob/v0.2.2-rc5/src/main/resources/scripts/yardimci-anonim-sihirbaz.groovy) | Proje + açık öğretim slaytı → seçilen klasörde kopyalar ve isteğe bağlı `anonymization_lookup_*.csv/.json` |
| Temel araçlar → Anotasyon yapısı QC (denetçi) | [`yardimci-anotasyon-qc.groovy`](https://github.com/sbalci/qupath-extension-workshop/blob/v0.2.2-rc5/src/main/resources/scripts/yardimci-anotasyon-qc.groovy) | Açık slayttaki anotasyonlar → yapısal sorun raporu; biyolojik doğruluk veya otomatik düzeltme sağlamaz |
| İçe ve dışa aktarma / veri → Karo (tile) dışa aktarma | [`yardimci-karo-disa-aktarma.groovy`](https://github.com/sbalci/qupath-extension-workshop/blob/v0.2.2-rc5/src/main/resources/scripts/yardimci-karo-disa-aktarma.groovy) | Açık kalibre slayt + anotasyon → varsayılan `exports/tiles/<slayt-slug>__<tarih>/`; maske modunda `images/`, `labels/`, `labels.txt` |
| Klinik ve kohort → İşlem kaydı (provenance JSON) | [`yardimci-kosu-manifesti.groovy`](https://github.com/sbalci/qupath-extension-workshop/blob/v0.2.2-rc5/src/main/resources/scripts/yardimci-kosu-manifesti.groovy) | Açık slayt gerekir; açık görüntü veya tüm proje kapsamı → proje altında `manifests/`; karo parametreleri ayrıca elle kaydedilir |

Kalibrasyon kontrolü için **Temel araçlar → Kalibrasyon (piksel boyutu)**, kaynak değerleri görmek için **Temel araçlar → Görüntü künyesi ve histogram** kullanılır; ikisi de rc5'te bulunur ve açık slayt gerektirir.

Anotasyon çizimi, sınıf atama ve GeoJSON devir adımları uygulama rehberindeki yerleşik QuPath araçlarıyla yapılır. Yukarıdaki QC betiği anotasyonları içe/dışa aktarmaz; `main` menüsündeki toplu anotasyon aktarımını rc5 özelliği saymayın.

**rc5 ile uygulamada dikkat edilecekler:**

- Anonimleştirme yardımcısı kopya üretir; yeniden adlandırma tam anonimleştirme değildir. Python kapalı tutulduğunda meta veriye dokunmaz; açıkken sabit listeli `ImageDescription` redaksiyonu yapar, etiket/makroyu veya ana görüntüdeki yazıyı kaldırmaz. Hata halinde yalnız kopyaya düşebilir. Anahtar, proje yolları, envanter, provenance, GeoJSON ve türetilmiş karolar ayrıca incelenir.
- rc5 sonuç penceresi proje için kopyaların bulunduğu klasörü önerebilir. **Ayrı, boş bir klasörde proje oluşturun**, sonra kopyaları **Add images** ile ekleyin. Kalibrasyonu bu yeni projede yeniden kontrol edin.
- Önce hasta düzeyinde bölme planını hazırlayın. Aynı hastanın slaytları, seri kesitleri, yeniden taramaları ve karoları aynı bölmede kalmalıdır. Yardımcı hasta bölmesini otomatik yapmaz.
- Kalibrasyon yoksa karo yardımcısı µm/px alanını downsample sayar; fiziksel ölçekli çıktı için durup doğrulayın. Çekirdek deneme: seçili iki küçük ROI, 256 px, 1.0 µm/px, örtüşme 0, PNG. Görüş alanı 256 µm'dir; kaynak µm/px ayrıca bilinmelidir.
- **Form her açılışta sıfırlanır:** varsayılan 512 px ve “Tüm anotasyonlar”dır. RGB'den maske moduna geçerken de **Seçili anotasyon(lar) (2), 256 px, 1.0 µm/px, 0 px örtüşme, PNG** değerlerini yeniden girin. Yardımcı kısmi kenar karolarını açmaz (`includePartialTiles` varsayılan false); eksik kenar şeritleri atlanır, farklı boyutlar ayrıca incelenir.
- Anotasyonla kesişen dikdörtgen karolar boş cam veya poligon dışı piksel içerebilir. `Ignore*` otomatik dışlanmaz. Sınıf indeksleri kapsamdaki alfabetik sınıf listesine bağlıdır; her çıktıda `labels.txt` kontrol edilir. Kısmi anotasyondaki 0, kanıtlanmış negatif etiket değildir.
- Maske küçük tamsayılar nedeniyle siyah görünebilir. Bir RGB/maske çiftinin koordinat, boyut ve değerlerini kontrol etmeden eğitim için hazır saymayın. Karo ayarlarını ve QC sonucunu elle kayıt şablonuna yazın.

Bu sınırlamalar rc5 kaynağından incelenmiştir. Bu README uzlaştırmasında menü/dosya eşlemeleri ve yayın meta verileri statik olarak kontrol edilmiştir. Betik çalıştırma, derleme, kurulum ve GUI provası yapılmamıştır; işlevsel doğrulama yapıldığı iddia edilmez.

## Requirements

- **QuPath 0.6.0** is the compile baseline and the catalog's minimum version. Check individual tools and dependencies before using a newer QuPath version.
- **JDK 21** is the source-build toolchain in both rc5 and the reviewed `main`; Java 17 does not meet it. Normal QuPath installations supply their own runtime, so installing a released JAR does not require a separate JDK.
- The data-preparation route uses QuPath and the pinned rc5 helpers; it does not require a GPU, StarDist, Cellpose, QuANTUM or an external Python environment. Leave optional Python metadata redaction off for this route.
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

As of October 20, 2026, the catalog's newest entry is **v0.2.2-rc5**. The manager downloads the published JAR referenced by the catalog; it does not compile the current branch. New source functionality becomes available through this route only after a new JAR is published and the catalog references it. Use the explicit release link above to obtain rc5; GitHub's **Latest** link/badge can differ when releases are marked as prereleases.

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
