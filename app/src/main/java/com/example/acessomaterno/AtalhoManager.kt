package com.example.acessomaterno

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * ETAPA 3 — Atalho do aplicativo principal na gaveta.
 * ===================================================
 * Cria, de forma automática, um ATALHO (pinned shortcut) que abre a
 * MainActivity. O atalho fica visível para o usuário (gaveta/tela), enquanto
 * o ÍCONE original do app é ocultado da gaveta (ver
 * MainActivity.ocultarIconeDaGaveta) — o principal passa a ser acessível
 * apenas pelas Configurações do Android.
 *
 * O aceite é persistido em 'atalho_confirmado' (SharedPreferences) para NÃO
 * pedir de novo a cada abertura. O diálogo do sistema "Adicionar à tela
 * inicial" é confirmado automaticamente pelo MyAccessibilityService (inject),
 * de modo que o operador não precisa ficar clicando toda vez.
 */
object AtalhoManager {

    private const val TAG = "AtalhoManager"
    const val ID_ATALHO = "atalho_principal"

    /** True se o aceite do atalho ainda não foi registrado. */
    fun precisaCriarAtalho(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("rastreio_facil_prefs", Context.MODE_PRIVATE)
        return !prefs.getBoolean("atalho_confirmado", false)
    }

    /** Marca o aceite como registrado (não pedir mais). */
    fun marcarConfirmado(ctx: Context) {
        ctx.getSharedPreferences("rastreio_facil_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("atalho_confirmado", true).apply()
    }

    /** O launcher suporta fixar atalhos? (Android 8+ e launchers compatíveis.) */
    fun suportaAtalho(ctx: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)

    /**
     * Dispara a criação do atalho que abre a MainActivity. O
     * MyAccessibilityService é "armado" para confirmar o diálogo do sistema
     * automaticamente (inject). Retorna true se o pedido foi enviado.
     */
    fun criarAtalhoPrincipal(ctx: Context, rotulo: String): Boolean {
        return try {
            // Intent explícita para a MainActivity: o atalho continua funcionando
            // mesmo depois que o ícone da gaveta (alias EntradaGaveta) é desativado
            // (a MainActivity NUNCA é desativada; só o alias).
            val alvo = Intent(ctx, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_DEFAULT)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            // Arma o inject ANTES de pedir: assim que o diálogo do sistema
            // aparecer, a acessibilidade clica "Adicionar" sozinha.
            MyAccessibilityService.armarConfirmacaoAtalho()

            // Android 8.0+ (API 26+): ShortcutManager.requestPinShortcut.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && suportaAtalho(ctx)) {
                val info = ShortcutInfoCompat.Builder(ctx, ID_ATALHO)
                    .setShortLabel(rotulo)
                    .setLongLabel(rotulo)
                    .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
                    .setIntent(alvo)
                    .build()
                val ok = ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
                Log.i(TAG, "requestPinShortcut (API26+) enviado (aceito=$ok).")
                if (ok) return true
                // Se o launcher recusou, cai para o broadcast legado abaixo.
            }
            // Android 7 (API 24-25, Nox) OU fallback: broadcast legado
            // com.android.launcher.action.INSTALL_SHORTCUT (funciona em launchers
            // antigos/Nox que não suportam o requestPinShortcut moderno).
            criarAtalhoLegado(ctx, rotulo, alvo)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar atalho principal: ${e.message}", e)
            false
        }
    }

    /** Fallback Android 7 (API 24-25) / launchers antigos: envia o broadcast
     *  legado INSTALL_SHORTCUT com EXTRA_SHORTCUT_INTENT/NAME/ICON_RESOURCE. */
    @Suppress("DEPRECATION")
    private fun criarAtalhoLegado(ctx: Context, rotulo: String, alvo: Intent): Boolean {
        return try {
            val inst = Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
                putExtra(Intent.EXTRA_SHORTCUT_INTENT, alvo)
                putExtra(Intent.EXTRA_SHORTCUT_NAME, rotulo)
                putExtra(
                    Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(ctx, R.mipmap.ic_launcher)
                )
                putExtra("duplicate", false)
            }
            ctx.sendBroadcast(inst)
            Log.i(TAG, "INSTALL_SHORTCUT (legado Android 7/Nox) enviado.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro no atalho legado: ${e.message}", e)
            false
        }
    }
}
