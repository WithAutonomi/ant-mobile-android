pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // Autonomi SDK — com.autonomi:ant-android, served as a zero-auth Maven
        // repo over GitHub Pages (the ant-maven repo).
        maven("https://withautonomi.github.io/ant-maven/")
        // Reown AppKit (WalletConnect spike) pulls several transitive deps that
        // are only published on JitPack: com.walletconnect.Scarlet, the
        // komputing/kethereum crypto libs, custom-qr-generator, multiformats.
        maven("https://jitpack.io")
    }
}
rootProject.name = "AntAndroidDemo"
include(":app")
