// Appended to the candidate's unmodified declarations (before CLI dispatch).
// qupathCheck, mainScanCheck, classifyFile, runApply and all file operations are
// real packaged closures: no replacement image-server or success-result stubs.
assert gui == null : 'Run in the isolated QuPath CLI, not a live GUI session'
def readPixels = { File f ->
    def server = ImageServers.buildServer(f.toURI())
    try {
        def image = server.readRegion(qupath.lib.regions.RegionRequest.createInstance(server.getPath(), 1d, 0, 0, 64, 48))
        assert image.width == 64 && image.height == 48
        return [server: server.getServerType(), width: server.width, height: server.height,
                px: server.pixelCalibration.averagedPixelSizeMicrons,
                assoc: new ArrayList(server.associatedImageList),
                pixels: image.getRGB(0, 0, 64, 48, null, 0, 64).toList()]
    } finally { server.close() }
}
File hostRoot = new File(acceptanceRoot, 'host'); assert hostRoot.mkdir()
File source = new File(acceptanceRoot, 'source.svs')
def original = readPixels(source)
assert original.width == 64 && original.height == 48 && Math.abs(original.px - 0.25d) < 1e-9
assert original.assoc.any { isLabelOrMacro(it) && it.toLowerCase().contains('label') }
assert original.assoc.any { isLabelOrMacro(it) && it.toLowerCase().contains('macro') }
def sourceDigest = sha256Of(source)
File out = new File(hostRoot, 'out'); assert out.mkdir()
File keys = new File(hostRoot, 'keys')
def cfg = [prefix:'case', naming:'hmac', keyDir:keys.path, reversible:true,
           redactMeta:true, removeLabels:true, mainScan:true, makeProject:false]
cfg.keyState = prepareKeyState(cfg, out, {s -> println s})
def items = [[file:source, name:source.name, uris:[], id:'synthetic', entry:null, plain:false, builder:null]]
def plan = buildPlan(cfg, items, out)
assert plan.units.size() == 1 && plan.units[0].kind == 'aperio'
def result = runApply(plan, {s -> println s}, new AtomicBoolean(false))
assert plan.units[0].status == 'TAMAM' : plan.units[0]
assert result.pkg != null
File output = new File(out, plan.units[0].anon)
def cleaned = readPixels(output)
assert cleaned.pixels == original.pixels && cleaned.px == original.px
assert !cleaned.assoc.any { isLabelOrMacro(it) }
assert sha256Of(source) == sourceDigest
assert new File(keys, 'salt.bin').isFile() && new File(keys, 'anahtar.csv').isFile()
assert keys.listFiles().any { it.name.endsWith('.json') }
assert !out.listFiles().any { it.name.endsWith('.csv') || it.name.endsWith('.json') || it.name == 'salt.bin' }
boolean rejected = false
try { assertKeyDirSafe(new File(out, 'keys'), out) } catch (IllegalArgumentException expected) { rejected = true }
assert rejected
// Deterministic mutation after the copy, before candidate edit/verification.
// Alter only a disposable synthetic source; the host checks remain unmodified.
File mutant = new File(hostRoot, 'mutation.svs'); mutant.bytes = source.bytes
File mutationOut = new File(hostRoot, 'mutation-out'); assert mutationOut.mkdir()
boolean injected = false
def unit = [file:mutant, kind:'aperio', anon:'case_mutation.svs', items:[]]
def logMutation = { String s ->
    if (!injected && s.contains('üst veri / etiket düzenleniyor')) {
        new RandomAccessFile(mutant, 'rw').withCloseable { raf ->
            // Metadata-independent byte mutation is still required to fail fingerprinting.
            raf.seek(raf.length() - 1); int b = raf.read(); raf.seek(raf.length() - 1); raf.write(b ^ 1)
        }
        injected = true
    }
}
def failed = processUnit(unit, cfg, mutationOut, logMutation, new AtomicBoolean(false))
assert injected && failed.status == 'HATA' && unit.sourceChanged : failed
assert mutationOut.listFiles().length == 0
new File(acceptanceRoot, 'host-results.json').text = qupath.lib.io.GsonTools.getInstance(true).toJson([
    status:'passed', fixture:'synthetic-only', gui_acceptance:'NOT RUN', real_slide_acceptance:'NOT RUN',
    qupath_version:qupath.lib.gui.BuildInfo.getInstance().getVersion().toString(),
    java_version:System.getProperty('java.version'),
    server:original.server, calibration:original.px, output:output.path,
    label_macro_removed:true, retained_pixels_equal:true, key_separation:true,
    source_unchanged:true, injected_mutation_rejected:true
])
