package ir.factory.entryexit.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.factory.entryexit.R
import ir.factory.entryexit.util.JalaliCalendar
import ir.factory.entryexit.util.toPersianDigits
import java.util.TimeZone

/**
 * A from-scratch Jalali (Shamsi) calendar range picker, built entirely from plain Android
 * views in code rather than a layout resource — this sidesteps adding any new third-party
 * calendar library (none of the common ones are on Maven Central/Google's repo, only JitPack,
 * which this project doesn't pull from) while still giving the admin an actual Shamsi month
 * grid to tap through, matching how [ir.factory.entryexit.util.JalaliCalendar] already formats
 * every date shown in the reports themselves.
 *
 * Tap one day to start a range, tap a later day to complete it (tapping before the start
 * restarts the range from that new day; tapping the start day again selects a single-day
 * range). Confirms via the dialog's positive button.
 */
object JalaliDateRangePickerDialog {

    fun show(
        context: Context,
        initialStartMillis: Long,
        initialEndMillis: Long,
        onRangeSelected: (startMillis: Long, endMillis: Long) -> Unit
    ) {
        val tz = TimeZone.getDefault()
        var rangeStart: JalaliCalendar.JalaliDate = JalaliCalendar.toJalali(initialStartMillis, tz)
        var rangeEnd: JalaliCalendar.JalaliDate? = JalaliCalendar.toJalali(initialEndMillis, tz)
        var viewYear = rangeStart.year
        var viewMonth = rangeStart.month

        val amber = ContextCompat.getColor(context, R.color.safety_amber)
        val amberBg = ContextCompat.getColor(context, R.color.accent_inspection_bg)
        val textColor = ContextCompat.getColor(context, R.color.concrete_900)
        val mutedColor = ContextCompat.getColor(context, R.color.concrete_500)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val pad = dp(context, 20)
            setPadding(pad, dp(context, 12), pad, 0)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Right-pointing glyph on the right (prev month), left-pointing on the left (next
        // month) — the RTL-conventional direction for a Persian calendar's month navigation.
        val btnPrev = TextView(context).apply {
            text = "›"
            textSize = 22f
            setTextColor(amber)
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        val btnNext = TextView(context).apply {
            text = "‹"
            textSize = 22f
            setTextColor(amber)
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        val title = TextView(context).apply {
            textSize = 17f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Row is RTL, so the first child added lands on the right: btnPrev goes right, btnNext
        // goes left, matching the RTL-conventional navigation direction set up above.
        header.addView(btnPrev)
        header.addView(title)
        header.addView(btnNext)
        root.addView(header)

        val weekdayRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        for (name in JalaliCalendar.weekdayShortNames) {
            weekdayRow.addView(TextView(context).apply {
                text = name
                gravity = Gravity.CENTER
                setTextColor(mutedColor)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, dp(context, 6), 0, dp(context, 6))
            })
        }
        root.addView(weekdayRow)

        // Deliberately left at the default (LTR) layoutDirection: unlike LinearLayout, GridLayout
        // doesn't reliably auto-mirror column order under RTL across API levels, so instead the
        // Saturday-on-the-right order is placed by hand below via columnSpec = 6 - (i % 7) —
        // setting RTL here too would silently cancel that out and flip the week back around.
        val grid = GridLayout(context).apply {
            columnCount = 7
            rowCount = 6
        }
        root.addView(grid)

        val rangeLabel = TextView(context).apply {
            textSize = 13f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 12), 0, dp(context, 4))
        }
        root.addView(rangeLabel)

        fun updateRangeLabel() {
            val endText = rangeEnd?.let { "${it.year}/${it.month.toPersianDigits()}/${it.day.toPersianDigits()}" }
                ?: "—"
            val startText = "${rangeStart.year}/${rangeStart.month.toPersianDigits()}/${rangeStart.day.toPersianDigits()}"
            rangeLabel.text = context.getString(R.string.jalali_picker_range_format, startText, endText)
        }

        // A decimal sortable key (YYYYMMDD-shaped) — month/day each safely fit under 100, so
        // there's no risk of a late-month day spilling into the next year's range like a
        // base-31/base-372 key would.
        fun dateKey(d: JalaliCalendar.JalaliDate) = d.year * 10000 + d.month * 100 + d.day

        fun inRange(d: JalaliCalendar.JalaliDate): Boolean {
            val end = rangeEnd ?: return d == rangeStart
            val dKey = dateKey(d)
            val sKey = dateKey(rangeStart)
            val eKey = dateKey(end)
            val lo = minOf(sKey, eKey)
            val hi = maxOf(sKey, eKey)
            return dKey in lo..hi
        }

        fun onDayTapped(d: JalaliCalendar.JalaliDate) {
            val hasCompleteRange = rangeEnd != null
            if (hasCompleteRange) {
                // Start a brand-new selection.
                rangeStart = d
                rangeEnd = null
            } else {
                val startKey = dateKey(rangeStart)
                val dKey = dateKey(d)
                if (dKey < startKey) {
                    rangeEnd = rangeStart
                    rangeStart = d
                } else {
                    rangeEnd = d
                }
            }
        }

        val dayCells = mutableListOf<TextView>()

        lateinit var renderMonth: () -> Unit
        lateinit var refreshCells: () -> Unit

        refreshCells = {
            for (cell in dayCells) {
                val d = cell.tag as? JalaliCalendar.JalaliDate
                if (d == null) {
                    cell.visibility = View.INVISIBLE
                } else {
                    cell.visibility = View.VISIBLE
                    val selected = inRange(d)
                    cell.setBackgroundColor(if (selected) amberBg else android.graphics.Color.TRANSPARENT)
                    cell.setTextColor(if (selected) amber else textColor)
                    cell.typeface = if (d == rangeStart || d == rangeEnd) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
            }
            updateRangeLabel()
        }

        renderMonth = {
            title.text = "${JalaliCalendar.monthNames[viewMonth - 1]} ${viewYear.toPersianDigits()}"
            grid.removeAllViews()
            dayCells.clear()

            val firstWeekday = JalaliCalendar.weekdayIndex(viewYear, viewMonth, 1, tz)
            val daysInMonth = JalaliCalendar.daysInJalaliMonth(viewYear, viewMonth)
            val cellSize = dp(context, 40)

            for (i in 0 until 42) {
                val dayNumber = i - firstWeekday + 1
                val cell = TextView(context).apply {
                    gravity = Gravity.CENTER
                    textSize = 14f
                    val lp = GridLayout.LayoutParams().apply {
                        width = cellSize
                        height = cellSize
                        columnSpec = GridLayout.spec(6 - (i % 7)) // RTL: rightmost column first
                        rowSpec = GridLayout.spec(i / 7)
                    }
                    layoutParams = lp
                }
                if (dayNumber in 1..daysInMonth) {
                    val d = JalaliCalendar.JalaliDate(viewYear, viewMonth, dayNumber)
                    cell.text = dayNumber.toPersianDigits()
                    cell.tag = d
                    cell.setOnClickListener {
                        onDayTapped(d)
                        refreshCells()
                    }
                } else {
                    cell.tag = null
                    cell.visibility = View.INVISIBLE
                }
                dayCells.add(cell)
                grid.addView(cell)
            }
            refreshCells()
        }

        btnPrev.setOnClickListener {
            viewMonth--
            if (viewMonth < 1) {
                viewMonth = 12
                viewYear--
            }
            renderMonth()
        }
        btnNext.setOnClickListener {
            viewMonth++
            if (viewMonth > 12) {
                viewMonth = 1
                viewYear++
            }
            renderMonth()
        }

        renderMonth()

        // A real ScrollView, not a bare wrapper — on a short screen (small phone, or the
        // keyboard/nav-bar eating vertical space) the 6-row grid plus header can exceed the
        // available dialog height, and without this the bottom rows/buttons would just clip.
        val scrollWrapper = ScrollView(context).apply { addView(root) }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.report_title)
            .setView(scrollWrapper)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val end = rangeEnd ?: rangeStart
                val startMillis = JalaliCalendar.jalaliToMillis(rangeStart.year, rangeStart.month, rangeStart.day, tz)
                val endMillis = JalaliCalendar.jalaliToMillis(end.year, end.month, end.day, tz)
                if (startMillis <= endMillis) {
                    onRangeSelected(startMillis, endMillis)
                } else {
                    onRangeSelected(endMillis, startMillis)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()

        val white = ContextCompat.getColor(context, R.color.white)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(white)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(white)
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()
}
