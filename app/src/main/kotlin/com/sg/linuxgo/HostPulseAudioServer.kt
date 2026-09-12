package com.sg.linuxgo

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Host-side PulseAudio for proot guests (same model as Wayland [pulse_server.rs]).
 *
 * - Daemon: `nativeLibraryDir/libpulseaudio_exec.so` (Termux pulseaudio 17)
 * - Guest: `PULSE_SERVER=tcp:127.0.0.1:14713` (native protocol, auth-anonymous)
 * - Output: `module-aaudio-sink` → Android audio
 *
 * Used for **X11** so audio does not depend on a fragile guest null-sink +
 * simple-protocol-tcp → AudioTrack bridge.
 */
object HostPulseAudioServer {
    private const val TAG = "HostPulseAudio"
    const val TCP_PORT = 14713
    private const val TCP_HOST = "127.0.0.1"

    private val daemonLib = "libpulseaudio_exec.so"
    private val protocolModule = "module-native-protocol-tcp.so"
    private val aaudioModule = "module-aaudio-sink.so"
    private val protocolHelper = "libprotocol-native.so"

    private val processRef = AtomicReference<Process?>(null)
    private val ready = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private val lastError = AtomicReference<String?>(null)

    fun isReady(): Boolean = ready.get() && tcpConnectable()

    fun lastError(): String? = lastError.get()

