# Model sihirbazlarının pencere dili — 15 Kasım 2026

Yerel dal: `feat/course-audit-followup-20261114`. Kaynak commit: `0c0cd92`.
Push, sürüm, dağıtım, GUI kurulumu ve model/ortam indirmesi yapılmadı.

## Kapsam

Classpose, CytoFormer ve GrandQC pencerelerine sırasıyla 105, 105 ve 102
görüntüleme lookup'u eklendi. Katalog 15 betik/565 TR/EN metindir; hücre tespiti
anahtarları ayrıdır. Bu ek 312 çağrı yalnız sabit metinleri kapsar. Önceki
[dinamik pencere doğrulamasının](dynamic-ui-validation-2026-11-15.md) 37 GString
kullanımı ve veri/değerlendirme sözleşmeleri aynen korunur.

Kurulum adımları, eksik dosya/ortam durumları, klasör/dosya seçici başlıkları,
çalıştırma/iptal düğmeleri ve mevcut sonuçların değiştirilmesi soruları çevrildi.
Envanter artık `addGuidance`, `addWarnLabel`, `section`, `statusLine`, bilgi
bildirimleri ve dosya seçici başlıklarını da görür; yöntem gövdeleri taranır.
`WORKSHOP_UI_ALL_TEXT=true` seçeneği, Türkçe karakter içermeyen kısa etiketlerin
de gözden kaçmaması için dil filtresini kaldırır. Bu araç betikleri çalıştırmaz.

Çevrilen çağrılar çıkarılınca üç betik `a93a211` kaynağına geri döner.
Sayısal varsayılanlar, komutlar, tercih anahtarları, sınıf/model kimlikleri,
onay kutusunun durumu, seçim/değiştirme koşulları ve çıktı sütunları değişmedi.
İngilizce menü yolu kodun sabit `Atölye` adını ve gerçek `Utilities` / `Python
bridges & foundation models` etiketlerini kullanır. Ortam kataloğundaki Türkçe
girdi adları yönergelerde aynen alıntılanır; olmayan İngilizce düğme adı üretilmez.

## Kaynak incelemesi gerektiren iki metin

CytoFormer'daki `③ Ağırlık (checkpoint.pth, CC BY-NC 4.0, kapılı)` başlığı ve
Hugging Face erişimi için “otomatik onay” diyen paragraf çeviri dışında tutuldu.
Güncel [resmî model kartı](https://huggingface.co/zhihuanglab/CytoFormer), Penn
Academic Software License adını ve erişim koşullarını kabul etme gereğini
gösteriyor. [Kodun lisansı](https://github.com/zhihuanglab/CytoFormer/blob/main/LICENSE)
da Penn metnidir. Sabitlenmiş `3e9691e` ağırlık revizyonunun kapılı README'si
bu oturumda okunamadı; eski revizyonun koşulları hakkında sonuç çıkarılmadı.
Bu iki ifade doğru/güncel diye onaylanmış değildir. İndirme veya lisans kabul
akışı değiştirilmedi; kullanıcı adına koşul kabul edilmedi.

Diğer yeni metinler mevcut açıklamaların çevirisidir; yeni klinik iddia,
eşik veya başarım vaadi eklenmedi. İlgili özgün kaynaklar:
[Classpose](https://github.com/sohmandal/classpose),
[GrandQC](https://github.com/cpath-ukk/grandqc).

## Doğrulama

Test JAR'ının SHA-256 değeri:
`7e06232c0e9d9d234a60891e84ab7b6bd1cfa5346aca652de3b73680032da051`.
Kaynak `0c0cd92`, diğer eş zamanlı düzenlemelerin bulunmadığı temiz çalışma
kopyasında Java 21 ile `gradlew.bat --offline --no-daemon --console=plain clean build`
komutuyla derlendi. Gerçek host kontrolleri bu JAR'ı açıkça yükleyip kimliğini
denetler; kurulu üçüncü taraf eklentilerin kopyasını test JAR'ı saymaz.

| Kontrol | Sonuç |
|---|---|
| JUnit | 67 test, sıfır hata/başarısız/atlanmış. |
| Katalog/fallback/biçim | 565 anahtar iki dilde eş; gerçek AST çağrıları Türkçe fallback ile eşleşir. Yeni üzerine yazma uyarılarında 17/31 özet–tespit sırası ve 126 hücre sayısı korunur. |
| Kaynak koruma | 15 betiğin özgün SHA eşliği ve dil üreticisi `--check` geçti. |
| Önceki dinamik metinler | 37 GString kullanımı; veri kodu, değerlendirme sırası, ertelenen closure, sayı/yol ve koşul testleri aynı temiz derlemede geçti. |
| QuPath 0.6.0 | 103 betik parse; 17.000 TR/EN lookup ve eklentisiz Türkçe fallback geçti. Betiklerin görüntü/model işlemleri çalıştırılmadı. |
| QuPath 0.7.0 | Aynı 103 parse ve 17.000 lookup/fallback kontrolü geçti. |
| Aynalar/lint | 103 Groovy ve 31 Python eşi, Groovy lint ve tercih varsayılanları geçti. |
| Türkçe | 328 dosyalık terim kontrolü geçti; dört ortak sözlük kopyası değişmedi. |

Biçim denetimi artık yalnız Java Formatter'ın geçerli dönüşümlerini sayar;
normal Türkçe metindeki `% ve` dizisini parametre sanmaz. Biçim grameri
[Java 21 belgesinden](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Formatter.html)
kontrol edildi. Bu düzeltme çalışma zamanı biçimlemesini değiştirmez.

## Sınırlar

Diske de yazılan rapor/komut/günlük gövdeleri, ortam kataloğu açıklamaları,
modelden gelen sınıf adları ve diğer yardımcılar tamamen çevrilmiş sayılmaz.
Bu üç model gerçek lamda çalıştırılmadı; ortam/başarım/kurulum testi değildir.
Önceki GUI köprüsü hatası (`os error 2`) sürmektedir; bu bir Windows onay
reddi değildir. Canlı GUI yerleşimi, iptal ve üzerine yazma doğrulanamadı.
Quarto sayfa/görsel kaynağı değişmedi; site çıktıları yeniden üretilmedi.
