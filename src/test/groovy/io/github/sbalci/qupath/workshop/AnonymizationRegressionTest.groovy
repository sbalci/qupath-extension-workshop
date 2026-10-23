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
    void setUp() { dir = Files.createTempDirectory('anon-regression-').toFile() }
    void tearDown() { dir.deleteDir() }
    static String code(String... names) { names.collect { SafetyRegressionTest.closure(SCRIPT, it) }.join('\n') }
    static String imports = '''import java.nio.*
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.image.BufferedImage
'''
    static String constants() {
        String s = SafetyRegressionTest.source(SCRIPT)
        s.substring(s.indexOf('def PHI_EXACT'), s.indexOf('// ── Kalıcı yapılandırma'))
    }
    static String tiffCode() { imports + constants() + code('rdBuf','u16','u32','parseTiff','readAscii','writeAscii',
        'readUInts','writePtr','assocKind','normKey','isPhiKey','isBlankValue','redactAperioDesc','blankAscii',
        'stemReplaceable','editTiff','inspectTiff','scanForProbes','latin1Form','isLabelOrMacro','extOf','fmtBytes','moveWithRetry') }
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
    static String processCode() { tiffCode() + code('processUnit') }
    Map runCopy(File f, AtomicBoolean cancel, Closure check, Map more = [:]) {
        def vars = [u:[file:f,kind:'aperio',anon:'case_1.svs'],cfg:cfg(),outDir:dir,
            log:{String s ->},cancel:cancel,qupathCheck:check,stemsOf:{u -> []},KIND_LABEL:[aperio:'Aperio']] + more
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
            assert !new File(dir,'case_1.svs').exists()
            assert dir.listFiles().toList() == [f]
            assert f.bytes == before
        }
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
}
