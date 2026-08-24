package ir.factory.entryexit.ui.fragments

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ir.factory.entryexit.R
import ir.factory.entryexit.util.AnimUtils
import ir.factory.entryexit.data.InspectionEntity
import ir.factory.entryexit.data.MachineryCategory
import ir.factory.entryexit.data.PersonEntity
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.databinding.FragmentInspectionListBinding
import ir.factory.entryexit.databinding.ItemInspectionVehicleBinding
import ir.factory.entryexit.ui.InspectionFormActivity
import ir.factory.entryexit.viewmodel.FactoryViewModel
import java.util.Calendar

/**
 * Tab 5: "بازدید هفتگی". One card per machine, showing whether its weekly visual/exterior
 * inspection has already been done this week, tapping a card opens [InspectionFormActivity].
 * Vehicles come from the same MACHINERY roster the other tabs use; inspection history is
 * layered on top from [FactoryViewModel.allInspections].
 */
class InspectionListFragment : Fragment(R.layout.fragment_inspection_list) {

    private val viewModel: FactoryViewModel by activityViewModels()
    private var _binding: FragmentInspectionListBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VehicleAdapter
    private var rawVehicles: List<PersonEntity> = emptyList()
    private var latestInspectionByPerson: Map<String, InspectionEntity> = emptyMap()
    private var selectedCategory: MachineryCategory? = null
    private var showCompleted: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentInspectionListBinding.bind(view)

        adapter = VehicleAdapter { person ->
            // If this vehicle already has a this-week record, tapping it opens that record for
            // correction instead of starting a brand new inspection on top of it.
            val existing = latestInspectionByPerson[person.id]?.takeIf { isThisWeek(it.timestamp) }
            val intent = Intent(requireContext(), InspectionFormActivity::class.java)
                .putExtra(InspectionFormActivity.EXTRA_PERSON_ID, person.id)
            if (existing != null) intent.putExtra(InspectionFormActivity.EXTRA_INSPECTION_ID, existing.id)
            startActivity(intent)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        AnimUtils.runLayoutAnimation(binding.recyclerView)

        binding.chipShowCompleted.setOnCheckedChangeListener { _, checked ->
            showCompleted = checked
            applyFilter(binding.etSearch.text?.toString().orEmpty())
        }

        // Same fix as the Machinery tab's filter row: without this, dragging across these
        // chips gets stolen by the enclosing ViewPager2 as a tab swipe.
        binding.scrollInspectionFilter.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val chipToCategory = mapOf(
            binding.chipInspectionMixer.id to MachineryCategory.MIXER,
            binding.chipInspectionDumpTruck.id to MachineryCategory.DUMP_TRUCK,
            binding.chipInspectionPump.id to MachineryCategory.CONCRETE_PUMP,
            binding.chipInspectionLogistics.id to MachineryCategory.LOGISTICS
        )
        binding.chipGroupCategoryFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedCategory = chipToCategory[checkedIds.firstOrNull()]
            applyFilter(binding.etSearch.text?.toString().orEmpty())
        }

        viewModel.personsByType(PersonType.MACHINERY).observe(viewLifecycleOwner) { vehicles ->
            rawVehicles = vehicles
            applyFilter(binding.etSearch.text?.toString().orEmpty())
        }

