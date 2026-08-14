/**
 * Yardımcı — Senkron ızgara: seri slaytları birlikte aç
 * ------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Aynı projedeki 2+ slaydı QuPath'in ÇOKLU GÖRÜNÜM (multi-view) ızgarasına yan yana
 *   açar ve "Synchronize viewers"ı açar — bir panelde kaydırma/yakınlaştırma diğerlerini
 *   birlikte hareket ettirir. Bir vakanın seri kesitlerini (ör. H&E + CK7 + CDX2 + CK20)
 *   birlikte incelemenin en hızlı yolu. QuPath'in ViewerManager API'sini çağırır:
 *   setGridSize(satır, sütun) → getAllViewers() → viewer.setImageData(...) →
 *   setSynchronizeViewers(true).
 *
 * ÖNEMLİ — "birlikte hareket" ≠ "otomatik hizalama":
 *   Synchronize viewers HAM koordinatları eşler; bir hizalama dönüşümü UYGULAMAZ. Yani
 *   seri kesitler birlikte kayar ama derine yakınlaştıkça ÖRTÜŞMEYEBİLİR (her kesitin
 *   kendi koordinat başlangıcı farklıdır). Gerçek ÖRTÜŞME için panellere ÖNCEDEN
 *   HİZALANMIŞ görüntüler verin: VALIS çıktısı (Ekler → Görüntü Hizalama §7) ya da
 *   dönüşümü OME-TIFF'e gömülmüş slaytlar (§6). Bu yardımcı hem ham seri kesitlerle
 *   (birlikte kayar) hem de hizalanmış çıktılarla (birlikte kayar + örtüşür) çalışır.
 *   Ayrıntı: Ekler → Görüntü Hizalama §9.
 *
 * NE ÖLÇER: Hiçbir şey — yalnız bir GÖRÜNTÜLEME düzenidir (nesne/ölçüm üretmez, açık
 *   slaytları değiştirmez; panellere projedeki slaytların KAYITLI hâlini yükler).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı görüntüleme.
 */

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Stage
import qupath.fx.dialogs.Dialogs

def gui = qupath.lib.gui.QuPathGUI.getInstance()
if (gui == null) { println 'Bu yardımcı QuPath arayüzü gerektirir (arayüzsüz çalışmaz).'; return }

def project = gui.getProject()
if (project == null) {
    Platform.runLater { Dialogs.showMessageDialog('Senkron ızgara', 'Önce bir PROJE açın — ızgara projedeki slaytlardan doldurulur.') }
    return
}
def entries = new ArrayList(project.getImageList())
if (entries.size() < 2) {
    Platform.runLater { Dialogs.showMessageDialog('Senkron ızgara', 'Projede en az 2 slayt olmalı (senkron ızgara birden çok slayt gösterir).') }
    return
}

int MAX_PANELS = 6   // ızgara en fazla 2×3 (doğrulanmış); fazlası ilk 6'ya kırpılır

def gm = gui.getViewerManager()

// Açık slayttan "vaka ön-eki" çıkar → aynı vakanın kardeş slaytlarını ön-işaretle
def activeData = gui.getImageData()
def openEntry = null
try { openEntry = activeData != null ? project.getEntry(activeData) : null } catch (Throwable ignore) {}
String openName = openEntry?.getImageName()
String casePrefix = null
if (openName != null && !openName.isBlank()) {
    def tok = openName.trim().split(/[\s_]+/)
    if (tok.length > 0) casePrefix = tok[0]
}

// gridFor(n): {satır, sütun} — 2→1×2, 3→1×3, 4→2×2, 5–6→2×3
def gridFor = { int c ->
    int k = Math.max(1, Math.min(MAX_PANELS, c))
    switch (k) {
        case 1:  return [1, 1]
        case 2:  return [1, 2]
        case 3:  return [1, 3]
        case 4:  return [2, 2]
        default: return [2, 3]
    }
}

