/**
 * Yardımcı - Warpy hizalama sihirbazı (esnek / non-rigid; Fiji köprüsü)
 * -----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Warpy (BIOP) ile hesaplanmış ESNEK (non-rigid) bir hizalama dönüşümünü
 *   kullanarak, bir KAYNAK lamdaki üst düzey anotasyonları — VE içlerindeki
 *   yuvalanmış tespitleri/hücreleri (alt nesneler özyinelemeli) — şu anki (HEDEF)
 *   lama TEK TIKLA aktarır. Yalnız "anotasyon" değil: bir anotasyonun içinde
 *   hücre tespitleri varsa onlar da (ad/ID/ölçümleriyle) taşınır. Afin (§ Hizalama
 *   aktarımı) yetmeyen, kesitler arası kırışma/gerilme olan durumlar içindir
 *   (hücre düzeyi doğruluk).
 *
 *   İsteğe bağlı — aktarım sonrası "yoğunluk ölçümü ekle": aktarılan nesnelere
 *   HEDEF lamda o boyanın (İHK) sinyali ölçülür (Warpy, QuPath çekirdeği
 *   ObjectMeasurements'ı sarmalar; parlak-alan boya ayrıştırmasını kendisi yapar;
 *   downsample seçilebilir). ⚠️ Seri kesitte AYNI hücre iki lamda olmadığından
 *   HÜCRE düzeyi yeniden-ölçüm bir VEKİLDİR — bölge/anotasyon düzeyi ölçüm daha güvenlidir.
 *
 * ÖNEMLİ — HİZALAMA (registration) FIJI'DE YAPILIR, QuPath'te DEĞİL:
 *   Warpy'nin esnek dönüşümü Fiji'de hesaplanır (BigDataViewer-Playground +
 *   elastix + isteğe bağlı BigWarp). Bu sihirbaz QuPath tarafını yapar:
 *     (a) ön koşulları denetler (Warpy eklentisi kurulu mu, Fiji yolu ayarlı mı),
 *     (b) Fiji'yi başlatır ve adımları gösterir,
 *     (c) Fiji hizalaması projeye yazdıktan SONRA anotasyonları aktarır.
 *   BigWarp'ın elle işaret arayüzü Fiji'ye özgüdür; QuPath sihirbazı içine
 *   gömülemez — bu yüzden akış Fiji'ye köprülenir.
 *
 * FIJI İŞ AKIŞI (sihirbazda da özetlenir):
 *   1. Fiji güncelleme siteleri: PTBIOP açık olmalı; elastix "wrapper"ları
 *      [Plugins → BIOP → Set and Check Wrappers] ile ayarlanmalı.
 *   2. [Plugins → BigDataViewer-Playground → BDVDataset → Create BDV Dataset (QuPath)]
 *      ile project.qpproj'u açın (birim: milimetre).
 *   3. [Plugins → BigDataViewer-Playground → Sources → Register → QuPath - Create Warpy Registration]:
 *      kaba rigid → elastix afin (ROI) → elastix spline → (ops.) BigWarp düzeltme →
 *      dönüşümü QuPath projesine yazar (transform_<hedef>_<kaynak>.json).
 *   4. QuPath'e dönüp HEDEF (sabit) lamı açın; bu sihirbazla aktarın.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Yalnız nesneleri TAŞIR (koordinat dönüşümü). Hizalama kalitesini,
 *     patoloji yorumunu veya klinik yeterliliği üretmez. Örtüşmeyi her zaman
 *     opaklık kaydırıcısıyla GÖZLE doğrulayın.
 *
 * ÇIKTI:
 *   • Kaynaktan dönüştürülmüş, KİLİTLİ nesneler (ad: "↪ Warpy aktarımı")
 *   • Aktarılan nesne sayısını gösteren özet
 *
 * KAYNAKLAR:
 *   Warpy (BIOP, Apache-2.0): https://imaging.epfl.ch/qupath-extension-warpy/
 *   Chiaruttini ve ark. 2022. bkz. Ekler → Görüntü Hizalama § Warpy.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.
 */

import qupath.fx.dialogs.Dialogs
import qupath.fx.dialogs.FileChoosers
import qupath.lib.scripting.QP
import org.codehaus.groovy.runtime.InvokerHelper

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

