package ir.factory.entryexit.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ir.factory.entryexit.R
import ir.factory.entryexit.data.PersonEntity
import ir.factory.entryexit.databinding.ItemRosterEntryBinding
import ir.factory.entryexit.util.AnimUtils
import ir.factory.entryexit.util.CategoryIconColors
import ir.factory.entryexit.util.buildPlateSpannable
import ir.factory.entryexit.data.PersonType

/**
 * Flat (ungrouped) machinery roster for the parking guard's screen. Deliberately a separate,
 * small adapter rather than reusing [GroupedPersonAdapter] — that one reads/writes
 * [PersonEntity.isInside] (the factory-level status) directly in its bind logic, and mixing the
 * two statuses in one class risked exactly the bug this feature exists to avoid. This one reads
 * [PersonEntity.insideParking] instead; everything else (icon, photo, press feedback) mirrors it
 * for a consistent look.
 */
class ParkingMachineryAdapter(
    private val onCheckIn: (PersonEntity) -> Unit,
    private val onCheckOut: (PersonEntity) -> Unit
) : RecyclerView.Adapter<ParkingMachineryAdapter.VH>() {

    private var items: List<PersonEntity> = emptyList()

    fun submit(list: List<PersonEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRosterEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], onCheckIn, onCheckOut)
    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemRosterEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(person: PersonEntity, onCheckIn: (PersonEntity) -> Unit, onCheckOut: (PersonEntity) -> Unit) {
            val context = binding.root.context
            binding.tvName.text = buildPlateSpannable(context, person.name)

            if (person.imageUri != null) {
                binding.ivTypeIcon.visibility = View.GONE
                binding.ivPhoto.visibility = View.VISIBLE
                Glide.with(context)
                    .load(android.net.Uri.parse(person.imageUri))
                    .placeholder(R.drawable.ic_machinery)
                    .error(R.drawable.ic_machinery)
                    .circleCrop()
                    .into(binding.ivPhoto)
            } else {
                binding.ivPhoto.visibility = View.GONE
                binding.ivTypeIcon.visibility = View.VISIBLE
                binding.ivTypeIcon.setImageResource(R.drawable.ic_machinery)
            }
            CategoryIconColors.apply(binding.ivTypeIcon, PersonType.MACHINERY)
            CategoryIconColors.applyCard(binding.root, PersonType.MACHINERY)

            val subtitleParts = mutableListOf<String>()
            person.extraInfo?.takeIf { it.isNotBlank() }?.let { subtitleParts += it }
            subtitleParts += context.getString(
                R.string.last_status_format,
                if (person.insideParking) context.getString(R.string.parking_status_inside)
                else context.getString(R.string.parking_status_outside)
            )
            binding.tvSubtitle.text = subtitleParts.joinToString(" · ")

            if (person.isBlacklisted) {
                binding.btnCheckIn.isEnabled = false
                binding.btnCheckIn.alpha = 0.35f
                binding.btnCheckOut.isEnabled = false
                binding.btnCheckOut.alpha = 0.35f
            } else if (person.insideParking) {
                binding.btnCheckOut.isEnabled = true
                binding.btnCheckOut.alpha = 1f
                binding.btnCheckIn.isEnabled = false
                binding.btnCheckIn.alpha = 0.35f
            } else {
                binding.btnCheckIn.isEnabled = true
                binding.btnCheckIn.alpha = 1f
                binding.btnCheckOut.isEnabled = false
                binding.btnCheckOut.alpha = 0.35f
            }
            binding.btnCheckIn.setOnClickListener { onCheckIn(person) }
            binding.btnCheckOut.setOnClickListener { onCheckOut(person) }

            AnimUtils.applyPressFeedback(binding.root)
            AnimUtils.applyPressFeedback(binding.btnCheckIn)
            AnimUtils.applyPressFeedback(binding.btnCheckOut)
        }
    }
}
