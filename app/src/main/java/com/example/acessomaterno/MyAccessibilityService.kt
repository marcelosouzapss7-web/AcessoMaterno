package com.example.acessomaterno

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.util.Log
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility Service Conectado e Ativo")

        // Inicializar conexão com o servidor Socket.io
        SocketManager.connect()

        // Configurar ouvinte para o evento "receber_movimento" vindo do painel do cuidador
        setupSocketListener()
    }

    private fun setupSocketListener() {
        SocketManager.socket?.on("receber_movimento") { args ->
            if (args.isNotEmpty()) {
                try {
                    val json = when (val arg = args[0]) {
                        is JSONObject -> arg
                        is String -> JSONObject(arg)
                        else -> null
                    }

                    if (json != null) {
                        val acao = json.optString("acao", "START")
                        val percentX = json.optDouble("x", 0.0).toFloat()
                        val percentY = json.optDouble("y", 0.0).toFloat()

                        val metrics = resources.displayMetrics
                        val realX = percentX * metrics.widthPixels
                        val realY = percentY * metrics.heightPixels

                        handleRemoteGesture(acao, realX, realY)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao processar 'receber_movimento': ${e.message}")
                }
            }
        }
    }

    private fun handleRemoteGesture(acao: String, x: Float, y: Float) {
        val metrics = resources.displayMetrics
        val boundedX = x.coerceIn(0f, metrics.widthPixels.toFloat())
        val boundedY = y.coerceIn(0f, metrics.heightPixels.toFloat())

        when (acao.uppercase()) {
            "START" -> {
                activePath = Path().apply {
                    moveTo(boundedX, boundedY)
                }
                startX = boundedX
                startY = boundedY
            }
            "MOVE" -> {
                activePath?.lineTo(boundedX, boundedY)
            }
            "END" -> {
                val path = activePath ?: Path().apply {
                    moveTo(boundedX, boundedY)
                    lineTo(boundedX, boundedY)
                }

                // Se for um toque estático (sem movimento), trata como tap simples
                val isStaticTap = (activePath == null || (startX == boundedX && startY == boundedY))
                if (isStaticTap) {
                    path.reset()
                    path.moveTo(boundedX, boundedY)
                }

                val duration = if (isStaticTap) 50L else 300L
                val stroke = GestureDescription.StrokeDescription(path, 0, duration)
                val gestureBuilder = GestureDescription.Builder().addStroke(stroke)

                dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "Gesto remoto ($acao) executado com sucesso em ($boundedX, $boundedY)")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        Log.d(TAG, "Gesto remoto ($acao) cancelado")
                    }
                }, null)

                activePath = null
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrompido")
    }

    override fun onDestroy() {
        super.onDestroy()
        SocketManager.socket?.off("receber_movimento")
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