        viewModel.allInspections().observe(viewLifecycleOwner) { inspections ->
            // Keep only the newest record per vehicle — small dataset, cheap to reduce client-side.
            latestInspectionByPerson = inspections
                .groupBy { it.personId }
                .mapValues { (_, list) -> list.maxByOrNull { it.timestamp }!! }
            applyFilter(binding.etSearch.text?.toString().orEmpty())
        }
    }

    private fun applyFilter(query: String) {
        var filtered = if (query.isBlank()) {
            rawVehicles
        } else {
            rawVehicles.filter {
                it.name.contains(query, ignoreCase = true) || it.group?.contains(query, ignoreCase = true) == true
            }
        }
        selectedCategory?.let { category ->
            filtered = filtered.filter { MachineryCategory.classify(it.group) == category }
        }

        val beforeWeekFilter = filtered.size
        // Default: once a vehicle's weekly inspection is submitted, it drops off this list so a
        // guard can't accidentally log a second one for the same week. Toggling "ثبت‌شده‌های این
        // هفته" flips that around, showing exactly the vehicles WITH a this-week record instead —
        // tapping one opens it in correction mode (see the adapter's onClick above) for a guard
        // who needs to fix a mistaken entry.
        filtered = filtered.filter { vehicle ->
            val record = latestInspectionByPerson[vehicle.id]
            val doneThisWeek = record != null && isThisWeek(record.timestamp)
            if (showCompleted) doneThisWeek else !doneThisWeek
        }

        adapter.submit(filtered, latestInspectionByPerson, showCompleted)

        binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = when {
            filtered.isNotEmpty() -> ""
            showCompleted -> getString(R.string.inspection_empty_list)
            beforeWeekFilter > 0 -> getString(R.string.inspection_all_done_this_week)
            else -> getString(R.string.empty_list_roster)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** True if [timestamp] falls within the same calendar week (Saturday-start, matching the
     *  Iranian work week the original Excel checklist follows) as right now. */
    private fun isThisWeek(timestamp: Long): Boolean {
        val cal = Calendar.getInstance().apply { firstDayOfWeek = Calendar.SATURDAY }
        cal.timeInMillis = System.currentTimeMillis()
        val weekOfYearNow = cal.get(Calendar.WEEK_OF_YEAR)
        val yearNow = cal.get(Calendar.YEAR)
        cal.timeInMillis = timestamp
        return cal.get(Calendar.WEEK_OF_YEAR) == weekOfYearNow && cal.get(Calendar.YEAR) == yearNow
    }

    private inner class VehicleAdapter(
        private val onClick: (PersonEntity) -> Unit
    ) : RecyclerView.Adapter<VehicleAdapter.ViewHolder>() {

        private var items: List<PersonEntity> = emptyList()
        private var latest: Map<String, InspectionEntity> = emptyMap()
        private var editMode: Boolean = false

        fun submit(newItems: List<PersonEntity>, newLatest: Map<String, InspectionEntity>, editMode: Boolean) {
            items = newItems
            latest = newLatest
            this.editMode = editMode
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemInspectionVehicleBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemInspectionVehicleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val person = items[position]
            holder.b.tvPlate.text = "${person.group.orEmpty()} — پلاک ${person.name}"
            holder.b.tvDriver.text = person.extraInfo?.let { "راننده: $it" } ?: ""
            holder.b.tvDriver.visibility = if (person.extraInfo.isNullOrBlank()) View.GONE else View.VISIBLE

            val record = latest[person.id]
            when {
                record != null && isThisWeek(record.timestamp) -> {
                    holder.b.tvInspectionStatus.setBackgroundResource(R.drawable.bg_status_inside)
                    holder.b.tvInspectionStatus.setTextColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.status_green)
                    )
                    val base = "${getString(R.string.inspection_done_this_week)} — " +
                        getString(R.string.inspection_last_result_format, record.approvedCount.toString(), record.rejectedCount.toString())
                    holder.b.tvInspectionStatus.text = if (editMode) "$base — ${getString(R.string.inspection_edit_hint)}" else base
                }
                record != null -> {
                    holder.b.tvInspectionStatus.setBackgroundResource(R.drawable.bg_status_outside)
                    holder.b.tvInspectionStatus.setTextColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.danger_red)
                    )
                    holder.b.tvInspectionStatus.text = "${getString(R.string.inspection_not_done_this_week)} — " +
                        getString(R.string.inspection_last_date_format, ir.factory.entryexit.util.JalaliCalendar.formatDate(record.timestamp))
                }
                else -> {
                    holder.b.tvInspectionStatus.setBackgroundResource(R.drawable.bg_status_outside)
                    holder.b.tvInspectionStatus.setTextColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.danger_red)
                    )
                    holder.b.tvInspectionStatus.text = getString(R.string.inspection_never_done)
                }
            }

            holder.b.root.setOnClickListener { onClick(person) }
        }

        override fun getItemCount(): Int = items.size
    }
}
