package com.putnamworkbook.offline.data.content

fun interface ContentInitializer {
    suspend fun initialize(): ContentRepository
}
