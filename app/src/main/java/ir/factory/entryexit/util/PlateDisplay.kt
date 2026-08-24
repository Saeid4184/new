package ir.factory.entryexit.util

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat
import ir.factory.entryexit.R

/**
 * Renders a machinery plate number the way a guard reads it at the gate: the first two digits
 * in the card's normal (white) text color, and the remaining digits in green — slightly larger
 * and bold — so the distinguishing part of the plate stands out on the roster card. Falls back
 * to plain text for anything too short to meaningfully split.
 */
fun buildPlateSpannable(context: Context, plate: String): CharSequence {
    if (plate.length < 3) return plate

    val splitIndex = 2
    val spannable = SpannableString(plate)
    val whiteColor = ContextCompat.getColor(context, R.color.white)
    val greenColor = ContextCompat.getColor(context, R.color.status_green)

    spannable.setSpan(
        ForegroundColorSpan(whiteColor),
        0,
        splitIndex,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    spannable.setSpan(
        ForegroundColorSpan(greenColor),
        splitIndex,
        plate.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    spannable.setSpan(
        StyleSpan(Typeface.BOLD),
        splitIndex,
        plate.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    spannable.setSpan(
        RelativeSizeSpan(1.15f),
        splitIndex,
        plate.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )

    return spannable
}
