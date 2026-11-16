// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import app.croonvox.core.model.InputDevice
import app.croonvox.core.session.SettingsRepository

/** One user setting as a key/value row; keys are stable strings. */
@Entity(tableName = "settings")
internal data class SettingEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)

@Dao
internal interface SettingsDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(setting: SettingEntity)
}

/** [SettingsRepository] on Room. */
class RoomSettingsRepository(database: CroonvoxDatabase) : SettingsRepository {
    private val dao = database.settings()

    override suspend fun countdownEnabled(): Boolean = dao.get(COUNTDOWN)?.toBooleanStrictOrNull() ?: false

    override suspend fun setCountdownEnabled(enabled: Boolean) = dao.put(SettingEntity(COUNTDOWN, enabled.toString()))

    override suspend fun reviewBackingDb(): Float? = dao.get(REVIEW_BACKING)?.toFloatOrNull()

    override suspend fun setReviewBackingDb(gainDb: Float) = dao.put(SettingEntity(REVIEW_BACKING, gainDb.toString()))

    override suspend fun inputGainDb(device: InputDevice): Float = dao.get(gainKey(device))?.toFloatOrNull() ?: 0f

    override suspend fun setInputGainDb(device: InputDevice, gainDb: Float) =
        dao.put(SettingEntity(gainKey(device), gainDb.toString()))

    private fun gainKey(device: InputDevice): String = "input_gain_db:" + when (device) {
        InputDevice.BuiltIn -> "built_in"
        InputDevice.Wired -> "wired"
        InputDevice.Usb -> "usb"
        InputDevice.Bluetooth -> "bluetooth"
        InputDevice.Other -> "other"
    }

    private companion object {
        const val COUNTDOWN = "countdown_enabled"
        const val REVIEW_BACKING = "review_backing_db"
    }
}
