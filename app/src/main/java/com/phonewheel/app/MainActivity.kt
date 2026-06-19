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

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        val top    = dp(50f)
        val bottom = h - dp(50f)
        gradient = LinearGradient(0f, bottom, 0f, top, colorGrad, colorMain, Shader.TileMode.CLAMP)
        paintFill.shader = gradient
    }

    override fun onDraw(canvas: Canvas) {
        val w  = width.toFloat()
        val h  = height.toFloat()
        val cx = w / 2f
        val padH = dp(50f)
        val padW = dp(14f)
        val tL = cx - padW; val tR = cx + padW
        val tT = padH;       val tB = h - padH
        val tH = tB - tT

        paintBg.color = Color.parseColor("#10141f")
        canvas.drawRoundRect(RectF(0f, 0f, w, h), dp(18f), dp(18f), paintBg)

        if (value > 0) {
            paintGlow.color = Color.argb(
                (value * 1.2f).toInt().coerceIn(0, 70),
                Color.red(colorMain), Color.green(colorMain), Color.blue(colorMain))
            canvas.drawCircle(cx, tB, padW * 2f, paintGlow)
        }

        paintBg.color = colorDark
        canvas.drawRoundRect(RectF(tL, tT, tR, tB), dp(10f), dp(10f), paintBg)

        val fillH = tH * value / 100f
        if (fillH > 2f)
            canvas.drawRoundRect(RectF(tL, tB - fillH, tR, tB), dp(10f), dp(10f), paintFill)

        val thumbY = (tB - fillH).coerceIn(tT + dp(12f), tB - dp(12f))
        paintThumb.color = Color.argb(if (value > 0) 150 else 50, 255, 255, 255)
        canvas.drawCircle(cx, thumbY, dp(12f), paintThumb)

        paintText.textSize = dp(11f)
        paintText.color = if (value > 0) colorMain else Color.parseColor("#6b7394")
        canvas.drawText(label, cx, padH - dp(6f), paintText)

        paintText.textSize = dp(20f)
        paintText.color = colorMain
        canvas.drawText("$value", cx, h - dp(6f), paintText)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tT = dp(50f); val tB = height - dp(50f)
        fun calc(y: Float) = (((1f - (y - tT) / (tB - tT)) * 100f).toInt()).coerceIn(0, 100)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE  -> { value = calc(event.y); invalidate(); return true }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> { value = 0; invalidate(); return true }
        }
        return super.onTouchEvent(event)
    }
}

class WheelView(context: Context) : View(context) {
    var steer: Float = 0f; var gas: Float = 0f; var brake: Float = 0f

    private val pRing  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pSpoke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pHub   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pArc   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pPedal = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w/2f; val cy = h/2f; val r = minOf(w,h)*0.36f

        pRing.style = Paint.Style.STROKE; pRing.strokeWidth = r*0.18f
        pRing.color = Color.parseColor("#1e2536")
        canvas.drawCircle(cx, cy, r, pRing)

        if (abs(steer) > 0.01f) {
            pArc.style = Paint.Style.STROKE; pArc.strokeWidth = r*0.18f
            pArc.color = Color.argb((80 + abs(steer)*170).toInt().coerceIn(0,255), 124, 111, 255)
            val oval = RectF(cx-r, cy-r, cx+r, cy+r)
            val sw = steer * 144f
            if (sw >= 0) canvas.drawArc(oval, -90f, sw, false, pArc)
            else         canvas.drawArc(oval, -90f+sw, -sw, false, pArc)
        }

        canvas.save(); canvas.translate(cx, cy); canvas.rotate(steer*82f)
        pSpoke.style = Paint.Style.STROKE; pSpoke.strokeWidth = r*0.042f
        pSpoke.strokeCap = Paint.Cap.ROUND; pSpoke.color = Color.parseColor("#7c6fff")
        canvas.drawLine(-r*.78f,0f,r*.78f,0f,pSpoke)
        canvas.drawLine(0f,0f,0f,r*.70f,pSpoke)
        canvas.restore()

