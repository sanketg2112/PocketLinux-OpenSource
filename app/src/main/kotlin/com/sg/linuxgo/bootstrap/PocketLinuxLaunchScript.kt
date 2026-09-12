package com.sg.linuxgo.bootstrap

/**
 * Builds `/usr/local/bin/pocketlinux-launch` for a guest rootfs.
 * Script body is split across part files to stay under the 1000-line limit.
 */
fun buildPocketLinuxLaunchScript(
    username: String,
    homeDir: String,
    selectedGuiMode: String,
    selectedDE: String,
    startCmd: String,
    gpuEnv: String
): String {
    return pocketLinuxLaunchScriptPart1(
        username, homeDir, selectedGuiMode, selectedDE, startCmd, gpuEnv
    ) + "\n" + pocketLinuxLaunchScriptPart2(
        username, homeDir, selectedGuiMode, selectedDE, startCmd, gpuEnv
    ) + "\n" + pocketLinuxLaunchScriptPart3(
        username, homeDir, selectedGuiMode, selectedDE, startCmd, gpuEnv
    ) + "\n" + pocketLinuxLaunchScriptPart4(
        username, homeDir, selectedGuiMode, selectedDE, startCmd, gpuEnv
    )
}
