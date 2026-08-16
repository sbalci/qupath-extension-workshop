/**
 * Yardımcı - Dikdörtgen seçimlerden görüntü oluştur (kırpılmış proje girdileri)
 * -----------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 * Açık slaytta çizilmiş DİKDÖRTGEN anotasyonların her birinden, projeye
 * KIRPILMIŞ yeni bir görüntü girdisi (project entry) ekler. Bölgenin piksel
 * verisi KOPYALANMAZ: yeni girdiler QuPath'in CroppedImageServer'ı üzerinden
 * orijinal dosyaya başvurur — bölge için bağımsız bir görüntü kopyası
 * üretilmez (proje klasörüne yalnız küçük resim ve anotasyon verisi yazılır).
 *
 * NE YAPAR:
 *   • Her uygun dikdörtgen → projeye yeni bir girdi. Girdi adı = anotasyonun
 *     adı; anotasyon adsızsa "<slayt> - kirpma N". Küçük resim (thumbnail)
 *     otomatik yazılır.
 *   • İsteğe bağlı (varsayılan açık): dikdörtgenle örtüşen ANOTASYONLAR
 *     kesişim alınarak kırpılır ve yeni girdiye kopyalanır; koordinatlar yeni
 *     girdiye göre kaydırılır. Sınıf ve ad korunur; ölçümler KOPYALANMAZ
 *     (kırpma, alan tabanlı ölçümleri geçersiz kılar). Hücre TESPİTLERİ ve
 *     kırpma çerçevelerinin kendileri kopyalanmaz.
 *   • Aynı adlı bir proje girdisi zaten varsa o dikdörtgen ATLANIR ve
 *     raporlanır — betiği yeniden çalıştırmak yinelenen girdi üretmez.
 *
 * KAPSAM (diyalogdan seçilir):
 *   • Seçili dikdörtgenler — yalnızca seçtiğiniz dikdörtgen anotasyonlar.
 *   • Tüm dikdörtgenler   — slayttaki her dikdörtgen anotasyon.
 *   • İsteğe bağlı sınıf filtresi (orijinal betik "ROI" sınıfını kullanır).
 *   Dikdörtgen DIŞI şekiller (elips, poligon, fırça) her zaman yok sayılır.
 *
 * KULLANIM:
 *   1. Dikdörtgen aracıyla (R) bölge(ler) çizin; girdiye ad vermek için
 *      anotasyona sağ tık → [Annotations → Set properties] ile ad yazın.
 *   2. [Extensions → Atölye → Yardımcılar → İçe ve dışa aktarma / veri →
 *      Dikdörtgen seçimlerden görüntü oluştur]
 *   3. Formdan kapsamı seçip "Girdileri oluştur" düğmesine basın.
 *
 * NE YAPMAZ / SINIRLAR:
 *   • Bölgenin bağımsız bir görüntü dosyasını (OME-TIFF / PNG vb.) YAZMAZ —
 *     bunun için "Görüntü alanı çıkart (Extract Region)" yardımcısını kullanın.
 *   • Yeni girdiler orijinal görüntü dosyasına BAĞIMLIDIR: kaynak dosyayı
 *     taşır ya da silerseniz kırpılmış girdiler açılamaz.
 *   • Döndürülmüş/serbest şekiller desteklenmez (yalnız eksen hizalı dikdörtgen).
 *
 * KAYNAK / TEŞEKKÜR (attribution):
 *   • Egor Zindy (EP.Zindy) — "Script for generating cropped image entries
 *     from rectangular annotations", image.sc forumu (Mart 2025):
 *     https://forum.image.sc/t/script-for-generating-cropped-image-entries-from-rectangular-annotations/109554
 *   • Örtüşen anotasyonları kesişimle kırpma deseni: ym.lim, image.sc forumu:
 *     https://forum.image.sc/t/quick-way-to-delete-parts-of-annotations/66681/10
 *   Bu betik, yukarıdaki paylaşımın atölye sözleşmelerine (Türkçe tek pencere
 *   form, Locale.US, kapsam seçimi, ad çakışma kontrolü, görüntü sınırına
 *   kırpma) göre yeniden yazılmış bir uyarlamasıdır.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.gui.commands.ProjectCommands
import qupath.lib.images.servers.CroppedImageServer
import qupath.lib.objects.PathObjects
import qupath.lib.regions.ImageRegion
import qupath.lib.roi.RectangleROI
import qupath.lib.roi.ROIs
import qupath.lib.roi.RoiTools

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

            def alwaysTop = new javafx.scene.control.CheckBox("Üstte tut")
            alwaysTop.setSelected(true)
            alwaysTop.selectedProperty().addListener(
                { obs, o, n -> stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener
            )

            def copyBtn = new javafx.scene.control.Button("Kopyala")
            copyBtn.setOnAction({
                def cb = javafx.scene.input.Clipboard.getSystemClipboard()
                def content = new javafx.scene.input.ClipboardContent()
                content.putString(windowBody)
                cb.setContent(content)
            })

            def closeBtn = new javafx.scene.control.Button("Kapat")
            closeBtn.setDefaultButton(true)
            closeBtn.setOnAction({ stage.close() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)

            def buttons = new javafx.scene.layout.HBox(10, alwaysTop, spacer, copyBtn, closeBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(8))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(textArea)
            def __footer = new javafx.scene.control.Label("QuPath Atölye Betikleri · araştırma/eğitim amaçlı")
            __footer.setMaxWidth(Double.MAX_VALUE)
            __footer.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.55; -fx-font-style: italic; -fx-padding: 2 4 2 4; -fx-font-size: 11px;")
            def __bottom = new javafx.scene.layout.VBox(8.0, __footer, buttons)
            __bottom.setPadding(new javafx.geometry.Insets(8))
            root.setBottom(__bottom)

            stage.setScene(new javafx.scene.Scene(root, 760, 560))
            stage.show()
        } catch (Throwable t) {
            qupath.fx.dialogs.Dialogs.showMessageDialog(windowTitle, windowBody)
        }
    }
}

// ── 1) Ön kontroller ───────────────────────────────────────────────
def project = QP.getProject()
if (project == null) {
    def msg = "Proje açık değil.\n\n" +
              "Kırpılmış girdiler bir PROJEYE eklenir. Önce [File → Project... → Create project]\n" +
              "ile bir proje oluşturun (ya da mevcut projeyi açın), slaytı projeden açın."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Proje gerekli", msg)
    return
}

def imageData = QP.getCurrentImageData()
if (imageData == null) {
    def msg = "Görüntü açık değil. Önce projedeki bir slaytı açın ve dikdörtgen anotasyon(lar) çizin."
    if (isHeadless) println msg else Dialogs.showErrorMessage("Görüntü açık değil", msg)
    return
}

def hierarchy = imageData.getHierarchy()
def serverOriginal = imageData.getServer()
def imageType = imageData.getImageType()

def baseName = project.getEntry(imageData)?.getImageName()
if (baseName == null || baseName.trim().isEmpty())
    baseName = serverOriginal.getMetadata().getName() ?: "goruntu"

def isRect = { ann -> ann.getROI() instanceof RectangleROI }
def allRects = QP.getAnnotationObjects().findAll { isRect(it) }
def selRects = hierarchy.getSelectionModel().getSelectedObjects()
        .findAll { it.isAnnotation() && isRect(it) } as List

if (allRects.isEmpty()) {
    def msg = "Bu slaytta dikdörtgen anotasyon yok.\n\n" +
              "Dikdörtgen aracıyla (R) bir ya da daha çok bölge çizin; girdiye ad vermek için\n" +
              "anotasyona sağ tık → [Annotations → Set properties] ile ad yazın, sonra bu betiği\n" +
              "tekrar çalıştırın. (Elips/poligon/fırça şekilleri bu betikte kullanılmaz.)"
    if (isHeadless) println msg else Dialogs.showWarningNotification("Dikdörtgen yok", msg)
    return
}

def classNames = allRects.collect { it.getPathClass()?.toString() }
        .findAll { it != null }.unique().sort()

// ── 2) Seçenek formu — tek pencere, latch ile Map döner ─────────────
def showOptionsForm = { int selCount, int allCount, List<String> classChoices ->
    if (isHeadless) {
        // Headless: orijinal betiğin (Zindy) davranışına yakın varsayılanlar —
        // "ROI" sınıfı varsa yalnız o sınıflı dikdörtgenler, yoksa tümü.
        def cls = classChoices.contains("ROI") ? "ROI" : null
        println "=== Kırpılmış girdiler (headless): " +
                (cls != null ? "yalnız '${cls}' sınıflı" : "tüm") +
                " dikdörtgenler; örtüşen anotasyonlar kopyalanır ==="
        return [scope: 'all', classFilter: cls, copyAnnotations: true]
    }
    def latch = new java.util.concurrent.CountDownLatch(1)
    def result = new java.util.concurrent.atomic.AtomicReference<Map>(null)

    javafx.application.Platform.runLater {
        try {
            def stage = new javafx.stage.Stage()
            stage.initModality(javafx.stage.Modality.NONE)
            stage.setTitle("Kırpılmış proje girdileri — seçenekler")
            stage.setAlwaysOnTop(true)

            def scopeBox = new javafx.scene.control.ChoiceBox<String>()
            scopeBox.getItems().addAll("Tüm dikdörtgenler (${allCount})".toString(),
                                       "Seçili dikdörtgenler (${selCount})".toString())
            if (selCount > 0)
                scopeBox.setValue("Seçili dikdörtgenler (${selCount})".toString())
            else
                scopeBox.setValue("Tüm dikdörtgenler (${allCount})".toString())

            def classBox = new javafx.scene.control.ChoiceBox<String>()
            classBox.getItems().add("(sınıf filtresi yok)")
            classChoices.each { classBox.getItems().add(it) }
            if (selCount == 0 && classChoices.contains("ROI"))
                classBox.setValue("ROI")
            else
                classBox.setValue("(sınıf filtresi yok)")

            def copyCheck = new javafx.scene.control.CheckBox("Örtüşen anotasyonları kırparak yeni girdiye kopyala")
            copyCheck.setSelected(true)

            def help = { String s ->
                def l = new javafx.scene.control.Label(s)
                l.setWrapText(true)
                l.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;")
                l.setMaxWidth(340)
                return l
            }

            def grid = new javafx.scene.layout.GridPane()
            grid.setHgap(12); grid.setVgap(8)
            grid.setPadding(new javafx.geometry.Insets(12))
            int r = 0
            grid.add(new javafx.scene.control.Label("Kapsam:"), 0, r)
            grid.add(scopeBox, 1, r)
            grid.add(help("Seçili: yalnız seçtiğiniz dikdörtgen anotasyonlar. Tüm: slayttaki her dikdörtgen anotasyon."), 2, r); r++
            grid.add(new javafx.scene.control.Label("Sınıf filtresi:"), 0, r)
            grid.add(classBox, 1, r)
            grid.add(help("Yalnız bu sınıfa atanmış dikdörtgenler kullanılır. Orijinal betik (Zindy, image.sc) 'ROI' sınıfını kullanır."), 2, r); r++
            grid.add(copyCheck, 0, r, 2, 1)
            grid.add(help("Dikdörtgenle örtüşen anotasyonlar kesişim alınarak kopyalanır; sınıf ve ad korunur, ölçümler kopyalanmaz."), 2, r); r++

            def okBtn = new javafx.scene.control.Button("Girdileri oluştur")
            okBtn.setDefaultButton(true)
            okBtn.setOnAction({
                def scope = scopeBox.getValue().startsWith("Seçili") ? 'selected' : 'all'
                def cf = classBox.getValue() == "(sınıf filtresi yok)" ? null : classBox.getValue()
                result.set([scope: scope, classFilter: cf, copyAnnotations: copyCheck.isSelected()])
                stage.close()
            })
            def cancelBtn = new javafx.scene.control.Button("İptal")
            cancelBtn.setCancelButton(true)
            cancelBtn.setOnAction({ result.set(null); stage.close() })

            stage.setOnHidden({ latch.countDown() })

            def spacer = new javafx.scene.layout.Region()
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
            def buttons = new javafx.scene.layout.HBox(10, spacer, cancelBtn, okBtn)
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT)
            buttons.setPadding(new javafx.geometry.Insets(10))

            def root = new javafx.scene.layout.BorderPane()
            root.setCenter(grid)
            root.setBottom(buttons)
            stage.setScene(new javafx.scene.Scene(root, 700, 220))
            stage.show()
        } catch (Throwable t) {
            result.set(null)
            latch.countDown()
        }
    }
    latch.await()
    return result.get()
}

def opts = showOptionsForm(selRects.size(), allRects.size(), classNames)
if (opts == null) {
    println "Kırpılmış girdi oluşturma iptal edildi."
    return
}

def rects
if (opts.scope == 'selected')
    rects = selRects
else
    rects = allRects
if (opts.classFilter != null)
    rects = rects.findAll { it.getPathClass()?.toString() == opts.classFilter }

if (rects.isEmpty()) {
    def msg = "Seçilen kapsam ve sınıf filtresiyle eşleşen dikdörtgen anotasyon yok.\n" +
              "Kapsamı ya da sınıf filtresini değiştirip yeniden deneyin."
    if (isHeadless) println msg else Dialogs.showWarningNotification("Eşleşen dikdörtgen yok", msg)
    return
}

// ── 3) Her dikdörtgen → kırpılmış proje girdisi ─────────────────────
def existingNames = project.getImageList().collect { it.getImageName() } as Set
def cropRectSet = rects as Set   // kırpma çerçevelerinin kendileri kopyalanmaz

def created = []   // [ad, genişlik px, yükseklik px, kopyalanan anotasyon]
def skipped = []   // [ad, neden]
int idx = 0

rects.each { rectAnn ->
    idx++
    def roi = rectAnn.getROI()

    def entryName = rectAnn.getName()
    if (entryName == null || entryName.trim().isEmpty())
        entryName = String.format(java.util.Locale.US, "%s - kirpma %d", baseName, idx)

    // Bölgeyi görüntü sınırlarına kırp (dikdörtgen slayt kenarından taşabilir)
    int x0 = Math.max(0, (int) Math.floor(roi.getBoundsX()))
    int y0 = Math.max(0, (int) Math.floor(roi.getBoundsY()))
    int x1 = Math.min(serverOriginal.getWidth(),  (int) Math.ceil(roi.getBoundsX() + roi.getBoundsWidth()))
    int y1 = Math.min(serverOriginal.getHeight(), (int) Math.ceil(roi.getBoundsY() + roi.getBoundsHeight()))
    if (x1 - x0 < 1 || y1 - y0 < 1) {
        skipped << [entryName, "dikdörtgen görüntü sınırlarının dışında"]
        return
    }
    if (existingNames.contains(entryName)) {
        skipped << [entryName, "aynı adlı proje girdisi zaten var"]
        return
    }

    def region = ImageRegion.createInstance(x0, y0, x1 - x0, y1 - y0, roi.getZ(), roi.getT())
    def croppedServer = null
    def entry = null
    boolean nameRecorded = false
    try {
        croppedServer = new CroppedImageServer(serverOriginal, region)
        entry = ProjectCommands.addSingleImageToProject(project, croppedServer, imageType)
        if (entry == null)
            throw new IllegalStateException("girdi projeye eklenemedi (addSingleImageToProject null döndü)")
        entry.setImageName(entryName)
        // Ad hemen kaydedilir (saveImageData'dan ÖNCE): aynı ada sahip ikinci bir
        // dikdörtgen, ilki daha sonra başarısız olsa bile bu koşuda yakalanır.
        nameRecorded = existingNames.add(entryName)
        try {
            entry.setThumbnail(ProjectCommands.getThumbnailRGB(croppedServer))
        } catch (Throwable tThumb) {
            println "  (küçük resim yazılamadı: ${entryName} — ${tThumb.getMessage()})"
        }

        int nCopied = 0
        // withCloseable: readImageData()'nın kendi içinde açtığı YENİ sunucu örneği
        // kapatılır (görüntüleyicinin sunucusu değil) — koşu başına okuyucu sızmaz.
        entry.readImageData().withCloseable { croppedImageData ->
            if (opts.copyAnnotations) {
                // Kesişimi görüntü sınırına kırpılmış bölgeyle al — böylece kaydırma
                // sonrası koordinatlar her zaman yeni girdinin içinde kalır (ym.lim deseni).
                def cropRoi = ROIs.createRectangleROI(x0, y0, x1 - x0, y1 - y0, roi.getImagePlane())
                def newObjects = []
                hierarchy.getAnnotationsForRegion(region).each { other ->
                    if (other == null || cropRectSet.contains(other)) return
                    def otherRoi = other.getROI()
                    if (otherRoi == null || otherRoi.isEmpty()) return
                    if (otherRoi.getZ() != roi.getZ() || otherRoi.getT() != roi.getT()) return
                    def interRoi = null
                    try {
                        interRoi = RoiTools.intersection(cropRoi, otherRoi)
                    } catch (Throwable tInt) {
                        return
                    }
                    if (interRoi == null || interRoi.isEmpty()) return
                    def moved = interRoi.translate(-((double) x0), -((double) y0))
                    def obj = PathObjects.createAnnotationObject(moved, other.getPathClass())
                    if (other.getName() != null) obj.setName(other.getName())
                    newObjects << obj
                }
                if (!newObjects.isEmpty()) {
                    croppedImageData.getHierarchy().addObjects(newObjects)
                    nCopied = newObjects.size()
                }
            }
            entry.saveImageData(croppedImageData)
        }
        created << [entryName, x1 - x0, y1 - y0, nCopied]
    } catch (Throwable t) {
        // Yarım kalmış girdiyi geri al — aksi halde syncChanges() bozuk/verisiz bir
        // girdiyi project.qpproj'a kalıcılaştırır.
        boolean rolledBack = false
        if (entry != null) {
            try {
                project.removeImage(entry, true)
                rolledBack = true
            } catch (Throwable ignore) {}
        }
        if (rolledBack && nameRecorded)
            existingNames.remove(entryName)
        skipped << [entryName, "hata: ${t.getMessage()}" +
            (rolledBack ? " (yarım girdi geri alındı)"
                        : " (yarım girdi projede kalmış olabilir — Project panelinden silebilirsiniz)")]
    } finally {
        // Kırpılmış sunucunun KENDİ karo önbelleğini temizler; sarmaladığı orijinal
        // sunucu (açık görüntüleyicininki) kapatılmaz (TransformingImageServer close
        // override etmez).
        try { croppedServer?.close() } catch (Throwable ignore) {}
    }
}

// ── 4) Projeyi kaydet + arayüzü tazele ──────────────────────────────
try {
    project.syncChanges()
} catch (Throwable t) {
    println "Proje kaydedilemedi (syncChanges): ${t.getMessage()}"
}
def gui = qupath.lib.gui.QuPathGUI.getInstance()
if (gui != null)
    javafx.application.Platform.runLater { gui.refreshProject() }

// ── 5) Sonucu sun ───────────────────────────────────────────────────
def body = new StringBuilder()
body << "SEÇİMLERDEN OLUŞTURULAN ALANLAR\n"
body << "═══════════════════════════════\n\n"
body << String.format(java.util.Locale.US, "Kaynak görüntü      : %s%n", baseName)
body << String.format(java.util.Locale.US, "Oluşturulan girdi   : %d%n", created.size())
body << String.format(java.util.Locale.US, "Atlanan dikdörtgen  : %d%n", skipped.size())
if (!created.isEmpty()) {
    body << "\nOluşturulanlar:\n"
    created.each { c ->
        body << String.format(java.util.Locale.US, "  ✓ %s  (%d×%d px, %d anotasyon kopyalandı)%n",
            c[0], c[1] as int, c[2] as int, c[3] as int)
    }
}
if (!skipped.isEmpty()) {
    body << "\nAtlananlar:\n"
    skipped.each { s ->
        body << String.format(java.util.Locale.US, "  • %s — %s%n", s[0], s[1])
    }
}
body << "\nPiksel verisi KOPYALANMADI: yeni girdiler orijinal dosyaya başvurur\n"
body << "(CroppedImageServer). Kaynak dosyayı taşır/silerseniz girdiler açılamaz.\n"
body << "Bağımsız bir dosya gerekiyorsa: Yardımcılar → Görüntü alanı çıkart.\n\n"
body << "Kaynak: Egor Zindy (image.sc #109554); kırpma deseni ym.lim (#66681).\n\n"
body << "⚠️ Yalnızca araştırma/eğitim amaçlı ölçüm üretir."

showResultWindow("Seçimlerden oluşturulan alanlar", body.toString())
println String.format(java.util.Locale.US, "✓ Kırpılmış proje girdileri: %d oluşturuldu, %d atlandı.",
    created.size(), skipped.size())
