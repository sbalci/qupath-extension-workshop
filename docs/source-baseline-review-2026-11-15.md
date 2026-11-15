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
