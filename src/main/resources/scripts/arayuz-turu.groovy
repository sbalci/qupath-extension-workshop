/**
 * QuPath Arayüz Turu (etkileşimli gezinti)
 * -------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Tek pencerede, sayfa sayfa QuPath arayüzünü Türkçe anlatır
 *   (görüntüleyici, kenar paneli sekmeleri, araç çubuğu, parlaklık/kontrast,
 *   Command List...). Her sayfada ilgili ÖĞE — belirli bir sekme (Project,
 *   Image, Annotations...) ya da araç çubuğu düğmesi (parlaklık, opaklık,
 *   ölçüm tablosu...) — canlı pencerede turuncu bir çerçeveyle vurgulanır.
 *   Öğeler tooltip metnine göre DEĞİL, düğmeye bağlı QuPath eyleminin (Action)
 *   KİMLİĞİNE göre bulunur; böylece "point" gibi bir anahtar kelime yanlışlıkla
 *   polygon/polyline gibi komşu düğmeleri vurgulamaz.
 *
 *   Bazı adımlarda ilgili komut örnek olarak KENDİLİĞİNDEN çalıştırılır:
 *   çizim/points aracı etkinleştirilir, seçim modu açılır, Parlaklık/Kontrast
 *   diyaloğu ile Betik editörü açılır, ölçüm tabloları menüsü gösterilir ve
 *   Command List (Ctrl/⌘+L) tetiklenir. Bu eylemler yalnızca EKRANI / aktif
 *   aracı / açık pencereleri değiştirir; hiçbir NESNEYİ, ÖLÇÜMÜ veya HİYERARŞİYİ
 *   DEĞİŞTİRMEZ. Tur kapanınca başlangıçtaki aktif araç ve seçim modu geri yüklenir.
 *   Görünürlük anahtarları (anotasyon/tespit göster-gizle) yalnızca vurgulanır,
 *   çalıştırılmaz — çünkü tetiklenirse nesneleri gizlerler.
 *
 *   Öğe bulunamazsa (slayt açık değil ya da QuPath sürümü farklı) sayfa yine de
 *   metniyle çalışır; erişilemeyen öğe için bütün bölgeye (araç çubuğu / kenar
 *   paneli / görüntüleyici) düşülür veya vurgu düğmesi pasifleşir.
 *
 *   Pete Bankhead'in qupath-extension-training (Apache-2.0) eklentisinden
 *   esinlenen, atölyeye özgü hafif bir Türkçe uyarlamadır. Resmî eklenti
 *   İngilizce, buton-düzeyinde canlı vurgu + otomatik ekran görüntüleri sunar.
 *
 * KULLANIM:
 *   1. (Önerilir) Bir slayt açın — görüntüleyici/meta veri bölgeleri dolu olsun.
 *   2. [Extensions → Atölye → Modüller → Arayüz turu]
 *   3. İleri / Geri ile gezinin; "Otomatik göster" açıkken her sayfa ilgili öğeyi
 *      vurgular ve (varsa) komutu çalıştırır; kapatmak için kutunun işaretini kaldırın.
 *
 * KAYNAK / İLHAM:
 *   qupath/qupath-extension-training (Apache-2.0, Pete Bankhead)
 *   https://github.com/qupath/qupath-extension-training
 *   Statik karşılığı: QuPath'e Giriş — Arayüz turu (panel tablosu + kısayollar).
 *
 * ⚠️ Yalnızca eğitim amaçlıdır; ölçüm veya klinik karar üretmez.
 */