        pHub.style = Paint.Style.FILL; pHub.color = Color.parseColor("#1a1f32")
        canvas.drawCircle(cx, cy, r*0.27f, pHub)

        pText.color = Color.parseColor("#7c6fff"); pText.textSize = r*0.20f
        pText.typeface = Typeface.DEFAULT_BOLD; pText.textAlign = Paint.Align.CENTER
        val deg = (steer*90f).toInt()
        canvas.drawText("${if(deg>=0)"+" else ""}${deg}°", cx, cy+r*0.08f, pText)

        fun arc(sa: Float, sv: Float, bg: Int, fg: Int) {
            val ov = RectF(cx-r*.72f, cy-r*.72f, cx+r*.72f, cy+r*.72f)
            pPedal.style=Paint.Style.STROKE; pPedal.strokeWidth=r*0.22f; pPedal.strokeCap=Paint.Cap.ROUND
            pPedal.color=bg; canvas.drawArc(ov, sa, 60f, false, pPedal)
            if (sv>0.01f) { pPedal.color=fg; canvas.drawArc(ov, sa, 60f*sv, false, pPedal) }
        }
        arc(200f, gas,   Color.parseColor("#0e2a1e"), Color.parseColor("#22dc82"))
        arc(280f, brake, Color.parseColor("#2a0e0e"), Color.parseColor("#ff4f4f"))
    }
}

