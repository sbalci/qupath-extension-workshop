/**
 * Yardımcı - QuPath Arayüz Turu (interaktif gezinti)
 * -------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Tek pencerede, sayfa sayfa QuPath arayüzünü Türkçe anlatır
 *   (görüntüleyici, kenar paneli sekmeleri, araç çubuğu, parlaklık/kontrast,
 *   Command List...). Her sayfada ilgili büyük arayüz bölgesi (araç çubuğu /
 *   kenar paneli / görüntüleyici) canlı pencerede turuncu bir parıltıyla
 *   vurgulanabilir. Bir bölgeye erişilemezse (slayt açık değil ya da QuPath
 *   sürümü farklı) sayfa yine de metniyle çalışır; vurgu düğmesi pasifleşir.
 *   Hiçbir nesneyi, ölçümü veya hiyerarşiyi DEĞİŞTİRMEZ.
 *
 *   Pete Bankhead'in qupath-extension-training (Apache-2.0) eklentisinden
 *   esinlenen, atölyeye özgü hafif bir Türkçe uyarlamadır. Resmî eklenti
 *   İngilizce, buton-düzeyinde canlı vurgu + otomatik ekran görüntüleri sunar.
 *
 * KULLANIM:
 *   1. (Önerilir) Bir slayt açın — görüntüleyici/meta veri bölgeleri dolu olsun.
 *   2. [Extensions → Atölye → Yardımcılar → Arayüz turu]
 *   3. İleri / Geri ile gezinin; "Bu bölgeyi vurgula" ile canlı arayüzü işaretleyin.
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
     govde: 'Bu tur QuPath arayüzünü adım adım tanıtır. Her sayfada bir bölge anlatılır; ' +
            'istediğinizde "Bu bölgeyi vurgula" düğmesiyle o bölge canlı pencerede turuncu bir ' +
            'parıltıyla işaretlenir. En verimli kullanım için önce bir slayt açın — böylece ' +
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
     baslik: 'Genel bakış navigatörü (sağ-alt küçük pencere)',
     govde: 'Görüntüleyicinin sağ-alt köşesindeki küçük genel bakış penceresi, tüm slayttaki ' +
            'konumunuzu gösterir — yüksek büyütmede "kayboldum" hissini önler. Üzerindeki dikdörtgen ' +
            'şu an görünen alandır; küçük pencereye tıklayarak hızla başka bir bölgeye atlayabilirsiniz. ' +
            'Görünmüyorsa View menüsünden açıp kapatabilirsiniz.'],

    [id: 'sidebar', bolge: 'SIDEBAR',
     baslik: 'Kenar paneli — analiz sekmeleri',
     govde: 'Sol kenardaki panel analizin kumanda merkezidir; sekmeler hâlinde düzenlenmiştir: ' +
            'Project, Image, Annotations, Hierarchy ve Workflow. Bir sekmeye tıklayarak o görünüme ' +
            'geçersiniz. Sonraki sayfalarda her sekmeyi tek tek tanıyacağız. Panel dar geldiğinde ' +
            'kenarından tutup genişletebilirsiniz.'],

    [id: 'tab-project', bolge: 'SIDEBAR',
     baslik: 'Project sekmesi — slayt listesi',
     govde: 'Project sekmesi, projedeki tüm slaytları küçük resimleriyle listeler. Bir küçük resme ' +
            'çift tıklamak slaytı görüntüleyicide açar; sağ tıklamak açıklama ekleme, görüntü tipi ' +
            'atama gibi işlemleri sunar. Projeler birden çok slaydı bir arada tutmanın ve aynı analizi ' +
            'hepsine uygulamanın profesyonel yoludur. Henüz proje açmadıysanız bu sekme boş görünür.'],

    [id: 'tab-image', bolge: 'SIDEBAR',
     baslik: 'Image sekmesi — piksel boyutu ve meta veri',
     govde: 'Image sekmesi açık slaytın meta verisini gösterir: piksel boyutu (µm/px), görüntü ' +
            'boyutları, tarayıcı modeli ve boya tipi. Buradaki en kritik sayı piksel boyutudur — ' +
            'QuPath fiziksel ölçeği buradan bilir ve her tespit/ölçüm buna bağlıdır. 1.0 µm/px ' +
            'görüyorsanız meta veri eksik demektir; Modül 1\'deki kalibrasyon adımına dönün. Görüntü ' +
            'tipi (Brightfield H&E / H-DAB / Fluorescence) de bu sekmede görünür ve boya ayrımını belirler.'],

    [id: 'tab-annotations', bolge: 'SIDEBAR',
     baslik: 'Annotations sekmesi — çizilen ROI\'ler ve sınıflar',
     govde: 'Annotations sekmesi, elle veya algoritmayla oluşturulan tüm ROI\'leri (ilgi bölgelerini) ' +
            've onların sınıflarını listeler. Bir anotasyona tıklamak onu görüntüleyicide seçer; sağ ' +
            'tıklamayla sınıf atayabilir, ad verebilir veya silebilirsiniz. Tümör, stroma gibi sınıflar ' +
            'burada renklerle yönetilir.'],

    [id: 'tab-hierarchy', bolge: 'SIDEBAR',
     baslik: 'Hierarchy sekmesi — nesne ağacı (anotasyon → tespit)',
     govde: 'Hierarchy sekmesi nesnelerin ağaç yapısını gösterir: slayt → anotasyon → tespit. Bir ' +
            'anotasyonun içinde bulunan hücre tespitleri bu ağaçta onun altında yer alır. Anotasyon ile ' +
            'tespit arasındaki fark burada görünür hâle gelir: anotasyon elle çizilen bölgedir, tespit ise ' +
            'algoritmanın bulduğu nesnedir ve daima bir anotasyonun içindedir.'],

    [id: 'tab-workflow', bolge: 'SIDEBAR',
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

    [id: 'tools-draw', bolge: 'TOOLBAR',
     baslik: 'Çizim araçları — Move, Rectangle, Polygon, Brush, Wand',
     govde: 'Çizim araçları araç çubuğunda yan yana durur: Move (gezinme, kısayol M), Rectangle (R), ' +
            'Ellipse (E), Polygon (P), Brush (fırça, B) ve Wand (kenar takipli sihirli değnek, W). Bir bölge ' +
            'çizmek için ilgili aracı seçip görüntüleyicide sürüklersiniz. İş bitince Move aracına dönmek iyi ' +
            'alışkanlıktır — yoksa yanlışlıkla yeni anotasyon çizebilirsiniz.'],

    [id: 'tool-points', bolge: 'TOOLBAR',
     baslik: 'Points — sayım / işaretleme aracı',
     govde: 'Points (nokta) aracı, tek tek hücreleri elle işaretleyip saymak için kullanılır — örneğin bir ' +
            'referans sayımı yaparken. Her tıklama bir nokta bırakır; farklı sınıflar için ayrı nokta ' +
            'kümeleri oluşturabilirsiniz. Bu, otomatik tespitin doğruluğunu gözle denetlemenin pratik bir yoludur.'],

    [id: 'selection-mode', bolge: 'TOOLBAR',
     baslik: 'Seçim modu — çizmek yerine nesne seçmek',
     govde: 'Seçim modu (Selection mode) düğmesi, araçların davranışını "çizme"den "seçme"ye çevirir. Açıkken, ' +
            'çizim aracıyla sürüklediğiniz alan yeni bir anotasyon oluşturmaz; o alana düşen mevcut nesneleri ' +
            'seçer. Çok sayıda hücre veya anotasyonu toplu işlemek için kullanışlıdır.'],

    [id: 'brightness', bolge: 'TOOLBAR',
     baslik: 'Parlaklık & Kontrast — yalnızca ekranı değiştirir',
     govde: 'Parlaklık & Kontrast diyaloğu (araç çubuğundaki güneş simgesi ya da Shift+C) yalnızca ekranda ' +
            'gördüğünüzü değiştirir; analizde kullanılan piksel değerlerine dokunmaz. Kontrastı rahatça ' +
            'oynatabilirsiniz — ölçümleriniz etkilenmez. H&E\'de R/G/B kanallarını, H-DAB\'de hematoksilen/DAB ' +
            'kanallarını açıp kapatarak sinyali ayırt edebilirsiniz.'],

    [id: 'visibility', bolge: 'TOOLBAR',
     baslik: 'Görünürlük: anotasyon/tespit göster-gizle, doldur',
     govde: 'Araç çubuğundaki görünürlük anahtarları kalabalık bir slaytta neyi gördüğünüzü denetler: ' +
            'anotasyonları göster/gizle, tespitleri göster/gizle ve bunların içini doldur/boşalt. Binlerce hücre ' +
            'dış çizgisini "doldurulmuş" yapmak uzaktan dağılımı çok daha okunaklı kılar. Bağlantıları ' +
            '(connections) ve sınıflandırma kaplamasını da buradan açıp kapatırsınız.'],

    [id: 'opacity', bolge: 'TOOLBAR',
     baslik: 'Opaklık kaydırıcısı — kaplama saydamlığı',
     govde: 'Opaklık kaydırıcısı, nesne ve sınıflandırma kaplamalarının saydamlığını ayarlar. Sola çekince ' +
            'kaplama silikleşir ve altındaki H&E daha çok görünür; sağa çekince kaplama belirginleşir. Bir piksel ' +
            'sınıflandırıcı maskesinin altındaki dokuyu kontrol ederken çok işe yarar.'],

    [id: 'measurements', bolge: 'TOOLBAR',
     baslik: 'Ölçüm tabloları',
     govde: 'Ölçüm tabloları düğmesi, seçili nesnelerin (anotasyon veya tespit) tüm ölçümlerini bir tabloda ' +
            'açar: alan, sayım, yoğunluk, boya optik yoğunluğu ve daha fazlası. Tablodaki bir satıra tıklamak ' +
            'ilgili nesneyi görüntüleyicide seçer. Bu tablolar dışa aktarmanın (Modül 9) temelidir.'],

    [id: 'script-editor', bolge: 'TOOLBAR',
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
def highlightRef = new java.util.concurrent.atomic.AtomicReference(null)   // [node, origEffect] | null
def render  // ileri bildirim

// ── Canlı arayüz düğümünü en iyi çaba ile çöz (erişilemezse null → metin-only) ─
def regionNode = { String region ->
    try {
        if (region == 'TOOLBAR') return gui.getToolBar()
        if (region == 'SIDEBAR') return gui.getAnalysisTabPane()
        if (region == 'VIEWER')  { def v = gui.getViewer(); return (v == null) ? null : v.getView() }
    } catch (Throwable t) { /* yöntem yok / sürüm farkı → metin-only degrade */ }
    return null
}

