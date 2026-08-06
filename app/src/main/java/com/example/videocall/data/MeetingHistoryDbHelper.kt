package com.example.videocall.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One meeting the user has created or joined, kept for the lobby's recent-meetings list. */
data class RecentMeeting(
    val code: String,
    val title: String,
    val startedAt: Long,
    val participantCount: Int,
)

/** Plain SQLite store for [RecentMeeting] rows — no Room/KSP dependency required. */
class MeetingHistoryDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_CODE TEXT PRIMARY KEY,
                $COL_TITLE TEXT NOT NULL,
                $COL_STARTED_AT INTEGER NOT NULL,
                $COL_PARTICIPANTS INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun recentMeetings(limit: Int): List<RecentMeeting> {
        readableDatabase.query(
            TABLE,
            arrayOf(COL_CODE, COL_TITLE, COL_STARTED_AT, COL_PARTICIPANTS),
            null,
            null,
            null,
            null,
            "$COL_STARTED_AT DESC",
            limit.toString(),
        ).use { cursor ->
            val meetings = mutableListOf<RecentMeeting>()
            while (cursor.moveToNext()) {
                meetings += RecentMeeting(
                    code = cursor.getString(0),
                    title = cursor.getString(1),
                    startedAt = cursor.getLong(2),
                    participantCount = cursor.getInt(3),
                )
            }
            return meetings
        }
    }

    fun titleForCode(code: String): String? =
        readableDatabase.query(
            TABLE,
            arrayOf(COL_TITLE),
            "$COL_CODE = ?",
            arrayOf(code),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun upsert(meeting: RecentMeeting) {
        val values = ContentValues().apply {
            put(COL_CODE, meeting.code)
            put(COL_TITLE, meeting.title)
            put(COL_STARTED_AT, meeting.startedAt)
            put(COL_PARTICIPANTS, meeting.participantCount)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private companion object {
        const val DB_NAME = "videocall.db"
        const val DB_VERSION = 1
        const val TABLE = "recent_meetings"
        const val COL_CODE = "code"
        const val COL_TITLE = "title"
        const val COL_STARTED_AT = "started_at"
        const val COL_PARTICIPANTS = "participant_count"
    }
}
