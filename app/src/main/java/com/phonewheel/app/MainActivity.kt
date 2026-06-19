package com.phonewheel.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import okhttp3.*
import org.json.JSONObject
import kotlin.math.*

// ─────────────────────────────────────────────
//  Pedal View — vertical drag slider, 0–100
//  Release finger → value snaps to 0
// ─────────────────────────────────────────────
class PedalView(context: Context, private val isGas: Boolean) : View(context) {

    var value: Int = 0          // 0–100, read by MainActivity
        private set

    private val colorMain = if (isGas) Color.parseColor("#22dc82") else Color.parseColor("#ff4f4f")
    private val colorDark = if (isGas) Color.parseColor("#0e2a1e") else Color.parseColor("#2a0e0e")
    private val colorGrad = if (isGas) Color.parseColor("#16a85a") else Color.parseColor("#c03030")
    private val label     = if (isGas) "GAS" else "BRAKE"

    private val paintBg    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintFill  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintThumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGlow  = Paint(Paint.ANTI_ALIAS_FLAG)

    private var gradient: LinearGradient? = null
    private var lastH = 0

    private val trackPadH = 60f   // px above/below track inside view
    private val trackPadW = 28f

    init {
        paintBg.color    = Color.parseColor("#10141f")
        paintText.color  = colorMain
        paintText.typeface = Typeface.DEFAULT_BOLD
        paintText.textAlign = Paint.Align.CENTER
        paintThumb.color = Color.argb(80, 255, 255, 255)
        paintThumb.style = Paint.Style.FILL
        paintGlow.maskFilter = BlurMaskFilter(40f, BlurMaskFilter.Blur.NORMAL)
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        lastH = h
        val trackTop    = trackPadH
        val trackBottom = h - trackPadH
        gradient = LinearGradient(0f, trackBottom, 0f, trackTop,
            colorGrad, colorMain, Shader.TileMode.CLAMP)
        paintFill.shader = gradient
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        val w  = width.toFloat()
        val h  = height.toFloat()
        val cx = w / 2f
        val r  = 12f

        val trackLeft   = cx - trackPadW
        val trackRight  = cx + trackPadW
        val trackTop    = trackPadH
        val trackBottom = h - trackPadH
        val trackH      = trackBottom - trackTop

        // Background card
        val card = RectF(0f, 0f, w, h)
        paintBg.color = Color.parseColor("#10141f")
        canvas.drawRoundRect(card, 24f, 24f, paintBg)

        // Glow when active
        if (value > 0) {
            paintGlow.color = Color.argb((value * 1.5f).toInt().coerceIn(0, 80), colorMain.red, colorMain.green, colorMain.blue)
            canvas.drawCircle(cx, trackBottom, trackPadW * 1.8f, paintGlow)
        }

        // Track background
        paintBg.color = colorDark
        canvas.drawRoundRect(RectF(trackLeft, trackTop, trackRight, trackBottom), r, r, paintBg)

        // Fill
        val fillH = trackH * value / 100f
        if (fillH > 2f) {
            canvas.drawRoundRect(
                RectF(trackLeft, trackBottom - fillH, trackRight, trackBottom),
                r, r, paintFill
            )
        }

        // Thumb circle
        val thumbY = trackBottom - fillH
        paintThumb.color = Color.argb(if (value > 0) 160 else 60, 255, 255, 255)
        canvas.drawCircle(cx, thumbY.coerceIn(trackTop + 14f, trackBottom - 14f), 13f, paintThumb)

        // Label (top)
        paintText.textSize = 28f
        paintText.color = if (value > 0) colorMain else Color.parseColor("#6b7394")
        canvas.drawText(label, cx, trackPadH - 10f, paintText)

        // Value (bottom)
        paintText.textSize = 44f
        paintText.color = colorMain
        canvas.drawText("$value", cx, h - 8f, paintText)
    }

    // ── Touch: drag up/down to set value, release → 0 ──
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val trackTop    = trackPadH
        val trackBottom = height - trackPadH

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val rel = 1f - (event.y - trackTop) / (trackBottom - trackTop)
                value = (rel * 100f).roundToInt().coerceIn(0, 100)
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

    // Helper to read color components
    private val Int.red   get() = (this shr 16) and 0xFF
    private val Int.green get() = (this shr 8)  and 0xFF
    private val Int.blue  get() = this and 0xFF
}

// ─────────────────────────────────────────────
//  Wheel View — animated steering wheel
// ─────────────────────────────────────────────
class WheelView(context: Context) : View(context) {

    var steer: Float = 0f
    var gas:   Float = 0f
    var brake: Float = 0f

