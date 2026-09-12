package com.sg.linuxgo.bootstrap

import java.io.File

/** Modes that force software GL (llvmpipe / swrast). Includes "auto" (defaults to llvmpipe). */
fun isSoftwareGpuDriverMode(driverMode: String): Boolean {
    val m = normalizeGpuDriverModeId(driverMode)
    return m == "llvmpipe" || m == "auto" || m.isBlank()
}

/**
 * Modes that expect GPU device bind + hardware Mesa (Freedreno/Zink/Panfrost).
 * "auto" is software (llvmpipe) — not hardware.
 */
fun isHardwareGpuDriverMode(driverMode: String): Boolean = when (normalizeGpuDriverModeId(driverMode)) {
    "adreno_freedreno", "adreno_zink", "mali_panfrost", "mali_zink" -> true
    else -> false
}

/** System DRI dirs (Alpine musl + Debian multiarch). Always after jail for swrast fallback. */
const val SYSTEM_DRI_PATH =
    "/usr/lib/dri:/usr/lib/aarch64-linux-gnu/dri:/usr/lib/xorg/modules/dri"

/**
 * Freedreno path: jail first (prefer kgsl), then system (swrast if kgsl fails).
 * Jail-only breaks Alpine when loader falls back to swrast.
 */
const val FREEDRENO_LIBGL_DRIVERS_PATH =
    "$FREEDRENO_DRI_JAIL_GUEST:$SYSTEM_DRI_PATH"

/** Shell: link system swrast into the jail so jail-only path still works. */
fun shellEnsureDriJailSwrastFallback(): String = """
_pl_jail=/usr/local/lib/pocketlinux-dri
mkdir -p "${'$'}_pl_jail" 2>/dev/null || true
# Always offer software modules in the jail (Alpine glmark2 if kgsl unavailable).
for _pl_mod in swrast kms_swrast; do
    for _pl_dir in /usr/lib/dri /usr/lib/aarch64-linux-gnu/dri /usr/lib/xorg/modules/dri; do
        if [ -e "${'$'}_pl_dir/${'$'}_pl_mod"_dri.so ]; then
            ln -sfn "${'$'}_pl_dir/${'$'}_pl_mod"_dri.so "${'$'}_pl_jail/${'$'}_pl_mod"_dri.so 2>/dev/null || true
            break
        fi
    done
done
""".trimIndent()

/**
 * Map legacy / short UI ids onto canonical prefs keys.
 * Wizard used to store "freedreno" / "zink" which fell through to software
 * (or left a prior Zink bake-in active).
 */
fun normalizeGpuDriverModeId(configured: String): String = when (configured.trim().lowercase()) {
    "freedreno", "adreno", "kgsl", "fd" -> "adreno_freedreno"
    "zink", "turnip", "adreno_turnip" -> "adreno_zink"
    "panfrost", "mali" -> "mali_panfrost"
    "mali_zink", "panzink" -> "mali_zink"
    "software", "sw", "soft" -> "llvmpipe"
    else -> configured.trim()
}

/**
 * Expand prefs mode for guest env / launch scripts.
 *
 * "auto" always resolves to **llvmpipe** (stable software GL). Hardware backends
 * are explicit only (Freedreno / Zink / Panfrost). Rootfs DRI presence is ignored
 * for auto so golden images that ship kgsl_dri.so do not surprise users with
 * Freedreno when Container Settings still says Auto.
 */
fun resolveGpuDriverMode(configured: String, rootfs: File? = null): String {
    val mode = normalizeGpuDriverModeId(configured).ifBlank { "auto" }
    if (mode == "auto" || mode.isBlank()) return "llvmpipe"
    return mode
}

/**
 * Static keys for /etc/environment (no shell). Must not leave GALLIUM_DRIVER=zink
 * residual when switching to Freedreno — scrub + rewrite is done by the caller.
 *
 * Freedreno: only MESA_LOADER_DRIVER_OVERRIDE=kgsl (lfdevs docs). Never set
 * GALLIUM_DRIVER — empty or "zink" makes glmark2 report "zink Vulkan …".
 */
