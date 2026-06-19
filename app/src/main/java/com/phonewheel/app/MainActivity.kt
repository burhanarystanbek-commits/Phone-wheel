package com.phonewheel.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.*
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import kotlin.math.*

class PedalView(context: Context, private val isGas: Boolean) : View(context) {

    var value: Int = 0
        private set

    private val colorMain = if (isGas) Color.parseColor("#22dc82") else Color.parseColor("#ff4f4f")
    private val colorDark = if (isGas) Color.parseColor("#0e2a1e") else Color.parseColor("#2a0e0e")
    private val colorGrad = if (isGas) Color.parseColor("#16a85a") else Color.parseColor("#c03030")
    private val label     = if (isGas) "GAS" else "BRAKE"

    private val paintBg     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintFill   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintThumb  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGlow   = Paint(Paint.ANTI_ALIAS_FLAG)

    private var gradient: LinearGradient? = null

    init {
        paintText.typeface   = Typeface.DEFAULT_BOLD
        paintText.textAlign  = Paint.Align.CENTER
        paintGlow.maskFilter = BlurMaskFilter(44f, BlurMaskFilter.Blur.NORMAL)
        paintBorder.style       = Paint.Style.STROKE
        paintBorder.strokeWidth = 2.5f
        paintBorder.color       = Color.argb(40, 255, 255, 255)
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        val trackTop    = dp(50f)
        val trackBottom = h - dp(50f)
        gradient = LinearGradient(0f, trackBottom, 0f, trackTop,
            colorGrad, colorMain, Shader.TileMode.CLAMP)
        paintFill.shader = gradient
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w  = width.toFloat()
        val h  = height.toFloat()
        val cx = w / 2f
        val padH = dp(52f)
        val padW = dp(16f)

        val trackLeft   = cx - padW
        val trackRight  = cx + padW
        val trackTop    = padH
        val trackBottom = h - padH
        val trackH      = trackBottom - trackTop

        // card bg — лёгкий вертикальный градиент вместо плоского цвета
        val cardGrad = LinearGradient(0f, 0f, 0f, h,
            Color.parseColor("#141927"), Color.parseColor("#0d1119"), Shader.TileMode.CLAMP)
        paintBg.shader = cardGrad
        canvas.drawRoundRect(RectF(0f, 0f, w, h), dp(20f), dp(20f), paintBg)
        paintBg.shader = null

        // тонкая обводка карточки, чуть ярче когда активна
        paintBorder.color = if (value > 0)
            Color.argb(70, Color.red(colorMain), Color.green(colorMain), Color.blue(colorMain))
        else Color.argb(28, 255, 255, 255)
        canvas.drawRoundRect(RectF(1.5f, 1.5f, w - 1.5f, h - 1.5f), dp(20f), dp(20f), paintBorder)

        // glow
        if (value > 0) {
            paintGlow.color = Color.argb(
                (value * 1.1f).toInt().coerceIn(0, 80),
                Color.red(colorMain), Color.green(colorMain), Color.blue(colorMain)
            )
            canvas.drawCircle(cx, trackBottom, padW * 2.2f, paintGlow)
        }

        // track bg
        paintBg.color = colorDark
        canvas.drawRoundRect(RectF(trackLeft, trackTop, trackRight, trackBottom), dp(12f), dp(12f), paintBg)

        // fill
        val fillH = trackH * value / 100f
        if (fillH > 2f) {
            canvas.drawRoundRect(
                RectF(trackLeft, trackBottom - fillH, trackRight, trackBottom),
                dp(12f), dp(12f), paintFill
            )
        }

        // thumb с тонким контрастным кольцом
        val thumbY = (trackBottom - fillH).coerceIn(trackTop + dp(13f), trackBottom - dp(13f))
        paintThumb.style = Paint.Style.FILL
        paintThumb.color = Color.argb(if (value > 0) 220 else 70, 255, 255, 255)
        canvas.drawCircle(cx, thumbY, dp(8.5f), paintThumb)
        paintThumb.style = Paint.Style.STROKE
        paintThumb.strokeWidth = dp(2f)
        paintThumb.color = if (value > 0) colorMain else Color.argb(60, 255, 255, 255)
        canvas.drawCircle(cx, thumbY, dp(12.5f), paintThumb)

        // label top
        paintText.textSize = dp(11.5f)
        paintText.letterSpacing = 0.12f
        paintText.color = if (value > 0) colorMain else Color.parseColor("#6b7394")
        canvas.drawText(label, cx, padH - dp(8f), paintText)
        paintText.letterSpacing = 0f

        // value bottom
        paintText.textSize = dp(22f)
        paintText.color = if (value > 0) colorMain else Color.parseColor("#8b93b0")
        canvas.drawText("$value", cx, h - dp(8f), paintText)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val padH        = dp(50f)
        val trackTop    = padH
        val trackBottom = height - padH

        fun calc(y: Float): Int {
            val rel = 1f - (y - trackTop) / (trackBottom - trackTop)
            return (rel * 100f).toInt().coerceIn(0, 100)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                value = calc(event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                value = 0
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

class WheelView(context: Context) : View(context) {

    var steer: Float = 0f
    var gas:   Float = 0f
    var brake: Float = 0f

    private val paintRing  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintSpoke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintHub   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintHubRing = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintArc   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintPedal = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGlow  = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paintGlow.maskFilter = BlurMaskFilter(36f, BlurMaskFilter.Blur.NORMAL)
        paintHubRing.style = Paint.Style.STROKE
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w  = width.toFloat()
        val h  = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r  = minOf(w, h) * 0.36f

        // мягкое свечение под кольцом при активном повороте
        if (abs(steer) > 0.02f) {
            paintGlow.color = Color.argb(
                (abs(steer) * 90).toInt().coerceIn(0, 90), 124, 111, 255
            )
            canvas.drawCircle(cx, cy, r * 1.12f, paintGlow)
        }

        // outer ring
        paintRing.style       = Paint.Style.STROKE
        paintRing.strokeWidth = r * 0.18f
        paintRing.color       = Color.parseColor("#1e2536")
        canvas.drawCircle(cx, cy, r, paintRing)

        // steer arc
        if (abs(steer) > 0.01f) {
            paintArc.style       = Paint.Style.STROKE
            paintArc.strokeWidth = r * 0.18f
            paintArc.strokeCap   = Paint.Cap.ROUND
            val alpha = (90 + abs(steer) * 165).toInt().coerceIn(0, 255)
            paintArc.color = Color.argb(alpha, 138, 125, 255)
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            val sweepDeg = steer * 144f
            if (sweepDeg >= 0) canvas.drawArc(oval, -90f, sweepDeg, false, paintArc)
            else               canvas.drawArc(oval, -90f + sweepDeg, -sweepDeg, false, paintArc)
        }

        // spokes
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(steer * 82f)
        paintSpoke.style       = Paint.Style.STROKE
        paintSpoke.strokeWidth = r * 0.042f
        paintSpoke.strokeCap   = Paint.Cap.ROUND
        paintSpoke.color       = Color.parseColor("#8a7eff")
        canvas.drawLine(-r * 0.78f, 0f, r * 0.78f, 0f, paintSpoke)
        canvas.drawLine(0f, 0f, 0f, r * 0.70f, paintSpoke)
        canvas.restore()

        // hub — лёгкий радиальный градиент вместо плоской заливки
        paintHub.style = Paint.Style.FILL
        paintHub.shader = RadialGradient(cx, cy - r * 0.06f, r * 0.30f,
            Color.parseColor("#232a45"), Color.parseColor("#171c2e"), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 0.27f, paintHub)
        paintHub.shader = null
        paintHubRing.strokeWidth = dp(1.5f)
        paintHubRing.color = Color.argb(50, 255, 255, 255)
        canvas.drawCircle(cx, cy, r * 0.27f, paintHubRing)

        // steer text
        paintText.color     = Color.parseColor("#8a7eff")
        paintText.textSize  = r * 0.20f
        paintText.typeface  = Typeface.DEFAULT_BOLD
        paintText.textAlign = Paint.Align.CENTER
        val deg = (steer * 90f).toInt()
        val sign = if (deg >= 0) "+" else ""
        canvas.drawText("$sign${deg}°", cx, cy + r * 0.08f, paintText)

        // gas arc bottom-left
        drawPedalArc(canvas, cx, cy, r * 0.72f, r * 0.11f, 200f, 60f, gas,
            Color.parseColor("#0e2a1e"), Color.parseColor("#22dc82"))
        // brake arc bottom-right
        drawPedalArc(canvas, cx, cy, r * 0.72f, r * 0.11f, 280f, 60f, brake,
            Color.parseColor("#2a0e0e"), Color.parseColor("#ff4f4f"))
    }

    private fun drawPedalArc(canvas: Canvas, cx: Float, cy: Float,
        radius: Float, strokeW: Float, startAngle: Float, sweep: Float,
        value: Float, bgColor: Int, fgColor: Int) {
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paintPedal.style       = Paint.Style.STROKE
        paintPedal.strokeWidth = strokeW * 2f
        paintPedal.strokeCap   = Paint.Cap.ROUND
        paintPedal.color       = bgColor
        canvas.drawArc(oval, startAngle, sweep, false, paintPedal)
        if (value > 0.01f) {
            paintPedal.color = fgColor
            canvas.drawArc(oval, startAngle, sweep * value, false, paintPedal)
        }
    }
}

@Suppress("DEPRECATION")
class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotSensor: Sensor? = null
    private val rotMatrix      = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation    = FloatArray(3)

    private var rollDeg       = 0f
    private var centerRoll    = 0f
    private var steer         = 0f
    private var maxSteerAngle = 45f
    private var usbMode       = true
    private var connected     = false
    private var seq           = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val client  = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
    private var socket: WebSocket? = null

    private lateinit var gasView:       PedalView
    private lateinit var brakeView:     PedalView
    private lateinit var wheelView:     WheelView
    private lateinit var statusText:    TextView
    private lateinit var statusDot:     View
    private lateinit var connectBtn:    Button
    private lateinit var ipInput:       EditText
    private lateinit var steerTv:       TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setupSensors()
        buildUi()
        startSendLoop()
        startUiLoop()
    }

    override fun onResume() {
        super.onResume()
        rotSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        socket?.close(1000, "closed")
        super.onDestroy()
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR ||
            event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

            // Переотображаем систему координат датчика под реальный физический
            // поворот экрана. Телефон в landscape можно держать двумя способами
            // (повёрнут на 90° влево или вправо от portrait) — Surface.getRotation()
            // говорит, какой именно, чтобы руление работало одинаково в обоих случаях.
            @Suppress("DEPRECATION")
            val rotation = windowManager.defaultDisplay.rotation
            when (rotation) {
                Surface.ROTATION_90 ->
                    SensorManager.remapCoordinateSystem(
                        rotMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix)
                Surface.ROTATION_270 ->
                    SensorManager.remapCoordinateSystem(
                        rotMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedMatrix)
                else ->
                    System.arraycopy(rotMatrix, 0, remappedMatrix, 0, rotMatrix.size)
            }

            SensorManager.getOrientation(remappedMatrix, orientation)
            // После remap-а в landscape руление как "наклон руля" — это уже pitch (orientation[1]),
            // а не roll (orientation[2]), который использовался для portrait-логики.
            rollDeg = orientation[1] * 180f / PI.toFloat()
            val raw = (rollDeg - centerRoll) / maxSteerAngle
            steer = raw.coerceIn(-1f, 1f).let { if (abs(it) < 0.015f) 0f else it }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun lp(w: Int, h: Int, weight: Float = 0f, endMargin: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).also {
            if (endMargin > 0) it.setMargins(0, 0, endMargin, 0)
        }

    private fun tv(text: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize  = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun roundRect(color: Int, radius: Float, strokeColor: Int? = null, strokeW: Float = 0f) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color); cornerRadius = radius
            if (strokeColor != null) setStroke(strokeW.toInt(), strokeColor)
        }

    private fun tabBtn(label: String, selected: Boolean) = Button(this).apply {
        text = label
        textSize = 12.5f
        letterSpacing = 0.04f
        setTextColor(if (selected) Color.WHITE else Color.parseColor("#6b7394"))
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        background = if (selected)
            roundRect(Color.parseColor("#5b4fe8"), dp(12).toFloat())
        else
            roundRect(Color.parseColor("#161b2c"), dp(12).toFloat(),
                Color.parseColor("#252c44"), dp(2).toFloat())
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#0a0d16"), Color.parseColor("#070910"))
            )
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }

