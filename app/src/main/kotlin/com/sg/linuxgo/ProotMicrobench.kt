package com.sg.linuxgo

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Host-side microbenchmark helpers for classic proot vs tawcroot.
 * Runs a fixed guest script in an installed container rootfs and parses
 * KEY=value timing lines. Used from Experimental settings.
 */
object ProotMicrobench {
    private const val TAG = "ProotMicrobench"
    /** Absolute guest path (outside /tmp bind used for write/unlink tests). */
    private const val GUEST_BIN = "/usr/local/bin/pocketlinux-microbench"
    private const val TIMEOUT_SEC = 90L

    /** Prefs for last selected bench container. */
    private const val PREFS_NAME = "pocket_linux_microbench"
    private const val PREF_TARGET_CONTAINER_ID = "bench_target_container_id"

    /** Display order / chart rows (label, metric key). */
    val CHART_METRICS: List<Pair<String, String>> = listOf(
        "Wall time" to "WALL",
        "Cold start" to "COLD",
        "Forks ×200" to "FORK_TRUE_200",
        "Shell forks ×100" to "FORK_SH_100",
        "Path/stat" to "STAT_1500",
        "Read file ×100" to "READ_OSRELEASE_100",
        "Compute" to "COMPUTE_5000",
        "Write/unlink" to "WRITE_UNLINK_100",
        "Python sum" to "PYTHON_SUM_100k"
    )

    data class ChartBar(
        val label: String,
        val key: String,
        val classicMs: Double?,
        val challengerMs: Double?,
        /** "classic" | challenger id | "tie" | "n/a" */
        val winner: String
    )

    data class Target(
        val containerId: String,
        val displayName: String,
        val rootfsPath: String
    )

    data class RunResult(
        val runtimeLabel: String,
        val success: Boolean,
        val wallMs: Double,
        val coldStartMs: Double?,
        val metrics: Map<String, Double>,
        val rawTail: String,
        val error: String?,
        val useTawcroot: Boolean = false
    )

    /**
     * True if [root] has a guest shell entry the bench can exec.
     * Must not use plain [File.exists] on bin/sh — Alpine uses guest-absolute
     * `bin/sh` → `/bin/busybox` and host exists() is false for those links.
     */
    fun rootfsHasGuestShell(root: File): Boolean =
        com.sg.linuxgo.util.TarHardlinkSafeExtract.hasGuestShell(root)

    /** All installed containers usable as a microbench rootfs. */
    fun listTargets(context: Context): List<Target> {
        val cm = ContainerManager(context)
        return cm.getContainers().mapNotNull { c ->
            val rootfs = cm.getContainerRootfsPath(c.id)
            val root = File(rootfs)
            val ready = (c.isInstalled || cm.isContainerInstalled(c.id)) &&
                rootfsHasGuestShell(root)
            if (!ready) return@mapNotNull null
            val name = c.name.ifBlank {
                listOfNotNull(c.distro, c.de)
                    .filter { it.isNotBlank() }
                    .joinToString(" / ")
                    .ifBlank { c.id }
            }
            Target(c.id, name, rootfs)
        }
    }

