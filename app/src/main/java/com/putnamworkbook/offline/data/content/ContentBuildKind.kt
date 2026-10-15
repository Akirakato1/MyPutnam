package com.putnamworkbook.offline.data.content

internal object ContentBuildKind {
    private val supported = setOf("set", "archive", "release")

    fun isSupported(value: String): Boolean = value in supported
}
