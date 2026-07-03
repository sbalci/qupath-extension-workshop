/**
 * Modül 1 - QuPath Arayüz Turu (interaktif gezinti)
 * -------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Tek pencerede, sayfa sayfa QuPath arayüzünü Türkçe anlatır
 *   (görüntüleyici, kenar paneli sekmeleri, araç çubuğu, parlaklık/kontrast,
 *   Command List...). Her sayfada ilgili ÖĞE — belirli bir sekme (Project,
 *   Image, Annotations...) ya da araç çubuğu düğmesi (parlaklık, opaklık,
 *   ölçüm tablosu...) — canlı pencerede turuncu bir çerçeveyle vurgulanabilir;
 *   öğe bulunamazsa bütün bölgeye (araç çubuğu / kenar paneli / görüntüleyici)
 *   düşer. Hiçbir öğeye erişilemezse (slayt açık değil ya da QuPath sürümü
 *   farklı) sayfa yine de metniyle çalışır; vurgu düğmesi pasifleşir.
 *   Hiçbir nesneyi, ölçümü veya hiyerarşiyi DEĞİŞTİRMEZ.
 *
 *   Pete Bankhead'in qupath-extension-training (Apache-2.0) eklentisinden
 *   esinlenen, atölyeye özgü hafif bir Türkçe uyarlamadır. Resmî eklenti
 *   İngilizce, buton-düzeyinde canlı vurgu + otomatik ekran görüntüleri sunar.
 *
 * KULLANIM:
 *   1. (Önerilir) Bir slayt açın — görüntüleyici/meta veri bölgeleri dolu olsun.
 *   2. [Extensions → Atölye → Modüller → Modül 1 - Arayüz turu]
 *   3. İleri / Geri ile gezinin; "Bu öğeyi vurgula" ile canlı arayüzü işaretleyin.
 *
 * KAYNAK / İLHAM:
 *   qupath/qupath-extension-training (Apache-2.0, Pete Bankhead)
 *   https://github.com/qupath/qupath-extension-training
 *   Statik karşılığı: Modül 1 — Arayüz turu (panel tablosu + kısayollar).
 *
 * ⚠️ Yalnızca eğitim amaçlıdır; ölçüm veya klinik karar üretmez.
 */

