package com.putnamworkbook.offline.data.library.packages

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilePackageStoreInstrumentedTest {
    @Test
    fun recoveryIsConfinedToDedicatedPackageRoot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = context.cacheDir.toPath().resolve("package-store-${System.nanoTime()}")
        Files.createDirectories(root.resolve(".staging-interrupted"))
        Files.createDirectories(root.resolve("unrelated"))
        val store = FilePackageStore(
            root,
            PackageVerifier { _, _, _ -> throw AssertionError("verification not expected") },
            { throw AssertionError("bundle open not expected") },
        )

        store.recoverInterruptedState()

        assertFalse(Files.exists(root.resolve(".staging-interrupted")))
        assertTrue(Files.isDirectory(root.resolve("unrelated")))
    }
}