fun gpuEnvironmentFileContent(driverMode: String): String {
    val resolved = resolveGpuDriverMode(driverMode, null)
    return when (resolved) {
        "adreno_freedreno" -> """
MESA_LOADER_DRIVER_OVERRIDE=kgsl
TU_DEBUG=noconform
VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
MESA_DEBUG=silent
PROOT_L_MT=1
""".trimIndent() + "\n"
        "adreno_zink" -> """
MESA_LOADER_DRIVER_OVERRIDE=zink
GALLIUM_DRIVER=zink
ZINK_DESCRIPTORS=lazy
ZINK_DEBUG=compact
TU_DEBUG=noconform,synced
MESA_VK_WSI_PRESENT_MODE=immediate
VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
MESA_SHADER_CACHE_DISABLE=false
MESA_SHADER_CACHE_MAX_SIZE=512MB
MESA_DEBUG=silent
PROOT_L_MT=1
""".trimIndent() + "\n"
        "mali_panfrost" -> """
MESA_LOADER_DRIVER_OVERRIDE=panfrost
MESA_DEBUG=silent
PROOT_L_MT=1
""".trimIndent() + "\n"
        "mali_zink" -> """
MESA_LOADER_DRIVER_OVERRIDE=zink
GALLIUM_DRIVER=zink
ZINK_DESCRIPTORS=lazy
ZINK_DEBUG=compact
MESA_DEBUG=silent
PROOT_L_MT=1
""".trimIndent() + "\n"
        else -> """
LIBGL_ALWAYS_SOFTWARE=1
GALLIUM_DRIVER=llvmpipe
MESA_LOADER_DRIVER_OVERRIDE=swrast
MESA_DEBUG=silent
""".trimIndent() + "\n"
    }
}

/**
 * Shell fragment sourced by pocketlinux-launch and /etc/profile.d/pocketlinux-gpu.sh.
 * Always starts by clearing opposing backend vars so switching modes cannot leave
 * GALLIUM_DRIVER=zink active under a Freedreno selection (glmark2 "zink Vulkan").
 *
 * Important: do **not** `export GALLIUM_DRIVER=` (empty). Mesa treats empty as set
 * and can ignore MESA_LOADER_DRIVER_OVERRIDE=kgsl, falling through to zink_dri.
 */
