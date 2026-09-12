package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Host-side repair for black XFCE wallpaper + missing default file manager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class XfceWallpaperThunarDefaultsTest {

    @Test
    fun ensureXfceDefaultsFixesMissingLastImageAndSetsThunar() {
        val root = createTempDirectory("xfce-wp").toFile()
        try {
            // Minimal guest tree
            File(root, "usr/bin").mkdirs()
            File(root, "usr/bin/thunar").writeText("#!/bin/sh\n")
            File(root, "usr/bin/xfdesktop").writeText("#!/bin/sh\n")
            File(root, "usr/share/applications").mkdirs()
            File(root, "usr/share/applications/thunar.desktop").writeText(
                "[Desktop Entry]\nName=Thunar\nExec=thunar\n"
            )

            val home = File(root, "home/kali").apply { mkdirs() }
            val deskXml = File(
                home,
                ".config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml"
            )
            deskXml.parentFile?.mkdirs()
            deskXml.writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <channel name="xfce4-desktop" version="1.0">
                  <property name="backdrop" type="empty">
                    <property name="screen0" type="empty">
                      <property name="monitor0" type="empty">
                        <property name="workspace0" type="empty">
                          <property name="image-style" type="int" value="0"/>
                          <property name="last-image" type="string" value="/usr/share/backgrounds/xfce/xfce-x.svg"/>
                        </property>
                      </property>
                    </property>
                  </property>
                </channel>
                """.trimIndent()
            )
            File(home, ".config/xfce4/helpers.rc").apply {
                parentFile?.mkdirs()
                writeText("FileManager=pcmanfm\nTerminalEmulator=xfce4-terminal\n")
            }

            // Seed wallpaper file the repair should point at
            val wp = File(root, "usr/share/xfce4/backdrops/pocketlinux_wp.png")
            wp.parentFile?.mkdirs()
            wp.writeBytes(byteArrayOf(1, 2, 3, 4))

            val engine = ContainerRestoreEngine(RuntimeEnvironment.getApplication())
            engine.ensureXfceWallpaperAndThunarDefaults(root)

            val xml = deskXml.readText()
            assertTrue(
                "last-image should not point at missing xfce-x.svg",
                !xml.contains("xfce-x.svg")
            )
            assertTrue(xml.contains("pocketlinux_wp.png"))
            assertTrue(xml.contains("""image-style" type="int" value="5""""))

            val helpers = File(home, ".config/xfce4/helpers.rc").readText()
            assertTrue(helpers.contains("FileManager=thunar"))
            assertEquals(1, helpers.lines().count { it.startsWith("FileManager=") })

            val mime = File(home, ".config/mimeapps.list").readText()
            assertTrue(mime.contains("inode/directory=thunar.desktop"))

            assertTrue(File(root, "usr/share/images/desktop-base").isDirectory ||
                File(root, "var/lib/pocketlinux/xfce_wp_thunar_v1").isFile)
            assertTrue(File(root, "var/lib/pocketlinux/xfce_wp_thunar_v1").isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
