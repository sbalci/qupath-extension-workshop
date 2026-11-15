# Yardımcı pencerelerin dili — 15 Kasım 2026

Yerel dal: `feat/course-audit-followup-20261114`. 48 yeni yardımcıda 818
görüntüleme ifadesi çevrildi: 809 sabit ifade ve 9 GString. Önceki metinlerle
birlikte katalog 63 betik/1.006 TR/EN metindir; hücre tespiti anahtarları ayrıdır.
Bu sayı, 63 betiğin her rapor/veri gövdesinin tamamen çevrildiği anlamına gelmez.

## Kapsam ve korunan davranış

Anotasyon içe/dışa aktarma, F1 doğrulama, eşik paleti, grafik, görüntü yakalama,
görüntü tipi, uzamsal ölçümler, mitoz model listesi/karşılaştırması, OME-Zarr,
örnek sınıflandırıcı ve eğitim yardımcılarının düğme/rehber/uyarı metinleri
aynı dil kaynağına bağlandı. Yeni kaynak sözleşmelerinin başlangıcı `7de156c`;
çeviri çağrıları çıkarılınca bu kaynağın SHA-256 eşliği korunur.

Seçim değerleri, `userData`, nesne kimliğiyle yapılan karşılaştırmalar, tercih
anahtarları, sayısal varsayılanlar, komutlar, sınıf/model adları ve CSV/JSON
alanları değiştirilmedi. Silme, üzerine yazma, iptal ve mevcut anotasyonları
koruma koşulları aynı kaldı. Görüntüleme sayıları ve biçim yer tutucuları iki
dilde aynı sırada sınandı. Yeni dokuz GString'de özgün veri ifadeleri/sırası ve
ertelenen metne çevirme davranışı Groovy AST ile karşılaştırıldı.

CytoFormer'da önceki turdan bekleyen iki metin de sabit revizyon metadata'sıyla
doğrulanıp çevrildi; ayrıntı [kaynak kontrolündedir](cytoformer-source-review-2026-11-15.md).
Genel yardımcı değişikliği `866a223`, kaynak doğrulaması sonrası ek düzeltme
`8ebd6e4` commit'indedir. Push, sürüm veya dağıtım yapılmadı.

## Eş zamanlı düzenlemeler

Ana çalışma ağacında başka işlemlerin kaynak düzenlemeleri sürüyordu.
`yardimci-hotspot-sirali.groovy`, yeni değişikliği görülünce bu turdan çıkarıldı.
Mitoz karşılaştırma ve önceki çeviri grubundaki bazı dosyalara daha sonra gelen
düzenlemeler korundu. Commitler temiz kopyadan hazırlanan yalnız bu çalışmanın
yamalarıyla oluşturuldu; çalışma dosyalarının diğer içeriği commit'e alınmadı.

Ana ağaçta yapılan genel kontrol, bazı Groovy/Python aynalarında fark ve hotspot
betiğinde bir lint sorunu bildirdi. Bu sonuç başarı diye kaydedilmedi. Çevirinin
doğrulaması, commit kaynaklarını kullanan ayrı temiz kopyada yapıldı.

## Doğrulama

Son kaynak commit'i: `8ebd6e4`.
JAR SHA-256: `4de691502b71c6f4fc07d358a97419cee68f9348876f6aa75a2c55e79d7517a0`.
Java 21 ile `gradlew.bat --offline --no-daemon --console=plain clean build`
temiz kaynak kopyasında çalıştırıldı. QuPath araçları bu JAR'ı açıkça yükleyip
kimliğini kontrol eder; kurulu başka bir JAR'ın sonucunu kullanmaz.

| Kontrol | Sonuç |
|---|---|
| JUnit | 67 test; sıfır hata/başarısız/atlanmış. |
| Kaynak/dil eşliği | 63 kaynak sözleşmesi, 1.006 çift metin, gerçek AST anahtar–Türkçe fallback ve biçim denetimleri geçti. |
| Kritik uyarılar | Aktarım önizlemesindeki beş sayı ve Unicode klasör yolu aynı sırada; anotasyon/tespit silme uyarılarının sayıları ve önceki model uyarıları korundu. |
| Dinamik cümleler | Toplam 46 kullanım/45 benzersiz GString; veri kodu, tür, sıra ve ertelenen closure denetimleri geçti. |
| QuPath 0.6.0 | 103 betik parse; 126.806 TR/EN lookup ve eklentisiz Türkçe fallback geçti. Betiklerin görüntü işlemleri çalıştırılmadı. |
| QuPath 0.7.0 | Aynı 103 parse ve 126.806 lookup/fallback kontrolü geçti. |
| Gerçek hostta dinamik helper | Her hostta 45 cümle için 180 TR/EN sentetik render ve 12 kapsam kontrolü; eklentisiz 90 render ve 6 kapsam kontrolü geçti. |
| Aynalar/lint | Commit kaynaklarından kurulan ayrı kopyada 103 Groovy ve 31 Python eşi, Groovy lint ve tercih varsayılanları geçti. |
| Türkçe | 328 dosyalık terim kontrolü geçti. |

İlk yardımcı commit'i `866a223` ayrıca 67 test ve her hostta 126.554 lookup ile
geçmişti. Sonraki iki CytoFormer metni eklendikten sonra yukarıdaki temiz derleme
ve iki-host kontrolleri son JAR üzerinde tekrarlandı.

## Sınırlar

Computer Use `list_windows` tekrar native pipe bulunamadı (`os error 2`) verdi.
Bu bir Windows onay reddi değildir. Gerçek GUI yerleşimi, canlı dil değiştirme,
iptal/üzerine yazma düğmelerinin tıklanması ve fiziksel projektör doğrulanamadı.
Kalıcı dil tercihi değiştirilmedi; kullanıcı lamı, hesap veya model kurulumu
kullanılmadı. Model başarımı veya klinik doğruluk iddiası eklenmedi.

Diske de yazılan rapor/komut/günlük gövdeleri, katalogdan/veriden gelen adlar ve
kalan yardımcılar bütünüyle çevrilmiş sayılmaz. Eş zamanlı algoritma değişiklikleri
bu dil çalışmasının onayı değildir. Quarto sayfa/görsel kaynağı değişmediği için
bu turda site çıktıları yeniden üretilmedi. Bekleyen terim kararı yoktur.
