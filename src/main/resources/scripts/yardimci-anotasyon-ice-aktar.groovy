/**
 * Yardımcı - Toplu Anotasyon İçe Aktarma (GeoJSON · ImageScope XML · ASAP XML)
 * ----------------------------------------------------------------------------
 * Hedef QuPath sürümü: 0.6.0+ (atölye eklentisi ile paketlenir).
 *
 * NE YAPAR:
 *   Bir klasördeki anotasyon dosyalarını bu görüntüye, seçili proje görüntülerine ya da tüm projeye
 *   TEK ÇALIŞTIRMADA aktarır. Dosyaları görüntülere TAM eşler (önce _manifest.tsv, sonra dosya adı;
 *   kısmi ad tahmini YOK) ve hiçbir şey yazmadan önce bir eşleştirme önizlemesi gösterir.
 *     • GeoJSON (QuPath) — delik, çok parçalı, nokta, çizgi, ölçüm, sınıf, ad ve UUID korunur
 *     • Aperio ImageScope XML — NegativeROA bölgeleri delik olarak geri yüklenir
 *     • ASAP XML — "Exclusion" grubundaki çokgenler delik olarak geri yüklenir
 *   ÖNCEKİ ANOTASYONLAR VARSAYILAN OLARAK SİLİNMEZ: "İçe aktar"da sihirbaz ne yapılacağını SORAR —
 *     • Mevcut anotasyonlara dokunma — yalnız ekle (varsayılan)
 *     • Aynı dosyadan önceki içe aktarımı değiştir (yalnız AYNI dosyadan — aynı biçim, aynı konum —
 *       gelmiş nesneler silinir; elle çizilenler ve başka dosyalardan gelenler korunur)
 *     • Tüm anotasyonları sil ve değiştir (ek onayla; tespitler korunur)
 *   İçe aktarılan her nesne 'atolye_iceaktarim' üst verisiyle (biçim · dosya yolu · zaman) işaretlenir. Hedefte
 *   aynı UUID'li bir nesne zaten varsa yenisi hiçbir kipte eklenmez (GeoJSON yeniden çalıştırması çoğaltmaz).
 *
 * NE ÖLÇER (ve ne ÖLÇMEZ):
 *   • VERİ AKTARIM betiğidir: dosyadaki geometriyi ve sınıf adlarını QuPath nesnesine çevirir.
 *     Patoloji yorumu, tanı, grade veya klinik karar üretmez.
 *   • Koordinatlar görüntünün taban (level-0) piksel uzayında olmalıdır; betik ölçekleme yapmaz.
 *     Nesneleri görüntü sınırını %1'den fazla aşan dosyalar işaretlenir (yanlış slayt ya da
 *     küçültülmüş koordinat olasılığı).
 *
 * KULLANIM:
 *   1. [Extensions → Atölye → Yardımcılar → İçe ve dışa aktarma / veri → Toplu anotasyon içe aktarma]
 *   2. Kapsamı ve kaynak klasörü seçin → "Önizle" → eşleşmeleri denetleyin → "İçe aktar".
 *   Arayüzsüz (headless / HPC):
 *     (proje= ZORUNLUDUR; -i ile tek görüntü desteklenmez.)
 *     QuPath script yardimci-anotasyon-ice-aktar.groovy --args "proje=<proje.qpproj>" --args "kaynak=<klasör>"
 *       [--args "bicim=otomatik|geojson|imagescope|asap"] [--args "mod=ekle|dosya|tumu"] [--args "kilitle=evet"]
 *     (varsayılan mod=ekle: hiçbir şey silinmez. Headless'ta belirsiz, dosyasız ve koordinatı sınır dışı
 *     satırlar atlanır ve raporlanır. Bilinmeyen anahtar/değer ya da eksik kaynak HATA verir; Türkçe
 *     yazımlar kabul edilir: biçim=bicim, kip değeri büyük/küçük harf duyarsız. QuPath'i -p ile DEĞİL,
 *     "proje=" argümanıyla çalıştırın.)
 *
 * ÇIKTI:
 *   • Nesneler her görüntünün .qpdata dosyasına kaydedilir (açık görüntüde canlı hiyerarşiye eklenir).
 *   • <proje>/exports/anotasyon_iceaktarma_<tarih>.tsv — görüntü başına durum ve sayımlar.
 *
 * YÖNTEM / KAYNAK REFERANSLARI:
 *   • QuPath PathIO.readObjects — QuPath 0.6.0+.
 *   • ImageScope Region tipleri (0 çokgen, 1 dikdörtgen, 2 elips, 3 ok, 4 cetvel) ve LineColor = COLORREF:
 *     yayımlanmış açık kaynak okuyucular; ASAP tip adları: computationalpathologygroup/ASAP Annotation.cpp.
 *   • Toplu proje döngüsü + rapor fikri: Balcı S. — sectra-qupath (MIT), doi:10.5281/zenodo.15542395.
 *     Kod özgündür.
 *
 * ⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.
 */

import qupath.fx.dialogs.Dialogs
import qupath.fx.dialogs.FileChoosers
import qupath.lib.scripting.QP
import qupath.lib.io.PathIO
import qupath.lib.projects.ProjectIO
import qupath.lib.common.ColorTools
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.roi.ROIs
import qupath.lib.roi.GeometryTools
import qupath.lib.regions.ImagePlane
import qupath.lib.geom.Point2
import org.locationtech.jts.geom.Coordinate
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def DISCLAIMER = '⚠️ Yalnızca araştırma/eğitim amaçlı veri aktarımı yapar.'

// ── Sabitler ────────────────────────────────────────────────────────────────
def TAG_KEY = 'atolye_iceaktarim'
def UNCLASSIFIED = '(sınıfsız)'
def FORMAT_LABELS = [otomatik: 'Otomatik (içeriğe göre)', geojson: 'GeoJSON', imagescope: 'Aperio ImageScope XML', asap: 'ASAP XML']
def MODE_LABELS = [ekle : 'Mevcut anotasyonlara dokunma — yalnız ekle (varsayılan)',
                   dosya: 'Aynı dosyadan önceki içe aktarımı değiştir (yalnız bu dosyadan gelenler; elle çizilenler ve başka dosyalar korunur)',
                   tumu : 'Tüm anotasyonları sil ve değiştir (tespitler korunur)']
def SUBDIRS = ['geojson', 'imagescope', 'asap']
double BOUNDS_TOL = 0.01d
def plane = ImagePlane.getDefaultPlane()
def GF = GeometryTools.getDefaultFactory()

// ── Kalıcı seçimler ─────────────────────────────────────────────────────────
def prefs = java.util.prefs.Preferences.userRoot().node('/qupath/atolye/anotasyon-ice')
def __wpClass = { -> try { Class.forName('io.github.sbalci.qupath.workshop.WorkshopPrefs') } catch (Throwable t) { null } }
def __wpCall  = { String m, Class[] sig, Object[] args, Object dflt ->
    def c = __wpClass(); if (c == null) return dflt
    try { c.getMethod(m, sig).invoke(null, args) } catch (Throwable t) { dflt }
}
def atolyeS = { String k, String d -> (String) __wpCall('str', [String.class, String.class] as Class[], [k, d] as Object[], d) }
def exportFolder = atolyeS('atolye.exportFolder', 'exports')

// ── Adlandırma sözleşmesi — yardimci-anotasyon-disa-aktar.groovy ile BİREBİR aynı tutulur ──
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
// Modül 9 (veri-aktarma.groovy) slug kuralı — birebir kopya; onun "<önek><slug>__annotations.geojson" dosyalarını eşlemek için
def toSlug = { String s ->
    if (s == null) return 'untitled'
    return s.replace('ı', 'i').replace('İ', 'I')
            .replace('ğ', 'g').replace('Ğ', 'G')
            .replace('ü', 'u').replace('Ü', 'U')
            .replace('ş', 's').replace('Ş', 'S')
            .replace('ö', 'o').replace('Ö', 'O')
            .replace('ç', 'c').replace('Ç', 'C')
            .replaceAll('[^a-zA-Z0-9_\\-]', '_')
            .replaceAll('_+', '_')
}
def nfc = { String s -> (s == null) ? null : java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC) }

