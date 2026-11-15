/**
 * Yardımcı - Dağılım Grafiği (QuPath içi scatter chart)
 * -------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Tespitlerin (hücrelerin) iki ölçümünü QuPath'in YERLEŞİK dağılım grafiği
 *   (Charts.scatterChart) ile çizer — matplotlib/Python gerekmez. İki ölçüm
 *   açılır listeden seçilir; grafik QuPath içinde ayrı bir pencerede açılır ve
 *   görüntüleyiciye bağlıdır (noktalar lamdaki sınıf renkleriyle gösterilir).
 *
 *   Tipik kullanım: denetimsiz fenotipleme sonrası UMAP1–UMAP2 gömmesini
 *   QuPath içinde görselleştirmek (bkz. Ekler → Hücre Fenotipleme; canlı köprü
 *   için Ekler → QuBaLab). UMAP1/UMAP2 ölçümleri varsa varsayılan eksenler
 *   olarak seçilir.
 *
 * NE ÜRETİR (ve ne ÜRETMEZ):
 *   • Yalnız var olan ölçümleri çizer. Tespit, ölçüm, kümeleme veya yorum YAPMAZ.
 *   • Ölçümler kullanıcının önceki adımlarından gelir (tespit modülleri,
 *     özellik matrisi, fenotipleme TSV içe aktarımı).
 *
 * KULLANIM:
 *   1. Ölçümlü tespitler içeren bir lam açın (örn. Hücre tespiti / Nükleer boya /
 *      Sitoplazmik boya / Tümör içi Ki-67 modüllerinden sonra,
 *      ya da fenotip/UMAP etiketleri içe aktarıldıktan sonra).
 *   2. [Extensions → Atölye → Yardımcılar → Eğitim, sunum ve ImageJ → Dağılım grafiği (scatter chart)]
 *   3. X ve Y ölçümlerini seçip "Grafiği aç".
 *
 * KAYNAK / İLHAM:
 *   • Bankhead/O'Callaghan, I2K 2024 "QuPath for Python Programmers" —
 *     plot.groovy (Charts.scatterChart ile UMAP görselleştirme).
 *     github.com/qupath/i2k-qupath-for-python-programmers
 *   • Bankhead P et al. (2017), Sci Rep — QuPath. doi:10.1038/s41598-017-17204-5
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.charts.Charts
import qupath.lib.scripting.QP

def gui = QuPathGUI.getInstance()
def isHeadless = gui == null
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"

// ── Tespitlerden ölçüm adlarını topla (ilk 200 tespitin birleşimi) ──────────
def collectMeasurementNames = { ->
    def names = new java.util.TreeSet<String>()
    def dets = QP.getDetectionObjects()
    int n = 0
    for (d in dets) {
        try { names.addAll(d.getMeasurements().keySet()) } catch (Throwable ignore) {}
        if (++n >= 200) break
    }
    return new ArrayList<String>(names)
}

// ── Headless: ölçüm adlarını raporla, etkileşim yok ─────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println "Önce bir lam açın."; return }
    def names = collectMeasurementNames()
    println "Dağılım grafiği sihirbazı: ${QP.getDetectionObjects().size()} tespit, ${names.size()} ölçüm."
    println "Bu sihirbaz QuPath arayüzü gerektirir (headless çalıştırılamaz)."
    println "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."
    return
}

// ── Durum makinesi alanları ──────────────────────────────────────────────────
def stage = null
def alwaysTop = new java.util.concurrent.atomic.AtomicBoolean(true)
def xComboRef = new java.util.concurrent.atomic.AtomicReference(null)
def yComboRef = new java.util.concurrent.atomic.AtomicReference(null)
def render  // forward declaration

def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text)
    b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    return b
}

// ── Grafiği aç ───────────────────────────────────────────────────────────────
def openChart = {
    def xc = xComboRef.get(); def yc = yComboRef.get()
    def xName = (xc != null) ? xc.getValue() : null
    def yName = (yc != null) ? yc.getValue() : null
    if (xName == null || yName == null) {
        Dialogs.showWarningNotification(workshopText('script.70fe347f0a97', "Eksik seçim"), workshopText('script.94905042e46b', "Lütfen X ve Y için birer ölçüm seçin."))
        return
    }
    def dets = QP.getDetectionObjects()
    if (dets.isEmpty()) {
        Dialogs.showWarningNotification(workshopText('script.4277b763a640', "Tespit yok"), workshopText('script.0c3cd5ac7013', "Çizilecek tespit yok. Önce bir tespit/ölçüm adımı çalıştırın."))
        return
    }
    try {
        Charts.scatterChart()
            .viewer(gui.getViewer())
            .title("Dağılım: ${xName} — ${yName}")
            .measurements(dets, xName, yName)
            .show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.08e9aece49b2', "Grafik açılamadı"), (t.getMessage() ?: t.getClass().getSimpleName()))
    }
}

// ── Render: pencere içeriğini kurar ─────────────────────────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())

    def title = new javafx.scene.control.Label(workshopText('script.fefc78fdcc60', "Dağılım grafiği — iki ölçüm seç"))
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10)
    center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)

    def names = collectMeasurementNames()
    def dets = QP.getDetectionObjects()

    if (dets.isEmpty() || names.isEmpty()) {
        def lbl = new javafx.scene.control.Label(
            workshopText('script.61a716f3975b', 'Çizilebilir ölçüm bulunamadı.\n\nÖnce ölçümlü tespitler oluşturun (Hücre tespiti / Nükleer boya / Sitoplazmik boya /\nTümör içi Ki-67 modülleri), ya da fenotip/UMAP\netiketlerini içe aktarın (Extensions → Atölye → Yardımcılar → İçe ve dışa aktarma / veri →\nKümeleme/fenotip etiketlerini içe aktar (TSV)).\nSonra \'⟳ Yenile\'.'))
        lbl.setWrapText(true)
        center.getChildren().add(lbl)
        xComboRef.set(null); yComboRef.set(null)
    } else {
        def xCombo = new javafx.scene.control.ComboBox()
        def yCombo = new javafx.scene.control.ComboBox()
        xCombo.getItems().addAll(names)
        yCombo.getItems().addAll(names)
        // Varsayılan: UMAP1 / UMAP2 varsa onları seç, yoksa ilk iki ölçüm.
        def pick = { List<String> ns, String pref, int fallbackIdx ->
            if (ns.contains(pref)) return pref
            return ns.size() > fallbackIdx ? ns.get(fallbackIdx) : ns.get(0)
        }
        xCombo.setValue(pick(names, "UMAP1", 0))
        yCombo.setValue(pick(names, "UMAP2", Math.min(1, names.size() - 1)))
        xComboRef.set(xCombo); yComboRef.set(yCombo)

        def info = new javafx.scene.control.Label(
            "${dets.size()}${workshopText('script.0b3dffaa7a47', ' tespit · ')}${names.size()}${workshopText('script.41fd26a07624', ' ölçüm. Noktalar lamdaki sınıf renkleriyle gösterilir.')}")
        info.setWrapText(true)

        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(8); grid.setVgap(8)
        grid.add(new javafx.scene.control.Label(workshopText('script.11ede729b0e5', "X ekseni:")), 0, 0)
        grid.add(xCombo, 1, 0)
        grid.add(new javafx.scene.control.Label(workshopText('script.6fe1602674e3', "Y ekseni:")), 0, 1)
        grid.add(yCombo, 1, 1)
        center.getChildren().addAll(info, grid)
    }

    // Alt çubuk
    def actions = new ArrayList()
    actions.add(navButton(workshopText('script.7b31a9fc4816', 'Kapat'), { stage.close() }))
    actions.add(navButton(workshopText('script.e8e5dcb69dea', '⟳ Yenile'), { render() }, workshopText('script.2ea02fef4e27', 'Ölçüm listesini yeniden tara')))
    def openBtn = navButton(workshopText('script.bb1b8e0c0fbf', 'Grafiği aç ▶'), { openChart() }, workshopText('script.7a4b706d1848', 'Seçili iki ölçümü QuPath dağılım grafiğinde çiz'))
    openBtn.setDisable(dets.isEmpty() || names.isEmpty())
    actions.add(openBtn)

    def topChk = new javafx.scene.control.CheckBox(workshopText('script.70ca64c0c43a', 'Üstte tut'))
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

    def disclaimer = new javafx.scene.control.Label(workshopText('script.1620e92e32c0', 'Yalnızca araştırma/eğitim amaçlı; var olan ölçümleri çizer, yorum üretmez.'))
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(center)
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 560, 360))
}

// ── Açılış ───────────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle(workshopText('script.7ffd45941f9e', 'Dağılım grafiği sihirbazı'))
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage(workshopText('script.7fd583bc0c88', 'Sihirbaz açılamadı'), workshopText('script.9aac4912cb86', 'Bu araç başlatılamadı.\n\nYapabilecekleriniz:\n  1. Araç açık bir lam istiyorsa önce lamı açın.\n  2. QuPath\'i tamamen kapatıp yeniden açın ve tekrar deneyin.\n  3. Sorun sürerse View → Show log penceresinin ekran görüntüsünü atölye düzenleyicisine gönderin.\n\nTeknik ayrıntı: ') + t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: workshopText('script.e136f4a3bb45', '(ileti yok)')))
    }
}
println "✓ Dağılım grafiği sihirbazı açıldı."

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
