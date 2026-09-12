package com.sg.linuxgo

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * SessionTerminateReceiver
 * 
 * A static BroadcastReceiver that handles the "Terminate Session" action.
 * This is designed to be called from a notification action button or explicitly.
 * It performs an aggressive cleanup of all Linux-related processes and temporary files.
 */
class SessionTerminateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SessionTerminateReceiver"
        
        /**
         * Perform aggressive cleanup of all Linux-related processes.
         * This can be called from within the app or from the broadcast receiver.
         */
        fun performCleanup(context: Context) {
            Log.i(TAG, "Starting aggressive session cleanup...")

            // 0. Mark intentional stop + deny FGS first so death monitors do not
            // treat pkill -9 / SIGKILL (exit 137) as Android OOM, and sticky
            // restarts cannot repost notifications.
            try {
                SessionLifecycleGate.setUserStopInProgress(context, true)
                SessionLifecycleGate.setAllowed(context, false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear session FGS gate / user-stop flag: ${e.message}")
            }

            // 1. Stop keep-alive / display FGS and clear the single session notification
            try {
                SessionKeepAliveService.stop(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop SessionKeepAliveService: ${e.message}")
            }
            try {
                X11Service.stop(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop X11Service: ${e.message}")
            }
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(SessionNotificationManager.NOTIFICATION_ID)
                nm.cancel(405) // legacy X11-only notification id (pre-unify)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear notification: ${e.message}")
            }

            // 2. Kill background processes by name using pkill
            val processesToKill = listOf("proot", "Xvnc", "websockify", "Xwayland", "pulseaudio")
            processesToKill.forEach { name ->
                try {
                    Log.d(TAG, "Killing process: $name")
                    Runtime.getRuntime().exec(arrayOf("pkill", "-9", "-f", name)).waitFor()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to kill $name: ${e.message}")
                }
            }

            // 3. Explicitly kill the :x11 and :wayland service/activity processes
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val packageName = context.packageName
                val x11ProcessName = "$packageName:x11"
                val waylandProcessName = "$packageName:wayland"
                
                am.runningAppProcesses?.forEach { processInfo ->
                    if (processInfo.processName == x11ProcessName || processInfo.processName == waylandProcessName) {
                        Log.i(TAG, "Found process ${processInfo.processName} (PID: ${processInfo.pid}), killing it...")
                        android.os.Process.killProcess(processInfo.pid)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to kill :x11 or :wayland process: ${e.message}")
            }

            // 4. Clear temporary files and locks
            try {
                // Clear host-side tmp directories
                val cacheDir = context.cacheDir
                cacheDir.listFiles()?.forEach { file ->
                    if (file.isDirectory && (file.name.contains("_tmp") || file.name == "shm" || file.name == ".X11-unix")) {
                        Log.d(TAG, "Cleaning temp directory: ${file.name}")
                        deleteRecursive(file)
                    }
                    if (file.name == "x11_server.log") {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear temp files: ${e.message}")
            }

            // 5. Clear active container state
            try {
                val prefs = context.getSharedPreferences("pocket_linux_state", Context.MODE_PRIVATE)
                prefs.edit().remove("active_container_id").apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear active container state: ${e.message}")
            }

            // 6. Final notification cancel (after kills, in case a sticky restart raced)
            try {
                SessionLifecycleGate.setAllowed(context, false)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(SessionNotificationManager.NOTIFICATION_ID)
                nm.cancel(405)
            } catch (_: Exception) {
            }
            
            Log.i(TAG, "Cleanup finished.")
        }

        private fun deleteRecursive(fileOrDirectory: File) {
            if (fileOrDirectory.isDirectory) {
                fileOrDirectory.listFiles()?.forEach { deleteRecursive(it) }
            }
            fileOrDirectory.delete()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MainActivity.ACTION_TERMINATE_SESSION) {
            Log.i(TAG, "Received ACTION_TERMINATE_SESSION broadcast")
            
            // Perform cleanup
            performCleanup(context)
            
            // If MainActivity is running, it should also handle the UI reset.
            // We don't need to do anything else here as MainActivity also listens for this broadcast.
            // But just in case MainActivity is NOT running, our cleanup ensures the processes are gone.
        }
    }
}