fun gpuProfileScript(driverMode: String): String {
    val mode = resolveGpuDriverMode(driverMode, null)
    val header = """
# PocketLinux GPU defaults.
# Hard-clear opposing backends (Zink residual is the usual glmark2 bug).
# Always clear LIBGL_DRIVERS_PATH: Freedreno jail has only kgsl_dri — if we fall
# back to swrast while path stays on the jail, glmark2 dies (Alpine symptom).
unset GALLIUM_DRIVER
unset ZINK_DESCRIPTORS
unset ZINK_DEBUG
unset MESA_EXTENSION_OVERRIDE
unset MESA_VK_WSI_PRESENT_MODE
unset LIBGL_ALWAYS_SOFTWARE
unset MESA_LOADER_DRIVER_OVERRIDE
unset LIBGL_DRIVERS_PATH
unset TU_DEBUG
unset VK_ICD_FILENAMES
unset MESA_SHADER_CACHE_DISABLE
unset MESA_SHADER_CACHE_MAX_SIZE

""".trimIndent() + "\n"

    val body = when (mode) {
        "adreno_freedreno" -> """
# Native Freedreno OpenGL via Android KGSL (lfdevs mesa).
# Modern Mesa: kgsl_dri.so → libdril_dri.so (~140KB); real code is libgallium-*-devel.so
# (~28MB). Stock Debian also uses libdril but lacks Android KGSL in libgallium → zink.
# Ready when: kgsl_dri entry exists AND fat libgallium-*-devel.so (lfdevs).
_pl_android_kgsl_ready() {
    [ -e /usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so ] || [ -e /usr/lib/dri/kgsl_dri.so ] || return 1
    for _g in /usr/lib/aarch64-linux-gnu/libgallium*-devel.so /usr/lib/libgallium*-devel.so; do
        [ -f "${'$'}_g" ] || continue
        _sz=${'$'}(stat -c%s "${'$'}_g" 2>/dev/null || echo 0)
        [ "${'$'}_sz" -gt 10000000 ] 2>/dev/null && return 0
    done
    # Legacy fat kgsl_dri.so
    for _f in /usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so /usr/lib/dri/kgsl_dri.so; do
        [ -e "${'$'}_f" ] || continue
        _t=${'$'}(readlink -f "${'$'}_f" 2>/dev/null || echo "${'$'}_f")
        case "${'$'}_t" in *libdril*) continue ;; esac
        _sz=${'$'}(stat -c%s "${'$'}_t" 2>/dev/null || echo 0)
        [ "${'$'}_sz" -gt 500000 ] 2>/dev/null && return 0
    done
    return 1
}
_pl_jail=/usr/local/lib/pocketlinux-dri
${shellEnsureDriJailSwrastFallback()}
if _pl_android_kgsl_ready; then
    _pl_kgsl=""
    for _pl_dir in /usr/lib/aarch64-linux-gnu/dri /usr/lib/dri /usr/lib/xorg/modules/dri; do
        if [ -e "${'$'}_pl_dir/kgsl_dri.so" ]; then
            _pl_kgsl="${'$'}_pl_dir/kgsl_dri.so"
            break
        fi
    done
    ln -sfn "${'$'}_pl_kgsl" "${'$'}_pl_jail/kgsl_dri.so" 2>/dev/null || true
    rm -f "${'$'}_pl_jail/zink_dri.so" "${'$'}_pl_jail/msm_dri.so" 2>/dev/null || true
    # Jail first, then system — so kgsl preferred, swrast still findable if jail-only was stale.
    export LIBGL_DRIVERS_PATH=${FREEDRENO_LIBGL_DRIVERS_PATH}
    export MESA_LOADER_DRIVER_OVERRIDE=kgsl
    unset GALLIUM_DRIVER
    unset ZINK_DESCRIPTORS
    unset ZINK_DEBUG
    unset LIBGL_ALWAYS_SOFTWARE
    export TU_DEBUG=noconform
    export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
else
    unset LIBGL_DRIVERS_PATH
    export LIBGL_ALWAYS_SOFTWARE=1
    export GALLIUM_DRIVER=llvmpipe
    export MESA_LOADER_DRIVER_OVERRIDE=swrast
    export LIBGL_DRIVERS_PATH=${SYSTEM_DRI_PATH}
    unset ZINK_DESCRIPTORS
    unset ZINK_DEBUG
fi
""".trimIndent()
        "adreno_zink" -> """
export GALLIUM_DRIVER=zink
export MESA_LOADER_DRIVER_OVERRIDE=zink
export ZINK_DESCRIPTORS=lazy
export ZINK_DEBUG=compact
export TU_DEBUG=noconform,synced
export MESA_VK_WSI_PRESENT_MODE=immediate
export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
export MESA_SHADER_CACHE_DISABLE=false
export MESA_SHADER_CACHE_MAX_SIZE=512MB
unset LIBGL_DRIVERS_PATH
export LIBGL_DRIVERS_PATH=${SYSTEM_DRI_PATH}
""".trimIndent()
        "mali_panfrost" -> """
export MESA_LOADER_DRIVER_OVERRIDE=panfrost
unset LIBGL_DRIVERS_PATH
export LIBGL_DRIVERS_PATH=${SYSTEM_DRI_PATH}
""".trimIndent()
        "mali_zink" -> """
export GALLIUM_DRIVER=zink
export MESA_LOADER_DRIVER_OVERRIDE=zink
export ZINK_DESCRIPTORS=lazy
export ZINK_DEBUG=compact
if [ -f /usr/share/vulkan/icd.d/panfrost_icd.aarch64.json ]; then
export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/panfrost_icd.aarch64.json
fi
unset LIBGL_DRIVERS_PATH
export LIBGL_DRIVERS_PATH=${SYSTEM_DRI_PATH}
""".trimIndent()
        "llvmpipe", "auto" -> """
unset LIBGL_DRIVERS_PATH
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export MESA_LOADER_DRIVER_OVERRIDE=swrast
export LIBGL_DRIVERS_PATH=${SYSTEM_DRI_PATH}
""".trimIndent()
        else -> """
unset LIBGL_DRIVERS_PATH
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export MESA_LOADER_DRIVER_OVERRIDE=swrast
export LIBGL_DRIVERS_PATH=${SYSTEM_DRI_PATH}
""".trimIndent()
    }

    return header + body + "\nexport MESA_DEBUG=silent\nexport PROOT_L_MT=1\n"
}

/**
 * True when [launchScriptText] is missing host GPU override for the resolved mode
 * (e.g. old Zink-only bake while prefs say Freedreno).
 *
 * Note: modern launch scripts embed a full case on POCKETLINUX_GPU_MODE that
 * includes both Freedreno and Zink branches — so "contains GALLIUM_DRIVER=zink"
 * alone is not a conflict.
 */
