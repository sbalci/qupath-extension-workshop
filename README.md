<img src="assets/logo.svg" alt="Patologlar için QuPath Atölyesi" width="320">

# qupath-extension-workshop

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.20375397.svg)](https://doi.org/10.5281/zenodo.20375397)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Release](https://img.shields.io/github/v/release/sbalci/qupath-extension-workshop)](https://github.com/sbalci/qupath-extension-workshop/releases/latest)

**Türkçe Özet:** **Patologlar için QuPath** (Dijital Patoloji & Yapay Zekâ) atölyesinin 102 Groovy betiğini QuPath içine tek-tıkla menü öğeleri, görsel sihirbazlar ve kalıcı ayarlar olarak ekleyen QuPath eklentisidir.

**English Overview:** QuPath extension that bundles all 102 Groovy scripts of the **Patologlar için QuPath** workshop (Digital Pathology & AI) as one-click Turkish menu entries, interactive wizards, and persistent workshop preferences.

> Modeled after the official [QuPath extension template](https://github.com/qupath/qupath-extension-template).

## Atıf / Citation

If you use this software in published work, please cite it using the metadata in [`CITATION.cff`](CITATION.cff). You can cite all versions by using the DOI [`10.5281/zenodo.20375397`](https://doi.org/10.5281/zenodo.20375397) — this DOI represents all versions and will always resolve to the latest one.

## Ne Yapar? / What it does

Kurulumdan sonra QuPath'in **Extensions** menüsüne **Atölye** alt menüsü ve **Senkron Izgara** kısayolu eklenir (toplam 102 paketlenmiş Groovy betiği):

After installation, **Extensions → Atölye** and **Extensions → Senkron Izgara** appear in QuPath (102 bundled scripts in total):

```text
Extensions
├── Atölye
│   ├── — Atölye betikleri —  (slayt açık değilse: ⚠ Önce bir slayt açın)
│   ├── ─────────────────────
│   ├── Modüller  (13 öğe / alt menü)
│   │   ├── Arayüz turu
│   │   ├── Doku tespiti ▸ (4 araç: Doku Tespiti Sihirbazı, Piksel Eşikleyici, DoG / Süperpiksel, TIAToolbox)
│   │   ├── Hücre tespiti
│   │   ├── Nükleer boya (Ki-67)
│   │   ├── ER / PR H-score
│   │   ├── Membran boya (HER2)
│   │   ├── Sitoplazmik boya (CD68)
│   │   ├── Tümör/Stroma sihirbazı
│   │   ├── Tümör içi Ki-67
│   │   ├── Mitoz tespiti ▸ (7 araç: MİA Kalibrasyon, 2 mm² Bölge, Aday Bulucu, Galeri, PHH3, H&E + PHH3, KongNet)
│   │   ├── QuANTUM cTCF
│   │   ├── Görüntü Hizalama ▸ (3 araç: Etkileşimli Hizalama, WSI-aligner, Otomatik Afin + İnce Eşleme)
│   │   └── Veri dışa aktarma
│   ├── Yardımcılar  (9 konu grubu)
│   │   ├── Temel araçlar ▸ (6 betik)
│   │   ├── Boya ve renk ▸ (4 betik)
│   │   ├── Hücre ve çekirdek tespiti ▸ (11 betik)
│   │   ├── Skorlama ve ölçüm ▸ (12 betik)
│   │   ├── Uzamsal analiz ▸ (9 betik)
│   │   ├── İçe ve dışa aktarma / veri ▸ (14 betik)
│   │   ├── Python köprüleri ve temel modeller ▸ (9 betik)
│   │   ├── Klinik ve kohort ▸ (6 betik)
│   │   └── Eğitim, sunum ve ImageJ ▸ (6 betik)
│   ├── ─────────────────────
│   ├── Atölye Ayarları…
│   ├── Ortam kontrolü…
│   └── Atölye hakkında…
└── Senkron Izgara  (çok-görüntülü eş zamanlı karşılaştırma)
```

Each item runs the corresponding workshop Groovy script: friendly Turkish wizards explain what is about to happen, sensible workshop defaults are applied, and non-blocking result windows display measurements plus research/education context.

The extension is a self-contained wrapper around the loose scripts at [`handson/scripts/`](../handson/scripts/). Both coexist: the loose scripts surface via **Automate → Project scripts** when the workshop project is open; the extension provides the exact same scripts without requiring participants to open the workshop project.

## Gereksinimler / Requirements

- **QuPath 0.6.0** or newer (baseline targeted by this build; compatible with 0.7.x)
- Java 17+ (bundled with QuPath; no separate install needed)
- **Optional deep-learning extensions:** `Membran boya (HER2)` (Cellpose) and `QuANTUM cTCF` (StarDist) automatically fall back to QuPath's built-in classic Watershed cell detection if Cellpose or StarDist is not installed.

## Derleme / Build

```bash
./gradlew clean build
```

Output: `build/libs/qupath-extension-workshop-X.Y.Z.jar`

(On Windows: `.\gradlew.bat clean build`.)

## Kurulum / Install

### Yöntem 1: QuPath Eklenti Yöneticisi (Önerilen / Recommended)

1. **Extensions → Manage extensions**
2. Add custom catalog URL: `https://raw.githubusercontent.com/sbalci/qupath-extension-workshop/main/catalog.json`
3. Select **Atölye eklentisi** and click **+** (Install) — QuPath fetches the latest release JAR.

### Yöntem 2: Sürükle-Bırak (Drag-and-Drop)

1. Open QuPath (0.6.0+).
2. Drag the built `.jar` onto the QuPath main window.
3. QuPath asks to copy the file to the extensions folder → confirm.
4. Restart QuPath; **Extensions → Atölye** appears.

## Sürümleme ve Yayın / Versioning & Releases

Releases are cut from the repository root using the version-bump tool, which updates `build.gradle.kts` and `catalog.json`, commits, tags, and dispatches release workflows across GitHub Releases, Zenodo, and OSF:

```powershell
# From the parent repository root:
pwsh ./tools/bump-extension-version.ps1 -NewVersion X.Y.Z -NoPush
# Review changes, then push or run without -NoPush
pwsh ./tools/check-version-sync.ps1
```

> **Note:** Version strings must follow `X.Y.Z` or `X.Y.Z-rcN` (QuPath's extension manager only recognizes `-rcN` pre-release suffixes).

| Version | Date    | QuPath baseline | Notes                                                          |
|---------|---------|-----------------|----------------------------------------------------------------|
| 0.3.x   | 2026    | 0.6.0+          | 102 atölye betiği (13 modül girişi + 9 yardımcı konu grubu)    |
| 0.1.0   | 2026-05 | 0.6.0+          | İlk yayın — çekirdek modül betikleri                           |

## İç Yapı / How it works internally

- `WorkshopExtension.java` implements `qupath.lib.gui.extensions.QuPathExtension` and is discovered by QuPath via `META-INF/services/qupath.lib.gui.extensions.QuPathExtension`.
- On install, it creates the **Atölye** submenu under **Extensions**, registers module and helper submenus, and exposes workshop settings both via **Atölye Ayarları…** (`WorkshopSettingsDialog.java`) and QuPath's **Edit → Preferences…** (`WorkshopPrefs.java`).
- Each menu item reads its script from `src/main/resources/scripts/` (packaged inside the JAR as UTF-8) via `WorkshopResources.java` and evaluates it through a fresh `GroovyShell` on a background thread.
- The scripts use `qupath.fx.dialogs.Dialogs` and JavaFX non-modal stages for user interaction, so they run identically whether launched from this menu or from `Automate → Project scripts`.

## Lisans / License

Apache 2.0 — matches QuPath itself.

## Sorumluluk Reddi / Disclaimer

⚠️ Yalnızca **araştırma ve eğitim** amaçlıdır. Klinik karar verme için onaylı tıbbi cihaz değildir.