    private val paintRing  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintSpoke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintHub   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintArc   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintPedal = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w  = width.toFloat()
        val h  = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r  = min(w, h) * 0.36f

        // Ring
        paintRing.style      = Paint.Style.STROKE
        paintRing.strokeWidth = r * 0.18f
        paintRing.color       = Color.parseColor("#1e2536")
        canvas.drawCircle(cx, cy, r, paintRing)

        // Steer arc
        if (abs(steer) > 0.01f) {
            paintArc.style       = Paint.Style.STROKE
            paintArc.strokeWidth = r * 0.18f
            val alpha = (80 + abs(steer) * 170).toInt().coerceIn(0, 255)
            paintArc.color = Color.argb(alpha, 124, 111, 255)
            val startDeg = -90f
            val sweepDeg = steer * 144f
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            if (sweepDeg >= 0) canvas.drawArc(oval, startDeg, sweepDeg, false, paintArc)
            else               canvas.drawArc(oval, startDeg + sweepDeg, -sweepDeg, false, paintArc)
        }

        // Spokes
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(steer * 82f)
        paintSpoke.style       = Paint.Style.STROKE
        paintSpoke.strokeWidth = r * 0.042f
        paintSpoke.strokeCap   = Paint.Cap.ROUND
        paintSpoke.color       = Color.parseColor("#7c6fff")
        canvas.drawLine(-r * 0.78f, 0f, r * 0.78f, 0f, paintSpoke)
        canvas.drawLine(0f, 0f, 0f, r * 0.70f, paintSpoke)
        canvas.restore()

        // Hub
        paintHub.style = Paint.Style.FILL
        paintHub.color = Color.parseColor("#1a1f32")
        canvas.drawCircle(cx, cy, r * 0.27f, paintHub)

        // Steer text
        paintText.color     = Color.parseColor("#7c6fff")
        paintText.textSize  = r * 0.20f
        paintText.typeface  = Typeface.DEFAULT_BOLD
        paintText.textAlign = Paint.Align.CENTER
        val deg = (steer * 90).roundToInt()
        canvas.drawText("${if (deg >= 0) "+" else ""}${deg}°", cx, cy + r * 0.07f, paintText)

        // Gas arc (bottom-left)
        drawPedalArc(canvas, cx, cy, r * 0.72f, r * 0.11f,
            startAngle = 200f, sweep = 60f, value = gas,
            bgColor = Color.parseColor("#0e2a1e"),
            fgColor = Color.parseColor("#22dc82"))

        // Brake arc (bottom-right)
        drawPedalArc(canvas, cx, cy, r * 0.72f, r * 0.11f,
            startAngle = 280f, sweep = 60f, value = brake,
            bgColor = Color.parseColor("#2a0e0e"),
            fgColor = Color.parseColor("#ff4f4f"))
    }

    private fun drawPedalArc(
        canvas: Canvas, cx: Float, cy: Float,
        radius: Float, strokeW: Float,
        startAngle: Float, sweep: Float, value: Float,
        bgColor: Int, fgColor: Int
    ) {
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

    private fun Float.roundToInt() = kotlin.math.roundToInt(this)
}

// ─────────────────────────────────────────────
//  MainActivity
// ─────────────────────────────────────────────
@Suppress("DEPRECATION")
class MainActivity : Activity(), SensorEventListener {

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var rotSensor: Sensor? = null
    private val rotMatrix  = FloatArray(9)
    private val orientation = FloatArray(3)

    // State
    private var rollDeg      = 0f
    private var centerRoll   = 0f
    private var steer        = 0f
    private var maxSteerAngle = 45f
    private var usbMode      = true
    private var connected    = false
    private var seq          = 0L

    // WS
    private val handler  = Handler(Looper.getMainLooper())
    private val client   = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
    private var socket: WebSocket? = null

    // Views
    private lateinit var gasView:    PedalView
    private lateinit var brakeView:  PedalView
    private lateinit var wheelView:  WheelView
    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button
    private lateinit var ipInput:    EditText

