package io.github.sbalci.qupath.workshop

import groovy.test.GroovyTestCase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.image.BufferedImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.datamatrix.DataMatrixWriter

/** Production closures with synthetic TIFFs and injected host checks, never patient data.
 * QuPath server/GUI operations are NOT exercised by these tests.
 */
class AnonymizationRegressionTest extends GroovyTestCase {
    static final String SCRIPT = 'yardimci-anonim-sihirbaz.groovy'
    File dir
    void setUp() {
        // Contain even the legacy path-traversal reproduction in an owned sandbox.
        dir = new File(Files.createTempDirectory('anon-regression-').toFile(), 'work')
        assert dir.mkdir()
    }
    void tearDown() { dir.parentFile.deleteDir() }
    // Closures are emitted in SCRIPT order (a closure may only use ones defined above it); duplicates dropped.
    static String code(String... names) {
        String s = SafetyRegressionTest.source(SCRIPT)
        names.toList().unique().sort { s.indexOf('def ' + it + ' = {') }.collect { SafetyRegressionTest.closure(SCRIPT, it) }.join('\n')
    }
    static String imports = '''import java.nio.*
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.image.BufferedImage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
'''
    static String constants() {
        String s = SafetyRegressionTest.source(SCRIPT)
        s.substring(s.indexOf('def PHI_EXACT'), s.indexOf('// ── Kalıcı yapılandırma'))
    }
    static List TIFF_NAMES = ['rdBuf','u16','u32','parseTiff','readAscii','writeAscii',
        'readUInts','writePtr','assocKind','normKey','isPhiKey','isBlankValue','redactAperioDesc','blankAscii',
        'stemReplaceable','ownedRanges','mergeRanges','overlapsMerged','verifyZeroed','editTiff','inspectTiff','scanForProbes',
        'latin1Form','isLabelOrMacro','extOf','fmtBytes','moveWithRetry','fingerprintOf','fpEqual','fpString',
        'compileAccession','maskHit','accessionHits','pinTimes']
    static String tiffCode() { imports + constants() + code(TIFF_NAMES as String[]) }
    def shell(Map vars = [:]) { SafetyRegressionTest.shell(vars) }

    // Minimal classic/BigTIFF in either byte order: tissue + label + macro IFDs,
    // with out-of-line descriptions and non-overlapping strip payloads.
    File fixture(boolean big = false, boolean le = true) {
        def b = ByteBuffer.allocate(4096).order(le ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN)
        b.put((le ? 0x49 : 0x4d) as byte).put((le ? 0x49 : 0x4d) as byte)
        b.putShort((big ? 43 : 42) as short)
        if (big) { b.putShort(8 as short).putShort(0 as short).putLong(16L) } else b.putInt(16)
        [16, 256, 496].eachWithIndex { int off, int i ->
            String d = i == 0 ? 'Aperio Image Library\n100x100|Patient=SECRET123|MPP=0.25|AppMag=40' : (i == 1 ? 'label 10x10' : 'macro 10x10')
            byte[] desc = (d + '\u0000').getBytes(StandardCharsets.ISO_8859_1)
            b.position(off)
            if (big) b.putLong(3L) else b.putShort(3 as short)
            [[270,2,desc.length,1000+i*200], [273,4,1,2000+i*100], [279,4,1,16]].each { e ->
                b.putShort(e[0] as short).putShort(e[1] as short)
                if (big) {
                    b.putLong(e[2] as long)
                    if (e[1] == 4) b.putInt(e[3] as int).putInt(0) else b.putLong(e[3] as long)
                } else b.putInt(e[2] as int).putInt(e[3] as int)
            }
            long next = i < 2 ? [256L,496L][i] : 0L
            if (big) b.putLong(next) else b.putInt(next as int)
            b.position(1000+i*200); b.put(desc)
            b.position(2000+i*100); b.put(('P' * 16).bytes)
        }
        File f = new File(dir, "source-${big}-${le}.svs"); f.bytes = b.array(); f
    }

    void testWholeWizardParsesAndMenuSupportsProjectWithoutOpenSlide() {
        def unit = new org.codehaus.groovy.control.CompilationUnit()
        unit.addSource(SCRIPT, SafetyRegressionTest.source(SCRIPT))
        unit.compile(org.codehaus.groovy.control.Phases.CONVERSION)
        String menu = new File('src/main/java/io/github/sbalci/qupath/workshop/WorkshopExtension.java').text
        assert menu.contains('"yardimci-anonim-sihirbaz.groovy", false, false)')
    }

    void testPlanDeduplicatesSourcesAndAvoidsExistingDestinations() {
        File f = fixture()
        new File(dir,'case_1.svs').text = 'existing'
        def items = [[file:f,name:f.name,uris:[]],[file:f,name:'Second entry',uris:[]]]
        def r = shell([items:items,out:dir,classifyFile:{a,b -> 'aperio'},KIND_LABEL:[:]]).evaluate(
            imports + code('extOf','groupUnits','buildPlan') + "\nreturn buildPlan([prefix:'case',naming:'sequential'],items,out)")
        assert r.units.size() == 1 && r.units[0].items.size() == 2
        assert r.units[0].anon == 'case_1a.svs'
        shouldFail(IllegalArgumentException) {
            shell([items:items,out:dir,classifyFile:{a,b -> 'aperio'},KIND_LABEL:[:]]).evaluate(imports + code('extOf','groupUnits','buildPlan') + "\nbuildPlan([prefix:'../bad'],items,out)")
        }
    }

    void testAuditReadOnlyAcrossTiffVariantsAndMalformedInput() {
        [false,true].each { big -> [false,true].each { le ->
            File f = fixture(big,le); byte[] before = f.bytes
            def r = shell([f:f]).evaluate(tiffCode() + "return inspectTiff(f, 'aperio', [])")
            assert r.ok && r.big == big && r.ifdCount == 3
            assert r.phi.keySet() == ['Patient'] as Set
            assert r.assocIfds.size() == 2
            assert f.bytes == before
        } }
        File bad = new File(dir,'bad.svs'); bad.text = 'not a TIFF'
        assert !shell([f:bad]).evaluate(tiffCode() + "return inspectTiff(f, 'aperio', [])").ok
    }

    static Map cfg() { [redactMeta:true,removeLabels:true] }
    static Map hostCheck() { [ok:true,width:100,height:100,px:0.25d,assoc:[]] }
    static String processCode() { tiffCode() + code('tmpNameFor','processUnitCore','processUnit') }
    Map runCopy(File f, AtomicBoolean cancel, Closure check, Map more = [:]) {
        def vars = [u:[file:f,kind:'aperio',anon:'case_1.svs'],cfg:cfg(),outDir:dir,
            log:{String s ->},cancel:cancel,qupathCheck:check,stemsOf:{u -> []},KIND_LABEL:[aperio:'Aperio'],
            mainScanCheck:{File p, boolean k -> [ok:true,left:0d,right:0d,flagged:false]}] + more
        shell(vars).evaluate(processCode() + '\nreturn processUnit(u,cfg,outDir,log,cancel)')
    }
    void testAnonymizedCopyPreservesSourceTissueAndCalibrationFields() {
        [false,true].each { big -> [false,true].each { le ->
            File f = fixture(big,le); byte[] before = f.bytes
            def r = runCopy(f,new AtomicBoolean(false),{File p -> hostCheck()})
            assert r.status == 'TAMAM'
            File out = new File(dir,'case_1.svs')
            assert f.bytes == before
            assert out.length() == f.length()
            assert out.bytes[2000..2015] == before[2000..2015]
            assert out.bytes[2100..2115].every { it == 0 }
            assert out.bytes[2200..2215].every { it == 0 }
            assert new String(out.bytes,StandardCharsets.ISO_8859_1).contains('MPP=0.25|AppMag=40')
            def inspected = shell([f:out]).evaluate(tiffCode() + "return inspectTiff(f,'aperio',[])")
            assert inspected.ok && inspected.phi.isEmpty() && inspected.assocIfds.isEmpty()
            out.delete()
        } }
    }
    void testCancellationBeforeCopyAfterCopyAndBeforeFinalMove() {
        [0,1,2].each { phase ->
            File f = fixture(); byte[] before = f.bytes
            def cancel = new AtomicBoolean(phase == 0); int calls = 0
            def r = runCopy(f,cancel,{File p -> if (++calls == phase) cancel.set(true); hostCheck()})
            assert r.status == 'ATLANDI'
            if (phase == 0) assert calls == 0 : 'Pre-cancelled operation started host work'
            assert !new File(dir,'case_1.svs').exists()
            assert dir.listFiles().toList() == [f]
            assert f.bytes == before
        }
    }
    void testLegacyTemporaryFileIsNeverDeleted() {
        File f = fixture()
        File legacy = new File(dir,'case_1.anonim-gecici.svs'); legacy.text = 'user file'
        runCopy(f,new AtomicBoolean(false),{File p -> hostCheck()})
        assert legacy.exists() && legacy.text == 'user file'
    }
    void testLateCancellationCannotPublishOutput() {
        File f = fixture(); def cancel = new AtomicBoolean(false); int calls = 0
        def r = runCopy(f,cancel,{File p -> if (++calls == 2) cancel.set(true); hostCheck()})
        assert r.status == 'ATLANDI'
        assert !new File(dir,'case_1.svs').exists()
    }

