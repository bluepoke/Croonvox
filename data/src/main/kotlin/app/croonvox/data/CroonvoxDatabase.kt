// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, TakeEntity::class, SettingEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)], // 2: takes and settings tables (#8)
)
abstract class CroonvoxDatabase : RoomDatabase() {
    internal abstract fun tracks(): TrackDao

    internal abstract fun takes(): TakeDao

    internal abstract fun settings(): SettingsDao

    companion object {
        fun create(context: Context): CroonvoxDatabase =
            Room.databaseBuilder(context.applicationContext, CroonvoxDatabase::class.java, "croonvox.db").build()
    }
}
