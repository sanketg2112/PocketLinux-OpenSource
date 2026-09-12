package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NetworkSecurityAndCatalogSecurityTest {

    @Test
    fun catalogEnforcesHttpsAndUpgradesHttp() {
        val sampleJson = """
        {
            "schema": 1,
            "images": [
                {
                    "id": "debian-xfce",
                    "distro": "debian",
                    "desktop": "xfce",
                    "url": "http://example.com/debian.tar.gz"
                },
                {
                    "id": "alpine-lxqt",
                    "distro": "alpine",
                    "desktop": "lxqt",
                    "url": "https://example.com/alpine.tar.gz"
                },
                {
                    "id": "bad-ftp",
                    "distro": "kali",
                    "desktop": "mate",
                    "url": "ftp://example.com/kali.tar.gz"
                }
            ]
        }
        """.trimIndent()

        val manifest = ContainerImageCatalog.parse(sampleJson)

        // The insecure HTTP URL should be upgraded to HTTPS
        val debian = manifest.images.firstOrNull { it.id == "debian-xfce" }
        assertEquals("https://example.com/debian.tar.gz", debian?.url)

        // The valid HTTPS URL should remain intact
        val alpine = manifest.images.firstOrNull { it.id == "alpine-lxqt" }
        assertEquals("https://example.com/alpine.tar.gz", alpine?.url)

        // The non-HTTPS scheme (ftp://) should be rejected
        val ftp = manifest.images.firstOrNull { it.id == "bad-ftp" }
        assertEquals(null, ftp)
    }

    @Test
    fun networkSecurityConfigFileExistsAndRestrictsWanCleartext() {
        val configFile = File("src/main/res/xml/network_security_config.xml")
        val altConfigFile = File("app/src/main/res/xml/network_security_config.xml")
        val target = if (configFile.exists()) configFile else altConfigFile

        assertTrue("network_security_config.xml exists", target.exists())
        val xml = target.readText()

        assertTrue("base-config cleartextTrafficPermitted is false", xml.contains("base-config cleartextTrafficPermitted=\"false\""))
        assertTrue("loopback 127.0.0.1 is permitted for proot sockets", xml.contains("<domain includeSubdomains=\"false\">127.0.0.1</domain>"))
        assertTrue("localhost is permitted for proot sockets", xml.contains("<domain includeSubdomains=\"false\">localhost</domain>"))
    }
}
