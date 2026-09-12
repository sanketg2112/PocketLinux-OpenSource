package com.sg.linuxgo

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Host-side microbenchmark for classic proot vs experimental tawcroot
 * (github.com/wmww/tawc). Uses [ProotMicrobench] for shared target listing and guest script.
 */
object TawcrootMicrobench {
    private const val TAG = "TawcrootMicrobench"

    // ── tawcroot performance check (separate prefs; same metric script) ─────

    private const val PREFS_TAWCROOT = "pocket_linux_tawcroot_microbench"
    private const val PREF_TAWC_LAST_REPORT = "last_report_text"
    private const val PREF_TAWC_LAST_REPORT_AT = "last_report_at_ms"
    private const val PREF_TAWC_WALL_WINNER = "last_wall_winner"
    private const val PREF_TAWC_CLASSIC_OK = "last_classic_ok"
    private const val PREF_TAWC_OK = "last_tawcroot_ok"
    private const val PREF_TAWC_RECOMMENDATION = "last_recommendation"
    private const val PREF_TAWC_METRIC_JSON = "last_metrics_json"
    private const val PREF_TAWC_FORK_WINNER = "last_fork_winner"
    private const val PREF_TAWC_DESKTOP_WINNER = "last_desktop_winner"

    fun hybridUseTawcroot(context: Context, purpose: ProotBinary.Purpose): Boolean {
        val p = context.getSharedPreferences(PREFS_TAWCROOT, Context.MODE_PRIVATE)
        if (!p.getBoolean(PREF_TAWC_CLASSIC_OK, false) && !p.getBoolean(PREF_TAWC_OK, false)) {
            return false
        }
        if (!p.getBoolean(PREF_TAWC_OK, false)) return false
        val key = when (purpose) {
            ProotBinary.Purpose.TERMINAL, ProotBinary.Purpose.SETUP -> PREF_TAWC_FORK_WINNER
            ProotBinary.Purpose.DESKTOP, ProotBinary.Purpose.GENERAL -> PREF_TAWC_DESKTOP_WINNER
        }
        return p.getString(key, "classic") == "tawcroot"
    }

    fun loadLastTawcrootReport(context: Context): Pair<String, Long>? {
        val p = context.getSharedPreferences(PREFS_TAWCROOT, Context.MODE_PRIVATE)
        val text = p.getString(PREF_TAWC_LAST_REPORT, null)?.takeIf { it.isNotBlank() } ?: return null
        return text to p.getLong(PREF_TAWC_LAST_REPORT_AT, 0L)
    }

    fun saveLastTawcrootReport(context: Context, reportText: String) {
        context.getSharedPreferences(PREFS_TAWCROOT, Context.MODE_PRIVATE).edit()
            .putString(PREF_TAWC_LAST_REPORT, reportText)
            .putLong(PREF_TAWC_LAST_REPORT_AT, System.currentTimeMillis())
            .apply()
    }

