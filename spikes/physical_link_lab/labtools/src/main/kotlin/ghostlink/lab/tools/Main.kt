package ghostlink.lab.tools

import java.io.File

/**
 * Cloud experiment runner (JVM). Usage (from spikes/physical_link_lab):
 *   ./gradlew :labtools:run --args="all"           # every experiment, writes results/cloud/
 *   ./gradlew :labtools:run --args="fountain loss" # selected experiments
 * Experiments: fountain, loss, timing, camsim, veil, virtual, pipeline, datasets.
 * Every output is SIMULATED / CLOUD-AUTOMATED evidence, never a physical measurement.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull { it.startsWith("--out=") }?.substringAfter("=") ?: "results/cloud").also { it.mkdirs() }
    val quick = args.contains("--quick")
    val names = args.filter { !it.startsWith("--") }.ifEmpty { listOf("all") }
    val all = linkedMapOf<String, (File, Boolean) -> Unit>(
        "fountain" to FountainBench::run,
        "loss" to LossBench::run,
        "timing" to TimingSweep::run,
        "veil" to VeilSweep::run,
        "camsim" to CamSimSweep::run,
        "virtual" to VirtualLoopRun::run,
        "pipeline" to PipelineBench::run,
        "datasets" to Datasets::run,
    )
    val selected = if ("all" in names) all.keys.toList() else names
    for (n in selected) {
        val f = all[n] ?: error("unknown experiment $n (known: ${all.keys})")
        val t0 = System.nanoTime()
        println("== $n ==")
        f(out, quick)
        println("== $n done in %.1f s".format((System.nanoTime() - t0) / 1e9))
    }
}

/** Tiny CSV helper for experiment outputs. */
class Csv(file: File, private val columns: List<String>) : AutoCloseable {
    private val w = file.bufferedWriter()
    init { w.write(columns.joinToString(",")); w.newLine() }
    @Synchronized fun row(vararg values: Any?) {
        require(values.size == columns.size) { "expected ${columns.size} values" }
        w.write(values.joinToString(",") { v ->
            when (v) {
                null -> ""
                is Double -> if (v.isNaN()) "" else "%.5f".format(java.util.Locale.ROOT, v)
                is Float -> "%.5f".format(java.util.Locale.ROOT, v)
                else -> v.toString().let { s -> if (s.contains(',')) "\"$s\"" else s }
            }
        })
        w.newLine()
    }
    override fun close() = w.close()
}

fun pct(values: List<Double>, p: Double): Double {
    if (values.isEmpty()) return Double.NaN
    val s = values.sorted()
    return s[((p / 100.0) * (s.size - 1)).let { Math.round(it).toInt() }.coerceIn(0, s.size - 1)]
}