    void testExistingDestinationTempAndFailedVerificationPreserved() {
        File f = fixture(); byte[] before = f.bytes
        File legacyTemp = new File(dir,'case_1.anonim-gecici.svs'); legacyTemp.text = 'user file'
        File out = new File(dir,'case_1.svs'); out.text = 'existing destination'
        assert runCopy(f,new AtomicBoolean(false),{File p -> hostCheck()}).status == 'HATA'
        assert out.text == 'existing destination' && legacyTemp.text == 'user file'
        out.delete()
        int calls = 0
        assert runCopy(f,new AtomicBoolean(false),{File p -> ++calls == 1 ? hostCheck() : [ok:false,error:'fixture failure']}).status == 'HATA'
        assert !out.exists() && legacyTemp.text == 'user file' && f.bytes == before
        assert !dir.listFiles().any { it.name.startsWith('atolye-anon-') }
    }
    void testOutputPathCannotAliasSourceOrEscapeDirectory() {
        File f = fixture(); byte[] before = f.bytes
        [f.name,'../escape.svs'].each { name ->
            assert runCopy(f,new AtomicBoolean(false),{File p -> hostCheck()},[u:[file:f,kind:'aperio',anon:name]]).status == 'HATA'
            assert f.bytes == before
        }
    }

    void testIncompleteAuditIsUncertainAndCancellationDoesNotLoadDependencies() {
        File f = fixture(); byte[] before = f.bytes
        [false,true].each { tiffOk ->
            def r = shell([items:[],cancel:new AtomicBoolean(false),log:{s ->},
                groupUnits:{items -> [[file:f,kind:'aperio',items:[]]]},stemsOf:{u -> []},KIND_LABEL:[aperio:'Aperio'],
                qupathCheck:{p -> [ok:tiffOk,error:'cannot open']},
                assocCheck:{a,b,c,d -> [label:[state:'NONE'],macro:[state:'NONE'],opened:true]},
                inspectTiff:{a,b,c -> [ok:!tiffOk,error:'invalid TIFF',phi:[:],std:[],assocIfds:[],stemHit:false]}]).evaluate(
                imports + code('runAudit') + '\nreturn runAudit(items,log,cancel,[:])')
            assert r.counts['TEMİZ'] == 0 && r.counts['BELİRSİZ'] == 1
        }
        def r = shell([items:[],cancel:new AtomicBoolean(true),log:{s ->},groupUnits:{items -> []},
            ensureZxing:{a,b -> fail('Cancelled audit must not download') }]).evaluate(imports + code('runAudit') + '\nreturn runAudit(items,log,cancel,[decodeLabels:true])')
        assert f.bytes == before
    }

    void testDownloadedDependencyRejectsCorruptionAndPreservesCache() {
        File cache = new File(dir,'core-3.5.3.jar'); cache.text = 'previous invalid cache'
        byte[] payload = 'not a jar'.bytes
        def server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        Thread responder = Thread.startDaemon {
            server.accept().withCloseable { socket ->
                socket.soTimeout = 5000
                def reader = new BufferedReader(new InputStreamReader(socket.inputStream))
                while (reader.readLine()) { }
                socket.outputStream.write(("HTTP/1.1 200 OK\r\nContent-Length: ${payload.length}\r\nConnection: close\r\n\r\n").getBytes('US-ASCII'))
                socket.outputStream.write(payload); socket.outputStream.flush()
            }
        }
        try {
            String pin = (SafetyRegressionTest.source(SCRIPT) =~ /def ZX_SHA256 = '([a-f0-9]+)'/)[0][1]
            def logs = []
            def r = shell([cache:cache,ZX_SHA256:pin,ZX_URL:"http://localhost:${server.localPort}/core.jar",logs:logs]).evaluate(
                imports + code('sha256Of') + '\ndef zxJarFile = { -> cache }\n' + code('ensureZxing') + '\nreturn ensureZxing(true, {s -> logs << s})')
            assert r == null
            assert logs.any { it.contains('SHA-256') }
            assert cache.text == 'previous invalid cache'
            assert !new File(dir,'core-3.5.3.jar.tmp').exists()
        } finally { server.close(); responder.join(6000) }
    }

    void testPinnedZxingCacheVerificationAndRealDataMatrixDecode() {
        File jar = new File(DataMatrixWriter.protectionDomain.codeSource.location.toURI())
        String source = SafetyRegressionTest.source(SCRIPT)
        String pin = (source =~ /def ZX_SHA256 = '([a-f0-9]+)'/)[0][1]
        File cache = new File(dir,'core-3.5.3.jar'); cache.bytes = jar.bytes
        def sh = shell([cache:cache,ZX_SHA256:pin])
        assert sh.evaluate(code('sha256Of') + '\nreturn sha256Of(cache)') == pin
        String setup = code('sha256Of') + '\ndef zxJarFile = { -> cache }\n' + code('ensureZxing')
        assert sh.evaluate(setup + '\nreturn ensureZxing(false, {s ->})') == cache
        cache.text = 'corrupt'
        assert sh.evaluate(setup + '\nreturn ensureZxing(false, {s ->})') == null
        assert !new File(dir,'core-3.5.3.jar.tmp').exists()
        def matrix = new DataMatrixWriter().encode('ANON123456',BarcodeFormat.DATA_MATRIX,240,240)
        def image = new BufferedImage(matrix.width,matrix.height,BufferedImage.TYPE_INT_RGB)
        for (int y=0;y<matrix.height;y++) for(int x=0;x<matrix.width;x++) image.setRGB(x,y,matrix.get(x,y) ? 0 : 0xffffff)
        assert shell([jar:jar,image:image]).evaluate(imports + code('lum','makeDecoder') + '\nreturn makeDecoder(jar)(image)') == 'ANON123456'
    }

    // ═══ Lessons from a production pipeline: A (safety), B (pseudonyms/keys), C (main scan), D (package) ═══

    static List KEY_NAMES = ['csvCell','parseCsvLine','hexOf','hmacHex','dirKey','assertKeyDirSafe','assertSaveDirSafe','keyDirOf',
        'saltNotice','loadSalt','loadCrosswalk','prepareKeyState','appendCrosswalk','extOf','groupUnits','buildPlan']
    static List APPLY_NAMES = ['stemOf','stemsOf','tmpNameFor','processUnitCore','processUnit','sha256Of','writeKeyFiles','foreignOutputs',
        'edgeFlagged','reviewUnits','buildBenioku','writePackage','buildApplyText','sanitizerFor','runApply']
    static String keyCode() { imports + constants() + code(KEY_NAMES as String[]) }
    static String applyCode() { imports + constants() + code((TIFF_NAMES + KEY_NAMES + APPLY_NAMES) as String[]) }

