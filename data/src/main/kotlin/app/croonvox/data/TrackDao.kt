// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class TrackDao {
    @Query("SELECT * FROM tracks ORDER BY added_at DESC")
    abstract fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE source = :source AND source_track_id = :sourceTrackId")
    abstract suspend fun get(source: String, sourceTrackId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE source = :source AND source_track_id = :sourceTrackId")
    abstract fun observe(source: String, sourceTrackId: String): Flow<TrackEntity?>

    @Query("UPDATE tracks SET pipeline_state = :state WHERE source = :source AND source_track_id = :sourceTrackId")
    protected abstract suspend fun setPipelineState(source: String, sourceTrackId: String, state: String): Int

    /** Sets the pipeline column of an existing row and returns the updated row. */
    @Transaction
    open suspend fun updatePipelineState(source: String, sourceTrackId: String, state: String): TrackEntity {
        check(setPipelineState(source, sourceTrackId, state) == 1) { "no Track $source:$sourceTrackId" }
        return checkNotNull(get(source, sourceTrackId))
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertOrIgnore(track: TrackEntity): Long

    /** Inserts [track] unless its identity exists; returns the stored row. */
    @Transaction
    open suspend fun insertIfAbsent(track: TrackEntity): TrackEntity {
        insertOrIgnore(track)
        return checkNotNull(get(track.source, track.sourceTrackId))
    }
}
