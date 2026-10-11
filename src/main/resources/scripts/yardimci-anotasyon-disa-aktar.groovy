/**
 * Yardımcı - Toplu Anotasyon Dışa Aktarma (GeoJSON · ImageScope XML · ASAP XML · COCO · merkez tablosu)
 * -------------------------------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Bu görüntünün, seçili proje görüntülerinin ya da tüm projenin anotasyonlarını (isteğe bağlı
 *   tespitleriyle) TEK ÇALIŞTIRMADA birden çok biçime yazar:
 *     • GeoJSON (QuPath FeatureCollection) — delik, çok parçalı, nokta, çizgi, sınıf, ad, UUID korunur
 *     • Aperio ImageScope XML — sınıf başına katman; delikler NegativeROA bölgeleri; 2 noktalı çizgi = cetvel
 *     • ASAP XML — sınıf başına grup; delikler "Exclusion" grubunda; nokta = Dot/PointSet
 *     • COCO JSON — tüm grup için tek dosya; çokgen + bbox + gerçek alan
 *     • Merkez tablosu (TSV/CSV) — nesne başına merkez, kutu, alan (yalnız geometri)
 *   Her çalıştırma bir _manifest.tsv yazar; "Toplu anotasyon içe aktarma" bu dosyayla dosyaları
 *   görüntülere TAM eşler.
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • Bu bir VERİ AKTARIM betiğidir: geometriyi ve sınıf adlarını dosyaya taşır; alan/merkez yalnız
 *     geometriden hesaplanır. Patoloji yorumu, tanı, grade veya klinik karar üretmez.
 *   • Koordinatlar görüntünün TABAN (level-0) piksel uzayındadır; y ekseni aşağı doğrudur.
 *   • Bir biçimin taşıyamadığı geometriler (ImageScope'ta nokta; ImageScope/ASAP'ta çoklu çizgi;
 *     COCO'da delik) atlanır ya da uyarılır ve _manifest.tsv'de görüntü başına sayılır.
 *   • Ölçüm tabloları bu betiğin işi değildir — onlar için Veri dışa aktarma modülü.
 *
 * KULLANIM:
 *   1. [Extensions → Atölye → Yardımcılar → İçe ve dışa aktarma / veri → Toplu anotasyon dışa aktarma]
 *   2. Kapsamı, nesneleri, biçimleri ve kayıt yerini seçin → "Dışa aktar".
 *   Arayüzsüz (headless / HPC):
 *     QuPath script yardimci-anotasyon-disa-aktar.groovy --args "proje=<proje.qpproj>"
 *       --args "bicim=geojson,imagescope,asap,coco,csv" --args "cikis=<klasör>"
 *       [--args "tespit=evet"] [--args "olcum=hayir"] [--args "siniflar=Tümör;Stroma"] (alt sınıflar dahil) [--args "ayrac=virgul"]
 *     QuPath'i -p ile ÇALIŞTIRMAYIN (betik projeyi kendisi açar); bilinmeyen anahtar/biçim hata verir (çıkış kodu 1).
 *
 * ÇIKTI:
 *   <çıkış>/_manifest.tsv · geojson/<görüntü adı>.geojson · imagescope/<slayt>.xml · asap/<slayt>.xml ·
 *   coco.json · merkezler.tsv (ya da .csv)
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • QuPath PathIO.exportObjectsAsGeoJSON (FEATURE_COLLECTION) — QuPath 0.6.0+.
 *   • ImageScope Region tipleri (0 çokgen, 1 dikdörtgen, 2 elips, 3 ok, 4 cetvel) ve LineColor = Windows
 *     COLORREF (r + g·256 + b·65536): yayımlanmış açık kaynak ImageScope okuyucularının belgelediği yapı.
 *   • ASAP XML: computationalpathologygroup/ASAP — annotation/XmlRepository.cpp, Annotation.cpp.
 *   • COCO "object detection" anotasyon biçimi: cocodataset.org/#format-data.
 *   • Toplu proje döngüsü + rapor fikri: Balcı S. — sectra-qupath (MIT), doi:10.5281/zenodo.15542395.
 *     Kod özgündür (biçim yazıcıları biçim tanımlarından temiz-oda yazılmıştır).
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.
 */

import qupath.fx.dialogs.Dialogs
import qupath.fx.dialogs.FileChoosers
import qupath.lib.scripting.QP
import qupath.lib.io.PathIO
import qupath.lib.io.GsonTools
import qupath.lib.projects.ProjectIO
import qupath.lib.common.ColorTools
import qupath.lib.objects.classes.PathClass
import org.locationtech.jts.geom.Polygon
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def DISCLAIMER = '⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.'

// ── Biçimler ve sütunlar ─────────────────────────────────────────────────────
def FORMAT_KEYS = ['geojson', 'imagescope', 'asap', 'coco', 'csv']
def FORMAT_LABELS = [geojson: 'GeoJSON (QuPath; kayıpsız)', imagescope: 'Aperio ImageScope XML',
                     asap: 'ASAP XML', coco: 'COCO JSON (makine öğrenmesi)', csv: 'Merkez tablosu (TSV/CSV)']
def UNCLASSIFIED = '(sınıfsız)'
def EXCLUSION_RGB = ColorTools.packRGB(128, 128, 128)
def DEFAULT_RGB = ColorTools.packRGB(255, 0, 0)
def MANIFEST_COLS = ['goruntu_adi', 'giris_id', 'uri_dosya', 'bicim', 'cikti_dosyasi', 'nesne_sayisi',
                     'atlanan_nokta', 'atlanan_cizgi', 'delik_uyarisi', 'durum', 'not']
def CSV_COLS = ['goruntu_adi', 'uuid', 'nesne_tipi', 'sinif', 'ad', 'roi_tipi', 'z', 't',
                'merkez_x_px', 'merkez_y_px', 'merkez_x_um', 'merkez_y_um',
                'bbox_x_px', 'bbox_y_px', 'bbox_w_px', 'bbox_h_px', 'alan_px2', 'alan_um2', 'delik_sayisi']

// ── Kalıcı seçimler (makineye özel) ─────────────────────────────────────────
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/anotasyon-disa')
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeS = { String k, String d -> (String) __wpCall('str', [String.class, String.class] as Class[], [k, d] as Object[], d) }
def exportFolder = atolyeS('atolye.exportFolder', 'exports')

