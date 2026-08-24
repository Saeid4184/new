package ir.factory.entryexit.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.factory.entryexit.R
import ir.factory.entryexit.data.LogEntity
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.data.Session
import ir.factory.entryexit.databinding.ActivityMyLogsBinding
import ir.factory.entryexit.util.AnimUtils
import ir.factory.entryexit.viewmodel.FactoryViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * "اصلاح رویدادهای ثبت‌شده" — lets a guard see everything THEY personally logged (or, for an
 * admin, everyone's) and correct the timestamp on any of them. For the case where a guard logs
 * something a few minutes after it actually happened: log it now as usual, then tap it here and
 * set the real time. Every correction keeps the original value visible (see
 * [ir.factory.entryexit.data.LogEntity.originalTimestamp]) rather than silently overwriting it.
 */
class MyLogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyLogsBinding
    private lateinit var viewModel: FactoryViewModel
    private lateinit var adapter: MyLogsAdapter
    private var rawList: List<LogEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[FactoryViewModel::class.java]

        binding.toolbar.title = getString(if (Session.isAdmin()) R.string.my_logs_title_admin else R.string.my_logs_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = MyLogsAdapter(onEdit = { log -> showEditDialog(log) })
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilter(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) {}
        })

        viewModel.myLogs().observe(this) { list ->
            rawList = list
            applyFilter(binding.etSearch.text?.toString().orEmpty())
            AnimUtils.runLayoutAnimation(binding.recyclerView)
        }
    }

    private fun applyFilter(query: String) {
        val filtered = if (query.isBlank()) rawList else rawList.filter { it.personName.contains(query, ignoreCase = true) }
        adapter.submit(filtered)
        binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Built programmatically (date button / time button / save-cancel) rather than a dedicated
     *  XML dialog layout — the whole dialog is just two pickers, so a layout file would be more
     *  code than this for no real benefit. */
    private fun showEditDialog(log: LogEntity) {
        val cal = Calendar.getInstance().apply { timeInMillis = log.timestamp }
        val dateFmt = SimpleDateFormat("yyyy/MM/dd", Locale.US)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
        val density = resources.displayMetrics.density

        val btnDate = TextView(this)
        val btnTime = TextView(this)
        val personLabel = TextView(this).apply {
            text = getString(
                R.string.edit_timestamp_person_format,
                log.personName,
                runCatching { PersonType.valueOf(log.type).displayName }.getOrDefault(log.type)
            )
            textSize = 14f
            setPadding(0, 0, 0, (16 * density).toInt())
        }

        fun styleButton(tv: TextView, label: String) {
            tv.text = label
            tv.textSize = 15f
            tv.gravity = android.view.Gravity.CENTER
            tv.setPadding(0, (14 * density).toInt(), 0, (14 * density).toInt())
            tv.setTextColor(getColor(R.color.concrete_900))
            tv.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_card_outline)
            tv.isClickable = true
            tv.isFocusable = true
        }
        styleButton(btnDate, dateFmt.format(cal.time))
        styleButton(btnTime, timeFmt.format(cal.time))

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(personLabel)
            addView(btnDate)
            addView(View(context), android.widget.LinearLayout.LayoutParams(1, (10 * density).toInt()))
            addView(btnTime)
        }

        btnDate.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    cal.set(Calendar.YEAR, year); cal.set(Calendar.MONTH, month); cal.set(Calendar.DAY_OF_MONTH, day)
                    btnDate.text = dateFmt.format(cal.time)
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        btnTime.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hour, minute ->
                    cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                    btnTime.text = timeFmt.format(cal.time)
                },
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true
            ).show()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_timestamp_title)
            .setView(container)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                if (cal.timeInMillis > System.currentTimeMillis()) {
                    Toast.makeText(this, R.string.edit_timestamp_future_error, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.editLogTimestamp(log.id, cal.timeInMillis) { result ->
                    result.onSuccess {
                        Toast.makeText(this, R.string.edit_timestamp_saved, Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this, it.message ?: getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton(R.string.btn_delete_event) { _, _ -> showDeleteConfirmDialog(log) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** A delete is irreversible (unlike a timestamp edit, which keeps the original value), so
     *  this asks for a second, explicit confirmation before actually removing the event —
     *  guarding against an accidental tap on "حذف رویداد" for a record that was actually fine. */
    private fun showDeleteConfirmDialog(log: LogEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_event_confirm_title)
            .setMessage(R.string.delete_event_confirm_message)
            .setPositiveButton(R.string.btn_delete_event) { _, _ ->
                viewModel.deleteLog(log.id) { result ->
                    result.onSuccess {
                        Toast.makeText(this, R.string.delete_event_success, Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this, it.message ?: getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
