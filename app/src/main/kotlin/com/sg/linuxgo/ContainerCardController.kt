package com.sg.linuxgo

/**
 * UI-agnostic state holder for container cards. Compose renders the cards, but
 * the activity still uses this API from setup/service callbacks.
 */
open class ContainerCardController(
    private var containers: List<ContainerConfig>,
    private val listener: ContainerActionListener
) {
    private var installingContainerId: String? = null
    private var installProgressMessage: String = "Preparing..."
    private var installProgressPercent: Int = -1
    private var activeContainerId: String? = null
    private var isGuiActive: Boolean = false
    private var isShellActive: Boolean = false
    private val containerStats: MutableMap<String, Stats> = mutableMapOf()

    data class Stats(
        val storageUsedMB: Int,
        val storageTotalMB: Int,
        val ramUsedMB: Int,
        val ramTotalMB: Int,
        val systemUsedStorageMB: Int = 0,
        val systemUsedRamMB: Int = 0
    )

    interface ContainerActionListener {
        fun onLaunchGui(container: ContainerConfig)
        fun onLaunchShell(container: ContainerConfig)
        fun onInstall(container: ContainerConfig)
        fun onSettings(container: ContainerConfig)
        fun onDelete(container: ContainerConfig)
        fun onTerminate(container: ContainerConfig)
        fun onAbortInstall(container: ContainerConfig)
    }

    open fun updateContainers(newContainers: List<ContainerConfig>) {
        containers = newContainers
    }

    fun getInstallingContainerId(): String? = installingContainerId
    fun getInstallProgressMessage(): String = installProgressMessage
    fun getInstallProgressPercent(): Int = installProgressPercent
    fun getStats(containerId: String): Stats? = containerStats[containerId]

    open fun setInstallingContainer(containerId: String?) {
        installingContainerId = containerId
        // Reset monotonic % so a new install never inherits progress from an aborted run.
        installProgressMessage = if (containerId == null) "" else "Preparing..."
        installProgressPercent = -1
    }

    open fun updateInstallProgress(message: String, percent: Int = -1) {
        installProgressMessage = message
        // Keep progress monotonic so late/out-of-order updates never reverse the bar
        if (percent in 0..100) {
            installProgressPercent = if (installProgressPercent in 0..100) {
                maxOf(installProgressPercent, percent)
            } else {
                percent
            }
        } else if (percent < 0 && installProgressPercent < 0) {
            installProgressPercent = percent
        }
    }

    open fun setActiveContainer(
        containerId: String?,
        guiActive: Boolean = false,
        shellActive: Boolean = false
    ) {
        activeContainerId = containerId
        isGuiActive = guiActive
        isShellActive = shellActive
    }

    open fun updateStats(
        containerId: String,
        storageUsedMB: Int,
        storageTotalMB: Int,
        ramUsedMB: Int,
        ramTotalMB: Int,
        systemUsedStorageMB: Int = 0,
        systemUsedRamMB: Int = 0
    ) {
        containerStats[containerId] = Stats(
            storageUsedMB = storageUsedMB,
            storageTotalMB = storageTotalMB,
            ramUsedMB = ramUsedMB,
            ramTotalMB = ramTotalMB,
            systemUsedStorageMB = systemUsedStorageMB,
            systemUsedRamMB = systemUsedRamMB
        )
    }
}