// ── Küçük yardımcılar ───────────────────────────────────────────────────────
def tsvCell = { String s -> (s ?: '').replace('\t', ' ').replace('\r', ' ').replace('\n', ' ') }
def stamp = { -> LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyy-MM-dd_HHmmss')) }
def reportDirFor = { project ->
    if (project != null && project.getPath() != null) return new File(project.getPath().getParent().toFile(), exportFolder)
    return new File(new File(System.getProperty('user.home'), 'QuPath'), exportFolder)
}
def classNameOf = { o -> def pc = o.getPathClass(); (pc == null || pc == PathClass.NULL_CLASS) ? null : pc.toString() }
def num = { String s ->
    if (s == null || s.trim().isEmpty()) return Double.NaN
    try { return Double.parseDouble(s.trim().replace(',', '.')) } catch (Throwable t) { return Double.NaN }
}
def intOr = { String s, int d -> try { return Integer.parseInt((s ?: '').trim()) } catch (Throwable t) { return d } }
def hexToRgb = { String s ->
    if (s == null) return null
    def h = s.trim()
    if (h.startsWith('#')) h = h.substring(1)
    if (h.length() != 6) return null
    try { return (Integer) ColorTools.packRGB(Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16), Integer.parseInt(h.substring(4, 6), 16)) }
    catch (Throwable t) { return null }
}

// ── Biçim tanıma (uzantıya değil İÇERİĞE bakar) ─────────────────────────────
def detectFormat = { File f ->
    String head = ''
    def ins = new FileInputStream(f)
    try { byte[] b = new byte[4096]; int n = ins.read(b); if (n > 0) head = new String(b, 0, n, 'UTF-8') } finally { ins.close() }
    if (!head.isEmpty() && head.charAt(0) == (char) 0xFEFF) head = head.substring(1)
    String s = head.trim()
    if (s.startsWith('[')) return 'geojson'
    if (s.startsWith('{')) return (s.contains('"FeatureCollection"') || s.contains('"Feature"')) ? 'geojson' : null
    if (s.startsWith('<')) {
        def xif = javax.xml.stream.XMLInputFactory.newInstance()
        xif.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false)
        xif.setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        def fis = new FileInputStream(f)
        def r = null
        try {
            r = xif.createXMLStreamReader(fis)
            while (r.hasNext()) {
                if (r.next() == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName()
                    if (name == 'Annotations') return 'imagescope'
                    if (name == 'ASAP_Annotations') return 'asap'
                    return null
                }
            }
        } catch (Throwable t) {
            return null
        } finally {
            try { r?.close() } catch (Throwable ignore) {}
            fis.close()
        }
    }
    return null
}

// ── XML okuma (DOCTYPE kapalı → XXE / varlık genişletme saldırılarına kapalı) ──
def parseXml = { File f ->
    def dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    dbf.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
    dbf.setXIncludeAware(false)
    dbf.setExpandEntityReferences(false)
    return dbf.newDocumentBuilder().parse(f)
}
def childElems = { node, String name ->
    def out = []
    def nl = node.getChildNodes()
    for (int i = 0; i < nl.getLength(); i++) {
        def c = nl.item(i)
        if (c.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && c.getNodeName() == name) out.add(c)
    }
    return out
}

// ── Geometri kurucuları ─────────────────────────────────────────────────────
def polygonOf = { List pts ->
    if (pts.size() < 3) return null
    def cs = pts.collect { p -> new Coordinate((double) p[0], (double) p[1]) }
    if (!cs[0].equals2D(cs[cs.size() - 1])) cs.add(new Coordinate(cs[0]))
    if (cs.size() < 4) return null
    def g = GF.createPolygon(GF.createLinearRing(cs as Coordinate[]))
    return g.isValid() ? g : g.buffer(0)
}
def bboxOf = { List pts ->
    double x0 = pts.collect { it[0] }.min(), x1 = pts.collect { it[0] }.max()
    double y0 = pts.collect { it[1] }.min(), y1 = pts.collect { it[1] }.max()
    return [x0, y0, x1 - x0, y1 - y0] as double[]
}
// Delikleri sahiplerine geri yükle: her negatif, ONU KAPSAYAN ve ondan GERÇEKTEN BÜYÜK en küçük pozitife
// (aynı kapsamda) düşülür. (covers yansımalıdır: deliğe eşit bir ada çokgeni sahip olamaz — silinirdi.)
// Fark boşsa ya da hesaplanamazsa delik sahipsiz sayılır; sahip asla düşürülmez.
def subtractHoles = { List positives, List negatives, Closure sameScope ->
    int orphan = 0
    negatives.each { neg ->
        double negArea = neg.geom.getArea()
        def owners = positives.findAll { p -> p.geom != null && sameScope(p, neg) &&
            p.geom.getArea() > negArea * (1 + 1e-6) && p.geom.covers(neg.geom) }
        if (owners.isEmpty()) { orphan++; return }
        def owner = owners.min { it.geom.getArea() }
        def diff = null
        try { diff = owner.geom.difference(neg.geom) }
        catch (Throwable t) {
            try { diff = owner.geom.buffer(0).difference(neg.geom.buffer(0)) } catch (Throwable t2) { diff = null }
        }
        if (diff == null || diff.isEmpty()) { orphan++; return }
        owner.geom = diff
        owner.holed = true
    }
    return orphan
}
def makeAnnotation = { roi, String cls, String name, Integer rgb ->
    def o = PathObjects.createAnnotationObject(roi, cls ? PathClass.fromString(cls) : null)
    if (name) o.setName(name)
    if (cls == null && rgb != null) o.setColor(rgb)   // sınıfsız nesnede dosyadaki rengi koru; sınıf renkleri QuPath listesinden gelir
    return o
}
// Dönüş: [objects: [...], empty: boş geometri nedeniyle atlanan sayısı]
def finishXml = { List positives, List others ->
    def objs = []
    int empty = 0
    positives.each { p ->
        def roi = null
        try { roi = (p.roi != null && !p.holed) ? p.roi : GeometryTools.geometryToROI(p.geom, plane) } catch (Throwable ignore) { roi = null }
        if (roi != null && !roi.isEmpty()) objs.add(makeAnnotation(roi, p.cls, p.name, p.rgb)) else empty++
    }
    others.each { o -> objs.add(makeAnnotation(o.roi, o.cls, o.name, o.rgb)) }
    return [objects: objs, empty: empty]
}

// ── ImageScope XML okuyucusu ────────────────────────────────────────────────
def readImageScope = { File f ->
    def rootEl = parseXml(f).getDocumentElement()
    def positives = [], negatives = [], others = []
    int skipped = 0, layer = 0
    childElems(rootEl, 'Annotation').each { annEl ->
        layer++
        int thisLayer = layer
        String cls = annEl.getAttribute('Name')?.trim()
        if (!cls) cls = null
        Integer rgb = null
        String lc = annEl.getAttribute('LineColor')
        if (lc) { try { int c = Integer.parseInt(lc.trim()); rgb = ColorTools.packRGB(c & 0xFF, (c >> 8) & 0xFF, (c >> 16) & 0xFF) } catch (Throwable ignore) {} }
        childElems(annEl, 'Regions').each { regsEl ->
            childElems(regsEl, 'Region').each { regEl ->
                def pts = []
                childElems(regEl, 'Vertices').each { vs ->
                    childElems(vs, 'Vertex').each { v ->
                        double x = num(v.getAttribute('X')), y = num(v.getAttribute('Y'))
                        if (!Double.isNaN(x) && !Double.isNaN(y)) pts.add([x, y] as double[])
                    }
                }
                int type = intOr(regEl.getAttribute('Type'), 0)
                boolean negative = regEl.getAttribute('NegativeROA')?.trim() == '1'
                String text = regEl.getAttribute('Text')?.trim()
                if (!text) text = null
                if ((type == 3 || type == 4) && pts.size() >= 2) {
                    others.add([roi: ROIs.createLineROI(pts[0][0], pts[0][1], pts[-1][0], pts[-1][1], plane), cls: cls, name: text, rgb: rgb])
                    return
                }
                def roi = null
                def geom = null
                if (type == 1 && pts.size() >= 2) {
                    def b = bboxOf(pts); roi = ROIs.createRectangleROI(b[0], b[1], b[2], b[3], plane); geom = roi.getGeometry()
                } else if (type == 2 && pts.size() >= 2) {
                    def b = bboxOf(pts); roi = ROIs.createEllipseROI(b[0], b[1], b[2], b[3], plane); geom = roi.getGeometry()
                } else {
                    geom = polygonOf(pts)
                }
                if (geom == null || geom.isEmpty()) { skipped++; return }
                if (negative) negatives.add([geom: geom, layer: thisLayer])
                else positives.add([geom: geom, roi: roi, cls: cls, name: text, rgb: rgb, layer: thisLayer, holed: false])
            }
        }
    }
    int orphan = subtractHoles(positives, negatives) { p, n -> p.layer == n.layer }
    def fin = finishXml(positives, others)
    return [objects: fin.objects, skipped: skipped + fin.empty, orphanHoles: orphan, warnings: []]
}

