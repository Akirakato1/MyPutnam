package com.putnamworkbook.offline.data.content

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackagedReleaseDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: ContentDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = Room.databaseBuilder(context, ContentDatabase::class.java, DATABASE_NAME)
            .createFromInputStream {
                InstrumentationRegistry.getInstrumentation().targetContext.assets
                    .open("databases/content.db")
            }
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun packagedReleaseDatabasePassesTheSameValidationUsedAtStartup() = runBlocking {
        ContentIntegrityValidator(database).validate()
        assertEquals("release", database.contentDao().meta("build_kind"))
        assertEquals("72", database.contentDao().meta("problem_count"))
    }

    private companion object {
        const val DATABASE_NAME = "packaged-release-test.db"
    }
}