String SENTINEL = '↪ Warpy aktarımı'
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
// Bu sihirbazın seti mi? Yalnız TAM ad ya da "ad (N)" — önek eşleşmesi YOK:
// kullanıcının kendi adlandırdığı bir anotasyon süpürülmez.
def isOwnSetName = { String name, String base ->
    if (name == null) return false
    if (name == base) return true
    return name.startsWith(base + ' (') && name.endsWith(')') &&
        name.substring(base.length() + 2, name.length() - 1).isInteger() &&
        (name.substring(base.length() + 2, name.length() - 1) as int) >= 2
}
def ownSetIndex = { String name, String base ->
    return (name == base) ? 1 : (name.substring(base.length() + 2, name.length() - 1) as int)
}
def prevOwnObjects = { hierarchy, String base ->
    if (hierarchy == null) return []
    return hierarchy.getAnnotationObjects().findAll { isOwnSetName(it.getName(), base) }
}
def nextOwnSetName = { hierarchy, String base ->
    def used = prevOwnObjects(hierarchy, base).collect { ownSetIndex(it.getName(), base) }
    if (used.isEmpty()) return base
    return base + ' (' + ((used.max() as int) + 1) + ')'
}
// Aktarılan her nesne (iç içe olanlar dahil) işaretlenir; "Değiştir" yalnız işaretli
// olanları siler. Kullanıcının sonradan içine eklediği (işaretsiz) nesneler KORUNUR.
def TRANSFER_TAG = 'atolye_aktarim'
def tagTransferTree = { roots, String tag ->
    def stack = new ArrayList(roots ?: [])
    while (!stack.isEmpty()) {
        def o = stack.remove(stack.size() - 1)
        try { o.getMetadata().put('atolye_aktarim', tag) } catch (Throwable ignore) {}
        try { stack.addAll(o.getChildObjectsAsArray() as List) } catch (Throwable ignore) {}
    }
}
def ownTransferTree = { prev ->
    def out = new LinkedHashSet(prev ?: [])
    def stack = new ArrayList(prev ?: [])
    while (!stack.isEmpty()) {
        def o = stack.remove(stack.size() - 1)
        def kids = []
        try { kids = (o.getChildObjectsAsArray() as List) } catch (Throwable ignore) {}
        kids.each { c ->
            boolean tagged = false
            try { tagged = c.getMetadata()?.containsKey('atolye_aktarim') } catch (Throwable ignore) {}
            if (tagged) out << c
            stack << c
        }
    }
    return new ArrayList(out)
}
// Önceki aktarım için kullanıcıya SOR (FX iş parçacığında, ağır işten ÖNCE çağrılır).
//   Dönüş: 'add' | 'replace' | null (İptal → hiçbir şey çalışmaz, hiçbir şey yazılmaz).
//   Önceki sonuç yoksa sormadan 'add' döner.
def askExisting = { ->
    def prev = prevOwnObjects(QP.getCurrentImageData()?.getHierarchy(), SENTINEL)
    if (prev.isEmpty()) return MODE_ADD
    String optAdd = workshopText('script.5fcbb9588b11', 'Öncekine dokunma — yeni bir set olarak ekle (varsayılan)')
    String optRep = workshopText('script.b7d79be32c1c', 'Öncekini değiştir (bu sihirbazın aktardıkları silinir)')
    def pick = Dialogs.showChoiceDialog(workshopText('script.aab9acb25376', 'Önceki Warpy aktarımı'),
        String.format(java.util.Locale.US, workshopText('script.3cad69a43512', 'Bu lamda bu sihirbazın önceki aktarımından %,d anotasyon var ("%s").\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni aktarım ayrı bir set olur ("%s (2)" gibi). Lamda iki set birlikte durur.\n\n"Değiştir" seçilirse önceki aktarımın anotasyonları silinir — onlarda elle yaptığınız düzeltmeler de gider. Onlarla birlikte aktarılmış nesneler (ör. hücre tespitleri) de silinir; içlerine sonradan kendi eklediğiniz nesneler lamda kalır. Bu sürümden önce yapılmış aktarımların içindeki nesneler işaretli olmadığından lamda kalır; gerekirse elle silin. Kendi çizdiğiniz anotasyonlara dokunulmaz.\n\nİptal: hiçbir şey çalıştırılmaz, hiçbir şey yazılmaz.\n\nÖnceki aktarım ne olsun?'), prev.size(), SENTINEL, SENTINEL),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.72b2fc13ba69', 'Önceki Warpy aktarımı silinecek'),
            String.format(java.util.Locale.US, workshopText('script.793ec45d606c', '"%2$s" adlı %1$,d anotasyon silinecek ve yerine yenisi aktarılacak. Bu anotasyonlarda elle yaptığınız düzeltmeler KAYBOLUR; onlarla birlikte aktarılmış nesneler de silinir. İçlerine sonradan kendi eklediğiniz nesneler kalır.\n\nDevam edilsin mi?'), prev.size(), SENTINEL))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/warpy')
String PREF_FIJI = 'fijiPath'

// Warpy, BIOP kataloğundan kurulur (elle .jar yerine önerilir; sürümünüzle eşleşen derlemeyi verir).
String BIOP_CATALOG_URL = 'https://github.com/BIOP/qupath-biop-catalog'
// Fiji (= "Fiji Is Just ImageJ") ayrı bir uygulamadır; buradan indirilir (hizalama Fiji'de yapılır).
String FIJI_DOWNLOAD_URL = 'https://fiji.sc'
// elastix: otomatik afin+spline motoru; Fiji EKLENTİSİ DEĞİL, harici çalıştırılabilir (SuperElastix).
String ELASTIX_URL = 'https://github.com/SuperElastix/elastix/releases'
def openUrl = { String url ->
    new Thread({
        boolean opened = false
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                def dt = java.awt.Desktop.getDesktop()
                if (dt.isSupported(java.awt.Desktop.Action.BROWSE)) { dt.browse(new java.net.URI(url)); opened = true }
            }
        } catch (Throwable ignore) {}
        if (!opened) javafx.application.Platform.runLater {
            try {
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def cc = new javafx.scene.input.ClipboardContent(); cc.putString(url); cb.setContent(cc)
                Dialogs.showMessageDialog(workshopText('script.072d21314064', 'Bağlantı panoya kopyalandı'), workshopText('script.4fdd757ec6b9', 'Tarayıcı otomatik açılamadı. Bağlantı panoya kopyalandı:\n') + url)
            } catch (Throwable t) { Dialogs.showMessageDialog(workshopText('script.7916c8f0474b', 'Bağlantı'), url) }
        }
    }, 'OpenUrl').start()
}
def showWarpyInstall = {
    def steps = workshopText('script.b89b0d40335a', 'Warpy (qupath-extension-warpy, BIOP) KURULUMU — BIOP KATALOĞUNDAN önerilir:\n\n1. Extensions → Manage extensions → Manage extension catalogs → Add.\n2. Katalog URL\'sini yapıştırın:\n     ') + BIOP_CATALOG_URL + '\n' +
        workshopText('script.12e771e3b104', '   (aşağıdaki düğme bu adresi açar/panoya kopyalar.)\n3. Katalogdan Warpy\'yi kurun — QuPath sürümünüzle EŞLEŞEN derlemeyi verir (elle .jar\'dan güvenli;\n   yanlış sürüm elle .jar yüklenmeyebilir).\n4. QuPath\'i YENİDEN BAŞLATIN.\n\nAYRICA Fiji gerekir (AYRI uygulama; hizalama Fiji\'de yapılır):\n  • Fiji\'yi ') + FIJI_DOWNLOAD_URL + workshopText('script.b8b1bb2d315d', ' adresinden indirin ("Fiji indir" düğmesi), arşivi açın.\n  • Fiji\'de PTBIOP güncelleme sitesini açın + elastix wrapper\'larını ayarlayın\n    (Plugins → BIOP → Set and Check Wrappers). BigWarp Fiji çekirdeğinde gelir.\n\nBIOP katalog sayfasını şimdi açayım mı?')
    if (Dialogs.showConfirmDialog(workshopText('script.a374f8165f51', 'Warpy nasıl kurulur? (BIOP kataloğu)'), steps)) openUrl(BIOP_CATALOG_URL)
}
def showFijiPlugins = {
    def s = workshopText('script.a8d028214f86', 'FIJI EKLENTİLERİ (Warpy hizalaması için) — Fiji açıkken:\n\n1) PTBIOP güncelleme sitesi (BigDataViewer-Playground + Warpy komutları):\n   Help → Update… → (kontrol bitince) Manage update sites → listeden PTBIOP\'u İŞARETLE →\n   Close → Apply changes → Fiji\'yi YENİDEN BAŞLAT.\n   ⚠ İhtiyacınız olan menü "BigDataViewer-Playground"dur (tireli) — DÜZ "BigDataViewer" DEĞİL.\n   PTBIOP açıkken de "BigDataViewer-Playground" görünmüyorsa: Apply changes + yeniden başlat;\n   hâlâ yoksa PTBIOP\'u kapatıp-açıp yeniden Apply edin (temiz indirme) ve Update penceresindeki hataları kontrol edin.\n   JAR kurulu (jars/bigdataviewer-playground-*.jar VAR) AMA menü yoksa: Fiji\'nizde JAR ÇAKIŞMASI vardır\n   (deneysel "SciJava-Ops"/scijava3 -SNAPSHOT jar\'ları klasik SciJava ile çakışır → menü kaydı düşer).\n   Bunlar genelde "Fiji-Latest" (nightly, sites.imagej.net/Fiji) güncelleme sitesinden gelir:\n   Manage Update Sites → "Fiji-Latest"i KAPAT (ImageJ/Fiji/PTBIOP/ElastixWrapper açık kalsın) → Apply → yeniden başlat.\n   Window → Console başlangıç hatasını gösterir. En güvenlisi: YALNIZ PTBIOP açık TEMİZ bir Fiji (fiji.sc).\n\n2) elastix (otomatik afin + spline; Fiji EKLENTİSİ DEĞİL, harici çalıştırılabilir):\n   • İndir (test edilen sürüm 5.0.1): ') + ELASTIX_URL + workshopText('script.e3641b6e9da3', ' → Windows zip → bir klasöre açın.\n   • Windows\'ta çalışmazsa "Visual C++ redistributable" kurun.\n   • Fiji → Plugins → BIOP → Set and Check Wrappers → elastix.exe ve transformix.exe yollarını verin.\n\n3) BigWarp: Fiji çekirdeğinde gelir (kurulum gerekmez).\n\nSonra Fiji\'yi yeniden başlatın; Warpy komutu Plugins → BigDataViewer-Playground → Sources → Register\naltında görünür. elastix sürüm sayfasını şimdi açayım mı?')
    if (Dialogs.showConfirmDialog(workshopText('script.d38ca9de9c03', 'Fiji eklentileri nasıl kurulur? (PTBIOP + elastix)'), s)) openUrl(ELASTIX_URL)
}