// ── ASAP XML okuyucusu ──────────────────────────────────────────────────────
def readASAP = { File f ->
    def rootEl = parseXml(f).getDocumentElement()
    def positives = [], negatives = [], others = []
    int skipped = 0
    def warnings = []
    childElems(rootEl, 'Annotations').each { annsEl ->
        childElems(annsEl, 'Annotation').each { a ->
            String type = a.getAttribute('Type')?.trim()
            String group = a.getAttribute('PartOfGroup')?.trim()
            String cls = (!group || group == 'None') ? null : group
            String name = a.getAttribute('Name')?.trim()
            if (!name) name = null
            Integer rgb = hexToRgb(a.getAttribute('Color'))
            def raw = []
            childElems(a, 'Coordinates').each { cs ->
                childElems(cs, 'Coordinate').each { c -> raw.add([order: intOr(c.getAttribute('Order'), raw.size()), x: num(c.getAttribute('X')), y: num(c.getAttribute('Y'))]) }
            }
            def pts = raw.findAll { !Double.isNaN(it.x) && !Double.isNaN(it.y) }.sort { it.order }.collect { [it.x, it.y] as double[] }
            if (cls == 'Exclusion') {
                def g = polygonOf(pts)
                if (g == null) { skipped++; return }
                negatives.add([geom: g])
                return
            }
            switch (type) {
                case 'Polygon':
                case 'Spline':
                    def g = polygonOf(pts)
                    if (g == null) { skipped++; return }
                    positives.add([geom: g, roi: null, cls: cls, name: name, rgb: rgb, holed: false])
                    break
                case 'Rectangle':
                    if (pts.size() < 2) { skipped++; return }
                    def b = bboxOf(pts)
                    def roi = ROIs.createRectangleROI(b[0], b[1], b[2], b[3], plane)
                    positives.add([geom: roi.getGeometry(), roi: roi, cls: cls, name: name, rgb: rgb, holed: false])
                    break
                case 'Dot':
                case 'PointSet':
                    if (pts.isEmpty()) { skipped++; return }
                    others.add([roi: ROIs.createPointsROI(pts.collect { new Point2(it[0], it[1]) }, plane), cls: cls, name: name, rgb: rgb])
                    break
                case 'Measurement':
                    if (pts.size() < 2) { skipped++; return }
                    others.add([roi: ROIs.createLineROI(pts[0][0], pts[0][1], pts[-1][0], pts[-1][1], plane), cls: cls, name: name, rgb: rgb])
                    break
                default:
                    skipped++
            }
        }
    }
    int orphan = subtractHoles(positives, negatives) { p, n -> true }
    if (!negatives.isEmpty()) warnings.add('"Exclusion" grubu delik olarak yorumlandı')
    def fin = finishXml(positives, others)
    return [objects: fin.objects, skipped: skipped + fin.empty, orphanHoles: orphan, warnings: warnings]
}

def readObjectsFrom = { File f, String fmt ->
    if (fmt == 'geojson') return [objects: new ArrayList(PathIO.readObjects(f.toPath())), skipped: 0, orphanHoles: 0, warnings: []]
    if (fmt == 'imagescope') return readImageScope(f)
    if (fmt == 'asap') return readASAP(f)
    throw new IllegalArgumentException('Bilinmeyen biçim: ' + fmt)
}

// ── Kaynak klasör çözümü ────────────────────────────────────────────────────
// Seçilen klasörde geojson/ imagescope/ asap/ alt klasörleri varsa (dışa aktarma kökü):
// biçim zorlandıysa o alt klasör, "otomatik"te ilk bulunan (geojson → imagescope → asap) kullanılır.
def resolveSource = { File chosen, String fmt ->
    File dir = chosen
    if (chosen.isDirectory()) {
        def sub = (fmt == 'otomatik') ? SUBDIRS.find { new File(chosen, it).isDirectory() } : (new File(chosen, fmt).isDirectory() ? fmt : null)
        if (sub != null) dir = new File(chosen, sub)
    }
    def list = chosen.isFile() ? [chosen] : (dir.listFiles()?.findAll { it.isFile() } ?: [])
    def out = []
    list.sort { it.getName() }.each { f ->
        String low = f.getName().toLowerCase(java.util.Locale.ROOT)
        if (!(low.endsWith('.geojson') || low.endsWith('.json') || low.endsWith('.xml'))) return
        String det = null
        try { det = detectFormat(f) } catch (Throwable ignore) {}
        if (det == null) return
        if (fmt != 'otomatik' && det != fmt) return
        out.add([file: f.getCanonicalFile(), format: det])
    }
    return [dir: (chosen.isFile() ? chosen.getParentFile() : dir), files: out]
}

def readManifest = { File dir ->
    def f = new File(dir, '_manifest.tsv')
    if (!f.isFile() && dir.getParentFile() != null) f = new File(dir.getParentFile(), '_manifest.tsv')
    if (!f.isFile()) return null
    def lines = f.readLines('UTF-8')
    if (lines.isEmpty()) return null
    def hdr = lines[0].split('\t', -1) as List
    int iName = hdr.indexOf('goruntu_adi'), iId = hdr.indexOf('giris_id'), iFile = hdr.indexOf('cikti_dosyasi'), iStat = hdr.indexOf('durum')
    if (iName < 0 || iFile < 0) return null
    def rows = []
    lines.drop(1).each { String ln ->
        if (!ln.trim()) return
        def c = ln.split('\t', -1)
        if (c.length <= Math.max(iName, iFile)) return
        if (iStat >= 0 && c.length > iStat && c[iStat] != 'tamam') return
        String rel = c[iFile]
        if (!rel) return
        rows.add([name: nfc(c[iName]), id: (iId >= 0 && c.length > iId) ? c[iId] : '',
                  file: new File(f.getParentFile(), rel.replace('/', File.separator)).getCanonicalFile()])
    }
    return rows
}

// ── Eşleştirme ──────────────────────────────────────────────────────────────
def fileStem = { File f ->
    String n = f.getName()
    String low = n.toLowerCase(java.util.Locale.ROOT)
    for (String ext in ['.geojson', '.json', '.xml']) { if (low.endsWith(ext)) { n = n.substring(0, n.length() - ext.length()); break } }
    if (n.endsWith('__annotations')) n = n.substring(0, n.length() - '__annotations'.length())
    return nfc(n)
}
def imageKeys = { String imageName, String uriFile ->
    def base = [imageName, stripExt(imageName), uriFile, stripExt(uriFile)].findAll { it }.collect { nfc(it) }
    def plain = new LinkedHashSet(base)
    base.each { plain.add(safeFileName(it)) }
    return [plain: plain, slug: new LinkedHashSet(base.collect { toSlug(it) })]
}
def targetOf = { entry, liveData ->
    String uriFile = null
    try { uriFile = (entry != null) ? uriFileNameOf(entry.getURIs()) : uriFileNameOf(liveData?.getServer()?.getURIs()) } catch (Throwable ignore) {}
    String name = (entry != null) ? entry.getImageName() : (liveData?.getServer()?.getMetadata()?.getName() ?: 'goruntu')
    return [name: name, entry: entry, live: liveData, uriFile: uriFile]
}
def buildMatches = { List targets, List files, List manifestRows ->
    def byPath = new LinkedHashMap()
    files.each { byPath.put(it.file.getPath(), it) }
    def rows = targets.collect { t -> [target: t, candidates: [], file: null, format: null, how: '', status: 'dosya yok',
                                       include: false, summary: '', outOfBounds: false] }
    // 1) _manifest.tsv — görüntü adı (NFC) eşit; aynı ad birden çok satırdaysa giris_id ayırır
    if (manifestRows != null) {
        rows.each { r ->
            def hits = manifestRows.findAll { it.name == nfc((String) r.target.name) && byPath.containsKey(it.file.getPath()) }
            if (hits.size() > 1 && r.target.entry != null) {
                def byId = hits.findAll { it.id == r.target.entry.getID() }
                if (byId.size() == 1) hits = byId
            }
            hits.each { h -> r.candidates.add(byPath.get(h.file.getPath())) }
            if (!hits.isEmpty()) r.how = 'manifest'
        }
    }
    // 2) dosya adı — önce TAM anahtar, yoksa Modül 9 slug'ı; kısmi/önek eşleşmesi YOK
    rows.findAll { it.candidates.isEmpty() }.each { r ->
        def keys = imageKeys((String) r.target.name, (String) r.target.uriFile)
        def plain = files.findAll { keys.plain.contains(fileStem(it.file)) }
        if (!plain.isEmpty()) { r.candidates.addAll(plain); r.how = 'ad' }
        else {
            def sl = files.findAll { keys.slug.contains(toSlug(fileStem(it.file))) }
            if (!sl.isEmpty()) { r.candidates.addAll(sl); r.how = 'slug' }
        }
    }
    // 3) tekillik: bir dosya birden çok görüntüye düşüyorsa hepsi belirsiz
    def claims = [:]
    rows.each { r -> r.candidates.each { c -> String k = c.file.getPath(); claims.put(k, ((claims.get(k) ?: 0) as int) + 1) } }
    rows.each { r ->
        if (r.candidates.size() == 1 && claims.get(r.candidates[0].file.getPath()) == 1) {
            r.file = r.candidates[0].file; r.format = r.candidates[0].format; r.status = 'eşleşti'; r.include = true
        } else if (!r.candidates.isEmpty()) {
            r.status = 'belirsiz'
        }
    }
    def used = new HashSet(rows.findAll { it.file != null }.collect { it.file.getPath() })
    return [rows: rows, unmatched: files.findAll { !used.contains(it.file.getPath()) }]
}
// Tek görüntü + tek seçilmiş dosya: adı ne olursa olsun eşle (kullanıcı açıkça seçti)
def forceSingle = { Map m, Map fileEntry ->
    if (m.rows.size() != 1 || fileEntry == null) return
    def r = m.rows[0]
    r.candidates = [fileEntry]; r.file = fileEntry.file; r.format = fileEntry.format
    r.how = 'seçildi'; r.status = 'eşleşti'; r.include = true
    m.unmatched = []
}

