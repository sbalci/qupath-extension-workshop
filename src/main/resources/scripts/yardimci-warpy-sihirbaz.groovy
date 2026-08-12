/**
 * Yardımcı - Warpy hizalama sihirbazı (esnek / non-rigid; Fiji köprüsü)
 * -----------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Warpy (BIOP) ile hesaplanmış ESNEK (non-rigid) bir hizalama dönüşümünü
 *   kullanarak, bir KAYNAK slayttaki anotasyonları/tespitleri şu anki (HEDEF)
 *   slayda TEK TIKLA aktarır. Afin (§ Hizalama aktarımı) dönüşümün yetmediği,
 *   kesitler arası kırışma/gerilme olan durumlar içindir (hücre düzeyi doğruluk).
 *
 * ÖNEMLİ — KAYIT (registration) FIJI'DE YAPILIR, QuPath'te DEĞİL:
 *   Warpy'nin esnek dönüşümü Fiji'de hesaplanır (BigDataViewer-Playground +
 *   elastix + isteğe bağlı BigWarp). Bu sihirbaz QuPath tarafını yapar:
 *     (a) önkoşulları denetler (Warpy eklentisi kurulu mu, Fiji yolu ayarlı mı),
 *     (b) Fiji'yi başlatır ve adımları gösterir,
 *     (c) Fiji kaydı projeye yazdıktan SONRA anotasyonları aktarır.
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
 *   4. QuPath'e dönüp HEDEF (sabit) slaydı açın; bu sihirbazla aktarın.
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
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/warpy')
String PREF_FIJI = 'fijiPath'

// Warpy, BIOP kataloğundan kurulur (elle .jar yerine önerilir; sürümünüzle eşleşen derlemeyi verir).
String BIOP_CATALOG_URL = 'https://github.com/BIOP/qupath-biop-catalog'
// Fiji (= "Fiji Is Just ImageJ") ayrı bir uygulamadır; buradan indirilir (kayıt Fiji'de yapılır).
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
                Dialogs.showMessageDialog('Bağlantı panoya kopyalandı', 'Tarayıcı otomatik açılamadı. Bağlantı panoya kopyalandı:\n' + url)
            } catch (Throwable t) { Dialogs.showMessageDialog('Bağlantı', url) }
        }
    }, 'OpenUrl').start()
}
def showWarpyInstall = {
    def steps = 'Warpy (qupath-extension-warpy, BIOP) KURULUMU — BIOP KATALOĞUNDAN önerilir:\n\n' +
        '1. Extensions → Manage extensions → Manage extension catalogs → Add.\n' +
        '2. Katalog URL\'sini yapıştırın:\n     ' + BIOP_CATALOG_URL + '\n' +
        '   (aşağıdaki düğme bu adresi açar/panoya kopyalar.)\n' +
        '3. Katalogdan Warpy\'yi kurun — QuPath sürümünüzle EŞLEŞEN derlemeyi verir (elle .jar\'dan güvenli;\n' +
        '   yanlış sürüm elle .jar yüklenmeyebilir).\n' +
        '4. QuPath\'i YENİDEN BAŞLATIN.\n\n' +
        'AYRICA Fiji gerekir (AYRI uygulama; kayıt Fiji\'de yapılır):\n' +
        '  • Fiji\'yi ' + FIJI_DOWNLOAD_URL + ' adresinden indirin ("Fiji indir" düğmesi), arşivi açın.\n' +
        '  • Fiji\'de PTBIOP güncelleme sitesini açın + elastix wrapper\'larını ayarlayın\n' +
        '    (Plugins → BIOP → Set and Check Wrappers). BigWarp Fiji çekirdeğinde gelir.\n\n' +
        'BIOP katalog sayfasını şimdi açayım mı?'
    if (Dialogs.showConfirmDialog('Warpy nasıl kurulur? (BIOP kataloğu)', steps)) openUrl(BIOP_CATALOG_URL)
}
def showFijiPlugins = {
    def s = 'FIJI EKLENTİLERİ (Warpy kaydı için) — Fiji açıkken:\n\n' +
        '1) PTBIOP güncelleme sitesi (BigDataViewer-Playground + Warpy komutları):\n' +
        '   Help → Update… → (kontrol bitince) Manage update sites → listeden PTBIOP\'u İŞARETLE →\n' +
        '   Close → Apply changes → Fiji\'yi YENİDEN BAŞLAT.\n' +
        '   ⚠ İhtiyacınız olan menü "BigDataViewer-Playground"dur (tireli) — DÜZ "BigDataViewer" DEĞİL.\n' +
        '   PTBIOP açıkken de "BigDataViewer-Playground" görünmüyorsa: Apply changes + yeniden başlat;\n' +
        '   hâlâ yoksa PTBIOP\'u kapatıp-açıp yeniden Apply edin (temiz indirme) ve Update penceresindeki hataları kontrol edin.\n' +
        '   JAR kurulu (jars/bigdataviewer-playground-*.jar VAR) AMA menü yoksa: Fiji\'nizde JAR ÇAKIŞMASI vardır\n' +
        '   (deneysel "SciJava-Ops"/scijava3 -SNAPSHOT jar\'ları klasik SciJava ile çakışır → menü kaydı düşer).\n' +
        '   Bunlar genelde "Fiji-Latest" (nightly, sites.imagej.net/Fiji) güncelleme sitesinden gelir:\n' +
        '   Manage Update Sites → "Fiji-Latest"i KAPAT (ImageJ/Fiji/PTBIOP/ElastixWrapper açık kalsın) → Apply → yeniden başlat.\n' +
        '   Window → Console başlangıç hatasını gösterir. En güvenlisi: YALNIZ PTBIOP açık TEMİZ bir Fiji (fiji.sc).\n\n' +
        '2) elastix (otomatik afin + spline; Fiji EKLENTİSİ DEĞİL, harici çalıştırılabilir):\n' +
        '   • İndir (test edilen sürüm 5.0.1): ' + ELASTIX_URL + ' → Windows zip → bir klasöre açın.\n' +
        '   • Windows\'ta çalışmazsa "Visual C++ redistributable" kurun.\n' +
        '   • Fiji → Plugins → BIOP → Set and Check Wrappers → elastix.exe ve transformix.exe yollarını verin.\n\n' +
        '3) BigWarp: Fiji çekirdeğinde gelir (kurulum gerekmez).\n\n' +
        'Sonra Fiji\'yi yeniden başlatın; Warpy komutu Plugins → BigDataViewer-Playground → Sources → Register\n' +
        'altında görünür. elastix sürüm sayfasını şimdi açayım mı?'
    if (Dialogs.showConfirmDialog('Fiji eklentileri nasıl kurulur? (PTBIOP + elastix)', s)) openUrl(ELASTIX_URL)
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
    Dialogs.showErrorMessage("Proje açık değil", "Kaynak ve hedef slayt AYNI QuPath projesinde olmalıdır.")
    return
}
if (isHeadless) {
    println "Bu sihirbaz QuPath arayüzü gerektirir (önkoşul denetimi + Fiji köprüsü + kaynak seçimi)."
    return
}

// ── Warpy aktarım işi (arka planda; kaynak hiyerarşi okuma dosya IO'su yapar) ───
def doTransfer = { Object sourceEntry, boolean addMeas, double downsample, boolean lock ->
    try {
        def W = warpyClass()
        if (W == null) return [ok:false, kind:'noclass', error:"Warpy eklentisi kurulu değil (BIOP kataloğu). Kurup QuPath'i yeniden başlatın."]
        def targetEntry = QP.getProjectEntry()
        if (targetEntry == null) return [ok:false, kind:'notarget', error:"Hedef slayt bulunamadı; aktarımın HEDEF (sabit) slaydını açın."]

        def transform, objs, transferred
        try {
            transform  = callStatic(W, 'getRealTransform', [sourceEntry, targetEntry])
            objs       = callStatic(W, 'getPathObjectsFromEntry', [sourceEntry])
            transferred = callStatic(W, 'transformPathObjects', [objs, transform])
        } catch (groovy.lang.MissingMethodException | NoSuchMethodException nsme) {
            return [ok:false, kind:'nomethod', error:"Warpy API imzası beklenenden farklı (sürüm uyumsuzluğu): " + nsme.getMessage()]
        } catch (Throwable t) {
            def cause = (t.getCause() != null) ? t.getCause() : t
            return [ok:false, kind:'compute', error:"Warpy dönüşümü uygulanamadı: " + cause.getClass().getSimpleName() + ': ' + (cause.getMessage() ?: '')]
        }

        def n = (transferred == null) ? 0 : transferred.size()
        if (n == 0) return [ok:false, kind:'empty', error:"Kaynak slaytta aktarılacak nesne yok."]

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

        // Önceki Warpy aktarımını (aynı ada sahip) temizle → yeniden çalıştırılabilir
        QP.removeObjects(QP.getAnnotationObjects().findAll { it.getName() == SENTINEL }, false)
        QP.addObjects(transferred)
        QP.fireHierarchyUpdate()
        return [ok:true, count:n, measWarn:measWarn]
    } catch (Throwable t) {
        return [ok:false, kind:'other', error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '')]
    }
}

// ── Tek pencere sihirbaz ─────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Warpy hizalama sihirbazı (esnek; Fiji köprüsü)')
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

        // — Önkoşul durumu —
        def W0 = warpyClass()
        def warpyOk = (W0 != null)
        def warpyLbl = new javafx.scene.control.Label(
            warpyOk ? '✓ Warpy eklentisi kurulu.' : '✗ Warpy eklentisi YÜKLENMEDİ — BIOP kataloğundan kurun (aşağıdaki "Warpy nasıl kurulur?").')
        warpyLbl.setWrapText(true); warpyLbl.setMaxWidth(Double.MAX_VALUE)
        warpyLbl.setStyle(warpyOk ? '-fx-text-fill: -qp-script-info-color;' : '-fx-text-fill: -qp-script-error-color;')
        // Warpy yoksa: BIOP kataloğu kurulum yardımı düğmesi.
        def warpyInstallBtn = new javafx.scene.control.Button('Warpy nasıl kurulur? (BIOP kataloğu)')
        warpyInstallBtn.setOnAction({ showWarpyInstall() })
        def warpyRow = warpyOk ? new javafx.scene.layout.HBox(8, warpyLbl)
                               : new javafx.scene.layout.HBox(8, warpyLbl, warpyInstallBtn)
        warpyRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        javafx.scene.layout.HBox.setHgrow(warpyLbl, javafx.scene.layout.Priority.ALWAYS)

        def fijiField = new javafx.scene.control.TextField(prefs.get(PREF_FIJI, ''))
        fijiField.setPromptText('ör.  C:\\Fiji.app\\ImageJ-win64.exe')
        fijiField.setMaxWidth(Double.MAX_VALUE)
        def fijiBrowse = new javafx.scene.control.Button('…')
        fijiBrowse.setOnAction({
            def x = FileChoosers.promptForFile(stage, 'Fiji/ImageJ çalıştırılabilir dosyasını seç')
            if (x != null) { fijiField.setText(x.getAbsolutePath()); prefs.put(PREF_FIJI, x.getAbsolutePath()) }
        })

        def fijiDownloadBtn = new javafx.scene.control.Button('Fiji indir')
        fijiDownloadBtn.setOnAction({ openUrl(FIJI_DOWNLOAD_URL) })
        def launchBtn = new javafx.scene.control.Button('Fiji\'yi başlat')
        launchBtn.setOnAction({
            def p = fijiField.getText()?.trim()
            if (!p || !(new File(p)).isFile()) {
                Dialogs.showErrorMessage('Fiji bulunamadı',
                    'Geçerli bir Fiji/ImageJ çalıştırılabilir dosyası seçin (… düğmesi).\n' +
                    'Fiji kurulu değilse "Fiji indir" ile fiji.sc\'den indirin (Fiji = ayrı uygulama; kayıt orada yapılır),\n' +
                    'arşivi açın ve içindeki çalıştırılabiliri (Windows: ImageJ-win64.exe) seçin.')
                return
            }
            prefs.put(PREF_FIJI, p)
            try {
                // ProcessBuilder List<String> — önceden tırnaklanmış komut dizesi KULLANMA (boşluklu yol güvenli).
                new ProcessBuilder([p]).start()
                Dialogs.showMessageDialog('Fiji başlatıldı',
                    'Fiji açılıyor. ③. adımdaki komutları izleyin (a: Create BDV Dataset (QuPath) → project.qpproj, b: Create Warpy Registration).\n' +
                    'Kayıt bittiğinde QuPath\'e dönün, HEDEF slaydı açıp ④. adımdan aktarın.')
            } catch (Throwable t) {
                Dialogs.showErrorMessage('Fiji başlatılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
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
        def refreshBtn = new javafx.scene.control.Button('↻ Kaynakları tazele')
        refreshBtn.setOnAction({ refreshSources()
            if (sourceBox.getItems().isEmpty())
                Dialogs.showMessageDialog('Warpy dönüşümü yok',
                    'Bu (HEDEF) slayt için kayıtlı Warpy dönüşümü bulunamadı. Önce Fiji\'de kaydı tamamlayıp bu slaydı açın.')
        })

        def measChk = new javafx.scene.control.CheckBox('Aktarım sonrası yoğunluk ölçümü ekle (yavaş; hata verirse aktarım yine tamamlanır)')
        measChk.setSelected(false); measChk.setWrapText(true)
        def lockChk = new javafx.scene.control.CheckBox('Aktarılanları kilitle'); lockChk.setSelected(true)

        def status = new javafx.scene.control.Label(warpyOk ? 'Hazır.' : 'Warpy eklentisi gerekli.'); status.setWrapText(true); status.setMaxWidth(Double.MAX_VALUE)
        def aktarBtn = new javafx.scene.control.Button('Anotasyonları aktar'); aktarBtn.setDefaultButton(true)
        def kapatBtn = new javafx.scene.control.Button('Kapat'); kapatBtn.setOnAction({ stage.close() })

        aktarBtn.setOnAction({
            if (!warpyOk) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('Warpy eklentisi kurulu değil.'); return }
            if (QP.getCurrentImageData() == null) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('Aktarımın HEDEF slaydını açın.'); return }
            def srcName = sourceBox.getValue()
            if (!srcName) { status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('Kaynak yok — önce Fiji\'de kaydı tamamlayıp "Kaynakları tazele"ye basın.'); return }
            def srcEntry = sourceEntries[srcName]
            aktarBtn.setDisable(true); status.setStyle(''); status.setText('Aktarılıyor…')
            def worker = new Thread({
                def r = doTransfer(srcEntry, measChk.isSelected(), 1.0d, lockChk.isSelected())
                javafx.application.Platform.runLater {
                    aktarBtn.setDisable(false)
                    if (r.ok) {
                        status.setStyle(''); status.setText(String.format(java.util.Locale.US,
                            'Tamamlandı ✅ — %,d nesne aktarıldı. Örtüşmeyi gözle doğrulayın.', r.count))
                        def msg = new StringBuilder()
                        msg << String.format(java.util.Locale.US, 'Kaynak: %s%n', srcName)
                        msg << String.format(java.util.Locale.US, 'Aktarılan nesne: %,d (kilitli; ad "%s")%n', r.count, SENTINEL)
                        if (r.measWarn) msg << ('\nNot: yoğunluk ölçümü eklenemedi (aktarım tamam): ' + r.measWarn + '\n')
                        msg << '\nEsnek dönüşüm hücre düzeyinde daha iyidir; yine de örtüşmeyi opaklık\n'
                        msg << 'kaydırıcısıyla doğrulayın. ⚠️ Yalnızca araştırma/eğitim amaçlıdır.'
                        Dialogs.showMessageDialog('Warpy aktarımı tamamlandı', msg.toString())
                    } else {
                        status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + (r.error ?: 'Bilinmeyen hata'))
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
            stepHeader('① Warpy eklentisini kur (BIOP kataloğu)'),
            warpyRow,
            instr('Warpy esnek (non-rigid) kaydı Fiji ile yapar. Eklentiyi BIOP kataloğundan kurun (elle .jar\'dan güvenli), sonra QuPath\'i yeniden başlatın.'))

        // ② Fiji uygulamasını + eklentilerini kur
        def fijiRow = new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('Fiji yolu:'), fijiField, fijiBrowse, fijiDownloadBtn)
        fijiRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        javafx.scene.layout.HBox.setHgrow(fijiField, javafx.scene.layout.Priority.ALWAYS)
        def fijiPluginsBtn = new javafx.scene.control.Button('Fiji eklentileri (PTBIOP + elastix) nasıl kurulur?')
        fijiPluginsBtn.setOnAction({ showFijiPlugins() })
        def step2 = new javafx.scene.layout.VBox(6,
            stepHeader('② Fiji uygulamasını ve eklentilerini kur'),
            instr('Fiji AYRI bir uygulamadır (fiji.sc). "Fiji indir" ile indirip arşivi açın; çalıştırılabilir yolunu "…" ile seçin. Sonra Fiji eklentilerini kurun: PTBIOP güncelleme sitesi + elastix wrapper\'ları (BigWarp Fiji ile gelir).'),
            fijiRow,
            new javafx.scene.layout.HBox(8, fijiPluginsBtn))

        // ③ Fiji'de görüntüleri aç + kaydı (registration) çalıştır
        def step3 = new javafx.scene.layout.VBox(6,
            stepHeader('③ Fiji\'de projeyi aç ve kaydı (registration) çalıştır'),
            new javafx.scene.layout.HBox(8, launchBtn),
            instr('Fiji açıldıktan sonra (menü: BigDataViewer-Playground — TİRELİ; düz "BigDataViewer" DEĞİL. Yoksa ②\'deki PTBIOP\'u kurun):\n' +
                  '  a) Plugins → BigDataViewer-Playground → BDVDataset → Create BDV Dataset (QuPath) → project.qpproj\'u açın (birim: mm).\n' +
                  '  b) Plugins → BigDataViewer-Playground → Sources → Register → QuPath - Create Warpy Registration:\n' +
                  '     rigid → elastix afin → elastix spline → (ops.) BigWarp → dönüşüm QuPath projesine YAZILIR (transform_<hedef>_<kaynak>.json).'))

        // ④ QuPath'e dön → anotasyonları aktar
        def srcRow = new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('Kaynak slayt:'), sourceBox, refreshBtn)
        srcRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        javafx.scene.layout.HBox.setHgrow(sourceBox, javafx.scene.layout.Priority.ALWAYS)
        sourceBox.setMaxWidth(Double.MAX_VALUE)
        def srcNote = new javafx.scene.control.Label('Kaynak listesi, ③\'teki Fiji kaydı projeye yazıldıktan SONRA "↻ Kaynakları tazele" ile dolar. HEDEF (sabit) slayt AÇIK olmalı; henüz kayıt yoksa liste BOŞTUR.')
        srcNote.setWrapText(true); srcNote.setMaxWidth(Double.MAX_VALUE); srcNote.setStyle('-fx-opacity: 0.8; -fx-font-size: 11px;')
        def step4 = new javafx.scene.layout.VBox(6,
            stepHeader('④ QuPath\'e dön → anotasyonları aktar'),
            instr('HEDEF (sabit) slaydı QuPath\'te açın, "↻ Kaynakları tazele" deyin, kaynağı seçip "Anotasyonları aktar"a basın. Örtüşmeyi opaklık kaydırıcısıyla doğrulayın.'),
            srcRow, srcNote,
            new javafx.scene.layout.VBox(4, measChk, lockChk),
            status)

        def disclaimer = new javafx.scene.control.Label('⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar; klinik karar üretmez.')
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
        Dialogs.showErrorMessage('Warpy sihirbazı açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
