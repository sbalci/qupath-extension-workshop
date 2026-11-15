# Commit edilmiş kaynaklarla dil sözleşmesinin eşlenmesi

15 Kasım 2026. Başlangıç eklenti commit'i: `30c1f81`.
Çalışma ağacı temizdi. Önceki dil turundan sonra commit edilmiş düzeltmeler,
11 betiğin çeviri dışındaki içeriğini değiştirmişti. Dil üreticisi bu nedenle
`non-UI source changed` ile duruyordu.

Her betiğin önceki kaydı `git show <revision>:<dosya>` ile okundu; bu içeriğin
eski SHA ile eşliği kontrol edildi. Sonra güncel kaynakta yalnız onaylı dil
çağrıları kaldırılarak iki kaynak karşılaştırıldı. İncelenen farklar:

| Betik | Zaten commit edilmiş fark |
|---|---|
| `sitoplazmik-boya.groovy` | Rapora paydanın tespit edilen tüm hücreler olduğu açıklaması eklenmiş. |
| `yardimci-metadata-sihirbaz.groovy` | Tüm görüntü alanı, doku alanı yerine tarama alanı olarak adlandırılmış. |
| `yardimci-cytoformer-sihirbaz.groovy` | Sınıflanmayan/aktarılmayan hücrelerin yoğunluk raporunun payını sınırladığı açıklanmış. |
| `yardimci-anotasyon-qc.groovy` | Tam iç içe anotasyon çiftleri, kısmi örtüşme sorunlarından ayrılmış. |
| `yardimci-boya-kalite-qc.groovy` | Seçili poligon dışındaki pikseller maske ile örneklem dışında tutulmuş. |
| `yardimci-dogrulama-f1.groovy` | Hücre nesnelerinde çekirdek ROI'si varsa eşleştirmede o ROI kullanılmış. |
| `yardimci-goruntu-kunye.groovy` | Alan ROI'si içinde piksel istatistiği için maske ve bölge açıklaması eklenmiş. |
| `yardimci-kohort-ozet-topla.groovy` | Aynı adlı kilitli anotasyonlar `(2)`, `(3)` sütunlarında korunmuş. |
| `yardimci-mitoz-karsilastir.groovy` | Model başına ROI çözünürlüğü/çıktısı ayrılmış; FCOS/RetinaNet ve HoVer-NeXt kendi kayıtlı ölçeğini kullanmış. |
| `yardimci-peritumoral-bant.groovy` | Dış bantlar komşu kaynak yapıdan/görüntü dışından kırpılmış; birleşim alanı ayrıca raporlanmış. |
| `yardimci-tma-cekirdek-aktarim.groovy` | Eksik alan/yüzde/yoğunluk boş TSV hücreleriyle temsil edilmiş; missing çekirdekler toplamdan çıkarılmış. |

Bu kayıt bu algoritmaları değiştirmez; commit edilmiş mevcut durumu sonraki
çevirinin başlangıcı olarak korur. Klinik başarım veya gerçek lam doğrulaması
bu dil incelemesinin sonucu değildir. İlgili kod değişikliklerinin kökeni
`ff3ac97` ve `30c1f81` commit'leridir.

`script-core.json` her dosyanın özgün `revision` bilgisini korur. Yeni
`baseline_reviews` kaydı önceki SHA'yı, yeni SHA'yı, incelenen commit'i ve bu
belgeyi taşır. Etkin `sha256` güncel, dil çağrıları çıkarılmış kaynağa aittir.
Denetim gevşetilmedi; sonraki bir çeviri dışı fark yine hata üretir. Betiklerin
veri/işlem kodu bu eşleme sırasında yazılmadı. Kaynak ve paketli ayna kopyaları
değiştirilmedi.

## Tüm kalan düzenlemelerin commit edilmesi

15 Kasım 2026. Kullanıcının tüm kalan değişiklikleri commit etme talebiyle,
önceden ayrı tutulan dört betik düzenlemesi `cc8d89a` commit'ine alındı.
Önceki kaynakların dil çağrıları çıkarılmış SHA değerleri eski sözleşmelerle
karşılaştırıldı; sonra aşağıdaki farklar ayrı incelenerek yeni kaynak kaydı
eklendi. Özgün `revision` ve önceki SHA geçmişi korunur.

| Betik | İncelenen fark |
|---|---|
| `yardimci-doku-tespiti-sihirbaz.groovy` | Hematoksilen tabanının kapsamı/fallback'i raporda ve seçime bağlı bir GUI etiketinde açıklanır; seçim dinleyicisi etiketi yeniler. Piksel/eşik hesabı aynı kalır. |
| `yardimci-hotspot-sirali.groovy` | Mevcut örneklem SD hesabının ölçüm adı `Top-N karo SD, n-1 (…)` olur; eski n bölenli çıktıyla karışması önlenir. Hesap değişmez. |
| `yardimci-hovernext-sihirbaz.groovy` | Mevcut `roi.getArea() * cal.pw * cal.ph` ölçümünün adı `Çekirdek alanı, piksel kenarı (µm2)` olur; açıklama aynı ada eşlenir. Hesap değişmez. |
| `yardimci-ki67-heterojenlik.groovy` | Mevcut örneklem SD/CV ölçüm adları `n-1` taşır; rapor CV etiketi de bu tanımı gösterir. Hesap değişmez. |

Bu ölçüm adı değişiklikleri çıktı şemasını bilinçli olarak değiştirir;
önceki çeviri commit'inin veri adlarını koruduğu kaydı kendi kaynağı için
geçerlidir. TR/EN ders sayfaları ve mevcut site çıktıları yeni adları açıklar.
Hematoksilen uyarısı değişkende oluşturulduğundan mevcut dil kataloğunun
kapsamı dışındadır; bu adım yeni bir tam çeviri veya klinik doğrulama değildir.

Bu son kaynakta `build-script-strings.py --check` ile 102 sözleşme/2.096 çift
metin ve Java 21 ile temiz Gradle derlemesinde 67 test geçti (hata/başarısızlık/
atlama yok). QuPath 0.6.0 ve 0.7.0 içinde 103 paketli betiğin tamamı derlendi;
betiklerin görüntü/GUI işlemleri çalıştırılmadı. Son test JAR'ının SHA-256 değeri
`49698031125b5734b0533c16c5db6b3ab4d4eec8a07f0b08891896a0a3a3d25c`.
Betik aynaları, Groovy lint ve tercih varsayılanları kontrolleri de geçti.
