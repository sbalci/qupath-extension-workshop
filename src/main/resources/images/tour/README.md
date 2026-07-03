# Arayüz turu — görseller (ekran görüntüsü / GIF)

`modul-01-arayuz-turu.groovy` (Modül 1 — Arayüz turu) her sayfada, ilgili sayfaya
ait bir görsel varsa onu başlık ile gövde metni arasında gösterir.

## Nasıl eklenir

Bir sayfaya görsel eklemek için, dosyayı bu klasöre **sayfanın `id`'siyle** koyun:

```
<sayfa-id>.gif   (öncelik 1 — animasyon)
<sayfa-id>.png   (öncelik 2 — statik ekran görüntüsü)
<sayfa-id>.jpg   (öncelik 3)
```

Yükleyici bu sırayla dener; bulduğu ilkini gösterir. **Dosya yoksa sayfa yalnız
metinle çalışır** (zarif düşüş — hata vermez). Kod değişikliği gerekmez: dosyayı
buraya koyup eklentiyi yeniden derlemek (`gradlew clean build`) yeterli.

- JavaFX `Image`, **GIF89a** animasyonlarını yerel olarak (ek kütüphanesiz)
  oynatır; PNG/JPEG statik gösterilir. **WebP desteklenmez**, APNG statik görünür.
- Görsel 740 px genişliğe ölçeklenir (en-boy korunur).

## Boyut bütçesi (ÖNEMLİ)

Bu klasör eklenti JAR'ına paketlenir; JAR ise GitHub + OSF + Zenodo'ya dağıtılır ve
görseller alt-modülün git geçmişinde **kalıcı** olur. Bu yüzden **işlemeden önce**
sıkıştırın:

```bash
gifsicle -O3 --colors 128 --lossy=40 giris.gif -o <sayfa-id>.gif
pngquant --quality=60-80 giris.png -o <sayfa-id>.png
```

Hedef: her dosya **≤ 300 KB** (mutlak üst sınır 500 KB). 20 sayfa × 300 KB ≈ 6 MB;
JAR ~2.8 MB → ~9–12 MB olur (drag-drop kurulum için kabul edilebilir).

## Sayfa id'leri (dosya adları)

Aşağıdaki id'ler `modul-01-arayuz-turu.groovy` içindeki `pages` listesinden gelir.
`intro` ve `close` genelde görselsiz bırakılır.

| id | Sayfa |
|----|-------|
| `viewer` | Görüntüleyici — merkez alan |
| `overview` | Genel bakış navigatörü |
| `sidebar` | Kenar paneli — genel |
| `tab-project` | Project sekmesi |
| `tab-image` | Image sekmesi |
| `tab-annotations` | Annotations sekmesi |
| `tab-hierarchy` | Hierarchy sekmesi |
| `tab-workflow` | Workflow sekmesi |
| `toolbar` | Araç çubuğu — genel |
| `tools-draw` | Çizim araçları |
| `tool-points` | Points aracı |
| `selection-mode` | Seçim modu |
| `brightness` | Parlaklık & Kontrast |
| `visibility` | Görünürlük anahtarları |
| `opacity` | Opaklık kaydırıcısı |
| `measurements` | Ölçüm tabloları |
| `script-editor` | Script editörü |
| `command-list` | Command List (Ctrl/⌘+L) |

> Not: Görseller yalnız **eklenti menüsünden** (Extensions → Atölye → Modüller →
> Modül 1) çalıştırıldığında görünür; `Automate → Project scripts`'ten çalıştırınca
> kaynaklar classpath'te olmadığından sayfa metin-only kalır (bilinçli davranış).
