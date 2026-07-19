package com.phonewheel.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.text.InputType
import android.view.*
import android.widget.*
import com.phonewheel.app.transport.ConnectionManager
import com.phonewheel.app.transport.ConnectionState
import com.phonewheel.app.transport.ITransport
import com.phonewheel.app.transport.TransportKind
import com.phonewheel.app.transport.WebSocketTransport
import com.phonewheel.app.transport.BluetoothTransport
import com.phonewheel.app.bluetooth.BluetoothManager
import com.phonewheel.app.bluetooth.BtDevice
import android.content.pm.PackageManager
import org.json.JSONObject
import kotlin.math.*

class PedalView(context: Context, private val isGas: Boolean) : View(context) {

    var value: Int = 0
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

    init {
        paintText.typeface  = Typeface.DEFAULT_BOLD
        paintText.textAlign = Paint.Align.CENTER
        paintGlow.maskFilter = BlurMaskFilter(40f, BlurMaskFilter.Blur.NORMAL)
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
        val padH = dp(50f)
        val padW = dp(14f)

        val trackLeft   = cx - padW
        val trackRight  = cx + padW
        val trackTop    = padH
        val trackBottom = h - padH
        val trackH      = trackBottom - trackTop

        // card bg
        paintBg.color = Color.parseColor("#10141f")
        canvas.drawRoundRect(RectF(0f, 0f, w, h), dp(18f), dp(18f), paintBg)

        // glow
        if (value > 0) {
            paintGlow.color = Color.argb(
                (value * 1.2f).toInt().coerceIn(0, 70),
                Color.red(colorMain), Color.green(colorMain), Color.blue(colorMain)
            )
            canvas.drawCircle(cx, trackBottom, padW * 2f, paintGlow)
        }

        // track bg
        paintBg.color = colorDark
        canvas.drawRoundRect(RectF(trackLeft, trackTop, trackRight, trackBottom), dp(10f), dp(10f), paintBg)

        // fill
        val fillH = trackH * value / 100f
        if (fillH > 2f) {
            canvas.drawRoundRect(
                RectF(trackLeft, trackBottom - fillH, trackRight, trackBottom),
                dp(10f), dp(10f), paintFill
            )
        }

        // thumb
        val thumbY = (trackBottom - fillH).coerceIn(trackTop + dp(12f), trackBottom - dp(12f))
        paintThumb.color = Color.argb(if (value > 0) 150 else 50, 255, 255, 255)
        canvas.drawCircle(cx, thumbY, dp(12f), paintThumb)

        // label top
        paintText.textSize = dp(11f)
        paintText.color = if (value > 0) colorMain else Color.parseColor("#6b7394")
        canvas.drawText(label, cx, padH - dp(6f), paintText)

        // value bottom
        paintText.textSize = dp(20f)
        paintText.color = colorMain
        canvas.drawText("$value", cx, h - dp(6f), paintText)
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
    private val paintText  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintArc   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintPedal = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w  = width.toFloat()
        val h  = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r  = minOf(w, h) * 0.36f

        // outer ring
        paintRing.style       = Paint.Style.STROKE
        paintRing.strokeWidth = r * 0.18f
        paintRing.color       = Color.parseColor("#1e2536")
        canvas.drawCircle(cx, cy, r, paintRing)

        // steer arc
        if (abs(steer) > 0.01f) {
            paintArc.style       = Paint.Style.STROKE
            paintArc.strokeWidth = r * 0.18f
            val alpha = (80 + abs(steer) * 170).toInt().coerceIn(0, 255)
            paintArc.color = Color.argb(alpha, 124, 111, 255)
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
        paintSpoke.color       = Color.parseColor("#7c6fff")
        canvas.drawLine(-r * 0.78f, 0f, r * 0.78f, 0f, paintSpoke)
        canvas.drawLine(0f, 0f, 0f, r * 0.70f, paintSpoke)
        canvas.restore()

        // hub
        paintHub.style = Paint.Style.FILL
        paintHub.color = Color.parseColor("#1a1f32")
        canvas.drawCircle(cx, cy, r * 0.27f, paintHub)

        // steer text
        paintText.color     = Color.parseColor("#7c6fff")
        paintText.textSize  = r * 0.20f
        paintText.typeface  = Typeface.DEFAULT_BOLD
        paintText.textAlign = Paint.Align.CENTER
        val deg = (steer * 90f).toInt()
        val sign = if (deg >= 0) "+" else ""
        canvas.drawText("$sign${deg}°", cx, cy + r * 0.08f, paintText)

        // brake arc bottom-left
        drawPedalArc(canvas, cx, cy, r * 0.72f, r * 0.11f, 200f, 60f, brake,
            Color.parseColor("#2a0e0e"), Color.parseColor("#ff4f4f"))
        // gas arc bottom-right
        drawPedalArc(canvas, cx, cy, r * 0.72f, r * 0.11f, 280f, 60f, gas,
            Color.parseColor("#0e2a1e"), Color.parseColor("#22dc82"))
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
    private val remappedMatrix = FloatArray(9)   // axes corrected for landscape hold
    private val orientation    = FloatArray(3)

    // Low-pass filter: smooth raw sensor value so a momentary bump doesn't
    // snap the steering. Alpha=0.15 = heavy smoothing, good for gyro noise.
    private val LP_ALPHA = 0.15f
    private var smoothedRoll = 0f
    private var firstSample  = true

    // Calibration: collect N samples on "Центр" press and use their average
    // instead of a single instantaneous reading — eliminates the 1-2° error
    // that comes from pressing the button exactly at a sensor noise peak.
    private val calibSamples   = mutableListOf<Float>()
    private var calibrating    = false
    private val CALIB_FRAMES   = 30   // ~300ms at 100Hz sensor rate

    private var rollDeg       = 0f
    private var centerRoll    = 0f
    private var steer         = 0f
    private var maxSteerAngle = 45f
    private var usbMode       = true
    private var connMode: TransportKind = TransportKind.USB
    private var selectedBtDevice: BtDevice? = null
    private var connected     = false
    private var seq           = 0L

    private val handler = Handler(Looper.getMainLooper())

    // Dedicated high-priority background thread for the 100 Hz send loop.
    // Running sends off the main thread means UI touch events never delay
    // outgoing packets, and the OS scheduler can give this thread elevated
    // CPU priority independent of the UI thread.
    private val sendThread = HandlerThread("pw-send", Process.THREAD_PRIORITY_URGENT_DISPLAY)
    private val sendHandler by lazy {
        sendThread.start()
        Handler(sendThread.looper)
    }

    // Reusable JSON objects — rebuilt only when button count changes, not
    // every frame, to keep per-frame allocation near zero and avoid GC spikes.
    private val stateJson     = org.json.JSONObject()
    private val buttonsJson   = org.json.JSONObject()
    private val labelsJson    = org.json.JSONObject()
    private val axesJson      = org.json.JSONObject()
    private val axisLabelsJson = org.json.JSONObject()
    private var lastBtnCount  = -1
    private var lastKnobCount = -1
    private var lastSendAt    = 0L
    private val connectionManager = ConnectionManager()
    private val btManager by lazy { BluetoothManager(this) }
    private val PERMISSION_REQUEST_CODE = 4201

    private lateinit var gasView:       PedalView
    private lateinit var brakeView:     PedalView
    private lateinit var wheelView:     WheelView
    private lateinit var statusText:    TextView
    private lateinit var connectBtn:    Button
    private lateinit var ipInput:       EditText
    private lateinit var btDeviceLabel: TextView
    private var btRowRef: LinearLayout? = null
    private lateinit var steerTv:       TextView

    // --- customizable buttons (free placement, unlimited count) ---
    private lateinit var layoutStore:    LayoutStore
    private lateinit var buttonOverlay:  FrameLayout
    private lateinit var editToggleBtn:  Button
    private lateinit var editToolbar:    LinearLayout
    private val customButtons = mutableListOf<CustomButtonView>()
    private var nextButtonId = 1
    private var editMode = false

    // --- customizable rotary knobs (ABS / TC / brake balance / ...) ---
    private lateinit var knobLayoutStore: KnobLayoutStore
    private val customKnobs = mutableListOf<RotaryKnobView>()
    private var nextAxisId = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        layoutStore = LayoutStore(this)
        knobLayoutStore = KnobLayoutStore(this)
        // Restore saved steering angle (default 45°)
        val prefs = getSharedPreferences("phonewheel_settings", Context.MODE_PRIVATE)
        maxSteerAngle = prefs.getFloat("maxSteerAngle", 45f)
        setupSensors()
        buildUi()
        setupConnectionCallbacks()
        startSendLoop()
        startUiLoop()
    }

    override fun onResume() {
        super.onResume()
        firstSample = true  // reset low-pass filter on resume
        calibrating = false
        calibSamples.clear()
        rotSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        connectionManager.disconnect()
        btManager.teardown()
        sendThread.quitSafely()
        super.onDestroy()
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

        // Remap axes for landscape (horizontal) hold.
        // The phone is held like a steering wheel, rotated 90° from portrait.
        // In this orientation:
        //   - physical "left/right tilt" = what we want as steer input
        //   - without remapping, getOrientation gives portrait axes and
        //     the left/right tilt maps to pitch (index 1) incorrectly.
        // Remapping: new X = old Y (along long edge), new Z = old X (normal axis)
        // gives roll (index 2) as the left/right tilt in landscape hold.
        SensorManager.remapCoordinateSystem(
            rotMatrix,
            SensorManager.AXIS_Y,
            SensorManager.AXIS_MINUS_X,
            remappedMatrix
        )
        SensorManager.getOrientation(remappedMatrix, orientation)

        // orientation[2] is roll in the remapped landscape frame —
        // left tilt = negative, right tilt = positive, so negate for
        // intuitive "tilt right = steer right" behaviour.
        val rawRoll = orientation[2] * 180f / PI.toFloat()  // positive = tilt right = steer right

        // Low-pass filter — heavy smoothing, greatly reduces sensor noise.
        smoothedRoll = if (firstSample) {
            firstSample = false
            rawRoll
        } else {
            smoothedRoll + LP_ALPHA * (rawRoll - smoothedRoll)
        }
        rollDeg = smoothedRoll

        // Calibration: collect samples after "Центр" is pressed.
        if (calibrating) {
            calibSamples.add(rawRoll)  // collect raw for averaging
            if (calibSamples.size >= CALIB_FRAMES) {
                centerRoll = calibSamples.average().toFloat()
                calibSamples.clear()
                calibrating = false
                smoothedRoll = centerRoll  // snap filter to new center
            }
        }

        val raw = (rollDeg - centerRoll) / maxSteerAngle
        steer = raw.coerceIn(-1f, 1f).let { v -> if (abs(v) < 0.015f) 0f else v }
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

    private fun roundRect(color: Int, radius: Float) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color); cornerRadius = radius
        }

