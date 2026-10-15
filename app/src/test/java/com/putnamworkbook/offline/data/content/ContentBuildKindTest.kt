package com.putnamworkbook.offline.data.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBuildKindTest {
    @Test
    fun releaseLibrariesAreSupportedByStartupValidation() {
        assertTrue(ContentBuildKind.isSupported("release"))
    }

    @Test
    fun knownDevelopmentLibraryKindsRemainSupported() {
        assertTrue(ContentBuildKind.isSupported("set"))
        assertTrue(ContentBuildKind.isSupported("archive"))
        assertFalse(ContentBuildKind.isSupported("unknown"))
    }
}
