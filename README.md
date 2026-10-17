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

After installation, a new **Extensions → Atölye** submenu appears in QuPath. This is the data preparation subset of the grouped menus (the complete extension also contains other modules):

```
Extensions
└── Atölye
    ├── Yardımcılar
    │   ├── Temel araçlar
    │   │   ├── Kalibrasyon (piksel boyutu)
    │   │   ├── Görüntü künyesi ve histogram
    │   │   └── Anotasyon yapısı QC (denetçi)
    │   ├── İçe ve dışa aktarma / veri
    │   │   └── Karo (tile) dışa aktarma
    │   └── Klinik ve kohort
    │       ├── Kohort üst veri sihirbazı
    │       ├── WSI anonimleştirme sihirbazı
    │       └── İşlem kaydı (provenance JSON)
    └── Atölye hakkında…
```

Each item runs the corresponding workshop Groovy script: friendly Turkish dialogs explain what's about to happen, sensible defaults are applied, and the results pop-up gives measurements plus research/education context.

The extension is a convenience wrapper around the loose scripts at [the workshop's handson/scripts/](https://github.com/sbalci/qupath-workshop/tree/main/handson/scripts). Both coexist: the loose scripts surface via **Automate → Project scripts** when the workshop project is open; the extension provides the scripts without requiring participants to open the workshop project. Match the source revision to the session build rather than assuming `main` and a published JAR contain identical scripts.

## Veri hazırlama oturumu: sabit sürüm ve yardımcılar

Katılımcının ayrıntılı [uygulama rehberi](https://atolye.patoloji.dev/sunumlar#uygulama-rotasi), veri hazırlama → takma adlandırma incelemesi → anotasyon → karo → devir sırasını izler. Süre yaklaşık 90 dakikadır ve esnektir. Patologlar ile patoloji deneyimi sınırlı teknik katılımcılar aynı **açık öğretim görüntüsünü** kullanır; klinik slayt/rapor kullanılmaz. Mevcut kapsamlı modüller ve dört sunum başvuru olarak korunur.

**Sürüm kaydı (17 Ekim 2026, statik inceleme):**

| Kaynak | Durum | Oturum için anlamı |
|---|---|---|
| [Kararlı Latest](https://github.com/sbalci/qupath-extension-workshop/releases/latest) | `v0.2.1` | Bu oturumun yardımcılarını içermez; hazırlık için tek başına bu bağlantıyı vermeyin |
| [Sabit aday yayın](https://github.com/sbalci/qupath-extension-workshop/releases/tag/v0.2.2-rc5) | `v0.2.2-rc5`, **prerelease**, 30 Ağustos 2026 | Aşağıdaki menü ve kaynak dosyalarını içerir; katılımcı bilgisayarında prova ayrıca gerekir |
| rc5 kaynak commit'i | `d1df6f335ccf374826731c1d69026acaad11cd27` | Bu tablodaki kaynak incelemesinin temeli; `main` ile aynı kabul edilmez |

Aday dosya: [`qupath-extension-workshop-0.2.2-rc5.jar`](https://github.com/sbalci/qupath-extension-workshop/releases/download/v0.2.2-rc5/qupath-extension-workshop-0.2.2-rc5.jar), 3.226.968 bayt. GitHub yayın kaydının SHA-256 değeri:

```text
8717da4902e2d3c0b52726b8b4e8e20f56b772a53f176526944b99c7c0bc4624
```

Bu bilgi yayın kaydından okunmuştur; bu düzenleme sırasında JAR indirilmedi/çalıştırılmadı ve GUI provası yapılmadı. Kullanılacak QuPath ve eklenti sürümü oturumdan önce birlikte sabitlenmelidir. Temel rota Python/GPU/model kurulumu gerektirmez.

**Menü yoksa kaynak dosyası yedeği:** [rc5 `src/main/resources/scripts/`](https://github.com/sbalci/qupath-extension-workshop/tree/v0.2.2-rc5/src/main/resources/scripts) içindeki dosyayı önceden hazırlayın; [Automate → Script editor] ile açıp **Run** kullanın. Projenin `scripts/` klasörüne konmuşsa [Automate → Project scripts] altında da bulunabilir. Kod düzenlemek gerekmez. Aşağıdaki bütün menüler [Extensions → Atölye → Yardımcılar] altındadır.

| Alt menü → işlev | Dosya | Girdi / beklenen çıktı |
|---|---|---|
| Klinik ve kohort → Kohort üst veri sihirbazı | `yardimci-metadata-sihirbaz.groovy` | Proje → `cohort_metadata/` içinde CSV + `detailed_summary.txt` |
| Klinik ve kohort → WSI anonimleştirme sihirbazı | `yardimci-anonim-sihirbaz.groovy` | Proje + açık öğretim slaytı → seçilen klasörde kopyalar ve isteğe bağlı `anonymization_lookup_*.csv/.json` |
| Temel araçlar → Anotasyon yapısı QC (denetçi) | `yardimci-anotasyon-qc.groovy` | Açık slayttaki anotasyonlar → yapısal sorun raporu; biyolojik doğruluk veya otomatik düzeltme sağlamaz |
| İçe ve dışa aktarma / veri → Karo (tile) dışa aktarma | `yardimci-karo-disa-aktarma.groovy` | Açık kalibre slayt + anotasyon → varsayılan `exports/tiles/<slayt-slug>__<tarih>/`; maske modunda `images/`, `labels/`, `labels.txt` |
| Klinik ve kohort → İşlem kaydı (provenance JSON) | `yardimci-kosu-manifesti.groovy` | Proje verileri → `manifests/`; karo parametreleri ayrıca elle kaydedilir |

**rc5 ile uygulamada dikkat edilecekler:**

- Anonimleştirme yardımcısı kopya üretir; yeniden adlandırma tam anonimleştirme değildir. Python kapalı tutulduğunda meta veriye dokunmaz; açıkken sabit listeli `ImageDescription` redaksiyonu yapar, etiket/makroyu veya ana görüntüdeki yazıyı kaldırmaz. Hata halinde yalnız kopyaya düşebilir. Anahtar, proje yolları, envanter, provenance, GeoJSON ve türetilmiş karolar ayrıca incelenir.
- rc5 sonuç penceresi proje için kopyaların bulunduğu klasörü önerebilir. **Ayrı, boş bir klasörde proje oluşturun**, sonra kopyaları **Add images** ile ekleyin. Kalibrasyonu bu yeni projede yeniden kontrol edin.
- Önce hasta düzeyinde bölme planını hazırlayın. Aynı hastanın slaytları, seri kesitleri, yeniden taramaları ve karoları aynı bölmede kalmalıdır. Yardımcı hasta bölmesini otomatik yapmaz.
- Kalibrasyon yoksa karo yardımcısı µm/px alanını downsample sayar; fiziksel ölçekli çıktı için durup doğrulayın. Çekirdek deneme: seçili iki küçük ROI, 256 px, 1.0 µm/px, örtüşme 0, PNG. Görüş alanı 256 µm'dir; kaynak µm/px ayrıca bilinmelidir.
- **Form her açılışta sıfırlanır:** varsayılan 512 px ve “Tüm anotasyonlar”dır. RGB'den maske moduna geçerken de **Seçili anotasyon(lar) (2), 256 px, 1.0 µm/px, 0 px örtüşme, PNG** değerlerini yeniden girin. Yardımcı kısmi kenar karolarını açmaz (`includePartialTiles` varsayılan false); eksik kenar şeritleri atlanır, farklı boyutlar ayrıca incelenir.
- Anotasyonla kesişen dikdörtgen karolar boş cam veya poligon dışı piksel içerebilir. `Ignore*` otomatik dışlanmaz. Sınıf indeksleri kapsamdaki alfabetik sınıf listesine bağlıdır; her çıktıda `labels.txt` kontrol edilir. Kısmi anotasyondaki 0, kanıtlanmış negatif etiket değildir.
- Maske küçük tamsayılar nedeniyle siyah görünebilir. Bir RGB/maske çiftinin koordinat, boyut ve değerlerini kontrol etmeden eğitim için hazır saymayın. Karo ayarlarını ve QC sonucunu elle kayıt şablonuna yazın.

Bu sınırlamalar rc5 kaynağından incelenmiştir. Testler, betik çalıştırma, derleme, render ve GUI kontrolleri bu hazırlık düzenlemesinde **bekletilmiştir**; işlevsel doğrulama yapıldığı iddia edilmez.

## Requirements

- **QuPath 0.6.0** or newer (baseline targeted by this build; works up to 0.7.x)
- Normal installation uses the Java runtime bundled with the supported QuPath application; participants do not need a separate JDK installation.
- Building this extension from source requires **JDK 21**, as declared by the Java toolchain in `build.gradle.kts` on both `v0.2.2-rc5` and the reviewed `main` source. A Java 17 installation does not meet this source-build requirement.
- The data preparation route does not require StarDist, Cellpose, QuANTUM or external Python environments. Other modules have their own prerequisites.

## Build

```bash
./gradlew clean build
```

Output: `build/libs/qupath-extension-workshop-X.Y.Z.jar`

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



