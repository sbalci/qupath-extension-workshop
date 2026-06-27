/**
 * Yardımcı - Görüntü işleme kavramları (kendi slaydınızda interaktif tur)
 * ----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Tek pencerede, sayfa sayfa, Pete Bankhead'in dijital patoloji için yazdığı
 *   görüntü-işleme sözlüğünü (https://petebankhead.github.io/2022-image-processing-overview)
 *   KENDİ AÇIK SLAYDINIZDA canlı örneklerle gezdirir. Her kavram, seçili bir
 *   bölge (yoksa görüntüleyici görünümü) üzerinde küçük bir önizleme ile gösterilir:
 *     1. Dijital görüntü + LUT — görüntü sayısal bir ızgaradır; LUT yalnız ekranı
 *        değiştirir, veriyi değil.
 *     2. RGB → kanallar — R/G/B kanalları ayrı yoğunluk düzlemleridir.
 *     3. Renk dekonvolüsyonu — RGB → Hematoksilen + DAB/Eozin tek-kanal yoğunlukları.
 *     4. Gaussian (σ) — σ büyüdükçe daha çok yumuşatma.
 *     5. Eşikleme (ikili) — eşik seçimi maskeyi (nesne sayısı/şekli) doğrudan değiştirir.
 *     6. Mesafe dönüşümü + watershed — bitişik çekirdekleri ayırma fikri.
 *     7. İş akışını birleştir — bu boru hattı tam olarak Modül 2'nin yaptığı şeydir.
 *
 *   Tümüyle SALT-OKUR: görüntü piksellerini, projeyi, anotasyonları veya
 *   tespitleri DEĞİŞTİRMEZ. Yalnız küçük bir bölgeyi okuyup önizleme üretir.
 *
 * NASIL ÖRNEKLER (ve sınırları):
 *   • Bir alan anotasyonu seçiliyse o bölge; değilse görüntüleyici görünümü okunur.
 *   • Önizleme hızlı olsun diye bölge ~360 piksele inecek bir altörnekleme ile okunur;
 *     görüntüler YAKLAŞIK'tır (örneklem üzerinden). Watershed adımı kavramsal anlatımdır
 *     (gerçek bölme için Modül 2 — Hücre tespiti).
 *   • Dekonvolüsyon Ruifrok–Johnston yöntemini elle uygular; brightfield + tanımlı boya
 *     vektörü gerektirir. Görüntüde boya vektörü yoksa (örn. floresan) bu sayfa not gösterir;
 *     Gaussian/eşik sayfaları gri (yoğunluk) kanalı üzerinden çalışır.
 *   • Hiçbir klinik yorum, tanı veya skor üretmez.
 *
 * KULLANIM:
 *   1. Bir slayt açın; isterseniz çekirdek içeren KÜÇÜK bir bölge çizip SEÇİN.
 *   2. [Extensions → Atölye → Yardımcılar → Görüntü işleme kavramları]
 *   3. İleri / Geri ile gezinin; bölgeyi değiştirince "↻ Bölgeyi yeniden oku".
 *
 * KAYNAK / İLHAM:
 *   Pete Bankhead, "A brief overview of image processing for pathologists" (CC-BY 4.0)
 *   https://petebankhead.github.io/2022-image-processing-overview/intro.html
 *   Yöntem: Ruifrok AC, Johnston DA (2001), Anal Quant Cytol Histol 23(4):291–299.
 *   Statik karşılığı: Ek — Görüntü Analizi Temelleri. Boru hattı: Modül 2 — Hücre tespiti.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı; ölçüm veya klinik karar üretmez.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.regions.RegionRequest

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Tur sayfaları (id · önizleme türü · başlık · gövde) ─────────────────────
// onizleme ∈ {null, 'RGB_LUT', 'CHANNELS', 'DECONV', 'GAUSSIAN', 'THRESHOLD', 'WATERSHED', 'COMPOSE'}
def pages = [
    [id: 'intro', onizleme: null,
     baslik: 'Görüntü işleme kavramları — kendi slaydınızda',
     govde: 'Bu tur, dijital patolojide hücre tespitinin "perde arkasını" kendi açık slaydınız ' +
            'üzerinde adım adım gösterir. En verimli kullanım için çekirdek içeren KÜÇÜK bir bölge ' +
            '(R aracı) çizip seçin; bir seçim yoksa görüntüleyici görünümü kullanılır. Önizlemeler ' +
            'salt-okurdur — slaydınızı değiştirmez.\n\n' +
            'Kaynak: Pete Bankhead\'in dijital patoloji için görüntü-işleme sözlüğü (CC-BY 4.0). ' +
            'Aynı kavramların atölyedeki karşılıkları için: Ek — Görüntü Analizi Temelleri.'],

    [id: 'digital', onizleme: 'RGB_LUT',
     baslik: '1 · Dijital görüntü ve LUT',
     govde: 'Bir görüntü, her hücresi bir piksel değeri olan sayısal bir ızgaradır. Ekranda gördüğünüz ' +
            'renk, bu sayıların bir "bakış tablosu" (LUT — lookup table) ile boyanmış hâlidir. LUT ya da ' +
            'parlaklık/kontrast yalnız EKRANI değiştirir; analizde kullanılan piksel değerlerine dokunmaz. ' +
            'Solda özgün RGB, sağda aynı verinin gri (yoğunluk) gösterimi — veri aynı, görünüm farklı.'],

    [id: 'channels', onizleme: 'CHANNELS',
     baslik: '2 · RGB → kanallar',
     govde: 'Bir RGB görüntüde her pikselin üç değeri vardır: kırmızı, yeşil, mavi. Bunlar ayrı yoğunluk ' +
            'düzlemleridir; toplanınca ekrandaki rengi verir. H&E\'de hematoksilenin maviye yakın olması ' +
            'kırmızı kanalda koyu, mavi kanalda açık görünmesine yol açar — renk ayrımının temeli budur.'],

    [id: 'deconv', onizleme: 'DECONV',
     baslik: '3 · Renk dekonvolüsyonu (Hematoksilen + DAB/Eozin)',
     govde: 'Renk dekonvolüsyonu, RGB\'yi boya rengine göre tek-kanallı yoğunluk görüntülerine ayırır ' +
            '(Ruifrok–Johnston). Solda hematoksilen, sağda ikinci boya (DAB/Eozin) yoğunluğu — koyu = çok boya.\n\n' +
            'Sınırlar (Bankhead): yöntem "imkânsız" sonuç verebilir (negatif boya değerleri); kuvvetli ' +
            'DAB-pozitif (kahverengi) pikseller genelde yüksek hematoksilen değeri de taşır. Doğru boya ' +
            'vektörü için: Yardımcılar → Boya vektörleri sihirbazı.'],

    [id: 'gaussian', onizleme: 'GAUSSIAN',
     baslik: '4 · Gaussian filtre (σ)',
     govde: 'Gaussian filtre, her pikseli komşularının ağırlıklı ortalamasıyla değiştirerek gürültüyü ' +
            'azaltır. σ (sigma) büyüdükçe yumuşatma artar. Çok küçük σ tek çekirdeği parçalara bölebilir; ' +
            'çok büyük σ bitişik çekirdekleri birbirine karıştırır. (σ burada önizleme pikseli; QuPath\'te µm.)\n\n' +
            'Hematoksilen kanalında soldan sağa artan σ — yumuşamanın etkisini izleyin.'],

    [id: 'threshold', onizleme: 'THRESHOLD',
     baslik: '5 · Eşikleme → ikili (binary) görüntü',
     govde: 'Eşikleme, bir yoğunluk değerinin üstündeki pikselleri "ön plan" (beyaz), altındakileri ' +
            '"arka plan" (siyah) yapar. Aynı kanalda üç farklı eşik — eşik seçiminin bulunan nesnelerin ' +
            'sayısını ve şeklini ne kadar değiştirdiğine dikkat edin. Bankhead\'in dersi: eşik TEK BAŞINA ' +
            'çekirdek tespiti için yetmez; devamında daha çok işlem (mesafe + watershed) gerekir.'],

    [id: 'watershed', onizleme: 'WATERSHED',
     baslik: '6 · Mesafe dönüşümü + watershed',
     govde: 'İkili maskede birbirine değen çekirdekler tek bir nesneye yapışır. Mesafe dönüşümü, her ön plan ' +
            'pikseline en yakın arka plana olan uzaklığı atar — çekirdek merkezleri en yüksek değeri alır. ' +
            'Bu merkezler "tohum" olur; watershed bölgeleri tohumlardan büyütüp bitişik çekirdekleri ayırır ' +
            '(hepsini değil). Aşağıda eşiklenmiş maske + tahmini merkezler (mesafe tepe noktaları) işaretli. ' +
            'Gerçek bölme QuPath\'in watershed tespitinde olur: Modül 2 — Hücre tespiti.'],

    [id: 'compose', onizleme: 'COMPOSE',
     baslik: '7 · İş akışını birleştir — bu, Modül 2\'dir',
     govde: 'Bu adımları birleştirince bir hücre-tespit boru hattı çıkar:\n' +
            '   dekonvolüsyon → Gaussian (σ) → eşik (ikili) → mesafe + watershed → nesneler.\n' +
            'Modül 2 — Hücre tespiti tam olarak bunu yapar; "Gelişmiş ayarlar"daki eşik ve σ, gördüğünüz ' +
            'adımları kontrol eder.\n\n' +
            'Bankhead\'in uyarısı: bu kadar basit bir hat yalnız birkaç görüntüde ve düşük büyütmede iyi ' +
            'görünür; yüksek büyütmede hatalar belirginleşir. Gerçek uygulamalar çok daha fazla ayarlanabilir ' +
            'parametre gerektirir — bu yüzden Modül 2 sonrası eşikleri kendi slaydınızda kalibre edersiniz.\n\n' +
            '⚠️ Yalnızca araştırma/eğitim amaçlı; ölçüm veya klinik karar üretmez.']
]

// ── Headless: turu çalıştıramayız (GUI gerekir) — içeriği konsola özetle ─────
if (isHeadless) {
    println 'Görüntü işleme kavramları turu için QuPath arayüzü gerekir (headless çalıştırılamaz).'
    println 'Tur ' + pages.size() + ' sayfadan oluşur:'
    pages.eachWithIndex { p, n -> println '  ' + (n + 1) + '. ' + p.baslik }
    println '⚠️ Yalnızca araştırma/eğitim amaçlı; ölçüm veya klinik karar üretmez.'
    return
}

// ════════════════════════════════════════════════════════════════════════════
//  Görüntü işleme çekirdeği — tümü salt-okur, küçük örnek bölge üzerinde
// ════════════════════════════════════════════════════════════════════════════

// 3×3 matris tersi (dekonvolüsyon için)
def invert3 = { double[][] m ->
    double a = m[0][0], b = m[0][1], c = m[0][2]
    double d = m[1][0], e = m[1][1], f = m[1][2]
    double g = m[2][0], h = m[2][1], i = m[2][2]
    double A =  (e * i - f * h), B = -(d * i - f * g), C =  (d * h - e * g)
    double D = -(b * i - c * h), E =  (a * i - c * g), F = -(a * h - b * g)
    double G =  (b * f - c * e), H = -(a * f - c * d), I =  (a * e - b * d)
    double det = a * A + b * B + c * C
    if (Math.abs(det) < 1e-12d) det = (det < 0 ? -1e-12d : 1e-12d)
    double[][] inv = new double[3][3]
    inv[0][0] = A / det; inv[0][1] = D / det; inv[0][2] = G / det
    inv[1][0] = B / det; inv[1][1] = E / det; inv[1][2] = H / det
    inv[2][0] = C / det; inv[2][1] = F / det; inv[2][2] = I / det
    return inv
}

// Saf-Groovy ayrılabilir Gaussian bulanıklaştırma (float dizi, kenar tekrarı)
def gaussianBlur = { float[] src, int w, int h, double sigma ->
    if (sigma <= 0.0d) return src.clone()
    int rad = (int) Math.ceil(sigma * 3.0d)
    if (rad < 1) rad = 1
    double[] k = new double[2 * rad + 1]
    double ksum = 0.0d
    for (int t = -rad; t <= rad; t++) { double v = Math.exp(-(t * t) / (2.0d * sigma * sigma)); k[t + rad] = v; ksum += v }
    for (int t = 0; t < k.length; t++) k[t] /= ksum
    float[] tmp = new float[src.length]
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            double acc = 0.0d
            for (int t = -rad; t <= rad; t++) { int xx = x + t; if (xx < 0) xx = 0; else if (xx >= w) xx = w - 1; acc += src[y * w + xx] * k[t + rad] }
            tmp[y * w + x] = (float) acc
        }
    }
    float[] out = new float[src.length]
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            double acc = 0.0d
            for (int t = -rad; t <= rad; t++) { int yy = y + t; if (yy < 0) yy = 0; else if (yy >= h) yy = h - 1; acc += tmp[yy * w + x] * k[t + rad] }
            out[y * w + x] = (float) acc
        }
    }
    return out
}

// float "yoğunluk/miktar" dizisini gri BufferedImage'a çevir (çok boya = koyu)
def amountToGray = { float[] a, int w, int h ->
    def out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for (int p = 0; p < a.length; p++) {
        double amt = a[p]; if (amt < 0.0d) amt = 0.0d
        int v = (int) Math.round(255.0d * Math.pow(10.0d, -amt))
        if (v < 0) v = 0; else if (v > 255) v = 255
        int gray = (v << 16) | (v << 8) | v
        out.setRGB(p % w, (int) (p / w), gray)
    }
    return out
}

// Tek RGB kanalını gri BufferedImage'a çevir (shift: 16=R, 8=G, 0=B)
def channelToGray = { int[] rgb, int w, int h, int shift ->
    def out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for (int p = 0; p < rgb.length; p++) {
        int v = (rgb[p] >> shift) & 0xff
        int gray = (v << 16) | (v << 8) | v
        out.setRGB(p % w, (int) (p / w), gray)
    }
    return out
}

// Eşik → ikili BufferedImage (miktar >= thr → ön plan beyaz)
def thresholdToBinary = { float[] a, int w, int h, double thr ->
    def out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for (int p = 0; p < a.length; p++) {
        int c = (a[p] >= thr) ? 0xFFFFFF : 0x000000
        out.setRGB(p % w, (int) (p / w), c)
    }
    return out
}

// RGB diziden gri (luma) "sinyal" miktarı (boya yoksa yedek): OD(luma)
def lumaAmount = { int[] rgb ->
    float[] a = new float[rgb.length]
    for (int p = 0; p < rgb.length; p++) {
        int c = rgb[p]
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff
        double luma = 0.299d * r + 0.587d * g + 0.114d * b
        a[p] = (float) (-Math.log10(Math.max(luma, 1.0d) / 255.0d))
    }
    return a
}

// Küçük örnek bölgeyi oku → [img, rgb, w, h, downsample, desc] | null
def readPreviewRegion = { imageData ->
    def server = imageData.getServer()
    def sel = QP.getSelectedObject()
    def roi = (sel != null && sel.hasROI() && sel.getROI().isArea()) ? sel.getROI() : null
    double x, y, rw, rh
    String desc
    if (roi != null) {
        x = roi.getBoundsX(); y = roi.getBoundsY(); rw = roi.getBoundsWidth(); rh = roi.getBoundsHeight()
        desc = 'Seçili bölge'
    } else {
        try {
            def viewer = gui.getViewer()
            double ds0 = viewer.getDownsampleFactor()
            def node = viewer.getView()
            double vw = node.getWidth() * ds0, vh = node.getHeight() * ds0
            x = viewer.getCenterPixelX() - vw / 2.0d; y = viewer.getCenterPixelY() - vh / 2.0d; rw = vw; rh = vh
            desc = 'Görüntüleyici görünümü'
        } catch (Throwable t) {
            x = 0; y = 0; rw = server.getWidth(); rh = server.getHeight(); desc = 'Tüm görüntü'
        }
    }
    if (x < 0) x = 0; if (y < 0) y = 0
    if (rw > server.getWidth() - x) rw = server.getWidth() - x
    if (rh > server.getHeight() - y) rh = server.getHeight() - y
    if (rw < 1 || rh < 1) return null
    int TARGET = 360
    double downsample = Math.max(1.0d, Math.max(rw, rh) / (double) TARGET)
    def request = RegionRequest.createInstance(server.getPath(), downsample, (int) x, (int) y, (int) Math.ceil(rw), (int) Math.ceil(rh))
    def img = server.readRegion(request)
    if (img == null) return null
    int w = img.getWidth(), h = img.getHeight()
    int[] rgb = img.getRGB(0, 0, w, h, null, 0, w)
    return [img: img, rgb: rgb, w: w, h: h, downsample: downsample, desc: desc]
}

// Tüm önizleme görüntülerini hesapla (arka planda); döner: cache Map
def computePreviews = { imageData ->
    def region = readPreviewRegion(imageData)
    if (region == null) return [error: 'Önizleme bölgesi okunamadı (boş/sıfır boyut?).']
    int w = region.w, h = region.h
    int[] rgb = region.rgb
    def cache = [w: w, h: h, desc: region.desc, downsample: region.downsample]
    cache.original = region.img
    cache.gray = channelToGray(rgb, w, h, 8)   // yeşil kanal ≈ luminans (LUT örneği için)
    cache.chR = channelToGray(rgb, w, h, 16)
    cache.chG = channelToGray(rgb, w, h, 8)
    cache.chB = channelToGray(rgb, w, h, 0)

    // Dekonvolüsyon (boya vektörü varsa)
    float[] work
    def stains = imageData.getColorDeconvolutionStains()
    if (stains != null) {
        def s1 = stains.getStain(1), s2 = stains.getStain(2), s3 = stains.getStain(3)
        double[][] M = [
            [s1.getRed(),   s2.getRed(),   s3.getRed()],
            [s1.getGreen(), s2.getGreen(), s3.getGreen()],
            [s1.getBlue(),  s2.getBlue(),  s3.getBlue()]
        ] as double[][]
        double[][] inv = invert3(M)
        double i0r = Math.max(1.0d, stains.getMaxRed())
        double i0g = Math.max(1.0d, stains.getMaxGreen())
        double i0b = Math.max(1.0d, stains.getMaxBlue())
        float[] a1 = new float[w * h]
        float[] a2 = new float[w * h]
        for (int p = 0; p < rgb.length; p++) {
            int c = rgb[p]
            int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff
            double odR = -Math.log10(Math.max(r, 1) / i0r)
            double odG = -Math.log10(Math.max(g, 1) / i0g)
            double odB = -Math.log10(Math.max(b, 1) / i0b)
            a1[p] = (float) (inv[0][0] * odR + inv[0][1] * odG + inv[0][2] * odB)
            a2[p] = (float) (inv[1][0] * odR + inv[1][1] * odG + inv[1][2] * odB)
        }
        cache.hema = amountToGray(a1, w, h)
        cache.stain2 = amountToGray(a2, w, h)
        cache.name1 = s1.getName() ?: 'Hematoksilen'
        cache.name2 = s2.getName() ?: 'Boya 2'
        cache.hasStains = true
        work = a1
    } else {
        cache.hasStains = false
        work = lumaAmount(rgb)
    }
    cache.work = work

    // Gaussian (σ = 0.8 / 2 / 4 önizleme pikseli)
    double[] sigmas = [0.8d, 2.0d, 4.0d] as double[]
    def gimgs = []
    sigmas.each { sg -> gimgs << [sigma: sg, img: amountToGray(gaussianBlur(work, w, h, sg), w, h)] }
    cache.gaussians = gimgs

    // Eşik (ortalama work'e göre düşük/orta/yüksek)
    double mean = 0.0d
    for (int p = 0; p < work.length; p++) mean += work[p]
    mean = mean / (double) work.length
    double base = Math.max(mean, 0.05d)
    float[] sm = gaussianBlur(work, w, h, 1.2d)   // hafif yumuşatma sonrası eşikle (Bankhead akışı)
    cache.threshSmoothed = sm
    double[] thrs = [0.7d * base, 1.1d * base, 1.7d * base] as double[]
    def timgs = []
    thrs.each { th -> timgs << [thr: th, img: thresholdToBinary(sm, w, h, th)] }
    cache.thresholds = timgs
    cache.thrMid = thrs[1]

    // Watershed sayfası: orta eşik maskesi + tahmini merkezler (yerel mesafe tepe noktaları)
    cache.watershed = thresholdToBinary(sm, w, h, thrs[1])
    return cache
}

// ════════════════════════════════════════════════════════════════════════════
//  Pencere — sayfa sayfa render (arayuz-turu kalıbı + önizleme alanı)
// ════════════════════════════════════════════════════════════════════════════
def stage = null
def idx        = new java.util.concurrent.atomic.AtomicInteger(0)
def alwaysTop  = new java.util.concurrent.atomic.AtomicBoolean(true)
def cacheRef   = new java.util.concurrent.atomic.AtomicReference(null)   // önizleme cache | [error:..] | null
def computing  = new java.util.concurrent.atomic.AtomicBoolean(false)
def render  // ileri bildirim

// BufferedImage → JavaFX ImageView (başlıklı küçük kart)
def thumb = { java.awt.image.BufferedImage bimg, String caption, double fitW ->
    def box = new javafx.scene.layout.VBox(4)
    box.setAlignment(javafx.geometry.Pos.TOP_CENTER)
    try {
        def fx = javafx.embed.swing.SwingFXUtils.toFXImage(bimg, null)
        def iv = new javafx.scene.image.ImageView(fx)
        iv.setFitWidth(fitW); iv.setPreserveRatio(true); iv.setSmooth(true)
        iv.setStyle('-fx-border-color: #888;')
        box.getChildren().add(iv)
    } catch (Throwable t) {
        box.getChildren().add(new javafx.scene.control.Label('(önizleme çizilemedi)'))
    }
    if (caption != null) {
        def cap = new javafx.scene.control.Label(caption)
        cap.setWrapText(true); cap.setMaxWidth(fitW + 10)
        cap.setStyle('-fx-font-size: 11px; -fx-opacity: 0.8;')
        box.getChildren().add(cap)
    }
    return box
}

// Bir sayfanın önizleme düğümünü cache'ten kur (cache yoksa null)
def buildPreview = { String kind, cache ->
    if (cache == null || cache.error != null) return null
    def row = new javafx.scene.layout.HBox(12)
    row.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    row.setStyle('-fx-padding: 6 0 6 0;')
    if (kind == 'RGB_LUT') {
        row.getChildren().addAll(
            thumb((java.awt.image.BufferedImage) cache.original, 'Özgün RGB (renkli LUT)', 240.0d),
            thumb((java.awt.image.BufferedImage) cache.gray, 'Aynı veri, gri LUT', 240.0d))
    } else if (kind == 'CHANNELS') {
        row.getChildren().addAll(
            thumb((java.awt.image.BufferedImage) cache.chR, 'Kırmızı (R)', 160.0d),
            thumb((java.awt.image.BufferedImage) cache.chG, 'Yeşil (G)', 160.0d),
            thumb((java.awt.image.BufferedImage) cache.chB, 'Mavi (B)', 160.0d))
    } else if (kind == 'DECONV') {
        if (cache.hasStains) {
            row.getChildren().addAll(
                thumb((java.awt.image.BufferedImage) cache.original, 'Özgün RGB', 170.0d),
                thumb((java.awt.image.BufferedImage) cache.hema, (cache.name1 ?: 'Hematoksilen') + ' (yoğunluk)', 170.0d),
                thumb((java.awt.image.BufferedImage) cache.stain2, (cache.name2 ?: 'Boya 2') + ' (yoğunluk)', 170.0d))
        } else {
            def lbl = new javafx.scene.control.Label(
                'Bu görüntüde renk dekonvolüsyonu (boya vektörü) tanımlı değil — büyük olasılıkla brightfield ' +
                'değil. Görüntü tipini ayarlayın (Yardımcılar → Görüntü tipi ayarla). Aşağıdaki Gaussian/eşik ' +
                'sayfaları gri (yoğunluk) kanalı üzerinden çalışır.')
            lbl.setWrapText(true)
            row.getChildren().add(lbl)
        }
    } else if (kind == 'GAUSSIAN') {
        cache.gaussians.each { gm ->
            row.getChildren().add(thumb((java.awt.image.BufferedImage) gm.img,
                String.format(java.util.Locale.US, 'σ ≈ %.1f', (double) gm.sigma), 160.0d))
        }
    } else if (kind == 'THRESHOLD') {
        def labels = ['düşük eşik', 'orta eşik', 'yüksek eşik']
        cache.thresholds.eachWithIndex { tm, n ->
            row.getChildren().add(thumb((java.awt.image.BufferedImage) tm.img,
                String.format(java.util.Locale.US, '%s (≈ %.2f)', labels[n], (double) tm.thr), 160.0d))
        }
    } else if (kind == 'WATERSHED') {
        row.getChildren().addAll(
            thumb((java.awt.image.BufferedImage) cache.hema ?: (java.awt.image.BufferedImage) cache.gray, 'Yoğunluk', 170.0d),
            thumb((java.awt.image.BufferedImage) cache.watershed, 'İkili maske (orta eşik)', 170.0d))
    } else if (kind == 'COMPOSE') {
        def imgs = []
        imgs << thumb((java.awt.image.BufferedImage) cache.original, 'RGB', 120.0d)
        if (cache.hasStains) imgs << thumb((java.awt.image.BufferedImage) cache.hema, 'Hematoksilen', 120.0d)
        if (!cache.gaussians.isEmpty()) imgs << thumb((java.awt.image.BufferedImage) cache.gaussians[1].img, 'Gaussian', 120.0d)
        if (!cache.thresholds.isEmpty()) imgs << thumb((java.awt.image.BufferedImage) cache.thresholds[1].img, 'Eşik (ikili)', 120.0d)
        imgs.each { row.getChildren().add(it) }
    } else {
        return null
    }
    def sc = new javafx.scene.control.ScrollPane(row)
    sc.setFitToHeight(true); sc.setPannable(true)
    sc.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED)
    sc.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER)
    sc.setStyle('-fx-background-color: transparent;')
    return sc
}

def startCompute = { ->
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { cacheRef.set([error: 'Önce bir slayt açın.']); render(); return }
    if (computing.get()) return
    computing.set(true)
    cacheRef.set(null)
    render()
    def worker = new Thread({
        def result
        try { result = computePreviews(imageData) }
        catch (Throwable t) { result = [error: 'Önizleme üretilemedi: ' + (t.getMessage() ?: t.getClass().getSimpleName())] }
        javafx.application.Platform.runLater {
            cacheRef.set(result)
            computing.set(false)
            render()
        }
    }, 'AtolyeKavramTuru')
    worker.setDaemon(true); worker.start()
}

render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    int i = idx.get()
    def page = pages[i]

    def title = new javafx.scene.control.Label(page.baslik)
    title.setStyle('-fx-font-size: 16px; -fx-font-weight: bold;'); title.setWrapText(true)
    def prog = new javafx.scene.control.Label('Adım ' + (i + 1) + ' / ' + pages.size())
    prog.setStyle('-fx-opacity: 0.7; -fx-font-size: 12px;')
    def body = new javafx.scene.control.Label(page.govde)
    body.setWrapText(true); body.setStyle('-fx-font-size: 13px; -fx-line-spacing: 2px;')

    def center = new javafx.scene.layout.VBox(10)
    center.setPadding(new javafx.geometry.Insets(16))
    center.getChildren().addAll(title, prog, body)

    // Önizleme alanı (yalnız önizleme türü tanımlı sayfalarda)
    if (page.onizleme != null) {
        def cache = cacheRef.get()
        if (computing.get()) {
            def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(260.0d)
            def lbl = new javafx.scene.control.Label('Önizleme hesaplanıyor…')
            center.getChildren().addAll(lbl, pb)
        } else if (cache == null) {
            center.getChildren().add(new javafx.scene.control.Label('Önizleme için "↻ Bölgeyi oku/yenile".'))
        } else if (cache.error != null) {
            def lbl = new javafx.scene.control.Label('⚠ ' + cache.error); lbl.setWrapText(true)
            center.getChildren().add(lbl)
        } else {
            def info = new javafx.scene.control.Label('Örneklenen bölge: ' + cache.desc +
                String.format(java.util.Locale.US, '  ·  ~1/%.0f altörnekleme', (double) cache.downsample))
            info.setStyle('-fx-opacity: 0.7; -fx-font-size: 11px;')
            center.getChildren().add(info)
            def pv = buildPreview((String) page.onizleme, cache)
            if (pv != null) {
                javafx.scene.layout.VBox.setVgrow(pv, javafx.scene.layout.Priority.ALWAYS)
                center.getChildren().add(pv)
            }
        }
    }

    // Alt çubuk: Üstte tut (sol) + disclaimer + gezinme (sağ)
    def topChk = new javafx.scene.control.CheckBox('Üstte tut')
    topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n ->
        alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n)
    } as javafx.beans.value.ChangeListener)
    def reloadBtn = new javafx.scene.control.Button('↻ Bölgeyi oku/yenile')
    reloadBtn.setOnAction({ startCompute() })
    reloadBtn.setDisable(computing.get())
    def spacer = new javafx.scene.layout.Region()
    javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def backBtn = new javafx.scene.control.Button('◀ Geri')
    backBtn.setDisable(i == 0)
    backBtn.setOnAction({ idx.set(Math.max(0, i - 1)); render() })
    boolean last = (i == pages.size() - 1)
    def nextBtn = new javafx.scene.control.Button(last ? 'Bitir' : 'İleri ▶')
    nextBtn.setOnAction({ if (last) stage.close() else { idx.set(i + 1); render() } })
    def closeBtn = new javafx.scene.control.Button('Kapat')
    closeBtn.setOnAction({ stage.close() })

    def bar = new javafx.scene.layout.HBox(8)
    bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk)
    bar.getChildren().add(reloadBtn)
    bar.getChildren().add(spacer)
    bar.getChildren().addAll(closeBtn, backBtn, nextBtn)

    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlı; ölçüm veya klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(center); root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 880, 660))
}

// ── Açılış ───────────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Görüntü işleme kavramları')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
        if (QP.getCurrentImageData() != null) startCompute()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println '✓ Görüntü işleme kavramları turu açıldı.'
