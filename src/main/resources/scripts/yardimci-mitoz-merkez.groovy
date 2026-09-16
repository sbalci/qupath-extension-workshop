/**
 * Modül - Mitoz tespiti: model listesi (başlangıç ekranı)
 * -------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   "Mitoz tespiti" alt menüsündeki sihirbazları tek bir liste penceresinde toplar. Bir modeli
 *   seçip "Aç ▶" ile başlatırsınız (liste kapanır). O modelle işiniz bitince sihirbaz
 *   penceresindeki "◀ Mitoz listesi" düğmesi bu listeyi yeniden açar; buradan başka bir model
 *   başlatabilirsiniz. Her satırda modelin Python ortamının kurulu olup olmadığı gösterilir.
 *
 * NE ÜRETİR (ve ne ÜRETMEZ):
 *   Bu pencere ölçüm yapmaz ve slayta hiçbir şey yazmaz; yalnızca sihirbazları açar.
 *   Sayım ve yoğunluk, açılan sihirbazın kendisi tarafından üretilir.
 *
 * ÖNERİLEN SIRA:
 *   ① Bir dedektörle (KongNet / FCOS / RetinaNet) seçili bölgedeki mitozları bulun.
 *   ② İsteğe bağlı: atipik sınıflandırıcıyla bulunan mitozları tipik/atipik olarak ayırın.
 *   ③ İsteğe bağlı: birden çok dedektörü aynı bölgede çalıştırıp uyumlarını karşılaştırın.
 *
 * KULLANIM:
 *   [Extensions → Atölye → Modüller → Mitoz tespiti → Mitoz modelleri listesi]
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Model listesi (Mitoz tespiti alt menüsüyle aynı sıra) ─────────────────────
// envIds: Atölye Python ortam yöneticisindeki ortam kimlikleri (yalnız durum göstergesi).
// anyEnv: en az biri yeterli (karşılaştırma). disabled: menüde de devre dışı olan giriş.
def MODEL_GROUPS = [
    [title: 'Dedektörler — seçili bölgede mitozları bulur',
     items: [
        [label: 'KongNet MIDOG (TIA Toolbox)', script: 'yardimci-mitoz-tiatoolbox.groovy', envIds: ['tiatoolbox-region'],
         note: 'Warwick TIA Centre\'ın KongNet-Det (MIDOG) H&E mitoz dedektörü; TIA Toolbox ortamında çalışır.'],
        [label: 'MIDOG25 FCOS (torchvision)', script: 'yardimci-mitoz-fcos.groovy', envIds: ['midog-fcos'],
         note: 'MIDOG 2025 Görev 1 resmî referans dedektörü (FCOS-ResNeXt101); ağırlık ve referans kod "Modeli yerel indir" ile bir kez indirilir.'],
        [label: 'MIDOG DA-RetinaNet (2021, eski)', script: 'yardimci-mitoz-retinanet.groovy', envIds: ['midog-retinanet-legacy'],
         note: '2021/22 MIDOG referans dedektörü (fastai v1); eski ve kırılgan bir yığındır, atölyede doğrulanmamıştır — FCOS önerilir.'],
     ]],
    [title: 'Sınıflandırıcılar — bulunmuş mitozları tipik/atipik olarak ayırır (tespitten sonra)',
     items: [
        [label: 'Atipik sınıflandırma — MIDOG25 EffNetV2', script: 'yardimci-mitoz-atipik-effnet.groovy', envIds: ['midog-atypical'],
         note: 'MIDOG 2025 Görev 2 resmî referans sınıflandırıcısı (EfficientNetV2-M). Önce bir dedektörle "Mitosis" noktaları üretin.'],
        [label: 'Atipik sınıflandırma — Sanofi EFTD (kapılı)', script: 'yardimci-mitoz-atipik-sanofi.groovy', envIds: ['sanofi-eftd'], disabled: true,
         note: 'Varsayılan olarak devre dışı: DINOv3-H+ omurgası Hugging Face\'te kapılıdır (Meta lisansı + huggingface-cli girişi gerekir).'],
     ]],
    [title: 'Karşılaştırma',
     items: [
        [label: 'Modelleri karşılaştır (aynı bölge)', script: 'yardimci-mitoz-karsilastir.groovy',
         envIds: ['tiatoolbox-region', 'midog-fcos', 'midog-retinanet-legacy', 'hovernext'], anyEnv: true,
         note: 'Kurulu dedektörleri aynı bölgede sırayla çalıştırır ve modeller arası uyumu ölçer (doğruluk ölçüsü değildir).'],
     ]],
]

// ── Ortam durumu (salt okunur; sihirbazların kendi otomatik bulma sırasıyla uyumlu) ──
def commonPrefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/common')
def atolyeDataRoot = { ->
    def p = ''
    try { p = commonPrefs.get('dataRoot', '') } catch (Throwable ignore) {}
    return (p?.trim()) ? new File(p.trim()) : new File(System.getProperty('user.home'), '.atolye')
}
// Resmî TIAToolbox eklenti ortamı (<kullanıcı>/QuPath/v*/tiatoolbox-runtime/.venv) — KongNet sihirbazı onu da kullanır.
def officialTiaRuntime = { ->
    def base = new File(System.getProperty('user.home'), 'QuPath')
    def vdirs = base.isDirectory() ? base.listFiles({ f -> f.isDirectory() && f.getName().startsWith('v') } as java.io.FileFilter) : null
    if (vdirs == null) return false
    return vdirs.any { vd -> def rt = new File(vd, 'tiatoolbox-runtime/.venv'); new File(rt, 'Scripts/python.exe').isFile() || new File(rt, 'bin/python').isFile() }
}
def envInstalled = { String id ->
    try { def rec = commonPrefs.get('py.' + id, ''); if (rec?.trim() && new File(rec.trim()).isFile()) return true } catch (Throwable ignore) {}
    def v = new File(new File(atolyeDataRoot(), 'runtimes'), id + '/.venv')
    if (new File(v, 'Scripts/python.exe').isFile() || new File(v, 'bin/python').isFile()) return true
    return (id == 'tiatoolbox-region') && officialTiaRuntime()
}
def statusOf = { item ->
    def ids = item.envIds ?: []
    int n = (ids.count { envInstalled(it) }) as int
    if (item.anyEnv) return [ok: n > 0, text: (n > 0 ? String.format(java.util.Locale.US, '✓ %d/%d dedektör ortamı kurulu', n, ids.size()) : '○ dedektör ortamı kurulu değil')]
    return [ok: n == ids.size(), text: (n == ids.size() ? '✓ Python ortamı kurulu' : '○ Python ortamı kurulu değil')]
}

