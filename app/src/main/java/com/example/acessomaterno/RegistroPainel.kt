package com.example.acessomaterno

import android.os.Build
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Registra este dispositivo no painel FastAPI chamando GET /conectar-filho.
 * É isto que faz o aparelho aparecer na lista "Celulares Online" do painel.
 */
object RegistroPainel {
    private const val TAG = "RegistroPainel"

    private val cliente = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Endereço base do painel. Padrão vindo do BuildConfig (10.0.2.2 no debug).
    // Pode ser sobrescrito em tempo de execução (ex.: colando uma URL do ngrok).
    @Volatile
    var baseUrl: String = BuildConfig.PANEL_URL

    fun registrar(
        nome: String = "${Build.MANUFACTURER}-${Build.MODEL}",
        onResultado: ((Boolean) -> Unit)? = null
    ) {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) {
            Log.e(TAG, "baseUrl vazia; registro cancelado.")
            onResultado?.invoke(false)
            return
        }

        val url = "$base/conectar-filho?nome=" + URLEncoder.encode(nome, "UTF-8")
        val requisicao = Request.Builder().url(url).get().build()

        Log.d(TAG, "Registrando no painel: $url")
        cliente.newCall(requisicao).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Falha ao registrar no painel ($url): ${e.message}")
                onResultado?.invoke(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    Log.d(TAG, "Resposta do painel: HTTP ${it.code}")
                    onResultado?.invoke(it.isSuccessful)
                }
            }
        })
    }
}