// ── Uygulama ────────────────────────────────────────────────────────────────
def outOfBounds = { List objs, int w, int h ->
    if (objs.isEmpty()) return false
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE
    objs.each { o ->
        def r = o.getROI()
        if (r == null) return
        minX = Math.min(minX, r.getBoundsX()); minY = Math.min(minY, r.getBoundsY())
        maxX = Math.max(maxX, r.getBoundsX() + r.getBoundsWidth()); maxY = Math.max(maxY, r.getBoundsY() + r.getBoundsHeight())
    }
    double tx = w * BOUNDS_TOL, ty = h * BOUNDS_TOL
    return minX < -tx || minY < -ty || maxX > w + tx || maxY > h + ty
}
// Kaynak anahtarı = "<biçim> · <kanonik dosya yolu>". Yalnız dosya ADI yetmez: dışa aktarıcı imagescope/<slayt>.xml
// ile asap/<slayt>.xml'i AYNI adla yazar; ada göre eşleşen "değiştir" öbür kaynağın nesnelerini silerdi.
def sourceKeyOf = { String fmt, File f -> fmt + ' · ' + f.getPath() }
// Hedefte bu KAYNAKTAN daha önce içe aktarılmış nesneler (etiket "<kaynak anahtarı> · <zaman>")
def sameFileTagged = { h, String sourceKey ->
    def rootObj = h.getRootObject()
    String prefix = sourceKey + ' · '
    return h.getFlattenedObjectList(null).findAll { it != rootObj && it.hasMetadata() && (it.getMetadata().get(TAG_KEY) ?: '').startsWith(prefix) }
}
// Önizleme sorusu için: hedefteki anotasyon sayısı + bu kaynaktan daha önce içe aktarılmış nesne sayısı
def existingCounts = { data, String sourceKey ->
    def h = data.getHierarchy()
    return [annotations: h.getAnnotationObjects().size(), sameFile: (sourceKey ? sameFileTagged(h, sourceKey).size() : 0)]
}
// mode: 'ekle' (varsayılan — hiçbir şey silinmez) | 'dosya' (yalnız AYNI kaynaktan önceki içe aktarım) | 'tumu' (tüm anotasyonlar)
def applyObjects = { data, List objs, String mode, boolean lock, String sourceKey, String tagValue ->
    def h = data.getHierarchy()
    int removed = 0
    if (mode == 'dosya') {
        def old = sameFileTagged(h, sourceKey)
        removed = old.size()
        if (!old.isEmpty()) h.removeObjects(old, true)
    } else if (mode == 'tumu') {
        def anns = new ArrayList(h.getAnnotationObjects())
        removed = anns.size()
        if (!anns.isEmpty()) h.removeObjects(anns, true)
    }
    // Hiçbir kipte hedefte zaten var olan UUID yeniden eklenmez (yinelenen UUID → bozuk hiyerarşi)
    def existing = new HashSet(h.getFlattenedObjectList(null).collect { it.getID() })
    def toAdd = objs.findAll { existing.add(it.getID()) }   // add()=false → hedefte ya da aynı dosyada yinelenen UUID
    toAdd.each { o -> o.getMetadata().put(TAG_KEY, tagValue); if (lock) o.setLocked(true) }
    if (!toAdd.isEmpty()) h.addObjects(toAdd)
    h.resolveHierarchy()
    return [added: toAdd.size(), removed: removed, skipped: objs.size() - toAdd.size()]
}