    // General multi-page Aperio-like fixture. Every page owns: IFD block, out-of-line description, StripOffsets
    // array (3 entries), StripByteCounts array (3 entries) and 3 x 16 bytes of strip data.
    // pages: [[desc:'...', kind:'keep'|'remove'], ...]; opts: big, le, overlap ('strip'|'desc'), overlapPage
    Map build(List pages, Map o = [:]) {
        boolean big = o.big ?: false, le = (o.le == null) ? true : o.le
        int cntSz = big ? 8 : 2, ent = big ? 20 : 12, ptr = big ? 8 : 4
        int cursor = big ? 16 : 8
        def lay = []
        pages.each { p ->
            byte[] d = (p.desc + '\u0000').getBytes(StandardCharsets.ISO_8859_1)
            def L = [desc: d, remove: p.kind == 'remove']
            L.ifd = cursor; cursor += cntSz + 3 * ent + ptr
            L.descOff = cursor; cursor += d.length; cursor = (cursor + 7) & ~7
            L.offArr = cursor; cursor += 3 * (big ? 8 : 4)
            L.cntArr = cursor; cursor += 12; cursor = (cursor + 7) & ~7
            L.strip = cursor; cursor += 48
            lay << L
        }
        int total = cursor + 256
        def b = ByteBuffer.allocate(total).order(le ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN)
        b.put((le ? 0x49 : 0x4d) as byte).put((le ? 0x49 : 0x4d) as byte)
        b.putShort((big ? 43 : 42) as short)
        if (big) { b.putShort(8 as short).putShort(0 as short).putLong(lay[0].ifd as long) } else b.putInt(lay[0].ifd as int)
        lay.eachWithIndex { L, int k ->
            int descPtr = L.descOff, stripPtr = L.strip
            int cntPtr = (o.overlap == 'counts' && k == o.overlapPage) ? lay[0].cntArr : L.cntArr
            b.position(L.ifd)
            if (big) b.putLong(3L) else b.putShort(3 as short)
            b.putShort(270 as short).putShort(2 as short)
            if (big) b.putLong(L.desc.length as long).putLong(descPtr as long) else b.putInt(L.desc.length).putInt(descPtr)
            b.putShort(273 as short).putShort((big ? 16 : 4) as short)
            if (big) b.putLong(3L).putLong(L.offArr as long) else b.putInt(3).putInt(L.offArr)
            b.putShort(279 as short).putShort(4 as short)
            if (big) b.putLong(3L).putLong(cntPtr as long) else b.putInt(3).putInt(cntPtr)
            long next = (k + 1 < lay.size()) ? lay[k + 1].ifd : 0L
            if (big) b.putLong(next) else b.putInt(next as int)
            b.position(L.descOff); b.put(L.desc)
            b.position(L.offArr)
            3.times { j ->
                long sp = (o.overlap == 'strip' && k == o.overlapPage && j == 0) ? lay[0].strip : (L.strip + 16 * j)
                if (big) b.putLong(sp) else b.putInt(sp as int)
            }
            b.position(L.cntArr); 3.times { b.putInt(16) }
            b.position(L.strip); b.put((String.valueOf(L.remove ? 'R' : 'K') * 48).bytes)
        }
        File f = new File(dir, "multi-${big}-${le}-${System.nanoTime()}.svs"); f.bytes = b.array()
        [file: f, lay: lay, big: big, le: le, cntSz: cntSz, ent: ent, ptr: ptr, orig: b.array().clone()]
    }
    static String KEEP0 = 'Aperio Image Library v12\n100x100|Patient=SECRETPAT|MPP=0.25|AppMag=40'
    static String KEEP = 'Aperio Image Library v12\n50x50 -> 25x25 - |MPP=0.25'
    static String LABEL = 'label 10x10\n|Comment=ORPHANTOKEN'
    static String MACRO = 'macro 10x10\n|Comment=ORPHANTOKEN'
    def ed(Map fx, Map cfgOver = [:]) {
        shell([f:fx.file, cfg:(cfg() + cfgOver), stems:[]]).evaluate(tiffCode() + "return editTiff(f,'aperio',cfg,stems)")
    }
    boolean zeroAt(byte[] bytes, int off, int len) { (off..<(off + len)).every { bytes[it] == 0 } }
    def chain(File f) {
        shell([f:f]).evaluate(tiffCode() + "def r = new RandomAccessFile(f,'r'); try { return parseTiff(r).ifds.collect { it.offset } } finally { r.close() }")
    }

    void testOrphanIfdTableDescriptionAndValuesAreZeroedForEveryLayout() {
        def layouts = [
            'label-macro-last' : [[desc:KEEP0], [desc:KEEP], [desc:LABEL, kind:'remove'], [desc:MACRO, kind:'remove']],
            'macro-before-label': [[desc:KEEP0], [desc:KEEP], [desc:MACRO, kind:'remove'], [desc:LABEL, kind:'remove']],
            'removed-not-last' : [[desc:KEEP0], [desc:LABEL, kind:'remove'], [desc:KEEP], [desc:MACRO, kind:'remove'], [desc:KEEP]],
            'removed-first-of-chain': [[desc:KEEP0], [desc:MACRO, kind:'remove'], [desc:LABEL, kind:'remove'], [desc:KEEP]]]
        layouts.each { name, pages -> [false,true].each { big -> [false,true].each { le ->
            def fx = build(pages, [big:big, le:le])
            def r = ed(fx)
            byte[] out = fx.file.bytes
            assert r.removed.size() == 2 : name
            assert r.ifdCountBefore == pages.size()
            fx.lay.each { L ->
                if (L.remove) {
                    assert zeroAt(out, L.ifd, fx.cntSz + 3 * fx.ent + fx.ptr) : "$name IFD table of removed page must be zero"
                    assert zeroAt(out, L.descOff, L.desc.length) : "$name description"
                    assert zeroAt(out, L.offArr, 3 * (big ? 8 : 4)) && zeroAt(out, L.cntArr, 12) && zeroAt(out, L.strip, 48)
                } else {
                    assert out[L.strip..(L.strip + 47)].every { it == (byte) 0x4b }
                }
            }
            assert !new String(out, StandardCharsets.ISO_8859_1).contains('ORPHANTOKEN') : "$name orphan bytes remain"
            assert chain(fx.file).size() == pages.count { it.kind != 'remove' } : name
            // production verification reads the ranges back and finds zeros
            assert shell([f:fx.file, z:r.zeroed]).evaluate(tiffCode() + 'return verifyZeroed(f, z)') == []
            assert !r.zeroed.isEmpty()
            // ... and reports a non-zero byte if one is planted
            out[fx.lay.find { it.remove }.descOff] = (byte) 65; fx.file.bytes = out
            assert shell([f:fx.file, z:r.zeroed]).evaluate(tiffCode() + 'return verifyZeroed(f, z)').any { it.contains('ImageDescription') || it.contains('etiket 270') }
        } } }
    }

    void testOverlapWithKeptPageRefusesBeforeAnythingIsZeroed() {
        ['strip','counts'].each { kind -> [false,true].each { big ->
            def fx = build([[desc:KEEP0], [desc:KEEP], [desc:LABEL, kind:'remove'], [desc:MACRO, kind:'remove']], [big:big, overlap:kind, overlapPage:2])
            def ex = shouldFail(java.io.IOException) { ed(fx) }
            assert ex.contains('çakış') : ex
            // through the production path the copy is refused and removed, the source is untouched
            byte[] before = fx.file.bytes
            def r = runCopy(fx.file, new AtomicBoolean(false), {File p -> hostCheck()})
            assert r.status == 'HATA' && r.detail.contains('çakış')
            assert !new File(dir,'case_1.svs').exists() && fx.file.bytes == before
            assert !dir.listFiles().any { it.name.startsWith('atolye-anon-') }
        } }
    }