    /**
     * Ensure the host daemon is listening. Safe to call from any thread; blocks
     * until ready or failure (typically < 2s).
     */
    @Synchronized
    fun ensureRunning(context: Context, onLog: (String) -> Unit = {}): Boolean {
        if (isReady()) {
            onLog("🔊 Host PulseAudio already running on $TCP_HOST:$TCP_PORT")
            return true
        }
        if (!starting.compareAndSet(false, true)) {
            // Another thread is starting — wait briefly
            repeat(40) {
                if (isReady()) return true
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            return isReady()
        }
        try {
            lastError.set(null)
            return startDaemon(context, onLog)
        } finally {
            starting.set(false)
        }
    }

    fun shutdown() {
        try {
            processRef.getAndSet(null)?.let { p ->
                try {
                    p.destroyForcibly()
                } catch (_: Exception) {
                }
                try {
                    p.waitFor()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        ready.set(false)
    }

    private fun startDaemon(context: Context, onLog: (String) -> Unit): Boolean {
        // Drop any previous host/guest PA holding the port
        try {
            Runtime.getRuntime().exec(arrayOf("pkill", "-9", "-f", "libpulseaudio_exec")).waitFor()
        } catch (_: Exception) {
        }
        try {
            Runtime.getRuntime().exec(arrayOf("pkill", "-9", "-f", "pulseaudio")).waitFor()
        } catch (_: Exception) {
        }
        try {
            Thread.sleep(150)
        } catch (_: InterruptedException) {
        }

        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val dataDir = context.filesDir
        val binary = File(libDir, daemonLib)
        if (!binary.isFile) {
            val msg = "Host PA: missing $daemonLib in ${libDir.absolutePath}"
            Log.e(TAG, msg)
            lastError.set(msg)
            onLog("! $msg")
            return false
        }
        val helper = File(libDir, protocolHelper)
        if (!helper.isFile) {
            val msg = "Host PA: missing $protocolHelper"
            Log.e(TAG, msg)
            lastError.set(msg)
            onLog("! $msg")
            return false
        }

        val configDir = File(dataDir, "pulse/config")
        val runtimeDir = File(dataDir, "pulse/runtime")
        val stateDir = File(dataDir, "pulse/state")
        val modulesDir = File(dataDir, "pulse/modules")
        listOf(configDir, runtimeDir, stateDir, modulesDir).forEach { d ->
            if (d.exists()) {
                // wipe runtime only
            } else {
                d.mkdirs()
            }
        }
        // Clean stale runtime sockets
        if (runtimeDir.exists()) {
            runtimeDir.listFiles()?.forEach { it.deleteRecursively() }
        }
        runtimeDir.mkdirs()

        try {
            extractAsset(context, protocolModule, File(modulesDir, protocolModule))
            extractAsset(context, aaudioModule, File(modulesDir, aaudioModule))
        } catch (e: Exception) {
            val msg = "Host PA: module extract failed: ${e.message}"
            Log.e(TAG, msg, e)
            lastError.set(msg)
            onLog("! $msg")
            return false
        }
        modulesDir.listFiles()?.forEach { f ->
            if (f.extension == "so") {
                f.setReadable(true, false)
                f.setExecutable(true, false)
            }
        }

        val protocol = File(modulesDir, protocolModule)
        val aaudio = File(modulesDir, aaudioModule)
        if (!protocol.isFile || !aaudio.isFile) {
            val msg = "Host PA: modules missing (protocol=${protocol.isFile} aaudio=${aaudio.isFile})"
            Log.e(TAG, msg)
            lastError.set(msg)
            onLog("! $msg")
            return false
        }

        val defaultPa = File(configDir, "default.pa")
        defaultPa.writeText(
            """
            # PocketLinux host PulseAudio — proot guest audio (native TCP, AAudio)
            .fail
            load-module module-native-protocol-tcp port=$TCP_PORT listen=$TCP_HOST auth-anonymous=1 auth-ip-acl=127.0.0.1
            load-module module-aaudio-sink
            """.trimIndent() + "\n"
        )
        File(configDir, "client.conf").writeText("autospawn = no\ndaemon-binary = ${binary.absolutePath}\n")
        File(configDir, "daemon.conf").writeText(
            """
            daemonize = no
            exit-idle-time = -1
            flat-volumes = yes
            log-level = info
            log-target = stderr
            dl-search-path = ${modulesDir.absolutePath}
            """.trimIndent() + "\n"
        )

        val ldPath = "${libDir.absolutePath}:${modulesDir.absolutePath}"
        val pb = ProcessBuilder(
            binary.absolutePath,
            "-n",
            "--verbose",
            "--exit-idle-time=-1",
            "--file=${defaultPa.absolutePath}",
            "--dl-search-path=${modulesDir.absolutePath}"
        )
        pb.environment().apply {
            put("HOME", dataDir.absolutePath)
            put("PULSE_RUNTIME_PATH", runtimeDir.absolutePath)
            put("PULSE_STATE_PATH", stateDir.absolutePath)
            put("PULSE_CONFIG_PATH", configDir.absolutePath)
            put("LD_LIBRARY_PATH", ldPath)
        }
        pb.redirectErrorStream(true)
        pb.directory(dataDir)

        onLog("🔊 Starting host PulseAudio ($TCP_HOST:$TCP_PORT)...")
        Log.i(TAG, "spawn ${binary.absolutePath} LD_LIBRARY_PATH=$ldPath")

        val process = try {
            pb.start()
        } catch (e: Exception) {
            val msg = "Host PA spawn failed: ${e.message}"
            Log.e(TAG, msg, e)
            lastError.set(msg)
            onLog("! $msg")
            return false
        }
        processRef.set(process)

        // Drain logs
        Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        Log.i(TAG, "[daemon] $line")
                    }
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            name = "host-pulse-log"
            start()
        }

        // Wait for TCP
        for (attempt in 1..80) {
            if (!process.isAlive) {
                val msg = "Host PA exited before listen (attempt $attempt)"
                Log.e(TAG, msg)
                lastError.set(msg)
                onLog("! $msg")
                ready.set(false)
                processRef.set(null)
                return false
            }
            if (tcpConnectable()) {
                ready.set(true)
                onLog("✓ Host PulseAudio ready on $TCP_HOST:$TCP_PORT")
                Log.i(TAG, "ready after $attempt attempts")
                return true
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            }
        }
        val msg = "Host PA timed out waiting for $TCP_HOST:$TCP_PORT"
        Log.e(TAG, msg)
        lastError.set(msg)
        onLog("! $msg")
        try {
            process.destroyForcibly()
        } catch (_: Exception) {
        }
        processRef.set(null)
        ready.set(false)
        return false
    }

    private fun tcpConnectable(): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(TCP_HOST, TCP_PORT), 200)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun extractAsset(context: Context, assetName: String, dest: File) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            val bytes = input.readBytes()
            if (dest.isFile && dest.length() == bytes.size.toLong()) {
                return
            }
            dest.outputStream().use { out -> out.write(bytes) }
        }
        dest.setReadable(true, false)
        dest.setExecutable(true, false)
    }
}
