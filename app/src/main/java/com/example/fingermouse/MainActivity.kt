package com.example.fingermouse

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private class Sample(val t: Long, val x: Float, val y: Float)

    private companion object {
        const val DEF_PINCH_ON = 0.28f   // 이 값보다 작으면 '집음'
        const val DEF_PINCH_OFF = 0.38f  // 이 값보다 커지면 '놓음'
        const val DEF_MARGIN = 0.15f
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView

    private var tracker: HandTracker? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var useFront = true
    private var margin = DEF_MARGIN
    private var pinchOn = DEF_PINCH_ON
    private var pinchOff = DEF_PINCH_OFF
    private val prefs by lazy { getSharedPreferences("fingermouse", MODE_PRIVATE) }

    private val filterX = OneEuroFilter(1.5f, 0.02f)
    private val filterY = OneEuroFilter(1.5f, 0.02f)
    private val history = ArrayDeque<Sample>()

    private var pinching = false
    private var offX = 0f
    private var offY = 0f
    private var lastPtrX = 0f
    private var lastPtrY = 0f
    private var downTime = 0L
    private var clickCount = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else status.text = "카메라 권한이 필요합니다"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        margin = prefs.getFloat("margin", DEF_MARGIN)
        pinchOn = prefs.getFloat("pinchOn", DEF_PINCH_ON)
        pinchOff = prefs.getFloat("pinchOff", DEF_PINCH_OFF)
        useFront = prefs.getBoolean("useFront", true)
        buildUi()
        setContentView(root)

        try {
            tracker = HandTracker(this)
        } catch (e: Exception) {
            status.text = "손 인식 모델 로드 실패: ${e.message}"
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ───────────────────────── UI ─────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            alpha = 0.5f
        }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            text = "카메라 준비 중…"
            setPadding(dp(12), dp(12), dp(12), 0)
        }
        root.addView(status, FrameLayout.LayoutParams(dp(210), WRAP))

        // 테스트 1: 클릭 버튼
        val testBtn = Button(this).apply {
            text = "클릭 테스트 (0)"
            setOnClickListener {
                clickCount++
                text = "클릭 테스트 ($clickCount)"
            }
        }
        root.addView(
            testBtn,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                topMargin = dp(180)
            }
        )

        // 테스트 2: 드래그 상자
        val box = TextView(this).apply {
            text = "드래그"
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.rgb(255, 167, 38))
        }
        var grabX = 0f
        var grabY = 0f
        box.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabX = e.x
                    grabY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    v.x += e.x - grabX
                    v.y += e.y - grabY
                }
            }
            true
        }
        root.addView(box, FrameLayout.LayoutParams(dp(90), dp(90), Gravity.CENTER))

        // 아래쪽 조절 패널
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        addSlider(
            panel, 0.05f, 25, margin,
            { "이동 민감도 (오른쪽일수록 작은 손동작으로 이동)" }
        ) { v ->
            margin = v
            overlay.margin = v
            overlay.invalidate()
            prefs.edit().putFloat("margin", v).apply()
        }
        addSlider(
            panel, 0.10f, 40, pinchOn,
            { "붙임 기준 ${"%.2f".format(it)} (작을수록 꽉 붙여야 클릭)" }
        ) { v ->
            pinchOn = v
            prefs.edit().putFloat("pinchOn", v).apply()
        }
        addSlider(
            panel, 0.20f, 60, pinchOff,
            { "놓음 기준 ${"%.2f".format(it)} (작을수록 빨리 초록으로)" }
        ) { v ->
            pinchOff = v
            prefs.edit().putFloat("pinchOff", v).apply()
        }
        val flip = Button(this).apply {
            text = "카메라 전환 (전면/후면)"
            setOnClickListener {
                useFront = !useFront
                prefs.edit().putBoolean("useFront", useFront).apply()
                filterX.reset()
                filterY.reset()
                startCamera()
            }
        }
        val full = Button(this).apply {
            text = "▶ 다른 화면에서도 사용 (전체 화면 모드)"
            setOnClickListener { startFullMode() }
        }
        panel.addView(full)
        panel.addView(flip)
        root.addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // 맨 위: 포인터/미니맵 오버레이 (터치는 통과)
        overlay = OverlayView(this)
        overlay.margin = margin
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** 라벨 + 슬라이더 한 쌍을 panel에 추가. 값 = base + 진행도/100 */
    private fun addSlider(
        panel: LinearLayout,
        base: Float,
        maxProgress: Int,
        initial: Float,
        labelOf: (Float) -> String,
        onChange: (Float) -> Unit
    ) {
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = labelOf(initial)
        }
        val seek = SeekBar(this).apply {
            max = maxProgress
            progress = ((initial - base) * 100f).roundToInt().coerceIn(0, maxProgress)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = base + p / 100f
                label.text = labelOf(v)
                onChange(v)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        panel.addView(label)
        panel.addView(seek)
    }

    // ───────────────────────── 카메라 ─────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

            val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
                status.text = "손을 카메라 앞에 보여주세요 ✋"
            } catch (e: Exception) {
                status.text = "카메라 시작 실패: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 분석 스레드: 프레임 → 손 인식 → UI 스레드로 결과 전달 */
    private fun analyze(proxy: ImageProxy) {
        try {
            val t = tracker ?: return
            val src = proxy.toBitmap()
            val m = Matrix()
            m.postRotate(proxy.imageInfo.rotationDegrees.toFloat())
            if (useFront) m.postScale(-1f, 1f) // 거울처럼 좌우 반전
            val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)

            val result = t.detect(bmp)
            val now = SystemClock.uptimeMillis()
            runOnUiThread { handleResult(result, now) }
        } catch (e: Exception) {
            runOnUiThread { status.text = "오류: ${e.message}" }
        } finally {
            proxy.close()
        }
    }

    // ───────────────────────── 포인터 / 클릭 ─────────────────────────

    private fun handleResult(r: HandTracker.Result?, now: Long) {
        if (r == null) {
            if (pinching) {
                sendTouch(MotionEvent.ACTION_UP, lastPtrX, lastPtrY)
                pinching = false
                offX = 0f
                offY = 0f
            }
            overlay.showPointer = false
            overlay.points = null
            overlay.invalidate()
            filterX.reset()
            filterY.reset()
            history.clear()
            status.text = "손이 보이지 않아요 ✋"
            return
        }

        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 카메라 중앙 영역을 화면 전체로 확대 매핑
        val m = margin
        val nx = ((r.tipX - m) / (1f - 2f * m)).coerceIn(0f, 1f)
        val ny = ((r.tipY - m) / (1f - 2f * m)).coerceIn(0f, 1f)
        val sx = filterX.filter(nx * w, now)
        val sy = filterY.filter(ny * h, now)

        history.addLast(Sample(now, sx, sy))
        while (history.size > 1 && now - history.first().t > 300) history.removeFirst()

        if (!pinching && r.pinchRatio < pinchOn) {
            // 집는 동작 중 손끝이 흔들리므로, 약 150ms 전 위치를 클릭 지점으로 사용
            val anchor = history.firstOrNull { now - it.t <= 150 } ?: history.last()
            offX = anchor.x - sx
            offY = anchor.y - sy
            pinching = true
            lastPtrX = anchor.x
            lastPtrY = anchor.y
            sendTouch(MotionEvent.ACTION_DOWN, anchor.x, anchor.y)
        } else if (pinching && r.pinchRatio > maxOf(pinchOff, pinchOn + 0.05f)) {
            sendTouch(MotionEvent.ACTION_UP, lastPtrX, lastPtrY)
            pinching = false
            offX = 0f
            offY = 0f
        }

        val px = (sx + offX).coerceIn(0f, w - 1f)
        val py = (sy + offY).coerceIn(0f, h - 1f)
        if (pinching && (px != lastPtrX || py != lastPtrY)) {
            sendTouch(MotionEvent.ACTION_MOVE, px, py)
        }
        lastPtrX = px
        lastPtrY = py

        overlay.showPointer = true
        overlay.pointerX = px
        overlay.pointerY = py
        overlay.pinching = pinching
        overlay.points = r.points
        overlay.invalidate()

        val gap = "%.2f".format(r.pinchRatio)
        status.text = if (pinching) "👌 집는 중 (클릭/드래그)\n손가락 간격 $gap"
        else "☝ 추적 중 · 엄지+검지를 붙이면 클릭\n손가락 간격 $gap"
    }

    /** 포인터 위치에 가짜 터치 이벤트를 보내 버튼/슬라이더/드래그를 조작합니다. */
    private fun sendTouch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val ev = MotionEvent.obtain(downTime, now, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        root.dispatchTouchEvent(ev)
        ev.recycle()
    }

    // ───────────────────────── 전체 화면 모드 ─────────────────────────

    private var askedNotif = false

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            askedNotif = true
            startFullMode()
        }

    /** 다른 앱 위에서도 포인터가 뜨고 터치가 되는 모드를 켭니다. */
    private fun startFullMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (PointerAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                "접근성 설정에서 '손가락 마우스'를 켠 뒤, 이 앱으로 돌아와 다시 눌러 주세요",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        // 이 화면의 카메라를 먼저 놓아야 서비스가 카메라를 쓸 수 있습니다.
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (_: Exception) {
        }
        overlay.showPointer = false
        overlay.invalidate()

        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        // 앱으로 돌아오면 전체 화면 모드는 끄고, 이 화면의 테스트 모드로 복귀
        if (TrackingService.instance != null) {
            TrackingService.stop()
            if (tracker != null && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startCamera()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        analysisExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)
        tracker?.close()
    }
}