// ── Adlandırma sözleşmesi — yardimci-anotasyon-ice-aktar.groovy ile BİREBİR aynı tutulur ──
def safeFileName = { String s ->
    def t = (s ?: '').trim().replaceAll(/[\\\/:*?"<>|\p{Cntrl}]/, '_')
    return t.isEmpty() ? 'goruntu' : t
}
def stripExt = { String s -> (s == null) ? null : s.replaceAll(/\.[^.\\\/]+$/, '') }
def uriFileNameOf = { uris ->
    try {
        for (u in (uris ?: [])) { if (u != null && 'file'.equals(u.getScheme())) return new File(u).getName() }
    } catch (Throwable ignore) {}
    return null
}

// ── Küçük yardımcılar ───────────────────────────────────────────────────────
def fmt2 = { double v -> String.format(java.util.Locale.US, '%.2f', v) }
def round2 = { double v -> Math.round(v * 100.0d) / 100.0d }
def xmlEsc = { String s ->
    (s ?: '').replaceAll(/[\x00-\x08\x0B\x0C\x0E-\x1F]/, '')
             .replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
}
def tsvCell = { String s -> (s ?: '').replace('\t', ' ').replace('\r', ' ').replace('\n', ' ') }
def csvCell = { String s, String sep ->
    def v = (s ?: '')
    if (sep == '\t') return tsvCell(v)
    return (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')) ? ('"' + v.replace('"', '""') + '"') : v
}
def hexColor = { int rgb -> String.format(java.util.Locale.US, '#%02X%02X%02X', ColorTools.red(rgb), ColorTools.green(rgb), ColorTools.blue(rgb)) }
def uniqueName = { Set used, String base, String ext ->
    String n = base; int i = 2
    while (used.contains((n + ext).toLowerCase(java.util.Locale.ROOT))) { n = base + '__' + i; i++ }
    used.add((n + ext).toLowerCase(java.util.Locale.ROOT))
    return n + ext
}
def stamp = { -> LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyy-MM-dd_HHmmss')) }
def defaultBaseDir = { project ->
    if (project != null && project.getPath() != null) return new File(project.getPath().getParent().toFile(), exportFolder)
    return new File(new File(System.getProperty('user.home'), 'QuPath'), exportFolder)
}

// ── Nesne ve geometri yardımcıları ──────────────────────────────────────────
def classNameOf = { o -> def pc = o.getPathClass(); (pc == null || pc == PathClass.NULL_CLASS) ? null : pc.toString() }
def rgbOf = { o ->
    Integer c = null
    try { c = o.getColor() } catch (Throwable ignore) {}
    if (c == null) { try { c = o.getPathClass()?.getColor() } catch (Throwable ignore) {} }
    return (c == null) ? DEFAULT_RGB : (int) c
}
def classRgb = { String cls, int fallback ->
    Integer c = null
    try { c = PathClass.fromString(cls).getColor() } catch (Throwable ignore) {}
    return (c == null) ? fallback : (int) c
}
// 'area' | 'points' | 'line' (2 nokta) | 'polyline' | 'other'
def roiKind = { roi ->
    if (roi.isPoint()) return 'points'
    if (roi.isArea()) return 'area'
    if (roi.isLine()) return (roi.getNumPoints() == 2) ? 'line' : 'polyline'
    return 'other'
}
def objectType = { o -> o.isCell() ? 'cell' : (o.isTile() ? 'tile' : (o.isDetection() ? 'detection' : (o.isAnnotation() ? 'annotation' : 'other'))) }
// Halka koordinatları — kapanış (yinelenen son) noktası OLMADAN
def ringPts = { ring ->
    def cs = ring.getCoordinates(); int n = cs.length
    if (n > 1 && cs[0].equals2D(cs[n - 1])) n--
    def out = new ArrayList(n)
    for (int i = 0; i < n; i++) out.add([cs[i].x, cs[i].y] as double[])
    return out
}
// Alan ROI'si → [[shell: [[x,y]…], holes: [[[x,y]…]…]], …] (çok parçalı → birden çok parça)
def polygonParts = { roi ->
    def parts = []
    def g = roi.getGeometry()
    for (int i = 0; i < g.getNumGeometries(); i++) {
        def p = g.getGeometryN(i)
        if (p instanceof Polygon && !p.isEmpty()) {
            def holes = []
            for (int h = 0; h < p.getNumInteriorRing(); h++) holes.add(ringPts(p.getInteriorRingN(h)))
            parts.add([shell: ringPts(p.getExteriorRing()), holes: holes])
        }
    }
    return parts
}
def holeCount = { roi -> roi.isArea() ? ((polygonParts(roi).sum { it.holes.size() }) ?: 0) : 0 }
// Sınıf süzgeci: nesnenin sınıf adı girdiye eşitse YA DA girdinin türetilmiş sınıfıysa ("Tümör: Pozitif" ∈ "Tümör")
def classMatches = { String cn, Set classFilter ->
    if (cn == null) return classFilter.contains(UNCLASSIFIED)
    return classFilter.any { String c -> c != UNCLASSIFIED && (cn == c || cn.startsWith(c + ': ')) }
}
def selectObjects = { hierarchy, boolean withDetections, Set classFilter ->
    def objs = new ArrayList(hierarchy.getAnnotationObjects())
    if (withDetections) objs.addAll(hierarchy.getDetectionObjects())
    return objs.findAll { o ->
        o.hasROI() && !o.getROI().isEmpty() &&
            (classFilter == null || classFilter.isEmpty() || classMatches(classNameOf(o), classFilter))
    }
}

// ── ImageScope XML yazıcısı ─────────────────────────────────────────────────
// <Annotations> → sınıf başına <Annotation> katmanı → <Region>: Type 0 = çokgen (delik → NegativeROA="1"),
// Type 4 = cetvel (2 noktalı çizgi). Nokta ve çoklu çizgi taşınamaz → sayılır.
def writeImageScope = { File f, List objs, double umpp ->
    def st = [regions: 0, holes: 0, points: 0, polylines: 0]
    def layers = new LinkedHashMap()
    objs.each { o -> def k = classNameOf(o) ?: ''; if (!layers.containsKey(k)) layers.put(k, []); layers.get(k).add(o) }
    def sb = new StringBuilder()
    sb << '<?xml version="1.0" encoding="UTF-8"?>\n'
    sb << '<Annotations MicronsPerPixel="' << (umpp > 0.0d ? String.format(java.util.Locale.US, '%.6f', umpp) : '0') << '">\n'
    int annId = 0
    layers.each { String cls, List list ->
        annId++
        int rgb = cls ? classRgb(cls, rgbOf(list[0])) : rgbOf(list[0])
        int colorref = ColorTools.red(rgb) + (ColorTools.green(rgb) << 8) + (ColorTools.blue(rgb) << 16)
        sb << '  <Annotation Id="' << annId << '" Name="' << xmlEsc(cls) << '" ReadOnly="0" NameReadOnly="0" LineColorReadOnly="0" Incremental="0" Type="4" LineColor="' << colorref << '" Visible="1" Selected="0" MarkupImagePath="" MacroName="">\n'
        sb << '    <Attributes>\n      <Attribute Name="Description" Id="0" Value=""/>\n    </Attributes>\n'
        sb << '    <Regions>\n'
        int regId = 0
        def region = { int type, boolean negative, String text, List pts ->
            regId++
            sb << '      <Region Id="' << regId << '" Type="' << type << '" Zoom="1" Selected="0" ImageLocation="" ImageFocus="-1" Text="' << xmlEsc(text) << '" NegativeROA="' << (negative ? '1' : '0') << '" InputRegionId="0" Analyze="1" DisplayId="' << regId << '">\n'
            sb << '        <Attributes/>\n        <Vertices>\n'
            pts.each { p -> sb << '          <Vertex X="' << fmt2(p[0]) << '" Y="' << fmt2(p[1]) << '" Z="0"/>\n' }
            sb << '        </Vertices>\n      </Region>\n'
        }
        list.each { o ->
            def roi = o.getROI(); def kind = roiKind(roi); String text = o.getName() ?: ''
            if (kind == 'area') {
                polygonParts(roi).each { part ->
                    region(0, false, text, part.shell); st.regions++
                    part.holes.each { h -> region(0, true, '', h); st.holes++ }
                }
            } else if (kind == 'line') {
                def ps = roi.getAllPoints()
                region(4, false, text, [[ps[0].getX(), ps[0].getY()] as double[], [ps[1].getX(), ps[1].getY()] as double[]]); st.regions++
            } else if (kind == 'points') {
                st.points++
            } else {
                st.polylines++
            }
        }
        sb << '    </Regions>\n    <Plots/>\n  </Annotation>\n'
    }
    sb << '</Annotations>\n'
    f.getParentFile().mkdirs()
    f.setText(sb.toString(), 'UTF-8')
    return st
}

// ── ASAP XML yazıcısı ───────────────────────────────────────────────────────
// Sınıf → PartOfGroup; delik → "Exclusion" grubunda çokgen; nokta → Dot/PointSet; 2 noktalı çizgi → Measurement.
def writeASAP = { File f, List objs ->
    def st = [annotations: 0, holes: 0, polylines: 0]
    def groups = new LinkedHashMap()
    boolean exclusion = false
    def sb = new StringBuilder()
    sb << '<?xml version="1.0" encoding="UTF-8"?>\n<ASAP_Annotations>\n  <Annotations>\n'
    def writeAnn = { String name, String type, String group, int rgb, List pts ->
        sb << '    <Annotation Name="' << xmlEsc(name) << '" Type="' << type << '" PartOfGroup="' << xmlEsc(group) << '" Color="' << hexColor(rgb) << '">\n      <Coordinates>\n'
        pts.eachWithIndex { p, i -> sb << '        <Coordinate Order="' << i << '" X="' << fmt2(p[0]) << '" Y="' << fmt2(p[1]) << '"/>\n' }
        sb << '      </Coordinates>\n    </Annotation>\n'
    }
    def toXY = { ps -> ps.collect { [it.getX(), it.getY()] as double[] } }
    objs.each { o ->
        String cls = classNameOf(o)
        String group = (cls == null) ? 'None' : cls
        int rgb = rgbOf(o)
        if (cls != null && !groups.containsKey(cls)) groups.put(cls, classRgb(cls, rgb))
        def roi = o.getROI(); def kind = roiKind(roi); String nm = o.getName() ?: ''
        if (kind == 'area') {
            polygonParts(roi).each { part ->
                writeAnn(nm, 'Polygon', group, rgb, part.shell); st.annotations++
                part.holes.each { h -> writeAnn('', 'Polygon', 'Exclusion', EXCLUSION_RGB, h); st.holes++; exclusion = true }
            }
        } else if (kind == 'points') {
            def pts = toXY(roi.getAllPoints())
            writeAnn(nm, pts.size() == 1 ? 'Dot' : 'PointSet', group, rgb, pts); st.annotations++
        } else if (kind == 'line') {
            writeAnn(nm, 'Measurement', group, rgb, toXY(roi.getAllPoints())); st.annotations++
        } else {
            st.polylines++
        }
    }
    sb << '  </Annotations>\n  <AnnotationGroups>\n'
    groups.each { String cls, Integer rgb ->
        sb << '    <Group Name="' << xmlEsc(cls) << '" PartOfGroup="None" Color="' << hexColor((int) rgb) << '">\n      <Attributes/>\n    </Group>\n'
    }
    if (exclusion) sb << '    <Group Name="Exclusion" PartOfGroup="None" Color="' << hexColor(EXCLUSION_RGB) << '">\n      <Attributes/>\n    </Group>\n'
    sb << '  </AnnotationGroups>\n</ASAP_Annotations>\n'
    f.getParentFile().mkdirs()
    f.setText(sb.toString(), 'UTF-8')
    return st
}

// ── COCO JSON (tüm grup için tek dosya) ─────────────────────────────────────
def cocoNew = { -> [images: [], annotations: [], categories: new TreeSet(), nextId: 1] }
def cocoAdd = { Map coco, int imageId, String fileName, int w, int h, List objs ->
    coco.images.add([id: imageId, file_name: fileName, width: w, height: h])
    int added = 0, holes = 0, sp = 0, sl = 0
    objs.each { o ->
        def roi = o.getROI(); def kind = roiKind(roi)
        if (kind != 'area') { if (kind == 'points') sp++ else sl++; return }
        def parts = polygonParts(roi)
        if (parts.isEmpty()) { sl++; return }
        String cat = classNameOf(o) ?: UNCLASSIFIED
        coco.categories.add(cat)
        if (parts.any { !it.holes.isEmpty() }) holes++
        def seg = parts.collect { part -> def flat = []; part.shell.each { p -> flat.add(round2(p[0])); flat.add(round2(p[1])) }; flat }
        def geom = roi.getGeometry()
        def env = geom.getEnvelopeInternal()
        coco.annotations.add([id: coco.nextId, image_id: imageId, category: cat, segmentation: seg,
                              bbox: [round2(env.getMinX()), round2(env.getMinY()), round2(env.getWidth()), round2(env.getHeight())],
                              area: round2(geom.getArea()), iscrowd: 0])
        coco.nextId = coco.nextId + 1
        added++
    }
    return [added: added, holes: holes, points: sp, lines: sl]
}
def cocoWrite = { File f, Map coco ->
    def ids = [:]; int cid = 0
    def cats = coco.categories.collect { String name -> cid++; ids.put(name, cid); [id: cid, name: name, supercategory: 'qupath'] }
    def anns = coco.annotations.collect { a ->
        [id: a.id, image_id: a.image_id, category_id: ids.get(a.category), segmentation: a.segmentation, bbox: a.bbox, area: a.area, iscrowd: a.iscrowd]
    }
    def root = [info: [description: 'QuPath Atölye — toplu anotasyon dışa aktarma', date_created: LocalDateTime.now().toString()],
                images: coco.images, annotations: anns, categories: cats]
    f.getParentFile().mkdirs()
    f.setText(GsonTools.getInstance(true).toJson(root), 'UTF-8')
}

// ── Merkez tablosu (yalnız geometri) ────────────────────────────────────────
def csvRow = { String imageName, o, cal, String sep ->
    def roi = o.getROI()
    boolean um = (cal != null && cal.hasPixelSizeMicrons())
    double pw = um ? cal.getPixelWidthMicrons() : 0.0d
    double ph = um ? cal.getPixelHeightMicrons() : 0.0d
    def cells = [imageName, o.getID().toString(), objectType(o), classNameOf(o) ?: '', o.getName() ?: '',
                 roi.getRoiName().toLowerCase(java.util.Locale.ROOT), String.valueOf(roi.getZ()), String.valueOf(roi.getT()),
                 fmt2(roi.getCentroidX()), fmt2(roi.getCentroidY()),
                 um ? String.format(java.util.Locale.US, '%.4f', roi.getCentroidX() * pw) : '',
                 um ? String.format(java.util.Locale.US, '%.4f', roi.getCentroidY() * ph) : '',
                 fmt2(roi.getBoundsX()), fmt2(roi.getBoundsY()), fmt2(roi.getBoundsWidth()), fmt2(roi.getBoundsHeight()),
                 roi.isArea() ? fmt2(roi.getArea()) : '',
                 (roi.isArea() && um) ? String.format(java.util.Locale.US, '%.4f', roi.getScaledArea(pw, ph)) : '',
                 String.valueOf(holeCount(roi))]
    return cells.collect { csvCell(it, sep) }.join(sep)
}

// ── Toplu dışa aktarma çekirdeği (arka planda ya da headless çağrılır) ──────
def runExport = { Map cfg, List targets, Closure onProgress, java.util.concurrent.atomic.AtomicBoolean cancelled ->
    File outDir = (File) cfg.outDir
    outDir.mkdirs()
    Set fmts = (Set) cfg.formats
    String sep = (String) cfg.sep
    def manifest = new StringBuilder(MANIFEST_COLS.join('\t') + '\n')
    def used = [geojson: new HashSet(), imagescope: new HashSet(), asap: new HashSet()]
    def coco = fmts.contains('coco') ? cocoNew() : null
    String csvName = (sep == ',') ? 'merkezler.csv' : 'merkezler.tsv'
    def csvOut = null
    def res = [outDir: outDir, images: 0, ok: 0, failed: 0, objects: 0, fileSet: new LinkedHashSet(), cancelled: false,
               lines: [], cocoHoleObjects: 0,
               skipped: [imagescope: [points: 0, polylines: 0], asap: [points: 0, polylines: 0], coco: [points: 0, polylines: 0]]]
    int idx = 0
    try {
        if (fmts.contains('csv')) {
            csvOut = new File(outDir, csvName).newWriter('UTF-8')
            csvOut.write(CSV_COLS.collect { csvCell(it, sep) }.join(sep) + '\n')
        }
        for (t in targets) {
            idx++
            if (cancelled.get()) { res.cancelled = true; res.lines.add('⏹ İptal edildi — kalan görüntüler işlenmedi.'); break }
            res.images++
            onProgress(idx, targets.size(), (String) t.name)
            def data = t.live
            boolean opened = false
            String uriFile = null
            String gid = ''
            try { if (t.entry != null) gid = t.entry.getID() ?: '' } catch (Throwable ignore) {}
            try { if (t.entry != null) uriFile = uriFileNameOf(t.entry.getURIs()) } catch (Throwable ignore) {}
            def mrow = { String bicim, String file, int nObj, int sp, int sl, int hw, String durum, String note ->
                manifest << [tsvCell((String) t.name), tsvCell(gid), tsvCell(uriFile ?: ''), bicim, tsvCell(file),
                             nObj, sp, sl, hw, durum, tsvCell(note)].join('\t') << '\n'
            }
            try {
                if (data == null) { data = t.entry.readImageData(); opened = true }
                def server = data.getServer()
                if (uriFile == null) uriFile = uriFileNameOf(server.getURIs())
                def objs = selectObjects(data.getHierarchy(), (boolean) cfg.detections, (Set) cfg.classes)
                res.objects += objs.size()
                String slideBase = safeFileName(stripExt(uriFile ?: (String) t.name))
                def cal = server.getPixelCalibration()
                double umpp = cal.hasPixelSizeMicrons() ? cal.getAveragedPixelSizeMicrons() : 0.0d
                boolean anyFail = false
                for (String fmt in FORMAT_KEYS) {
                    if (!fmts.contains(fmt)) continue
                    try {
                        if (fmt == 'geojson') {
                            def name = uniqueName(used.geojson, safeFileName((String) t.name), '.geojson')
                            def f = new File(new File(outDir, 'geojson'), name)
                            f.getParentFile().mkdirs()
                            def opts = [PathIO.GeoJsonExportOptions.FEATURE_COLLECTION]
                            if (!cfg.measurements) opts.add(PathIO.GeoJsonExportOptions.EXCLUDE_MEASUREMENTS)
                            PathIO.exportObjectsAsGeoJSON(f, objs, opts as PathIO.GeoJsonExportOptions[])
                            mrow('geojson', 'geojson/' + name, objs.size(), 0, 0, 0, 'tamam', '')
                            res.fileSet.add('geojson/' + name)
                        } else if (fmt == 'imagescope') {
                            def name = uniqueName(used.imagescope, slideBase, '.xml')
                            def st = writeImageScope(new File(new File(outDir, 'imagescope'), name), objs, umpp)
                            res.skipped.imagescope.points += st.points
                            res.skipped.imagescope.polylines += st.polylines
                            mrow('imagescope', 'imagescope/' + name, objs.size() - st.points - st.polylines, st.points, st.polylines, 0, 'tamam',
                                 (st.points + st.polylines) > 0 ? 'ImageScope nokta ve çoklu çizgi taşımaz' : '')
                            res.fileSet.add('imagescope/' + name)
                        } else if (fmt == 'asap') {
                            def name = uniqueName(used.asap, slideBase, '.xml')
                            def st = writeASAP(new File(new File(outDir, 'asap'), name), objs)
                            res.skipped.asap.polylines += st.polylines
                            mrow('asap', 'asap/' + name, objs.size() - st.polylines, 0, st.polylines, 0, 'tamam',
                                 st.polylines > 0 ? 'ASAP çoklu çizgi taşımaz' : '')
                            res.fileSet.add('asap/' + name)
                        } else if (fmt == 'coco') {
                            def st = cocoAdd(coco, idx, (String) t.name, server.getWidth(), server.getHeight(), objs)
                            res.cocoHoleObjects += st.holes
                            res.skipped.coco.points += st.points
                            res.skipped.coco.polylines += st.lines
                            mrow('coco', 'coco.json', st.added, st.points, st.lines, st.holes, 'tamam',
                                 st.holes > 0 ? 'COCO çokgeni delik taşımaz (alan delikler düşülerek yazıldı)' : '')
                        } else if (fmt == 'csv') {
                            objs.each { o -> csvOut.write(csvRow((String) t.name, o, cal, sep) + '\n') }
                            mrow('csv', csvName, objs.size(), 0, 0, 0, 'tamam', '')
                        }
                    } catch (Throwable e) {
                        anyFail = true
                        String msg = e.getMessage() ?: e.getClass().getSimpleName()
                        mrow(fmt, '', 0, 0, 0, 0, 'hata', msg)
                        res.lines.add('✗ ' + t.name + ' — ' + fmt + ': ' + msg)
                    }
                }
                if (anyFail) {
                    res.failed++
                } else {
                    res.ok++
                    res.lines.add('✓ ' + t.name + ' — ' + objs.size() + ' nesne')
                }
            } catch (Throwable e) {
                res.failed++
                String msg = e.getMessage() ?: e.getClass().getSimpleName()
                FORMAT_KEYS.findAll { fmts.contains(it) }.each { String fmt -> mrow(fmt, '', 0, 0, 0, 0, 'hata', msg) }
                res.lines.add('✗ ' + t.name + ' — okunamadı: ' + msg)
            } finally {
                if (opened && data != null) { try { data.getServer().close() } catch (Throwable ignore) {} }
            }
        }
    } finally {
        if (csvOut != null) { try { csvOut.close() } catch (Throwable ignore) {} }
        if (coco != null) {
            try {
                cocoWrite(new File(outDir, 'coco.json'), coco); res.fileSet.add('coco.json')
            } catch (Throwable e) {
                res.failed++
                res.lines.add('✗ coco.json yazılamadı: ' + (e.getMessage() ?: e.getClass().getSimpleName()))
            }
        }
        if (fmts.contains('csv')) res.fileSet.add(csvName)
        try {
            new File(outDir, '_manifest.tsv').setText(manifest.toString(), 'UTF-8')
        } catch (Throwable e) {
            res.failed++
            res.lines.add('✗ _manifest.tsv yazılamadı: ' + (e.getMessage() ?: e.getClass().getSimpleName()))
        }
    }
    return res
}

def summaryText = { Map res ->
    def sb = new StringBuilder()
    sb << 'TOPLU ANOTASYON DIŞA AKTARMA — ÖZET' << (res.cancelled ? ' (İPTAL EDİLDİ)' : '') << '\n'
    sb << '════════════════════════════════════\n\n'
    sb << String.format(java.util.Locale.US, 'Görüntü: %,d   başarılı: %,d   hatalı: %,d   nesne: %,d%n',
                        (int) res.images, (int) res.ok, (int) res.failed, (int) res.objects)
    sb << 'Klasör : ' << ((File) res.outDir).getAbsolutePath() << '\n'
    sb << 'Dosya  : ' << res.fileSet.size() << ' (+ _manifest.tsv)\n'
    res.skipped.each { String fk, Map sk ->
        if (sk.points > 0 || sk.polylines > 0)
            sb << String.format(java.util.Locale.US, '%s — biçimin taşıyamadığı: %,d nokta, %,d çoklu çizgi (görüntü başına sayım: _manifest.tsv)%n',
                                (String) FORMAT_LABELS.get(fk), (int) sk.points, (int) sk.polylines)
    }
    if (res.cocoHoleObjects > 0)
        sb << String.format(java.util.Locale.US, 'COCO: %,d delikli nesnenin deliği çokgende yok (alan doğru yazıldı)%n', (int) res.cocoHoleObjects)
    sb << '\n'
    res.lines.each { sb << it << '\n' }
    sb << '\nKoordinatlar taban (level-0) piksel uzayındadır; y ekseni aşağı doğrudur.\n'
    sb << DISCLAIMER
    return sb.toString()
}

// ── Komut satırı argümanları (--args "anahtar=değer") ───────────────────────
// Anahtar/seçenek normalleştirme: küçük harf (Locale.ROOT) + Türkçe harfleri ASCII'ye katla (biçim→bicim, çıkış→cikis …)
def foldTr = { String s ->
    (s ?: '').trim().toLowerCase(java.util.Locale.ROOT)
        .replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i').replace('ö', 'o').replace('ş', 's').replace('ü', 'u')
}
def scriptArgs = { ->
    def m = [:]
    def raw = []
    try { if (this.binding.hasVariable('args')) raw = (this.binding.getVariable('args') ?: []) as List } catch (Throwable ignore) {}
    raw.each { s ->
        String str = String.valueOf(s); int i = str.indexOf('=')
        if (i > 0) m.put(foldTr(str.substring(0, i)), str.substring(i + 1).trim())
    }
    return m
}
def yes = { String v, boolean dflt -> (v == null || v.isEmpty()) ? dflt : (v.toLowerCase(java.util.Locale.ROOT) in ['evet', 'e', 'true', '1', 'yes']) }

// ── Headless (arayüzsüz) giriş noktası ──────────────────────────────────────
if (isHeadless) {
    def a = scriptArgs()
    def VALID_KEYS = ['proje', 'bicim', 'cikis', 'tespit', 'olcum', 'siniflar', 'ayrac']
    def badKeys = a.keySet().findAll { !VALID_KEYS.contains(it) }
    if (!badKeys.isEmpty())
        throw new IllegalArgumentException('Bilinmeyen argüman: ' + badKeys.join(', ') + '. Geçerli anahtarlar: ' + VALID_KEYS.join(', '))
    if (QP.getProject() != null)
        throw new IllegalArgumentException("Bu betik projeyi kendisi açar: QuPath'i -p OLMADAN, --args \"proje=<proje.qpproj>\" ile çalıştırın.")
    def project = a.proje ? ProjectIO.loadProject(new File((String) a.proje), BufferedImage.class) : QP.getProject()
    def targets = []
    if (a.proje) {
        project.getImageList().each { e -> targets.add([name: e.getImageName(), entry: e, live: null]) }
    } else {
        def d = QP.getCurrentImageData()
        if (d != null) {
            def e = QP.getProjectEntry()
            targets.add([name: (e != null ? e.getImageName() : (d.getServer().getMetadata().getName() ?: 'goruntu')), entry: e, live: d])
        }
    }
    if (targets.isEmpty())
        throw new IllegalArgumentException('Dışa aktarılacak görüntü yok: --args "proje=<proje.qpproj>" verin ya da -i ile bir görüntü açın.')
    def fmtTokens = ((String) (a.bicim ?: 'geojson')).split(',').collect { foldTr(it) }.findAll { it }
    def badFmts = fmtTokens.findAll { !FORMAT_KEYS.contains(it) }
    if (!badFmts.isEmpty())
        throw new IllegalArgumentException('Bilinmeyen biçim: ' + badFmts.join(', ') + '. Seçenekler: ' + FORMAT_KEYS.join(','))
    def fmts = new LinkedHashSet(fmtTokens)
    if (fmts.isEmpty()) throw new IllegalArgumentException('Geçerli biçim yok. Seçenekler: ' + FORMAT_KEYS.join(','))
    String sepKey = a.ayrac ? foldTr((String) a.ayrac) : 'tab'
    String sepVal
    if (sepKey in ['virgul', ',']) sepVal = ','
    else if (sepKey in ['tab', 'sekme']) sepVal = '\t'
    else throw new IllegalArgumentException('Geçersiz ayraç: ' + a.ayrac + '. Seçenekler: virgul, tab (sekme)')
    def classes = a.siniflar ? new LinkedHashSet(((String) a.siniflar).split(';').collect { it.trim() }.findAll { it }) : null
    File outDir = a.cikis ? new File((String) a.cikis) : new File(defaultBaseDir(project), 'anotasyon_' + stamp())
    def cfg = [outDir: outDir, formats: fmts, detections: yes((String) a.tespit, false), measurements: yes((String) a.olcum, true),
               classes: classes, sep: sepVal]
    def res = runExport(cfg, targets, { int i, int n, String nm -> println "[${i}/${n}] ${nm}" }, new java.util.concurrent.atomic.AtomicBoolean(false))
    println summaryText(res)
    if (res.failed > 0) throw new RuntimeException(res.failed + ' görüntü dışa aktarılamadı (ayrıntı: _manifest.tsv)')
    return
}

// ── Arayüz durumu ───────────────────────────────────────────────────────────
// CONFIG | RUNNING | RESULT | ERROR
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('CONFIG')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def resultTitleRef = new java.util.concurrent.atomic.AtomicReference('Tamamlandı ✅')
def lastProjectPath = new java.util.concurrent.atomic.AtomicReference(null)   // proje değişince işaretli görüntüleri sıfırla
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def baseDirRef    = new java.util.concurrent.atomic.AtomicReference(null)   // File; null → proje/exports
def scopeRef      = new java.util.concurrent.atomic.AtomicReference(prefs.get('scope', 'project'))
def progressLabelRef = new java.util.concurrent.atomic.AtomicReference(null)
def progressBarRef   = new java.util.concurrent.atomic.AtomicReference(null)
def checkedImages = Collections.synchronizedSet(new LinkedHashSet())       // "Seçili" kapsamı: giriş kimlikleri
def render  // ileri bildirim

def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text)
    b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    return b
}
def copyToClipboard = { String txt ->
    def cb = javafx.scene.input.Clipboard.getSystemClipboard()
    def c = new javafx.scene.input.ClipboardContent(); c.putString(txt ?: ''); cb.setContent(c)
}

// Bir proje girdisi bir görüntüleyicide açıksa onun CANLI ImageData'sı (kaydedilmemiş düzenlemeler dahil)
def liveDataFor = { project, entry ->
    if (gui == null || project == null || entry == null) return null
    try {
        for (v in gui.getAllViewers()) {
            def d = v.getImageData()
            if (d == null) continue
            def e = project.getEntry(d)
            if (e != null && e.getID() == entry.getID()) return d
        }
    } catch (Throwable ignore) {}
    return null
}
def buildTargets = { String scope ->
    def project = QP.getProject()
    def out = []
    if (scope == 'current') {
        def d = QP.getCurrentImageData()
        if (d == null) return out
        def e = (project != null) ? project.getEntry(d) : null
        out.add([name: (e != null ? e.getImageName() : (d.getServer().getMetadata().getName() ?: 'goruntu')), entry: e, live: d])
        return out
    }
    if (project == null) return out
    project.getImageList().each { e ->
        if (scope == 'selected' && !checkedImages.contains(e.getID())) return
        out.add([name: e.getImageName(), entry: e, live: liveDataFor(project, e)])
    }
    return out
}

def startExport = {
    String scope = scopeRef.get()
    if (scope == 'selected' && QP.getProject()?.getPath()?.toString() != lastProjectPath.get()) {
        checkedImages.clear()
        errorTextRef.set('Proje değişti; görüntüleri yeniden seçin.')
        step.set('ERROR'); render(); return
    }
    def targets = buildTargets(scope)
    if (targets.isEmpty()) {
        errorTextRef.set(scope == 'selected' ? 'Hiç görüntü işaretlenmedi.' : 'Dışa aktarılacak görüntü yok (proje ya da açık görüntü yok).')
        step.set('ERROR'); render(); return
    }
    def fmts = new LinkedHashSet(((String) prefs.get('formats', 'geojson')).split(',').findAll { FORMAT_KEYS.contains(it) })
    if (fmts.isEmpty()) { errorTextRef.set('En az bir biçim seçin.'); step.set('ERROR'); render(); return }
    Set classes = null
    if (prefs.getBoolean('classFilter', false)) {
        classes = new LinkedHashSet(((String) prefs.get('classes', '')).split(';').findAll { it })
        if (classes.isEmpty()) { errorTextRef.set('Sınıf filtresi açık ama hiçbir sınıf işaretli değil.'); step.set('ERROR'); render(); return }
    }
    boolean withDet = prefs.getBoolean('detections', false)
    if (withDet && targets.size() > 1) {
        boolean ok = Dialogs.showConfirmDialog('Tespitler de dışa aktarılsın mı?',
            'Tespit/hücre nesneleri çok sayıda olabilir; GeoJSON ve XML dosyaları büyür ve işlem uzar.\n\nDevam edilsin mi?')
        if (!ok) return
    }
    File base = baseDirRef.get() ?: defaultBaseDir(QP.getProject())
    def cfg = [outDir: new File(base, 'anotasyon_' + stamp()), formats: fmts, detections: withDet,
               measurements: prefs.getBoolean('measurements', true), classes: classes,
               sep: (prefs.get('sep', 'tab') == 'comma') ? ',' : '\t']
    prefs.put('scope', scope)
    try { prefs.flush() } catch (Throwable ignore) {}
    cancelledRef.set(false)
    step.set('RUNNING'); render()
    def worker = new Thread({
        try {
            def res = runExport(cfg, targets, { int i, int n, String nm ->
                javafx.application.Platform.runLater {
                    progressLabelRef.get()?.setText(i + ' / ' + n + ' — ' + nm)
                    progressBarRef.get()?.setProgress((i - 1) / (double) n)
                }
            }, cancelledRef)
            String txt = summaryText(res)
            String ttl = res.cancelled ? 'İptal edildi' : ((res.failed ?: 0) > 0 ? 'Tamamlandı (hatalarla) ⚠' : 'Tamamlandı ✅')
            javafx.application.Platform.runLater { resultTextRef.set(txt); resultTitleRef.set(ttl); step.set('RESULT'); render() }
        } catch (Throwable t) {
            javafx.application.Platform.runLater {
                errorTextRef.set('Dışa aktarma başarısız:\n' + (t.getMessage() ?: t.getClass().getSimpleName()))
                step.set('ERROR'); render()
            }
        }
    }, 'AtolyeAnotasyonDisa-Run')
    worker.setDaemon(true); worker.start()
}

// ── Render: her durum değişiminde sahneyi sıfırdan kurar ────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def project = QP.getProject()
    def imageData = QP.getCurrentImageData()
    String projPath = project?.getPath()?.toString()
    if (projPath != lastProjectPath.get()) { checkedImages.clear(); lastProjectPath.set(projPath) }

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 14px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10)
    center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)
    def actions = new ArrayList()

    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); lbl.setMaxWidth(Double.MAX_VALUE); center.getChildren().add(lbl) }
    def section = { String txt -> def l = new javafx.scene.control.Label(txt); l.setStyle('-fx-font-weight: bold; -fx-padding: 6 0 0 0;'); center.getChildren().add(l) }
    def addMonoArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: '')
        ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(ta)
    }
    def onChange = { Closure c -> ({ obs, o, n -> c(n) } as javafx.beans.value.ChangeListener) }

    if (cur == 'CONFIG') {
        title.setText('Toplu anotasyon dışa aktarma')

        section('1 · Kapsam')
        String sc = scopeRef.get()
        if ((sc == 'current' && imageData == null) || (sc != 'current' && project == null)) sc = (project != null) ? 'project' : 'current'
        scopeRef.set(sc)
        def tg = new javafx.scene.control.ToggleGroup()
        def mkRadio = { String text, String key, boolean disabled ->
            def rb = new javafx.scene.control.RadioButton(text); rb.setToggleGroup(tg); rb.setUserData(key)
            rb.setDisable(disabled); rb.setSelected(key == sc); return rb
        }
        def scopeRow = new javafx.scene.layout.HBox(16,
            mkRadio('Bu görüntü', 'current', imageData == null),
            mkRadio('Seçili proje görüntüleri', 'selected', project == null),
            mkRadio('Tüm proje', 'project', project == null))
        center.getChildren().add(scopeRow)
        tg.selectedToggleProperty().addListener(onChange { n -> if (n != null) { scopeRef.set((String) n.getUserData()); render() } })
        if (project != null && sc == 'selected') {
            def imgBox = new javafx.scene.layout.VBox(4)
            imgBox.setPadding(new javafx.geometry.Insets(4))
            project.getImageList().each { e ->
                def cb = new javafx.scene.control.CheckBox(e.getImageName())
                cb.setSelected(checkedImages.contains(e.getID()))
                cb.selectedProperty().addListener(onChange { n -> if (n) checkedImages.add(e.getID()) else checkedImages.remove(e.getID()) })
                imgBox.getChildren().add(cb)
            }
            def sp = new javafx.scene.control.ScrollPane(imgBox)
            sp.setPrefViewportHeight(150)
            sp.setFitToWidth(true)
            center.getChildren().add(sp)
        }

        section('2 · Nesneler')
        addGuidance('Anotasyonlar her zaman dahildir.')
        def det = new javafx.scene.control.CheckBox('Tespit/hücre nesnelerini de ekle (dosyalar büyür)')
        det.setSelected(prefs.getBoolean('detections', false))
        det.selectedProperty().addListener(onChange { n -> prefs.putBoolean('detections', n) })
        def meas = new javafx.scene.control.CheckBox('GeoJSON\'a ölçümleri ekle')
        meas.setSelected(prefs.getBoolean('measurements', true))
        meas.selectedProperty().addListener(onChange { n -> prefs.putBoolean('measurements', n) })
        center.getChildren().addAll(det, meas)
        def cf = new javafx.scene.control.CheckBox('Yalnız şu sınıflar (alt sınıflar dahil):')
        cf.setSelected(prefs.getBoolean('classFilter', false))
        def chosen = new LinkedHashSet(((String) prefs.get('classes', '')).split(';').findAll { it })
        def flow = new javafx.scene.layout.FlowPane(10, 6)
        // Birleşim (sıra korunur, yineleme yok): sınıf listesi + açık görüntülerdeki anotasyon/tespit sınıfları + kayıtlı seçimler
        def names = new LinkedHashSet()
        try { gui.getAvailablePathClasses().each { pc -> if (pc != null && pc != PathClass.NULL_CLASS) names.add(pc.toString()) } } catch (Throwable ignore) {}
        try {
            gui.getAllViewers().each { vw ->
                def h = vw.getImageData()?.getHierarchy()
                if (h == null) return
                (new ArrayList(h.getAnnotationObjects()) + new ArrayList(h.getDetectionObjects())).each { o ->
                    def cn = classNameOf(o); if (cn != null) names.add(cn)
                }
            }
        } catch (Throwable ignore) {}
        chosen.each { names.add(it) }
        names.remove(UNCLASSIFIED)
        names.add(UNCLASSIFIED)
        names.each { String n0 ->
            def cb = new javafx.scene.control.CheckBox(n0)
            cb.setSelected(chosen.contains(n0))
            cb.selectedProperty().addListener(onChange { n -> if (n) chosen.add(n0) else chosen.remove(n0); prefs.put('classes', chosen.join(';')) })
            flow.getChildren().add(cb)
        }
        flow.setDisable(!cf.isSelected())
        cf.selectedProperty().addListener(onChange { n -> prefs.putBoolean('classFilter', n); flow.setDisable(!n) })
        center.getChildren().addAll(cf, flow)

        section('3 · Biçimler')
        def chosenFmts = new LinkedHashSet(((String) prefs.get('formats', 'geojson')).split(',').findAll { FORMAT_KEYS.contains(it) })
        def fmtFlow = new javafx.scene.layout.FlowPane(14, 6)
        FORMAT_KEYS.each { String k ->
            def cb = new javafx.scene.control.CheckBox((String) FORMAT_LABELS[k])
            cb.setSelected(chosenFmts.contains(k))
            cb.selectedProperty().addListener(onChange { n ->
                if (n) chosenFmts.add(k) else chosenFmts.remove(k)
                prefs.put('formats', FORMAT_KEYS.findAll { chosenFmts.contains(it) }.join(','))
            })
            fmtFlow.getChildren().add(cb)
        }
        def sepBox = new javafx.scene.control.ChoiceBox()
        sepBox.getItems().addAll('Sekme (.tsv)', 'Virgül (.csv)')
        sepBox.setValue(prefs.get('sep', 'tab') == 'comma' ? 'Virgül (.csv)' : 'Sekme (.tsv)')
        sepBox.valueProperty().addListener(onChange { n -> prefs.put('sep', n == 'Virgül (.csv)' ? 'comma' : 'tab') })
        def sepRow = new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('Merkez tablosu ayracı:'), sepBox)
        sepRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        center.getChildren().addAll(fmtFlow, sepRow)

        section('4 · Kayıt yeri')
        File base = baseDirRef.get() ?: defaultBaseDir(project)
        def outLbl = new javafx.scene.control.Label(new File(base, 'anotasyon_<tarih>').getAbsolutePath())
        outLbl.setWrapText(true); outLbl.setMaxWidth(Double.MAX_VALUE)
        def browse = navButton('Seç…', {
            def d = FileChoosers.promptForDirectory(stage, 'Kayıt klasörünü seçin', base)
            if (d != null) { baseDirRef.set(d); render() }
        })
        def outRow = new javafx.scene.layout.HBox(8, outLbl, browse)
        outRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        center.getChildren().add(outRow)
        addGuidance('Açık görüntünün kaydedilmemiş değişiklikleri de dışa aktarılır. Koordinatlar taban (level-0) piksel ' +
            'uzayındadır. ImageScope nokta taşımaz; ImageScope ve ASAP çoklu çizgi taşımaz; COCO delik taşımaz — ' +
            'atlananlar _manifest.tsv\'de görüntü başına sayılır.')
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Dışa aktar ▶', { startExport() }, 'Seçili kapsamı seçili biçimlere yazar'))
    } else if (cur == 'RUNNING') {
        title.setText('Dışa aktarılıyor…')
        def pl = new javafx.scene.control.Label('Başlıyor…'); pl.setWrapText(true); pl.setMaxWidth(Double.MAX_VALUE)
        def pb = new javafx.scene.control.ProgressBar(0.0d); pb.setMaxWidth(Double.MAX_VALUE)
        progressLabelRef.set(pl); progressBarRef.set(pb)
        center.getChildren().addAll(pl, pb)
        addGuidance('İptal, sıradaki görüntüden önce durur; yazılmakta olan görüntü tamamlanır.')
        actions.add(navButton('İptal et', { cancelledRef.set(true) }))
    } else if (cur == 'RESULT') {
        title.setText((String) resultTitleRef.get())
        addMonoArea(resultTextRef.get())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('↻ Yeniden', { step.set('CONFIG'); render() }))
    } else { // ERROR
        title.setText('Hata')
        addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Geri', { step.set('CONFIG'); render() }))
        actions.add(navButton('Kapat', { stage.close() }))
    }

    // Alt çubuk: "Üstte tut" (sol) + disclaimer + eylem düğmeleri (sağ)
    def topChk = new javafx.scene.control.CheckBox('Üstte tut')
    topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n -> alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
    def spacer = new javafx.scene.layout.Region()
    javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8)
    bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk)
    bar.getChildren().add(spacer)
    bar.getChildren().addAll(actions)
    def disclaimer = new javafx.scene.control.Label('Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar; klinik karar üretmez.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))

    def scroll = new javafx.scene.control.ScrollPane(center)
    scroll.setFitToWidth(true)
    scroll.setFitToHeight(cur != 'CONFIG')
    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(scroll)
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 760, 680))
}

// ── Açılış ───────────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Toplu anotasyon dışa aktarma')
        stage.setAlwaysOnTop(alwaysTop.get())
        stage.setOnCloseRequest({ cancelledRef.set(true) } as javafx.event.EventHandler)   // çalışırken kapatma = iptal
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println '✓ Toplu anotasyon dışa aktarma sihirbazı açıldı.'
