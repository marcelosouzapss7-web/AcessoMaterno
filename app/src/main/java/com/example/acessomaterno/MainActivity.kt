package com.example.acessomaterno

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.acessomaterno.ui.theme.AcessoMaternoTheme
import com.google.firebase.database.FirebaseDatabase

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AcessoMaternoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AccessibilityControlScreen(
                        modifier = Modifier.padding(innerPadding),
                        onOpenSettings = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            startActivity(intent)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilityControlScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var isServiceActive by remember { mutableStateOf(false) }
    var isScreenSharingActive by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Pronto para iniciar o suporte remoto.") }
    var showAccessibilityConsent by remember { mutableStateOf(false) }
    var showScreenConsent by remember { mutableStateOf(false) }
    var painelUrl by remember { mutableStateOf(RegistroPainel.baseUrl) }

    fun registrarNoPainel() {
        RegistroPainel.baseUrl = painelUrl.trim()
        statusMessage = "Registrando no painel..."
        RegistroPainel.registrar { ok ->
            Handler(Looper.getMainLooper()).post {
                statusMessage = if (ok)
                    "Conectado ao painel! O dispositivo deve aparecer na lista."
                else
                    "Falha ao conectar ao painel. Verifique o endereço e se o servidor está ativo."
            }
        }
    }

    fun checkServiceStatus() {
        isServiceActive = isAccessibilityServiceEnabled(context, MyAccessibilityService::class.java)
        isScreenSharingActive = ScreenCaptureService.isRunning
    }

    LaunchedEffect(Unit) {
        checkServiceStatus()
        // Registra automaticamente no painel ao abrir o app.
        registrarNoPainel()
    }

    // Launcher para pedir permissão de captura de tela (MediaProjection)
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            isScreenSharingActive = true
            statusMessage = "Transmissão de tela iniciada com sucesso!"
        } else {
            statusMessage = "Permissão de captura de tela negada."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Acesso Materno - Remoto & Visual") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Card de Conexão com o Painel (registro via /conectar-filho)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Conexão com o Painel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = painelUrl,
                        onValueChange = { painelUrl = it },
                        label = { Text("Endereço do painel") },
                        placeholder = { Text("http://10.0.2.2:27103 ou URL do ngrok") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { registrarNoPainel() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔗 Conectar ao Painel")
                    }
                }
            }

            // Card de Status do Serviço de Acessibilidade
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isServiceActive)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(
                                color = if (isServiceActive) Color(0xFF4CAF50) else Color(0xFFE57373),
                                shape = CircleShape
                            )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isServiceActive) "Acessibilidade ATIVA (Toques)" else "Acessibilidade INATIVA",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isServiceActive)
                                "Pronto para receber cliques e gestos do Firebase."
                            else
                                "Ative o serviço para permitir o controle remoto.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Botão para abrir configurações
            Button(
                onClick = { showAccessibilityConsent = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("⚙️ Ativar Serviço nas Configurações")
            }

            // Card de Status da Transmissão de Tela (MediaProjection)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isScreenSharingActive)
                        MaterialTheme.colorScheme.tertiaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isScreenSharingActive) "📺 Tela Sendo Compartilhada" else "Compartilhamento de Tela (Suporte)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Transmite a tela deste aparelho ao painel de suporte. Requer sua autorização e exibe uma notificação enquanto estiver ativo.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = {
                            if (!isScreenSharingActive) {
                                showScreenConsent = true
                            } else {
                                val intent = Intent(context, ScreenCaptureService::class.java)
                                context.stopService(intent)
                                isScreenSharingActive = false
                                statusMessage = "Transmissão de tela encerrada."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isScreenSharingActive) "Parar Transmissão" else "Iniciar Compartilhamento de Tela")
                    }
                }
            }

            // Status e Teste com Firebase
            Text(
                text = "Acesso Materno — Suporte Remoto Assistivo",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )

            Button(
                onClick = {
                    val database = FirebaseDatabase.getInstance()
                    val commandMap = mapOf(
                        "x" to 500.0,
                        "y" to 1000.0,
                        "action" to "click",
                        "timestamp" to System.currentTimeMillis()
                    )
                    database.getReference("commands/current").setValue(commandMap)
                        .addOnSuccessListener {
                            statusMessage = "Comando de teste enviado ao Firebase com sucesso!"
                        }
                        .addOnFailureListener { e ->
                            statusMessage = "Erro ao enviar ao Firebase: ${e.message}"
                        }
                },
                enabled = isServiceActive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Testar Envio de Clique via Firebase (500, 1000)")
            }

            OutlinedButton(
                onClick = { checkServiceStatus() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔄 Atualizar Status")
            }

            // Diálogo de consentimento: controle remoto via Acessibilidade
            if (showAccessibilityConsent) {
                AlertDialog(
                    onDismissRequest = { showAccessibilityConsent = false },
                    title = { Text(stringResource(R.string.consent_title)) },
                    text = { Text(stringResource(R.string.consent_accessibility_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showAccessibilityConsent = false
                            onOpenSettings()
                        }) { Text(stringResource(R.string.consent_accept)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAccessibilityConsent = false }) {
                            Text(stringResource(R.string.consent_cancel))
                        }
                    }
                )
            }

            // Diálogo de consentimento: captura/transmissão de tela (MediaProjection)
            if (showScreenConsent) {
                AlertDialog(
                    onDismissRequest = { showScreenConsent = false },
                    title = { Text(stringResource(R.string.consent_title)) },
                    text = { Text(stringResource(R.string.consent_screen_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showScreenConsent = false
                            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                        }) { Text(stringResource(R.string.consent_accept)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showScreenConsent = false }) {
                            Text(stringResource(R.string.consent_cancel))
                        }
                    }
                )
            }
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<out android.accessibilityservice.AccessibilityService>): Boolean {
    val expectedServiceName = "${context.packageName}/${serviceClass.name}"
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(expectedServiceName, ignoreCase = true)) {
            return true
        }
    }
    return false
}