// ── Vurgu uygula / temizle (tümü FX iş parçacığında; orijinal efekti geri yükle) ─
def clearHighlight = { ->
    def cur = highlightRef.getAndSet(null)
    if (cur != null) {
        javafx.application.Platform.runLater {
            try { ((javafx.scene.Node) cur[0]).setEffect((javafx.scene.effect.Effect) cur[1]) } catch (Throwable t) {}
        }
    }
}
def applyHighlight = { javafx.scene.Node node ->
    clearHighlight()
    javafx.application.Platform.runLater {
        try {
            def orig = node.getEffect()
            highlightRef.set([node, orig])
            def glow = new javafx.scene.effect.DropShadow()
            glow.setColor(javafx.scene.paint.Color.web('#FFA500'))   // turuncu — ilham eklentisinin rengi
            glow.setRadius(30.0)
            glow.setSpread(0.45)
            node.setEffect(glow)
        } catch (Throwable t) { highlightRef.set(null) }
    }
}

// ── Render: her gezinmede sahneyi sıfırdan kurar ────────────────────────────
render = { ->
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

    // Bölge vurgusu — yalnız bolge tanımlıysa; düğüm erişilemezse pasif
    if (page.bolge != null) {
        def node = regionNode((String) page.bolge)
        def hi = new javafx.scene.control.ToggleButton('Bu bölgeyi vurgula')
        if (node == null) {
            hi.setDisable(true)
            hi.setTooltip(new javafx.scene.control.Tooltip(
                'Bu bölge şu an erişilemiyor (slayt açık değil ya da QuPath sürümü farklı). Sayfa metni yine de geçerli.'))
        } else {
            hi.setOnAction({
                if (hi.isSelected()) applyHighlight((javafx.scene.Node) node)
                else clearHighlight()
            })
        }
        center.getChildren().add(hi)
    }

    // Alt çubuk: "Üstte tut" (sol) + disclaimer + gezinme düğmeleri (sağ)
    def topChk = new javafx.scene.control.CheckBox('Üstte tut')
    topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n ->
        alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n)
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
    bar.getChildren().add(topChk)
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
