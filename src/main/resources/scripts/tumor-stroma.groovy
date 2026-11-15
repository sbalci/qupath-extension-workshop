/**
 * Tümör/Stroma Alan Ölçümü
 * -----------------------------------
 * Hedef QuPath sürümü: 0.6.0+
 *
 * Patolog tarafından SEÇİLEN anotasyonların (seçim yoksa `Region` sınıfı)
 * birleşimi içinde Tumor ve Stroma piksel sınıflarının alanlarını ölçer.
 * İsteğe bağlı `Analysis ROI` anotasyonları için aynı ölçümleri ayrı üretir.
 *
 * ÜRETİLEN BETİMSEL ÖLÇÜMLER:
 *   • Tümör alanı (%) = Tumor / (Tumor + Stroma) × 100
 *   • Stroma alanı (%) = Stroma / (Tumor + Stroma) × 100
 *   • Tümör/Stroma oranı = Tumor / Stroma
 *
 * Betik klinik kategori, eşik, prognoz veya tedavi yorumu üretmez.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.objects.PathObjects
import qupath.lib.roi.RoiTools
import qupath.lib.scripting.QP
import qupath.opencv.ml.pixel.PixelClassifierTools
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane

import java.util.Locale

// ── Kendi başına çalışabilen, modal olmayan sonuç penceresi ─────────
def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

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

            def alwaysTop = new javafx.scene.control.CheckBox(workshopText('script.70ca64c0c43a', "Üstte tut"))
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, oldValue, newValue -> stage.setAlwaysOnTop(newValue) } as javafx.beans.value.ChangeListener
            )

            def copyBtn = new javafx.scene.control.Button(workshopText('script.a8bcca42d9ce', "Kopyala"))
            copyBtn.setOnAction({
                def clipboard = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent()
                content.putString(windowBody)
                clipboard.setContent(content)
            })

            def closeBtn = new javafx.scene.control.Button(workshopText('script.7b31a9fc4816', "Kapat"))
            closeBtn.setDefaultButton(true)
            closeBtn.setOnAction({ stage.close() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, copyBtn, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(textArea)
            def __footer = new javafx.scene.control.Label(workshopText('script.be46103cef99', "QuPath Atölye Betikleri · araştırma/eğitim amaçlı"))
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons)
            __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)
            stage.setScene(new javafx.scene.Scene(root, 820, 620))
            stage.show()
        } catch (Throwable t) {
            Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── Eklenti tercihleri; eklenti yoksa aynı sabit varsayılanlar ─────
def __wpClass = { ->
    try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') }
    catch (Throwable ignored) { null }
}
def __wpCall = { String method, Class[] signature, Object[] args, Object fallback ->
    def cls = __wpClass()
    if (cls == null) return fallback
    try { cls.getMethod(method, signature).invoke(null, args) }
    catch (Throwable ignored) { fallback }
}
def atolyeD = { String key, double fallback ->
    (double)__wpCall('dbl', [String.class, double.class] as Class[], [key, fallback] as Object[], fallback)
}
def atolyeS = { String key, String fallback ->
    (String)__wpCall('str', [String.class, String.class] as Class[], [key, fallback] as Object[], fallback)
}

def bundledClassifierJson = { ->
    try {
        Class.forName('io.github.sbalci.qupath.workshop.WorkshopResources')
            .getMethod('getTumorStromaClassifierJson')
            .invoke(null)
    } catch (Throwable ignored) { null }
}

// ── Paketli kardeş betikleri çalıştırma (eklenti varsa) ────────────
def bundledScript = { String name ->
    try {
        Class.forName('io.github.sbalci.qupath.workshop.WorkshopResources')
            .getMethod('getBundledScript', String.class).invoke(null, name)
    } catch (Throwable t) { null }
}
def launchBundled = { String name ->
    def text = bundledScript(name)
    if (text == null) return false
    def runner = new Thread({
        try { new groovy.lang.GroovyShell(this.class.classLoader).evaluate(text, name) }
        catch (Throwable err) { println "Alt betik hatası (${name}): ${err}" }
    }, "AtolyeLaunch-${name}")
    runner.setDaemon(true)
    runner.start()
    return true
}

// ── Model seçim penceresi (hub) ────────────────────────────────────
// Dönüş: [action:'MEASURE', models:[..], wholeSlide:bool] | [action:'CANCEL']
def chooseModels = { List entries, String preferName, Closure launch, boolean bundledAvailable ->
    def latch = new java.util.concurrent.CountDownLatch(1)
    def result = new java.util.concurrent.atomic.AtomicReference([action: 'CANCEL'])
    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle(workshopText('script.14e2aeb481be', 'Tümör/Stroma — model seçimi'))
            stage.setAlwaysOnTop(true)

            def header = new javafx.scene.control.Label(
                workshopText('script.867b0d1a3351', 'Hangi sınıflandırıcı(lar) ile ölçüm yapılsın? Birden fazla seçerseniz\n' +
                'sonuçlar karşılaştırmalı gösterilir.'))
            header.setWrapText(true)
            header.setStyle('-fx-font-size: 12px; -fx-padding: 4px 0;')

            def boxes = []
            def listBox = new javafx.scene.layout.VBox(4)
            if (entries.isEmpty()) {
                listBox.getChildren().add(new javafx.scene.control.Label(
                    workshopText('script.b13fefb3614b', '(Projede sınıflandırıcı yok. Aşağıdan örnek modeli kurun ya da sihirbazı açın.)')))
            } else {
                entries.each { e ->
                    def label = e.source == 'BUNDLED' ? "${e.name}  (örnek, paketli)" : e.name
                    def cb = new javafx.scene.control.CheckBox(label)
                    cb.setUserData(e.name)
                    if (e.name == preferName) cb.setSelected(true)
                    boxes << cb
                    listBox.getChildren().add(cb)
                }
            }
            def listScroll = new javafx.scene.control.ScrollPane(listBox)
            listScroll.setFitToWidth(true)
            listScroll.setPrefHeight(180)

            def scopeGroup = new javafx.scene.control.ToggleGroup()
            def rRegion = new javafx.scene.control.RadioButton(workshopText('script.888debc99627', 'Seçili anotasyon(lar)'))
            rRegion.setToggleGroup(scopeGroup); rRegion.setSelected(true)
            def rSlide = new javafx.scene.control.RadioButton(workshopText('script.5354112c42be', 'Tüm lam (seçim gerekmez — kaba tahmin)'))
            rSlide.setToggleGroup(scopeGroup)
            def scopeBox = new javafx.scene.layout.VBox(4,
                new javafx.scene.control.Label('Kapsam:'), rRegion, rSlide)
            scopeBox.setStyle('-fx-padding: 8px 0;')

            def detCb = new javafx.scene.control.CheckBox(workshopText('script.feadc3dfad73', 'Bölgeleri tespit (detection) olarak oluştur'))
            detCb.setTooltip(new javafx.scene.control.Tooltip(
                workshopText('script.480abd8be340', 'İşaretliyse sınıf bölgeleri Detections panelinde (içi boş) oluşturulur;\n' +
                'işaretsizse sınıf adıyla etiketli anotasyon olarak (varsayılan).')))

            def installBtn = new javafx.scene.control.Button(workshopText('script.ba4a31a6e293', 'Örnek modeli kur'))
            installBtn.setOnAction({
                if (!launch('yardimci-ornek-siniflandirici.groovy')) {
                    qupath.fx.dialogs.Dialogs.showMessageDialog('Eklenti gerekli',
                        workshopText('script.c6967ae055af', 'Örnek modeli kurmak için: Extensions → Atölye → Yardımcılar →\n' +
                        '"Örnek tümör/stroma sınıflandırıcısını projeye kaydet".'))
                }
                result.set([action: 'CANCEL']); stage.close()
            })
            def wizardBtn = new javafx.scene.control.Button(workshopText('script.0adf2615b6d3', 'Sihirbazı aç (yeni model)'))
            wizardBtn.setOnAction({
                if (!launch('sihirbaz.groovy')) {
                    qupath.fx.dialogs.Dialogs.showMessageDialog('Eklenti gerekli',
                        workshopText('script.3389ec6facef', 'Sihirbaz için: Extensions → Atölye → Modüller →\n' +
                        '"Tümör/Stroma sihirbazı (model kur/eğit)".'))
                }
                result.set([action: 'CANCEL']); stage.close()
            })
            def measureBtn = new javafx.scene.control.Button(workshopText('script.bd61986c8097', 'Seçilenlerle ölç'))
            measureBtn.setDefaultButton(true)
            measureBtn.setDisable(entries.isEmpty())
            measureBtn.setOnAction({
                def picked = boxes.findAll { it.isSelected() }.collect { it.getUserData() as String }
                result.set([action: 'MEASURE', models: picked, wholeSlide: rSlide.isSelected(),
                            asDetections: detCb.isSelected()])
                stage.close()
            })
            def cancelBtn = new javafx.scene.control.Button(workshopText('script.72278748133a', 'İptal'))
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({ result.set([action: 'CANCEL']); stage.close() })

            stage.setOnHidden({ latch.countDown() })

            def actionRow = new javafx.scene.layout.HBox(8, installBtn, wizardBtn)
            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def confirmRow = new javafx.scene.layout.HBox(8, spacer, cancelBtn, measureBtn)
            def bottom = new javafx.scene.layout.VBox(8, actionRow, confirmRow)
            bottom.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setTop(header)
            root.setCenter(new javafx.scene.layout.VBox(6, listScroll, scopeBox, detCb))
            root.setBottom(bottom)
            javafx.scene.layout.BorderPane.setMargin(header, new javafx.geometry.Insets(8))
            javafx.scene.layout.BorderPane.setMargin(root.getCenter(), new javafx.geometry.Insets(0, 8, 0, 8))

            stage.setScene(new javafx.scene.Scene(root, 560, 460))
            stage.show()
        } catch (Throwable t) {
            result.set([action: 'CANCEL'])
            latch.countDown()
        }
    }
    latch.await()
    return result.get()
}

// ── Ön kontroller ──────────────────────────────────────────────────
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage(workshopText('script.b4c901dc184b', 'Görüntü açık değil'), workshopText('script.3587b2e65fb9', 'Önce bir H&E lamı açın.'))
    return
}
def project = QP.getProject()
if (project == null) {
    Dialogs.showErrorMessage(workshopText('script.a38d0e6adac0', 'Proje açık değil'), workshopText('script.941800bc9075', 'Bu betik bir QuPath projesi içinde çalışır.'))
    return
}

String imageTypeName = imageData.getImageType()?.toString() ?: ''
String normalizedImageType = imageTypeName.toUpperCase(Locale.ROOT).replaceAll('[^A-Z0-9]+', '_')
if (!normalizedImageType.contains('BRIGHTFIELD_H_E')) {
    Dialogs.showErrorMessage(
        workshopText('script.be9dc7d830d5', 'Yanlış görüntü tipi'),
        "${workshopText('script.b89ccd4c78ba', 'Tümör/stroma sınıflandırıcısı Brightfield (H&E) görüntü bekler. Şu anki: ')}${imageTypeName}"
    )
    return
}

def calibration = imageData.getServer().getPixelCalibration()
double pixelWidth = calibration.getPixelWidthMicrons()
double pixelHeight = calibration.getPixelHeightMicrons()
if (!(pixelWidth > 0) || !(pixelHeight > 0)) {
    Dialogs.showErrorMessage(
        workshopText('script.a5a01874aff2', 'Kalibrasyon yok'),
        workshopText('script.a756936a8b3d', 'Piksel boyutu tanımlı değil; mm² alan ölçümleri hesaplanamaz.\n' +
        'Önce kalibrasyonu metadata veya bir kalibrasyon lamıyla doğrulayın.')
    )
    return
}

String classifierName = atolyeS('atolye.classifierName', 'tumor-stroma-RF')
double minObjectArea = atolyeD('atolye.minObjectArea', 10000.0)
double minHoleArea = atolyeD('atolye.minHoleArea', 5000.0)

// ── Model keşfi ────────────────────────────────────────────────────
def projectClassifierNames = new ArrayList(project.getPixelClassifiers().getNames())
Collections.sort(projectClassifierNames)
def bundledJson = bundledClassifierJson()
boolean bundledAvailable = bundledJson != null && !projectClassifierNames.contains(classifierName)

def selectable = []
projectClassifierNames.each { selectable << [name: it, source: 'PROJECT'] }
if (bundledAvailable) selectable << [name: classifierName, source: 'BUNDLED']

def choice
String headlessMevcut = null
if (isHeadless) {
    // Önceki sonuç: başsız modda diyalog yok — `-a mevcut=ekle|degistir`. Belirtilmezse (ve önceki sonuç
    // varsa) hiçbir şey yazılmaz. Bilinmeyen değer HER durumda durdurur (fail closed).
    def scriptArgs = []
    try { if (binding.hasVariable('args')) { def a = binding.getVariable('args'); if (a != null) scriptArgs = (a as List) } } catch (Throwable ignore) {}
    def argMap = [:]
    scriptArgs.each { a -> def sa = a?.toString() ?: ''; int ia = sa.indexOf('='); if (ia > 0) argMap[sa.substring(0, ia).trim()] = sa.substring(ia + 1).trim() }
    headlessMevcut = argMap.mevcut == null ? null : argMap.mevcut.toLowerCase(java.util.Locale.ROOT)
    if (headlessMevcut != null && !['ekle', 'degistir'].contains(headlessMevcut)) {
        println String.format(java.util.Locale.US, workshopText('script.768b9abc88f3', 'HATA: mevcut yalnız \'ekle\' ya da \'degistir\' olabilir (verilen: %s). Hiçbir şey yazılmadı.'), argMap.mevcut); return
    }
    def headlessModels = []
    if (projectClassifierNames.contains(classifierName)) headlessModels << classifierName
    else if (bundledAvailable) headlessModels << classifierName
    if (headlessModels.isEmpty()) {
        println "Sınıflandırıcı yok ('${classifierName}'). Önce model eğitin/kurun."
        return
    }
    choice = [action: 'MEASURE', models: headlessModels, wholeSlide: false]
} else {
    choice = chooseModels(selectable, classifierName, launchBundled, bundledAvailable)
}
if (choice.action != 'MEASURE') { println 'İptal / alt-betik başlatıldı.'; return }

def selectedModelNames = choice.models
if (selectedModelNames.isEmpty()) {
    Dialogs.showErrorMessage(workshopText('script.18ad4fb71877', 'Model seçilmedi'), workshopText('script.169f7925b1b7', 'En az bir sınıflandırıcı seçin.'))
    return
}
boolean wholeSlide = choice.wholeSlide
boolean asDetections = (choice.asDetections == true)

def resolveClassifier = { String name ->
    if (projectClassifierNames.contains(name)) return project.getPixelClassifiers().get(name)
    if (bundledAvailable && name == classifierName && bundledJson != null)
        return qupath.lib.io.GsonTools.getInstance().fromJson(bundledJson, qupath.lib.classifiers.pixel.PixelClassifier.class)
    return null
}

// ── Önceki sonuç: SORULMADAN silinmez (ev kuralı: sonuç yazan her betik önce sorar) ──
// Ekle (varsayılan): hiçbir şey silinmez; yeni özet "Tümör-Stroma Özet (2)", "(3)" … adını alır.
// Değiştir: yalnız bu betiğin özetleri (TAM ad ya da "ad (N)", önek eşleşmesi YOK; eski adı "TSR Özet" de)
// ve bu modülün ürettiği sınıf bölgeleri silinir; içlerindeki başka nesneler KORUNUR (keepChildren = true).
def SUMMARY_BASE = 'Tümör-Stroma Özet'
def MODE_ADD     = 'add'
def MODE_REPLACE = 'replace'
// Bu betiğin seti mi? Yalnız TAM ad ya da "ad (N)" — önek eşleşmesi YOK:
// kullanıcının "Tümör-Stroma Özet düzeltmelerim" diye adlandırdığı anotasyon süpürülmez.
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
// Özet adı: bu betiğin adı (ya da eski adı "TSR Özet") — tam ad ya da "(N)".
def isOwnSummaryName = { String name ->
    return name != null && (name == 'TSR Özet' || isOwnSetName(name, SUMMARY_BASE))
}
def prevSummaryObjects = { hierarchy ->
    if (hierarchy == null) return []
    return hierarchy.getAnnotationObjects().findAll { isOwnSummaryName(it.getName()) }
}
// Bu modülün ürettiği sınıf bölgesi: ad = sınıf adı ve sınıf boş değil; anotasyonlarda ayrıca açıklama
// "<sınıf> — Tümör/Stroma modülü (<model>)" biçiminde. Tespit olarak üretilen bölgelerde açıklama YOKTUR
// (PathDetectionObject'te getDescription/setDescription yok) → orada ad = sınıf adı ölçütü yeter (kullanıcının
// hücre tespitleri adsızdır). Yeniden sınıflandırılmış ya da yeniden adlandırılmış nesne eşleşmez.
def isOwnClassRegion = { obj ->
    String cn = obj.getPathClass()?.getName()
    if (cn == null || obj.getName() != cn) return false
    if (!(obj instanceof qupath.lib.objects.PathAnnotationObject)) return obj.isDetection()
    String d = obj.getDescription() ?: ''
    return d.startsWith(cn + ' — Tümör/Stroma modülü (') && d.endsWith(')')
}
// Bir nesnenin DOĞRUDAN çocukları arasındaki, bu modülün sınıf bölgeleri (tespit olarak üretilenler dahil).
def ownClassRegionsUnder = { parentObj ->
    return (parentObj.getChildObjectsAsArray() as List).findAll { isOwnClassRegion(it) }
}
// Özet kaynağı işareti (meta veri `atolye_ozet_kaynak`): 'arac' = bu betiğin kendi oluşturduğu özet;
// 'kullanici' = kullanıcının çizdiği tek bölge, özet olarak yeniden kullanıldığında ADI DEĞİŞTİRİLEN anotasyon.
// İşaretsiz (bu değişiklikten önceki) özet kullanıcının bölgesi olabilir → ASLA silinmez, yerinde yeniden ölçülür.
// Silme yalnız açıkça 'arac' işaretli özet için serbesttir.
def SUMMARY_SOURCE_KEY = 'atolye_ozet_kaynak'
def summarySource = { obj ->
    try { return obj.getMetadata()?.get(SUMMARY_SOURCE_KEY) } catch (Throwable ignore) { return null }
}
def markSummary = { obj, String value ->
    try { obj.getMetadata().put(SUMMARY_SOURCE_KEY, value) } catch (Throwable ignore) {}
}
def isToolMadeSummary = { obj ->
    return summarySource(obj) == 'arac'
}
// Önceki sonuç için kullanıcıya SOR (işlemden ÖNCE çağrılır; betik iş parçacığında, yukarıdaki
// Dialogs çağrıları gibi doğrudan). Dönüş: 'add' | 'replace' | null (İptal → hiçbir şey çalışmaz/yazılmaz).
def askExisting = { int nSummaries, int nRegions ->
    String optAdd = workshopText('script.1e749978a0e7', 'Ekle — öncekine dokunma, yeni bir özet olarak ekle (varsayılan)')
    String optRep = workshopText('script.320920522b08', 'Değiştir — önceki sınıf bölgeleri silinir; özet bölgeniz korunur ve aynı anotasyonda yeniden ölçülür')
    def pick = Dialogs.showChoiceDialog(workshopText('script.a43bd962f751', 'Önceki Tümör/Stroma sonucu'),
        String.format(java.util.Locale.US, workshopText('script.c30e648eac1e', 'Bu lamda zaten %,d Tümör-Stroma özeti var; Değiştir seçilirse silinebilecek sınıf bölgesi sayısı: %,d.\n\n"Ekle" seçilirse hiçbir şey silinmez; yeni sonuç ayrı bir özet olur ("Tümör-Stroma Özet (2)" gibi) ve kendi sınıf bölgelerini taşır. Önceki özetler değişmeden kalır.\n\n"Değiştir" seçilirse bu modülün ürettiği önceki Tumor/Stroma sınıf bölgeleri silinir — onlarda elle yaptığınız düzeltmeler de gider. Özet olarak kullanılan bölgeniz ASLA silinmez: çizgisi, adı ve sınıfı korunur, yalnızca ölçümleri aynı anotasyonda yeniden hesaplanır. Yalnızca aracın kendi oluşturduğu eski özet anotasyonları silinir; bu araçla işaretlenmemiş (eski sürümden kalma) özetler olduğu gibi kalır. İçlerindeki diğer nesneler (hücre tespitleri, kendi anotasyonlarınız) silinmez.\n\nİptal: hiçbir şey çalıştırılmaz, hiçbir şey yazılmaz.\n\nÖnceki Tümör/Stroma sonucu ne olsun?'), nSummaries, nRegions),
        [optAdd, optRep], optAdd)
    if (pick == null) return null
    if (pick == optRep) {
        boolean ok = Dialogs.showConfirmDialog(workshopText('script.2c478add6c37', 'Önceki sınıf bölgeleri silinecek'),
            String.format(java.util.Locale.US, workshopText('script.f7a62bf61fe4', 'Bu lamda %,d Tümör-Stroma özeti var; %,d sınıf bölgesi silinecek. Sınıf bölgelerinde elle yaptığınız düzeltmeler KAYBOLUR. Özet olarak kullanılan bölgeniz silinmez: yerinde yeniden ölçülür. Yalnızca aracın kendi oluşturduğu eski özet anotasyonları silinir; sizin çizdiğiniz ya da işaretlenmemiş özetler korunur. İçlerindeki diğer nesneler de korunur.\n\nDevam edilsin mi?'), nSummaries, nRegions))
        return ok ? MODE_REPLACE : null
    }
    return MODE_ADD
}

// ── Kapsam ROI'leri ────────────────────────────────────────────────
def annotations = QP.getAnnotationObjects()
def server = imageData.getServer()
def scopeRoi
def analysisObjects = []
def scopeRegions = []
def candidate = null   // yerinde yeniden ölçülecek önceki özet (tek seçili, bu betiğin özeti)
// Kapsam dışı: özet anotasyonu, Tumor/Stroma (çıktı/eğitim sınıfları) ve
// 'Ignore*' (boş cam/artefakt — ölçümden çıkarılacak alanlar).
def isOwnOutput = { obj ->
    String nm = obj.getName()
    def cn = obj.getPathClass()?.getName()
    isOwnSummaryName(nm) || cn in ['Tumor', 'Stroma', 'Ignore*']
}
if (wholeSlide) {
    scopeRoi = ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(),
        ImagePlane.getDefaultPlane())
} else {
    // Öncelik: patoloğun açıkça SEÇTİĞİ anotasyon(lar) — sınıf fark etmez.
    // Seçim yoksa QuPath varsayılan `Region` sınıfı anotasyonlarına düşülür.
    // Tek SEÇİLİ alan bu betiğin önceki özetiyse (ör. daha önce yeniden kullanılan kullanıcı bölgesi), o anotasyon
    // YERİNDE yeniden ölçülür (candidate); aksi halde yukarıdaki kural.
    def selAreas = QP.getSelectedObjects().findAll { it.isAnnotation() && it.getROI()?.isArea() }
    if (selAreas.size() == 1 && isOwnSummaryName(selAreas[0].getName())) {
        candidate = selAreas[0]
        scopeRegions = [candidate]
    } else {
        scopeRegions = QP.getSelectedObjects().findAll {
            it.isAnnotation() && it.getROI()?.isArea() && !isOwnOutput(it)
        }
    }
    if (scopeRegions.isEmpty()) {
        scopeRegions = annotations.findAll {
            it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Region' && !isOwnOutput(it)
        }
    }
    if (scopeRegions.isEmpty()) {
        Dialogs.showErrorMessage(
            workshopText('script.da079a56fe7d', 'Anotasyon seçili değil'),
            workshopText('script.2dd760dd3e3d', 'Seçili bölge modunda ölçülecek dokuyu bir anotasyonla çevreleyip o\n' +
            'anotasyon(lar)ı SEÇİN (sınıf fark etmez) — ya da anotasyon sınıfını\n' +
            '"Region" yapın, ya da kapsam olarak "Tüm lam" seçin.'))
        return
    }
    scopeRoi = RoiTools.union(scopeRegions.collect { it.getROI() })
    if (scopeRoi == null || scopeRoi.isEmpty() || !scopeRoi.isArea()) {
        Dialogs.showErrorMessage(workshopText('script.153b901a6cd4', 'Geçersiz sınır'), workshopText('script.6e0f821497c0', 'Seçili anotasyonlar geçerli bir alan oluşturmuyor.'))
        return
    }
    analysisObjects = annotations.findAll {
        it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Analysis ROI'
    }
}

// İsteğe bağlı: sınıfı 'Ignore*' olan alanları (boş cam/artefakt) kapsamdan çıkar
// (hem seçili bölge hem tüm lam modunda geçerli).
def ignoreObjects = annotations.findAll { it.getROI()?.isArea() && it.getPathClass()?.getName() == 'Ignore*' }
if (scopeRoi != null && !ignoreObjects.isEmpty()) {
    try {
        def ig = RoiTools.union(ignoreObjects.collect { it.getROI() })
        def diff = scopeRoi.getGeometry().difference(ig.getGeometry())
        if (diff != null && !diff.isEmpty())
            scopeRoi = qupath.lib.roi.GeometryTools.geometryToROI(diff, scopeRoi.getImagePlane())
    } catch (Throwable ignored) { }
}

def classifierAreaToMm2 = { Number value, String measurementName ->
    if (value == null) return Double.NaN
    double numeric = value.doubleValue()
    String lower = measurementName.toLowerCase(java.util.Locale.ROOT)
    if (lower.contains('µm^2') || lower.contains('μm^2') || lower.contains('um^2')) return numeric / 1_000_000.0
    if (lower.contains('mm^2')) return numeric
    return Double.NaN
}
def measureWith = { manager, tumorAreaName, stromaAreaName, roi ->
    if (roi == null || roi.isEmpty() || !roi.isArea()) {
        return [roiArea: 0.0, tumorArea: 0.0, stromaArea: 0.0, classifiedArea: 0.0,
                tumorPct: Double.NaN, stromaPct: Double.NaN, ratio: Double.NaN, coveragePct: Double.NaN]
    }
    double roiAreaMm2 = roi.getArea() * pixelWidth * pixelHeight / 1_000_000.0
    double tumorAreaMm2 = classifierAreaToMm2(manager.getMeasurementValue(roi, tumorAreaName), tumorAreaName)
    double stromaAreaMm2 = classifierAreaToMm2(manager.getMeasurementValue(roi, stromaAreaName), stromaAreaName)
    double classifiedAreaMm2 = tumorAreaMm2 + stromaAreaMm2
    double tumorPct = classifiedAreaMm2 > 0 ? 100.0 * tumorAreaMm2 / classifiedAreaMm2 : Double.NaN
    double stromaPct = classifiedAreaMm2 > 0 ? 100.0 * stromaAreaMm2 / classifiedAreaMm2 : Double.NaN
    double ratio = stromaAreaMm2 > 0 ? tumorAreaMm2 / stromaAreaMm2 : Double.NaN
    double coveragePct = roiAreaMm2 > 0 ? 100.0 * classifiedAreaMm2 / roiAreaMm2 : Double.NaN
    return [roiArea: roiAreaMm2, tumorArea: tumorAreaMm2, stromaArea: stromaAreaMm2,
            classifiedArea: classifiedAreaMm2, tumorPct: tumorPct, stromaPct: stromaPct,
            ratio: ratio, coveragePct: coveragePct]
}
def formatValue = { double value, String pattern ->
    Double.isFinite(value) ? String.format(java.util.Locale.US, pattern, value) : 'hesaplanamadı'
}

boolean multi = selectedModelNames.size() > 1
def keyPrefix = { String model -> multi ? "[${model}] " : '' }

// Özet anotasyonu: tek bir bölge seçiliyse o anotasyon YENİDEN KULLANILIR (kopya
// üretilmez); çok bölge/tüm lamda yeni bir birleşim özeti yaratılır.
boolean reuse = (!wholeSlide && scopeRegions.size() == 1)

// Önceki sonuç var mı? (1) lamdaki bu betiğin özetleri (tam ad ya da "(N)", eski ad "TSR Özet");
// (2) yeniden kullanılacak bölgenin altında önceki çalıştırmadan kalan sınıf bölgeleri.
// SORU, ölçüm ve özet nesnesine yapılan HERHANGİ bir değişiklikten ÖNCE sorulur.
def hier = imageData.getHierarchy()
def prevSummaries = prevSummaryObjects(hier)
def prevInRegion = reuse ? ownClassRegionsUnder(scopeRegions[0]) : []
def prevRegions = (prevSummaries.collectMany { ownClassRegionsUnder(it) } + prevInRegion).unique()
boolean hasPrev = !prevSummaries.isEmpty() || !prevInRegion.isEmpty()
// Değiştir'in silebileceği: YALNIZ 'arac' işaretli (bu betiğin kendi oluşturduğu) özetler ve onların sınıf bölgeleri,
// artı yerinde yeniden ölçülen özetin/bölgenin altındaki bu modülün sınıf bölgeleri. İşaretsiz ya da 'kullanici'
// işaretli özet ASLA silinmez; yerinde yeniden ölçülmüyorsa olduğu gibi kalır.
def removableSummaries = prevSummaries.findAll { it != candidate && isToolMadeSummary(it) }
def keptSummaries = prevSummaries.findAll { it != candidate && !isToolMadeSummary(it) }
def replaceRegions = (removableSummaries.collectMany { ownClassRegionsUnder(it) } + prevInRegion).unique()
String existingMode = MODE_ADD
if (hasPrev) {
    if (isHeadless) {
        if (headlessMevcut == null) {
            println String.format(java.util.Locale.US, workshopText('script.f5c14d171336', 'Bu lamda bu betiğin oluşturduğu önceki bir Tümör/Stroma sonucu zaten var (%,d). Hiçbir şey yazılmadı. Yeni bir özet eklemek için -a mevcut=ekle, önceki sonucu değiştirmek için -a mevcut=degistir ile yeniden çalıştırın.'), prevSummaries.size() + prevInRegion.size()); return
        }
        existingMode = (headlessMevcut == 'degistir') ? MODE_REPLACE : MODE_ADD
    } else {
        existingMode = askExisting(prevSummaries.size(), replaceRegions.size())
        if (existingMode == null) return
    }
}
boolean addRun = hasPrev && existingMode == MODE_ADD
boolean replaceRun = hasPrev && existingMode == MODE_REPLACE
// Ekle: kullanıcının bölgesi yeniden adlandırılmaz/ölçümleri silinmez; yeni, ayrı bir özet kurulur.
if (addRun) reuse = false
// Değiştir + yerinde yeniden ölçüm yok + silinmeyen önceki özet var: ad çakışmasın diye sıradaki ad.
String summaryName = (addRun || (replaceRun && candidate == null && !keptSummaries.isEmpty())) ? nextOwnSetName(hier, SUMMARY_BASE) : SUMMARY_BASE
def summary
if (reuse) {
    summary = scopeRegions[0]
    if (candidate == null) {
        // Kullanıcının kendi bölgesi özet olarak yeniden kullanılıyor: işaretle ('kullanici') → sonraki Değiştir silmez.
        summary.setName(summaryName); summary.setPathClass(null)
        markSummary(summary, 'kullanici')
    }
    // Yerinde yeniden ölçüm: aynı anotasyon (ad/sınıf/çizgi korunur); yalnız ölçümler sıfırlanır.
    // Önceki sınıf bölgeleri "Değiştir" seçildiyse aşağıda kaldırılır.
    summary.getMeasurementList().clear()
} else {
    summary = PathObjects.createAnnotationObject(scopeRoi)
    summary.setName(summaryName)
    markSummary(summary, 'arac')   // bu betiğin kendi oluşturduğu özet: yalnız bu silinebilir
}

def perModel = []
selectedModelNames.each { modelName ->
    def classifier = resolveClassifier(modelName)
    if (classifier == null) { println "Model çözülemedi: ${modelName}"; return }
    def manager = PixelClassifierTools.createMeasurementManager(imageData, classifier)
    def names = manager.getMeasurementNames()
    String tumorAreaName = names.find { it.startsWith('Tumor area ') }
    String stromaAreaName = names.find { it.startsWith('Stroma area ') }
    if (tumorAreaName == null || stromaAreaName == null) {
        println "Model '${modelName}' Tumor/Stroma çıktı sınıflarını içermiyor; atlandı."
        return
    }
    def aggregate = measureWith(manager, tumorAreaName, stromaAreaName, scopeRoi)

    def p = keyPrefix(modelName)
    summary.measurements["${p}ROI alanı (mm2)"] = aggregate.roiArea
    summary.measurements["${p}Tümör alanı (mm2)"] = aggregate.tumorArea
    summary.measurements["${p}Stroma alanı (mm2)"] = aggregate.stromaArea
    summary.measurements["${p}Sınıflandırılmış alan (mm2)"] = aggregate.classifiedArea
    summary.measurements["${p}Tümör alanı (%)"] = aggregate.tumorPct
    summary.measurements["${p}Stroma alanı (%)"] = aggregate.stromaPct
    summary.measurements["${p}Tümör/Stroma oranı"] = aggregate.ratio
    summary.measurements["${p}Sınıflandırılmış kapsam (%)"] = aggregate.coveragePct

    def analysisRows = []
    if (!wholeSlide) {
        // Tek bölge yeniden kullanıldığında o bölge zaten özet (toplam) ölçümünü taşır;
        // ayrıca per-bölge yazmaya gerek yok (çok bölge seçildiğinde her biri ayrı ölçülür).
        // (Ekle + yerinde ölçülebilir önceki özet seçili: o özetin eski değerleri YAZILMAZ, değişmeden kalır.)
        if (!reuse && candidate == null) scopeRegions.each { sp ->
            def m = measureWith(manager, tumorAreaName, stromaAreaName, sp.getROI())
            sp.measurements["${p}ROI alanı (mm2)"] = m.roiArea
            sp.measurements["${p}Tümör alanı (mm2)"] = m.tumorArea
            sp.measurements["${p}Stroma alanı (mm2)"] = m.stromaArea
            sp.measurements["${p}Sınıflandırılmış alan (mm2)"] = m.classifiedArea
            sp.measurements["${p}Tümör alanı (%)"] = m.tumorPct
            sp.measurements["${p}Stroma alanı (%)"] = m.stromaPct
            sp.measurements["${p}Tümör/Stroma oranı"] = m.ratio
            sp.measurements["${p}Sınıflandırılmış kapsam (%)"] = m.coveragePct
        }
        analysisObjects.eachWithIndex { analysis, index ->
            def clipped = RoiTools.intersection([analysis.getROI(), scopeRoi])
            def m = measureWith(manager, tumorAreaName, stromaAreaName, clipped)
            analysis.measurements["${p}ROI alanı (mm2)"] = m.roiArea
            analysis.measurements["${p}Tümör alanı (mm2)"] = m.tumorArea
            analysis.measurements["${p}Stroma alanı (mm2)"] = m.stromaArea
            analysis.measurements["${p}Sınıflandırılmış alan (mm2)"] = m.classifiedArea
            analysis.measurements["${p}Tümör alanı (%)"] = m.tumorPct
            analysis.measurements["${p}Stroma alanı (%)"] = m.stromaPct
            analysis.measurements["${p}Tümör/Stroma oranı"] = m.ratio
            analysis.measurements["${p}Sınıflandırılmış kapsam (%)"] = m.coveragePct
            analysisRows << [name: analysis.getName() ?: "Analysis ROI ${index + 1}", metrics: m]
        }
    }
    perModel << [model: modelName, aggregate: aggregate, analysisRows: analysisRows, classifier: classifier]
}

if (perModel.isEmpty()) {
    Dialogs.showErrorMessage(workshopText('script.19182065eeb6', 'Ölçüm yapılamadı'), workshopText('script.c84bbaa3e48c', 'Seçilen modellerin hiçbiri Tumor/Stroma çıktısı üretmedi.'))
    return
}

summary.measurements['Seçilen model sayısı'] = perModel.size() as double
summary.measurements['Ölçülen bölge sayısı'] = (wholeSlide ? 0 : scopeRegions.size()) as double
summary.measurements['Analysis ROI sayısı'] = (wholeSlide ? 0 : analysisObjects.size()) as double
summary.setLocked(true)
// Değiştir (kullanıcı seçti ve onayladı): bu betiğin ÖNCEKİ özetleri ve sınıf bölgeleri silinir. Hepsi
// yukarıda (yeni özet eklenmeden önce) alındı. İçlerindeki başka nesneler (hücre tespitleri, kullanıcının
// anotasyonları) keepChildren = true ile KORUNUR — önceki removeObjectsAndDescendants onları da silerdi.
int removedSummaries = 0
int removedRegions = 0
if (replaceRun) {
    // Özet (summary) hiçbir koşulda silinmez; kullanıcının bölgesi/işaretsiz özet de silinmez (yukarıya bak).
    def oldSummaries = removableSummaries.findAll { it != summary }
    def oldRegions = replaceRegions.findAll { it != summary }
    if (!oldRegions.isEmpty()) QP.removeObjects(oldRegions, true)
    if (!oldSummaries.isEmpty()) QP.removeObjects(oldSummaries, true)
    removedSummaries = oldSummaries.size()
    removedRegions = oldRegions.size()
}
if (!reuse) QP.addObjects([summary])

// Görsel kontrol için sınıf bölgeleri (yalnız birincil model): piksel sınıflandırıcının
// ürettiği çok sayıda küçük poligon, her SINIF için TEK bölgeye birleştirilir ve sınıf
// adıyla (Tumor / Stroma …) ETİKETLENİR — ad = sınıf olduğundan 'Show names' sınıfı yazar.
// asDetections=true ise bölgeler Detections paneline (içi boş) yazılır; anotasyon listesi
// temiz kalır. SELECT_NEW kullanılmaz (seçim sarı çizilir, sınıf rengini gizler).
def primary = perModel[0]
def before = QP.getAnnotationObjects() as Set
QP.selectObjects(summary)
if (reuse) {
    // Yeniden kullanılan özet KULLANICININ KENDİ anotasyonudur. DELETE_EXISTING onun TÜM alt
    // ER/PR…) sessizce yok olurdu; bu yüzden burada KULLANILMAZ. Bu modülün önceki sınıf bölgeleri
    // yalnız kullanıcı "Değiştir"i seçip onayladıysa, yukarıda kaldırıldı; başka alt nesneye dokunulmaz.
    QP.createAnnotationsFromPixelClassifier(primary.classifier, minObjectArea, minHoleArea)
} else {
    QP.createAnnotationsFromPixelClassifier(primary.classifier, minObjectArea, minHoleArea,
        'DELETE_EXISTING')
}
def generated = QP.getAnnotationObjects().findAll { !before.contains(it) && it.getPathClass() != null }
def byClass = generated.groupBy { it.getPathClass().getName() }
// `generated` = bu çalıştırmanın GEÇİCİ ara nesneleri. Referansla ayrılır: `before` (sınıflandırıcı çağrısından
// hemen önce hiyerarşideki tüm anotasyonlar, kimlik tabanlı küme) içinde OLMAYAN anotasyonlar — ad ya da
// sınıfla aranmaz. Çıktı, birleştirilmiş bölgelere aşağıda dönüştürülür; ara nesneler silinir. keepChildren = true:
// içlerine yerleşmiş başka nesne olsaydı bile silinmez.
if (!generated.isEmpty()) QP.removeObjects(generated, true)
double smoothPx = (pixelWidth > 0 ? 3.0 / pixelWidth : 6.0)
def plane = scopeRoi.getImagePlane()
def outputs = []
byClass.each { className, objs ->
    try {
        def geom = objs.collect { it.getROI().getGeometry() }.inject { a, b -> a.union(b) }
        if (geom == null || geom.isEmpty()) return
        if (!ignoreObjects.isEmpty()) {   // Ignore* alanlarını görsel bölgelerden de çıkar
            try { def clip = geom.intersection(scopeRoi.getGeometry()); if (clip != null && !clip.isEmpty()) geom = clip }
            catch (Throwable ignored) { }
        }
        def simp = org.locationtech.jts.simplify.TopologyPreservingSimplifier.simplify(geom, smoothPx)
        if (simp != null && !simp.isEmpty()) geom = simp
        def roi = qupath.lib.roi.GeometryTools.geometryToROI(geom, plane)
        def obj = asDetections
            ? PathObjects.createDetectionObject(roi, objs[0].getPathClass())
            : PathObjects.createAnnotationObject(roi, objs[0].getPathClass())
        obj.setName(className)   // 'Show names' sınıf adını yazar (Tumor / Stroma)
        try { obj.setDescription("${className} — Tümör/Stroma modülü (${primary.model})") } catch (Throwable ignored) { }
        if (!asDetections) obj.setLocked(true)
        outputs << obj
    } catch (Throwable ignored) { }
}
if (!outputs.isEmpty()) summary.addChildObjects(outputs)
imageData.getHierarchy().getSelectionModel().clearSelection()
QP.fireHierarchyUpdate()

def body = new StringBuilder()
body << 'TÜMÖR/STROMA ALAN ÖLÇÜMÜ\n'
body << '════════════════════════════════════════\n\n'
body << "Kapsam              : ${wholeSlide ? 'Tüm lam' : 'Seçili anotasyon(lar)'}\n"
if (wholeSlide) body << 'Not: Tüm lam modunda boş cam/arka plan da Tumor/Stroma sayılabilir (kaba tahmin).\n'
body << "Lamdaki bindirme  : ${primary.model}\n\n"

if (perModel.size() == 1) {
    def a = primary.aggregate
    body << "Sınıflandırıcı      : ${primary.model}\n"
    body << String.format(java.util.Locale.US, 'ROI alanı             : %.3f mm²%n', a.roiArea)
    body << String.format(java.util.Locale.US, 'Tümör alanı           : %.3f mm²%n', a.tumorArea)
    body << String.format(java.util.Locale.US, 'Stroma alanı          : %.3f mm²%n', a.stromaArea)
    body << "Tümör alanı (%)      : ${formatValue(a.tumorPct, '%.2f%%')}\n"
    body << "Stroma alanı (%)     : ${formatValue(a.stromaPct, '%.2f%%')}\n"
    body << "Tümör/Stroma oranı   : ${formatValue(a.ratio, '%.4f')}\n"
    body << "Sınıflandırılmış kapsam: ${formatValue(a.coveragePct, '%.2f%%')}\n"
    primary.analysisRows.each { row ->
        def m = row.metrics
        body << "\n${row.name}: Tümör ${formatValue(m.tumorPct, '%.2f%%')} | " +
                "Stroma ${formatValue(m.stromaPct, '%.2f%%')} | T/S ${formatValue(m.ratio, '%.4f')}\n"
    }
} else {
    body << 'KARŞILAŞTIRMA (kapsam birleşimi)\n'
    body << '────────────────────────────────────────\n'
    body << String.format(java.util.Locale.US, '%-22s %9s %9s %8s%n', 'Model', 'Tümör%', 'Stroma%', 'T/S')
    perModel.each { pm ->
        def a = pm.aggregate
        body << String.format(java.util.Locale.US, '%-22s %9s %9s %8s%n',
            (pm.model.length() > 22 ? pm.model.substring(0, 22) : pm.model),
            formatValue(a.tumorPct, '%.2f'), formatValue(a.stromaPct, '%.2f'),
            formatValue(a.ratio, '%.3f'))
    }
}

if (addRun) {
    // Ekle: önceki sonuç duruyor; seçili bölgelere yazılan ölçümler BU çalıştırmayı gösterir.
    def prevNames = prevSummaries.collect { it.getName() }.unique().sort()
    if (!prevInRegion.isEmpty()) prevNames << (scopeRegions[0].getName() ?: '-')
    body << '\n' << String.format(java.util.Locale.US, workshopText('script.eebc097a5825', 'ÖNCEKİ SONUÇ DURUYOR\nÖzet anotasyonu: %s. Bu lamda önceki Tümör/Stroma sonucu da duruyor: %s. Hiçbir şey silinmedi; eski sonucu kaldırmak için o özet anotasyonunu seçip silin ya da bir sonraki çalıştırmada "Değiştir"i seçin. Seçtiğiniz bölge anotasyonlarına ölçüm yazıldıysa o ölçümler BU çalıştırmayı gösterir; önceki özet anotasyonu eski değerleri göstermeye devam eder.'), summaryName, prevNames.join(', ')) << '\n'
} else if (replaceRun) {
    body << '\n' << String.format(java.util.Locale.US, workshopText('script.04273d6ac80a', 'ÖNCEKİ SONUÇ DEĞİŞTİRİLDİ\n%,d sınıf bölgesi ve aracın kendi oluşturduğu %,d eski özet anotasyonu silindi (içlerindeki diğer nesneler korundu). Sizin çizdiğiniz bölge ya da işaretlenmemiş özet silinmedi; özet bölgesi korundu ve ölçümler onda yeniden hesaplandı.'), removedRegions, removedSummaries) << '\n'
}
body << '\nQC NOTLARI\n'
body << '────────────────────────────────────────\n'
body << '• Payda yalnız Tumor + Stroma olarak sınıflandırılan alandır.\n'
body << '• Çoklu modelde nesne ölçümleri model adıyla ön-eklenir ([model] ...).\n'
body << '• Sınıf bölgeleri her sınıf için TEK, sınıf adıyla etiketli birleşik bölgedir\n'
body << '  (yalnız birincil model). İçi boş tespit (detection) olarak da üretilebilir.\n'
body << '• Tek bölge seçiliyse o anotasyon özet olur (kopya üretilmez).\n'
body << '• İpucu: anotasyon dolgusunu Shift+F ile kapatıp sınıf sınırlarını görebilirsiniz.\n\n'
body << 'Bu çıktı betimsel bir ölçümdür; klinik yorum veya kategori üretmez.\n'
body << '⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.'

showResultWindow('Tümör/Stroma ölçümü', body.toString())
println String.format(java.util.Locale.US, 'Tümör/Stroma modülü tamamlandı: %d model, kapsam=%s',
    perModel.size(), (wholeSlide ? 'slide' : 'region'))

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
