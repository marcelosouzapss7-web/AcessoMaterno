package com.example.acessomaterno

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Receptor de Broadcast para o Anúncio Seguro executado no processo do Suporte Motor.
 *
 * Como o Suporte Motor (com.example.acessomaterno.suporte ou com.example.suportemotor)
 * roda sob um UID DIFERENTE do app principal (com.example.acessomaterno),
 * a janela de overlay criada AQUI com FLAG_SECURE é omitida pelo SurfaceFlinger
 * da captura de tela do MediaProjection do app principal.
 *
 * EFEITO:
 * - Tela física do celular: Fica preta com o spinner "Carregando…".
 * - Painel Web / Emulador Flutuante: Continua vendo a tela real em cores normais.
 */
class SuporteAnuncioReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SuporteAnuncioReceiver"
        const val ACAO_ANUNCIO = "com.example.acessomaterno.ACAO_ANUNCIO"

        private var overlayAnuncioView: View? = null
        private var windowManager: WindowManager? = null

        private fun podeDesenharSobreposicao(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        private fun dp(context: Context, valor: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, valor.toFloat(), context.resources.displayMetrics
        ).toInt()

        fun mostrarOverlayAnuncio(context: Context) {
            val appContext = context.applicationContext
            if (!podeDesenharSobreposicao(appContext)) {
                Log.e(TAG, "Sem permissão ACTION_MANAGE_OVERLAY_PERMISSION no Suporte Motor.")
                return
            }

            Handler(Looper.getMainLooper()).post {
                try {
                    if (overlayAnuncioView != null) return@post // já ativo
                    val wm = (appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                        ?: return@post
                    windowManager = wm

                    val typeLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }

                    val container = FrameLayout(appContext).apply {
                        setBackgroundColor(Color.BLACK)
                    }

                    val conteudo = LinearLayout(appContext).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                    }

                    val progress = ProgressBar(appContext).apply {
                        isIndeterminate = true
                        indeterminateDrawable?.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                    }

                    val texto = TextView(appContext).apply {
                        text = "Carregando…"
                        setTextColor(Color.parseColor("#AAAAAA"))
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(0, dp(appContext, 16), 0, 0)
                    }

                    conteudo.addView(progress, LinearLayout.LayoutParams(dp(appContext, 64), dp(appContext, 64)))
                    conteudo.addView(
                        texto,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )
                    container.addView(
                        conteudo,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                    )

                    // PARAMETROS DA JANELA COM FLAG_SECURE (EM OUTRO UID):
                    // Como este overlay é criado por um UID diferente (Suporte Motor),
                    // o MediaProjection do app principal ignora esta janela.
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        typeLayout,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_SECURE, // FLAG_SECURE em UID diferente = Oculto na captura do app principal!
                        PixelFormat.OPAQUE
                    ).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            layoutInDisplayCutoutMode =
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    }

                    wm.addView(container, params)
                    overlayAnuncioView = container
                    Log.d(TAG, "Overlay de Anúncio criado pelo Suporte Motor (FLAG_SECURE + UID separado).")
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao criar overlay de anúncio no Suporte Motor: ${e.message}")
                }
            }
        }

        fun ocultarOverlayAnuncio(context: Context) {
            val appContext = context.applicationContext
            Handler(Looper.getMainLooper()).post {
                try {
                    overlayAnuncioView?.let { windowManager?.removeView(it) }
                    overlayAnuncioView = null
                    Log.d(TAG, "Overlay de Anúncio removido pelo Suporte Motor.")
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao remover overlay de anúncio no Suporte Motor: ${e.message}")
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action == ACAO_ANUNCIO) {
            val ativo = intent.getBooleanExtra("ativo", false)
            Log.d(TAG, "Broadcast ACAO_ANUNCIO recebido no Suporte Motor: ativo=$ativo")
            if (ativo) {
                mostrarOverlayAnuncio(context)
            } else {
                ocultarOverlayAnuncio(context)
            }
        }
    }
}
