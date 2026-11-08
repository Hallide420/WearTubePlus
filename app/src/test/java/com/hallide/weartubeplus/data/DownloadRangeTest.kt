package com.hallide.weartubeplus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRangeTest {

    @Test
    fun `range end is inclusive`() {
        assertEquals(DownloadRange.CHUNK - 1, DownloadRange.end(0))
        assertEquals(4L * DownloadRange.CHUNK + DownloadRange.CHUNK - 1, DownloadRange.end(4L * DownloadRange.CHUNK))
    }

    @Test
    fun `total is parsed from content-range`() {
        assertEquals(12_345_678L, DownloadRange.totalFrom("bytes 0-524287/12345678"))
        assertEquals(1_005L, DownloadRange.totalFrom("bytes 1004-1004/1005"))
    }

    @Test
    fun `unknown total yields null`() {
        assertNull(DownloadRange.totalFrom(null))
        assertNull(DownloadRange.totalFrom("bytes 0-1/*"))
        assertNull(DownloadRange.totalFrom("garbage"))
    }

    @Test
    fun `completion needs a known positive total`() {
        assertTrue(DownloadRange.isComplete(100, 100))
        assertTrue(DownloadRange.isComplete(101, 100))
        assertFalse(DownloadRange.isComplete(99, 100))
        assertFalse(DownloadRange.isComplete(0, 0))
        assertFalse(DownloadRange.isComplete(0, -1))
    }
}