import qupath.fx.dialogs.Dialogs

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Tur sayfaları (id · vurgu bölgesi · hedef · demo · başlık · gövde) ───────
// bolge ∈ {TOOLBAR, SIDEBAR, VIEWER, null}: belirli hedef çözülemezse geri çekilme.
// hedef: sekme -> [tab:'Annotations']; araç çubuğu -> Action KİMLİĞİ ile:
//        [tools:'ALL'|'POINTS'] · [selectionMode:true] · [action:'BRIGHTNESS_CONTRAST']
//        · [actions:['SHOW_ANNOTATIONS',...]] · [id:'opacitySlider'] (setId'li düğüm).
// demo: sayfa açılınca örnek çalıştırılacak eylem (yoksa null):
//        'TOOL_MOVE' · 'TOOL_POINTS' · 'SELECTION_MODE' · 'FIRE' (düğmeye tıkla) ·
//        'MENU' (menü düğmesini aç) · 'COMMAND_LIST' (Ctrl/⌘+L).
def pages = [
    [id: 'intro', bolge: null,
     baslik: 'Hoş geldiniz — QuPath arayüz turu',
     govde: 'Bu tur QuPath arayüzünü adım adım tanıtır. Her sayfada bir öğe anlatılır; ' +
            'ilgili sekme, düğme ya da bölge canlı pencerede turuncu bir çerçeveyle işaretlenir. ' +
            'Bazı adımlarda ilgili komut örnek olarak kendiliğinden çalıştırılır (ör. Parlaklık/Kontrast ' +
            'diyaloğu açılır, Points aracı etkinleşir). En verimli kullanım için önce bir slayt açın — ' +
            'böylece görüntüleyici ve meta veri sekmeleri de dolu olur.\n\n' +
            'İleri / Geri düğmeleriyle gezinin; sihirbaz penceresi her zaman üstte kalsın ' +
            'isterseniz alttaki "Üstte tut" kutusu işaretli kalsın. "Otomatik göster" kutusu, her sayfada ' +
            'vurgu ve örnek komutu kendiliğinden tetikler. Bu sihirbaz Pete Bankhead\'in ' +
            'qupath-extension-training eklentisinden esinlenmiştir (son sayfadaki bağlantı).'],

    [id: 'viewer', bolge: 'VIEWER',
     baslik: 'Görüntüleyici — slaytın görüldüğü merkez alan',
     govde: 'Görüntüleyici, slaytın görüntülendiği merkezdeki büyük alandır. WSI\'yi Google ' +
            'Haritalar gibi bir çözünürlük piramidi üzerinde gezersiniz: fare tekerleğiyle ' +
            'yakınlaşıp uzaklaşır, Move aracı etkinken tıklayıp sürükleyerek kaydırırsınız. ' +
            'Tüm tespit ve anotasyonlar bu alanın üzerine çizilir. Shift+Z tüm slaytı pencereye sığdırır.'],

    [id: 'overview', bolge: 'VIEWER',
     baslik: 'Genel bakış navigatörü (görüntüleyicinin sağ-üst köşesi)',
     govde: 'Görüntüleyicinin sağ-üst köşesindeki küçük genel bakış (navigatör) penceresi, tüm slayttaki ' +
            'konumunuzu gösterir — yüksek büyütmede "kayboldum" hissini önler. Üzerindeki dikdörtgen ' +
            'şu an görünen alandır; küçük pencereye tıklayarak hızla başka bir bölgeye atlayabilirsiniz. ' +
            'Konumu sabittir — QuPath onu her zaman sağ-üst köşeye yerleştirir (taşınamaz). ' +
            'Ayarlanabilen tek şey görünürlüğüdür: View menüsünden ya da araç çubuğundaki görüntüleyici ' +
            'görünüm menüsünden (ekran/monitör simgesi) genel bakışı gösterip gizleyebilirsiniz. ' +
            'Not: "Bu bölgeyi vurgula" tüm görüntüleyiciyi çerçeveler; navigatör bu çerçevenin sağ-üst ' +
            'köşesindeki küçük kutudur (görüntünün üstüne çizildiği için ayrı olarak işaretlenemez).'],

    [id: 'sidebar', bolge: 'SIDEBAR',
     baslik: 'Kenar paneli — analiz sekmeleri',
     govde: 'Sol kenardaki panel analizin kumanda merkezidir; sekmeler hâlinde düzenlenmiştir: ' +
            'Project, Image, Annotations, Hierarchy ve Workflow. Bir sekmeye tıklayarak o görünüme ' +
            'geçersiniz. Sonraki sayfalarda her sekmeyi tek tek tanıyacağız. Panel dar geldiğinde ' +
            'kenarından tutup genişletebilirsiniz.'],

    [id: 'tab-project', bolge: 'SIDEBAR', hedef: [tab: 'Project'], safeActivate: true,
     baslik: 'Project sekmesi — slayt listesi',
     govde: 'Project sekmesi, projedeki tüm slaytları küçük resimleriyle listeler. Bir küçük resme ' +
            'çift tıklamak slaytı görüntüleyicide açar; sağ tıklamak açıklama ekleme, görüntü tipi ' +
            'atama gibi işlemleri sunar. Projeler birden çok slaydı bir arada tutmanın ve aynı analizi ' +
            'hepsine uygulamanın profesyonel yoludur. Henüz proje açmadıysanız bu sekme boş görünür.'],

    [id: 'tab-image', bolge: 'SIDEBAR', hedef: [tab: 'Image'], safeActivate: true,
     baslik: 'Image sekmesi — piksel boyutu ve meta veri',
     govde: 'Image sekmesi açık slaytın meta verisini gösterir: piksel boyutu (µm/px), görüntü ' +
            'boyutları, tarayıcı modeli ve boya tipi. Buradaki en kritik sayı piksel boyutudur — ' +
            'QuPath fiziksel ölçeği buradan bilir ve her tespit/ölçüm buna bağlıdır. 1.0 µm/px ' +
            'görüyorsanız meta veri eksik demektir; Giriş modülündeki kalibrasyon adımına dönün. Görüntü ' +
            'tipi (Brightfield H&E / H-DAB / Fluorescence) de bu sekmede görünür ve boya ayrımını belirler.'],

    [id: 'tab-annotations', bolge: 'SIDEBAR', hedef: [tab: 'Annotations'], safeActivate: true,
     baslik: 'Annotations sekmesi — çizilen ROI\'ler ve sınıflar',
     govde: 'Annotations sekmesi, elle veya algoritmayla oluşturulan tüm ROI\'leri (ilgi bölgelerini) ' +
            've onların sınıflarını listeler. Bir anotasyona tıklamak onu görüntüleyicide seçer; sağ ' +
            'tıklamayla sınıf atayabilir, ad verebilir veya silebilirsiniz. Tümör, stroma gibi sınıflar ' +
            'burada renklerle yönetilir.'],

    [id: 'tab-hierarchy', bolge: 'SIDEBAR', hedef: [tab: 'Hierarchy'], safeActivate: true,
     baslik: 'Hierarchy sekmesi — nesne ağacı (anotasyon → tespit)',
     govde: 'Hierarchy sekmesi nesnelerin ağaç yapısını gösterir: slayt → anotasyon → tespit. Bir ' +
            'anotasyonun içinde bulunan hücre tespitleri bu ağaçta onun altında yer alır. Anotasyon ile ' +
            'tespit arasındaki fark burada görünür hâle gelir: anotasyon elle çizilen bölgedir, tespit ise ' +
            'algoritmanın bulduğu nesnedir ve daima bir anotasyonun içindedir.'],

    [id: 'tab-workflow', bolge: 'SIDEBAR', hedef: [tab: 'Workflow'], safeActivate: true,
     baslik: 'Workflow sekmesi — yaptıklarınızın kaydı',
     govde: 'Workflow sekmesi, oturumda yaptığınız işlemleri sırayla kaydeder — bir tür otomatik günlük. ' +
            'Buradan tüm adımları yeniden çalıştırılabilir bir Groovy betiğine dönüştürebilirsiniz ' +
            '(Create script), böylece aynı analizi başka slaytlara tekrarlamak kolaylaşır. Bir girdiye ' +
            'çift tıklamak o komutu aynı ayarlarla yeniden açar.'],

    [id: 'toolbar', bolge: 'TOOLBAR',
     baslik: 'Araç çubuğu — genel bakış',
     govde: 'Üstteki araç çubuğu en sık kullanılan komutları tek tıkla sunar: gezinme ve çizim araçları, ' +
            'parlaklık/kontrast, görünürlük anahtarları, ölçüm tabloları ve betik editörü. Bir düğmenin ' +
            'üzerine gelip beklerseniz adını ve kısayolunu gösteren ipucu belirir. Sonraki sayfalarda öne ' +
            'çıkan düğmeleri tanıyacağız.'],

    [id: 'tools-draw', bolge: 'TOOLBAR', hedef: [tools: 'ALL'], demo: 'TOOL_MOVE',
     baslik: 'Çizim araçları — Move, Rectangle, Polygon, Brush, Wand',
     govde: 'Çizim araçları araç çubuğunda yan yana durur: Move (gezinme, kısayol M), Rectangle (R), ' +
            'Ellipse (E), Polygon (P), Brush (fırça, B) ve Wand (kenar takipli sihirli değnek, W). Bir bölge ' +
            'çizmek için ilgili aracı seçip görüntüleyicide sürüklersiniz. İş bitince Move aracına dönmek iyi ' +
            'alışkanlıktır — yoksa yanlışlıkla yeni anotasyon çizebilirsiniz. (Bu adım örnek olarak Move aracını etkinleştirir.)'],

    [id: 'tool-points', bolge: 'TOOLBAR', hedef: [tools: 'POINTS'], demo: 'TOOL_POINTS',
     baslik: 'Points — sayım / işaretleme aracı',
     govde: 'Points (nokta) aracı, tek tek hücreleri elle işaretleyip saymak için kullanılır — örneğin bir ' +
            'referans sayımı yaparken. Her tıklama bir nokta bırakır; farklı sınıflar için ayrı nokta ' +
            'kümeleri oluşturabilirsiniz. Bu, otomatik tespitin doğruluğunu gözle denetlemenin pratik bir yoludur. ' +
            '(Bu adım örnek olarak Points aracını etkinleştirir.)'],

    // ── Uygulamalı alıştırma: kullanıcı istenen şekli çizince otomatik ilerler ──
    [id: 'practice-rectangle', bolge: 'TOOLBAR', hedef: [tools: 'RECTANGLE'], demo: 'TOOL_RECTANGLE', practice: 'RECTANGLE',
     baslik: 'Alıştırma — bir DİKDÖRTGEN çizin',
     govde: 'Sıra sizde! Rectangle (R) aracı sizin için seçildi. Görüntü üzerinde tıklayıp sürükleyerek bir ' +
            'dikdörtgen anotasyon çizin. Sihirbaz çizimi algılayınca kendiliğinden bir sonraki alıştırmaya geçer. ' +
            '(Bu alıştırma açık bir slayt ister; istemezseniz "İleri ▶" ile atlayabilirsiniz.)'],

    [id: 'practice-ellipse', bolge: 'TOOLBAR', hedef: [tools: 'ELLIPSE'], demo: 'TOOL_ELLIPSE', practice: 'ELLIPSE',
     baslik: 'Alıştırma — bir ELİPS çizin',
     govde: 'Ellipse (E) aracı seçildi. Görüntü üzerinde tıklayıp sürükleyerek bir elips çizin. ' +
            'Shift ile sürüklerseniz daire olur. Çizim algılanınca otomatik ilerlenir.'],

    [id: 'practice-polygon', bolge: 'TOOLBAR', hedef: [tools: 'POLYGON'], demo: 'TOOL_POLYGON', practice: 'POLYGON',
     baslik: 'Alıştırma — bir POLİGON çizin',
     govde: 'Polygon (P) aracı seçildi. Köşelere tek tek tıklayarak bir çokgen oluşturun; çift tıklayarak ' +
            '(ya da ilk noktaya dönerek) kapatın. İstersen tıklayıp sürükleyip bırakarak da çizebilirsin. ' +
            'Poligon tamamlanınca otomatik ilerlenir.'],

    [id: 'practice-points', bolge: 'TOOLBAR', hedef: [tools: 'POINTS'], demo: 'TOOL_POINTS', practice: 'POINTS',
     baslik: 'Alıştırma — NOKTA ekleyin',
     govde: 'Points aracı seçildi. Görüntüye tıklayarak bir veya birkaç nokta işaretleyin (sayım/işaretleme ' +
            'için kullanılır). Nokta ekleyince alttaki mesaj güncellenir; "İleri ▶" ile tura devam edin. ' +
            'Tebrikler — beş çizim aracını da denediniz!'],

    [id: 'selection-mode', bolge: 'TOOLBAR', hedef: [selectionMode: true], demo: 'SELECTION_MODE',
     baslik: 'Seçim modu — çizmek yerine nesne seçmek',
     govde: 'Seçim modu (Selection mode) düğmesi, araçların davranışını "çizme"den "seçme"ye çevirir. Açıkken, ' +
            'çizim aracıyla sürüklediğiniz alan yeni bir anotasyon oluşturmaz; o alana düşen mevcut nesneleri ' +
            'seçer. Çok sayıda hücre veya anotasyonu toplu işlemek için kullanışlıdır. (Bu adım örnek olarak seçim ' +
            'modunu açar; tur kapanınca eski durumu geri yükler.)'],

    [id: 'brightness', bolge: 'TOOLBAR', hedef: [action: 'BRIGHTNESS_CONTRAST'], demo: 'FIRE',
     baslik: 'Parlaklık & Kontrast — yalnızca ekranı değiştirir',
     govde: 'Parlaklık & Kontrast diyaloğu (araç çubuğundaki güneş simgesi ya da Shift+C) yalnızca ekranda ' +
            'gördüğünüzü değiştirir; analizde kullanılan piksel değerlerine dokunmaz. Kontrastı rahatça ' +
            'oynatabilirsiniz — ölçümleriniz etkilenmez. H&E\'de R/G/B kanallarını, H-DAB\'de hematoksilen/DAB ' +
            'kanallarını açıp kapatarak sinyali ayırt edebilirsiniz. (Bu adım diyaloğu örnek olarak açar.)'],

    [id: 'visibility', bolge: 'TOOLBAR',
     hedef: [actions: ['SHOW_ANNOTATIONS', 'FILL_ANNOTATIONS', 'SHOW_DETECTIONS', 'FILL_DETECTIONS']],
     baslik: 'Görünürlük: anotasyon/tespit göster-gizle, doldur',
     govde: 'Araç çubuğundaki görünürlük anahtarları kalabalık bir slaytta neyi gördüğünüzü denetler: ' +
            'anotasyonları göster/gizle, tespitleri göster/gizle ve bunların içini doldur/boşalt. Binlerce hücre ' +
            'dış çizgisini "doldurulmuş" yapmak uzaktan dağılımı çok daha okunaklı kılar. Bağlantıları ' +
            '(connections) ve sınıflandırma kaplamasını da buradan açıp kapatırsınız. (Bu anahtarlar yalnızca ' +
            'vurgulanır; tetiklenirse nesneleri gizleyeceği için otomatik çalıştırılmaz.)'],

    [id: 'opacity', bolge: 'TOOLBAR', hedef: [id: 'opacitySlider'],
     baslik: 'Opaklık kaydırıcısı — kaplama saydamlığı',
     govde: 'Opaklık kaydırıcısı, nesne ve sınıflandırma kaplamalarının saydamlığını ayarlar. Sola çekince ' +
            'kaplama silikleşir ve altındaki H&E daha çok görünür; sağa çekince kaplama belirginleşir. Bir piksel ' +
            'sınıflandırıcı maskesinin altındaki dokuyu kontrol ederken çok işe yarar.'],

    [id: 'measurements', bolge: 'TOOLBAR', hedef: [id: 'measurementTablesMenuButton'], demo: 'MENU',
     baslik: 'Ölçüm tabloları',
     govde: 'Ölçüm tabloları düğmesi, seçili nesnelerin (anotasyon veya tespit) tüm ölçümlerini bir tabloda ' +
            'açar: alan, sayım, yoğunluk, boya optik yoğunluğu ve daha fazlası. Tablodaki bir satıra tıklamak ' +
            'ilgili nesneyi görüntüleyicide seçer. Bu tablolar dışa aktarmanın (Veri dışa aktarma modülü) temelidir. ' +
            '(Bu adım düğmenin menüsünü örnek olarak açar.)'],

    [id: 'script-editor', bolge: 'TOOLBAR', hedef: [action: 'SCRIPT_EDITOR'], demo: 'FIRE',
     baslik: 'Betik düzenleyicisi — betikler ve konsol',
     govde: 'Betik düzenleyicisi (Script editor), QuPath\'in Groovy konsoludur; tekrar eden işleri otomatikleştirmenin ' +
            'yoludur. Atölye eklentisinin tüm yardımcıları aslında buradan çalışan betiklerdir. Menüden ' +
            'Automate → Script editor ile de açılır. Korkmayın — çoğu işi menülerden yapabilirsiniz; betikler ' +
            'yalnızca tekrar ve ölçeklenme içindir. (Bu adım editörü örnek olarak açar.)'],

    [id: 'command-list', bolge: null, demo: 'COMMAND_LIST',
     baslik: 'Command List (Ctrl/⌘+L) — en hızlı navigasyon',
     govde: 'Command List (Ctrl+L / ⌘+L), QuPath\'in "komut paleti"dir: aratabileceğiniz bir pencere açar, ' +
            'menüleri gezmek yerine komutun adını yazıp çalıştırırsınız. "cell detection", "estimate stain ' +
            'vectors", "brightness" gibi aramalar menü yolunu ezberleme yükünü ortadan kaldırır. Bir komutu ' +
            'hatırlamadığınızda ilk refleksiniz bu olsun. (Bu adım Command List\'i örnek olarak açar — bir ' +
            'araç çubuğu düğmesi değil, klavye kısayoludur; pencereyi kapatıp devam edin.)'],

    [id: 'close', bolge: null,
     link: [text: 'qupath-extension-training — resmî tur eklentisi (GitHub) ↗',
            url: 'https://github.com/qupath/qupath-extension-training'],
     baslik: 'Tur tamam — sıradaki adımlar',
     govde: 'Turu tamamladınız. Bu sayfaların yazılı karşılığı, panel tablosu ve klavye kısayolları için ' +
            'QuPath\'e Giriş — Arayüz turu bölümüne bakın. Daha derin, İngilizce ve buton-düzeyinde canlı vurgulu resmî ' +
            'tur için Pete Bankhead\'in qupath-extension-training eklentisini kurabilirsiniz (aşağıdaki bağlantı). ' +
            'Sıradaki adım: Hücre Tespiti.\n\n' +
            '⚠️ Bu sihirbaz yalnızca eğitim amaçlıdır; ölçüm veya klinik karar üretmez.']
]

