# Kalan betik pencerelerinin dili — 15 Kasım 2026

Yerel inceleme dalı: `feat/course-audit-followup-20261114`. Ortak katalog artık
102 betikte 2.096 TR/EN metin içerir. Hücre tespiti betiği ayrıca kendi 25 ortak
dil anahtarını kullanır; böylece paketli 103 betiğin hepsinin dil yolu vardır.
Bu, her betiğin bütün rapor ve veri gövdelerinin çevrildiği anlamına gelmez.

## Değişiklik ve kaynak

39 betiğin sabit ve dinamik görüntüleme metinleri ortak dil kaynağına bağlandı.
Model kurulumu/çalıştırma rehberleri, hücre tespiti araçları, çekirdek boyası,
ER/PR H-skoru, model eğitimi, görüntü aktarımı, hizalama, ICC, karo ve maske
araçları bu gruptadır. Boya vektörü, üst veri ve Python ortam yöneticisindeki
dokuz sabit rehber/seçici metni de tamamlandı. Kod commit'i `6a245aa`.

Komutlar, sayısal varsayılanlar, koşullar, tercih anahtarları, sınıf/model
kimlikleri, CSV/JSON alanları, iptal/silme/üzerine yazma kararları bu çeviriyle
değiştirilmedi. Kaydedilen Türkçe seçenek kimlikleri ve bunlara yapılan
karşılaştırmalar korundu; İngilizce rehberde gerçek menü ve katalog adları
kullanıldı. Kullanıcı adına model indirilmedi veya lisans kabul edilmedi.

Önceden commit edilmiş 11 kaynak değişikliği çeviriden önce ayrı incelendi;
özgün kaynak bilgisi korunan bir inceleme kaydıyla eşlendi (`8766c31`).
[Kaynak eşleme belgesi](source-baseline-review-2026-11-15.md), bu işlemin
algoritma değişikliği veya klinik doğrulama olmadığını açıklar. Yeni 39 kaynak
sözleşmesinin başlangıcı `53c98af`; çeviri çağrıları kaldırılınca aynı kaynak
geri elde edilir. Kontrol, sonraki bir kaynak farkını yine hata sayar.

ICC penceresindeki eski genel açıklama önce resmî QuPath sürüm kaydı ve PR ile
kontrol edilip düzeltildi (`53c98af`), ardından çevrildi. QuPath 0.7'de OpenSlide
için isteğe bağlı ICC uygulaması vardır; dosyada profil bulunması açık görüntüde
dönüşümün etkin olduğunu göstermez. Denetçi, etkin dönüşümü ölçtüğü iddiasında
bulunmaz. Piksel/tercih/ölçüm kodu değiştirilmedi.
[Kaynak kontrolü ve bağlantılar](icc-ui-source-review-2026-11-15.md).

## Doğrulama

Temiz commit kaynağı `6a245aa`, Java 21 ile
`gradlew.bat --offline --no-daemon --console=plain clean build` komutuyla
derlendi. Test edilen JAR'ın SHA-256 değeri:
`c329154fc4262992a772deacf7306601cdde564b6aea20ca19df0627ca8632f2`.
Sürüm artırılmadı; bu yayımlanmış bir sürüm değildir.

| Kontrol | Sonuç |
|---|---|
| JUnit | 67 test; sıfır hata, başarısızlık ve atlama. |
| `python tools/build-script-strings.py --check` | 102 kaynak sözleşmesi ve 2.096 çift metin geçti. Üretilen TR/EN dosyaları güncel. |
| Kaynak/AST | Anahtar–Türkçe fallback, biçim yer tutucuları, özgün veri ifadeleri, koşullar, sıra ve ertelenen closure davranışı geçti. |
| Dinamik metin | Toplam 62 GString kullanımı / 61 benzersiz ifade. |
| QuPath 0.6.0 | 103 paketli betik `GroovyClassLoader.parseClass` ile derlendi; hiçbiri çalıştırılmadı. 427.634 TR/EN lookup ve eklentisiz Türkçe fallback geçti. |
| QuPath 0.7.0 | Aynı 103 derleme ve 427.634 lookup/fallback kontrolü geçti. |
| Her iki hostta gerçek dil yardımcısı | 61 ifade için 244 TR/EN sentetik render ve 12 kapsam kontrolü; eklentisiz 122 render ve 6 kapsam kontrolü geçti. |
| Betik aynaları/lint | Temiz commit kaynaklarından kurulan kopyada ve kontrol anındaki ana çalışma ağacında 103 Groovy/31 Python eşi, sınıflandırıcı eşliği, Groovy lint ve tercih varsayılanları geçti. |
| Türkçe | 328 dosyalık terim kontrolü geçti. |

QuPath komutları `tools/check-packaged-script-syntax.groovy`,
`tools/test-script-ui-bundles.groovy` ve `tools/test-script-ui-expressions.groovy`
ile çalıştırıldı. Lookup araçları test JAR'ını açıkça yükleyip kaynak kimliğini
kontrol eder; kurulu başka bir JAR'ın sonucunu kullanmaz. Dil alanı yalnız test
sürecinin belleğinde değiştirilip geri alınır; kullanıcı tercihi yazılmaz.
Mevcut JPen native kitaplığı ve yinelenen Warpy JAR uyarıları test başarı
satırlarından ayrı tutuldu; başka eklentilerin kurulumu değiştirilmedi.

## Eş zamanlı düzenlemeler ve sınırlar

Doku tespiti, hotspot, HoVer-NeXt ve Ki-67 heterojenlik betiklerinde çalışma
sırasında gelen başka kaynak düzenlemeleri korundu. Çeviri, temiz kaynaktan
hazırlanan yamayla ayrı commit edildi; bu düzenlemeler o commit'e alınmadı.
Doku tespitindeki yeni `hemHint` satırı çevrilmiş etiketlerle birleştirildi;
dil çağrıları kaldırılınca çalışma kaynağının aynen korunduğu kontrol edildi.
Yukarıdaki JAR testleri commit kaynağına aittir; eş zamanlı algoritma
düzenlemeleri için test veya onay anlamına gelmez.

Computer Use bağlantısı yeniden denendi; native pipe bulunamadı (`os error 2`).
Bu bir Windows izin/onay reddi değildir. Gerçek GUI yerleşimi, dil değiştirip
pencereyi yeniden açma, iptal/üzerine yazma tıklamaları ve fiziksel projektör
kontrolü tamamlanamadı. Kalıcı ayar, kullanıcı lamı, hesap ve model kurulumu
kullanılmadı; model başarımı veya klinik doğruluk iddiası eklenmedi.

Diske de yazılan rapor/komut/günlük gövdeleri, veriden/katalogdan gelen adlar ve
bazı değişkende oluşturulan açıklamalar bu görüntüleme çevirisinin dışındadır.
Bu turda Quarto sayfa/görsel kaynağı değiştirilmedi; başka çalışmanın mevcut
sayfa ve site çıktıları korundu. Push, tag, sürüm veya dağıtım yapılmadı.
Bekleyen terim kararı yoktur.