    fun getPreferredTargetId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_TARGET_CONTAINER_ID, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setPreferredTargetId(context: Context, containerId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_TARGET_CONTAINER_ID, containerId)
            .apply()
    }

    /**
     * Prefer [containerId] if set and still valid; else saved preference; else first installed.
     */
    fun pickTarget(context: Context, containerId: String? = null): Target? {
        val targets = listTargets(context)
        if (targets.isEmpty()) return null
        val preferred = containerId?.takeIf { it.isNotBlank() }
            ?: getPreferredTargetId(context)
        if (preferred != null) {
            targets.firstOrNull { it.containerId == preferred }?.let { return it }
        }
        return targets.first()
    }

    /** Shared by [TawcrootMicrobench.runCompareTawcroot]. */
    internal fun runOnceForBench(
        context: Context,
        rootfsPath: String,
        runtime: ProotBinary.Runtime,
        label: String
    ): RunResult = runOnce(context, rootfsPath, runtime, label)

    private fun runOnce(
        context: Context,
        rootfsPath: String,
        runtime: ProotBinary.Runtime,
        label: String
    ): RunResult {
        val launcher = File(runtime.launcherPath)
        if (!launcher.exists()) {
            return RunResult(
                runtimeLabel = label,
                success = false,
                wallMs = 0.0,
                coldStartMs = null,
                metrics = emptyMap(),
                rawTail = "",
                error = "Launcher missing: ${runtime.launcherPath}",
                useTawcroot = runtime.useTawcroot
            )
        }
        launcher.setExecutable(true, false)

        val cold = measureColdEcho(context, rootfsPath, runtime)
        val cmd = buildGuestCommand(context, rootfsPath, runtime, listOf(GUEST_BIN))
        Log.i(TAG, "Bench $label: ${cmd.joinToString(" ")}")

        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            ProotBinary.configureProcessBuilderEnv(pb.environment(), context, runtime)
            val t0 = System.nanoTime()
            val process = pb.start()
            val out = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { r ->
                        r.lineSequence().forEach { line ->
                            out.appendLine(line)
                            Log.d(TAG, "[$label] $line")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "read failed: ${e.message}")
                }
            }.apply { isDaemon = true; name = "microbench-$label"; start() }

            val finished = process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(1000)
                return RunResult(
                    runtimeLabel = label,
                    success = false,
                    wallMs = (System.nanoTime() - t0) / 1e6,
                    coldStartMs = cold,
                    metrics = emptyMap(),
                    rawTail = out.toString().takeLast(500),
                    error = "Timed out after ${TIMEOUT_SEC}s",
                    useTawcroot = runtime.useTawcroot
                )
            }
            reader.join(3000)
            val wallMs = (System.nanoTime() - t0) / 1e6
            val text = out.toString()
            val metrics = parseMetrics(text)
            val signalKill = text.contains("killed by signal") ||
                text.contains("child exited with code 139") ||
                text.contains("SIGSEGV")
            val crashed = signalKill ||
                (text.contains("extend-bss") && metrics.isEmpty()) ||
                (process.exitValue() != 0 && metrics.isEmpty())
            val ok = metrics.isNotEmpty() && !crashed
            RunResult(
                runtimeLabel = label,
                success = ok,
                wallMs = wallMs,
                coldStartMs = cold,
                metrics = metrics,
                rawTail = text.takeLast(600),
                useTawcroot = runtime.useTawcroot,
                error = if (ok) null else {
                    val hint = when {
                        text.contains("awk: command not found") ->
                            "guest missing awk (fixed in latest bench script — re-run check)"
                        signalKill -> "runtime/guest crashed (signal)"
                        metrics.isEmpty() && text.contains("BENCH_END") ->
                            "bench finished but no metric numbers parsed"
                        else -> text.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
                    }
                    "exit=${process.exitValue()}" + if (hint.isNotBlank()) " · $hint" else ""
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bench $label failed", e)
            RunResult(
                runtimeLabel = label,
                success = false,
                wallMs = 0.0,
                coldStartMs = cold,
                metrics = emptyMap(),
                rawTail = "",
                error = e.message ?: e.javaClass.simpleName,
                useTawcroot = runtime.useTawcroot
            )
        }
    }

    private fun measureColdEcho(
        context: Context,
        rootfsPath: String,
        runtime: ProotBinary.Runtime
    ): Double? {
        return try {
            val cmd = buildGuestCommand(
                context, rootfsPath, runtime,
                listOf("/bin/sh", "-c", "echo ok")
            )
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            ProotBinary.configureProcessBuilderEnv(pb.environment(), context, runtime)
            val t0 = System.nanoTime()
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val done = p.waitFor(20, TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                return null
            }
            val ms = (System.nanoTime() - t0) / 1e6
            if (out.contains("ok") && p.exitValue() == 0) ms else null
        } catch (_: Exception) {
            null
        }
    }

    private fun buildGuestCommand(
        context: Context,
        rootfsPath: String,
        runtime: ProotBinary.Runtime,
        guestArgs: List<String>
    ): List<String> {
        val tmpHost = File(context.cacheDir, "microbench_tmp").apply { mkdirs() }
        val guestEnv = listOf(
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root",
            "TERM=xterm",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "PWD=/"
        )

        if (runtime.useTawcroot) {
            return buildList {
                add(runtime.launcherPath)
                add("-r"); add(rootfsPath)
                add("-b"); add("/dev:/dev")
                add("-b"); add("/proc:/proc")
                add("-b"); add("/sys:/sys")
                LinuxIsolation.phoneStorageBindSpec(context)?.let { spec ->
                    add("-b"); add(spec)
                }
                add("-b"); add("${tmpHost.absolutePath}:/tmp")
                add("--")
                add("/usr/bin/env")
                add("-i")
                addAll(guestEnv)
                addAll(guestArgs)
            }
        }

        val hostGuest = runtime.requiresHostGuestBinds
        fun b(path: String) = ProotBinary.bindSpec(path, hostGuest)
        val cmd = mutableListOf(
            runtime.launcherPath,
            "--link2symlink",
            "-0",
            "-r", rootfsPath,
            "-b", b("/dev"),
            "-b", b("/proc"),
            "-b", b("/sys")
        )
        LinuxIsolation.phoneStorageBindSpec(context)?.let { spec ->
            cmd.add("-b")
            cmd.add(spec)
        }
        cmd.add("--sysvipc")
        cmd.add("-b")
        cmd.add("${tmpHost.absolutePath}:/tmp")
        cmd.add("-w")
        cmd.add("/")
        cmd.add("/usr/bin/env")
        cmd.add("-i")
        cmd.addAll(guestEnv)
        cmd.addAll(guestArgs)
        return cmd
    }

    internal fun writeGuestScriptForBench(rootfs: File) = writeGuestScript(rootfs)

    private fun writeGuestScript(rootfs: File) {
        // Outside /tmp bind mount — path is GUEST_BIN under the container rootfs.
        val script = File(rootfs, GUEST_BIN.removePrefix("/"))
        script.parentFile?.mkdirs()
        script.writeText(GUEST_SCRIPT_BODY)
        script.setExecutable(true, false)
    }

    private fun parseMetrics(text: String): Map<String, Double> {
        val out = linkedMapOf<String, Double>()
        val re = Regex("""^([A-Z0-9_]+)_ms=([0-9]+(?:\.[0-9]+)?)""")
        for (line in text.lineSequence()) {
            val m = re.find(line.trim()) ?: continue
            out[m.groupValues[1]] = m.groupValues[2].toDoubleOrNull() ?: continue
        }
        return out
    }

    internal fun fmtPublic(v: Double): String = fmt(v)

    private fun fmt(v: Double): String {
        return if (v >= 100) v.roundToInt().toString()
        else String.format("%.1f", v)
    }

    // Invoked as /usr/local/bin/pocketlinux-microbench (see writeGuestScript).
    // Pure POSIX sh — no awk/python required (Arch minimal images often lack gawk).
    private val GUEST_SCRIPT_BODY = """
        |#!/bin/sh
        |export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        |# Epoch time as integer: prefer nanoseconds (GNU date), else whole seconds.
        |now() {
        |  d=`date +%s%N 2>/dev/null`
        |  case "${'$'}d" in
        |    *N*|""|*[!0-9]*) date +%s 2>/dev/null || echo 0 ;;
        |    *) echo "${'$'}d" ;;
        |  esac
        |}
        |# Elapsed milliseconds using shell arithmetic only (no awk/bc).
        |elapsed_ms() {
        |  s="${'$'}1"; e="${'$'}2"
        |  case "${'$'}s" in ""|*[!0-9]*) echo 0; return ;; esac
        |  case "${'$'}e" in ""|*[!0-9]*) echo 0; return ;; esac
        |  slen=${'$'}{#s}
        |  if [ "${'$'}slen" -gt 12 ]; then
        |    # nanoseconds → ms
        |    echo ${'$'}(( (e - s) / 1000000 ))
        |  else
        |    # seconds → ms
        |    echo ${'$'}(( (e - s) * 1000 ))
        |  fi
        |}
        |echo "=== BENCH_START uname=`uname -m` ==="
        |/bin/true; /bin/true
        |S=`now`; i=0
        |while [ ${'$'}i -lt 200 ]; do /bin/true; i=$((i+1)); done
        |E=`now`; echo "FORK_TRUE_200_ms=`elapsed_ms ${'$'}S ${'$'}E`"
        |S=`now`; i=0
        |while [ ${'$'}i -lt 100 ]; do /bin/sh -c "exit 0"; i=$((i+1)); done
        |E=`now`; echo "FORK_SH_100_ms=`elapsed_ms ${'$'}S ${'$'}E`"
        |S=`now`; i=0
        |while [ ${'$'}i -lt 500 ]; do
        |  [ -e /bin/sh ]; [ -e /usr/bin/env ]; [ -e /etc/os-release ]
        |  i=$((i+1))
        |done
        |E=`now`; echo "STAT_1500_ms=`elapsed_ms ${'$'}S ${'$'}E`"
        |S=`now`; i=0
        |while [ ${'$'}i -lt 100 ]; do cat /etc/os-release >/dev/null 2>&1; i=$((i+1)); done
        |E=`now`; echo "READ_OSRELEASE_100_ms=`elapsed_ms ${'$'}S ${'$'}E`"
        |S=`now`; i=0; s=0
        |while [ ${'$'}i -lt 5000 ]; do s=$((s+i)); i=$((i+1)); done
        |E=`now`; echo "COMPUTE_5000_ms=`elapsed_ms ${'$'}S ${'$'}E`"
        |S=`now`; i=0
        |while [ ${'$'}i -lt 100 ]; do
        |  echo bench > /tmp/pl_bench_${'$'}i.txt 2>/dev/null
        |  rm -f /tmp/pl_bench_${'$'}i.txt 2>/dev/null
        |  i=$((i+1))
        |done
        |E=`now`; echo "WRITE_UNLINK_100_ms=`elapsed_ms ${'$'}S ${'$'}E`"
        |PY=
        |if command -v python3 >/dev/null 2>&1; then PY=python3
        |elif command -v python >/dev/null 2>&1; then PY=python
        |fi
        |if [ -n "${'$'}PY" ]; then
        |  S=`now`
        |  ${'$'}PY -c "print(sum(range(100000)))" >/dev/null 2>&1
        |  E=`now`; echo "PYTHON_SUM_100k_ms=`elapsed_ms ${'$'}S ${'$'}E`"
        |else
        |  echo "PYTHON_SUM_100k_ms=SKIP"
        |fi
        |echo "=== BENCH_END ==="
    """.trimMargin()
}