// ── Headless: turu çalıştıramayız (GUI gerekir) — içeriği konsola özetle ─────
if (isHeadless) {
    println 'Arayüz turu için QuPath arayüzü gerekir; arayüzsüz (headless) kipte çalıştırılamaz.'
    println 'Tur ' + pages.size() + ' sayfadan oluşur:'
    pages.eachWithIndex { p, n -> println '  ' + (n + 1) + '. ' + p.baslik }
    println '⚠️ Yalnızca eğitim amaçlıdır; ölçüm veya klinik karar üretmez.'
    return
}

// ── Durum: geçerli sayfa indeksi + canlı vurgu kaydı + geri-yükleme değerleri ─
def stage = null
def idx          = new java.util.concurrent.atomic.AtomicInteger(0)
def alwaysTop    = new java.util.concurrent.atomic.AtomicBoolean(true)
def autoShow     = new java.util.concurrent.atomic.AtomicBoolean(true)     // sayfa açılınca vurgula + örnek komutu çalıştır
def highlightRef = new java.util.concurrent.atomic.AtomicReference(null)   // List<[node, origEffect]> | null
def originalTool = new java.util.concurrent.atomic.AtomicReference(null)   // PathTool — tur başındaki aktif araç
def originalSelMode = new java.util.concurrent.atomic.AtomicReference(null) // Boolean — tur başındaki seçim modu
def practiceRef  = new java.util.concurrent.atomic.AtomicReference(null)   // aktif alıştırma dinleyicisi [hierarchy, listener] | null
def render  // ileri bildirim

