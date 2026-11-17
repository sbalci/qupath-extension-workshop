# Raster maske içe aktarma denetimi — 16 Kasım 2026

Bu kayıt yerel `0.2.2-rc9` geliştirme adayı içindir. Yayımlanmış `v0.2.2-rc8` dosyasının aynı davranışları sunduğu iddia edilmez. Sürüm numarası değiştirilmedi; sürüm yayımlanmadı.

`yardimci-maske-iceaktar.groovy` incelendiğinde üç yanlış veri aktarımı yolu bulundu:

| Kullanıcı sorunu | Önceki davranış | Yeni davranış |
|---|---|---|
| JSON yan dosyası okunamıyor veya ölçek geçersiz | JSON okuma hatası yok sayılıyordu; geçersiz ölçek bazı yollarda 1 oluyordu, pozitif sonsuz kabul ediliyordu. | Okunamayan JSON, sıfır/negatif/sonlu olmayan ölçek anotasyon eklenmeden durur. Hata iletisi düzeltilecek dosyayı veya değeri açıklar. Eksik JSON için elle ölçek girme yolu korunur. |
| Görüntü genişliği doğru, yüksekliği yanlış | Yalnız genişlik karşılaştırılıyordu. | İki eksen ayrı karşılaştırılır. Boyut farkında iki görüntünün genişlik ve yüksekliği gösterilir. Mevcut devam onayı korunur; maskenin (0,0) köşesine yerleştirildiği, kırpma konumunun çıkarılmadığı belirtilir. |
| RGB/RGBA renk gösterimi etiket maskesi sanılıyor | Yalnız ilk renk kanalı sınıf indeksi olarak izleniyordu. | Çok kanallı görüntü anotasyon eklenmeden durur. Tek raster bandı olan indeksli palet, 8 bit gri ve 16 bit gri maskeler kabul edilir. |

Ölçeği otomatik düzeltmek, sınıf rengini etiket değeri sanmak veya hatalı ekseni sessizce kabul etmek yerine kullanıcıdan doğru kaynak veri istenir. Önceki anotasyonları ekleme/değiştirme seçimi bu değişiklikte düzenlenmedi.

`MaskImportGeometryTest` üretimdeki ölçek ve boyut yardımcılarını, çok kanallı maske kapısını ve QuPath 0.6.0 `ContourTracing` API'sini çalıştırır. Sentetik rasterde 7 ve 255 etiketlerinin ayrı kaldığı; downsample=4 için 2×2 maske pikselinin 64 taban piksel² alanına dönüştüğü denetlenir. Bu, klinik başarım ölçümü değildir.

Komutlar **PowerShell** içinde **eklenti deposunun kök klasöründen** çalıştırılır:

```powershell
.\gradlew.bat --no-daemon clean build
python tools/check-script-ui-contract.py
python tools/build-script-strings.py --check
pwsh -NoProfile -File ../tools/check-script-sync.ps1
pwsh -NoProfile -File ../tools/lint-groovy-scripts.ps1
```

Gradle, `build/libs/qupath-extension-workshop-0.2.2-rc9.jar` ve `build/reports/tests/test/index.html` üretir. Diğer komutlar yalnız denetler. Türkçe/İngilizce iletiler `script-core.json` kaynağından iki properties dosyasına üretilmiştir. Davranış parmak izi bu notla ve önceki SHA-256 değeriyle birlikte kaydedilmiştir; denetleyicinin kuralları değiştirilmemiştir.

| Kontrol | Sonuç | Kanıt |
|---|---|---|
| `clean build` | **geçti** | 15 sınıf, 282 test; 0 başarısız, 0 hata, 0 atlanan. Dört yeni maske testi dahil. |
| Son sözleşme kayıtlarıyla `assemble` | **geçti** | JAR içindeki maske betiği, sözleşme JSON'u ve iki dil paketi kaynak dosyalarıyla byte düzeyinde eşit. |
| Kaynak sözleşmesi ve üretilmiş dil paketi | **geçti** | 102 betik sözleşmesi, 5814 Türkçe/İngilizce ileti çifti. Önceki HEAD'deki 15 sapmanın incelemesi `accuracy-contract-review-2026-11-16.md` içinde. |
| Betik eş kopyaları ve Groovy lint | **geçti** | 103 betik ve paketlenmiş Python köprüleri. |
| Paketlenmiş maske betiği, gerçek QuPath 0.6.0 ve 0.7.0 CLI | **geçti** | `tools/test-mask-import-geometry.groovy`: tüm betik derlendi, üretim ölçek/boyut yardımcıları çalıştırıldı; GUI açılmadı. |
| Gerçek QuPath 0.6.0 CLI, sentetik ilk hücre tespiti | **geçti** | `tools/test-synthetic-first-run.groovy`: yerel rc9 JAR'da 100 nesne ve dolu ölçüm satırları. |
| Yayımlanmış rc8 ile aynı ilk çalıştırma | **geçti** | GitHub SHA-256 özetiyle doğrulanan rc8 JAR + gerçek QuPath 0.6.0 CLI: `tools/test-synthetic-first-run.groovy` 100 ayrı nesne ve dolu ölçüm satırları üretti; çıkış kodu 0. Sistem eklentisi değiştirilmedi. |
| GUI, gerçek preparat, katılımcı kabulü | **çalıştırılmadı** | Ayrı kullanıcı kabulü gerekli. |

Son yerel JAR SHA-256: `2dce59c3190b98208ca5184f15dd5f7efa8240327b05e7f12c79d58b7a7e090b`. Bu değer her derlemede değişebilen tarih üst verisini de içerir; yayımlanmış sürüm kimliği değildir.

Yayımlanmış rc8 JAR SHA-256: `1ad657556c455a8cde1f432d9a1655e450bd792514313871a525caffa3148969`. Başlangıç deneyi `WORKSHOP_EXAMPLE_JAR` ile bu arşive yönlendirildi. Bu deney yeni maske korumalarının rc8'de bulunduğunu göstermez; bu nottaki maske değişiklikleri yerel rc9 adayına aittir.

Gerçek QuPath arayüzünde pencere, dosya seçimi, önceki sonuçlarla ekleme/değiştirme ve kullanıcı iptali **çalıştırılmadı**. Sonraki adım: gri ve indeksli PNG ile içe aktarmayı, RGB reddini, hatalı yüksekliğin uyarısını ve geçersiz JSON ölçeğinin reddini arayüzde denemek. Gerçek preparat ve katılımcı denemesi yapılmadı.
