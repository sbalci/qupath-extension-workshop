<img src="assets/logo.svg" alt="Patologlar için QuPath Atölyesi" width="320">

# qupath-extension-workshop

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.20375397.svg)](https://doi.org/10.5281/zenodo.20375397)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Release](https://img.shields.io/github/v/release/sbalci/qupath-extension-workshop?include_prereleases)](https://github.com/sbalci/qupath-extension-workshop/releases)

**Türkçe Özet:** **Patologlar için QuPath** (Dijital Patoloji ve Yapay Zekâ) atölyesinin paketlenmiş Groovy iş akışlarını, 15 harici Python model köprüsünü, etkileşimli sihirbazlarını ve kalıcı ayarlarını QuPath içine **Türkçe / İngilizce** dil seçeneğiyle ekleyen QuPath eklentisidir.

**English Overview:** Bilingual (**Turkish default / English**) QuPath extension that bundles all hands-on Groovy workflows, 15 self-extracting Python model bridges, interactive wizards, and persistent parameters of the **QuPath Digital Pathology & AI Workshop** into one-click menus.

> Modeled after the official [QuPath extension template](https://github.com/qupath/qupath-extension-template).

---

## Sıfır Kodla Kurulum (Patologlar İçin) / Zero-Code Installation (For Pathologists)

### Yöntem A — QuPath Eklenti Yöneticisi (Önerilen / Recommended)

**Türkçe:**
1. **QuPath 0.6.0+** programını açın (ayrıca Java veya terminal kurmanız **gerekmez**).
2. Üst menüden **Extensions → Manage extensions** penceresini açın.
3. **Add catalog** (Katalog ekle) düğmesine tıklayın ve şu adresi yapıştırın:
   `https://raw.githubusercontent.com/sbalci/qupath-extension-workshop/main/catalog.json`
4. Listede **`QuPath Workshop extension (Atölye)`** satırını bulun ve sağındaki **`+`** (Kur) düğmesine tıklayın. *(Sürüm kutusunda en üstteki `v0.2.2-rc8` veya güncel sürüm seçili gelir.)*
5. QuPath'i kapatıp yeniden açın. Üst menüde **Extensions → Atölye** ve **Extensions → Senkron Izgara** görünür.

**English:**
1. Open **QuPath 0.6.0+** (no separate Java or terminal installation is required).
2. Open **Extensions → Manage extensions**.
3. Click **Add catalog** and paste:
   `https://raw.githubusercontent.com/sbalci/qupath-extension-workshop/main/catalog.json`
4. Locate **`QuPath Workshop extension (Atölye)`** in the catalog list and click **`+`** (Install).
5. Restart QuPath, then switch the menu language anytime via **Extensions → Atölye → 🌐 Dil: Türkçe (English'e geç)** (no restart needed).

### Yöntem B — Sürükle-Bırak (Atölye Salonu / Çevrimdışı Kurulum — Drag-and-Drop)

1. [GitHub Releases](https://github.com/sbalci/qupath-extension-workshop/releases) sayfasından en güncel `qupath-extension-workshop-0.2.2-rc8.jar` dosyasını indirin *(not: `-rcN` ön-yayın sürümleri "Releases" listesinin en üstünde yer alır)*.
2. İndirdiğiniz `.jar` dosyasını fareyle tutup açık **QuPath ana penceresinin ortasına sürükleyip bırakın**.
3. QuPath ilk kez eklenti kuruyorsanız kullanıcı eklenti klasörü seçmenizi ister → varsayılan klasörü onaylayın.
4. QuPath'i tamamen kapatıp yeniden açın.

---

## 5 Dakikada İlk Çalıştırma Örneği / 5-Minute Worked Example

1. **Arayüz turu (lam açmadan):** **Extensions → Atölye → Modüller → Arayüz turu** (*Interface tour*) öğesine tıklayarak QuPath panellerini etkileşimli olarak gezin.
2. **Örnek sanal lamı açın:** [OSF örnek veri paketinden](https://osf.io/8749g/) indirdiğiniz örnek Ki-67 İHK kesitini QuPath penceresine sürükleyin ve görüntü tipini **`Brightfield (H-DAB)`** olarak onaylayın.
3. **Kapalı bir odak alanı çizin:** Üst araç çubuğundan **Rectangle** veya **Polygon** aracını seçip tümör üzerinde ~1 mm² büyüklüğünde bir alan çizin (kenarı **sarı** seçili kalsın).
4. **Tek tıkla Ki-67 sayımı:** **Extensions → Atölye → Modüller → Nükleer boya (Ki-67)** öğesine tıklayın. Sonuç penceresinde toplam hücre sayısı, 1+/2+/3+ dağılımı, Ki-67 indeksi (%) ve H-score özetini inceleyin. *(Mevcut tespitlerin üzerine yeniden çalıştırırsanız veri kaybını önleyen **Ekle / Değiştir** onay kutusu açılır.)*

---

## Menü Yapısı / Menu Tree

Kurulumdan sonra **Extensions → Atölye** ve üst düzey **Extensions → Senkron Izgara** kısayolu eklenir (depoda ve JAR içinde bayt-özdeş 103 Groovy betiği + 15 gömülü Python köprüsü):

```text
Extensions
├── Atölye
│   ├── — Atölye betikleri —  (lam açık değilse: ⚠ Önce bir lam açın)
│   ├── ─────────────────────
│   ├── Modüller / Modules
│   │   ├── Arayüz turu (Interface tour)
│   │   ├── Doku tespiti ▸ (Yerleşik Otsu · Piksel eşikleyici · GrandQC · TIA Toolbox)
│   │   ├── Hücre tespiti (Cell detection)
│   │   ├── Nükleer boya (Ki-67)
│   │   ├── ER / PR H-score
│   │   ├── Membran boya (HER2)
│   │   ├── Sitoplazmik boya (CD68)
│   │   ├── Tümör/Stroma sihirbazı
│   │   ├── Tümör içi Ki-67
│   │   ├── QuANTUM cTCF
│   │   ├── Mitoz tespiti ▸ (Merkez · KongNet · MIDOG25 FCOS · DA-RetinaNet · EffNetV2 · Sanofi EFTD · Karşılaştır)
│   │   ├── Görüntü Hizalama ▸ (VALIS · Warpy · Afin)
│   │   └── Veri dışa aktarma (TSV / CSV / GeoJSON)
│   ├── Yardımcılar / Utilities  (9 konu grubu / 9 topic groups)
│   │   ├── Temel araçlar ▸ (6 araç)
│   │   ├── Boya ve renk ▸ (4 araç)
│   │   ├── Hücre ve çekirdek tespiti ▸ (11 araç — StarDist, Cellpose, InstanSeg, HoVer-NeXt, Classpose, CytoFormer, HistoPLUS, NuClick, Hepatosit, KongNet, F1/IoU)
│   │   ├── Skorlama ve ölçüm ▸ (13 araç — Göz kararı kalibrasyon, Stromal TİL, Sıralı yoğun odak seçici vb.)
│   │   ├── Uzamsal analiz ▸ (9 araç — Delaunay, NN, Peritümöral bant, Eşit alanlı merkez/çevre vb.)
│   │   ├── İçe ve dışa aktarma / veri ▸ (14 araç — 5 sekmeli Karo dışa aktarma, OME-Zarr, Toplu GeoJSON/XML vb.)
│   │   ├── Python köprüleri ve temel modeller ▸ (8 araç — Python ortam yöneticisi, TIA Toolbox, Kaiko, SPIDER, HistoART, FM hazırlık, MCP/ajan denetimi)
│   │   ├── Klinik ve kohort ▸ (6 araç — Salt okunur TIFF üst veri sihirbazı, WSI anonimleştirme, Sectra PACS, Provenance JSON vb.)
│   │   └── Eğitim, sunum ve ImageJ ▸ (6 araç)
│   ├── ─────────────────────
│   ├── Atölye Ayarları… / Workshop Settings…
│   ├── 🌐 Dil: Türkçe (English'e geç) / Language: English (Türkçe'ye geç)
│   ├── Ortam kontrolü… / Environment check…
│   └── Atölye hakkında… / About Workshop…
└── Senkron Izgara / Synchronized Grid
```

---

## Gereksinimler ve Çevrimdışı Notlar / Requirements & Offline Workshop Notes

- **Kullanıcılar için / For end users:** **QuPath 0.6.0+** (kendi Java 21 çalışma zamanıyla birlikte gelir; ayrı kurulum gerekmez). Çekirdek modüller internet bağlantısı olmadan tamamen çevrimdışı çalışır; `Membran boya (HER2)` ve `QuANTUM cTCF` modülleri Cellpose/StarDist kurulu değilse otomatik olarak yerleşik **Watershed** algoritmasına yedeklenir.
- **Harici Python köprüleri / Bundled Python bridges:** GrandQC, Classpose, HoVer-NeXt, HistoPLUS, CytoFormer, NuClick, HistoART, Kaiko, SPIDER ve mitoz köprülerinin `.py` betikleri JAR içinden otomatik olarak `~/.atolye/bridges/<sürüm>/` klasörüne çıkarılır (ana atölye deposunu klonlamanız gerekmez). Sanal ortamlar ve model ağırlıkları çevrimdışı bir atölyeden önce **Atölye Python ortam yöneticisi** ile bir kez önceden indirilebilir.
- **Geliştiriciler için derleme / Building from source:** **JDK 21** gereklidir (`build.gradle.kts` Java 21 toolchain kullanır):
  ```powershell
  .\gradlew.bat clean build   # Windows
  ./gradlew clean build       # macOS / Linux
  ```
  Çıktı: `build/libs/qupath-extension-workshop-0.2.2-rc8.jar`

---

## Takıldıysanız (Sorun Giderme) / Troubleshooting

| Belirti / Symptom | Çözüm / Action |
|---|---|
| Menü öğeleri gri (tıklanamıyor) / Menu items greyed out | Önce bir sanal lam açın (`File → Open`). Lam açık olduğu hâlde tüm öğeler griyse `Edit → Preferences → Atölye` altındaki eğitmen kilidini (`Atölye menüsü etkin`) işaretleyin. |
| "Geçerli bir kapalı alan (ROI) seçili değil" / No closed ROI selected | Nokta veya çizgi yerine **Rectangle / Polygon / Brush** aracıyla kapalı bir alan çizin ve kenarı **sarı** olana kadar içine tıklayın. |
| Güncelleme sonrası "Eklenti yeniden yüklenmeli" hatası | `.jar` dosyası QuPath açıkken değiştirilmiştir; QuPath'i tamamen kapatıp yeniden başlatın. |
| Beklenmeyen betik hatası / Script exception | Hata penceresindeki **Tanı özetini kopyala** (*Copy diagnostic summary*) düğmesi ev dizini ve lam dosya adını (`<HOME>`, `<SLIDE_FILE>`) otomatik maskeler; bu özeti paylaşabilirsiniz. |

---

## Atıf ve Lisans / Citation & License

- **DOI:** [`10.5281/zenodo.20375397`](https://doi.org/10.5281/zenodo.20375397) (`CITATION.cff`)
- **Lisans / License:** Apache-2.0 (`CHANGELOG.md` ve `CHANGES.md` dosyalarına bakınız).
- **Sorumluluk Reddi / Disclaimer:** ⚠️ Yalnızca **araştırma ve eğitim** amaçlıdır; klinik tanı koymaya yönelik tıbbi cihaz değildir (*For research and educational use only; not a diagnostic medical device*).
