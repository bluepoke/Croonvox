// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, TakeEntity::class, SettingEntity::class, LyricsEntity::class, LyricsOffsetEntity::class],
    version = 11,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // 2: takes and settings tables (#8)
        AutoMigration(from = 2, to = 3), // 3: pipeline progress and failure reason (#7)
        AutoMigration(from = 3, to = 4), // 4: lyrics table (#11)
        AutoMigration(from = 4, to = 5), // 5: lyrics rows go with their Track (#11)
        AutoMigration(from = 5, to = 6), // 6: lyrics offsets (#12)
        AutoMigration(from = 6, to = 7), // 7: each Take's Transpose (#10)
        AutoMigration(from = 7, to = 8), // 8: when each Track was last sung (#20)
        AutoMigration(from = 8, to = 9), // 9: Vocal-Free Tracks (#25)
        AutoMigration(from = 9, to = 10), // 10: each Track's Backing Audio choice (#34)
        AutoMigration(from = 10, to = 11), // 11: the Separation Model picked for each Track (#17)
    ],
)
abstract class CroonvoxDatabase : RoomDatabase() {
    internal abstract fun tracks(): TrackDao

    internal abstract fun takes(): TakeDao

    internal abstract fun settings(): SettingsDao

    internal abstract fun lyrics(): LyricsDao

    companion object {
        fun create(context: Context): CroonvoxDatabase =
            Room.databaseBuilder(context.applicationContext, CroonvoxDatabase::class.java, "croonvox.db").build()
    }
}
