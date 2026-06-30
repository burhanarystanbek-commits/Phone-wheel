package com.phonewheel.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A free-form rotary knob the user created on the phone — for continuous
 * adjustments that don't fit a button (ABS bias, traction control level,
 * brake balance, etc). Mirrors ButtonLayout's design: a stable id
 * ("axis_1", "axis_2", ...) generated once and never reused, sent to the PC
 * as part of the "axes" map in each state frame. The PC side maps id -> one
 * of vJoy's extra continuous axes (Rx/Ry/Rz/Slider/Dial), independent of
 * where the knob sits on screen or what it's called there.
 */
data class KnobLayout(
    val id: String,
    var xFrac: Float,
    var yFrac: Float,
    var sizeFrac: Float, // knobs are square (diameter), unlike buttons which can be rectangular
    var label: String,
    var value: Float = 0.5f // 0..1, persisted so a knob keeps its position across app restarts
)

/** Persists the user's current free-form knob layout — same pattern as LayoutStore. */
class KnobLayoutStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("phonewheel_knob_layout", Context.MODE_PRIVATE)

    fun save(layouts: List<KnobLayout>, nextId: Int) {
        val arr = JSONArray()
        for (k in layouts) {
            arr.put(
                JSONObject()
                    .put("id", k.id)
                    .put("x", k.xFrac.toDouble())
                    .put("y", k.yFrac.toDouble())
                    .put("size", k.sizeFrac.toDouble())
                    .put("label", k.label)
                    .put("value", k.value.toDouble())
            )
        }
        prefs.edit {
            putString("layout", arr.toString())
            putInt("nextId", nextId)
        }
    }

    fun load(): Pair<List<KnobLayout>, Int>? {
        val raw = prefs.getString("layout", null) ?: return null
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<KnobLayout>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    KnobLayout(
                        o.getString("id"),
                        o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                        o.optDouble("size", 0.12).toFloat(),
                        o.optString("label", o.getString("id")),
                        o.optDouble("value", 0.5).toFloat()
                    )
                )
            }
            val nextId = prefs.getInt("nextId", list.size + 1)
            if (list.isEmpty()) null else list to nextId
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * A single on-screen rotary knob. In play mode, dragging a finger in a
 * circular motion around the knob's center rotates it and updates [value]
 * (0..1) — like turning a real ABS/TC dial on a steering wheel. In edit
 * mode it can be dragged to reposition, resized via drag from a corner
 * handle, renamed via tap, and removed via long-press — mirroring
 * CustomButtonView's edit interactions so the editing experience is
 * consistent between buttons and knobs.
 */