// ── Coarse bölge düğümü — geri çekilme (belirli öğe çözülemezse) ─────────────
def regionNode = { String region ->
    try {
        if (region == 'TOOLBAR') return gui.getToolBar()
        if (region == 'SIDEBAR') return gui.getAnalysisTabPane()
        if (region == 'VIEWER')  { def v = gui.getViewer(); return (v == null) ? null : v.getView() }
    } catch (Throwable t) { /* yöntem yok / sürüm farkı → metin-only degrade */ }
    return null
}

// ── Simgesel ad → QuPath Action (görünürlük / diyalog / betik düğmeleri) ─────
// Buton, tam olarak bu Action örneğinden üretildiği için kimlik (==) eşleşir.
def actionByName = { String key ->
    try {
        switch (key) {
            case 'BRIGHTNESS_CONTRAST': return gui.getCommonActions().BRIGHTNESS_CONTRAST
            case 'SCRIPT_EDITOR':       return gui.getAutomateActions().SCRIPT_EDITOR
            case 'SHOW_ANNOTATIONS':    return gui.getOverlayActions().SHOW_ANNOTATIONS
            case 'FILL_ANNOTATIONS':    return gui.getOverlayActions().FILL_ANNOTATIONS
            case 'SHOW_DETECTIONS':     return gui.getOverlayActions().SHOW_DETECTIONS
            case 'FILL_DETECTIONS':     return gui.getOverlayActions().FILL_DETECTIONS
        }
    } catch (Throwable t) {}
    return null
}

