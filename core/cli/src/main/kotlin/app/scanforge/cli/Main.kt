package app.scanforge.cli

import app.scanforge.export.AxisConvention
import app.scanforge.export.ExportFormat
import app.scanforge.export.ExportOptions
import app.scanforge.export.MeshExporter
import app.scanforge.geometry.Aabb
import app.scanforge.geometry.LengthUnit
import app.scanforge.geometry.Vec3
import app.scanforge.project.MeasurementRecords
import app.scanforge.project.ObjectCategory
import app.scanforge.project.ProjectPackager
import app.scanforge.project.ProjectStore
import app.scanforge.reconstruction.CaptureWriter
import app.scanforge.reconstruction.DirectoryCaptureSource
import app.scanforge.reconstruction.ImageDecoder
import app.scanforge.reconstruction.InMemoryCaptureSource
import app.scanforge.reconstruction.QualityPreset
import app.scanforge.reconstruction.ReconstructionException
import app.scanforge.reconstruction.RgbImage
import app.scanforge.reconstruction.SdfShape
import app.scanforge.reconstruction.SyntheticScene
import app.scanforge.transfer.ClientRegistry
import app.scanforge.transfer.DiscoveryReply
import app.scanforge.transfer.DiscoveryResponder
import app.scanforge.transfer.PairingCode
import app.scanforge.transfer.ServerConfig
import app.scanforge.transfer.TlsSupport
import app.scanforge.transfer.TransferEvent
import app.scanforge.transfer.TransferServer
import app.scanforge.workflow.ScanWorkflow
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private val USAGE = """
ScanForge 3D developer CLI

  synth <capture-dir> [--shape box|cylinder|sphere] [--frames N]
        Write a SYNTHETIC capture dataset (ground truth known) in the on-device capture format.
  reconstruct <capture-dir> <store> --name NAME [--preset draft|standard|high]
        Reconstruct a capture directory into a new project; prints the project id.
  info <store> <project-id> [--unit mm]
  export <store> <project-id> <format: ply|obj|stl|glb> <out-dir> [--axis y_up|z_up] [--unit mm|cm|m|in|ft]
  calibrate <store> <project-id> --measured <m> --reference <m>
        Record a known-length reference (both in meters) and rescale the model.
  package <store> <project-id> <out.sf3d> [--include-capture]
  colmap <capture-dir> <out-dir>
        Write keyframes + ARCore poses as a COLMAP text model for optional desktop photogrammetry.
  serve <state-dir> --offer <file.sf3d> [--name NAME] [--port 47821] [--bind 127.0.0.1]
        [--auto-approve] [--exit-after-receipt]
        Run the transfer server (same code as the phone) for testing the Blender add-on.
""".trimIndent()

object AwtImageDecoder : ImageDecoder {
    override fun decode(bytes: ByteArray): RgbImage {
        val img = ImageIO.read(bytes.inputStream()) ?: error("Unreadable image")
        val rgb = ByteArray(img.width * img.height * 3)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y); val o = (y * img.width + x) * 3
            rgb[o] = (p shr 16).toByte(); rgb[o + 1] = (p shr 8).toByte(); rgb[o + 2] = p.toByte()
        }
        return RgbImage(img.width, img.height, rgb)
    }

    fun encodeJpeg(img: RgbImage): ByteArray {
        val bi = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val o = (y * img.width + x) * 3
            bi.setRGB(x, y, ((img.rgb[o].toInt() and 0xff) shl 16) or ((img.rgb[o + 1].toInt() and 0xff) shl 8) or (img.rgb[o + 2].toInt() and 0xff))
        }
        return ByteArrayOutputStream().also { ImageIO.write(bi, "jpg", it) }.toByteArray()
    }
}

private class Args(raw: Array<String>) {
    val positional = ArrayList<String>()
    val options = HashMap<String, String>()
    val flags = HashSet<String>()
    init {
        var i = 0
        while (i < raw.size) {
            val a = raw[i]
            if (a.startsWith("--")) {
                if (i + 1 < raw.size && !raw[i + 1].startsWith("--")) { options[a.drop(2)] = raw[i + 1]; i++ } else flags += a.drop(2)
            } else positional += a
            i++
        }
    }
    fun pos(i: Int, name: String) = positional.getOrNull(i) ?: fail("Missing <$name>")
}

private fun fail(msg: String): Nothing { System.err.println("error: $msg\n\n$USAGE"); exitProcess(2) }

