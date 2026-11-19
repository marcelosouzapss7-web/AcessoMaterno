package com.example.acessomaterno

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Activity alvo do Full-Screen Intent (notificação urgente).
 *
 * Objetivo único: ACENDER O VISOR mesmo com a tela apagada e por CIMA da
 * tela de bloqueio, quando o painel remoto envia o comando "notificacao_acordar".
 *
 * Estratégia em camadas (quanto mais moderna a API, mais oficial):
 *   1) AndroidManifest: android:turnScreenOn="true" + android:showWhenLocked="true" (API 27+)
 *   2) onCreate: setTurnScreenOn(true) + setShowWhenLocked(true) (API 27+, código)
 *   3) Flags de janela legados (API < 27): FLAG_TURN_SCREEN_ON | FLAG_SHOW_WHEN_LOCKED
 *      | FLAG_DISMISS_KEYGUARD | FLAG_KEEP_SCREEN_ON | FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
 *   4) WakeLock SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP (garantia física)
 *   5) KeyguardManager.requestDismissKeyguard() (remove a tela de bloqueio, API 26+)
 *
 * A Activity é singleInstance / noHistory / excludeFromRecents: aparece como
 * uma janela rápida, some sozinha após 4 segundos e não polui a lista de
 * aplicativos recentes.
 */
class WakeUpActivity : Activity() {

    companion object {
        private const val TAG = "WakeUpActivity"
        /** Quanto tempo (ms) a janela fica visível antes de fechar sozinha. */
        private const val FECHAR_AUTOMATICAMENTE_MS = 4000L
        /** Duração do WakeLock de garantia. */
        private const val WAKELOCK_MS = 5000L
    }

    private val handlerMain = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        // ── CAMADA 2/3: flags de janela ANTES de setContentView (obrigatório) ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // API 27+: método oficial que liga o visor e aparece por cima do lock.
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            // Legado: flags de janela.
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
            )
        }
        // Em TODAS as APIs, mantemos KEEP_SCREEN_ON para a tela não apagar
        // imediatamente enquanto o operador do painel precisa ver o dispositivo.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        super.onCreate(savedInstanceState)

        // ── CAMADA 4: WakeLock físico (garantia de que o visor acende) ──
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "AcessoMaterno:WakeUpFullScreen"
            ).apply { acquire(WAKELOCK_MS) }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao adquirir WakeLock: ${e.message}")
        }

        // ── CAMADA 5: dispensa a tela de bloqueio (se houver) ──
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (km.isKeyguardLocked) {
                    km.requestDismissKeyguard(this, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao dispensar keyguard: ${e.message}")
        }

        // ── UI mínima: fundo escuro + mensagem central (não precisa de layout XML) ──
        setContentView(construirInterfaceSimples())

        // ── Fecha sozinha após 4s (volta para a tela anterior / home) ──
        handlerMain.postDelayed({ fecharSilencio() }, FECHAR_AUTOMATICAMENTE_MS)

        Log.d(TAG, "WakeUpActivity criada — tela acesa por cima do bloqueio.")
    }

    private fun construirInterfaceSimples(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0A0F1A"))
            setPadding(48, 48, 48, 48)
        }
        val titulo = TextView(this).apply {
            text = "Dispositivo Ativado"
            setTextColor(Color.parseColor("#4ADE80"))
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        val subtitulo = TextView(this).apply {
            text = "Conexão remota ativa — esta janela fecha sozinha em instantes."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        layout.addView(titulo)
        layout.addView(subtitulo)
        return layout
    }

    /** Fecha a Activity e libera o WakeLock (sem animação, silenciosa). */
    private fun fecharSilencio() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        try {
            finishAndRemoveTask()
        } catch (e: Exception) {
            try {
                finish()
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        handlerMain.removeCallbacksAndMessages(null)
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
