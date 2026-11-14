# Dinamik pencere metinleri — 15 Kasım 2026

Dal: `feat/course-audit-followup-20261114`. Yerel inceleme; push, sürüm,
GUI eklenti kurulumu, model indirmesi veya dağıtım yapılmadı.

## Kapsam

Dokuz betikte 188 ek görüntüleme ifadesi çevrildi: 151 sabit ifade ve
37 GString kullanımı (36 benzersiz ifade). Katalog 12 betik/379 çift metindir;
hücre tespiti anahtarları ayrıdır. Kontrol listeleri, sayaçlar, sınıflandırıcı
adları, piksel boyutu ve işlem kapsamı cümleleri bu kapsamdadır.

GString'in yalnız sabit parçalarına mevcut dil lookup'u yerleştirilir.
Değer ifadelerinin kaynakları ve sırası korunur; sonuç GString olarak kalır.
Tüm lam/seçili bölge ve sınıflandırıcı yok etiketi yalnız koşullu ifadenin
sonuç kollarında çevrilir; koşul, veri adı ve işlem kapsamı kararı değişmez.
[Groovy'nin closure interpolasyonu belgesi](https://groovy-lang.org/syntax.html#_special_case_of_interpolating_closure_expressions)
değerin metne çevrilme zamanındaki ayrımı açıklar; testler bunu gerçek yardımcı
fonksiyon ve sentetik closure/sayı/yol değerleriyle sınar.

Diske yazılan özetler, loglar, CSV/JSON biçimleri, sınıf/model kimlikleri,
sayısal varsayılanlar ve analiz callback'leri değiştirilmedi. Kaynak koruma
kayıtları önceki SHA-256 değerlerini aynen kullanır. Kontrol yalnız hash'e
dayanmaz: Groovy AST testleri lookup'ları sabit parçalara geri katıp özgün
değer kodunu/konumunu karşılaştırır. Değiştirilmiş veri/kapsam koşulu ve yanlış
Türkçe fallback denemeleri reddedildi.

## Doğrulama

Commit edilmiş kaynak: `66ca2de`.
JAR SHA-256: `56fd1f7b3084c691f15f159ef0f899843f043302e931d4520c31964ee0e812f9`.
Eş zamanlı diğer kaynak düzenlemeleri ayrı bırakılarak temiz çalışma kopyasında
Java 21 / çevrimdışı Gradle `clean build` çalıştırıldı.

| Kontrol | Sonuç |
|---|---|
| JUnit | 67 test, sıfır hata/başarısız/atlanmış. |
| Kaynak koruma | 12 betikte önceki kaynak SHA eşliği; gerçek anahtar–Türkçe fallback ve printf biçimleri eş. |
| Groovy envanteri | Tüm GString, sabit parça ve değer kaynakları ayrılır; bazı birleşimlerde yalnız `+` işaretini kapsayan Groovy konum aralığı operand sınırlarıyla düzeltilir. |
| QuPath 0.6.0 ve 0.7.0 | Her hostta 103 betik parse edildi; bu adım betikleri çalıştırmaz. |
| Gerçek lookup | Her hostta 9.146 TR/EN lookup ve eklentisiz Türkçe fallback geçti. |
| Gerçek dinamik cümleler | Her hostta 36 benzersiz ifade, 144 TR/EN sentetik render ve 12 gerçek kapsam/boş-liste kontrolü geçti. |
| Eklentisiz dinamik fallback | Her hostta aynı 36 ifade için 72 sentetik render ve 6 kapsam kontrolü geçti. |
| Değerlendirme/veri | GString türü, ertelenen closure'ın her metne çevirmede bir kez çalışması, sayılar, Unicode yollar, `$`, tırnaklar, parantezler ve model adlarının korunması sınandı. Kalıcı dil tercihi yazılmadı. |
| Sentetik ilk çalışma | Her hostta 100 nesne, ayrı konturlar ve dolu ölçüm satırları. Gerçek doku başarımı değildir. |
| Aynalar/lint | 103 Groovy ve 31 Python eşi, varsayılan parametre kontrolü ve dil kaynağı üreticisi geçti. |

## Sınırlar

Computer Use `list_windows` yeniden denendi: native pipe yok (`os error 2`).
Bu bir onay reddi değildir. GUI yerleşimi, canlı dil değiştirme, iptal/üzerine
yazma ve ortam/model kurma bu testlerle doğrulanmış sayılmaz. CLI'da önceden
mevcut üçüncü taraf eklenti başlangıç uyarıları sürdü; bu kurulumlar değiştirilmedi.

Dosyaya da yazılan özetler, ortam kataloğu açıklamaları ve diğer yardımcılar
tamamen çevrilmiş sayılmaz. Kaynaklarından gelen tanı/veri değerleri aynen
gösterilir. Quarto sayfa veya görsel kaynağı değişmedi; bu turda site çıktıları
yeniden üretilmedi. Önceki 14 Kasım kayıtları o günkü kod ve test kapsamını anlatır.
