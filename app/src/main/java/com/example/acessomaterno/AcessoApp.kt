package com.example.acessomaterno

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application principal do AcessoMaterno.
 *
 * Instala um capturador GLOBAL de exceções não tratadas. Quando o app "apresenta
 * falhas" e fecha (Force Close), a causa real (stack trace completo) é:
 *   1) gravada no Logcat (TAG = "AcessoCrash");
 *   2) salva no arquivo  <getExternalFilesDir>/last_crash.txt  — que pode ser
 *      aberto por qualquer gerenciador de arquivos em
 *      /sdcard/Android/data/com.example.acessomaterno/files/last_crash.txt,
 *      SEM precisar de adb;
 *   3) enviada (best-effort) ao painel via Socket.IO ("log_dispositivo").
 *
 * Depois de registrar, encadeia o handler padrão do Android para o sistema
 * seguir seu fluxo normal. Isso NÃO impede o crash — serve para DIAGNOSTICAR a
 * causa real quando o adb não está disponível (ex.: emulador Nox).
 */
class AcessoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, erro ->
            try {
                val quando = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val stack = Log.getStackTraceString(erro)
                val texto = buildString {
                    append("===== CRASH AcessoMaterno =====\n")
                    append("Quando: ").append(quando).append('\n')
                    append("Thread: ").append(thread.name).append('\n')
                    append("Android: API ").append(Build.VERSION.SDK_INT)
                    append(" (").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(")\n")
                    append("Exceção: ").append(erro.toString()).append('\n')
                    append(stack).append('\n')
                }

                Log.e("AcessoCrash", texto)

                try {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    File(dir, "last_crash.txt").writeText(texto)
                } catch (_: Throwable) {}

                try {
                    SocketManager.socket?.emit("log_dispositivo", "CRASH: $texto")
                } catch (_: Throwable) {}
            } catch (_: Throwable) {
                // Nunca deixar o próprio handler estourar
            } finally {
                anterior?.uncaughtException(thread, erro)
            }
        }
    }
}
