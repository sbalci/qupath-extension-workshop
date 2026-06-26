/**
 * Yardımcı - Cellpose Hücre / Çekirdek Tespiti Sihirbazı (tek pencere)
 * --------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   BIOP `qupath-extension-cellpose` eklentisinin `Cellpose2D` builder'ını
 *   tek bir pencereden kurar ve seçili anotasyon(lar) içinde hücre/çekirdek
 *   tespiti çalıştırır — builder betiğini elle yazmadan. Aile (cyto3 / cpsam /
 *   Omnipose), model, çap, kanal modu (brightfield İHK dekonvolüsyon ya da
 *   basit/floresan) ve flow/cellprob eşikleri pencereden seçilir.
 *   Derin öğrenme QuPath DIŞINDA, BIOP eklentisinin yapılandırdığınız Python
 *   venv'inde koşar; bu betik yalnızca builder'ı kurar ve sonucu raporlar.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Çıktı: tespit edilen hücre nesneleri + adet + (kalibreyse) yoğunluk
 *     (hücre/mm²) + (opsiyonel) İHK yoğunluk sınıflandırması (0/1+/2+/3+ adet).
 *   • Hiçbir hastalık/tanı sınıfı, grade ya da klinik karar üretmez; segmentasyon
 *     bir derin öğrenme tahminidir ve görsel doğrulama gerekir (Ek W).
 *
 * KULLANIM:
 *   1. BIOP Cellpose eklentisini kurun + [Edit → Preferences → Cellpose/Omnipose]
 *      altında en az "Cellpose 'python.exe' location" alanını doldurun.
 *      Bkz. Ekler → Ek F (Cellpose) § kurulum.
 *   2. Bir slayt açın; tümör (epitel) içeren bir anotasyon çizip SEÇİN (sarı kenar).
 *   3. [Extensions → Atölye → Yardımcılar → Cellpose hücre/çekirdek tespiti sihirbazı]
 *   4. Parametreleri seçip "Çalıştır" → sonuç aynı pencerede güncellenir.
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • Cellpose: Stringer ve ark. 2021 — doi:10.1038/s41592-020-01018-x
 *   • Cellpose 2.0 (kendi modelini eğit): Pachitariu & Stringer 2022 — doi:10.1038/s41592-022-01663-4
 *   • Omnipose: Cutler ve ark. 2022 — doi:10.1038/s41592-022-01639-4
 *   • Eklenti + builder JavaDoc: github.com/BIOP/qupath-extension-cellpose
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Atölye ayarları: eklenti yüklüyse oku, yoksa atölye varsayılanı kullanılır ──
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeD = { String k, double  d -> (double)  __wpCall('dbl',  [String.class, double.class]  as Class[], [k, d] as Object[], d) }
def atolyeS = { String k, String  d -> (String)  __wpCall('str',  [String.class, String.class]  as Class[], [k, d] as Object[], d) }
def atolyeI = { String k, int     d -> (int)     __wpCall('intg', [String.class, int.class]     as Class[], [k, d] as Object[], d) }

def MONO = "-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;"

// ── BIOP Cellpose eklentisi var mı? (parse-zamanı bağımlılık olmadan) ──
def cellposeHere = false
try {
    Class.forName('qupath.ext.biop.cellpose.Cellpose2D', false, this.class.classLoader)
    cellposeHere = true
} catch (Throwable ignored) { /* kurulu değil */ }

// ── Kalıcı yapılandırma: java.util.prefs (eklenti JAR'ı olmadan da çalışır) ──
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/cellpose')
def cfg = [
    family       : prefs.get('family',       atolyeS('atolye.cellposeFamily', 'cyto3')),
    model        : prefs.get('model',        atolyeS('atolye.cellposeModel',  'cyto3')),
    diameter     : prefs.get('diameter',     String.valueOf(atolyeI('atolye.cellposeDiameter', 25))),
    pixelSize    : prefs.get('pixelSize',    String.valueOf(atolyeD('atolye.pixelSize', 0.5))),
    cellExpansion: prefs.get('cellExpansion',String.valueOf(atolyeD('atolye.cellExpansionNuclear', 5.0))),
    flow         : prefs.get('flow',         String.valueOf(atolyeD('atolye.cellposeFlow', 0.4))),
    cellprob     : prefs.get('cellprob',     String.valueOf(atolyeD('atolye.cellposeCellprob', 0.0))),
    channelMode  : prefs.get('channelMode',  'brightfield'),
    ihcBin       : prefs.get('ihcBin',       'false'),
    compartment  : prefs.get('compartment',  'Cell: DAB OD mean')
]

