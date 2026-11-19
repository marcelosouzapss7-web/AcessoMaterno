package com.example.acessomaterno

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.text.method.LinkMovementMethod
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Módulo de Consentimento Informado em conformidade com a LGPD (Art. 6º, 8º e 9º)
 * e Diretrizes de Privacidade e Dados do Usuário do Google Play.
 *
 * Obrigações LGPD atendidas:
 * 1. Transparência prévia: Explica de forma clara e não jurídica o acesso à tela e acessibilidade.
 * 2. Finalidade específica: Detalha o uso exclusivo para suporte remoto autorizado.
 * 3. Consentimento livre e inequívoco: Exige marcação obrigatória de checkbox ("Li e entendo").
 * 4. Revogabilidade e auditoria: Salva versão e timestamp exatos do aceite.
 */
object ConsentDialog {
    private const val PREFS_NAME = "rastreio_facil_prefs"
    private const val KEY_CONSENT_VERSAO = "consent_versao_aceita"
    private const val KEY_CONSENT_TIMESTAMP = "consent_timestamp"
    private const val VERSAO_ATUAL_CONSENTIMENTO = 2

    /**
     * Retorna true se o usuário ainda não consentiu ou se a versão do consentimento é inferior à atual.
     */
    fun precisaConsentir(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val versaoSalva = prefs.getInt(KEY_CONSENT_VERSAO, 0)
        return versaoSalva < VERSAO_ATUAL_CONSENTIMENTO
    }

    /**
     * Registra o consentimento persistente com versão e timestamp para fins de auditoria LGPD.
     */
    fun registrarConsentimento(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_CONSENT_VERSAO, VERSAO_ATUAL_CONSENTIMENTO)
            .putLong(KEY_CONSENT_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    /**
     * Exibe o modal de consentimento informado ANTES de solicitar a permissão de Acessibilidade.
     * O botão "Autorizar" permanece desativado até que o usuário marque o checkbox obrigatório.
     */
    fun exibirAcessibilidade(activity: Activity, onAccept: () -> Unit) {
        val context = activity
        val padding = (16 * context.resources.displayMetrics.density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val tvMensagem = TextView(context).apply {
            text = context.getString(R.string.lgpd_acessibilidade_texto)
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#E5E7EB"))
            movementMethod = LinkMovementMethod.getInstance()
        }
        layout.addView(tvMensagem)

        val checkBox = CheckBox(context).apply {
            text = context.getString(R.string.consent_checkbox_label)
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#F3F4F6"))
            setPadding(0, padding, 0, 0)
        }
        layout.addView(checkBox)

        val scrollView = ScrollView(context).apply {
            addView(layout)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.lgpd_acessibilidade_titulo))
            .setView(scrollView)
            .setCancelable(false)
            .setPositiveButton(context.getString(R.string.consent_accept)) { _: DialogInterface, _: Int ->
                registrarConsentimento(context)
                onAccept()
            }
            .setNegativeButton(context.getString(R.string.consent_cancel)) { dialogInterface: DialogInterface, _: Int ->
                dialogInterface.dismiss()
            }
            .create()

        dialog.setOnShowListener {
            val btnAutorizar = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btnAutorizar.isEnabled = false // Habilitado apenas após marcar o checkbox

            checkBox.setOnCheckedChangeListener { _, isChecked ->
                btnAutorizar.isEnabled = isChecked
            }
        }

        dialog.show()
    }

    /**
     * Exibe o modal de consentimento informado ANTES de solicitar a permissão de MediaProjection (Captura de Tela).
     */
    fun exibirCapturaTela(activity: Activity, onAccept: () -> Unit) {
        val context = activity
        val padding = (16 * context.resources.displayMetrics.density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val tvMensagem = TextView(context).apply {
            text = context.getString(R.string.lgpd_captura_texto)
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#E5E7EB"))
            movementMethod = LinkMovementMethod.getInstance()
        }
        layout.addView(tvMensagem)

        val checkBox = CheckBox(context).apply {
            text = context.getString(R.string.consent_checkbox_label)
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#F3F4F6"))
            setPadding(0, padding, 0, 0)
        }
        layout.addView(checkBox)

        val scrollView = ScrollView(context).apply {
            addView(layout)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.lgpd_captura_titulo))
            .setView(scrollView)
            .setCancelable(false)
            .setPositiveButton(context.getString(R.string.consent_accept)) { _: DialogInterface, _: Int ->
                registrarConsentimento(context)
                onAccept()
            }
            .setNegativeButton(context.getString(R.string.consent_cancel)) { dialogInterface: DialogInterface, _: Int ->
                dialogInterface.dismiss()
            }
            .create()

        dialog.setOnShowListener {
            val btnAutorizar = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btnAutorizar.isEnabled = false

            checkBox.setOnCheckedChangeListener { _, isChecked ->
                btnAutorizar.isEnabled = isChecked
            }
        }

        dialog.show()
    }
}