import qupath.fx.dialogs.Dialogs

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Tur sayfaları (id · vurgu bölgesi · başlık · gövde) ─────────────────────
// bolge ∈ {TOOLBAR, SIDEBAR, VIEWER, null}. null → vurgu düğmesi gösterilmez.
def pages = [
    [id: 'intro', bolge: null,
     baslik: 'Hoş geldiniz — QuPath arayüz turu',
     govde: 'Bu tur QuPath arayüzünü adım adım tanıtır. Her sayfada bir öğe anlatılır; ' +
            'istediğinizde "Bu öğeyi vurgula" düğmesiyle ilgili sekme, düğme ya da bölge canlı ' +
            'pencerede turuncu bir çerçeveyle işaretlenir. En verimli kullanım için önce bir slayt açın — böylece ' +
            'görüntüleyici ve meta veri sekmeleri de dolu olur.\n\n' +
            'İleri / Geri düğmeleriyle gezinin; sihirbaz penceresi her zaman üstte kalsın ' +
            'isterseniz alttaki "Üstte tut" kutusu işaretli kalsın. Bu sihirbaz Pete Bankhead\'in ' +
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
            'görüyorsanız meta veri eksik demektir; Modül 1\'deki kalibrasyon adımına dönün. Görüntü ' +
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

    [id: 'tools-draw', bolge: 'TOOLBAR', hedef: [ipucu: ['move', 'rectangle', 'ellipse', 'polygon', 'polyline', 'brush', 'wand']],
     baslik: 'Çizim araçları — Move, Rectangle, Polygon, Brush, Wand',
     govde: 'Çizim araçları araç çubuğunda yan yana durur: Move (gezinme, kısayol M), Rectangle (R), ' +
            'Ellipse (E), Polygon (P), Brush (fırça, B) ve Wand (kenar takipli sihirli değnek, W). Bir bölge ' +
            'çizmek için ilgili aracı seçip görüntüleyicide sürüklersiniz. İş bitince Move aracına dönmek iyi ' +
            'alışkanlıktır — yoksa yanlışlıkla yeni anotasyon çizebilirsiniz.'],

    [id: 'tool-points', bolge: 'TOOLBAR', hedef: [ipucu: ['point']],
     baslik: 'Points — sayım / işaretleme aracı',
     govde: 'Points (nokta) aracı, tek tek hücreleri elle işaretleyip saymak için kullanılır — örneğin bir ' +
            'referans sayımı yaparken. Her tıklama bir nokta bırakır; farklı sınıflar için ayrı nokta ' +
            'kümeleri oluşturabilirsiniz. Bu, otomatik tespitin doğruluğunu gözle denetlemenin pratik bir yoludur.'],

    [id: 'selection-mode', bolge: 'TOOLBAR', hedef: [ipucu: ['selection']],
     baslik: 'Seçim modu — çizmek yerine nesne seçmek',
     govde: 'Seçim modu (Selection mode) düğmesi, araçların davranışını "çizme"den "seçme"ye çevirir. Açıkken, ' +
            'çizim aracıyla sürüklediğiniz alan yeni bir anotasyon oluşturmaz; o alana düşen mevcut nesneleri ' +
            'seçer. Çok sayıda hücre veya anotasyonu toplu işlemek için kullanışlıdır.'],

    [id: 'brightness', bolge: 'TOOLBAR', hedef: [ipucu: ['brightness', 'contrast']],
     baslik: 'Parlaklık & Kontrast — yalnızca ekranı değiştirir',
     govde: 'Parlaklık & Kontrast diyaloğu (araç çubuğundaki güneş simgesi ya da Shift+C) yalnızca ekranda ' +
            'gördüğünüzü değiştirir; analizde kullanılan piksel değerlerine dokunmaz. Kontrastı rahatça ' +
            'oynatabilirsiniz — ölçümleriniz etkilenmez. H&E\'de R/G/B kanallarını, H-DAB\'de hematoksilen/DAB ' +
            'kanallarını açıp kapatarak sinyali ayırt edebilirsiniz.'],

    [id: 'visibility', bolge: 'TOOLBAR', hedef: [ipucu: ['annotation', 'detection']],
     baslik: 'Görünürlük: anotasyon/tespit göster-gizle, doldur',
     govde: 'Araç çubuğundaki görünürlük anahtarları kalabalık bir slaytta neyi gördüğünüzü denetler: ' +
            'anotasyonları göster/gizle, tespitleri göster/gizle ve bunların içini doldur/boşalt. Binlerce hücre ' +
            'dış çizgisini "doldurulmuş" yapmak uzaktan dağılımı çok daha okunaklı kılar. Bağlantıları ' +
            '(connections) ve sınıflandırma kaplamasını da buradan açıp kapatırsınız.'],

    [id: 'opacity', bolge: 'TOOLBAR', hedef: [id: 'opacitySlider'],
     baslik: 'Opaklık kaydırıcısı — kaplama saydamlığı',
     govde: 'Opaklık kaydırıcısı, nesne ve sınıflandırma kaplamalarının saydamlığını ayarlar. Sola çekince ' +
            'kaplama silikleşir ve altındaki H&E daha çok görünür; sağa çekince kaplama belirginleşir. Bir piksel ' +
            'sınıflandırıcı maskesinin altındaki dokuyu kontrol ederken çok işe yarar.'],

    [id: 'measurements', bolge: 'TOOLBAR', hedef: [id: 'measurementTablesMenuButton'],
     baslik: 'Ölçüm tabloları',
     govde: 'Ölçüm tabloları düğmesi, seçili nesnelerin (anotasyon veya tespit) tüm ölçümlerini bir tabloda ' +
            'açar: alan, sayım, yoğunluk, boya optik yoğunluğu ve daha fazlası. Tablodaki bir satıra tıklamak ' +
            'ilgili nesneyi görüntüleyicide seçer. Bu tablolar dışa aktarmanın (Modül 9) temelidir.'],

    [id: 'script-editor', bolge: 'TOOLBAR', hedef: [ipucu: ['script']],
     baslik: 'Script editörü — betikler ve konsol',
     govde: 'Betik (script) editörü QuPath\'in Groovy konsoludur; tekrar eden işleri otomatikleştirmenin ' +
            'yoludur. Atölye eklentisinin tüm yardımcıları aslında buradan çalışan betiklerdir. Menüden ' +
            'Automate → Script editor ile de açılır. Korkmayın — çoğu işi menülerden yapabilirsiniz; betikler ' +
            'yalnızca tekrar ve ölçeklenme içindir.'],

    [id: 'command-list', bolge: 'TOOLBAR',
     baslik: 'Command List (Ctrl/⌘+L) — en hızlı navigasyon',
     govde: 'Command List (Ctrl+L / ⌘+L), QuPath\'in "komut paleti"dir: aratabileceğiniz bir pencere açar, ' +
            'menüleri gezmek yerine komutun adını yazıp çalıştırırsınız. "cell detection", "estimate stain ' +
            'vectors", "brightness" gibi aramalar menü yolunu ezberleme yükünü ortadan kaldırır. Bir komutu ' +
            'hatırlamadığınızda ilk refleksiniz bu olsun.'],

    [id: 'close', bolge: null,
     baslik: 'Tur tamam — sıradaki adımlar',
     govde: 'Turu tamamladınız. Bu sayfaların yazılı karşılığı, panel tablosu ve klavye kısayolları için ' +
            'Modül 1 — Arayüz turu bölümüne bakın. Daha derin, İngilizce ve buton-düzeyinde canlı vurgulu resmî ' +
            'tur için Pete Bankhead\'in qupath-extension-training eklentisini kurabilirsiniz (Ekler → Arayüz ' +
            'Turu bölümündeki bağlantılar). Sıradaki adım: Modül 2 — Hücre tespiti.\n\n' +
            '⚠️ Bu sihirbaz yalnızca eğitim amaçlıdır; ölçüm veya klinik karar üretmez.']
]

// ── Headless: turu çalıştıramayız (GUI gerekir) — içeriği konsola özetle ─────
if (isHeadless) {
    println 'Arayüz turu için QuPath arayüzü gerekir (headless çalıştırılamaz).'
    println 'Tur ' + pages.size() + ' sayfadan oluşur:'
    pages.eachWithIndex { p, n -> println '  ' + (n + 1) + '. ' + p.baslik }
    println '⚠️ Yalnızca eğitim amaçlıdır; ölçüm veya klinik karar üretmez.'
    return
}

// ── Durum: geçerli sayfa indeksi + canlı vurgu kaydı ────────────────────────
def stage = null
def idx          = new java.util.concurrent.atomic.AtomicInteger(0)
def alwaysTop    = new java.util.concurrent.atomic.AtomicBoolean(true)
def autoHighlight = new java.util.concurrent.atomic.AtomicBoolean(true)   // #1: sayfa açılınca hedefi otomatik vurgula
def highlightRef = new java.util.concurrent.atomic.AtomicReference(null)   // List<[node, origEffect]> | null
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

// ── Belirli öğeyi çöz: kenar paneli sekmesi ya da araç çubuğu düğmesi ─────────
// page.hedef ∈ { [tab:'Annotations'] | [id:'opacitySlider'] | [ipucu:['brightness',…]] }
// Döner: [nodes: List<Node>, spot: boolean]. nodes boşsa çağıran coarse bölgeye düşer.
// Yalnız kararlı yollar: getAnalysisTabPane + JavaFX standart skin sınıfları (.tab/.tab-label)
// metne göre; araç çubuğunda setId'li #id (opacitySlider / measurementTablesMenuButton) ya da
// getItems() üzerinden tooltip anahtar-sözcük eşleşmesi. Hepsi en-iyi-çaba; hata → boş → coarse.
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
        } else if (h.id != null || h.ipucu != null) {
            // Araç çubuğu: önce setId'li kararlı düğüm, sonra tooltip anahtar-sözcükleri
            // (eşleşen TÜM düğmeler → araç/görünürlük kümesi birlikte vurgulanır).
            def tb = gui.getToolBar()
            if (tb != null) {
                if (h.id != null) {
                    def n = tb.lookup('#' + ((String) h.id))
                    if (n != null) out << n
                }
                if (out.isEmpty() && h.ipucu != null) {
                    def keys = (h.ipucu as List).collect { ((String) it).toLowerCase(java.util.Locale.ROOT) }
                    for (item in tb.getItems()) {
                        try {
                            if (item instanceof javafx.scene.control.Control) {
                                def tt = ((javafx.scene.control.Control) item).getTooltip()
                                def tip = (tt == null) ? null : tt.getText()
                                if (tip != null && keys.any { tip.toLowerCase(java.util.Locale.ROOT).contains(it) }) out << item
                            }
                        } catch (Throwable t) {}
                    }
                }
            }
        }
    } catch (Throwable t) { return [nodes: [], spot: false] }
    return [nodes: out, spot: !out.isEmpty()]
}

