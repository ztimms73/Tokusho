package org.xtimms.shirizu.sections.details.domain

import org.xtimms.shirizu.core.model.MangaHistory
import org.xtimms.shirizu.core.model.findById
import org.xtimms.shirizu.core.prefs.AppSettings
import org.xtimms.shirizu.sections.details.data.MangaDetails
import org.xtimms.shirizu.sections.details.data.ReadingTime
import javax.inject.Inject
import kotlin.math.roundToInt

class ReadingTimeUseCase @Inject constructor() {

    fun invoke(manga: MangaDetails?, branch: String?, history: MangaHistory?): ReadingTime? {
        if (!AppSettings.isReadingTimeEstimationEnabled()) {
            return null
        }
        // FIXME MAXIMUM HARDCODE!!! To do calculation with user's page read speed and his favourites/history mangas average pages in chapter
        val chapters = manga?.chapters?.get(branch)
        if (chapters.isNullOrEmpty()) {
            return null
        }
        val isOnHistoryBranch = history != null && chapters.findById(history.chapterId) != null
        // Impossible task, I guess. Good luck on this.
        var averageTimeSec: Int = 20 * 10 * chapters.size // 20 pages, 10 seconds per page
        if (isOnHistoryBranch) {
            averageTimeSec = (averageTimeSec * (1f - checkNotNull(history).percent)).roundToInt()
        }
        if (averageTimeSec < 60) {
            return null
        }
        return ReadingTime(
            minutes = (averageTimeSec / 60) % 60,
            hours = averageTimeSec / 3600,
            isContinue = isOnHistoryBranch,
        )
    }
}