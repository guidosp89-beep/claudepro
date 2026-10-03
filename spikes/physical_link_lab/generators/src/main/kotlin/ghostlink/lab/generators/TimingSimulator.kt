package ghostlink.lab.generators

import kotlin.math.floor
import kotlin.random.Random

/**
 * Candidates E (temporal modulation) and F (rolling shutter): how many *distinct, clean* symbols per
 * second a rolling-shutter camera can capture from a display that changes content every
 * `hold` refreshes. A capture of a code region is clean when no camera row's exposure window contains
 * a display transition for any display row it sees. With `tiles` > 1 the code is split into
 * independent bands along the camera readout direction (each with its own CRC/symbol), so a
 * capture straddling a transition still yields the clean bands.
 */
data class TimingParams(
    val refreshHz: Double,
    val hold: Int,
    val cameraFps: Double,
    val exposureMs: Double,
    /** Full-sensor readout time (rolling-shutter skew). */
    val readoutMs: Double,
    /** Fraction of the camera image (along readout) covered by the code. */
    val codeFractionCamera: Double = 0.25,
    /** Fraction of the display scan covered by the code. */
    val codeFractionDisplay: Double = 0.9,
    /** Display scan direction perpendicular to camera readout (both phones portrait = true). */
    val perpendicular: Boolean = true,
    /** Pixel response time (OLED ~0.1 ms, LCD ~5–8 ms). */
    val responseMs: Double = 0.5,
    val tiles: Int = 1,
) {
    val txFps: Double get() = refreshHz / hold
}

data class TimingResult(
    val params: TimingParams,
    val cleanFrameFraction: Double,
    val uniqueSymbolsPerSec: Double,
    /** Distinct (symbol, tile) captures per second expressed in whole-symbol equivalents. */
    val uniqueTileSymbolsPerSec: Double,
    /** Worst display/camera phase (rates can be phase-locked for long periods on real devices). */
    val worstPhaseSymbolsPerSec: Double = uniqueSymbolsPerSec,
)

object TimingSimulator {
    /** Averages over [phases] random display/camera phase offsets and reports the worst one too. */
    fun simulate(p: TimingParams, seconds: Double = 20.0, phases: Int = 8): TimingResult {
        val runs = (0 until phases).map { simulateOnce(p, seconds, it + 1L) }
        return TimingResult(
            p, runs.map { it.cleanFrameFraction }.average(), runs.map { it.uniqueSymbolsPerSec }.average(),
            runs.map { it.uniqueTileSymbolsPerSec }.average(), runs.minOf { it.uniqueSymbolsPerSec },
        )
    }

    fun simulateOnce(p: TimingParams, seconds: Double, seed: Long): TimingResult {
        val rnd = Random(seed)
        val frameMs = 1000.0 / p.cameraFps
        val symbolMs = 1000.0 * p.hold / p.refreshHz
        val scanMs = 1000.0 / p.refreshHz * p.codeFractionDisplay
        val roMs = p.readoutMs * p.codeFractionCamera
        val displayPhase = rnd.nextDouble() * symbolMs
        val rows = 64
        val dispSamples = if (p.perpendicular) 16 else 1
        val frames = (seconds * p.cameraFps).toInt()
        val seenFull = HashSet<Long>()
        val seenTiles = HashSet<Long>()
        var clean = 0
        for (c in 0 until frames) {
            val t0 = c * frameMs + rnd.nextDouble(-0.2, 0.2) // small capture jitter
            val rowIdx = LongArray(rows) { -1 }
            for (r in 0 until rows) {
                val rf = (r + 0.5) / rows
                val start = t0 + rf * roMs
                val end = start + p.exposureMs
                var idx = -2L
                for (s in 0 until dispSamples) {
                    val df = if (p.perpendicular) (s + 0.5) / dispSamples else rf
                    // display row df shows symbol floor((t - phase - df*scan)/symbolMs); transition lasts responseMs
                    val off = displayPhase + df * scanMs
                    val a = floor((start - off - p.responseMs) / symbolMs).toLong()
                    val b = floor((end - off) / symbolMs).toLong()
                    val i = if (a == b) a else -1L
                    idx = if (idx == -2L) i else if (idx == i) idx else -1L
                    if (idx == -1L) break
                }
                rowIdx[r] = idx
            }
            if (rowIdx.all { it >= 0 && it == rowIdx[0] }) { clean++; seenFull.add(rowIdx[0]) }
            val per = rows / p.tiles
            for (t in 0 until p.tiles) {
                val band = rowIdx.copyOfRange(t * per, (t + 1) * per)
                if (band.all { it >= 0 && it == band[0] }) seenTiles.add(band[0] * 64 + t)
            }
        }
        return TimingResult(p, clean.toDouble() / frames, seenFull.size / seconds, seenTiles.size / seconds / p.tiles)
    }
}
