# QuPath Workshop uzantısı denetimi — 14 Kasım 2026

**Aşama 1 güncel denetimi. Aşama 2 planı onay bekliyor; Aşama 3 başlamadı.**

İncelenen commit: `5a3715ff995abcb17b9e2047a58e4e9656a18d71`. Başlangıç çalışma ağacı temiz, dal `main` idi. Derlemeler izlenen dosyaların geçici kopyalarında yapıldı. Kaynak, uygulama davranışı, kalıcı site çıktıları ve dağıtım ayarları değiştirilmedi. Bu çalışmanın depodaki teslimi yalnızca bu rapordur. Commit, push ve dağıtım yapılmadı. Eski rapordaki bulgular güncel kaynakla yeniden sınandı.

## Yöntem ve sınırlar

Bütün yayımlanan QMD sayfaları, dil eşleri, yerel HTML bağlantıları/çapaları ve medya yolları tarandı. Taze derlemelerin `assets/**/*.html` bağımlılıkları da kontrol edildi. Include dosyaları açılarak sayfa iskeleti çıkarıldı. Her cümlenin uzman editoryal incelemesi yapılmış değildir; ayrıntılı inceleme başlangıç, kurulum, ana işlem yolları ve aşağıdaki somut örneklere odaklandı.

Üç site birlikte 1.033 tekil dış URL için anonim HTTP kontrolünden geçti: 905 adet 2xx, GET ile de doğrulanan 14 adet 404, 114 adet koruma/ağ/diğer belirsiz sonuç. 401/403/416 ve zaman aşımı bozuk bağlantı ilan edilmedi. GitHub 404 özel/silinmiş depo ayrımını göstermez. Dış sayfaların bütün çapaları ve bağlantı içeriklerinin uygunluğu doğrulanmadı.

Chrome test profillerinde dış HTTP engellenerek 1280×800 ve 390×844 görünüm sınandı. Toplam 105 TR/EN açılışında JavaScript veya gezinme hatası yoktu. Bu, bütün eğitim ve kullanıcı yollarının tamamlandığı anlamına gelmez. Azaltılmış hareket tercihiyle açılışlar da sınandı; gerçek projektör/ekran okuyucu oturumu yapılmadı.

## Doğrulanan durum

| Kontrol | Sonuç ve sınır |
|---|---|
| `gradlew.bat --offline --no-daemon --console=plain clean build`, Java 21 | Geçti; geçici Gradle önbelleğiyle `qupath-extension-workshop-0.2.2-rc8.jar` |
| Testler | 61; 0 hata/başarısız/atlanmış: anonimleştirme 39, parametre 6, güvenlik 12, dil/katalog 4 |
| `tools/smoke-extension.groovy`, gerçek QuPath 0.6.0 ve 0.7.0 | İkisi 0; test JAR konumu, 103 script, servis, ayar API'si, sayısal doğrulama |
| Gerçek hostta sentetik çekirdek örneği | İki sürümde paketli hücre tespiti/nükleer boya çalıştı; 100 nesne. Klinik doğruluk ölçümü değildir |
| `tools/test-annotation-roundtrip.ps1 -Stage export -Keep` | İki sürümde geçti; fixture, beş biçim, sınıf/tespit filtresi, hatalı argümanda çıktı üretmeme |
| Groovy lint/eşlik | 103 temiz; Python'da yalnız satır sonu farkı: E4 |
| Sürüm/katalog/alt sınır | 0.2.2-rc8 ve QuPath 0.6.0 referansları aynı |
| Dil kaynakları | TR/EN property anahtarları/kayıtlı menü kaynakları testle doğrulandı |
| GUI | Sınanamadı: iki Computer Use girişinde native pipe yok (`os error 2`). Bu reddedilmiş Windows onayı değildir |

İlk eksik önbellek kopyasıyla çevrimdışı deneme Foojay plugin çözümlemesine takıldı; mevcut önbellek eksiksiz kopyalanınca temiz derleme geçti. Kaynak derleme hatası sayılmadı. QuPath CLI kullanıcının diğer eklentilerini de yükledi; 0.6'da yinelenen üçüncü taraf JAR uyarıları vardı. Test JAR kimliği ayrıca kontrol edildi; temiz işletim sistemi hesabında kurulum/GUI sertifikasyonu yapılmadı. Türkçe CLI loglarında konsol kodlamasına bağlı karakter kaybı görüldü; sayısal sonuç/çıkış kodu okunabildi.

