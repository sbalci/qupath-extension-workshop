/**
 * Yardımcı - Foundation Model Hazırlık ve Sağlamlık Sihirbazı (tek pencere)
 * ------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Açık slaytı bir patoloji TEMEL MODELİ (foundation model) hattına beslemeden
 *   önce hızlı, SALT-OKUR bir "hazırlık + sağlamlık" denetimi yapar — hepsi tek
 *   pencereden:
 *     1. HAZIRLIK OLGULARI — piksel boyutu (µm/px) ve buna göre 224 px karo için
 *        önerilen downsample / etkin µm/px; kestirilen/gömülü büyütme; piramit
 *        durumu; küratörlü karo dışa aktarımı için anotasyon var mı.
 *     2. SAĞLAMLIK KONTROL LİSTESİ — slayt durumuna göre uyarlanmış FARKINDALIK
 *        notları: tek- vs çok-kaynak / batch etkisi; girdi bütünlüğü ve düşmanca
 *        (adversarial) kırılganlık (UTAP — Wang ve ark. 2026); boya normalizasyonu
 *        ve doğrulama işaretçileri.
 *
 *   Tümüyle SALT-OKUR: hücre tespiti, sınıflandırma, anotasyon veya hiyerarşi
 *   DEĞİŞTİRMEZ. Hiçbir foundation model ÇALIŞTIRMAZ ve hiçbir pertürbasyon /
 *   saldırı ÜRETMEZ — yalnız farkındalık ve hazırlık raporu üretir.
 *
 * NEDEN:
 *   FM gömmeleri tarayıcı/merkez farkına (batch etkisi) ve gözle görülmeyen
 *   düşmanca pertürbasyonlara duyarlı olabilir. Bu yardımcı, FM-tabanlı bir aracı
 *   değerlendirirken sorulacak doğru soruları (girdi bütünlüğü, doğrulama,
 *   genelleme) bir kontrol listesi olarak hatırlatır.
 *   bkz. Ekler → Patolojide Temel Modeller — Genel Bakış.
 *
 * KULLANIM:
 *   1. Bir slayt açın.
 *   2. [Extensions → Atölye → Yardımcılar → Foundation model hazırlık ve sağlamlık sihirbazı]
 *   3. Raporu inceleyin; "Kopyala" ile panoya alıp not/protokolünüze ekleyin.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.common.GeneralTools

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// FM ön-eğitim hedef çözünürlüğü (çoğu patoloji FM'i ~20× ≈ 0.5 µm/px, 224 px karo)
final double TARGET_MPP = 0.5d
final int    TILE_PX     = 224

// ── Biçim yardımcıları (Locale.US — Türkçe yerelde virgül-ondalık olmasın) ──
def fmt = { double d, int dec -> Double.isNaN(d) ? '—' : String.format(java.util.Locale.US, '%.' + dec + 'f', d) }
def fmtInt = { long v -> String.format(java.util.Locale.US, '%,d', v) }

// ── Açık slayttan FM-hazırlık olgularını çıkar (salt-okur) ──────────────────
def extractReadiness = { imageData ->
    def m = new java.util.LinkedHashMap()
    def server = imageData.getServer()

    m.image_name = server.getMetadata().getName() ?: 'slide'
    int w = server.getWidth(); int h = server.getHeight()
    m.width_px = w; m.height_px = h
    m.is_rgb = server.isRGB()
    m.n_channels = server.nChannels()
    m.area_px = ((long) w) * ((long) h)

    double pw = Double.NaN
    try {
        def cal = server.getPixelCalibration()
        if (cal != null) pw = cal.getPixelWidthMicrons()
    } catch (Throwable ignore) {}
    m.pixel_um = pw
    m.calibrated = (!Double.isNaN(pw) && pw > 0)

    // 224 px karo için TARGET_MPP'e ulaştıran downsample + tamsayı downsample ile etkin çözünürlük
    if (m.calibrated) {
        double ds = TARGET_MPP / pw
        m.recommended_downsample = ds                           // kesin TARGET_MPP için (kesirli)
        int dsInt = (int) Math.max(1L, Math.round(ds))          // pratik tamsayı downsample
        m.integer_downsample = dsInt
        m.effective_mpp = pw * dsInt                            // tamsayı ds ile gerçek µm/px
        m.tile_physical_um = TILE_PX * (double) m.effective_mpp // 224 px karonun fiziksel kenarı (tamsayı ds)
        m.estimated_mag = Math.round(0.25d / pw * 40)           // sezgisel (gömülü büyütme yoksa)
    }
    try { double mag = server.getMetadata().getMagnification(); if (!Double.isNaN(mag) && mag > 0) m.objective_mag = mag } catch (Throwable ignore) {}

    m.has_pyramid = server.nResolutions() > 1
    m.pyramid_levels = server.nResolutions()
    m.large_no_pyramid = !(m.has_pyramid) && (m.area_px > 100_000_000L)   // >100 MP, piramitsiz

    // Anotasyon durumu (küratörlü karo dışa aktarımı için)
    int annCount = 0
    try { annCount = QP.getAnnotationObjects().size() } catch (Throwable ignore) {}
    m.annotation_count = annCount
    m.has_annotations = annCount > 0

    return m
}

// ── Rapor metnini kur (olgular + uyarlanmış sağlamlık kontrol listesi) ───────
def buildReport = { m ->
    def sb = new StringBuilder()
    def line = { String label, Object val -> sb << String.format(java.util.Locale.US, '  %-26s : %s%n', label, (val == null ? '—' : val.toString())) }

    sb << 'FOUNDATION MODEL — HAZIRLIK RAPORU\n'
    sb << '════════════════════════════════════\n'
    line('Görüntü', m.image_name)
    line('Boyut (px)', fmtInt((long) m.width_px) + ' × ' + fmtInt((long) m.height_px))
    line('RGB / kanal', (m.is_rgb ? 'evet' : 'hayır') + ' / ' + m.n_channels)
    sb << '\n'
    if (m.calibrated) {
        line('Piksel (µm/px)', fmt((double) m.pixel_um, 4))
        // Gömülü objektif büyütme varsa onu (güvenilir) göster; yoksa sezgiseli göster — ikisi
        // birden gösterilirse 40× (gömülü) ile ~38× (sezgisel) çelişiyormuş gibi görünür.
        if (m.objective_mag != null) line('Objektif büyütme', fmt((double) m.objective_mag, 1) + '×  (gömülü)')
        else                         line('Kestirilen büyütme', '~' + m.estimated_mag + '×  (sezgisel)')
        line('Önerilen downsample', fmt((double) m.recommended_downsample, 2) + '  (kesin ~' + TARGET_MPP + ' µm/px için)')
        line('Tamsayı downsample', '' + m.integer_downsample + '  → etkin ' + fmt((double) m.effective_mpp, 3) + ' µm/px')
        line('224 px karo ≈', fmt((double) m.tile_physical_um, 0) + ' µm  (' + TILE_PX + '×' + TILE_PX + ' px, tamsayı ds)')
    } else {
        line('Piksel (µm/px)', '— (KALİBRE DEĞİL)')
    }
    line('Piramit', m.has_pyramid ? ('evet, ' + m.pyramid_levels + ' seviye') : 'YOK')
    line('Anotasyon (küratör)', m.has_annotations ? (m.annotation_count + ' adet') : 'yok')

    // ── Sağlamlık + hazırlık kontrol listesi (uyarlanmış) ──
    sb << '\nSAĞLAMLIK VE HAZIRLIK KONTROL LİSTESİ\n'
    sb << '─────────────────────────────────────\n'
    def item = { String mark, String txt -> sb << '  ' + mark + ' ' + txt + '\n' }

    // Kalibrasyon
    if (m.calibrated) item('✓', 'Kalibrasyon var — karo ölçeği (µm/px) güvenilir.')
    else              item('⚠', 'Kalibre DEĞİL — önce piksel boyutunu ayarlayın (Yardımcılar → Kalibrasyon); aksi halde FM karo ölçeği belirsizdir.')

    // Piramit
    if (m.large_no_pyramid) item('⚠', 'Büyük görüntü piramitsiz — karo dışa aktarımı yavaş/bellek-yoğun olabilir.')

    // Küratörlük
    if (m.has_annotations) item('✓', 'Anotasyon mevcut — karo dışa aktarımını dokuyla sınırlayabilirsiniz (annotatedTilesOnly).')
    else                   item('•', 'Anotasyon yok — arka plan/artefakt karoları gömmeyi/dikkati seyreltir. Önce doku maskesi/anotasyon çıkarın (bkz. Ek B kalite kontrol).')

    // Batch etkisi (genelleme)
    item('•', 'Genelleme/batch etkisi: FM gömmeleri biyolojiden çok tarayıcı/merkez imzasını kodlayabilir (de Jong 2025). Çok-merkez doğrulayın; gerekirse boya normalizasyonu (Ek A) uygulayın.')

    // Düşmanca / girdi bütünlüğü (UTAP — farkındalık)
    item('•', 'Girdi bütünlüğü: FM çıktısı gözle görülmeyen düşmanca pertürbasyonlarla bozulabilir (UTAP — Wang ve ark. 2026). Yalnız güvenilir, değiştirilmemiş pikselleri besleyin; çıktıyı makullük açısından denetleyin.')

    // Doğrulama
    item('•', 'Doğrulama: hiçbir FM çıktısı, Ek W makullük/doğrulama kontrollerini geçmeden klinik karara dönüşmemeli.')

    sb << '\n⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir; klinik karar üretmez.'
    return sb.toString()
}

// ── Headless: yalnız açık slaytı raporla (UI yok, değişiklik yok) ────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println 'Önce bir slayt açın (headless modda yalnız açık slayt raporlanır).'; return }
    println buildReport(extractReadiness(imageData))
    return
}

// ── Tek pencere (salt-okur rapor); hata olursa rapor alanında satır içi gösterilir ──
def stage = null
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def reportRef     = new java.util.concurrent.atomic.AtomicReference('')
def render  // ileri bildirim

def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text)
    b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    return b
}
def copyToClipboard = { String txt ->
    def cb = javafx.scene.input.Clipboard.getSystemClipboard()
    def content = new javafx.scene.input.ClipboardContent()
    content.putString(txt ?: '')
    cb.setContent(content)
}

// ── Render: her durum değişiminde sahneyi sıfırdan kurar ────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())

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

    def imageData = QP.getCurrentImageData()
    title.setText('Foundation model hazırlık ve sağlamlık sihirbazı')
    if (imageData != null) {
        def rep
        try { rep = buildReport(extractReadiness(imageData)) }
        catch (Throwable t) { rep = 'Açık slayt okunamadı: ' + (t.getMessage() ?: t.getClass().getSimpleName()) }
        reportRef.set(rep)
        addMonoArea(rep)
    } else {
        reportRef.set('')
        addGuidance('Önce bir slayt açın. Bu sihirbaz açık slaydın FM-hazırlık olgularını ve sağlamlık kontrol listesini raporlar (salt-okur).')
    }
    actions.add(navButton('Kapat', { stage.close() }))
    if (imageData != null) actions.add(navButton('Kopyala', { copyToClipboard(reportRef.get()) }, 'Raporu panoya kopyala'))
    actions.add(navButton('⟳ Yenile', { render() }, 'Slayt/anotasyon değiştiyse yeniden hesapla'))

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

    def disclaimer = new javafx.scene.control.Label('Salt-okur; FM çalıştırmaz, pertürbasyon üretmez. Yalnızca araştırma/eğitim amaçlı ölçüm üretir.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; ' +
        '-fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(center)
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 820, 600))
}

// ── Açılış ───────────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Foundation model hazırlık ve sağlamlık sihirbazı')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println '✓ Foundation model hazırlık ve sağlamlık sihirbazı açıldı.'
