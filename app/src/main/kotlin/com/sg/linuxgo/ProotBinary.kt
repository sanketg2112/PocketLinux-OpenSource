package com.sg.linuxgo

import android.content.Context
import androidx.preference.PreferenceManager
import android.util.Log
import java.io.File

/**
 * Resolves which rootless runtime binary to exec:
 * - classic Termux proot ([libproot.so])
 * - experimental [wmww/tawc tawcroot](https://github.com/wmww/tawc) ([libtawcroot.so])
 *
 * Modes (Experimental settings):
 * - classic — always libproot.so
 * - tawcroot — always libtawcroot.so when present
 * - tawcroot_hybrid — routes classic vs tawcroot from last tawcroot performance check
 *
 * Stale prefs `proroot` / `hybrid` / `auto` map to classic (ProRoot is no longer shipped).
 * tawcroot CLI: `tawcroot -r ROOTFS [-b SRC:DST[:ro]]... -- CMD` (systrap, static ET_EXEC).
 */
object ProotBinary {
    private const val TAG = "ProotBinary"

    /** Legacy boolean; still written false so older readers cannot re-enable ProRoot. */
    const val PREF_ENABLE_PROROOT = "enableProroot"

    /**
     * classic | tawcroot | tawcroot_hybrid
     * (legacy "proroot" / "hybrid" / "auto" migrate to classic)
     */
    const val PREF_RUNTIME_MODE = "prootRuntimeMode"

    enum class Engine {
        CLASSIC,
        TAWCROOT
    }

    enum class Mode(val value: String) {
        CLASSIC("classic"),
        TAWCROOT("tawcroot"),
        TAWCROOT_HYBRID("tawcroot_hybrid");

        companion object {
            fun from(raw: String?): Mode {
                return when (raw) {
                    "tawcroot" -> TAWCROOT
                    "tawcroot_hybrid" -> TAWCROOT_HYBRID
                    else -> CLASSIC
                }
            }
        }
    }

    /** Why the runtime is being resolved — drives hybrid routing. */
    enum class Purpose {
        GENERAL,
        TERMINAL,
        DESKTOP,
        SETUP
    }

    data class Runtime(
        val launcherPath: String,
        val engine: Engine,
        val nativeLibDir: String,
        /** proot ptrace loader path; null for tawcroot. */
        val loaderPath: String?,
        val mode: Mode = Mode.CLASSIC,
        val purpose: Purpose = Purpose.GENERAL
    ) {
        val useTawcroot: Boolean get() = engine == Engine.TAWCROOT
        /** tawcroot requires `-b host:guest` (not short `-b /dev`). */
        val requiresHostGuestBinds: Boolean get() = useTawcroot
    }

    fun getMode(context: Context): Mode {
        if (!FeatureGates.experimentalRuntimeAllowed()) return Mode.CLASSIC
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return Mode.from(prefs.getString(PREF_RUNTIME_MODE, null))
    }

