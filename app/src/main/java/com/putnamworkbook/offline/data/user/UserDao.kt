package com.putnamworkbook.offline.data.user

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM problem_progress WHERE year = :year AND problem_code = :code")
    fun observeProgress(year: Int, code: String): Flow<ProblemProgressEntity?>

    @Query("SELECT * FROM problem_progress ORDER BY year DESC, problem_code")
    fun observeAllProgress(): Flow<List<ProblemProgressEntity>>

    @Query("SELECT * FROM problem_progress ORDER BY year, problem_code")
    suspend fun allProgressForBackup(): List<ProblemProgressEntity>

    @Query("SELECT * FROM problem_progress ORDER BY last_opened_at DESC, year DESC, problem_code LIMIT 1")
    fun observeRecentProgress(): Flow<ProblemProgressEntity?>

    @Query("SELECT * FROM problem_progress WHERE year = :year AND problem_code = :code")
    suspend fun progress(year: Int, code: String): ProblemProgressEntity?

    @Upsert
    suspend fun upsertProgress(progress: ProblemProgressEntity)

    @Query("DELETE FROM problem_progress")
    suspend fun deleteAllProgress()

    @Query("SELECT * FROM app_setting WHERE id = 1")
    fun observeSettings(): Flow<AppSettingEntity?>

    @Query("SELECT * FROM app_setting WHERE id = 1")
    suspend fun settings(): AppSettingEntity?

    @Upsert
    suspend fun upsertSettings(settings: AppSettingEntity)

    @Query("DELETE FROM app_setting")
    suspend fun deleteSettings()
}
