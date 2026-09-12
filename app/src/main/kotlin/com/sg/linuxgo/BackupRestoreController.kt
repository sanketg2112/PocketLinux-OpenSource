package com.sg.linuxgo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.io.FileOutputStream

class BackupRestoreController(
    private val activity: Activity,
    private val containerManager: ContainerManager,
    private val containerAdapterProvider: () -> ContainerCardController?,
    private val pickBackupLauncher: ActivityResultLauncher<Array<String>>,
    private val onLogToMini: (String) -> Unit,
    private val onSetupCompleteCall: () -> Unit
) {

    fun confirmBackup() {
        if (!checkStoragePermission()) return
        val installedContainers = containerManager.getContainers().filter { it.isInstalled }
        
        if (installedContainers.isEmpty()) {
            Toast.makeText(activity, "No installed containers to backup", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (installedContainers.size == 1) {
            showBackupNameDialog(installedContainers[0])
            return
        }
        
        // Show selection dialog
        val names = installedContainers.map { it.name }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Select Container to Backup")
            .setItems(names) { _, which ->
                showBackupNameDialog(installedContainers[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showBackupNameDialog(container: ContainerConfig) {
        val cleanDefault = container.name.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val pad = (16 * activity.resources.displayMetrics.density).toInt()

        val nameInput = EditText(activity).apply {
            setText(cleanDefault)
            hint = "Backup filename"
        }
        val passwordInput = EditText(activity).apply {
            hint = "Password (recommended)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val warning = TextView(activity).apply {
            text = "Saved to /sdcard/PocketLinux Backup/. Without a password this is a plain archive. " +
                "It can contain SSH keys, browser profiles, Git credentials, and shell history."
            textSize = 13f
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(warning)
            addView(nameInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad })
            addView(passwordInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 })
        }

        AlertDialog.Builder(activity)
            .setTitle("Backup: ${container.name}")
            .setView(layout)
            .setPositiveButton("Backup") { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { cleanDefault }
                val password = passwordInput.text.toString()
                if (password.isEmpty()) {
                    confirmUnencryptedBackup(container.id, name)
                } else {
                    performBackup(container.id, name, password)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmUnencryptedBackup(containerId: String, name: String) {
        AlertDialog.Builder(activity)
            .setTitle("Save without encryption?")
            .setMessage(
                "This archive is not encrypted. Anyone who can read shared storage can open it, " +
                    "including SSH keys and browser data inside the distro."
            )
            .setPositiveButton("Save anyway") { _, _ ->
                performBackup(containerId, name, null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun confirmRestore() {
        if (!checkStoragePermission()) return
        
        val sdcard = Environment.getExternalStorageDirectory()
        val backupDir = File(sdcard, "PocketLinux Backup")
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }
        
        val localFiles = if (backupDir.exists()) {
            backupDir.listFiles()?.filter { BackupArchiveIo.isBackupFileName(it.name) } ?: emptyList()
        } else {
            emptyList()
        }
        
        val options = localFiles.map { it.name }.toMutableList()
        options.add("📁 BROWSE...")
        
        AlertDialog.Builder(activity)
            .setTitle("Restore System")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == options.size - 1) {
                    pickBackupLauncher.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream"))
                } else {
                    // Restore always creates a new container — never wipes existing ones.
                    restoreAskingPasswordIfNeeded(localFiles[which])
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun performBackup(containerId: String, name: String, password: String? = null) {
        if (!checkStoragePermission()) return
        val intent = Intent(activity, BackupRestoreService::class.java).apply {
            action = BackupRestoreService.ACTION_START_BACKUP
            putExtra(BackupRestoreService.EXTRA_CONTAINER_ID, containerId)
            putExtra(BackupRestoreService.EXTRA_BACKUP_NAME, name)
            if (!password.isNullOrEmpty()) {
                putExtra(
                    BackupRestoreService.EXTRA_BACKUP_PASSWORD_TOKEN,
                    BackupPasswordStore.put(activity, password)
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent)
        } else {
            activity.startService(intent)
        }
    }

    fun restoreAskingPasswordIfNeeded(backupFile: File, originalName: String? = null) {
        if (BackupCrypto.isEncryptedName(backupFile.name) || BackupCrypto.isEncryptedFile(backupFile)) {
            promptBackupPassword { password ->
                performRestore(backupFile, originalName, password)
            }
        } else {
            performRestore(backupFile, originalName, null)
        }
    }

    private fun promptBackupPassword(onPassword: (String) -> Unit) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val input = EditText(activity).apply {
            hint = "Backup password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle("Encrypted backup")
            .setMessage("This archive is password-protected.")
            .setView(layout)
            .setPositiveButton("Restore") { _, _ ->
                onPassword(input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun performRestore(backupFile: File, originalName: String? = null, password: String? = null) {
        if (!checkStoragePermission()) return
        val intent = Intent(activity, BackupRestoreService::class.java).apply {
            action = BackupRestoreService.ACTION_START_RESTORE
            putExtra(BackupRestoreService.EXTRA_BACKUP_FILE_PATH, backupFile.absolutePath)
            if (originalName != null) {
                putExtra(BackupRestoreService.EXTRA_ORIGINAL_NAME, originalName)
            }
            if (!password.isNullOrEmpty()) {
                putExtra(
                    BackupRestoreService.EXTRA_BACKUP_PASSWORD_TOKEN,
                    BackupPasswordStore.put(activity, password)
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent)
        } else {
            activity.startService(intent)
        }
    }

    fun validateAndFixRestoredSystem(rootfs: File, containerId: String): Boolean {
        onLogToMini("Validating restored system...")
        
        // Delegate to the shared engine so Arch (usrmerge, pacman, xkb) matches Settings restore.
        val engine = ContainerRestoreEngine(activity)
        return engine.validateAndFix(rootfs, containerId, containerManager) { onLogToMini(it) }
    }

    fun performRestoreFromUri(uri: Uri) {
        val originalName = getFileNameFromUri(uri)
        val tempFile = File(activity.cacheDir, "restore_temp.tar.gz")
        onLogToMini("Copying backup ${originalName ?: ""} from storage...")
        
        Thread {
            try {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                activity.runOnUiThread { restoreAskingPasswordIfNeeded(tempFile, originalName) }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Failed to load backup: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("BackupRestoreController", "Failed to get filename from URI: ${e.message}")
        }
        return name
    }

    fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(intent)
                    Toast.makeText(activity, "Please grant 'All Files Access' to take backups.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        activity.startActivity(intent)
                        Toast.makeText(activity, "Please grant 'All Files Access' to take backups.", Toast.LENGTH_LONG).show()
                    } catch (ex: Exception) {
                        Toast.makeText(activity, "Unable to open storage settings.", Toast.LENGTH_LONG).show()
                    }
                }
                return false
            }
        } else {
            if (activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1002)
                return false
            }
        }
        return true
    }
}
