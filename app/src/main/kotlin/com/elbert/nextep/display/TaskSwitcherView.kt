package com.elbert.nextep.display

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.elbert.nextep.R
import com.elbert.nextep.config.AppLanguage
import com.elbert.nextep.logging.NeXtepLog
import com.elbert.nextep.workspace.SidebarSide

class TaskSwitcherView(
    context: Context,
    private val slotIndex: Int,
) : FrameLayout(context) {
    interface Listener {
        fun onSlotClicked(slotIndex: Int)
        fun onIntentDropped(slotIndex: Int, intent: Intent): Boolean
        fun onSlotDragStarting(drag: SlotTaskDrag): Boolean
        fun onSlotDragStarted(source: TaskSwitcherView, drag: SlotTaskDrag): Boolean
        fun onSlotDragTouch(source: View, event: MotionEvent): Boolean
        fun onSlotBackgroundSwiped(drag: SlotTaskDrag): Boolean
        fun onSlotHibernateToggled(drag: SlotTaskDrag): Boolean = false
        fun onSlotHibernateBadgeClicked(slotIndex: Int) {}
        fun onSlotDragEnded()
        fun onSlotDropped(slotIndex: Int, drag: SlotTaskDrag): Boolean
        fun onContentDragEvent(slotIndex: Int, event: DragEvent): Boolean = false
    }

    var textureView: TextureView = createTextureView()
        private set

    private val hibernatePreviewView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.MATRIX
    }
    private val hibernateScrimView = View(context).apply {
        setBackgroundColor(Color.argb(72, 10, 14, 20))
    }
    private val hibernateBadge = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 11f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(4), dp(9), dp(4))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(212, 22, 28, 36))
            setStroke(dp(1).coerceAtLeast(1), Color.argb(115, 255, 255, 255))
        }
    }
    private val hibernateOverlay = FrameLayout(context).apply {
        visibility = GONE
        isClickable = false
        isFocusable = false
        addView(hibernatePreviewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hibernateScrimView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            hibernateBadge,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(8)
                marginStart = dp(6)
                marginEnd = dp(6)
            },
        )
    }

    private val statusView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        background = statusBackground()
    }
    private var listener: Listener? = null
    private var busy = false
    private var currentState: SlotState = SlotState.Empty
    private var waiting = false
    private var stateRendered = false
    private var ownsDrag = false
    private var acceptsContentDrag = false
    private var exchangePreviewActive = false
    private var hibernated = false
    private var waitingWakeFrame = false
    private var frozenPreview: Bitmap? = null
    private var downOnHibernateBadge = false
    private val backgroundSwipe = SlotBackgroundSwipeGesture(
        view = this,
        currentTask = {
            (currentState as? SlotState.Occupied)?.takeIf { !busy && !waiting && !ownsDrag }
                ?.let { SlotTaskDrag(slotIndex, it.taskId, it.displayId) }
        },
        onStarted = { listener?.onSlotDragStarting(it) == true },
        onDismiss = { listener?.onSlotBackgroundSwiped(it) == true },
        onHibernateToggle = { listener?.onSlotHibernateToggled(it) == true },
        onEnded = { listener?.onSlotDragEnded() },
    )

    init {
        clipToOutline = false
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hibernateOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            statusView,
            LayoutParams(dp(30), dp(30), Gravity.CENTER),
        )
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateHibernatePreviewMatrix() }
        showState(SlotState.Empty)
        setOnDragListener { _, event -> handleDrag(event) }
        setOnClickListener {
            if (hibernated && downOnHibernateBadge && currentState is SlotState.Occupied) {
                downOnHibernateBadge = false
                hibernateBadge.alpha = 1f
                listener?.onSlotHibernateBadgeClicked(slotIndex)
                return@setOnClickListener
            }
            // The coordinator serializes exchanges and retains the latest selection.
            // Filtering busy taps here prevents that queue from ever receiving them.
            if (currentState is SlotState.Ready || currentState is SlotState.Occupied ||
                currentState is SlotState.Failed) {
                listener?.onSlotClicked(slotIndex)
            }
        }
        setOnLongClickListener {
            downOnHibernateBadge = false
            hibernateBadge.alpha = 1f
            val occupied = currentState as? SlotState.Occupied
                ?: return@setOnLongClickListener false
            if (busy || waiting) return@setOnLongClickListener false
            val drag = SlotTaskDrag(slotIndex, occupied.taskId, occupied.displayId)
            if (listener?.onSlotDragStarting(drag) != true) return@setOnLongClickListener false
            ownsDrag = true
            val started = listener?.onSlotDragStarted(this, drag) == true
            if (!started) finishDrag()
            else {
                setContentAlpha(0.55f)
            }
            started
        }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downOnHibernateBadge = isPointInsideHibernateBadge(event.x, event.y)
                    hibernateBadge.alpha = if (downOnHibernateBadge) 0.72f else 1f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hibernateBadge.alpha = 1f
                }
            }
            if (listener?.onSlotDragTouch(view, event) == true) {
                downOnHibernateBadge = false
                hibernateBadge.alpha = 1f
                backgroundSwipe.cancel()
                true
            } else backgroundSwipe.touch(event)
        }
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun finishInternalDrag() = finishDrag()
    fun setBackgroundSwipeEnabled(enabled: Boolean) = backgroundSwipe.setEnabled(enabled)
    fun setBackgroundSwipeSide(side: SidebarSide) = backgroundSwipe.setSide(side)
    fun cancelBackgroundSwipe() = backgroundSwipe.cancel()
    fun acceptsAppDrop() = !busy && currentState is SlotState.Ready
    fun setDropHighlighted(highlighted: Boolean) {
        foreground = if (highlighted) GradientDrawable().apply {
            setColor(Color.argb(35, 62, 190, 198))
            setStroke(dp(2), Color.rgb(80, 216, 224))
        } else null
    }

    fun showState(state: SlotState) {
        if (stateRendered && state == currentState && !waiting) return
        stateRendered = true
        if (state != currentState || waiting) backgroundSwipe.cancel()
        waiting = false
        val previousState = currentState
        currentState = state
        when (state) {
            SlotState.Empty -> showReady(animated = !exchangePreviewActive && previousState is SlotState.Occupied)
            is SlotState.Ready -> showReady(animated = !exchangePreviewActive && previousState is SlotState.Occupied)
            is SlotState.Occupied -> {
                showOccupied(
                    animated = !exchangePreviewActive &&
                        (previousState !is SlotState.Occupied || previousState.taskId != state.taskId),
                )
            }
            is SlotState.Failed -> showReady(animated = !exchangePreviewActive && previousState is SlotState.Occupied)
        }
    }

    internal fun beginExchangePreview() {
        exchangePreviewActive = true
        textureView.animate().cancel()
    }

    internal fun endExchangePreview() {
        if (!exchangePreviewActive) return
        exchangePreviewActive = false
        if (waiting) return
        // The overlay already performed the entrance. Reveal the live buffer at
        // its final size, including when a drag was cancelled before the move.
        stateRendered = false
        showState(currentState)
    }

    fun showWaiting() {
        backgroundSwipe.cancel()
        clearHibernateCoverImmediate()
        waiting = true
        textureView.animate().cancel()
        textureView.animate()
            .alpha(0f)
            .scaleX(CONTENT_EXIT_SCALE)
            .scaleY(CONTENT_EXIT_SCALE)
            .setDuration(TRANSITION_OUT_DURATION_MS)
            .setInterpolator(transitionInterpolator)
            .start()
        statusView.animate().cancel()
        setStatusVisible(currentState !is SlotState.Occupied)
    }

    fun setBusy(isBusy: Boolean) {
        busy = isBusy
        if (isBusy) backgroundSwipe.cancel()
        if (!isBusy) {
            showState(currentState)
        }
    }

    fun replaceTextureView(listener: TextureView.SurfaceTextureListener): TextureView {
        backgroundSwipe.cancel()
        clearHibernateCoverImmediate()
        waiting = true
        val previous = textureView
        previous.animate().cancel()
        previous.surfaceTextureListener = null
        removeView(previous)

        return createTextureView().also { replacement ->
            replacement.surfaceTextureListener = listener
            textureView = replacement
            addView(
                replacement,
                0,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    internal fun resetSwipeAnimations() {
        textureView.animate().cancel()
        textureView.scaleX = 1f
        textureView.scaleY = 1f
        hibernateOverlay.animate().cancel()
        hibernateOverlay.scaleX = 1f
        hibernateOverlay.scaleY = 1f
    }

    internal fun setSwipeTransform(translationX: Float, alpha: Float) {
        textureView.translationX = translationX
        textureView.alpha = alpha
        if (hibernateOverlay.visibility == VISIBLE) {
            hibernateOverlay.translationX = translationX
            hibernateOverlay.alpha = alpha
        }
    }

    internal fun captureFrozenCover(sourceWidth: Int, sourceHeight: Int): Bitmap? {
        if (!textureView.isAvailable || width <= 0 || height <= 0) return null
        val targetWidth = computeFrozenCoverWidth(width, sourceWidth)
        val targetHeight = if (sourceWidth > 0 && sourceHeight > 0) {
            (targetWidth * sourceHeight.toLong() / sourceWidth).toInt().coerceAtLeast(1)
        } else height
        return runCatching { textureView.getBitmap(targetWidth, targetHeight) }.getOrNull()
    }

    internal fun showHibernated(preview: Bitmap?) {
        hibernated = true
        waitingWakeFrame = false
        backgroundSwipe.cancel()
        val previous = frozenPreview
        frozenPreview = preview
        hibernatePreviewView.setImageBitmap(preview)
        updateHibernatePreviewMatrix()
        if (previous != null && previous !== preview && !previous.isRecycled) previous.recycle()
        updateHibernateBadgeText()
        hibernateScrimView.visibility = VISIBLE
        hibernateBadge.visibility = VISIBLE
        hibernateBadge.alpha = 1f
        hibernateOverlay.animate().cancel()
        hibernateOverlay.translationX = 0f
        hibernateOverlay.alpha = 1f
        hibernateOverlay.visibility = VISIBLE
    }

    internal fun beginWakeFromHibernate(immediate: Boolean = false) {
        if (!hibernated && !waitingWakeFrame && frozenPreview == null) return
        if (immediate || frozenPreview == null) {
            clearHibernateCoverImmediate()
            return
        }
        hibernated = false
        waitingWakeFrame = true
        downOnHibernateBadge = false
        hibernateScrimView.visibility = GONE
        hibernateBadge.visibility = GONE
        hibernateOverlay.alpha = 1f
        hibernateOverlay.visibility = VISIBLE
    }

    internal fun finishWakeFrame() {
        if (!waitingWakeFrame) return
        clearHibernateCoverImmediate()
    }

    internal fun clearHibernateCoverImmediate() {
        hibernated = false
        waitingWakeFrame = false
        downOnHibernateBadge = false
        hibernateOverlay.animate().cancel()
        hibernateOverlay.visibility = GONE
        hibernateOverlay.translationX = 0f
        hibernateOverlay.alpha = 1f
        hibernateScrimView.visibility = GONE
        hibernateBadge.visibility = GONE
        hibernateBadge.alpha = 1f
        hibernatePreviewView.setImageDrawable(null)
        frozenPreview?.let { bitmap ->
            frozenPreview = null
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    internal fun updateHibernatePreviewTransform() {
        updateHibernatePreviewMatrix()
    }

    fun capturePreview(width: Int = textureView.width, height: Int = textureView.height): Bitmap? = runCatching {
        com.elbert.nextep.monitoring.WorkspaceEnergyDiagnostics.previewCaptures.incrementAndGet()
        // Exchanges request the producer's recorded size. The no-argument
        // TextureView bitmap is only rail-sized and blurs when promoted to main.
        val cached = frozenPreview?.takeIf { !it.isRecycled && (hibernated || waitingWakeFrame) }
        val bitmap = if (cached != null) {
            val targetW = width.coerceAtLeast(1)
            val targetH = height.coerceAtLeast(1)
            if (cached.width == targetW && cached.height == targetH) {
                cached.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                Bitmap.createScaledBitmap(cached, targetW, targetH, true)
            }
        } else {
            textureView.getBitmap(width, height) ?: return@runCatching null
        }
        // TextureView returns its logical portrait buffer. Follow the rail's
        // actual transform so reverse-landscape drag/swap previews face correctly.
        val panelRotation = (parent as? View)?.rotation ?: 0f
        if (panelRotation != 0f) {
            try {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                    android.graphics.Matrix().apply { postRotate(panelRotation) }, true)
            } finally { bitmap.recycle() }
        } else bitmap
    }.getOrNull()

    /** getGlobalVisibleRect is relative to the ViewRoot, not display 0. The
     * sidebar is a separate window whose physical offset must be included. */
    fun getScreenVisibleRect(bounds: Rect): Boolean {
        if (!getGlobalVisibleRect(bounds)) return false
        val windowOrigin = IntArray(2)
        rootView.getLocationOnScreen(windowOrigin)
        bounds.offset(windowOrigin[0], windowOrigin[1])
        return true
    }

    override fun onDetachedFromWindow() {
        acceptsContentDrag = false
        backgroundSwipe.cancel()
        finishDrag()
        clearHibernateCoverImmediate()
        super.onDetachedFromWindow()
    }

    private fun finishDrag() {
        if (!ownsDrag) return
        ownsDrag = false
        listener?.onSlotDragEnded()
        if (!busy && currentState is SlotState.Occupied) setContentAlpha(1f)
    }

    private fun setContentAlpha(alpha: Float) {
        textureView.alpha = alpha
        if (hibernateOverlay.visibility == VISIBLE) hibernateOverlay.alpha = alpha
    }

    private fun updateHibernateBadgeText() {
        hibernateBadge.text = AppLanguage.string(context, R.string.slot_hibernated_badge)
        hibernateBadge.contentDescription = AppLanguage.string(context, R.string.slot_hibernated_badge_description)
    }

    private fun isPointInsideHibernateBadge(x: Float, y: Float): Boolean {
        if (!hibernated || hibernateOverlay.visibility != VISIBLE || hibernateBadge.visibility != VISIBLE) {
            return false
        }
        val hitSlop = dp(8)
        return x >= hibernateBadge.left - hitSlop && x <= hibernateBadge.right + hitSlop &&
            y >= hibernateBadge.top - hitSlop && y <= hibernateBadge.bottom + hitSlop
    }

    private fun updateHibernatePreviewMatrix() {
        val bitmap = frozenPreview ?: return
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0 || width <= 0 || height <= 0) return
        val textureMatrix = Matrix()
        textureView.getTransform(textureMatrix)
        hibernatePreviewView.imageMatrix = Matrix().apply {
            setScale(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            postConcat(textureMatrix)
        }
    }

    private fun computeFrozenCoverWidth(viewWidth: Int, sourceWidth: Int): Int {
        val desired = maxOf(viewWidth * 2, 420)
        return if (sourceWidth > 0) minOf(sourceWidth, desired) else desired.coerceAtLeast(1)
    }

    private fun handleDrag(event: DragEvent): Boolean {
        if (event.action == DragEvent.ACTION_DRAG_STARTED) {
            acceptsContentDrag = event.localState == null &&
                !AppDragContract.accepts(event.clipDescription) &&
                listener?.onContentDragEvent(slotIndex, event) == true
            if (acceptsContentDrag) return true
        } else if (acceptsContentDrag) {
            if (event.action == DragEvent.ACTION_DRAG_ENDED) acceptsContentDrag = false
            return listener?.onContentDragEvent(slotIndex, event) == true
        }
        return when (event.action) {
        DragEvent.ACTION_DRAG_STARTED -> {
            val localDrag = event.localState as? SlotTaskDrag
            val accepted = !busy && (localDrag != null &&
                currentState.inDragTargetStates() || currentState !is SlotState.Occupied &&
                AppDragContract.accepts(event.clipDescription))
            NeXtepLog.info(
                "slot_drag",
                "slot=$slotIndex started accepted=$accepted busy=$busy state=$currentState " +
                    "mime=${event.clipDescription}",
            )
            accepted
        }
        DragEvent.ACTION_DRAG_ENTERED -> {
            if (!busy) {
                if (currentState is SlotState.Occupied) {
                    foreground = GradientDrawable().apply {
                        setColor(Color.argb(35, 62, 190, 198))
                        setStroke(dp(2), Color.rgb(80, 216, 224))
                    }
                } else showReady(highlighted = true)
            }
            true
        }
        DragEvent.ACTION_DRAG_EXITED -> {
            showState(currentState)
            foreground = null
            true
        }
        DragEvent.ACTION_DROP -> {
            val localDrag = event.localState as? SlotTaskDrag
            if (localDrag != null && width > 0 && height > 0) {
                val bounds = Rect()
                getScreenVisibleRect(bounds)
                localDrag.dropPoint = if (resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels) {
                    android.graphics.PointF(bounds.right - event.y * bounds.width() / height,
                        bounds.top + event.x * bounds.height() / width)
                } else android.graphics.PointF(bounds.left + event.x, bounds.top + event.y)
            }
            val intent = AppDragContract.readLaunchIntent(event.clipData)
            val handled = !busy && if (localDrag != null) {
                listener?.onSlotDropped(slotIndex, localDrag) == true
            } else intent != null && listener?.onIntentDropped(slotIndex, intent) == true
            NeXtepLog.info(
                "slot_drag",
                "slot=$slotIndex drop handled=$handled component=${intent?.component}",
            )
            handled
        }
        DragEvent.ACTION_DRAG_ENDED -> {
            foreground = null
            if (!busy) showState(currentState)
            if ((event.localState as? SlotTaskDrag)?.slotIndex == slotIndex) finishDrag()
            true
        }
        else -> true
        }
    }

    private fun SlotState.inDragTargetStates(): Boolean = this is SlotState.Ready || this is SlotState.Occupied

    private fun showReady(highlighted: Boolean = false, animated: Boolean = false) {
        clearHibernateCoverImmediate()
        textureView.animate().cancel()
        if (animated) {
            textureView.animate()
                .alpha(0f)
                .scaleX(CONTENT_EXIT_SCALE)
                .scaleY(CONTENT_EXIT_SCALE)
                .setDuration(TRANSITION_OUT_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        } else {
            textureView.alpha = 0f
            textureView.scaleX = 1f
            textureView.scaleY = 1f
        }
        statusView.text = "+"
        statusView.textSize = 20f
        statusView.setPadding(0, 0, 0, dp(2))
        statusView.layoutParams = LayoutParams(dp(30), dp(30), Gravity.CENTER)
        statusView.background = statusBackground(
            if (highlighted) Color.argb(210, 28, 112, 121) else Color.argb(158, 22, 40, 48),
        )
        statusView.animate().cancel()
        // Empty slots remain actionable when a buffer or animation is interrupted.
        setStatusVisible(true)
    }

    private fun showOccupied(animated: Boolean) {
        statusView.animate().cancel()
        setStatusVisible(false)
        hibernateOverlay.translationX = 0f

        textureView.animate().cancel()
        if (animated) {
            textureView.alpha = 0f
            textureView.scaleX = CONTENT_ENTER_SCALE
            textureView.scaleY = CONTENT_ENTER_SCALE
            textureView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(TRANSITION_IN_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        } else {
            textureView.alpha = 1f
            textureView.scaleX = 1f
            textureView.scaleY = 1f
        }
    }

    private fun setStatusVisible(visible: Boolean) {
        statusView.animate().cancel()
        statusView.visibility = if (visible) VISIBLE else GONE
        statusView.alpha = 1f
        statusView.scaleX = 1f
        statusView.scaleY = 1f
    }

    private fun statusBackground(color: Int = Color.argb(158, 22, 40, 48)) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun createTextureView(): TextureView = TextureView(context)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val transitionInterpolator = DecelerateInterpolator(1.6f)

    private companion object {
        const val TRANSITION_OUT_DURATION_MS = 120L
        const val TRANSITION_IN_DURATION_MS = 220L
        const val CONTENT_ENTER_SCALE = 0.96f
        const val CONTENT_EXIT_SCALE = 0.98f
    }

}
