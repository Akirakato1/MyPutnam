package com.putnamworkbook.offline.data.content

import androidx.room.Dao
import androidx.room.Query

@Dao
interface ContentDao {
    @Query("SELECT DISTINCT year FROM problem ORDER BY year DESC")
    suspend fun years(): List<Int>

    @Query("SELECT * FROM problem WHERE year = :year ORDER BY ordinal, code")
    suspend fun problems(year: Int): List<ProblemEntity>

    @Query("SELECT * FROM problem WHERE year = :year AND code = :code")
    suspend fun problem(year: Int, code: String): ProblemEntity?

    @Query(
        "SELECT * FROM hint WHERE year = :year AND problem_code = :code " +
            "ORDER BY hint_order",
    )
    suspend fun hints(year: Int, code: String): List<HintEntity>

    @Query(
        "SELECT * FROM prerequisite WHERE year = :year AND problem_code = :code " +
            "ORDER BY prerequisite_order",
    )
    suspend fun prerequisites(year: Int, code: String): List<PrerequisiteEntity>

    @Query("SELECT value FROM content_meta WHERE `key` = :key")
    suspend fun meta(key: String): String?
}