fun launchScriptGpuConflictsWithMode(launchScriptText: String, resolvedMode: String): Boolean {
    val mode = resolveGpuDriverMode(resolvedMode, null)
    val text = launchScriptText
    val hasHostModeSwitch = text.contains("POCKETLINUX_GPU_MODE")
    val hasKgslForce = text.contains("MESA_LOADER_DRIVER_OVERRIDE=kgsl")
    val hasZinkForce = text.contains("GALLIUM_DRIVER=zink") &&
        text.contains("MESA_LOADER_DRIVER_OVERRIDE=zink")
    // Pre-v38: only zink exports, no host mode switch.
    val oldZinkOnlyBake = hasZinkForce && !hasHostModeSwitch
    return when (mode) {
        "adreno_freedreno" -> oldZinkOnlyBake || !hasHostModeSwitch || !hasKgslForce
        "adreno_zink", "mali_zink" -> !hasZinkForce || !hasHostModeSwitch
        "llvmpipe" -> oldZinkOnlyBake
        else -> false
    }
}

/**
 * Guest env pairs for proot `env -i` (desktop + terminal).
 * Only non-empty values. Freedreno never includes GALLIUM_DRIVER (Mesa would treat
 * empty/zink as “use Zink” and glmark2 prints "zink Vulkan … Turnip").
 */
fun gpuGuestEnvPairs(driverMode: String): Map<String, String> {
    val mode = resolveGpuDriverMode(driverMode, null)
    val base = linkedMapOf("POCKETLINUX_GPU_MODE" to mode)
    return when (mode) {
        "adreno_freedreno" -> base + mapOf(
            // Jail first (kgsl), then system (swrast fallback — Alpine needs this).
            "MESA_LOADER_DRIVER_OVERRIDE" to "kgsl",
            "LIBGL_DRIVERS_PATH" to FREEDRENO_LIBGL_DRIVERS_PATH,
            "TU_DEBUG" to "noconform",
            "VK_ICD_FILENAMES" to "/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json",
            "MESA_DEBUG" to "silent",
            "PROOT_L_MT" to "1"
        )
        "adreno_zink" -> base + mapOf(
            "GALLIUM_DRIVER" to "zink",
            "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
            "ZINK_DESCRIPTORS" to "lazy",
            "ZINK_DEBUG" to "compact",
            "TU_DEBUG" to "noconform,synced",
            "MESA_VK_WSI_PRESENT_MODE" to "immediate",
            "VK_ICD_FILENAMES" to "/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json",
            "MESA_SHADER_CACHE_DISABLE" to "false",
            "MESA_SHADER_CACHE_MAX_SIZE" to "512MB",
            "MESA_DEBUG" to "silent",
            "PROOT_L_MT" to "1"
        )
        "mali_panfrost" -> base + mapOf(
            "MESA_LOADER_DRIVER_OVERRIDE" to "panfrost",
            "MESA_DEBUG" to "silent",
            "PROOT_L_MT" to "1"
        )
        "mali_zink" -> base + mapOf(
            "GALLIUM_DRIVER" to "zink",
            "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
            "ZINK_DESCRIPTORS" to "lazy",
            "ZINK_DEBUG" to "compact",
            "MESA_DEBUG" to "silent",
            "PROOT_L_MT" to "1"
        )
        else -> base + mapOf(
            "LIBGL_ALWAYS_SOFTWARE" to "1",
            "GALLIUM_DRIVER" to "llvmpipe",
            "MESA_LOADER_DRIVER_OVERRIDE" to "swrast",
            "LIBGL_DRIVERS_PATH" to SYSTEM_DRI_PATH,
            "MESA_DEBUG" to "silent"
        )
    }
}

/**
 * Shell fragment: re-apply GPU from Android-injected POCKETLINUX_GPU_MODE.
 * Runs after any baked `$gpuEnv` so container settings always win over golden-image Zink.
 */