Eski rapordaki eksik dil sınıfı, paketlenmemiş köprüler, 49 teste sabitlenmiş CI ve changelog yok bulguları kapanmıştır. `candidate-acceptance.yml` artık `>=55` kullanıyor; bu çalışmadaki 61 teste uygun. Köprüler, `CHANGELOG.md`, `CHANGES.md` ve katalog mevcut.

## Öğretim etkisine göre açık bulgular

### E1 — P1: EN menü çalışma pencerelerinin EN olduğu anlamına gelmiyor

`WorkshopI18n` menü/ayar/üst hatalarda yararlı. Fakat `src/main/resources/scripts/hucre-tespiti.groovy` gibi ana scriptler görüntü yok, ROI seçimi, kalibrasyon ve pencere metinlerini doğrudan Türkçe yazıyor. EN menüden Türkçe iletiye düşülebilir; property anahtar testi script içini kapsamıyor. Bu **kaynak incelemesi bulgusu**, GUI'de görülmüş ekran sonucu değildir. Ortak, varsayılanı TR olan kaynak ve bağımsız scriptte de çalışan dil yolu gerekli; algoritma/parametre davranışı korunmalı.

### E2 — P1: İlk örnek EN okur için eksik; örnek dosya belirsiz

README katalog kurulumu TR/EN, fakat sürükle-bırak ve ilk örnek işlem metinleri büyük ölçüde Türkçe. OSF proje bağlantısı var; tam Ki-67 dosya adı, boyut/sağlama ve belirli ROI'nin örnek ölçüm tablosu yok. “Hangi dosya, doğru sonuç bu mu?” soruları açık. GUI erişilemediği için `Add catalog` düğmesinin mevcut tam metni/konumu da ayrıca görülecek. Sentetik küçük çalışılmış örnek ve çekim listesi gerekli.

### E3 — P1: Bazı script catch blokları ortak hata rehberini atlıyor

`WorkshopErrors` iki dilde sonraki adım ve maskelenmiş tanı özeti sağlıyor. Ancak hücre tespiti son `catch`, doğrudan `Dialogs.showErrorMessage('Hücre tespiti açılamadı', sınıf + mesaj)` kullanarak dönüyor; kullanıcı teknik istisnayla kalabilir. Eksik dosya/yanlış görüntü/kalibrasyon gibi yollar eyleme dönük rehbere bağlanmalı. Mevcut dosya/ev dizini maskelemesi testlerde korunmalı.

### E4 — P2: Windows Python aynaları bayt sözleşmesini bozuyor

Karşılaştırılan 31 dosyadan 30 eş CRLF/LF farkı taşıyor; normalleştirilen içerikler aynı. İki `.gitattributes` yalnız Groovy için LF zorluyor. Python/requirements politikası iki depoda birlikte uygulanmalı; içerik/arayüz değişmemeli. Ana kontrolün hata sonrası başarı mesajı da düzeltilmeli.

### E5 — P2: Geçen testler bütün 103 yolun onayı değil

61 test çoğunlukla anonimleştirme/güvenlik sözleşmeleri. Bu denetimde iki hostta JAR yükleme, iki ana sentetik tespit ve beş biçimde dışa aktarma geçti. Tam içe aktarma gidiş-dönüşü, GUI iptal/üstüne yazma/dil değiştirme, ilk ortam indirmesi, model ağırlıkları ve 15 köprünün gerçek model çalışması sınanmadı. macOS/Linux GUI ve 0.6/0.7 dışındaki sürümler doğrulanmadı. Kapsama matrisi hazırlanmalı; sınanmayan yola “uyumlu” veya “bozuk” hükmü verilmemeli.

### E6 — P2: Tercih anahtarı belgesi ve kod eş değil

`CHANGELOG.md`, dil tercihine `atolye.language` diyor; `WorkshopI18n.java` `/qupath/atolye/i18n` düğümünde `language` kullanıyor. Katılımcıya iç anahtar öğretilmemeli, bakım belgesi düzeltilmeli. Alt sınır 0.6.0/Java 21 tutarlı. CLI Türkçe kodlaması için yeniden üretilebilir UTF-8 talimatı doğrulanmalı; uyumsuz sürüm biçimi eklenmemeli.

## Modül ve belge envanteri

