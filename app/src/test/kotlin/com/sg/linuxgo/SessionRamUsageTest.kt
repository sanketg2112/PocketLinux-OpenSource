package com.sg.linuxgo

import com.sg.linuxgo.util.SessionRamUsage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

class SessionRamUsageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun readRealUid_parsesStatus() {
        val status = tmp.newFile("status")
        status.writeText(
            """
            Name:	proot
            Uid:	10234	10234	10234	10234
            Gid:	10234	10234	10234	10234
            VmRSS:	  12000 kB
            """.trimIndent()
        )
        assertEquals(10234, SessionRamUsage.readRealUid(status))
    }

    @Test
    fun readRealUid_missingFile_returnsNull() {
        assertNull(SessionRamUsage.readRealUid(File(tmp.root, "nope")))
    }

    @Test
    fun parsePssKb_readsSmapsRollup() {
        val rollup = tmp.newFile("smaps_rollup")
        rollup.writeText(
            """
            Rss:              20000 kB
            Pss:              15360 kB
            Pss_Anon:         10000 kB
            Pss_File:          5360 kB
            """.trimIndent()
        )
        assertEquals(15360L, SessionRamUsage.parsePssKb(rollup))
    }

    @Test
    fun parseVmRssKb_readsStatus() {
        val status = tmp.newFile("status2")
        status.writeText("VmRSS:\t8192 kB\n")
        assertEquals(8192L, SessionRamUsage.parseVmRssKb(status))
    }

    @Test
    fun collectPidsForUid_filtersMatchingUid() {
        val proc = tmp.newFolder("proc")
        fun writePid(pid: String, uid: Int) {
            val dir = File(proc, pid).apply { mkdirs() }
            File(dir, "status").writeText("Uid:\t$uid\t$uid\t$uid\t$uid\n")
        }
        writePid("1001", 50)
        writePid("1002", 99)
        writePid("1003", 50)
        writePid("self", 50) // non-numeric ignored
        File(proc, "net").mkdirs() // non-pid dir ignored

        val pids = SessionRamUsage.collectPidsForUid(50, proc)
        assertArrayEquals(intArrayOf(1001, 1003), pids)
    }

    @Test
    fun readPssKbFromProc_prefersSmapsRollup() {
        val proc = tmp.newFolder("proc2")
        val dir = File(proc, "42").apply { mkdirs() }
        File(dir, "smaps_rollup").writeText("Pss:\t4096 kB\n")
        File(dir, "status").writeText("VmRSS:\t99999 kB\n")
        assertEquals(4096L, SessionRamUsage.readPssKbFromProc(42, proc))
    }

    @Test
    fun readPssKbFromProc_fallsBackToVmRss() {
        val proc = tmp.newFolder("proc3")
        val dir = File(proc, "7").apply { mkdirs() }
        File(dir, "status").writeText("VmRSS:\t2048 kB\n")
        assertEquals(2048L, SessionRamUsage.readPssKbFromProc(7, proc))
    }
}