    void testGt450StyleDescriptionsOnEveryPageRedactAllKeysAndKeepMppAppMag() {
        String block = '|AppMag = 40|MPP = 0.2631|Filename = FNAME99|Title = TTL99|Patient = PAT99|Case = CASE99|Barcode = BAR99|' +
            'Date = 11/10/25|Time = 10:11:12|Time Zone = GMT+03:00|User = USR99|ImageID = 1234567|ScanScope ID = SS4455|Rack = 7|Slide = 9|Left = 12.5|Top = 3.25'
        String d = 'Aperio Leica Biosystems GT450 v1.0.1\n100x100 [0,0 100x100] (256x256) JPEG/RGB Q=70' + block
        def fx = build([[desc:d], [desc:d + ' thumb'], [desc:d.replace('Q=70','Q=75')], [desc:'label 10x10' + block, kind:'remove'], [desc:'macro 10x10' + block, kind:'remove']])
        def r = ed(fx)
        assert r.keys.containsAll(['Filename','Title','Patient','Case','Barcode','Date','Time','Time Zone','User','ImageID','ScanScope ID','Rack','Slide'])
        String all = new String(fx.file.bytes, StandardCharsets.ISO_8859_1)
        ['FNAME99','TTL99','PAT99','CASE99','BAR99','11/10/25','10:11:12','GMT+03:00','USR99','1234567','SS4455'].each { assert !all.contains(it) : it }
        assert all.contains('MPP = 0.2631') && all.contains('AppMag = 40') && all.contains('Left = 12.5') && all.contains('Top = 3.25')
        assert all.contains('Rack = X|Slide = X') && all.contains('Time Zone = XXXXXXXXX')
        def ins = shell([f:fx.file]).evaluate(tiffCode() + "return inspectTiff(f,'aperio',[])")
        assert ins.ok && ins.phi.isEmpty() && ins.assocIfds.isEmpty() && ins.ifdCount == 3
    }

    // Accession pattern: scan of every ASCII value of every linked IFD
    List hits(String text, String pattern = null) {
        def fx = build([[desc:text]])
        shell([f:fx.file, p:pattern]).evaluate(tiffCode() + 'return accessionHits(f, compileAccession(p))')
    }
    void testAccessionPatternHitsAndFalsePositives() {
        assert hits('Aperio v12\nCase 7-25 here').collect { it.masked } == ['#-##']
        assert hits('ref 12345-1A end').collect { it.masked } == ['#####-#']
        assert hits('acc 1021-25').size() == 1
        assert hits('scanned 2026-09-28T10:00').isEmpty()                  // ISO date
        assert hits('stain PDL1-22C3 clone').isEmpty()                      // glued to letters
        assert hits('SS12345-01').isEmpty()
        assert hits('Aperio Image Library v12.0.15\n46000x32914 [0,0 46000x32814] (256x256) JPEG/RGB Q=70|AppMag = 40|MPP = 0.2527|Left = 25.69|Top = 23.45').isEmpty()
        def h = hits('x 7-25')
        assert h[0].page == 0 && h[0].tag == 270 && !h[0].toString().contains('7-25')    // masked, never the value
        assert hits('custom ZZ-77', 'ZZ-\\d+').size() == 1                  // configurable
        shouldFail(IllegalArgumentException) { hits('x', '(unclosed') }
    }
    void testFlaggedAccessionHitMakesUnitReviewNotClean() {
        def fx = build([[desc:KEEP0 + '|Comment=case 7-25'], [desc:KEEP], [desc:LABEL, kind:'remove'], [desc:MACRO, kind:'remove']])
        def r = runCopy(fx.file, new AtomicBoolean(false), {File p -> hostCheck()})
        assert r.status == 'İNCELE'
        assert r.detail.contains('IFD 0') && r.detail.contains('etiket 270') && r.detail.contains('#-##') && !r.detail.contains('7-25')
        assert new File(dir, 'case_1.svs').exists()
        // a clean fixture stays TAMAM
        def ok = build([[desc:KEEP0], [desc:KEEP], [desc:LABEL, kind:'remove'], [desc:MACRO, kind:'remove']])
        new File(dir,'case_1.svs').delete()
        assert runCopy(ok.file, new AtomicBoolean(false), {File p -> hostCheck()}).status == 'TAMAM'
    }

