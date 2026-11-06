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
    /** Half of lock-to-lock range: the wheel angle that steer = ±1 means. */
    var halfDeg: Float = 180f
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
        canvas.rotate(steer * halfDeg)
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
        val deg = (steer * halfDeg).toInt()
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
    private val rotMatrix = FloatArray(9)

    // ── steering state ──────────────────────────────────────────────────
    // The wheel angle is the rotation of the phone around the axis that
    // goes through the screen (like a real steering wheel). It is computed
    // from the gravity direction in device coordinates with atan2, which is
    // continuous over a full circle, and then "unwrapped": every new sample
    // adds only the small delta from the previous one. So turning past 90°
    // keeps going in the same direction (up to 180°, 450° … whatever the
    // lock-to-lock setting allows) instead of folding back.
    private var hasPrevRaw   = false
    private var prevRawDeg   = 0f
    private var wheelDeg     = 0f   // unwrapped angle relative to center, clamped to ±halfLock
    @Volatile private var steer = 0f
    private var lockToLockDeg = 360f
    private var invertSteer   = false
    private var smoothing     = 0.3f   // 0 = none, 0.9 = heavy

    private var connMode: TransportKind = TransportKind.USB
    private var selectedBtDevice: BtDevice? = null
    @Volatile private var connected = false
    private var wantConnected = false
    private var seq = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val sendThread = HandlerThread("pw-send", Process.THREAD_PRIORITY_URGENT_DISPLAY)
    private val sendHandler by lazy { sendThread.start(); Handler(sendThread.looper) }

    private val stateJson   = JSONObject()
    private var buttonsJson = JSONObject()
    private var labelsJson  = JSONObject()
    @Volatile private var layoutVersion = 0
    private var sentLayoutVersion = -1
    @Volatile private var lastSendAt = 0L
    private val connectionManager = ConnectionManager()
    private val btManager by lazy { BluetoothManager(this) }
    private val PERMISSION_REQUEST_CODE = 4201
    private val prefs by lazy { getSharedPreferences("phonewheel_settings", Context.MODE_PRIVATE) }

    private lateinit var gasView:       PedalView
    private lateinit var brakeView:     PedalView
    private lateinit var wheelView:     WheelView
    private lateinit var statusText:    TextView
    private lateinit var connectBtn:    Button
    private lateinit var ipInput:       EditText
    private lateinit var btDeviceLabel: TextView
    private var btRowRef: LinearLayout? = null
    private lateinit var steerTv:       TextView

    private lateinit var layoutStore:   LayoutStore
    private lateinit var knobStore:     KnobLayoutStore
    private lateinit var buttonOverlay: FrameLayout
    private lateinit var editToolbar:   LinearLayout
    // Read by the send thread → thread-safe lists.
    private val customButtons = java.util.concurrent.CopyOnWriteArrayList<CustomButtonView>()
    private val knobs         = java.util.concurrent.CopyOnWriteArrayList<StepKnobView>()
    private var nextButtonId = 1
    private var nextKnobId   = 1
    private var editMode = false
    private var settingsDialog: android.app.Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        layoutStore = LayoutStore(this)
        knobStore   = KnobLayoutStore(this)
        lockToLockDeg = prefs.getFloat("lockDeg", 360f)
        invertSteer   = prefs.getBoolean("invert", false)
        smoothing     = prefs.getFloat("smooth", 0.3f)
        setupSensors()
        buildUi()
        setupConnectionCallbacks()
        startSendLoop()
        startUiLoop()
    }

    override fun onResume() {
        super.onResume()
        hasPrevRaw = false
        rotSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        wantConnected = false
        handler.removeCallbacksAndMessages(null)
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
        // Third row of R = world "up" expressed in device coordinates.
        val gx = rotMatrix[6]
        val gy = rotMatrix[7]
        // Phone lying almost flat: rotation around the screen axis is undefined.
        if (hypot(gx, gy) < 0.2f) return
        val raw = Math.toDegrees(atan2(gy, gx).toDouble()).toFloat()
        if (!hasPrevRaw) { prevRawDeg = raw; hasPrevRaw = true; return }
        var d = raw - prevRawDeg
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        prevRawDeg = raw

        val half = lockToLockDeg / 2f
        // Clamp like a real steering lock: past the lock nothing happens,
        // turning back responds immediately.
        wheelDeg = (wheelDeg + d).coerceIn(-half, half)
        var target = wheelDeg / half
        if (invertSteer) target = -target
        steer = (steer + (target - steer) * (1f - smoothing)).coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun centerWheel() {
        wheelDeg = 0f
        steer = 0f
        Toast.makeText(this, "Центр установлен", Toast.LENGTH_SHORT).show()
    }

    // ── small UI helpers ────────────────────────────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun lp(w: Int, h: Int, weight: Float = 0f, endMargin: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).also { if (endMargin > 0) it.setMargins(0, 0, endMargin, 0) }

    private fun tv(text: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = sizeSp; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun roundRect(color: Int, radius: Float) =
        android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius }

    private fun btn(label: String, fg: String = "#ffffff", bg: String = "#1e2536", onClick: () -> Unit) =
        Button(this).apply {
            text = label; isAllCaps = false
            setTextColor(Color.parseColor(fg))
            background = roundRect(Color.parseColor(bg), dp(10).toFloat())
            setOnClickListener { onClick() }
        }

    private fun tabBtn(label: String, selected: Boolean) = Button(this).apply {
        text = label
        setTextColor(if (selected) Color.parseColor("#7c6fff") else Color.parseColor("#6b7394"))
        background = roundRect(
            if (selected) Color.parseColor("#1a213a") else Color.parseColor("#1e2536"), dp(10).toFloat())
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#080b12"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        brakeView = PedalView(this, false)
        root.addView(brakeView, lp(0, MATCH, 1f, endMargin = dp(6)))

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundRect(Color.parseColor("#10141f"), dp(18).toFloat())
        }
        root.addView(center, lp(0, MATCH, 2.4f, endMargin = dp(6)))

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(View(this), lp(dp(44), dp(24)))  // room for the ⚙ button
        statusText = tv("не подключено", 11f, Color.parseColor("#ffbe5f"))
        topRow.addView(statusText, lp(0, WRAP, 1f))
        center.addView(topRow, lp(MATCH, dp(24)))

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

        ipInput = EditText(this).apply {
            hint = "192.168.1.20"
            setText(prefs.getString("ip", "192.168.1.20"))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6b7394"))
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            background = roundRect(Color.parseColor("#0a0d15"), dp(10).toFloat())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        center.addView(ipInput, lp(MATCH, dp(42)).also { it.setMargins(0, dp(4), 0, 0) })

        val btRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        btDeviceLabel = tv("Устройство не выбрано", 11f, Color.parseColor("#6b7394"))
        btRow.addView(btDeviceLabel, lp(0, dp(36), 1f, endMargin = dp(4)))
        btRow.addView(btn("Выбрать", "#7c6fff") { showBluetoothDevicePicker() }, lp(dp(96), dp(36)))
        center.addView(btRow, lp(MATCH, dp(36)).also { it.setMargins(0, dp(4), 0, 0) })
        btRowRef = btRow
        prefs.getString("btAddr", null)?.let { addr ->
            selectedBtDevice = BtDevice(prefs.getString("btName", addr) ?: addr, addr, true)
            btDeviceLabel.text = selectedBtDevice!!.name
            btDeviceLabel.setTextColor(Color.parseColor("#22dc82"))
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectBtn = btn("Подключить", "#7c6fff", "#1f2a4a") { if (wantConnected) disconnect() else connect() }
        btnRow.addView(connectBtn, lp(0, dp(44), 1f, endMargin = dp(4)))
        btnRow.addView(btn("Центр") { centerWheel() }, lp(0, dp(44), 1f))
        center.addView(btnRow, lp(MATCH, dp(44)).also { it.setMargins(0, dp(4), 0, 0) })

        wheelView = WheelView(this)
        center.addView(wheelView, lp(MATCH, 0, 1f))

        steerTv = tv("+0°", 22f, Color.parseColor("#7c6fff"), bold = true).apply { gravity = Gravity.CENTER }
        center.addView(steerTv, lp(MATCH, dp(34)))

        gasView = PedalView(this, true)
        root.addView(gasView, lp(0, MATCH, 1f))

        val screen = FrameLayout(this)
        screen.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        buttonOverlay = FrameLayout(this)
        screen.addView(buttonOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        buildEditToolbar(screen)
        setContentView(screen)

        buttonOverlay.post {
            layoutStore.load()?.let { (layouts, savedNextId) ->
                nextButtonId = savedNextId
                rebuildButtons(layouts)
            }
            val (knobLayouts, nk) = knobStore.load()
            nextKnobId = nk
            knobLayouts.forEach { addKnobView(it) }
            layoutVersion++
        }
    }

    private fun buildEditToolbar(screen: FrameLayout) {
        val gear = Button(this).apply {
            text = "⚙"
            textSize = 16f
            setTextColor(Color.parseColor("#c7cbe0"))
            background = roundRect(Color.argb(160, 22, 26, 40), dp(20).toFloat())
            setOnClickListener { if (editMode) setEditMode(false) else openSettings() }
        }
        screen.addView(gear, FrameLayout.LayoutParams(dp(40), dp(40)).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dp(10), dp(10), 0, 0)
        })

        editToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(220, 12, 14, 22))
            setPadding(dp(56), dp(6), dp(8), dp(6))
            visibility = View.GONE
        }
        editToolbar.addView(tv("Расстановка: тяни — переместить • уголок — размер • тап — переименовать • удерживай — удалить",
            10f, Color.parseColor("#c7cbe0")), lp(0, dp(34), 1f, endMargin = dp(4)))
        editToolbar.addView(btn("Готово", "#22dc82", "#0e2a1e") { setEditMode(false) }, lp(dp(110), dp(34)))
        screen.addView(editToolbar, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.TOP })
    }

    private fun setEditMode(on: Boolean) {
        editMode = on
        customButtons.forEach { it.editMode = on }
        knobs.forEach { it.editMode = on }
        editToolbar.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) persistCurrentLayout()
    }

    // ── settings window ─────────────────────────────────────────────────
    private fun openSettings() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        settingsDialog = dialog
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#080b12")) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(20))
        }
        scroll.addView(body)
        dialog.setContentView(scroll)
        dialog.setOnDismissListener { settingsDialog = null }
        fillSettings(body)
        dialog.show()
    }

    private fun refreshSettings() {
        val d = settingsDialog ?: return
        val body = (d.findViewById<View>(android.R.id.content) as? ViewGroup)
            ?.let { (it.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout } ?: return
        fillSettings(body)
    }

    private fun section(body: LinearLayout, title: String) {
        body.addView(tv(title, 15f, Color.parseColor("#7c6fff"), bold = true),
            lp(MATCH, WRAP).also { it.setMargins(0, dp(16), 0, dp(6)) })
    }

    @SuppressLint("SetTextI18n")
    private fun fillSettings(body: LinearLayout) {
        body.removeAllViews()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(tv("Настройки", 20f, Color.WHITE, bold = true), lp(0, WRAP, 1f))
        header.addView(btn("Закрыть", "#22dc82", "#0e2a1e") { settingsDialog?.dismiss() }, lp(dp(120), dp(40)))
        body.addView(header)

        // ── steering ──
        section(body, "Руль")
        val lockLbl = tv("", 13f, Color.parseColor("#c7cbe0"))
        fun lockText() = "Угол поворота: ${lockToLockDeg.toInt()}° (±${(lockToLockDeg / 2).toInt()}°)"
        lockLbl.text = lockText()
        body.addView(lockLbl)
        body.addView(SeekBar(this).apply {
            max = (1080 - 90) / 10
            progress = ((lockToLockDeg - 90) / 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    lockToLockDeg = 90f + p * 10f
                    lockLbl.text = lockText()
                    prefs.edit().putFloat("lockDeg", lockToLockDeg).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }, lp(MATCH, dp(40)))
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (deg in listOf(180, 360, 540, 900)) {
            presets.addView(btn("$deg°") {
                lockToLockDeg = deg.toFloat()
                prefs.edit().putFloat("lockDeg", lockToLockDeg).apply()
                refreshSettings()
            }, lp(0, dp(38), 1f, endMargin = dp(6)))
        }
        body.addView(presets)
        body.addView(tv("Угол — как у настоящего руля: 360° = до 180° в каждую сторону. " +
            "Телефон можно крутить дальше 90° — направление запоминается.", 11f, Color.parseColor("#6b7394")),
            lp(MATCH, WRAP).also { it.setMargins(0, dp(4), 0, 0) })

        val smoothLbl = tv("Сглаживание: ${(smoothing * 100).toInt()}%", 13f, Color.parseColor("#c7cbe0"))
        body.addView(smoothLbl, lp(MATCH, WRAP).also { it.setMargins(0, dp(10), 0, 0) })
        body.addView(SeekBar(this).apply {
            max = 90; progress = (smoothing * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    smoothing = p / 100f
                    smoothLbl.text = "Сглаживание: $p%"
                    prefs.edit().putFloat("smooth", smoothing).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }, lp(MATCH, dp(40)))
        body.addView(CheckBox(this).apply {
            text = "Инвертировать направление руля"
            setTextColor(Color.parseColor("#c7cbe0"))
            isChecked = invertSteer
            setOnCheckedChangeListener { _, c -> invertSteer = c; prefs.edit().putBoolean("invert", c).apply() }
        })
        body.addView(btn("Установить центр сейчас") { centerWheel() }, lp(MATCH, dp(42)))

        // ── buttons ──
        section(body, "Кнопки (${customButtons.size})")
        for (b in customButtons) {
            body.addView(itemRow(b.labelText, b.buttonId,
                onRename = { renameDialog(b.labelText) { n -> b.labelText = n; b.invalidate(); persistCurrentLayout(); refreshSettings() } },
                onRemove = { removeButton(b); refreshSettings() }))
        }
        body.addView(btn("+ Добавить кнопку", bg = "#5b4fe8") { addNewButton(); refreshSettings() }, lp(MATCH, dp(42)))

        // ── knobs ──
        section(body, "Колёсики TC / ABS / MAP (${knobs.size})")
        body.addView(tv("Крути колёсико пальцем или тапай по левой (−) / правой (+) половине. " +
            "Каждый щелчок — нажатие кнопки «−» или «+»: в игре назначь их на «TC −/+», «ABS −/+», «MAP −/+».",
            11f, Color.parseColor("#6b7394")))
        for (k in knobs) {
            body.addView(itemRow(k.labelText, "${k.minusId} / ${k.plusId}",
                onRename = { renameDialog(k.labelText) { n -> k.labelText = n; k.invalidate(); persistCurrentLayout(); refreshSettings() } },
                onRemove = { removeKnob(k); refreshSettings() }))
        }
        val knobRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (name in listOf("TC", "ABS", "MAP", "BB")) {
            knobRow.addView(btn("+ $name", bg = "#5b4fe8") { addNewKnob(name); refreshSettings() },
                lp(0, dp(42), 1f, endMargin = dp(6)))
        }
        body.addView(knobRow, lp(MATCH, WRAP).also { it.setMargins(0, dp(6), 0, 0) })

        section(body, "Расположение")
        body.addView(btn("Расставить кнопки и колёсики на экране", "#7c6fff") {
            settingsDialog?.dismiss(); setEditMode(true)
        }, lp(MATCH, dp(42)))
    }

    private fun itemRow(title: String, sub: String, onRename: () -> Unit, onRemove: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(Color.parseColor("#10141f"), dp(10).toFloat())
            setPadding(dp(12), dp(4), dp(6), dp(4))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(title, 14f, Color.WHITE, bold = true))
        texts.addView(tv(sub, 10f, Color.parseColor("#6b7394")))
        row.addView(texts, lp(0, WRAP, 1f))
        row.addView(btn("Имя", "#7c6fff") { onRename() }, lp(dp(80), dp(38), endMargin = dp(6)))
        row.addView(btn("Удалить", "#ff6060", "#2a0e0e") { onRemove() }, lp(dp(96), dp(38)))
        return row.also { it.layoutParams = lp(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(6)) } }
    }

    private fun renameDialog(current: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply { setText(current); setSingleLine(true); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle("Название")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onSave)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ── buttons & knobs on screen ───────────────────────────────────────
    private fun newButtonView(id: String, label: String) = CustomButtonView(this, id, label,
        onMoved = { persistCurrentLayout() },
        onLongPressRemove = { v -> confirmRemove(v.labelText) { removeButton(v) } },
        onTapRename = { v -> renameDialog(v.labelText) { n -> v.labelText = n; v.invalidate(); persistCurrentLayout() } })

    private fun rebuildButtons(layouts: List<ButtonLayout>) {
        customButtons.forEach { buttonOverlay.removeView(it) }
        customButtons.clear()
        val pw = buttonOverlay.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val ph = buttonOverlay.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        for (bl in layouts) {
            val view = newButtonView(bl.id, bl.label)
            view.editMode = editMode
            buttonOverlay.addView(view, FrameLayout.LayoutParams((bl.wFrac * pw).toInt(), (bl.hFrac * ph).toInt()).apply {
                leftMargin = (bl.xFrac * pw).toInt()
                topMargin  = (bl.yFrac * ph).toInt()
            })
            customButtons.add(view)
        }
        layoutVersion++
    }

    private fun confirmRemove(name: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Удалить «$name»?")
            .setPositiveButton("Удалить") { _, _ -> onYes() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun removeButton(v: CustomButtonView) {
        buttonOverlay.removeView(v)
        customButtons.remove(v)
        layoutVersion++
        persistCurrentLayout()
    }

    private fun addNewButton() {
        val id = "btn_${nextButtonId}"
        val label = "BTN $nextButtonId"
        nextButtonId++
        val pw = buttonOverlay.width; val ph = buttonOverlay.height
        val stagger = (customButtons.size % 5) * dp(16)
        val view = newButtonView(id, label)
        view.editMode = editMode
        val w = dp(90); val h = dp(70)
        buttonOverlay.addView(view, FrameLayout.LayoutParams(w, h).apply {
            leftMargin = (pw - w) / 2 + stagger
            topMargin  = (ph - h) / 2 + stagger
        })
        customButtons.add(view)
        layoutVersion++
        persistCurrentLayout()
    }

    private fun addKnobView(k: KnobLayout) {
        val pw = buttonOverlay.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val ph = buttonOverlay.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val view = StepKnobView(this, k.id, k.label, k.level,
            onChanged = { persistCurrentLayout() },
            onLongPressRemove = { v -> confirmRemove(v.labelText) { removeKnob(v) } },
            onTapRename = { v -> renameDialog(v.labelText) { n -> v.labelText = n; v.invalidate(); persistCurrentLayout() } })
        view.editMode = editMode
        val size = (k.sizeFrac * pw).toInt().coerceAtLeast(dp(64))
        buttonOverlay.addView(view, FrameLayout.LayoutParams(size, size).apply {
            leftMargin = (k.xFrac * pw).toInt().coerceIn(0, (pw - size).coerceAtLeast(0))
            topMargin  = (k.yFrac * ph).toInt().coerceIn(0, (ph - size).coerceAtLeast(0))
        })
        knobs.add(view)
    }

    private fun addNewKnob(name: String) {
        val id = "knob_${nextKnobId++}"
        val pw = buttonOverlay.width.toFloat().coerceAtLeast(1f)
        val ph = buttonOverlay.height.toFloat().coerceAtLeast(1f)
        val size = dp(110) / pw
        val stagger = (knobs.size % 4) * 0.12f
        addKnobView(KnobLayout(id, 0.22f + stagger, 0.55f - dp(55) / ph, size, name))
        layoutVersion++
        persistCurrentLayout()
    }

    private fun removeKnob(v: StepKnobView) {
        buttonOverlay.removeView(v)
        knobs.remove(v)
        layoutVersion++
        persistCurrentLayout()
    }

    private fun persistCurrentLayout() {
        layoutStore.save(customButtons.map { it.toLayout() }, nextButtonId)
        knobStore.save(knobs.map { it.toLayout() }, nextKnobId)
    }

    // ── connection ──────────────────────────────────────────────────────
    private fun setConnMode(kind: TransportKind, usbBtn: Button, wifiBtn: Button, btBtn: Button) {
        if (wantConnected) disconnect()
        connMode = kind
        fun style(b: Button, selected: Boolean) {
            b.setTextColor(if (selected) Color.parseColor("#7c6fff") else Color.parseColor("#6b7394"))
            b.background = roundRect(
                if (selected) Color.parseColor("#1a213a") else Color.parseColor("#1e2536"), dp(10).toFloat())
        }
        style(usbBtn, kind == TransportKind.USB)
        style(wifiBtn, kind == TransportKind.WIFI)
        style(btBtn, kind == TransportKind.BLUETOOTH)
        ipInput.visibility = if (kind == TransportKind.WIFI) View.VISIBLE else View.GONE
        btRowRef?.visibility = if (kind == TransportKind.BLUETOOTH) View.VISIBLE else View.GONE
    }

    private fun buildTransport(kind: TransportKind): ITransport = when (kind) {
        // Trailing slash matters: the PC's HttpListener is registered on "/ws/".
        TransportKind.USB -> WebSocketTransport(TransportKind.USB, "ws://127.0.0.1:27111/ws/", ::buildHello)
        TransportKind.WIFI -> {
            val ip = ipInput.text.toString().trim()
                .removePrefix("ws://").removePrefix("http://").substringBefore("/").substringBefore(":")
                .ifBlank { "127.0.0.1" }
            WebSocketTransport(TransportKind.WIFI, "ws://$ip:27111/ws/", ::buildHello)
        }
        TransportKind.BLUETOOTH -> BluetoothTransport(this,
            selectedBtDevice ?: throw IllegalStateException("Bluetooth device not selected"))
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
                Toast.makeText(this, "Сначала выбери ПК в списке Bluetooth", Toast.LENGTH_SHORT).show()
                showBluetoothDevicePicker()
                return
            }
        }
        if (connMode == TransportKind.WIFI) {
            prefs.edit().putString("ip", ipInput.text.toString().trim()).apply()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(ipInput.windowToken, 0)
        }
        wantConnected = true
        connectBtn.text = "Отменить"
        doConnect()
    }

    private fun doConnect() {
        if (!wantConnected) return
        setStatus("Подключение...", "#ffbe5f")
        try {
            connectionManager.switchTo(connMode) { k -> buildTransport(k) }
        } catch (e: Exception) {
            setStatus("Ошибка: ${e.message}", "#ff6060")
            scheduleReconnect()
        }
    }

    private val reconnectRunnable = Runnable { doConnect() }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        if (wantConnected) handler.postDelayed(reconnectRunnable, 2000)
    }

    private fun ensureBluetoothPermissions(): Boolean {
        if (btManager.hasPermissions()) return true
        requestPermissions(btManager.permissionsToRequest(), PERMISSION_REQUEST_CODE)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            showBluetoothDevicePicker()
        } else {
            Toast.makeText(this, "Без разрешений Bluetooth работать не будет", Toast.LENGTH_LONG).show()
        }
    }

    /** Lists paired devices. The PC must be paired in Android's Bluetooth
     *  settings first — RFCOMM to an unpaired Windows PC is refused. */
    private fun showBluetoothDevicePicker() {
        if (!ensureBluetoothPermissions()) return
        if (!btManager.isSupported()) {
            Toast.makeText(this, "Bluetooth не поддерживается на этом устройстве", Toast.LENGTH_LONG).show(); return
        }
        if (!btManager.isEnabled()) {
            Toast.makeText(this, "Включи Bluetooth в настройках телефона", Toast.LENGTH_LONG).show(); return
        }
        val devices = btManager.pairedDevices()
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Нет сопряжённых устройств")
                .setMessage("Открой настройки Bluetooth на телефоне, найди свой ПК и выполни сопряжение. Потом вернись сюда.")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                .setNegativeButton("Закрыть", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Выбери ПК")
            .setItems(devices.map { "${it.name}  (${it.address})" }.toTypedArray()) { _, i ->
                selectedBtDevice = devices[i]
                prefs.edit().putString("btAddr", devices[i].address).putString("btName", devices[i].name).apply()
                btDeviceLabel.text = devices[i].name
                btDeviceLabel.setTextColor(Color.parseColor("#22dc82"))
            }
            .setNeutralButton("Сопрячь новый") { _, _ ->
                startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun setStatus(text: String, color: String) {
        statusText.text = text
        statusText.setTextColor(Color.parseColor(color))
    }

    private fun styleConnectBtn(isConnected: Boolean) {
        connectBtn.text = if (isConnected) "Отключить" else if (wantConnected) "Отменить" else "Подключить"
        connectBtn.setTextColor(Color.parseColor(if (isConnected) "#22dc82" else "#7c6fff"))
        connectBtn.background = roundRect(Color.parseColor(if (isConnected) "#0e2a1e" else "#1f2a4a"), dp(10).toFloat())
    }

    private fun disconnect() {
        wantConnected = false
        handler.removeCallbacks(reconnectRunnable)
        connected = false
        connectionManager.disconnect()
        setStatus("Отключено", "#ffbe5f")
        styleConnectBtn(false)
    }

    private fun setupConnectionCallbacks() {
        connectionManager.setOnStateChanged { state ->
            runOnUiThread {
                when (state) {
                    ConnectionState.CONNECTED -> {
                        connected = true
                        setStatus("Подключено", "#22dc82")
                        styleConnectBtn(true)
                    }
                    ConnectionState.ERROR, ConnectionState.DISCONNECTED, ConnectionState.RECONNECTING -> {
                        connected = false
                        if (wantConnected) {
                            setStatus(lastError?.let { "Нет связи: $it — повтор..." } ?: "Нет связи — повтор...", "#ff6060")
                            scheduleReconnect()
                        } else {
                            setStatus("Отключено", "#ffbe5f")
                        }
                        styleConnectBtn(false)
                    }
                    ConnectionState.CONNECTING -> setStatus("Подключение...", "#ffbe5f")
                }
            }
        }
        connectionManager.setOnLog { msg, lvl -> if (lvl == "err") lastError = msg.take(60) }
    }

    @Volatile private var lastError: String? = null

    private fun startSendLoop() {
        sendHandler.post(object : Runnable {
            override fun run() {
                if (connected) {
                    try {
                        if (sentLayoutVersion != layoutVersion) {
                            buttonsJson = JSONObject()
                            labelsJson = JSONObject()
                            sentLayoutVersion = layoutVersion
                        }
                        for (cb in customButtons) {
                            buttonsJson.put(cb.buttonId, cb.pressed)
                            labelsJson.put(cb.buttonId, cb.labelText)
                        }
                        for (k in knobs) {
                            buttonsJson.put(k.minusId, k.minusPressed)
                            buttonsJson.put(k.plusId, k.plusPressed)
                            labelsJson.put(k.minusId, "${k.labelText} −")
                            labelsJson.put(k.plusId, "${k.labelText} +")
                        }
                        val now = System.currentTimeMillis()
                        stateJson.put("type", "state")
                        stateJson.put("seq", seq++)
                        stateJson.put("ts", now)
                        stateJson.put("steer", steer.toDouble())
                        // Pedals go out as 0.0..1.0 (released = 0).
                        stateJson.put("throttle", gasView.value / 100.0)
                        stateJson.put("brake", brakeView.value / 100.0)
                        stateJson.put("buttons", buttonsJson)
                        stateJson.put("buttonLabels", labelsJson)
                        connectionManager.send(stateJson.toString())
                        lastSendAt = now
                    } catch (_: Exception) {}
                } else if (lastSendAt > 0L) {
                    seq = 0L
                    lastSendAt = 0L
                    sentLayoutVersion = -1
                }
                sendHandler.postDelayed(this, SEND_INTERVAL_MS)
            }
        })
    }

    private fun startUiLoop() {
        handler.post(object : Runnable {
            override fun run() {
                val half = lockToLockDeg / 2f
                val deg = (steer * half).toInt()
                steerTv.text    = "${if (deg >= 0) "+" else ""}${deg}°"
                wheelView.halfDeg = half
                wheelView.steer = steer
                wheelView.gas   = gasView.value / 100f
                wheelView.brake = brakeView.value / 100f
                wheelView.invalidate()
                handler.postDelayed(this, 33L)
            }
        })
    }

    companion object {
        private const val SEND_INTERVAL_MS = 10L   // 100 Hz
    }
}
