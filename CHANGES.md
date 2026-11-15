# 15 Kasım 2026 — kalan betik pencerelerinin TR/EN metinleri

- 39 betik ve önceki üç yardımcının dokuz sabit rehber/seçici metni ortak dil kaynağına bağlandı. Katalog 102 betik/2.096 TR/EN metindir; hücre tespiti ayrıca 25 ortak anahtar kullanır. Her rapor/veri gövdesinin çevrildiği iddia edilmez.
- Komutlar, veri/tercih kimlikleri, sayısal varsayılanlar ve onay/silme koşulları korundu. Toplam 62 kullanım/61 benzersiz dinamik cümlede veri ifadeleri ve ertelenen metne çevirme davranışı denetlendi.
- Önceden commit edilmiş 11 kaynak farkı ayrı incelenip kaynak sözleşmesine köken kaydıyla işlendi (`8766c31`). ICC açıklaması resmî QuPath 0.7 OpenSlide kaynağıyla düzeltildi (`53c98af`), ardından pencere metni çevrildi; görüntü/ölçüm kodu değiştirilmedi.
- Temiz `6a245aa` derlemesinde 67 test geçti. QuPath 0.6.0/0.7.0'ın her birinde 103 betik derleme, 427.634 TR/EN lookup ve eklentisiz Türkçe fallback geçti. Aynalar/lint ve 328 dosyalık Türkçe terim kontrolü geçti.
- Eş zamanlı doku tespiti, hotspot, HoVer-NeXt ve Ki-67 düzenlemeleri korunup bu commit'lerden ayrı tutuldu. Test JAR'ı temiz commit kaynağına aittir. GUI native pipe hâlâ erişilemiyor (`os error 2`); bu bir Windows onay reddi değildir.

Yerel inceleme; push, sürüm veya dağıtım yapılmadı. Bekleyen terim kararı yoktur. Ayrıntı: [kalan betik doğrulaması](docs/remaining-script-ui-validation-2026-11-15.md).

---

# 15 Kasım 2026 — yardımcı pencereler ve sabit model kaynağı

- 48 yeni yardımcıda 818 görüntüleme ifadesi çevrildi: 809 sabit ifade ve 9 GString. Ortak katalog 63 betik/1.006 TR/EN metindir; veri/rapor gövdeleri tamamen çevrilmiş sayılmaz.
- Anotasyon aktarımı, F1 doğrulama, eşik, grafik, görüntü yakalama ve uzamsal araçlarda veri alanları, sayısal varsayılanlar ve onay/silme koşulları korundu. Dinamik veri ifadelerinin kaynakları ve değerlendirme sırası AST ile denetlendi.
- CytoFormer'in bekleyen iki ifadesi, kullanılan `3e9691e` revizyonunun resmî metadata'sıyla doğrulandı ve çevrildi. Güncel `main` kartı yerine sabit revizyon kontrol edildi; kullanıcı adına koşul kabul edilmedi.
- Eş zamanlı kaynak düzenlemeleri ayrı tutuldu. Ana ağacın genel ayna/lint kontrolü hata bildirdi; temiz commit kaynaklarıyla kurulan doğrulama kopyası kullanıldı. Hotspot betiğine dokunulmadı.
- Son temiz derleme: 67 test. QuPath 0.6.0/0.7.0'ın her birinde 103 betik parse, 126.806 dil lookup'u ve 45 dinamik cümle için sentetik kontroller geçti. Temiz kaynak kopyasında betik aynaları/lint de geçti.

Kaynak: `866a223` + `8ebd6e4` (eklenti). Yerel inceleme dalı; push, sürüm veya dağıtım yapılmadı. Bekleyen terim kararı yoktur. GUI köprüsü hâlâ erişilemiyor (`os error 2`); bu bir Windows onay reddi değildir. Ayrıntı: [yardımcı pencere doğrulaması](docs/utility-ui-validation-2026-11-15.md).

---

# 15 Kasım 2026 — üç model sihirbazının rehber metinleri

