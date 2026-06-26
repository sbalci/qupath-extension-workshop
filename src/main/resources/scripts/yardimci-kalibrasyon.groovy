/**
 * Yardımcı - Kalibrasyon (piksel boyutu)
 * --------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Açık slaytın piksel boyutunu (µm/px) ayarlar. QuPath fiziksel ölçeği bu
 * sayıdan bilir; eksikse alan/yoğunluk/çap ölçümleri yanlış olur — "Kalibrasyon
 * yok" uyarısının çözümü budur.
 *
 * TEK PENCERE: Tüm akış tek bir pencerede adım adım ilerler — yöntem seçimi,
 * değer/cetvel girişi, proje-geneli sorusu ve sonuç. Ayrı pop-up dialog açılmaz.
 *
 * İKİ MOD:
 *   A) Doğrudan değer gir — µm/px değerini elle girin.
 *      Atölye tarayıcıları: Leica Aperio GT450 (40×) ≈ 0.26 µm/px;
 *                           Aperio AT2 (40×) ≈ 0.25 µm/px (20× ≈ 0.50).
 *      Not: Yerel Aperio .svs dosyaları piksel boyutunu zaten gömer (QuPath
 *      otomatik kalibre eder); bu araç asıl olarak meta verisi silinmiş
 *      dönüştürülmüş/dışa aktarılmış dosyalar (ör. TIFF) içindir.
 *   B) Cetvelden ölç — Çizgi (Line) aracı otomatik seçilir; bilinen uzunlukta
 *      bir çizgi çizip "Ölç"e basın (çizgi yoksa pencere açık kalır, satır-içi
 *      uyarı çıkar — yeniden çalıştırma GEREKMEZ). Gerçek uzunluğu (µm) girin →
 *      µm/px = gerçek_µm ÷ çizgi_uzunluğu_px.
 *
 * KAPSAM: Açık görüntü (her zaman). Mod A'da isteğe bağlı: projedeki tüm
 *         KALİBRE EDİLMEMİŞ görüntülere de uygula (kalibre olanlara dokunmaz).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.images.servers.ImageServerMetadata

def isHeadless = qupath.lib.gui.QuPathGUI.getInstance() == null

// ── UI yardımcıları (modül 6/7 sihirbazlarıyla aynı) ───────────────────────
def faIcon = { String glyphName ->
    try {
        def node = org.controlsfx.glyphfont.Glyph.create('FontAwesome|' + glyphName)
        node.setStyle('-fx-text-fill: -fx-text-base-color;')
        return node
    } catch (Throwable ignored) { return null }
}
def navButton = { String text, Closure action, String tooltip = null, String icon = null ->
    def b = new javafx.scene.control.Button(text); b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    if (icon) { def g = faIcon(icon); if (g != null) b.setGraphic(g) }
    return b
}
def busyBar = { ->
    def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb
}

// ── İş mantığı (arka planda çağrılır) ──────────────────────────────────────
def applyMeta = { imgData, double px ->
    def srv = imgData.getServer()
    def newMeta = new ImageServerMetadata.Builder(srv.getMetadata())
        .pixelSizeMicrons(px, px)
        .build()
    imgData.updateServerMetadata(newMeta)
}

// Açık görüntüye uygula (bellekte) + projedeyse diske yaz (best-effort).
def runApply = { double px ->
    try {
        def imageData = QP.getCurrentImageData()
        if (imageData == null) return [ok:false, error:'Görüntü açık değil.']
        applyMeta(imageData, px)
        boolean persisted = false
        def project = QP.getProject()
        def currentEntry = (project != null) ? QP.getProjectEntry() : null
        if (currentEntry != null) {
            try { currentEntry.saveImageData(imageData); persisted = true }
            catch (Throwable t) { println "Uyarı: açık görüntü diske yazılamadı (${t.getClass().getSimpleName()})." }
        }
        return [ok:true, persisted:persisted, hasProject:(project != null)]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '(mesaj yok)')]
    }
}

// Proje genelinde kalibre edilmemişlere uygula (yalnız Mod A).
def runBatch = { double px ->
    int updated = 0, skipped = 0
    try {
        def project = QP.getProject()
        if (project == null) return [ok:false, error:'Proje yok.']
        def currentEntry = QP.getProjectEntry()
        for (entry in project.getImageList()) {
            try {
                if (currentEntry != null && entry == currentEntry) continue
                entry.readImageData().withCloseable { ed ->
                    if (!ed.getServer().getPixelCalibration().hasPixelSizeMicrons()) {
                        applyMeta(ed, px); entry.saveImageData(ed); updated++
                    } else { skipped++ }
                }
            } catch (Throwable t) {
                println "Uyarı: ${entry.getImageName()} güncellenemedi (${t.getClass().getSimpleName()})."
            }
        }
        return [ok:true, updated:updated, skipped:skipped]
    } catch (Throwable t) {
        return [ok:false, error: t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: '(mesaj yok)')]
    }
}

// Her render'da görüntü/proje/kalibrasyon durumunu tazeler.
def computeState = { ->
    def st = [image:false, project:false, calibrated:false, curPx:0.0d, curStr:'tanımsız (NaN)']
    def imageData = QP.getCurrentImageData()
    st.image = imageData != null
    if (imageData != null) {
        def cal = imageData.getServer().getPixelCalibration()
        double px = cal.getPixelWidthMicrons()
        st.calibrated = (px > 0)
        st.curPx = px
        st.curStr = st.calibrated ? String.format(java.util.Locale.US, "%.4f µm/px", px) : "tanımsız (NaN)"
    }
    st.project = QP.getProject() != null
    return st
}

// ── Headless (GUI yok): etkileşimsiz çalışılamaz, durumu yaz ve çık ─────────
if (isHeadless) {
    def s = computeState()
    println "Kalibrasyon (headless): görüntü=${s.image} kalibre=${s.calibrated} (${s.curStr}) proje=${s.project}"
    println "GUI olmadan kalibrasyon etkileşimi yok — µm/px değerini bir insanın girmesi gerekir."
    return
}

// ── Tek pencere, adım adım render ──────────────────────────────────────────
// Stage/Scene YALNIZ FX uygulama iş parçacığında oluşturulabilir; betik arka
// planda çalıştığından stage aşağıdaki Platform.runLater içinde oluşturulur.
def stage = null

// CHOICE | DIRECT | RULER_DRAW | RULER_LEN | APPLYING | BATCH_ASK | BATCHING | RESULT
def step = new java.util.concurrent.atomic.AtomicReference('CHOICE')
def modeRef = new java.util.concurrent.atomic.AtomicReference(null)     // 'direct' | 'ruler'
def newPxRef = new java.util.concurrent.atomic.AtomicReference(null)    // Double
def methodNoteRef = new java.util.concurrent.atomic.AtomicReference('') // String
def prevStrRef = new java.util.concurrent.atomic.AtomicReference('')    // önceki kalibrasyon (özet için)
def lineRef = new java.util.concurrent.atomic.AtomicReference(null)     // PathObject (çizgi)
def lenPxRef = new java.util.concurrent.atomic.AtomicReference(null)    // Double (piksel)
def applyResultRef = new java.util.concurrent.atomic.AtomicReference(null) // Map
def batchResultRef = new java.util.concurrent.atomic.AtomicReference(null) // Map | null
def directWarnedRef = new java.util.concurrent.atomic.AtomicBoolean(false) // sıra-dışı değer iki-adımlı onayı
def render  // ileri bildirim

// Seçili/var olan tek çizgiyi bul: önce seçili çizgi, yoksa tam bir çizgi anotasyonu.
def findLine = { ->
    def sel = QP.getSelectedObject()
    if (sel != null && sel.getROI()?.isLine()) return sel
    def lines = QP.getAnnotationObjects().findAll { it.getROI()?.isLine() }
    if (lines.size() == 1) return lines[0]
    return null
}

// Sonuç özet metni (RESULT TextArea'sında gösterilir).
def buildSummary = { ->
    def b = new StringBuilder()
    b << "Kalibrasyon güncellendi.\n\n"
    b << "  Yöntem        : ${methodNoteRef.get()}\n"
    b << "  Önceki        : ${prevStrRef.get()}\n"
    b << String.format(java.util.Locale.US, "  Yeni          : %.4f µm/px\n", (double) newPxRef.get())
    def ar = applyResultRef.get()
    boolean persisted = (ar != null && ar.persisted)
    boolean hasProject = (ar != null && ar.hasProject)
    b << "  Diske yazıldı : " + (persisted ? "evet (proje)" : (hasProject ? "hayır" : "proje yok — yalnız bellek")) + "\n"
    def br = batchResultRef.get()
    if (br != null && br.ok) {
        b << String.format(java.util.Locale.US, "  Proje geneli  : %d güncellendi, %d zaten kalibre\n", (int) br.updated, (int) br.skipped)
    }
    b << "\nDoğrulama: Ruler aracıyla tipik bir tümör çekirdeği çapı ~8–12 µm görünmeli.\n"
    b << "\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return b.toString()
}

// ── Arka plan: açık görüntüye uygula → busy → sonraki adım ──────────────────
def startApply = { ->
    step.set('APPLYING'); render()
    def worker = new Thread({
        def r = runApply((double) newPxRef.get())
        javafx.application.Platform.runLater {
            applyResultRef.set(r)
            // Ölçek çubuğu/ruler tazelensin (mevcut idiomu koruyor).
            try { qupath.lib.gui.QuPathGUI.getInstance()?.getViewer()?.repaint() } catch (Throwable ignored) {}
            if (!r.ok) { step.set('RESULT'); render(); return }
            if (modeRef.get() == 'direct' && r.hasProject) { step.set('BATCH_ASK'); render() }
            else { batchResultRef.set(null); step.set('RESULT'); render() }
        }
    }, 'AtolyeCalApply'); worker.setDaemon(true); worker.start()
}

// ── Arka plan: proje-geneli batch → busy → sonuç ────────────────────────────
def startBatch = { ->
    step.set('BATCHING'); render()
    def worker = new Thread({
        def r = runBatch((double) newPxRef.get())
        javafx.application.Platform.runLater { batchResultRef.set(r); step.set('RESULT'); render() }
    }, 'AtolyeCalBatch'); worker.setDaemon(true); worker.start()
}

render = { ->
    if (stage != null) stage.setAlwaysOnTop(true)   // her render'da üstte-kal yeniden uygulanır
    def s = computeState()
    def content = new javafx.scene.layout.VBox(10)
    content.setPadding(new javafx.geometry.Insets(14))
    def buttons = new javafx.scene.layout.HBox(8)
    buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def bodyLbl = new javafx.scene.control.Label()
    bodyLbl.setWrapText(true)
    content.getChildren().addAll(title, bodyLbl)

    def cur = step.get()
    if (cur == 'CHOICE') {
        if (!s.image) {
            title.setText('Görüntü açık değil')
            bodyLbl.setText('Önce bir slayt açın, sonra "⟳ Yenile"ye basın.')
            buttons.getChildren().addAll(
                navButton('Kapat', { stage.close() }),
                navButton('⟳ Yenile', { render() }))
        } else {
            title.setText('Kalibrasyon — yöntem seçin')
            bodyLbl.setText(
                (s.calibrated
                    ? "ℹ Bu görüntü ZATEN kalibre: ${s.curStr}.\n\n"
                    : "⚠ Bu görüntüde kalibrasyon YOK (µm/px tanımsız).\n\n") +
                "Piksel boyutunu (µm/px) nasıl ayarlamak istersiniz?\n\n" +
                "• Doğrudan değer: tarayıcı değerini elle girin\n" +
                "    (GT450 40× ≈ 0.26 · AT2 40× ≈ 0.25 / 20× ≈ 0.50)\n" +
                "• Cetvelden ölç: bilinen uzunlukta bir çizgi çizip ölçün")
            buttons.getChildren().addAll(
                navButton('Kapat', { stage.close() }),
                navButton('Cetvelden ölç', {
                    modeRef.set('ruler')
                    // En-iyi-çaba: Çizgi (Line) aracına geç (FX thread; sürüm farklarına karşı iki deneme).
                    try { qupath.lib.gui.QuPathGUI.getInstance()?.getToolManager()?.setSelectedTool(qupath.lib.gui.viewer.tools.PathTools.LINE) }
                    catch (Throwable t1) {
                        try { qupath.lib.gui.QuPathGUI.getInstance()?.setSelectedTool(qupath.lib.gui.viewer.tools.PathTools.LINE) } catch (Throwable t2) {}
                    }
                    def existing = findLine()
                    if (existing != null && existing.getROI().getLength() > 0) {
                        lineRef.set(existing); lenPxRef.set((Double) existing.getROI().getLength()); step.set('RULER_LEN')
                    } else { step.set('RULER_DRAW') }
                    render()
                }, 'Bilinen uzunlukta bir çizgi çizip ölçerek µm/px hesaplar', 'PENCIL'),
                navButton('Doğrudan değer ▶', {
                    modeRef.set('direct'); directWarnedRef.set(false); step.set('DIRECT'); render()
                }, 'Tarayıcı µm/px değerini elle girin', 'KEYBOARD_ALT'))
        }
    } else if (cur == 'DIRECT') {
        title.setText('Doğrudan değer gir')
        bodyLbl.setText(
            "Şu anki piksel boyutu: ${s.curStr}\n\n" +
            "Yeni piksel boyutu (µm/px):\n" +
            "  • Leica Aperio GT450 (40×) ≈ 0.26\n" +
            "  • Leica Aperio AT2 (40×) ≈ 0.25  (20× ≈ 0.50)\n\n" +
            "Not: Yerel .svs dosyaları bu değeri zaten içerir; bu araç meta verisi\n" +
            "eksik (ör. dışa aktarılmış TIFF) dosyalar içindir.")
        def field = new javafx.scene.control.TextField('0.26'); field.setPrefColumnCount(10)
        field.textProperty().addListener({ obs, o, n -> directWarnedRef.set(false) } as javafx.beans.value.ChangeListener)
        def errLbl = new javafx.scene.control.Label(); errLbl.setWrapText(true)
        content.getChildren().addAll(
            new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('µm/px:'), field), errLbl)
        def applyDirect = {
            double px
            try { px = Double.parseDouble(field.getText().toString().trim().replace(',' as char, '.' as char)) }
            catch (Exception e) {
                errLbl.setStyle('-fx-text-fill: -qp-script-error-color;')
                errLbl.setText('⚠ Sayısal bir µm/px değeri girin (ör. 0.26).'); return
            }
            if (!(px > 0)) {
                errLbl.setStyle('-fx-text-fill: -qp-script-error-color;')
                errLbl.setText('⚠ Piksel boyutu pozitif olmalı.'); return
            }
            if ((px < 0.05 || px > 2.0) && !directWarnedRef.get()) {
                directWarnedRef.set(true)
                errLbl.setStyle('-fx-text-fill: #cc7a00;')   // amber: sıra-dışı değer onayı
                errLbl.setText(String.format(java.util.Locale.US,
                    '⚠ %.4f µm/px tipik patoloji aralığının (0.05–2.0) dışında. Yine de uygulamak için "Uygula"ya tekrar basın.', px))
                return
            }
            newPxRef.set((Double) px); methodNoteRef.set('Doğrudan giriş'); prevStrRef.set(s.curStr)
            startApply()
        }
        field.setOnAction({ applyDirect() })
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('CHOICE'); render() }),
            navButton('Uygula ▶', { applyDirect() }, 'Bu değeri açık görüntüye uygular'))
    } else if (cur == 'RULER_DRAW') {
        title.setText('Cetvel — çizgi çizin')
        bodyLbl.setText(
            "Çizgi (Line) aracı seçili olmalı — değilse araç çubuğundan seçin.\n\n" +
            "1. Bilinen uzunlukta bir yapının üzerine bir çizgi çizin\n" +
            "   (ör. gömülü ölçek çubuğu ya da bilinen çaplı bir yapı)\n" +
            "2. Aşağıdan 'Ölç ▶'e basın\n\n" +
            "Çizgiyi yeniden çizebilirsiniz; en son/seçili çizgi kullanılır.")
        def statusLbl = new javafx.scene.control.Label(); statusLbl.setWrapText(true)
        content.getChildren().add(statusLbl)
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('CHOICE'); render() }),
            navButton('Ölç ▶', {
                def line = findLine()
                if (line == null) {
                    int n = QP.getAnnotationObjects().findAll { it.getROI()?.isLine() }.size()
                    statusLbl.setStyle('-fx-text-fill: -qp-script-error-color;')
                    statusLbl.setText(n > 1
                        ? '⚠ Birden fazla çizgi var — ölçmek istediğinizi SEÇİN, sonra tekrar "Ölç".'
                        : '⚠ Henüz çizgi yok — bir çizgi çizip tekrar "Ölç"e basın.')
                    return
                }
                double lenPx = line.getROI().getLength()
                if (!(lenPx > 0)) {
                    statusLbl.setStyle('-fx-text-fill: -qp-script-error-color;')
                    statusLbl.setText('⚠ Çizginin uzunluğu sıfır görünüyor; daha uzun bir çizgi çizin.')
                    return
                }
                lineRef.set(line); lenPxRef.set((Double) lenPx); step.set('RULER_LEN'); render()
            }, 'Çizdiğiniz/seçtiğiniz çizgiyi ölçer'))
    } else if (cur == 'RULER_LEN') {
        double lenPx = (double) lenPxRef.get()
        String defaultLen = '100'
        String bodyTxt
        if (s.calibrated) {
            double measuredUm = lenPx * (s.curPx as double)
            defaultLen = String.format(java.util.Locale.US, '%.1f', measuredUm)
            bodyTxt = String.format(java.util.Locale.US,
                "Çizgi uzunluğu: %.1f piksel.\n" +
                "Bu görüntü zaten kalibre (%s), bu da bu çizgi için yaklaşık %.1f µm demek.\n\n" +
                "Mevcut kalibrasyon doğruysa değeri olduğu gibi bırakıp 'Uygula'ya basın (değişmez).\n" +
                "Düzeltmek isterseniz çizginin gerçek uzunluğunu (µm) girin.",
                lenPx, s.curStr, measuredUm)
        } else {
            bodyTxt = String.format(java.util.Locale.US,
                "Çizgi uzunluğu: %.1f piksel.\n\nBu çizginin gerçek uzunluğu kaç µm?\n" +
                "(ör. 100 µm'lik bir ölçek çubuğu için 100)", lenPx)
        }
        title.setText('Cetvel — gerçek uzunluk')
        bodyLbl.setText(bodyTxt)
        def field = new javafx.scene.control.TextField(defaultLen); field.setPrefColumnCount(10)
        def errLbl = new javafx.scene.control.Label(); errLbl.setWrapText(true)
        content.getChildren().addAll(
            new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('Gerçek uzunluk (µm):'), field), errLbl)
        def applyRuler = {
            double realUm
            try { realUm = Double.parseDouble(field.getText().toString().trim().replace(',' as char, '.' as char)) }
            catch (Exception e) {
                errLbl.setStyle('-fx-text-fill: -qp-script-error-color;')
                errLbl.setText('⚠ Sayısal bir uzunluk (µm) girin.'); return
            }
            if (!(realUm > 0)) {
                errLbl.setStyle('-fx-text-fill: -qp-script-error-color;')
                errLbl.setText('⚠ Uzunluk pozitif olmalı.'); return
            }
            double newPx = realUm / lenPx
            newPxRef.set((Double) newPx)
            methodNoteRef.set(String.format(java.util.Locale.US, 'Cetvel: %.1f µm ÷ %.1f px', realUm, lenPx))
            prevStrRef.set(s.curStr)
            startApply()
        }
        field.setOnAction({ applyRuler() })
        buttons.getChildren().addAll(
            navButton('◀ Geri', { step.set('RULER_DRAW'); render() }),
            navButton('Uygula ▶', { applyRuler() }, 'Bu uzunluktan µm/px hesaplayıp uygular'))
    } else if (cur == 'APPLYING') {
        title.setText('Kalibrasyon uygulanıyor…')
        bodyLbl.setText('Piksel boyutu açık görüntüye uygulanıyor ve (projede ise) diske yazılıyor. Lütfen bekleyin…')
        content.getChildren().add(busyBar())
    } else if (cur == 'BATCH_ASK') {
        title.setText('Proje geneli uygula?')
        bodyLbl.setText(String.format(java.util.Locale.US,
            "Açık görüntü %.4f µm/px olarak ayarlandı.\n\n" +
            "Projedeki TÜM kalibre edilmemiş görüntülere de aynı değeri uygulamak ister misiniz?\n" +
            "(Zaten kalibre olan görüntülere dokunulmaz.)", (double) newPxRef.get()))
        buttons.getChildren().addAll(
            navButton('Hayır, atla', { batchResultRef.set(null); step.set('RESULT'); render() }),
            navButton('Evet, uygula ▶', { startBatch() },
                'Projedeki kalibre edilmemiş görüntülere de uygular'))
    } else if (cur == 'BATCHING') {
        title.setText('Proje geneli uygulanıyor…')
        bodyLbl.setText('Kalibre edilmemiş görüntüler güncelleniyor ve diske yazılıyor. Lütfen bekleyin…')
        content.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        def ar = applyResultRef.get()
        if (ar != null && ar.ok) {
            title.setText('Kalibrasyon tamam ✅')
            def resultArea = new javafx.scene.control.TextArea(buildSummary())
            resultArea.setEditable(false); resultArea.setWrapText(false); resultArea.setPrefRowCount(10)
            resultArea.setStyle("-fx-font-family: 'Consolas','Menlo','Courier New',monospace; -fx-font-size: 12px;")
            content.getChildren().add(resultArea)
            javafx.scene.layout.VBox.setVgrow(resultArea, javafx.scene.layout.Priority.ALWAYS)
            def copyBtn = new javafx.scene.control.Button('Kopyala')
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def cc = new javafx.scene.input.ClipboardContent(); cc.putString(resultArea.getText()); cb.setContent(cc)
            })
            // Konsola da yaz (kayıt için).
            println String.format(java.util.Locale.US, "Kalibrasyon: %s → %.4f µm/px (%s)",
                prevStrRef.get(), (double) newPxRef.get(), methodNoteRef.get())
            buttons.getChildren().addAll(
                navButton('Kapat', { stage.close() }),
                copyBtn,
                navButton('↻ Yeniden kalibre et', { step.set('CHOICE'); render() }, 'Baştan kalibrasyon yapar'))
        } else {
            title.setText('Kalibrasyon yapılamadı')
            bodyLbl.setText((ar?.error ?: 'Bilinmeyen hata.').toString())
            buttons.getChildren().addAll(
                navButton('Kapat', { stage.close() }),
                navButton('↻ Tekrar dene', { step.set('CHOICE'); render() }))
        }
    }

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(content)
    // Kalıcı sorumluluk reddi notu — tema-duyarlı (açık/koyu tema).
    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, buttons)
    bottom.setPadding(new javafx.geometry.Insets(10))
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 600, 500))
}

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Kalibrasyon (piksel boyutu)')
        stage.setAlwaysOnTop(true)
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Kalibrasyon penceresi açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
