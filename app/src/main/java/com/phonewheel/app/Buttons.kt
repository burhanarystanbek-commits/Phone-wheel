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
import kotlin.math.abs

/**
 * Fixed set of momentary actions a custom button can trigger. The string id
 * is what gets sent to the PC app over the WebSocket "buttons" map, so it
 * must never change once a preset/profile depends on it — the PC side maps
 * id -> vJoy button number, independent of where the button sits on screen.
 */
enum class ButtonAction(val id: String, val defaultLabel: String) {
    GEAR_UP("gear_up", "UP"),
    GEAR_DOWN("gear_down", "DOWN"),
    HANDBRAKE("handbrake", "HB"),
    DRS("drs", "DRS"),
    PIT_LIMITER("pit_limiter", "PIT");

    companion object {
        fun byId(id: String): ButtonAction? = values().firstOrNull { it.id == id }
    }
}

/** One button's position/size as a fraction of the overlay (0..1), so it is
 *  resolution- and orientation-independent. */
data class ButtonLayout(
    val action: ButtonAction,
    var xFrac: Float,
    var yFrac: Float,
    var wFrac: Float,
    var hFrac: Float,
    var label: String = action.defaultLabel
) {
    fun copy2() = ButtonLayout(action, xFrac, yFrac, wFrac, hFrac, label)
}

/**
 * Built-in default layouts for supported games. These only decide where
 * buttons sit on the phone screen and which of them are visible by default —
 * the actual in-game effect of each action is configured on the PC app side.
 */
object Presets {
    const val ASSETTO_CORSA = "assetto_corsa"
    const val F1 = "f1"
    const val IRACING = "iracing"
    const val CUSTOM = "custom"

    val NAMES = linkedMapOf(
        ASSETTO_CORSA to "Assetto Corsa",
        F1 to "F1",
        IRACING to "iRacing"
    )

    private fun paddle(action: ButtonAction, left: Boolean, label: String) = ButtonLayout(
        action, if (left) 0.012f else 0.888f, 0.04f, 0.10f, 0.20f, label
    )

    val ASSETTO_CORSA_LAYOUT = listOf(
        paddle(ButtonAction.GEAR_DOWN, true, "DN"),
        paddle(ButtonAction.GEAR_UP, false, "UP"),
        ButtonLayout(ButtonAction.HANDBRAKE, 0.420f, 0.78f, 0.16f, 0.18f, "HB"),
        ButtonLayout(ButtonAction.PIT_LIMITER, 0.012f, 0.78f, 0.14f, 0.18f, "PIT"),
    )

    val F1_LAYOUT = listOf(
        paddle(ButtonAction.GEAR_DOWN, true, "DN"),
        paddle(ButtonAction.GEAR_UP, false, "UP"),
        ButtonLayout(ButtonAction.DRS, 0.420f, 0.04f, 0.16f, 0.18f, "DRS"),
        ButtonLayout(ButtonAction.PIT_LIMITER, 0.012f, 0.78f, 0.14f, 0.18f, "PIT"),
    )

    val IRACING_LAYOUT = listOf(
        paddle(ButtonAction.GEAR_DOWN, true, "DN"),
        paddle(ButtonAction.GEAR_UP, false, "UP"),
        ButtonLayout(ButtonAction.HANDBRAKE, 0.420f, 0.78f, 0.16f, 0.18f, "HB"),
        ButtonLayout(ButtonAction.PIT_LIMITER, 0.012f, 0.78f, 0.14f, 0.18f, "PIT"),
    )

    fun layoutFor(key: String): List<ButtonLayout> = when (key) {
        ASSETTO_CORSA -> ASSETTO_CORSA_LAYOUT
        F1 -> F1_LAYOUT
        IRACING -> IRACING_LAYOUT
        else -> ASSETTO_CORSA_LAYOUT
    }.map { it.copy2() }
}