@Suppress("DEPRECATION")
class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotSensor: Sensor? = null
    private val rotMatrix   = FloatArray(9)
    private val orientation = FloatArray(3)

    // For landscape: phone held like steering wheel horizontally
    // We use orientation[1] (pitch) which changes when rotating left/right in landscape
    private var steer         = 0f
    private var centerPitch   = 0f
    private var rawPitch      = 0f
    private var maxSteerAngle = 45f
    private var usbMode       = true
    private var connected     = false
    private var seq           = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val client  = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
    private var socket: WebSocket? = null

    // Views
    private lateinit var brakeView:  PedalView   // LEFT
    private lateinit var gasView:    PedalView   // RIGHT
    private lateinit var wheelView:  WheelView
    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button
    private lateinit var ipInput:    EditText
    private lateinit var steerTv:    TextView

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
    override fun onPause()   { super.onPause();   sensorManager.unregisterListener(this) }
    override fun onDestroy() { socket?.close(1000,"closed"); super.onDestroy() }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

        // Remap for LANDSCAPE orientation (phone held horizontally like a steering wheel)
        // In landscape: X axis points up, Y axis points toward user
        // We remap axes so orientation[2] gives left/right tilt in landscape
        val remapped = FloatArray(9)
        SensorManager.remapCoordinateSystem(
            rotMatrix,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            remapped
        )
        SensorManager.getOrientation(remapped, orientation)

        // orientation[2] = roll — this is left/right tilt when phone is landscape
        rawPitch = orientation[2] * 180f / PI.toFloat()
        val raw = (rawPitch - centerPitch) / maxSteerAngle
        steer = raw.coerceIn(-1f, 1f).let { if (abs(it) < 0.015f) 0f else it }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun lp(w: Int, h: Int, weight: Float = 0f, endMargin: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).also {
            if (endMargin > 0) it.setMargins(0, 0, endMargin, 0)
        }

    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = sp; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun rr(color: Int, radius: Float) =
        android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius }

    private fun tabBtn(lbl: String, sel: Boolean) = Button(this).apply {
        text = lbl
        setTextColor(if (sel) Color.parseColor("#7c6fff") else Color.parseColor("#6b7394"))
        background = rr(if (sel) Color.parseColor("#1a213a") else Color.parseColor("#1e2536"), dp(10).toFloat())
    }

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#080b12"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        // BRAKE — LEFT
        brakeView = PedalView(this, false)
        root.addView(brakeView, lp(0, MATCH, 1f, endMargin = dp(6)))

        // CENTER
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rr(Color.parseColor("#10141f"), dp(18).toFloat())
        }
        root.addView(center, lp(0, MATCH, 2.4f, endMargin = dp(6)))

        // status row
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusText = tv("не подключено", 11f, Color.parseColor("#ffbe5f"))
        topRow.addView(statusText, lp(0, WRAP, 1f))
        center.addView(topRow, lp(MATCH, dp(24)))

        // USB / WiFi tabs
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val usbBtn  = tabBtn("USB", true)
        val wifiBtn = tabBtn("Wi-Fi", false)
        usbBtn.setOnClickListener  { setConnMode(true,  usbBtn, wifiBtn) }
        wifiBtn.setOnClickListener { setConnMode(false, usbBtn, wifiBtn) }
        modeRow.addView(usbBtn,  lp(0, dp(36), 1f, endMargin = dp(4)))
        modeRow.addView(wifiBtn, lp(0, dp(36), 1f))
        center.addView(modeRow, lp(MATCH, dp(36)).also { it.setMargins(0, dp(4), 0, 0) })

        // IP input
        ipInput = EditText(this).apply {
            hint = "192.168.1.20"; setText("192.168.1.20")
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#6b7394"))
            inputType = InputType.TYPE_CLASS_TEXT; maxLines = 1
            background = rr(Color.parseColor("#0a0d15"), dp(10).toFloat())
            setPadding(dp(10), dp(6), dp(10), dp(6)); visibility = View.GONE
        }
        center.addView(ipInput, lp(MATCH, dp(42)).also { it.setMargins(0, dp(4), 0, 0) })

        // Connect + Center buttons
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectBtn = Button(this).apply {
            text = "Подключить"; setTextColor(Color.parseColor("#7c6fff"))
            background = rr(Color.parseColor("#1f2a4a"), dp(10).toFloat())
            setOnClickListener { if (connected) disconnect() else connect() }
        }
        val centerBtn = Button(this).apply {
            text = "Центр"; setTextColor(Color.WHITE)
            background = rr(Color.parseColor("#1e2536"), dp(10).toFloat())
            setOnClickListener {
                centerPitch = rawPitch
                Toast.makeText(context, "Центр установлен", Toast.LENGTH_SHORT).show()
            }
        }
        btnRow.addView(connectBtn, lp(0, dp(44), 1f, endMargin = dp(4)))
        btnRow.addView(centerBtn,  lp(0, dp(44), 1f))
        center.addView(btnRow, lp(MATCH, dp(44)).also { it.setMargins(0, dp(4), 0, 0) })

        // Angle seekbar
        val angleLabel = tv("Угол: 45°", 11f, Color.parseColor("#6b7394"))
        center.addView(angleLabel, lp(MATCH, dp(22)).also { it.setMargins(0, dp(6), 0, 0) })
        val angleSeek = SeekBar(this).apply {
            max = 80; progress = 35
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    maxSteerAngle = (10 + p).toFloat()
                    angleLabel.text = "Угол: ${maxSteerAngle.toInt()}°"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        center.addView(angleSeek, lp(MATCH, dp(32)))

        // Wheel
        wheelView = WheelView(this)
        center.addView(wheelView, lp(MATCH, 0, 1f))

        // Steer value
        steerTv = tv("+0°", 22f, Color.parseColor("#7c6fff"), bold = true)
            .also { it.gravity = Gravity.CENTER }
        center.addView(steerTv, lp(MATCH, dp(34)))

        val hint = tv("Держи горизонтально как руль. Нажми Центр перед игрой.", 10f, Color.parseColor("#6b7394"))
        hint.gravity = Gravity.CENTER
        center.addView(hint, lp(MATCH, dp(24)))

        // GAS — RIGHT
        gasView = PedalView(this, true)
        root.addView(gasView, lp(0, MATCH, 1f))

        setContentView(root)
    }

    private fun setConnMode(usb: Boolean, usbBtn: Button, wifiBtn: Button) {
        usbMode = usb
        usbBtn.setTextColor(if (usb) Color.parseColor("#7c6fff") else Color.parseColor("#6b7394"))
        usbBtn.background = rr(if (usb) Color.parseColor("#1a213a") else Color.parseColor("#1e2536"), dp(10).toFloat())
        wifiBtn.setTextColor(if (!usb) Color.parseColor("#7c6fff") else Color.parseColor("#6b7394"))
        wifiBtn.background = rr(if (!usb) Color.parseColor("#1a213a") else Color.parseColor("#1e2536"), dp(10).toFloat())
        ipInput.visibility = if (usb) View.GONE else View.VISIBLE
    }

    private fun connect() {
        val ip  = ipInput.text.toString().trim().ifBlank { "127.0.0.1" }
        val url = if (usbMode) "ws://127.0.0.1:27111/ws" else "ws://$ip:27111/ws"
        statusText.text = "Подключение..."; statusText.setTextColor(Color.parseColor("#ffbe5f"))
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected = true
                ws.send(JSONObject().put("type","hello")
                    .put("name", android.os.Build.MODEL)
                    .put("mode", if (usbMode) "usb" else "wifi").toString())
                runOnUiThread {
                    statusText.text = "Подключено"; statusText.setTextColor(Color.parseColor("#22dc82"))
                    connectBtn.text = "Отключить"; connectBtn.setTextColor(Color.parseColor("#22dc82"))
                    connectBtn.background = rr(Color.parseColor("#0e2a1e"), dp(10).toFloat())
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connected = false
                runOnUiThread {
                    statusText.text = "Ошибка: ${t.message?.take(28)?:"?"}"; statusText.setTextColor(Color.parseColor("#ff6060"))
                    connectBtn.text = "Подключить"; connectBtn.setTextColor(Color.parseColor("#7c6fff"))
                    connectBtn.background = rr(Color.parseColor("#1f2a4a"), dp(10).toFloat())
                }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connected = false
                runOnUiThread {
                    statusText.text = "Отключено"; statusText.setTextColor(Color.parseColor("#ffbe5f"))
                    connectBtn.text = "Подключить"; connectBtn.setTextColor(Color.parseColor("#7c6fff"))
                    connectBtn.background = rr(Color.parseColor("#1f2a4a"), dp(10).toFloat())
                }
            }
        })
    }

    private fun disconnect() {
        connected = false; socket?.close(1000,"user"); socket = null
        statusText.text = "Отключено"; statusText.setTextColor(Color.parseColor("#ffbe5f"))
        connectBtn.text = "Подключить"; connectBtn.setTextColor(Color.parseColor("#7c6fff"))
        connectBtn.background = rr(Color.parseColor("#1f2a4a"), dp(10).toFloat())
    }

    private fun startSendLoop() {
        handler.post(object : Runnable {
            override fun run() {
                if (connected) {
                    try {
                        val throttleAxis = 0.5 + gasView.value.toDouble()   / 200.0
                        val brakeAxis    = 0.5 + brakeView.value.toDouble() / 200.0
                        socket?.send(JSONObject()
                            .put("type",     "state")
                            .put("seq",      seq++)
                            .put("steer",    steer.toDouble())
                            .put("throttle", throttleAxis)
                            .put("brake",    brakeAxis)
                            .put("buttons",  JSONObject())
                            .toString())
                    } catch (_: Exception) {}
                }
                handler.postDelayed(this, 16L)
            }
        })
    }

    private fun startUiLoop() {
        handler.post(object : Runnable {
            override fun run() {
                val deg = (steer * maxSteerAngle).toInt()
                steerTv.text    = "${if (deg>=0)"+" else ""}${deg}°"
                wheelView.steer  = steer
                wheelView.gas    = gasView.value / 100f
                wheelView.brake  = brakeView.value / 100f
                wheelView.invalidate()
                handler.postDelayed(this, 40L)
            }
        })
    }
}