    // ── B: HMAC / key folder / crosswalk
    def hmac(byte[] salt, String msg) { shell([salt:salt, msg:msg]).evaluate(keyCode() + 'return hmacHex(salt, msg)') }
    void testHmacIsDeterministicAndSaltDependent() {
        byte[] s1 = new byte[32]; Arrays.fill(s1, (byte) 1)
        byte[] s2 = new byte[32]; Arrays.fill(s2, (byte) 2)
        String a = hmac(s1, 'slide-1.svs')
        assert a == hmac(s1, 'slide-1.svs') && a.length() == 64 && a ==~ /[0-9a-f]{64}/
        assert a != hmac(s2, 'slide-1.svs') && a != hmac(s1, 'slide-2.svs')
        def m = javax.crypto.Mac.getInstance('HmacSHA256'); m.init(new javax.crypto.spec.SecretKeySpec(s1, 'HmacSHA256'))
        assert a == m.doFinal('slide-1.svs'.bytes).encodeHex().toString()      // really HMAC-SHA256(key=salt, msg=name)
    }
    Map planFor(List names, Map cfgOver, File out) {
        def items = names.collect { n -> [file:new File(dir, n), name:n, uris:[]] }
        names.each { n -> File f = new File(dir, n); if (!f.exists()) f.text = 'x' }
        shell([items:items, out:out, classifyFile:{a,b -> 'aperio'}, KIND_LABEL:[:], cfg:cfgOver]).evaluate(keyCode() + "return buildPlan(cfg, items, out)")
    }
    void testHmacNamingIsReproducibleAcrossRunsAndOrders() {
        byte[] salt = new byte[32]; Arrays.fill(salt, (byte) 7)
        def names = ['a-1.svs','b-2.svs','c-3.svs','d-4.svs']
        File out = new File(dir, 'out'); out.mkdir()
        def ks = { byte[] sl -> [dir:dir, salt:sl, usedBases:[:], byOrig:[:]] }
        def p1 = planFor(names, [prefix:'case', naming:'hmac', keyState:ks(salt)], out)
        def p2 = planFor(names.reverse(), [prefix:'case', naming:'hmac', keyState:ks(salt)], out)
        def map = { p -> p.units.collectEntries { [(it.file.name): it.anon] } }
        assert map(p1) == map(p2)
        assert map(p1).values().every { it ==~ /case_[0-9a-f]{10}\.svs/ }
        byte[] other = new byte[32]; Arrays.fill(other, (byte) 8)
        assert map(planFor(names, [prefix:'case', naming:'hmac', keyState:ks(other)], out)) != map(p1)
        // no salt -> refusal; duplicate names -> refusal
        shouldFail(IllegalStateException) { planFor(names, [prefix:'case', naming:'hmac', keyState:ks(null)], out) }
    }
    void testHmacCollisionExtendsInsteadOfSuffixing() {
        byte[] salt = new byte[32]; Arrays.fill(salt, (byte) 9)
        String full = hmac(salt, 'x-1.svs')
        File out = new File(dir, 'out'); out.mkdir()
        def ks = [dir:dir, salt:salt, byOrig:[:], usedBases:[('case_' + full.substring(0, 10)):'another-original.svs']]
        def p = planFor(['x-1.svs'], [prefix:'case', naming:'hmac', keyState:ks], out)
        assert p.units[0].anon == 'case_' + full.substring(0, 12) + '.svs'
        ks.usedBases.put('case_' + full.substring(0, 12), 'third.svs')
        assert planFor(['x-1.svs'], [prefix:'case', naming:'hmac', keyState:ks], out).units[0].anon == 'case_' + full.substring(0, 16) + '.svs'
        // an existing destination file is skipped, never renamed with a silent suffix
        new File(out, 'case_' + full.substring(0, 16) + '.svs').text = 'previous'
        def q = planFor(['x-1.svs'], [prefix:'case', naming:'hmac', keyState:ks], out)
        assert !q.units[0].process && q.units[0].skipReason.contains('zaten var')
    }
    void testKeyFolderInsideOutputFolderIsRefusedAndSaltCreatedOnce() {
        File out = new File(dir, 'out'); out.mkdir()
        def check = { File k, File o -> shell([k:k, o:o]).evaluate(keyCode() + 'assertKeyDirSafe(k, o); return true') }
        shouldFail(IllegalArgumentException) { check(new File(out, 'keys'), out) }
        shouldFail(IllegalArgumentException) { check(out, out) }
        shouldFail(IllegalArgumentException) { check(dir, out) }                // output inside key folder
        shouldFail(IllegalArgumentException) { check(new File(out, 'a/../keys'), out) }
        assert check(new File(dir, 'keys'), out)
        assert check(new File(dir, 'out-keys'), out)                           // sibling with shared name prefix is fine
        def logs = []
        def prep = { String naming -> shell([cfg:[keyDir:new File(dir,'keys').path, naming:naming], out:out, logs:logs, atolyeDataRoot:{ -> dir }]).evaluate(
            keyCode() + 'return prepareKeyState(cfg, out, { s -> logs << s })') }
        assert !prep('sequential').saltCreated && !new File(dir,'keys/salt.bin').exists()      // not needed -> not created
        def k1 = prep('hmac')
        assert k1.saltCreated && new File(dir,'keys/salt.bin').length() == 32
        assert logs.size() == 1 && logs[0].contains('YEDEKLEYİN') && logs[0].contains('salt.bin')
        byte[] first = new File(dir,'keys/salt.bin').bytes
        def k2 = prep('hmac')
        assert !k2.saltCreated && Arrays.equals(k2.salt, first) && logs.size() == 1
        new File(dir,'keys/salt.bin').bytes = new byte[5]
        shouldFail(IllegalStateException) { prep('hmac') }
        assert new File(dir,'keys/salt.bin').length() == 5                      // never overwritten
        // the key folder defaults to <dataRoot>/anonim-anahtar
        assert shell([cfg:[:], atolyeDataRoot:{ -> dir }]).evaluate(keyCode() + 'return keyDirOf(cfg)') == new File(dir, 'anonim-anahtar')
    }
    void testCrosswalkIsAppendOnlyAndSecondRunReusesPseudonyms() {
        File keys = new File(dir, 'keys'); keys.mkdir()
        File out = new File(dir, 'out'); out.mkdir()
        def append = { List rows -> shell([k:keys, rows:rows]).evaluate(keyCode() + 'appendCrosswalk(k, rows)') }
        def row = { String n, String ps, String st -> [origName:n, origPath:'/data/' + n, pseudonym:ps, mode:'sequential', ts:'20261027_101010', status:st, fpBefore:'size=1', fpAfter:'size=1'] }
        append([row('s-1,odd name.svs', 'case_1', 'BASLADI'), row('s-1,odd name.svs', 'case_1', 'TAMAM')])
        byte[] after1 = new File(keys, 'anahtar.csv').bytes
        append([row('s-2.svs', 'case_2', 'TAMAM')])
        byte[] after2 = new File(keys, 'anahtar.csv').bytes
        assert after2.length > after1.length && Arrays.equals(after2[0..<after1.length] as byte[], after1)   // existing rows untouched
        assert new String(after2, StandardCharsets.UTF_8).readLines().size() == 4                       // one header only
        def cw = shell([k:keys]).evaluate(keyCode() + 'return loadCrosswalk(k)')
        assert cw.byOrig['s-1,odd name.svs'].pseudonym == 'case_1' && cw.usedBases.keySet() == ['case_1','case_2'] as Set
        // second run: s-1 reuses case_1, new slide s-3 gets the lowest FREE number (case_3), never case_1/2
        def ks = [dir:keys, salt:null, byOrig:cw.byOrig, usedBases:cw.usedBases]
        def p = planFor(['s-1,odd name.svs', 's-3.svs'], [prefix:'case', naming:'sequential', keyState:ks], out)
        def m = p.units.collectEntries { [(it.file.name): it.anon] }
        assert m['s-1,odd name.svs'] == 'case_1.svs' && m['s-3.svs'] == 'case_3.svs'
        assert p.units.find { it.file.name == 's-1,odd name.svs' }.reused
        // contradictory rows are an error, not silently repaired
        append([row('s-2.svs', 'case_9', 'TAMAM')])
        shouldFail(IllegalStateException) { shell([k:keys]).evaluate(keyCode() + 'return loadCrosswalk(k)') }
    }
    void testHmacCrosswalkMismatchIsAnErrorAndHmacRowsAreReused() {
        byte[] salt = new byte[32]; Arrays.fill(salt, (byte) 3)
        File out = new File(dir, 'out'); out.mkdir()
        String full = hmac(salt, 'h-1.svs')
        def good = [dir:dir, salt:salt, byOrig:['h-1.svs':[pseudonym:'case_' + full.substring(0,12), mode:'hmac']], usedBases:[:]]
        assert planFor(['h-1.svs'], [prefix:'case', naming:'hmac', keyState:good], out).units[0].anon == 'case_' + full.substring(0,12) + '.svs'
        def bad = [dir:dir, salt:salt, byOrig:['h-1.svs':[pseudonym:'case_0123456789', mode:'hmac']], usedBases:[:]]
        def ex = shouldFail(IllegalStateException) { planFor(['h-1.svs'], [prefix:'case', naming:'hmac', keyState:bad], out) }
        assert ex.contains('HMAC')
    }
    void testSequentialNumberingDoesNotFollowInputOrderAndPlanIsInPseudonymOrder() {
        byte[] salt = new byte[32]; Arrays.fill(salt, (byte) 5)
        File out = new File(dir, 'out'); out.mkdir()
        def names = (1..12).collect { String.format(Locale.US, '%d-25.svs', 1000 + it) }       // accession order
        def p = planFor(names, [prefix:'case', naming:'sequential', keyState:[dir:dir, salt:salt, byOrig:[:], usedBases:[:]]], out)
        def num = names.collect { n -> (p.units.find { it.file.name == n }.anon =~ /case_(\d+)/)[0][1] as int }
        assert num.sort(false) == (1..12).toList() && num != (1..12).toList() && num != (1..12).toList().reverse()
        assert p.units.collect { it.anon } == (1..12).collect { "case_${it}.svs" as String }       // plan (report/write) order = pseudonym order
        // salted order is deterministic; without a salt the order is shuffled (still a permutation of 1..n)
        def p2 = planFor(names.reverse(), [prefix:'case', naming:'sequential', keyState:[dir:dir, salt:salt, byOrig:[:], usedBases:[:]]], out)
        assert p2.units.collectEntries { [(it.file.name): it.anon] } == p.units.collectEntries { [(it.file.name): it.anon] }
        def p3 = planFor(names, [prefix:'case', naming:'sequential'], out)
        assert p3.units.collect { it.anon } == (1..12).collect { "case_${it}.svs" as String }
    }

