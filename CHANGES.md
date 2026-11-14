# 14 Kasım 2026 — onaylı planın uygulama kaydı

Dal: `feat/course-audit-followup-20261114`. Yerel inceleme; push, tag, sürüm veya paket yayımlama yapılmadı. Sürüm numarası bu yüzden artırılmadı.

- README kurulum ve ilk örnek yolu iki dilde tamamlandı. 512×512, 100 nesneli CC0 sentetik PNG, oluşturucu, SHA manifesti ve ilk çalıştırma belgesi eklendi.
- Hücre tespiti penceresi 25 ortak TR/EN anahtarını kullanır; son hata ortak sonraki-adım rehberini gösterir. Sekiz ek ana betikte 143 başlık/düğme/yardım/ilk hata iletisi tek katalogdan üretilir. Bağımsız Groovy çalıştırmada Türkçe fallback korunur.
- Python ve requirements aynalarının LF politikası düzeltildi. Ortak 97 terimlik sözlük SHA kontrolü, onaylanan *epoch* ve VUS tanımları eklendi. Dil tercihi dokümanı gerçek düğüm/anahtarla eşlendi.
- Tespit algoritmaları, parametre varsayılanları, CSV/GeoJSON alan adları ve public API bu dil çalışmasında değiştirilmedi.

Doğrulama: Java 21/Gradle build; 63 test, sıfır hata/başarısız. Gerçek QuPath 0.6.0 ve 0.7.0 içinde paketli 103 betik parse edildi; sentetik ilk örnekte 100 kontur ve dolu ölçüm satırları doğrulandı. Her hostta 2.338 gerçek TR/EN lookup ve eklentisiz Türkçe fallback geçti; GUI açılmadı, tercih kaydedilmedi. Ana deponun script-sync ve lint kontrolleri geçti.

Karar bekleyenler: ortak sözlükte beş terim grubu (workshop deposundaki somut taslak). Yeni public API veya analiz davranışı önerilmedi.

Doğrulanamayanlar: Windows GUI denetim bağlantısı yok (`os error 2`); kurulum/GUI iptal/üzerine-yazma/dil değiştirme elle görülmedi. Bazı yardımcı pencereler ve dinamik sonuçlar hâlâ Türkçe. Harici model/ortam indirmeleri ve tüm köprülerin gerçek model çalışması test edilmedi. Yerel ağaçtaki eş zamanlı diğer düzenlemeler korunup commit dışında bırakıldı; build sonucu o andaki ağaca aittir.

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