// ── Vurgu uygula / temizle (tümü FX iş parçacığında; orijinal efektleri geri yükle) ─
// Tüm ref mutasyonları + setEffect TEK bir runLater içinde (FX iş parçacığı) yapılır;
// böylece hızlı gezinmede clear/apply görevleri sıralı ve atomik çalışır, takılı parıltı olmaz.
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

// ── Sekme etkinleştirme (#2): SADECE güvenli, salt-görünüm eylem — hedef sekmeyi seçer.
// Yalnız page.safeActivate == true ve hedef bir SEKME ise çalışır. Araç çubuğu düğmeleri
// ASLA fire() edilmez: görünürlük anahtarları görünümü değiştirir, düğmeler diyalog açar →
// sihirbazın salt-okur ("hiçbir şeyi değiştirmez") sözleşmesini bozardı. Sekme seçimi ise
// zararsız, kullanıcı tarafından tek tıkla geri alınabilir bir görünüm değişikliğidir.
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

// ── Görsel yükleyici (#4): /images/tour/<sayfa-id>.{gif,png,jpg} — JAR kaynağından.
// Eklenti sınıfının classloader'ı JAR kaynaklarını taşır; yoksa script sınıfına düşer;
// kaynak yoksa null → sayfa metin-only kalır (Automate → Project scripts'te de sorunsuz).
// JavaFX Image, GIF89a'yı yerel olarak (ek kütüphanesiz) canlandırır; PNG/JPEG statiktir.
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
        // arka planda yükle (son parametre true) → FX iş parçacığını bloklamaz; yükleme
        // başarısızsa ImageView boş kalır (sayfa yine çalışır). URL, JAR içi jar:file:… olabilir.
        def img = new javafx.scene.image.Image(url, 740d, 0d, true, true, true)
        def iv = new javafx.scene.image.ImageView(img)
        iv.setPreserveRatio(true); iv.setFitWidth(740d); iv.setSmooth(true)
        return iv
    } catch (Throwable t) { return null }
}