    // ── A2: fingerprints
    void testFingerprintDetectsChangeAndOriginalMutationBecomesLoudError() {
        File f = fixture(); def fp = { shell([f:f]).evaluate(tiffCode() + 'return fingerprintOf(f)') }
        def a = fp(); def a2 = fp()
        def eq = { x, y -> shell([x:x, y:y]).evaluate(tiffCode() + 'return fpEqual(x, y)') }
        assert eq(a, a2) && a.head.length() == 64 && a.size == f.length()
        long m = f.lastModified(); f.setLastModified(m + 5000)
        assert !eq(a, fp()); f.setLastModified(m); assert eq(a, fp())
        byte[] bytes = f.bytes; bytes[bytes.length - 1] ^= 1; f.bytes = bytes; f.setLastModified(m)
        assert !eq(a, fp())                                                  // tail differs
        // mutation of the source while a unit runs -> HATA, copy deleted, loud message
        File g = fixture(false, false); int calls = 0
        def r = runCopy(g, new AtomicBoolean(false), {File p -> if (++calls == 2) { g.setLastModified(g.lastModified() + 3000) }; hostCheck()})
        assert r.status == 'HATA' && r.detail.contains('ORİJİNAL DOSYA DEĞİŞMİŞ')
        assert !new File(dir, 'case_1.svs').exists()
    }