- 9 Java sınıfı: `WorkshopExtension`, `WorkshopSettingsDialog`, `WorkshopPrefs`, `WorkshopMicrocopy`, `WorkshopColorPalettes`, `WorkshopParameterValidation`, `WorkshopErrors`, `WorkshopResources`, `WorkshopI18n`.
- Kaynak: 103 Groovy, 15 Python köprü klasörü/31 karşılaştırılan dosya, sınıflandırıcı, tur görselleri ve `i18n/strings_{tr,en}.properties`.
- 4 test sınıfı: anonimleştirme, parametre, güvenlik ve dil/katalog.
- Belgeler: `README.md`, `GLOSSARY.md`, `CHANGELOG.md`, `CHANGES.md`, `docs/candidate-integration-acceptance.md`, `docs/rc6-safety-validation.md`, `docs/rc6-published-artifact-audit.md`, `docs/rc7-published-artifact-audit.md`, `docs/releases/v0.2.2-rc7.md`, `src/main/resources/images/tour/README.md`.
- Alt depoda Quarto sayfası yok; bütün web dersleri ana deponun envanterinde. Paketli scriptlerin tam listesi aşağıda.

## Aşama 2 — onay bekleyen plan

| Öncelik / değişiklik | Neden | Efor | Dosyalar |
|---|---|---|---|
| P1 — sıfır kodlu TR/EN kurulum ve belirli sentetik örnek | README'den tek başına bitirebilme | 1–2 gün | `README.md`, `docs/` örnek/çekim listesi, örnek üretimi/verisi; ana site bağlantıları |
| P1 — ana script dili, sonra kalan 103 yol | EN menüden Türkçe pencereye düşmeme | ana yollar 2–3; kalan 3–5 gün | `WorkshopI18n.java`, strings dosyaları, scriptler ve ana depo aynaları |
| P1 — rehberi atlayan catch yolları | Uygulanabilir sonraki adım | 1 gün | `WorkshopErrors.java`, ilgili Groovy/i18n/hata testleri |
| P2 — Python LF | Windows bayt eşliği | 0,25 gün | iki `.gitattributes`, ana kontrol mesajı |
| P2 — iki hostta ana yol/GUI matrisi | Test boşluklarını kapatma | 1–2 gün + GUI oturumu | `src/test/`, sentetik host araçları, `.github/workflows/`, `docs/` |
| P2 — sözlük/changelog/UTF-8 | Katılımcı/bakım dili | 0,5 gün | `GLOSSARY.md`, `README.md`, `CHANGELOG.md`, `CHANGES.md`, ana site sözlüğü |

**Davranış/arayüz sınırı:** algoritma, eşik varsayılanı, girdi/çıktı biçimi, işlem kapsamı ve public Java API otomatik değiştirilmeyecek. Gerekiyorsa önce mevcut/yeni davranış ve etkisi size sunulur. CLI hata çıkış semantiği değişikliği de ayrı onay gerektirir. Yeni çalışma zamanı bağımlılığı önerilmiyor.

### Görsel ve video teslimi

Kurulum: katalog→kur→yeniden başlat→menüyü gör. İlk çalışma: tam dosya→görüntü tipi→kapalı ROI→işlem→ölçüm. Hata: belirti→düzelt→beklenen çıktıyı yeniden gör. Her çekim listesinin hedefi, kod yazmadan işlemi bitirip doğru çıktıyı ayırt edebilmektir. TR/EN anlatım ve WebVTT hazırlanır; **QuPath GUI kayıtlarını kullanıcı çeker**. Ana sitedeki boya ayrımı/hücre tespiti/karo animasyonları korunur; süs animasyonu eklenmez. Sentetik/mevcut izinli örnek, çift dil, renk dışı işaret ve kısa alternatif açıklama şartları geçerlidir.

## Uygulama kapısı

Onaydan sonra alt depoda ayrı özellik dalı; ana siteyle koordineli küçük commitler; her değişiklikte temiz Gradle ve ilgili QuPath/eşlik kontrolü. Ana depo submodule işaretçisi ancak inceleme kapsamında güncellenir. Sürüm/release/push/OSF/Zenodo/Cloudflare dağıtımı yapılmaz.

## Ortak sözlük ve bekleyen kararlar

