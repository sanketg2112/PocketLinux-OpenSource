package com.sg.linuxgo

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class BackupRestoreService : Service() {

    companion object {
        const val TAG = "BackupRestoreService"

        const val CHANNEL_BACKUP_RESTORE = "pocketlinux_backup_restore"
        const val NOTIF_ID = 2001

        const val ACTION_START_BACKUP  = "com.sg.linuxgo.action.START_BACKUP"
        const val ACTION_START_RESTORE = "com.sg.linuxgo.action.START_RESTORE"
        const val ACTION_ABORT         = "com.sg.linuxgo.action.ABORT"

        const val BROADCAST_START    = "lm.backup_restore.START"
        const val BROADCAST_PROGRESS = "lm.backup_restore.PROGRESS"
        const val BROADCAST_COMPLETE = "lm.backup_restore.COMPLETE"

        const val EXTRA_OPERATION = "operation" // "backup" or "restore"
        const val EXTRA_SUCCESS   = "success"
        const val EXTRA_MESSAGE   = "message"

        const val EXTRA_CONTAINER_ID = "container_id"
        const val EXTRA_BACKUP_NAME  = "backup_name"
        const val EXTRA_BACKUP_FILE_PATH = "backup_file_path"
        const val EXTRA_ORIGINAL_NAME = "original_name"
        const val EXTRA_BACKUP_PASSWORD_TOKEN = "backup_password_token"

        @Volatile var isRunning = false
        @Volatile var currentOperation: String? = null // "backup" or "restore"
        /** Last overall progress (0–100) for UI reconnect when activity was backgrounded. */
        @Volatile var lastProgress: Int = 0
        @Volatile var lastMessage: String = ""
        /** Set when an operation finishes; consumed by MainActivity on resume. */
        @Volatile var lastCompleteSuccess: Boolean? = null
        @Volatile var lastCompleteMessage: String = ""
        @Volatile var lastCompleteOperation: String? = null
        /** Restored (or backed-up) container id for completion UI / launch targets. */
        @Volatile var lastCompleteContainerId: String? = null
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var activeProcess: Process? = null
    private var backupFile: File? = null
    private var rootfsDir: File? = null
    private var metadataFile: File? = null
    private var serviceThread: Thread? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Rate-limit shade updates so SystemUI (Samsung status-bar clock) stays healthy. */
    private val notifUpdater = ThrottledNotificationUpdater(
        minIntervalMs = 600L,
        maxIntervalMs = 1500L
    ) { message, progress, _ ->
        publishProgressNotificationNow(message, progress)
    }

    override fun onCreate() {
        super.onCreate()
        // FGS contract first — channel + startForeground before any WakeLock work
        // (Crash D / ForegroundServiceDidNotStartInTime on strict OEMs).
        createNotificationChannel()
        try {
            startForeground(
                NOTIF_ID,
                NotificationCompat.Builder(this, CHANNEL_BACKUP_RESTORE)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("PocketLinux")
                    .setContentText("Preparing…")
                    .setOngoing(true)
                    .setSilent(true)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "onCreate startForeground failed", e)
        }
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::BackupRestoreLock").apply {
                acquire(60 * 60 * 1000L /* 1 hour max */)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock after FGS promote: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert FGS on every start before action work.
        try {
            publishProgressNotificationNow(lastMessage.ifBlank { "Working…" }, lastProgress)
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand re-promote failed", e)
        }
        when (intent?.action) {
            ACTION_ABORT -> {
                abortOperation()
            }
            ACTION_START_BACKUP -> {
                if (isRunning) {
                    sendToast("An operation is already in progress.")
                } else {
                    val containerId = intent.getStringExtra(EXTRA_CONTAINER_ID) ?: ""
                    val name = intent.getStringExtra(EXTRA_BACKUP_NAME) ?: "backup"
                    val password = BackupPasswordStore.take(
                        this,
                        intent.getStringExtra(EXTRA_BACKUP_PASSWORD_TOKEN)
                    )
                    startBackup(containerId, name, password)
                }
            }
            ACTION_START_RESTORE -> {
                if (isRunning) {
                    sendToast("An operation is already in progress.")
                } else {
                    val filePath = intent.getStringExtra(EXTRA_BACKUP_FILE_PATH) ?: ""
                    val originalName = intent.getStringExtra(EXTRA_ORIGINAL_NAME)
                    val password = BackupPasswordStore.take(
                        this,
                        intent.getStringExtra(EXTRA_BACKUP_PASSWORD_TOKEN)
                    )
                    startRestore(File(filePath), originalName, password)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        notifUpdater.reset()
        wakeLock?.let { if (it.isHeld) it.release() }
        cleanupProcess()
        isRunning = false
    }

    private fun sendToast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun startBackup(containerId: String, name: String, password: String? = null) {
        isRunning = true
        currentOperation = "backup"
        lastProgress = 0
        lastMessage = "Preparing backup..."
        lastCompleteSuccess = null
        lastCompleteMessage = ""
        lastCompleteOperation = null
        lastCompleteContainerId = null
        notifUpdater.reset()

        publishProgressNotificationNow("Preparing backup...", 0)
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(BROADCAST_START).putExtra(EXTRA_OPERATION, "backup"))

        serviceThread = Thread {
            try {
                val containerManager = ContainerManager(this)
                val container = containerManager.getContainer(containerId)
                val rootfs = if (container != null) {
                    File(containerManager.getContainerRootfsPath(containerId))
                } else {
                    File(filesDir, "rootfs")
                }
                rootfsDir = rootfs

                val sdcard = android.os.Environment.getExternalStorageDirectory()
                val backupDir = File(sdcard, "PocketLinux Backup")
                if (!backupDir.exists()) {
                    if (!backupDir.mkdirs()) {
                        Log.e(TAG, "Failed to create backup directory: ${backupDir.absolutePath}")
                        completeOperation(false, "Failed to create backup directory: ${backupDir.absolutePath}. Please check permissions.")
                        return@Thread
                    }
                }
                val toybox = ensureToyboxInFiles()
                val toyboxFile = File(toybox)

                if (!toyboxFile.exists()) {
                    completeOperation(false, "Backup engine missing")
                    return@Thread
                }
                toyboxFile.setExecutable(true, false)

                logToMini("Starting robust system backup to ${backupDir.absolutePath}...")
                logToMini("Checking engine: ${toyboxFile.exists()} | Exe: ${toyboxFile.canExecute()}")

                val restoreEngine = ContainerRestoreEngine(this)

                // Proot/pacman leave dirs 0555 → host EACCES writing metadata (common on Arch).
                try {
                    restoreEngine.ensureHostDirAccess(rootfs, maxDepth = 8) { logToMini(it) }
                } catch (e: Exception) {
                    logToMini("! Permission fix warning: ${e.message}")
                }

                // Arch leaves gpg-agent sockets under etc/pacman.d/gnupg; toybox tar
                // cannot archive sockets and marks the run as errors. Scrub first.
                try {
                    restoreEngine.scrubUnarchivableSpecialFiles(rootfs) { logToMini(it) }
                } catch (e: Exception) {
                    logToMini("! Socket scrub warning: ${e.message}")
                }

                broadcastProgress("Analyzing container size...", 2)
                val sourceSize = getDirectorySize(rootfs)
                val targetSize = if (sourceSize > 0) sourceSize / 3 else 300 * 1024 * 1024L
                logToMini("Container size: ${sourceSize / (1024 * 1024)} MB | Expected backup size: ${targetSize / (1024 * 1024)} MB")

                // Prepare metadata
                val configJson = container?.toJson()
                val prefsName = if (containerId.isNotEmpty()) "container_${containerId}_settings" else "pocket_linux_settings"
                val containerPrefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                val settingsJson = JSONObject()
                containerPrefs.all.forEach { (key, value) -> settingsJson.put(key, value) }

                val metadata = JSONObject().apply {
                    put("config", configJson)
                    put("settings", settingsJson)
                    put("version", 1)
                }
                val metadataText = metadata.toString()

                // Write metadata into rootfs (tmp is usually 1777 and always host-writable).
                val metaCandidates = listOf(
                    File(rootfs, ".pocketlinux_config.json"),
                    File(rootfs, "tmp/.pocketlinux_config.json"),
                    File(rootfs, "var/tmp/.pocketlinux_config.json")
                )
                var meta: File? = null
                for (candidate in metaCandidates) {
                    try {
                        candidate.parentFile?.let { parent ->
                            parent.mkdirs()
                            try {
                                android.system.Os.chmod(parent.absolutePath, 0x1ed) // 0755
                            } catch (_: Exception) {
                                parent.setWritable(true, true)
                            }
                        }
                        try {
                            android.system.Os.chmod(rootfs.absolutePath, 0x1ed)
                        } catch (_: Exception) {
                            rootfs.setWritable(true, false)
                        }
                        candidate.writeText(metadataText)
                        if (candidate.isFile && candidate.length() > 0) {
                            meta = candidate
                            metadataFile = candidate
                            if (candidate.name != ".pocketlinux_config.json" ||
                                candidate.parentFile != rootfs
                            ) {
                                // Also try root for clean peeks; ignore failure
                                try {
                                    File(rootfs, ".pocketlinux_config.json").writeText(metadataText)
                                } catch (_: Exception) {
                                }
                            }
                            logToMini("✓ Metadata written (${candidate.absolutePath.removePrefix(rootfs.absolutePath)})")
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "metadata write ${candidate}: ${e.message}")
                    }
                }
                if (meta == null) {
                    logToMini("! Warning: Could not embed metadata in rootfs (will use sidecar)")
                }

                // Sidecar next to the final backup — always, for restore distro detection
                try {
                    File(backupDir, "${name}.pocketlinux.json").writeText(metadataText)
                } catch (e: Exception) {
                    Log.w(TAG, "sidecar metadata: ${e.message}")
                }

                broadcastProgress("Compressing filesystem image...", 5)

                // Stage archive in app cache (reliable), then copy to shared storage.
                // Writing gzip streams directly to FUSE/sdcard has corrupted Arch backups.
                val staged = File(cacheDir, "backup_stage_${System.currentTimeMillis()}.tar.gz")
                backupFile = staged // abort deletes staged if cancelled mid-run

                // Include tmp metadata path in the archive (not under --exclude tmp/* files at root of tmp...
                // We exclude the whole tmp dir — so if metadata only landed in tmp/, move it to root.
                if (meta != null && meta.absolutePath.contains("/tmp/")) {
                    try {
                        val rootMeta = File(rootfs, ".pocketlinux_config.json")
                        meta.copyTo(rootMeta, overwrite = true)
                        meta = rootMeta
                        metadataFile = rootMeta
                        logToMini("✓ Metadata copied to rootfs root for archive")
                    } catch (e: Exception) {
                        // Exclude would drop tmp/ — write via a non-excluded path
                        try {
                            val alt = File(rootfs, "etc/pocketlinux_config.json")
                            alt.parentFile?.mkdirs()
                            alt.writeText(metadataText)
                            meta = alt
                            logToMini("✓ Metadata placed at etc/pocketlinux_config.json")
                        } catch (e2: Exception) {
                            logToMini("! Metadata may be missing from archive: ${e2.message}")
                        }
                    }
                }

                val pb = ProcessBuilder(
                    toybox, "tar",
                    "-C", rootfs.absolutePath,
                    "-czf", staged.absolutePath,
                    "--exclude=./tmp",
                    "--exclude=tmp",
                    "--exclude=./run",
                    "--exclude=run",
                    "--exclude=./proc",
                    "--exclude=proc",
                    "--exclude=./sys",
                    "--exclude=sys",
                    "--exclude=./dev",
                    "--exclude=dev",
                    "--exclude=./var/run",
                    "--exclude=var/run",
                    "--exclude=./etc/pacman.d/gnupg/S.*",
                    "--exclude=etc/pacman.d/gnupg/S.*",
                    "."
                ).redirectErrorStream(true)
                pb.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
                pb.environment()["PATH"] = restoreEngine.prepareToyboxArchiverPath(toybox)

                val process = pb.start()
                activeProcess = process
                val logOutput = java.lang.StringBuilder()

                val monitorThread = Thread {
                    try {
                        while (isRunning && activeProcess?.isAlive == true) {
                            if (staged.exists()) {
                                val currentLength = staged.length()
                                val sizeMB = currentLength / (1024 * 1024)
                                val pct = Math.min(90, ((currentLength.toDouble() / targetSize.toDouble()) * 90.0).toInt())
                                broadcastProgress("Compressing... $sizeMB MB written", pct)
                            }
                            Thread.sleep(2000)
                        }
                    } catch (_: Exception) {
                    }
                }
                monitorThread.start()

                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        logOutput.append(line).append("\n")
                    }
                }

                val exitCode = process.waitFor()
                activeProcess = null

                // Cleanup embedded metadata files
                listOf(
                    File(rootfs, ".pocketlinux_config.json"),
                    File(rootfs, "tmp/.pocketlinux_config.json"),
                    File(rootfs, "var/tmp/.pocketlinux_config.json"),
                    File(rootfs, "etc/pocketlinux_config.json")
                ).forEach { f ->
                    try {
                        if (f.exists()) f.delete()
                    } catch (_: Exception) {
                    }
                }

                val tarOk = exitCode == 0 || (exitCode == 1 && staged.exists() && staged.length() > 1024L)
                if (!tarOk) {
                    staged.delete()
                    completeOperation(false, "Backup failed (exit code $exitCode). tar output: ${logOutput.takeLast(500)}")
                    return@Thread
                }

                broadcastProgress("Verifying backup archive...", 92)
                if (!restoreEngine.verifyBackupArchive(staged, toybox)) {
                    val errTail = logOutput.takeLast(300)
                    staged.delete()
                    completeOperation(
                        false,
                        "Backup archive failed verification (corrupt or empty root). tar log: $errTail"
                    )
                    return@Thread
                }

                broadcastProgress("Saving to shared storage...", 95)
                val published: File
                try {
                    published = BackupArchiveIo.publishStagedArchive(
                        stagedTar = staged,
                        backupDir = backupDir,
                        name = name,
                        password = password,
                        onLog = { logToMini(it) }
                    )
                } catch (t: Throwable) {
                    staged.delete()
                    completeOperation(false, "Failed to save backup: ${t.message}")
                    return@Thread
                } finally {
                    staged.delete()
                }
                backupFile = published

                val sizeMB = published.length() / 1024 / 1024
                val status = if (exitCode == 0) "successful" else "completed with warnings"
                val locked = if (!password.isNullOrEmpty()) " (password-encrypted)" else " (not encrypted)"
                completeOperation(
                    true,
                    "Backup $status$locked — ${sizeMB} MB saved to ${published.absolutePath}",
                    containerId = containerId
                )

            } catch (t: Throwable) {
                Log.e(TAG, "Backup exception", t)
                completeOperation(false, "Backup failed: ${t.message}")
            }
        }
        serviceThread?.start()
    }

    private fun startRestore(file: File, originalName: String?, password: String? = null) {
        isRunning = true
        currentOperation = "restore"
        lastProgress = 0
        lastMessage = "Preparing restore..."
        lastCompleteSuccess = null
        lastCompleteMessage = ""
        lastCompleteOperation = null
        lastCompleteContainerId = null
        notifUpdater.reset()

        publishProgressNotificationNow("Preparing restore...", 0)
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(BROADCAST_START).putExtra(EXTRA_OPERATION, "restore"))

        serviceThread = Thread {
            var createdContainerId: String? = null
            try {
                val containerManager = ContainerManager(this)
                val engine = ContainerRestoreEngine(this)

                val archive = try {
                    BackupArchiveIo.prepareArchiveForRestore(
                        file = file,
                        password = password,
                        cacheDir = cacheDir,
                        onLog = { logToMini(it) }
                    )
                } catch (e: BackupCrypto.WrongPasswordException) {
                    completeOperation(false, "This backup is password-protected. Enter the password to restore.")
                    return@Thread
                } catch (e: Exception) {
                    completeOperation(false, "Could not open backup: ${e.message}")
                    return@Thread
                }

                val rawName = (originalName ?: file.name)
                    .replace(BackupCrypto.FILE_SUFFIX, "")
                    .replace(".tar.gz", "")
                    .replace(".tar.xz", "")
                    .replace(".tgz", "")
                    .replace(".tar", "")
                    .replace("restore_temp", "")
                    .replace("Restore", "", ignoreCase = true)
                    .replace("_", " ")
                    .replace("-", " ")
                    .trim()

                // Prefer sidecar / embedded metadata (correct distro) over filename.
                val peekMeta = try {
                    val sidecarText = BackupArchiveIo.readSidecarText(file)
                    val fromSidecar = if (!sidecarText.isNullOrBlank()) {
                        try {
                            JSONObject(sidecarText).also {
                                logToMini("✓ Read sidecar metadata: ${BackupArchiveIo.expectedSidecarFile(file).name}")
                            }
                        } catch (_: Throwable) {
                            null
                        }
                    } else {
                        null
                    }
                    fromSidecar ?: engine.peekBackupMetadata(archive)
                } catch (e: Exception) {
                    Log.w(TAG, "peek metadata: ${e.message}")
                    null
                }
                val peekConfig = peekMeta?.optJSONObject("config")
                val metaDistro = peekConfig?.optString("distro", "")?.takeIf { it.isNotBlank() }
                val metaDe = peekConfig?.optString("de", "")?.takeIf { it.isNotBlank() }
                val metaWm = peekConfig?.optString("wm", "")?.takeIf { it.isNotBlank() }
                val metaUser = peekConfig?.optString("username", "")?.takeIf {
                    it.isNotBlank() && !it.equals("root", ignoreCase = true)
                }
                val metaName = peekConfig?.optString("name", "")?.takeIf { it.isNotBlank() }
                val metaGui = peekConfig?.optString("guiMode", "")?.takeIf { it.isNotBlank() }

                val distro = metaDistro
                    ?: engine.detectDistroFromName(rawName)
                    ?: engine.detectDistroFromName(originalName ?: file.name)
                    ?: "alpine"

                val displayName = when {
                    !metaName.isNullOrBlank() -> "Restored: $metaName"
                    rawName.isEmpty() || rawName.equals("temp", ignoreCase = true) -> "Restored Backup"
                    rawName.contains("alpine", ignoreCase = true) -> "Restored: Alpine"
                    rawName.contains("kali", ignoreCase = true) -> "Restored: Kali Linux"
                    rawName.contains("debian", ignoreCase = true) -> "Restored: Debian"
                    rawName.contains("ubuntu", ignoreCase = true) -> "Restored: Ubuntu"
                    rawName.contains("arch", ignoreCase = true) -> "Restored: Arch Linux"
                    distro == "kali" -> "Restored: Kali Linux"
                    distro == "archlinux" -> "Restored: Arch Linux"
                    else -> rawName.split(" ").filter { it.isNotEmpty() }
                        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                }

                if (peekMeta != null) {
                    logToMini("✓ Read backup metadata (distro=$distro)")
                } else {
                    logToMini("ℹ No embedded metadata; using distro hint: $distro")
                }

                // Placeholder username; after extract we sync from metadata + /home + passwd.
                val config = containerManager.createNewContainer(
                    distro = distro,
                    de = metaDe ?: "xfce4",
                    wm = metaWm ?: "none",
                    software = emptyList(),
                    username = metaUser ?: "PocketLinux",
                    customName = displayName,
                    guiMode = metaGui ?: "x11"
                )
                if (!containerManager.addContainer(config)) {
                    completeOperation(false, "Cannot restore: maximum number of containers reached.")
                    return@Thread
                }
                createdContainerId = config.id
                val containerId = config.id
                val rootfs = File(containerManager.getContainerRootfsPath(containerId))
                rootfsDir = rootfs

                logToMini("Starting full system restore from ${file.absolutePath}...")
                logToMini("Backup file size: ${archive.length() / (1024 * 1024)} MB (target distro: $distro)")

                killStaleVncProcess()

                // Same engine used by golden-image install (toybox symlink + tar extract)
                var extractError: String? = null
                try {
                    engine.extractBackupIntoRootfs(
                        archive = archive,
                        rootfsDir = rootfs,
                        onProgress = { fraction, message ->
                            val pct = (5 + (fraction * 90f)).toInt().coerceIn(5, 95)
                            broadcastProgress(message, pct)
                        },
                        isCancelled = { !isRunning },
                        scanMemberPaths = true
                    )
                } catch (e: Exception) {
                    extractError = e.message
                    Log.e(TAG, "Restore extract failed", e)
                }

                val isSuccess = if (extractError == null) {
                    engine.validateAndFix(rootfs, containerId, containerManager) { logToMini(it) }
                } else {
                    logToMini("! $extractError")
                    false
                }

                if (isSuccess) {
                    // Metadata first, then force username from real rootfs user
                    engine.applyEmbeddedMetadata(rootfs, containerId, containerManager) { logToMini(it) }
                    val restoredUser = engine.syncContainerUsername(
                        rootfs, containerId, containerManager,
                        onLog = { logToMini(it) }
                    )
                    logToMini("✓ Restored session user: $restoredUser")
                    try {
                        engine.installProotHelpers(rootfs) { logToMini(it) }
                    } catch (e: Exception) {
                        logToMini("! Proot helpers: ${e.message}")
                    }
                    // Arch again after helpers (xkb / pacman SigLevel)
                    try {
                        engine.prepareArchAfterRestore(rootfs) { logToMini(it) }
                    } catch (e: Exception) {
                        logToMini("! Arch post-restore: ${e.message}")
                    }
                    // Force identity files even if installProotHelpers ran first with a
                    // placeholder — this is what makes whoami/PS1 show the backup user.
                    engine.applySessionIdentity(rootfs, restoredUser) { logToMini(it) }

                    // Mark container installed and rewrite launch scripts as restored user
                    val container = containerManager.getContainer(containerId)
                    if (container != null) {
                        if (!container.isInstalled) {
                            containerManager.updateContainer(container.copy(isInstalled = true))
                        }
                        val ready = containerManager.getContainer(containerId) ?: container
                        val bootstrap = Bootstrap(this)
                        bootstrap.applyContainerPreset(ready.copy(username = restoredUser))
                        bootstrap.setupDisplayConfig(rootfs)
                    }

                    // Keep the container (do not clean up); still report its id for the
                    // completion dialog so Launch Desktop/Terminal targets this card.
                    val finishedId = containerId
                    createdContainerId = null
                    completeOperation(
                        true,
                        "Restore complete — system ready (user: $restoredUser)",
                        containerId = finishedId
                    )
                } else {
                    // Drop the half-created container so a failed Arch restore
                    // does not leave a broken Alpine-labelled card behind.
                    try {
                        createdContainerId?.let { containerManager.removeContainer(it) }
                    } catch (e: Exception) {
                        Log.w(TAG, "cleanup failed container: ${e.message}")
                    }
                    createdContainerId = null
                    completeOperation(false, "Restore failed: ${extractError ?: "validation failed"}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Restore exception", e)
                try {
                    createdContainerId?.let { ContainerManager(this).removeContainer(it) }
                } catch (_: Exception) {
                }
                completeOperation(false, "Restore failed: ${e.message}")
            } catch (t: Throwable) {
                Log.e(TAG, "Restore fatal", t)
                try {
                    createdContainerId?.let { ContainerManager(this).removeContainer(it) }
                } catch (_: Exception) {
                }
                completeOperation(false, "Restore failed: ${t.javaClass.simpleName}")
            }
        }
        serviceThread?.start()
    }

    private fun abortOperation() {
        if (!isRunning) return
        logToMini("! Operation aborted by user")
        cleanupProcess()
        
        if (currentOperation == "backup") {
            try {
                backupFile?.let {
                    if (it.exists()) {
                        it.delete()
                        Log.d(TAG, "Deleted aborted backup file: ${it.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete aborted backup file", e)
            }
        }
        
        completeOperation(false, "${currentOperation?.replaceFirstChar { it.uppercase() }} aborted")
    }

    private fun cleanupProcess() {
        try {
            activeProcess?.destroy()
        } catch (e: Exception) {}
        activeProcess = null
        
        try {
            metadataFile?.let {
                if (it.exists()) it.delete()
            }
        } catch (e: Exception) {}
        metadataFile = null
    }

    private fun completeOperation(
        success: Boolean,
        message: String,
        containerId: String? = null
    ) {
        isRunning = false
        val op = currentOperation ?: "backup"

        logToMini(if (success) "✓ $message" else "! $message")

        // Snapshot for UI reconnect if COMPLETE broadcast is missed while paused.
        lastProgress = if (success) 100 else lastProgress
        lastMessage = message
        lastCompleteSuccess = success
        lastCompleteMessage = message
        lastCompleteOperation = op
        lastCompleteContainerId = if (success) containerId else null

        val intent = Intent(BROADCAST_COMPLETE).apply {
            putExtra(EXTRA_OPERATION, op)
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_MESSAGE, message)
            if (containerId != null) putExtra(EXTRA_CONTAINER_ID, containerId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        if (success) {
            // Auto-dismiss progress notification (same behavior as container install).
            dismissProgressNotification(
                finalMessage = if (op == "backup") "Backup complete" else "Restore complete",
                finalProgress = 100
            )
        } else {
            // Keep a failure notification the user can read/dismiss; drop the ongoing FGS.
            val finalTitle = if (op == "backup") "Backup failed" else "Restore failed"
            notifUpdater.cancelPending()
            try {
                postFailureNotification(finalTitle, message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to post failure notification", e)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION") stopForeground(true)
                }
            } catch (_: Exception) {
            }
            // Failure uses NOTIF_ID via notify after remove — re-post so it stays.
            try {
                postFailureNotification(finalTitle, message)
            } catch (_: Exception) {
            }
        }
        stopSelf()
    }

    private fun broadcastProgress(message: String, progress: Int = 0) {
        // Extract's size-walk monitor can finish after completeOperation.
        // Do not reopen the Settings bar or wipe the completion snapshot.
        if (!isRunning) return
        val pct = progress.coerceIn(0, 100)
        // Monotonic so out-of-order monitor ticks never reverse the bar.
        val effective = if (lastProgress in 0..100) maxOf(lastProgress, pct) else pct
        lastProgress = effective
        lastMessage = message

        val op = currentOperation ?: "backup"
        val intent = Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_OPERATION, op)
            // Same overall % the notification bar uses.
            putExtra(EXTRA_MESSAGE, "$message|$effective")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        // Throttled — in-app banner still gets every broadcast above.
        notifUpdater.update(
            message = message,
            progress = effective,
            deterministic = true,
            force = effective >= 100 || effective == 0
        )
    }

    private fun getDirectorySize(dir: File): Long {
        var size = 0L
        val stack = java.util.Stack<File>()
        stack.push(dir)
        val rootPath = dir.absolutePath
        while (stack.isNotEmpty()) {
            val current = stack.pop()
            if (java.nio.file.Files.isSymbolicLink(current.toPath())) {
                continue
            }
            
            // Check relative path to exclude matched directories
            val relPath = current.absolutePath.substringAfter(rootPath).trimStart('/')
            if (relPath.startsWith("tmp") || 
                relPath.startsWith("run") || 
                relPath.startsWith("proc") || 
                relPath.startsWith("sys") || 
                relPath.startsWith("dev") || 
                relPath.startsWith("var/run")) {
                continue
            }
            
            val files = current.listFiles()
            if (files != null) {
                for (f in files) {
                    if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                        continue
                    }
                    val fRelPath = f.absolutePath.substringAfter(rootPath).trimStart('/')
                    if (fRelPath.startsWith("tmp") || 
                        fRelPath.startsWith("run") || 
                        fRelPath.startsWith("proc") || 
                        fRelPath.startsWith("sys") || 
                        fRelPath.startsWith("dev") || 
                        fRelPath.startsWith("var/run")) {
                        continue
                    }
                    if (f.isDirectory) {
                        stack.push(f)
                    } else {
                        size += f.length()
                    }
                }
            }
        }
        return size
    }

    private fun logToMini(message: String) {
        val intent = Intent(SetupForegroundService.BROADCAST_LOG_LINE).apply {
            putExtra(SetupForegroundService.EXTRA_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun killStaleVncProcess() {
        try {
            // Find and kill running Xvnc or vnc processes
            val pb = ProcessBuilder("pkill", "-f", "Xvnc")
            pb.start().waitFor()
        } catch (e: Exception) {}
    }

    /** Backup still needs toybox path for tar -c. */
    private fun ensureToyboxInFiles(): String {
        return ContainerRestoreEngine(this).ensureToyboxPath()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_BACKUP_RESTORE,
                "Linux System Backup & Restore",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of container backup or restore"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun mainActivityPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, 0, openIntent, flags)
    }

    private fun buildProgressNotification(message: String, progress: Int = 0): Notification {
        val abortIntent = Intent(this, BackupRestoreService::class.java).apply {
            action = ACTION_ABORT
        }
        val abortPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getService(this, 101, abortIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 101, abortIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        return NotificationCompat.Builder(this, CHANNEL_BACKUP_RESTORE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (currentOperation == "backup") "PocketLinux Backup" else "PocketLinux Restore")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(mainActivityPendingIntent())
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Abort",
                abortPendingIntent
            )
            .build()
    }

    /** Immediate SystemUI publish — only from throttler or FGS start. */
    private fun publishProgressNotificationNow(message: String, progress: Int = 0) {
        val notif = buildProgressNotification(message, progress)
        try {
            startForeground(NOTIF_ID, notif)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update progress notification via startForeground", e)
            try {
                val nm = NotificationManagerCompat.from(this)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    nm.areNotificationsEnabled()
                ) {
                    nm.notify(NOTIF_ID, notif)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to update notification", e2)
            }
        }
    }

    private fun dismissProgressNotification(finalMessage: String, finalProgress: Int) {
        notifUpdater.cancelPending()
        try {
            publishProgressNotificationNow(finalMessage, finalProgress)
        } catch (_: Exception) {
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground remove: ${e.message}")
        }
        mainHandler.post {
            try {
                getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
            } catch (_: Exception) {
            }
        }
        mainHandler.postDelayed({
            try {
                getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
            } catch (_: Exception) {
            }
        }, 800L)
    }

    /** Non-ongoing failure notice the user can swipe away. */
    private fun postFailureNotification(title: String, message: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_BACKUP_RESTORE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(mainActivityPendingIntent())
            .build()
        try {
            val nm = NotificationManagerCompat.from(this)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                nm.areNotificationsEnabled()
            ) {
                nm.notify(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post failure notification", e)
        }
    }
}