def parseIntOr    = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }
def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().trim()) } catch (Throwable t) { return d } }

// ── Ön kontrol: görüntü açık mı? ──
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    def msg = "Önce bir slayt açın, sonra bu sihirbazı tekrar çalıştırın."
    if (isHeadless) { println msg } else { Dialogs.showErrorMessage("Görüntü açık değil", msg) }
    return
}

// ──────────────────────────────────────────────────────────────
// Tespit motoru — pencere kapanmadan (parametreleri değiştirip) tekrar çağrılır.
//   family       : 'cyto3' | 'cpsam' | 'omnipose'
//   model        : model adı (cpsam'da otomatik "cpsam")
//   channelMode  : 'brightfield' (dekonvolüsyon) | 'simple' (gri/floresan)
//   diameter(px), pixelSize(µm), flow, cellprob, cellExpansion(µm)
//   ihcBin       : true ise compartment OD'ye göre 0/1+/2+/3+ binning
//   t1,t2,t3     : İHK OD eşikleri (yalnız ihcBin true ise)
//   compartment  : "Cell|Membrane|Nucleus|Cytoplasm: DAB OD mean"
// Dönüş: [ok:true, text:<sonuç>] | [ok:false, error:<mesaj>]
// ──────────────────────────────────────────────────────────────
def runDetection = { String family, String model, String channelMode,
                     int diameter, double pixelSize, double flow, double cellprob, double cellExpansion,
                     boolean ihcBin, double t1, double t2, double t3, String compartment ->

    if (!cellposeHere)
        return [ok:false, error:'BIOP Cellpose eklentisi bulunamadı. Kurulum: Ek F § 2 (Extensions → Manage extension catalogs → BIOP).']

    def selected = QP.getSelectedObjects().findAll { it.isAnnotation() }
    if (selected.isEmpty())
        return [ok:false, error:'Önce bir bölgeyi anotasyonla çevreleyip SEÇİN (kenarı sarı görünür). Sihirbaz yalnızca seçili anotasyon içinde çalışır.']

    // RE-RUN GÜVENLİĞİ: seçili anotasyonlardaki mevcut tespitleri temizle (birikim önlenir)
    selected.each { ann ->
        def existing = ann.getChildObjects().findAll { it.isDetection() }
        if (!existing.isEmpty()) QP.removeObjects(existing, false)
    }

    // Builder zincirini Groovy tarafında kur (opsiyonel parçalar koşullu eklenir).
    def modelName = (family == 'cpsam') ? 'cpsam' : model
    def envFlag = ''
    if (family == 'cpsam')        envFlag = '.useCellposeSAM()'
    else if (family == 'omnipose') envFlag = '.useOmnipose()'

    def chain = new StringBuilder()
    chain << "Cellpose2D.builder(\"${modelName}\")\n"
    if (envFlag) chain << "        ${envFlag}\n"
    chain << "        .pixelSize(${pixelSize})\n"
    if (channelMode == 'brightfield') {
        // Brightfield H-DAB → renk dekonvolüsyonu + DAB(1)/Hematoxylin(0) çıkar.
        chain << "        .preprocess(ImageOps.Channels.deconvolve(stains), ImageOps.Channels.extract(1, 0))\n"
        chain << "        .cellposeChannels(1, 2)\n"
    }
    chain << "        .normalizePercentilesGlobal(0.1, 99.8, 10)\n"
    chain << "        .diameter(${diameter})\n"
    chain << "        .flowThreshold(${flow})\n"
    chain << "        .cellprobThreshold(${cellprob})\n"
    if (cellExpansion > 0) chain << "        .cellExpansion(${cellExpansion})\n"
    chain << "        .measureShape()\n"
    chain << "        .measureIntensity()\n"
    chain << "        .build()"

    // İç betiği ayrı GroovyShell'de çalıştır: `import qupath.ext.biop.cellpose.Cellpose2D`
    // yalnızca eklenti classpath'te ise parse olabildiği için iç bloğa kapatıyoruz.
    def innerScript = """
        import qupath.ext.biop.cellpose.Cellpose2D
        import qupath.lib.scripting.QP
        import qupath.opencv.ops.ImageOps

        def stains = QP.getCurrentImageData().getColorDeconvolutionStains()
        def cellpose = ${chain.toString()}
        cellpose.detectObjects(QP.getCurrentImageData(), QP.getSelectedObjects())
    """

    def t0 = System.currentTimeMillis()
    try {
        new groovy.lang.GroovyShell(this.class.classLoader).evaluate(innerScript)
    } catch (Throwable cellErr) {
        def reason = cellErr.getMessage() ?: cellErr.getClass().getSimpleName()
        // Kısmi nesneler kalmış olabilir — temizle.
        selected.each { ann ->
            def partial = ann.getChildObjects().findAll { it.isDetection() }
            if (!partial.isEmpty()) QP.removeObjects(partial, true)
        }
        return [ok:false, error:"Cellpose çalıştırılamadı: ${reason}\n\nİpuçları: Python yolu ayarlı mı (Edit → Preferences → Cellpose/Omnipose)? Model ilk çalıştırmada indiriliyor olabilir. cpsam ayrı venv ister."]
    }

    // Opsiyonel İHK yoğunluk binning
    if (ihcBin) {
        try { QP.setCellIntensityClassifications(compartment, t1, t2, t3) }
        catch (Throwable bErr) { /* ölçüm yoksa binning atlanır; tespit sonucu geçerli */ }
    }

    double elapsed = (System.currentTimeMillis() - t0) / 1000.0

    // Hücreleri say (yalnızca tespit nesneleri)
    def cells = []
    selected.each { ann -> cells.addAll(ann.getChildObjects().findAll { it.isDetection() }) }
    int total = cells.size()

    // Sınıf dağılımı (binning açıksa)
    int n0 = 0, n1 = 0, n2 = 0, n3 = 0
    if (ihcBin) {
        cells.each { c ->
            def cls = c.getPathClass()?.getName() ?: ''
            if (cls.contains('3+'))      n3++
            else if (cls.contains('2+')) n2++
            else if (cls.contains('1+')) n1++
            else                          n0++
        }
    }

    // Yoğunluk (hücre/mm²) — yalnız kalibreyse
    def cal = imageData.getServer().getPixelCalibration()
    boolean calibrated = cal.getPixelWidthMicrons() > 0
    double areaMm2 = 0.0
    if (calibrated) {
        selected.each { ann ->
            areaMm2 += ann.getROI().getScaledArea(cal.getPixelWidthMicrons(), cal.getPixelHeightMicrons()) / 1e6
        }
    }
    double densityPerMm2 = (calibrated && areaMm2 > 0) ? total / areaMm2 : 0.0

    // ── Sonuç dizesi ──
    def famLabel = (family == 'cpsam') ? "Cellpose-SAM (cpsam)" : (family == 'omnipose' ? "Omnipose (${model})" : "Cellpose v3 (${model})")
    def chanLabel = (channelMode == 'brightfield') ? "brightfield İHK (dekonvolüsyon · DAB/H)" : "basit (gri/floresan)"

    def sb = new StringBuilder()
    sb << "Cellpose tespiti tamamlandı.\n\n"
    sb << "═══════════════════════════════════════════════════\n"
    sb << "  Aile / model     : ${famLabel}\n"
    sb << "  Kanal modu       : ${chanLabel}\n"
    sb << "  Parametreler     : diameter ${diameter} px · pixelSize ${String.format(java.util.Locale.US, '%.2f', pixelSize)} µm\n"
    sb << "                     flow ${String.format(java.util.Locale.US, '%.2f', flow)} · cellprob ${String.format(java.util.Locale.US, '%.2f', cellprob)} · expansion ${String.format(java.util.Locale.US, '%.1f', cellExpansion)} µm\n"
    sb << "═══════════════════════════════════════════════════\n"
    sb << "  Toplam hücre     : ${String.format(java.util.Locale.US, '%,d', total)}\n"
    if (calibrated && areaMm2 > 0)
        sb << "  Alan / yoğunluk  : ${String.format(java.util.Locale.US, '%.3f', areaMm2)} mm² · ${String.format(java.util.Locale.US, '%,.0f', densityPerMm2)} hücre/mm²\n"
    else
        sb << "  Alan / yoğunluk  : kalibre değil (piksel boyutu ayarlı değil → Yardımcılar → Kalibrasyon)\n"
    if (ihcBin) {
        def pct = { int cnt -> total > 0 ? 100.0 * cnt / total : 0.0 }
        sb << "  İHK binning (${compartment}):\n"
        sb << "    0  (negatif)   : ${String.format(java.util.Locale.US, '%,d', n0)}  (%${String.format(java.util.Locale.US, '%.1f', pct(n0))})\n"
        sb << "    1+ (zayıf)     : ${String.format(java.util.Locale.US, '%,d', n1)}  (%${String.format(java.util.Locale.US, '%.1f', pct(n1))})\n"
        sb << "    2+ (orta)      : ${String.format(java.util.Locale.US, '%,d', n2)}  (%${String.format(java.util.Locale.US, '%.1f', pct(n2))})\n"
        sb << "    3+ (güçlü)     : ${String.format(java.util.Locale.US, '%,d', n3)}  (%${String.format(java.util.Locale.US, '%.1f', pct(n3))})\n"
    }
    sb << "  Süre             : ${String.format(java.util.Locale.US, '%.1f', elapsed)} sn\n\n"
    sb << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

    println "Cellpose: ${total} hücre · ${famLabel} · ${String.format(java.util.Locale.US, '%.1f', elapsed)} sn"
    return [ok:true, text:sb.toString()]
}