class RotaryKnobView(
    context: Context,
    var axisId: String,
    var labelText: String,
    initialValue: Float,
    private val onValueChanged: () -> Unit,
    private val onMoved: () -> Unit,
    private val onLongPressRemove: (RotaryKnobView) -> Unit,
    private val onTapRename: (RotaryKnobView) -> Unit
) : View(context) {

    var editMode: Boolean = false
        set(v) { field = v; invalidate() }

    /** Current knob position, 0..1. */
    var value: Float = initialValue.coerceIn(0f, 1f)
        private set

    val xFrac get() = (left.toFloat()) / ((parent as? ViewGroup)?.width?.toFloat() ?: 1f)
    val yFrac get() = (top.toFloat()) / ((parent as? ViewGroup)?.height?.toFloat() ?: 1f)
    val sizeFrac get() = width.toFloat() / ((parent as? ViewGroup)?.width?.toFloat() ?: 1f)

    private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val paintFillArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val paintKnob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintIndicator = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#c0c8e8")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7c6fff") }

    // Knob rotates from -135° to +135° (270° total sweep) like a real dial —
    // leaves a visible gap at the bottom so the current position is always
    // unambiguous even at the extremes.
    private val sweepStartDeg = -135f
    private val sweepDeg = 270f

    private var dragging = false
    private var resizing = false
    private var moved = false
    private var lastRawX = 0f
    private var lastRawY = 0f
    private val handlePx get() = dp(18f)

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (editMode && !dragging && !resizing) onLongPressRemove(this)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(w, h) / 2f) - dp(6f)

        paintRing.strokeWidth = dp(5f)
        paintRing.color = Color.parseColor("#1e2536")
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
            sweepStartDeg, sweepDeg, false, paintRing)

        paintFillArc.strokeWidth = dp(5f)
        paintFillArc.color = if (editMode) Color.parseColor("#7c6fff") else Color.parseColor("#22dc82")
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
            sweepStartDeg, sweepDeg * value, false, paintFillArc)

        paintKnob.color = Color.parseColor("#161b2c")
        canvas.drawCircle(cx, cy, radius - dp(10f), paintKnob)

        val angleDeg = sweepStartDeg + sweepDeg * value
        val angleRad = angleDeg * PI.toFloat() / 180f
        val indR = radius - dp(14f)
        val ix = cx + indR * cos(angleRad)
        val iy = cy + indR * sin(angleRad)
        paintIndicator.strokeWidth = dp(3f)
        canvas.drawLine(cx, cy, ix, iy, paintIndicator)
        canvas.drawCircle(cx, cy, dp(3f), paintIndicator)

        paintValue.textSize = h * 0.16f
        canvas.drawText("${(value * 100).toInt()}", cx, cy + paintValue.textSize * 0.34f, paintValue)

        paintText.textSize = h * 0.11f
        canvas.drawText(labelText, cx, h - dp(4f), paintText)

        if (editMode) {
            canvas.drawCircle(w - handlePx / 2f - dp(2f), h - handlePx / 2f - dp(2f), handlePx / 2f, paintHandle)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editMode) return handlePlayTouch(event)
        return handleEditTouch(event)
    }

    /** Play mode: dragging anywhere on the knob rotates it, tracking the
     *  finger's angle relative to the knob's center. */
    private fun handlePlayTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                updateValueFromTouch(event.x, event.y)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateValueFromTouch(touchX: Float, touchY: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val dx = touchX - cx
        val dy = touchY - cy
        var angleDeg = atan2(dy, dx) * 180f / PI.toFloat()

        var rel = angleDeg - sweepStartDeg
        if (rel < 0) rel += 360f
        val clampedRel = rel.coerceIn(0f, sweepDeg)
        val newValue = if (rel > sweepDeg) {
            if (rel - sweepDeg < 360f - rel) 1f else 0f
        } else {
            (clampedRel / sweepDeg).coerceIn(0f, 1f)
        }

        if (newValue != value) {
            value = newValue
            invalidate()
            onValueChanged()
        }
    }

    private fun handleEditTouch(event: MotionEvent): Boolean {
        val parentView = parent as? ViewGroup ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inHandle = event.x > width - handlePx * 1.6f && event.y > height - handlePx * 1.6f
                resizing = inHandle
                dragging = !inHandle
                moved = false
                lastRawX = event.rawX
                lastRawY = event.rawY
                longPressHandler.postDelayed(longPressRunnable, 550)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                if (abs(dx) > 10 || abs(dy) > 10) {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    moved = true
                }
                val lp = layoutParams as ViewGroup.MarginLayoutParams
                val pw = parentView.width.toFloat()
                val ph = parentView.height.toFloat()
                if (dragging) {
                    lp.leftMargin = (lp.leftMargin + dx).toInt().coerceIn(0, (pw - width).toInt().coerceAtLeast(0))
                    lp.topMargin  = (lp.topMargin + dy).toInt().coerceIn(0, (ph - height).toInt().coerceAtLeast(0))
                    layoutParams = lp
                } else if (resizing) {
                    val maxSize = minOf(pw - lp.leftMargin, ph - lp.topMargin)
                    val newSize = (width + dx).coerceIn(dp(60f), maxSize)
                    lp.width = newSize.toInt()
                    lp.height = newSize.toInt()
                    layoutParams = lp
                }
                lastRawX = event.rawX
                lastRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (dragging || resizing) {
                    if (moved) onMoved() else onTapRename(this)
                }
                dragging = false
                resizing = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun toLayout() = KnobLayout(axisId, xFrac, yFrac, sizeFrac, labelText, value)
}
