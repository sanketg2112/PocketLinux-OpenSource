package com.sg.linuxgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Regression: Kotlin [File.deleteRecursively] can throw AssertionError
 * ("rootDir must be verified to be directory beforehand") which is an Error,
 * not Exception — that crashed the app on container delete (Crash G).
 */
class ContainerManagerSafeDeleteTest {

    @Test
    fun safeDelete_emptyDir() {
        val dir = createTempDirectory("pl_del_empty").toFile()
        assertTrue(dir.isDirectory)
        assertTrue(ContainerManager.safeDeleteRecursively(dir))
        assertFalse(dir.exists())
    }

    @Test
    fun safeDelete_nestedTree() {
        val root = createTempDirectory("pl_del_tree").toFile()
        val nested = File(root, "a/b/c")
        assertTrue(nested.mkdirs())
        File(nested, "file.txt").writeText("hello")
        File(root, "top.bin").writeBytes(ByteArray(8))
        assertTrue(ContainerManager.safeDeleteRecursively(root))
        assertFalse(root.exists())
    }

    @Test
    fun safeDelete_singleFile() {
        val file = File.createTempFile("pl_del_file", ".tmp")
        file.writeText("x")
        assertTrue(file.isFile)
        assertTrue(ContainerManager.safeDeleteRecursively(file))
        assertFalse(file.exists())
    }

    @Test
    fun safeDelete_missingPath_isSuccess() {
        val missing = File(System.getProperty("java.io.tmpdir"), "pl_del_missing_${System.nanoTime()}")
        assertFalse(missing.exists())
        assertTrue(ContainerManager.safeDeleteRecursively(missing))
    }

    @Test
    fun safeDelete_doesNotThrowWhenRootIsFileAfterExistsCheck() {
        // deleteRecursively would assert if walk treats non-dir poorly;
        // our helper must return cleanly for a plain file path.
        val file = File.createTempFile("pl_del_assert", ".tmp")
        try {
            assertTrue(ContainerManager.safeDeleteRecursively(file))
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }
}
