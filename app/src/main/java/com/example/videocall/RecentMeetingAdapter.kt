package com.example.videocall

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.videocall.data.RecentMeeting
import com.example.videocall.databinding.ItemRecentMeetingBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class RecentMeetingAdapter : RecyclerView.Adapter<RecentMeetingAdapter.ViewHolder>() {

    private var meetings: List<RecentMeeting> = emptyList()

    fun submitList(items: List<RecentMeeting>) {
        meetings = items
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
        holder.bind(meetings[position])
    }

    override fun getItemCount(): Int = meetings.size

    class ViewHolder(private val binding: ItemRecentMeetingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(meeting: RecentMeeting) {
            val context = binding.root.context
            binding.meetingChip.text = relativeDayLabel(context, meeting.startedAt)
            binding.meetingTitle.text = meeting.title
            binding.meetingId.text = context.getString(R.string.meeting_id_format, meeting.code)
            binding.meetingParticipants.text =
                context.getString(R.string.meeting_participants, meeting.participantCount)
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
