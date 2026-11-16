// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.model.Take
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.TrackId
import app.croonvox.core.session.TakeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryTakeRepository : TakeRepository {
    private val takes = MutableStateFlow<List<Take>>(emptyList())

    override fun observe(track: TrackId): Flow<List<Take>> =
        takes.map { all -> all.filter { it.track == track }.sortedBy { it.recordedAt } }

    override suspend fun get(id: TakeId): Take? = takes.value.firstOrNull { it.id == id }

    override suspend fun add(take: Take) {
        takes.update { current ->
            check(current.none { it.id == take.id }) { "duplicate Take ${take.id}" }
            current + take
        }
    }

    override suspend fun rename(id: TakeId, name: String) {
        takes.update { current -> current.map { if (it.id == id) it.copy(name = name) else it } }
    }

    override suspend fun delete(id: TakeId) {
        takes.update { current -> current.filterNot { it.id == id } }
    }
}
