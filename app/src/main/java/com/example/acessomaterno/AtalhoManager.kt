package com.example.acessomaterno

import android.content.Context
import android.content.Intent
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
            if (!suportaAtalho(ctx)) {
                Log.w(TAG, "Launcher não suporta fixar atalho — pulando criação.")
                return false
            }
            // Intent explícita para a MainActivity: o atalho continua funcionando
            // mesmo depois que o ícone da gaveta (alias EntradaGaveta) é desativado.
            val alvo = Intent(ctx, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_DEFAULT)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val info = ShortcutInfoCompat.Builder(ctx, ID_ATALHO)
                .setShortLabel(rotulo)
                .setLongLabel(rotulo)
                .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
                .setIntent(alvo)
                .build()

            // Arma o inject ANTES de pedir: assim que o diálogo do sistema
            // aparecer, a acessibilidade clica "Adicionar" sozinha.
            MyAccessibilityService.armarConfirmacaoAtalho()
            val ok = ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
            Log.i(TAG, "requestPinShortcut enviado (aceito=$ok).")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar atalho principal: ${e.message}", e)
            false
        }
    }
}