// ── Araç çubuğu düğümlerini Action KİMLİĞİNE göre bul ────────────────────────
// QuPath her düğmeye bağlı Action'ı node.getProperties() içinde saklar
// (ActionTools.getActionProperty). Tooltip metni yerine bu kimliği eşleştirmek,
// "point" gibi bir kelimenin polygon/polyline gibi komşu düğmeleri yakalamasını önler.
def buttonsForActions = { List actions ->
    def result = []
    try {
        def wanted = actions.findAll { it != null }
        if (wanted.isEmpty()) return result
        def tb = gui.getToolBar()
        if (tb == null) return result
        def candidates = []
        for (item in tb.getItems()) {
            if (item instanceof javafx.scene.Node) {
                candidates << item
                try { candidates.addAll(((javafx.scene.Node) item).lookupAll('*')) } catch (Throwable t) {}
            }
        }
        for (node in candidates) {
            try {
                def a = qupath.lib.gui.actions.ActionTools.getActionProperty((javafx.scene.Node) node)
                if (a != null && wanted.any { it.is(a) } && !result.any { it.is(node) }) result << node
            } catch (Throwable t) {}
        }
    } catch (Throwable t) {}
    return result
}

// ── Simgesel araç anahtarı → PathTool (vurgu + etkinleştirme + alıştırma) ────
def toolByKey = { String k ->
    def P = qupath.lib.gui.viewer.tools.PathTools
    switch (k) {
        case 'MOVE':      return P.MOVE
        case 'RECTANGLE': return P.RECTANGLE
        case 'ELLIPSE':   return P.ELLIPSE
        case 'LINE':      return P.LINE
        case 'POLYGON':   return P.POLYGON
        case 'POLYLINE':  return P.POLYLINE
        case 'BRUSH':     return P.BRUSH
        case 'POINTS':    return P.POINTS
    }
    return null
}

// ── Belirli öğeyi çöz: kenar paneli sekmesi ya da araç çubuğu düğmesi ─────────
// Döner: [nodes: List<Node>, spot: boolean]. nodes boşsa çağıran coarse bölgeye düşer.
def resolveTargets = { page ->
    def out = []
    try {
        def h = page.hedef
        if (h == null) return [nodes: out, spot: false]

        if (h.tab != null) {
            // Sekme başlığını (.tab) metnine göre bul — böylece tüm paneli değil O sekmeyi vurgular.
            def tp = gui.getAnalysisTabPane()
            if (tp != null) {
                def want = ((String) h.tab).toLowerCase(java.util.Locale.ROOT)
                for (tabNode in tp.lookupAll('.tab')) {
                    try {
                        def lbl = tabNode.lookup('.tab-label')
                        def txt = (lbl instanceof javafx.scene.control.Labeled) ? ((javafx.scene.control.Labeled) lbl).getText() : null
                        if (txt != null && txt.toLowerCase(java.util.Locale.ROOT).contains(want)) { out << tabNode; break }
                    } catch (Throwable t) {}
                }
            }
        } else if (h.tools != null) {
            def tm = gui.getToolManager()
            def acts = []
            if (h.tools == 'ALL') { for (t in tm.getTools()) acts << tm.getToolAction(t) }
            else { def pt = toolByKey((String) h.tools); if (pt != null) acts << tm.getToolAction(pt) }
            out.addAll(buttonsForActions(acts))
        } else if (h.selectionMode == true) {
            out.addAll(buttonsForActions([gui.getToolManager().getSelectionModeAction()]))
        } else if (h.action != null) {
            out.addAll(buttonsForActions([actionByName((String) h.action)]))
        } else if (h.actions != null) {
            out.addAll(buttonsForActions((h.actions as List).collect { actionByName((String) it) }))
        } else if (h.id != null) {
            def tb = gui.getToolBar()
            if (tb != null) { def n = tb.lookup('#' + ((String) h.id)); if (n != null) out << n }
        }
    } catch (Throwable t) { return [nodes: [], spot: false] }
    return [nodes: out, spot: !out.isEmpty()]
}

// ── Vurgu uygula / temizle (tümü FX iş parçacığında; orijinal efektleri geri yükle) ─
def clearHighlight = { ->
    javafx.application.Platform.runLater {
        def cur = highlightRef.getAndSet(null)
        if (cur != null) for (pair in cur) {
            try { ((javafx.scene.Node) pair[0]).setEffect((javafx.scene.effect.Effect) pair[1]) } catch (Throwable t) {}
        }
    }
}
// spot=true → küçük tekil öğe (düğme/sekme): ince, keskin turuncu iç çerçeve.
// spot=false → büyük bitişik bölge (araç çubuğu/panel/görüntüleyici): daha geniş iç parıltı.
// Neden hep İÇ parıltı: dış (DropShadow) parıltı bitişik döşenen panolarda komşu bölmeler,
// SplitPane kırpması ve z-sırası yüzünden görünmez kalıyordu; iç parıltı düğümün KENDİ
// sınırları içinde, içeriğinin (opak tuval/simge) ÜSTÜNE çizildiğinden her zaman görünür.
def applyHighlight = { List nodes, boolean spot ->
    javafx.application.Platform.runLater {
        // önce önceki vurguyu ATOMİK geri yükle (hızlı gezinmede takılı parıltı kalmasın)
        def prev = highlightRef.getAndSet(null)
        if (prev != null) for (pair in prev) {
            try { ((javafx.scene.Node) pair[0]).setEffect((javafx.scene.effect.Effect) pair[1]) } catch (Throwable t) {}
        }
        if (nodes == null || nodes.isEmpty()) return
        try {
            double r = spot ? 8.0 : 16.0
            double c = spot ? 0.85 : 0.5
            def saved = []
            for (node in nodes) {
                def n = (javafx.scene.Node) node
                saved << [n, n.getEffect()]
                def glow = new javafx.scene.effect.InnerShadow()
                glow.setColor(javafx.scene.paint.Color.web('#FF7A00'))   // turuncu — ilham eklentisinin rengi
                glow.setRadius(r)
                glow.setChoke(c)
                n.setEffect(glow)
            }
            highlightRef.set(saved)
        } catch (Throwable t) { highlightRef.set(null) }
    }
}

