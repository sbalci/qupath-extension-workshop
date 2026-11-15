/**
 * Yardımcı - Hizalama sihirbazı (afin — otomatik/elle) + anotasyon aktarımı
 * -----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Aynı projedeki bir KAYNAK lamın üst düzey anotasyonlarını, bir AFİN
 *   dönüşüm uygulayarak şu anki (HEDEF) lama kopyalar. Afin dönüşümü İKİ
 *   yoldan elde edebilirsiniz:
 *     (A) OTOMATİK — Align eklentisinin AutoAligner'ı ile kaynak↔hedef arasında
 *         yoğunluk/anotasyon tabanlı afin hizalamayı BURADAN hesaplar (ayrı
 *         pencere + kopyala-yapıştır gerekmez). [qupath-extension-align gerekir.]
 *     (B) ELLE — [Analyze → Alignment → Interactive image alignment] penceresindeki
 *         6 sayıyı (m00 m01 m02 m10 m11 m12) yapıştırırsınız.
 *   Böylece H&E üzerinde çizdiğiniz tümör/bölge anotasyonunu, hizaladığınız İHK
 *   lamına TEK TIKLA ve YİNELENEBİLİR biçimde aktarırsınız.
 *
 *   Kavram + yöntem: Pete Bankhead'in "transfer objects between images" betiği
 *   (gist c696ffb…) — kaynak hiyerarşiyi okur, PathObjectTools.transformObject(...)
 *   ile her ROI'ye afin dönüşümü uygular, alt anotasyonları özyinelemeli taşır.
 *   bkz. Ekler → Görüntü Hizalama §3/§6.
 *
 * YÖN (önemli):
 *   AutoAligner.getAlignTransformation(base, toAlign, …) 'base' (HEDEF/şu anki)
 *   piksel uzayından 'toAlign' (KAYNAK) piksel uzayına eşleyen bir dönüşüm döndürür.
 *   Kaynak anotasyonunu HEDEF'e taşımak için TERSİ gerekir → otomatik yolda
 *   dönüşümün TERSİ (createInverse) kaynağa uygulanır (deterministik; kullanıcı
 *   "ters çevir" onayı GEREKMEZ). Elle (paste) yolda matrisin yönü, hangi görüntünün
 *   "hareketli" seçildiğine bağlı olduğundan "Ters çevir" onayı sunulur.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Yalnız nesneleri TAŞIR (koordinat dönüşümü). Hizalama kalitesini,
 *     patoloji yorumunu veya klinik yeterliliği üretmez. Aktarılan sınır,
 *     seri kesitlerde YAKLAŞIK aynı bölgeyi gösterir (birebir hücre değil).
 *     Örtüşmeyi her zaman opaklık kaydırıcısıyla GÖZLE doğrulayın.
 *   • Esnek (non-rigid) deformasyon için afin yetmez → Warpy sihirbazı (Ek § Warpy).
 *
 * KULLANIM:
 *   1. Kaynak (anotasyonların çizili olduğu) ve hedef lam AYNI projede olsun.
 *   2. HEDEF lamı açın.
 *   3. Bu sihirbazı çalıştırın → kaynağı seçin → (A) "Otomatik hizala + aktar"
 *      VEYA (B) matrisi yapıştırıp "Elle matrisle aktar".
 *
 * ÇIKTI:
 *   • Kaynaktan dönüştürülmüş, KİLİTLİ anotasyonlar (ad: "↪ Hizalama aktarımı")
 *   • Aktarılan nesne sayısını gösteren özet
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjectTools
import java.awt.geom.AffineTransform
import org.codehaus.groovy.runtime.InvokerHelper

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

String SENTINEL = '↪ Hizalama aktarımı'
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
    def pick = Dialogs.showChoiceDialog(workshopText('script.a4536559d5c3', 'Önceki hizalama aktarımı'),
        String.format(java.util.Locale.US, workshopText('script.9e93c5be3e96', 'Bu lamda bu sihirbazın önceki aktarımından %,d anotasyon var ("%s").\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni aktarım ayrı bir set olur ("%s (2)" gibi). Lamda iki set birlikte durur.\n\n"Değiştir" seçilirse önceki aktarımın anotasyonları silinir — onlarda elle yaptığınız düzeltmeler de gider. Onlarla birlikte aktarılmış nesneler (ör. hücre tespitleri) de silinir; içlerine sonradan kendi eklediğiniz nesneler lamda kalır. Bu sürümden önce yapılmış aktarımların içindeki nesneler işaretli olmadığından lamda kalır; gerekirse elle silin. Kendi çizdiğiniz anotasyonlara dokunulmaz.\n\nİptal: hiçbir şey çalıştırılmaz, hiçbir şey yazılmaz (otomatik hizalama da başlamaz).\n\nÖnceki aktarım ne olsun?'), prev.size(), SENTINEL, SENTINEL),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.49dce785c700', 'Önceki hizalama aktarımı silinecek'),
            String.format(java.util.Locale.US, workshopText('script.793ec45d606c', '"%2$s" adlı %1$,d anotasyon silinecek ve yerine yenisi aktarılacak. Bu anotasyonlarda elle yaptığınız düzeltmeler KAYBOLUR; onlarla birlikte aktarılmış nesneler de silinir. İçlerine sonradan kendi eklediğiniz nesneler kalır.\n\nDevam edilsin mi?'), prev.size(), SENTINEL))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}

// ── İsteğe bağlı bağımlılık: AutoAligner yalnız qupath-extension-align'ın AutoAligner İÇEREN bir
//    derlemesinde vardır. DİKKAT: yayınlanmış 0.5.0 sürümü bu sınıfı İÇERMEZ (yalnız GUI:
//    qupath.ext.align.gui.ImageAlignmentPane) — AutoAligner geliştirme (main) dalındadır. Bu yüzden
//    "Align kurulu mu" (GUI) ile "otomatik hizalama var mı" (AutoAligner) AYRI denetlenir.
def alignerClass = { ->
    try { return Class.forName('qupath.ext.align.core.AutoAligner') }
    catch (Throwable t) { return null }
}
def alignInstalled = { ->   // yayınlanmış Align (Interactive image alignment) yüklü mü?
    for (n in ['qupath.ext.align.AlignExtension', 'qupath.ext.align.gui.ImageAlignmentPane']) {
        try { Class.forName(n); return true } catch (Throwable ignore) {}
    }
    return false
}
// İç içe enum: qupath.ext.align.core.AutoAligner$AlignmentType / $TransformationTypes
// (TEK tırnak: Groovy'de '$' düz karakter; çift tırnak interpolasyon yapardı.)
def enumVal = { String clsName, String name -> Enum.valueOf((Class) Class.forName(clsName), name) }

// ── Align eklentisi (qupath-extension-align) kurulum yardımı ─────────
// Hem (A) otomatik hizalama hem de (B) için matris üreten [Interactive image alignment] bu eklentidendir.
String ALIGN_RELEASES_URL = 'https://github.com/qupath/qupath-extension-align/releases'
def openUrl = { String url ->
    // Tarayıcıda aç (arka planda; Desktop.browse bloklayabilir); olmazsa panoya kopyala.
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
            } catch (Throwable t) { Dialogs.showMessageDialog(workshopText('script.faa3b940c076', 'İndirme bağlantısı'), url) }
        }
    }, 'OpenUrl').start()
}
def showAlignInstall = {
    def steps = workshopText('script.08b4993f18ec', 'AFİN HİZALAMA — (A) otomatik vs (B) elle:\n\n⚠ ÖNEMLİ: Sihirbazın (A) OTOMATİK yolu, Align\'ın "AutoAligner" sınıfını İÇEREN bir derlemesini ister.\n   YAYINLANMIŞ 0.5.0 sürümü bunu İÇERMEZ (AutoAligner yalnız geliştirme/main dalındadır). Yani Align\'ı\n   kursanız bile 0.5.0\'da (A) KAPALI kalır — bu normaldir. O sürümde:\n     • [Analyze → Alignment → Interactive image alignment] ile hizalayın (Align KURULU olmalı),\n     • gösterilen 6 sayıyı (B) Elle matris kutusuna yapıştırıp "Elle matrisle aktar" deyin.\n   (A) yalnız AutoAligner içeren bir Align derlemesi kurulursa otomatik etkinleşir.\n\nqupath-extension-align KURULUMU (Interactive image alignment için — (B) yolu ve GUI hizalama):\n\nEN İYİSİ — resmî KATALOGDAN kurun (sürümünüzle EŞLEŞEN derlemeyi verir):\n  Extensions → Manage extensions → Manage extension catalogs → resmî QuPath kataloğu → Align → kur.\n\nElle .jar ile:\n  1. GitHub sürüm sayfasını açın (aşağıda), QuPath SÜRÜMÜNÜZLE uyumlu en son sürümü bulun.\n  2. "qupath-extension-align-X.Y.Z.jar" indirin — "-javadoc"/"-sources" ya da kaynak (zip/tar.gz) DEĞİL; düz .jar (~48 KB).\n  3. .jar\'ı QuPath penceresine SÜRÜKLEYİP BIRAKIN.\n  4. QuPath\'i YENİDEN BAŞLATIN; bu sihirbazı tekrar açın.\n\nZATEN KURDUYSANIZ AMA TANINMIYORSA (Analyze → Alignment menüsü de YOKSA):\n  Büyük olasılıkla SÜRÜM UYUMSUZLUĞU — QuPath uyumsuz eklentiyi yükleMEZ (menü çıkmaz, sınıfları görünmez).\n  • QuPath sürümünüzü kontrol edin (Help → About).\n  • Log\'a bakın (View → Show log; "align" arayın) — uyumsuzluk/yükleme hatası orada yazar.\n  • Elle .jar yerine KATALOGDAN, sürümünüzle eşleşen Align\'ı kurun (0.5.0 bazı 0.6/0.7 sürümlerinde yüklenmeyebilir).\n\nGitHub sürüm sayfasını şimdi açayım mı?')
    if (Dialogs.showConfirmDialog(workshopText('script.b9087b724a59', 'Align eklentisi nasıl kurulur?'), steps)) openUrl(ALIGN_RELEASES_URL)
}

// ── Özyinelemeli dönüşüm (Bankhead gist deseni): nesneyi + tüm alt nesnelerini taşı
def transformRecursive
transformRecursive = { PathObject src, AffineTransform tf, boolean copyMeas ->
    def out = PathObjectTools.transformObject(src, tf, copyMeas)
    // Yalnız alt ANOTASYONLARI taşı; kaynak hücre tespitlerini (binlerce) sürüklemeyiz.
    def childAnns = src.getChildObjectsAsArray().findAll { it.isAnnotation() }
    if (!childAnns.isEmpty())
        out.addPathObjects(childAnns.collect { transformRecursive(it, tf, copyMeas) })
    return out
}

// ── 6 sayılık matrisi ayrıştır (boşluk / virgül / noktalı virgül / satır ayrılmış)
def parseMatrix = { String text ->
    if (text == null) return null
    def toks = text.trim().split('[\\s,;]+').findAll { it }
    if (toks.size() != 6) return null
    try { return toks.collect { Double.parseDouble(it) } } catch (Throwable t) { return null }
}

// ── Ön kontroller ───────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', "Görüntü açık değil"), workshopText('script.06e050a2afff', "Aktarımın HEDEF lamını açın (anotasyonların kopyalanacağı lam)."))
    return
}
def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage(workshopText('script.a38d0e6adac0', "Proje açık değil"), workshopText('script.94d00a2c421b', "Kaynak ve hedef lam AYNI QuPath projesinde olmalıdır."))
    return
}
def entries = project.getImageList()
if (entries == null || entries.isEmpty()) {
    Dialogs.showErrorMessage(workshopText('script.9b147d756945', "Projede lam yok"), workshopText('script.9df5df6146ce', "Hizalama aktarımı için projede en az bir kaynak lam olmalı."))
    return
}
if (isHeadless) {
    println "Bu yardımcı QuPath arayüzü gerektirir (kaynak lam seçimi + otomatik hizalama veya matris girişi)."
    return
}

// ── Ortak aktarım kuyruğu: kaynak anotasyonlarını finalTf ile taşı → kilitli ekle
def applyTransferred = { List srcAnns, AffineTransform finalTf, boolean copyMeas, boolean lock, String mode = 'add' ->
    def newObjs = srcAnns.collect { transformRecursive(it, finalTf, copyMeas) }
    // Önceki aktarım: SORULMADAN SİLİNMEZ. Kip kullanıcıdan gelir (varsayılan: ekle).
    //   ekle     → hiçbir şey silinmez; yeni set sıradaki adı alır ("↪ Hizalama aktarımı (2)" …).
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
    newObjs.each { o -> o.setName(setName); if (lock) o.setLocked(true) }
    tagTransferTree(newObjs, setName)
    QP.addObjects(newObjs)
    QP.fireHierarchyUpdate()
    return [count: newObjs.size(), setName: setName, prevNames: (mode == 'replace' ? [] : prevNames), removed: removed]
}

// ── (B) ELLE: pastelenmiş 6 sayı → aktarım (arka planda; kaynak hiyerarşi okuma IO'su) ───
def doTransferManual = { String sourceName, List<Double> m, boolean inv, boolean copyMeas, boolean lock, String mode = 'add' ->
    try {
        def entry = project.getImageList().find { it.getImageName() == sourceName }
        if (entry == null) return [ok:false, error:String.format(java.util.Locale.US, workshopText('script.946205c2d40a', 'Kaynak lam bulunamadı: %s'), sourceName)]
        def srcHier
        try { srcHier = entry.readHierarchy() }
        catch (Throwable t) { return [ok:false, error:String.format(java.util.Locale.US, workshopText('script.20e5b381a4ff', 'Kaynak hiyerarşi okunamadı (%s). Kaynak lamı projede bir kez açıp kaydetmeyi deneyin.'), t.getClass().getSimpleName())] }
        def srcAnns = srcHier.getRootObject().getChildObjectsAsArray().findAll { it.isAnnotation() }
        if (srcAnns.isEmpty()) return [ok:false, error:String.format(java.util.Locale.US, workshopText('script.e9c1a0050994', 'Kaynak lamda üst düzey anotasyon yok: %s'), sourceName)]

        // Matris sırası: dialog m00 m01 m02 / m10 m11 m12 → AffineTransform(m00,m10,m01,m11,m02,m12)
        def tf = new AffineTransform(m[0], m[3], m[1], m[4], m[2], m[5])
        if (inv) {
            try { tf = tf.createInverse() }
            catch (Throwable t) { return [ok:false, error:workshopText('script.287cf7360c03', 'Matrisin tersi alınamadı (tekil matris). \'Ters çevir\' kutusunu kapatmayı deneyin.')] }
        }
        def tr = applyTransferred(srcAnns, tf, copyMeas, lock, mode)
        return [ok:true, count:tr.count, source:sourceName, setName:tr.setName, prevNames:tr.prevNames, removed:tr.removed]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
    }
}

// ── (A) OTOMATİK: AutoAligner ile afin hesapla → TERSİ ile kaynağı hedefe taşı ───
def doTransferAuto = { String sourceName, String typeName, String transformName, Integer userDs, boolean copyMeas, boolean lock, String mode = 'add' ->
    def A = alignerClass()
    if (A == null) return [ok:false, kind:'noclass', error:workshopText('script.c05bc5deff33', 'Align eklentisi (qupath-extension-align) kurulu değil; otomatik hizalama yok. Resmî katalogdan kurun ya da (B) elle matris yolunu kullanın.')]
    def entry = project.getImageList().find { it.getImageName() == sourceName }
    if (entry == null) return [ok:false, error:String.format(java.util.Locale.US, workshopText('script.946205c2d40a', 'Kaynak lam bulunamadı: %s'), sourceName)]
    def srcImageData = null
    try {
        def base = QP.getCurrentImageData()          // HEDEF (şu anki) = base; KAPATMA (açık görüntü)
        srcImageData = entry.readImageData()          // KAYNAK = toAlign; AÇIYORUZ → finally'de KAPAT
        // Downsample tabanı görüntü boyutundan hesaplanır (aksi halde INTENSITY ECC tam çözünürlükte OOM olur).
        int w = Math.max(base.getServer().getWidth(),  srcImageData.getServer().getWidth())
        int h = Math.max(base.getServer().getHeight(), srcImageData.getServer().getHeight())
        int autoDs = Math.max(1, (int) Math.ceil(Math.max(w, h) / 1500.0d))
        int ds = (userDs != null && userDs > 0) ? userDs : autoDs
        // Piksel bütçesi: aşırı küçük downsample bellek patlatır ("takıldı" gibi görünür).
        double mp = ((double) w / ds) * ((double) h / ds) / 1.0e6d
        if (mp > 50.0d) return [ok:false, kind:'budget', error:String.format(java.util.Locale.US,
            workshopText('script.036d0ab7e8dc', 'Downsample çok küçük (işlenen ~%.0f MP > 50 MP sınırı). En az %d kullanın (boş = otomatik %d).'), mp, autoDs, autoDs)]

        def alignType, transformType
        try {
            alignType     = enumVal('qupath.ext.align.core.AutoAligner$AlignmentType', typeName)
            transformType = enumVal('qupath.ext.align.core.AutoAligner$TransformationTypes', transformName)
        } catch (Throwable t) {
            return [ok:false, kind:'nomethod', error:workshopText('script.00b90764fec6', 'AutoAligner enum\'ları çözülemedi (sürüm uyumsuzluğu): ') + (t.getMessage() ?: t.getClass().getSimpleName())]
        }

        def T
        try {
            // getAlignTransformation(base, toAlign, initial, AlignmentType, TransformationTypes, downsample)
            T = InvokerHelper.invokeStaticMethod(A, 'getAlignTransformation',
                [base, srcImageData, new AffineTransform(), alignType, transformType, (double) ds] as Object[])
        } catch (groovy.lang.MissingMethodException | NoSuchMethodException nsme) {
            return [ok:false, kind:'nomethod', error:workshopText('script.042ccc1f4ff5', 'AutoAligner API imzası beklenenden farklı (sürüm uyumsuzluğu): ') + nsme.getMessage()]
        } catch (Throwable t) {
            def cause = (t.getCause() != null) ? t.getCause() : t
            return [ok:false, kind:'compute', error:workshopText('script.a97e90448a7c', 'Otomatik hizalama başarısız: ') + cause.getClass().getSimpleName() + ': ' + (cause.getMessage() ?: '') +
                workshopText('script.34c9c0f2e0d5', '\nYoğunluk (INTENSITY) modu benzer görünümlü görüntülerde iyi çalışır; farklı boyalarda (B) elle matris ya da Warpy\'yi deneyin.')]
        }
        if (T == null || !(T instanceof AffineTransform))
            return [ok:false, kind:'compute', error:workshopText('script.dd39f583dd6e', 'Otomatik hizalama bir dönüşüm üretmedi (görüntüler yeterince örtüşmüyor olabilir).')]

        // YÖN: T = base→toAlign (hedef→kaynak). Kaynak anotasyonunu HEDEF'e taşımak için TERSİ gerekir.
        def finalTf
        try { finalTf = ((AffineTransform) T).createInverse() }
        catch (Throwable t) { return [ok:false, kind:'compute', error:workshopText('script.b3d9c811e8f0', 'Hesaplanan dönüşümün tersi alınamadı (tekil): ') + (t.getMessage() ?: '')] }

        def srcAnns = srcImageData.getHierarchy().getRootObject().getChildObjectsAsArray().findAll { it.isAnnotation() }
        if (srcAnns.isEmpty()) return [ok:false, error:String.format(java.util.Locale.US, workshopText('script.e9c1a0050994', 'Kaynak lamda üst düzey anotasyon yok: %s'), sourceName)]
        def tr = applyTransferred(srcAnns, finalTf, copyMeas, lock, mode)
        return [ok:true, count:tr.count, source:sourceName, ds:ds, setName:tr.setName, prevNames:tr.prevNames, removed:tr.removed]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
    } finally {
        if (srcImageData != null) { try { srcImageData.close() } catch (Throwable ignore) {} }  // ImageData AutoCloseable (0.6)
    }
}

// ── Tek pencere sihirbaz ─────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        def alignPresent = (alignerClass() != null)          // (A) otomatik hizalama sınıfı (AutoAligner) var mı?
        def alignGuiOnly = (!alignPresent && alignInstalled())  // Align KURULU ama bu derlemede AutoAligner YOK (ör. 0.5.0)

        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle(workshopText('script.bfef327a90ad', 'Hizalama sihirbazı (afin — otomatik/elle)'))
        stage.setAlwaysOnTop(true)

        def info = new javafx.scene.control.Label(
            workshopText('script.a295c2a388f8', 'Kaynak lamı seçin. (A) Otomatik: Align eklentisi kaynağı bu (HEDEF) lama afin hizalar ve\n' +
            'anotasyonları aktarır. (B) Elle: [Analyze → Alignment → Interactive image alignment] penceresindeki\n' +
            '6 sayıyı yapıştırın. Her iki durumda kaynağın üst düzey anotasyonları buraya kilitli kopyalanır;\n' +
            'örtüşmeyi opaklık kaydırıcısıyla gözle doğrulayın.'))
        info.setWrapText(true); info.setMaxWidth(Double.MAX_VALUE)

        def sourceBox = new javafx.scene.control.ChoiceBox()
        entries.each { sourceBox.getItems().add(it.getImageName()) }
        if (!sourceBox.getItems().isEmpty()) sourceBox.getSelectionModel().selectFirst()
        sourceBox.setMaxWidth(Double.MAX_VALUE)

        def measChk = new javafx.scene.control.CheckBox(workshopText('script.b5d46a3255ec', 'Ölçümleri kopyala')); measChk.setSelected(true)
        def lockChk = new javafx.scene.control.CheckBox(workshopText('script.f9efed6c13a8', 'Aktarılanları kilitle')); lockChk.setSelected(true)

        // — (A) Otomatik —
        def typeBox = new javafx.scene.control.ChoiceBox()
        typeBox.getItems().addAll('INTENSITY', 'AREA_ANNOTATIONS', 'POINT_ANNOTATIONS'); typeBox.getSelectionModel().selectFirst()
        def transformBox = new javafx.scene.control.ChoiceBox()
        transformBox.getItems().addAll('AFFINE', 'RIGID'); transformBox.getSelectionModel().selectFirst()
        def dsField = new javafx.scene.control.TextField(); dsField.setPromptText(workshopText('script.a9a111622b2f', 'boş = otomatik')); dsField.setPrefColumnCount(6)
        def autoBtn = new javafx.scene.control.Button(workshopText('script.716ea7f610e3', 'Otomatik hizala + aktar'))
        def autoNote = new javafx.scene.control.Label(
            alignPresent ? workshopText('script.718cc55dc2f9', 'INTENSITY: anotasyon gerektirmez (yoğunluk/ECC). AREA/POINT: her iki lamda uygun anotasyon ister.') :
            alignGuiOnly ? workshopText('script.e1dc814fad80', '⚠ Align KURULU ama bu SÜRÜM otomatik-hizalama sınıfını (AutoAligner) içermez (yayınlanmış 0.5.0 böyle — AutoAligner yalnız geliştirme/main dalında). [Analyze → Alignment → Interactive image alignment] ile hizalayıp 6 sayıyı (B)\'ye yapıştırın. Ayrıntı: "Otomatik neden kapalı?" düğmesi.')
                         : workshopText('script.3a993989b3b4', '⚠ qupath-extension-align YÜKLENMEDİ — otomatik hizalama devre dışı. "Align eklentisini kur (nasıl?)" düğmesine basın (kurulu ama tanınmıyorsa: sürüm uyumsuzluğu — düğme log/çözümü açıklar). Ya da (B) elle matrisi kullanın.'))
        autoNote.setWrapText(true); autoNote.setMaxWidth(Double.MAX_VALUE)
        if (!alignPresent) autoNote.setStyle('-fx-text-fill: -qp-script-error-color;')
        // (A) kapalıysa yardım düğmesi: metni duruma göre değişir (kurulu-ama-AutoAligner-yok vs kurulu-değil).
        def installBtn = new javafx.scene.control.Button(alignGuiOnly ? workshopText('script.22158275eb3a', 'Otomatik neden kapalı?') : workshopText('script.b312d2b11f19', 'Align eklentisini kur (nasıl?)'))
        installBtn.setOnAction({ showAlignInstall() })
        if (!alignPresent) { typeBox.setDisable(true); transformBox.setDisable(true); dsField.setDisable(true); autoBtn.setDisable(true) }

        // — (B) Elle —
        def matrixField = new javafx.scene.control.TextArea()
        matrixField.setPromptText(workshopText('script.6c8c55d1c07d', 'ör.  -0.998  -0.070  127256.994  0.070  -0.998  72627.371'))
        matrixField.setPrefRowCount(2); matrixField.setWrapText(true)
        def invChk  = new javafx.scene.control.CheckBox(workshopText('script.3dd9b0e54f15', 'Ters çevir (konum yanlış çıkarsa açık tutun)')); invChk.setSelected(true)
        def manualBtn = new javafx.scene.control.Button(workshopText('script.62fa43017902', 'Elle matrisle aktar'))

        def status = new javafx.scene.control.Label(workshopText('script.86bb45e59294', 'Hazır.')); status.setWrapText(true); status.setMaxWidth(Double.MAX_VALUE)
        def kapatBtn = new javafx.scene.control.Button(workshopText('script.7b31a9fc4816', 'Kapat')); kapatBtn.setOnAction({ stage.close() })

        def showResult = { r ->
            if (r.ok) {
                status.setStyle(''); status.setText(String.format(java.util.Locale.US,
                    workshopText('script.bfcc323dc363', 'Tamamlandı ✅ — %,d anotasyon aktarıldı. Örtüşmeyi gözle doğrulayın.'), r.count))
                def msg = new StringBuilder()
                msg << String.format(java.util.Locale.US, workshopText('script.b3989cbb3bf6', 'Kaynak: %s%n'), r.source)
                msg << String.format(java.util.Locale.US, workshopText('script.8cad3cea7582', 'Aktarılan anotasyon: %,d (kilitli; ad "%s")%n'), r.count, (r.setName ?: SENTINEL))
                if (r.prevNames) {
                    def stillMsg = String.format(java.util.Locale.US, workshopText('script.2d499cfac317', 'DİKKAT — bu lamda önceki hizalama aktarımı da duruyor: %1$s. Hiçbir şey silinmedi; yeni set "%2$s" adıyla eklendi. Eski seti kaldırmak için o addaki anotasyonları seçip silin ya da yeniden aktarırken "Öncekini değiştir"i seçin. Dışa aktarım ve ölçümler iki seti de görür.'), r.prevNames.join(', '), r.setName)
                    msg << '\n' << stillMsg << '\n'
                    status.setText(status.getText() + '\n' + stillMsg)
                } else if ((r.removed ?: 0) > 0) {
                    def replMsg = String.format(java.util.Locale.US, workshopText('script.a5361261ace9', 'Önceki hizalama aktarımı değiştirildi: %,d anotasyon silindi ve onlarla aktarılmış nesneler (sonradan eklediğiniz nesneler korundu).'), r.removed)
                    msg << '\n' << replMsg << '\n'
                    status.setText(status.getText() + '\n' + replMsg)
                }
                if (r.ds) msg << String.format(java.util.Locale.US, 'Otomatik hizalama downsample: %d%n', r.ds)
                msg << workshopText('script.b100d0fba2ef', '\nAktarılan sınır seri kesitte YAKLAŞIK aynı bölgeyi gösterir; örtüşmeyi\n')
                msg << workshopText('script.c6eb27d579b4', 'opaklık kaydırıcısıyla doğrulayın. Hücre-hücre kesinlik için Warpy kullanın.\n')
                msg << workshopText('script.625c43e2ff2e', '⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.')
                Dialogs.showMessageDialog(workshopText('script.c97bb2b11831', 'Hizalama aktarımı tamamlandı'), msg.toString())
            } else {
                status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + (r.error ?: workshopText('script.da001998a5bd', 'Bilinmeyen hata')))
            }
        }

        autoBtn.setOnAction({
            def src = sourceBox.getValue()
            if (!src) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText(workshopText('script.c43c48c1957f', 'Önce kaynak lamı seçin.')); return }
            Integer userDs = null
            def dsTxt = dsField.getText()?.trim()
            if (dsTxt) { try { userDs = Integer.parseInt(dsTxt); if (userDs < 1) throw new NumberFormatException() } catch (Throwable t) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText(workshopText('script.45a760468538', 'Downsample pozitif tam sayı olmalı (ya da boş).')); return } }
            // Önceki aktarım varsa ağır işten (kaynak açma + hizalama) ÖNCE sor: İptal'de hiçbir şey çalışmaz, hiçbir şey yazılmaz.
            final String transferMode = askExisting()
            if (transferMode == null) return
            autoBtn.setDisable(true); manualBtn.setDisable(true); status.setStyle(''); status.setText(workshopText('script.53d61fd0efa4', 'Otomatik hizalanıyor… (kaynak görüntü açılıyor, büyük lam yavaş olabilir)'))
            def worker = new Thread({
                def r = doTransferAuto(src, typeBox.getValue(), transformBox.getValue(), userDs, measChk.isSelected(), lockChk.isSelected(), transferMode)
                javafx.application.Platform.runLater { autoBtn.setDisable(false); manualBtn.setDisable(false); showResult(r) }
            }, 'HizalamaOto'); worker.setDaemon(true); worker.start()
        })

        manualBtn.setOnAction({
            def src = sourceBox.getValue()
            if (!src) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText(workshopText('script.c43c48c1957f', 'Önce kaynak lamı seçin.')); return }
            def m = parseMatrix(matrixField.getText())
            if (m == null) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText(workshopText('script.c4c17e024795', 'Matris tam 6 sayı olmalı (boşluk/virgül ayrılmış).')); return }
            // Önceki aktarım varsa sor: İptal'de hiçbir şey yazılmaz.
            final String transferMode = askExisting()
            if (transferMode == null) return
            autoBtn.setDisable(true); manualBtn.setDisable(true); status.setStyle(''); status.setText(workshopText('script.e7ae44366dcf', 'Aktarılıyor…'))
            def worker = new Thread({
                def r = doTransferManual(src, m, invChk.isSelected(), measChk.isSelected(), lockChk.isSelected(), transferMode)
                javafx.application.Platform.runLater { autoBtn.setDisable(false); manualBtn.setDisable(false); showResult(r) }
            }, 'HizalamaAktarim'); worker.setDaemon(true); worker.start()
        })

        // — Yerleşim —
        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(8); grid.setPadding(new javafx.geometry.Insets(4))
        grid.addRow(0, new javafx.scene.control.Label(workshopText('script.a4aab64f8584', 'Kaynak lam:')), sourceBox)
        javafx.scene.layout.GridPane.setHgrow(sourceBox, javafx.scene.layout.Priority.ALWAYS)

        def autoTitle = new javafx.scene.control.Label(workshopText('script.51ca954222cd', '(A) Otomatik hizala (qupath-extension-align)')); autoTitle.setStyle('-fx-font-weight: bold;')
        def autoRow = new javafx.scene.layout.HBox(8,
            new javafx.scene.control.Label(workshopText('script.e79a63f3a487', 'Mod:')), typeBox, new javafx.scene.control.Label(workshopText('script.2866438262a5', 'Dönüşüm:')), transformBox,
            new javafx.scene.control.Label('Downsample:'), dsField, autoBtn)
        autoRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        if (!alignPresent) autoRow.getChildren().add(installBtn)   // eklenti yoksa kurulum düğmesini (A) satırına ekle

        def manTitle = new javafx.scene.control.Label(workshopText('script.681ab8ea1f89', '(B) Elle matris (Interactive image alignment penceresindeki 6 sayı)')); manTitle.setStyle('-fx-font-weight: bold;')
        def manRow = new javafx.scene.layout.HBox(8, invChk, manualBtn); manRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)

        def spacer = new javafx.scene.layout.Region()
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
        def buttons = new javafx.scene.layout.HBox(8, spacer, kapatBtn)
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

        def disclaimer = new javafx.scene.control.Label(workshopText('script.690db56ca047', '⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar; klinik karar üretmez.'))
        disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
        disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-font-size: 11px; -fx-padding: 4 2 4 2;')

        def sep1 = new javafx.scene.control.Separator(); def sep2 = new javafx.scene.control.Separator()
        def content = new javafx.scene.layout.VBox(10, info, grid,
            new javafx.scene.layout.HBox(16, measChk, lockChk), sep1,
            autoTitle, autoRow, autoNote, sep2,
            manTitle, matrixField, manRow, status)
        content.setPadding(new javafx.geometry.Insets(14))
        def root = new javafx.scene.layout.BorderPane()
        root.setCenter(content)
        def bottom = new javafx.scene.layout.VBox(8, disclaimer, buttons)
        bottom.setPadding(new javafx.geometry.Insets(10))
        root.setBottom(bottom)
        stage.setScene(new javafx.scene.Scene(root, 720, 520))
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.934aee7fda81', 'Hizalama sihirbazı açılamadı'), t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
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
