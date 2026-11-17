// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface TakeDao {
    @Query("SELECT * FROM takes WHERE source = :source AND source_track_id = :sourceTrackId ORDER BY recorded_at, id")
    fun observe(source: String, sourceTrackId: String): Flow<List<TakeEntity>>

    @Query("SELECT source, source_track_id, COUNT(*) AS count FROM takes GROUP BY source, source_track_id")
    fun observeCounts(): Flow<List<TakeCount>>

    @Query("SELECT * FROM takes WHERE id = :id")
    suspend fun get(id: String): TakeEntity?

    @Insert
    suspend fun insert(take: TakeEntity)

    @Query("UPDATE takes SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM takes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM takes WHERE source = :source AND source_track_id = :sourceTrackId")
    suspend fun deleteAll(source: String, sourceTrackId: String)
}

internal data class TakeCount(
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "source_track_id") val sourceTrackId: String,
    @ColumnInfo(name = "count") val count: Int,
)
