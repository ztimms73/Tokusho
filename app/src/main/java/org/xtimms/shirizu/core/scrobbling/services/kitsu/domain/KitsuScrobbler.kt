package org.xtimms.shirizu.core.scrobbling.services.kitsu.domain

import org.xtimms.shirizu.core.database.ShirizuDatabase
import org.xtimms.shirizu.core.parser.MangaRepository
import org.xtimms.shirizu.core.scrobbling.domain.Scrobbler
import org.xtimms.shirizu.core.scrobbling.domain.model.ScrobblerService
import org.xtimms.shirizu.core.scrobbling.domain.model.ScrobblingStatus
import org.xtimms.shirizu.core.scrobbling.services.kitsu.data.KitsuRepository
import javax.inject.Inject

class KitsuScrobbler @Inject constructor(
    private val repository: KitsuRepository,
    db: ShirizuDatabase,
    mangaRepositoryFactory: MangaRepository.Factory,
) : Scrobbler(db, ScrobblerService.KITSU, repository, mangaRepositoryFactory) {

    init {
        statuses[ScrobblingStatus.PLANNED] = "planned"
        statuses[ScrobblingStatus.READING] = "current"
        statuses[ScrobblingStatus.COMPLETED] = "completed"
        statuses[ScrobblingStatus.ON_HOLD] = "on_hold"
        statuses[ScrobblingStatus.DROPPED] = "dropped"
    }

    override suspend fun updateScrobblingInfo(
        mangaId: Long,
        rating: Float,
        status: ScrobblingStatus?,
        comment: String?
    ) {
        val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId)
        requireNotNull(entity) { "Scrobbling info for manga $mangaId not found" }
        repository.updateRate(
            rateId = entity.id,
            mangaId = entity.mangaId,
            rating = rating,
            status = statuses[status],
            comment = comment,
        )
    }

}