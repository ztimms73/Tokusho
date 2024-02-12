package org.xtimms.tokusho.sections.details.data

import android.content.res.Resources
import org.xtimms.tokusho.R

data class ReadingTime(
    val minutes: Int,
    val hours: Int,
    val isContinue: Boolean,
) {

    fun format(resources: Resources): String = when {
        hours == 0 -> resources.getQuantityString(R.plurals.minutes, minutes, minutes)
        minutes == 0 -> resources.getQuantityString(R.plurals.hours, hours, hours)
        else -> resources.getString(
            R.string.remaining_time_pattern,
            resources.getQuantityString(R.plurals.hours, hours, hours),
            resources.getQuantityString(R.plurals.minutes, minutes, minutes),
        )
    }
}