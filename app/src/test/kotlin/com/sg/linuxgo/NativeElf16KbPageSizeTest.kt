package com.sg.linuxgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android 15+ requires 64-bit JNI .so LOAD segments with ELF p_align
 * of at least 16 KB. This scans the arm64 prebuilts that ship.
 */
class NativeElf16KbPageSizeTest {

    @Test
    fun arm64JniLibsHave16KbLoadAlignment() {
        val dir = jniLibsArm64()
        val sos = dir.listFiles { f -> f.isFile && f.name.endsWith(".so") }?.sortedBy { it.name }
        if (sos.isNullOrEmpty()) {
            return
        }
        val unaligned = mutableListOf<String>()
        var sawTawcroot = false
        for (so in sos) {
            if (so.name == "libtawcroot.so") sawTawcroot = true
            val aligns = loadAligns(so) ?: continue
            if (aligns.any { it < PAGE_16K }) {
                unaligned += "${so.name} align=${aligns.joinToString { "0x${it.toString(16)}" }}"
            }
        }
        assertTrue("libtawcroot.so must be packaged", sawTawcroot)
        assertFalse(
            "libproroot*.so must not ship (removed engine)",
            sos.any { it.name == "libproroot.so" || it.name.startsWith("libproroot-") },
        )
        assertTrue(
            "64-bit JNI libs must be 16 KB ELF aligned:\n${unaligned.joinToString("\n")}",
            unaligned.isEmpty(),
        )
    }

    private fun jniLibsArm64(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "src/main/jniLibs/arm64-v8a"),
            File(cwd, "app/src/main/jniLibs/arm64-v8a"),
            File(cwd.parentFile, "app/src/main/jniLibs/arm64-v8a"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: File(cwd, "src/main/jniLibs/arm64-v8a")
    }

    private fun loadAligns(file: File): List<Long>? {
        val data = file.readBytes()
        if (data.size < 64) return null
        if (data[0] != 0x7f.toByte() || data[1] != 'E'.code.toByte() ||
            data[2] != 'L'.code.toByte() || data[3] != 'F'.code.toByte()
        ) {
            return null
        }
        if (data[4].toInt() != 2 || data[5].toInt() != 1) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val phoff = buf.getLong(32)
        val phentsize = buf.getShort(54).toInt() and 0xffff
        val phnum = buf.getShort(56).toInt() and 0xffff
        val aligns = ArrayList<Long>()
        for (i in 0 until phnum) {
            val off = phoff + i.toLong() * phentsize
            if (off + 56 > data.size) break
            val pType = buf.getInt(off.toInt())
            if (pType != PT_LOAD) continue
            aligns += buf.getLong(off.toInt() + 48)
        }
        return aligns
    }

    companion object {
        private const val PT_LOAD = 1
        private const val PAGE_16K = 16384L
    }
}