    // ── A3: timestamps
    void testPinnedTimestampsOfShareableOutputs() {
        File f = fixture()
        def r = shell([f:f]).evaluate(tiffCode() + 'return pinTimes(f)')
        long expected = java.time.Instant.parse('2000-01-01T00:00:00Z').toEpochMilli()
        assert r.mtime && f.lastModified() == expected
        if (System.getProperty('os.name').toLowerCase().contains('win')) assert r.creation    // NTFS honours creation time
        def at = Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes)
        assert at.lastAccessTime().toMillis() == expected
        // a successful unit comes out pinned
        File g = fixture(true, false)
        assert runCopy(g, new AtomicBoolean(false), {File p -> hostCheck()}).status == 'TAMAM'
        assert new File(dir, 'case_1.svs').lastModified() == expected
    }

    // ── C: edge band
    BufferedImage img(int w, int h, Closure rgb) {
        def im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) im.setRGB(x, y, rgb(x, y) as int)
        im
    }
    void testEdgeBandScoreFlagsDarkSideBandOnly() {
        def score = { BufferedImage im -> shell([im:im]).evaluate(imports + constants() + code('lum','edgeBandScore') + '\nreturn edgeBandScore(im)') }
        def pale = 0xE8D8E0, dark = 0x202020
        assert !score(img(1024, 700) { x, y -> pale }).flagged
        def left = score(img(1024, 700) { x, y -> x < 160 ? dark : pale })
        assert left.flagged && left.left > 0.99 && left.right < 0.01
        def right = score(img(1024, 700) { x, y -> x >= 1024 - 140 ? dark : pale })
        assert right.flagged && right.right > 0.99
        assert !score(img(1024, 700) { x, y -> (x < 160 && y < 300) ? dark : pale }).flagged      // dark on only ~43% of rows
        assert !score(img(1024, 700) { x, y -> (x >= 400 && x < 700) ? dark : pale }).flagged        // dark tissue in the middle
        assert !score(img(1024, 700) { x, y -> x < 20 ? dark : pale }).flagged                       // thin edge only
    }

    // ── D + integration through runApply (host QuPath checks injected)
    String sha(File f) { shell([f:f]).evaluate(tiffCode() + code('sha256Of') + '\nreturn sha256Of(f)') }
    void testRunApplyKeepsKeysOutOfOutputWritesChecksumsAndReadmeAndPinsTimes() {
        File src = new File(dir, 'src'); src.mkdir()
        def srcs = ['1021-25;A.svs','1022-25;B.svs','1023-25;C.svs'].collect { n ->
            def fx = build([[desc:KEEP0], [desc:KEEP], [desc:LABEL, kind:'remove'], [desc:MACRO, kind:'remove']], [:])
            File t = new File(src, n); Files.move(fx.file.toPath(), t.toPath()); t }
        def before = srcs.collect { it.bytes }
        File out = new File(dir, 'out'); out.mkdir()
        new File(out, 'desktop.ini').text = '[x]'; new File(out, '.tmp.driveupload').mkdir()
        File keys = new File(dir, 'keys')
        int scans = 0
        def items = srcs.collect { f -> [file:f, name:f.name, uris:[], id:'id-' + f.name, entry:null, plain:false, builder:null] }
        def vars = [items:items, out:out, keys:keys, logs:[],
            classifyFile:{a,b -> 'aperio'}, KIND_LABEL:[aperio:'Aperio SVS'],
            qupathCheck:{File p -> hostCheck()},
            mainScanCheck:{File p, boolean k -> scans++; [ok:true, left:(scans == 1 ? 0.9d : 0.0d), right:0d, flagged:(scans == 1)]},
            atolyeDataRoot:{ -> dir}]
        String script = applyCode() + '''
def cfg = [prefix:'case', naming:'hmac', keyDir:keys.path, reversible:true, redactMeta:true, removeLabels:true, includePartial:false, makeProject:false, transferObjects:false]
cfg.keyState = prepareKeyState(cfg, out, { s -> logs << s })
def plan = buildPlan(cfg, items, out)
def res = runApply(plan, { s -> logs << s }, new AtomicBoolean(false))
return [plan:plan, res:res]
'''
        def r = shell(vars).evaluate(script)
        // sources untouched
        srcs.eachWithIndex { f, i -> assert f.bytes == before[i] }
        assert r.plan.units.every { it.status == 'TAMAM' || it.status == 'İNCELE' } : r.plan.units*.detail
        // keys live outside the output folder
        assert new File(keys, 'salt.bin').length() == 32 && new File(keys, 'anahtar.csv').isFile()
        assert out.list().toList().every { !(it.startsWith('anonymization_') || it == 'anahtar.csv' || it == 'salt.bin') }
        assert keys.list().toList().any { it.startsWith('anonymization_lookup_') && it.endsWith('.csv') }
        String cwText = new File(keys, 'anahtar.csv').getText('UTF-8')
        assert cwText.contains('1021-25;A.svs') && cwText.contains('BASLADI') && cwText.contains('TAMAM')
        assert r.res.saltCreated && r.res.text.contains('YEDEKLEYİN')
        // package files
        File sums = new File(out, 'SHA256SUMS.txt'), readme = new File(out, 'BENIOKU.md')
        assert sums.isFile() && readme.isFile()
        byte[] sb = sums.bytes
        assert !sb.any { it == (byte) 13 } && sb[-1] == (byte) 10                      // LF only
        def lines = new String(sb, StandardCharsets.UTF_8).readLines()
        def slides = out.listFiles().findAll { it.name.endsWith('.svs') }
        assert slides.size() == 3 && lines.size() == 3 + 2                               // slides + report + BENIOKU
        lines.each { ln ->
            def m = (ln =~ /^([0-9a-f]{64}) {2}(\S+)$/); assert m.find() : ln
            assert sha(new File(out, m.group(2))) == m.group(1)                                // what `sha256sum -c` would do
        }
        assert !lines.any { it.contains('SHA256SUMS') }                                  // not self-referential
        // timestamps pinned for every shareable file
        long expected = java.time.Instant.parse('2000-01-01T00:00:00Z').toEpochMilli()
        (slides + [sums, readme] + out.listFiles().findAll { it.name.startsWith('anonimlestirme_raporu_') }).each { assert it.lastModified() == expected : it.name }
        // README + report: pseudonym of the edge-flagged slide, no original names, no accession-looking dates
        String flagged = r.plan.units.find { it.edge?.flagged }.anon
        String rd = readme.getText('UTF-8'), rep = out.listFiles().find { it.name.startsWith('anonimlestirme_raporu_') }.getText('UTF-8')
        assert rd.contains(flagged) && rep.contains(flagged) && rd.contains('Seviye V') && rd.contains('MPP') && rd.contains('sha256sum -c') && rd.contains('Get-FileHash')
        assert rd.contains('English summary') && rd.contains('Seviye I') && rd.contains('Seviye III') && rd.contains('Seviye IV')
        srcs.each { assert !rd.contains(it.name) && !rep.contains(it.name) && !rep.contains(it.name.replace('.svs','')) }
        assert !(rd =~ /(?<![0-9])\d{4}-\d{2}-\d{2}/).find() && rd.contains(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
        assert r.res.text.contains('desktop.ini') && r.res.text.contains('.tmp.driveupload/')      // D3 warning, files kept
        assert new File(out, 'desktop.ini').exists() && new File(out, '.tmp.driveupload').isDirectory()
        assert !rep.contains('desktop.ini')                                                        // not in the shareable report
        // second run into another folder: same originals -> identical hmac pseudonyms; crosswalk grew, never shrank
        long len1 = new File(keys, 'anahtar.csv').length()
        def names1 = r.plan.units.collectEntries { [(it.file.name): it.anon] }
        File out2 = new File(dir, 'out2'); out2.mkdir()
        def vars2 = vars + [out:out2, items:srcs.collect { f -> [file:f, name:f.name, uris:[], id:'x', entry:null, plain:false, builder:null] }]
        def r2 = shell(vars2).evaluate(script)
        assert r2.plan.units.collectEntries { [(it.file.name): it.anon] } == names1
        assert new File(keys, 'anahtar.csv').length() > len1 && !r2.res.saltCreated
        srcs.eachWithIndex { f, i -> assert f.bytes == before[i] }
    }

    void testIrreversibleModeStoresNoOriginalNames() {
        File src = new File(dir, 'src'); src.mkdir()
        def fx = build([[desc:KEEP0], [desc:KEEP], [desc:LABEL, kind:'remove'], [desc:MACRO, kind:'remove']], [:])
        File t = new File(src, 'PRIVATE-NAME-77.svs'); Files.move(fx.file.toPath(), t.toPath())
        File out = new File(dir, 'out'); out.mkdir(); File keys = new File(dir, 'keys')
        def vars = [items:[[file:t, name:t.name, uris:[], id:'i', entry:null, plain:false, builder:null]], out:out, keys:keys, logs:[],
            classifyFile:{a,b -> 'aperio'}, KIND_LABEL:[aperio:'Aperio SVS'], qupathCheck:{File p -> hostCheck()},
            mainScanCheck:{File p, boolean k -> [ok:true,left:0d,right:0d,flagged:false]}, atolyeDataRoot:{ -> dir}]
        String script = applyCode() + '''
def cfg = [prefix:'case', naming:'hmac', keyDir:keys.path, reversible:false, redactMeta:true, removeLabels:true]
cfg.keyState = prepareKeyState(cfg, out, { s -> })
def plan = buildPlan(cfg, items, out)
return runApply(plan, { s -> }, new AtomicBoolean(false))
'''
        def r = shell(vars).evaluate(script)
        keys.listFiles().each { f ->
            String txt = f.name.endsWith('.bin') ? '' : f.getText('UTF-8')
            assert !txt.contains('PRIVATE-NAME-77') && !txt.contains('size=') && !txt.contains(t.parent.replace('\\', '/')) : f.name
        }
        assert keys.list().toList().any { it.startsWith('anonymization_manifest_') }
        assert !keys.list().toList().any { it.startsWith('anonymization_lookup_') }
    }

    // ── review follow-ups
    void testIrreversibleHmacRunsKeepTheSamePseudonymsAcrossManyBatches() {
        byte[] salt = new byte[32]; Arrays.fill(salt, (byte) 11)
        File keys = new File(dir, 'keys'); keys.mkdir()
        def names = ['p-1.svs','p-2.svs','p-3.svs']
        Map first = null
        (1..4).each { run ->
            File out = new File(dir, 'out' + run); out.mkdir()
            def cw = shell([k:keys]).evaluate(keyCode() + 'return loadCrosswalk(k)')
            def ks = [dir:keys, salt:salt, byOrig:cw.byOrig, usedBases:cw.usedBases]
            def p = planFor(names, [prefix:'case', naming:'hmac', reversible:false, keyState:ks], out)   // must not throw
            def m = p.units.collectEntries { [(it.file.name): it.anon] }
            if (first == null) first = m
            assert m == first : "run $run changed pseudonyms"
            assert m.values().every { it ==~ /case_[0-9a-f]{10}\.svs/ }
            // irreversible rows: pseudonym only, no original name
            shell([k:keys, rows:p.units.collect { [origName:'', origPath:'', pseudonym:it.base, mode:'hmac', ts:'20261027_101010', status:'TAMAM', fpBefore:'', fpAfter:'']}]).evaluate(
                keyCode() + 'appendCrosswalk(k, rows)')
        }
        assert new File(keys, 'anahtar.csv').text.readLines().size() == 1 + 4 * 3
    }
    void testReversibleRunRefusesDuplicateOriginalNames() {
        File a = new File(dir, 'd1'); a.mkdir(); File b = new File(dir, 'd2'); b.mkdir()
        [a, b].each { new File(it, 'same.svs').text = 'x' }
        File out = new File(dir, 'out'); out.mkdir()
        def items = [a, b].collect { d -> [file:new File(d, 'same.svs'), name:'same.svs', uris:[]] }
        ['sequential','uuid'].each { naming ->
            shouldFail(IllegalArgumentException) {
                shell([items:items, out:out, classifyFile:{x,y -> 'aperio'}, KIND_LABEL:[:],
                       cfg:[prefix:'case', naming:naming, reversible:true, keyState:[dir:dir, salt:null, byOrig:[:], usedBases:[:]]]]).evaluate(keyCode() + 'return buildPlan(cfg, items, out)')
            }
        }
        // irreversible mode stores no names, so the same situation is allowed
        def p = shell([items:items, out:out, classifyFile:{x,y -> 'aperio'}, KIND_LABEL:[:],
                       cfg:[prefix:'case', naming:'sequential', reversible:false, keyState:[dir:dir, salt:null, byOrig:[:], usedBases:[:]]]]).evaluate(keyCode() + 'return buildPlan(cfg, items, out)')
        assert p.units.size() == 2
    }
    void testReadmeStatesOnlyTheLevelsActuallyReached() {
        def units = [[status:'TAMAM', anon:'case_a.svs', kind:'aperio'], [status:'KISMİ', anon:'case_b.tif', kind:'tiff']]
        def render = { Map cfg -> shell([plan:[cfg:cfg + [naming:'hmac', prefix:'case'], units:units]]).evaluate(
            imports + constants() + code('edgeFlagged','reviewUnits','buildBenioku') + "\nreturn buildBenioku(plan, '20261027_101010', 'rapor.txt', 'SHA256SUMS.txt', 'BENIOKU.md', 2)") }
        String full = render([removeLabels:true, redactMeta:true])
        assert full.contains('**Seviye III** (1 Aperio') && full.contains('**Seviye IV** (1 Aperio') && full.contains('Levels reached: I + III + IV')
        assert full.contains('case_b.tif') && full.contains('KISMİ')                          // partial file listed, not claimed as fully de-identified
        String noLabel = render([removeLabels:false, redactMeta:true])
        assert noLabel.contains('**Seviye III** — UYGULANMADI') && noLabel.contains('Levels reached: I + IV')
        String none = render([removeLabels:false, redactMeta:false])
        assert none.contains('**Seviye III** — UYGULANMADI') && none.contains('**Seviye IV** — UYGULANMADI') && none.contains('Levels reached: I (1 file(s)')
    }
    void testReportIsPinnedEvenWhenNoPackageIsWritten() {
        File src = new File(dir, 'src'); src.mkdir()
        def fx = build([[desc:KEEP0], [desc:KEEP], [desc:LABEL, kind:'remove'], [desc:MACRO, kind:'remove']], [:])
        File t = new File(src, 'x-1.svs'); Files.move(fx.file.toPath(), t.toPath())
        File out = new File(dir, 'out'); out.mkdir(); File keys = new File(dir, 'keys')
        int calls = 0
        def vars = [items:[[file:t, name:t.name, uris:[], id:'i', entry:null, plain:false, builder:null]], out:out, keys:keys,
            classifyFile:{a,b -> 'aperio'}, KIND_LABEL:[aperio:'Aperio SVS'],
            qupathCheck:{File p -> (++calls % 2 == 1) ? hostCheck() : [ok:false, error:'fixture']},          // verification always fails -> HATA
            mainScanCheck:{File p, boolean k -> [ok:true,left:0d,right:0d,flagged:false]}, atolyeDataRoot:{ -> dir}]
        def r = shell(vars).evaluate(applyCode() + '''
def cfg = [prefix:'case', naming:'sequential', keyDir:keys.path, reversible:true, redactMeta:true, removeLabels:true]
cfg.keyState = prepareKeyState(cfg, out, { s -> })
def plan = buildPlan(cfg, items, out)
return [plan:plan, res:runApply(plan, { s -> }, new AtomicBoolean(false))]
''')
        assert r.plan.units[0].status == 'HATA'
        assert !new File(out, 'SHA256SUMS.txt').exists() && !new File(out, 'BENIOKU.md').exists() && r.res.pkg == null
        def rep = out.listFiles().find { it.name.startsWith('anonimlestirme_raporu_') }
        assert rep != null && rep.lastModified() == java.time.Instant.parse('2000-01-01T00:00:00Z').toEpochMilli()
        assert out.listFiles().every { !it.name.endsWith('.svs') }
    }

    // ── independent-review follow-ups
    void testNonAsciiResidualValueIsFoundByTheByteScan() {
        // UTF-8 bytes of a Turkish value, as they sit in the file (ISO-8859-1 view of the bytes)
        String v = new String('Şükrü Yılmaz'.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)
        def fx = build([[desc:KEEP0.replace('Patient=SECRETPAT', 'Patient=' + v)], [desc:KEEP + '|Note=' + v],
                        [desc:LABEL, kind:'remove'], [desc:MACRO, kind:'remove']])
        byte[] before = fx.file.bytes
        def r = runCopy(fx.file, new AtomicBoolean(false), {File p -> hostCheck()})
        assert r.status == 'HATA' && r.detail.contains('orijinal değer geçiyor') : r.detail     // surviving copy of the value is detected
        assert !new File(dir, 'case_1.svs').exists() && fx.file.bytes == before
        // without the surviving copy the same unit is clean
        def ok = build([[desc:KEEP0.replace('Patient=SECRETPAT', 'Patient=' + v)], [desc:KEEP], [desc:LABEL, kind:'remove'], [desc:MACRO, kind:'remove']])
        assert runCopy(ok.file, new AtomicBoolean(false), {File p -> hostCheck()}).status == 'TAMAM'
    }
    void testCatastrophicUserRegexIsBoundedAndNeverClean() {
        String bomb = '(a*)*\\1b'      // backreference defeats the JDK's memoisation, so this really is catastrophic
        String desc = KEEP0 + '|Comment=' + ('a' * 45) + '!'
        def fx = build([[desc:desc], [desc:KEEP], [desc:LABEL, kind:'remove'], [desc:MACRO, kind:'remove']])
        long t0 = System.currentTimeMillis()
        def msg = shouldFail(IllegalStateException) {
            shell([f:fx.file, p:bomb]).evaluate(tiffCode() + "return accessionHits(f, compileAccession(p), [budgetMs: 200L])")
        }
        assert msg.contains('ZAMANASIMI') && System.currentTimeMillis() - t0 < 20000
        def r = runCopy(fx.file, new AtomicBoolean(false), {File p -> hostCheck()}, [cfg: cfg() + [accPattern:bomb, accBudgetMs:200L]])
        assert r.status == 'İNCELE' && r.detail.contains('ZAMAN AŞIMI') : r.status + r.detail
        // a cancel request interrupts the scan instead of hanging
        def cancel = new AtomicBoolean(true)
        assert shouldFail(IllegalStateException) {
            shell([f:fx.file, p:bomb, c:cancel]).evaluate(tiffCode() + "return accessionHits(f, compileAccession(p), [cancel: c, budgetMs: 60000L])")
        }.contains('IPTAL')
    }
    void testDefaultPatternCoverageIsStatedHonestlyAndSharedReportHasNoAbsolutePaths() {
        File src = new File(dir, 'src'); src.mkdir()
        def fx = build([[desc:KEEP0], [desc:KEEP], [desc:LABEL, kind:'remove'], [desc:MACRO, kind:'remove']])
        File t = new File(src, 'x-1.svs'); Files.move(fx.file.toPath(), t.toPath())
        File out = new File(dir, 'out-unique-name'); out.mkdir(); File keys = new File(dir, 'keys')
        def vars = [items:[[file:t, name:t.name, uris:[], id:'i', entry:null, plain:false, builder:null]], out:out, keys:keys,
            classifyFile:{a,b -> 'aperio'}, KIND_LABEL:[aperio:'Aperio SVS'], qupathCheck:{File p -> hostCheck()},
            mainScanCheck:{File p, boolean k -> [ok:true,left:0d,right:0d,flagged:false]}, atolyeDataRoot:{ -> dir}]
        def r = shell(vars).evaluate(applyCode() + '''
def cfg = [prefix:'case', naming:'sequential', keyDir:keys.path, reversible:true, redactMeta:true, removeLabels:true]
cfg.keyState = prepareKeyState(cfg, out, { s -> })
def plan = buildPlan(cfg, items, out)
return runApply(plan, { s -> }, new AtomicBoolean(false))
''')
        String rep = out.listFiles().find { it.name.startsWith('anonimlestirme_raporu_') }.getText('UTF-8')
        String rd = new File(out, 'BENIOKU.md').getText('UTF-8')
        [rep, rd].each { assert it.contains('YAKALAMAZ') && it.contains('S25-12345') }
        assert !rep.contains('bulunmadı.\n') || rep.contains('yalnız aranan desen')
        assert rep.contains('Çıktı klasörü : out-unique-name') && !rep.contains(out.absolutePath) && !rep.contains(dir.absolutePath)
        assert r.text.contains(out.absolutePath)                       // the private summary keeps full paths
    }
    void testAuditPngFolderCannotBeInsideOutputOrKeyFolder() {
        File out = new File(dir, 'out'); File keys = new File(dir, 'keys'); out.mkdir(); keys.mkdir()
        def chk = { File s -> shell([s:s, forb:[out, keys]]).evaluate(keyCode() + 'assertSaveDirSafe(s, forb); return true') }
        shouldFail(IllegalArgumentException) { chk(new File(out, 'png')) }
        shouldFail(IllegalArgumentException) { chk(keys) }
        shouldFail(IllegalArgumentException) { chk(dir) }               // contains both
        assert chk(new File(dir, 'png')) && chk(null)
    }
    void testKeyFolderCheckSeesCaseVariantsAndLinks() {
        File out = new File(dir, 'out'); out.mkdir()
        def check = { File k, File o -> shell([k:k, o:o]).evaluate(keyCode() + 'assertKeyDirSafe(k, o); return true') }
        String os = System.getProperty('os.name').toLowerCase()
        if (os.contains('win') || os.contains('mac'))
            shouldFail(IllegalArgumentException) { check(new File(out.path.toUpperCase() + File.separator + 'Keys'), out) }
        // symbolic link (or, on Windows without privilege, a junction) pointing at the output folder
        File link = new File(dir, 'linkdir')
        boolean made = false
        try { Files.createSymbolicLink(link.toPath(), out.toPath()); made = true } catch (Throwable ignore) {}
        if (!made && os.contains('win')) {
            try { made = new ProcessBuilder('cmd', '/c', 'mklink', '/J', link.path, out.path).redirectErrorStream(true).start().waitFor() == 0 && link.exists() } catch (Throwable ignore) {}
        }
        if (made) {
            shouldFail(IllegalArgumentException) { check(new File(link, 'keys'), out) }
            shouldFail(IllegalArgumentException) { check(out, link) }
            if (os.contains('win') && !Files.isSymbolicLink(link.toPath())) new ProcessBuilder('cmd', '/c', 'rmdir', link.path).start().waitFor()
        } else println 'link/junction creation not possible here - link part skipped'
    }
}