    // ── Lifecycle ─────────────────────────────
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
        socket?.close(1000, "app closed")
        super.onDestroy()
    }

    // ── Sensors ───────────────────────────────
    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR ||
            event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            rollDeg = (orientation[2] * 180f / PI.toFloat())
            val raw = (rollDeg - centerRoll) / maxSteerAngle
            steer = raw.coerceIn(-1f, 1f).let { if (abs(it) < 0.015f) 0f else it }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── UI ────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun buildUi() {
        // Root: horizontal layout  [GAS | CENTER | BRAKE]
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#080b12"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        // ── GAS (left) ──────────────────────────
        gasView = PedalView(this, isGas = true).apply {
            background = roundRect(Color.parseColor("#10141f"), dp(18).toFloat())
        }
        root.addView(gasView, lp(0, MATCH, 1.05f, marginEnd = dp(6)))

        // ── CENTER ──────────────────────────────
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundRect(Color.parseColor("#10141f"), dp(18).toFloat())
        }
        root.addView(center, lp(0, MATCH, 2.4f, marginEnd = dp(6)))

        // Status bar
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusText = tv("не подключено", 12f, Color.parseColor("#ffbe5f"))
        topRow.addView(statusText, lp(0, WRAP, 1f))
        center.addView(topRow, lp(MATCH, dp(26)))

        // Mode buttons
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val usbBtn  = modeTabBtn("USB",   selected = true)
        val wifiBtn = modeTabBtn("Wi-Fi", selected = false)
        usbBtn.setOnClickListener  { setConnMode(true,  usbBtn, wifiBtn) }
        wifiBtn.setOnClickListener { setConnMode(false, usbBtn, wifiBtn) }
        modeRow.addView(usbBtn,  lp(0, dp(36), 1f, marginEnd = dp(4)))
        modeRow.addView(wifiBtn, lp(0, dp(36), 1f))
        center.addView(modeRow, lp(MATCH, dp(36)).apply { setMargins(0, dp(4), 0, 0) })

        // IP input (hidden by default)
        ipInput = EditText(this).apply {
            hint = "192.168.1.20"
            setText("192.168.1.20")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6b7394"))
            singleLine = true
            background = roundRect(Color.parseColor("#0a0d15"), dp(10).toFloat())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        center.addView(ipInput, lp(MATCH, dp(42)).apply { setMargins(0, dp(4), 0, 0) })

        // Connect + Center buttons
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectBtn = Button(this).apply {
            text = "Подключить"
            setTextColor(Color.parseColor("#7c6fff"))
            background = roundRect(Color.parseColor("#1f2a4a"), dp(10).toFloat())
            setOnClickListener { if (connected) disconnect() else connect() }
        }
        val centerBtn = Button(this).apply {
            text = "Центр"
            setTextColor(Color.WHITE)
            background = roundRect(Color.parseColor("#1e2536"), dp(10).toFloat())
            setOnClickListener {
                centerRoll = rollDeg
                socket?.send(JSONObject().put("type", "center").toString())
                Toast.makeText(context, "Центр установлен", Toast.LENGTH_SHORT).show()
            }
        }
        btnRow.addView(connectBtn, lp(0, dp(44), 1f, marginEnd = dp(4)))
        btnRow.addView(centerBtn,  lp(0, dp(44), 1f))
        center.addView(btnRow, lp(MATCH, dp(44)).apply { setMargins(0, dp(4), 0, 0) })

        // Angle sensitivity label + seekbar
        val angleLabel = tv("Угол: 45°", 11f, Color.parseColor("#6b7394"))
        center.addView(angleLabel, lp(MATCH, dp(22)).apply { setMargins(0, dp(6), 0, 0) })
        val angleSeek = SeekBar(this).apply {
            max = 80; progress = 35 // 10+35=45
            setOnSeekBarChangeListener(simpleSeek { p ->
                maxSteerAngle = (10 + p).toFloat()
                angleLabel.text = "Угол: ${maxSteerAngle.toInt()}°"
            })
        }
        center.addView(angleSeek, lp(MATCH, dp(32)))

        // Wheel canvas (fills remaining space)
        wheelView = WheelView(this)
        center.addView(wheelView, lp(MATCH, 0, 1f))

        // Live steer text
        val steerTv = tv("+0°", 22f, Color.parseColor("#7c6fff"), bold = true).also {
            it.gravity = android.view.Gravity.CENTER
        }
        center.addView(steerTv, lp(MATCH, dp(34)))
        // store ref so uiLoop can update it
        steerTextView = steerTv

        // Hint
        val hint = tv("Держи как руль. Нажми Центр перед игрой.", 11f, Color.parseColor("#6b7394"))
        hint.gravity = android.view.Gravity.CENTER
        center.addView(hint, lp(MATCH, dp(28)))

        // ── BRAKE (right) ───────────────────────
        brakeView = PedalView(this, isGas = false).apply {
            background = roundRect(Color.parseColor("#10141f"), dp(18).toFloat())
        }
        root.addView(brakeView, lp(0, MATCH, 1.05f))

        setContentView(root)
    }

    private lateinit var steerTextView: TextView

    // ── Connection ────────────────────────────
    private fun setConnMode(usb: Boolean, usbBtn: Button, wifiBtn: Button) {
        usbMode = usb
        usbBtn.setTextColor(if (usb) Color.parseColor("#7c6fff") else Color.parseColor("#6b7394"))
        usbBtn.background = roundRect(
            if (usb) Color.parseColor("#1a213a") else Color.parseColor("#1e2536"), dp(10).toFloat())
        wifiBtn.setTextColor(if (!usb) Color.parseColor("#7c6fff") else Color.parseColor("#6b7394"))
        wifiBtn.background = roundRect(
            if (!usb) Color.parseColor("#1a213a") else Color.parseColor("#1e2536"), dp(10).toFloat())
        ipInput.visibility = if (usb) View.GONE else View.VISIBLE
    }

    private fun connect() {
        val url = if (usbMode) "ws://127.0.0.1:27111/ws"
        else "ws://${ipInput.text.toString().trim().ifBlank { "127.0.0.1" }}:27111/ws"
        statusText.text = "Подключение..."
        statusText.setTextColor(Color.parseColor("#ffbe5f"))
        val req = Request.Builder().url(url).build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected = true
                ws.send(JSONObject().put("type","hello")
                    .put("name", android.os.Build.MODEL)
                    .put("mode", if (usbMode) "usb" else "wifi").toString())
                runOnUiThread {
                    statusText.text = "Подключено"
                    statusText.setTextColor(Color.parseColor("#22dc82"))
                    connectBtn.text = "Отключить"
                    connectBtn.setTextColor(Color.parseColor("#22dc82"))
                    connectBtn.background = roundRect(Color.parseColor("#0e2a1e"), dp(10).toFloat())
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connected = false
                runOnUiThread {
                    statusText.text = "Ошибка: ${t.message?.take(30)}"
                    statusText.setTextColor(Color.parseColor("#ff6060"))
                    connectBtn.text = "Подключить"
                    connectBtn.setTextColor(Color.parseColor("#7c6fff"))
                    connectBtn.background = roundRect(Color.parseColor("#1f2a4a"), dp(10).toFloat())
                }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connected = false
                runOnUiThread {
                    statusText.text = "Отключено"
                    statusText.setTextColor(Color.parseColor("#ffbe5f"))
                    connectBtn.text = "Подключить"
                    connectBtn.setTextColor(Color.parseColor("#7c6fff"))
                    connectBtn.background = roundRect(Color.parseColor("#1f2a4a"), dp(10).toFloat())
                }
            }
        })
    }

    private fun disconnect() {
        connected = false
        socket?.close(1000, "user")
        socket = null
        statusText.text = "Отключено"
        statusText.setTextColor(Color.parseColor("#ffbe5f"))
        connectBtn.text = "Подключить"
        connectBtn.setTextColor(Color.parseColor("#7c6fff"))
        connectBtn.background = roundRect(Color.parseColor("#1f2a4a"), dp(10).toFloat())
    }

    // ── Send loop (60 Hz) ──────────────────────
    private fun startSendLoop() {
        handler.post(object : Runnable {
            override fun run() {
                if (connected) {
                    val obj = JSONObject()
                    obj.put("type",     "state")
                    obj.put("seq",      seq++)
                    obj.put("steer",    steer.toDouble())
                    obj.put("throttle", gasView.value.toDouble() / 100.0)
                    obj.put("brake",    brakeView.value.toDouble() / 100.0)
                    obj.put("buttons",  JSONObject())
                    try { socket?.send(obj.toString()) } catch (_: Exception) {}
                }
                handler.postDelayed(this, 16L)
            }
        })
    }

    // ── UI refresh loop (25 Hz) ────────────────
    private fun startUiLoop() {
        handler.post(object : Runnable {
            override fun run() {
                val deg = (steer * maxSteerAngle).roundToInt()
                steerTextView.text = "${if (deg >= 0) "+" else ""}${deg}°"
                wheelView.steer = steer
                wheelView.gas   = gasView.value / 100f
                wheelView.brake = brakeView.value / 100f
                wheelView.invalidate()
                handler.postDelayed(this, 40L)
            }
        })
    }

    // ── Helpers ───────────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun lp(w: Int, h: Int, weight: Float = 0f,
                   marginEnd: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply {
            if (marginEnd > 0) setMargins(0, 0, marginEnd, 0)
        }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize  = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun modeTabBtn(label: String, selected: Boolean) = Button(this).apply {
        text = label
        setTextColor(if (selected) Color.parseColor("#7c6fff") else Color.parseColor("#6b7394"))
        background = roundRect(
            if (selected) Color.parseColor("#1a213a") else Color.parseColor("#1e2536"),
            dp(10).toFloat())
    }

    private fun roundRect(color: Int, radius: Float) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color); cornerRadius = radius
        }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) = onChange(p)
        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
    }

    private fun Float.roundToInt() = kotlin.math.roundToInt(this)
}
