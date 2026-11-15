// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TrackEntity::class], version = 1, exportSchema = true)
abstract class CroonvoxDatabase : RoomDatabase() {
    internal abstract fun tracks(): TrackDao

    companion object {
        fun create(context: Context): CroonvoxDatabase =
            Room.databaseBuilder(context.applicationContext, CroonvoxDatabase::class.java, "croonvox.db").build()
    }
}
