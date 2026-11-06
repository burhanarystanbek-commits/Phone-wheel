package com.phonewheel.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Step knob (TC / ABS / MAP / brake bias ...). Games bind these settings to
 * "+" and "−" buttons, so the knob does not send an axis: every detent
 * (or tap on the + / − half) emits a short press of a virtual button
 * "<id>_plus" / "<id>_minus". The PC auto-maps those ids to vJoy buttons,
 * exactly like ordinary custom buttons.
 */
data class KnobLayout(
    val id: String,
    var xFrac: Float,
    var yFrac: Float,
    var sizeFrac: Float,
    var label: String,
    var level: Int = 0,
)

class KnobLayoutStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("phonewheel_knobs_v2", Context.MODE_PRIVATE)

    fun save(layouts: List<KnobLayout>, nextId: Int) {
        val arr = JSONArray()
        for (k in layouts) arr.put(
            JSONObject().put("id", k.id).put("x", k.xFrac.toDouble()).put("y", k.yFrac.toDouble())
                .put("size", k.sizeFrac.toDouble()).put("label", k.label).put("level", k.level)
        )
        prefs.edit { putString("layout", arr.toString()); putInt("nextId", nextId) }
    }

    fun load(): Pair<List<KnobLayout>, Int> {
        val raw = prefs.getString("layout", null) ?: return emptyList<KnobLayout>() to 1
        return try {
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                KnobLayout(
                    o.getString("id"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                    o.optDouble("size", 0.14).toFloat(), o.optString("label", "KNOB"), o.optInt("level", 0)
                )
            }
            list to prefs.getInt("nextId", list.size + 1)
        } catch (_: Exception) {
            emptyList<KnobLayout>() to 1
        }
    }
}

