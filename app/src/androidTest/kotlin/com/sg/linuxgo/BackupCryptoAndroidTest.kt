package com.sg.linuxgo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupCryptoAndroidTest {

    @Test
    fun roundtripEncryptionOnAndroidDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheDir = context.cacheDir
        val src = File(cacheDir, "test_android_src.tar.gz").apply { writeText("android-rootfs-content-payload") }
        val enc = File(cacheDir, "test_android_enc.plbk")
        val dest = File(cacheDir, "test_android_dest.tar.gz")

        try {
            BackupCrypto.encryptFile(src, enc, "test-password")
            assertTrue(enc.exists())
            assertTrue(enc.length() > src.length())
            assertTrue(BackupCrypto.isEncryptedFile(enc))

            BackupCrypto.decryptFile(enc, dest, "test-password")
            assertTrue(dest.exists())
            assertEquals("android-rootfs-content-payload", dest.readText())
        } finally {
            src.delete()
            enc.delete()
            dest.delete()
        }
    }

    @Test
    fun liveManifestHasEncryptedVariantsOnAndroid() {
        val manifest = ContainerImageCatalog.fetch()
        assertNotNull(manifest)
        assertTrue(manifest.images.isNotEmpty())

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
            val img = ContainerImageCatalog.selectImage(manifest, distro, desktop, "aarch64")
            assertNotNull("Expected image for $distro/$desktop", img)
            assertTrue("Expected .tar.gz for $distro/$desktop, got ${img.filename}", img.filename.endsWith(".tar.gz"))
            assertTrue("Expected .tar.gz URL for $distro/$desktop, got ${img.url}", img.url.endsWith(".tar.gz"))
            assertTrue("Expected sha256 for $distro/$desktop", img.sha256.isNotBlank())
            assertTrue("Expected sizeBytes > 100MB for $distro/$desktop", img.sizeBytes > 100_000_000L)
            assertTrue("Expected encrypted variant metadata for $distro/$desktop", img.hasEncryptedVariant)
        }
    }
}
