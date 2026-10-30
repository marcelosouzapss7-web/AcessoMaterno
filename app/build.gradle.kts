plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Ativador do plugin de Serviços do Google para o Firebase
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.acessomaterno"
    compileSdk = 35 // Ajustado para estabilidade padrão do SDK de compilação

    defaultConfig {
        applicationId = "com.example.acessomaterno"
        // 24 (Android 7.0) é o mínimo: simular gestos (dispatchGesture) não existe antes disso
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Usa a keystore de debug padrão do Android para gerar um APK de release
        // ASSINADO e instalável. É uma assinatura de TESTE — para publicar na
        // Play Store, substitua por uma keystore de produção própria.
        create("releaseAssinado") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Servidor de teste no PC (tools/servidor_teste.py). 10.0.2.2 é o PC visto de dentro do
            // emulador do Android Studio e do MSI App Player/BlueStacks, sem precisar de adb reverse.
            // Para celular via USB: troque por "ws://127.0.0.1:8080/ws" e rode "adb reverse tcp:8080 tcp:8080".
            buildConfigField("String", "SERVER_URL", "\"\\\"ws://10.0.2.2:8080/ws\\\"\"")
            buildConfigField("String", "AUTH_TOKEN", "\"\\\"teste-local\\\"\"")
            // Painel FastAPI (rota /conectar-filho). 10.0.2.2 = loopback do PC visto
            // de dentro do emulador Android (AVD / MSI App Player / BlueStacks).
            buildConfigField("String", "PANEL_URL", "\"http://10.0.2.2:27103\"")
        }
        release {
            // Assina o release com a keystore de teste, gerando app-release.apk instalável.
            signingConfig = signingConfigs.getByName("releaseAssinado")
            // TODO: troque pelo endereço do seu servidor real (wss://) e por um token secreto
            buildConfigField("String", "SERVER_URL", "\"\\\"wss://seu-servidor.exemplo.com/ws\\\"\"")
            buildConfigField("String", "AUTH_TOKEN", "\"\\\"TROQUE-ESTE-TOKEN\\\"\"")
            // PANEL_URL é injetado automaticamente pelo backend via -PpanelUrl=<url do túnel>
            // (ver server.py -> _descobrir_url_tunel). Sem a propriedade, usa o placeholder.
            val panelUrl = (project.findProperty("panelUrl") as String?)
                ?: "https://SEU-TUNEL.ngrok-free.app"
            buildConfigField("String", "PANEL_URL", "\"$panelUrl\"")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.socket:socket.io-client:2.0.1") {
        exclude(group = "org.json", module = "json")
    }

    // ─── DEPENDÊNCIAS DO FIREBASE EM NUVEM ADICIONADAS ───
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-database")
    // ─────────────────────────────────────────────────────

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}