    private fun tabBtn(label: String, selected: Boolean) = Button(this).apply {
        text = label
        setTextColor(if (selected) Color.parseColor("#7c6fff") else Color.parseColor("#6b7394"))
        background = roundRect(
            if (selected) Color.parseColor("#1a213a") else Color.parseColor("#1e2536"),
            dp(10).toFloat())
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#080b12"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        // BRAKE left
        brakeView = PedalView(this, false)
        root.addView(brakeView, lp(0, MATCH, 1f, endMargin = dp(6)))

        // CENTER
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundRect(Color.parseColor("#10141f"), dp(18).toFloat())
        }
        root.addView(center, lp(0, MATCH, 2.4f, endMargin = dp(6)))

        // status
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusText = tv("не подключено", 11f, Color.parseColor("#ffbe5f"))
        topRow.addView(statusText, lp(0, WRAP, 1f))
        center.addView(topRow, lp(MATCH, dp(24)))

        // mode toggle
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val usbBtn  = tabBtn("USB", true)
        val wifiBtn = tabBtn("Wi-Fi", false)
        val btBtn   = tabBtn("BT", false)
        usbBtn.setOnClickListener  { setConnMode(TransportKind.USB, usbBtn, wifiBtn, btBtn) }
        wifiBtn.setOnClickListener { setConnMode(TransportKind.WIFI, usbBtn, wifiBtn, btBtn) }
        btBtn.setOnClickListener   { setConnMode(TransportKind.BLUETOOTH, usbBtn, wifiBtn, btBtn) }
        modeRow.addView(usbBtn,  lp(0, dp(36), 1f, endMargin = dp(4)))
        modeRow.addView(wifiBtn, lp(0, dp(36), 1f, endMargin = dp(4)))
        modeRow.addView(btBtn,   lp(0, dp(36), 1f))
        center.addView(modeRow, lp(MATCH, dp(36)).also { it.setMargins(0, dp(4), 0, 0) })

