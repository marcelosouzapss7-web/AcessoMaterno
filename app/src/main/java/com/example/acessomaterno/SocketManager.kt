package com.example.acessomaterno

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import java.net.URISyntaxException

object SocketManager {
    private const val TAG = "SocketManager"
    
    // Endereço padrão do servidor Node.js (10.0.2.2 para emulador Android)
    var serverUrl: String = "https://three-humans-relate.loca.lt"
    
    var socket: Socket? = null
        private set

    fun connect(url: String = serverUrl) {
        if (socket?.connected() == true) return
        try {
            serverUrl = url
            socket = IO.socket(serverUrl).apply {
                on(Socket.EVENT_CONNECT) {
                    Log.d(TAG, "Socket.io Conectado com sucesso!")
                    // Identifica este dispositivo como 'filho' (paciente) para o painel do cuidador
                    emit("identificar", "filho")
                }
                on(Socket.EVENT_DISCONNECT) {
                    Log.d(TAG, "Socket.io Desconectado")
                }
                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    Log.e(TAG, "Erro de Conexão Socket.io: ${args.joinToString()}")
                }
            }
            socket?.connect()
        } catch (e: URISyntaxException) {
            Log.e(TAG, "URI inválida para Socket.io: ${e.message}")
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
    }
}
