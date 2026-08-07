package com.example.videocall

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.videocall.data.RecentMeeting
import com.example.videocall.databinding.ItemRecentMeetingBinding
import dev.meshcall.sdk.api.MeetingStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Past meetings, each offering Rejoin only while that meeting is still live.
 *
 * A meeting exists only while somebody is in it, so history alone cannot say whether a
 * code is joinable — [setLiveMeetings] supplies that separately, once the broker has been
 * asked. Until it does, nothing is offered: a Rejoin button that leads to "meeting not
 * found" is worse than no button.
 */
class RecentMeetingAdapter(
    private val onRejoin: (RecentMeeting) -> Unit,
) : RecyclerView.Adapter<RecentMeetingAdapter.ViewHolder>() {

    private var meetings: List<RecentMeeting> = emptyList()

    /** Live meetings by code. Absent means "not live", which is also the initial state. */
    private var liveStatus: Map<String, MeetingStatus> = emptyMap()

    fun submitList(items: List<RecentMeeting>) {
        meetings = items
        // Liveness belongs to the previous list; keeping it would flash a Rejoin button
        // on whichever meeting happened to land in that position.
        liveStatus = emptyMap()
        notifyDataSetChanged()
    }

    /** What the broker says about these codes right now. Only live ones are kept. */
    fun setLiveMeetings(statuses: List<MeetingStatus>) {
        val live = statuses.filter { it.isLive }.associateBy { it.meetingId }
        if (liveStatus == live) return
        liveStatus = live
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentMeetingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val meeting = meetings[position]
        holder.bind(meeting, status = liveStatus[meeting.code], onRejoin = onRejoin)
    }

    override fun getItemCount(): Int = meetings.size

    class ViewHolder(private val binding: ItemRecentMeetingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            meeting: RecentMeeting,
            status: MeetingStatus?,
            onRejoin: (RecentMeeting) -> Unit,
        ) {
            val context = binding.root.context
            binding.meetingChip.text = relativeDayLabel(context, meeting.startedAt)
            binding.meetingTitle.text = meeting.title
            binding.meetingId.text = context.getString(R.string.meeting_id_format, meeting.code)
            binding.meetingParticipants.text =
                context.getString(R.string.meeting_participants, meeting.participantCount)

            val isLive = status != null
            binding.btnRejoin.visibility = if (isLive) View.VISIBLE else View.GONE
            binding.liveBadge.visibility = if (isLive) View.VISIBLE else View.GONE
            binding.privateBadge.visibility =
                if (status?.isPrivate == true) View.VISIBLE else View.GONE
            binding.btnRejoin.setOnClickListener { onRejoin(meeting) }
        }

        private fun relativeDayLabel(context: Context, startedAt: Long): String {
            val started = Calendar.getInstance().apply { timeInMillis = startedAt }
            val today = Calendar.getInstance()
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            return when {
                isSameDay(started, today) -> context.getString(
                    R.string.meeting_chip_today,
                    SimpleDateFormat("h:mm a", Locale.getDefault()).format(started.time),
                )
                isSameDay(started, yesterday) -> context.getString(R.string.meeting_chip_yesterday)
                else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(started.time)
            }
        }

        private fun isSameDay(a: Calendar, b: Calendar): Boolean =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}
