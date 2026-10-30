package com.example.acessomaterno

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra("RESULT_CODE", -1)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("DATA")
        }

        if (resultCode == -1 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        isRunning = true

        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
        startVirtualDisplay()

        Log.d(TAG, "ScreenCaptureService iniciado com MediaProjection")
        return START_STICKY
    }

    private fun startVirtualDisplay() {
        val metrics = resources.displayMetrics
        // Resolução otimizada para streaming fluido via Socket.io (largura de 720p proporcional)
        val width = 720
        val height = (720f * metrics.heightPixels / metrics.widthPixels).toInt()
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            // Limita a ~15 quadros por segundo (a cada ~65ms) para não sobrecarregar a rede
            if (now - lastSendTime < 65) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastSendTime = now

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
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
                image.close()

                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (bitmap != croppedBitmap) bitmap.recycle()

                // Comprimir Bitmap em JPEG com 60% de qualidade
                val baos = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                val byteArray = baos.toByteArray()
                croppedBitmap.recycle()

                // Converter em String Base64 sem quebras de linha (Base64.NO_WRAP)
                val base64Texto = Base64.encodeToString(byteArray, Base64.NO_WRAP)

                // Enviar via Socket.io para o painel do cuidador
                SocketManager.socket?.emit("enviar_tela", base64Texto)

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao capturar/enviar frame: ${e.message}")
                image.close()
            }
        }, null)
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
        isRunning = false
        Log.d(TAG, "ScreenCaptureService destruído")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
