package ghostlink.lab.benchmark

import ghostlink.lab.codecs.FrameType
import ghostlink.lab.codecs.LabFrame
import ghostlink.lab.codecs.SchemeId
import ghostlink.lab.codecs.Stage
import ghostlink.lab.codecs.visual.QrEcc

/**
 * Stage 2 of the search (brief §22): rank configurations by measured results and eliminate the
 * clearly worse ones. Score = median goodput of PASS runs × success rate (failures count as 0).
 * Ties are broken by fewer failed frames. Only throughput-search stages are ranked.
 */
object Ranking {
    data class Scored(val spec: TrialSpec, val score: Double, val runs: Int, val passes: Int, val medianGoodput: Double)

    fun rank(records: List<RunRecord>, stages: Set<String> = setOf(Stage.COARSE.name, Stage.FINE.name, Stage.CONFIRM.name)): List<Scored> =
        records.filter { it["stage"] in stages && it["config_key"] != null }
            .groupBy { it["config_key"] as String }
            .mapNotNull { (_, rs) ->
                val spec = specOf(rs.first()) ?: return@mapNotNull null
                val passes = rs.filter { it["RESULT"] == "PASS" }
                val gp = passes.map { (it["goodput_bytes_sec"] as? Number)?.toDouble() ?: 0.0 }
                val med = percentile(gp, 50.0) ?: 0.0
                Scored(spec, med * passes.size / rs.size, rs.size, passes.size, med)
            }
            .sortedByDescending { it.score }

    fun top(records: List<RunRecord>, n: Int = 4): List<TrialSpec> = rank(records).filter { it.score > 0 }.take(n).map { it.spec }

    fun specOf(r: RunRecord): TrialSpec? {
        val fps = (r["target_visual_fps"] as? Number)?.toInt() ?: return null
        val scheme = (r["scheme"] as? String)?.let { s -> SchemeId.entries.firstOrNull { it.name == s } } ?: return null
        val phy = when (r["codec"]) {
            "QR" -> PhyChoice.Qr((r["qr_version"] as Number).toInt(), QrEcc.valueOf(r["qr_ecc"] as String))
            "GRID" -> PhyChoice.Grid((r["grid_cols"] as Number).toInt(), (r["grid_bits_per_cell"] as Number).toInt(),
                (r["grid_rs_parity"] as Number).toInt(), (r["grid_finder_module"] as? Number)?.toInt() ?: 1)
            else -> return null
        }
        return TrialSpec(phy, fps, scheme, Plans.FINE_PAYLOAD, Stage.FINE)
    }

    /** PLAN frame (shown by the receiver as a QR, scanned by the transmitter). */
    fun planFrame(sessionTag: Int, top: List<TrialSpec>): ByteArray =
        LabFrame(FrameType.PLAN, sessionTag, 0, SchemeId.SEQUENTIAL, 0, 0, Plans.encodeTop(top)).encode()
}