fun main(argv: Array<String>) {
    if (argv.isEmpty() || argv[0] in setOf("-h", "--help", "help")) { println(USAGE); return }
    val args = Args(argv.drop(1).toTypedArray())
    try {
        when (argv[0]) {
            "synth" -> synth(args)
            "reconstruct" -> reconstruct(args)
            "info" -> info(args)
            "export" -> export(args)
            "package" -> pack(args)
            "calibrate" -> calibrate(args)
            "colmap" -> {
                val r = app.scanforge.reconstruction.ColmapExport.export(File(args.pos(0, "capture-dir")), File(args.pos(1, "out-dir")))
                println("Wrote ${r.images} images, ${r.cameras} camera(s) to ${r.dir}")
            }
            "serve" -> serve(args)
            else -> fail("Unknown command ${argv[0]}")
        }
    } catch (e: ReconstructionException) {
        System.err.println("Reconstruction failed: ${e.message}")
        e.failure.recovery.forEach { System.err.println("  - $it") }
        exitProcess(3)
    }
}

private fun synth(a: Args) {
    val dir = File(a.pos(0, "capture-dir"))
    require(!File(dir, "frames.jsonl").exists()) { "$dir already contains a capture" }
    val shape = when (a.options["shape"] ?: "box") {
        "box" -> SdfShape.Box(Vec3(0.0, 0.04, 0.0), Vec3(0.06, 0.04, 0.03))
        "cylinder" -> SdfShape.Cylinder(Vec3.ZERO, 0.04, 0.10)
        "sphere" -> SdfShape.Sphere(Vec3(0.0, 0.05, 0.0), 0.05)
        else -> fail("Unknown shape")
    }
    val perRing = a.options["frames"]?.toInt()?.div(3) ?: 24
    val mem: InMemoryCaptureSource = SyntheticScene(listOf(shape)).orbitCapture(
        Vec3(0.0, 0.05, 0.0), 0.45, listOf(15.0, 40.0, 70.0), perRing, Aabb(Vec3(-0.25, -0.05, -0.25), Vec3(0.25, 0.3, 0.25)),
    )
    CaptureWriter(dir, mem.session).use { w ->
        for (f in mem.frames) w.append(f.copy(colorFile = null, depthFile = null), mem.loadDepth(f), mem.loadColor(f)?.let(AwtImageDecoder::encodeJpeg))
        println("Wrote ${w.frameCount} SYNTHETIC frames (${w.bytesWritten / 1024} KiB) to $dir")
    }
}

private fun reconstruct(a: Args) {
    val captureDir = File(a.pos(0, "capture-dir"))
    val store = ProjectStore(File(a.pos(1, "store")))
    val name = a.options["name"] ?: fail("--name is required")
    val probe = DirectoryCaptureSource(captureDir, null)
    val p = store.create(name, ObjectCategory.OTHER, if (probe.session.source == "synthetic") listOf("synthetic") else emptyList())
    // Projects own their capture (as on the phone), so packages can include it and reconstruction can be re-run.
    captureDir.copyRecursively(store.captureDir(p.id), overwrite = true)
    val source = DirectoryCaptureSource(store.captureDir(p.id), AwtImageDecoder)
    val preset = a.options["preset"]?.let { QualityPreset.valueOf(it.uppercase()) }
    val wf = ScanWorkflow(store)
    var lastStage = ""
    val settings = app.scanforge.reconstruction.ReconstructionSettings.forPreset(preset ?: source.session.qualityPreset)
    wf.reconstruct(p.id, source, settings) { pr ->
        if (pr.stage.name != lastStage || pr.completed == pr.total) { System.err.println("[${pr.stage}] ${pr.message}"); lastStage = pr.stage.name }
    }
    println(p.id)
}

private fun info(a: Args) {
    val store = ProjectStore(File(a.pos(0, "store")))
    val doc = store.load(a.pos(1, "project-id"))
    val unit = LengthUnit.fromSymbol(a.options["unit"] ?: "mm")
    val v = doc.model.current
    println("${doc.name} [${doc.status}] scale=${doc.scale.source}×${doc.scale.factor}")
    v?.let { println("model v${it.version}: ${it.vertexCount} vertices, ${it.triangleCount} triangles, watertight=${it.watertight}, holes=${it.holeCount}") }
    doc.reconstruction?.warnings?.forEach { println("warning: $it") }
    for (m in doc.measurements) {
        val values = MeasurementRecords.calibrated(doc, m).joinToString(" × ") { MeasurementRecords.format(it, m.dimension, unit) }
        println("${m.label}: $values  [confidence ${m.confidence}]")
    }
}