Dört `GLOSSARY.md` kopyası bayt düzeyinde aynı: 95 EN/TR terim, iki kitle için tanım ve OB-1…OB-14 tablosu; SHA-256 başlangıcı `cbfe0da5b1c1`. Eski “sözlük yok/tek kitleli” bulgusu kapanmıştır. Bu aşamada sözlük veya önceki onaylar değiştirilmedi.

Kapsam bütün teknik sözcükleri henüz karşılamıyor: terminal/komut satırı/konsol, çalışma dizini/dosya yolu, sanal ortam, tensör, epoch, VUS/sürücü/yolcu ve örnek–hasta ayrımı için ayrı ilk kullanım tanımları eksik. `TPD`, `DSÖ`, `yz+gk/build-glossary.py` gibi genel kaynak notları dışarıdan denetlenebilir kullanım kaynağı yerine geçmiyor. Yazım ilkelerindeki `su분` karakter artığı da temizlenmeli.

### ONAY BEKLEYEN TERİMLER / kapsam

- `CLAUDE.md` eski kapsam notları ile OB tablosunun dört depoya uygulama kapsamı eşlenmeli; bu rapor yeni Türkçe varyant seçmedi.
- `terminal / console / command prompt`, `directory / folder`, `epoch`, `tensor` için mevcut varyantlar çıkarılıp içerik yazılmadan önce **GLOSSARY.md içindeki ONAY BEKLEYEN TERİMLER** bölümünde sunulmalı.
- VUS/sürücü/yolcu ayrımı klinik yazar incelemesi gerektirir; terim kararı biyolojik hüküm yerine geçmez.
- Öneri: QuPath `GLOSSARY.md` yetkili kaynak, diğer üç kopya sürümlü ayna olsun. Bu karardan sonra üreticiler ve eşlik kontrolü bağlansın; yeni bağımlılık gerekmiyor.

## Paketli 103 scriptin tam envanteri

