package com.putnamworkbook.offline.freeworkspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class FreeWorkspaceNameTest {
    @Test
    fun trimsAndPreservesDisplayCapitalization() {
        val validated = validateWorkspaceName("  My Scratch Work  ", emptyList()).getOrThrow()

        assertEquals("My Scratch Work", validated.display)
        assertEquals("my scratch work", validated.uniquenessKey)
    }

    @Test
    fun rejectsBlankControlAndOverEightyCodePoints() {
        assertNameError(FreeWorkspaceNameError.BLANK, " \t\n ")
        assertNameError(FreeWorkspaceNameError.CONTROL_CHARACTER, "notes\u0007")
        assertNameError(FreeWorkspaceNameError.TOO_LONG, "📐".repeat(81))
        assertEquals(80, validateWorkspaceName("📐".repeat(80), emptyList()).getOrThrow().display.codePointCount(0, 160))
    }

    @Test
    fun nfkcAndCaseVariantsShareOneUniqueName() {
        val existing = listOf(record("Ｍａｔｈ", "00000000-0000-0000-0000-000000000001"))

        assertNameError(FreeWorkspaceNameError.DUPLICATE, "math", existing)
        assertNameError(FreeWorkspaceNameError.DUPLICATE, "MATH", existing)
    }

    @Test
    fun renameMayKeepItsOwnNormalizedName() {
        val own = record("My Notes", "00000000-0000-0000-0000-000000000002")

        val renamed = validateWorkspaceName("  MY NOTES  ", listOf(own), selfId = own.id).getOrThrow()

        assertEquals("MY NOTES", renamed.display)
        assertEquals("my notes", renamed.uniquenessKey)
    }

    private fun assertNameError(
        expected: FreeWorkspaceNameError,
        candidate: String,
        existing: List<FreeWorkspaceRecord> = emptyList(),
    ) {
        val error = assertThrows(FreeWorkspaceNameException::class.java) {
            validateWorkspaceName(candidate, existing).getOrThrow()
        }
        assertEquals(expected, error.error)
    }

    private fun record(name: String, id: String) = FreeWorkspaceRecord(
        id = UUID.fromString(id),
        name = name,
        createdAtEpochMillis = 10L,
        modifiedAtEpochMillis = 20L,
    )
}
