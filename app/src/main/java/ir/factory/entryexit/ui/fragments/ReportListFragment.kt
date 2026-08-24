package ir.factory.entryexit.ui.fragments

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ir.factory.entryexit.R
import ir.factory.entryexit.data.ReportEntity
import ir.factory.entryexit.data.ReportType
import ir.factory.entryexit.databinding.FragmentReportListBinding
import ir.factory.entryexit.databinding.ItemReportBinding
import ir.factory.entryexit.ui.ReportFormActivity
import ir.factory.entryexit.util.AnimUtils
import ir.factory.entryexit.viewmodel.FactoryViewModel

/**
 * Tab 7: "گزارشات حراست" — violation / incident / positive / general write-ups filed from
 * dropdown-driven templates (see [ir.factory.entryexit.data.ReportCatalog]). The FAB opens
 * [ReportFormActivity] to file a new one of any type; tapping an existing card reopens it in
 * correction mode, same "fix a mistaken entry" idea as the weekly inspection tab.
 */
class ReportListFragment : Fragment(R.layout.fragment_report_list) {

    private val viewModel: FactoryViewModel by activityViewModels()
    private var _binding: FragmentReportListBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ReportAdapter
    private var rawReports: List<ReportEntity> = emptyList()
    private var selectedType: ReportType? = null

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentReportListBinding.bind(view)

        adapter = ReportAdapter { report ->
            startActivity(
                Intent(requireContext(), ReportFormActivity::class.java)
                    .putExtra(ReportFormActivity.EXTRA_REPORT_ID, report.id)
            )
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        AnimUtils.runLayoutAnimation(binding.recyclerView)

        binding.chipGroupReportFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedType = when (checkedIds.firstOrNull()) {
                binding.chipReportViolation.id -> ReportType.VIOLATION
                binding.chipReportIncident.id -> ReportType.INCIDENT
                binding.chipReportPositive.id -> ReportType.POSITIVE
                binding.chipReportGeneral.id -> ReportType.GENERAL
                else -> null
            }
            applyFilter()
        }

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(requireContext(), ReportFormActivity::class.java))
        }

        viewModel.allReports().observe(viewLifecycleOwner) { reports ->
            rawReports = reports
            applyFilter()
        }
    }

    private fun applyFilter() {
        val type = selectedType
        val filtered = if (type == null) rawReports else rawReports.filter { it.type == type.name }
        adapter.submit(filtered)
        binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class ReportAdapter(
    private val onClick: (ReportEntity) -> Unit
) : RecyclerView.Adapter<ReportAdapter.VH>() {

    private var items: List<ReportEntity> = emptyList()

    fun submit(newItems: List<ReportEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(val b: ItemReportBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemReportBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val report = items[position]
        val type = runCatching { ReportType.valueOf(report.type) }.getOrNull()
        val context = holder.itemView.context

        holder.b.tvTypeBadge.text = type?.label ?: report.type
        holder.b.tvCategory.text = report.category
        holder.b.tvTimestamp.text = ir.factory.entryexit.util.JalaliCalendar.formatDateTime(report.timestamp)
        holder.b.tvSummary.text = report.summaryText

        when (type) {
            ir.factory.entryexit.data.ReportType.VIOLATION, ir.factory.entryexit.data.ReportType.INCIDENT -> {
                holder.b.tvTypeBadge.setBackgroundResource(R.drawable.bg_status_outside)
                holder.b.tvTypeBadge.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.danger_red))
            }
            ir.factory.entryexit.data.ReportType.POSITIVE -> {
                holder.b.tvTypeBadge.setBackgroundResource(R.drawable.bg_status_inside)
                holder.b.tvTypeBadge.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.status_green))
            }
            else -> {
                holder.b.tvTypeBadge.setBackgroundResource(R.drawable.bg_status_outside)
                holder.b.tvTypeBadge.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.concrete_500))
            }
        }

        if (!report.performedByName.isNullOrBlank()) {
            holder.b.tvReporter.visibility = View.VISIBLE
            holder.b.tvReporter.text = context.getString(R.string.report_reporter_format, report.performedByName)
        } else {
            holder.b.tvReporter.visibility = View.GONE
        }

        holder.b.tvCorrectedBadge.visibility = if (report.correctedAt != null) View.VISIBLE else View.GONE
        holder.b.tvCorrectedBadge.text = context.getString(R.string.report_corrected_badge)

        holder.b.root.setOnClickListener { onClick(report) }
    }
}
