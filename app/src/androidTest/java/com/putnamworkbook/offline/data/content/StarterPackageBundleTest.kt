package com.putnamworkbook.offline.data.content

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.putnamworkbook.offline.data.library.catalog.CatalogCodec
import com.putnamworkbook.offline.data.library.packages.PackageArchiveValidator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StarterPackageBundleTest {
    @Test
    fun embeddedCatalogAndEveryFlattenedPackageAreCompleteAndValid() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val catalog = CatalogCodec().decode(
            assets.open("content-library/catalog.json").use { it.readBytes() },
        )
        assertEquals(72, catalog.problems.size)
        assertEquals(97, catalog.references.size)

        val validator = PackageArchiveValidator(currentAppVersionCode = 5)
        val entries = catalog.problems.map { it.key to it.packageDescriptor } +
            catalog.references.map { it.key to it.packageDescriptor }
        assertEquals(169, entries.size)
        entries.forEach { (key, descriptor) ->
            val bytes = assets.open("content-library/packages/${descriptor.sha256}.zip").use { it.readBytes() }
            validator.validate(bytes, descriptor, key)
        }
    }
}
