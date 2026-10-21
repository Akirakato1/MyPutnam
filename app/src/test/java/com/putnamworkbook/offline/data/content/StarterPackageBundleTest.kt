package com.putnamworkbook.offline.data.content

import com.putnamworkbook.offline.data.library.catalog.CatalogCodec
import com.putnamworkbook.offline.data.library.packages.PackageArchiveValidator
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StarterPackageBundleTest {
    @Test
    fun generatedAndroidAssetsContainOnlyTheCompleteVerifiedPackageLibrary() {
        val assets = assetRoot()
        val catalog = CatalogCodec().decode(Files.readAllBytes(assets.resolve("content-library/catalog.json")))
        assertEquals(72, catalog.problems.size)
        assertEquals(97, catalog.references.size)

        val entries = catalog.problems.map { it.key to it.packageDescriptor } +
            catalog.references.map { it.key to it.packageDescriptor }
        assertEquals(169, entries.size)
        assertEquals(169, Files.list(assets.resolve("content-library/packages")).use { it.count() })

        val validator = PackageArchiveValidator(currentAppVersionCode = 5)
        entries.forEach { (key, descriptor) ->
            val archive = assets.resolve("content-library/packages/${descriptor.sha256}.zip")
            validator.validate(Files.readAllBytes(archive), descriptor, key)
        }

        assertFalse(Files.exists(assets.resolve("content")))
        assertFalse(Files.exists(assets.resolve("databases/content.db")))
    }

    private fun assetRoot(): Path = sequenceOf(
        Path.of("app/src/main/assets"),
        Path.of("src/main/assets"),
    ).map(Path::toAbsolutePath).first(Files::isDirectory)
}