- Classpose, CytoFormer ve GrandQC'de 312 görüntüleme çağrısı çevrildi; ortak katalog 15 betik/565 TR/EN metindir. Özel rehber/durum yardımcıları ve dosya seçici başlıkları envantere eklendi.
- Komutlar, sınıf/model kimlikleri, kalıcı çıktılar, varsayılanlar ve onay/üzerine yazma kararları kaynak SHA eşliğiyle korundu. İngilizce yönergeler gerçek menü ve ortam girdisi adlarıyla eşlendi.
- `0c0cd92` temiz kaynak derlemesinde 67 test geçti. QuPath 0.6.0/0.7.0 içinde her hostta 103 betik parse ve 17.000 TR/EN lookup geçti. Önceki dinamik metin testleri de bu derlemede çalıştı.
- CytoFormer ağırlık lisansı başlığı ile “otomatik onay” paragrafı kaynak incelemesi bekliyor: güncel model kartı Penn lisansını gösteriyor, sabitlenmiş eski README okunamadı. Bu iki ifade yeni çeviriye alınmadı. Bekleyen terim kararı yoktur.

Yerel inceleme dalında çalışıldı; push, sürüm veya dağıtım yapılmadı. GUI/model kurulumu ve gerçek model başarımı doğrulanmadı. Ayrıntı: [model sihirbazları doğrulaması](docs/model-wizard-ui-validation-2026-11-15.md).

---

# 15 Kasım 2026 — dinamik pencere metinleri

Yerel inceleme dalı: `feat/course-audit-followup-20261114`; push, sürüm veya dağıtım yapılmadı.

- Dokuz betikte 188 ek görüntüleme ifadesi çevrildi: 151 sabit ifade ve 37 GString kullanımı. Katalog 12 betik/379 TR/EN metindir.
- Dinamik sayılar, yollar ve sınıflandırıcı adları korunur; tüm lam/seçili bölge etiketleri iki dilde açıktır. Değer kodu, değerlendirme sırası ve koşullar Groovy AST ile karşılaştırılır. Kaynak koruma SHA değerleri değişmedi.
- Temiz commit kaynağında 67 test geçti. QuPath 0.6/0.7 içinde her hostta 9.146 lookup, 36 benzersiz dinamik cümle için TR/EN ve eklentisiz sentetik kontroller, 103 betik parse ve 100 nesnelik ilk örnek geçti.
- Diske yazılan özetler, loglar, CSV/JSON biçimleri, sayısal varsayılanlar ve analiz callback'leri değiştirilmedi. Diğer eş zamanlı düzenlemeler commit dışında tutuldu.

Sınır: GUI köprüsü yine `os error 2` verdi; görsel GUI/kurulum/model yolları doğrulanmadı. Dosya özetleri, ortam kataloğu açıklamaları ve diğer yardımcılar bütünüyle çevrilmiş sayılmaz. Bekleyen terim kararı yoktur. Ayrıntı: [dinamik UI doğrulaması](docs/dynamic-ui-validation-2026-11-15.md).

---

# 14 Kasım 2026 — onaylı planın uygulama kaydı

Dal: `feat/course-audit-followup-20261114`. Yerel inceleme; push, tag, sürüm veya paket yayımlama yapılmadı. Sürüm numarası bu yüzden artırılmadı.

- README kurulum ve ilk örnek yolu iki dilde tamamlandı. 512×512, 100 nesneli CC0 sentetik PNG, oluşturucu, SHA manifesti ve ilk çalıştırma belgesi eklendi.
- Hücre tespiti penceresi 25 ortak TR/EN anahtarını kullanır; son hata ortak sonraki-adım rehberini gösterir. Sekiz ana betiğe ek dört yardımcının sabit metinleri de çevrildi: kalibrasyon, boya vektörü, üst veri ve Python ortam yöneticisi. Toplam 12 betikte 255 başlık/düğme/yardım/ilk hata metni tek katalogdan üretilir. Bağımsız Groovy çalıştırmada Türkçe fallback korunur.
- Python ve requirements aynalarının LF politikası düzeltildi. Ortak 102 terimlik sözlük SHA kontrolü, onaylanan *epoch* ve VUS tanımları eklendi. Dil tercihi dokümanı gerçek düğüm/anahtarla eşlendi.
- Tespit algoritmaları, parametre varsayılanları, CSV/GeoJSON alan adları ve public API bu dil çalışmasında değiştirilmedi.