// ── Headless ──────────────────────────────────────────────────────────────────
if (isHeadless) {
    println "Mitoz modelleri listesi — QuPath arayüzü gerekir (headless çalıştırılamaz). Durum:"
    MODEL_GROUPS.each { g -> g.items.each { m -> println '  ' + m.label + ' — ' + (m.disabled ? 'devre dışı' : statusOf(m).text) } }
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
def stage = null
def step         = new java.util.concurrent.atomic.AtomicReference('LIST')
def alwaysTop    = new java.util.concurrent.atomic.AtomicBoolean(true)
def openingRef   = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef = new java.util.concurrent.atomic.AtomicReference('')
def busyRef      = new java.util.concurrent.atomic.AtomicBoolean(false)
def render

def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text); b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip)); return b
}

// Paketli betiği menüyle aynı ortamda (eklenti sınıf yükleyicisi + yeni Binding) ayrı bir iş
// parçacığında çalıştırır. Betik kendi penceresini kurup döndüğünde onOpened FX iş parçacığında çağrılır.
def launchScript = { String resourceName, Map returnHook, Closure onOpened ->
    new Thread({
        try {
            def url = null
            try { url = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getResource('/scripts/' + resourceName) } catch (Throwable t) {}
            if (url == null) url = this.getClass().getResource('/scripts/' + resourceName)
            if (url == null) {
                javafx.application.Platform.runLater {
                    busyRef.set(false)
                    errorTextRef.set('Betik bulunamadı: ' + resourceName + '\nAtölye eklentisinin kurulu ve güncel olduğundan emin olun (Extensions → Atölye).')
                    step.set('ERROR'); render()
                }
                return
            }
            def cl = this.getClass().getClassLoader()
            try { cl = Class.forName('io.github.sbalci.qupath.workshop.WorkshopExtension').getClassLoader() } catch (Throwable t) {}
            def shellBinding = new Binding()
            shellBinding.setVariable('EXTENSION_NAME', 'qupath-extension-workshop')
            if (returnHook != null) shellBinding.setVariable('atolyeReturnHook', returnHook)
            new GroovyShell(cl, shellBinding).evaluate(url.getText('UTF-8'), resourceName)
            javafx.application.Platform.runLater { busyRef.set(false); if (onOpened != null) onOpened.call() }
        } catch (Throwable t) {
            javafx.application.Platform.runLater {
                busyRef.set(false)
                errorTextRef.set('Açılamadı: ' + resourceName + '\n' + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
                step.set('ERROR'); render()
            }
        }
    } as Runnable, 'AtolyeMitozListe-Ac').start()
}

// Seçilen modelin sihirbazını aç; açıldığında bu liste kapanır (sihirbazdaki "◀ Mitoz listesi" geri getirir).
def openModel = { item ->
    if (item.disabled || busyRef.getAndSet(true)) return
    openingRef.set(item.label + ' açılıyor…'); step.set('OPENING'); render()
    launchScript(item.script, null, { if (stage != null) stage.close() })
}

// Ortam yöneticisini dönüş kancasıyla aç: kurulum bitince "Sihirbaza dön ▶" bu listeyi yeniler.
def openEnvManager = { ->
    def missing = []
    MODEL_GROUPS.each { g -> g.items.each { m ->
        if (!m.disabled && !m.anyEnv) (m.envIds ?: []).each { id -> if (!envInstalled(id) && !missing.contains(id)) missing << id }
    } }
    def hook = [envIds: missing, wizard: 'Mitoz modelleri listesi',
                onReturn: { reopen ->
                    javafx.application.Platform.runLater {
                        if (stage == null || (!stage.isShowing() && !reopen)) return
                        if (step.get() != 'OPENING') { step.set('LIST'); render() }
                        if (stage.isIconified()) stage.setIconified(false)
                        if (!stage.isShowing()) stage.show()
                        stage.toFront(); stage.requestFocus()
                    }
                }]
    launchScript('yardimci-python-ortam-yoneticisi.groovy', hook, null)
}

def slideNameOf = { imageData ->
    try { return imageData.getServer().getMetadata().getName() ?: '(adsız)' } catch (Throwable t) { return '(adsız)' }
}

// ── Render ───────────────────────────────────────────────────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def imageData = QP.getCurrentImageData()

    def title = new javafx.scene.control.Label(); title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10); center.setPadding(new javafx.geometry.Insets(14)); center.getChildren().add(title)
    def actions = new ArrayList()
    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); lbl.setMaxWidth(Double.MAX_VALUE); center.getChildren().add(lbl) }

    if (cur == 'LIST') {
        title.setText('Mitoz tespiti — model listesi')
        addGuidance('Bir model seçip "Aç ▶" ile başlatın (bu liste kapanır). O modelle işiniz bitince sihirbaz penceresindeki "◀ Mitoz listesi" düğmesi sizi bu listeye geri getirir; buradan başka bir model başlatabilirsiniz.\n' +
            'Önerilen sıra: ① bir dedektörle mitozları bulun → ② isteğe bağlı: atipik sınıflandırıcıyla tipik/atipik ayırın → ③ isteğe bağlı: dedektörleri aynı bölgede karşılaştırın.')
        def slideLbl = new javafx.scene.control.Label(imageData == null
            ? 'Açık slayt: yok — sihirbazlar bir H&E slaytı ve seçili bir alan anotasyonu bekler.'
            : ('Açık slayt: ' + slideNameOf(imageData)))
        slideLbl.setWrapText(true); slideLbl.setMaxWidth(Double.MAX_VALUE); slideLbl.setStyle('-fx-opacity: 0.85;')
        center.getChildren().add(slideLbl)

        def listBox = new javafx.scene.layout.VBox(4)
        MODEL_GROUPS.each { g ->
            def gl = new javafx.scene.control.Label(g.title); gl.setWrapText(true); gl.setMaxWidth(Double.MAX_VALUE)
            gl.setStyle('-fx-font-weight: bold; -fx-padding: 10 0 2 0;')
            listBox.getChildren().add(gl)
            g.items.each { m ->
                def st = statusOf(m)
                def row = new javafx.scene.layout.VBox(2)
                row.setStyle('-fx-border-color: -fx-box-border; -fx-border-width: 0 0 1 0; -fx-padding: 6 2 6 12;')
                def head = new javafx.scene.layout.HBox(8); head.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
                def name = new javafx.scene.control.Label(m.label); name.setStyle('-fx-font-weight: bold;')
                def chip = new javafx.scene.control.Label(m.disabled ? '⊘ devre dışı' : st.text)
                chip.setStyle(m.disabled ? '-fx-opacity: 0.7;' : (st.ok ? '-fx-text-fill: #2e8b57;' : '-fx-text-fill: #b8860b;'))
                def spacer = new javafx.scene.layout.Region(); javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
                def openBtn = navButton('Aç ▶', { openModel(m) }, m.label + ' sihirbazını açar (bu liste kapanır)')
                openBtn.setDisable(m.disabled == true)
                head.getChildren().addAll(name, spacer, chip, openBtn)
                def note = new javafx.scene.control.Label(m.note ?: ''); note.setWrapText(true); note.setMaxWidth(Double.MAX_VALUE)
                note.setStyle('-fx-opacity: 0.75; -fx-font-size: 11px;')
                row.getChildren().addAll(head, note)
                listBox.getChildren().add(row)
            }
        }
        def scroll = new javafx.scene.control.ScrollPane(listBox); scroll.setFitToWidth(true)
        javafx.scene.layout.VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(scroll)
        addGuidance('Ortam durumu yalnız bilgi amaçlıdır: "○ kurulu değil" görünen bir modeli yine de açabilirsiniz; sihirbaz eksik adımı (Python ortamı, model indirme) kendi penceresinde gösterir.')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('⚙ Python ortamı', { openEnvManager() }, 'Atölye Python ortam yöneticisini açar; kurulum bitince "Sihirbaza dön ▶" bu listeye döner'))
        actions.add(navButton('⟳ Yenile', { render() }))
    } else if (cur == 'OPENING') {
        title.setText(openingRef.get())
        addGuidance('Sihirbaz açılıyor; açıldığında bu liste kapanır. Listeye dönmek için sihirbazdaki "◀ Mitoz listesi" düğmesini kullanın.')
        def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE)
        center.getChildren().add(pb)
    } else { // ERROR
        title.setText('Hata')
        def ta = new javafx.scene.control.TextArea(errorTextRef.get() ?: ''); ta.setEditable(false); ta.setWrapText(true)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(ta)
        actions.add(navButton('◀ Listeye dön', { step.set('LIST'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    }

    def topChk = new javafx.scene.control.CheckBox('Üstte tut'); topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n -> alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
    def spacer = new javafx.scene.layout.Region(); javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8); bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk); bar.getChildren().add(spacer); bar.getChildren().addAll(actions)
    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar); bottom.setPadding(new javafx.geometry.Insets(10))
    def root = new javafx.scene.layout.BorderPane(); root.setCenter(center); root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 820, 640))
}

// ── Açılış ────────────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Mitoz tespiti — model listesi')
        stage.setAlwaysOnTop(alwaysTop.get())
        render(); stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Liste açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ Mitoz modelleri listesi açıldı."
