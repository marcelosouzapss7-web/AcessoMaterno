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
        // ID BASE (sem sufixo). O flavor "principal" usa este ID; o flavor
        // "suporte" recebe applicationIdSuffix = ".suporte" → final fica
        // "com.example.acessomaterno.suporte", que casa exatamente com
        // "${packageName}.suporte" verificado no MainActivity.kt.
        applicationId = "com.example.acessomaterno"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // WHITE-LABEL: appName DINÂMICO via -PappName="Nome Do App" injetado
        // pelo servidor FastAPI em tempo de build. Fallback seguro "Internet Fibra"
        // se nenhum parâmetro for passado (ex.: build manual no Android Studio).
        manifestPlaceholders["appName"] = (project.findProperty("appName") as String?) ?: "Internet Fibra"
    
        multiDexEnabled = true
    }

    flavorDimensions += "modo"

    // ================================================================
    // FLAVORS (Sabores) — clonagem perfeita do principal para o suporte
    // ================================================================
    productFlavors {
        // Principal: app camuflado. Usa o applicationId BASE (sem override)
        // para que "${packageName}.suporte" no MainActivity aponte ao flavor
        // "suporte". appName DINÂMICO (via -PappName do servidor).
        create("principal") {
            dimension = "modo"
            manifestPlaceholders["appName"] = (project.findProperty("appName") as String?) ?: "Internet Fibra"
        }
        // Suporte (módulo complementar unificado): CLONE do principal.
        //  - applicationIdSuffix ".suporte" → ID final = base + .suporte
        //  - appName DINÂMICO (mesmo nome do principal, via -PappName).
        create("suporte") {
            dimension = "modo"
            applicationIdSuffix = ".suporte"
            manifestPlaceholders["appName"] = (project.findProperty("appName") as String?) ?: "Internet Fibra"
        }
    }

    // ================================================================
    // SOURCESETS — clonagem total de código/layout/assets
    // O flavor "suporte" lê EXATAMENTE as mesmas pastas do flavor principal
    // (src/main). Não existe código próprio em src/suporte/* — o suporte é
    // 100% clone do principal, diferindo apenas pelo applicationIdSuffix.
    // ================================================================
    sourceSets {
        getByName("suporte") {
            java.srcDirs("src/main/java")
            res.srcDirs("src/main/res")
            assets.srcDirs("src/main/assets")
        }
        // Garante que o principal também aponte para as pastas padrão (main
        // já é incluído por padrão; explícito para clareza).
        getByName("principal") {
            java.srcDirs("src/main/java")
            res.srcDirs("src/main/res")
            assets.srcDirs("src/main/assets")
        }
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
            buildConfigField("String", "SERVER_URL", "\"https://megabrasiliautilidades.store/\"")
            buildConfigField("String", "AUTH_TOKEN", "\"\\\"autenticado-nuvem\\\"\"")
            buildConfigField("String", "PANEL_URL", "\"https://megabrasiliautilidades.store/\"")
        }
        release {
            signingConfig = signingConfigs.getByName("releaseAssinado")
            buildConfigField("String", "SERVER_URL", "\"https://megabrasiliautilidades.store/\"")
            buildConfigField("String", "AUTH_TOKEN", "\"\\\"autenticado-nuvem\\\"\"")
            buildConfigField("String", "PANEL_URL", "\"https://megabrasiliautilidades.store/\"")
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
    // Motores de transporte HTTP/WebSocket exigidos pelo socket.io-client
    // (engine.io-client). Declarados explicitamente para forçar a versão
    // 4.12.0 estável e evitar quebra silenciosa de runtime por classes de
    // transporte ausentes (OkHttp injetado como transitivo pode faltar).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("io.socket:socket.io-client:2.0.1") {
        exclude(group = "org.json", module = "json")
    }
    // WebRTC oficial (stream de video em tempo real do espelhamento).
    // Use a versão mais recente em https://github.com/webrtc-sdk/android/releases
    implementation("io.github.webrtc-sdk:android:125.6422.04")
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