// ── Sekme etkinleştirme: güvenli, salt-görünüm — hedef sekmeyi seçer ─────────
def activateTarget = { pg ->
    try {
        def h = pg.hedef
        if (pg.safeActivate != true || h == null || h.tab == null) return
        def tp = gui.getAnalysisTabPane()
        if (tp == null) return
        def want = ((String) h.tab).toLowerCase(java.util.Locale.ROOT)
        javafx.application.Platform.runLater {
            try {
                for (tab in tp.getTabs()) {
                    def txt = tab.getText()
                    if (txt != null && txt.toLowerCase(java.util.Locale.ROOT).contains(want)) {
                        if (tp.getSelectionModel().getSelectedItem() != tab) tp.getSelectionModel().select(tab)
                        break
                    }
                }
            } catch (Throwable ex) {}
        }
    } catch (Throwable ex) {}
}

// ── Örnek komut çalıştır: araç etkinleştir / diyalog-menü aç / Ctrl+L ────────
// Yalnızca ekranı, aktif aracı ya da açık pencereleri değiştirir; nesne/ölçüm/hiyerarşi
// DEĞİŞMEZ. Görünürlük anahtarları (demo == null) tetiklenmez — tetiklenirse gizlerler.
// Aç/kapa düğmeleri (seçim modu, parlaklık) yalnızca "kapalı" iken açılır (idempotent).
def demoRun = { page, List nodes ->
    javafx.application.Platform.runLater {
        try {
            def d = page.demo
            if (d == null) return
            if (d.startsWith('TOOL_')) {
                try { def pt = toolByKey(d.substring(5)); if (pt != null) gui.getToolManager().setSelectedTool(pt) } catch (Throwable t) {}
                return
            }
            if (d == 'SELECTION_MODE') {
                for (n in nodes) if (n instanceof javafx.scene.control.ToggleButton) {
                    def tb = (javafx.scene.control.ToggleButton) n
                    if (!tb.isSelected()) tb.fire()
                    break
                }
                return
            }
            if (d == 'FIRE') {
                for (n in nodes) {
                    if (n instanceof javafx.scene.control.ToggleButton) {
                        def tb = (javafx.scene.control.ToggleButton) n
                        if (!tb.isSelected()) tb.fire()
                        break
                    }
                    if (n instanceof javafx.scene.control.ButtonBase) { ((javafx.scene.control.ButtonBase) n).fire(); break }
                }
                return
            }
            if (d == 'MENU') {
                for (n in nodes) if (n instanceof javafx.scene.control.MenuButton) { ((javafx.scene.control.MenuButton) n).show(); break }
                return
            }
            if (d == 'COMMAND_LIST') {
                def tb = gui.getToolBar()
                def scene = (tb == null) ? null : tb.getScene()
                if (scene != null) {
                    def kc = new javafx.scene.input.KeyCodeCombination(
                        javafx.scene.input.KeyCode.L, javafx.scene.input.KeyCombination.SHORTCUT_DOWN)
                    def rn = scene.getAccelerators().get(kc)
                    if (rn != null) rn.run()
                }
                return
            }
        } catch (Throwable t) {}
    }
}

// ── Tur kapanınca başlangıç durumunu geri yükle: aktif araç + seçim modu ─────
def restoreState = { ->
    javafx.application.Platform.runLater {
        try {
            def tm = gui.getToolManager()
            def ot = originalTool.get()
            if (ot != null) { try { tm.setSelectedTool(ot) } catch (Throwable t) {} }
            def osm = originalSelMode.get()
            if (osm != null) {
                for (n in buttonsForActions([tm.getSelectionModeAction()])) if (n instanceof javafx.scene.control.ToggleButton) {
                    def tb = (javafx.scene.control.ToggleButton) n
                    if (tb.isSelected() != ((Boolean) osm).booleanValue()) tb.fire()
                    break
                }
            }
        } catch (Throwable t) {}
    }
}

// ── Görsel yükleyici: /images/tour/<sayfa-id>.{gif,png,jpg} — JAR kaynağından ─
def scriptClass = this.getClass()
def imageUrl = { String base ->
    for (ext in ['.gif', '.png', '.jpg']) {
        try {
            def c = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension')
            def u = c.getResource(base + ext)
            if (u != null) return u.toExternalForm()
        } catch (Throwable t) {}
        try {
            def u = scriptClass.getResource(base + ext)
            if (u != null) return u.toExternalForm()
        } catch (Throwable t) {}
    }
    return null
}
def imageNode = { String base ->
    def url = imageUrl(base)
    if (url == null) return null
    try {
        def img = new javafx.scene.image.Image(url, 740d, 0d, true, true, true)
        def iv = new javafx.scene.image.ImageView(img)
        iv.setPreserveRatio(true); iv.setFitWidth(740d); iv.setSmooth(true)
        return iv
    } catch (Throwable t) { return null }
}

// ── Alıştırma (uygulamalı çizim adımları) ───────────────────────────────────
// Çizilen ROI tipini beklenen tiple eşleştir.
def matchesRoi = { roi, String type ->
    if (roi == null) return false
    switch (type) {
        case 'RECTANGLE': return roi instanceof qupath.lib.roi.RectangleROI
        case 'ELLIPSE':   return roi instanceof qupath.lib.roi.EllipseROI
        case 'POLYGON':   return roi instanceof qupath.lib.roi.PolygonROI
        case 'POINTS':    return roi instanceof qupath.lib.roi.PointsROI
        case 'LINE':      return roi instanceof qupath.lib.roi.LineROI
    }
    return false
}
def PRACTICE_HINT = [
    RECTANGLE: 'Görüntü üzerinde tıklayıp sürükleyerek bir DİKDÖRTGEN çizin…',
    ELLIPSE:   'Tıklayıp sürükleyerek bir ELİPS çizin…',
    POLYGON:   'Köşelere tıklayarak bir POLİGON çizin (çift tıkla ya da ilk noktaya dönerek bitirin)…',
    POINTS:    'Görüntüye tıklayarak bir veya birkaç NOKTA ekleyin…'
]
def PRACTICE_DONE = [RECTANGLE: 'Dikdörtgen', ELLIPSE: 'Elips', POLYGON: 'Poligon', POINTS: 'Nokta']

// Aktif alıştırma dinleyicisini kaldır (gezinme/kapanışta).
def detachPractice = { ->
    def p = practiceRef.getAndSet(null)
    if (p != null) { try { p.hierarchy.removeListener(p.listener) } catch (Throwable t) {} }
}

