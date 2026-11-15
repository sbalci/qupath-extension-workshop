# CytoFormer sabit revizyonu — kaynak kontrolü

15 Kasım 2026. Önceki [model sihirbazı raporunda](model-wizard-ui-validation-2026-11-15.md)
bekleyen iki ifade, modelin güncel `main` kartı yerine kullanılan sabit revizyonun
herkese açık metadata'sıyla kontrol edildi.

| Alan | Sabit revizyon | Kontrol anındaki `main` |
|---|---|---|
| SHA | `3e9691e45c3bd7139059dafa8dc5da716d67801a` | `6a3572026e491774e7530beb3485e31459fdbee5` |
| `cardData.license` | `cc-by-nc-4.0` | `other` |
| `gated` | `auto` | `auto` |

Kaynaklar: [sabit revizyon metadata'sı](https://huggingface.co/api/models/zhihuanglab/CytoFormer/revision/3e9691e),
[`main` metadata'sı](https://huggingface.co/api/models/zhihuanglab/CytoFormer/revision/main).
Bu iki GET isteği oturum veya jeton kullanmadan yapıldı; model dosyası indirilmedi.

Paketli `cytoformer_runner.py` içindeki `HF_REVISION` tam olarak tablodaki sabit
SHA'dır; indirme çağrıları aynı değeri kullanır. Bu nedenle penceredeki ağırlık
başlığının `CC BY-NC 4.0` ifadesi o revizyonun kart kaydıyla eşleşir. Bu bulgu,
güncel `main` koşullarını eski ağırlığa aktarmak için kullanılmadı.

Hugging Face'in [resmî erişim belgesi](https://huggingface.co/docs/hub/models-gated),
`auto` modunda istek sonrasında erişimin otomatik verildiğini açıklar.
Kullanıcının kendi hesabıyla koşulları inceleyip kabul etmesi adımı korunur.
Kimse adına koşul kabul edilmedi veya erişim istenmedi.

İngilizceye çevrilen iki özgün Türkçe metin:

- `③ Ağırlık (checkpoint.pth, CC BY-NC 4.0, kapılı)` başlığı.
- Hesap → model sayfasındaki koşullar → okuma jetonu → `hf auth login` veya
  sihirbazın jeton alanı yolunu anlatan paragraf.

Jeton alanının değeri betikte tercihlere yazılmaz; alt sürece `HF_TOKEN` ortam
değişkeniyle geçer. Köprünün durum günlüğü, değeri yerine jetonun bulunduğu
kaynağın etiketini yazar. Bu kaynak
incelemesi gerçek hesapla giriş veya indirme denemesi değildir. Kullanıcının
ayrı çalıştırdığı `hf auth login` komutunun saklama davranışı değiştirilmedi.

Uygulama commit'i: `8ebd6e4`. Dosya/komut/jeton işleme davranışı aynı kaldı;
yalnız iki görüntüleme çağrısı ortak dil kaynağına eklendi. Önceki raporun
“kaynak doğrulaması bekliyor” kaydı kendi eski commit'ine aittir; bu iki metin
artık o bekleme durumunda değildir.