fun hostGpuModeOverrideScript(): String = """
# Host GPU mode (POCKETLINUX_GPU_MODE) is authoritative — kills Zink bake-in from images.
case "${'$'}{POCKETLINUX_GPU_MODE:-}" in
    adreno_freedreno)
        unset GALLIUM_DRIVER
        unset ZINK_DESCRIPTORS
        unset ZINK_DEBUG
        unset MESA_EXTENSION_OVERRIDE
        unset MESA_VK_WSI_PRESENT_MODE
        unset MESA_SHADER_CACHE_DISABLE
        unset MESA_SHADER_CACHE_MAX_SIZE
        # Ready: kgsl_dri + fat libgallium-*-devel.so (lfdevs). libdril stub alone is OK.
        _pl_android_kgsl_ready() {
            [ -e /usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so ] || [ -e /usr/lib/dri/kgsl_dri.so ] || return 1
            for _g in /usr/lib/aarch64-linux-gnu/libgallium*-devel.so /usr/lib/libgallium*-devel.so; do
                [ -f "${'$'}_g" ] || continue
                _sz=${'$'}(stat -c%s "${'$'}_g" 2>/dev/null || echo 0)
                [ "${'$'}_sz" -gt 10000000 ] 2>/dev/null && return 0
            done
            return 1
        }
        ${shellEnsureDriJailSwrastFallback()}
        if _pl_android_kgsl_ready; then
            _pl_kgsl=/usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so
            [ -e "${'$'}_pl_kgsl" ] || _pl_kgsl=/usr/lib/dri/kgsl_dri.so
            ln -sfn "${'$'}_pl_kgsl" "${'$'}_pl_jail/kgsl_dri.so" 2>/dev/null || true
            rm -f "${'$'}_pl_jail/zink_dri.so" "${'$'}_pl_jail/msm_dri.so" 2>/dev/null || true
            export LIBGL_DRIVERS_PATH=${FREEDRENO_LIBGL_DRIVERS_PATH}
            export MESA_LOADER_DRIVER_OVERRIDE=kgsl
            unset LIBGL_ALWAYS_SOFTWARE
            unset GALLIUM_DRIVER
            export TU_DEBUG=noconform
            export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
        else
            unset LIBGL_DRIVERS_PATH
            export LIBGL_ALWAYS_SOFTWARE=1
            export GALLIUM_DRIVER=llvmpipe
            export MESA_LOADER_DRIVER_OVERRIDE=swrast
            export LIBGL_DRIVERS_PATH=${SYSTEM_DRI_PATH}
            unset ZINK_DESCRIPTORS
            unset ZINK_DEBUG
        fi
        export MESA_DEBUG=silent
        export PROOT_L_MT=1
        ;;
    adreno_zink)
        unset LIBGL_DRIVERS_PATH
        export GALLIUM_DRIVER=zink
        export MESA_LOADER_DRIVER_OVERRIDE=zink
        export ZINK_DESCRIPTORS=lazy
        export ZINK_DEBUG=compact
        export TU_DEBUG=noconform,synced
        export MESA_VK_WSI_PRESENT_MODE=immediate
        export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
        export MESA_SHADER_CACHE_DISABLE=false
        export MESA_SHADER_CACHE_MAX_SIZE=512MB
        export LIBGL_DRIVERS_PATH=${SYSTEM_DRI_PATH}
        export MESA_DEBUG=silent
        export PROOT_L_MT=1
        ;;
    mali_panfrost)
        unset GALLIUM_DRIVER
        unset LIBGL_DRIVERS_PATH
        export MESA_LOADER_DRIVER_OVERRIDE=panfrost
        export LIBGL_DRIVERS_PATH=${SYSTEM_DRI_PATH}
        export MESA_DEBUG=silent
        ;;
    mali_zink)
        unset LIBGL_DRIVERS_PATH
        export GALLIUM_DRIVER=zink
        export MESA_LOADER_DRIVER_OVERRIDE=zink
        export ZINK_DESCRIPTORS=lazy
        export ZINK_DEBUG=compact
        export LIBGL_DRIVERS_PATH=${SYSTEM_DRI_PATH}
        export MESA_DEBUG=silent
        ;;
    llvmpipe|auto|"")
        unset LIBGL_DRIVERS_PATH
        export LIBGL_ALWAYS_SOFTWARE=1
        export GALLIUM_DRIVER=llvmpipe
        export MESA_LOADER_DRIVER_OVERRIDE=swrast
        export LIBGL_DRIVERS_PATH=${SYSTEM_DRI_PATH}
        export MESA_DEBUG=silent
        ;;
esac
""".trimIndent() + "\n"
