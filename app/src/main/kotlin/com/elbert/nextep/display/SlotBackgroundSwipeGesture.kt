package com.elbert.nextep.display

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.workspace.SidebarSide
import kotlin.math.abs

/** Uses slot-local axes so an outward or inward swipe also follows the rotated rail. */
internal class SlotBackgroundSwipeGesture(
    private val view: TaskSwitcherView,
    private val currentTask: () -> SlotTaskDrag?,
    private val onStarted: (SlotTaskDrag) -> Boolean,
    private val onDismiss: (SlotTaskDrag) -> Boolean,
    private val onHibernateToggle: (SlotTaskDrag) -> Boolean,
    private val onEnded: () -> Unit,
) {
    private enum class SwipeMode {
        NONE,
        OUTWARD,
        INWARD,
    }

    internal enum class HorizontalSwipeOutcome {
        NONE,
        OUTWARD_DISMISS,
        INWARD_HIBERNATE,
    }

    private val configuration = ViewConfiguration.get(view.context)
    private val touchSlop = configuration.scaledTouchSlop.toFloat()
    private val minimumFlingVelocity = maxOf(
        configuration.scaledMinimumFlingVelocity.toFloat(),
        600f * view.resources.displayMetrics.density,
    )
    private val inwardNudgeMaxPx = 24f * view.resources.displayMetrics.density
    private var side = SidebarSide.RIGHT
    private var enabled = true
    private var downX = 0f
    private var downY = 0f
    private var task: SlotTaskDrag? = null
    private var tracking = false
    private var consumed = false
    private var mode = SwipeMode.NONE
    private var velocityTracker: VelocityTracker? = null

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        cancel()
    }

    fun setSide(side: SidebarSide) {
        cancel()
        this.side = side
    }

    fun touch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            tracking = true
            consumed = false
            downX = event.x
            downY = event.y
            task = currentTask()
            velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            return false // Keep the existing click and long-press handling.
        }
        if (!tracking) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_CANCEL -> {
                val handled = consumed
                cancel()
                tracking = false
                return handled
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelViewTouch(event)
                cancel()
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY
                val direction = directionSign(side)
                val outwardDistance = dx * direction
                val inwardDistance = -outwardDistance
                val horizontal = abs(dx) > abs(dy) * 1.25f
                if (!consumed && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    // Any moved gesture must not fall through to a slot exchange,
                    // including an unfinished outward or inward swipe.
                    consumed = true
                    cancelViewTouch(event)
                    val candidate = task
                    // Still consume moved outward touches when disabled so a swipe
                    // cannot accidentally become a slot click. Clicks stay intact.
                    if (candidate != null && candidate == currentTask() && horizontal) {
                        if (enabled && outwardDistance > touchSlop && onStarted(candidate)) {
                            mode = SwipeMode.OUTWARD
                            view.resetSwipeAnimations()
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        } else if (inwardDistance > touchSlop) {
                            mode = SwipeMode.INWARD
                            view.resetSwipeAnimations()
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                }
                when (mode) {
                    SwipeMode.OUTWARD -> {
                        val offset = outwardDistance.coerceIn(0f, view.width.toFloat())
                        view.setSwipeTransform(
                            translationX = offset * direction,
                            alpha = 1f - 0.6f * offset / view.width.coerceAtLeast(1),
                        )
                    }
                    SwipeMode.INWARD -> {
                        val offset = (inwardDistance * 0.35f).coerceIn(0f, inwardNudgeMaxPx)
                        view.setSwipeTransform(
                            translationX = -offset * direction,
                            alpha = 1f,
                        )
                    }
                    SwipeMode.NONE -> Unit
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val handled = consumed
                    val candidate = task
                    velocityTracker?.computeCurrentVelocity(
                        1000, configuration.scaledMaximumFlingVelocity.toFloat(),
                    )
                    val velocityX = velocityTracker?.xVelocity ?: 0f
                    val outcome = if (candidate != null) {
                        classifyRelease(
                            side = side,
                            outwardEnabled = mode == SwipeMode.OUTWARD,
                            inwardEnabled = mode == SwipeMode.INWARD,
                            dx = dx,
                            dy = dy,
                            velocityX = velocityX,
                            viewWidth = view.width,
                            touchSlop = touchSlop,
                            minimumFlingVelocity = minimumFlingVelocity,
                        )
                    } else {
                        HorizontalSwipeOutcome.NONE
                    }
                    try {
                        when (outcome) {
                            HorizontalSwipeOutcome.OUTWARD_DISMISS -> {
                                val dismissed = dismiss(checkNotNull(candidate))
                                NeXtepLog.info("slot_swipe", "slot=${candidate.slotIndex} background=$dismissed")
                            }
                            HorizontalSwipeOutcome.INWARD_HIBERNATE -> {
                                val activeCandidate = checkNotNull(candidate)
                                view.setSwipeTransform(0f, 1f)
                                val toggled = onHibernateToggle(activeCandidate)
                                NeXtepLog.info(
                                    "slot_swipe",
                                    "slot=${activeCandidate.slotIndex} hibernateToggle=$toggled",
                                )
                            }
                            HorizontalSwipeOutcome.NONE -> Unit
                        }
                    } catch (error: Throwable) {
                        NeXtepLog.warn("slot_swipe", "Could not complete slot swipe", error)
                    } finally {
                        // An untouched UP still belongs to View's click handler;
                        // cleanup must leave its pressed state intact.
                        tracking = false
                        cancel()
                    }
                    return handled
                }
            }
        }
        return consumed
    }

    private fun dismiss(candidate: SlotTaskDrag): Boolean {
        // setBusy/showState can cancel this gesture inside onDismiss. Finish
        // owning its transform first, so that cancellation cannot reveal the
        // old buffer at full opacity while the native task leaves the display.
        // Keep the coordinator's drag ownership until the move returns.
        mode = SwipeMode.NONE
        view.resetSwipeAnimations()
        view.setSwipeTransform(0f, 0f)
        view.parent?.requestDisallowInterceptTouchEvent(false)
        var dismissed = false
        try {
            dismissed = onDismiss(candidate)
            return dismissed
        } finally {
            if (!dismissed && candidate == currentTask()) {
                // A rejected/rolled-back move must remain usable. A replacement
                // task or a waiting view owns its own presentation instead.
                view.resetSwipeAnimations()
                view.setSwipeTransform(0f, 1f)
            }
            onEnded()
        }
    }

    /** Drain moved gestures after a task, layout, or lifecycle change. */
    fun cancel() {
        val previousMode = mode
        mode = SwipeMode.NONE
        task = null
        velocityTracker?.recycle()
        velocityTracker = null
        // View posts performClick after UP. An earlier click may finish its
        // exchange after the next DOWN; do not cancel that untouched next tap.
        if (tracking && (consumed || previousMode != SwipeMode.NONE)) {
            consumed = true
            view.isPressed = false
            view.cancelLongPress()
        }
        if (previousMode != SwipeMode.NONE) {
            view.setSwipeTransform(0f, 1f)
            view.parent?.requestDisallowInterceptTouchEvent(false)
            if (previousMode == SwipeMode.OUTWARD) onEnded()
        }
    }

    private fun cancelViewTouch(event: MotionEvent) {
        val cancelEvent = MotionEvent.obtain(event)
        try {
            cancelEvent.action = MotionEvent.ACTION_CANCEL
            view.onTouchEvent(cancelEvent)
        } finally {
            cancelEvent.recycle()
        }
    }

    companion object {
        internal fun directionSign(side: SidebarSide): Float =
            if (side == SidebarSide.RIGHT) 1f else -1f

        internal fun classifyRelease(
            side: SidebarSide,
            outwardEnabled: Boolean,
            inwardEnabled: Boolean,
            dx: Float,
            dy: Float,
            velocityX: Float,
            viewWidth: Int,
            touchSlop: Float,
            minimumFlingVelocity: Float,
        ): HorizontalSwipeOutcome {
            if (abs(dx) <= abs(dy) * 1.25f) return HorizontalSwipeOutcome.NONE
            val direction = directionSign(side)
            val outwardDistance = dx * direction
            val inwardDistance = -outwardDistance
            val outwardVelocity = velocityX * direction
            val inwardVelocity = -outwardVelocity
            val distanceThreshold = maxOf(viewWidth * 0.35f, touchSlop * 2f)
            val flingDistanceThreshold = maxOf(viewWidth * 0.15f, touchSlop * 2f)
            if (outwardEnabled && (outwardDistance >= distanceThreshold ||
                    (outwardDistance >= flingDistanceThreshold && outwardVelocity >= minimumFlingVelocity))
            ) {
                return HorizontalSwipeOutcome.OUTWARD_DISMISS
            }
            if (inwardEnabled && (inwardDistance >= distanceThreshold ||
                    (inwardDistance >= flingDistanceThreshold && inwardVelocity >= minimumFlingVelocity))
            ) {
                return HorizontalSwipeOutcome.INWARD_HIBERNATE
            }
            return HorizontalSwipeOutcome.NONE
        }
    }
}
