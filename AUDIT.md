# QuPath Workshop uzantısı denetimi — 15 Kasım 2026 (Aşama 1, yenilenmiş)

**Salt okunur denetim. Bu dosyadan başka hiçbir şey değiştirilmedi; commit, etiket, push, yayın yapılmadı. Düzeltme planı bu raporda yok (Aşama 2).**

- İncelenen commit: `ceb2935fe4230178ca51bcb6775022b6920a7fb8` (`main` = `origin/main`; `git describe`: `v0.2.2-rc8-40-gceb2935`). Başlangıçta ve bitişte çalışma ağacı temizdi (`git status --porcelain` boş; tek değişiklik bu dosya).
- Önceki rapor: 14 Kasım 2026, commit `5a3715f`. Onun bulguları aşağıda tek tek yeniden sınandı.
- Yöntem etiketleri: **[ÇALIŞTIRILDI]** komutu ben çalıştırdım ve çıktıyı gördüm; **[KAYNAK]** kaynağı okudum, çalıştırmadım; **[ÇIKARIM]** kaynaktan ve başka kanıttan sonuç çıkardım; **[HEURİSTİK]** düzenli ifade/grep taraması, kesin sayım değildir.

## 1. Yöntem ve sınırlar

- `build/` ve `.gradle/` `.gitignore`'da (`git check-ignore -v` ile doğrulandı); derleme yalnız oraya yazdı. QuPath konsol komutları için kullanıcı dizini `-Duser.home` ile boş bir geçici klasöre yönlendirildi; yine de QuPath, gerçek `C:\Users\patolojiai\QuPath\v0.6\extensions` altındaki üçüncü taraf eklentileri okudu (yalnız okuma; QuPath Java tercihleri Windows kayıt defterinde tutulduğu için tamamen yalıtılamadı). Konsol testleri dil tercihini kalıcı yazmadığını iddia ediyor; kayıt defterini önce/sonra karşılaştırmadım.
- Gerçek QuPath **arayüzü** hiç açılmadı. Menü, pencere, sürükle-bırak kurulumu, katalogdan kurulum, dil geçişi, gerçek Python/model çalışması bu denetimde **görülmedi**.
- Bütün 103 script okunmadı. 30 script için hata yolları grep ile tarandı, 25 kadarının ilgili bölümleri elle okundu; ayrıca `WorkshopExtension.java`, `WorkshopErrors.java`, `WorkshopI18n.java` tam okundu, `WorkshopResources.java` kısmen.
- Ağ: maven.scijava.org'a erişildi (derleme geçti). `gh` ve `curl` salt okunur kullanıldı.
- Bu denetimde bulunan yeni bulgular `Y1…` ile numaralandı; eski bulgular `E1…`, `K1…`, `G1…` olarak korundu.

## 2. Derleme, test ve kontrol sonuçları

