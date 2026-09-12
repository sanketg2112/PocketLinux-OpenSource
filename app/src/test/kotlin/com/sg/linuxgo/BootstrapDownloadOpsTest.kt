package com.sg.linuxgo

import com.sg.linuxgo.bootstrap.BootstrapDownloadOps
import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapDownloadOpsTest {

    private val ops = BootstrapDownloadOps(
        downloadOverallProgress = { it },
        downloadBandStart = { 0 }
    )

    @Test
    fun parseContentRangeTotal() {
        assertEquals(123456L, ops.parseContentRangeTotal("bytes 0-0/123456"))
        assertEquals(999L, ops.parseContentRangeTotal("bytes */999"))
        assertEquals(-1L, ops.parseContentRangeTotal(null))
        assertEquals(-1L, ops.parseContentRangeTotal(""))
        assertEquals(-1L, ops.parseContentRangeTotal("bytes 0-0/"))
        assertEquals(-1L, ops.parseContentRangeTotal("not-a-range"))
    }

    @Test
    fun parallelThreadCountScalesWithSize() {
        assertEquals(3, ops.parallelThreadCount(1L * 1024 * 1024))
        assertEquals(4, ops.parallelThreadCount(25L * 1024 * 1024))
        assertEquals(6, ops.parallelThreadCount(150L * 1024 * 1024))
        assertEquals(8, ops.parallelThreadCount(500L * 1024 * 1024))
    }

    @Test
    fun resolveRedirectLocationHandlesRelative() {
        assertEquals(
            "https://cdn.example.com/file.tar.gz",
            ops.resolveRedirectLocation(
                "https://cdn.example.com/path/old",
                "/file.tar.gz"
            )
        )
        assertEquals(
            "https://other.example/x",
            ops.resolveRedirectLocation(
                "https://cdn.example.com/a",
                "https://other.example/x"
            )
        )
    }

    @Test
    fun publicHostLabelNeverExposesPath() {
        assertEquals(
            "github.com",
            ops.publicHostLabel(
                "https://github.com/owner/repo/releases/download/v1.0.0/secret-asset.tar.gz"
            )
        )
        assertEquals(
            "release-assets.githubusercontent.com",
            ops.publicHostLabel(
                "https://release-assets.githubusercontent.com/github-production-release-asset/1/x?sig=abc"
            )
        )
        assertEquals("download server", ops.publicHostLabel("not-a-url"))
    }

    @Test
    fun isTransientHttpCodeCoversRetryableStatuses() {
        assertEquals(true, ops.isTransientHttpCode(500))
        assertEquals(true, ops.isTransientHttpCode(502))
        assertEquals(true, ops.isTransientHttpCode(503))
        assertEquals(true, ops.isTransientHttpCode(429))
        assertEquals(true, ops.isTransientHttpCode(408))
        assertEquals(false, ops.isTransientHttpCode(404))
        assertEquals(false, ops.isTransientHttpCode(403))
        assertEquals(false, ops.isTransientHttpCode(200))
    }
}
