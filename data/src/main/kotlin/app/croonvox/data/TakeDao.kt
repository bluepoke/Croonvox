// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface TakeDao {
    @Query("SELECT * FROM takes WHERE source = :source AND source_track_id = :sourceTrackId ORDER BY recorded_at, id")
    fun observe(source: String, sourceTrackId: String): Flow<List<TakeEntity>>

    @Query("SELECT * FROM takes WHERE id = :id")
    suspend fun get(id: String): TakeEntity?

    @Insert
    suspend fun insert(take: TakeEntity)

    @Query("UPDATE takes SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM takes WHERE id = :id")
    suspend fun delete(id: String)
}