Platform.runLater {
    def stage = new Stage()
    stage.setTitle('Senkron ızgara — seri slaytları birlikte aç')

    def info = new Label(
        'Açılacak slaytları işaretleyin (aynı vakanın seri kesitleri). QuPath çoklu görünüm ' +
        'ızgarası kurar ve panelleri SENKRONLAR — birinde kaydır/yakınlaştır, hepsi birlikte hareket eder. ' +
        'En fazla ' + MAX_PANELS + ' panel (2×3).')
    info.setWrapText(true); info.setMaxWidth(560)

    def caveat = new Label(
        '⚠ Senkron = "birlikte hareket", otomatik hizalama DEĞİL. Ham seri kesitler derine ' +
        'yakınlaştıkça örtüşmeyebilir. Gerçek örtüşme için ÖNCEDEN hizalanmış görüntüler açın ' +
        '(VALIS çıktısı / dönüşümü gömülü OME-TIFF). Ayrıntı: Ekler → Görüntü Hizalama §9.')
    caveat.setWrapText(true); caveat.setMaxWidth(560); caveat.setStyle('-fx-font-style: italic;')

    def boxes = []
    def listBox = new VBox(4)
    entries.each { e ->
        def cb = new CheckBox(e.getImageName())
        cb.setUserData(e)
        if (casePrefix != null && e.getImageName()?.startsWith(casePrefix)) cb.setSelected(true)
        boxes << cb
        listBox.getChildren().add(cb)
    }
    // Hiç ön-işaret oluşmadıysa açık slaydı işaretle (en az bir başlangıç seçimi)
    if (openEntry != null && boxes.every { !it.isSelected() }) {
        boxes.find { it.getUserData() == openEntry }?.setSelected(true)
    }
    def scroll = new ScrollPane(listBox)
    scroll.setFitToWidth(true); scroll.setPrefViewportHeight(220)

    def selectAll = new Button('Tümünü seç')
    selectAll.setOnAction { boxes.each { it.setSelected(true) } }
    def clearAll = new Button('Tümünü kaldır')
    clearAll.setOnAction { boxes.each { it.setSelected(false) } }
    def selRow = new HBox(8, selectAll, clearAll)

    def status = new Label('')
    status.setWrapText(true); status.setMaxWidth(560)

    def openBtn = new Button('Aç (senkron ızgara)')
    def backBtn = new Button('Tek görünüme dön')
    def closeBtn = new Button('Kapat')
    closeBtn.setOnAction { stage.close() }

    openBtn.setOnAction {
        def selected = boxes.findAll { it.isSelected() }.collect { it.getUserData() }
        if (selected.size() < 2) { status.setText('En az 2 slayt seçin.'); return }
        int n = selected.size()
        String note = ''
        if (n > MAX_PANELS) {
            note = ' (' + n + ' seçildi; ilk ' + MAX_PANELS + ' açılıyor)'
            selected = new ArrayList(selected.subList(0, MAX_PANELS))
            n = MAX_PANELS
        }
        // Açık slaydı ilk panele al (kararlı yerleşim — getAllViewers'ın ilki)
        if (openEntry != null && selected.contains(openEntry)) {
            selected = [openEntry] + selected.findAll { it != openEntry }
        }
        // Kaydedilmemiş değişiklik onayı (ızgara, açık panellerin içeriğinin yerini alır)
        boolean anyUnsaved = gm.getAllViewers().any { v ->
            def d = v.getImageData()
            try { d != null && d.isChanged() } catch (Throwable t) { false }
        }
        if (anyUnsaved && !Dialogs.showConfirmDialog('Senkron ızgara',
                'Görüntüleyicilerdeki bazı görüntülerde kaydedilmemiş değişiklik var; ızgara bunların ' +
                'yerini alır ve değişiklikler kaybolabilir. Devam edilsin mi?')) {
            return
        }
        def g = gridFor(n)
        boolean resized = gm.setGridSize((int) g[0], (int) g[1])
        if (!resized) {
            status.setText('Izgara ayarlanamadı (çok fazla açık görüntüleyici olabilir). ' +
                'Fazla görüntüleyicileri kapatıp yeniden deneyin.')
            return
        }
        def viewers = gm.getAllViewers()
        int m = Math.min(selected.size(), viewers.size())
        for (int i = 0; i < m; i++) {
            def entry = selected[i]
            def viewer = viewers[i]
            // Görüntüyü FX-DIŞI iş parçacığında yükle, sonra FX'te panele koy (CaseCompare deseni)
            new Thread({
                try {
                    def data = entry.readImageData()
                    Platform.runLater {
                        try { viewer.setImageData(data) }
                        catch (Throwable ex) { println 'Panel açılamadı: ' + entry.getImageName() + ' — ' + ex.getMessage() }
                    }
                } catch (Throwable ex) {
                    Platform.runLater { println 'Panel yüklenemedi: ' + entry.getImageName() + ' — ' + ex.getMessage() }
                }
            }, 'senkron-izgara-' + i).start()
        }
        gm.setSynchronizeViewers(true)
        if (!viewers.isEmpty()) gm.setActiveViewer(viewers[0])
        status.setText(m + ' slayt senkron ızgarada açılıyor' + note + '. Kaydırma/yakınlaştırma artık ' +
            'birlikte. (Yakınlaştırmayı tam eşitlemek için: View → Multi-view → Match viewer resolutions.)')
    }

    backBtn.setOnAction {
        gm.setSynchronizeViewers(false)
        def all = gm.getAllViewers()
        def keep = gm.getActiveViewer() ?: (all.isEmpty() ? null : all.get(0))
        for (v in new ArrayList(all)) {
            if (v == keep) continue
            if (!gui.closeViewer(v)) {
                status.setText('Kapatma iptal edildi; ızgara olduğu gibi bırakıldı (senkron kapalı).')
                return
            }
        }
        gm.setGridSize(1, 1)
        status.setText('Tek görünüme dönüldü (senkron kapalı).')
    }

    def btnRow = new HBox(8, openBtn, backBtn, closeBtn)
    def root = new VBox(10, info, scroll, selRow, caveat, btnRow, status)
    root.setPadding(new Insets(14))
    VBox.setVgrow(scroll, Priority.ALWAYS)
    stage.setScene(new Scene(root, 600, 540))
    stage.show()
}