def runImport = { List rows, Map cfg, Closure onProgress, java.util.concurrent.atomic.AtomicBoolean cancelled ->
    String ts = LocalDateTime.now().withNano(0).toString()
    def report = new StringBuilder(['goruntu_adi', 'dosya', 'bicim', 'eslesme', 'durum', 'eklenen', 'silinen', 'atlanan', 'not'].join('\t') + '\n')
    def addRow = { r, String durum, int added, int removed, int skipped, String note ->
        report << [tsvCell((String) r.target.name), tsvCell(r.file?.getName() ?: ''), r.format ?: '', r.how ?: '', durum,
                   added, removed, skipped, tsvCell(note)].join('\t') << '\n'
    }
    def res = [ok: 0, skipped: 0, failed: 0, added: 0, removed: 0, skippedObjects: 0, lines: [], cancelled: false, stopped: false,
               liveTouched: false, liveUnsaved: false, reportFile: null]
    def todo = rows.findAll { it.include && it.file != null }
    rows.findAll { !(it.include && it.file != null) }.each { r ->
        res.skipped++
        addRow(r, 'atlandı', 0, 0, 0, (String) r.status)
        if (r.status != 'dosya yok') res.lines.add('• ' + r.target.name + ' — atlandı (' + r.status + ')')
    }
    int idx = 0
    for (r in todo) {
        idx++
        if (cancelled.get()) {
            res.cancelled = true
            for (rest in todo.subList(idx - 1, todo.size())) {
                res.skipped++
                addRow(rest, 'atlandı', 0, 0, 0, 'iptal edildi — işlenmedi')
            }
            res.lines.add('⏹ İptal edildi — kalan görüntüler işlenmedi.')
            break
        }
        if (cfg.stillValid != null) {
            Boolean valid = null     // null = doğrulanamadı (zaman aşımı/hata) → yine fail-closed
            try { valid = cfg.stillValid() ? Boolean.TRUE : Boolean.FALSE } catch (Throwable ignore) { valid = null }
            if (valid != Boolean.TRUE) {
                String why = (valid == Boolean.FALSE) ? 'proje değişti — kalan görüntüler işlenmedi'
                                                      : 'proje doğrulanamadı (arayüz yanıt vermedi) — kalan görüntüler işlenmedi'
                res.stopped = true
                res.stopUnverified = (valid == null)
                for (rest in todo.subList(idx - 1, todo.size())) {
                    res.skipped++
                    addRow(rest, 'atlandı', 0, 0, 0, why)
                }
                res.lines.add('⏹ ' + why.capitalize() + '.')
                break
            }
        }
        def t = r.target
        // Açık görüntü İŞLEM ANINDA çözülür (önizlemeden sonra açılmış/kapatılmış olabilir); çözücü yoksa (headless) önizleme tutamacı
        def live = null
        boolean opened = false
        def data = null
        try {
            live = t.live
            onProgress(idx, todo.size(), (String) r.target.name)
            def read = readObjectsFrom((File) r.file, (String) r.format)
            def objs = read.objects
            String note = (read.orphanHoles > 0) ? (read.orphanHoles + ' sahipsiz delik atlandı') : ''
            if (read.warnings) note = (note ? note + '; ' : '') + read.warnings.join('; ')
            // Açık görüntü OKUMADAN HEMEN ÖNCE çözülür (yarış penceresini daraltır)
            live = (cfg.liveResolver != null && t.entry != null) ? cfg.liveResolver(t.entry) : t.live
            data = live
            if (data == null) { data = t.entry.readImageData(); opened = true }
            def server = data.getServer()
            // Elle seçilen dosyalar her zaman sınır denetiminden geçer (önizleme işaretlemesi atlanmış olabilir)
            if ((cfg.respectBounds || r.how == 'elle') && outOfBounds(objs, server.getWidth(), server.getHeight())) {
                String why = 'koordinatlar görüntü sınırının dışında' + ((r.how == 'elle') ? ' (elle seçilen dosya)' : '')
                res.skipped++
                addRow(r, 'atlandı', 0, 0, objs.size(), why)
                res.lines.add('• ' + t.name + ' — atlandı (' + why + ')')
                continue
            }
            String sourceKey = sourceKeyOf((String) r.format, (File) r.file)
            def ap = applyObjects(data, objs, (String) cfg.mode, (boolean) cfg.lock, sourceKey, sourceKey + ' · ' + ts)
            if (t.entry != null) t.entry.saveImageData(data) else res.liveUnsaved = true
            if (live != null) res.liveTouched = true
            res.ok++
            res.added += ap.added
            res.removed += ap.removed
            res.skippedObjects += ap.skipped + (int) read.skipped
            addRow(r, 'tamam', ap.added, ap.removed, ap.skipped + (int) read.skipped, note)
            res.lines.add(String.format(java.util.Locale.US, '✓ %s — +%,d, −%,d%s%s', t.name, ap.added, ap.removed,
                ap.skipped > 0 ? String.format(java.util.Locale.US, ', %,d aynı UUID atlandı', ap.skipped) : '',
                note ? (' [' + note + ']') : ''))
        } catch (Throwable e) {
            res.failed++
            String msg = e.getMessage() ?: e.getClass().getSimpleName()
            for (Throwable c = e; c != null; c = ((c.getCause() == null || c.getCause().is(c)) ? null : c.getCause())) {
                if (c instanceof java.util.concurrent.TimeoutException) { msg = 'açık görüntü sorgusu zaman aşımına uğradı (arayüz yanıt vermedi)'; break }
            }
            addRow(r, 'hata', 0, 0, 0, msg)
            res.lines.add('✗ ' + t.name + ' — ' + msg)
        } finally {
            if (opened && data != null) { try { data.getServer().close() } catch (Throwable ignore) {} }
        }
    }
    try {
        File rf = new File((File) cfg.reportDir, 'anotasyon_iceaktarma_' + stamp() + '.tsv')
        rf.getParentFile().mkdirs()
        rf.setText(report.toString(), 'UTF-8')
        res.reportFile = rf
    } catch (Throwable e) {
        res.lines.add('⚠ Rapor yazılamadı: ' + (e.getMessage() ?: e.getClass().getSimpleName()))
    }
    return res
}

def resultText = { Map res, String modeLabel ->
    def sb = new StringBuilder()
    sb << 'TOPLU ANOTASYON İÇE AKTARMA — ÖZET' << (res.cancelled ? ' (İPTAL EDİLDİ)' : '') << ((!res.cancelled && res.stopped) ? (res.stopUnverified ? ' (DURDURULDU — proje doğrulanamadı)' : ' (DURDURULDU — proje değişti)') : '') << '\n'
    sb << '═══════════════════════════════════\n\n'
    sb << String.format(java.util.Locale.US, 'İçe aktarılan görüntü: %,d   atlanan: %,d   hata: %,d%n', (int) res.ok, (int) res.skipped, (int) res.failed)
    sb << String.format(java.util.Locale.US, 'Nesne: +%,d eklendi, −%,d silindi, %,d atlandı%n', (int) res.added, (int) res.removed, (int) res.skippedObjects)
    sb << 'Kip    : ' << modeLabel << '\n'
    sb << 'Rapor  : ' << (res.reportFile != null ? ((File) res.reportFile).getAbsolutePath() : '-') << '\n\n'
    res.lines.each { sb << it << '\n' }
    if (res.liveUnsaved) sb << '\nAçık görüntü bir projede değil: değişiklikleri saklamak için File → Save kullanın.\n'
    sb << '\nYeni sınıflar sınıf listesinde görünmüyorsa: Annotations sekmesi → sınıf listesi menüsü → Populate from existing objects.\n'
    sb << DISCLAIMER
    return sb.toString()
}

// ── Komut satırı argümanları (--args "anahtar=değer") ───────────────────────
// Anahtar/seçenek normalleştirme: küçük harf (Locale.ROOT) + Türkçe harfleri ASCII'ye katla (biçim→bicim, …)
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
    def VALID_KEYS = ['proje', 'kaynak', 'bicim', 'mod', 'kilitle']
    def badKeys = a.keySet().findAll { !VALID_KEYS.contains(it) }
    if (!badKeys.isEmpty())
        throw new IllegalArgumentException('Bilinmeyen argüman: ' + badKeys.join(', ') + '. Geçerli anahtarlar: ' + VALID_KEYS.join(', '))
    if (!a.kaynak)
        throw new IllegalArgumentException('Kaynak gerekli: --args "kaynak=<klasör ya da dosya>"')
    if (QP.getProject() != null)
        throw new IllegalArgumentException("Bu betik projeyi kendisi açar: QuPath'i -p OLMADAN, --args \"proje=<proje.qpproj>\" ile çalıştırın.")
    if (!a.proje)
        throw new IllegalArgumentException('Proje gerekli: --args "proje=<proje.qpproj>" (içe aktarma .qpdata dosyalarına yazılır; -i ile tek görüntü desteklenmez).')
    def project = ProjectIO.loadProject(new File((String) a.proje), BufferedImage.class)
    def targets = []
    project.getImageList().each { e -> targets.add(targetOf(e, null)) }
    if (targets.isEmpty())
        throw new IllegalArgumentException('İçe aktarılacak görüntü yok: projede görüntü bulunmuyor.')
    String fmt = a.bicim ? foldTr((String) a.bicim) : 'otomatik'
    if (!FORMAT_LABELS.containsKey(fmt))
        throw new IllegalArgumentException('Geçersiz biçim: ' + a.bicim + ' (seçenekler: ' + FORMAT_LABELS.keySet().join(', ') + ')')
    String mode = a.mod ? foldTr((String) a.mod) : 'ekle'
    if (!MODE_LABELS.containsKey(mode))
        throw new IllegalArgumentException('Geçersiz kip: ' + a.mod + ' (seçenekler: ' + MODE_LABELS.keySet().join(', ') + ')')
    File chosen = new File((String) a.kaynak)
    def src = resolveSource(chosen, fmt)
    if (src.files.isEmpty()) throw new IllegalStateException('Kaynakta okunabilir GeoJSON / ImageScope / ASAP dosyası yok: ' + chosen.getAbsolutePath())
    def m = buildMatches(targets, src.files, readManifest((File) src.dir))
    if (chosen.isFile() && targets.size() == 1) forceSingle(m, src.files[0])
    def res = runImport(m.rows, [mode: mode, lock: yes((String) a.kilitle, false), respectBounds: true, reportDir: reportDirFor(project)],
                        { int i, int n, String nm -> println "[${i}/${n}] ${nm}" }, new java.util.concurrent.atomic.AtomicBoolean(false))
    println resultText(res, (String) MODE_LABELS[mode])
    if (res.failed > 0) throw new RuntimeException(res.failed + ' görüntü içe aktarılamadı (ayrıntı: ' + res.reportFile + ')')
    return
}

// ── Arayüz durumu ───────────────────────────────────────────────────────────
// SOURCE | LOADING | PREVIEW | RUNNING | RESULT | ERROR
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('SOURCE')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def scopeRef      = new java.util.concurrent.atomic.AtomicReference(prefs.get('scope', 'project'))
def sourceRef     = new java.util.concurrent.atomic.AtomicReference(null)   // File: klasör ya da dosya
def previewRef    = new java.util.concurrent.atomic.AtomicReference(null)   // [rows, unmatched, files, dir]
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def resultTitleRef = new java.util.concurrent.atomic.AtomicReference('Tamamlandı ✅')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
def progressLabelRef = new java.util.concurrent.atomic.AtomicReference(null)
def progressBarRef   = new java.util.concurrent.atomic.AtomicReference(null)
def checkedImages = Collections.synchronizedSet(new LinkedHashSet())       // "Seçili" kapsamı: giriş kimlikleri
def render  // ileri bildirim

