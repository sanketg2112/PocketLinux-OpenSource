package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContainerImageCatalogTest {

    @Test
    fun normalizeDistroMapsCommonLabels() {
        assertEquals("debian", ContainerImageCatalog.normalizeDistro("Debian 12"))
        assertEquals("ubuntu", ContainerImageCatalog.normalizeDistro("UBUNTU"))
        assertEquals("kali", ContainerImageCatalog.normalizeDistro("Kali Linux"))
        assertEquals("alpine", ContainerImageCatalog.normalizeDistro(" alpine "))
        assertEquals("archlinux", ContainerImageCatalog.normalizeDistro("Arch Linux"))
        assertEquals("archlinux", ContainerImageCatalog.normalizeDistro("arch"))
        assertEquals("artix", ContainerImageCatalog.normalizeDistro("Artix Linux"))
        assertEquals("archlinux", ContainerImageCatalog.normalizeDistro("Arch Linux ARM"))
        assertEquals("fedora", ContainerImageCatalog.normalizeDistro("Fedora Workstation"))
        assertEquals("void", ContainerImageCatalog.normalizeDistro("Void Linux"))
        assertEquals("opensuse", ContainerImageCatalog.normalizeDistro("openSUSE Leap"))
        assertEquals("", ContainerImageCatalog.normalizeDistro("  "))
        assertEquals("fedora", ContainerImageCatalog.normalizeDistro("fedora"))
    }

    @Test
    fun normalizeDesktopMapsCommonLabels() {
        assertEquals("xfce4", ContainerImageCatalog.normalizeDesktop("xfce"))
        assertEquals("xfce4", ContainerImageCatalog.normalizeDesktop("XFCE4"))
        assertEquals("lxqt", ContainerImageCatalog.normalizeDesktop("LXQt"))
        assertEquals("mate", ContainerImageCatalog.normalizeDesktop("MATE Desktop"))
        assertEquals("kde", ContainerImageCatalog.normalizeDesktop("Plasma"))
        assertEquals("kde", ContainerImageCatalog.normalizeDesktop("KDE"))
        assertEquals("ubuntu-de", ContainerImageCatalog.normalizeDesktop("Ubuntu DE"))
        assertEquals("none", ContainerImageCatalog.normalizeDesktop("headless"))
        assertEquals("hyprland", ContainerImageCatalog.normalizeDesktop("Hyprland"))
        assertEquals("hyprland", ContainerImageCatalog.normalizeDesktop("hypr"))
        assertEquals("none", ContainerImageCatalog.normalizeDesktop("cli"))
        assertEquals("", ContainerImageCatalog.normalizeDesktop(""))
    }

    @Test
    fun parseManifestImagesAndHiddenDistros() {
        val json = """
            {
              "schema": 2,
              "latest": "v1.2.0",
              "manifest_url": "https://example.com/manifest.json",
              "hidden_distros": ["ubuntu", "Arch"],
              "hidden_combos": ["debian/mate", "Debian:MATE"],
              "images": [
                {
                  "id": "deb-xfce",
                  "version": "1.2.0",
                  "tag": "v1.2.0",
                  "filename": "debian-xfce.tar.gz",
                  "url": "https://cdn.example/debian-xfce.tar.gz",
                  "sha256": "abc",
                  "size_bytes": 1000,
                  "arch": "aarch64",
                  "distro": "Debian",
                  "desktop": "xfce",
                  "notes": "stable"
                },
                {
                  "id": "skip-me",
                  "url": ""
                }
              ]
            }
        """.trimIndent()

        val manifest = ContainerImageCatalog.parse(json)
        assertEquals(2, manifest.schema)
        assertEquals("v1.2.0", manifest.latest)
        assertEquals("https://example.com/manifest.json", manifest.manifestUrl)
        assertEquals(1, manifest.images.size)
        val img = manifest.images.single()
        assertEquals("deb-xfce", img.id)
        assertEquals("debian", img.normalizedDistro)
        assertEquals("xfce4", img.normalizedDesktop)
        assertTrue(manifest.hiddenDistros.contains("ubuntu"))
        assertTrue(manifest.hiddenDistros.contains("archlinux"))
        assertTrue(manifest.hiddenCombos.contains("debian/mate"))
        assertEquals("debian/mate", ContainerImageCatalog.comboKey("debian", "mate"))
        assertEquals("debian/xfce", ContainerImageCatalog.comboKey("debian", "xfce4"))
    }

    @Test
    fun matchesArchAcceptsArm64Aliases() {
        val img = ContainerImageCatalog.Image(
            id = "x",
            version = "1",
            tag = "v1",
            filename = "f",
            url = "https://example/f",
            sha256 = "",
            sizeBytes = 1,
            arch = "aarch64",
            distro = "debian",
            desktop = "xfce4",
            notes = ""
        )
        assertTrue(ContainerImageCatalog.matchesArch(img, "arm64-v8a"))
        assertTrue(ContainerImageCatalog.matchesArch(img, "aarch64"))
        assertFalse(ContainerImageCatalog.matchesArch(img, "x86_64"))
    }

    @Test
    fun availableDistrosAndDesktopsFilterHiddenAndArch() {
        val manifest = ContainerImageCatalog.Manifest(
            schema = 1,
            latest = null,
            images = listOf(
                image("d1", "debian", "xfce", "aarch64"),
                image("d2", "debian", "mate", "aarch64"),
                image("u1", "ubuntu", "ubuntu-de", "aarch64"),
                image("f1", "fedora", "xfce", "aarch64"),
                image("a1", "alpine", "lxqt", "x86_64")
            ),
            manifestUrl = null,
            hiddenDistros = setOf("ubuntu")
        )
        val distros = ContainerImageCatalog.availableDistroIds(manifest, preferredArch = "aarch64")
        assertEquals(listOf("debian", "fedora"), distros)

        val desktops = ContainerImageCatalog.availableDesktopIds(
            manifest,
            distroId = "debian",
            preferredArch = "aarch64"
        )
        assertEquals(listOf("xfce4", "mate"), desktops)
        assertTrue(ContainerImageCatalog.isDistroHidden(manifest, "Ubuntu"))
        assertFalse(ContainerImageCatalog.isDistroHidden(manifest, "debian"))
        assertFalse(ContainerImageCatalog.isDistroHidden(manifest, "fedora"))
    }

    @Test
    fun fedoraGoldenImageIsAnAvailableCatalogDistro() {
        val manifest = ContainerImageCatalog.Manifest(
            schema = 1,
            latest = "v1.0.0",
            images = listOf(
                image("deb", "debian", "xfce", "aarch64", version = "1.0.2"),
                image("fed", "fedora", "xfce", "aarch64", version = "1.0.0"),
            ),
            manifestUrl = null,
        )
        val ids = ContainerImageCatalog.availableDistroIds(manifest, preferredArch = "aarch64")
        assertTrue("fedora" in ids)
        val img = ContainerImageCatalog.selectImage(
            manifest,
            preferredDistro = "fedora",
            preferredDesktop = "xfce4",
            preferredArch = "aarch64",
        )
        assertEquals("fedora", img.normalizedDistro)
        assertEquals("xfce4", img.normalizedDesktop)
        assertEquals("1.0.0", img.version)
    }

    @Test
    fun availableDesktopsFilterHiddenCombos() {
        val manifest = ContainerImageCatalog.Manifest(
            schema = 1,
            latest = null,
            images = listOf(
                image("d1", "debian", "xfce", "aarch64"),
                image("d2", "debian", "mate", "aarch64"),
            ),
            manifestUrl = null,
            hiddenDistros = emptySet(),
            hiddenCombos = setOf("debian/mate"),
        )
        val desktops = ContainerImageCatalog.availableDesktopIds(
            manifest,
            distroId = "debian",
            preferredArch = "aarch64",
        )
        assertEquals(listOf("xfce4"), desktops)
        assertTrue(ContainerImageCatalog.isComboHidden(manifest, "debian", "mate"))
        assertFalse(ContainerImageCatalog.isComboHidden(manifest, "debian", "xfce4"))
    }

    @Test
    fun selectImagePicksHighestVersionPerDistroIgnoringGlobalLatestPin() {
        // Mirrors production bug: catalog.latest stayed at v1.0.0 after Debian 1.0.2 published
        val manifest = ContainerImageCatalog.Manifest(
            schema = 1,
            latest = "v1.0.0",
            images = listOf(
                image("deb-100", "debian", "xfce", "aarch64", version = "1.0.0"),
                image("deb-101", "debian", "xfce", "aarch64", version = "1.0.1"),
                image("deb-102", "debian", "xfce", "aarch64", version = "1.0.2"),
                image("arch-100", "archlinux", "xfce", "aarch64", version = "1.0.0"),
                image("arch-101", "archlinux", "xfce", "aarch64", version = "1.0.1"),
                image("ubu-100", "ubuntu", "xfce", "aarch64", version = "1.0.0"),
            ),
            manifestUrl = null,
            hiddenDistros = emptySet()
        )

        val debian = ContainerImageCatalog.selectImage(
            manifest,
            preferredDistro = "debian",
            preferredDesktop = "xfce4",
            preferredArch = "aarch64"
        )
        assertEquals("1.0.2", debian.version)
        assertEquals("v1.0.2", debian.tag)
        assertEquals("debian", debian.normalizedDistro)

        val arch = ContainerImageCatalog.selectImage(
            manifest,
            preferredDistro = "archlinux",
            preferredDesktop = "xfce",
            preferredArch = "aarch64"
        )
        assertEquals("1.0.1", arch.version)
        assertEquals("archlinux", arch.normalizedDistro)

        val ubuntu = ContainerImageCatalog.selectImage(
            manifest,
            preferredDistro = "ubuntu",
            preferredDesktop = "xfce4",
            preferredArch = "aarch64"
        )
        assertEquals("1.0.0", ubuntu.version)
    }

    @Test
    fun selectImageWithDistroOnlyStillPicksNewestForThatDistro() {
        val manifest = ContainerImageCatalog.Manifest(
            schema = 1,
            latest = "v1.0.0",
            images = listOf(
                image("deb-old", "debian", "xfce", "aarch64", version = "1.0.0"),
                image("deb-new", "debian", "xfce", "aarch64", version = "1.0.2"),
                image("ubu", "ubuntu", "xfce", "aarch64", version = "1.0.0"),
            ),
            manifestUrl = null
        )
        val img = ContainerImageCatalog.selectImage(
            manifest,
            preferredDistro = "debian",
            preferredDesktop = null,
            preferredArch = "aarch64"
        )
        assertEquals("1.0.2", img.version)
        assertEquals("debian", img.normalizedDistro)
    }

    @Test
    fun parseManifestWithEncryptedVariantKeepsTarGzByDefault() {
        val json = """
            {
              "schema": 1,
              "images": [
                {
                  "id": "default",
                  "version": "1.0.0",
                  "tag": "v1.0.0",
                  "filename": "pocketlinux-alpine-xfce-aarch64-v1.0.0.tar.gz",
                  "url": "https://example.com/pocketlinux-alpine-xfce-aarch64-v1.0.0.tar.gz",
                  "sha256": "plain-sha",
                  "size_bytes": 1000,
                  "arch": "aarch64",
                  "distro": "alpine",
                  "desktop": "xfce",
                  "encrypted": {
                    "filename": "pocketlinux-alpine-xfce-aarch64-v1.0.0.plbk",
                    "url": "https://example.com/pocketlinux-alpine-xfce-aarch64-v1.0.0.plbk",
                    "sha256": "encrypted-sha",
                    "size_bytes": 1050
                  }
                }
              ]
            }
        """.trimIndent()

        val manifest = ContainerImageCatalog.parse(json)
        assertEquals(1, manifest.images.size)
        val rawImage = manifest.images.single()
        assertEquals("pocketlinux-alpine-xfce-aarch64-v1.0.0.tar.gz", rawImage.filename)
        assertTrue(rawImage.hasEncryptedVariant)

        val defaultImage = ContainerImageCatalog.selectImage(
            manifest,
            preferredDistro = "alpine",
            preferredDesktop = "xfce4",
            preferredArch = "aarch64"
        )
        assertEquals("pocketlinux-alpine-xfce-aarch64-v1.0.0.tar.gz", defaultImage.filename)
        assertEquals("https://example.com/pocketlinux-alpine-xfce-aarch64-v1.0.0.tar.gz", defaultImage.url)
        assertEquals("plain-sha", defaultImage.sha256)
        assertEquals(1000L, defaultImage.sizeBytes)

        val encryptedImage = ContainerImageCatalog.selectImage(
            manifest,
            preferredDistro = "alpine",
            preferredDesktop = "xfce4",
            preferredArch = "aarch64",
            preferEncrypted = true
        )
        assertEquals("pocketlinux-alpine-xfce-aarch64-v1.0.0.plbk", encryptedImage.filename)
        assertEquals("https://example.com/pocketlinux-alpine-xfce-aarch64-v1.0.0.plbk", encryptedImage.url)
        assertEquals("encrypted-sha", encryptedImage.sha256)
        assertEquals(1050L, encryptedImage.sizeBytes)
        assertEquals(rawImage, encryptedImage.unencryptedFallback)
    }

    @Test
    fun parseManifestWithoutEncryptedVariantKeepsUnencryptedTarGz() {
        val json = """
            {
              "schema": 1,
              "images": [
                {
                  "id": "default",
                  "version": "1.0.0",
                  "tag": "v1.0.0",
                  "filename": "pocketlinux-debian-xfce-aarch64-v1.0.0.tar.gz",
                  "url": "https://example.com/pocketlinux-debian-xfce-aarch64-v1.0.0.tar.gz",
                  "sha256": "deb-sha",
                  "size_bytes": 2000,
                  "arch": "aarch64",
                  "distro": "debian",
                  "desktop": "xfce"
                }
              ]
            }
        """.trimIndent()

        val manifest = ContainerImageCatalog.parse(json)
        val selected = ContainerImageCatalog.selectImage(
            manifest,
            preferredDistro = "debian",
            preferredDesktop = "xfce4",
            preferredArch = "aarch64",
            preferEncrypted = true
        )
        assertEquals("pocketlinux-debian-xfce-aarch64-v1.0.0.tar.gz", selected.filename)
        assertFalse(selected.hasEncryptedVariant)
    }

    @Test
    fun parseManifestWithEncryptedImagesArrayIncludesThemInCatalog() {
        val json = """
            {
              "schema": 1,
              "images": [
                {
                  "id": "legacy",
                  "version": "1.0.0",
                  "filename": "legacy.tar.gz",
                  "url": "https://example.com/legacy.tar.gz",
                  "distro": "debian",
                  "desktop": "xfce"
                }
              ],
              "encrypted_images": [
                {
                  "id": "new-only",
                  "version": "1.0.0",
                  "filename": "new-only.plbk",
                  "url": "https://example.com/new-only.plbk",
                  "distro": "alpine",
                  "desktop": "xfce"
                }
              ]
            }
        """.trimIndent()

        val manifest = ContainerImageCatalog.parse(json)
        assertEquals(2, manifest.images.size)
        assertTrue(manifest.images.any { it.filename == "legacy.tar.gz" })
        assertTrue(manifest.images.any { it.filename == "new-only.plbk" })
    }

    @Test
    fun realManifestAllLatestCombosHaveEncryptedVariantsAndFallback() {
        val rootDir = File(System.getProperty("user.dir") ?: ".").let { if (it.name == "app") it.parentFile ?: it else it }
        val manifestFile = listOf(
            File(rootDir, "manifest.json"),
            File(rootDir, "PocketLinux_Releases/manifest.json"),
        ).firstOrNull { it.isFile } ?: File(rootDir, "manifest.json")
        assertTrue("manifest.json must exist at ${manifestFile.absolutePath}", manifestFile.isFile)

        val manifest = ContainerImageCatalog.parse(manifestFile.readText())
        val combos = listOf(
            "debian" to "xfce",
            "archlinux" to "xfce",
            "fedora" to "xfce",
            "alpine" to "mate",
            "debian" to "mate",
            "alpine" to "xfce",
            "kali" to "xfce",
            "ubuntu" to "xfce"
        )

        for ((distro, desktop) in combos) {
            val image = ContainerImageCatalog.selectImage(manifest, distro, desktop, "aarch64")
            requireNotNull(image) { "Expected image for $distro/$desktop" }
            assertTrue("Expected .tar.gz for $distro/$desktop, got ${image.filename}", image.filename.endsWith(".tar.gz"))
            assertTrue("Expected .tar.gz URL for $distro/$desktop", image.url.endsWith(".tar.gz"))
            assertTrue("Expected sha256 to be present for $distro/$desktop", image.sha256.isNotBlank())
            assertTrue("Expected sizeBytes > 100MB for $distro/$desktop", image.sizeBytes > 100_000_000L)
            assertTrue("Expected encrypted variant metadata for $distro/$desktop", image.hasEncryptedVariant)

            val encrypted = ContainerImageCatalog.selectImage(
                manifest, distro, desktop, "aarch64", preferEncrypted = true
            )
            requireNotNull(encrypted) { "Expected encrypted image for $distro/$desktop" }
            assertTrue("Expected .plbk for $distro/$desktop, got ${encrypted.filename}", encrypted.filename.endsWith(".plbk"))
            assertTrue("Expected encrypted URL for $distro/$desktop", encrypted.url.endsWith(".plbk"))
        }
    }

    private fun image(
        id: String,
        distro: String,
        desktop: String,
        arch: String,
        version: String = "1.0.0"
    ): ContainerImageCatalog.Image = ContainerImageCatalog.Image(
        id = id,
        version = version,
        tag = "v${version.removePrefix("v")}",
        filename = "$id.tar.gz",
        url = "https://example/$id.tar.gz",
        sha256 = "0",
        sizeBytes = 10,
        arch = arch,
        distro = distro,
        desktop = desktop,
        notes = ""
    )
}
