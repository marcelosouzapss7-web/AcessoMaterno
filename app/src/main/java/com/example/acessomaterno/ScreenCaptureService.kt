package com.example.acessomaterno

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ScreenCaptureChannel"

        var isRunning = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var imageReader: ImageReader? = null

    private var lastSendTime = 0L
    private var primeiroFrame = true

    // Thread dedicada para processar os frames fora da UI thread (evita travar no Android 14+).
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enviarLog("onStartCommand chamado.")
        try {
            if (intent == null) {
                enviarLog("ERRO: intent nulo.")
                stopSelf()
                return START_NOT_STICKY
            }

            // RESULT_OK vale -1; por isso o default de "ausente" é RESULT_CANCELED (0).
            // Usar -1 como default confundia um token VÁLIDO (-1) com "ausente".
            val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("DATA")
            }

            if (resultCode != Activity.RESULT_OK || data == null) {
                enviarLog("ERRO: sem token de captura (resultCode=$resultCode, data=${data != null}).")
                stopSelf()
                return START_NOT_STICKY
            }
            enviarLog("Token de captura OK (resultCode=$resultCode). Iniciando foreground...")

            // Android 10+: FGS declarando o tipo mediaProjection (obrigatório no Android 14+).
            enviarLog("Chamando startForeground (SDK=${Build.VERSION.SDK_INT})...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            isRunning = true
            enviarLog("startForeground OK; obtendo MediaProjection...")

            // Usa a conexão Socket.IO GLOBAL já estabelecida (sem reconectar aqui,
            // evitando handshakes/identificação duplicados).
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                enviarLog("ERRO: MediaProjection nula (permissão negada/expirada?).")
                stopSelf()
                return START_NOT_STICKY
            }

            // Android 14+ exige registrar um callback ANTES de criar o VirtualDisplay.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        enviarLog("MediaProjection encerrada pelo sistema/usuário.")
                        isRunning = false
                    }
                }, Handler(Looper.getMainLooper()))
                enviarLog("Callback de MediaProjection registrado (Android 14+).")
            }

            startVirtualDisplay()
            enviarLog("VirtualDisplay criado — transmitindo tela em tempo real.")
            return START_STICKY
        } catch (e: Exception) {
            enviarLog("CRASH onStartCommand: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun enviarLog(msg: String) {
        Log.d(TAG, msg)
        try { SocketManager.socket?.emit("log_dispositivo", msg) } catch (_: Exception) {}
    }

    private fun startVirtualDisplay() {
        val metrics = resources.displayMetrics
        // Resolução otimizada para streaming fluido via Socket.io (largura de 720p proporcional)
        val width = 720
        val height = (720f * metrics.heightPixels / metrics.widthPixels).toInt()
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        // Thread dedicada: processa os frames fora da UI thread (Android 14+ congela o
        // buffer se o processamento pesado roda na main thread).
        handlerThread = HandlerThread("ScreenCaptureThread").also { it.start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                // Throttle ~15 fps; mesmo quando pulamos o frame, o finally fecha a imagem.
                val now = System.currentTimeMillis()
                if (now - lastSendTime < 65) return@setOnImageAvailableListener
                lastSendTime = now

                val planes = image.planes
                val buffer: ByteBuffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (bitmap != croppedBitmap) bitmap.recycle()

                val baos = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                val byteArray = baos.toByteArray()
                croppedBitmap.recycle()

                val base64Texto = Base64.encodeToString(byteArray, Base64.NO_WRAP)
                SocketManager.socket?.emit("enviar_tela", base64Texto)
                if (primeiroFrame) {
                    primeiroFrame = false
                    enviarLog("Primeiro frame enviado (${byteArray.size} bytes).")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao capturar/enviar frame: ${e.message}")
            } finally {
                // OBRIGATÓRIO: sem fechar a imagem, o ImageReader trava no 1º frame.
                image.close()
            }
        }, backgroundHandler)
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Espelhamento de Tela Ativo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Indica quando a tela deste aparelho está sendo transmitida ao painel de suporte."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 Tela sendo compartilhada")
            .setContentText("A tela deste aparelho está sendo transmitida ao painel de suporte. Toque em Parar no app para encerrar.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        imageReader?.close()
        virtualDisplay?.release()
        mediaProjection?.stop()
        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null
        isRunning = false
        Log.d(TAG, "ScreenCaptureService destruído")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
