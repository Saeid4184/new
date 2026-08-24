package ir.factory.entryexit.util

import java.util.Calendar
import java.util.TimeZone

/**
 * Gregorian <-> Jalali (Shamsi/Persian) calendar conversion, plus the formatting helpers used
 * everywhere a report shows a date to a human (date-range label, exported Excel rows, the
 * custom Jalali date picker). No external date library is used here — the conversion is the
 * well-known algorithm behind the widely-used `jdf.php` (Sallar Kaboli), reimplemented in
 * Kotlin, which is accurate for the ordinary range of dates this app will ever see (today's
 * factory logs, going back/forward a handful of years). The Gregorian and Jalali directions are
 * proper inverses of each other, which [daysInJalaliMonth] leans on to work out Esfand's length
 * (29 vs 30) without needing a separate leap-year formula.
 */
object JalaliCalendar {

    data class JalaliDate(val year: Int, val month: Int, val day: Int)

    val monthNames = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    /** Iranian week order (Saturday first), matching [weekdayIndex]'s 0..6 mapping. */
    val weekdayShortNames = listOf("ش", "ی", "د", "س", "چ", "پ", "ج")

    private val gDaysInMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    fun toJalali(gy: Int, gm: Int, gd: Int): JalaliDate {
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) +
            ((gy2 + 399) / 400) + gd + gDaysInMonth[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + (days / 31)
            jd = 1 + (days % 31)
        } else {
            jm = 7 + ((days - 186) / 30)
            jd = 1 + ((days - 186) % 30)
        }
        return JalaliDate(jy, jm, jd)
    }

    /** Returns Gregorian (year, month, day) as an IntArray of size 3. */
    fun toGregorian(jy0: Int, jm: Int, jd: Int): IntArray {
        var jy = jy0 + 1595
        var days = -355668 + (365 * jy) + ((jy / 33) * 8) + (((jy % 33) + 3) / 4) + jd +
            if (jm < 7) (jm - 1) * 31 else ((jm - 7) * 30) + 186
        var gy = 400 * (days / 146097)
        days %= 146097
        if (days > 36524) {
            days--
            gy += 100 * (days / 36524)
            days %= 36524
            if (days >= 365) days++
        }
        gy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            gy += (days - 1) / 365
            days = (days - 1) % 365
        }
        var gd = days + 1
        val isLeap = (gy % 4 == 0 && gy % 100 != 0) || gy % 400 == 0
        val salA = intArrayOf(0, 31, if (isLeap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 0
        for (i in 1..12) {
            if (gd <= salA[i]) {
                gm = i
                break
            }
            gd -= salA[i]
        }
        return intArrayOf(gy, gm, gd)
    }

    fun toJalali(timeMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): JalaliDate {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = timeMillis }
        return toJalali(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    /** Midday-anchored millis for a given Jalali calendar day, safe to then clamp to
     *  start/end-of-day in the caller without landing on the wrong day across a DST shift. */
    fun jalaliToMillis(jy: Int, jm: Int, jd: Int, timeZone: TimeZone = TimeZone.getDefault()): Long {
        val (gy, gm, gd) = toGregorian(jy, jm, jd).toList()
        val cal = Calendar.getInstance(timeZone).apply {
            set(gy, gm - 1, gd, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /** 0 = شنبه (Saturday) ... 6 = جمعه (Friday), matching [weekdayShortNames]. */
    fun weekdayIndex(jy: Int, jm: Int, jd: Int, timeZone: TimeZone = TimeZone.getDefault()): Int {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = jalaliToMillis(jy, jm, jd, timeZone) }
        return cal.get(Calendar.DAY_OF_WEEK) % 7
    }

    fun daysInJalaliMonth(jy: Int, jm: Int): Int {
        if (jm <= 6) return 31
        if (jm <= 11) return 30
        // Esfand: 29 or 30 depending on leap year — checked by round-tripping day 30 through
        // the (mutually inverse) conversion functions above rather than a separate leap formula.
        val (gy, gm, gd) = toGregorian(jy, 12, 30).toList()
        val roundTrip = toJalali(gy, gm, gd)
        return if (roundTrip.month == 12 && roundTrip.day == 30) 30 else 29
    }

    /** "۱۴۰۳/۰۵/۲۹" — the standard compact form used across reports and the Excel export.
     *  Builds the raw western-digit string first and converts to Persian digits exactly once —
     *  [toPersianDigits]/[toPersianDigitsInString] must never be chained on their own output,
     *  since re-running digit conversion on an already-Persian digit corrupts it. */
    fun formatDate(timeMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val j = toJalali(timeMillis, timeZone)
        val raw = "${j.year}/${j.month.toString().padStart(2, '0')}/${j.day.toString().padStart(2, '0')}"
        return raw.toPersianDigitsInString()
    }

    /** "۱۴۰۳/۰۵/۲۹ ۱۴:۰۷" — date plus HH:mm, for logs where the time of day matters. */
    fun formatDateTime(timeMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val j = toJalali(timeMillis, timeZone)
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = timeMillis }
        val raw = "${j.year}/${j.month.toString().padStart(2, '0')}/${j.day.toString().padStart(2, '0')} " +
            "${cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')}:" +
            cal.get(Calendar.MINUTE).toString().padStart(2, '0')
        return raw.toPersianDigitsInString()
    }

    /** "۱۴۰۳/۰۵/۲۹ ۱۴:۰۷:۳۳" — date plus HH:mm:ss, for the most detailed traffic log rows. */
    fun formatDateTimeSeconds(timeMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val j = toJalali(timeMillis, timeZone)
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = timeMillis }
        val raw = "${j.year}/${j.month.toString().padStart(2, '0')}/${j.day.toString().padStart(2, '0')} " +
            "${cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')}:" +
            "${cal.get(Calendar.MINUTE).toString().padStart(2, '0')}:" +
            cal.get(Calendar.SECOND).toString().padStart(2, '0')
        return raw.toPersianDigitsInString()
    }
}
