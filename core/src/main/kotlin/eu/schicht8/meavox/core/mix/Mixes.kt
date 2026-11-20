// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.mix

import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.model.BackingChoice
import eu.schicht8.meavox.core.model.Take
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.session.BackingAudio
import eu.schicht8.meavox.core.session.Session
import eu.schicht8.meavox.core.session.SettingsRepository
import eu.schicht8.meavox.core.session.TakeRepository
import eu.schicht8.meavox.core.session.backingAudio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * A Track's Mixes (GLOSSARY: Mix, ADR 7): the default one made when a Take is kept, more of them
 * from any Take, and edits of their Mix Settings, which never touch the Take.
 */
class Mixes(
    private val tracks: TrackRepository,
    private val takes: TakeRepository,
    private val mixes: MixRepository,
    private val files: TrackFiles,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newMixId: () -> MixId = { MixId(UUID.randomUUID().toString()) },
) {
    /** The Track's Mixes, oldest first. */
    fun observe(track: TrackId): Flow<List<Mix>> = mixes.observe(track)

    /**
     * A new Mix of the kept [take], over what the Track plays now (its [backingAudio]), at the
     * Review Balance like a default Mix.
     */
    suspend fun create(take: TakeId): Mix {
        val stored = checkNotNull(takes.get(take)) { "no Take ${take.value}" }
        val track = checkNotNull(tracks.get(stored.track)) { "no Track ${stored.track.key}" }
        return add(stored, track.backingAudio())
    }

    /** Stores new Mix Settings for the Mix; the Take is never changed (ADR 7). */
    suspend fun updateSettings(id: MixId, settings: MixSettings) {
        val mix = mixes.get(id) ?: return
        mixes.update(mix.copy(settings = settings))
    }

    /**
     * Switches the Mix to the Track's Instrumental or to its Original Audio. Returns false, and
     * changes nothing, for the Instrumental while the Track has none (e.g. a Vocal-Free Track, ADR 14).
     */
    suspend fun chooseBacking(id: MixId, choice: BackingChoice): Boolean {
        val mix = mixes.get(id) ?: return false
        val backing = when (choice) {
            BackingChoice.OriginalAudio -> BackingAudio.OriginalAudio
            BackingChoice.Instrumental -> tracks.get(mix.track)?.instrumentalModel?.let(BackingAudio::Instrumental) ?: return false
        }
        mixes.update(mix.copy(backing = backing))
        return true
    }

    /** Deletes the Mix; its Take stays. */
    suspend fun delete(id: MixId) = mixes.delete(id)

    /**
     * Adds a Mix of [take] over [backing] at the Review Balance, so it sounds like Review (#14). The
     * default Mix made when a Take is kept is one, over the Backing Audio it was sung over.
     */
    internal suspend fun add(take: Take, backing: BackingAudio): Mix {
        val backingDb = settings.reviewBackingDb() ?: Session.DEFAULT_REVIEW_BACKING_DB
        val mix = Mix(
            id = newMixId(),
            track = take.track,
            take = take.id,
            name = nextName(take.track),
            backing = backing,
            settings = MixSettings.clamped(backingDb, 0f, 0),
            createdAt = clock(),
        )
        mixes.add(mix)
        return mix
    }

    /** The Mix's Take and Backing Audio as the Track is now (see [resolveMix]). */
    internal suspend fun resolve(id: MixId): MixParts = resolveMix(id, mixes, tracks, takes, files)

    /** Deletes the Mixes of a Take that is being deleted. */
    internal suspend fun deleteForTake(take: TakeId) = mixes.deleteForTake(take)

    private suspend fun nextName(track: TrackId): String {
        val names = mixes.observe(track).first().mapTo(HashSet()) { it.name }
        var n = names.size + 1
        while ("Mix $n" in names) n++
        return "Mix $n"
    }
}
