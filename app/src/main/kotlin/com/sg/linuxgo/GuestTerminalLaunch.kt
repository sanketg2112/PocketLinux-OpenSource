package com.sg.linuxgo

import java.io.File

/**
 * Extra guest environment so the app terminal loads the same interactive stack
 * as xfce4-terminal (bashrc, starship, oh-my, XDG paths).
 */
object GuestTerminalLaunch {

    enum class PromptTheme {
        NONE,
        STARSHIP,
        OH_MY_BASH,
        OH_MY_ZSH,
        POWERLEVEL10K,
        FISH
    }

    data class GuestPrompt(
        val theme: PromptTheme,
        val starshipToml: File?,
        val customCommand: String?
    )

    fun detectPrompt(rootfs: File, username: String): GuestPrompt {
        val home = GuestTerminalAppearance.guestHome(rootfs, username)
        val starshipToml = listOf(
            File(home, ".config/starship.toml"),
            File(rootfs, "root/.config/starship.toml")
        ).firstOrNull { it.isFile }
        val hasStarshipBin = File(rootfs, "usr/bin/starship").isFile ||
            File(rootfs, "usr/local/bin/starship").isFile ||
            File(home, ".local/bin/starship").isFile ||
            File(home, ".cargo/bin/starship").isFile
        val theme = when {
            File(home, ".p10k.zsh").isFile -> PromptTheme.POWERLEVEL10K
            File(home, ".oh-my-zsh").isDirectory -> PromptTheme.OH_MY_ZSH
            File(home, ".oh-my-bash").isDirectory -> PromptTheme.OH_MY_BASH
            File(home, ".config/fish/config.fish").isFile -> PromptTheme.FISH
            hasStarshipBin || starshipToml != null -> PromptTheme.STARSHIP
            else -> PromptTheme.NONE
        }
        return GuestPrompt(theme = theme, starshipToml = starshipToml, customCommand = null)
    }

    /**
     * Extra `KEY=value` pairs injected into the guest session (no Android host vars).
     * Intentionally does **not** set PS1 — starship / oh-my / user bashrc own the prompt.
     */
    fun extraEnvPairs(
        rootfs: File,
        username: String,
        homeDir: String,
        shellBin: String,
        prompt: GuestPrompt
    ): Array<String> {
        val pairs = mutableListOf(
            "SHELL=$shellBin",
            "XDG_CONFIG_HOME=$homeDir/.config",
            "XDG_DATA_HOME=$homeDir/.local/share",
            "XDG_CACHE_HOME=$homeDir/.cache",
            "XDG_STATE_HOME=$homeDir/.local/state"
        )
        prompt.starshipToml?.let { f ->
            val guestPath = hostFileToGuestPath(rootfs, f) ?: "$homeDir/.config/starship.toml"
            pairs += "STARSHIP_CONFIG=$guestPath"
        }
        if (prompt.theme != PromptTheme.NONE || prompt.starshipToml != null) {
            // Stop profile.d from clobbering starship / oh-my / p10k / fish prompts.
            pairs += "POCKETLINUX_KEEP_PS1=1"
        }
        return pairs.toTypedArray()
    }

    fun hostFileToGuestPath(rootfs: File, file: File): String? {
        val root = rootfs.absolutePath.trimEnd('/')
        val path = file.absolutePath
        if (!path.startsWith(root)) return null
        val rest = path.removePrefix(root)
        return if (rest.startsWith("/")) rest else "/$rest"
    }
}
