/**
 * Yardımcı - Karo (tile) dışa aktarma
 * ------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Açık lamdan görüntü karoları (tiles) — isteğe bağlı olarak eşleşen etiket
 * maskesi karolarıyla birlikte — dışa aktarır. Derin öğrenme / harici yapay
 * zekâ araçlarına veri hazırlamak, bir bölgeyi paylaşmak veya segmentasyon
 * eğitim seti üretmek içindir.
 *
 * İKİ MOD (diyalogdan seçilir):
 *   • Sadece görüntü            — düz RGB karolar.
 *   • Görüntü + etiket maskesi   — her karo + anotasyon sınıflarından üretilen
 *                                  tek kanallı indeksli maske (segmentasyon).
 *
 *   KAVRAM (Bankhead): etiket maskesi bir "etiketli görüntü"dür (labelled image:
 *   0 = arka plan, her tamsayı = sınıf). Bu yüzden maske modunda JPEG değil PNG
 *   kullanılır — kayıplı sıkıştırma tamsayı etiketlerini bozar.
 *
 * KAPSAM (yalnızca AÇIK görüntü; diyalogdan seçilir):
 *   • Seçili anotasyon(lar)  — yalnızca seçtiğiniz ROI'lerin içindeki karolar.
 *   • Tüm anotasyonlar       — lamdaki her anotasyonun içindeki karolar.
 *   (Her iki kapsam da anotasyonla sınırlıdır — boş lam döşenmez.)
 *
 * ETİKETE GÖRE DÜZEN (bu sürümde yeni):
 *   Her karo, içinden kesildiği anotasyonun ETİKETİNİ taşır: etiket hem klasör
 *   adına hem dosya adına yazılır. Etiket, "Klasörleme" seçeneğine göre
 *   anotasyonun sınıfı (PathClass), adı (Name) ya da ikisidir; hiçbiri yoksa
 *   "Sinifsiz". Aynı etiketli anotasyonlar tek grup olarak döşenir.
 *   • Karo ızgarası görüntünün (0,0) köşesine hizalıdır; aynı ayarla yeniden
 *     çalıştırmak aynı dosya adlarını üretir (üzerine yazar, hiçbir şey silmez).
 *   • "Ignore*" sınıflı anotasyonlar karo üretmez ve (seçenek açıksa) diğer
 *     anotasyonlardan çıkarılır: katlantı / kalem izi içine düşen karo yazılmaz.
 *   • Alt anotasyonu olan üst anotasyon (ör. Doku içinde Tümör) için alt
 *     anotasyonlar üstten çıkarılabilir; aynı karo iki etikete birden girmez.
 *   • "En az kapsama (%)": karonun alanının en az bu kadarı etiket bölgesinde
 *     olmalıdır. 0 = herhangi bir örtüşme yeter (önceki davranış).
 *
 * SEÇENEKLER (beş sekme; her satırın yanında açıklaması vardır):
 *   Rehber           — amaca göre başlangıç ayarı (sınıflandırma/MIL, temel model, segmentasyon,
 *                      hücre düzeyi, paylaşım, elle), gerekçesi ve canlı özet (görüş alanı, adım,
 *                      bu lamdaki downsample, uyarılar). Öneriler sezgisel başlangıçtır, kılavuz değil.
 *   Temel            — Mod · Kapsam · Karo boyutu · µm/px · Örtüşme · Format · JPG kalitesi.
 *   Etiket ve bölge  — Klasörleme · canlı etiket listesi (kapsam ve klasörlemeye göre) · dışlama sınıfları · iki çıkarma kutusu ·
 *                      en az kapsama · etiket başına en çok karo. (Sınır karosu her zaman tek etikete yazılır.)
 *   Çıktı düzeni     — çıktı klasörü · klasör düzeni · dosya adında etiket / bölme · yalnız karo listesi ·
 *                      önizleme görseli · karo listesi.
 *   Başka açık kaynak araçlardan uyarlanan üç özellik (belgelerinden doğrulandı, kod alınmadı):
 *     • En az doku (%) + beyazlık eşiği — histolab tissue_percent, Slideflow whitespace süzgeci.
 *     • Yalnız karo listesi — CLAM ve TRIDENT karoları değil koordinatları saklar.
 *     • Önizleme görseli — histolab locate_tiles, CLAM --stitch, Slideflow çıkarım raporu.
 *   Bölme ve sızıntı — bölme ve hasta anahtarları. Bölme okuma ve sızıntı denetimleri kapatılamaz.
 *   Maske modunda format PNG'ye zorlanır (JPEG tamsayı etiketleri bozar).
 *   Örtüşme ne zaman: karolar yeniden birleştirilecekse (segmentasyon, tahmin haritası) ya da
 *   kenarda kesilen yapı önemliyse (hücre düzeyi). Sınıflandırma, MIL ve paylaşımda 0.
 *
 * KULLANIM:
 *   1. Bir lam açın; en az bir anotasyon çizin (maske modu için sınıflı
 *      anotasyon gerekir — bkz. Tümör/Stroma modülü).
 *   2. "Seçili" kapsam için anotasyon(lar)ı seçili tutun.
 *   3. [Extensions → Atölye → Yardımcılar → Karo (tile) dışa aktarma]
 *   4. Formdan seçenekleri ayarlayıp "Dışa aktar".
 *
 * ÇIKTI:
 *   <proje-klasörü>/exports/tiles/karo_<boyut>px_<µm/px>um_ov<örtüşme>/
 *     ├── <Etiket>/<lam>/<lam>__<Etiket>__ds<d>_x<X>_y<Y>.png      (sadece görüntü)
 *     ├── <Etiket>/<lam>/images/…  ve  <Etiket>/<lam>/labels/…     (görüntü + maske)
 *     ├── karo_listesi__<lam>.tsv   her karo için bir satır (dosya, etiket, x, y, kapsama …)
 *     └── labels.txt                maske modunda sınıf → indeks
 *   Klasör adı ayarları taşır; farklı lamlar aynı ayarla aynı klasöre, etiket
 *   klasörlerinin altına yazılır (derin öğrenme araçlarının beklediği
 *   <sınıf>/<dosya> düzeni). x ve y, taban (level 0) piksel koordinatıdır.
 *   (Proje yoksa çıktı kullanıcı ana klasörünün altına yazılır.)
 *
 * KAYNAK DÜZEN: <Sınıf>/<Lam>/<Lam>_ds<d>_x<X>_y<Y>.png klasörlemesi ve Ignore
 *   çıkarma mantığı, yazarın PinkKidney ve karaciğer tru-cut projelerindeki
 *   karo betiklerinden uyarlanmıştır.
 *
 * NE YAPMAZ: Tüm projeyi (çoklu lam) ya da anotasyonsuz tüm lamı döşemez.
 *
 * API / BELGE: ImageServer.readRegion + LabeledImageServer.Builder (QuPath 0.6.0+).
 *   Karolar tek tek okunur ve yazılır (TileExporter kullanılmaz): etiket başına
 *   klasör, dosya adı ve kapsama eşiği bu sayede denetlenir.
 *   Maske modu tek kanallı indeksli çıktı verir (multichannelOutput=false).
 *   Resmi belgeler:
 *     https://qupath.readthedocs.io/en/stable/docs/advanced/exporting_images.html
 *     https://qupath.readthedocs.io/en/stable/docs/advanced/exporting_annotations.html
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.images.servers.LabeledImageServer
import qupath.lib.regions.RegionRequest
import qupath.lib.common.ColorTools

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// --- Atölye ayarları: eklenti yüklüyse oku, yoksa atölye varsayılanı kullanılır ---
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeS = { String k, String d -> (String) __wpCall('str', [String.class, String.class] as Class[], [k, d] as Object[], d) }
def exportFolder = atolyeS('atolye.exportFolder', 'exports')

// ──────────────────────────────────────────────────────────────
// Türkçe karakter + boşluk içeren lam isimlerini güvenli slug'a çevirir
// ──────────────────────────────────────────────────────────────
def toSlug = { String s ->
    if (s == null) return "untitled"
    return s.replace('ı', 'i').replace('İ', 'I')
            .replace('ğ', 'g').replace('Ğ', 'G')
            .replace('ü', 'u').replace('Ü', 'U')
            .replace('ş', 's').replace('Ş', 'S')
            .replace('ö', 'o').replace('Ö', 'O')
            .replace('ç', 'c').replace('Ç', 'C')
            .replaceAll('[^a-zA-Z0-9_\\-]', '_')
            .replaceAll('_+', '_')
}

// ──────────────────────────────────────────────────────────────
// Seçenek formu — tek pencere, açıklamalı satırlar, latch ile Map döner
// ──────────────────────────────────────────────────────────────
// Etiket: "Klasörleme" seçeneğine göre sınıf, ad ya da ikisi; boşsa "Sinifsiz". Form ve dışa aktarma aynı işlevi kullanır.
def labelFor = { String cls, String nm, String mode ->
    if (nm != null && nm.trim().isEmpty()) nm = null
    String raw = null
    if (mode == 'name') raw = (nm != null ? nm : cls)
    else if (mode == 'both') raw = (cls != null && nm != null) ? (cls + '__' + nm) : (cls != null ? cls : nm)
    else raw = (cls != null ? cls : nm)
    if (raw == null) return 'Sinifsiz'
    String s = toSlug(raw).replaceAll('^_+|_+$', '')
    if (s.length() > 60) s = s.substring(0, 60)
    return s.isEmpty() ? 'Sinifsiz' : s
}
// Dışlama sınıfı mı? "Ad*" önek, "Ad" tam eşleşme (büyük/küçük harf ayırmaz).
def excludeMatch = { String cls, List patterns ->
    if (cls == null) return false
    String c = cls.toLowerCase(java.util.Locale.ROOT)
    return patterns.any { p -> String q = p.toString().trim().toLowerCase(java.util.Locale.ROOT); !q.isEmpty() && (q.endsWith('*') ? c.startsWith(q.substring(0, q.length() - 1)) : (c == q)) }
}

def showOptionsForm = { int selCount, int allCount, List annInfo, File defaultOutRoot, double slidePx ->
    def defaults = [mode: 'image', scope: 'all', group: 'class', tileSize: 512, umPerPx: 1.0d, overlap: 0, ext: '.png',
                    minCover: 0.0d, subtractIgnore: true, subtractChildren: true,
                    labels: null, exclude: ['Ignore*'], maxPerLabel: 0,
                    minTissue: 0.0d, whiteThreshold: 220, coordsOnly: false, preview: true,
                    layout: 'label-slide', labelInName: true, splitInName: false, jpegQuality: 90, writeManifest: true,
                    outRoot: defaultOutRoot.getAbsolutePath(),
                    splitKeys: ['split', 'bolme', 'bölme', 'set', 'fold', 'kume', 'küme'],
                    patientKeys: ['patient', 'patient_id', 'patientid', 'hasta', 'hasta_id', 'patient_pseudonym', 'hasta_takma_adi',
                                  'case', 'case_id', 'caseid', 'vaka', 'vaka_id', 'olgu', 'olgu_id']]
    def csv = { String s -> (s ?: '').split(',').collect { it.trim() }.findAll { !it.isEmpty() } }
    if (isHeadless) {
        // Headless: varsayılanlar + isteğe bağlı -a anahtar=değer. Tanınmayan anahtar ya da değer → hiçbir şey yazılmadan durur.
        //   mod=maske|goruntu · klasorleme=sinif|ad|ikisi · kapsama=0..100 · enfazla=N
        //   duzen=etiket-lam|lam-etiket|etiket · adetiket=evet|hayir · adbolme=evet|hayir · liste=evet|hayir
        //   doku=0..100 · beyaz=0..255 · yalnizliste=evet|hayir · onizleme=evet|hayir
        //   disla=A,B* · etiketler=A,B · cikti=<klasör>   (bölme okuma kapatılamaz)
        def cli = (binding.hasVariable('args') && binding.getVariable('args') != null) ? (binding.getVariable('args') as List) : []
        for (a in cli) {
            String s = a.toString()
            int eq = s.indexOf('=')
            String k = (eq > 0 ? s.substring(0, eq) : s).trim().toLowerCase(java.util.Locale.ROOT)
            String raw = (eq > 0 ? s.substring(eq + 1) : '').trim()
            String v = raw.toLowerCase(java.util.Locale.ROOT)
            boolean yn = ['evet', 'hayir'].contains(v)
            if (k == 'mod' && ['maske', 'goruntu'].contains(v)) defaults.mode = (v == 'maske' ? 'mask' : 'image')
            else if (k == 'klasorleme' && ['sinif', 'ad', 'ikisi'].contains(v)) defaults.group = (v == 'ad' ? 'name' : (v == 'ikisi' ? 'both' : 'class'))
            else if (k == 'kapsama' && v ==~ /\d+(\.\d+)?/ && Double.parseDouble(v) <= 100.0d) defaults.minCover = Double.parseDouble(v) / 100.0d
            else if (k == 'enfazla' && v ==~ /\d+/) defaults.maxPerLabel = Integer.parseInt(v)
            else if (k == 'duzen' && ['etiket-lam', 'lam-etiket', 'etiket'].contains(v)) defaults.layout = (v == 'lam-etiket' ? 'slide-label' : (v == 'etiket' ? 'label' : 'label-slide'))
            else if (k == 'adetiket' && yn) defaults.labelInName = (v == 'evet')
            else if (k == 'adbolme' && yn) defaults.splitInName = (v == 'evet')
            else if (k == 'liste' && yn) defaults.writeManifest = (v == 'evet')
            else if (k == 'doku' && v ==~ /\d+(\.\d+)?/ && Double.parseDouble(v) <= 100.0d) defaults.minTissue = Double.parseDouble(v) / 100.0d
            else if (k == 'beyaz' && v ==~ /\d+/ && Integer.parseInt(v) <= 255) defaults.whiteThreshold = Integer.parseInt(v)
            else if (k == 'yalnizliste' && yn) defaults.coordsOnly = (v == 'evet')
            else if (k == 'onizleme' && yn) defaults.preview = (v == 'evet')
            else if (k == 'disla') defaults.exclude = csv(raw)
            else if ((k == 'etiketler' || k == 'siniflar') && !raw.isEmpty()) defaults.labels = csv(raw) as Set
            else if (k == 'cikti' && !raw.isEmpty()) defaults.outRoot = raw
            else if (k == 'senaryo') continue          // tools/test-karo-etiket.groovy kendi bağımsız değişkeni
            else { println "Tanınmayan bağımsız değişken: " + s + ". Karo yazılmadı."; return null }
        }
        println "=== Karo dışa aktarma (headless) === " + defaults.findAll { k, v -> !(k in ['splitKeys', 'patientKeys']) }
        return defaults
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def result = new java.util.concurrent.atomic.AtomicReference<Map>(null)

    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle("Karo (tile) dışa aktarma — seçenekler")
            stage.setAlwaysOnTop(true)

            def help = { String s ->
                def l = new javafx.scene.control.Label(s)
                l.setWrapText(true)
                l.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;")
                l.setMaxWidth(330)
                return l
            }
            def newGrid = { ->
                def g = new javafx.scene.layout.GridPane()
                g.setHgap(12); g.setVgap(7)
                g.setPadding(new javafx.geometry.Insets(12))
                return g
            }
            def addRow = { javafx.scene.layout.GridPane g, int row, String label, javafx.scene.Node control, String helpText ->
                g.add(new javafx.scene.control.Label(label), 0, row)
                g.add(control, 1, row)
                g.add(help(helpText), 2, row)
            }
            def choice = { List items ->
                def b = new javafx.scene.control.ChoiceBox<String>()
                b.getItems().addAll(items)
                b.setValue((String) items[0])
                return b
            }
            def field = { String v, int cols -> def f = new javafx.scene.control.TextField(v); f.setPrefColumnCount(cols); return f }
            def check = { String text, boolean on -> def c = new javafx.scene.control.CheckBox(text); c.setMnemonicParsing(false); c.setSelected(on); return c }

            // ── Sekme 1: Temel ──
            def modeBox  = choice(["Sadece görüntü", "Görüntü + etiket maskesi"])
            def scopeBox = choice(["Tüm anotasyonlar (${allCount})".toString(), "Seçili anotasyon(lar) (${selCount})".toString()])
            def tileField    = field("512", 8)
            def umField      = field("1.0", 8)
            def overlapField = field("0", 8)
            def formatBox = choice(["PNG (kayıpsız)", "JPG (küçük, kayıplı)", "TIFF (kayıpsız)"])
            def qualityField = field("90", 8)
            def g1 = newGrid()
            int r = 0
            addRow(g1, r++, "Mod:", modeBox, "Sadece görüntü: RGB karolar. Görüntü + etiket maskesi: anotasyon sınıflarından maske (segmentasyon eğitimi).")
            addRow(g1, r++, "Kapsam:", scopeBox, "Seçili anotasyon(lar): yalnızca seçtiğiniz ROI'ler. Tüm anotasyonlar: lamdaki her anotasyon.")
            addRow(g1, r++, "Karo boyutu (px):", tileField, "Her karonun kenar uzunluğu (piksel). Tipik: 224, 256, 512.")
            addRow(g1, r++, "Çözünürlük (µm/px):", umField, "Hedef çözünürlük; 20× ve 40× lamlarda aynı fiziksel alanı verir. Kalibre değilse downsample katsayısı olarak yorumlanır.")
            addRow(g1, r++, "Örtüşme (px):", overlapField, "Komşu karolar arası ortak piksel. 0 = örtüşme yok. >0 ise karo düzeyinde rastgele bölme sızıntıya yol açar.")
            addRow(g1, r++, "Format:", formatBox, "PNG: kayıpsız. JPG: küçük ama kayıplı. TIFF: kayıpsız, büyük. Maske modunda PNG'ye zorlanır.")
            addRow(g1, r++, "JPG kalitesi (1–100):", qualityField, "Yalnız JPG seçiliyse kullanılır.")

            // ── Sekme 2: Etiket ve bölge ──
            def groupBox = choice(["Sınıfa göre (yoksa ada göre)", "Anotasyon adına göre (yoksa sınıfa göre)", "Sınıf + ad"])
            def excludeField = field("Ignore*", 16)
            // Etiket listesi CANLIDIR: kapsam, klasörleme ve dışlama alanı değiştikçe yeniden kurulur.
            def labelPane = new javafx.scene.layout.FlowPane(10, 4)
            labelPane.setPrefWrapLength(260)
            def unchecked = new HashSet()               // kullanıcının kapattığı etiketler (yeniden kurulurken korunur)
            def rebuildLabels = { ->
                boolean onlySel = scopeBox.getValue().startsWith("Seçili")
                String gv0 = groupBox.getValue()
                String mode0 = gv0.startsWith("Anotasyon adına") ? 'name' : (gv0.startsWith("Sınıf +") ? 'both' : 'class')
                def pats = csv(excludeField.getText())
                def counts = new java.util.TreeMap<String, Integer>()
                annInfo.each { a ->
                    if (onlySel && !a.sel) return
                    if (excludeMatch((String) a.cls, pats)) return
                    String lab = labelFor((String) a.cls, (String) a.name, mode0)
                    counts.put(lab, (counts.get(lab) ?: 0) + 1)
                }
                labelPane.getChildren().clear()
                counts.each { String lab, Integer n ->
                    def c = check(lab + " (" + n + ")", !unchecked.contains(lab))
                    c.setUserData(lab)
                    c.selectedProperty().addListener({ obs, o, nv -> if (nv) unchecked.remove(lab) else unchecked.add(lab) } as javafx.beans.value.ChangeListener)
                    labelPane.getChildren().add(c)
                }
                if (counts.isEmpty()) {
                    def none = new javafx.scene.control.Label(onlySel ? "Seçili anotasyon yok ya da hepsi dışlama sınıfında." : "Karo üretecek anotasyon yok.")
                    none.setStyle("-fx-text-fill: #a32d2d;")
                    labelPane.getChildren().add(none)
                }
            }
            scopeBox.valueProperty().addListener({ obs, o, n -> rebuildLabels() } as javafx.beans.value.ChangeListener)
            groupBox.valueProperty().addListener({ obs, o, n -> unchecked.clear(); rebuildLabels() } as javafx.beans.value.ChangeListener)
            excludeField.textProperty().addListener({ obs, o, n -> rebuildLabels() } as javafx.beans.value.ChangeListener)
            rebuildLabels()
            def ignoreBox = check("Dışlama bölgelerini diğerlerinden çıkar", true)
            def childBox = check("Alt anotasyonları üst anotasyondan çıkar", true)
            def coverField = field("0", 8)
            def maxField = field("0", 8)
            def tissueField = field("0", 8)
            def whiteField = field("220", 8)
            def g2 = newGrid()
            r = 0
            addRow(g2, r++, "Klasörleme:", groupBox, "Karoların etiketi: anotasyonun sınıfı, adı ya da ikisi. Sınıfsız ve adsız anotasyon: Sinifsiz.")
            addRow(g2, r++, "Etiketler:", labelPane, "Bu lamda, seçili kapsam ve klasörlemeyle oluşacak etiketler ve anotasyon sayıları. İşareti kaldırılan etiket atlanır. Liste, kapsam / klasörleme / dışlama değişince yenilenir.")
            addRow(g2, r++, "Dışlama sınıfları:", excludeField, "Virgülle ayırın; sonda * önek demektir (ör. Ignore*, Artifact, Fold, Blur). Bu sınıflar karo üretmez.")
            addRow(g2, r++, "", ignoreBox, "Katlantı ya da kalem izi gibi dışlama anotasyonları diğer bölgelerden kesilip atılır.")
            addRow(g2, r++, "", childBox, "Doku içinde Tümör gibi iç içe anotasyonlarda üst bölge, alt bölge çıkarılarak döşenir.")
            addRow(g2, r++, "En az kapsama (%):", coverField, "Karonun alanının en az bu kadarı etiket bölgesinde olmalı. 0 = herhangi bir örtüşme yeter; kenar karolarını elemek için ör. 50.")
            g2.add(help("Sınır karoları: bir karo iki etiket bölgesine değiyorsa yalnız kapsaması en büyük etikete yazılır. Aynı pikseller hiçbir zaman iki klasöre girmez; bu ayar kapatılamaz."), 0, r++, 3, 1)
            addRow(g2, r++, "En az doku (%):", tissueField, "Karonun piksellerinin en az bu kadarı doku olmalı (beyazlık eşiğinden koyu). 0 = kapalı. Anotasyon geniş çizildiyse boş cam karolarını eler.")
            addRow(g2, r++, "Beyazlık eşiği (0–255):", whiteField, "Gri değeri bunun altındaki piksel doku sayılır. 220 yaygın bir başlangıçtır; soluk boyamada yükseltin.")
            addRow(g2, r++, "Etiket başına en çok:", maxField, "0 = sınırsız. Sayı verirseniz her etiketten rastgele (yinelenebilir) bu kadar karo seçilir. Sınır BU LAM içindir ve doku süzgecinden sonra uygulanır; küme genelindeki dengeyi sonuç penceresindeki küme özeti gösterir.")

            // ── Sekme 3: Çıktı düzeni ──
            def outField = field(defaultOutRoot.getAbsolutePath(), 30)
            def browseBtn = new javafx.scene.control.Button("Seç…")
            browseBtn.setOnAction({
                def cur = new File(outField.getText().trim())
                while (cur != null && !cur.isDirectory()) cur = cur.getParentFile()
                def picked = qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, "Karo çıktı klasörü", cur)
                if (picked != null) outField.setText(picked.getAbsolutePath())
            })
            def outBox = new javafx.scene.layout.HBox(6, outField, browseBtn)
            def layoutBox = choice(["<Etiket>/<lam>/dosya", "<lam>/<Etiket>/dosya", "<Etiket>/dosya (lam klasörü yok)"])
            def labelNameBox = check("Dosya adına etiketi yaz", true)
            def splitNameBox = check("Dosya adına bölmeyi yaz", false)
            def manifestBox = check("Karo listesini yaz (karo_listesi__<lam>.tsv)", true)
            def coordsBox = check("Yalnız karo listesi (görüntü dosyası yazma)", false)
            def previewBox = check("Önizleme görseli yaz (onizleme__<lam>.jpg)", true)
            def g3 = newGrid()
            r = 0
            addRow(g3, r++, "Çıktı klasörü:", outBox, "Bu klasörün altına, ayarları adında taşıyan bir küme klasörü açılır (karo_<boyut>px_<µm>um_ov<örtüşme>).")
            addRow(g3, r++, "Klasör düzeni:", layoutBox, "Derin öğrenme araçlarının çoğu <sınıf>/<dosya> bekler. Lam klasörü, lam düzeyinde bölmeyi kolaylaştırır.")
            addRow(g3, r++, "Dosya adı:", labelNameBox, "<lam>__<Etiket>__ds<d>_x<X>_y<Y>. Kapalıysa: <lam>__ds<d>_x<X>_y<Y>.")
            addRow(g3, r++, "", splitNameBox, "Açıksa bölme de dosya adına girer: <lam>__<bölme>__<Etiket>__…")
            addRow(g3, r++, "", coordsBox, "Karolar diske yazılmaz; yalnız koordinat listesi üretilir. Karoları sonradan kendi kodunuzla lamdan okursunuz (disk yeri ve kopya sorunu olmaz).")
            addRow(g3, r++, "Önizleme:", previewBox, "Lamın küçük görüntüsü üzerinde yazılan karoların kutuları, etikete göre renkli. Dışa aktarımı gözle denetlemek için.")
            addRow(g3, r++, "Karo listesi:", manifestBox, "Her karo için bir satır: bölme, etiket, x, y, kapsama, dosya. Eğitimde klasörü değil bu listeyi kullanın.")

            // ── Sekme 4: Bölme ve sızıntı ──
            def splitKeyField = field(defaults.splitKeys.findAll { it ==~ /[a-z_]+/ }.join(', '), 24)
            def patientKeyField = field("patient, hasta, case, vaka, patient_pseudonym", 24)
            def leakInfo = new javafx.scene.control.Label(
                "Üst veride bölme varsa yok sayılamaz; şu denetimler HER ZAMAN yapılır ve kapatılamaz:\n" +
                "  • aynı lam birden çok bölmeye yazılamaz,\n" +
                "  • lamın karoları aynı kümede başka bölmede varsa durur,\n" +
                "  • aynı hastanın başka lamı projede farklı bölmedeyse durur,\n" +
                "  • bölmeli ve bölmesiz lamlar aynı kümeye yazılamaz,\n" +
                "  • hasta kimliği yoksa durur (onayla devam yolu yoktur).\n" +
                "Betik bölme atamaz; yalnız var olan bölmeyi okur.")
            leakInfo.setWrapText(true)
            leakInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: #7a1f1f; -fx-background-color: #fdeeee; -fx-padding: 8;")
            def g4 = newGrid()
            r = 0
            g4.add(help("Üst veride bölme varsa HER ZAMAN okunur ve klasörlerin başına <Bölme>/ düzeyi gelir (train / val / test). Bölme yoksa bu düzey açılmaz."), 0, r++, 3, 1)
            addRow(g4, r++, "Bölme anahtarları:", splitKeyField, "Önce anotasyonun, yoksa proje girdisinin üst verisinde aranır. Virgülle ayırın.")
            addRow(g4, r++, "Hasta anahtarları:", patientKeyField, "Proje girdisinin üst verisinde hasta kimliğini taşıyan anahtarlar. Virgülle ayırın.")
            g4.add(leakInfo, 0, r, 3, 1)

            // ── Sekme 0: Rehber — amaca göre başlangıç ayarı + canlı özet ──
            // Öneriler sezgisel başlangıç değerleridir, kılavuz değildir; modelinizin beklediği ölçek her zaman önce gelir.
            def PRESETS = [
                [ad: "Elle (ayarları ben seçerim)", uygula: null,
                 neden: "Hiçbir ayara dokunulmaz. Diğer sekmelerde her şeyi kendiniz belirlersiniz."],
                [ad: "Karo sınıflandırma / lam düzeyi model (MIL)", uygula: [mod: 0, karo: "256", um: "1.0", ort: "0", kap: "50", fmt: 0],
                 neden: "Örtüşme 0: aynı doku iki kez sayılmaz ve karolar arası ortak piksel olmaz.\n" +
                        "En az kapsama 50: çoğu arka plan ya da komşu bölge olan kenar karoları elenir.\n" +
                        "Her lam aynı µm/px ile kesilir; 20× ve 40× taramalar aynı fiziksel alanı verir."],
                [ad: "Temel model (foundation model) ile özellik çıkarımı", uygula: [mod: 0, karo: "224", um: "0.5", ort: "0", kap: "50", fmt: 0],
                 neden: "Yaygın başlangıç: 224 px, 0,5 µm/px. ÖNCE model kartına bakın: karo boyutu ve µm/px modelin eğitildiği\n" +
                        "değerle aynı olmalıdır. Yanlış ölçekte model hata vermez, yanlış cevap verir.\n" +
                        "Örtüşme 0: gömmeler lam düzeyinde toplanırken aynı doku iki kez ağırlık almaz."],
                [ad: "Segmentasyon eğitimi (görüntü + maske)", uygula: [mod: 1, karo: "512", um: "1.0", ort: "64", kap: "0", fmt: 0],
                 neden: "Maske modu: her karoya eşleşen etiket maskesi yazılır (PNG zorunlu; JPG etiket değerlerini bozar).\n" +
                        "Örtüşme 64 px: karo kenarında kesilen yapılar komşu karoda bütün görünür.\n" +
                        "Kapsama 0: sınır karoları da gerekir; model sınırı öğrenir."],
                [ad: "Hücre / çekirdek düzeyi eğitim", uygula: [mod: 0, karo: "256", um: "0.25", ort: "32", kap: "0", fmt: 0],
                 neden: "Hücre ayrıntısı için ince ölçek (≈ 0,25 µm/px, 40×). Lamın kendi µm/px değerinden ince ölçek yeni ayrıntı üretmez.\n" +
                        "Küçük örtüşme: kenarda yarım kalan çekirdek komşu karoda bütündür."],
                [ad: "Paylaşmak için karolar", uygula: [mod: 0, karo: "512", um: "1.0", ort: "0", kap: "50", fmt: 0],
                 neden: "Örtüşme 0: paylaşılan karolar arasında ortak bilgi olmamalıdır; karolardan lam yeniden kurulamamalıdır.\n" +
                        "Paylaşmadan önce dosya adlarına ve karo listesine bakın: lam adı kimlik taşımamalıdır."],
            ]
            def presetBox = choice(PRESETS.collect { it.ad })
            def whyLabel = new javafx.scene.control.Label((String) PRESETS[0].neden)
            whyLabel.setWrapText(true)
            whyLabel.setStyle("-fx-font-size: 12px; -fx-background-color: #e9f5f0; -fx-padding: 8;")
            def liveLabel = new javafx.scene.control.TextArea("")
            liveLabel.setEditable(false)
            liveLabel.setWrapText(true)
            liveLabel.setPrefRowCount(7)
            whyLabel.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE)
            liveLabel.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 12px; -fx-padding: 6 8 6 8;")
            def rules = new javafx.scene.control.Label(
                "Genel ilkeler\n" +
                "  1. Ölçeği büyütmeyle değil µm/px ile verin; görüş alanı = karo (px) × µm/px.\n" +
                "  2. Örtüşmeyi yalnız karolar yeniden birleştirilecekse ya da kenarda kesilen yapı önemliyse açın.\n" +
                "     Örtüşen karolar neredeyse kopyadır: aynı bölmede kalmalıdır.\n" +
                "  3. Bölmeyi karo düzeyinde değil hasta düzeyinde yapın (Bölme ve sızıntı sekmesi).\n" +
                "  4. Arka planı ve artefaktı karodan önce dışlayın: Doku tespiti, Ignore* anotasyonları, en az kapsama.\n" +
                "  5. Ayarları ve seçim stratejisini kaydedin: küme klasörünün adı ve karo listesi bunu taşır.\n" +
                "Tek bir doğru ayar yoktur; görev ve modele bağlıdır.")
            rules.setWrapText(true)
            rules.setStyle("-fx-font-size: 11px; -fx-text-fill: #444;")
            def refresh = { ->
                def sb = new StringBuilder()
                try {
                    int ts = Integer.parseInt(tileField.getText().trim())
                    double um = Double.parseDouble(umField.getText().trim().replace(',', '.'))
                    int ov = Integer.parseInt(overlapField.getText().trim())
                    boolean cal = (slidePx > 0 && !Double.isNaN(slidePx))
                    double ds = cal ? (um / slidePx) : um
                    sb << String.format(java.util.Locale.US, "Görüş alanı : %s  (karo %d px × %.3f µm/px)%n",
                            (cal ? String.format(java.util.Locale.US, "%.0f × %.0f µm", ts * um, ts * um) : "bilinmiyor (lam kalibre değil)"), ts, um)
                    sb << String.format(java.util.Locale.US, "Bu lamda    : downsample %.2f%s%n", ds, (cal ? String.format(java.util.Locale.US, "  (lam %.4f µm/px)", slidePx) : "  (µm/px alanı downsample olarak yorumlanır)"))
                    sb << String.format(java.util.Locale.US, "Adım        : %d px%s%n", ts - ov, (ov > 0 ? String.format(java.util.Locale.US, "  (komşu karolar %d px = %%%.0f ortak)", ov, 100.0d * ov / ts) : "  (örtüşme yok)"))
                    if (cal && ds < 0.98d) sb << "⚠ İstenen ölçek lamın kendi çözünürlüğünden ince: karo büyütülerek üretilir, yeni ayrıntı içermez.\n"
                    if (!cal) sb << "⚠ Lam kalibre değil: önce piksel boyutunu girin, yoksa lamlar arasında ölçek tutmaz.\n"
                    if (ov > 0) sb << "⚠ Örtüşme açık: karo sayısı artar; karo düzeyinde rastgele bölme sızıntı olur.\n"
                    if (formatBox.getValue().startsWith("JPG")) sb << "⚠ JPG kayıplıdır: renk ölçümü ya da yeniden sıkıştırma gerekiyorsa PNG seçin.\n"
                    if (modeBox.getValue().startsWith("Görüntü +") && !formatBox.getValue().startsWith("PNG")) sb << "ℹ Maske modunda biçim PNG'ye çevrilir.\n"
                } catch (Throwable ignore) {
                    sb << "Karo boyutu, µm/px ve örtüşme alanlarına geçerli sayı girin.\n"
                }
                liveLabel.setText(sb.toString())
            }
            presetBox.getSelectionModel().selectedIndexProperty().addListener({ obs, o, n ->
                def p = PRESETS[n.intValue()]
                whyLabel.setText((String) p.neden)
                if (p.uygula != null) {
                    modeBox.getSelectionModel().select((int) p.uygula.mod)
                    tileField.setText((String) p.uygula.karo)
                    umField.setText((String) p.uygula.um)
                    overlapField.setText((String) p.uygula.ort)
                    coverField.setText((String) p.uygula.kap)
                    formatBox.getSelectionModel().select((int) p.uygula.fmt)
                }
                refresh()
            } as javafx.beans.value.ChangeListener)
            [tileField, umField, overlapField].each { f -> f.textProperty().addListener({ obs, o, n -> refresh() } as javafx.beans.value.ChangeListener) }
            [modeBox, formatBox].each { b -> b.valueProperty().addListener({ obs, o, n -> refresh() } as javafx.beans.value.ChangeListener) }
            refresh()
            def g0 = new javafx.scene.layout.VBox(10,
                new javafx.scene.layout.HBox(10, new javafx.scene.control.Label("Amaç:"), presetBox),
                help("Bir amaç seçince karo boyutu, µm/px, örtüşme, kapsama ve mod başlangıç değerlerine ayarlanır; sonra diğer sekmelerde istediğinizi değiştirin."),
                whyLabel, liveLabel, new javafx.scene.control.Separator(), rules)
            g0.setPadding(new javafx.geometry.Insets(12))

            def tabs = new javafx.scene.control.TabPane()
            tabs.setTabClosingPolicy(javafx.scene.control.TabPane.TabClosingPolicy.UNAVAILABLE)
            [["Rehber", g0], ["Temel", g1], ["Etiket ve bölge", g2], ["Çıktı düzeni", g3], ["Bölme ve sızıntı", g4]].each { p ->
                def sp = new javafx.scene.control.ScrollPane((javafx.scene.Node) p[1])
                sp.setFitToWidth(true)
                tabs.getTabs().add(new javafx.scene.control.Tab((String) p[0], sp))
            }

            def exportBtn = new javafx.scene.control.Button("Dışa aktar")
            exportBtn.setDefaultButton(true)
            exportBtn.setOnAction({
                try {
                    int ts = Integer.parseInt(tileField.getText().trim())
                    double um = Double.parseDouble(umField.getText().trim().replace(',', '.'))
                    int ov = Integer.parseInt(overlapField.getText().trim())
                    double mc = Double.parseDouble(coverField.getText().trim().replace(',', '.'))
                    int mx = Integer.parseInt(maxField.getText().trim())
                    int jq = Integer.parseInt(qualityField.getText().trim())
                    double mt = Double.parseDouble(tissueField.getText().trim().replace(',', '.'))
                    int wt = Integer.parseInt(whiteField.getText().trim())
                    if (mt < 0 || mt > 100) { Dialogs.showErrorMessage("Geçersiz", "En az doku 0 ile 100 arasında olmalı."); return }
                    if (wt < 0 || wt > 255) { Dialogs.showErrorMessage("Geçersiz", "Beyazlık eşiği 0 ile 255 arasında olmalı."); return }
                    if (coordsBox.isSelected() && !manifestBox.isSelected()) { Dialogs.showErrorMessage("Geçersiz", "\"Yalnız karo listesi\" seçiliyken karo listesi yazılmalıdır; yoksa hiçbir çıktı kalmaz."); return }
                    if (ts <= 0)            { Dialogs.showErrorMessage("Geçersiz", "Karo boyutu pozitif olmalı."); return }
                    if (!(um > 0))          { Dialogs.showErrorMessage("Geçersiz", "Çözünürlük pozitif olmalı."); return }
                    if (ov < 0 || ov >= ts) { Dialogs.showErrorMessage("Geçersiz", "Örtüşme 0 ile karo boyutu arasında olmalı."); return }
                    if (mc < 0 || mc > 100) { Dialogs.showErrorMessage("Geçersiz", "En az kapsama 0 ile 100 arasında olmalı."); return }
                    if (mx < 0)             { Dialogs.showErrorMessage("Geçersiz", "Etiket başına en çok karo 0 ya da pozitif olmalı."); return }
                    if (jq < 1 || jq > 100) { Dialogs.showErrorMessage("Geçersiz", "JPG kalitesi 1 ile 100 arasında olmalı."); return }
                    def labelChecks = labelPane.getChildren().findAll { it instanceof javafx.scene.control.CheckBox }
                    def picked = new LinkedHashSet()
                    labelChecks.each { if (it.isSelected()) picked.add(it.getUserData().toString()) }
                    if (picked.isEmpty()) { Dialogs.showErrorMessage("Geçersiz", "En az bir etiket işaretli olmalı."); return }
                    String outText = outField.getText().trim()
                    if (outText.isEmpty()) { Dialogs.showErrorMessage("Geçersiz", "Çıktı klasörü boş olamaz."); return }
                    def sk = csv(splitKeyField.getText()).collect { it.toLowerCase(java.util.Locale.ROOT) }
                    def pk = csv(patientKeyField.getText()).collect { it.toLowerCase(java.util.Locale.ROOT) }
                    if (sk.isEmpty()) { Dialogs.showErrorMessage("Geçersiz", "En az bir bölme anahtarı gerekir; boş bırakmak bölme denetimini kapatırdı."); return }
                    def fv = formatBox.getValue()
                    def gv = groupBox.getValue()
                    def lv = layoutBox.getValue()
                    result.set([
                        mode: (modeBox.getValue().startsWith("Görüntü +") ? 'mask' : 'image'),
                        scope: (scopeBox.getValue().startsWith("Seçili") ? 'selected' : 'all'),
                        group: (gv.startsWith("Anotasyon adına") ? 'name' : (gv.startsWith("Sınıf +") ? 'both' : 'class')),
                        tileSize: ts, umPerPx: um, overlap: ov,
                        ext: (fv.startsWith("JPG") ? '.jpg' : (fv.startsWith("TIFF") ? '.tif' : '.png')),
                        minCover: mc / 100.0d, subtractIgnore: ignoreBox.isSelected(), subtractChildren: childBox.isSelected(),
                        labels: (picked.size() == labelChecks.size() ? null : picked), exclude: csv(excludeField.getText()),
                        maxPerLabel: mx,
                        layout: (lv.startsWith("<lam>") ? 'slide-label' : (lv.contains("lam klasörü yok") ? 'label' : 'label-slide')),
                        labelInName: labelNameBox.isSelected(), splitInName: splitNameBox.isSelected(),
                        jpegQuality: jq, writeManifest: manifestBox.isSelected(), outRoot: outText,
                        minTissue: mt / 100.0d, whiteThreshold: wt, coordsOnly: coordsBox.isSelected(), preview: previewBox.isSelected(),
                        splitKeys: sk,
                        patientKeys: (pk.isEmpty() ? defaults.patientKeys : pk)])
                    stage.close()
                } catch (NumberFormatException nfe) {
                    Dialogs.showErrorMessage("Sayı formatı", "Karo boyutu, örtüşme, en çok karo ve JPG kalitesi tam sayı; çözünürlük ve kapsama ondalık olmalı (ör. 512 / 64 / 0 / 90 / 0.5 / 50).")
                }
            })
            def cancelBtn = new javafx.scene.control.Button("İptal")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({ result.set(null); stage.close() })

            stage.setOnHidden({ latch.countDown() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def buttons = new javafx.scene.layout.HBox(10, spacer, cancelBtn, exportBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(tabs)
            root.setBottom(buttons)
            stage.setScene(new javafx.scene.Scene(root, 900, 640))
            stage.show()
        } catch (Throwable t) {
            println "Seçenek penceresi açılamadı: " + t
            result.set(null)
            latch.countDown()
        }
    }
    latch.await()
    return result.get()
}

def showResultWindow = { String windowTitle, String windowBody ->
    if (isHeadless) {
        println "=== ${windowTitle} ===\n${windowBody}\n=================="
        return
    }
    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle(windowTitle)
            stage.setAlwaysOnTop(true)

            def textArea = new javafx.scene.control.TextArea(windowBody)
            textArea.setEditable(false)
            textArea.setWrapText(false)
            textArea.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;")

            def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut")
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )

            def copyBtn = new javafx.scene.control.Button("Kopyala")
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent()
                content.putString(windowBody)
                cb.setContent(content)
            })
            def closeBtn = new javafx.scene.control.Button("Kapat")
            closeBtn.setDefaultButton(true)
            closeBtn.setOnAction({ stage.close() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, copyBtn, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(textArea)
            def __footer = new javafx.scene.control.Label("QuPath Atölye Betikleri · araştırma/eğitim amaçlı")
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons)
            __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)
            stage.setScene(new javafx.scene.Scene(root, 760, 560))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 1) Ön kontrol: açık görüntü
// ──────────────────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Görüntü açık değil", "Önce bir lam açın, sonra bu betiği tekrar çalıştırın.")
    return
}
def server = imageData.getServer()
// getAveragedPixelSize() kalibre olmayan lamda 1.0 döndürür (NaN değil); bu yüzden µm bilgisi açıkça sorulur.
def pixelCal = server.getPixelCalibration()
double avgPx = pixelCal.hasPixelSizeMicrons() ? pixelCal.getAveragedPixelSizeMicrons().doubleValue() : Double.NaN

def selectedAnns = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI() != null }
def allAnns = QP.getAnnotationObjects().findAll { it.getROI() != null }

if (allAnns.isEmpty()) {
    Dialogs.showErrorMessage(
        "Anotasyon yok",
        "Karo dışa aktarımı anotasyonla sınırlıdır; lamdaki hiçbir anotasyon bulunamadı.\n\n" +
        "Önce bir bölge çizin (dikdörtgen/çokgen) ya da maske için sınıflı anotasyon\n" +
        "üretin (Tümör/Stroma modülü), sonra tekrar deneyin."
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 2) Seçenek formu
// ──────────────────────────────────────────────────────────────
def project = QP.getProject()
def baseDir = (project != null && project.getPath() != null)
    ? project.getPath().getParent().toFile()
    : new File(System.getProperty("user.home"), "QuPath")
// Form için anotasyon özeti: sınıf, ad, seçili mi? (etiket listesi bunlardan canlı kurulur)
def annInfo = allAnns.collect { a ->
    [cls: (a.getPathClass() != null ? a.getPathClass().toString() : null), name: a.getName(), sel: selectedAnns.contains(a)]
}
def opt = showOptionsForm(selectedAnns.size(), allAnns.size(), annInfo, new File(new File(baseDir, exportFolder), "tiles"), avgPx)
if (opt == null) { println "Karo dışa aktarımı iptal edildi."; return }

def maskMode = (opt.mode == 'mask')
def selectedScope = (opt.scope == 'selected')
int tileSize = opt.tileSize
double umPerPx = opt.umPerPx
int overlap = opt.overlap
String imageExt = opt.ext
String groupMode = (opt.group ?: 'class').toString()
double minCover = (opt.minCover ?: 0.0d) as double
boolean subtractIgnore = (opt.subtractIgnore != false)
boolean subtractChildren = (opt.subtractChildren != false)
def labelFilter = opt.labels                       // null = bütün etiketler
def excludePatterns = (opt.exclude ?: []).collect { it.toString().trim().toLowerCase(java.util.Locale.ROOT) }.findAll { !it.isEmpty() }
int maxPerLabel = (opt.maxPerLabel ?: 0) as int
String layoutMode = (opt.layout ?: 'label-slide').toString()
boolean labelInName = (opt.labelInName != false)
boolean splitInName = (opt.splitInName == true)
int jpegQuality = (opt.jpegQuality ?: 90) as int
boolean writeManifest = (opt.writeManifest != false)
double minTissue = (opt.minTissue ?: 0.0d) as double
int whiteThreshold = (opt.whiteThreshold ?: 220) as int
boolean coordsOnly = (opt.coordsOnly == true)
boolean writePreview = (opt.preview != false)
if (coordsOnly) writeManifest = true              // yalnız liste modunda tek çıktı listedir

// ──────────────────────────────────────────────────────────────
// 3) Çözünürlük → downsample
// ──────────────────────────────────────────────────────────────
boolean calibrated = (avgPx > 0 && !Double.isNaN(avgPx))
double downsample = calibrated ? (umPerPx / avgPx) : umPerPx
if (!(downsample > 0)) downsample = 1.0d

// ──────────────────────────────────────────────────────────────
// 4) Kapsam nesnelerini çöz + doğrula
// ──────────────────────────────────────────────────────────────
def inScope = selectedScope ? selectedAnns : allAnns
if (selectedScope && inScope.isEmpty()) {
    Dialogs.showErrorMessage(
        "Seçili anotasyon yok",
        "'Seçili anotasyon(lar)' kapsamı için önce bir veya birden çok anotasyon seçin\n" +
        "(Project/Annotations panelinden ya da lam üzerinde tıklayarak), sonra tekrar çalıştırın."
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 5) Maske modu: sınıf → indeks haritası
// ──────────────────────────────────────────────────────────────
def labelServer = null
def legend = []   // ["0 = arka plan", "1 = Tumor", ...]
def maskClasses = []   // bu lamda maskeye girecek sınıf adları (dışlama sınıfları arka plan kalır)
if (maskMode) {
    maskClasses = inScope.collect { it.getPathClass() }.findAll { it != null }.collect { it.toString() }
                         .findAll { !excludeMatch(it, excludePatterns) }.unique().sort()
    if (maskClasses.isEmpty()) {
        Dialogs.showErrorMessage(
            "Sınıflı anotasyon yok",
            "Etiket maskesi üretmek için anotasyonların bir SINIFI (PathClass) olmalı\n" +
            "(ör. Tumor, Stroma). Kapsamdaki anotasyonların hiçbiri sınıflı değil.\n\n" +
            "Çözüm: anotasyonları sınıflandırın (Tümör/Stroma modülü) ya da\n" +
            "'Sadece görüntü' modunu kullanın."
        )
        return
    }
    imageExt = '.png'   // maske modunda RGB karolar da kayıpsız olsun (JPEG önerilmez)
    // Sınıf → indeks haritası çıktı kümesi belli olduktan sonra kurulur (bkz. "Maske indeksleri"):
    // indeksler lamdan lama değişmemeli, bu yüzden kümedeki labels.txt esas alınır.
}

// ──────────────────────────────────────────────────────────────
// 6) Etiket grupları: her anotasyonun etiketi → birleşik bölge
// ──────────────────────────────────────────────────────────────
def isIgnoreClass = { ann -> excludeMatch((ann.getPathClass() != null ? ann.getPathClass().toString() : null), excludePatterns) }
def labelOf = { ann -> labelFor((ann.getPathClass() != null ? ann.getPathClass().toString() : null), ann.getName(), groupMode) }
def safeGeom = { roi ->
    def g = roi.getGeometry()
    try { if (!g.isValid()) g = g.buffer(0) } catch (Throwable ignore) {}
    return g
}
def unionOf = { List geoms ->
    if (geoms.isEmpty()) return null
    try { return org.locationtech.jts.operation.union.UnaryUnionOp.union(geoms) }
    catch (Throwable t) { return geoms.inject { a, b -> a.union(b) } }
}

// ── Eğitim / doğrulama / test bölmesi: üst veriden okunur, betik bölme ATAMAZ ──
// Önce anotasyonun kendi üst verisine (PathObject metadata), yoksa proje girdisinin
// üst verisine (Project sekmesi → sağ tık → "Add metadata") bakılır. Anahtar adları:
// split, bolme, set, fold, kume. Değerler train / val / test olarak normalleştirilir.
def SPLIT_KEYS = (opt.splitKeys ?: ['split', 'bolme', 'set', 'fold']).collect { it.toString().trim().toLowerCase(java.util.Locale.ROOT) } as Set
def metaSplit = { Map md ->
    if (md == null) return null
    for (e in md.entrySet()) {
        if (e.getKey() == null || e.getValue() == null) continue
        if (SPLIT_KEYS.contains(e.getKey().toString().trim().toLowerCase(java.util.Locale.ROOT)) && !e.getValue().toString().trim().isEmpty())
            return e.getValue().toString().trim()
    }
    return null
}
def normSplit = { String v ->
    if (v == null) return null
    String s = toSlug(v).toLowerCase(java.util.Locale.ROOT).replaceAll('^_+|_+$', '')
    if (s.isEmpty()) return null
    if (['train', 'training', 'egitim', 'trn'].contains(s)) return 'train'
    if (['val', 'valid', 'validation', 'dogrulama', 'dev'].contains(s)) return 'val'
    if (['test', 'testing', 'holdout', 'hold_out'].contains(s)) return 'test'
    return (s ==~ /\d+/) ? ('fold_' + s) : s
}
// Hasta kimliği (sızıntı denetimi için): proje girdisinin üst verisinden
def PATIENT_KEYS = (opt.patientKeys ?: ['patient', 'hasta', 'case', 'vaka']).collect { it.toString().trim().toLowerCase(java.util.Locale.ROOT) } as Set
def metaPatient = { Map md ->
    if (md == null) return null
    for (e in md.entrySet()) {
        if (e.getKey() == null || e.getValue() == null) continue
        if (PATIENT_KEYS.contains(e.getKey().toString().trim().toLowerCase(java.util.Locale.ROOT)) && !e.getValue().toString().trim().isEmpty())
            return e.getValue().toString().trim()
    }
    return null
}
// Hasta kimliği karşılaştırması: "P-001", "p-001 " ve tam genişlikli yazımlar aynı hastadır.
// Yalnız karşılaştırmada kullanılır; ekranda ve karo listesinde özgün yazım kalır.
def normPatient = { Object v ->
    if (v == null) return null
    String s = java.text.Normalizer.normalize(v.toString(), java.text.Normalizer.Form.NFKC)
                   .trim().replaceAll('\\s+', ' ').toLowerCase(java.util.Locale.ROOT)
    return s.isEmpty() ? null : s
}
// Bölme değeri yazılmış ama klasör adına çevrilemiyorsa (ör. yalnız Latin dışı harfler) "bölme yok"
// sayılmaz: sessizce bölmesiz yazmak sızıntı denetimini atlatırdı. Bu değerler toplanır, çalışma durur.
def badSplits = new java.util.TreeSet()
def readSplit = { Map md ->
    String raw = metaSplit(md)
    String n = normSplit(raw)
    if (raw != null && n == null) badSplits.add(raw)
    return n
}
String entrySplit = null
String patientId = null
def projEntry = null
try {
    projEntry = (project != null) ? project.getEntry(imageData) : null
    if (projEntry != null) {
        entrySplit = readSplit(projEntry.getMetadata())
        patientId = metaPatient(projEntry.getMetadata())
    }
} catch (Throwable ignore) {}
String patientKey = normPatient(patientId)
// [bölme, kaynak] — bölme yoksa [null, '']
def splitOf = { ann ->
    String v = null
    try { v = readSplit(ann.getMetadata()) } catch (Throwable ignore) {}
    if (v != null) return [v, 'anotasyon']
    if (entrySplit != null) return [entrySplit, 'proje girdisi']
    return [null, '']
}

def ignoreAnns = allAnns.findAll { isIgnoreClass(it) }
def ignoreUnion = (subtractIgnore && !ignoreAnns.isEmpty()) ? unionOf(ignoreAnns.collect { safeGeom(it.getROI()) }) : null

// Grup anahtarı = bölme + etiket → [geometri listesi]; ad ve sınıf listeleri karo listesine yazılır
def groupGeoms = new java.util.TreeMap<String, List>()
def groupCount = new java.util.TreeMap<String, Integer>()
def groupInfo = new java.util.HashMap<String, Map>()
int ignoredAsLabel = 0
int skippedByClass = 0
inScope.each { ann ->
    if (isIgnoreClass(ann)) { ignoredAsLabel++; return }
    if (labelFilter != null && !labelFilter.contains(labelOf(ann))) { skippedByClass++; return }
    def g = safeGeom(ann.getROI())
    if (g.getArea() <= 0) return                      // çizgi ve nokta anotasyonları karo üretmez
    if (subtractChildren) {
        def kids = allAnns.findAll { it.getParent() == ann && !isIgnoreClass(it) && it.getROI().isArea() }   // doğrudan alt anotasyonlar
        if (!kids.isEmpty()) {
            try { g = g.difference(unionOf(kids.collect { safeGeom(it.getROI()) })) } catch (Throwable ignore) {}
        }
    }
    if (ignoreUnion != null) {
        try { if (g.intersects(ignoreUnion)) g = g.difference(ignoreUnion) } catch (Throwable ignore) {}
    }
    if (g == null || g.isEmpty() || g.getArea() <= 0) return
    String lab = labelOf(ann)
    def sp = splitOf(ann)
    String key = ((sp[0] != null) ? sp[0].toString() : '') + '|' + lab
    if (!groupGeoms.containsKey(key)) {
        groupGeoms.put(key, []); groupCount.put(key, 0)
        groupInfo.put(key, [label: lab, split: sp[0], source: sp[1], names: new java.util.TreeSet(), classes: new java.util.TreeSet()])
    }
    groupGeoms.get(key) << g
    groupCount.put(key, groupCount.get(key) + 1)
    if (ann.getName() != null && !ann.getName().trim().isEmpty()) groupInfo.get(key).names.add(ann.getName().trim())
    if (ann.getPathClass() != null) groupInfo.get(key).classes.add(ann.getPathClass().toString())
}
if (!badSplits.isEmpty()) {
    Dialogs.showErrorMessage(
        "Bölme değeri okunamadı",
        "Şu bölme değerleri klasör adına çevrilemedi: " + badSplits.take(6).join(", ") + ".\n\n" +
        "Bölme değeri Latin harfi ya da rakam içermelidir (ör. train, val, test, fold_1).\n" +
        "Değer yok sayılıp bölmesiz yazılsaydı sızıntı denetimi yapılamazdı; hiçbir karo yazılmadı."
    )
    return
}
boolean useSplit = groupInfo.values().any { it.split != null }
// Bölme varken karo listesi kapatılamaz: hasta ↔ bölme kaydı sonraki lamların sızıntı denetiminde kullanılır.
if (useSplit && !writeManifest) {
    writeManifest = true
    println "Bölme kullanıldığı için karo listesi yazılacak (sızıntı denetiminin kaydıdır)."
}
def splitsHere = new java.util.TreeSet(groupInfo.values().collect { it.split }.findAll { it != null })
String NO_SPLIT = 'bolmesiz'
def keyTitle = { String key ->
    def gi = groupInfo.get(key)
    return useSplit ? (((gi.split != null) ? gi.split : NO_SPLIT) + ' / ' + gi.label) : gi.label.toString()
}
if (groupGeoms.isEmpty()) {
    Dialogs.showErrorMessage(
        "Karo üretilecek bölge yok",
        "Kapsamdaki anotasyonlardan alan içeren bir bölge kalmadı.\n\n" +
        "Olası nedenler: yalnız Ignore* sınıflı, çizgi ya da nokta anotasyonları var;\n" +
        "ya da bölgelerin tamamı Ignore* / alt anotasyon çıkarmasıyla boşaldı."
    )
    return
}

// ──────────────────────────────────────────────────────────────
// 7) Karo listesi: görüntünün (0,0) köşesine hizalı ızgara
// ──────────────────────────────────────────────────────────────
int imgW = server.getWidth()
int imgH = server.getHeight()
int tileL0 = (int) Math.max(1L, Math.round(tileSize * downsample))            // karonun taban pikseldeki kenarı
int stepL0 = (int) Math.max(1L, Math.round((tileSize - overlap) * downsample)) // ızgara adımı (taban piksel)
double tileArea = ((double) tileL0) * ((double) tileL0)

def tiles = []          // [grup anahtarı, x, y, kapsama]
def perLabel = new java.util.TreeMap<String, Integer>()
groupGeoms.each { String lab, List geoms ->
    def region = unionOf(geoms)
    perLabel.put(lab, 0)
    if (region == null || region.isEmpty()) return
    def prep = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(region)
    def env = region.getEnvelopeInternal()
    def gf = region.getFactory()
    long ix0 = (long) Math.max(0L, Math.floor((env.getMinX() - tileL0) / stepL0))
    long iy0 = (long) Math.max(0L, Math.floor((env.getMinY() - tileL0) / stepL0))
    for (long iy = iy0; iy * stepL0 < env.getMaxY(); iy++) {
        long y = iy * stepL0
        if (y + tileL0 > imgH) break                  // görüntü dışına taşan kısmi karo yazılmaz
        for (long ix = ix0; ix * stepL0 < env.getMaxX(); ix++) {
            long x = ix * stepL0
            if (x + tileL0 > imgW) break
            def rect = gf.toGeometry(new org.locationtech.jts.geom.Envelope((double) x, (double) (x + tileL0), (double) y, (double) (y + tileL0)))
            if (!prep.intersects(rect)) continue
            double cover = 1.0d
            if (!prep.contains(rect)) {
                try { cover = region.intersection(rect).getArea() / tileArea } catch (Throwable t) { cover = 0.0d }
            }
            if (cover <= 0.0d || cover < minCover) continue
            tiles << [lab, (int) x, (int) y, cover]
            perLabel.put(lab, perLabel.get(lab) + 1)
        }
    }
}

// Aynı ızgara karosu birden çok etiket bölgesine değebilir (sınır karoları). Her karo yalnız
// BİR etikete yazılır: kapsaması en büyük olana. Böylece aynı pikseller iki klasöre girmez.
int sharedTiles = 0
def bestByCell = new java.util.LinkedHashMap<String, List>()
tiles.each { List tile ->
    String cell = tile[1] + '_' + tile[2]
    def prev = bestByCell.get(cell)
    if (prev == null) { bestByCell.put(cell, tile); return }
    sharedTiles++
    if (((double) tile[3]) > ((double) prev[3])) bestByCell.put(cell, tile)
}
if (sharedTiles > 0) {
    tiles = new ArrayList(bestByCell.values())
    perLabel.keySet().each { String k -> perLabel.put(k, 0) }
    tiles.each { List tile -> perLabel.put((String) tile[0], perLabel.get((String) tile[0]) + 1) }
}

// Etiket başına en çok N karo: rastgele ama yinelenebilir (tohum = lam adı), sınıf dengesi için.
// Doku süzgeci açıksa sınır BURADA uygulanmaz: önce süzgeç, sonra sınır (bölüm 9'daki ön seçim).
// Yoksa N karo seçilip ardından boş cam karoları atılır ve etiket N'nin altında kalır.
boolean capAfterTissue = (maxPerLabel > 0 && minTissue > 0.0d)
int droppedByCap = 0
if (maxPerLabel > 0 && !capAfterTissue) {
    def kept = []
    tiles.groupBy { it[0] }.each { key, List list ->
        if (list.size() > maxPerLabel) {
            def shuffled = new ArrayList(list)
            java.util.Collections.shuffle(shuffled, new java.util.Random((long) (server.getMetadata().getName() + '|' + key).hashCode()))
            droppedByCap += list.size() - maxPerLabel
            kept.addAll(shuffled.subList(0, maxPerLabel))
        } else kept.addAll(list)
    }
    tiles = kept
    perLabel.keySet().each { String k -> perLabel.put(k, 0) }
    tiles.each { List tile -> perLabel.put((String) tile[0], perLabel.get((String) tile[0]) + 1) }
}

int expectedTiles = tiles.size()
if (capAfterTissue) {
    expectedTiles = 0
    tiles.groupBy { it[0] }.each { key, List list -> expectedTiles += Math.min(list.size(), maxPerLabel) }
}
if (!isHeadless && expectedTiles > 5000) {
    def ok = Dialogs.showConfirmDialog(
        "Çok sayıda karo",
        String.format(java.util.Locale.US,
            "Bu ayarlarla %s%,d karo yazılacak.\n\n" +
            "Devam edilsin mi? (Karo boyutunu büyütmek, µm/px değerini büyütmek ya da\n" +
            "en az kapsama eşiğini yükseltmek karo sayısını azaltır.)", (capAfterTissue ? "en çok " : ""), expectedTiles)
    )
    if (!ok) { println "Kullanıcı çok-karo onayını reddetti."; return }
}

// ──────────────────────────────────────────────────────────────
// 8) Çıktı klasörü: ayarları taşıyan küme klasörü / etiket / lam
// ──────────────────────────────────────────────────────────────
def slug = toSlug(qupath.lib.common.GeneralTools.stripExtension(server.getMetadata().getName()))
def numLabel = { double v ->
    String s = String.format(java.util.Locale.US, "%.3f", v)
    s = s.replaceAll('0+$', '').replaceAll('\\.$', '')
    return s
}
String dsLabel = numLabel(downsample)
String setName = "karo_" + tileSize + "px_" + (calibrated ? (numLabel(umPerPx).replace('.', 'p') + "um") : ("ds" + dsLabel.replace('.', 'p'))) + "_ov" + overlap
def outDir = new File(new File((opt.outRoot ?: new File(new File(baseDir, exportFolder), "tiles").getAbsolutePath()).toString()), setName)
outDir.mkdirs()

// ── Sızıntı denetimi: bölme kullanılıyorsa aşağıdakilerin hiçbirine izin verilmez ──
//   (a) aynı lamın birden çok bölmeye yazılması,
//   (b) bu lamın karolarının aynı kümede başka bir bölmede zaten bulunması,
//   (c) aynı hastanın başka bir lamının projede farklı bölmede olması,
//   (d) hasta kimliği olmadan sessizce devam etmek.
// Bu lamın karo dosyaları: ad her zaman "<lam>__" ile başlar ve "ds<d>_x<X>_y<Y>" ile biter (klasör düzeninden bağımsız).
def tilePattern = java.util.regex.Pattern.compile('^' + java.util.regex.Pattern.quote(slug) + '__(.+__)?ds[0-9.]+_x\\d+_y\\d+\\.(png|jpg|tif)' + '\$')
def findSlideTiles = { File rootDir ->
    def found = []
    if (rootDir == null || !rootDir.isDirectory()) return found
    try {
        java.nio.file.Files.walk(rootDir.toPath(), 5).withCloseable { st ->
            st.filter { java.nio.file.Files.isRegularFile(it) && tilePattern.matcher(it.getFileName().toString()).matches() }
              .forEach { found << it.toFile() }
        }
    } catch (Throwable ignore) {}
    return found
}
String slideSplit = null
boolean patientVerified = false
// ── Küme düzeyi tutarlılık: bir çıktı kümesi ya tümüyle bölmeli ya tümüyle bölmesizdir ──
// Bölmesiz bir lam bölmeli kümeye (ya da tersi) yazılırsa hasta ↔ bölme denetimi o lam için hiç çalışmaz;
// aynı hasta hem "test" içinde hem bölmesiz klasörde bulunabilir. Bu yüzden karışık kümeye yazılmaz.
// Okunamayan bir karo listesi de denetimi kör eder: o durumda da durulur.
def otherListFiles = (outDir.listFiles() ?: new File[0]).findAll {
    it.isFile() && it.getName().startsWith("karo_listesi__") && it.getName().endsWith(".tsv") && it.getName() != ("karo_listesi__" + slug + ".tsv")
}
def setSplitLists = []
def setUnsplitLists = []
def unreadableLists = []
otherListFiles.each { lf ->
    try {
        def rows = lf.readLines("UTF-8")
        if (rows.size() < 2) return
        int ib = (rows[0].split("\t") as List).indexOf("bolme")
        if (ib < 0) { unreadableLists << lf.getName(); return }
        def vals = rows.drop(1).findAll { !it.trim().isEmpty() }.collect { ln -> def c = ln.split("\t", -1); (c.length > ib) ? c[ib].trim() : '' }
        if (vals.any { !it.isEmpty() }) setSplitLists << lf.getName()
        if (vals.any { it.isEmpty() }) setUnsplitLists << lf.getName()
    } catch (Throwable t) { unreadableLists << lf.getName() }
}
if (!unreadableLists.isEmpty()) {
    Dialogs.showErrorMessage(
        "Veri sızıntısı denetlenemedi: karo listesi okunamadı",
        "Bu çıktı kümesindeki şu karo listeleri okunamadı ya da 'bolme' sütunu yok:\n  " + unreadableLists.take(8).join("\n  ") + "\n\n" +
        "Bu listeler olmadan kümedeki diğer lamların bölmesi ve hastası denetlenemez. Hiçbir karo yazılmadı.\n" +
        "Dosya başka bir programda açıksa kapatın; bozuksa o lamı yeniden dışa aktarın ya da başka bir çıktı klasörü seçin."
    )
    return
}
if (!useSplit && !setSplitLists.isEmpty()) {
    Dialogs.showErrorMessage(
        "Veri sızıntısı önlendi: bölmesiz lam, bölmeli küme",
        "Bu çıktı kümesinde bölmesi olan lamlar var:\n  " + setSplitLists.take(8).join("\n  ") + "\n\n" +
        "Bu lamda bölme üst verisi yok. Bölmesiz yazılırsa hasta ↔ bölme denetimi bu lam için çalışmaz;\n" +
        "aynı hasta hem bir bölmede hem bölmesiz klasörde bulunabilir. Hiçbir karo yazılmadı.\n\n" +
        "Bu lama bölme verin (proje girdisi ya da anotasyon üst verisi) ya da başka bir çıktı klasörü seçin."
    )
    return
}
if (useSplit && !setUnsplitLists.isEmpty()) {
    Dialogs.showErrorMessage(
        "Veri sızıntısı önlendi: bölmeli lam, bölmesiz küme",
        "Bu çıktı kümesinde bölmesiz yazılmış lamlar var:\n  " + setUnsplitLists.take(8).join("\n  ") + "\n\n" +
        "O lamların hastaları hiçbir bölmeye bağlı değil; bu lamın hastasıyla çakışıp çakışmadığı denetlenemez.\n" +
        "Hiçbir karo yazılmadı. O lamlara bölme verip yeniden dışa aktarın ya da başka bir çıktı klasörü seçin."
    )
    return
}
if (useSplit) {
    def distinct = new java.util.TreeSet(groupInfo.values().collect { it.split != null ? it.split.toString() : NO_SPLIT })
    if (distinct.size() > 1) {
        Dialogs.showErrorMessage(
            "Veri sızıntısı önlendi: bir lam, birden çok bölme",
            "Bu lamdaki anotasyonlar birden çok bölmeye düşüyor: " + distinct.join(", ") + ".\n\n" +
            "Aynı lamın (aynı hastanın) karoları eğitim ve test kümelerine birlikte girerse\n" +
            "model sınanırken gördüğü dokuyu tanır; ölçülen başarım olduğundan yüksek çıkar.\n\n" +
            "Hiçbir karo yazılmadı. Bölmeyi lam / hasta düzeyinde verin: bu lamdaki bütün\n" +
            "anotasyonlar aynı bölmeyi taşımalı (ya da bölme proje girdisine yazılmalı).\n" +
            "\"" + NO_SPLIT + "\" = bölme üst verisi olmayan anotasyon."
        )
        return
    }
    slideSplit = distinct.first()
    // (b) aynı kümede başka bölme klasöründe bu lamın karoları var mı?
    def clash = []
    // Bölme adı serbest olabilir (external, holdout2 …): kümedeki şimdiki bölme dışındaki HER üst klasöre bakılır.
    // Bölmesiz yazılmış eski bir düzenin etiket klasörleri de bu kapsama girer: lam iki yerde birden olamaz.
    def splitDirs = outDir.listFiles()
    if (splitDirs != null) {
        splitDirs.findAll { it.isDirectory() && it.getName() != slideSplit }.each { sd ->
            def hit = findSlideTiles(sd)
            if (!hit.isEmpty()) clash << (sd.getName() + "/ → " + hit.size() + " karo (ör. " + outDir.toPath().relativize(hit[0].toPath()).toString().replace('\\', '/') + ")")
        }
    }
    if (!clash.isEmpty()) {
        Dialogs.showErrorMessage(
            "Veri sızıntısı önlendi: lam başka bölmede zaten var",
            "Bu lamın karoları aynı çıktı kümesinde başka bir bölmede bulunuyor:\n  " + clash.take(6).join("\n  ") + "\n\n" +
            "Şimdiki bölme: " + slideSplit + ". Hiçbir karo yazılmadı.\n" +
            "Önce eski klasörü kendiniz kaldırın ya da bölme üst verisini düzeltin."
        )
        return
    }
    // (c) aynı hastanın başka lamı projede farklı bölmede mi?
    if (patientKey != null && project != null) {
        def conflicts = []
        def unreadable = []
        int siblingCount = 0
        try {
            project.getImageList().each { other ->
                if (other == projEntry) return
                def omd = other.getMetadata()
                String op = normPatient(metaPatient(omd))
                if (op == null || op != patientKey) return
                siblingCount++
                // Aynı hastanın diğer lamı: bölme proje girdisinde yoksa kayıtlı anotasyonlarının üst verisine de bakılır
                // (görüntü açılmadan, kayıtlı hiyerarşiden). Okunamazsa "denetlenemedi" sayılır.
                def found = new java.util.TreeSet()
                // Okunamayan bölme değeri ("?…") hiçbir bölmeye eşit sayılmaz → çakışma olarak bildirilir.
                def splitOrUnknown = { Map md2 ->
                    String raw = metaSplit(md2)
                    String n = normSplit(raw)
                    return (raw != null && n == null) ? ('?' + raw) : n
                }
                String os = splitOrUnknown(omd)
                if (os != null) found.add(os)
                try {
                    def oh = other.readHierarchy()
                    if (oh != null) oh.getAnnotationObjects().each { oa ->
                        String asp = null
                        try { asp = splitOrUnknown(oa.getMetadata()) } catch (Throwable ignore2) {}
                        if (asp != null) found.add(asp)
                    }
                } catch (Throwable th) {
                    if (os == null) unreadable << other.getImageName()
                }
                found.findAll { it != slideSplit }.each { conflicts << (other.getImageName() + " → " + it) }
            }
        } catch (Throwable ignore) {}
        if (!conflicts.isEmpty()) {
            Dialogs.showErrorMessage(
                "Veri sızıntısı önlendi: aynı hasta, farklı bölme",
                "Bu lamın hastası (" + patientId + ") projede başka bir bölmede de var:\n  " + conflicts.take(8).join("\n  ") + "\n\n" +
                "Şimdiki lamın bölmesi: " + slideSplit + ". Hiçbir karo yazılmadı.\n" +
                "Bir hastanın bütün lamları aynı bölmede olmalıdır."
            )
            return
        }
        if (!unreadable.isEmpty()) {
            String why2 = "Aynı hastanın (" + patientId + ") şu lamlarının anotasyonları okunamadı; bölmeleri denetlenemedi:\n  " + unreadable.take(8).join("\n  ")
            if (isHeadless) { println "Veri sızıntısı denetlenemedi; karo yazılmadı.\n" + why2; return }
            if (!Dialogs.showConfirmDialog("Hasta düzeyinde sızıntı denetlenemedi", why2 + "\n\nBu lamların aynı bölmede olduğundan eminseniz devam edin.\nDevam edilsin mi?")) {
                println "Kullanıcı dışa aktarmayı durdurdu."; return
            }
        }
        // Aynı kümeye daha önce yazılmış karo listeleri: aynı hasta başka bölmede mi? (başka projeden yazılmış olsa bile)
        def listClash = []
        def lists = outDir.listFiles()
        if (lists != null) lists.findAll { it.isFile() && it.getName().startsWith("karo_listesi__") && it.getName().endsWith(".tsv") && it.getName() != ("karo_listesi__" + slug + ".tsv") }.each { lf ->
            try {
                def rows = lf.readLines("UTF-8")
                if (rows.size() < 2) return
                def head = rows[0].split("\t") as List
                int ih = head.indexOf("hasta")
                int ib = head.indexOf("bolme")
                if (ih < 0 || ib < 0) return
                def seen = new java.util.TreeSet()
                rows.drop(1).each { ln -> def c = ln.split("\t", -1); if (c.length > Math.max(ih, ib) && normPatient(c[ih]) == patientKey && !c[ib].isEmpty() && c[ib] != slideSplit) seen.add(c[ib]) }
                seen.each { listClash << (lf.getName() + " → " + it) }
            } catch (Throwable ignore) {}
        }
        if (!listClash.isEmpty()) {
            Dialogs.showErrorMessage(
                "Veri sızıntısı önlendi: aynı hasta, farklı bölme",
                "Bu hastanın (" + patientId + ") karoları aynı çıktı kümesinde başka bir bölmede zaten var:\n  " + listClash.take(8).join("\n  ") + "\n\n" +
                "Şimdiki lamın bölmesi: " + slideSplit + ". Hiçbir karo yazılmadı."
            )
            return
        }
        patientVerified = true
    } else {
        // (d) hasta kimliği yok: denetlenemez → durur. Onayla devam yolu YOKTUR (ne pencerede ne headless):
        // bir "eminim" tıklaması hasta düzeyi denetimin yerini tutmaz ve denetimi atlatmanın tek yolu olurdu.
        String why = "Bölme üst verisi var, ama bu lamın proje girdisinde hasta kimliği yok\n" +
                     "(anahtar: patient / hasta / case / vaka / patient_pseudonym …).\n\n" +
                     "Aynı hastanın başka lamlarının başka bölmede olup olmadığı DENETLENEMEDİ.\n\n" +
                     "Proje sekmesinde lama sağ tıklayın → Add metadata → anahtar \"hasta\", değer hasta kimliği\n" +
                     "(takma ad olabilir; aynı hastanın bütün lamlarında aynı olmalı). Sonra yeniden çalıştırın.\n" +
                     "Hiçbir karo yazılmadı."
        println "Veri sızıntısı denetlenemedi; karo yazılmadı.\n" + why
        if (!isHeadless) Dialogs.showErrorMessage("Hasta kimliği yok: karo yazılmadı", why)
        return
    }
}

// ── Maske indeksleri: küme boyunca sabit ──
// İndeksler her lamda alfabetik sıradan yeniden üretilseydi "1" bir lamda Stroma, ötekinde Tümör olurdu.
// Kümedeki labels.txt esas alınır: bilinen sınıf indeksini korur, yeni sınıf sona eklenir, dosya hiç daraltılmaz.
// Dışlama (Ignore*) sınıfları indeks almaz; maskede arka plan (0) kalır.
if (maskMode) {
    def labelsFile = new File(outDir, "labels.txt")
    def classIndex = new java.util.LinkedHashMap<String, Integer>()
    if (labelsFile.isFile()) {
        boolean bad = false
        try {
            def lp = java.util.regex.Pattern.compile('(\\d+) = (.+)')
            labelsFile.readLines("UTF-8").each { ln ->
                if (ln.trim().isEmpty()) return
                def m = lp.matcher(ln.trim())
                if (!m.matches()) { bad = true; return }
                int idx = Integer.parseInt(m.group(1))
                if (idx == 0) return
                if (classIndex.containsKey(m.group(2)) || classIndex.containsValue(idx)) bad = true
                classIndex.put(m.group(2), idx)
            }
        } catch (Throwable t) { bad = true }
        if (bad) {
            Dialogs.showErrorMessage(
                "labels.txt okunamadı",
                "Bu çıktı kümesindeki labels.txt okunamadı ya da aynı indeks / sınıf iki kez geçiyor:\n  " + labelsFile.getAbsolutePath() + "\n\n" +
                "Maske indeksleri kümedeki diğer lamlarla aynı olmak zorundadır; dosya olmadan bu sağlanamaz.\n" +
                "Hiçbir karo yazılmadı. Dosyayı düzeltin ya da başka bir çıktı klasörü seçin."
            )
            return
        }
    }
    int nextIndex = classIndex.isEmpty() ? 1 : (classIndex.values().max() + 1)
    maskClasses.each { String cn -> if (!classIndex.containsKey(cn)) { classIndex.put(cn, nextIndex); nextIndex++ } }
    if (classIndex.values().max() > 255) {
        Dialogs.showErrorMessage("Çok fazla sınıf", "Bu çıktı kümesinde 255'ten fazla maske sınıfı birikti; 8 bit maskeye sığmaz. Hiçbir karo yazılmadı.")
        return
    }
    def builder = new LabeledImageServer.Builder(imageData)
        .backgroundLabel(0, ColorTools.WHITE)
        .downsample(downsample)
        .multichannelOutput(false)
    legend << "0 = arka plan"
    classIndex.entrySet().sort { it.getValue() }.each { e ->
        builder.addLabel(e.getKey(), e.getValue())
        legend << (e.getValue() + " = " + e.getKey())
    }
    labelServer = builder.build()
    // Karolardan ÖNCE yazılır: yazılamıyorsa indeks kaydı olmayan maske üretilmez.
    try { labelsFile.write(legend.join("\n") + "\n", "UTF-8") }
    catch (Throwable t) {
        Dialogs.showErrorMessage("labels.txt yazılamadı", "Sınıf → indeks kaydı yazılamadı: " + t.getMessage() + "\nHiçbir karo yazılmadı.")
        return
    }
}

// ── Bu lamın aynı kümede önceden yazılmış karoları var mı? ──
// Küme klasörü tarih taşımaz; yeniden çalıştırma aynı adlı karoların üzerine yazar ama
// anotasyon küçültüldüyse ya da sınıfı değiştiyse eski karolar klasörde kalır.
// Varsayılan: hiçbir şey silinmez. Silme yalnız açık seçimle ve yalnız bu lamın karolarında.
def oldTileFiles = findSlideTiles(useSplit ? new File(outDir, slideSplit) : outDir)
String oldTilesNote = null
if (!oldTileFiles.isEmpty()) {
    String KEEP = "Eski karoları bırak (yalnız üzerine yaz)"
    String WIPE = "Bu lamın eski karolarını sil, sonra yaz"
    String choice = KEEP
    if (!isHeadless) {
        choice = Dialogs.showChoiceDialog(
            "Bu lamın eski karoları var",
            String.format(java.util.Locale.US,
                "Bu çıktı kümesinde bu lama ait %,d karo dosyası zaten var.\n\n" +
                "Anotasyonu yeniden çizdiyseniz ya da sınıfını değiştirdiyseniz eski karolar\n" +
                "klasörde kalır; aynı karo iki etiket klasöründe birden bulunabilir.\n\n" +
                "Silme yalnız bu lamın, bu kümedeki karo dosyalarını kapsar.", oldTileFiles.size()),
            [KEEP, WIPE], KEEP)
        if (choice == null) { println "Karo dışa aktarımı iptal edildi."; return }
    }
    if (choice == WIPE) {
        int removed = 0
        oldTileFiles.each { try { if (it.delete()) removed++ } catch (Throwable ignore) {} }
        oldTilesNote = "  • Bu lamın " + removed + " eski karo dosyası silindi (seçiminizle)."
    } else {
        oldTilesNote = "  • ⚠️ Bu lamın " + oldTileFiles.size() + " eski karo dosyası yerinde bırakıldı. Klasörde güncel olmayan karo\n" +
                       "    kalmış olabilir: eğitimde klasör içeriğini değil, karo listesini (" + ("karo_listesi__" + slug + ".tsv") + ") kullanın."
    }
}

println "─────────────────────────────────────"
println "Karo (tile) dışa aktarma"
println "─────────────────────────────────────"
println "Mod      : " + (maskMode ? "Görüntü + etiket maskesi" : "Sadece görüntü")
println "Kapsam   : " + (selectedScope ? "Seçili anotasyon(lar)" : "Tüm anotasyonlar") + " (${inScope.size()})"
println String.format(java.util.Locale.US, "Çözünürlük: %.3f µm/px → downsample %.3f%s", umPerPx, downsample, (calibrated ? "" : " (kalibre değil)"))
println "Etiketler" + ((minTissue > 0.0d) ? " (aday, doku süzgecinden önce)" : "") + ": " + perLabel.collect { k, v -> keyTitle(k) + "=" + v }.join(", ")
println "Çıktı    : ${outDir.getAbsolutePath()}"

// ──────────────────────────────────────────────────────────────
// 9) Dışa aktar: karoları tek tek oku ve yaz (birkaç iş parçacığı)
// ──────────────────────────────────────────────────────────────
def plane = inScope[0].getROI().getImagePlane()
int planeZ = plane.getZ()
int planeT = plane.getT()
String serverPath = server.getPath()
String labelPath = (labelServer != null) ? labelServer.getPath() : null
String imgFormat = (imageExt == '.jpg') ? 'jpg' : (imageExt == '.tif' ? 'tiff' : 'png')

// Okunan karo tam karo boyutunda değilse (yuvarlama) yeniden ölçekle; maskede en yakın komşu.
def fitTo = { java.awt.image.BufferedImage img, boolean nearest ->
    if (img.getWidth() == tileSize && img.getHeight() == tileSize) return img
    def at = java.awt.geom.AffineTransform.getScaleInstance(tileSize / (double) img.getWidth(), tileSize / (double) img.getHeight())
    def op = new java.awt.image.AffineTransformOp(at, nearest ? java.awt.image.AffineTransformOp.TYPE_NEAREST_NEIGHBOR : java.awt.image.AffineTransformOp.TYPE_BILINEAR)
    def dst = op.createCompatibleDestImage(img, img.getColorModel())
    def exact = new java.awt.image.BufferedImage(img.getColorModel(), dst.getRaster().createCompatibleWritableRaster(tileSize, tileSize), img.isAlphaPremultiplied(), null)
    op.filter(img, exact)
    return exact
}
def toRgb = { java.awt.image.BufferedImage img ->
    if (img.getType() == java.awt.image.BufferedImage.TYPE_INT_RGB) return img
    def rgb = new java.awt.image.BufferedImage(img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB)
    def g2 = rgb.createGraphics()
    g2.setColor(java.awt.Color.WHITE); g2.fillRect(0, 0, img.getWidth(), img.getHeight())
    g2.drawImage(img, 0, 0, null); g2.dispose()
    return rgb
}

def errors = java.util.Collections.synchronizedList(new ArrayList())
def manifestRows = java.util.Collections.synchronizedList(new ArrayList())
def imageCounter = new java.util.concurrent.atomic.AtomicInteger(0)
def maskCounter = new java.util.concurrent.atomic.AtomicInteger(0)
def madeDirs = java.util.concurrent.ConcurrentHashMap.newKeySet()
def tissueDropped = new java.util.concurrent.atomic.AtomicInteger(0)
boolean needPixels = !coordsOnly || minTissue > 0.0d
// Doku oranı: gri değeri beyazlık eşiğinin altındaki piksellerin payı (her 2. piksel örneklenir).
def tissueFraction = { java.awt.image.BufferedImage im ->
    int w = im.getWidth()
    int h = im.getHeight()
    long dark = 0L
    long total = 0L
    for (int yy = 0; yy < h; yy += 2) {
        for (int xx = 0; xx < w; xx += 2) {
            int p = im.getRGB(xx, yy)
            int gray = (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF)) / 3 as int
            if (gray < whiteThreshold) dark++
            total++
        }
    }
    return total == 0L ? 0.0d : (dark / (double) total)
}

def writeOne = { List tile ->
    String key = (String) tile[0]
    def gi = groupInfo.get(key)
    String lab = gi.label.toString()
    int x = (int) tile[1]
    int y = (int) tile[2]
    double cover = (double) tile[3]
    String splitName = (gi.split != null ? gi.split.toString() : NO_SPLIT)
    String fileStem = slug + ((useSplit && splitInName) ? ("__" + splitName) : "") + (labelInName ? ("__" + lab) : "") + "__ds" + dsLabel + "_x" + x + "_y" + y
    def splitDir = useSplit ? new File(outDir, splitName) : outDir
    def slideDir = (layoutMode == 'slide-label') ? new File(new File(splitDir, slug), lab)
                 : ((layoutMode == 'label') ? new File(splitDir, lab) : new File(new File(splitDir, lab), slug))
    def imgDir = maskMode ? new File(slideDir, "images") : slideDir
    def labDir = maskMode ? new File(slideDir, "labels") : null
    try {
        def req = RegionRequest.createInstance(serverPath, downsample, x, y, tileL0, tileL0, planeZ, planeT)
        def img = needPixels ? fitTo(server.readRegion(req), false) : null
        String tissueText = ''
        if (img != null) {
            double tf = tissueFraction(img)
            tissueText = String.format(java.util.Locale.US, "%.4f", tf)
            if (minTissue > 0.0d && tf < minTissue) { tissueDropped.incrementAndGet(); return }
        }
        if (coordsOnly) {
            manifestRows << [lab, x, y, String.format(java.util.Locale.US, "%.4f", cover), '', '',
                             (gi.split != null ? gi.split : ""), gi.source, gi.classes.join("; "), gi.names.join("; "), (patientId ?: ""), tissueText, key]
            return
        }
        // createDirectories yinelenebilir ve iş parçacığı güvenlidir. Eski "kümeye ekle, sonra mkdirs" kalıbında ikinci
        // iş parçacığı klasör daha oluşmadan yazmaya geçebiliyor ve o karo "yol bulunamadı" hatasıyla kayboluyordu.
        if (!madeDirs.contains(imgDir.getAbsolutePath())) { java.nio.file.Files.createDirectories(imgDir.toPath()); madeDirs.add(imgDir.getAbsolutePath()) }
        if (labDir != null && !madeDirs.contains(labDir.getAbsolutePath())) { java.nio.file.Files.createDirectories(labDir.toPath()); madeDirs.add(labDir.getAbsolutePath()) }
        if (imgFormat == 'jpg') img = toRgb(img)
        def imgFile = new File(imgDir, fileStem + imageExt)
        if (imgFormat == 'jpg') {
            def jw = javax.imageio.ImageIO.getImageWritersByFormatName('jpg').next()
            def jp = jw.getDefaultWriteParam()
            jp.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT)
            jp.setCompressionQuality((float) (jpegQuality / 100.0d))
            def ios = javax.imageio.ImageIO.createImageOutputStream(imgFile)
            try { jw.setOutput(ios); jw.write(null, new javax.imageio.IIOImage(img, null, null), jp) }
            finally { try { ios.close() } catch (Throwable ignore) {}; jw.dispose() }
        } else if (!javax.imageio.ImageIO.write(img, imgFormat, imgFile)) throw new java.io.IOException("yazıcı bulunamadı: " + imgFormat)
        imageCounter.incrementAndGet()
        String rel = outDir.toPath().relativize(imgFile.toPath()).toString().replace('\\', '/')
        String relMask = ''
        if (maskMode) {
            def mreq = RegionRequest.createInstance(labelPath, downsample, x, y, tileL0, tileL0, planeZ, planeT)
            def mimg = fitTo(labelServer.readRegion(mreq), true)
            def maskFile = new File(labDir, fileStem + ".png")
            if (!javax.imageio.ImageIO.write(mimg, "png", maskFile)) throw new java.io.IOException("maske yazılamadı")
            maskCounter.incrementAndGet()
            relMask = outDir.toPath().relativize(maskFile.toPath()).toString().replace('\\', '/')
        }
        manifestRows << [lab, x, y, String.format(java.util.Locale.US, "%.4f", cover), rel, relMask,
                         (gi.split != null ? gi.split : ""), gi.source, gi.classes.join("; "), gi.names.join("; "), (patientId ?: ""), tissueText, key]
    } catch (Throwable t) {
        errors << (fileStem + " → " + t.getClass().getSimpleName() + ": " + t.getMessage())
    }
}

def t0 = System.currentTimeMillis()
int nThreads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1))
def pool = java.util.concurrent.Executors.newFixedThreadPool(nThreads)

// Ön seçim (doku süzgeci + etiket başına en çok N birlikte): her grupta karolar sabit tohumla karıştırılır,
// sırayla doku oranı ölçülür ve süzgeci geçen ilk N karo alınır. Ölçüm paralel, kabul sırası karıştırma
// sırasıdır; bu yüzden sonuç iş parçacığı sayısından bağımsız ve yinelenebilirdir. Hiçbir şey yazılmaz.
if (capAfterTissue) {
    def kept = []
    try {
        tiles.groupBy { it[0] }.each { key, List list ->
            def shuffled = new ArrayList(list)
            java.util.Collections.shuffle(shuffled, new java.util.Random((long) (server.getMetadata().getName() + '|' + key).hashCode()))
            int taken = 0
            int failed = 0
            int idx = 0
            while (taken < maxPerLabel && idx < shuffled.size()) {
                int want = Math.max(maxPerLabel - taken, nThreads * 4)
                def batch = shuffled.subList(idx, Math.min(shuffled.size(), idx + want))
                def futs = batch.collect { List tile ->
                    pool.submit({
                        try {
                            def req = RegionRequest.createInstance(serverPath, downsample, (int) tile[1], (int) tile[2], tileL0, tileL0, planeZ, planeT)
                            return tissueFraction(fitTo(server.readRegion(req), false))
                        } catch (Throwable t) { return null }
                    } as java.util.concurrent.Callable)
                }
                for (int bi = 0; bi < batch.size(); bi++) {
                    def tf = futs[bi].get()
                    if (taken >= maxPerLabel) continue          // ölçüldü ama sınır doldu: sınırla dışarıda kalanlara sayılır
                    if (tf == null) { failed++; errors << ("ön seçim: x" + batch[bi][1] + " y" + batch[bi][2] + " okunamadı"); continue }
                    if (((double) tf) < minTissue) { failed++; tissueDropped.incrementAndGet(); continue }
                    kept << batch[bi]
                    taken++
                }
                idx += batch.size()
            }
            droppedByCap += list.size() - taken - failed
        }
        tiles = kept
    } catch (Throwable t) {
        // Ön seçim yarıda kalırsa hiçbir karo yazılmaz: eksik bir seçimle devam etmek sayıları yanıltır.
        tiles = []
        errors << ("ön seçim yarıda kaldı, karo yazılmadı: " + t.getClass().getSimpleName() + ": " + t.getMessage())
    }
}

try {
    def futures = tiles.collect { List tile -> pool.submit({ writeOne(tile) } as Runnable) }
    futures.each { it.get() }
} catch (Throwable t) {
    errors << "${t.getClass().getSimpleName()}: ${t.getMessage()}".toString()
} finally {
    pool.shutdown()
}
def elapsed = (System.currentTimeMillis() - t0) / 1000.0

int imageTiles = imageCounter.get()
int maskTiles = maskCounter.get()
int listedTiles = manifestRows.size()
// Etiket başına sayılar: doku süzgecinden sonra gerçekten kalanlar
perLabel.keySet().each { String k -> perLabel.put(k, 0) }
manifestRows.each { r -> perLabel.put((String) r[12], perLabel.get((String) r[12]) + 1) }

// Karo listesi (manifest): her karo için bir satır; x ve y taban piksel
def manifestFile = new File(outDir, "karo_listesi__" + slug + ".tsv")
if (writeManifest) try {
    manifestFile.withWriter('UTF-8') { w ->
        w.writeLine(['lam', 'hasta', 'bolme', 'bolme_kaynagi', 'etiket', 'sinif', 'anotasyon_adlari', 'x', 'y', 'kenar_taban_px', 'karo_px', 'downsample', 'um_px', 'kapsama', 'doku_orani', 'dosya', 'maske'].join('\t'))
        def clean = { Object v -> (v == null ? '' : v.toString().replaceAll('[\\t\\r\\n]', ' ')) }
        // um_px: karonun GERÇEK çözünürlüğü. Taban kenar tam piksele yuvarlandığı için istenen değerden az farklı olabilir.
        String umEff = calibrated ? String.format(java.util.Locale.US, "%.4f", tileL0 * avgPx / (double) tileSize) : ''
        manifestRows.sort { a, b -> (a[6] <=> b[6]) ?: (a[0] <=> b[0]) ?: (a[2] <=> b[2]) ?: (a[1] <=> b[1]) }.each { r ->
            w.writeLine([slug, clean(r[10]), r[6], r[7], r[0], clean(r[8]), clean(r[9]), r[1], r[2], tileL0, tileSize, dsLabel,
                         umEff, r[3], r[11], r[4], r[5]].join('\t'))
        }
    }
} catch (Throwable t) {
    errors << ("karo listesi yazılamadı: " + t.getMessage())
}
// labels.txt (sınıf → indeks) karolardan önce, "Maske indeksleri" adımında yazıldı.

// ── Küme özeti: bu çıktı kümesindeki TÜM karo listeleri (bu lam dahil) üzerinden sınıf dengesi ──
// Yalnız sayar ve raporlar: karo silmez, çoğaltmaz, bölme atamaz. Kaynak karo listeleridir (klasör içeriği değil).
// Satır: [lam, hasta (karşılaştırma biçimi), bölme, etiket]
def setRows = []
def setSkipped = []
manifestRows.each { r -> setRows << [slug, (patientKey ?: ''),(r[6] != null ? r[6].toString() : ''), r[0].toString()] }
(outDir.listFiles() ?: new File[0]).findAll {
    it.isFile() && it.getName().startsWith("karo_listesi__") && it.getName().endsWith(".tsv") && it.getName() != manifestFile.getName()
}.sort { it.getName() }.each { lf ->
    try {
        def lines = lf.readLines("UTF-8")
        if (lines.size() < 2) return
        def head = lines[0].split("\t") as List
        int cl = head.indexOf("lam"), ch = head.indexOf("hasta"), cb = head.indexOf("bolme"), ce = head.indexOf("etiket")
        if (cl < 0 || ce < 0) { setSkipped << lf.getName(); return }
        lines.drop(1).findAll { !it.trim().isEmpty() }.each { ln ->
            def c = ln.split("\t", -1)
            if (c.length <= ce || c.length <= cl) return
            setRows << [c[cl].trim(), ((ch >= 0 && c.length > ch) ? (normPatient(c[ch]) ?: '') : ''), ((cb >= 0 && c.length > cb) ? c[cb].trim() : ''), c[ce].trim()]
        }
    } catch (Throwable t) { setSkipped << lf.getName() }
}
String setSummaryBlock = ""
def setSummaryFile = new File(outDir, "kume_ozeti.tsv")
if (!setRows.isEmpty()) {
    def setLabels = new java.util.TreeSet<String>(setRows.collect { (String) it[3] })
    def setSplits = new java.util.TreeSet<String>(setRows.collect { (String) it[2] })
    def setSlides = new java.util.TreeSet<String>(setRows.collect { (String) it[0] })
    def setPatients = new java.util.TreeSet<String>(setRows.collect { (String) it[1] }.findAll { it })
    int rowsNoPatient = setRows.count { !it[1] } as int
    def splitTitle = { String s -> s ? s : "(bölmesiz)" }
    def sl = []
    sl << String.format(java.util.Locale.US, "  %,d karo · %d lam · %d hasta%s", setRows.size(), setSlides.size(), setPatients.size(),
                        (rowsNoPatient > 0 ? String.format(java.util.Locale.US, " (%,d karonun hasta kimliği yok)", rowsNoPatient) : ""))
    sl << ("  " + String.format(java.util.Locale.US, "%-22s", "Etiket") + setSplits.collect { String.format(java.util.Locale.US, "%12s", splitTitle(it)) }.join("") +
           String.format(java.util.Locale.US, "%12s%7s%7s%20s", "Toplam", "Lam", "Hasta", "En büyük hasta payı"))
    def tsv = [['etiket', 'bolme', 'karo', 'lam', 'hasta', 'en_buyuk_hasta_payi'].join('\t')]
    def warns = []
    def totals = new java.util.TreeMap<String, Integer>()
    setLabels.each { String lab ->
        def rowsL = setRows.findAll { it[3] == lab }
        totals.put(lab, rowsL.size())
        def withPat = rowsL.findAll { it[1] }
        def byPat = withPat.countBy { it[1] }
        double topShare = byPat.isEmpty() ? Double.NaN : (byPat.values().max() / (double) withPat.size())
        String shareText = Double.isNaN(topShare) ? "—" : String.format(java.util.Locale.US, "%% %.0f", topShare * 100.0d)
        sl << ("  " + String.format(java.util.Locale.US, "%-22s", (lab.length() > 22 ? lab.substring(0, 21) + "…" : lab)) +
               setSplits.collect { String sp -> String.format(java.util.Locale.US, "%,12d", rowsL.count { it[2] == sp }) }.join("") +
               String.format(java.util.Locale.US, "%,12d%7d%7d%20s", rowsL.size(), rowsL.collect { it[0] }.unique().size(), byPat.size(), shareText))
        (setSplits + ['*']).each { String sp ->
            def rr = (sp == '*') ? rowsL : rowsL.findAll { it[2] == sp }
            def wp = rr.findAll { it[1] }
            def bp = wp.countBy { it[1] }
            tsv << [lab, (sp == '*' ? 'tumu' : sp), rr.size(), rr.collect { it[0] }.unique().size(), bp.size(),
                    (bp.isEmpty() ? '' : String.format(java.util.Locale.US, "%.4f", bp.values().max() / (double) wp.size()))].join('\t')
        }
        if (setSplits.size() > 1) {
            def missing = setSplits.findAll { String sp -> rowsL.count { it[2] == sp } == 0 }
            if (!missing.isEmpty()) warns << ("  • ⚠️ \"" + lab + "\" şu bölmelerde hiç yok: " + missing.collect { splitTitle(it) }.join(", ") + ".")
        }
        if (setPatients.size() >= 3 && !Double.isNaN(topShare) && topShare > 0.5d) {
            warns << String.format(java.util.Locale.US, "  • ⚠️ \"%s\" karolarının %% %.0f kadarı tek hastadan geliyor (%d hastadan).", lab, topShare * 100.0d, byPat.size())
        }
    }
    if (totals.size() > 1) {
        int mx = totals.values().max()
        int mn = totals.values().min()
        sl << (mn > 0 ? String.format(java.util.Locale.US, "  En büyük / en küçük etiket: %.1f kat (%,d / %,d karo)", mx / (double) mn, mx, mn) : "  En küçük etiketin karosu yok.")
    }
    if (setPatients.size() > 0 && setPatients.size() < 3) warns << "  • Kümede 3'ten az hasta var: hasta payı uyarısı üretilmedi."
    if (!setSkipped.isEmpty()) warns << ("  • ⚠️ Okunamayan karo listesi özete girmedi: " + setSkipped.join(", "))
    if (maskMode) warns << "  • Maske modunda sayım karo etiketine göredir; maskelerin içindeki sınıf piksel payı ölçülmez."
    warns << "  • Özet yalnız sayar: karo silmez, çoğaltmaz. Dengelemeyi eğitimde (örnekleme / ağırlık) yapın; doğrulama ve test bölmelerini dengelemeyin."
    try { setSummaryFile.write(tsv.join('\n') + '\n', 'UTF-8') }
    catch (Throwable t) { errors << ("küme özeti yazılamadı: " + t.getMessage()) }
    setSummaryBlock = "\n🧮 Küme özeti (bu kümedeki tüm karo listeleri · " + setSummaryFile.getName() + ")\n──────────────\n" + sl.join("\n") + "\n" + warns.join("\n") + "\n"
}

// Önizleme: lamın küçük görüntüsü üzerinde, listeye giren karoların kutuları (etikete göre renkli)
def previewFile = new File(outDir, "onizleme__" + slug + ".jpg")
boolean previewWritten = false
if (writePreview && !manifestRows.isEmpty()) {
    try {
        double pds = Math.max(1.0d, Math.max(imgW, imgH) / 1800.0d)
        def thumb = toRgb(server.readRegion(RegionRequest.createInstance(serverPath, pds, 0, 0, imgW, imgH, planeZ, planeT)))
        def PALETTE = [0xA32D2D, 0x0F6E56, 0x185FA5, 0x854F0B, 0x6B4FA0, 0x1F8A8A, 0xB0562A, 0x4A4845]
        def labelsSorted = new ArrayList(new java.util.TreeSet(manifestRows.collect { it[0].toString() }))
        def g2 = thumb.createGraphics()
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        manifestRows.each { r ->
            int ci = labelsSorted.indexOf(r[0].toString()) % PALETTE.size()
            def base = new java.awt.Color((int) PALETTE[ci])
            int rx = (int) Math.round(((int) r[1]) / pds)
            int ry = (int) Math.round(((int) r[2]) / pds)
            int rs = (int) Math.max(2L, Math.round(tileL0 / pds))
            g2.setColor(new java.awt.Color(base.getRed(), base.getGreen(), base.getBlue(), 70)); g2.fillRect(rx, ry, rs, rs)
            g2.setColor(base); g2.drawRect(rx, ry, rs, rs)
        }
        g2.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 20))
        int ly = 14
        labelsSorted.eachWithIndex { String lab, int i ->
            def base = new java.awt.Color((int) PALETTE[i % PALETTE.size()])
            long n = manifestRows.count { it[0].toString() == lab }
            String text = lab + "  " + n
            int tw = g2.getFontMetrics().stringWidth(text)
            g2.setColor(new java.awt.Color(255, 255, 255, 225)); g2.fillRect(10, ly - 2, tw + 44, 28)
            g2.setColor(base); g2.fillRect(16, ly + 3, 18, 18)
            g2.setColor(java.awt.Color.BLACK); g2.drawString(text, 42, ly + 20)
            ly += 32
        }
        g2.dispose()
        previewWritten = javax.imageio.ImageIO.write(thumb, 'jpg', previewFile)
    } catch (Throwable t) {
        errors << ("önizleme yazılamadı: " + t.getClass().getSimpleName() + ": " + t.getMessage())
    }
}

// ──────────────────────────────────────────────────────────────
// 10) Sonuç
// ──────────────────────────────────────────────────────────────
def labelBlock = perLabel.collect { k, v ->
    String.format(java.util.Locale.US, "  %-34s %,7d karo   (%d anotasyon)", keyTitle(k), v, groupCount.get(k))
}.join("\n")
def legendBlock = maskMode ? ("\n🏷️  Sınıf → indeks (labels.txt)\n──────────────────\n" + legend.collect { "  ${it}" }.join("\n") + "\n") : ""
def errBlock = errors.isEmpty() ? "" :
    ("\n⚠️  Bazı karolar yazılamadı:\n" + errors.take(8).collect { "  • ${it}" }.join("\n") +
     (errors.size() > 8 ? "\n  ... ve ${errors.size() - 8} hata daha" : "") + "\n")
def emptyHint = (listedTiles == 0 && errors.isEmpty()) ?
    "\n💡 0 karo yazıldı. Olası nedenler:\n" +
    "  • Anotasyonlar seçilen karo boyutu / çözünürlük için çok küçük ya da en az kapsama / en az doku eşiği çok yüksek.\n" +
    "  • Karo boyutunu küçültün, µm/px değerini düşürün ya da kapsama eşiğini azaltın.\n" : ""
def notes = []
if (ignoredAsLabel > 0) notes << ("  • " + ignoredAsLabel + " Ignore* anotasyonu karo üretmedi" + (subtractIgnore ? " ve diğer bölgelerden çıkarıldı." : "."))
if (subtractChildren) notes << "  • Alt anotasyonlar üst anotasyondan çıkarıldı."
if (skippedByClass > 0) notes << ("  • " + skippedByClass + " anotasyon, etiketi işaretli olmadığı için atlandı.")
if (droppedByCap > 0) notes << ("  • Etiket başına en çok " + maxPerLabel + " karo: " + droppedByCap + " karo rastgele seçimle dışarıda kaldı" +
                                (capAfterTissue ? " (sınır doku süzgecinden sonra uygulandı; atlanan doku sayısı yalnız incelenen karoları kapsar)." : "."))
else if (maxPerLabel > 0) notes << ("  • Etiket başına en çok " + maxPerLabel + " karo: hiçbir etiket sınırı aşmadı. Sınır bu lam içindir, küme genelinde değil.")
if (!writeManifest) notes << "  • Karo listesi yazılmadı (seçiminizle)."
if (minTissue > 0.0d) notes << String.format(java.util.Locale.US, "  • En az doku %%%.0f (beyazlık eşiği %d): %,d karo yeterli doku içermediği için atlandı.", minTissue * 100.0d, whiteThreshold, tissueDropped.get())
if (coordsOnly) notes << ("  • Yalnız karo listesi: " + listedTiles + " karonun koordinatı yazıldı, görüntü dosyası yazılmadı.")
if (previewWritten) notes << ("  • Önizleme: " + previewFile.getName() + " (karo kutuları etikete göre renkli).")
if (sharedTiles > 0) notes << ("  • " + sharedTiles + " sınır karosu birden çok etiket bölgesine değiyordu; her biri yalnız kapsaması en büyük etikete yazıldı.")
if (overlap > 0) notes << "  • Örtüşme > 0: komşu karolar ortak piksel taşır; karo düzeyinde rastgele bölme sızıntıya yol açar."
if (useSplit) {
    def src = new java.util.TreeSet(groupInfo.values().collect { it.source }.findAll { it })
    notes << ("  • Bölme üst veriden okundu (" + src.join(" + ") + "): " + splitsHere.join(", ") + ". Betik bölme atamaz.")
    notes << ("  • Sızıntı denetimi: bu lam tek bölmede (" + slideSplit + "); aynı kümede başka bölmede karosu yok.")
    notes << ("  • Hasta " + patientId + ": projedeki diğer lamları (proje girdisi + kayıtlı anotasyonlar) ve bu kümedeki karo listeleri denetlendi; başka bölmede yok.")
} else {
    notes << "  • Bölme üst verisi bulunamadı (anahtar: split / bolme / set / fold): klasörlerde bölme düzeyi yok.\n    Bölmeyi sonradan yaparken karo düzeyinde değil, lam / hasta düzeyinde yapın."
}
if (oldTilesNote != null) notes << oldTilesNote
def noteBlock = notes.isEmpty() ? "" : ("\nℹ️  Notlar\n" + notes.join("\n") + "\n")

showResultWindow(
    "Karo dışa aktarma — Tamamlandı 🧩",
    String.format(java.util.Locale.US,
        "Mod      : %s\n" +
        "Kapsam   : %s (%d anotasyon)\n" +
        "Çıktı klasörü:\n  %s\n" +
        "Düzen    : %s\n" +
        "Karo listesi: %s\n\n" +
        "⚙️  Ayarlar\n" +
        "──────────\n" +
        "  Karo boyutu : %d px  (tabanda %d px)\n" +
        "  Çözünürlük  : %.3f µm/px → downsample %.3f%s\n" +
        "  Örtüşme     : %d px\n" +
        "  En az kapsama: %.0f %%\n" +
        "  Format      : %s%s\n\n" +
        "📊 Etiket başına\n" +
        "──────────────\n" +
        "%s\n\n" +
        "  Görüntü karosu : %,d\n" +
        "  Maske karosu   : %,d\n" +
        "  Süre           : %.1f sn\n" +
        "%s%s%s%s\n" +
        "📝 Sonraki adım:\n" +
        "  • Karoları derin öğrenme / harici AI araçlarına verin (<Etiket>/<dosya> düzeni).\n" +
        "  • Veri bölmeyi karo listesindeki lam / hasta düzeyinde yapın, karo düzeyinde değil.\n" +
        "  • Yöntem ve ML'e devir notları: Ek — Karo (tile) dışa aktarma ve Veri dışa aktarma modülü.\n\n" +
        "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.",
        (maskMode ? "Görüntü + etiket maskesi" : "Sadece görüntü"),
        (selectedScope ? "Seçili anotasyon(lar)" : "Tüm anotasyonlar"), inScope.size(),
        outDir.getAbsolutePath(),
        ((useSplit ? "<Bölme>/" : "") +
            (layoutMode == 'slide-label' ? (slug + "/<Etiket>/") : (layoutMode == 'label' ? "<Etiket>/" : ("<Etiket>/" + slug + "/"))) +
            (maskMode ? "images/" : "") + slug + ((useSplit && splitInName) ? "__<Bölme>" : "") + (labelInName ? "__<Etiket>" : "") +
            "__ds" + dsLabel + "_x<X>_y<Y>" + imageExt),
        (writeManifest ? manifestFile.getName() : "yazılmadı"),
        tileSize, tileL0,
        umPerPx, downsample, (calibrated ? "" : " (kalibre değil)"),
        overlap,
        minCover * 100.0d,
        (maskMode ? "PNG (maske için zorunlu)" : imageExt.substring(1).toUpperCase(java.util.Locale.ROOT)),
        legendBlock,
        labelBlock,
        imageTiles, maskTiles, elapsed,
        setSummaryBlock, errBlock, emptyHint, noteBlock
    )
)

println String.format(java.util.Locale.US, "Tamamlandı: %,d görüntü + %,d maske karosu, %.1f sn", imageTiles, maskTiles, elapsed)
if (!errors.isEmpty()) println "⚠️  ${errors.size()} hata (detay sonuç penceresinde)"
println "Çıktı: ${outDir.getAbsolutePath()}"
println "─────────────────────────────────────"