// Toplam nokta sayısı — Points aracı mevcut bir anotasyona da ekleyebildiği için
// "yeni anotasyon" yerine nokta sayısı ARTIŞINI izleriz (daha güvenilir).
def countPoints = { hierarchy ->
    int n = 0
    try {
        for (a in hierarchy.getAnnotationObjects()) {
            def r = a.getROI()
            if (r instanceof qupath.lib.roi.PointsROI) n += ((qupath.lib.roi.PointsROI) r).getNumPoints()
        }
    } catch (Throwable t) {}
    return n
}

// Kullanıcı istenen şekli çizince durum etiketini günceller.
// NOKTA: nokta sayısı artınca "devam edin" mesajı (otomatik geçmez — son alıştırma).
// Diğerleri: beklenen tipte YENİ bir anotasyon çizilince otomatik bir sonraki adıma geçer.
def setupPractice = { page, javafx.scene.control.Label statusLabel, int pageIdx ->
    def imageData = null
    try { imageData = gui.getImageData() } catch (Throwable t) {}
    if (imageData == null) {
        statusLabel.setText('⚠ Bu alıştırma için önce bir slayt açın. "İleri ▶" ile atlayabilirsiniz.')
        statusLabel.setStyle('-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: -fx-text-base-color; -fx-opacity: 0.8;')
        return
    }
    def hierarchy = imageData.getHierarchy()
    String expected = (String) page.practice
    boolean isPoints = (expected == 'POINTS')
    int basePoints = isPoints ? countPoints(hierarchy) : 0
    def baseline = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap())
    if (!isPoints) baseline.addAll(hierarchy.getAnnotationObjects())
    statusLabel.setText('⏳ ' + (PRACTICE_HINT[expected] ?: 'Bir anotasyon çizin…'))
    def done = new java.util.concurrent.atomic.AtomicBoolean(false)
    def listener = ({ evt ->
        try {
            if (done.get()) return
            boolean hit = isPoints ? (countPoints(hierarchy) > basePoints)
                                   : (hierarchy.getAnnotationObjects().find { a -> !baseline.contains(a) && matchesRoi(a.getROI(), expected) } != null)
            if (hit) {
                done.set(true)
                javafx.application.Platform.runLater {
                    detachPractice()   // dinleyici tetikleme döngüsü DIŞINDA kaldır (güvenli)
                    statusLabel.setStyle('-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1a8a5a;')
                    if (isPoints) {
                        statusLabel.setText('✓ Nokta eklendi! Alıştırmalar tamam — "İleri ▶" ile tura devam edin.')
                    } else {
                        statusLabel.setText('✓ ' + (PRACTICE_DONE[expected] ?: 'Şekil') + ' algılandı — sonraki adıma geçiliyor…')
                        if (idx.get() == pageIdx && pageIdx + 1 < pages.size()) {
                            def pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(900))
                            pause.setOnFinished({ if (idx.get() == pageIdx) { clearHighlight(); idx.set(pageIdx + 1); render() } })
                            pause.play()
                        }
                    }
                }
            }
        } catch (Throwable t) {}
    } as qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener)
    try { hierarchy.addListener(listener) } catch (Throwable t) { return }
    practiceRef.set([hierarchy: hierarchy, listener: listener])
}