- `src/main/resources/scripts/arayuz-turu.groovy`
- `src/main/resources/scripts/er-pr-hscore.groovy`
- `src/main/resources/scripts/hucre-tespiti.groovy`
- `src/main/resources/scripts/membran-boya.groovy`
- `src/main/resources/scripts/model-egit.groovy`
- `src/main/resources/scripts/nukleer-boya.groovy`
- `src/main/resources/scripts/quantum-ctcf.groovy`
- `src/main/resources/scripts/sihirbaz.groovy`
- `src/main/resources/scripts/sitoplazmik-boya.groovy`
- `src/main/resources/scripts/tumor-ici-ki67.groovy`
- `src/main/resources/scripts/tumor-stroma.groovy`
- `src/main/resources/scripts/veri-aktarma.groovy`
- `src/main/resources/scripts/yardimci-ajan-kopru-denetimi.groovy`
- `src/main/resources/scripts/yardimci-alan-pozitiflik.groovy`
- `src/main/resources/scripts/yardimci-anonim-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-anotasyon-disa-aktar.groovy`
- `src/main/resources/scripts/yardimci-anotasyon-ice-aktar.groovy`
- `src/main/resources/scripts/yardimci-anotasyon-qc.groovy`
- `src/main/resources/scripts/yardimci-bolge-cikart-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-boya-kalite-qc.groovy`
- `src/main/resources/scripts/yardimci-boya-vektor-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-capraz-nn-mesafe.groovy`
- `src/main/resources/scripts/yardimci-cellpose-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-classpose-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-cytoformer-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-dagilim-grafigi.groovy`
- `src/main/resources/scripts/yardimci-delaunay-komsuluk.groovy`
- `src/main/resources/scripts/yardimci-dicom-svs-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-dogrulama-f1.groovy`
- `src/main/resources/scripts/yardimci-doku-tespiti-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-entegre-od.groovy`
- `src/main/resources/scripts/yardimci-esik-alan.groovy`
- `src/main/resources/scripts/yardimci-esik-ayarla.groovy`
- `src/main/resources/scripts/yardimci-esik-siniflandirici.groovy`
- `src/main/resources/scripts/yardimci-foundation-model-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-goruntu-isleme-turu.groovy`
- `src/main/resources/scripts/yardimci-goruntu-kunye.groovy`
- `src/main/resources/scripts/yardimci-goruntu-yakala.groovy`
- `src/main/resources/scripts/yardimci-goruntuden-alan-cikart.groovy`
- `src/main/resources/scripts/yardimci-goz-karari-kalibrasyon.groovy`
- `src/main/resources/scripts/yardimci-grandqc-kohort-ozet.groovy`
- `src/main/resources/scripts/yardimci-grandqc-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-hepatosit-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-histoart-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-histoplus-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-hizalama-aktarim.groovy`
- `src/main/resources/scripts/yardimci-hotspot-sirali.groovy`
- `src/main/resources/scripts/yardimci-hovernext-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-icc-denetci-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-image-type.groovy`
- `src/main/resources/scripts/yardimci-imagej-otsu-anotasyon.groovy`
- `src/main/resources/scripts/yardimci-imagej-spline-duzeltme.groovy`
- `src/main/resources/scripts/yardimci-immun-yogunluk.groovy`
- `src/main/resources/scripts/yardimci-instanseg-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-kaiko-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-kalibrasyon.groovy`
- `src/main/resources/scripts/yardimci-karo-disa-aktarma.groovy`
- `src/main/resources/scripts/yardimci-kesisim-alani.groovy`
- `src/main/resources/scripts/yardimci-ki67-heterojenlik.groovy`
- `src/main/resources/scripts/yardimci-kohort-boya-qc.groovy`
- `src/main/resources/scripts/yardimci-kohort-ozet-topla.groovy`
- `src/main/resources/scripts/yardimci-konveks-zarf.groovy`
- `src/main/resources/scripts/yardimci-kosu-manifesti.groovy`
- `src/main/resources/scripts/yardimci-kume-etiketi-iceaktar.groovy`
- `src/main/resources/scripts/yardimci-maske-iceaktar.groovy`
- `src/main/resources/scripts/yardimci-merkez-cevre.groovy`
- `src/main/resources/scripts/yardimci-metadata-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-mitoz-atipik-effnet.groovy`
- `src/main/resources/scripts/yardimci-mitoz-atipik-sanofi.groovy`
- `src/main/resources/scripts/yardimci-mitoz-fcos.groovy`
- `src/main/resources/scripts/yardimci-mitoz-karsilastir.groovy`
- `src/main/resources/scripts/yardimci-mitoz-kongnet.groovy`
- `src/main/resources/scripts/yardimci-mitoz-merkez.groovy`
- `src/main/resources/scripts/yardimci-mitoz-phh3.groovy`
- `src/main/resources/scripts/yardimci-mitoz-retinanet.groovy`
- `src/main/resources/scripts/yardimci-mitoz-tiatoolbox.groovy`
- `src/main/resources/scripts/yardimci-nn-mesafe.groovy`
- `src/main/resources/scripts/yardimci-nuclick-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-omezarr-disa-aktarma.groovy`
- `src/main/resources/scripts/yardimci-ornek-siniflandirici.groovy`
- `src/main/resources/scripts/yardimci-ozellik-matrisi.groovy`
- `src/main/resources/scripts/yardimci-peritumoral-bant.groovy`
- `src/main/resources/scripts/yardimci-python-ortam-yoneticisi.groovy`
- `src/main/resources/scripts/yardimci-sectra-iceaktar.groovy`
- `src/main/resources/scripts/yardimci-senkron-izgara.groovy`
- `src/main/resources/scripts/yardimci-sinif-sayim.groovy`
- `src/main/resources/scripts/yardimci-spider-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-stardist-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-stromal-til.groovy`
- `src/main/resources/scripts/yardimci-tahmin-iceaktar.groovy`
- `src/main/resources/scripts/yardimci-tespitleri-sil.groovy`
- `src/main/resources/scripts/yardimci-tiatoolbox-bolge-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-tiatoolbox-maske.groovy`
- `src/main/resources/scripts/yardimci-tiatoolbox-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-tma-cekirdek-aktarim.groovy`
- `src/main/resources/scripts/yardimci-tumor-tomurcuklanma.groovy`
- `src/main/resources/scripts/yardimci-tus-fare-gostergesi.groovy`
- `src/main/resources/scripts/yardimci-valis-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-warpy-sihirbaz.groovy`
- `src/main/resources/scripts/yardimci-wsinfer-denetim.groovy`
- `src/main/resources/scripts/yardimci-wsinfer-ozet.groovy`
- `src/main/resources/scripts/yardimci-yapi-uzaklik.groovy`
- `src/main/resources/scripts/yardimci-yogunluk-haritasi.groovy`
