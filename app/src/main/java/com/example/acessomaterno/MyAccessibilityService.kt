package com.example.acessomaterno

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.util.TypedValue
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyAccessibilityService"
        var instance: MyAccessibilityService? = null
            private set
    }

    private var activePath: Path? = null
    private var startX = 0f
    private var startY = 0f

    // --- Tela de privacidade (overlay) ---
    private var windowManager: WindowManager? = null
    private var overlayPrivacidade: View? = null
    private val handlerMain = Handler(Looper.getMainLooper())
    // Tempo-limite de segurança: a tela NUNCA fica preta indefinidamente.
    private val TIMEOUT_PRIVACIDADE_MS = 3 * 60 * 1000L
    private val autoRemover = Runnable { removerTelaPrivacidade("tempo-limite de seguranca atingido") }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "Accessibility Service Conectado e Ativo")

        // Garante que o socket único exista. Os OUVINTES de controle
        // (receber_movimento/receber_tecla/desligar) ficam no SocketManager,
        // pendurados no socket vivo, e despacham para esta instância.
        SocketManager.connect()
        enviarLog("Servico de acessibilidade ATIVO e pronto para receber cliques.")
    }

    /** Chamado pelo SocketManager quando chega 'receber_movimento' do painel. */
    fun onRemoteMovimento(json: JSONObject) {
        val acao = json.optString("acao", "START")
        val percentX = json.optDouble("x", 0.0).toFloat()
        val percentY = json.optDouble("y", 0.0).toFloat()

        val metrics = resources.displayMetrics
        val realX = percentX * metrics.widthPixels
        val realY = percentY * metrics.heightPixels

        // Ações de acessibilidade/gestos na main thread (mais confiável no Android 14+).
        Handler(Looper.getMainLooper()).post { handleRemoteGesture(acao, realX, realY) }
    }

    /** Chamado pelo SocketManager quando chega 'receber_tecla' do painel. */
    fun onRemoteTecla(json: JSONObject) {
        val tipo = json.optString("tipo", "")
        val valor = json.optString("valor", "")
        Handler(Looper.getMainLooper()).post { handleTecla(tipo, valor) }
    }

    /** Chamado pelo SocketManager quando chega 'desligar' do painel. */
    fun pararCaptura() {
        enviarLog("Recebido 'desligar' do painel — encerrando compartilhamento de tela.")
        try {
            stopService(Intent(this, ScreenCaptureService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar ScreenCaptureService: ${e.message}")
        }
    }

    private fun enviarLog(msg: String) {
        try { SocketManager.socket?.emit("log_dispositivo", "[ACES] $msg") } catch (_: Exception) {}
    }

    // ====================================================================
    // TELA DE PRIVACIDADE (overlay opaco durante a sessão de suporte).
    //
    // Regras inegociáveis para isto NÃO virar armadilha:
    //  1) Só ativa APÓS consentimento explícito nesta tela (Permitir/Recusar).
    //  2) Quem segura o aparelho SEMPRE consegue sair: tecla Volume - ou o
    //     tempo-limite de segurança. O controle do dispositivo sempre volta.
    //  3) Não finge estar desligado: mostra um texto de que é proteção do suporte.
    //  4) O preto é NOT_TOUCHABLE: os toques do suporte passam para o app de
    //     baixo (o acesso remoto continua), e a tela apenas oculta o conteúdo.
    // ====================================================================

    /** Chamado pelo SocketManager quando o painel pede a tela de privacidade. */
    fun solicitarTelaPrivacidade() {
        handlerMain.post {
            if (overlayPrivacidade != null) return@post   // já existe
            mostrarConsentimento()
        }
    }

    /** O painel pediu para ocultar a tela (fim da senha). Equivale a sair. */
    fun removerTelaPrivacidadeRemoto() {
        handlerMain.post { removerTelaPrivacidade("painel encerrou a tela de privacidade") }
    }

    private fun dp(valor: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, valor.toFloat(), resources.displayMetrics
    ).toInt()

    /** Passo 1: pede consentimento HONESTO no próprio aparelho. */
    private fun mostrarConsentimento() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#EE000000"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        val titulo = TextView(this).apply {
            text = "🔒 Tela de privacidade"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        val msg = TextView(this).apply {
            text = "A sessão de suporte quer escurecer sua tela por alguns instantes " +
                "para digitar uma informação em sigilo.\n\n" +
                "Se permitir, a tela ficará preta. Você pode voltar a qualquer momento " +
                "pressionando o botão Volume (−)."
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(24))
        }

        val linhaBotoes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val btnRecusar = Button(this).apply {
            text = "Recusar"
            setOnClickListener {
                removerTelaPrivacidade("usuario RECUSOU a tela de privacidade")
            }
        }
        val btnPermitir = Button(this).apply {
            text = "Permitir"
            setOnClickListener { aplicarTelaPreta() }
        }
        linhaBotoes.addView(btnRecusar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        linhaBotoes.addView(btnPermitir, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        container.addView(titulo)
        container.addView(msg)
        container.addView(linhaBotoes)

        // Consentimento PRECISA receber toques (botões) -> focável/tocável.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager?.addView(container, params)
            overlayPrivacidade = container
            enviarLog("Pedido de tela de privacidade exibido ao usuario (aguardando consentimento).")
        } catch (e: Exception) {
            enviarLog("Falha ao exibir consentimento da tela de privacidade: ${e.message}")
        }
    }

    /** Passo 2: usuário permitiu -> tela preta opaca que NÃO bloqueia o acesso remoto. */
    private fun aplicarTelaPreta() {
        // Remove o overlay de consentimento antes de recriar o opaco.
        removerOverlay()

        val preta = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        // Texto discreto: esconde o conteúdo SEM fingir que está desligado.
        val aviso = TextView(this).apply {
            text = "Tela protegida durante o suporte\nVolume (−) para voltar"
            setTextColor(Color.parseColor("#555555"))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        preta.addView(
            aviso,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_TOUCHABLE -> os gestos do suporte atravessam para o app de baixo.
            // NOT_FOCUSABLE -> não rouba foco do campo de texto.
            // KEEP_SCREEN_ON -> aparelho fica ligado e processando.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        try {
            windowManager?.addView(preta, params)
            overlayPrivacidade = preta
            handlerMain.postDelayed(autoRemover, TIMEOUT_PRIVACIDADE_MS)
            enviarLog("Tela de privacidade ATIVADA (com consentimento). Acesso remoto segue ativo.")
            try { SocketManager.socket?.emit("tela_privacidade_estado", "ativa") } catch (_: Exception) {}
        } catch (e: Exception) {
            enviarLog("Falha ao ativar tela preta: ${e.message}")
        }
    }

    private fun removerOverlay() {
        overlayPrivacidade?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayPrivacidade = null
    }

    private fun removerTelaPrivacidade(motivo: String) {
        handlerMain.removeCallbacks(autoRemover)
        if (overlayPrivacidade == null) return
        removerOverlay()
        enviarLog("Tela de privacidade REMOVIDA: $motivo")
        try { SocketManager.socket?.emit("tela_privacidade_estado", "inativa") } catch (_: Exception) {}
    }

    /** Volume (−) é a saída de emergência garantida para quem segura o aparelho. */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (overlayPrivacidade != null &&
            event?.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            removerTelaPrivacidade("usuario pressionou Volume (−)")
            return true // consome a tecla
        }
        return super.onKeyEvent(event)
    }

    private fun handleRemoteGesture(acao: String, x: Float, y: Float) {
        val metrics = resources.displayMetrics
        val boundedX = x.coerceIn(0f, metrics.widthPixels.toFloat() - 1f)
        val boundedY = y.coerceIn(0f, metrics.heightPixels.toFloat() - 1f)

        when (acao.uppercase()) {
            "START" -> {
                activePath = Path().apply { moveTo(boundedX, boundedY) }
                startX = boundedX
                startY = boundedY
            }
            "MOVE" -> activePath?.lineTo(boundedX, boundedY)
            "END" -> {
                val movido = activePath != null &&
                    (kotlin.math.abs(boundedX - startX) > 3f || kotlin.math.abs(boundedY - startY) > 3f)

                // Path SEMPRE com comprimento > 0 (Android 14+ rejeita gesto de tamanho zero).
                val path: Path
                val duration: Long
                if (movido) {
                    path = activePath!!.apply { lineTo(boundedX, boundedY) }
                    duration = 300L
                } else {
                    // Tap: pequeno traço de 1px (equivale a um toque), nunca zero.
                    path = Path().apply {
                        moveTo(startX, startY)
                        lineTo(startX + 1f, startY + 1f)
                    }
                    duration = 60L
                }

                val stroke = GestureDescription.StrokeDescription(path, 0, duration)
                val gesto = GestureDescription.Builder().addStroke(stroke).build()

                val aceito = dispatchGesture(gesto, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        enviarLog("gesto OK (${boundedX.toInt()},${boundedY.toInt()})")
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        enviarLog("gesto CANCELADO (${boundedX.toInt()},${boundedY.toInt()})")
                    }
                }, null)

                if (!aceito) {
                    enviarLog("dispatchGesture recusado (canPerformGestures off?) em (${boundedX.toInt()},${boundedY.toInt()})")
                }
                activePath = null
            }
        }
    }

    /**
     * Injeta teclado no campo de texto atualmente focado.
     *  - tipo "texto": acrescenta o(s) caractere(s) recebido(s).
     *  - tipo "tecla": BACKSPACE (apaga 1), ENTER (nova linha / enviar), ESPACO, TAB.
     */
    private fun handleTecla(tipo: String, valor: String) {
        val node: AccessibilityNodeInfo? =
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (node == null || !node.isEditable) {
            Log.d(TAG, "Nenhum campo de texto editável focado (tecla ignorada: $tipo/$valor)")
            return
        }

        val atual = node.text?.toString() ?: ""

        when (tipo) {
            "texto" -> definirTexto(node, atual + valor)
            "tecla" -> when (valor.uppercase()) {
                "BACKSPACE" -> if (atual.isNotEmpty()) definirTexto(node, atual.dropLast(1))
                "ESPACO", "SPACE" -> definirTexto(node, "$atual ")
                "TAB" -> definirTexto(node, "$atual\t")
                "ENTER" -> {
                    // API 30+: ação de IME (enviar/buscar/próximo). Senão, quebra de linha.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        node.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                        )
                    } else {
                        definirTexto(node, "$atual\n")
                    }
                }
            }
        }
    }

    /** Escreve o texto no campo e posiciona o cursor no fim. */
    private fun definirTexto(node: AccessibilityNodeInfo, texto: String) {
        val argsTexto = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                texto
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, argsTexto)

        // Move o cursor para o final (evita digitar de trás pra frente).
        val argsSel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, texto.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, texto.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, argsSel)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrompido")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Nunca deixar a tela presa no preto se o serviço cair.
        handlerMain.removeCallbacks(autoRemover)
        removerOverlay()
        // NÃO removemos os ouvintes do socket aqui: eles pertencem ao SocketManager
        // e despacham para instance dinamicamente (que fica null quando o serviço cai).
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "Accessibility Service Destruído")
    }

    fun clickAt(x: Float, y: Float, onResult: ((Boolean) -> Unit)? = null) {
        handleRemoteGesture("START", x, y)
        handleRemoteGesture("END", x, y)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300, onResult: ((Boolean) -> Unit)? = null) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gestureBuilder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onResult?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                onResult?.invoke(false)
            }
        }, null)
    }

    fun performGlobalHomeAction(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performGlobalBackAction(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
}
