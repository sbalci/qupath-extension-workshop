<img src="assets/logo.svg" alt="Patologlar için QuPath Atölyesi" width="320">

# qupath-extension-workshop

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.20375397.svg)](https://doi.org/10.5281/zenodo.20375397)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Release](https://img.shields.io/github/v/release/sbalci/qupath-extension-workshop?include_prereleases)](https://github.com/sbalci/qupath-extension-workshop/releases)

**Türkçe Özet:** **Patologlar için QuPath** (Dijital Patoloji ve Yapay Zekâ) atölyesinin paketlenmiş Groovy iş akışlarını, 15 harici Python model köprüsünü, etkileşimli sihirbazlarını ve kalıcı ayarlarını QuPath içine **Türkçe / İngilizce** dil seçeneğiyle ekleyen QuPath eklentisidir.

**English Overview:** Bilingual (**Turkish default / English**) QuPath extension that bundles all hands-on Groovy workflows, 15 self-extracting Python model bridges, interactive wizards, and persistent parameters of the **QuPath Digital Pathology & AI Workshop** into one-click menus.

**Dil kapsamı / Language coverage:** Menü, ortak ayarlar ve paketteki 103 betiğin başlık, düğme, açıklama ve soru pencereleri TR/EN ortak kaynağını kullanır. Sonuç pencerelerindeki raporların, günlük satırlarının ve veriden türeyen bazı iletilerin bir bölümü hâlâ yalnız Türkçedir. Kök menü adı her iki dilde **Atölye**dir. / Menus, shared settings and the titles, buttons, guidance and question dialogs of all 103 bundled scripts use the shared TR/EN source. Part of the result reports, log lines and data-derived messages is still Turkish only. The root menu is named **Atölye** in both languages.

> Modeled after the official [QuPath extension template](https://github.com/qupath/qupath-extension-template).

---

## Sıfır Kodla Kurulum (Patologlar İçin) / Zero-Code Installation (For Pathologists)

### Yöntem A — Sürükle-bırak (önerilen; atölye salonu ve çevrimdışı kurulum) / Drag-and-drop (recommended)

Bu sıra ve düğme adları atölye sitesindeki kurulum sayfasıyla aynıdır. / The order and button names match the workshop site's installation page.

**Türkçe:**
1. **QuPath 0.6.0+** programını açın (ayrıca Java kurmanız veya **terminal (komut penceresi)** kullanmanız **gerekmez**; terminal komut yazıp Enter ile çalıştırdığınız penceredir).
2. [GitHub Releases](https://github.com/sbalci/qupath-extension-workshop/releases) sayfasından listenin **en üstündeki** `qupath-extension-workshop-<sürüm>.jar` dosyasını indirin. `-rcN` ile biten ön sürümler listenin en üstünde yer alır; sayfadaki "Latest" etiketi daha eski bir sürümü gösterebilir.
3. İndirdiğiniz `.jar` dosyasını fareyle tutup açık **QuPath ana penceresinin ortasına sürükleyip bırakın**.
4. QuPath ilk kez eklenti kuruyorsanız eklenti klasörünü sorar; önerilen klasörü onaylayın.
5. QuPath'i **tamamen kapatıp yeniden açın**. Üst menüde **Extensions → Atölye** ve **Extensions → Senkron Izgara** görünür.

**English:**
1. Open **QuPath 0.6.0+** (no separate Java installation or **terminal**, the window where you enter commands, is required).
2. From [GitHub Releases](https://github.com/sbalci/qupath-extension-workshop/releases) download the `qupath-extension-workshop-<version>.jar` at the **top** of the list. Pre-releases ending in `-rcN` are listed first; the page's "Latest" badge may point to an older version.
3. Drag the `.jar` onto the centre of QuPath's main window.
4. If QuPath asks for an extension folder, accept the suggested one.
5. **Quit QuPath completely and reopen it.** Look for **Extensions → Atölye** (the menu keeps this name in English too) and **Extensions → Senkron Izgara**. Switch the menu language at any time with **Extensions → Atölye → 🌐 Dil: Türkçe (English'e geç)**; no restart is needed.

For an offline room, copy the JAR and the [synthetic example](assets/examples/synthetic-nuclei.png) to the workshop folder beforehand.

### Yöntem B — Eklenti yöneticisi (yalnız Yöntem A çalışmazsa) / Extension manager (only if A fails)

Düğme adları QuPath 0.6.0'ın kendi arayüz metinlerinden alınmıştır. / Button names are taken from QuPath 0.6.0's own interface strings.

**Türkçe:**
1. **Extensions → Manage extensions** penceresini açın ve **Manage extension catalogs** düğmesine tıklayın.
2. **Catalog URL** kutusuna şu adresi olduğu gibi yapıştırın ve **Add** düğmesine tıklayın:
   `https://raw.githubusercontent.com/sbalci/qupath-extension-workshop/main/catalog.json`
3. **Manage extensions** penceresine dönün. **`QuPath Workshop extension (Atölye)`** satırındaki kurma düğmesine (**+**; üzerine gelince "Install extension" yazar) tıklayın.
4. Açılan pencerede **Version to install** listesinden en üstteki sürümü seçin ve **Install** düğmesine tıklayın.
5. QuPath'i **tamamen kapatıp yeniden açın**.

**English:**
1. Open **Extensions → Manage extensions** and click **Manage extension catalogs**.
2. Paste this address into **Catalog URL** exactly as written and click **Add**:
   `https://raw.githubusercontent.com/sbalci/qupath-extension-workshop/main/catalog.json`
3. Go back to **Manage extensions**. On the **`QuPath Workshop extension (Atölye)`** row click the install button (**+**; its tooltip reads "Install extension").
4. In the window that opens, choose the top entry under **Version to install** and click **Install**.
5. **Quit QuPath completely and reopen it.**

**Kurulumu doğrulayın / Check the installation:** **Extensions → Manage extensions** penceresinde Atölye eklentisinin sürümü, indirdiğiniz dosya adındaki sürümle aynı olmalıdır. / The version shown for the Atölye extension must match the version in the file name you downloaded.

---

## 5 Dakikada İlk Çalıştırma Örneği / 5-Minute Worked Example

**Başlangıç / Start here:** [100 daireli sentetik örnek: TR/EN adımlar, beklenen çıktı ve sorun giderme](docs/first-run.md). Bu küçük PNG örneği internet, model veya hasta görüntüsü gerektirmez. / This small PNG example needs no internet, model or patient image after download.

**İsteğe bağlı gerçek İHK örneği / Optional IHC sample:** aşağıdaki OSF yolu, sentetik örnekten sonra kullanılabilir. / Use the OSF route below after the synthetic first run.

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
│   │   ├── Tümör/Stroma ▸ (Tümör/Stroma sihirbazı · Adım 1 — Model eğit · Adım 2 — Alan ölçümü)
│   │   ├── Tümör içi Ki-67
│   │   ├── QuANTUM cTCF
│   │   ├── Mitoz tespiti ▸ (Merkez · KongNet · MIDOG25 FCOS · DA-RetinaNet · EffNetV2 · Sanofi EFTD (kapılı model: erişim izni gerekir) · Karşılaştır)
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

**Yeniden çalıştırma / Re-running a tool:** Bir araç aynı lamda daha önce sonuç üretmişse önce sorar. **Ekle** (varsayılan) hiçbir şeyi silmez ve yeni sonucu `… (2)` adıyla ekler; **Değiştir** ikinci bir onay ister; **İptal** hiçbir şey çalıştırmaz. Penceresiz (headless) çalıştırmada önceki sonuç varsa betik durur ve `mevcut=ekle` ya da `mevcut=degistir` bağımsız değişkenini ister. / If a tool already produced a result on the slide it asks first. **Add** (default) deletes nothing and names the new result `… (2)`; **Replace** needs a second confirmation; **Cancel** runs nothing. In a headless run with an earlier result the script stops and asks for `mevcut=ekle` or `mevcut=degistir`.

**Sanofi EFTD:** Model omurgası Hugging Face'te kapılıdır; erişim otomatik verilmez. Sihirbaz erişim yoksa durur ve adımları gösterir. / The model backbone is gated on Hugging Face and access is not automatic; the wizard stops and shows the steps when access is missing.

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
| Beklenmeyen betik hatası / Script exception | Hata penceresindeki **Tanı özetini kopyala** (*Copy diagnostic summary*) düğmesi ev dizini yolunu ve lam dosya adını (`<HOME>`, `<SLIDE_FILE>`) maskeler, ancak başka dosya, klasör veya proje adları özette kalabilir. Paylaşmadan önce özeti okuyun ve hasta bilgisi içeren her şeyi çıkarın. / The button masks the home-folder path and the slide file name, but other file, folder or project names may remain. Read the summary and remove anything that identifies a patient before sharing it. |

---

## Atıf ve Lisans / Citation & License

- **DOI:** [`10.5281/zenodo.20375397`](https://doi.org/10.5281/zenodo.20375397) (`CITATION.cff`)
- **Lisans / License:** Apache-2.0 (`CHANGELOG.md` ve `CHANGES.md` dosyalarına bakınız).
- **Sorumluluk Reddi / Disclaimer:** ⚠️ Yalnızca **araştırma ve eğitim** amaçlıdır; klinik tanı koymaya yönelik tıbbi cihaz değildir (*For research and educational use only; not a diagnostic medical device*).