private fun export(a: Args) {
    val store = ProjectStore(File(a.pos(0, "store")))
    val id = a.pos(1, "project-id")
    val format = ExportFormat.valueOf(a.pos(2, "format").uppercase())
    val options = ExportOptions(
        format,
        axis = AxisConvention.valueOf((a.options["axis"] ?: "y_up").uppercase()),
        unit = LengthUnit.fromSymbol(a.options["unit"] ?: "m"),
    )
    val doc = store.load(id)
    val r = MeshExporter.export(store.loadModel(id), File(a.pos(3, "out-dir")), doc.name.replace(Regex("[^A-Za-z0-9_-]"), "_"), options)
    println("${r.primaryFile} (${r.bytes} bytes, ${r.triangleCount} triangles) validation=${if (r.validation.ok) "ok" else r.validation.problems}")
}

private fun calibrate(a: Args) {
    val store = ProjectStore(File(a.pos(0, "store")))
    val id = a.pos(1, "project-id")
    val measured = a.options["measured"]?.toDouble() ?: fail("--measured is required")
    val reference = a.options["reference"]?.toDouble() ?: fail("--reference is required")
    val doc = store.save(app.scanforge.project.Calibration.addReference(store.load(id), app.scanforge.project.CalibrationMethod.USER_ENTERED, measured, reference, emptyList(), store.now()))
    println("scale factor ${doc.scale.factor} (${doc.scale.source})")
}

private fun pack(a: Args) {
    val store = ProjectStore(File(a.pos(0, "store")))
    val m = ProjectPackager.pack(store, a.pos(1, "project-id"), File(a.pos(2, "out.sf3d")), "include-capture" in a.flags)
    println("Packaged ${m.files.size} files, primary model ${m.primaryModel}")
}

private fun serve(a: Args) {
    val state = File(a.pos(0, "state-dir")).apply { mkdirs() }
    val ks = File(state, "server.p12")
    val password = "scanforge-dev"
    if (!ks.exists()) TlsSupport.generateWithKeytool(ks, password, "ScanForge CLI")
    val store = TlsSupport.loadPkcs12(ks, password.toCharArray())
    val cert = TlsSupport.certificate(store).encoded
    val bind = InetAddress.getByName(a.options["bind"] ?: "127.0.0.1")
    val name = a.options["name"] ?: "ScanForge CLI"
    val server = TransferServer(
        ServerConfig(deviceName = name, deviceId = "cli-" + PairingCode.fingerprintHex(cert).take(12), port = (a.options["port"] ?: "47821").toInt(), bindAddress = bind),
        ClientRegistry(File(state, "clients.json")),
        TlsSupport.serverSocketFactory(TlsSupport.sslContext(store, password.toCharArray())),
    )
    val done = CountDownLatch(1)
    val autoApprove = "auto-approve" in a.flags
    if (autoApprove) System.err.println("WARNING: --auto-approve accepts any pairing request. Use only for automated tests on loopback.")
    server.events.subscribe { e ->
        when (e) {
            is TransferEvent.PairingRequested -> {
                println("PAIRING_REQUEST ${e.pending.request.clientName} from ${e.pending.address}")
                if (autoApprove) server.approvePairing(e.pending.requestId)
            }
            is TransferEvent.OfferCompleted -> { println("RECEIPT ${e.offerId} verified=${e.verified} ${e.detail ?: ""}"); if ("exit-after-receipt" in a.flags) done.countDown() }
            is TransferEvent.OfferProgress -> {}
            is TransferEvent.ClientSeen -> {}
            is TransferEvent.ServerError -> System.err.println("server error: ${e.message}")
        }
    }
    server.start()
    server.setPairingOpen(true)
    a.options["offer"]?.let { path ->
        val f = File(path)
        val manifest = ProjectPackager.verify(f)
        val o = server.offer(f, manifest.projectId, manifest.projectName)
        println("OFFER ${o.id} ${manifest.projectName} ${o.bytes} bytes sha256=${o.sha256}")
    }
    val discovery = runCatching { DiscoveryResponder { DiscoveryReply(deviceName = name, deviceId = "cli", port = server.localPort, pairingOpen = server.pairingOpen) }.also { it.start() } }.getOrNull()
    println("LISTENING ${bind.hostAddress}:${server.localPort}")
    println("PAIRING_CODE ${PairingCode.fromCertificate(cert)}")
    System.out.flush()
    Runtime.getRuntime().addShutdownHook(Thread { server.close(); discovery?.close() })
    done.await()
    Thread.sleep(300)
    server.close(); discovery?.close()
}
