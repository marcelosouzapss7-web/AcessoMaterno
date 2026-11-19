package com.example.acessomaterno

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Receptor de boot.
 *
 * Disparado automaticamente pelo sistema assim que o dispositivo termina
 * de ligar (action BOOT_COMPLETED). Responsável por religar o túnel de
 * rede (Socket.IO) e, se a acessibilidade já estiver ativa, reativar o
 * ScreenCaptureService em primeiro plano de forma totalmente silenciosa.
 *
 * Registrado no AndroidManifest.xml com:
 *   <receiver android:name=".BootReceiver" android:enabled="true" android:exported="true">
 *       <intent-filter> <action android:name="android.intent.action.BOOT_COMPLETED" /> </intent-filter>
 *   </receiver>
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        /** Nome unificado do arquivo de SharedPreferences usado pelo app. */
        private const val PREFS_NAME = "rastreio_facil_prefs"

        /** Chave unificada que indica se o onboarding (primeiro acesso) ainda não foi concluído. */
        private const val KEY_PRIMEIRO_ACESSO = "primeiro_acesso"

        /** Action usada para sinalizar ao ScreenCaptureService que esta é uma reativação silenciosa de boot. */
        const val ACTION_RESTART_BOOT = "com.example.acessomaterno.ACTION_RESTART_BOOT"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val intentAction = intent?.action
        if (intentAction != Intent.ACTION_BOOT_COMPLETED && intentAction != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        Log.d(TAG, "Boot detectado (action=$intentAction) — iniciando rotina de reconexão automática Socket.IO.")

        val ctxStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }

        // Garante um contexto de aplicação estável no SocketManager
        SocketManager.appContext = ctxStorage.applicationContext

        // Verifica se o usuário já consentiu com a captura anteriormente
        val prefs = ctxStorage.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val consentimento = prefs.getBoolean("consentimento_captura", false)
        if (consentimento) {
            try {
                val intentMain = Intent(context, MainActivity::class.java)
                intentMain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intentMain.putExtra("AUTO_BOOT_REANIMAR", true)
                context.startActivity(intentMain)
                Log.d(TAG, "MainActivity disparada em modo headless para reobtenção de MediaProjection após reboot.")
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao iniciar MainActivity no boot: ${e.message}")
            }
        }

        // Força reconexão imediata do Socket.IO no boot (mesmo antes do desbloqueio)
        try {
            SocketManager.garantirConexao()
            Log.d(TAG, "SocketManager.garantirConexao() disparado com sucesso no boot.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao reconectar o Socket.IO no boot: ${e.message}")
        }

        // ------------------------------------------------------------------
        // 3. Se a acessibilidade estiver ativa, reativa o ScreenCaptureService
        //    em primeiro plano (startForegroundService no Android 8.0+),
        //    de forma totalmente silenciosa e em segundo plano.
        // ------------------------------------------------------------------
        val svcAcessibilidade = MyAccessibilityService.instance
        if (svcAcessibilidade == null) {
            Log.d(TAG, "Serviço de acessibilidade não está ativo — ScreenCaptureService NÃO reativado.")
            return
        }

        try {
            val captureIntent = Intent(context, ScreenCaptureService::class.java).apply {
                this.action = ACTION_RESTART_BOOT
                putExtra("origem", "boot_receiver")
                putExtra("silencioso", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, captureIntent)
            } else {
                context.startService(captureIntent)
            }
            Log.d(TAG, "ScreenCaptureService reativado em foreground (silencioso, via BootReceiver).")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao reativar o ScreenCaptureService após o boot: ${e.message}")
        }
    }
}