- 12 betik için çeviri çağrıları çıkarıldığında önceki kaynağın SHA-256 eşliği doğrulanır. Sayısal biçim/yer tutucu ve gerçek AST anahtar–Türkçe fallback eşliği test edilir. Dil testleri artık kalıcı kullanıcı tercihini yazmadan çalışır. Ayrıntı: `docs/script-ui-localization.md` (eklenti deposu).

Doğrulama: Java 21/Gradle build; 64 test, sıfır hata/başarısız. Gerçek QuPath 0.6.0 ve 0.7.0 içinde paketli 103 betik parse edildi; sentetik ilk örnekte 100 kontur ve dolu ölçüm satırları doğrulandı. Her hostta 6.170 gerçek TR/EN lookup ve eklentisiz Türkçe fallback geçti; GUI açılmadı, tercih kaydedilmedi. Ana deponun script-sync ve lint kontrolleri geçti.

Terim kararı: Beş kalan ilk kullanım grubu da 14 Kasım 2026'da kaynak kontrolü koşuluyla onaylandı ve uygulandı. Terminal/Komut İstemi/konsol, klasör/çalışma klasörü, sanal ortam, tensör ve örnek–hasta ayrımı ortak sözlüğe taşındı; bekleyen terim yoktur. Kaynaklar ve uygulama kaydı workshop deposunun `docs/terminology-decisions-2026-11-14.md` dosyasındadır. Önceki klinik onay kapsamı genişletilmedi.

Doğrulanamayanlar: Windows GUI denetim bağlantısı yok (`os error 2`); kurulum/GUI iptal/üzerine-yazma/dil değiştirme elle görülmedi. Bazı yardımcı pencereler ve dinamik sonuçlar hâlâ Türkçe. Harici model/ortam indirmeleri ve tüm köprülerin gerçek model çalışması test edilmedi. Yerel ağaçtaki eş zamanlı diğer düzenlemeler korunup commit dışında bırakıldı; kod doğrulaması ayrı çalışma kopyasındaki commit kaynağına aittir.

---

## Önceki çalışma kaydı

# CHANGES — `qupath-extension-workshop`

Branch: `feat/bilingual-pedagogy-audit` (`main` untouched; not pushed or released).

## 1. What Changed

1. **Phase 1 Audit & Unified Glossary (`ee88f2e`):**
   - Added [`AUDIT.md`](AUDIT.md) and unified [`GLOSSARY.md`](GLOSSARY.md) (`OB-1..OB-14` approved).
2. **Bundled External Python Bridges (`4636e9c`) + Script Bug Fixes (`5b00e3a`, `37ff1a2`) + Guided Wizards (`7cf2c23`):**
   - Packaged all 15 external Python bridge folders (`31` files under `src/main/resources/python/`) into the extension JAR and added SHA-256-verified auto-extraction (`WorkshopResources.extractBundledPythonBridge`) to `~/.atolye/bridges/<version>/`.
   - Preserved and integrated the 5-tab guided tile exporter (`yardimci-karo-disa-aktarma.groovy`), read-only TIFF header metadata scanner (`yardimci-metadata-sihirbaz.groovy`), and GrandQC working-directory/home-button fixes (`yardimci-grandqc-sihirbaz.groovy`).