    fun lastTawcrootRecommendation(context: Context): String? {
        return context.getSharedPreferences(PREFS_TAWCROOT, Context.MODE_PRIVATE)
            .getString(PREF_TAWC_RECOMMENDATION, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun tawcrootHybridRoutingSummary(context: Context): String {
        val p = context.getSharedPreferences(PREFS_TAWCROOT, Context.MODE_PRIVATE)
        if (!p.contains(PREF_TAWC_WALL_WINNER)) {
            return "Run a tawcroot performance check to configure Hybrid routing."
        }
        val fork = p.getString(PREF_TAWC_FORK_WINNER, "classic")
        val desk = p.getString(PREF_TAWC_DESKTOP_WINNER, "classic")
        fun label(w: String?) = if (w == "tawcroot") "tawcroot" else "classic"
        return "Hybrid map: terminal/setup → ${label(fork)} · desktop → ${label(desk)}"
    }

    fun loadTawcrootChartBars(context: Context): List<ProotMicrobench.ChartBar> {
        val p = context.getSharedPreferences(PREFS_TAWCROOT, Context.MODE_PRIVATE)
        val json = p.getString(PREF_TAWC_METRIC_JSON, null) ?: return emptyList()
        return try {
            val root = org.json.JSONObject(json)
            val classic = root.optJSONObject("classic") ?: return emptyList()
            val challenger = root.optJSONObject("tawcroot")
            ProotMicrobench.CHART_METRICS.map { (label, key) ->
                val c = classic.optDouble(key, Double.NaN).takeIf { !it.isNaN() }
                val t = challenger?.optDouble(key, Double.NaN)?.takeIf { !it.isNaN() }
                ProotMicrobench.ChartBar(
                    label = label,
                    key = key,
                    classicMs = c,
                    challengerMs = t,
                    winner = when {
                        c == null && t == null -> "n/a"
                        c == null -> "tawcroot"
                        t == null -> "classic"
                        kotlin.math.abs(c - t) < 0.05 * maxOf(c, t, 1.0) -> "tie"
                        t < c -> "tawcroot"
                        else -> "classic"
                    }
                )
            }.filter { it.classicMs != null || it.challengerMs != null }
        } catch (e: Exception) {
            Log.w(TAG, "loadTawcrootChartBars failed: ${e.message}")
            emptyList()
        }
    }

    fun clearLastTawcrootReport(context: Context) {
        context.getSharedPreferences(PREFS_TAWCROOT, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun saveTawcrootCompareOutcome(context: Context, report: TawcrootCompareReport) {
        val classicOk = report.classic.success
        val tawcOk = report.tawcroot?.success == true
        val c = report.classic
        val t = report.tawcroot
        val wallWinner = when {
            classicOk && tawcOk && t != null ->
                if (t.wallMs < c.wallMs) "tawcroot" else "classic"
            classicOk -> "classic"
            tawcOk -> "tawcroot"
            else -> "classic"
        }
        val forkWinner = metricWinnerTawc(c, t, "FORK_TRUE_200")
            ?: metricWinnerTawc(c, t, "FORK_SH_100")
            ?: wallWinner
        val desktopWinner = desktopMetricWinnerTawc(c, t) ?: wallWinner
        val recommendation = buildTawcrootRecommendation(report)
        val metricsJson = buildTawcrootMetricsJson(c, t)

        context.getSharedPreferences(PREFS_TAWCROOT, Context.MODE_PRIVATE).edit()
            .putString(PREF_TAWC_WALL_WINNER, wallWinner)
            .putString(PREF_TAWC_FORK_WINNER, forkWinner)
            .putString(PREF_TAWC_DESKTOP_WINNER, desktopWinner)
            .putBoolean(PREF_TAWC_CLASSIC_OK, classicOk)
            .putBoolean(PREF_TAWC_OK, tawcOk)
            .putString(PREF_TAWC_RECOMMENDATION, recommendation)
            .putString(PREF_TAWC_METRIC_JSON, metricsJson)
            .apply()
        Log.i(TAG, "Saved tawcroot bench wall=$wallWinner fork=$forkWinner desktop=$desktopWinner")
    }

    data class TawcrootCompareReport(
        val target: ProotMicrobench.Target,
        val classic: ProotMicrobench.RunResult,
        val tawcroot: ProotMicrobench.RunResult?,
        val tawcrootAvailable: Boolean
    ) {
        fun format(): String = buildString {
            appendLine("Container: ${target.displayName} (${target.containerId})")
            appendLine("Rootfs: ${target.rootfsPath}")
            appendLine("tawcroot libs present: $tawcrootAvailable")
            appendLine()
            append(formatRunShared(classic))
            if (tawcroot != null) {
                appendLine()
                append(formatRunShared(tawcroot))
                appendLine()
                append(formatDeltaTawc(classic, tawcroot))
                appendLine()
                appendLine("── Recommendation ──")
                appendLine(buildTawcrootRecommendation(this@TawcrootCompareReport))
            } else if (!tawcrootAvailable) {
                appendLine()
                appendLine("tawcroot: libtawcroot.so not packaged (arm64 only)")
            }
        }
    }

    private fun formatRunShared(r: ProotMicrobench.RunResult): String = buildString {
        appendLine("── ${r.runtimeLabel} ──")
        if (!r.success) {
            appendLine("FAILED  wall=${ProotMicrobench.fmtPublic(r.wallMs)} ms")
            r.error?.let { appendLine(it.trim().take(400)) }
            if (r.rawTail.isNotBlank()) appendLine(r.rawTail.trim().take(400))
            return@buildString
        }
        appendLine("OK  wall=${ProotMicrobench.fmtPublic(r.wallMs)} ms")
        r.coldStartMs?.let { appendLine("cold_echo=${ProotMicrobench.fmtPublic(it)} ms") }
        val order = listOf(
            "FORK_TRUE_200", "FORK_SH_100", "STAT_1500", "READ_OSRELEASE_100",
            "COMPUTE_5000", "WRITE_UNLINK_100", "PYTHON_SUM_100k"
        )
        for (key in order) {
            val v = r.metrics[key] ?: continue
            appendLine("${key}_ms=${ProotMicrobench.fmtPublic(v)}")
        }
        for ((k, v) in r.metrics) {
            if (k !in order) appendLine("${k}_ms=${ProotMicrobench.fmtPublic(v)}")
        }
    }

    private fun formatDeltaTawc(classic: ProotMicrobench.RunResult, tawc: ProotMicrobench.RunResult): String {
        if (!classic.success || !tawc.success) {
            return "Delta: n/a (one side failed — no speedup to report)"
        }
        return buildString {
            appendLine("── Delta (tawcroot vs classic; negative = tawcroot faster) ──")
            fun line(label: String, c: Double?, t: Double?) {
                if (c == null || t == null) return
                val d = t - c
                val pct = if (c > 0.0) (d / c) * 100.0 else 0.0
                appendLine(
                    "$label: classic=${ProotMicrobench.fmtPublic(c)}  tawcroot=${ProotMicrobench.fmtPublic(t)}  " +
                        "Δ=${ProotMicrobench.fmtPublic(d)} ms (${ProotMicrobench.fmtPublic(pct)}%)"
                )
            }
            line("wall", classic.wallMs, tawc.wallMs)
            line("cold_echo", classic.coldStartMs, tawc.coldStartMs)
            for (k in (classic.metrics.keys + tawc.metrics.keys).toSortedSet()) {
                line(k, classic.metrics[k], tawc.metrics[k])
            }
        }
    }

    private fun metricWinnerTawc(c: ProotMicrobench.RunResult, t: ProotMicrobench.RunResult?, key: String): String? {
        if (!c.success || t?.success != true) return null
        val cv = c.metrics[key] ?: return null
        val tv = t.metrics[key] ?: return null
        return if (tv < cv) "tawcroot" else "classic"
    }

    private fun desktopMetricWinnerTawc(c: ProotMicrobench.RunResult, t: ProotMicrobench.RunResult?): String? {
        if (!c.success || t?.success != true) return null
        val keys = listOf("STAT_1500", "WRITE_UNLINK_100", "COMPUTE_5000", "READ_OSRELEASE_100")
        var cWins = 0
        var tWins = 0
        for (k in keys) {
            val cv = c.metrics[k] ?: continue
            val tv = t.metrics[k] ?: continue
            if (tv < cv) tWins++ else cWins++
        }
        return when {
            tWins > cWins -> "tawcroot"
            cWins > tWins -> "classic"
            else -> if (t.wallMs < c.wallMs) "tawcroot" else "classic"
        }
    }

    fun buildTawcrootRecommendation(report: TawcrootCompareReport): String {
        val c = report.classic
        val t = report.tawcroot
        if (!c.success && t?.success != true) return "Both failed — stay on classic proot."
        if (!c.success && t?.success == true) return "Only tawcroot completed — use tawcroot always."
        if (c.success && t?.success != true) {
            return "Only classic completed — Hybrid will keep classic until tawcroot passes."
        }
        val tr = t!!
        val fork = metricWinnerTawc(c, tr, "FORK_TRUE_200")
        val desk = desktopMetricWinnerTawc(c, tr)
        val wall = if (tr.wallMs < c.wallMs) "tawcroot" else "classic"
        fun L(w: String?) = if (w == "tawcroot") "tawcroot" else "classic"
        return "Hybrid would use: terminal/setup → ${L(fork)} · desktop → ${L(desk)}. " +
            "Overall wall: ${L(wall)}. Prefer Hybrid when forks and file work disagree; " +
            "tawcroot always only if tawcroot wins across the board."
    }

    private fun buildTawcrootMetricsJson(c: ProotMicrobench.RunResult, t: ProotMicrobench.RunResult?): String {
        fun pack(r: ProotMicrobench.RunResult?): org.json.JSONObject {
            val o = org.json.JSONObject()
            if (r == null || !r.success) return o
            o.put("WALL", r.wallMs)
            r.coldStartMs?.let { o.put("COLD", it) }
            for ((k, v) in r.metrics) o.put(k, v)
            return o
        }
        return org.json.JSONObject()
            .put("classic", pack(c.takeIf { it.success }))
            .put("tawcroot", pack(t?.takeIf { it.success }))
            .toString()
    }

    /**
     * Run classic always; run tawcroot when [libtawcroot.so] is present.
     */
    fun runCompareTawcroot(
        context: Context,
        containerId: String? = null,
        onProgress: (String) -> Unit = {}
    ): TawcrootCompareReport {
        val target = ProotMicrobench.pickTarget(context, containerId)
            ?: error("No installed container with a guest shell found. Install a container first.")
        if (containerId != null) ProotMicrobench.setPreferredTargetId(context, target.containerId)
        val appCtx = context.applicationContext
        val classicRt = ProotBinary.classic(appCtx)
        val tawcRt = ProotBinary.tawcrootOrNull(appCtx)

        ProotMicrobench.writeGuestScriptForBench(File(target.rootfsPath))

        onProgress("Running classic proot…")
        val classic = ProotMicrobench.runOnceForBench(appCtx, target.rootfsPath, classicRt, "classic proot")

        val tawc = if (tawcRt != null) {
            onProgress("Running tawcroot…")
            ProotMicrobench.runOnceForBench(appCtx, target.rootfsPath, tawcRt, "tawcroot")
        } else {
            null
        }

        onProgress("Done")
        val report = TawcrootCompareReport(
            target = target,
            classic = classic,
            tawcroot = tawc,
            tawcrootAvailable = tawcRt != null
        )
        saveTawcrootCompareOutcome(appCtx, report)
        return report
    }

}