// ── İsteğe bağlı bağımlılık: Warpy sınıfı yalnız BIOP eklentisi kuruluysa vardır.
//    SERT import KULLANMA (kurulu değilse betik derlenemez) → Class.forName + InvokerHelper.
def warpyClass = { ->
    try { return Class.forName('qupath.ext.warpy.Warpy') }
    catch (Throwable t) { return null }
}
def callStatic = { Class c, String name, List args ->
    InvokerHelper.invokeStaticMethod(c, name, args.toArray())
}

// ── Ön kontroller ───────────────────────────────────────────────────
def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage(workshopText('script.a38d0e6adac0', "Proje açık değil"), workshopText('script.94d00a2c421b', "Kaynak ve hedef lam AYNI QuPath projesinde olmalıdır."))
    return
}
if (isHeadless) {
    println "Bu sihirbaz QuPath arayüzü gerektirir (ön koşul denetimi + Fiji köprüsü + kaynak seçimi)."
    return
}

// ── Warpy aktarım işi (arka planda; kaynak hiyerarşi okuma dosya IO'su yapar) ───
def doTransfer = { Object sourceEntry, boolean addMeas, double downsample, boolean lock, String mode = 'add' ->
    try {
        def W = warpyClass()
        if (W == null) return [ok:false, kind:'noclass', error:workshopText('script.e599520b381f', 'Warpy eklentisi kurulu değil (BIOP kataloğu). Kurup QuPath\'i yeniden başlatın.')]
        def targetEntry = QP.getProjectEntry()
        if (targetEntry == null) return [ok:false, kind:'notarget', error:workshopText('script.a8870befe937', 'Hedef lam bulunamadı; aktarımın HEDEF (sabit) lamını açın.')]

        def transform, objs, transferred
        try {
            transform  = callStatic(W, 'getRealTransform', [sourceEntry, targetEntry])
            objs       = callStatic(W, 'getPathObjectsFromEntry', [sourceEntry])
            transferred = callStatic(W, 'transformPathObjects', [objs, transform])
        } catch (groovy.lang.MissingMethodException | NoSuchMethodException nsme) {
            return [ok:false, kind:'nomethod', error:workshopText('script.13607c6b9909', 'Warpy API imzası beklenenden farklı (sürüm uyumsuzluğu): ') + nsme.getMessage()]
        } catch (Throwable t) {
            def cause = (t.getCause() != null) ? t.getCause() : t
            return [ok:false, kind:'compute', error:workshopText('script.00ddb41ec932', 'Warpy dönüşümü uygulanamadı: ') + cause.getClass().getSimpleName() + ': ' + (cause.getMessage() ?: '')]
        }

        def n = (transferred == null) ? 0 : transferred.size()
        if (n == 0) return [ok:false, kind:'empty', error:workshopText('script.e8824ca3a1c6', 'Kaynak lamda aktarılacak nesne yok.')]

        transferred.each { o -> o.setName(SENTINEL); if (lock) o.setLocked(true) }

        // İsteğe bağlı yoğunluk ölçümü — HATA VERSE BİLE aktarımı kaybetme (nesneler zaten taşındı).
        def measWarn = null
        if (addMeas) {
            try { callStatic(W, 'addIntensityMeasurements', [transferred, downsample]) }
            catch (Throwable t) {
                def cause = (t.getCause() != null) ? t.getCause() : t
                measWarn = cause.getClass().getSimpleName() + ': ' + (cause.getMessage() ?: '')
            }
        }

        // Önceki aktarım: SORULMADAN SİLİNMEZ. Kip kullanıcıdan gelir (varsayılan: ekle).
        //   ekle     → hiçbir şey silinmez; yeni set sıradaki adı alır ("↪ Warpy aktarımı (2)" …).
        //   değiştir → yalnız bu sihirbazın setleri (tam ad ya da "(N)") silinir; onlarla birlikte
        //              aktarılmış (işaretli) nesneler de silinir; sonradan eklenenler KORUNUR. Kullanıcının kendi anotasyonlarına dokunulmaz.
        def prev = prevOwnObjects(QP.getCurrentImageData()?.getHierarchy(), SENTINEL)
        def prevNames = prev.collect { it.getName() }.unique().sort()
        int removed = 0
        String setName
        if (mode == 'replace') {
            removed = prev.size()
            if (removed > 0) QP.removeObjects(ownTransferTree(prev), true)
            setName = SENTINEL
        } else {
            setName = nextOwnSetName(QP.getCurrentImageData()?.getHierarchy(), SENTINEL)
        }
        transferred.each { o -> o.setName(setName) }
        tagTransferTree(transferred, setName)
        QP.addObjects(transferred)
        QP.fireHierarchyUpdate()
        return [ok:true, count:n, measWarn:measWarn, setName:setName, prevNames:(mode == 'replace' ? [] : prevNames), removed:removed]
    } catch (Throwable t) {
        return [ok:false, kind:'other', error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
    }
}

// ── Tek pencere sihirbaz ─────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle(workshopText('script.cad4190acc89', 'Warpy hizalama sihirbazı (esnek; Fiji köprüsü)'))
        stage.setAlwaysOnTop(true)

        // Adım başlığı + açıklama yardımcıları (4 adımlı rehber için).
        def stepHeader = { String t ->
            def l = new javafx.scene.control.Label(t)
            l.setStyle('-fx-font-weight: bold; -fx-font-size: 13px;'); l.setWrapText(true); l.setMaxWidth(Double.MAX_VALUE); return l
        }
        def instr = { String t ->
            def l = new javafx.scene.control.Label(t)
            l.setWrapText(true); l.setMaxWidth(Double.MAX_VALUE); l.setStyle('-fx-opacity: 0.85;'); return l
        }

        // — Ön koşul durumu —
        def W0 = warpyClass()
        def warpyOk = (W0 != null)
        def warpyLbl = new javafx.scene.control.Label(
            warpyOk ? workshopText('script.68b6595f8d13', '✓ Warpy eklentisi kurulu.') : workshopText('script.d92228d4f3c2', '✗ Warpy eklentisi YÜKLENMEDİ — BIOP kataloğundan kurun (aşağıdaki "Warpy nasıl kurulur?").'))
        warpyLbl.setWrapText(true); warpyLbl.setMaxWidth(Double.MAX_VALUE)
        warpyLbl.setStyle(warpyOk ? '-fx-text-fill: -qp-script-info-color;' : '-fx-text-fill: -qp-script-error-color;')
        // Warpy yoksa: BIOP kataloğu kurulum yardımı düğmesi.
        def warpyInstallBtn = new javafx.scene.control.Button(workshopText('script.a374f8165f51', 'Warpy nasıl kurulur? (BIOP kataloğu)'))
        warpyInstallBtn.setOnAction({ showWarpyInstall() })
        def warpyRow = warpyOk ? new javafx.scene.layout.HBox(8, warpyLbl)
                               : new javafx.scene.layout.HBox(8, warpyLbl, warpyInstallBtn)
        warpyRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        javafx.scene.layout.HBox.setHgrow(warpyLbl, javafx.scene.layout.Priority.ALWAYS)

        def fijiField = new javafx.scene.control.TextField(prefs.get(PREF_FIJI, ''))
        fijiField.setPromptText(workshopText('script.022d4ea95aca', 'ör.  C:\\Fiji.app\\ImageJ-win64.exe'))
        fijiField.setMaxWidth(Double.MAX_VALUE)
        def fijiBrowse = new javafx.scene.control.Button('…')
        fijiBrowse.setOnAction({
            def x = FileChoosers.promptForFile(stage, workshopText('script.1c9be3a7bfbb', 'Fiji/ImageJ çalıştırılabilir dosyasını seç'))
            if (x != null) { fijiField.setText(x.getAbsolutePath()); prefs.put(PREF_FIJI, x.getAbsolutePath()) }
        })

        def fijiDownloadBtn = new javafx.scene.control.Button(workshopText('script.7109a74e1e1f', 'Fiji indir'))
        fijiDownloadBtn.setOnAction({ openUrl(FIJI_DOWNLOAD_URL) })
        def launchBtn = new javafx.scene.control.Button(workshopText('script.29c85f0196be', 'Fiji\'yi başlat'))
        launchBtn.setOnAction({
            def p = fijiField.getText()?.trim()
            if (!p || !(new File(p)).isFile()) {
                Dialogs.showErrorMessage(workshopText('script.998d7aea13d1', 'Fiji bulunamadı'),
                    workshopText('script.c1383bf7b943', 'Geçerli bir Fiji/ImageJ çalıştırılabilir dosyası seçin (… düğmesi).\n' +
                    'Fiji kurulu değilse "Fiji indir" ile fiji.sc\'den indirin (Fiji = ayrı uygulama; hizalama orada yapılır),\n' +
                    'arşivi açın ve içindeki çalıştırılabiliri (Windows: ImageJ-win64.exe) seçin.'))
                return
            }
            prefs.put(PREF_FIJI, p)
            try {
                // ProcessBuilder List<String> — önceden tırnaklanmış komut dizesi KULLANMA (boşluklu yol güvenli).
                new ProcessBuilder([p]).start()
                Dialogs.showMessageDialog(workshopText('script.a13eb72116e6', 'Fiji başlatıldı'),
                    workshopText('script.7eb8e9a5075a', 'Fiji açılıyor. ③. adımdaki komutları izleyin (a: Create BDV Dataset (QuPath) → project.qpproj, b: Create Warpy Registration).\n' +
                    'Hizalama bittiğinde QuPath\'e dönün, HEDEF lamı açıp ④. adımdan aktarın.'))
            } catch (Throwable t) {
                Dialogs.showErrorMessage(workshopText('script.b6a5d31b46d7', 'Fiji başlatılamadı'), t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
            }
        })

        // — Kaynak seçimi + aktarım —
        def sourceBox = new javafx.scene.control.ChoiceBox()
        def sourceEntries = [:]   // ad -> entry
        def refreshSources = {
            sourceBox.getItems().clear(); sourceEntries.clear()
            def W = warpyClass()
            if (W == null) return
            try {
                def targetEntry = QP.getProjectEntry()
                if (targetEntry == null) return
                // getCandidateSourceEntries: bu HEDEF için kullanılabilir (ileri/ters) Warpy dönüşümü olan kaynaklar.
                // Dönüşüm dosyasını kendimiz aramayız — varlık denetiminin TEK yolu budur.
                def cands = callStatic(W, 'getCandidateSourceEntries', [targetEntry])
                cands.each { e -> def nm = e.getImageName(); sourceEntries[nm] = e; sourceBox.getItems().add(nm) }
                if (!sourceBox.getItems().isEmpty()) sourceBox.getSelectionModel().selectFirst()
            } catch (Throwable ignore) {}
        }
        refreshSources()
        def refreshBtn = new javafx.scene.control.Button(workshopText('script.91dc2acd438e', '↻ Kaynakları tazele'))
        refreshBtn.setOnAction({ refreshSources()
            if (sourceBox.getItems().isEmpty())
                Dialogs.showMessageDialog(workshopText('script.bb1e7389c5b9', 'Warpy dönüşümü yok'),
                    workshopText('script.ec5584d6ddb7', 'Bu (HEDEF) lam için kayıtlı Warpy dönüşümü bulunamadı. Önce Fiji\'de hizalamayı tamamlayıp bu lamı açın.'))
        })

        def measChk = new javafx.scene.control.CheckBox(workshopText('script.6d56abab1228', 'Aktarım sonrası HEDEF lamda yoğunluk ölçümü ekle — aktarılan nesnelere hedef boyanın (İHK) sinyalini ölçer (yavaş; hata verirse aktarım yine tamamlanır)'))
        measChk.setSelected(false); measChk.setWrapText(true)
        def dsField = new javafx.scene.control.TextField('1'); dsField.setPrefColumnCount(4)
        dsField.disableProperty().bind(measChk.selectedProperty().not())
        def dsRow = new javafx.scene.layout.HBox(6, new javafx.scene.control.Label(workshopText('script.0621eaf4bc3b', '   Ölçüm downsample (px; 1=tam çözünürlük, 2–4 daha hızlı):')), dsField)
        dsRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        def lockChk = new javafx.scene.control.CheckBox(workshopText('script.f9efed6c13a8', 'Aktarılanları kilitle')); lockChk.setSelected(true)

        def status = new javafx.scene.control.Label(warpyOk ? workshopText('script.86bb45e59294', 'Hazır.') : workshopText('script.c6c70a2c72ef', 'Warpy eklentisi gerekli.')); status.setWrapText(true); status.setMaxWidth(Double.MAX_VALUE)
        def aktarBtn = new javafx.scene.control.Button(workshopText('script.3631e657b0d6', 'Anotasyonları aktar')); aktarBtn.setDefaultButton(true)
        def kapatBtn = new javafx.scene.control.Button(workshopText('script.7b31a9fc4816', 'Kapat')); kapatBtn.setOnAction({ stage.close() })

        aktarBtn.setOnAction({
            if (!warpyOk) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText(workshopText('script.ddf88e27c650', 'Warpy eklentisi kurulu değil.')); return }
            if (QP.getCurrentImageData() == null) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText(workshopText('script.39f1e858e936', 'Aktarımın HEDEF lamını açın.')); return }
            def srcName = sourceBox.getValue()
            if (!srcName) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText(workshopText('script.00f5c8cc1719', 'Kaynak yok — önce Fiji\'de hizalamayı tamamlayıp "Kaynakları tazele"ye basın.')); return }
            def srcEntry = sourceEntries[srcName]
            // Önceki aktarım varsa ağır işten ÖNCE sor: İptal'de hiçbir şey çalışmaz, hiçbir şey yazılmaz.
            final String transferMode = askExisting()
            if (transferMode == null) return
            aktarBtn.setDisable(true); status.setStyle(''); status.setText(workshopText('script.e7ae44366dcf', 'Aktarılıyor…'))
            double ds = 1.0d
            try { def _t = (dsField.getText() ?: '1').trim(); if (_t) ds = Math.max(1.0d, Double.parseDouble(_t)) } catch (ignore) {}
            def worker = new Thread({
                def r = doTransfer(srcEntry, measChk.isSelected(), ds, lockChk.isSelected(), transferMode)
                javafx.application.Platform.runLater {
                    aktarBtn.setDisable(false)
                    if (r.ok) {
                        status.setStyle(''); status.setText(String.format(java.util.Locale.US,
                            workshopText('script.8d4f44cf84e8', 'Tamamlandı ✅ — %,d nesne aktarıldı. Örtüşmeyi gözle doğrulayın.'), r.count))
                        def msg = new StringBuilder()
                        msg << String.format(java.util.Locale.US, workshopText('script.b3989cbb3bf6', 'Kaynak: %s%n'), srcName)
                        msg << String.format(java.util.Locale.US, workshopText('script.2380a7791215', 'Aktarılan nesne: %,d (kilitli; ad "%s")%n'), r.count, (r.setName ?: SENTINEL))
                        if (r.prevNames) {
                            def stillMsg = String.format(java.util.Locale.US, workshopText('script.b953a9fa8fe1', 'DİKKAT — bu lamda önceki Warpy aktarımı da duruyor: %1$s. Hiçbir şey silinmedi; yeni set "%2$s" adıyla eklendi. Eski seti kaldırmak için o addaki anotasyonları seçip silin ya da yeniden aktarırken "Öncekini değiştir"i seçin. Dışa aktarım ve ölçümler iki seti de görür.'), r.prevNames.join(', '), r.setName)
                            msg << '\n' << stillMsg << '\n'
                            status.setText(status.getText() + '\n' + stillMsg)
                        } else if ((r.removed ?: 0) > 0) {
                            def replMsg = String.format(java.util.Locale.US, workshopText('script.9bc28ae951f1', 'Önceki Warpy aktarımı değiştirildi: %,d anotasyon silindi ve onlarla aktarılmış nesneler (sonradan eklediğiniz nesneler korundu).'), r.removed)
                            msg << '\n' << replMsg << '\n'
                            status.setText(status.getText() + '\n' + replMsg)
                        }
                        if (r.measWarn) msg << (workshopText('script.b5d6d3a337e2', '\nNot: yoğunluk ölçümü eklenemedi (aktarım tamam): ') + r.measWarn + '\n')
                        msg << workshopText('script.5bb051c91e47', '\nEsnek dönüşüm hücre düzeyinde daha iyidir; yine de örtüşmeyi opaklık\n')
                        msg << workshopText('script.01d07eb3013a', 'kaydırıcısıyla doğrulayın. ⚠️ Yalnızca araştırma/eğitim amaçlıdır.')
                        Dialogs.showMessageDialog(workshopText('script.ef00ffdefd8a', 'Warpy aktarımı tamamlandı'), msg.toString())
                    } else {
                        status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + (r.error ?: workshopText('script.da001998a5bd', 'Bilinmeyen hata')))
                    }
                }
            }, 'WarpyAktarim'); worker.setDaemon(true); worker.start()
        })

        def spacer = new javafx.scene.layout.Region()
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
        def buttons = new javafx.scene.layout.HBox(8, spacer, kapatBtn, aktarBtn)
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

        // ── 4 ADIMLI REHBER ─────────────────────────────────────────────
        // ① Warpy eklentisini kur
        def step1 = new javafx.scene.layout.VBox(6,
            stepHeader(workshopText('script.2ad19a8d5f5b', '① Warpy eklentisini kur (BIOP kataloğu)')),
            warpyRow,
            instr(workshopText('script.0663b6f036e8', 'Warpy esnek (non-rigid) hizalamayı Fiji ile yapar. Eklentiyi BIOP kataloğundan kurun (elle .jar\'dan güvenli), sonra QuPath\'i yeniden başlatın.')))

        // ② Fiji uygulamasını + eklentilerini kur
        def fijiRow = new javafx.scene.layout.HBox(8, new javafx.scene.control.Label(workshopText('script.610d768d402a', 'Fiji yolu:')), fijiField, fijiBrowse, fijiDownloadBtn)
        fijiRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        javafx.scene.layout.HBox.setHgrow(fijiField, javafx.scene.layout.Priority.ALWAYS)
        def fijiPluginsBtn = new javafx.scene.control.Button(workshopText('script.ee7167c3031a', 'Fiji eklentileri (PTBIOP + elastix) nasıl kurulur?'))
        fijiPluginsBtn.setOnAction({ showFijiPlugins() })
        def step2 = new javafx.scene.layout.VBox(6,
            stepHeader(workshopText('script.5410888c6e0d', '② Fiji uygulamasını ve eklentilerini kur')),
            instr(workshopText('script.aa6579253a46', 'Fiji AYRI bir uygulamadır (fiji.sc). "Fiji indir" ile indirip arşivi açın; çalıştırılabilir yolunu "…" ile seçin. Sonra Fiji eklentilerini kurun: PTBIOP güncelleme sitesi + elastix wrapper\'ları (BigWarp Fiji ile gelir).')),
            fijiRow,
            new javafx.scene.layout.HBox(8, fijiPluginsBtn))

        // ③ Fiji'de görüntüleri aç + hizalamayı (registration) çalıştır
        def step3 = new javafx.scene.layout.VBox(6,
            stepHeader(workshopText('script.b76129e15f0d', '③ Fiji\'de projeyi aç ve hizalamayı (registration) çalıştır')),
            new javafx.scene.layout.HBox(8, launchBtn),
            instr(workshopText('script.ec0253d9c853', 'Fiji açıldıktan sonra (menü: BigDataViewer-Playground — TİRELİ; düz "BigDataViewer" DEĞİL. Yoksa ②\'deki PTBIOP\'u kurun):\n  a) Plugins → BigDataViewer-Playground → BDVDataset → Create BDV Dataset (QuPath) → project.qpproj\'u açın (birim: mm).\n  b) Plugins → BigDataViewer-Playground → Sources → Register → QuPath - Create Warpy Registration:\n     rigid → elastix afin → elastix spline → (ops.) BigWarp → dönüşüm QuPath projesine YAZILIR (transform_<hedef>_<kaynak>.json).')))

        // ④ QuPath'e dön → anotasyonları aktar
        def srcRow = new javafx.scene.layout.HBox(8, new javafx.scene.control.Label(workshopText('script.a4aab64f8584', 'Kaynak lam:')), sourceBox, refreshBtn)
        srcRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        javafx.scene.layout.HBox.setHgrow(sourceBox, javafx.scene.layout.Priority.ALWAYS)
        sourceBox.setMaxWidth(Double.MAX_VALUE)
        def srcNote = new javafx.scene.control.Label(workshopText('script.ffe0a5050b9c', 'Kaynak listesi, ③\'teki Fiji hizalaması projeye yazıldıktan SONRA "↻ Kaynakları tazele" ile dolar. HEDEF (sabit) lam AÇIK olmalı; henüz hizalama yoksa liste BOŞTUR.'))
        srcNote.setWrapText(true); srcNote.setMaxWidth(Double.MAX_VALUE); srcNote.setStyle('-fx-opacity: 0.8; -fx-font-size: 11px;')
        def step4 = new javafx.scene.layout.VBox(6,
            stepHeader(workshopText('script.1fbd7084c582', '④ QuPath\'e dön → anotasyonları aktar')),
            instr(workshopText('script.70dfaeed0656', 'HEDEF (sabit) lamı QuPath\'te açın, "↻ Kaynakları tazele" deyin, kaynağı seçip "Anotasyonları aktar"a basın. Örtüşmeyi opaklık kaydırıcısıyla doğrulayın.')),
            srcRow, srcNote,
            new javafx.scene.layout.VBox(4, measChk, dsRow, lockChk),
            status)

        def disclaimer = new javafx.scene.control.Label(workshopText('script.690db56ca047', '⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar; klinik karar üretmez.'))
        disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
        disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-font-size: 11px; -fx-padding: 4 2 4 2;')

        def mkSep = { -> new javafx.scene.control.Separator() }
        def content = new javafx.scene.layout.VBox(12, step1, mkSep(), step2, mkSep(), step3, mkSep(), step4)
        content.setPadding(new javafx.geometry.Insets(14))
        def scroll = new javafx.scene.control.ScrollPane(content)
        scroll.setFitToWidth(true)
        def root = new javafx.scene.layout.BorderPane()
        root.setCenter(scroll)
        def bottom = new javafx.scene.layout.VBox(8, disclaimer, buttons)
        bottom.setPadding(new javafx.geometry.Insets(10))
        root.setBottom(bottom)
        stage.setScene(new javafx.scene.Scene(root, 720, 620))
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.c552359af77c', 'Warpy sihirbazı açılamadı'), t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}

// Resolve extension UI language; standalone scripts keep their Turkish fallback.
private String workshopText(String key, String fallback) {
    try {
        def bundle = Class.forName('io.github.sbalci.qupath.workshop.WorkshopI18n')
        String translated = bundle.getMethod('t', String.class).invoke(null, key) as String
        return translated == key ? fallback : translated
    } catch (Throwable ignored) {
        return fallback
    }
}
