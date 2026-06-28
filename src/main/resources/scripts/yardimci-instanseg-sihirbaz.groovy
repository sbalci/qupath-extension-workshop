/**
 * Yardımcı - InstanSeg Çekirdek/Hücre Tespiti Sihirbazı (tek pencere)
 * -------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Yerel QuPath **InstanSeg eklentisi** ile, seçili anotasyon (ROI) içinde
 *   çekirdek/hücreleri derin öğrenme ile tespit eder — tek pencereden, kod
 *   yazmadan:
 *     1. InstanSeg eklentisi + bir InstanSeg modeli (klasör) kontrol edilir.
 *     2. TESPİT — seçili ROI içinde InstanSeg çalışır.
 *     3. Özet: tespit sayısı, ROI alanı (mm²), yoğunluk (tespit/mm²), süre.
 *   InstanSeg QuPath İÇİNDE Deep Java Library (DJL) ile koşar — ayrı bir
 *   Python ortamı (venv) GEREKMEZ; en sade derin öğrenme seçeneğidir.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Yalnız sayım / alan / yoğunluk üretir; sınıflandırma, grade, alt-tip veya
 *     klinik yorum YAPMAZ.
 *   • InstanSeg çıktısı bir DERİN ÖĞRENME TAHMİNİDİR; görsel doğrulama gerekir (Ek W).
 *   • StarDist sihirbazına kardeş bir araçtır (bkz. Yardımcılar → StarDist
 *     çekirdek tespiti sihirbazı; ayrıntı: Ekler → Ek H InstanSeg).
 *
 * KULLANIM:
 *   1. InstanSeg eklentisini kurun: [Extensions → Manage extensions] → InstanSeg.
 *   2. Bir model indirin: InstanSeg arayüzünden (ör. "brightfield_nuclei") ya da
 *      instanseg.pt + rdf.yaml içeren bir model klasörünü hazır bulundurun.
 *   3. Bir slayt açın; ölçülecek bölge için bir ALAN anotasyonu çizip SEÇİN.
 *   4. [Extensions → Atölye → Yardımcılar → InstanSeg çekirdek/hücre tespiti sihirbazı]
 *   5. Model klasörü + cihazı (cpu/gpu) ayarlayın → "Tespit".
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Goldsborough T ve ark. (2024) — InstanSeg. arXiv:2408.15954 ; bioRxiv 2024.09.04.611150
 *   • Resmî eklenti: github.com/qupath/qupath-extension-instanseg
 *   • Resmî doküman: qupath.readthedocs.io/en/stable/docs/deep/instanseg.html
 *   • İlham: Bankhead, I2K 2024 "QuPath for Beginners / Python Programmers"
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.objects.PathAnnotationObject
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"

// ── Kalıcı yapılandırma (yerel düğüm; WorkshopPrefs köprüsü kullanmaz) ───────
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/instanseg')
def PREF_MODEL  = 'modelDir'
def PREF_DEVICE = 'device'

def loadModelDir = { -> def p = prefs.get(PREF_MODEL, ''); return (p?.trim()) ? p.trim() : '' }
def loadDevice   = { -> def p = prefs.get(PREF_DEVICE, 'cpu'); return (p?.trim()) ? p.trim() : 'cpu' }
def saveParams = { String modelDir, String device ->
    prefs.put(PREF_MODEL, modelDir ?: '')
    prefs.put(PREF_DEVICE, device ?: 'cpu')
    try { prefs.flush() } catch (Throwable ignore) {}
}

def instansegInstalled = { -> try { Class.forName('qupath.ext.instanseg.core.InstanSeg'); return true } catch (Throwable t) { return false } }
def isValidModelDir = { String dir ->
    if (dir == null || dir.trim().isEmpty()) return false
    try {
        return qupath.ext.instanseg.core.InstanSegModel.isValidModel(java.nio.file.Paths.get(dir.trim()))
    } catch (Throwable t) {
        // Yedek: instanseg.pt + rdf.yaml dosyaları var mı?
        def d = new File(dir.trim())
        return d.isDirectory() && new File(d, 'instanseg.pt').exists() && new File(d, 'rdf.yaml').exists()
    }
}

def imageNameOf = { imageData -> def nm = imageData.getServer().getMetadata().getName() ?: 'slide'; return nm }
def nThreadsDefault = { -> Math.max(1, Runtime.getRuntime().availableProcessors() - 1) }

// ── Özet metni ───────────────────────────────────────────────────────────────
def buildResultText = { String slideName, int n, double areaMm2, double density, double secs,
                        String modelDir, String device, boolean calOk ->
    def sb = new StringBuilder()
    sb << "INSTANSEG — TESPİT\n"
    sb << "═══════════════════════════════\n\n"
    sb << "Slayt          : " << slideName << "\n"
    sb << "Model klasörü  : " << modelDir << "\n"
    sb << "Cihaz          : " << device << "\n"
    sb << "\n"
    sb << String.format(java.util.Locale.US, "Tespit sayısı   : %,d%n", n)
    if (calOk) {
        sb << String.format(java.util.Locale.US, "ROI alanı       : %.4f mm²%n", areaMm2)
        sb << String.format(java.util.Locale.US, "Yoğunluk        : %,.1f tespit/mm²%n", density)
    } else {
        sb << "ROI alanı       : (piksel kalibrasyonu yok — alan/yoğunluk hesaplanamadı)\n"
    }
    sb << String.format(java.util.Locale.US, "Süre            : %.1f sn%n", secs)
    sb << "\nŞekil + yoğunluk ölçümleri her tespite yazıldı (Measurements paneli).\n"
    sb << "InstanSeg çıktısı bir derin öğrenme tahminidir; görsel olarak doğrulayın (Ek W).\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return sb.toString()
}

// ── Headless: yapılandırmayı raporla, etkileşim yok ─────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println "Önce bir slayt açın."; return }
    println "InstanSeg eklentisi: ${instansegInstalled() ? 'bulundu' : 'BULUNAMADI — Extensions → Manage extensions → InstanSeg'}"
    println "Model: ${loadModelDir() ?: '(ayarlanmadı)'} (${isValidModelDir(loadModelDir()) ? 'geçerli' : 'geçersiz/eksik'})"
    println "Cihaz: ${loadDevice()}"
    println "Bu sihirbaz QuPath arayüzü gerektirir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
// NEED_INSTALL | READY | RUNNING | RESULT | ERROR
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef   = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def modelFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def deviceFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def render  // forward declaration

def textOf = { ref -> def f = ref.get(); return (f != null ? f.getText() : '').trim() }
def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text)
    b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    return b
}
def busyBar = { ->
    def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb
}
def copyToClipboard = { String txt ->
    def cb = javafx.scene.input.Clipboard.getSystemClipboard()
    def content = new javafx.scene.input.ClipboardContent()
    content.putString(txt ?: "")
    cb.setContent(content)
}
def pickDir = {
    try { return qupath.fx.dialogs.FileChoosers.promptForDirectory(stage, 'InstanSeg model klasörünü seç', null) }
    catch (Throwable t) {
        try { return qupath.fx.dialogs.FileChoosers.promptForDirectory('InstanSeg model klasörünü seç', (File) null) }
        catch (Throwable t2) { return null }
    }
}

// ── Tespit akışı (arka plan iş parçacığı) ───────────────────────────────────
def startDetection = {
    if (!instansegInstalled()) { errorTextRef.set('InstanSeg eklentisi yüklü değil.\nExtensions → Manage extensions → InstanSeg (bkz. Ek H).'); step.set('ERROR'); render(); return }
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('Görüntü açık değil.'); step.set('ERROR'); render(); return }
    def selected = QP.getSelectedObject()
    if (selected == null || !(selected instanceof PathAnnotationObject) || !selected.hasROI() || !selected.getROI().isArea()) {
        errorTextRef.set('Önce bir ALAN anotasyonu çizip seçin.\n  1. [P] → Polygon/Brush aracı\n  2. Ölçülecek bölgenin çevresini çizin\n  3. Anotasyon seçiliyken "Tespit".'); step.set('ERROR'); render(); return
    }
    String modelDir = textOf(modelFieldRef)
    String device   = textOf(deviceFieldRef) ?: 'cpu'
    if (!isValidModelDir(modelDir)) {
        errorTextRef.set('Geçerli bir InstanSeg model klasörü seçin.\nKlasör instanseg.pt + rdf.yaml içermeli.\nModel indirmek için: InstanSeg arayüzü (Extensions → InstanSeg).'); step.set('ERROR'); render(); return
    }
    saveParams(modelDir, device)

    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('InstanSeg çalışıyor…'); step.set('RUNNING'); render()

    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        appendLine('Slayt: ' + imageNameOf(imageData))
        appendLine('Model: ' + modelDir)
        appendLine('Cihaz: ' + device)

        if (cancelledRef.get()) { javafx.application.Platform.runLater { errorTextRef.set('İptal edildi.'); step.set('ERROR'); render() }; return }

        appendLine('InstanSeg tespiti çalışıyor (büyük ROI\'de / CPU\'da birkaç dakika sürebilir)…')
        def t0 = System.currentTimeMillis()
        try {
            def oldDetections = selected.getChildObjects().findAll { it.isDetection() }
            if (!oldDetections.isEmpty()) { selected.removePathObjects(oldDetections); appendLine('  Önceki ' + oldDetections.size() + ' tespit temizlendi.') }

            int nThreads = nThreadsDefault()
            def b = qupath.ext.instanseg.core.InstanSeg.builder()
            b = b.modelPath(modelDir)
            b = b.device(device)
            b = b.nThreads(nThreads)
            b = b.outputDetections()
            b = b.makeMeasurements(true)
            // Giriş kanalları: H&E parlak-alan modelleri (brightfield_nuclei) tüm RGB
            // kanallarını bekler. allInputChannels mevcut değilse varsayılana düşülür.
            try { b = b.allInputChannels() } catch (Throwable ignore) {}
            def instanseg = b.build()
            QP.selectObjects(selected)
            instanseg.detectObjects(imageData, [selected])
        } catch (Throwable t) {
            javafx.application.Platform.runLater { errorTextRef.set('InstanSeg tespiti başarısız:\n' + (t.getMessage() ?: t.getClass().getSimpleName()) + '\n\nOlası nedenler: model/kanal uyuşmazlığı (model H&E RGB bekliyor olabilir), eksik DJL motoru, GPU/cihaz adı ya da bellek. Konsolu kontrol edin (View → Show log).'); step.set('ERROR'); render() }
            return
        }
        def secs = (System.currentTimeMillis() - t0) / 1000.0d

        def dets = selected.getChildObjects().findAll { it.isDetection() }
        int n = dets.size()

        def cal = imageData.getServer().getPixelCalibration()
        double pw = cal.getPixelWidthMicrons(); double ph = cal.getPixelHeightMicrons()
        boolean calOk = (pw > 0.0d && ph > 0.0d)
        double areaMm2 = Double.NaN; double density = Double.NaN
        if (calOk) {
            double areaPx = selected.getROI().getArea()
            areaMm2 = areaPx * pw * ph / 1_000_000.0d
            density = (areaMm2 > 0.0d) ? (n / areaMm2) : Double.NaN
        }

        QP.fireHierarchyUpdate()
        javafx.application.Platform.runLater { try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {} }

        if (n == 0) {
            javafx.application.Platform.runLater {
                errorTextRef.set('InstanSeg 0 tespit buldu.\nOlası nedenler:\n  • Model görüntü tipine uymuyor (model H&E parlak-alan ise floresan slaytta çalışmaz)\n  • ROI çok küçük\n  • Yanlış model klasörü')
                step.set('ERROR'); render()
            }
            return
        }

        def slideName = imageNameOf(imageData)
        javafx.application.Platform.runLater {
            resultTextRef.set(buildResultText(slideName, n, areaMm2, density, secs, modelDir, device, calOk))
            step.set('RESULT'); render()
        }
    }, 'AtolyeInstanSeg-Run')
    worker.setDaemon(true); worker.start()
}

// ── READY ekranı parametre satırları ─────────────────────────────────────────
def addParamGrid = { center ->
    def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
    def mdField  = new javafx.scene.control.TextField(loadModelDir())
    def devField = new javafx.scene.control.TextField(loadDevice())
    mdField.setPrefColumnCount(34); devField.setPrefColumnCount(10)
    modelFieldRef.set(mdField); deviceFieldRef.set(devField)
    def browse = navButton('…', {
        def x = pickDir()
        if (x != null) mdField.setText(x.getAbsolutePath())
    })
    int row = 0
    qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Model klasörü:'), mdField, browse)
    qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Cihaz (cpu/gpu):'), devField)
    center.getChildren().add(grid)
}

// ── Render: her durum değişiminde sahneyi sıfırdan kurar ────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def imageData = QP.getCurrentImageData()

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10)
    center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)
    def actions = new ArrayList()

    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); center.getChildren().add(lbl) }
    def addMonoArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: '')
        ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(ta)
    }
    def addLiveLog = { ->
        def la = logAreaRef.get()
        if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) }
    }

    if (cur == 'NEED_INSTALL') {
        title.setText('InstanSeg eklentisi gerekli')
        addGuidance('Bu sihirbaz yerel QuPath **InstanSeg eklentisini** kullanır; şu anda yüklü değil.\n\n' +
            'Kurulum:\n  1. [Extensions → Manage extensions] → InstanSeg → Install\n  2. QuPath\'i yeniden başlatın\n  3. Bu sihirbazı tekrar açın\n\n' +
            'Ayrıntılı kurulum + modeller: Ekler → Ek H (InstanSeg Eklentisi).\n' +
            'Not: InstanSeg DJL ile QuPath içinde koşar — ayrı bir Python ortamı (venv) GEREKMEZ.')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('⟳ Yeniden denetle', { step.set(instansegInstalled() ? 'READY' : 'NEED_INSTALL'); render() }))
    } else if (cur == 'READY') {
        title.setText('InstanSeg — çekirdek/hücre tespiti')
        if (imageData == null) {
            addGuidance('Önce bir slayt açın ve ölçülecek bölge için bir alan anotasyonu çizip SEÇİN, sonra "⟳ Yenile".')
        } else {
            def selected = QP.getSelectedObject()
            boolean selOk = (selected != null && selected instanceof PathAnnotationObject && selected.hasROI() && selected.getROI().isArea())
            boolean modelOk = isValidModelDir(loadModelDir())
            def sb = new StringBuilder()
            sb << "Slayt           : " << imageNameOf(imageData) << "\n"
            sb << "Seçili anotasyon: " << (selOk ? 'var (alan ROI)' : 'YOK — bir alan anotasyonu seçin') << "\n"
            sb << "InstanSeg        : yüklü ✓\n"
            sb << "Model klasörü    : " << (modelOk ? 'geçerli ✓' : 'ayarlanmadı/geçersiz — aşağıdan seçin') << "\n"
            addMonoArea(sb.toString())
            addParamGrid(center)
            addGuidance('Çıktı yalnız tespit SAYISI + alan + yoğunluktur; sınıflandırma/yorum üretmez. ' +
                'Model görüntü tipine uymalı (ör. brightfield_nuclei = H&E parlak-alan).')
        }
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('⟳ Yenile', { render() }))
        def selectedNow = (imageData != null) ? QP.getSelectedObject() : null
        boolean canRun = (imageData != null && selectedNow != null && selectedNow instanceof PathAnnotationObject && selectedNow.hasROI() && selectedNow.getROI().isArea())
        def runBtn = navButton('Tespit ▶', { startDetection() }, 'Seçili ROI içinde InstanSeg tespiti çalıştırır')
        runBtn.setDisable(!canRun)
        actions.add(runBtn)
    } else if (cur == 'RUNNING') {
        title.setText(runPhaseRef.get())
        addGuidance('InstanSeg çalışıyor. İlerleme aşağıda. Büyük ROI\'lerde CPU\'da birkaç dakika sürebilir.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('İptal et', { cancelledRef.set(true) }, 'İptal bir sonraki kontrol noktasında etkilidir'))
    } else if (cur == 'RESULT') {
        title.setText('Tamamlandı ✅')
        addMonoArea(resultTextRef.get())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('↻ Yeniden çalıştır', { step.set('READY'); render() }))
    } else { // ERROR
        title.setText('Hata')
        addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Geri', { step.set(instansegInstalled() ? 'READY' : 'NEED_INSTALL'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    }

    // Alt çubuk: "Üstte tut" (sol) + disclaimer + eylem düğmeleri (sağ)
    def topChk = new javafx.scene.control.CheckBox('Üstte tut')
    topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n ->
        alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n)
    } as javafx.beans.value.ChangeListener)
    def spacer = new javafx.scene.layout.Region()
    javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8)
    bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk)
    bar.getChildren().add(spacer)
    bar.getChildren().addAll(actions)

    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(center)
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 760, 580))
}

// ── Açılış durumu ───────────────────────────────────────────────────────────
step.set(instansegInstalled() ? 'READY' : 'NEED_INSTALL')

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('InstanSeg çekirdek/hücre tespiti sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ InstanSeg çekirdek/hücre tespiti sihirbazı açıldı."