// Proje değişimi koruması: önizleme/işaretler yalnız oluşturuldukları projeye aittir
def currentProjectPath = { -> QP.getProject()?.getPath()?.toString() }
def lastProjectPath = new java.util.concurrent.atomic.AtomicReference(currentProjectPath())
def projectChanged = { ->
    if (currentProjectPath() == lastProjectPath.get()) return false
    checkedImages.clear(); previewRef.set(null)
    return true
}

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
def showError = { String msg -> errorTextRef.set(msg); step.set('ERROR'); render() }
def setProgress = { int i, int n, String nm ->
    javafx.application.Platform.runLater {
        progressLabelRef.get()?.setText(i + ' / ' + n + ' — ' + nm)
        progressBarRef.get()?.setProgress((i - 1) / (double) n)
    }
}

// Bir proje girdisi bir görüntüleyicide açıksa onun CANLI ImageData'sı
// Yalnız FX iş parçacığında çağrılmalı; hata YUTMAZ (çalıştırma sırasında hata → o görüntü "hata", hiçbir şey yazılmaz)
def liveDataForStrict = { project, entry ->
    if (gui == null || project == null || entry == null) return null
    for (v in gui.getAllViewers()) {
        def d = v.getImageData()
        if (d == null) continue
        def e = project.getEntry(d)
        if (e != null && e.getID() == entry.getID()) return d
    }
    return null
}
// Önizleme/sayım amaçlı yumuşak sürüm (hata → null)
def liveDataFor = { project, entry ->
    try { return liveDataForStrict(project, entry) } catch (Throwable ignore) { return null }
}
def buildTargets = { String scope ->
    def project = QP.getProject()
    def out = []
    if (scope == 'current') {
        def d = QP.getCurrentImageData()
        if (d == null) return out
        out.add(targetOf((project != null) ? project.getEntry(d) : null, d))
        return out
    }
    if (project == null) return out
    project.getImageList().each { e ->
        if (scope == 'selected' && !checkedImages.contains(e.getID())) return
        out.add(targetOf(e, liveDataFor(project, e)))
    }
    return out
}

// Önizleme satırını doldur: dosyayı oku, sınıf sayımı + koordinat sınır denetimi
def summarizeRow = { Map r, Set knownClasses = null ->
    try {
        def read = readObjectsFrom((File) r.file, (String) r.format)
        def counts = new TreeMap()
        read.objects.each { o -> String k = classNameOf(o) ?: UNCLASSIFIED; counts.put(k, ((counts.get(k) ?: 0) as int) + 1) }
        def data = r.target.live
        boolean opened = false
        try {
            if (data == null) { data = r.target.entry.readImageData(); opened = true }
            r.outOfBounds = outOfBounds(read.objects, data.getServer().getWidth(), data.getServer().getHeight())
            def ec = existingCounts(data, sourceKeyOf((String) r.format, (File) r.file))
            r.existing = ec.annotations
            r.existingSameFile = ec.sameFile
        } finally {
            if (opened && data != null) { try { data.getServer().close() } catch (Throwable ignore) {} }
        }
        r.summary = read.objects.size() + ' nesne · ' + counts.collect { k, v -> k + ((knownClasses != null && k != UNCLASSIFIED && !knownClasses.contains(k)) ? ' (yeni)' : '') + ' ' + v }.join(', ') +
            ((read.orphanHoles > 0) ? (' · ' + read.orphanHoles + ' sahipsiz delik') : '') +
            (read.warnings ? (' · ' + read.warnings.join('; ')) : '')
        if (r.outOfBounds) { r.include = false; r.status = 'koordinat dışı' }
    } catch (Throwable e) {
        r.include = false
        r.status = 'okunamadı'
        r.summary = e.getMessage() ?: e.getClass().getSimpleName()
    }
}

