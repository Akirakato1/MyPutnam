package com.putnamworkbook.offline.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReferenceKeyTest {
    @Test
    fun canonicalRoundTripIsCaseSensitive() {
        val key = ReferenceKey("Number-Theory", "Euler-Totient")
        assertEquals("math-reference/Number-Theory/Euler-Totient", key.canonical)
        assertEquals(key, ReferenceKey.parse(key.canonical))
        assertNotEquals(key, ReferenceKey("number-theory", "euler-totient"))
        assertEquals(key.hashCode(), ReferenceKey.parse(key.canonical).hashCode())
    }

    @Test
    fun parserRejectsWrongNamespaceUnsafeAndExtraSegments() {
        listOf(
            "reference/number-theory/euler-totient",
            "math-reference/number-theory",
            "math-reference/number-theory/euler-totient/extra",
            "math-reference/../euler-totient",
            "math-reference/number-theory/.",
            "math-reference//euler-totient",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { ReferenceKey.parse(value) }
        }
    }
}
