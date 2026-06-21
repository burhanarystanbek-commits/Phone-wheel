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
import android.os.Looper
import android.text.InputType
import android.view.*
import android.widget.*
import com.phonewheel.app.transport.ConnectionManager
import com.phonewheel.app.transport.ConnectionState
import com.phonewheel.app.transport.ITransport
import com.phonewheel.app.transport.TransportKind
import com.phonewheel.app.transport.WebSocketTransport
import com.phonewheel.app.transport.BluetoothTransport
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
    private val rotMatrix   = FloatArray(9)
    private val orientation = FloatArray(3)

    private var rollDeg       = 0f
    private var centerRoll    = 0f
    private var steer         = 0f
    private var maxSteerAngle = 45f
    private var usbMode       = true
    private var connected     = false
    private var seq           = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val connectionManager = ConnectionManager()

    private lateinit var gasView:       PedalView
    private lateinit var brakeView:     PedalView
    private lateinit var wheelView:     WheelView
    private lateinit var statusText:    TextView
    private lateinit var connectBtn:    Button
    private lateinit var ipInput:       EditText
    private lateinit var steerTv:       TextView

    // --- customizable buttons (free placement, unlimited count) ---
    private lateinit var layoutStore:    LayoutStore
    private lateinit var buttonOverlay:  FrameLayout
    private lateinit var editToggleBtn:  Button
    private lateinit var editToolbar:    LinearLayout
    private val customButtons = mutableListOf<CustomButtonView>()
    private var nextButtonId = 1
    private var editMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        layoutStore = LayoutStore(this)
        setupSensors()
        buildUi()
        setupConnectionCallbacks()
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
        connectionManager.disconnect()
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
            SensorManager.getOrientation(rotMatrix, orientation)
            // Приложение всегда держат в landscape (см. манифест), поэтому
            // естественные portrait-оси повёрнуты на 90°: наклон влево-вправо
            // в landscape-хвате соответствует оси pitch (X), а не roll (Y) —
            // roll в этом хвате реагирует на наклон вперёд-назад, отсюда и баг.
            rollDeg = -orientation[1] * 180f / PI.toFloat()
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
            setHintTextColor(Color.parseColor("#6b7394"))
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            background = roundRect(Color.parseColor("#0a0d15"), dp(10).toFloat())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        center.addView(ipInput, lp(MATCH, dp(42)).also { it.setMargins(0, dp(4), 0, 0) })

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
                centerRoll = rollDeg
                Toast.makeText(context, "Центр установлен", Toast.LENGTH_SHORT).show()
            }
        }
        btnRow.addView(connectBtn, lp(0, dp(44), 1f, endMargin = dp(4)))
        btnRow.addView(centerBtn,  lp(0, dp(44), 1f))
        center.addView(btnRow, lp(MATCH, dp(44)).also { it.setMargins(0, dp(4), 0, 0) })

        // angle label + seekbar
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
            // No saved layout yet -> start with an empty overlay; user adds
            // buttons with the "+" toolbar button.
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
        val addBtn = Button(this).apply {
            text = "+ Добавить кнопку"
            setTextColor(Color.WHITE)
            background = roundRect(Color.parseColor("#5b4fe8"), dp(10).toFloat())
            setOnClickListener { addNewButton() }
        }
        editToolbar.addView(addBtn, lp(0, dp(34), 1f, endMargin = dp(4)))
        val hintLbl = tv("тяни • тяни уголок чтобы изменить размер • тап чтобы переименовать • держи чтобы удалить", 9f, Color.parseColor("#6b7394"))
        editToolbar.addView(hintLbl, lp(0, dp(34), 1.4f, endMargin = dp(4)))
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

    /** Builds the concrete transport for whichever kind ConnectionManager
     *  was asked to switch to. USB and Wi-Fi are both WebSocketTransport —
     *  only the target URL differs — Bluetooth is its own implementation
     *  (a not-yet-implemented placeholder for now). */
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
        TransportKind.BLUETOOTH -> BluetoothTransport()
    }

    private fun buildHello(): String = JSONObject()
        .put("type", "hello")
        .put("name", android.os.Build.MODEL)
        .put("mode", if (usbMode) "usb" else "wifi")
        .toString()

    private fun connect() {
        statusText.text = "Подключение..."
        statusText.setTextColor(Color.parseColor("#ffbe5f"))
        val kind = if (usbMode) TransportKind.USB else TransportKind.WIFI
        connectionManager.switchTo(kind) { k -> buildTransport(k) }
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
        handler.post(object : Runnable {
            override fun run() {
                if (connected) {
                    try {
                        val buttons = JSONObject()
                        val labels  = JSONObject()
                        // Emit id->pressed and id->label for every button
                        // currently on screen, so the PC side can build its
                        // mapping table from whatever buttons actually exist
                        // — no fixed catalogue, no limit on count.
                        for (cb in customButtons) {
                            buttons.put(cb.buttonId, cb.pressed)
                            labels.put(cb.buttonId, cb.labelText)
                        }
                        connectionManager.send(JSONObject()
                            .put("type",     "state")
                            .put("seq",      seq++)
                            .put("steer",    steer.toDouble())
                            .put("throttle", 0.5 + gasView.value.toDouble() / 200.0)
                            .put("brake",    0.5 + brakeView.value.toDouble() / 200.0)
                            .put("buttons",  buttons)
                            .put("buttonLabels", labels)
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
}
