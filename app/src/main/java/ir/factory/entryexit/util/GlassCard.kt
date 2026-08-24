package ir.factory.entryexit.util

import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView

/**
 * Gives a card a translucent "glass" tint of its own accent color instead of the flat opaque
 * dark surface every card used to share — a soft alpha-blended fill plus a stronger-alpha
 * stroke of the same hue, so each category (home menu section, roster row, machinery tile,
 * inspection/item-log/report/defect entry...) reads as its own color at a glance instead of
 * every card looking identically black.
 *
 * Used for cards bound dynamically per-row (where the accent depends on the item, e.g. person
 * type); cards whose category is fixed for the whole screen just set these same two colors
 * (accent_x_glass / accent_x) directly in their layout XML instead.
 */
object GlassCard {
    private const val FILL_ALPHA = 41   // ~16% opacity — matches the accent_x_glass resources
    private const val STROKE_ALPHA = 140 // ~55% opacity

    fun applyRes(card: MaterialCardView, @ColorRes accentRes: Int) {
        applyColor(card, ContextCompat.getColor(card.context, accentRes))
    }

    fun applyColor(card: MaterialCardView, @ColorInt accent: Int) {
        card.setCardBackgroundColor(ColorUtils.setAlphaComponent(accent, FILL_ALPHA))
        card.strokeColor = ColorUtils.setAlphaComponent(accent, STROKE_ALPHA)
    }
}
