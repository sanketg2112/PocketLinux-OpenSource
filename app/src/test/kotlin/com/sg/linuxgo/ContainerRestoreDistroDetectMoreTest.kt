package com.sg.linuxgo

import com.sg.linuxgo.restore.detectDistroFromName
import com.sg.linuxgo.restore.detectDistroFromRootfs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ContainerRestoreDistroDetectMoreTest {

    @Test
    fun detectDistroFromNameEdges() {
        assertEquals("ubuntu", detectDistroFromName("My_Ubuntu_Backup.tar.gz"))
        assertEquals("alpine", detectDistroFromName("alpine-cli"))
        assertNull(detectDistroFromName("mystery-box"))
        assertNull(detectDistroFromName(""))
    }

    @Test
    fun detectDistroFromRootfsUbuntuPreferredOverDebianId() {
        val root = createTempDirectory("rootfs-u").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/os-release").writeText("ID=ubuntu\nID_LIKE=debian\n")
            assertEquals("ubuntu", detectDistroFromRootfs(root, "alpine"))
        } finally {
            root.deleteRecursively()
        }
    }
}
