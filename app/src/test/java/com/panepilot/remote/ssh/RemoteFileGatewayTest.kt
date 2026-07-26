package com.panepilot.remote.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFileGatewayTest {
    @Test
    fun normalizeRelativePathAllowsProjectChildren() {
        assertEquals(
            "results/checkpoint.bin",
            RemoteFileGateway.normalizeRelativePath("results/checkpoint.bin")
        )
        assertEquals("", RemoteFileGateway.normalizeRelativePath(""))
    }

    @Test
    fun normalizeRelativePathRejectsEscapesAndAbsolutePaths() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFileGateway.normalizeRelativePath("../outside.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFileGateway.normalizeRelativePath("/etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFileGateway.normalizeRelativePath("results//checkpoint.bin")
        }
    }

    @Test
    fun rootBoundaryDoesNotMatchSiblingPrefixes() {
        assertTrue(
            RemoteFileGateway.isWithinRoot(
                "/work/project",
                "/work/project/results/model.pt"
            )
        )
        assertFalse(
            RemoteFileGateway.isWithinRoot(
                "/work/project",
                "/work/project-copy/secrets.txt"
            )
        )
    }

    @Test
    fun parentPathStopsAtProjectRoot() {
        assertEquals("results", RemoteFileGateway.parentPath("results/archive"))
        assertEquals("", RemoteFileGateway.parentPath("results"))
        assertEquals("", RemoteFileGateway.parentPath(""))
    }
}
