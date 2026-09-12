package com.sg.linuxgo

import com.sg.linuxgo.util.getSafeBoolean
import com.sg.linuxgo.util.getSafeFloat
import com.sg.linuxgo.util.getSafeString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsHelperTest {

    @Test
    fun getSafeFloatReadsTypedAndCoercesString() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putFloat("scale", 1.5f).commit()
        assertEquals(1.5f, prefs.getSafeFloat("scale", 0f), 0.001f)

        prefs.putRaw("scale_str", "2.25")
        // ClassCastException path: getFloat fails on String → coerce via all[]
        assertEquals(2.25f, prefs.getSafeFloat("scale_str", 0f), 0.001f)
        assertEquals(9f, prefs.getSafeFloat("missing", 9f), 0.001f)
    }

    @Test
    fun getSafeBooleanCoercesNumberAndString() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putBoolean("flag", true).commit()
        assertTrue(prefs.getSafeBoolean("flag", false))

        prefs.putRaw("num", 1)
        assertTrue(prefs.getSafeBoolean("num", false))
        prefs.putRaw("num0", 0)
        assertFalse(prefs.getSafeBoolean("num0", true))
        prefs.putRaw("str", "true")
        assertTrue(prefs.getSafeBoolean("str", false))
    }

    @Test
    fun getSafeStringCoercesNonString() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("name", "debian").commit()
        assertEquals("debian", prefs.getSafeString("name", null))

        prefs.putRaw("n", 42)
        assertEquals("42", prefs.getSafeString("n", "x"))
        assertEquals("fallback", prefs.getSafeString("missing", "fallback"))
    }
}