    fun setMode(context: Context, mode: Mode) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_RUNTIME_MODE, mode.value)
            .putBoolean(PREF_ENABLE_PROROOT, false)
            .apply()
    }

    fun isTawcrootToggleEnabled(context: Context): Boolean {
        return getMode(context) == Mode.TAWCROOT
    }

    fun isTawcrootBinaryPresent(context: Context): Boolean {
        val f = File(context.applicationInfo.nativeLibraryDir, "libtawcroot.so")
        return f.exists()
    }

    fun classic(context: Context, mode: Mode = Mode.CLASSIC, purpose: Purpose = Purpose.GENERAL): Runtime {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        return Runtime(
            launcherPath = File(nativeLibDir, "libproot.so").absolutePath,
            engine = Engine.CLASSIC,
            nativeLibDir = nativeLibDir,
            loaderPath = File(nativeLibDir, "libprootloader.so").absolutePath,
            mode = mode,
            purpose = purpose
        )
    }

    fun tawcrootOrNull(context: Context, mode: Mode = Mode.TAWCROOT, purpose: Purpose = Purpose.GENERAL): Runtime? {
        if (!isTawcrootBinaryPresent(context)) return null
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        return Runtime(
            launcherPath = File(nativeLibDir, "libtawcroot.so").absolutePath,
            engine = Engine.TAWCROOT,
            nativeLibDir = nativeLibDir,
            loaderPath = null,
            mode = mode,
            purpose = purpose
        )
    }

    fun resolve(context: Context, purpose: Purpose = Purpose.GENERAL): Runtime {
        val mode = getMode(context)
        when (mode) {
            Mode.CLASSIC -> return classic(context, mode, purpose)
            Mode.TAWCROOT -> {
                val t = tawcrootOrNull(context, mode, purpose)
                if (t != null) {
                    Log.i(TAG, "Using tawcroot (mode=${mode.value}, purpose=$purpose)")
                    return t
                }
                Log.w(TAG, "tawcroot requested but libtawcroot.so missing — classic proot")
                return classic(context, mode, purpose)
            }
            Mode.TAWCROOT_HYBRID -> {
                if (TawcrootMicrobench.hybridUseTawcroot(context, purpose)) {
                    val t = tawcrootOrNull(context, mode, purpose)
                    if (t != null) {
                        Log.i(TAG, "Hybrid → tawcroot (purpose=$purpose)")
                        return t
                    }
                }
                return classic(context, mode, purpose)
            }
        }
    }

    fun applyHostEnv(env: MutableMap<String, String>, context: Context, runtime: Runtime) {
        when (runtime.engine) {
            Engine.TAWCROOT -> {
                // Static non-PIE ET_EXEC — needs no host linker path. Critically,
                // tawcroot feeds process envp into the first guest ELF; leaving
                // Android LD_LIBRARY_PATH (or a full JVM env) breaks guest
                // /lib/ld-linux and aborts desktop/terminal sessions.
                env.remove("LD_LIBRARY_PATH")
                env.remove("LD_PRELOAD")
                env.remove("PROOT_LOADER")
                env.remove("PROOT_TMP_DIR")
                env.remove("PROROOT_TMP_DIR")
            }
            Engine.CLASSIC -> {
                env["LD_LIBRARY_PATH"] = runtime.nativeLibDir
                runtime.loaderPath?.let { env["PROOT_LOADER"] = it }
                env["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
                env.remove("PROROOT_TMP_DIR")
            }
        }
    }

    /**
     * Prepare ProcessBuilder environment for [runtime].
     * tawcroot: wipe inherited Android env (keeps only a host TMPDIR).
     */
    fun configureProcessBuilderEnv(
        env: MutableMap<String, String>,
        context: Context,
        runtime: Runtime
    ) {
        if (runtime.useTawcroot) {
            env.clear()
            env["TMPDIR"] = context.cacheDir.absolutePath
            applyHostEnv(env, context, runtime)
            return
        }
        applyHostEnv(env, context, runtime)
    }

    fun hostEnvPairs(context: Context, runtime: Runtime = resolve(context)): Array<String> {
        val map = mutableMapOf<String, String>()
        applyHostEnv(map, context, runtime)
        return map.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    /**
     * tawcroot only accepts `-b host:guest`. Classic proot accepts both.
     */
    fun bindSpec(path: String, requireHostGuest: Boolean): String {
        if (!requireHostGuest) return path
        return if (path.contains(':')) path else "$path:$path"
    }

    fun bindSpec(host: String, guest: String, requireHostGuest: Boolean = true): String {
        return "$host:$guest"
    }

    /**
     * Optional guest chdir for engines without proot's `-w` (tawcroot).
     * Portable: `sh -c 'cd "$1" && shift; exec "$@"' _ WORKDIR CMD…`
     */
    fun withGuestWorkingDir(workDir: String, guestArgv: List<String>): List<String> {
        val wd = workDir.trim().ifBlank { "/" }
        if (wd == "/" || guestArgv.isEmpty()) return guestArgv
        return listOf(
            "/bin/sh",
            "-c",
            "cd \"\$1\" || exit 1; shift; exec \"\$@\"",
            "pl-cwd",
            wd
        ) + guestArgv
    }

    fun modeLabel(mode: Mode): String = when (mode) {
        Mode.CLASSIC -> "Classic proot"
        Mode.TAWCROOT -> "tawcroot always"
        Mode.TAWCROOT_HYBRID -> "tawcroot hybrid (from check)"
    }

    fun modeSummary(mode: Mode): String = when (mode) {
        Mode.CLASSIC -> "All sessions use Termux proot (safe default)"
        Mode.TAWCROOT -> "All sessions use tawcroot (systrap) when libtawcroot.so is present"
        Mode.TAWCROOT_HYBRID ->
            "Routes by last tawcroot check: terminal/setup use fork winner; desktop uses " +
                "file metrics. Run the tawcroot performance check first."
    }

    fun engineLabel(engine: Engine): String = when (engine) {
        Engine.CLASSIC -> "classic proot"
        Engine.TAWCROOT -> "tawcroot"
    }
}
