package com.putnamworkbook.offline.data.user

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserDatabaseSchemaTest {
    @Test
    fun userDatabaseIsVersionedAndContainsNoBundledContentTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val sqlite = database.openHelper.writableDatabase
            val tableNames = sqlite.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }

            assertTrue("problem_progress" in tableNames)
            assertTrue("app_setting" in tableNames)
            assertFalse("problem" in tableNames)
            assertEquals(2, sqlite.version)
        } finally {
            database.close()
        }
    }
}
