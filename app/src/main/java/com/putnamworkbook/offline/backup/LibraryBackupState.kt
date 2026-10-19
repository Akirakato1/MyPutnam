package com.putnamworkbook.offline.backup

data class LibraryBackupState(
    val desiredKeys: Set<String>,
    val logicallyRemovedBundledKeys: Set<String>,
    val activeDigests: Map<String, String>,
)
