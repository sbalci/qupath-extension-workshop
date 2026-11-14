# Yardımcı pencere metinleri — 14 Kasım 2026

Yerel inceleme dalı: `feat/course-audit-followup-20261114`. Push, sürüm,
dağıtım, GUI eklenti kurulumu veya model indirmesi yapılmadı.

## Değişiklik ve kaynak sınırı

Dört yardımcının 169 sabit metin çağrısı mevcut TR/EN kaynağına bağlandı:
kalibrasyon, boya vektörü, üst veri ve Python ortam yöneticisi. Katalog artık
12 betik ve 255 çift metin içerir. Hücre tespiti anahtarları ayrı kalır.
İngilizce ilk çalışma belgesi kalibrasyonun yeni etiketleriyle eşlendi.

On iki betikten yalnız onaylı dil yardımcı fonksiyonu ve `workshopText`
çağrıları çıkarıldığında önceki kaynak SHA-256 değerleri elde edilir. Dört yeni
betik için temel commit `6c648c9`, sekiz önceki betik için `cdb47e2`'dir.
Sayısal değerler, komutlar, durum/seçenek kimlikleri, callback'ler ve çıktı
sütunları bu karşılaştırmaya dahildir. Sayısal eşiği veya dil yardımcı
fonksiyonunu değiştiren olumsuz denemeler kontrol tarafından yakalandı.

## Çalıştırılan kontroller

| Kontrol | Kanıt / sonuç |
|---|---|
| `check-script-sync.ps1`, `lint-groovy-scripts.ps1` | 103 Groovy ve 31 Python eşi güncel; dört düzenlenen betik byte-identical. Varsayılan parametre kontrolü geçti. |
| `build-script-strings.py --check` | 255 çift metin ve 12 kaynak koruma kaydı geçti; bu kontrol CI'ın mevcut üretici adımında da çalışır. |
| Java 21, çevrimdışı Gradle `clean build` | Commit edilmiş kaynak için 64 test; sıfır hata, başarısızlık veya atlama. |
| Groovy AST kaynak kontrolü | Her betikteki gerçek lookup anahtarı ile Türkçe fallback'in katalogda aynı satıra karşılık geldiği doğrulandı. |
| Biçim ve kodlama | TR/EN printf yer tutucuları/duyarlıkları eş; `0.2600 µm/px`, sentetik klasör yolu ve `12.5 GB` iki dilde doğrulandı. Özellik dosyalarındaki anlamlı sınır boşlukları Unicode kaçışlarıyla korunur. |
| QuPath 0.6.0 ve 0.7.0 | Her hostta 103 paketli betik parse edildi; betikler bu kontrolde çalıştırılmaz. |
| Gerçek dil yardımcı fonksiyonu | Her hostta 6.170 TR/EN lookup ve eklentisiz Türkçe fallback geçti. Test JAR kimliği URLClassLoader kaynağıyla denetlendi; GUI açılmadı, dil tercihi yazılmadı. |
| Sentetik ilk çalışma | Her hostta 100 ayrı nesne/kapalı kontur ve dolu ölçüm satırları; gerçek doku başarımı ölçülmedi. |

CLI ile sınanan üretim kaynağı: `7b3c8e7`.
JAR SHA-256: `48578c3d5d437b47ec0fa50b48e404196b52b8cbf2a618b5809808fa0b1015d1`.
Sonraki `e1a0384` yalnız bir test dosyasını değiştirir: dil testinde kalıcı
`setLanguage` çağrısı yerine önceki değeri geri yükleyen süreç belleği kullanılır. Son commit de ayrı çalışma kopyasında temiz derlendi; 64 test geçti. Bu JAR ile CLI-testli JAR arasında yalnız `build-info.properties` derleme zamanı farklıdır; bütün çalışan sınıf ve kaynak içerikleri aynıdır. Son JAR SHA-256: `e4a5aaacccb2cbe4159d6e1aa4fb7f65c3a47fe4ebdf6f7c36f9eae00f74048e`.

## Görülmeyen yollar

Computer Use `@oai/sky` ile yeniden denendi; `list_windows` yine native pipe
bulunamadığını bildirdi (`os error 2`). Bu bir otomatik onay reddi değildir.
GUI görünümü, canlı dil değiştirme, iptal/üzerine-yazma, ortam kurma/onarımı ve
model çalıştırma bu sonuçlarla doğrulanmış sayılmaz. QuPath başlangıçlarındaki
önceden var olan üçüncü taraf eklenti uyarıları değiştirilmedi.

Dinamik/interpolasyonlu sonuç ve log metinleri, ortam kataloğundaki açıklamalar
ve diğer yardımcılar bütünüyle çevrilmiş sayılmaz. Dil değişiminden sonra
pencereyi kapatıp yeniden açın. Eş zamanlı diğer kaynak düzenlemeleri korunarak
commit dışında bırakıldı. Bu değişiklik yalnız betik ve bakım belgelerini
etkiledi; Quarto sayfa/varlık kaynağı değiştirilmedi.
