package ir.factory.entryexit.util

private val PERSIAN_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

/** Converts a non-negative integer to a zero-padded Persian-numeral string, e.g. 3 -> "۰۳". */
fun Int.toPersianDigits(minWidth: Int = 2): String {
    val raw = this.toString().padStart(minWidth, '0')
    val sb = StringBuilder(raw.length)
    for (c in raw) {
        sb.append(if (c.isDigit()) PERSIAN_DIGITS[c - '0'] else c)
    }
    return sb.toString()
}

/** Converts any western digits inside a string to Persian-Indic digits (for display only). */
fun String.toPersianDigitsInString(): String {
    val sb = StringBuilder(this.length)
    for (c in this) {
        sb.append(if (c.isDigit()) PERSIAN_DIGITS[c - '0'] else c)
    }
    return sb.toString()
}

/** Extracts just the digits from a string, normalizing any Persian-Indic digits to plain
 *  '0'-'9' along the way (e.g. "پلاک ۶۹۷۴۴" -> "69744"). Used to match an AI-read plate number
 *  against roster names regardless of digit script or surrounding text/prefix. */
fun String.extractDigits(): String {
    val sb = StringBuilder()
    for (c in this) {
        when {
            c in '0'..'9' -> sb.append(c)
            else -> {
                val idx = PERSIAN_DIGITS.indexOf(c)
                if (idx >= 0) sb.append('0' + idx)
            }
        }
    }
    return sb.toString()
}

private val ARABIC_INDIC_DIGITS = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

/** Normalizes Persian-Indic and Arabic-Indic digits to plain '0'-'9' while leaving everything
 *  else (including the decimal point) untouched — unlike [extractDigits], this keeps the
 *  string's shape intact, so it's safe to run before parsing a decimal number typed on a
 *  Persian keyboard (e.g. "۶٫۵" or "6.5" both become parseable as "6.5"). */
fun String.normalizeDigitsForParsing(): String {
    val sb = StringBuilder(this.length)
    for (c in this) {
        val persianIdx = PERSIAN_DIGITS.indexOf(c)
        val arabicIdx = ARABIC_INDIC_DIGITS.indexOf(c)
        when {
            persianIdx >= 0 -> sb.append('0' + persianIdx)
            arabicIdx >= 0 -> sb.append('0' + arabicIdx)
            c == '٫' -> sb.append('.') // Arabic decimal separator
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