class StepKnobView(
    context: Context,
    val knobId: String,
    var labelText: String,
    initialLevel: Int,
    private val onChanged: () -> Unit,
    private val onLongPressRemove: (StepKnobView) -> Unit,
    private val onTapRename: (StepKnobView) -> Unit,
) : View(context) {

    var editMode = false
        set(v) { field = v; invalidate() }

    /** Counter shown on the knob (purely visual — the game keeps the real value). */
    var level = initialLevel.coerceAtLeast(0)
        private set

    val minusId get() = "${knobId}_minus"
    val plusId get() = "${knobId}_plus"

    @Volatile var minusPressed = false
        private set
    @Volatile var plusPressed = false
        private set

    private var pendingSteps = 0          // >0 → plus pulses queued, <0 → minus
    private var pulsing = false
    private val h = Handler(Looper.getMainLooper())

    private var angle = 0f                // visual rotation, degrees
    private var lastTouchAngle = 0f
    private var accumulated = 0f
    private var totalTurn = 0f
    private var downX = 0f
    private var downY = 0f

    private var dragging = false
    private var resizing = false
    private var moved = false
    private var lastRawX = 0f
    private var lastRawY = 0f
    private val longPress = Runnable {
        if (editMode && !resizing && !moved) { moved = true; onLongPressRemove(this) }
    }

    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    val xFrac get() = left.toFloat() / ((parent as? ViewGroup)?.width?.toFloat() ?: 1f)
    val yFrac get() = top.toFloat() / ((parent as? ViewGroup)?.height?.toFloat() ?: 1f)
    val sizeFrac get() = width.toFloat() / ((parent as? ViewGroup)?.width?.toFloat() ?: 1f)

    fun toLayout() = KnobLayout(knobId, xFrac, yFrac, sizeFrac, labelText, level)

    private fun step(dir: Int) {
        // TC / ABS / MAP / BB are never negative: the counter stops at 0.
        // The "−" press is still sent so the game's own value also bottoms out.
        level = (level + dir).coerceAtLeast(0)
        pendingSteps += dir
        invalidate()
        onChanged()
        if (!pulsing) nextPulse()
    }

    /** Presses the virtual button for 70 ms, releases for 50 ms, repeats while queued. */
    private fun nextPulse() {
        if (pendingSteps == 0) { pulsing = false; return }
        pulsing = true
        val plus = pendingSteps > 0
        pendingSteps += if (plus) -1 else 1
        if (plus) plusPressed = true else minusPressed = true
        invalidate()
        h.postDelayed({
            plusPressed = false; minusPressed = false; invalidate()
            h.postDelayed({ nextPulse() }, 50)
        }, 70)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - dp(4f)

        pFill.color = Color.parseColor("#161b2c")
        canvas.drawCircle(cx, cy, r, pFill)
        // highlight the half that is being pulsed
        if (minusPressed || plusPressed) {
            pFill.color = Color.parseColor("#5b4fe8")
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(oval, if (plusPressed) -90f else 90f, 180f, true, pFill)
        }
        pRing.strokeWidth = dp(3f)
        pRing.color = if (editMode) Color.parseColor("#7c6fff") else Color.parseColor("#2a3150")
        canvas.drawCircle(cx, cy, r, pRing)

        // detent ticks rotating with the knob
        pTick.color = Color.parseColor("#6b7394")
        pTick.strokeWidth = dp(2f)
        for (i in 0 until 12) {
            val a = (angle + i * 30f - 90f) * PI.toFloat() / 180f
            canvas.drawLine(cx + cos(a) * r * 0.80f, cy + sin(a) * r * 0.80f,
                cx + cos(a) * r * 0.92f, cy + sin(a) * r * 0.92f, pTick)
        }
        val a0 = (angle - 90f) * PI.toFloat() / 180f
        pTick.color = Color.WHITE
        pTick.strokeWidth = dp(4f)
        canvas.drawLine(cx + cos(a0) * r * 0.55f, cy + sin(a0) * r * 0.55f,
            cx + cos(a0) * r * 0.90f, cy + sin(a0) * r * 0.90f, pTick)

        pText.color = Color.parseColor("#6b7394")
        pText.textSize = r * 0.40f
        canvas.drawText("−", cx - r * 0.55f, cy + r * 0.14f, pText)
        canvas.drawText("+", cx + r * 0.55f, cy + r * 0.14f, pText)
        pText.color = Color.parseColor("#c0c8e8")
        pText.textSize = r * 0.24f
        canvas.drawText(labelText, cx, cy - r * 0.05f, pText)
        pText.color = Color.WHITE
        pText.textSize = r * 0.28f
        canvas.drawText(level.toString(), cx, cy + r * 0.32f, pText)

        if (editMode) {
            pFill.color = Color.parseColor("#7c6fff")
            canvas.drawCircle(width - dp(12f), height - dp(12f), dp(9f), pFill)
        }
    }

    private fun touchAngle(e: MotionEvent) =
        Math.toDegrees(atan2((e.y - height / 2f).toDouble(), (e.x - width / 2f).toDouble())).toFloat()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = if (editMode) editTouch(e) else playTouch(e)

    private fun playTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchAngle = touchAngle(e); accumulated = 0f; totalTurn = 0f
                downX = e.x; downY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val a = touchAngle(e)
                var d = a - lastTouchAngle
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                lastTouchAngle = a
                // ignore jitter near the center where angle is unstable
                if (hypot(e.x - width / 2f, e.y - height / 2f) < width * 0.12f) return true
                accumulated += d; totalTurn += abs(d)
                angle += d
                while (accumulated >= DETENT) { accumulated -= DETENT; step(+1) }
                while (accumulated <= -DETENT) { accumulated += DETENT; step(-1) }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                // a tap (no real rotation) → step by the side that was tapped
                if (totalTurn < 8f && hypot(e.x - downX, e.y - downY) < dp(14f)) {
                    val dir = if (e.x >= width / 2f) 1 else -1
                    angle += dir * DETENT
                    step(dir)
                }
            }
        }
        return true
    }

    private fun editTouch(e: MotionEvent): Boolean {
        val pv = parent as? ViewGroup ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizing = e.x > width - dp(28f) && e.y > height - dp(28f)
                dragging = !resizing; moved = false
                lastRawX = e.rawX; lastRawY = e.rawY
                h.postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - lastRawX
                val dy = e.rawY - lastRawY
                if (abs(dx) > 8 || abs(dy) > 8) { moved = true; h.removeCallbacks(longPress) }
                val lp = layoutParams as ViewGroup.MarginLayoutParams
                if (dragging) {
                    lp.leftMargin = (lp.leftMargin + dx).toInt().coerceIn(0, (pv.width - width).coerceAtLeast(0))
                    lp.topMargin = (lp.topMargin + dy).toInt().coerceIn(0, (pv.height - height).coerceAtLeast(0))
                } else {
                    val s = (width + maxOf(dx, dy)).toInt()
                        .coerceIn(dp(64f).toInt(), minOf(pv.width - lp.leftMargin, pv.height - lp.topMargin))
                    lp.width = s; lp.height = s
                }
                layoutParams = lp
                lastRawX = e.rawX; lastRawY = e.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                h.removeCallbacks(longPress)
                if (moved) onChanged() else if (e.actionMasked == MotionEvent.ACTION_UP) onTapRename(this)
                dragging = false; resizing = false
            }
        }
        return true
    }

    companion object { const val DETENT = 30f }
}
