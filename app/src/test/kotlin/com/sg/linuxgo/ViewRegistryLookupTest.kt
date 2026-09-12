package com.sg.linuxgo

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure policy for Crash A / [RegistryAppCompatActivity]:
 * registry serves bridge views; [android.R.id.content] never shadows the real window;
 * unknown ids fall through as null (no throw).
 */
class ViewRegistryLookupTest {

    /** Mirrors [MainActivity.findRegisteredView]. */
    private fun findRegistered(registry: Map<Int, Any>, id: Int): Any? {
        if (id == android.R.id.content) return null
        return registry[id]
    }

    @Test
    fun contentIdNeverServedFromRegistry() {
        val dummyContent = Any()
        val mainRoot = Any()
        val registry = mapOf(
            android.R.id.content to dummyContent,
            0x7f0a0001 to mainRoot
        )
        assertNull(findRegistered(registry, android.R.id.content))
        assertSame(mainRoot, findRegistered(registry, 0x7f0a0001))
        assertNull(findRegistered(registry, 0x7f0a9999))
    }
}
