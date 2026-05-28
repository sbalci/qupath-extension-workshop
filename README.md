# qupath-extension-workshop

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.20375397.svg)](https://doi.org/10.5281/zenodo.20375397)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Release](https://img.shields.io/github/v/release/sbalci/qupath-extension-workshop)](https://github.com/sbalci/qupath-extension-workshop/releases/latest)

QuPath extension that bundles the workshop scripts of the **Patologlar için QuPath** workshop (Dijital Patoloji & Yapay Zekâ) as one-click menu entries.

> Modeled after the official [QuPath extension template](https://github.com/qupath/qupath-extension-template).

## Citation

If you use this software in published work, please cite it using the metadata in [`CITATION.cff`](CITATION.cff). You can cite all versions by using the DOI [`10.5281/zenodo.20375397`](https://doi.org/10.5281/zenodo.20375397) — this DOI represents all versions and will always resolve to the latest one.

## What it does

After installation, a new **Extensions → Atölye** submenu appears in QuPath:

```
Extensions
└── Atölye
    ├── — Atölye scriptleri —
    ├── ─────────────────────
    ├── Modül 2 - Hücre tespiti
    ├── Modül 3 - Nükleer boya (Ki-67)
    ├── Modül 4 - Membranöz boya (HER2)
    ├── Modül 5 - Sitoplazmik boya (CD68)
    ├── Modül 6 - Tümör vs stroma sınıflandırıcı
    ├── Modül 7 - Tümör içi Ki-67
    ├── ─────────────────────
    └── Atölye hakkında…
```

Each item runs the corresponding workshop Groovy script: friendly Turkish dialogs explain what's about to happen, sensible defaults are applied, and the results pop-up gives measurements plus research/education context.

The extension is a convenience wrapper around the loose scripts at [handson/scripts/](../handson/scripts/). Both coexist: the loose scripts surface via **Automate → Project scripts** when the workshop project is open; the extension provides the same scripts without requiring participants to open the workshop project.

## Requirements

- **QuPath 0.6.0** or newer (baseline targeted by this build; works up to 0.7.x)
- Java 17+ (bundled with QuPath; no separate install needed)
- Module 8 / StarDist / QuANTUM are reserved for later workshop versions and are not required for this release.

## Build

```bash
./gradlew clean build
```

Output: `build/libs/qupath-extension-workshop-0.1.0.jar`

(On Windows: `gradlew.bat clean build`.)


If you don't have the Gradle wrapper checked in, run `gradle wrapper` once or use a globally-installed Gradle 8.x.

## Install

1. Open QuPath (0.6.0+).
2. Drag the built `.jar` onto the QuPath main window.
3. QuPath asks: *"Move to extensions folder?"* → click **Yes**.
4. Restart QuPath.
5. **Extensions → Atölye** menu appears.

## Distribution

The compiled JAR can be hosted on a GitHub Release. Users install via QuPath's built-in extension manager:

1. **Extensions → Manage extensions**
2. Add this repo URL: `https://github.com/sbalci/qupath-extension-workshop`
3. Click Install — QuPath fetches the latest release JAR.

## Versioning

| Version | Date    | QuPath baseline | Notes                                |
|---------|---------|------------------|--------------------------------------|
| 0.1.0   | 2026-05 | 0.6.0+           | İlk yayın — 7 modül scripti          |

## How it works internally

- `WorkshopExtension.java` implements `qupath.lib.gui.extensions.QuPathExtension` and is discovered by QuPath via the `META-INF/services/qupath.lib.gui.extensions.QuPathExtension` service loader file.
- On install, it creates the **Atölye** submenu under **Extensions** and registers one menu item per workshop script.
- Each menu item, when clicked, reads its script from `src/main/resources/scripts/` (packaged inside the JAR) and evaluates it through a fresh `GroovyShell` on a background thread.
- The scripts themselves use `qupath.lib.gui.dialogs.Dialogs` for user interaction, so they run identically whether launched from this menu or from `Automate → Project scripts`.

## License

Apache 2.0 — matches QuPath itself.

## Disclaimer

⚠️ Yalnızca **araştırma ve eğitim** amaçlıdır. Klinik karar verme için onaylı tıbbi cihaz değildir.




## Local build and install steps

```
[System.Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot', 'Machine')
```


```
Add-Content $PROFILE "`n# Override broken Machine-scope JAVA_HOME with working JDK 21
`$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot'`n"
```

```
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot'; .\gradlew.bat clean build
```

Then update tag and push

```
cd qupath-extension-workshop
git tag v0.1.0
git push origin v0.1.0
```



