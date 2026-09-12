package com.sg.linuxgo

import com.sg.linuxgo.bootstrap.SOFTWARE_GPU_ENV
import com.sg.linuxgo.bootstrap.buildPocketLinuxLaunchScript
import com.sg.linuxgo.bootstrap.gpuEnvironmentFileContent
import com.sg.linuxgo.bootstrap.gpuProfileScript
import com.sg.linuxgo.bootstrap.guestHomeDir
import com.sg.linuxgo.bootstrap.isHardwareGpuDriverMode
import com.sg.linuxgo.bootstrap.isSoftwareGpuDriverMode
import com.sg.linuxgo.bootstrap.gpuGuestEnvPairs
import com.sg.linuxgo.bootstrap.hostGpuModeOverrideScript
import com.sg.linuxgo.bootstrap.launchScriptGpuConflictsWithMode
import com.sg.linuxgo.bootstrap.overallProgress
import com.sg.linuxgo.bootstrap.resolveGpuDriverMode
import com.sg.linuxgo.bootstrap.resolveStartCmd
import com.sg.linuxgo.bootstrap.resolveStartCmdFallback
import com.sg.linuxgo.bootstrap.rootfsHasKgslDri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class BootstrapHelpersTest {

    @Test
    fun overallProgressMapsFractionIntoBand() {
        assertEquals(10, overallProgress(10, 20, 0f))
        assertEquals(20, overallProgress(10, 20, 1f))
        assertEquals(15, overallProgress(10, 20, 0.5f))
        assertEquals(0, overallProgress(0, 100, -1f))
        assertEquals(100, overallProgress(0, 100, 2f))
    }

    @Test
    fun guestHomeDir() {
        assertEquals("/root", guestHomeDir("root"))
        assertEquals("/home/PocketLinux", guestHomeDir("PocketLinux"))
    }

    @Test
    fun archXfceX11UsesMiniSession() {
        val cmd = resolveStartCmd(
            isArchRootfs = true,
            selectedGuiMode = "x11",
            selectedDE = "xfce4",
            selectedWM = "none"
        )
        assertEquals("/usr/local/bin/pocketlinux-xfce-session", cmd)
    }

    @Test
    fun debianXfceX11UsesStartxfce4() {
        val cmd = resolveStartCmd(
            isArchRootfs = false,
            selectedGuiMode = "x11",
            selectedDE = "xfce4",
            selectedWM = "none"
        )
        assertEquals("startxfce4", cmd)
    }

    @Test
    fun waylandXfceUsesStartxfce4Wayland() {
        val cmd = resolveStartCmd(
            isArchRootfs = false,
            selectedGuiMode = "wayland",
            selectedDE = "xfce4",
            selectedWM = "none"
        )
        assertEquals("startxfce4 --wayland", cmd)
    }

    @Test
    fun kdeX11AndWayland() {
        assertEquals(
            "startplasma-x11",
            resolveStartCmd(false, "x11", "kde", "none")
        )
        assertEquals(
            "startplasma-wayland",
            resolveStartCmd(false, "wayland", "kde", "none")
        )
    }

    @Test
    fun hyprlandStartsHyprlandBinary() {
        assertEquals(
            "Hyprland",
            resolveStartCmd(true, "x11", "hyprland", "none")
        )
        assertEquals(
            "Hyprland",
            resolveStartCmd(false, "wayland", "hyprland", "none")
        )
        assertEquals(
            "Hyprland",
            resolveStartCmdFallback(true, "x11", "hyprland", "none")
        )
    }

    @Test
    fun windowManagerOnly() {
        assertEquals(
            "openbox-session",
            resolveStartCmd(false, "x11", "none", "openbox")
        )
        assertEquals(
            "i3",
            resolveStartCmd(false, "x11", "none", "i3")
        )
    }

    @Test
    fun archFallbackWithoutDeUsesMiniSession() {
        assertEquals(
            "/usr/local/bin/pocketlinux-xfce-session",
            resolveStartCmdFallback(true, "x11", "none", "none")
        )
        assertEquals(
            "startxfce4",
            resolveStartCmdFallback(false, "x11", "none", "none")
        )
    }

    @Test
    fun softwareGpuEnvHasLlvmpipe() {
        assertTrue(SOFTWARE_GPU_ENV.contains("llvmpipe"))
        assertTrue(SOFTWARE_GPU_ENV.contains("LIBGL_ALWAYS_SOFTWARE=1"))
    }

    @Test
    fun hardwareGpuModeClassification() {
        assertTrue(isHardwareGpuDriverMode("adreno_freedreno"))
        assertTrue(isHardwareGpuDriverMode("adreno_zink"))
        assertTrue(isHardwareGpuDriverMode("mali_panfrost"))
        assertFalse(isHardwareGpuDriverMode("llvmpipe"))
        assertFalse(isHardwareGpuDriverMode("auto"))
        assertTrue(isSoftwareGpuDriverMode("llvmpipe"))
        assertTrue(isSoftwareGpuDriverMode("auto"))
        assertFalse(isSoftwareGpuDriverMode("adreno_freedreno"))
    }

    @Test
    fun resolveGpuDriverModeAutoAlwaysLlvmpipeEvenWhenKgslPresent() {
        val root = createTempDirectory("gpu-root").toFile()
        try {
            val dri = File(root, "usr/lib/dri").apply { mkdirs() }
            File(dri, "zink_dri.so").writeText("x")
            File(dri, "kgsl_dri.so").writeText("x")
            // Auto is software-stable; hardware is opt-in only.
            assertEquals("llvmpipe", resolveGpuDriverMode("auto", root))
            assertEquals("llvmpipe", resolveGpuDriverMode("auto", null))
            assertEquals("llvmpipe", resolveGpuDriverMode("", root))
            assertEquals("adreno_zink", resolveGpuDriverMode("adreno_zink", root))
            assertEquals("adreno_freedreno", resolveGpuDriverMode("adreno_freedreno", root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolveGpuDriverModeAutoIsLlvmpipeWithoutDri() {
        val root = createTempDirectory("gpu-empty").toFile()
        try {
            assertEquals("llvmpipe", resolveGpuDriverMode("auto", root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun freedrenoProfileClearsZinkAndDoesNotExportGALLIUMZink() {
        val script = gpuProfileScript("adreno_freedreno")
        assertTrue(script.contains("unset GALLIUM_DRIVER"))
        assertTrue(script.contains("unset ZINK_DESCRIPTORS"))
        assertTrue(script.contains("kgsl_dri.so") || script.contains("MESA_LOADER_DRIVER_OVERRIDE"))
        assertFalse(script.contains("export GALLIUM_DRIVER=zink"))
        assertFalse(script.contains("export MESA_LOADER_DRIVER_OVERRIDE=zink"))
        // Empty GALLIUM_DRIVER= breaks Mesa kgsl selection (glmark2 shows zink).
        assertFalse(
            "must not export empty GALLIUM_DRIVER=",
            script.contains(Regex("""export\s+GALLIUM_DRIVER=\s*$""", RegexOption.MULTILINE)) ||
                script.lines().any { it.trim() == "export GALLIUM_DRIVER=" }
        )
        // Must probe kgsl and never silently prefer zink.
        assertTrue(script.contains("kgsl") || script.contains("msm"))
        // Debian multiarch path first.
        assertTrue(script.contains("aarch64-linux-gnu/dri"))
        val env = gpuEnvironmentFileContent("adreno_freedreno")
        assertTrue(env.contains("MESA_LOADER_DRIVER_OVERRIDE=kgsl"))
        assertFalse(env.contains("GALLIUM_DRIVER=zink"))
        assertFalse(env.contains("GALLIUM_DRIVER="))
        assertFalse(env.contains("LIBGL_ALWAYS_SOFTWARE=1"))
    }

    @Test
    fun legacyFreedrenoAliasResolvesToAdrenoFreedreno() {
        assertEquals("adreno_freedreno", resolveGpuDriverMode("freedreno", null))
        assertEquals("adreno_freedreno", resolveGpuDriverMode("kgsl", null))
        assertEquals("adreno_zink", resolveGpuDriverMode("zink", null))
        assertTrue(isHardwareGpuDriverMode("freedreno"))
        val script = gpuProfileScript("freedreno")
        assertTrue(script.contains("kgsl_dri.so"))
        assertFalse(script.contains("export GALLIUM_DRIVER=zink"))
    }

    @Test
    fun launchScriptGpuConflictDetectsStaleZinkUnderFreedreno() {
        val zinkBake = "export GALLIUM_DRIVER=zink\nexport MESA_LOADER_DRIVER_OVERRIDE=zink\n"
        assertTrue(launchScriptGpuConflictsWithMode(zinkBake, "adreno_freedreno"))
        val fullFd = buildPocketLinuxLaunchScript(
            username = "user",
            homeDir = "/home/user",
            selectedGuiMode = "x11",
            selectedDE = "xfce4",
            startCmd = "startxfce4",
            gpuEnv = gpuProfileScript("adreno_freedreno")
        )
        // Full launch embeds host override for Freedreno — not a conflict.
        assertFalse(launchScriptGpuConflictsWithMode(fullFd, "adreno_freedreno"))
        assertTrue(fullFd.contains("POCKETLINUX_GPU_MODE"))
        assertTrue(fullFd.contains("adreno_freedreno"))
    }

    @Test
    fun launchScriptOmitsEmptyGalliumDriverInSessionEnv() {
        val hw = buildPocketLinuxLaunchScript(
            username = "user",
            homeDir = "/home/user",
            selectedGuiMode = "x11",
            selectedDE = "xfce4",
            startCmd = "startxfce4",
            gpuEnv = gpuProfileScript("adreno_freedreno")
        )
        // Session must not re-export empty GALLIUM_DRIVER= (Mesa → zink bug).
        assertTrue(hw.contains("__PL_GPU_ENV"))
        assertTrue(hw.contains("POCKETLINUX_GPU_MODE"))
        assertTrue(hw.contains("adreno_freedreno"))
        assertFalse(
            hw.contains("GALLIUM_DRIVER=\"\${GALLIUM_DRIVER-}\"") ||
                hw.contains("GALLIUM_DRIVER=\"\${GALLIUM_DRIVER:-}\"")
        )
        // Host override + final lock force kgsl for Freedreno.
        assertTrue(hw.contains("MESA_LOADER_DRIVER_OVERRIDE=kgsl"))
        assertTrue(hostGpuModeOverrideScript().contains("adreno_freedreno"))
    }

    @Test
    fun gpuGuestEnvPairsFreedrenoNeverSetsGalliumZink() {
        val m = gpuGuestEnvPairs("adreno_freedreno")
        assertEquals("adreno_freedreno", m["POCKETLINUX_GPU_MODE"])
        assertEquals("kgsl", m["MESA_LOADER_DRIVER_OVERRIDE"])
        assertTrue(
            m["LIBGL_DRIVERS_PATH"]!!.startsWith("/usr/local/lib/pocketlinux-dri:")
        )
        assertFalse(m.containsKey("GALLIUM_DRIVER"))
        assertFalse(m.containsKey("ZINK_DESCRIPTORS"))
        val z = gpuGuestEnvPairs("adreno_zink")
        assertEquals("zink", z["GALLIUM_DRIVER"])
        assertEquals("zink", z["MESA_LOADER_DRIVER_OVERRIDE"])
    }

    @Test
    fun softwareGpuEnvDoesNotUseFreedrenoJail() {
        val soft = gpuGuestEnvPairs("llvmpipe")
        assertEquals("swrast", soft["MESA_LOADER_DRIVER_OVERRIDE"])
        val path = soft["LIBGL_DRIVERS_PATH"].orEmpty()
        assertFalse(
            "software must not use pocketlinux-dri jail alone",
            path == "/usr/local/lib/pocketlinux-dri"
        )
        assertTrue(path.contains("/usr/lib/dri"))
        val fd = gpuGuestEnvPairs("adreno_freedreno")
        val fdPath = fd["LIBGL_DRIVERS_PATH"].orEmpty()
        assertTrue(
            "Freedreno path must include system dri after jail (swrast fallback)",
            fdPath.contains("pocketlinux-dri") && fdPath.contains("/usr/lib/dri")
        )
        val script = gpuProfileScript("adreno_freedreno")
        assertTrue(script.contains("swrast"))
        assertTrue(script.contains("/usr/lib/dri:/usr/lib/aarch64-linux-gnu/dri"))
    }

    @Test
    fun freedrenoHostOverrideUsesJailNotSystemDriWithZink() {
        val o = hostGpuModeOverrideScript()
        assertTrue(o.contains("pocketlinux-dri"))
        assertTrue(o.contains("kgsl_dri.so"))
        // Must not fall back to msm when kgsl missing (msm is stock DRM, not Android KGSL).
        assertFalse(
            "must not use msm as Freedreno success path under proot",
            o.contains("MESA_LOADER_DRIVER_OVERRIDE=msm")
        )
        assertTrue(o.contains("llvmpipe") || o.contains("swrast"))
    }

    @Test
    fun zinkProfileForcesZinkGallium() {
        val script = gpuProfileScript("adreno_zink")
        assertTrue(script.contains("export GALLIUM_DRIVER=zink"))
        assertTrue(script.contains("export MESA_LOADER_DRIVER_OVERRIDE=zink"))
        val env = gpuEnvironmentFileContent("adreno_zink")
        assertTrue(env.contains("GALLIUM_DRIVER=zink"))
    }

    @Test
    fun llvmpipeProfileForcesSoftware() {
        val script = gpuProfileScript("llvmpipe")
        assertTrue(script.contains("LIBGL_ALWAYS_SOFTWARE=1"))
        assertTrue(script.contains("GALLIUM_DRIVER=llvmpipe"))
    }

    @Test
    fun rootfsHasKgslDriDetectsModule() {
        val root = createTempDirectory("kgsl-root").toFile()
        try {
            assertFalse(rootfsHasKgslDri(root))
            val lib = File(root, "usr/lib/aarch64-linux-gnu").apply { mkdirs() }
            val dri = File(lib, "dri").apply { mkdirs() }
            // Stock-like: libdril stubs only — NOT Android KGSL ready.
            val stub = File(dri, "libdril_dri.so").apply {
                writeBytes(ByteArray(133_304) { 1 })
            }
            java.nio.file.Files.createSymbolicLink(
                File(dri, "kgsl_dri.so").toPath(),
                stub.toPath().fileName
            )
            assertFalse(
                "libdril stub without fat libgallium-devel must not count",
                rootfsHasKgslDri(root)
            )
            // lfdevs layout: kgsl_dri → libdril + fat libgallium-*-devel.so
            File(lib, "libgallium-26.2.0-devel.so").writeBytes(ByteArray(12_000_000) { 2 })
            assertTrue(
                "lfdevs libgallium-*-devel.so + kgsl_dri entry must count",
                rootfsHasKgslDri(root)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun isRealAndroidKgslAcceptsLfdevsLibdrilPlusGallium() {
        val root = createTempDirectory("lfdevs-layout").toFile()
        try {
            val lib = File(root, "usr/lib/aarch64-linux-gnu").apply { mkdirs() }
            val dri = File(lib, "dri").apply { mkdirs() }
            val stub = File(dri, "libdril_dri.so").apply { writeBytes(ByteArray(144_000) { 1 }) }
            val kgsl = File(dri, "kgsl_dri.so")
            java.nio.file.Files.createSymbolicLink(kgsl.toPath(), stub.toPath().fileName)
            assertFalse(com.sg.linuxgo.bootstrap.isRealAndroidKgslDri(kgsl, dri))
            File(lib, "libgallium-26.2.0-devel.so").writeBytes(ByteArray(11_000_000) { 3 })
            assertTrue(com.sg.linuxgo.bootstrap.isRealAndroidKgslDri(kgsl, dri))
            assertTrue(com.sg.linuxgo.bootstrap.findLfdevsLibgallium(root)!!.name.contains("devel"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun gpuProfileScriptIsSilentInTerminal() {
        // Sourced from .bashrc / .profile — must never print GPU status lines.
        for (mode in listOf(
            "llvmpipe", "auto", "adreno_freedreno", "adreno_zink",
            "mali_panfrost", "mali_zink", "unknown"
        )) {
            val script = gpuProfileScript(mode)
            assertFalse(
                "gpuProfileScript($mode) must not echo GPU status",
                script.contains("echo \"PocketLinux GPU") || script.contains("echo 'PocketLinux GPU")
            )
            assertFalse(script.contains("PocketLinux GPU:"))
        }
    }

    @Test
    fun archLaunchKeepsExplicitHardwareGpuMode() {
        val hw = buildPocketLinuxLaunchScript(
            username = "user",
            homeDir = "/home/user",
            selectedGuiMode = "x11",
            selectedDE = "xfce4",
            startCmd = "startxfce4",
            gpuEnv = gpuProfileScript("adreno_freedreno")
        )
        // Must not unconditionally force software after embedding Freedreno gpuEnv.
        assertTrue(hw.contains("keeping hardware GL") || hw.contains("MESA_LOADER_DRIVER_OVERRIDE"))
        assertTrue(hw.contains("kgsl|msm|zink|panfrost") || hw.contains("case \"\${MESA_LOADER_DRIVER_OVERRIDE:-}\""))
        // Soft OOM path still present for default/auto
        assertTrue(hw.contains("forced software GL") || hw.contains("OOM/black-screen guard"))
    }

    @Test
    fun launchScriptContainsStartCmdAndUser() {
        val script = com.sg.linuxgo.bootstrap.buildPocketLinuxLaunchScript(
            username = "alice",
            homeDir = "/home/alice",
            selectedGuiMode = "x11",
            selectedDE = "xfce4",
            startCmd = "startxfce4",
            gpuEnv = "export LIBGL_ALWAYS_SOFTWARE=1\n"
        )
        assertTrue(script.startsWith("#!/bin/sh") || script.contains("#!/bin/sh"))
        assertTrue(script.contains("startxfce4"))
        assertTrue(script.contains("alice") || script.contains("/home/alice"))
        assertTrue(script.contains("LIBGL_ALWAYS_SOFTWARE"))
    }

    @Test
    fun launchScriptPreservesUserCustomizationsPolicy() {
        val script = com.sg.linuxgo.bootstrap.buildPocketLinuxLaunchScript(
            username = "alice",
            homeDir = "/home/alice",
            selectedGuiMode = "x11",
            selectedDE = "xfce4",
            startCmd = "startxfce4",
            gpuEnv = "export LIBGL_ALWAYS_SOFTWARE=1\n"
        )
        // Must not always overwrite xfwm4.xml / desktop xml on every boot.
        assertTrue(script.contains("User config policy") || script.contains("preserve"))
        assertTrue(script.contains("__pl_xf_bool"))
        assertTrue(script.contains("if [ ! -f") && script.contains("xfwm4.xml"))
        // helpers.rc: only seed missing keys, never force overwrite each boot
        assertTrue(script.contains("! grep -q \"^TerminalEmulator=\""))
        // Desktop xml: create only when missing
        assertTrue(
            script.contains("Never rewrite an existing xfce4-desktop.xml") ||
                (script.contains("xfce4-desktop.xml") && script.contains("[ ! -f"))
        )
    }

    @Test
    fun launchScriptKeepsGuestToolkitAtUnityScale() {
        val script = com.sg.linuxgo.bootstrap.buildPocketLinuxLaunchScript(
            username = "alice",
            homeDir = "/home/alice",
            selectedGuiMode = "x11",
            selectedDE = "xfce4",
            startCmd = "startxfce4",
            gpuEnv = "export LIBGL_ALWAYS_SOFTWARE=1\n"
        )
        // Viewer zoom is Lorie-only; guest must stay at 1× DPI (no GDK/Qt scale rewrite).
        assertTrue(script.contains("export GDK_SCALE=1") || script.contains("GDK_SCALE=1"))
        assertTrue(script.contains("export GDK_DPI_SCALE=1") || script.contains("GDK_DPI_SCALE=1"))
        assertTrue(script.contains("Xft.dpi: 96"))
        // Must not reintroduce guest DPI scaling from POCKETLINUX_SCALE
        assertFalse(script.contains("POCKETLINUX_SCALE * 96"))
        assertFalse(script.contains("WindowScalingFactor"))
    }

    @Test
    fun mateLaunchScriptRepairsMarcoThemeAndWallpaper() {
        val script = buildPocketLinuxLaunchScript(
            username = "alice",
            homeDir = "/home/alice",
            selectedGuiMode = "x11",
            selectedDE = "mate",
            startCmd = "mate-session",
            gpuEnv = "export LIBGL_ALWAYS_SOFTWARE=1\n"
        )
        // Must not leave GTK Adwaita as Marco (Metacity) theme — no title-bar buttons.
        assertTrue(script.contains("TraditionalOk") || script.contains("_marco_theme"))
        assertTrue(script.contains("org.mate.Marco.general"))
        assertTrue(script.contains("button-layout"))
        assertTrue(script.contains("menu:minimize,maximize,close"))
        // Fallback if session never starts the WM
        assertTrue(script.contains("marco --replace") || script.contains("native Marco"))
        // Native MATE: Marco WM (Appearance controls title bars); Openbox only fallback
        assertTrue(script.contains("GTK_CSD=0") || script.contains("GTK_CSD"))
        assertTrue(script.contains("windowmanager marco") || script.contains("windowmanager='marco'"))
        assertTrue(script.contains("org.mate.session.required-components") || script.contains("windowmanager"))
        assertTrue(script.contains("Openbox fallback") || script.contains("PocketLinux-Dark"))
        // Trash handlers
        assertTrue(script.contains("Trash/files") || script.contains(".local/share/Trash"))
        assertTrue(script.contains("x-scheme-handler/trash") || script.contains("caja.desktop"))
        assertTrue(script.contains("unset GIO_USE_VFS") || script.contains("GIO_USE_VFS"))
        // Wallpaper: only re-apply user gsettings path — never hardcode pocketlinux image
        assertTrue(script.contains("__pl_mate_paint_user_wallpaper") || script.contains("user backdrop") || script.contains("user wallpaper"))
        assertTrue(script.contains("org.mate.background picture-filename"))
        assertTrue(script.contains("quick-assert") || script.contains("Fast re-assert"))
        assertTrue(script.contains("stripped wallpaper/gtk-theme") || script.contains("picture-filename="))
        assertTrue(script.contains("pocketlinux_user_prefs_seeded") || script.contains("mate_user_prefs_seeded"))
        // Panel ready: force-start + no gtk3-nocsd on whole session
        assertTrue(script.contains("force-starting mate-panel") || script.contains("starting mate-panel"))
        assertTrue(script.contains("gtk3-nocsd") || script.contains("mate-panel"))
    }

    @Test
    fun mateResolveStartCmd() {
        assertEquals(
            "mate-session",
            resolveStartCmd(false, "x11", "mate", "marco")
        )
    }

    @Test
    fun resizeScriptIsPython() {
        val py = com.sg.linuxgo.bootstrap.buildPocketLinuxResizeScript()
        assertTrue(py.contains("import ctypes") || py.contains("XOpenDisplay"))
    }

    @Test
    fun waylandSessionContainsStartCmd() {
        val s = com.sg.linuxgo.bootstrap.buildWaylandSessionScript("startxfce4 --wayland")
        assertTrue(s.contains("startxfce4") || s.contains("wayland"))
    }
}
