package ir.factory.entryexit.util

import android.content.Context
import android.graphics.PorterDuff
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import ir.factory.entryexit.R
import ir.factory.entryexit.data.PersonType

/**
 * Maps each of the four person/vehicle types to a distinct duotone pair — a muted background
 * tint for the icon's rounded-square chip, and a matching brighter accent for the glyph itself
 * — so the four tabs (پرسنل/ماشین‌آلات/مراجعین/رانندگان) stay visually distinguishable at a
 * glance in list rows, search results, and setup entries, instead of every row using the same
 * flat amber circle.
 */
object CategoryIconColors {

    private fun accentRes(type: PersonType): Int = when (type) {
        PersonType.PERSONNEL -> R.color.accent_personnel
        PersonType.MACHINERY -> R.color.accent_machinery
        PersonType.VISITOR -> R.color.accent_visitor
        PersonType.DRIVER -> R.color.accent_driver
    }

    private fun backgroundRes(type: PersonType): Int = when (type) {
        PersonType.PERSONNEL -> R.color.accent_personnel_bg
        PersonType.MACHINERY -> R.color.accent_machinery_bg
        PersonType.VISITOR -> R.color.accent_visitor_bg
        PersonType.DRIVER -> R.color.accent_driver_bg
    }

    /** [iconView] is the small glyph ImageView (e.g. ivTypeIcon); its direct parent is expected
     *  to be the chip container carrying `@drawable/bg_icon_circle` as its background — true for
     *  every layout that uses that drawable (item_roster_entry, item_person_badge,
     *  item_setup_entry, item_inspection_vehicle). */
    fun apply(iconView: ImageView, type: PersonType) {
        val context: Context = iconView.context
        val accent = ContextCompat.getColor(context, accentRes(type))
        val background = ContextCompat.getColor(context, backgroundRes(type))

        iconView.setColorFilter(accent, PorterDuff.Mode.SRC_IN)

        (iconView.parent as? View)?.background?.mutate()?.setTint(background)
    }

    /** Tints [card] — the row's own MaterialCardView container — with a translucent "glass"
     *  version of [type]'s accent color (see [GlassCard]), so each of the four person/vehicle
     *  types reads as its own color even though every row shares the same layout. */
    fun applyCard(card: MaterialCardView, type: PersonType) {
        GlassCard.applyRes(card, accentRes(type))
    }
}