// ── Render: her gezinmede sahneyi sıfırdan kurar ────────────────────────────
render = { ->
    if (!javafx.application.Platform.isFxApplicationThread()) { javafx.application.Platform.runLater { render() }; return }
    if (stage == null) return
    detachPractice()   // önceki sayfanın alıştırma dinleyicisini bırak
    stage.setAlwaysOnTop(alwaysTop.get())
    int i = idx.get()
    def page = pages[i]

    def title = new javafx.scene.control.Label(page.baslik)
    title.setStyle('-fx-font-size: 16px; -fx-font-weight: bold;')
    title.setWrapText(true)

    def prog = new javafx.scene.control.Label('Adım ' + (i + 1) + ' / ' + pages.size())
    prog.setStyle('-fx-opacity: 0.7; -fx-font-size: 12px;')

    def body = new javafx.scene.control.Label(page.govde)
    body.setWrapText(true)
    body.setStyle('-fx-font-size: 13px; -fx-line-spacing: 2px;')
    def scroll = new javafx.scene.control.ScrollPane(body)
    scroll.setFitToWidth(true)
    scroll.setStyle('-fx-background-color: transparent;')
    javafx.scene.layout.VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS)

    def center = new javafx.scene.layout.VBox(10)
    center.setPadding(new javafx.geometry.Insets(16))
    center.getChildren().addAll(title, prog, scroll)

    // Görsel: /images/tour/<sayfa-id> — varsa başlık ile gövde arasına ekle (yoksa yok say).
    def iv = imageNode('/images/tour/' + page.id)
    if (iv != null) center.getChildren().add(2, iv)

    // Bağlantı (ör. son sayfada Bankhead referansı) — tıklanınca varsayılan tarayıcıda açılır.
    if (page.link != null) {
        def lk = new javafx.scene.control.Hyperlink((String) page.link.text)
        lk.setWrapText(true)
        lk.setStyle('-fx-font-size: 13px;')
        def linkUrl = (String) page.link.url
        lk.setTooltip(new javafx.scene.control.Tooltip(linkUrl))
        lk.setOnAction({ try { qupath.lib.gui.QuPathGUI.openInBrowser(linkUrl) } catch (Throwable t) {} })
        center.getChildren().add(lk)
    }

    // Vurgu hedefi — belirli öğe (sekme/araç çubuğu düğmesi); yoksa coarse bölge.
    def tgt = resolveTargets(page)
    def hiNodes = (List) tgt.nodes
    boolean hiSpot = (boolean) tgt.spot
    if (hiNodes.isEmpty() && page.bolge != null) {
        def rn = regionNode((String) page.bolge)
        if (rn != null) { hiNodes = [rn]; hiSpot = false }
    }
    def hiBtn = null                 // ToggleButton | null
    List nodesF = hiNodes            // geri-çekilme sonrası kesin liste
    boolean spotF = hiSpot
    if (!hiNodes.isEmpty()) {
        String hiLbl = !spotF ? 'Bu bölgeyi vurgula' : (nodesF.size() > 1 ? 'Bu öğeleri vurgula' : 'Bu öğeyi vurgula')
        hiBtn = new javafx.scene.control.ToggleButton(hiLbl)
        def hiRef = hiBtn
        hiRef.setOnAction({
            if (hiRef.isSelected()) { applyHighlight(nodesF, spotF); activateTarget(page) }
            else clearHighlight()
        })
        center.getChildren().add(hiBtn)
        if (page.bolge == 'VIEWER' && page.hedef == null) {
            def vhint = new javafx.scene.control.Label(
                'İpucu: vurgu görüntüleyiciye uygulanır; bu pencere önünü kapatıyorsa kenara çekin.')
            vhint.setWrapText(true)
            vhint.setStyle('-fx-opacity: 0.7; -fx-font-size: 11px; -fx-font-style: italic;')
            center.getChildren().add(vhint)
        }
        if (autoShow.get()) {
            applyHighlight(nodesF, spotF); activateTarget(page); hiBtn.setSelected(true)
        }
    } else if (page.bolge != null) {
        def hi = new javafx.scene.control.ToggleButton('Bu bölgeyi vurgula')
        hi.setDisable(true)
        hi.setTooltip(new javafx.scene.control.Tooltip(
            'Bu öğe şu an erişilemiyor (slayt açık değil ya da QuPath sürümü farklı). Sayfa metni yine de geçerli.'))
        center.getChildren().add(hi)
    }

    // Örnek komut düğmesi — demo tanımlı (alıştırma OLMAYAN) sayfalarda komutu elle çalıştırılabilir kılar.
    if (page.demo != null && page.practice == null) {
        List demoNodes = nodesF
        def demoBtn = new javafx.scene.control.Button('▶ Bu adımı çalıştır')
        demoBtn.setOnAction({ demoRun(page, demoNodes) })
        center.getChildren().add(demoBtn)
        // Pencere/menü açan adımlarda kullanıcıya "kapatıp devam et" bilgisini ver.
        boolean opensWindow = (page.demo == 'FIRE' || page.demo == 'MENU' || page.demo == 'COMMAND_LIST')
        def demoHint = new javafx.scene.control.Label(opensWindow
            ? 'Bu adım ilgili komutu örnek olarak açar. Açılan pencere/menü bu sihirbazın önüne gelebilir; ' +
              'inceledikten sonra kapatın ve "İleri ▶" ile tura devam edin.'
            : 'Bu adım ilgili aracı örnek olarak etkinleştirir; araç çubuğunda seçili duruma geçtiğini görürsünüz. ' +
              'Tur kapanınca başlangıçtaki araç geri yüklenir. "İleri ▶" ile devam edin.')
        demoHint.setWrapText(true); demoHint.setMaxWidth(Double.MAX_VALUE)
        demoHint.setStyle('-fx-opacity: 0.75; -fx-font-size: 11px; -fx-font-style: italic;')
        center.getChildren().add(demoHint)
        if (autoShow.get()) demoRun(page, demoNodes)
    }

    // Uygulamalı alıştırma: ilgili çizim aracını seç + beklenen şekil çizilince otomatik ilerle.
    if (page.practice != null) {
        def practiceStatus = new javafx.scene.control.Label()
        practiceStatus.setWrapText(true); practiceStatus.setMaxWidth(Double.MAX_VALUE)
        practiceStatus.setStyle('-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: -fx-accent;')
        center.getChildren().add(practiceStatus)
        demoRun(page, nodesF)              // ilgili çizim aracını seç (alıştırma için şart)
        setupPractice(page, practiceStatus, i)
    }

    // Alt çubuk: "Üstte tut" + "Otomatik göster" (sol) + disclaimer + gezinme düğmeleri (sağ)
    def topChk = new javafx.scene.control.CheckBox('Üstte tut')
    topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n ->
        alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n)
    } as javafx.beans.value.ChangeListener)

    // "Otomatik göster" — açık (varsayılan) iken her sayfa hedefini vurgular ve örnek komutu çalıştırır.
    def autoChk = new javafx.scene.control.CheckBox('Otomatik göster')
    autoChk.setSelected(autoShow.get())
    autoChk.setDisable(page.bolge == null && page.hedef == null && page.demo == null)  // intro/kapanış: hiçbir hedef yok
    autoChk.selectedProperty().addListener({ obs, o, n ->
        autoShow.set(n)
        if (n) {
            if (hiBtn != null) { applyHighlight(nodesF, spotF); activateTarget(page); hiBtn.setSelected(true) }
            if (page.demo != null) demoRun(page, nodesF)
        } else {
            clearHighlight(); if (hiBtn != null) hiBtn.setSelected(false)
        }
    } as javafx.beans.value.ChangeListener)

    def spacer = new javafx.scene.layout.Region()
    javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)

    def backBtn = new javafx.scene.control.Button('◀ Geri')
    backBtn.setDisable(i == 0)
    backBtn.setOnAction({ clearHighlight(); idx.set(Math.max(0, i - 1)); render() })

    boolean last = (i == pages.size() - 1)
    def nextBtn = new javafx.scene.control.Button(last ? 'Bitir' : 'İleri ▶')
    nextBtn.setOnAction({
        clearHighlight()
        if (last) stage.close()
        else { idx.set(i + 1); render() }
    })

    def closeBtn = new javafx.scene.control.Button('Kapat')
    closeBtn.setOnAction({ clearHighlight(); stage.close() })

    def bar = new javafx.scene.layout.HBox(8)
    bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().addAll(topChk, autoChk)
    bar.getChildren().add(spacer)
    bar.getChildren().addAll(closeBtn, backBtn, nextBtn)

    def disclaimer = new javafx.scene.control.Label('Yalnızca eğitim amaçlıdır; ölçüm veya klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')

    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(center)
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 800, 600))
}

// ── Açılış ───────────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        // Tur başındaki durumu yakala (kapanışta geri yüklemek için).
        try { originalTool.set(gui.getToolManager().getSelectedTool()) } catch (Throwable t) {}
        try {
            for (n in buttonsForActions([gui.getToolManager().getSelectionModeAction()]))
                if (n instanceof javafx.scene.control.ToggleButton) {
                    originalSelMode.set(Boolean.valueOf(((javafx.scene.control.ToggleButton) n).isSelected())); break
                }
        } catch (Throwable t) {}

        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('QuPath arayüz turu')
        stage.setAlwaysOnTop(alwaysTop.get())
        stage.setOnHidden({ clearHighlight(); restoreState(); detachPractice() })
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println '✓ QuPath arayüz turu açıldı.'
