/**
 * Yardımcı - Hizalama sihirbazı (afin — otomatik/elle) + anotasyon aktarımı
 * -----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Aynı projedeki bir KAYNAK slaytın üst düzey anotasyonlarını, bir AFİN
 *   dönüşüm uygulayarak şu anki (HEDEF) slayda kopyalar. Afin dönüşümü İKİ
 *   yoldan elde edebilirsiniz:
 *     (A) OTOMATİK — Align eklentisinin AutoAligner'ı ile kaynak↔hedef arasında
 *         yoğunluk/anotasyon tabanlı afin hizalamayı BURADAN hesaplar (ayrı
 *         pencere + kopyala-yapıştır gerekmez). [qupath-extension-align gerekir.]
 *     (B) ELLE — [Extensions → Alignment → Interactive image alignment] penceresindeki
 *         6 sayıyı (m00 m01 m02 m10 m11 m12) yapıştırırsınız.
 *   Böylece H&E üzerinde çizdiğiniz tümör/bölge anotasyonunu, hizaladığınız İHK
 *   slaydına TEK TIKLA ve YİNELENEBİLİR biçimde aktarırsınız.
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
 *   1. Kaynak (anotasyonların çizili olduğu) ve hedef slayt AYNI projede olsun.
 *   2. HEDEF slaydı açın.
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
                Dialogs.showMessageDialog('Bağlantı panoya kopyalandı', 'Tarayıcı otomatik açılamadı. Bağlantı panoya kopyalandı:\n' + url)
            } catch (Throwable t) { Dialogs.showMessageDialog('İndirme bağlantısı', url) }
        }
    }, 'OpenUrl').start()
}
def showAlignInstall = {
    def steps = 'AFİN HİZALAMA — (A) otomatik vs (B) elle:\n\n' +
        '⚠ ÖNEMLİ: Sihirbazın (A) OTOMATİK yolu, Align\'ın "AutoAligner" sınıfını İÇEREN bir derlemesini ister.\n' +
        '   YAYINLANMIŞ 0.5.0 sürümü bunu İÇERMEZ (AutoAligner yalnız geliştirme/main dalındadır). Yani Align\'ı\n' +
        '   kursanız bile 0.5.0\'da (A) KAPALI kalır — bu normaldir. O sürümde:\n' +
        '     • [Extensions → Alignment → Interactive image alignment] ile hizalayın (Align KURULU olmalı),\n' +
        '     • gösterilen 6 sayıyı (B) Elle matris kutusuna yapıştırıp "Elle matrisle aktar" deyin.\n' +
        '   (A) yalnız AutoAligner içeren bir Align derlemesi kurulursa otomatik etkinleşir.\n\n' +
        'qupath-extension-align KURULUMU (Interactive image alignment için — (B) yolu ve GUI hizalama):\n\n' +
        'EN İYİSİ — resmî KATALOGDAN kurun (sürümünüzle EŞLEŞEN derlemeyi verir):\n' +
        '  Extensions → Manage extensions → Manage extension catalogs → resmî QuPath kataloğu → Align → kur.\n\n' +
        'Elle .jar ile:\n' +
        '  1. GitHub sürüm sayfasını açın (aşağıda), QuPath SÜRÜMÜNÜZLE uyumlu en son sürümü bulun.\n' +
        '  2. "qupath-extension-align-X.Y.Z.jar" indirin — "-javadoc"/"-sources" ya da kaynak (zip/tar.gz) DEĞİL; düz .jar (~48 KB).\n' +
        '  3. .jar\'ı QuPath penceresine SÜRÜKLEYİP BIRAKIN.\n' +
        '  4. QuPath\'i YENİDEN BAŞLATIN; bu sihirbazı tekrar açın.\n\n' +
        'ZATEN KURDUYSANIZ AMA TANINMIYORSA (Extensions → Alignment menüsü de YOKSA):\n' +
        '  Büyük olasılıkla SÜRÜM UYUMSUZLUĞU — QuPath uyumsuz eklentiyi yükleMEZ (menü çıkmaz, sınıfları görünmez).\n' +
        '  • QuPath sürümünüzü kontrol edin (Help → About).\n' +
        '  • Log\'a bakın (View → Show log; "align" arayın) — uyumsuzluk/yükleme hatası orada yazar.\n' +
        '  • Elle .jar yerine KATALOGDAN, sürümünüzle eşleşen Align\'ı kurun (0.5.0 bazı 0.6/0.7 sürümlerinde yüklenmeyebilir).\n\n' +
        'GitHub sürüm sayfasını şimdi açayım mı?'
    if (Dialogs.showConfirmDialog('Align eklentisi nasıl kurulur?', steps)) openUrl(ALIGN_RELEASES_URL)
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
    Dialogs.showErrorMessage("Görüntü açık değil", "Aktarımın HEDEF slaytını açın (anotasyonların kopyalanacağı slayt).")
    return
}
def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage("Proje açık değil", "Kaynak ve hedef slayt AYNI QuPath projesinde olmalıdır.")
    return
}
def entries = project.getImageList()
if (entries == null || entries.isEmpty()) {
    Dialogs.showErrorMessage("Projede slayt yok", "Hizalama aktarımı için projede en az bir kaynak slayt olmalı.")
    return
}
if (isHeadless) {
    println "Bu yardımcı QuPath arayüzü gerektirir (kaynak slayt seçimi + otomatik hizalama veya matris girişi)."
    return
}

// ── Ortak aktarım kuyruğu: kaynak anotasyonlarını finalTf ile taşı → kilitli ekle
def applyTransferred = { List srcAnns, AffineTransform finalTf, boolean copyMeas, boolean lock ->
    def newObjs = srcAnns.collect { transformRecursive(it, finalTf, copyMeas) }
    newObjs.each { o -> o.setName(SENTINEL); if (lock) o.setLocked(true) }
    // Önceki aktarımı (aynı ada sahip) temizle → yeniden çalıştırılabilir
    QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == SENTINEL }, false)
    QP.addObjects(newObjs)
    QP.fireHierarchyUpdate()
    return newObjs.size()
}

// ── (B) ELLE: pastelenmiş 6 sayı → aktarım (arka planda; kaynak hiyerarşi okuma IO'su) ───
def doTransferManual = { String sourceName, List<Double> m, boolean inv, boolean copyMeas, boolean lock ->
    try {
        def entry = project.getImageList().find { it.getImageName() == sourceName }
        if (entry == null) return [ok:false, error:"Kaynak slayt bulunamadı: ${sourceName}"]
        def srcHier
        try { srcHier = entry.readHierarchy() }
        catch (Throwable t) { return [ok:false, error:"Kaynak hiyerarşi okunamadı (${t.getClass().getSimpleName()}). Kaynak slaydı projede bir kez açıp kaydetmeyi deneyin."] }
        def srcAnns = srcHier.getRootObject().getChildObjectsAsArray().findAll { it.isAnnotation() }
        if (srcAnns.isEmpty()) return [ok:false, error:"Kaynak slaytta üst düzey anotasyon yok: ${sourceName}"]

        // Matris sırası: dialog m00 m01 m02 / m10 m11 m12 → AffineTransform(m00,m10,m01,m11,m02,m12)
        def tf = new AffineTransform(m[0], m[3], m[1], m[4], m[2], m[5])
        if (inv) {
            try { tf = tf.createInverse() }
            catch (Throwable t) { return [ok:false, error:"Matrisin tersi alınamadı (tekil matris). 'Ters çevir' kutusunu kapatmayı deneyin."] }
        }
        def count = applyTransferred(srcAnns, tf, copyMeas, lock)
        return [ok:true, count:count, source:sourceName]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
    }
}

// ── (A) OTOMATİK: AutoAligner ile afin hesapla → TERSİ ile kaynağı hedefe taşı ───
def doTransferAuto = { String sourceName, String typeName, String transformName, Integer userDs, boolean copyMeas, boolean lock ->
    def A = alignerClass()
    if (A == null) return [ok:false, kind:'noclass', error:"Align eklentisi (qupath-extension-align) kurulu değil; otomatik hizalama yok. Resmî katalogdan kurun ya da (B) elle matris yolunu kullanın."]
    def entry = project.getImageList().find { it.getImageName() == sourceName }
    if (entry == null) return [ok:false, error:"Kaynak slayt bulunamadı: ${sourceName}"]
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
            "Downsample çok küçük (işlenen ~%.0f MP > 50 MP sınırı). En az %d kullanın (boş = otomatik %d).", mp, autoDs, autoDs)]

        def alignType, transformType
        try {
            alignType     = enumVal('qupath.ext.align.core.AutoAligner$AlignmentType', typeName)
            transformType = enumVal('qupath.ext.align.core.AutoAligner$TransformationTypes', transformName)
        } catch (Throwable t) {
            return [ok:false, kind:'nomethod', error:"AutoAligner enum'ları çözülemedi (sürüm uyumsuzluğu): " + (t.getMessage() ?: t.getClass().getSimpleName())]
        }

        def T
        try {
            // getAlignTransformation(base, toAlign, initial, AlignmentType, TransformationTypes, downsample)
            T = InvokerHelper.invokeStaticMethod(A, 'getAlignTransformation',
                [base, srcImageData, new AffineTransform(), alignType, transformType, (double) ds] as Object[])
        } catch (groovy.lang.MissingMethodException | NoSuchMethodException nsme) {
            return [ok:false, kind:'nomethod', error:"AutoAligner API imzası beklenenden farklı (sürüm uyumsuzluğu): " + nsme.getMessage()]
        } catch (Throwable t) {
            def cause = (t.getCause() != null) ? t.getCause() : t
            return [ok:false, kind:'compute', error:"Otomatik hizalama başarısız: " + cause.getClass().getSimpleName() + ': ' + (cause.getMessage() ?: '') +
                "\nYoğunluk (INTENSITY) modu benzer görünümlü görüntülerde iyi çalışır; farklı boyalarda (B) elle matris ya da Warpy'yi deneyin."]
        }
        if (T == null || !(T instanceof AffineTransform))
            return [ok:false, kind:'compute', error:"Otomatik hizalama bir dönüşüm üretmedi (görüntüler yeterince örtüşmüyor olabilir)."]

        // YÖN: T = base→toAlign (hedef→kaynak). Kaynak anotasyonunu HEDEF'e taşımak için TERSİ gerekir.
        def finalTf
        try { finalTf = ((AffineTransform) T).createInverse() }
        catch (Throwable t) { return [ok:false, kind:'compute', error:"Hesaplanan dönüşümün tersi alınamadı (tekil): " + (t.getMessage() ?: '')] }

        def srcAnns = srcImageData.getHierarchy().getRootObject().getChildObjectsAsArray().findAll { it.isAnnotation() }
        if (srcAnns.isEmpty()) return [ok:false, error:"Kaynak slaytta üst düzey anotasyon yok: ${sourceName}"]
        def count = applyTransferred(srcAnns, finalTf, copyMeas, lock)
        return [ok:true, count:count, source:sourceName, ds:ds]
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
        stage.setTitle('Hizalama sihirbazı (afin — otomatik/elle)')
        stage.setAlwaysOnTop(true)

        def info = new javafx.scene.control.Label(
            'Kaynak slaytı seçin. (A) Otomatik: Align eklentisi kaynağı bu (HEDEF) slayda afin hizalar ve\n' +
            'anotasyonları aktarır. (B) Elle: [Extensions → Alignment → Interactive image alignment] penceresindeki\n' +
            '6 sayıyı yapıştırın. Her iki durumda kaynağın üst düzey anotasyonları buraya kilitli kopyalanır;\n' +
            'örtüşmeyi opaklık kaydırıcısıyla gözle doğrulayın.')
        info.setWrapText(true); info.setMaxWidth(Double.MAX_VALUE)

        def sourceBox = new javafx.scene.control.ChoiceBox()
        entries.each { sourceBox.getItems().add(it.getImageName()) }
        if (!sourceBox.getItems().isEmpty()) sourceBox.getSelectionModel().selectFirst()
        sourceBox.setMaxWidth(Double.MAX_VALUE)

        def measChk = new javafx.scene.control.CheckBox('Ölçümleri kopyala'); measChk.setSelected(true)
        def lockChk = new javafx.scene.control.CheckBox('Aktarılanları kilitle'); lockChk.setSelected(true)

        // — (A) Otomatik —
        def typeBox = new javafx.scene.control.ChoiceBox()
        typeBox.getItems().addAll('INTENSITY', 'AREA_ANNOTATIONS', 'POINT_ANNOTATIONS'); typeBox.getSelectionModel().selectFirst()
        def transformBox = new javafx.scene.control.ChoiceBox()
        transformBox.getItems().addAll('AFFINE', 'RIGID'); transformBox.getSelectionModel().selectFirst()
        def dsField = new javafx.scene.control.TextField(); dsField.setPromptText('boş = otomatik'); dsField.setPrefColumnCount(6)
        def autoBtn = new javafx.scene.control.Button('Otomatik hizala + aktar')
        def autoNote = new javafx.scene.control.Label(
            alignPresent ? 'INTENSITY: anotasyon gerektirmez (yoğunluk/ECC). AREA/POINT: her iki slaytta uygun anotasyon ister.' :
            alignGuiOnly ? '⚠ Align KURULU ama bu SÜRÜM otomatik-hizalama sınıfını (AutoAligner) içermez (yayınlanmış 0.5.0 böyle — AutoAligner yalnız geliştirme/main dalında). [Extensions → Alignment → Interactive image alignment] ile hizalayıp 6 sayıyı (B)\'ye yapıştırın. Ayrıntı: "Otomatik neden kapalı?" düğmesi.'
                         : '⚠ qupath-extension-align YÜKLENMEDİ — otomatik hizalama devre dışı. "Align eklentisini kur (nasıl?)" düğmesine basın (kurulu ama tanınmıyorsa: sürüm uyumsuzluğu — düğme log/çözümü açıklar). Ya da (B) elle matrisi kullanın.')
        autoNote.setWrapText(true); autoNote.setMaxWidth(Double.MAX_VALUE)
        if (!alignPresent) autoNote.setStyle('-fx-text-fill: -qp-script-error-color;')
        // (A) kapalıysa yardım düğmesi: metni duruma göre değişir (kurulu-ama-AutoAligner-yok vs kurulu-değil).
        def installBtn = new javafx.scene.control.Button(alignGuiOnly ? 'Otomatik neden kapalı?' : 'Align eklentisini kur (nasıl?)')
        installBtn.setOnAction({ showAlignInstall() })
        if (!alignPresent) { typeBox.setDisable(true); transformBox.setDisable(true); dsField.setDisable(true); autoBtn.setDisable(true) }

        // — (B) Elle —
        def matrixField = new javafx.scene.control.TextArea()
        matrixField.setPromptText('ör.  -0.998  -0.070  127256.994  0.070  -0.998  72627.371')
        matrixField.setPrefRowCount(2); matrixField.setWrapText(true)
        def invChk  = new javafx.scene.control.CheckBox('Ters çevir (konum yanlış çıkarsa açık tutun)'); invChk.setSelected(true)
        def manualBtn = new javafx.scene.control.Button('Elle matrisle aktar')

        def status = new javafx.scene.control.Label('Hazır.'); status.setWrapText(true); status.setMaxWidth(Double.MAX_VALUE)
        def kapatBtn = new javafx.scene.control.Button('Kapat'); kapatBtn.setOnAction({ stage.close() })

        def showResult = { r ->
            if (r.ok) {
                status.setStyle(''); status.setText(String.format(java.util.Locale.US,
                    'Tamamlandı ✅ — %,d anotasyon aktarıldı. Örtüşmeyi gözle doğrulayın.', r.count))
                def msg = new StringBuilder()
                msg << String.format(java.util.Locale.US, 'Kaynak: %s%n', r.source)
                msg << String.format(java.util.Locale.US, 'Aktarılan anotasyon: %,d (kilitli; ad "%s")%n', r.count, SENTINEL)
                if (r.ds) msg << String.format(java.util.Locale.US, 'Otomatik hizalama downsample: %d%n', r.ds)
                msg << '\nAktarılan sınır seri kesitte YAKLAŞIK aynı bölgeyi gösterir; örtüşmeyi\n'
                msg << 'opaklık kaydırıcısıyla doğrulayın. Hücre-hücre kesinlik için Warpy kullanın.\n'
                msg << '⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.'
                Dialogs.showMessageDialog('Hizalama aktarımı tamamlandı', msg.toString())
            } else {
                status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + (r.error ?: 'Bilinmeyen hata'))
            }
        }

        autoBtn.setOnAction({
            def src = sourceBox.getValue()
            if (!src) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('Önce kaynak slaytı seçin.'); return }
            Integer userDs = null
            def dsTxt = dsField.getText()?.trim()
            if (dsTxt) { try { userDs = Integer.parseInt(dsTxt); if (userDs < 1) throw new NumberFormatException() } catch (Throwable t) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('Downsample pozitif tam sayı olmalı (ya da boş).'); return } }
            autoBtn.setDisable(true); manualBtn.setDisable(true); status.setStyle(''); status.setText('Otomatik hizalanıyor… (kaynak görüntü açılıyor, büyük slayt yavaş olabilir)')
            def worker = new Thread({
                def r = doTransferAuto(src, typeBox.getValue(), transformBox.getValue(), userDs, measChk.isSelected(), lockChk.isSelected())
                javafx.application.Platform.runLater { autoBtn.setDisable(false); manualBtn.setDisable(false); showResult(r) }
            }, 'HizalamaOto'); worker.setDaemon(true); worker.start()
        })

        manualBtn.setOnAction({
            def src = sourceBox.getValue()
            if (!src) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('Önce kaynak slaytı seçin.'); return }
            def m = parseMatrix(matrixField.getText())
            if (m == null) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('Matris tam 6 sayı olmalı (boşluk/virgül ayrılmış).'); return }
            autoBtn.setDisable(true); manualBtn.setDisable(true); status.setStyle(''); status.setText('Aktarılıyor…')
            def worker = new Thread({
                def r = doTransferManual(src, m, invChk.isSelected(), measChk.isSelected(), lockChk.isSelected())
                javafx.application.Platform.runLater { autoBtn.setDisable(false); manualBtn.setDisable(false); showResult(r) }
            }, 'HizalamaAktarim'); worker.setDaemon(true); worker.start()
        })

        // — Yerleşim —
        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(8); grid.setPadding(new javafx.geometry.Insets(4))
        grid.addRow(0, new javafx.scene.control.Label('Kaynak slayt:'), sourceBox)
        javafx.scene.layout.GridPane.setHgrow(sourceBox, javafx.scene.layout.Priority.ALWAYS)

        def autoTitle = new javafx.scene.control.Label('(A) Otomatik hizala (qupath-extension-align)'); autoTitle.setStyle('-fx-font-weight: bold;')
        def autoRow = new javafx.scene.layout.HBox(8,
            new javafx.scene.control.Label('Mod:'), typeBox, new javafx.scene.control.Label('Dönüşüm:'), transformBox,
            new javafx.scene.control.Label('Downsample:'), dsField, autoBtn)
        autoRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        if (!alignPresent) autoRow.getChildren().add(installBtn)   // eklenti yoksa kurulum düğmesini (A) satırına ekle

        def manTitle = new javafx.scene.control.Label('(B) Elle matris (Interactive image alignment penceresindeki 6 sayı)'); manTitle.setStyle('-fx-font-weight: bold;')
        def manRow = new javafx.scene.layout.HBox(8, invChk, manualBtn); manRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)

        def spacer = new javafx.scene.layout.Region()
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
        def buttons = new javafx.scene.layout.HBox(8, spacer, kapatBtn)
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

        def disclaimer = new javafx.scene.control.Label('⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar; klinik karar üretmez.')
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
        Dialogs.showErrorMessage('Hizalama sihirbazı açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
