package com.sg.linuxgo

/**
 * Picks which container the Install Complete / Restore Complete dialog should
 * launch. Prefer explicit ids from the finishing service; never invent "first
 * container in the list" (that launched the wrong card when 2+ envs exist).
 */
object InstallCompleteTarget {
    fun resolve(
        completedContainerId: String?,
        installingHint: String? = null,
        activeInstallingId: String? = null,
        lastCompleteInstallId: String? = null,
        lastCompleteRestoreId: String? = null,
        activeContainerId: String? = null,
    ): String? {
        return completedContainerId
            ?: installingHint
            ?: activeInstallingId
            ?: lastCompleteInstallId
            ?: lastCompleteRestoreId
            ?: activeContainerId
    }
}