3. **Non-Destructive Safety Prompts, HistoPLUS Canonical Aliases & Redacted Error Dialog (`205053c`):**
   - Added explicit `Ekle / Değiştir` (`Add / Replace`) mode selection, overwrite confirmation dialogs before deleting existing detections/sentinel annotations, and subprocess `killTree(proc)` cleanup in `yardimci-classpose-sihirbaz.groovy`, `yardimci-hovernext-sihirbaz.groovy`, `yardimci-mitoz-fcos.groovy`, `yardimci-mitoz-retinanet.groovy`, `yardimci-mitoz-tiatoolbox.groovy`, `yardimci-histoart-sihirbaz.groovy`, `membran-boya.groovy`, and `yardimci-cellpose-sihirbaz.groovy`.
   - Added `CANONICAL_ALIASES` + `canonicalKeyOf` in `yardimci-histoplus-sihirbaz.groovy` so `cancer cell` / `Epithelial` / `Endothelial` checkpoint class names normalize deterministically.
   - Added privacy-redacting `WorkshopErrors.redactSensitiveText` + `formatCopyableReport` (`<HOME>` and `<SLIDE_FILE>` masking) and wired a copyable error dialog in `WorkshopExtension.java`.
   - Fixed the validation-error badge overwrite bug in `WorkshopSettingsDialog.java` and updated `.github/workflows/candidate-acceptance.yml`.
4. **Bilingual `TR` / `EN` UI, Zero-Code Documentation, Changelog & Regression Tests:**
   - Added `WorkshopI18n.java` + UTF-8 `strings_tr.properties` / `strings_en.properties` (`295` keys in 1:1 parity) with live JavaFX menu/dialog binding (`Extensions → Atölye → 🌐 Dil / Language` and `WorkshopSettingsDialog` selector) without requiring a QuPath restart.
   - Localized `WorkshopMicrocopy` (all 46 dual-audience `🩺 Klinik / Clinical` + `💻 Matematik / Computation` tooltips), `WorkshopErrors`, `WorkshopParameterValidation`, `WorkshopSettingsDialog`, and `WorkshopExtension` (`Environment check`, `About`, menus, and `OB-7` `"Sıralı yoğun odak seçici (top-N)"` / `"Ranked hotspot selector (top-N)"`).
   - Rewrote [`README.md`](README.md) with zero-code catalog & drag-and-drop instructions, accurate Java 21 build vs bundled runtime notes, 5-minute worked example, 103-script menu tree, and troubleshooting; updated [`src/main/resources/images/tour/README.md`](src/main/resources/images/tour/README.md); created [`CHANGELOG.md`](CHANGELOG.md).
   - Added JUnit tests in `SafetyRegressionTest.groovy` and `WorkshopI18nAndCatalogTest.groovy`.

## 2. Waiting for User Decision / Release Cut

- **Version bump & release publishing:** Per Phase 3 rules, `version = "0.2.2-rc8"` was kept in sync across `build.gradle.kts`, `WorkshopExtension.java`, `CITATION.cff`, and `catalog.json` without cutting or pushing a new tag. When ready to publish a release JAR containing these changes to GitHub Releases, Zenodo, and OSF, run `pwsh ./tools/bump-extension-version.ps1 -NewVersion 0.2.2-rc9 -NoPush` from `D:\qupath-workshop`, review, and push.
- **Individual Groovy wizard interiors:** The Java extension menu, settings window, parameter tooltips, validation messages, environment check, about dialog, and error dialogs now switch dynamically between Turkish and English (`atolye.language`). Individual Groovy wizard interiors remain in Turkish by design so single-file loose scripts in `handson/scripts/` stay self-contained; if desired in a future release, Groovy wizards can read `WorkshopI18n.isEnglish()` reflectively.

## 3. What Could Not Be Verified Automatically

- **Live QuPath 0.6.0+ interactive GUI click-through & GPU/gated model checkpoints:** All Java/Groovy compilation, resource packaging, script lint checks (`lint-groovy-scripts.ps1`), byte-identity sync (`check-script-sync.ps1`), and headless JUnit suites (`.\gradlew.bat clean build`) were verified automatically. Live interactive clicking inside a running QuPath 0.6.0 desktop window and runtime downloads of gated Hugging Face weights (e.g., Sanofi EFTD, HistoPLUS, SPIDER) require an interactive desktop session and user Hugging Face credentials.