def startPreview = {
    if (projectChanged()) { showError('Proje değişti; önizlemeyi yeniden oluşturun.'); return }
    previewRef.set(null)     // yeni önizleme başlıyor: eski önizleme geçersiz (hata → "Geri" kaynağa döner)
    File chosen = sourceRef.get()
    if (chosen == null || !chosen.exists()) { showError('Önce bir kaynak klasör (ya da dosya) seçin.'); return }
    def targets = buildTargets(scopeRef.get())
    if (targets.isEmpty()) { showError(scopeRef.get() == 'selected' ? 'Hiç görüntü işaretlenmedi.' : 'İçe aktarılacak görüntü yok (proje ya da açık görüntü yok).'); return }
    String fmt = prefs.get('format', 'otomatik')
    prefs.put('scope', scopeRef.get())
    try { prefs.flush() } catch (Throwable ignore) {}
    // Projenin sınıf listesindeki adlar (FX iş parçacığında okunur); listede olmayan sınıflar önizlemede "(yeni)" işaretlenir
    Set knownClasses = new HashSet()
    try {
        gui?.getAvailablePathClasses()?.each { pc ->
            if (pc != null && pc != PathClass.NULL_CLASS) knownClasses.add(pc.toString())
        }
    } catch (Throwable ignore) {}
    cancelledRef.set(false)
    step.set('LOADING'); render()
    def worker = new Thread({
        try {
            def src = resolveSource(chosen, fmt)
            if (src.files.isEmpty()) throw new IllegalStateException('Kaynakta okunabilir GeoJSON / ImageScope / ASAP dosyası bulunamadı:\n' + chosen.getAbsolutePath())
            def m = buildMatches(targets, src.files, readManifest((File) src.dir))
            if (chosen.isFile() && targets.size() == 1) forceSingle(m, src.files[0])
            int i = 0
            for (r in m.rows) {
                i++
                if (cancelledRef.get()) break
                if (r.file == null) continue
                setProgress(i, m.rows.size(), (String) r.target.name)
                summarizeRow(r, knownClasses)
            }
            javafx.application.Platform.runLater {
                if (cancelledRef.get()) { step.set('SOURCE'); render(); return }
                previewRef.set([rows: m.rows, unmatched: m.unmatched, files: src.files, dir: src.dir])
                step.set('PREVIEW'); render()
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { showError('Önizleme hazırlanamadı:\n' + (t.getMessage() ?: t.getClass().getSimpleName())) }
        }
    }, 'AtolyeAnotasyonIce-Preview')
    worker.setDaemon(true); worker.start()
}

def startImport = {
    if (projectChanged()) { showError('Proje değişti; önizlemeyi yeniden oluşturun.'); return }
    def pv = previewRef.get()
    if (pv == null) { showError('Önce bir önizleme oluşturun.'); return }
    boolean lock = prefs.getBoolean('lock', false)
    def todo = pv.rows.findAll { it.include && it.file != null }
    if (todo.isEmpty()) { showError('İçe aktarılacak işaretli satır yok.'); return }
    // Projesiz tek görüntü: önizlemedeki açık görüntü hâlâ geçerli görüntü olmalı (aksi hâlde yanlış görüntüye yazılırdı)
    def curData = QP.getCurrentImageData()
    if (todo.any { it.target.entry == null && !(curData != null && curData.is(it.target.live)) }) {
        showError('Açık görüntü değişti; önizlemeyi yeniden oluşturun.'); return
    }
    // Pencere kalıcıdır: önizlemeden sonra bir hedef slayt açılmış olabilir → açık slaytların sayımlarını ŞİMDİ tazele
    def projNow = QP.getProject()
    todo.each { r ->
        def ld = (r.target.entry != null) ? liveDataFor(projNow, r.target.entry) : r.target.live
        if (ld != null) {
            try {
                def ec = existingCounts(ld, sourceKeyOf((String) r.format, (File) r.file))
                r.existing = ec.annotations
                r.existingSameFile = ec.sameFile
            } catch (Throwable ignore) {}
        }
    }
    // Önceki anotasyonlar için kullanıcıya SOR — varsayılan: hiçbir şey silme (kullanıcı kararı D5).
    // Yalnız tüm hedeflerin boş olduğu BİLİNİYORSA sorulmaz (sayımı bilinmeyen satırlar → sorulur).
    // Elle seçilen (ve açık olmayan) satırda "aynı dosyadan" sayısı bilinmez (null → 0 sayılır); kip 'dosya' o satırda yine yalnız o dosyanın etiketli nesnelerini siler.
    String mode = 'ekle'
    boolean anyUnknown = todo.any { it.existing == null }
    int nImg = todo.count { (it.existing ?: 0) > 0 } as int
    int nAnn = (todo.collect { (it.existing ?: 0) as int }.sum() ?: 0) as int
    int nSame = (todo.collect { (it.existingSameFile ?: 0) as int }.sum() ?: 0) as int
    if (anyUnknown || nAnn > 0) {
        def sb = new StringBuilder()
        if (nAnn > 0) sb << String.format(java.util.Locale.US, '%,d hedef görüntüde zaten %,d anotasyon var.', nImg, nAnn)
        else sb << 'Hedef görüntülerin bazılarında mevcut anotasyonlar olabilir (elle seçilen dosyalar önizlenmedi).'
        if (nSame > 0) sb << String.format(java.util.Locale.US,
            '\n%,d nesne bu dosyalardan daha önce içe aktarılmış; ImageScope/ASAP dosyalarında "yalnız ekle" bunların ikinci kopyasını oluşturur (GeoJSON\'da aynı UUID yeniden eklenmez).\n"Aynı dosyadan önceki içe aktarımı değiştir" seçeneği bu nesnelerde sonradan yaptığınız düzeltmeleri de siler.', nSame)
        sb << '\n\nÖnceki anotasyonlar ne olsun?'
        def pick = Dialogs.showChoiceDialog('Önceki anotasyonlar', sb.toString(), (MODE_LABELS.values() as List), MODE_LABELS.ekle)
        if (pick == null) return                                   // İptal → hiçbir şey yazılmaz
        mode = (String) MODE_LABELS.find { k, v -> v == pick }.key
        if (mode == 'tumu') {
            boolean ok = Dialogs.showConfirmDialog('Tüm anotasyonlar silinecek',
                String.format(java.util.Locale.US, '%,d görüntüde mevcut TÜM anotasyonlar silinip dosyadakilerle değiştirilecek (tespitler korunur). Bu işlem geri alınamaz.\n\nDevam edilsin mi?', todo.size()))
            if (!ok) return
        }
    }
    def projAtRun = QP.getProject()      // çalıştırma boyunca SABİT proje (kimlikler projeler arasında çakışır)
    if (projAtRun != null && todo.any { it.target.entry != null && !projAtRun.getImageList().any { e -> e.is(it.target.entry) } }) {
        showError('Önizleme bu projeye ait değil; önizlemeyi yeniden oluşturun.'); return
    }
    cancelledRef.set(false)
    def reportDirAtRun = reportDirFor(projAtRun)
    previewRef.set(null)                 // çalıştırma başladı: önizleme tüketildi (hata → "Geri" kaynağa döner)
    step.set('RUNNING'); render()
    def onFx = { int secs, Closure body ->         // FX iş parçacığında çalıştır, sonucu bekle; hata/zaman aşımı çağırana yayılır (fail-closed)
        def ft = new java.util.concurrent.FutureTask({ -> body() } as java.util.concurrent.Callable)
        javafx.application.Platform.runLater(ft)
        return ft.get(secs, java.util.concurrent.TimeUnit.SECONDS)
    }
    def worker = new Thread({
        try {
            def res = runImport(pv.rows, [mode: mode, lock: lock, respectBounds: false, reportDir: reportDirAtRun,
                                      liveResolver: { e -> onFx(10) { liveDataForStrict(projAtRun, e) } },
                                      stillValid: { -> onFx(30) { QP.getProject().is(projAtRun) } }], setProgress, cancelledRef)
            String txt = resultText(res, (String) MODE_LABELS[mode])
            String ttl = res.cancelled ? 'İptal edildi' : res.stopped ? (res.stopUnverified ? 'Durduruldu ⚠' : 'Durduruldu — proje değişti ⚠') : ((res.failed as int) > 0 ? 'Tamamlandı (hatalarla) ⚠' : 'Tamamlandı ✅')
            javafx.application.Platform.runLater {
                if (res.liveTouched) { try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {} }
                resultTextRef.set(txt); resultTitleRef.set(ttl); step.set('RESULT'); render()
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater { showError('İçe aktarma başarısız:\n' + (t.getMessage() ?: t.getClass().getSimpleName())) }
        }
    }, 'AtolyeAnotasyonIce-Run')
    worker.setDaemon(true); worker.start()
}

// ── Render: her durum değişiminde sahneyi sıfırdan kurar ────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def project = QP.getProject()
    def imageData = QP.getCurrentImageData()
    // Proje değiştiyse eski işaretleri/önizlemeyi at (başka projenin satırlarını yazmamak için)
    String projPath = currentProjectPath()
    if (projPath != lastProjectPath.get()) {
        checkedImages.clear(); previewRef.set(null); lastProjectPath.set(projPath)
    }
    if (step.get() == 'PREVIEW' && previewRef.get() == null) step.set('SOURCE')
    def cur = step.get()

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
    def addProgress = { String first ->
        def pl = new javafx.scene.control.Label(first); pl.setWrapText(true); pl.setMaxWidth(Double.MAX_VALUE)
        def pb = new javafx.scene.control.ProgressBar(0.0d); pb.setMaxWidth(Double.MAX_VALUE)
        progressLabelRef.set(pl); progressBarRef.set(pb)
        center.getChildren().addAll(pl, pb)
    }
    def onChange = { Closure c -> ({ obs, o, n -> c(n) } as javafx.beans.value.ChangeListener) }

    if (cur == 'SOURCE') {
        title.setText('Toplu anotasyon içe aktarma')

        section('1 · Kapsam')
        String sc = scopeRef.get()
        if ((sc == 'current' && imageData == null) || (sc != 'current' && project == null)) sc = (project != null) ? 'project' : 'current'
        scopeRef.set(sc)
        def tg = new javafx.scene.control.ToggleGroup()
        def mkRadio = { String text, String key, boolean disabled ->
            def rb = new javafx.scene.control.RadioButton(text); rb.setToggleGroup(tg); rb.setUserData(key)
            rb.setDisable(disabled); rb.setSelected(key == sc); return rb
        }
        center.getChildren().add(new javafx.scene.layout.HBox(16,
            mkRadio('Bu görüntü', 'current', imageData == null),
            mkRadio('Seçili proje görüntüleri', 'selected', project == null),
            mkRadio('Tüm proje', 'project', project == null)))
        tg.selectedToggleProperty().addListener(onChange { n -> if (n != null) { scopeRef.set((String) n.getUserData()); render() } })
        if (project != null && sc == 'selected') {
            def list = new javafx.scene.layout.VBox(4)
            list.setPadding(new javafx.geometry.Insets(4))
            project.getImageList().each { e ->
                def cb = new javafx.scene.control.CheckBox(e.getImageName())
                cb.setSelected(checkedImages.contains(e.getID()))
                cb.selectedProperty().addListener(onChange { n -> if (n) checkedImages.add(e.getID()) else checkedImages.remove(e.getID()) })
                list.getChildren().add(cb)
            }
            def lsp = new javafx.scene.control.ScrollPane(list)
            lsp.setPrefViewportHeight(150)
            lsp.setFitToWidth(true)
            center.getChildren().add(lsp)
        }

        section('2 · Kaynak')
        File src = sourceRef.get()
        def srcLbl = new javafx.scene.control.Label(src != null ? src.getAbsolutePath() : '(seçilmedi)')
        srcLbl.setWrapText(true); srcLbl.setMaxWidth(Double.MAX_VALUE)
        def pickDir = navButton('Klasör seç…', {
            def d = FileChoosers.promptForDirectory(stage, 'Anotasyon klasörünü seçin', (src != null && src.isDirectory()) ? src : src?.getParentFile())
            if (d != null) { sourceRef.set(d); render() }
        }, 'Dışa aktarma kökü (geojson/ imagescope/ asap/ alt klasörlü) ya da dosyaların durduğu klasör')
        def pickFile = navButton('Dosya seç…', {
            def f = FileChoosers.promptForFile(stage, 'Anotasyon dosyasını seçin',
                FileChoosers.createExtensionFilter('Anotasyon (*.geojson, *.json, *.xml)', '*.geojson', '*.json', '*.xml'),
                FileChoosers.FILTER_ALL_FILES)
            if (f != null) { sourceRef.set(f); render() }
        }, 'Yalnız "Bu görüntü" kapsamında: tek dosyayı açık görüntüye aktarır')
        pickFile.setDisable(sc != 'current')
        def srcRow = new javafx.scene.layout.HBox(8, srcLbl, pickDir, pickFile)
        srcRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        center.getChildren().add(srcRow)
        def fmtBox = new javafx.scene.control.ChoiceBox()
        FORMAT_LABELS.each { k, v -> fmtBox.getItems().add(v) }
        fmtBox.setValue(FORMAT_LABELS[prefs.get('format', 'otomatik')] ?: FORMAT_LABELS.otomatik)
        fmtBox.valueProperty().addListener(onChange { n -> def key = FORMAT_LABELS.find { k, v -> v == n }?.key; if (key) prefs.put('format', (String) key) })
        def fmtRow = new javafx.scene.layout.HBox(8, new javafx.scene.control.Label('Biçim:'), fmtBox)
        fmtRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        center.getChildren().add(fmtRow)

        section('3 · Seçenekler')
        def lockChk = new javafx.scene.control.CheckBox('İçe aktarılan nesneleri kilitle')
        lockChk.setSelected(prefs.getBoolean('lock', false))
        lockChk.selectedProperty().addListener(onChange { n -> prefs.putBoolean('lock', n) })
        center.getChildren().add(lockChk)
        addGuidance('"Önizle" hiçbir şey yazmaz. "İçe aktar"a bastığınızda, hedef görüntülerde anotasyon varsa ne yapılacağı ' +
            'sorulur; varsayılan seçenek önceki anotasyonlara DOKUNMAZ. İçe aktarılan her nesne "atolye_iceaktarim" üst ' +
            'verisiyle (dosya adı · zaman) işaretlenir; hedefte aynı UUID\'li bir nesne zaten varsa yenisi eklenmez.')
        def go = navButton('Önizle ▶', { startPreview() }, 'Dosyaları görüntülerle eşler ve içeriğini özetler; henüz hiçbir şey yazmaz')
        go.setDisable(src == null)
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(go)
    } else if (cur == 'LOADING') {
        title.setText('Önizleme hazırlanıyor…')
        addProgress('Dosyalar eşleniyor…')
        addGuidance('Her eşleşen dosya okunur ve görüntü boyutuyla karşılaştırılır; henüz hiçbir şey yazılmaz.')
        actions.add(navButton('İptal et', { cancelledRef.set(true) }))
    } else if (cur == 'PREVIEW') {
        def pv = previewRef.get()
        def rows = pv.rows
        title.setText('Eşleştirme önizlemesi — henüz hiçbir şey yazılmadı')
        int nOk = rows.count { it.status == 'eşleşti' } as int
        int nAmb = rows.count { it.status == 'belirsiz' } as int
        int nNone = rows.count { it.status == 'dosya yok' } as int
        int nBad = rows.count { it.status in ['koordinat dışı', 'okunamadı'] } as int
        addGuidance(String.format(java.util.Locale.US, '%,d görüntü · eşleşti %,d · belirsiz %,d · dosya yok %,d · sorunlu %,d   — kaynak: %s',
            rows.size(), nOk, nAmb, nNone, nBad, ((File) pv.dir).getAbsolutePath()))
        String NONE = '— dosya yok —'
        def byName = new LinkedHashMap()
        pv.files.each { fe -> byName.put(fe.file.getName(), fe) }
        def grid = new javafx.scene.layout.GridPane()
        grid.setHgap(10); grid.setVgap(6)
        ['İçe aktar', 'Görüntü', 'Dosya', 'Eşleşme', 'Durum / içerik'].eachWithIndex { String h, int c ->
            def l = new javafx.scene.control.Label(h); l.setStyle('-fx-font-weight: bold;'); grid.add(l, c, 0)
        }
        rows.eachWithIndex { r, int i ->
            int rowIdx = i + 1
            def inc = new javafx.scene.control.CheckBox()
            inc.setSelected((boolean) r.include); inc.setDisable(r.file == null)
            inc.selectedProperty().addListener(onChange { n -> r.include = n })
            def combo = new javafx.scene.control.ComboBox()
            def opts = [NONE] + r.candidates.collect { it.file.getName() } + pv.unmatched.collect { it.file.getName() }
            combo.getItems().addAll(opts.unique())
            combo.setValue(r.file != null ? r.file.getName() : NONE)
            def st = new javafx.scene.control.Label(r.status + (r.summary ? (' — ' + r.summary) : ''))
            st.setWrapText(true); st.setMaxWidth(420)
            def howLbl = new javafx.scene.control.Label((String) (r.how ?: ''))
            combo.valueProperty().addListener(onChange { n ->
                def pick = (n == null || n == NONE) ? null : byName.get(n)
                r.file = pick?.file; r.format = pick?.format; r.how = (pick == null) ? '' : 'elle'
                r.existingSameFile = null          // önceki dosyanın sayısı yeni dosya için geçersiz (existing görüntüye aittir, kalır)
                howLbl.setText((String) r.how)
                r.include = (pick != null)
                inc.setSelected((boolean) r.include); inc.setDisable(pick == null)
                r.status = (pick == null) ? 'dosya yok' : 'elle seçildi'
                r.summary = (pick == null) ? '' : '(çalıştırmada okunacak)'
                st.setText(r.status + (r.summary ? (' — ' + r.summary) : ''))
            })
            grid.add(inc, 0, rowIdx)
            grid.add(new javafx.scene.control.Label((String) r.target.name), 1, rowIdx)
            grid.add(combo, 2, rowIdx)
            grid.add(howLbl, 3, rowIdx)
            grid.add(st, 4, rowIdx)
        }
        def sp = new javafx.scene.control.ScrollPane(grid)
        sp.setFitToWidth(true)
        javafx.scene.layout.VBox.setVgrow(sp, javafx.scene.layout.Priority.ALWAYS)
        center.getChildren().add(sp)
        addGuidance('Koordinatı görüntü sınırını aşan ya da okunamayan satırlar işaretsiz başlar; yine de aktarmak için işaretleyin. ' +
            'Açılır listeden elle seçilen dosyalar çalıştırmada yine sınır denetiminden geçer. ' +
            '"İçe aktar" önce mevcut anotasyonlar için ne yapılacağını sorar (varsayılan: dokunma).' +
            (prefs.getBoolean('lock', false) ? ' Nesneler kilitli eklenecek.' : ''))
        actions.add(navButton('◀ Geri', { step.set('SOURCE'); render() }))
        actions.add(navButton('İçe aktar ▶', { startImport() }, 'İşaretli satırları içe aktarır ve kaydeder'))
    } else if (cur == 'RUNNING') {
        title.setText('İçe aktarılıyor…')
        addProgress('Başlıyor…')
        addGuidance('İptal, sıradaki görüntüden önce durur; işlenmekte olan görüntü tamamlanır ve kaydedilir.')
        actions.add(navButton('İptal et', { cancelledRef.set(true) }))
    } else if (cur == 'RESULT') {
        title.setText(resultTitleRef.get())
        addMonoArea(resultTextRef.get())
        actions.add(navButton('Kapat', { stage.close() }))
        actions.add(navButton('Kopyala', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('↻ Yeniden', { step.set('SOURCE'); render() }))
    } else { // ERROR
        title.setText('Hata')
        addMonoArea(errorTextRef.get())
        // Önizleme varsa (içe aktarma öncesi doğrulama hatası) önizleme ve elle seçimler korunur; yoksa kaynağa dön
        actions.add(navButton('◀ Geri', { step.set(previewRef.get() != null ? 'PREVIEW' : 'SOURCE'); render() }))
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

    def root = new javafx.scene.layout.BorderPane()
    if (cur == 'SOURCE') {
        def scroll = new javafx.scene.control.ScrollPane(center); scroll.setFitToWidth(true)
        root.setCenter(scroll)
    } else {
        root.setCenter(center)
    }
    root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 940, 680))
}

// ── Açılış ───────────────────────────────────────────────────────────────────
javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Toplu anotasyon içe aktarma')
        stage.setAlwaysOnTop(alwaysTop.get())
        // Pencere LOADING/RUNNING sırasında kapatılırsa işlem iptal edilir
        stage.setOnCloseRequest({ cancelledRef.set(true) } as javafx.event.EventHandler)
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Sihirbaz açılamadı', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println '✓ Toplu anotasyon içe aktarma sihirbazı açıldı.'