        // IP input
        ipInput = EditText(this).apply {
            hint = "192.168.1.20"
            setText("192.168.1.20")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6b7394"))
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            background = roundRect(Color.parseColor("#0a0d15"), dp(10).toFloat())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        center.addView(ipInput, lp(MATCH, dp(42)).also { it.setMargins(0, dp(4), 0, 0) })

        // Bluetooth device picker row (visible only in BT mode)
        val btRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        btDeviceLabel = tv("Устройство не выбрано", 11f, Color.parseColor("#6b7394"))
        val pickBtDeviceBtn = Button(this).apply {
            text = "Выбрать"
            setTextColor(Color.parseColor("#7c6fff"))
            background = roundRect(Color.parseColor("#1e2536"), dp(10).toFloat())
            setOnClickListener { showBluetoothDevicePicker() }
        }
        btRow.addView(btDeviceLabel, lp(0, dp(36), 1f, endMargin = dp(4)))
        btRow.addView(pickBtDeviceBtn, lp(dp(96), dp(36)))
        center.addView(btRow, lp(MATCH, dp(36)).also { it.setMargins(0, dp(4), 0, 0) })
        this.btRowRef = btRow

        // connect + center buttons
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
                // Start calibration: collect CALIB_FRAMES sensor readings and
                // use their average as the new center. Shows "Калибровка..." for
                // ~300ms then confirms. This eliminates the 1-2° drift that comes
                // from using a single instantaneous sample as the zero point.
                if (!calibrating) {
                    calibSamples.clear()
                    calibrating = true
                    text = "⏳"
                    isEnabled = false
                    handler.postDelayed({
                        text = "Центр"
                        isEnabled = true
                        Toast.makeText(context, "Центр установлен", Toast.LENGTH_SHORT).show()
                    }, (CALIB_FRAMES * 12L) + 100L)
                }
            }
        }
        val settingsBtn = Button(this).apply {
            text = "⚙ Угол"
            setTextColor(Color.parseColor("#6b7394"))
            background = roundRect(Color.parseColor("#0d1117"), dp(10).toFloat())
            setOnClickListener { showSteeringSettings() }
        }
        btnRow.addView(connectBtn,  lp(0, dp(44), 1f, endMargin = dp(4)))
        btnRow.addView(centerBtn,   lp(0, dp(44), 1f, endMargin = dp(4)))
        btnRow.addView(settingsBtn, lp(0, dp(44), 1f))
        center.addView(btnRow, lp(MATCH, dp(44)).also { it.setMargins(0, dp(4), 0, 0) })

        // wheel
        wheelView = WheelView(this)
        center.addView(wheelView, lp(MATCH, 0, 1f))

        // steer text
        steerTv = tv("+0°", 22f, Color.parseColor("#7c6fff"), bold = true).apply {
            gravity = Gravity.CENTER
        }
        center.addView(steerTv, lp(MATCH, dp(34)))

        // hint
        val hint = tv("Держи как руль. Нажми Центр перед игрой.", 10f, Color.parseColor("#6b7394"))
        hint.gravity = Gravity.CENTER
        center.addView(hint, lp(MATCH, dp(24)))

        // GAS right
        gasView = PedalView(this, true)
        root.addView(gasView, lp(0, MATCH, 1f))

        val screen = FrameLayout(this)
        screen.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))

        buttonOverlay = FrameLayout(this)
        screen.addView(buttonOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        buildEditToolbar(screen)

        setContentView(screen)

        buttonOverlay.post {
            val loaded = layoutStore.load()
            if (loaded != null) {
                val (layouts, savedNextId) = loaded
                nextButtonId = savedNextId
                rebuildButtons(layouts)
            }
            val loadedKnobs = knobLayoutStore.load()
            if (loadedKnobs != null) {
                val (knobLayouts, savedNextAxisId) = loadedKnobs
                nextAxisId = savedNextAxisId
                rebuildKnobs(knobLayouts)
            }
            // No saved layout yet -> start with an empty overlay; user adds
            // buttons/knobs with the toolbar buttons.
        }
    }

    private fun buildEditToolbar(screen: FrameLayout) {
        // small always-visible gear toggle, top-left, doesn't block the view
        editToggleBtn = Button(this).apply {
            text = "⚙"
            textSize = 16f
            setTextColor(Color.parseColor("#c7cbe0"))
            background = roundRect(Color.argb(160, 22, 26, 40), dp(20).toFloat())
            setOnClickListener { setEditMode(!editMode) }
        }
        val toggleLp = FrameLayout.LayoutParams(dp(40), dp(40)).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dp(6), dp(6), 0, 0)
        }
        screen.addView(editToggleBtn, toggleLp)

        // toolbar shown only while editing: add + done
        editToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(220, 12, 14, 22))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            visibility = View.GONE
        }
        // Row 1: add buttons
        val addBtn = Button(this).apply {
            text = "+ Кнопку"
            setTextColor(Color.WHITE)
            background = roundRect(Color.parseColor("#5b4fe8"), dp(10).toFloat())
            setOnClickListener { addNewButton() }
        }
        editToolbar.addView(addBtn, lp(0, dp(34), 1f, endMargin = dp(4)))

        val removeBtn = Button(this).apply {
            text = "− Кнопку"
            setTextColor(Color.parseColor("#ff6060"))
            background = roundRect(Color.parseColor("#2a1020"), dp(10).toFloat())
            setOnClickListener { removeLastButton() }
        }
        editToolbar.addView(removeBtn, lp(0, dp(34), 1f, endMargin = dp(4)))

        val addKnobBtn = Button(this).apply {
            text = "+ Колёсико"
            setTextColor(Color.WHITE)
            background = roundRect(Color.parseColor("#228866"), dp(10).toFloat())
            setOnClickListener { addNewKnob() }
        }
        editToolbar.addView(addKnobBtn, lp(0, dp(34), 1f, endMargin = dp(4)))

        val removeKnobBtn = Button(this).apply {
            text = "− Колёсико"
            setTextColor(Color.parseColor("#ff6060"))
            background = roundRect(Color.parseColor("#1a2a1a"), dp(10).toFloat())
            setOnClickListener { removeLastKnob() }
        }
        editToolbar.addView(removeKnobBtn, lp(0, dp(34), 1f, endMargin = dp(4)))

        val hintLbl = tv("тяни • уголок=размер • тап=переим.", 8f, Color.parseColor("#6b7394"))
        editToolbar.addView(hintLbl, lp(0, dp(34), 0.8f, endMargin = dp(4)))
        val doneBtn = Button(this).apply {
            text = "Готово"
            setTextColor(Color.parseColor("#22dc82"))
            background = roundRect(Color.parseColor("#0e2a1e"), dp(10).toFloat())
            setOnClickListener { setEditMode(false) }
        }
        editToolbar.addView(doneBtn, lp(0, dp(34), 1f))

        val toolbarLp = FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP
        }
        screen.addView(editToolbar, toolbarLp)
    }

    private fun setEditMode(on: Boolean) {
        editMode = on
        customButtons.forEach { it.editMode = on }
        customKnobs.forEach { it.editMode = on }
        editToolbar.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun rebuildButtons(layouts: List<ButtonLayout>) {
        customButtons.forEach { buttonOverlay.removeView(it) }
        customButtons.clear()
        val pw = buttonOverlay.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val ph = buttonOverlay.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        for (bl in layouts) {
            val view = CustomButtonView(this, bl.id, bl.label,
                onMoved = { persistCurrentLayout() },
                onLongPressRemove = { v -> confirmRemove(v) },
                onTapRename = { v -> renameDialog(v) })
            view.editMode = editMode
            val params = FrameLayout.LayoutParams((bl.wFrac * pw).toInt(), (bl.hFrac * ph).toInt()).apply {
                leftMargin = (bl.xFrac * pw).toInt()
                topMargin  = (bl.yFrac * ph).toInt()
            }
            buttonOverlay.addView(view, params)
            customButtons.add(view)
        }
    }

    private fun confirmRemove(view: CustomButtonView) {
        AlertDialog.Builder(this)
            .setTitle("Удалить кнопку «${view.labelText}»?")
            .setPositiveButton("Удалить") { _, _ ->
                buttonOverlay.removeView(view)
                customButtons.remove(view)
                persistCurrentLayout()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun renameDialog(view: CustomButtonView) {
        val input = EditText(this).apply {
            setText(view.labelText)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6b7394"))
            hint = "Название кнопки"
            setSingleLine(true)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Название кнопки (${view.buttonId})")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newLabel = input.text.toString().trim()
                if (newLabel.isNotEmpty()) {
                    view.labelText = newLabel
                    view.invalidate()
                    persistCurrentLayout()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Adds a new free-form button in the center of the screen with a fresh,
     *  never-reused id ("btn_N"). No fixed catalogue, no limit on count. */
    private fun addNewButton() {
        val pw = buttonOverlay.width.toFloat()
        val ph = buttonOverlay.height.toFloat()
        val id = "btn_${nextButtonId}"
        val label = "BTN $nextButtonId"
        nextButtonId++

        val stagger = (customButtons.size % 5) * dp(16)
        val view = CustomButtonView(this, id, label,
            onMoved = { persistCurrentLayout() },
            onLongPressRemove = { v -> confirmRemove(v) },
            onTapRename = { v -> renameDialog(v) })
        view.editMode = editMode
        val w = dp(90); val h = dp(70)
        val params = FrameLayout.LayoutParams(w, h).apply {
            leftMargin = ((pw - w) / 2f).toInt() + stagger
            topMargin  = ((ph - h) / 2f).toInt() + stagger
        }
        buttonOverlay.addView(view, params)
        customButtons.add(view)
        persistCurrentLayout()
    }

    private fun persistCurrentLayout() {
        layoutStore.save(customButtons.map { it.toLayout() }, nextButtonId)
    }

    /** Removes the last added button — mirrors the long-press delete but
     *  accessible from the toolbar without needing to long-press on a small
     *  target. Shows a confirmation with the button's name so nothing gets
     *  deleted by accident. */
    private fun removeLastButton() {
        val last = customButtons.lastOrNull() ?: run {
            Toast.makeText(this, "Нет кнопок для удаления", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Удалить кнопку «${last.labelText}»?")
            .setPositiveButton("Удалить") { _, _ ->
                buttonOverlay.removeView(last)
                customButtons.remove(last)
                persistCurrentLayout()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Removes the last added knob — same pattern as removeLastButton. */
    private fun removeLastKnob() {
        val last = customKnobs.lastOrNull() ?: run {
            Toast.makeText(this, "Нет колёсиков для удаления", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Удалить колёсико «${last.labelText}»?")
            .setPositiveButton("Удалить") { _, _ ->
                buttonOverlay.removeView(last)
                customKnobs.remove(last)
                persistCurrentKnobLayout()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ─── Rotary knobs (ABS / TC / brake balance / ...) ─────────────────────
    // Mirrors the button management methods above exactly — same id scheme,
    // same edit-mode interactions, same persistence pattern.

    private fun rebuildKnobs(layouts: List<KnobLayout>) {
        customKnobs.forEach { buttonOverlay.removeView(it) }
        customKnobs.clear()
        val pw = buttonOverlay.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val ph = buttonOverlay.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        for (kl in layouts) {
            val view = RotaryKnobView(this, kl.id, kl.label, kl.value,
                onValueChanged = { persistCurrentKnobLayout() },
                onMoved = { persistCurrentKnobLayout() },
                onLongPressRemove = { v -> confirmRemoveKnob(v) },
                onTapRename = { v -> renameKnobDialog(v) })
            view.editMode = editMode
            val size = (kl.sizeFrac * pw).toInt()
            val params = FrameLayout.LayoutParams(size, size).apply {
                leftMargin = (kl.xFrac * pw).toInt()
                topMargin  = (kl.yFrac * ph).toInt()
            }
            buttonOverlay.addView(view, params)
            customKnobs.add(view)
        }
    }

    private fun confirmRemoveKnob(view: RotaryKnobView) {
        AlertDialog.Builder(this)
            .setTitle("Удалить колёсико «${view.labelText}»?")
            .setPositiveButton("Удалить") { _, _ ->
                buttonOverlay.removeView(view)
                customKnobs.remove(view)
                persistCurrentKnobLayout()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun renameKnobDialog(view: RotaryKnobView) {
        val input = EditText(this).apply {
            setText(view.labelText)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6b7394"))
            hint = "Название колёсика (например ABS)"
            setSingleLine(true)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Название колёсика (${view.axisId})")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newLabel = input.text.toString().trim()
                if (newLabel.isNotEmpty()) {
                    view.labelText = newLabel
                    view.invalidate()
                    persistCurrentKnobLayout()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Adds a new free-form rotary knob in the center of the screen with a
     *  fresh, never-reused id ("axis_N"). Same auto-naming/no-limit pattern
     *  as addNewButton(). */
    private fun addNewKnob() {
        val pw = buttonOverlay.width.toFloat()
        val ph = buttonOverlay.height.toFloat()
        val id = "axis_${nextAxisId}"
        val label = "AXIS $nextAxisId"
        nextAxisId++

        val stagger = (customKnobs.size % 5) * dp(16)
        val view = RotaryKnobView(this, id, label, 0.5f,
            onValueChanged = { persistCurrentKnobLayout() },
            onMoved = { persistCurrentKnobLayout() },
            onLongPressRemove = { v -> confirmRemoveKnob(v) },
            onTapRename = { v -> renameKnobDialog(v) })
        view.editMode = editMode
        val size = dp(96)
        val params = FrameLayout.LayoutParams(size, size).apply {
            leftMargin = ((pw - size) / 2f).toInt() + stagger
            topMargin  = ((ph - size) / 2f).toInt() + stagger
        }
        buttonOverlay.addView(view, params)
        customKnobs.add(view)
        persistCurrentKnobLayout()
    }

    private fun persistCurrentKnobLayout() {
        knobLayoutStore.save(customKnobs.map { it.toLayout() }, nextAxisId)
    }

    /** Small settings dialog for the steering angle — keeps the main screen
     *  clean. Opened via the "⚙ Угол" button. Persists the value to
     *  SharedPreferences so it survives app restarts. */
    private fun showSteeringSettings() {
        val prefs = getSharedPreferences("phonewheel_settings", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val label = TextView(this).apply {
            text = "Угол поворота: ${maxSteerAngle.toInt()}°"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        layout.addView(label)

        val seek = SeekBar(this).apply {
            max = 80                                     // range: 10°..90°
            progress = (maxSteerAngle - 10f).toInt().coerceIn(0, 80)
            setPadding(0, dp(12), 0, dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    maxSteerAngle = (10 + p).toFloat()
                    label.text = "Угол поворота: ${maxSteerAngle.toInt()}°"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    prefs.edit().putFloat("maxSteerAngle", maxSteerAngle).apply()
                }
            })
        }
        layout.addView(seek)

        val hint = TextView(this).apply {
            text = "Рекомендуется 30–50° для обычного вождения.\nМеньший угол — точнее, больший — реалистичнее."
            setTextColor(Color.parseColor("#6b7394"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        layout.addView(hint)

        AlertDialog.Builder(this)
            .setTitle("Настройки руля")
            .setView(layout)
            .setPositiveButton("Закрыть") { _, _ ->
                prefs.edit().putFloat("maxSteerAngle", maxSteerAngle).apply()
            }
            .show()
    }

    private fun setConnMode(kind: TransportKind, usbBtn: Button, wifiBtn: Button, btBtn: Button) {
        connMode = kind
        usbMode = kind == TransportKind.USB

        fun style(btn: Button, selected: Boolean) {
            btn.setTextColor(if (selected) Color.parseColor("#7c6fff") else Color.parseColor("#6b7394"))
            btn.background = roundRect(
                if (selected) Color.parseColor("#1a213a") else Color.parseColor("#1e2536"), dp(10).toFloat())
        }
        style(usbBtn, kind == TransportKind.USB)
        style(wifiBtn, kind == TransportKind.WIFI)
        style(btBtn, kind == TransportKind.BLUETOOTH)

        ipInput.visibility = if (kind == TransportKind.WIFI) View.VISIBLE else View.GONE
        btRowRef?.visibility = if (kind == TransportKind.BLUETOOTH) View.VISIBLE else View.GONE
    }

    /** Builds the concrete transport for whichever kind ConnectionManager
     *  was asked to switch to. USB and Wi-Fi are both WebSocketTransport —
     *  only the target URL differs. Bluetooth connects out to whichever
     *  device the user picked via showBluetoothDevicePicker(). */
    private fun buildTransport(kind: TransportKind): ITransport = when (kind) {
        TransportKind.USB -> WebSocketTransport(
            TransportKind.USB,
            "ws://127.0.0.1:27111/ws",
            helloPayload = ::buildHello
        )
        TransportKind.WIFI -> {
            val ip = ipInput.text.toString().trim().ifBlank { "127.0.0.1" }
            WebSocketTransport(TransportKind.WIFI, "ws://$ip:27111/ws", helloPayload = ::buildHello)
        }
        TransportKind.BLUETOOTH -> {
            val device = selectedBtDevice
                ?: throw IllegalStateException("Bluetooth device not selected")
            BluetoothTransport(this, device)
        }
    }

    private fun buildHello(): String = JSONObject()
        .put("type", "hello")
        .put("name", android.os.Build.MODEL)
        .put("mode", connMode.name.lowercase())
        .toString()

    private fun connect() {
        if (connMode == TransportKind.BLUETOOTH) {
            if (!ensureBluetoothPermissions()) return
            if (selectedBtDevice == null) {
                Toast.makeText(this, "Сначала выбери устройство", Toast.LENGTH_SHORT).show()
                showBluetoothDevicePicker()
                return
            }
        }
        statusText.text = "Подключение..."
        statusText.setTextColor(Color.parseColor("#ffbe5f"))
        connectionManager.switchTo(connMode) { k -> buildTransport(k) }
    }

    /** Returns true if Bluetooth permissions are already granted. Otherwise
     *  kicks off the runtime permission request dialog and returns false —
     *  the caller should re-attempt the action from onRequestPermissionsResult. */
    private fun ensureBluetoothPermissions(): Boolean {
        if (btManager.hasPermissions()) return true
        requestPermissions(btManager.permissionsToRequest(), PERMISSION_REQUEST_CODE)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            Toast.makeText(this, "Bluetooth разрешения получены", Toast.LENGTH_SHORT).show()
            showBluetoothDevicePicker()
        } else {
            Toast.makeText(this, "Без разрешений Bluetooth работать не будет", Toast.LENGTH_LONG).show()
        }
    }

    /** Shows a list of paired devices with a "Искать ещё" action that runs
     *  live discovery and appends newly found devices to the same list.
     *  Tapping a device just selects it (updates the label); the user still
     *  presses the existing "Подключить" button to actually connect — this
     *  keeps a single, consistent connect/disconnect entry point instead of
     *  connecting immediately on tap. */
    private fun showBluetoothDevicePicker() {
        if (!ensureBluetoothPermissions()) return
        if (!btManager.isSupported()) {
            Toast.makeText(this, "Bluetooth не поддерживается на этом устройстве", Toast.LENGTH_LONG).show()
            return
        }
        if (!btManager.isEnabled()) {
            Toast.makeText(this, "Включи Bluetooth в настройках телефона", Toast.LENGTH_LONG).show()
            return
        }

        val devices = btManager.pairedDevices().toMutableList()
        val labels = devices.map { "${it.name}  (${it.address})" }.toMutableList()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Bluetooth-устройства")
            .setItems(labels.toTypedArray()) { _, index ->
                selectedBtDevice = devices[index]
                btDeviceLabel.text = devices[index].name
                btDeviceLabel.setTextColor(Color.parseColor("#22dc82"))
            }
            .setNegativeButton("Закрыть", null)
            .setNeutralButton("Искать ещё") { _, _ -> /* re-opened below via discovery */ }
            .create()

        // Live discovery appends to the already-shown list; since AlertDialog
        // with setItems doesn't expose its adapter easily after creation, we
        // keep discovery simple: collect found devices and let the user
        // reopen the picker (now including them in pairedDevices() results
        // only applies to bonded devices, so newly discovered-but-unpaired
        // devices are surfaced via a toast prompting the user to pair first
        // — Android requires pairing before RFCOMM connect works reliably
        // for most head units / PCs anyway).
        btManager.setOnDeviceFound { found ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Найдено: ${found.name}. Сопряди его в настройках Bluetooth, затем выбери здесь.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        dialog.show()
        // Trigger a discovery pass in the background; any new devices show
        // up via the toast above so the user knows to go pair them.
        btManager.startDiscovery()
    }

    private fun disconnect() {
        connected = false
        connectionManager.disconnect()
        statusText.text = "Отключено"
        statusText.setTextColor(Color.parseColor("#ffbe5f"))
        connectBtn.text = "Подключить"
        connectBtn.setTextColor(Color.parseColor("#7c6fff"))
        connectBtn.background = roundRect(Color.parseColor("#1f2a4a"), dp(10).toFloat())
    }

    /** Wires ConnectionManager's transport-agnostic callbacks to the same UI
     *  updates connect()'s old WebSocketListener used to do inline — now
     *  shared by every transport instead of duplicated per-transport. */
    private fun setupConnectionCallbacks() {
        connectionManager.setOnStateChanged { state ->
            runOnUiThread {
                when (state) {
                    ConnectionState.CONNECTED -> {
                        connected = true
                        statusText.text = "Подключено"
                        statusText.setTextColor(Color.parseColor("#22dc82"))
                        connectBtn.text = "Отключить"
                        connectBtn.setTextColor(Color.parseColor("#22dc82"))
                        connectBtn.background = roundRect(Color.parseColor("#0e2a1e"), dp(10).toFloat())
                    }
                    ConnectionState.ERROR -> {
                        connected = false
                        statusText.text = "Ошибка соединения"
                        statusText.setTextColor(Color.parseColor("#ff6060"))
                        connectBtn.text = "Подключить"
                        connectBtn.setTextColor(Color.parseColor("#7c6fff"))
                        connectBtn.background = roundRect(Color.parseColor("#1f2a4a"), dp(10).toFloat())
                    }
                    ConnectionState.DISCONNECTED -> {
                        connected = false
                        statusText.text = "Отключено"
                        statusText.setTextColor(Color.parseColor("#ffbe5f"))
                        connectBtn.text = "Подключить"
                        connectBtn.setTextColor(Color.parseColor("#7c6fff"))
                        connectBtn.background = roundRect(Color.parseColor("#1f2a4a"), dp(10).toFloat())
                    }
                    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                        statusText.text = "Подключение..."
                        statusText.setTextColor(Color.parseColor("#ffbe5f"))
                    }
                }
            }
        }
        connectionManager.setOnLog { _, _ -> /* could surface to a log panel later */ }
    }


    private fun startSendLoop() {
        sendHandler.post(object : Runnable {
            override fun run() {
                if (connected) {
                    try {
                        val now = System.currentTimeMillis()

                        // Rebuild button/label maps only when count changes — avoids
                        // creating new JSONObject + N string keys every 10ms.
                        val btnCount = customButtons.size
                        if (btnCount != lastBtnCount) {
                            buttonsJson.keys().forEach { buttonsJson.remove(it) }
                            labelsJson.keys().forEach  { labelsJson.remove(it) }
                            lastBtnCount = btnCount
                        }
                        for (cb in customButtons) {
                            buttonsJson.put(cb.buttonId, cb.pressed)
                            labelsJson.put(cb.buttonId, cb.labelText)
                        }

                        // Same reuse pattern for rotary-knob axes.
                        val knobCount = customKnobs.size
                        if (knobCount != lastKnobCount) {
                            axesJson.keys().forEach { axesJson.remove(it) }
                            axisLabelsJson.keys().forEach { axisLabelsJson.remove(it) }
                            lastKnobCount = knobCount
                        }
                        for (kn in customKnobs) {
                            axesJson.put(kn.axisId, kn.value.toDouble())
                            axisLabelsJson.put(kn.axisId, kn.labelText)
                        }

                        // Reuse stateJson — update fields in place.
                        stateJson.put("type",        "state")
                        stateJson.put("seq",         seq++)
                        stateJson.put("ts",          System.currentTimeMillis())
                        stateJson.put("steer",       steer.toDouble())
                        stateJson.put("throttle",    0.5 + gasView.value.toDouble() / 200.0)
                        stateJson.put("brake",       0.5 + brakeView.value.toDouble() / 200.0)
                        stateJson.put("buttons",     buttonsJson)
                        stateJson.put("buttonLabels",labelsJson)
                        stateJson.put("axes",        axesJson)
                        stateJson.put("axisLabels",  axisLabelsJson)

                        connectionManager.send(stateJson.toString())
                        lastSendAt = now
                    } catch (_: Exception) {}
                } else {
                    // Reset seq counter on disconnect so PC can detect gaps.
                    if (lastSendAt > 0L) {
                        seq = 0L
                        lastSendAt = 0L
                        lastBtnCount = -1
                        lastKnobCount = -1
                    }
                }
                sendHandler.postDelayed(this, SEND_INTERVAL_MS)
            }
        })

        // Heartbeat runnable — if no state packet was sent in the last
        // HEARTBEAT_INTERVAL_MS (e.g. phone is completely still), send a
        // minimal keep-alive ping so the PC watchdog doesn't time out.
        sendHandler.post(object : Runnable {
            override fun run() {
                if (connected) {
                    val now = System.currentTimeMillis()
                    if (now - lastSendAt >= HEARTBEAT_INTERVAL_MS) {
                        try {
                            connectionManager.send(
                                org.json.JSONObject()
                                    .put("type", "heartbeat")
                                    .put("ts",   now)
                                    .toString()
                            )
                            lastSendAt = now
                        } catch (_: Exception) {}
                    }
                }
                sendHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        })
    }

    private fun startUiLoop() {
        handler.post(object : Runnable {
            override fun run() {
                val deg  = (steer * maxSteerAngle).toInt()
                val sign = if (deg >= 0) "+" else ""
                steerTv.text      = "$sign${deg}°"
                wheelView.steer   = steer
                wheelView.gas     = gasView.value / 100f
                wheelView.brake   = brakeView.value / 100f
                wheelView.invalidate()
                handler.postDelayed(this, 40L)
            }
        })
    }

    companion object {
        private const val SEND_INTERVAL_MS      = 10L   // 100 Hz target
        private const val HEARTBEAT_INTERVAL_MS = 500L  // keepalive every 500ms
    }
}
