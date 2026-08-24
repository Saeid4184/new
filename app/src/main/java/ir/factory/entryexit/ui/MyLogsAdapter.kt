package ir.factory.entryexit.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ir.factory.entryexit.R
import ir.factory.entryexit.data.Checkpoint
import ir.factory.entryexit.data.LogEntity
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.databinding.ItemMyLogEntryBinding
import ir.factory.entryexit.util.AnimUtils
import ir.factory.entryexit.util.CategoryIconColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MyLogsAdapter(
    private val onEdit: (LogEntity) -> Unit
) : RecyclerView.Adapter<MyLogsAdapter.VH>() {

    private var items: List<LogEntity> = emptyList()
    private val fmt = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US)

    fun submit(list: List<LogEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMyLogEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], fmt, onEdit)
    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemMyLogEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(log: LogEntity, fmt: SimpleDateFormat, onEdit: (LogEntity) -> Unit) {
            val context = binding.root.context
            val isIn = log.action == "IN"

            binding.tvActionBadge.text = context.getString(if (isIn) R.string.action_in_label else R.string.action_out_label)
            binding.tvActionBadge.setBackgroundResource(if (isIn) R.drawable.bg_status_inside else R.drawable.bg_status_outside)
            binding.tvActionBadge.setTextColor(
                androidx.core.content.ContextCompat.getColor(context, if (isIn) R.color.status_green else R.color.danger_red)
            )
            binding.tvName.text = log.personName

            val type = runCatching { PersonType.valueOf(log.type) }.getOrNull()
            CategoryIconColors.applyCard(binding.root, type ?: PersonType.PERSONNEL)
            val categoryLabel = type?.displayName ?: log.type
            val checkpointLabel = Checkpoint.fromStringOrNull(log.checkpoint)?.displayName
            binding.tvSubtitle.text = listOfNotNull(categoryLabel, checkpointLabel, log.detail).joinToString(" · ")

            binding.tvTimestamp.text = fmt.format(Date(log.timestamp))

            if (log.editedAt != null && log.originalTimestamp != null) {
                binding.tvEditedBadge.visibility = View.VISIBLE
                binding.tvEditedBadge.text = context.getString(
                    R.string.my_logs_edited_badge_format,
                    fmt.format(Date(log.originalTimestamp))
                )
            } else {
                binding.tvEditedBadge.visibility = View.GONE
            }

            AnimUtils.applyPressFeedback(binding.root)
            binding.root.setOnClickListener { onEdit(log) }
        }
    }
}
