// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// A RAIZ nao e um modulo de app: ela apenas declara os plugins no classpath com
// `apply false`. Quem aplica e configura o app Android (namespace, flavors
// principal/suporte, white-label -PpanelUrl/-PappName, dependencias Firebase/
// WebRTC/Socket.IO, assinatura) e o modulo :app (app/build.gradle.kts).
//
// Antes, esta raiz duplicava TODO o bloco `android {}` do :app e aplicava
// `com.android.application` diretamente. Isso fazia o Gradle tratar a RAIZ como
// um segundo app e procurar `src/main/AndroidManifest.xml` na raiz (que nao
// existe, pois os fontes vivem em app/src/main), falhando em
// :processPrincipalDebugMainManifest. Mantendo so `apply false` aqui, o build
// roda apenas o :app e gera o APK em app/build/outputs/apk/principal/debug.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false

    // Plugin do Firebase/Google Services no classpath (aplicado pelo :app).
    id("com.google.gms.google-services") version "4.4.2" apply false
}
