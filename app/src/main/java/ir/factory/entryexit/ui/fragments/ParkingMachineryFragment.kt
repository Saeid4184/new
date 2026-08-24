package ir.factory.entryexit.ui.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.factory.entryexit.R
import ir.factory.entryexit.data.PersonEntity
import ir.factory.entryexit.databinding.FragmentParkingMachineryBinding
import ir.factory.entryexit.ui.ParkingMachineryAdapter
import ir.factory.entryexit.util.AnimUtils
import ir.factory.entryexit.viewmodel.FactoryViewModel

/**
 * The parking guard's "ماشین‌آلات" screen. Deliberately independent of [CategoryFragment] (which
 * the gate guard's "ماشین‌آلات" card opens instead) — see [CategoryDetailActivity.fragmentFor]
 * for the routing, and [ir.factory.entryexit.data.PersonEntity.insideParking] for why the two
 * screens can't share one in/out status.
 */
class ParkingMachineryFragment : Fragment(R.layout.fragment_parking_machinery) {

    private val viewModel: FactoryViewModel by activityViewModels()
    private var _binding: FragmentParkingMachineryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ParkingMachineryAdapter
    private var rawList: List<PersonEntity> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentParkingMachineryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ParkingMachineryAdapter(
            onCheckIn = { person ->
                viewModel.checkInParking(person.id) { result ->
                    result.onFailure { showError(it) }
                }
            },
            onCheckOut = { person -> promptCheckOutReason(person) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilter(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) {}
        })

        viewModel.machineryForParking().observe(viewLifecycleOwner) { list ->
            rawList = list
            applyFilter(binding.etSearch.text?.toString().orEmpty())
            AnimUtils.runLayoutAnimation(binding.recyclerView)
        }
    }

    private fun applyFilter(query: String) {
        val filtered = if (query.isBlank()) rawList else rawList.filter { it.name.contains(query, ignoreCase = true) }
        adapter.submit(filtered)
        binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        val insideCount = rawList.count { it.insideParking }
        binding.tvInsideCount.text = getString(R.string.parking_inside_count_format, insideCount)
    }

    /** Quick one-tap reasons cover the two cases the guard described (repair shop / photos),
     *  plus a free-text "سایر" for anything else — matches how [CategoryFragment] prompts for
     *  machinery cargo/load type on regular checkout. */
    private fun promptCheckOutReason(person: PersonEntity) {
        val reasons = arrayOf(
            getString(R.string.parking_checkout_reason_repair),
            getString(R.string.parking_checkout_reason_photo),
            getString(R.string.parking_checkout_reason_other)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.parking_checkout_reason_title)
            .setItems(reasons) { _, which ->
                if (which == reasons.lastIndex) {
                    promptOtherReason(person)
                } else {
                    doCheckOut(person, reasons[which])
                }
            }
            .show()
    }

    private fun promptOtherReason(person: PersonEntity) {
        val input = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            hint = getString(R.string.parking_checkout_reason_other_hint)
        }
        val container = android.widget.FrameLayout(requireContext()).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.parking_checkout_reason_title)
            .setView(container)
            .setPositiveButton(R.string.btn_confirm_checkout) { _, _ ->
                doCheckOut(person, input.text?.toString()?.trim().orEmpty().ifBlank { getString(R.string.parking_checkout_reason_other) })
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun doCheckOut(person: PersonEntity, reason: String) {
        viewModel.checkOutParking(person.id, reason) { result ->
            result.onFailure { showError(it) }
        }
    }

    private fun showError(error: Throwable) {
        Toast.makeText(requireContext(), error.message ?: getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
