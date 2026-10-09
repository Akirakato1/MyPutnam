package com.putnamworkbook.offline.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProblemKeyTest {
    @Test
    fun canonicalKeysRoundTripWithoutChangingCase() {
        val set = ProblemSetKey("Course", "chapter-1")
        val problem = ProblemKey("Course", "chapter-1", "P1")

        assertEquals("Course/chapter-1", set.canonical)
        assertEquals(set, ProblemSetKey.parse(set.canonical))
        assertEquals("Course/chapter-1/P1", problem.canonical)
        assertEquals(problem, ProblemKey.parse(problem.canonical))
        assertEquals(set, problem.setKey)
        assertNotEquals(problem, ProblemKey("Course", "chapter-1", "p1"))
    }

    @Test
    fun everySegmentUsesTheSharedPathSafeGrammar() {
        listOf("", ".", "..", "a/b", "a b", "a:b", "ümlaut", "a\\b").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ProblemKey(invalid, "set", "problem")
            }
            assertThrows(IllegalArgumentException::class.java) {
                ProblemKey("collection", invalid, "problem")
            }
            assertThrows(IllegalArgumentException::class.java) {
                ProblemKey("collection", "set", invalid)
            }
        }

        assertEquals(
            "a.Z_9-b/set.v1/P_1-2",
            ProblemKey("a.Z_9-b", "set.v1", "P_1-2").canonical,
        )
    }

    @Test
    fun parsersRequireExactlyTheCanonicalNumberOfSegments() {
        listOf("", "putnam", "putnam/2025/A1", "putnam//2025").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { ProblemSetKey.parse(invalid) }
        }
        listOf("", "putnam/2025", "putnam/2025/A1/extra", "putnam/../A1").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { ProblemKey.parse(invalid) }
        }
    }
}
