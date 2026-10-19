package com.putnamworkbook.offline.data.library.packages

import com.putnamworkbook.offline.data.library.catalog.PackageDescriptor
import java.io.InputStream
import java.nio.file.Path

data class StagedPackage(
    val contentKey: String,
    val descriptor: PackageDescriptor,
    val envelope: PackageEnvelope,
    val stagingRoot: Path,
    val payloadRoot: Path,
)

data class PackageLocation(val root: Path, val digest: String)

interface PackageStore {
    suspend fun recoverInterruptedState()
    suspend fun stage(contentKey: String, descriptor: PackageDescriptor, input: InputStream): StagedPackage
    suspend fun activate(staged: StagedPackage): PackageLocation
    suspend fun ensureBundledExtracted(contentKey: String, descriptor: PackageDescriptor): PackageLocation
    suspend fun delete(location: PackageLocation)
}

fun interface BundledPackageOpener {
    fun open(path: String): InputStream
}