| Kontrol | Sonuç | Etiket |
|---|---|---|
| `./gradlew --no-daemon clean build` (Java 21, Gradle 8.10.2) | **BUILD SUCCESSFUL** 1 dk 27 sn; uyarı/deprecation satırı yok. Çıktı `build/libs/qupath-extension-workshop-0.2.2-rc8.jar` (4.066.363 bayt; yayımlanmış rc8 JAR'ı 3.503.941 bayt) | ÇALIŞTIRILDI |
| JUnit testleri | **67 test, 0 hata/başarısız/atlanmış**: Anonimleştirme 39, Safety 12, ParameterValidation 6, I18nAndCatalog 4, DynamicScriptLanguage 3, CoreScriptLanguage 2, CellScriptLanguage 1 | ÇALIŞTIRILDI |
| `tools/check-script-sync.ps1` | Geçti (103 Groovy + 31 Python köprü dosyası bayt eşit) | ÇALIŞTIRILDI |
| `tools/check-version-sync.ps1` | Geçti: her yerde `0.2.2-rc8`, QuPath tabanı `0.6.0` | ÇALIŞTIRILDI |
| `tools/lint-groovy-scripts.ps1` | Geçti (103 dosya, tercih varsayılanı kayması yok) | ÇALIŞTIRILDI |
| `check-turkish-terms.ps1`, `check-large-files.ps1` | Geçti (328 dosya; 2260 izlenen dosya < 95 MB) | ÇALIŞTIRILDI |
| `tools/check-glossary-sync.py`, `build-script-strings.py --check`, `check-script-ui-contract.py` | Geçti: sözlük özeti `e8b7de3bb478` (dört kopyada aynı); 102 script / 2.096 TR-EN metin | ÇALIŞTIRILDI |
| `tools/verify-candidate.py` | Windows'ta **UnicodeDecodeError** (cp1252, `read_text()` kodlamasız); `PYTHONUTF8=1` ile geçti (Y14) | ÇALIŞTIRILDI |
| Parent `tools/test-nuclick-naming.groovy`, `test-histoplus-naming.groovy`, `test-cytoformer-overwrite.groovy` | QuPath 0.6.0 ve 0.7.0 konsolunda ikisi de geçti (HistoPLUS 16, CytoFormer **19** doğrulama — CLAUDE.md 18 diyor; NuClick sayısı çıktıda görünmedi) | ÇALIŞTIRILDI |
| `check-packaged-script-syntax.groovy` (0.6.0 + 0.7.0) | 103 script derlendi, hiçbiri çalıştırılmadı | ÇALIŞTIRILDI |
| `test-script-ui-bundles.groovy` | İki sürümde **427.634 TR/EN arama** + eklentisiz Türkçe geri dönüş geçti (CHANGES.md ile aynı sayı) | ÇALIŞTIRILDI |
| `test-script-ui-expressions.groovy` | İki sürümde geçti (61 ifade, 244 çizim, 12 kapsam kontrolü) | ÇALIŞTIRILDI |
| `test-synthetic-first-run.groovy` | İki sürümde geçti: 100 ayrı nesne, dolu ölçüm satırları. Kalibrasyon API ile verildi, Kalibrasyon penceresiyle değil | ÇALIŞTIRILDI |
| `tools/test-annotation-roundtrip.ps1 -Stage all` (parent) | QuPath 0.6.0 + 0.7.0: dışa aktarım, GeoJSON/ImageScope/ASAP geri alma, belirsiz eşleşme, `mod=tumu` — **hepsi geçti** | ÇALIŞTIRILDI |
| `tools/check-release-sync.ps1 -Tag v0.2.2-rc8` | GitHub (ön sürüm), Zenodo, OSF'te rc8 var. **Uyarı:** GitHub "Latest" = `v0.2.1`, katalog/derleme = `v0.2.2-rc8` | ÇALIŞTIRILDI |
| `tools/integration/host-checks.groovy` | Bağımsız çalışmaz (aday script'in sonuna eklenen parça; `AtomicBoolean` çözülemedi). Hata değil, tasarım; atlandı | ÇALIŞTIRILDI |
| GUI tıklama, katalogdan kurulum, model indirme | **YAPILMADI** | — |

## 3. Önceki bulguların durumu

| Kod | Eski bulgu (kısaca) | Durum | Kanıt |
|---|---|---|---|
| E1 | EN menü, EN pencere demek değil; ana script'ler Türkçe | **Kısmen düzeldi** | 102 script için 2.096 çiftli metin kataloğu var (`script-core.json`), başlık/düğme/yardım çevrildi. Ama ~841 arayüz satırında hâlâ yalnız-Türkçe sabit metin var, hata yolları dahil: `hucre-tespiti` 15, `membran-boya` 21, `yardimci-valis-sihirbaz` 48 satır [HEURİSTİK, `scan_tr.py`]. Seçim kutusu seçenekleri ("Ekle — hiçbir şey silme", "Eski karoları bırak…") Türkçe sabit: `yardimci-maske-iceaktar.groovy:321-322`, `yardimci-karo-disa-aktarma.groovy:1208-1209` [KAYNAK]. Bkz. Y8 |
| E2 | İlk örnek EN okur için eksik; örnek dosya belirsiz | **Kısmen düzeldi** | `docs/first-run.md` TR+EN, `assets/examples/synthetic-nuclei.png` (512×512, SHA-256 manifestle eşleşiyor, 100 nesne iki sürümde yeniden üretildi) [ÇALIŞTIRILDI]. Hâlâ açık: gerçek İHK örneği için dosya adı/boyut/sağlama toplamı yok (`README.md:57`); EN adımlar var olmayan menü adı veriyor (Y6) |
| E3 | Bazı catch blokları ortak rehberi atlıyor | **Kısmen düzeldi** | `hucre-tespiti.groovy:268-274` artık `WorkshopErrors.guidance` çağırıyor ama hâlâ sınıf adı + ham ileti gösteriyor. Betikler arasında `WorkshopErrors` kullanan **yalnız 1** script; öteki yollar kendi metnini kullanıyor (çoğu "Yapabilecekleriniz" adımlı). Rehberin kendisi yanlış eşleşiyor (Y5) |
| E4 | Python aynaları CRLF; kontrol hata sonrası başarı yazıyor | **Kapandı** | Her iki `.gitattributes` `*.py text eol=lf`, `**/requirements*.txt eol=lf`; 31 bridge dosyası indeks ve çalışma ağacında LF (`git ls-files --eol`) [ÇALIŞTIRILDI]; `check-script-sync.ps1` sonunda hata varsa `throw`, başarı mesajı yalnız `-not $failed` sonrası [KAYNAK]. (`handson/python/qubalab/*.ipynb` iki dosya CRLF; köprü değil, eşitlik kontrolünde yok.) |
| E5 | Testler 103 yolun onayı değil; kapsam matrisi yok | **Hâlâ açık (azaldı)** | Kapsam matrisi bu raporda (bölüm 9); depoda yok. Gidiş-dönüş testi artık çalıştı (dışa/içe aktarım). Gerçek model, GUI, macOS/Linux hâlâ yok |
| E6 | Tercih anahtarı belgesi ≠ kod | **Büyük ölçüde kapandı** | `CHANGELOG.md:9` artık `language` / `/qupath/atolye/i18n` diyor, `WorkshopI18n.java:32-33` ile eşleşiyor. Artık yanlış kalan: `CHANGES.md:95` hâlâ `atolye.language` ve "Groovy sihirbaz iç ekranları tasarım gereği Türkçe" yazıyor (sonraki işlerle çelişiyor) |
| K1 | Eksik dil sınıfı | Kapandı | `WorkshopI18n.java` var, 2.416 anahtar TR=EN eşleşiyor [ÇALIŞTIRILDI `en_scan.py`] |
| K2 | Paketlenmemiş köprüler | Kapandı | JAR'da 103 script + 47 `python/` girişi (31 dosya); `WorkshopResources.extractBundledPythonBridge` içerik karşılaştırmalı çıkarıyor [ÇALIŞTIRILDI + KAYNAK] |
| K3 | CI 49 teste sabit | Kapandı | `candidate-acceptance.yml:79` `>= 55`; bugün 67 test |
| K4 | CHANGELOG yok | **Kısmen** | Dosya var ama eksik (Y11) |
| K5 | Sözlük yok / tek kitleli | Kapandı | `GLOSSARY.md` dört depoda aynı; eski rapordaki "su분" artığı kaldırılmış |
| G1 | Terminal/dizin/sanal ortam/tensör/epoch/VUS tanımları yok | Kapandı | `GLOSSARY.md:59,165,166,177` |
| G2 | VUS/sürücü/yolcu ayrımı klinik inceleme gerektirir | **Hâlâ açık** | Sözlükte "sürücü/yolcu" geçmiyor (`grep` boş). `GLOSSARY.md` "bekleyen terim yoktur" diyor; bu terimin ele alındığına dair kayıt görmedim |
| G3 | `CLAUDE.md` kapsam notları OB tablosuyla eşlenmeli | Doğrulanamadı | Parent `CLAUDE.md` bu denetimde değişmemiş; eşleme yapıldı mı bilmiyorum |

## 4. Açık bulgular (katılımcıya etkisine göre)

Kitle: **A** = kod bilmeyen patolog, **B** = patoloji bilmeyen bilgisayar bilimci.

### P1

**Y1 — Aynı sürüm numarası, iki farklı yapı; belgeler katalogda bulunmayan yapıyı anlatıyor.** (A+B)
- Katalogdaki ve GitHub'daki `v0.2.2-rc8` (etiket `59f1eeb`, yayın 13 Kasım 2026) ile HEAD (`build.gradle.kts:27`, `WorkshopExtension.java:768` hâlâ `0.2.2-rc8`; 40 commit sonrası) **farklı ürün**: 103 script'in **103'ü farklı** (`git diff --name-only v0.2.2-rc8 HEAD -- src/main/resources/scripts` = 103), JAR 3,5 MB vs 4,07 MB. Yayımlanmış rc8'de `WorkshopI18n`, `strings_*.properties`, gömülü Python köprüleri, `docs/first-run.md` ve dil geçişi **yok**; testler 3 dosya. [ÇALIŞTIRILDI `git ls-tree -r v0.2.2-rc8`, `git diff`, `gh release list`, `check-release-sync`]
- `origin/main` = HEAD olduğundan GitHub'daki `README.md` bugünkü kaynağı anlatıyor: "Extensions → Atölye → 🌐 Dil…" (README:37), ilk örnek, TR/EN pencereler. `catalog.json` en üstte `v0.2.2-rc8` → katılımcı yayımlanmış eski yapıyı kuruyor. Site sayfaları da "sürüm **0.2.2-rc8**" doğrulatıyor (`hazirlik/yazilim-kurulumu.qmd:224`); iki yapı aynı sürümü bildirdiği için bu kontrol ayırt edemez. GitHub "Latest" ise `v0.2.1` (eski).
- Belge çelişkisi: `docs/rc8-unpublished-candidate.md:1` ve `CITATION.cff:24` rc8'i "yayımlanmamış" diyor; oysa GitHub/Zenodo/OSF'te var. Release iş akışı (`release.yml`) yalnız kabul edilmiş sabit baytları terfi ettiriyor; HEAD kaynağı rc8 adıyla yayımlanamaz [KAYNAK `docs/review-branch-merge-2026-11-15.md`, `release.yml:51-81`].

**Y2 — Katılımcının kurabildiği yayımlanmış rc8, kullanıcıya sormadan siliyor; HEAD'de düzelen yerler rc8'de yok.** (A+B)
- rc8 etiketinde `MODE_REPLACE`/onay yok: Classpose, HoVer-NeXt, FCOS, RetinaNet, TIA Toolbox mitoz, HistoART, Cellpose sihirbazı (`MODE_REPLACE`/`showConfirmDialog` eşleşmesi rc8'de 0; HEAD'de her birinde ≥ 1) [ÇALIŞTIRILDI `git show v0.2.2-rc8:… | grep`]. Sahibin kalıcı kuralıyla (içe aktarma/tahmin sihirbazları önce sorar, varsayılan hiçbir şey silmez) çelişir. Y1 çözülmeden bu düzeltmeler katılımcıya ulaşmaz.

**Y3 — HEAD'de de sormadan silen/üzerine yazan içe aktarma ve tahmin yolları var.** (A+B) Tablo bölüm 7. Öne çıkanlar: GrandQC içe aktarım bütün lamda önceki sonucu siler (`yardimci-grandqc-sihirbaz.groovy:481`); VALIS/Warpy/Afin aktarımı aynı adlı önceki aktarılmış anotasyonları siler (`yardimci-valis-sihirbaz.groovy:1771,2761`, `yardimci-warpy-sihirbaz.groovy:186`, `yardimci-hizalama-aktarim.groovy:172`); KongNet içe aktarımı önceki "Generated by KongNet import" tespitlerini ve yetim halkaları siler (`yardimci-mitoz-kongnet.groovy:225-232`); TIA Toolbox bölge ve doku maskesi ve mitoz karşılaştırma sentinel anotasyonlarını siler. Hepsi yalnız kendi ürettiği nesneleri hedefliyor ve çoğu kilitli; ama elle düzeltilmiş sınırlar da gider ve soru yok. [KAYNAK]

**Y4 — "Paylaşabilirsiniz" denen tanı özeti hasta/vaka adını sızdırabilir.** (A) `WorkshopErrors.redactSensitiveText` yalnız ev dizinini, `X:\Users\…` yolunu ve `svs|ndpi|mrxs|scn|bif|vms|vmu|isyntax|dcm|ome.tif(f)|ome.zarr` uzantılı adları maskeliyor. Gerçek çalıştırma: `D:\arsiv\2025\S25-12345 Ahmet Y.tif`, `E:\hasta\kesit_4412.tiff`, `/mnt/data/case_77.qptiff` ve `Image name: S25-12345 A1` **maskelenmedi**; yalnız `C:\Users\ali\x.svs` maskelendi [ÇALIŞTIRILDI, QuPath 0.6.0 konsolunda JAR'dan sınıf yükleyerek]. README:126 "bu özeti paylaşabilirsiniz" diyor. Ek: genel "Sihirbaz açılamadı" penceresindeki "Teknik ayrıntı:" satırı hiç maskelenmeden ham istisna gösteriyor (`strings_tr.properties:337`), ve "atölye düzenleyicisine gönderin" atölye bağlamı varsayıyor.

### P2

**Y5 — Hata rehberi yanlış eşleşiyor, yanlış sonraki adımı öneriyor.** (A+B) [ÇALIŞTIRILDI] `NumberFormatException('For input string: "0,25"')` → "Görüntü biçimi okunamadı veya piramit bozuk" (sınıf adında "format" geçtiği için; `WorkshopErrors.java` `className.contains("format")`); `IllegalFormatConversionException` aynı mesajı veriyor; `IllegalArgumentException('financial')` → "Geçersiz sayısal ölçüm (NaN)" (ileti alt dizisi `nan`); `NullPointerException` ve `MissingMethodException` → "Bir sanal lam açın…" (lam açıkken de). Yani Türkçe ondalık virgül girip hata alan kullanıcı dosya bozuk sanıyor.

**Y6 — EN belgeler var olmayan menü adı veriyor; yönlendirme cümleleri grup adını atlıyor.** (A+B)
- Kök menü her dilde `Atölye` (`WorkshopExtension.java:67` `MENU_PATH`; kodda kök metin hiç değişmiyor). Ama `docs/first-run.md:24,26` "Extensions → **Workshop** → Utilities → Basic tools → Calibration / Modules → Cell detection", `en/hazirlik/yazilim-kurulumu.qmd:222,224`, `en/baslangic.qmd:15,50`, `en/hazirlik/atolye-gunu.qmd:70` aynısını yazıyor. İngilizce okur "Workshop" menüsünü arayıp bulamaz. Alt menüler ("Modules", "Utilities") gerçekten dil değiştiriyor [KAYNAK].
- Kullanıcıya gösterilen 94 satırda "Yardımcılar/Utilities → <araç>" yolu konu grubunu atlıyor (ör. gerçek yol Yardımcılar → Temel araçlar → Kalibrasyon); 23'ü `strings_tr/en.properties` içinde, ör. `hucre-tespiti.groovy:94` "Yardımcılar → Kalibrasyon" [ÇALIŞTIRILDI `path_scan.py`, HEURİSTİK].

**Y7 — Kurulum belgeleri birbirinden ayrışıyor.** (A) README: "Add catalog düğmesine tıklayın" (README:26); site: Manage extensions → **Manage extension catalogs → Add** (`hazirlik/yazilim-kurulumu.qmd:212`, `kaynaklar.qmd:891`). Sıra ters: README katalogu "önerilen" yapıyor, site sürükle-bırakı birincil, katalogu "yalnız A çalışmazsa" yapıyor (`yazilim-kurulumu.qmd:200`). README:28 "+ (Kur)" diyor, site yeşil **+** sonra **Install**. QuPath arayüzünde bu düğmeler görülmedi; hangisinin doğru olduğunu bilmiyorum.

**Y8 — EN modunda Türkçe iletiler (hata yolları dahil).** (A+B) Bkz. E1. Ek olarak: `Ortam kontrolü` ve `Hakkında` metinleri `WorkshopExtension.java:604-750` içinde iki dil yan yana, properties dışında (üçüncü metin kaynağı; ikisi script kataloğu, biri Java satır içi). EN `Hakkında` metninde TR'deki "Ayarlar" bölümü yok. 42 EN değerde Türkçe karakter/terim var (çoğu bilinçli adlar: "Atölye", "Tümör-Stroma Özet"; ama `script.b80b52e02bb5` gibi cümleler EN içinde Türkçe menü adı veriyor: "install the 'Classpose — H&E hücre fenotipleme' entry…").

**Y9 — Sürümle ilgili kurulum tuzakları.** (A) GitHub "Latest" `v0.2.1`; README:41 "ön sürümler listenin en üstünde" diye bunu karşılıyor, ama `releases/latest` bağlantısı veren her sayfa eski yapıya götürür (`check-release-sync` uyarısı).

**Y10 — Otomatik testin kapsamadığı silme/üzerine yazma kararları.** (B) `SafetyRegressionTest` yalnız CytoFormer/HistoPLUS/NuClick'in adlandırma ve onay kapanışlarını sınıyor; Classpose, HoVer-NeXt, FCOS, RetinaNet, Cellpose, Membran onay akışları için otomatik test yok; bunlar yalnız kaynak incelemesiyle "Ekle/Değiştir var" (`MODE_REPLACE` grep). [KAYNAK]

### P3

**Y11 — Belge/değişiklik kaydı kayması.** `CHANGELOG.md`: `[Unreleased]` hâlâ birleştirilmiş dal `feat/bilingual-pedagogy-audit`'e bağlı, "143 metin / sekiz script" diyor (gerçek 2.096 / 102); rc1–rc7 ve 0.2.0 girdileri yok; rc8 tek satır, tarihsiz. `README.md:13` aynı eski "143" sayısını yineliyor. `CHANGES.md:95` bayat (E6). `CLAUDE.md` (parent): "102 çift", "newest tag rc5", "Classpose hâlâ sormadan siliyor", "histoplus 18 doğrulama" gibi bayat sayılar var. Git geçmişi 258 commit, 12+ etiket.

**Y12 — Menüde ulaşılamayan iki script ve kalıcı gri bir giriş.** `model-egit.groovy` ve `tumor-stroma.groovy` pakette ama hiçbir menü girişi yok (101 benzersiz kaynak menüde, 103 dosya) [ÇALIŞTIRILDI `comm`]. "Atipik sınıflandırma — Sanofi EFTD (kapılı)" girişi her zaman gri (`WorkshopExtension.java:105`, `disabled=true`); README:83 listeye bu uyarı olmadan koyuyor; script içi açıklama "eklentiyi yeniden derleyip etkinleştirin" diyor — kod bilmeyen patolog için yapılamaz.

**Y13 — Ondalık: sözlük virgül diyor, çıktı nokta.** `GLOSSARY.md:10` "Türkçe ondalık virgül (0,25 µm/piksel)"; depo kuralı `String.format(Locale.US…)` (sonuç pencereleri "0.25", `%,d` binlik ayracı virgül: `1,234`). Ayarlar penceresi "0,25" ve "0.25"i kabul ediyor; `yardimci-tespitleri-sil` de. Sistematik değil: kaç girdi alanının virgülü kabul ettiği denetlenmedi.

**Y14 — Araç ve yeniden üretilebilirlik.** `yardimci-python-ortam-yoneticisi.groovy:57,328` `uv`'yi `releases/latest/download/`'dan sağlama toplamı doğrulamadan indirip çalıştırıyor (öteki indirmeler SHA ile sabit). `build.gradle.kts` SciJava `snapshots` deposunu da ekliyor (Y2 ile ilgisiz; yeniden üretilebilirlik riski). `tools/verify-candidate.py` Windows'ta kodlamasız `read_text()` ile çöküyor.

**Y15 — Sözlük uyumu.** OB-1 "slayt yalnız sunum slaytları için" der; TR arayüz metinlerinde 4 satırda (`strings_tr.properties:616,1257,2089,2298`) ve script içinde ~36 satırda "slayt" cam/sanal lam anlamında. Düğme etiketleri "siz" yerine çıplak emir ("Klasörü aç", "Kodu indir"); bu arayüz düğmeleri için olağan ama sözlük "siz" diyor.

**Y16 — İlk örnek erişimi.** Sentetik PNG yalnız GitHub'da "Download raw file" ile alınıyor (JAR'da, OSF'te yok); çevrimdışı salon için README bunu söylüyor ama hangi klasöre konacağını tarif etmiyor. İlk adım A için GitHub sayfa gezintisi gerektirir. GUI'de bu yol denenmedi.

## 5. Örneklenen script × hata yolu (30 script)

Hücre = ilgili durum için kullanıcıya mesaj var mı. **v** = dizgi eşleşmesi buldu (grep, sayım > 0), **—** = eşleşme bulunamadı (yokluk kanıtı değil, çoğu zaman o durum uygulanmaz), ***** = ilgili satırı elle okudum. "EN'de TR satır" = çevrilmemiş Türkçe sabit içeren arayüz satırı sayısı [HEURİSTİK]. Bu tablo tam bir test değildir: hiçbir yol çalıştırılmadı.

| Script (menü grubu) | Görüntü yok | Seçim yok | Piksel boyutu yok | Eksik Python/model/dosya | Proje yok | EN'de TR satır |
|---|---|---|---|---|---|---|
| `hucre-tespiti` (Modüller) | v* (çevrildi) | v | v* "Yardımcılar → Kalibrasyon" (yol eksik) | — | — | 15 |
| `nukleer-boya` | v* | v | v* | — | — | 9 |
| `er-pr-hscore` | v | v | v* | — | — | 8 |
| `membran-boya` | v | v | v | v (Cellpose yoksa Watershed'e düşer, println) | — | 21 |
| `tumor-stroma` | v | v | v | — | v | 6 |
| `sihirbaz` (Tümör/Stroma) | v | v | v | v | v | 9 |
| `quantum-ctcf` | v | v | v | v (StarDist yönlendirme) | — | 12 |
| `veri-aktarma` | v* | v | — | v | v* | 16 |
| `yardimci-doku-tespiti-sihirbaz` | v | v | v | v | — | 6 |
| `yardimci-grandqc-sihirbaz` | v | v | v | v | v | 22 |
| `yardimci-mitoz-fcos` | v | v | v | v | — | 17 |
| `yardimci-mitoz-merkez` | — (kendi durum satırı) | v | — | v | — | 7 |
| `yardimci-valis-sihirbaz` | v | v | v | v | v | 48 |
| `yardimci-hizalama-aktarim` | v | v | — | v | v | 13 |
| `yardimci-tespitleri-sil` | v | — | — | — | — | 4 |
| `yardimci-esik-ayarla` | v | v | — | v | — | 13 |
| `yardimci-kalibrasyon` | v | v | v | — | v | 5 |
| `yardimci-anotasyon-ice-aktar` | v | v | — | v | v | 4 |
| `yardimci-karo-disa-aktarma` | v | v | v | v | v | 19 |
| `yardimci-hotspot-sirali` | v | — | — (satır 128'de kalibrasyon okunuyor) | v | — | 6 |
| `yardimci-nn-mesafe` | v | — | v | — | — | 3 |
| `yardimci-anonim-sihirbaz` | v | v | v | — | v | 18 |
| `yardimci-metadata-sihirbaz` | v | — | v | — | v | 3 |
| `yardimci-kohort-ozet-topla` | v | — | — | v | v | 1 |
| `yardimci-python-ortam-yoneticisi` | — (gerekmez) | — | — | v (13) | — | 14 |
| `yardimci-classpose-sihirbaz` | v | v | v | v* "ortam kurulu değil → ② ile kurun" | — | 15 |
| `yardimci-histoplus-sihirbaz` | v | v | v | v | — | 14 |
| `yardimci-stardist-sihirbaz` | v | v | v | — | — | 8 |
| `yardimci-instanseg-sihirbaz` | v | v | v | — | — | 7 |
| `yardimci-wsinfer-denetim` | v | — | v | v | — | 1 |
| `yardimci-dicom-svs-sihirbaz` | — (gerekmez) | v | — | v | v | 18 |
| `yardimci-senkron-izgara` | — | v | — | — | v | 4 |

Genel gözlemler (hepsi [KAYNAK]/[HEURİSTİK]):
- **Menü** lam yokken lam isteyen girişleri gri bırakıyor ve üstte "⚠ Önce bir lam açın" yazıyor (`WorkshopExtension.java:297-303,785`). Menü kurulumu çökerse yalnız `logger.error` var, kullanıcıya mesaj yok (`:388-390`).
- Çalışma dışı kalan script istisnaları ortak iletişim kutusunda (EN/TR, kopyalanabilir rapor) gösteriliyor; bu iyi. Rehberin eşleştirme hataları Y5, maskeleme eksikleri Y4.
- Çoğu hata ve durum satırı **sonraki adımı söylüyor** (ör. "Önce bir bölgeyi anotasyonla çevreleyip SEÇİN"); yalnız-iletide-kalanlar: `GeoJSON okunamadı:\n` + ham istisna iletisi (GrandQC, Sectra, HoVer-NeXt; `yardimci-grandqc-sihirbaz.groovy:477`), `Alt betik hatası (${name}): ${err}` (`tumor-ici-ki67.groovy:57`, `quantum-ctcf.groovy:56`, `sihirbaz.groovy:84`), `Bölge sınır dışı ya da boş.` (FCOS/RetinaNet/Sanofi/…).
- `println` ile yalnız günlüğe yazılan uyarılar kullanıcıya görünmüyor, ör. floresan görüntüde "bu modül parlak alan içindir; tip değiştirilmedi" (`hucre-tespiti.groovy:76`) ve H-DAB varsayılan boya vektörü uygulaması (`nukleer-boya.groovy:120`): `QP.setImageType('BRIGHTFIELD_H_DAB')` yalnız konsola not düşüyor.
- Başsız (headless) kötü argüman: `yardimci-karo-disa-aktarma` ("Tanınmayan bağımsız değişken"), anotasyon dışa/içe aktarma kapalı başarısız oluyor; gidiş-dönüş testi hatalı argümanda çıktı üretilmediğini doğruladı [ÇALIŞTIRILDI]. Diğer başsız yollar denenmedi.

## 6. Üretilen ama eksik bırakılan belgeler ve örnekler

- Çalışılmış örnek: yalnız sentetik PNG (`docs/first-run.md`). Gerçek İHK örneği için dosya adı/boyut/SHA-256/ROI/beklenen ölçüm tablosu yok.
- Python köprüleri için kullanıcı belgesi README'de tek paragraf; hangi ortamın ne kadar disk/ağ (GB) istediği README'de yok (kaynaklar sitesinde olabilir, bu denetimde okunmadı).
- Değişiklik kaydı: bölüm Y11. Sürüm notu `docs/releases/` altında yalnız `v0.2.2-rc7.md`.
- Hata/sorun giderme: README 4 satırlık tablo; ayrı SSS yok.

## 7. Silen veya üzerine yazan script'ler

"Sorar" = onay/seçim penceresi var ve varsayılan hiçbir şey silmemek veya iptal. "Sormaz" = HEAD'de pencere yok. rc8 sütunu yayımlanmış etiketin durumu [ÇALIŞTIRILDI `git show` + grep]; kalan sütun [KAYNAK].

| Script | Ne siliyor / üzerine yazıyor | HEAD | Yayımlanmış rc8 |
|---|---|---|---|
| `yardimci-classpose-sihirbaz` | Seçili alandaki önceki Classpose özetleri ve sınıflı tespitler | **Sorar** (varsayılan Ekle + ikinci onay; `:931-950`) | Sormaz |
| `yardimci-hovernext-sihirbaz`, `yardimci-mitoz-fcos`, `-retinanet`, `-tiatoolbox` | Bölgedeki önceki sonuç/özet/nokta | **Sorar** (`MODE_REPLACE`) | Sormaz |
| `yardimci-histoplus-sihirbaz`, `yardimci-nuclick-sihirbaz`, `yardimci-cytoformer-sihirbaz` | Önceki katman / yabancı sınıflar | **Sorar** (varsayılan koru) | Sorar |
| `yardimci-histoart-sihirbaz` | Bütün lamdaki sentinel anotasyon | **Sorar** (evet/hayır onayı, yalnız GUI; başsızda sormadan siler `:704-713`) | Sormaz |
| `membran-boya`, `yardimci-cellpose-sihirbaz` | Seçili anotasyonun mevcut alt tespitleri | **Sorar** (evet/hayır; `membran-boya.groovy:667`, `cellpose:444`) | Sormaz (ikisinde de yeniden çalıştırma onayı yok) |
| `yardimci-maske-iceaktar`, `yardimci-tahmin-iceaktar` | Önceki içe aktarım anotasyonları | **Sorar** (varsayılan Ekle) | — |
| `yardimci-anotasyon-ice-aktar` | `dosya` veya `tumu` kipinde anotasyonlar | **Sorar** (varsayılan ekle; başsızda `mod=` açık seçim) | — |
| `yardimci-sectra-iceaktar` | Önceki Sectra anotasyonları | **Sorar** (`replacePrevious`) | — |
| `yardimci-tespitleri-sil` | Tüm / küçük / sahipsiz tespitler | **Sorar** (açık amaç + onay) | — |
| `yardimci-karo-disa-aktarma` | Bu lamın eski karo dosyaları | **Sorar** (varsayılan bırak; `:1205-1230`) | — |
| `yardimci-grandqc-sihirbaz` (içe aktarım) | **Bütün lamda** önceki GrandQC anotasyonları | **Sormaz** (`:481`) | Sormaz |
| `yardimci-valis-sihirbaz` | Hedefte önceki VALIS anotasyonları | **Sormaz** (`:1771,2761`) | Sormaz |
| `yardimci-warpy-sihirbaz` | Önceki Warpy aktarımı | **Sormaz** (`:186`) | Sormaz |
| `yardimci-hizalama-aktarim` | Önceki afin aktarım | **Sormaz** (`:172`) | Sormaz |
| `yardimci-mitoz-kongnet` | Önceki KongNet tespitleri + yetim halkalar | **Sormaz** (`:225-232`) | Sormaz |
| `yardimci-mitoz-karsilastir` | Bölgede ilgili sınıfın nokta anotasyonları, özet, konsensüs | **Sormaz** (`:358,590,594`) | Sormaz |
| `yardimci-tiatoolbox-bolge-sihirbaz`, `yardimci-tiatoolbox-sihirbaz` (doku maskesi), `yardimci-doku-tespiti-sihirbaz` | Önceki sentinel anotasyonları (`Doku` vb.) | **Sormaz** (`:328`, `:395`, `:178`) | Sormaz |
| Özet anotasyonu üreten ölçüm araçları: `yardimci-hotspot-sirali`, `-nn-mesafe`, `-capraz-nn-mesafe`, `-sinif-sayim`, `-delaunay-komsuluk`, `-dogrulama-f1`, `-immun-yogunluk`, `-stromal-til`, `-wsinfer-ozet`, `-mitoz-phh3`, `-yapi-uzaklik`, `-kesisim-alani`, `-peritumoral-bant`, `-konveks-zarf`, `-merkez-cevre`, `-ki67-heterojenlik`, `-yogunluk-haritasi`, `-goz-karari-kalibrasyon`, `tumor-ici-ki67`, `sihirbaz`, `tumor-stroma` | Kendi adlı özet/ızgara/halka anotasyonlarını yeniden çalıştırmada siler (ad ile eşleşme; kullanıcı aynı adı vermişse gider) | **Sormaz** (yalnız kendi çıktısı; düşük risk) | Sormaz |
| `yardimci-tumor-tomurcuklanma` | Önceki `BAND_NAME` bantları; ölçüt dışı tespitleri | **Sormaz** (`:173,210`) | Sormaz |
| `hucre-tespiti` | `BRIGHTFIELD_H_E` tipini sessizce ayarlar (stain'de hematoksilen yoksa) | **Sormaz**, yalnız `println` (`:79`) | Sormaz |
| `nukleer-boya`, `er-pr-hscore`, `membran-boya`, `sitoplazmik-boya` | H-DAB tipinde ve hematoksilen yoksa varsayılanı uygular | **Sormaz**, `println` (`nukleer-boya.groovy:120`) | Sormaz |
| `yardimci-histoart/kaiko/spider-sihirbaz`, `yardimci-tiatoolbox-sihirbaz` | Kendi ürettikleri ara karo klasörü (`deleteDir`, `listFiles().each delete`) | Sormaz; çalışma klasörü içinde | Sormaz |
| `yardimci-anonim-sihirbaz` | Kopya üretir; kaynak dosyayı değiştirmez; başarısız kopyayı siler | Sorar/denetler (39 test) | — |
| `veri-aktarma`, `omezarr`, `bolge-cikart`, `goruntu-yakala` | Çıktı dosyası: tarih alt klasörü varsayılan açık; `-1,-2` ek ile çakışma önleniyor (`goruntu-yakala:81`, `bolge-cikart:117`) | Üzerine yazmaz (aynı gün aynı önek `veri-aktarma` için doğrulanmadı) | — |

## 8. Sürüm uyumu

- Beyan: QuPath `0.6.0` (`build.gradle.kts:48`, `WorkshopExtension.java:773`, `gradle.properties`, `catalog.json min v0.6.0`) — hepsi tutarlı [ÇALIŞTIRILDI `check-version-sync`].
- Derleme 0.6.0 API'sine karşı; Gradle JavaFX 21 bildirimi. Bütün 103 script **0.6.0 ve 0.7.0 konsolunda derlendi** ve çalıştırılmadan ayrıştırıldı; kullanıcı arayüzü metin aramaları, ifadeler ve ilk örnek her iki sürümde geçti [ÇALIŞTIRILDI]. 0.7'ye özgü API bağımlılığı bulunamadı; 0.7 yalnız tavsiye metinlerinde geçiyor (ICC, MCP). Python köprüleri her iki sürümde çalıştırılmadı.
- 0.7.0 konsolu açılışında `java.io.IOException: Unable to extract JPen natives` yazdı; kendi uzantı koduyla ilgisiz görünüyor (QuPath/JPen). 0.6.0'da üçüncü taraf JAR yinelenme uyarıları.
- Bağımlılık: `junit 4.13.2`, `groovy-test 4.0.21`, `zxing core 3.5.3` yalnız test. ZXing çalışma anında SHA ile sabit indirilir (paketlenmez). Çalışma zamanı bağımlılığı yok.

## 9. Kapsam: otomatik, başsız-elle, hiç denenmemiş

| Alan | Otomatik (JUnit/CI) | Başsız elle (konsol, bu denetimde ÇALIŞTIRILDI) | Hiç denenmemiş (bu denetimde) |
|---|---|---|---|
| Anonimleştirme sihirbazı (TIFF motoru, HMAC, iptal) | 39 test | — | GUI, gerçek tarayıcı SVS/NDPI/BigTIFF, Sectra barkodu |
| Hata yönlendirme, ayar doğrulama, mikrometin | ParameterValidation 6 test + Safety içindeki maskeleme testi | Yukarıdaki yanlış eşleşmeler (Y4, Y5) | — |
| TR/EN anahtar eşliği, katalog, menü kaynakları | 4 test + 6 dil testi (3+2+1) | 427.634 arama, 61 ifade, 103 script derleme (0.6.0 + 0.7.0) | Canlı menü dil geçişi |
| Hücre tespiti + sentetik ilk örnek | 1 test (dil) | 100 nesne, iki sürüm | Kalibrasyon penceresi, seçim, düğme |
| CytoFormer / HistoPLUS / NuClick onay ve adlandırma | 12 test + parent 3 konsol testi (21/16/19) | 3 konsol testi iki sürümde | GUI penceresi, gerçek model |
| Toplu anotasyon dışa/içe aktarma | — | Gidiş-dönüş (5 biçim, iki sürüm) | GUI yolları (canlı lam, proje sabitleme) |
| Classpose, HoVer-NeXt, FCOS, RetinaNet, TIA Toolbox, Cellpose, Membran: Ekle/Değiştir | — (yalnız kaynak) | — | GUI + gerçek çalışma |
| GrandQC, VALIS, Warpy, HistoART, Kaiko, SPIDER, StarDist, InstanSeg, … 15 Python köprüsü | — | yalnız derleme/ayrıştırma | Ortam kurulumu, model indirme, gerçek çıkarım |
| Menü, kurulum (katalog/sürükle-bırak), sürüm denetimi, yeniden başlatma | — | — | Hepsi |
| macOS / Linux, QuPath 0.5 ve 0.8+ | CI yalnız Ubuntu derleme | — | QuPath çalışması |
| Yayımlanmış `v0.2.2-rc8` JAR'ının kendisi | — | — | Çalıştırılmadı; yalnız içeriği `git ls-tree` ile karşılaştırıldı |

## 10. Doğrulanamayanlar

- Bütün GUI yolları: menü, dil geçişi, katalogdan kurulum düğmeleri ve adları (Y7), iptal/üzerine yazma pencereleri, Kalibrasyon ve Hücre tespiti pencereleri.
- Yayımlanmış rc8 JAR'ının davranışı: "Sormaz" sütunu kaynaktan (etiketteki script metni) çıkarıldı, JAR çalıştırılmadı.
- Gerçek model ağırlıkları, Hugging Face kapılı modeller, gerçek WSI dosyaları, GPU yolu.
- EN metinlerin çeviri kalitesi/doğruluğu (yalnız anahtar eşliği ve ekran görüntüsüz metin taraması yapıldı); Türkçe cümlelerin tamamı okunmadı. Yalnız örnekleme ve düzenli ifade (diakritik eksikliği, "siz/sen") tarandı; çıplak emir düğme etiketleri dışında "sen" biçimi bulunmadı.
- `String.format` Locale uyumu lint'e güveniyor; ben dışında `%,d`/`%.1f` kullanımı yalnız örneklendi.
- Zenodo ve OSF içeriğinin rc8 JAR'ıyla bayt eşitliği (yalnız varlık doğrulandı).
- Üçüncü taraf siteler: parent depodaki kardeş denetim ajanına ait.

## 11. Sahibe ait kararlar

1. HEAD yayımlanmış `v0.2.2-rc8`'den farklı ve aynı sürüm numarasını taşıyor: yeni sürüm numarası ne olacak, ve ne zaman yayımlanacak? Bu karar verilene kadar belge ve katalog arasındaki çelişki sürer (Y1, Y2).
2. Sahibin "içe aktarma/tahmin sihirbazları önce sorar" kuralı hizalama aktarımları, GrandQC içe aktarımı, KongNet içe aktarımı ve sentinel anotasyon yeniden yazımlarını kapsıyor mu? Kendi çıktısı olan özet anotasyonları için sormak gerekli mi (Y3, bölüm 7)?
3. Tanı özetinin gizlilik kapsamı: yalnız ev dizini ve WSI uzantıları mı, yoksa tüm dosya adı/yol kalıpları ve görüntü adları mı (Y4)?
4. EN modda kök menü adı `Atölye` olarak mı kalacak, yoksa EN'de `Workshop` olacak mı? Bu bir arayüz değişikliği; şu an belgeler yanlış (Y6).
5. Ondalık gösterim: Türkçe arayüzde `0,25` (sözlük) mi, `0.25` (Locale.US kuralı) mi (Y13)?
6. `Sanofi EFTD` girişi gri kalacak mı, kapalı olarak mı listelenecek (Y12)? `tumor-stroma.groovy` ve `model-egit.groovy` menüye girecek mi?
7. `uv` indirmesi için sağlama toplamı sabitleme isteği (Y14) ve "sürücü/yolcu" tanımı için klinik inceleme (G2).
8. `slayt` sözcüğünün arayüzde cam/sanal lam anlamında kullanımı OB-1 ile çelişiyor (Y15): bilinçli mi?