        // GAS left
        gasView = PedalView(this, true)
        root.addView(gasView, lp(0, MATCH, 1f, endMargin = dp(7)))

        // CENTER
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundRect(Color.parseColor("#11151f"), dp(20).toFloat(),
                Color.parseColor("#1f2536"), dp(2).toFloat())
        }
        root.addView(center, lp(0, MATCH, 2.4f, endMargin = dp(7)))

        // status row with indicator dot
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#ffbe5f"))
            }
        }
        topRow.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).also { it.setMargins(0, 0, dp(6), 0) })
        statusText = tv("не подключено", 12f, Color.parseColor("#ffbe5f"), bold = true)
        topRow.addView(statusText, lp(0, WRAP, 1f))
        center.addView(topRow, lp(MATCH, dp(26)))

        // mode toggle
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val usbBtn  = tabBtn("USB", true)
        val wifiBtn = tabBtn("Wi-Fi", false)
        usbBtn.setOnClickListener  { setConnMode(true, usbBtn, wifiBtn) }
        wifiBtn.setOnClickListener { setConnMode(false, usbBtn, wifiBtn) }
        modeRow.addView(usbBtn,  lp(0, dp(36), 1f, endMargin = dp(4)))
        modeRow.addView(wifiBtn, lp(0, dp(36), 1f))
        center.addView(modeRow, lp(MATCH, dp(36)).also { it.setMargins(0, dp(4), 0, 0) })

        // IP input
        ipInput = EditText(this).apply {
            hint = "192.168.1.20"
            setText("192.168.1.20")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#525a78"))
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            background = roundRect(Color.parseColor("#0a0d15"), dp(12).toFloat(),
                Color.parseColor("#1f2536"), dp(2).toFloat())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        center.addView(ipInput, lp(MATCH, dp(42)).also { it.setMargins(0, dp(4), 0, 0) })

        // connect + center buttons
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectBtn = Button(this).apply {
            text = "Подключить"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundRect(Color.parseColor("#5b4fe8"), dp(12).toFloat())
            setOnClickListener { if (connected) disconnect() else connect() }
        }
        val centerBtn = Button(this).apply {
            text = "Центр"
            textSize = 13f
            setTextColor(Color.parseColor("#c7cbe0"))
            background = roundRect(Color.parseColor("#161b2c"), dp(12).toFloat(),
                Color.parseColor("#252c44"), dp(2).toFloat())
            setOnClickListener {
                centerRoll = rollDeg
                Toast.makeText(context, "Центр установлен", Toast.LENGTH_SHORT).show()
            }
        }
        b
