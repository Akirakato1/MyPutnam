package com.putnamworkbook.offline.data.content

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentAssetUpgradeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun changedPackagedAssetReplacesOnlyTheImmutableContentDatabase() = runBlocking {
        val initial = ContentDatabase.create(context, DATABASE_NAME, PREFERENCES_NAME, FIXTURE_ASSET)
        val packagedVersion = initial.contentDao().meta("content_version")
        assertEquals("3", initial.contentDao().meta("schema_version"))
        initial.openHelper.writableDatabase.execSQL(
            "UPDATE content_meta SET value = 'stale-version' WHERE `key` = 'content_version'",
        )
        initial.close()

        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(ContentDatabase.CONTENT_ASSET_HASH_KEY, "obsolete-asset-hash")
            .commit()

        val refreshed = ContentDatabase.create(context, DATABASE_NAME, PREFERENCES_NAME, FIXTURE_ASSET)
        try {
            assertEquals(packagedVersion, refreshed.contentDao().meta("content_version"))
        } finally {
            refreshed.close()
        }
    }

    private companion object {
        const val DATABASE_NAME = "content-upgrade-test.db"
        const val PREFERENCES_NAME = "content-upgrade-test-state"
        const val FIXTURE_ASSET = "databases/content-fixture.db"
    }
}
