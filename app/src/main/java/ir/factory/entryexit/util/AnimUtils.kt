package ir.factory.entryexit.util

import android.animation.ObjectAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView
import ir.factory.entryexit.R

/**
 * Small, dependency-free animation helpers shared across activities/fragments. Kept intentionally
 * lightweight (no Lottie/MotionLayout) so every screen gets a bit of visual life — staggered list
 * entrances, a tactile press response on cards/buttons, and a pop-in for FABs — without adding
 * new build dependencies.
 */
object AnimUtils {

    /** Plays a staggered fall-down-and-fade-in animation for the RecyclerView's items the next
     *  time it lays out children (initial population or a full data-set swap). Safe to call
     *  once, right after the adapter is attached. */
    fun runLayoutAnimation(recyclerView: RecyclerView) {
        val controller = AnimationUtils.loadLayoutAnimation(recyclerView.context, R.anim.layout_animation_fall_down)
        recyclerView.layoutAnimation = controller
        recyclerView.scheduleLayoutAnimation()
    }

    /** Pops a view (typically a FAB) in with a slight overshoot, as if it just landed. */
    fun popIn(view: View, startDelay: Long = 120L) {
        view.scaleX = 0f
        view.scaleY = 0f
        view.alpha = 0f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setStartDelay(startDelay)
            .setDuration(420)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()
    }

    /** Spins a view (the factory logo) end-over-end around its vertical axis forever, like a
     *  slowly tumbling coin. Uses rotationY rather than plain rotation so it reads as a 3D flip
     *  instead of a flat pinwheel spin; the boosted cameraDistance keeps the perspective
     *  foreshortening gentle instead of warping the logo as it turns edge-on. Safe to call
     *  multiple times (e.g. from onCreate after a config change) — it just restarts the spin. */
    fun startCoinSpin(view: View, periodMs: Long = 2600L) {
        view.cameraDistance = 24000f * view.resources.displayMetrics.density
        view.clearAnimation()
        ObjectAnimator.ofFloat(view, View.ROTATION_Y, 0f, 360f).apply {
            duration = periodMs
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** Adds a subtle "press down" scale response to any clickable view (cards, action buttons,
     *  chips) on top of whatever click listener it already has — this only observes touch
     *  events and always returns false, so it never steals the click. */
    fun applyPressFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(160).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                }
            }
            false
        }
    }
}