// ── Headless: seçili bölgede atölye varsayılanlarıyla tek sefer çalıştır + yazdır ──
if (isHeadless) {
    def r = runDetection(
        cfg.family, cfg.model, cfg.channelMode,
        parseIntOr(cfg.diameter, 25), parseDoubleOr(cfg.pixelSize, 0.5),
        parseDoubleOr(cfg.flow, 0.4), parseDoubleOr(cfg.cellprob, 0.0), parseDoubleOr(cfg.cellExpansion, 5.0),
        false, 0.15, 0.40, 0.70, cfg.compartment)
    println r.ok ? r.text : ("Hata: " + r.error)
    return
}

// ──────────────────────────────────────────────────────────────
// Tek pencere: ayarla → Çalıştır → sonuç → (gerekirse) tekrar
// ──────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        def stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Cellpose hücre / çekirdek tespiti sihirbazı')
        stage.setAlwaysOnTop(true)

        def title = new javafx.scene.control.Label('Cellpose hücre / çekirdek tespiti')
        title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')

        def banner = new javafx.scene.control.Label(cellposeHere
            ? '✅ BIOP Cellpose eklentisi bulundu. Python yolu: Edit → Preferences → Cellpose/Omnipose.'
            : '⚠ BIOP Cellpose eklentisi bulunamadı. Kurulum: Ek F § 2. Bu sihirbaz ölçüm yapamaz.')
        banner.setWrapText(true)
        banner.setStyle(cellposeHere ? '' : '-fx-text-fill: -qp-script-error-color;')

        def info = new javafx.scene.control.Label(
            'Bir bölge anotasyonu çizip SEÇİN (kenarı sarı), parametreleri seçip "Çalıştır".\n' +
            'Segmentasyon QuPath dışındaki Python (venv) ortamında koşar; ilk çalıştırmada model indirilebilir.')
        info.setWrapText(true)

        def familyBox = new javafx.scene.control.ChoiceBox()
        familyBox.getItems().addAll('cyto3', 'cpsam', 'omnipose'); familyBox.setValue(cfg.family)
        def modelField = new javafx.scene.control.TextField(cfg.model); modelField.setPrefWidth(160)
        def channelBox = new javafx.scene.control.ChoiceBox()
        channelBox.getItems().addAll('brightfield', 'simple'); channelBox.setValue(cfg.channelMode)

        def spDia  = new javafx.scene.control.Spinner(0, 200, parseIntOr(cfg.diameter, 25), 1)
        def spPx   = new javafx.scene.control.Spinner(0.1, 4.0, parseDoubleOr(cfg.pixelSize, 0.5), 0.05)
        def spFlow = new javafx.scene.control.Spinner(0.0, 3.0, parseDoubleOr(cfg.flow, 0.4), 0.05)
        def spProb = new javafx.scene.control.Spinner(-6.0, 6.0, parseDoubleOr(cfg.cellprob, 0.0), 0.5)
        def spExp  = new javafx.scene.control.Spinner(0.0, 20.0, parseDoubleOr(cfg.cellExpansion, 5.0), 0.5)
        [spDia, spPx, spFlow, spProb, spExp].each { it.setEditable(true); it.setPrefWidth(110) }

        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(6); grid.setPadding(new javafx.geometry.Insets(6))
        grid.addRow(0, new javafx.scene.control.Label('Aile'),              familyBox)
        grid.addRow(1, new javafx.scene.control.Label('Model'),             modelField)
        grid.addRow(2, new javafx.scene.control.Label('Kanal modu'),        channelBox)
        grid.addRow(3, new javafx.scene.control.Label('Çap diameter (px)'), spDia)
        grid.addRow(4, new javafx.scene.control.Label('Piksel boyutu (µm)'),spPx)
        grid.addRow(5, new javafx.scene.control.Label('flowThreshold'),     spFlow)
        grid.addRow(6, new javafx.scene.control.Label('cellprobThreshold'), spProb)
        grid.addRow(7, new javafx.scene.control.Label('Hücre genişletme (µm)'), spExp)

        // İHK yoğunluk binning (opsiyonel, gelişmiş)
        def binChk = new javafx.scene.control.CheckBox('İHK yoğunluk binning uygula (0/1+/2+/3+)')
        binChk.setSelected(Boolean.parseBoolean(cfg.ihcBin))
        def compartBox = new javafx.scene.control.ChoiceBox()
        compartBox.getItems().addAll('Cell: DAB OD mean', 'Membrane: DAB OD mean', 'Nucleus: DAB OD mean', 'Cytoplasm: DAB OD mean')
        compartBox.setValue(cfg.compartment)
        def spT1 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.membrane1', 0.15), 0.01)
        def spT2 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.membrane2', 0.40), 0.01)
        def spT3 = new javafx.scene.control.Spinner(0.0, 2.0, atolyeD('atolye.membrane3', 0.70), 0.01)
        [spT1, spT2, spT3].each { it.setEditable(true); it.setPrefWidth(90) }
        def binGrid = new javafx.scene.layout.GridPane()
        binGrid.setHgap(8); binGrid.setVgap(6); binGrid.setPadding(new javafx.geometry.Insets(6))
        binGrid.addRow(0, binChk)
        binGrid.addRow(1, new javafx.scene.control.Label('Bölme (compartment)'), compartBox)
        binGrid.addRow(2, new javafx.scene.control.Label('OD eşikleri 1+/2+/3+'),
            new javafx.scene.layout.HBox(6, spT1, spT2, spT3))
        def advBin = new javafx.scene.control.TitledPane('⚙ İHK yoğunluk binning (opsiyonel)', binGrid)
        advBin.setExpanded(false); advBin.setAnimated(false)

        def status = new javafx.scene.control.Label('Hazır.')
        def progress = new javafx.scene.control.ProgressBar()
        progress.setMaxWidth(Double.MAX_VALUE); progress.setVisible(false); progress.setManaged(false)
        def resultArea = new javafx.scene.control.TextArea()
        resultArea.setEditable(false); resultArea.setWrapText(false); resultArea.setPrefRowCount(11)
        resultArea.setPromptText('Sonuçlar burada görünecek…')
        resultArea.setStyle(MONO)

        def runBtn = new javafx.scene.control.Button('Çalıştır'); runBtn.setDefaultButton(true)
        runBtn.setDisable(!cellposeHere)
        runBtn.setOnAction({
            // Seçimleri hatırla
            prefs.put('family', familyBox.getValue() as String)
            prefs.put('model', modelField.getText())
            prefs.put('channelMode', channelBox.getValue() as String)
            prefs.put('diameter', String.valueOf(spDia.getValue()))
            prefs.put('pixelSize', String.valueOf(spPx.getValue()))
            prefs.put('flow', String.valueOf(spFlow.getValue()))
            prefs.put('cellprob', String.valueOf(spProb.getValue()))
            prefs.put('cellExpansion', String.valueOf(spExp.getValue()))
            prefs.put('ihcBin', String.valueOf(binChk.isSelected()))
            prefs.put('compartment', compartBox.getValue() as String)

            String fam = familyBox.getValue() as String
            String mdl = modelField.getText()?.trim() ?: 'cyto3'
            String chan = channelBox.getValue() as String
            int dia  = spDia.getValue() as int
            double px = spPx.getValue() as double
            double fl = spFlow.getValue() as double
            double pr = spProb.getValue() as double
            double ex = spExp.getValue() as double
            boolean bin = binChk.isSelected()
            double t1 = spT1.getValue() as double
            double t2 = spT2.getValue() as double
            double t3 = spT3.getValue() as double
            String comp = compartBox.getValue() as String

            runBtn.setDisable(true)
            status.setStyle(''); status.setText('… Cellpose çalışıyor (ilk koşuda model indirilebilir)…')
            progress.setVisible(true); progress.setManaged(true); progress.setProgress(-1.0)
            def worker = new Thread({
                def res = runDetection(fam, mdl, chan, dia, px, fl, pr, ex, bin, t1, t2, t3, comp)
                javafx.application.Platform.runLater {
                    progress.setVisible(false); progress.setManaged(false); runBtn.setDisable(false)
                    if (res.ok) {
                        status.setStyle(''); status.setText('Tamamlandı ✅ — parametreleri değiştirip tekrar çalıştırabilirsiniz.')
                        resultArea.setText(res.text)
                    } else {
                        status.setStyle('-fx-text-fill: -qp-script-error-color;'); status.setText('⚠ ' + res.error)
                    }
                }
            }, 'AtolyeCellpose-Run')
            worker.setDaemon(true); worker.start()
        })

        def alwaysTop = new javafx.scene.control.CheckBox('Üstte tut'); alwaysTop.setSelected(true)
        alwaysTop.selectedProperty().addListener(
            { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
        def copyBtn = new javafx.scene.control.Button('Kopyala')
        copyBtn.setOnAction({
            def cb = javafx.scene.input.Clipboard.getSystemClipboard()
            def c = new javafx.scene.input.ClipboardContent(); c.putString(resultArea.getText()); cb.setContent(c)
        })
        def closeBtn = new javafx.scene.control.Button('Kapat'); closeBtn.setOnAction({ stage.close() })

        def footer = new javafx.scene.control.Label('QuPath Atölye Scriptleri · araştırma/eğitim amaçlı')
        footer.setMaxWidth(Double.MAX_VALUE)
        footer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;')

        def spacer = new javafx.scene.layout.Region()
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
        def btnRow = new javafx.scene.layout.HBox(8, alwaysTop, spacer, copyBtn, runBtn, closeBtn)
        btnRow.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

        def content = new javafx.scene.layout.VBox(10, title, banner, info, grid, advBin, status, progress, resultArea)
        content.setPadding(new javafx.geometry.Insets(14))
        javafx.scene.layout.VBox.setVgrow(resultArea, javafx.scene.layout.Priority.ALWAYS)
        def bottom = new javafx.scene.layout.VBox(8, footer, btnRow)
        bottom.setPadding(new javafx.geometry.Insets(10))
        def root = new javafx.scene.layout.BorderPane()
        root.setCenter(content); root.setBottom(bottom)
        stage.setScene(new javafx.scene.Scene(root, 640, 680))
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Cellpose sihirbazı açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
