# Önceki doğruluk düzeltmelerinin kaynak sözleşmesi — 16 Kasım 2026

Başlangıç HEAD'i `a43b788b45d1d853472c79cd1a33704ba27fe8c2` idi. Bu commit 16 betikte doğruluk düzeltmeleri içeriyordu; `hucre-tespiti.groovy` ayrı çeviri düzenini kullandığından `script-core.json` kapsamında değildir. Kalan 15 betiğin davranış parmak izi yenilenmediği için `check-script-ui-contract.py` ilk olarak `membran-boya.groovy` üzerinde duruyordu. Bu durum bu çalışmanın maske değişikliğinden önceki HEAD metninde yeniden üretildi.

Her dosya için eski sözleşme SHA-256 değerinin **`HEAD^` kaynak metnine**, UI sarmalayıcıları kurala göre kaldırıldıktan sonra, tam eşit olduğu denetlendi. Ardından `git show a43b788 -- src/main/resources/scripts` farkının tamamı incelendi. Güncellemeler aşağıdaki davranış değişikliklerini kaydeder; kaynak betikleri tekrar değiştirmez. `baseline_reviews` içine eski/yeni SHA-256, commit ve bu not eklendi. Denetleyici ve sözleşme geri açma yöntemi değiştirilmedi.

| Betikler | İncelenen değişiklik | Kanıt ve sınırı |
|---|---|---|
| `membran-boya`, `nukleer-boya`, `sitoplazmik-boya`, `er-pr-hscore` | Açık kalan pencerelerde görüntü tipi ve kalibrasyon güncel görüntüden okunuyor; membran işçisi hatasını sonuç penceresine taşıyor. | `QuPathAccuracyFixesTest.testPersistentWindowModulesResolveCurrentImageDataAndCatchWorkerErrors` kaynak denetimi; GUI'de lam değiştirme çalıştırılmadı. |
| `yardimci-bolge-cikart-sihirbaz`, `yardimci-omezarr-disa-aktarma`, `yardimci-goruntu-kunye`, `yardimci-boya-kalite-qc` | Negatif başlangıç koordinatında sağ/alt sınırdan kırpılmış genişlik/yükseklik hesaplanıyor. | `testNegativeOriginBoundingBoxClampingShrinksSpan` gerçek QuPath ROI nesneleriyle yardımcıyı çalıştırır; diğer üç betikte kaynak biçimini denetler. Dosya dışa aktarma çalıştırılmadı. |
| `yardimci-dogrulama-f1` | Çok noktalı ROI bölgeye en az bir noktayla girerse değerlendirmeye alınıyor, her nokta bölge sınırından ayrı süzülüyor. | `testValidationF1MultiPointRoiFiltersIndividualPointsInsidePatch` üretim `buildUnits` yardımcısı ve gerçek ROI nesneleri. |
| `yardimci-hotspot-sirali` | Önceki yoğun odak karosu kendi özgün sınıfını üst veride saklıyor ve sonraki çalıştırmada bu sınıfa dönüyor. | `testHotspotMultiClassRerunRestoresEachTilesOriginalClass` gerçek nesnelerle davranış örneği ve kaynak denetimi; tüm betik GUI'de çalıştırılmadı. |
| `yardimci-karo-disa-aktarma` | Maske sunucusu seçili anotasyonlarla sınırlanıyor; dışlanan bölgeler maske rasterinde 0 oluyor; en yakın komşu ile ölçekleniyor. | `testTileExporterScopesMaskServerAndZeroesIgnorePixelsWithNearestNeighbor` kaynak denetimi. Gerçek karo–maske çiftlerinin uçtan uca piksel denetimi çalıştırılmadı. |
| `yardimci-kohort-ozet-topla` | TSV başlık ve değerlerindeki sekme/satır sonları hücre sınırlarını bozmuyor. | `testCohortSummarySanitizesTabsAndNewlinesInTsvCells` üretim yardımcısını çalıştırır. |
| `yardimci-ki67-heterojenlik`, `yardimci-yogunluk-haritasi` | Geometri kesişimi başarısızsa o karo atlanıyor. | Fark incelemesi ve derleme; bu hata koluna özel çalışma testi yok. Atlanan karo sonucu eksiltebilir. |
| `yardimci-peritumoral-bant` | Geometri düzeltme yolunda iç/dış/iki yön seçimi korunuyor. | Fark incelemesi ve derleme; geometri hata kolunun uçtan uca testi yok. |

İnceleme otomatik ajan tarafından yapıldı; kişi veya katılımcı kabulü değildir. Kaynak parmak izinin eşleşmesi bilimsel doğruluk, bütün hata kollarının çalıştığı veya GUI kabulü anlamına gelmez. Özellikle karo maskesi ve geometri hata yolları için gerçek uygulama kabulü hâlâ gereklidir.
