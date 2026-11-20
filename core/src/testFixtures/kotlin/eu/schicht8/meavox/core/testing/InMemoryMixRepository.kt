// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.testing

import eu.schicht8.meavox.core.mix.Mix
import eu.schicht8.meavox.core.mix.MixId
import eu.schicht8.meavox.core.mix.MixRepository
import eu.schicht8.meavox.core.model.TakeId
import eu.schicht8.meavox.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryMixRepository : MixRepository {
    private val mixes = MutableStateFlow<List<Mix>>(emptyList())

    override fun observe(track: TrackId): Flow<List<Mix>> =
        mixes.map { all -> all.filter { it.track == track }.sortedBy { it.createdAt } }

    override suspend fun get(id: MixId): Mix? = mixes.value.firstOrNull { it.id == id }

    override suspend fun add(mix: Mix) {
        mixes.update { current ->
            check(current.none { it.id == mix.id }) { "duplicate Mix ${mix.id}" }
            current + mix
        }
    }

    override suspend fun update(mix: Mix) {
        mixes.update { current -> current.map { if (it.id == mix.id) mix else it } }
    }

    override suspend fun delete(id: MixId) {
        mixes.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun deleteForTake(take: TakeId) {
        mixes.update { current -> current.filterNot { it.take == take } }
    }

    override suspend fun deleteAll(track: TrackId) {
        mixes.update { current -> current.filterNot { it.track == track } }
    }
}
