package com.example.fingermouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 앱이 화면에 없어도 카메라로 손을 계속 추적하는 포그라운드 서비스.
 * 결과는 PointerAccessibilityService(포인터 표시 + 터치 대행)로 전달됩니다.
 */
class TrackingService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "com.example.fingermouse.STOP"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIF_ID = 1

        @Volatile
        var instance: TrackingService? = null

        fun stop() {
            instance?.shutdown()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private var tracker: HandTracker? = null
    private var engine: PointerEngine? = null

    @Volatile
    private var running = false

    @Volatile
    private var useFront = true

    private val output = object : PointerEngine.Output {
        private val a: PointerAccessibilityService?
            get() = PointerAccessibilityService.instance

        override fun screenSize(): Pair<Float, Float>? = a?.screenSize()

        override fun onPointer(x: Float, y: Float, pinching: Boolean, visible: Boolean) {
            a?.updatePointer(x, y, pinching, visible)
        }

        override fun onDown(x: Float, y: Float) {
            a?.pinchDown(x, y)
        }

        override fun onMove(x: Float, y: Float) {
            a?.pinchMove(x, y)
        }

        override fun onUp(x: Float, y: Float) {
            a?.pinchUp(x, y)
        }

        override fun onCancel() {
            a?.pinchCancel()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        running = true
        instance = this

        startInForeground()

        val prefs = getSharedPreferences("fingermouse", MODE_PRIVATE)
        useFront = prefs.getBoolean("useFront", true)

        try {
            tracker = HandTracker(this)
        } catch (e: Exception) {
            shutdown()
            return START_NOT_STICKY
        }
        engine = PointerEngine(prefs, output)
        executor = Executors.newSingleThreadExecutor()

        PointerAccessibilityService.instance?.show()
        bindCamera()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "손가락 마우스", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("손가락 마우스 실행 중")
            .setContentText("카메라로 손가락을 추적하고 있어요")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "정지", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val ex = executor
            if (!running || ex == null) return@addListener
            try {
                val provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(ex) { proxy -> analyze(proxy) }

                val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
            } catch (e: Exception) {
                shutdown()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 분석 스레드: 프레임 → 손 인식 → 메인 스레드의 엔진으로 전달 */
    private fun analyze(proxy: ImageProxy) {
        try {
            val t = tracker ?: return
            val src = proxy.toBitmap()
            val m = Matrix()
            m.postRotate(proxy.imageInfo.rotationDegrees.toFloat())
            if (useFront) m.postScale(-1f, 1f)
            val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)

            val result = t.detect(bmp)
            val now = SystemClock.uptimeMillis()
            main.post {
                if (running) engine?.handle(result, now)
            }
        } catch (_: Exception) {
        } finally {
            proxy.close()
        }
    }

    fun shutdown() {
        if (running) {
            running = false
            engine?.stop()
            engine = null
            try {
                ProcessCameraProvider.getInstance(this).get().unbindAll()
            } catch (_: Exception) {
            }
            PointerAccessibilityService.instance?.hide()
            executor?.shutdown()
            try {
                executor?.awaitTermination(300, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            }
            executor = null
            tracker?.close()
            tracker = null
        }
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) {
            running = false
            PointerAccessibilityService.instance?.hide()
        }
        instance = null
        super.onDestroy()
    }
}
