plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.25"
}

// Reown 1.4.1's ChaChaPolyCodec throws "OutputLengthException: Output buffer too
// short" when decrypting relay messages against bcprov 1.78.x (stricter AEAD
// output-buffer handling). Reown's own BOM pulls 1.78.1; pin back to 1.77.
configurations.all {
    resolutionStrategy {
        force("org.bouncycastle:bcprov-jdk18on:1.77")
    }
}

android {
    namespace = "com.autonomi.examples.antdemo"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.autonomi.examples.antdemo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // Published SDK from the ant-maven repo (see settings.gradle.kts). The POM
    // brings JNA + kotlinx-coroutines-core transitively.
    implementation("com.autonomi:ant-android:0.0.4")
    // coroutines-android (Dispatchers.Main) isn't transitive from the SDK.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose UI.
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // WalletConnect spike — Reown AppKit (successor to Web3Modal), same stack
    // family as the desktop app. BOM 1.4.1 is the latest stable on Maven
    // Central (com.reown); artifacts resolve from the mavenCentral() already
    // declared in settings.gradle.kts.
    implementation(platform("com.reown:android-bom:1.4.1"))
    implementation("com.reown:android-core")
    implementation("com.reown:appkit")
    // The AppKit modal presents itself through Compose navigation: it plugs an
    // `appKitGraph` into a NavHost that uses a bottom-sheet navigator
    // (androidx.compose.material.navigation, versioned by the Compose BOM).
    implementation("androidx.navigation:navigation-compose:2.8.0")
    // AppKit's `appKitGraph` (appkit 1.4.1) hosts the modal via ACCOMPANIST's
    // navigation-material `bottomSheet {}`, so the NavController must register
    // Accompanist's BottomSheetNavigator (not the newer androidx
    // material-navigation one — appkit's `develop` sample uses that, but the
    // 1.4.1 release still uses accompanist). Pin the exact version appkit pulls.
    implementation("com.google.accompanist:accompanist-navigation-material:0.34.0")
    implementation("androidx.compose.material:material")
}