/** Persists the user's current free-form layout + active preset name. */
class LayoutStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("phonewheel_layout", Context.MODE_PRIVATE)

    fun save(layouts: List<ButtonLayout>, presetKey: String) {
        val arr = JSONArray()
        for (b in layouts) {
            arr.put(
                JSONObject()
                    .put("action", b.action.id)
                    .put("x", b.xFrac.toDouble())
                    .put("y", b.yFrac.toDouble())
                    .put("w", b.wFrac.toDouble())
                    .put("h", b.hFrac.toDouble())
                    .put("label", b.label)
            )
        }
        prefs.edit {
            putString("layout", arr.toString())
            putString("preset", presetKey)
        }
    }

    fun load(): Pair<List<ButtonLayout>, String>? {
        val raw = prefs.getString("layout", null) ?: return null
        val preset = prefs.getString("preset", Presets.CUSTOM) ?: Presets.CUSTOM
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ButtonLayout>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val action = ButtonAction.byId(o.getString("action")) ?: continue
                list.add(
                    ButtonLayout(
                        action,
                        o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                        o.getDouble("w").toFloat(), o.getDouble("h").toFloat(),
                        o.optString("label", action.defaultLabel)
                    )
                )
            }
            if (list.isEmpty()) null else list to preset
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * A single on-screen button. In play mode it's just a momentary press
 * surface (pressed while held). In edit mode it can be dragged anywhere by
 * its body, resized from the bottom-right handle, and removed via long-press.
 */
class CustomButtonView(
    context: Context,
    var action: ButtonAction,
    var labelText: String,
    private val onMoved: () -> Unit,
    private val onLongPressRemove: (CustomButtonView) -> Unit
) : View(context) {

    var editMode: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    var pressed: Boolean = false
        private set

    val xFrac get() = (left.toFloat()) / ((parent as? ViewGroup)?.width?.toFloat() ?: 1f)
    val yFrac get() = (top.toFloat()) / ((parent as? ViewGroup)?.height?.toFloat() ?: 1f)
    val wFrac get() = width.toFloat() / ((parent as? ViewGroup)?.width?.toFloat() ?: 1f)
    val hFrac get() = height.toFloat() / ((parent as? ViewGroup)?.height?.toFloat() ?: 1f)

    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7c6fff")
    }

    private var dragging = false
    private var resizing = false
    private var lastRawX = 0f
    private var lastRawY = 0f
    private val handlePx get() = dp(20f)

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (editMode && !dragging && !resizing) onLongPressRemove(this)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paintBg.color = if (pressed) Color.parseColor("#5b4fe8") else Color.parseColor("#161b2c")
        canvas.drawRoundRect(RectF(0f, 0f, w, h), dp(14f), dp(14f), paintBg)

        paintBorder.color = if (editMode) Color.parseColor("#7c6fff")
        else if (pressed) Color.parseColor("#9a8fff") else Color.parseColor("#2a3150")
        canvas.drawRoundRect(RectF(1.5f, 1.5f, w - 1.5f, h - 1.5f), dp(14f), dp(14f), paintBorder)

        paintText.textSize = h * 0.28f
        canvas.drawText(labelText, w / 2f, h / 2f + paintText.textSize * 0.34f, paintText)

        if (editMode) {
            canvas.drawCircle(w - handlePx / 2f - dp(4f), h - handlePx / 2f - dp(4f), handlePx / 2f, paintHandle)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true; invalidate(); return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pressed = false; invalidate(); return true
                }
            }
            return super.onTouchEvent(event)
        }

        val parentView = parent as? ViewGroup ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inHandle = event.x > width - handlePx * 1.6f && event.y > height - handlePx * 1.6f
                resizing = inHandle
                dragging = !inHandle
                lastRawX = event.rawX
                lastRawY = event.rawY
                longPressHandler.postDelayed(longPressRunnable, 550)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                if (abs(dx) > 10 || abs(dy) > 10) longPressHandler.removeCallbacks(longPressRunnable)
                val lp = layoutParams as ViewGroup.MarginLayoutParams
                val pw = parentView.width.toFloat()
                val ph = parentView.height.toFloat()
                if (dragging) {
                    lp.leftMargin = (lp.leftMargin + dx).toInt()
                        .coerceIn(0, (pw - width).toInt().coerceAtLeast(0))
                    lp.topMargin = (lp.topMargin + dy).toInt()
                        .coerceIn(0, (ph - height).toInt().coerceAtLeast(0))
                    layoutParams = lp
                } else if (resizing) {
                    val maxW = pw - lp.leftMargin
                    val maxH = ph - lp.topMargin
                    lp.width = (width + dx).coerceIn(dp(56f), maxW).toInt()
                    lp.height = (height + dy).coerceIn(dp(44f), maxH).toInt()
                    layoutParams = lp
                }
                lastRawX = event.rawX
                lastRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (dragging || resizing) onMoved()
                dragging = false
                resizing = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun toLayout() = ButtonLayout(action, xFrac, yFrac, wFrac, hFrac, labelText)
}
