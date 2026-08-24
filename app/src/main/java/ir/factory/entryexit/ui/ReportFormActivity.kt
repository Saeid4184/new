package ir.factory.entryexit.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import ir.factory.entryexit.R
import ir.factory.entryexit.data.ReportCatalog
import ir.factory.entryexit.data.ReportEntity
import ir.factory.entryexit.data.ReportType
import ir.factory.entryexit.databinding.ActivityReportFormBinding
import ir.factory.entryexit.util.NetworkMonitor
import ir.factory.entryexit.util.AnimUtils
import ir.factory.entryexit.viewmodel.FactoryViewModel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * File a new security report or correct an existing one (see [EXTRA_REPORT_ID]). Every dropdown
 * (نوع/دسته/شدت/اقدام) is backed by [ReportCatalog]; picking [ReportCatalog.OTHER] reveals a
 * free-text field so an unusual case is never blocked. The bottom preview is the exact sentence
 * that gets saved as [ReportEntity.summaryText] (see [ReportCatalog.buildSummary]).
 */
class ReportFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportFormBinding
    private lateinit var viewModel: FactoryViewModel

    private var editingReportId: String? = null
    private var existingPhotoUri: String? = null
    private var pendingPhotoUri: String? = null

    private var selectedType: ReportType = ReportType.VIOLATION
    private var selectedCategory: String = ""
    private var selectedSeverity: String? = null
    private var selectedAction: String? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pendingPhotoUri = uri.toString()
            binding.tvPhotoAttached.visibility = android.view.View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[FactoryViewModel::class.java]
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupTypeDropdown()
        setupSeverityDropdown()
        setupActionDropdown()

        binding.etCategory.setOnItemClickListener { _, _, position, _ ->
            val options = ReportCatalog.categoryOptionsFor(selectedType)
            val picked = options[position]
            binding.tilCategoryOther.visibility = if (picked == ReportCatalog.OTHER) android.view.View.VISIBLE else android.view.View.GONE
            selectedCategory = if (picked == ReportCatalog.OTHER) binding.etCategoryOther.text?.toString().orEmpty() else picked
            updatePreview()
        }
        binding.etCategoryOther.doAfterTextChangedCompat {
            if (binding.tilCategoryOther.visibility == android.view.View.VISIBLE) {
                selectedCategory = it
                updatePreview()
            }
        }
        binding.etSubjectName.doAfterTextChangedCompat { updatePreview() }
        binding.etLocation.doAfterTextChangedCompat { updatePreview() }
        binding.etDescription.doAfterTextChangedCompat { updatePreview() }
        binding.etActionOther.doAfterTextChangedCompat {
            if (binding.tilActionOther.visibility == android.view.View.VISIBLE) {
                selectedAction = it
                updatePreview()
            }
        }

        binding.btnAttachPhoto.setOnClickListener { pickPhoto.launch(arrayOf("image/*")) }
        binding.btnSubmit.setOnClickListener { submit() }
        AnimUtils.applyPressFeedback(binding.btnSubmit)

        editingReportId = intent.getStringExtra(EXTRA_REPORT_ID)
        val editingId = editingReportId
        if (editingId != null) {
            binding.toolbar.title = getString(R.string.report_correction_title)
            binding.btnSubmit.text = getString(R.string.report_correction_submit)
            viewModel.reportById(editingId) { existing -> existing?.let { fillForCorrection(it) } }
        } else {
            applyTypeSelection(ReportType.VIOLATION, keepCategory = false)
        }

        observeConnectivity()
    }

    private fun fillForCorrection(report: ReportEntity) {
        val type = runCatching { ReportType.valueOf(report.type) }.getOrDefault(ReportType.GENERAL)
        selectedType = type
        binding.etType.setText(type.label, false)
        binding.tilType.isEnabled = false // report type itself isn't editable in correction mode
        applyFieldVisibility(type)

        val options = ReportCatalog.categoryOptionsFor(type)
        binding.etCategory.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, options))
        if (options.contains(report.category)) {
            binding.etCategory.setText(report.category, false)
            selectedCategory = report.category
        } else {
            binding.etCategory.setText(ReportCatalog.OTHER, false)
            binding.tilCategoryOther.visibility = android.view.View.VISIBLE
            binding.etCategoryOther.setText(report.category)
            selectedCategory = report.category
        }

        selectedSeverity = report.severity
        report.severity?.let { binding.etSeverity.setText(it, false) }

        binding.etSubjectName.setText(report.subjectName.orEmpty())
        binding.etLocation.setText(report.location.orEmpty())

        if (type == ReportType.VIOLATION) {
            val actionOptions = ReportCatalog.violationActionOptions
            if (actionOptions.contains(report.actionTaken)) {
                binding.etActionTaken.setText(report.actionTaken, false)
                selectedAction = report.actionTaken
            } else if (!report.actionTaken.isNullOrBlank()) {
                binding.etActionTaken.setText(ReportCatalog.OTHER, false)
                binding.tilActionOther.visibility = android.view.View.VISIBLE
                binding.etActionOther.setText(report.actionTaken)
                selectedAction = report.actionTaken
            }
        }

        binding.etDescription.setText(report.description)
        existingPhotoUri = report.photoUri
        if (!report.photoUri.isNullOrBlank()) binding.tvPhotoAttached.visibility = android.view.View.VISIBLE

        updatePreview()
    }

    private fun setupTypeDropdown() {
        val labels = ReportType.entries.map { it.label }
        binding.etType.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        binding.etType.setOnItemClickListener { _, _, position, _ ->
            applyTypeSelection(ReportType.entries[position], keepCategory = false)
        }
    }

    private fun setupSeverityDropdown() {
        binding.etSeverity.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, ReportCatalog.severityOptions))
        binding.etSeverity.setOnItemClickListener { _, _, position, _ ->
            selectedSeverity = ReportCatalog.severityOptions[position]
            updatePreview()
        }
    }

    private fun setupActionDropdown() {
        binding.etActionTaken.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, ReportCatalog.violationActionOptions))
        binding.etActionTaken.setOnItemClickListener { _, _, position, _ ->
            val picked = ReportCatalog.violationActionOptions[position]
            binding.tilActionOther.visibility = if (picked == ReportCatalog.OTHER) android.view.View.VISIBLE else android.view.View.GONE
            selectedAction = if (picked == ReportCatalog.OTHER) binding.etActionOther.text?.toString().orEmpty() else picked
            updatePreview()
        }
    }

    private fun applyTypeSelection(type: ReportType, keepCategory: Boolean) {
        selectedType = type
        binding.etType.setText(type.label, false)
        applyFieldVisibility(type)
        val options = ReportCatalog.categoryOptionsFor(type)
        binding.etCategory.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, options))
        if (!keepCategory) {
            binding.etCategory.setText("", false)
            selectedCategory = ""
            binding.tilCategoryOther.visibility = android.view.View.GONE
        }
        updatePreview()
    }

    private fun applyFieldVisibility(type: ReportType) {
        binding.tilSeverity.visibility = if (ReportCatalog.showsSeverity(type)) android.view.View.VISIBLE else android.view.View.GONE
        binding.tilActionTaken.visibility = if (ReportCatalog.showsAction(type)) android.view.View.VISIBLE else android.view.View.GONE
        binding.tilActionOther.visibility = android.view.View.GONE
        binding.tilSubjectName.hint = getString(R.string.report_hint_subject_name)
        binding.tilSubjectName.visibility = android.view.View.VISIBLE // always optional/visible, harmless for every type
        binding.tilLocation.visibility = android.view.View.VISIBLE
    }

    private fun updatePreview() {
        val dateText = ir.factory.entryexit.util.JalaliCalendar.formatDate(System.currentTimeMillis())
        val summary = ReportCatalog.buildSummary(
            type = selectedType,
            dateText = dateText,
            category = selectedCategory,
            severity = selectedSeverity,
            subjectName = binding.etSubjectName.text?.toString(),
            location = binding.etLocation.text?.toString(),
            actionTaken = selectedAction,
            description = binding.etDescription.text?.toString().orEmpty()
        )
        binding.tvPreview.text = summary
    }

    private fun submit() {
        val category = selectedCategory.trim()
        if (category.isEmpty()) {
            Toast.makeText(this, R.string.report_error_category_required, Toast.LENGTH_SHORT).show()
            return
        }
        val description = binding.etDescription.text?.toString().orEmpty()
        if (description.isBlank()) {
            Toast.makeText(this, R.string.report_error_description_required, Toast.LENGTH_SHORT).show()
            return
        }
        val summary = binding.tvPreview.text.toString()
        val subjectName = binding.etSubjectName.text?.toString()
        val location = binding.etLocation.text?.toString()
        val photo = pendingPhotoUri ?: existingPhotoUri

        binding.btnSubmit.isEnabled = false
        val editingId = editingReportId
        if (editingId != null) {
            viewModel.correctReport(editingId, category, selectedSeverity, subjectName, location, selectedAction, description, summary, photo) { result ->
                binding.btnSubmit.isEnabled = true
                result.onSuccess {
                    Toast.makeText(this, R.string.report_correction_success, Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        viewModel.submitReport(selectedType, category, selectedSeverity, subjectName, location, selectedAction, description, summary, photo) { result ->
            binding.btnSubmit.isEnabled = true
            result.onSuccess {
                Toast.makeText(this, R.string.report_submit_success, Toast.LENGTH_SHORT).show()
                finish()
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Same offline banner as [MainActivity]/[InspectionFormActivity]. */
    private fun observeConnectivity() {
        binding.tvOfflineBanner.visibility =
            if (NetworkMonitor.isOnline(applicationContext)) android.view.View.GONE else android.view.View.VISIBLE
        lifecycleScope.launch {
            NetworkMonitor.observe(applicationContext)
                .drop(1)
                .collect { online ->
                    binding.tvOfflineBanner.visibility = if (online) android.view.View.GONE else android.view.View.VISIBLE
                }
        }
    }

    companion object {
        /** Optional — when present, this screen loads and overwrites that existing report (a
         *  correction) instead of creating a new one. */
        const val EXTRA_REPORT_ID = "extra_report_id"
    }
}

/** Small helper so text-change listeners above read as one line instead of a full TextWatcher
 *  each — mirrors the same convenience used nowhere else in this codebase but kept local here
 *  since 5 fields need it and pulling in a whole new util file for it isn't worth it. */
private fun android.widget.EditText.doAfterTextChangedCompat(action: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            action(s?.toString().orEmpty())
        }
    })
}