// ── Render: her gezinmede sahneyi sıfırdan kurar ────────────────────────────
render = { ->
    if (!javafx.application.Platform.isFxApplicationThread()) { javafx.application.Platform.runLater { render() }; return }
    if (stage == null) return
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

    // Görsel (#4): /images/tour/<sayfa-id> — varsa başlık ile gövde arasına ekle (yoksa yok say).
    def iv = imageNode('/images/tour/' + page.id)
    if (iv != null) center.getChildren().add(2, iv)

    // Vurgu düğmesi — önce belirli öğe (sekme/araç çubuğu düğmesi); yoksa coarse bölge;
    // ikisi de erişilemezse düğme pasifleşir (sayfa metni yine geçerli).
    def tgt = resolveTargets(page)
    def hiNodes = (List) tgt.nodes
    boolean hiSpot = (boolean) tgt.spot
    if (hiNodes.isEmpty() && page.bolge != null) {
        def rn = regionNode((String) page.bolge)
        if (rn != null) { hiNodes = [rn]; hiSpot = false }
    }
    def hiBtn = null                 // ToggleButton | null — alt çubuktaki "Otomatik vurgula" buna erişir
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
        // #1 uyarı: VIEWER hedefli sayfada üstte-tut pencere görüntüleyicinin önünü kapatabilir.
        if (page.bolge == 'VIEWER' && page.hedef == null) {
            def vhint = new javafx.scene.control.Label(
                'İpucu: vurgu görüntüleyiciye uygulanır; bu pencere önünü kapatıyorsa kenara çekin.')
            vhint.setWrapText(true)
            vhint.setStyle('-fx-opacity: 0.7; -fx-font-size: 11px; -fx-font-style: italic;')
            center.getChildren().add(vhint)
        }
        // #1: otomatik vurgu açıksa sayfa açılır açılmaz hedefi işaretle (+ güvenliyse sekmeyi aç).
        if (autoHighlight.get()) {
            applyHighlight(nodesF, spotF); activateTarget(page); hiBtn.setSelected(true)
        }
    } else if (page.bolge != null) {
        def hi = new javafx.scene.control.ToggleButton('Bu bölgeyi vurgula')
        hi.setDisable(true)
        hi.setTooltip(new javafx.scene.control.Tooltip(
            'Bu öğe şu an erişilemiyor (slayt açık değil ya da QuPath sürümü farklı). Sayfa metni yine de geçerli.'))
        center.getChildren().add(hi)
    }

    // Alt çubuk: "Üstte tut" + "Otomatik vurgula" (sol) + disclaimer + gezinme düğmeleri (sağ)
    def topChk = new javafx.scene.control.CheckBox('Üstte tut')
    topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n ->
        alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n)
    } as javafx.beans.value.ChangeListener)

    // #1: "Otomatik vurgula" — açık (varsayılan) iken her sayfa hedefini kendiliğinden işaretler.
    def autoChk = new javafx.scene.control.CheckBox('Otomatik vurgula')
    autoChk.setSelected(autoHighlight.get())
    autoChk.setDisable(page.bolge == null && page.hedef == null)   // yalnız hiç hedefi olmayan sayfada (intro/kapanış) kapalı
    autoChk.selectedProperty().addListener({ obs, o, n ->
        autoHighlight.set(n)
        if (hiBtn != null) {
            if (n) { applyHighlight(nodesF, spotF); activateTarget(page); hiBtn.setSelected(true) }
            else { clearHighlight(); hiBtn.setSelected(false) }
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
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('QuPath arayüz turu')
        stage.setAlwaysOnTop(alwaysTop.get())
        stage.setOnHidden({ clearHighlight() })
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println '✓ QuPath arayüz turu açıldı.'
