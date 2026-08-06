package com.example.videocall.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recent meetings shown in the lobby, backed by [MeetingHistoryDbHelper]. */
class MeetingHistoryRepository(context: Context) {

    private val dbHelper = MeetingHistoryDbHelper(context)

    suspend fun recentMeetings(limit: Int = RECENT_LIMIT): List<RecentMeeting> =
        withContext(Dispatchers.IO) { dbHelper.recentMeetings(limit) }

    /** Called once a meeting ends. Upserts by code, so rejoining bumps it back to the top. */
    suspend fun recordMeeting(code: String, title: String, startedAt: Long, participantCount: Int) {
        withContext(Dispatchers.IO) {
            val resolvedTitle = title.ifBlank { dbHelper.titleForCode(code) ?: code }
            dbHelper.upsert(
                RecentMeeting(
                    code = code,
                    title = resolvedTitle,
                    startedAt = startedAt,
                    participantCount = participantCount,
                ),
            )
        }
    }

    private companion object {
        const val RECENT_LIMIT = 5
    }
}
