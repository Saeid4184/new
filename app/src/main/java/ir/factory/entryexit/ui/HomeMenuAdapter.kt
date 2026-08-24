package ir.factory.entryexit.ui

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ir.factory.entryexit.databinding.ItemHomeMenuBinding
import ir.factory.entryexit.util.AnimUtils
import ir.factory.entryexit.util.GlassCard

/** The 7 sections reachable from the home menu. Each maps to either a [ir.factory.entryexit.data.PersonType]-backed
 *  roster or one of the three standalone logs (inspection checklist, goods entry/exit, security reports) —
 *  mirrors the set of tabs the old ViewPager used to show. */
enum class HomeDestination {
    PERSONNEL, MACHINERY, VISITOR, DRIVER, INSPECTION, ITEM_LOG, REPORT
}

data class HomeMenuItem(
    val destination: HomeDestination,
    val titleRes: Int,
    val subtitleRes: Int,
    val iconRes: Int,
    val accentColorRes: Int,
    val accentBgColorRes: Int
)

/** Simple single-column list of tappable cards — the new home screen's "menu" — replacing the
 *  old swipeable tab pager. Tapping a card opens [CategoryDetailActivity] for that section. */
class HomeMenuAdapter(
    private val items: List<HomeMenuItem>,
    private val onClick: (HomeMenuItem) -> Unit
) : RecyclerView.Adapter<HomeMenuAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHomeMenuBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], onClick)

    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemHomeMenuBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeMenuItem, onClick: (HomeMenuItem) -> Unit) {
            val context = binding.root.context
            binding.tvTitle.setText(item.titleRes)
            binding.tvSubtitle.setText(item.subtitleRes)
            binding.ivIcon.setImageResource(item.iconRes)

            val accent = ContextCompat.getColor(context, item.accentColorRes)
            val background = ContextCompat.getColor(context, item.accentBgColorRes)
            binding.ivIcon.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
            binding.iconChip.background?.mutate()?.setTint(background)
            binding.colorStripe.setBackgroundColor(accent)
            GlassCard.applyColor(binding.cardRoot, accent)

            AnimUtils.applyPressFeedback(binding.cardRoot)
            binding.cardRoot.setOnClickListener { onClick(item) }
        }
    }
}
